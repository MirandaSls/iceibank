# RESPOSTAS — Sprint 1: ICEIBank

**Aluno:** Arthur Miranda Sales
**Disciplina:** Laboratório de Desenvolvimento de Aplicações Móveis e Distribuídas — U2
**Linguagem escolhida (Sprints 1 a 4):** Java 21 + Spring Boot 3.4 (Maven)
**Frontend:** HTML + CSS + JavaScript puro, sem framework
**OFFSET pessoal:** 45

---

## 0. Decisões de projeto e desvios do roteiro

### 0.1 Porta base: `4100 + OFFSET`, e não `4000 + OFFSET`

O roteiro sugere `PORTA_BASE = 4000 + OFFSET`. Com OFFSET 45 isso daria a porta **4045**, que
**Chrome e Firefox bloqueiam por padrão** (é a porta reservada ao serviço `lockd`). Na prática o
navegador recusa a requisição antes de sair da máquina, com `net::ERR_UNSAFE_PORT`, e o frontend
da Parte G nunca conseguiria falar com a Agência 0 — foi exatamente o que aconteceu no primeiro
teste de integração.

Como o Sprint 1 exige frontend web, mantive a ideia do roteiro (porta derivada do OFFSET pessoal,
sem colisão entre alunos) e apenas mudei a base para `4100 + OFFSET`:

| Agência | Porta |
|---|---|
| 0 | 4145 |
| 1 | 4146 |
| 2 | 4147 |

O particionamento, a lógica de Lamport e todo o resto seguem o roteiro sem alteração.

### 0.2 Adaptação do `mesclar-logs.js` para Java

O roteiro entrega o script da Parte E em Node.js. Como a entrega é em Java, ele virou a classe
`MesclarLogs` (`agencia/src/main/java/.../MesclarLogs.java`), com o mesmo comportamento: lê todos
os `.jsonl` da pasta `data/`, ordena por `timestampLamport` e imprime a linha do tempo unificada.
Acrescentei uma seção final que agrupa os **empates de timestamp entre agências diferentes**,
justamente para facilitar a observação pedida na seção 10.2 do roteiro.

### 0.3 Uso de ferramentas de IA

Usei o Claude (Anthropic) como apoio na estruturação do projeto, na revisão do código e na redação
deste documento. Todo o código entregue foi executado e testado por mim (39 testes automatizados
com JUnit + MockMvc, todos passando, além dos testes manuais registrados em `evidencias/`), e sou
capaz de explicar e defender qualquer trecho da entrega.

---

## 1. Funcionalidade adicional (seção 2.1): idempotência de transferências

### O que faz

A rota `POST /transferencias` passou a aceitar o cabeçalho **opcional** `Idempotency-Key`.

- **Sem o cabeçalho:** comportamento idêntico ao do roteiro — duas requisições iguais geram dois
  débitos.
- **Com o cabeçalho:** a agência guarda o resultado da primeira execução daquela chave. Se a mesma
  chave chegar de novo:
  - com o **mesmo corpo** → a transferência **não é reaplicada**; a agência devolve a resposta
    original, acrescentando o cabeçalho `Idempotency-Replayed: true`;
  - com um **corpo diferente** → devolve **409 Conflict**, porque reutilizar uma chave para outra
    operação seria um erro de quem chamou.

Os dois casos ficam registrados no log de eventos, com timestamp de Lamport
(`TRANSFERENCIA_REPETIDA_IGNORADA` e `IDEMPOTENCIA_CHAVE_REUTILIZADA`).

### Por que escolhi esta

Porque ela ataca um problema real de sistemas distribuídos, e não uma regra de negócio qualquer:
se a resposta de uma transferência se perde na rede, quem chamou não sabe se a operação foi
aplicada ou não. Reenviar é a reação natural — e, sem idempotência, reenviar significa transferir
duas vezes. É também a peça que conversa diretamente com o Sprint 4: uma Saga que faz *retry* de
uma etapa depende de a etapa ser idempotente para não duplicar efeitos.

### Onde está

- Backend: `TransferenciasController.transferir(...)` e `EstadoAgencia.chavesDeIdempotencia()`
- Testes: `IdempotenciaTest.java` (4 testes)
- Frontend: caixa "Proteger contra reenvio duplicado" na aba **Transferir** e o botão
  **"Reenviar a mesma requisição"**, que repete a última transferência com a mesma chave
- Evidência: `evidencias/sprint1/funcionalidade-adicional.png`

### Prova de funcionamento (execução real)

```
1ª chamada  → HTTP 200, saldo da conta 0 vai de 130 para 100
2ª chamada  → HTTP 200, Idempotency-Replayed: true, saldo continua 100
```

---

## 2. Parte B — Relógio de Lamport (seção 6.4)

### 2.1 Por que `max(contador_local, timestampRecebido) + 1` em vez de adotar o timestamp recebido?

Porque adotar o timestamp recebido **quebraria as duas garantias do algoritmo**.

O relógio de Lamport precisa ser monótono dentro de cada processo: se a Agência 0 já registrou 10
eventos, o próximo evento dela tem obrigatoriamente que ser maior que 10 — senão dois eventos
diferentes da mesma agência teriam o mesmo carimbo, e a ordem interna se perderia. Se ela adotasse
um timestamp recebido igual a 3, o relógio **andaria para trás** e os próximos eventos colidiriam
com eventos passados.

O `max` resolve o lado do "não retroceder"; o `+ 1` resolve o lado da causalidade: o recebimento
da mensagem *aconteceu depois* do envio dela, então precisa ter um valor **estritamente maior**
que o do envio. Sem o `+ 1`, envio e recebimento empatariam, e o empate significaria
"concorrentes" — o oposto da verdade, já que um causou o outro.

### 2.2 Agência 0 no contador 10 recebe mensagem com timestamp 3

O contador vai para **11**: `max(10, 3) + 1 = 11`.

A mensagem atrasada é simplesmente ignorada como referência, porque o relógio local já está mais
adiantado que ela. Isso mostra que **o relógio de Lamport não mede tempo, mede quantidade de
eventos causalmente encadeados**. Uma agência movimentada acumula um contador alto rapidamente;
uma agência ociosa fica com o contador baixo por horas. Comparar os dois números diretamente não
diz nada sobre "quem aconteceu antes no relógio de parede" — só faz sentido comparar timestamps
quando existe uma cadeia de mensagens ligando os dois eventos.

Na prática isso também significa que **agências rápidas puxam as lentas para cima**: assim que a
agência lenta recebe uma mensagem da rápida, o contador dela salta para perto do da outra. O
contrário não acontece — uma agência nunca é puxada para baixo.

---

## 3. Parte D — Transferências (seção 8.3)

### 3.1 Por que a transferência local não usa `aoEnviar()`/`aoReceber()`?

Porque **não existe mensagem entre processos** no caso local. Débito e crédito acontecem dentro do
mesmo processo, na mesma memória, na mesma requisição HTTP: são dois eventos locais, e a regra 1
(incrementar antes de cada evento) basta para ordená-los. Aplicar as regras 2 e 3 ali seria mentir
sobre a arquitetura — estaríamos simulando uma troca de mensagens que não aconteceu.

Na transferência entre agências, sim: a Agência 0 envia uma mensagem HTTP para a Agência 1. Aí o
envio precisa carimbar um timestamp (regra 2) e o recebimento precisa ajustar o relógio da Agência
1 para `max(local, recebido) + 1` (regra 3). É esse par que cria a **relação causal entre os
relógios de dois processos independentes** — sem isso, cada agência contaria eventos sozinha, sem
nenhuma ordenação comum.

Isso aparece na linha do tempo real da execução:

```
[Lamport 10] agencia-0 - TRANSFERENCIA_DEBITO   {"idOrigem":0,"idDestino":1,"valor":25}
[Lamport 11] agencia-0 - TRANSFERENCIA_ENVIADA  {"idOrigem":0,"idDestino":1,"valor":25}
[Lamport 12] agencia-1 - TRANSFERENCIA_CREDITO_REMOTO {"idConta":1,"valor":25,"origemAgencia":0}
```

A Agência 1 estava no contador 1 e saltou para 12 — `max(1, 11) + 1`.

### 3.2 O saldo de origem foi revertido depois do erro?

**Não.** Reproduzi a falha derrubando a Agência 2 e transferindo da conta 0 (Agência 0) para a
conta 2 (Agência 2):

```
Saldo antes:   R$ 35,00
Resposta:      HTTP 502 - "Falha ao contatar agencia de destino. Debito ja aplicado -
                            inconsistencia conhecida (ver Sprint 4)."
Saldo depois:  R$ 25,00
Conta 2:       não recebeu nada
```

Log da Agência 0:

```
[Lamport 15] TRANSFERENCIA_DEBITO  {"idOrigem":0,"idDestino":2,"valor":10}
[Lamport 17] TRANSFERENCIA_FALHOU  {"idOrigem":0,"idDestino":2,"valor":10,
                                    "erro":"...Connection refused: getsockopt"}
```

Em termos de consistência: o sistema violou a **atomicidade** da transferência. Ela é uma operação
que deveria ser tudo-ou-nada e ficou pela metade — R$ 10,00 saíram do sistema e não entraram em
lugar nenhum. Somando todos os saldos das três agências, o total do banco **diminuiu**, o que num
banco de verdade é um erro grave (o dinheiro não pode simplesmente evaporar). O estado global do
sistema ficou inconsistente e permanece assim: nada no Sprint 1 conserta isso sozinho, apenas o
log registra a inconsistência para que ela seja visível.

### 3.3 Duas formas de corrigir isso no Sprint 4

**1) Two-Phase Commit (2PC) — atomicidade por bloqueio.**
Um coordenador pergunta a todas as agências envolvidas "você consegue fazer sua parte?" (fase de
preparação). Cada uma reserva os recursos — a agência de origem congela o valor sem debitar de
fato — e responde *sim* ou *não*. Só se **todas** disserem sim o coordenador manda efetivar (fase
de commit); se qualquer uma falhar ou não responder, ele manda abortar e ninguém aplica nada.
Custo: enquanto a decisão não sai, os recursos ficam bloqueados, e se o coordenador cair no meio
os participantes ficam presos esperando.

**2) Saga com compensação — atomicidade por reversão.**
A transferência é quebrada em etapas locais, cada uma efetivada de imediato, e cada etapa ganha
uma **ação compensatória**. Débito na origem → crédito na agência de destino. Se o crédito falhar,
a saga executa a compensação do débito (um estorno) até o sistema voltar a um estado consistente.
Não bloqueia ninguém e escala melhor, mas aceita um período de inconsistência temporária e exige
que cada etapa seja idempotente — que é exatamente a funcionalidade adicional que já implementei
neste sprint.

---

## 4. Parte E — Linha do tempo unificada (seções 10.2 e 10.3)

### 4.1 O que observei (tarefa 10.2, passo 3)

A execução real produziu vários empates. Um deles:

```
Lamport 3:
  agencia-0 - CRIAR_CONTA (hora de parede: 2026-09-07T16:08:45.487Z)
  agencia-2 - CRIAR_CONTA (hora de parede: 2026-09-07T16:10:32.903Z)
```

Esses dois eventos são **concorrentes**, não causalmente relacionados: criar a conta 0 na Agência 0
não teve nenhuma influência sobre criar a conta 2 na Agência 2, e nenhuma mensagem trafegou entre
as duas. Elas chegaram ao contador 3 de forma independente, cada uma contando os próprios eventos.

O caso mais interessante foi este:

```
Lamport 12:
  agencia-0 - TRANSFERENCIA_DEBITO        (hora de parede: 16:10:51.034Z)
  agencia-1 - TRANSFERENCIA_CREDITO_REMOTO (hora de parede: 16:10:03.825Z)
```

Aqui a ordem por hora de parede e a ordem por Lamport **discordam completamente**: pelo relógio
físico, o evento da Agência 1 aconteceu quase 48 segundos *antes* do evento da Agência 0, mas os
dois têm o mesmo timestamp lógico. Isso não é um bug — é a definição do algoritmo. Eventos sem
relação causal não têm ordem definida, e o relógio de Lamport nunca prometeu concordar com o
relógio de parede. Aliás, é bom que não concorde: relógios físicos de máquinas diferentes têm
desvio (*clock skew*), e confiar neles para ordenar eventos distribuídos é justamente o erro que o
relógio lógico existe para evitar.

### 4.2 Se A e B têm timestamps diferentes, um influenciou o outro?

Não necessariamente — e essa é a limitação central do relógio de Lamport. A garantia dele é de mão
única:

> se A → B (A causou B), então `timestamp(A) < timestamp(B)`

A volta **não vale**: `timestamp(A) < timestamp(B)` não permite concluir que A causou B. Na minha
linha do tempo, `CRIAR_CONTA` na Agência 1 (Lamport 2) tem timestamp menor que `CRIAR_CONTA` na
Agência 0 (Lamport 3), e uma coisa não tem absolutamente nada a ver com a outra.

Ou seja: na prática, olhando dois timestamps diferentes eu só posso afirmar com segurança uma
coisa **negativa** — que B **não** causou A. Se A causou B ou se são concorrentes, o número
sozinho não diz.

### 4.3 Lamport basta para distinguir "concorrente" de "aconteceu antes"?

**Não basta.** Ele detecta apenas um subconjunto: timestamps iguais garantidamente indicam eventos
concorrentes, mas eventos concorrentes também aparecem com timestamps diferentes — como no exemplo
acima. Um único inteiro por processo simplesmente não carrega informação suficiente para
reconstruir quem sabia o quê no momento de cada evento.

É essa lacuna que motiva o **relógio vetorial** do Sprint 2: em vez de um contador, cada processo
mantém um vetor com um contador por processo do sistema, e anexa o vetor inteiro nas mensagens.
Comparando dois vetores dá para decidir com certeza: se todas as posições de V(A) forem ≤ às de
V(B) e pelo menos uma for estritamente menor, então A → B; se nenhum dos dois domina o outro, os
eventos são **provadamente concorrentes**. O custo é o tamanho: a mensagem passa a carregar N
inteiros em vez de 1, e o vetor cresce com o número de processos.

---

## 5. Parte F — Autenticação JWT (seções 11.1 e 11.3)

### 5.1 Formato das credenciais escolhido, e por quê

Escolhi **usuário e senha** (`POST /auth/login` com `{"usuario": "...", "senha": "..."}`), com uma
base de usuários em memória (`UsuariosEmMemoria`): `ana`, `bruno` e `carla`, todos com senha
`senha123`.

Considerei usar `id da conta + senha`, mas descartei por um motivo arquitetural: **conta é um
conceito particionado, usuário não é**. A conta 1 só existe na Agência 1; se o login fosse pelo id
da conta, cada agência só conseguiria autenticar as próprias contas, e a pessoa teria que descobrir
em qual agência fazer login antes de conseguir um token. Com usuário/senha, as três agências
carregam a mesma lista e qualquer uma consegue autenticar qualquer pessoa — o que casa com a
realidade (uma pessoa pode ter contas em agências diferentes) e deixa o frontend muito mais simples.

**Expiração:** 15 minutos para token de usuário. Curto o bastante para limitar o estrago de um
token vazado, longo o bastante para não atrapalhar uma sessão de uso.

**Chave secreta:** as três agências compartilham a mesma chave (`iceibank.jwt.segredo`), lida de
variável de ambiente com um valor padrão só para desenvolvimento. É isso que permite a uma agência
validar um token emitido por outra sem nenhuma consulta pela rede.

**Biblioteca:** `io.jsonwebtoken:jjwt` 0.12.6, com HMAC-SHA256.

### 5.2 A chamada entre agências deveria levar token igual ao do frontend?

**Não, e implementei diferente de propósito.** O token do usuário e o token de serviço são
distinguidos por uma claim `tipo`:

| Tipo | Emitido por | Vale para | Validade |
|---|---|---|---|
| `USUARIO` | login de uma pessoa | rotas de conta e transferência | 15 min |
| `SERVICO` | a própria agência de origem | somente `POST /contas/{id}/creditar-remoto` | 30 s |

O filtro (`JwtFilter`) recusa token de usuário na rota interna **e** token de serviço nas rotas de
usuário — os dois sentidos são bloqueados, com 401.

Justificativa:

1. **Quem chama não é a pessoa, é a agência.** `creditar-remoto` é comunicação máquina-a-máquina.
   Reaproveitar o token da pessoa seria personificá-la em uma operação que ela não fez diretamente,
   e embaralharia a trilha de auditoria.
2. **Repassar o token do usuário aumenta a superfície de ataque.** Se a Agência 1 fosse comprometida,
   ela ficaria com tokens de usuários válidos por 15 minutos e poderia agir como eles em qualquer
   agência. O token de serviço vive 30 segundos e só abre uma rota.
3. **Privilégio mínimo.** `creditar-remoto` é uma rota interna: nenhum cliente externo deveria
   alcançá-la, e um token de usuário nunca abre essa porta.
4. **Ciclo de vida diferente.** Uma transferência iniciada com um token quase expirando ainda
   precisa completar o crédito remoto. Com token próprio, a etapa interna não depende do prazo do
   token da pessoa.

O que **não** implementei (e reconheço como limitação): a agência receptora valida a assinatura e o
tipo do token, mas não verifica *qual* agência o emitiu contra uma lista de identidades confiáveis.
Como a chave é compartilhada, qualquer detentor da chave pode emitir um token de serviço. Numa
evolução, cada agência teria seu próprio par de chaves e a validação seria por chave pública.

### 5.3 Autenticação x autorização — minha implementação verifica as duas?

**São coisas diferentes:** autenticação responde "quem é você?" (provar identidade); autorização
responde "você pode fazer isso?" (permissões sobre um recurso).

Minha implementação faz **autenticação completa** e apenas um **fragmento de autorização**:

- ✅ Autenticação: assinatura verificada, expiração verificada, 401 em qualquer falha.
- ⚠️ Autorização: existe só no nível de *tipo de token* (usuário x serviço) — um token de usuário
  não acessa a rota interna e vice-versa.
- ❌ Autorização por dono da conta: **não existe**.

Respondendo diretamente à pergunta do roteiro: **sim, na implementação atual um usuário autenticado
consegue sacar de uma conta que não é dele.** A `ana` pode fazer login e sacar da conta do `bruno`,
porque a conta não guarda vínculo com um usuário — o campo `nomeAluno` é texto livre, não uma
referência ao dono. Todo mundo autenticado tem os mesmos poderes sobre todas as contas.

Isso é uma escolha consciente de escopo, não um descuido: o roteiro pede na Parte F que as rotas
"exijam um token JWT válido", que é exatamente o que está feito. Fechar a lacuna exigiria um campo
`dono` na conta, preenchido na criação a partir do `sub` do token, e uma verificação
`conta.dono == token.sub` em cada operação — coisa de poucas linhas, mas que muda o modelo de
dados e é candidata natural a um sprint seguinte.

### 5.4 Por que o servidor não precisa de banco de dados para validar um JWT?

Porque o token é **autocontido e assinado**. Ele carrega os próprios dados (`sub`, `tipo`, `iat`,
`exp`) e uma assinatura HMAC-SHA256 calculada sobre eles com a chave secreta. Para validar, o
servidor recalcula o HMAC do cabeçalho+payload recebidos e compara com a assinatura que veio junto.
Se bater, é matematicamente certo que o conteúdo não foi alterado por quem não tem a chave. A
expiração vem dentro do próprio payload, então também é verificada localmente.

Comparando com sessão em memória no servidor:

| | Sessão no servidor | JWT |
|---|---|---|
| Validar requisição | consulta a um estado compartilhado | cálculo local de HMAC |
| Escalar horizontalmente | precisa de sessão distribuída (Redis) ou *sticky sessions* | qualquer instância valida sozinha |
| Revogar um acesso | imediato (apaga a sessão) | difícil: o token vale até expirar |
| Ponto único de falha | o repositório de sessões | nenhum |

No ICEIBank isso é o que faz o sistema funcionar: um token emitido pela Agência 0 é aceito pelas
Agências 1 e 2 **sem nenhuma comunicação entre elas** — testei isso na interface, fazendo login na
Agência 0 e trocando a agência de entrada para a 1 no seletor, sem novo login. Se as sessões
fossem guardadas em memória, cada agência teria que consultar a outra a cada requisição, e o
sistema deixaria de ser particionado de verdade.

O preço é a revogação: um token roubado continua válido até expirar (daí os 15 minutos curtos).
Quem precisa de revogação imediata acrescenta uma lista de tokens revogados — que traz de volta o
estado compartilhado que o JWT tinha eliminado.

### 5.5 O que aconteceria se a chave secreta vazasse?

Seria comprometimento total do sistema de autenticação. Quem tivesse a chave poderia:

1. **Forjar tokens de qualquer usuário**, sem precisar de senha — assinaria um payload
   `{"sub":"ana","tipo":"USUARIO"}` e as três agências aceitariam, porque a assinatura seria
   legítima.
2. **Forjar tokens de serviço** e chamar `creditar-remoto` diretamente, criando dinheiro do nada:
   creditar qualquer conta com qualquer valor, sem débito correspondente em lugar nenhum.
3. **Escolher a validade que quisesse** — um token com expiração daqui a 10 anos.
4. **Ler o conteúdo de qualquer token** — o payload do JWT é só Base64URL, nem precisa da chave
   para isso; a chave garante integridade, nunca sigilo.

E o pior: como a validação é local e sem estado, **nada disso apareceria em log de acesso indevido**
— os tokens forjados são indistinguíveis dos legítimos.

Mitigações reais: nunca versionar a chave (por isso ela vem de variável de ambiente, com o valor do
repositório servindo só para desenvolvimento), guardá-la num cofre de segredos, rotacionar
periodicamente com suporte a duas chaves válidas durante a transição, e usar chaves assimétricas
(RS256) para que só o emissor tenha a chave privada e os validadores tenham apenas a pública.

---

## 6. Parte G — Frontend (seções 12.1 e 12.3)

### 6.1 Como o frontend "lembra" de reenviar o token

O token é guardado no `localStorage` no momento do login (`sessao.abrir(...)` em `model.js`) e
existe **um único ponto de saída HTTP** em todo o frontend: a função `chamar()`, também em
`model.js`. Toda operação — consultar, depositar, sacar, transferir, criar conta — passa por ela,
e é lá que o cabeçalho é montado:

```js
if (sessao.token) {
  requisicao.headers.Authorization = `Bearer ${sessao.token}`;
}
```

Nenhuma tela e nenhum handler precisa lembrar do token: quem esquecer não consegue nem fazer a
requisição, porque não existe outro caminho até a API. Escolhi `localStorage` (e não memória) para
que um F5 não derrube a sessão. É a escolha comum em aplicações assim, com a ressalva conhecida:
`localStorage` é acessível por JavaScript, então é vulnerável a XSS. Um sistema em produção
guardaria o token em cookie `HttpOnly` + `Secure`, inacessível ao script da página.

### 6.2 O que acontece se o token expirar no meio de uma operação?

A pessoa é avisada explicitamente, não vê erro genérico. O fluxo:

1. A API responde **401** com `{"erro": "Token expirado. Faca login novamente."}`.
2. `chamar()` detecta o 401, **limpa a sessão** do `localStorage` e lança um `ErroDaApi` com o
   status preservado.
3. O `Controller` (`tratarErro`) reconhece o 401, registra a ocorrência no "Diário da sessão",
   volta para a tela de login e mostra a mensagem: *"Sua sessão expirou ou o token é inválido.
   Entre novamente."*

Ou seja: a pessoa não fica clicando num botão que não funciona e nem descobre o problema pelo
console — ela é levada de volta ao login com a explicação do motivo. Detalhe que precisou de
atenção no backend: o filtro de CORS roda **antes** do filtro de JWT justamente para que as
respostas 401 cheguem ao navegador com os cabeçalhos de CORS; sem isso o `fetch` falharia com um
erro genérico de CORS e a mensagem real do servidor se perderia.

### 6.3 Onde estão o M, o V e o C no meu frontend?

A separação é explícita, um arquivo por papel:

| Camada | Arquivo | Responsabilidade | Regra que respeitei |
|---|---|---|---|
| **Model** | `js/model.js` | Estado da sessão, contas, chamadas HTTP à API, cálculo da agência responsável | Não toca no DOM: nenhum `document.` no arquivo |
| **View** | `js/view.js` | Desenhar conta, alertas, abas, diário, animação do saldo | Não faz `fetch` e não decide regra de negócio |
| **Controller** | `js/controller.js` | Escuta os eventos da tela, chama o Model, entrega o resultado à View, trata erro | Não monta HTML nem monta requisição |
| *bootstrap* | `js/app.js` | Só liga o Controller quando a página carrega | — |

A separação ficou bem limpa, com dois pontos que vale reconhecer honestamente:

1. **A View também guarda um pouquinho de estado.** O saldo anterior fica em `dataset.valor` no
   próprio elemento, para animar a contagem de um valor ao outro. Num MVC purista esse valor
   pertenceria ao Model.
2. **O Model tem uma regra duplicada do backend:** `agenciaResponsavel(idConta)` repete o
   `id % 3` que já existe em `ConfigAgencias.java`. É duplicação consciente e apenas informativa —
   serve para a interface dizer antecipadamente "essa transferência vai ser entre agências". Quem
   decide de verdade continua sendo o backend; se o frontend errasse essa conta, nada quebraria.

A ligação entre as camadas é sempre de mão única: Controller → Model e Controller → View. A View
nunca chama o Model, e o Model nunca chama a View — quem tem de coordenar é sempre o Controller.

---

## 7. Como reproduzir tudo

```powershell
# compilar
cd agencia
mvn clean package

# subir as 3 agencias (um terminal cada)
$env:AGENCIA_ID=0; mvn spring-boot:run
$env:AGENCIA_ID=1; mvn spring-boot:run
$env:AGENCIA_ID=2; mvn spring-boot:run

# frontend
cd frontend
python -m http.server 5500     # depois abra http://localhost:5500

# linha do tempo unificada
cd agencia
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"

# testes automatizados (39 testes)
cd agencia
mvn test
```

Usuários disponíveis: `ana`, `bruno`, `carla` — senha `senha123` para todos.

---
---

# RESPOSTAS — Sprint 2: Mensageria (RabbitMQ) e Relógio Vetorial

**Unidade:** U3 — Comunicação Indireta. Mesma linguagem do Sprint 1 (Java 21 + Spring Boot).

## S2.0 Estado da entrega e uso de IA

- **Validado por execução:** 55 testes automatizados passando (`mvn test`), incluindo a topologia do
  RabbitMQ declarada (exchange topic, filas duráveis, bindings, DLQ), as três regras do relógio
  vetorial, o consumidor de créditos (idempotência, conta inexistente) e o `MesclarLogs`.
- **Regressão executada (3 agências no ar, sem broker, pelo jar e pelo frontend em
  `http://localhost:5500`):** rota sem token → 401; login, criar contas nas 3 agências, depósito,
  saque, transferência local e idempotência (`Idempotency-Replayed: true`) funcionando. Transferência
  entre agências com o broker fora do ar → **502** "Broker de mensagens indisponível... débito
  revertido", e o saldo da origem continuou 100 (conferido por API e pela tela, cujo diário
  também registra a reversão). `MesclarLogs` sobre esses logs reais: 17 eventos, 56 pares
  concorrentes (ex.: `CRIAR_CONTA [3,0,0]` x `CRIAR_CONTA [0,2,0]`). As agências sobem normalmente
  sem broker; o consumidor só fica tentando reconectar.
- **Executado com broker real** (CloudAMQP/LavinMQ, `amqps`, 3 agências): transferência assíncrona
  0→1 (conta 1 passou de 0 a 30), resiliência com a Agência 1 derrubada, DLQ e `MesclarLogs`
  causal. Saídas em `evidencias/sprint2/execucao-real.txt`. Transferência entre agências pela tela
  (frontend) também confirmada (conta 0: 55, conta 1: 5).
- **Evidências** (`evidencias/sprint2/*.png`): capturas reais de tela da execução de 05/10/2026
  (Terminal do macOS com `date` visível e painel LavinMQ Manager do CloudAMQP):
  `transferencia-assincrona.png` (3 agências + transferência 0→1, saldos 70 e 30),
  `resiliencia-fila.png` (Agência 1 parada, `fila-agencia-1` com 1 mensagem retida e 0 consumidores),
  `dead-letter-queue.png` (`fila-agencia-1.dlq` com 2 mensagens após `Conta 1 nao encontrada`) e
  `linha-do-tempo-causal.png` (`MesclarLogs`: 2 pares causais e 26 concorrentes).
  `execucao-real.txt` guarda a saída de texto de uma execução anterior, equivalente.
- Usei o Claude (Anthropic) para estruturar e escrever código e documentação. Declaro o uso e
  consigo explicar cada trecho.

## S2.1 Parte A — RabbitMQ (topologia)

Declarada em `MensageriaConfig`: exchange `iceibank.eventos` (topic, durável); filas
`fila-agencia-0/1/2` (duráveis) ligadas por `agencia.<id>.creditar`; mensagens persistentes.
Toda agência declara as 3 filas, então uma transferência para agência que nunca subiu fica retida.
URL do broker em `RABBITMQ_URL` (CloudAMQP ou local).

## S2.2 Funcionalidade adicional — Dead-letter queue (seção 2.1)

**O que faz:** cada fila tem `x-dead-letter-exchange = iceibank.eventos.dlx`. Quando o consumidor
não consegue aplicar um crédito (conta inexistente, mensagem malformada), rejeita a mensagem
**sem reenfileirar** (`AmqpRejectAndDontRequeueException`) e ela vai para `fila-agencia-<id>.dlq`.
Sem isso, o default do Spring AMQP reenfileira e a mensagem voltaria em loop infinito.

**Por que esta:** cobre exatamente o buraco do cenário da Parte C: o crédito para a conta que não
existe mais deixa de ser descartado em silêncio e fica guardado, inspecionável e reprocessável.
**Extra relacionado:** cada mensagem tem `idMensagem`; reentregas (at-least-once) são ignoradas
(`CREDITO_DUPLICADO_IGNORADO`), mesma ideia da idempotência do Sprint 1.
**Onde:** `MensageriaConfig`, `ConsumidorCreditos`, `ProcessadorCreditos`; testes
`MensageriaConfigTest` e `ProcessadorCreditosTest`.

## S2.3 Parte B — Relógio vetorial (seção 6.4)

**1. Com 10 agências, o que acontece com o vetor?** Cada mensagem passa a carregar 10 inteiros em
vez de 3: o custo é O(N) por mensagem e por evento registrado. Com 3 agências é irrelevante; com
milhares de processos vira problema real (por isso existem variações como vetores esparsos/
version vectors, ou voltar a Lamport quando a precisão causal não é necessária). Além disso o
conjunto de processos precisa ser conhecido e fixo.

**2. `[3,1,0]` x `[3,2,0]`:** todas as posições do primeiro são ≤ às do segundo (3≤3, 1≤2, 0≤0) e
são diferentes, logo o primeiro evento aconteceu **antes** do segundo (teste `v1AntesDeV2`).

**3. `[3,1,0]` x `[1,3,0]`:** na posição 0, 3>1; na posição 1, 1<3. Nenhum é ≤ ao outro, logo são
**concorrentes** (teste `concorrentes`).

## S2.4 Parte C — Publish/Subscribe (seção 7.5)

**1. O que acontece quando a Agência 1 volta?** A mensagem ficou retida na `fila-agencia-1`
(durável + persistente), então a mensageria **não falhou**: ao reconectar, o consumidor a recebe
e aplica `aoReceber` no relógio. O que decide o resultado é a **conta**: as contas vivem só em
memória; se a agência *reiniciou*, ela voltou vazia, o crédito encontra `contas.get(id) == null`,
e é registrado `CREDITO_REMOTO_FALHOU` ("conta nao encontrada"). O dinheiro já saiu da origem e
não entrou em lugar nenhum — mas agora a mensagem vai para a `fila-agencia-1.dlq` em vez de se
perder (`ProcessadorCreditosTest.contaInexistenteEhRecusada`). Se a agência apenas ficou
indisponível sem perder memória, o crédito é aplicado normalmente.
**Observado na execução real:** com a Agência 1 derrubada, a transferência de 10 devolveu `200` e a
`fila-agencia-1` passou a ter 1 mensagem retida. Ao subir a Agência 1 de novo (processo novo, sem
contas), a mensagem foi entregue (fila → 0), o log registrou `CREDITO_REMOTO_FALHOU` ("conta nao
encontrada", vetor `[6,1,0]`) e a `fila-agencia-1.dlq` ficou com 1 mensagem; `GET /contas/1` → 404.
Ou seja, a mensageria não falhou: o crédito é que não teve onde ser aplicado.

**2. O que melhorou e o que continua aberto.** Melhorou: a origem não depende mais de o destino
estar no ar (antes: 502 e débito pendurado), a mensagem sobrevive à indisponibilidade, e se o
broker estiver fora a transferência falha **antes** do débito definitivo (revertido, pois sabemos
que nada foi publicado). Continua aberto: "a mensagem não se perde" ≠ "o sistema está correto":
sem persistência das contas, uma agência que reinicia perde o saldo e não consegue aplicar
créditos; o `200` da API só significa "publicado", não "creditado"; e não há transação
distribuída ligando débito e crédito (Sprint 4: 2PC/Saga).

**3. Consumidor sem JWT é problema de segurança?** Sim, é uma superfície real: quem consegue
publicar na exchange cria dinheiro, e o JWT não protege esse caminho. No ambiente de
desenvolvimento qualquer processo com a `RABBITMQ_URL` (ou `guest/guest` local) publica; na
CloudAMQP, quem tiver a URL. A proteção passa a ser a credencial/vhost do broker (usuários
separados para publicar e consumir, permissões por exchange/fila, TLS `amqps`), e idealmente
assinar a mensagem (HMAC/JWT no corpo) e validar. Por isso removi a rota REST `creditar-remoto`
e o token de serviço do Sprint 1: a autenticação entre agências passou a ser a do broker.

## S2.5 Parte D — Linha do tempo causal (seção 8.3)

**1. O que torna a comparação confiável no vetor?** Cada posição conta os eventos de **um**
processo que influenciaram aquele evento. `V1 ≤ V2` em todas as posições significa que tudo que
V1 "viu" V2 também viu, ou seja, há um caminho de mensagens de um ao outro. Em Lamport, um número
só resume tudo, então `L(a) < L(b)` não prova que a causou b. Aqui a relação é "se e somente se".

**2. Par concorrente.** Ex.: `CRIAR_CONTA` na Agência 0 `[1,0,0]` e `CRIAR_CONTA` na Agência 1
`[0,1,0]`: nenhuma mensagem ligou as duas, então um não pode ter causado o outro (coberto por
`MesclarLogsTest.detectaEventosConcorrentes`). Já `TRANSFERENCIA_ENVIADA` `[2,0,0]` →
`TRANSFERENCIA_CREDITO_REMOTO` `[2,1,0]` são causais (ANTES) e não aparecem como concorrentes
(`transferenciaNaoEhConcorrente`); o script os lista em "pares causais" ligados pelo `idMensagem`.

**3. O(n²) é problema?** Sim com milhões de eventos. Dá para: comparar só eventos candidatos
(janela de tempo de parede, ou por conta/agência), indexar por vetor, processar em streaming
mantendo o "frontier" por agência, usar processamento distribuído (map/reduce) ou limitar a análise
a eventos de interesse (ex.: só débitos/créditos da mesma conta).

## S2.6 Como reproduzir

```powershell
cd agencia; mvn clean package               # 54 testes, sem precisar de broker
$env:RABBITMQ_URL="amqp://guest:guest@localhost:5672"   # ou a URL do CloudAMQP
$env:AGENCIA_ID=0; mvn spring-boot:run      # idem 1 e 2, um terminal cada
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"
```

---

# RESPOSTAS — Sprint 3: Eleição de líder (Bully), App Flutter e Offline-first

## S3.0 Decisões de projeto e uso de IA

### Adaptação do Bully para Java

O roteiro traz o Bully em Node.js/Express. Mantive o algoritmo e as mensagens (eleição, resposta e
anúncio de coordenador) e troquei só as ferramentas pelas equivalentes do Java:

- **Chamadas entre agências:** `java.net.http.HttpClient` com timeout curto, no lugar do `fetch`.
  Uma agência que não responde dentro do timeout do Bully conta como fora do ar.
- **Heartbeat:** um `ScheduledExecutorService` no lugar do `setInterval`. A cada intervalo a agência
  consulta a líder atual e, se ela não responder, inicia uma eleição.
- **Estado compartilhado:** líder atual e flag "eleição em andamento" em variáveis atômicas
  (`AtomicReference` / `AtomicBoolean`). No Node o event loop é de uma thread só e isso não
  importa; no Spring, o heartbeat agendado e as requisições HTTP rodam em threads diferentes e
  podem tentar começar duas eleições ao mesmo tempo. O `compareAndSet` impede isso.
- **Histórico de lideranças:** o script do roteiro virou a classe Java `HistoricoLideranca`, do mesmo
  jeito que o `mesclar-logs.js` virou `MesclarLogs` no Sprint 1 (seção 0.2). Ela lê os `.jsonl` das 3
  agências e monta a sequência de líderes eleitos.
- **`obterVetorAtual()`:** no `RelogioVetorial` já existia o método `vetor()`, que devolve uma
  cópia do vetor sem incrementar. É exatamente o `obterVetorAtual()` da seção 9.1, então reaproveitei
  esse método em vez de criar um segundo com o mesmo comportamento.
- **`/bully/**` público:** as rotas do Bully ficam fora do `JwtFilter`. Quem chama são as outras
  agências (sem token de usuário) e o app antes do login, porque o painel de liderança aparece já
  na tela inicial. Essas rotas não mexem em dinheiro, só expõem e mudam quem é a líder. Num
  ambiente real eu protegeria pelo menos `eleicao`/`coordenador` com autenticação entre serviços,
  mas com a líder sendo só informativa neste sprint o risco é baixo, e fica registrado como
  limitação.

### Escolhas do app Flutter

- **Sem pacote de gerenciamento de estado.** Usei `ChangeNotifier` (do próprio Flutter) com
  `ListenableBuilder`/`AnimatedBuilder` nas telas. São dois estados vivos: `LiderancaService`
  (polling) e `ServicoContas` (cache, fila e sincronização). Para esse tamanho, Provider, Riverpod
  ou Bloc trariam dependência e conceitos novos sem resolver nenhum problema que eu tivesse. As
  classes de serviço não importam widgets, então são testáveis isoladamente (o `LiderancaService`
  recebe a consulta como função, e o teste passa um fake).
- **Hive como armazenamento local** (justificativa completa na pergunta 1 da Parte F). Uso caixas
  guardando `Map` JSON (`toJson`/`fromJson` dos próprios modelos), sem `TypeAdapter` gerado. Assim
  não preciso de `build_runner` e o formato no disco é o mesmo que trafega na API.
- **Host das agências:** no emulador Android, `localhost` é o próprio emulador, e a máquina host
  aparece como `10.0.2.2`. Esse é o padrão em `config.dart`. Para dispositivo físico, o IP é passado
  na compilação: `flutter run --dart-define=AGENCIA_HOST=192.168.0.10`. As portas continuam
  `4145/4146/4147` (seção 0.1).
- **HTTP em texto claro:** as agências não têm TLS no ambiente da disciplina, e o Android bloqueia
  `http://` por padrão desde a API 28. Liberei o *cleartext* no `AndroidManifest.xml`
  (`android:usesCleartextTraffic="true"`). Isso só vale para desenvolvimento. Em produção o token JWT
  trafegaria em claro, então o certo seria HTTPS e remover essa liberação.

### Uso de ferramentas de IA (Sprint 3)

Como nos sprints anteriores, usei o Claude Code (Anthropic) como apoio para estruturar o código do
backend e do app, revisar e redigir este documento. As decisões de design (polling nas 3 agências,
tratamento manual de conflito, saque offline limitado ao saldo provisório) foram discutidas e
conferidas por mim. Executei e testei tudo o que está entregue (testes automatizados e a execução
registrada em `evidencias/sprint3/`) e consigo explicar e defender qualquer trecho.

---

### Visão geral da implementação no backend

Mesma linguagem dos sprints anteriores (Java 21 + Spring Boot). As portas continuam
`4145`/`4146`/`4147` (`4100 + OFFSET`, ver seção 0.1). Os scripts do roteiro em Node.js viraram
classes Java, como no Sprint 1 (seção 0.2):

| Roteiro (Node.js) | Minha implementação (Java) |
|---|---|
| `bullyService.js` | `BullyService` (`java.net.http.HttpClient`, `ScheduledExecutorService`) |
| rotas `/bully/*` em `routes.js` | rotas `/bully/eleicao`, `/bully/coordenador`, `/bully/lider` (públicas no `JwtFilter`) |
| `app.listen(...)` → `iniciarEleicao()` | eleição disparada no `ApplicationReadyEvent` |
| `historico-lideranca.js` | CLI `HistoricoLideranca` |
| `compararVetores.js` / `obterVetorAtual()` | `ComparadorVetorial` / `RelogioVetorial.vetor()` (já existiam desde o Sprint 2) |
| `POST /contas/:id/sincronizar` | `POST /contas/{id}/sincronizar` |

Parâmetros configuráveis (o roteiro usa constantes fixas):

| Propriedade | Variável de ambiente | Padrão |
|---|---|---|
| `iceibank.bully.timeout-ms` | `ICEIBANK_BULLY_TIMEOUT_MS` | 1000 ms (timeout por requisição) |
| `iceibank.bully.heartbeat-ms` | `ICEIBANK_HEARTBEAT_MS` | 3000 ms (intervalo do heartbeat) |

Dois detalhes da versão Java que fazem diferença nas respostas abaixo:

- `liderAtual` é um valor atômico, porque é lido e escrito por três threads diferentes: a do
  heartbeat, as threads HTTP que recebem `/bully/coordenador` e a da eleição.
- Quando uma agência recebe `/bully/eleicao` de alguém com ID menor, ela começa **a própria
  eleição de forma assíncrona** e devolve o `200` na hora. Se a eleição rodasse dentro da
  requisição, o `200` demoraria tanto quanto os timeouts dessa nova eleição, a agência de ID menor
  estouraria o próprio timeout esperando e concluiria, errado, que ninguém maior está vivo.

Todos os eventos do Bully (`ELEICAO_INICIADA`, `ELEICAO_RECEBIDA`, `TORNOU_LIDER`,
`LIDER_ANUNCIADO`, `LIDER_NAO_RESPONDEU`) entram no mesmo log `.jsonl` do Sprint 2, carimbados com
o relógio vetorial. As mensagens de eleição e de coordenador levam `vetorEnvio`, então o envio de
uma eleição e o recebimento dela ficam causalmente ligados no log, igual à transferência do Sprint 2.

---

## S3.1 Funcionalidade adicional — indicador de agências ativas (seção 2.1)

### O que faz

O painel de liderança, no topo da tela principal, mostra além da líder um **chip por agência**
(Agência 0, 1 e 2). A cada rodada de polling (3 s) o chip fica verde se aquela agência respondeu a
`GET /bully/lider` e vermelho se não respondeu (conexão recusada, timeout ou erro). Junto vem o
horário da última consulta.

Dois complementos usam a mesma rodada de polling:

- **Histórico de trocas de liderança:** as últimas 10 trocas observadas pelo app ("Agência 2 →
  Agência 1, 14:32:05"). Uma rodada sem líder no meio de uma eleição não gera entrada duplicada.
- **Timeout configurável no backend:** o intervalo do heartbeat e o timeout do Bully saem das
  variáveis de ambiente `ICEIBANK_HEARTBEAT_MS` e `ICEIBANK_BULLY_TIMEOUT_MS`, com valores padrão
  quando não definidas. Isso deixa ajustar o tempo de detecção de falha na demonstração sem
  recompilar.

### Por que escolhi esta

Porque ela torna o failover **visível como processo**, não só como resultado. Só com o nome da
líder, a pessoa vê "Agência 2" virar "Agência 1" e nada no meio. Com os chips, a sequência real
aparece na tela: primeiro o chip da Agência 2 fica vermelho, enquanto as outras ainda dizem que a
líder é a 2. Depois de alguns segundos o heartbeat de alguém expira, a eleição acontece e a líder
muda. Dá para enxergar o tempo de detecção de falha, que é justamente o que o heartbeat e o timeout
controlam.

Ela também ajudou na depuração: quando o painel não atualizava, os chips mostravam na hora se o
problema era a agência (vermelha) ou o app. E quase não custou nada, porque o polling já consulta as
3 agências em paralelo (pergunta 1 da Parte D). O estado de cada uma já vinha de graça em cada
rodada.

### Onde está

- App: `app_mobile/lib/lideranca/lideranca_service.dart` (`agenciasAtivas`, `historico`,
  `ultimaConsulta`) e `app_mobile/lib/lideranca/painel_lideranca.dart` (chips, aviso de divergência,
  lista do histórico)
- Testes do app: `app_mobile/test/` (polling com consulta fake: agência fora do ar, troca de líder,
  limite de 10 no histórico)
- Backend: leitura de `ICEIBANK_HEARTBEAT_MS` / `ICEIBANK_BULLY_TIMEOUT_MS` na configuração do Bully
- Evidência: `evidencias/sprint3/funcionalidade-adicional.png`

### Prova de funcionamento

Execução real em 15/11/2026, com os valores padrão (`ICEIBANK_HEARTBEAT_MS=3000`,
`ICEIBANK_BULLY_TIMEOUT_MS=1000`) e o app rodando como Flutter Web (ver S3.5):

- `evidencias/sprint3/app-lider-atual.png`: três chips verdes, estrela na Agência 2.
- Derrubei a Agência 2 às **21:40:23**. No backend, a Agência 1 registrou `LIDER_NAO_RESPONDEU`
  e `TORNOU_LIDER` às **21:40:26.41** (cerca de 3,4 s depois: um ciclo de heartbeat mais o
  timeout). O app registrou a troca **"Agência 2 → Agência 1" às 21:40:28**, uns 5 s após a
  queda, já que o polling de 3 s só percebe a mudança na volta seguinte
  (`evidencias/sprint3/app-failover.png`: chip da 2 vermelho, estrela na 1).
- Religuei a Agência 2 às **21:43:33**. Ela subiu, venceu a eleição (`TORNOU_LIDER` às 21:43:38)
  e o app registrou **"Agência 1 → Agência 2" às 21:43:40**
  (`evidencias/sprint3/funcionalidade-adicional.png`: três chips verdes e as 3 entradas do histórico).

---

## S3.2 Parte A — Mensagens de eleição e coordenador (seção 5.5)

**1. Por que o Bully elege o processo de maior ID, e não o de menor? O resultado seria diferente
(mas ainda correto) se a regra fosse invertida?**

Não existe nada de especial no "maior". O algoritmo só precisa de um critério que **todos os
processos apliquem da mesma forma** e que gere uma **ordem total** sem empates entre os
candidatos. Como cada agência tem um ID único e todas conhecem os IDs das outras (0, 1, 2 em
`ConfigAgencias`), qualquer agência consegue decidir sozinha, sem negociar, quem "ganha" de quem.
O que garante a correção é que todas concordam sobre um único vencedor, e não qual vencedor é.

Se a regra fosse invertida (mensagem de eleição para os IDs **menores**, vence o menor vivo), o
resultado mudaria: com as três no ar a líder seria a Agência 0, e não a 2. Mas o algoritmo
continuaria correto, com as mesmas propriedades: uma líder só, a mesma para todas, e reeleição
quando ela cai. O mesmo vale para qualquer ordem total, como menor carga ou maior uptime, desde que
todos calculem o mesmo valor (aí entra o problema de concordar sobre um valor que muda, e não é
mais o Bully simples).

O "maior ID" é só convenção do artigo original (Garcia-Molina, 1982). Num sistema real, a
"prioridade" costuma representar algo concreto, como a máquina mais forte ou a réplica mais
atualizada.

**2. Mais de uma agência declarou `TORNOU_LIDER` durante a subida. Bug ou comportamento esperado?**

É **comportamento esperado**. O algoritmo não promete uma única declaração, promete convergência.

Cada agência dispara a própria eleição no `ApplicationReadyEvent`, e as três sobem em terminais
diferentes, com alguns segundos de diferença (o Spring Boot leva um tempo para subir). A sequência
típica é esta:

1. A Agência 0 fica pronta primeiro e envia `/bully/eleicao` para a 1 e a 2. Nenhuma delas está
   no ar ainda: a conexão é recusada, ou o pedido estoura `iceibank.bully.timeout-ms`. A 0 conclui
   que ninguém maior está vivo e registra **`TORNOU_LIDER` com ela mesma como líder**.
2. A Agência 1 sobe. Se a 2 ainda não estiver pronta, acontece a mesma coisa:
   **`TORNOU_LIDER` da 1**, e a 0 recebe `LIDER_ANUNCIADO` com a 1 como líder.
3. A Agência 2 sobe, não tem ninguém acima dela, registra **`TORNOU_LIDER` da 2** e anuncia para
   as duas. A partir daí as três concordam.

Além disso, quando as agências sobem quase juntas, aparecem eleições sobrepostas: a 2 recebe
`ELEICAO_RECEBIDA` da 0 e da 1, inicia sua eleição a cada uma e pode se declarar líder (e
reanunciar) mais de uma vez. São disputas e timeouts normais de várias eleições concorrentes.
Nenhuma dessas declarações é contraditória no fim: cada `TORNOU_LIDER` mais novo vem de uma agência
de ID maior ou igual, e o último a valer é sempre o da agência de maior ID viva.

Seria um bug se o sistema **não** convergisse: duas agências vivas e alcançáveis terminando com
líderes diferentes, ou uma agência de ID menor sobrepondo a 2 depois que ela já estava no ar.

**O que aconteceu na execução real (15/11/2026, 21:32).** As três agências foram iniciadas pelo
mesmo script, com menos de 1 s entre elas. Por isso o cenário "0 vira líder, depois 1, depois 2"
não aconteceu: quando a 0 e a 1 iniciaram suas eleições, a 2 já respondia. A subida teve
**4 `TORNOU_LIDER`, todos da Agência 2**. Ela se declarou de novo a cada `ELEICAO_RECEBIDA`
(uma da 0 e duas da 1), e as outras receberam o mesmo `LIDER_ANUNCIADO {liderId=2}` quatro vezes.
Trecho do log (vetores reais, ordenado pela `horaParede`):

```text
00:32:37.800 agencia-1 ELEICAO_INICIADA  [0,1,0]
00:32:37.934 agencia-0 ELEICAO_INICIADA  [1,0,0]
00:32:37.970 agencia-2 ELEICAO_INICIADA  [0,0,1]
00:32:38.075 agencia-2 TORNOU_LIDER      [0,0,2]
00:32:38.294 agencia-2 ELEICAO_RECEBIDA  [0,2,4]   {de=1}
00:32:38.403 agencia-1 ELEICAO_RECEBIDA  [2,3,0]   {de=0}
00:32:38.467 agencia-2 ELEICAO_RECEBIDA  [4,5,6]   {de=0}
00:32:38.494 agencia-2 TORNOU_LIDER      [4,5,9]
00:32:38.508 agencia-2 TORNOU_LIDER      [4,5,13]
00:32:38.523 agencia-2 TORNOU_LIDER      [4,5,17]
00:32:38.532 agencia-1 LIDER_ANUNCIADO   [4,9,19]  {liderId=2}
```

(horário UTC no log, 21:32 no horário local. Print: `evidencias/sprint3/eleicao-inicial.png`, com as
três janelas das agências e as três respostas de `GET /bully/lider` = 2.)

**3. `/bully/eleicao` não tem autenticação. Isso é um problema de segurança real para este
projeto?**

**Hoje o impacto é limitado. No Sprint 4 o problema fica sério.** Não é "não", e explico os dois
lados.

As rotas `/bully/*` estão liberadas de propósito em `JwtFilter.rotaPublica(...)`: o token JWT
deste projeto é de **usuário** (claim `tipo = USUARIO`, seção 5.2), e uma agência que faz eleição
não é uma pessoa logada. Além disso, o Spring Boot escuta em todas as interfaces por padrão, então
qualquer máquina da mesma rede alcança as portas 4145 a 4147, e não só o `localhost`.

Ataques possíveis, sem precisar de credencial nenhuma:

- **Forçar eleições sem parar:** um laço de `POST /bully/eleicao` faz a agência eleger, mandar
  mensagens e escrever no log o tempo todo. É um ataque de **disponibilidade**: gasta CPU e rede,
  enche o `.jsonl` e mantém o sistema numa troca constante de líder.
- **Forjar a líder via `/bully/coordenador`:** a rota irmã, também sem autenticação, é mais grave.
  Basta enviar `{"liderId": 0}` com a Agência 2 viva, e as agências passam a acreditar que a 0 é a
  líder. O heartbeat **não corrige isso**, porque ele só verifica se a líder responde, e a 0
  responde. O Bully simples confia no que recebe e não confere se quem se anunciou tem mesmo o
  maior ID.
- **Envenenar o relógio vetorial:** as duas rotas aplicam `aoReceber(vetorEnvio)`. Um `vetorEnvio`
  como `[1000000000, 0, 0]` infla o vetor da agência de uma vez. Isso estraga a linha do tempo
  causal (Sprint 2) e, junto com a Parte E, faz **toda sincronização offline voltar 409**, porque
  o vetor atual deixa de bater com o vetor que qualquer app conhece.

Por que o impacto é limitado **hoje**: no Sprint 3 a líder não tem nenhum poder. Nenhuma operação
bancária depende dela, então uma líder falsa não move dinheiro. No Sprint 4 a líder passa a
**coordenar o 2PC**. Aí uma líder forjada decide commit ou abort de transferências, e eleições
forçadas no meio de uma transação deixam participantes bloqueados. O problema passa a ser de
integridade, e não só de disponibilidade.

Mitigações, na ordem em que eu adotaria:

1. **Validação barata, dentro do próprio algoritmo:** ao receber `/bully/coordenador` com `liderId`
   menor que o meu próprio ID, recusar e iniciar uma eleição. Isso não autentica ninguém, mas
   impede que um ID baixo derrube uma agência maior viva.
2. **Token de serviço entre agências:** a mesma ideia do token `SERVICO` do Sprint 1 (seção 5.2),
   um JWT curto com claim própria, aceito só nas rotas `/bully/*`. De preferência com **chave
   própria por agência** (assimétrica), como já apontei naquela seção como limitação, para a
   receptora saber *qual* agência assinou.
3. **mTLS entre as agências:** cada agência apresenta um certificado. A identidade vem do canal, e
   o `/bully/*` nem fica acessível para quem está de fora.
4. **Isolamento de rede:** as rotas internas expostas só na rede privada entre as agências, como
   foi feito com o broker no Sprint 2 (seção S2.4, pergunta 3).

---

## S3.3 Parte B — Heartbeat e failover automático (seção 6.4)

**1. Heartbeat muito curto (100 ms) ou muito longo (60 s)?**

O tempo para detectar uma falha é, no pior caso, **intervalo + timeout**. Com os padrões
(`ICEIBANK_HEARTBEAT_MS = 3000` e `ICEIBANK_BULLY_TIMEOUT_MS = 1000`), uma líder que cai é
percebida em até cerca de 4 s. O intervalo controla a velocidade da detecção, e o preço aparece de
dois lados.

**Intervalo de 100 ms:**

- **Carga:** cada agência que não é líder faz `GET /bully/lider` 10 vezes por segundo. Com 3
  agências isso não pesa nada. Mas a carga cai toda na líder e cresce com o número de agências
  (N−1 seguidoras × 10 req/s), e ela é justamente quem vai ter mais trabalho no Sprint 4.
- **Falsos positivos (o problema maior):** com uma janela tão curta, qualquer soluço vira "líder
  morta": uma pausa de GC, um pico de CPU, um pacote atrasado. Cada falso positivo gera
  `LIDER_NAO_RESPONDEU`, uma eleição e, às vezes, uma troca de líder que não era necessária. Isso
  dá **instabilidade de liderança** (*flapping*).
- **Efeito colateral no relógio:** cada um desses eventos chama `eventoLocal()` e avança o vetor
  da agência. Pela Parte E, isso aumenta os 409 de sincronização (ver S3.6, pergunta 2).
- **Verificações empilhadas:** com um intervalo (100 ms) menor que o timeout (1000 ms), um agendamento
  por taxa fixa começaria uma verificação antes de a anterior terminar. Eu usei
  `scheduleWithFixedDelay` num executor de thread única: o próximo heartbeat só começa 100 ms depois
  que o anterior termina, então nada empilha. Mesmo assim o intervalo deve ficar acima do timeout.

**Intervalo de 60 s:**

- A rede fica tranquila, mas a líder pode ficar morta por **até um minuto sem ninguém perceber**.
  No Sprint 3 isso só deixa o `/bully/lider` desatualizado. No Sprint 4, toda transferência que
  precisa da coordenadora falha ou fica pendurada esse tempo todo, o que num banco é indisponibilidade.

**Meio-termo:** um intervalo de alguns segundos, bem acima do RTT e do timeout (os 3 s padrão são
razoáveis para 3 agências numa rede local), e um contador de suspeita: só declarar a líder morta
depois de *k* falhas seguidas. Por isso deixei os dois valores configuráveis por variável de
ambiente, em vez da constante fixa do roteiro.

**2. A agência derrubada volta e "toma" a liderança de volta. Isso é desejável? Que problema
prático isso causa?**

Na maioria dos casos **não é desejável**. É a característica que dá nome ao algoritmo: o processo
de maior ID sempre se impõe (*bully*). Ao voltar, a Agência 2 dispara a eleição no
`ApplicationReadyEvent`, não tem ninguém acima e se declara líder, mesmo com a Agência 1 liderando
sem nenhum problema.

Na execução real, **voltou sim**, as duas vezes em que religuei a Agência 2. Trecho da segunda
volta (horário UTC no log, 21:43 local). A Agência 1 liderava sem problema algum e foi destituída
em ~60 ms:

```text
00:40:26.413 agencia-1 TORNOU_LIDER     [4,20,19]                 (failover: 2 caiu)
00:43:38.051 agencia-2 ELEICAO_INICIADA [0,0,1]                   (2 volta, relógio zerado)
00:43:38.110 agencia-2 TORNOU_LIDER     [0,0,2]
00:43:38.165 agencia-0 LIDER_ANUNCIADO  [23,21,19] {liderId=2}
00:43:38.195 agencia-1 LIDER_ANUNCIADO  [4,23,19]  {liderId=2}
```

Repare também no vetor `[0,0,1]`: a agência que volta perdeu o relógio (está em memória) e
recomeça do zero, enquanto as outras já estão em `[23,21,19]`.

Problemas práticos, pensando no Sprint 4, em que a líder coordena o 2PC:

- **Troca de coordenadora no meio de uma transação:** a Agência 1 está entre a fase de preparação
  e a de commit de uma transferência, com valores reservados nas participantes. A Agência 2 assume
  e **não sabe que essa transação existe**, porque o estado da coordenadora está na memória da 1.
  As participantes ficam com recursos bloqueados, esperando uma decisão que pode não chegar.
- **Responsabilidade dividida:** durante a transição, a 1 ainda pode mandar o commit da transação
  antiga enquanto a 2 já coordena novas transações. Com duas coordenadoras ao mesmo tempo, uma
  participante pode receber decisões das duas.
- **Instabilidade:** uma agência que cai e volta repetidamente provoca uma troca de líder a cada
  retorno. Cada troca custa eleição, mensagens e uma janela sem líder definida.
- **Ganho nenhum:** a líder antiga estava saudável. A troca só traz risco.

Mitigações:

1. **Não destituir uma líder saudável:** ao subir, a agência primeiro pergunta `GET /bully/lider`
   às outras. Se existe uma líder viva, ela a adota e só concorre quando o heartbeat detectar
   falha. A prioridade por ID passa a valer só para desempate numa eleição de verdade.
2. **Épocas/termos (como no Raft):** cada eleição incrementa um número de termo, e toda mensagem
   de coordenação leva esse termo. Mensagens de termo antigo são recusadas, então a coordenadora
   destituída não consegue mais decidir nada.
3. **Log da coordenadora persistido e compartilhado:** a nova líder consegue retomar ou abortar as
   transações em andamento, em vez de deixá-las órfãs (recuperação do 2PC).

**3. E se a rede estiver lenta (não fora do ar) e a resposta passar de 1 segundo?**

A agência conclui que a outra **morreu**, quando ela só estava lenta. O `HttpClient` do
`BullyService` interrompe a espera ao estourar `iceibank.bully.timeout-ms`, e uma resposta `200`
que chegue depois é simplesmente descartada. Consequências:

- **Falso `LIDER_NAO_RESPONDEU`:** o heartbeat não recebe resposta da líder a tempo, apaga
  `liderAtual` e inicia uma eleição desnecessária.
- **Duas líderes (*split-brain*):** na eleição, a Agência 1 manda `/bully/eleicao` para a 2, que
  está viva mas lenta. A resposta não chega em 1 s, e a 1 conclui que ninguém maior respondeu e
  registra `TORNOU_LIDER`. Enquanto isso, a 2 continua se considerando líder. Dependendo de quais
  anúncios de coordenador cheguem e de quais se percam, partes do sistema seguem líderes
  diferentes até a rede normalizar e uma nova eleição resolver. No Sprint 4 isso significa **duas
  coordenadoras de 2PC ao mesmo tempo**.
- **Instabilidade:** com a lentidão persistente, o ciclo se repete a cada heartbeat.

O ponto central é que, num sistema assíncrono, **não dá para distinguir com certeza um processo
lento de um processo morto**. Isso não é limitação da minha implementação. É um resultado teórico:
o FLP (Fischer, Lynch e Paterson, 1985) mostra que não existe consenso determinístico garantido
com uma única falha possível nesse modelo, e por isso todo detector de falhas baseado em timeout é
**imperfeito** (Chandra e Toueg). O timeout é um palpite: curto demais gera falsos positivos, longo
demais deixa a detecção lenta. É o mesmo trade-off da pergunta 1.

Mitigações:

- **Timeout maior, ou adaptativo**, calculado a partir do RTT observado (como no detector *phi
  accrual* do Cassandra/Akka), em vez de 1 s fixo. Por isso ele é configurável
  (`ICEIBANK_BULLY_TIMEOUT_MS`).
- **Retentativas e contador de suspeita:** só declarar falha depois de *k* timeouts seguidos.
- **Resposta assíncrona** no `/bully/eleicao`, que já implementei: o tempo de resposta não inclui
  a eleição de quem recebe, o que tira uma fonte de timeout falso.
- **Para evitar *split-brain* de verdade:** termos/épocas e **quórum de maioria**. Com 3 agências,
  uma líder só vale se 2 a reconhecerem, então dois lados da partição não conseguem ter líder ao
  mesmo tempo. É o que Raft e Paxos fazem, e o que o Bully não faz.

---

## S3.4 Parte C — Observando a eleição (seção 7.3)

O `HistoricoLideranca` lê todos os `.jsonl` de `data/`, filtra os cinco tipos de evento do Bully,
ordena por `horaParede` e imprime a linha do tempo de líderes. Acrescentei ao final duas contagens
diferentes, de propósito:

- **total de `TORNOU_LIDER`:** quantas vezes alguma agência se declarou líder;
- **trocas reais de liderança:** quantas vezes a líder mudou de fato, ou seja, `TORNOU_LIDER`
  consecutivos de agências **diferentes**. Uma agência que se reanuncia (como a 2, ao atender a
  eleição da 0 e depois a da 1) conta só na primeira.

**1. Quantas vezes a liderança mudou de mãos durante a tempestade da subida, antes de estabilizar?
Isso tem custo real, mesmo sem afetar o resultado final?**

Pelo algoritmo, com as agências subindo na ordem 0 → 1 → 2 e alguns segundos entre uma e outra, o
esperado são **até 3 declarações e 2 trocas reais** (0 → 1 → 2). Se as três subirem praticamente
juntas, pode haver uma troca só, ou nenhuma, mas com mais `TORNOU_LIDER` repetidos da 2.

**Na execução real** (`evidencias/sprint3/historico-lideranca.png`), as três subiram juntas, com
menos de 1 s de diferença. O `HistoricoLideranca` imprimiu **"Total: 5 eventos TORNOU_LIDER, 1
trocas reais de lider"**. Desses, **4 `TORNOU_LIDER` foram da subida, todos da Agência 2, com 0
trocas reais**: a primeira declaração define a líder e as outras três são reanúncios. O 5º
`TORNOU_LIDER` e a única troca real (2 → 1) vieram do failover provocado depois.

O tempo entre o primeiro `ELEICAO_INICIADA` (00:32:37.800, Agência 1) e o último `TORNOU_LIDER`
(00:32:38.523, Agência 2) foi de **~0,7 s**. Nesse intervalo houve 7 `ELEICAO_INICIADA`,
4 `ELEICAO_RECEBIDA`, 4 `TORNOU_LIDER` e 8 `LIDER_ANUNCIADO`, ou seja, 23 eventos registrados
para chegar a um resultado que uma mensagem só resolveria.

**Sim, tem custo**, mesmo com o resultado final certo:

- **Rede:** no Bully, uma eleição iniciada pela agência *i* manda mensagem para todas as de ID
  maior, cada uma que recebe inicia a própria eleição, e cada vencedora anuncia para todas. No pior
  caso isso dá **O(n²) mensagens**. Com 3 agências são poucas dezenas de requisições HTTP. Com
  dezenas de nós vira uma rajada.
- **Tempo:** cada tentativa de falar com uma agência que ainda não subiu custa até
  `iceibank.bully.timeout-ms` (ou a conexão recusada). Enquanto a tempestade não termina, **não
  existe líder acordada**: o `/bully/lider` devolve respostas diferentes em agências diferentes.
  No Sprint 4, qualquer operação que precise da coordenadora teria que esperar ou falhar nessa
  janela.
- **Log e relógio:** cada evento entra no `.jsonl` e avança o relógio vetorial da agência. Um
  vetor que avançou por causa de uma eleição é tratado como conflito na sincronização offline da
  Parte E, mesmo sem nenhuma mudança na conta.
- **Trabalho perdido:** as lideranças passageiras da 0 e da 1 não serviram para nada. No Sprint 4,
  qualquer coisa que elas começassem a coordenar nesse intervalo seria interrompida.

Uma observação sobre o script: ordenar por `horaParede` funciona aqui porque as três agências
rodam na mesma máquina e usam o mesmo relógio físico. Com agências em máquinas diferentes, o
desvio entre os relógios (seção 4.1) poderia inverter a ordem. O correto seria ordenar pela
relação causal do vetor, que as mensagens do Bully já carregam (`vetorEnvio`).

---

## S3.5 Parte D — App Flutter (seção 8.3)

> **Como as evidências do app foram geradas.** A máquina de entrega não tem Android SDK nem
> emulador, então o app foi compilado e executado como **Flutter Web** (Flutter 3.50 / Dart 3.14),
> o mesmo código de `app_mobile/lib`, sem nenhum ramo específico de plataforma:
> `flutter build web --dart-define=AGENCIA_HOST=localhost --dart-define=PORTA_BASE=5145`.
> Para simular o "modo avião", o app apontou para um **proxy TCP local** (portas 5145-5147 →
> 4145-4147). Desligar o proxy derruba a conexão com as três agências de uma vez, sem mexer no
> backend. O `PORTA_BASE` existe só para isso; sem ele, o app usa 4145 como antes. Detalhes em
> `evidencias/sprint3/COMO-GERAR.md`. No celular, o `connectivity_plus` também perceberia a volta
> da rede. Na web, quem detectou a reconexão foi a nova tentativa periódica de 5 s do `ServicoContas`.

### 1. Como o app decide com que frequência consultar `/bully/lider`? Que trade-off você considerou ao escolher esse intervalo (resposta rápida a mudanças vs. consumo de bateria/rede do celular)?

O intervalo é fixo em **3 segundos** (`intervaloLideranca` em `config.dart`). Um `Timer.periodic`
no `LiderancaService` dispara a rodada. Cada rodada consulta **as 3 agências em paralelo**
(`Future.wait`), com timeout de 3 s por requisição. Se uma rodada ainda está em andamento quando o
timer dispara de novo (rede lenta), a nova é ignorada. Assim as rodadas não se empilham nem são
aplicadas fora de ordem.

Para escolher o valor, pensei no tempo total que a pessoa espera para ver a nova líder: **tempo de
o backend detectar a falha (heartbeat + timeout do Bully) + tempo da eleição + intervalo do
polling**. Consultar a cada 500 ms não faz o app descobrir a nova líder antes de ela existir, porque
a parte dominante é a detecção no backend. Um intervalo bem menor que o heartbeat só gasta rede e
bateria repetindo a mesma resposta. Já um intervalo muito maior (30 s) faria a tela mostrar uma
líder morta por meio minuto, e a demonstração da Parte D não funcionaria. 3 s fica na mesma ordem de
grandeza do heartbeat: a troca aparece "em alguns segundos", como o roteiro pede.

Sobre custo: são 3 requisições GET minúsculas a cada 3 s, só **enquanto a tela está aberta**. O
serviço é iniciado com a tela principal e parado no `dispose`, então não existe polling em segundo
plano. Para um app real eu acrescentaria: pausar o timer quando o app vai para background
(`AppLifecycleState.paused`), aumentar o intervalo (backoff) quando nenhuma agência responde, e,
melhor ainda, trocar o polling por push (WebSocket/SSE), em que a agência avisa quando a líder muda.
Para o escopo do sprint, polling simples era o requisito, e o trade-off escolhido prioriza a
visibilidade da troca.

Consultar as 3 em vez de uma de cada vez com fallback triplica as requisições, mas tem duas
vantagens. A latência da rodada não aumenta quando uma agência está fora (não espero 3 s de timeout
para só então tentar a próxima). E cada rodada já informa quem está ativo, o que alimenta o
indicador da funcionalidade adicional.

### 2. Se, num momento de transição, você consultasse duas agências diferentes e elas respondessem líderes diferentes (uma ainda não sabe da nova eleição), como seu app se comporta? Isso é um problema real, ou se resolve sozinho na próxima consulta?

Como o app consulta as 3 agências em toda rodada, esse caso aparece de forma explícita. O
`LiderancaService` faz:

1. **Contagem de votos:** cada agência que respondeu "vota" na líder que ela conhece. A líder exibida
   é a **mais votada entre as que responderam**.
2. **Desempate:** prefiro o candidato que **está respondendo** nesta rodada (uma líder que não
   responde provavelmente caiu) e, se ainda empatar, o de **maior id**, a mesma regra do Bully.
3. **Sinalização:** se houve mais de uma líder nas respostas, a flag `divergencia` liga e o painel
   mostra um aviso ("agências discordam sobre a líder, eleição possivelmente em andamento"). Não
   escondo a inconsistência atrás de um valor só.

Exemplo: a Agência 2 (líder) cai. A Agência 0 detecta primeiro, faz a eleição e passa a dizer "líder
= 1". A Agência 1 diz "líder = 1" e ninguém mais diz 2, porque a 2 não responde. O app já mostra
1. Se a eleição ainda não terminou e a 0 diz "2" enquanto a 1 diz "1", o empate de votos é resolvido
a favor da 1, que está ativa, e o aviso de divergência aparece.

**É um problema real, mas transitório**, e neste sprint não tem consequência. A divergência
reflete o estado verdadeiro do sistema distribuído: durante a eleição, as agências de fato ainda não
concordam. O Bully termina com o anúncio de coordenador para todas, então na rodada seguinte (3 s
depois) as respostas convergem, o aviso some e o histórico registra uma única troca. Como a líder é
só informativa, mostrar a errada por alguns segundos não faz nenhuma operação dar errado.

No Sprint 4 isso muda: se a líder coordenar o 2PC, um cliente ou uma agência que fala com uma "ex-
líder" pode iniciar uma transação com quem não tem mais autoridade. Aí o voto do app não basta. O
backend precisa de um número de época/mandato (*term*) no anúncio de coordenador para recusar
ordens de uma líder antiga, e não depender de o cliente adivinhar certo.

### 3. Compare a arquitetura do app Flutter com a do frontend web do Sprint 1: o que foi reaproveitável (conceitos, não necessariamente código) e o que precisou ser pensado de forma diferente por ser uma plataforma mobile?

**Reaproveitado (conceitos):**

| Frontend web (Sprint 1) | App Flutter (Sprint 3) |
|---|---|
| `model.js` com `chamar()`: **um único ponto** de acesso à API, que põe o `Bearer` | `ApiClient`: mesmo papel, e nenhuma tela monta requisição |
| `agenciaResponsavel(id) = id % 3` para rotear | o mesmo cálculo em `config.dart`, e o `ApiClient` envia `/contas/{id}` para a agência `id % 3` |
| `ErroDaApi` com o status preservado | `ApiException` (status + mensagem do campo `erro`) |
| 401 → limpa sessão → volta ao login com explicação | 401 → `aoExpirarSessao` → volta ao login com a mensagem |
| separação M / V / C, de mão única | serviços (`ChangeNotifier`) ≈ Model; widgets ≈ View + Controller |

O contrato da API não mudou. O app fala exatamente as mesmas rotas, com o mesmo JSON e o mesmo JWT.
Isso confirma que a decisão do Sprint 1 de deixar o backend como única fonte da regra de negócio
valeu a pena.

**Pensado de forma diferente por ser mobile:**

1. **Rede é exceção, não regra.** No web, falha de rede vira mensagem de erro e pronto. No celular é
   o caso normal (elevador, metrô, modo avião). Por isso o `ApiClient` distingue
   `SemConexaoException` (a agência não respondeu) de `ApiException` (respondeu com erro). Só a
   primeira faz a operação ir para a fila. Saldo insuficiente nunca é enfileirado.
2. **Estado persistente além do token.** O web guardava só sessão no `localStorage`. O app guarda
   conta, `vetorAtual` e a fila de pendentes no Hive. A tela precisa funcionar sem servidor.
3. **Estado reativo em vez de manipulação de DOM.** No web o Controller chamava funções da View
   para pintar o resultado. No Flutter, a UI é função do estado: o serviço chama `notifyListeners()`
   e os widgets se redesenham sozinhos. Isso é necessário porque o estado agora muda sem ação do
   usuário (polling, conectividade voltando, sincronização em segundo plano).
4. **Endereço do servidor.** No navegador, `localhost` era a máquina. No emulador, é `10.0.2.2`, e
   no aparelho físico é o IP da rede (`--dart-define`). Mais o bloqueio de HTTP em claro do Android.
   Problemas parecidos com a porta 4045 do Sprint 1: a plataforma impondo regras sobre a rede.
5. **Timeout curto e explícito.** O `fetch` do navegador espera muito tempo. No app, 3 s, para a
   tela não ficar travada e o modo offline entrar rápido.
6. **Ciclo de vida.** Timers e listeners de conectividade precisam ser cancelados no `dispose`. No
   web, fechar a aba resolvia tudo.
7. **O token** fica na memória do serviço/armazenamento do app, não em `localStorage`. O risco de
   XSS discutido no Sprint 1 (6.1) não existe aqui da mesma forma. Para produção, o lugar certo seria
   o armazenamento seguro do sistema (Keystore/Keychain, via `flutter_secure_storage`).

---

## S3.6 Parte E — Sincronização offline-first no backend (seção 9.6)

**Como implementei:** `GET /contas/{id}`, o depósito e o saque passaram a devolver `vetorAtual`
(o `RelogioVetorial.vetor()`, que lê sem incrementar). `POST /contas/{id}/sincronizar` recebe
`tipo`, `valor` e `vetorConhecido` e compara com `ComparadorVetorial.comparar(vetorConhecido,
vetorAtual)`. Qualquer resultado diferente de `IGUAIS` devolve **409** com `contaAtual`,
`vetorAtual` e `relacao`. A comparação e a aplicação da operação acontecem de forma **atômica,
dentro do monitor do relógio**. Sem isso, duas sincronizações com o mesmo `vetorConhecido`
poderiam passar juntas pelo "IGUAIS" antes que qualquer uma avançasse o vetor (*check-then-act*),
e as duas operações seriam aplicadas sobre um estado que nenhuma delas viu.

Execução real (`evidencias/sprint3/conflito-sincronizacao.png`, 21:37, Agência 0, conta 6 com
saldo 100). O "app" leu a conta e guardou `vetorConhecido = [13,14,19]`. Em seguida, outro
cliente depositou 25 direto na agência, e o vetor foi para `[14,14,19]`. A sincronização do saque
de 30 com o vetor antigo voltou:

```json
HTTP 409
{"erro":"Conflito detectado: o estado da conta mudou desde a ultima sincronizacao do app.",
 "vetorConhecido":[13,14,19],"vetorAtual":[14,14,19],"relacao":"ANTES",
 "contaAtual":{"id":6,"nomeAluno":"Ana","saldo":125}}
```

Reenviando o mesmo saque com `vetorConhecido = [14,14,19]` (o `vetorAtual` devolvido), a resposta
foi **HTTP 200, saldo 95, vetor `[15,14,19]`**.

**1. Por que comparar o vetor inteiro, e não só verificar se o saldo mudou? O que o vetor detecta
que a comparação de saldo não detecta?**

Porque **o saldo é o resultado das operações, e não a história delas**. Dois estados com o mesmo
saldo podem ter histórias completamente diferentes. O caso clássico é o problema ABA: o app lê
saldo 100 e fica offline. Enquanto isso, entra um depósito de 50 e sai um saque de 50. O saldo
volta a 100. Comparando só o saldo, o servidor concluiria que nada mudou e aplicaria a operação
pendente como se a pessoa tivesse visto o estado atual, o que é falso: aconteceram duas operações
que ela não viu.

O vetor conta **eventos**. Cada depósito ou saque chama `eventoLocal()` e incrementa a posição da
agência, então o vetor nunca volta ao valor anterior. Ele detecta:

- **qualquer evento entre a leitura e a sincronização**, mesmo que os efeitos se anulem;
- **eventos causados por outras agências** (créditos remotos via RabbitMQ, que entram com
  `aoReceber`), que também avançam o vetor;
- **o tipo de divergência**, através da `relacao`. `ANTES` é o caso normal: o app ficou para trás.
  `DEPOIS` ou `CONCORRENTES` indicam algo anormal. Um exemplo que acontece de fato neste projeto:
  o estado fica só em memória, então uma agência que **reinicia** volta com o vetor zerado, e o
  vetor que o app guardou passa a estar "depois" do vetor da agência. A comparação de saldo não
  perceberia isso. O vetor percebe e recusa.

Ou seja: a pergunta certa não é "o saldo é o mesmo?", e sim "a pessoa usuária decidiu com base no
estado atual?". Só a história causal responde isso.

**2. Qualquer mudança no vetor da agência, mesmo em outra conta, vira conflito. Por que existe essa
simplificação, e o que seria necessário para detectar por conta?**

A simplificação existe porque **o relógio vetorial é por agência, não por conta**. O
`RelogioVetorial` tem uma posição por agência (`new int[numeroAgencias]`) e conta todos os eventos
daquele processo. Ele foi feito no Sprint 2 para ordenar **eventos entre processos**, e não para
versionar dados. Uma operação na conta 3 e uma na conta 6 (as duas na Agência 0) incrementam a
mesma posição `vetor[0]`. Vendo só o vetor, não dá para saber qual conta mudou. Há ainda os
eventos que nem são de conta: os do próprio Bully (`ELEICAO_RECEBIDA`, `LIDER_NAO_RESPONDEU`...)
também avançam o vetor. Assim, um failover ou o heartbeat detectando uma falha basta para gerar
409 numa sincronização sem conflito nenhum.

O resultado são **falsos conflitos**. A detecção continua **segura**, porque nunca deixa passar um
conflito de verdade, mas é **pessimista**. Numa agência movimentada, quase toda sincronização
voltaria 409.

Para ficar preciso, por conta:

- **Versão por conta (concorrência otimista):** cada `Conta` ganha um contador `versao`,
  incrementado a cada operação **naquela conta**. O app guarda a versão lida e manda junto. O
  servidor só aplica se `versaoConhecida == conta.versao`, o mesmo princípio de `ETag` /
  `If-Match` no HTTP e de `@Version` no JPA. Como cada conta vive numa só agência (`id % 3`), um
  contador escalar basta.
- **Vetor por conta (version vector por objeto):** se a mesma conta pudesse ser alterada por mais
  de um processo, como várias réplicas ou vários dispositivos offline, cada conta precisaria do
  próprio vetor, à maneira do Dynamo/Riak. Isso permite distinguir "o app está atrasado" de "houve
  escrita concorrente de verdade".
- **Semântica da operação:** depósitos são **comutativos** (somar 50 antes ou depois de outro
  depósito dá no mesmo), então poderiam ser aplicados mesmo com uma versão desatualizada. Um saque
  não é comutativo, porque depende de existir saldo. Uma detecção mais refinada recusaria só as
  operações cuja validade depende do estado que mudou.

**3. Com 409, o app deveria descartar a operação, tentar de novo automaticamente, ou perguntar à
pessoa?**

**Perguntar à pessoa.** Essa é a escolha de design que levo para a Parte F.

- **Repetir automaticamente** com o `vetorAtual` recebido seria aplicar a operação às cegas, que é
  exatamente o que o 409 existe para impedir. Se o app reenvia sozinho com o vetor novo, a
  detecção de conflito vira só um atraso, e não proteção nenhuma. Num banco a intenção depende do
  saldo: quem decidiu sacar 100 vendo saldo 150 talvez não queira mais sacar depois de descobrir
  que entrou um débito e o saldo agora é 60.
- **Descartar em silêncio** perde a intenção da pessoa. Ela acreditou que fez um depósito ou um
  saque, e a operação some sem aviso. É o pior resultado para a confiança no app.
- **Perguntar** é a única opção que respeita as duas coisas: o servidor tem o estado verdadeiro, e
  só a pessoa sabe se a intenção ainda vale.

Como o app trata isso, usando o que o backend devolve no 409:

1. A operação pendente fica marcada como **`CONFLITO`**, e não como falha.
2. O app mostra o **saldo real** que veio em `contaAtual` ao lado da operação pendente ("você
   tentou sacar R$ 100,00; o saldo atual é R$ 60,00").
3. A pessoa escolhe:
   - **Reaplicar:** o app reenvia com o `vetorAtual` do 409. O servidor revalida tudo, inclusive
     o saldo, e, se o estado mudou de novo nesse intervalo, devolve outro 409.
   - **Descartar:** a operação sai da fila, com registro no histórico local.

A retentativa automática só faz sentido para falhas de **transporte** (timeout, sem conexão), e
não para conflito de **estado**. Mesmo nesse caso ela é segura só se a operação for idempotente, a
mesma ideia da `Idempotency-Key` do Sprint 1 (seção 1).

## S3.7 Parte F — App Flutter offline-first (seção 10.3)

### 1. Qual biblioteca de armazenamento local você escolheu, e por quê? Quais alternativas você considerou?

Escolhi o **Hive** (`hive` + `hive_flutter`).

O que o app precisa guardar é pouco e tem forma de documento: por conta, `{conta, vetorAtual,
atualizadoEm}`; e uma lista de `OperacaoPendente`. Não há consulta relacional, junção nem filtro
complexo. Os acessos são "pegar a conta X" e "listar a fila". Um armazenamento chave-valor resolve
isso diretamente. O Hive é puro Dart (sem dependência nativa para compilar), rápido, e a API é
`box.put(chave, mapa)` / `box.get(chave)`.

Usei caixas guardando **mapas JSON** em vez de `TypeAdapter` gerado. Os modelos já têm
`toJson`/`fromJson` por causa da API, então reaproveitei. Não precisei de `build_runner` nem de
arquivos `.g.dart`, e o que está no disco tem o mesmo formato do que veio do backend, o que ajuda a
depurar.

Alternativas consideradas:

| Opção | Por que não |
|---|---|
| `sqflite` | SQL e esquema/migração para dados sem relação nenhuma; mais código para o mesmo resultado |
| `drift` | ótimo (tipado, reativo), mas exige geração de código e é pensado para modelos relacionais maiores |
| `shared_preferences` | serve para preferências pequenas; guardar uma fila serializada inteira numa string a cada mudança é frágil |
| `isar` | parecido com o Hive, mas com dependência nativa e geração de código |

A ressalva honesta: o Hive não tem transações entre caixas. Atualizar o cache da conta e remover a
operação da fila são duas escritas separadas. Por isso o `ServicoContas` remove a operação da fila
**antes** de gravar a conta atualizada (`sincronizarPendentes`): se o app morrer entre as duas, a
operação (que a agência já aplicou) não está mais na fila e não pode ser reenviada; o que sobra é
só um cache de saldo/vetor antigo, corrigido na próxima consulta online. Na ordem inversa, a
operação continuaria na fila e seria reenviada (o vetor desatualizado daria 409, mas a pessoa veria
um conflito falso). Não há risco de aplicar duas vezes em silêncio. Para um app de produção
eu consideraria o Drift justamente para ter transação.

### 2. Na tarefa 5, o que exatamente aconteceu na tela do app quando a sincronização foi recusada? Descreva o comportamento que você implementou e explique por que escolheu essa abordagem em vez de outra.

**O que aconteceu na execução real (15/11/2026, app como Flutter Web, conta 6 da Agência 0).**
O conflito apareceu sem que eu precisasse provocá-lo, e de um jeito que mostra bem a simplificação
discutida na S3.6, pergunta 2:

1. Por volta das 21:40 consultei a conta 6 (saldo **R$ 95,00**). O app guardou o vetor da Agência 0.
2. Às 21:40:23 derrubei a líder (Agência 2) para o print de failover. A Agência 0 registrou
   `LIDER_ANUNCIADO {liderId=1}`, e esse evento **avançou o vetor da Agência 0** para `[18,21,19]`.
   Nenhuma operação tocou a conta 6.
3. Logo depois desliguei o proxy (modo avião simulado) e depositei **R$ 20,00**. A tela mostrou
   "Offline - mostrando dados salvos em 21:40", saldo confirmado R$ 95,00 e **saldo provisório
   R$ 115,00** com o selo "Pendente de sincronização · 1 operação aguardando"
   (mesma tela de `evidencias/sprint3/app-offline.png`).
4. Às 21:41:13 religuei o proxy. Em ~1 s o app sincronizou sozinho e recebeu **409**
   (`SINCRONIZACAO_CONFLITO`, relação `ANTES`). O card vermelho apareceu no topo:
   *"Conflito na sincronização — conta 6. A operação offline de R$ 20,00 (depósito) não foi
   aplicada: o saldo da conta mudou enquanto você estava offline. Saldo real: R$ 95,00."*. O saldo
   provisório sumiu e ficou só o confirmado, R$ 95,00 (`evidencias/sprint3/app-conflito.png`).
5. Toquei em **"Reaplicar com saldo atual"**. As duas primeiras tentativas deram 409 de novo, porque
   eu tinha feito login por fora com `curl` para conferir o saldo, e o evento `LOGIN` também avança
   o vetor da agência. Na terceira, sem interferência, a agência aplicou (`SINCRONIZACAO_OFFLINE`,
   novo saldo **R$ 115,00**, vetor `[21,21,19]`) e o card sumiu (`evidencias/sprint3/app-reaplicado.png`).

Ou seja: o saldo da conta 6 **não mudou** entre a leitura e a sincronização, mas o vetor da agência
mudou por causa de eventos do Bully e de logins, e o app tratou isso como conflito. Pela regra da
Parte E isso está correto (vetor diferente ⇒ 409), mas, do ponto de vista da pessoa usuária, é um
**falso positivo**. O texto do card ("o saldo da conta mudou") também fica impreciso nesse caso.
Com vetor por conta (S3.6, pergunta 2), esse 409 não aconteceria.

Depois, num ciclo limpo (sem failover nem login no meio), o fluxo feliz funcionou como esperado:
depósito offline de R$ 10,00 com saldo provisório R$ 125,00 (`app-offline.png`), proxy religado
às 21:43:12, e às 21:43:14 a agência registrou `SINCRONIZACAO_OFFLINE` com novo saldo R$ 125,00.
O app mostrou o saldo confirmado sem nenhuma ação manual (`app-sincronizacao.png`).

Comportamento implementado:

1. Offline, faço um depósito. Ele entra na fila como `PENDENTE`, com o `vetorConhecido` que estava
   em cache. O cartão de saldo mostra o **saldo provisório** (confirmado + pendentes) com o selo
   "pendente".
2. Por fora, o saldo da mesma conta é alterado, e o vetor da agência avança.
3. A conectividade volta (`connectivity_plus`, ou a nova tentativa periódica de 5 s) e o
   `ServicoContas` sincroniza sozinho: `POST /contas/{id}/sincronizar` com o vetor antigo.
4. O backend responde **409** com `contaAtual` e `vetorAtual`. O app:
   - marca a operação como **`CONFLITO`** e ela **sai do cálculo do saldo provisório**;
   - atualiza o cache com o **saldo real** de `contaAtual` e o novo vetor;
   - mostra um **card de destaque** no topo da tela principal: a operação que não pôde ser aplicada
     ("Depósito de R$ X, feito offline às hh:mm"), o aviso de que a conta mudou enquanto o app
     estava desconectado, e o **saldo real atual**;
   - oferece dois botões: **"Reaplicar com saldo atual"**, que envia a mesma operação de novo com o
     `vetorAtual` recebido no 409, e **"Descartar"**, que remove a operação da fila.

Uma operação em conflito **nunca é reenviada automaticamente**. As outras pendentes da mesma conta
seguem o fluxo normal.

**Por que perguntar à pessoa usuária** (a resposta que a Parte E pedia para justificar aqui):

- **Descartar automaticamente** perderia uma intenção da pessoa sem ela saber. Num banco, um
  depósito registrado que "some" é o pior resultado possível para a confiança.
- **Reaplicar automaticamente** com o vetor novo seria apenas contornar a detecção. O 409 existe
  para dizer "o mundo mudou desde que você decidiu", e reenviar sem perguntar ignora esse aviso.
  No depósito isso pareceria inofensivo. No **saque** não é: a pessoa decidiu sacar R$ 50 vendo
  saldo R$ 100. Se nesse meio tempo outra operação levou o saldo para R$ 60, talvez ela não queira
  mais sacar. Para não ter regra diferente por tipo, tratei os dois do mesmo jeito.
- **Perguntar** custa um toque, mas a decisão fica com quem tem o contexto, e ela decide **vendo o
  saldo real**, não o provisório. Isso cumpre o mínimo do roteiro (avisar e mostrar o estado real) e
  oferece a nova tentativa como ação explícita.

Um detalhe que precisei tratar: como o vetor é **por agência** e não por conta, uma sincronização
bem-sucedida avança o vetor da agência. Se houvesse duas pendentes na mesma agência, a segunda
sempre daria 409 por causa da primeira, um conflito causado pelo próprio app. Por isso, depois de
cada sucesso, o vetor devolvido é propagado para as outras pendentes **da mesma agência**. Assim, só
mudanças feitas por fora geram conflito.

### 3. UI otimista significa mostrar o resultado esperado antes de ter certeza de que ele vai acontecer. Que risco isso traz para um aplicativo bancário, especificamente? Como seu design tenta reduzir esse risco?

Em app de rede social, curtida otimista que falha é irrelevante. Num banco, o número na tela é
**usado para tomar decisões com dinheiro real**. Os riscos específicos:

1. **A pessoa acreditar que o dinheiro já se moveu.** Ela vê o saldo subir depois de um "depósito"
   offline, conta com esse valor, e a sincronização é recusada horas depois. Ou ela fecha o app
   achando que pagou algo que nunca chegou à agência.
2. **Gasto duplo offline.** Sem conexão, o app não sabe o que aconteceu na conta. A pessoa pode sacar
   pelo app offline e, ao mesmo tempo, pelo caixa ou pelo web. Ou fazer vários saques offline que,
   somados, passam do saldo. O saldo otimista dá a sensação de que isso é permitido.
3. **Divergência silenciosa.** Se um conflito fosse resolvido sozinho, o saldo exibido e o real
   poderiam se distanciar sem ninguém perceber.

Como o design reduz cada um:

- **Rótulo explícito de provisório.** O saldo com pendentes aparece como "saldo provisório", com o
  selo "pendente" e a lista das operações aguardando sincronização. Com banner offline, o cartão
  também mostra **quando** o dado confirmado foi obtido ("dados de hh:mm"). A tela nunca apresenta
  valor não confirmado como se fosse confirmado.
- **Saque offline limitado ao saldo provisório.** O app recusa localmente um saque maior que
  confirmado + pendentes. Isso não elimina o gasto duplo (o app não enxerga operações feitas por
  outros canais), mas impede que o próprio app empilhe saques além do que ele sabe que existe. O que
  sobra é barrado pelo backend: saldo insuficiente na hora de aplicar → `RECUSADA`, e o vetor
  divergente → `CONFLITO`.
- **Transferência e criação de conta só online.** Transferência entre agências envolve outra conta
  e a mensageria do Sprint 2, e o endpoint de sincronização cobre só depósito e saque. Enfileirar
  transferência seria prometer algo que o backend não sabe validar. Sem conexão, os botões dizem
  que a operação exige conexão.
- **Conflito nunca aplicado sozinho.** O 409 sempre vira decisão da pessoa (pergunta anterior), e a
  operação em conflito deixa de contar no saldo provisório.
- **Saldo real sempre à vista.** Em qualquer conflito ou recusa, o card mostra o saldo vindo do
  backend (`contaAtual`), e toda leitura online substitui o cache. A fonte da verdade continua
  sendo a agência, e o app só guarda uma intenção até ela confirmar.

O limite que reconheço: num banco de verdade, saque offline simplesmente não existiria (ou teria um
teto pequeno garantido pelo banco, como em cartões offline). Aqui ele existe porque o sprint pede, e
o design tenta deixar o risco visível, não fingir que ele não existe.

