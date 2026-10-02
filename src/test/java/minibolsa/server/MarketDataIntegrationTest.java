package minibolsa.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MarketDataIntegrationTest {

    @Test
    @Timeout(20)
    void subscriberReceivesTicksEverySecondReflectingTrades() throws IOException {
        try (Server server = new Server(Server.Config.parse("--port", "0"))) {
            server.start();
            try (TestClient carla = new TestClient(server.port());
                 TestClient ana = new TestClient(server.port());
                 TestClient bia = new TestClient(server.port())) {
                carla.request("LOGIN carla");
                ana.request("LOGIN ana");
                bia.request("LOGIN bia");

                assertEquals("OK SUBSCRIBE PETR4", carla.request("SUBSCRIBE PETR4"));
                assertEquals("TICK PETR4 38.50 0.00 0", carla.read()); // ainda sem negócios: preço inicial

                assertTrue(ana.request("SELL PETR4 10 39.27").startsWith("OK ORDER "));
                ana.requestUntilEnd("BOOK PETR4"); // garante que a venda já está no livro
                assertTrue(bia.request("BUY PETR4 10 39.27").startsWith("OK ORDER "));

                // os próximos TICK trazem o preço do negócio, +2% sobre 38,50 e volume 10
                String tick = carla.read();
                while (tick.equals("TICK PETR4 38.50 0.00 0")) {
                    tick = carla.read();
                }
                assertEquals("TICK PETR4 39.27 +2.00 10", tick);
            }
        }
    }

    @Test
    @Timeout(20)
    void subscribeAllSendsOneTickPerAsset() throws IOException {
        try (Server server = new Server(Server.Config.parse("--port", "0"))) {
            server.start();
            try (TestClient carla = new TestClient(server.port())) {
                carla.request("LOGIN carla");

                assertEquals("OK SUBSCRIBE ALL", carla.request("SUBSCRIBE ALL"));
                assertEquals("TICK PETR4 38.50 0.00 0", carla.read());
                assertEquals("TICK VALE3 62.00 0.00 0", carla.read());
                assertEquals("TICK ITUB4 34.20 0.00 0", carla.read());
                assertEquals("TICK BBDC4 14.80 0.00 0", carla.read());
                assertEquals("TICK MGLU3 9.90 0.00 0", carla.read());
            }
        }
    }
}
