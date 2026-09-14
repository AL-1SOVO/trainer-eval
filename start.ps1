<#
.SYNOPSIS
    企业培训与智能考核系统 —— 一键启动脚本（Windows / PowerShell 5.1+）

.DESCRIPTION
    依次完成：环境自检 → 数据库自举 → 启动后端(8080) → 启动前端(5173) → 就绪等待 → 打印访问信息。
    脚本只做"检查 + 启动 + 按需建表"，不改业务数据。

.PARAMETER JavaHome
    JDK 21 安装目录。默认 E:\java（本机系统默认 java 是 1.8，必须显式指定）。

.PARAMETER MavenCmd
    mvn.cmd 路径。默认 C:\Program Files\apache-maven-3.9.9\bin\mvn.cmd。

.PARAMETER ApiKey
    DeepSeek API Key。不传则依次读 $env:DEEPSEEK_API_KEY → 项目根 .env.local。
    缺失时不阻断启动（登录/考试/阅卷/报表均可用），仅 AI 主观题批改与学习助手不可用。

.PARAMETER BackendOnly / FrontendOnly
    只启动其中一端。

.PARAMETER Online
    强制 Maven 在线构建。默认离线(-o)：依赖已在本机 ~/.m2 缓存，启动更快且不依赖网络。

.PARAMETER Reindex
    启动后强制调用 /api/v1/rag/reindex 重建切块。默认仅在 rag_chunk 为空时自动执行。

.PARAMETER Restart
    若 8080 / 5173 已被占用，先结束占用进程再启动（默认提示并退出，避免误杀）。

.EXAMPLE
    .\start.cmd
    .\start.ps1 -Restart
    .\start.ps1 -ApiKey "sk-xxxx" -Reindex
#>
[CmdletBinding()]
param(
    [string]$JavaHome = "E:\java",
    [string]$MavenCmd = "C:\Program Files\apache-maven-3.9.9\bin\mvn.cmd",
    [string]$ApiKey,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$Online,
    [switch]$Reindex,
    [switch]$Restart
)

# 本脚本大量调用外部命令（mysql / mvn / npm / taskkill），它们会把警告写到 stderr。
# 若用 Stop，PowerShell 会把原生 stderr 当成终止错误而中断脚本，因此这里用 Continue，
# 并对关键步骤显式检查退出码。
$ErrorActionPreference = "Continue"
$OutputEncoding = [System.Text.Encoding]::UTF8

# ---------------------------------------------------------------- 路径与常量
$Root         = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerDir    = Join-Path $Root "trainer-eval-server"
$WebDir       = Join-Path $Root "trainer-eval-web"
# 日志统一放在后端工程目录下。原因：Spring Boot 的 logging.file.name 用相对路径写这里，
# 而项目绝对路径含空格（"D:\c program\..."），把绝对路径塞进命令行容易被拆断。
$LogDir       = Join-Path $ServerDir "logs"
$RagSqlDir    = Join-Path $ServerDir "src\main\resources\rag"

$BackendPort  = 8080
$FrontendPort = 5173
$DbHost = "127.0.0.1"; $DbPort = 3306; $DbName = "trainer_eval"
if ($env:MYSQL_USERNAME) { $DbUser = $env:MYSQL_USERNAME } else { $DbUser = "root" }
if ($env:MYSQL_PASSWORD) { $DbPass = $env:MYSQL_PASSWORD } else { $DbPass = "123456" }
$RedisHost = "127.0.0.1"; $RedisPort = 6379   # 供提示信息使用

$MysqlExe = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

# ---------------------------------------------------------------- 输出 helper
function Write-Title($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Write-Ok($t)    { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Write-Warn2($t) { Write-Host "  [警告] $t" -ForegroundColor Yellow }
function Write-Err($t)   { Write-Host "  [失败] $t" -ForegroundColor Red }
function Write-Info($t)  { Write-Host "  $t" -ForegroundColor Gray }

function Test-Port {
    param([string]$ComputerName, [int]$Port, [int]$TimeoutMs = 1500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($ComputerName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch { return $false } finally { $client.Close() }
}

function Get-PortOwner {
    param([int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn[0].OwningProcess }
    return $null
}

$script:StartedProcesses = @()

function Stop-Started {
    if ($script:StartedProcesses.Count -eq 0) { return }
    Write-Host ""
    Write-Title "正在停止本次启动的服务"
    foreach ($p in $script:StartedProcesses) {
        if ($p -and -not $p.HasExited) {
            # 必须 taskkill /T 结束整棵进程树：mvn 会派生 java 子进程、npm 会派生 node/vite，
            # 只杀父进程会留下占着端口的孤儿进程，下次启动就报"端口被占用"。
            & taskkill /PID $p.Id /T /F 2>&1 | Out-Null
            Write-Info "已停止 PID $($p.Id)（含子进程）"
        }
    }
    $script:StartedProcesses = @()
}
$script:Failed = $false

# ---------------------------------------------------------------- 0. 配置与密钥
Write-Title "0. 环境配置"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

$EnvFile = Join-Path $Root ".env.local"
if (-not $ApiKey -and $env:DEEPSEEK_API_KEY) { $ApiKey = $env:DEEPSEEK_API_KEY }
if (-not $ApiKey -and (Test-Path $EnvFile)) {
    foreach ($line in Get-Content $EnvFile) {
        $t = $line.Trim()
        if ($t -and -not $t.StartsWith("#") -and $t -match '^\s*(DEEPSEEK_API_KEY|OPENAI_API_KEY)\s*=\s*(.+)$') {
            $ApiKey = $Matches[2].Trim().Trim('"').Trim("'")
            break
        }
    }
}
if ($ApiKey) {
    $env:DEEPSEEK_API_KEY = $ApiKey
    $masked = $ApiKey.Substring(0, [Math]::Min(7, $ApiKey.Length)) + "****"
    Write-Ok "已配置 AI Key（$masked）—— AI 批改与学习助手可用"
} else {
    Write-Warn2 "未找到 DeepSeek API Key：系统可正常启动使用（登录/考试/阅卷/报表），"
    Write-Warn2 "        但 AI 主观题批改与学习助手会报错。"
    Write-Warn2 "        配置方式：设置环境变量 DEEPSEEK_API_KEY，或复制 .env.local.example 为 .env.local 并填入"
    Write-Warn2 "        （.env.local 已在 .gitignore 中，不会入库）"
}

# ---------------------------------------------------------------- 1. 环境自检
Write-Title "1. 环境自检"
$failed = $false

# --- JDK：读 JDK 自带 release 文件判断版本，避免执行 java -version（其输出走 stderr）
$JavaExe = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path $JavaExe)) {
    Write-Err "找不到 JDK：$JavaExe"
    Write-Info "本项目需要 JDK 21。可用 -JavaHome 指定，例如：.\start.ps1 -JavaHome 'D:\jdk-21'"
    $failed = $true
} else {
    $major = $null
    $releaseFile = Join-Path $JavaHome "release"
    if (Test-Path $releaseFile) {
        $m = Select-String -Path $releaseFile -Pattern 'JAVA_VERSION="(\d+)' -ErrorAction SilentlyContinue
        if ($m) { $major = [int]$m.Matches[0].Groups[1].Value }
    }
    if ($null -eq $major) {
        # 回退：执行 java -version（输出在 stderr，用 Out-String 兜住）
        $verText = (& $JavaExe -version 2>&1 | Out-String)
        if ($verText -match 'version "(\d+)') { $major = [int]$Matches[1] }
    }
    if ($null -eq $major) { Write-Warn2 "无法解析 JDK 版本，继续尝试启动：$JavaHome" }
    elseif ($major -ge 21) { Write-Ok "JDK $major（$JavaHome）" }
    else { Write-Err "JDK 版本过低：需要 21，实际 $major（$JavaHome）"; $failed = $true }
}

# --- Maven
if (-not $FrontendOnly) {
    if (Test-Path $MavenCmd) { Write-Ok "Maven（$MavenCmd）" }
    else {
        $alt = "E:\IntelliJ IDEA 2024.3.5\plugins\maven\lib\maven3\bin\mvn.cmd"
        if (Test-Path $alt) { $MavenCmd = $alt; Write-Warn2 "默认 Maven 不存在，改用 IntelliJ 自带：$alt" }
        else { Write-Err "找不到 mvn.cmd：$MavenCmd（可用 -MavenCmd 指定）"; $failed = $true }
    }
}

# --- MySQL
if (Test-Path $MysqlExe) { Write-Ok "MySQL 客户端（$MysqlExe）" }
else { Write-Warn2 "找不到 mysql.exe：$MysqlExe（将跳过数据库自举）" }

if (Test-Port $DbHost $DbPort) { Write-Ok "MySQL 服务可达（${DbHost}:${DbPort}）" }
else {
    Write-Err "MySQL 不可达（${DbHost}:${DbPort}）"
    Write-Info "启动方式：net start MySQL"
    $failed = $true
}

if (Test-Port $RedisHost $RedisPort) { Write-Ok "Redis 可达（${RedisHost}:${RedisPort}）" }
else {
    Write-Err "Redis 不可达（${RedisHost}:${RedisPort}）—— 登录与 JWT 鉴权依赖 Redis"
    Write-Info "启动方式：net start Redis"
    $failed = $true
}

# --- Node / npm
if (-not $BackendOnly) {
    $npmCmd = Get-Command npm -ErrorAction SilentlyContinue
    if ($npmCmd) { Write-Ok "npm（$($npmCmd.Source)）" } else { Write-Err "找不到 npm，请安装 Node.js 20+"; $failed = $true }
    if (-not (Test-Path (Join-Path $WebDir "node_modules"))) {
        Write-Warn2 "前端 node_modules 不存在，稍后将自动执行 npm install"
    }
}

# --- 端口占用
$portChecks = @()
if (-not $FrontendOnly) { $portChecks += @{ P = $BackendPort;  N = "后端" } }
if (-not $BackendOnly)  { $portChecks += @{ P = $FrontendPort; N = "前端" } }
foreach ($pc in $portChecks) {
    $owner = Get-PortOwner $pc.P
    if ($owner) {
        if ($Restart) {
            Write-Warn2 "$($pc.N)端口 $($pc.P) 被 PID $owner 占用，按 -Restart 结束它"
            & taskkill /PID $owner /T /F 2>&1 | Out-Null
            Start-Sleep -Milliseconds 800
        } else {
            Write-Err "$($pc.N)端口 $($pc.P) 已被 PID $owner 占用"
            Write-Info "若确认是旧实例：.\start.ps1 -Restart   或   taskkill /PID $owner /T /F"
            $failed = $true
        }
    } else { Write-Ok "$($pc.N)端口 $($pc.P) 空闲" }
}

if ($failed) {
    Write-Host ""
    Write-Err "环境自检未通过，已终止。请按上面的提示处理后重试。"
    exit 1
}

# ---------------------------------------------------------------- 2. 数据库自举
function Invoke-Mysql {
    param([string]$Sql, [switch]$NoDatabase)
    # 用 MYSQL_PWD 传口令：既避免命令行明文，也避免 mysql 输出
    # "Using a password on the command line interface can be insecure" 警告
    $env:MYSQL_PWD = $DbPass
    $mysqlArgs = @("-h", $DbHost, "-P", "$DbPort", "-u", $DbUser,
                   "--default-character-set=utf8mb4", "--skip-column-names")
    if (-not $NoDatabase) { $mysqlArgs += $DbName }
    $mysqlArgs += @("-e", $Sql)
    $out = & $MysqlExe @mysqlArgs 2>&1
    return @{ Output = ($out | Where-Object { $_ -notmatch 'Using a password' }); Code = $LASTEXITCODE }
}

Write-Title "2. 数据库自举"
if (Test-Path $MysqlExe) {
    $probe = Invoke-Mysql -Sql "SELECT 1;" -NoDatabase
    if ($probe.Code -ne 0) {
        Write-Err "无法连接 MySQL（账号 $DbUser）。输出：$($probe.Output -join ' ')"
        Write-Info "账号/口令可用环境变量覆盖：环境变量 MYSQL_USERNAME / MYSQL_PASSWORD"
        exit 1
    }
    Write-Ok "MySQL 连接成功（${DbUser}@${DbHost}:${DbPort}）"

    $dbExists = (Invoke-Mysql -Sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$DbName';" -NoDatabase).Output
    if (-not $dbExists) {
        Write-Err "数据库 $DbName 不存在。请先建库并导入基础结构："
        Write-Info "  & '$MysqlExe' -u$DbUser -e `"CREATE DATABASE $DbName DEFAULT CHARSET utf8mb4;`""
        Write-Info "  & '$MysqlExe' -u$DbUser $DbName -e `"source $ServerDir\src\main\resources\schema.sql`""
        exit 1
    }
    Write-Ok "数据库 $DbName 存在"

    # RAG 表：仅在缺失时创建（不覆盖已有切块与 Golden 题集）
    $ragExists = (Invoke-Mysql -Sql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DbName' AND TABLE_NAME='rag_chunk';").Output
    if (-not $ragExists) {
        $schemaFile = Join-Path $RagSqlDir "rag_schema.sql"
        if (Test-Path $schemaFile) {
            Write-Info "RAG 表缺失，正在创建（rag_schema.sql）..."
            Get-Content $schemaFile -Raw -Encoding UTF8 |
                & $MysqlExe -h $DbHost -P $DbPort -u $DbUser --default-character-set=utf8mb4 2>&1 |
                Where-Object { $_ -notmatch 'Using a password' } | Out-Null
            if ($LASTEXITCODE -eq 0) { Write-Ok "RAG 表已创建" } else { Write-Warn2 "RAG 建表失败，请手动执行 rag_schema.sql" }

            $seedFile = Join-Path $RagSqlDir "rag_golden_seed.sql"
            $goldenCount = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM rag_golden_item;").Output
            if ((Test-Path $seedFile) -and -not $goldenCount) {
                Get-Content $seedFile -Raw -Encoding UTF8 |
                    & $MysqlExe -h $DbHost -P $DbPort -u $DbUser --default-character-set=utf8mb4 2>&1 |
                    Where-Object { $_ -notmatch 'Using a password' } | Out-Null
                if ($LASTEXITCODE -eq 0) { Write-Ok "Golden 题集种子已导入" }
            }
        } else { Write-Warn2 "未找到 $schemaFile，跳过建表" }
    } else {
        Write-Ok "RAG 表已存在（保留现有切块与题集）"
    }
}

# ---------------------------------------------------------------- 3~7. 启动
# backend.log 由 Spring Boot 自己写（Logback 每条即时 flush），可实时查看；
# backend-mvn.log 是 Maven 自身输出，仅在应用日志缺失时兜底。
# 之所以不依赖 stdout 重定向：JVM / Node 的 stdout 被重定向到文件时会缓冲，
# 实测 45 秒后文件仍为 0 字节，排查启动失败时会被误导。
$backendAppLog = Join-Path $LogDir "backend.log"
$backendLog    = Join-Path $LogDir "backend-mvn.log"
$backendErr    = Join-Path $LogDir "backend-mvn.err.log"
$frontendLog   = Join-Path $LogDir "frontend.log"
$frontendErr   = Join-Path $LogDir "frontend.err.log"

try {
    # ------------------------------------------------------------ 3. 后端
    if (-not $FrontendOnly) {
        Write-Title "3. 启动后端（端口 $BackendPort）"
        $mvnArgs = @("-DskipTests")
        if (-not $Online) { $mvnArgs += "-o" }
        # 让应用把日志写到文件（该值不含空格，可安全内联进命令行）
        $mvnArgs += "-Dspring-boot.run.arguments=--logging.file.name=logs/backend.log"
        $mvnArgs += "spring-boot:run"

        # 用 cmd /c 包一层，让 JAVA_HOME 只在子进程生效，不污染当前会话
        $cmdLine = "set `"JAVA_HOME=$JavaHome`" && `"$MavenCmd`" $($mvnArgs -join ' ')"
        Write-Info "应用日志：$backendAppLog（实时写入）"

        $backend = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmdLine `
            -WorkingDirectory $ServerDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $backendLog -RedirectStandardError $backendErr
        $script:StartedProcesses += $backend
        Write-Ok "后端进程已拉起（PID $($backend.Id)），等待就绪（首次编译可能较久）..."

        $ready = $false
        for ($i = 1; $i -le 120; $i++) {
            Start-Sleep -Seconds 1
            if ($backend.HasExited) { break }
            if (Test-Port "127.0.0.1" $BackendPort 400) { $ready = $true; break }
            if ($i % 10 -eq 0) { Write-Host "." -NoNewline }
        }
        Write-Host ""
        if ($ready) {
            Start-Sleep -Seconds 2
            Write-Ok "后端已就绪：http://localhost:$BackendPort"
        } else {
            Write-Err "后端未在预期时间内就绪（退出码：$($backend.ExitCode)）"
            $shown = $false
            if ((Test-Path $backendAppLog) -and (Get-Item $backendAppLog).Length -gt 0) {
                Write-Info "应用日志末尾（$backendAppLog）："
                Get-Content $backendAppLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Info "    $_" }
                $shown = $true
            }
            if ((Test-Path $backendErr) -and (Get-Item $backendErr).Length -gt 0) {
                Write-Info "Maven 错误流（$backendErr）："
                Get-Content $backendErr -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Info "    $_" }
                $shown = $true
            }
            if (-not $shown -and (Test-Path $backendLog)) {
                Write-Info "Maven 输出末尾（$backendLog）："
                Get-Content $backendLog -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Info "    $_" }
            }
            Write-Info "提示：若出现依赖缺失/offline 相关报错，请加 -Online 重试。"
            throw "后端启动失败"
        }
    }

    # ------------------------------------------------------------ 4. 前端
    if (-not $BackendOnly) {
        Write-Title "4. 启动前端（端口 $FrontendPort）"
        if (-not (Test-Path (Join-Path $WebDir "node_modules"))) {
            Write-Info "安装前端依赖（npm install，首次较慢）..."
            Push-Location $WebDir
            & npm install 2>&1 | Select-Object -Last 5 | ForEach-Object { Write-Info "    $_" }
            Pop-Location
        }
        Write-Info "日志：$frontendLog"
        $frontend = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev" `
            -WorkingDirectory $WebDir -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $frontendLog -RedirectStandardError $frontendErr
        $script:StartedProcesses += $frontend
        Write-Ok "前端进程已拉起（PID $($frontend.Id)），等待就绪..."

        $feReady = $false
        for ($i = 1; $i -le 60; $i++) {
            Start-Sleep -Seconds 1
            if ($frontend.HasExited) { break }
            if (Test-Port "127.0.0.1" $FrontendPort 400) { $feReady = $true; break }
        }
        if ($feReady) { Write-Ok "前端已就绪：http://localhost:$FrontendPort" }
        else {
            Write-Warn2 "前端未在预期时间内就绪，请查看 $frontendLog"
            Get-Content $frontendLog -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Info "    $_" }
        }
    }

    # ------------------------------------------------------------ 5. RAG 切块
    if (-not $FrontendOnly) {
        $needReindex = $false
        if ($Reindex) { $needReindex = $true }
        elseif (Test-Path $MysqlExe) {
            $cnt = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM rag_chunk;").Output
            if (-not $cnt -or [int]$cnt -eq 0) { $needReindex = $true }
        }
        if ($needReindex) {
            Write-Title "5. 重建 RAG 切块"
            try {
                $login = Invoke-RestMethod -Method Post `
                    -Uri "http://127.0.0.1:$BackendPort/api/v1/auth/login" `
                    -ContentType "application/json" `
                    -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
                $headers = @{ Authorization = "Bearer $($login.data.token)" }
                $res = Invoke-RestMethod -Method Post `
                    -Uri "http://127.0.0.1:$BackendPort/api/v1/rag/reindex" `
                    -Headers $headers -TimeoutSec 90
                Write-Ok "切块完成：$($res.data.materialsIndexed) 条资料已建索引"
            } catch {
                Write-Warn2 "自动重建切块失败（不影响启动）：$($_.Exception.Message)"
                Write-Info "可登录后手动调用：POST /api/v1/rag/reindex"
            }
        } else {
            Write-Ok "RAG 切块已存在，跳过重建（需要重建请加 -Reindex）"
        }
    }

    # ------------------------------------------------------------ 6. 汇总
    Write-Host ""
    Write-Host "================================================================" -ForegroundColor Green
    Write-Host "  启动完成" -ForegroundColor Green
    Write-Host "================================================================" -ForegroundColor Green
    if (-not $FrontendOnly) {
        Write-Host "  前端页面    http://localhost:$FrontendPort" -ForegroundColor White
        Write-Host "  后端接口    http://localhost:$BackendPort/api/v1" -ForegroundColor White
    }
    Write-Host ""
    Write-Host "  测试账号    admin / admin123   （管理员，含全部角色）" -ForegroundColor Gray
    Write-Host "  日志目录    $LogDir" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  RAG 调试接口（需 MANAGER 令牌）" -ForegroundColor Gray
    Write-Host "    POST /api/v1/rag/search        检索调试（看命中通道与分数）" -ForegroundColor Gray
    Write-Host "    POST /api/v1/rag/eval?topK=5   运行 Golden 评测" -ForegroundColor Gray
    Write-Host "    GET  /api/v1/rag/traces        最近检索轨迹" -ForegroundColor Gray
    Write-Host "    POST /api/v1/rag/reindex       重建切块" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  按 Ctrl+C 停止本次启动的服务；也可在另一个窗口运行 .\stop.cmd" -ForegroundColor Yellow
    Write-Host "================================================================" -ForegroundColor Green

    # ------------------------------------------------------------ 7. 常驻等待
    while ($true) {
        Start-Sleep -Seconds 2
        foreach ($p in $script:StartedProcesses) {
            if ($p.HasExited) {
                Write-Warn2 "进程 PID $($p.Id) 已退出（退出码 $($p.ExitCode)），请检查日志。"
            }
        }
        $script:StartedProcesses = @($script:StartedProcesses | Where-Object { -not $_.HasExited })
        if ($script:StartedProcesses.Count -eq 0) {
            Write-Warn2 "所有服务均已退出。"
            break
        }
    }
}
catch {
    Write-Host ""
    Write-Err $_.Exception.Message
    Write-Host ""
    Write-Host "  本窗口会保留，可向上滚动查看完整错误信息。" -ForegroundColor Yellow
    $script:Failed = $true
}
finally {
    Stop-Started
}

# 必须显式设置退出码。否则被 try/catch 捕获的异常会以退出码 0 结束：
# 调用方（start.cmd / CI）会误判为成功，双击运行时窗口也会立刻关闭、
# 错误信息一闪而过 —— 这正是"窗口一闪而过"的原因之一。
if ($script:Failed) { exit 1 }
exit 0
