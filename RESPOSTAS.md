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
