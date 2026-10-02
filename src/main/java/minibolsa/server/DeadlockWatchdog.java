package minibolsa.server;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;

/**
 * Watchdog de deadlock: a cada segundo pergunta à JVM
 * ({@link ThreadMXBean#findDeadlockedThreads()}) se há threads esperando umas
 * pelas outras em ciclo e, se houver, loga quem espera qual lock e quem o segura.
 *
 * <p>O ThreadMXBean só enxerga threads de plataforma. É por isso que a
 * transferência, a operação que pode entrar em deadlock, roda num pool de
 * plataforma e não na virtual thread da sessão.
 */
final class DeadlockWatchdog implements AutoCloseable {

    private static final int STACK_DEPTH = 20;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("watchdog").factory());
    /** Threads já denunciadas, para não repetir o aviso a cada segundo. Só a thread do watchdog mexe. */
    private final Set<Long> reported = new HashSet<>();
    private final AtomicLong deadlocks = new AtomicLong();

    void start() {
        scheduler.scheduleAtFixedRate(this::check, 1, 1, TimeUnit.SECONDS);
    }

    /** Quantos deadlocks diferentes já foram encontrados. */
    long deadlocks() {
        return deadlocks.get();
    }

    private void check() {
        // Uma exceção que escapasse daqui faria o agendador parar de chamar a tarefa, em silêncio.
        try {
            long[] ids = threads.findDeadlockedThreads();
            if (ids == null) {
                return;
            }
            List<ThreadInfo> fresh = new ArrayList<>();
            for (ThreadInfo info : threads.getThreadInfo(ids, STACK_DEPTH)) {
                if (info != null && reported.add(info.getThreadId())) {
                    fresh.add(info);
                }
            }
            if (fresh.isEmpty()) {
                return;
            }
            deadlocks.incrementAndGet();
            Log.error("!!! DEADLOCK: " + fresh.size() + " threads paradas para sempre,"
                    + " cada uma esperando um lock que outra segura !!!");
            for (ThreadInfo info : fresh) {
                Log.error("!!!   " + info.getThreadName() + " espera " + info.getLockName()
                        + " (que está com " + info.getLockOwnerName() + "), parada em " + whereInOurCode(info));
            }
        } catch (RuntimeException e) {
            Log.error("watchdog falhou: " + e);
        }
    }

    /**
     * Os dois pontos mais altos da pilha que são código do projeto, ex.:
     * {@code AccountRegistry.lockInterruptibly(AccountRegistry.java:262) ← AccountRegistry.transfer(...)}.
     */
    private static String whereInOurCode(ThreadInfo info) {
        List<String> frames = new ArrayList<>();
        for (StackTraceElement frame : info.getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("minibolsa.") && frames.size() < 2) {
                frames.add(className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");
            }
        }
        return frames.isEmpty() ? "?" : String.join(" ← ", frames);
    }

    @Override
    public void close() {
        ExecutorShutdown.shutdownAndAwait(scheduler, "watchdog");
    }
}
