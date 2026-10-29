# ============================================================
# E2E 验证脚本（main 版）：动态配置管理系统 全流程 166 用例
#   = 既有 35（TC1–TC22 + TC23 + GF1–GF6 + R-137×2 + E2E-CTX-1..4）
#     + 批① 91（AIE2E-*，设计稿 docs/research/设计-AI对话E2E用例全集-2026-10-22.md §2 十二域
#       ＋ L-21 两条表格主用例 T-13/T-14，设计稿之外新增、行内登记于 SH 域）
#     + V1 34（AIE2E-W-*，测试扩充 V1 · 工作区原语全链（桩层）+ 缺口 0 动态披露修复的回归位）
#     + V2 4（AIE2E-W-35..W-38，测试扩充 V2 · 刷新恢复链的桩级三面：在途轮发现 / 全量重放 /
#       挂起卡重建源数据 / 增量续收锚点）
#     + V5 2（AIE2E-W-39..W-40，测试扩充 V5 · 不可撤销卡批准后执行（B9 后续链路）+
#       预览→应用→撤销的协议半段（B13/D7））
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
#   - 安全防护批①（裁决 I3，2026-10-21）：**限流不在 E2E 覆盖面，由单测覆盖**
#     （AiRateLimiterTest 锁滑动窗口语义与降级；AiControllerChatGateTest 锁 20/21、429 形状、
#     归一化与长度闸）。本脚本的 /chat 才 26 次、每 sessionId ≤ 22 次（TC18 取消后重试循环），
#     全程最坏 ~45 次；故限流在 E2E 显式抬高，否则用例增长或同机并跑会以 429 变成"看似产品坏"的假红：
#       --app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000
#     CI 的 e2e job（.github/workflows/ci.yml）已按此参数启动。
#   - -BackendLog：后端 stdout 日志文件（com.example.configmgr 为 DEBUG 级）。
#     TC10 的日志断言（Q8①）与 Q8 两项回归需要它；TC15 的渐进披露断言已改用只读端点
#     GET /api/ai/tools（M1 收尾项⑤），日志仅作"日志行 vs 端点"的交叉校验（未提供则跳过）。
#     未提供 -BackendLog 时日志类断言判 FAIL（不静默跳过）
#
# 数据面安全：本脚本只创建/删除自己的 S5B-*/AIE2E-* 任务与 E2E_TEMP 定义；
#   CURRENCY 演示数据在 TC13 会被替换为 3 行，TC19 用全量导出件恢复为 5 行。
#
# 结果口径：166 个用例计入 PASS/FAIL —— 既有 35 + 批① 91（AIE2E-*）+ V1 34（AIE2E-W-*）+ V2 4 + V5 2
#   既有 35：TC1–TC22 共 22 个 + TC23（T3b 收尾：CONFIRM 卡快照重建）+ GF1–GF5 共 5 个
#   （生成式表单端到端，docs/evidence/GFc-E2E用例设计-GLM-5.3.md）+ GF6（入参闸门，-EnableGf6，
#   桩模式启用并计入主计数，底座为桩的确定性违规模式 --violate-form-type）+ R-137 红队补测
#   （MAJOR-2/MAJOR-3）2 个 + E2E-CTX-1..4 共 4 个（上下文计量，底座为桩的请求级
#   `usage-shape=<last|repeat|big|zero-last|none>`，形状表取 scripts/ci/stub-routes.json；
#   `big` = repeat 同形态但真值更大（11000/200），供 C13 起"窗口 ≥ 22K 才不被前缀主导"的用例）。
#   批① 91：设计稿 §2 十二域的接口级用例（AIE2E-S01…X13）+ L-21 两条表格主用例（T-13/T-14）
#   + AR-04 的可控时序 409（D-13，批次8A 追加、不在设计稿 §2 名单内）；同批把 Q-08 / Q-05 / D-08 / M-07
#   与遗留 TC18 的"挂起后必 409"统一改为**200/409 两果都合法**（无幻影先断：帧面未受理零帧 +
#   run 维度（runs/{runId} 的 snapshot/historyRoles）断"受理恰一次、旧段不被改写、单 assistant 身份"；
#   Redis 窗口只做"未受理 token 零条"的反向断言与挂起轮收敛正控，因为取消挂起轮会整窗覆写它；409 断
#   SESSION_BUSY + runId + reattach，200 断独立 runId + done 无 error），TC18 的 Start-Sleep 与重试循环一并移除，
#   桩开关与路由见 scripts/ci/stub-routes.json
#   的 `faults.switches`（唯一取数点）；桩下**零 SKIP**，末尾 MANIFEST 自校验条数/逐域/优先级。
#   V1 34（W 域）：7 个 `ui_*` 原语全链（B1 7 条）+ 拒执码落档（B3 2）+ 动态披露两分支（B4 2，
#   缺口 0 的回归位）+ 不可撤销位三通道（B9 3）+ 交接卡（B14 2）+ 页面拒执如实落档（B15 2）+
#   写动词轮内状态归零（B18 1）+ 前端注册表对账（B20 1）+ 停止/恢复五组（A9/A10/A11/A12/A13 共 14）。
#   V2 4（W 域尾）：刷新恢复链的桩级三面 —— W-35 断开后在途轮发现（runs/current 的 RUNNING +
#   archiveMaxSeq）/ W-36 断开后 lastSeq=0 全量重放完整 / W-37 断开后挂起卡重建源数据（kind/req/
#   arguments 与 confirm_request 帧同源）/ W-38 已收末号续收只补新帧。
#   V5 2（W 域尾）：W-39 不可撤销卡批准后执行（IRREV-PUBLISH 的 irreversible 保真 → 批准 →
#   tool_result ok → done + 发布作业真实产生）/ W-40 预览→应用→撤销协议半段（SHEET-UPDATE 应用
#   回灌 + SHEET-UNDO 撤销回灌，两轮同会话各自收敛；写闸关时两工具都不披露、零工具帧）。
#   移植期缺陷清单（Q8）的两项断言单列计数（Q8 项 PASS/FAIL），不计入 166 与退出码。
# 退出码：任一主用例 FAIL ⇒ exit 1；GF1–GF4 全部 SKIP（本棒零有效验证，硬底线）⇒ exit 1；
#   E2E_STUB_MODE=1 且 GFSKIP≥1 ⇒ exit 1（桩模式零容忍）；
#   批① 的 P2 红**单列不阻塞**（设计稿 §4.2 第 5 项：防一门禁把开发卡死），P0/P1 红照常 exit 1。
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

# ---------- 超时窗口（超时面治理批① 第 9 项：一律从产品键派生，不写字面量） ----------
# 根源键 = app.ai.resilience.total-budget（application.yml，缺省 300s）= 一轮对话的机器预算上界。
# 客户端等一帧 / 等一个响应必须比它**宽**，否则"产品还在跑、脚本先判超时"就是假红。
# 公式 = total-budget + 60s 余量（余量覆盖建连 + 服务端收尾 + 本机调度抖动）。
# 键值调整时只改 $script:TurnBudgetSeconds 一处（原为 :98 的 5min 与 :250 的 180000 两个魔数）。
$script:TurnBudgetSeconds = 300
$script:HttpTimeoutMs = ($script:TurnBudgetSeconds + 60) * 1000

Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$http = New-Object System.Net.Http.HttpClient
# 单请求上限 = total-budget + 60s（见上方派生段；原为写死的 5min）
$http.Timeout = [TimeSpan]::FromMilliseconds($script:HttpTimeoutMs)

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

# Wait-Job 作业终态等待窗口（总账 S-10；2026-10-11 批 B / R-96 由隐式 96s 显式化为 120s）。
# = heartbeat-interval(30s) × dead-multiplier(3) + scan-period(10s) + margin(20s)，与 application.yml 四键对应，键值调整时须同步本式。
# 同一纪律见上方 $script:HttpTimeoutMs（total-budget + 60s）：窗口一律由产品键派生，脚本里不出现裸时间常量；
# 本式与 SSE 窗口的分工 = 作业通道看"作业终态"，对话通道看"一帧/一个响应"。
$script:JobWaitTimeoutMs = ((30*3)+10+20)*1000

function Wait-Job($jobId, [string[]]$terminal = @('COMPLETED', 'FAILED', 'CANCELLED')) {
    $deadline = (Get-Date).AddMilliseconds($script:JobWaitTimeoutMs)
    while ((Get-Date) -lt $deadline) {
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
# （TC11 需要"先订阅、后启动作业"，故必须有这个时点）。
# 返回 {status, ok, text}：**非 2xx 不在这里抛错**，由调用方决定 —— Read-Sse 照旧抛错（既有 60+ 调用点
# 的语义不变）；AR-04 要求的"先把历史三面断完、再判状态码"用 Ai-ChatSse 取状态码（见该函数）。
function Read-SseCore($path, $bodyObj, [string[]]$stopOn, $sink, $onOpen) {
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
    # SSE 读窗口与 HTTP 同源（total-budget + 60s；原为写死的 180000ms）
    $cts.CancelAfter($script:HttpTimeoutMs)
    $resp = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
    if (-not $resp.IsSuccessStatusCode) {
        $status = [int]$resp.StatusCode
        $t = $resp.Content.ReadAsStringAsync().Result
        $resp.Dispose()
        $cts.Dispose()
        return @{ status = $status; ok = $false; text = $t }
    }
    $statusOk = [int]$resp.StatusCode
    if ($onOpen) { & $onOpen }
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    # 202（AR-06 "已 start、快照未建" ⇒ "准备中"，AiController#preparingOrUnknown）：响应体是 JSON
    # 契约（preparing/retryAfterMs/elapsedMs），不是 SSE 帧。只有这个状态码需要正文，故只在 202 时
    # 收集非 `data:` 行；其余状态码的 text 仍恒为 ''（既有 60+ 调用点的观测量不变）。
    $bodyLines = New-Object System.Collections.ArrayList
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
            } elseif ($statusOk -eq 202) {
                $bodyLines.Add($line) | Out-Null
            }
        }
    } catch {
        # 断开/超时（含预期内的 StopOn 早退）不视为用例失败，由断言判定实际收到的帧
    } finally {
        $reader.Dispose()
        $resp.Dispose()
        $cts.Dispose()
    }
    $text = if ($bodyLines.Count -gt 0) { ($bodyLines -join "`n") } else { '' }
    return @{ status = $statusOk; ok = $true; text = $text }
}

# Read-Sse = Read-SseCore + 既有抛错口径（文案形状不变："SSE <path> => HTTP <status> <body>"；
# 状态码自本批起按整数渲染，与同仓 b23l1-dual-instance.ps1 同形，既有调用点无一解析这段文字）
function Read-Sse($path, $bodyObj, [string[]]$stopOn, $sink, $onOpen) {
    $r = Read-SseCore $path $bodyObj $stopOn $sink $onOpen
    if (-not $r.ok) { throw "SSE $path => HTTP $($r.status) $($r.text)" }
}

# 发一轮 /api/ai/chat 并收集帧，但**不因非 2xx 抛错**（AR-04 §2.4：旧 Q-08 把"第二次请求必 409"排在
# 历史检查之前，请求一失败历史面就根本没被观测到）。返回 {status, ok, text, json, frames}；
# 帧收集口径与 Read-Sse 同源；返回体是 JSON（如 409 回执）时一并解析成 json。
# $onOpen 与 Read-Sse 同一时点（响应头已到、尚未读流）——"门确定被持有"的可控时序窗口用这个时点。
# 定义位置在 TC18 之前：TC18（既有 35 条之一）也要按 200/409 两果收，PowerShell 必须先定义后调用。
function Ai-ChatSse($sid, $msg, $ctx, [string[]]$stopOn, [scriptblock]$onOpen) {
    $buf = New-Object System.Collections.ArrayList
    $r = Read-SseCore '/api/ai/chat' @{ sessionId = $sid; message = $msg; context = $ctx } $stopOn $buf $onOpen
    $obj = $null
    if ($r.text -and $r.text.TrimStart().StartsWith('{')) { try { $obj = $r.text | ConvertFrom-Json } catch { $obj = $null } }
    return @{ status = $r.status; ok = $r.ok; text = $r.text; json = $obj; frames = $buf }
}

# ---------- 数据库完整历史端点移除后的两个判据载体 ----------
# 载体选择（复审 N2）：Redis 记忆窗口是 convergeMemory 的**整窗覆盖**（saveAll 原子替换）——
# "受理轮跑完之后再取消挂起轮"会用挂起轮的 snapshot 覆盖窗口，把更晚的受理轮消息抹掉。因此
#   · 正向判据（本轮落了什么 / 旧段是否被改写）一律走 **run 维度**：GET /api/ai/runs/{runId} 的
#     snapshot + historyRoles 是每一轮自己的快照，不会被别的轮收敛改写；
#   · 窗口只做**反向断言**（未受理的 token 零条）与挂起轮收敛后的正控。
function Ai-RunRoleInfo($runId) {
    $r = Invoke-Api 'GET' "/api/ai/runs/$runId" $null
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "GET /api/ai/runs/$runId => HTTP $($r.status) $($r.text)" }
    $data = $r.json.data
    if (-not $data.exists -or -not $data.snapshot) { throw "run $runId 不存在或没有快照（判据无对象）" }
    $roles = @($data.historyRoles | ForEach-Object { [string]$_ })
    if ($roles.Count -eq 0) { throw "run $runId 的 historyRoles 为空（判据无对象）" }
    $messageCount = [int]$data.snapshot.messageCount
    if ($messageCount -ne $roles.Count) {
        throw "run $runId 的 messageCount=$messageCount 与 historyRoles=$($roles.Count) 不一致（快照自相矛盾）"
    }
    return @{ runId = $runId; status = [string]$data.snapshot.status; roles = $roles; messageCount = $messageCount }
}
# 本轮自身的尾部形态：末条 ASSISTANT、其前恰一条 USER（再往前是边界或开头）。
# 这是原"一次发送恰落 user+assistant 两行"在 run 快照上的等价判据（快照可能还含更早的会话历史）。
function Ai-AssertSingleTurnTail($info, $label) {
    $roles = @($info.roles)
    $n = $roles.Count
    if ($n -lt 2) { throw "$label（run $($info.runId)）historyRoles 只有 $n 条（判据无对象）" }
    if ($roles[$n - 1] -ne 'ASSISTANT') { throw "$label（run $($info.runId)）末条角色是 $($roles[$n - 1])，期望 ASSISTANT" }
    if ($roles[$n - 2] -ne 'USER') { throw "$label（run $($info.runId)）末条前一条是 $($roles[$n - 2])，期望 USER（本轮用户原话）" }
    if ($n -ge 3 -and $roles[$n - 3] -eq 'USER') {
        throw "$label（run $($info.runId)）尾部连续出现两条 USER（同一次发送落了两次用户原话）"
    }
}
# 旧段不被改写：第二次发送之后，挂起轮自己的快照必须与发送前逐项一致（状态 / 消息数 / 角色序列）。
function Ai-AssertRunUnchanged($before, $label) {
    $after = Ai-RunRoleInfo $before.runId
    if ($after.status -ne $before.status) {
        throw "$label：run $($before.runId) 状态 $($before.status) → $($after.status)（历史被改写）"
    }
    if ($after.messageCount -ne $before.messageCount) {
        throw "$label：run $($before.runId) 消息数 $($before.messageCount) → $($after.messageCount)（旧段被删/被追加）"
    }
    if (($after.roles -join ',') -ne ($before.roles -join ',')) {
        throw "$label：run $($before.runId) 角色序列被改写（$($before.roles -join ',') → $($after.roles -join ',')）"
    }
}
# 已收敛的 Redis 记忆窗口（只用于反向断言与挂起轮正控）。
function Ai-Window($sid) {
    $h = GetJson "/api/ai/history/$sid"
    return ,@($h.messages)
}
# 等窗口里出现该 token（返回命中条目）；超时抛错（判据无对象 = 失败，不是跳过）。
function Ai-WaitWindowToken($sid, $token, [int]$sec = 20) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        $hits = @(Ai-Window $sid | Where-Object { ([string]$_.text) -like "*$token*" })
        if ($hits.Count -ge 1) { return ,$hits }
        Start-Sleep -Milliseconds 200
    }
    throw "会话 $sid 的窗口在 ${sec}s 内没有出现 $token（收敛未发生：判据无对象，不得当通过）"
}
# 窗口里重复的用户原话（同一条发送被落两次）= 幻影气泡/重复落库的最小可观测形式。
# 只用于"窗口是最后写入者"的用例（如 D-13：首轮取消是最后一次收敛）；会被后续整窗覆盖的
# 用例（Q-05/Q-08/TC18）不使用它，改用 run 维度的 Ai-AssertSingleTurnTail / Ai-AssertRunUnchanged。
function Ai-WindowDuplicateUserTexts($sid) {
    $texts = @(Ai-Window $sid | Where-Object { $_.type -eq 'USER' } | ForEach-Object { [string]$_.text } |
        Where-Object { $_ })
    if ($texts.Count -eq 0) { throw "会话 $sid 的窗口没有任何用户消息（判据无对象，不得当通过）" }
    return ,@($texts | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
}
# 一轮的 assistant 消息身份集合（message_start / delta / message_end 都带 messageId）。
# 空集合 = 这一轮没有可判定的 assistant 正文 ⇒ 调用方必须显式失败（判据无对象）。
function Ai-AssistantIds($frames) {
    $ids = @($frames | Where-Object {
            @('message_start', 'delta', 'message_end') -contains $_.type -and
            $null -ne $_.PSObject.Properties['messageId']
        } | ForEach-Object { [string]$_.messageId } | Where-Object { $_ } | Sort-Object -Unique)
    return ,$ids
}
# 等 run 进入指定状态（TC18/Q-05/Q-08 的"挂起已落定"前提都要它 ⇒ 定义放在这些用例之前）。
function Ai-WaitStatus($runId, [string[]]$want, [int]$sec = 15) {
    $deadline = (Get-Date).AddSeconds($sec)
    $last = '(未读到)'
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Api 'GET' "/api/ai/runs/$runId" $null
        if ($r.ok -and $r.json -and $r.json.success -and $r.json.data.snapshot) {
            $last = [string]$r.json.data.snapshot.status
            if ($want -contains $last) { return $r.json.data }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "轮次 $runId 在 ${sec}s 内未进入 $($want -join '/')（现值 $last）"
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
        if ($rowGfc.task.title -like 'GFC-*' -or $rowGfc.task.title -like 'AIE2E-*') {
            try { DeleteJson ("/api/tasks/" + $rowGfc.task.id); $removedGfc++ } catch { }
        }
    }
    Say "--- 预清理（GF/批①）：删除残留 GFC-*/AIE2E-* 任务 $removedGfc 个 ---"
} catch { Say ("--- 预清理（GF/批①）：跳过（" + $_.Exception.Message + "） ---") }

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
#   帧类型为 JOB_PROGRESS / JOB_DONE / HEARTBEAT（TASK_CHANGED 发布链已随手动刷新批 S5 摘除）；
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

    # 重复发布同一份暂存 → R-137 终态守卫（COMPLETED+最终作业成功）在**建作业之前**即 409 TASK_FINALIZED
    # （原断言链"启动成功→作业 FAILED+发布冲突明细"依赖守卫前的旧契约，新契约下拿不到作业 id）。
    # 快照冲突（发布冲突/乐观锁）面改由后端单测 PublishSemanticsTest.snapshotMismatchFailsJobWithBaselineMessage
    # 锁定；E2E 面覆盖归零挂账（R-134 族，见热修报告）。终态作业取消幂等子例改用首次成功的 $jPub。
    $r2 = Expect-Fail 'POST' "/api/tasks/$($script:t12)/jobs" @{ jobType = 'PUBLISH' } '二次发布未被终态守卫拒绝'
    if ($r2.status -ne 409 -or $r2.json.code -ne 'TASK_FINALIZED') {
        throw "二次发布响应 HTTP $($r2.status)/code=$($r2.json.code)，期望 409/TASK_FINALIZED"
    }
    if ("$($r2.json.message)" -notlike '*TASK_FINALIZED*') { throw "二次发布文案缺 TASK_FINALIZED：$($r2.json.message)" }
    $again = GetJson '/api/data/CURRENCY?page=0&size=50'
    if ($again.content.Count -ne 3) { throw "二次发布后行数 $($again.content.Count)" }
    foreach ($r in $again.content) {
        if ($r.id -ne $afterMap[$r.rowKey].id) { throw "二次发布后 $($r.rowKey) 行 id 变化" }
    }

    # 终态作业取消 → 409 JOB_ALREADY_FINAL（机器可读码；终态作业 = 首次成功的 $jPub）
    $c = Expect-Fail 'DELETE' "/api/jobs/$($jPub.id)" $null '终态作业取消未被拒'
    if ($c.status -ne 409 -or $c.json.code -ne 'JOB_ALREADY_FINAL') {
        throw "终态取消响应 HTTP $($c.status)/code=$($c.json.code)，期望 409/JOB_ALREADY_FINAL"
    }
    Ok 'TC13 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 终态守卫拦截二次发布 409 TASK_FINALIZED + 409 JOB_ALREADY_FINAL'
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
#   提供 -BackendLog 时仍做一次"日志行 vs 端点"的交叉校验。取 page:tasks / task:IMPORT/PUBLISH /
#   task:EXPORT/{SELECT_DEFS,QUERY_COND} 四个上下文对照（start_export 只在任务中心与导出向导的
#   导出步披露；start_publish 只在导入向导的发布步披露）。
#   2026-10-28 步骤披露收口：三个步骤级向导动作按"它能成功的步骤"披露（与前端动作表的 allowSteps
#   同源，口径见 ToolRegistryDisclosureTest#wizardToolDisclosureIsStepScoped）——
#   select_definitions 只在 EXPORT/SELECT_DEFS + IMPORT/UPLOAD，set_condition 只在 EXPORT/QUERY_COND，
#   confirm_step 在整个任务内；任务中心没有任务/步骤事实，三者一律不披露
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

    foreach ($need in @('list_config_defs', 'create_task', 'navigate_to', 'start_export')) {
        if ($tTasks -notcontains $need) { throw "page:tasks 未披露 $need（$($tTasks -join ',')）" }
    }
    if ($tTasks -contains 'start_publish') { throw 'page:tasks 不应披露 start_publish' }
    # 2026-10-28 步骤披露收口：任务中心没有「当前任务/步骤」这个事实，三个步骤级向导动作不披露
    # （旧口径的宽披露会让模型在 SELECT_DEFS 直接调 set_condition，被前端 allowSteps 守卫拒绝）
    foreach ($stepScoped in @('select_definitions', 'set_condition', 'confirm_step')) {
        if ($tTasks -contains $stepScoped) { throw "page:tasks 不应披露步骤级动作 $stepScoped（$($tTasks -join ',')）" }
    }
    if ($tPub -notcontains 'start_publish') { throw "task:IMPORT/PUBLISH 缺少 start_publish（$($tPub -join ',')）" }
    if ($tPub -contains 'start_export') { throw 'task:IMPORT/PUBLISH 不应披露 start_export' }

    # 正向对照：步骤级动作在各自「能成功的步骤」披露（收口不等于全删）
    $toolsSelDefs = GetJson '/api/ai/tools?page=export&taskType=EXPORT&step=SELECT_DEFS'
    if ($toolsSelDefs.context -ne 'task:EXPORT/SELECT_DEFS') { throw "端点上下文 $($toolsSelDefs.context)，期望 task:EXPORT/SELECT_DEFS" }
    $tSelDefs = @($toolsSelDefs.toolNames)
    if ($tSelDefs -notcontains 'select_definitions') { throw "task:EXPORT/SELECT_DEFS 未披露 select_definitions（$($tSelDefs -join ',')）" }
    if ($tSelDefs -notcontains 'confirm_step') { throw "task:EXPORT/SELECT_DEFS 未披露 confirm_step（$($tSelDefs -join ',')）" }
    if ($tSelDefs -contains 'set_condition') { throw 'task:EXPORT/SELECT_DEFS 不应披露 set_condition（QUERY_COND 才可用）' }

    $toolsQueryCond = GetJson '/api/ai/tools?page=export&taskType=EXPORT&step=QUERY_COND'
    if ($toolsQueryCond.context -ne 'task:EXPORT/QUERY_COND') { throw "端点上下文 $($toolsQueryCond.context)，期望 task:EXPORT/QUERY_COND" }
    $tQueryCond = @($toolsQueryCond.toolNames)
    if ($tQueryCond -notcontains 'set_condition') { throw "task:EXPORT/QUERY_COND 未披露 set_condition（$($tQueryCond -join ',')）" }
    if ($tQueryCond -contains 'select_definitions') { throw 'task:EXPORT/QUERY_COND 不应披露 select_definitions（SELECT_DEFS 才可用）' }

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
    Ok ("TC15 流式+工具调用可见+渐进披露（只读端点 /api/ai/tools）：page:tasks {0} 个工具（无 start_publish、无步骤级向导动作）；task:IMPORT/PUBLISH 含 start_publish；task:EXPORT/SELECT_DEFS 含 select_definitions 且无 set_condition；task:EXPORT/QUERY_COND 含 set_condition" -f $tTasks.Count)
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

# POST /api/ai/chat 的"客户端主动断流"形态（S-05 的重连差量续收底座）。
#   - 读到 $stopAfterFrames 帧即主动 Dispose（等价于前端用完即走 / 页面关闭），
#     否则最多等 $millis 毫秒；两条路径都会断开连接，而**后端不因此取消本轮**（停止是显式动作）。
#   - 与 Read-Sse-Brief 同一纪律：握手失败必须显式抛出，不得被读流窗口的 catch 吞掉。
function Read-Sse-Brief-Post($path, $bodyObj, $millis, $stopAfterFrames = 3) {
    $frames = New-Object System.Collections.ArrayList
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($Base + $path)
    $req.Method = [System.Net.Http.HttpMethod]::Post
    $json = if ($bodyObj -is [string]) { $bodyObj } else { $bodyObj | ConvertTo-Json -Depth 20 -Compress }
    $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $cts = New-Object System.Threading.CancellationTokenSource
    $cts.CancelAfter($millis)
    $resp = $null
    try {
        $resp = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
    } catch {
        $cts.Dispose()
        throw "SSE POST $path => 建连失败（${millis}ms 窗口内未建立）：$($_.Exception.Message)"
    }
    if (-not $resp.IsSuccessStatusCode) {
        $code = [int]$resp.StatusCode
        $text = ''
        try { $text = $resp.Content.ReadAsStringAsync().Result } catch { }
        $resp.Dispose(); $cts.Dispose()
        throw "SSE POST $path => HTTP $code $text"
    }
    $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result, [System.Text.Encoding]::UTF8)
    try {
        while ($sw.ElapsedMilliseconds -lt $millis -and $frames.Count -lt $stopAfterFrames) {
            $remaining = $millis - [int]$sw.ElapsedMilliseconds
            if ($remaining -le 0) { break }
            $readTask = $reader.ReadLineAsync()
            if (-not $readTask.Wait($remaining)) { break }
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
# 2026-10-20 去超时裁决（第 2 条）：确认门与 generative_form 等的是人（填表/决策），没有到期时刻 ——
# GF 帧**不带** timeoutSeconds/expiresAt 两键，前端据此不武装倒计时。即时执行类前端工具仍成对带
# （120s，生产侧 ConfirmGate.suspendWaitSeconds 单点取值）；本函数只服务 GF 四用例，故只断言"无此两键"。
function Gf-Assert-FrameContract($f) {
    if (-not $f.toolCallId) { throw 'frontend_tool_request 缺 toolCallId' }
    foreach ($k in @('timeoutSeconds', 'expiresAt')) {
        if ($null -ne $f.PSObject.Properties[$k]) {
            throw "frontend_tool_request 带 $k=$($f.$k)，期望 GF 帧不含该键（generative_form 无到期时刻，2026-10-20 裁决）"
        }
    }
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
# E2E-CTX-1..4 上下文计量（上下文统计与压缩专项批① 的 E2E 面；设计稿 §5.8）
#   缺口登记：E2E-CTX-1..4 + 三形态 usage 桩（批① 收尾记录，统一归 E2E 批）
#   底座：桩的**请求级** usage 形状开关 —— 提示词里的 `usage-shape=<mode>` 标记
#     （scripts/ci/stub-upstream.mjs；与 --violate-form-type 同族；未带标记的请求帧逐字节不变）
#   数值唯一取数点：scripts/ci/stub-routes.json 的 `usageShapes`（桩读同一份 ⇒ 桩与用例不漂移）
#   口径：pill 的"显示一致"在无浏览器 CI 里的等价物 = GET /api/ai/context/{sessionId}
#     （pill 的唯一数据源）与 done.usage / done.context **三方同源**；pill 渲染本身由前端
#     tests/context-pill.spec.ts（C-31..C-36）锁，本批不起浏览器。
#   压缩：四条用例的占比都远低于 trigger-ratio，故"本轮不发 context 帧"本身是断言；
#     「压缩按估算触发」的等价物见 E2E-CTX-4 的第二轮（估算随历史增长 = 触发判定的输入）。
# ============================================================

# 形状表（与桩同源）。读不到即本组 4 条全 FAIL —— 桩坏了不能装成绿
function Get-CtxShapes {
    $file = Join-Path $PSScriptRoot 'ci/stub-routes.json'
    if (-not (Test-Path $file)) { throw "形状表缺失：$file" }
    $cfg = Get-Content -Raw -Encoding UTF8 -Path $file | ConvertFrom-Json
    if (-not $cfg.usageShapes) { throw "形状表缺失：$file 的 usageShapes 段（三形态 usage 桩未落地）" }
    return $cfg.usageShapes
}

# 一轮帧 + 端点快照 → 错误串数组（空 = 通过）。
#   刻意不碰 HTTP 与全局计数：可在**无服务**环境里用合成件单独回归（施工报告 §3.2 的做法）。
function Test-CtxCase($frames, $snap, $expect) {
    $errors = New-Object System.Collections.ArrayList
    $dones = @($frames | Where-Object { $_.type -eq 'done' })
    if ($dones.Count -ne 1) {
        $errors.Add("done 帧 $($dones.Count) 个，期望恰 1") | Out-Null
        return $errors
    }
    $done = $dones[0]
    $u = $done.usage
    if ($null -eq $u) {
        $errors.Add('done 帧缺 usage 载荷') | Out-Null
        return $errors
    }
    if ([bool]$u.est -ne [bool]$expect.est) { $errors.Add("usage.est=$($u.est)，期望 $($expect.est)") | Out-Null }
    if ($u.source -ne $expect.source) { $errors.Add("usage.source=$($u.source)，期望 $($expect.source)") | Out-Null }
    if ([int]$u.dirty -ne 0) { $errors.Add("usage.dirty=$($u.dirty)，期望 0（本组用例没有回退帧）") | Out-Null }
    if ($null -ne $expect.framesExact -and [int]$u.frames -ne [int]$expect.framesExact) {
        $errors.Add("usage.frames=$($u.frames)，期望恰 $($expect.framesExact)") | Out-Null
    }
    if ($null -ne $expect.framesMin -and [int]$u.frames -lt [int]$expect.framesMin) {
        $errors.Add("usage.frames=$($u.frames)，期望 ≥$($expect.framesMin)（形状没被真的发出来，用例即空转）") | Out-Null
    }
    if ($null -ne $expect.prompt) {
        if ([int]$u.promptTokens -ne [int]$expect.prompt -or [int]$u.completionTokens -ne [int]$expect.completion `
                -or [int]$u.totalTokens -ne [int]$expect.total) {
            $errors.Add(("usage={0}/{1}/{2}，期望 {3}/{4}/{5}（形状 {6}）" -f $u.promptTokens, $u.completionTokens,
                    $u.totalTokens, $expect.prompt, $expect.completion, $expect.total, $expect.shape)) | Out-Null
        }
    } else {
        # 估算回退（ContextMeter.usage 的 est 分支）：completion 恒 0、total 恒等于 prompt、prompt 必须 >0
        if ([int]$u.promptTokens -le 0) { $errors.Add("usage.promptTokens=$($u.promptTokens)，期望 >0（估算回退值）") | Out-Null }
        if ([int]$u.completionTokens -ne 0) { $errors.Add("usage.completionTokens=$($u.completionTokens)，期望 0（估算回退值）") | Out-Null }
        if ([int]$u.totalTokens -ne [int]$u.promptTokens) {
            $errors.Add("usage.totalTokens=$($u.totalTokens) ≠ promptTokens=$($u.promptTokens)（估算回退口径）") | Out-Null
        }
    }
    # done.context 与 done.usage 同源（批①「端点与 done 必须同源」的帧面一半）
    $c = $done.context
    if ($null -eq $c) {
        $errors.Add('done 帧缺 context 子对象（上下文计量未装配？）') | Out-Null
    } else {
        if ([int]$c.usedTokens -ne [int]$u.promptTokens) {
            $errors.Add("done.context.usedTokens=$($c.usedTokens) ≠ usage.promptTokens=$($u.promptTokens)") | Out-Null
        }
        if ([bool]$c.est -ne [bool]$expect.est) { $errors.Add("done.context.est=$($c.est)，期望 $($expect.est)") | Out-Null }
        if ($c.level -ne 'none') { $errors.Add("done.context.level=$($c.level)，期望 none（本组用例不触发压缩）") | Out-Null }
    }
    # 未压缩轮不得发 context 帧（批① 自决⑥：只在该轮真压缩时发）
    if (@($frames | Where-Object { $_.type -eq 'context' }).Count -ne 0) {
        $errors.Add('本轮不应出现 context 帧（未压缩却发作压缩事件）') | Out-Null
    }
    # 端点（pill 的唯一数据源）
    if ($null -eq $snap) {
        $errors.Add('GET /api/ai/context 无响应体') | Out-Null
        return $errors
    }
    if ([int]$snap.usedTokens -ne [int]$u.promptTokens) {
        $errors.Add("端点 usedTokens=$($snap.usedTokens)，期望 $($u.promptTokens)（与 done 必须同源）") | Out-Null
    }
    $wantSource = if ([bool]$expect.est) { 'estimate' } else { 'stream' }
    if ($snap.source -ne $wantSource) { $errors.Add("端点 source=$($snap.source)，期望 $wantSource") | Out-Null }
    if ([bool]$snap.estimated -ne [bool]$expect.est) { $errors.Add("端点 estimated=$($snap.estimated)，期望 $($expect.est)") | Out-Null }
    if ($null -eq $snap.turnNo -or [int]$snap.turnNo -lt 1) { $errors.Add("端点 turnNo=$($snap.turnNo)，期望 ≥1") | Out-Null }
    # 分项守恒：Σ(非 free 项) == usedTokens（设计稿 §9 第 3 条的端点面）
    $sum = 0
    foreach ($part in @($snap.breakdown)) { if ($part.key -ne 'free') { $sum += [int]$part.tokens } }
    if ($sum -ne [int]$snap.usedTokens) { $errors.Add("分项 Σ=$sum ≠ usedTokens=$($snap.usedTokens)（守恒破坏）") | Out-Null }
    # percent 依赖估算值（used 随工具披露面/人设体量漂），判据只锁取整口径、不锁绝对值。
    # 后端 ContextBreakdownService.percent = Math.round(used*1000/limit)/10 即 half-up；
    # [math]::Round 缺省是 ToEven（银行家舍入），只在中点分歧（42.5 → 42），故须显式 AwayFromZero。
    if ($null -ne $snap.limitTokens -and $null -ne $snap.percent) {
        $wantPercent = [math]::Round([double]$snap.usedTokens * 1000.0 / [double]$snap.limitTokens, 0, [MidpointRounding]::AwayFromZero) / 10.0
        if ([math]::Abs([double]$snap.percent - $wantPercent) -gt 0.05) {
            $errors.Add("端点 percent=$($snap.percent)，期望 $wantPercent（used/limit 的一位小数）") | Out-Null
        }
    }
    return $errors
}

Say '--- E2E-CTX-1..4 上下文计量（usage 形状 / 去重 / 末帧零 / 回退估算） ---'
$ctxShapes = $null
try { $ctxShapes = Get-CtxShapes } catch { }
if ($null -eq $ctxShapes) {
    foreach ($ctxId in @('E2E-CTX-1', 'E2E-CTX-2', 'E2E-CTX-3', 'E2E-CTX-4')) {
        No $ctxId '形状表不可读（scripts/ci/stub-routes.json 的 usageShapes）：三形态 usage 桩未落地'
    }
} else {
    $ctxCases = @(
        @{ id = 'E2E-CTX-1'; shape = 'last'; title = '末块 usage 真值'
            expect = @{ est = $false; source = 'stream'; framesMin = 1; shape = 'last'
                prompt = [int]$ctxShapes.last.promptTokens
                completion = [int]$ctxShapes.last.completionTokens
                total = [int]$ctxShapes.last.totalTokens } },
        @{ id = 'E2E-CTX-2'; shape = 'repeat'; title = '每块重复 usage 不放大'
            expect = @{ est = $false; source = 'stream'; framesMin = 2; shape = 'repeat'
                prompt = [int]$ctxShapes.repeat.promptTokens
                completion = [int]$ctxShapes.repeat.completionTokens
                total = [int]$ctxShapes.repeat.totalTokens } },
        @{ id = 'E2E-CTX-3'; shape = 'zero-last'; title = '末块 0/0/0 取中间真值'
            # frames 判据 = **与 E2E-CTX-1 实测同值**（同构对照），不写死数字：
            # 实测（2026-10-21 主会话 E2E 首轮）末帧序列 = 真值×2 + 0/0/0×2 —— Spring AI 的
            # buffer(2,1) 聚合把每个候选各投递 2 次，`last` 与 `zero-last` 在这一维度**同构**；
            # 写死 1 会红（首轮实测 frames=2）。判据需在循环内取 CTX-1 的实测值填充。
            expect = @{ est = $false; source = 'stream'; framesMin = 1; shape = 'zero-last'
                prompt = [int]$ctxShapes.'zero-last'.promptTokens
                completion = [int]$ctxShapes.'zero-last'.completionTokens
                total = [int]$ctxShapes.'zero-last'.totalTokens } },
        @{ id = 'E2E-CTX-4'; shape = 'none'; title = '无 usage 回退估算'
            expect = @{ est = $true; source = 'estimate'; framesExact = 0; shape = 'none'; prompt = $null } }
    )
    # E2E-CTX-1 实测 usage.frames —— E2E-CTX-3 的**同构对照基准**（见该用例 expect 的注释）：
    # 两个形状在这一维度必须相等（每个 usage 候选被聚合器投递的次数相同）；数值不同即形状没走对路径。
    $ctxLastFrames = $null
    foreach ($c in $ctxCases) {
        Say ("--- {0} {1}（usage-shape={2}） ---" -f $c.id, $c.title, $c.shape)
        try {
            $sid = 's5b-' + $c.id.ToLower() + '-' + [Guid]::NewGuid().ToString('N')
            $turn = Invoke-AiTurn $sid "【上下文计量用例 $($c.id)】usage-shape=$($c.shape) 请只回复一句确认。" `
                @{ page = 'tasks' } $script:continueHandler 2
            $resp = Invoke-Api 'GET' "/api/ai/context/$sid" $null
            if (-not $resp.ok) { throw "GET /api/ai/context/$sid => HTTP $($resp.status) $($resp.text)" }
            $doneFrame = @($turn.frames | Where-Object { $_.type -eq 'done' })[0]
            if ($null -ne $doneFrame -and $null -ne $doneFrame.usage) {
                if ($c.id -eq 'E2E-CTX-1') { $ctxLastFrames = [int]$doneFrame.usage.frames }
            }
            if ($c.id -eq 'E2E-CTX-3') {
                # 同构判据（依据 = 首轮实跑 + 指挥官裁决 2026-10-21）：基准缺失即判红，不静默跳过
                if ($null -eq $ctxLastFrames) {
                    throw 'E2E-CTX-1 未取到 usage.frames —— CTX-3 的同构判据没有基准（不允许退化成"不判"）'
                }
                $c.expect.framesExact = $ctxLastFrames
            }
            $errors = New-Object System.Collections.ArrayList
            foreach ($e in @(Test-CtxCase $turn.frames $resp.json $c.expect)) { $errors.Add($e) | Out-Null }
            $used1 = [int]$resp.json.usedTokens

            if ($c.id -eq 'E2E-CTX-4') {
                # 第二轮：估算随历史增长（"压缩按估算触发"的输入面）；长提示词让增长无歧义
                $filler = ('历史增长填充。' * 20)
                $turn2 = Invoke-AiTurn $sid "【上下文计量用例 E2E-CTX-4】usage-shape=none 第二轮（$filler）请只回复一句确认。" `
                    @{ page = 'tasks' } $script:continueHandler 2
                $resp2 = Invoke-Api 'GET' "/api/ai/context/$sid" $null
                if (-not $resp2.ok) { throw "第二轮 GET /api/ai/context/$sid => HTTP $($resp2.status) $($resp2.text)" }
                foreach ($e in @(Test-CtxCase $turn2.frames $resp2.json $c.expect)) { $errors.Add($e) | Out-Null }
                if ([int]$resp2.json.usedTokens -le $used1) {
                    $errors.Add("第二轮估算 usedTokens=$($resp2.json.usedTokens) 未超过第一轮 $used1（估算不随历史增长 ⇒ 压缩触发判定的输入失真）") | Out-Null
                }
                # /health 的两个上下文观测字段必须同源（上限未知 ⇒ 不做比例判定 ⇒ compactionOn=false）
                $health = Invoke-Api 'GET' '/api/ai/health' $null
                if (-not $health.ok) {
                    $errors.Add("GET /api/ai/health => HTTP $($health.status)") | Out-Null
                } else {
                    $ctxLimit = $health.json.contextLimitTokens
                    $ctxOn = [bool]$health.json.compactionOn
                    if ($ctxOn -ne ($null -ne $ctxLimit)) {
                        $errors.Add("health.compactionOn=$ctxOn 与 contextLimitTokens=$ctxLimit 不同源") | Out-Null
                    }
                }
            }

            if ($errors.Count -gt 0) { throw ($errors -join '；') }
            # 成功行带实测 usage 与 frames：frames 是 CTX-3 同构判据的**取证值**（与 CTX-1 对照可读）
            $uOk = if ($null -ne $doneFrame) { $doneFrame.usage } else { $null }
            $usageOk = if ($null -ne $uOk) { "{0}/{1}/{2} est={3}" -f $uOk.promptTokens, $uOk.completionTokens, $uOk.totalTokens, $uOk.est } else { '?' }
            $framesOk = if ($null -ne $uOk) { $uOk.frames } else { '?' }
            Ok ("{0} {1}（usage={2} frames={3}；端点 usedTokens={4} 与 done.usage/done.context 同源；分项守恒）" -f $c.id, $c.title, $usageOk, $framesOk, $used1)
        } catch { No $c.id $_.Exception.Message }
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
# TC18 会话隔离 / 并发发轮两果（AR-04）/ 取消后终态与再发
#   源断言：会话计数增减、清空
#   main 适配：无 /sessions/count 与 /clear；改为 main 的会话语义：
#   ① 不同 sessionId 的记忆窗口互相隔离（/api/ai/history/{sessionId}）；
#   ② 同 sessionId 并发发轮 → **两果都合法**（AR-04：park 后会话门由驱动线程 finally 释放，
#      "挂起"≠"占门"）：409 ⇒ SESSION_BUSY + 进行中 runId + reattach（ADR-5/Q11，可接管）；
#      200 ⇒ 独立轮（runId 不重用 + 恰 1 start/done + 无 error）。两分支之前先断历史无幻影。
#      409 的**确定性**覆盖在 AIE2E-D-13（可控时序），本用例不再赌时序。
#   ③ 取消该轮 → 读到归档终帧 done{cancelled:true}（事件驱动等待）→ 同会话再发一次（同口径两果）。
#   抗抖动（M1 收尾项⑦，本批改口径）：
#     - fixture 仍用"从未发布过的导入任务"（避免真模型据此拒绝再次调用 start_publish）；
#     - **不再**对"模型未调用工具"做换会话重试，也不再 sleep 后重发 —— 挂不上确认门就如实红
#       （重试会把时序两果糊成绿，与本批 AR-04 口径相悖）。
# ============================================================
Say '--- TC18 会话隔离 / 并发发轮两果（AR-04） / 取消后终态与再发 ---'
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

    # ② 串行化面（AR-04 两果都合法，见下方段注释）：fixture = 全新导入任务
    #    （预检查通过 + 已导入待发布，从未发布过；真模型据此不会拒绝再发布、挂得到确认门）
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
    # 挂起轮的用户原文带唯一 token：数据库完整历史端点已移除 ⇒ 收敛后按 Redis 记忆窗口对账"恰一条"，
    # token 是稳定身份（不用任务号这类短数字通配，见 Q-08 的历史教训）。
    $askToken18 = 'tc18-first-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $ask18 = ("【TC18】turn-text {0} 请立即调用 start_publish 工具发布导入任务 {1}（系统会自动弹出确认卡片，无需再询问我）" -f $askToken18, $t18)
    # ② 串行化面（AR-04 两果都合法）：会话 C 挂起在确认门时同会话再发 —— 409（门仍被占）与 200
    #    （park 已释放门）都是现行语义下的合法结果。旧写法是"2 次尝试凑一个挂起 + Start-Sleep 后
    #    反复重发直到 200"，那种重试把两果都糊成绿、也把偶发红藏起来 ⇒ 本批去掉：只发一次，不 sleep、
    #    不重试；幻影轮次（帧面）先断，再按分支断状态面（409 确定性覆盖见 AIE2E-D-13），
    #    最后用收敛后的窗口（/history/{sid}）对账用户原文"每次受理恰一条 / 未受理零条"。
    $sc = 's5b-' + [Guid]::NewGuid().ToString('N')
    $frames18 = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sc; message = $ask18; context = $ctx18 } `
        @('confirm_request', 'done', 'error') $frames18 $null
    $cr18 = @($frames18 | Where-Object { $_.type -eq 'confirm_request' })
    if ($cr18.Count -lt 1) { throw '未挂起到确认门（TC18 串行化面的现场不成立；不重试）' }
    $runId18 = $cr18[0].runId

    # 判据载体（复审 N2）：正向断言一律走 run 维度（/runs/{runId} 的 snapshot+historyRoles），
    # 因为 Redis 窗口是 convergeMemory 的**整窗覆盖**，会被后续"取消挂起轮"的收敛改写；
    # 窗口只做反向断言（未受理 token 零条）与挂起轮收敛后的正控。
    # 先等挂起落定（快照已是 SUSPENDED + 含 assistant(tool_calls)），否则 run 维度的尾部形态还不是落定态。
    $null = Ai-WaitStatus $runId18 @('SUSPENDED') 20
    $st18p = @($frames18 | Where-Object { $_.type -eq 'start' })
    if ($st18p.Count -ne 1) { throw "TC18 挂起轮 start 帧 $($st18p.Count) 个（期望恰 1；幻影判据无对象）" }
    if ([string]$st18p[0].runId -ne [string]$runId18) {
        throw "TC18 挂起轮 start.runId=$($st18p[0].runId) ≠ confirm_request.runId=$runId18（轮次身份错配）"
    }
    $parkInfo18 = Ai-RunRoleInfo $runId18
    Ai-AssertSingleTurnTail $parkInfo18 'TC18 挂起轮'

    $token18 = 'tc18-second-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $busy = Ai-ChatSse $sc ('【TC18】turn-text ' + $token18 + ' 在吗') @{ page = 'tasks' } @('done', 'error') $null
    $busyStatus = [int]$busy.status

    # 幻影轮次（先断，与状态码无关）：未受理（非 200）的发送一个 SSE 帧都不许产出；
    # 受理轮 = 真的开了独立一轮（start/终帧/runId 不复用在下面分支断言）。
    if ($busyStatus -ne 200 -and @($busy.frames).Count -ne 0) {
        throw "TC18 未受理的发送（HTTP $busyStatus）却产出了 $(@($busy.frames).Count) 个 SSE 帧（幻影轮次）"
    }
    # 历史不污染（run 维度）：并发发送不得改写挂起轮自己的快照（状态/消息数/角色序列逐项不变）。
    Ai-AssertRunUnchanged $parkInfo18 'TC18 并发发送后'

    # 状态面
    if ($busyStatus -eq 409) {
        if (-not $busy.json) { throw "TC18 409 无 JSON 体（$($busy.text)）" }
        if ([string]$busy.json.code -ne 'SESSION_BUSY') { throw "并发响应 HTTP 409/code=$($busy.json.code)，期望 SESSION_BUSY" }
        if ([string]$busy.json.runId -ne [string]$runId18) { throw "409 未携带进行中 runId（$($busy.json.runId) ≠ $runId18）" }
        if ([string]$busy.json.reattach -ne "/api/ai/events/$runId18") { throw "409 的 reattach 端点异常: $($busy.json.reattach)" }
        if (@($busy.frames).Count -ne 0) { throw "TC18 被拒请求却产出了 $(@($busy.frames).Count) 个 SSE 帧" }
    }
    elseif ($busyStatus -eq 200) {
        $st18 = @($busy.frames | Where-Object { $_.type -eq 'start' })
        if ($st18.Count -ne 1) { throw "TC18 受理轮 start 帧 $($st18.Count) 个（期望恰 1）" }
        if ((@($busy.frames | Where-Object { $_.type -eq 'done' })).Count -ne 1) { throw 'TC18 受理轮 done 帧不恰 1 个' }
        if ((@($busy.frames | Where-Object { $_.type -eq 'error' })).Count -ne 0) { throw 'TC18 受理轮出现 error 帧' }
        if ([string]$st18[0].runId -eq [string]$runId18) { throw 'TC18 受理轮复用了挂起轮的 runId（两路共写一条流）' }
        # 受理恰一次（run 维度）：本轮快照尾部必须恰为 USER + ASSISTANT 各一条。
        $busyInfo18 = Ai-RunRoleInfo ([string]$st18[0].runId)
        Ai-AssertSingleTurnTail $busyInfo18 'TC18 并发受理轮'
        $ids18b = Ai-AssistantIds $busy.frames
        if ($ids18b.Count -eq 0) { throw 'TC18 并发受理轮没有任何 assistant 消息身份（判据无对象，不得当通过）' }
        if ($ids18b.Count -ne 1) {
            throw "TC18 并发受理轮 assistant messageId 有 $($ids18b.Count) 个（同一条消息被拆成多身份）：$($ids18b -join ',')"
        }
    }
    else {
        throw "TC18 同会话并发发轮 HTTP $busyStatus（只接受 200 受理 / 409 忙回执两果；$($busy.text)）"
    }

    # ③ 取消 → 终态（事件驱动等待：读到归档 done 即返回，不 sleep、不轮询重发）→ 同会话再发一次（两果都合法）
    $c18 = PostJsonRaw "/api/ai/cancel/$runId18" $null
    if (-not $c18.cancelled) { throw '取消未生效' }
    $end18 = New-Object System.Collections.ArrayList
    Read-Sse "/api/ai/events/$runId18`?lastSeq=0" $null @('done', 'error') $end18 $null
    $done18 = @($end18 | Where-Object { $_.type -eq 'done' })
    if ($done18.Count -ne 1) { throw "TC18 取消后归档终帧 $($done18.Count) 个（期望恰 1，读到即止）" }
    if ($done18[0].cancelled -ne $true) { throw "TC18 取消终帧未带 cancelled=true（实际 $($done18[0].cancelled)）" }
    $cur18 = GetJson "/api/ai/runs/current?sessionId=$sc"
    if ($cur18.found -and $cur18.snapshotStatus -eq 'SUSPENDED') { throw 'TC18 取消后 runs/current 仍报 SUSPENDED（挂起态未收敛）' }
    # 挂起轮取消收敛的正控（窗口此刻只被"挂起轮自己的 snapshot"覆写过）：该轮用户原文恰 1 条。
    $null = Ai-WaitWindowToken $sc $askToken18 20
    $parkWinHits18 = @((Ai-Window $sc) | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$askToken18*" })
    if ($parkWinHits18.Count -ne 1) {
        throw "TC18 挂起轮取消收敛后，窗口里有 $($parkWinHits18.Count) 条该轮用户消息（期望 1）"
    }
    # 取消会合法追加 stopped 消息；此后的"旧段不被改写"以取消后的快照为新基线。
    $parkCancelledInfo18 = Ai-RunRoleInfo $runId18

    $token18c = 'tc18-again-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $again = Ai-ChatSse $sc ('【TC18】turn-text ' + $token18c + ' 取消后同会话再发一次') @{ page = 'tasks' } @('done', 'error') $null
    $againStatus = [int]$again.status
    # 幻影轮次（先断）：未受理（非 200）的发送一个 SSE 帧都不许产出；受理轮的 run 维度身份在下面分支断言。
    if ($againStatus -ne 200 -and @($again.frames).Count -ne 0) {
        throw "TC18 取消后再发未受理（HTTP $againStatus）却产出了 $(@($again.frames).Count) 个 SSE 帧（幻影轮次）"
    }
    # 历史不污染（run 维度）：再发不得改写已取消轮的快照。
    Ai-AssertRunUnchanged $parkCancelledInfo18 'TC18 取消后再发'
    if ($againStatus -eq 200) {
        $st18c = @($again.frames | Where-Object { $_.type -eq 'start' })
        if ($st18c.Count -ne 1) { throw "TC18 取消后再发 start 帧 $($st18c.Count) 个（期望恰 1）" }
        if ((@($again.frames | Where-Object { $_.type -eq 'done' })).Count -ne 1) { throw 'TC18 取消后再发缺 done 帧' }
        if ((@($again.frames | Where-Object { $_.type -eq 'error' })).Count -ne 0) { throw 'TC18 取消后再发出现 error 帧' }
        if ([string]$st18c[0].runId -eq [string]$runId18) { throw 'TC18 取消后再发复用了已取消轮的 runId' }
        $againInfo18 = Ai-RunRoleInfo ([string]$st18c[0].runId)
        Ai-AssertSingleTurnTail $againInfo18 'TC18 取消后再发受理轮'
        $ids18c = Ai-AssistantIds $again.frames
        if ($ids18c.Count -eq 0) { throw 'TC18 取消后再发受理轮没有任何 assistant 消息身份（判据无对象，不得当通过）' }
        if ($ids18c.Count -ne 1) {
            throw "TC18 取消后再发受理轮 assistant messageId 有 $($ids18c.Count) 个（同一条消息被拆成多身份）：$($ids18c -join ',')"
        }
    }
    elseif ($againStatus -eq 409) {
        if (-not $again.json) { throw "TC18 取消后再发 409 无 JSON 体（$($again.text)）" }
        if ([string]$again.json.code -ne 'SESSION_BUSY') { throw "TC18 取消后再发 HTTP 409/code=$($again.json.code)，期望 SESSION_BUSY" }
        if ([string]$again.json.runId -ne [string]$runId18) { throw "TC18 取消后再发的 409 runId=$($again.json.runId) ≠ $runId18" }
        if ([string]$again.json.reattach -ne "/api/ai/events/$runId18") { throw "TC18 取消后再发的 409 reattach=$($again.json.reattach)" }
    }
    else {
        throw "TC18 取消后再发 HTTP $againStatus（只接受 200 放行 / 409 忙回执两果；$($again.text)）"
    }

    # ── 窗口反向断言（只断"未受理 = 零条"；正向判据已在 run 维度断完，窗口会被后续整窗收敛覆盖）──
    $win18 = Ai-Window $sc
    if ($busyStatus -ne 200) {
        $busyHits18 = @($win18 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$token18*" })
        if ($busyHits18.Count -ne 0) { throw "TC18 未受理的发送却在窗口里落了 $($busyHits18.Count) 条用户消息（幻影气泡）" }
    }
    if ($againStatus -ne 200) {
        $againHits18 = @($win18 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$token18c*" })
        if ($againHits18.Count -ne 0) { throw "TC18 取消后再发未受理，却在窗口里落了 $($againHits18.Count) 条用户消息（幻影气泡）" }
    }
    Ok 'TC18 会话记忆隔离 + 并发发轮两果（409 忙回执 runId/reattach 可接管 / 200 独立轮 done 无 error）+ 取消后终态与再发'
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
    # park 没有人工等待线程：决策应走有界续跑派发，不能声称唤醒了进程内等待方。
    if ($conf23.resumeTriggered -ne $true -or $conf23.resumeOutcome -ne 'SCHEDULED') {
        throw "TC23 park 下应由决策触发有界续跑，实际 resumeTriggered=$($conf23.resumeTriggered)/$($conf23.resumeOutcome)"
    }
    if ($conf23.wokeInProcessGate -ne $false) { throw 'TC23 park 语义下不应声称唤醒进程内等待方（没有等待线程）' }

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
# 批① · 桩模式 131 用例（AIE2E-*；设计稿 docs/research/设计-AI对话E2E用例全集-2026-10-22.md）
#
# 分层口径（设计稿 §4.1 的一次分配，逐条对账见本节末尾 MANIFEST）：
#   154 = 批① 88（本节，桩下可确定性复现）+ 批② 10（nightly 真模型，本仓只登记）
#         + 批③ 41（R-01..R-18 的 DOM 面、P-01..P-13 的卡面、G-07/G-18、Q-01/Q-03/Q-07、
#                   C-01/C-02、K-09、T-06/T-12；需 Playwright，待裁）
#         + 批④ 7（M-01..M-06/M-10；harness）+ 收编 8（G-03/G-05/G-06/D-12/C-03..C-06 已由
#                   既有 GF3/GF5/GF6/CTX-1..4 断言承载，不重复施工）
#   ⇒ 批① 的名单 = 154 − 41 − 10 − 7 − 8 = 88，P0/P1/P2 = 12/50/26（与 §2 逐行 P 列同源）。
#   本笔追加（挂账 L-21 销记）：T-13 聚合卡 / T-14 条件更新卡两条表格主用例 = 设计稿之外新增，
#   88 → 90、P1 50 → 52；两条都只断言**协议半段**（工具帧 / 挂起 / 回灌 / 收敛 / 审计计数），
#   卡面 DOM 与导出字节仍归批③（DOM 在桩里不存在，不以接口级断言冒充）。
#   此后逐批追加（V1 34 / V2 4 / V5 2 / AR-04 的 D-13 1）把名单推到 131，逐条推导见本节末尾
#   MANIFEST 上方的注释；本节标题与 runner 断言的数字以 MANIFEST 为准。
#
# 三条硬约束（设计稿 §4.2）：
#   ① 不新建脚本：全部进本文件，沿用 Ok()/No() 计数体系；
#   ② 桩开关全走提示词标记（与 usage-shape / --violate-form-type 同族），未带标记的请求帧
#      逐字节不变 ⇒ 既有 35 条零影响；标记清单的唯一取数点 = scripts/ci/stub-routes.json 的 faults.switches；
#   ③ 桩下零 SKIP：本节**不调用 Gf-Skip**，每条用例必须给出 PASS 或 FAIL（runner 末尾断言
#      executed=131 / skip=0，名单与逐域计数同 MANIFEST）。
#
# 断言与修法解耦（设计稿 §0 红线）：断言点只落在「用户能观察到的事实」上——
#   帧序 / 端点契约 / 归档可发现面 / 幂等码，不落任何实现细节（不读前端 store、不假设某个修法）。
#   例：D-08 只断言 409 携 runId + reattach，不预设「/runs/current 对 RUNNING 也写索引」这一修法。
#
# 阈值一律从产品键派生（不自造数字）：first-byte / inter-event / total-budget 读 application.yml，
#   usage 形状读 stub-routes.json 的 usageShapes，前端工具超时读 GET /api/ai/health。
# ============================================================

$script:aiCases = New-Object System.Collections.ArrayList
function Add-AiCase($id, $p, $title, $body) {
    $script:aiCases.Add(@{ id = $id; p = $p; title = $title; body = $body }) | Out-Null
}
function Ai-NewSid($tag) { return ('aie2e-' + $tag + '-' + [Guid]::NewGuid().ToString('N')) }
# 恒返回数组（逗号运算符）：返回单元素时 PowerShell 会把 `@(...)` 拆包成标量，
# 而 PSCustomObject 没有 .Count ⇒ `(Ai-F ...).Count` 变 $null，"恰 1 个" 的断言与 Ai-One 会双双失效。
function Ai-F($frames, [string[]]$types) { return ,@($frames | Where-Object { $types -contains $_.type }) }
function Ai-One($frames, $type) { $a = Ai-F $frames @($type); return $(if ($a.Count -gt 0) { $a[0] } else { $null }) }
function Ai-RunId($frames) {
    $s = Ai-One $frames 'start'
    if (-not $s -or -not $s.runId) { throw '未收到 start 帧（runId 缺失）' }
    return $s.runId
}
function Ai-Done($frames) {
    $d = Ai-F $frames @('done')
    if ($d.Count -ne 1) { throw "done 帧 $($d.Count) 个（期望恰 1）" }
    return $d[0]
}
function Ai-SeqStrict($frames) {
    $prev = 0
    foreach ($f in @($frames | Where-Object { $null -ne $_.seq })) {
        $s = [int]$f.seq
        if ($s -le $prev) { throw "seq 未严格递增：$prev → $s（帧序有洞/回退）" }
        $prev = $s
    }
}
function Ai-DoneIsLast($frames) {
    $list = @($frames)
    for ($i = 0; $i -lt $list.Count; $i++) {
        if ($list[$i].type -eq 'done' -and $i -lt $list.Count - 1) {
            throw "done 之后仍有 $($list.Count - 1 - $i) 个帧（终帧不是最后帧）"
        }
    }
}
function Ai-Text($frames) { return (($frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text }) -join '') }
# 安全面探针取证（X-02 / X-03，L-137）：把**实收 delta 分片**逐条落档到 -ArtifactDir（仓库外），
# 供"上游确实把探针切开并发出"的事后复核 —— 帧是上游 chunk 经产品 SSE 的中继，切点即上游切点。
# `$needle` = 探针本体（假密钥 / PEM body），落档里标出哪些分片含它的片段。
function Ai-SafetyDump($case, $frames, $needle) {
    if (-not $script:gfArtifactDir) { return }
    try {
        $pieces = @($frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text })
        $joined = ($pieces -join '')
        $at = $joined.IndexOf($needle)
        $enc = New-Object System.Text.UTF8Encoding($false)
        $lines = New-Object System.Collections.ArrayList
        $lines.Add(("# {0} —— 上游探针实证：实收 delta 分片（按到达顺序；拼回后 needle 起始偏移={1}）" -f $case, $at)) | Out-Null
        $offset = 0
        $hit = 0
        for ($i = 0; $i -lt $pieces.Count; $i++) {
            $end = $offset + $pieces[$i].Length
            $touches = ($at -ge 0 -and $offset -lt ($at + $needle.Length) -and $end -gt $at)
            if ($touches) { $hit++ }
            $lines.Add(("chunk[{0,2}] off={1,-4} len={2,-4} touchesNeedle={3}`n{4}" -f $i, $offset, $pieces[$i].Length, $touches, $pieces[$i])) | Out-Null
            $offset = $end
        }
        [IO.File]::WriteAllLines((Join-Path $script:gfArtifactDir "${case}-safety-probe.txt"), $lines.ToArray(), $enc)
        Write-Host ("  [ART] {0}-safety-probe.txt 已落盘（分片 {1} 个，含探针片段 {2} 片）" -f $case, $pieces.Count, $hit) -ForegroundColor DarkGray
    } catch {
        Write-Host ("  [WARN] 安全面取证落盘失败（{0}）：{1}" -f $case, $_.Exception.Message) -ForegroundColor Yellow
    }
}
function Ai-MaxSeq($frames) {
    $m = 0
    foreach ($f in @($frames | Where-Object { $null -ne $_.seq })) { if ([int]$f.seq -gt $m) { $m = [int]$f.seq } }
    return $m
}
function Ai-Run($runId) {
    $r = Invoke-Api 'GET' "/api/ai/runs/$runId" $null
    if (-not $r.ok -or -not $r.json -or -not $r.json.success) { throw "GET /api/ai/runs/$runId => HTTP $($r.status) $($r.text)" }
    return $r.json.data
}
# GET /api/ai/context/{sid}：AI 域的**裸对象**端点（与 /cancel、DELETE /history 同姿态，
# 见 AiController#context 的类注；前端 api/ai.ts 的 unwrapApiData 对两种形态都认）。
# 故这里不走 GetJson（那条路要求 success 信封），也无法用 .data 取值。
function Ai-Context($sid) {
    $r = Invoke-Api 'GET' "/api/ai/context/$sid" $null
    if (-not $r.ok -or $null -eq $r.json) { throw "GET /api/ai/context/$sid => HTTP $($r.status) $($r.text)" }
    return $r.json
}
# 施加停止的前置：快照已建立（AR-06 的同一段空窗，见上方 Ai-WaitCurrentRun 注）。
# start 帧先于「请求前压缩 → 建快照」发出；若取消落在这段空窗里，产品走早停分支，分两种：
#   · "首轮交棒前收到取消（不发起模型调用）" —— 仍会建快照（W-26/W-32 能断言 CANCELLED 即此）；
#   · "首轮压缩期间收到取消（不建快照、不发起模型调用）" —— 快照与归档都是空的，只发终帧。
# 后者下停止类判据（快照 CANCELLED / 归档 done{cancelled:true} / 条目终态）没有持久面可断言，
# 读到的是"(未读到)"。run 40708651817 的 W-25（首次压缩期间取消）即此。故停止一律等快照建立
# 后再施加：有界等待（上界取产品首包超时），空窗不收敛即 FAIL —— 各用例断言面一个字未动。
function Ai-WaitSnapshotReady($runId, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Api 'GET' "/api/ai/runs/$runId" $null
        if ($r.ok -and $r.json -and $r.json.success -and $r.json.data.snapshot) { return $r.json.data }
        Start-Sleep -Milliseconds 200
    }
    throw "${sec}s 内 /api/ai/runs/$runId 读不到 snapshot（AR-06 空窗未收敛：该轮还没建快照就被停，停止类判据没有持久面）"
}
function Ai-ChatRaw($sid, $msg, $ctx) {
    return Invoke-Api 'POST' '/api/ai/chat' @{ sessionId = $sid; message = $msg; context = $ctx }
}
function Ai-Events($runId, $lastSeq, [string[]]$stopOn) {
    $buf = New-Object System.Collections.ArrayList
    Read-Sse "/api/ai/events/$runId`?lastSeq=$lastSeq" $null $stopOn $buf $null
    return $buf
}
# ---------- 刷新恢复类用例的取数时点（AR-06：start 先于快照建立） ----------
# 首帧 start 前移到"请求前压缩"之前发出（AiController#events 的 AR-06 类注）⇒ 存在一段**合法窗口**：
# runId 已有、帧面已开、快照还没建。此刻两个持久面都还不完整 ——
#   · GET /runs/current           → 空态（found=false）；
#   · GET /events/{runId}         → 202 {preparing:true, retryAfterMs, elapsedMs}（AiController#preparing）。
# 这两处都不是被测行为本身（刷新恢复的判据是"快照建好后持久面报什么"），而是取数时点太早：
# "收到 start 就立刻读持久面"跑在空窗里，读到的是准备中，不是这一轮的恢复面。
# 故取数时点改为按产品自己的信号**有界等待**，上界由调用方传产品键派生值
# （app.ai.resilience.first-byte-timeout，见 Get-AiResilienceSeconds）——空窗不收敛即 FAIL，
# 不是放宽断言：断言面（runId / RUNNING / archiveMaxSeq / 帧集）一个字都没动。
function Ai-WaitCurrentRun($sid, $runId, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    while ($true) {
        $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
        if ($cur.found) { return $cur }
        if ((Get-Date) -ge $deadline) {
            throw "${sec}s 内 /runs/current 一直是空态（found=false，runId=$runId）：快照未建立，AR-06 空窗未收敛"
        }
        Start-Sleep -Milliseconds 300
    }
}
function Ai-EventsReady($runId, $lastSeq, [string[]]$stopOn, [int]$sec) {
    $deadline = (Get-Date).AddSeconds($sec)
    $tries = 0
    while ($true) {
        $buf = New-Object System.Collections.ArrayList
        $r = Read-SseCore "/api/ai/events/$runId`?lastSeq=$lastSeq" $null $stopOn $buf $null
        if (-not $r.ok) { throw "SSE /api/ai/events/$runId`?lastSeq=$lastSeq => HTTP $($r.status) $($r.text)" }
        # 202 是"准备中"，不是"回放零帧"：202 是 2xx，旧口径会让空 buf 冒充完整回放，
        # 用例于是在空窗里断言"重放没有 start"。按服务端给的 retryAfterMs 重挂，直到拿到真回放。
        if ([int]$r.status -ne 202) { return $buf }
        $tries++
        $body = $null
        if ($r.text) { try { $body = $r.text | ConvertFrom-Json } catch { $body = $null } }
        if (-not $body -or $null -eq $body.PSObject.Properties['retryAfterMs']) {
            throw "重挂 202 缺 retryAfterMs（准备中契约被破坏）：$($r.text)"
        }
        $waitMs = [int]$body.retryAfterMs
        if ($waitMs -lt 1) { throw "重挂 202 的 retryAfterMs=$waitMs（无法按契约重试）" }
        if ((Get-Date).AddMilliseconds($waitMs) -ge $deadline) {
            throw "${sec}s 内 /events/$runId 一直停在 202 准备中（重挂 $tries 次，最后 retryAfterMs=$waitMs，elapsedMs=$($body.elapsedMs)）"
        }
        Start-Sleep -Milliseconds $waitMs
    }
}
function Ai-Cancel($runId) {
    try { return PostJsonRaw "/api/ai/cancel/$runId" $null } catch { return @{ cancelled = $false } }
}
# 应用配置单点（application.yml 的 app.ai.resilience.*；脚本不写裸时间常量）
$script:AiYml = $null
function Get-AiResilienceSeconds($name) {
    if ($null -eq $script:AiYml) {
        $p = Join-Path $PSScriptRoot '../backend/src/main/resources/application.yml'
        if (-not (Test-Path $p)) { throw "application.yml 不可读（阈值唯一取数点）：$p" }
        $script:AiYml = Get-Content -Raw -Encoding UTF8 -Path $p
    }
    $m = [regex]::Match($script:AiYml, "(?m)^\s*" + [regex]::Escape($name) + ":\s*(\d+)s\s*$")
    if (-not $m.Success) { throw "application.yml 缺 app.ai.resilience.$name（阈值不能写死在脚本里）" }
    return [int]$m.Groups[1].Value
}
# 次数型键（max-attempts / max-tool-steps 之类，无单位）：同一个唯一取数点，但**不要求** `s` 后缀 ——
# 用读秒的 helper 读次数会造出"表里有键、脚本说缺键"的假红（2026-10-23 复跑实证）。
function Get-AiResilienceCount($name) {
    if ($null -eq $script:AiYml) {
        $p = Join-Path $PSScriptRoot '../backend/src/main/resources/application.yml'
        if (-not (Test-Path $p)) { throw "application.yml 不可读（阈值唯一取数点）：$p" }
        $script:AiYml = Get-Content -Raw -Encoding UTF8 -Path $p
    }
    $m = [regex]::Match($script:AiYml, "(?m)^\s*" + [regex]::Escape($name) + ":\s*(\d+)\s*$")
    if (-not $m.Success) { throw "application.yml 缺 app.ai.resilience.$name（阈值不能写死在脚本里）" }
    return [int]$m.Groups[1].Value
}
# 夹具：导出任务（start_export 的确认门）/ 待发布导入任务（start_publish 的确认门）
function Ai-ExportFixture($title) {
    $t = New-ExportTask $title @('CURRENCY')
    PutJson "/api/tasks/$t/step" @{ step = 'EXPORT' } | Out-Null
    return $t
}
function Ai-PublishFixture($title) {
    $t = New-ImportTask $title @('DOC_TYPE')
    Upload-File $t (Join-Path $tmp 'DOC_TYPE_e2e.xlsx') 'DOC_TYPE_ok.xlsx' | Out-Null
    $j1 = Start-JobOf $t 'PRECHECK'
    if ((Wait-Job $j1.id).status -ne 'COMPLETED') { throw '夹具前置：预检查未通过' }
    $j2 = Start-JobOf $t 'IMPORT'
    if ((Wait-Job $j2.id).status -ne 'COMPLETED') { throw '夹具前置：导入未通过' }
    PutJson "/api/tasks/$t/step" @{ step = 'PUBLISH' } | Out-Null
    return $t
}
# 通用"继续型"处理器（拒绝确认门 / 回灌前端工具），与既有 $script:continueHandler 同义，
# 单独一份是为了让本节用例可以拿到帧而不受既有用例的变量污染
$script:aiContinue = $script:continueHandler
$script:aiDeny = {
    param($f)
    if ($f.type -eq 'confirm_request') { return @{ approved = $false } }
    return @{ result = '{"ok":true,"source":"aie2e"}' }
}

Say '--- 批① · STRM 流式稳定（13 条） ---'

Add-AiCase 'S-01' 'P1' '纯文本流帧序完整（1 start / ≥1 delta / 1 done / seq 严格递增 / done 后零帧）' {
    $sid = Ai-NewSid 's01'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-01】turn-text 请只回复一句确认。' @{ page = 'tasks' } $script:aiContinue 2
    if ((Ai-F $turn.frames @('start')).Count -ne 1) { throw "start 帧 $((Ai-F $turn.frames @('start')).Count) 个（期望恰 1）" }
    if ((Ai-F $turn.frames @('delta')).Count -lt 1) { throw 'delta 帧 0 个（期望 ≥1）' }
    Ai-Done $turn.frames | Out-Null
    Ai-SeqStrict $turn.frames
    Ai-DoneIsLast $turn.frames
    if ((Ai-Text $turn.frames).Length -lt 20) { throw "正文过短（$((Ai-Text $turn.frames).Length) 字符），turn-text 蓝本未生效" }
    $ctxSnap = Ai-Context $sid
    if ([int]$ctxSnap.turnNo -ne 1) { throw "首轮后 turnNo=$($ctxSnap.turnNo)，期望 1（空回复轮不得推进轮次）" }
    return $null
}

Add-AiCase 'S-03' 'P2' '响应头挂起不误判断流（首包看门狗生效并给出可重试错误）' {
    $firstByte = Get-AiResilienceSeconds 'first-byte-timeout'
    $maxAttempts = Get-AiResilienceCount 'max-attempts'
    $sid = Ai-NewSid 's03'
    $window = $firstByte * $maxAttempts + 30
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $buf = New-Object System.Collections.ArrayList
    # stall-headers 必须**大于**首包超时，否则看门狗不会响
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = "【AIE2E-S-03】turn-text stall-headers=$($firstByte * 1000 + 5000) 请回复一句。" ; context = @{ page = 'tasks' } } `
        @('done', 'error') $buf $null
    $elapsed = $sw.Elapsed.TotalSeconds
    if ($elapsed -gt $window) { throw "建连看门狗未在 $(($firstByte * $maxAttempts))s 量级内收口（实测 ${elapsed}s，窗口 ${window}s）" }
    $term = @($buf | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
    if ($term.Count -lt 1) { throw '挂起响应头后既无 error 也无 done（用户会看到"已发送但无任何状态变化"）' }
    $e = $term[$term.Count - 1]
    $code = if ($e.type -eq 'error') { [string]$e.code } else { [string]$e.error }
    if ([string]::IsNullOrWhiteSpace($code)) { throw '终态缺少可行动错误码（无法提示"可重试"）' }
    return $null
}

Add-AiCase 'S-04' 'P0' '帧间静默：不把静默当完成（无 done、无伪造 delta，连接未被判死）' {
    $interEvent = Get-AiResilienceSeconds 'inter-event-timeout'
    $sid = Ai-NewSid 's04'
    $frames = New-Object System.Collections.ArrayList
    # 静默 > 帧间超时：产品必须由看门狗判定，而不是把静默当成正常收尾
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = "【AIE2E-S-04】turn-text stall-frames=$($interEvent * 1000 + 5000) 请回复一句。" ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    if ((Ai-F $frames @('start')).Count -ne 1) { throw '未收到 start 帧（runId 无法定位）' }
    $runId = Ai-RunId $frames
    $deltaN = (Ai-F $frames @('delta')).Count
    if ($deltaN -gt 1) { throw "静默期收到了 $deltaN 个 delta（桩只应发首帧后的静默）" }
    $term = @($frames | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
    if ($term.Count -eq 0) {
        # 未收终态 = 看门狗还在静默判定中：断言轮次可发现且未伪完成（不得出现"没有 done 的 DONE 态"）
        $r = Ai-Run $runId
        if ($r.snapshot.status -eq 'DONE') { throw "轮次状态 DONE 但没有 done 终帧（静默被当成正常收尾）" }
    } else {
        $last = $term[$term.Count - 1]
        if ($last.type -eq 'done' -and -not $last.cancelled -and [string]::IsNullOrWhiteSpace([string]$last.error)) {
            throw '静默后收到"正常" done（断流被误判为成功收尾）'
        }
    }
    Ai-Cancel $runId | Out-Null
    return $null
}

Add-AiCase 'S-05' 'P0' '重连差量续收不重复（lastSeq 续收、正文不重不漏、done 只 1 次）' {
    $sid = Ai-NewSid 's05'
    # 断流形态 = **客户端**在收到首帧后主动断开（设计稿 Q10/S-05：断开 → 带 lastSeq 重连续收）。
    # 不用桩的 break-at-seq 来模拟：那是**上游**断流（桩销毁上游 socket，产品会重试并落 error），
    # 与"客户端走开、后端继续把这一轮跑完"是两种语义（后者见 S-06）。
    $live = Read-Sse-Brief-Post '/api/ai/chat' `
        @{ sessionId = $sid; message = '【AIE2E-S-05】turn-text 客户端断开后带 lastSeq 重连续收。' ; context = @{ page = 'tasks' } } 8000 3
    $runId = Ai-RunId $live
    $lastSeq = Ai-MaxSeq $live
    if ($lastSeq -lt 1) { throw "断流前一个带 seq 的帧都没有（lastSeq=$lastSeq，差量续收无从谈起）" }
    if (@($live | Where-Object { $_.type -eq 'done' }).Count -ne 0) { throw '首段就读到了 done（断流没发生，续收断言无对象）' }
    # 后端不因客户端断开而取消：本轮必须在断连后自行收敛
    Ai-WaitStatus $runId @('DONE', 'FAILED', 'CANCELLED') 30 | Out-Null
    $replay = Ai-Events $runId $lastSeq @('done', 'error')
    $stale = @($replay | Where-Object { $null -ne $_.seq -and [int]$_.seq -le $lastSeq })
    if ($stale.Count -gt 0) { throw "差量续收重放了 $($stale.Count) 个 seq ≤ lastSeq 的旧帧" }
    if (@($replay | Where-Object { $_.type -eq 'done' }).Count -lt 1) { throw '续收段没有 done（差量续收收不到终态，用户停在"工作中…"）' }
    $dones = @($live | Where-Object { $_.type -eq 'done' }) + @($replay | Where-Object { $_.type -eq 'done' })
    if ($dones.Count -ne 1) { throw "两段合计 done 帧 $($dones.Count) 个（期望恰 1，重复收尾会二次写库）" }
    $joined = (Ai-Text $live) + (Ai-Text $replay)
    $hist = GetJson "/api/ai/history/$sid"
    $final = (@($hist.messages | Where-Object { $_.type -eq 'ASSISTANT' }) | ForEach-Object { [string]$_.text }) -join ''
    if ($joined.Length -eq 0) { throw '实时段与续收段的 delta 全为空（正文丢失）' }
    if ($final.Length -gt 0 -and $joined.Length -gt $final.Length) {
        throw "续收拼接后正文 $($joined.Length) 字符 > 归档 $($final.Length) 字符（重复段落）"
    }
    return $null
}

Add-AiCase 'S-06' 'P0' '上游中途断流（TCP 断）：done 前有终态错误、正文保留、可继续发下一条' {
    $sid = Ai-NewSid 's06'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-S-06】turn-text kill-stream=1200 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $runId = Ai-RunId $frames
    $term = @($frames | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
    if ($term.Count -lt 1) { throw '上游断流后前端收不到任何终态（用户停在"工作中…"）' }
    $final = Ai-WaitStatus $runId @('DONE', 'FAILED', 'CANCELLED') 30
    $snap = $final.snapshot
    if ($snap.status -eq 'FAILED' -and [string]::IsNullOrWhiteSpace([string]$snap.error)) {
        throw '轮次 FAILED 但快照里没有 error（失败不可归因）'
    }
    # 页面可继续发下一条：新会话同轮不得被旧轮锁住
    $next = Ai-ChatRaw $sid '继续：这是断流后的下一条消息' @{ page = 'tasks' }
    if (-not $next.ok) { throw "断流后同会话再发 => HTTP $($next.status) $($next.text)" }
    return $null
}

Add-AiCase 'S-07' 'P1' '上游 5xx 重试退避：尝试次数有界、成功只 1 个 done' {
    $maxAttempts = Get-AiResilienceCount 'max-attempts'
    $sid = Ai-NewSid 's07'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-S-07】turn-text fail-503-once 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $done = Ai-Done $frames
    if ($done.type -eq 'error') { throw '一次 503 后未能重试成功（首请求失败即放弃）' }
    $runId = Ai-RunId $frames
    $r = Ai-Run $runId
    $attempts = [int]$r.snapshot.attempts
    if ($attempts -gt $maxAttempts) { throw "attempts=$attempts 超过 max-attempts=$maxAttempts（重试未收口）" }
    if ($attempts -lt 2) { throw "attempts=$attempts，期望 ≥2（说明没有重试就成功了——503 注入没生效）" }
    return $null
}

Add-AiCase 'S-08' 'P1' '流式 usage 末帧去重：done.usage 与形状表逐值相等、frames 与实测帧数一致' {
    $sid = Ai-NewSid 's08'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-08】turn-text usage-shape=last 请回复一句。' @{ page = 'tasks' } $script:aiContinue 2
    $done = Ai-Done $turn.frames
    $shapes = Get-CtxShapes
    $want = $shapes.last
    $u = $done.usage
    if ($null -eq $u) { throw 'done 帧缺 usage' }
    if ([int]$u.promptTokens -ne [int]$want.promptTokens -or [int]$u.completionTokens -ne [int]$want.completionTokens -or [int]$u.totalTokens -ne [int]$want.totalTokens) {
        throw "done.usage=$($u.promptTokens)/$($u.completionTokens)/$($u.totalTokens)，期望 $($want.promptTokens)/$($want.completionTokens)/$($want.totalTokens)"
    }
    $deltaN = (Ai-F $turn.frames @('delta')).Count
    if ($deltaN -lt 1) { throw 'delta 帧 0 个（frames 计数无从对照）' }
    if ([int]$u.frames -lt 1) { throw "usage.frames=$($u.frames)，期望 ≥1（末帧去重把真值也丢了）" }
    $snap = Ai-Context $sid
    if ([int]$snap.usedTokens -ne [int]$u.promptTokens) {
        throw "端点 usedTokens=$($snap.usedTokens) ≠ done.usage.promptTokens=$($u.promptTokens)（三方不同源）"
    }
    return $null
}

Add-AiCase 'S-09' 'P1' '前端工具不回流：超时后卡收敛为终态、轮次继续到 done' {
    $health = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $health.ok) { throw "GET /api/ai/health => HTTP $($health.status)" }
    $budget = [int]$health.json.frontendToolTimeoutSeconds
    if ($budget -lt 1) { throw "health.frontendToolTimeoutSeconds=$budget（超时预算未下发）" }
    $t = Ai-ExportFixture 'AIE2E-S-09-前端工具'
    $sid = Ai-NewSid 's09'
    $frames = New-Object System.Collections.ArrayList
    $ctx = @{ page = 'tasks'; taskId = $t; taskType = 'EXPORT'; step = 'SELECT_DEFS' }
    # 首轮读到 frontend_tool_request 即断开（等价"客户端不回灌"，fe-tool-hang 的脚本层形态）
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请打开任务 {0} 的导出向导" -f $t); context = $ctx } `
        @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（前端工具通道未触发）' }
    $runId = Ai-RunId $frames
    $entry = $null
    $deadline = (Get-Date).AddSeconds($budget + 30)
    while ((Get-Date) -lt $deadline) {
        $p = Invoke-Api 'GET' "/api/ai/pending/$runId" $null
        if ($p.ok -and $p.json.data) {
            $entry = @($p.json.data | Where-Object { $_.toolCallId -eq $req.toolCallId })[0]
            if ($entry -and $entry.status -ne 'PENDING') { break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $entry) { throw 'pending 里没有该 toolCallId 的条目' }
    if ($entry.status -eq 'PENDING') { throw "前端工具 ${budget}s 后仍是 PENDING（超时未收敛）" }
    # 轮次必须继续（不是整轮挂死）：重挂能看到 done
    $after = Ai-Events $runId 0 @('done', 'error')
    if ((Ai-F $after @('done')).Count -lt 1) { throw '工具超时后整轮未收尾（没有 done 帧）' }
    return $null
}

Add-AiCase 'S-10' 'P2' '工具步数到顶：连续调用被收口，轮次仍能收尾' {
    $sid = Ai-NewSid 's10'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-10】tool-loop=26 请连续调用工具。' @{ page = 'tasks' } $script:aiContinue 30
    $runId = Ai-RunId $turn.frames
    $r = Ai-Run $runId
    $steps = [int]$r.snapshot.toolSteps
    if ($steps -gt 26) { throw "toolSteps=$steps 超过桩注入的 26 次（步数闸未生效）" }
    $starts = (Ai-F $turn.frames @('tool_start')).Count
    if ($starts -gt 26) { throw "tool_start 帧 $starts 个 > 26（请求风暴）" }
    if ((Ai-F $turn.frames @('done')).Count -lt 1 -and (Ai-F $turn.frames @('error')).Count -lt 1) {
        throw '步数收口后轮次未收尾（既无 done 也无 error）'
    }
    return $null
}

Add-AiCase 'S-11' 'P1' '挂起等待不计入段预算：挂起→决策→续跑仍能正常收尾' {
    $t = Ai-PublishFixture 'AIE2E-S-11-预算'
    $sid = Ai-NewSid 's11'
    $runId = $null
    $callId = $null
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（start_publish 未被调用）' }
    $runId = $cr.runId
    $callId = $cr.toolCallId
    Start-Sleep -Seconds 3   # 挂起停留（gateWait 的观察窗）
    $conf = Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $runId; toolCallId = $callId; approved = $false; reason = 'aie2e-s11' }
    if (-not $conf.ok) { throw "POST /api/ai/confirm => HTTP $($conf.status) $($conf.text)" }
    $after = Ai-Events $runId 0 @('done', 'error')
    $term = @($after | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
    if ($term.Count -lt 1) { throw '决策后重挂未收到终态（挂起等待吃掉了段预算）' }
    $r = Ai-Run $runId
    if ($r.snapshot.status -notin @('DONE', 'CANCELLED', 'FAILED')) { throw "决策后轮次状态 $($r.snapshot.status)" }
    return $null
}

Add-AiCase 'S-12' 'P0' '迟到帧闸门：终帧之后不再有业务帧、不回退卡状态' {
    $sid = Ai-NewSid 's12'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-12】turn-text 请回复一句。' @{ page = 'tasks' } $script:aiContinue 2
    $runId = Ai-RunId $turn.frames
    Ai-DoneIsLast $turn.frames
    $max = Ai-MaxSeq $turn.frames
    # 归档末号之后不得再有业务帧被补发（迟到帧的唯一入口是差量重挂）
    $brief = Read-Sse-Brief "/api/ai/events/$runId`?lastSeq=$max" 3000
    $late = @($brief | Where-Object { $_.type -in @('delta', 'tool_start', 'tool_result', 'confirm_request') })
    if ($late.Count -gt 0) { throw "done 之后又收到 $($late.Count) 个业务帧（迟到帧闸门失效）" }
    return $null
}

Add-AiCase 'S-13' 'P2' '单帧 64KB 上限不破坏 JSON：大帧轮可解析且正文不丢' {
    $sid = Ai-NewSid 's13'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-13】turn-text huge-frame=102400 请回复一句。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $turn.frames | Out-Null
    $text = Ai-Text $turn.frames
    if ($text.Length -lt 4096) { throw "正文只有 $($text.Length) 字符（大帧被整帧丢弃）" }
    # 解析面：本节的帧全部经 ConvertFrom-Json 还原，null 帧即 JSON 破损
    $nulls = @($turn.frames | Where-Object { $null -eq $_ }).Count
    if ($nulls -gt 0) { throw "有 $nulls 个帧解析失败（帧被腰斩成非法 JSON）" }
    $hist = GetJson "/api/ai/history/$sid"
    $final = (@($hist.messages | Where-Object { $_.type -eq 'ASSISTANT' }) | ForEach-Object { [string]$_.text }) -join ''
    if ($final.Length -lt 4096) { throw "归档正文 $($final.Length) 字符，期望 ≥4096（大帧未落库）" }
    return $null
}

Add-AiCase 'S-14' 'P2' '慢订阅者隔离：两路订阅各自拿到完整帧集、首帧不被拖慢' {
    $sid = Ai-NewSid 's14'
    $turn = Invoke-AiTurn $sid '【AIE2E-S-14】turn-text 请回复一句。' @{ page = 'tasks' } $script:aiContinue 2
    $runId = Ai-RunId $turn.frames
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $first = Read-Sse-Brief "/api/ai/events/$runId`?lastSeq=0" 5000
    $firstMs = $sw.ElapsedMilliseconds
    $second = Ai-Events $runId 0 @('done', 'error')
    if ($first.Count -lt 1) { throw '第一路订阅未在窗口内拿到任何帧' }
    if ($firstMs -gt 2000) { throw "第一路首帧延迟 ${firstMs}ms > 2000ms（订阅被拖慢）" }
    $a = (@($first | Where-Object { $null -ne $_.seq } | ForEach-Object { [int]$_.seq }) | Sort-Object) -join ','
    $b = (@($second | Where-Object { $null -ne $_.seq } | ForEach-Object { [int]$_.seq }) | Sort-Object) -join ','
    if ($a -ne $b) { throw '两路订阅拿到的 seq 集合不同（慢订阅者拖累了投递或有丢帧）' }
    return $null
}

Say '--- 批① · STP 手工停止（1 条接口级）与 MUL 多实例（3 条接口级） ---'

Add-AiCase 'P-14' 'P1' '停止后台账一致：runs / pending / turn_end 同向 CANCELLED' {
    $t = Ai-PublishFixture 'AIE2E-P-14-停止台账'
    $sid = Ai-NewSid 'p14'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（停止台账无从对照）' }
    $runId = $cr.runId
    $cancel = Ai-Cancel $runId
    if (-not $cancel.cancelled) { throw 'POST /api/ai/cancel 未返回 cancelled=true' }
    $r = Ai-WaitStatus $runId @('CANCELLED') 15
    if ($r.snapshot.status -ne 'CANCELLED') { throw "runs/{runId} 状态 $($r.snapshot.status)，期望 CANCELLED" }
    $pend = Invoke-Api 'GET' "/api/ai/pending/$runId" $null
    if (-not $pend.ok) { throw "GET /api/ai/pending => HTTP $($pend.status)" }
    $open = @($pend.json.data | Where-Object { $_.status -eq 'PENDING' })
    if ($open.Count -gt 0) { throw "取消后仍有 $($open.Count) 个条目停在 PENDING（停止未收敛挂起项）" }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($cur.found -and $cur.snapshotStatus -eq 'SUSPENDED') { throw '取消后 runs/current 仍报 SUSPENDED（可发现面说谎）' }
    return $null
}

Add-AiCase 'M-07' 'P2' '多页签降级语义（AR-04 两果都合法）：在途轮占门时第二路被 409 拦；park 释放门的窗口里放行时两路各是独立轮' {
    # AR-04：park 后会话门由驱动线程 finally 释放，「挂起」不等于「占门」（窗口无读侧同步信号）⇒
    # 第二路发轮 409 / 200 都合法。本用例两果都断：
    #   · 409 ⇒ 第二路没有开轮（零 SSE 帧）+ 忙回执码；
    #   · 200 ⇒ 第二路开的是**独立轮**（runId 不等于在途轮，start/done 齐、无 error 帧）。
    # 409 的确定性覆盖见 D-13（可控时序窗口）。
    $sid = Ai-NewSid 'm07'
    $t = Ai-PublishFixture 'AIE2E-M-07-多页签'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（两页签同会话的现场不成立）' }
    $null = Ai-WaitStatus $cr.runId @('SUSPENDED') 20
    $second = Ai-ChatSse $sid '【AIE2E】turn-text 另一个页签也在问一句' @{ page = 'tasks' } @('done', 'error') $null
    if ([int]$second.status -eq 409) {
        if (-not $second.json) { throw "409 无 JSON 体（$($second.text)）" }
        if ([string]$second.json.code -ne 'SESSION_BUSY') { throw "409 码 $($second.json.code)，期望 SESSION_BUSY" }
        if (@($second.frames).Count -ne 0) { throw "第二路被拒却产出了 $(@($second.frames).Count) 个 SSE 帧（两路会在同一条流上写）" }
    }
    elseif ([int]$second.status -eq 200) {
        if ([string](Ai-RunId $second.frames) -eq [string]$cr.runId) { throw '第二路复用了在途轮的 runId（两页签共写一条流）' }
        Ai-Done $second.frames | Out-Null
        if ((Ai-F $second.frames @('error')).Count -ne 0) { throw '放行轮出现 error 帧' }
    }
    else {
        throw "同会话第二路发轮 HTTP $($second.status)（只接受 409 拦 / 200 放行两果；$($second.text)）"
    }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'M-08' 'P1' '重连退避有界：断流后重连次数/间隔受预算约束，放弃要有显式终态' {
    $interEvent = Get-AiResilienceSeconds 'inter-event-timeout'
    $maxAttempts = Get-AiResilienceCount 'max-attempts'
    $sid = Ai-NewSid 'm08'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-M-08】turn-text break-at-seq=1 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $runId = Ai-RunId $frames
    $elapsed = $sw.Elapsed.TotalSeconds
    $bound = ($interEvent * $maxAttempts) + 60
    if ($elapsed -gt $bound) { throw "断流收口耗时 ${elapsed}s > 预算 ${bound}s（退避无界）" }
    $r = Ai-WaitStatus $runId @('DONE', 'FAILED', 'CANCELLED') 30
    if ($r.snapshot.status -eq 'RUNNING') { throw '断流后轮次停在 RUNNING（放弃无显式终态）' }
    if ([int]$r.snapshot.attempts -gt $maxAttempts) { throw "attempts=$($r.snapshot.attempts) 超过 max-attempts=$maxAttempts" }
    return $null
}

Add-AiCase 'M-09' 'P1' 'lastSeq 差量 + 孤儿过滤：只回放 seq>K，heartbeat 不重放' {
    $sid = Ai-NewSid 'm09'
    $turn = Invoke-AiTurn $sid '【AIE2E-M-09】turn-text 请回复一句。' @{ page = 'tasks' } $script:aiContinue 2
    $runId = Ai-RunId $turn.frames
    $k = [int]([math]::Floor((Ai-MaxSeq $turn.frames) / 2))
    if ($k -lt 1) { throw "归档末号太小（$k），差量窗口不成立" }
    $replay = Ai-Events $runId $k @('done', 'error')
    $stale = @($replay | Where-Object { $null -ne $_.seq -and [int]$_.seq -le $k })
    if ($stale.Count -gt 0) { throw "lastSeq=$k 时回放了 $($stale.Count) 个旧帧（孤儿过滤失效）" }
    if (@($replay | Where-Object { $_.type -eq 'heartbeat' }).Count -gt 0) { throw '差量重挂重放了 heartbeat 帧（应过滤）' }
    return $null
}


Say '--- 批① · HITL 确认门（11 条） ---'

# 表单挂起助手（GF 用例共用）：读到 frontend_tool_request 即返回，args 里的 form 由调用方解析
function Ai-FormSuspend($sid, $marker, $ctx = @{ page = 'tasks' }) {
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("【AIE2E】$marker 请按标记出表单。") ; context = $ctx } `
        @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) {
        $err = Ai-One $frames 'error'
        throw ("未收到 frontend_tool_request（标记 $marker$(if ($err) { "；error=$($err.code) $($err.message)" })）")
    }
    if ($req.name -ne 'generative_form') { throw "挂起工具是 $($req.name)，期望 generative_form" }
    $argsObj = $null
    try { $argsObj = $req.args | ConvertFrom-Json } catch { }
    if (-not $argsObj -or -not $argsObj.form) { throw 'frontend_tool_request.args 缺 form（入参闸门未拦截，产品缺陷）' }
    return @{ runId = $req.runId; toolCallId = $req.toolCallId; form = $argsObj.form; frames = $frames }
}
# 回灌表单值（原始响应，供 400/200 双向断言）
function Ai-FormPost($runId, $toolCallId, $payload) {
    return Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{ runId = $runId; toolCallId = $toolCallId; result = $payload; source = 'aie2e' }
}
# 附件两段式上传（第一段）：multipart + runId/toolCallId
function Ai-FormUpload($runId, $toolCallId, $filePath, $fileName) {
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $bytes = [System.IO.File]::ReadAllBytes($filePath)
    $bc = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$bytes)
    # MIME 必须与扩展名一致：产品的附件闸按"扩展名 → 允许 MIME 集"比对（GenerativeFormAttachmentController
    # 的 ALLOWED 表），.txt 只认 text/plain。这里按浏览器上传时的同一口径给值，不拿
    # application/octet-stream 去撞闸（那是客户端缺陷，不是产品缺陷）。
    $mime = switch ([System.IO.Path]::GetExtension($fileName).ToLowerInvariant()) {
        '.txt' { 'text/plain' }
        '.csv' { 'text/csv' }
        '.pdf' { 'application/pdf' }
        '.png' { 'image/png' }
        '.jpg' { 'image/jpeg' }
        '.jpeg' { 'image/jpeg' }
        '.webp' { 'image/webp' }
        default { 'application/octet-stream' }
    }
    $bc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($mime)
    $content.Add($bc, 'file', $fileName)
    $content.Add((New-Object System.Net.Http.StringContent($runId)), 'runId')
    $content.Add((New-Object System.Net.Http.StringContent($toolCallId)), 'toolCallId')
    $resp = $http.PostAsync($Base + '/api/ai/form/attachments', $content).Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $obj = $null
    if ($text -and $text.TrimStart().StartsWith('{')) { try { $obj = $text | ConvertFrom-Json } catch { } }
    return @{ status = [int]$resp.StatusCode; ok = $resp.IsSuccessStatusCode; text = $text; json = $obj }
}

Add-AiCase 'H-01' 'P1' '单卡批准 → 真实执行（作业创建、confirm_decision 帧、卡转只读的数据面）' {
    $t = Ai-PublishFixture 'AIE2E-H-01-批准'
    $sid = Ai-NewSid 'h01'
    $jobsBefore = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' }).Count
    $turn = Invoke-AiTurn $sid ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t) `
        @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } `
        { param($f) if ($f.type -eq 'confirm_request' -and $f.name -eq 'start_publish') { return @{ approved = $true } } return @{ approved = $false } } 4
    $cr = @($turn.frames | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_publish' })[0]
    if (-not $cr) { throw '未收到 start_publish 确认门事件' }
    $dec = @($turn.frames | Where-Object { $_.type -eq 'confirm_decision' -and $_.toolCallId -eq $cr.toolCallId })
    if ($dec.Count -lt 1) { throw '批准后没有 confirm_decision 回执帧（卡面无从"待确认"转只读的依据）' }
    if ($dec[$dec.Count - 1].approved -ne $true) { throw "confirm_decision.approved=$($dec[$dec.Count - 1].approved)" }
    $job = Wait-Until {
        $js = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
        if ($js.Count -gt $jobsBefore) { return $js[0] } else { return $null }
    } 60 '批准后未创建发布作业'
    if ((Wait-Job $job.id).status -ne 'COMPLETED') { throw '发布作业未 COMPLETED' }
    $r = Ai-Run $cr.runId
    $ledger = @($r.ledger | Where-Object { $_.toolCallId -eq $cr.toolCallId })
    if ($ledger.Count -lt 1) { throw '台账里没有该 toolCallId 的执行记录' }
    # 台账记录字段（RunStore#appendLedger）：outcome / code / executedBy / atMs / resultText / epoch。
    # 没有 executed 布尔（那是 pending 条目 asMap 的字段）—— 判"真执行过"看 outcome=ok + executedBy 落名。
    if ([string]$ledger[0].outcome -ne 'ok') { throw "台账 outcome=$($ledger[0].outcome)，期望 ok（已批准却未执行）" }
    if ([string]::IsNullOrWhiteSpace([string]$ledger[0].executedBy)) { throw '台账缺 executedBy（无法判定由哪个实例执行）' }
    if ([long]$ledger[0].atMs -lt 1) { throw '台账缺 atMs（执行时刻不可判）' }
    return $null
}

Add-AiCase 'H-02' 'P0' '单卡拒绝 → 零执行（无作业行、条目 REJECTED、后续轮可发）' {
    $t = Ai-PublishFixture 'AIE2E-H-02-拒绝'
    $sid = Ai-NewSid 'h02'
    $turn = Invoke-AiTurn $sid ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t) `
        @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } $script:aiDeny 4
    $cr = @($turn.frames | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_publish' })[0]
    if (-not $cr) { throw '未收到 start_publish 确认门事件' }
    Start-Sleep -Seconds 1
    $pubJobs = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
    if ($pubJobs.Count -gt 0) { throw "拒绝后仍创建了发布作业（$($pubJobs.Count) 个）" }
    $entry = Gf-PendingEntry $cr.runId $cr.toolCallId
    if ($entry.status -ne 'REJECTED') { throw "条目状态 $($entry.status)，期望 REJECTED" }
    if ($entry.executed -eq $true) { throw '拒绝的条目却 executed=true' }
    Ai-Cancel $cr.runId | Out-Null
    $next = Ai-ChatRaw $sid '拒绝之后再问一句' @{ page = 'tasks' }
    if (-not $next.ok) { throw "拒绝后同会话再发 => HTTP $($next.status) $($next.text)" }
    return $null
}

Add-AiCase 'H-03' 'P1' '重复决策幂等：409 DUPLICATE_TOOL_CALL_ID 且台账计数不变' {
    $t = Ai-PublishFixture 'AIE2E-H-03-幂等'
    $sid = Ai-NewSid 'h03'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未收到确认门事件' }
    $body = @{ runId = $cr.runId; toolCallId = $cr.toolCallId; approved = $false; reason = 'aie2e-h03' }
    $first = Invoke-Api 'POST' '/api/ai/confirm' $body
    if (-not $first.ok) { throw "首次决策 => HTTP $($first.status) $($first.text)" }
    $ledgerBefore = @((Ai-Run $cr.runId).ledger).Count
    $dup = Expect-Fail 'POST' '/api/ai/confirm' $body '重复决策未被拒'
    if ($dup.status -ne 409 -or $dup.json.code -ne 'DUPLICATE_TOOL_CALL_ID') {
        throw "重复决策 HTTP $($dup.status)/code=$($dup.json.code)，期望 409/DUPLICATE_TOOL_CALL_ID"
    }
    Start-Sleep -Seconds 1
    $ledgerAfter = @((Ai-Run $cr.runId).ledger).Count
    if ($ledgerAfter -ne $ledgerBefore) { throw "重复决策后台账条目 $ledgerBefore → $ledgerAfter（发生了第二次执行）" }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'H-04' 'P0' '多卡逐卡决策：每卡独立收敛、其余卡未决时仍处等待、全决后不等待' {
    $t = Ai-ExportFixture 'AIE2E-H-04-多卡'
    $sid = Ai-NewSid 'h04'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_export 工具启动任务 {0} 的导出作业 multi-card=3（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'export'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $first = Ai-One $frames 'confirm_request'
    if (-not $first) { throw '未收到第一批确认门事件' }
    $runId = $first.runId
    $cards = New-Object System.Collections.ArrayList
    $cards.Add($first) | Out-Null
    # C1（2026-10-25 裁决）：park 开启时整批请求帧由 ConfirmGate#publishRequestsForParkedBatch
    # 在 park **前一次公布**（本批每张卡的 req 都置 true），不再"第 N 张等第 N-1 张决策后才发"。
    # 本循环只保留"收已公布帧 → 逐条决策"的节奏；判据仍是逐卡独立收敛、全决前 awaitingExternal=true。
    $decided = New-Object System.Collections.ArrayList
    $deadline = (Get-Date).AddSeconds(45)
    while ($decided.Count -lt 3 -and (Get-Date) -lt $deadline) {
        $next = @($cards | Where-Object { $id = $_.toolCallId; -not (@($decided | Where-Object { $_ -eq $id }).Count -gt 0) })
        if ($next.Count -eq 0) {
            # 拉一次归档把"刚被放出来"的卡收进来（只读，不重复）
            foreach ($f in @((Read-Sse-Brief "/api/ai/events/$runId`?lastSeq=0" 3000) | Where-Object { $_.type -eq 'confirm_request' })) {
                if (-not (@($cards | Where-Object { $_.toolCallId -eq $f.toolCallId }).Count -gt 0)) { $cards.Add($f) | Out-Null }
            }
            Start-Sleep -Milliseconds 300
            continue
        }
        foreach ($c in $next) {
            $conf = Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $runId; toolCallId = $c.toolCallId; approved = $false; reason = 'aie2e-h04' }
            if (-not $conf.ok -and $conf.status -ne 409) { throw "逐卡决策 => HTTP $($conf.status) $($conf.text)" }
            $decided.Add([string]$c.toolCallId) | Out-Null
            Start-Sleep -Milliseconds 400
            $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
            if ($cur.found -and $cur.awaitingExternal -eq $true) {
                # 仍有未决卡：会话必须仍可被"停止"，即可发现面不为空（B1 M-6 的接口级判据）
                if (@($cur.unresolvedExternal).Count -lt 1) { throw 'awaitingExternal=true 但 unresolvedExternal 为空（自相矛盾）' }
            }
        }
    }
    if ($decided.Count -lt 3) { throw "逐卡决策只走到 $($decided.Count)/3 张（多卡批次未按'决策一张放一张'推进）" }
    # 全部决策后：不再等待外部（suspended 撤除）
    $deadline = (Get-Date).AddSeconds(20)
    $ok = $false
    while ((Get-Date) -lt $deadline) {
        $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
        if (-not $cur.found -or $cur.awaitingExternal -ne $true) { $ok = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ok) { throw '全部卡决策后 runs/current 仍 awaitingExternal=true（suspended 未撤）' }
    $r = Ai-WaitStatus $runId @('DONE', 'CANCELLED', 'FAILED') 30
    $open = @((Invoke-Api 'GET' "/api/ai/pending/$runId" $null).json.data | Where-Object { $_.status -eq 'PENDING' })
    if ($open.Count -gt 0) { throw "全部决策后仍有 $($open.Count) 个条目 PENDING" }
    return $null
}

Add-AiCase 'H-05' 'P1' '预检摘要三通道同源（帧的预检位 / runs/current / pending 逐值相等）' {
    $t = Ai-ExportFixture 'AIE2E-H-05-预检'
    $sid = Ai-NewSid 'h05'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_export 工具启动任务 {0} 的导出作业（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'export'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未收到 start_export 确认门事件' }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found) { throw 'runs/current 未 found（挂起态未外置）' }
    $entryCur = @($cur.pendingEntries | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $entryCur) { throw 'runs/current 缺该 toolCallId' }
    $entryPend = Gf-PendingEntry $cr.runId $cr.toolCallId
    if ([bool]$cr.preflight -ne ($null -ne $entryCur.preflight)) {
        throw "帧的 preflight 位（$($cr.preflight)）与 runs/current 的预检对象（$($null -ne $entryCur.preflight)）不同源"
    }
    if (($null -ne $entryCur.preflight) -ne ($null -ne $entryPend.preflight)) {
        throw 'runs/current 与 pending 的预检对象有无不一致'
    }
    if ($null -ne $entryCur.preflight) {
        $a = $entryCur.preflight | ConvertTo-Json -Depth 10 -Compress
        $b = $entryPend.preflight | ConvertTo-Json -Depth 10 -Compress
        if ($a -ne $b) { throw '两通道的预检对象逐值不等（用户可能批准了另一版预检）' }
        if (-not $entryCur.preflight.reportHash) { throw '预检对象缺 reportHash（无法绑定"批准的是哪一版"）' }
    }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'H-06' 'P1' '预检失败不进闸门：无 confirm_request、结构化失败码、retryable' {
    # 非法目标 = **类型不匹配**（拿导入任务要求导出）：预检的第 2 项（类型匹配）必然失败，闸门不得被 arm。
    # 旧夹具用"未选配置项就要求导出"，但产品的预检没有"已选配置项"这一项，且零配置项的导出
    # 会正常 COMPLETED（实测）—— 那不是预检失败，夹具命题本身不成立（不是产品缺陷）。
    $t = New-ImportTask 'AIE2E-H-06-预检失败' @('PROJECT_ENV') $null
    $sid = Ai-NewSid 'h06'
    $turn = Invoke-AiTurn $sid ("请立即调用 start_export 工具启动任务 {0} 的导出作业" -f $t) `
        @{ page = 'export'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' } $script:aiDeny 4
    $cr = @($turn.frames | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_export' })
    if ($cr.Count -gt 0) { throw '预检失败的调用仍进了确认门（产生了可点批准的空闸门）' }
    $tr = @($turn.frames | Where-Object { $_.type -eq 'tool_result' -and $_.name -eq 'start_export' -and $_.ok -eq $false })
    if ($tr.Count -lt 1) { throw '没有 start_export 的失败工具结果帧（用户看不到失败原因）' }
    $code = [string]$tr[$tr.Count - 1].code
    if ([string]::IsNullOrWhiteSpace($code)) { throw '失败工具结果缺机器可读 code' }
    if ($tr[$tr.Count - 1].retryable -ne $true) { throw "失败工具结果 retryable=$($tr[$tr.Count - 1].retryable)，预检类失败应可重试" }
    $runId = Ai-RunId $turn.frames
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($cur.found -and $cur.awaitingExternal -eq $true) { throw '预检失败后 runs/current 仍 awaitingExternal=true' }
    return $null
}

Add-AiCase 'H-07' 'P2' '越 scope 先拦：未披露的工具不会被调用（预检计数 0 的接口等价）' {
    $tools = GetJson '/api/ai/tools?page=export&taskType=EXPORT&step=EXPORT'
    if (@($tools.toolNames) -contains 'start_publish') { throw 'page=export/EXPORT 上下文不该披露 start_publish' }
    $sid = Ai-NewSid 'h07'
    $turn = Invoke-AiTurn $sid '请调用 start_publish 发布这个导出任务' @{ page = 'export'; taskType = 'EXPORT'; step = 'EXPORT' } $script:aiDeny 3
    if (@($turn.frames | Where-Object { $_.type -eq 'tool_start' -and $_.name -eq 'start_publish' }).Count -gt 0) {
        throw '越 scope 的 start_publish 仍被调用（披露闸被绕过）'
    }
    if (@($turn.frames | Where-Object { $_.type -eq 'confirm_request' }).Count -gt 0) { throw '越 scope 调用进了确认门' }
    return $null
}

Add-AiCase 'H-08' 'P1' '已决六态的数据面：决策帧与台账逐项可读（expired 不显"已拒绝"）' {
    $t = Ai-PublishFixture 'AIE2E-H-08-六态'
    $sid = Ai-NewSid 'h08'
    $turn = Invoke-AiTurn $sid ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t) `
        @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } $script:aiDeny 4
    $cr = @($turn.frames | Where-Object { $_.type -eq 'confirm_request' -and $_.name -eq 'start_publish' })[0]
    if (-not $cr) { throw '未收到确认门事件' }
    $r = Ai-Run $cr.runId
    $entry = @($r.pending | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $entry) { throw '台账 pending 里无该条目（六态徽标无数据源）' }
    foreach ($k in @('status', 'outcome', 'code', 'executed', 'executedBy')) {
        if ($null -eq $entry.PSObject.Properties[$k]) { throw "pending 条目缺 $k（六态之一无判据）" }
    }
    if ($entry.status -eq 'REJECTED' -and $entry.outcome -eq 'TIMEOUT') { throw 'expired 被写成 REJECTED（历史回读会误显"已拒绝"）' }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'H-09' 'P2' 'reportHash 绑定：预检哈希稳定可读（批准的是哪一版）' {
    $t = Ai-ExportFixture 'AIE2E-H-09-哈希'
    $sid = Ai-NewSid 'h09'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_export 工具启动任务 {0} 的导出作业（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'export'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未收到 start_export 确认门事件' }
    $a = (Gf-PendingEntry $cr.runId $cr.toolCallId).preflight
    if ($null -eq $a) { throw '确认门条目没有预检对象（无 reportHash 可绑）' }
    if (-not $a.reportHash) { throw '预检对象缺 reportHash' }
    Start-Sleep -Milliseconds 800
    $b = (Gf-PendingEntry $cr.runId $cr.toolCallId).preflight
    if ([string]$a.reportHash -ne [string]$b.reportHash) { throw '两次读到的 reportHash 不同（哈希不是稳定值）' }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'H-10' 'P0' '挂起即断连接 + 决策后差量重连（续写原轮，不新建轮）' {
    $t = Ai-PublishFixture 'AIE2E-H-10-断连'
    $sid = Ai-NewSid 'h10'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未收到确认门事件' }
    $runId = $cr.runId
    # ① 挂起后 ≤5s 无业务帧（请求帧发完即断连，人机等待期不占连接）
    $quiet = Read-Sse-Brief "/api/ai/events/$runId`?lastSeq=0" 5000
    $biz = @($quiet | Where-Object { $_.type -in @('delta', 'tool_start', 'tool_result', 'suspended') })
    if ($biz.Count -gt 0) {
        $maxOld = Ai-MaxSeq $frames
        $new = @($biz | Where-Object { $null -ne $_.seq -and [int]$_.seq -gt $maxOld })
        if ($new.Count -gt 0) { throw "挂起期仍有 $($new.Count) 个新业务帧（人机等待期仍在产帧）" }
    }
    # ② 决策后带 lastSeq 差量续收，且帧序接着原轮（不出现第二个 start）
    $before = Ai-MaxSeq $frames
    $conf = Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $runId; toolCallId = $cr.toolCallId; approved = $false; reason = 'aie2e-h10' }
    if (-not $conf.ok) { throw "POST /api/ai/confirm => HTTP $($conf.status) $($conf.text)" }
    $after = Ai-Events $runId $before @('done', 'error')
    if (@($after | Where-Object { $_.type -eq 'start' }).Count -gt 0) { throw '决策后重连出现第二个 start 帧（新建了一轮/新气泡）' }
    $seqs = @($after | Where-Object { $null -ne $_.seq } | ForEach-Object { [int]$_.seq })
    if ($seqs.Count -gt 0 -and ($seqs | Measure-Object -Minimum).Minimum -le $before) {
        throw "决策后差量续收回放了 seq ≤ $before 的旧帧"
    }
    if ((Ai-F $after @('done', 'error')).Count -lt 1) { throw '决策后续收未收到终态' }
    return $null
}

Add-AiCase 'H-11' 'P0' '卡快照重建：只对 req=true 建卡、kind 三值原样、已决卡不被补发帧复活' {
    $t = Ai-PublishFixture 'AIE2E-H-11-快照'
    $sid = Ai-NewSid 'h11'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未收到确认门事件' }
    $runId = $cr.runId
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found) { throw 'runs/current 未 found（快照未外置）' }
    foreach ($e in @($cur.pendingEntries)) {
        if ($null -eq $e.PSObject.Properties['req']) { throw "条目 $($e.toolCallId) 缺 req 键（前端会按 false 处理 ⇒ 少一张卡）" }
        if (@('CONFIRM', 'FRONTEND', 'BACKEND') -notcontains [string]$e.kind) { throw "条目 kind=$($e.kind) 不在三值内" }
        if ($e.entryStatus -eq 'PENDING' -and [string]$e.kind -eq 'BACKEND') { throw 'BACKEND 类条目出现在 PENDING（会被画成可点卡）' }
    }
    $mine = @($cur.pendingEntries | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $mine) { throw '快照缺该 toolCallId' }
    if ($mine.req -ne $true) { throw '已发出请求帧的条目 req 不是 true（重建会漏卡）' }
    # 已决后：补发/回放不得让该卡"复活"回到 pending。
    #   口径（设计稿 §Q25）：归档回放本来就会把原始 confirm_request 再送一遍 —— "first-wins"
    #   是**前端**的消费纪律，接口层要锁的是数据面不被回放翻案：
    #   ① 台账条目不回到 PENDING；② 不带回放重发第二份请求帧；③ 快照不再把它列为未决。
    Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $runId; toolCallId = $cr.toolCallId; approved = $false; reason = 'aie2e-h11' } | Out-Null
    Start-Sleep -Seconds 1
    $replay = Ai-Events $runId 0 @('done', 'error')
    $framesOfCard = @($replay | Where-Object { $_.type -eq 'confirm_request' -and $_.toolCallId -eq $cr.toolCallId })
    if ($framesOfCard.Count -gt 1) { throw "已决卡被补发 $($framesOfCard.Count) 次请求帧（归档里必须只有首次那一份）" }
    $entryAfter = Gf-PendingEntry $runId $cr.toolCallId
    if ($entryAfter.status -eq 'PENDING') { throw '决策后条目仍是 PENDING（回放把卡翻回了未决）' }
    $curAfter = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($curAfter.found -and @($curAfter.unresolvedExternal | Where-Object { $_ -eq $cr.toolCallId }).Count -gt 0) {
        throw '决策后 runs/current 仍把该卡列为未决（回放复活）'
    }
    return $null
}

Say '--- 批① · GF 生成式表单（11 条：新能力面 + 结构化 issues） ---'

Add-AiCase 'G-04' 'P1' '复核拒绝 → 改值重发：400 带结构化 issues/retryable，状态仍 PENDING' {
    $sid = Ai-NewSid 'g04'
    # 首条 user 文本 = 桩的轮次台账键（fingerprint = 首条 user 文本）：同文本的多条用例会共用
    # "每路由一次"台账，只有消息数严格回退才清零 —— 各用例的标记带括号区分，避免跨用例串账
    # （同 `gf-linkage（G-10 …）` / `gf-options-from（G-12 …）` 的既有做法；`scenario=FILTER` 子串保留）。
    $s = Ai-FormSuspend $sid 'scenario=FILTER（G-04 复核拒绝改值）'
    $bad = '{"keyword":"' + ('X' * 400) + '"}'
    $r1 = Ai-FormPost $s.runId $s.toolCallId $bad
    if ($r1.status -ne 400) { throw "非法值回灌 HTTP $($r1.status)，期望 400" }
    if (-not $r1.json.issues -or @($r1.json.issues).Count -lt 1) { throw '400 缺 issues 数组（前端无法点亮字段）' }
    if ($r1.json.retryable -ne $true) { throw "400.retryable=$($r1.json.retryable)，期望 true（改值后可重试）" }
    foreach ($i in @($r1.json.issues)) { if (-not $i.key -or -not $i.code) { throw "issues 条目缺 key/code：$($i | ConvertTo-Json -Compress)" } }
    $entry = Gf-PendingEntry $s.runId $s.toolCallId
    if ($entry.status -ne 'PENDING') { throw "拒绝后条目状态 $($entry.status)，期望仍 PENDING" }
    $r2 = Ai-FormPost $s.runId $s.toolCallId '{"keyword":"E2EGFC","scope":"XN"}'
    if ($r2.status -ne 200) { throw "改值重发 HTTP $($r2.status) $($r2.text)" }
    if ($r2.json.status -ne 'FRONTEND_RESULT') { throw "改值重发 status=$($r2.json.status)，期望 FRONTEND_RESULT" }
    return $null
}

Add-AiCase 'G-08' 'P2' 'longText 2000 上限：2001 字被 400 拒、2000 字可提交（不静默截断）' {
    $sid = Ai-NewSid 'g08'
    $s = Ai-FormSuspend $sid 'gf-longtext'
    $memo = $s.form.fields | Where-Object { $_.key -eq 'memo' }
    if (-not $memo) { throw '表单缺 memo 字段' }
    if ($memo.variant -ne 'longText') { throw "memo.variant=$($memo.variant)，期望 longText（长文本渲染面丢失）" }
    $tooLong = '{"memo":"' + ('L' * 2001) + '"}'
    $r1 = Ai-FormPost $s.runId $s.toolCallId $tooLong
    if ($r1.status -ne 400) { throw "2001 字 HTTP $($r1.status)，期望 400（不得静默截断）" }
    $codes = @($r1.json.issues | ForEach-Object { [string]$_.code })
    if ($codes.Count -lt 1) { throw '超长被拒但 issues 为空' }
    $ok = '{"memo":"' + ('L' * 2000) + '","short":"S"}'
    $r2 = Ai-FormPost $s.runId $s.toolCallId $ok
    if ($r2.status -ne 200) { throw "2000 字 HTTP $($r2.status)（上限被收紧到 2000 以下）" }
    return $null
}

Add-AiCase 'G-09' 'P1' 'visibleWhen 联动结构原样透出（op 在闭集，不求值）' {
    $sid = Ai-NewSid 'g09'
    $s = Ai-FormSuspend $sid 'gf-linkage'
    $detail = $s.form.fields | Where-Object { $_.key -eq 'detail' }
    if (-not $detail) { throw '表单缺 detail 字段' }
    if ($null -eq $detail.visibleWhen) { throw 'detail 的 visibleWhen 在透出链路里丢了（联动不可用）' }
    if ($detail.visibleWhen.op -ne 'eq') { throw "visibleWhen.op=$($detail.visibleWhen.op)，期望 eq（闭集）" }
    if ($detail.visibleWhen.key -ne 'kind') { throw "visibleWhen.key=$($detail.visibleWhen.key)，期望 kind" }
    # 联动是数据比较、不是表达式：结构里不得出现可执行串
    $json = $detail.visibleWhen | ConvertTo-Json -Depth 6 -Compress
    if ($json -match '===|eval|function|alert\(') { throw "visibleWhen 被下发改写成表达式形态：$json" }
    $r = Ai-FormPost $s.runId $s.toolCallId '{"kind":"A1","detail":"D","note":"N"}'
    if ($r.status -ne 200) { throw "联动表单合法提交 HTTP $($r.status) $($r.text)" }
    return $null
}

Add-AiCase 'G-10' 'P1' 'requiredWhen 条件必填由后端同判（kind=A1 缺 detail 被 400 拒）' {
    $sid = Ai-NewSid 'g10'
    $s = Ai-FormSuspend $sid 'gf-linkage（G-10 requiredWhen）'
    $note = $s.form.fields | Where-Object { $_.key -eq 'note' }
    if ($null -eq $note.disabledWhen) { throw 'note 的 disabledWhen 在透出链路里丢了' }
    $r1 = Ai-FormPost $s.runId $s.toolCallId '{"kind":"A1","note":"N"}'
    if ($r1.status -ne 400) { throw "条件必填缺值 HTTP $($r1.status)，期望 400（requiredWhen 未同判）" }
    $keys = @($r1.json.issues | ForEach-Object { [string]$_.key })
    if ($keys -notcontains 'detail') { throw "issues 未指到 detail（实际：$($keys -join ',')）" }
    $r2 = Ai-FormPost $s.runId $s.toolCallId '{"kind":"A1","detail":"D","note":"N"}'
    if ($r2.status -ne 200) { throw "补上条件必填后 HTTP $($r2.status)" }
    return $null
}

Add-AiCase 'G-11' 'P1' 'optionsFrom 闭集：级联选项透出且越集值被 400 拒' {
    $sid = Ai-NewSid 'g11'
    $s = Ai-FormSuspend $sid 'gf-options-from'
    $city = $s.form.fields | Where-Object { $_.key -eq 'city' }
    if (-not $city) { throw '表单缺 city 字段' }
    if ($null -eq $city.optionsFrom) { throw 'city 的 optionsFrom 在透出链路里丢了' }
    if ($city.optionsFrom.dependsOn -ne 'region') { throw "optionsFrom.dependsOn=$($city.optionsFrom.dependsOn)，期望 region" }
    if ($null -eq $city.optionsFrom.map) { throw 'optionsFrom.map 丢失（前端无级联数据）' }
    $r1 = Ai-FormPost $s.runId $s.toolCallId '{"region":"XN","city":"NOT_IN_MAP","amount":10}'
    if ($r1.status -ne 400) { throw "越集选项 HTTP $($r1.status)，期望 400（闭集未生效）" }
    $r2 = Ai-FormPost $s.runId $s.toolCallId '{"region":"XN","city":"XA","amount":10}'
    if ($r2.status -ne 200) { throw "集内选项 HTTP $($r2.status)" }
    return $null
}

Add-AiCase 'G-12' 'P2' 'defaultFrom 映射透出（默认值来源可读、与字段类型一致）' {
    $sid = Ai-NewSid 'g12'
    $s = Ai-FormSuspend $sid 'gf-options-from（G-12 defaultFrom）'
    $amount = $s.form.fields | Where-Object { $_.key -eq 'amount' }
    if (-not $amount) { throw '表单缺 amount 字段' }
    if ($null -eq $amount.defaultFrom) { throw 'amount 的 defaultFrom 在透出链路里丢了' }
    if ($amount.defaultFrom.dependsOn -ne 'region') { throw 'defaultFrom.dependsOn 不是 region' }
    $map = $amount.defaultFrom.map
    if ($null -eq $map) { throw 'defaultFrom.map 丢失' }
    foreach ($p in @($map.PSObject.Properties)) {
        if ($p.Value -isnot [int] -and $p.Value -isnot [long] -and $p.Value -isnot [double]) {
            throw "defaultFrom.map[$($p.Name)]=$($p.Value) 与 type=number 不符"
        }
    }
    return $null
}

Add-AiCase 'G-13' 'P2' '依赖深度上限 2：四层依赖被后端拒（不静默丢字段）' {
    $sid = Ai-NewSid 'g13'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】gf-deep-linkage 请按标记出表单。' ; context = @{ page = 'tasks' } } `
        @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    $rej = @($frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.ok -eq $false })
    if ($req) {
        # 若产品把表单下发下来，它必须**没有**丢掉 d 字段（静默裁剪比拒绝更坏）
        $argsObj = $req.args | ConvertFrom-Json
        $keys = @($argsObj.form.fields | ForEach-Object { $_.key })
        if ($keys -notcontains 'd') { throw "超深联动被静默裁剪（字段：$($keys -join ',')）——应整体判非法" }
        throw '四层依赖的表单被下发到前端（MAX_LINKAGE_DEPTH 未生效）'
    }
    if ($rej.Count -lt 1) { throw '超深联动既没被拒也没下发（入参闸门无结论）' }
    $code = [string]$rej[$rej.Count - 1].status
    if ([string]::IsNullOrWhiteSpace($code)) { throw '拒绝帧缺 status/code（无法归因）' }
    return $null
}

Add-AiCase 'G-14' 'P1' '附件两段式：上传返 fileId（格式），回灌只带 fileId' {
    $sid = Ai-NewSid 'g14'
    # 标记后缀让三条附件用例的提示词互不相同：桩的"每路由一次"台账按提示词指纹分桶
    # （stub-upstream.mjs 的 turns 表），同文提示词会被判成同一条台账 ⇒ 第二条拿不到卡。
    $s = Ai-FormSuspend $sid 'gf-attachment（G-14 两段式）'
    $file = Join-Path $tmp 'aie2e-attach.txt'
    [IO.File]::WriteAllText($file, 'AIE2E 附件内容：仅用于两段式上传断言。')
    $up = Ai-FormUpload $s.runId $s.toolCallId $file 'aie2e-attach.txt'
    if (-not $up.ok) { throw "附件上传 => HTTP $($up.status) $($up.text)" }
    $fileId = [string]$up.json.data.fileId
    if ($fileId -notmatch '^att_[A-Za-z0-9_-]{8,64}$') { throw "fileId 形态异常：$fileId" }
    $payload = '{"sheet":"' + $fileId + '"}'
    $r = Ai-FormPost $s.runId $s.toolCallId $payload
    if ($r.status -ne 200) { throw "带 fileId 回灌 HTTP $($r.status) $($r.text)" }
    $entry = Gf-PendingEntry $s.runId $s.toolCallId
    if ([string]$entry.resultText -notlike "*$fileId*") { throw '回灌结果里没有 fileId（两段式链路断了）' }
    return $null
}

Add-AiCase 'G-15' 'P1' '附件三重校验：一次性消费（同 fileId 二次提交被拒）' {
    # 设计稿 G-15 的三重校验（① 同号二次提交 ② 跨 runId 引用=归属 ③ 伪造 id），外加消费面：
    # 每个子面都要一张**挂起中的**表单，而桩的"每路由一次"台账按会话首条 user 文本分桶 ⇒
    # 同一会话第二轮拿不到第二次卡，故三个子面各用独立会话 + 独立标记。
    #
    # 附件件数口径（13db3a92 起）：本表单只有 **1 个**附件字段（fields[*].type=attachment 的
    # 单附件位），产品的上传口对"在册件数 ≥ 附件字段数"的处置是**顶掉旧件**（前端"重新选择文件"
    # 就是再传一次，没有删除接口）—— 同一 runId+toolCallId 传第二件会让第一件的 fileId 失效。
    # 故 ② 的"未消费件"必须由**另一张表单**持有：它未消费、但归属另一轮 runId。
    $sid = Ai-NewSid 'g15'
    $s1 = Ai-FormSuspend $sid 'gf-attachment（G-15 一次性消费）'
    $fileA = Join-Path $tmp 'aie2e-attach2a.txt'
    [IO.File]::WriteAllText($fileA, 'AIE2E 附件内容：一次性消费断言（A）。')
    $upA = Ai-FormUpload $s1.runId $s1.toolCallId $fileA 'aie2e-attach2a.txt'
    if (-not $upA.ok) { throw "附件上传 A => HTTP $($upA.status) $($upA.text)" }
    $fileIdA = [string]$upA.json.data.fileId
    # 第二件附件（不消费）：挂在**另一张**表单上，用于 ② 的"归属"面（未消费、但换了 runId）
    $s1b = Ai-FormSuspend (Ai-NewSid 'g15b') 'gf-attachment（G-15 未消费件归属）'
    $fileB = Join-Path $tmp 'aie2e-attach2b.txt'
    [IO.File]::WriteAllText($fileB, 'AIE2E 附件内容：跨轮归属断言（B）。')
    $upB = Ai-FormUpload $s1b.runId $s1b.toolCallId $fileB 'aie2e-attach2b.txt'
    if (-not $upB.ok) { throw "附件上传 B => HTTP $($upB.status) $($upB.text)" }
    $fileIdB = [string]$upB.json.data.fileId
    $r1 = Ai-FormPost $s1.runId $s1.toolCallId ('{"sheet":"' + $fileIdA + '"}')
    if ($r1.status -ne 200) {
        throw "首次提交 HTTP $($r1.status) issues=$((@($r1.json.issues | ForEach-Object { [string]$_.code })) -join ',') text=$($r1.text)"
    }
    # ① 同号二次提交：同一 runId+toolCallId 已有结果 ⇒ 重复提交被拒
    $dup = Ai-FormPost $s1.runId $s1.toolCallId ('{"sheet":"' + $fileIdA + '"}')
    if ($dup.status -ne 409 -or [string]$dup.json.code -ne 'DUPLICATE_TOOL_CALL_ID') {
        throw "同号二次提交 HTTP $($dup.status)/code=$($dup.json.code)，期望 409/DUPLICATE_TOOL_CALL_ID"
    }
    # ② 跨 runId 引用：B 未被消费（干净），但归属另一张未决表单 ⇒ 归属被拒
    $s2 = Ai-FormSuspend (Ai-NewSid 'g15c') 'gf-attachment（G-15 跨轮归属）'
    $cross = Ai-FormPost $s2.runId $s2.toolCallId ('{"sheet":"' + $fileIdB + '"}')
    if ($cross.status -ne 400) { throw "跨轮引用未消费附件 HTTP $($cross.status)，期望 400" }
    $crossCodes = @($cross.json.issues | ForEach-Object { [string]$_.code })
    if ($crossCodes -notcontains 'ATTACHMENT_FORBIDDEN') { throw "跨轮引用被拒但 issues 码=$($crossCodes -join ',')（期望 ATTACHMENT_FORBIDDEN）" }
    # ②b 已消费的 A 再被别的轮次引用：一次性口径
    $reuse = Ai-FormPost $s2.runId $s2.toolCallId ('{"sheet":"' + $fileIdA + '"}')
    if ($reuse.status -ne 400) { throw "已消费附件被跨轮引用 HTTP $($reuse.status)，期望 400" }
    $reuseCodes = @($reuse.json.issues | ForEach-Object { [string]$_.code })
    if ($reuseCodes -notcontains 'ATTACHMENT_CONSUMED') { throw "已消费附件被引用但 issues 码=$($reuseCodes -join ',')（期望 ATTACHMENT_CONSUMED）" }
    # ③ 伪造 id：存在性校验
    $s3 = Ai-FormSuspend (Ai-NewSid 'g15d') 'gf-attachment（G-15 伪造 id）'
    $r2 = Ai-FormPost $s3.runId $s3.toolCallId '{"sheet":"att_FAKEFAKEFAKE"}'
    if ($r2.status -ne 400) { throw "伪造 fileId HTTP $($r2.status)，期望 400" }
    $codes = @($r2.json.issues | ForEach-Object { [string]$_.code })
    if ($codes.Count -lt 1) { throw '伪造 fileId 被拒但 issues 为空' }
    Ai-Cancel $s3.runId | Out-Null
    Ai-Cancel $s2.runId | Out-Null
    Ai-Cancel $s1b.runId | Out-Null
    return $null
}

Add-AiCase 'G-16' 'P1' '草稿不进服务端：表单挂起期服务端无草稿残留，提交后无遗留' {
    $sid = Ai-NewSid 'g16'
    # 标记带括号区分（同 G-04/G-17）：首条 user 文本是桩的轮次台账键，三条用例共用
    # `scenario=FILTER` 纯文本会共用台账（G-16 提交后的续轮请求若晚于 G-17 到达，G-17 会拿到
    # "已用过该路由"的文本收尾，从而拿不到表单）。
    $s = Ai-FormSuspend $sid 'scenario=FILTER（G-16 草稿不进服务端）'
    $pend = Gf-PendingEntry $s.runId $s.toolCallId
    foreach ($k in @('draft', 'model', 'values')) {
        if ($null -ne $pend.PSObject.Properties[$k]) { throw "pending 条目带 $k（表单草稿被写到服务端）" }
    }
    $hist = GetJson "/api/ai/history/$sid"
    $blob = ($hist.messages | ConvertTo-Json -Depth 8 -Compress)
    if ($blob -like '*E2E_DRAFT_SENTINEL*') { throw '服务端历史里出现草稿哨兵' }
    Ai-FormPost $s.runId $s.toolCallId '{"keyword":"E2EGFC"}' | Out-Null
    $hist2 = GetJson "/api/ai/history/$sid"
    if (($hist2.messages | ConvertTo-Json -Depth 8 -Compress) -like '*draft*') { throw '终态后历史里仍有草稿字段' }
    return $null
}

Add-AiCase 'G-17' 'P1' '结构化 issues 是点亮字段的数据源（key + code 齐备、可重试区分）' {
    $sid = Ai-NewSid 'g17'
    # 标记带括号区分（同 G-04/G-16）：与 G-16 共用纯 `scenario=FILTER` 时，台账复位依赖
    # "G-16 的续轮请求先到达"这一时序（本轮 nightly 实测：续轮迟到 ⇒ 本用例拿到文本收尾、零表单）。
    $s = Ai-FormSuspend $sid 'scenario=FILTER（G-17 结构化 issues）'
    $r = Ai-FormPost $s.runId $s.toolCallId '{"keyword":"E2EGFC","scope":"NOT_A_SCOPE"}'
    if ($r.status -ne 400) { throw "非法枚举值 HTTP $($r.status)，期望 400" }
    $issues = @($r.json.issues)
    if ($issues.Count -lt 1) { throw '400 缺 issues（前端只能显示一句笼统错误）' }
    $scoped = @($issues | Where-Object { $_.key -eq 'scope' })
    if ($scoped.Count -lt 1) { throw "issues 未指到 scope（实际 key：$((@($issues | ForEach-Object { $_.key })) -join ','))" }
    foreach ($i in $issues) {
        if (-not $i.code) { throw 'issues 条目缺 code（无法走 gf.errors.<CODE> 词表）' }
    }
    if ($r.json.retryable -ne $true) { throw '枚举越集应可改后重试（retryable=true）' }
    return $null
}

Say '--- 批① · SH 表格工具（10 条：披露/治理面 8 + 两条主用例 2） ---'

# 表格工具是**前端工具**（执行在前端 adapter），故 T 域的接口级断言落在
# 「披露清单 + 治理有效值 + 声明对账」三处；采样/批扫/diff 的算术属 adapter 单测面（蓝图 ✅ 单测列）。
# T-13 / T-14 是 L-21 的两条主用例（聚合卡 / 条件更新卡）：补的是**协议半段**——
#   模型工具调用 → frontend_tool_request 挂起 → 回灌 → tool_result/done 收敛 + 计数面。
#   卡面 DOM（预览卡渲染/点应用/导出字节）属批③ 浏览器面（设计稿 §4.4，Playwright 待裁），
#   桩里没有 DOM，故那半段不在此断言（如实登记，不以"跑了桩"充数）。
function Ai-SheetHealth {
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $h.ok -or $null -eq $h.json.sheet) { throw "GET /api/ai/health 缺 sheet 段（表格治理面未装配）" }
    return $h.json.sheet
}
function Ai-ToolsOf($query) { return @((GetJson ("/api/ai/tools?" + $query)).toolNames) }
# 表格工具的披露域 = task:EXPORT/EXPORT（frontend/src/types/tools.ts 的 scope 口径；page:tasks 不披露表格工具）
$script:aiSheetCtx = 'taskType=EXPORT&step=EXPORT'
# 危险工具的披露域（page:tasks 不披露 start_publish，见 TC15 的反向断言）
$script:aiPubCtx = 'page=import&taskType=IMPORT&step=PUBLISH'
$script:aiSheetRead = @('read_sheet_summary', 'aggregate_sheet', 'inspect_columns', 'review_cells', 'find_rows')
$script:aiSheetWrite = @('replace_values', 'update_rows', 'undo_last_sheet_edit')
# 表格工具的对话上下文（与 $script:aiSheetCtx 的查询口径同源：同一个披露域）
function Ai-SheetCtx($taskId) {
    return @{ page = 'tasks'; taskId = $taskId; taskType = 'EXPORT'; step = 'EXPORT' }
}
# 表格工具的审计计数面（/health.sheet.counters；进程内视图，多实例各一份 —— 只做增量判定）
function Ai-SheetCounters {
    $h = Ai-SheetHealth
    if ($null -eq $h.counters) { throw '/health.sheet 缺 counters 段（表格审计计数未暴露）' }
    return $h.counters
}
# 挂起后回灌（带 sheetMeta 的写侧/读侧元数据；白名单在入口过滤，业务值到此为止）
function Ai-SheetToolResult($runId, $toolCallId, $result, $meta) {
    return Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{
        runId = $runId; toolCallId = $toolCallId; result = $result; source = 'aie2e'; sheetMeta = $meta
    }
}

Add-AiCase 'T-02' 'P1' 'find_rows 在表格上下文披露，且采样/投影上界不超硬顶' {
    $h = Ai-SheetHealth
    $names = Ai-ToolsOf $script:aiSheetCtx
    if ($names -notcontains 'find_rows') { throw "page:tasks 未披露 find_rows（$($names -join ',')）" }
    if ([int]$h.sampleMaxRows -gt 20) { throw "sampleMaxRows=$($h.sampleMaxRows) 超过硬顶 20" }
    if ([int]$h.projectionMaxColumns -gt 5) { throw "projectionMaxColumns=$($h.projectionMaxColumns) 超过硬顶 5" }
    if ([int]$h.sampleMaxRows -lt 1) { throw 'sampleMaxRows<1（读侧无采样上限，整表会被送进模型）' }
    return $null
}

Add-AiCase 'T-04' 'P2' '批扫等价的数据面上界：采样闸有效值可读且不超过硬顶' {
    $h = Ai-SheetHealth
    if ($h.effectiveFrom -ne 'config') { throw "effectiveFrom=$($h.effectiveFrom)，期望 config（有效值来源不可读）" }
    foreach ($n in @($script:aiSheetRead)) {
        if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains $n) { throw "读工具 $n 未披露（批扫路径缺失）" }
    }
    return $null
}

Add-AiCase 'T-05' 'P1' 'count 预览快路径的工具面在位（read_sheet_summary 披露 + 读闸开）' {
    $h = Ai-SheetHealth
    if (-not [bool]$h.readEnabled) { throw 'readEnabled=false（读侧闸关闭，count 预览不可用）' }
    if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains 'read_sheet_summary') { throw 'read_sheet_summary 未披露' }
    return $null
}

Add-AiCase 'T-07' 'P1' '撤销入口随写闸披露（undo_last_sheet_edit 与 writeEnabled 一致）' {
    $h = Ai-SheetHealth
    $names = Ai-ToolsOf $script:aiSheetCtx
    $hasUndo = $names -contains 'undo_last_sheet_edit'
    if ([bool]$h.writeEnabled -ne $hasUndo) {
        throw "writeEnabled=$($h.writeEnabled) 与 undo 披露（$hasUndo）不一致（治理面与披露面不同源）"
    }
    return $null
}

Add-AiCase 'T-08' 'P1' '写工具声明与写闸一致（关闸 = 不披露，而不是调了再拒）' {
    $h = Ai-SheetHealth
    $names = Ai-ToolsOf $script:aiSheetCtx
    foreach ($w in @($script:aiSheetWrite)) {
        $declared = $names -contains $w
        if ([bool]$h.writeEnabled -and -not $declared) { throw "写闸开但 $w 未披露" }
        if (-not [bool]$h.writeEnabled -and $declared) { throw "写闸关但 $w 仍披露（模型会先调再被拒）" }
    }
    return $null
}

Add-AiCase 'T-09' 'P0' '只读壳只拦写 + 工具声明逐项可读（风险级/通道/词表键）' {
    $items = @((GetJson ("/api/ai/tools?" + $script:aiPubCtx)).tools)
    if ($items.Count -lt 1) { throw '/api/ai/tools 未返回 tools 明细（声明对账无对象）' }
    foreach ($it in $items) {
        if (-not $it.name) { throw 'tools 条目缺 name' }
        if (-not $it.riskLevel) { throw "工具 $($it.name) 缺 riskLevel" }
        if (-not $it.channel) { throw "工具 $($it.name) 缺 channel（前后端通道对账无依据）" }
        if (-not $it.titleKey) { throw "工具 $($it.name) 缺 titleKey（i18n 词表键缺失）" }
    }
    $pub = @($items | Where-Object { $_.name -eq 'start_publish' })
    if ($pub.Count -eq 1 -and $pub[0].riskLevel -notin @('DANGER', 'HIGH')) {
        throw "start_publish 的 riskLevel=$($pub[0].riskLevel)，期望 DANGER/HIGH（危险工具未被标级）"
    }
    return $null
}

Add-AiCase 'T-10' 'P1' '写关 = 退出披露面（有效值由 /health.sheet 下发、披露随之收敛）' {
    $h = Ai-SheetHealth
    if ($null -eq $h.writeEnabled) { throw '/health.sheet 未下发 writeEnabled（前端无法收紧上界）' }
    $names = Ai-ToolsOf $script:aiSheetCtx
    $writeShown = @($script:aiSheetWrite | Where-Object { $names -contains $_ })
    if (-not [bool]$h.writeEnabled -and $writeShown.Count -gt 0) {
        throw "写闸关但披露了写工具：$($writeShown -join ',')"
    }
    if ([bool]$h.writeEnabled -and $writeShown.Count -eq 0) { throw '写闸开但一个写工具都没披露' }
    return $null
}

Add-AiCase 'T-11' 'P2' '非选区路径投影列数受 ≤5 约束（选区放宽属 adapter 面）' {
    $h = Ai-SheetHealth
    if ([int]$h.projectionMaxColumns -lt 1) { throw 'projectionMaxColumns<1（非选区路径会回全表列）' }
    if ([int]$h.projectionMaxColumns -gt 5) { throw "projectionMaxColumns=$($h.projectionMaxColumns) 超过硬顶 5" }
    return $null
}

# L-21 主用例① 聚合卡：读链全链路（工具帧 → 挂起 → 回灌 → 收敛）。
# 桩侧底座 = stub-routes.json 路由 SHEET-AGG（requiresTools 硬闸：读闸关或非表格上下文即退化为文本轮）。
Add-AiCase 'T-13' 'P1' '聚合卡主用例：aggregate_sheet 工具帧 → 挂起 → 回灌 → 结局帧/done 收敛' {
    $h = Ai-SheetHealth
    if (-not [bool]$h.readEnabled) { throw 'readEnabled=false（读闸关：聚合卡在主用例链路上拿不到数据源）' }
    if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains 'aggregate_sheet') { throw 'aggregate_sheet 未披露（聚合卡不可达）' }
    $before = Ai-SheetCounters
    $t = Ai-ExportFixture 'AIE2E-T-13-聚合卡'
    $sid = Ai-NewSid 't13'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        # 标记后面带本次任务号：桩的"每路由一次"台账以**首条用户消息**为键（脚本重复跑/同机复跑
        # 若不换措辞，第二次就被当成同一轮的重复请求 ⇒ 退化为文本轮）。CI 每次重启桩，故只影响本机复跑。
        message = "【AIE2E】scenario=SHEET-AGG 任务 $t 这张表按小数位数各有多少种货币？"
        context = (Ai-SheetCtx $t)
    } @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) {
        $err = Ai-One $frames 'error'
        throw ("未收到 frontend_tool_request（聚合卡链路未触发$(if ($err) { "；error=$($err.code) $($err.message)" })）")
    }
    if ($req.name -ne 'aggregate_sheet') { throw "挂起工具是 $($req.name)，期望 aggregate_sheet" }
    Ai-SeqStrict $frames
    $toolArgs = $req.args | ConvertFrom-Json
    if ($toolArgs.defCode -ne 'CURRENCY') { throw "args.defCode=$($toolArgs.defCode)，期望 CURRENCY" }
    if (-not $toolArgs.groupBy) { throw 'args 缺 groupBy（聚合卡没有分组依据）' }
    # D2 边界：工具入参只有结构与标识，不得夹带表格业务行
    foreach ($bizValue in @('人民币', '美元', '7.24')) {
        if ([string]$req.args -like "*$bizValue*") { throw "工具入参出现了表内业务值「$bizValue」（入参越界）" }
    }
    # 卡面数据源：挂起条目必须是 FRONTEND 且可判（kind 决定前端走预览卡还是确认门）
    $entry = Gf-PendingEntry $req.runId $req.toolCallId
    if (-not $entry) { throw 'pending 里没有该 toolCallId 的条目（聚合卡无数据源）' }
    if ($entry.kind -ne 'FRONTEND') { throw "pending.kind=$($entry.kind)，期望 FRONTEND" }
    # 回灌（真实前端由 sheet-tools#aggregateSheet 产出这段文本）：文案原样进 tool_result 才算"卡上有数"
    $aggregate = "按 decimalPlaces 聚合：共 2 组（默认按 count 降序）`n  2 位 = 4 行`n  0 位 = 1 行`n共 5 行"
    $posted = Ai-SheetToolResult $req.runId $req.toolCallId $aggregate @{ dataClass = 'D1'; totalRows = 5; sheetVer = 1; defCode = 'CURRENCY' }
    if (-not $posted.ok) { throw "回灌 HTTP $($posted.status) $($posted.text)" }
    $after = Ai-Events $req.runId 0 @('done', 'error')
    # 前端工具的结局帧是 frontend_tool_result（kind=FRONTEND）；后端执行类工具才是 tool_result。
    # 两者同契约位置，按"带本 toolCallId 的结局帧"取，不预设哪一种（种类由 kind 决定）。
    $results = @($after | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req.toolCallId
    })
    if ($results.Count -ne 1) { throw "结局帧 $($results.Count) 个（期望恰 1）" }
    if ($results[0].type -ne 'frontend_tool_result') { throw "结局帧类型是 $($results[0].type)，期望 frontend_tool_result（FRONTEND 工具）" }
    if ([string]$results[0].result -notlike '*共 2 组*') { throw '结局帧 result 不含回灌文本（卡面数据被改写/丢失）' }
    if ($results[0].outcome -ne 'ok') { throw "结局帧 outcome=$($results[0].outcome)，期望 ok" }
    $done = Ai-Done $after
    Ai-DoneIsLast $after
    Ai-SeqStrict $after
    if (@($after | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req.toolCallId -and $_.seq -gt $done.seq
    }).Count -gt 0) {
        throw '结局帧落在了 done 之后（终帧不是最后帧）'
    }
    # 审计计数面（/health.sheet.counters）：读工具结算计入 readCalls
    $counters = Ai-SheetCounters
    if ([int]$counters.readCalls -le [int]$before.readCalls) { throw "readCalls 未增长（$($before.readCalls) → $($counters.readCalls)）：读工具结算没有进审计面" }
    Ai-Cancel $req.runId | Out-Null
    return $null
}

# L-21 主用例② 条件更新卡：写闸两态各自可判（写关 = 退出披露面；写开 = 挂起 → 回灌 → 收敛）。
# 桩侧底座 = stub-routes.json 路由 SHEET-UPDATE（requiresTools 含 update_rows ⇒ 写关时桩按披露清单退化为文本轮）。
Add-AiCase 'T-14' 'P1' '条件更新卡主用例：写闸关不披露（零工具帧）/ 写闸开挂起回灌收敛' {
    $h = Ai-SheetHealth
    $names = Ai-ToolsOf $script:aiSheetCtx
    $disclosed = $names -contains 'update_rows'
    if ([bool]$h.writeEnabled -ne $disclosed) {
        throw "writeEnabled=$($h.writeEnabled) 与 update_rows 披露($disclosed) 不一致（治理面与披露面不同源）"
    }
    $t = Ai-ExportFixture 'AIE2E-T-14-条件更新卡'
    $sid = Ai-NewSid 't14'
    # 同 T-13：标记后带任务号，避免"每路由一次"台账把本机复跑当成同一轮重复请求。
    $message = "【AIE2E】scenario=SHEET-UPDATE 任务 $t 把 code 为 USD 那一行的小数位数改成 3"
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = $message
        context = (Ai-SheetCtx $t)
    } @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $disclosed) {
        # 写关：模型手里没有这个工具 ⇒ 轮次以文本收尾、零工具帧；"预览卡"在结构上无从出现
        if ($req) { throw "写闸关但仍下发了 frontend_tool_request（$($req.name)：披露闸被绕过）" }
        if (@($frames | Where-Object { $_.type -eq 'tool_start' }).Count -gt 0) { throw '写闸关但仍有 tool_start 帧' }
        Ai-Done $frames | Out-Null
        Ai-SeqStrict $frames
        return $null
    }
    if (-not $req) {
        $err = Ai-One $frames 'error'
        throw ("写闸开但未收到 frontend_tool_request（条件更新卡未触发$(if ($err) { "；error=$($err.code) $($err.message)" })）")
    }
    if ($req.name -ne 'update_rows') { throw "挂起工具是 $($req.name)，期望 update_rows" }
    $toolArgs = $req.args | ConvertFrom-Json
    if ($toolArgs.defCode -ne 'CURRENCY') { throw "args.defCode=$($toolArgs.defCode)，期望 CURRENCY" }
    if (-not $toolArgs.predicate) { throw 'args 缺 predicate（条件更新卡的"条件"没有下发）' }
    if (@($toolArgs.set).Count -lt 1) { throw 'args 缺 set（条件更新卡没有写入声明）' }
    if ($toolArgs.scope -ne 'predicate') { throw "args.scope=$($toolArgs.scope)，期望 predicate（条件路径）" }
    # 表内其它业务行不得随入参出域（谓词值 = 模型自己给的条件，不算越界）
    foreach ($bizValue in @('人民币', '欧元', '7.24')) {
        if ([string]$req.args -like "*$bizValue*") { throw "工具入参出现了表内业务值「$bizValue」（入参越界）" }
    }
    $entry = Gf-PendingEntry $req.runId $req.toolCallId
    if (-not $entry) { throw 'pending 里没有该 toolCallId 的条目（条件更新卡无数据源）' }
    if ($entry.kind -ne 'FRONTEND') { throw "pending.kind=$($entry.kind)，期望 FRONTEND" }
    $before = Ai-SheetCounters
    # 回灌（真实前端在用户点"应用"后回灌）：计数档预览 + planId，样例对数 = 3 处
    $applied = "已应用 1 格（plan #e2e-plan-1，回到修改前值可撤销）。`n共 1 格"
    $posted = Ai-SheetToolResult $req.runId $req.toolCallId $applied @{
        dataClass = 'D2'; sampleRows = 1; planId = 'e2e-plan-1'; previewLevel = 'count'; sheetVer = 1; defCode = 'CURRENCY'
    }
    if (-not $posted.ok) { throw "回灌 HTTP $($posted.status) $($posted.text)" }
    $after = Ai-Events $req.runId 0 @('done', 'error')
    $results = @($after | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req.toolCallId
    })
    if ($results.Count -ne 1) { throw "结局帧 $($results.Count) 个（期望恰 1）" }
    if ([string]$results[0].result -notlike '*plan #e2e-plan-1*') { throw '结局帧 result 不含回灌文本（应用回执被改写/丢失）' }
    if ($results[0].outcome -ne 'ok') { throw "结局帧 outcome=$($results[0].outcome)，期望 ok" }
    Ai-Done $after | Out-Null
    Ai-DoneIsLast $after
    Ai-SeqStrict $after
    # 审计计数面：带 planId 的写工具结算计入 writePlans（授权记录的数据源）
    $counters = Ai-SheetCounters
    if ([int]$counters.writePlans -le [int]$before.writePlans) { throw "writePlans 未增长（$($before.writePlans) → $($counters.writePlans)）：写计划没进审计面" }
    Ai-Cancel $req.runId | Out-Null
    return $null
}

Say '--- 批① · KB 知识问答（6 条：KB 端点契约 + 工具披露） ---'

Add-AiCase 'K-03' 'P1' 'kb_read_doc 披露且文档目录可读（首屏/分页的数据源在位）' {
    $kb = Invoke-Api 'GET' '/api/ai/kb/status' $null
    if (-not $kb.ok) { throw "GET /api/ai/kb/status => HTTP $($kb.status)（KB 面不可用）" }
    $docs = Invoke-Api 'GET' '/api/ai/kb/docs' $null
    if (-not $docs.ok) { throw "GET /api/ai/kb/docs => HTTP $($docs.status)" }
    if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains 'kb_read_doc') { throw 'kb_read_doc 未披露' }
    return $null
}

Add-AiCase 'K-04' 'P1' 'kb_lookup_term 披露（精确+别名通道在位）' {
    if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains 'kb_lookup_term') { throw 'kb_lookup_term 未披露' }
    $kb = GetJson '/api/ai/kb/status'
    if ($null -eq $kb) { throw 'KB status 缺 data 段' }
    return $null
}

Add-AiCase 'K-05' 'P1' '目录段注入的可见面：KB 状态可读、多轮上下文稳定' {
    $docs1 = Invoke-Api 'GET' '/api/ai/kb/docs' $null
    if (-not $docs1.ok) { throw "GET /api/ai/kb/docs => HTTP $($docs1.status)" }
    $sid = Ai-NewSid 'k05'
    $t1 = Invoke-AiTurn $sid '【AIE2E-K-05】turn-text 知识库目录一句话说明。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $t1.frames | Out-Null
    $t2 = Invoke-AiTurn $sid '【AIE2E-K-05】turn-text 再问一句。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $t2.frames | Out-Null
    $r = Ai-Run (Ai-RunId $t2.frames)
    if ($null -eq $r.context) { throw '轮次快照缺 context（系统上下文的上下文面不可读）' }
    return $null
}

Add-AiCase 'K-06' 'P0' '索引未就绪文案分流：status 如实 + 不 5xx + 可行动' {
    $kb = Invoke-Api 'GET' '/api/ai/kb/status' $null
    if (-not $kb.ok) { throw "索引状态端点 => HTTP $($kb.status)（应如实返回状态而非 5xx）" }
    # 状态面的分流判据（AiKbController#toMap 的键集）：enabled（总开关）/ ready（索引就绪）/
    # last_error（建索引失败原因；空串 = 首次建索引进行中）。旧断言要求的 available/message
    # 从未在响应里出现过 —— 那是目标态，不是现行契约。
    foreach ($k in @('enabled', 'ready')) {
        if ($null -eq $kb.json.data.PSObject.Properties[$k]) { throw "KB status 缺 $k（前端无法分流'准备中'文案）" }
    }
    if ($null -eq $kb.json.data.PSObject.Properties['last_error']) {
        throw 'KB status 缺 last_error（"建索引中"与"建索引失败"无法区分，用户只看到空白）'
    }
    $reload = Invoke-Api 'POST' '/api/ai/kb/reload' $null
    if ($reload.status -ge 500) { throw "KB reload => HTTP $($reload.status)（重建失败不该 5xx）" }
    return $null
}

Add-AiCase 'K-07' 'P1' '缓存/版本传播面：status 暴露版本或缓存态（判据数据源在下发面）' {
    $kb = GetJson '/api/ai/kb/status'
    $fields = @($kb.PSObject.Properties | ForEach-Object { $_.Name })
    $hit = @($fields | Where-Object { $_ -match 'version|cache|stale|builtAt|indexedAt|docCount' })
    if ($hit.Count -lt 1) { throw "KB status 未暴露版本/缓存字段（实际：$($fields -join ',')）" }
    return $null
}

Add-AiCase 'K-08' 'P1' '热重载原子切：reload 期间查询无 5xx' {
    $before = Invoke-Api 'GET' '/api/ai/kb/status' $null
    if (-not $before.ok) { throw "reload 前 status => HTTP $($before.status)" }
    Invoke-Api 'POST' '/api/ai/kb/reload' $null | Out-Null
    for ($i = 0; $i -lt 3; $i++) {
        $r = Invoke-Api 'GET' '/api/ai/kb/status' $null
        if (-not $r.ok) { throw "reload 后第 $($i + 1) 次 status => HTTP $($r.status)（重建期查询失败）" }
        Start-Sleep -Milliseconds 200
    }
    return $null
}

Say '--- 批① · QC 快捷操作（4 条：发送路径与幻影面，200/409 两果都合法；409 的确定性面见 DEG 的 D-13） ---'

Add-AiCase 'Q-04' 'P2' '历史重载后不出现重复 messageId（无半旧半新错配）' {
    $sid = Ai-NewSid 'q04'
    $t1 = Invoke-AiTurn $sid '【AIE2E-Q-04】turn-text 第一轮。' @{ page = 'tasks' } $script:aiContinue 2
    $run1 = Ai-RunId $t1.frames
    $h1 = GetJson "/api/ai/history/$sid"
    $t2 = Invoke-AiTurn $sid '【AIE2E-Q-04】turn-text 第二轮。' @{ page = 'tasks' } $script:aiContinue 2
    $run2 = Ai-RunId $t2.frames
    $h2 = GetJson "/api/ai/history/$sid"
    if ($h2.count -le $h1.count) { throw "第二轮后历史条数 $($h1.count) → $($h2.count)（历史未增长，判据无对象）" }
    if ($run2 -eq $run1) { throw '两轮共用同一 runId（轮次未推进）' }
    $ids = @($h2.messages | ForEach-Object { $_.id } | Where-Object { $_ })
    if ($ids.Count -gt 0 -and @($ids | Sort-Object -Unique).Count -ne $ids.Count) { throw '历史里出现重复 messageId（整替错配）' }
    return $null
}

Add-AiCase 'Q-05' 'P1' '流式中禁用（AR-04 两果都合法）：同会话并发发轮 —— 409 走忙回执 / 200 走独立轮；无幻影/不污染按 run 维度先断' {
    # 原口径"挂起后同会话必 409"在现行语义下不可确定：park 后由驱动线程 finally 释放会话门，挂起帧与
    # 释放之间有一段无读侧同步信号的窗口（与 Q-08 同一个病）。⇒ 与 Q-08 同口径：**无幻影先断**，
    # 再按 409 / 200 分支断；409 的确定性覆盖在 D-13（可控时序窗口），不在这里靠时序赌。
    # 载体（复审 N2）：正向断言（受理恰一次 / 旧段不被改写）走 run 维度 —— /api/ai/runs/{runId} 的
    # snapshot+historyRoles；Redis 窗口是 convergeMemory 的整窗覆盖，取消挂起轮会把它改写成挂起轮自己的
    # 快照，因此窗口只做反向断言（未受理 token 零条）与挂起轮收敛后的正控。
    $t = Ai-PublishFixture 'AIE2E-Q-05-并发'
    $sid = Ai-NewSid 'q05'
    $parkToken = 'q05-first-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("【AIE2E-Q-05】turn-text {0} 请立即调用 start_publish 工具发布导入任务 {1}（系统会自动弹出确认卡片，无需再询问我）" -f $parkToken, $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起（并发面现场不成立）' }
    $null = Ai-WaitStatus $cr.runId @('SUSPENDED') 20

    # 轮次身份与"只落一条用户原话"基线（run 维度）必须排在任何状态码断言之前。
    $st5 = Ai-F $frames @('start')
    if ($st5.Count -ne 1) { throw "挂起轮 start 帧 $($st5.Count) 个（期望恰 1；幻影判据无对象）" }
    $parkInfo5 = Ai-RunRoleInfo $cr.runId
    Ai-AssertSingleTurnTail $parkInfo5 'Q-05 挂起轮'

    # 同会话并发再发（"点建议"路径）。只发一次：不 sleep、不重试；两果都收。
    $token = 'q05-second-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $secondText = '【AIE2E-Q-05】turn-text ' + $token + ' （点建议）继续。'
    $second = Ai-ChatSse $sid $secondText @{ page = 'tasks' } @('confirm_request', 'frontend_tool_request', 'done', 'error') $null
    $secondStatus = [int]$second.status

    # ── 幻影轮次（帧面，先断、与状态码无关）：未受理（非 200）的发送一个 SSE 帧都不许产出 ──
    if ($secondStatus -ne 200 -and @($second.frames).Count -ne 0) {
        throw "未受理的发送（HTTP $secondStatus）却产出了 $(@($second.frames).Count) 个 SSE 帧（幻影轮次）"
    }
    # 历史不污染（run 维度）：并发发送不得改写挂起轮自己的快照（状态/消息数/角色序列逐项不变）。
    Ai-AssertRunUnchanged $parkInfo5 'Q-05 并发发送后'

    # ── 状态面 ──
    if ($secondStatus -eq 200) {
        # 被受理 ⇒ 真的开了独立一轮：恰 1 start / 无 error / 恰 1 done / 非 cancelled / runId 不复用挂起轮
        if ((Ai-F $second.frames @('start')).Count -ne 1) { throw "受理轮 start 帧 $((Ai-F $second.frames @('start')).Count) 个（期望恰 1）" }
        if ((Ai-F $second.frames @('error')).Count -ne 0) { throw '受理轮出现 error 帧' }
        $done5 = Ai-Done $second.frames
        if ($done5.cancelled -eq $true) { throw '受理轮以 cancelled 收尾' }
        $secondRun5 = [string](Ai-RunId $second.frames)
        if ($secondRun5 -eq [string]$cr.runId) { throw '受理轮复用了挂起轮的 runId（新轮没有真正开起来）' }
        # 受理恰一次（run 维度）：本轮快照尾部恰 USER + ASSISTANT 各一条。
        $secondInfo5 = Ai-RunRoleInfo $secondRun5
        Ai-AssertSingleTurnTail $secondInfo5 'Q-05 受理轮'
        $ids5 = Ai-AssistantIds $second.frames
        if ($ids5.Count -eq 0) { throw '受理轮没有任何 assistant 消息身份（判据无对象，不得当通过）' }
        if ($ids5.Count -ne 1) { throw "受理轮 assistant messageId 有 $($ids5.Count) 个（同一条消息被拆成多身份）：$($ids5 -join ',')" }
    }
    elseif ($secondStatus -eq 409) {
        # 被拒 ⇒ 必须是真实忙回执：码 + 占门轮 runId + 重挂端点（前端可接管），且零 SSE 帧
        if (-not $second.json) { throw "409 无 JSON 体（$($second.text)）" }
        if ([string]$second.json.code -ne 'SESSION_BUSY') { throw "409 码 $($second.json.code)，期望 SESSION_BUSY" }
        if ([string]$second.json.runId -ne [string]$cr.runId) { throw "409 的 runId=$($second.json.runId) ≠ 占门轮 runId=$($cr.runId)" }
        if ([string]$second.json.reattach -ne "/api/ai/events/$($cr.runId)") { throw "409 的 reattach=$($second.json.reattach)，期望 /api/ai/events/$($cr.runId)" }
        if (@($second.frames).Count -ne 0) { throw "被拒请求却产出了 $(@($second.frames).Count) 个 SSE 帧" }
    }
    else {
        throw "同会话并发发轮 HTTP $secondStatus（只接受 200 受理 / 409 忙回执两果；$($second.text)）"
    }

    # ── 窗口（仅反向断言 + 挂起轮正控）──
    # 取消挂起轮（与本用例原顺序一致）→ 等收敛：窗口此刻由挂起轮自己的 snapshot 整窗覆写，
    # 所以只断言"挂起轮原文恰 1 条"这条正控 + "未受理 token 零条"这条反向断言。
    Ai-Cancel $cr.runId | Out-Null
    $null = Ai-WaitWindowToken $sid $parkToken 20
    $win5 = Ai-Window $sid
    $parkHits5 = @($win5 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$parkToken*" })
    if ($parkHits5.Count -ne 1) { throw "挂起轮收敛后窗口里有 $($parkHits5.Count) 条该轮用户消息（期望 1）" }
    if ($secondStatus -ne 200) {
        $secondHits5 = @($win5 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$token*" })
        if ($secondHits5.Count -ne 0) { throw "未受理的发送却在窗口里落了 $($secondHits5.Count) 条用户消息（幻影气泡）" }
    }
    return $null
}

Add-AiCase 'Q-06' 'P2' 'i18n 词表对称：工具 titleKey/lang 齐备' {
    $items = @((GetJson '/api/ai/tools?page=tasks').tools)
    if ($items.Count -lt 1) { throw '/api/ai/tools 无明细' }
    foreach ($it in $items) {
        if ($it.titleKey -notlike 'tool.*') { throw "工具 $($it.name) 的 titleKey=$($it.titleKey) 不在 tool.* 词表命名内" }
        if ([string]::IsNullOrWhiteSpace([string]$it.lang)) { throw "工具 $($it.name) 缺 lang（双语取值面不可判）" }
    }
    return $null
}

Add-AiCase 'Q-08' 'P1' '点即发与 409 的交互（AR-04）：同会话再发两果都合法 —— 200 走完成断言 / 409 走忙回执；历史不污染 + 无幻影气泡 + 无假提示' {
    # ===== AR-04（2026-10-26 裁决）的用例体口径 =====
    #  1) 产品语义**不动**：park 后由驱动线程 finally 释放会话门（AiController#chat），续跑方用同一
    #     runId 重新取门 ⇒「挂起」≠「占门」。旧用例断言"收到挂起帧就必 409"，而挂起帧到 finally
    #     之间有一段窗口 ⇒ 同一份代码会 409 / 200 两果（主 CI 40255993044 attempt1 绿、attempt2 红）。
    #  2) 那段窗口**没有读侧同步信号**（/ai/runs/{runId} 的 keys 不含 ai:session:lock:<sid>），所以本
    #     用例**接受两果**：200 = 门已释放、新轮被受理；409 = 门仍被占（同一次释放窗口的另一侧）。
    #     两者都是现行语义下的合法结果 —— 不用 sleep / 重试去"凑"其中一种（那正是旧用例的病）。
    #  3) 顺序（AR-04 §2.4）：**无幻影 / 不污染先断、与状态码无关** ——
    #     数据库完整历史端点（/history/{sid}/messages）已随 ai_chat_message 链路移除，载体改为：
    #     ① 帧面（本地，无 I/O）：未受理（非 200）的发送一个 SSE 帧都不许产出；受理轮的 start/done/runId
    #        在状态分支断言（= 真的开了独立一轮，不是幻影轮次）；
    #     ② **run 维度**（复审 N2）：/api/ai/runs/{runId} 的 snapshot+historyRoles ——
    #        · 历史不污染：第二次发送后挂起轮自己的快照必须逐项不变（状态/消息数/角色序列）；
    #        · 无幻影气泡 / 受理恰一次：受理轮快照尾部恰一条 USER + 一条 ASSISTANT；
    #        · messageId 幂等：受理轮 assistant 帧共用一个 messageId。
    #     为什么不用 Redis 窗口做这些正向断言：convergeMemory 是整窗覆盖，随后取消挂起轮会用挂起轮的
    #     snapshot 覆写窗口，把更晚的受理轮消息抹掉（窗口只用于"未受理 token 零条"这类反向断言）。
    #     ③ 之后才按分支断状态面与"无假提示"：
    #        200 ⇒ 完成断言（恰 1 start / 恰 1 done / done 是最后帧 / seq 严格递增 / 无 error / 非 cancelled）；
    #        409 ⇒ 忙回执断言（SESSION_BUSY + runId 指向占门轮 + reattach 指向它的重挂端点 + 零 SSE 帧）。
    #  4) 409 的**确定性**覆盖不放在本节（否则与 D-13 重复）：D-13 用可控时序窗口保证门被持有。
    $t = Ai-PublishFixture 'AIE2E-Q-08-幻影'
    $sid = Ai-NewSid 'q08'
    $parkToken = 'q08-first-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $parkText = ("【AIE2E-Q-08】turn-text {0} 请立即调用 start_publish 工具发布导入任务 {1}（系统会自动弹出确认卡片，无需再询问我）" -f $parkToken, $t)
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = $parkText; context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起（点即发场景的现场不成立：没有卡就不会走这条入口）' }

    # 轮 1 的落定面（都是产品事实，不是 sleep）：快照 SUSPENDED / 未决条目已公布 / runs/current 指向它。
    # 旧用例的前提错在"收到挂起帧就算 409 前提成立"，这里改成"这一轮确实已挂起落定"。
    $null = Ai-WaitStatus $cr.runId @('SUSPENDED') 20
    $entry = Gf-PendingEntry $cr.runId $cr.toolCallId
    if ($entry.status -ne 'PENDING') { throw "挂起后条目状态 $($entry.status)，期望 PENDING（点即发现场不成立）" }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found -or $cur.runId -ne $cr.runId -or $cur.snapshotStatus -ne 'SUSPENDED') {
        throw "runs/current 未指向挂起轮（found=$($cur.found) runId=$($cur.runId) status=$($cur.snapshotStatus)）"
    }

    # 轮次身份与"只落一条用户原话"基线（run 维度）必须排在任何状态码断言之前（AR-04 §2.4）。
    $st8 = Ai-F $frames @('start')
    if ($st8.Count -ne 1) { throw "挂起轮 start 帧 $($st8.Count) 个（期望恰 1；幻影判据无对象）" }
    if ([string]$st8[0].runId -ne [string]$cr.runId) {
        throw "挂起轮 start.runId=$($st8[0].runId) ≠ confirm_request.runId=$($cr.runId)（轮次身份错配）"
    }
    $parkInfo8 = Ai-RunRoleInfo $cr.runId
    Ai-AssertSingleTurnTail $parkInfo8 'Q-08 挂起轮'

    # 第二次发送（"点建议"路径）。只发一次：不 sleep、不重试；两果都收（见文件头 2)。
    $token = 'q08-second-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $secondText = '【AIE2E-Q-08】turn-text ' + $token + ' 点建议换成另一个问题。'
    $second = Ai-ChatSse $sid $secondText @{ page = 'tasks' } @('confirm_request', 'frontend_tool_request', 'done', 'error') $null
    $secondStatus = [int]$second.status
    $accepted = ($secondStatus -eq 200)

    # ── 幻影轮次（帧面，先断、与状态码无关）：未受理（非 200）的发送一个 SSE 帧都不许产出 ──
    if (-not $accepted -and @($second.frames).Count -ne 0) {
        throw "未受理的发送（HTTP $secondStatus）却产出了 $(@($second.frames).Count) 个 SSE 帧（幻影轮次）"
    }
    # 历史不污染（run 维度）：第二次发送不得改写挂起轮自己的快照（状态/消息数/角色序列逐项不变）。
    Ai-AssertRunUnchanged $parkInfo8 'Q-08 第二次发送后'

    # ── ③ 无假提示 + 状态面 ──
    if ($accepted) {
        # 200：受理轮的完成面（与既有纯文本轮同口径）—— 前端据此出正常答复，不会给"没发出去"的提示。
        if ((Ai-F $second.frames @('start')).Count -ne 1) { throw "受理轮 start 帧 $((Ai-F $second.frames @('start')).Count) 个（期望恰 1）" }
        if ((Ai-F $second.frames @('error')).Count -ne 0) { throw '受理的轮次出现 error 帧（前端会给出假提示）' }
        $done2 = Ai-Done $second.frames
        Ai-DoneIsLast $second.frames
        Ai-SeqStrict $second.frames
        if ($done2.cancelled -eq $true) { throw '受理的轮次以 cancelled 收尾（被当成取消 = 假提示）' }
        $run2 = [string](Ai-RunId $second.frames)
        if ($run2 -eq [string]$cr.runId) { throw '受理轮复用了挂起轮的 runId（新轮没有真正开起来）' }
        # 受理恰一次（run 维度）：本轮快照尾部恰 USER + ASSISTANT 各一条。
        $secondInfo8 = Ai-RunRoleInfo $run2
        Ai-AssertSingleTurnTail $secondInfo8 'Q-08 受理轮'
        $ids8 = Ai-AssistantIds $second.frames
        if ($ids8.Count -eq 0) { throw '受理轮没有任何 assistant 消息身份（判据无对象，不得当通过）' }
        if ($ids8.Count -ne 1) {
            throw "受理轮 assistant messageId 有 $($ids8.Count) 个（同一条消息被拆成多身份）：$($ids8 -join ',')"
        }
    }
    elseif ($secondStatus -eq 409) {
        # 409：必须是**真实忙回执**（码 + 占门轮 runId + 重挂端点三件齐备）⇒ 前端能接管，
        # 不是"未分类错误 / 请稍后再试"这类无行动性的假提示。
        if (-not $second.json) { throw "409 无 JSON 体（$($second.text)）" }
        if ([string]$second.json.code -ne 'SESSION_BUSY') { throw "409 码 $($second.json.code)，期望 SESSION_BUSY" }
        if ([string]$second.json.runId -ne [string]$cr.runId) {
            throw "409 的 runId=$($second.json.runId) ≠ 占门轮 runId=$($cr.runId)（回执指错轮，前端会接管错流）"
        }
        if ([string]$second.json.reattach -ne "/api/ai/events/$($cr.runId)") {
            throw "409 的 reattach=$($second.json.reattach)，期望 /api/ai/events/$($cr.runId)（前端无法接管）"
        }
        if (@($second.frames).Count -ne 0) { throw "被拒请求却产出了 $($second.frames.Count) 个 SSE 帧（未受理的轮不应有帧）" }
    }
    else {
        throw "同会话第二次发送 HTTP $secondStatus（只接受 200 受理 / 409 忙回执两果；$($second.text)）"
    }

    # ── 窗口（仅反向断言 + 挂起轮正控）──
    # 取消挂起轮（与本用例原顺序一致）→ 等收敛：窗口此刻由挂起轮自己的 snapshot 整窗覆写，
    # 只断言"挂起轮用户原文恰 1 条"（正控）与"未受理 token 零条"（反向）。正向判据已在 run 维度断完。
    # 说明：入库正文经过归一化（NFKC，全角括号会变 ASCII），这里用 ASCII 唯一 token 定位，不做发送原文比对。
    Ai-Cancel $cr.runId | Out-Null
    $null = Ai-WaitWindowToken $sid $parkToken 20
    $win8 = Ai-Window $sid
    $parkHits8 = @($win8 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$parkToken*" })
    if ($parkHits8.Count -ne 1) {
        throw "挂起轮收敛后窗口里有 $($parkHits8.Count) 条该轮用户消息（期望 1）"
    }
    if (-not $accepted) {
        $secondHits8 = @($win8 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$token*" })
        if ($secondHits8.Count -ne 0) {
            throw "未受理的发送却在窗口里落了 $($secondHits8.Count) 条用户消息（幻影气泡）"
        }
    }
    return $null
}

Say '--- 批① · CTX 上下文与记忆（8 条） ---'

Add-AiCase 'C-07' 'P2' '六项拆分守恒：Σ 分项 == used' {
    $sid = Ai-NewSid 'c07'
    $t = Invoke-AiTurn $sid '【AIE2E-C-07】turn-text 上下文拆分。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $t.frames | Out-Null
    $snap = Ai-Context $sid
    $parts = @($snap.breakdown)
    # 分项数由后端 ContextBreakdown 决定（本批只锁守恒，不锁项数与键名——那是实现细节）
    if ($parts.Count -lt 1) { throw 'breakdown 为空（分项守恒无对象）' }
    $sum = 0
    foreach ($p in $parts) { if ($p.key -ne 'free') { $sum += [int]$p.tokens } }
    if ($sum -ne [int]$snap.usedTokens) { throw "Σ=$sum ≠ usedTokens=$($snap.usedTokens)" }
    if ($null -eq $snap.turnNo) { throw '缺 turnNo' }
    return $null
}

Add-AiCase 'C-08' 'P1' 'L1 清除面不暴露内部键（占位符由后端单测锁措辞）' {
    $snap = Ai-Context (Ai-NewSid 'c08')
    $body = $snap | ConvertTo-Json -Depth 8 -Compress
    if ($body -like '*ai:ledger:*' -or $body -like '*ai:events:*' -or $body -like '*ai:ctx:*') { throw 'context 响应泄露内部 Redis 键' }
    if ($null -eq (Invoke-Api 'GET' '/api/ai/health' $null).json.memoryBackend) { throw 'health 缺 memoryBackend（记忆后端不可判）' }
    return $null
}

Add-AiCase 'C-09' 'P1' 'L2 滑窗按 turn 整轮淘汰：多轮后 turnNo 单调、末轮不被淘汰' {
    $sid = Ai-NewSid 'c09'
    for ($i = 1; $i -le 3; $i++) {
        $t = Invoke-AiTurn $sid ("【AIE2E-C-09】turn-text 第 $i 轮填充。" + ('历史填充文本。' * 20)) @{ page = 'tasks' } $script:aiContinue 2
        Ai-Done $t.frames | Out-Null
    }
    $snap = Ai-Context $sid
    if ([int]$snap.turnNo -lt 3) { throw "turnNo=$($snap.turnNo)，期望 ≥3" }
    $hist = GetJson "/api/ai/history/$sid"
    $asst = @($hist.messages | Where-Object { $_.type -eq 'ASSISTANT' })
    if ($asst.Count -lt 1) { throw '历史里没有助手消息' }
    if ([string]::IsNullOrWhiteSpace([string]$asst[$asst.Count - 1].text)) { throw '末轮助手文本被压缩清空（末轮不得被淘汰）' }
    return $null
}

Add-AiCase 'C-10' 'P1' '摘要 5xx 失败不静默：level 不升高、历史不被重写' {
    $sid = Ai-NewSid 'c10'
    $t1 = Invoke-AiTurn $sid '【AIE2E-C-10】turn-text summary-500 第一轮。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $t1.frames | Out-Null
    $h1 = (GetJson "/api/ai/history/$sid") | ConvertTo-Json -Depth 10 -Compress
    $c1 = Ai-Context $sid
    $t2 = Invoke-AiTurn $sid '【AIE2E-C-10】turn-text summary-500 第二轮（摘要注入已 arm）。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $t2.frames | Out-Null
    $c2 = Ai-Context $sid
    # 压缩级别在 context 的 compaction.level（ContextBreakdownService#compaction）；
    # 顶层没有 level 键 —— 读顶层会得到 $null 并把断言变成恒真（假绿），故显式取嵌套值。
    if ([string]$c2.compaction.level -ne [string]$c1.compaction.level) {
        throw "摘要 5xx 后 level 由 $($c1.compaction.level) 升到 $($c2.compaction.level)（失败被当成压缩成功）"
    }
    $h2 = (GetJson "/api/ai/history/$sid") | ConvertTo-Json -Depth 10 -Compress
    if ($h2.Length -le $h1.Length) { throw '第二轮后历史没有增长（历史被摘要请求重写）' }
    return $null
}

Add-AiCase 'C-11' 'P1' '记忆窗口：预算面可读且未决 HITL 条目仍在' {
    $t = Ai-PublishFixture 'AIE2E-C-11-记忆'
    $sid = Ai-NewSid 'c11'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起' }
    $entry = Gf-PendingEntry $cr.runId $cr.toolCallId
    if ($entry.status -ne 'PENDING') { throw "挂起后条目状态 $($entry.status)" }
    $snap = Ai-Context $sid
    # 挂起期的预算面：记账户只在轮次的终态出口落笔（done/cancelled，见 contextTelemetry），
    # 故挂起中 usedTokens=0 且 source=none 是当前实现的**如实**形态（不是编造数字）。
    # 这里锁"如实与自洽"，不锁"挂起中必须 >0"（设计稿 C-11 的判据是记忆窗口/淘汰面，
    # 没有承诺挂起中的占用值；硬要求 >0 等于要一个未设计的中途记账点）。
    if ($null -eq $snap.PSObject.Properties['usedTokens']) { throw 'context 缺 usedTokens（预算面不可读）' }
    if (@('none', 'stream', 'estimate') -notcontains [string]$snap.source) { throw "source=$($snap.source) 不在三值内（计量来源不可判）" }
    if ([string]$snap.source -eq 'none' -and [int]$snap.usedTokens -ne 0) { throw 'source=none 却给出非 0 占用（凭空造数）' }
    # 分母可为 null（C-02 的"分母未知"降级态）；但 null 时不得显百分比（否则就是编造分母）
    if ($null -eq $snap.limitTokens -and $null -ne $snap.percent) { throw 'limitTokens=null 却下发了 percent（编造分母）' }
    # 未决 HITL 条目在记忆窗口里永不淘汰：取消这一轮后条目仍可按 runId 找到、状态为终态而非"消失"
    Ai-Cancel $cr.runId | Out-Null
    $after = Wait-Until { $x = Ai-Context $sid; if ([int]$x.usedTokens -ge 1) { return $x } return $null } 20 '轮次收敛后预算面仍不可读（usedTokens 恒 0）'
    if ([int]$after.usedTokens -lt 1) { throw "轮次收敛后 usedTokens=$($after.usedTokens)，预算面不可判" }
    return $null
}

Add-AiCase 'C-12' 'P1' 'CONTEXT_OVERFLOW 识别：越窗 400 映射为不可重试的结构化码' {
    $sid = Ai-NewSid 'c12'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-C-12】turn-text context-overflow 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $term = @($frames | Where-Object { $_.type -eq 'error' -or $_.type -eq 'done' })
    if ($term.Count -lt 1) { throw '越窗后没有任何终态（用户停在等待态）' }
    $last = $term[$term.Count - 1]
    $code = if ($last.type -eq 'error') { [string]$last.code } else { [string]$last.error }
    if ($code -notlike '*CONTEXT_OVERFLOW*') { throw "终态码=$code，期望 CONTEXT_OVERFLOW（被当成泛化上游错误会触发无意义重试）" }
    return $null
}

Add-AiCase 'C-13' 'P2' '压缩级别只升一级：单轮观察 level 至多前进一档' {
    $sid = Ai-NewSid 'c13'
    $levels = @()
    for ($i = 1; $i -le 2; $i++) {
        $t = Invoke-AiTurn $sid ("【AIE2E-C-13】turn-text 第 $i 轮。" + ('压缩级别填充。' * 30)) @{ page = 'tasks' } $script:aiContinue 2
        Ai-Done $t.frames | Out-Null
        $levels += ([string](Ai-Context $sid).compaction.level).ToLower()
    }
    $order = @('none', 'l1', 'l2', 'l3')
    for ($i = 1; $i -lt $levels.Count; $i++) {
        $a = [array]::IndexOf($order, $levels[$i - 1])
        $b = [array]::IndexOf($order, $levels[$i])
        if ($a -lt 0 -or $b -lt 0) { throw "level 取值异常：$($levels -join ' → ')" }
        if ($b - $a -gt 1) { throw "level 一次跳了 $($b - $a) 档：$($levels -join ' → ')" }
    }
    return $null
}

Add-AiCase 'C-14' 'P2' '归档寿命可判：context 不泄露归档键、health 下发 sessionTtl' {
    $snap = Ai-Context (Ai-NewSid 'c14')
    $body = $snap | ConvertTo-Json -Depth 8 -Compress
    if ($body -like '*ai:ctx:archive:*') { throw 'context 响应暴露归档键（内部键面泄露）' }
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $h.ok) { throw "GET /api/ai/health => HTTP $($h.status)" }
    if ([string]::IsNullOrWhiteSpace([string]$h.json.sessionTtl)) { throw 'health 未下发 sessionTtl（归档寿命不可判）' }
    return $null
}


Say '--- 批① · DEG 降级与错误面（12 条；D-13 = AR-04 的可控时序 409） ---'

Add-AiCase 'D-01' 'P1' 'Redis 探活面如实（不静默挂、不 500）' {
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $h.ok) { throw "GET /api/ai/health => HTTP $($h.status)" }
    if ($null -eq $h.json.redis) { throw 'health 缺 redis 段（探活不可见）' }
    if ($null -eq $h.json.redis.PSObject.Properties['available']) { throw 'redis 段缺 available' }
    $sid = Ai-NewSid 'd01'
    $r = Ai-ChatRaw $sid '你好' @{ page = 'tasks' }
    if ($r.status -ge 500) { throw "Redis 可用时 /api/ai/chat => HTTP $($r.status)（不应 500）" }
    return $null
}

Add-AiCase 'D-02' 'P1' '模型可用性三档码：health.available 与 code 同源' {
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if ($null -eq $h.json.available) { throw 'health 缺 available' }
    $code = [string]$h.json.code
    if ([string]::IsNullOrWhiteSpace($code)) { throw 'health 缺 code（三档码之一）' }
    if ([bool]$h.json.available -and $code -ne 'AI_AVAILABLE') { throw "available=true 但 code=$code" }
    if (-not [bool]$h.json.available -and $code -eq 'AI_AVAILABLE') { throw 'available=false 但 code=AI_AVAILABLE' }
    return $null
}

Add-AiCase 'D-03' 'P2' '续跑契约：未知 run 的 resume 明确 404（不静默换模型）' {
    $r = Invoke-Api 'POST' '/api/ai/runs/no-such-run-aie2e/resume' $null
    if ($r.status -notin @(404, 409)) { throw "未知 run 续跑 HTTP $($r.status)，期望 404（或 409 家族）" }
    return $null
}

Add-AiCase 'D-04' 'P2' '无工具调用轮：tool_start 零帧、done 正常（不报错）' {
    $sid = Ai-NewSid 'd04'
    $turn = Invoke-AiTurn $sid '【AIE2E-D-04】turn-text 只聊天，不调用任何工具。' @{ page = 'tasks' } $script:aiContinue 2
    if ((Ai-F $turn.frames @('tool_start')).Count -ne 0) { throw '纯文本轮出现 tool_start' }
    Ai-Done $turn.frames | Out-Null
    if ((Ai-F $turn.frames @('error')).Count -gt 0) { throw '纯文本轮出现 error 帧（不支持工具调用时应静默降级）' }
    return $null
}

Add-AiCase 'D-05' 'P2' '限流不误伤：连续 5 次短请求无 429' {
    $sid = Ai-NewSid 'd05'
    for ($i = 1; $i -le 5; $i++) {
        $r = Ai-ChatRaw $sid ("第 $i 条") @{ page = 'tasks' }
        if ($r.status -eq 429) { throw "第 $i 次被限流（E2E 阈值应已抬高，属假红）" }
        if ($r.status -ge 500) { throw "第 $i 次 HTTP $($r.status)" }
        Start-Sleep -Milliseconds 200
    }
    return $null
}

Add-AiCase 'D-06' 'P1' '输入超长 8193：400 MESSAGE_TOO_LONG、不截断、不回显原文' {
    $sid = Ai-NewSid 'd06'
    $long = 'A' * 8193
    $r = Invoke-Api 'POST' '/api/ai/chat' @{ sessionId = $sid; message = $long; context = @{ page = 'tasks' } }
    if ($r.status -ne 400) { throw "8193 码元 HTTP $($r.status)，期望 400" }
    if ($r.json.code -ne 'MESSAGE_TOO_LONG') { throw "code=$($r.json.code)，期望 MESSAGE_TOO_LONG" }
    if ($r.text -like ('*' + ('A' * 200) + '*')) { throw '错误响应回显了超长原文' }
    return $null
}

Add-AiCase 'D-07' 'P2' '归一化后为空：零宽字符被按必填拒，不送上上游' {
    $sid = Ai-NewSid 'd07'
    $r = Invoke-Api 'POST' '/api/ai/chat' @{ sessionId = $sid; message = ([string][char]0x200B + [char]0x200C + [char]0x200D); context = @{ page = 'tasks' } }
    if ($r.status -ne 400) { throw "纯零宽字符 HTTP $($r.status)，期望 400（空消息不该送上游）" }
    return $null
}

Add-AiCase 'D-08' 'P0' '会话忙回执（AR-04 两果都合法）：409 携在途 runId + reattach 可接管；park 释放门的窗口里放行也判据成立' {
    # AR-04：park 后由驱动线程 finally 释放会话门，「收到挂起帧」不再等价于「门被占有」，两者之间
    # 有一段**无读侧同步信号**的窗口 ⇒ 同会话再发 409 / 200 都是现行语义下的合法结果（旧断言"必 409"
    # 就是 Q-08 那条偶发红的同一个前提）。本用例两果都断：
    #   · 409 ⇒ 忙回执可接管（SESSION_BUSY + 占门轮 runId + 重挂端点，且零 SSE 帧）；
    #   · 200 ⇒ 新轮真的开起来了（start/done 齐、不复用挂起轮 runId、无 error 帧）。
    # 409 的**确定性**覆盖在 D-13（可控时序窗口：门确定被持有），不在这里靠时序赌。
    $t = Ai-PublishFixture 'AIE2E-D-08-409'
    $sid = Ai-NewSid 'd08'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起（回执面的现场不成立）' }
    $null = Ai-WaitStatus $cr.runId @('SUSPENDED') 20
    $r = Ai-ChatSse $sid '【AIE2E-D-08】turn-text 在途时再发一条' @{ page = 'tasks' } @('done', 'error') $null
    if ([int]$r.status -eq 409) {
        if (-not $r.json) { throw "409 无 JSON 体（$($r.text)）" }
        if ([string]$r.json.code -ne 'SESSION_BUSY') { throw "code=$($r.json.code)，期望 SESSION_BUSY" }
        if ([string]$r.json.runId -ne [string]$cr.runId) { throw "409 的 runId=$($r.json.runId) ≠ 在途 runId=$($cr.runId)" }
        if ([string]$r.json.reattach -ne "/api/ai/events/$($cr.runId)") { throw "409 的 reattach=$($r.json.reattach)（前端无法接管）" }
        if (@($r.frames).Count -ne 0) { throw "被拒请求却产出了 $(@($r.frames).Count) 个 SSE 帧" }
    }
    elseif ([int]$r.status -eq 200) {
        if ((Ai-F $r.frames @('start')).Count -ne 1) { throw "放行轮 start 帧 $((Ai-F $r.frames @('start')).Count) 个（期望恰 1）" }
        Ai-Done $r.frames | Out-Null
        if ((Ai-F $r.frames @('error')).Count -ne 0) { throw '放行轮出现 error 帧' }
        if ([string](Ai-RunId $r.frames) -eq [string]$cr.runId) { throw '放行轮复用了挂起轮的 runId（两页签共写一条流）' }
    }
    else {
        throw "同会话再发 HTTP $($r.status)（只接受 409 忙回执 / 200 放行两果；$($r.text)）"
    }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'D-09' 'P1' '上游持续失败：重试到 max-attempts 后终态（不无限重试、不假装成功）' {
    $maxAttempts = Get-AiResilienceCount 'max-attempts'
    $sid = Ai-NewSid 'd09'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-D-09】turn-text kill-stream=800 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $runId = Ai-RunId $frames
    $r = Ai-WaitStatus $runId @('FAILED', 'DONE', 'CANCELLED') 90
    if ([int]$r.snapshot.attempts -gt $maxAttempts) { throw "attempts=$($r.snapshot.attempts) > $maxAttempts" }
    if ($r.snapshot.status -eq 'DONE' -and [string]::IsNullOrWhiteSpace([string]$r.snapshot.finalText)) {
        throw '持续断流的轮次却以空正文 DONE 收尾（失败被当成成功）'
    }
    return $null
}

Add-AiCase 'D-10' 'P2' '慢生产者轮仍完整落档（慢订阅者不阻塞整轮）' {
    $sid = Ai-NewSid 'd10'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E-D-10】turn-text slow-reader 请回复一句。' ; context = @{ page = 'tasks' } } `
        @('done', 'error') $frames $null
    $runId = Ai-RunId $frames
    $r = Ai-WaitStatus $runId @('DONE', 'FAILED') 90
    if ((Ai-MaxSeq $frames) -lt 1) { throw '慢生产者轮一帧都没落档' }
    $replay = Ai-Events $runId 0 @('done')
    if ($replay.Count -lt 1) { throw '慢读轮的归档回放为空（整轮没跑完）' }
    return $null
}

Add-AiCase 'D-11' 'P1' '前端工具超时预算下发且条目可判（终态收敛由 S-09 实证）' {
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $h.ok) { throw "GET /api/ai/health => HTTP $($h.status)" }
    $budget = [int]$h.json.frontendToolTimeoutSeconds
    if ($budget -lt 1) { throw "frontendToolTimeoutSeconds=$budget（预算未下发，前端预算无同源）" }
    $t = Ai-ExportFixture 'AIE2E-D-11-前端预算'
    $sid = Ai-NewSid 'd11'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请打开任务 {0} 的导出向导" -f $t); context = @{ page = 'tasks'; taskId = $t; taskType = 'EXPORT'; step = 'SELECT_DEFS' } } `
        @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $entry = Gf-PendingEntry $req.runId $req.toolCallId
    if ($entry.status -ne 'PENDING') { throw "条目状态 $($entry.status)" }
    if ($null -eq $entry.PSObject.Properties['expiresAt']) { throw '条目缺 expiresAt（超时判据不可读）' }
    Ai-Cancel $req.runId | Out-Null
    return $null
}

Add-AiCase 'D-13' 'P0' '会话门持有期 409（AR-04 可控时序）：门确定被持有时发轮被拒、回执指向持有轮（不靠 sleep/重试）' {
    # ===== AR-04 Task12 Step2：409 面另立**可控时序**用例 =====
    # 旧口径（"收到挂起帧就断言同会话必 409"）在现行语义下不可确定：park 后驱动线程 finally 才释放
    # 会话门，挂起帧与释放之间有一段窗口（Q-08 的两次结果就是这条窗口的两侧）。
    # 本用例把窗口改成**由产品事实确定**：
    #   · 会话门在 HTTP 线程 acquire、在驱动线程 finally 释放（AiController#chat）；
    #   · 首个 POST /api/ai/chat 的**响应头**只在该轮已被受理后才到（409 走 JSON、不进 SSE）；
    #   · 首帧 start 在调上游之前就发出（AR-06），而本轮的 stall-frames 把上游后续分片停住 ⇒
    #     上游流在窗口内不会结束 ⇒ service.chat 不可能返回 ⇒ 门**确定**被这一轮持有
    #     （stall-frames 是本套件既有的段级延迟开关，W-23/W-35/W-38 同族用法）。
    # 第二个请求就在这个时点发（Read-Sse 的 onOpen：响应头已到、尚未读流），只发一次；
    # 不 sleep、不重试 —— 断言不成立就如实红。
    # 分工（同批修正后）：Q-08 / D-08 / M-07 的"同会话再发"一律**两果都合法**（park 释放窗无读侧
    # 同步信号，200 与 409 都是现行语义下的合法结果）；**"必 409"只在本用例断言**，因为这里的门
    # 由可控时序确定被持有 —— 不再有"挂起即 409"的过时前提。
    $sid = Ai-NewSid 'd13'
    # 窗口长度（只用于保证"门确定被持有"；用例在窗口内就取消，不等满它 —— 所以这个数字不是
    # "等多久"，而是"上游流至少还要这么久才结束"）。8000ms 与 W-23/W-35 同族取值的量级一致。
    $windowMs = 8000
    # 被拒请求的文本带一个 ASCII 标记：① 归一化（NFKC）不会把它改形，窗口面按标记判"有没有落下来"
    # 才不会被全角括号折叠骗成假绿；② 它与本用例其它标记一起保证唯一。
    $rejectToken = 'd13-rejected-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $secondText = $rejectToken + ' 门持有时再发一条'
    # 首轮正文也带唯一 token：零副作用面需要**正控**（首轮原文必须出现在收敛后的窗口里），
    # 否则"被拒 token 零条"会退化成对空窗口的空断言。
    $firstToken = 'd13-first-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $script:d13Second = $null
    $onOpen = {
        $script:d13Second = Ai-ChatRaw $sid $secondText @{ page = 'tasks' }
        # 被占的那一轮的 runId 就在 409 回执里（ADR-5）：直接用它取消轮 1 收尾，
        # 免得这轮在 stall 窗口里干等（也顺带证明回执的 runId 真的可操作）。
        if ($script:d13Second.status -eq 409 -and $script:d13Second.json -and $script:d13Second.json.runId) {
            Ai-Cancel ([string]$script:d13Second.json.runId) | Out-Null
        }
    }
    $first = Ai-ChatSse $sid ("【AIE2E-D-13】turn-text {0} stall-frames=$windowMs 请回复一句。" -f $firstToken) @{ page = 'tasks' } @('done', 'error') $onOpen
    if (-not $first.ok) { throw "首个请求 HTTP $($first.status) $($first.text)（可控窗口前提不成立）" }
    $second = $script:d13Second
    if ($null -eq $second) { throw '第二个请求没有发出（onOpen 时点未生效）' }

    # ③ 零副作用面（**排在状态码断言之前**，与 AR-04 §2.4 同一纪律）：被这一轮拒掉的发送不得在
    # 用户可见回显里留下任何用户消息，也不得开流。数据库完整历史端点已移除 ⇒ 载体是
    # ① 响应形态：被拒请求必须是 **JSON 回执**（不是 SSE 流）—— `Ai-ChatRaw` 走 Invoke-Api，返回
    #    {status, ok, text, json} 没有 frames 属性（复审 N3），所以判"没有 SSE 帧"只能看响应体形态；
    # ② 收敛后的 Redis 记忆窗口（见 ④，首轮正控 + 被拒 token 零条，非空断言）。
    $secondBody = [string]$second.text
    if ($secondBody.TrimStart().StartsWith('event:') -or $secondBody.TrimStart().StartsWith('data:')) {
        throw "第二次发送（HTTP $($second.status)）返回了 SSE 流（被拒请求不得开流）：$($secondBody.Substring(0, [Math]::Min(120, $secondBody.Length)))"
    }
    if (-not $second.json) {
        throw "第二次发送（HTTP $($second.status)）不是 JSON 回执（零副作用判据不可判）：$($secondBody)"
    }
    # ① 409 + 码
    if ($second.status -ne 409) { throw "门持有期发轮 HTTP $($second.status)，期望 409" }
    if (-not $second.json -or [string]$second.json.code -ne 'SESSION_BUSY') { throw "409 码 $($second.json.code)，期望 SESSION_BUSY" }
    # ② 回执指向"占着会话的那一轮"：runId = 首个请求那一轮，reattach = 它的重挂端点（前端据此接管）
    $firstRun = Ai-RunId $first.frames
    if ([string]$second.json.runId -ne [string]$firstRun) {
        throw "409 的 runId=$($second.json.runId) ≠ 门持有者 runId=$firstRun（回执指错轮，前端会接管错流）"
    }
    if ([string]$second.json.reattach -ne "/api/ai/events/$firstRun") {
        throw "409 的 reattach=$($second.json.reattach)，期望 /api/ai/events/$firstRun（前端无法接管）"
    }
    # ④ 收尾：轮 1 已按取消收敛（门随驱动线程 finally 释放，不留僵尸门）
    $null = Ai-WaitStatus $firstRun @('CANCELLED') 20
    # 收敛后的窗口对账（数据库完整历史端点已移除）：首轮原文恰一条是**正控**（证明判据有对象），
    # 被拒的发送零条；窗口里不得出现重复用户原话（原"完整历史无重复 messageId"的等价形式）。
    $null = Ai-WaitWindowToken $sid $firstToken 20
    $win13 = Ai-Window $sid
    $firstHits13 = @($win13 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$firstToken*" })
    if ($firstHits13.Count -ne 1) {
        throw "首轮发送在窗口里有 $($firstHits13.Count) 条用户消息（期望 1：零副作用判据的正控）"
    }
    $rejectHits13 = @($win13 | Where-Object { $_.type -eq 'USER' -and ([string]$_.text) -like "*$rejectToken*" })
    if ($rejectHits13.Count -ne 0) {
        throw "第二次发送（HTTP $($second.status)）在窗口里落了 $($rejectHits13.Count) 条用户消息（零副作用被破坏 / 幻影气泡）"
    }
    $dup13 = Ai-WindowDuplicateUserTexts $sid
    if ($dup13.Count -ne 0) { throw "窗口里出现重复用户原话（幻影气泡）：$($dup13 -join ' | ')" }
    # messageId 幂等（帧侧）：被取消的首轮若已吐过正文，其 assistant 身份必须唯一（不得被拆成多条）。
    # 0 个身份 = 取消太早、本轮没有可判定的正文（不作为失败；窗口面的正控已保证判据非空）。
    $ids13 = Ai-AssistantIds $first.frames
    if ($ids13.Count -gt 1) {
        throw "首轮 assistant messageId 有 $($ids13.Count) 个（同一条消息被拆成多身份）：$($ids13 -join ',')"
    }
    return $null
}

Say '--- 批① · SAF 合规与脱敏（12 条） ---'

function Ai-Headers($path) {
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, ($Base + $path))
    $resp = $http.SendAsync($req).Result
    $out = @{ status = [int]$resp.StatusCode; headers = @{} }
    foreach ($h in $resp.Headers) { $out.headers[$h.Key.ToLowerInvariant()] = ($h.Value -join ',') }
    foreach ($h in $resp.Content.Headers) { $out.headers[$h.Key.ToLowerInvariant()] = ($h.Value -join ',') }
    $resp.Dispose()
    return $out
}
function Ai-SecurityCounters {
    $h = Invoke-Api 'GET' '/api/ai/health' $null
    if (-not $h.ok -or $null -eq $h.json.security) { return $null }
    return $h.json.security
}
# DLP 计数零增长断言（X-01 / X-02 / X-03 同一口径；评审 I-2 / I-3）：
#   ① 起始基线为 null 一律失败 —— 「跳过比较」会把"计数面不可观测"伪装成零增长（I-3）；
#   ② 逐键先断言 before / after **两处都存在** —— 键被删/改名时 0==0 同样是假绿（I-2）；
#   ③ 再比较值。三键键位是 /health 的兼容面（L-99 后无写入点，值应零增长）。
function Ai-AssertDlpFlat($before, $after, [string[]]$keys) {
    if ($null -eq $before) { throw 'health 起始基线为 null（security 计数不可观测）——不得跳过零增长断言' }
    if ($null -eq $after) { throw 'health 未下发 security 计数（DLP 面不可观测）' }
    $beforeNames = @($before.PSObject.Properties.Name)
    $afterNames = @($after.PSObject.Properties.Name)
    foreach ($k in $keys) {
        if ($beforeNames -notcontains $k) { throw "health.security 基线缺键 $k（键位被删/改名 ⇒ 零增长不可判，按失败处理）" }
        if ($afterNames -notcontains $k) { throw "health.security 缺键 $k（键位被删/改名 ⇒ 零增长不可判，按失败处理）" }
        if ([long]$after.$k -ne [long]$before.$k) { throw "$k 仍在增长（$($before.$k) → $($after.$k)）——DLP 未删净" }
    }
}

Add-AiCase 'X-01' 'P0' '输入含 sk- 形态：原文透传（不 block、不脱敏），DLP 计数不增长' {
    $before = Ai-SecurityCounters
    $sid = Ai-NewSid 'x01'
    $probe = 'sk-' + ('A' * 40)
    $turn = Invoke-AiTurn $sid ("【AIE2E-X-01】turn-text 我的密钥是 $probe 请确认收到。") @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $turn.frames | Out-Null
    if ((Ai-F $turn.frames @('error')).Count -gt 0) { throw '含密钥形态的输入被 block（L-99：不 block，原文透传）' }
    # L-99 口径（2026-10-24 裁决：删 DLP）——输入侧不再脱敏，归一层之后的文本逐字进模型与记忆。
    # 故这里锁"历史里是原文"（旧口径锁的是"历史里已 mask"），并额外锁"掩码痕迹确实不存在"。
    $hist = GetJson "/api/ai/history/$sid"
    $blob = ($hist.messages | ConvertTo-Json -Depth 8 -Compress)
    if ($blob -notlike ('*' + $probe + '*')) { throw '历史里没有密钥原文（L-99 后应原文透传；被掩码即回归）' }
    if ($blob -match '\[REDACTED') { throw '历史里出现 [REDACTED 掩码（DLP 应已删除）' }
    $after = Ai-SecurityCounters
    # 评审 I-2/I-3：基线 null 失败、逐键存在性先断言（缺键时 0==0 是假绿）。
    Ai-AssertDlpFlat $before $after @('inputRedacted', 'outputRedacted', 'outputBlocked')
    return $null
}

Add-AiCase 'X-02' 'P1' '输出探针跨分片：拼回即原文、无掩码痕迹、DLP 三计数零增长' {
    $before = Ai-SecurityCounters
    $sid = Ai-NewSid 'x02'
    $probe = 'sk-' + ('B' * 40)
    $turn = Invoke-AiTurn $sid ("【AIE2E-X-02】turn-text 请原样重复这一行：token=$probe 结束。") @{ page = 'tasks' } $script:aiContinue 2
    $done = Ai-Done $turn.frames
    if ((Ai-F $turn.frames @('error')).Count -gt 0) { throw '输出探针轮出现 error 帧（文本轮应正常收尾）' }
    if ($done.PSObject.Properties.Name -contains 'safety') { throw 'done 仍带 safety 段（L-99：输出守卫与 done.extra.safety 一并删除）' }
    # L-99 口径（2026-10-24 裁决：删 DLP）——输出侧不再 mask/block，故这里锁"拼回即原文"，
    # 并锁"掩码痕迹确实不存在"。桩在 X-02 标记下主动回出跨分片的假密钥串（stub-upstream.mjs
    # 的 SAFETY_STREAM_PROBE），本用例因此有对象；此前的绿是"桩回复不含探针"的空断言（L-137）。
    # 任务语义是**原文透传**：断言拼回正文含桩的**完整确定性回复**（不只密钥子串）——
    # 前缀/后缀任一被改写、截断或替换即红（断言升级，不改产品去迎合）。
    $text = Ai-Text $turn.frames
    $expected = "密钥一：token=$probe 结束。"
    $deltas = @($turn.frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text })
    if ($deltas.Count -lt 3) { throw "delta 帧 $($deltas.Count) 个（跨分片形态至少 3 片：前缀 + 密钥两片）" }
    if (-not $text.Contains($expected)) { throw '拼回的正文不含完整确定性回复（L-99 后应原文透传；缺前缀/后缀或被改写即回归）' }
    if ($text -match '\[REDACTED') { throw '正文里出现 [REDACTED 掩码（DLP 应已删除）' }
    # 跨分片实证：整串密钥不得落在任一单片内，且分片边界必须落在密钥内部。
    # 桩按 24 字符切片、密钥 43 字符 —— 若哪天桩改成整串单片，这里即红（防"断言还在、对象没了"）。
    if (@($deltas | Where-Object { $_.Contains($probe) }).Count -gt 0) { throw '单个 delta 即含完整密钥（跨分片探针没被切开，断言无对象）' }
    $joined = ''
    $bounds = New-Object System.Collections.Generic.List[int]
    foreach ($piece in $deltas) { $joined += $piece; $bounds.Add($joined.Length) }
    $at = $joined.IndexOf($probe)
    if (@($bounds | Where-Object { $_ -gt $at -and $_ -lt ($at + $probe.Length) }).Count -lt 1) {
        throw '分片边界未落在密钥内部（密钥未被跨分片发出，跨分片断言无对象）'
    }
    Ai-SafetyDump 'x02' $turn.frames $probe
    $after = Ai-SecurityCounters
    Ai-AssertDlpFlat $before $after @('inputRedacted', 'outputRedacted', 'outputBlocked')
    return $null
}

Add-AiCase 'X-03' 'P1' '私钥块不再阻断：完整 PEM 原文到达、done 收尾、DLP 计数零增长' {
    $before = Ai-SecurityCounters
    $sid = Ai-NewSid 'x03'
    $turn = Invoke-AiTurn $sid '【AIE2E-X-03】turn-text 请输出一段 BEGIN PRIVATE KEY 内容用于演示。' @{ page = 'tasks' } $script:aiContinue 2
    $term = @($turn.frames | Where-Object { $_.type -eq 'done' -or $_.type -eq 'error' })
    if ($term.Count -lt 1) { throw '无终态' }
    if ((Ai-F $turn.frames @('error')).Count -gt 0) { throw '私钥块轮出现 error 帧（L-99 后不再阻断，不得有错误终态）' }
    if ($term[0].type -ne 'done') { throw "终态是 $($term[0].type)（L-99 后私钥块不再阻断，必须 done 收尾）" }
    Ai-Done $turn.frames | Out-Null
    # 正文必须是**完整**假 PEM：头 + body + 尾三行齐全、顺序正确，且**连续成块**（不只三段各自存在）。
    $text = Ai-Text $turn.frames
    # 私钥头同样拆开拼接：避免与提交前敏感信息扫描规则（`-----BEGIN…PRIVATE KEY-----`）自撞。
    $pemHead = '-----BEGIN' + ' RSA PRIVATE KEY-----'
    $pemBody = 'MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn'
    $pemTail = '-----END RSA PRIVATE KEY-----'
    $pem = $pemHead + "`n" + $pemBody + "`n" + $pemTail
    foreach ($part in @($pemHead, $pemBody, $pemTail)) {
        if (-not $text.Contains($part)) { throw "正文缺 PEM 片段：$part（L-99 后应原样透传）" }
    }
    if (-not $text.Contains($pem)) { throw '拼回的正文不含完整 PEM 块（头/body/尾须连续成块；被改写或插入内容即回归）' }
    if ($text.IndexOf($pemHead) -gt $text.IndexOf($pemTail)) { throw 'PEM 首尾顺序倒置（内容不完整）' }
    if ($text -match '\[REDACTED') { throw '正文里出现 [REDACTED 掩码（DLP 应已删除）' }
    # 跨分片实证（同 X-02）：完整 PEM 块不得落在单片内，否则"流式到达"断言无对象。
    $deltas = @($turn.frames | Where-Object { $_.type -eq 'delta' } | ForEach-Object { [string]$_.text })
    if ($deltas.Count -lt 2) { throw "delta 帧 $($deltas.Count) 个（PEM 块必须先跨分片发出）" }
    if (@($deltas | Where-Object { $_.Contains($pem) }).Count -gt 0) { throw '单个 delta 即含完整 PEM 块（跨分片探针没被切开，断言无对象）' }
    Ai-SafetyDump 'x03' $turn.frames $pemBody
    $after = Ai-SecurityCounters
    Ai-AssertDlpFlat $before $after @('outputBlocked', 'outputRedacted')
    return $null
}

Add-AiCase 'X-04' 'P1' 'Markdown 外链外图：服务端不改写正文、安全头在位' {
    $sid = Ai-NewSid 'x04'
    $turn = Invoke-AiTurn $sid '【AIE2E-X-04】turn-text 请给一个外链与一张站外图。' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $turn.frames | Out-Null
    $h = Ai-Headers '/api/ai/health'
    if (-not $h.headers['x-content-type-options']) { throw '缺 X-Content-Type-Options（nosniff）' }
    return $null
}

Add-AiCase 'X-05' 'P2' 'CSP / nosniff 头在位（报告仅上报不阻断）' {
    $h = Ai-Headers '/api/ai/health'
    $csp = $h.headers['content-security-policy-report-only']
    if (-not $csp) { throw '缺 Content-Security-Policy-Report-Only' }
    # 判据按产品已定稿的形态（SecurityHeadersFilter 类注）：本仓不提供 CSP 收集端点，
    # 故策略里**不会**有 report-uri/report-to —— 旧断言要求它等于要一个从未设计的上报面。
    # 真正要锁的是"只报告不阻断"本身：
    #   ① 策略是 Report-Only（带 default-src 'self' 的实质内容，空头不算）；
    #   ② 强制头 Content-Security-Policy 不得同时下发（否则就是"会阻断"，与裁决相反）。
    if ($csp -notlike "*default-src 'self'*") { throw "CSP-Report-Only 策略内容异常：$csp" }
    if ($h.headers['content-security-policy']) { throw "同时下发了强制 CSP（会阻断页面，与'只报告'裁决相反）：$($h.headers['content-security-policy'])" }
    if ($h.headers['x-content-type-options'] -ne 'nosniff') { throw "X-Content-Type-Options=$($h.headers['x-content-type-options'])，期望 nosniff" }
    return $null
}

Add-AiCase 'X-06' 'P2' 'context 形状闸：未知 page 被剔、不回显、不 500' {
    $sid = Ai-NewSid 'x06'
    $ctx = @{ page = 'no-such-page-aie2e'; evilKey = 'X'; taskId = 1 }
    $r = Invoke-Api 'POST' '/api/ai/chat' @{ sessionId = $sid; message = '你好'; context = $ctx }
    if ($r.status -ge 500) { throw "未知 page 的 context => HTTP $($r.status)（形状闸未兜住）" }
    if ($r.text -like '*no-such-page-aie2e*') { throw '响应回显了未知 page' }
    if ($r.text -like '*evilKey*') { throw '白名单外键被回显' }
    return $null
}

Add-AiCase 'X-08' 'P2' '安全计数面可读、done 既有字段不被 additive 破坏' {
    $s = Ai-SecurityCounters
    if ($null -eq $s) { throw 'health 缺 security 段（计数不可观测）' }
    $keys = @($s.PSObject.Properties | ForEach-Object { $_.Name })
    if ($keys.Count -lt 5) { throw "security 计数只有 $($keys.Count) 项（实际：$($keys -join ',')）" }
    $sid = Ai-NewSid 'x08'
    $turn = Invoke-AiTurn $sid ("【AIE2E-X-08】turn-text 密钥 sk-$('C' * 40) 请确认。") @{ page = 'tasks' } $script:aiContinue 2
    $done = Ai-Done $turn.frames
    if ($null -eq $done.usage) { throw 'done 缺 usage（additive 键破坏了既有字段）' }
    return $null
}

Add-AiCase 'X-09' 'P1' 'HTML 注入按文本呈现：正文原样保留、服务端不展开标记' {
    $sid = Ai-NewSid 'x09'
    $turn = Invoke-AiTurn $sid '【AIE2E-X-09】turn-text 请原样重复：<img src=x onerror=alert(1)>' @{ page = 'tasks' } $script:aiContinue 2
    Ai-Done $turn.frames | Out-Null
    $text = Ai-Text $turn.frames
    if ($text.Length -lt 1) { throw '正文为空（HTML 片段被整段吞掉）' }
    if ($text -match '<script') { throw '服务端把 <script 当标记展开' }
    return $null
}

Add-AiCase 'X-10' 'P1' '联动条件写成表达式：判非法、不 eval、不下发' {
    $sid = Ai-NewSid 'x10'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】gf-expr-linkage 请按标记出表单。' ; context = @{ page = 'tasks' } } `
        @('frontend_tool_request', 'done', 'error') $frames $null
    $req = Ai-One $frames 'frontend_tool_request'
    if ($req) { throw '表达式形态的联动被下发到前端（fail-closed 失效）' }
    $rej = @($frames | Where-Object { $_.type -eq 'frontend_tool_result' -and $_.ok -eq $false })
    if ($rej.Count -lt 1) { throw '表达式联动既没下发也没被拒（入参闸门无结论）' }
    if ([string]::IsNullOrWhiteSpace([string]$rej[$rej.Count - 1].status)) { throw '拒绝帧缺 status/code' }
    return $null
}

Add-AiCase 'X-11' 'P1' '整表不出域：采样上界与列投影上界可读且受限' {
    $h = Ai-SheetHealth
    if ([int]$h.sampleMaxRows -gt 20) { throw "sampleMaxRows=$($h.sampleMaxRows) > 20（整表出域风险）" }
    if ([int]$h.projectionMaxColumns -gt 5) { throw "projectionMaxColumns=$($h.projectionMaxColumns) > 5" }
    if ((Ai-ToolsOf $script:aiSheetCtx) -notcontains 'read_sheet_summary') { throw 'read_sheet_summary 未披露（读路径不可用反而无法验证上界）' }
    return $null
}

Add-AiCase 'X-12' 'P1' '附件内容不进模型：回灌结果只带元数据' {
    $sid = Ai-NewSid 'x12'
    $s = Ai-FormSuspend $sid 'gf-attachment（X-12 元数据）'
    $secret = 'AIE2E-SECRET-PAYLOAD-' + ('Z' * 24)
    $file = Join-Path $tmp 'aie2e-secret.txt'
    [IO.File]::WriteAllText($file, $secret)
    $up = Ai-FormUpload $s.runId $s.toolCallId $file 'aie2e-secret.txt'
    if (-not $up.ok) { throw "附件上传 => HTTP $($up.status) $($up.text)" }
    $fileId = [string]$up.json.data.fileId
    $r = Ai-FormPost $s.runId $s.toolCallId ('{"sheet":"' + $fileId + '"}')
    if ($r.status -ne 200) { throw "回灌 HTTP $($r.status)" }
    $entry = Gf-PendingEntry $s.runId $s.toolCallId
    $txt = [string]$entry.resultText
    if ($txt -like ('*' + $secret + '*')) { throw '文件内容进了回灌结果（会原样进模型上下文）' }
    if ($txt -notlike ('*' + $fileId + '*')) { throw '回灌结果缺 fileId（元数据通道断了）' }
    return $null
}

Add-AiCase 'X-13' 'P2' '台账不落敏感值：runs/history 不含草稿哨兵与密钥形态串' {
    $sid = Ai-NewSid 'x13'
    $t = Invoke-AiTurn $sid '【AIE2E-X-13】turn-text 台账面检查。' @{ page = 'tasks' } $script:aiContinue 2
    $runId = Ai-RunId $t.frames
    Ai-Done $t.frames | Out-Null
    $blob = (Ai-Run $runId) | ConvertTo-Json -Depth 12 -Compress
    if ($blob -match 'E2E_DRAFT_SENTINEL') { throw '台账里出现草稿哨兵' }
    if ($blob -match 'sk-[A-Za-z0-9]{20,}') { throw '台账里出现密钥形态串' }
    $hist = (GetJson "/api/ai/history/$sid") | ConvertTo-Json -Depth 12 -Compress
    if ($hist -match 'sk-[A-Za-z0-9]{20,}') { throw '历史里出现密钥形态串' }
    return $null
}

# ============================================================
# V1 · 工作区原语全链（桩层）34 条（W-01 … W-34）
#
# 两件事一起锁：
#   ① 缺口 0（动态披露 `ui:on` 在生产链路不生效）：修复前 ContextFieldGate 把 `extra.uiRegionCount`
#      当非白名单键丢弃 ⇒ ToolRegistry#buildTags 恒见"缺失"⇒ 恒 fail-open，显式 0 收不窄披露面。
#      修法 = 闸门的 tag 专用旁路（ContextBuilder.TAG_ONLY_EXTRA_KEYS）：披露判定看得见、
#      模型载荷看不见（A-13 锁不变）。W-10/W-11 是它的 HTTP 全链回归位（红→绿即此两条）。
#   ② 7 个 `ui_*` 原语的协议半段（披露 → 挂起 → 回灌 → 结局帧 → done 收敛）+ 拒执码落档、
#      交接卡、停止面（A9/A10/A11/A12/A13）。卡面 DOM 与浏览器内执行属前端 vitest 面，
#      桩里没有 DOM，故不以接口级断言冒充（同 T-13/T-14 的登记口径）。
#
# 断言面（全部用户可观察，不落实现细节）：
#   - 披露面：后端 DEBUG 行 `上下文=… 披露工具=[…]`（按 runId 取那一轮冻结的清单，与 /api/ai/tools 同源）；
#   - 行为面：桩按"披露清单驱动"（requiresTools 硬闸）决定是否发起工具调用 ⇒ 缺席/在席两侧都可判；
#   - 台账面：/api/ai/runs/{runId}（pending / historyRoles）、/runs/current、/api/ai/history、events 归档。
# ============================================================
Say '--- V1 · 工作区原语全链（34 条：ui_* 七工具 + 拒执码 + 动态披露 + HITL + 停止面） ---'

# 7 个原语（与 backend @Tool(name) / frontend UI_TOOL_NAMES 严格同名）
$script:aiUiTools = @('ui_snapshot', 'ui_click', 'ui_fill', 'ui_select', 'ui_scroll', 'ui_highlight',
    'ui_request_upload')

# 工作区上下文：`ui:on` 的唯一判据 = extra.uiRegionCount（缺省 = 缺失 = fail-open）。
# -Omit 走"旧客户端 / curl"形态（不带该键）。
function Ai-UiCtx([object]$regionCount, [switch]$Omit) {
    $extra = @{ pageId = 'definitions' }
    if (-not $Omit) { $extra['uiRegionCount'] = $regionCount }
    return @{ page = 'definitions'; extra = $extra }
}

# 该 runId 冻结在日志里的披露清单（"AI 轮次开始 … 上下文=… 披露工具=[…]" 行）。
# 为什么按 runId 取而不是按上下文取：同一上下文会出现多轮，只有 runId 能把"这一轮看到了什么"钉死。
function Ai-DisclosureOf($runId) {
    if (-not $runId) { throw '披露取证缺 runId' }
    $text = Get-LogText
    $pattern = 'runId=' + [regex]::Escape($runId) + '\s+sessionId=\S+\s+会话已存在=\S+\s+上下文=(\S+)\s+披露工具=\[([^\]]*)\]'
    $m = [regex]::Match($text, $pattern)
    if (-not $m.Success) { throw "后端日志里没有 runId=$runId 的披露行（披露取证不可用）" }
    return @{
        context = $m.Groups[1].Value
        tools = @($m.Groups[2].Value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    }
}

# 前端工具结果回灌（$extra 合并 outcome / code 等 additive 字段）
function Ai-FePost($runId, $toolCallId, $result, $extra) {
    $body = @{ runId = $runId; toolCallId = $toolCallId; result = $result; source = 'aie2e' }
    if ($extra) { foreach ($k in $extra.Keys) { $body[$k] = $extra[$k] } }
    return Invoke-Api 'POST' '/api/ai/frontend-tool-result' $body
}

# 发一轮工作区原语对话（桩按 marker 决定发起哪个 ui_* 调用；会话 id 由调用方给，保证各用例互不串轮）
function Ai-UiChat($sid, $marker, $context) {
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = "【AIE2E】scenario=$marker 按原语执行一次。"
        context = $context
    } @('frontend_tool_request', 'done', 'error') $frames $null
    return $frames
}

# 回灌 + 收尾断言（结局帧恰 1 条、done 恰 1 条、done 是最后帧、seq 严格递增）
function Ai-UiConverge($req, $resultText, $bodyExtra) {
    if (-not $req) { throw '未收到 frontend_tool_request（工具未披露或桩未命中路由）' }
    $posted = Ai-FePost $req.runId $req.toolCallId $resultText $bodyExtra
    if (-not $posted.ok) { throw "回灌 HTTP $($posted.status) $($posted.text)" }
    $after = Ai-Events $req.runId 0 @('done', 'error')
    $res = @($after | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req.toolCallId
    })
    if ($res.Count -ne 1) { throw "结局帧 $($res.Count) 个（期望恰 1）" }
    Ai-Done $after | Out-Null
    Ai-DoneIsLast $after
    Ai-SeqStrict $after
    return @{ posted = $posted; after = $after; frame = $res[0] }
}

# 单轮"中途停止"：只读 start 帧即断连（轮次在服务端继续跑），随后 cancel。
# 为什么这样建模：停止是用户动作，发生在流的中段；读完整轮再 cancel 只能测到"事后取消"。
function Ai-StopMidTurn($sid, $message, $context) {
    $head = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = $message; context = $context } @('start') $head
    $runId = Ai-RunId $head
    # 停止前先等快照建立（原因见 Ai-WaitSnapshotReady 的注）：本 helper 的调用方断言的都是
    # "轮次在飞时被停止"的持久面（快照 CANCELLED / 归档 done{cancelled:true} / 条目终态）。
    $null = Ai-WaitSnapshotReady $runId (Get-AiResilienceSeconds 'first-byte-timeout')
    $cancel = Ai-Cancel $runId
    if (-not $cancel.cancelled) { throw 'POST /api/ai/cancel 未返回 cancelled=true' }
    $final = Ai-WaitStatus $runId @('CANCELLED') 20
    if ($final.snapshot.status -ne 'CANCELLED') { throw "停止后轮次状态 $($final.snapshot.status)，期望 CANCELLED" }
    return $runId
}

# 停止的落档是异步的：快照状态先变、归档帧与条目终态后到（收尾线程在事件边界收口）。
# 因此"停止之后"的读侧断言一律有界轮询，不用一次读到的瞬时值 —— 否则时序抖动会变成假红。
function Ai-WaitArchivedDone($runId, [int]$sec = 15) {
    $deadline = (Get-Date).AddSeconds($sec)
    $frames = @()
    while ((Get-Date) -lt $deadline) {
        $frames = Ai-Events $runId 0 @('done', 'error')
        if (@($frames | Where-Object { $_.type -eq 'done' }).Count -eq 1) { return $frames }
        Start-Sleep -Milliseconds 300
    }
    return $frames
}

# 条目终态（停止把挂起项收敛为终态也是异步的：等它离开 PENDING）
function Ai-WaitPendingSettled($runId, $toolCallId, [int]$sec = 15) {
    $deadline = (Get-Date).AddSeconds($sec)
    $entry = $null
    while ((Get-Date) -lt $deadline) {
        $entry = @(GetJson "/api/ai/pending/$runId" | Where-Object { $_.toolCallId -eq $toolCallId })[0]
        if ($entry -and $entry.status -ne 'PENDING') { return $entry }
        Start-Sleep -Milliseconds 300
    }
    return $entry
}

# 该轮不再有 PENDING 条目（含"本来就没有"）
function Ai-WaitNoPending($runId, [int]$sec = 10) {
    $deadline = (Get-Date).AddSeconds($sec)
    $open = @()
    while ((Get-Date) -lt $deadline) {
        $open = @(GetJson "/api/ai/pending/$runId" | Where-Object { $_.status -eq 'PENDING' })
        if ($open.Count -eq 0) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "停止后仍有 $($open.Count) 个条目停在 PENDING（${sec}s 内未收敛）"
}

Add-AiCase 'W-01' 'P1' 'ui_snapshot 全链：披露 → 挂起 → 回灌 → 结局帧 ok → done 收敛' {
    $sid = Ai-NewSid 'w01'
    $frames = Ai-UiChat $sid 'UI-SNAPSHOT' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_snapshot 未命中披露清单）' }
    if ($req.name -ne 'ui_snapshot') { throw "挂起工具 $($req.name)，期望 ui_snapshot" }
    $d = Ai-DisclosureOf $req.runId
    if ($d.tools -notcontains 'ui_snapshot') { throw '披露清单里没有 ui_snapshot（挂起工具与披露面不自洽）' }
    $a = $req.args | ConvertFrom-Json
    if ([int]$a.limit -lt 1 -or [int]$a.limit -gt 40) { throw "args.limit=$($a.limit) 越 1..40（快照行硬顶）" }
    $entry = Gf-PendingEntry $req.runId $req.toolCallId
    if ($entry.kind -ne 'FRONTEND') { throw "pending.kind=$($entry.kind)，期望 FRONTEND" }
    $conv = Ai-UiConverge $req '[import.upload.choose] button "选择文件" state=enabled（共 1 个元素，本次回 1 个）' $null
    if ($conv.frame.outcome -ne 'ok' -or $conv.frame.ok -ne $true) {
        throw "结局帧 outcome=$($conv.frame.outcome)/ok=$($conv.frame.ok)，期望 ok/true"
    }
    return $null
}

Add-AiCase 'W-02' 'P1' 'ui_click 全链：入参只有 ref+epoch（无 selector 形态），回灌后收敛' {
    $sid = Ai-NewSid 'w02'
    $frames = Ai-UiChat $sid 'UI-CLICK' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_click 未命中披露清单）' }
    if ($req.name -ne 'ui_click') { throw "挂起工具 $($req.name)，期望 ui_click" }
    $a = $req.args | ConvertFrom-Json
    if (-not $a.ref) { throw 'args 缺 ref' }
    if (@($a.ref -split '\.').Count -lt 3) { throw "args.ref=$($a.ref) 不足三段（page.region.name）" }
    if (-not $a.epoch) { throw 'args 缺 epoch（快照闸无从判定）' }
    foreach ($k in @('selector', 'xpath', 'html', 'path', 'filePath')) {
        if ([string]$req.args -like "*$k*") { throw "入参出现禁键 $k（选择器/路径形态一律不接受）" }
    }
    $conv = Ai-UiConverge $req '已点击「选择文件」（mutate-local，记撤销点）' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-03' 'P1' 'ui_fill 全链：写值与回读由浏览器负责，协议侧结局帧原样回填' {
    $sid = Ai-NewSid 'w03'
    $frames = Ai-UiChat $sid 'UI-FILL' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_fill 未命中披露清单）' }
    if ($req.name -ne 'ui_fill') { throw "挂起工具 $($req.name)，期望 ui_fill" }
    $a = $req.args | ConvertFrom-Json
    if ([string]::IsNullOrEmpty([string]$a.value)) { throw 'args 缺 value' }
    if ($a.value.Length -gt 200) { throw "args.value 长度 $($a.value.Length) 超过 200" }
    $conv = Ai-UiConverge $req '已写入「E2E」，回读一致' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-04' 'P1' 'ui_select 全链：选项是语义值不是索引' {
    $sid = Ai-NewSid 'w04'
    $frames = Ai-UiChat $sid 'UI-SELECT' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_select 未命中披露清单）' }
    if ($req.name -ne 'ui_select') { throw "挂起工具 $($req.name)，期望 ui_select" }
    $a = $req.args | ConvertFrom-Json
    if ([string]::IsNullOrEmpty([string]$a.option)) { throw 'args 缺 option（语义值）' }
    if ($a.option -match '^\d+$') { throw "args.option=$($a.option) 是索引形态（语义值不是索引）" }
    $conv = Ai-UiConverge $req '已选择 XLSX，回读一致' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-05' 'P2' 'ui_scroll 全链：只改视口（入参 delta 在 ±2000 内）' {
    $sid = Ai-NewSid 'w05'
    $frames = Ai-UiChat $sid 'UI-SCROLL' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_scroll 未命中披露清单）' }
    if ($req.name -ne 'ui_scroll') { throw "挂起工具 $($req.name)，期望 ui_scroll" }
    $a = $req.args | ConvertFrom-Json
    if ($null -ne $a.delta -and [Math]::Abs([int]$a.delta) -gt 2000) { throw "args.delta=$($a.delta) 超过 ±2000" }
    $conv = Ai-UiConverge $req '已滚动到 export.panel.list' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-06' 'P2' 'ui_highlight 全链：note ≤60 字符，只读不记撤销点' {
    $sid = Ai-NewSid 'w06'
    $frames = Ai-UiChat $sid 'UI-HIGHLIGHT' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_highlight 未命中披露清单）' }
    if ($req.name -ne 'ui_highlight') { throw "挂起工具 $($req.name)，期望 ui_highlight" }
    $a = $req.args | ConvertFrom-Json
    if ($null -ne $a.note -and $a.note.Length -gt 60) { throw "args.note 长度 $($a.note.Length) 超过 60" }
    $conv = Ai-UiConverge $req '已高亮「开始」并把用户送过去' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-07' 'P1' 'ui_request_upload 全链：交接卡挂起 → 回灌 fileId → 收敛（永不经手路径）' {
    $sid = Ai-NewSid 'w07'
    $frames = Ai-UiChat $sid 'UI-UPLOAD' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request（ui_request_upload 未命中披露清单）' }
    if ($req.name -ne 'ui_request_upload') { throw "挂起工具 $($req.name)，期望 ui_request_upload" }
    $a = $req.args | ConvertFrom-Json
    if (@($a.accept) -notcontains 'xlsx') { throw "args.accept=$($a.accept)，期望收窄到 xlsx" }
    foreach ($k in @('path', 'filePath', 'filename', 'selector')) {
        if ([string]$req.args -like "*$k*") { throw "入参出现禁键 $k（交接永不经手文件路径）" }
    }
    $conv = Ai-UiConverge $req '已选择文件：数据.xlsx（fileId=f-e2e-1）' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-08' 'P1' '拒执机器码落档：UI_REF_NOT_SNAPSHOTTED 经 normalizeCode 进帧与台账（同源）' {
    $sid = Ai-NewSid 'w08'
    $frames = Ai-UiChat $sid 'UI-CLICK' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $conv = Ai-UiConverge $req '未执行：ref 不在本 epoch 快照里' @{ outcome = 'rejected'; code = 'UI_REF_NOT_SNAPSHOTTED' }
    if ($conv.frame.code -ne 'UI_REF_NOT_SNAPSHOTTED') { throw "结局帧 code=$($conv.frame.code)，期望原样落档" }
    if ($conv.frame.outcome -ne 'rejected' -or $conv.frame.ok -ne $false) {
        throw "结局帧 outcome=$($conv.frame.outcome)/ok=$($conv.frame.ok)，期望 rejected/false"
    }
    if ($conv.frame.executed -ne $false) { throw "结局帧 executed=$($conv.frame.executed)，期望 false（拒执零副作用）" }
    $entry = @(GetJson "/api/ai/pending/$($req.runId)" | Where-Object { $_.toolCallId -eq $req.toolCallId })[0]
    if ($entry.code -ne 'UI_REF_NOT_SNAPSHOTTED') { throw "台账 code=$($entry.code)，与帧不同源" }
    return $null
}

Add-AiCase 'W-09' 'P2' '非法机器码容错：不报 400、丢弃后回落状态推导（A-14 口径）' {
    $sid = Ai-NewSid 'w09'
    $frames = Ai-UiChat $sid 'UI-FILL' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $conv = Ai-UiConverge $req '已写入 E2E' @{ code = 'ui_lower' }
    if ([string]::IsNullOrWhiteSpace([string]$conv.frame.code)) {
        # 期望分支：非法码被入口丢弃
    } else {
        throw "非法码被落库：code=$($conv.frame.code)"
    }
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望回落为 ok（成功判定不被诊断字段拖垮）" }
    return $null
}

Add-AiCase 'W-10' 'P0' '动态披露 · 显式 0：uiRegionCount=0 ⇒ 7 个 ui_* 不进本轮披露清单（缺口 0 回归位）' {
    $sid = Ai-NewSid 'w10'
    $frames = Ai-UiChat $sid 'UI-SNAPSHOT' (Ai-UiCtx 0)
    $start = Ai-One $frames 'start'
    if (-not $start) { throw '未收到 start 帧（runId 无法定位）' }
    $d = Ai-DisclosureOf $start.runId
    $ui = @($d.tools | Where-Object { $_ -like 'ui_*' })
    if ($ui.Count -ne 0) { throw "显式 uiRegionCount=0 仍披露了 $($ui.Count) 个 ui_*：$($ui -join ',')（tag 旁路未生效 / 恒 fail-open）" }
    if (@($d.tools | Where-Object { $_ -eq 'list_config_defs' }).Count -ne 1) {
        throw '非 ui 工具被连带裁掉（收窄越界：ui:on 只能收窄 ui_*）'
    }
    if (Ai-One $frames 'frontend_tool_request') { throw '显式 0 仍下发了 frontend_tool_request（披露闸被绕过）' }
    Ai-Done $frames | Out-Null
    Ai-SeqStrict $frames
    return $null
}

Add-AiCase 'W-11' 'P0' '动态披露 · 缺失：不带 uiRegionCount（旧客户端/curl）⇒ 7 个 ui_* 仍在披露清单（fail-open）' {
    $sid = Ai-NewSid 'w11'
    $frames = Ai-UiChat $sid 'UI-SNAPSHOT' (Ai-UiCtx -Omit)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '缺失 uiRegionCount 时 ui_* 未披露（fail-open 被破坏：旧客户端工具会静默消失）' }
    $d = Ai-DisclosureOf $req.runId
    $ui = @($d.tools | Where-Object { $_ -like 'ui_*' })
    if ($ui.Count -ne $script:aiUiTools.Count) { throw "缺失时 ui_* 披露 $($ui.Count) 个，期望 $($script:aiUiTools.Count)" }
    $conv = Ai-UiConverge $req '共 0 个元素（本次回 0 个）' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-12' 'P0' '不可撤销位同源①：confirm_request 帧与 suspended 帧的 irreversible 一致（P 类别）' {
    $t = Ai-PublishFixture 'AIE2E-W-12-不可撤销帧'
    $sid = Ai-NewSid 'w12'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = ("【AIE2E】scenario=IRREV-PUBLISH 任务 {0} 发布。" -f $t)
        context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' }
    } @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（不可撤销位无从核对）' }
    if ($cr.name -ne 'start_publish') { throw "确认工具 $($cr.name)，期望 start_publish" }
    if ($cr.irreversible -ne $true) { throw 'confirm_request 帧 irreversible 不是 true（不可撤销位丢失 ⇒ 卡上没有复选框）' }
    $sus = Ai-One $frames 'suspended'
    if (-not $sus) { throw '未收到 suspended 帧（挂起公告缺失）' }
    $tc = @($sus.toolCalls | Where-Object { $_.toolCallId -eq $cr.toolCallId })
    if ($tc.Count -ne 1) { throw "suspended 帧里没有该条目（$(@($sus.toolCalls).Count) 条）" }
    if ($tc[0].irreversible -ne $true) { throw 'suspended 帧的 irreversible 与 confirm_request 不一致（两个通道不同源）' }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'W-13' 'P1' '不可撤销位同源②：runs/current 与 runs/{runId} 的条目 irreversible 一致' {
    $t = Ai-PublishFixture 'AIE2E-W-13-不可撤销台账'
    $sid = Ai-NewSid 'w13'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = ("【AIE2E】scenario=IRREV-PUBLISH 任务 {0} 发布。" -f $t)
        context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' }
    } @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门' }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found) { throw 'runs/current 找不到挂起轮（刷新恢复无数据源）' }
    $e = @($cur.pendingEntries | Where-Object { $_.toolCallId -eq $cr.toolCallId })
    if ($e.Count -ne 1) { throw "runs/current 里没有该条目（$(@($cur.pendingEntries).Count) 条）" }
    if ($e[0].irreversible -ne $true) { throw 'runs/current 的 irreversible 不是 true（刷新恢复会丢复选框）' }
    if ($e[0].req -ne $true) { throw 'runs/current 的 req 不是 true（条目没有真的发过帧）' }
    $run = Ai-Run $cr.runId
    $pe = @($run.pending | Where-Object { $_.toolCallId -eq $cr.toolCallId })
    if ($pe.Count -ne 1) { throw 'runs/{runId} 台账里没有该条目' }
    if ($pe[0].irreversible -ne $true) { throw 'runs/{runId} 的 irreversible 与 runs/current 不一致' }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'W-14' 'P2' '不可撤销位反向：J 类别（start_export）显式 false，两通道一致' {
    $t = Ai-ExportFixture 'AIE2E-W-14-可逆确认'
    $sid = Ai-NewSid 'w14'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = ("【AIE2E】start_export 任务 {0} 开始导出。" -f $t)
        context = @{ page = 'tasks'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' }
    } @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（可逆确认卡未触发）' }
    if ($cr.name -ne 'start_export') { throw "确认工具 $($cr.name)，期望 start_export" }
    if ($cr.irreversible -ne $false) { throw "可逆动作 irreversible=$($cr.irreversible)，期望显式 false（否则每张卡都会多一个复选框）" }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($cur.found) {
        $e = @($cur.pendingEntries | Where-Object { $_.toolCallId -eq $cr.toolCallId })
        if ($e.Count -eq 1 -and $e[0].irreversible -ne $false) { throw 'runs/current 的 irreversible 与 confirm_request 不一致' }
    }
    Ai-Cancel $cr.runId | Out-Null
    return $null
}

Add-AiCase 'W-15' 'P1' '交接卡语义：ui_request_upload 无超时（等人不武装倒计时）、accept 只能收窄' {
    $sid = Ai-NewSid 'w15'
    $frames = Ai-UiChat $sid 'UI-UPLOAD' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    if ($null -ne $req.timeoutSeconds) { throw "交接型工具下发了 timeoutSeconds=$($req.timeoutSeconds)（等人不该有到期时刻）" }
    if ($null -ne $req.expiresAt) { throw "交接型工具下发了 expiresAt=$($req.expiresAt)" }
    $a = $req.args | ConvertFrom-Json
    $accept = @($a.accept)
    if ($accept.Count -lt 1) { throw 'args.accept 为空（页面允许的扩展名不会被收窄）' }
    foreach ($ext in $accept) {
        if (@('xlsx', 'zip') -notcontains $ext) { throw "args.accept 含 $ext（越出页面白名单 xlsx/zip）" }
    }
    $conv = Ai-UiConverge $req '已选择文件：数据.xlsx（fileId=f-e2e-2）' $null
    if ($conv.frame.outcome -ne 'ok') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 ok" }
    return $null
}

Add-AiCase 'W-16' 'P1' '交接卡放弃：cancelled=true ⇒ FRONTEND_CANCELLED 终态 + 结局帧 ok=false + done 收敛' {
    $sid = Ai-NewSid 'w16'
    $frames = Ai-UiChat $sid 'UI-UPLOAD' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $posted = Invoke-Api 'POST' '/api/ai/frontend-tool-result' @{
        runId = $req.runId; toolCallId = $req.toolCallId; cancelled = $true; source = 'aie2e'
    }
    if (-not $posted.ok) { throw "放弃回灌 HTTP $($posted.status) $($posted.text)" }
    if ($posted.json.status -ne 'FRONTEND_CANCELLED') { throw "回执状态 $($posted.json.status)，期望 FRONTEND_CANCELLED" }
    if ($posted.json.cancelled -ne $true -or $posted.json.executed -ne $false) {
        throw "回执 cancelled=$($posted.json.cancelled)/executed=$($posted.json.executed)"
    }
    $after = Ai-Events $req.runId 0 @('done', 'error')
    $res = @($after | Where-Object { $_.toolCallId -eq $req.toolCallId -and ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') })
    if ($res.Count -ne 1) { throw "结局帧 $($res.Count) 个（期望恰 1）" }
    if ($res[0].status -ne 'FRONTEND_CANCELLED' -or $res[0].ok -ne $false) {
        throw "结局帧 status=$($res[0].status)/ok=$($res[0].ok)，期望 FRONTEND_CANCELLED/false"
    }
    if ([string]$res[0].result -notlike 'FRONTEND_CANCELLED*') { throw '结局帧 result 缺 FRONTEND_CANCELLED：前缀（前端卡片读不到放弃文案）' }
    Ai-Done $after | Out-Null
    Ai-DoneIsLast $after
    Ai-SeqStrict $after
    return $null
}

Add-AiCase 'W-17' 'P1' '写动词被页面拒执：outcome=blocked 如实透出（不谎报成功、零副作用）' {
    $sid = Ai-NewSid 'w17'
    # 只读页面上写动词能不能做，判据在浏览器（区域 data-ai-verbs / data-ai-deny）；桩层断言的是
    # "页面拒执之后协议必须如实落档"这一半段 —— 卡面与元素闸的判定属前端 vitest 面（ai-dom-verbs.spec.ts）。
    $frames = Ai-UiChat $sid 'UI-SELECT' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $conv = Ai-UiConverge $req '该元素不允许「select」动词（data-ai-verbs 收紧了允许集）' @{ outcome = 'blocked' }
    if ($conv.frame.outcome -ne 'blocked') { throw "结局帧 outcome=$($conv.frame.outcome)，期望 blocked" }
    if ($conv.frame.ok -ne $false -or $conv.frame.executed -ne $false) {
        throw "结局帧 ok=$($conv.frame.ok)/executed=$($conv.frame.executed)，期望 false/false（拒执零副作用）"
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$conv.frame.code)) {
        throw "结局帧 code=$($conv.frame.code)（页面没给码时不得臆造）"
    }
    return $null
}

Add-AiCase 'W-18' 'P2' '拒执不产生重试风暴：同一 toolCallId 的请求帧恰 1 条，本轮仍收敛到文本' {
    $sid = Ai-NewSid 'w18'
    $frames = Ai-UiChat $sid 'UI-FILL' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $conv = Ai-UiConverge $req '未执行：写前原值已被用户手改' @{ outcome = 'rejected'; code = 'UI_PRECONDITION_CHANGED' }
    if ($conv.frame.code -ne 'UI_PRECONDITION_CHANGED') { throw "结局帧 code=$($conv.frame.code)" }
    $reAsk = @($conv.after | Where-Object { $_.type -eq 'frontend_tool_request' -and $_.toolCallId -eq $req.toolCallId }).Count
    if ($reAsk -ne 1) { throw "同一 toolCallId 的 frontend_tool_request $reAsk 条（拒执被当成重试信号）" }
    if ((Ai-Text $conv.after).Length -lt 1) { throw '拒执后模型没有收到任何正文（本轮无收尾文本）' }
    return $null
}

Add-AiCase 'W-19' 'P1' '写动词的轮内状态按轮归零：上一轮的计数/停止位不带进下一轮' {
    # 轮 1：写动词挂起 → 回灌 → 收敛
    $sid = Ai-NewSid 'w19'
    $frames = Ai-UiChat $sid 'UI-FILL' (Ai-UiCtx 1)
    $first = Ai-One $frames 'frontend_tool_request'
    if (-not $first) { throw '轮 1 未挂起（写动词未披露）' }
    $conv = Ai-UiConverge $first '已写入 E2E' $null
    if ($conv.frame.outcome -ne 'ok') { throw "轮 1 结局 $($conv.frame.outcome)" }
    # 轮 2（同会话）：必须能发下一条（配额/停止位是轮内的，不是会话级累加）
    $next = Ai-ChatRaw $sid '【AIE2E】继续：写动词之后再说一句。' @{ page = 'definitions'; extra = @{ pageId = 'definitions'; uiRegionCount = 1 } }
    if (-not $next.ok) { throw "同会话下一轮 HTTP $($next.status)（被上一轮的状态挡住）" }
    # 新轮里写动词仍可挂起（配额未被上一轮吃掉）：换会话 = 干净的一轮
    $sid2 = Ai-NewSid 'w19b'
    $frames2 = Ai-UiChat $sid2 'UI-FILL' (Ai-UiCtx 1)
    $second = Ai-One $frames2 'frontend_tool_request'
    if (-not $second) { throw '新轮写动词未挂起（配额/停止位跨轮残留）' }
    if ($second.runId -eq $first.runId) { throw '两轮 runId 相同（轮次台账按 runId 归零的前提不成立）' }
    $conv2 = Ai-UiConverge $second '已写入 E2E' $null
    if ($conv2.frame.outcome -ne 'ok') { throw "新轮结局 $($conv2.frame.outcome)" }
    return $null
}

Add-AiCase 'W-20' 'P1' '前端注册表 18 条与 /api/ai/tools 逐字段对账（名称/风险/通道/标题键/披露标签）' {
    $tsPath = Join-Path $PSScriptRoot '../frontend/src/types/tools.ts'
    if (-not (Test-Path $tsPath)) { throw "前端注册表不可读：$tsPath" }
    $src = Get-Content -Raw -Encoding UTF8 -Path $tsPath
    $at = $src.IndexOf('export const FRONTEND_TOOLS')
    if ($at -lt 0) { throw 'tools.ts 里找不到 FRONTEND_TOOLS 注册表' }
    $block = $src.Substring($at)
    $end = $block.IndexOf("`n]")
    if ($end -gt 0) { $block = $block.Substring(0, $end) }
    $names = @([regex]::Matches($block, "(?m)^\s{4}name: '([a-z_0-9]+)'") | ForEach-Object { $_.Groups[1].Value })
    if ($names.Count -ne 18) { throw "前端注册表有 $($names.Count) 条，期望 18（7 原语 + 9 表格 + 2 编辑器/下载）" }
    $h = Ai-SheetHealth
    $writeOn = [bool]$h.writeEnabled
    $items = @((GetJson '/api/ai/tools?taskType=EXPORT&step=EXPORT').tools)
    $byName = @{}
    foreach ($it in $items) { $byName[$it.name] = $it }
    $sheetWrite = @('normalize_column', 'replace_values', 'update_rows', 'undo_last_sheet_edit')
    foreach ($n in $names) {
        $declared = @()
        $m = [regex]::Match($block, "(?m)^\s{4}name: '" + [regex]::Escape($n) + "'[\s\S]*?riskLevel: '(READ|WRITE|DANGER)'")
        if ($m.Success) { $declared = @($m.Groups[1].Value) }
        $it = $byName[$n]
        if (-not $it) {
            if ($sheetWrite -contains $n -and -not $writeOn) { continue }   # 写闸关 = 退出披露面（T-08 口径）
            throw "注册表工具 $n 不在 /api/ai/tools 的披露清单里（声明漂移）"
        }
        if ($it.channel -ne 'FRONTEND') { throw "$n 的通道是 $($it.channel)，注册表期望 FRONTEND" }
        if ($declared.Count -eq 1 -and $it.riskLevel -ne $declared[0]) {
            throw "$n 的风险级 $($it.riskLevel) 与前端声明 $($declared[0]) 不一致"
        }
        if (-not $it.titleKey) { throw "$n 缺 titleKey（卡面拿不到 i18n 键）" }
        if ($n -like 'ui_*' -and (@($it.scopePatterns) -join ',') -ne 'ui:on') {
            throw "$n 的 scopePatterns=$(@($it.scopePatterns) -join ',')，期望 ui:on（披露标签收窄）"
        }
    }
    return $null
}

Add-AiCase 'W-21' 'P1' '历史恢复段序对账①：/history 段序与归档快照 historyRoles 同源（去 SYSTEM 后逐段相等）' {
    $sid = Ai-NewSid 'w21'
    $frames = Ai-UiChat $sid 'UI-CLICK' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $conv = Ai-UiConverge $req '已点击「选择文件」' $null
    $hist = GetJson "/api/ai/history/$sid"
    $msgs = @($hist.messages)
    $histTypes = @($msgs | ForEach-Object { $_.type })
    $run = Ai-Run $req.runId
    $roles = @($run.historyRoles | Where-Object { $_ -ne 'SYSTEM' })
    if (($histTypes -join ',') -ne ($roles -join ',')) {
        throw "段序不一致：history=[$($histTypes -join ',')] snapshot=[$($roles -join ',')]"
    }
    if ($histTypes -notcontains 'TOOL') { throw '历史里缺 TOOL 段（工具结果没有进记忆）' }
    $call = @($msgs | Where-Object { $null -ne $_.toolCalls })[0]
    if (-not $call) { throw '历史里缺带 toolCalls 的 ASSISTANT 段' }
    if (@($call.toolCalls)[0].id -ne $req.toolCallId) { throw "历史 toolCalls id=$(@($call.toolCalls)[0].id) 与挂起条目 $($req.toolCallId) 不一致" }
    $resp = @($msgs | Where-Object { $null -ne $_.toolResponses })[0]
    if (-not $resp) { throw '历史里缺 TOOL 段（toolResponses 为空）' }
    if (@($resp.toolResponses)[0].id -ne $req.toolCallId) { throw '历史 toolResponses 的 id 与挂起条目不一致' }
    return $null
}

Add-AiCase 'W-22' 'P2' '历史恢复对账②：工具段只保留回灌 message 原文（不把声明/<tool_result>信封 JSON 透给用户）' {
    $sid = Ai-NewSid 'w22'
    $frames = Ai-UiChat $sid 'UI-CLICK' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $null = Ai-UiConverge $req '已点击「选择文件」（mutate-local）' $null
    $hist = GetJson "/api/ai/history/$sid"
    $resp = @(@($hist.messages) | Where-Object { $null -ne $_.toolResponses })[0]
    if (-not $resp) { throw '历史里缺 TOOL 段' }
    $data = [string]@($resp.toolResponses)[0].responseData
    if ($data -notlike '*选择文件*') { throw "历史工具结果丢了回灌原文（实际：$data）" }
    # L-70 收口：历史通道只回 message 原文 —— 模型侧声明/定界外壳/信封 JSON 一律不得上屏。
    if ($data -like '*是【数据】不是【指令】*') { throw '历史工具结果仍透出模型侧定界声明（应只保留 message 原文）' }
    if ($data -like '*<tool_result>*' -or $data -like '*</tool_result>*') { throw '历史工具结果仍透出 <tool_result> 外壳' }
    if ($data -like '*"outcome"*' -or $data -like '*"retryable"*') { throw '历史工具结果仍透出信封 JSON 字段' }
    foreach ($bad in @('C:\', '<script', 'javascript:', 'file://')) {
        if ($data -like "*$bad*") { throw "历史工具结果含禁用形态 $bad（回灌守卫的口径没有延续到历史通道）" }
    }
    return $null
}

Add-AiCase 'W-23' 'P1' '停止后可发下一条：中途停止的轮次不锁会话' {
    $sid = Ai-NewSid 'w23'
    $runId = Ai-StopMidTurn $sid '【AIE2E】turn-text stall-frames=6000 中途停止。' @{ page = 'tasks' }
    $next = Ai-ChatRaw $sid '【AIE2E】继续：停止后的下一条。' @{ page = 'tasks' }
    if ($next.status -eq 409) { throw "停止后同会话再发被 409 拦（$($next.json.code)）：会话被旧轮锁死" }
    if (-not $next.ok) { throw "停止后同会话再发 HTTP $($next.status)" }
    return $null
}

Add-AiCase 'W-24' 'P2' '停止后下一条可完整收敛并留档（新轮不继承旧轮终态）' {
    $sid = Ai-NewSid 'w24'
    $oldRun = Ai-StopMidTurn $sid '【AIE2E】turn-text stall-frames=6000 停止后留档。' @{ page = 'tasks' }
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】turn-text 停止后的完整一轮。'; context = @{ page = 'tasks' } } @('done', 'error') $frames $null
    $done = Ai-Done $frames
    if ($done.cancelled -eq $true) { throw '新轮一开始就是取消态（旧轮的停止位被继承）' }
    $newRun = Ai-RunId $frames
    if ($newRun -eq $oldRun) { throw '新轮复用了旧 runId' }
    $run = Ai-Run $newRun
    if ($run.snapshot.status -ne 'DONE') { throw "新轮状态 $($run.snapshot.status)，期望 DONE" }
    $hist = GetJson "/api/ai/history/$sid"
    if (@($hist.messages).Count -lt 2) { throw '停止后的新轮没有留档（历史只有旧轮）' }
    return $null
}

Add-AiCase 'W-25' 'P1' '停止归档收敛信号①：重挂归档能读到 done{cancelled:true}' {
    $sid = Ai-NewSid 'w25'
    $runId = Ai-StopMidTurn $sid '【AIE2E】turn-text stall-frames=6000 归档收敛。' @{ page = 'tasks' }
    $replay = Ai-WaitArchivedDone $runId
    $done = Ai-Done $replay
    if ($done.cancelled -ne $true) { throw "归档 done.cancelled=$($done.cancelled)，期望 true（停止事实没落档）" }
    Ai-SeqStrict $replay
    $starts = @($replay | Where-Object { $_.type -eq 'start' })
    if ($starts.Count -ne 1) { throw "归档 start 帧 $($starts.Count) 个（期望恰 1）" }
    return $null
}

Add-AiCase 'W-26' 'P2' '停止归档收敛信号②：runs / pending / runs-当前 三面同向（无残留 PENDING、不谎报 SUSPENDED）' {
    $sid = Ai-NewSid 'w26'
    $runId = Ai-StopMidTurn $sid '【AIE2E】turn-text stall-frames=6000 三面同向。' @{ page = 'tasks' }
    Ai-WaitNoPending $runId
    $run = Ai-Run $runId
    if ($run.snapshot.status -ne 'CANCELLED') { throw "runs/{runId} 状态 $($run.snapshot.status)，期望 CANCELLED" }
    $open = @($run.pending | Where-Object { $_.status -eq 'PENDING' })
    if ($open.Count -gt 0) { throw "停止后仍有 $($open.Count) 个条目停在 PENDING" }
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($cur.found -and $cur.snapshotStatus -eq 'SUSPENDED') { throw '停止后 runs/current 仍报 SUSPENDED（可发现面说谎）' }
    $hist = GetJson "/api/ai/history/$sid"
    if (@($hist.messages).Count -lt 1) { throw '停止后历史为空（用户那句提问丢了）' }
    return $null
}

Add-AiCase 'W-27' 'P1' '工具轮编排中停止：工具调用从未下发（零副作用）+ done{cancelled:true}' {
    $sid = Ai-NewSid 'w27'
    $runId = Ai-StopMidTurn $sid '【AIE2E】scenario=UI-SNAPSHOT slow-tool=8000 慢编排中途停止。' (Ai-UiCtx 1)
    $replay = Ai-WaitArchivedDone $runId
    $done = Ai-Done $replay
    if ($done.cancelled -ne $true) { throw "done.cancelled=$($done.cancelled)，期望 true" }
    $toolStarts = @($replay | Where-Object { $_.type -eq 'tool_start' })
    if ($toolStarts.Count -gt 0) { throw "停止前后已下发 $($toolStarts.Count) 个 tool_start（用户停止后仍推进了工具）" }
    Ai-WaitNoPending $runId
    return $null
}

Add-AiCase 'W-28' 'P2' '工具轮停止后同会话可再发（编排中停止不锁会话）' {
    $sid = Ai-NewSid 'w28'
    $runId = Ai-StopMidTurn $sid '【AIE2E】scenario=UI-SNAPSHOT slow-tool=8000 慢编排再发。' (Ai-UiCtx 1)
    $next = Ai-ChatRaw $sid '【AIE2E】继续：慢编排停止后的下一条。' @{ page = 'tasks' }
    if ($next.status -eq 409) { throw "停止后同会话再发被 409 拦（$($next.json.code)）" }
    if (-not $next.ok) { throw "停止后同会话再发 HTTP $($next.status)" }
    return $null
}

Add-AiCase 'W-29' 'P0' '确认门挂起中停止：条目终态 + 迟到决策被拒（不产生任何执行）' {
    $t = Ai-PublishFixture 'AIE2E-W-29-挂起中停止'
    $sid = Ai-NewSid 'w29'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = ("【AIE2E】scenario=IRREV-PUBLISH 任务 {0} 发布。" -f $t)
        context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' }
    } @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门' }
    $cancel = Ai-Cancel $cr.runId
    if (-not $cancel.cancelled) { throw 'POST /api/ai/cancel 未生效' }
    $final = Ai-WaitStatus $cr.runId @('CANCELLED') 20
    if ($final.snapshot.status -ne 'CANCELLED') { throw "停止后状态 $($final.snapshot.status)" }
    $entry = Ai-WaitPendingSettled $cr.runId $cr.toolCallId
    if (-not $entry) { throw '台账里条目消失（停止应留下终态而不是抹掉）' }
    if ($entry.status -eq 'PENDING') { throw '停止后条目仍停在 PENDING（挂起未收敛）' }
    if ($entry.executed -eq $true) { throw '停止后条目显示已执行（副作用越过了停止）' }
    $late = Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $cr.runId; toolCallId = $cr.toolCallId; approved = $true; reason = 'late' }
    if ($late.ok) { throw '停止后的迟到批准被接受（停止面被绕过）' }
    if ($late.status -ne 409) { throw "迟到批准 HTTP $($late.status)，期望 409" }
    $jobs = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
    if ($jobs.Count -gt 0) { throw '停止后仍创建了发布作业（不可撤销动作越过了停止）' }
    return $null
}

Add-AiCase 'W-30' 'P1' '前端工具挂起中停止：迟到回灌 409 + 同会话可发下一轮（不锁死）' {
    $sid = Ai-NewSid 'w30'
    $frames = Ai-UiChat $sid 'UI-SCROLL' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $cancel = Ai-Cancel $req.runId
    if (-not $cancel.cancelled) { throw 'cancel 未生效' }
    $final = Ai-WaitStatus $req.runId @('CANCELLED') 20
    if ($final.snapshot.status -ne 'CANCELLED') { throw "停止后状态 $($final.snapshot.status)" }
    $entry = Ai-WaitPendingSettled $req.runId $req.toolCallId
    if ($entry.status -eq 'PENDING') { throw '停止后条目仍停在 PENDING' }
    $late = Ai-FePost $req.runId $req.toolCallId '迟到的回灌' $null
    if ($late.ok) { throw '停止后的迟到回灌被接受（停止后零执行被破坏）' }
    if ($late.status -ne 409) { throw "迟到回灌 HTTP $($late.status)，期望 409" }
    # 取消终态的**真实归档**：done{cancelled:true} 恰 1 条、是最后一帧、seq 严格递增。
    # 收尾的落档是异步的（快照先变、归档后到），先沿用既有有界等待（Ai-WaitArchivedDone 内部即有界轮询），
    # 再整读归档断言。整读必须传**空 stopOn**：Ai-Events 的 stopOn 命中即 break，用 @('done') 读到的
    # 帧集按构造就以第一个 done 结尾 —— 那种帧集上的"done 是最后一帧"是同义反复，证不了归档尾部。
    # 终态轮的回放在排空后由服务端收尾（SseChatEmitter 的 L-143 判据），故空 stopOn 会读完整个归档即 EOF。
    $null = Ai-WaitArchivedDone $req.runId
    $archive = Ai-Events $req.runId 0 @()
    $cancelDone = Ai-Done $archive
    if ($cancelDone.cancelled -ne $true) { throw "取消终帧未带 cancelled=true（实际 $($cancelDone.cancelled)）" }
    Ai-DoneIsLast $archive
    Ai-SeqStrict $archive
    $next = Ai-ChatRaw $sid '【AIE2E】继续：挂起停止后的下一条。' @{ page = 'tasks' }
    if ($next.status -eq 409) { throw '挂起停止后同会话再发被 409 拦（会话锁死）' }
    if (-not $next.ok) { throw "再发 HTTP $($next.status)" }
    return $null
}

Add-AiCase 'W-31' 'P2' '历史分页不改段序：分片拼接 == 全量（offset/limit 只是切片）' {
    $sid = Ai-NewSid 'w31'
    $frames = Ai-UiChat $sid 'UI-CLICK' (Ai-UiCtx 1)
    $req = Ai-One $frames 'frontend_tool_request'
    if (-not $req) { throw '未收到 frontend_tool_request' }
    $null = Ai-UiConverge $req '已点击「选择文件」' $null
    $all = GetJson "/api/ai/history/${sid}"
    $total = [int]$all.total
    if ($total -lt 3) { throw "历史只有 $total 条（分页对照无从谈起）" }
    $joined = @()
    for ($off = 0; $off -lt $total; $off += 2) {
        $page = GetJson ("/api/ai/history/${sid}?offset={0}&limit=2" -f $off)
        if ([int]$page.total -ne $total) { throw "分页 total=$($page.total) 与全量 $total 不一致（切片改写了总量）" }
        $joined += @($page.messages | ForEach-Object { $_.type })
    }
    $full = @($all.messages | ForEach-Object { $_.type })
    if (($joined -join ',') -ne ($full -join ',')) { throw "分片段序 [$(($joined -join ','))] != 全量 [$(($full -join ','))]" }
    return $null
}

Add-AiCase 'W-32' 'P2' '停止归档幂等：同一 runId 重挂两次帧集相同、done 恰 1' {
    $sid = Ai-NewSid 'w32'
    $runId = Ai-StopMidTurn $sid '【AIE2E】turn-text stall-frames=6000 归档幂等。' @{ page = 'tasks' }
    $a = Ai-WaitArchivedDone $runId
    $b = Ai-Events $runId 0 @('done', 'error')
    $sa = (@($a | ForEach-Object { [int]$_.seq }) | Sort-Object) -join ','
    $sb = (@($b | ForEach-Object { [int]$_.seq }) | Sort-Object) -join ','
    if ($sa -ne $sb) { throw "两次重挂帧集不同：[$sa] vs [$sb]" }
    $dones = @(@($a | Where-Object { $_.type -eq 'done' })) + @(@($b | Where-Object { $_.type -eq 'done' }))
    if ($dones.Count -ne 2) { throw "两次重挂合计 done $($dones.Count) 个（期望各 1）" }
    return $null
}

Add-AiCase 'W-33' 'P2' '编排中停止零副作用：该轮台账无工具条目、pending 为空、无作业创建' {
    $t = Ai-ExportFixture 'AIE2E-W-33-零副作用'
    $sid = Ai-NewSid 'w33'
    $runId = Ai-StopMidTurn $sid ("【AIE2E】start_export 任务 {0} slow-tool=8000 中途停止。" -f $t) @{ page = 'tasks'; taskId = $t; taskType = 'EXPORT'; step = 'EXPORT' }
    Ai-WaitNoPending $runId
    $run = Ai-Run $runId
    if ($run.snapshot.status -ne 'CANCELLED') { throw "轮次状态 $($run.snapshot.status)" }
    $jobs = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'EXPORT' })
    if ($jobs.Count -gt 0) { throw '停止后仍创建了导出作业（副作用越过停止）' }
    return $null
}

Add-AiCase 'W-34' 'P1' '挂起中停止不遮挡新轮：同会话下一轮可再挂起且 runs/current 指向新轮' {
    $sid = Ai-NewSid 'w34'
    $frames = Ai-UiChat $sid 'UI-HIGHLIGHT' (Ai-UiCtx 1)
    $old = Ai-One $frames 'frontend_tool_request'
    if (-not $old) { throw '轮 1 未挂起' }
    $cur0 = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur0.found -or $cur0.runId -ne $old.runId) { throw '挂起时 runs/current 未指向该轮（刷新恢复无数据源）' }
    Ai-Cancel $old.runId | Out-Null
    $null = Ai-WaitStatus $old.runId @('CANCELLED') 20
    $cur1 = GetJson "/api/ai/runs/current?sessionId=$sid"
    if ($cur1.found -and $cur1.snapshotStatus -eq 'SUSPENDED') { throw '停止后 runs/current 仍报 SUSPENDED' }
    $frames2 = Ai-UiChat $sid 'UI-SCROLL' (Ai-UiCtx 1)
    $new = Ai-One $frames2 'frontend_tool_request'
    if (-not $new) { throw '停止后同会话下一轮未能再挂起（旧挂起遮挡了新轮）' }
    $cur2 = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur2.found) { throw '新轮挂起后 runs/current 为空' }
    if ($cur2.runId -ne $new.runId) { throw "runs/current 指向 $($cur2.runId)，期望新轮 $($new.runId)" }
    $null = Ai-UiConverge $new '已滚动到 export.panel.list' $null
    return $null
}

# V2 · 刷新恢复链（桩层 4 条）：客户端"刷新"在接口面的等价形态 = **断开后重新取数**。
# 前端 jsdom 侧的真形态已由 tests/aie2e-refresh-forms.spec.ts 覆盖（全新 store + /history +
# /runs/current + 帧）；这里补的是那三处持久面在**真进程 / 真 SSE** 下确实可供重建 ——
# 单测无论怎么 mock 都不能证明"服务端真的还留着这段现场"。

Add-AiCase 'W-35' 'P1' '刷新恢复①在途轮发现：客户端断开后 runs/current 仍报在途轮与归档末号' {
    $sid = Ai-NewSid 'w35'
    $head = New-Object System.Collections.ArrayList
    # 只读到 start 就断开（= 用户刷新页面）：连接没了，但这一轮还在服务端跑
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】turn-text stall-frames=2000 刷新恢复在途轮发现。'; context = @{ page = 'tasks' } } @('start') $head
    $runId = Ai-RunId $head
    # 取数时点（AR-06）：start 帧前移到"请求前压缩"之前发出，故"收到 start 就立刻读"会命中
    # "快照还没建"的合法窗口，runs/current 此刻必然 found=false。刷新恢复的判据是**快照建好后的**
    # 持久面，故先有界等"这一轮被建立"（上界 = 首字节预算，产品键），超时即 FAIL。
    # 断言面不变：仍要求它指向本轮的 runId、报 RUNNING、archiveMaxSeq≥1。
    $cur = Ai-WaitCurrentRun $sid $runId (Get-AiResilienceSeconds 'first-byte-timeout')
    if ($cur.runId -ne $runId) { throw "runs/current 指向 $($cur.runId)，期望 $runId" }
    if ($cur.snapshotStatus -ne 'RUNNING') { throw "断开后快照状态 $($cur.snapshotStatus)，期望 RUNNING（在途轮发现的判据）" }
    if ([int]$cur.archiveMaxSeq -lt 1) { throw "archiveMaxSeq=$($cur.archiveMaxSeq)，期望 ≥1（无归档则刷新后无从续收）" }
    $null = Ai-Cancel $runId
    $null = Ai-WaitStatus $runId @('CANCELLED') 20
    return $null
}

Add-AiCase 'W-36' 'P1' '刷新恢复②全量重放：断开后 lastSeq=0 重连收齐 start/delta/done（seq 严格递增）' {
    $sid = Ai-NewSid 'w36'
    $head = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】turn-text stall-frames=600 刷新恢复全量重放。'; context = @{ page = 'tasks' } } @('start') $head
    $runId = Ai-RunId $head
    # 刷新恢复的在途路径（restoreInFlightRound）就是 lastSeq=0 全量重放：镜像正文滞后，增量必缺口。
    # 取数时点（AR-06）：start 已到但快照可能未建，此刻 /events 回 202 {preparing,retryAfterMs}；
    # 202 是 2xx，若当"回放零帧"读就变成了"重放没有 start"的假红。按 retryAfterMs 有界重挂，
    # 直到拿到真回放（上界 = 首字节预算，产品键），断言面不变。
    $replay = Ai-EventsReady $runId 0 @('done', 'error') (Get-AiResilienceSeconds 'first-byte-timeout')
    $starts = @($replay | Where-Object { $_.type -eq 'start' })
    if ($starts.Count -ne 1) { throw "重放 start 帧 $($starts.Count) 个（期望恰 1）" }
    if ($starts[0].runId -ne $runId) { throw "重放首帧 runId=$($starts[0].runId)，期望 $runId" }
    $deltas = @($replay | Where-Object { $_.type -eq 'delta' })
    if ($deltas.Count -lt 1) { throw '重放没有任何 delta（全量重放不完整）' }
    $errs = @($replay | Where-Object { $_.type -eq 'error' })
    if ($errs.Count -gt 0) { throw "重放出现 error 帧（$($errs[0].code)）：断开影响了本轮" }
    $done = Ai-Done $replay
    if ($done.cancelled -eq $true) { throw '重放 done.cancelled=true（未停止的轮被标成取消）' }
    Ai-SeqStrict $replay
    # 正文拼接必须完整（切片按 24 字符发；有洞会在这里露出来）
    $text = ($deltas | ForEach-Object { [string]$_.text }) -join ''
    if ($text -notlike '*命名轮蓝本*' -or $text.Length -lt 40) { throw "重放正文不完整（长度 $($text.Length)）" }
    return $null
}

Add-AiCase 'W-37' 'P1' '刷新恢复③挂起卡重建：断开后快照条目自带 kind/req/arguments 且与 confirm_request 帧同源' {
    $t = Ai-PublishFixture 'AIE2E-W-37-挂起卡重建'
    $sid = Ai-NewSid 'w37'
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = ("请立即调用 start_publish 工具发布导入任务 {0}（系统会自动弹出确认卡片，无需再询问我）" -f $t); context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' } } `
        @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（挂起卡重建无从对账）' }
    # 读到 confirm_request 即断开 = 用户此刻刷新：挂起现场只能由快照重建
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found) { throw '断开后 runs/current 为空（刷新后挂起卡无处重建）' }
    if ($cur.runId -ne $cr.runId) { throw "runs/current 指向 $($cur.runId)，期望 $($cr.runId)" }
    if (-not $cur.awaitingExternal) { throw '断开后 awaitingExternal=false（挂起轮被当成"在途续收"而非"等人工"）' }
    $entry = @($cur.pendingEntries | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $entry) { throw '快照 pendingEntries 缺该条目（卡无从重建）' }
    if ($entry.entryStatus -ne 'PENDING') { throw "条目状态 $($entry.entryStatus)，期望 PENDING（已决条目不重建卡）" }
    if ($entry.kind -ne 'CONFIRM') { throw "条目 kind=$($entry.kind)，期望 CONFIRM（三值原文）" }
    if ($entry.req -ne $true) { throw "条目 req=$($entry.req)，期望 true（请求帧真的发过的条目才可建卡）" }
    if ($entry.name -ne $cr.name) { throw "条目 name=$($entry.name) 与帧 name=$($cr.name) 不同源" }
    if ([string]::IsNullOrWhiteSpace([string]$entry.arguments)) { throw '条目缺 arguments（卡面入参无从渲染）' }
    $pend = @(GetJson "/api/ai/pending/$($cr.runId)" | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $pend -or $pend.status -ne 'PENDING') { throw '/pending 与 runs/current 不同向（同一挂起两个口径）' }
    if ([int]$cur.archiveMaxSeq -lt [int]$cr.seq) { throw "archiveMaxSeq=$($cur.archiveMaxSeq) < confirm_request.seq=$($cr.seq)（续收锚点落后于已发帧）" }
    $null = Ai-Cancel $cr.runId
    $null = Ai-WaitStatus $cr.runId @('CANCELLED') 20
    return $null
}

Add-AiCase 'W-38' 'P2' '刷新恢复④增量续收锚点：以已收末号 lastSeq=K 续收只补新帧、不重放旧帧' {
    $sid = Ai-NewSid 'w38'
    $head = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $sid; message = '【AIE2E】turn-text stall-frames=700 刷新恢复增量续收。'; context = @{ page = 'tasks' } } @('delta') $head
    $runId = Ai-RunId $head
    $seen = @($head | Where-Object { $null -ne $_.seq } | ForEach-Object { [int]$_.seq })
    if ($seen.Count -eq 0) { throw '断开前未收到任何带序号的帧（锚点无从确定）' }
    $k = ($seen | Measure-Object -Maximum).Maximum
    $cur = GetJson "/api/ai/runs/current?sessionId=$sid"
    if (-not $cur.found) { throw '断开后 runs/current 为空' }
    if ([int]$cur.archiveMaxSeq -lt $k) { throw "archiveMaxSeq=$($cur.archiveMaxSeq) < 已收末号 $k（锚点落后 ⇒ 续收会重放旧帧）" }
    $tail = Ai-Events $runId $k @('done', 'error')
    $old = @($tail | Where-Object { $null -ne $_.seq -and [int]$_.seq -le $k })
    if ($old.Count -gt 0) { throw "增量续收重放了 $($old.Count) 个旧帧（lastSeq=$k 未生效）" }
    $done = Ai-Done $tail
    if ($done.cancelled -eq $true) { throw '增量续收收尾为 cancelled（未停止的轮被标成取消）' }
    Ai-SeqStrict $tail
    return $null
}

# V5 · 不可撤销 + 预览→应用→撤销（桩层 2 条）：
#   B9 已由 W-12/13/14 锁住「irreversible 三通道保真」，缺的是**批准之后**的链路
#   （复选框语义在协议面的投影 = irreversible=true 的卡被批准后照常执行、作业真的产生）；
#   B13/D7 的浏览器半段在 vitest（sheet-preview-apply-undo.spec.ts），这里补协议半段：
#   应用回灌与撤销回灌各自走通「frontend_tool_result ok → done」，两轮同会话。
Add-AiCase 'W-39' 'P0' '不可撤销卡批准后执行（IRREV-PUBLISH）：irreversible 保真 → 批准 → 工具执行 → done + 发布作业真实产生' {
    $t = Ai-PublishFixture 'AIE2E-W-39-不可撤销批准'
    $sid = Ai-NewSid 'w39'
    $jobsBefore = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' }).Count
    $frames = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = ("【AIE2E】scenario=IRREV-PUBLISH 任务 {0} 发布。" -f $t)
        context = @{ page = 'import'; taskId = $t; taskType = 'IMPORT'; step = 'PUBLISH' }
    } @('confirm_request', 'done', 'error') $frames $null
    $cr = Ai-One $frames 'confirm_request'
    if (-not $cr) { throw '未挂起到确认门（不可撤销批准链路无从核对）' }
    if ($cr.name -ne 'start_publish') { throw "确认工具 $($cr.name)，期望 start_publish" }
    if ($cr.irreversible -ne $true) { throw 'confirm_request 帧 irreversible 不是 true（复选框语义丢失）' }
    $sus = Ai-One $frames 'suspended'
    if (-not $sus) { throw '未收到 suspended 帧' }
    $susEntry = @($sus.toolCalls | Where-Object { $_.toolCallId -eq $cr.toolCallId })[0]
    if (-not $susEntry -or $susEntry.irreversible -ne $true) { throw 'suspended 帧的 irreversible 与 confirm_request 不同源' }
    $k = Ai-MaxSeq $frames
    $conf = Invoke-Api 'POST' '/api/ai/confirm' @{ runId = $cr.runId; toolCallId = $cr.toolCallId; approved = $true; reason = 'e2e-w39' }
    if (-not $conf.ok) { throw "POST /api/ai/confirm => HTTP $($conf.status) $($conf.text)" }
    $after = Ai-Events $cr.runId $k @('done', 'error')
    $decisions = Ai-F $after 'confirm_decision'
    if ($decisions.Count -ne 1) { throw "confirm_decision 帧 $($decisions.Count) 个（期望恰 1）" }
    if ($decisions[0].approved -ne $true) { throw "confirm_decision.approved=$($decisions[0].approved)" }
    $results = @($after | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $cr.toolCallId
    })
    if ($results.Count -ne 1) { throw "结局帧 $($results.Count) 个（期望恰 1：批准后必须真的执行一次）" }
    if ($results[0].type -ne 'tool_result') { throw "结局帧类型是 $($results[0].type)，期望 tool_result（后端执行类工具）" }
    if ($results[0].outcome -ne 'ok') { throw "结局帧 outcome=$($results[0].outcome)，期望 ok" }
    $done = Ai-Done $after
    if ($done.cancelled -eq $true) { throw '批准执行的轮收尾为 cancelled（未停止的轮被标成取消）' }
    Ai-DoneIsLast $after
    Ai-SeqStrict $after
    $job = Wait-Until {
        $js = @(GetJson "/api/tasks/$t/jobs" | Where-Object { $_.jobType -eq 'PUBLISH' })
        if ($js.Count -gt $jobsBefore) { return $js[0] } else { return $null }
    } 60 '批准后未创建发布作业（复选框只是 UI 语义、执行链没接上）'
    if ((Wait-Job $job.id).status -ne 'COMPLETED') { throw "发布作业未 COMPLETED（当前 $((Wait-Job $job.id).status)）" }
    $ledger = @((Ai-Run $cr.runId).ledger | Where-Object { $_.toolCallId -eq $cr.toolCallId })
    if ($ledger.Count -lt 1) { throw '台账里没有该 toolCallId 的执行记录（批准后没有真的执行）' }
    if ($ledger[0].outcome -ne 'ok') { throw "台账 outcome=$($ledger[0].outcome)，期望 ok" }
    if ([string]::IsNullOrWhiteSpace([string]$ledger[0].executedBy)) { throw '台账缺 executedBy（不知道谁执行的）' }
    return $null
}

Add-AiCase 'W-40' 'P1' '预览→应用→撤销跨层闭环（桩级协议半段）：update_rows 应用回灌 + undo_last_sheet_edit 撤销回灌各自收敛到 done' {
    $names = Ai-ToolsOf $script:aiSheetCtx
    $writeOn = $names -contains 'update_rows'
    $undoOn = $names -contains 'undo_last_sheet_edit'
    if ($writeOn -ne $undoOn) { throw "写闸两侧不同源：update_rows=$writeOn / undo_last_sheet_edit=$undoOn（撤销与写必须同一个闸）" }
    $t = Ai-ExportFixture 'AIE2E-W-40-撤销闭环'
    $sid = Ai-NewSid 'w40'
    if (-not $writeOn) {
        # 写闸关：两个 WRITE 面工具都不披露 ⇒ 两轮都退化为文本轮、零工具帧
        foreach ($marker in @('SHEET-UPDATE', 'SHEET-UNDO')) {
            $frames = New-Object System.Collections.ArrayList
            Read-Sse '/api/ai/chat' @{
                sessionId = $sid
                message = "【AIE2E】scenario=$marker 任务 $t 写闸关口径检查。"
                context = (Ai-SheetCtx $t)
            } @('frontend_tool_request', 'done', 'error') $frames $null
            if (Ai-One $frames 'frontend_tool_request') { throw "写闸关但 $marker 仍下发 frontend_tool_request（披露闸被绕过）" }
            if (@($frames | Where-Object { $_.type -eq 'tool_start' }).Count -gt 0) { throw "写闸关但 $marker 轮仍有 tool_start 帧" }
            Ai-Done $frames | Out-Null
            Ai-SeqStrict $frames
        }
        return $null
    }
    # 轮①：应用（写工具挂起 → 浏览器回灌 → frontend_tool_result + done）
    $before = Ai-SheetCounters
    $frames1 = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = "【AIE2E】scenario=SHEET-UPDATE 任务 $t 把 code 为 USD 那一行的小数位数改成 3"
        context = (Ai-SheetCtx $t)
    } @('frontend_tool_request', 'done', 'error') $frames1 $null
    $req = Ai-One $frames1 'frontend_tool_request'
    if (-not $req) { throw '写闸开但未收到 update_rows 的 frontend_tool_request' }
    if ($req.name -ne 'update_rows') { throw "挂起工具是 $($req.name)，期望 update_rows" }
    $entry = Gf-PendingEntry $req.runId $req.toolCallId
    if ($entry.kind -ne 'FRONTEND') { throw "pending.kind=$($entry.kind)，期望 FRONTEND" }
    $applied = "已应用 1 格（plan #e2e-plan-w40，回到修改前值可撤销）。`n共 1 格"
    $posted1 = Ai-SheetToolResult $req.runId $req.toolCallId $applied @{
        dataClass = 'D2'; sampleRows = 1; planId = 'e2e-plan-w40'; previewLevel = 'sample-diff'; sheetVer = 1; defCode = 'CURRENCY'
    }
    if (-not $posted1.ok) { throw "应用回灌 HTTP $($posted1.status) $($posted1.text)" }
    $after1 = Ai-Events $req.runId 0 @('done', 'error')
    $res1 = @($after1 | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req.toolCallId
    })
    if ($res1.Count -ne 1 -or $res1[0].type -ne 'frontend_tool_result') { throw "应用结局帧 $($res1.Count) 个 / 类型 $($res1[0].type)" }
    if ([string]$res1[0].result -notlike '*plan #e2e-plan-w40*') { throw '应用结局帧 result 不含回灌文本' }
    if ($res1[0].outcome -ne 'ok') { throw "应用结局帧 outcome=$($res1[0].outcome)，期望 ok" }
    Ai-Done $after1 | Out-Null
    Ai-DoneIsLast $after1
    Ai-SeqStrict $after1
    # 轮②：撤销（同会话；撤销是另一个 WRITE 面工具，必须同样披露并挂起）
    $frames2 = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{
        sessionId = $sid
        message = "【AIE2E】scenario=SHEET-UNDO 任务 $t 撤销刚才那次修改。"
        context = (Ai-SheetCtx $t)
    } @('frontend_tool_request', 'done', 'error') $frames2 $null
    $req2 = Ai-One $frames2 'frontend_tool_request'
    if (-not $req2) { throw '写闸开但未收到 undo_last_sheet_edit 的 frontend_tool_request' }
    if ($req2.name -ne 'undo_last_sheet_edit') { throw "挂起工具是 $($req2.name)，期望 undo_last_sheet_edit" }
    if ([string]$req2.args -notlike '*CURRENCY*') { throw "撤销入参缺 defCode（$($req2.args)）" }
    if ([string]$req2.args -match '人民币|美元|7\.24') { throw '撤销入参出现了表内业务值（越界）' }
    $entry2 = Gf-PendingEntry $req2.runId $req2.toolCallId
    if ($entry2.kind -ne 'FRONTEND') { throw "撤销条目 kind=$($entry2.kind)，期望 FRONTEND" }
    $undone = "已撤销 1 格（plan #e2e-plan-w40，回到修改前值）。剩余可撤销 0 层。`n共 1 格"
    $posted2 = Ai-SheetToolResult $req2.runId $req2.toolCallId $undone @{
        dataClass = 'D1'; planId = 'e2e-plan-w40'; previewLevel = 'sample-diff'; sheetVer = 1; defCode = 'CURRENCY'
    }
    if (-not $posted2.ok) { throw "撤销回灌 HTTP $($posted2.status) $($posted2.text)" }
    $after2 = Ai-Events $req2.runId 0 @('done', 'error')
    $res2 = @($after2 | Where-Object {
        ($_.type -eq 'tool_result' -or $_.type -eq 'frontend_tool_result') -and $_.toolCallId -eq $req2.toolCallId
    })
    if ($res2.Count -ne 1 -or $res2[0].type -ne 'frontend_tool_result') { throw "撤销结局帧 $($res2.Count) 个 / 类型 $($res2[0].type)" }
    if ([string]$res2[0].result -notlike '*剩余可撤销 0 层*') { throw '撤销结局帧 result 不含层数回执（撤销链在协议面断掉）' }
    if ($res2[0].outcome -ne 'ok') { throw "撤销结局帧 outcome=$($res2[0].outcome)，期望 ok" }
    Ai-Done $after2 | Out-Null
    Ai-DoneIsLast $after2
    Ai-SeqStrict $after2
    $counters = Ai-SheetCounters
    if ([int]$counters.writePlans -le [int]$before.writePlans) { throw "writePlans 未增长（$($before.writePlans) → $($counters.writePlans)）：应用+撤销两轮没进审计面" }
    return $null
}


# ============================================================
# 批① 汇总：逐域 n/m + 计数自校验（MANIFEST 对账）
#   名单推导链（设计稿 §4.1/§4.3/§4.4/§4.5/§3）：
#     154 = 88（本节）+ 10（批② nightly 真模型）+ 41（批③ UI）+ 7（批④ 多实例）
#           + 8（收编：G-03/G-05/G-06/D-12/C-03..C-06 已由既有 GF3/GF5/GF6/CTX-1..4 断言承载）
#   本节名单 = 154 − 41 − 10 − 7 − 8；优先级 = §2 逐行 P 列的直接汇总（12/50/26）
#   V1 追加（测试扩充 V1 · 工作区原语全链）：W 域 34 条（90 → 124），P0 12→15 / P1 52→72 / P2 26→37
#   V2 追加（测试扩充 V2 · 刷新/停止真形态下沉）：W 域 4 条（124 → 128）——
#     W-35 在途轮发现 / W-36 全量重放 / W-37 挂起卡重建源数据 / W-38 增量续收锚点，
#     P1 +3 / P2 +1（前端 jsdom 真形态 26 条见 tests/aie2e-refresh-forms.spec.ts，不计入本节）
#   V5 追加（测试扩充 V5 · 不可撤销 & 预览/应用/撤销跨层）：W 域 2 条（128 → 130）——
#     W-39 不可撤销卡批准后执行（IRREV-PUBLISH，P0；B9 批准后续链路）/
#     W-40 预览→应用→撤销协议半段（SHEET-UPDATE + SHEET-UNDO，P1；B13/D7），P0 +1 / P1 +1
#   AR-04 追加（2026-10-26 批次8A · Q-08 会话门裁决的施工面）：D 域 1 条（130 → 131）——
#     D-13 会话门持有期 409（**可控时序**：门确定被持有时发轮被拒、回执指向持有轮）。
#     Q-08 / Q-05 / D-08 / M-07 同批改为**两果都合法**：200 走完成断言、409 走忙回执（SESSION_BUSY +
#     runId + reattach），历史不污染 / 无幻影气泡 / 无假提示三面在两分支之前先断（条数不变、只改断言面）。
#     遗留 TC18（既有 35 条之一）同口径改造，并移除其 Start-Sleep 与"重发到 200"的重试循环。
#     D-13 不来自设计稿 §2（设计稿 D 域止于 D-12，且 D-12 已收编）
# ============================================================
Say '============================================================'
Say '--- 批① · 131 用例执行（桩模式，零 SKIP） ---'
$script:aiStat = @{}
$script:aiExecuted = 0
$script:aiSkip = 0
$script:aiPass = 0
$script:aiP0Fail = 0
$script:aiP1Fail = 0
$script:aiP2Fail = 0
$script:aiP = @{ P0 = 0; P1 = 0; P2 = 0 }
foreach ($c in @($script:aiCases)) {
    $domain = ($c.id -split '-')[0]
    if (-not $script:aiStat.ContainsKey($domain)) { $script:aiStat[$domain] = @{ pass = 0; fail = 0 } }
    $why = $null
    try { $why = & $c.body } catch { $why = $_.Exception.Message }
    $script:aiExecuted++
    $script:aiP[$c.p] = [int]$script:aiP[$c.p] + 1
    if ([string]$why -like 'SKIP:*') {
        # 本节不允许 SKIP（桩下零 SKIP）——把它记成 FAIL 并计入 aiSkip，由 MANIFEST 断言判红
        $script:aiSkip++
        $why = 'SKIP 非法：批① 桩模式下不允许 SKIP（' + ([string]$why).Substring(5) + '）'
    }
    if ([string]::IsNullOrWhiteSpace([string]$why)) {
        $script:aiStat[$domain].pass++
        $script:aiPass++
        Ok ("AIE2E-{0} [{1}] {2}" -f $c.id, $c.p, $c.title)
    } else {
        $script:aiStat[$domain].fail++
        switch ($c.p) {
            'P0' { $script:aiP0Fail++ }
            'P1' { $script:aiP1Fail++ }
            'P2' { $script:aiP2Fail++ }
        }
        No ("AIE2E-{0} [{1}] {2}" -f $c.id, $c.p, $c.title) $why
    }
}
$expect = [ordered]@{ S = 13; P = 1; H = 11; G = 11; T = 10; K = 6; Q = 4; C = 8; M = 3; D = 12; X = 12; W = 40 }
$domainLines = @()
foreach ($k in $expect.Keys) {
    $st = if ($script:aiStat.ContainsKey($k)) { $script:aiStat[$k] } else { @{ pass = 0; fail = 0 } }
    $domainLines += ("{0}={1}/{2}" -f $k, $st.pass, ($st.pass + $st.fail))
}
Say (" 批① 逐域：" + ($domainLines -join '  '))
Say (" 批① 合计：{0}/{1}（P0={2} P1={3} P2={4}）" -f $script:aiPass, $script:aiExecuted, $script:aiP.P0, $script:aiP.P1, $script:aiP.P2)

$manifestWhy = @()
if ($script:aiCases.Count -ne 131) { $manifestWhy += "用例清单 $($script:aiCases.Count) 条，蓝图要求 131（88 设计稿 + 2 L-21 主用例 + 34 V1 工作区原语 + 4 V2 刷新恢复链 + 2 V5 不可撤销/撤销闭环 + 1 AR-04 可控时序 409）" }
if ($script:aiExecuted -ne 131) { $manifestWhy += "实际执行 $($script:aiExecuted) 条，期望 131（88 设计稿 + 2 L-21 主用例 + 34 V1 工作区原语 + 4 V2 刷新恢复链 + 2 V5 不可撤销/撤销闭环 + 1 AR-04 可控时序 409）" }
if ($script:aiSkip -ne 0) { $manifestWhy += "SKIP=$($script:aiSkip) 条（桩下零 SKIP 是硬底线）" }
foreach ($k in $expect.Keys) {
    $n = @($script:aiCases | Where-Object { $_.id -like "$k-*" }).Count
    if ($n -ne $expect[$k]) { $manifestWhy += "域 $k 有 $n 条，蓝图 $($expect[$k]) 条" }
}
if ($script:aiP.P0 -ne 18 -or $script:aiP.P1 -ne 74 -or $script:aiP.P2 -ne 39) {
    $manifestWhy += "优先级分布 P0/P1/P2=$($script:aiP.P0)/$($script:aiP.P1)/$($script:aiP.P2)，蓝图 18/74/39（88 设计稿 12/50/26 + 2 条 P1 主用例 + V1 的 4/18/12 + V2 的 0/3/1 + V5 的 1/1/0 + D-13 的 1/0/0）"
}
if ($manifestWhy.Count -gt 0) {
    No 'AIE2E-MANIFEST 计数自校验' ($manifestWhy -join '；')
} else {
    Ok ("AIE2E-MANIFEST 计数自校验：131 条 / 12 域（S13 P1 H11 G11 T10 K6 Q4 C8 M3 D12 X12 W40）/ P0 {0} · P1 {1} · P2 {2}，与设计稿 §2 逐行 P 列一致 + L-21 两条表格主用例（T-13/T-14，P1）+ V1 的 W 域 34 条 + V2 的刷新恢复链 4 条 + V5 的不可撤销批准/撤销闭环 2 条 + AR-04 的 D-13（可控时序 409，P0）" -f $script:aiP.P0, $script:aiP.P1, $script:aiP.P2)
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
    # 批① 口径（设计稿 §4.2 第 1 项）：新增用例只删自己的 AIE2E-* 任务，不碰既有 S5B-*/E2E_TEMP 之外的资产
    $mineAi = @((GetJson '/api/tasks?page=0&size=500').content | Where-Object { $_.task.title -like 'AIE2E-*' })
    foreach ($row in $mineAi) { DeleteJson ("/api/tasks/" + $row.task.id) | Out-Null }
    $leftAi = @((GetJson '/api/tasks?page=0&size=500').content | Where-Object { $_.task.title -like 'AIE2E-*' })
    if ($leftAi.Count -gt 0) { throw "仍有 $($leftAi.Count) 个 AIE2E-* 任务未删除" }
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
# R-137 红队补测桩（MAJOR-2/MAJOR-3，step-wizard-pkg\红队报告.md :48-64 原案）
#   MAJOR-2 = 后端高危工具绕行只读：终态任务上 start_* 必须拒建作业（TASK_FINALIZED 409）
#   MAJOR-3 = SpreadGrid 只读态单元格不可编辑（F2 修复的运行期锁：isProtected 生效）
#   口径注记（R-137 E2E 热修项 3，改如实）：MAJOR-2/3 为守卫断言，走主计数器 Ok()/No()，
#   PASS/FAIL 计入主用例计数（登记时口径 29+2 ⇒ 当时的真实基数是 31；现基数见脚本头「结果口径」= 35），
#   任一失败即扛 exit 1（与 TC 用例同门禁属性）。
# ============================================================
Say '--- R-137 红队补测（MAJOR-2/MAJOR-3） ---'
try {
    # MAJOR-2 ①：CANCELLED 任务上四类作业全部 409 TASK_FINALIZED，且不留作业行
    $m2task = New-ExportTask 'S5B-R137-终态拒建' @('CURRENCY')
    Start-JobOf $m2task 'EXPORT' | Out-Null
    $m2job = (GetJson "/api/tasks/$m2task/jobs")[0]
    # 经取消联动把任务置 CANCELLED（唯一合法通路：DELETE /api/jobs/{id}）
    DeleteJson "/api/jobs/$($m2job.id)" | Out-Null
    $m2t = GetJson "/api/tasks/$m2task"
    if ($m2t.status -ne 'CANCELLED') { throw "取消联动未生效（status=$($m2t.status)）" }
    foreach ($jt in @('EXPORT', 'PRECHECK', 'IMPORT', 'PUBLISH')) {
        # 终态传播窗口（MAJOR-2 间歇红的口径）：作业终态先行提交、任务行的终态联动随后写，
        # 两条写不在同一事务 —— 请求正好落进窗口时，终态守卫看不到 CANCELLED，会先撞上后一道
        # 守卫（IMPORT/PUBLISH ⇒ PRECHECK_NOT_PASSED）。断言值不变（仍必须 409/TASK_FINALIZED），
        # 只是把"瞬时读"换成"有界条件等待"，窗口过后结论必然稳定。
        $deadline = (Get-Date).AddSeconds(10)
        $last = $null
        while ((Get-Date) -lt $deadline) {
            $last = Invoke-Api 'POST' "/api/tasks/$m2task/jobs" @{ jobType = $jt }
            if ($last.status -eq 409 -and $last.json -and $last.json.code -eq 'TASK_FINALIZED') { break }
            if ($last.ok) { throw "终态任务上 $jt 未被拒 —— 预期被拒绝但请求成功（HTTP $($last.status)）" }
            Start-Sleep -Milliseconds 300
        }
        if ($null -eq $last -or $last.status -ne 409 -or $last.json.code -ne 'TASK_FINALIZED') {
            throw "MAJOR-2：$jt 响应 HTTP $($last.status)/code=$($last.json.code)，期望 409/TASK_FINALIZED"
        }
    }
    if (@(GetJson "/api/tasks/$m2task/jobs").Count -ne 1) { throw 'MAJOR-2：被拒请求留了作业行' }
    # MAJOR-2 ②：COMPLETED 且最终作业成功（EXPORT/EXPORT COMPLETED）同样拒建
    $m2done = New-ExportTask 'S5B-R137-完成后拒建' @('CURRENCY')
    $dj = Start-JobOf $m2done 'EXPORT'
    if ((Wait-Job $dj.id).status -ne 'COMPLETED') { throw 'MAJOR-2 前置：导出作业未完成' }
    # 作业 COMPLETED 与任务行联动是两次提交：前置断言也要等联动落地（同 ① 的传播窗口口径）
    $m2t2 = Wait-Until { $x = GetJson "/api/tasks/$m2done"; if ($x.status -eq 'COMPLETED') { return $x } return $null } 10 '任务行未随作业终态联动为 COMPLETED'
    if ($m2t2.status -ne 'COMPLETED') { throw "MAJOR-2 前置：任务未置 COMPLETED（$($m2t2.status)）" }
    $r2 = $null
    $deadline2 = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline2) {
        $r2 = Invoke-Api 'POST' "/api/tasks/$m2done/jobs" @{ jobType = 'EXPORT' }
        if ($r2.status -eq 409 -and $r2.json -and $r2.json.code -eq 'TASK_FINALIZED') { break }
        if ($r2.ok) { throw '终态任务上 EXPORT 未被拒 —— 预期被拒绝但请求成功' }
        Start-Sleep -Milliseconds 300
    }
    if ($r2.status -ne 409 -or $r2.json.code -ne 'TASK_FINALIZED') {
        throw "MAJOR-2：COMPLETED+终作业 响应 HTTP $($r2.status)/code=$($r2.json.code)"
    }
    # MAJOR-2 ③（回归锁）：FAILED 任务放行（僵尸恢复语义）
    $m2fail = New-ExportTask 'S5B-R137-失败放行' @('CURRENCY')
    Invoke-Api 'POST' "/api/tasks/$m2fail/jobs" @{ jobType = 'EXPORT' } | Out-Null
    $fj = (GetJson "/api/tasks/$m2fail/jobs")[0]
    DeleteJson "/api/jobs/$($fj.id)" | Out-Null  # 取消联动 ⇒ 任务 CANCELLED；再手置 FAILED 模拟僵尸恢复面
    $m2failObj = GetJson "/api/tasks/$m2fail"
    # FAILED 任务只能经作业失败联动产生，此处改用 REST 直接构造：跳过（H2 FAILED 放行已由后端单测锁定），仅清场
    # 清场竞态修复（R-153 顺带①）：取消后立即 DELETE 任务会与执行器迟到终态链并发（乐观锁 500，
    # 复现报告 §4.2）。旧等待轮询的是作业行 —— 取消已同步把作业行置 CANCELLED，while(PENDING/RUNNING)
    # 首判即假、循环零次进入，形同无等待。改按「任务行 version 稳定」重设信号：执行器侧 afterCommit
    # 链路（REQUIRES_NEW 终态+联动）若发生会推进任务行 version；连续两次读数相同（间隔 400ms）即视为
    # 落定。上限 5s，超时保留 WARN 语义仍执行删除。不碰 MAJOR-2 严判断言（409/TASK_FINALIZED 保持）。
    foreach ($t in @($m2task, $m2done, $m2fail)) {
        $prevVer = $null
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $stable = $false
        while (-not $stable) {
            if ($sw.ElapsedMilliseconds -gt 5000) { break }
            Start-Sleep -Milliseconds 400
            try { $ver = (GetJson "/api/tasks/$t").version } catch { $ver = $null }
            if ($null -ne $ver -and $ver -eq $prevVer) { $stable = $true } else { $prevVer = $ver }
        }
        if (-not $stable) {
            Write-Host ("  [WARN] MAJOR-2 清场：任务 {0} {1}ms 任务行 version 未稳定，仍执行删除" -f $t, $sw.ElapsedMilliseconds) -ForegroundColor Yellow
        }
        DeleteJson "/api/tasks/$t" | Out-Null
    }
    Ok 'MAJOR-2 终态任务上 start_* 拒建作业（CANCELLED/COMPLETED+终作业 409 TASK_FINALIZED、不留行）'
} catch { No 'MAJOR-2' $_.Exception.Message }
try {
    # MAJOR-3：SpreadGrid 只读态单元格不可编辑（isProtected 由 readOnly 驱动，F2 已修 watch；此处锁运行期契约）
    # 桩形态：直接断言组件源内的 watch(readOnly) → applyReadOnly 联动与 isProtected 赋值仍在位（红队 :57-64 原案的代码面锁 + CDP 运行期断言属 E2E 真跑面，此处用源断言兜底）
    $sg = Get-Content -Raw (Join-Path $PSScriptRoot '../frontend/src/components/SpreadGrid.vue')
    if ($sg -notmatch 'watch\(\(\) => props\.readOnly,\s*applyReadOnly\)') { throw 'MAJOR-3：watch(readOnly) 不在位（F2 修复缺失）' }
    if ($sg -notmatch 'isProtected = props\.readOnly') { throw 'MAJOR-3：isProtected 由 readOnly 驱动的赋值不在位' }
    Ok 'MAJOR-3 SpreadGrid 只读联动在位（watch(readOnly)→applyReadOnly→isProtected）'
} catch { No 'MAJOR-3' $_.Exception.Message }

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
Say (" 结果：PASS={0}  FAIL={1}  （主用例计数，含 TC1–TC22、TC23、GF1–GF6（GF6 需 -EnableGf6）、E2E-CTX-1..4 与批① AIE2E 131 条 + V1 34 条 + V2 4 条；基数不写死，随用例演进）" -f $script:passed, $script:failed)
# 批① 门禁口径（设计稿 §4.2 第 5 项）：P0/P1 红 ⇒ exit 1；P2 红单列不阻塞（防一门禁把开发卡死）
$aiP2Fail = [int]$script:aiP2Fail
Say (" 批① AIE2E：PASS={0}  FAIL={1}（其中 P2 红 {2} 条，不阻塞退出码）" -f $script:aiPass, ($script:aiExecuted - $script:aiPass), $aiP2Fail)
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
if ($script:gfskip14 -ge 4) { exit 1 }
# 批① 门禁：P0/P1 任一红 ⇒ 1；P2 红只计数不阻塞（§4.2 第 5 项）
if (($script:failed - $aiP2Fail) -gt 0) { exit 1 }
if ([int]$script:aiP0Fail -gt 0 -or [int]$script:aiP1Fail -gt 0) { exit 1 }
