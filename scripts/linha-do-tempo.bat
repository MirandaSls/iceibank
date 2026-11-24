@echo off
REM Linha do tempo unificada dos logs das 3 agencias. Uso: linha-do-tempo.bat [1^|2^|3]  (padrao: 3)
set SPRINT=%1
if "%SPRINT%"=="" set SPRINT=3
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0linha-do-tempo.ps1" -Sprint %SPRINT%
pause
