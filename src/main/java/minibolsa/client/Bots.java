package minibolsa.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import minibolsa.Args;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;

/**
 * Modo {@code bots}: vários robôs, cada um com a sua conexão.
 *
 * <ul>
 *   <li>as ações de todos os robôs são agendadas num único
 *       {@link ScheduledExecutorService} com poucas threads: {@code --rate} ações
 *       por segundo, por robô;</li>
 *   <li>cada robô tem uma tarefa de leitura, numa virtual thread (é I/O puro);</li>
 *   <li>com {@code --transfer-storm}, os robôs formam pares e cada um transfere
 *       para o outro sem parar (A→B e B→A). Contra um servidor com
 *       {@code --naive-transfer}, isso dá deadlock; sem a flag, nunca.</li>
 * </ul>
 */
public final class Bots implements AutoCloseable {

    private static final int SCHEDULER_THREADS = 4;
    private static final long REPORT_EVERY_SECONDS = 5;

    // contadores somados por todos os robôs (LongAdder: muitas threads somando sem disputar)
    final LongAdder orders = new LongAdder();
    final LongAdder cancels = new LongAdder();
    final LongAdder fills = new LongAdder();
    final LongAdder transfers = new LongAdder();
    private final Map<String, LongAdder> rejections = new ConcurrentHashMap<>();

    private final List<Bot> bots = new ArrayList<>();
    private final AtomicInteger connected = new AtomicInteger();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(SCHEDULER_THREADS, Thread.ofPlatform().name("agenda-", 1).factory());
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);

    private Bots() {
    }

    /** Modo {@code bots} da linha de comando: roda até o Ctrl+C. */
    public static void run(String[] argv) throws IOException, InterruptedException {
        Args args = Args.parse(argv, Set.of("--host", "--port", "--count", "--rate"), Set.of("--transfer-storm"));
        Bots bots = start(args.value("--host", "localhost"), (int) args.number("--port", 9000),
                (int) args.number("--count", 50), (int) args.number("--rate", 10), args.flag("--transfer-storm"));
        Runtime.getRuntime().addShutdownHook(new Thread(bots::close, "desligamento"));
        bots.closed.await();
    }

    /** Conecta {@code count} robôs e começa a agendar as ações. */
    public static Bots start(String host, int port, int count, int rate, boolean transferStorm) throws IOException {
        if (count < 1 || rate < 1) {
            throw new IllegalArgumentException("--count e --rate devem ser pelo menos 1");
        }
        Bots swarm = new Bots();
        try {
            for (int i = 0; i < count; i++) {
                String partner = transferStorm ? name(partnerOf(i, count)) : null;
                Bot bot = Bot.connect(host, port, name(i), partner, swarm);
                swarm.bots.add(bot);
                swarm.connected.incrementAndGet();
                swarm.readers.execute(bot::readLoop);
            }
        } catch (IOException e) {
            swarm.close();
            throw e;
        }

        long periodMicros = 1_000_000L / rate;
        for (Bot bot : swarm.bots) {
            long start = ThreadLocalRandom.current().nextLong(periodMicros); // espalha os robôs dentro do período
            swarm.scheduler.scheduleAtFixedRate(bot::act, start, periodMicros, TimeUnit.MICROSECONDS);
        }
        swarm.scheduler.scheduleAtFixedRate(() -> swarm.report("robôs"),
                REPORT_EVERY_SECONDS, REPORT_EVERY_SECONDS, TimeUnit.SECONDS);
        Log.info(count + " robôs conectados em " + host + ":" + port + ", " + rate + " ações/s cada"
                + (transferStorm ? " | TEMPESTADE de transferências cruzadas entre pares" : ""));
        return swarm;
    }

    private static String name(int index) {
        return "robo-" + (index + 1);
    }

    /** Pares 0↔1, 2↔3, ...; com quantidade ímpar, o último transfere para o primeiro. */
    private static int partnerOf(int index, int count) {
        int partner = index % 2 == 0 ? index + 1 : index - 1;
        return partner < count ? partner : 0;
    }

    void rejected(String code) {
        rejections.computeIfAbsent(code, c -> new LongAdder()).increment();
    }

    void disconnected() {
        connected.decrementAndGet();
    }

    private void report(String title) {
        Map<String, Long> refused = new TreeMap<>();
        rejections.forEach((code, n) -> refused.put(code, n.sum()));
        Log.info(title + ": " + connected.get() + "/" + bots.size() + " conectados | ordens " + orders.sum()
                + " | execuções " + fills.sum() + " | cancelamentos " + cancels.sum()
                + " | transferências " + transfers.sum() + " | recusas " + refused);
    }

    /** Para as ações, fecha as conexões e espera os executores terminarem. */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        ExecutorShutdown.shutdownAndAwait(scheduler, "agenda dos robôs");
        for (Bot bot : bots) {
            bot.stop();
        }
        ExecutorShutdown.shutdownAndAwait(readers, "leitores dos robôs");
        report("robôs encerrados, total");
        closed.countDown();
    }
}
