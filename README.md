# ICEIBank

Projeto da disciplina **Laboratorio de Desenvolvimento de Aplicacoes Moveis e Distribuidas**.

Banco simplificado dividido em agencias, onde cada agencia e uma particao independente
de contas. O projeto evolui ao longo de 4 sprints; este repositorio esta no **Sprint 2**
(U3 - Comunicacao Indireta: mensageria Publish/Subscribe com RabbitMQ e relogio vetorial).
O Sprint 1 (REST/MVC, relogio de Lamport, JWT, frontend) continua funcionando e esta documentado
em `RESPOSTAS.md` (partes 1 a 7).

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

## Estrutura

```
iceibank/
|-- agencia/          servico REST de uma agencia (mesmo codigo, 3 execucoes)
|-- frontend/         interface web que consome a API
|-- evidencias/       prints de teste (sprint1/, sprint2/)
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
