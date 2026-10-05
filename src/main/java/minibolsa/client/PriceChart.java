package minibolsa.client;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import minibolsa.market.Asset;
import minibolsa.market.Money;

/**
 * Gráfico de linha do preço de um ativo nos últimos {@link QuoteBoard#HISTORY}
 * TICKs. O eixo horizontal é o tempo (um ponto por segundo), e o vertical vai do
 * menor ao maior preço do período.
 */
final class PriceChart extends JPanel {

    private static final Color LINE = new Color(0x1F77B4);
    private static final Color AXIS = new Color(0x888888);

    private final QuoteBoard board;
    private Asset asset = Asset.PETR4;

    PriceChart(QuoteBoard board) {
        this.board = board;
        setPreferredSize(new Dimension(720, 320));
        setBackground(Color.WHITE);
    }

    /** Troca o ativo mostrado. Só na EDT. */
    void setAsset(Asset asset) {
        this.asset = asset;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        g.setColor(Color.DARK_GRAY);
        g.drawString(asset + ": últimos " + QuoteBoard.HISTORY + " segundos", 12, 22);

        List<Long> prices = board.history(asset);
        g.setFont(getFont().deriveFont(13f));
        if (prices.size() < 2) {
            g.drawString("esperando cotações...", 12, 50);
            return;
        }
        long min = Collections.min(prices);
        long max = Collections.max(prices);
        if (min == max) { // linha reta: abre um centavo para cada lado
            min--;
            max++;
        }

        int left = 72;
        int right = getWidth() - 16;
        int top = 40;
        int bottom = getHeight() - 24;
        g.setColor(AXIS);
        g.drawLine(left, top, left, bottom);
        g.drawLine(left, bottom, right, bottom);
        g.drawString(Money.format(max), 12, top + 5);
        g.drawString(Money.format(min), 12, bottom);

        int[] xs = new int[prices.size()];
        int[] ys = new int[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            xs[i] = left + (right - left) * i / (QuoteBoard.HISTORY - 1);
            ys[i] = bottom - (int) ((bottom - top) * (prices.get(i) - min) / (max - min));
        }
        g.setColor(LINE);
        g.setStroke(new BasicStroke(2.5f));
        g.drawPolyline(xs, ys, xs.length);
    }
}
