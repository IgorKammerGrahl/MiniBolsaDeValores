package minibolsa.engine;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import minibolsa.ExecutorShutdown;
import minibolsa.market.Asset;
import minibolsa.market.OrderBook;
import minibolsa.market.Trade;

/**
 * Single-writer: cada ativo tem o seu {@code newSingleThreadExecutor}, e essa
 * thread é a única dona do livro daquele ativo.
 *
 * <p>Como só uma thread toca no livro, ele não precisa de lock nenhum. As tarefas
 * entram na fila do executor e rodam uma de cada vez, na ordem de chegada. Essa
 * fila é o que define a prioridade de tempo. Ativos diferentes têm threads
 * diferentes, então são processados em paralelo.
 */
final class SingleWriterEngine implements MatchingEngine {

    private final Map<Asset, OrderBook> books = new EnumMap<>(Asset.class);
    private final Map<Asset, ExecutorService> executors = new EnumMap<>(Asset.class);

    SingleWriterEngine(Consumer<Trade> settlement) {
        for (Asset asset : Asset.values()) {
            books.put(asset, new OrderBook(asset, settlement));
            executors.put(asset, Executors.newSingleThreadExecutor(Thread.ofPlatform().name("livro-" + asset).factory()));
        }
    }

    @Override
    public <T> CompletableFuture<T> run(Asset asset, Function<OrderBook, T> task) {
        OrderBook book = books.get(asset);
        return CompletableFuture.supplyAsync(() -> task.apply(book), executors.get(asset));
    }

    @Override
    public void close() {
        executors.forEach((asset, executor) -> ExecutorShutdown.shutdownAndAwait(executor, "livro-" + asset));
    }
}
