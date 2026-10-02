package minibolsa.server;

import java.util.List;
import minibolsa.market.Asset;
import minibolsa.market.Side;

/** Um comando do cliente já interpretado e validado pelo {@link Protocol}. Valores em centavos. */
sealed interface Command {

    record Login(String name) implements Command {
    }

    record PlaceOrder(Side side, Asset asset, long quantity, long limitPrice) implements Command {
    }

    record Cancel(long orderId) implements Command {
    }

    record Book(Asset asset) implements Command {
    }

    record Portfolio() implements Command {
    }

    record Orders() implements Command {
    }

    record Transfer(String user, long amount) implements Command {
    }

    /** {@code all} = SUBSCRIBE ALL; nesse caso {@code assets} traz todos os ativos. */
    record Subscribe(List<Asset> assets, boolean all) implements Command {
    }

    record Stats() implements Command {
    }

    record Help() implements Command {
    }

    record Quit() implements Command {
    }
}
