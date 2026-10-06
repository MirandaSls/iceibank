# Como gerar as evidências do Sprint 2

Pré-requisito: RabbitMQ no ar (CloudAMQP ou local) e `RABBITMQ_URL` definida em **cada** terminal.
Todos os prints devem mostrar `Get-Date` visível. Suba as 3 agências e o frontend como no README
(`mvn spring-boot:run` com `AGENCIA_ID=0/1/2`), e pegue um token:

```powershell
Get-Date
$login = Invoke-RestMethod -Uri "http://localhost:4145/auth/login" -Method Post -ContentType "application/json" -Body '{"usuario":"ana","senha":"senha123"}'
$h = @{ Authorization = "Bearer $($login.token)" }
```

Crie contas (0 na Agência 0 e 1 na Agência 1; troque a porta 4145/4146):

```powershell
Invoke-RestMethod -Uri "http://localhost:4145/contas" -Method Post -Headers $h -ContentType "application/json" -Body '{"id":0,"nomeAluno":"Ana","saldoInicial":100}'
Invoke-RestMethod -Uri "http://localhost:4146/contas" -Method Post -Headers $h -ContentType "application/json" -Body '{"id":1,"nomeAluno":"Bruno","saldoInicial":0}'
```

## 1. `transferencia-assincrona.png`
Transfira 30 da conta 0 para a 1 e mostre o log das duas agências (`[Vetor ...] TRANSFERENCIA_ENVIADA`
na 0 e `TRANSFERENCIA_CREDITO_REMOTO` na 1) e o saldo da conta 1 = 30:

```powershell
Invoke-RestMethod -Uri "http://localhost:4145/transferencias" -Method Post -Headers $h -ContentType "application/json" -Body '{"idOrigem":0,"idDestino":1,"valor":30}'
Invoke-RestMethod -Uri "http://localhost:4146/contas/1" -Headers $h
```

## 2. `resiliencia-fila.png`
1. Feche o terminal da Agência 1. 2. Repita a transferência: a resposta continua 200. 3. No
RabbitMQ Manager, veja 1 mensagem em `fila-agencia-1`. 4. Suba a Agência 1 de novo e observe o log:
- Fechar o processo apaga as contas (estão em memória): ao voltar,
  a conta 1 não existe, aparece `CREDITO_REMOTO_FALHOU (conta nao encontrada)` e a mensagem vai
  para `fila-agencia-1.dlq` (veja no Manager). Anote isso na resposta 1 da Parte C.

## 3. `linha-do-tempo-causal.png`
Crie contas em duas agências sem transferir entre elas (eventos concorrentes), faça uma
transferência entre agências, e rode:

```powershell
cd agencia
mvn -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.MesclarLogs"
```
Deve listar o par concorrente e mostrar o par envio→crédito só em "pares causais".

## 4. `dead-letter-queue.png` (funcionalidade adicional)
Print do RabbitMQ Manager com a mensagem em `fila-agencia-1.dlq` após o cenário 2.
