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
 *      —— 匹配前做 NFKC 折叠 + 隐形/零宽字符双态归一（删除态抓词内藏形 / 分隔态抓隐形字符作分隔）+
 *         Unicode 连字符族分隔符 + **词首守卫**（T4-S2 强化、T4-S2-R1 补全双态），并追加第三态
 *         **去分隔符连写**（分隔符零个或多个 → 抓零分隔连写形态；DC-08 补强，2026-10-10 裁决），
 *         覆盖全角/兼容折叠形态、U+2010-U+2015/U+2212 等"粘贴破折号"、隐形字符分隔与零分隔连写形态；
 *         **该覆盖不等于"无法绕过"**：NFKC 不折叠的同形字母（西里尔/希腊等）与本断言注释末尾登记的
 *         其他已知缺口仍可穿过（详见断言 ⑤ 段注释；⑤ 自带正向种子/负对照自检，且连写组种子断言
 *         「仅第三态可捕获」，防规则被改窄后静默失效）
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
//   词首守卫 + 前缀 + 分隔符（连字符/下划线/空格/点，可重复**或零个**）+ 后缀，任意大小写组合。
//
// 归一化口径（T4-S2 强化、T4-S2-R1 补全双态、DC-08 补强第三态、DC-08-R2 扩字符类，防"拆形绕过"）：
// 分隔符类只含 ASCII + \s 时，普通粘贴一枚 Unicode 连字符（U+2011 等）或全角输入即可让禁词在门禁下隐形。
// 故匹配前对文本做归一，并按三态分别匹配、任一命中即红：
//   ① NFKC 折叠 —— 全角字母与全角连字符（U+FF0D）等兼容字符折回 ASCII 形态；
//      （本注释自身即受断言 ⑤ 约束：禁用字面量按 DC-08 拼接写，不在此写出实例。）
//   ② 隐形/零宽字符（字符面 = INVISIBLE_RE，见其定义处）**双态**归一：
//      删除态 = 替换为空串（拼合相邻片段），抓"隐形字符藏在词内"；
//      分隔态 = 替换为单个空格（视作分隔符），抓"隐形字符充当分隔"。
//      只做删除态是不够的：它丢掉 JS \s 原本覆盖 U+FEFF 的能力（T4-S2-R1 打回原因）。
//   ③ 分隔符类补 Unicode 连字符族（U+2010-U+2015 / U+2043 / U+2212 / U+FE58 / U+FE63 / U+FF0D）。
// **② 的字符面扩容（DC-08-R2，2026-10-10；GLM 红队 UI 包评审 MAJOR-2）**：原类只含
//   U+200B-200D / U+2060 / U+FEFF，红队实测另有 10 例隐形字符形态可全绿入库 —— U+00AD（软连字符，
//   复制粘贴最常见）、U+180E、U+2061-U+2064、U+206A（同族 U+206B-U+206F）、U+3164、U+FFA0。故字符面
//   扩至「零宽 / 方向格式 / 不可见算符 / 填充符 / 变体选择符」全族。两个实现要点：**NFKC 先于隐形字符
//   归一**，故 U+FFA0 会先折成 U+1160，U+115F/U+1160 必须同时在类内才拦得住；两个归一态共用同一字符面，
//   故只改一处即可同时覆盖删除态与分隔态（漏改一族另一态会静默失效）。
// 归一化只作用于匹配，不改文件内容，也不改变行号（各态都不增删换行）。
//
// **第三态 = 去分隔符连写（DC-08 补强，2026-10-10 裁决）**：①② 的分隔符量词是"一个或多个"，故
//   **零分隔连写**形态（前缀与后缀之间一个分隔符也没有）在双态下隐形、全绿入库。补 NAME_CONCAT_RE：
//   分隔符量词改"零个或多个"，在删除态归一文本上匹配 —— 等价于"把 - _ 空格 点 去掉后按前缀+后缀
//   匹配"，并入①的覆盖，且顺带覆盖"零宽既藏词内又作分隔"的组合形态。
// **词首守卫 NAME_GUARD**：禁词前一个字符不得是 ASCII 字母/数字。这是"去掉分隔符后匹配"的必然配套
//   —— 若不做守卫，域名/长单词尾部（如 *domain* 后缀 `v2` 这类写法去掉分隔符后与禁词尾部逐字符
//   重合）会被逐字符匹配误伤。真实指代（仓库目录名/分支名/路径段/代码字符串）恒由 `/\<>-`、空格、
//   引号或行首等非字母数字字符定界，故守卫不削弱本禁则的判定面（本批全仓实测：加守卫前后全仓命中
//   集合完全一致，收窄的只是域名/长单词尾部的假阳性）。附：负对照中能写出域名样本本身，也依赖该守卫。
// **已知缺口（如实登记：本断言不声称穷尽绕过面；2026-10-10 GLM 红队 UI 包评审 MAJOR-2 实测 3 例）**：
//   NFKC 不折叠的**同形字母**可穿过本断言 —— 把禁词里的 ASCII 字母换成视觉酷似的西里尔/希腊字母
//   （例如用小写西里尔 a 顶替 ASCII a、或用希腊 ν 顶替 ASCII n），门禁判绿而人眼读作禁词。本批**不修**：
//   同形折叠须一张全字母表级映射表，漏一个字母即等于没折叠，且折叠后与真实英文标识符的判定面互相
//   污染（含同名 ASCII 字母的普通单词会被折成禁词），须连同负对照集单独立项评估。同族未覆盖的还有
//   「以分隔符类之外的字符充当分隔」的形态（例如以 `/` 充当分隔的路径式写法）。
//   → 本断言的覆盖口径以本条为准：**覆盖「NFKC 可折叠 + 隐形字符 + 分隔符量化（零个或多个）」三类
//   绕过面，不等于"无法绕过"，也不等于"全部拆形手法"。**
const NAME_GUARD = '(^|[^A-Za-z0-9])'
const NAME_PREFIX = 'ma' + 'in'
const NAME_SUFFIX = 'v' + '2'
const NAME_SEP_CLASS = '\\s\\-_.\\u2010-\\u2015\\u2043\\u2212\\uFE58\\uFE63\\uFF0D'
const NAME_SEP = `[${NAME_SEP_CLASS}]+`
const NAME_SEP_OPT = `[${NAME_SEP_CLASS}]*`
const NAME_RE = new RegExp(NAME_GUARD + NAME_PREFIX + NAME_SEP + NAME_SUFFIX, 'i')
const NAME_CONCAT_RE = new RegExp(NAME_GUARD + NAME_PREFIX + NAME_SEP_OPT + NAME_SUFFIX, 'i')
// 隐形/零宽字符面（DC-08-R2 扩字符类）：零宽（U+200B-200D 空格/连接符/不连接符 + U+2060 词连接符）、
//   方向标记与嵌入（U+200E-200F / U+202A-202E / U+2066-2069）、不可见算符（U+2061-2064）、弃用格式字符
//   （U+206A-206F）、填充符（U+115F-1160 / U+3164 / U+FFA0，后两者经 NFKC 亦落 U+1160）、变体选择符
//   （U+FE00-FE0F）、BOM（U+FEFF）、软连字符（U+00AD）、蒙古元音分隔（U+180E）。
const INVISIBLE_RE = /[\u00AD\u115F\u1160\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u206F\u3164\uFE00-\uFE0F\uFEFF\uFFA0]/g
const normalizeName = (text) => text.normalize('NFKC').replace(INVISIBLE_RE, '')
const separateName = (text) => text.normalize('NFKC').replace(INVISIBLE_RE, ' ')
const hitsName = (text) =>
  NAME_RE.test(normalizeName(text)) || NAME_RE.test(separateName(text)) || NAME_CONCAT_RE.test(normalizeName(text))

// ── ⑤-a 规则自检（植入对照；DC-08 补强、DC-08-R2 严格化）：正向种子必命中、负对照必不误伤 ────
// 种子/对照同样按 DC-08 拼接构造（本文件自身在断言 ⑤ 的扫描面内，源码里不得出现禁用字面量）。
// **拆两组断言（2026-10-10，GLM 红队 UI 包评审 MINOR-1）**：原实现只统计「种子是否经 NAME_CONCAT_RE
// 命中」并设下限 6，而该正则的分隔符量词是"零个或多个" ⇒ 含分隔符的种子**必然**也命中它（13/13 是数学
// 必然），下限形同虚设；红队实测「删掉第三态 + 删掉 5 个连写种子」后自检仍全绿、违禁文本可放行。现改为：
//   ① CONCAT 组（连写组）：种子的命中**只允许**来自第三态连写规则 —— 断言 hitsName 为真，且前两态
//      （NFKC 折叠态 / 隐形字符分隔态）都不命中。第三态一旦被删或量词被改回"一个或多个"，本组立即红。
//   ② SEPARATED 组（分隔组）：种子的命中必须来自前两态之一（NFKC 折叠态或隐形字符分隔态）—— 含 DC-08-R2
//      收编的红队隐形字符绕过样本；本组不得退回"仅第三态可捕获"，否则等于隐形字符面被改窄而无人察觉。
// 两组各设**数量下限常量**（与数组长度解耦，见下）：删掉种子行会被下限抓住（若下限写成数组长度，删行后
// 下限会静默跟随、永不报红），追加样本不受影响。
const NAMING_SEEDS_CONCAT_MIN = 5
const NAMING_SEEDS_SEPARATED_MIN = 24
const NAMING_SEEDS_CONCAT = [
  ['零分隔连写', NAME_PREFIX + NAME_SUFFIX],
  ['零分隔连写·全大写', (NAME_PREFIX + NAME_SUFFIX).toUpperCase()],
  ['零分隔连写·路径形态', '<TEMP_ROOT>/' + NAME_PREFIX + NAME_SUFFIX + '-db-backup.mv.db'],
  ['零分隔连写·中文紧邻', '本机' + NAME_PREFIX + NAME_SUFFIX + '仓'],
  ['零分隔连写·前缀为分隔符', 'kimi' + '_' + NAME_PREFIX + NAME_SUFFIX]
]
const NAMING_SEEDS_SEPARATED = [
  ['分隔符·连字符', NAME_PREFIX + '-' + NAME_SUFFIX],
  ['分隔符·下划线', NAME_PREFIX + '_' + NAME_SUFFIX],
  ['分隔符·空格', NAME_PREFIX + ' ' + NAME_SUFFIX],
  ['分隔符·点', NAME_PREFIX + '.' + NAME_SUFFIX],
  ['分隔符·全角连字符（NFKC）', NAME_PREFIX + '\uFF0D' + NAME_SUFFIX],
  ['分隔符·Unicode 连字符 U+2011', NAME_PREFIX + '\u2011' + NAME_SUFFIX],
  ['隐形字符·零宽藏词内（删除态）', NAME_PREFIX + '\u200B' + NAME_SUFFIX],
  ['隐形字符·零宽作分隔（分隔态）', NAME_PREFIX + '\u200B' + NAME_SUFFIX + '\u200B' + 'path'],
  ['隐形字符·软连字符 U+00AD（红队 MAJOR-2）', NAME_PREFIX + '\u00AD' + NAME_SUFFIX],
  ['隐形字符·软连字符+连字符 U+00AD（红队 MAJOR-2）', NAME_PREFIX + '\u00AD' + '-' + NAME_SUFFIX],
  ['隐形字符·蒙古元音分隔 U+180E（红队 MAJOR-2）', NAME_PREFIX + '\u180E' + NAME_SUFFIX],
  ['隐形字符·不可见算符 U+2061（红队 MAJOR-2）', NAME_PREFIX + '\u2061' + NAME_SUFFIX],
  ['隐形字符·不可见算符 U+2062（红队 MAJOR-2）', NAME_PREFIX + '\u2062' + NAME_SUFFIX],
  ['隐形字符·不可见分隔 U+2063（红队 MAJOR-2）', NAME_PREFIX + '\u2063' + NAME_SUFFIX],
  ['隐形字符·加号算符 U+2064（红队 MAJOR-2）', NAME_PREFIX + '\u2064' + NAME_SUFFIX],
  ['隐形字符·弃用格式 U+206A（红队 MAJOR-2）', NAME_PREFIX + '\u206A' + NAME_SUFFIX],
  ['隐形字符·弃用格式落点 U+206F（同族补样）', NAME_PREFIX + '\u206F' + NAME_SUFFIX],
  ['隐形字符·Hangul filler U+3164（红队 MAJOR-2）', NAME_PREFIX + '\u3164' + NAME_SUFFIX],
  ['隐形字符·半角 Hangul filler U+FFA0（红队 MAJOR-2；NFKC 先折 U+1160）', NAME_PREFIX + '\uFFA0' + NAME_SUFFIX],
  ['隐形字符·Hangul filler U+115F（同族补样，U+FFA0 折叠落点家族）', NAME_PREFIX + '\u115F' + NAME_SUFFIX],
  ['隐形字符·方向标记 U+200E（同族补样）', NAME_PREFIX + '\u200E' + NAME_SUFFIX],
  ['隐形字符·方向嵌入 U+202B（同族补样）', NAME_PREFIX + '\u202B' + NAME_SUFFIX],
  ['隐形字符·方向隔离 U+2066（同族补样）', NAME_PREFIX + '\u2066' + NAME_SUFFIX],
  ['隐形字符·变体选择符 U+FE00（同族补样）', NAME_PREFIX + '\uFE00' + NAME_SUFFIX]
]
const NAMING_CONTROLS = [
  ['单词单独出现', '分支 ' + 'main' + ' 与 ' + NAME_SUFFIX + ' 版本'],
  ['域名类·空格', 'domain' + ' ' + NAME_SUFFIX],
  ['域名类·连字符', 'domain' + '-' + NAME_SUFFIX],
  ['域名类·连写', 'domain' + NAME_SUFFIX],
  ['域名类·URL', 'https://example.com/domain' + NAME_SUFFIX],
  ['文件名主干', 'src/' + 'main' + '.ts'],
  ['文件名主干+版本词', 'src/' + 'main' + '.ts ' + NAME_SUFFIX + ' 未涉及'],
  ['占位符惯例', '<MAIN_REPO>-db-backup'],
  ['占位符惯例·紧凑', '<REPO_ROOT>/<TEMP_ROOT>'],
  ['无关英文词', 'derivative'],
  ['无关标识符', 'mainFrame component']
]

function selfCheckNamingRule() {
  // 连写组：命中必须且只能来自第三态 —— 前两态（NFKC 折叠态 / 隐形字符分隔态）双双不命中才算"仅第三态可捕获"
  let seedsConcat = 0
  for (const [label, sample] of NAMING_SEEDS_CONCAT) {
    if (!hitsName(sample)) {
      fail('scripts/ci/check-docs.mjs', `命名纪律规则自检：连写组种子未被捕获（第三态连写规则失效或量词被改窄？）（${label}）`)
      continue
    }
    if (NAME_RE.test(normalizeName(sample)) || NAME_RE.test(separateName(sample))) {
      fail('scripts/ci/check-docs.mjs', `命名纪律规则自检：连写组种子被前两态命中（第三态严格性失效：命中不再唯一来自连写规则）（${label}）`)
      continue
    }
    seedsConcat++
  }
  if (seedsConcat < NAMING_SEEDS_CONCAT_MIN) {
    fail(
      'scripts/ci/check-docs.mjs',
      `命名纪律规则自检：连写组"仅第三态可捕获"的种子不足（${seedsConcat} < 下限 ${NAMING_SEEDS_CONCAT_MIN}；种子行被删或被改宽）`
    )
  }
  // 分隔组：命中必须来自前两态之一（隐形字符面被改窄时，本组会退化为"仅第三态可捕获"而报红）
  let seedsSeparated = 0
  for (const [label, sample] of NAMING_SEEDS_SEPARATED) {
    if (!NAME_RE.test(normalizeName(sample)) && !NAME_RE.test(separateName(sample))) {
      fail('scripts/ci/check-docs.mjs', `命名纪律规则自检：分隔组种子未经前两态捕获（NFKC 折叠 / 隐形字符分隔面被改窄？）（${label}）`)
      continue
    }
    seedsSeparated++
  }
  if (seedsSeparated < NAMING_SEEDS_SEPARATED_MIN) {
    fail(
      'scripts/ci/check-docs.mjs',
      `命名纪律规则自检：分隔组正向种子不足（${seedsSeparated} < 下限 ${NAMING_SEEDS_SEPARATED_MIN}；种子行被删）`
    )
  }
  for (const [label, sample] of NAMING_CONTROLS) {
    if (hitsName(sample)) fail('scripts/ci/check-docs.mjs', `命名纪律规则自检：负对照被误伤（${label}）`)
  }
  return {
    concat: NAMING_SEEDS_CONCAT.length,
    separated: NAMING_SEEDS_SEPARATED.length,
    seedsConcat,
    seedsSeparated,
    controls: NAMING_CONTROLS.length
  }
}

function assertNamingDiscipline() {
  const selfCheck = selfCheckNamingRule()
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
      if (hitsName(line)) fail(`${relPath}:${no}`, '命中命名纪律禁则（旧主仓目录名/同名分支名，含零分隔连写形态；指代一律用占位符）')
    }
  }
  console.log(`         （命名纪律扫描：git ls-files ${gitLsFiles().length} 个路径，实扫文本 ${scanned} 个；豁免 ${exemptSet.size} 个）`)
  console.log(`         （规则自检：连写组 ${selfCheck.seedsConcat}/${selfCheck.concat} 例「仅第三态可捕获」（下限 ${NAMING_SEEDS_CONCAT_MIN}）；分隔组 ${selfCheck.seedsSeparated}/${selfCheck.separated} 例经前两态捕获（下限 ${NAMING_SEEDS_SEPARATED_MIN}）；负对照 ${selfCheck.controls} 全不误伤）`)
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
  ⑤ 命名纪律禁则（git ls-files 全量；匹配三态 = NFKC + 隐形/零宽字符双态 + 去分隔符连写，带词首守卫，
     断言内自带正向种子/负对照自检，种子拆「连写组 / 分隔组」两组并各设数量下限；唯一豁免 = 3 个
     spike 脱敏脚本的匹配源正则。**已知缺口**：NFKC 不折叠的同形字母（西里尔/希腊等）可穿过，详见
     scripts/ci/check-docs.mjs 断言 ⑤ 段注释末尾的缺口登记）
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
