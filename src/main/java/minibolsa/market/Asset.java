package minibolsa.market;

/** Ativos negociados, com o preço inicial em centavos. */
public enum Asset {
    PETR4(3850),
    VALE3(6200),
    ITUB4(3420),
    BBDC4(1480),
    MGLU3(990);

    private final long initialPrice;

    Asset(long initialPrice) {
        this.initialPrice = initialPrice;
    }

    public long initialPrice() {
        return initialPrice;
    }
}
