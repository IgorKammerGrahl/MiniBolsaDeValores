package minibolsa.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import minibolsa.client.Bots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Versões curtas das duas demonstrações dos robôs (as longas estão no README). */
@Timeout(30)
class BotsIntegrationTest {

    @Test
    void botsTradeAndTheAuditFindsNoViolation() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--audit-every", "1"))) {
            server.start();
            try (TestClient observer = new TestClient(server.port())) {
                observer.request("LOGIN observador");
                try (Bots bots = Bots.start("localhost", server.port(), 8, 50, false)) {
                    Thread.sleep(2_500);
                    long orders = observer.stat("orders");
                    long trades = observer.stat("trades");
                    Thread.sleep(1_000);

                    assertTrue(trades > 0, "nenhum negócio");
                    assertTrue(observer.stat("orders") > orders, "o STATS parou de crescer");
                }
                assertEquals(0, observer.stat("violations"));
            }
        }
    }

    @Test
    void transferStormDeadlocksTheNaiveServer() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0", "--naive-transfer"))) {
            server.start();
            try (TestClient observer = new TestClient(server.port());
                 Bots bots = Bots.start("localhost", server.port(), 4, 50, true)) {
                observer.request("LOGIN observador");
                while (observer.stat("deadlocks") == 0) { // o @Timeout pega se o watchdog nunca acusar
                    Thread.sleep(100);
                }
            }
        }
    }

    @Test
    void transferStormNeverDeadlocksWithOrderedLocks() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0"))) {
            server.start();
            try (TestClient observer = new TestClient(server.port());
                 Bots bots = Bots.start("localhost", server.port(), 4, 50, true)) {
                observer.request("LOGIN observador");
                Thread.sleep(2_500);
                assertEquals(0, observer.stat("deadlocks"));
            }
        }
    }
}
