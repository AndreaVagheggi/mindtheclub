@echo off
rem Dice quali file del motore sono cambiati nella App dopo essere stati copiati sul PC.
rem Non modifica nulla.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0motore\confronta.ps1"
pause
