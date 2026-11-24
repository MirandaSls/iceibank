# ICEIBank - imprime a linha do tempo unificada dos logs das 3 agencias de um sprint.
#   Sprint 1: ordenada pelo relogio de Lamport (e lista os empates entre agencias)
#   Sprint 2 e 3: relogio vetorial, com os pares causais e os pares CONCORRENTES
# Rode depois de usar o sistema do sprint (os logs ficam na pasta data do backend dele).
#
# Uso: powershell -ExecutionPolicy Bypass -File scripts\linha-do-tempo.ps1 -Sprint 1

param(
    [ValidateSet('1', '2', '3')]
    [string]$Sprint = '3'
)

$raiz = Split-Path -Parent $PSScriptRoot
if ($Sprint -eq '3') {
    $backend = Join-Path $raiz '01-backend-agencias'
    $classe  = 'br.pucminas.icei.iceibank.agencia.ferramentas.MesclarLogs'
} else {
    $backend = Join-Path $raiz ".sprints\sprint-$Sprint\agencia"
    $classe  = 'br.pucminas.icei.iceibank.agencia.MesclarLogs'
}

if (-not (Test-Path (Join-Path $backend 'mvnw.cmd'))) {
    Write-Host "O Sprint $Sprint ainda nao foi executado neste PC. Rode antes scripts\sprint-$Sprint.bat." -ForegroundColor Red
    exit 1
}

Write-Host "=== Linha do tempo do Sprint $Sprint ===" -ForegroundColor Cyan
Push-Location $backend
& .\mvnw.cmd -q compile exec:java "-Dexec.mainClass=$classe"
Pop-Location
