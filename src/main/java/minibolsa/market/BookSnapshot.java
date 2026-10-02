package minibolsa.market;

import java.util.List;

/** Cópia dos melhores níveis do livro (comando BOOK), do melhor preço para o pior. */
public record BookSnapshot(Asset asset, List<Level> bids, List<Level> asks) {

    /** Um preço e a quantidade somada de todas as ordens nele. */
    public record Level(long price, long quantity) {
    }
}
