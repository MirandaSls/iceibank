@echo off
REM Le os logs das 3 agencias e imprime o historico de liderancas (Bully).
cd /d "%~dp0..\01-backend-agencias"
call mvnw.cmd -q compile exec:java "-Dexec.mainClass=br.pucminas.icei.iceibank.agencia.ferramentas.HistoricoLideranca"
pause
