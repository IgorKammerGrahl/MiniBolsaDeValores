package minibolsa.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import minibolsa.Log;
import minibolsa.market.Asset;
import minibolsa.market.Money;

/**
 * Um robô: uma conexão própria com o servidor e duas partes que rodam em
 * threads diferentes.
 *
 * <ul>
 *   <li>{@link #readLoop}: tarefa de leitura (virtual thread). Consome respostas e
 *       eventos: guarda o último preço de cada TICK e o id de cada ordem aceita.</li>
 *   <li>{@link #act}: a ação periódica, agendada pelo {@code ScheduledExecutorService}
 *       de {@link Bots}. Compra ou vende perto do último preço e às vezes cancela
 *       uma ordem antiga; na tempestade, transfere para o par.</li>
 * </ul>
 *
 * <p>Só {@link #act} escreve no socket. Ela é uma única tarefa periódica, que nunca
 * roda duas vezes ao mesmo tempo, e cada execução enxerga o que a anterior fez
 * (garantia do agendador), então o {@code Writer} dispensa lock.
 */
final class Bot {

    /** Com mais ordens abertas que isso, o robô cancela a mais antiga em vez de mandar outra. */
    private static final int MAX_OPEN_ORDERS = 20;

    private final String name;
    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private final Bots swarm;
    /** Na tempestade de transferências: para quem este robô transfere. {@code null} = negocia. */
    private final String partner;
    /** Último preço de cada ativo (por {@link Asset#ordinal()}): escrito pelo leitor, lido pela ação. */
    private final AtomicLongArray lastPrice = new AtomicLongArray(Asset.values().length);
    /** Ids das ordens aceitas e ainda sem execução, da mais antiga para a mais nova: o leitor põe e tira, a ação tira. */
    private final ConcurrentLinkedDeque<Long> openOrders = new ConcurrentLinkedDeque<>();
    private volatile boolean stopped;

    private Bot(String name, Socket socket, Bots swarm, String partner) throws IOException {
        this.name = name;
        this.socket = socket;
        this.swarm = swarm;
        this.partner = partner;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8));
        for (Asset asset : Asset.values()) {
            lastPrice.set(asset.ordinal(), asset.initialPrice());
        }
    }

    /** Conecta, entra com o próprio nome (esperando o OK, para o par já existir na tempestade) e assina as cotações. */
    static Bot connect(String host, int port, String name, String partner, Bots swarm) throws IOException {
        Bot bot = new Bot(name, new Socket(host, port), swarm, partner);
        bot.send("LOGIN " + name);
        String answer = bot.in.readLine();
        if (answer == null || !answer.startsWith("OK LOGIN")) {
            bot.socket.close();
            throw new IOException(name + ": LOGIN recusado: " + answer);
        }
        bot.send("SUBSCRIBE ALL");
        return bot;
    }

    void readLoop() {
        Thread.currentThread().setName(name + "-leitor");
        try {
            String line;
            while ((line = in.readLine()) != null) {
                handle(line);
            }
            if (!stopped) {
                Log.info(name + ": o servidor encerrou a conexão");
            }
        } catch (IOException e) {
            if (!stopped) {
                Log.info(name + ": conexão perdida: " + e.getMessage());
            }
        } finally {
            stopped = true;
            swarm.disconnected();
        }
    }

    private void handle(String line) {
        if (line.startsWith("TICK ")) {
            String[] tick = line.split(" "); // TICK <ATIVO> <ultimo> <variacao> <volume>
            lastPrice.set(Asset.valueOf(tick[1]).ordinal(), Money.parse(tick[2]));
        } else if (line.startsWith("OK ORDER ")) {
            openOrders.addLast(Long.parseLong(line.substring("OK ORDER ".length())));
        } else if (line.startsWith("FILL ")) {
            // FILL <id> ...: a ordem já executou (ao menos em parte), não vale mais a pena cancelá-la
            openOrders.remove(Long.parseLong(line.split(" ")[1]));
            swarm.fills.increment();
        } else if (line.startsWith("OK TRANSFER ")) {
            swarm.transfers.increment();
        } else if (line.startsWith("ERR ")) {
            swarm.rejected(line.split(" ")[1]);
        }
    }

    /** Uma ação; o agendador chama {@code --rate} vezes por segundo. */
    void act() {
        if (stopped) {
            return;
        }
        try {
            if (partner != null) {
                send("TRANSFER " + partner + " 1.00");
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (openOrders.size() > MAX_OPEN_ORDERS || (!openOrders.isEmpty() && random.nextInt(10) == 0)) {
                Long oldest = openOrders.pollFirst();
                if (oldest != null) {
                    send("CANCEL " + oldest);
                    swarm.cancels.increment();
                    return;
                }
            }
            Asset asset = Asset.values()[random.nextInt(Asset.values().length)];
            long last = lastPrice.get(asset.ordinal());
            long price = Math.max(1, last + last * (random.nextInt(401) - 200) / 10_000); // até 2% para cima ou para baixo
            String side = random.nextBoolean() ? "BUY " : "SELL ";
            send(side + asset + " " + (1 + random.nextInt(10)) + " " + Money.format(price));
            swarm.orders.increment();
        } catch (IOException e) {
            stop();
        } catch (RuntimeException e) {
            // escapando daqui, o agendador pararia este robô em silêncio
            Log.error(name + ": falha na ação: " + e);
        }
    }

    void stop() {
        stopped = true;
        try {
            socket.close(); // destrava o leitor
        } catch (IOException ignored) {
            // já estava fechado
        }
    }

    private void send(String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }
}
