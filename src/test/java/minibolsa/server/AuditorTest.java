package minibolsa.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A auditoria periódica com o servidor rodando. Dois clientes da mesma conta
 * (R$ 100.000,00) mandam ao mesmo tempo compras de R$ 60.000,00 cada.
 */
@Timeout(20)
class AuditorTest {

    /** Manda as duas compras sem esperar resposta, para elas correrem juntas no servidor. */
    private static void raceTwoBigBuys(Server server) throws IOException {
        try (TestClient ana1 = new TestClient(server.port()); TestClient ana2 = new TestClient(server.port())) {
            ana1.request("LOGIN ana");
            ana2.request("LOGIN ana");
            ana1.send("BUY PETR4 1000 60.00");
            ana2.send("BUY PETR4 1000 60.00");
            ana1.read();
            ana2.read();
        }
    }

    @Test
    void auditCatchesTheRaceOfTheUnsafeMode() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--audit-every", "1",
                "--unsafe-accounts", "--race-window-ms", "200"))) {
            server.start();
            raceTwoBigBuys(server);

            try (TestClient observer = new TestClient(server.port())) {
                observer.request("LOGIN observador");
                long violations;
                while ((violations = observer.stat("violations")) == 0) { // o @Timeout pega se nunca acusar
                    Thread.sleep(100);
                }
                // Dependendo de como as threads se entrelaçaram, a corrida deixa saldo negativo, dinheiro
                // criado do nada (atualização perdida) ou reserva incoerente: 1 a 3 violações. Elas continuam
                // lá nas auditorias seguintes, mas são as mesmas: o contador não pode crescer.
                Thread.sleep(2_500);
                assertEquals(violations, observer.stat("violations"));
            }
        }
    }

    @Test
    void auditFindsNothingInSafeModeWithTheSameRace() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--audit-every", "1",
                "--race-window-ms", "200"))) {
            server.start();
            raceTwoBigBuys(server);
            Thread.sleep(2_500); // tempo para pelo menos duas auditorias

            try (TestClient observer = new TestClient(server.port())) {
                observer.request("LOGIN observador");
                assertEquals(0, observer.stat("violations"));
            }
        }
    }

    @Test
    void configReadsAuditPeriod() {
        assertEquals(5, Server.Config.parse("--audit-every", "5").auditEverySeconds());
        assertEquals(0, Server.Config.parse().auditEverySeconds());
    }
}
