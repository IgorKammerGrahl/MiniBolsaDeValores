package minibolsa;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Encerramento padrão de todo executor do projeto: shutdown, espera, e só então shutdownNow. */
public final class ExecutorShutdown {

    private static final long TIMEOUT_SECONDS = 5;

    private ExecutorShutdown() {
    }

    public static void shutdownAndAwait(ExecutorService executor, String name) {
        executor.shutdown(); // não aceita tarefas novas, mas termina as que já estão na fila
        try {
            if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.error(name + ": não terminou em " + TIMEOUT_SECONDS + " s, interrompendo as threads");
                executor.shutdownNow();
                executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
