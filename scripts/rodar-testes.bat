@echo off
REM Roda os testes automatizados do backend (JUnit) usando o Maven Wrapper.
cd /d "%~dp0..\01-backend-agencias"
call mvnw.cmd clean test
pause
