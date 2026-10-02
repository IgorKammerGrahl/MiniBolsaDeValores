package minibolsa;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import minibolsa.client.TerminalClient;
import minibolsa.server.Server;

/** Ponto de entrada único: o primeiro argumento escolhe o modo. */
public final class Main {

    private static final String HELP = """
            Uso: java -jar mini-bolsa.jar <modo> [opções]

            Modos:
              server   servidor da bolsa
                         --port 9000
                         --engine single-writer|global-lock
                         --unsafe-accounts        desliga os locks das contas (demonstração)
                         --race-window-ms 0       pausa entre verificar o saldo e debitar
                         --naive-transfer         transferência que pode dar deadlock (demonstração)
                         --transfer-pause-ms 10   pausa entre os dois locks da transferência ingênua
              client   cliente de terminal (--host localhost --port 9000)
              bots     robôs que negociam sozinhos
              bench    benchmark do motor de ordens (em processo, saída CSV)
              help     mostra esta ajuda
            """;

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] options = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        try {
            switch (mode) {
                case "help", "-h", "--help" -> System.out.print(HELP);
                case "server" -> Server.run(options);
                case "client" -> TerminalClient.run(options);
                case "bots", "bench" -> Log.info("Modo '" + mode + "' ainda não implementado.");
                default -> {
                    System.err.println("Modo desconhecido: " + args[0]);
                    System.err.print(HELP);
                    System.exit(2);
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(2);
        }
    }
}
