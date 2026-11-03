#!/usr/bin/env node
/**
 * check-d6-sync.mjs —— D6（AI 对话复用指南）同步复核门禁
 *
 * 目标（轻量，只证明"有人工确认"，不证明语义正确）：
 *   本次暂存区出现 **AI 对话生产代码与核心契约**（block 面）时，要求同一笔提交里
 *   `docs/delivery/d6-review.json` **新增**一条与当前指纹匹配的确认记录：
 *     - status=synced       本批已同时更新 D6 正文（门禁核对 D6 文件确实在暂存区）
 *     - status=not-affected 本次改动不影响 D6 描述的迁移面，reason 必须写清为什么
 *   测试 / 模拟设施只告警（warn），文档（doc）与 CI/脚本/构建配置（infra）以及业务文件
 *   （other）都不阻断。
 *
 * 指纹口径（只覆盖"本次暂存的 block 文件"）：
 *   sha256( 规则版本 + 排序后的 `状态 blob 路径` 行 )
 *   —— 改内容（blob 变）、增删 block 文件（集合变）、改分类规则（须升 RULE_VERSION）都会让旧确认失效；
 *      `docs/delivery/d6-review.json` 自身不参与指纹计算。
 *   **确认必须本次新增**：HEAD 中已存在的同指纹记录不许复用（防止历史确认被反复套用）。
 *
 * 退出码：0 = 无需确认或确认有效；1 = 需要确认而未满足（提交应被阻止）；2 = 输入/环境错误
 *         （d6-review.json 非法 JSON、reviews 结构错误、version 缺失或不为 1、非 git 仓库等，fail-closed）。
 *
 * 局限（写在文档里，也必须在这里说清）：
 *   本门禁无法验证 `reason` 的真实性，也无法判断 D6 内容是否与代码语义一致 —— 它只能保证
 *   "改 block 面的人留下了一条当次专用的书面确认"。`git commit --no-verify` 会跳过本检查；
 *   没装 Node 的环境也会跳过（输出明确告警）。CI / 人工复核仍是必要补充。
 *
 * 用法：
 *   node scripts/ci/check-d6-sync.mjs [--root <仓库根>]   # 默认按 git 暂存区检查
 *   node scripts/ci/check-d6-sync.mjs --help
 *
 * 零第三方依赖：只用 Node 内置模块与 git 命令（与 scripts/ci 其他门禁同口径）。
 */

import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const RULE_VERSION = 'd6-sync/v1'
export const REVIEW_VERSION = 1
export const REVIEW_PATH = 'docs/delivery/d6-review.json'
export const D6_DOC_PATH = 'docs/delivery/06-新项目复用与落地指南.md'

const ZERO_SHA = '0'.repeat(40)
const MIN_REASON_CHARS = 8
const GENERIC_REASONS = new Set([
  'n/a', 'na', 'none', 'nil', 'null', 'no', '-', '--', 'ok', 'okay',
  'todo', 'tbd', '待补', '待定', '待确认', '未确认', '无', '暂无', '无影响', '不影响', '同上', '见上'
])

// ── 路径分类 ────────────────────────────────────────────────────────────────
// 顺序：先判"测试 / 模拟设施 / 多语言共享面"（warn，永不阻断），再判 block。
// 这样 `frontend/src/utils/ai-identity.test.ts` 这类文件落在 warn，而不是被 ai-* 前缀带进 block。
// 改动本表的 block / warn 面**必须同时升 RULE_VERSION**，让旧确认失效。

const WARN_RULES = [
  {
    id: 'backend-ai-tests',
    label: 'backend AI / ID / SSE tests (not part of the migration asset)',
    match: (p) => /^backend\/src\/test\/java\/com\/example\/configmgr\/(ai|common\/id|common\/sse)\//.test(p),
  },
  {
    id: 'frontend-tests',
    label: 'frontend unit tests (__tests__ / *.test / *.spec)',
    match: (p) => p.startsWith('frontend/') && (/(^|\/)__tests__\//.test(p) || /\.(test|spec)\.[cm]?[jt]sx?$/.test(p)),
  },
  {
    id: 'frontend-e2e',
    label: 'frontend E2E / harness / domain-specific stub (not migrated)',
    match: (p) => p.startsWith('frontend/e2e/'),
  },
  {
    id: 'ci-sim',
    label: 'CI upstream stub and E2E facilities (not migrated)',
    match: (p) =>
      p.startsWith('scripts/ci/stub-') ||
      p === 'scripts/verify-e2e.ps1' ||
      p === 'scripts/b23l1-dual-instance.ps1' ||
      p === 'scripts/chat-panel-smoke.mjs',
  },
  {
    id: 'backend-i18n-props',
    label: 'backend error-code messages (key set bound to common/ErrorCodes, see D6 §6.8)',
    match: (p) => /^backend\/src\/main\/resources\/messages[^/]*\.properties$/.test(p),
  },
  {
    id: 'frontend-locale-store',
    label: 'frontend locale switching wiring (see D6 §6.8, three things in one commit)',
    match: (p) => p === 'frontend/src/stores/locale.ts',
  },
]

const BLOCK_RULES = [
  {
    id: 'backend-ai-runtime',
    label: 'backend AI runtime (ai/**)',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/ai/'),
  },
  {
    id: 'backend-ai-tools',
    label: 'tool disclosure list (resources/ai/**; changing a description affects the prompt cache)',
    match: (p) => p.startsWith('backend/src/main/resources/ai/'),
  },
  {
    id: 'backend-id',
    label: 'session ID issuance (common/id/**)',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/common/id/'),
  },
  {
    id: 'backend-sse',
    label: 'SSE write budget (common/sse/**)',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/common/sse/'),
  },
  {
    id: 'frontend-ai-panel',
    label: 'AI panel (components/AiPanel/**)',
    match: (p) => p.startsWith('frontend/src/components/AiPanel/'),
  },
  {
    id: 'frontend-ai-store',
    label: 'AI conversation store (stores/ai.ts)',
    match: (p) => p === 'frontend/src/stores/ai.ts',
  },
  {
    id: 'frontend-ai-api',
    label: 'AI API / SSE client / identity headers (api)',
    match: (p) =>
      p === 'frontend/src/api/ai.ts' ||
      p === 'frontend/src/api/aiConfig.ts' ||
      p === 'frontend/src/api/sse.ts' ||
      p === 'frontend/src/api/http.ts',
  },
  {
    id: 'frontend-ai-types',
    label: 'AI / SSE frame / tool types',
    match: (p) =>
      p === 'frontend/src/types/ai.ts' || p === 'frontend/src/types/sse.ts' || p === 'frontend/src/types/tools.ts',
  },
  {
    id: 'frontend-ai-utils',
    label: 'AI identity / tool feed-back / context accounting helpers',
    match: (p) =>
      p.startsWith('frontend/src/utils/ai-') ||
      p === 'frontend/src/utils/frontend-tools.ts' ||
      p === 'frontend/src/utils/context-usage.ts' ||
      p === 'frontend/src/utils/parts.ts',
  },
  {
    id: 'frontend-locales',
    label: 'frontend i18n (locales/**; AI key set ships with the business section)',
    match: (p) => p.startsWith('frontend/src/locales/'),
  },
]

const INFRA_PREFIXES = ['.github/', 'scripts/', 'spike/', 'test-results/']
const INFRA_BASENAMES = new Set([
  'pom.xml',
  'package.json',
  'package-lock.json',
  'pnpm-lock.yaml',
  'yarn.lock',
  'tsconfig.json',
  'tsconfig.node.json',
  'vite.config.ts',
  '.gitignore',
  '.gitattributes',
  '.gitmodules',
  '.editorconfig',
  '.env.example',
])
const INFRA_BASENAME_PATTERNS = [
  /^tsconfig\..*\.json$/,
  /^vite\.config\./,
  /^application.*\.ya?ml$/,
  /^Dockerfile/,
  /^docker-compose.*\.ya?ml$/,
]

export function normalizePath(filePath) {
  return String(filePath).replace(/\\/g, '/').replace(/^\.\//, '')
}

function isInfra(p) {
  if (INFRA_PREFIXES.some((prefix) => p.startsWith(prefix))) return true
  const base = p.slice(p.lastIndexOf('/') + 1)
  if (INFRA_BASENAMES.has(base)) return true
  return INFRA_BASENAME_PATTERNS.some((re) => re.test(base))
}

/** 单文件分类：block / warn / doc / infra / other（other 即"非 AI 面文件，忽略"）。 */
export function classify(filePath) {
  const p = normalizePath(filePath)
  for (const rule of WARN_RULES) {
    if (rule.match(p)) return { category: 'warn', ruleId: rule.id, label: rule.label }
  }
  for (const rule of BLOCK_RULES) {
    if (rule.match(p)) return { category: 'block', ruleId: rule.id, label: rule.label }
  }
  if (p.startsWith('docs/') || p.endsWith('.md')) return { category: 'doc', ruleId: 'docs', label: 'documentation' }
  if (isInfra(p)) return { category: 'infra', ruleId: 'infra', label: 'CI / scripts / build config' }
  return { category: 'other', ruleId: 'other', label: 'non-AI file (never blocks)' }
}

export function classifyChanges(changes = []) {
  const grouped = { block: [], warn: [], doc: [], infra: [], other: [] }
  for (const change of changes) {
    const p = normalizePath(change.path)
    const { category, ruleId, label } = classify(p)
    grouped[category].push({
      path: p,
      status: change.status || 'M',
      blob: change.blob || '',
      category,
      ruleId,
      label,
    })
  }
  return grouped
}

// ── 指纹与确认记录 ──────────────────────────────────────────────────────────

/** 指纹只取 block 文件：状态 + blob + 路径（排序后）与规则版本；d6-review.json 不在其中。 */
export function computeFingerprint(blockChanges = []) {
  const lines = blockChanges
    .map((c) => `${c.status || 'M'} ${c.blob || ZERO_SHA} ${normalizePath(c.path)}`)
    .sort()
  const hash = createHash('sha256')
  hash.update(`${RULE_VERSION}\n`)
  for (const line of lines) hash.update(`${line}\n`)
  return `sha256:${hash.digest('hex')}`
}

export class GateInputError extends Error {}

export function parseReview(text) {
  let data
  try {
    data = JSON.parse(text)
  } catch (e) {
    throw new GateInputError(`docs/delivery/d6-review.json is not valid JSON (${e.message})`)
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new GateInputError('docs/delivery/d6-review.json must be a JSON object at the top level')
  }
  if (data.version !== REVIEW_VERSION) {
    throw new GateInputError(`version must be ${REVIEW_VERSION} (current: ${JSON.stringify(data.version)})`)
  }
  if (data.reviews === undefined) return { reviews: [] }
  if (!Array.isArray(data.reviews)) throw new GateInputError('reviews must be an array')
  return { reviews: data.reviews }
}

/** not-affected 的"具体理由"判据：去空白后 ≥8 字，且不是常见占位词。 */
export function isConcreteReason(reason) {
  if (typeof reason !== 'string') return false
  const value = reason.trim()
  if (value.length < MIN_REASON_CHARS) return false
  return !GENERIC_REASONS.has(value.toLowerCase())
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function stableEntry(entry) {
  const keys = Object.keys(entry).sort()
  return JSON.stringify(keys.map((key) => [key, entry[key]]))
}

function validateEntry(entry, { d6Updated }) {
  const problems = []
  if (entry.status !== 'synced' && entry.status !== 'not-affected') {
    problems.push(`status must be synced or not-affected (current: ${JSON.stringify(entry.status)})`)
  }
  if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
    problems.push('reason must not be empty: explain how this change relates to D6')
  }
  if (entry.status === 'synced' && !d6Updated) {
    // 注意：文案里的「同批暂存」被 scripts/ci/check-d6-sync.test.mjs 按子串断言，改动前先改测试。
    problems.push(`status=synced 要求同批暂存 ${D6_DOC_PATH}`)
  }
  if (entry.status === 'not-affected' && !isConcreteReason(entry.reason)) {
    problems.push(`status=not-affected needs a concrete reason (>=${MIN_REASON_CHARS} chars after trimming, and not a placeholder such as n/a / 无 / 待补)`)
  }
  return problems
}

/**
 * 纯函数门禁判定：输入暂存变更集与 d6-review.json 文本，输出退出码与解释。
 * changes: [{ path, status, blob }]；reviewText 必须是**暂存区（索引）版本**的文本。
 */
export function evaluate({ changes = [], reviewText = '', headReviewText = '' } = {}) {
  const grouped = classifyChanges(changes)
  const result = {
    exitCode: 0,
    kind: 'no-block',
    fingerprint: null,
    block: grouped.block,
    warn: grouped.warn,
    doc: grouped.doc,
    infra: grouped.infra,
    other: grouped.other,
    reviewStaged: false,
    d6Updated: false,
    problems: [],
    entry: null,
  }

  if (result.block.length === 0) return result

  result.fingerprint = computeFingerprint(result.block)
  result.reviewStaged = changes.some((c) => normalizePath(c.path) === REVIEW_PATH && (c.status || 'M') !== 'D')
  result.d6Updated = changes.some((c) => normalizePath(c.path) === D6_DOC_PATH && (c.status || 'M') !== 'D')

  if (!result.reviewStaged) {
    result.exitCode = 1
    result.kind = 'review-not-staged'
    result.problems.push(`${result.block.length} block file(s) are staged but ${REVIEW_PATH} was not updated in this commit`)
    return result
  }

  let review
  try {
    review = parseReview(reviewText)
  } catch (e) {
    if (!(e instanceof GateInputError)) throw e
    result.exitCode = 2
    result.kind = 'bad-json'
    result.problems.push(e.message)
    return result
  }

  // HEAD 版本用于"必须本次新增"判定；HEAD 坏 JSON 不阻断（本次的坏 JSON 已在上面判 2）
  let headEntries = []
  if (headReviewText) {
    try {
      headEntries = parseReview(headReviewText).reviews.filter(isPlainObject)
    } catch {
      headEntries = []
    }
  }
  const headKeys = new Set(headEntries.map(stableEntry))

  const sameFingerprint = review.reviews.filter(
    (entry) => isPlainObject(entry) && entry.fingerprint === result.fingerprint
  )

  if (sameFingerprint.length === 0) {
    result.exitCode = 1
    result.kind = 'no-confirmation'
    result.problems.push(
      review.reviews.length === 0
        ? 'reviews is empty: a block change requires a confirmation added in this commit'
        : 'no existing record matches the current fingerprint (file content or the block file set changed, so old confirmations are invalid)'
    )
    return result
  }

  const fresh = sameFingerprint.filter((entry) => !headKeys.has(stableEntry(entry)))
  if (fresh.length === 0) {
    result.exitCode = 1
    result.kind = 'reused-confirmation'
    result.problems.push('the matching confirmation already exists in the previous commit (HEAD): a confirmation must be added in this commit and must not reuse history')
    return result
  }

  const problems = []
  for (const entry of fresh) {
    const entryProblems = validateEntry(entry, { d6Updated: result.d6Updated })
    if (entryProblems.length === 0) {
      result.exitCode = 0
      result.kind = 'ok'
      result.entry = entry
      return result
    }
    problems.push(...entryProblems)
  }

  result.exitCode = 1
  result.kind = 'invalid-confirmation'
  result.problems = problems
  return result
}

// ── git 读取（CLI 用；测试直接调纯函数，不依赖仓库） ────────────────────────

function git(args, cwd, options = {}) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options,
  })
}

export function readStagedChanges(repo) {
  const raw = git(['diff', '--cached', '--name-status', '-z', '--diff-filter=ACDMRTU', '--no-color', '--no-ext-diff'], repo)
  const fields = raw.split('\0')
  const changes = []
  let i = 0
  while (i < fields.length) {
    const status = fields[i]
    if (!status) {
      i += 1
      continue
    }
    const letter = status.charAt(0)
    if (letter === 'R' || letter === 'C') {
      changes.push({ status: letter, path: fields[i + 2], fromPath: fields[i + 1] })
      i += 3
    } else {
      changes.push({ status: letter, path: fields[i + 1] })
      i += 2
    }
  }

  const blobByPath = new Map()
  const indexListing = git(['ls-files', '-s', '-z'], repo).split('\0')
  for (const record of indexListing) {
    if (!record) continue
    const tab = record.indexOf('\t')
    if (tab < 0) continue
    const [mode, sha, stage] = record.slice(0, tab).split(' ')
    if (!sha || stage !== '0') continue
    blobByPath.set(normalizePath(record.slice(tab + 1)), sha)
  }
  for (const change of changes) {
    change.blob = blobByPath.get(normalizePath(change.path)) || ZERO_SHA
    change.path = normalizePath(change.path)
  }
  return changes
}

function showFromIndex(repo, relPath) {
  return git(['show', `:${relPath}`], repo)
}

function showFromHead(repo, relPath) {
  try {
    return git(['show', `HEAD:${relPath}`], repo)
  } catch {
    return ''
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────────

function sorted(files) {
  return [...files].sort((a, b) => a.path.localeCompare(b.path))
}

function confirmationTemplate(fingerprint) {
  return {
    fingerprint,
    status: 'synced',
    reason: '<describe what this commit changes and whether D6 needs an update, and why>',
    reviewedAt: new Date().toISOString().slice(0, 10),
    reviewer: '<name or employee id>',
  }
}

export function describe(result) {
  const lines = [`D6 sync review gate (check-d6-sync, rule version ${RULE_VERSION})`, '']

  if (result.block.length === 0) {
    if (result.warn.length > 0) {
      lines.push(`WARN ${result.warn.length} staged file(s) are test/simulation infrastructure or shared i18n surface; warn only, not blocking:`)
      for (const file of sorted(result.warn)) lines.push(`    ${file.status} ${file.path}   <- ${file.label}`)
      lines.push('')
    }
    lines.push('OK no AI-conversation core file (block surface) is staged; no D6 review confirmation needed')
    return lines
  }

  lines.push(`Staged block files (${result.block.length}; each needs a valid confirmation added in this commit):`)
  for (const file of sorted(result.block)) lines.push(`    ${file.status} ${file.path}   <- ${file.label}`)

  if (result.warn.length > 0) {
    lines.push('')
    lines.push(`WARN ${result.warn.length} more staged file(s) are test/simulation infrastructure or shared i18n surface; warn only, not blocking:`)
    for (const file of sorted(result.warn)) lines.push(`    ${file.status} ${file.path}   <- ${file.label}`)
  }

  lines.push('')
  lines.push(`fingerprint: ${result.fingerprint}`)
  lines.push('      = rule version + "status blob path" of the staged block files (d6-review.json itself is excluded)')
  lines.push('')

  if (result.kind === 'ok') {
    lines.push(`OK new confirmation is valid: status=${result.entry.status}`)
    lines.push(`    reason: ${String(result.entry.reason).trim()}`)
    lines.push(`OK D6 review passed (${REVIEW_PATH} + ${result.block.length} block file(s) in the same commit)`)
    lines.push('')
    lines.push('Note: this gate only proves that a confirmation was recorded for this change, not that the D6 content matches the code.')
    return lines
  }

  if (result.kind === 'bad-json') {
    lines.push('FAIL docs/delivery/d6-review.json is structurally unusable (treated as an environment/input error, rc=2):')
    for (const problem of result.problems) lines.push(`    - ${problem}`)
    lines.push('')
    lines.push('A broken JSON file is never treated as "no confirmation": fix it into valid JSON (version: 1, reviews array) and commit again.')
    return lines
  }

  const headlines = {
    'review-not-staged': `FAIL ${REVIEW_PATH} was not updated in this commit: block changes need a confirmation added in the same commit`,
    'no-confirmation': 'FAIL no confirmation record matches the current fingerprint',
    'reused-confirmation': 'FAIL the matching confirmation was not added in this commit',
    'invalid-confirmation': 'FAIL the confirmation record does not meet the requirements',
  }
  lines.push(headlines[result.kind] || 'FAIL D6 sync review did not pass')
  for (const problem of result.problems) lines.push(`    - ${problem}`)

  if (result.kind === 'invalid-confirmation' && result.entry) {
    lines.push('')
    lines.push(`current record: ${JSON.stringify(result.entry)}`)
  }

  lines.push('')
  lines.push(`How to fix: ADD the record below to the reviews array of ${REVIEW_PATH} and stage that file too:`)
  lines.push('')
  for (const line of JSON.stringify(confirmationTemplate(result.fingerprint), null, 2).split('\n')) {
    lines.push(`  ${line}`)
  }
  lines.push('')
  lines.push('  status, pick one:')
  lines.push(`    synced       = this commit also updates ${D6_DOC_PATH} (the gate checks it is staged)`)
  lines.push('    not-affected = this change does not touch the migration surface described by D6; explain why in reason (not n/a or a placeholder)')
  lines.push('')
  lines.push('  Changing the fingerprint invalidates it: later edits to these files (or adding/removing block files) need a new confirmation.')
  lines.push('  This gate does not judge whether reason is true, nor whether the D6 content is correct; CI / human review remain necessary.')
  return lines
}

function printHelp() {
  console.log(`D6 sync review gate (check-d6-sync, rule version ${RULE_VERSION})

usage:
  node scripts/ci/check-d6-sync.mjs [--root <repo root>] [--staged]
  node scripts/ci/check-d6-sync.mjs --help

verdict (staged index only):
  block  backend ai/**, common/id, common/sse, resources/ai/**,
         frontend AiPanel / stores/ai.ts / api(ai|aiConfig|sse|http) / types(ai|sse|tools) /
         utils(ai-*|frontend-tools|context-usage|parts) / locales
         -- needs a confirmation with the matching fingerprint added in this commit:
            synced (D6 updated in the same commit) or not-affected (concrete reason)
  warn   tests / E2E / stubs / harness / backend messages_*.properties / stores/locale.ts -- warn only
  doc    docs/** and *.md; infra scripts/**, .github/**, build config; all other files are ignored

fingerprint: sha256(rule version + sorted "status blob path"), staged block files only; d6-review.json itself excluded.
exit code: 0 pass (including "no confirmation needed") / 1 confirmation needed but missing / 2 input or environment error
           (bad JSON, missing version or version != 1, not a git repo, ...).

limits: a confirmation is a written human commitment; the gate does not verify its truth, nor that the D6 content is correct;
        git commit --no-verify and machines without Node skip this check (a warning is printed when skipped).`)
}

// ── 入口 ────────────────────────────────────────────────────────────────────

function resolveRepoRoot(explicitRoot) {
  if (explicitRoot) return path.resolve(explicitRoot)
  return git(['rev-parse', '--show-toplevel'], process.cwd()).trim()
}

function main(argv) {
  if (argv.includes('--help') || argv.includes('-h')) {
    printHelp()
    return 0
  }

  let root = ''
  const unknown = []
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--root') {
      root = argv[i + 1] || ''
      i += 1
    } else if (arg !== '--staged') {
      unknown.push(arg)
    }
  }
  if (unknown.length > 0 || (argv.includes('--root') && !root)) {
    console.error(`[d6-sync] FAIL unrecognised argument: ${unknown.join(' ') || '--root is missing its value'} (see --help)`)
    return 2
  }

  try {
    const repo = resolveRepoRoot(root)
    const changes = readStagedChanges(repo)
    const reviewStaged = changes.some((c) => c.path === REVIEW_PATH && c.status !== 'D')
    const reviewText = reviewStaged ? showFromIndex(repo, REVIEW_PATH) : ''
    const headReviewText = showFromHead(repo, REVIEW_PATH)
    const result = evaluate({ changes, reviewText, headReviewText })
    for (const line of describe(result)) console.log(line)
    return result.exitCode
  } catch (e) {
    console.error(`[d6-sync] FAIL check could not complete (treated as an error, rc=2): ${e.message}`)
    console.error('    Make sure the current directory (or --root) is a git repository, git is available, and the index is readable.')
    return 2
  }
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : ''
const self = path.resolve(fileURLToPath(import.meta.url))
if (entry && (entry === self || entry.toLowerCase() === self.toLowerCase())) {
  process.exitCode = main(process.argv.slice(2))
}
