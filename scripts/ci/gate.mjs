#!/usr/bin/env node
/**
 * gate.mjs —— 本地两级门禁统一入口（C 期，设计稿 §5.1 三层形态 / §7.3）。
 *
 *   node scripts/ci/gate.mjs --tier commit [--shadow] [--base <sha>] [--mvn <path>]
 *   node scripts/ci/gate.mjs --tier merge  [--base <sha>] [--mvn <path>]
 *
 * 两层（§5.1）：
 *   L1 commit（每笔提交）：**后端选择集**（`select-tests.mjs` 查倒排表）+ 前端全量 vitest
 *     + `vue-tsc --noEmit` + `check-docs` + i18n 残留（--strict）。目标 ≤ 3 min。
 *   L2 merge（入库前）：**与今天逐字相同**的全量口径 —— 后端全量默认 locale + 后端全量
 *     JVM en-US、前端全量 vitest、`vue-tsc`、`vite build`、`check-docs`、i18n 残留（--strict）。
 *     选择器**不参与**合流级（§5.1「合流不进选择集」，CI 全量是它的保险）。
 *
 * 三条硬约束的落位：
 *   - **O8（不稳定表自动降级）**：表缺失/过期/`deterministic=false` 且未并集修复 ⇒ 选择器自身
 *     输出 `ALL`；本脚本读清单判定后**走全量并打印告警**，不阻断提交（人工只在夜间处理）。
 *   - **O3/§7.3-③（影子期）**：`--shadow` 时选择集与全量**同跑**，选择集结果只报告不阻断，
 *     阻断口径仍是全量（= 今天）。对照结果（漏检集）追加到
 *     `backend/target/selection/shadow-ledger.json`，判据 = 漏检 ≤ 1 笔 / 300 笔。
 *   - **F13（locale/时区面）**：合流级恒跑 en-US，故 F13 命中时"强制 en-US"自动成立；
 *     本脚本仍会点名命中项，让"为什么这次必须跑 en-US"在日志里可查。
 *
 * 端口纪律：本脚本只跑单测/构建/静态检查，**不启停 8081 / 5202**，也不起桩或 E2E 实例
 * （合流级的 E2E 维持今天的做法：手动跑 `scripts/verify-e2e.ps1` 或由 CI 承担，见末尾提示）。
 *
 * 退出码：0 = 绿；1 = 红；3 = 环境/用法错误（mvn 缺失、表不可读等）。
 */

import { execFileSync, spawnSync } from 'node:child_process'
import {
  closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { staticFallbacks } from './select-tests.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')

const MAP = 'backend/target/testmap/map.json'
const INCLUDES = 'backend/target/selection/includes.txt'
const AUDIT = 'backend/target/testmap/selection/latest.json'
const LEDGER = 'backend/target/selection/shadow-ledger.json'
const SUREFIRE = 'backend/target/surefire-reports'

// §7.3-③：影子期判据（O4）= 漏检 ≤ 1 笔 / 300 笔。
const SHADOW_WINDOW = 300
const SHADOW_MAX_MISSED = 1

// ------------------------------------------------------------------ 参数

function parseArgs(argv) {
  const opts = { tier: null, base: null, shadow: false, mvn: null, help: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--tier') opts.tier = argv[++i]
    else if (a === '--base') opts.base = argv[++i]
    else if (a === '--shadow') opts.shadow = true
    else if (a === '--mvn') opts.mvn = argv[++i]
    else if (a === '--help' || a === '-h') opts.help = true
    else throw new Error(`未知参数：${a}`)
  }
  return opts
}

function usage() {
  const src = readFileSync(fileURLToPath(import.meta.url), 'utf8')
  console.log(src.slice(src.indexOf('/**'), src.indexOf('*/') + 2))
}

// ------------------------------------------------------------------ 基础工具

/** git 只取 stdout：stderr（CRLF 警告、`fatal:` 之类）不往门禁输出里灌；出错由调用方兜。 */
function git(args, cwd = REPO) {
  return execFileSync('git', args, {
    cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'],
  })
}

/**
 * 让 mvn 路径在**当前平台**上可执行（跨平台：Windows 本地跑 + ubuntu CI）。
 * - Git Bash 形态 `/<盘符>/…` → `<盘符>:\…`（cmd 不认 POSIX 路径）；
 * - Windows 上 `.../bin/mvn` 是 sh 脚本，cmd 只能执行 `mvn.cmd` ⇒ 无扩展名时探测 `.cmd`。
 */
function normalizeExe(p) {
  if (!p) return p
  if (process.platform !== 'win32') return p
  let out = p
  const m = /^\/([a-zA-Z])\//.exec(out)
  if (m) out = `${m[1].toUpperCase()}:\\${out.slice(3).replace(/\//g, '\\')}`
  if (!path.extname(out) && existsSync(`${out}.cmd`)) out = `${out}.cmd`
  return out
}

const quote = (s) => (/\s/.test(s) ? `"${s}"` : s)

/**
 * 本机常见的「mvn 不在 PATH 上」形态：IDE 自带的 maven（Windows）。
 * 只在 `--mvn` / `MVN` / PATH 都没有时兜底 —— IDEA 可能不在系统盘，故按盘符探（C~F），拿不到就返回 null。
 */
function bundledMaven() {
  if (process.platform !== 'win32') return null
  const roots = [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.ProgramW6432]
    .filter(Boolean)
  for (const drive of ['C', 'D', 'E', 'F']) roots.push(`${drive}:\\Program Files`, `${drive}:\\`)
  const seen = new Set()
  for (const root of roots) {
    if (seen.has(root)) continue
    seen.add(root)
    const jb = path.join(root, 'JetBrains')
    if (!existsSync(jb)) continue
    for (const dir of readdirSync(jb)) {
      const cand = path.join(jb, dir, 'plugins', 'maven-plugin', 'lib', 'maven3', 'bin', 'mvn.cmd')
      if (existsSync(cand)) return cand
    }
  }
  return null
}

function resolveMvn(explicit) {
  const fromEnv = explicit || process.env.MVN
  if (fromEnv) return normalizeExe(fromEnv)
  const probe = spawnSync('mvn -v', { shell: true, stdio: 'ignore' })
  if (probe.status === 0) return 'mvn'
  const bundled = bundledMaven()
  return bundled ? normalizeExe(bundled) : null
}

const fmt = (s) => `${s.toFixed(1)}s`

function rel(p) {
  return path.relative(REPO, p).split(path.sep).join('/')
}

function clearSurefire() {
  rmSync(path.join(REPO, SUREFIRE), { recursive: true, force: true })
}

// ------------------------------------------------------------------ 执行

const LOG_ROOT = path.join(REPO, 'backend', 'target', 'gate', new Date().toISOString().replace(/[:.]/g, '-'))
const steps = []

/**
 * 跑一条命令，输出全量落到日志文件（不进终端，避免把 Maven/vitest 的整屏灌进对话），
 * 失败时由调用方决定是否 tail。返回 { code, seconds, logFile }。
 */
function shell(name, command, cwd = REPO) {
  mkdirSync(LOG_ROOT, { recursive: true })
  const logFile = path.join(LOG_ROOT, `${name}.log`)
  const fd = openSync(logFile, 'w')
  const t0 = process.hrtime.bigint()
  const r = spawnSync(command, { cwd, shell: true, stdio: ['ignore', fd, fd], env: process.env })
  closeSync(fd)
  const seconds = Number(process.hrtime.bigint() - t0) / 1e9
  return { code: r.status ?? -1, seconds, logFile }
}

function tail(logFile, n = 30) {
  try {
    const lines = readFileSync(logFile, 'utf8').split('\n')
    console.log(lines.slice(-n).join('\n'))
  } catch { /* 无日志 */ }
}

/** 一步 = 一条命令 + 一行结果。note 供汇总表使用。 */
function step(name, command, { cwd = REPO, note = '' } = {}) {
  process.stdout.write(`[gate] ${name} … `)
  const r = shell(name, command, cwd)
  const ok = r.code === 0
  console.log(`${ok ? 'ok' : 'RED'} ${fmt(r.seconds)}${note ? `  ${note}` : ''}`)
  steps.push({ name, ...r, ok, note })
  if (!ok) tail(r.logFile)
  return { ...r, ok }
}

// ------------------------------------------------------------------ 后端

function mvnInBackend({ includes, enUS }) {
  const parts = [quote(MVN), '-o', '-B', 'test']
  if (includes) {
    parts.push(`-Dsurefire.includesFile=${includes}`)
    // §3.8：清单里若有陈旧条目（测试已删/改名），surefire 不得因此红。
    parts.push('-Dsurefire.failIfNoSpecifiedTests=false')
  }
  // L-61 口径：MAVEN_OPTS 只作用于 Maven 自身 JVM，surefire fork 只认 -DargLine。
  if (enUS) parts.push('-DargLine="-Duser.language=en -Duser.country=US"')
  return parts.join(' ')
}

/** 读 surefire XML（口径 = TEST-*.xml 求和，与看板既有门禁口径一致）。 */
function readSurefire() {
  const dir = path.join(REPO, SUREFIRE)
  const out = { tests: 0, failures: 0, errors: 0, skipped: 0, classes: 0, failed: [] }
  if (!existsSync(dir)) return out
  for (const f of readdirSync(dir)) {
    if (!f.startsWith('TEST-') || !f.endsWith('.xml')) continue
    const xml = readFileSync(path.join(dir, f), 'utf8')
    const head = /<testsuite\b[^>]*>/.exec(xml)
    if (!head) continue
    const attr = (n) => Number((new RegExp(`\\b${n}="(\\d+)"`).exec(head[0]) || [0, 0])[1])
    const cls = (/\bname="([^"]+)"/.exec(head[0]) || ['', f])[1]
    out.classes += 1
    out.tests += attr('tests')
    out.failures += attr('failures')
    out.errors += attr('errors')
    out.skipped += attr('skipped')
    for (const m of xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)) {
      if (/<(failure|error)\b/.test(m[2])) {
        const tn = (/\bname="([^"]*)"/.exec(m[1]) || ['', '(?)'])[1]
        out.failed.push(`${cls}#${tn}`)
      }
    }
  }
  return out
}

const surefireLine = (r) => `${r.tests} 例 / ${r.classes} 类 · ${r.failures}F/${r.errors}E/${r.skipped}S`

// ------------------------------------------------------------------ 选择集

function readIncludes() {
  if (!existsSync(path.join(REPO, INCLUDES))) return null
  const lines = readFileSync(path.join(REPO, INCLUDES), 'utf8').split('\n').map((s) => s.trim()).filter(Boolean)
  return { all: lines.includes('ALL'), tests: lines.filter((l) => l !== 'ALL') }
}

function readAudit() {
  try {
    return JSON.parse(readFileSync(path.join(REPO, AUDIT), 'utf8'))
  } catch {
    return null
  }
}

function changedPaths() {
  try {
    const text = git(['diff', '--name-status', '--find-renames', BASE])
    const out = []
    for (const line of text.split('\n')) {
      if (!line.trim()) continue
      const parts = line.split('\t')
      const status = parts[0][0]
      out.push({ status, path: status === 'R' || status === 'C' ? parts[2] : parts[1] })
    }
    return out
  } catch {
    return []
  }
}

// ------------------------------------------------------------------ 影子期

function loadLedger() {
  try {
    const data = JSON.parse(readFileSync(path.join(REPO, LEDGER), 'utf8'))
    if (Array.isArray(data.entries)) return data
  } catch { /* 首笔 */ }
  return { startedAt: new Date().toISOString(), runs: 0, missed: 0, entries: [] }
}

function saveLedger(ledger) {
  const dir = path.dirname(path.join(REPO, LEDGER))
  mkdirSync(dir, { recursive: true })
  // 只留最近 400 笔（> 判据窗口 300，滚动统计有余量）
  if (ledger.entries.length > 400) ledger.entries = ledger.entries.slice(-400)
  writeFileSync(path.join(REPO, LEDGER), `${JSON.stringify(ledger, null, 2)}\n`, 'utf8')
}

function shadowVerdict(ledger) {
  if (ledger.runs === 0) return '尚未有影子样本'
  const limit = Math.max(SHADOW_MAX_MISSED, Math.floor(ledger.runs / SHADOW_WINDOW))
  return ledger.missed <= limit ? `通过（漏检 ${ledger.missed}/${ledger.runs}）` : `未达（漏检 ${ledger.missed}/${ledger.runs}）`
}

// ------------------------------------------------------------------ 各层

function selectStep() {
  process.stdout.write('[gate] 选择集（select-tests.mjs）… ')
  const cmd = ['node scripts/ci/select-tests.mjs',
    `--base ${quote(BASE)}`, `--map ${MAP}`, `--out ${INCLUDES}`, `--audit ${AUDIT}`, '--quiet'].join(' ')
  const r = shell('select', cmd)
  const inc = readIncludes()
  const audit = readAudit()
  const selected = inc ? inc.tests.length : 0
  const all = !inc || inc.all
  console.log(`ok ${fmt(r.seconds)}  ${all ? 'ALL（退化全量）' : `${selected} 类`}`)
  steps.push({ name: 'select', ...r, ok: r.code === 0, note: all ? 'ALL' : `${selected} 类` })
  if (r.code !== 0) tail(r.logFile)

  if (all) {
    const reason = audit?.reason || '选择器不可用/清单缺失'
    const rules = (audit?.rulesHit || []).join(', ') || '-'
    console.log(`[gate] ⚠ O8 自动降级：后端本次走**全量**（原因：${reason}；规则：${rules}）`)
    console.log('[gate]   处置：不阻断提交；夜间 job 重建倒排表（§2.3），人工只在夜间处理。')
  } else {
    const hit = (audit?.rulesHit || []).join(', ')
    console.log(`[gate]   选中 ${selected} 类，冒烟 ${audit?.smoke?.length ?? '?'} 类，命中规则：${hit || '无（按倒排查表）'}`)
    printUnstableCost(audit)
  }
  return { all, selected, selectedClasses: inc?.tests ?? [], audit, code: r.code }
}

/**
 * L-101 的可视化半边：不稳定类数 + 预估成本，直接打进提交级门禁输出。
 * 判据口径 = 看板 L-101（一次高负载录制 33 类 ⇒ 提交级 107s 涨到 187s），超过冒烟成本 30% 点名告警。
 */
function printUnstableCost(audit) {
  const unstable = audit?.unstable || []
  if (!unstable.length) return
  const cost = audit?.costEstimate || {}
  const u = cost.unstable || {}
  const s = cost.smoke || {}
  const sec = (x) => (x === null || x === undefined ? '?' : `${x}s`)
  const share = s.share
    ? `，占冒烟成本 ${Math.min(100, Math.round(((u.share || 0) / Math.max(s.share, u.share || 0)) * 100))}%`
    : ''
  const policy = audit?.unstablePolicy
  console.log(`[gate]   ⚠ unstable 拖慢（L-101）：${unstable.length} 类 ≈ ${sec(u.estimatedSeconds)}${share}` +
    `；冒烟合计 ${s.classCount ?? '?'} 类 ≈ ${sec(s.estimatedSeconds)}`)
  if (policy) {
    console.log(`[gate]     策略：连续 ${policy.cleanRuns} 次夜间录制无差异即移出冒烟集，或距最后复现超 ${policy.maxAgeDays} 天过期（录制器侧执行）`)
  }
}

function commitTier() {
  const sel = selectStep()
  let red = sel.code !== 0

  if (opts.shadow && !sel.all) {
    // ---- 影子期：选择集先跑（只报告），全量后跑（阻断口径 = 今天）
    clearSurefire()
    const s = step('后端选择集（影子·只报告）', mvnInBackend({ includes: `target/selection/includes.txt` }), { cwd: path.join(REPO, 'backend') })
    const selRes = readSurefire()
    // 影子期内选择集结果**不计入结论**：汇总表里标出来，避免"步骤 RED 但 tier 绿"被误读。
    steps[steps.length - 1].note = `${surefireLine(selRes)}（影子·不计入结论）`
    if (!s.ok) console.log(`[gate] 选择集跑红 ${surefireLine(selRes)}（影子期内**不阻断**，以全量为准）`)

    clearSurefire()
    const f = step('后端全量（影子对照·阻断）', mvnInBackend({}), { cwd: path.join(REPO, 'backend') })
    const fullRes = readSurefire()
    steps[steps.length - 1].note = `${surefireLine(fullRes)}（全量·阻断口径）`
    compareShadow(sel, selRes, fullRes)

    // 全量红 ⇒ 门禁红（今天的口径不变）
    if (!f.ok || fullRes.failures + fullRes.errors > 0) red = true
  } else if (!red) {
    clearSurefire()
    const includes = sel.all ? null : 'target/selection/includes.txt'
    const note = sel.all ? '（O8 降级 · 全量）' : `（选择集 ${sel.selected} 类）`
    const r = step('后端（选择集或全量）', mvnInBackend({ includes }), { cwd: path.join(REPO, 'backend'), note })
    const res = readSurefire()
    console.log(`[gate]   ${surefireLine(res)}`)
    steps[steps.length - 1].note = `${surefireLine(res)}${note}`
    if (!r.ok || res.failures + res.errors > 0) {
      if (!sel.all) console.log('[gate]   提示：后端红在**选择集**内。若疑漏测，跑 --shadow 同跑全量取证（§7.3-③）。')
      red = true
    }
    if (!opts.shadow) printShadowStatus()
    else console.log('[gate] [影子] 选择集=全量（O8 降级），本笔无对照样本 ⇒ 不计入影子窗口。')
  }

  const restRed = staticAndFrontend({ withBuild: false })
  return red || restRed ? 1 : 0
}

/** 影子对照：漏检 = 全量红、而选择集没跑或跑绿的用例。选择集红**不计**入本层结果。 */
function compareShadow(sel, selRes, fullRes) {
  const selFailed = new Set(selRes.failed)
  const fullFailed = fullRes.failed
  const missed = fullFailed.filter((t) => !selFailed.has(t))
  const notSelected = missed.filter((t) => !sel.selectedClasses.includes(t.split('#')[0]))
  const extra = [...selFailed].filter((t) => !fullFailed.includes(t))

  console.log(`[gate] [影子] 选择集：${sel.selected} 类 · ${surefireLine(selRes)}`)
  console.log(`[gate] [影子] 全量　：${surefireLine(fullRes)}`)
  console.log(`[gate] [影子] 漏检 missed=${missed.length}（其中未选中 ${notSelected.length} / 选中但绿 ${missed.length - notSelected.length}）`)
  if (missed.length) {
    console.log('[gate] [影子] 漏检清单（须逐笔根因归类：反射/夹具/状态/时区/超限/其他，并回填兜底规则或冒烟集）：')
    for (const t of missed.slice(0, 40)) console.log(`[gate]   - ${t}${sel.selectedClasses.includes(t.split('#')[0]) ? '' : '（未选中）'}`)
  }
  if (extra.length) {
    console.log(`[gate] [影子] 选择集多红 ${extra.length} 条（全量绿）⇒ 疑似 flake 或选择集噪声：${extra.slice(0, 10).join(', ')}`)
  }
  recordShadow(sel, selRes, { ...fullRes, missed })
  return []
}

function recordShadow(sel, selRes, fullInfo) {
  const ledger = loadLedger()
  ledger.runs += 1
  const missed = fullInfo?.missed?.length ?? 0
  ledger.missed += missed
  ledger.entries.push({
    at: new Date().toISOString(),
    treeSha: sel.audit?.treeSha ?? null,
    rulesHit: sel.audit?.rulesHit ?? [],
    selectedClasses: sel.selectedClasses,
    selection: selRes ? { tests: selRes.tests, failed: selRes.failed.length } : null,
    full: fullInfo ? { tests: fullInfo.tests, failed: fullInfo.failed.length } : null,
    missed: fullInfo?.missed ?? [],
    // §7.3-③：每一笔漏必须人工填根因归类（反射 / 夹具 / 状态 / 时区 / 超限 / 其他）并回填
    // 兜底规则或冒烟集；回填后重新起算窗口（清空 entries 重记）。
    rootCauses: [],
  })
  saveLedger(ledger)
  console.log(`[gate] [影子] 窗口累计：${ledger.runs} 笔 · 漏检 ${ledger.missed} 笔 ⇒ 判据（≤${SHADOW_MAX_MISSED}/${SHADOW_WINDOW}）${shadowVerdict(ledger)}`)
  console.log(`[gate] [影子] 台账：${LEDGER}`)
}

function printShadowStatus() {
  const ledger = loadLedger()
  console.log(`[gate] [影子] 本笔未同跑全量（未传 --shadow）；窗口累计 ${ledger.runs} 笔 · 漏检 ${ledger.missed} 笔 ⇒ ${shadowVerdict(ledger)}`)
  console.log('[gate] [影子] 判据（§7.3-③ / O4）：漏检 ≤ 1 笔 / 300 笔；每笔漏必须根因归类并回填兜底规则或冒烟集。')
}

function mergeTier() {
  // §5.1 L2：与今天逐字相同（选择器不参与）。F13 命中时点名（en-US 本就恒跑）。
  const hits = staticFallbacks(changedPaths())
  const f13 = hits.filter((h) => h.rule === 'F13')
  if (f13.length) {
    console.log(`[gate] F13 命中（locale/时区面）：${f13[0].reason}`)
    console.log('[gate]   合流级恒跑 en-US 全量 ⇒ 「F13 强制 en-US」自动成立（不额外加步骤）。')
  }

  let red = false
  clearSurefire()
  const a = step('后端全量（默认 locale）', mvnInBackend({}), { cwd: path.join(REPO, 'backend') })
  const ra = readSurefire()
  console.log(`[gate]   ${surefireLine(ra)}`)
  steps[steps.length - 1].note = surefireLine(ra)
  if (!a.ok || ra.failures + ra.errors > 0) red = true

  clearSurefire()
  const b = step('后端全量（JVM en-US）', mvnInBackend({ enUS: true }), { cwd: path.join(REPO, 'backend') })
  const rb = readSurefire()
  console.log(`[gate]   ${surefireLine(rb)}`)
  steps[steps.length - 1].note = surefireLine(rb)
  if (!b.ok || rb.failures + rb.errors > 0) red = true

  if (staticAndFrontend({ withBuild: true })) red = true
  return red ? 1 : 0
}

/** L1 也跑这里：前端全量 + typecheck（build 只在合流级）。 */
function staticAndFrontend({ withBuild }) {
  let red = false
  const frontend = path.join(REPO, 'frontend')

  const t = step('前端单测（全量 vitest）', 'yarn test', { cwd: frontend })
  const vitestCount = countVitest(t.logFile)
  if (vitestCount) steps[steps.length - 1].note = vitestCount
  if (!t.ok) red = true

  const tc = step('前端类型检查（vue-tsc --noEmit）', 'yarn typecheck', { cwd: frontend })
  if (!tc.ok) red = true

  if (withBuild) {
    const bd = step('前端构建（vite build）', 'yarn build', { cwd: frontend })
    if (!bd.ok) red = true
  }

  const docs = step('文档一致性门禁（check-docs）', 'node scripts/ci/check-docs.mjs')
  if (!docs.ok) red = true

  const i18n = step('i18n 残留（--strict 棘轮）', 'node scripts/ci/check-i18n-residue.mjs --strict')
  if (!i18n.ok) red = true

  return red
}

function countVitest(logFile) {
  try {
    const text = readFileSync(logFile, 'utf8')
    const m = /Test Files\s+(\d+) passed[^\n]*\n[\s\S]{0,200}?Tests\s+(\d+) passed[^\n]*/.exec(text)
      || /Tests\s+(\d+) passed[^\n]*/.exec(text)
    if (!m) return ''
    return m.length > 2 ? `${m[1]} 文件 · ${m[2]} 例通过` : `${m[1]} 例通过`
  } catch {
    return ''
  }
}

// ------------------------------------------------------------------ main

let opts
let BASE
let MVN

function summary(tier, code, totalSeconds) {
  console.log('')
  console.log(`[gate] ===== tier=${tier} 结果=${code === 0 ? '绿' : '红'} 总耗时=${fmt(totalSeconds)} =====`)
  for (const s of steps) {
    console.log(`[gate]   ${s.name.padEnd(34)} ${fmt(s.seconds).padStart(7)}  ${s.ok ? 'ok' : 'RED'}  ${s.note || ''}`)
  }
  console.log(`[gate]   日志：${rel(LOG_ROOT)}/`)
  if (tier === 'commit') {
    console.log('[gate]   合流前仍须跑 --tier merge（全量双 locale + build）；E2E 按今天做法手动/CI 跑。')
  } else {
    console.log('[gate]   E2E 未自动跑（沿用今天：合流前手动 scripts/verify-e2e.ps1，或由 CI e2e job 承担）。')
  }
}

function main() {
  opts = parseArgs(process.argv.slice(2))
  if (opts.help) { usage(); return 0 }
  if (!['commit', 'merge'].includes(opts.tier)) {
    console.error('[gate] 必须给 --tier commit|merge（--help 看用法）')
    return 3
  }
  MVN = resolveMvn(opts.mvn)
  if (!MVN) {
    console.error('[gate] 找不到 mvn：请把路径传给 --mvn <path> 或设环境变量 MVN=<path to mvn|mvn.cmd>')
    return 3
  }
  console.log(`[gate] tier=${opts.tier}${opts.shadow ? '（影子模式）' : ''}`)
  console.log(`[gate] mvn=${MVN}`)
  try {
    BASE = opts.base || git(['merge-base', 'origin/main', 'HEAD']).trim()
  } catch {
    console.error('[gate] 取不到 diff 基准（仓库没有 origin/main：浅克隆/新仓）。请显式给 --base <sha>')
    return 3
  }
  console.log(`[gate] base=${BASE}`)

  const t0 = Date.now()
  const code = opts.tier === 'commit' ? commitTier() : mergeTier()
  const total = (Date.now() - t0) / 1000
  summary(opts.tier, code, total)
  return code
}

try {
  process.exitCode = main()
} catch (e) {
  console.error(`[gate] 失败：${e.message}`)
  process.exitCode = 3
}
