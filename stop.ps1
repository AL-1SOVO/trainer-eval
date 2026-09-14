<#
.SYNOPSIS
    停止本项目占用的服务（按端口结束进程树）。

.DESCRIPTION
    start.ps1 正常退出时会自己清理；但如果是直接关掉窗口、或脚本被强杀，
    残留的 java / node 进程会继续占着 8080 / 5173。此时运行本脚本清理。

.PARAMETER Ports
    要清理的端口，默认 8080（后端）与 5173（前端）。
#>
[CmdletBinding()]
param([int[]]$Ports = @(8080, 5173))

$ErrorActionPreference = "Continue"

Write-Host "=== 停止本项目服务 ===" -ForegroundColor Cyan

$found = $false
foreach ($port in $Ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Host "  端口 $port : 未被占用" -ForegroundColor Gray
        continue
    }
    $found = $true
    foreach ($pid2 in ($conns | Select-Object -ExpandProperty OwningProcess -Unique)) {
        $proc = Get-Process -Id $pid2 -ErrorAction SilentlyContinue
        $name = if ($proc) { $proc.ProcessName } else { "未知" }
        Write-Host "  端口 $port : 结束 PID $pid2（$name）及其子进程" -ForegroundColor Yellow
        # /T 必须带上：java 与 vite 都是被 mvn / npm 派生出来的子进程
        & taskkill /PID $pid2 /T /F 2>&1 | Out-Null
    }
}

if (-not $found) {
    Write-Host "  没有需要停止的服务。" -ForegroundColor Green
} else {
    Write-Host "  完成。" -ForegroundColor Green
}
