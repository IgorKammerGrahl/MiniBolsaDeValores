package minibolsa.engine;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import minibolsa.ExecutorShutdown;
import minibolsa.market.Asset;
import minibolsa.market.OrderBook;
import minibolsa.market.Trade;

/**
 * Lock global: um pool de threads de plataforma processa ordens de qualquer
 * ativo, e todas disputam um único {@link ReentrantLock} para mexer nos livros.
 *
 * <p>É o jeito "óbvio" de proteger os livros, e existe para comparar com o
 * single-writer: aqui ordens de ativos diferentes também esperam umas pelas
 * outras, porque o lock é o mesmo.
 */
final class GlobalLockEngine implements MatchingEngine {

    private final Map<Asset, OrderBook> books = new EnumMap<>(Asset.class);
    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService pool;

    GlobalLockEngine(Consumer<Trade> settlement, int threads) {
        for (Asset asset : Asset.values()) {
            books.put(asset, new OrderBook(asset, settlement));
        }
        pool = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("motor-", 1).factory());
    }

    @Override
    public <T> CompletableFuture<T> run(Asset asset, Function<OrderBook, T> task) {
        OrderBook book = books.get(asset);
        return CompletableFuture.supplyAsync(() -> {
            lock.lock();
            try {
                return task.apply(book);
            } finally {
                lock.unlock();
            }
        }, pool);
    }

    @Override
    public void close() {
        ExecutorShutdown.shutdownAndAwait(pool, "motor");
    }
}
