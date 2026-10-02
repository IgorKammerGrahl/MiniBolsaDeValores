package minibolsa.engine;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import minibolsa.market.Asset;
import minibolsa.market.OrderBook;

/**
 * Como o motor garante que cada livro de ofertas (que não é thread-safe) é
 * usado por uma thread de cada vez. Toda operação num livro (enviar ordem,
 * cancelar, consultar) passa por {@link #run}.
 */
interface MatchingEngine extends AutoCloseable {

    /** Executa {@code task} com acesso exclusivo ao livro do ativo, numa thread do motor. */
    <T> CompletableFuture<T> run(Asset asset, Function<OrderBook, T> task);

    /** Encerra os executores do motor (shutdown + awaitTermination). */
    @Override
    void close();
}
