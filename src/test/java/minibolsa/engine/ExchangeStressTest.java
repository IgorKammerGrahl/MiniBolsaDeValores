package minibolsa.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.engine.Exchange.Strategy;
import minibolsa.market.Asset;
import minibolsa.market.Order;
import minibolsa.market.Side;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Dezenas de threads mandando milhares de ordens aleatórias, em todos os ativos,
 * com poucas contas compartilhadas. No fim, as cinco invariantes têm de valer.
 *
 * <p>Junta tudo que disputa as contas ao mesmo tempo: reservas (threads
 * produtoras), liquidações (threads do motor), cancelamentos e transferências.
 */
class ExchangeStressTest {

    private static final int PRODUCERS = 32;
    private static final int ACTIONS_PER_PRODUCER = 2_000;
    private static final int ACCOUNTS = 20;

    @ParameterizedTest
    @EnumSource(Strategy.class)
    @Timeout(60)
    void thousandsOfRandomOrdersKeepAllInvariants(Strategy strategy) throws Exception {
        AccountRegistry registry = new AccountRegistry();
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(registry.login("conta" + i));
        }
        AtomicLong trades = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong canceled = new AtomicLong();

        try (Exchange exchange = new Exchange(registry, strategy)) {
            List<Callable<Void>> producers = new ArrayList<>();
            for (int p = 0; p < PRODUCERS; p++) {
                producers.add(() -> {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    List<Order> mine = new ArrayList<>();
                    for (int i = 0; i < ACTIONS_PER_PRODUCER; i++) {
                        int dice = random.nextInt(100);
                        if (dice < 10 && !mine.isEmpty()) {
                            Order victim = mine.remove(random.nextInt(mine.size()));
                            Account owner = registry.byId(victim.accountId());
                            if (exchange.cancel(owner, victim.id()).join() > 0) {
                                canceled.incrementAndGet();
                            }
                        } else if (dice < 15) {
                            Account from = accounts.get(random.nextInt(ACCOUNTS));
                            Account to = accounts.get(random.nextInt(ACCOUNTS));
                            registry.transfer(from, to, 1 + random.nextLong(1_000_00));
                        } else {
                            Account account = accounts.get(random.nextInt(ACCOUNTS));
                            Asset asset = Asset.values()[random.nextInt(Asset.values().length)];
                            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                            // preço até 2% acima ou abaixo do inicial, para as ordens cruzarem bastante
                            long price = asset.initialPrice() + asset.initialPrice() * (random.nextInt(401) - 200) / 10_000;
                            try {
                                Order order = exchange.reserve(account, asset, side, 1 + random.nextInt(100), price);
                                trades.addAndGet(exchange.submit(order).join().size());
                                mine.add(order);
                            } catch (OrderRejectedException e) {
                                rejected.incrementAndGet();
                            }
                        }
                    }
                    return null;
                });
            }

            ExecutorService pool = Executors.newFixedThreadPool(PRODUCERS);
            try {
                for (Future<Void> result : pool.invokeAll(producers)) {
                    result.get(); // repassa qualquer exceção das threads
                }
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, SECONDS));
            }

            assertEquals(List.of(), exchange.checkInvariants());
        }
        // o teste só prova algo se houve muito negócio, recusa e cancelamento de verdade
        assertTrue(trades.get() > 10_000, "poucos negócios: " + trades);
        assertTrue(rejected.get() > 0, "nenhuma ordem recusada");
        assertTrue(canceled.get() > 500, "poucos cancelamentos: " + canceled);
    }
}
