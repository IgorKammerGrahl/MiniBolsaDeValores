package minibolsa.engine;

import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Side.BUY;
import static minibolsa.market.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.engine.Exchange.Strategy;
import minibolsa.engine.OrderRejectedException.Reason;
import minibolsa.market.Order;
import minibolsa.market.Trade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** O mesmo comportamento nas duas estratégias. */
class ExchangeTest {

    private final AccountRegistry registry = new AccountRegistry();
    private final Account ana = registry.login("ana");
    private final Account bia = registry.login("bia");

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void ordersFromDifferentAccountsMatchAndSettle(Strategy strategy) {
        try (Exchange exchange = new Exchange(registry, strategy)) {
            Order sell = exchange.reserve(bia, PETR4, SELL, 100, 3850);
            assertEquals(List.of(), exchange.submit(sell).join());

            List<Trade> trades = exchange.submit(exchange.reserve(ana, PETR4, BUY, 60, 3900)).join();

            assertEquals(1, trades.size());
            assertSame(sell, trades.getFirst().sell());
            assertEquals(60, trades.getFirst().quantity());
            assertEquals(3850, trades.getFirst().price());
            assertEquals(40, sell.remaining());
            assertEquals(List.of(), exchange.checkInvariants());
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void reservationFailureRejectsTheOrder(Strategy strategy) {
        try (Exchange exchange = new Exchange(registry, strategy)) {
            OrderRejectedException noCash = assertThrows(OrderRejectedException.class,
                    () -> exchange.reserve(ana, PETR4, BUY, 10_000, 3850));
            OrderRejectedException noShares = assertThrows(OrderRejectedException.class,
                    () -> exchange.reserve(ana, PETR4, SELL, 1_001, 3850));

            assertEquals(Reason.INSUFFICIENT_CASH, noCash.reason());
            assertEquals(Reason.INSUFFICIENT_SHARES, noShares.reason());
            assertEquals(List.of(), exchange.checkInvariants());
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void cancelReturnsWhatIsLeftOnceAndOnlyToTheOwner(Strategy strategy) {
        try (Exchange exchange = new Exchange(registry, strategy)) {
            Order buy = exchange.reserve(ana, PETR4, BUY, 100, 3800);
            exchange.submit(buy).join();
            exchange.submit(exchange.reserve(bia, PETR4, SELL, 30, 3800)).join(); // executa 30

            assertEquals(0, exchange.cancel(bia, buy.id()).join()); // não é da bia
            assertEquals(70, exchange.cancel(ana, buy.id()).join());
            assertEquals(0, exchange.cancel(ana, buy.id()).join()); // já cancelada
            assertEquals(0, exchange.cancel(ana, 999).join()); // não existe

            assertTrue(exchange.submit(exchange.reserve(bia, PETR4, SELL, 10, 3800)).join().isEmpty(),
                    "a ordem cancelada continuou no livro");
            assertEquals(List.of(), exchange.checkInvariants());
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void cancelThatArrivesBeforeTheOrderWins(Strategy strategy) {
        // Duas sessões da mesma conta: uma reservou a ordem e a outra a cancela antes do envio.
        try (Exchange exchange = new Exchange(registry, strategy)) {
            Order buy = exchange.reserve(ana, PETR4, BUY, 100, 3800);

            assertEquals(100, exchange.cancel(ana, buy.id()).join());
            assertEquals(List.of(), exchange.submit(buy).join());

            assertTrue(exchange.submit(exchange.reserve(bia, PETR4, SELL, 10, 3800)).join().isEmpty(),
                    "a ordem cancelada entrou no livro");
            assertEquals(List.of(), exchange.checkInvariants());
        }
    }
}
