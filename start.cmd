@echo off
rem ============================================================
rem  一键启动（双击即可运行）
rem  Ctrl+C 后如提示 "Terminate batch job (Y/N)?"，输入 Y 回车即可停止服务
rem  参数会原样透传给 start.ps1，例如：start.cmd -Restart
rem ============================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set EXITCODE=%ERRORLEVEL%

if not "%EXITCODE%"=="0" (
    echo.
    echo 脚本以退出码 %EXITCODE% 结束。
    pause
)
endlocal
