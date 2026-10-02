package minibolsa.server;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;
import minibolsa.engine.Exchange;

/**
 * Auditoria periódica ({@code --audit-every N}): a cada N segundos verifica as
 * cinco invariantes com o servidor rodando e loga cada violação bem à vista. É
 * como se vê, ao vivo, o modo inseguro corrompendo saldos.
 *
 * <p>No modo seguro a verificação trava todas as contas (em ordem de id), então
 * vê um estado consistente e nunca acusa à toa. No modo inseguro é "melhor
 * esforço".
 */
final class Auditor implements AutoCloseable {

    private final Exchange exchange;
    private final long periodSeconds;
    private final AtomicLong violations = new AtomicLong();
    /** O que a auditoria anterior achou, para não repetir o mesmo aviso. Só a thread "auditoria" mexe. */
    private Set<String> lastFound = Set.of();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("auditoria").factory());

    /** @param periodSeconds intervalo entre auditorias; 0 desliga */
    Auditor(Exchange exchange, long periodSeconds) {
        this.exchange = exchange;
        this.periodSeconds = periodSeconds;
    }

    void start() {
        if (periodSeconds > 0) {
            scheduler.scheduleAtFixedRate(this::audit, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        }
    }

    /** Violações diferentes encontradas até agora: a mesma, repetida em auditorias seguidas, conta uma vez. */
    long violations() {
        return violations.get();
    }

    private void audit() {
        // Uma exceção que escapasse daqui faria o agendador parar de chamar a tarefa, em silêncio.
        try {
            List<String> found = exchange.checkInvariants();
            List<String> fresh = found.stream().filter(v -> !lastFound.contains(v)).toList();
            lastFound = new HashSet<>(found);
            if (found.isEmpty()) {
                Log.info("Auditoria: as cinco invariantes valem.");
            } else if (fresh.isEmpty()) {
                Log.info("Auditoria: " + found.size() + " violação(ões) já avisada(s) continua(m).");
            } else {
                violations.addAndGet(fresh.size());
                Log.error("!!! AUDITORIA: " + fresh.size() + " violação(ões) nova(s) das invariantes !!!");
                for (String violation : fresh) {
                    Log.error("!!!   " + violation);
                }
            }
        } catch (RuntimeException e) {
            Log.error("auditoria falhou: " + e);
        }
    }

    @Override
    public void close() {
        ExecutorShutdown.shutdownAndAwait(scheduler, "auditoria");
    }
}
