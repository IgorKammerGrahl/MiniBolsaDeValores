package minibolsa.account;

import java.util.ArrayList;
import java.util.List;
import minibolsa.market.Asset;
import minibolsa.market.Money;
import minibolsa.market.Order;
import minibolsa.market.Side;

/**
 * O "teste de verdade" do sistema. Se alguma destas regras falhar, houve uma
 * condição de corrida (ou um bug):
 *
 * <ol>
 *   <li>nenhum saldo ou quantidade de ações é negativo;</li>
 *   <li>dinheiro se conserva: a soma de disponível + reservado de todas as contas é
 *       o número de contas × o saldo inicial;</li>
 *   <li>ações se conservam, ativo por ativo;</li>
 *   <li>a reserva é coerente: o dinheiro reservado de cada conta é a soma de
 *       restante × preço das suas compras abertas, e as ações reservadas são a soma
 *       do restante das suas vendas abertas.</li>
 * </ol>
 *
 * A quinta invariante (nenhum livro cruzado) é verificada pelo motor, que é o
 * único que pode olhar os livros com segurança.
 */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    /**
     * Trava todas as contas em ordem crescente de id (a mesma ordem de todo o
     * resto, então não há deadlock) e verifica as invariantes num estado
     * consistente. Devolve as violações encontradas; lista vazia = tudo certo.
     *
     * <p>No modo inseguro os locks não fazem nada, e a verificação é só "melhor
     * esforço": pode pegar um negócio no meio do caminho.
     */
    public static List<String> check(AccountRegistry registry) {
        List<Account> accounts = registry.all();
        for (Account account : accounts) {
            registry.lock(account);
        }
        try {
            return violations(accounts);
        } finally {
            for (Account account : accounts) {
                registry.unlock(account);
            }
        }
    }

    private static List<String> violations(List<Account> accounts) {
        List<String> violations = new ArrayList<>();
        Asset[] assets = Asset.values();
        long totalCash = 0;
        long[] totalShares = new long[assets.length];

        for (Account a : accounts) {
            if (a.cashAvailable < 0 || a.cashReserved < 0) {
                violations.add(a + ": dinheiro negativo (disponível " + Money.format(a.cashAvailable)
                        + ", reservado " + Money.format(a.cashReserved) + ")");
            }
            totalCash += a.cashAvailable + a.cashReserved;

            long buysOpen = 0;
            long[] sellsOpen = new long[assets.length];
            for (Order order : a.openOrders.values()) {
                if (order.side() == Side.BUY) {
                    buysOpen += order.remaining() * order.limitPrice();
                } else {
                    sellsOpen[order.asset().ordinal()] += order.remaining();
                }
            }
            if (a.cashReserved != buysOpen) {
                violations.add(a + ": dinheiro reservado " + Money.format(a.cashReserved)
                        + " ≠ compras abertas " + Money.format(buysOpen));
            }

            for (Asset asset : assets) {
                int i = asset.ordinal();
                if (a.sharesAvailable[i] < 0 || a.sharesReserved[i] < 0) {
                    violations.add(a + ": ações de " + asset + " negativas (disponíveis " + a.sharesAvailable[i]
                            + ", reservadas " + a.sharesReserved[i] + ")");
                }
                totalShares[i] += a.sharesAvailable[i] + a.sharesReserved[i];
                if (a.sharesReserved[i] != sellsOpen[i]) {
                    violations.add(a + ": " + a.sharesReserved[i] + " ações de " + asset
                            + " reservadas ≠ vendas abertas " + sellsOpen[i]);
                }
            }
        }

        long expectedCash = accounts.size() * Account.INITIAL_CASH;
        if (totalCash != expectedCash) {
            violations.add("dinheiro não se conserva: total " + Money.format(totalCash)
                    + ", esperado " + Money.format(expectedCash));
        }
        long expectedShares = accounts.size() * Account.INITIAL_SHARES;
        for (Asset asset : assets) {
            if (totalShares[asset.ordinal()] != expectedShares) {
                violations.add("ações de " + asset + " não se conservam: total " + totalShares[asset.ordinal()]
                        + ", esperado " + expectedShares);
            }
        }
        return violations;
    }
}
