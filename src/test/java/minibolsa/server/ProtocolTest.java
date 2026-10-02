package minibolsa.server;

import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Asset.VALE3;
import static minibolsa.market.Side.BUY;
import static minibolsa.market.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import minibolsa.account.AccountSnapshot;
import minibolsa.market.Asset;
import minibolsa.market.BookSnapshot;
import minibolsa.market.Order;
import minibolsa.market.Trade;
import minibolsa.server.CommandException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** O protocolo sem rede: texto entra, comando ou texto sai. */
class ProtocolTest {

    @Test
    void parsesEveryCommandIgnoringCaseAndExtraSpaces() {
        assertEquals(new Command.Login("ana"), Protocol.parse("LOGIN ana"));
        assertEquals(new Command.Login("Ana"), Protocol.parse("login Ana"));
        assertEquals(new Command.PlaceOrder(BUY, PETR4, 100, 3850), Protocol.parse("BUY PETR4 100 38.50"));
        assertEquals(new Command.PlaceOrder(SELL, VALE3, 10, 6200), Protocol.parse("  sell   vale3 10 62  "));
        assertEquals(new Command.Cancel(12), Protocol.parse("cancel 12"));
        assertEquals(new Command.Book(PETR4), Protocol.parse("Book petr4"));
        assertEquals(new Command.Transfer("bia", 10050), Protocol.parse("TRANSFER bia 100.50"));
        assertEquals(new Command.Portfolio(), Protocol.parse("PORTFOLIO"));
        assertEquals(new Command.Orders(), Protocol.parse("orders"));
        assertEquals(new Command.Stats(), Protocol.parse("STATS"));
        assertEquals(new Command.Help(), Protocol.parse("help"));
        assertEquals(new Command.Quit(), Protocol.parse("QUIT"));
        assertEquals(new Command.Subscribe(List.of(PETR4), false), Protocol.parse("subscribe petr4"));
        assertEquals(new Command.Subscribe(List.of(Asset.values()), true), Protocol.parse("SUBSCRIBE all"));
    }

    @Test
    void formatsTicksWithChangeAgainstTheInitialPrice() {
        assertEquals("TICK PETR4 38.50 0.00 0", Protocol.tick(PETR4, 3850, 0));
        assertEquals("TICK PETR4 39.27 +2.00 150", Protocol.tick(PETR4, 3927, 150)); // 77 / 3850 = 2%
        assertEquals("TICK VALE3 61.38 -1.00 10", Protocol.tick(VALE3, 6138, 10)); // −62 / 6200 = −1%
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "FOO                     | INVALID_COMMAND",
        "''                      | INVALID_COMMAND",
        "LOGIN                   | INVALID_COMMAND",
        "LOGIN ana bia           | INVALID_COMMAND",
        "BUY PETR4 100           | INVALID_COMMAND",
        "BUY PETR4 0 38.50       | INVALID_COMMAND",
        "BUY PETR4 -5 38.50      | INVALID_COMMAND",
        "BUY PETR4 abc 38.50     | INVALID_COMMAND",
        "BUY PETR4 99999999999999999999 38.50 | INVALID_COMMAND",
        "BUY PETR4 10 0          | INVALID_COMMAND",
        "BUY PETR4 10 38,50      | INVALID_COMMAND",
        "SELL XPTO3 10 1.00      | UNKNOWN_ASSET",
        "BOOK XPTO3              | UNKNOWN_ASSET",
        "CANCEL abc              | INVALID_COMMAND",
        "TRANSFER bia 0.00       | INVALID_COMMAND",
        "PORTFOLIO agora         | INVALID_COMMAND",
        "SUBSCRIBE               | INVALID_COMMAND",
        "SUBSCRIBE XPTO3         | UNKNOWN_ASSET",
    })
    void rejectsInvalidLinesWithTheRightCode(String line, Code code) {
        CommandException e = assertThrows(CommandException.class, () -> Protocol.parse(line));
        assertEquals(code, e.code());
    }

    @Test
    void formatsErrorsAndFills() {
        assertEquals("ERR SALDO_INSUFICIENTE saldo insuficiente",
                Protocol.error(Code.INSUFFICIENT_CASH, "saldo insuficiente"));

        Order buy = new Order(7, 1, PETR4, BUY, 100, 3900);
        Order sell = new Order(3, 2, PETR4, SELL, 60, 3850);
        Trade trade = new Trade(PETR4, buy, sell, 60, 3850);
        assertEquals("FILL 7 PETR4 BUY 60 38.50", Protocol.fill(buy, trade));
        assertEquals("FILL 3 PETR4 SELL 60 38.50", Protocol.fill(sell, trade));
    }

    @Test
    void formatsMultiLineResponsesAsOneMessage() {
        BookSnapshot book = new BookSnapshot(PETR4,
                List.of(new BookSnapshot.Level(3840, 200)), List.of(new BookSnapshot.Level(3860, 300)));
        assertEquals("BOOK PETR4\nBID 38.40 200\nASK 38.60 300\nEND", Protocol.book(book));

        AccountSnapshot account = new AccountSnapshot("ana", 9615000, 385000,
                List.of(new AccountSnapshot.Position(PETR4, 1000, 0)),
                List.of(new AccountSnapshot.OpenOrder(12, PETR4, BUY, 70, 100, 3850)));
        assertEquals("PORTFOLIO ana\nCASH 96150.00 3850.00\nSHARES PETR4 1000 0\nEND", Protocol.portfolio(account));
        assertEquals("ORDERS\nORDER 12 PETR4 BUY 70 100 38.50\nEND", Protocol.orders(account));
    }
}
