package minibolsa.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.Set;
import minibolsa.Args;

/**
 * Cliente de terminal mínimo, para quem não tem {@code nc}: o que for digitado
 * vai para o servidor, e tudo que chega é impresso.
 *
 * <p>Duas threads, porque as mensagens do servidor (FILL, TICK) chegam a qualquer
 * momento, inclusive enquanto o usuário digita: a thread principal lê o socket e
 * uma thread "teclado" lê o stdin.
 */
public final class TerminalClient {

    private TerminalClient() {
    }

    public static void run(String[] argv) throws IOException {
        Args args = Args.parse(argv, Set.of("--host", "--port"), Set.of());
        String host = args.value("--host", "localhost");
        int port = (int) args.number("--port", 9000);

        try (Socket socket = new Socket(host, port)) {
            BufferedReader fromServer = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            Writer toServer = new OutputStreamWriter(socket.getOutputStream(), UTF_8);
            System.out.println("Conectado a " + host + ":" + port + ". Digite HELP para ver os comandos.");

            // daemon: quando a conexão acabar, o programa termina mesmo com o teclado esperando
            Thread.ofPlatform().name("teclado").daemon(true).start(() -> forwardKeyboard(toServer));

            String line;
            while ((line = fromServer.readLine()) != null) {
                System.out.println(line);
            }
            System.out.println("Conexão encerrada.");
        }
    }

    private static void forwardKeyboard(Writer toServer) {
        try {
            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = keyboard.readLine()) != null) {
                toServer.write(line + "\n");
                toServer.flush();
            }
            toServer.write("QUIT\n"); // fim da entrada (Ctrl+D / Ctrl+Z): sai educadamente
            toServer.flush();
        } catch (IOException e) {
            // a conexão já fechou
        }
    }
}
