@echo off
rem ============================================================
rem  Trainer-Eval stop script (double-click to run)
rem
rem  NOTE: keep this file ASCII-ONLY - see the note in start.cmd.
rem  Kills the process trees listening on 8080 / 5173.
rem ============================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
pause
rem endlocal + exit on one line so %EXITCODE% is expanded before endlocal runs.
endlocal & exit /b %EXITCODE%
