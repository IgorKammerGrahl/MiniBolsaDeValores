package minibolsa.market;

/**
 * Um negócio: {@code quantity} ações de {@code asset} ao preço {@code price},
 * que é sempre o da ordem que já estava no livro.
 */
public record Trade(Asset asset, Order buy, Order sell, long quantity, long price) {

    /** Desconta a quantidade das duas ordens. Quem chama é a liquidação (ver {@link OrderBook}). */
    public void fillOrders() {
        buy.fill(quantity);
        sell.fill(quantity);
    }
}
