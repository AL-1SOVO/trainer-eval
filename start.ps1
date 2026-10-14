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

.PARAMETER MysqlExe
    mysql.exe 路径。不传则依次探测 $env:MYSQL_EXE → PATH → 常见安装目录
    （官网 ZIP 解压式 / MySQL Installer / ProgramData 版本目录）。
    找不到时脚本会直接失败：缺表会让检索接口 500、Golden 评测无法运行，
    这种失败必须响亮地报出来，不能静默跳过自举。

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

.PARAMETER SkipMimirQ
    跳过 MimirQ 知识库容器的启动。检索会自动降级为本地混合检索。

.PARAMETER MimirQBuild
    启动 MimirQ 时强制重建镜像。改了 mimirq/ 下的源码或依赖后才需要，日常启动不用。

.EXAMPLE
    .\start.cmd
    .\start.ps1 -Restart
    .\start.ps1 -ApiKey "sk-xxxx" -Reindex
#>
[CmdletBinding()]
param(
    # 解析顺序：显式传入 → 环境变量 JAVA_HOME → 本机默认 E:\java。
    # 之前这里写死 E:\java，别人克隆下来必然跑不起来（而 JDK 是唯一硬性依赖）。
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "E:\java" }),
    # 同理：优先用本机已知路径，找不到就留空，后面会回退到 PATH 上的 mvn。
    [string]$MavenCmd = $(if (Test-Path "C:\Program Files\apache-maven-3.9.9\bin\mvn.cmd") { "C:\Program Files\apache-maven-3.9.9\bin\mvn.cmd" } else { "" }),
    # 同上：MySQL 客户端位置因安装方式而异（官方 ZIP / 安装包 / 包管理器），
    # 这里同样不写死，留空则按下述顺序自动探测。
    [string]$MysqlExe = "",
    [string]$ApiKey,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$Online,
    [switch]$Reindex,
    [switch]$Restart,
    [switch]$SkipMimirQ,
    [switch]$MimirQBuild
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

# MySQL 客户端探测顺序：显式 -MysqlExe → $env:MYSQL_EXE → PATH → 常见安装位置。
#
# 这里曾经硬编码 "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"，
# 而该路径在非安装包安装（官方 ZIP 解压 / 包管理器）的机器上并不存在。
# 后果不是报错，而是下面数据库自举整段被"找不到就跳过"的软失败静默吞掉：
# 脚本一路打印"启动成功"，而 rag_chunk / rag_retrieval_trace / rag_golden_item /
# rag_eval_run / agent_message 五张表从未被创建 —— 检索接口永远 500，
# 评测功能永远跑不起来，且没有任何一处提示指向真正的原因。
# 教训：把一个"可选步骤"做成静默软失败，等于把核心功能的缺失伪装成启动正常。
if (-not $MysqlExe) { $MysqlExe = $env:MYSQL_EXE }
if (-not $MysqlExe) {
    $onPath = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($onPath) { $MysqlExe = $onPath.Source }
}
if (-not $MysqlExe) {
    $probe = @(
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 9.0\bin\mysql.exe",
        "E:\MySQL\mysql-8.0.46-winx64\bin\mysql.exe",
        "D:\MySQL\bin\mysql.exe"
    )
    # 兜底再宽扫一层：覆盖 MySQL Installer 的 ProgramData 版本目录，
    # 以及任意盘符下解压式安装的 E:\MySQL\mysql-8.x.y-winx64 形态。
    $probe += @(
        Get-ChildItem "C:\ProgramData\MySQL", "E:\MySQL", "D:\MySQL" -Filter mysql.exe -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 5 -ExpandProperty FullName
    )
    foreach ($p in $probe) { if ($p -and (Test-Path $p)) { $MysqlExe = $p; break } }
}

# ---------------------------------------------------------------- 输出 helper
function Write-Title($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Write-Ok($t)    { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Write-Warn2($t) { Write-Host "  [警告] $t" -ForegroundColor Yellow }
function Write-Err($t)   { Write-Host "  [失败] $t" -ForegroundColor Red }
function Write-Info($t)  { Write-Host "  $t" -ForegroundColor Gray }

function Test-Port {
    param([string]$ComputerName, [int]$Port, [int]$TimeoutMs = 1500)
    # 这里不能用 New-Object System.Net.Sockets.TcpClient：
    # 它建的是 IPv4 套接字，拿 "::1" 去连会直接抛「发现的地址或指定的地址与
    # 套接字地址族都不匹配」，拿解析出 IPv6 的 "localhost" 去连也一样失败
    # （表现为等满超时才返回 false）。而 Vite 的 server.host 默认是 localhost，
    # 在 Windows 上只监听 ::1 —— 于是"前端已就绪"被误报成"未在预期时间内就绪"。
    # 正确做法：先把主机名解析成全部地址，再逐个用匹配的地址族去连。
    $addresses = $null
    try { $addresses = [System.Net.Dns]::GetHostAddresses($ComputerName) } catch { return $false }
    if (-not $addresses -or $addresses.Count -eq 0) { return $false }
    foreach ($addr in $addresses) {
        $sock = $null
        try {
            $sock = New-Object System.Net.Sockets.Socket(
                $addr.AddressFamily,
                [System.Net.Sockets.SocketType]::Stream,
                [System.Net.Sockets.ProtocolType]::Tcp)
            $iar = $sock.BeginConnect($addr, $Port, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
                $sock.EndConnect($iar)
                if ($sock.Connected) { return $true }
            }
        } catch {
            # 这个地址连不上就换下一个，不在这里报错
        } finally {
            if ($sock) { $sock.Close() }
        }
    }
    return $false
}

# 有了上面按地址族逐个尝试的 Test-Port，单次调用其实已经能覆盖 IPv4/IPv6。
# 这层仍然保留：万一某个主机名解析不出地址、或只有字面量地址可用，
# 多个候选名一起试比逐个排查便宜得多。Vite 只监听 ::1 就是踩过的坑。
function Test-PortAny {
    param([string[]]$ComputerNames, [int]$Port, [int]$TimeoutMs = 400)
    foreach ($n in $ComputerNames) {
        if (Test-Port $n $Port $TimeoutMs) { return $true }
    }
    return $false
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

# MimirQ 知识库配置。不注入的话后端拿不到 key，每次检索都要白等一次连接再降级到本地 ——
# 服务其实在跑，却一直用不上。
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith("#")) { continue }
        if ($t -match '^\s*(MIMIRQ_ENABLED|MIMIRQ_BASE_URL|MIMIRQ_API_KEY|MIMIRQ_INGEST_ENABLED|MIMIRQ_USERNAME|MIMIRQ_PASSWORD)\s*=\s*(.+)$') {
            $kvKey = $Matches[1]
            $kvVal = $Matches[2].Trim().Trim('"').Trim("'")
            Set-Item -Path "env:$kvKey" -Value $kvVal
        }
    }
}
if ($env:MIMIRQ_API_KEY) {
    $mqBase = if ($env:MIMIRQ_BASE_URL) { $env:MIMIRQ_BASE_URL } else { "http://localhost:8000" }
    Write-Ok "已配置 MimirQ（$mqBase，key 长度 $($env:MIMIRQ_API_KEY.Length)）—— 检索走知识库服务"
    # 配了 key 但服务没起来，是最不容易察觉的一种坏状态：后端每次检索都要先连一次
    # MimirQ、等它超时、再降级到本地，接口返回 degraded=true、熔断器随后打开，
    # 而启动日志里从上到下全是 [OK]。这里主动探一次，把这个"看起来正常"戳破。
    $mqHost = "localhost"; $mqPort = 8000
    if ($mqBase -match '^https?://([^/:]+)(?::(\d+))?') {
        $mqHost = $Matches[1]
        if ($Matches[2]) { $mqPort = [int]$Matches[2] }
    }
    if (-not (Test-Port $mqHost $mqPort 1500)) {
        Write-Warn2 "MimirQ 已配置但 ${mqHost}:${mqPort} 连不上：每次检索都会先失败再降级"
        Write-Warn2 "        表现为接口返回 degraded=true、熔断器打开、检索多耗约 0.5s（首次 3 次）"
        Write-Warn2 "        要干净演示：启动 Docker Desktop 后不加 -SkipMimirQ 重跑本脚本；"
        Write-Warn2 "        或临时在 .env.local 里注释掉 MIMIRQ_API_KEY，改走本地混合检索"
    }
} else {
    Write-Info "未配置 MIMIRQ_API_KEY：检索使用内置的本地混合检索（功能正常，只是不调外部知识库）。"
    Write-Info "        接入方式：在 .env.local 里加 MIMIRQ_API_KEY=<MimirQ 的 Dify key>"
}
# 写入侧（自动灌库）：检索只要 Key，写入要账号密码换 JWT，所以单独判断并单独提示。
# 少了它不会报错，但"内训师上传 → MimirQ 侧也能搜到"就只剩本地方案。
if ($env:MIMIRQ_INGEST_ENABLED -and $env:MIMIRQ_INGEST_ENABLED -notin @('false', '0') `
        -and $env:MIMIRQ_USERNAME -and $env:MIMIRQ_PASSWORD) {
    Write-Ok "已配置 MimirQ 自动灌库（账号 $($env:MIMIRQ_USERNAME)）—— 上传资料会自动同步进知识库"
} else {
    Write-Info "未配置 MimirQ 自动灌库（缺 MIMIRQ_USERNAME / MIMIRQ_PASSWORD）："
    Write-Info "        资料仍会切块进本地索引、员工能搜到；只是不会同步进 MimirQ 知识库。"
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
    if ($MavenCmd -and (Test-Path $MavenCmd)) {
        Write-Ok "Maven（$MavenCmd）"
    } elseif (-not $MavenCmd -and (Get-Command mvn -ErrorAction SilentlyContinue)) {
        # 没指定、也没有本机固定路径：回退到 PATH 上的 mvn（Linux/macOS 或标准安装都走这里）
        $MavenCmd = (Get-Command mvn).Source
        Write-Ok "Maven（$MavenCmd，来自 PATH）"
    } else {
        $alt = "E:\IntelliJ IDEA 2024.3.5\plugins\maven\lib\maven3\bin\mvn.cmd"
        if (Test-Path $alt) { $MavenCmd = $alt; Write-Warn2 "默认 Maven 不存在，改用 IntelliJ 自带：$alt" }
        else {
            Write-Err "找不到 Maven：$MavenCmd"
            Write-Info "可用 -MavenCmd 指定 mvn.cmd 的完整路径，或把 mvn 加入 PATH"
            $failed = $true
        }
    }
}

# --- MySQL
# 这里必须是硬失败，不能是"找不到就跳过自举"。
# 软失败的代价在这一版之前真实发生过：脚本一路打印"启动成功"，
# 而 rag_chunk / rag_retrieval_trace / rag_golden_item / rag_eval_run /
# agent_message 五张表从未被创建 —— 检索接口永远 500、评测永远跑不起来，
# 且没有任何一处输出指向真正的原因。把核心功能的缺失伪装成启动正常，
# 比直接报错难查得多。
if (Test-Path $MysqlExe) { Write-Ok "MySQL 客户端（$MysqlExe）" }
else {
    Write-Err "找不到 mysql.exe —— 无法执行数据库自举（缺表会导致检索接口 500、评测不可用）"
    Write-Info "任选一种方式指定（探测顺序：-MysqlExe → `$env:MYSQL_EXE → PATH → 常见安装目录）："
    Write-Info "  1) .\start.ps1 -MysqlExe `"E:\MySQL\mysql-8.0.46-winx64\bin\mysql.exe`""
    Write-Info "  2) `$env:MYSQL_EXE = 'E:\MySQL\mysql-8.0.46-winx64\bin\mysql.exe'"
    Write-Info "  3) 把 mysql.exe 所在目录加入 PATH"
    $failed = $true
}

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

# 执行一个 .sql 文件。刻意用 cmd 的 `<` 重定向而不是 PowerShell 管道：
# 管道会把文本按 PowerShell 的编码重新编码一遍，含中文的语料在非 UTF-8 代码页下
# 会被写成乱码入库；`<` 把文件字节原样交给 mysql，配合 --default-character-set=utf8mb4
# 才与文件实际编码一致。另外注意：本文件里的 .sql 有的自带 `USE trainer_eval;`、
# 有的没有（db\*.sql 全部没有），没有的必须显式传库名，否则报 1046 No database selected。
function Import-SqlFile([string]$Path, [switch]$NoDatabase) {
    $env:MYSQL_PWD = $DbPass
    $dbArg = if ($NoDatabase) { "" } else { " `"$DbName`"" }
    $line  = "`"$MysqlExe`" -h $DbHost -P $DbPort -u $DbUser --default-character-set=utf8mb4$dbArg < `"$Path`""
    $out   = & cmd.exe /c $line 2>&1
    return @{
        Output = ($out | Where-Object { $_ -notmatch 'Using a password' })
        Code   = $LASTEXITCODE
    }
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

    # ---- RAG 表：仅在缺失时创建（不覆盖已有切块与 Golden 题集）
    $ragExists = (Invoke-Mysql -Sql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DbName' AND TABLE_NAME='rag_chunk';").Output
    if (-not $ragExists) {
        $schemaFile = Join-Path $RagSqlDir "rag_schema.sql"
        if (Test-Path $schemaFile) {
            Write-Info "RAG 表缺失，正在创建（rag_schema.sql）..."
            $r = Import-SqlFile $schemaFile
            if ($r.Code -eq 0) { Write-Ok "RAG 表已创建" }
            else { Write-Warn2 "RAG 建表失败：$($r.Output -join ' ')" }
        } else { Write-Warn2 "未找到 $schemaFile，跳过建表" }
    } else {
        Write-Ok "RAG 表已存在（保留现有切块与题集）"
    }

    # ---- 教学资料语料：这是必修项，不是可选项。
    # rag_chunk 由 tra_material 切块而来 —— 资料为空则切块为 0，检索恒返回空，
    # Golden 评测恒为 0 分。原仓库的 seed.sql / test-data.sql 都没有 tra_material
    # 的 INSERT，而被 golden-rag.jsonl 指定要命中的"资料 3 / 资料 4"只存在于原作者
    # 本机数据库，从未提交。结果是任何人 clone 后都复现不出检索与评测，
    # 却看不出少了什么。rag_material_seed.sql 就是为了补上这个缺口。
    $matCount = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM tra_material;").Output
    if (-not $matCount -or "$matCount" -eq "0") {
        $matSeed = Join-Path $RagSqlDir "rag_material_seed.sql"
        if (Test-Path $matSeed) {
            Write-Info "教学资料为空，导入语料种子（rag_material_seed.sql）..."
            $r = Import-SqlFile $matSeed
            if ($r.Code -eq 0) { Write-Ok "语料已导入（13 份资料，含 Golden 题集引用的资料 3 / 4）" }
            else { Write-Warn2 "语料导入失败：$($r.Output -join ' ')" }
        } else { Write-Warn2 "未找到 $matSeed —— 检索将一直返回空结果" }
    } else {
        Write-Ok "教学资料已存在（$matCount 份，保留现状）"
    }

    # ---- Golden 题集
    $goldenCount = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM rag_golden_item;").Output
    if (-not $goldenCount -or "$goldenCount" -eq "0") {
        $seedFile = Join-Path $RagSqlDir "rag_golden_seed.sql"
        if (Test-Path $seedFile) {
            Write-Info "Golden 题集为空，导入种子..."
            $r = Import-SqlFile $seedFile
            if ($r.Code -eq 0) { Write-Ok "Golden 题集种子已导入" }
            else { Write-Warn2 "Golden 导入失败：$($r.Output -join ' ')" }
        }
    } else {
        Write-Ok "Golden 题集已存在（$goldenCount 条，保留现状）"
    }
}

# ---------------------------------------------------------------- 2.5 MimirQ 知识库
# 设计取舍：**知识库起不来不能挡住培训系统启动**。
# MimirQ 是派生能力（检索质量增强），后端在它不可用时会自动降级到本地混合检索，
# 所以这里失败只警告、不 exit。反过来，如果把它做成硬依赖，
# 一次 Docker 没启动就会让"登录/考试/阅卷"这些核心功能也起不来 —— 那是错的优先级。
$MimirQCompose = Join-Path $Root "mimirq\docker\docker-compose.lite.yml"
$MimirQEnv     = Join-Path $Root "mimirq\.env"
if (-not $SkipMimirQ -and (Test-Path $MimirQCompose) -and -not (Test-Path $MimirQEnv)) {
    # mimirq/.env 含密钥、被 git 忽略 —— 全新 clone 后不存在。
    # 不先检查的话，compose 会在容器启动阶段报一堆变量缺失，排查成本高得多。
    Write-Title "2.5 知识库服务（MimirQ）"
    Write-Warn2 "缺少 mimirq\.env：跳过 MimirQ（检索会自动降级为本地混合检索，功能可用）"
    Write-Info "        初始化：Copy-Item .\mimirq\.env.example .\mimirq\.env"
    Write-Info "        必填 SECRET_KEY / INITIAL_ADMIN_* / DIFY_EXTERNAL_KNOWLEDGE_*，详见 mimirq\VENDORED.md"
} elseif (-not $SkipMimirQ -and (Test-Path $MimirQCompose)) {
    Write-Title "2.5 知识库服务（MimirQ）"

    $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $dockerCmd) {
        Write-Warn2 "未找到 docker：跳过 MimirQ。检索会自动降级为本地混合检索（功能可用）"
    } else {
        # docker info 是判断"守护进程是否在跑"最便宜的方式（Docker Desktop 没启动时 compose 会直接报错）
        & docker info 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn2 "Docker 守护进程未运行（Docker Desktop 没启动？）：跳过 MimirQ"
            Write-Info "        启动 Docker Desktop 后重新运行本脚本即可；不影响其它功能。"
        } else {
            $running = & docker ps --filter "name=mimirq-mimirq-api" --format "{{.Names}}" 2>$null
            if ($running) {
                Write-Ok "MimirQ 容器已在运行"
            } else {
                Write-Info "正在启动 MimirQ 容器（首次或改动源码后需要构建，可能数分钟）..."
                # 不用 --build：日常启动走已有镜像，秒级完成。
                # 需要重建（改了 mimirq/ 下的源码或依赖）时用 -MimirQBuild。
                $mqArgs = @("compose", "-f", $MimirQCompose, "up", "-d")
                if ($MimirQBuild) { $mqArgs += "--build" }
                & docker @mqArgs 2>&1 | ForEach-Object { if ($_ -notmatch '^\s*$') { Write-Verbose $_ } }
            }

            # 就绪等待：空库首次建表可能要好几分钟，所以给足时间；超时也只警告
            $mqReady = $false
            for ($i = 0; $i -lt 60; $i++) {
                $code = & curl.exe -s -o NUL -w '%{http_code}' --max-time 5 `
                    "http://127.0.0.1:8000/api/v1/health/ready" 2>$null
                if ($code -eq '200') { $mqReady = $true; break }
                Start-Sleep -Seconds 3
            }
            if ($mqReady) {
                Write-Ok "MimirQ 已就绪（http://127.0.0.1:8000）"

                # 预热：就绪 ≠ 热。MimirQ 的向量模型是懒加载的，第一次检索要现加载，
                # 实测未离线化时可达 18 秒甚至更久，而 Java 侧读超时只有 5 秒 ——
                # 结果是"启动后前几次搜索必然降级"，界面上出现"当前为降级检索"。
                # 这里在启动阶段主动打一次检索，把冷加载的代价挪到启动流程里。
                # 用课程 1 的 knowledge_id；失败也无所谓（模型可能已被加载或映射未配），
                # 所以只吞掉结果、不报错、不阻塞。
                if ($env:MIMIRQ_API_KEY) {
                    Write-Info "正在预热知识库（首次检索需加载向量模型，请稍候）..."
                    $warmBody = '{"knowledge_id":"course-1","query":"warmup","retrieval_setting":{"top_k":1,"score_threshold":0.0,"latency_profile":"fast"}}'
                    $warmFile = Join-Path $env:TEMP "mimirq-warmup.json"
                    try {
                        [System.IO.File]::WriteAllText($warmFile, $warmBody, (New-Object System.Text.UTF8Encoding($false)))
                        $sw = [System.Diagnostics.Stopwatch]::StartNew()
                        & curl.exe -s -o NUL --max-time 120 -X POST `
                            "http://127.0.0.1:8000/api/v1/integrations/dify/retrieval" `
                            -H "Authorization: Bearer $($env:MIMIRQ_API_KEY)" `
                            -H "Content-Type: application/json" --data-binary "@$warmFile" 2>$null | Out-Null
                        $sw.Stop()
                        Write-Ok "知识库预热完成（$([Math]::Round($sw.Elapsed.TotalSeconds,1))s）"
                        Remove-Item $warmFile -Force -ErrorAction SilentlyContinue
                    } catch {
                        Write-Info "        预热未成功，首次搜索可能稍慢（不影响功能）"
                    }
                }
            } else {
                Write-Warn2 "MimirQ 未在 180s 内就绪：本次检索走本地混合检索"
                Write-Info "        排查：docker compose -f `"$MimirQCompose`" logs --tail 80 mimirq-api"
            }
        }
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
            # 不能用 127.0.0.1：见 Test-PortAny 的注释（Vite 只监听 ::1）
            if (Test-PortAny @("localhost", "127.0.0.1", "::1") $FrontendPort 400) { $feReady = $true; break }
        }
        if ($feReady) { Write-Ok "前端已就绪：http://localhost:$FrontendPort" }
        else {
            Write-Warn2 "前端未在预期时间内就绪，请查看 $frontendLog"
            Get-Content $frontendLog -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Info "    $_" }
        }
    }

    # ------------------------------------------------------------ 5. RAG 切块与 Golden 题集
    if (-not $FrontendOnly) {
        # Golden 题的权威来源是 golden-rag.jsonl（每行一题，以 # 开头为注释）。
        # rag_golden_seed.sql 只是数据库侧的子集（7 条），与 jsonl 的 20 条不一致：
        # 全新 clone 走一遍自举后 itemCount 会显示 7，而不是 README 与 jsonl 声称的 20，
        # 评测结果看似正常、实际少测了 13 题。所以这里以 jsonl 的行数为基准，
        # 库里不足就调 /rag/golden/import（覆盖式导入）补齐。
        $jsonl       = Join-Path $RagSqlDir "golden-rag.jsonl"
        $goldenTotal = 0
        if (Test-Path $jsonl) {
            $goldenTotal = @(Get-Content $jsonl -Encoding UTF8 |
                Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith('#') }).Count
        }

        $needReindex = $false
        $needGolden  = $false
        if ($Reindex) { $needReindex = $true }
        elseif (Test-Path $MysqlExe) {
            $cnt = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM rag_chunk;").Output
            if (-not $cnt -or [int]$cnt -eq 0) { $needReindex = $true }
        }
        if ((Test-Path $MysqlExe) -and $goldenTotal -gt 0) {
            $gc = (Invoke-Mysql -Sql "SELECT COUNT(*) FROM rag_golden_item;").Output
            if (-not $gc -or [int]$gc -lt $goldenTotal) { $needGolden = $true }
        }

        if ($needReindex -or $needGolden) {
            Write-Title "5. RAG 切块与 Golden 题集"
            try {
                $login = Invoke-RestMethod -Method Post `
                    -Uri "http://127.0.0.1:$BackendPort/api/v1/auth/login" `
                    -ContentType "application/json" `
                    -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
                $headers = @{ Authorization = "Bearer $($login.data.token)" }

                if ($needReindex) {
                    $res = Invoke-RestMethod -Method Post `
                        -Uri "http://127.0.0.1:$BackendPort/api/v1/rag/reindex" `
                        -Headers $headers -TimeoutSec 90
                    Write-Ok "切块完成：$($res.data.materialsIndexed) 条资料已建索引"
                }

                if ($needGolden) {
                    $gr = Invoke-RestMethod -Method Post `
                        -Uri "http://127.0.0.1:$BackendPort/api/v1/rag/golden/import" `
                        -Headers $headers -TimeoutSec 60
                    Write-Ok "Golden 题集已补齐：$($gr.data.imported) 题（jsonl 共 $goldenTotal 题）"
                }
            } catch {
                Write-Warn2 "RAG 初始化失败（不影响启动）：$($_.Exception.Message)"
                Write-Info "可登录后手动调用：POST /api/v1/rag/reindex 与 POST /api/v1/rag/golden/import"
            }
        } else {
            Write-Ok "RAG 切块与 Golden 题集均已就绪，跳过（需要重建切块请加 -Reindex）"
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
