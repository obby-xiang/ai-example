#!/usr/bin/env node
/**
 * check-d6-sync.mjs —— D6 同步复核 + 统一开发规范审查门禁
 *
 * 默认模式的 D6 规则保持不变；--standards 模式覆盖全部暂存变更，并要求逐文件、七规范域、
 * 规格变化和真实证据边界的当次记录。--standards --base <sha> --head <sha> 用于 CI 逐提交复核。
 * 统一规范模式在 pre-commit 中缺少 Node 或脚本时失败关闭，不沿用 D6 的告警跳过。
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
export const STANDARDS_RULE_VERSION = 'development-standards/v2'
export const STANDARDS_REVIEW_VERSION = 1
export const STANDARDS_REVIEW_PATH = 'docs/delivery/development-standards-review.json'
export const STANDARDS_DOC_PATH = 'docs/delivery/development-standards.md'
export const STANDARDS_DOMAINS = Object.freeze([
  'dependency-reuse',
  'java-api-style',
  'logging-exception-safety',
  'toc-interaction',
  'protocol-redis-concurrency',
  'unicode-stream-parsing',
  'verification-review',
])

const ZERO_SHA = '0'.repeat(40)
const ZERO_MODE = '000000'
const MIN_REASON_CHARS = 8
const GENERIC_REASONS = new Set([
  'n/a', 'na', 'none', 'nil', 'null', 'no', '-', '--', 'ok', 'okay',
  'todo', 'tbd', '待补', '待定', '待确认', '未确认', '无', '暂无', '无影响', '不影响', '同上', '见上'
])
const STANDARDS_GENERIC_REASONS = new Set([
  ...GENERIC_REASONS,
  'checked', 'not-affected', 'not applicable', 'no impact', 'same as above',
  '已审查', '已检查', '符合规范', '无异常', '不涉及', '无需修改', '按规范执行',
  'describe the per-file, per-hunk review and its actual evidence boundary',
  'describe every hunk reviewed in this file',
  'give a concrete applicability reason; n/a is not accepted',
  'describe the standards change',
])
const STANDARDS_GENERIC_CLAUSES = new Set([
  '本批已全面审查', '本次已全面审查', '已全面审查', '全面审查',
  '符合规范要求', '符合规范', '按规范执行', '按规范要求执行',
  '本次已检查无异常按规范执行', '本次已检查无异常', '已检查无异常', '无异常',
  '全部变更均不涉及其他规范领域', '全部变更不涉及其他规范领域', '不涉及其他规范领域',
  '本批已审查', '本次已审查', '已审查', '已检查', '本批已检查', '本次已检查',
  '本批已核查', '本次已核查', '已核查', '本批已完成审查', '本次已完成审查', '已完成审查',
  '本批已全面检查', '本次已全面检查', '已全面检查', '全部已审查', '全部已检查',
  '无影响', '不影响', '不涉及',
])
const STANDARDS_SPECIFIC_REASON = /(?:hunk|文件|指纹|fingerprint|merge|tree|ledger|reviews?\b|base|head|mode|fromPath|blob|sha(?:256)?|json|node|ci|api|sse|redis|unicode|utf-?8|specChanges|scripts?[\\/]|[\w-]+\.(?:mjs|java|ts|tsx|vue|json|ya?ml|md|sh)\b|状态机|并发|集合|数组|方法|依赖|序列化|日志|异常|模型|卡片|滚动|边距|白名单|父提交|暂存区|路径|规格变化|代理对|字素簇)/i
const STANDARDS_DOC_EXTENSIONS = new Set(['.md', '.markdown', '.rst', '.adoc', '.asciidoc'])

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

export function computeChangeFingerprint(changes = [], ruleVersion) {
  const lines = changes
    .map((c) => `${c.status || 'M'} ${c.blob || ZERO_SHA} ${normalizePath(c.path)}`)
    .sort()
  const hash = createHash('sha256')
  hash.update(`${ruleVersion}\n`)
  for (const line of lines) hash.update(`${line}\n`)
  return `sha256:${hash.digest('hex')}`
}

/** 指纹只取 block 文件：状态 + blob + 路径（排序后）与规则版本；d6-review.json 不在其中。 */
export function computeFingerprint(blockChanges = []) {
  return computeChangeFingerprint(blockChanges, RULE_VERSION)
}

export function computeStandardsFingerprint(changes = []) {
  const tuples = changes
    .filter((c) => String(c.path) !== STANDARDS_REVIEW_PATH)
    .map((c) => JSON.stringify([
      c.status || 'M',
      c.mode || ZERO_MODE,
      c.fromPath ? String(c.fromPath) : '',
      String(c.path),
      c.blob || ZERO_SHA,
    ]))
    .sort()
  const hash = createHash('sha256')
  hash.update(`${STANDARDS_RULE_VERSION}\n`)
  for (const tuple of tuples) hash.update(`${tuple}\n`)
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

function isGenericReasonCombination(value) {
  if (STANDARDS_SPECIFIC_REASON.test(value)) return false
  const clauses = value.split(/(?:，|,|。|；|;|、|以及|并且|而且|同时|然后|且|并|和|与|\s+)/).map((item) => item.trim()).filter(Boolean)
  return clauses.length > 0 && clauses.every((item) => STANDARDS_GENERIC_CLAUSES.has(item))
}

function isStandardsPlaceholder(value) {
  if (typeof value !== 'string') return true
  const trimmed = value.trim()
  if (!trimmed || /^<[^>]*>$/.test(trimmed)) return true
  const normalized = trimmed.replace(/\s+/g, ' ').toLowerCase()
  return STANDARDS_GENERIC_REASONS.has(normalized) || isGenericReasonCombination(normalized)
}

export function isConcreteStandardsReason(reason) {
  return isConcreteReason(reason) && !isStandardsPlaceholder(reason)
}

export function isStandardsDocumentation(filePath) {
  const p = String(filePath)
  const extension = path.extname(p).toLowerCase()
  if (p.startsWith('docs/')) return STANDARDS_DOC_EXTENSIONS.has(extension) || extension === '.txt'
  if (STANDARDS_DOC_EXTENSIONS.has(extension)) return true
  const base = p.slice(p.lastIndexOf('/') + 1)
  return /^README(?:\.[^/]*)?$/i.test(base) && (extension === '' || extension === '.txt')
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue)
  if (!isPlainObject(value)) return value
  return Object.keys(value).sort().map((key) => [key, stableValue(value[key])])
}

function stableEntry(entry) {
  return JSON.stringify(stableValue(entry))
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

// ── 统一开发规范审查：全变更指纹、逐文件与七域确认 ─────────────────────────

export function parseStandardsReview(text) {
  let data
  try {
    data = JSON.parse(text)
  } catch (e) {
    throw new GateInputError(`${STANDARDS_REVIEW_PATH} is not valid JSON (${e.message})`)
  }
  if (!isPlainObject(data)) {
    throw new GateInputError(`${STANDARDS_REVIEW_PATH} must be a JSON object at the top level`)
  }
  if (data.version !== STANDARDS_REVIEW_VERSION) {
    throw new GateInputError(`version must be ${STANDARDS_REVIEW_VERSION} (current: ${JSON.stringify(data.version)})`)
  }
  if (!Array.isArray(data.reviews)) throw new GateInputError('reviews must be present and must be an array')
  return { reviews: data.reviews }
}

function validateStandardsBase(entry, covered) {
  const problems = []
  if (entry.status !== 'reviewed' && entry.status !== 'not-affected') {
    problems.push('status must be reviewed or not-affected')
  }
  if (!isConcreteStandardsReason(entry.reason)) problems.push('reason must give a concrete, non-template per-hunk explanation')
  if (isStandardsPlaceholder(entry.reviewer)) {
    problems.push('reviewer must identify the actual reviewer or independent review context')
  }
  const timestamp = typeof entry.reviewedAt === 'string' ? entry.reviewedAt : ''
  const isoWithZone = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
  if (!isoWithZone.test(timestamp) || Number.isNaN(Date.parse(timestamp))) {
    problems.push('reviewedAt must be an ISO 8601 date-time with an explicit timezone')
  }
  if (entry.status === 'not-affected' && covered.some((c) => !isStandardsDocumentation(c.path))) {
    problems.push('status=not-affected is allowed only when every covered change is whitelist documentation')
  }
  return problems
}

function validateSpecChanges(entry, covered) {
  if (!Array.isArray(entry.specChanges)) return ['specChanges must be an array (use [] when no specification changed)']
  const problems = []
  for (const [index, item] of entry.specChanges.entries()) {
    if (!isPlainObject(item)) {
      problems.push(`specChanges[${index}] must be an object with change, reason and impact`)
      continue
    }
    for (const field of ['change', 'reason', 'impact']) {
      if (!isConcreteStandardsReason(item[field])) {
        problems.push(`specChanges[${index}].${field} must be concrete and non-template`)
      }
    }
  }
  if (covered.some((c) => String(c.path) === STANDARDS_DOC_PATH) && entry.specChanges.length === 0) {
    problems.push(`changing ${STANDARDS_DOC_PATH} requires at least one specChanges item`)
  }
  return problems
}

function validateStandardsFiles(entry, covered) {
  const problems = []
  if (!Array.isArray(entry.files)) return ['files must be an array covering every changed file except the review ledger']
  const expected = new Map(covered.map((c) => [String(c.path), c]))
  const seen = new Set()
  for (const file of entry.files) {
    if (!isPlainObject(file) || typeof file.path !== 'string') {
      problems.push('every files item must be an object with a path')
      continue
    }
    const filePath = file.path
    if (!expected.has(filePath)) problems.push(`files contains an unchanged or duplicate path: ${filePath}`)
    if (seen.has(filePath)) problems.push(`files contains a duplicate path: ${filePath}`)
    seen.add(filePath)
    if (file.status !== 'checked' && file.status !== 'not-affected') {
      problems.push(`files[${filePath}].status must be checked or not-affected`)
    }
    if (file.status === 'not-affected' && !isStandardsDocumentation(filePath)) {
      problems.push(`files[${filePath}] uses not-affected for a non-whitelist-documentation change`)
    }
    if (!isConcreteStandardsReason(file.reason)) problems.push(`files[${filePath}] needs a concrete, non-template per-hunk reason`)
  }
  for (const filePath of expected.keys()) {
    if (!seen.has(filePath)) problems.push(`files is missing the changed path: ${filePath}`)
  }
  return problems
}

function validateStandardsDomains(entry) {
  const problems = []
  if (!isPlainObject(entry.domains)) return ['domains must be an object containing all seven standards domains']
  for (const key of Object.keys(entry.domains)) {
    if (!STANDARDS_DOMAINS.includes(key)) problems.push(`unknown standards domain: ${key}`)
  }
  for (const domain of STANDARDS_DOMAINS) {
    const check = entry.domains[domain]
    if (!isPlainObject(check)) {
      problems.push(`domains.${domain} must be an object`)
      continue
    }
    if (check.status !== 'checked' && check.status !== 'not-affected') {
      problems.push(`domains.${domain}.status must be checked or not-affected`)
    }
    if (!isConcreteStandardsReason(check.reason)) problems.push(`domains.${domain} needs a concrete applicability reason, not a template or generic phrase`)
  }
  if (entry.status === 'reviewed' && !STANDARDS_DOMAINS.some((domain) => entry.domains[domain]?.status === 'checked')) {
    problems.push('status=reviewed requires at least one domain with status=checked')
  }
  return problems
}

export function validateStandardsEntry(entry, changes = []) {
  const covered = changes.filter((c) => String(c.path) !== STANDARDS_REVIEW_PATH)
  return [
    ...validateStandardsBase(entry, covered),
    ...validateSpecChanges(entry, covered),
    ...validateStandardsFiles(entry, covered),
    ...validateStandardsDomains(entry),
  ]
}

function parseStandardsGateInput(result, text, source, kind) {
  try {
    return parseStandardsReview(text).reviews
  } catch (e) {
    if (!(e instanceof GateInputError)) throw e
    result.exitCode = 2
    result.kind = kind
    result.problems.push(`${source}: ${e.message}`)
    return null
  }
}

function validateStandardsAppend(result, current, parent) {
  if (!parent) return true
  if (current.length <= parent.length) {
    result.problems.push('the current ledger must strictly append at least one record after the parent ledger')
  } else {
    for (let i = 0; i < parent.length; i += 1) {
      if (stableEntry(current[i]) !== stableEntry(parent[i])) {
        result.problems.push(`parent ledger history was edited or reordered at reviews[${i}]; append-only is required`)
        break
      }
    }
  }
  if (result.problems.length === 0) return true
  result.exitCode = 1
  result.kind = 'history-not-append'
  return false
}

function freshStandardsEntries(result, reviewText, parentReviewText) {
  const current = parseStandardsGateInput(result, reviewText, 'current ledger', 'bad-json')
  if (!current) return []
  const parent = parentReviewText === null
    ? null
    : parseStandardsGateInput(result, parentReviewText, 'parent ledger', 'bad-parent-json')
  if (result.exitCode !== 0 || !validateStandardsAppend(result, current, parent)) return []
  const parentEntries = parent || []
  const parentFingerprints = new Set(parentEntries.filter(isPlainObject).map((entry) => entry.fingerprint))
  const matching = current.filter((entry) => isPlainObject(entry) && entry.fingerprint === result.fingerprint)
  if (matching.length === 0) {
    result.exitCode = 1
    result.kind = 'no-confirmation'
    result.problems.push('no standards review record matches the current all-change fingerprint')
    return []
  }
  const fresh = current.slice(parentEntries.length).filter((entry) =>
    isPlainObject(entry) && entry.fingerprint === result.fingerprint && !parentFingerprints.has(entry.fingerprint))
  if (fresh.length === 0) {
    result.exitCode = 1
    result.kind = 'reused-confirmation'
    result.problems.push('the current fingerprint already exists in the parent ledger; changing other fields does not make it reusable')
  }
  return fresh
}

export function evaluateStandards({ changes = [], reviewText = '', parentReviewText = null } = {}) {
  const result = {
    exitCode: 0,
    kind: 'no-changes',
    fingerprint: computeStandardsFingerprint(changes),
    changes,
    reviewStaged: false,
    problems: [],
    entry: null,
  }
  if (changes.length === 0) return result
  result.reviewStaged = changes.some((c) => String(c.path) === STANDARDS_REVIEW_PATH && (c.status || 'M') !== 'D')
  if (!result.reviewStaged) {
    result.exitCode = 1
    result.kind = 'review-not-staged'
    result.problems.push(`${STANDARDS_REVIEW_PATH} must be updated in the same commit`)
    return result
  }
  const fresh = freshStandardsEntries(result, reviewText, parentReviewText)
  if (result.exitCode !== 0) return result
  for (const entry of fresh) {
    const problems = validateStandardsEntry(entry, changes)
    if (problems.length === 0) {
      result.kind = 'ok'
      result.entry = entry
      return result
    }
    result.problems.push(...problems)
  }
  result.exitCode = 1
  result.kind = 'invalid-confirmation'
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

function parseNameStatus(raw, mapPath = normalizePath) {
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
      changes.push({ status: letter, path: mapPath(fields[i + 2]), fromPath: mapPath(fields[i + 1]) })
      i += 3
    } else {
      changes.push({ status: letter, path: mapPath(fields[i + 1]) })
      i += 2
    }
  }
  return changes
}

function attachBlobs(changes, blobByPath) {
  for (const change of changes) change.blob = blobByPath.get(change.path) || ZERO_SHA
  return changes
}

function treeEntries(raw, mapPath, indexFormat) {
  const entries = new Map()
  for (const record of raw.split('\0')) {
    if (!record || record.indexOf('\t') < 0) continue
    const tab = record.indexOf('\t')
    const fields = record.slice(0, tab).split(' ')
    const stage = indexFormat ? fields[2] : '0'
    const sha = indexFormat ? fields[1] : fields[2]
    if (sha && stage === '0') entries.set(mapPath(record.slice(tab + 1)), { mode: fields[0], sha })
  }
  return entries
}

function stagedEntries(repo, mapPath) {
  return treeEntries(git(['ls-files', '-s', '-z'], repo), mapPath, true)
}

function commitEntries(repo, commit, mapPath) {
  return treeEntries(git(['ls-tree', '-r', '-z', commit], repo), mapPath, false)
}

function stagedBlobs(repo) {
  const blobs = new Map()
  for (const [filePath, entry] of stagedEntries(repo, normalizePath)) blobs.set(filePath, entry.sha)
  return blobs
}

function attachMetadata(changes, entries) {
  for (const change of changes) {
    const entry = entries.get(change.path)
    change.blob = entry?.sha || ZERO_SHA
    change.mode = entry?.mode || ZERO_MODE
    change.fromPath ||= ''
  }
  return changes
}

export function readStagedChanges(repo) {
  const raw = git(['diff', '--cached', '--name-status', '-z', '--diff-filter=ACDMRTU', '--no-color', '--no-ext-diff'], repo)
  return attachBlobs(parseNameStatus(raw), stagedBlobs(repo))
}

export function readStandardsStagedChanges(repo) {
  const raw = git(['diff', '--cached', '--name-status', '-z', '--no-renames', '--diff-filter=ACDMRTU', '--no-color', '--no-ext-diff'], repo)
  return attachMetadata(parseNameStatus(raw, String), stagedEntries(repo, String))
}

function readCommitChanges(repo, commit, parent) {
  const common = ['--name-status', '-z', '--no-renames', '--diff-filter=ACDMRTU', '--no-color', '--no-ext-diff']
  const args = parent
    ? ['diff', ...common, parent, commit]
    : ['diff-tree', '--root', '--no-commit-id', '-r', ...common, commit]
  return attachMetadata(parseNameStatus(git(args, repo), String), commitEntries(repo, commit, String))
}

function showFromCommit(repo, commit, relPath) {
  if (!commit) return null
  const listing = git(['ls-tree', '-z', commit, '--', relPath], repo)
  return listing ? git(['show', `${commit}:${relPath}`], repo) : null
}

function isUnbornHead(repo) {
  const branch = git(['symbolic-ref', '-q', 'HEAD'], repo).trim()
  const existing = git(['for-each-ref', '--format=%(refname)', branch], repo).trim()
  return existing === ''
}

function readStagedParentReview(repo) {
  try {
    git(['rev-parse', '--verify', 'HEAD^{commit}'], repo)
  } catch (e) {
    if (isUnbornHead(repo)) return null
    throw e
  }
  return showFromCommit(repo, 'HEAD', STANDARDS_REVIEW_PATH)
}

function readCommitReview(repo, commit) {
  const parents = git(['rev-list', '--parents', '-n', '1', commit], repo).trim().split(' ').slice(1)
  const parent = parents[0] || ''
  return {
    parents,
    changes: readCommitChanges(repo, commit, parent),
    reviewText: showFromCommit(repo, commit, STANDARDS_REVIEW_PATH),
    parentReviewText: showFromCommit(repo, parent, STANDARDS_REVIEW_PATH),
  }
}

function assertValidRevision(repo, revision, label) {
  if (!revision || /^0{40}$/.test(revision)) throw new GateInputError(`${label} must be a non-zero commit revision`)
  return git(['rev-parse', '--verify', `${revision}^{commit}`], repo).trim()
}

function readRangeCommits(repo, base, head) {
  if (!base) return [assertValidRevision(repo, head, 'head')]
  const baseCommit = assertValidRevision(repo, base, 'base')
  const headCommit = assertValidRevision(repo, head, 'head')
  if (baseCommit === headCommit) return []
  try {
    git(['merge-base', '--is-ancestor', baseCommit, headCommit], repo)
  } catch {
    throw new GateInputError(`base ${baseCommit} is not an ancestor of head ${headCommit}`)
  }
  const commits = git(['rev-list', '--reverse', `${baseCommit}..${headCommit}`], repo).trim().split('\n').filter(Boolean)
  if (commits.length === 0) throw new GateInputError(`empty commit range ${baseCommit}..${headCommit}; only equal commits may be empty`)
  return commits
}

function automaticMergeTree(repo, parents) {
  let raw
  let conflicted = false
  try {
    raw = git(['merge-tree', '--write-tree', parents[0], parents[1]], repo)
  } catch (e) {
    if (e.status !== 1) throw e
    conflicted = true
    raw = String(e.stdout || '')
  }
  const tree = raw.trim().split('\n')[0]
  if (!/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(tree)) {
    throw new GateInputError('git merge-tree did not produce a valid expected tree; failing closed')
  }
  return { tree, conflicted }
}

function isCleanMerge(repo, commit, parents) {
  if (parents.length < 2) return false
  if (parents.length > 2) throw new GateInputError(`octopus merge ${commit} is not supported; failing closed`)
  const tree = git(['rev-parse', `${commit}^{tree}`], repo).trim()
  if (parents.some((parent) => git(['rev-parse', `${parent}^{tree}`], repo).trim() === tree)) return true
  const expected = automaticMergeTree(repo, parents)
  return !expected.conflicted && tree === expected.tree
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

function standardsConfirmationTemplate(result) {
  const covered = result.changes.filter((c) => String(c.path) !== STANDARDS_REVIEW_PATH)
  return {
    fingerprint: result.fingerprint,
    status: 'reviewed',
    reason: '',
    reviewedAt: new Date().toISOString(),
    reviewer: '',
    specChanges: covered.some((c) => c.path === STANDARDS_DOC_PATH) ? [{ change: '', reason: '', impact: '' }] : [],
    files: covered.map((c) => ({
      path: c.path,
      status: 'checked',
      reason: '',
    })),
    domains: Object.fromEntries(STANDARDS_DOMAINS.map((domain) => [domain, {
      status: domain === 'verification-review' ? 'checked' : 'not-affected',
      reason: '',
    }])),
  }
}

export function describeStandards(result) {
  const lines = [`Development standards review gate (rule version ${STANDARDS_RULE_VERSION})`, '']
  if (result.kind === 'no-changes') {
    lines.push('OK no staged changes; no standards review confirmation needed')
    return lines
  }
  const coveredCount = result.changes.filter((c) => String(c.path) !== STANDARDS_REVIEW_PATH).length
  lines.push(`Covered changes: ${coveredCount} (production, test/stress, scripts, CI and docs all included)`)
  lines.push(`fingerprint: ${result.fingerprint}`)
  lines.push(`      = all changes except ${STANDARDS_REVIEW_PATH} itself`, '')
  if (result.kind === 'ok') {
    lines.push(`OK new standards review is valid: status=${result.entry.status}, reviewer=${result.entry.reviewer}`)
    lines.push('Note: checked means human review only; it is not a test, real-interface or Redis PASS.')
    return lines
  }
  const headlines = {
    'review-not-staged': `FAIL ${STANDARDS_REVIEW_PATH} was not updated in this commit`,
    'bad-json': `FAIL ${STANDARDS_REVIEW_PATH} is structurally unusable`,
    'bad-parent-json': 'FAIL the parent ledger could not be read or is structurally invalid',
    'history-not-append': 'FAIL the current ledger is not a strict append-only extension of the parent ledger',
    'no-confirmation': 'FAIL no standards review matches the current fingerprint',
    'reused-confirmation': 'FAIL the current fingerprint already exists in the parent ledger',
    'invalid-confirmation': 'FAIL the standards review record does not meet the requirements',
  }
  lines.push(headlines[result.kind] || 'FAIL standards review did not pass')
  for (const problem of result.problems) lines.push(`    - ${problem}`)
  lines.push('', `How to fix: ADD and complete the record below in ${STANDARDS_REVIEW_PATH}, then stage it:`)
  for (const line of JSON.stringify(standardsConfirmationTemplate(result), null, 2).split('\n')) {
    lines.push(`  ${line}`)
  }
  lines.push('', 'Every changed file and all seven domains need concrete reasons. Any change to the covered staged state needs a new review.')
  return lines
}

function printHelp() {
  console.log(`D6 sync + development standards gates

usage:
  node scripts/ci/check-d6-sync.mjs [--root <repo root>] [--staged]
  node scripts/ci/check-d6-sync.mjs --standards [--root <repo root>] [--staged]
  node scripts/ci/check-d6-sync.mjs --standards [--root <repo root>] [--base <sha>] --head <sha>
  node scripts/ci/check-d6-sync.mjs --help

D6 default mode: staged AI block files need a fresh matching ${REVIEW_PATH} record.
standards mode: every staged change needs an appended matching ${STANDARDS_REVIEW_PATH} record,
  including production, test/stress, scripts, CI and docs. All seven domains and every file need reasons.
  Parent reviews must be a strict prefix; a parent fingerprint cannot be reused.
  --base/--head validates ancestry and checks every introduced commit; only equal commits allow an empty range.
  A two-parent merge reusing a parent tree or the automatic merge-tree result needs no merge-specific record;
  octopus merges fail closed; all other merges need their own appended record.

standards fingerprint: sha256(v2 + sorted JSON [status,mode,fromPath,path,blob]); D6 v1 is unchanged.
The corresponding review ledger is excluded.
exit code: 0 pass / 1 required confirmation missing or invalid / 2 input or environment error.
D6 keeps its existing pre-commit skip behavior without Node; the standards hook fails closed without Node.
A confirmation proves written review only, not semantic correctness or real test/interface/Redis execution.`)
}

// ── 入口 ────────────────────────────────────────────────────────────────────

function resolveRepoRoot(explicitRoot) {
  if (explicitRoot) return path.resolve(explicitRoot)
  return git(['rev-parse', '--show-toplevel'], process.cwd()).trim()
}

function runD6(repo) {
  const changes = readStagedChanges(repo)
  const reviewStaged = changes.some((c) => c.path === REVIEW_PATH && c.status !== 'D')
  const reviewText = reviewStaged ? showFromIndex(repo, REVIEW_PATH) : ''
  const result = evaluate({ changes, reviewText, headReviewText: showFromHead(repo, REVIEW_PATH) })
  for (const line of describe(result)) console.log(line)
  return result.exitCode
}

function runStandardsStaged(repo) {
  const changes = readStandardsStagedChanges(repo)
  const reviewStaged = changes.some((c) => c.path === STANDARDS_REVIEW_PATH && c.status !== 'D')
  const reviewText = reviewStaged ? showFromIndex(repo, STANDARDS_REVIEW_PATH) : ''
  const parentReviewText = readStagedParentReview(repo)
  const result = evaluateStandards({ changes, reviewText, parentReviewText })
  for (const line of describeStandards(result)) console.log(line)
  return result.exitCode
}

function runStandardsCommits(repo, base, head) {
  const commits = readRangeCommits(repo, base, head)
  for (const commit of commits) {
    console.log(`\nStandards review for commit ${commit}`)
    const input = readCommitReview(repo, commit)
    if (isCleanMerge(repo, commit, input.parents)) {
      console.log('OK merge tree reuses a parent tree or the automatic merge-tree result; no merge-specific record required, all introduced commits are still checked')
      continue
    }
    const result = evaluateStandards(input)
    for (const line of describeStandards(result)) console.log(line)
    if (result.exitCode !== 0) return result.exitCode
  }
  console.log(`OK standards review passed for ${commits.length} introduced commit(s)`)
  return 0
}

function parseArguments(argv) {
  const options = { root: '', standards: false, base: '', head: '', unknown: [], missingValue: '' }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--standards') options.standards = true
    else if (arg === '--staged') continue
    else if (arg === '--root' || arg === '--base' || arg === '--head') {
      const value = argv[i + 1] || ''
      if (!value) options.missingValue = arg
      options[arg.slice(2)] = value
      i += 1
    } else options.unknown.push(arg)
  }
  return options
}

function main(argv) {
  if (argv.includes('--help') || argv.includes('-h')) {
    printHelp()
    return 0
  }
  const options = parseArguments(argv)
  const invalidRange = (!options.standards && (options.base || options.head)) || (options.base && !options.head)
  if (options.unknown.length || options.missingValue || invalidRange) {
    const detail = options.unknown.join(' ') || options.missingValue || '--base/--head require --standards and --head'
    console.error(`[check-d6-sync] FAIL unrecognised or incomplete arguments: ${detail} (see --help)`)
    return 2
  }
  try {
    const repo = resolveRepoRoot(options.root)
    if (!options.standards) return runD6(repo)
    return options.head ? runStandardsCommits(repo, options.base, options.head) : runStandardsStaged(repo)
  } catch (e) {
    console.error(`[check-d6-sync] FAIL check could not complete (treated as an error, rc=2): ${e.message}`)
    console.error('    Ensure the repository, git, index and requested commits are readable; failures are never a pass.')
    return 2
  }
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : ''
const self = path.resolve(fileURLToPath(import.meta.url))
if (entry && (entry === self || entry.toLowerCase() === self.toLowerCase())) {
  process.exitCode = main(process.argv.slice(2))
}
