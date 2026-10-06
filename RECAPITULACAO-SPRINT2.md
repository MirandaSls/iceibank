# Recapitulação: Comunicação indireta, Mensageria, Pub/Sub e Relógio Vetorial

**Aluno:** Arthur Miranda Sales · **Projeto:** ICEIBank · respostas baseadas no código do Sprint 1
(estado de partida) e na evolução implementada no Sprint 2 (este repositório).

## 1. Arquitetura atual e divisão em agências

São **3 processos** Spring Boot idênticos (`AgenciaApplication`), diferenciados por `AGENCIA_ID`
(porta 4145/4146/4147), mais um frontend estático (porta 5500) que fala HTTP+JWT com a agência
escolhida no login. Cada agência é dona de uma **partição** das contas: `idConta % 3`
(`ConfigAgencias.agenciaResponsavel`). Conta 0, 3, 6… → Agência 0; 1, 4, 7… → Agência 1; 2, 5, 8… → Agência 2.
Não há replicação nem banco: contas, relógio e log vivem em memória (`EstadoAgencia`) e o log
também em `agencia/data/eventos-agencia-N.jsonl`.

- **Local (só a agência dona):** criar conta, consultar, depositar, sacar, transferir entre duas
  contas da mesma agência.
- **Depende de outra agência:** transferência cuja conta de destino pertence a outra partição:
  o débito é local, o crédito precisa chegar à agência de destino.

## 2. Comunicação atual entre as agências

No Sprint 1 era uma **chamada REST síncrona**: a origem fazia `POST /contas/{id}/creditar-remoto`
na agência de destino (`RestTemplate`, timeouts de 3 s/5 s), com um token JWT de serviço. Fluxo:
débito local → HTTP → crédito remoto → `200` → resposta ao cliente. Se o destino está fora do ar,
lento ou a resposta se perde, a chamada lança `RestClientException`; a origem responde **502** e o
débito **não** é revertido: o dinheiro some (limitação conhecida, teste
`limitacaoConhecidaAgenciaDeDestinoForaDoAr`). A origem fica acoplada à disponibilidade e à
latência do destino e não sabe se o crédito foi aplicado quando o erro é de timeout.

## 3. Operações distribuídas e seus efeitos

Transferência da conta 0 (Ag. 0) para a 1 (Ag. 1), valor 30:
1. Ag. 0 valida a conta de origem e o saldo.
2. Ag. 0 debita 30 e registra `TRANSFERENCIA_DEBITO`.
3. Ag. 0 envia o crédito à Ag. 1 (REST no Sprint 1; mensagem no Sprint 2) e registra `TRANSFERENCIA_ENVIADA`.
4. Ag. 1 credita 30 na conta 1 e registra `TRANSFERENCIA_CREDITO_REMOTO`.

Dados alterados: saldo na Ag. 0 (passo 2) e na Ag. 1 (passo 4), mais os logs. A operação é
"concluída" para o cliente no `200` (Sprint 1: após o crédito remoto; Sprint 2: após a
**publicação** da mensagem, o crédito ocorre depois, de forma assíncrona). Entre os passos 2 e 4
o dinheiro está "em trânsito".

## 4. Eventos que poderiam ser publicados

| Evento | Dados | Produtor | Consumidores |
|---|---|---|---|
| `CreditoSolicitado` (routing key `agencia.<id>.creditar`) | idMensagem, idConta, valor, vetorEnvio, origemAgencia, idOrigem | agência de origem | agência dona da conta |
| `CreditoRecusado` / falha | idMensagem, motivo, vetor | agência de destino | dead-letter queue, operação/auditoria |
| `SaldoBaixo` (ideia) | idConta, saldo, limite | qualquer agência | serviço de notificação |
| `TransferenciaConcluida` (confirmação, ideia) | idMensagem, vetor | agência de destino | agência de origem |

Implementados: `CreditoSolicitado` e o encaminhamento do recusado para a DLQ.

## 5. Transferência com mensageria / Pub-Sub

- **Produtor:** agência de origem (`PublicadorRabbit`).
- **Canal:** exchange `iceibank.eventos` (topic, durável), routing key `agencia.<id>.creditar`.
- **Consumidor:** a agência de destino, via `fila-agencia-<id>` ligada àquela routing key
  (`ConsumidorCreditos` → `ProcessadorCreditos`). Só a fila do destino recebe.
- **Mensagem (JSON, persistente):**

```json
{ "idMensagem": "6f1c…", "idConta": 1, "valor": 30,
  "vetorEnvio": [2,0,0], "origemAgencia": 0, "idOrigem": 0 }
```

A origem não conhece o destino nem precisa dele no ar: a fila durável retém a mensagem.

## 6. Duplicidade, falha e fora de ordem

- **Duplicada** (o ack se perde e o broker reentrega, entrega *at-least-once*): sem controle, o
  crédito seria somado duas vezes e o dinheiro duplicaria. Solução implementada: `idMensagem`
  único e conjunto de mensagens já processadas (`CREDITO_DUPLICADO_IGNORADO`).
- **Falha/reprocessamento:** conta inexistente (ex.: a agência reiniciou e perdeu a memória) ou
  mensagem malformada. Sem tratamento a mensagem voltaria em loop; agora é rejeitada sem
  reenfileirar e vai para a **dead-letter queue**. Como a falha **não** marca o id como processado,
  um reprocessamento posterior é possível (`recusaNaoMarcaComoProcessada`).
- **Fora de ordem:** dois créditos/débitos da mesma conta chegando trocados. Para somas comutativas
  o saldo final é o mesmo, mas operações dependentes (ex.: saque que exige saldo) mudam de resultado.
- **Informação necessária:** id único da mensagem, vetor/ordem causal, origem, destino, valor e
  estado do processamento.

## 7. Eventos concorrentes e ordem de recebimento

Cenário: a Ag. 0 publica o crédito **A** para a Ag. 2 às 10:00:00; a Ag. 1 publica o crédito **B**
para a Ag. 2 às 10:00:01. Por atraso de rede e filas separadas, a Ag. 2 consome **B antes de A**. A
ordem de recebimento (B, A) difere da ordem de ocorrência (A, B), e A e B são **concorrentes**:
nenhum causou o outro. Consequência: ler o log por ordem de chegada ou por hora de parede (relógios
de máquinas diferentes discordam) leva a conclusões falsas sobre causa e efeito. É preciso separar
**ordem local** (sequência no mesmo processo), **ordem de recebimento** (acidental) e **relação
causal** (o que realmente importa).

## 8. Relógio vetorial na aplicação

- **Participantes:** as 3 agências, um vetor `[v0, v1, v2]` (`RelogioVetorial`).
- **Atualizações:** evento local → `v[eu]++`; envio → `v[eu]++` e anexa o vetor à mensagem
  (`vetorEnvio`); recebimento → `v[i] = max(v[i], recebido[i])` para todo `i`, depois `v[eu]++`.
- **Comparação** (`ComparadorVetorial`): `V1 ≤ V2` em todas as posições (e ≠) → V1 aconteceu antes; senão, se nenhum é ≤ ao outro → concorrentes.
- **Concorrentes:** `CRIAR_CONTA` na Ag. 0 `[1,0,0]` e `CRIAR_CONTA` na Ag. 1 `[0,1,0]`.
- **Causais:** `TRANSFERENCIA_ENVIADA` `[2,0,0]` na Ag. 0 → `TRANSFERENCIA_CREDITO_REMOTO` `[2,1,0]` na Ag. 1: `[2,0,0] ≤ [2,1,0]`, logo o envio é anterior.

## 9. Consistência e observabilidade

Cada evento é gravado em `.jsonl` com `agencia`, `tipo`, `timestampVetorial`, `horaParede` e
`detalhes` (inclui `idMensagem`, `idConta`, `valor`, `origemAgencia`). Com isso o `MesclarLogs`:
(a) lista pares **concorrentes** entre agências diferentes (vetores incomparáveis);
(b) liga `TRANSFERENCIA_ENVIADA` ao `TRANSFERENCIA_CREDITO_REMOTO` pelo `idMensagem` e confirma que
o par é "antes", provando que a agência de destino recebeu informação causada pela de origem;
(c) a ordem de dois eventos é decidida pela comparação dos vetores, não pela hora de parede.

## 10. Proposta de evolução (e o que foi implementado)

**Operação alterada:** a transferência entre agências. **Componentes:** controller de
transferências, `PublicadorRabbit`, RabbitMQ (exchange topic + filas por agência + DLQ),
`ConsumidorCreditos`/`ProcessadorCreditos`, `RelogioVetorial`, `RegistroEventos`, `MesclarLogs`.
**Mensagens:** `CreditoSolicitado` (acima) e, para falhas, o encaminhamento à DLQ.
**Metadados novos:** `idMensagem` e `vetorEnvio` na mensagem; `timestampVetorial` no log.
**Como demonstrar que funciona:**
- 55 testes automatizados: relógio vetorial (3 regras), topologia, consumidor (idempotência, conta
  ausente), fluxo causal sem broker (`FluxoCausalTest`) e `MesclarLogs`.
- Execução com broker (saídas em `evidencias/sprint2/execucao-real.txt`): transferência assíncrona,
  agência de destino fora do ar com a mensagem retida na fila, e linha do tempo causal com pares
  concorrentes e causais.
- O que **continua aberto**: sem persistência de contas, reiniciar a agência perde saldo; atomicidade
  entre débito e crédito fica para o Sprint 4 (2PC/Saga).
