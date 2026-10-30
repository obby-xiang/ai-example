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
    label: '后端 AI / 发号 / SSE 测试（不属搬迁资产）',
    match: (p) => /^backend\/src\/test\/java\/com\/example\/configmgr\/(ai|common\/id|common\/sse)\//.test(p),
  },
  {
    id: 'frontend-tests',
    label: '前端单测（含 __tests__ / *.test / *.spec）',
    match: (p) => p.startsWith('frontend/') && (/(^|\/)__tests__\//.test(p) || /\.(test|spec)\.[cm]?[jt]sx?$/.test(p)),
  },
  {
    id: 'frontend-e2e',
    label: '前端 E2E / harness / 域专用桩（不迁移）',
    match: (p) => p.startsWith('frontend/e2e/'),
  },
  {
    id: 'ci-sim',
    label: 'CI 上游桩与 E2E 设施（不迁移）',
    match: (p) =>
      p.startsWith('scripts/ci/stub-') ||
      p === 'scripts/verify-e2e.ps1' ||
      p === 'scripts/b23l1-dual-instance.ps1' ||
      p === 'scripts/chat-panel-smoke.mjs',
  },
  {
    id: 'backend-i18n-props',
    label: '后端错误码多语言（键集与 common/ErrorCodes 绑定，见 D6 §6.8）',
    match: (p) => /^backend\/src\/main\/resources\/messages[^/]*\.properties$/.test(p),
  },
  {
    id: 'frontend-locale-store',
    label: '前端语言切换联动（见 D6 §6.8「切语言三件事同批」）',
    match: (p) => p === 'frontend/src/stores/locale.ts',
  },
]

const BLOCK_RULES = [
  {
    id: 'backend-ai-runtime',
    label: '后端 AI 运行时（ai/**）',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/ai/'),
  },
  {
    id: 'backend-ai-tools',
    label: '工具披露清单（resources/ai/**，改 description 会影响提示词缓存）',
    match: (p) => p.startsWith('backend/src/main/resources/ai/'),
  },
  {
    id: 'backend-id',
    label: '会话发号（common/id/**）',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/common/id/'),
  },
  {
    id: 'backend-sse',
    label: 'SSE 写预算（common/sse/**）',
    match: (p) => p.startsWith('backend/src/main/java/com/example/configmgr/common/sse/'),
  },
  {
    id: 'frontend-ai-panel',
    label: 'AI 面板（components/AiPanel/**）',
    match: (p) => p.startsWith('frontend/src/components/AiPanel/'),
  },
  {
    id: 'frontend-ai-store',
    label: 'AI 会话 store（stores/ai.ts）',
    match: (p) => p === 'frontend/src/stores/ai.ts',
  },
  {
    id: 'frontend-ai-api',
    label: 'AI 接口 / SSE 客户端 / 身份头（api）',
    match: (p) =>
      p === 'frontend/src/api/ai.ts' ||
      p === 'frontend/src/api/aiConfig.ts' ||
      p === 'frontend/src/api/sse.ts' ||
      p === 'frontend/src/api/http.ts',
  },
  {
    id: 'frontend-ai-types',
    label: 'AI / SSE 帧 / 工具类型',
    match: (p) =>
      p === 'frontend/src/types/ai.ts' || p === 'frontend/src/types/sse.ts' || p === 'frontend/src/types/tools.ts',
  },
  {
    id: 'frontend-ai-utils',
    label: 'AI 身份 / 工具回灌 / 上下文计量工具函数',
    match: (p) =>
      p.startsWith('frontend/src/utils/ai-') ||
      p === 'frontend/src/utils/frontend-tools.ts' ||
      p === 'frontend/src/utils/context-usage.ts' ||
      p === 'frontend/src/utils/parts.ts',
  },
  {
    id: 'frontend-locales',
    label: '前端多语言（locales/**，AI 键集与业务段同批）',
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
  if (p.startsWith('docs/') || p.endsWith('.md')) return { category: 'doc', ruleId: 'docs', label: '文档' }
  if (isInfra(p)) return { category: 'infra', ruleId: 'infra', label: 'CI / 脚本 / 构建配置' }
  return { category: 'other', ruleId: 'other', label: '非 AI 面文件（不阻断）' }
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
    throw new GateInputError(`docs/delivery/d6-review.json 不是合法 JSON（${e.message}）`)
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new GateInputError('docs/delivery/d6-review.json 顶层必须是对象')
  }
  if (data.version !== REVIEW_VERSION) {
    throw new GateInputError(`version 必须是 ${REVIEW_VERSION}（当前：${JSON.stringify(data.version)}）`)
  }
  if (data.reviews === undefined) return { reviews: [] }
  if (!Array.isArray(data.reviews)) throw new GateInputError('reviews 必须是数组')
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
    problems.push(`status 必须是 synced 或 not-affected（当前：${JSON.stringify(entry.status)}）`)
  }
  if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
    problems.push('reason 不能为空：需写明本次改动与 D6 的关系')
  }
  if (entry.status === 'synced' && !d6Updated) {
    problems.push(`status=synced 要求同批暂存 ${D6_DOC_PATH}`)
  }
  if (entry.status === 'not-affected' && !isConcreteReason(entry.reason)) {
    problems.push(`status=not-affected 的 reason 必须是具体理由（去空白后 ≥${MIN_REASON_CHARS} 字，且不是 n/a / 无 / 待补 这类占位）`)
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
    result.problems.push(`暂存区有 ${result.block.length} 个 block 文件，但 ${REVIEW_PATH} 未随本次提交更新`)
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
        ? 'reviews 为空：block 变更必须本次新增一条确认'
        : '现有记录的指纹与当前暂存内容不一致（文件内容或 block 文件集合变化后旧确认即失效）'
    )
    return result
  }

  const fresh = sameFingerprint.filter((entry) => !headKeys.has(stableEntry(entry)))
  if (fresh.length === 0) {
    result.exitCode = 1
    result.kind = 'reused-confirmation'
    result.problems.push('匹配到的确认记录已存在于上一次提交（HEAD）中：确认必须本次新增，不能复用历史记录')
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
    reason: '<写清本次改了什么、D6 是否需更新及原因>',
    reviewedAt: new Date().toISOString().slice(0, 10),
    reviewer: '<姓名或工号>',
  }
}

export function describe(result) {
  const lines = [`D6 同步复核门禁（check-d6-sync，规则版本 ${RULE_VERSION}）`, '']

  if (result.block.length === 0) {
    if (result.warn.length > 0) {
      lines.push(`⚠ 以下 ${result.warn.length} 个暂存文件属测试 / 模拟设施或多语言共享面，只告警不阻断：`)
      for (const file of sorted(result.warn)) lines.push(`    ${file.status} ${file.path}   ← ${file.label}`)
      lines.push('')
    }
    lines.push('✓ 暂存区没有 AI 对话核心文件（block 面），无需 D6 复核确认')
    return lines
  }

  lines.push(`暂存区 block 文件（${result.block.length} 个，必须本次新增有效确认）：`)
  for (const file of sorted(result.block)) lines.push(`    ${file.status} ${file.path}   ← ${file.label}`)

  if (result.warn.length > 0) {
    lines.push('')
    lines.push(`⚠ 另有 ${result.warn.length} 个暂存文件属测试 / 模拟设施或多语言共享面，只告警不阻断：`)
    for (const file of sorted(result.warn)) lines.push(`    ${file.status} ${file.path}   ← ${file.label}`)
  }

  lines.push('')
  lines.push(`指纹：${result.fingerprint}`)
  lines.push('      = 规则版本 + 本次暂存 block 文件的「状态 blob 路径」（d6-review.json 自身不计）')
  lines.push('')

  if (result.kind === 'ok') {
    lines.push(`✓ 本次新增确认有效：status=${result.entry.status}`)
    lines.push(`    reason: ${String(result.entry.reason).trim()}`)
    lines.push(`✓ D6 同步复核通过（${REVIEW_PATH} + ${result.block.length} 个 block 文件同批提交）`)
    lines.push('')
    lines.push('提醒：本门禁只证明"当次确实有人留下确认"，不证明 D6 内容与代码语义一致。')
    return lines
  }

  if (result.kind === 'bad-json') {
    lines.push('✖ docs/delivery/d6-review.json 结构不可用（按环境/输入错误处理，rc=2）：')
    for (const problem of result.problems) lines.push(`    - ${problem}`)
    lines.push('')
    lines.push('坏 JSON 不会被当成"无确认"放行：先修成合法 JSON（version: 1、reviews 数组）再提交。')
    return lines
  }

  const headlines = {
    'review-not-staged': `✖ ${REVIEW_PATH} 未随本次提交更新：block 变更必须本次新增确认`,
    'no-confirmation': '✖ 没有与当前指纹匹配的确认记录',
    'reused-confirmation': '✖ 匹配到的确认记录不是本次新增',
    'invalid-confirmation': '✖ 确认记录不满足要求',
  }
  lines.push(headlines[result.kind] || '✖ D6 同步复核未通过')
  for (const problem of result.problems) lines.push(`    - ${problem}`)

  if (result.kind === 'invalid-confirmation' && result.entry) {
    lines.push('')
    lines.push(`当前记录：${JSON.stringify(result.entry)}`)
  }

  lines.push('')
  lines.push(`处理方式：把下面这条记录**新增**到 ${REVIEW_PATH} 的 reviews 数组，并把该文件一起暂存：`)
  lines.push('')
  for (const line of JSON.stringify(confirmationTemplate(result.fingerprint), null, 2).split('\n')) {
    lines.push(`  ${line}`)
  }
  lines.push('')
  lines.push('  status 二选一：')
  lines.push(`    synced       = 本批同时更新了 ${D6_DOC_PATH}（门禁会核对它在暂存区里）`)
  lines.push('    not-affected = 本次改动不影响 D6 描述的迁移面，reason 写清具体原因（不能是 n/a、无、待补）')
  lines.push('')
  lines.push('  指纹变即失效：后续再改这些文件（或增减 block 文件）需要重新确认。')
  lines.push('  本门禁不判断 reason 是否真实，也不判断 D6 内容是否正确；CI / 人工复核仍是必要补充。')
  return lines
}

function printHelp() {
  console.log(`D6 同步复核门禁（check-d6-sync，规则版本 ${RULE_VERSION}）

用法：
  node scripts/ci/check-d6-sync.mjs [--root <仓库根>] [--staged]
  node scripts/ci/check-d6-sync.mjs --help

判定（只看暂存区）：
  block  backend ai/**、common/id、common/sse、resources/ai/**、
         frontend AiPanel / stores/ai.ts / api(ai|aiConfig|sse|http) / types(ai|sse|tools) /
         utils(ai-*|frontend-tools|context-usage|parts) / locales
         —— 必须本次新增一条指纹匹配的确认：synced（同批更新 D6）或 not-affected（具体理由）
  warn   测试 / E2E / 桩 / harness / 后端 messages_*.properties / stores/locale.ts —— 只告警
  doc    docs/** 与 *.md；infra  scripts/**、.github/**、构建配置；其余业务文件一律忽略

指纹：sha256(规则版本 + 排序后的「状态 blob 路径」)，只含本次暂存 block 文件；d6-review.json 自身不计。
退出码：0 通过（含"无需确认"）/ 1 需要确认而未满足 / 2 输入或环境错误（坏 JSON、version 缺失或不为 1、非 git 仓库等）。

局限：确认记录是人工书面承诺，门禁不校验其真实性，也不校验 D6 内容正确性；
      git commit --no-verify 与未安装 Node 的环境都会跳过本检查（跳过时输出告警）。`)
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
    console.error(`✖ [d6-sync] 参数无法识别：${unknown.join(' ') || '--root 缺少值'}（--help 查看用法）`)
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
    console.error(`✖ [d6-sync] 检查未能完成（按错误处理，rc=2）：${e.message}`)
    console.error('    请确认当前目录（或 --root）是 git 仓库、git 可用，且暂存区可读。')
    return 2
  }
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : ''
const self = path.resolve(fileURLToPath(import.meta.url))
if (entry && (entry === self || entry.toLowerCase() === self.toLowerCase())) {
  process.exitCode = main(process.argv.slice(2))
}
