# Como gerar as evidências do Sprint 3

Mesma convenção dos sprints anteriores: todo print precisa mostrar uma saída de `Get-Date`
visível em algum terminal (nos prints do app, deixe um terminal com `Get-Date` ao lado do
emulador).

Arquivos esperados nesta pasta:

| Arquivo | Seção do roteiro |
|---|---|
| `eleicao-inicial.png` | 5.4 - as 3 agências convergindo para a mesma líder |
| `failover.png` | 6.3 - líder derrubada e reeleição automática |
| `historico-lideranca.png` | 7.2 - saída do `HistoricoLideranca` |
| `app-lider-atual.png` | 8.2 - app mostrando a líder atual |
| `app-failover.png` | 8.2 - painel do app atualizando sozinho após a queda |
| `conflito-sincronizacao.png` | 9.5 - backend recusando sincronização desatualizada (409) |
| `app-offline.png` | 10.2 - operação offline aparecendo como pendente |
| `app-sincronizacao.png` | 10.2 - app sincronizando sozinho ao reconectar |
| `funcionalidade-adicional.png` | 2.1 - indicador de agências ativas + histórico de líderes no app |

---

## 0. Preparação

Compile uma vez e deixe o broker configurado (igual ao Sprint 2):

```powershell
cd 01-backend-agencias
mvn -q clean package -DskipTests
$env:RABBITMQ_URL="amqps://usuario:senha@host.cloudamqp.com/vhost"   # ou amqp://guest:guest@localhost:5672
```

> `$env:` vale só para o terminal onde foi definido: repita o `RABBITMQ_URL` nos 3 terminais das
> agências.

Para que o histórico de lideranças fique limpo, apague os logs antigos **antes** de subir as
agências (eles ficam fora do Git):

```powershell
Remove-Item agencia\data\*.jsonl -ErrorAction SilentlyContinue
```

---

## 1. `eleicao-inicial.png`

Abra 3 terminais e suba as agências **em sequência rápida** (poucos segundos entre uma e outra),
para observar a "tempestade" de eleições:

```powershell
# Terminal 1
cd 01-backend-agencias; $env:AGENCIA_ID=0; java -jar target\iceibank-agencia-2.0.0.jar
# Terminal 2
cd 01-backend-agencias; $env:AGENCIA_ID=1; java -jar target\iceibank-agencia-2.0.0.jar
# Terminal 3
cd 01-backend-agencias; $env:AGENCIA_ID=2; java -jar target\iceibank-agencia-2.0.0.jar
```

(`mvn spring-boot:run` também funciona, só é mais barulhento.)

Espere uns 5 segundos e, num quarto terminal (o de testes):

```powershell
Get-Date
4145..4147 | ForEach-Object { Invoke-RestMethod -Uri "http://localhost:$_/bully/lider" | ConvertTo-Json -Compress }
```

Saída esperada: as 3 linhas com `"liderAtual":2`.

O print deve mostrar o terminal de testes **e** pelo menos um terminal de agência com as linhas
`ELEICAO_INICIADA`, `ELEICAO_RECEBIDA`, `TORNOU_LIDER` / `LIDER_ANUNCIADO` (é normal mais de uma
agência declarar `TORNOU_LIDER` durante a subida - ver Parte A em `04-documentacao/RESPOSTAS.md`).

## 2. `failover.png`

Com as 3 no ar e a líder sendo a Agência 2:

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4147/bully/lider" | ConvertTo-Json -Compress
```

Agora feche a Agência 2 (Ctrl+C no Terminal 3). Espere ~5 segundos (heartbeat de 3 s + timeout de
1 s) e consulte as que sobraram:

```powershell
Get-Date
4145..4147 | ForEach-Object {
  try { Invoke-RestMethod -Uri "http://localhost:$_/bully/lider" -TimeoutSec 2 | ConvertTo-Json -Compress }
  catch { "porta $_ -> fora do ar" }
}
```

Esperado: 4145 e 4146 com `"liderAtual":1`; 4147 fora do ar. Capture também os terminais das
Agências 0/1 com `LIDER_NAO_RESPONDEU` seguido de nova eleição e `TORNOU_LIDER` (Agência 1).

Depois suba a Agência 2 de novo e repita a consulta: ela inicia sua própria eleição e **toma a
liderança de volta** (`"liderAtual":2` nas três). Esse segundo momento enriquece o histórico do
passo 3.

> Para um failover mais rápido na demonstração, suba as agências com
> `$env:ICEIBANK_HEARTBEAT_MS=1000` (o padrão é 3000).

## 3. `historico-lideranca.png`

Depois dos passos 1 e 2 (com as agências no ar ou não - o script só lê os `.jsonl`):

```powershell
Get-Date
cd 01-backend-agencias
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.ferramentas.HistoricoLideranca"
```

Capture as duas seções: o histórico de eventos de eleição e a **linha do tempo de líderes**,
que deve mostrar líder inicial (2) -> queda -> nova líder (1) -> retorno da 2.

## 4. `app-lider-atual.png`

Com as 3 agências no ar, rode o app (passo a passo em `03-app-mobile-flutter/README.md`):

```powershell
cd 03-app-mobile-flutter
flutter run
```

Faça login (`ana` / `senha123`) e abra o painel de liderança. O print mostra o emulador com
**"Líder atual: Agência 2"** e um terminal com `Get-Date`.

## 5. `app-failover.png`

Com o app aberto no painel, **sem tocar na tela**:

1. Print "antes": painel mostrando Agência 2 como líder, as 3 agências ativas.
2. Ctrl+C na Agência 2. Rode `Get-Date` no terminal de testes.
3. Espere alguns segundos (o app consulta as 3 agências a cada 3 s e o backend precisa do
   heartbeat para reeleger).
4. Print "depois": painel mostrando **Agência 1** como líder e a Agência 2 marcada como inativa.

Junte os dois prints num só arquivo (lado a lado) ou capture o "depois" com o histórico de
líderes do app mostrando a troca 2 -> 1.

## 6. `conflito-sincronizacao.png`

Terminal de testes, com as 3 agências no ar. A conta 3 pertence à Agência 0 (`3 % 3 = 0`).

```powershell
Get-Date
$login = Invoke-RestMethod -Uri "http://localhost:4145/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"ana","senha":"senha123"}'
$h = @{ Authorization = "Bearer $($login.token)" }

# 1) cria a conta 3 na Agencia 0
Invoke-RestMethod -Uri "http://localhost:4145/contas" -Method Post -Headers $h -ContentType "application/json" -Body '{"id":3,"nomeAluno":"Carla","saldoInicial":100}' | ConvertTo-Json -Compress

# 2) "app" le a conta e guarda o vetor (vetorConhecido)
$leitura = Invoke-RestMethod -Uri "http://localhost:4145/contas/3" -Headers $h
$vetorAntigo = $leitura.vetorAtual
"vetor guardado pelo app: [$($vetorAntigo -join ',')]"

# 3) mudanca "enquanto o app estava offline": deposito direto
Invoke-RestMethod -Uri "http://localhost:4145/contas/3/depositar" -Method Post -Headers $h -ContentType "application/json" -Body '{"valor":50}' | ConvertTo-Json -Compress

# 4) sincroniza uma operacao pendente usando o vetor ANTIGO -> 409
$corpo = @{ tipo = "SAQUE"; valor = 20; vetorConhecido = $vetorAntigo } | ConvertTo-Json -Compress
try {
  Invoke-RestMethod -Uri "http://localhost:4145/contas/3/sincronizar" -Method Post -Headers $h -ContentType "application/json" -Body $corpo
} catch {
  $r = $_.Exception.Response
  $json = $_.ErrorDetails.Message
  if (-not $json) { $json = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd() }
  "HTTP $([int]$r.StatusCode) -> $json"
  $conflito = $json | ConvertFrom-Json
}
```

O print deve mostrar o **HTTP 409** com `erro`, `vetorConhecido`, `vetorAtual`, `relacao` e
`contaAtual` (saldo 150, já com o depósito).

Em seguida (mesmo print ou logo abaixo), a nova tentativa com o vetor devolvido no 409:

```powershell
Get-Date
$corpo2 = @{ tipo = "SAQUE"; valor = 20; vetorConhecido = $conflito.vetorAtual } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "http://localhost:4145/contas/3/sincronizar" -Method Post -Headers $h -ContentType "application/json" -Body $corpo2 | ConvertTo-Json -Compress
```

Esperado: 200 e saldo 130. No terminal da Agência 0 aparece o evento da sincronização aplicada.

> Atenção: qualquer evento na Agência 0 entre o passo 4 e a nova tentativa (por exemplo, uma
> eleição ou uma operação em outra conta) avança o vetor e gera **outro** 409. O vetor é por
> agência, não por conta - é a simplificação discutida na Parte E de `04-documentacao/RESPOSTAS.md`.

## 7. `app-offline.png`

1. No app, logado, abra a conta **3** (ou outra da Agência 0) com a rede ligada, para o app
   guardar saldo e `vetorAtual` no cache local.
2. Coloque o emulador em **modo avião** (arraste a barra de notificações -> ícone de avião) ou
   desligue Wi-Fi e dados móveis. No emulador isso corta o acesso a `10.0.2.2`, ou seja, às
   agências.
3. Faça um depósito de 10 pelo app.
4. Print: a operação aparece como **pendente**, com o saldo provisório já somado e o indicador de
   "aguardando sincronização", sem mensagem de erro. Terminal com `Get-Date` ao lado.

> Alternativa para simular "agência inacessível" sem mexer na rede do emulador: parar o processo
> da agência (Ctrl+C). Cuidado: o estado da agência é **em memória** - ao subir de novo ela volta
> sem contas e com o vetor zerado, e a sincronização posterior falha (404). Para o par de prints
> offline/sincronização, prefira o modo avião.

## 8. `app-sincronizacao.png`

Continuando do passo 7, **sem tocar no app**:

1. Desligue o modo avião.
2. O `connectivity_plus` avisa o app da reconexão e a fila pendente é enviada sozinha para
   `POST /contas/{id}/sincronizar`.
3. Print: a operação sai da fila, o selo de pendente some e o saldo exibido passa a ser o real
   devolvido pela agência. Terminal com `Get-Date` e, se quiser, o terminal da Agência 0 mostrando
   o evento da sincronização.

Para provar pelo terminal que o saldo chegou de fato:

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4145/contas/3" -Headers $h | ConvertTo-Json -Compress
```

### Conflito no app (roteiro 10.2, tarefa 5)

Não é um print obrigatório, mas o roteiro pede a reprodução (e ela rende uma boa imagem extra):

1. App online abre a conta 3 (cache atualizado).
2. Modo avião no emulador. Faça um saque de 5 pelo app -> fica pendente.
3. **No host** (o host continua falando com a agência), altere a mesma conta por outro caminho:

   ```powershell
   Get-Date
   Invoke-RestMethod -Uri "http://localhost:4145/contas/3/depositar" -Method Post -Headers $h -ContentType "application/json" -Body '{"valor":7}' | ConvertTo-Json -Compress
   ```

   ou pelo frontend web (<http://localhost:5500>, login `ana`, depósito na conta 3).
4. Desligue o modo avião. A sincronização automática recebe **409** e o app mostra o **cartão de
   conflito**: operação que não pôde ser aplicada + saldo real atual da conta, com os botões
   **Reaplicar** (reenvia com o vetor novo) e **Descartar** (remove da fila).
5. Toque em **Reaplicar** e confira o saldo final pelo `Invoke-RestMethod` acima.

## 9. `funcionalidade-adicional.png`

Funcionalidade adicional do sprint: **indicador de agências ativas** (e histórico de líderes) no
painel do app.

1. Com as 3 agências no ar, o painel mostra as 3 como ativas.
2. Derrube a Agência 1 (Ctrl+C) - não é a líder, então a liderança não muda.
3. Print: o painel mostra a Agência 1 como **inativa**, a líder continua sendo a 2, e a lista de
   trocas de liderança do app está visível. Terminal com `Get-Date`.

Pelo terminal, o mesmo estado:

```powershell
Get-Date
4145..4147 | ForEach-Object {
  try { "porta $_ -> " + (Invoke-RestMethod -Uri "http://localhost:$_/bully/lider" -TimeoutSec 2 | ConvertTo-Json -Compress) }
  catch { "porta $_ -> inativa" }
}
```

---

## Checklist de regressão (Sprints 1 e 2)

Rode antes de entregar, com as 3 agências e o broker no ar:

- [ ] **JWT: 401 sem token**

  ```powershell
  Get-Date
  try { Invoke-RestMethod -Uri "http://localhost:4145/contas/3" } catch { $_.Exception.Response.StatusCode; $_.ErrorDetails.Message }
  ```

  Esperado: `Unauthorized`. As rotas `/bully/*` continuam públicas; `/contas/*`, incluindo
  `/sincronizar`, não.
- [ ] **Frontend web** em <http://localhost:5500> (`cd 02-frontend-web; python -m http.server 5500`):
  login, saldo, depósito, saque e transferência continuam funcionando (o campo novo `vetorAtual`
  na resposta não quebra a tela).
- [ ] **Transferência entre agências via RabbitMQ**:

  ```powershell
  Get-Date
  $loginAg1 = Invoke-RestMethod -Uri "http://localhost:4146/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"bruno","senha":"senha123"}'
  $h1 = @{ Authorization = "Bearer $($loginAg1.token)" }
  Invoke-RestMethod -Uri "http://localhost:4146/contas" -Method Post -Headers $h1 -ContentType "application/json" -Body '{"id":1,"nomeAluno":"Bruno","saldoInicial":0}'
  Invoke-RestMethod -Uri "http://localhost:4145/transferencias" -Method Post -Headers $h -ContentType "application/json" -Body '{"idOrigem":3,"idDestino":1,"valor":10}'
  Start-Sleep -Seconds 2
  Invoke-RestMethod -Uri "http://localhost:4146/contas/1" -Headers $h1 | ConvertTo-Json -Compress
  ```

  Esperado: "entrega assíncrona" na resposta e saldo 10 na conta 1.
- [ ] **MesclarLogs** ainda roda e lista pares concorrentes (os eventos de eleição entram na
  linha do tempo também - isso é esperado):

  ```powershell
  cd 01-backend-agencias
  mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.ferramentas.MesclarLogs"
  ```
- [ ] **Testes automatizados**: `cd 01-backend-agencias; mvn test` e `cd 03-app-mobile-flutter; flutter test` verdes.

## Como os prints desta entrega foram gerados (sem emulador Android)

A máquina de entrega não tinha Android SDK, então os prints do app (4, 5, 7, 8, 9) foram feitos com o
**mesmo código rodando como Flutter Web**, e o "modo avião" foi simulado com um proxy TCP local:

```powershell
cd 03-app-mobile-flutter
flutter build web --dart-define=AGENCIA_HOST=localhost --dart-define=PORTA_BASE=5145
# serve build/web em qualquer servidor estatico (ex.: npx http-server build/web -p 8080)
```

O app passa a falar com as portas 5145-5147. Um proxy TCP simples (Node `net`, ~30 linhas) repassa
5145-5147 -> 4145-4147. **Fechar o proxy** equivale a tirar a rede do celular: as três agências
somem para o app, mas o backend segue no ar. **Reabrir o proxy** equivale a reconectar. O
`ServicoContas` percebe a volta na tentativa periódica de 5 s e sincroniza sozinho.

Prints extras gerados nessa execução:

| Arquivo | O que mostra |
|---|---|
| `app-conflito.png` | card de conflito: depósito offline de R$ 15 recusado com 409 porque outro cliente depositou R$ 50 enquanto o app estava offline |
| `app-reaplicado.png` | após "Reaplicar com saldo atual": operação aplicada (saldo 185), card some |

Todo print do app tem, à direita, uma janela **Monitor** capturada no mesmo instante. Ela mostra
`Get-Date`, o estado da rede do app (ONLINE/OFFLINE, conforme o proxy) e o `GET /bully/lider` de cada
agência ("no ar liderAtual = X" ou "FORA DO AR"). Assim o horário e o estado do backend ficam
visíveis junto com a tela.

Os prints de terminal (1, 2, 3, 6) usam janelas `conhost` separadas por agência, com `Get-Date` no topo.
`historico-lideranca.png` usa uma janela alta o bastante para mostrar a saída inteira, sem rolagem.
`eleicao-inicial.png` e `failover.png` juntam numa só imagem a janela de consulta e as janelas das agências.
