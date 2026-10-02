package minibolsa.engine;

/** Ordem recusada na reserva: faltou dinheiro (compra) ou ações (venda). */
public final class OrderRejectedException extends RuntimeException {

    public enum Reason {
        INSUFFICIENT_CASH,
        INSUFFICIENT_SHARES
    }

    private final Reason reason;

    public OrderRejectedException(Reason reason) {
        super(reason == Reason.INSUFFICIENT_CASH ? "saldo insuficiente" : "ações insuficientes");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
