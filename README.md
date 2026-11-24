# ICEIBank

Projeto da disciplina **Laboratório de Desenvolvimento de Aplicações Móveis e Distribuídas**.

Banco simplificado dividido em **3 agências**: cada agência é o mesmo serviço Java rodando 3 vezes,
dona de uma fatia das contas (`idConta % 3`). O projeto evolui por sprints e está no **Sprint 3**:

| Sprint | O que entrou | Conceito distribuído |
|---|---|---|
| 1 | API REST (Spring Boot), login JWT, frontend web MVC | relógio lógico de Lamport |
| 2 | transferência entre agências via RabbitMQ, dead-letter queue | relógio vetorial |
| 3 | app Flutter offline-first, eleição de líder com heartbeat | consenso (algoritmo de Bully) |

> **Vai apresentar?** O roteiro com cada requisito → tela → arquivo/linha do código está em
> **[`04-documentacao/GUIA-DE-APRESENTACAO.md`](04-documentacao/GUIA-DE-APRESENTACAO.md)**.
> Vídeo: [`05-evidencias/video/video.mp4`](05-evidencias/video/video.mp4).

---

## Estrutura das pastas

```
iceibank/
├── 01-backend-agencias/     Java 21 + Spring Boot: o serviço de UMA agência (roda 3 vezes)
│   ├── mvnw.cmd / mvnw      Maven Wrapper: compila sem precisar instalar o Maven
│   └── src/main/java/br/pucminas/icei/iceibank/agencia/
│       ├── controller/      rotas REST: auth, contas, transferências, bully
│       ├── service/         relógio vetorial, Bully, processamento de créditos, log de eventos
│       ├── mensageria/      RabbitMQ: filas, exchange, dead-letter, publicador e consumidor
│       ├── security/        JWT: geração, filtro e usuários
│       ├── model/           Conta e EstadoAgencia (estado em memória)
│       ├── config/          partição/portas das agências e CORS
│       ├── dto/             corpos das requisições e respostas
│       └── ferramentas/     MesclarLogs (linha do tempo) e HistoricoLideranca
├── 02-frontend-web/         HTML + CSS + JS puro (Model / View / Controller)
├── 03-app-mobile-flutter/   app Flutter: painel de líder + modo offline-first
├── 04-documentacao/         guia de apresentação, respostas do roteiro, explicação completa
├── 05-evidencias/           prints de cada teste (sprint1/, sprint2/, sprint3/) e vídeo
├── scripts/                 atalhos .bat para rodar tudo no Windows
└── docker-compose.yml       RabbitMQ local (opcional)
```

---

## Rodar no PC do laboratório (Windows)

### 1. Pré-requisitos

| Precisa | Para quê | Como conferir |
|---|---|---|
| **JDK 21** (obrigatório) | backend das agências | `java -version` mostra 21 ou maior |
| **Git** | baixar o projeto | `git --version` |
| RabbitMQ (opcional) | só para transferência **entre** agências | Docker Desktop **ou** uma URL do CloudAMQP |
| Flutter SDK (opcional) | só para rodar o app mobile | `flutter doctor` |

**Não precisa** instalar Maven (o projeto traz o Maven Wrapper) nem Python/Node (o frontend é servido
por um script PowerShell). Na primeira compilação o wrapper baixa o Maven e as dependências: o PC
precisa de internet.

Se o `java -version` não funcionar: baixe o JDK 21 (ex.: *Eclipse Temurin 21* em
<https://adoptium.net>), instale marcando **"Set JAVA_HOME"** e **"Add to PATH"**, e abra um
terminal novo.

### 2. Baixar o projeto

```powershell
git clone https://github.com/MirandaSls/iceibank.git
cd iceibank
```

(Ou *Code → Download ZIP* no GitHub e extraia numa pasta **sem acentos**.)

### 3. Executar um sprint (jeito rápido)

Cada sprint roda **separado**, com o código exatamente como foi entregue naquele sprint. Dê **dois
cliques** no `.bat` do sprint que quer mostrar:

| Atalho | O que sobe | Precisa de RabbitMQ? |
|---|---|---|
| `scripts\sprint-1.bat` | REST + **relógio de Lamport** + JWT + frontend. Transferência entre agências é uma **chamada HTTP** e a falha conhecida (débito sem crédito) aparece | não |
| `scripts\sprint-2.bat` | Transferência via **RabbitMQ** + **relógio vetorial** + dead-letter queue | sim, para transferir entre agências |
| `scripts\sprint-3.bat` | Tudo do Sprint 2 + **eleição de líder (Bully)** + heartbeat + sincronização offline do app | sim, para transferir entre agências |

(`scripts\iniciar-tudo.bat` é o mesmo que o Sprint 3.) Pelo PowerShell, na raiz do projeto:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1 -Sprint 1
```

Rode **um sprint de cada vez**: todos usam as mesmas portas. Para trocar de sprint, rode
`scripts\parar-tudo.bat` antes.

O script:

1. **Sprints 1 e 2:** na primeira vez, extrai o código daquele sprint (tags git `sprint-1` e
   `sprint-2`) para a pasta `.sprints\sprint-N`, que fica fora do Git. O Sprint 3 usa as pastas
   `01-backend-agencias` e `02-frontend-web`;
2. compila o backend do sprint na primeira vez (Maven Wrapper);
3. **Sprints 2 e 3:** sobe o RabbitMQ via Docker, se você não tiver definido `RABBITMQ_URL` e o
   Docker existir;
4. abre **3 janelas**, uma por agência (portas **4145**, **4146**, **4147**), com o sprint no título;
5. abre 1 janela servindo o frontend **daquele sprint** em **<http://localhost:5500>** e abre o
   navegador;
6. **Sprint 3:** mostra quem é a líder em cada agência (deve ser a **Agência 2**).

Login: **`ana` / `senha123`** (também `bruno` e `carla`), em todos os sprints.

Para os Sprints 1 e 2 o projeto precisa ter sido baixado com `git clone` (o ZIP do GitHub não traz o
histórico). Sem Git, baixe no GitHub o ZIP da tag `sprint-1` ou `sprint-2` e extraia o conteúdo em
`.sprints\sprint-1` (ou `-2`), de forma que exista `.sprints\sprint-1\agencia`.

#### O que mostrar em cada sprint

| Sprint | Demonstração | Como |
|---|---|---|
| 1 | Transferência entre agências por HTTP | transfira da conta 0 (Agência 0) para a conta 1 (Agência 1) |
| 1 | Falha conhecida | feche a janela da Agência 2 e transfira para a conta 2: 502, o saldo de origem cai e o destino não recebe |
| 1 | Linha do tempo por Lamport | `scripts\linha-do-tempo.bat 1` |
| 2 | Transferência assíncrona | transfira de 0 para 1 e veja `TRANSFERENCIA_ENVIADA` na Agência 0 e o crédito na Agência 1 |
| 2 | Resiliência da fila | feche a janela da Agência 1 e transfira para a conta 1: a origem responde 200 e o painel do RabbitMQ mostra a mensagem **retida** em `fila-agencia-1` (0 consumidores) |
| 2 | Dead-letter queue | suba a Agência 1 de novo (`$env:AGENCIA_ID=1; java -jar target\iceibank-agencia-2.0.0.jar` em `.sprints\sprint-2\agencia`): como as contas ficam em memória, a conta 1 não existe mais e o crédito vai para `fila-agencia-1.dlq` |
| 2 | Linha do tempo com eventos concorrentes | `scripts\linha-do-tempo.bat 2` |
| 3 | Líder e failover | feche a janela da Agência 2; em ~4 s a Agência 1 vira líder |
| 3 | Histórico de lideranças | `scripts\historico-lideranca.bat` |

#### Usando CloudAMQP em vez de Docker

Sem Docker, use a "AMQP URL" de uma instância gratuita do CloudAMQP. No **mesmo** PowerShell, antes do
script:

```powershell
$env:RABBITMQ_URL = "amqps://usuario:senha@host.cloudamqp.com/vhost"
powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1
```

Sem RabbitMQ nenhum, o sistema sobe e quase tudo funciona (login, contas, depósito, saque,
transferência na mesma agência, eleição de líder); só a transferência **entre** agências responde 502.

### 4. Subir tudo (jeito manual, um terminal por processo)

```powershell
# compilar uma vez
cd 01-backend-agencias
.\mvnw.cmd clean package -DskipTests

# Terminais 1, 2 e 3 (a partir de 01-backend-agencias)
$env:AGENCIA_ID=0; java -jar target\iceibank-agencia-2.0.0.jar
$env:AGENCIA_ID=1; java -jar target\iceibank-agencia-2.0.0.jar
$env:AGENCIA_ID=2; java -jar target\iceibank-agencia-2.0.0.jar

# Terminal 4 (a partir da raiz) - frontend
powershell -ExecutionPolicy Bypass -File scripts\servidor-frontend.ps1
# ou, se tiver Python:  cd 02-frontend-web; python -m http.server 5500
```

Abra <http://localhost:5500>.

### 5. Comandos úteis durante a apresentação

| O que mostrar | Comando |
|---|---|
| Líder em cada agência | `4145..4147 \| % { Invoke-RestMethod "http://localhost:$_/bully/lider" }` |
| Failover | feche a janela da **Agência 2**; em ~4 s a Agência 1 vira líder |
| Linha do tempo do sprint | `scripts\linha-do-tempo.bat 1` (ou `2`, ou `3`) |
| Histórico de lideranças | `scripts\historico-lideranca.bat` |
| Testes automatizados (89 JUnit) | `scripts\rodar-testes.bat` |
| Painel do RabbitMQ (Docker) | <http://localhost:15672> (guest / guest) |

### 6. App Flutter (opcional)

Com as 3 agências no ar:

```powershell
cd 03-app-mobile-flutter
flutter create --platforms=android --project-name iceibank_mobile .   # só na primeira vez
flutter pub get
flutter run
```

No Android é preciso liberar HTTP sem TLS e, em celular físico, apontar para o IP do PC. Passo a passo
completo em [`03-app-mobile-flutter/README.md`](03-app-mobile-flutter/README.md).

### Problemas comuns

| Sintoma | Solução |
|---|---|
| `java` não é reconhecido | instale o JDK 21 marcando "Add to PATH" e abra um terminal novo |
| "a execução de scripts foi desabilitada" | use os `.bat` ou `powershell -ExecutionPolicy Bypass -File ...` |
| `Unable to rename ... .jar` ao recompilar | as agências estão rodando: `scripts\parar-tudo.bat` antes |
| Porta 4145 / 5500 em uso | outro sprint (ou outra pessoa) já está rodando: `scripts\parar-tudo.bat` |
| "Nao consegui extrair a tag sprint-N" | rode `git fetch --tags` na raiz do projeto e tente de novo |
| Transferência entre agências dá 502 | RabbitMQ fora: suba o Docker ou defina `RABBITMQ_URL` |
| Navegador mostra "Failed to fetch" | agência daquela porta não está no ar (veja a janela dela) |
| Contas sumiram | o estado é em memória: reiniciar a agência zera as contas (é proposital) |

---

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21 + Spring Boot 3.4 (Maven Wrapper) |
| Mensageria | RabbitMQ (Spring AMQP), exchange topic `iceibank.eventos` |
| Autenticação | JWT (jjwt 0.12.6) |
| Frontend | HTML + CSS + JavaScript puro (sem framework) |
| App mobile | Flutter (Dart) + Hive + connectivity_plus + http |

## Portas

O OFFSET pessoal é **45**, a porta base é `4100 + 45 = 4145` (a sugestão `4000 + OFFSET` daria 4045,
que Chrome e Firefox bloqueiam com `ERR_UNSAFE_PORT`; ver `04-documentacao/RESPOSTAS.md` §0.1).

| Processo | Porta |
|---|---|
| Agência 0 (contas 0, 3, 6...) | 4145 |
| Agência 1 (contas 1, 4, 7...) | 4146 |
| Agência 2 (contas 2, 5, 8...) | 4147 |
| Frontend web | 5500 |
| RabbitMQ (AMQP / painel) | 5672 / 15672 |

## Endpoints

| Método | Rota | Auth | Descrição |
|---|---|---|---|
| POST | `/auth/login` | pública | `{usuario, senha}` → `{token}` |
| POST | `/contas` | JWT | cria conta `{id, nomeAluno, saldoInicial}` (só se `id % 3` for desta agência) |
| GET | `/contas/{id}` | JWT | conta + `vetorAtual` |
| POST | `/contas/{id}/depositar` | JWT | `{valor}` |
| POST | `/contas/{id}/sacar` | JWT | `{valor}` |
| POST | `/contas/{id}/sincronizar` | JWT | `{tipo: DEPOSITO\|SAQUE, valor, vetorConhecido}` → 200 ou **409** (conflito) |
| POST | `/transferencias` | JWT | `{idOrigem, idDestino, valor}`, cabeçalho opcional `Idempotency-Key` |
| POST | `/bully/eleicao` | pública | mensagem de eleição entre agências |
| POST | `/bully/coordenador` | pública | anúncio da nova líder |
| GET | `/bully/lider` | pública | `{idAgencia, liderAtual}` |

## Variáveis de ambiente

| Variável | Padrão | Efeito |
|---|---|---|
| `AGENCIA_ID` | `0` | qual agência este processo é (0, 1 ou 2); define a porta |
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672` | broker de mensagens |
| `ICEIBANK_HEARTBEAT_MS` | `3000` | intervalo entre verificações da líder |
| `ICEIBANK_BULLY_TIMEOUT_MS` | `1000` | timeout de eleição/coordenador/heartbeat |
| `ICEIBANK_JWT_SECRET` | chave de desenvolvimento | chave que assina os JWT (a mesma nas 3) |

## Documentação

| Documento | Conteúdo |
|---|---|
| [`04-documentacao/GUIA-DE-APRESENTACAO.md`](04-documentacao/GUIA-DE-APRESENTACAO.md) | roteiro da apresentação: requisito → tela → código |
| [`04-documentacao/RESPOSTAS.md`](04-documentacao/RESPOSTAS.md) | respostas das questões dos roteiros dos Sprints 1, 2 e 3 |
| [`04-documentacao/ENTENDENDO-O-PROJETO.md`](04-documentacao/ENTENDENDO-O-PROJETO.md) | explicação completa: decisões, problemas e soluções |
| [`04-documentacao/RECAPITULACAO-SPRINT2.md`](04-documentacao/RECAPITULACAO-SPRINT2.md) | recapitulação de mensageria e relógio vetorial |
| `05-evidencias/sprint*/COMO-GERAR.md` | passo a passo para reproduzir cada print |
| [`03-app-mobile-flutter/README.md`](03-app-mobile-flutter/README.md) | instalação do Flutter e execução do app |
