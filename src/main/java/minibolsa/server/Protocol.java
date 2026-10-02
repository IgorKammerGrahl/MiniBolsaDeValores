package minibolsa.server;

import java.util.List;
import java.util.Locale;
import minibolsa.account.AccountSnapshot;
import minibolsa.market.Asset;
import minibolsa.market.BookSnapshot;
import minibolsa.market.Money;
import minibolsa.market.Order;
import minibolsa.market.Side;
import minibolsa.market.Trade;
import minibolsa.server.CommandException.Code;

/**
 * O protocolo de texto: uma linha por mensagem, comandos sem diferença entre
 * maiúsculas e minúsculas, preços como "38.50". Só funções puras (texto entra,
 * texto sai), testáveis sem rede.
 *
 * <p>Respostas de várias linhas saem como uma única String com '\n' no meio:
 * entram na fila de saída do cliente como uma mensagem só, e um FILL que chegue
 * no meio não as embaralha.
 */
final class Protocol {

    static final int BOOK_DEPTH = 5;

    static final String HELP = """
            HELP
            LOGIN <nome>                 entra (cria a conta se não existir)
            BUY <ATIVO> <QTD> <PRECO>    compra limitada, ex.: BUY PETR4 100 38.50
            SELL <ATIVO> <QTD> <PRECO>   venda limitada
            CANCEL <id>                  cancela o que falta executar de uma ordem
            BOOK <ATIVO>                 livro: BID/ASK <preco> <qtd>, até 5 níveis
            PORTFOLIO                    CASH <disponivel> <reservado>; SHARES <ATIVO> <disponiveis> <reservadas>
            ORDERS                       ORDER <id> <ATIVO> <lado> <restante> <original> <preco>
            TRANSFER <usuario> <valor>   transfere dinheiro disponível
            SUBSCRIBE <ATIVO|ALL>        recebe a cada segundo TICK <ATIVO> <ultimo> <variacao_%> <volume>
            STATS                        contadores do servidor
            HELP                         esta ajuda
            QUIT                         sai
            Ativos: PETR4 VALE3 ITUB4 BBDC4 MGLU3
            END""";

    private Protocol() {
    }

    /** Interpreta uma linha. Lança {@link CommandException} se ela não for um comando válido. */
    static Command parse(String line) {
        String[] t = line.trim().split("\\s+");
        String name = t[0].toUpperCase(Locale.ROOT);
        return switch (name) {
            case "LOGIN" -> {
                expect(t, 1, "LOGIN <nome>");
                yield new Command.Login(t[1]);
            }
            case "BUY", "SELL" -> {
                expect(t, 3, name + " <ATIVO> <QTD> <PRECO>");
                Side side = name.equals("BUY") ? Side.BUY : Side.SELL;
                yield new Command.PlaceOrder(side, asset(t[1]), positiveNumber(t[2], "quantidade"), positiveMoney(t[3]));
            }
            case "CANCEL" -> {
                expect(t, 1, "CANCEL <id>");
                yield new Command.Cancel(positiveNumber(t[1], "id da ordem"));
            }
            case "BOOK" -> {
                expect(t, 1, "BOOK <ATIVO>");
                yield new Command.Book(asset(t[1]));
            }
            case "TRANSFER" -> {
                expect(t, 2, "TRANSFER <usuario> <valor>");
                yield new Command.Transfer(t[1], positiveMoney(t[2]));
            }
            case "SUBSCRIBE" -> {
                expect(t, 1, "SUBSCRIBE <ATIVO|ALL>");
                boolean all = t[1].equalsIgnoreCase("ALL");
                yield new Command.Subscribe(all ? List.of(Asset.values()) : List.of(asset(t[1])), all);
            }
            case "PORTFOLIO" -> noArguments(t, new Command.Portfolio());
            case "ORDERS" -> noArguments(t, new Command.Orders());
            case "STATS" -> noArguments(t, new Command.Stats());
            case "HELP" -> noArguments(t, new Command.Help());
            case "QUIT" -> noArguments(t, new Command.Quit());
            default -> throw invalid("comando desconhecido: " + t[0] + " (veja HELP)");
        };
    }

    static String error(Code code, String message) {
        return "ERR " + code.wire + " " + message;
    }

    /**
     * TICK com o último preço, a variação em relação ao preço inicial e o
     * volume negociado desde que o servidor subiu. A variação é calculada em
     * centésimos de ponto percentual, com inteiros, e sai com duas casas como o
     * dinheiro: 200 → "+2.00".
     */
    static String tick(Asset asset, long lastPrice, long volume) {
        long change = (lastPrice - asset.initialPrice()) * 10_000 / asset.initialPrice();
        return "TICK " + asset + " " + Money.format(lastPrice) + " " + (change > 0 ? "+" : "") + Money.format(change)
                + " " + volume;
    }

    /** FILL para o dono de {@code order}, que é uma das duas pontas do negócio. */
    static String fill(Order order, Trade trade) {
        return "FILL " + order.id() + " " + trade.asset() + " " + order.side() + " " + trade.quantity() + " "
                + Money.format(trade.price());
    }

    static String book(BookSnapshot book) {
        StringBuilder out = new StringBuilder("BOOK ").append(book.asset());
        for (BookSnapshot.Level level : book.bids()) {
            out.append("\nBID ").append(Money.format(level.price())).append(' ').append(level.quantity());
        }
        for (BookSnapshot.Level level : book.asks()) {
            out.append("\nASK ").append(Money.format(level.price())).append(' ').append(level.quantity());
        }
        return out.append("\nEND").toString();
    }

    static String portfolio(AccountSnapshot account) {
        StringBuilder out = new StringBuilder("PORTFOLIO ").append(account.name())
                .append("\nCASH ").append(Money.format(account.cashAvailable()))
                .append(' ').append(Money.format(account.cashReserved()));
        for (AccountSnapshot.Position position : account.positions()) {
            out.append("\nSHARES ").append(position.asset()).append(' ').append(position.available())
                    .append(' ').append(position.reserved());
        }
        return out.append("\nEND").toString();
    }

    static String orders(AccountSnapshot account) {
        StringBuilder out = new StringBuilder("ORDERS");
        for (AccountSnapshot.OpenOrder order : account.openOrders()) {
            out.append("\nORDER ").append(order.id()).append(' ').append(order.asset()).append(' ').append(order.side())
                    .append(' ').append(order.remaining()).append(' ').append(order.quantity())
                    .append(' ').append(Money.format(order.limitPrice()));
        }
        return out.append("\nEND").toString();
    }

    private static void expect(String[] tokens, int arguments, String usage) {
        if (tokens.length != arguments + 1) {
            throw invalid("uso: " + usage);
        }
    }

    private static Command noArguments(String[] tokens, Command command) {
        if (tokens.length != 1) {
            throw invalid(tokens[0] + " não recebe argumentos");
        }
        return command;
    }

    private static Asset asset(String symbol) {
        try {
            return Asset.valueOf(symbol.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CommandException(Code.UNKNOWN_ASSET, "ativo inexistente: " + symbol);
        }
    }

    private static long positiveNumber(String text, String what) {
        try {
            long value = Long.parseLong(text);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // cai no erro abaixo
        }
        throw invalid(what + " deve ser um inteiro positivo: " + text);
    }

    private static long positiveMoney(String text) {
        try {
            long cents = Money.parse(text);
            if (cents > 0) {
                return cents;
            }
        } catch (IllegalArgumentException e) {
            // cai no erro abaixo
        }
        throw invalid("valor deve ser positivo, no formato 38.50: " + text);
    }

    private static CommandException invalid(String message) {
        return new CommandException(Code.INVALID_COMMAND, message);
    }
}
