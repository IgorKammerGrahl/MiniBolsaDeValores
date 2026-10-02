package minibolsa.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Servidor de verdade numa porta livre, conversando por socket. */
@Timeout(20)
class ServerIntegrationTest {

    private Server server;

    @BeforeEach
    void startServer() throws IOException {
        server = new Server(Server.Config.parse("--port", "0"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private TestClient login(String name) throws IOException {
        TestClient client = new TestClient(server.port());
        assertEquals("OK LOGIN " + name + " 100000.00", client.request("LOGIN " + name));
        return client;
    }

    private static String orderId(String response) {
        assertTrue(response.startsWith("OK ORDER "), response);
        return response.substring("OK ORDER ".length());
    }

    private static void assertError(String code, String response) {
        assertTrue(response.startsWith("ERR " + code + " "), response);
    }

    @Test
    void tradeBetweenTwoClientsSendsFillToBothSides() throws IOException {
        try (TestClient ana = login("ana"); TestClient bia = login("bia")) {
            String sellId = orderId(ana.request("SELL PETR4 100 38.50"));
            // OK ORDER = aceita e reservada, ainda não no livro. A sessão só lê o próximo comando
            // depois que o motor terminou a ordem anterior, então a resposta do BOOK garante que ela entrou.
            assertEquals(List.of("BOOK PETR4", "ASK 38.50 100"), ana.requestUntilEnd("BOOK PETR4"));
            String buyId = orderId(bia.request("BUY PETR4 60 39.00"));

            // o OK ORDER sempre chega antes do FILL da própria ordem
            assertEquals("FILL " + buyId + " PETR4 BUY 60 38.50", bia.read());
            assertEquals("FILL " + sellId + " PETR4 SELL 60 38.50", ana.read());

            assertEquals(List.of("BOOK PETR4", "ASK 38.50 40"), bia.requestUntilEnd("book petr4"));
            assertEquals(List.of("ORDERS", "ORDER " + sellId + " PETR4 SELL 40 100 38.50"),
                    ana.requestUntilEnd("ORDERS"));
            assertEquals("OK CANCEL " + sellId + " 40", ana.request("CANCEL " + sellId));
            assertEquals(List.of("BOOK PETR4"), bia.requestUntilEnd("BOOK PETR4"));

            List<String> anaPortfolio = ana.requestUntilEnd("PORTFOLIO");
            assertEquals("CASH 102310.00 0.00", anaPortfolio.get(1)); // 100.000 + 60 × 38,50
            assertEquals("SHARES PETR4 940 0", anaPortfolio.get(2));
            List<String> biaPortfolio = bia.requestUntilEnd("PORTFOLIO");
            assertEquals("CASH 97690.00 0.00", biaPortfolio.get(1)); // reservou a 39,00, pagou 38,50
            assertEquals("SHARES PETR4 1060 0", biaPortfolio.get(2));

            assertEquals("STATS orders=2 trades=1 clients=2 violations=0 deadlocks=0", ana.request("STATS"));
        }
    }

    @Test
    void errorsUseTheProtocolCodes() throws IOException {
        try (TestClient guest = new TestClient(server.port()); TestClient ana = login("ana")) {
            assertError("NAO_AUTENTICADO", guest.request("BUY PETR4 1 1.00"));
            assertEquals("HELP", guest.requestUntilEnd("help").getFirst()); // HELP funciona sem login

            assertError("COMANDO_INVALIDO", ana.request("VOAR PETR4"));
            assertError("COMANDO_INVALIDO", ana.request("BUY PETR4 dez 38.50"));
            assertError("COMANDO_INVALIDO", ana.request("LOGIN outra"));
            assertError("ATIVO_INEXISTENTE", ana.request("BUY XPTO3 1 1.00"));
            assertError("SALDO_INSUFICIENTE", ana.request("BUY PETR4 10000 38.50"));
            assertError("ACOES_INSUFICIENTES", ana.request("SELL PETR4 1001 38.50"));
            assertError("ORDEM_INEXISTENTE", ana.request("CANCEL 999"));
            assertError("USUARIO_INEXISTENTE", ana.request("TRANSFER ninguem 1.00"));
            assertError("SALDO_INSUFICIENTE", ana.request("TRANSFER ana 100000.01"));
        }
    }

    @Test
    void transferMovesCashBetweenUsers() throws IOException {
        try (TestClient ana = login("ana"); TestClient bia = login("bia")) {
            assertEquals("OK TRANSFER bia 250.00", ana.request("TRANSFER bia 250.00"));

            assertEquals("CASH 99750.00 0.00", ana.requestUntilEnd("PORTFOLIO").get(1));
            assertEquals("CASH 100250.00 0.00", bia.requestUntilEnd("PORTFOLIO").get(1));
        }
    }

    @Test
    void sameUserInTwoClientsSharesTheAccountAndGetsFillsInBoth() throws IOException {
        try (TestClient ana1 = login("ana"); TestClient ana2 = login("ana"); TestClient bia = login("bia")) {
            String sellId = orderId(ana1.request("SELL VALE3 10 62.00"));
            ana1.requestUntilEnd("BOOK VALE3"); // garante que a venda já está no livro
            orderId(bia.request("BUY VALE3 10 62.00"));

            assertEquals("FILL " + sellId + " VALE3 SELL 10 62.00", ana1.read());
            assertEquals("FILL " + sellId + " VALE3 SELL 10 62.00", ana2.read());
            assertEquals("SHARES VALE3 990 0", ana2.requestUntilEnd("PORTFOLIO").get(3));
        }
    }

    @Test
    void quitSaysByeAndClosesTheConnection() throws IOException {
        try (TestClient client = new TestClient(server.port())) {
            assertEquals("OK BYE", client.request("QUIT"));
            assertTrue(client.closedByServer());
        }
    }
}
