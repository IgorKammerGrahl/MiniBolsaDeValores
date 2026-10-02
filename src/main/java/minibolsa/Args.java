package minibolsa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opções de linha de comando no formato {@code --nome valor} e {@code --flag}.
 * Opção desconhecida é erro: um {@code --unsafe-acounts} digitado errado não
 * pode ser ignorado em silêncio no meio de uma demonstração.
 */
public final class Args {

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    private Args() {
    }

    /**
     * @param withValue opções que recebem um valor ({@code --port 9000})
     * @param flags     opções sem valor ({@code --unsafe-accounts})
     * @throws IllegalArgumentException opção desconhecida ou sem valor
     */
    public static Args parse(String[] argv, Set<String> withValue, Set<String> flags) {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String name = argv[i];
            if (flags.contains(name)) {
                args.flags.add(name);
            } else if (withValue.contains(name)) {
                if (i + 1 == argv.length) {
                    throw new IllegalArgumentException("falta o valor de " + name);
                }
                args.values.put(name, argv[++i]);
            } else {
                throw new IllegalArgumentException("opção desconhecida: " + name);
            }
        }
        return args;
    }

    public String value(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    public long number(String name, long defaultValue) {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " precisa ser um número: " + value);
        }
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }
}
