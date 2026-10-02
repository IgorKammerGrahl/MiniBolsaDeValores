package minibolsa.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Duas transferências cruzadas ao mesmo tempo (ana → bia e bia → ana). Na
 * versão ingênua, cada uma trava a sua origem, espera a pausa e fica presa
 * esperando a outra: o watchdog tem de acusar o deadlock.
 */
@Timeout(30)
class DeadlockWatchdogTest {

    private static void crossTransfers(TestClient ana, TestClient bia) throws IOException {
        ana.request("LOGIN ana");
        bia.request("LOGIN bia");
        ana.send("TRANSFER bia 10.00");
        bia.send("TRANSFER ana 10.00");
    }

    @Test
    void watchdogDetectsTheDeadlockOfCrossedNaiveTransfers() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--naive-transfer",
                "--transfer-pause-ms", "500"))) {
            server.start();
            try (TestClient ana = new TestClient(server.port());
                 TestClient bia = new TestClient(server.port());
                 TestClient observer = new TestClient(server.port())) {
                observer.request("LOGIN observador");
                crossTransfers(ana, bia);

                while (observer.stat("deadlocks") != 1) { // o @Timeout pega se o watchdog nunca acusar
                    Thread.sleep(100);
                }
            }
        } // ao fechar, o shutdownNow do pool de transferências interrompe as duas threads presas
    }

    @Test
    void orderedLocksNeverDeadlockWithTheSameCrossedTransfers() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--transfer-pause-ms", "500"))) {
            server.start();
            try (TestClient ana = new TestClient(server.port());
                 TestClient bia = new TestClient(server.port())) {
                crossTransfers(ana, bia);

                assertEquals("OK TRANSFER bia 10.00", ana.read());
                assertEquals("OK TRANSFER ana 10.00", bia.read());
                Thread.sleep(1_500); // tempo para o watchdog olhar pelo menos uma vez
                assertEquals(0, ana.stat("deadlocks"));
            }
        }
    }
}
