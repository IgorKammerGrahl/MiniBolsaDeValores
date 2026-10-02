package minibolsa.account;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TransferStressTest {

    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 20_000;

    /**
     * Poucas contas e muitas threads transferindo em todas as direções: A→B e B→A
     * acontecem ao mesmo tempo o tempo todo. Com os locks em ordem de id isso
     * termina; se a ordem dependesse dos argumentos, travaria (e o timeout pegaria).
     */
    @Test
    @Timeout(30)
    void randomCrossTransfersFinishWithoutDeadlockAndConserveMoney() throws Exception {
        AccountRegistry registry = new AccountRegistry();
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            accounts.add(registry.login("conta" + i));
        }
        AtomicLong succeeded = new AtomicLong();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            tasks.add(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    Account from = accounts.get(random.nextInt(accounts.size()));
                    Account to = accounts.get(random.nextInt(accounts.size()));
                    if (registry.transfer(from, to, 1 + random.nextLong(5_000_00))) {
                        succeeded.incrementAndGet();
                    }
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (Future<Void> result : pool.invokeAll(tasks)) {
                result.get(); // repassa qualquer exceção das threads
            }
        } finally {
            pool.shutdownNow(); // no caso do timeout, interrompe quem ficou preso
            assertTrue(pool.awaitTermination(5, SECONDS));
        }

        assertEquals(List.of(), InvariantChecker.check(registry));
        assertTrue(succeeded.get() > THREADS * TRANSFERS_PER_THREAD / 2, "poucas transferências: " + succeeded);
    }

    /**
     * A versão ingênua: cada thread trava a sua origem, espera, e tenta o destino,
     * que a outra thread já travou. As duas ficam esperando para sempre.
     */
    @Test
    @Timeout(10)
    void naiveCrossTransfersDeadlockAndInterruptReleasesThem() throws Exception {
        AccountRegistry registry = new AccountRegistry(false, 0, true, 200); // transferência ingênua, pausa de 200 ms
        Account a = registry.login("a");
        Account b = registry.login("b");
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            start.await();
            return registry.transfer(a, b, 100_00);
        });
        pool.submit(() -> {
            start.await();
            return registry.transfer(b, a, 100_00);
        });

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked;
        while ((deadlocked = threads.findDeadlockedThreads()) == null) {
            Thread.sleep(20);
        }
        assertEquals(2, deadlocked.length);

        pool.shutdownNow(); // lockInterruptibly: a interrupção tira as duas threads do deadlock
        assertTrue(pool.awaitTermination(5, SECONDS));
        assertEquals(List.of(), InvariantChecker.check(registry)); // nada foi movido pela metade
        assertEquals(Account.INITIAL_CASH, a.cashAvailable);
    }
}
