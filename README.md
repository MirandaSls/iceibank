# ICEIBank

Projeto da disciplina **Laboratorio de Desenvolvimento de Aplicacoes Moveis e Distribuidas**.

Banco simplificado dividido em agencias, onde cada agencia e uma particao independente
de contas. O projeto evolui ao longo de 4 sprints; este repositorio esta no **Sprint 1**
(U2 - Desenvolvimento Web: arquitetura MVC, servicos REST e relogio logico de Lamport).

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
| Autenticacao | JWT (jjwt 0.12.6) |
| Frontend | HTML + CSS + JavaScript puro (sem framework) |

## Estrutura

```
iceibank/
|-- agencia/          servico REST de uma agencia (mesmo codigo, 3 execucoes)
|-- frontend/         interface web que consome a API
|-- evidencias/       prints de teste do sprint
|-- RESPOSTAS.md      respostas das questoes do roteiro
```

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
cd agencia; $env:AGENCIA_ID=0; java -jar target\iceibank-agencia-1.0.0.jar
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
