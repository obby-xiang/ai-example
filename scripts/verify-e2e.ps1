# ============================================================
# E2E 验证脚本：动态配置管理系统 全流程测试
# 用法：pwsh -File scripts/verify-e2e.ps1 [-Base http://127.0.0.1:18080]
# 覆盖：配置定义动态建模/数据校验/导出(条件+进度+打包)/模板/导入(匹配+检查+依赖+草稿+发布)
#      /AI 对话(流式+工具+渐进披露+HITL 确认)/SSE 进度
# ============================================================
param([string]$Base = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$script:passed = 0
$script:failed = 0
$script:results = New-Object System.Collections.ArrayList

function Say($msg) { Write-Host $msg }
function Ok($name) { $script:passed++; $script:results.Add(@{ name = $name; result = 'PASS' }) | Out-Null; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
function No($name, $why) { $script:failed++; $script:results.Add(@{ name = $name; result = "FAIL: $why" }) | Out-Null; Write-Host ("  [FAIL] " + $name + " => " + $why) -ForegroundColor Red }

# ---------- HTTP 辅助 ----------
Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMinutes(5)

function GetJson($path) {
    $resp = $http.GetAsync($Base + $path).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    if (-not $resp.IsSuccessStatusCode -or $obj.code -ne 0) { throw "GET $path => HTTP $($resp.StatusCode) $txt" }
    return $obj.data
}

function PostJson($path, $body) {
    $json = ($body | ConvertTo-Json -Depth 20 -Compress)
    $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $resp = $http.PostAsync($Base + $path, $content).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    if (-not $resp.IsSuccessStatusCode) { throw "POST $path => HTTP $($resp.StatusCode) $txt" }
    if ($obj.code -ne 0) { throw "POST $path => " + $obj.msg }
    return $obj.data
}

function PutJson($path, $body) {
    $json = ($body | ConvertTo-Json -Depth 20 -Compress)
    $content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    $resp = $http.PutAsync($Base + $path, $content).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    if (-not $resp.IsSuccessStatusCode -or $obj.code -ne 0) { throw "PUT $path => HTTP $($resp.StatusCode) $txt" }
    return $obj.data
}

function DeleteJson($path) {
    $resp = $http.DeleteAsync($Base + $path).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    if (-not $resp.IsSuccessStatusCode -or $obj.code -ne 0) { throw "DELETE $path => HTTP $($resp.StatusCode) $txt" }
}

function GetBytes($path) {
    $resp = $http.GetAsync($Base + $path).Result
    if (-not $resp.IsSuccessStatusCode) { throw "GET $path => HTTP $($resp.StatusCode)" }
    return $resp.Content.ReadAsByteArrayAsync().Result
}

function Wait-Task($path, $terminal) {
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 300
        $snap = GetJson $path
        if ($terminal -contains $snap.status) { return $snap }
    }
    throw "任务超时未结束: $path"
}

# SSE 读取（HttpClient 流式；bodyObj 为 null 时用 GET）
function Read-Sse($path, $bodyObj, $eventHandler) {
    $req = New-Object System.Net.Http.HttpRequestMessage
    $req.RequestUri = New-Object System.Uri($Base + $path)
    if ($bodyObj) {
        $req.Method = [System.Net.Http.HttpMethod]::Post
        $json = ($bodyObj | ConvertTo-Json -Depth 20 -Compress)
        $req.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    } else {
        $req.Method = [System.Net.Http.HttpMethod]::Get
    }
    $resp = $http.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    if (-not $resp.IsSuccessStatusCode) { throw "SSE $path => HTTP $($resp.StatusCode)" }
    $stream = $resp.Content.ReadAsStreamAsync().Result
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $event = 'message'
    while ($true) {
        $line = $reader.ReadLine()
        if ($null -eq $line) { break }
        if ($line.StartsWith('event:')) { $event = $line.Substring(6).Trim() }
        elseif ($line.StartsWith('data:')) {
            $data = $line.Substring(5).Trim()
            if ($data -and $data -ne '[DONE]') {
                try { $payload = $data | ConvertFrom-Json } catch { $payload = $data }
                $eventHandler.Invoke($event, $payload)
            }
            $event = 'message'
        }
    }
    $reader.Dispose()
}

# multipart 上传
function Upload-File($path, $files) {
    $content = New-Object System.Net.Http.MultipartFormDataContent
    foreach ($f in $files) {
        $bytes = [IO.File]::ReadAllBytes($f)
        $byteContent = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$bytes)
        $byteContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/octet-stream')
        $content.Add($byteContent, 'files', [IO.Path]::GetFileName($f))
    }
    $resp = $http.PostAsync($Base + $path, $content).Result
    $txt = $resp.Content.ReadAsStringAsync().Result
    $obj = $txt | ConvertFrom-Json
    if (-not $resp.IsSuccessStatusCode -or $obj.code -ne 0) { throw "UPLOAD $path => $txt" }
    return $obj.data
}

# xlsx = zip；篡改 sharedStrings.xml 中的值（构造非法数据）。
# 通过“读全部条目→改写→新建 zip”的方式重建，避免原地 Update 破坏 OOXML 包结构。
function Corrupt-Xlsx($path, $old, $new) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outPath = "$path.tmp"
    if (Test-Path $outPath) { Remove-Item $outPath -Force }
    $src = [System.IO.Compression.ZipFile]::OpenRead($path)
    $dst = [System.IO.Compression.ZipFile]::Open($outPath, 'Create')
    foreach ($entry in $src.Entries) {
        $newEntry = $dst.CreateEntry($entry.FullName)
        $s = $entry.Open()
        $d = $newEntry.Open()
        if ($entry.FullName -eq 'xl/sharedStrings.xml') {
            $reader = New-Object System.IO.StreamReader($s, [System.Text.Encoding]::UTF8)
            $content = $reader.ReadToEnd()
            $reader.Close()
            $content2 = $content.Replace($old, $new)
            $writer = New-Object System.IO.StreamWriter($d, (New-Object System.Text.UTF8Encoding($false)))
            $writer.Write($content2)
            $writer.Flush()
            $writer.Close()
        } else {
            $s.CopyTo($d)
        }
        $s.Close()
        $d.Close()
    }
    $src.Dispose()
    $dst.Dispose()
    Move-Item $outPath $path -Force
}

# zip 包内容检查
function Zip-Entries($zipPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    $names = $zip.Entries | ForEach-Object { $_.FullName }
    $zip.Dispose()
    return $names
}

$tmp = Join-Path $env:TEMP ('cfg-e2e-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Say '============================================================'
Say ' E2E 验证开始  base=' + $Base + '  tmp=' + $tmp
Say '============================================================'

# ---- 预清理：删除可能残留的 E2E_TEMP 配置（幂等重跑） ----
try {
    GetJson '/api/defs/E2E_TEMP' | Out-Null
    foreach ($pub in @('true', 'false')) {
        $rows = GetJson "/api/data/rows?defCode=E2E_TEMP&published=$pub&page=1&size=1000"
        foreach ($r in $rows.rows) { DeleteJson ("/api/data/rows/" + $r.id) }
    }
    DeleteJson '/api/defs/E2E_TEMP'
    Say '--- 预清理：已删除残留 E2E_TEMP ---'
} catch { Say '--- 预清理：无残留 ---' }

# ---------------- TC1 配置定义与种子数据 ----------------
Say '--- TC1 配置定义列表与种子数据 ---'
try {
    $defs = GetJson '/api/defs'
    $map = @{}; $defs | ForEach-Object { $map[$_.code] = $_ }
    if ($map.Count -lt 4) { throw "定义数量不足: $($map.Count)" }
    if ($map['SERVER_PARAM'].level -ne 'GLOBAL') { throw 'SERVER_PARAM 层级错误' }
    if ($map['REGION_BILLING'].level -ne 'REGION') { throw 'REGION_BILLING 层级错误' }
    if ($map['PROJECT_QUOTA'].level -ne 'PROJECT') { throw 'PROJECT_QUOTA 层级错误' }
    if ($map['SERVER_PARAM'].publishedRowCount -ne 5) { throw "SERVER_PARAM 行数 $($map['SERVER_PARAM'].publishedRowCount)" }
    if (-not ($map['SERVER_EXTEND'].dependsOn -contains 'SERVER_PARAM')) { throw 'SERVER_EXTEND 缺少依赖' }
    $ref = $map['SERVER_EXTEND'].fields | Where-Object { $_.code -eq 'server_ref' }
    if ($ref.type -ne 'REFERENCE' -or $ref.refDefCode -ne 'SERVER_PARAM') { throw '引用字段定义错误' }
    Ok 'TC1 种子数据与层级/依赖/引用定义正确'
} catch { No 'TC1' $_.Exception.Message }

# ---------------- TC2 配置定义动态 CRUD ----------------
Say '--- TC2 动态配置定义 CRUD ---'
try {
    $newDef = @{
        code = 'E2E_TEMP'; name = 'E2E临时配置'; level = 'GLOBAL'; description = '自动化测试用'
        fields = @(
            @{ code = 'f_text'; label = '文本字段'; type = 'TEXT'; required = $true },
            @{ code = 'f_num'; label = '数字字段'; type = 'NUMBER'; required = $false; min = 1; max = 10 },
            @{ code = 'f_sel'; label = '下拉字段'; type = 'SELECT'; required = $false; options = @('甲', '乙', '丙') }
        )
        dependsOn = @(); sortOrder = 99; enabled = $true
    }
    $created = PostJson '/api/defs' $newDef
    if ($created.code -ne 'E2E_TEMP') { throw '创建失败' }
    # 校验：重复编码应失败
    $dupOk = $false
    try { PostJson '/api/defs' $newDef | Out-Null } catch { $dupOk = $true }
    if (-not $dupOk) { throw '重复编码未拦截' }
    # 更新：追加字段
    $newDef.fields += @{ code = 'f_date'; label = '日期字段'; type = 'DATE'; required = $false }
    $updated = PutJson '/api/defs/E2E_TEMP' $newDef
    if ($updated.fields.Count -ne 4) { throw '更新字段失败' }
    Ok 'TC2 定义创建/重复拦截/字段动态追加'
} catch { No 'TC2' $_.Exception.Message }

# ---------------- TC3 数据行 CRUD 与校验 ----------------
Say '--- TC3 数据行校验引擎 ---'
try {
    # 合法行
    PostJson '/api/data/rows?defCode=E2E_TEMP' @{ scope = $null; data = @{ f_text = 'ok'; f_num = 5; f_sel = '甲' }; published = $true } | Out-Null
    # 非法：必填缺失
    $bad1 = $false
    try { PostJson '/api/data/rows?defCode=E2E_TEMP' @{ scope = $null; data = @{ f_num = 5 }; published = $true } | Out-Null } catch { $bad1 = $true }
    if (-not $bad1) { throw '必填校验未拦截' }
    # 非法：选项越界
    $bad2 = $false
    try { PostJson '/api/data/rows?defCode=E2E_TEMP' @{ scope = $null; data = @{ f_text = 'ok'; f_sel = '丁' }; published = $true } | Out-Null } catch { $bad2 = $true }
    if (-not $bad2) { throw '下拉选项校验未拦截' }
    # 非法：数值范围
    $bad3 = $false
    try { PostJson '/api/data/rows?defCode=E2E_TEMP' @{ scope = $null; data = @{ f_text = 'ok'; f_num = 99 }; published = $true } | Out-Null } catch { $bad3 = $true }
    if (-not $bad3) { throw '数值范围校验未拦截' }
    # 非法：REGION 缺范围
    $bad4 = $false
    try { PostJson '/api/data/rows?defCode=REGION_BILLING' @{ scope = ''; data = @{ unit_price = 1; currency = 'CNY' }; published = $true } | Out-Null } catch { $bad4 = $true }
    if (-not $bad4) { throw '范围必填校验未拦截' }
    Ok 'TC3 类型/必填/选项/范围/数值校验全部生效'
} catch { No 'TC3' $_.Exception.Message }

# ---------------- TC4 导出：无条件全量 ----------------
Say '--- TC4 导出任务（全量 + 进度 + 文件） ---'
try {
    $t = PostJson '/api/export/tasks' @{ defCodes = @('SERVER_PARAM', 'REGION_BILLING', 'PROJECT_QUOTA', 'SERVER_EXTEND'); conditions = @{} }
    $taskId = $t.detail.id
    $snap = Wait-Task "/api/export/tasks/$taskId" @('SUCCESS', 'FAILED')
    if ($snap.status -ne 'SUCCESS') { throw "导出失败: $($snap.message)" }
    if ($snap.detail.files.Count -ne 4) { throw "文件数 $($snap.detail.files.Count)" }
    $sp = $snap.detail.files | Where-Object { $_.defCode -eq 'SERVER_PARAM' }
    if ($sp.rowCount -ne 5) { throw "SERVER_PARAM 导出行数 $($sp.rowCount)" }
    # 下载文件并校验为合法 xlsx (zip 魔数 PK)
    $bytes = GetBytes "/api/export/tasks/$taskId/files/SERVER_PARAM"
    if ($bytes.Length -lt 1000 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4B) { throw 'xlsx 魔数错误' }
    $spBytes = $bytes
    $script:exportTask4 = $taskId
    Ok 'TC4 全量导出 4 文件 + 行数正确 + 文件可下载'
} catch { No 'TC4' $_.Exception.Message }

# ---------------- TC5 导出：查询条件 ----------------
Say '--- TC5 导出查询条件（字段条件 + 范围条件） ---'
try {
    $body = @{ defCodes = @('SERVER_PARAM', 'REGION_BILLING')
        conditions = @{
            SERVER_PARAM = @{ env = @{ op = 'eq'; value = '生产' } }
            REGION_BILLING = @{ __scope__ = @{ op = 'eq'; value = '华东区' } }
        } }
    $t = PostJson '/api/export/tasks' $body
    $snap = Wait-Task "/api/export/tasks/$($t.detail.id)" @('SUCCESS', 'FAILED')
    $sp = $snap.detail.files | Where-Object { $_.defCode -eq 'SERVER_PARAM' }
    $rb = $snap.detail.files | Where-Object { $_.defCode -eq 'REGION_BILLING' }
    if ($sp.rowCount -ne 3) { throw "env=生产 应为 3 行，实际 $($sp.rowCount)" }
    if ($rb.rowCount -ne 1) { throw "华东区 应为 1 行，实际 $($rb.rowCount)" }
    $script:exportTask5 = $t.detail.id
    # 保存“生产=3行”文件，供后续发布替换测试
    # 保存“生产=3行”文件，供后续发布替换测试（TC13 会先写回 5 行文件，故这里直接存为匹配名）
    [IO.File]::WriteAllBytes("$tmp\SERVER_PARAM_prod3.xlsx", (GetBytes "/api/export/tasks/$($t.detail.id)/files/SERVER_PARAM"))
    Ok 'TC5 字段条件(env=生产→3行)与范围条件(华东区→1行)正确'
} catch { No 'TC5' $_.Exception.Message }

# ---------------- TC6 打包下载 ----------------
Say '--- TC6 zip 打包下载 ---'
try {
    $bytes = GetBytes "/api/export/tasks/$script:exportTask4/package?defCodes=SERVER_PARAM,REGION_BILLING"
    [IO.File]::WriteAllBytes("$tmp\pkg.zip", $bytes)
    $names = Zip-Entries "$tmp\pkg.zip"
    if (-not ($names -contains 'SERVER_PARAM.xlsx')) { throw '缺少 SERVER_PARAM.xlsx' }
    if (-not ($names -contains 'REGION_BILLING.xlsx')) { throw '缺少 REGION_BILLING.xlsx' }
    if ($names.Count -ne 2) { throw "条目数 $($names.Count)" }
    Ok 'TC6 打包下载条目正确（选定 2 个）'
} catch { No 'TC6' $_.Exception.Message }

# ---------------- TC7 模板下载 ----------------
Say '--- TC7 导入模板下载 ---'
try {
    $single = GetBytes '/api/import/templates?defCodes=SERVER_PARAM&zip=false'
    if ($single[0] -ne 0x50 -or $single[1] -ne 0x4B) { throw '单个模板非 xlsx' }
    [IO.File]::WriteAllBytes("$tmp\tpl_single.xlsx", $single)
    $zipped = GetBytes '/api/import/templates?defCodes=SERVER_PARAM,REGION_BILLING&zip=true'
    [IO.File]::WriteAllBytes("$tmp\tpl.zip", $zipped)
    $names = Zip-Entries "$tmp\tpl.zip"
    if (-not ($names -contains 'SERVER_PARAM.xlsx') -or -not ($names -contains 'REGION_BILLING.xlsx')) { throw '模板 zip 条目错误' }
    Ok 'TC7 模板单个 xlsx 与多配置 zip 下载正常'
} catch { No 'TC7' $_.Exception.Message }

# ---------------- TC8 导入批次与文件名匹配 ----------------
Say '--- TC8 批次创建 + 上传匹配（xlsx/zip/序号后缀/未匹配） ---'
try {
    $b = PostJson '/api/import/batches' @{ name = 'E2E-批次A'; defCodes = @('SERVER_PARAM', 'REGION_BILLING', 'PROJECT_QUOTA') }
    $bid = $b.detail.id
    # 上传 zip：正确名 + 未知名
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipPath = "$tmp\up1.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
    $e1 = $zip.CreateEntry('SERVER_PARAM.xlsx')
    $s1 = $e1.Open(); $b1 = [IO.File]::ReadAllBytes("$tmp\tpl_single.xlsx"); $s1.Write($b1, 0, $b1.Length); $s1.Close()
    $e2 = $zip.CreateEntry('UNKNOWN_CFG.xlsx')
    $s2 = $e2.Open(); $s2.Write($b1, 0, 10); $s2.Close()
    $zip.Dispose()
    $report = Upload-File "/api/import/batches/$bid/files" @($zipPath)
    if ($report.matched.Count -ne 1) { throw "zip 匹配数 $($report.matched.Count)" }
    if (-not ($report.unmatched -contains 'UNKNOWN_CFG.xlsx')) { throw '未匹配文件名未报告' }
    # 上传单文件（序号后缀匹配）
    Copy-Item "$tmp\tpl_single.xlsx" "$tmp\SERVER_PARAM(1).xlsx" -Force
    $report2 = Upload-File "/api/import/batches/$bid/files" @("$tmp\SERVER_PARAM(1).xlsx")
    if ($report2.matched.Count -ne 1 -or $report2.matched[0].defCode -ne 'SERVER_PARAM') { throw '序号后缀未匹配' }
    $script:batchA = $bid
    Ok 'TC8 文件名匹配（含(1)后缀）与未匹配报告正确'
} catch { No 'TC8' $_.Exception.Message }

# ---------------- TC9 检查：通过场景 ----------------
Say '--- TC9 检查（合法数据全部通过 + 依赖拓扑） ---'
try {
    # 用导出产物作为合法上传（闭环）：SERVER_PARAM 全量 + REGION_BILLING 全量 + PROJECT_QUOTA 全量
    foreach ($code in @('SERVER_PARAM', 'REGION_BILLING', 'PROJECT_QUOTA')) {
        $bytes = GetBytes "/api/export/tasks/$script:exportTask4/files/$code"
        [IO.File]::WriteAllBytes("$tmp\$code.xlsx", $bytes)
    }
    $report = Upload-File "/api/import/batches/$script:batchA/files" @("$tmp\SERVER_PARAM.xlsx", "$tmp\REGION_BILLING.xlsx", "$tmp\PROJECT_QUOTA.xlsx")
    if ($report.matched.Count -ne 3) { throw "上传匹配 $($report.matched.Count)" }
    PostJson "/api/import/batches/$script:batchA/check" @{} | Out-Null
    $snap = Wait-Task "/api/import/batches/$script:batchA" @('CHECKED', 'FAILED')
    if ($snap.status -ne 'CHECKED') { throw "状态 $($snap.status) $($snap.message)" }
    $bad = $snap.detail.files | Where-Object { $_.errorCount -gt 0 }
    if ($bad) { throw '存在错误文件: ' + ($bad.defCode -join ',') }
    # 依赖拓扑：批次 [SERVER_EXTEND, SERVER_PARAM] 应排序为 SERVER_PARAM 在前
    $b2 = PostJson '/api/import/batches' @{ name = 'E2E-批次B'; defCodes = @('SERVER_EXTEND', 'SERVER_PARAM') }
    $order = $b2.detail.order
    if ($order[0] -ne 'SERVER_PARAM' -or $order[1] -ne 'SERVER_EXTEND') { throw "拓扑顺序错误: $($order -join ',')" }
    $script:batchB = $b2.detail.id
    Ok 'TC9 检查通过 + 依赖拓扑排序（SERVER_PARAM 先于 SERVER_EXTEND）'
} catch { No 'TC9' $_.Exception.Message }

# ---------------- TC10 检查：失败场景（选项/引用/缺文件） ----------------
Say '--- TC10 检查失败明细（非法选项 + 引用不存在 + 未上传） ---'
try {
    # 10a: 非法选项（文件名必须匹配配置编码才能被上传识别）
    $bytes = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_PARAM"
    [IO.File]::WriteAllBytes("$tmp\SERVER_PARAM.xlsx", $bytes)
    Corrupt-Xlsx "$tmp\SERVER_PARAM.xlsx" '生产' '不存在的环境'
    $report = Upload-File "/api/import/batches/$script:batchA/files" @("$tmp\SERVER_PARAM.xlsx")
    PostJson "/api/import/batches/$script:batchA/check" @{} | Out-Null
    $snap = Wait-Task "/api/import/batches/$script:batchA" @('CHECKED', 'FAILED')
    $sp = $snap.detail.files | Where-Object { $_.defCode -eq 'SERVER_PARAM' }
    if ($sp.errorCount -lt 3) { throw "非法选项应报错≥3，实际 $($sp.errorCount)" }
    $issues = GetJson "/api/import/batches/$script:batchA/issues/SERVER_PARAM"
    if (-not ($issues | Where-Object { $_.message -like '*不在可选范围*' })) { throw '未发现选项错误明细' }
    # 10b: 引用不存在（SERVER_EXTEND 引用 SERVER_PARAM.server_name）
    $bytes2 = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_EXTEND"
    [IO.File]::WriteAllBytes("$tmp\SERVER_EXTEND.xlsx", $bytes2)
    Corrupt-Xlsx "$tmp\SERVER_EXTEND.xlsx" 'srv-app-01' 'srv-ghost-99'
    Upload-File "/api/import/batches/$script:batchB/files" @("$tmp\SERVER_EXTEND.xlsx") | Out-Null
    PostJson "/api/import/batches/$script:batchB/check" @{} | Out-Null
    $snapB = Wait-Task "/api/import/batches/$script:batchB" @('CHECKED', 'FAILED')
    $se = $snapB.detail.files | Where-Object { $_.defCode -eq 'SERVER_EXTEND' }
    if ($se.errorCount -lt 1) { throw '引用错误未拦截' }
    $issuesB = GetJson "/api/import/batches/$script:batchB/issues/SERVER_EXTEND"
    if (-not ($issuesB | Where-Object { $_.message -like '*不存在*' })) { throw '未发现引用错误明细' }
    # 10c: 未上传文件的配置 → ERROR
    $b3 = PostJson '/api/import/batches' @{ name = 'E2E-批次C'; defCodes = @('PROJECT_QUOTA') }
    PostJson "/api/import/batches/$($b3.detail.id)/check" @{} | Out-Null
    $snapC = Wait-Task "/api/import/batches/$($b3.detail.id)" @('CHECKED', 'FAILED')
    if ($snapC.detail.files[0].errorCount -lt 1) { throw '未上传文件未报错' }
    $script:batchC = $b3.detail.id
    Ok 'TC10 非法选项/引用不存在/未上传文件 三类错误均拦截并给出明细'
} catch { No 'TC10' $_.Exception.Message }

# ---------------- TC11 SSE 进度订阅 ----------------
Say '--- TC11 SSE 进度事件流 ---'
try {
    # 造 200 行数据使导出过程可观测
    for ($i = 1; $i -le 170; $i++) {
        PostJson '/api/data/rows?defCode=E2E_TEMP' @{ scope = $null; data = @{ f_text = "row$i"; f_num = ($i % 9 + 1); f_sel = '甲' }; published = $true } | Out-Null
    }
    $script:progressEvents = New-Object System.Collections.ArrayList
    $handler = {
        param($ev, $data)
        if ($ev -in @('progress', 'done', 'snapshot')) {
            $script:progressEvents.Add("$ev|$($data.progress)|$($data.status)") | Out-Null
        }
    }
    # 先创建任务再订阅（订阅时后端推快照，随后收到 progress/done 事件流）
    $t = PostJson '/api/export/tasks' @{ defCodes = @('E2E_TEMP'); conditions = @{} }
    Read-Sse "/api/export/tasks/$($t.detail.id)/events" $null $handler
    if (-not ($script:progressEvents | Where-Object { $_ -like 'progress|*' })) { throw "未收到 progress 事件（收到 $($script:progressEvents.Count) 条）" }
    if (-not ($script:progressEvents | Where-Object { $_ -like 'done|100|SUCCESS' })) { throw '未收到 done(SUCCESS) 事件' }
    Ok ('TC11 收到进度事件流，样例：' + (($script:progressEvents | Select-Object -First 3) -join ' ；'))
} catch { No 'TC11' $_.Exception.Message }

# ---------------- TC12 导入：草稿隔离 ----------------
Say '--- TC12 导入为草稿（不影响生效数据） ---'
try {
    # 批次A 修复 SERVER_PARAM 为合法全量文件后导入（REGION_BILLING/PROJECT_QUOTA 已合法）
    $bytes = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_PARAM"
    [IO.File]::WriteAllBytes("$tmp\SERVER_PARAM.xlsx", $bytes)
    Upload-File "/api/import/batches/$script:batchA/files" @("$tmp\SERVER_PARAM.xlsx") | Out-Null
    PostJson "/api/import/batches/$script:batchA/check" @{} | Out-Null
    Wait-Task "/api/import/batches/$script:batchA" @('CHECKED', 'FAILED') | Out-Null
    $before = GetJson '/api/defs/SERVER_PARAM'
    PostJson "/api/import/batches/$script:batchA/import" @{} | Out-Null
    $snap = Wait-Task "/api/import/batches/$script:batchA" @('IMPORTED', 'FAILED')
    if ($snap.status -ne 'IMPORTED') { throw "导入失败 $($snap.message)" }
    $after = GetJson '/api/defs/SERVER_PARAM'
    if ($after.publishedRowCount -ne $before.publishedRowCount) { throw '生效数据在导入后发生变化' }
    if ($after.draftRowCount -lt 5) { throw "草稿数异常 $($after.draftRowCount)" }
    # 草稿预览
    $drafts = GetJson "/api/import/batches/$script:batchA/drafts/SERVER_PARAM"
    if ($drafts.Count -ne 5) { throw "草稿预览行数 $($drafts.Count)" }
    Ok 'TC12 导入后为草稿，生效数据不变，草稿可预览'
} catch { No 'TC12' $_.Exception.Message }

# ---------------- TC13 发布：替换生效 ----------------
Say '--- TC13 发布（替换生效数据） ---'
try {
    PostJson "/api/import/batches/$script:batchA/publish" @{} | Out-Null
    $snap = Wait-Task "/api/import/batches/$script:batchA" @('PUBLISHED', 'FAILED')
    if ($snap.status -ne 'PUBLISHED') { throw "发布失败 $($snap.message)" }
    $def = GetJson '/api/defs/SERVER_PARAM'
    if ($def.publishedRowCount -ne 5) { throw "发布后行数 $($def.publishedRowCount)" }
    if ($def.draftRowCount -ne 0) { throw "发布后仍有草稿 $($def.draftRowCount)" }
    # 发布后禁止重复发布
    $dup = $false
    try { PostJson "/api/import/batches/$script:batchA/publish" @{} | Out-Null } catch { $dup = $true }
    if (-not $dup) { throw '重复发布未拦截' }
    # 用“生产=3行”文件再走一轮导入发布 → 生效数据替换为 3 行（文件名必须匹配配置编码）
    $b4 = PostJson '/api/import/batches' @{ name = 'E2E-批次D'; defCodes = @('SERVER_PARAM') }
    Copy-Item "$tmp\SERVER_PARAM_prod3.xlsx" "$tmp\SERVER_PARAM.xlsx" -Force
    $report4 = Upload-File "/api/import/batches/$($b4.detail.id)/files" @("$tmp\SERVER_PARAM.xlsx")
    if ($report4.matched.Count -ne 1) { throw "批次D上传未匹配: $($report4.matched.Count)" }
    PostJson "/api/import/batches/$($b4.detail.id)/check" @{} | Out-Null
    Wait-Task "/api/import/batches/$($b4.detail.id)" @('CHECKED', 'FAILED') | Out-Null
    PostJson "/api/import/batches/$($b4.detail.id)/import" @{} | Out-Null
    $snapD = Wait-Task "/api/import/batches/$($b4.detail.id)" @('IMPORTED', 'FAILED')
    if ($snapD.status -ne 'IMPORTED') { throw "批次D导入失败: $($snapD.message)" }
    PostJson "/api/import/batches/$($b4.detail.id)/publish" @{} | Out-Null
    Wait-Task "/api/import/batches/$($b4.detail.id)" @('PUBLISHED', 'FAILED') | Out-Null
    $def2 = GetJson '/api/defs/SERVER_PARAM'
    if ($def2.publishedRowCount -ne 3) { throw "替换后应为 3 行，实际 $($def2.publishedRowCount)" }
    $script:batchD = $b4.detail.id
    Ok 'TC13 发布替换生效（5行→发布3行文件→生效3行）+ 重复发布拦截'
} catch { No 'TC13' $_.Exception.Message }

# ---------------- TC14 发布检查失败保护 ----------------
Say '--- TC14 发布前最终检查失败保护 ---'
try {
    # 自建批次（未上传任何文件）：导入应整体失败，发布被拒
    $b14 = PostJson '/api/import/batches' @{ name = 'E2E-批次保护'; defCodes = @('PROJECT_QUOTA') }
    $bid14 = $b14.detail.id
    PostJson "/api/import/batches/$bid14/import" @{} | Out-Null
    $snap = Wait-Task "/api/import/batches/$bid14" @('FAILED', 'IMPORTED')
    if ($snap.status -ne 'FAILED') { throw '未上传文件批次不应导入成功' }
    $rej = $false
    try { PostJson "/api/import/batches/$bid14/publish" @{} | Out-Null } catch { $rej = $true }
    if (-not $rej) { throw '非 IMPORTED 批次发布未被拒' }
    Ok 'TC14 检查失败→导入失败；非已导入批次禁止发布'
} catch { No 'TC14' $_.Exception.Message }

# ---------------- TC15 AI：流式 + 工具 + 渐进披露 ----------------
Say '--- TC15 AI 对话（流式/工具调用/页面工具隔离） ---'
try {
    $session = 'e2e-' + [Guid]::NewGuid().ToString('N')
    $events = New-Object System.Collections.ArrayList
    $handler = {
        param($ev, $data)
        if ($ev -in @('delta', 'tool_start', 'tool_result', 'ui_event', 'done', 'error', 'reasoning')) {
            $script:aiEvents.Add("$ev|" + ($data | ConvertTo-Json -Depth 6 -Compress)) | Out-Null
        }
    }
    # 15a 列出配置（工具 list_config_defs）
    $script:aiEvents = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $session; message = '请列出系统中所有配置项'; context = @{ page = 'export' } } $handler
    if (-not ($script:aiEvents | Where-Object { $_ -like 'tool_start|*list_config_defs*' })) { throw '未调用 list_config_defs 工具' }
    if (-not ($script:aiEvents | Where-Object { $_ -like 'done|*' })) { throw '未收到 done' }
    # 15b 页面工具隔离（渐进式披露）
    $toolsExport = GetJson '/api/ai/tools?page=export'
    $toolsImport = GetJson '/api/ai/tools?page=import'
    $hasPublishInExport = @($toolsExport | Where-Object { $_.name -eq 'start_publish' }).Count -gt 0
    if ($hasPublishInExport) { throw '导出页不应披露 start_publish' }
    $hasStartExport = @($toolsExport | Where-Object { $_.name -eq 'start_export' }).Count -gt 0
    if (-not $hasStartExport) { throw '导出页缺少 start_export' }
    $hasPublishInImport = @($toolsImport | Where-Object { $_.name -eq 'start_publish' }).Count -gt 0
    if (-not $hasPublishInImport) { throw '导入页缺少 start_publish' }
    $hasCommon = @($toolsExport | Where-Object { $_.name -eq 'get_ui_state' }).Count -gt 0
    if (-not $hasCommon) { throw '通用工具未披露' }
    Ok 'TC15 流式输出+工具调用可见+按页面渐进式披露'
    $script:aiSession = $session
} catch { No 'TC15' $_.Exception.Message }

# ---------------- TC16 AI：ui_event 驱动业务（选配置+启动导出） ----------------
Say '--- TC16 AI 工具结果驱动工作区（ui_event） ---'
try {
    $script:aiEvents = New-Object System.Collections.ArrayList
    $handler = {
        param($ev, $data)
        if ($ev -in @('tool_start', 'tool_result', 'ui_event', 'done', 'error')) {
            $script:aiEvents.Add("$ev|" + ($data | ConvertTo-Json -Depth 6 -Compress)) | Out-Null
        }
    }
    Read-Sse '/api/ai/chat' @{ sessionId = $script:aiSession
        message = '帮我选择配置 SERVER_PARAM 并立即开始导出'
        context = @{ page = 'export'; export = @{ step = 1; selectedDefs = @(); conditions = @{} } } } $handler
    if (-not ($script:aiEvents | Where-Object { $_ -like 'ui_event|*select_defs*' })) { throw '未下发 select_defs ui_event' }
    if (-not ($script:aiEvents | Where-Object { $_ -like 'ui_event|*export_started*' })) { throw '未下发 export_started ui_event' }
    $list = GetJson '/api/export/tasks'
    if ($list.Count -lt 2) { throw 'AI 未实际创建导出任务' }
    Ok 'TC16 AI 经 ui_event 驱动导出向导（选配置+启动任务）'
} catch { No 'TC16' $_.Exception.Message }

# ---------------- TC17 AI：HITL 确认（发布需确认） ----------------
Say '--- TC17 AI 破坏性操作需确认（HITL） ---'
try {
    $script:aiEvents = New-Object System.Collections.ArrayList
    $handler = {
        param($ev, $data)
        if ($ev -in @('tool_start', 'tool_result', 'ui_event', 'done', 'error')) {
            $script:aiEvents.Add("$ev|" + ($data | ConvertTo-Json -Depth 6 -Compress)) | Out-Null
        }
    }
    # 批次D 已发布——新建一个可发布场景：先用批次B（SERVER_EXTEND+SERVER_PARAM）合法文件导入
    $bytesSp = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_PARAM"
    [IO.File]::WriteAllBytes("$tmp\SERVER_PARAM.xlsx", $bytesSp)
    $bytesSe = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_EXTEND"
    [IO.File]::WriteAllBytes("$tmp\SERVER_EXTEND.xlsx", $bytesSe)
    Upload-File "/api/import/batches/$script:batchB/files" @("$tmp\SERVER_PARAM.xlsx", "$tmp\SERVER_EXTEND.xlsx") | Out-Null
    PostJson "/api/import/batches/$script:batchB/check" @{} | Out-Null
    Wait-Task "/api/import/batches/$script:batchB" @('CHECKED', 'FAILED') | Out-Null
    PostJson "/api/import/batches/$script:batchB/import" @{} | Out-Null
    Wait-Task "/api/import/batches/$script:batchB" @('IMPORTED', 'FAILED') | Out-Null
    # AI 要求发布批次B
    Read-Sse '/api/ai/chat' @{ sessionId = $script:aiSession
        message = "发布导入批次 $($script:batchB)"
        context = @{ page = 'import'; import = @{ step = 3; selectedDefs = @(); batchId = $script:batchB } } } $handler
    if (-not ($script:aiEvents | Where-Object { $_ -like 'ui_event|*confirm_tool*' })) { throw '未收到 confirm_tool 确认事件' }
    # 拒绝 → 不发布
    $script:aiEvents = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/confirm' @{ sessionId = $script:aiSession; approved = $false } $handler
    $b = GetJson "/api/import/batches/$script:batchB"
    if ($b.status -eq 'PUBLISHED') { throw '拒绝后仍发布了' }
    # 重新请求并同意 → 发布
    $script:aiEvents = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/chat' @{ sessionId = $script:aiSession
        message = "发布导入批次 $($script:batchB)"
        context = @{ page = 'import'; import = @{ step = 3; selectedDefs = @(); batchId = $script:batchB } } } $handler
    if (-not ($script:aiEvents | Where-Object { $_ -like 'ui_event|*confirm_tool*' })) { throw '第二次未收到确认事件' }
    $script:aiEvents = New-Object System.Collections.ArrayList
    Read-Sse '/api/ai/confirm' @{ sessionId = $script:aiSession; approved = $true } $handler
    $b2 = Wait-Task "/api/import/batches/$script:batchB" @('PUBLISHED', 'FAILED')
    if ($b2.status -ne 'PUBLISHED') { throw '确认后未发布成功' }
    Ok 'TC17 HITL：发布需确认，拒绝不执行、确认后执行成功'
} catch { No 'TC17' $_.Exception.Message }

# ---------------- TC18 会话隔离与清空 ----------------
Say '--- TC18 页签会话隔离/清空 ---'
try {
    $c1 = GetJson '/api/ai/sessions/count'
    $s2 = 'e2e-' + [Guid]::NewGuid().ToString('N')
    $script:aiEvents = New-Object System.Collections.ArrayList
    $handler = { param($ev, $data) }
    Read-Sse '/api/ai/chat' @{ sessionId = $s2; message = '你好'; context = @{ page = 'export' } } $handler
    $c2 = GetJson '/api/ai/sessions/count'
    if ($c2.count -le $c1.count) { throw '会话数未增加' }
    PostJson '/api/ai/clear' @{ sessionId = $s2 } | Out-Null
    $c3 = GetJson '/api/ai/sessions/count'
    if ($c3.count -ge $c2.count) { throw '清空后会话数未减少' }
    Ok 'TC18 会话创建/计数/清空正常'
} catch { No 'TC18' $_.Exception.Message }

# ---------------- 清理临时配置 ----------------
Say '--- 清理：删除 E2E_TEMP 配置及其数据 ---'
try {
    $rows = GetJson '/api/data/rows?defCode=E2E_TEMP&published=true&page=1&size=1000'
    foreach ($r in $rows.rows) { DeleteJson ("/api/data/rows/" + $r.id) }
    DeleteJson '/api/defs/E2E_TEMP'
    # 恢复 SERVER_PARAM 演示数据为 5 行（再走一轮导入发布）
    $bytes = GetBytes "/api/export/tasks/$script:exportTask4/files/SERVER_PARAM"
    [IO.File]::WriteAllBytes("$tmp\SERVER_PARAM.xlsx", $bytes)
    $b5 = PostJson '/api/import/batches' @{ name = 'E2E-恢复批次'; defCodes = @('SERVER_PARAM') }
    Upload-File "/api/import/batches/$($b5.detail.id)/files" @("$tmp\SERVER_PARAM.xlsx") | Out-Null
    PostJson "/api/import/batches/$($b5.detail.id)/check" @{} | Out-Null
    Wait-Task "/api/import/batches/$($b5.detail.id)" @('CHECKED', 'FAILED') | Out-Null
    PostJson "/api/import/batches/$($b5.detail.id)/import" @{} | Out-Null
    Wait-Task "/api/import/batches/$($b5.detail.id)" @('IMPORTED', 'FAILED') | Out-Null
    PostJson "/api/import/batches/$($b5.detail.id)/publish" @{} | Out-Null
    Wait-Task "/api/import/batches/$($b5.detail.id)" @('PUBLISHED', 'FAILED') | Out-Null
    $def = GetJson '/api/defs/SERVER_PARAM'
    if ($def.publishedRowCount -ne 5) { throw "恢复后行数 $($def.publishedRowCount)" }
    Ok 'TC19 清理完成，演示数据恢复'
} catch { No 'TC19' $_.Exception.Message }

Say '============================================================'
Say " 结果：PASS=$script:passed  FAIL=$script:failed"
Say '============================================================'
if ($script:failed -gt 0) { exit 1 }
