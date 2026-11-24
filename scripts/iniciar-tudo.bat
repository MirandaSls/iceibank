@echo off
REM Atalho para o sprint mais recente (Sprint 3). Para outro sprint use sprint-1.bat ou sprint-2.bat.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar-tudo.ps1" -Sprint 3
pause
