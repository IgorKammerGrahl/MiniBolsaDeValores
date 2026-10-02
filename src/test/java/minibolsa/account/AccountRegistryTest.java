package minibolsa.account;

import static minibolsa.account.Account.INITIAL_CASH;
import static minibolsa.account.Account.INITIAL_SHARES;
import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Asset.VALE3;
import static minibolsa.market.Side.BUY;
import static minibolsa.market.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import minibolsa.market.Asset;
import minibolsa.market.Money;
import minibolsa.market.Order;
import minibolsa.market.OrderBook;
import minibolsa.market.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AccountRegistryTest {

    private final AccountRegistry registry = new AccountRegistry();
    private final Account ana = registry.login("ana");
    private final Account bia = registry.login("bia");
    private final OrderBook book = new OrderBook(PETR4, registry::settle);
    private long nextOrderId = 1;

    private Order order(Account account, Side side, Asset asset, long qty, String price) {
        return new Order(nextOrderId++, account.id(), asset, side, qty, Money.parse(price));
    }

    @Test
    void newAccountStartsWithInitialBalancesAndLoginIsIdempotent() {
        assertEquals(INITIAL_CASH, ana.cashAvailable);
        assertEquals(0, ana.cashReserved);
        for (Asset asset : Asset.values()) {
            assertEquals(INITIAL_SHARES, ana.sharesAvailable[asset.ordinal()]);
        }
        assertSame(ana, registry.login("ana"));
        assertEquals(ana.id() + 1, bia.id());
    }

    @Test
    void buyReservesQuantityTimesLimitPrice() {
        Order buy = order(ana, BUY, PETR4, 100, "38.50");

        assertTrue(registry.reserve(buy));

        assertEquals(INITIAL_CASH - 3850_00, ana.cashAvailable);
        assertEquals(3850_00, ana.cashReserved);
        assertSame(buy, ana.openOrders.get(buy.id()));
    }

    @Test
    void buyWithoutEnoughCashIsRejectedAndChangesNothing() {
        Order buy = order(ana, BUY, PETR4, 2_598, "38.50"); // 100.023,00 > 100.000,00

        assertFalse(registry.reserve(buy));

        assertEquals(INITIAL_CASH, ana.cashAvailable);
        assertEquals(0, ana.cashReserved);
        assertTrue(ana.openOrders.isEmpty());
    }

    @Test
    void hugeQuantityThatWouldOverflowIsRejected() {
        // 2^62 × 38.50 estoura o long; sem multiplyExact o custo "daria a volta" e ficaria pequeno
        assertFalse(registry.reserve(order(ana, BUY, PETR4, 1L << 62, "38.50")));
        assertEquals(INITIAL_CASH, ana.cashAvailable);
    }

    @Test
    void sellReservesSharesOfThatAssetOnly() {
        Order sell = order(ana, SELL, PETR4, 300, "38.50");

        assertTrue(registry.reserve(sell));

        assertEquals(INITIAL_SHARES - 300, ana.sharesAvailable[PETR4.ordinal()]);
        assertEquals(300, ana.sharesReserved[PETR4.ordinal()]);
        assertEquals(INITIAL_SHARES, ana.sharesAvailable[VALE3.ordinal()]);
        assertEquals(INITIAL_CASH, ana.cashAvailable);
    }

    @Test
    void sellWithoutEnoughSharesIsRejected() {
        assertTrue(registry.reserve(order(ana, SELL, PETR4, 600, "38.50")));

        assertFalse(registry.reserve(order(ana, SELL, PETR4, 401, "38.50")));
        assertEquals(400, ana.sharesAvailable[PETR4.ordinal()]);
    }

    @Test
    void cancelReturnsTheReservation() {
        Order buy = order(ana, BUY, PETR4, 100, "38.50");
        Order sell = order(ana, SELL, VALE3, 50, "62.00");
        registry.reserve(buy);
        registry.reserve(sell);

        assertEquals(100, registry.cancel(buy));
        assertEquals(50, registry.cancel(sell));

        assertEquals(INITIAL_CASH, ana.cashAvailable);
        assertEquals(0, ana.cashReserved);
        assertEquals(INITIAL_SHARES, ana.sharesAvailable[VALE3.ordinal()]);
        assertEquals(0, ana.sharesReserved[VALE3.ordinal()]);
        assertTrue(ana.openOrders.isEmpty());
        assertEquals(0, registry.cancel(buy)); // cancelar de novo não devolve nada
        assertEquals(INITIAL_CASH, ana.cashAvailable);
    }

    /** Reserva e manda para o livro, que chama registry.settle a cada negócio. */
    private void place(Order order) {
        assertTrue(registry.reserve(order));
        book.submit(order);
    }

    @Test
    void settlementPaysTheExecutionPriceAndRefundsTheDifference() {
        place(order(bia, SELL, PETR4, 10, "38.00"));
        place(order(ana, BUY, PETR4, 10, "40.00")); // reservou 400.00, executa a 38.00

        assertEquals(INITIAL_CASH - 380_00, ana.cashAvailable); // devolveu os 20.00 de diferença
        assertEquals(0, ana.cashReserved);
        assertEquals(INITIAL_SHARES + 10, ana.sharesAvailable[PETR4.ordinal()]);
        assertEquals(INITIAL_CASH + 380_00, bia.cashAvailable);
        assertEquals(INITIAL_SHARES - 10, bia.sharesAvailable[PETR4.ordinal()]);
        assertEquals(0, bia.sharesReserved[PETR4.ordinal()]);
        assertTrue(ana.openOrders.isEmpty());
        assertTrue(bia.openOrders.isEmpty());
    }

    @Test
    void partialSettlementKeepsTheReservationOfWhatIsLeft() {
        Order buy = order(ana, BUY, PETR4, 100, "38.50");
        place(buy);
        place(order(bia, SELL, PETR4, 30, "38.50"));

        assertEquals(70, buy.remaining());
        assertEquals(70 * 3850, ana.cashReserved);
        assertSame(buy, ana.openOrders.get(buy.id()));
        assertEquals(INITIAL_CASH + 30 * 3850, bia.cashAvailable);
        assertTrue(bia.openOrders.isEmpty());
    }

    @Test
    @Timeout(5)
    void selfTradeSettlesWithTheReentrantLock() {
        place(order(ana, SELL, PETR4, 10, "38.50"));
        place(order(ana, BUY, PETR4, 10, "38.50"));

        assertEquals(INITIAL_CASH, ana.cashAvailable);
        assertEquals(0, ana.cashReserved);
        assertEquals(INITIAL_SHARES, ana.sharesAvailable[PETR4.ordinal()]);
        assertEquals(0, ana.sharesReserved[PETR4.ordinal()]);
        assertTrue(ana.openOrders.isEmpty());
    }
}
