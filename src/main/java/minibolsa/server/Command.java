package minibolsa.server;

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

    record Stats() implements Command {
    }

    record Help() implements Command {
    }

    record Quit() implements Command {
    }
}
