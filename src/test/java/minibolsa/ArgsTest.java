package minibolsa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ArgsTest {

    private static final Set<String> VALUES = Set.of("--port", "--engine");
    private static final Set<String> FLAGS = Set.of("--unsafe-accounts");

    @Test
    void readsValuesFlagsAndDefaults() {
        Args args = Args.parse(new String[] {"--unsafe-accounts", "--port", "9001"}, VALUES, FLAGS);

        assertEquals(9001, args.number("--port", 9000));
        assertEquals("single-writer", args.value("--engine", "single-writer"));
        assertTrue(args.flag("--unsafe-accounts"));
        assertFalse(Args.parse(new String[0], VALUES, FLAGS).flag("--unsafe-accounts"));
    }

    @Test
    void rejectsUnknownOptionMissingValueAndNonNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--unsafe-acounts"}, VALUES, FLAGS));
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"--port"}, VALUES, FLAGS));
        Args args = Args.parse(new String[] {"--port", "abc"}, VALUES, FLAGS);
        assertThrows(IllegalArgumentException.class, () -> args.number("--port", 9000));
    }
}
