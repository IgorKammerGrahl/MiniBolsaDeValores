package minibolsa.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.Args;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.engine.Exchange;
import minibolsa.engine.Exchange.Strategy;
import minibolsa.market.Order;
import minibolsa.market.Trade;

/**
 * O servidor TCP:
 *
 * <ul>
 *   <li>a thread "accept" aceita conexões;</li>
 *   <li>cada cliente ganha duas tarefas (leitor e escritor) num executor de
 *       <b>virtual threads</b>: threads baratas, boas para passar o tempo paradas
 *       esperando a rede;</li>
 *   <li>o processamento das ordens fica no motor, em <b>threads de plataforma</b>;</li>
 *   <li>o TRANSFER roda num pool de threads de plataforma. Além de ser
 *       processamento, isso deixa o deadlock da transferência ingênua visível
 *       para o {@code ThreadMXBean}, que não enxerga virtual threads.</li>
 * </ul>
 */
public final class Server implements AutoCloseable {

    /** Opções da linha de comando do modo {@code server}. */
    public record Config(int port, Strategy strategy, boolean unsafeAccounts, long raceWindowMs,
                         boolean naiveTransfer, long transferPauseMs) {

        public static Config parse(String... argv) {
            Args args = Args.parse(argv,
                    Set.of("--port", "--engine", "--race-window-ms", "--transfer-pause-ms"),
                    Set.of("--unsafe-accounts", "--naive-transfer"));
            Strategy strategy = switch (args.value("--engine", "single-writer")) {
                case "single-writer" -> Strategy.SINGLE_WRITER;
                case "global-lock" -> Strategy.GLOBAL_LOCK;
                default -> throw new IllegalArgumentException("--engine deve ser single-writer ou global-lock");
            };
            return new Config((int) args.number("--port", 9000), strategy, args.flag("--unsafe-accounts"),
                    args.number("--race-window-ms", 0), args.flag("--naive-transfer"),
                    args.number("--transfer-pause-ms", 10));
        }
    }

    private static final int TRANSFER_THREADS = 8;

    private final Config config;
    private final AccountRegistry registry;
    private final Exchange exchange;
    private final ServerSocket serverSocket;
    private final Thread acceptThread = Thread.ofPlatform().name("accept").unstarted(this::acceptLoop);
    private final ExecutorService sessionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService transferPool =
            Executors.newFixedThreadPool(TRANSFER_THREADS, Thread.ofPlatform().name("transferencia-", 1).factory());

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    /** Sessões logadas em cada conta (uma conta pode estar aberta em vários clientes): para onde vão os FILL. */
    private final Map<Long, Set<ClientSession>> sessionsByAccount = new ConcurrentHashMap<>();
    private final AtomicLong nextSessionId = new AtomicLong();
    private final AtomicLong ordersAccepted = new AtomicLong();
    private final AtomicLong trades = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();

    public Server(Config config) throws IOException {
        this.config = config;
        this.registry = new AccountRegistry(config.unsafeAccounts(), config.raceWindowMs(),
                config.naiveTransfer(), config.transferPauseMs());
        this.exchange = new Exchange(registry, config.strategy(), this::onTrade);
        this.serverSocket = new ServerSocket(config.port());
    }

    /** Modo {@code server} da linha de comando: roda até o Ctrl+C. */
    public static void run(String[] argv) throws IOException, InterruptedException {
        Server server = new Server(Config.parse(argv));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "desligamento"));
        server.start();
        server.acceptThread.join();
    }

    public void start() {
        acceptThread.start();
        Log.info("Servidor ouvindo na porta " + port()
                + " | motor: " + (config.strategy() == Strategy.SINGLE_WRITER ? "single-writer" : "global-lock")
                + " | contas: " + (config.unsafeAccounts() ? "SEM LOCK" : "com lock")
                + (config.raceWindowMs() > 0 ? " (janela de corrida " + config.raceWindowMs() + " ms)" : "")
                + " | transferência: " + (config.naiveTransfer()
                        ? "INGÊNUA (pausa " + config.transferPauseMs() + " ms)" : "locks em ordem de id"));
    }

    /** A porta real (útil quando a configuração pede a porta 0, "qualquer uma livre"). */
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true); // respostas curtas saem na hora, sem esperar juntar pacote
                ClientSession session = new ClientSession(nextSessionId.incrementAndGet(), socket, this);
                sessions.add(session);
                Log.info(session + " conectado de " + socket.getRemoteSocketAddress());
                sessionExecutor.execute(session::readLoop);
                sessionExecutor.execute(session::writeLoop);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    Log.error("falha ao aceitar conexão: " + e.getMessage());
                }
            }
        }
    }

    // --- chamado pelas sessões ---

    AccountRegistry registry() {
        return registry;
    }

    Exchange exchange() {
        return exchange;
    }

    void orderAccepted() {
        ordersAccepted.incrementAndGet();
    }

    void loggedIn(ClientSession session, Account account) {
        sessionsByAccount.computeIfAbsent(account.id(), id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    void disconnected(ClientSession session) {
        sessions.remove(session);
        Account account = session.account();
        if (account != null) {
            sessionsByAccount.getOrDefault(account.id(), Set.of()).remove(session);
        }
    }

    /** Roda a transferência no pool de plataforma e espera o resultado. */
    boolean transfer(Account from, Account to, long amount) throws InterruptedException {
        Future<Boolean> result = transferPool.submit(() -> registry.transfer(from, to, amount));
        try {
            return result.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("transferência falhou", e.getCause());
        }
    }

    String stats() {
        return "STATS orders=" + ordersAccepted.get() + " trades=" + trades.get() + " clients=" + sessions.size();
    }

    /** Roda na thread do motor, logo depois da liquidação: só enfileira, nunca bloqueia. */
    private void onTrade(Trade trade) {
        trades.incrementAndGet();
        notifyOwner(trade.buy(), trade);
        notifyOwner(trade.sell(), trade);
    }

    private void notifyOwner(Order order, Trade trade) {
        Set<ClientSession> owners = sessionsByAccount.get(order.accountId());
        if (owners != null) {
            String fill = Protocol.fill(order, trade);
            for (ClientSession session : owners) {
                session.send(fill);
            }
        }
    }

    /**
     * Encerra em ordem: para de aceitar, desconecta todo mundo e fecha os
     * executores. As transferências fecham antes das sessões, para soltar quem
     * estiver esperando uma transferência presa num deadlock.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
            acceptThread.join();
        } catch (IOException e) {
            Log.error("falha ao fechar o socket do servidor: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (ClientSession session : sessions) {
            session.close("servidor encerrando");
        }
        ExecutorShutdown.shutdownAndAwait(transferPool, "transferências");
        ExecutorShutdown.shutdownAndAwait(sessionExecutor, "sessões");
        exchange.close();
        Log.info("Servidor encerrado. " + stats());
    }
}
