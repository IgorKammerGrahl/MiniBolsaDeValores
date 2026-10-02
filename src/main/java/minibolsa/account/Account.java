package minibolsa.account;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import minibolsa.market.Asset;
import minibolsa.market.Order;

/**
 * Conta de um usuário: dinheiro e ações, cada um separado em disponível e
 * reservado (comprometido com ordens abertas).
 *
 * <p>A conta só guarda o estado e o próprio lock. Quem trava e altera é o
 * {@link AccountRegistry}, sempre com o lock na mão, para que todas as regras de
 * travamento fiquem num lugar só.
 */
public final class Account {

    /** R$ 100.000,00 em centavos. */
    public static final long INITIAL_CASH = 100_000_00;
    public static final long INITIAL_SHARES = 1_000;

    final ReentrantLock lock = new ReentrantLock();
    final long id;
    final String name;

    long cashAvailable = INITIAL_CASH;
    long cashReserved;
    /** Ações por ativo, indexadas por {@link Asset#ordinal()}. */
    final long[] sharesAvailable = new long[Asset.values().length];
    final long[] sharesReserved = new long[Asset.values().length];
    /** Ordens abertas, por id. É concorrente para que o modo inseguro corrompa só os saldos, nunca o mapa. */
    final Map<Long, Order> openOrders = new ConcurrentHashMap<>();

    Account(long id, String name) {
        this.id = id;
        this.name = name;
        Arrays.fill(sharesAvailable, INITIAL_SHARES);
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "conta " + id + " (" + name + ")";
    }
}
