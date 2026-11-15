# ICEIBank

Projeto da disciplina **Laboratorio de Desenvolvimento de Aplicacoes Moveis e Distribuidas**.

Banco simplificado dividido em agencias, onde cada agencia e uma particao independente
de contas. O projeto evolui ao longo de 4 sprints; este repositorio esta no **Sprint 3**
(U4 - Desenvolvimento Movel: app Flutter offline-first e eleicao de lider com o algoritmo de Bully).
Os Sprints 1 (REST/MVC, relogio de Lamport, JWT, frontend) e 2 (mensageria RabbitMQ, relogio
vetorial) continuam funcionando e estao documentados em `RESPOSTAS.md`.

## Video de apresentacao

**Arquivo:** [`evidencias/video/video.mp4`](evidencias/video/video.mp4)
(clique em *View raw* / *Download* para assistir)

Conteudo: funcionalidades do sistema em execucao (particionamento, relogio de Lamport,
transferencias local e entre agencias, falha conhecida, autenticacao JWT, frontend e
idempotencia) e as principais decisoes de projeto.

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21 + Spring Boot 3.4 (Maven) |
| Mensageria | RabbitMQ (Spring AMQP), exchange topic `iceibank.eventos` |
| Autenticacao | JWT (jjwt 0.12.6) |
| Frontend | HTML + CSS + JavaScript puro (sem framework) |
| App mobile | Flutter (Dart) + Hive + connectivity_plus + http |

## Estrutura

```
iceibank/
|-- agencia/          servico REST de uma agencia (mesmo codigo, 3 execucoes)
|-- frontend/         interface web que consome a API
|-- app_mobile/       app Flutter (Sprint 3) - ver app_mobile/README.md
|-- evidencias/       prints de teste (sprint1/, sprint2/, sprint3/)
|-- RESPOSTAS.md      respostas das questoes do roteiro
```

## Sprint 2 - Mensageria e relogio vetorial

- A transferencia entre agencias deixou de ser uma chamada REST: a agencia de origem **publica**
  um evento em `iceibank.eventos` (routing key `agencia.<id>.creditar`) e a de destino o consome
  da sua fila duravel `fila-agencia-<id>`, mesmo que estivesse fora do ar na hora.
- O relogio de Lamport foi substituido por um **relogio vetorial** (um contador por agencia).
- `MesclarLogs` agora lista os pares de eventos **comprovadamente concorrentes**.
- Funcionalidade adicional: **dead-letter queue** (`fila-agencia-<id>.dlq`) para creditos que a
  agencia nao consegue aplicar (ex.: conta inexistente).

### RabbitMQ

Defina `RABBITMQ_URL` antes de subir cada agencia. Sem a variavel, usa `amqp://guest:guest@localhost:5672`.

```powershell
# CloudAMQP (AMQP URL do painel da instancia)
$env:RABBITMQ_URL="amqps://usuario:senha@host.cloudamqp.com/vhost"
# ou RabbitMQ local
$env:RABBITMQ_URL="amqp://guest:guest@localhost:5672"
```

RabbitMQ local via Docker (usa o `docker-compose.yml` da raiz):

```powershell
docker compose up -d
```

Painel de administracao local: <http://localhost:15672> (guest/guest).

## Como executar

O OFFSET pessoal deste projeto e **45**, logo a porta base e `4145`.

| Agencia | Porta |
|---|---|
| 0 | 4145 |
| 1 | 4146 |
| 2 | 4147 |

> O roteiro sugere `4000 + OFFSET`. Aqui a base e `4100 + OFFSET` porque a porta 4045 esta na
> lista de portas bloqueadas por Chrome e Firefox (servico `lockd`): o navegador recusa a
> chamada com `ERR_UNSAFE_PORT` e o frontend nao conseguiria falar com a Agencia 0.

Compilar uma vez:

```powershell
cd agencia
mvn -q clean package -DskipTests
```

Subir as 3 agencias, cada uma em um terminal do PowerShell (a partir da raiz do repositorio):

```powershell
# Terminal 1
cd agencia; $env:AGENCIA_ID=0; mvn spring-boot:run

# Terminal 2
cd agencia; $env:AGENCIA_ID=1; mvn spring-boot:run

# Terminal 3
cd agencia; $env:AGENCIA_ID=2; mvn spring-boot:run
```

Alternativa mais silenciosa (usa o jar ja empacotado, sem o ruido do Maven na saida - foi assim
que as evidencias em `evidencias/sprint1/` foram geradas):

```powershell
cd agencia; $env:AGENCIA_ID=0; java -jar target\iceibank-agencia-2.0.0.jar
```

Frontend (quarto terminal):

```powershell
cd frontend
python -m http.server 5500
```

Depois abra <http://localhost:5500>.

Linha do tempo unificada (Parte E), com as agencias ja tendo gerado eventos:

```powershell
cd agencia
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"
```

## Sprint 3 - Eleicao de lider (Bully) e app Flutter offline-first

O que entrou neste sprint:

- **Algoritmo de Bully** nas 3 agencias (`BullyService` + `BullyController`). Ao subir, cada
  agencia inicia uma eleicao; vence sempre a de **maior ID** entre as ativas. A lider e, por
  enquanto, apenas informativa (no Sprint 4 ela vai coordenar o 2PC).
- **Heartbeat**: a cada `ICEIBANK_HEARTBEAT_MS` (padrao 3000 ms) cada agencia consulta a lider que
  conhece; se ela nao responder em `ICEIBANK_BULLY_TIMEOUT_MS` (padrao 1000 ms), registra
  `LIDER_NAO_RESPONDEU` e inicia nova eleicao (failover automatico).
- Eventos de eleicao gravados no mesmo log `.jsonl` com vetor: `ELEICAO_INICIADA`,
  `ELEICAO_RECEBIDA`, `TORNOU_LIDER`, `LIDER_ANUNCIADO`, `LIDER_NAO_RESPONDEU`.
- **`HistoricoLideranca`**: CLI Java (ao lado de `MesclarLogs`) que le os logs das 3 agencias e
  reconstroi a linha do tempo de lideres.
- **Sincronizacao offline-first**: `GET /contas/{id}`, `depositar` e `sacar` passam a devolver
  `vetorAtual`; o novo `POST /contas/{id}/sincronizar` compara o `vetorConhecido` enviado pelo app
  com o vetor atual da agencia e recusa com **409** quando algo mudou nesse intervalo.
- **App Flutter** (`app_mobile/`): login JWT, saldo, deposito, saque, transferencia, criacao de
  conta, painel da lider atual (polling das 3 agencias a cada 3 s), fila local de operacoes
  pendentes (Hive), saldo provisorio otimista, sincronizacao automatica ao reconectar e cartao de
  conflito (409) com **Reaplicar** / **Descartar**.
- Funcionalidade adicional: **indicador de agencias ativas** no painel do app (quais das 3 estao
  respondendo) e historico das trocas de lideranca observadas pelo app.

### Variaveis de ambiente novas (opcionais)

| Variavel | Padrao | Efeito |
|---|---|---|
| `ICEIBANK_HEARTBEAT_MS` | `3000` | intervalo entre verificacoes da lider |
| `ICEIBANK_BULLY_TIMEOUT_MS` | `1000` | timeout das chamadas de eleicao/coordenador/heartbeat |

### Como executar (Sprint 3)

Igual aos sprints anteriores: `RABBITMQ_URL` definido (ou RabbitMQ local) e uma agencia por
terminal. Suba as 3 em sequencia rapida para observar a "tempestade" de eleicoes da subida.

```powershell
cd agencia; mvn -q clean package -DskipTests

# Terminal 1, 2 e 3 (troque o AGENCIA_ID)
cd agencia; $env:AGENCIA_ID=0; java -jar target\iceibank-agencia-2.0.0.jar
cd agencia; $env:AGENCIA_ID=1; java -jar target\iceibank-agencia-2.0.0.jar
cd agencia; $env:AGENCIA_ID=2; java -jar target\iceibank-agencia-2.0.0.jar
```

Conferir a lider nas 3 agencias:

```powershell
4145..4147 | ForEach-Object { Invoke-RestMethod -Uri "http://localhost:$_/bully/lider" }
```

Historico de liderancas (depois de gerar eleicoes/failover):

```powershell
cd agencia
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.HistoricoLideranca"
```

App Flutter: instalacao do SDK, geracao das pastas de plataforma, ajuste de cleartext no Android
e execucao estao em [`app_mobile/README.md`](app_mobile/README.md).

### Endpoints novos ou alterados

| Metodo | Rota | Auth | Corpo | Resposta |
|---|---|---|---|---|
| POST | `/bully/eleicao` | publica | `{origemId, vetorEnvio}` | 200 (OK implicito; a agencia inicia a propria eleicao) |
| POST | `/bully/coordenador` | publica | `{liderId, vetorEnvio}` | 200 |
| GET | `/bully/lider` | publica | - | `{idAgencia, liderAtual}` (`liderAtual` pode ser `null` durante eleicao) |
| GET | `/contas/{id}` | JWT | - | conta + `vetorAtual` |
| POST | `/contas/{id}/depositar` | JWT | `{valor}` | conta + `vetorAtual` |
| POST | `/contas/{id}/sacar` | JWT | `{valor}` | conta + `vetorAtual` |
| POST | `/contas/{id}/sincronizar` | JWT | `{tipo: DEPOSITO\|SAQUE, valor, vetorConhecido}` | 200 aplicado, ou **409** `{erro, vetorConhecido, vetorAtual, relacao, contaAtual}` |

As rotas `/bully/*` sao publicas de proposito (sem JWT): sao trafego entre agencias e o polling
do app nao deve depender de login. A discussao de seguranca esta em `RESPOSTAS.md`.

### Evidencias

Roteiro passo a passo de cada print (comandos PowerShell exatos, `Get-Date` visivel, reproducao do
409 e do conflito no app, checklist de regressao dos Sprints 1-2):
[`evidencias/sprint3/COMO-GERAR.md`](evidencias/sprint3/COMO-GERAR.md).
