package minibolsa.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void parsesReaisAndCents() {
        assertEquals(3850, Money.parse("38.50"));
        assertEquals(3850, Money.parse("38.5"));
        assertEquals(3800, Money.parse("38"));
        assertEquals(1, Money.parse("0.01"));
        assertEquals(10_000_000, Money.parse("100000.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "38,50", "38.505", "-1", "+1", "1e3", " 38.50", ".50", "38."})
    void rejectsInvalidText(String text) {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(text));
    }

    @Test
    void formatsWithTwoDecimals() {
        assertEquals("38.50", Money.format(3850));
        assertEquals("0.05", Money.format(5));
        assertEquals("0.00", Money.format(0));
        assertEquals("100000.00", Money.format(10_000_000));
        assertEquals("-0.50", Money.format(-50));
        assertEquals("-12.07", Money.format(-1207));
    }
}
