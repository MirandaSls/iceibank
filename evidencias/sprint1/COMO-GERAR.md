# Como gerar as evidências do Sprint 1

Todos os prints devem mostrar uma saída de `Get-Date` visível em algum terminal, para comprovar
que a execução é recente (convenção dos laboratórios anteriores).

Antes de tudo, deixe as 3 agências no ar (um terminal cada) e o frontend servido:

```powershell
cd agencia
mvn clean package
```

```powershell
# Terminal 1
cd agencia; $env:AGENCIA_ID=0; mvn spring-boot:run
# Terminal 2
cd agencia; $env:AGENCIA_ID=1; mvn spring-boot:run
# Terminal 3
cd agencia; $env:AGENCIA_ID=2; mvn spring-boot:run
# Terminal 4
cd frontend; python -m http.server 5500
```

> Dica usada nos prints deste repositorio: acrescente `| ConvertTo-Json -Compress` no fim das
> chamadas que devolvem uma conta. A resposta sai em uma linha so (`{"id":0,"nomeAluno":"Ana",
> "saldo":70}`), o que deixa o print bem mais legivel do que a tabela padrao do PowerShell.

Em um quinto terminal (o de testes), pegue um token e guarde numa variável:

```powershell
Get-Date
$login = Invoke-RestMethod -Uri "http://localhost:4145/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"ana","senha":"senha123"}'
$h = @{ Authorization = "Bearer $($login.token)" }
```

---

## 1. `transferencia-local.png`

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4145/contas" -Method Post -Headers $h -ContentType "application/json" -Body '{"id":0,"nomeAluno":"Ana","saldoInicial":100}'
Invoke-RestMethod -Uri "http://localhost:4145/contas" -Method Post -Headers $h -ContentType "application/json" -Body '{"id":3,"nomeAluno":"Carla","saldoInicial":10}'
Invoke-RestMethod -Uri "http://localhost:4145/transferencias" -Method Post -Headers $h -ContentType "application/json" -Body '{"idOrigem":0,"idDestino":3,"valor":30}'
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h
Invoke-RestMethod -Uri "http://localhost:4145/contas/3" -Headers $h
```

Capture o terminal de teste **e** o terminal da Agência 0, onde aparecem as linhas
`[Lamport n] TRANSFERENCIA_DEBITO` e `[Lamport n+1] TRANSFERENCIA_CREDITO`.

## 2. `transferencia-entre-agencias.png`

```powershell
Get-Date
$loginAg1 = Invoke-RestMethod -Uri "http://localhost:4146/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"bruno","senha":"senha123"}'
$h1 = @{ Authorization = "Bearer $($loginAg1.token)" }
Invoke-RestMethod -Uri "http://localhost:4146/contas" -Method Post -Headers $h1 -ContentType "application/json" -Body '{"id":1,"nomeAluno":"Bruno","saldoInicial":50}'
Invoke-RestMethod -Uri "http://localhost:4145/transferencias" -Method Post -Headers $h -ContentType "application/json" -Body '{"idOrigem":0,"idDestino":1,"valor":25}'
Invoke-RestMethod -Uri "http://localhost:4146/contas/1" -Headers $h1
```

O print precisa mostrar os **logs das duas agências envolvidas**: na Agência 0 o
`TRANSFERENCIA_DEBITO` e o `TRANSFERENCIA_ENVIADA`; na Agência 1 o
`TRANSFERENCIA_CREDITO_REMOTO` com o timestamp já ajustado por `max(local, recebido) + 1`.

## 3. `falha-conhecida.png`

Feche o terminal da **Agência 2** (Ctrl+C) e então:

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h          # saldo antes
try {
  Invoke-RestMethod -Uri "http://localhost:4145/transferencias" -Method Post -Headers $h -ContentType "application/json" -Body '{"idOrigem":0,"idDestino":2,"valor":10}'
} catch {
  # no 502 o corpo nao chega em $_.ErrorDetails; e preciso ler o stream da resposta
  $r = $_.Exception.Response
  $corpo = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd()
  "HTTP $([int]$r.StatusCode) -> $corpo"
}
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h          # saldo depois: caiu e nao voltou
```

O print deve mostrar o **502**, o saldo menor depois do erro e a linha
`[Lamport n] TRANSFERENCIA_FALHOU` no terminal da Agência 0.

Depois suba a Agência 2 de novo.

## 4. `linha-do-tempo.png`

```powershell
Get-Date
cd agencia
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"
```

Capture a linha do tempo unificada **e** a seção final de empates entre agências
(os eventos concorrentes).

## 5. `auth-sem-token.png`

```powershell
Get-Date
try { Invoke-RestMethod -Uri "http://localhost:4145/contas/0" } catch { $_.Exception.Response.StatusCode; $_.ErrorDetails.Message }
```

## 6. `auth-com-token.png`

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h
```

## 7. `auth-token-expirado.png`

Jeito rápido (sem esperar): suba a Agência 0 com validade **zero**, e o token já nasce expirado.

```powershell
cd agencia
$env:AGENCIA_ID=0
mvn spring-boot:run "-Dspring-boot.run.arguments=--iceibank.jwt.validade-minutos=0"
```

```powershell
Get-Date
$expirado = Invoke-RestMethod -Uri "http://localhost:4145/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"ana","senha":"senha123"}'
try {
  Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers @{ Authorization = "Bearer $($expirado.token)" }
} catch {
  $_.Exception.Response.StatusCode; $_.ErrorDetails.Message                   # 401 - Token expirado
}
```

Jeito realista (esperando o prazo passar): suba **uma** agência com validade de 1 minuto:

```powershell
cd agencia
$env:AGENCIA_ID=0
mvn spring-boot:run "-Dspring-boot.run.arguments=--iceibank.jwt.validade-minutos=1"
```

No terminal de teste:

```powershell
Get-Date
$curto = Invoke-RestMethod -Uri "http://localhost:4145/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"ana","senha":"senha123"}'
Start-Sleep -Seconds 70
Get-Date
try {
  Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers @{ Authorization = "Bearer $($curto.token)" }
} catch {
  $_.Exception.Response.StatusCode; $_.ErrorDetails.Message                   # 401 - Token expirado
}
```

## 8. `frontend-login.png`, `frontend-transferencia.png`, `frontend-erro.png`

Abra <http://localhost:5500> e capture:

- **login**: a tela de login preenchida com `ana` / `senha123` e a agência de entrada selecionada
- **transferencia**: uma transferência concluída, com o saldo atualizado no cartão da conta e a
  linha correspondente no "Diário da sessão" (faça uma local e uma entre agências)
- **erro**: um saque maior que o saldo, mostrando a mensagem vermelha "Saldo insuficiente."
  (ou a mensagem âmbar do 502, com a Agência de destino derrubada)

## 9. `funcionalidade-adicional.png`

Na aba **Transferir**, deixe marcada a caixa "Proteger contra reenvio duplicado", faça uma
transferência e em seguida clique em **"Reenviar a mesma requisição"**. O print deve mostrar o
aviso *"Requisição repetida: a transferência já tinha sido aplicada e NÃO foi duplicada"* e o
saldo inalterado.

Se preferir provar pelo terminal:

```powershell
Get-Date
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h
$hIdem = $h.Clone(); $hIdem["Idempotency-Key"] = "transferencia-demo-1"
$corpo = '{"idOrigem":0,"idDestino":3,"valor":5}'
# Invoke-WebRequest (e nao Invoke-RestMethod) porque so ele expoe os cabecalhos da resposta
# no Windows PowerShell 5.1 - o parametro -ResponseHeadersVariable so existe do PowerShell 7 em diante
$r1 = Invoke-WebRequest -Uri "http://localhost:4145/transferencias" -Method Post -Headers $hIdem -ContentType "application/json" -Body $corpo -UseBasicParsing
"1a chamada -> HTTP $($r1.StatusCode) | Idempotency-Replayed: [$($r1.Headers['Idempotency-Replayed'])] | $($r1.Content)"
$r2 = Invoke-WebRequest -Uri "http://localhost:4145/transferencias" -Method Post -Headers $hIdem -ContentType "application/json" -Body $corpo -UseBasicParsing
"2a chamada -> HTTP $($r2.StatusCode) | Idempotency-Replayed: [$($r2.Headers['Idempotency-Replayed'])] | $($r2.Content)"
Invoke-RestMethod -Uri "http://localhost:4145/contas/0" -Headers $h          # caiu apenas 5, nao 10
```
