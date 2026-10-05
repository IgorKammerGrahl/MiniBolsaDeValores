package minibolsa.bench;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;
import minibolsa.Args;
import minibolsa.ExecutorShutdown;
import minibolsa.Log;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.engine.Exchange;
import minibolsa.engine.Exchange.Strategy;
import minibolsa.market.Asset;
import minibolsa.market.Side;

/**
 * Modo {@code bench}: mede o motor de ordens em processo, sem rede, para medir
 * o motor e não a rede.
 *
 * <p>Para cada configuração (estratégia × threads produtoras × cenário) há uma
 * rodada de aquecimento, descartada, para o JIT compilar o caminho quente, e
 * {@code --repetitions} rodadas medidas. Cada métrica do CSV é a mediana delas.
 *
 * <p>Numa rodada, N threads produtoras mandam ordens direto para a
 * {@link Exchange}. Cada uma reserva, envia e espera o resultado antes da próxima
 * ordem: uma ordem em voo por produtor, como uma sessão do servidor. A latência de
 * uma ordem vai do início da reserva até o {@code CompletableFuture} completar.
 * O total de ordens por rodada é o mesmo para qualquer número de threads.
 *
 * <p>Ao fim de cada rodada as cinco invariantes são verificadas: benchmark com
 * resultado errado não vale, e o programa para.
 */
public final class Benchmark {

    static final String HEADER = "estrategia,threads,cenario,vazao_ordens_por_s,p50_us,p99_us";
    private static final int[] THREADS = {1, 2, 4, 8, 16};
    private static final Asset[] ASSETS = Asset.values();

    enum Scenario {
        /** Ordens espalhadas pelos 5 ativos: o single-writer pode usar até 5 threads em paralelo. */
        SPREAD("5-ativos"),
        /** Todas as ordens num ativo só: um livro, uma thread, nenhum paralelismo para aproveitar. */
        SINGLE_ASSET("1-ativo");

        final String label;

        Scenario(String label) {
            this.label = label;
        }
    }

    /** Uma rodada: vazão em ordens por segundo e latências em microssegundos. */
    record Result(double throughput, double p50Micros, double p99Micros) {
    }

    private Benchmark() {
    }

    /** Modo {@code bench} da linha de comando: roda todos os experimentos e grava o CSV. */
    public static void run(String[] argv) throws IOException, InterruptedException {
        Args args = Args.parse(argv, Set.of("--orders", "--repetitions", "--output"), Set.of());
        int orders = (int) args.number("--orders", 200_000);
        int repetitions = (int) args.number("--repetitions", 5);
        Path output = Path.of(args.value("--output", "benchmark.csv"));
        if (orders < 2 * THREADS[THREADS.length - 1] || repetitions < 1) {
            throw new IllegalArgumentException("--orders deve ser pelo menos " + 2 * THREADS[THREADS.length - 1]
                    + " e --repetitions pelo menos 1");
        }

        Log.info("Benchmark: " + orders + " ordens por rodada, 1 aquecimento + " + repetitions
                + " medições por configuração | " + Runtime.getRuntime().availableProcessors() + " núcleos | Java "
                + Runtime.version());
        List<String> csv = new ArrayList<>();
        csv.add(HEADER);
        for (Scenario scenario : Scenario.values()) {
            for (Strategy strategy : Strategy.values()) {
                for (int threads : THREADS) {
                    measure(strategy, threads, scenario, orders); // aquecimento: resultado descartado
                    List<Result> results = new ArrayList<>();
                    for (int r = 0; r < repetitions; r++) {
                        results.add(measure(strategy, threads, scenario, orders));
                    }
                    Result median = new Result(median(results, Result::throughput),
                            median(results, Result::p50Micros), median(results, Result::p99Micros));
                    csv.add(String.format(Locale.ROOT, "%s,%d,%s,%.0f,%.1f,%.1f", label(strategy), threads,
                            scenario.label, median.throughput(), median.p50Micros(), median.p99Micros()));
                    Log.info(String.format(Locale.ROOT, "%-13s %2d threads  %-8s %,10.0f ordens/s | p50 %7.1f µs"
                                    + " | p99 %8.1f µs", label(strategy), threads, scenario.label,
                            median.throughput(), median.p50Micros(), median.p99Micros()));
                }
            }
        }
        Files.write(output, csv, UTF_8);
        Log.info("CSV gravado em " + output.toAbsolutePath());
    }

    /** Uma rodada, com contas, motor e produtores novos. Lança exceção se alguma invariante falhar. */
    static Result measure(Strategy strategy, int threads, Scenario scenario, int orders) throws InterruptedException {
        AccountRegistry registry = new AccountRegistry();
        long[][] latencies = new long[threads][];
        long elapsed;
        try (Exchange exchange = new Exchange(registry, strategy)) {
            ExecutorService producers =
                    Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("produtor-", 1).factory());
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> running = new ArrayList<>();
            for (int p = 0; p < threads; p++) {
                Account account = registry.login("produtor-" + p); // uma conta por produtor, como um usuário por sessão
                long[] mine = latencies[p] = new long[orders / threads / 2 * 2]; // par: as ordens vão em pares
                running.add(producers.submit(() -> {
                    ready.countDown();
                    go.await(); // todos largam juntos
                    produce(exchange, account, scenario, mine);
                    return null;
                }));
            }
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            try {
                for (Future<?> producer : running) {
                    producer.get();
                }
            } catch (ExecutionException e) {
                throw new IllegalStateException("um produtor falhou: " + e.getCause(), e.getCause());
            } finally {
                elapsed = System.nanoTime() - start;
                ExecutorShutdown.shutdownAndAwait(producers, "produtores");
            }

            List<String> violations = exchange.checkInvariants();
            if (!violations.isEmpty()) {
                throw new IllegalStateException("benchmark inválido, invariantes violadas: " + violations);
            }
        }

        long[] all = Arrays.stream(latencies).flatMapToLong(Arrays::stream).sorted().toArray();
        return new Result(all.length * 1e9 / elapsed, percentile(all, 0.50) / 1e3, percentile(all, 0.99) / 1e3);
    }

    /**
     * Pares de ordens: vende q a um preço aleatório até 1% acima ou abaixo do inicial
     * e compra q com limite no topo dessa faixa. A compra sempre cruza com a venda
     * mais barata que houver, e o preço do negócio é o da venda (aleatório). O livro
     * nunca acumula mais do que as ordens em voo, e nenhuma conta esgota dinheiro ou
     * ações, então nenhuma ordem é recusada e todas passam pelo motor.
     *
     * <p>Uma recusa seria uma carga diferente da planejada: ela derruba o produtor e o
     * benchmark para. (Com compra e venda a preços aleatórios e sem cancelamento, o
     * livro acumula compras baratas e vendas caras que nunca se cruzam, até as contas
     * esgotarem.)
     */
    private static void produce(Exchange exchange, Account account, Scenario scenario, long[] latencies) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i + 1 < latencies.length; i += 2) {
            Asset asset = scenario == Scenario.SINGLE_ASSET ? Asset.PETR4 : ASSETS[random.nextInt(ASSETS.length)];
            long band = asset.initialPrice() / 100; // 1%
            long sellPrice = asset.initialPrice() + random.nextLong(-band, band + 1);
            long quantity = 1 + random.nextInt(10);
            latencies[i] = timedOrder(exchange, account, asset, Side.SELL, quantity, sellPrice);
            latencies[i + 1] = timedOrder(exchange, account, asset, Side.BUY, quantity, asset.initialPrice() + band);
        }
    }

    private static long timedOrder(Exchange exchange, Account account, Asset asset, Side side, long quantity,
                                   long price) {
        long start = System.nanoTime();
        exchange.submit(exchange.reserve(account, asset, side, quantity, price)).join();
        return System.nanoTime() - start;
    }

    /** Percentil pelo método do "posto mais próximo", sobre um vetor já ordenado. */
    static long percentile(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    static double median(List<Result> results, ToDoubleFunction<Result> metric) {
        double[] values = results.stream().mapToDouble(metric).sorted().toArray();
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }

    private static String label(Strategy strategy) {
        return strategy == Strategy.SINGLE_WRITER ? "single-writer" : "global-lock";
    }
}
