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

> Projeto em construção: `bots` e `bench` ainda estão vazios e serão implementados nos próximos PRs.

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
momento.
