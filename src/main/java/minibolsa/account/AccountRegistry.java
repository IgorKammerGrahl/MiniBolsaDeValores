package minibolsa.account;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.market.Asset;
import minibolsa.market.Order;
import minibolsa.market.Side;
import minibolsa.market.Trade;

/**
 * Cadastro de contas e todas as operações que mexem nelas: reserva,
 * cancelamento, liquidação e transferência.
 *
 * <p>Regra de travamento: quem altera uma conta segura o lock dela. Quem precisa
 * de duas contas trava sempre a de menor id primeiro, e é isso que impede o
 * deadlock (a única exceção é a transferência ingênua, que existe para mostrar o
 * problema).
 *
 * <p>No modo inseguro ({@code --unsafe-accounts}) os locks das contas não fazem
 * nada: a verificação de saldo e o débito viram um check-then-act sem proteção.
 */
public final class AccountRegistry {

    private final Map<String, Account> byName = new ConcurrentHashMap<>();
    /** Ordenado por id: {@link #all()} já sai na ordem em que as contas devem ser travadas. */
    private final Map<Long, Account> byId = new ConcurrentSkipListMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final boolean unsafeAccounts;
    private final long raceWindowMs;
    private final boolean naiveTransfer;
    private final long transferPauseMs;

    /** Modo seguro, sem pausas. */
    public AccountRegistry() {
        this(false, 0, false, 0);
    }

    /**
     * @param unsafeAccounts  desliga os locks das contas (demonstração da corrida no saldo)
     * @param raceWindowMs    pausa entre verificar o saldo e debitar, para a corrida aparecer sempre
     * @param naiveTransfer   transferência trava origem e depois destino (pode dar deadlock, só para demonstração)
     * @param transferPauseMs pausa entre os dois locks da transferência ingênua, para o deadlock aparecer sempre
     */
    public AccountRegistry(boolean unsafeAccounts, long raceWindowMs, boolean naiveTransfer, long transferPauseMs) {
        this.unsafeAccounts = unsafeAccounts;
        this.raceWindowMs = raceWindowMs;
        this.naiveTransfer = naiveTransfer;
        this.transferPauseMs = transferPauseMs;
    }

    /** Devolve a conta do usuário, criando-a com o saldo inicial se ainda não existir. */
    public Account login(String name) {
        return byName.computeIfAbsent(name, n -> {
            Account account = new Account(nextId.incrementAndGet(), n);
            byId.put(account.id, account);
            return account;
        });
    }

    public Optional<Account> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Account byId(long id) {
        return Objects.requireNonNull(byId.get(id), () -> "conta inexistente: " + id);
    }

    /** Cópia consistente da conta: saldos, ações e ordens abertas lidos com o lock na mão. */
    public AccountSnapshot snapshot(Account account) {
        lock(account);
        try {
            List<AccountSnapshot.Position> positions = new ArrayList<>();
            for (Asset asset : Asset.values()) {
                int i = asset.ordinal();
                positions.add(new AccountSnapshot.Position(asset, account.sharesAvailable[i], account.sharesReserved[i]));
            }
            List<AccountSnapshot.OpenOrder> orders = account.openOrders.values().stream()
                    .sorted(Comparator.comparingLong(Order::id))
                    .map(o -> new AccountSnapshot.OpenOrder(o.id(), o.asset(), o.side(), o.remaining(), o.quantity(),
                            o.limitPrice()))
                    .toList();
            return new AccountSnapshot(account.name, account.cashAvailable, account.cashReserved, positions, orders);
        } finally {
            unlock(account);
        }
    }

    /** Todas as contas, em ordem crescente de id. */
    public List<Account> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Reserva o que a ordem precisa e a registra como aberta. BUY q @ p move
     * q × p de dinheiro disponível para reservado; SELL q move q ações. Devolve
     * {@code false} (sem mexer em nada) se faltar dinheiro ou ações.
     *
     * <p>A verificação e o débito acontecem com o lock da conta na mão: duas
     * ordens simultâneas da mesma conta nunca gastam o mesmo dinheiro. Sem o lock
     * (modo inseguro), as duas podem passar pela verificação antes de qualquer
     * uma debitar.
     */
    public boolean reserve(Order order) {
        Account account = byId(order.accountId());
        lock(account);
        try {
            if (order.side() == Side.BUY) {
                long cost = costOf(order.quantity(), order.limitPrice());
                if (account.cashAvailable < cost) {
                    return false;
                }
                raceWindow();
                account.cashAvailable -= cost;
                account.cashReserved += cost;
            } else {
                int i = order.asset().ordinal();
                if (account.sharesAvailable[i] < order.quantity()) {
                    return false;
                }
                raceWindow();
                account.sharesAvailable[i] -= order.quantity();
                account.sharesReserved[i] += order.quantity();
            }
            account.openOrders.put(order.id(), order);
            return true;
        } finally {
            unlock(account);
        }
    }

    /**
     * Cancela o que falta executar da ordem e devolve a reserva correspondente.
     * Devolve a quantidade cancelada (0 se a ordem já estava encerrada).
     */
    public long cancel(Order order) {
        Account account = byId(order.accountId());
        lock(account);
        try {
            long canceled = order.cancel();
            if (order.side() == Side.BUY) {
                long amount = canceled * order.limitPrice();
                account.cashReserved -= amount;
                account.cashAvailable += amount;
            } else {
                int i = order.asset().ordinal();
                account.sharesReserved[i] -= canceled;
                account.sharesAvailable[i] += canceled;
            }
            account.openOrders.remove(order.id());
            return canceled;
        } finally {
            unlock(account);
        }
    }

    /**
     * Liquida um negócio de q ações ao preço e. O comprador tinha reservado
     * q × L (L = limite da compra) e recebe de volta a diferença q × (L − e).
     *
     * <p>Trava as duas contas em ordem crescente de id. Como toda operação com
     * duas contas segue a mesma ordem, nunca há duas threads esperando uma pela
     * outra em ciclo. Se comprador e vendedor forem a mesma conta (self-trade), o
     * lock é reentrante e a mesma thread o pega duas vezes.
     *
     * <p>A quantidade restante das ordens muda aqui dentro, junto com os saldos:
     * quem olhar as contas travadas (a auditoria) nunca vê um negócio pela metade.
     */
    public void settle(Trade trade) {
        Account buyer = byId(trade.buy().accountId());
        Account seller = byId(trade.sell().accountId());
        lockInIdOrder(buyer, seller);
        try {
            long q = trade.quantity();
            long price = trade.price();
            long limit = trade.buy().limitPrice();
            int i = trade.asset().ordinal();

            trade.fillOrders();
            buyer.cashReserved -= q * limit;
            buyer.cashAvailable += q * (limit - price);
            buyer.sharesAvailable[i] += q;
            seller.sharesReserved[i] -= q;
            seller.cashAvailable += q * price;

            if (trade.buy().remaining() == 0) {
                buyer.openOrders.remove(trade.buy().id());
            }
            if (trade.sell().remaining() == 0) {
                seller.openOrders.remove(trade.sell().id());
            }
        } finally {
            unlock(buyer);
            unlock(seller);
        }
    }

    /**
     * Move {@code amount} centavos de dinheiro disponível de {@code from} para
     * {@code to}. Devolve {@code false} (sem mexer em nada) se faltar saldo.
     *
     * <p>Modo normal: trava as duas contas em ordem crescente de id, como a
     * liquidação. Modo ingênuo: trava a origem, espera um pouco e trava o
     * destino. Duas transferências cruzadas (A→B e B→A) travam cada uma a sua
     * origem e ficam esperando pela outra para sempre: deadlock.
     *
     * <p>Os locks são pegos com {@code lockInterruptibly}: interromper a thread
     * (o {@code shutdownNow} de um executor) a tira de um deadlock.
     */
    public boolean transfer(Account from, Account to, long amount) throws InterruptedException {
        if (amount <= 0) {
            throw new IllegalArgumentException("valor deve ser positivo: " + amount);
        }
        boolean fromFirst = naiveTransfer || from.id <= to.id;
        Account first = fromFirst ? from : to;
        Account second = fromFirst ? to : from;

        lockInterruptibly(first);
        try {
            if (naiveTransfer) {
                Thread.sleep(transferPauseMs); // alarga a janela em que a outra transferência trava a sua origem
            }
            lockInterruptibly(second);
            try {
                if (from.cashAvailable < amount) {
                    return false;
                }
                raceWindow();
                from.cashAvailable -= amount;
                to.cashAvailable += amount;
                return true;
            } finally {
                unlock(second);
            }
        } finally {
            unlock(first);
        }
    }

    // --- locks das contas: no modo inseguro não fazem nada ---

    void lock(Account account) {
        if (!unsafeAccounts) {
            account.lock.lock();
        }
    }

    void unlock(Account account) {
        if (!unsafeAccounts) {
            account.lock.unlock();
        }
    }

    private void lockInterruptibly(Account account) throws InterruptedException {
        if (!unsafeAccounts) {
            account.lock.lockInterruptibly();
        }
    }

    private void lockInIdOrder(Account a, Account b) {
        Account first = a.id <= b.id ? a : b;
        Account second = first == a ? b : a;
        lock(first);
        lock(second);
    }

    /**
     * Pausa artificial entre verificar e debitar ({@code --race-window-ms}). Com o
     * lock da conta, só deixa tudo mais lento; sem ele, alarga a janela em que
     * outra thread passa pela mesma verificação.
     */
    private void raceWindow() {
        if (raceWindowMs > 0) {
            try {
                Thread.sleep(raceWindowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** q × p, tratando estouro do {@code long} (quantidade absurda vinda do cliente) como "caro demais". */
    private static long costOf(long quantity, long price) {
        try {
            return Math.multiplyExact(quantity, price);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
