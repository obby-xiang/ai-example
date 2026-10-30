<#
.SYNOPSIS
  B23L1 批① 双实例 harness（8081 / 8082 共享同一 Redis + 桩上游 18399）。

.DESCRIPTION
  只交付、不自动运行：脚本自己负责"起 / 等 / 收"，但**本批施工不执行它**——
  运行归核验阶段（核验者显式调用，并自行承担端口占用）。

  形态（设计稿 §5.2）：
    · 桩上游  scripts/ci/stub-upstream.mjs（18399，零密钥确定性）
    · 实例 A   java -jar config-mgr.jar --server.port=8081（独立 H2 文件，避免文件锁冲突）
    · 实例 B   java -jar config-mgr.jar --server.port=8082（同上）
    · 两者共享 spring.data.redis.*（事件日志 ai:xevents: / 唤醒 ai:wake: 都在 Redis 上）

  用例面（默认 = 批① 三条 + 批② 三条 + 批③ 两条）：
    · reattach-full-replay   一轮在 A 跑完，B 侧从 0 回放得到逐帧同序同号（连接层无状态）
    · reattach-diff          同一轮在 B 侧用 ?lastSeq 只拿到差量，不重不漏
    · suspend-reattach-wake  A 挂起 → B 侧重挂 → 在 A 批准确认 → 续跑帧经 ai:wake: 扇出到 B 的连接
    · suspend-decision       A 挂起 → 决策打 B（A 的等待方持租）→ 续跑只发生一次，双实例都能重挂到终帧
    · resource-lock          同 task 双 start_export 竞态：恰一个执行、恰一个 blocked/RESOURCE_LOCKED
                              未开 -SharedDb 时走 H3 确定性预置锁变体（不依赖共享 H2）
    · idempotency            同一 runId+toolCallId 二次决策 → 409 DUPLICATE，台账/执行仍恰一次
    · instance-kill          A 挂起后杀真 A → 决策打 B → 租约过期后 B 接管续跑（台账 executedBy=B）

  非默认可选用例（显式 -Cases 指定）：
    · suspend-decision-concurrent  同一 runId/toolCallId 双路并发决策（恰一个 200、一个 409）
    · lease-scripts                真 Redis 租约脚本冒烟（ACQUIRE/RENEW/FENCED-SET/RELEASE；
                                   会 kill instance-a，须排在最后或单独跑）
    · rate-limit-shared           防护批③：A 侧 11 次 + B 侧 10 次（同一 sessionId）→ 第 21 次 429。
                                   证据面 = "限流计数在 Redis 上跨实例共享"，进程内计数会放行第 21 次。
                                   探针用超长 message（裁决 I2：超长也占额度）⇒ 不必真跑 21 轮模型。

.PARAMETER SharedDb
  两实例共享同一 H2 文件（绝对路径 + AUTO_SERVER=TRUE + DB_CLOSE_DELAY=-1）：
  批③ resource-lock 的跨实例变体要求任务在两库都可见（L-12 边界）。
  未开时 resource-lock 走 H3 预置锁变体，仍可验证 blocked/RESOURCE_LOCKED。
  SharedDb 启动失败（H2 共享不可行）会直接 FAIL 并点名该边界，不回退到假绿。

.PARAMETER NoStart
  不自起服务：只用已就绪的 8081/8082（脚本仍做健康等待，不达标即整棒 FAIL）。

.PARAMETER KeepAlive
  用例跑完不杀进程（默认会收掉本脚本起来的全部子进程）。

.PARAMETER Cases
  逗号分隔的用例名；缺省跑批①+②+③ 七条（instance-kill 会杀 A，恒为最后一条）。

.PARAMETER WakeReadyTimeoutSec
  B 侧重挂长连接的"订阅就绪"等待上限（秒，默认 30）：后台作业建立连接后写哨兵
  `wake-B.ready.<runId>`，主线程轮询哨兵而不是硬等；超时判 FAIL（B 侧 SSE 订阅未就绪）。

.PARAMETER KillTakeoverTimeoutSec
  instance-kill / lease-scripts 里"杀 A 后等 B 接管"的预算（秒，默认 90）：
  决策落库后租约 TTL（30s）未过期时首跳允许 LEASE_HELD_ELSEWHERE，等租约过期 + 延迟重试接管。

.EXAMPLE
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/b23l1-dual-instance.ps1 `
      -Jar backend/target/config-mgr.jar -ArtifactDir backend/target/b23l1-artifacts
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    [string]$Jar = 'backend/target/config-mgr.jar',
    [int]$PortA = 8081,
    [int]$PortB = 8082,
    [int]$StubPort = 18399,
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6379,
    [string]$ArtifactDir = '',
    [string]$Cases = 'reattach-full-replay,reattach-diff,suspend-reattach-wake,suspend-decision,resource-lock,idempotency,instance-kill',
    [int]$HealthTimeoutSec = 120,
    [int]$WakeReadyTimeoutSec = 30,
    [int]$KillTakeoverTimeoutSec = 90,
    # 批③：两实例共享同一 H2 文件（AUTO_SERVER=TRUE），用于跨实例同 taskId 的资源锁用例。
    # 关（默认）时 resource-lock 走"确定性预置锁（H3）"变体，不依赖共享库。
    [switch]$SharedDb,
    [switch]$NoStart,
    [switch]$KeepAlive
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

$RepoRoot = (Resolve-Path $RepoRoot).Path
if ([string]::IsNullOrWhiteSpace($ArtifactDir)) {
    $ArtifactDir = Join-Path $RepoRoot 'backend/target/b23l1-artifacts'
}
New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null
# 统一成绝对路径：Start-Job 子进程的当前目录不是调用方目录（本机实测 = 用户文档目录），
# 相对路径的哨兵会被写到别处，且作业内 Set-Content 失败默认不终止 ⇒ 主线程只看到超时。
$ArtifactDir = (Resolve-Path -LiteralPath $ArtifactDir).Path
$BaseA = "http://127.0.0.1:$PortA"
$BaseB = "http://127.0.0.1:$PortB"
$StubBase = "http://127.0.0.1:$StubPort"

$script:passed = 0
$script:failed = 0
$script:skipped = 0
$script:children = New-Object System.Collections.ArrayList
$script:childrenCleaned = $false
$script:frameSamples = New-Object System.Collections.ArrayList
$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromMinutes(3)

# ── 身份（租户/用户）：AI 对话数据面每个请求都带 X-Tenant-Id / X-User-Id ──────────
# 与后端 TenantIdentityFilter 同一分界：/api/ai 下的**对话数据面**缺头即 400；排除面只有
#   · 精确路径 health / tools / tenants（子路径不排除）
#   · 模型配置子树 providers / models / settings
# `startup-resume` 的读取与 `POST startup-resume/scan` **在数据面内**（只处理当前身份的轮次，
# 漏带头 400），因此本脚本对它们照常加身份头；"scan" 不是可以裸调用的运维面。
# 业务数据（/api/tasks、select-defs…）与模型配置**不做租户隔离**，本脚本也不给它们加身份头。
$script:AiTenantId = 1
$script:AiUserId = 101
$script:AiExcludedExactPaths = @('/api/ai/health', '/api/ai/tools', '/api/ai/tenants')
$script:AiConfigSubtrees = @('/api/ai/providers', '/api/ai/models', '/api/ai/settings')

function Test-AiDataPath([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $false }
    $p = $path.Split('?')[0]
    if ($p.Length -gt 1 -and $p.EndsWith('/')) { $p = $p.Substring(0, $p.Length - 1) }
    if (-not ($p -eq '/api/ai' -or $p.StartsWith('/api/ai/'))) { return $false }
    if ($script:AiExcludedExactPaths -contains $p) { return $false }
    foreach ($sub in $script:AiConfigSubtrees) {
        if ($p -eq $sub -or $p.StartsWith($sub + '/')) { return $false }
    }
    return $true
}

# run 级键的 hash tag：租户任务 2 起<b>同时含身份与 runId</b>（`{<tenant>:<user>:run:<runId>}`）。
# harness 的每个 AI 请求都带身份，故直接取证时也必须用带身份的 tag —— 否则读到的是
# legacy 命名空间里不存在的键（表现为"取号未落"之类的假红），或误判同槽。
function Get-RunTag($runId) {
    return '{' + $script:AiTenantId + ':' + $script:AiUserId + ':run:' + $runId + '}'
}

function Add-AiIdentityHeaders($req) {
    if (-not (Test-AiDataPath $req.RequestUri.AbsolutePath)) { return $req }
    $req.Headers.TryAddWithoutValidation('X-Tenant-Id', [string]$script:AiTenantId) | Out-Null
    $req.Headers.TryAddWithoutValidation('X-User-Id', [string]$script:AiUserId) | Out-Null
    return $req
}

# 会话号只由后端发（POST /api/ai/session）；权威值是 data.sessionIdText（十进制字符串）。
# 不得本地造号：多实例/同毫秒唯一性与会话归属只有后端能保证；也不得读 JSON number
# （超 2^53 时 double 会静默改值）—— 故从原始响应文本正则取字符串。
function Get-SessionIdText([string]$json) {
    if (-not $json) { return $null }
    $m = [regex]::Match($json, '"sessionIdText"\s*:\s*"([0-9]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# 会话号判据：十进制正整数且不超过 long 上限（`[long]::TryParse` 溢出即 false）。
# **不按位数**：当前发号器 = 13 位毫秒时间戳；"未超出 2^53 / 不足 16 位"这类断言会误拒它。
function Test-SessionIdText([string]$sid) {
    if ($sid -notmatch '^[0-9]+$') { return $false }
    $value = [long]0
    if (-not [long]::TryParse($sid, [ref]$value)) { return $false }
    return ($value -gt 0)
}

function New-AiSession($base, $tag) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, ($base + '/api/ai/session'))
    Add-AiIdentityHeaders $req | Out-Null
    $resp = $script:http.SendAsync($req).Result
    if ($null -eq $resp) { throw "POST /api/ai/session（$tag）未取得响应（$base 不可达？）" }
    $text = $resp.Content.ReadAsStringAsync().Result
    if (-not $resp.IsSuccessStatusCode) { throw "POST /api/ai/session（$tag）=> HTTP $([int]$resp.StatusCode) $text" }
    $sid = Get-SessionIdText $text
    if (-not $sid) { throw "POST /api/ai/session（$tag）响应缺权威字段 sessionIdText：$text" }
    if (-not (Test-SessionIdText $sid)) { throw "sessionIdText（$tag）= $sid 不是十进制正整数或超出 long 范围" }
    return $sid
}

function Say($msg) { Write-Host $msg }
function Ok($name) { $script:passed++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
function No($name, $why) { $script:failed++; Write-Host ("  [FAIL] " + $name + " => " + $why) -ForegroundColor Red }
function Skip($name, $why) { $script:skipped++; Write-Host ("  [SKIP] " + $name + " => " + $why) -ForegroundColor Yellow }

# ── 进程管理（只动本脚本自己起的子进程） ─────────────────────────────────────

# 起进程前先确认端口空闲：否则"旧的、健康的实例"占着端口时，新 java 绑定失败但健康检查照样通过 ⇒ 假绿。
function Assert-PortFree($port, $what) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync('127.0.0.1', $port)
        if ($task.Wait(300) -and $client.Connected) {
            throw "$what 端口 $port 已被占用（拒绝在既有实例上产出假绿证据）"
        }
    } catch [System.Net.Sockets.SocketException] {
        return
    } catch [System.AggregateException] {
        return
    } finally {
        $client.Close()
    }
}

# 共享 Redis 是双实例的前提（事件日志 ai:xevents: / 唤醒 ai:wake: 都在 Redis 上）。
# harness 不自起 Redis：不可达就早 FAIL，而不是让两个实例各空转 120s 后报"实例未就绪"的误导文案。
function Assert-RedisReachable($hostName, $port) {
    $client = New-Object System.Net.Sockets.TcpClient
    $ok = $false
    try {
        $ok = $client.ConnectAsync($hostName, $port).Wait(1000) -and $client.Connected
    } catch {
        $ok = $false
    } finally {
        $client.Close()
    }
    if (-not $ok) {
        throw "Redis 不可达（${hostName}:${port}），harness 需要共享 Redis，请先启动"
    }
}

function Start-Child($name, $exe, $arguments, $workDir, [hashtable]$env) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = $arguments
    $psi.WorkingDirectory = $workDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    if ($env) { foreach ($k in $env.Keys) { $psi.EnvironmentVariables[$k] = [string]$env[$k] } }
    $log = Join-Path $ArtifactDir "$name.log"
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null
    $writer = New-Object System.IO.StreamWriter($log, $true, [System.Text.Encoding]::UTF8)
    $writer.AutoFlush = $true
    $outSub = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
        if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
    } -MessageData $writer
    $errSub = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
        if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
    } -MessageData $writer
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()
    $script:children.Add(@{
        name = $name; proc = $proc; log = $log; writer = $writer; outSub = $outSub; errSub = $errSub
    }) | Out-Null
    return $proc
}

# 单个子进程的资源回收（事件订阅 / 日志写入器 / Process 句柄）；不影响其它子进程。
function Remove-ChildResources($child) {
    foreach ($sub in @($child.outSub, $child.errSub)) {
        try {
            if ($null -ne $sub) { Unregister-Event -SourceIdentifier $sub.Name -ErrorAction Stop }
        } catch { }
        try {
            if ($null -ne $sub) { Remove-Job -Id $sub.Id -Force -ErrorAction Stop }
        } catch { }
    }
    try { if ($child.writer) { $child.writer.Dispose() } } catch { }
    try { if ($child.proc) { $child.proc.Dispose() } } catch { }
}

function Stop-Children {
    # 幂等：finally 与 Ctrl-C 处理可能先后进来，第二次直接返回，避免重复 Kill / 重复释放。
    if ($script:childrenCleaned) { return }
    $script:childrenCleaned = $true
    foreach ($child in $script:children) {
        try {
            if ($child.proc -and -not $child.proc.HasExited) {
                Say ("  收尾：停止 " + $child.name + "（pid " + $child.proc.Id + "）")
                # .NET Framework 的 Process 没有 Kill(bool) 重载（Kill($true) 只会抛 MethodException
                # 并被 catch 吞掉 ⇒ 子进程存活、端口不释放）；Windows 上用 taskkill /T 连子孙一起收。
                & taskkill.exe /PID $child.proc.Id /T /F 2>$null | Out-Null
            }
        } catch { }
        try {
            if ($child.proc -and -not $child.proc.HasExited) { $child.proc.Kill() }
        } catch { }
        Remove-ChildResources $child
    }
    $script:children.Clear()
}

# 按 name 精确杀单个子进程（instance-kill / lease-scripts 用）：
# kill 前比对活进程的 StartTime（复审 M6：PID 复用时不误杀无关进程）；杀完从列表移除，finally 不再二次杀。
function Stop-Child($name) {
    $target = $null
    foreach ($child in $script:children) {
        if ($child.name -eq $name) { $target = $child; break }
    }
    if ($null -eq $target) {
        throw "Stop-Child：未找到名为 $name 的子进程（-NoStart 或已被收掉）"
    }
    $proc = $target.proc
    try {
        if ($proc -and -not $proc.HasExited) {
            $live = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
            if ($null -eq $live) {
                Say ("  跳过 " + $name + "（pid " + $proc.Id + "）：进程已退出")
            } elseif ($live.StartTime -ne $proc.StartTime) {
                Say ("  跳过 " + $name + "（pid " + $proc.Id + "）：PID 已被复用，拒绝误杀")
            } else {
                Say ("  按名停止 " + $name + "（pid " + $proc.Id + "）")
                & taskkill.exe /PID $proc.Id /T /F 2>$null | Out-Null
                try { if (-not $proc.HasExited) { $proc.Kill() } } catch { }
            }
        }
    } catch { }
    Remove-ChildResources $target
    # ArrayList.Remove 对引用类型走引用相等；这里仍用重建列表，避免任何哈希相等语义的意外。
    $remaining = New-Object System.Collections.ArrayList
    foreach ($child in $script:children) {
        if (-not [object]::ReferenceEquals($child, $target)) { $remaining.Add($child) | Out-Null }
    }
    $script:children = $remaining
}

# 起一个后端实例（SharedDb 时两条实例的 H2 URL 相同；A 先起、健康后再起 B，
# 让 H2 AUTO_SERVER 的服务端先就绪，降低"两边同时初始化抢文件锁"的失败率）。
function Start-Instance($pair, $jarPath) {
    $runDir = Join-Path $ArtifactDir $pair.name
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    # SharedDb 时 URL 可能含空格：整体加引号（ProcessStartInfo.Arguments 是单串，靠引号保住一个 argv）。
    $dsArg = if ($SharedDb) {
        '--spring.datasource.url="' + $sharedDbUrl + '"'
    } else {
        "--spring.datasource.url=jdbc:h2:file:./data/b23l1_$($pair.name);DB_CLOSE_DELAY=-1"
    }
    $javaArgs = @(
        '-jar', "`"$jarPath`"",
        "--server.port=$($pair.port)",
        "--spring.data.redis.host=$RedisHost",
        "--spring.data.redis.port=$RedisPort",
        $dsArg,
        '--app.job.batch-size=10'
    ) -join ' '
    $env = @{
        'AI_API_KEY' = 'b23l1-stub-placeholder'
        'AI_BASE_URL' = $StubBase
        'AI_MODEL' = 'ci-stub'
        'LOGGING_LEVEL_COM_EXAMPLE_CONFIGMGR' = 'DEBUG'
    }
    Start-Child $pair.name 'java' $javaArgs $runDir $env | Out-Null
}

# Ctrl-C：先收干净子进程再退出（130 = 128 + SIGINT），不让 java/node 变成孤儿进程。
$script:cancelHandler = [ConsoleCancelEventHandler]{
    param($sender, $e)
    $e.Cancel = $true
    try { Stop-Children } catch { }
    exit 130
}
[Console]::add_CancelKeyPress($script:cancelHandler)

function GetJson($base, $path) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, ($base + $path))
    Add-AiIdentityHeaders $req | Out-Null
    $resp = $script:http.SendAsync($req).Result
    # .NET Framework 在连不上/被取消时 .Result 会回 $null（不抛）——显式报"不可达"，避免空值方法调用。
    if ($null -eq $resp) { throw "GET $path 未取得响应（$base 不可达？）" }
    $text = $resp.Content.ReadAsStringAsync().Result
    if (-not $resp.IsSuccessStatusCode) { throw "GET $path => HTTP $([int]$resp.StatusCode) $text" }
    return ($text | ConvertFrom-Json)
}

function PostJson($base, $path, $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 20 -Compress }
    $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, ($base + $path))
    $req.Content = $content
    Add-AiIdentityHeaders $req | Out-Null
    $resp = $null
    try {
        $resp = $script:http.SendAsync($req).Result
        if ($null -eq $resp) { throw "POST $path 未取得响应（$base 不可达？）" }
        $text = $resp.Content.ReadAsStringAsync().Result
        return @{ status = [int]$resp.StatusCode; text = $text; ok = $resp.IsSuccessStatusCode }
    } finally {
        if ($resp) { $resp.Dispose() }
        $req.Dispose()
        $content.Dispose()
    }
}

function Wait-Health($base, $what) {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = $script:http.GetAsync($base + '/api/ai/health').Result
            $text = $resp.Content.ReadAsStringAsync().Result
            # /api/ai/health 是**扁平体**（AiController#health：available / redis 与 code/model 同级，
            # 没有 data 包装、也没有 ai 子对象）；AI 不可用时该端点返回 503 但体形不变。
            if (-not [string]::IsNullOrWhiteSpace($text) -and $text.TrimStart().StartsWith('{')) {
                $body = $text | ConvertFrom-Json
                $aiAvailable = [bool]$body.available
                $redisAvailable = [bool]$body.redis.available
                if ($aiAvailable -and $redisAvailable) {
                    Say ("  就绪：" + $what + "（" + $base + "）")
                    return
                }
                Say ("  等待 " + $what + "：HTTP " + [int]$resp.StatusCode + " ai.available=" + $aiAvailable + " redis.available=" + $redisAvailable)
            }
        } catch { }
        Start-Sleep -Milliseconds 800
    }
    throw "$what 在 $HealthTimeoutSec 秒内未就绪（$base/api/ai/health）"
}

# ── SSE 读取（帧原文落盘，返回帧数组） ────────────────────────────────────────

function Read-Sse($base, $path, $bodyObj, [string[]]$stopOn, [int]$timeoutMs = 120000, [string]$tag = '') {
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($base + $path)
    if ($null -ne $bodyObj) {
        $req.Method = [System.Net.Http.HttpMethod]::Post
        $json = $bodyObj | ConvertTo-Json -Depth 20 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    } else {
        $req.Method = [System.Net.Http.HttpMethod]::Get
    }
    Add-AiIdentityHeaders $req | Out-Null
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter($timeoutMs)
    $resp = $script:http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
    # 取消/超时时 .Result 会返回 $null（本机 .NET Framework 实测：请求在等响应头时被 CancelAfter 打断，
    # 不抛异常而是回 null）。此时按"没收到任何帧"收尾 —— 与本函数读取循环里的超时口径一致；
    # 若继续往下走，$resp.Content.ReadAsStringAsync() 就是空值方法调用。
    if ($null -eq $resp) {
        if ($tag) {
            [System.IO.File]::WriteAllText((Join-Path $ArtifactDir "$tag.sse.txt"), '', [System.Text.Encoding]::UTF8)
        }
        $cts.Dispose(); $req.Dispose()
        return , (New-Object System.Collections.ArrayList)
    }
    if (-not $resp.IsSuccessStatusCode) {
        $t = $resp.Content.ReadAsStringAsync().Result
        throw "SSE $path => HTTP $([int]$resp.StatusCode) $t"
    }
    $frames = New-Object System.Collections.ArrayList
    $raw = New-Object System.Collections.ArrayList
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    try {
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            $raw.Add($line) | Out-Null
            if ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($data -and $data -ne '[DONE]') {
                    try { $frames.Add(($data | ConvertFrom-Json)) | Out-Null } catch { }
                    $last = if ($frames.Count -gt 0) { $frames[$frames.Count - 1].type } else { '' }
                    if ($stopOn -contains $last) { break }
                }
            }
        }
    } catch {
        # 超时/断开不视为用例失败：断言只看实际收到的帧
    } finally {
        if ($tag) { $raw | Set-Content -Path (Join-Path $ArtifactDir "$tag.sse.txt") -Encoding UTF8 }
        $reader.Dispose(); $resp.Dispose(); $cts.Dispose(); $req.Dispose()
    }
    return , $frames
}

function Seq-Of($frame) {
    if ($null -ne $frame.seq) { return [int64]$frame.seq }
    return [int64]0
}

function Seq-List($frames) {
    $list = New-Object System.Collections.ArrayList
    foreach ($f in $frames) { if ($null -ne $f.seq) { $list.Add([int64]$f.seq) | Out-Null } }
    return , $list
}

function Assert-StrictlyIncreasing($seqs, $what) {
    for ($i = 1; $i -lt $seqs.Count; $i++) {
        if ($seqs[$i] -le $seqs[$i - 1]) { throw "$what：seq 未严格递增（$($seqs[$i - 1]) → $($seqs[$i])）" }
    }
}

# 等后台长连接的"订阅就绪"哨兵（作业在连接建立后写）。
# 除超时外还盯作业状态：作业提前 Failed/Stopped 时把错误流原文带回，避免只报"未就绪"掩盖真实原因。
function Wait-WakeReady($job, $readyFile, [int]$timeoutSec, $base, $runId) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while (-not (Test-Path -LiteralPath $readyFile)) {
        $state = $null
        try { $state = (Get-Job -Id $job.Id -ErrorAction Stop).State } catch { }
        if ($state -and ($state.ToString() -in @('Completed', 'Failed', 'Stopped'))) {
            if (Test-Path -LiteralPath $readyFile) { break }
            $jobErr = @()
            try { Receive-Job -Job $job -ErrorAction SilentlyContinue -ErrorVariable jobErr | Out-Null } catch { }
            $detail = if ($jobErr.Count -gt 0) { ($jobErr | ForEach-Object { $_.Exception.Message }) -join ' | ' } else { '（作业无错误输出）' }
            try { Remove-Job $job -Force -ErrorAction SilentlyContinue } catch { }
            throw "B 侧 SSE 订阅未就绪：后台长连接作业已结束（state=$state）：$detail"
        }
        if ((Get-Date) -ge $deadline) {
            try { Stop-Job $job -ErrorAction SilentlyContinue } catch { }
            try { Remove-Job $job -Force -ErrorAction SilentlyContinue } catch { }
            throw "B 侧 SSE 订阅未就绪：$timeoutSec 秒内后台长连接未建立（$base/api/ai/events/$runId）"
        }
        Start-Sleep -Milliseconds 100
    }
    Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue
}

# 统计某类帧的条数（可按 toolCallId 过滤；传 $null 则只看类型）。
function Count-Frames($frames, $type, $toolCallId) {
    $n = 0
    foreach ($f in $frames) {
        if ($f.type -eq $type -and ($null -eq $toolCallId -or $f.toolCallId -eq $toolCallId)) { $n++ }
    }
    return $n
}

# 最近秩百分位（样本少时 P50 = P95 = 唯一样本，调用方如实标注样本数）。
function Get-Percentile($values, [double]$p) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $rank = [int][math]::Ceiling(($p / 100.0) * $sorted.Count)
    if ($rank -lt 1) { $rank = 1 }
    if ($rank -gt $sorted.Count) { $rank = $sorted.Count }
    return $sorted[$rank - 1]
}

# 在独立作业里发一次 POST（并发用例用）：作业不可见主进程函数，故块内自包含。
function Start-PostJob($base, $path, $bodyObj, [int64]$fireAtMs = 0) {
    $json = if ($null -eq $bodyObj) { '{}' } else { $bodyObj | ConvertTo-Json -Depth 10 -Compress }
    # 作业进程看不到主进程的函数，身份头与"哪些路径算 AI 数据面"的判据一起传进去。
    # 并发用例打的是 /api/ai/confirm（对话数据面）⇒ 缺头即 400，双路并发会退化成"两路都 400"。
    $tenantId = [string]$script:AiTenantId
    $userId = [string]$script:AiUserId
    return Start-Job -ArgumentList $base, $path, $json, $fireAtMs, $tenantId, $userId -ScriptBlock {
        param($base, $path, $json, $fireAtMs, $tenantId, $userId)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Net.Http
        $http = New-Object System.Net.Http.HttpClient
        $http.Timeout = [TimeSpan]::FromSeconds(60)
        $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
        $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, ($base + $path))
        $req.Content = $content
        $p = $path.Split('?')[0]
        # 与 Test-AiDataPath 同一分界：精确排除只有 health/tools/tenants（startup-resume + scan 在边界内）
        $isAiData = ($p -eq '/api/ai' -or $p.StartsWith('/api/ai/')) -and
            -not (@('/api/ai/health', '/api/ai/tools', '/api/ai/tenants') -contains $p) -and
            -not ($p.StartsWith('/api/ai/providers') -or $p.StartsWith('/api/ai/models') -or $p.StartsWith('/api/ai/settings'))
        if ($isAiData) {
            $req.Headers.TryAddWithoutValidation('X-Tenant-Id', $tenantId) | Out-Null
            $req.Headers.TryAddWithoutValidation('X-User-Id', $userId) | Out-Null
        }
        # 并发用例用同一个墙钟时刻做"发令枪"：作业启动耗时不均，不校准就变成先后两枪。
        if ($fireAtMs -gt 0) {
            $waitMs = $fireAtMs - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }
        }
        $resp = $null
        try {
            $resp = $http.SendAsync($req).Result
            if ($null -eq $resp) { throw "POST $path 未取得响应（$base 不可达？）" }
            $status = [int]$resp.StatusCode
            $text = $resp.Content.ReadAsStringAsync().Result
            return @{ status = $status; text = $text }
        } finally {
            if ($resp) { $resp.Dispose() }
            $req.Dispose()
            $content.Dispose()
            $http.Dispose()
        }
    }
}

# 极简 RESP 单命令读（只读，仅 lease-scripts 取证）：GET / PTTL；任何失败静默返回 $null，绝不写键。
function Invoke-RedisCommand([string[]]$parts) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect($RedisHost, $RedisPort)
        $client.ReceiveTimeout = 2000
        $stream = $client.GetStream()
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.Append("*$($parts.Count)`r`n")
        foreach ($part in $parts) { [void]$sb.Append("`$$($part.Length)`r`n$part`r`n") }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $head = $reader.ReadLine()
        if ($null -eq $head -or $head -eq '$-1') { return $null }
        if ($head.StartsWith('$')) { return $reader.ReadLine() }
        if ($head.StartsWith(':')) { return [int64]$head.Substring(1) }
        return $head
    } catch {
        return $null
    } finally {
        $client.Close()
    }
}

# 测试预置/清理专用（批③ H3 变体）——仅允许：
#   SET <key> <value> PX <ms>（必须带 TTL，避免留下无主测试键）
#   DEL <key>
# 作用域固定为 harness 自己的 $RedisHost:$RedisPort；判定路径仍只用只读 Invoke-RedisCommand。
function Invoke-RedisWrite([string[]]$parts) {
    if ($null -eq $parts -or $parts.Count -lt 2) { throw 'Invoke-RedisWrite：至少需要命令与键' }
    $cmd = ([string]$parts[0]).ToUpperInvariant()
    if ($cmd -eq 'SET') {
        if ($parts.Count -ne 5 -or ([string]$parts[3]).ToUpperInvariant() -ne 'PX') {
            throw 'Invoke-RedisWrite 的 SET 只允许 SET <key> <value> PX <ms>（禁止无 TTL 写）'
        }
        $ttl = 0
        if (-not [int]::TryParse([string]$parts[4], [ref]$ttl) -or $ttl -le 0) {
            throw "Invoke-RedisWrite 的 PX 必须是正整数毫秒（当前 = $($parts[4])）"
        }
    } elseif ($cmd -eq 'DEL') {
        if ($parts.Count -ne 2) { throw 'Invoke-RedisWrite 的 DEL 只允许单键：DEL <key>' }
    } else {
        throw "Invoke-RedisWrite 只允许 SET(key value PX ms) / DEL(key)，收到：$cmd"
    }
    $result = Invoke-RedisCommand $parts
    if ($null -eq $result) { throw "Invoke-RedisWrite 执行失败：$cmd $($parts[1])（Redis 不可达或命令被拒）" }
    return $result
}

# 等一个 Redis 键消失（只读轮询）：资源锁"终态释放"的取证用，避免与释放时点抢跑。
function Wait-RedisKeyGone($key, [int]$timeoutSec = 10) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Invoke-RedisCommand @('GET', $key))) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return ($null -eq (Invoke-RedisCommand @('GET', $key)))
}

# hash tag 内容（与 Redis 规范一致：第一个 `{` 与其后的第一个 `}` 之间非空才算 tag）。
function Get-RedisHashTag([string]$key) {
    $open = $key.IndexOf('{')
    if ($open -lt 0) { return $key }
    $close = $key.IndexOf('}', $open + 1)
    if ($close -le ($open + 1)) { return $key }
    return $key.Substring($open + 1, $close - $open - 1)
}

# 键的槽号（CRC16-XMODEM mod 16384，cluster 口径；自检：foo=12182 / bar=5061 / hello=866）。
# 为什么 harness 要自己算：单节点 Redis 不判槽，跨槽的 DEL/EVAL 在这里永远绿 ——
# 只有把槽算出来，才能证明"一轮 13 键同槽"不是靠单机侥幸。
function Get-RedisSlot([string]$key) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes((Get-RedisHashTag $key))
    $crc = 0
    foreach ($b in $bytes) {
        $crc = $crc -bxor (([int]$b -band 0xFF) -shl 8)
        for ($i = 0; $i -lt 8; $i++) {
            if (($crc -band 0x8000) -ne 0) { $crc = (($crc -shl 1) -bxor 0x1021) } else { $crc = $crc -shl 1 }
            $crc = $crc -band 0xFFFF
        }
    }
    return ($crc -band 0x3FFF)
}

# 挂起骨架（wake / suspend-decision / instance-kill / lease-scripts / 批③ 共用）：
# 建真实 EXPORT 任务（或复用 -TaskId）→ select-defs → 带 context 发 start_export → 读到 confirm_request 为止。
# -Base 缺省 A；SharedDb 资源锁用例的第二条必须在 B 侧发起（run 的挂起者 = 该实例）。
# -MessageSuffix：桩的会话指纹 = 首条 user 消息文本；共享库变体两侧必须给互异后缀，
#   否则同一 taskId + 同一句话会被桩视为同一会话（bucket.used 已占 TC16-EXPORT → 退化为文本轮）。
# 返回 @{ sessionId; taskId; runId; toolCallId; lastSeq }。
function New-SuspendedExportRun($prefix, $tag, [int64]$TaskId = 0, $Base = '', [string]$MessageSuffix = '') {
    if ([string]::IsNullOrWhiteSpace($Base)) { $Base = $BaseA }
    $sideLabel = if ($Base -eq $BaseA) { 'A 侧' } else { 'B 侧' }
    $sessionId = New-AiSession $Base ($prefix + 'sid')
    if ($TaskId -gt 0) {
        # SharedDb / 资源锁用例：复用已存在（且两侧可见）的任务，不再建新任务。
        $taskId = $TaskId
    } else {
        # 确认门的两道脚本侧前提：
        #   ① start_export 只在 page:tasks / task:EXPORT/EXPORT 上下文披露（否则桩的 requiresTools 硬闸不过）；
        #   ② 确认门前的只读预检要求任务真实存在（任务不存在会被 block，不出确认卡）。
        $taskTitle = $prefix + [guid]::NewGuid().ToString('N').Substring(0, 8)
        $taskResp = PostJson $BaseA '/api/tasks' @{ type = 'EXPORT'; title = $taskTitle }
        $taskText = if ($null -ne $taskResp) { [string]$taskResp.text } else { '' }
        if ($null -eq $taskResp -or -not $taskResp.ok) {
            throw "创建导出任务失败：HTTP $($taskResp.status) $($taskText)"
        }
        $taskBody = $null
        try { $taskBody = $taskText | ConvertFrom-Json } catch { $taskBody = $null }
        $taskId = $null
        if ($null -ne $taskBody -and $null -ne $taskBody.data -and $null -ne $taskBody.data.id) {
            $taskId = [int64]$taskBody.data.id
        }
        if ($null -eq $taskId -or $taskId -le 0) {
            $snippet = if ($taskText.Length -gt 400) { $taskText.Substring(0, 400) } else { $taskText }
            throw "创建导出任务响应缺少 data.id：HTTP $($taskResp.status) 体=$snippet"
        }
        $defsResp = PostJson $BaseA ("/api/tasks/" + $taskId + "/select-defs") @{ defCodes = @('CURRENCY') }
        if ($null -eq $defsResp -or -not $defsResp.ok) {
            throw "选择导出定义失败：HTTP $($defsResp.status) $($defsResp.text)"
        }
    }

    $context = @{ page = 'tasks'; taskType = 'EXPORT'; step = 'EXPORT'; taskId = $taskId }
    $message = 'start_export 帮我导出 taskId=' + $taskId + $MessageSuffix
    $framesA = Read-Sse $Base '/api/ai/chat' @{ sessionId = $sessionId; message = $message; context = $context } @('confirm_request', 'frontend_tool_request', 'error') 120000 $tag
    $runId = $null
    $toolCallId = $null
    $lastSeq = 0
    foreach ($f in $framesA) {
        if ($f.type -eq 'start') { $runId = $f.runId }
        if ($f.type -eq 'confirm_request') { $toolCallId = $f.toolCallId }
        if ($null -ne $f.seq) { $lastSeq = [int64]$f.seq }
    }
    if (-not $runId) { throw "${sideLabel}未收到 start 帧（runId 缺失）" }
    # 桩的指纹 = 首条 user 消息；同一 taskId 的并发两条必须带互异后缀，否则后到的一条退化为文本轮。
    if (-not $toolCallId) {
        $seen = @($framesA | ForEach-Object { $_.type }) -join ','
        throw "${sideLabel}未出现确认卡（桩路由 start_export 未触发；已收帧: $seen；消息: $message）"
    }
    return @{ sessionId = $sessionId; taskId = $taskId; runId = $runId; toolCallId = $toolCallId; lastSeq = $lastSeq }
}

# ── 用例 ────────────────────────────────────────────────────────────────────

function Case-Reattach-Full-Replay {
    $name = 'reattach-full-replay（批①：连接层无状态，B 侧可全量回放 A 的帧）'
    $sessionId = New-AiSession $BaseA 'b23l1-full'
    $framesA = Read-Sse $BaseA '/api/ai/chat' @{ sessionId = $sessionId; message = 'scenario=PLAIN 你好' } @('done', 'error') 120000 'full-A'
    $runId = $null
    foreach ($f in $framesA) { if ($f.type -eq 'start') { $runId = $f.runId } }
    if (-not $runId) { throw 'A 侧未收到 start 帧（runId 缺失）' }
    $seqsA = Seq-List $framesA
    Assert-StrictlyIncreasing $seqsA 'A 侧实时流'

    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $runId) $null @('done', 'error') 120000 'full-B'
    $seqsB = Seq-List $framesB
    Assert-StrictlyIncreasing $seqsB 'B 侧回放'
    if (($seqsB -join ',') -ne ($seqsA -join ',')) {
        throw "B 侧回放与 A 侧实时流不同号不同序：A=$($seqsA -join ',') B=$($seqsB -join ',')"
    }
    Ok $name
}

function Case-Reattach-Diff {
    $name = 'reattach-diff（批①：?lastSeq 差量重挂，不重不漏）'
    $sessionId = New-AiSession $BaseA 'b23l1-diff'
    $framesA = Read-Sse $BaseA '/api/ai/chat' @{ sessionId = $sessionId; message = 'scenario=PLAIN 你好' } @('done', 'error') 120000 'diff-A'
    $runId = $null
    foreach ($f in $framesA) { if ($f.type -eq 'start') { $runId = $f.runId } }
    if (-not $runId) { throw 'A 侧未收到 start 帧（runId 缺失）' }
    $seqsA = Seq-List $framesA
    if ($seqsA.Count -lt 3) { throw "帧数不足以验证差量：$($seqsA.Count)" }
    $cut = $seqsA[[int]($seqsA.Count / 2)]

    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=" + $cut) $null @('done', 'error') 120000 'diff-B'
    $seqsB = Seq-List $framesB
    $expected = New-Object System.Collections.ArrayList
    foreach ($s in $seqsA) { if ($s -gt $cut) { $expected.Add($s) | Out-Null } }
    if (($seqsB -join ',') -ne ($expected -join ',')) {
        throw "差量不匹配：期望 $($expected -join ',') 实得 $($seqsB -join ',')"
    }
    Ok $name
}

function Case-Suspend-Reattach-Wake {
    $name = 'suspend-reattach-wake（批①：续跑帧经 ai:wake: 扇出到另一实例的连接）'
    $run = New-SuspendedExportRun 'b23l1-wake-' 'wake-A'
    $runId = $run.runId
    $toolCallId = $run.toolCallId
    $lastSeq = $run.lastSeq

    # B 侧重挂：先拿一次差量快照（证明回放可用），再挂一条长连接等唤醒
    $snapshot = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=" + $lastSeq) $null @() 4000 'wake-B-snapshot'

    # 长连接在后台作业里读（主线程去 A 批准确认）；作业用默认凭据与自己的 HttpClient。
    # 就绪信号不靠固定睡眠：作业在"订阅已发起 + 连接已建立"后写哨兵，主线程轮询哨兵再批准确认。
    # 哨兵名带 runId（跨用例隔离），起作业前先清掉同名的陈旧件。
    $readyFile = Join-Path $ArtifactDir ('wake-B.ready.' + $runId)
    Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue
    $job = Start-Job -ArgumentList $BaseB, $runId, $lastSeq, $readyFile, ([string]$script:AiTenantId), ([string]$script:AiUserId) -ScriptBlock {
        param($base, $runId, $lastSeq, $readyFile, $tenantId, $userId)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Net.Http
        $http = New-Object System.Net.Http.HttpClient
        $http.Timeout = [TimeSpan]::FromMinutes(2)
        $uri = New-Object System.Uri($base + '/api/ai/events/' + $runId + '?lastSeq=' + $lastSeq)
        # /api/ai/events 是对话数据面：缺身份头会被 400（响应头立刻回、看起来"就绪"，
        # 但收到的是 JSON 错误体而不是 SSE 回放）—— 头必须带。
        $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $uri)
        $req.Headers.TryAddWithoutValidation('X-Tenant-Id', $tenantId) | Out-Null
        $req.Headers.TryAddWithoutValidation('X-User-Id', $userId) | Out-Null
        # 先把订阅请求发出去（异步、不等响应头），再判就绪。
        $task = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
        $ready = $false
        try {
            # 有回放帧时服务端立刻回响应头；零回放时 SSE 不 commit（一帧都不写），
            # 于是退一步用"到目标的 TCP 连接已建立"作为连接层就绪判据（服务在监听、请求已发出）。
            if ($task.Wait(1000)) { $ready = $true }
        } catch {
            $ready = $false
        }
        if (-not $ready) {
            $probe = New-Object System.Net.Sockets.TcpClient
            try {
                $ready = $probe.ConnectAsync($uri.Host, $uri.Port).Wait(1000) -and $probe.Connected
            } catch {
                $ready = $false
            } finally {
                $probe.Close()
            }
        }
        if ($ready) { Set-Content -LiteralPath $readyFile -Value 'ready' -Encoding UTF8 }
        $resp = $task.Result
        if ($null -eq $resp) { throw "B 侧 SSE 长连接未取得响应（$uri）" }
        $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result)
        $out = New-Object System.Collections.ArrayList
        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($data -and $data -ne '[DONE]') {
                    try {
                        $frame = $data | ConvertFrom-Json
                        # 收帧时刻（客户端墙钟）：用于 P95「A confirm 完成 → B 首续跑帧」延迟取证。
                        $frame | Add-Member -NotePropertyName _recvAtMs -NotePropertyValue ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) -Force
                        $out.Add($frame) | Out-Null
                    } catch { }
                    $t = $out[$out.Count - 1].type
                    if ($t -eq 'done' -or $t -eq 'error') { break }
                }
            }
        }
        $reader.Dispose(); $resp.Dispose(); $http.Dispose()
        # 作业输出会经序列化回主进程：此处不能再用 `, @($out)` 把数组打包成**单个对象**
        # （主线程会拿到一个"元素是帧数组"的数组，seq/type 全变成数组）。逐帧输出。
        return @($out)
    }

    # 轮询哨兵（默认 30s，可 -WakeReadyTimeoutSec 调）：连接真建立了才去批准确认，
    # 否则唤醒帧可能早于 B 侧订阅落库 → 只能等终帧超时，现象是"唤醒未生效"的假红。
    Wait-WakeReady $job $readyFile $WakeReadyTimeoutSec $BaseB $runId

    $approve = PostJson $BaseA '/api/ai/confirm' @{ runId = $runId; toolCallId = $toolCallId; approved = $true; reason = 'b23l1 harness' }
    if (-not $approve.ok) { throw "A 侧批准确认失败：HTTP $($approve.status) $($approve.text)" }
    $approveAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    $woke = $null
    if (-not (Wait-Job $job -Timeout 90)) { Stop-Job $job }
    $woke = Receive-Job $job
    Remove-Job $job -Force

    $framesB = @($woke | Where-Object { $null -ne $_ })
    # L-5 取证：A confirm POST 完成 → B 首帧（续跑帧）到达的客户端墙钟差。
    $firstFrame = $framesB | Where-Object { $null -ne $_.PSObject.Properties['_recvAtMs'] } | Select-Object -First 1
    if ($null -ne $firstFrame) {
        $sample = [double]([int64]$firstFrame._recvAtMs - [int64]$approveAtMs)
        $script:frameSamples.Add($sample) | Out-Null
        Say ("  wake 帧延迟（confirm→B 首帧）：" + [int64]$sample + "ms")
    }
    $seqsB = Seq-List $framesB
    Assert-StrictlyIncreasing $seqsB 'B 侧唤醒补帧'
    $done = $framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1
    if ($null -eq $done) {
        throw "B 侧未收到终帧（唤醒未生效？已收 seq：$($seqsB -join ',')；快照帧数：$($snapshot.Count)）"
    }
    if ((Seq-Of $done) -le $lastSeq) { throw "终帧 seq 未推进：done.seq=$(Seq-Of $done) lastSeq=$lastSeq" }
    Ok $name
}

function Case-Suspend-Decision {
    $name = 'suspend-decision（批②：决策打 B，A 的等待方持租；续跑恰一次）'
    $run = New-SuspendedExportRun 'b23l1-dec-' 'dec-A'
    $runId = $run.runId
    $toolCallId = $run.toolCallId
    $lastSeq = $run.lastSeq

    # 决策打到 B（不是 A）：B 抢不到 A 等待方手里的续跑租约，只落库、不派发。
    $resp = PostJson $BaseB '/api/ai/confirm' @{ runId = $runId; toolCallId = $toolCallId; approved = $true; reason = 'b23l1 decision on B' }
    if ($null -eq $resp -or -not $resp.ok) {
        throw "B 侧决策被拒：HTTP $($resp.status) $($resp.text)"
    }
    $body = $null
    try { $body = $resp.text | ConvertFrom-Json } catch { $body = $null }
    if ($null -eq $body -or -not [bool]$body.accepted) {
        throw "B 侧决策未被接受：$($resp.text)"
    }
    # 字段依据：AiController#confirm（body: accepted / wokeInProcessGate / resumeTriggered / resumeOutcome）
    # 与 ConfirmGate.ResumeTrigger（outcome=LEASE_HELD_ELSEWHERE 表示这一轮由别的实例的等待方持租）。
    if ([string]$body.resumeOutcome -ne 'LEASE_HELD_ELSEWHERE') {
        throw "B 侧 resumeOutcome 期望 LEASE_HELD_ELSEWHERE（A 的等待方持租），实得 '$($body.resumeOutcome)'：$($resp.text)"
    }
    if ([bool]$body.wokeInProcessGate) {
        throw "B 侧不应唤醒本进程闸门（wokeInProcessGate=true）：$($resp.text)"
    }

    # 全量回放（lastSeq=0）等终帧并做"恰一次"计数：挂起前的 tool_start 也在归档里，
    # 续跑若重复执行会多出第二份 tool_start/tool_result；全量回放对终态轮立即回放完毕。
    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=0") $null @('done', 'error') 120000 'dec-B'
    $doneB = $framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1
    if ($null -eq $doneB) {
        throw "B 侧重挂未收到终帧（已收：$(($framesB | ForEach-Object { $_.type }) -join ',')）"
    }
    $starts = Count-Frames $framesB 'tool_start' $toolCallId
    if ($starts -ne 1) { throw "续跑 tool_start 期望恰 1 帧，实得 $starts（toolCallId=$toolCallId）" }
    $results = Count-Frames $framesB 'tool_result' $toolCallId
    if ($results -ne 1) { throw "续跑 tool_result 期望恰 1 帧，实得 $results（toolCallId=$toolCallId）" }

    # ③ 双实例 reattach 都能收到终帧：A 侧同 lastSeq 再重挂一次。
    $framesA = Read-Sse $BaseA ("/api/ai/events/" + $runId + "?lastSeq=" + $lastSeq) $null @('done', 'error') 120000 'dec-A-done'
    if ((Count-Frames $framesA 'done' $null) -lt 1) {
        throw "A 侧重挂未收到终帧（已收：$(($framesA | ForEach-Object { $_.type }) -join ',')）"
    }
    Ok $name
}

function Case-Suspend-Decision-Concurrent {
    $name = 'suspend-decision-concurrent（批②：同 runId 双路并发决策，恰一胜）'
    $run = New-SuspendedExportRun 'b23l1-conc-' 'conc-A'
    $bodyObj = @{ runId = $run.runId; toolCallId = $run.toolCallId; approved = $true; reason = 'b23l1 concurrent decision' }
    # 同一发令时刻：两路 POST 真正并发，避免 Start-Job 启动耗时把"并发"变成"一先一后"。
    $fireAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 2000
    $jobA = Start-PostJob $BaseA '/api/ai/confirm' $bodyObj $fireAt
    $jobB = Start-PostJob $BaseB '/api/ai/confirm' $bodyObj $fireAt
    $null = Wait-Job -Job @($jobA, $jobB) -Timeout 60
    $errA = @()
    $errB = @()
    $rA = Receive-Job $jobA -ErrorAction SilentlyContinue -ErrorVariable errA
    $rB = Receive-Job $jobB -ErrorAction SilentlyContinue -ErrorVariable errB
    Remove-Job $jobA, $jobB -Force -ErrorAction SilentlyContinue

    $all = @($rA, $rB) | Where-Object { $null -ne $_ }
    $oks = @($all | Where-Object { $_.status -eq 200 })
    $conflicts = @($all | Where-Object { $_.status -eq 409 })
    if ($oks.Count -ne 1 -or $conflicts.Count -ne 1) {
        $errText = (@($errA) + @($errB)) | ForEach-Object { $_.Exception.Message }
        throw ("并发决策期望恰一个 200 + 一个 409，实得状态 [" + (($all | ForEach-Object { $_.status }) -join ',') +
            "]，体：" + (($all | ForEach-Object { $_.text }) -join ' || ') +
            "，作业错误：" + ($errText -join ' || '))
    }
    $okBody = $null
    try { $okBody = $oks[0].text | ConvertFrom-Json } catch { $okBody = $null }
    if ($null -eq $okBody -or -not [bool]$okBody.accepted) {
        throw "并发胜方未返回 accepted=true：$($oks[0].text)"
    }
    $confBody = $null
    try { $confBody = $conflicts[0].text | ConvertFrom-Json } catch { $confBody = $null }
    # 字段依据：AiController#duplicateToolCall → code=DUPLICATE_TOOL_CALL_ID / duplicate=true。
    if ($null -eq $confBody -or [string]$confBody.code -ne 'DUPLICATE_TOOL_CALL_ID') {
        throw "并发败方期望 code=DUPLICATE_TOOL_CALL_ID，实得 '$($confBody.code)'：$($conflicts[0].text)"
    }

    # R4：回执/续跑"恰一次" —— 全量回放（lastSeq=0）等终帧后数 confirm_decision / tool_start。
    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $run.runId + "?lastSeq=0") $null @('done', 'error') 120000 'conc-B'
    if ($null -eq ($framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1)) {
        throw "并发决策后 B 侧重挂未收到终帧（已收：$(($framesB | ForEach-Object { $_.type }) -join ',')）"
    }
    $decisions = Count-Frames $framesB 'confirm_decision' $run.toolCallId
    if ($decisions -ne 1) { throw "confirm_decision 期望恰 1 帧，实得 $decisions" }
    $starts = Count-Frames $framesB 'tool_start' $run.toolCallId
    if ($starts -ne 1) { throw "tool_start 期望恰 1 帧，实得 $starts" }
    Ok $name
}

# 全量回放（?lastSeq=0）到终帧：对"快照已 DONE、done 帧尚未落档"的窄窗重试。
# 后端 finish() 先 save(DONE)（ResilientChatService:487）再 out.done(:502)，而终态回放不挂订阅
# （SseChatEmitter:523-530）——恰在此窗口到达的重挂会拿到 16 帧（到 message_end）就收流，永远收不到 done。
# 这里缺终帧时短延迟重读（done 通常几毫秒内落档）；4 次仍无终帧才 FAIL（不掩盖真实的 done 缺失）。
function Read-RunFullToTerminal($base, $runId, $tag, [int]$timeoutSec = 120) {
    $lastFrames = $null
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        $frames = Read-Sse $base ("/api/ai/events/" + $runId + "?lastSeq=0") $null @('done', 'error') $timeoutSec ("$tag-a$attempt")
        $lastFrames = $frames
        if ((Count-Frames $frames 'done' $null) -ge 1 -or (Count-Frames $frames 'error' $null) -ge 1) {
            return , $frames
        }
        Start-Sleep -Milliseconds 500
    }
    $seen = @($lastFrames | ForEach-Object { $_.type }) -join ','
    throw "全量回放重试 4 次仍未收到终帧（runId=$runId；最后已收：$seen）"
}

# 审批一轮挂起并在该 run 所在的实例上全量回放到终帧；返回全量帧数组。
function Approve-SuspendedRun($base, $run, $tag, $reason) {
    $approve = PostJson $base '/api/ai/confirm' @{ runId = $run.runId; toolCallId = $run.toolCallId; approved = $true; reason = $reason }
    if ($null -eq $approve -or -not $approve.ok) {
        throw "决策被拒：HTTP $($approve.status) $($approve.text)"
    }
    $full = Read-RunFullToTerminal $base $run.runId $tag 120
    if ((Count-Frames $full 'done' $null) -lt 1) {
        throw "未收到终帧（已收：$(($full | ForEach-Object { $_.type }) -join ',')）"
    }
    return , $full
}

# 单轮竞态判定（抽出来便于离线喂假数据）：命中 = 恰一个 RESOURCE_LOCKED（BLOCKED/blocked/未执行）
# + 恰一个真实执行 + 两轮台账合计 1 + 作业数 1。否则返回 Hit=false（竞态窗口没重叠，不是系统错）。
function Test-ResourceLockRound($fullA, $fullB, $detailA, $detailB, $jobsCount) {
    $all = @($fullA) + @($fullB)
    $locked = @($all | Where-Object { $_.type -eq 'tool_result' -and [string]$_.code -eq 'RESOURCE_LOCKED' })
    $executed = @($all | Where-Object { $_.type -eq 'tool_result' -and [bool]$_.executed })
    $sum = [int]$detailA.ledgerCounts.start_export + [int]$detailB.ledgerCounts.start_export
    $lockShapeOk = $false
    if ($locked.Count -eq 1) {
        $lf = $locked[0]
        $lockShapeOk = ([string]$lf.status -eq 'BLOCKED' -and [string]$lf.outcome -eq 'blocked' -and -not [bool]$lf.executed)
    }
    $hit = ($locked.Count -eq 1 -and $lockShapeOk -and $executed.Count -eq 1 -and $sum -eq 1 -and [int]$jobsCount -eq 1)
    $lockShown = if ($locked.Count -eq 1) {
        "status=$($locked[0].status)/outcome=$($locked[0].outcome)/executed=$($locked[0].executed)"
    } else { "-" }
    return @{
        Hit = $hit
        Shape = ("locked=$($locked.Count)[$lockShown] executed=$($executed.Count) ledgerSum=$sum jobs=$jobsCount")
    }
}

# 竞态重试编排（I-2）：最多 $maxRounds 轮；任一命中即 PASS，全未命中 → SKIP（行为可能完全正确）。
# $roundRunner 是无参/单参脚本块，返回 Test-ResourceLockRound 的结果（@{Hit;Shape}）。
function Invoke-ResourceLockRace($roundRunner, [int]$maxRounds = 3) {
    $shapes = @()
    for ($i = 1; $i -le $maxRounds; $i++) {
        $r = & $roundRunner $i
        $shapes += ("R$i[" + [string]$r.Shape + "]")
        if ([bool]$r.Hit) {
            return @{ Result = 'PASS'; Rounds = $i; Shapes = $shapes }
        }
    }
    return @{ Result = 'SKIP'; Rounds = $maxRounds; Shapes = $shapes }
}

function Case-Resource-Lock {
    $name = 'resource-lock（批③：同 task 双 start_export 恰一个执行、恰一个 RESOURCE_LOCKED）'
    if ($SharedDb) {
        Say '  resource-lock：-SharedDb 已开 ⇒ 跨实例共享库变体（最多 3 轮，命中即 PASS；3 轮均未命中判 SKIP）'
        $race = Case-Resource-Lock-Shared
        if ($race -eq 'SKIP') {
            Skip $name '竞态窗口未命中（3/3），跨实例资源互斥证据以 H3 确定性变体为准'
            return
        }
    } else {
        Say '  resource-lock：未开 -SharedDb ⇒ 走 H3 确定性预置锁变体（跨实例共享库版需 -SharedDb）'
        Case-Resource-Lock-Preset
    }
    Ok $name
}

# H3：真 Redis 预置 ai:lock:job:<taskId>:EXPORT，单侧发起 start_export 必得 blocked/RESOURCE_LOCKED；
# DEL 后同一任务重跑成功。不依赖 SharedDb（任务/执行都在 A 侧）。
function Case-Resource-Lock-Preset {
    $run = New-SuspendedExportRun 'b23l1-lock-' 'lock-A'
    $lockKey = 'ai:lock:job:' + $run.taskId + ':EXPORT'
    Say ("  H3：预置资源锁 " + $lockKey + " = 1|other-instance（PX 60000）")
    Invoke-RedisWrite @('SET', $lockKey, '1|other-instance', 'PX', '60000') | Out-Null
    try {
        $blockedFrames = Approve-SuspendedRun $BaseA $run 'lock-A-blocked' 'b23l1 resource-lock preset'
        $tr = @($blockedFrames | Where-Object { $_.type -eq 'tool_result' -and $_.toolCallId -eq $run.toolCallId })
        if ($tr.Count -ne 1) { throw "预置锁场景 tool_result 期望 1 帧，实得 $($tr.Count)" }
        $f = $tr[0]
        # 字段依据：SseChatEmitter#toolResult（code/status/outcome/executed/retryable）
        # 与 ToolOutcome.BLOCKED（只 RESOURCE_LOCKED 映射 blocked 且 retryable=true）。
        if ([string]$f.code -ne 'RESOURCE_LOCKED') {
            throw "预置锁场景期望 code=RESOURCE_LOCKED，实得 '$($f.code)'（status=$($f.status) outcome=$($f.outcome)）"
        }
        if ([string]$f.status -ne 'BLOCKED') { throw "预置锁场景 status 期望 BLOCKED，实得 '$($f.status)'" }
        if ([string]$f.outcome -ne 'blocked') { throw "预置锁场景 outcome 期望 blocked，实得 '$($f.outcome)'" }
        if ([bool]$f.executed) { throw 'RESOURCE_LOCKED 帧不应 executed=true（被锁=未执行）' }
        if (-not [bool]$f.retryable) { throw 'RESOURCE_LOCKED 应 retryable=true（等锁可重试）' }

        $detail = (GetJson $BaseA ('/api/ai/runs/' + $run.runId)).data
        if ([int]$detail.ledgerCounts.start_export -ne 0) {
            throw "被锁调用不应进执行台账：ledgerCounts.start_export=$($detail.ledgerCounts.start_export)"
        }
        $jobsBlocked = @((GetJson $BaseA ('/api/tasks/' + $run.taskId + '/jobs')).data)
        if ($jobsBlocked.Count -ne 0) { throw "被锁调用不应创建作业：jobs=$($jobsBlocked.Count)" }

        Invoke-RedisWrite @('DEL', $lockKey) | Out-Null
        Say '  已删除预置锁，同一任务重跑 start_export'
        $run2 = New-SuspendedExportRun 'b23l1-lock-' 'lock-A-ok' $run.taskId
        $okFrames = Approve-SuspendedRun $BaseA $run2 'lock-A-ok-done' 'b23l1 resource-lock rerun'
        $tr2 = @($okFrames | Where-Object { $_.type -eq 'tool_result' -and $_.toolCallId -eq $run2.toolCallId })
        if ($tr2.Count -ne 1) { throw "解锁后 tool_result 期望 1 帧，实得 $($tr2.Count)" }
        if (-not [bool]$tr2[0].executed) {
            throw "解锁后工具应真实执行（executed=true），实得 code=$($tr2[0].code) outcome=$($tr2[0].outcome)"
        }
        if ([string]$tr2[0].outcome -ne 'ok') { throw "解锁后 outcome 期望 ok，实得 '$($tr2[0].outcome)'" }
        $detail2 = (GetJson $BaseA ('/api/ai/runs/' + $run2.runId)).data
        if ([int]$detail2.ledgerCounts.start_export -ne 1) {
            throw "解锁后台账 start_export 期望 1，实得 $($detail2.ledgerCounts.start_export)"
        }
        $jobsOk = @((GetJson $BaseA ('/api/tasks/' + $run.taskId + '/jobs')).data)
        if ($jobsOk.Count -ne 1) { throw "解锁后作业数期望 1，实得 $($jobsOk.Count)" }
        if (-not (Wait-RedisKeyGone $lockKey 10)) {
            throw "执行完成后资源锁未释放：$lockKey=$(Invoke-RedisCommand @('GET', $lockKey))"
        }
    } finally {
        # 预置锁清理（成败都清；幂等）
        try { Invoke-RedisWrite @('DEL', $lockKey) | Out-Null } catch { }
    }
}

# SharedDb 每轮新建一个共享任务（新任务 = 干净的 jobs=1 判据，不被上一轮的作业污染），
# 并先在 B 侧确认可见（看不到 = SharedDb 未生效，立即点名 L-12 边界）。
function New-SharedExportTask($prefix) {
    $taskTitle = $prefix + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $taskResp = PostJson $BaseA '/api/tasks' @{ type = 'EXPORT'; title = $taskTitle }
    $taskText = if ($null -ne $taskResp) { [string]$taskResp.text } else { '' }
    if ($null -eq $taskResp -or -not $taskResp.ok) { throw "SharedDb：创建共享任务失败 HTTP $($taskResp.status) $taskText" }
    $taskBody = $null
    try { $taskBody = $taskText | ConvertFrom-Json } catch { $taskBody = $null }
    $taskId = $null
    if ($null -ne $taskBody -and $null -ne $taskBody.data -and $null -ne $taskBody.data.id) { $taskId = [int64]$taskBody.data.id }
    if ($null -eq $taskId -or $taskId -le 0) { throw "SharedDb：创建共享任务响应缺少 data.id：$taskText" }
    $defsResp = PostJson $BaseA ("/api/tasks/" + $taskId + "/select-defs") @{ defCodes = @('CURRENCY') }
    if ($null -eq $defsResp -or -not $defsResp.ok) { throw "SharedDb：select-defs 失败 HTTP $($defsResp.status) $($defsResp.text)" }
    try { $null = GetJson $BaseB ('/api/tasks/' + $taskId) } catch {
        throw ("SharedDb 未生效：B 侧看不到任务 #$taskId（" + $_.Exception.Message +
            "）。请确认 -SharedDb（H2 AUTO_SERVER、绝对路径）或改用 H3 变体（不加 -SharedDb）。")
    }
    return $taskId
}

# 跨实例单轮：每轮新任务 + A/B 各一个新 run，同刻发令 → 锁竞态；返回 Test-ResourceLockRound 结果。
# 基础设施错误（批准非 200 / 缺终帧 / 锁不释放）直接抛；"窗口没重叠"只算 miss，不抛。
function Invoke-ResourceLockSharedRound($round) {
    $taskId = New-SharedExportTask ("b23l1-lock-shared-r$round-")
    # 同 taskId 的两条消息必须互异（桩的会话指纹 = 首条 user 消息文本）：加后缀但不引入前置数字，
    # extractTaskId 仍取到同一个 taskId；否则后到的一条会因 bucket.used 已占 TC16-EXPORT 退化为文本轮。
    $runA = New-SuspendedExportRun ("b23l1-lockA$round-") ("lock-shared-A$round") $taskId $BaseA '（共享库并发 A 侧）'
    $runB = New-SuspendedExportRun ("b23l1-lockB$round-") ("lock-shared-B$round") $taskId $BaseB '（共享库并发 B 侧）'
    Say ("  第 $round 轮：task=#$taskId；A run=" + $runA.runId + "；B run=" + $runB.runId)

    $fireAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 2000
    $jobA = Start-PostJob $BaseA '/api/ai/confirm' @{ runId = $runA.runId; toolCallId = $runA.toolCallId; approved = $true; reason = "b23l1 shared lock r$round A" } $fireAt
    $jobB = Start-PostJob $BaseB '/api/ai/confirm' @{ runId = $runB.runId; toolCallId = $runB.toolCallId; approved = $true; reason = "b23l1 shared lock r$round B" } $fireAt
    $null = Wait-Job -Job @($jobA, $jobB) -Timeout 60
    $errA = @(); $errB = @()
    $rA = Receive-Job $jobA -ErrorAction SilentlyContinue -ErrorVariable errA
    $rB = Receive-Job $jobB -ErrorAction SilentlyContinue -ErrorVariable errB
    Remove-Job $jobA, $jobB -Force -ErrorAction SilentlyContinue
    foreach ($side in @(@{ r = $rA; e = $errA; s = 'A' }, @{ r = $rB; e = $errB; s = 'B' })) {
        if ($null -eq $side.r -or [int]$side.r.status -ne 200) {
            $msg = if (@($side.e).Count -gt 0) { ($side.e | ForEach-Object { $_.Exception.Message }) -join ' | ' } else { '（无作业错误）' }
            throw ("Shared 资源锁（第 $round 轮）：$($side.s) 侧批准失败：$msg")
        }
    }

    $fullA = Read-RunFullToTerminal $BaseA $runA.runId ("lock-shared-A-r$round") 120
    $fullB = Read-RunFullToTerminal $BaseB $runB.runId ("lock-shared-B-r$round") 120
    if ((Count-Frames $fullA 'done' $null) -lt 1) {
        throw "Shared 资源锁（第 $round 轮）：A 侧未收到终帧（已收：$(($fullA | ForEach-Object { $_.type }) -join ',')）"
    }
    if ((Count-Frames $fullB 'done' $null) -lt 1) {
        throw "Shared 资源锁（第 $round 轮）：B 侧未收到终帧（已收：$(($fullB | ForEach-Object { $_.type }) -join ',')）"
    }

    $dA = (GetJson $BaseA ('/api/ai/runs/' + $runA.runId)).data
    $dB = (GetJson $BaseB ('/api/ai/runs/' + $runB.runId)).data
    $jobs = @((GetJson $BaseA ('/api/tasks/' + $taskId + '/jobs')).data)
    $result = Test-ResourceLockRound $fullA $fullB $dA $dB $jobs.Count

    if ([bool]$result.Hit) {
        $lockKey = 'ai:lock:job:' + $taskId + ':EXPORT'
        if (-not (Wait-RedisKeyGone $lockKey 10)) {
            throw "执行完成后资源锁未释放：$lockKey=$(Invoke-RedisCommand @('GET', $lockKey))"
        }
    }
    return $result
}

# 跨实例变体（-SharedDb）：I-2 裁决 —— 两段临界区都是毫秒级，可能不重叠 ⇒ 最多重试 3 轮；
# 任一命中即 PASS，3 轮全未命中判 SKIP（不等于系统错，证据以 H3 确定性变体为准）。
function Case-Resource-Lock-Shared {
    $race = Invoke-ResourceLockRace { param($i) Invoke-ResourceLockSharedRound $i } 3
    foreach ($shape in $race.Shapes) { Say ('  竞态观察：' + $shape) }
    if ($race.Result -eq 'PASS') {
        Say ('  Shared 竞态命中：第 ' + $race.Rounds + ' 轮')
        return 'PASS'
    }
    return 'SKIP'
}

function Case-Idempotency {
    $name = 'idempotency（批③：同 runId+toolCallId 二次决策 409 DUPLICATE，执行/台账仍恰一次）'
    $run = New-SuspendedExportRun 'b23l1-idem-' 'idem-A'
    $full = Approve-SuspendedRun $BaseA $run 'idem-A' 'b23l1 idempotency first'
    $tr = @($full | Where-Object { $_.type -eq 'tool_result' -and $_.toolCallId -eq $run.toolCallId })
    if ($tr.Count -ne 1) { throw "首次决策 tool_result 期望 1 帧，实得 $($tr.Count)" }
    if (-not [bool]$tr[0].executed) { throw "首次决策应真实执行（executed=true），实得 code=$($tr[0].code)" }
    $detail1 = (GetJson $BaseA ('/api/ai/runs/' + $run.runId)).data
    if ([int]$detail1.ledgerCounts.start_export -ne 1) {
        throw "首次执行后台账 start_export 期望 1，实得 $($detail1.ledgerCounts.start_export)"
    }
    $jobs1 = @((GetJson $BaseA ('/api/tasks/' + $run.taskId + '/jobs')).data)
    if ($jobs1.Count -ne 1) { throw "首次执行后作业数期望 1，实得 $($jobs1.Count)" }

    # 第二次：同一 runId+toolCallId 打 B（决策 guard 已在 Redis，不触碰任务库）。
    $dup = PostJson $BaseB '/api/ai/confirm' @{ runId = $run.runId; toolCallId = $run.toolCallId; approved = $true; reason = 'b23l1 idempotency duplicate' }
    if ($null -eq $dup -or [int]$dup.status -ne 409) {
        throw "二次决策期望 HTTP 409，实得 status=$($dup.status) body=$($dup.text)"
    }
    $dupBody = $null
    try { $dupBody = $dup.text | ConvertFrom-Json } catch { $dupBody = $null }
    # 字段依据：AiController#confirm 的 DUPLICATE 分支 → duplicateToolCall（code=DUPLICATE_TOOL_CALL_ID）。
    if ($null -eq $dupBody -or [string]$dupBody.code -ne 'DUPLICATE_TOOL_CALL_ID') {
        throw "二次决策期望 code=DUPLICATE_TOOL_CALL_ID，实得 '$($dupBody.code)'：$($dup.text)"
    }

    $detail2 = (GetJson $BaseA ('/api/ai/runs/' + $run.runId)).data
    if ([int]$detail2.ledgerCounts.start_export -ne 1) {
        throw "二次决策后台账 start_export 仍应 1，实得 $($detail2.ledgerCounts.start_export)"
    }
    $jobs2 = @((GetJson $BaseA ('/api/tasks/' + $run.taskId + '/jobs')).data)
    if ($jobs2.Count -ne 1) { throw "二次决策后作业数仍应 1，实得 $($jobs2.Count)" }

    $full2 = Read-Sse $BaseA ("/api/ai/events/" + $run.runId + "?lastSeq=0") $null @('done', 'error') 60000 'idem-A-after'
    $starts = Count-Frames $full2 'tool_start' $run.toolCallId
    if ($starts -ne 1) { throw "二次决策后 tool_start 仍应恰 1，实得 $starts" }

    # 批③ 幂等索引只读取证：ai:idem:<tag> 的 field = <runId>:<toolCallId>（RunStore#idemField），
    # tag 带身份与 runId（租户任务 2）—— 幂等索引与台账必须同槽，否则 IDEM_PUSH 在原生 Cluster
    # 上 CROSSSLOT（炸点正好在"副作用已执行之后"）。
    $idemField = $run.runId + ':' + $run.toolCallId
    $idemKey = 'ai:idem:' + (Get-RunTag $run.runId)
    $idemVal = Invoke-RedisCommand @('HGET', $idemKey, $idemField)
    if ([string]::IsNullOrWhiteSpace([string]$idemVal)) {
        throw "幂等索引缺失：HGET $idemKey $idemField 为空"
    }
    Ok $name
}

# instance-kill 的「恰一次」中间证据（批③ TARGET_INVALID 前置拦截后新增形态乙）：
#   形态甲（共享库/生产形态）：台账恰 1 条且 executedBy=接管实例；
#   形态乙（H2 隔离，L-12 边界）：台账 0 条 + 全量回放里该 toolCallId 有 TARGET_INVALID 帧
#     （status=BLOCKED、executed=false）—— 证明是设计内前置拦截，不是丢消息/没执行。
# 返回 'A' / 'B'；其它组合抛错并带实得细节。抽成函数是为了离线用伪造帧序列验两形态。
function Assert-KillTakeoverEvidence($ledger, $full, $toolCallId, $bInstanceId, $ledgerCount) {
    $entries = @($ledger)
    if ($entries.Count -gt 1) {
        throw "台账该 toolCallId 期望 0 或 1 条（形态乙/甲），实得 $($entries.Count)"
    }
    if ($entries.Count -eq 1) {
        if ([string]$entries[0].executedBy -ne $bInstanceId) {
            throw "台账形态甲要求 executedBy=接管实例 $bInstanceId，实得 $($entries[0].executedBy)"
        }
        if ([int]$ledgerCount -ne 1) {
            throw "台账形态甲要求 ledgerCounts.start_export=1，实得 $ledgerCount"
        }
        return 'A'
    }
    $blocked = @($full | Where-Object {
        $_.type -eq 'tool_result' -and $_.toolCallId -eq $toolCallId -and [string]$_.code -eq 'TARGET_INVALID'
    })
    if ($blocked.Count -ne 1) {
        $seen = @($full | Where-Object { $_.type -eq 'tool_result' } | ForEach-Object {
            "code=$($_.code)/status=$($_.status)/executed=$($_.executed)"
        })
        throw ("台账 0 条但未找到恰 1 帧 TARGET_INVALID 拦截（实得 tool_result：" + ($seen -join '; ') + "）")
    }
    $bf = $blocked[0]
    if ([string]$bf.status -ne 'BLOCKED') {
        throw "TARGET_INVALID 帧 status 期望 BLOCKED，实得 '$($bf.status)'"
    }
    if ([bool]$bf.executed) {
        throw 'TARGET_INVALID 帧不应 executed=true（前置拦截 = 未执行）'
    }
    return 'B'
}

function Case-Instance-Kill {
    $name = 'instance-kill（批②：杀真 A 后 B 接管续跑；中间证据两形态）'
    if ($NoStart) {
        Skip $name '-NoStart：实例不是本脚本起的，无法按名字精确 kill（Stop-Child 只动自己的子进程）'
        return
    }
    try { $null = GetJson $BaseA '/api/ai/health' } catch {
        Skip $name ('instance-a 不可达（可能已被前序终止性用例杀掉）：' + $_.Exception.Message)
        return
    }
    $run = New-SuspendedExportRun 'b23l1-kill-' 'kill-A'
    $runId = $run.runId
    $toolCallId = $run.toolCallId
    $lastSeq = $run.lastSeq

    $healthB = GetJson $BaseB '/api/ai/health'
    $bInstanceId = [string]$healthB.instanceId
    if ([string]::IsNullOrWhiteSpace($bInstanceId)) { throw 'B 侧 /health 缺少 instanceId' }

    Stop-Child 'instance-a'
    Say ('  已杀 instance-a；B 侧 instanceId=' + $bInstanceId)

    # 杀 A 后 B 仍能重建该挂起轮（session→current 索引在 Redis，快照/条目也在）。
    $current = (GetJson $BaseB ('/api/ai/runs/current?sessionId=' + $run.sessionId)).data
    if ($null -eq $current -or -not [bool]$current.found) { throw "B 侧 /runs/current 未找到挂起轮（sessionId=$($run.sessionId)）" }
    if ([string]$current.runId -ne $runId) { throw "B 侧 /runs/current 指向 $($current.runId)，期望 $runId" }
    if ([string]$current.snapshotStatus -ne 'SUSPENDED') { throw "B 侧 /runs/current 快照状态期望 SUSPENDED，实得 $($current.snapshotStatus)" }
    if (-not [bool]$current.awaitingExternal) { throw 'B 侧 /runs/current awaitingExternal 期望 true' }
    $entry = @($current.pendingEntries | Where-Object { $_.toolCallId -eq $toolCallId })
    if ($entry.Count -lt 1) { throw "B 侧 /runs/current 未列出待决条目 $toolCallId" }

    # 决策落 B：首跳允许 LEASE_HELD_ELSEWHERE（A 的 30s 租约未过期，B 排一次延迟重试）。
    $resp = PostJson $BaseB '/api/ai/confirm' @{ runId = $runId; toolCallId = $toolCallId; approved = $true; reason = 'b23l1 instance-kill' }
    if ($null -eq $resp -or -not $resp.ok) { throw "B 侧决策被拒：HTTP $($resp.status) $($resp.text)" }
    $body = $null
    try { $body = $resp.text | ConvertFrom-Json } catch { $body = $null }
    if ($null -eq $body -or -not [bool]$body.accepted) { throw "B 侧决策未被接受：$($resp.text)" }
    $outcome = [string]$body.resumeOutcome
    if (@('LEASE_HELD_ELSEWHERE', 'SCHEDULED') -notcontains $outcome) {
        throw "B 侧 resumeOutcome 期望 LEASE_HELD_ELSEWHERE（租约未过期）或 SCHEDULED（已接管），实得 '$outcome'：$($resp.text)"
    }

    # 等终帧（预算 KillTakeoverTimeoutSec；LEASE_HELD_ELSEWHERE 时由 B 的延迟重试在租约过期后接管）。
    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=" + $lastSeq) $null @('done', 'error') ($KillTakeoverTimeoutSec * 1000) 'kill-B'
    $doneB = $framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1
    if ($null -eq $doneB) {
        throw "B 侧接管后未收到终帧（已收：$(($framesB | ForEach-Object { $_.type }) -join ',')）"
    }
    # tool_start 的"恰一次"要在**全量回放**里数：挂起前那帧（confirm_request 之前）不在增量里。
    $full = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=0") $null @('done', 'error') 60000 'kill-B-full'
    $starts = Count-Frames $full 'tool_start' $toolCallId
    if ($starts -ne 1) { throw "接管续跑 tool_start 期望恰 1 帧（全量回放），实得 $starts" }

    # 「恰一次」的中间证据两形态（批③ ToolTargetResolver 前置拦截后新增形态乙）：
    #   甲：台账恰 1 条且 executedBy=B（共享库/生产形态）；
    #   乙：台账 0 条 + 全量回放里该 toolCallId 有 TARGET_INVALID / status=BLOCKED / executed=false
    #       （L-12 H2 隔离：B 库无此任务，设计内拦截 = 闭环证据，不占 claim/不写台账/不执行）。
    # 字段依据：SseChatEmitter#toolResult（code/status/executed）、ToolOutcome.CODE_TARGET_INVALID、
    # ResumeService#describe（ledger/ledgerCounts）与 RunStore#appendLedger（executedBy）。
    $detail = (GetJson $BaseB ('/api/ai/runs/' + $runId)).data
    if ($null -eq $detail) { throw "B 侧 /runs/$runId 返回空" }
    $ledger = @($detail.ledger | Where-Object { $_.toolCallId -eq $toolCallId })
    $form = Assert-KillTakeoverEvidence $ledger $full $toolCallId $bInstanceId ([int]$detail.ledgerCounts.start_export)
    if ($form -eq 'A') {
        Say ('  台账形态甲（执行落账）：1 条，executedBy=B（' + $bInstanceId + '）')
    } else {
        Say ('  台账形态乙（L-12 边界：B 库无任务，ToolTargetResolver 前置拦截即闭环证据）：TARGET_INVALID/status=BLOCKED/executed=false，台账 0 条')
    }
    Ok $name
}

function Case-Lease-Scripts {
    $name = 'lease-scripts（批②：真 Redis 租约脚本冒烟 ACQUIRE/RENEW/FENCED-SET/RELEASE）'
    if ($NoStart) {
        Skip $name '-NoStart：无法 kill 实例制造租约真空'
        return
    }
    try { $null = GetJson $BaseA '/api/ai/health' } catch {
        Skip $name ('instance-a 不可达（可能已被前序终止性用例杀掉）：' + $_.Exception.Message)
        return
    }
    $hA = GetJson $BaseA '/api/ai/health'
    $hB = GetJson $BaseB '/api/ai/health'
    if (-not [bool]$hA.leaseOk) { throw 'A 侧 /health leaseOk=false（真 Redis 租约设施不可用）' }
    if (-not [bool]$hB.leaseOk) { throw 'B 侧 /health leaseOk=false（真 Redis 租约设施不可用）' }
    $bInstanceId = [string]$hB.instanceId

    $run = New-SuspendedExportRun 'b23l1-lease-' 'lease-A'
    $runId = $run.runId
    $toolCallId = $run.toolCallId
    $lastSeq = $run.lastSeq

    $detail = (GetJson $BaseA ('/api/ai/runs/' + $runId)).data
    $leaseKey = [string]$detail.keys.lease
    if ([string]::IsNullOrWhiteSpace($leaseKey)) { throw 'A 侧 /runs 未给出续跑租约键（keys.lease）' }
    $leaseVal = Invoke-RedisCommand @('GET', $leaseKey)
    if ([string]::IsNullOrWhiteSpace([string]$leaseVal)) { throw "ACQUIRE 未生效：Redis 中 $leaseKey 不存在" }
    Say ("  租约已存在：" + $leaseKey + " = " + $leaseVal)

    # 批① Cluster 硬阻断取证（R1-01 + 评审 I-1）：epoch 取号在<b>与租约键同槽的 per-run 计数器</b>上
    # （`ai:lease:epoch:<tag>`，tag = `{<tenant>:<user>:run:<runId>}`），`INCR + SET` 在<b>同一条 Lua</b> 里 —— 号与占用必然同序。
    # 判据三条：① 租约值仍是 `<epoch>|<owner>` 且 > 0；② 计数器键存在且 == 该租约 epoch
    # （脚本内的 INCR 是这一任的唯一号源）；③ 旧全局 `ai:lease:epoch` <b>不再</b>参与 run 租约
    # （资源锁仍会用它，所以它可能存在，但不能等于/小于本租约 epoch 的"号源校验"）。
    $leaseEpoch = [int64](([string]$leaseVal).Split('|')[0])
    if ($leaseEpoch -le 0) { throw "租约值形态异常（期望 <epoch>|<owner>，实得 '$leaseVal'）：epoch 未由计数器发放" }
    $runTag = Get-RunTag $runId
    $runEpochKey = 'ai:lease:epoch:' + $runTag
    $epochRaw = Invoke-RedisCommand @('GET', $runEpochKey)
    if ([string]::IsNullOrWhiteSpace([string]$epochRaw)) {
        throw ('取号未落 per-run 计数器 ' + $runEpochKey + '（取号没有回到脚本内？）')
    }
    if ([int64]$epochRaw -ne $leaseEpoch) {
        throw ('per-run 计数器与租约 epoch 不一致：' + $runEpochKey + '=' + $epochRaw + '，租约 epoch=' + $leaseEpoch +
            '（号源不是同一条原子步 ⇒ 存在迟到者用更小号占租的窗口）')
    }
    Say ("  ACQUIRE 证据：epoch=" + $leaseEpoch + "，per-run 计数器 " + $runEpochKey + "=" + $epochRaw + "（脚本内 INCR+SET 同槽原子）")

    # ── 批① 收口：一轮 13 键同槽 + 真实写读（R1-05 / R1-02 / R1-03 / 评审 I-1） ──────────────
    # 判据三条：① slot 算法自检（Redis 官方示例 foo=12182）；② 13 个键全部带带身份的 run tag
    # （`{<tenant>:<user>:run:<runId>}`，租户任务 2）
    # 且落同一槽（deleteRun 的那条 13 键 DEL 才可能原子生效）；③ 帧/序号/req 位真的写在 tagged 键上
    # —— 写方（RedisFrameStore / RunStore）与删方键形不一致时，第 ③ 条必红。
    # 说明：deleteRun 目前**没有生产调用点**（池饱和回滚用），所以"13 键一条 DEL 原子生效"的取证
    # 落在单测 ClusterSlotTest；harness 这边能证的是"13 键同槽 + 写读同一批键"。
    if ((Get-RedisSlot 'foo') -ne 12182 -or (Get-RedisSlot 'bar') -ne 5061) {
        throw ("slot 自检失败：foo/bar 期望 12182/5061，实得 " + (Get-RedisSlot 'foo') + "/" + (Get-RedisSlot 'bar') + "（CRC16 或 hash tag 实现不对，后续同槽判定不可信）")
    }
    $tag = Get-RunTag $runId
    $keyRun = 'ai:run:' + $tag
    $keyPending = 'ai:pending:' + $tag
    $keyClaim = 'ai:claim:' + $tag
    $keyLedger = 'ai:ledger:' + $tag
    $keyIdem = 'ai:idem:' + $tag
    $keyEvents = 'ai:events:' + $tag
    $keySeq = 'ai:seq:' + $tag
    $keyXevents = 'ai:xevents:' + $tag
    $keyLease = 'ai:lease:run:' + $tag
    $keyFence = 'ai:fence:run:' + $tag
    $keyReq = 'ai:req:' + $tag
    $keyDecided = 'ai:decided:' + $tag
    $keyEpoch = 'ai:lease:epoch:' + $tag
    $family = @($keyRun, $keyPending, $keyClaim, $keyLedger, $keyIdem, $keyEvents, $keySeq, $keyXevents, $keyLease, $keyFence, $keyReq, $keyDecided, $keyEpoch)
    $slots = @($family | ForEach-Object { Get-RedisSlot $_ } | Sort-Object -Unique)
    if ($slots.Count -ne 1) {
        $detailLines = ($family | ForEach-Object { "    $_ -> slot " + (Get-RedisSlot $_) }) -join "`n"
        throw ("13 键不同槽（期望同槽，实得 $($slots.Count) 个槽）：`n" + $detailLines)
    }
    if ($leaseKey -ne $keyLease) {
        throw ("租约键形不符（服务端 keys.lease=$leaseKey，期望 $keyLease）：写新删旧/写旧删新会让 deleteRun 与续期落空")
    }
    Say ("  13 键同槽证据：slot=" + $slots[0] + "（run/pending/claim/ledger/idem/events/seq/xevents/lease/fence/req/decided/epoch）")

    # 真实写读（帧族三键）：Stream 有帧、序号已发放、请求帧位已置位 —— 三者都在 tagged 键上。
    $xlen = Invoke-RedisCommand @('XLEN', $keyXevents)
    if ($null -eq $xlen -or [int64]$xlen -lt 1) {
        throw ("事件 Stream 未落在 tagged 键：" + $keyXevents + " XLEN=" + $xlen + "（写方仍在旧裸键名？）")
    }
    $seqRaw = Invoke-RedisCommand @('GET', $keySeq)
    if ([string]::IsNullOrWhiteSpace([string]$seqRaw) -or [int64]$seqRaw -le 0) {
        throw ("序号键未落在 tagged 键：" + $keySeq + " = '" + $seqRaw + "'（RedisFrameStore#seqKey 与 RunStore#seqKey 键形不一致？）")
    }
    $reqRaw = Invoke-RedisCommand @('HGET', $keyReq, $toolCallId)
    if ([string]::IsNullOrWhiteSpace([string]$reqRaw)) {
        throw ("请求帧位缺失：HGET " + $keyReq + " " + $toolCallId + " 为空")
    }
    Say ("  写读证据：XLEN " + $keyXevents + "=" + $xlen + "，ai:seq=" + $seqRaw + "，req 位已置位")

    # 等待方每 10s 续租一次：睡 12s 后 PTTL 应回到 ~28s（未续租则只剩 ~18s）——RENEW 执行证据。
    Start-Sleep -Seconds 12
    $pttl = Invoke-RedisCommand @('PTTL', $leaseKey)
    if ($null -eq $pttl) { throw "RENEW 取证失败：$leaseKey 在等待期消失" }
    if ([int64]$pttl -lt 22000) { throw "RENEW 未生效：等待 12s 后 PTTL=$pttl ms（期望 >22000）" }
    Say ("  RENEW 证据：等待 12s 后 PTTL=" + $pttl + "ms")

    # 再等 7s（避开 20s 那次续租），杀 A：最后一次续租在 ~10s，租约将于 ~40s 过期。
    Start-Sleep -Seconds 7
    Stop-Child 'instance-a'
    Say '  已杀 instance-a（lease-scripts 为终止性用例）'

    # 决策落 B：此刻 A 的租约未过期 ⇒ LEASE_HELD_ELSEWHERE（只落库 + 排延迟重试）。
    $resp = PostJson $BaseB '/api/ai/confirm' @{ runId = $runId; toolCallId = $toolCallId; approved = $true; reason = 'b23l1 lease-scripts' }
    if ($null -eq $resp -or -not $resp.ok) { throw "B 侧决策被拒：HTTP $($resp.status) $($resp.text)" }
    $body = $null
    try { $body = $resp.text | ConvertFrom-Json } catch { $body = $null }
    if ($null -eq $body -or -not [bool]$body.accepted) { throw "B 侧决策未被接受：$($resp.text)" }
    if ([string]$body.resumeOutcome -ne 'LEASE_HELD_ELSEWHERE') {
        throw "租约未过期时决策应报 LEASE_HELD_ELSEWHERE，实得 '$($body.resumeOutcome)'（时序漂移？）"
    }

    # 租约过期后两路并发 resume 抢同一把租约：恰一个 202 ACCEPTED、一个 409。
    $resumePath = '/api/ai/runs/' + $runId + '/resume'
    $deadline = (Get-Date).AddSeconds($KillTakeoverTimeoutSec)
    $winner = $null
    $conflict = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $fireAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 2000
        $j1 = Start-PostJob $BaseB $resumePath $null $fireAt
        $j2 = Start-PostJob $BaseB $resumePath $null $fireAt
        $null = Wait-Job -Job @($j1, $j2) -Timeout 60
        $r1 = Receive-Job $j1
        $r2 = Receive-Job $j2
        Remove-Job $j1, $j2 -Force -ErrorAction SilentlyContinue
        $pair = @($r1, $r2) | Where-Object { $null -ne $_ }
        $acc = @($pair | Where-Object { $_.status -eq 202 })
        $conf = @($pair | Where-Object { $_.status -eq 409 })
        if ($acc.Count -eq 1 -and $conf.Count -eq 1) {
            $winner = $acc[0]
            $conflict = $conf[0]
            break
        }
        $busy = @($pair | Where-Object { $_.status -eq 409 -and ($_.text -match 'ALREADY_RUNNING|ALREADY_DONE') })
        if ($busy.Count -ge 1) { break }
    }
    if ($null -eq $winner) {
        throw "并发 resume 未观测到「恰一个 202 + 一个 409」（预算 ${KillTakeoverTimeoutSec}s；可能延迟重试先接管）"
    }
    $confBody = $null
    try { $confBody = $conflict.text | ConvertFrom-Json } catch { $confBody = $null }
    if ($null -eq $confBody -or @('LEASE_HELD_ELSEWHERE', 'IN_PROGRESS', 'RESUME_IN_PROGRESS', 'ALREADY_RUNNING') -notcontains [string]$confBody.reason) {
        throw "并发 resume 败方 reason 期望 LEASE_HELD_ELSEWHERE/IN_PROGRESS，实得 '$($confBody.reason)'：$($conflict.text)"
    }
    # 观察点（不阻断）：胜方 resume 返回后、终态前的租约。正确实现下应为续跑方持有；
    # 若此处已是 (nil) 且随后 fence / ledger.epoch 缺失 ⇒ 胜方租约被并发败方释放（fencing 失效）。
    $leaseMid = Invoke-RedisCommand @('GET', $leaseKey)
    Say ("  租约观察（并发 resume 后、终态前）：" + $(if ($null -eq $leaseMid) { '(nil)' } else { [string]$leaseMid }))

    # 接管后续跑跑完：终帧 + tool_start 恰一次（全量回放）+ 台账 executedBy=B。
    $framesB = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=" + $lastSeq) $null @('done', 'error') 120000 'lease-B'
    if ($null -eq ($framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1)) {
        throw "接管后续跑未收到终帧（已收：$(($framesB | ForEach-Object { $_.type }) -join ',')）"
    }
    $full = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=0") $null @('done', 'error') 60000 'lease-B-full'
    $starts = Count-Frames $full 'tool_start' $toolCallId
    if ($starts -ne 1) { throw "接管续跑 tool_start 期望恰 1 帧（全量回放），实得 $starts" }

    $final = (GetJson $BaseB ('/api/ai/runs/' + $runId)).data
    $ledger = @($final.ledger | Where-Object { $_.toolCallId -eq $toolCallId })
    if ($ledger.Count -ne 1) { throw "台账该 toolCallId 期望 1 条，实得 $($ledger.Count)" }
    if ([string]$ledger[0].executedBy -ne $bInstanceId) { throw "台账 executedBy 期望 B 实例 $bInstanceId，实得 $($ledger[0].executedBy)" }

    # FENCED-SET 三重证据（任一缺失都说明执行方没持租、save 走了 plain SET）：
    #   ① 真 Redis 的 fence 键（无 TTL，写过就一直在）② 快照 runEpoch ③ 台账 epoch（I5：执行时持有的 epoch）。
    $runEpoch = [int64]$final.snapshot.runEpoch
    $fenceKey = [string]$final.keys.fence
    $fenceVal = if ([string]::IsNullOrWhiteSpace($fenceKey)) { $null } else { Invoke-RedisCommand @('GET', $fenceKey) }
    $ledgerEpoch = [int64]$ledger[0].epoch
    $fenceShown = if ($null -eq $fenceVal) { '(nil)' } else { [string]$fenceVal }
    if ($runEpoch -le 0 -or $null -eq $fenceVal -or [int64]$fenceVal -le 0 -or $ledgerEpoch -le 0) {
        throw ("FENCED-SET 证据缺失：snapshot.runEpoch=$runEpoch、Redis $fenceKey=$fenceShown、ledger.epoch=$ledgerEpoch" +
            "（真 Redis 上无 fence 写 = 续跑执行时未持租，B 的 save 走了 plain SET）")
    }

    # RELEASE：终态 save 释放租约 → Redis 中该键应消失。
    $after = Invoke-RedisCommand @('GET', $leaseKey)
    if ($null -ne $after) { throw "RELEASE 未生效：终态后 Redis 仍存在 $leaseKey" }

    # 写读删的收口（批① 12 键）：执行完成后，台账/决策 guard 仍应留在各自的 tagged 键上；
    # 租约键（唯一会被终态主动删掉的那个）已在上一条验过消失。三者键形不一致时这里会红。
    $ledgerLen = Invoke-RedisCommand @('LLEN', $keyLedger)
    if ($null -eq $ledgerLen -or [int64]$ledgerLen -ne 1) {
        throw ("台账键 " + $keyLedger + " LLEN=" + $ledgerLen + "（期望 1；写方/读方键形不一致？）")
    }
    $decidedRaw = Invoke-RedisCommand @('HGET', $keyDecided, $toolCallId)
    if ([string]::IsNullOrWhiteSpace([string]$decidedRaw)) {
        throw ("决策 guard 缺失：HGET " + $keyDecided + " " + $toolCallId + " 为空")
    }
    Say ("  写读删证据：台账 1 条、decided=" + $decidedRaw + "、租约键已删（deleteRun 的 12 键单 DEL 由 ClusterSlotTest 取证）")
    Say ("  租约脚本覆盖：ACQUIRE / RENEW / FENCED-SET(fence=" + $fenceShown + ",runEpoch=" + $runEpoch + ",ledgerEpoch=" + $ledgerEpoch + ") / RELEASE(键已删) 各命中一次")
    Ok $name
}

# ── 防护批③ W5：跨实例限流（"共享 Redis 计数，不按实例放大 2×"） ─────────────
# 判据：A 侧 11 次 + B 侧 10 次（同一 sessionId）⇒ 第 21 次必须 429。
# 若计数是进程内的，A 只会数到 11、B 数到 10，第 21 次仍会被放行 —— 这条用例正是那个差别的唯一证据。
#
# 探针用**超长 message**（8193 字符）：批① 裁决 I2 明确"超长请求同样消耗额度"，
# 因此不必真跑 21 轮模型（省时省桩），而限流判据不变（限流排在归一化/长度之前）。
#
# 守卫（SKIP 而不是假绿）：
#   · 首跳就 503（AI 未装配 / Redis 不可用）⇒ 闸门根本没跑，SKIP 并点名；
#   · 首跳就 429 ⇒ 上一个同名前缀的窗口没退，SKIP（同一分钟内不要连跑两次）。
function Case-RateLimit-Shared {
    $name = 'rate-limit-shared（防护批③：A11+B10 同一 sessionId → 第 21 次 429）'
    $sessionId = New-AiSession $BaseA 'b23l1-ratelimit'
    $probe = '中' * 8193
    $other429 = 0
    $bad = @()

    for ($i = 1; $i -le 20; $i++) {
        $base = if ($i -le 11) { $BaseA } else { $BaseB }
        $resp = PostJson $base '/api/ai/chat' @{ sessionId = $sessionId; message = $probe }
        if ($null -eq $resp) { throw "第 $i 次 POST /api/ai/chat 无响应（$base）" }
        $status = [int]$resp.status
        if ($status -eq 503 -and $i -eq 1) {
            Skip $name ('首跳即 503（AI 未装配或 Redis 不可用），限流闸门未运行：HTTP 503 ' + $resp.text)
            return
        }
        if ($status -eq 429) {
            $other429++
            if ($i -eq 1) {
                Skip $name '首跳即 429，窗口未退（同一分钟内不要连跑本用例）'
                return
            }
        }
        elseif ($status -ne 400) {
            $bad += ("第 " + $i + " 次=" + $status)
        }
    }
    if ($bad.Count -gt 0) {
        throw ("额度内的 20 次应全部 400 MESSAGE_TOO_LONG，实得异常状态：" + ($bad -join ', '))
    }
    if ($other429 -gt 0) {
        throw ("额度内出现 $other429 次 429（20 次额度被提前耗尽：检查 app.ai.limit.session-per-minute 是否为 20）")
    }

    # 第 21 次打 A：跨实例累计已满 ⇒ 429 + Retry-After + scope=session
    $last = PostJson $BaseA '/api/ai/chat' @{ sessionId = $sessionId; message = $probe }
    if ($null -eq $last) { throw '第 21 次 POST /api/ai/chat 无响应（A 侧）' }
    if ([int]$last.status -ne 429) {
        throw ("第 21 次期望 429（共享 Redis 计数），实得 HTTP " + $last.status + "：跨实例计数可能被按实例各算一份")
    }
    $body = $null
    try { $body = $last.text | ConvertFrom-Json } catch { $body = $null }
    if ($null -eq $body -or [string]$body.code -ne 'RATE_LIMITED') {
        throw ("第 21 次期望 code=RATE_LIMITED，实得 '" + $body.code + "'：" + $last.text)
    }
    if ([string]$body.scope -ne 'session') {
        throw ("期望 scope=session（同 sessionId 额度），实得 '" + $body.scope + "'")
    }
    Ok $name
}

# ── 主流程 ──────────────────────────────────────────────────────────────────

$pendingCases = @{
    # 批③ 两条已实作；保留空表给未知批③后续条目（switch 的 default 仍按此表给 Skip 文案）。
}

# 批③ SharedDb：两实例共享同一 H2 文件（绝对路径 + AUTO_SERVER=TRUE）。
# AUTO_SERVER 让第一个 JVM 兼作 H2 服务端，第二个 JVM 通过本机 TCP 连同一文件；
# 这是跨实例同 taskId 用例（L-12）的前提。未开时 resource-lock 走 H3 预置锁变体。
$sharedDbUrl = $null
if ($SharedDb) {
    $sharedDbDir = Join-Path $ArtifactDir 'h2'
    New-Item -ItemType Directory -Force -Path $sharedDbDir | Out-Null
    $sharedDbPath = (Join-Path $sharedDbDir 'b23l1_shared').Replace('\', '/')
    $sharedDbUrl = 'jdbc:h2:file:' + $sharedDbPath + ';AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1'
    Say ('  SharedDb：两实例共享 H2 → ' + $sharedDbUrl)
}

try {
    # 守卫（不是警告）：-SharedDb 时 A 兼作 H2 AUTO_SERVER 服务端，杀 A 会断开 B 的共享库连接 ——
    # 与 instance-kill/lease-scripts 同棒必然半路崩，提前 FAIL 点名（两棒分开跑）。
    if ($SharedDb -and ($Cases -match 'instance-kill|lease-scripts')) {
        throw ('-SharedDb 不能与 instance-kill/lease-scripts 同棒：杀 A 会断开 B 的 H2 AUTO_SERVER 连接。' +
            '请分两棒：① -SharedDb -Cases resource-lock（跨实例锁）② 不加 -SharedDb 跑 instance-kill/lease-scripts。')
    }
    if (-not $NoStart) {
        $jarPath = if ([System.IO.Path]::IsPathRooted($Jar)) { $Jar } else { Join-Path $RepoRoot $Jar }
        if (-not (Test-Path $jarPath)) { throw "找不到后端 jar：$jarPath（先跑 mvn -DskipTests package）" }

        Assert-PortFree $PortA '实例 A'
        Assert-PortFree $PortB '实例 B'
        Assert-PortFree $StubPort '上游桩'
        # Redis 是双实例的事件日志/唤醒面：harness 不自起，反向预检不通就立刻 FAIL（文案点名 host:port）。
        Assert-RedisReachable $RedisHost $RedisPort

        $stubLogDir = Join-Path $ArtifactDir 'stub'
        New-Item -ItemType Directory -Force -Path $stubLogDir | Out-Null
        Start-Child 'stub' 'node' ("scripts/ci/stub-upstream.mjs --port " + $StubPort + " --dir `"" + $stubLogDir + "`"") $RepoRoot $null | Out-Null

        $pairs = @(@{ name = 'instance-a'; port = $PortA }, @{ name = 'instance-b'; port = $PortB })
        if ($SharedDb) {
            # 交错启动：A 先起并等健康（H2 AUTO_SERVER 服务端就绪），再起 B 连接同一文件。
            Start-Instance $pairs[0] $jarPath
            try { Wait-Health $BaseA '实例 A' } catch {
                throw ("SharedDb 启动失败（H2 AUTO_SERVER 共享不可行）：" + $_.Exception.Message +
                    "。可用 resource-lock 的 H3 预置锁变体（不加 -SharedDb）继续验证 RESOURCE_LOCKED。")
            }
            Start-Instance $pairs[1] $jarPath
            try { Wait-Health $BaseB '实例 B' } catch {
                throw ("SharedDb 启动失败（H2 AUTO_SERVER 共享不可行）：实例 B 连不上 A 打开的共享库 —— " + $_.Exception.Message +
                    "。可用 resource-lock 的 H3 预置锁变体（不加 -SharedDb）继续验证 RESOURCE_LOCKED。")
            }
        } else {
            foreach ($pair in $pairs) { Start-Instance $pair $jarPath }
            Wait-Health $BaseA '实例 A'
            Wait-Health $BaseB '实例 B'
        }
    } else {
        Wait-Health $BaseA '实例 A（-NoStart）'
        Wait-Health $BaseB '实例 B（-NoStart）'
    }

    Say ''
    Say ('用例：' + $Cases)
    foreach ($case in ($Cases -split ',')) {
        $c = $case.Trim()
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        try {
            switch ($c) {
                'reattach-full-replay' { Case-Reattach-Full-Replay }
                'reattach-diff' { Case-Reattach-Diff }
                'suspend-reattach-wake' { Case-Suspend-Reattach-Wake }
                'suspend-decision' { Case-Suspend-Decision }
                'suspend-decision-concurrent' { Case-Suspend-Decision-Concurrent }
                'resource-lock' { Case-Resource-Lock }
                'idempotency' { Case-Idempotency }
                'instance-kill' { Case-Instance-Kill }
                'lease-scripts' { Case-Lease-Scripts }
                'rate-limit-shared' { Case-RateLimit-Shared }
                default {
                    if ($pendingCases.ContainsKey($c)) { Skip $c $pendingCases[$c] }
                    else { No $c '未知用例名' }
                }
            }
        } catch {
            No $c $_.Exception.Message
        }
    }
} catch {
    No 'harness' $_.Exception.Message
} finally {
    Say ''
    # L-5 挂账取证：confirm POST 完成 → B 首续跑帧的客户端墙钟延迟（默认用例集里只有 wake 产出样本）。
    if ($script:frameSamples.Count -gt 0) {
        $p50 = Get-Percentile $script:frameSamples 50
        $p95 = Get-Percentile $script:frameSamples 95
        Say ("帧延迟（A confirm 完成 → B 首续跑帧）：P50=" + [int64]$p50 + "ms P95=" + [int64]$p95 + "ms 样本=" + $script:frameSamples.Count + "（样本少时 P50=P95=单值）")
    }
    Say ("摘要：PASS=" + $script:passed + " FAIL=" + $script:failed + " SKIP=" + $script:skipped)
    Say ("证据目录：" + $ArtifactDir)
    if (-not $KeepAlive) { Stop-Children }
    try { [Console]::remove_CancelKeyPress($script:cancelHandler) } catch { }
    try { $script:http.Dispose() } catch { }
}

if ($script:failed -gt 0) { exit 1 }
exit 0
