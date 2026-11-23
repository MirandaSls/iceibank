# ICEIBank - encerra as 3 agencias e o servidor do frontend abertos por iniciar-tudo.ps1.
# O RabbitMQ do Docker continua no ar; para derruba-lo: docker compose down

$agencias = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*iceibank-agencia*' }
foreach ($p in $agencias) {
    Stop-Process -Id $p.ProcessId -Force
    Write-Host "Agencia encerrada (PID $($p.ProcessId))" -ForegroundColor Green
}

$frontend = Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" |
    Where-Object { $_.CommandLine -like '*servidor-frontend.ps1*' }
foreach ($p in $frontend) {
    Stop-Process -Id $p.ProcessId -Force
    Write-Host "Servidor do frontend encerrado (PID $($p.ProcessId))" -ForegroundColor Green
}

if (-not $agencias -and -not $frontend) {
    Write-Host 'Nada do ICEIBank estava rodando.' -ForegroundColor Yellow
}
