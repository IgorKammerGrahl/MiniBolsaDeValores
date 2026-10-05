# Design: onde está cada conceito de concorrência

Este documento explica, de forma curta, cada decisão de concorrência da Mini
Bolsa e onde ela está no código. Para rodar as demonstrações, veja o
[README](README.md).

## Visão geral

```
 clientes (nc / client / robôs)
        │  TCP, uma linha por mensagem
        ▼
 Server ── thread "accept"
   │  cada conexão ganha 2 tarefas num executor de VIRTUAL THREADS:
   │    cliente-N-leitor    lê, interpreta e executa os comandos
   │    cliente-N-escritor  esvazia a fila de saída (1.000 mensagens) no socket
   │
   ├─ Exchange (fachada)                reserve → submit → CompletableFuture
   │    └─ MatchingEngine
   │         single-writer: livro-PETR4 … livro-MGLU3   1 thread por ativo, livro sem lock
   │         global-lock:   motor-1 … motor-N           pool + 1 ReentrantLock para todos os livros
   ├─ AccountRegistry                  1 ReentrantLock por conta, sempre em ordem de id
   ├─ transferencia-1 … 8              pool de threads de plataforma para o TRANSFER
   ├─ cotacoes   (agendador, 1 s)      TICK para quem assinou
   ├─ auditoria  (agendador, N s)      verifica as invariantes
   └─ watchdog   (agendador, 1 s)      procura deadlocks com o ThreadMXBean
```

Os nomes acima são os nomes reais das threads. Eles aparecem em cada linha de
log (`[hora] [thread] mensagem`), o que deixa ver quem fez o quê durante a
demonstração.

## O caminho de uma ordem

1. `cliente-3-leitor` lê `BUY PETR4 100 38.50`, e `Protocol.parse` devolve um
   `Command.PlaceOrder`.
2. `Exchange.reserve`, ainda na thread da sessão, cria a `Order` com um id
   global (`AtomicLong`). Depois `AccountRegistry.reserve` move 100 × 38,50 do
   dinheiro disponível para o reservado, com o lock da conta. Se faltar saldo, a
   resposta é `ERR SALDO_INSUFICIENTE`.
3. A sessão enfileira `OK ORDER <id>` **antes** de mandar a ordem ao motor. Assim
   nenhum `FILL` dessa ordem chega antes do OK.
4. `Exchange.submit` chama `engine.run(PETR4, livro -> livro.submit(ordem))`, que
   devolve um `CompletableFuture`.
5. Na thread `livro-PETR4`, `OrderBook.submit` casa a ordem com as melhores
   vendas. Para cada negócio:
   - `AccountRegistry.settle` trava comprador e vendedor (menor id primeiro),
     desconta as quantidades das ordens (`trade.fillOrders()`) e move dinheiro e
     ações;
   - `Server.onTrade` enfileira os `FILL` das duas pontas e atualiza a cotação.
6. O future completa. A sessão, que esperava por ele (`join`), lê o próximo
   comando: há uma ordem em voo por sessão.

## 1. Single-writer por ativo × lock global

O livro de ofertas (`OrderBook`: `TreeMap` de preço → `ArrayDeque` de ordens)
**não é thread-safe de propósito**. Cada estratégia garante, do seu jeito, que só
uma thread mexe nele por vez. Toda operação num livro (enviar, cancelar, `BOOK`,
"está cruzado?") passa por `MatchingEngine.run(ativo, tarefa)`.

- **single-writer** (`SingleWriterEngine`): cada ativo tem o seu
  `Executors.newSingleThreadExecutor()`, e essa thread é a única que toca no
  livro daquele ativo. É confinamento de dados a uma thread, por isso o livro
  dispensa lock. Duas garantias tornam isso correto:
  - o executor roda as tarefas uma de cada vez, na ordem em que entraram na fila;
    essa fila define a prioridade de tempo;
  - submeter uma tarefa a um executor estabelece *happens-before* com a execução
    dela, então a thread do livro sempre vê a ordem completa.

  Ativos diferentes ficam em threads diferentes e são processados em paralelo.
- **global-lock** (`GlobalLockEngine`): um pool fixo de threads de plataforma
  (uma por núcleo) processa ordens de qualquer ativo. Todas disputam um único
  `ReentrantLock`, e mesmo ordens de ativos diferentes esperam umas pelas outras.

As duas estratégias devolvem `CompletableFuture`, e é por isso que o benchmark
compara as duas com a mesma API e a mesma carga. Elas diferem só na forma de
serializar o acesso aos livros.

**Por que a prioridade de tempo vem da chegada, não do id:** o id nasce na
sessão (passo 2), mas duas sessões podem chegar ao motor na ordem inversa. O
livro numera as ordens quando elas chegam (`Order.arrivalSeq`), e é esse número
que decide quem vem antes no mesmo preço.

**Ordem dos locks entre camadas:** no global-lock, a liquidação acontece com o
lock global na mão e trava as contas dentro dele (global → contas). Nada trava
uma conta e depois pede o lock global, então não há ciclo.

**O que o benchmark mostrou** (detalhes no README): com muitos produtores o
single-writer vence nos dois cenários e tem cauda de latência bem menor. Com
todas as ordens num ativo só, ele para de escalar em cerca de 1 milhão de
ordens/s, que é o limite de uma thread: o paralelismo é limitado pelo número de
partições.

## 2. Reserva de saldo e a corrida check-then-act

`AccountRegistry.reserve` faz um *check-then-act*: verifica se há saldo e depois
debita. Com o lock da conta na mão, duas ordens simultâneas da mesma conta nunca
passam juntas pela verificação. A segunda só verifica depois que a primeira
debitou.

No **modo inseguro** (`--unsafe-accounts`), os métodos `lock`/`unlock` do
registro viram no-op. A pausa `--race-window-ms` fica entre a verificação e o
débito (`raceWindow()`), para alargar a janela da corrida. Duas falhas aparecem:

- **saldo negativo:** duas compras de R$ 60.000 numa conta de R$ 100.000 passam
  pela verificação antes de qualquer débito; o disponível vai a −20.000 (teste
  `UnsafeAccountsDemoTest`, demonstração 2 do README);
- **atualização perdida:** a reserva (thread da sessão) e a liquidação (thread do
  livro) fazem "ler, somar, gravar" no mesmo `long` ao mesmo tempo, e uma escrita
  se perde. Dinheiro some, ou ações aparecem do nada.

Duas defesas impedem que o modo inseguro corrompa as estruturas internas:
- o livro continua protegido pela estratégia do motor;
- as ordens abertas da conta ficam num `ConcurrentHashMap`.

Assim só os saldos correm, e o servidor nunca trava por causa do modo inseguro.

O custo da ordem usa `Math.multiplyExact`: uma quantidade absurda vinda do
cliente estouraria o `long`, daria a volta para um valor pequeno e passaria na
verificação.

## 3. Ordenação de locks e o deadlock da versão ingênua

Um deadlock precisa de quatro condições: exclusão mútua, segurar um recurso
enquanto espera outro, nenhuma preempção e **espera circular**. A ordenação de
locks quebra a última.

Toda operação que precisa de duas contas trava **sempre a de menor id
primeiro**:
- a liquidação (`lockInIdOrder` em `settle`);
- a transferência (`transfer`);
- a auditoria (`InvariantChecker.check`, que trava todas em ordem crescente).

Com uma ordem global única, nenhuma thread espera por uma conta "menor" do que
uma que já segura, e não há ciclo possível.

A **transferência ingênua** (`--naive-transfer`) muda uma linha só:

```java
boolean fromFirst = naiveTransfer || from.id <= to.id;
```

Ela trava a origem, espera `--transfer-pause-ms` e trava o destino. Com
`ana → bia` e `bia → ana` ao mesmo tempo, cada uma segura a sua origem e espera a
outra para sempre.

- **Reentrância:** num self-trade, comprador e vendedor são a mesma conta. O
  `ReentrantLock` deixa a mesma thread pegar o lock duas vezes.
- **Recuperação:** a transferência usa `lockInterruptibly`. Interromper a thread (o
  `shutdownNow` do pool, no Ctrl+C) a tira do deadlock, e o servidor encerra.
  Nada fica movido pela metade, porque o débito só acontece com os dois locks na mão.

## 4. Fila de saída por cliente e o cliente lento

Cada sessão tem uma `ArrayBlockingQueue<String>` de 1.000 mensagens
(`ClientSession`). É um produtor/consumidor:

- **produtores:** a própria sessão (respostas), as threads do motor (`FILL`) e a
  thread de cotações (`TICK`). Todos chamam `send`, que faz `offer`, ou seja,
  **nunca bloqueia**;
- **consumidor:** o escritor, a única thread que escreve no socket. Ele faz `take`,
  escreve e só dá `flush` quando a fila esvazia.

**Cliente lento:** se o `offer` falhar (fila cheia), o cliente é desconectado,
com um log explicando o motivo. O motor nunca espera por um cliente que não lê;
se esperasse, um único cliente parado travaria um livro inteiro.

Outros detalhes:
- **Pílula de veneno:** para parar o escritor, entra na fila uma `String`
  sentinela, comparada por identidade (`!=`). No `QUIT` ela entra depois do
  `OK BYE`. Numa desconexão forçada, o socket é fechado, o que destrava o `read`
  do leitor e o `write` do escritor, e a pílula acorda o escritor parado no `take`.
- **Respostas de várias linhas são uma mensagem só:** `BOOK`, `PORTFOLIO`,
  `ORDERS` e `HELP` entram na fila como uma `String` com `\n`. Um `FILL` vindo de
  outra thread não cai no meio delas.
- **Fechamento idempotente:** `close` usa um `AtomicBoolean` (`compareAndSet`),
  porque pode ser chamado ao mesmo tempo pelo leitor, pelo escritor, por uma
  thread do motor (fila cheia) e pelo servidor.

## 5. Virtual threads para I/O, threads de plataforma para processamento

- **Virtual threads:** leitor e escritor de cada sessão
  (`Executors.newVirtualThreadPerTaskExecutor()`) e os leitores dos robôs. Passam
  quase todo o tempo bloqueados na rede, e uma virtual thread parada custa pouco.
  O `join` no future da ordem também é barato numa virtual thread.
- **Threads de plataforma:** as threads dos livros, o pool do lock global, o
  pool de transferências, os agendadores e os produtores do benchmark. É onde
  está o processamento.

Há um motivo extra para o `TRANSFER` rodar no pool `transferencia-N` e não na
thread da sessão: o `ThreadMXBean.findDeadlockedThreads()` **não enxerga virtual
threads**. Isso foi testado: duas virtual threads em deadlock com `ReentrantLock`
devolvem `null`. Na virtual thread, o deadlock da transferência ingênua seria
invisível para o watchdog. A sessão manda a transferência ao pool e espera o
`Future`.

## 6. Tarefas periódicas

Cada tarefa periódica tem o seu `ScheduledExecutorService` de uma thread. Uma
auditoria presa (por exemplo, esperando o lock de uma conta num deadlock) não
para as cotações nem o watchdog.

- **`MarketDataPublisher`** (`cotacoes`, 1 s): as threads do motor atualizam o
  último preço e o volume. A cotação é um record imutável trocado inteiro num
  `AtomicReference`, então quem lê nunca vê o preço novo com o volume velho. O
  `TICK` só é enfileirado (`send`), e um assinante lento não atrasa os outros.
- **`Auditor`** (`auditoria`, `--audit-every N`): chama `Exchange.checkInvariants`
  e loga as violações **novas** com `!!! AUDITORIA`. As que continuam de uma
  auditoria para a outra viram uma linha curta.
- **`DeadlockWatchdog`** (`watchdog`, 1 s): `findDeadlockedThreads` e
  `getThreadInfo`. Para cada thread presa, loga o lock que ela espera, quem o
  segura e a linha do nosso código onde parou. Avisa cada deadlock uma vez.

Um cuidado comum às três: se uma exceção escapar de uma tarefa de
`scheduleAtFixedRate`, o agendador **para de executá-la em silêncio**. Por isso
todas capturam e logam.

Os robôs usam o mesmo recurso. Um único agendador com 4 threads dispara as ações
de todos os robôs. Uma tarefa periódica nunca roda duas vezes ao mesmo tempo, e
cada execução vê o que a anterior fez, então o `Writer` de cada robô dispensa lock.

## 7. Invariantes como prova de corretude

`InvariantChecker` e `Exchange.checkInvariants` verificam:

1. nenhum saldo ou quantidade de ações negativo;
2. conservação do dinheiro: a soma de disponível + reservado é o número de contas
   × R$ 100.000;
3. conservação das ações, ativo por ativo;
4. reserva coerente: o dinheiro reservado é a soma de restante × preço das compras
   abertas, e as ações reservadas são o restante das vendas abertas;
5. nenhum livro cruzado (melhor compra ≥ melhor venda).

Elas são verificadas:
- ao fim dos testes de estresse;
- ao fim de **cada** rodada do benchmark (resultado errado não vale);
- periodicamente no servidor, com a auditoria.

Para que uma auditoria com o servidor rodando nunca acuse à toa, duas decisões
foram tomadas:
- **a quantidade restante das ordens só muda dentro da liquidação**, com as duas
  contas travadas, junto com os saldos. Por isso o livro não desconta as
  quantidades: ele chama a liquidação, que chama `trade.fillOrders()`. Quem trava
  todas as contas nunca vê um negócio pela metade (invariante 4);
- **a invariante 5 é verificada dentro do motor**, porque só a thread dona pode
  ler o livro.

Um verificador que nunca reclama passaria em todos os testes. Por isso o
`InvariantCheckerTest` corrompe o estado de propósito e exige a reclamação.

## 8. O monitor gráfico e a thread do Swing

O Swing não é thread-safe. Todo componente deve ser criado e alterado só na
**EDT** (Event Dispatch Thread), a thread que desenha a janela e trata os cliques.
O `Monitor` segue a regra com dois papéis separados:

- a **thread principal** lê o socket e transforma cada linha `TICK` num
  `QuoteBoard.Tick`, um record imutável, que pode passar de uma thread para outra
  sem cuidado nenhum;
- a **EDT** recebe cada tick por `SwingUtilities.invokeLater`, atualiza a tabela
  (`QuoteBoard`, um `AbstractTableModel`) e redesenha o gráfico (`PriceChart`).

O estado do monitor (últimos preços e histórico) é **confinado à EDT**, a mesma
ideia do single-writer do livro: um único dono, então não há lock. A janela é
criada na EDT, e a thread principal recebe a referência por um
`CompletableFuture`. Se a criação falhar, o future completa com a exceção, em vez
de deixar a thread principal esperando para sempre.

## 9. Outros detalhes

- **Cancelamento antes da chegada:** com duas sessões da mesma conta, um
  `CANCEL` pode chegar ao motor antes da própria ordem. O cancelamento zera a
  ordem com o lock da conta e devolve a reserva. Quando a ordem chega,
  `remaining == 0`, e ela não casa nem entra no livro.
- **`OK ORDER` quer dizer "aceita e reservada"**, não "já está no livro". Uma
  ordem enviada logo depois por outro cliente pode chegar ao motor primeiro.
- **Encerramento ordenado** (`Server.close`):
  1. para de aceitar conexões e desconecta todo mundo;
  2. fecha o pool de transferências (antes das sessões, para soltar quem espera
     uma transferência presa);
  3. fecha as sessões, os agendadores e, por último, o motor.

  Todo executor passa por `ExecutorShutdown.shutdownAndAwait`: `shutdown`, espera,
  e só então `shutdownNow`.
- **Dinheiro em centavos (`long`)**, nunca `double`. A conversão para "38.50" só
  acontece no protocolo e nos logs (`Money`).

## Mapa: conceito da disciplina → classe

| Conceito | Onde |
|---|---|
| Framework Executor | `Server`, `SingleWriterEngine`, `GlobalLockEngine`, `Bots`, `Benchmark` |
| `newSingleThreadExecutor` (confinamento a uma thread) | `SingleWriterEngine` (um por ativo) |
| `newFixedThreadPool` | `GlobalLockEngine`, transferências em `Server`, produtores em `Benchmark` |
| `newVirtualThreadPerTaskExecutor` | sessões em `Server`, leitores em `Bots` |
| `ScheduledExecutorService` | `MarketDataPublisher`, `Auditor`, `DeadlockWatchdog`, `Bots` |
| `shutdown` + `awaitTermination` | `ExecutorShutdown` (usado por todos) |
| `CompletableFuture` | `MatchingEngine.run`, `Exchange.submit` / `cancel` / `book`, criação da janela em `Monitor` |
| `Future.get` | `Server.transfer` |
| `ReentrantLock` (exclusão mútua) | `Account` (um por conta), `GlobalLockEngine` (o global) |
| Reentrância | `AccountRegistry.settle` no self-trade |
| `lockInterruptibly` | `AccountRegistry.transfer` |
| Ordenação de locks | `AccountRegistry.lockInIdOrder` e `transfer`, `InvariantChecker.check` |
| Deadlock provocado | `AccountRegistry.transfer` com `naiveTransfer` |
| Detecção de deadlock (`ThreadMXBean`) | `DeadlockWatchdog` |
| Condição de corrida, check-then-act | `AccountRegistry.reserve` no modo inseguro |
| Produtor/consumidor, `BlockingQueue` limitada | `ClientSession` (fila de saída) |
| Pílula de veneno | `ClientSession.POISON` |
| `AtomicLong`, `AtomicBoolean` | ids das ordens (`Exchange`), contadores (`Server`), fechamento (`ClientSession`) |
| `AtomicReference` + objeto imutável | `MarketDataPublisher.Quote` |
| `AtomicLongArray`, `ConcurrentLinkedDeque`, `LongAdder` | `Bot`, `Bots` |
| `ConcurrentHashMap`, `ConcurrentSkipListMap` | `AccountRegistry`, `Server`, `MarketDataPublisher` |
| `volatile` | `ClientSession.account`, `Bot.stopped` |
| `CountDownLatch` | largada dos produtores em `Benchmark`, espera do modo `bots` |
| `CyclicBarrier` | `TransferStressTest`, `UnsafeAccountsDemoTest` |
| Shutdown hook (Ctrl+C) | `Server.run`, `Bots.run` |
| Confinamento na EDT, `SwingUtilities.invokeLater` | `Monitor`, `QuoteBoard`, `PriceChart` |
| Invariantes | `InvariantChecker`, `Exchange.checkInvariants`, `Auditor` |
| Medição de desempenho (vazão, p50/p99) | `Benchmark` |

## Testes: o que cada um prova

| Teste | Prova |
|---|---|
| `OrderBookTest` | prioridade preço-tempo, execução total e parcial, preço do livro, cancelamento, livro nunca cruzado (20 mil passos aleatórios) |
| `AccountRegistryTest` | reserva, recusa, estouro de `long`, liquidação com devolução da diferença, self-trade, transferência |
| `InvariantCheckerTest` | o verificador acusa estados corrompidos de propósito |
| `TransferStressTest` | 160 mil transferências cruzadas em 8 threads sem deadlock; a versão ingênua trava e a interrupção solta |
| `ExchangeStressTest` | 32 threads, 64 mil operações por estratégia, as cinco invariantes valem no fim |
| `UnsafeAccountsDemoTest` | sem lock, o mesmo dinheiro é gasto duas vezes; com lock, a mesma corrida é inofensiva |
| `ProtocolTest` | o parser e a formatação, sem rede |
| `ServerIntegrationTest` | conversa por socket: FILL para as duas pontas, cancelamento, todos os erros |
| `SlowClientTest` | cliente que não lê é desconectado e não atrapalha os outros |
| `MarketDataIntegrationTest` | TICK após assinar, refletindo os negócios |
| `AuditorTest` | a auditoria acusa a corrida do modo inseguro e nada no modo seguro |
| `DeadlockWatchdogTest` | o watchdog acusa o deadlock da transferência ingênua; a ordenada nunca trava |
| `BotsIntegrationTest` | robôs negociam sem violação; a tempestade só trava o servidor ingênuo |
| `BenchmarkTest` | percentil, mediana e o CSV completo |
| `QuoteBoardTest` | interpretação do TICK, atualização da tabela, histórico de 2 minutos e o gráfico desenhado numa imagem (sem abrir janela) |
