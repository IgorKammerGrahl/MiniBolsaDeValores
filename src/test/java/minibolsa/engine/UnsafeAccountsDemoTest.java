package minibolsa.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Side.BUY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.engine.Exchange.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Demonstração da condição de corrida no saldo. Uma conta com R$ 100.000,00
 * manda, ao mesmo tempo, duas compras de R$ 60.000,00 cada.
 *
 * <p>Sem o lock da conta, as duas threads verificam o saldo (100.000 ≥ 60.000),
 * as duas esperam na janela de corrida e as duas debitam: a conta fica negativa.
 * Com o lock, a segunda só verifica depois que a primeira debitou e é recusada.
 */
class UnsafeAccountsDemoTest {

    private static final long RACE_WINDOW_MS = 200;

    private record Outcome(long accepted, List<String> violations) {
    }

    @Test
    @Timeout(10)
    void withoutAccountLocksTheSameMoneyIsSpentTwice() throws Exception {
        Outcome outcome = twoSimultaneousBigBuys(new AccountRegistry(true, RACE_WINDOW_MS, false, 0));

        assertEquals(2, outcome.accepted());
        assertFalse(outcome.violations().isEmpty(), "o modo inseguro deveria violar as invariantes");
        System.out.println("Violações no modo inseguro: " + outcome.violations());
    }

    @Test
    @Timeout(10)
    void withAccountLocksTheSameRaceIsHarmless() throws Exception {
        Outcome outcome = twoSimultaneousBigBuys(new AccountRegistry(false, RACE_WINDOW_MS, false, 0));

        assertEquals(1, outcome.accepted());
        assertEquals(List.of(), outcome.violations());
    }

    private static Outcome twoSimultaneousBigBuys(AccountRegistry registry) throws Exception {
        Account ana = registry.login("ana");
        CyclicBarrier start = new CyclicBarrier(2); // as duas compras saem juntas
        try (Exchange exchange = new Exchange(registry, Strategy.SINGLE_WRITER)) {
            Callable<Boolean> buy = () -> {
                start.await();
                try {
                    exchange.submit(exchange.reserve(ana, PETR4, BUY, 1_000, 60_00)).join(); // 60.000,00
                    return true;
                } catch (OrderRejectedException e) {
                    return false;
                }
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);
            long accepted = 0;
            try {
                for (Future<Boolean> result : pool.invokeAll(List.of(buy, buy))) {
                    accepted += result.get() ? 1 : 0;
                }
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, SECONDS));
            }
            return new Outcome(accepted, exchange.checkInvariants());
        }
    }
}
