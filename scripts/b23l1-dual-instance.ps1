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

  用例面（本节只列<可以现在跑>的批①用例；批②③ 依赖未落地，一律 SKIP 并打印原因）：
    · reattach-full-replay   一轮在 A 跑完，B 侧从 0 回放得到逐帧同序同号（连接层无状态）
    · reattach-diff          同一轮在 B 侧用 ?lastSeq 只拿到差量，不重不漏
    · suspend-reattach-wake  A 挂起 → B 侧重挂 → 在 A 批准确认 → 续跑帧经 ai:wake: 扇出到 B 的连接
    · suspend-decision       批②（fencing 租约）依赖 —— SKIP
    · instance-kill          批② 依赖（A 死后 B 接管续跑）—— SKIP
    · resource-lock          批③ 依赖（跨实例资源互斥）—— SKIP
    · idempotency            批③ 依赖（runId:toolCallId 幂等）—— SKIP

.PARAMETER NoStart
  不自起服务：只用已就绪的 8081/8082（脚本仍做健康等待，不达标即整棒 FAIL）。

.PARAMETER KeepAlive
  用例跑完不杀进程（默认会收掉本脚本起来的全部子进程）。

.PARAMETER Cases
  逗号分隔的用例名；缺省跑全部批①用例。

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
    [string]$Cases = 'reattach-full-replay,reattach-diff,suspend-reattach-wake',
    [int]$HealthTimeoutSec = 120,
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
$BaseA = "http://127.0.0.1:$PortA"
$BaseB = "http://127.0.0.1:$PortB"
$StubBase = "http://127.0.0.1:$StubPort"

$script:passed = 0
$script:failed = 0
$script:skipped = 0
$script:children = New-Object System.Collections.ArrayList
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
    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action {
        if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
    } -MessageData $writer | Out-Null
    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action {
        if ($EventArgs.Data) { $Event.MessageData.WriteLine($EventArgs.Data) }
    } -MessageData $writer | Out-Null
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()
    $script:children.Add(@{ name = $name; proc = $proc; log = $log }) | Out-Null
    return $proc
}

function Stop-Children {
    foreach ($child in $script:children) {
        try {
            if (-not $child.proc.HasExited) {
                Say ("  收尾：停止 " + $child.name + "（pid " + $child.proc.Id + "）")
                $child.proc.Kill($true)
            }
        } catch { }
    }
    $script:children.Clear()
}

function GetJson($base, $path) {
    $resp = $script:http.GetAsync($base + $path).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    if (-not $resp.IsSuccessStatusCode) { throw "GET $path => HTTP $([int]$resp.StatusCode) $text" }
    return ($text | ConvertFrom-Json)
}

function PostJson($base, $path, $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 20 -Compress }
    $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $resp = $script:http.PostAsync($base + $path, $content).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    return @{ status = [int]$resp.StatusCode; text = $text; ok = $resp.IsSuccessStatusCode }
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
        $reader.Dispose(); $resp.Dispose(); $cts.Dispose()
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
    $sessionId = 'b23l1-wake-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    # 桩路由 start_export ⇒ 确认门（CONFIRM）⇒ 挂起
    $framesA = Read-Sse $BaseA '/api/ai/chat' @{ sessionId = $sessionId; message = 'start_export 帮我导出 taskId=2' } @('confirm_request', 'frontend_tool_request', 'error') 120000 'wake-A'
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

    # B 侧重挂：先拿一次差量快照（证明回放可用），再挂一条长连接等唤醒
    $snapshot = Read-Sse $BaseB ("/api/ai/events/" + $runId + "?lastSeq=" + $lastSeq) $null @() 4000 'wake-B-snapshot'

    # 长连接在后台作业里读（主线程去 A 批准确认）；作业用默认凭据与自己的 HttpClient
    $job = Start-Job -ArgumentList $BaseB, $runId, $lastSeq -ScriptBlock {
        param($base, $runId, $lastSeq)
        Add-Type -AssemblyName System.Net.Http
        $http = New-Object System.Net.Http.HttpClient
        $http.Timeout = [TimeSpan]::FromMinutes(2)
        $resp = $http.GetAsync($base + '/api/ai/events/' + $runId + '?lastSeq=' + $lastSeq,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
        $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result)
        $out = New-Object System.Collections.ArrayList
        $deadline = (Get-Date).AddSeconds(90)
        while ((Get-Date) -lt $deadline) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($data -and $data -ne '[DONE]') {
                    try { $out.Add(($data | ConvertFrom-Json)) | Out-Null } catch { }
                    $t = $out[$out.Count - 1].type
                    if ($t -eq 'done' -or $t -eq 'error') { break }
                }
            }
        }
        return , @($out)
    }

    Start-Sleep -Milliseconds 1500
    $approve = PostJson $BaseA '/api/ai/confirm' @{ runId = $runId; toolCallId = $toolCallId; approved = $true; reason = 'b23l1 harness' }
    if (-not $approve.ok) { throw "A 侧批准确认失败：HTTP $($approve.status) $($approve.text)" }

    $woke = $null
    if (-not (Wait-Job $job -Timeout 90)) { Stop-Job $job }
    $woke = Receive-Job $job
    Remove-Job $job -Force

    $framesB = @($woke | Where-Object { $null -ne $_ })
    $seqsB = Seq-List $framesB
    Assert-StrictlyIncreasing $seqsB 'B 侧唤醒补帧'
    $done = $framesB | Where-Object { $_.type -eq 'done' } | Select-Object -First 1
    if ($null -eq $done) {
        throw "B 侧未收到终帧（唤醒未生效？已收 seq：$($seqsB -join ',')；快照帧数：$($snapshot.Count)）"
    }
    if ((Seq-Of $done) -le $lastSeq) { throw "终帧 seq 未推进：done.seq=$(Seq-Of $done) lastSeq=$lastSeq" }
    Ok $name
}

# ── 主流程 ──────────────────────────────────────────────────────────────────

$pendingCases = @{
    'suspend-decision' = '批②（fencing 租约）未落地：跨实例决策只能路由到持有运行线程的实例'
    'instance-kill'    = '批② 未落地：A 死后的接管续跑依赖续跑租约'
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
    Say ("摘要：PASS=" + $script:passed + " FAIL=" + $script:failed + " SKIP=" + $script:skipped)
    Say ("证据目录：" + $ArtifactDir)
    if (-not $KeepAlive) { Stop-Children }
}

if ($script:failed -gt 0) { exit 1 }
exit 0
