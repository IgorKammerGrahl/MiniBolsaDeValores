package minibolsa;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Log mínimo: cada linha leva o horário e o nome da thread, para que na
 * demonstração dê para ver qual thread fez o quê.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String message) {
        System.out.println(prefix() + message);
    }

    public static void error(String message) {
        System.err.println(prefix() + message);
    }

    private static String prefix() {
        return "[" + LocalTime.now().format(TIME) + "] [" + Thread.currentThread().getName() + "] ";
    }
}
