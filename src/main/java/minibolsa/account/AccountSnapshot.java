package minibolsa.account;

import java.util.List;
import minibolsa.market.Asset;
import minibolsa.market.Side;

/**
 * Cópia do estado de uma conta, tirada com o lock da conta (comandos PORTFOLIO
 * e ORDERS). Por ser uma cópia, pode ser formatada e enviada sem lock nenhum.
 */
public record AccountSnapshot(String name, long cashAvailable, long cashReserved,
                              List<Position> positions, List<OpenOrder> openOrders) {

    public record Position(Asset asset, long available, long reserved) {
    }

    public record OpenOrder(long id, Asset asset, Side side, long remaining, long quantity, long limitPrice) {
    }
}
