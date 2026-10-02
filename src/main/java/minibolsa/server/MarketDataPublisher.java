package minibolsa.server;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;
import minibolsa.market.Asset;
import minibolsa.market.Trade;

/**
 * Cotações: guarda o último preço e o volume de cada ativo e, a cada segundo,
 * manda um TICK para quem assinou.
 *
 * <p>Quem escreve a cotação é a thread do motor, a cada negócio. Quem lê é a
 * thread "cotacoes", do {@link ScheduledExecutorService}. A cotação é um record
 * imutável trocado inteiro num {@link AtomicReference}: quem lê sempre vê um par
 * (preço, volume) que existiu de verdade, nunca o preço novo com o volume velho.
 */
final class MarketDataPublisher implements AutoCloseable {

    record Quote(long lastPrice, long volume) {
    }

    private final Map<Asset, AtomicReference<Quote>> quotes = new EnumMap<>(Asset.class);
    private final Map<Asset, Set<ClientSession>> subscribers = new EnumMap<>(Asset.class);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("cotacoes").factory());

    MarketDataPublisher() {
        for (Asset asset : Asset.values()) {
            quotes.put(asset, new AtomicReference<>(new Quote(asset.initialPrice(), 0)));
            subscribers.put(asset, ConcurrentHashMap.newKeySet());
        }
    }

    void start() {
        scheduler.scheduleAtFixedRate(this::publish, 1, 1, TimeUnit.SECONDS);
    }

    /** Chamado na thread do motor a cada negócio. */
    void onTrade(Trade trade) {
        quotes.get(trade.asset()).updateAndGet(q -> new Quote(trade.price(), q.volume() + trade.quantity()));
    }

    void subscribe(ClientSession session, Collection<Asset> assets) {
        for (Asset asset : assets) {
            subscribers.get(asset).add(session);
        }
    }

    void unsubscribe(ClientSession session) {
        for (Set<ClientSession> audience : subscribers.values()) {
            audience.remove(session);
        }
    }

    private void publish() {
        // Uma exceção que escapasse daqui faria o agendador parar de chamar a tarefa, em silêncio.
        try {
            for (Asset asset : Asset.values()) {
                Set<ClientSession> audience = subscribers.get(asset);
                if (!audience.isEmpty()) {
                    Quote quote = quotes.get(asset).get();
                    String tick = Protocol.tick(asset, quote.lastPrice(), quote.volume());
                    for (ClientSession session : audience) {
                        session.send(tick); // só enfileira: um assinante lento não atrasa os outros
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.error("falha ao publicar cotações: " + e);
        }
    }

    @Override
    public void close() {
        ExecutorShutdown.shutdownAndAwait(scheduler, "cotações");
    }
}
