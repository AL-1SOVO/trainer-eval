@echo off
rem ============================================================
rem  Trainer-Eval one-click launcher (double-click to run)
rem
rem  NOTE: keep this file ASCII-ONLY.
rem  cmd.exe reads .cmd files using the OEM code page (e.g. GBK/936),
rem  so non-ASCII comments get decoded wrongly and corrupt the
rem  following command lines. All Chinese messages are printed by
rem  start.ps1 instead (PowerShell reads it as UTF-8 with BOM).
rem
rem  Usage:
rem    start.cmd                 start backend + frontend
rem    start.cmd -Restart        kill stale instances first
rem    start.cmd -BackendOnly    backend only
rem  Stop: press Ctrl+C in this window (then answer Y), or run stop.cmd
rem ============================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
    echo ============================================================
    echo  [ERROR] start.ps1 exited with code %EXITCODE%
    echo  Scroll up to read the messages above.
    echo ============================================================
) else (
    echo ============================================================
    echo  [INFO] All services stopped.
    echo ============================================================
)
echo.
pause
rem NOTE: endlocal and exit must be on ONE line. Otherwise endlocal discards
rem EXITCODE first and "%EXITCODE%" expands to empty, losing the real exit code.
endlocal & exit /b %EXITCODE%
