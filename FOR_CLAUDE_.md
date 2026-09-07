# FOR_CLAUDE_ — o ICEIBank explicado do zero

Documento de contexto do projeto: o que é, como está montado, por que cada decisão foi tomada e
quais pedras apareceram no caminho. Serve para quem (pessoa ou agente) pegar este repositório
daqui a dois meses e precisar entender tudo sem arqueologia.

---

## 1. O que é este projeto

Um banco de mentira, dividido em três agências, feito para tornar **visíveis** os problemas de
sistemas distribuídos.

A ideia central: cada agência é um processo independente, dono de uma fatia das contas. Nenhuma
agência conhece o saldo das contas das outras. Quando o dinheiro precisa atravessar a fronteira,
uma agência conversa com a outra pela rede — e é aí que tudo que pode dar errado, dá.

Por que banco? Porque erro de dinheiro é impossível de esconder. Se um sistema de recomendação
erra a ordem de dois eventos, ninguém percebe. Se um banco erra, o saldo não bate e a conta some.
Lamport usou exatamente esse exemplo no artigo de 1978 que criou o relógio lógico.

O projeto tem 4 sprints. Este repositório está no **Sprint 1**:

| Sprint | Tecnologia | Conceito distribuído |
|---|---|---|
| **1 (aqui)** | REST/MVC + JWT + frontend web | Relógio lógico de Lamport |
| 2 | Mensageria / Pub-Sub | Relógio vetorial |
| 3 | Flutter | Consenso (eleição de líder) |
| 4 | Containers | Transações distribuídas (2PC/Saga) |

Cada sprint parte do código do anterior. **Não recomeça do zero** — o que estiver mal estruturado
aqui vai doer nos próximos três.

---

## 2. Arquitetura em uma tela

```
                    navegador (frontend/, porta 5500)
                                  |
                          fetch + JWT no header
                                  |
        +-------------------------+-------------------------+
        |                         |                         |
   Agência 0                 Agência 1                 Agência 2
   porta 4145                porta 4146                porta 4147
   contas 0,3,6,9...         contas 1,4,7,10...        contas 2,5,8,11...
        |                         ^
        |  POST /contas/1/creditar-remoto (token de SERVIÇO)
        +-------------------------+

   Cada agência escreve seu proprio log:
   agencia/data/eventos-agencia-0.jsonl, -1.jsonl, -2.jsonl
                                  |
                          MesclarLogs (Parte E)
                                  |
                 linha do tempo unica, ordenada por Lamport
```

**O mesmo binário roda três vezes.** A identidade vem da variável de ambiente `AGENCIA_ID`, que
também determina a porta. Isso não é economia de código — é a essência do exercício: as três
agências são réplicas do mesmo programa com estados diferentes, como nós reais de um sistema
distribuído.

### Partição, não replicação

```java
int agenciaResponsavel(int idConta) { return idConta % NUMERO_AGENCIAS; }  // NUMERO_AGENCIAS = 3
```

Uma linha, e ela decide tudo. Conta 0 → Agência 0. Conta 1 → Agência 1. Conta 3 → volta pra
Agência 0. Se você pedir à Agência 0 para criar a conta 1, ela **recusa com 400** — não é dela.

Isso é *sharding* por hash, o mesmo princípio do Redis Cluster ou do particionamento de tópicos do
Kafka. A vantagem é que não existe estado duplicado para sincronizar. A desvantagem aparece na
hora de mover dinheiro entre partições, que é justamente o assunto do sprint.

---

## 3. Estrutura do código

```
agencia/                                       (backend Java, Spring Boot 3.4)
├── pom.xml
└── src/main/java/br/pucminas/icei/iceibank/agencia/
    ├── AgenciaApplication.java                lê AGENCIA_ID, escolhe a porta, sobe o Spring
    ├── MesclarLogs.java                       Parte E: linha do tempo unificada
    ├── config/
    │   ├── ConfigAgencias.java                partição, portas, URLs das 3 agências
    │   ├── ClienteRestConfig.java             RestTemplate com timeout (o "axios" do roteiro)
    │   └── CorsConfig.java                    libera o frontend; roda ANTES do filtro de JWT
    ├── controller/
    │   ├── AuthController.java                POST /auth/login  (única rota pública)
    │   ├── ContasController.java              criar / consultar / depositar / sacar
    │   └── TransferenciasController.java      transferir + creditar-remoto + idempotência
    ├── model/
    │   ├── Conta.java                         id, titular, saldo (BigDecimal)
    │   └── EstadoAgencia.java                 contas + relógio + log  (= app.locals do roteiro)
    ├── security/
    │   ├── JwtService.java                    gera e valida token de USUARIO e de SERVICO
    │   ├── JwtFilter.java                     exige token; 401 quando falta/expira/não bate o tipo
    │   └── UsuariosEmMemoria.java             ana / bruno / carla
    ├── service/
    │   ├── RelogioLamport.java                as 3 regras, synchronized
    │   └── RegistroEventos.java               escreve e lê .jsonl
    └── dto/                                   records de entrada e saída

frontend/                                      (HTML/CSS/JS puro, sem build)
├── index.html
├── css/estilo.css
└── js/
    ├── model.js        estado + fetch (não toca no DOM)
    ├── view.js         DOM (não faz fetch)
    ├── controller.js   liga os dois
    └── app.js          bootstrap
```

### O que importa entender de verdade

**`EstadoAgencia` é o coração.** No exemplo em Node do roteiro, contas/relógio/log ficam em
`app.locals`. Em Spring, viraram um `@Component` singleton injetado nos controllers. Todo o estado
mutável do processo está ali, em um lugar só. Isso torna óbvio o que se perde quando a agência
reinicia — e sim, **tudo se perde**: não há banco de dados neste sprint, de propósito. O foco é
REST/MVC e relógio lógico, não persistência.

**Os controllers carregam a lógica.** Poderia haver uma camada de serviço entre controller e
modelo. Não há, e é intencional: o roteiro apresenta a lógica dentro dos controllers, e manter o
mapeamento 1-para-1 com o material da disciplina vale mais aqui do que uma camada extra que não
resolve problema nenhum neste tamanho de projeto. Quando o Sprint 2 trouxer mensageria, aí sim a
camada de serviço vai se justificar.

---

## 4. O relógio de Lamport, que é o ponto do sprint

### As três regras

```java
public synchronized int eventoLocal()  { contador += 1; return contador; }
public synchronized int aoEnviar()     { contador += 1; return contador; }
public synchronized int aoReceber(int t) { contador = Math.max(contador, t) + 1; return contador; }
```

Cabe em cinco linhas e resolve um problema que parece impossível: **ordenar eventos entre máquinas
sem um relógio comum**.

A analogia que ajuda: imagine três pessoas escrevendo diários em cidades diferentes, sem
combinarem a hora. Cada uma numera as próprias páginas. Quando A manda uma carta pra B, escreve na
carta o número da página em que estava. B, ao ler, pula seu numerador para depois daquele número.
Assim, se a página 7 de A causou a página 12 de B, ninguém consegue ler os diários e concluir o
contrário. Mas se A está na página 3 e B na página 3 e nunca trocaram carta, os dois "3" não
significam nada em comum.

### Por que `synchronized` importa aqui

Spring Boot atende requisições em várias threads simultâneas. O contador é estado compartilhado.
Sem sincronização, duas requisições concorrentes podem ler o mesmo valor, incrementar e escrever —
e um evento fica sem carimbo próprio. Race condition clássica, a mesma do laboratório de threads e
semáforos.

Existe um teste que prova isso: `RelogioLamportTest.contadorEhSeguroSobConcorrencia` dispara 8
threads × 500 eventos e exige que o contador final seja exatamente 4000. Remova o `synchronized` e
ele falha.

### O que se observa rodando

Trecho real de uma execução (`MesclarLogs`):

```
[Lamport 10] agencia-0 - TRANSFERENCIA_DEBITO
[Lamport 11] agencia-0 - TRANSFERENCIA_ENVIADA
[Lamport 12] agencia-1 - TRANSFERENCIA_CREDITO_REMOTO     <- estava em 1, saltou pra 12
```

E o caso que ensina mais:

```
Lamport 12:
  agencia-0 - TRANSFERENCIA_DEBITO         (hora de parede 16:10:51)
  agencia-1 - TRANSFERENCIA_CREDITO_REMOTO (hora de parede 16:10:03)
```

Mesmo timestamp lógico, 48 segundos de diferença no relógio físico, e em **ordem invertida**. Não
é bug: é a definição. Eventos sem relação causal não têm ordem, e o relógio lógico nunca prometeu
concordar com o relógio de parede — ele existe justamente porque relógios de parede de máquinas
diferentes não são confiáveis.

Por isso cada evento no log guarda os dois carimbos: `timestampLamport` (usado para ordenar) e
`horaParede` (só para você poder comparar e perceber que eles discordam).

---

## 5. A falha conhecida — o bug que é para existir

```java
int tsDebito = relogio.eventoLocal();
contaOrigem.debitar(valor);            // <- dinheiro sai daqui

try {
    restTemplate.postForObject(urlDestino + "/contas/" + idDestino + "/creditar-remoto", ...);
} catch (RestClientException erro) {
    // o débito acima NÃO é revertido. De propósito.
    registro.registrar("TRANSFERENCIA_FALHOU", ..., erro.getMessage());
    return ResponseEntity.status(502).body(...);
}
```

Derrube a Agência 2 e transfira para a conta 2: o saldo de origem cai, o destino não recebe, e o
total de dinheiro no banco **diminui**. Rodei isso: R$ 35,00 → R$ 25,00, e os R$ 10,00 evaporaram.

Isso não é descuido — é o problema que o Sprint 4 vai resolver com 2PC ou Saga. O Sprint 1 apenas
o registra no log, para que ele seja observável em vez de silencioso.

**Detalhe de frontend que valeu a pena:** na primeira versão, o 502 mostrava a mensagem de erro
mas deixava o saldo antigo na tela. Ficava pior do que inútil — escondia justamente o ponto da
demonstração. Agora, ao receber 502, o Controller reconsulta a conta e redesenha o cartão, para a
pessoa **ver o dinheiro sumir**. Lição geral: quando o objetivo é ensinar uma falha, a interface
tem que mostrar a falha, não amenizá-la.

---

## 6. Autenticação: dois tipos de token

| Tipo | Emitido por | Vale onde | Validade |
|---|---|---|---|
| `USUARIO` | login de uma pessoa | rotas de conta e transferência | 15 min |
| `SERVICO` | a própria agência de origem | só `creditar-remoto` | 30 s |

O `JwtFilter` recusa cada tipo fora do seu lugar. A justificativa completa está em `RESPOSTAS.md`,
mas o resumo é: quem chama `creditar-remoto` não é uma pessoa, é uma máquina. Repassar o token da
pessoa personificaria alguém em uma operação que ela não fez e daria 15 minutos de poder a uma
agência eventualmente comprometida.

As três agências compartilham a mesma chave secreta. É isso que permite fazer login na Agência 0 e
usar o mesmo token na Agência 1 **sem nenhuma comunicação entre elas** — a propriedade que torna o
JWT interessante em sistema particionado. O preço: não dá para revogar um token antes de ele
expirar.

---

## 7. Funcionalidade adicional: idempotência

Cabeçalho opcional `Idempotency-Key` em `POST /transferencias`. Mesma chave + mesmo corpo →
devolve o resultado guardado, com `Idempotency-Replayed: true`, sem debitar de novo. Mesma chave +
corpo diferente → 409.

É o padrão que Stripe e todo gateway de pagamento sério usam, e pelo motivo mais prosaico do mundo:
quando a resposta se perde na rede, quem chamou não sabe se a operação foi aplicada. Reenviar é a
reação natural, e sem idempotência reenviar significa cobrar duas vezes.

Também é a ponte para o Sprint 4: uma Saga que faz retry depende de cada etapa ser idempotente.

---

## 8. Pedras que apareceram no caminho (e como foram resolvidas)

### 8.1 A porta 4045 é bloqueada pelo navegador

**Sintoma:** frontend não conseguia falar com a Agência 0. Console: `net::ERR_UNSAFE_PORT`. E o
curl funcionava perfeitamente na mesma porta.

**Causa:** Chrome e Firefox mantêm uma lista de portas banidas por segurança, e 4045 (`lockd`,
do NFS) está nela. A requisição morre no navegador, antes de virar pacote.

**Solução:** porta base virou `4100 + OFFSET` → 4145/4146/4147. Documentado em `RESPOSTAS.md` §0.1.

**Lição:** "funciona no curl" não é o mesmo que "funciona no navegador". O navegador é um ambiente
de execução com regras próprias — portas banidas, CORS, mixed content, cookies SameSite. Sempre
teste no cliente real.

### 8.2 O 401 chegava como erro genérico de CORS

**Sintoma:** token expirado, e o frontend mostrava "Failed to fetch" em vez de "Token expirado".

**Causa:** o filtro de JWT respondia 401 **antes** de o filtro de CORS acrescentar os cabeçalhos.
Sem `Access-Control-Allow-Origin`, o navegador bloqueia a resposta e o JavaScript nunca vê nem o
status nem o corpo.

**Solução:** registrar o `CorsFilter` com `Ordered.HIGHEST_PRECEDENCE`, garantindo que ele rode
antes do filtro de autenticação.

**Lição:** ordem de filtros é uma decisão de arquitetura, não um detalhe. Tudo que precisa aparecer
no cliente — inclusive erro — tem que passar pelas camadas de infraestrutura na ordem certa.

### 8.3 `[hidden]` não escondia nada

**Sintoma:** os três formulários de operação (depositar/sacar/transferir) apareciam ao mesmo tempo,
mesmo com o atributo `hidden`.

**Causa:** `.formulario { display: grid }` é uma regra de autor e vence o `[hidden] {display:none}`
da folha de estilo padrão do navegador, que é de origem inferior.

**Solução:** `[hidden] { display: none !important; }` no topo do CSS.

**Lição:** a cascata do CSS tem camadas de origem (navegador < autor < autor `!important`). Um
`display` genérico numa classe pode anular um mecanismo básico do HTML sem avisar.

### 8.4 Mockito não funciona no JDK 26

**Sintoma:** `Mockito cannot mock this class: RestTemplate` / `Could not modify all classes`.

**Causa:** a versão do Byte Buddy trazida pelo Spring Boot 3.4 não conhece o formato de classe do
Java 26. Instrumentação de bytecode é sempre a primeira coisa a quebrar num JDK novo.

**Solução:** trocar o mock por **`MockRestServiceServer`**, do `spring-test`, que intercepta no
nível do `RestTemplate` sem mexer em bytecode. E ficou melhor: os testes passaram a verificar a URL
chamada, o corpo enviado (inclusive o timestamp de Lamport!) e o header `Authorization`, coisa que
o mock genérico não checava.

**Lição:** quando a ferramenta briga com o ambiente, às vezes a saída não é forçar a ferramenta —
é usar a que o próprio framework já oferece. O resultado costuma ser mais expressivo.

### 8.5 `mvn package` falhou com "Unable to rename jar"

**Causa:** as três agências estavam rodando **a partir daquele jar**; no Windows, arquivo aberto
não pode ser renomeado, e o `spring-boot:repackage` renomeia o jar original.

**Solução:** parar os processos antes de reempacotar.

**Lição:** Windows trava arquivos abertos (Linux não). Rebuild com o serviço no ar é rotina em
Unix e não funciona aqui.

---

## 9. Como se trabalhou (e por que valeu a pena)

**TDD de verdade, não teatro.** Cada parte começou com testes falhando: primeiro
`ConfigAgenciasTest` (vermelho: classe não existe), depois a implementação. São 39 testes hoje. O
ganho real não foi "pegar bugs" — foi que quando o JWT entrou e protegeu a API inteira, os testes
de conta e transferência quebraram na hora, mostrando exatamente o que a mudança afetou. Sem eles,
a descoberta seria manual, clicando na tela.

O teste que mais vale: `limitacaoConhecidaAgenciaDeDestinoForaDoAr`. Ele **afirma que o saldo NÃO
volta**. Um teste que documenta um defeito conhecido, para que ninguém o "conserte" por acidente e
para deixar registrado que o comportamento é intencional. Quando o Sprint 4 chegar, esse teste é o
primeiro a ser invertido.

**Commits por parte, não um despejo no fim.** Um commit para cada parte do roteiro (config,
Lamport, contas, transferências, linha do tempo, auth, extra, frontend). O histórico conta a
história da construção, que é exatamente o que o roteiro pede para avaliar.

**Validação com o cliente real.** Todo o fluxo foi executado no navegador — login, criar conta,
depositar, sacar, transferir local, transferir entre agências, erro de saldo, 502 com agência
derrubada, reenvio idempotente. Os três bugs de frontend das seções 8.1–8.3 só apareceram porque o
fluxo foi executado de verdade, não porque o código "parecia certo".

---

## 10. Comandos essenciais

```powershell
cd agencia; mvn clean package                              # compila e roda os 39 testes
cd agencia; $env:AGENCIA_ID=0; mvn spring-boot:run         # sobe uma agência (0, 1 ou 2)
cd frontend; python -m http.server 5500                    # serve o frontend
cd agencia; mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"
```

| Recurso | Onde |
|---|---|
| Portas | 4145 / 4146 / 4147 (frontend em 5500) |
| Usuários | `ana`, `bruno`, `carla` — senha `senha123` |
| Logs de evento | `agencia/data/eventos-agencia-N.jsonl` (fora do Git, por design) |
| Respostas do roteiro | `RESPOSTAS.md` |
| Roteiro de evidências | `evidencias/sprint1/COMO-GERAR.md` |

---

## 11. O que o Sprint 2 vai precisar daqui

- `RelogioLamport` vira `RelogioVetorial`: um contador por processo em vez de um só. A interface
  (`eventoLocal` / `aoEnviar` / `aoReceber`) tende a se manter; o que muda é o tipo do carimbo.
- `RegistroEventos` já grava `timestampLamport` no JSON — vai virar um vetor. `MesclarLogs` passa
  a comparar vetores (dominância) em vez de ordenar inteiros, e aí sim consegue **provar** que dois
  eventos são concorrentes, em vez de só suspeitar.
- A chamada HTTP direta entre agências (`creditar-remoto`) dá lugar a mensageria. O token de
  serviço provavelmente vira credencial do broker.
- A idempotência já implementada passa a ser essencial: broker com entrega *at-least-once* reentrega
  mensagens, e sem idempotência isso vira dinheiro duplicado.
