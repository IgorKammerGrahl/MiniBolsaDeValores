package minibolsa;

import java.util.Locale;

/** Ponto de entrada único: o primeiro argumento escolhe o modo. */
public final class Main {

    private static final String HELP = """
            Uso: java -jar mini-bolsa.jar <modo> [opções]

            Modos:
              server   servidor da bolsa
              client   cliente de terminal
              bots     robôs que negociam sozinhos
              bench    benchmark do motor de ordens (em processo, saída CSV)
              help     mostra esta ajuda
            """;

    private Main() {
    }

    public static void main(String[] args) {
        String mode = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "help", "-h", "--help" -> System.out.print(HELP);
            case "server", "client", "bots", "bench" -> Log.info("Modo '" + mode + "' ainda não implementado.");
            default -> {
                System.err.println("Modo desconhecido: " + args[0]);
                System.err.print(HELP);
                System.exit(2);
            }
        }
    }
}
