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

  用例面（默认 = 批① 三条 + 批② 两条；批③ 两条仍 SKIP 并打印原因）：
    · reattach-full-replay   一轮在 A 跑完，B 侧从 0 回放得到逐帧同序同号（连接层无状态）
    · reattach-diff          同一轮在 B 侧用 ?lastSeq 只拿到差量，不重不漏
    · suspend-reattach-wake  A 挂起 → B 侧重挂 → 在 A 批准确认 → 续跑帧经 ai:wake: 扇出到 B 的连接
    · suspend-decision       A 挂起 → 决策打 B（A 的等待方持租）→ 续跑只发生一次，双实例都能重挂到终帧
    · instance-kill          A 挂起后杀真 A → 决策打 B → 租约过期后 B 接管续跑（台账 executedBy=B）
    · resource-lock          批③ 依赖（跨实例资源互斥）—— SKIP
    · idempotency            批③ 依赖（runId:toolCallId 幂等）—— SKIP

  非默认可选用例（显式 -Cases 指定）：
    · suspend-decision-concurrent  同一 runId/toolCallId 双路并发决策（恰一个 200、一个 409）
    · lease-scripts                真 Redis 租约脚本冒烟（ACQUIRE/RENEW/FENCED-SET/RELEASE；
                                   会 kill instance-a，须排在最后或单独跑）

.PARAMETER NoStart
  不自起服务：只用已就绪的 8081/8082（脚本仍做健康等待，不达标即整棒 FAIL）。

.PARAMETER KeepAlive
  用例跑完不杀进程（默认会收掉本脚本起来的全部子进程）。

.PARAMETER Cases
  逗号分隔的用例名；缺省跑批①+批② 五条（instance-kill 会杀 A，恒为最后一条）。

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
    [string]$Cases = 'reattach-full-replay,reattach-diff,suspend-reattach-wake,suspend-decision,instance-kill',
    [int]$HealthTimeoutSec = 120,
    [int]$WakeReadyTimeoutSec = 30,
    [int]$KillTakeoverTimeoutSec = 90,
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

# Ctrl-C：先收干净子进程再退出（130 = 128 + SIGINT），不让 java/node 变成孤儿进程。
$script:cancelHandler = [ConsoleCancelEventHandler]{
    param($sender, $e)
    $e.Cancel = $true
    try { Stop-Children } catch { }
    exit 130
}
[Console]::add_CancelKeyPress($script:cancelHandler)

function GetJson($base, $path) {
    $resp = $script:http.GetAsync($base + $path).Result
    # .NET Framework 在连不上/被取消时 .Result 会回 $null（不抛）——显式报"不可达"，避免空值方法调用。
    if ($null -eq $resp) { throw "GET $path 未取得响应（$base 不可达？）" }
    $text = $resp.Content.ReadAsStringAsync().Result
    if (-not $resp.IsSuccessStatusCode) { throw "GET $path => HTTP $([int]$resp.StatusCode) $text" }
    return ($text | ConvertFrom-Json)
}

function PostJson($base, $path, $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 20 -Compress }
    $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $resp = $null
    try {
        $resp = $script:http.PostAsync($base + $path, $content).Result
        if ($null -eq $resp) { throw "POST $path 未取得响应（$base 不可达？）" }
        $text = $resp.Content.ReadAsStringAsync().Result
        return @{ status = [int]$resp.StatusCode; text = $text; ok = $resp.IsSuccessStatusCode }
    } finally {
        if ($resp) { $resp.Dispose() }
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
    return Start-Job -ArgumentList $base, $path, $json, $fireAtMs -ScriptBlock {
        param($base, $path, $json, $fireAtMs)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Net.Http
        $http = New-Object System.Net.Http.HttpClient
        $http.Timeout = [TimeSpan]::FromSeconds(60)
        $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
        # 并发用例用同一个墙钟时刻做"发令枪"：作业启动耗时不均，不校准就变成先后两枪。
        if ($fireAtMs -gt 0) {
            $waitMs = $fireAtMs - [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            if ($waitMs -gt 0) { Start-Sleep -Milliseconds $waitMs }
        }
        $resp = $null
        try {
            $resp = $http.PostAsync($base + $path, $content).Result
            if ($null -eq $resp) { throw "POST $path 未取得响应（$base 不可达？）" }
            $status = [int]$resp.StatusCode
            $text = $resp.Content.ReadAsStringAsync().Result
            return @{ status = $status; text = $text }
        } finally {
            if ($resp) { $resp.Dispose() }
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

# 挂起骨架（wake / suspend-decision / instance-kill / lease-scripts 共用）：
# 建真实 EXPORT 任务 → select-defs → 带 context 发 start_export → 读到 confirm_request 为止。
# 返回 @{ sessionId; taskId; runId; toolCallId; lastSeq }。
function New-SuspendedExportRun($prefix, $tag) {
    $sessionId = $prefix + [guid]::NewGuid().ToString('N').Substring(0, 8)
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

    $context = @{ page = 'tasks'; taskType = 'EXPORT'; step = 'EXPORT'; taskId = $taskId }
    $message = 'start_export 帮我导出 taskId=' + $taskId
    $framesA = Read-Sse $BaseA '/api/ai/chat' @{ sessionId = $sessionId; message = $message; context = $context } @('confirm_request', 'frontend_tool_request', 'error') 120000 $tag
    $runId = $null
    $toolCallId = $null
    $lastSeq = 0
    foreach ($f in $framesA) {
        if ($f.type -eq 'start') { $runId = $f.runId }
        if ($f.type -eq 'confirm_request') { $toolCallId = $f.toolCallId }
        if ($null -ne $f.seq) { $lastSeq = [int64]$f.seq }
    }
    if (-not $runId) { throw 'A 侧未收到 start 帧（runId 缺失）' }
    if (-not $toolCallId) { throw 'A 侧未出现确认卡（桩路由 start_export 未触发）' }
    return @{ sessionId = $sessionId; taskId = $taskId; runId = $runId; toolCallId = $toolCallId; lastSeq = $lastSeq }
}

# ── 用例 ────────────────────────────────────────────────────────────────────

function Case-Reattach-Full-Replay {
    $name = 'reattach-full-replay（批①：连接层无状态，B 侧可全量回放 A 的帧）'
    $sessionId = 'b23l1-full-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
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
    $sessionId = 'b23l1-diff-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
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
    $job = Start-Job -ArgumentList $BaseB, $runId, $lastSeq, $readyFile -ScriptBlock {
        param($base, $runId, $lastSeq, $readyFile)
        $ErrorActionPreference = 'Stop'
        Add-Type -AssemblyName System.Net.Http
        $http = New-Object System.Net.Http.HttpClient
        $http.Timeout = [TimeSpan]::FromMinutes(2)
        $uri = New-Object System.Uri($base + '/api/ai/events/' + $runId + '?lastSeq=' + $lastSeq)
        # 先把订阅请求发出去（异步、不等响应头），再判就绪。
        $task = $http.GetAsync($uri, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
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

function Case-Instance-Kill {
    $name = 'instance-kill（批②：杀真 A 后 B 接管续跑，台账 executedBy=B）'
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

    # 台账：该工具恰 1 条、executedBy = B 的 instanceId、ledgerCounts=1。
    # 字段依据：ResumeService#describe（ledger/ledgerCounts/instanceId）与 RunStore#appendLedger（executedBy）。
    $detail = (GetJson $BaseB ('/api/ai/runs/' + $runId)).data
    if ($null -eq $detail) { throw "B 侧 /runs/$runId 返回空" }
    $ledger = @($detail.ledger | Where-Object { $_.toolCallId -eq $toolCallId })
    if ($ledger.Count -ne 1) { throw "台账该 toolCallId 期望 1 条，实得 $($ledger.Count)" }
    if ([string]$ledger[0].executedBy -ne $bInstanceId) {
        throw "台账 executedBy 期望 B 实例 $bInstanceId，实得 $($ledger[0].executedBy)"
    }
    if ([int]$detail.ledgerCounts.start_export -ne 1) {
        throw "ledgerCounts.start_export 期望 1，实得 $($detail.ledgerCounts.start_export)"
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
    Say ("  租约脚本覆盖：ACQUIRE / RENEW / FENCED-SET(fence=" + $fenceShown + ",runEpoch=" + $runEpoch + ",ledgerEpoch=" + $ledgerEpoch + ") / RELEASE(键已删) 各命中一次")
    Ok $name
}

# ── 主流程 ──────────────────────────────────────────────────────────────────

$pendingCases = @{
    'resource-lock'    = '批③ 未落地：资源互斥租约（RESOURCE_LOCKED）'
    'idempotency'      = '批③ 未落地：工具副作用幂等键'
}

try {
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

        foreach ($pair in @(@{ name = 'instance-a'; port = $PortA }, @{ name = 'instance-b'; port = $PortB })) {
            $runDir = Join-Path $ArtifactDir $pair.name
            New-Item -ItemType Directory -Force -Path $runDir | Out-Null
            $javaArgs = @(
                '-jar', "`"$jarPath`"",
                "--server.port=$($pair.port)",
                "--spring.data.redis.host=$RedisHost",
                "--spring.data.redis.port=$RedisPort",
                "--spring.datasource.url=jdbc:h2:file:./data/b23l1_$($pair.name);DB_CLOSE_DELAY=-1",
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

        Wait-Health $BaseA '实例 A'
        Wait-Health $BaseB '实例 B'
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
                'instance-kill' { Case-Instance-Kill }
                'lease-scripts' { Case-Lease-Scripts }
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
