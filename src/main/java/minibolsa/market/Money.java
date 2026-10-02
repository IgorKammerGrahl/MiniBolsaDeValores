package minibolsa.market;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dinheiro é sempre {@code long} em centavos ({@code 38.50 → 3850}); nunca
 * {@code double}, que não representa centavos exatamente. A conversão para
 * texto só acontece na borda: protocolo e exibição.
 */
public final class Money {

    /** Até 12 dígitos de reais e até 2 de centavos, com ponto: "38", "38.5", "38.50". */
    private static final Pattern TEXT = Pattern.compile("(\\d{1,12})(?:\\.(\\d{1,2}))?");

    private Money() {
    }

    /** "38.50" → 3850. Lança {@link IllegalArgumentException} se o texto não for um valor válido. */
    public static long parse(String text) {
        Matcher m = TEXT.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("valor inválido: " + text);
        }
        String cents = m.group(2) == null ? "00" : (m.group(2) + "0").substring(0, 2);
        return Long.parseLong(m.group(1)) * 100 + Long.parseLong(cents);
    }

    /** 3850 → "38.50". Valores negativos (possíveis no modo inseguro) saem como "-0.50". */
    public static String format(long cents) {
        long abs = Math.abs(cents);
        long rest = abs % 100;
        return (cents < 0 ? "-" : "") + abs / 100 + (rest < 10 ? ".0" : ".") + rest;
    }
}
