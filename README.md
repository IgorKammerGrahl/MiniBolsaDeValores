# Mini Bolsa de Valores

Trabalho da disciplina **Programação Concorrente e Paralela** (BCC).

Uma bolsa de valores simplificada: usuários e robôs se conectam por socket,
enviam ordens de compra e venda de ações, as ordens são casadas num livro de
ofertas e os preços mudam em tempo real.

O domínio é só o pretexto. O objetivo é demonstrar, num exemplo fácil de
explicar:

- atendimento concorrente de clientes com o framework **Executor**;
- processamento **single-writer** por ativo (um executor por ação) comparado a um **lock global**;
- **condições de corrida** reais (saldo negativo, venda de ações que não existem) e sua correção;
- **deadlock** numa transferência entre contas e sua prevenção por **ordenação de locks**;
- **produtor/consumidor** (fila de saída por cliente), tarefas periódicas (cotações) e medição de desempenho.

## Requisitos

- JDK 21 ou mais novo (o projeto é desenvolvido com o JDK 25 e compilado com `--release 21`).
- Maven não é necessário: o projeto traz o Maven Wrapper (`mvnw` / `mvnw.cmd`).

## Compilar e testar

```sh
./mvnw verify          # Linux/macOS
mvnw.cmd verify        # Windows
```

O jar fica em `target/mini-bolsa.jar`.

## Rodar

Um único jar; o primeiro argumento escolhe o modo:

```sh
java -jar target/mini-bolsa.jar help
```

| Modo | O que faz |
|---|---|
| `server` | servidor da bolsa |
| `client` | cliente de terminal (para quem não tem `nc`) |
| `bots` | robôs que negociam sozinhos |
| `bench` | benchmark do motor de ordens, com saída em CSV |

> Projeto em construção: `bench` ainda está vazio e será implementado num próximo PR.

### Servidor e cliente

```sh
java -jar target/mini-bolsa.jar server --port 9000
java -jar target/mini-bolsa.jar client --host localhost --port 9000   # em outro terminal
```

O `nc localhost 9000` também funciona como cliente. Exemplo de sessão:

```
LOGIN ana
OK LOGIN ana 100000.00
SELL PETR4 100 38.50
OK ORDER 1
BOOK PETR4
BOOK PETR4
ASK 38.50 100
END
```

Toda conta nova começa com R$ 100.000,00 e 1.000 ações de cada ativo (PETR4,
VALE3, ITUB4, BBDC4, MGLU3). `HELP` lista os comandos. Quando uma ordem sua é
executada, chega um `FILL <id> <ATIVO> <BUY|SELL> <qtd> <preco>` a qualquer
momento. Com `SUBSCRIBE <ATIVO|ALL>`, chega a cada segundo um
`TICK <ATIVO> <ultimo_preco> <variacao_%> <volume>` (variação sobre o preço
inicial, volume desde que o servidor subiu).

### Monitoramento

- `--audit-every N`: a cada N segundos o servidor verifica as invariantes
  (conservação do dinheiro e das ações, reservas, livros) e loga qualquer
  violação com `!!! AUDITORIA`.
- O watchdog de deadlock roda sempre: se threads ficarem presas esperando umas
  pelas outras, o log mostra `!!! DEADLOCK` com quem espera quem.
- `STATS` mostra ordens, negócios, clientes conectados, violações e deadlocks
  detectados.

### Robôs

```sh
java -jar target/mini-bolsa.jar bots --host localhost --port 9000 --count 50 --rate 10
```

Cada robô abre a sua conexão (`robo-1`, `robo-2`, ...), assina as cotações e,
`--rate` vezes por segundo, compra ou vende uma quantidade pequena a até 2% do
último preço; de vez em quando cancela uma ordem antiga. A cada 5 segundos o
modo `bots` imprime um resumo (ordens, execuções, cancelamentos, recusas). Com
`--transfer-storm`, os robôs formam pares e cada um transfere R$ 1,00 para o
outro sem parar, em vez de negociar.

## Demonstrações com robôs

Cada demonstração usa três terminais: servidor, robôs e um cliente para olhar o
`STATS`. Encerre tudo com Ctrl+C.

### 1. Muita concorrência, nenhuma violação

```sh
java -jar target/mini-bolsa.jar server --audit-every 5          # terminal 1
java -jar target/mini-bolsa.jar bots --count 50 --rate 10       # terminal 2
java -jar target/mini-bolsa.jar client                          # terminal 3: LOGIN obs, depois STATS de vez em quando
```

O que observar:

- no servidor, a cada 5 s: `Auditoria: as cinco invariantes valem.`;
- no cliente, o `STATS` crescendo e sempre com `violations=0`. Numa execução de
  60 s: `orders=8472` aos 20 s, `16787` aos 40 s, `24822` aos 60 s, com cerca de
  300 negócios por segundo.

São 50 conexões, 500 ações por segundo (umas 415 ordens e o resto cancelamentos),
cinco livros em paralelo e liquidações
mexendo nas mesmas contas o tempo todo, e o dinheiro e as ações se conservam.

### 2. A corrida no saldo (modo inseguro)

```sh
java -jar target/mini-bolsa.jar server --unsafe-accounts --race-window-ms 1 --audit-every 5
java -jar target/mini-bolsa.jar bots --count 50 --rate 10
```

Em poucos segundos a auditoria acusa, por exemplo:

```
!!! AUDITORIA: 2 violação(ões) nova(s) das invariantes !!!
!!!   dinheiro não se conserva: total 4999400.38, esperado 5000000.00
!!!   ações de PETR4 não se conservam: total 50010, esperado 50000
```

Sem o lock da conta, a reserva (na thread da sessão) e a liquidação (na thread do
livro) fazem "ler, somar, gravar" no mesmo saldo ao mesmo tempo, e uma das
escritas se perde. O mesmo comando sem `--unsafe-accounts` é a demonstração 1:
nenhuma violação.

### 3. Deadlock e a sua prevenção

```sh
java -jar target/mini-bolsa.jar server --naive-transfer
java -jar target/mini-bolsa.jar bots --count 10 --rate 20 --transfer-storm
```

Em cerca de um segundo o watchdog acusa:

```
!!! DEADLOCK: 4 threads paradas para sempre, cada uma esperando um lock que outra segura !!!
!!!   transferencia-1 espera ...ReentrantLock$NonfairSync@416248ed (que está com transferencia-2), parada em AccountRegistry.lockInterruptibly(...) ← AccountRegistry.transfer(...)
!!!   transferencia-2 espera ...ReentrantLock$NonfairSync@3e153645 (que está com transferencia-1), parada em ...
```

A transferência ingênua trava a origem e depois o destino. `robo-1 → robo-2` e
`robo-2 → robo-1` ao mesmo tempo: cada uma segura a sua origem e espera a outra.
O `STATS` mostra `deadlocks=1`, e o resumo dos robôs para de contar
transferências. Repita **sem** `--naive-transfer`: as mesmas transferências
cruzadas rodam sem parar (cerca de 1.500 em 8 s) e `deadlocks=0`, porque as duas
contas são travadas sempre em ordem crescente de id. No Ctrl+C, o servidor
interrompe as threads presas (os locks da transferência aceitam interrupção) e
encerra normalmente.
