package minibolsa.market;

import java.util.Objects;

/**
 * Ordem limitada: compra até {@code limitPrice} ou vende a partir dele.
 *
 * <p>O id é global e vem de quem cria a ordem. O número de chegada é dado
 * pelo livro quando a ordem chega até ele, e é a ordem de chegada (não o id)
 * que decide a prioridade de tempo.
 */
public final class Order {

    private final long id;
    private final long accountId;
    private final Asset asset;
    private final Side side;
    private final long quantity;
    private final long limitPrice;
    private long remaining;
    private long arrivalSeq;

    public Order(long id, long accountId, Asset asset, Side side, long quantity, long limitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantidade deve ser positiva: " + quantity);
        }
        if (limitPrice <= 0) {
            throw new IllegalArgumentException("preço deve ser positivo: " + limitPrice);
        }
        this.id = id;
        this.accountId = accountId;
        this.asset = Objects.requireNonNull(asset);
        this.side = Objects.requireNonNull(side);
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.remaining = quantity;
    }

    public long id() {
        return id;
    }

    public long accountId() {
        return accountId;
    }

    public Asset asset() {
        return asset;
    }

    public Side side() {
        return side;
    }

    /** Quantidade original. */
    public long quantity() {
        return quantity;
    }

    public long remaining() {
        return remaining;
    }

    /** Preço limite, em centavos. */
    public long limitPrice() {
        return limitPrice;
    }

    public long arrivalSeq() {
        return arrivalSeq;
    }

    void markArrival(long seq) {
        arrivalSeq = seq;
    }

    void fill(long qty) {
        if (qty <= 0 || qty > remaining) {
            throw new IllegalArgumentException("execução de " + qty + " na ordem " + this);
        }
        remaining -= qty;
    }

    /** Zera o restante e devolve quanto foi cancelado. */
    public long cancel() {
        long canceled = remaining;
        remaining = 0;
        return canceled;
    }

    @Override
    public String toString() {
        return "#" + id + " " + side + " " + asset + " " + remaining + "/" + quantity + " @ " + Money.format(limitPrice);
    }
}
