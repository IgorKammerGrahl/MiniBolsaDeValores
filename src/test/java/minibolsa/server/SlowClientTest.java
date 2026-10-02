package minibolsa.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Um cliente que manda comandos sem parar e nunca lê as respostas. Os buffers
 * TCP enchem, o escritor da sessão para no {@code write}, a fila de saída enche
 * e o servidor o desconecta. Enquanto isso, outro cliente é atendido normalmente.
 */
class SlowClientTest {

    @Test
    @Timeout(60)
    void slowClientIsDisconnectedWithoutAffectingTheOthers() throws Exception {
        try (Server server = new Server(Server.Config.parse("--port", "0"))) {
            server.start();

            Socket slow = new Socket();
            slow.setReceiveBufferSize(4096); // janela TCP pequena: os buffers enchem logo
            slow.connect(new InetSocketAddress("localhost", server.port()));
            Thread flooder = Thread.ofPlatform().name("cliente-lento").start(() -> {
                try (Writer out = new BufferedWriter(new OutputStreamWriter(slow.getOutputStream(), UTF_8))) {
                    out.write("LOGIN lento\n");
                    for (int i = 0; i < 1_000_000; i++) {
                        out.write("HELP\n");
                    }
                } catch (IOException e) {
                    // o servidor desconectou: é o esperado
                }
            });

            try (TestClient fast = new TestClient(server.port())) {
                assertEquals("OK LOGIN rapido 100000.00", fast.request("LOGIN rapido"));
                while (!fast.request("STATS").endsWith("clients=1")) { // espera o lento sair
                    Thread.sleep(50);
                }
                assertTrue(fast.request("BUY PETR4 10 38.50").startsWith("OK ORDER "));
            }

            flooder.join(10_000);
            assertFalse(flooder.isAlive(), "o cliente lento não foi desconectado");
            slow.close();
        }
    }
}
