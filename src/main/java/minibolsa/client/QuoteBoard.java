package minibolsa.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import minibolsa.market.Asset;
import minibolsa.market.Money;

/**
 * As cotações do monitor: a tabela (um {@link AbstractTableModel}) e o histórico
 * de preços de cada ativo para o gráfico.
 *
 * <p>Confinado à thread do Swing (EDT): só ela chama {@link #update} e só ela lê
 * para desenhar, então não há lock nenhum. A thread que lê o socket só transforma
 * a linha num {@link Tick} imutável e o entrega à EDT com
 * {@code SwingUtilities.invokeLater}. É a mesma ideia do single-writer do livro de
 * ofertas: um único dono para o estado.
 */
final class QuoteBoard extends AbstractTableModel {

    /** Quantos TICKs o gráfico guarda: dois minutos, a um por segundo. */
    static final int HISTORY = 120;

    private static final Asset[] ASSETS = Asset.values();
    private static final String[] COLUMNS = {"Ativo", "Último", "Variação %", "Volume"};

    /** Um TICK já interpretado. Imutável: pode passar de uma thread para outra sem cuidado nenhum. */
    record Tick(Asset asset, long lastPrice, String change, long volume) {

        /** {@code TICK PETR4 39.27 +2.00 10} vira um Tick; qualquer outra linha, {@code null}. */
        static Tick parse(String line) {
            String[] fields = line.split(" ");
            if (fields.length != 5 || !fields[0].equals("TICK")) {
                return null;
            }
            try {
                return new Tick(Asset.valueOf(fields[1]), Money.parse(fields[2]), fields[3],
                        Long.parseLong(fields[4]));
            } catch (IllegalArgumentException e) { // ativo, preço ou volume inválidos
                return null;
            }
        }
    }

    private final Tick[] latest = new Tick[ASSETS.length];
    private final List<ArrayDeque<Long>> history = new ArrayList<>();

    QuoteBoard() {
        for (Asset asset : ASSETS) {
            latest[asset.ordinal()] = new Tick(asset, asset.initialPrice(), "0.00", 0);
            history.add(new ArrayDeque<>());
        }
    }

    /** Aplica um TICK. Só na EDT. */
    void update(Tick tick) {
        int row = tick.asset().ordinal();
        latest[row] = tick;
        ArrayDeque<Long> prices = history.get(row);
        prices.addLast(tick.lastPrice());
        if (prices.size() > HISTORY) {
            prices.removeFirst();
        }
        fireTableRowsUpdated(row, row);
    }

    /** Os últimos preços do ativo, do mais antigo para o mais novo. */
    List<Long> history(Asset asset) {
        return List.copyOf(history.get(asset.ordinal()));
    }

    Asset assetAt(int row) {
        return ASSETS[row];
    }

    @Override
    public int getRowCount() {
        return ASSETS.length;
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 3 ? Long.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Tick tick = latest[row];
        return switch (column) {
            case 0 -> tick.asset().name();
            case 1 -> Money.format(tick.lastPrice());
            case 2 -> tick.change();
            default -> tick.volume();
        };
    }
}
