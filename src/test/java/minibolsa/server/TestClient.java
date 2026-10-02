package minibolsa.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Cliente de teste: manda linhas e lê as respostas, com timeout para nunca travar o teste. */
final class TestClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;

    TestClient(int port) throws IOException {
        socket = new Socket("localhost", port);
        socket.setSoTimeout(5_000); // nada em 5 s: o teste falha em vez de ficar esperando
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
        out = new OutputStreamWriter(socket.getOutputStream(), UTF_8);
    }

    void send(String line) throws IOException {
        out.write(line + "\n");
        out.flush();
    }

    String read() throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new EOFException("o servidor fechou a conexão");
        }
        return line;
    }

    String request(String line) throws IOException {
        send(line);
        return read();
    }

    /** Manda um comando de resposta com várias linhas e devolve todas, menos o END. */
    List<String> requestUntilEnd(String line) throws IOException {
        send(line);
        List<String> lines = new ArrayList<>();
        for (String next = read(); !next.equals("END"); next = read()) {
            lines.add(next);
        }
        return lines;
    }

    /** Manda STATS e devolve o contador pedido, ex.: {@code stat("clients")} de "STATS ... clients=2 ...". */
    long stat(String name) throws IOException {
        for (String field : request("STATS").split(" ")) {
            if (field.startsWith(name + "=")) {
                return Long.parseLong(field.substring(name.length() + 1));
            }
        }
        throw new IllegalStateException("STATS sem o contador " + name);
    }

    boolean closedByServer() throws IOException {
        return in.readLine() == null;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
