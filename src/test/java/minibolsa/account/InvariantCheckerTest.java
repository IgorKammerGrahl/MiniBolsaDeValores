package minibolsa.account;

import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Side.BUY;
import static minibolsa.market.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import minibolsa.market.Order;
import minibolsa.market.OrderBook;
import org.junit.jupiter.api.Test;

/** Um verificador que nunca reclama passaria em todos os testes; aqui ele é obrigado a reclamar. */
class InvariantCheckerTest {

    private final AccountRegistry registry = new AccountRegistry();
    private final Account ana = registry.login("ana");
    private final Account bia = registry.login("bia");

    @Test
    void consistentStateAfterTradesHasNoViolations() {
        OrderBook book = new OrderBook(PETR4, registry::settle);
        Order sell = new Order(1, bia.id(), PETR4, SELL, 100, 3800);
        Order buy = new Order(2, ana.id(), PETR4, BUY, 30, 4000);
        registry.reserve(sell);
        book.submit(sell);
        registry.reserve(buy);
        book.submit(buy);

        assertEquals(List.of(), InvariantChecker.check(registry));
    }

    @Test
    void detectsNegativeCashAndMoneyCreatedFromNothing() {
        ana.cashAvailable = -1;

        List<String> violations = InvariantChecker.check(registry);

        assertTrue(violations.stream().anyMatch(v -> v.contains("dinheiro negativo")), violations::toString);
        assertTrue(violations.stream().anyMatch(v -> v.contains("dinheiro não se conserva")), violations::toString);
    }

    @Test
    void detectsSharesThatDoNotAddUp() {
        bia.sharesAvailable[PETR4.ordinal()] += 5;

        List<String> violations = InvariantChecker.check(registry);

        assertEquals(List.of("ações de PETR4 não se conservam: total 2005, esperado 2000"), violations);
    }

    @Test
    void detectsReservationThatDoesNotMatchOpenOrders() {
        registry.reserve(new Order(1, ana.id(), PETR4, BUY, 10, 3850));
        ana.cashReserved -= 100; // a reserva "some" sem a ordem mudar
        ana.cashAvailable += 100;

        List<String> violations = InvariantChecker.check(registry);

        assertEquals(List.of("conta 1 (ana): dinheiro reservado 384.00 ≠ compras abertas 385.00"), violations);
    }
}
