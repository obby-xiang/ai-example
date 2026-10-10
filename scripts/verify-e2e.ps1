# ============================================================
# E2E 验证脚本（main 版）：动态配置管理系统 全流程 29 用例
#
# 移植来源：<REPO_ROOT>/ai-example-code/ai-example-deepseek-v4-pro/scripts/verify-e2e.ps1
#           （685 行 / TC1–TC22 共 22 用例）。用例编号与源脚本一一对照，
#           但断言按 main 的 API 与业务语义逐条改写（映射表见
#           docs/evidence/S5b-deepseek-E2E移植验证.md）：
#             - 对象模型：任务（tasks）+ 作业（jobs，EXPORT/PRECHECK/IMPORT/PUBLISH）
#               取代源脚本的 /api/export/tasks 与 /api/import/batches 两套独立对象
#             - 发布语义：行级 upsert + 范围差集删除（REPLACE 模式），取代"范围内删表重建"
#             - 校验规则：必填 / 主键重复 / 引用存在性（无枚举合法性与数值范围校验）
#             - AI：会话 409 串行化、确认门（DANGER 工具）、前端工具挂起、
#               /api/ai/history/{sessionId}、/api/ai/events/{runId} 重挂与差量补发
#
# 用法：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 `
#       -Base http://127.0.0.1:18330 -BackendLog <后端 stdout 日志路径> `
#       [-ArtifactDir <GF 证据落盘目录>] [-EnableGf6]
#   -ArtifactDir 指定后，每个 GF 用例把实收 SSE 帧与回灌 POST 原文落 gfc-<case>.sse.txt /
#     gfc-<case>.http.txt；-EnableGf6 启用 GF6 入参闸门用例（**桩模式启用并计入主计数**；
#     真模型模式默认关——诱导非法 schema 的服从率不可控）。
#
# 桩模式（CI 路线，M2-T1.2）：先置 E2E_STUB_MODE=1 再用同一脚本调用——上游换成
#   scripts/ci/stub-upstream.mjs 确定性替身，此时 GFSKIP≥1 即整棒 FAIL（零容忍：桩下任何
#   SKIP 都属桩路由或产品链路异常，不可能由"模型未触发"引起）；真 key 本地手动跑法不设该
#   变量，SKIP 语义不变（模型未触发，不计退出码）。
#
# 后端启动约定（本脚本的断言依赖，脚本自身不启动后端）：
#   - AI key 经环境变量注入（AI_API_KEY，兼容 DEEPSEEK_API_KEY）；缺 key 时 AI 用例判 FAIL
#   - Redis 可用（127.0.0.1:6379）：AI 会话记忆 / 确认门 / 会话锁
#   - 建议启动参数：--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150
#     （TC11 断言 JOB_PROGRESS 事件流；batch-size 为默认 100 时小配置项不会跨分片边界）
#   - -BackendLog：后端 stdout 日志文件（com.example.configmgr 为 DEBUG 级）。
#     TC10 的日志断言（Q8①）与 Q8 两项回归需要它；TC15 的渐进披露断言已改用只读端点
#     GET /api/ai/tools（M1 收尾项⑤），日志仅作"日志行 vs 端点"的交叉校验（未提供则跳过）。
#     未提供 -BackendLog 时日志类断言判 FAIL（不静默跳过）
#
# 数据面安全：本脚本只创建/删除自己的 S5B-* 任务与 E2E_TEMP 定义；
#   CURRENCY 演示数据在 TC13 会被替换为 3 行，TC19 用全量导出件恢复为 5 行。
#
# 结果口径：29 个用例计入 PASS/FAIL 与退出码（有失败即 exit 1）——TC1–TC22 共 22 个
#   + TC23（T3b 收尾：CONFIRM 卡快照重建，T3-6b）
#   （由源脚本移植而来）+ GF1–GF5 共 5 个（生成式表单端到端，设计稿 docs/evidence/
#   GFc-E2E用例设计-GLM-5.3.md）；+ GF6（入参闸门，-EnableGf6，桩模式启用并计入主计数，
#   底座为桩的确定性违规模式 --violate-form-type）共 1 个；
#   移植期缺陷清单（Q8）的两项断言单列计数（Q8 项 PASS/FAIL），不计入 29 用例与退出码
#   —— Q8② 属日志质量问题，具体残留见 docs/evidence/S5b-deepseek-E2E移植验证.md
# 退出码：任一主用例 FAIL ⇒ exit 1；GF1–GF4 全部 SKIP（本棒零有效验证，硬底线）⇒ exit 1；
#   E2E_STUB_MODE=1 且 GFSKIP≥1 ⇒ exit 1（桩模式零容忍）
# ============================================================
param(
    [string]$Base = 'http://127.0.0.1:18330',
    [string]$BackendLog = '',
    # GF6 入参闸门用例：桩模式（CI）启用并计入主计数；真模型模式默认关（服从率不可控，见设计稿 §4 GF6）
    [switch]$EnableGf6,
    # GF 证据落盘目录（裁决 #4 / 设计稿 §6）：每个 GF 用例把 SSE 帧与回灌请求/响应原文写到
    # gfc-<case>.sse.txt / gfc-<case>.http.txt。默认在系统临时目录下新建 gfc-artifacts-<guid>（仓库外）
    [string]$ArtifactDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Base = $Base.TrimEnd('/')
$script:passed = 0
$script:failed = 0
$script:skippedNote = 0
$script:gfskip = 0     # GF 用例 SKIP 计数（模型未触发，不计退出码；摘要打印 GFSKIP=n）
$script:gfskip14 = 0   # GF1–GF4 中 SKIP 的个数；全部 SKIP（=4）触发硬底线整棒 FAIL（裁决 #1）
$script:results = New-Object System.Collections.ArrayList
$script:gfHttp = @()   # 当前 GF 用例的回灌 HTTP 请求/响应原文（每用例重置，Gf-Dump 落盘）

function Say($msg) { Write-Host $msg }
function Ok($name) {
    $script:passed++
    $script:results.Add(@{ name = $name; result = 'PASS' }) | Out-Null
    Write-Host ("  [PASS] " + $name) -ForegroundColor Green
}
function No($name, $why) {
    $script:failed++
    $script:results.Add(@{ name = $name; result = "FAIL: $why" }) | Out-Null
    Write-Host ("  [FAIL] " + $name + " => " + $why) -ForegroundColor Red
}

Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMinutes(5)

# ---------- HTTP 辅助（main 响应信封：{success, message?, code?, data?}） ----------

function Invoke-Api($method, $path, $body) {
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($Base + $path)
    $req.Method = New-Object System.Net.Http.HttpMethod($method)
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 20 -Compress }
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $resp = $http.SendAsync($req).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $obj = $null
    if ($text -and $text.TrimStart().StartsWith('{')) {
        try { $obj = $text | ConvertFrom-Json } catch { $obj = $null }
    }
    return @{ status = [int]$resp.StatusCode; ok = $resp.IsSuccessStatusCode; text = $text; json = $obj }
}

function GetJson($path) {
    $r = Invoke-Api 'GET' $path $null
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "GET $path => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}

function PostJson($path, $body) {
    $r = Invoke-Api 'POST' $path $body
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "POST $path => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}

function PutJson($path, $body) {
    $r = Invoke-Api 'PUT' $path $body
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "PUT $path => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}

function DeleteJson($path) {
    $r = Invoke-Api 'DELETE' $path $null
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "DELETE $path => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}

# AI 端点（/api/ai/confirm、/api/ai/frontend-tool-result、/api/ai/cancel/{runId}）返回裸 Map
# 而非 ApiResponse 信封，故单独一个不校验 success 的 POST 帮助函数
function PostJsonRaw($path, $body) {
    $r = Invoke-Api 'POST' $path $body
    if (-not $r.ok) { throw "POST $path => HTTP $($r.status) $($r.text)" }
    return $r.json
}

function GetBytes($path) {
    $resp = $http.GetAsync($Base + $path).Result
    if (-not $resp.IsSuccessStatusCode) { throw "GET $path => HTTP $($resp.StatusCode)" }
    return $resp.Content.ReadAsByteArrayAsync().Result
}

# 断言失败：预期被拒绝的请求（返回原始响应，不做 success 判定）
function Expect-Fail($method, $path, $body, $what) {
    $r = Invoke-Api $method $path $body
    if ($r.ok -and $r.json -and $r.json.success) {
        throw "$what —— 预期被拒绝但请求成功（HTTP $($r.status)）"
    }
    return $r
}

function Wait-Job($jobId, [string[]]$terminal = @('COMPLETED', 'FAILED', 'CANCELLED')) {
    for ($i = 0; $i -lt 240; $i++) {
        Start-Sleep -Milliseconds 400
        $j = GetJson "/api/jobs/$jobId"
        if ($terminal -contains $j.status) { return $j }
    }
    throw "作业超时未结束: /api/jobs/$jobId"
}

function Wait-Until($scriptBlock, $timeoutSec, $what) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $v = & $scriptBlock
        if ($v) { return $v }
        Start-Sleep -Milliseconds 500
    }
    throw "等待超时（${timeoutSec}s）: $what"
}

# ---------- 业务动作封装 ----------

function New-ExportTask($title, [string[]]$defCodes, $conditions = $null) {
    $t = PostJson '/api/tasks' @{ type = 'EXPORT'; title = $title }
    $tid = $t.id
    PostJson "/api/tasks/$tid/select-defs" @{ defCodes = $defCodes } | Out-Null
    if ($conditions) {
        foreach ($code in $conditions.Keys) {
            PutJson "/api/tasks/$tid/items/$code/condition" @{ condition = $conditions[$code] } | Out-Null
        }
    }
    return $tid
}

function New-ImportTask($title, [string[]]$defCodes, [string]$mode) {
    $t = PostJson '/api/tasks' @{ type = 'IMPORT'; title = $title }
    $tid = $t.id
    PostJson "/api/tasks/$tid/select-defs" @{ defCodes = $defCodes } | Out-Null
    if ($mode) { PutJson "/api/tasks/$tid/import-mode" @{ mode = $mode } | Out-Null }
    return $tid
}

function Start-JobOf($taskId, $jobType) {
    return PostJson "/api/tasks/$taskId/jobs" @{ jobType = $jobType }
}

function Skip-Upload($taskId, $filePath, $fileName) {
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $bytes = [System.IO.File]::ReadAllBytes($filePath)
    $bc = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$bytes)
    $bc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
    $content.Add($bc, 'file', $fileName)
    $resp = $http.PostAsync($Base + "/api/tasks/$taskId/files/upload", $content).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $obj = $null
    if ($text -and $text.TrimStart().StartsWith('{')) { try { $obj = $text | ConvertFrom-Json } catch { } }
    return @{ status = [int]$resp.StatusCode; ok = $resp.IsSuccessStatusCode; text = $text; json = $obj }
}

function Upload-File($taskId, $filePath, $fileName) {
    $r = Skip-Upload $taskId $filePath $fileName
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "UPLOAD => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}

# ---------- SSE ----------

# 读取 SSE 流；$stopOn 命中即断开；$sink 收集帧；$onOpen 在收到响应头之后、开始读流之前执行
# （TC11 需要"先订阅、后启动作业"，故必须有这个时点）
function Read-Sse($path, $bodyObj, [string[]]$stopOn, $sink, $onOpen) {
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($Base + $path)
    if ($null -ne $bodyObj) {
        $req.Method = [System.Net.Http.HttpMethod]::Post
        $json = $bodyObj | ConvertTo-Json -Depth 20 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    } else {
        $req.Method = [System.Net.Http.HttpMethod]::Get
    }
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter(180000)
    $resp = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
    if (-not $resp.IsSuccessStatusCode) {
        $t = $resp.Content.ReadAsStringAsync().Result
        throw "SSE $path => HTTP $($resp.StatusCode) $t"
    }
    if ($onOpen) { & $onOpen }
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    try {
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($data -and $data -ne '[DONE]') {
                    $frame = $null
                    try { $frame = $data | ConvertFrom-Json } catch { $frame = $null }
                    if ($frame) {
                        # 注意：ArrayList 为空时 `if ($sink)` 为 $false（PowerShell 集合真值转换），
                        # 必须用 $null 判定，否则第一帧就丢
                        if ($null -ne $sink) { $sink.Add($frame) | Out-Null }
                        if ($stopOn -contains $frame.type) { break }
                    }
                }
            }
        }
    } catch {
        # 断开/超时（含预期内的 StopOn 早退）不视为用例失败，由断言判定实际收到的帧
    } finally {
        $reader.Dispose()
        $resp.Dispose()
        $cts.Dispose()
    }
}

# 发起一轮 AI 对话并处理挂起（确认门 / 前端工具）：
#   - 首次 POST /api/ai/chat，之后用 GET /api/ai/events/{runId}?lastSeq=N 差量重挂续读
#   - $onSuspend 脚本块收到挂起帧，返回 @{ approved=$true|$false; result='...' } 决定如何回灌；
#     返回 $null 表示不再处理（提前结束本轮的读取）
function Invoke-AiTurn($sessionId, $message, $context, [scriptblock]$onSuspend, [int]$maxSuspend = 6) {
    $frames = New-Object System.Collections.ArrayList
    $runId = $null
    $lastSeq = 0
    for ($i = 0; $i -le $maxSuspend; $i++) {
        $batch = New-Object System.Collections.ArrayList
        if ($i -eq 0) {
            Read-Sse '/api/ai/chat' @{ sessionId = $sessionId; message = $message; context = $context } `
                @('confirm_request', 'frontend_tool_request', 'done', 'error') $batch $null
        } else {
            if (-not $runId) { break }
            Read-Sse "/api/ai/events/$runId`?lastSeq=$lastSeq" $null `
                @('confirm_request', 'frontend_tool_request', 'done', 'error') $batch $null
        }
        foreach ($f in $batch) {
            $frames.Add($f) | Out-Null
            if ($f.type -eq 'start' -and $f.runId) { $runId = $f.runId }
            if ($f.seq -and [int]$f.seq -gt $lastSeq) { $lastSeq = [int]$f.seq }
        }
        $term = @($batch | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
        if ($term.Count -gt 0) { break }
        $suspend = @($batch | Where-Object { $_.type -eq 'confirm_request' -or $_.type -eq 'frontend_tool_request' })
        if ($suspend.Count -eq 0) { break }
        $s = $suspend[$suspend.Count - 1]
        if (-not $onSuspend) { break }
        $act = & $onSuspend $s
        if ($null -eq $act) { break }
        if ($s.type -eq 'confirm_request') {
            PostJsonRaw '/api/ai/confirm' @{ runId = $runId; toolCallId = $s.toolCallId; approved = [bool]$act.approved; reason = 'e2e' } | Out-Null
        } else {
            # 加法式兜底（设计稿 §5.3，裁决 #2）：模型自发调用 generative_form 时，$onSuspend 返回的
            # 通用回灌值 '{"ok":true,"source":"e2e"}' 必被闸门 400 拒。故挂起帧 name=generative_form 时
            # 优先直接 POST cancelled:true 收敛（对齐 GFa §8.3 契约），仅当处理器显式返回表单专用载荷
            # （显式 cancelled，或非通用 result）时才改用处理器载荷。收敛 POST 走 Invoke-Api 并容忍
            # 409（条目已超时/已决视为已收敛，不 throw）。$onSuspend 为 $null 时的 break 路径（上文）不受影响。
            $formSpecific = $false
            if ($s.name -eq 'generative_form' -and $act -is [System.Collections.IDictionary]) {
                $hasCancelled = $act.Keys -contains 'cancelled'
                $hasCustomResult = ($act.Keys -contains 'result') -and $act.result -and ($act.result -ne '{"ok":true,"source":"e2e"}')
                if ($hasCancelled -or $hasCustomResult) { $formSpecific = $true }
            }
            if ($s.name -eq 'generative_form' -and -not $formSpecific) {
                $converged = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $runId; toolCallId = $s.toolCallId; cancelled = $true; source = 'e2e' }
                if (-not $converged.ok -and $converged.status -ne 409) { throw "POST /api/ai/frontend-tool-result => HTTP $($converged.status) $($converged.text)" }
            } else {
                $res = if ($act.result) { $act.result } else { '{"ok":true,"source":"e2e"}' }
                PostJsonRaw '/api/ai/frontend-tool-result' @{ runId = $runId; toolCallId = $s.toolCallId; result = $res; source = 'e2e' } | Out-Null
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return @{ frames = $frames; runId = $runId }
}

# 通用"继续型"挂起处理器：对确认门一律拒绝、对前端工具回灌成功结果，
# 使读流走到 done（避免在无关挂起点提前收摊——真实模型可能先挂起在别的工具上）
$script:continueHandler = {
    param($f)
    if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
    return @{ result = '{"ok":true,"source":"e2e"}' }
}

# ---------- xlsx 工具 ----------
# 上传件的构造走"导出产物 + 单元格手术"闭环：导出件的数据表用 inline string 写值，
# 单元格带 r 引用（如 A1/B2），故可按引用精确改写（新增/清空值）而不动包结构。

function ConvertTo-XmlText($s) {
    return ([string]$s).Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
}

function Edit-XlsxCells($srcPath, $outPath, $edits) {
    if (Test-Path $outPath) { Remove-Item $outPath -Force }
    $src = [System.IO.Compression.ZipFile]::OpenRead($srcPath)
    $dst = [System.IO.Compression.ZipFile]::Open($outPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $src.Entries) {
            $s = $entry.Open()
            $ms = New-Object System.IO.MemoryStream
            $s.CopyTo($ms)
            $s.Close()
            $bytes = $ms.ToArray()
            $ms.Dispose()
            if ($entry.FullName -eq 'xl/worksheets/sheet1.xml') {
                $text = [System.Text.Encoding]::UTF8.GetString($bytes)
                foreach ($ref in $edits.Keys) {
                    $pattern = '<c r="' + [regex]::Escape($ref) + '"[^>]*/>|<c r="' + [regex]::Escape($ref) + '"[^>]*>.*?</c>'
                    $m = [regex]::Match($text, $pattern, [System.Text.RegularExpressions.RegexOptions]::Singleline)
                    if (-not $m.Success) { throw "xlsx 单元格未找到: $ref（$($entry.FullName)）" }
                    $val = $edits[$ref]
                    $new = if ($null -eq $val) {
                        '<c r="' + $ref + '"/>'
                    } else {
                        '<c r="' + $ref + '" t="inlineStr"><is><t>' + (ConvertTo-XmlText $val) + '</t></is></c>'
                    }
                    $text = $text.Remove($m.Index, $m.Length).Insert($m.Index, $new)
                }
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
            }
            $ne = $dst.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $d = $ne.Open()
            $d.Write($bytes, 0, $bytes.Length)
            $d.Close()
        }
    } finally {
        $src.Dispose()
        $dst.Dispose()
    }
}

# $entries：有序字典 entryName -> byte[]（条目名显式用正斜杠，保证 OOXML 包合规）
function New-ZipFile($zipPath, $entries) {
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $entries.Keys) {
            $e = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
            $s = $e.Open()
            $b = $entries[$name]
            $s.Write($b, 0, $b.Length)
            $s.Close()
        }
    } finally {
        $zip.Dispose()
    }
}

function Zip-Entries($zipPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    $zip.Dispose()
    return $names
}

# ---------- 后端日志断言（TC15 渐进披露 / Q8 日志项） ----------

function Get-LogText {
    if (-not $BackendLog) { throw '未提供 -BackendLog，无法执行日志类断言' }
    if (-not (Test-Path $BackendLog)) { throw "后端日志不存在: $BackendLog" }
    # 后端进程持有该文件的写句柄（stdout 重定向），故以 FileShare.ReadWrite 打开
    $fs = New-Object System.IO.FileStream($BackendLog, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    try { return $sr.ReadToEnd() } finally { $sr.Close(); $fs.Close() }
}

# 上下文 -> 最近一次披露的工具名数组（DEBUG 行：上下文=<ctx> 披露工具=[...]）
function Get-DisclosureMap {
    $text = Get-LogText
    $map = @{}
    foreach ($m in [regex]::Matches($text, '上下文=(\S+)\s+披露工具=\[([^\]]*)\]')) {
        $ctx = $m.Groups[1].Value
        $tools = @($m.Groups[2].Value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $map[$ctx] = $tools
    }
    return $map
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) ('s5b-e2e-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# GF 证据落盘目录（裁决 #4 / 设计稿 §6）：优先用 -ArtifactDir（执行棒指定仓库外目录），
# 否则在系统临时目录下新建 gfc-artifacts-<guid>（同样是仓库外）。
if (-not $ArtifactDir) { $ArtifactDir = Join-Path ([IO.Path]::GetTempPath()) ('gfc-artifacts-' + [Guid]::NewGuid().ToString('N')) }
New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null
$script:gfArtifactDir = (Resolve-Path -LiteralPath $ArtifactDir).Path

Say '============================================================'
Say (" E2E 验证开始  base={0}  tmp={1}" -f $Base, $tmp)
Say (" 后端日志：{0}" -f ($(if ($BackendLog) { $BackendLog } else { '（未提供，日志类断言将判 FAIL）' })))
Say (" GF 证据落盘目录：{0}" -f $script:gfArtifactDir)
Say '============================================================'

# ---- 预清理：删除上一次运行残留的 S5B-* 任务与 E2E_TEMP 定义（幂等重跑） ----
try {
    $page = GetJson '/api/tasks?page=0&size=500'
    $removed = 0
    foreach ($row in $page.content) {
        if ($row.task.title -like 'S5B-*') {
            try { DeleteJson ("/api/tasks/" + $row.task.id); $removed++ } catch { }
        }
    }
    $delDef = 0
    try { GetJson '/api/definitions/E2E_TEMP' | Out-Null; DeleteJson '/api/definitions/E2E_TEMP'; $delDef = 1 } catch { }
    Say "--- 预清理：删除残留任务 $removed 个，E2E_TEMP 定义 $delDef 个 ---"
} catch { Say ("--- 预清理：跳过（" + $_.Exception.Message + "） ---") }

# ---- 预清理（GF 追加段，设计稿 §5.4）：删除上一次运行残留的 GFC-* 任务（不改既有行，幂等重跑） ----
try {
    $pageGfc = GetJson '/api/tasks?page=0&size=500'
    $removedGfc = 0
    foreach ($rowGfc in $pageGfc.content) {
        if ($rowGfc.task.title -like 'GFC-*') {
            try { DeleteJson ("/api/tasks/" + $rowGfc.task.id); $removedGfc++ } catch { }
        }
    }
    Say "--- 预清理（GF）：删除残留 GFC-* 任务 $removedGfc 个 ---"
} catch { Say ("--- 预清理（GF）：跳过（" + $_.Exception.Message + "） ---") }

# ============================================================
# TC1 配置定义列表与种子数据
#   源断言：定义数 ≥4、层级正确、publishedRowCount、dependsOn、REFERENCE 字段
#   main 适配：种子 = 基座 7 + glm 并集 8 = 15 个定义；发布行数以 /api/data/{code}/count 表达；
#   依赖以 REFERENCE 字段表达（无独立的 dependsOn 数组）
# ============================================================
Say '--- TC1 配置定义列表与种子数据 ---'
try {
    $defs = GetJson '/api/definitions'
    $map = @{}
    foreach ($d in $defs) { $map[$d.code] = $d }
    if ($map.Count -lt 15) { throw "定义数量不足: $($map.Count)（期望基座 7 + glm 8 = 15）" }
    foreach ($code in @('CURRENCY', 'DOC_TYPE', 'APPROVE_ROLE', 'TAX_RATE', 'PROJ_PARAM', 'PROJ_APPROVE', 'PROJ_PRICE',
            'SYS_PARAM', 'METRIC_DICT', 'ALARM_THRESHOLD', 'ROLE_DICT', 'REGION_NETWORK', 'REGION_TARIFF',
            'PROJECT_MEMBER', 'PROJECT_ENV')) {
        if (-not $map.ContainsKey($code)) { throw "缺少种子定义 $code" }
    }
    if ($map['CURRENCY'].level -ne 'GLOBAL') { throw 'CURRENCY 层级错误' }
    if ($map['TAX_RATE'].level -ne 'REGION') { throw 'TAX_RATE 层级错误' }
    if ($map['PROJECT_MEMBER'].level -ne 'PROJECT') { throw 'PROJECT_MEMBER 层级错误' }

    $ref = @($map['ALARM_THRESHOLD'].fields | Where-Object { $_.code -eq 'metricCode' })
    if ($ref.Count -ne 1) { throw 'ALARM_THRESHOLD 缺少 metricCode 字段' }
    if ($ref[0].fieldType -ne 'REFERENCE' -or $ref[0].refDefCode -ne 'METRIC_DICT' -or $ref[0].refFieldCode -ne 'metricCode') {
        throw "引用字段定义错误: $($ref[0].fieldType)/$($ref[0].refDefCode)/$($ref[0].refFieldCode)"
    }

    $counts = @{ CURRENCY = 5; DOC_TYPE = 5; SYS_PARAM = 40; REGION_NETWORK = 12; REGION_TARIFF = 20; METRIC_DICT = 30 }
    foreach ($code in $counts.Keys) {
        $c = (GetJson "/api/data/$code/count").count
        if ($c -ne $counts[$code]) { throw "$code 已发布行数 $c，期望 $($counts[$code])" }
    }

    $regions = @(GetJson '/api/definitions?level=REGION')
    if (@($regions | Where-Object { $_.level -ne 'REGION' }).Count -gt 0) { throw 'level=REGION 过滤混入非地区级定义' }
    if ($regions.Count -ne 3) { throw "地区级定义数 $($regions.Count)，期望 3" }
    $kw = @(GetJson '/api/definitions?keyword=CURRENCY')
    if ($kw.Count -ne 1 -or $kw[0].code -ne 'CURRENCY') { throw "keyword 过滤结果异常: $($kw.Count) 条" }

    Ok 'TC1 15 个定义（基座 7 + glm 8）/层级/引用字段/发布行数/过滤正确'
} catch { No 'TC1' $_.Exception.Message }

# ============================================================
# TC2 配置定义动态 CRUD
#   源断言：创建 → 重复编码拦截 → 追加字段
#   main 适配：重复编码走 M1 收尾守卫④（DefinitionService#save 前置校验）→
#   409 + 机器可读码 DEFINITION_CODE_DUPLICATE，且不回显 SQL 报文（原为 500 + 原始 JDBC 报文）；
#   追加字段走整份 fields 替换语义（PUT）
# ============================================================
Say '--- TC2 动态配置定义 CRUD ---'
try {
    $newDef = @{
        code        = 'E2E_TEMP'
        name        = 'E2E临时配置'
        level       = 'GLOBAL'
        description = '自动化测试用'
        sortOrder   = 99
        fields      = @(
            @{ code = 'f_text'; label = '文本字段'; fieldType = 'STRING'; required = $true; key = $true },
            @{ code = 'f_num'; label = '数字字段'; fieldType = 'NUMBER'; required = $false; key = $false },
            @{ code = 'f_sel'; label = '下拉字段'; fieldType = 'ENUM'; required = $false; key = $false;
                optionsJson = '[{"value":"甲","label":"甲"},{"value":"乙","label":"乙"},{"value":"丙","label":"丙"}]' }
        )
    }
    $created = PostJson '/api/definitions' $newDef
    if ($created.code -ne 'E2E_TEMP' -or @($created.fields).Count -ne 3) { throw '创建失败' }

    $dup = Expect-Fail 'POST' '/api/definitions' $newDef '重复编码未被拦截'
    $dupStatus = $dup.status
    # M1 收尾守卫④：必须是 409 + 机器可读码，且报文里不得出现 SQL/JDBC 片段
    if ($dupStatus -ne 409 -or $dup.json.code -ne 'DEFINITION_CODE_DUPLICATE') {
        throw "重复编码响应 HTTP $dupStatus/code=$($dup.json.code)，期望 409/DEFINITION_CODE_DUPLICATE"
    }
    if ($dup.text -match 'insert into|Unique index|23505|could not execute statement') {
        throw "重复编码响应泄漏 SQL 报文: $($dup.text)"
    }
    $dupDef = GetJson '/api/definitions/E2E_TEMP'
    if (@($dupDef.fields).Count -ne 3) { throw '重复创建篡改了已存在定义（字段数变化）' }

    $newDef.fields = @($newDef.fields) + @(
        @{ code = 'f_date'; label = '日期字段'; fieldType = 'DATE'; required = $false; key = $false })
    $updated = PutJson '/api/definitions/E2E_TEMP' $newDef
    if (@($updated.fields).Count -ne 4) { throw "更新后字段数 $(@($updated.fields).Count)，期望 4" }
    $after = GetJson '/api/definitions/E2E_TEMP'
    if (@($after.fields).Count -ne 4) { throw "回读字段数 $(@($after.fields).Count)" }
    if (@($after.fields | Where-Object { $_.code -eq 'f_date' }).Count -ne 1) { throw '新增字段 f_date 未落库' }

    # 结构校验：无主键字段的定义必须被拒（DefinitionService.validateFields）
    $noKey = @{ code = 'E2E_NOKEY'; name = '无主键'; level = 'GLOBAL'; fields = @(
            @{ code = 'a'; label = 'A'; fieldType = 'STRING'; required = $true; key = $false }) }
    Expect-Fail 'POST' '/api/definitions' $noKey '无主键定义未被拒绝' | Out-Null

    Ok "TC2 定义创建/重复编码拦截(HTTP $dupStatus + DEFINITION_CODE_DUPLICATE，无 SQL 泄漏)/字段动态追加/无主键拒绝"
} catch { No 'TC2' $_.Exception.Message }

# ============================================================
# TC3 校验引擎（预检查：必填 / 主键重复 / 引用存在性 / 范围必填）
#   源断言：类型/必填/选项/范围/数值五类校验
#   main 适配：PrecheckJobRunner 实际覆盖"必填 / 主键重复 / 引用存在性"三类
#   （无枚举合法性与数值范围校验，见 ImportFlowJobTest 类注释），
#   范围必填以"REGION 级的范围字段缺失"表达（regionCode 为 key+required）
# ============================================================
Say '--- TC3 数据校验引擎（预检查四类规则） ---'
try {
    # 先取合法导出件作为基件
    $t3x = New-ExportTask 'S5B-TC3-基件' @('SYS_PARAM', 'ALARM_THRESHOLD', 'REGION_NETWORK')
    $j3x = Start-JobOf $t3x 'EXPORT'
    $s3x = Wait-Job $j3x.id
    if ($s3x.status -ne 'COMPLETED') { throw "基件导出失败: $($s3x.status)" }
    $baseSys = (Join-Path $tmp 'sysparam_base.xlsx'); [IO.File]::WriteAllBytes($baseSys, (GetBytes "/api/tasks/$t3x/files/SYS_PARAM"))
    $baseAlarm = (Join-Path $tmp 'alarm_base.xlsx'); [IO.File]::WriteAllBytes($baseAlarm, (GetBytes "/api/tasks/$t3x/files/ALARM_THRESHOLD"))
    $baseNet = (Join-Path $tmp 'regionnet_base.xlsx'); [IO.File]::WriteAllBytes($baseNet, (GetBytes "/api/tasks/$t3x/files/REGION_NETWORK"))

    # 缺陷 1+2：SYS_PARAM 第 2 行 paramValue（B2）清空 = 必填缺失；第 3 行 paramKey（A3）改成 A2 的值 = 主键重复
    $badSys = (Join-Path $tmp 'sysparam_bad.xlsx')
    Edit-XlsxCells $baseSys $badSys ([ordered]@{ 'B2' = $null; 'A3' = 'param.1' })
    # 缺陷 3：ALARM_THRESHOLD 第 2 行 metricCode（B2）改为不存在的指标 = 引用不存在
    $badAlarm = (Join-Path $tmp 'alarm_bad.xlsx')
    Edit-XlsxCells $baseAlarm $badAlarm ([ordered]@{ 'B2' = 'metric.999' })
    # 缺陷 4：REGION_NETWORK 第 2 行 regionCode（A2）清空 = 范围（key+必填）缺失
    $badNet = (Join-Path $tmp 'regionnet_bad.xlsx')
    Edit-XlsxCells $baseNet $badNet ([ordered]@{ 'A2' = $null })

    $t3 = New-ImportTask 'S5B-TC3-缺陷数据' @('SYS_PARAM', 'ALARM_THRESHOLD', 'REGION_NETWORK')
    Upload-File $t3 $badSys 'SYS_PARAM_e2e.xlsx' | Out-Null
    Upload-File $t3 $badAlarm 'ALARM_THRESHOLD_e2e.xlsx' | Out-Null
    Upload-File $t3 $badNet 'REGION_NETWORK_e2e.xlsx' | Out-Null
    $j3 = Start-JobOf $t3 'PRECHECK'
    $s3 = Wait-Job $j3.id
    if ($s3.status -ne 'FAILED') { throw "预检查应 FAILED，实际 $($s3.status)（errorCount=$($s3.errorCount)）" }

    $iss = GetJson "/api/jobs/$($j3.id)/issues?page=0&size=200"
    $rows = @($iss.content)
    $msgSys = @($rows | Where-Object { $_.defCode -eq 'SYS_PARAM' } | ForEach-Object { $_.message })
    $msgAlarm = @($rows | Where-Object { $_.defCode -eq 'ALARM_THRESHOLD' } | ForEach-Object { $_.message })
    $msgNet = @($rows | Where-Object { $_.defCode -eq 'REGION_NETWORK' } | ForEach-Object { $_.message })
    if (-not ($msgSys | Where-Object { $_ -like '*必填字段*' })) { throw '未检出必填缺失' }
    if (-not ($msgSys | Where-Object { $_ -like '*主键重复*' })) { throw '未检出主键重复' }
    if (-not ($msgAlarm | Where-Object { $_ -like '*不存在*' })) { throw '未检出引用不存在' }
    if (-not ($msgNet | Where-Object { $_ -like '*必填字段*' })) { throw '未检出范围字段缺失' }
    if ($s3.errorCount -ne 4) { throw "errorCount=$($s3.errorCount)，期望 4（必填1+主键1+引用1+范围1）" }

    # 任务条目状态联动
    $task3 = GetJson "/api/tasks/$t3"
    if (@($task3.items | Where-Object { $_.status -ne 'FAILED' }).Count -gt 0) { throw '存在未标记 FAILED 的任务条目' }
    $script:tc3JobId = $j3.id
    Ok 'TC3 四类规则（必填/主键重复/引用不存在/范围必填）全部命中且给出明细'
} catch { No 'TC3' $_.Exception.Message }

# ============================================================
# TC4 导出任务（全量 + 进度 + 文件可下载）
#   源断言：4 文件、行数、xlsx 魔数
#   main 适配：/api/tasks/{id}/files 给出文件清单（含 rowCount），
#   下载入口为 /api/tasks/{id}/files/{defCode}
# ============================================================
Say '--- TC4 导出任务（全量 + 进度 + 文件） ---'
try {
    $script:tExp4 = New-ExportTask 'S5B-TC4-全量导出' @('CURRENCY', 'DOC_TYPE', 'REGION_NETWORK')
    $j4 = Start-JobOf $script:tExp4 'EXPORT'
    $s4 = Wait-Job $j4.id
    if ($s4.status -ne 'COMPLETED') { throw "导出失败: $($s4.status)" }
    if ($s4.errorCount -ne 0) { throw "errorCount=$($s4.errorCount)" }
    if (@($s4.items).Count -ne 3) { throw "作业条目数 $(@($s4.items).Count)" }
    if ($s4.progress -ne $s4.total -or $s4.total -le 0) { throw "进度口径异常 $($s4.progress)/$($s4.total)" }

    $files = @(GetJson "/api/tasks/$($script:tExp4)/files" | Where-Object { $_.fileType -eq 'EXPORT' })
    if ($files.Count -ne 3) { throw "导出文件数 $($files.Count)" }
    $expect = @{ CURRENCY = 5; DOC_TYPE = 5; REGION_NETWORK = 12 }
    foreach ($code in $expect.Keys) {
        $f = @($files | Where-Object { $_.defCode -eq $code })
        if ($f.Count -ne 1) { throw "$code 导出文件缺失" }
        if ($f[0].rowCount -ne $expect[$code]) { throw "$code 导出行数 $($f[0].rowCount)，期望 $($expect[$code])" }
    }
    $bytes = GetBytes "/api/tasks/$($script:tExp4)/files/CURRENCY"
    if ($bytes.Length -lt 1000 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) { throw 'xlsx 魔数错误' }
    [IO.File]::WriteAllBytes((Join-Path $tmp 'CURRENCY_full.xlsx'), $bytes)

    $task4 = GetJson "/api/tasks/$($script:tExp4)"
    if ($task4.status -ne 'COMPLETED') { throw "任务态 $($task4.status)" }
    if ($task4.currentStep -ne 'EXPORT') { throw "任务步骤 $($task4.currentStep)" }
    Ok 'TC4 全量导出 3 文件 + 行数/进度正确 + 文件可下载（PK 魔数）'
} catch { No 'TC4' $_.Exception.Message }

# ============================================================
# TC5 导出查询条件（字段条件 + 范围条件）
#   源断言：字段条件 3 行、范围条件 1 行
#   main 适配：条件经 PUT /api/tasks/{id}/items/{defCode}/condition 落库
#   （对象形态 {scopeKeys,fields}，与前端契约同形），行数按数据集实况断言
# ============================================================
Say '--- TC5 导出查询条件（字段条件 + 范围条件） ---'
try {
    $conds = @{
        CURRENCY       = @{ fields = @(@{ fieldCode = 'code'; operator = 'EQ'; value = 'CNY' }) }
        REGION_NETWORK = @{ scopeKeys = @('HE') }
        REGION_TARIFF  = @{ scopeKeys = @('HE', 'XN') }
    }
    $t5 = New-ExportTask 'S5B-TC5-条件导出' @('CURRENCY', 'REGION_NETWORK', 'REGION_TARIFF') $conds
    $items5 = @(GetJson "/api/tasks/$t5").items
    if (@($items5 | Where-Object { $_.status -ne 'READY' }).Count -gt 0) {
        throw '存在未落到 READY 的条件条目'
    }
    $j5 = Start-JobOf $t5 'EXPORT'
    $s5 = Wait-Job $j5.id
    if ($s5.status -ne 'COMPLETED') { throw "条件导出失败: $($s5.status)" }
    $files5 = @(GetJson "/api/tasks/$t5/files" | Where-Object { $_.fileType -eq 'EXPORT' })
    $exp5 = @{ CURRENCY = 1; REGION_NETWORK = 3; REGION_TARIFF = 10 }
    foreach ($code in $exp5.Keys) {
        $f = @($files5 | Where-Object { $_.defCode -eq $code })
        if ($f.Count -ne 1) { throw "$code 条件导出文件缺失" }
        if ($f[0].rowCount -ne $exp5[$code]) { throw "$code 条件导出行数 $($f[0].rowCount)，期望 $($exp5[$code])" }
    }

    # 再导一份 CURRENCY 的 3 行子集，作为 TC13 的"范围内替换"输入件
    $conds3 = @{ CURRENCY = @{ fields = @(@{ fieldCode = 'code'; operator = 'IN'; value = @('CNY', 'USD', 'EUR') }) } }
    $t5b = New-ExportTask 'S5B-TC5-条件导出3行' @('CURRENCY') $conds3
    $j5b = Start-JobOf $t5b 'EXPORT'
    $s5b = Wait-Job $j5b.id
    if ($s5b.status -ne 'COMPLETED') { throw "3 行子集导出失败: $($s5b.status)" }
    $bytes5b = GetBytes "/api/tasks/$t5b/files/CURRENCY"
    [IO.File]::WriteAllBytes((Join-Path $tmp 'CURRENCY_3rows.xlsx'), $bytes5b)
    $f5b = @((GetJson "/api/tasks/$t5b/files" | Where-Object { $_.defCode -eq 'CURRENCY' }))
    if ($f5b[0].rowCount -ne 3) { throw "3 行子集 rowCount=$($f5b[0].rowCount)" }

    $script:tExp5 = $t5
    Ok 'TC5 字段条件(code=CNY→1行)与范围条件(HE→3行、HE+XN→10行)正确，3 行子集件已保存'
} catch { No 'TC5' $_.Exception.Message }

# ============================================================
# TC6 打包下载（勾选文件 zip）
#   源断言：zip 含 SERVER_PARAM.xlsx/REGION_BILLING.xlsx 两个条目
#   main 适配：/api/tasks/{id}/files/download?codes=A,B（条目名为"编码_名称.xlsx"）
# ============================================================
Say '--- TC6 打包下载（勾选 + 全量） ---'
try {
    $bytes = GetBytes "/api/tasks/$($script:tExp4)/files/download?codes=CURRENCY,REGION_NETWORK"
    [IO.File]::WriteAllBytes((Join-Path $tmp 'pkg.zip'), $bytes)
    $names = Zip-Entries (Join-Path $tmp 'pkg.zip')
    if ($names.Count -ne 2) { throw "条目数 $($names.Count)，期望 2" }
    foreach ($code in @('CURRENCY', 'REGION_NETWORK')) {
        if (@($names | Where-Object { $_ -like "$code*" }).Count -ne 1) { throw "缺少 $code 的条目（$($names -join '|')）" }
    }
    $all = GetBytes "/api/tasks/$($script:tExp4)/files/download-all"
    [IO.File]::WriteAllBytes((Join-Path $tmp 'pkg-all.zip'), $all)
    $namesAll = Zip-Entries (Join-Path $tmp 'pkg-all.zip')
    if ($namesAll.Count -ne 3) { throw "全量打包条目数 $($namesAll.Count)，期望 3" }
    Ok 'TC6 勾选打包（2 条目）与全量打包（3 条目）均正确'
} catch { No 'TC6' $_.Exception.Message }

# ============================================================
# TC7 导入模板下载
#   源断言：单个 xlsx + 多个 zip
#   main 适配：任务级 /api/tasks/{id}/files/templates（单个 xlsx / 多个 zip），
#   定义级 /api/definitions/{code}/template
# ============================================================
Say '--- TC7 导入模板下载 ---'
try {
    $single = GetBytes "/api/tasks/$($script:tExp4)/files/templates?codes=CURRENCY"
    if ($single.Length -lt 500 -or $single[0] -ne 0x50 -or $single[1] -ne 0x4B) { throw '单个模板非 xlsx' }
    [IO.File]::WriteAllBytes((Join-Path $tmp 'tpl_single.xlsx'), $single)
    $zipped = GetBytes "/api/tasks/$($script:tExp4)/files/templates?codes=CURRENCY,DOC_TYPE"
    [IO.File]::WriteAllBytes((Join-Path $tmp 'tpl.zip'), $zipped)
    $names = Zip-Entries (Join-Path $tmp 'tpl.zip')
    if ($names.Count -ne 2) { throw "模板 zip 条目数 $($names.Count)" }
    $defTpl = GetBytes '/api/definitions/CURRENCY/template'
    if ($defTpl.Length -lt 500 -or $defTpl[0] -ne 0x50 -or $defTpl[1] -ne 0x4B) { throw '定义级模板非 xlsx' }
    Ok 'TC7 模板：单个 xlsx + 多配置 zip + 定义级模板下载正常'
} catch { No 'TC7' $_.Exception.Message }

# ============================================================
# TC8 上传文件名匹配（zip / 单文件 / 未匹配 / 非法命名）
#   源断言：含"序号后缀 (1)"匹配、未匹配清单
#   main 适配：匹配规则 = 文件名去扩展名后等于编码，或以"编码_"、"编码-"开头
#   （matchDefCode）；"(1) 后缀"不支持 → 断言为被拒绝并给出可读提示（语义变化见证据文档）
# ============================================================
Say '--- TC8 上传文件名匹配 ---'
try {
    $t8 = New-ImportTask 'S5B-TC8-文件名匹配' @('CURRENCY', 'DOC_TYPE')
    # (a) zip：一个可匹配 + 一个不可匹配
    $entries = [ordered]@{}
    $entries['CURRENCY_e2e.xlsx'] = [IO.File]::ReadAllBytes((Join-Path $tmp 'CURRENCY_full.xlsx'))
    $entries['UNKNOWN_CFG.xlsx'] = ([IO.File]::ReadAllBytes((Join-Path $tmp 'CURRENCY_full.xlsx')))[0..63]
    $zipPath = (Join-Path $tmp 'up1.zip')
    New-ZipFile $zipPath $entries
    $rep = Upload-File $t8 $zipPath 'up1.zip'
    if (@($rep.matchedFiles).Count -ne 1) { throw "zip 匹配数 $(@($rep.matchedFiles).Count)" }
    if (@($rep.unmatchedFiles | Where-Object { $_ -like 'UNKNOWN_CFG*' }).Count -ne 1) { throw '未匹配文件未报告' }
    # (b) 单文件前缀命名（编码_名称.xlsx）
    [IO.File]::WriteAllBytes((Join-Path $tmp 'DOC_TYPE_e2e.xlsx'), (GetBytes "/api/tasks/$($script:tExp4)/files/DOC_TYPE"))
    $rep2 = Upload-File $t8 (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_名称.xlsx'
    if ($rep2.count -ne 1 -or $rep2.defCode -ne 'DOC_TYPE') { throw "单文件前缀匹配失败: $($rep2.defCode)" }
    # (c) 无法匹配的单文件 → 400
    Copy-Item (Join-Path $tmp 'CURRENCY_full.xlsx') (Join-Path $tmp 'SOMETHING.xlsx') -Force
    $bad = Skip-Upload $t8 (Join-Path $tmp 'SOMETHING.xlsx') 'SOMETHING.xlsx'
    if ($bad.ok -or -not ($bad.text -like '*无法从文件名匹配配置编码*')) { throw "无法匹配的单文件未被拒（HTTP $($bad.status)）" }
    # (d) zip 全部不可匹配 → 400
    $entries2 = [ordered]@{}
    $entries2['ZZZ.xlsx'] = [IO.File]::ReadAllBytes((Join-Path $tmp 'CURRENCY_full.xlsx'))
    New-ZipFile (Join-Path $tmp 'up2.zip') $entries2
    $bad2 = Skip-Upload $t8 (Join-Path $tmp 'up2.zip') 'up2.zip'
    if ($bad2.ok -or -not ($bad2.text -like '*没有可匹配的文件*')) { throw "全不匹配 zip 未被拒（HTTP $($bad2.status)）" }
    # (e) 源脚本支持的"(1) 序号后缀"在 main 不被识别 → 断言被拒绝
    $bad3 = Skip-Upload $t8 (Join-Path $tmp 'SOMETHING.xlsx') 'CURRENCY(1).xlsx'
    if ($bad3.ok) { throw '"(1) 后缀"命名被接受（main 语义为不支持）' }
    Ok 'TC8 zip 匹配/未匹配报告/前缀命名/非法命名拒绝均正确（序号后缀语义见映射说明）'
} catch { No 'TC8' $_.Exception.Message }

# ============================================================
# TC9 检查通过 + 依赖拓扑
#   源断言：合法数据全部通过 + SERVER_PARAM 先于 SERVER_EXTEND
#   main 适配：依赖先序由 REFERENCE 字段推出（METRIC_DICT 先于 ALARM_THRESHOLD）。
#   job_items 集合无 @OrderBy，返回数组顺序按 (job_id, def_code) 索引，故"执行顺序"以
#   条目 id 递升为准（执行器依 DependencyResolver.sort 的次序逐个 startItem → 插入序即拓扑序）
# ============================================================
Say '--- TC9 检查通过 + 依赖拓扑 ---'
try {
    $t9x = New-ExportTask 'S5B-TC9-基件' @('METRIC_DICT', 'ALARM_THRESHOLD')
    $j9x = Start-JobOf $t9x 'EXPORT'
    $s9x = Wait-Job $j9x.id
    if ($s9x.status -ne 'COMPLETED') { throw "基件导出失败: $($s9x.status)" }
    [IO.File]::WriteAllBytes((Join-Path $tmp 'metric_ok.xlsx'), (GetBytes "/api/tasks/$t9x/files/METRIC_DICT"))
    [IO.File]::WriteAllBytes((Join-Path $tmp 'alarm_ok.xlsx'), (GetBytes "/api/tasks/$t9x/files/ALARM_THRESHOLD"))

    # 选中顺序故意"依赖方在前"，用于验证后端拓扑排序
    $t9 = New-ImportTask 'S5B-TC9-检查通过' @('ALARM_THRESHOLD', 'METRIC_DICT')
    Upload-File $t9 (Join-Path $tmp 'alarm_ok.xlsx') 'ALARM_THRESHOLD_ok.xlsx' | Out-Null
    Upload-File $t9 (Join-Path $tmp 'metric_ok.xlsx') 'METRIC_DICT_ok.xlsx' | Out-Null
    $j9 = Start-JobOf $t9 'PRECHECK'
    $s9 = Wait-Job $j9.id
    if ($s9.status -ne 'COMPLETED') { throw "检查应为 COMPLETED，实际 $($s9.status)（error=$($s9.errorCount)）" }
    if ($s9.errorCount -ne 0) { throw "errorCount=$($s9.errorCount)" }
    $mi = @($s9.items | Where-Object { $_.defCode -eq 'METRIC_DICT' })
    $ai = @($s9.items | Where-Object { $_.defCode -eq 'ALARM_THRESHOLD' })
    if ($mi.Count -ne 1 -or $ai.Count -ne 1) { throw "作业条目异常: $(@($s9.items).Count) 条" }
    if ($mi[0].id -ge $ai[0].id) {
        throw "依赖拓扑错误：METRIC_DICT 条目 id=$($mi[0].id) 未先于 ALARM_THRESHOLD id=$($ai[0].id)"
    }
    $task9 = GetJson "/api/tasks/$t9"
    if (@($task9.items | Where-Object { $_.status -ne 'CHECKED' }).Count -gt 0) { throw '任务条目未全部 CHECKED' }
    Ok 'TC9 检查全通过 + 依赖拓扑（METRIC_DICT 先于 ALARM_THRESHOLD）+ 条目状态 CHECKED'
} catch { No 'TC9' $_.Exception.Message }

# ============================================================
# TC10 检查失败明细 + 未上传文件（含 Q8① 明细落库验证）
#   源断言：非法选项/引用不存在/未上传文件三类明细
#   main 适配：① 明细经 GET /api/jobs/{jobId}/issues 分页读取（落库，不落盘）；
#   ② "未上传文件"的配置项在预检查阶段即报 ERROR 且明细可查（Q8① 的原始缺陷是写盘 NoSuchFileException，
#   本仓库明细仅在库中，故断言"明细可查 + 日志无 NoSuchFileException/明细写入失败"）
# ============================================================
Say '--- TC10 检查失败明细 + 未上传文件 ---'
try {
    if (-not $script:tc3JobId) { throw 'TC3 未产出检查作业，无法验证明细' }
    $page = GetJson "/api/jobs/$($script:tc3JobId)/issues?page=0&size=10"
    if ($page.totalElements -ne 4) { throw "明细总数 $($page.totalElements)，期望 4" }
    if ($page.content.Count -ne 4) { throw "首页明细 $($page.content.Count) 条，期望 4" }
    if (@($page.content | Where-Object { $_.jobId -ne $script:tc3JobId }).Count -gt 0) { throw '明细混入其他作业' }
    $page2 = GetJson "/api/jobs/$($script:tc3JobId)/issues?page=0&size=2"
    if ($page2.content.Count -ne 2 -or $page2.totalElements -ne 4) { throw '明细分页未生效' }

    $t10 = New-ImportTask 'S5B-TC10-未上传' @('PROJECT_ENV')
    $j10 = Start-JobOf $t10 'PRECHECK'
    $s10 = Wait-Job $j10.id
    if ($s10.status -ne 'FAILED') { throw "未上传文件应 FAILED，实际 $($s10.status)" }
    $iss10 = GetJson "/api/jobs/$($j10.id)/issues?page=0&size=50"
    if (@($iss10.content | Where-Object { $_.message -like '*未找到上传文件*' }).Count -lt 1) {
        throw '未上传文件未给出明细'
    }

    # Q8① 日志项：明细写盘失败（NoSuchFileException / 明细写入失败）不得出现
    $log = Get-LogText
    if ($log -like '*NoSuchFileException*') { throw '日志出现 NoSuchFileException（Q8① 未修复）' }
    if ($log -like '*明细写入失败*') { throw '日志出现"明细写入失败"（Q8① 未修复）' }
    Ok 'TC10 检查失败明细可查（分页/总数/归属正确）+ 未上传文件报错 + 日志无写盘异常'
} catch { No 'TC10' $_.Exception.Message }

# ============================================================
# TC11 SSE 进度事件流（任务级事件通道）
#   源断言：订阅导出任务事件流收到 progress/done
#   main 适配：GET /api/tasks/{id}/events（无名事件的 data 帧，type 在帧内），
#   帧类型为 JOB_PROGRESS / JOB_DONE / TASK_CHANGED / HEARTBEAT；
#   后端需以 --app.job.batch-size=10 启动（默认 100 时小配置项不跨分片边界，无进度帧）
# ============================================================
Say '--- TC11 SSE 进度事件流 ---'
try {
    $script:t11 = New-ExportTask 'S5B-TC11-SSE进度' @('ALARM_THRESHOLD')
    $frames11 = New-Object System.Collections.ArrayList
    $script:tc11Job = $null
    $onOpen11 = { $script:tc11Job = Start-JobOf $script:t11 'EXPORT' }
    Read-Sse "/api/tasks/$($script:t11)/events" $null @('JOB_DONE') $frames11 $onOpen11
    $prog = @($frames11 | Where-Object { $_.type -eq 'JOB_PROGRESS' })
    $done = @($frames11 | Where-Object { $_.type -eq 'JOB_DONE' })
    if ($prog.Count -lt 2) {
        throw "收到 JOB_PROGRESS 帧 $($prog.Count) 条（后端需以 --app.job.batch-size=10 启动）"
    }
    $processed = @($prog | ForEach-Object { [int]$_.data.processed })
    for ($i = 1; $i -lt $processed.Count; $i++) {
        if ($processed[$i] -lt $processed[$i - 1]) { throw "进度倒退: $($processed -join ',')" }
    }
    if ($done.Count -lt 1) { throw '未收到 JOB_DONE' }
    if ($done[$done.Count - 1].data.status -ne 'COMPLETED') { throw "JOB_DONE 状态 $($done[$done.Count - 1].data.status)" }
    if ($processed[$processed.Count - 1] -ne 120) { throw "末条进度 processed=$($processed[$processed.Count - 1])，期望 120" }
    if (-not $script:tc11Job) { throw '未创建导出作业' }
    $s11 = Wait-Job $script:tc11Job.id
    if ($s11.status -ne 'COMPLETED' -or $s11.progress -ne $s11.total) { throw "作业终态 $($s11.status) $($s11.progress)/$($s11.total)" }
    Ok ("TC11 收到进度事件流 {0} 条（末条 {1}/120）+ JOB_DONE(COMPLETED)" -f $prog.Count, $processed[$processed.Count - 1])
} catch { No 'TC11' $_.Exception.Message }

# ============================================================
# TC12 导入草稿隔离（暂存不动生效数据）
#   源断言：导入后 publishedRowCount 不变、草稿可预览
#   main 适配：暂存行经 GET /api/jobs/{importJobId}/diff 读取（config_staging_rows）；
#   M1 收尾守卫① 生效后 IMPORT 前必须先通过 PRECHECK（守卫把守作业入口），故本用例
#   先跑一次预检查（真实流程），再导入
# ============================================================
Say '--- TC12 导入草稿隔离 ---'
try {
    $before = GetJson '/api/data/CURRENCY?page=0&size=50'
    $beforeIds = @{}
    foreach ($r in $before.content) { $beforeIds[$r.rowKey] = $r.id }
    if ($before.content.Count -ne 5) { throw "前置行数 $($before.content.Count)，期望 5" }

    $script:t12 = New-ImportTask 'S5B-TC12-草稿隔离' @('CURRENCY')
    Upload-File $script:t12 (Join-Path $tmp 'CURRENCY_3rows.xlsx') 'CURRENCY_3rows.xlsx' | Out-Null
    $jPre12 = Start-JobOf $script:t12 'PRECHECK'
    $sPre12 = Wait-Job $jPre12.id
    if ($sPre12.status -ne 'COMPLETED' -or $sPre12.errorCount -ne 0) {
        throw "TC12 前置预检查未通过: $($sPre12.status) error=$($sPre12.errorCount)"
    }
    $jImp = Start-JobOf $script:t12 'IMPORT'
    $sImp = Wait-Job $jImp.id
    if ($sImp.status -ne 'COMPLETED') { throw "导入失败: $($sImp.status)" }

    $after = GetJson '/api/data/CURRENCY?page=0&size=50'
    if ($after.content.Count -ne 5) { throw "导入后生效行数 $($after.content.Count)，应保持 5" }
    if (@($after.content | Where-Object { $_.rowKey -eq 'JPY' }).Count -ne 1) { throw '生效数据在导入后被改动（JPY 丢失）' }

    $diff = @(GetJson "/api/jobs/$($jImp.id)/diff")
    if ($diff.Count -ne 3) { throw "暂存行数 $($diff.Count)，期望 3" }
    if (@($diff | Where-Object { $_.status -ne 'STAGED' }).Count -gt 0) { throw '存在非 STAGED 的暂存行' }
    if (@($diff | Where-Object { $_.defCode -ne 'CURRENCY' }).Count -gt 0) { throw '暂存行混入其他配置项' }

    $task12 = GetJson "/api/tasks/$($script:t12)"
    $item12 = @($task12.items | Where-Object { $_.defCode -eq 'CURRENCY' })[0]
    if ($item12.status -ne 'IMPORTED') { throw "任务条目状态 $($item12.status)，期望 IMPORTED" }
    $script:beforeIds12 = $beforeIds
    Ok 'TC12 导入写暂存（3 行 STAGED），生效数据保持 5 行不变'
} catch { No 'TC12' $_.Exception.Message }

# ============================================================
# TC13 发布：范围替换（upsert 保 id + 差集删除）+ 幂等 + 终态不可取消
#   源断言：发布后 5→3 行、重复发布被拦截
#   main 适配（Q14 定案语义）：REPLACE = 行级 upsert（保留行 id、版本递增）
#   + 覆盖范围内未出现的旧行删除；"重复发布"改为"重复发布幂等"，并以
#   "取消已终态作业 → 409 JOB_ALREADY_FINAL"表达 main 的冲突语义
# ============================================================
Say '--- TC13 发布：范围替换 + 幂等 + 终态冲突 ---'
try {
    PutJson "/api/tasks/$($script:t12)/import-mode" @{ mode = 'REPLACE' } | Out-Null
    $jPub = Start-JobOf $script:t12 'PUBLISH'
    $sPub = Wait-Job $jPub.id
    if ($sPub.status -ne 'COMPLETED') { throw "发布失败: $($sPub.status) error=$($sPub.errorCount)" }

    $after = GetJson '/api/data/CURRENCY?page=0&size=50'
    $afterMap = @{}
    foreach ($r in $after.content) { $afterMap[$r.rowKey] = $r }
    if ($after.content.Count -ne 3) { throw "发布后行数 $($after.content.Count)，期望 3（5 行 → 替换为 3 行）" }
    foreach ($k in @('CNY', 'USD', 'EUR')) {
        if (-not $afterMap.ContainsKey($k)) { throw "缺失 $k" }
        if ($afterMap[$k].id -ne $script:beforeIds12[$k]) { throw "$k 行 id 变化（$( $script:beforeIds12[$k] ) → $($afterMap[$k].id)），upsert 应保留行 id" }
        if ($afterMap[$k].version -le 1) { throw "$k 版本未递增（$($afterMap[$k].version)）" }
    }
    if ($afterMap.ContainsKey('JPY') -or $afterMap.ContainsKey('GBP')) { throw '范围内未出现的旧行未被差集删除' }

    # 重复发布同一份暂存 → 被"导入快照版本比对"拦住（作业 FAILED + 行级发布冲突文案），
    # 且生效数据零变化。这是 main 相对源脚本"重复发布未拦截"更强的守卫：
    # 暂存行记录了导入时刻的行版本（baseVersion），发布后版本已递增，故再次发布即判定冲突。
    $jPub2 = Start-JobOf $script:t12 'PUBLISH'
    $sPub2 = Wait-Job $jPub2.id
    if ($sPub2.status -ne 'FAILED') { throw "二次发布应因快照冲突 FAILED，实际 $($sPub2.status)" }
    $issPub2 = GetJson "/api/jobs/$($jPub2.id)/issues?page=0&size=50"
    if (@($issPub2.content | Where-Object { $_.message -like '*发布冲突*' }).Count -lt 1) { throw '二次发布未给出发布冲突明细' }
    $again = GetJson '/api/data/CURRENCY?page=0&size=50'
    if ($again.content.Count -ne 3) { throw "二次发布后行数 $($again.content.Count)" }
    foreach ($r in $again.content) {
        if ($r.id -ne $afterMap[$r.rowKey].id) { throw "二次发布后 $($r.rowKey) 行 id 变化" }
    }

    # 终态作业取消 → 409 JOB_ALREADY_FINAL（机器可读码）
    $c = Expect-Fail 'DELETE' "/api/jobs/$($jPub2.id)" $null '终态作业取消未被拒'
    if ($c.status -ne 409 -or $c.json.code -ne 'JOB_ALREADY_FINAL') {
        throw "终态取消响应 HTTP $($c.status)/code=$($c.json.code)，期望 409/JOB_ALREADY_FINAL"
    }
    Ok 'TC13 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 快照冲突拦截重复发布 + 409 JOB_ALREADY_FINAL'
} catch { No 'TC13' $_.Exception.Message }

# ============================================================
# TC14 导入/发布前置守卫（M1 收尾守卫①：预检查未通过 → 拒绝）
#   源断言：检查失败 → 导入失败 → 发布被拒
#   main 适配：守卫落在作业入口 POST /api/tasks/{id}/jobs（JobService#createAndStart）——
#   ① 尚无预检查记录（从严口径）→ 409 PRECHECK_NOT_PASSED；
#   ② 预检查真实失败（无上传文件）→ 同样 409，且被拒时不落任何作业行；
#   ③ 守卫放行侧 + 空暂存发布的不误删不变量（S5.b 待裁决 #5：空发布是无害空操作）
# ============================================================
Say '--- TC14 导入/发布前置守卫（预检查未过 → 被拒） ---'
try {
    $beforeEnv = (GetJson '/api/data/PROJECT_ENV/count').count
    if ($beforeEnv -le 0) { throw 'PROJECT_ENV 无种子数据，前置不成立' }

    # ① 从严分支：尚无任何预检查记录 → IMPORT/PUBLISH 均被拒（且不留作业行）
    $t14 = New-ImportTask 'S5B-TC14-无预检查' @('PROJECT_ENV') 'REPLACE'
    foreach ($jt in @('IMPORT', 'PUBLISH')) {
        $r = Expect-Fail 'POST' "/api/tasks/$t14/jobs" @{ jobType = $jt } "无预检查记录的 $jt 未被拒"
        if ($r.status -ne 409 -or $r.json.code -ne 'PRECHECK_NOT_PASSED') {
            throw "无预检查记录时 $jt：HTTP $($r.status)/code=$($r.json.code)，期望 409/PRECHECK_NOT_PASSED"
        }
    }
    if (@(GetJson "/api/tasks/$t14/jobs").Count -ne 0) { throw '被守卫拒绝时落了作业行' }

    # ② 真实失败分支：无上传文件 → PRECHECK FAILED（明细可查）→ IMPORT/PUBLISH 仍被拒
    $j14 = Start-JobOf $t14 'PRECHECK'
    $s14 = Wait-Job $j14.id
    if ($s14.status -ne 'FAILED') { throw "无上传文件的预检查应 FAILED，实际 $($s14.status)" }
    $iss14 = GetJson "/api/jobs/$($j14.id)/issues?page=0&size=50"
    if (@($iss14.content | Where-Object { $_.message -like '*未找到上传文件*' }).Count -lt 1) {
        throw '预检查失败未给出"未找到上传文件"明细'
    }
    foreach ($jt in @('IMPORT', 'PUBLISH')) {
        $r = Expect-Fail 'POST' "/api/tasks/$t14/jobs" @{ jobType = $jt } "预检查失败后的 $jt 未被拒"
        if ($r.status -ne 409 -or $r.json.code -ne 'PRECHECK_NOT_PASSED') {
            throw "预检查失败后 $jt：HTTP $($r.status)/code=$($r.json.code)，期望 409/PRECHECK_NOT_PASSED"
        }
    }
    $jobs14 = @(GetJson "/api/tasks/$t14/jobs")
    if (@($jobs14 | Where-Object { $_.jobType -ne 'PRECHECK' }).Count -gt 0) { throw '被拒的导入/发布仍落了作业行' }
    $afterEnv = (GetJson '/api/data/PROJECT_ENV/count').count
    if ($afterEnv -ne $beforeEnv) { throw "生效数据被改变: $beforeEnv → $afterEnv" }

    # ③ 放行侧 + 不误删不变量：预检查通过后可发布，但暂存集为空 → 生效数据零变化
    $t14b = New-ImportTask 'S5B-TC14-空暂存' @('DOC_TYPE') 'REPLACE'
    Upload-File $t14b (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_ok.xlsx' | Out-Null
    $j14bPre = Start-JobOf $t14b 'PRECHECK'
    $s14bPre = Wait-Job $j14bPre.id
    if ($s14bPre.status -ne 'COMPLETED' -or $s14bPre.errorCount -ne 0) {
        throw "空暂存分支前置预检查未通过: $($s14bPre.status) error=$($s14bPre.errorCount)"
    }
    $docBefore = (GetJson '/api/data/DOC_TYPE/count').count
    $j14b = Start-JobOf $t14b 'PUBLISH'          # 守卫放行（预检查已通过）
    $s14b = Wait-Job $j14b.id
    $docAfter = (GetJson '/api/data/DOC_TYPE/count').count
    if ($docAfter -ne $docBefore) { throw "空暂存发布改变了生效数据: $docBefore → $docAfter" }

    Ok ("TC14 守卫真实阻断（无预检查/预检查失败 → IMPORT、PUBLISH 均 409 PRECHECK_NOT_PASSED，零作业行；PROJECT_ENV {0} 行不变）+ 放行侧空暂存发布不误删（DOC_TYPE {1} 行不变，终态 {2}）" -f $afterEnv, $docAfter, $s14b.status)
} catch { No 'TC14' $_.Exception.Message }

# ============================================================
# TC20 任务中心（统一列表 / 创建即持久化 / 类型与状态过滤 / 关键词转义）
#   源断言：统一列表含导出与导入、创建即入库、type 过滤
#   main 适配：/api/tasks 为 Spring Page 信封（content/totalElements，page 从 0 起），
#   每行为 TaskSummary{task,itemCount,fileCount,latestJob}；关键词 LIKE 转义为 Q16② 修复项
# ============================================================
Say '--- TC20 任务中心（列表/持久化/过滤/转义） ---'
try {
    $pAll = GetJson '/api/tasks?page=0&size=500'
    if ($pAll.totalElements -lt 1) { throw '任务列表为空' }
    $types = @($pAll.content | ForEach-Object { $_.task.type } | Sort-Object -Unique)
    if ($types.Count -lt 2) { throw "列表未同时包含导出与导入任务: $($types -join ',')" }
    $sample = @($pAll.content | Where-Object { $_.task.id -eq $script:tExp4 })[0]
    if ($null -eq $sample) { throw 'TC4 的导出任务不在列表中' }
    if ($sample.itemCount -ne 3) { throw "itemCount=$($sample.itemCount)，期望 3" }
    if ($sample.fileCount -ne 3) { throw "fileCount=$($sample.fileCount)，期望 3" }
    if ($null -eq $sample.latestJob -or $sample.latestJob.status -ne 'COMPLETED') { throw 'latestJob 缺失或状态异常' }

    $immediate = PostJson '/api/tasks' @{ type = 'EXPORT'; title = 'S5B-TC20-即时可见' }
    $found = @((GetJson '/api/tasks?page=0&size=500').content | Where-Object { $_.task.id -eq $immediate.id })
    if ($found.Count -ne 1) { throw '新建任务未立即出现在任务列表' }
    if ($found[0].task.status -ne 'ACTIVE') { throw "新建任务状态 $($found[0].task.status)" }
    if ($found[0].task.currentStep -ne 'SELECT_DEFS') { throw "新建任务步骤 $($found[0].task.currentStep)" }

    $onlyImport = GetJson '/api/tasks?type=IMPORT&page=0&size=500'
    if (@($onlyImport.content | Where-Object { $_.task.type -ne 'IMPORT' }).Count -gt 0) { throw 'IMPORT 过滤混入导出任务' }
    $onlyExport = GetJson '/api/tasks?type=EXPORT&page=0&size=500'
    if (@($onlyExport.content | Where-Object { $_.task.type -ne 'EXPORT' }).Count -gt 0) { throw 'EXPORT 过滤混入导入任务' }
    $onlyDone = GetJson '/api/tasks?status=COMPLETED&page=0&size=500'
    if (@($onlyDone.content | Where-Object { $_.task.status -ne 'COMPLETED' }).Count -gt 0) { throw '状态过滤混入非终态任务' }
    $kwHit = GetJson '/api/tasks?keyword=S5B&page=0&size=500'
    if ($kwHit.totalElements -lt 1) { throw '关键词 S5B 无命中' }
    $kwMiss = GetJson '/api/tasks?keyword=%25&page=0&size=500'
    if ($kwMiss.totalElements -ne 0) { throw "关键词 % 命中 $($kwMiss.totalElements) 条（LIKE 转义未生效）" }
    $script:t20 = $immediate.id
    Ok 'TC20 统一列表/创建即持久化/类型与状态过滤/关键词转义（% 字符 → 0 命中）正确'
} catch { No 'TC20' $_.Exception.Message }

# ============================================================
# TC21 任务服务端分页
#   源断言：size=5 两页不重复、total ≥5
#   main 适配：page 从 0 起；排序 createdAt DESC, id DESC（Q16① 第二排序键）
# ============================================================
Say '--- TC21 任务分页 ---'
try {
    $p1 = GetJson '/api/tasks?page=0&size=5'
    $p2 = GetJson '/api/tasks?page=1&size=5'
    if ($p1.content.Count -gt 5) { throw "第 1 页行数 $($p1.content.Count)" }
    if ($p1.totalElements -lt 5) { throw "totalElements=$($p1.totalElements)" }
    if ($p2.content.Count -lt 1) { throw '第 2 页为空（历史任务应超过 5 条）' }
    if ($p1.number -ne 0 -or $p2.number -ne 1) { throw "分页号异常 $($p1.number)/$($p2.number)" }
    $ids1 = @($p1.content | ForEach-Object { $_.task.id })
    $ids2 = @($p2.content | ForEach-Object { $_.task.id })
    $overlap = @($ids1 | Where-Object { $ids2 -contains $_ })
    if ($overlap.Count -gt 0) { throw "两页重复: $($overlap -join ',')" }
    for ($i = 1; $i -lt $ids1.Count; $i++) {
        if ($ids1[$i] -ge $ids1[$i - 1]) {
            if ($ids1[$i] -gt $ids1[$i - 1]) { throw "第 1 页未按 id DESC 排序: $($ids1 -join ',')" }
        }
    }
    Ok ("TC21 任务分页正确（total={0}，第 1 页 {1} 条 / 第 2 页 {2} 条，无重复）" -f $p1.totalElements, $ids1.Count, $ids2.Count)
} catch { No 'TC21' $_.Exception.Message }

# ============================================================
# TC15 AI 对话（流式 / 工具调用 / 渐进披露）
#   源断言：tool_start list_config_defs + /api/ai/tools?page=export 的披露子集
#   main 适配：披露子集由只读端点 GET /api/ai/tools 取证（M1 收尾项⑤新增）——
#   该端点与 ToolRegistry.forContext 同源，故断言不再依赖后端 DEBUG 日志；
#   提供 -BackendLog 时仍做一次"日志行 vs 端点"的交叉校验。取 page:tasks 与
#   task:IMPORT/PUBLISH 两个上下文对照（start_export 只在任务中心/导出向导披露；
#   start_publish 只在导入向导的发布步披露）
# ============================================================
Say '--- TC15 AI 对话（流式 / 工具 / 渐进披露） ---'
try {
    $script:aiSession15 = 's5b-' + [Guid]::NewGuid().ToString('N')
    $turn = $null
    $hitTool = $false
    for ($attempt = 1; $attempt -le 2 -and -not $hitTool; $attempt++) {
        $msg = if ($attempt -eq 1) { '请列出系统中所有配置定义' } else { '请调用 list_config_defs 工具列出系统中所有配置定义' }
        $turn = Invoke-AiTurn $script:aiSession15 $msg @{ page = 'tasks' } $script:continueHandler 4
        $hitTool = (@($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'list_config_defs' }).Count -gt 0)
    }
    if (-not $turn.runId) { throw '未收到 start 帧（runId 缺失）' }
    if (-not (@($turn.frames | Where-Object { $_.type -eq 'delta' }).Count -ge 1)) { throw '未收到 delta 正文帧' }
    if (-not $hitTool) { throw '未调用 list_config_defs 工具（重试后仍未命中）' }
    $tr = @($turn.frames | Where-Object { $_.type -eq 'tool_result' -and $_.name -eq 'list_config_defs' })
    if ($tr.Count -lt 1 -or -not $tr[0].ok) { throw '工具结果帧异常' }
    if (-not (@($turn.frames | Where-Object { $_.type -eq 'done' }).Count -ge 1)) { throw '未收到 done 帧' }

    # 第二个上下文：导入向导发布步（用于披露对照）
    $turn2 = Invoke-AiTurn $script:aiSession15 '当前这个导入向导走到哪一步了？只回答步骤名称即可。' `
        @{ page = 'import'; taskId = $script:t12; taskType = 'IMPORT'; step = 'PUBLISH' } $script:continueHandler 4
    if (-not (@($turn2.frames | Where-Object { $_.type -eq 'done' }).Count -ge 1)) { throw '第二个上下文未收到 done 帧' }

    # 主断言：只读端点 GET /api/ai/tools（M1 收尾项⑤；与 ToolRegistry.forContext 同源，不依赖日志）
    $toolsTasks = GetJson '/api/ai/tools?page=tasks'
    if ($toolsTasks.context -ne 'page:tasks') { throw "端点上下文 $($toolsTasks.context)，期望 page:tasks" }
    $tTasks = @($toolsTasks.toolNames)
    $toolsPub = GetJson '/api/ai/tools?page=import&taskType=IMPORT&step=PUBLISH'
    if ($toolsPub.context -ne 'task:IMPORT/PUBLISH') { throw "端点上下文 $($toolsPub.context)，期望 task:IMPORT/PUBLISH" }
    $tPub = @($toolsPub.toolNames)

    foreach ($need in @('list_config_defs', 'create_task', 'navigate_to', 'select_definitions', 'start_export')) {
        if ($tTasks -notcontains $need) { throw "page:tasks 未披露 $need（$($tTasks -join ',')）" }
    }
    if ($tTasks -contains 'start_publish') { throw 'page:tasks 不应披露 start_publish' }
    if ($tPub -notcontains 'start_publish') { throw "task:IMPORT/PUBLISH 缺少 start_publish（$($tPub -join ',')）" }
    if ($tPub -contains 'start_export') { throw 'task:IMPORT/PUBLISH 不应披露 start_export' }

    # 备选交叉校验：后端 DEBUG 行的披露清单必须与只读端点一致（未提供 -BackendLog 时跳过）
    if ($BackendLog) {
        $disc = Get-DisclosureMap
        if (-not $disc.ContainsKey('page:tasks')) { throw '日志中无 page:tasks 的披露记录' }
        if (-not $disc.ContainsKey('task:IMPORT/PUBLISH')) { throw '日志中无 task:IMPORT/PUBLISH 的披露记录' }
        $logTasks = (@($disc['page:tasks']) | Sort-Object) -join ','
        $logPub = (@($disc['task:IMPORT/PUBLISH']) | Sort-Object) -join ','
        $epTasks = (@($tTasks) | Sort-Object) -join ','
        $epPub = (@($tPub) | Sort-Object) -join ','
        if ($logTasks -ne $epTasks) { throw "page:tasks 日志($logTasks) 与端点($epTasks) 披露不一致" }
        if ($logPub -ne $epPub) { throw "task:IMPORT/PUBLISH 日志($logPub) 与端点($epPub) 披露不一致" }
    }
    Ok ("TC15 流式+工具调用可见+渐进披露（只读端点 /api/ai/tools）：page:tasks {0} 个工具（无 start_publish）；task:IMPORT/PUBLISH 含 start_publish" -f $tTasks.Count)
} catch { No 'TC15' $_.Exception.Message }

# ============================================================
# TC16 AI 驱动业务（确认门放行 → 真实创建作业；前端工具挂起 → 回灌续跑）
#   源断言：ui_event(select_defs/export_started) + 导出任务数增加
#   main 适配：不再有 ui_event 帧；工作区动作是 FRONTEND 通道工具，经
#   frontend_tool_request 挂起 + POST /api/ai/frontend-tool-result 回灌；
#   作业发起是 DANGER 工具，经 confirm_request 挂起 + POST /api/ai/confirm 放行后
#   由后端真实创建作业（同一服务层，副作用可核）
# ============================================================
Say '--- TC16 AI 工具驱动工作区（确认门 + 前端工具挂起） ---'
try {
    $script:t16 = New-ExportTask 'S5B-TC16-AI驱动' @('CURRENCY')
    PutJson "/api/tasks/$($script:t16)/step" @{ step = 'EXPORT' } | Out-Null
    $session16 = 's5b-' + [Guid]::NewGuid().ToString('N')
    $jobsBefore = @(GetJson "/api/tasks/$($script:t16)/jobs").Count

    $handler16 = {
        param($f)
        if ($f.type -eq 'confirm_request') {
            if ($f.name -eq 'start_export') { return @{ approved = $true } }
            return @{ approved = $false }
        }
        return @{ result = '{"ok":true,"page":"export","taskId":' + $script:t16 + '}' }
    }
    $turn16 = Invoke-AiTurn $session16 `
        ("请立即调用 start_export 工具启动任务 {0} 的导出作业（系统会自动弹出确认卡片，无需再询问我）" -f $script:t16) `
        @{ page = 'export'; taskId = $script:t16; taskType = 'EXPORT'; step = 'EXPORT' } $handler16 3

    $confirm = @($turn16.frames | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_export' })
    if ($confirm.Count -lt 1) { throw '未收到 start_export 的确认门事件' }

    $newJob = Wait-Until {
        $js = @(GetJson "/api/tasks/$($script:t16)/jobs")
        if ($js.Count -gt $jobsBefore) { return $js[0] } else { return $null }
    } 60 "AI 放行后未创建导出作业"
    $final = Wait-Job $newJob.id
    if ($final.jobType -ne 'EXPORT') { throw "作业类型 $($final.jobType)" }
    $files16 = @(GetJson "/api/tasks/$($script:t16)/files" | Where-Object { $_.fileType -eq 'EXPORT' })
    if ($files16.Count -lt 1) { throw '确认放行后未产出导出文件' }

    # 前端工具挂起通道：工作区动作（navigate_to / select_definitions）不得由后端执行，需回灌结果
    $session16b = 's5b-' + [Guid]::NewGuid().ToString('N')
    $handler16b = {
        param($f)
        if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
        return @{ result = '{"ok":true,"handled":"e2e"}' }
    }
    $turn16b = Invoke-AiTurn $session16b `
        ("请打开任务 {0} 的导出向导，并帮我勾选 CURRENCY 配置项" -f $script:t16) `
        @{ page = 'tasks'; taskId = $script:t16; taskType = 'EXPORT'; step = 'SELECT_DEFS' } $handler16b 4
    $fe = @($turn16b.frames | Where-Object { $_.type -eq 'frontend_tool_request' })
    if ($fe.Count -lt 1) { throw '未收到 frontend_tool_request（工作区动作未走前端通道）' }
    $feNames = @($fe | ForEach-Object { $_.name } | Sort-Object -Unique)
    if (@($feNames | Where-Object { @('navigate_to', 'select_definitions', 'confirm_step', 'set_condition') -contains $_ }).Count -lt 1) {
        throw "前端工具名不在工作区动作表内: $($feNames -join ',')"
    }
    $feResult = @($turn16b.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq $fe[0].name })
    if ($feResult.Count -ge 1 -and $feResult[0].executed -eq $true) { throw '前端工具被后端直接执行（应为挂起等回灌）' }
    Ok ("TC16 确认放行→真实创建导出作业并产出文件；前端工具挂起+回灌续跑（{0}）" -f ($feNames -join ','))
} catch { No 'TC16' $_.Exception.Message }

# ============================================================
# GF1–GF6 生成式表单 E2E 用例（设计稿 docs/evidence/GFc-E2E用例设计-GLM-5.3.md）
#   - GF1–GF5 计入主 PASS/FAIL 与退出码；模型未触发记 SKIP（GFSKIP=n 单列，不计退出码；
#     GF1–GF4 全部 SKIP 触发硬底线整棒 FAIL，裁决 #1）
#   - GF6（入参闸门）需 -EnableGf6：**桩模式启用并计入主计数**（底座 = 桩的确定性违规模式
#     --violate-form-type，请求级只在 GF6 提示词上触发）；真模型模式默认关（服从率不可控）
#   - 会话统一 gfc-<guid> 前缀；GF2 fixture 任务 GFC-* 用例内自删 + 预清理追加段双保险
# ============================================================

# GF 用例的对话轮蓝本（设计稿 §5.2）：与 Invoke-AiTurn 同构（Read-Sse + lastSeq 差量重挂 + 180s 预算），
# 差异仅一处——挂起处理回调返回"待 POST 的载荷描述"（$act.payload：result / cancelled / source），
# 由本函数统一用 Invoke-Api 发 POST（非 2xx 不 throw，GF3/GF4/GF5 需要原始 400/409 响应），
# 原始响应依次收集到 $script:gfPosts 供用例块断言。回调返回 $null 或 @{ break = $true } 表示停止本轮读取；
# 回调未给 payload 时：generative_form 挂起按 §5.3 语义 cancelled:true 收敛，其它前端工具按通用回灌继续
# （仿 TC17 handler17"回灌并继续"策略）。
function Invoke-FormTurn($sessionId, $message, $context, [scriptblock]$onSuspend, [int]$maxSuspend = 6) {
    $frames = New-Object System.Collections.ArrayList
    $runId = $null
    $lastSeq = 0
    for ($i = 0; $i -le $maxSuspend; $i++) {
        $batch = New-Object System.Collections.ArrayList
        if ($i -eq 0) {
            Read-Sse '/api/ai/chat' @{ sessionId = $sessionId; message = $message; context = $context } `
                @('confirm_request', 'frontend_tool_request', 'done', 'error') $batch $null
        } else {
            if (-not $runId) { break }
            Read-Sse "/api/ai/events/$runId`?lastSeq=$lastSeq" $null `
                @('confirm_request', 'frontend_tool_request', 'done', 'error') $batch $null
        }
        foreach ($f in $batch) {
            $frames.Add($f) | Out-Null
            if ($f.type -eq 'start' -and $f.runId) { $runId = $f.runId }
            if ($f.seq -and [int]$f.seq -gt $lastSeq) { $lastSeq = [int]$f.seq }
        }
        $term = @($batch | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
        if ($term.Count -gt 0) { break }
        $suspend = @($batch | Where-Object { $_.type -eq 'confirm_request' -or $_.type -eq 'frontend_tool_request' })
        if ($suspend.Count -eq 0) { break }
        $s = $suspend[$suspend.Count - 1]
        if (-not $onSuspend) { break }
        $act = & $onSuspend $s $runId
        if ($null -eq $act) { break }
        if ($act['break']) { break }
        if ($s.type -eq 'confirm_request') {
            PostJsonRaw '/api/ai/confirm' @{ runId = $runId; toolCallId = $s.toolCallId; approved = [bool]$act.approved; reason = 'e2e' } | Out-Null
        } else {
            $payload = $act.payload
            if (-not $payload) {
                $payload = if ($s.name -eq 'generative_form') { @{ cancelled = $true; source = 'e2e' } } else { @{ result = '{"ok":true,"source":"e2e"}'; source = 'e2e' } }
            }
            $body = @{ runId = $runId; toolCallId = $s.toolCallId }
            foreach ($k in $payload.Keys) { $body[$k] = $payload[$k] }
            $gfResp = Invoke-Api 'POST' '/api/ai/frontend-tool-result' $body
            $script:gfPosts += @($gfResp)
            # 裁决 #4：回灌请求/响应原文入档（Gf-Dump 落 gfc-<case>.http.txt）
            Gf-Http-Note ("$($s.name) 挂起回灌（Invoke-FormTurn，payload=$($payload.Keys -join ','))") 'POST' '/api/ai/frontend-tool-result' $body $gfResp
        }
        Start-Sleep -Milliseconds 500
    }
    return @{ frames = $frames; runId = $runId; lastSeq = $lastSeq }
}

# 值合成（设计稿 §4）：按实际收到的 schema 现场合成回灌值 JSON——按字段 type 取值
# （text→E2EGFC / number→1 / boolean→$true / date→2026-10-07 / enum→首个 option / multi_select→单元素数组），
# 跳过 required=false 的字段（验证"可选字段缺省合法"）；$prefer 允许用例按提示词固定选项值
# （如 scope=XN）收紧回显断言；$includeOptional 对列出的 key 即使非必填也给出值（保证回显可断言）。
# 产物保持"对象 + 数组字段"外壳经 ConvertTo-Json -Compress 序列化（§1.4：嵌套单元素数组产出正确）。
function New-FormValues($form, $prefer = @{}, [string[]]$includeOptional = @()) {
    $values = [ordered]@{}
    foreach ($field in @($form.fields)) {
        if ($field.required -eq $false -and $includeOptional -notcontains $field.key) { continue }
        # 空 options 防御（裁决取舍②）：enum/multi_select 的 options 为空时跳过该字段，不产出 null 值
        if (@('enum', 'multi_select') -contains $field.type -and @($field.options).Count -lt 1) { continue }
        switch ($field.type) {
            'text' { $v = 'E2EGFC' }
            'number' { $v = 1 }
            'boolean' { $v = $true }
            'date' { $v = '2026-10-07' }
            'enum' {
                $v = $field.options[0].value
                foreach ($opt in @($field.options)) { if ($prefer[$field.key] -eq $opt.value) { $v = $opt.value; break } }
            }
            'multi_select' {
                $v = @($field.options[0].value)
                foreach ($opt in @($field.options)) { if ($prefer[$field.key] -eq $opt.value) { $v = @($opt.value); break } }
            }
            default { throw "表单字段 $($field.key) 类型 $($field.type) 越六型白名单" }
        }
        $values[$field.key] = $v
    }
    return ($values | ConvertTo-Json -Depth 10 -Compress)
}

# 短窗读流：$millis 毫秒窗口内能读到的帧全部收集后返回（设计稿 GF4-A3 / GF5-A4 的"无新帧"检查窗）
# 真正时间有界（红队高-2 修复）：读循环以 Stopwatch 定截止，每行用 ReadLineAsync 等待剩余窗口，
# 到点主动断开——挂起 run 的流不关闭（仅心跳）也能自行结束，不会阻塞到 HITL 超时/HttpClient 超时。
#
# 读窗下界由**被观察方的节拍**决定，不是随手取的整数：挂起期心跳每 2s 一帧
# （ConfirmGate.HEARTBEAT_SECONDS = 2），故 3000ms 窗口只兜得住 1 个心跳周期，抖动一次即漏帧
# （漏帧会让"无新帧"断言假绿）。判据取「窗口 ≥ 2 个心跳周期 + 抖动余量」，即 2s × 2 + 1s = 5000ms；
# 参照业界依据 §2（heartbeat < min(代理超时) 实践公式）与 §12（K8s periodSeconds ×
# failureThreshold 的"连续 N 次失败才判死"模式）。2026-10-10 数字规格清点裁决⑤：3000 → 5000。
# 三处调用点本轮一律取 5000（GF4-A3 / GF5-A4 / TC23④）—— 原 3500ms 同样不达标（1.75 个心跳周期），
# 按同一判据同改（2026-10-10 小批苞 F，登记见 docs/M2-排期计划.md 去向表 R-104）。
function Read-Sse-Brief($path, $millis) {
    $frames = New-Object System.Collections.ArrayList
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # 裁决 #3（O1）：握手（连接 + 状态码）必须**显式抛出**，不得被读流窗口的兜底 catch 吞掉——
    # 否则重挂失败会返回空集，调用方的"无新帧"断言（GF4-A3 / GF5-A4）恒真、空转。
    # 故把 SendAsync 与状态码判定放在吞错范围**之外**，只有"窗口内有界读流的中断/到点"才允许吞。
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($Base + $path)
    $req.Method = [System.Net.Http.HttpMethod]::Get
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter($millis)
    $resp = $null
    try {
        $resp = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
    } catch {
        $cts.Dispose()
        throw "SSE $path => 重挂连接失败（${millis}ms 窗口内未建立）：$($_.Exception.Message)"
    }
    if (-not $resp.IsSuccessStatusCode) {
        $code = [int]$resp.StatusCode
        $resp.Dispose(); $cts.Dispose()
        throw "SSE $path => HTTP $code（重挂失败，无新帧断言不可空转）"
    }
    $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result, [System.Text.Encoding]::UTF8)
    try {
        while ($sw.ElapsedMilliseconds -lt $millis) {
            $remaining = $millis - [int]$sw.ElapsedMilliseconds
            if ($remaining -le 0) { break }
            $readTask = $reader.ReadLineAsync()
            if (-not $readTask.Wait($remaining)) { break }  # 窗口到点：主动断开，不再等下一行
            $line = $readTask.Result
            if ($null -eq $line) { break }
            if ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($data -and $data -ne '[DONE]') {
                    $frame = $null
                    try { $frame = $data | ConvertFrom-Json } catch { $frame = $null }
                    if ($frame) { $frames.Add($frame) | Out-Null }
                }
            }
        }
    } catch { } finally { $reader.Dispose(); $resp.Dispose(); $cts.Dispose() }
    return $frames
}

# pending 快照取条目（红队 P0-1 修复：GET /api/ai/pending/{runId} 返回 ApiResponse 信封
# {success,data:[...]}，后端 AiController.pending 实测——条目在 data 内，非裸数组）
function Gf-PendingEntry($runId, $toolCallId) {
    $pend = Invoke-Api 'GET' "/api/ai/pending/$runId" $null
    if (-not $pend.ok -or -not $pend.json -or -not $pend.json.data) { throw "GET /api/ai/pending/$runId => HTTP $($pend.status) $($pend.text)" }
    $entry = @($pend.json.data | Where-Object { $_.toolCallId -eq $toolCallId })[0]
    if (-not $entry) { throw "pending/$runId 无 toolCallId=$toolCallId 条目" }
    return $entry
}

# 失败分支收尾兜底（设计稿 §8）：已记录 toolCallId 且 pending 仍 PENDING 时，FAIL 前 POST
# cancelled:true 收敛挂起，避免 120s 悬置拖慢后续用例
function Gf-Converge($runId, $toolCallId) {
    try {
        if (-not $runId -or -not $toolCallId) { return }
        $pend = Invoke-Api 'GET' "/api/ai/pending/$runId" $null
        if (-not $pend.ok -or -not $pend.json -or -not $pend.json.data) { return }
        $entry = @($pend.json.data | Where-Object { $_.toolCallId -eq $toolCallId })[0]
        if ($entry -and $entry.status -eq 'PENDING') {
            Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $runId; toolCallId = $toolCallId; cancelled = $true; source = 'e2e' } | Out-Null
        }
    } catch { }
}

# SKIP 记账（设计稿 §3.3，裁决 #1）：模型行为问题不记产品 FAIL，单列保留
function Gf-Skip($name, $why) {
    $script:gfskip++
    if (@('GF1', 'GF2', 'GF3', 'GF4') -contains $name) { $script:gfskip14++ }
    $script:results.Add(@{ name = $name; result = "SKIP: $why" }) | Out-Null
    Write-Host ("  [SKIP] " + $name + " => " + $why) -ForegroundColor Yellow
}

# 挂起帧的通用契约断言（设计稿 GF1-A1，GF2/GF3/GF4 同构复用）
function Gf-Assert-FrameContract($f) {
    if (-not $f.toolCallId) { throw 'frontend_tool_request 缺 toolCallId' }
    if ([int]$f.timeoutSeconds -le 0) { throw "frontend_tool_request timeoutSeconds=$($f.timeoutSeconds)，期望 > 0（不硬编码 120，与 app.ai.hitl.frontend-tool-timeout 解耦）" }
    if (-not ($f.expiresAt -match '^\d+$')) { throw 'frontend_tool_request expiresAt 非数字' }
}

# 解析 args 原始 JSON 字符串并取 form（设计稿 §4；args 缺 form / scenario 漂移按 §3.3 判定）
function Gf-Parse-Form($f, $expectScenario, $needKeys, $driftVar) {
    $argsObj = $null
    try { $argsObj = $f.args | ConvertFrom-Json } catch { }
    if (-not $argsObj -or -not $argsObj.form) { throw 'args 缺 form 键（入参闸门未拦截，产品缺陷）' }
    $form = $argsObj.form
    if ($form.scenario -ne $expectScenario) {
        throw "args.form.scenario=$($form.scenario)，期望 $expectScenario（入参闸门未拦截，产品缺陷）"
    }
    $keys = @($form.fields | ForEach-Object { $_.key })
    foreach ($need in $needKeys) {
        if ($keys -notcontains $need) {
            Set-Variable -Name $driftVar -Scope Script -Value "缺字段 $need（实际: $($keys -join ',')）"
            return $null
        }
    }
    foreach ($field in @($form.fields)) {
        if (@('text', 'number', 'boolean', 'date', 'enum', 'multi_select') -notcontains $field.type) {
            throw "字段 $($field.key) 类型 $($field.type) 越六型白名单（入参闸门未拦截，产品缺陷）"
        }
    }
    return $form
}

# 会话记忆中的"最终助手文本"（裁决 #1 的值回显权威通道）：取该会话最后一条 text 非空的 ASSISTANT 消息。
# 与 delta 通道不同，该文本由服务端持久化，**不受**"回灌→重挂间隙 delta 丢失"影响（首轮根因见证据文档 §4）。
function Gf-FinalAssistantText($sessionId) {
    $hist = GetJson "/api/ai/history/$sessionId"
    $asst = @($hist.messages | Where-Object { $_.type -eq 'ASSISTANT' -and -not [string]::IsNullOrWhiteSpace([string]$_.text) })
    if ($asst.Count -lt 1) { throw "会话 $sessionId 无 text 非空的 ASSISTANT 消息（值回显断言无对象）" }
    return [string]$asst[$asst.Count - 1].text
}

# 值回显断言（裁决 #1）：会话最终助手文本必须包含**本次实际回灌的全部值**。
# 关键：断言对象取自**实际 POST 的值 JSON**（$postedJson），而不是提示词里列出的字段——
# 因为设计上"required=false 的字段被 New-FormValues 跳过"（验证可选字段缺省合法），
# 若模型把某字段标为可选，该字段的值根本不会回灌，断言它必然假阴性（GF1 重跑第 1 轮的实测教训）。
function Gf-Assert-Echo($case, $text, $postedJson) {
    $obj = $postedJson | ConvertFrom-Json
    $props = @($obj.PSObject.Properties)
    if ($props.Count -lt 1) { throw "$case 回灌值为空对象，无法做值回显断言" }
    foreach ($p in $props) {
        $vals = @()
        if ($p.Value -is [bool]) { $vals = @($(if ($p.Value) { 'true' } else { 'false' })) }
        elseif ($p.Value -is [System.Array]) { $vals = @($p.Value | ForEach-Object { [string]$_ }) }
        else { $vals = @([string]$p.Value) }
        foreach ($v in $vals) {
            if ($text -notlike "*$v*") { throw "$case 值回显缺 '$($p.Name)=$v'（会话最终助手文本，len=$($text.Length)）" }
        }
        if ($text -notlike "*$($p.Name)*") { throw "$case 值回显缺字段名 '$($p.Name)'（会话最终助手文本）" }
    }
}

# 回灌 HTTP 原文记录（裁决 #4）：调用方在每次 POST 后追加，Gf-Dump 落盘为 gfc-<case>.http.txt
function Gf-Http-Note($label, $method, $path, $reqBody, $r) {
    $reqText = if ($null -eq $reqBody) { '(无请求体)' } elseif ($reqBody -is [string]) { $reqBody } else { $reqBody | ConvertTo-Json -Depth 20 -Compress }
    $script:gfHttp += @("`n### $label`n$method $path`nREQUEST: $reqText`nRESPONSE: HTTP $($r.status)`n$($r.text)")
}

# GF 证据落盘（裁决 #4 / 设计稿 §6）：把本用例**脚本实际收到**的 SSE 帧与回灌 HTTP 原文写到
# $script:gfArtifactDir（仓库外）下的 gfc-<case>.sse.txt / gfc-<case>.http.txt（UTF-8 无 BOM）。
# 成功与失败路径均调用（catch 内也调用，便于事后复盘）；集合为空也写文件（仅表头注释）。
# 落盘失败只告警不判 FAIL——归档属旁证，不应改变用例判定。
function Gf-Dump($case, $frames) {
    if (-not $script:gfArtifactDir) { return }
    try {
        $enc = New-Object System.Text.UTF8Encoding($false)
        $sseLines = New-Object System.Collections.ArrayList
        $sseLines.Add("# gfc-$case —— 脚本实际收到的 SSE 帧（按到达顺序，每行 data: <帧 JSON>）") | Out-Null
        foreach ($f in @($frames)) {
            if ($null -eq $f) { continue }
            $sseLines.Add('data:' + ($f | ConvertTo-Json -Depth 30 -Compress)) | Out-Null
        }
        [IO.File]::WriteAllLines((Join-Path $script:gfArtifactDir "gfc-$case.sse.txt"), $sseLines.ToArray(), $enc)
        $httpLines = New-Object System.Collections.ArrayList
        $httpLines.Add("# gfc-$case —— 回灌 HTTP 请求/响应原文（脚本实际发出的 POST）") | Out-Null
        foreach ($h in @($script:gfHttp)) { if ($h) { $httpLines.Add([string]$h) | Out-Null } }
        [IO.File]::WriteAllLines((Join-Path $script:gfArtifactDir "gfc-$case.http.txt"), $httpLines.ToArray(), $enc)
        Write-Host ("  [ART] gfc-{0}.sse.txt / gfc-{0}.http.txt 已落盘（帧 {1}，HTTP {2}）" -f $case, @($frames).Count, @($script:gfHttp).Count) -ForegroundColor DarkGray
    } catch {
        Write-Host ("  [WARN] GF 证据落盘失败（{0}）：{1}" -f $case, $_.Exception.Message) -ForegroundColor Yellow
    }
}

# ============================================================
# GF1 生成式表单：FILTER 回灌续跑（设计稿 §4 GF1；提示词为设计稿原文）
#   schema 下发 → 按实际 schema 合成值 JSON 回灌 → 续跑值回显
# ============================================================
Say '--- GF1 生成式表单：FILTER 回灌续跑 ---'
$script:gf1Run = $null
$script:gf1Call = $null
try {
    $promptGf1 = '请调用 generative_form 工具（scenario=FILTER）出一张收集导出筛选条件的表单，字段就用这四个，key 和类型必须一致：keyword（文本，必填，占位提示"编码或名称关键字"）、minRows（数字，非必填）、effectiveDate（日期，格式 yyyy-MM-dd，非必填）、scope（下拉单选，选项 XN 和 HD）。不要用文字向我提问，也不要调用 generative_form 以外的任何工具，不会出现确认卡片。我填完后，请把每个字段按 key=值 原样逐行回报。'
    $toolsGf1 = GetJson '/api/ai/tools?page=tasks'
    if (@($toolsGf1.toolNames) -notcontains 'generative_form') { throw '披露端点 /api/ai/tools?page=tasks 未列出 generative_form' }

    $handlerGf1 = {
        param($f, $runId)
        if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
        if ($f.name -ne 'generative_form') { return @{ payload = @{ result = '{"ok":true,"source":"e2e"}' } } }
        $script:gf1Run = $runId
        $script:gf1Call = $f.toolCallId
        Gf-Assert-FrameContract $f
        $form = Gf-Parse-Form $f 'FILTER' @('keyword', 'minRows', 'effectiveDate', 'scope') 'gfSchemaDrift'
        if (-not $form) { return @{ break = $true } }
        $values = New-FormValues $form @{ 'scope' = 'XN' } @('scope')
        $script:gf1Posted = $values
        return @{ payload = @{ result = $values; source = 'e2e-gf' } }
    }
    $attempt = 0
    $turn = $null
    $fe = @()
    $drift = $null
    $driftCount = 0
    $formOk = $false
    while ($attempt -lt 3) {
        $attempt++
        $sessionGf1 = 'gfc-' + [Guid]::NewGuid().ToString('N')
        $script:gfPosts = @()
        $script:gfHttp = @()   # 裁决 #4：HTTP 原文按用例重置，避免 gfc-<case>.http.txt 累积前序用例的请求
        $script:gfSchemaDrift = $null
        $turn = Invoke-FormTurn $sessionGf1 $promptGf1 @{ page = 'tasks' } $handlerGf1 4
        $fe = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.name -eq 'generative_form' })
        # schema 漂移（缺 needKeys，模型行为）与"未触发表单"分开：漂移记数并继续重试，3 次皆漂移才 SKIP（§3.3）
        if ($script:gfSchemaDrift) { $drift = $script:gfSchemaDrift; $driftCount++; Start-Sleep -Seconds 2; continue }
        if ($fe.Count -ge 1) { $formOk = $true; break }
        # SKIP 前置校验（§3.3 判定表第 1 行）：本轮已有 tool_start(generative_form) 却无 frontend_tool_request、
        # 也无 REJECTED_ARGUMENTS 结局帧 → 挂起帧未下发属产品缺陷，判 FAIL，不得记 SKIP
        $toolStartN = @($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'generative_form' }).Count
        $rejN = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq 'generative_form' -and $_.status -eq 'REJECTED_ARGUMENTS' }).Count
        if ($toolStartN -gt 0 -and $rejN -lt 1) { throw "模型已 tool_start(generative_form) 但未收到 frontend_tool_request（亦无 REJECTED_ARGUMENTS 结局帧）——挂起帧未下发，产品缺陷" }
        Start-Sleep -Seconds 2
    }
    if (-not $formOk) {
        if ($driftCount -gt 0) { throw "GF-SKIP:模型 $driftCount/3 次尝试 schema 漂移（最近：$drift），判模型行为" }
        throw 'GF-SKIP:模型 3 次尝试均未触发表单（披露正常），判模型行为'
    }

    # A3 回灌响应：HTTP 200 + FRONTEND_RESULT + accepted + executed=false
    $post = @($script:gfPosts)[0]
    if (-not $post) { throw '未执行回灌 POST' }
    if ($post.status -ne 200 -or -not $post.json) { throw "回灌 POST => HTTP $($post.status) $($post.text)" }
    if ($post.json.status -ne 'FRONTEND_RESULT' -or $post.json.accepted -ne $true -or $post.json.executed -ne $false) {
        throw "回灌响应异常: $($post.text)"
    }
    # A4 pending 终态与 resultText 原样
    $entry = Gf-PendingEntry $script:gf1Run $script:gf1Call
    if ($entry.status -ne 'FRONTEND_RESULT') { throw "pending 条目状态 $($entry.status)，期望 FRONTEND_RESULT" }
    if ($entry.resultText -ne $script:gf1Posted) { throw 'pending resultText 与回灌 JSON 不一致（回灌值未原样入上下文）' }
    # A5 结局帧：ok=true 恰 1 条 + done 恰 1 条 + 无 error
    $ftr = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.toolCallId -eq $script:gf1Call })
    if ($ftr.Count -ne 1 -or $ftr[0].ok -ne $true) { throw "frontend_tool_result 异常（$($ftr.Count) 条 / ok=$($ftr[0].ok)）" }
    if (@($turn.frames | Where-Object { $_.type -eq 'done' }).Count -ne 1) { throw 'done 帧不恰为 1 条' }
    if (@($turn.frames | Where-Object { $_.type -eq 'error' }).Count -gt 0) { throw '出现 error 帧' }
    # A6 值回显（裁决 #1）：**权威通道改为会话记忆的最终助手文本**（服务端持久化，确定性）。
    # 断言对象取自实际回灌值 $script:gf1Posted（可选字段被跳过时不误判）。
    # 原 delta 通道降级为纯 INFO（不判 FAIL）——delta 仅 live 流，回灌→重挂间隙产出的 delta 会丢
    # （首轮 3 例 FAIL 的根因，见 docs/evidence/GFc-端到端执行验证.md §4）。
    $echoGf1 = Gf-FinalAssistantText $sessionGf1
    Gf-Assert-Echo 'GF1' $echoGf1 $script:gf1Posted
    $deltaGf1 = ($turn.frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text }) -join "`n"
    Write-Host ("  [INFO] GF1 delta 弱旁证（不判 FAIL）：{0}" -f $(if ($deltaGf1) { "采到 $($deltaGf1.Length) 字符" } else { '未采到（回灌→重挂间隙，属预期）' })) -ForegroundColor DarkGray
    # A7 后端日志：披露清单含 generative_form（-BackendLog 未提供时 Get-LogText 判 FAIL，与既有口径一致）
    $disc = Get-DisclosureMap
    if (-not $disc.ContainsKey('page:tasks')) { throw '日志中无 page:tasks 的披露记录' }
    if (@($disc['page:tasks']) -notcontains 'generative_form') { throw '日志披露清单缺 generative_form' }
    Gf-Dump 'GF1' $turn.frames
    Ok 'GF1 FILTER 表单：schema 下发→值回灌 200→FRONTEND_RESULT→pending 终态→值回显（会话记忆通道，断言对象=实际回灌值）'
} catch {
    if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF1' $_.Exception.Message.Substring(8); Gf-Dump 'GF1' $turn.frames }
    else { Gf-Converge $script:gf1Run $script:gf1Call; Gf-Dump 'GF1' $turn.frames; No 'GF1' $_.Exception.Message }
}

# ============================================================
# GF2 生成式表单：CLARIFY 回灌续跑（设计稿 §4 GF2；提示词为设计稿原文）
#   fixture 任务推到查询条件步；用例内自删（A7）
# ============================================================
Say '--- GF2 生成式表单：CLARIFY 回灌续跑 ---'
$script:gf2Run = $null
$script:gf2Call = $null
try {
    $tGf2 = New-ExportTask 'GFC-TC-GF2-澄清' @('CURRENCY')
    PutJson "/api/tasks/$tGf2/step" @{ step = 'QUERY_COND' } | Out-Null
    $ctxGf2 = @{ page = 'export'; taskId = $tGf2; taskType = 'EXPORT'; step = 'QUERY_COND' }
    $promptGf2 = '我想继续这个导出任务，但你缺两个信息。请调用 generative_form 工具（scenario=CLARIFY）向我澄清，字段就用这两个，key 和类型必须一致：exportScope（下拉单选，选项 ALL 和 SELECTED）、includeInactive（开关布尔）。不要用文字提问，也不要调用 generative_form 以外的任何工具。我填完后，把两个字段按 key=值 原样逐行回报。'
    $toolsGf2 = GetJson '/api/ai/tools?page=export&taskType=EXPORT&step=QUERY_COND'
    if (@($toolsGf2.toolNames) -notcontains 'generative_form') { throw '披露端点 task:EXPORT/QUERY_COND 未列出 generative_form' }

    $handlerGf2 = {
        param($f, $runId)
        if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
        if ($f.name -ne 'generative_form') { return @{ payload = @{ result = '{"ok":true,"source":"e2e"}' } } }
        $script:gf2Run = $runId
        $script:gf2Call = $f.toolCallId
        Gf-Assert-FrameContract $f
        $form = Gf-Parse-Form $f 'CLARIFY' @('exportScope', 'includeInactive') 'gfSchemaDrift'
        if (-not $form) { return @{ break = $true } }
        $values = New-FormValues $form @{ 'exportScope' = 'ALL' } @('includeInactive')
        $script:gf2Posted = $values
        return @{ payload = @{ result = $values; source = 'e2e-gf' } }
    }
    $attempt = 0
    $turn = $null
    $fe = @()
    $drift = $null
    $driftCount = 0
    $formOk = $false
    while ($attempt -lt 3) {
        $attempt++
        $sessionGf2 = 'gfc-' + [Guid]::NewGuid().ToString('N')
        $script:gfPosts = @()
        $script:gfHttp = @()   # 裁决 #4：HTTP 原文按用例重置，避免 gfc-<case>.http.txt 累积前序用例的请求
        $script:gfSchemaDrift = $null
        $turn = Invoke-FormTurn $sessionGf2 $promptGf2 $ctxGf2 $handlerGf2 4
        $fe = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.name -eq 'generative_form' })
        # schema 漂移（缺 needKeys，模型行为）与"未触发表单"分开：漂移记数并继续重试，3 次皆漂移才 SKIP（§3.3）
        if ($script:gfSchemaDrift) { $drift = $script:gfSchemaDrift; $driftCount++; Start-Sleep -Seconds 2; continue }
        if ($fe.Count -ge 1) { $formOk = $true; break }
        # SKIP 前置校验（§3.3 判定表第 1 行）：本轮已有 tool_start(generative_form) 却无 frontend_tool_request、
        # 也无 REJECTED_ARGUMENTS 结局帧 → 挂起帧未下发属产品缺陷，判 FAIL，不得记 SKIP
        $toolStartN = @($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'generative_form' }).Count
        $rejN = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq 'generative_form' -and $_.status -eq 'REJECTED_ARGUMENTS' }).Count
        if ($toolStartN -gt 0 -and $rejN -lt 1) { throw "模型已 tool_start(generative_form) 但未收到 frontend_tool_request（亦无 REJECTED_ARGUMENTS 结局帧）——挂起帧未下发，产品缺陷" }
        Start-Sleep -Seconds 2
    }
    if (-not $formOk) {
        if ($driftCount -gt 0) { throw "GF-SKIP:模型 $driftCount/3 次尝试 schema 漂移（最近：$drift），判模型行为" }
        throw 'GF-SKIP:模型 3 次尝试均未触发表单（披露正常），判模型行为'
    }

    $post = @($script:gfPosts)[0]
    if (-not $post) { throw '未执行回灌 POST' }
    if ($post.status -ne 200 -or -not $post.json) { throw "回灌 POST => HTTP $($post.status) $($post.text)" }
    if ($post.json.status -ne 'FRONTEND_RESULT' -or $post.json.accepted -ne $true -or $post.json.executed -ne $false) {
        throw "回灌响应异常: $($post.text)"
    }
    $entry = Gf-PendingEntry $script:gf2Run $script:gf2Call
    if ($entry.status -ne 'FRONTEND_RESULT') { throw "pending 条目状态 $($entry.status)，期望 FRONTEND_RESULT" }
    if ($entry.resultText -ne $script:gf2Posted) { throw 'pending resultText 与回灌 JSON 不一致' }
    $ftr = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.toolCallId -eq $script:gf2Call })
    if ($ftr.Count -ne 1 -or $ftr[0].ok -ne $true) { throw "frontend_tool_result 异常（$($ftr.Count) 条 / ok=$($ftr[0].ok)）" }
    if (@($turn.frames | Where-Object { $_.type -eq 'done' }).Count -ne 1) { throw 'done 帧不恰为 1 条' }
    if (@($turn.frames | Where-Object { $_.type -eq 'error' }).Count -gt 0) { throw '出现 error 帧' }
    # A6 值回显（裁决 #1）：权威通道 = 会话记忆的最终助手文本（服务端持久化）。delta 通道降级为纯 INFO。
    $echoGf2 = Gf-FinalAssistantText $sessionGf2
    Gf-Assert-Echo 'GF2' $echoGf2 $script:gf2Posted
    $deltaGf2 = ($turn.frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text }) -join "`n"
    Write-Host ("  [INFO] GF2 delta 弱旁证（不判 FAIL）：{0}" -f $(if ($deltaGf2) { "采到 $($deltaGf2.Length) 字符" } else { '未采到（回灌→重挂间隙，属预期）' })) -ForegroundColor DarkGray
    # A7 fixture 用例内自删
    DeleteJson "/api/tasks/$tGf2" | Out-Null
    Gf-Dump 'GF2' $turn.frames
    Ok 'GF2 CLARIFY 表单：schema 下发→值回灌 200→续跑回显（会话记忆通道，断言对象=实际回灌值）+ fixture 自删'
} catch {
    if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF2' $_.Exception.Message.Substring(8); Gf-Dump 'GF2' $turn.frames }
    else { Gf-Converge $script:gf2Run $script:gf2Call; Gf-Dump 'GF2' $turn.frames; No 'GF2' $_.Exception.Message }
}

# ============================================================
# GF3 生成式表单：取消路径（设计稿 §4 GF3；提示词为设计稿原文）
#   回灌 cancelled:true → FRONTEND_CANCELLED 终态 → 模型收尾；记录 runId/toolCallId 供 GF5
# ============================================================
Say '--- GF3 生成式表单：取消路径 ---'
$script:gf3Run = $null
$script:gf3Call = $null
$script:gf3LastSeq = 0
try {
    $promptGf3 = '请调用 generative_form 工具（scenario=FILTER）出一张表单收集一个筛选条件：keyword（文本，必填）。不要用文字提问，也不要调用其它任何工具。我填完后，把结果按 key=值 回报。'
    $toolsGf3 = GetJson '/api/ai/tools?page=tasks'
    if (@($toolsGf3.toolNames) -notcontains 'generative_form') { throw '披露端点 /api/ai/tools?page=tasks 未列出 generative_form' }

    $handlerGf3 = {
        param($f, $runId)
        if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
        if ($f.name -ne 'generative_form') { return @{ payload = @{ result = '{"ok":true,"source":"e2e"}' } } }
        $script:gf3Run = $runId
        $script:gf3Call = $f.toolCallId
        Gf-Assert-FrameContract $f
        $form = Gf-Parse-Form $f 'FILTER' @('keyword') 'gfSchemaDrift'
        if (-not $form) { return @{ break = $true } }
        return @{ payload = @{ cancelled = $true; source = 'e2e-gf' } }
    }
    $attempt = 0
    $turn = $null
    $fe = @()
    $drift = $null
    $driftCount = 0
    $formOk = $false
    while ($attempt -lt 3) {
        $attempt++
        $sessionGf3 = 'gfc-' + [Guid]::NewGuid().ToString('N')
        $script:gfPosts = @()
        $script:gfHttp = @()   # 裁决 #4：HTTP 原文按用例重置，避免 gfc-<case>.http.txt 累积前序用例的请求
        $script:gfSchemaDrift = $null
        $turn = Invoke-FormTurn $sessionGf3 $promptGf3 @{ page = 'tasks' } $handlerGf3 4
        $fe = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.name -eq 'generative_form' })
        # schema 漂移（缺 needKeys，模型行为）与"未触发表单"分开：漂移记数并继续重试，3 次皆漂移才 SKIP（§3.3）
        if ($script:gfSchemaDrift) { $drift = $script:gfSchemaDrift; $driftCount++; Start-Sleep -Seconds 2; continue }
        if ($fe.Count -ge 1) { $formOk = $true; break }
        # SKIP 前置校验（§3.3 判定表第 1 行）：本轮已有 tool_start(generative_form) 却无 frontend_tool_request、
        # 也无 REJECTED_ARGUMENTS 结局帧 → 挂起帧未下发属产品缺陷，判 FAIL，不得记 SKIP
        $toolStartN = @($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'generative_form' }).Count
        $rejN = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq 'generative_form' -and $_.status -eq 'REJECTED_ARGUMENTS' }).Count
        if ($toolStartN -gt 0 -and $rejN -lt 1) { throw "模型已 tool_start(generative_form) 但未收到 frontend_tool_request（亦无 REJECTED_ARGUMENTS 结局帧）——挂起帧未下发，产品缺陷" }
        Start-Sleep -Seconds 2
    }
    if (-not $formOk) {
        if ($driftCount -gt 0) { throw "GF-SKIP:模型 $driftCount/3 次尝试 schema 漂移（最近：$drift），判模型行为" }
        throw 'GF-SKIP:模型 3 次尝试均未触发表单（披露正常），判模型行为'
    }
    $script:gf3LastSeq = $turn.lastSeq

    # A1 取消 POST：200 + FRONTEND_CANCELLED + cancelled=true + executed=false
    $post = @($script:gfPosts)[0]
    if (-not $post) { throw '未执行取消 POST' }
    if ($post.status -ne 200 -or -not $post.json) { throw "取消 POST => HTTP $($post.status) $($post.text)" }
    if ($post.json.status -ne 'FRONTEND_CANCELLED' -or $post.json.cancelled -ne $true -or $post.json.executed -ne $false) {
        throw "取消响应异常: $($post.text)"
    }
    # A2 结局帧：ok=false + FRONTEND_CANCELLED + result 固定文本前缀
    $ftr = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.toolCallId -eq $script:gf3Call })
    if ($ftr.Count -ne 1 -or $ftr[0].ok -ne $false) { throw "frontend_tool_result 异常（$($ftr.Count) 条 / ok=$($ftr[0].ok)）" }
    if ($ftr[0].status -ne 'FRONTEND_CANCELLED') { throw "结局帧 status=$($ftr[0].status)" }
    if ([string]$ftr[0].result -notlike 'FRONTEND_CANCELLED：*') { throw '结局帧 result 缺 FRONTEND_CANCELLED：前缀' }
    # A3 done 到达且无 error
    if (@($turn.frames | Where-Object { $_.type -eq 'done' }).Count -lt 1) { throw '未收到 done 帧' }
    if (@($turn.frames | Where-Object { $_.type -eq 'error' }).Count -gt 0) { throw '出现 error 帧' }
    # A4 pending 终态：FRONTEND_CANCELLED + reason 含 FRONTEND_DISMISSED + executed=false
    $entry = Gf-PendingEntry $script:gf3Run $script:gf3Call
    if ($entry.status -ne 'FRONTEND_CANCELLED') { throw "pending 条目状态 $($entry.status)，期望 FRONTEND_CANCELLED" }
    if ([string]$entry.reason -notlike '*FRONTEND_DISMISSED*') { throw "pending reason=$($entry.reason)，缺 FRONTEND_DISMISSED" }
    if ($entry.executed -ne $false) { throw 'pending executed 应为 false' }
    # A5 后端日志：取消行（INFO）
    $logGf3 = Get-LogText
    if ($logGf3 -notlike '*前端工具被用户取消*' -or $logGf3 -notlike '*name=generative_form*') {
        throw '日志缺"前端工具被用户取消 … name=generative_form"行'
    }
    # A6 同一 toolCallId 的 frontend_tool_request 不重复下发（恰 1 条）
    $reqN = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.toolCallId -eq $script:gf3Call }).Count
    if ($reqN -ne 1) { throw "同一 toolCallId 的 frontend_tool_request $reqN 条，期望 1" }
    # A7 模型收尾 delta（弱旁证，红队中-6 修复）：delta 仅 live 流，取消→重挂间隙可能丢失，无服务端确定性锚点；
    # 收集到即断言，收集不到不 FAIL（受模型速度影响）
    if (@($turn.frames | Where-Object { $_.type -eq 'delta' }).Count -lt 1) {
        Write-Host '  [INFO] GF3 模型收尾 delta 未收集到（受模型速度影响，弱旁证不 FAIL）' -ForegroundColor Yellow
    }
    Gf-Dump 'GF3' $turn.frames
    Ok 'GF3 取消路径：cancelled:true→FRONTEND_CANCELLED 终态（帧/pending/日志三面一致）+ 模型收尾'
} catch {
    if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF3' $_.Exception.Message.Substring(8); Gf-Dump 'GF3' $turn.frames }
    else { Gf-Converge $script:gf3Run $script:gf3Call; Gf-Dump 'GF3' $turn.frames; No 'GF3' $_.Exception.Message }
}

# ============================================================
# GF4 生成式表单：复核拒绝路径（设计稿 §4 GF4；提示词为设计稿原文）
#   违规值 400 FORM_RESULT_REJECTED（PENDING 保持、不发帧）→ 同 toolCallId 改值重发 200 → 续跑
# ============================================================
Say '--- GF4 生成式表单：复核拒绝（400→改值重发） ---'
$script:gf4Run = $null
$script:gf4Call = $null
$script:gf4LastSeq = 0
try {
    $promptGf4 = '请调用 generative_form 工具（scenario=FILTER）出一张表单收集筛选条件，字段 key 和类型必须一致：keyword（文本，必填）、amount（数字）、scope（下拉单选，选项 XN 和 HD）。不要用文字提问，也不要调用其它任何工具。我填完后，把每个字段按 key=值 原样逐行回报。'
    $toolsGf4 = GetJson '/api/ai/tools?page=tasks'
    if (@($toolsGf4.toolNames) -notcontains 'generative_form') { throw '披露端点 /api/ai/tools?page=tasks 未列出 generative_form' }

    $handlerGf4 = {
        param($f, $runId)
        if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
        if ($f.name -ne 'generative_form') { return @{ payload = @{ result = '{"ok":true,"source":"e2e"}' } } }
        $script:gf4Run = $runId
        $script:gf4Call = $f.toolCallId
        Gf-Assert-FrameContract $f
        $form = Gf-Parse-Form $f 'FILTER' @('keyword', 'amount', 'scope') 'gfSchemaDrift'
        if (-not $form) { return @{ break = $true } }
        return @{ break = $true }
    }
    $attempt = 0
    $turn = $null
    $fe = @()
    $drift = $null
    $driftCount = 0
    $formOk = $false
    while ($attempt -lt 3) {
        $attempt++
        $sessionGf4 = 'gfc-' + [Guid]::NewGuid().ToString('N')
        $script:gfPosts = @()
        $script:gfHttp = @()   # 裁决 #4：HTTP 原文按用例重置，避免 gfc-<case>.http.txt 累积前序用例的请求
        $script:gfSchemaDrift = $null
        $turn = Invoke-FormTurn $sessionGf4 $promptGf4 @{ page = 'tasks' } $handlerGf4 4
        $fe = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.name -eq 'generative_form' })
        # schema 漂移（缺 needKeys，模型行为）与"未触发表单"分开：漂移记数并继续重试，3 次皆漂移才 SKIP（§3.3）
        if ($script:gfSchemaDrift) { $drift = $script:gfSchemaDrift; $driftCount++; Start-Sleep -Seconds 2; continue }
        if ($fe.Count -ge 1) { $formOk = $true; break }
        # SKIP 前置校验（§3.3 判定表第 1 行）：本轮已有 tool_start(generative_form) 却无 frontend_tool_request、
        # 也无 REJECTED_ARGUMENTS 结局帧 → 挂起帧未下发属产品缺陷，判 FAIL，不得记 SKIP
        $toolStartN = @($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'generative_form' }).Count
        $rejN = @($turn.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq 'generative_form' -and $_.status -eq 'REJECTED_ARGUMENTS' }).Count
        if ($toolStartN -gt 0 -and $rejN -lt 1) { throw "模型已 tool_start(generative_form) 但未收到 frontend_tool_request（亦无 REJECTED_ARGUMENTS 结局帧）——挂起帧未下发，产品缺陷" }
        Start-Sleep -Seconds 2
    }
    if (-not $formOk) {
        if ($driftCount -gt 0) { throw "GF-SKIP:模型 $driftCount/3 次尝试 schema 漂移（最近：$drift），判模型行为" }
        throw 'GF-SKIP:模型 3 次尝试均未触发表单（披露正常），判模型行为'
    }
    $lastSeqGf4 = $turn.lastSeq
    $script:gf4LastSeq = $lastSeqGf4

    # A1 违规值 POST：400 + FORM_RESULT_REJECTED + reasons≥3 + status=PENDING + note 挂起仍在等待
    $bad = Expect-Fail 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":123,"amount":"abc","scope":"ZZ","extra":"<x>"}'; source = 'e2e-gf' } '违规回灌未被拒'
    Gf-Http-Note 'A1 违规值回灌（期望 400）' 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":123,"amount":"abc","scope":"ZZ","extra":"<x>"}'; source = 'e2e-gf' } $bad
    if ($bad.status -ne 400 -or -not $bad.json) { throw "违规回灌 => HTTP $($bad.status) $($bad.text)，期望 400" }
    if ($bad.json.code -ne 'FORM_RESULT_REJECTED') { throw "400 code=$($bad.json.code)，期望 FORM_RESULT_REJECTED" }
    if (@($bad.json.reasons).Count -lt 3) { throw "reasons $(@($bad.json.reasons).Count) 条，期望 ≥3（推演 4 条）" }
    if ($bad.json.status -ne 'PENDING') { throw "拒绝响应 status=$($bad.json.status)，期望 PENDING" }
    if ([string]$bad.json.note -notlike '*挂起仍在等待*') { throw "note 缺'挂起仍在等待': $($bad.json.note)" }
    # A2 挂起未被消费：pending 仍 PENDING
    $entryP = Gf-PendingEntry $script:gf4Run $script:gf4Call
    if ($entryP.status -ne 'PENDING') { throw "400 后 pending 状态 $($entryP.status)，期望仍 PENDING" }
    # A3 拒绝路径不发帧不唤醒：5000ms（5s）窗口内重挂 events 无 frontend_tool_result
    # （裁决 #3：该窗口读失败会显式抛出，不再退化为空集让本断言空转）
    $win = Read-Sse-Brief "/api/ai/events/$($script:gf4Run)?lastSeq=$lastSeqGf4" 5000
    if (@($win | Where-Object { $_.type -eq 'frontend_tool_result' }).Count -gt 0) { throw '400 后 5s 窗口内出现 frontend_tool_result（拒绝路径不应发帧）' }
    # A4 同 toolCallId 改值重发：200 + FRONTEND_RESULT + accepted
    $good = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":"E2EGFC","scope":"XN"}'; source = 'e2e-gf' }
    Gf-Http-Note 'A4 同 toolCallId 改值重发（期望 200）' 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":"E2EGFC","scope":"XN"}'; source = 'e2e-gf' } $good
    if ($good.status -ne 200 -or -not $good.json) { throw "改值重发 => HTTP $($good.status) $($good.text)" }
    if ($good.json.status -ne 'FRONTEND_RESULT' -or $good.json.accepted -ne $true) { throw "改值重发响应异常: $($good.text)" }
    # A4b pending resultText 与改值重发 JSON 精确相等（红队中-6 修复的主锚点：服务端已存，确定性；
    # delta 回显仅 live 流、存在竞态，见 A5 弱旁证）
    $entryG4 = Gf-PendingEntry $script:gf4Run $script:gf4Call
    if ($entryG4.resultText -ne '{"keyword":"E2EGFC","scope":"XN"}') { throw 'pending resultText 与改值重发 JSON 不一致（回灌值未原样入上下文）' }
    # A5 续跑：恰 1 条 ok=true 结局帧 + done 到达
    $cont = New-Object System.Collections.ArrayList
    Read-Sse "/api/ai/events/$($script:gf4Run)?lastSeq=$lastSeqGf4" $null @('done', 'error') $cont $null
    # 红队高-3 修复：续跑读到的结局帧/done 帧 seq 回写 gf4LastSeq（记到 done 帧 seq），
    # 使 GF5-A4 用最终 lastSeq 重挂时重放为空，不把 GF4 已见帧误判为新帧
    foreach ($cf in $cont) { if ($cf.seq -and [int]$cf.seq -gt $script:gf4LastSeq) { $script:gf4LastSeq = [int]$cf.seq } }
    $ftr4 = @($cont | Where-Object { $_.type -eq 'frontend_tool_result' })
    if ($ftr4.Count -ne 1 -or $ftr4[0].ok -ne $true) { throw "续跑结局帧异常（$($ftr4.Count) 条，期望恰 1 条 ok=true）" }
    if (@($cont | Where-Object { $_.type -eq 'done' }).Count -lt 1) { throw '续跑未到达 done' }
    if (@($cont | Where-Object { $_.type -eq 'error' }).Count -gt 0) { throw '续跑出现 error 帧' }
    # A5b 值回显（裁决 #1）：权威通道 = 会话记忆的最终助手文本（服务端持久化）。delta 通道降级为纯 INFO。
    # 断言对象 = 本用例 A4 实际改值重发的 JSON（GF4 的表单由脚本显式回灌，不经 New-FormValues）
    $echo4 = Gf-FinalAssistantText $sessionGf4
    Gf-Assert-Echo 'GF4' $echo4 '{"keyword":"E2EGFC","scope":"XN"}'
    $delta4 = ($cont | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text }) -join "`n"
    Write-Host ("  [INFO] GF4 delta 弱旁证（不判 FAIL）：{0}" -f $(if ($delta4) { "采到 $($delta4.Length) 字符" } else { '未采到（回灌→重挂间隙，属预期）' })) -ForegroundColor DarkGray
    # A6 后端日志：安全闸拒绝行（WARN，状态不变挂起继续等待）
    $logGf4 = Get-LogText
    if ($logGf4 -notlike '*前端工具回灌被安全闸拒绝*' -or $logGf4 -notlike '*code=FORM_RESULT_REJECTED*') {
        throw '日志缺"前端工具回灌被安全闸拒绝 … code=FORM_RESULT_REJECTED"行'
    }
    Gf-Dump 'GF4' (@($turn.frames) + @($cont))
    Ok 'GF4 复核拒绝：违规值 400（PENDING 保持/5s 无帧）→同 toolCallId 改值重发 200→续跑回显'
} catch {
    if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF4' $_.Exception.Message.Substring(8); Gf-Dump 'GF4' $turn.frames }
    else { Gf-Converge $script:gf4Run $script:gf4Call; Gf-Dump 'GF4' $turn.frames; No 'GF4' $_.Exception.Message }
}

# ============================================================
# GF5 幂等（409 双向）：纯 HTTP，零模型轮次（设计稿 §4 GF5，裁决 #5）
#   复用 GF3（已取消）/GF4（已回灌）的 runId+toolCallId；前置缺失则 SKIP
# ============================================================
Say '--- GF5 生成式表单：幂等（409 双向） ---'
$script:gfHttp = @()   # 裁决 #4：本用例的 HTTP 原文（纯 HTTP 用例，无模型轮次）
try {
    if (-not $script:gf3Run -or -not $script:gf3Call -or -not $script:gf4Run -or -not $script:gf4Call) {
        throw 'GF-SKIP:GF3/GF4 未记录 runId/toolCallId（前置 SKIP 或失败），幂等断言无对象'
    }
    # A1 值路径：GF4 已决条目重发合规值 → 409 DUPLICATE_TOOL_CALL_ID（回显既有终态，不改写）
    $dup1 = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":"E2EGFC","scope":"XN"}'; source = 'e2e' }
    Gf-Http-Note 'A1 值路径重复回灌（期望 409）' 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf4Run; toolCallId = $script:gf4Call; result = '{"keyword":"E2EGFC","scope":"XN"}'; source = 'e2e' } $dup1
    if ($dup1.status -ne 409 -or -not $dup1.json) { throw "值路径重发 => HTTP $($dup1.status) $($dup1.text)，期望 409" }
    if ($dup1.json.code -ne 'DUPLICATE_TOOL_CALL_ID') { throw "① code=$($dup1.json.code)，期望 DUPLICATE_TOOL_CALL_ID" }
    if ($dup1.json.duplicate -ne $true) { throw '① 响应缺 duplicate=true' }
    if ($dup1.json.status -ne 'FRONTEND_RESULT') { throw "① 回显终态 $($dup1.json.status)，期望 FRONTEND_RESULT（不改写）" }
    # A2 取消路径：GF3 已取消条目再取消 → 409，取消终态不被重复提交改写
    $dup2 = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf3Run; toolCallId = $script:gf3Call; cancelled = $true; source = 'e2e' }
    Gf-Http-Note 'A2 取消路径重复取消（期望 409）' 'POST' '/api/ai/frontend-tool-result' @{ runId = $script:gf3Run; toolCallId = $script:gf3Call; cancelled = $true; source = 'e2e' } $dup2
    if ($dup2.status -ne 409 -or -not $dup2.json) { throw "取消路径重发 => HTTP $($dup2.status) $($dup2.text)，期望 409" }
    if ($dup2.json.code -ne 'DUPLICATE_TOOL_CALL_ID') { throw "② code=$($dup2.json.code)，期望 DUPLICATE_TOOL_CALL_ID" }
    if ($dup2.json.status -ne 'FRONTEND_CANCELLED') { throw "② 回显终态 $($dup2.json.status)，期望 FRONTEND_CANCELLED（不被改写）" }
    # A3 两次 409 后 pending 条目状态不变（仍是各自终态）
    $e4 = Gf-PendingEntry $script:gf4Run $script:gf4Call
    if ($e4.status -ne 'FRONTEND_RESULT') { throw "GF4 条目被 409 改写: $($e4.status)" }
    $e3 = Gf-PendingEntry $script:gf3Run $script:gf3Call
    if ($e3.status -ne 'FRONTEND_CANCELLED') { throw "GF3 条目被 409 改写: $($e3.status)" }
    # A4 两次 409 后 events 重挂无新帧（心跳帧除外）
    # （裁决 #3：窗口读失败会显式抛出，不再退化为空集让本断言空转）
    foreach ($pair in @(@($script:gf4Run, $script:gf4LastSeq), @($script:gf3Run, $script:gf3LastSeq))) {
        $w = Read-Sse-Brief "/api/ai/events/$($pair[0])?lastSeq=$($pair[1])" 5000
        $badFrames = @($w | Where-Object { @('frontend_tool_request', 'frontend_tool_result', 'done', 'error') -contains $_.type })
        if ($badFrames.Count -gt 0) { throw "409 后重挂 $($pair[0]) 出现新帧: $($badFrames[0].type)" }
    }
    Gf-Dump 'GF5' $null
    Ok 'GF5 幂等 409 双向：值路径/取消路径重复回灌均 409 DUPLICATE（终态不改写、无新帧）'
} catch {
    if ($_.Exception.Message -like 'GF-SKIP:*') { Gf-Skip 'GF5' $_.Exception.Message.Substring(8); Gf-Dump 'GF5' $null }
    else { Gf-Dump 'GF5' $null; No 'GF5' $_.Exception.Message }
}

# ============================================================
# GF6（-EnableGf6）入参闸门（设计稿 §4 GF6；M2-T4 T4-4 裁决 4）
#   诱导非法 schema → 不挂起 + REJECTED_ARGUMENTS 结局帧。
#   桩模式启用并**计入主计数**（底座 = 桩的确定性违规模式 --violate-form-type：请求级，
#   只在 GF6 提示词上触发，GF1/GF3/GF4 不受影响）；真模型模式默认关（服从率不可控）。
# ============================================================
if ($EnableGf6) {
    Say '--- GF6 生成式表单：入参闸门（主用例，计入主计数） ---'
    $gf6Rejected = $false
    $gf6Attempt = 0
    $gf6Frames = $null
    while ($gf6Attempt -lt 3 -and -not $gf6Rejected) {
        $gf6Attempt++
        $sessionGf6 = 'gfc-' + [Guid]::NewGuid().ToString('N')
        $script:gfPosts = @()
        $script:gfHttp = @()
        $handlerGf6 = {
            param($f, $runId)
            if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
            if ($f.name -eq 'generative_form') {
                # 模型未按指示给非法 schema 反而下发了合法表单：收敛挂起后视为未触发
                return @{ payload = @{ cancelled = $true; source = 'e2e' } }
            }
            return @{ payload = @{ result = '{"ok":true,"source":"e2e"}' } }
        }
        # 独立提示词（红队低-10 修复）：不再引用 GF1 try 块内的 $promptGf1，消除跨用例隐式耦合；
        # 末句"type 故意写成 html"同时是桩侧请求级违规注入的开关（--violate-form-type）
        $promptGf6 = '请调用 generative_form 工具（scenario=FILTER）出一张收集导出筛选条件的表单，字段就用这四个，key 和类型必须一致：keyword（文本，必填，占位提示"编码或名称关键字"）、minRows（数字，非必填）、effectiveDate（日期，格式 yyyy-MM-dd，非必填）、scope（下拉单选，选项 XN 和 HD）。不要用文字向我提问，也不要调用 generative_form 以外的任何工具，不会出现确认卡片。为了演示安全闸，请把其中一个字段的 type 故意写成 html。'
        $turn6 = Invoke-FormTurn $sessionGf6 $promptGf6 @{ page = 'tasks' } $handlerGf6 4
        $gf6Frames = $turn6.frames
        $gf6Req = @($turn6.frames | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.name -eq 'generative_form' })
        $gf6Rej = @($turn6.frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.name -eq 'generative_form' -and $_.ok -eq $false -and $_.status -eq 'REJECTED_ARGUMENTS' })
        if ($gf6Req.Count -eq 0 -and $gf6Rej.Count -ge 1) { $gf6Rejected = $true }
        Start-Sleep -Seconds 2
    }
    Gf-Dump 'GF6' $gf6Frames
    if ($gf6Rejected) {
        $logGf6 = Get-LogText
        if ($logGf6 -like '*前端工具入参被安全闸拒绝*') {
            Ok 'GF6 入参闸门：非法 schema 不挂起 + REJECTED_ARGUMENTS 结局帧 + 闸门日志'
        } else {
            No 'GF6 入参闸门' '日志缺"前端工具入参被安全闸拒绝"行'
        }
    } elseif ($env:E2E_STUB_MODE -eq '1') {
        # 桩是确定性替身：非法 schema 由 --violate-form-type 构造保证 ⇒ 未产出即桩/产品链路异常
        No 'GF6 入参闸门' '桩模式 3 次均未产出非法 schema（应由 --violate-form-type 构造保证，属桩路由或产品链路异常）'
    } else {
        Gf-Skip 'GF6' '模型 3 次均未产出非法 schema（真模型服从率不可控，观察项；桩模式下该分支不可达）'
    }
}

# ============================================================
# TC17 HITL：破坏性操作需确认（拒绝不执行、确认后执行）
#   源断言：confirm_tool → 拒绝后未发布、同意后 PUBLISHED
#   main 适配：确认事件为 confirm_request，回执帧为 confirm_decision；
#   决策经 POST /api/ai/confirm{runId,toolCallId,approved} 提交；
#   重复提交同一 toolCallId → 409 DUPLICATE_TOOL_CALL_ID
# ============================================================
Say '--- TC17 HITL：发布需确认（拒绝/确认） ---'
try {
    # 准备一个"已导入待发布"的导入任务
    $script:t17 = New-ImportTask 'S5B-TC17-HITL' @('DOC_TYPE')
    Upload-File $script:t17 (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_ok.xlsx' | Out-Null
    $j17pre = Start-JobOf $script:t17 'PRECHECK'
    if ((Wait-Job $j17pre.id).status -ne 'COMPLETED') { throw 'TC17 前置预检查未通过' }
    $j17imp = Start-JobOf $script:t17 'IMPORT'
    if ((Wait-Job $j17imp.id).status -ne 'COMPLETED') { throw 'TC17 前置导入未通过' }
    PutJson "/api/tasks/$($script:t17)/step" @{ step = 'PUBLISH' } | Out-Null

    $ctx17 = @{ page = 'import'; taskId = $script:t17; taskType = 'IMPORT'; step = 'PUBLISH' }
    $ask = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $script:t17)
    $session17 = 's5b-' + [Guid]::NewGuid().ToString('N')

    # ① 拒绝：不发布
    $before17 = @{}
    foreach ($r in (GetJson '/api/data/DOC_TYPE?page=0&size=50').content) { $before17[$r.rowKey] = $r.version }
    $stagedBefore = @(GetJson "/api/jobs/$($j17imp.id)/diff").Count
    if ($stagedBefore -lt 1) { throw '前置导入未产生暂存行' }
    $script:rejectToolCallId = $null
    $script:rejectRunId = $null
    # 注意：模型可能先挂起在别的工具上（如 FRONTEND 通道的 navigate_to/confirm_step —— 它同样以
    # frontend_tool_request 挂起），故处理器必须"回灌并继续"，只在 start_publish 的确认门处下决策；
    # 否则会在别的挂起点提前收摊，误判为"未收到确认事件"（实测抖动来源）。
    $script:tc17Approve = $false
    $handler17 = {
        param($f)
        if ($f.type -eq 'confirm_request') {
            if ($f.name -eq 'start_publish') {
                $script:rejectToolCallId = $f.toolCallId
                $script:rejectRunId = $f.runId
                return @{ approved = [bool]$script:tc17Approve }
            }
            return @{ approved = $false }
        }
        return @{ result = '{"ok":true,"source":"e2e"}' }
    }
    $rej = Invoke-AiTurn $session17 $ask $ctx17 $handler17 6
    if (-not $script:rejectToolCallId) { throw '拒绝路径未收到 start_publish 确认事件' }
    # 重复提交同一决策 → 409 DUPLICATE_TOOL_CALL_ID
    $dup = Expect-Fail 'POST' '/api/ai/confirm' @{ runId = $script:rejectRunId; toolCallId = $script:rejectToolCallId; approved = $false } '重复决策未被拒'
    if ($dup.status -ne 409 -or $dup.json.code -ne 'DUPLICATE_TOOL_CALL_ID') {
        throw "重复决策响应 HTTP $($dup.status)/code=$($dup.json.code)，期望 409/DUPLICATE_TOOL_CALL_ID"
    }
    # 取消该轮，避免模型在同一轮内重试发布（取消为 main 权威语义：Redis 标志）
    $cancel = PostJsonRaw "/api/ai/cancel/$($script:rejectRunId)" $null
    if (-not $cancel.cancelled) { throw '取消未生效' }
    Start-Sleep -Seconds 3
    $jobsAfterReject = @(GetJson "/api/tasks/$($script:t17)/jobs")
    if (@($jobsAfterReject | Where-Object { $_.jobType -eq 'PUBLISH' }).Count -gt 0) { throw '拒绝后仍创建了发布作业' }
    # 不执行的证据面：暂存行仍是 STAGED、生效行版本零变化
    $stagedAfter = @(GetJson "/api/jobs/$($j17imp.id)/diff")
    if (@($stagedAfter | Where-Object { $_.status -ne 'STAGED' }).Count -gt 0) { throw '拒绝后暂存行状态被推进（应仍为 STAGED）' }
    foreach ($r in (GetJson '/api/data/DOC_TYPE?page=0&size=50').content) {
        if ($r.version -ne $before17[$r.rowKey]) { throw "拒绝后 $($r.rowKey) 版本变化（发布被误执行）" }
    }

    # ② 确认：真的发布（作业由后端服务层创建）。换新会话（避免上一轮拒绝的对话记忆影响模型），
    #    并对"模型未调用工具"的抖动做最多 3 次尝试
    Start-Sleep -Seconds 2
    $session17b = 's5b-' + [Guid]::NewGuid().ToString('N')
    $script:tc17Approve = $true
    $appr = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $script:rejectToolCallId = $null
        $appr = Invoke-AiTurn $session17b $ask $ctx17 $handler17 6
        if ($script:rejectToolCallId) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $script:rejectToolCallId) { throw '确认路径未收到 start_publish 确认事件（3 次尝试均未调用工具）' }
    $pubJob = Wait-Until {
        $js = @(GetJson "/api/tasks/$($script:t17)/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
        if ($js.Count -gt 0) { return $js[0] } else { return $null }
    } 60 '确认后未创建发布作业'
    $pubFinal = Wait-Job $pubJob.id
    if ($pubFinal.status -ne 'COMPLETED') { throw "发布作业终态 $($pubFinal.status)" }
    $docCount = (GetJson '/api/data/DOC_TYPE/count').count
    if ($docCount -ne 5) { throw "发布后 DOC_TYPE 行数 $docCount，期望 5" }
    $stagedPub = @(GetJson "/api/jobs/$($j17imp.id)/diff")
    if (@($stagedPub | Where-Object { $_.status -ne 'PUBLISHED' }).Count -gt 0) { throw '发布后暂存行未提升为 PUBLISHED' }
    Ok 'TC17 拒绝不执行（暂存仍 STAGED、版本零变化、409 重复决策拦截）+ 确认后真实发布（PUBLISH COMPLETED）'
} catch { No 'TC17' $_.Exception.Message }

# ============================================================
# TC18 会话隔离 / 串行化（409）/ 取消后释放
#   源断言：会话计数增减、清空
#   main 适配：无 /sessions/count 与 /clear；改为 main 的会话语义：
#   ① 不同 sessionId 的记忆窗口互相隔离（/api/ai/history/{sessionId}）；
#   ② 同 sessionId 并发第二轮 → 409 SESSION_BUSY 且携进行中 runId 与 reattach 端点（ADR-5/Q11）；
#   ③ 取消该轮后同会话可再次发起。
#   抗抖动（M1 收尾项⑦）：
#     - fixture 改用"从未发布过的导入任务"（原先复用 TC17 的任务，而它已被发布，
#       真实模型可能据此拒绝再次调用 start_publish，导致挂不到确认门）；
#     - 并对"模型未调用工具"的抖动做一次换会话重试。
# ============================================================
Say '--- TC18 会话隔离 / 409 串行化 / 取消释放 ---'
try {
    $sa = 's5b-' + [Guid]::NewGuid().ToString('N')
    $sb = 's5b-' + [Guid]::NewGuid().ToString('N')
    $tagA = 'ABC' + (Get-Random -Minimum 100000 -Maximum 999999)
    $tagB = 'XYZ' + (Get-Random -Minimum 100000 -Maximum 999999)
    Invoke-AiTurn $sa ("你好，我的会话标识是 $tagA") @{ page = 'tasks' } $script:continueHandler 3 | Out-Null
    Invoke-AiTurn $sb ("你好，我的会话标识是 $tagB") @{ page = 'tasks' } $script:continueHandler 3 | Out-Null
    $histA = GetJson "/api/ai/history/$sa"
    $histB = GetJson "/api/ai/history/$sb"
    $textA = ($histA.messages | ForEach-Object { $_.text }) -join ' '
    $textB = ($histB.messages | ForEach-Object { $_.text }) -join ' '
    if ($histA.count -lt 2 -or $histB.count -lt 2) { throw "会话记忆过短 A=$($histA.count) B=$($histB.count)" }
    if ($textA -notlike "*$tagA*") { throw '会话 A 记忆缺少自身内容' }
    if ($textB -notlike "*$tagB*") { throw '会话 B 记忆缺少自身内容' }
    if ($textA -like "*$tagB*" -or $textB -like "*$tagA*") { throw '两会话记忆互相污染' }

    # ② 串行化：会话 C 挂起在确认门时，第二轮请求 → 409
    #    fixture：全新导入任务（预检查通过 + 已导入待发布，从未发布过）
    $t18 = New-ImportTask 'S5B-TC18-串行化' @('DOC_TYPE')
    Upload-File $t18 (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_ok.xlsx' | Out-Null
    $j18pre = Start-JobOf $t18 'PRECHECK'
    if ((Wait-Job $j18pre.id).status -ne 'COMPLETED') { throw 'TC18 前置预检查未通过' }
    $j18imp = Start-JobOf $t18 'IMPORT'
    if ((Wait-Job $j18imp.id).status -ne 'COMPLETED') { throw 'TC18 前置导入未通过' }
    PutJson "/api/tasks/$t18/step" @{ step = 'PUBLISH' } | Out-Null
    if (@(GetJson "/api/tasks/$t18/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' }).Count -gt 0) {
        throw 'TC18 fixture 不应已有发布作业（否则模型可能拒绝再发布）'
    }

    $ctx18 = @{ page = 'import'; taskId = $t18; taskType = 'IMPORT'; step = 'PUBLISH' }
    $ask18 = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t18)
    $runId18 = $null
    $sc = $null
    for ($attempt = 1; $attempt -le 2 -and -not $runId18; $attempt++) {
        $sc = 's5b-' + [Guid]::NewGuid().ToString('N')
        $frames18 = New-Object System.Collections.ArrayList
        Read-Sse '/api/ai/chat' @{ sessionId = $sc; message = $ask18; context = $ctx18 } `
            @('confirm_request') $frames18 $null
        $cr = @($frames18 | Where-Object { $_.type -eq 'confirm_request' })
        if ($cr.Count -ge 1) {
            $runId18 = $cr[0].runId
        } else {
            Start-Sleep -Seconds 2
        }
    }
    if (-not $runId18) { throw '未取得挂起轮的 runId（2 次尝试均未挂起到确认门）' }
    $busy = Expect-Fail 'POST' '/api/ai/chat' @{ sessionId = $sc; message = '在吗'; context = @{ page = 'tasks' } } '同会话并发第二轮未被拒'
    if ($busy.status -ne 409 -or $busy.json.code -ne 'SESSION_BUSY') { throw "并发响应 HTTP $($busy.status)/code=$($busy.json.code)，期望 409/SESSION_BUSY" }
    if ($busy.json.runId -ne $runId18) { throw "409 未携带进行中 runId（$($busy.json.runId) ≠ $runId18）" }
    if ($busy.json.reattach -ne "/api/ai/events/$runId18") { throw "409 的 reattach 端点异常: $($busy.json.reattach)" }

    # ③ 取消 → 会话释放后同会话可再次发起
    $c18 = PostJsonRaw "/api/ai/cancel/$runId18" $null
    if (-not $c18.cancelled) { throw '取消未生效' }
    $released = $false
    for ($i = 0; $i -lt 20 -and -not $released; $i++) {
        Start-Sleep -Seconds 1
        $r = Invoke-Api 'POST' '/api/ai/chat' @{ sessionId = $sc; message = '你好'; context = @{ page = 'tasks' } }
        if ($r.ok) { $released = $true } elseif ($r.status -ne 409) { throw "取消后重试返回 HTTP $($r.status) $($r.text)" }
    }
    if (-not $released) { throw '取消后 20s 内同会话仍被拒' }
    Ok 'TC18 会话记忆隔离 + 409 SESSION_BUSY(携 runId/reattach) + 取消后释放'
} catch { No 'TC18' $_.Exception.Message }

# ============================================================
# TC22 AI 会话历史恢复
#   源断言：user/assistant 计数、工具卡片、未知会话返回空
#   main 适配：GET /api/ai/history/{sessionId} → {sessionId,count,messages[{type,text,toolCalls?,toolResponses?}]}
# ============================================================
Say '--- TC22 AI 历史恢复 ---'
try {
    $hist = GetJson "/api/ai/history/$($script:aiSession15)"
    if ($hist.count -lt 4) { throw "历史条数 $($hist.count)" }
    $users = @($hist.messages | Where-Object { $_.type -eq 'USER' })
    $assist = @($hist.messages | Where-Object { $_.type -eq 'ASSISTANT' })
    if ($users.Count -lt 2) { throw "用户消息数 $($users.Count)" }
    if ($assist.Count -lt 1) { throw '无助手消息' }
    $hasToolCard = $false
    foreach ($a in $assist) { if (@($a.toolCalls).Count -gt 0) { $hasToolCard = $true } }
    if (-not $hasToolCard) { throw '历史中无工具卡片（toolCalls）' }
    $unknown = GetJson '/api/ai/history/no-such-session-s5b'
    if ($unknown.count -ne 0 -or @($unknown.messages).Count -ne 0) { throw '未知会话应返回空列表' }
    Ok ("TC22 历史恢复：{0} 条（USER {1} / ASSISTANT {2}，含工具卡片）+ 未知会话返回空" -f $hist.count, $users.Count, $assist.Count)
} catch { No 'TC22' $_.Exception.Message }

# ============================================================
# TC23 CONFIRM 卡快照重建（T3-6b，T3b 收尾用例）
#   流程：真挂起（start_publish 落确认门）→ 断开流（等价"整页刷新"）→
#        GET /api/ai/runs/current 拿到重建确认卡所需的全部契约事实 →
#        差量重挂（lastSeq=archiveMaxSeq，孤儿过滤不重发旧帧）→ 用帧里的真实 toolCallId confirm → 收敛
#   前置约束（设计卡 §T3-6b 原文）：手工 POST /confirm 造不出 PENDING（409 UNKNOWN_TOOL_CALL）；
#        同 session 二轮 → 409 SESSION_BUSY（故每轮独立 sessionId）；测后 POST /api/ai/cancel/{runId} 清理。
#   口径收窄（A25，已在 T3b 施工报告登记）：本脚本没有浏览器自动化载体，
#        "整页刷新后的前端断言"降级为接口级断言（runs/current 契约 + 差量续收的孤儿过滤）
#        + 前端 store#restoreSuspendedSnapshot 的逻辑走查；不驱动浏览器。
# ============================================================
Say '--- TC23 CONFIRM 卡快照重建（挂起态外置 + 整页刷新重建 + 收敛） ---'
try {
    # ① 前置：一个"已导入待发布"的导入任务（与 TC17 同构，保证 confirm 后发布真能跑成）
    $t23 = New-ImportTask 'S5B-TC23-快照重建' @('DOC_TYPE')
    Upload-File $t23 (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_ok.xlsx' | Out-Null
    $j23pre = Start-JobOf $t23 'PRECHECK'
    if ((Wait-Job $j23pre.id).status -ne 'COMPLETED') { throw 'TC23 前置预检查未通过' }
    $j23imp = Start-JobOf $t23 'IMPORT'
    if ((Wait-Job $j23imp.id).status -ne 'COMPLETED') { throw 'TC23 前置导入未通过' }
    PutJson "/api/tasks/$t23/step" @{ step = 'PUBLISH' } | Out-Null

    # ② 真挂起：桩上游按 TC17-PUBLISH 路由下发 start_publish 工具轮 → 产品落确认门
    $ctx23 = @{ page = 'import'; taskId = $t23; taskType = 'IMPORT'; step = 'PUBLISH' }
    $ask23 = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t23)
    $run23 = $null
    $call23 = $null
    $frames23 = New-Object System.Collections.ArrayList
    $script:gfHttp = @()   # TC23 的 HTTP 原文按用例重置（Gf-Dump 落 gfc-TC23.http.txt）
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $session23 = 's5b-tc23-' + [Guid]::NewGuid().ToString('N')
        $batch23 = New-Object System.Collections.ArrayList
        Read-Sse '/api/ai/chat' @{ sessionId = $session23; message = $ask23; context = $ctx23 } `
            @('confirm_request', 'done', 'error') $batch23 $null
        foreach ($f in $batch23) { $frames23.Add($f) | Out-Null }
        $cr = @($batch23 | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_publish' })
        if ($cr.Count -ge 1) {
            $run23 = $cr[$cr.Count - 1].runId
            $call23 = $cr[$cr.Count - 1].toolCallId
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $call23) { throw 'TC23 未收到 start_publish 确认帧（3 次尝试均未调用工具）' }
    if (-not $run23) { throw 'TC23 确认帧缺 runId' }
    # 此刻客户端已断开流（Read-Sse 在 confirm_request 处早退并 Dispose）——即"整页刷新"的形态

    # ③ 整页刷新重建：runs/current 的四项契约事实（found / awaitingExternal / PENDING 条目 / archiveMaxSeq）
    $curRaw = Invoke-Api 'GET' "/api/ai/runs/current?sessionId=$session23" $null
    Gf-Http-Note '整页刷新：挂起轮快照（重建确认卡的数据源）' 'GET' "/api/ai/runs/current?sessionId=$session23" $null $curRaw
    if (-not $curRaw.ok -or -not $curRaw.json -or -not $curRaw.json.success) { throw "TC23 GET runs/current => HTTP $($curRaw.status) $($curRaw.text)" }
    $cur23 = $curRaw.json.data
    if (-not $cur23.found) { throw 'TC23 runs/current 未 found（挂起态未外置？）' }
    if ($cur23.runId -ne $run23) { throw "TC23 runs/current runId=$($cur23.runId)，帧内 runId=$run23" }
    if ($cur23.snapshotStatus -ne 'SUSPENDED') { throw "TC23 snapshotStatus=$($cur23.snapshotStatus)，期望 SUSPENDED" }
    if ($cur23.awaitingExternal -ne $true) { throw 'TC23 awaitingExternal 非 true' }
    $entry23 = @($cur23.pendingEntries | Where-Object { $_.toolCallId -eq $call23 })[0]
    if (-not $entry23) { throw 'TC23 pendingEntries 缺该 toolCallId（确认卡重建无对象）' }
    if ($entry23.entryStatus -ne 'PENDING') { throw "TC23 条目状态 $($entry23.entryStatus)，期望 PENDING" }
    if ($entry23.kind -ne 'CONFIRM') { throw "TC23 条目 kind=$($entry23.kind)，期望 CONFIRM" }
    if ([int]$cur23.archiveMaxSeq -lt 1) { throw "TC23 archiveMaxSeq=$($cur23.archiveMaxSeq)，期望 > 0" }

    # ④ 差量续收的孤儿过滤（前端 resumeSnapshotStream 的接口级等价）：lastSeq=archiveMaxSeq 时，
    #    重挂只收"新帧"，旧业务帧一律不重发（seq ≤ lastSeq 即孤儿）
    $brief23 = Read-Sse-Brief "/api/ai/events/$run23`?lastSeq=$([int]$cur23.archiveMaxSeq)" 5000
    $orphans23 = @($brief23 | Where-Object { $null -ne $_.seq -and [int]$_.seq -le [int]$cur23.archiveMaxSeq })
    if ($orphans23.Count -gt 0) { throw "TC23 差量续收重发了 $($orphans23.Count) 个旧帧（孤儿过滤失效）" }

    # ⑤ 用帧里的真实 toolCallId 提交决策（手工构造的 id 只会 409 UNKNOWN_TOOL_CALL）
    $confBody = @{ runId = $run23; toolCallId = $call23; approved = $true; reason = 'e2e-tc23' }
    $confRaw = Invoke-Api 'POST' '/api/ai/confirm' $confBody
    Gf-Http-Note 'confirm（帧里的真实 toolCallId；响应含 A10 新增只读字段）' 'POST' '/api/ai/confirm' $confBody $confRaw
    if (-not $confRaw.ok) { throw "TC23 POST /api/ai/confirm => HTTP $($confRaw.status) $($confRaw.text)" }
    $conf23 = $confRaw.json
    if ($conf23.accepted -ne $true) { throw "TC23 confirm 未 accepted：$($conf23 | ConvertTo-Json -Compress -Depth 5)" }
    if ($null -eq $conf23.resumeTriggered) { throw 'TC23 confirm 响应缺 resumeTriggered（A10 契约字段）' }
    if ([string]::IsNullOrWhiteSpace([string]$conf23.resumeOutcome)) { throw 'TC23 confirm 响应缺 resumeOutcome（A10 契约字段）' }
    if ($conf23.resumeTriggered -ne $false -or $conf23.resumeOutcome -ne 'WOKE_IN_PROCESS_GATE') {
        throw "TC23 进程存活时应 woke=true 且不触发死卡兜底续跑，实际 resumeTriggered=$($conf23.resumeTriggered)/$($conf23.resumeOutcome)"
    }
    if ($conf23.wokeInProcessGate -ne $true) { throw 'TC23 确认未唤醒同进程等待方' }

    # ⑥ 收敛：重挂回放整轮状态帧（confirm_decision → tool_result → done），台账里该条目已执行
    $after23 = New-Object System.Collections.ArrayList
    Read-Sse "/api/ai/events/$run23" $null @('done', 'error') $after23 $null
    $dec23 = @($after23 | Where-Object { $_.type -eq 'confirm_decision' -and $_.toolCallId -eq $call23 })
    if ($dec23.Count -lt 1) { throw 'TC23 重挂回放里没有 confirm_decision 帧' }
    $tr23 = @($after23 | Where-Object { $_.type -eq 'tool_result' -and $_.toolCallId -eq $call23 })
    if ($tr23.Count -lt 1) { throw 'TC23 重挂回放里没有 tool_result 帧（未收敛）' }
    # 条目状态口径（实读）：CONFIRM 类工具执行后仍保持 APPROVED（只有 BACKEND 类会转 EXECUTED），
    # 故判据是"已执行"这一事实（executed=true）+ executedBy 落地，而不是状态字面量
    if ($tr23[$tr23.Count - 1].executed -ne $true) {
        throw "TC23 条目未执行：status=$($tr23[$tr23.Count - 1].status)/executed=$($tr23[$tr23.Count - 1].executed)"
    }
    if (-not $tr23[$tr23.Count - 1].executedBy) { throw 'TC23 执行者（executedBy）缺失' }
    $done23 = @($after23 | Where-Object { $_.type -eq 'done' })
    if ($done23.Count -ne 1) { throw "TC23 done 帧数 $($done23.Count)，期望 1" }
    if (@($after23 | Where-Object { $_.type -eq 'error' }).Count -gt 0) { throw 'TC23 出现 error 终帧' }

    # 台账面（跨进程事实源）：该 toolCallId 已有执行记录
    $desc23 = GetJson "/api/ai/runs/$run23"
    $ledger23 = @($desc23.ledger | Where-Object { $_.toolCallId -eq $call23 })
    if ($ledger23.Count -lt 1) { throw 'TC23 台账里没有该 toolCallId 的执行记录' }

    # 发布真的跑成了（第 4 方案"收敛为真实续跑"的最强证据）
    $pub23 = Wait-Until {
        $js = @(GetJson "/api/tasks/$t23/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
        if ($js.Count -gt 0) { return $js[0] } else { return $null }
    } 60 'TC23 确认后未创建发布作业'
    $pubFinal23 = Wait-Job $pub23.id
    if ($pubFinal23.status -ne 'COMPLETED') { throw "TC23 发布作业终态 $($pubFinal23.status)" }
    $staged23 = @(GetJson "/api/jobs/$($j23imp.id)/diff")
    if (@($staged23 | Where-Object { $_.status -ne 'PUBLISHED' }).Count -gt 0) { throw 'TC23 发布后暂存行未提升为 PUBLISHED' }

    Gf-Dump 'TC23' (@($frames23) + @($brief23) + @($after23))
    Ok 'TC23 CONFIRM 卡快照重建：真挂起→runs/current 重建（found/awaitingExternal/PENDING/archiveMaxSeq）→差量续收孤儿过滤→真实 toolCallId confirm（resumeTriggered/Outcome 契约）→confirm_decision+tool_result(executed=true)+done 收敛 + 发布 COMPLETED'
} catch {
    Gf-Dump 'TC23' (@($frames23) + @($after23))
    No 'TC23' $_.Exception.Message
} finally {
    # 设计卡前置约束：测后清理该轮（取消为 main 权威语义；终态轮取消同样返回 200）
    if ($run23) { try { PostJsonRaw "/api/ai/cancel/$run23" $null | Out-Null } catch { } }
}

# ============================================================
# TC19 清理与演示数据恢复（含 Q8② 运行期日志收尾扫描）
#   源断言：删除 E2E_TEMP、演示数据恢复为 5 行
#   main 适配：任务删除为级联清理（作业/条目/问题/暂存/文件），
#   演示数据恢复仍走"导入全量导出件 + REPLACE 发布"闭环
# ============================================================
Say '--- TC19 清理与演示数据恢复 ---'
try {
    DeleteJson '/api/definitions/E2E_TEMP' | Out-Null
    $gone = Invoke-Api 'GET' '/api/definitions/E2E_TEMP' $null
    if ($gone.ok) { throw 'E2E_TEMP 未删除' }
    if ($gone.status -ne 404) { throw "删除后回读 HTTP $($gone.status)，期望 404" }

    $page = GetJson '/api/tasks?page=0&size=500'
    $mine = @($page.content | Where-Object { $_.task.title -like 'S5B-*' })
    $jobToCheck = $script:tc3JobId
    foreach ($row in $mine) { DeleteJson ("/api/tasks/" + $row.task.id) | Out-Null }
    $left = @((GetJson '/api/tasks?page=0&size=500').content | Where-Object { $_.task.title -like 'S5B-*' })
    if ($left.Count -gt 0) { throw "仍有 $($left.Count) 个 S5B-* 任务未删除" }
    $afterDel = Invoke-Api 'GET' "/api/tasks/$($script:t20)" $null
    if ($afterDel.ok -or $afterDel.status -ne 404) { throw "已删除任务回读 HTTP $($afterDel.status)" }
    $jobGone = Invoke-Api 'GET' "/api/jobs/$jobToCheck" $null
    if ($jobGone.ok -or $jobGone.status -ne 404) { throw "任务删除未级联清理作业（HTTP $($jobGone.status)）" }

    # 演示数据恢复：CURRENCY 回到 5 行
    $restore = New-ImportTask 'S5B-恢复-CURRENCY' @('CURRENCY') 'REPLACE'
    Upload-File $restore (Join-Path $tmp 'CURRENCY_full.xlsx') 'CURRENCY_restore.xlsx' | Out-Null
    $jr1 = Start-JobOf $restore 'PRECHECK'
    if ((Wait-Job $jr1.id).status -ne 'COMPLETED') { throw '恢复件预检查未通过' }
    $jr2 = Start-JobOf $restore 'IMPORT'
    if ((Wait-Job $jr2.id).status -ne 'COMPLETED') { throw '恢复件导入未通过' }
    $jr3 = Start-JobOf $restore 'PUBLISH'
    if ((Wait-Job $jr3.id).status -ne 'COMPLETED') { throw '恢复发布未通过' }
    $cur = (GetJson '/api/data/CURRENCY/count').count
    if ($cur -ne 5) { throw "恢复后 CURRENCY 行数 $cur，期望 5" }
    DeleteJson "/api/tasks/$restore" | Out-Null
    Ok 'TC19 清理完成（定义删除 404、任务删除级联作业 404）+ 演示数据恢复 5 行'
} catch { No 'TC19' $_.Exception.Message }

# ============================================================
# Q8 项回归（移植期缺陷清单的日志/明细类断言；不计入主用例计数，单独计数）
#   Q8① 未上传文件的导入批次写 issues 明细抛 NoSuchFileException → main 明细仅落库，不应出现
#   Q8② GlobalExceptionHandler 对 SSE 请求二次写 JSON（日志噪音）→ 修复后可断言日志无此噪音
# ============================================================
Say '--- Q8 项回归（移植期缺陷清单） ---'
$script:q8pass = 0
$script:q8fail = 0
function Q8-Ok($n) { $script:q8pass++; Write-Host ("  [PASS] " + $n) -ForegroundColor Green }
function Q8-No($n, $why) { $script:q8fail++; Write-Host ("  [FAIL] " + $n + " => " + $why) -ForegroundColor Red }
try {
    $log = Get-LogText
    if ($log -like '*NoSuchFileException*' -or $log -like '*明细写入失败*') {
        Q8-No 'Q8① 明细落库（无写盘异常）' '日志出现 NoSuchFileException/明细写入失败'
    } else {
        # 同一事实的行为面旁证：未上传文件的配置项明细可经 REST 查询
        $bad = New-ImportTask 'S5B-Q8-未上传' @('PROJECT_ENV')
        $bj = Start-JobOf $bad 'PRECHECK'
        $bs = Wait-Job $bj.id
        $bi = GetJson "/api/jobs/$($bj.id)/issues?page=0&size=50"
        DeleteJson "/api/tasks/$bad" | Out-Null
        if ($bs.status -eq 'FAILED' -and @($bi.content).Count -ge 1) {
            Q8-Ok 'Q8① 明细落库（未上传文件批次的明细可查，日志无写盘异常）'
        } else {
            Q8-No 'Q8① 明细落库' "状态 $($bs.status)，明细 $(@($bi.content).Count) 条"
        }
    }
    $convCount = ([regex]::Matches($log, 'No converter for')).Count
    $writableCount = ([regex]::Matches($log, 'HttpMessageNotWritableException')).Count
    if ($convCount -gt 0 -or $writableCount -gt 0) {
        Q8-No 'Q8② SSE 请求不做二次 JSON 写入' ("日志出现 No converter for x{0} / HttpMessageNotWritableException x{1}（客户端断开 SSE 时 GlobalExceptionHandler 仍以 ApiResponse 写回，内容类型已固定为 text/event-stream）" -f $convCount, $writableCount)
    } else {
        Q8-Ok 'Q8② SSE 请求不做二次 JSON 写入（日志无 No converter/HttpMessageNotWritableException）'
    }
} catch { Q8-No 'Q8 项回归' $_.Exception.Message }

Say '============================================================'
Say (" 结果：PASS={0}  FAIL={1}  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6（GF6 需 -EnableGf6）；基数不写死，随用例演进）" -f $script:passed, $script:failed)
# GFSKIP 摘要口径（红队问题 5）：文案随模式分支，与下方判定分支保持一致（纯文案，不改判定）
$gfskipNote = if ($env:E2E_STUB_MODE -eq '1') {
    '桩模式零容忍：GFSKIP≥1 即整棒 FAIL（上游是确定性替身，任何 SKIP 都属桩路由或产品链路异常）'
} else {
    '模型未触发 SKIP，不计退出码；GF1–GF4 全部 SKIP 判整棒 FAIL，裁决 #1'
}
Say (" GFSKIP={0}  （{1}）" -f $script:gfskip, $gfskipNote)
Say (" Q8 项：PASS={0}  FAIL={1}  （不计入主用例计数，见 docs/evidence/S5b-deepseek-E2E移植验证.md）" -f $script:q8pass, $script:q8fail)
Say '============================================================'
# 桩模式零容忍（M2-T1.2 裁决 T12-E#1，环境变量 E2E_STUB_MODE=1）：上游是确定性替身，CI 中任何 GF SKIP 都意味着桩路由或产品链路异常，故 GFSKIP≥1 即整棒 FAIL；真 key 本地手动跑法不设该变量，语义不变。
if ($env:E2E_STUB_MODE -eq '1' -and $script:gfskip -ge 1) { exit 1 }
# 硬底线（裁决 #1）：GF1–GF4 全部 SKIP ⇒ 本棒零有效验证，整棒 FAIL（退出码 1）
if ($script:failed -gt 0 -or $script:gfskip14 -ge 4) { exit 1 }
