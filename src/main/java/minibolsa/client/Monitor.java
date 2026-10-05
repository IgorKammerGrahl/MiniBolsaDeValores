package minibolsa.client;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import minibolsa.Args;

/**
 * Modo {@code monitor}: uma janela Swing que se conecta como cliente, assina
 * todas as cotações e mostra uma tabela e o gráfico do preço do ativo
 * selecionado.
 *
 * <p>Duas threads, com papéis bem separados:
 * <ul>
 *   <li>a thread principal lê o socket e transforma cada TICK num valor imutável;</li>
 *   <li>a thread do Swing (EDT) é a única que mexe na janela.</li>
 * </ul>
 * Toda atualização passa de uma para a outra por {@link SwingUtilities#invokeLater}.
 * O Swing não é thread-safe: mexer num componente fora da EDT é uma condição de
 * corrida.
 */
public final class Monitor {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Monitor() {
    }

    public static void run(String[] argv) throws IOException {
        Args args = Args.parse(argv, Set.of("--host", "--port"), Set.of());
        String host = args.value("--host", "localhost");
        int port = (int) args.number("--port", 9000);
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException("o monitor precisa de uma tela, e este ambiente não tem interface gráfica");
        }

        try (Socket socket = new Socket(host, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), UTF_8);
            out.write("LOGIN monitor\nSUBSCRIBE ALL\n");
            out.flush();

            // A janela é criada na EDT; o future a devolve para esta thread quando estiver pronta.
            CompletableFuture<MonitorWindow> created = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                try {
                    created.complete(new MonitorWindow(host + ":" + port));
                } catch (RuntimeException e) {
                    created.completeExceptionally(e); // sem isso, esta thread esperaria para sempre
                }
            });
            MonitorWindow window = created.join();

            String line;
            while ((line = in.readLine()) != null) {
                QuoteBoard.Tick tick = QuoteBoard.Tick.parse(line);
                if (tick != null) {
                    SwingUtilities.invokeLater(() -> window.onTick(tick));
                }
            }
            SwingUtilities.invokeLater(window::onDisconnected);
        }
    }

    /** A janela e tudo o que está nela. Criada e usada só na EDT. */
    private static final class MonitorWindow {

        private final QuoteBoard board = new QuoteBoard();
        private final PriceChart chart = new PriceChart(board);
        private final JLabel status = new JLabel("Esperando cotações...");
        private final JFrame frame;

        MonitorWindow(String server) {
            frame = new JFrame("Mini Bolsa: cotações de " + server);
            JTable table = new JTable(board);
            table.setFont(table.getFont().deriveFont(16f));
            table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(15f));
            table.setRowHeight(28);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setRowSelectionInterval(0, 0);
            table.getSelectionModel().addListSelectionListener(e -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    chart.setAsset(board.assetAt(row));
                }
            });
            DefaultTableCellRenderer right = new DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(1).setCellRenderer(right);
            table.getColumnModel().getColumn(2).setCellRenderer(new ChangeRenderer());

            JScrollPane quotes = new JScrollPane(table);
            quotes.setPreferredSize(new Dimension(720, table.getRowHeight() * board.getRowCount() + 32));
            status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

            frame.add(quotes, BorderLayout.NORTH);
            frame.add(chart, BorderLayout.CENTER);
            frame.add(status, BorderLayout.SOUTH);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        void onTick(QuoteBoard.Tick tick) {
            board.update(tick);
            chart.repaint();
            status.setText("Última cotação às " + LocalTime.now().format(TIME)
                    + "  |  clique num ativo para ver o gráfico dele");
        }

        void onDisconnected() {
            status.setText("Conexão encerrada pelo servidor.");
            status.setForeground(Color.RED);
        }
    }

    /** Variação em verde quando sobe e em vermelho quando cai, alinhada à direita. */
    private static final class ChangeRenderer extends DefaultTableCellRenderer {

        private static final Color UP = new Color(0x1A7F37);
        private static final Color DOWN = new Color(0xCF222E);

        ChangeRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            String change = String.valueOf(value);
            if (!selected) {
                setForeground(change.startsWith("+") ? UP : change.startsWith("-") ? DOWN : Color.DARK_GRAY);
            }
            return this;
        }
    }
}
