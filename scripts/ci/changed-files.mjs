/**
 * changed-files.mjs —— 变更集分类器（A 期：CI 按变更集跳 job，设计稿 §5.2 / §7.1）
 *
 * 零第三方依赖：只用 Node 内置模块（与 `scripts/ci/check-docs.mjs`、`stub-upstream.mjs` 同口径）。
 * 用法：
 *   node scripts/ci/changed-files.mjs --base <sha|''> [--head <sha>]
 *                                     [--github-output <file>] [--summary-out <file>] [--json-out <file>]
 *   node scripts/ci/changed-files.mjs --diff-file <changed-paths.txt> [...]
 *   node scripts/ci/changed-files.mjs --self-check
 * 退出码：0 = 判定完成（含"基准不可用 ⇒ 全跑"——那是正常结论，不是错误）；
 *         1 = `--self-check` 断言失败（规则表/基准解析/输出协议漂移，CI 侧应视为红）。
 *   取 diff 失败/基准不可解析**不**退出非 0：改成"全跑"并打印 ::warning::，
 *   因为在本脚本的语义里"多跑"永远比"红掉门禁"安全。
 *
 * 职责边界（重要）：本脚本**只决定哪些 job 需要跑**，不决定跑什么用例 —— CI 里被选中的 job
 *   仍是全量（§5.1 L3）。选择集（跑哪些测试）是 B/C 期 `select-tests.mjs` 的职责。
 *
 * 三个面：
 *   - backend 面：backend/**、docs/kb-samples/**（构建期拷入 classpath，后端测试直接断言语料）、
 *     scripts/**、.github/workflows/**
 *   - frontend 面：frontend/**、scripts/**、.github/workflows/**
 *   - e2e 面：与 backend 面同源 —— `scripts/verify-e2e.ps1` 只打后端 HTTP（含 SSE）与打包 jar，
 *     不触前端产物；故前端改动不影响 E2E 的成败面。分离成独立输出是为了让 B/C 期能单独调整
 *     （例如 UI 层 E2E 接进本 workflow 时，e2e 面再加 frontend/**）。
 *
 * 兜底方向（§2.4 同口径）：**认不出的路径一律全跑**（fail-open）。漏跑的代价是"该跑没跑却报绿"，
 *   比多跑一个 job 贵得多。同理，基准 sha 不可解析/为全零/取 diff 失败 ⇒ 全跑并打印原因。
 *
 * `--self-check`：规则表与期望矩阵同源（下表的断言即是契约），漂移在这里先红，
 *   而不是等到某个 job 被静默跳过。CI 的 changes job 每次先跑它，再算变更集。
 */

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SELF = fileURLToPath(import.meta.url)
const REPO = path.resolve(HERE, '..', '..')

const argv = process.argv.slice(2)

function arg(name, fallback = '') {
  const i = argv.indexOf(`--${name}`)
  if (i < 0) return fallback
  // 允许显式传空串（`--base ""` 是 PR 上 `github.event.before` 未定义的真实形态），
  // 故不能用 `argv[i + 1] || fallback`。
  return i + 1 < argv.length ? argv[i + 1] : fallback
}

const flag = (name) => argv.includes(`--${name}`)

// ── 分类规则表（唯一取数点）──────────────────────────────────────────────────
// 顺序即优先级：先命中先归类（`docs/kb-samples/**` 必须先于 `docs/**`，
// 否则耦合项会被"纯文档"吃掉 —— 那是本文件最容易犯的错，--self-check ③ 专门盯它）。
// 每条规则只声明"命中后哪些面要跑"，未声明的面为 false。
const RULES = [
  {
    id: 'kb-samples',
    match: (p) => p.startsWith('docs/kb-samples/'),
    backend: true,
    e2e: true,
    why: '构建期由 maven-resources-plugin 拷入 classpath（KbSampleSeeder），KbSampleCorpusTest/SeedingTest 直接断言语料',
  },
  {
    id: 'backend',
    match: (p) => p.startsWith('backend/'),
    backend: true,
    e2e: true,
    why: 'backend/** 后端源码、资源、pom 与测试',
  },
  {
    id: 'frontend',
    match: (p) => p.startsWith('frontend/'),
    frontend: true,
    why: 'frontend/** 前端源码、语言包、依赖锁与构建配置（vite/vitest/tsconfig 均在此）',
  },
  {
    id: 'workflows',
    match: (p) => p.startsWith('.github/workflows/'),
    backend: true,
    frontend: true,
    e2e: true,
    why: 'workflow 自身改动 ⇒ 三面全跑（门禁改错必须立刻有作业能证伪）',
  },
  {
    id: 'scripts',
    match: (p) => p.startsWith('scripts/'),
    backend: true,
    frontend: true,
    e2e: true,
    why: 'scripts/** 被三个 job 直接调用（check-docs / stub-upstream / verify-e2e / githooks）',
  },
  {
    id: 'docs',
    match: (p) => p.startsWith('docs/') || /^[^/]+\.md$/.test(p),
    why: '仅文档面：docs job 无条件执行，代码 job 不因此跑（§5.2 的 A 期收益点）',
  },
]

const SURFACES = ['backend', 'frontend', 'e2e']

/** 纯函数：路径清单 → 三个面 + 命中规则 + 一行理由。不碰 git、不碰文件系统。 */
export function classify(files) {
  const hits = new Set()
  const perFile = []
  const surfaces = { backend: false, frontend: false, e2e: false }

  for (const p of files) {
    const rule = RULES.find((r) => r.match(p))
    if (!rule) {
      // fail-open：认不出的路径 = 可能影响任意面
      hits.add('unknown')
      for (const s of SURFACES) surfaces[s] = true
      perFile.push({ path: p, rule: 'unknown' })
      continue
    }
    hits.add(rule.id)
    for (const s of SURFACES) if (rule[s]) surfaces[s] = true
    perFile.push({ path: p, rule: rule.id })
  }

  const n = files.length
  let note
  if (n === 0) {
    note = 'empty change set (0 files)'
  } else if (!surfaces.backend && !surfaces.frontend && !surfaces.e2e) {
    note = `docs-only surface: all ${n} files fall under docs/** (excluding docs/kb-samples) or are root-level *.md`
  } else {
    note = `rules hit: ${[...hits].join(',')} (${n} files)`
    if (hits.has('unknown')) note += '; unknown path present => run everything'
  }

  return { ...surfaces, files: perFile, hits: [...hits], note, count: n }
}

/** 纯函数：基准形态判定（不碰 git）。全零串 = 新分支/强推，无处可 diff ⇒ 全跑。 */
export function baseMode(base) {
  const b = String(base ?? '').trim()
  if (b === '') return { mode: 'full', basis: 'full-no-base' }
  if (/^0+$/.test(b)) return { mode: 'full', basis: 'full-zero-sha' }
  return { mode: 'diff', basis: 'sha' }
}

const commitExists = (sha, cwd) => {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', `${sha}^{commit}`], { cwd, stdio: 'pipe' })
    return true
  } catch {
    return false
  }
}

/** 取 base..head 的变更路径。`--no-renames`：重命名拆成"删 + 增"两条，避免
 *  后端文件被挪到 docs/ 时只看见新路径（旧路径的删除面会漏掉 backend job）。 */
function diffPaths(base, head, cwd) {
  const out = execFileSync('git', ['diff', '--name-only', '--no-renames', '-z', base, head], {
    cwd,
    maxBuffer: 64 * 1024 * 1024,
    encoding: 'buffer',
  })
  return out.toString('utf8').split('\0').filter((s) => s !== '')
}

const readList = (file) =>
  fs
    .readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s !== '' && !s.startsWith('#'))

function writeOutputs(file, pairs) {
  const body = pairs.map(([k, v]) => `${k}=${String(v).replace(/\r?\n/g, ' ')}`).join('\n')
  fs.appendFileSync(file, body + '\n')
}

function summaryMarkdown(result, meta) {
  const mark = (b) => (b ? 'run' : 'skip')
  const lines = [
    '## Change set (phase A: CI skips jobs by change set)',
    '',
    `- Basis: \`${meta.basis}\`${meta.base ? ` = \`${meta.base}\`` : ''} (event: ${meta.event || 'not passed'})`,
    `- Changed files: ${result.count}; matched rules: ${result.hits.join(',') || '(none)'}`,
    `- Decision: ${result.note}`,
    '',
    '| Surface | Runs this time | Reason |',
    '|---|---|---|',
    `| backend | ${mark(result.backend)} | ${result.backend ? 'backend surface matched' : 'backend surface not matched'} |`,
    `| frontend | ${mark(result.frontend)} | ${result.frontend ? 'frontend surface matched' : 'frontend surface not matched'} |`,
    `| e2e | ${mark(result.e2e)} | ${result.e2e ? 'e2e surface matched (same source as backend)' : 'e2e surface not matched'} |`,
    '',
  ]
  if (result.count > 0) {
    const shown = result.files.slice(0, 50)
    lines.push('<details><summary>Changed files (up to 50)</summary>', '')
    for (const f of shown) lines.push(`- \`${f.path}\` — ${f.rule}`)
    if (result.count > shown.length) lines.push(`- ... ${result.count - shown.length} more omitted`)
    lines.push('', '</details>', '')
  }
  return lines.join('\n') + '\n'
}

// ── 自检 ────────────────────────────────────────────────────────────────────
function selfCheck() {
  let n = 0
  const eq = (label, got, want) => {
    n++
    const g = JSON.stringify(got)
    const w = JSON.stringify(want)
    if (g !== w) {
      console.error(`[self-check] FAIL ${label}\n  actual ${g}\n  expected ${w}`)
      process.exit(1)
    }
  }

  // ① 纯文档 PR：三面全跳（验收①的判定依据）
  eq('docs/** only: no code surface opens', (() => {
    const r = classify(['docs/research/x.md', 'docs/README.md', 'docs/evidence/a.png'])
    return [r.backend, r.frontend, r.e2e, r.hits]
  })(), [false, false, false, ['docs']])

  // ② 根级 *.md（README 等）同为文档面
  eq('root README.md: no code surface opens', (() => {
    const r = classify(['README.md'])
    return [r.backend, r.frontend, r.e2e]
  })(), [false, false, false])

  // ③ 耦合项：docs/kb-samples 必须开后端（否则被"纯文档"吃掉 —— 本文件最容易犯的错）
  eq('docs/kb-samples/** opens backend and e2e', (() => {
    const r = classify(['docs/kb-samples/01-region-tariff.md'])
    return [r.backend, r.frontend, r.e2e, r.hits]
  })(), [true, false, true, ['kb-samples']])

  // ④ 任意 backend/src/** 改动：backend + e2e（验收②）
  eq('backend/src/** opens backend and e2e', (() => {
    const r = classify(['backend/src/main/java/com/example/configmgr/A.java'])
    return [r.backend, r.frontend, r.e2e]
  })(), [true, false, true])

  // ⑤ 后端依赖/构建配置同属后端面
  eq('backend/pom.xml opens backend and e2e', (() => {
    const r = classify(['backend/pom.xml'])
    return [r.backend, r.e2e]
  })(), [true, true])

  // ⑥ 前端改动只开前端（E2E 只打后端 HTTP，不被前端影响）
  eq('frontend/** opens frontend only', (() => {
    const r = classify(['frontend/src/App.vue', 'frontend/src/locales/zh-CN.ts', 'frontend/yarn.lock',
      'frontend/vite.config.ts', 'frontend/tsconfig.app.json'])
    return [r.backend, r.frontend, r.e2e]
  })(), [false, true, false])

  // ⑦ scripts/** 与 workflow 自身：三面全跑（门禁被改必须自证）
  eq('scripts/** runs all three surfaces', (() => {
    const r = classify(['scripts/ci/stub-upstream.mjs'])
    return [r.backend, r.frontend, r.e2e]
  })(), [true, true, true])
  eq('.github/workflows/** runs all three surfaces', (() => {
    const r = classify(['.github/workflows/ci.yml'])
    return [r.backend, r.frontend, r.e2e]
  })(), [true, true, true])

  // ⑧ 认不出的路径兜底全跑（fail-open：宁可多跑，不可假绿）
  eq('unknown path => run everything', (() => {
    const r = classify(['.gitignore', 'some/new/dir/file.bin'])
    return [r.backend, r.frontend, r.e2e, r.hits.includes('unknown'), r.note.includes('run everything')]
  })(), [true, true, true, true, true])

  // ⑨ 混合变更：文档 + 后端 ⇒ 后端面开、前端面不开（文档不放大前端）
  eq('docs + backend mixed', (() => {
    const r = classify(['docs/a.md', 'backend/src/test/java/B.java'])
    return [r.backend, r.frontend, r.e2e, r.hits]
  })(), [true, false, true, ['docs', 'backend']])  // hits 按首次出现顺序，不排序

  // ⑩ 空变更集：三面全跳，且不是"输出丢失"（输出仍是 'false' 字符串）
  eq('empty change set opens no surface', (() => {
    const r = classify([])
    return [r.backend, r.frontend, r.e2e, r.count, r.note]
  })(), [false, false, false, 0, 'empty change set (0 files)'])

  // ⑪ 优先级：docs/kb-samples 不被 docs/** 规则先吃掉（规则表顺序契约）
  eq('rule table order: kb-samples before docs', RULES.findIndex((r) => r.id === 'kb-samples') <
    RULES.findIndex((r) => r.id === 'docs'), true)

  // ⑫ 基准形态（验收③）：空/全零 ⇒ 全跑；正常 sha ⇒ diff
  eq('empty base => full run', baseMode(''), { mode: 'full', basis: 'full-no-base' })
  eq('40 zeroes => full run', baseMode('0'.repeat(40)), { mode: 'full', basis: 'full-zero-sha' })
  eq('normal sha => diff', baseMode('dd88c58f'.padEnd(40, '0')), { mode: 'diff', basis: 'sha' })

  // ⑬ GITHUB_OUTPUT 形状：三面必须是字面量 'true'/'false'（workflow 用 == 'true' 比较），
  //    且值里不得出现换行（GITHUB_OUTPUT 的行协议）。
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'changed-files-selfcheck-'))
  writeOutputs(path.join(tmp, 'out.txt'), [
    ['backend', 'true'],
    ['frontend', 'false'],
    ['note', 'rules hit: docs\n(no newline allowed)'],
  ])
  const parsed = Object.fromEntries(
    fs.readFileSync(path.join(tmp, 'out.txt'), 'utf8').split('\n').filter(Boolean)
      .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]),
  )
  eq('output protocol: surfaces are literal true/false', [parsed.backend, parsed.frontend], ['true', 'false'])
  eq('output protocol: no newline inside a value', parsed.note.includes('\n'), false)
  fs.rmSync(tmp, { recursive: true, force: true })

  // ⑭ 仓内真实路径抽查：本仓确有此文件（防规则表按想象写）
  eq('repo contains backend/pom.xml', fs.existsSync(path.join(REPO, 'backend', 'pom.xml')), true)
  eq('repo contains backend/src/main/resources/application.yml',
    fs.existsSync(path.join(REPO, 'backend', 'src', 'main', 'resources', 'application.yml')), true)

  console.log(`changed-files self-check passed (${n} cases): rule table / basis parsing / output protocol / real repo paths`)
}

// ── 主流程 ──────────────────────────────────────────────────────────────────
function main() {
  if (flag('help')) {
    console.log(`usage:
  node scripts/ci/changed-files.mjs --base <sha|""> [--head <sha>] [--event <push|pull_request>]
                                    [--github-output <file>] [--summary-out <file>] [--json-out <file>] [--verbose]
  node scripts/ci/changed-files.mjs --diff-file <changed-paths.txt> [...]   # offline: feed a path list directly
  node scripts/ci/changed-files.mjs --self-check                            # rule-table self-check (runs first in the CI changes job)
single source of truth for rules = the RULES table in this file; unknown paths and unusable bases always mean "run everything".`)
    return
  }
  if (flag('self-check')) {
    selfCheck()
    return
  }

  const event = arg('event', '')
  const diffFile = arg('diff-file', '')
  const head = arg('head', 'HEAD')
  const base = arg('base', '')

  let files
  let basis
  let baseShown = base

  if (diffFile) {
    files = readList(diffFile)
    basis = 'diff-file'
  } else {
    const spec = baseMode(base)
    if (spec.mode === 'full') {
      files = null
      basis = spec.basis
    } else if (!commitExists(base, REPO)) {
      files = null
      basis = 'full-unreachable-base'
      console.error(`::warning::base commit ${base} is not resolvable locally (shallow clone / force push) => full run`)
    } else {
      try {
        files = diffPaths(base, head, REPO)
        basis = 'pr-base'
        if (event === 'push') basis = 'push-before'
        if (!event) basis = 'explicit-sha'
      } catch (e) {
        files = null
        basis = 'full-diff-error'
        console.error(`::warning::git diff ${base}..${head} failed (${e.message}) => full run`)
      }
    }
  }

  const result = files === null
    ? { backend: true, frontend: true, e2e: true, hits: ['full-run'], count: 0, files: [],
        note: `base unavailable (${basis}) => all three surfaces run, no skipping` }
    : classify(files)

  const pairs = [
    ['base', files === null ? 'FULL' : baseShown],
    ['basis', basis],
    ['backend', String(result.backend)],
    ['frontend', String(result.frontend)],
    ['e2e', String(result.e2e)],
    ['files', String(result.count)],
    ['hits', result.hits.join(',')],
    ['note', result.note],
  ]

  const githubOutput = arg('github-output', '')
  if (githubOutput) writeOutputs(githubOutput, pairs)

  const summaryOut = arg('summary-out', '')
  if (summaryOut) fs.appendFileSync(summaryOut, summaryMarkdown(result, { basis, base: baseShown, event }))

  const jsonOut = arg('json-out', '')
  if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify({ ...result, basis, base: baseShown, event }, null, 2))

  // 人读输出：CI 日志首屏就能看出为什么某个 job 没跑。
  console.log(`change set: basis=${basis}${baseShown ? `(${baseShown})` : ''} files=${result.count}`)
  console.log(`rules hit: ${result.hits.join(',') || '(none)'}`)
  console.log(`backend=${result.backend} frontend=${result.frontend} e2e=${result.e2e}`)
  console.log(`decision: ${result.note}`)
  if (flag('verbose')) for (const f of result.files) console.log(`  ${f.rule}\t${f.path}`)
}

// 只有被直接执行时才跑 main：便于 B 期 `select-tests.mjs` 与单测 `import { classify }`，
// 导入本模块不会产生副作用。
const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(SELF)
if (invokedDirectly) main()
