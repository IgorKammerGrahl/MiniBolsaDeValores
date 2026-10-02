package minibolsa.account;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import minibolsa.market.Order;
import minibolsa.market.Side;

/**
 * Cadastro de contas e todas as operações que mexem nelas: reserva,
 * cancelamento, liquidação e transferência.
 *
 * <p>Regra de travamento: quem altera uma conta segura o lock dela. Quem precisa
 * de duas contas trava sempre a de menor id primeiro, e é isso que impede o
 * deadlock (a única exceção é a transferência ingênua, que existe para mostrar o
 * problema).
 */
public final class AccountRegistry {

    private final Map<String, Account> byName = new ConcurrentHashMap<>();
    /** Ordenado por id: {@link #all()} já sai na ordem em que as contas devem ser travadas. */
    private final Map<Long, Account> byId = new ConcurrentSkipListMap<>();
    private final AtomicLong nextId = new AtomicLong();

    /** Devolve a conta do usuário, criando-a com o saldo inicial se ainda não existir. */
    public Account login(String name) {
        return byName.computeIfAbsent(name, n -> {
            Account account = new Account(nextId.incrementAndGet(), n);
            byId.put(account.id, account);
            return account;
        });
    }

    public Account byId(long id) {
        return Objects.requireNonNull(byId.get(id), () -> "conta inexistente: " + id);
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
     * ordens simultâneas da mesma conta nunca gastam o mesmo dinheiro.
     */
    public boolean reserve(Order order) {
        Account account = byId(order.accountId());
        account.lock.lock();
        try {
            if (order.side() == Side.BUY) {
                long cost = costOf(order.quantity(), order.limitPrice());
                if (account.cashAvailable < cost) {
                    return false;
                }
                account.cashAvailable -= cost;
                account.cashReserved += cost;
            } else {
                int i = order.asset().ordinal();
                if (account.sharesAvailable[i] < order.quantity()) {
                    return false;
                }
                account.sharesAvailable[i] -= order.quantity();
                account.sharesReserved[i] += order.quantity();
            }
            account.openOrders.put(order.id(), order);
            return true;
        } finally {
            account.lock.unlock();
        }
    }

    /**
     * Cancela o que falta executar da ordem e devolve a reserva correspondente.
     * Devolve a quantidade cancelada (0 se a ordem já estava encerrada).
     */
    public long cancel(Order order) {
        Account account = byId(order.accountId());
        account.lock.lock();
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
            account.lock.unlock();
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
