# ICEIBank - sobe o sistema de UM sprint no Windows.
#
#   Sprint 3 (padrao): o codigo atual da pasta 01-backend-agencias / 02-frontend-web.
#   Sprint 1 e 2: o codigo exatamente como foi entregue naquele sprint (tags git sprint-1 e
#   sprint-2), extraido para a pasta .sprints\sprint-N na primeira vez.
#
# O script:
#   - prepara e compila o backend do sprint (Maven Wrapper: nao precisa ter Maven instalado)
#   - Sprints 2 e 3: sobe o RabbitMQ via Docker, se RABBITMQ_URL nao estiver definida
#   - abre 3 janelas, uma por agencia (portas 4145, 4146, 4147)
#   - abre 1 janela servindo o frontend do sprint em http://localhost:5500 e abre o navegador
#
# Uso (pela raiz do projeto):
#   powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1 -Sprint 1
# Ou de dois cliques em scripts\sprint-1.bat, sprint-2.bat ou sprint-3.bat

param(
    [ValidateSet('1', '2', '3')]
    [string]$Sprint = '3'
)

$ErrorActionPreference = 'Stop'
$raiz       = Split-Path -Parent $PSScriptRoot
$backendS3  = Join-Path $raiz '01-backend-agencias'
$servidor   = Join-Path $PSScriptRoot 'servidor-frontend.ps1'

$descricao = @{
    '1' = 'REST + relogio de Lamport + JWT + frontend (transferencia entre agencias por HTTP)'
    '2' = 'RabbitMQ + relogio vetorial + dead-letter queue'
    '3' = 'Eleicao de lider (Bully) + heartbeat + sincronizacao offline do app'
}

# Diz se ja existe algo escutando na porta (agencia de outro sprint ainda no ar, por exemplo).
function Test-PortaOcupada([int]$porta) {
    $cliente = New-Object System.Net.Sockets.TcpClient
    try {
        return $cliente.ConnectAsync('localhost', $porta).Wait(500)
    } catch {
        return $false
    } finally {
        $cliente.Close()
    }
}

Write-Host ''
Write-Host "=== ICEIBank - Sprint $Sprint ===" -ForegroundColor Cyan
Write-Host "    $($descricao[$Sprint])" -ForegroundColor Cyan
Write-Host ''

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host 'Java nao encontrado no PATH. Instale o JDK 21 (ver README.md, secao "Rodar no PC do laboratorio").' -ForegroundColor Red
    exit 1
}

if ((Test-PortaOcupada 4145) -or (Test-PortaOcupada 5500)) {
    Write-Host 'As portas 4145 ou 5500 ja estao em uso: ha agencias (talvez de outro sprint) rodando.' -ForegroundColor Red
    Write-Host 'Rode scripts\parar-tudo.bat e tente de novo.' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------------------------
# 1. Pastas do backend e do frontend deste sprint
# ---------------------------------------------------------------------------------------------
if ($Sprint -eq '3') {
    $backend  = $backendS3
    $frontend = Join-Path $raiz '02-frontend-web'
} else {
    $pastaSprint = Join-Path $raiz ".sprints\sprint-$Sprint"
    $backend     = Join-Path $pastaSprint 'agencia'
    $frontend    = Join-Path $pastaSprint 'frontend'

    if (-not (Test-Path $backend)) {
        if (-not (Test-Path (Join-Path $raiz '.git')) -or -not (Get-Command git -ErrorAction SilentlyContinue)) {
            Write-Host "Para rodar o Sprint $Sprint e preciso o Git e o projeto baixado com 'git clone'." -ForegroundColor Red
            Write-Host "Alternativa sem Git: no GitHub, baixe o ZIP da tag sprint-$Sprint e extraia o conteudo" -ForegroundColor Red
            Write-Host "em $pastaSprint (de forma que exista $backend)." -ForegroundColor Red
            exit 1
        }
        Write-Host ">> Extraindo o codigo do Sprint $Sprint (tag sprint-$Sprint) para .sprints\sprint-$Sprint ..." -ForegroundColor Cyan
        # Traz as tags do GitHub so se o clone for anterior a elas.
        $tag = git -C $raiz rev-parse -q --verify "refs/tags/sprint-$Sprint"
        if (-not $tag) {
            git -C $raiz fetch -q origin --tags
        }
        git -C $raiz worktree add -q --detach $pastaSprint "sprint-$Sprint"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backend)) {
            Write-Host "Nao consegui extrair a tag sprint-$Sprint. Rode 'git fetch --tags' e tente de novo." -ForegroundColor Red
            exit 1
        }
    }

    # As versoes antigas nao tinham o Maven Wrapper: copia o do Sprint 3.
    if (-not (Test-Path (Join-Path $backend 'mvnw.cmd'))) {
        Copy-Item (Join-Path $backendS3 'mvnw.cmd') $backend
        Copy-Item (Join-Path $backendS3 'mvnw') $backend
    }
    if (-not (Test-Path (Join-Path $backend '.mvn'))) {
        Copy-Item -Recurse (Join-Path $backendS3 '.mvn') $backend
    }
}

# ---------------------------------------------------------------------------------------------
# 2. Compila (so na primeira vez de cada sprint)
# ---------------------------------------------------------------------------------------------
function Get-JarDoSprint {
    Get-ChildItem (Join-Path $backend 'target') -Filter 'iceibank-agencia-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
}

$jar = Get-JarDoSprint
if (-not $jar) {
    Write-Host ">> Compilando o backend do Sprint $Sprint (a primeira vez pode levar alguns minutos)..." -ForegroundColor Cyan
    Push-Location $backend
    & .\mvnw.cmd -q clean package -DskipTests
    $codigo = $LASTEXITCODE
    Pop-Location
    $jar = Get-JarDoSprint
    if ($codigo -ne 0 -or -not $jar) {
        Write-Host "Falha ao compilar. Rode manualmente: cd `"$backend`"; .\mvnw.cmd clean package -DskipTests" -ForegroundColor Red
        exit 1
    }
}
$jar = $jar.FullName

# ---------------------------------------------------------------------------------------------
# 3. RabbitMQ (so Sprints 2 e 3; o Sprint 1 transferia por HTTP direto)
# ---------------------------------------------------------------------------------------------
if ($Sprint -eq '1') {
    Write-Host '>> Sprint 1 nao usa RabbitMQ: a transferencia entre agencias e uma chamada HTTP.' -ForegroundColor Cyan
} elseif ($env:RABBITMQ_URL) {
    Write-Host '>> Usando o RabbitMQ de RABBITMQ_URL.' -ForegroundColor Cyan
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host '>> Subindo RabbitMQ local via Docker (painel: http://localhost:15672, guest/guest)...' -ForegroundColor Cyan
    docker compose -f (Join-Path $raiz 'docker-compose.yml') up -d
} else {
    Write-Host 'AVISO: sem RABBITMQ_URL e sem Docker. Login, contas, deposito, saque e transferencia na mesma' -ForegroundColor Yellow
    Write-Host '       agencia funcionam; transferencia ENTRE agencias vai responder 502.' -ForegroundColor Yellow
    Write-Host '       Para ter tudo: defina $env:RABBITMQ_URL com a URL do CloudAMQP e rode de novo.' -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------------------------
# 4. Sobe as 3 agencias e o frontend
# ---------------------------------------------------------------------------------------------
foreach ($id in 0, 1, 2) {
    $porta = 4145 + $id
    $comando = "`$host.UI.RawUI.WindowTitle = 'ICEIBank Sprint $Sprint - Agencia $id (porta $porta)'; " +
               "`$env:AGENCIA_ID = $id; Set-Location '$backend'; java -jar '$jar'"
    Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $comando
    Write-Host ">> Agencia $id subindo na porta $porta" -ForegroundColor Green
    Start-Sleep -Milliseconds 700
}

Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', "`"$servidor`"", '-Pasta', "`"$frontend`""
Write-Host ">> Frontend do Sprint $Sprint em http://localhost:5500" -ForegroundColor Green

Write-Host '>> Aguardando as agencias responderem...' -ForegroundColor Cyan
for ($i = 0; $i -lt 90; $i++) {
    if (Test-PortaOcupada 4147) { break }
    Start-Sleep -Seconds 1
}
Start-Sleep -Seconds 3

if ($Sprint -eq '3') {
    Write-Host ''
    Write-Host 'Lider em cada agencia (GET /bully/lider):' -ForegroundColor Cyan
    4145..4147 | ForEach-Object {
        try {
            $r = Invoke-RestMethod -Uri "http://localhost:$_/bully/lider" -TimeoutSec 2
            Write-Host ("  Agencia {0} -> lider atual = {1}" -f $r.idAgencia, $r.liderAtual)
        } catch {
            Write-Host "  porta $_ ainda nao respondeu" -ForegroundColor Yellow
        }
    }
}

Start-Process 'http://localhost:5500'
Write-Host ''
Write-Host "Pronto! Sprint $Sprint no ar. Login: ana / senha123 (tambem bruno e carla)." -ForegroundColor Green
Write-Host "Linha do tempo deste sprint: scripts\linha-do-tempo.bat $Sprint" -ForegroundColor Green
Write-Host 'Para parar tudo: scripts\parar-tudo.bat' -ForegroundColor Green
