package minibolsa.client;

import static minibolsa.market.Asset.PETR4;
import static minibolsa.market.Asset.VALE3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O modelo e o gráfico do monitor, sem abrir janela nenhuma. */
class QuoteBoardTest {

    private final QuoteBoard board = new QuoteBoard();

    @Test
    void parsesOnlyValidTicks() {
        assertEquals(new QuoteBoard.Tick(PETR4, 3927, "+2.00", 10), QuoteBoard.Tick.parse("TICK PETR4 39.27 +2.00 10"));
        assertNull(QuoteBoard.Tick.parse("FILL 3 PETR4 BUY 10 39.27"));
        assertNull(QuoteBoard.Tick.parse("TICK XPTO3 1.00 0.00 0"));
        assertNull(QuoteBoard.Tick.parse("TICK PETR4 abc 0.00 0"));
        assertNull(QuoteBoard.Tick.parse("OK SUBSCRIBE ALL"));
    }

    @Test
    void startsWithInitialPricesAndUpdatesOnlyTheTickedRow() {
        List<Integer> updatedRows = new ArrayList<>();
        board.addTableModelListener(e -> updatedRows.add(e.getFirstRow()));

        assertEquals("38.50", board.getValueAt(PETR4.ordinal(), 1));
        board.update(new QuoteBoard.Tick(VALE3, 6138, "-1.00", 25));

        assertEquals(List.of(VALE3.ordinal()), updatedRows);
        assertEquals("VALE3", board.getValueAt(VALE3.ordinal(), 0));
        assertEquals("61.38", board.getValueAt(VALE3.ordinal(), 1));
        assertEquals("-1.00", board.getValueAt(VALE3.ordinal(), 2));
        assertEquals(25L, board.getValueAt(VALE3.ordinal(), 3));
        assertEquals("38.50", board.getValueAt(PETR4.ordinal(), 1)); // as outras linhas não mudam
    }

    @Test
    void historyKeepsOnlyTheLastTwoMinutes() {
        for (int i = 1; i <= QuoteBoard.HISTORY + 10; i++) {
            board.update(new QuoteBoard.Tick(PETR4, 3800 + i, "0.00", i));
        }

        List<Long> history = board.history(PETR4);
        assertEquals(QuoteBoard.HISTORY, history.size());
        assertEquals(3811, history.getFirst()); // os 10 mais antigos saíram
        assertEquals(3800 + QuoteBoard.HISTORY + 10, history.getLast());
        assertTrue(board.history(VALE3).isEmpty());
    }

    @Test
    void chartDrawsTheLineIntoAnImage() {
        PriceChart chart = new PriceChart(board);
        chart.setSize(400, 200);
        for (int i = 0; i < 30; i++) {
            board.update(new QuoteBoard.Tick(PETR4, 3850 + (i % 7) * 5, "0.00", i));
        }

        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        chart.paint(g);
        g.dispose();

        assertTrue(countPixels(image, new Color(0x1F77B4)) > 50, "a linha do preço não foi desenhada");
    }

    private static int countPixels(BufferedImage image, Color color) {
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) == color.getRGB()) {
                    count++;
                }
            }
        }
        return count;
    }
}
