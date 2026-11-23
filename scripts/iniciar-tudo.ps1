# ICEIBank - sobe o sistema inteiro no Windows.
#   - compila o backend na primeira vez (usa o Maven Wrapper: nao precisa ter Maven instalado)
#   - sobe o RabbitMQ local via Docker, se RABBITMQ_URL nao estiver definida e o Docker existir
#   - abre 3 janelas, uma por agencia (portas 4145, 4146, 4147)
#   - abre 1 janela servindo o frontend web em http://localhost:5500 e abre o navegador
#
# Uso (pela raiz do projeto):  powershell -ExecutionPolicy Bypass -File scripts\iniciar-tudo.ps1
# Ou de dois cliques em scripts\iniciar-tudo.bat

$ErrorActionPreference = 'Stop'
$raiz     = Split-Path -Parent $PSScriptRoot
$backend  = Join-Path $raiz '01-backend-agencias'
$jar      = Join-Path $backend 'target\iceibank-agencia-2.0.0.jar'
$servidor = Join-Path $PSScriptRoot 'servidor-frontend.ps1'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host 'Java nao encontrado no PATH. Instale o JDK 21 (ver README.md, secao "Rodar no PC do laboratorio").' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $jar)) {
    Write-Host '>> Compilando o backend pela primeira vez (pode levar alguns minutos)...' -ForegroundColor Cyan
    Push-Location $backend
    & .\mvnw.cmd -q clean package -DskipTests
    $codigo = $LASTEXITCODE
    Pop-Location
    if ($codigo -ne 0) {
        Write-Host 'Falha ao compilar. Rode manualmente: cd 01-backend-agencias; .\mvnw.cmd clean package -DskipTests' -ForegroundColor Red
        exit 1
    }
}

if ($env:RABBITMQ_URL) {
    Write-Host ">> Usando o RabbitMQ de RABBITMQ_URL." -ForegroundColor Cyan
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
    Write-Host '>> Subindo RabbitMQ local via Docker (painel: http://localhost:15672, guest/guest)...' -ForegroundColor Cyan
    docker compose -f (Join-Path $raiz 'docker-compose.yml') up -d
} else {
    Write-Host 'AVISO: sem RABBITMQ_URL e sem Docker. Login, contas, deposito, saque, transferencia na mesma' -ForegroundColor Yellow
    Write-Host '       agencia e eleicao de lider funcionam; transferencia ENTRE agencias vai responder 502.' -ForegroundColor Yellow
    Write-Host '       Para ter tudo: defina $env:RABBITMQ_URL com a URL do CloudAMQP e rode de novo.' -ForegroundColor Yellow
}

foreach ($id in 0, 1, 2) {
    $porta = 4145 + $id
    $comando = "`$host.UI.RawUI.WindowTitle = 'ICEIBank - Agencia $id (porta $porta)'; " +
               "`$env:AGENCIA_ID = $id; Set-Location '$backend'; java -jar '$jar'"
    Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-Command', $comando
    Write-Host ">> Agencia $id subindo na porta $porta" -ForegroundColor Green
    Start-Sleep -Milliseconds 700
}

Start-Process powershell -ArgumentList '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$servidor`""
Write-Host '>> Frontend em http://localhost:5500' -ForegroundColor Green

Write-Host '>> Aguardando as agencias responderem...' -ForegroundColor Cyan
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-RestMethod -Uri 'http://localhost:4147/bully/lider' -TimeoutSec 1 | Out-Null
        break
    } catch {
        Start-Sleep -Seconds 1
    }
}
Start-Sleep -Seconds 3

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

Start-Process 'http://localhost:5500'
Write-Host ''
Write-Host 'Pronto! Login: ana / senha123 (tambem bruno e carla).' -ForegroundColor Green
Write-Host 'Para parar tudo: scripts\parar-tudo.bat' -ForegroundColor Green
