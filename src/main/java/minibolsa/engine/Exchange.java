package minibolsa.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import minibolsa.account.Account;
import minibolsa.account.AccountRegistry;
import minibolsa.account.InvariantChecker;
import minibolsa.engine.OrderRejectedException.Reason;
import minibolsa.market.Asset;
import minibolsa.market.BookSnapshot;
import minibolsa.market.Order;
import minibolsa.market.OrderBook;
import minibolsa.market.Side;
import minibolsa.market.Trade;

/**
 * Fachada da bolsa: é por aqui que sessões, robôs e o benchmark mandam ordens.
 *
 * <p>Uma ordem passa por dois passos:
 * <ol>
 *   <li>{@link #reserve}: na thread de quem chamou, cria a ordem e reserva o
 *       dinheiro ou as ações, com o lock da conta;</li>
 *   <li>{@link #submit}: manda a ordem para o motor, que a casa no livro do ativo e
 *       liquida os negócios. Devolve um {@link CompletableFuture} com o resultado.</li>
 * </ol>
 * São dois passos para que quem chamou possa responder "ordem aceita" antes de a
 * ordem chegar ao livro, e portanto antes de qualquer execução dela.
 */
public final class Exchange implements AutoCloseable {

    public enum Strategy {
        SINGLE_WRITER,
        GLOBAL_LOCK
    }

    private final AccountRegistry registry;
    private final MatchingEngine engine;
    private final AtomicLong nextOrderId = new AtomicLong();

    public Exchange(AccountRegistry registry, Strategy strategy) {
        this(registry, strategy, trade -> { });
    }

    /**
     * @param onTrade chamado na thread do motor logo depois de cada liquidação.
     *                Não pode bloquear: o motor inteiro esperaria por ele.
     */
    public Exchange(AccountRegistry registry, Strategy strategy, Consumer<Trade> onTrade) {
        this.registry = registry;
        Consumer<Trade> settlement = trade -> {
            registry.settle(trade);
            onTrade.accept(trade);
        };
        this.engine = switch (strategy) {
            case SINGLE_WRITER -> new SingleWriterEngine(settlement);
            case GLOBAL_LOCK -> new GlobalLockEngine(settlement, Runtime.getRuntime().availableProcessors());
        };
    }

    /**
     * Passo 1: cria a ordem, com um id global novo, e reserva o que ela precisa.
     *
     * @throws OrderRejectedException   se faltar dinheiro (compra) ou ações (venda)
     * @throws IllegalArgumentException se a quantidade ou o preço não forem positivos
     */
    public Order reserve(Account account, Asset asset, Side side, long quantity, long limitPrice) {
        Order order = new Order(nextOrderId.incrementAndGet(), account.id(), asset, side, quantity, limitPrice);
        if (!registry.reserve(order)) {
            throw new OrderRejectedException(side == Side.BUY ? Reason.INSUFFICIENT_CASH : Reason.INSUFFICIENT_SHARES);
        }
        return order;
    }

    /** Passo 2: casa a ordem no livro do ativo. O future traz os negócios que ela gerou ao chegar. */
    public CompletableFuture<List<Trade>> submit(Order order) {
        return engine.run(order.asset(), book -> book.submit(order));
    }

    /**
     * Cancela o restante de uma ordem aberta da conta. O future traz a quantidade
     * cancelada; 0 quer dizer que não havia o que cancelar (a ordem não existe, é
     * de outra conta ou já foi executada).
     *
     * <p>Roda no motor porque precisa tirar a ordem do livro. Se o cancelamento
     * chegar antes da própria ordem (duas sessões da mesma conta), a ordem é zerada
     * aqui e, quando chegar ao livro, não terá mais nada para casar.
     */
    public CompletableFuture<Long> cancel(Account account, long orderId) {
        Order order = account.openOrder(orderId);
        if (order == null) {
            return CompletableFuture.completedFuture(0L);
        }
        return engine.run(order.asset(), book -> {
            book.remove(order);
            return registry.cancel(order);
        });
    }

    /** Os melhores {@code depth} níveis de cada lado, lidos dentro do motor. */
    public CompletableFuture<BookSnapshot> book(Asset asset, int depth) {
        return engine.run(asset, book -> book.snapshot(depth));
    }

    /**
     * Verifica as cinco invariantes: as quatro das contas pelo
     * {@link InvariantChecker} e "nenhum livro cruzado" dentro do motor, que é
     * onde é seguro olhar cada livro. Lista vazia = tudo certo.
     */
    public List<String> checkInvariants() {
        List<String> violations = new ArrayList<>(InvariantChecker.check(registry));
        for (Asset asset : Asset.values()) {
            if (engine.run(asset, OrderBook::isCrossed).join()) {
                violations.add("livro de " + asset + " cruzado");
            }
        }
        return violations;
    }

    @Override
    public void close() {
        engine.close();
    }
}
