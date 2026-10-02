package minibolsa.market;

import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Side.BUY;
import static minibolsa.market.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final OrderBook book = new OrderBook(PETR4, Trade::fillOrders);
    private long nextId = 1;

    private Order order(long accountId, Side side, long qty, String price) {
        return new Order(nextId++, accountId, PETR4, side, qty, Money.parse(price));
    }

    private Order buy(long qty, String price) {
        return order(1, BUY, qty, price);
    }

    private Order sell(long qty, String price) {
        return order(2, SELL, qty, price);
    }

    @Test
    void ordersThatDoNotCrossRestInTheBook() {
        assertTrue(book.submit(buy(100, "38.00")).isEmpty());
        assertTrue(book.submit(sell(100, "39.00")).isEmpty());
        assertEquals(3800, book.bestBid().getAsLong());
        assertEquals(3900, book.bestAsk().getAsLong());
    }

    @Test
    void fullExecutionEmptiesTheBook() {
        Order sell = sell(100, "38.50");
        book.submit(sell);
        Order buy = buy(100, "38.50");

        assertEquals(List.of(new Trade(PETR4, buy, sell, 100, 3850)), book.submit(buy));
        assertEquals(0, buy.remaining());
        assertEquals(0, sell.remaining());
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void partialExecutionKeepsTheRestOfTheRestingOrder() {
        Order sell = sell(100, "38.50");
        book.submit(sell);

        book.submit(buy(30, "38.50"));
        assertEquals(70, sell.remaining());
        assertEquals(3850, book.bestAsk().getAsLong());

        assertEquals(70, book.submit(buy(70, "38.50")).getFirst().quantity());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void partialExecutionRestsTheRestOfTheIncomingOrderAtItsLimit() {
        book.submit(sell(100, "38.50"));
        Order buy = buy(150, "39.00");

        book.submit(buy);

        assertEquals(50, buy.remaining());
        assertEquals(3900, book.bestBid().getAsLong());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void oneOrderExecutesAgainstSeveralLevels() {
        book.submit(sell(10, "38.00"));
        book.submit(sell(20, "38.50"));
        Order lastHit = sell(30, "39.00");
        book.submit(lastHit);
        book.submit(sell(40, "39.50")); // acima do limite da compra

        List<Trade> trades = book.submit(buy(50, "39.00"));

        assertEquals(List.of(10L, 20L, 20L), trades.stream().map(Trade::quantity).toList());
        assertEquals(List.of(3800L, 3850L, 3900L), trades.stream().map(Trade::price).toList());
        assertEquals(10, lastHit.remaining());
        assertEquals(3900, book.bestAsk().getAsLong());
    }

    @Test
    void executionPriceIsThePriceOfTheRestingOrder() {
        book.submit(sell(10, "38.00"));
        // o comprador aceitaria pagar 40.00, mas paga o preço de quem estava no livro
        assertEquals(3800, book.submit(buy(10, "40.00")).getFirst().price());

        book.submit(buy(10, "40.00"));
        // o vendedor aceitaria 38.00, mas recebe o preço de quem estava no livro
        assertEquals(4000, book.submit(sell(10, "38.00")).getFirst().price());
    }

    @Test
    void betterPriceWinsOverEarlierArrival() {
        book.submit(sell(10, "39.00"));
        Order cheaper = sell(10, "38.00");
        book.submit(cheaper);

        assertSame(cheaper, book.submit(buy(10, "39.00")).getFirst().sell());
    }

    @Test
    void samePriceIsServedInArrivalOrderNotIdOrder() {
        // No motor o id nasce antes de a ordem chegar ao livro; quem decide a prioridade é a chegada.
        Order arrivesFirst = new Order(2, 2, PETR4, SELL, 10, 3850);
        Order arrivesSecond = new Order(1, 2, PETR4, SELL, 10, 3850);
        book.submit(arrivesFirst);
        book.submit(arrivesSecond);

        List<Trade> trades = book.submit(buy(20, "38.50"));

        assertSame(arrivesFirst, trades.get(0).sell());
        assertSame(arrivesSecond, trades.get(1).sell());
        assertTrue(arrivesFirst.arrivalSeq() < arrivesSecond.arrivalSeq());
    }

    @Test
    void removedOrderNoLongerMatches() {
        Order sell = sell(100, "38.50");
        book.submit(sell);

        assertTrue(book.remove(sell));
        assertEquals(100, sell.cancel());
        assertEquals(0, sell.remaining());
        assertTrue(book.bestAsk().isEmpty());
        assertTrue(book.submit(buy(100, "38.50")).isEmpty());
        assertFalse(book.remove(sell));
    }

    @Test
    void filledOrderCannotBeRemoved() {
        Order sell = sell(10, "38.50");
        book.submit(sell);
        book.submit(buy(10, "38.50"));

        assertFalse(book.remove(sell));
    }

    @Test
    void selfTradeIsAllowed() {
        book.submit(order(7, SELL, 10, "38.50"));

        assertEquals(1, book.submit(order(7, BUY, 10, "38.50")).size());
    }

    @Test
    void bookNeverCrossesUnderRandomOrdersAndCancels() {
        Random random = new Random(42);
        List<Order> placed = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            if (!placed.isEmpty() && random.nextInt(10) == 0) {
                Order victim = placed.get(random.nextInt(placed.size()));
                if (book.remove(victim)) {
                    victim.cancel();
                }
            } else {
                long price = PETR4.initialPrice() + random.nextInt(201) - 100; // 37.50 a 39.50
                Order incoming = new Order(nextId++, 1 + random.nextInt(5), PETR4,
                        random.nextBoolean() ? BUY : SELL, 1 + random.nextInt(100), price);
                for (Trade t : book.submit(incoming)) {
                    assertTrue(t.sell().limitPrice() <= t.price() && t.price() <= t.buy().limitPrice(),
                            "negócio fora dos limites: " + t);
                }
                placed.add(incoming);
            }
            assertFalse(book.isCrossed(), "livro cruzado no passo " + i);
        }
    }

    @Test
    void settlementThatDoesNotFillTheOrdersIsRejected() {
        OrderBook broken = new OrderBook(PETR4, trade -> { });
        broken.submit(sell(10, "38.50"));

        assertThrows(IllegalStateException.class, () -> broken.submit(buy(10, "38.50")));
    }

    @Test
    void orderRejectsNonPositiveQuantityOrPrice() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, 1, PETR4, BUY, 0, 3850));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, 1, PETR4, BUY, 10, 0));
    }

    @Test
    void snapshotSumsEachPriceAndLimitsTheDepth() {
        book.submit(buy(10, "38.00"));
        book.submit(buy(5, "38.00"));
        book.submit(buy(7, "37.90"));
        book.submit(buy(1, "37.80"));
        book.submit(sell(3, "38.50"));

        BookSnapshot snapshot = book.snapshot(2);

        assertEquals(List.of(new BookSnapshot.Level(3800, 15), new BookSnapshot.Level(3790, 7)), snapshot.bids());
        assertEquals(List.of(new BookSnapshot.Level(3850, 3)), snapshot.asks());
    }
}
