package minibolsa.server;

/** Erro que vira uma resposta {@code ERR <CODIGO> <mensagem>} para o cliente. */
final class CommandException extends RuntimeException {

    /** Os códigos de erro do protocolo. */
    enum Code {
        NOT_AUTHENTICATED("NAO_AUTENTICADO"),
        INVALID_COMMAND("COMANDO_INVALIDO"),
        UNKNOWN_ASSET("ATIVO_INEXISTENTE"),
        INSUFFICIENT_CASH("SALDO_INSUFICIENTE"),
        INSUFFICIENT_SHARES("ACOES_INSUFICIENTES"),
        UNKNOWN_ORDER("ORDEM_INEXISTENTE"),
        UNKNOWN_USER("USUARIO_INEXISTENTE");

        /** Como o código aparece na linha enviada ao cliente. */
        final String wire;

        Code(String wire) {
            this.wire = wire;
        }
    }

    private final Code code;

    CommandException(Code code, String message) {
        super(message);
        this.code = code;
    }

    Code code() {
        return code;
    }
}
