package minibolsa.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import minibolsa.Log;
import minibolsa.account.Account;
import minibolsa.engine.OrderRejectedException;
import minibolsa.market.Asset;
import minibolsa.market.Money;
import minibolsa.market.Order;
import minibolsa.market.Side;
import minibolsa.server.CommandException.Code;

/**
 * Uma conexão de cliente, atendida por duas tarefas em virtual threads:
 *
 * <ul>
 *   <li><b>leitor</b>: lê uma linha, interpreta, executa e enfileira a resposta;</li>
 *   <li><b>escritor</b>: tira mensagens da fila de saída e as escreve no socket.</li>
 * </ul>
 *
 * <p>É um produtor/consumidor: qualquer thread que precise falar com o cliente
 * (a própria sessão, as threads do motor com os FILL) só chama {@link #send},
 * que enfileira sem bloquear. Só o escritor escreve no socket.
 *
 * <p>Cliente lento: se a fila (limitada) encher, o cliente é desconectado. Assim
 * o motor nunca fica esperando por um cliente que não lê.
 */
final class ClientSession {

    static final int QUEUE_CAPACITY = 1_000;
    static final int MAX_LINE = 1_000;
    /** "Pílula de veneno": avisa o escritor que acabou. Comparada por identidade, nunca por equals. */
    private static final String POISON = new String("FIM");

    private final long id;
    private final Socket socket;
    private final Server server;
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Definida pelo leitor no LOGIN; lida também por outras threads (desconexão, logs). */
    private volatile Account account;
    /** Só o leitor mexe. */
    private boolean quitting;

    ClientSession(long id, Socket socket, Server server) {
        this.id = id;
        this.socket = socket;
        this.server = server;
    }

    Account account() {
        return account;
    }

    /** Enfileira uma mensagem (pode ter várias linhas) sem nunca bloquear. Fila cheia: desconecta. */
    void send(String message) {
        if (closed.get()) {
            return;
        }
        if (!outbox.offer(message)) {
            close("fila de saída cheia (" + QUEUE_CAPACITY + " mensagens): o cliente não está lendo,"
                    + " desconectado para não atrasar ninguém");
        }
    }

    void readLoop() {
        Thread.currentThread().setName("cliente-" + id + "-leitor");
        String reason = "conexão encerrada pelo cliente";
        try {
            // Sem try-with-resources: fechar o stream fecharia o socket antes de o escritor mandar o "OK BYE".
            Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            String line;
            while (!quitting && (line = readLine(in)) != null) {
                if (!line.isBlank()) {
                    handle(line);
                }
            }
        } catch (IOException e) {
            reason = e.getMessage();
        } catch (InterruptedException e) {
            reason = "servidor encerrando";
        } catch (RuntimeException e) {
            Log.error(this + ": erro inesperado: " + e);
            reason = "erro interno";
        } finally {
            if (!quitting) {
                close(reason);
            }
        }
    }

    void writeLoop() {
        Thread.currentThread().setName("cliente-" + id + "-escritor");
        String reason = "saiu (QUIT)";
        try {
            Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8));
            String message;
            while ((message = outbox.take()) != POISON) {
                out.write(message);
                out.write('\n');
                if (outbox.isEmpty()) {
                    out.flush(); // com fila, junta várias mensagens numa escrita só
                }
            }
            out.flush();
        } catch (IOException e) {
            reason = "erro de escrita: " + e.getMessage();
        } catch (InterruptedException e) {
            reason = "servidor encerrando";
        } finally {
            close(reason);
        }
    }

    /**
     * Desconecta na hora (idempotente). Fechar o socket destrava o leitor parado
     * no {@code read} e o escritor parado no {@code write}; a pílula de veneno
     * acorda o escritor parado no {@code take}.
     */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // já estava fechado
        }
        outbox.clear();
        outbox.offer(POISON);
        server.disconnected(this);
        Log.info(this + " desconectado: " + reason);
    }

    private void handle(String line) throws InterruptedException {
        try {
            execute(Protocol.parse(line));
        } catch (CommandException e) {
            send(Protocol.error(e.code(), e.getMessage()));
        }
    }

    private void execute(Command command) throws InterruptedException {
        boolean allowedAnonymously = command instanceof Command.Login || command instanceof Command.Help
                || command instanceof Command.Quit;
        if (account == null && !allowedAnonymously) {
            throw new CommandException(Code.NOT_AUTHENTICATED, "faça LOGIN <nome> primeiro");
        }
        switch (command) {
            case Command.Login(String name) -> login(name);
            case Command.PlaceOrder(Side side, Asset asset, long quantity, long price) ->
                    placeOrder(side, asset, quantity, price);
            case Command.Cancel(long orderId) -> cancel(orderId);
            case Command.Book(Asset asset) ->
                    send(Protocol.book(server.exchange().book(asset, Protocol.BOOK_DEPTH).join()));
            case Command.Portfolio p -> send(Protocol.portfolio(server.registry().snapshot(account)));
            case Command.Orders o -> send(Protocol.orders(server.registry().snapshot(account)));
            case Command.Transfer(String user, long amount) -> transfer(user, amount);
            case Command.Stats s -> send(server.stats());
            case Command.Help h -> send(Protocol.HELP);
            case Command.Quit q -> quit();
        }
    }

    private void login(String name) {
        if (account != null) {
            throw new CommandException(Code.INVALID_COMMAND, "já autenticado como " + account.name());
        }
        Account logged = server.registry().login(name);
        send("OK LOGIN " + name + " " + Money.format(server.registry().snapshot(logged).cashAvailable()));
        account = logged;
        server.loggedIn(this, logged);
        Log.info(this + " entrou");
    }

    private void placeOrder(Side side, Asset asset, long quantity, long price) {
        Order order;
        try {
            order = server.exchange().reserve(account, asset, side, quantity, price);
        } catch (OrderRejectedException e) {
            Code code = e.reason() == OrderRejectedException.Reason.INSUFFICIENT_CASH
                    ? Code.INSUFFICIENT_CASH : Code.INSUFFICIENT_SHARES;
            throw new CommandException(code, e.getMessage());
        }
        server.orderAccepted();
        send("OK ORDER " + order.id()); // antes de ir ao motor: nenhum FILL desta ordem chega antes do OK
        server.exchange().submit(order).join(); // espera o motor: uma ordem em voo por sessão
    }

    private void cancel(long orderId) {
        long canceled = server.exchange().cancel(account, orderId).join();
        if (canceled == 0) {
            throw new CommandException(Code.UNKNOWN_ORDER, "nenhuma ordem aberta sua com id " + orderId);
        }
        send("OK CANCEL " + orderId + " " + canceled);
    }

    private void transfer(String user, long amount) throws InterruptedException {
        Account to = server.registry().find(user)
                .orElseThrow(() -> new CommandException(Code.UNKNOWN_USER, "usuário inexistente: " + user));
        if (!server.transfer(account, to, amount)) {
            throw new CommandException(Code.INSUFFICIENT_CASH, "saldo disponível insuficiente");
        }
        send("OK TRANSFER " + user + " " + Money.format(amount));
    }

    private void quit() {
        send("OK BYE");
        quitting = true;
        if (!outbox.offer(POISON)) { // o escritor fecha a conexão depois de mandar o BYE
            close("fila de saída cheia");
        }
    }

    /** Lê uma linha de até {@link #MAX_LINE} caracteres, sem o '\n' (e o '\r' do Windows). null = fim da conexão. */
    private static String readLine(Reader in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (line.length() == MAX_LINE) {
                throw new IOException("linha com mais de " + MAX_LINE + " caracteres");
            }
            line.append((char) c);
        }
        if (c == -1 && line.isEmpty()) {
            return null;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    @Override
    public String toString() {
        Account logged = account;
        return "cliente " + id + (logged == null ? "" : " (" + logged.name() + ")");
    }
}
