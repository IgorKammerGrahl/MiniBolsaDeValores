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

## Benchmark

Um comando roda todos os experimentos e grava o CSV (cerca de 1min30s numa
máquina de 16 núcleos):

```sh
java -jar target/mini-bolsa.jar bench --output benchmark.csv
```

Opções: `--orders 200000` (ordens por rodada) e `--repetitions 5` (rodadas
medidas por configuração).

### Como é medido

- **Em processo, sem rede.** Mede o motor, não o socket.
- **Experimentos.** `single-writer` e `global-lock`, com 1, 2, 4, 8 e 16 threads
  produtoras, em dois cenários: ordens espalhadas pelos 5 ativos (`5-ativos`) e
  todas num ativo só (`1-ativo`).
- **Cada produtor tem uma conta** e manda uma ordem por vez: reserva, envia e
  espera o `CompletableFuture`, como uma sessão do servidor. A latência de uma
  ordem vai do início da reserva até o resultado.
- **Carga.** São pares "vende q a um preço até 1% acima ou abaixo do inicial,
  compra q com limite no topo dessa faixa". Quase toda ordem gera negócio, o
  livro não acumula ordens e nenhuma é recusada. Se uma ordem fosse recusada, o
  benchmark pararia.
- **Volume.** São sempre 200 mil ordens por rodada, divididas entre os produtores.
- **Repetições.** Para cada configuração: 1 rodada de aquecimento, descartada, para
  o JIT compilar o caminho quente, e 5 rodadas medidas. O CSV traz a mediana de
  cada métrica.
- **Invariantes.** Ao fim de cada rodada as cinco invariantes são verificadas.
  Benchmark com resultado errado não vale: se alguma falhar, o programa para.
- O pool do `global-lock` tem uma thread por núcleo.

Colunas do CSV: `estrategia, threads, cenario, vazao_ordens_por_s, p50_us, p99_us`.

### Resultado nesta máquina (16 núcleos, Java 25)

Ordens espalhadas pelos 5 ativos:

| Produtores | single-writer (ordens/s) | global-lock (ordens/s) | p99 single-writer | p99 global-lock |
|---:|---:|---:|---:|---:|
| 1 | 106 mil | 90 mil | 24 µs | 30 µs |
| 2 | 302 mil | 227 mil | 17 µs | 36 µs |
| 4 | 226 mil | 437 mil | 45 µs | 56 µs |
| 8 | 848 mil | 709 mil | 25 µs | 65 µs |
| 16 | 1,40 milhão | 773 mil | 46 µs | 79 µs |

Todas as ordens num ativo só:

| Produtores | single-writer (ordens/s) | global-lock (ordens/s) | p99 single-writer | p99 global-lock |
|---:|---:|---:|---:|---:|
| 1 | 132 mil | 99 mil | 17 µs | 29 µs |
| 2 | 371 mil | 213 mil | 10 µs | 36 µs |
| 4 | 750 mil | 444 mil | 10 µs | 54 µs |
| 8 | 971 mil | 433 mil | 14 µs | 91 µs |
| 16 | 905 mil | 631 mil | 27 µs | 97 µs |

O que os números mostram:

1. **Com muitos produtores, o single-writer vence nos dois cenários**, e com uma
   cauda de latência bem menor. O livro dele não tem lock. No global-lock, as
   threads do pool disputam um lock só, e o p99 cresce com elas (de 29 para 97 µs
   com um ativo).
2. **Com um ativo só, o single-writer para de escalar** em cerca de 1 milhão de
   ordens/s, o limite de uma thread. De 8 para 16 produtores a vazão cai (971 mil
   → 905 mil) e o p50 dobra (7,9 → 16,6 µs): forma fila na única thread do livro.
   Com 5 ativos ele continua crescendo até 16 produtores (1,40 milhão). O
   paralelismo do single-writer é limitado pelo número de partições.
3. **A vantagem do single-writer diminui com um ativo, mas não some** (com 16
   produtores, de 1,8× para 1,4×). Some o ganho do particionamento, porque os dois
   viram uma fila só. Fica o custo de o global-lock passar o lock de thread em thread.
4. **Com poucos produtores, a vazão é limitada pela latência de ida e volta**
   (acordar a thread do motor e depois o produtor), não pela capacidade do motor.
   Particionar tem custo. Com 4 produtores espalhados por 5 livros, cada thread de
   livro passa a maior parte do tempo parada, e cada ordem paga para acordá-la
   (p50 de 16 µs). Com um livro só, a thread nunca para. Essa é a explicação mais
   provável de o cenário `5-ativos` ficar abaixo do `1-ativo` nessa faixa (o efeito
   se repetiu em rodadas diferentes).

Os números absolutos mudam de máquina para máquina (e com o modo de economia de
energia da CPU). O que vale comparar é a forma das curvas.
