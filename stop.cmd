@echo off
rem ============================================================
rem  停止本项目服务（双击即可运行）
rem ============================================================
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
echo.
pause
endlocal
