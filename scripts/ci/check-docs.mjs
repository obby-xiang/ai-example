/**
 * check-docs.mjs —— 文档一致性门禁（DOC#5 裁决 / M2 排期条目 18 / T4-7a-1）
 *
 * 零第三方依赖：只用 Node 内置模块（与 `scripts/ci/stub-upstream.mjs` 同口径）。
 * 用法：node scripts/ci/check-docs.mjs [--help]
 * 退出码：0 = 六条断言全绿；1 = 有红（逐条打印 文件:行 定位）。
 *
 * 六条断言（裁决 7 固定）：
 *   ① 导航/口径文档无盘符绝对路径（正则取法与 scripts/githooks/scan-lib.sh 的 scan_rule_path 一致）
 *   ② 技术栈版本与 backend/pom.xml、frontend/package.json 一致
 *   ③ 导航/口径文档引用的仓库内文件存在
 *   ④ 端口表与 application.yml / vite.config.ts 一致（且默认值三方对齐：vite / .env.example / 文档）
 *   ⑤ 命名纪律禁则（按 git ls-files 口径全量逐文件；不得 grep 工作树）
 *      —— 匹配前做 NFKC 折叠 + 零宽字符双态归一（删除态抓词内藏形 / 分隔态抓零宽作分隔）+ Unicode
 *         连字符族分隔符（T4-S2 强化、T4-S2-R1 补全双态），使全角形态、U+2010-U+2015/U+2212 等
 *         "粘贴破折号"与零宽分隔形态都无法绕过本断言（口径详见断言 ⑤ 段注释）
 *   ⑥ docs/evidence/probes/ 的 .java 探针不参与构建
 *
 * 范围边界（裁决 7，重要）：①②③④ 的扫描范围是下面的 NAV_DOCS 显式清单 ——
 *   **明确排除 `docs/evidence/` 历史证据文档**（其中出现的 8080/182xx 端口号、旧版本号属历史记录，不回改）。
 *   ⑤ 相反，按 `git ls-files` **全量**（历史证据文档同样受命名纪律约束）。
 */

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')

// ── 扫描范围：导航/口径文档（显式清单，避免"全仓 grep"近似实现把历史证据文档扫进来） ──
const NAV_DOCS = [
  'README.md',
  'docs/README.md',
  'scripts/README.md',
  'scripts/githooks/README.md'
]

// ── 命名纪律豁免（唯一豁免面：3 个 spike 脱敏脚本的匹配源正则，见 docs/README.md §命名纪律） ──
const NAMING_EXEMPT = [
  'spike/sp01ab/scripts/sp01ab-sanitize.mjs',
  'spike/sp02/scripts/sp02-sanitize.mjs',
  'spike/sp01d/scripts/make-attachments.mjs'
]

// ── 探针期望清单（新增探针必须显式登记到本清单，防静默夹带） ──
const PROBE_DIR = 'docs/evidence/probes'
const EXPECTED_PROBES = [
  'M2-T3-5-ProbeDelta.java',
  'M2-T3-5-ProbeLock.java',
  'M2-T3-5-ProbeMatrix.java',
  'M2-T3-5-ProbeReplay.java',
  'M2-T3a-Probe.java',
  'M2-T3a-ProbeR1.java'
]

const problems = []
let currentAssertion = ''
const fail = (where, detail) => problems.push({ assertion: currentAssertion, where, detail })
const assertion = (title, fn) => {
  currentAssertion = title
  const before = problems.length
  try {
    fn()
  } catch (ex) {
    fail('scripts/ci/check-docs.mjs', `断言执行异常：${ex.message}`)
  }
  const hits = problems.length - before
  console.log(`  ${hits === 0 ? '[PASS]' : '[FAIL]'} ${title}${hits === 0 ? '' : `（${hits} 处）`}`)
}

const abs = (relPath) => path.join(REPO, relPath)
const exists = (relPath) => fs.existsSync(abs(relPath))
const read = (relPath) => fs.readFileSync(abs(relPath), 'utf8')
const docLines = (relPath) => read(relPath).split(/\r?\n/).map((text, i) => ({ no: i + 1, text }))

function gitLsFiles() {
  return execFileSync('git', ['ls-files', '-z'], { cwd: REPO, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 })
    .split('\0')
    .filter(Boolean)
}

// ── ① 导航文档无盘符绝对路径 ────────────────────────────────────────────────
// 正则两条与 scan-lib.sh 的 scan_rule_path 逐字同构（D:/… 与 /c/… 两种形态）；
// 占位符 <…> 整段先剔除，命中片段含 < > 或 xxx/placeholder/redacted 时按既有豁免口放过。
const PATH_RE = [
  /(^|[^A-Za-z0-9_])[A-Za-z]:[\\/][-A-Za-z0-9_.\\/]{1,60}/g,
  /(^|[^A-Za-z0-9_/])\/[cdefgh]\/[-A-Za-z0-9_.\\/]{1,60}/g
]
const PATH_EXEMPT = /(xxx|placeholder|redacted|[<>])/i

function assertNoAbsolutePathInDocs() {
  const scanLib = read('scripts/githooks/scan-lib.sh')
  if (!/scan_rule_path\s*\(\)/.test(scanLib)) {
    fail('scripts/githooks/scan-lib.sh', '未找到 scan_rule_path()：① 的"复用 scan-lib PATH 正则"前提失效')
  }
  for (const doc of NAV_DOCS) {
    if (!exists(doc)) {
      fail(doc, '导航/口径文档清单中的文件不存在')
      continue
    }
    for (const { no, text } of docLines(doc)) {
      const stripped = text.replace(/<[^>]{1,80}>/g, '')
      for (const re of PATH_RE) {
        re.lastIndex = 0
        let m
        while ((m = re.exec(stripped)) !== null) {
          const token = m[0]
          if (!PATH_EXEMPT.test(token)) fail(`${doc}:${no}`, `盘符/本机绝对路径：${token.trim()}`)
          if (m.index === re.lastIndex) re.lastIndex++
        }
      }
    }
  }
}

// ── ② 技术栈版本与 pom.xml / package.json 一致 ───────────────────────────────
function firstMatch(text, re, where) {
  const m = re.exec(text)
  if (!m) fail(where, `未能从事实源取到版本（正则不匹配）：${re}`)
  return m ? m[1] : null
}

function assertVersionsMatch() {
  const pom = read('backend/pom.xml')
  const pkg = JSON.parse(read('frontend/package.json'))
  const dep = (name) => pkg.dependencies?.[name] ?? pkg.devDependencies?.[name]
  const checks = [
    ['Spring Boot', firstMatch(pom, /<artifactId>spring-boot-starter-parent<\/artifactId>\s*<version>([^<]+)<\/version>/, 'backend/pom.xml'), 'backend/pom.xml'],
    ['JDK', firstMatch(pom, /<java\.version>([^<]+)<\/java\.version>/, 'backend/pom.xml'), 'backend/pom.xml'],
    ['Spring AI', firstMatch(pom, /<spring-ai\.version>([^<]+)<\/spring-ai\.version>/, 'backend/pom.xml'), 'backend/pom.xml'],
    ['Vue 3', dep('vue'), 'frontend/package.json'],
    ['TypeScript', dep('typescript'), 'frontend/package.json'],
    ['Vite', dep('vite'), 'frontend/package.json'],
    ['Pinia', dep('pinia'), 'frontend/package.json'],
    ['Element Plus + TailwindCSS', `${dep('element-plus')} / ${dep('tailwindcss')}`, 'frontend/package.json'],
    ['GrapeCity SpreadJS', dep('@grapecity/spread-sheets'), 'frontend/package.json']
  ]
  const readme = docLines('README.md')
  for (const [label, expected, source] of checks) {
    if (!expected) {
      fail(source, `${label}：事实源中无该依赖版本`)
      continue
    }
    const row = readme.find((l) => new RegExp(`\\|\\s*${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\|`).test(l.text))
    if (!row) {
      fail('README.md', `技术栈表缺「${label}」行（事实源：${source}）`)
      continue
    }
    if (!row.text.replace(/`/g, '').includes(expected)) {
      fail(`README.md:${row.no}`, `版本漂移：README 行内未出现事实源值 ${expected}（${source}）`)
    }
  }
}

// ── ③ 导航文档引用的仓库内文件存在 ───────────────────────────────────────────
const REF_RE = /^(?:docs|scripts|backend|frontend|spike|\.github)\/[A-Za-z0-9_./@-]+\.(?:md|json|yml|yaml|xml|ts|js|mjs|cjs|ps1|sh|java|vue|txt|cfg|example)$/

// 断言 ③ 的显式例外（可审计，非宽泛豁免）：README 引导用户"从 .example 复制生成"的本地配置 ——
// backend/config/application.yml 被 .gitignore 排除、仓库内本就不存在，属"待用户创建"，不是仓库引用漂移。
const REF_ALLOW = new Set(['backend/config/application.yml'])

function assertReferencedFilesExist() {
  const seen = new Set()
  for (const doc of NAV_DOCS) {
    if (!exists(doc)) continue
    for (const { no, text } of docLines(doc)) {
      for (const m of text.matchAll(/`([^`]+)`/g)) {
        const token = m[1].trim()
        if (!REF_RE.test(token)) continue
        if (token.includes('*')) continue // glob 形态不逐文件断言
        if (REF_ALLOW.has(token)) continue // 见 REF_ALLOW 注释：仓库外/待用户创建的本地文件
        const key = `${doc}|${token}`
        if (seen.has(key)) continue
        seen.add(key)
        if (!exists(token)) fail(`${doc}:${no}`, `引用的文件不存在：${token}`)
      }
    }
  }
}

// ── ④ 端口表与两个事实源一致 ────────────────────────────────────────────────
function portOf(text) {
  const m = /\d+/.exec(text)
  return m ? Number(m[0]) : null
}

function readPortTable() {
  const lines = docLines('README.md')
  const start = lines.findIndex((l) => /^##\s+端口一览\s*$/.test(l.text))
  if (start < 0) {
    fail('README.md', '缺「## 端口一览」小节（断言 ④ 的事实源）')
    return new Map()
  }
  const rows = new Map()
  for (let i = start + 1; i < lines.length; i++) {
    const text = lines[i].text
    if (/^##\s/.test(text)) break
    if (!text.startsWith('|')) continue
    const cells = text.split('|').map((c) => c.trim())
    if (cells.length < 4) continue
    const label = cells[1]
    if (label === '服务' || /^-+$/.test(label)) continue
    rows.set(label, { port: portOf(cells[2]), line: lines[i].no, text })
  }
  return rows
}

function assertPortsMatch() {
  const appYml = read('backend/src/main/resources/application.yml')
  const vite = read('frontend/vite.config.ts')
  const base = read('scripts/verify-e2e.ps1')
  const stub = read('scripts/ci/stub-upstream.mjs')

  const serverPort = Number(firstMatch(appYml, /^server:\s*\n\s+port:\s*(\d+)/m, 'application.yml'))
  const devPort = Number(firstMatch(vite, /DEFAULT_DEV_PORT\s*=\s*(\d+)/, 'frontend/vite.config.ts'))
  const e2ePort = Number(firstMatch(base, /\[string\]\$Base\s*=\s*'http:\/\/127\.0\.0\.1:(\d+)'/, 'scripts/verify-e2e.ps1'))
  const stubPort = Number(firstMatch(stub, /PORT\s*=\s*Number\(arg\('port',\s*'(\d+)'\)\)/, 'scripts/ci/stub-upstream.mjs'))

  const table = readPortTable()
  const expectRows = [
    ['后端', serverPort, 'application.yml 的 server.port'],
    ['前端 dev server', devPort, 'vite.config.ts 的 DEFAULT_DEV_PORT'],
    ['E2E 后端', e2ePort, 'verify-e2e.ps1 的 -Base 默认值'],
    ['上游桩（E2E/CI）', stubPort, 'stub-upstream.mjs 的 --port 默认值']
  ]
  for (const [label, expected, source] of expectRows) {
    const row = table.get(label)
    if (!row) {
      fail('README.md', `端口一览缺「${label}」行（事实源：${source}）`)
      continue
    }
    if (row.port !== expected) {
      fail(`README.md:${row.line}`, `${label} 端口表值 ${row.port} != ${source} 的 ${expected}`)
    }
  }

  // 端口唯一事实源：application.yml 不得残留 app.server.* / frontend-port / backend-port 死键
  for (const { no, text } of docLines('backend/src/main/resources/application.yml')) {
    if (/app\.server|frontend-port|backend-port|^\s{2}server:\s*$/.test(text)) {
      fail(`backend/src/main/resources/application.yml:${no}`, `残留无绑定死键：${text.trim()}`)
    }
  }

  // 默认值三方对齐：vite DEFAULT_API_BASE / .env.example VITE_API_BASE 都必须指向后端真实端口
  const apiBasePort = Number(firstMatch(vite, /DEFAULT_API_BASE\s*=\s*'http:\/\/localhost:(\d+)'/, 'frontend/vite.config.ts'))
  if (apiBasePort !== serverPort) {
    fail('frontend/vite.config.ts', `DEFAULT_API_BASE 端口 ${apiBasePort} != 后端 server.port ${serverPort}（开箱对齐被破坏）`)
  }
  const envExample = read('frontend/.env.example')
  const envPort = Number(firstMatch(envExample, /^VITE_API_BASE=http:\/\/localhost:(\d+)/m, 'frontend/.env.example'))
  if (envPort !== serverPort) {
    fail('frontend/.env.example', `VITE_API_BASE 端口 ${envPort} != 后端 server.port ${serverPort}`)
  }

  // 导航/口径文档不得残留旧默认端口 8080（历史证据文档已按裁决 7 排除在扫描范围外）
  for (const doc of NAV_DOCS) {
    if (!exists(doc)) continue
    for (const { no, text } of docLines(doc)) {
      if (/:8080\b/.test(text)) fail(`${doc}:${no}`, '残留旧默认端口 8080（应为后端 8081 或去掉）')
    }
  }
}

// ── ⑤ 命名纪律禁则（git ls-files 全量） ──────────────────────────────────────
// 规则字面量按 DC-08 拼接构造（不在源码里写出完整禁用字面量）：
//   前缀 + 分隔符（连字符/下划线/空格/点，可重复）+ 后缀，任意大小写组合。
//
// 归一化口径（T4-S2 强化、T4-S2-R1 补全，防"同形字符绕过"）：分隔符类只含 ASCII + \s 时，普通粘贴
// 一枚 Unicode 连字符（U+2011 等）或全角输入即可让禁词在门禁下隐形。故匹配前对文本做三步归一：
//   ① NFKC 折叠 —— 全角字母与全角连字符（U+FF0D）等兼容字符折回 ASCII 形态；
//      （本注释自身即受断言 ⑤ 约束：禁用字面量按 DC-08 拼接写，不在此写出实例。）
//   ② 零宽字符（U+200B-200D / U+2060 / U+FEFF）**双态**归一，两态分别匹配、任一命中即红：
//      删除态 = 替换为空串（拼合相邻片段），抓"零宽藏在词内"；
//      分隔态 = 替换为单个空格（视作分隔符），抓"零宽充当分隔"。
//      只做删除态是不够的：它会把"零宽作分隔"退化成"相邻连写"（连写是既定反例、不报红），
//      并丢掉 JS \s 原本覆盖 U+FEFF 的能力（T4-S2-R1 打回原因）；双态不动判定边界。
//   ③ 分隔符类补 Unicode 连字符族（U+2010-U+2015 / U+2043 / U+2212 / U+FE58 / U+FE63 / U+FF0D）。
// 归一化只作用于匹配，不改文件内容，也不改变行号（两态都不增删换行）。
const NAME_PREFIX = 'ma' + 'in'
const NAME_SUFFIX = 'v' + '2'
const NAME_SEP = '[\\s\\-_.\\u2010-\\u2015\\u2043\\u2212\\uFE58\\uFE63\\uFF0D]+'
const NAME_RE = new RegExp(NAME_PREFIX + NAME_SEP + NAME_SUFFIX, 'i')
const ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g
const normalizeName = (text) => text.normalize('NFKC').replace(ZERO_WIDTH_RE, '')
const separateName = (text) => text.normalize('NFKC').replace(ZERO_WIDTH_RE, ' ')
const hitsName = (text) => NAME_RE.test(normalizeName(text)) || NAME_RE.test(separateName(text))

function assertNamingDiscipline() {
  for (const exempt of NAMING_EXEMPT) {
    if (!exists(exempt)) {
      fail(exempt, '命名纪律豁免清单中的文件不存在（豁免面与 docs/README.md §命名纪律不一致）')
      continue
    }
    const head = read(exempt).split(/\r?\n/).slice(0, 12).join('\n')
    if (!/命名纪律豁免/.test(head) || !/DOC#5/.test(head)) {
      fail(exempt, '豁免文件头部缺「命名纪律豁免 / DOC#5」依据注释（防借豁免之名夹带）')
    }
  }
  const exemptSet = new Set(NAMING_EXEMPT)
  let scanned = 0
  for (const relPath of gitLsFiles()) {
    if (exemptSet.has(relPath)) continue
    const file = abs(relPath)
    let buf
    try {
      buf = fs.readFileSync(file)
    } catch {
      fail(relPath, '按 git ls-files 清单读取失败（文件缺失或被删除）')
      continue
    }
    if (buf.includes(0)) continue // 二进制跳过
    scanned++
    const text = buf.toString('utf8')
    if (!hitsName(text)) continue
    for (const { no, text: line } of text.split(/\r?\n/).map((t, i) => ({ no: i + 1, text: t }))) {
      if (hitsName(line)) fail(`${relPath}:${no}`, '命中命名纪律禁则（旧主仓目录名/同名分支名；指代一律用占位符）')
    }
  }
  console.log(`         （命名纪律扫描：git ls-files ${gitLsFiles().length} 个路径，实扫文本 ${scanned} 个；豁免 ${exemptSet.size} 个）`)
}

// ── ⑥ .java 探针不参与构建 ──────────────────────────────────────────────────
function assertProbesNotBuilt() {
  const tracked = gitLsFiles().filter((f) => f.startsWith(`${PROBE_DIR}/`) && f.endsWith('.java'))
  const basenames = tracked.map((f) => path.basename(f)).sort()
  const expected = [...EXPECTED_PROBES].sort()
  if (basenames.join(',') !== expected.join(',')) {
    fail(PROBE_DIR, `探针清单与期望集合不一致（新增探针须显式登记进 check-docs.mjs）：实见 [${basenames.join(', ')}]，期望 [${expected.join(', ')}]`)
  }
  for (const relPath of tracked) {
    const head = read(relPath).split(/\r?\n/).slice(0, 20).join('\n')
    if (!/不参与.*构建.*测试计数/.test(head)) {
      fail(relPath, '探针头部缺「仓库外编译运行、不参与构建与测试计数」声明')
    }
  }
  const pom = read('backend/pom.xml')
  const patterns = [
    [/<sourceDirectory>([^<]*docs[^<]*)<\/sourceDirectory>/, 'sourceDirectory'],
    [/<testSourceDirectory>([^<]*docs[^<]*)<\/testSourceDirectory>/, 'testSourceDirectory'],
    [/<(?:test)?[Ii]ncludes>([\s\S]*?<\/(?:test)?[Ii]ncludes)>/, 'includes']
  ]
  for (const [re, name] of patterns) {
    const m = re.exec(pom)
    if (m && /docs/.test(m[1])) {
      fail('backend/pom.xml', `pom 声明了指向 docs/ 的 ${name}：${m[1].trim()}`)
    }
  }
}

// ── 入口 ───────────────────────────────────────────────────────────────────
if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(`文档一致性门禁（DOC#5）

用法：node scripts/ci/check-docs.mjs
      node scripts/ci/check-docs.mjs --help

六条断言（任何一条红即 exit 1）：
  ① 导航/口径文档无盘符绝对路径（正则取法与 scripts/githooks/scan-lib.sh 一致）
  ② 技术栈版本与 backend/pom.xml、frontend/package.json 一致
  ③ 导航/口径文档引用的仓库内文件存在
  ④ 端口表与 application.yml / vite.config.ts 一致（含默认值三方对齐与端口唯一事实源）
  ⑤ 命名纪律禁则（git ls-files 全量；唯一豁免 = 3 个 spike 脱敏脚本的匹配源正则）
  ⑥ docs/evidence/probes/ 的 .java 探针不参与构建

扫描范围：①②③④ 只扫导航/口径文档（${NAV_DOCS.join(' / ')}），
明确排除 docs/evidence/ 历史证据文档（裁决 7）；⑤ 按 git ls-files 全量。
详细口径见 docs/M2-排期计划.md 条目 18、docs/README.md §命名纪律。`)
  process.exit(0)
}

console.log('文档一致性门禁（check-docs）')
assertion('① 导航/口径文档无盘符绝对路径', assertNoAbsolutePathInDocs)
assertion('② 技术栈版本与 pom.xml / package.json 一致', assertVersionsMatch)
assertion('③ 导航/口径文档引用的文件存在', assertReferencedFilesExist)
assertion('④ 端口表与 application.yml / vite.config.ts 一致', assertPortsMatch)
assertion('⑤ 命名纪律禁则（git ls-files 全量）', assertNamingDiscipline)
assertion('⑥ .java 探针不参与构建', assertProbesNotBuilt)

console.log('')
if (problems.length === 0) {
  console.log('全部通过：6/6 断言绿。')
  process.exit(0)
}
console.log(`发现 ${problems.length} 处文档漂移：`)
for (const p of problems) console.log(`  [${p.assertion}] ${p.where}  =>  ${p.detail}`)
process.exit(1)
