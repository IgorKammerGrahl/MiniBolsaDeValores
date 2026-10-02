package minibolsa.market;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Livro de ofertas de um ativo, com prioridade preço-tempo: primeiro o melhor
 * preço e, no mesmo preço, quem chegou antes.
 *
 * <p>Não é thread-safe de propósito. O motor garante que uma só thread mexe no
 * livro: ou ele tem um único dono (a thread do executor do ativo), ou fica
 * atrás do lock global.
 *
 * <p>A cada negócio o livro chama {@code settlement}, e é ela quem desconta a
 * quantidade das ordens ({@link Trade#fillOrders()}). Assim a liquidação muda a
 * quantidade restante e os saldos juntos, com as contas travadas.
 */
public final class OrderBook {

    private final Asset asset;
    private final Consumer<Trade> settlement;
    /** Compras, do maior preço para o menor; em cada preço, fila por ordem de chegada. */
    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    /** Vendas, do menor preço para o maior. */
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private long arrivals;

    public OrderBook(Asset asset, Consumer<Trade> settlement) {
        this.asset = asset;
        this.settlement = settlement;
    }

    /**
     * Casa a ordem com as melhores contrapartes enquanto o preço for compatível.
     * O que sobrar fica no livro. Devolve os negócios na ordem em que aconteceram.
     */
    public List<Trade> submit(Order incoming) {
        if (incoming.asset() != asset) {
            throw new IllegalArgumentException("ordem de " + incoming.asset() + " no livro de " + asset);
        }
        incoming.markArrival(++arrivals);
        boolean buying = incoming.side() == Side.BUY;
        TreeMap<Long, ArrayDeque<Order>> opposite = buying ? asks : bids;
        List<Trade> trades = new ArrayList<>();

        while (incoming.remaining() > 0 && !opposite.isEmpty()) {
            long bestPrice = opposite.firstKey();
            boolean compatible = buying ? bestPrice <= incoming.limitPrice() : bestPrice >= incoming.limitPrice();
            if (!compatible) {
                break;
            }
            ArrayDeque<Order> level = opposite.firstEntry().getValue();
            Order resting = level.peekFirst();
            long qty = Math.min(incoming.remaining(), resting.remaining());
            // Preço de execução: o da ordem que já estava no livro.
            Trade trade = buying
                    ? new Trade(asset, incoming, resting, qty, bestPrice)
                    : new Trade(asset, resting, incoming, qty, bestPrice);

            long before = resting.remaining();
            settlement.accept(trade);
            if (resting.remaining() != before - qty) {
                throw new IllegalStateException("a liquidação não executou o negócio " + trade);
            }
            trades.add(trade);

            if (resting.remaining() == 0) {
                level.pollFirst();
                if (level.isEmpty()) {
                    opposite.pollFirstEntry();
                }
            }
        }

        if (incoming.remaining() > 0) {
            TreeMap<Long, ArrayDeque<Order>> own = buying ? bids : asks;
            own.computeIfAbsent(incoming.limitPrice(), price -> new ArrayDeque<>()).addLast(incoming);
        }
        return trades;
    }

    /**
     * Tira a ordem do livro. Devolve {@code false} se ela não estava lá (já
     * executada ou já removida). Zerar o restante ({@link Order#cancel()}) fica
     * com quem chama, junto com a devolução da reserva.
     */
    public boolean remove(Order order) {
        TreeMap<Long, ArrayDeque<Order>> side = order.side() == Side.BUY ? bids : asks;
        ArrayDeque<Order> level = side.get(order.limitPrice());
        if (level == null || !level.remove(order)) {
            return false;
        }
        if (level.isEmpty()) {
            side.remove(order.limitPrice());
        }
        return true;
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /** Cruzado = melhor compra ≥ melhor venda. Depois de cada {@link #submit} isso nunca acontece. */
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }
}
