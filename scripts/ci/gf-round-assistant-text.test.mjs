#!/usr/bin/env node
/**
 * `Gf-RoundAssistantText` 的轮次作用域回归（零第三方依赖，`node --test` 运行）。
 *
 * 背景（2026-10-31 全量审查）：该函数决定"GF 值回显断言取哪一段助手文本"。旧实现在**本轮尚无助手
 * 文本**时遇到非透明工具结果会 `continue` 继续往下扫 —— 于是这一轮其实没产出回显时，它会穿过别的
 * 工具轮次，把**后一轮**的文本当成这一次回灌的结果交出去，`Gf-Assert-Echo` 因此可能对着错文本过闸
 * （假绿）；反过来也可能因为错配而假红。修法：**只许同批并行** —— 只有"同一条 assistant 的
 * `toolCalls`"里的兄弟结果允许跳过，其余任何工具结果都结束作用域。
 *
 * 本回归不做实现改写：它从**实际的 scripts/verify-e2e.ps1** 里按 AST 取出 `Gf-RoundAssistantText`
 * 的原文，用合成历史逐场景跑（`GetJson` 被替换成"返回这份历史"），只判**外部可观察结论**：
 * 拿到哪段文本 / 是否如实失败。五个场景：
 *   ① 同批并行兄弟结果 ⇒ 允许跳过，收到本轮文本；
 *   ② **反例**：本轮无助手文本 + 孤儿工具结果 + 后一轮文本 ⇒ 必须失败（拒绝借位）；
 *   ③ 宿主条件表管道（透明）⇒ 允许穿过，收到续答文本；
 *   ④ 同一份历史**不声明透明** ⇒ 必须失败（透明是显式标注，不得默认放行）；
 *   ⑤ 新 USER 边界 ⇒ 必须失败。
 *
 * 需要 PowerShell（CI 的 ubuntu 镜像自带 pwsh，Windows 走 powershell.exe）；都没有时 skip。
 */
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const PS1 = path.join(REPO, 'scripts', 'verify-e2e.ps1')

/** 找 PowerShell：优先 pwsh（CI/跨平台），退 powershell.exe（Windows）。都没有则跳过。 */
function powershell() {
  for (const candidate of process.platform === 'win32'
    ? ['powershell.exe', 'pwsh', 'pwsh.exe']
    : ['pwsh', 'powershell']) {
    const probe = spawnSync(candidate, ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.Major'], {
      encoding: 'utf8'
    })
    if (probe.status === 0) return candidate
  }
  return null
}

const ps = powershell()

/** 合成历史的简写：user / asst(文本, toolCalls) / tool(结果 id)。 */
const user = (text) => ({ type: 'USER', text })
const asst = (text, calls = []) => ({
  type: 'ASSISTANT',
  text,
  toolCalls: calls.map((id) => ({ id, name: 'generative_form' }))
})
const tool = (...ids) => ({ type: 'TOOL', toolResponses: ids.map((id) => ({ id, name: 'generative_form' })) })

const GUARDED = '工具结果段：gate=before-execution'

const SCENARIOS = [
  {
    id: 'sibling-parallel-is-allowed',
    why: '同批并行：同一 assistant 的两个 toolCalls，兄弟结果仍属本次模型步',
    toolCallId: 'c1',
    transparent: [],
    messages: [
      user('请出 CLARIFY 表'),
      asst('', ['c1', 'c2']),
      tool('c1'),
      tool('c2'),
      asst('exportScope=ALL includeInactive=true')
    ],
    expect: { ok: true, contains: ['exportScope=ALL'] }
  },
  {
    id: 'orphan-tool-result-does-not-borrow-next-round-text',
    why: '反例：本轮没有助手文本，隔着一条孤儿工具结果后一轮才有文本 ⇒ 必须失败（禁止借位）',
    toolCallId: 'c1',
    transparent: [],
    messages: [
      user('请出 CLARIFY 表'),
      asst('', ['c1']),
      tool('c1'),
      // 载体被合并/裁剪掉的孤儿结果：不属于本次回灌那一批
      tool('c-other'),
      asst('这是后一轮的回答（不得借位）')
    ],
    expect: { ok: false, errorContains: '没有助手文本' }
  },
  {
    id: 'transparent-host-pipeline-is-crossed',
    why: '宿主条件表管道在轮次边界上透明：它的调用/结果由宿主挂起、驱动取消，模型续答在其后',
    toolCallId: 'c1',
    transparent: ['host-1'],
    messages: [
      user('请出 CLARIFY 表'),
      asst('', ['c1']),
      tool('c1'),
      asst('', ['host-1']),
      tool('host-1'),
      asst('exportScope=ALL includeInactive=true')
    ],
    expect: { ok: true, contains: ['exportScope=ALL'] }
  },
  {
    id: 'host-pipeline-without-transparency-is-rejected',
    why: '透明是显式标注：同一份历史不声明透明时，宿主表结果结束作用域（不得默认放行）',
    toolCallId: 'c1',
    transparent: [],
    messages: [
      user('请出 CLARIFY 表'),
      asst('', ['c1']),
      tool('c1'),
      asst('', ['host-1']),
      tool('host-1'),
      asst('exportScope=ALL includeInactive=true')
    ],
    expect: { ok: false, errorContains: '没有助手文本' }
  },
  {
    id: 'user-boundary-stops-scope',
    why: '新 USER 消息 = 新轮次：后面的助手文本不属于本次回灌',
    toolCallId: 'c1',
    transparent: [],
    messages: [
      user('请出 CLARIFY 表'),
      asst('', ['c1']),
      tool('c1'),
      user('再问一句别的'),
      asst('后一轮回答')
    ],
    expect: { ok: false, errorContains: '没有助手文本' }
  }
]

/** 在 PowerShell 里跑"真实的 Gf-RoundAssistantText × 合成历史"，返回每个场景的结论。 */
function runScenarios() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gf-round-scope-'))
  const scenFile = path.join(dir, 'scenarios.json')
  const outFile = path.join(dir, 'result.json')
  fs.writeFileSync(scenFile, JSON.stringify(SCENARIOS), 'utf8')

  const script = `
$ErrorActionPreference = 'Stop'
$path = ${JSON.stringify(PS1)}
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw ("verify-e2e.ps1 解析失败：" + $errors[0].Message) }
$fn = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Gf-RoundAssistantText' }, $true) | Select-Object -First 1
if (-not $fn) { throw '找不到 Gf-RoundAssistantText（函数被改名 = 本回归失效，需同步）' }
. ([scriptblock]::Create($fn.Extent.Text))
$script:hist = $null
function GetJson($p) { return $script:hist }
$scenarios = Get-Content -Raw -Encoding UTF8 ${JSON.stringify(scenFile)} | ConvertFrom-Json
$out = @()
foreach ($s in $scenarios) {
    $script:hist = [pscustomobject]@{ messages = @($s.messages) }
    try {
        $text = Gf-RoundAssistantText 'sid-shot' $s.toolCallId @($s.transparent)
        $out += [pscustomobject]@{ id = $s.id; ok = $true; text = [string]$text; error = '' }
    }
    catch {
        $out += [pscustomobject]@{ id = $s.id; ok = $false; text = ''; error = $_.Exception.Message }
    }
}
[IO.File]::WriteAllText(${JSON.stringify(outFile)}, ($out | ConvertTo-Json -Depth 6 -Compress), (New-Object System.Text.UTF8Encoding($false)))
`
  try {
    const result = spawnSync(ps, ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script], {
      encoding: 'utf8',
      timeout: 120_000
    })
    assert.equal(result.status, 0, `PowerShell 场景执行失败：${result.stderr || result.stdout}`)
    return JSON.parse(fs.readFileSync(outFile, 'utf8'))
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

test('Gf-RoundAssistantText 只许同批并行、拒绝跨轮借位', { skip: ps === null && '本机无 PowerShell（pwsh/powershell.exe）' }, () => {
  const results = runScenarios()
  assert.equal(results.length, SCENARIOS.length, `场景数不一致：${results.length} vs ${SCENARIOS.length}`)
  for (const scenario of SCENARIOS) {
    const got = results.find((item) => item.id === scenario.id)
    assert.ok(got, `缺场景结果：${scenario.id}`)
    if (scenario.expect.ok) {
      assert.equal(got.ok, true, `${scenario.id}（${scenario.why}）应拿到本轮文本，实得错误：${got.error}`)
      for (const needle of scenario.expect.contains) {
        assert.ok(got.text.includes(needle), `${scenario.id}（${scenario.why}）文本缺 "${needle}"，实得：${got.text}`)
      }
    } else {
      assert.equal(got.ok, false, `${scenario.id}（${scenario.why}）必须失败，实得文本：${got.text}`)
      assert.ok(
        got.error.includes(scenario.expect.errorContains),
        `${scenario.id} 失败原因应含 "${scenario.expect.errorContains}"，实得：${got.error}`
      )
    }
  }
  // 反例的"负向"价值：借位一旦复现，②④ 会拿到后一轮文本而不是失败 —— 显式点名，便于排障
  assert.ok(
    !results.some((item) => item.ok && String(item.text).includes(GUARDED)),
    '合成历史里不该有受控标记串被读出（测试自身写错）'
  )
})
