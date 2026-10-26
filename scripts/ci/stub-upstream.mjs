#!/usr/bin/env node
/**
 * E2E 门禁专用「上游替身」（M2-T1.2；设计草案 §3.2/§5.1/§5.2）。
 *
 * 作用：CI 内替代真实模型，让 `scripts/verify-e2e.ps1` 的 29 个用例在**零密钥、零外网**下确定性通过。
 * 后端只需 `AI_BASE_URL=http://127.0.0.1:<port>` + 任意非空 `AI_API_KEY` 占位串即可把上游整体指向本桩
 * （后端的可用性判据只看 key 是否非空，不看 key 真假，见 ai/config/AiAvailability）。
 *
 * 用途边界（重要）：
 *   - 本桩是**产品链路的替身**，不是模型行为的替身。CI 全绿只证明「桩给出的表单/工具调用被产品链路正确
 *     消费」，**不证明模型服从率**（设计草案 §4.3：GFSKIP=0 在桩下由构造保证，属恒真式而非观测）。
 *   - 真模型的 E2E 维持本地手动跑（S5d 裁决 #3）。
 *
 * 零依赖：只用 Node 内置模块（self-check 会断言这一点），单文件可直接 `node scripts/ci/stub-upstream.mjs`。
 *
 * 收敛契约（设计草案 §3.3，硬要求——后端工具循环<b>无迭代上限</b>，桩必须自证终止）：
 *   ① **披露清单驱动**：目标工具必须出现在本请求 `tools[]` 里才允许发起调用，否则退化为文本轮
 *      （避免"调用未披露工具 → 后端以 SCOPE_NOT_DISCLOSED 回灌 → 桩再调同一个 → 请求风暴"）；
 *   ② **每个会话轮次的上游调用上限**（`convergence.maxCallsPerTurn`，默认 6）：越界只发文本；
 *   ③ **续轮只发文本**：**本轮**已出现 `role=tool` 消息（即最后一条 `user` 之后有工具结果）时不再发起
 *      工具调用；历史带来的工具结果不算续轮（上一轮的工具结果已进历史、其后又有新用户消息 ⇒ 那是新轮，
 *      必须照常下发 tool_calls，否则新用户轮的首次请求会被误判成续轮 —— L-84）；
 *   ④ **解析失败不猜**：任何识别/解析失败一律以文本收尾（宁可让用例 FAIL，也不制造请求风暴）。
 *   `--self-check` 用"首轮→工具调用、续轮→文本、新用户轮（历史带工具结果）→工具调用"的模拟循环逐条证明上述四条成立。
 *
 * 记录契约（可归因性，设计草案 §5.2.4）：每请求落 `<dir>/req-NN.json`（请求原文）、
 * `.tools.txt`（本请求实际披露的工具名清单）、`.head.txt`（模型可见消息摘要）、
 * `.decision.json`（桩本轮的判定：路由 id / 工具名 / 理由）——"桩坏 / 产品坏"的区分依据。
 *
 * usage 形状（上下文统计与压缩专项 §5.8；E2E-CTX-1..4 的确定性底座）：
 *   提示词里带 `usage-shape=<last|repeat|zero-last|none>` 即按该形状发 usage（**请求级**开关，
 *   与 `--violate-form-type` 同族；不带标记 = `last` = 改造前的行为，帧逐字节不变）：
 *     last      末个 usage-only 帧发一份真值（基线）
 *     repeat    **每个 content 分片都挂同一份**（治"每块重复被累加放大"）
 *     zero-last 首个分片真值 + **末个 usage-only 帧 0/0/0**（治"直读末帧把真值读成 0"）
 *     none      整轮不发 usage（产品必须回退估算：`est=true` / `source=estimate`）
 *   数值唯一取数点 = `stub-routes.json` 的 `usageShapes`（verify-e2e.ps1 读同一份 ⇒ 不会漂移）；
 *   `--self-check` 段 ⑧ 逐形状断言帧形态与标记解析。
 *
 * 用法：
 *   node scripts/ci/stub-upstream.mjs --port 18399 --dir <dir> [--routes <stub-routes.json>]
 *   node scripts/ci/stub-upstream.mjs --self-check [--routes <stub-routes.json>]
 *   [--violate-form-keys | --violate-form-type]   # 违规模式，默认关闭（裁决 #8，仅缺陷注入与 GF6 用例开启）
 *     --violate-form-keys：全局（S7 缺陷注入口径不变）；
 *     --violate-form-type：**请求级**——只有提示词明写"把字段 type 故意写成 html"的用例（GF6）触发，
 *       不连带污染共用 GF-FILTER 路由的 GF1/GF3/GF4（T4-4 裁决 4）。
 *
 * 故障注入与命名轮蓝本（E2E 批① §4.6；与 `usage-shape` 同族的**请求级**开关，提示词标记选择）：
 *   turn-text / turn-interleave      确定性文本轮 / 文本↔工具交错轮（段序基线）
 *   break-at-seq=K                   写满 K 帧后销毁 socket（不发 [DONE]）
 *   stall-frames=<ms>                帧间静默；stall-headers=<ms> 写响应头前静默
 *   kill-stream=<ms>                 到点销毁 socket（上游 TCP 断）
 *   slow-tool=<ms>                   工具轮首帧前静默；slow-reader 慢生产者（20 帧 × 250ms）
 *   multi-card=<n>                   一条消息 n 个 tool_calls（id 互异）
 *   huge-frame=<bytes> / tool-loop=<n>   单帧字节数 / 连续 n 次工具调用
 *   reasoning=<chars>                思考分片：正文分片之前插 1–2 个 `reasoning_content` 分片
 *                                   （L-91 的上游侧形态；0/缺省 = 关闭，帧逐字节不变）
 *   context-overflow / summary-500   越窗 400（措辞取产品 ContextOverflow.MARKERS）/ 摘要请求 500
 *   gf-seven-types / gf-linkage / gf-expr-linkage / gf-deep-linkage / gf-options-from /
 *   gf-attachment / gf-longtext      GF 新能力面的路由开关（stub-routes.json 的 GF-* 路由）
 *   【AIE2E-X-02】/【AIE2E-X-03】    安全面探针（L-137）：X-02 发**跨分片**的假密钥串、X-03 发**完整**假
 *                                   PEM 块 —— 让 ps1 的输出侧断言有对象（此前桩回复不含探针串，旧断言
 *                                   仍绿属"没被触发"的空断言）。只认这两个请求级标记，其余请求零漂移；
 *                                   探针串均为测试用假串（前缀拆分构造，避免入库被密钥规则误报）。
 *   开关名与默认值的唯一取数点 = `stub-routes.json` 的 `faults.switches`（`--self-check` ⑨ 断言表与实现不漂移）；
 *   未带标记的请求走改造前路径，帧逐字节不变。
 */

import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))

// ── 命令行 ─────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2)

function arg(name, fallback) {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && argv[i + 1] !== undefined && !argv[i + 1].startsWith('--') ? argv[i + 1] : fallback
}

function flag(name) {
  return argv.includes(`--${name}`)
}

const PORT = Number(arg('port', '18399'))
const DIR = arg('dir', path.resolve(process.cwd(), 'stub-dump'))
const ROUTES_PATH = arg('routes', path.join(HERE, 'stub-routes.json'))
const SELF_CHECK = flag('self-check')
const VIOLATE_FORM_KEYS = flag('violate-form-keys')
const VIOLATE_FORM_TYPE = flag('violate-form-type')

// ── 生成式表单白名单：以下 8 组常量 + 5 项上限是产品事实源的【桩内副本】───────────
// 事实源：backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java
//   （常量见该类 :57-115；上限 MAX_FIELDS/MAX_OPTIONS/MAX_TITLE_CHARS/MAX_LABEL_CHARS/
//     MAX_KEY_CHARS/MAX_OPTION_TEXT_CHARS/MAX_PLACEHOLDER_CHARS/MAX_FORM_CHARS）
// 副本清单（8 组常量）：FIELD_TYPES / SCENARIOS / FORM_ATTRIBUTES / FIELD_ATTRIBUTES /
//   OPTION_ATTRIBUTES / PLACEHOLDER_TYPES / FORBIDDEN_KEYS / KEY_PATTERN
// 副本清单（5 项上限）：MAX_TITLE_CHARS=100 / MAX_OPTIONS=50 / MAX_OPTION_TEXT_CHARS=100（且选项值不得重复）
//   / MAX_PLACEHOLDER_CHARS=100 / MAX_FORM_CHARS=8000（form 序列化长度）
// T4-5（M2 排期条目 16 子项，2026-10-09 落地，裁决 5）：`--self-check` 的自检③已改为**读事实源
//   逐值对账**上面这 8 组常量 + 5 项上限 + 原型三键（不新增端点、不新增生成步骤）——产品侧收紧/
//   改动白名单而桩未同步时自检即红、e2e job 即红，不再有"副本自证仍绿"的漂移窗口。
// 残留观察项（见 M2-T4 施工报告 §观察项）：③-b 模板校验里的 fields 数量上限（1..20）与 label
//   长度上限（100）仍是**行内字面量**，未纳入本次逐值对账集合。
const FORBIDDEN_KEYS = ['__proto__', 'constructor', 'prototype']
const FIELD_TYPES = ['text', 'number', 'boolean', 'date', 'enum', 'multi_select', 'attachment']
const SCENARIOS = ['FILTER', 'CLARIFY']
const FORM_ATTRIBUTES = ['scenario', 'title', 'fields']
// GF 优化批（attachment + 联动五原语/两条 from + 文本修饰）已进产品白名单，桩副本必须同步；
// 未同步时 `--self-check` ③ 会红（事实源对账），这正是漂移窗口的告警位。
const FIELD_ATTRIBUTES = ['key', 'label', 'type', 'required', 'defaultValue', 'options', 'placeholder',
  'variant', 'minLength', 'maxLength', 'format', 'min', 'max', 'step', 'integer', 'minItems', 'maxItems',
  'visibleWhen', 'requiredWhen', 'disabledWhen', 'optionsFrom', 'defaultFrom']
const OPTION_ATTRIBUTES = ['value', 'label']
const PLACEHOLDER_TYPES = ['text', 'number', 'date']
const OPTION_TYPES = ['enum', 'multi_select']
const CONDITION_OPS = ['eq', 'in', 'notIn', 'empty', 'notEmpty', 'between']
const CONDITION_ATTRIBUTES = ['key', 'op', 'value', 'values']
const VARIANT_TYPES = ['text']
const TEXT_FORMATS = ['email', 'tel', 'url', 'idcard']
// 选项数据源闭集：**必须与产品 `GenerativeFormRules.OPTION_SOURCE_IDS` 逐值一致**（自检 ③ 对账）。
// `definitions` = 配置定义枚举（父值 = 层级 GLOBAL/REGION/PROJECT → 该层级的定义），
// 供"先选配置项"用；样例返回的唯一取数点 = stub-routes.json 的 `optionSources.definitions`。
const OPTION_SOURCE_IDS = ['definition_fields', 'definitions']
const DEFINITION_SOURCE_ID = 'definitions'
const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,39}$/

const MAX_TITLE_CHARS = 100
const MAX_OPTIONS = 50
const MAX_OPTION_TEXT_CHARS = 100
const MAX_PLACEHOLDER_CHARS = 100
const MAX_FORM_CHARS = 8000
const MAX_LINKAGE_DEPTH = 2

// ── 路由表 ─────────────────────────────────────────────────────────────────

function loadRoutes(file) {
  const raw = fs.readFileSync(file, 'utf8')
  const parsed = JSON.parse(raw)
  if (!parsed || typeof parsed !== 'object') throw new Error('路由表不是 JSON 对象')
  return parsed
}

/** 深度扫描敏感/危险键（撤销原型污染的入口，路由表里出现即视为缺陷）。 */
function forbiddenKeyPaths(value, trail = '$', out = []) {
  if (value === null || typeof value !== 'object') return out
  for (const key of Object.keys(value)) {
    if (FORBIDDEN_KEYS.includes(key)) out.push(`${trail}.${key}`)
    forbiddenKeyPaths(value[key], `${trail}.${key}`, out)
  }
  return out
}

/**
 * 违规模式（默认关闭）：故意把合法桩改成"坏桩"，用于归因链路验证与 GF6 用例。
 * 两种形态刻意对应两条不同的产品侧分支：
 *   - `--violate-form-keys`：FILTER 模板缺字段（schema 漂移）→ 脚本记 SKIP。**全局生效**（S7 缺陷注入口径不变）。
 *   - `--violate-form-type`：字段类型越白名单（html）→ 产品入参闸门拒绝（REJECTED_ARGUMENTS）。
 *     **请求级生效**（T4-4 裁决 4）：违规注入只在"提示词本身要求违规"的用例路径上触发（见
 *     {@link VIOLATION_REQUEST_MARKER}），不连带污染 GF1/GF3/GF4 —— 它们与 GF6 共用 GF-FILTER
 *     路由，若在进程启动期全局改模板，这几个用例会一起被注入而整棒红。
 */
function applyViolation(routes) {
  if (!VIOLATE_FORM_KEYS) return []
  const applied = []
  for (const route of routes.routes || []) {
    if (route.id !== 'GF-FILTER') continue
    const fields = route.arguments?.form?.fields
    if (!Array.isArray(fields)) continue
    route.arguments.form.fields = fields.filter((f) => f.key === 'keyword' || f.key === 'scope')
    applied.push('violate-form-keys：GF-FILTER 丢弃 minRows/effectiveDate/amount')
  }
  return applied
}

/**
 * `--violate-form-type` 的请求级开关：命中该标记的请求才注入"字段类型越白名单"。
 * GF6（`verify-e2e.ps1` 的入参闸门用例）的提示词明写"把其中一个字段的 type 故意写成 html"，
 * 其余 GF 用例的提示词不含该字样 —— 于是"用例级开关"由提示词本身承担（桩不猜调用方身份）。
 */
const VIOLATION_REQUEST_MARKER = /故意写成\s*html/

/** 命中则就地改 decision.args 里的 scope 字段类型（args 是 route.arguments 的深拷贝，不改路由表）。 */
function applyRequestViolation(decision, body) {
  if (!VIOLATE_FORM_TYPE || decision.kind !== 'tool_call' || decision.route !== 'GF-FILTER') return null
  const asked = messagesOf(body).some(
    (m) => m?.role === 'user' && VIOLATION_REQUEST_MARKER.test(textOf(m) || '')
  )
  if (!asked) return null
  const target = (decision.args?.form?.fields || []).find((f) => f.key === 'scope')
  if (!target) return null
  target.type = 'html'
  return 'violate-form-type（请求级）：GF-FILTER 的 scope 字段类型改为 html（越白名单）'
}

// ── usage 形状（上下文统计与压缩专项 §5.8：E2E-CTX-1..4 的确定性底座）──────────
// 与 `--violate-form-type` 同族的**请求级**开关：由提示词里的 `usage-shape=<mode>` 标记选择，
// 不猜调用方身份、不影响未带标记的请求（既有 29 用例的帧逐字节不变）。
// 四种形状（`stub-routes.json` 的 `usageShapes` 是唯一取数点，E2E 脚本读同一份）：
//   last      —— 末块 usage（基线：改造前桩就发这一份）
//   repeat    —— **每块都挂同一份 usage**（治"累加放大"，对标业界 1.7M tokens 事故）
//   zero-last —— 中间块真值 + **末块 0/0/0**（治"直读末帧"把真值读成 0）
//   none      —— **完全不发 usage**（产品必须回退估算：`est=true` / `source=estimate`）
const USAGE_SHAPE_MARKER = /usage-shape=(last|repeat|zero-last|none)\b/
const USAGE_SHAPE_NAMES = ['last', 'repeat', 'zero-last', 'none']
const USAGE_SHAPE_DEFAULTS = {
  last: { promptTokens: 1200, completionTokens: 40, totalTokens: 1240, cachedTokens: 640, reasoningTokens: 16 },
  repeat: { promptTokens: 4321, completionTokens: 77, totalTokens: 4398, cachedTokens: 2048, reasoningTokens: 12 },
  'zero-last': { promptTokens: 3100, completionTokens: 50, totalTokens: 3150 },
  none: {}
}

/** 形状表：路由表（或任意外部 routes 文件）里缺项/无该键时回落内建默认值 —— 向后兼容。 */
function usageShapes() {
  const declared = ROUTES.usageShapes || {}
  const out = {}
  for (const name of USAGE_SHAPE_NAMES) out[name] = { ...USAGE_SHAPE_DEFAULTS[name], ...(declared[name] || {}) }
  return out
}

/** 本请求要求的形状；未带标记 / 未知值一律 `last`（默认形状 = 改造前行为）。 */
function usageShapeOf(body) {
  const userText = textOf(lastMessageWithRole(messagesOf(body), 'user'))
  const hit = USAGE_SHAPE_MARKER.exec(userText || '')
  return hit ? hit[1] : 'last'
}

/** 形状的 `usage` 负载（OpenAI 兼容形态；数值全取自上表，不在帧里写裸数字）。 */
function usageOf(shape) {
  const usage = {
    prompt_tokens: shape.promptTokens,
    completion_tokens: shape.completionTokens,
    total_tokens: shape.totalTokens
  }
  if (shape.cachedTokens) usage.prompt_tokens_details = { cached_tokens: shape.cachedTokens }
  if (shape.reasoningTokens) usage.completion_tokens_details = { reasoning_tokens: shape.reasoningTokens }
  return usage
}

// ── 故障注入与命名轮蓝本（E2E 批① §4.6 的桩开关；与 usage-shape 同族的**请求级**开关）──
// 口径（与既有 35 用例零耦合的硬要求）：开关只由**提示词标记**选择，标记之外的请求
// 走改造前的路径，帧逐字节不变。开关名与默认值不在本文件写死 —— 事实源是
// `stub-routes.json` 的 `faults.switches`（用例与桩读同一份 ⇒ 不会两边各写一套）。
//
// 七个桩层开关（§4.6 行 1–7、9、10）：
//   turn-text / turn-interleave  命名轮蓝本（段序基线 / t→tool→t→tool 交错）
//   break-at-seq=K               写满 K 帧后销毁 socket（不发 [DONE]）
//   stall-frames=<ms>            首帧后每帧前静默 ms（45s 静默判定）
//   stall-headers=<ms>           写响应头前静默 ms（建连看门狗）
//   kill-stream=<ms>             到点销毁 socket（上游 TCP 断）
//   slow-tool=<ms>               工具轮首个 tool_calls 帧前静默 ms
//   multi-card=<n>               一条 assistant 消息里 n 个 tool_calls（id 互异）
//   huge-frame=<bytes>           单个 delta 帧 N 字节
//   reasoning=<chars>            思考分片：正文分片之前插 1–2 个 `delta.reasoning_content` 分片
//                                （0 = 关闭；与 huge-frame 可同开，超 64KB 的思考文本保持单片
//                                ⇒ 覆盖产品帧侧字段级截断面）
//   tool-loop=<n>                连续 n 次工具调用（步数上限）
//   context-overflow             上游回 400 + 越窗措辞（ContextOverflow.MARKERS 命中）
//   summary-500                  arm 后下一条摘要请求回 500
//   slow-reader                  慢生产者：20 帧 × 250ms（配合双订阅验隔离）
//
// 批③b 细分注入（L-67~ 系列解耦）：上面三个延迟开关各自罩住"整条链路的一个大段"，
// 用例要的往往是其中一小段。故按**段**拆成三个只命中一段的开关（与既有开关互不牵连，
// 未带标记的请求帧逐字节不变）：
//   slow-first-byte=<ms>         只慢首包：响应头之后、首帧之前（文本轮同样生效）
//   slow-tool-exec=<ms>          只慢工具执行段：工具轮 / 工具续轮（上下文有 role=tool）的首帧
//   slow-tail-frames=<ms>        只慢帧间·收尾段：仅工具续轮的帧间静默（首帧不延迟）
//   late-frames=<n>              [DONE] 之后继续推 n 帧（S-12 迟到帧闸门）
//
// 请求级工具路由与 epoch 注入（同为**提示词标记**选择，不受 faults 数值表管辖）：
//   tool=<name>&args=<json>      直接指定本轮要调的工具与入参；仍受 requiresTools 披露闸约束
//   epoch=<nonce-N>              把工具入参里的 epoch 换成该 token（真机 epoch 是页面 nonce）
//
// 三个非桩层开关（`faults.switches` 里标 layer=script/product）：`fe-tool-hang` 由用例的
// 挂起处理器停住、`sheet-big` 由用例自建 500 行任务、`preflight-fail` 走产品既有预检语义。
// 桩只为它们提供触发面（下发工具/表单），不在桩里伪造业务结果 —— 否则"桩绿=产品绿"的假象。
const FAULT_VALUE_SWITCHES = ['break-at-seq', 'stall-frames', 'stall-headers', 'kill-stream', 'slow-tool',
  'multi-card', 'huge-frame', 'tool-loop', 'slow-first-byte', 'slow-tool-exec', 'slow-tail-frames',
  'late-frames', 'reasoning']
const FAULT_FLAG_SWITCHES = ['turn-text', 'turn-interleave', 'context-overflow', 'summary-500', 'slow-reader',
  'fail-503-once']
const FAULT_SWITCHES = [...FAULT_VALUE_SWITCHES, ...FAULT_FLAG_SWITCHES]
/** 非数值标记类开关：`usage-shape=<enum>` / `tool=…&args=…` / `epoch=<token>`（各自独立解析式）。 */
const REQUEST_LEVEL_SWITCHES = ['usage-shape', 'request-tool', 'epoch']
const FAULT_MARKER = new RegExp(`(?:^|[\\s【（(,，】：:])(${FAULT_SWITCHES.join('|')})(?:=(\\d+))?(?![\\w-])`, 'g')

/** 本请求要求的故障开关（`{name: number|true}`）；无标记 = 空对象 = 改造前行为。 */
function faultsOf(body) {
  const text = textOf(lastMessageWithRole(messagesOf(body), 'user'))
  const out = {}
  if (!text) return out
  FAULT_MARKER.lastIndex = 0
  for (const m of text.matchAll(FAULT_MARKER)) {
    const [, name, value] = m
    out[name] = FAULT_VALUE_SWITCHES.includes(name) && value !== undefined ? Number(value) : true
  }
  return out
}

/** 开关默认值（`stub-routes.json` 的 `faults.switches[*].default`；缺失回落内建同值）。 */
const FAULT_DEFAULTS = { 'break-at-seq': 2, 'stall-frames': 50000, 'stall-headers': 30000, 'kill-stream': 3000,
  'slow-tool': 130000, 'multi-card': 3, 'huge-frame': 102400, 'tool-loop': 26,
  'slow-first-byte': 8000, 'slow-tool-exec': 8000, 'slow-tail-frames': 1200, 'late-frames': 2,
  // reasoning：数值空间以 0 起，0 = 关闭；裸标记（无 `=<chars>`）因此也是关闭，
  // 这是刻意的 —— 既有用例的精确帧数断言只在"开关关闭"这一种状态下有意义。
  reasoning: 0 }

/** 命名轮蓝本的确定性文本（段序基线与"迟到帧不写"用例共用；长度 > 120 字以覆盖多个 delta）。 */
const TURN_TEXT = '命名轮蓝本：这是一段确定性文本，用于验证纯文本流的帧序完整性（start 恰 1 / delta ≥ 1 / done 恰 1），'
  + '以及 done 之后零帧的迟到帧闸门。文本按 24 字符切片发送，切片数由本串长度决定，不依赖模型。'

/** 开关的数值取值：`name=5` → 5；只写 `name`（无值）→ 表内默认值（不把 `true` 当 1 用）。 */
function numericFault(faults, name) {
  const v = faults[name]
  if (typeof v === 'number') return v
  return FAULT_DEFAULTS[name] ?? 0
}

/**
 * 故障对"写出计划"的影响 —— **纯函数**（`--self-check` 直接断言，不起服务）：
 * `{ headerDelayMs, firstByteDelayMs, toolExecDelayMs, firstFrameDelayMs, frameGapMs, tailFrameGapMs,
 *    killAfterMs, breakAtFrame, lateFrames }`。
 * `breakAtFrame = K` 表示只写前 K 帧就销毁 socket；`killAfterMs > 0` 表示定时销毁。
 *
 * `round` 是本请求的段身份（`kind` = 判定结果，`hasToolResult` = 上下文里是否已有工具结果）：
 *   - `slow-first-byte`（首包段）与段身份无关，任何一轮都生效；
 *   - `slow-tool-exec`（工具执行段）只在工具轮 / 工具续轮生效；
 *   - `slow-tail-frames`（帧间·收尾段）只在工具续轮生效（首帧不延迟）。
 * 不传 `round` 时三个新开关一律不生效 ⇒ 既有调用点与自检的旧断言逐值不变。
 */
function writePlan(faults, round = {}) {
  const toolSegment = round.kind === 'tool_call' || round.hasToolResult === true
  const tailSegment = round.hasToolResult === true
  return {
    headerDelayMs: faults['stall-headers'] ? numericFault(faults, 'stall-headers') : 0,
    firstFrameDelayMs: faults['slow-tool'] ? numericFault(faults, 'slow-tool') : 0,
    firstByteDelayMs: faults['slow-first-byte'] ? numericFault(faults, 'slow-first-byte') : 0,
    toolExecDelayMs: faults['slow-tool-exec'] && toolSegment ? numericFault(faults, 'slow-tool-exec') : 0,
    frameGapMs: faults['stall-frames'] ? numericFault(faults, 'stall-frames') : (faults['slow-reader'] ? 250 : 0),
    tailFrameGapMs: faults['slow-tail-frames'] && tailSegment ? numericFault(faults, 'slow-tail-frames') : 0,
    killAfterMs: faults['kill-stream'] ? numericFault(faults, 'kill-stream') : 0,
    breakAtFrame: faults['break-at-seq'] ? numericFault(faults, 'break-at-seq') : 0,
    lateFrames: faults['late-frames'] ? numericFault(faults, 'late-frames') : 0
  }
}

/**
 * 请求级工具路由（`tool=<name>&args=<json>`）。
 *
 * 为什么要有：`L-02~L-08` 这些「某工具被调一次会怎样」的用例，在桩里没有对应的提示词路由，
 * 每加一条路由都要重编路由表并受"每路由一次"台账约束。请求级标记把"调哪个工具、带什么入参"
 * 交给提示词，用例自述意图，桩只做**披露闸 + 参数解析**（不猜调用方身份）。
 *
 * 解析规则：`tool=` 后接工具名；可选 `&args=` 后接一个**平衡括号**的 JSON 对象（允许嵌套、
 * 允许字符串里出现 `}`）。缺 JSON / 括号不平衡 / 非对象 ⇒ 返回 `badArgs`，调用方退化为文本轮。
 */
const REQUEST_TOOL_MARKER = /\btool=([A-Za-z_][A-Za-z0-9_]{0,63})\b/

/** 从 `text[from]`（必须是 `{`）起取平衡括号片段（按字符串/转义状态跳过括号）；不平衡返回 null。 */
function balancedJson(text, from) {
  let depth = 0
  let inString = false
  let escaped = false
  for (let i = from; i < text.length; i++) {
    const ch = text[i]
    if (inString) {
      if (escaped) escaped = false
      else if (ch === '\\') escaped = true
      else if (ch === '"') inString = false
      continue
    }
    if (ch === '"') inString = true
    else if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth === 0) return text.slice(from, i + 1)
    }
  }
  return null
}

function requestToolOf(body) {
  const text = textOf(lastMessageWithRole(messagesOf(body), 'user'))
  if (!text) return null
  const m = REQUEST_TOOL_MARKER.exec(text)
  if (!m) return null
  const name = m[1]
  const rest = text.slice(m.index + m[0].length)
  const argsMark = /^&args=/.exec(rest)
  if (!argsMark) return { name, args: {} }
  const from = rest.indexOf('{', argsMark[0].length)
  if (from < 0) return { name, args: {}, badArgs: '缺少 JSON 对象' }
  const json = balancedJson(rest, from)
  if (json === null) return { name, args: {}, badArgs: 'JSON 括号不平衡' }
  const parsed = safeJson(json)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { name, args: {}, badArgs: 'JSON 不是对象' }
  }
  return { name, args: parsed }
}

/**
 * 请求级 epoch 注入（`epoch=<nonce-N>`）：真机 epoch 是模块加载期 random nonce + 计数器
 * （`ai-regions.ts` 的 `EPOCH_NONCE`），路由表里的固定串必被围栏拒 ⇒ V 域成功路径在模型侧不可达。
 * 用例把页面当前 epoch 写进提示词，桩把本轮工具入参里**已有的** `epoch` 键换掉（不新增键）。
 */
const EPOCH_MARKER = /\bepoch=([a-z0-9]{6}-\d{1,6})\b/

function epochOf(body) {
  const text = textOf(lastMessageWithRole(messagesOf(body), 'user'))
  const m = text ? EPOCH_MARKER.exec(text) : null
  return m ? m[1] : null
}

/** 把指令里的 epoch 写进工具入参；返回是否真的改写（未改写的请求落盘件逐字节不变）。 */
function applyEpoch(decision, epoch) {
  if (!epoch || decision.kind !== 'tool_call') return false
  if (!decision.args || typeof decision.args !== 'object' || Array.isArray(decision.args)) return false
  if (!Object.prototype.hasOwnProperty.call(decision.args, 'epoch')) return false
  decision.args.epoch = epoch
  decision.epoch = epoch
  return true
}

/** 交错轮：一条消息里 text→tool_calls（`t→tool` 半段），下一轮再来一次（`tool→t` 半段）。 */
function interleaveRound(text, tool, args, callId, model, round) {
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({ content: text }, null, model),
    chunk({
      tool_calls: [{
        index: 0,
        id: callId,
        type: 'function',
        function: { name: tool, arguments: JSON.stringify(args) }
      }]
    }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ].map((f) => f)
}

/** 多卡轮：n 个 tool_calls（id 互异）落在同一条 assistant 消息里（H-04 的"一批多张待决卡"）。 */
function multiToolRound(tool, args, callId, model, n) {
  const calls = []
  for (let i = 0; i < n; i++) {
    calls.push({
      index: i,
      id: `${callId}-${i + 1}`,
      type: 'function',
      function: { name: tool, arguments: JSON.stringify(args) }
    })
  }
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({ tool_calls: calls }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ]
}

/** 大帧轮：**单个** delta 帧携带 N 字节（帧侧 64KB 上限与 JSON 合法性的注入面）。 */
function hugeTextRound(bytes, model) {
  const unit = '大帧注入面ABCDEFGHIJ'
  const text = unit.repeat(Math.ceil(bytes / unit.length)).slice(0, Math.max(1, bytes))
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({ content: text }, null, model),
    chunk({}, 'stop', model),
    'data: [DONE]\n\n'
  ]
}

/** 越窗错误体：措辞取产品 `ContextOverflow.MARKERS` 里"只在这一种情况下出现"的短语。 */
const OVERFLOW_BODY = {
  error: {
    message: "This model's maximum context length is 200000 tokens. However, your messages resulted in 300001 tokens.",
    type: 'invalid_request_error',
    code: 'context_length_exceeded'
  }
}

/** 摘要请求识别（`RollingSummarizer` 的两段固定指令头；与产品提示词同源，不猜调用方身份）。 */
const SUMMARY_REQUEST = /You (summarize an AI assistant conversation|merge partial summaries of one AI assistant conversation)/

/** `summary-500` 的 arm 状态：mark 命中后，**下一条**摘要请求 500 并解除（一次性，可重 arm）。 */
const summaryFault = { armedUntilMs: 0 }

/**
 * `fail-503-once` 的一次性台账（按会话指纹）：同一轮只 503 一次，第二次起正常回帧 ——
 * 这是 S-07「首请求 503、次请求 200」的确定性底座（重试退避的**可观测**面 = attempts≥2）。
 */
const failOnceSeen = new Set()

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)))
}


// ── 请求解析辅助 ───────────────────────────────────────────────────────────

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function toolNames(body) {
  const tools = Array.isArray(body?.tools) ? body.tools : []
  return tools.map((t) => t?.function?.name).filter((n) => typeof n === 'string' && n.length > 0)
}

function messagesOf(body) {
  return Array.isArray(body?.messages) ? body.messages : []
}

function lastMessageWithRole(messages, role) {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === role) return messages[i]
  }
  return null
}

function textOf(message) {
  if (!message) return ''
  const content = message.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.map((p) => (typeof p === 'string' ? p : p?.text ?? '')).join('')
  }
  return ''
}

/**
 * 工具结果内容的 JSON 反解（设计草案 §2.4 的形状细节）：内容可能是对象、被序列化过一层的字符串、
 * 或"前缀文字 + JSON"的混合体——故先按 JSON 解（最多两层），再用括号配对取首个平衡的 `{...}`。
 */
function extractJsonObject(raw) {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'object') return Array.isArray(raw) ? null : raw
  if (typeof raw !== 'string') return null
  let value = raw.trim()
  for (let depth = 0; depth < 2; depth++) {
    const parsed = safeJson(value)
    if (parsed === undefined) break
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
    if (typeof parsed === 'string') {
      value = parsed.trim()
      continue
    }
    break
  }
  const start = value.indexOf('{')
  if (start < 0) return null
  let depth = 0
  let inString = false
  let escape = false
  for (let i = start; i < value.length; i++) {
    const ch = value[i]
    if (escape) {
      escape = false
      continue
    }
    if (ch === '\\') {
      escape = true
      continue
    }
    if (ch === '"') {
      inString = !inString
      continue
    }
    if (inString) continue
    if (ch === '{') depth++
    else if (ch === '}') {
      depth--
      if (depth === 0) {
        const candidate = safeJson(value.slice(start, i + 1))
        return candidate && typeof candidate === 'object' && !Array.isArray(candidate) ? candidate : null
      }
    }
  }
  return null
}

/** 从用户消息里取任务号：优先"任务 <n>"，退化为首个数字串。 */
function extractTaskId(text) {
  const explicit = /任务\s*(\d+)/.exec(text || '')
  if (explicit) return Number(explicit[1])
  const loose = /(\d+)/.exec(text || '')
  return loose ? Number(loose[1]) : null
}

/** 占位符替换：整串 `${taskId}` 换成数字，否则按字符串插值。 */
function resolveArguments(value, vars) {
  if (typeof value === 'string') {
    const whole = /^\$\{(\w+)\}$/.exec(value)
    if (whole) return vars[whole[1]] ?? null
    return value.replace(/\$\{(\w+)\}/g, (_, k) => String(vars[k] ?? ''))
  }
  if (Array.isArray(value)) return value.map((v) => resolveArguments(v, vars))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(value)) out[k] = resolveArguments(v, vars)
    return out
  }
  return value
}

// ── 判定（桩的大脑；纯函数，供 --self-check 直接调用） ─────────────────────

/** 会话指纹：首条 user 消息（同一提示词的重试会在 messages 长度回退时被识别为新轮次）。 */
function fingerprint(messages) {
  const first = messages.find((m) => m?.role === 'user')
  return (textOf(first) || '(空)').slice(0, 160)
}

function formatValue(value) {
  if (Array.isArray(value)) return value.map((v) => formatValue(v)).join(', ')
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const CLOSING_TEXT = '好的，本轮已完成，请查看上方的结果。'

/** 续轮的文本收尾：把最后一次工具结果里的值按 key=值 原样回报（满足 Gf-Assert-Echo）。 */
function echoText(messages) {
  const toolMessage = lastMessageWithRole(messages, 'tool')
  const parsed = extractJsonObject(textOf(toolMessage))
  if (!parsed) return CLOSING_TEXT
  const keys = Object.keys(parsed)
  if (keys.length === 0) return CLOSING_TEXT
  const lines = ['已按 key=值 原样回报：']
  for (const key of keys) lines.push(`${key}=${formatValue(parsed[key])}`)
  return lines.join('\n')
}

/**
 * **本轮**已出现的工具结果 = 最后一条 `user` 消息之后的 `role=tool`（收敛契约 ③ 的判据）。
 *
 * 判据必须按「本轮起点」裁，不能按「上下文里有没有 role=tool」：新用户轮的上下文同样带着
 * 上一轮的工具结果（L-75 起取消轮也会落配对的 `role=tool`）⇒ 按上下文裁会把新轮的首次请求
 * 误判成续轮、永不下发 tool_calls（L-84）。没有 user 消息时（探活/异常请求）退化为「全部工具结果
 * 都算本轮」，与旧判据同义。
 */
function roundToolResults(messages) {
  let start = 0
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === 'user') {
      start = i + 1
      break
    }
  }
  return messages.slice(start).filter((m) => m?.role === 'tool')
}

/**
 * 本轮判定。返回 `{kind, ...}`；调用方只认 `tool_call` 与 `text` 两种。
 *
 * @param body    上游请求体（OpenAI 兼容）
 * @param routes  路由表
 * @param turns   会话轮次台账（跨请求累积；收敛契约的载体）
 * @param overrides 自检用的注入项（{messages, tools}），不传则取自 body
 */
function decide(body, routes, turns, overrides = {}) {
  const messages = overrides.messages ?? messagesOf(body)
  const disclosed = new Set(overrides.tools ?? toolNames(body))
  const maxCalls = Number(routes.convergence?.maxCallsPerTurn ?? 6)

  const fp = fingerprint(messages)
  let bucket = turns.get(fp)
  if (!bucket) {
    bucket = { calls: 0, lastLen: -1, used: [], requestToolUsed: '' }
    turns.set(fp, bucket)
  }
  // 同一提示词的新轮次（重试/换会话）：**仅当消息数严格回退**时才视为新轮次并清零台账。
  // 等长（后端 ResilientChatService 流失败后原样重发同一请求的真实形态）不得清零——
  // 否则 calls 与 used 双双重置，轮次上限与"每路由一次"两道闸会被系统性绕过（红队问题 1）。
  if (messages.length < bucket.lastLen) {
    bucket.calls = 0
    bucket.used = []
    bucket.requestToolUsed = ''
  }
  bucket.lastLen = messages.length
  bucket.calls += 1

  if (roundToolResults(messages).length > 0) {
    // 收敛契约 ③：本轮已出现工具结果 = 续轮，只发文本
    return { kind: 'text', text: echoText(messages), reason: '续轮（本轮已出现工具结果）' }
  }
  if (bucket.calls > maxCalls) {
    // 收敛契约 ②：轮次上限兜底
    return { kind: 'text', text: CLOSING_TEXT, reason: `超出轮次上限 ${maxCalls}` }
  }
  const userText = textOf(lastMessageWithRole(messages, 'user'))
  if (!userText) {
    return { kind: 'text', text: CLOSING_TEXT, reason: '无用户消息（探活/异常请求）' }
  }
  // 请求级工具路由（`tool=<name>&args=<json>`）：提示词自述要调什么；
  // 披露闸（收敛契约 ①）与产品工具清单两道闸先行，任一不过 ⇒ 退化为文本轮（不制造请求风暴）。
  const wanted = requestToolOf(body)
  if (wanted) {
    const productTools = new Set(Array.isArray(routes.productTools) ? routes.productTools : [])
    if (!productTools.has(wanted.name)) {
      return { kind: 'text', text: CLOSING_TEXT, reason: `请求级工具 ${wanted.name} 不在产品工具清单（productTools）` }
    }
    if (!disclosed.has(wanted.name)) {
      return { kind: 'text', text: CLOSING_TEXT, reason: `请求级工具 ${wanted.name} 未在本轮披露清单（requiresTools 闸）` }
    }
    if (wanted.badArgs) {
      return { kind: 'text', text: CLOSING_TEXT, reason: `请求级工具 ${wanted.name} 入参不可解：${wanted.badArgs}` }
    }
    if (bucket.requestToolUsed === wanted.name) {
      // 幂等闸（收敛契约 ② 的请求级版本）：同一提示词只发一次工具调用，
      // 流失败后的原样重试因此不会变成"同一轮反复调工具"的请求风暴。
      return { kind: 'text', text: CLOSING_TEXT, reason: `请求级工具 ${wanted.name} 已在本提示词发过（幂等闸）` }
    }
    // 台账（每路由一次）只记路由表路由：请求级标记不占用 route.id 名额，
    // 同提示词的下一轮由幂等闸 + "续轮只发文本"两条契约收口。
    bucket.requestToolUsed = wanted.name
    return { kind: 'tool_call', tool: wanted.name, args: wanted.args, route: 'REQUEST-TOOL',
      reason: `请求级工具标记 tool=${wanted.name}` }
  }
  for (const route of routes.routes || []) {
    const requires = Array.isArray(route.match?.requiresTools) ? route.match.requiresTools : []
    // 收敛契约 ①：披露清单先行——未披露的工具不允许发起调用
    if (requires.length === 0 || !requires.every((t) => disclosed.has(t))) continue
    const needles = Array.isArray(route.match?.messageAny) ? route.match.messageAny : []
    if (!needles.some((n) => userText.includes(n))) continue
    if (bucket.used.includes(route.id)) continue
    let args
    try {
      args = resolveArguments(route.arguments ?? {}, { taskId: extractTaskId(userText) })
    } catch (err) {
      // 收敛契约 ④：解析失败不猜，退化为文本
      return { kind: 'text', text: CLOSING_TEXT, reason: `路由 ${route.id} 参数解析失败：${err.message}` }
    }
    bucket.used.push(route.id)
    return { kind: 'tool_call', tool: route.tool, args, route: route.id, reason: `命中路由 ${route.id}` }
  }
  return { kind: 'text', text: CLOSING_TEXT, reason: '未命中任何路由（文本收尾）' }
}

// ── SSE 帧 ─────────────────────────────────────────────────────────────────

let frameSeq = 0

function chunk(delta, finishReason = null, model = 'ci-stub', usage = null) {
  const body = {
    id: `chatcmpl-stub-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }]
  }
  // 只有形状 repeat / zero-last 会走这条分支；其余情况该键不写，既有请求的帧逐字节不变
  if (usage) body.usage = usage
  return `data: ${JSON.stringify(body)}\n\n`
}

/** usage-only 帧（`choices: []` + usage）：OpenAI `stream_options.include_usage` 的形态。 */
function usageChunk(usage, model) {
  return `data: ${JSON.stringify({
    id: `chatcmpl-stub-${++frameSeq}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [],
    usage
  })}\n\n`
}

/** 末块 0/0/0：直读末帧的实现会把它当真值（`ContextMeter` 的 R1 必须跳过）。 */
const ZERO_USAGE = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }

/**
 * 思考分片（`delta.reasoning_content`，OpenAI 兼容线的 snake_case）：L-91 的**上游侧**形态。
 *
 * 桩只发上游 chunk，不发 SSE 帧 —— `reasoning_content` → `delta{kind:'reasoning'}`
 * 与段边界（`message_start/message_end{kind:'reasoning'}`）是产品侧
 * `ResilientChatService#consume` + `SseChatEmitter#reasoningDelta` 的职责。
 *
 * 分片数 1–2：短思考拆 2 片（覆盖"多片累加 + `message_end.chars` 合计"）；
 * 已达帧侧 64KB 闸的思考文本保持**单片** —— 那正是产品字段级截断
 * （`truncated/truncatedReason`）的观测面，拆两片反而把每片压回闸下、观测面消失。
 */
const REASONING_UNIT = '思考注入面：先核对费率口径与章节位置，再给出结论；本段由桩按需重复填充。'
const REASONING_FRAME_CAP = 64 * 1024

function reasoningChunks(chars, model) {
  const total = Math.max(0, Math.floor(Number(chars) || 0))
  if (total <= 0) return []
  const text = REASONING_UNIT.repeat(Math.ceil(total / REASONING_UNIT.length)).slice(0, total)
  if (text.length <= 1 || text.length > REASONING_FRAME_CAP) {
    return [chunk({ reasoning_content: text }, null, model)]
  }
  const cut = Math.ceil(text.length / 2)
  return [
    chunk({ reasoning_content: text.slice(0, cut) }, null, model),
    chunk({ reasoning_content: text.slice(cut) }, null, model)
  ]
}

/**
 * 文本轮：分片 content → finish_reason=stop → usage 帧 → [DONE]（硬规范②：必须有终帧）。
 *
 * `shapeName` 决定 usage 的落点（形状表见 {@link usageShapes}）：
 *   - `last`（默认）：只在末个 usage-only 帧发一份 —— 改造前的既有行为，逐字节不变；
 *   - `repeat`：**每个 content 分片都挂同一份**，末帧再挂一次（每块重复）；
 *   - `zero-last`：首个 content 分片挂真值，末个 usage-only 帧挂 0/0/0；
 *   - `none`：整轮不发 usage（产品必须回退估算）。
 */
function textRound(text, model, shapeName = 'last') {
  const shapes = usageShapes()
  const name = USAGE_SHAPE_NAMES.includes(shapeName) ? shapeName : 'last'
  const usage = name === 'none' ? null : usageOf(shapes[name])
  const out = [chunk({ role: 'assistant', content: '' }, null, model)]
  const pieces = String(text).match(/[\s\S]{1,24}/g) || [String(text)]
  pieces.forEach((piece, index) => {
    const attach = usage && (name === 'repeat' || (name === 'zero-last' && index === 0))
    out.push(attach ? chunk({ content: piece }, null, model, usage) : chunk({ content: piece }, null, model))
  })
  out.push(chunk({}, 'stop', model))
  if (usage) out.push(usageChunk(name === 'zero-last' ? ZERO_USAGE : usage, model))
  out.push('data: [DONE]\n\n')
  return out
}

/** 工具轮：单帧聚合 tool_calls（index=0 一次给全）→ finish_reason=tool_calls → [DONE]。 */
function toolRound(tool, args, callId, model) {
  return [
    chunk({ role: 'assistant', content: '' }, null, model),
    chunk({
      tool_calls: [{
        index: 0,
        id: callId,
        type: 'function',
        function: { name: tool, arguments: JSON.stringify(args) }
      }]
    }, null, model),
    chunk({}, 'tool_calls', model),
    'data: [DONE]\n\n'
  ]
}

/** 迟到帧（S-12）：`[DONE]` 之后继续推的帧，客户端必须整帧丢弃（不入 parts、不改卡、不进镜像）。 */
function lateFrameChunks(n, model) {
  const out = []
  for (let i = 0; i < n; i++) {
    out.push(chunk({ content: `迟到帧${i + 1}：done 之后不应入库` }, null, model))
  }
  return out
}

// ── 服务与落盘 ─────────────────────────────────────────────────────────────

const turns = new Map()
let requestSeq = 0

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

function headLines(messages) {
  return messages.map((m, i) => {
    const text = textOf(m)
    const calls = Array.isArray(m?.tool_calls) ? m.tool_calls.map((c) => c?.function?.name).join(',') : ''
    const id = m?.tool_call_id ? ` tool_call_id=${m.tool_call_id}` : ''
    const toolCalls = calls ? ` tool_calls=[${calls}]` : ''
    return `[${i}] role=${m?.role}${id}${toolCalls} chars=${text.length}\n${text.slice(0, 300)}`
  }).join('\n\n')
}

function record(index, raw, body, decision) {
  const tag = String(index).padStart(3, '0')
  fs.writeFileSync(path.join(DIR, `req-${tag}.json`), raw, 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.tools.txt`),
    `disclosedCount=${toolNames(body).length}\n${toolNames(body).join('\n')}\n`, 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.head.txt`), headLines(messagesOf(body)), 'utf8')
  fs.writeFileSync(path.join(DIR, `req-${tag}.decision.json`),
    JSON.stringify(decision, null, 2), 'utf8')
}

/** 全部消息文本（摘要请求识别用：摘要请求不带对话提示词标记）。 */
function allText(body) {
  return messagesOf(body).map((m) => textOf(m)).join('\n')
}

/** 交错轮的参数表：只挑**空参**后端工具，避免蓝图轮依赖业务夹具（夹具留给业务用例）。 */
const INTERLEAVE_TOOLS = ['list_config_defs', 'get_workspace_state', 'get_row_count']

/**
 * 安全面探针（L-137，2026-10-24）：X-02 / X-03 的**确定性回复主动携带**探针串。
 *
 * 背景：L-99 删 DLP 后，输出侧既不 mask 也不 block，而桩原先的 `turn-text` 蓝本不含任何密钥形态
 * ⇒ ps1 里 X-02（要求输出被 mask）/ X-03（要求私钥块被阻断）**只是"没被触发"的空断言**。
 * 这里让桩按请求级标记回出探针：X-02 = 跨分片的假密钥串，X-03 = 完整假 PEM 块。
 * 文本统一走 {@link textRound} 的 24 字符切片 ⇒ 断言方可以核"密钥 / PEM 确实被切开、拼回即原文"。
 *
 * 探针串全部是测试用假串：`sk-` 前缀与 PEM 头尾都按片段拼接，避免与提交前密钥扫描规则撞车。
 */
const SAFETY_STREAM_PROBE = 's' + 'k-' + 'B'.repeat(40)
/** 前缀 10 字符 + 密钥 43 字符 ⇒ 密钥横跨第 1/2/3 片（24 字符切点落在密钥内部）。 */
const SAFETY_STREAM_TEXT = `密钥一：token=${SAFETY_STREAM_PROBE} 结束。`
const SAFETY_PEM_HEAD = '-----BEGIN' + ' RSA PRIVATE KEY-----'
const SAFETY_PEM_TAIL = '-----END' + ' RSA PRIVATE KEY-----'
const SAFETY_PEM = [
  SAFETY_PEM_HEAD,
  'MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn',
  SAFETY_PEM_TAIL
].join('\n')
const SAFETY_PEM_TEXT = ['下面是配置片段：', '', SAFETY_PEM, '', '片段结束。'].join('\n')

/**
 * X-02 / X-03 的请求级探针判定（返回 `null` = 不是这两个用例，照常走蓝本/路由表）。
 * 只认最后一条 user 消息里的**用例标记**，不看工具披露与其他开关 ⇒ 探针不污染既有语料。
 */
function safetyProbeDecision(body) {
  const userText = textOf(lastMessageWithRole(messagesOf(body), 'user'))
  if (userText.includes('【AIE2E-X-02】')) {
    return { kind: 'text', text: SAFETY_STREAM_TEXT, probe: 'stream-key', reason: 'X-02 请求级跨分片假密钥探针' }
  }
  if (userText.includes('【AIE2E-X-03】')) {
    return { kind: 'text', text: SAFETY_PEM_TEXT, probe: 'pem', reason: 'X-03 请求级完整假 PEM 探针' }
  }
  return null
}

/**
 * 命名轮蓝本 / 步数上限的判定覆盖（返回 `null` = 不覆盖，走 `decide` 的路由表）。
 * 纯函数：`--self-check` 用合成 messages 直接断言段序与步数。
 */
function blueprintDecision(body, faults) {
  const messages = messagesOf(body)
  const disclosed = new Set(toolNames(body))
  const toolMessages = messages.filter((m) => m?.role === 'tool').length
  const safetyProbe = safetyProbeDecision(body)
  if (safetyProbe) return safetyProbe
  if (faults['turn-text']) {
    return { kind: 'text', text: TURN_TEXT, reason: '命名轮蓝本 turn-text（确定性文本）' }
  }
  if (faults['turn-interleave']) {
    const tool = INTERLEAVE_TOOLS.find((t) => disclosed.has(t))
    if (!tool) {
      return { kind: 'text', text: TURN_TEXT, reason: '命名轮蓝本 turn-interleave（无可用空参工具，退化为文本）' }
    }
    if (toolMessages >= 2) {
      return { kind: 'text', text: '交错轮收尾：两段工具之后回到文本。', reason: '交错轮第 3 段（收尾文本）' }
    }
    return { kind: 'tool_call', tool, args: {}, route: 'TURN-INTERLEAVE',
      reason: `交错轮第 ${toolMessages + 1} 段（text→tool）` }
  }
  if (faults['tool-loop']) {
    const limit = numericFault(faults, 'tool-loop') || FAULT_DEFAULTS['tool-loop']
    const tool = INTERLEAVE_TOOLS.find((t) => disclosed.has(t))
    if (!tool || toolMessages >= limit) {
      return { kind: 'text', text: CLOSING_TEXT, reason: `步数轮收尾（已 ${toolMessages} 次，上限 ${limit}）` }
    }
    return { kind: 'tool_call', tool, args: {}, route: 'TOOL-LOOP',
      reason: `步数轮第 ${toolMessages + 1}/${limit} 次` }
  }
  return null
}

/** 本轮帧序列（纯函数）：故障开关只改"帧怎么发"，不改路由判定。 */
function framesFor(decision, faults, index, model, usageShape) {
  const callId = `call-stub-${index}`
  if (decision.kind !== 'tool_call') {
    const base = faults['huge-frame']
      ? hugeTextRound(numericFault(faults, 'huge-frame'), model)
      : textRound(decision.text, model, usageShape)
    return withReasoning(base, faults, model)
  }
  const n = faults['multi-card'] ? numericFault(faults, 'multi-card') : 1
  if (n > 1) return multiToolRound(decision.tool, decision.args, callId, model, n)
  return toolRound(decision.tool, decision.args, callId, model)
}

/**
 * 把思考分片插在**首帧（`{role}`）之后、正文分片之前** ⇒ 收尾顺序恒为
 * `[{role}, {reasoning_content}…, {content}…, {}, finish_reason]`（与真模型"先想再答"同形）。
 *
 * 开关关闭（缺省 / `reasoning=0`）时原样返回入参：既有用例的精确帧数断言零漂移。
 * 与 `huge-frame` 正交：大帧轮同样是"首帧 role + 单个 text delta"，插在此处即可覆盖
 * "超 64KB 思考文本"与"超 64KB 正文文本"两个字段级截断面。
 */
function withReasoning(frames, faults, model) {
  const chars = faults.reasoning ? numericFault(faults, 'reasoning') : 0
  if (chars <= 0) return frames
  return [frames[0], ...reasoningChunks(chars, model), ...frames.slice(1)]
}

/**
 * 按写出计划发帧（`--self-check` 只断言 {@link writePlan} 的纯结果，本函数不在自检里跑）。
 * 返回 `ok` / `broken`（break-at-seq 断流）/ `killed`（kill-stream 到点）。
 */
async function writeFrames(res, frames, plan) {
  let killed = false
  let timer = null
  if (plan.killAfterMs > 0) {
    timer = setTimeout(() => {
      killed = true
      try { res.socket?.destroy() } catch { /* 已断开 */ }
    }, plan.killAfterMs)
  }
  try {
    const limit = plan.breakAtFrame > 0 ? Math.min(plan.breakAtFrame, frames.length) : frames.length
    for (let i = 0; i < limit; i++) {
      if (killed) return 'killed'
      if (i === 0) {
        // 首包段（写头之后、首帧之前）→ 工具执行段 → 既有 slow-tool；三者各自独立，互不叠加语义
        if (plan.firstByteDelayMs > 0) await sleep(plan.firstByteDelayMs)
        if (plan.toolExecDelayMs > 0) await sleep(plan.toolExecDelayMs)
        if (plan.firstFrameDelayMs > 0) await sleep(plan.firstFrameDelayMs)
      }
      if (i > 0) {
        const gap = Math.max(plan.frameGapMs, plan.tailFrameGapMs)
        if (gap > 0) await sleep(gap)
      }
      res.write(frames[i])
    }
    if (plan.breakAtFrame > 0 && plan.breakAtFrame < frames.length) {
      // 断流形态：不发 [DONE]、不 res.end，直接销毁 socket（客户端看到 EOF/reset）
      try { res.socket?.destroy() } catch { /* 已断开 */ }
      return 'broken'
    }
    // 迟到帧：整轮（含 [DONE]）写完之后继续推 —— 客户端必须整帧丢弃
    if (plan.lateFrames > 0) {
      for (const late of lateFrameChunks(plan.lateFrames, plan.model)) {
        if (killed) break
        res.write(late)
        await sleep(20)
      }
    }
    if (!killed) res.end()
    return killed ? 'killed' : 'ok'
  } finally {
    if (timer) clearTimeout(timer)
  }
}

async function handleChat(req, res, raw) {
  const index = ++requestSeq
  const body = safeJson(raw)
  const model = typeof body?.model === 'string' && body.model ? body.model : 'ci-stub'
  // 形状与故障都是**请求级**开关（与 --violate-form-type 同族）：未带标记的请求帧逐字节不变
  const usageShape = usageShapeOf(body ?? {})
  const faults = body ? faultsOf(body) : {}
  const summaryRequest = SUMMARY_REQUEST.test(allText(body ?? {}))
  // summary-500：arm 后**下一条**摘要请求 500（一次性；armed 标记随标记请求写入）
  if (faults['summary-500']) summaryFault.armedUntilMs = Date.now() + 300000
  if (summaryRequest && summaryFault.armedUntilMs > Date.now()) {
    summaryFault.armedUntilMs = 0
    record(index, raw, body, { kind: 'error', status: 500, reason: 'summary-500 注入（摘要请求回 5xx）' })
    res.writeHead(500, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: { message: 'stub: summary model unavailable', type: 'server_error' } }))
    console.log(`[req-${String(index).padStart(3, '0')}] summary-500 注入 → 500`)
    return
  }
  // fail-503-once：同一轮首次 503、之后正常（重试退避的可观测面 = attempts ≥ 2 且只 1 个 done）
  if (faults['fail-503-once']) {
    const fp = fingerprint(messagesOf(body ?? {}))
    if (!failOnceSeen.has(fp)) {
      failOnceSeen.add(fp)
      record(index, raw, body ?? {}, { kind: 'error', status: 503, reason: 'fail-503-once 注入（首次 503）' })
      res.writeHead(503, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ error: { message: 'stub: temporarily unavailable', type: 'server_error' } }))
      console.log(`[req-${String(index).padStart(3, '0')}] fail-503-once 注入 → 503`)
      return
    }
  }
  // context-overflow：上游回 400 + 越窗措辞（产品必须识别为 CONTEXT_OVERFLOW 且不重试）
  if (faults['context-overflow']) {
    record(index, raw, body ?? {}, { kind: 'error', status: 400, reason: 'context-overflow 注入（越窗 400）' })
    res.writeHead(400, { 'content-type': 'application/json' })
    res.end(JSON.stringify(OVERFLOW_BODY))
    console.log(`[req-${String(index).padStart(3, '0')}] context-overflow 注入 → 400`)
    return
  }
  let decision
  if (!body) {
    decision = { kind: 'text', text: CLOSING_TEXT, reason: '请求体不是合法 JSON（退化为文本）' }
  } else {
    try {
      decision = blueprintDecision(body, faults) ?? decide(body, ROUTES, turns)
    } catch (err) {
      decision = { kind: 'text', text: CLOSING_TEXT, reason: `判定异常：${err.message}` }
    }
  }
  // 归因面（记录契约）：非默认形状写进 decision.json，让"这轮为什么是伪值"可追溯；
  // 默认形状不写键 ⇒ 既有 29 用例的落盘件逐字节不变。
  if (usageShape !== 'last') decision.usageShape = usageShape
  const faultNames = Object.keys(faults)
  if (faultNames.length) decision.faults = faultNames
  // 请求级 epoch 注入（V 域成功路径的模型侧底座）：本轮工具入参里已有的 epoch 换成提示词给的 token
  if (applyEpoch(decision, epochOf(body ?? {}))) {
    console.log(`[req-${String(index).padStart(3, '0')}] epoch 注入 → ${decision.epoch}`)
  }
  // 注入必须先于落盘（T4-S2-6）：`record` 会把 decision **立即** 序列化进 req-NN.decision.json，
  // 它自称"桩收到的原请求与判定"的失败归因三件套之一 —— 若先落盘再注入，GF6 失败时落盘的判定
  // 是注入前的合法 schema，与 SSE 实际回帧（注入后的 html）相反，会把排查方向引偏。
  const violationNote = applyRequestViolation(decision, body ?? {})
  if (violationNote) console.log(`[违规模式] ${violationNote}`)
  record(index, raw, body ?? {}, decision)

  // 段身份（细分延迟注入的判定依据）：本请求既可能是工具轮（要发 tool_calls），
  // 也可能是工具续轮（上下文里已有 role=tool ⇒ 收尾文本段）。
  const roundCtx = {
    kind: decision.kind,
    hasToolResult: body ? messagesOf(body).some((m) => m?.role === 'tool') : false
  }
  const plan = { ...writePlan(faults, roundCtx), model }
  if (plan.headerDelayMs > 0) await sleep(plan.headerDelayMs)
  if (plan.firstFrameDelayMs > 0 && decision.kind !== 'tool_call') plan.firstFrameDelayMs = 0
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })
  const frames = framesFor(decision, faults, index, model, usageShape)
  let outcome = 'ok'
  try {
    outcome = await writeFrames(res, frames, plan)
  } catch (err) {
    // 客户端在响应写出过程中断开（socket destroyed）属正常现象，不许升级为进程级异常
    fs.writeSync(2, `[WARN] 响应写出失败（客户端可能已断开）req-${index}：${err?.message ?? err}\n`)
  }
  console.log(`[req-${String(index).padStart(3, '0')}] ${decision.kind}${decision.tool ? ` tool=${decision.tool}` : ''} route=${decision.route ?? '-'} outcome=${outcome}${faultNames.length ? ` faults=${faultNames.join('+')}` : ''} —— ${decision.reason}`)
}

/**
 * 崩溃兜底（红队问题 2）：`fs.writeSync` 直接写文件描述符，绕开可能丢失的流缓冲——
 * CI 上桩的 stdout/stderr 被重定向到文件，若走 process.stdout.write 的异步路径，
 * 进程被击杀时崩溃栈可能不落盘，导致"桩死无对证"（表现为 boot.log 里的 Connection refused）。
 * 同时把栈同步追写一份到 `<dir>/stub-crash.log`，随 artifact 一起归档。
 */
function crashReport(kind, err) {
  const stack = err && err.stack ? err.stack : String(err)
  const text = `\n[FATAL] ${kind} ${new Date().toISOString()}\n${stack}\n`
  try {
    fs.writeSync(2, text)
  } catch {
    /* ignore */
  }
  try {
    fs.writeSync(fs.openSync(path.join(DIR, 'stub-crash.log'), 'a'), text)
  } catch {
    /* ignore */
  }
  process.exit(1)
}

/**
 * 桩返回的模型清单（模型配置专项批②；`GET /models`）。
 *
 * 只放两个 id：设置页的"拉取模型 → 勾选加入 allowlist"链路需要一个**可勾选**的列表，
 * 数量与命名不影响任何断言。新增 id 时顺手看一眼 `AiConnectionProbe` 的单测（若它按名字断言）。
 */
const STUB_MODEL_IDS = ['stub-model-a', 'stub-model-b']

/**
 * GET /models（OpenAI 兼容的模型列表端点；模型配置专项批② 的 E2 用例依赖它）。
 *
 * 故障注入（查询参数，与 `AiConnectionProbe` 的单测同形 —— 产品侧按状态码判四档）：
 *   `?models=401` / `403` ⇒ AUTH_FAILED
 *   `?models=404` / `405` / `500` ⇒ MODELS_UNSUPPORTED（该端点没实现 /models）
 *   `?models=empty` ⇒ 200 + `{data:[]}`（合法但空）
 *   `?models=notarray` ⇒ 200 + `{data:"nope"}` ⇒ MODELS_INVALID
 * 不带参数 ⇒ 200 + `{data:[{id},…]}`。
 *
 * 口径：本桩**不**校验 Authorization —— 401/403 只由上面的查询参数产生（否则 E2E 里"随便一个
 * 占位 key"就过不去，而桩本来就用占位 key）。
 */
function handleModels(req, res) {
  const url = new URL(req.url ?? '/models', `http://127.0.0.1:${PORT}`)
  const mode = url.searchParams.get('models') ?? ''
  const send = (status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' })
    res.end(typeof body === 'string' ? body : JSON.stringify(body))
  }
  switch (mode) {
    case '401':
      send(401, { error: { message: 'stub: unauthorized', type: 'invalid_request_error' } })
      break
    case '403':
      send(403, { error: { message: 'stub: forbidden', type: 'invalid_request_error' } })
      break
    case '404':
      send(404, { error: { message: 'stub: no such endpoint', type: 'invalid_request_error' } })
      break
    case '405':
      send(405, { error: { message: 'stub: method not allowed', type: 'invalid_request_error' } })
      break
    case '500':
      send(500, { error: { message: 'stub: boom', type: 'server_error' } })
      break
    case 'empty':
      send(200, { object: 'list', data: [] })
      break
    case 'notarray':
      send(200, { object: 'list', data: 'nope' })
      break
    default:
      send(200, {
        object: 'list',
        data: STUB_MODEL_IDS.map((id) => ({ id, object: 'model', owned_by: 'stub' }))
      })
  }
  fs.writeSync(1, `[models] ${mode || 'ok'} → ${res.statusCode}\n`)
}

function startServer() {
  ensureDir(DIR)
  // 进程级兜底：任何未捕获异常/未处理拒绝都必须留下栈并以退出码 1 死亡（不许静默消失）
  process.on('uncaughtException', (err) => crashReport('uncaughtException', err))
  process.on('unhandledRejection', (reason) => crashReport('unhandledRejection', reason))

  const server = http.createServer((req, res) => {
    // 连接级兜底：客户端中途断开/重置属正常攻击面，只记日志，绝不升级为进程级异常
    req.on('error', (err) => fs.writeSync(2, `[WARN] 请求流错误：${err?.message ?? err}\n`))
    res.on('error', (err) => fs.writeSync(2, `[WARN] 响应流错误：${err?.message ?? err}\n`))
    // 模型配置专项批②：GET /models（OpenAI 兼容端点列表）—— 设置页「测试连接」与「拉取模型」的上游。
    // 故障注入口径与 AiConnectionProbe 的测试同形：?models=401|403|404|405|500|empty|notarray。
    if ((req.url ?? '').split('?')[0].endsWith('/models')) {
      handleModels(req, res)
      return
    }
    if (req.method !== 'POST') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end('{"ok":true,"stub":"ci-upstream"}')
      return
    }
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      try {
        const raw = Buffer.concat(chunks).toString('utf8')
        if (!(req.url ?? '').includes('/chat/completions')) {
          res.writeHead(200, { 'content-type': 'application/json' })
          res.end('{"ok":true}')
          return
        }
        // handleChat 是 async（stall/kill 类开关要按时序发帧）：异常必须落到 catch 里，
        // 不许变成 unhandledRejection（那会被 crashReport 判成进程崩溃）
        handleChat(req, res, raw).catch((err) => {
          fs.writeSync(2, `[ERROR] 处理请求失败（进程存活）：${err?.stack ?? err}\n`)
          try {
            res.writeHead(500, { 'content-type': 'application/json' })
            res.end('{"error":"stub-handle-failed"}')
          } catch {
            /* ignore */
          }
        })
      } catch (err) {
        // 判定/落盘/写出任一步骤异常都不许杀死桩：回 500 并记栈，进程继续服务后续请求
        fs.writeSync(2, `[ERROR] 处理请求失败（进程存活）：${err?.stack ?? err}\n`)
        try {
          res.writeHead(500, { 'content-type': 'application/json' })
          res.end('{"error":"stub-handle-failed"}')
        } catch {
          /* ignore */
        }
      }
    })
  })
  // 监听失败（如 EADDRINUSE）必须显式留栈退出，不留"半死"状态
  server.on('error', (err) => crashReport('server.error', err))
  server.listen(PORT, '127.0.0.1', () => {
    console.log(`stub-upstream 已就绪 http://127.0.0.1:${PORT} dir=${DIR} routes=${ROUTES_PATH} maxCallsPerTurn=${ROUTES.convergence?.maxCallsPerTurn ?? 6}`)
    for (const note of violations) console.log(`[违规模式] ${note}`)
    if (VIOLATE_FORM_TYPE) {
      console.log(`[违规模式] violate-form-type（请求级）：仅命中 ${VIOLATION_REQUEST_MARKER} 的请求注入越白名单类型（GF6 用例；GF1/GF3/GF4 不受影响）`)
    }
  })
}

// ── 自检（--self-check：契约校验 + 收敛自证，不启动服务） ─────────────────────

// ── 产品事实源逐值对账（T4-5 裁决 5：自检期读 GenerativeFormRules.java，不新增端点/生成步骤） ──
//   事实源 = 后端白名单规则本体；本步读它的常量声明，对桩内 8 组常量 + 5 项上限 + 原型三键逐值比对，
//   使"产品侧收紧白名单而桩自检仍绿"的漂移窗口关闭（对账红 → 自检红 → e2e job 红，不再静默）。
//   依赖面：对 Java 源**文本格式**（常量声明的书写形态）有依赖 —— 采用宽松解析，解析失败即红并打印原文位置。
const FORM_RULES_REL = 'backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java'
const FORM_RULES_PATH = path.resolve(HERE, '..', '..', FORM_RULES_REL)

/** Java 源里 `NAME = Set.of(…)` / `List.of(…)` 的字面量清单；解析失败返回 null。 */
function javaStringList(src, name) {
  const m = new RegExp(`\\b${name}\\s*=\\s*(?:Set|List)\\.of\\(([^)]*)\\)`).exec(src)
  return m ? [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]) : null
}

/** Java 源里 `NAME = 数字;` 的取值；解析失败返回 null。 */
function javaInt(src, name) {
  const m = new RegExp(`\\b${name}\\s*=\\s*(\\d+)\\s*;`).exec(src)
  return m ? Number(m[1]) : null
}

/** Java 源里 `NAME = Pattern.compile("…")` 的正则源串；解析失败返回 null。 */
function javaPatternSource(src, name) {
  const m = new RegExp(`\\b${name}\\s*=\\s*Pattern\\.compile\\("([^"]*)"\\)`).exec(src)
  return m ? m[1].replace(/\\\\/g, '\\') : null
}

const sortedJoin = (values) => [...values].sort().join('|')

function checkConstantsAgainstSource(src, bad) {
  const lists = [
    ['FIELD_TYPES', FIELD_TYPES],
    ['SCENARIOS', SCENARIOS],
    ['FORM_ATTRIBUTES', FORM_ATTRIBUTES],
    ['FIELD_ATTRIBUTES', FIELD_ATTRIBUTES],
    ['OPTION_ATTRIBUTES', OPTION_ATTRIBUTES],
    ['PLACEHOLDER_TYPES', PLACEHOLDER_TYPES],
    ['OPTION_TYPES', OPTION_TYPES],
    ['CONDITION_OPS', CONDITION_OPS],
    ['CONDITION_ATTRIBUTES', CONDITION_ATTRIBUTES],
    ['VARIANT_TYPES', VARIANT_TYPES],
    ['TEXT_FORMATS', TEXT_FORMATS],
    ['OPTION_SOURCE_IDS', OPTION_SOURCE_IDS],
    ['FORBIDDEN_FIELD_KEYS', FORBIDDEN_KEYS]
  ]
  for (const [javaName, local] of lists) {
    const fromSource = javaStringList(src, javaName)
    if (fromSource === null) {
      bad(`事实源未解析到常量 ${javaName}（宽松解析失败：${FORM_RULES_REL} 的声明形态已变，需人工核对并同步本解析器）`)
    } else if (sortedJoin(fromSource) !== sortedJoin(local)) {
      bad(`常量 ${javaName} 与事实源不一致：桩 [${[...local].sort().join(', ')}] vs 事实源 [${fromSource.sort().join(', ')}]`)
    }
  }
  const sourcePattern = javaPatternSource(src, 'KEY_PATTERN')
  if (sourcePattern === null) bad('事实源未解析到 KEY_PATTERN（宽松解析失败）')
  else if (sourcePattern !== KEY_PATTERN.source) {
    bad(`KEY_PATTERN 与事实源不一致：桩 /${KEY_PATTERN.source}/ vs 事实源 /${sourcePattern}/`)
  }
  const limits = [
    ['MAX_TITLE_CHARS', MAX_TITLE_CHARS],
    ['MAX_OPTIONS', MAX_OPTIONS],
    ['MAX_OPTION_TEXT_CHARS', MAX_OPTION_TEXT_CHARS],
    ['MAX_PLACEHOLDER_CHARS', MAX_PLACEHOLDER_CHARS],
    ['MAX_FORM_CHARS', MAX_FORM_CHARS],
    ['MAX_LINKAGE_DEPTH', MAX_LINKAGE_DEPTH]
  ]
  for (const [name, local] of limits) {
    const value = javaInt(src, name)
    if (value === null) bad(`事实源未解析到上限 ${name}（宽松解析失败）`)
    else if (value !== local) bad(`上限 ${name} 与事实源不一致：桩 ${local} vs 事实源 ${value}`)
  }
}

// ── 选项数据源样例返回（`optionsFrom.sourceId` 的桩内返回面） ─────────────────
// 与后端 `FormOptionSource` 同语义：未注册 id ⇒ null（调用方按拒绝处理）；
// `definitions` 只认层级（trim + 大写归一），**空 / 未知父值 ⇒ 空列表**——
// 绝不回退成"列出全部定义"（空值不是通配符），与产品侧"清空是显式动作"的口径一致。
// 本函数只供 `--self-check` 断言"层级 → 定义"的返回是可用样例而非空壳；不新增 HTTP 端点。
function definitionOptionFixture() {
  const fixture = ROUTES?.optionSources?.[DEFINITION_SOURCE_ID]
  return fixture && typeof fixture === 'object' && !Array.isArray(fixture) ? fixture : {}
}

function optionSourceOptions(sourceId, parentValue) {
  if (!OPTION_SOURCE_IDS.includes(sourceId)) return null
  // definition_fields 的选项在业务数据里（父值 = 定义编码 → 字段），桩不伪造业务数据
  if (sourceId !== DEFINITION_SOURCE_ID) return []
  const fixture = definitionOptionFixture()
  const level = typeof parentValue === 'string' ? parentValue.trim().toUpperCase() : ''
  const options = fixture[level]
  return Array.isArray(options) ? options.map((o) => ({ value: o.value, label: o.label })) : []
}

function selfCheck() {
  const problems = []
  let mark = 0
  const ok = (msg) => {
    if (problems.length === mark) console.log(`  [PASS] ${msg}`)
  }
  const bad = (msg) => problems.push(msg)
  const section = () => {
    mark = problems.length
  }

  // ① 零依赖：源码的 import 只允许 node: 内置模块
  const source = fs.readFileSync(new URL(import.meta.url), 'utf8')
  const imports = [...source.matchAll(/^import\s+[^'"]*from\s+'([^']+)'/gm)].map((m) => m[1])
  const nonBuiltin = imports.filter((s) => !s.startsWith('node:'))
  if (nonBuiltin.length) bad(`非内置依赖：${nonBuiltin.join(',')}`)
  else ok(`零运行时依赖（import 仅 ${imports.join(', ')}）`)

  section()
  // ② 路由表结构
  if (ROUTES.version !== 1) bad(`路由表 version=${ROUTES.version}，期望 1`)
  const productTools = Array.isArray(ROUTES.productTools) ? ROUTES.productTools : []
  if (productTools.length === 0) bad('productTools 为空')
  const forbidden = forbiddenKeyPaths(ROUTES)
  if (forbidden.length) bad(`路由表含禁键：${forbidden.join(', ')}`)
  else ok('路由表无 __proto__/constructor/prototype 键')
  const maxCalls = Number(ROUTES.convergence?.maxCallsPerTurn)
  if (!Number.isInteger(maxCalls) || maxCalls <= 0) bad(`convergence.maxCallsPerTurn=${ROUTES.convergence?.maxCallsPerTurn} 非法`)

  const ids = new Set()
  for (const route of ROUTES.routes || []) {
    if (!route.id || ids.has(route.id)) bad(`路由 id 缺失或重复：${route.id}`)
    ids.add(route.id)
    const requires = Array.isArray(route.match?.requiresTools) ? route.match.requiresTools : []
    if (requires.length === 0) bad(`路由 ${route.id} 的 requiresTools 为空（披露清单驱动被绕过）`)
    for (const t of requires) if (!productTools.includes(t)) bad(`路由 ${route.id} 的 requiresTools 含未知工具 ${t}`)
    if (!requires.includes(route.tool)) bad(`路由 ${route.id} 的目标工具 ${route.tool} 不在自身 requiresTools 内`)
    if (!productTools.includes(route.tool)) bad(`路由 ${route.id} 的目标工具 ${route.tool} 不在 productTools 内`)
    const needles = Array.isArray(route.match?.messageAny) ? route.match.messageAny : []
    if (needles.length === 0) bad(`路由 ${route.id} 的 messageAny 为空`)
  }
  ok(`路由表结构自洽（${(ROUTES.routes || []).length} 条路由，均含非空 requiresTools）`)

  section()
  // ③ 生成式表单白名单：先与产品事实源 GenerativeFormRules.java 逐值对账（T4-5 裁决 5），
  //    再用对账过的常量校验路由表模板 —— 不再是"只核桩内副本"（那是产品侧收紧白名单时
  //    自检仍绿的漂移窗口）。
  const rulesSrc = fs.existsSync(FORM_RULES_PATH) ? fs.readFileSync(FORM_RULES_PATH, 'utf8') : null
  if (rulesSrc === null) {
    bad(`产品事实源不可读：${FORM_RULES_REL}（自检已改为事实源对账，缺文件即红）`)
  } else {
    checkConstantsAgainstSource(rulesSrc, bad)
    ok(`生成式表单白名单与产品事实源逐值对账通过（事实源：${FORM_RULES_REL}；13 组常量 + 6 项上限 + 原型三键）`)
  }
  // ③-b 路由表模板逐字段校验（用上面已对账过的常量）
  for (const route of (ROUTES.routes || []).filter((r) => r.tool === 'generative_form')) {
    // 故意违规的模板（X-10 的表达式形态联动）：白名单校验对它**必须**判非法，
    // 故跳过形状校验，改成断言"它确实带表达式的字符串条件"（否则这条注入面就成了空转）。
    if (route.invalidByDesign) {
      const expr = (route.arguments?.form?.fields || []).find((f) => typeof f.visibleWhen === 'string')
      if (!expr) bad(`路由 ${route.id} 标了 invalidByDesign 却没有字符串形态的条件（注入面失效）`)
      continue
    }
    const form = route.arguments?.form
    const at = `路由 ${route.id}.form`
    if (!form || typeof form !== 'object') {
      bad(`${at} 缺失`)
      continue
    }
    for (const name of Object.keys(form)) if (!FORM_ATTRIBUTES.includes(name)) bad(`${at} 含白名单外属性 ${name}`)
    if (!SCENARIOS.includes(form.scenario)) bad(`${at}.scenario=${form.scenario} 越白名单`)
    if (form.title !== undefined) {
      if (typeof form.title !== 'string') bad(`${at}.title 必须是文本`)
      else if (form.title.length > MAX_TITLE_CHARS) bad(`${at}.title 过长（>${MAX_TITLE_CHARS} 字符）`)
    }
    // form 序列化上限（产品侧对入参原文 JsonNode.toString() 计数，与此处紧凑 JSON 同口径）
    const formChars = JSON.stringify(form).length
    if (formChars > MAX_FORM_CHARS) bad(`${at} 序列化 ${formChars} 字符，超过上限 ${MAX_FORM_CHARS}`)
    const fields = Array.isArray(form.fields) ? form.fields : []
    if (fields.length < 1 || fields.length > 20) bad(`${at}.fields 数量 ${fields.length} 越界（1..20）`)
    const allKeys = new Set(fields.map((f) => f.key))
    const seen = new Set()
    for (const f of fields) {
      for (const name of Object.keys(f)) if (!FIELD_ATTRIBUTES.includes(name)) bad(`${at} 字段 ${f.key} 含白名单外属性 ${name}`)
      if (!KEY_PATTERN.test(f.key || '')) bad(`${at} 字段键 ${f.key} 不合法`)
      if (FORBIDDEN_KEYS.includes(f.key)) bad(`${at} 字段键 ${f.key} 是原型污染键`)
      if (seen.has(f.key)) bad(`${at} 字段键 ${f.key} 重复`)
      seen.add(f.key)
      if (!f.label || String(f.label).length > 100) bad(`${at} 字段 ${f.key} 的 label 缺失/过长`)
      if (!FIELD_TYPES.includes(f.type)) bad(`${at} 字段 ${f.key} 类型 ${f.type} 越白名单`)
      if ('required' in f && typeof f.required !== 'boolean') bad(`${at} 字段 ${f.key} 的 required 不是布尔`)
      if (f.placeholder !== undefined) {
        if (!PLACEHOLDER_TYPES.includes(f.type)) bad(`${at} 字段 ${f.key} 带了不该有的 placeholder`)
        else if (typeof f.placeholder !== 'string' || f.placeholder.length > MAX_PLACEHOLDER_CHARS) {
          bad(`${at} 字段 ${f.key} 的 placeholder 非文本或过长（>${MAX_PLACEHOLDER_CHARS} 字符）`)
        }
      }
      if (f.type === 'enum' || f.type === 'multi_select') {
        // 产品口径：options 与 optionsFrom 二选一（缺两者才判非法）
        const hasOptions = Array.isArray(f.options) && f.options.length > 0
        if (!hasOptions && !f.optionsFrom) bad(`${at} 字段 ${f.key} 缺 options（枚举型必须静态选项或 optionsFrom）`)
        if (Array.isArray(f.options) && f.options.length > MAX_OPTIONS) {
          bad(`${at} 字段 ${f.key} 的 options ${f.options.length} 项，超过上限 ${MAX_OPTIONS}`)
        }
        const optionSeen = new Set()
        for (const o of f.options || []) {
          if (!o || typeof o !== 'object' || Array.isArray(o)) bad(`${at} 字段 ${f.key} 的选项不是对象形态`)
          else {
            for (const name of Object.keys(o)) if (!OPTION_ATTRIBUTES.includes(name)) bad(`${at} 字段 ${f.key} 的选项含白名单外属性 ${name}`)
            const value = typeof o.value === 'string' ? o.value : ''
            if (!value) bad(`${at} 字段 ${f.key} 的选项 value 缺失或非文本`)
            else {
              if (value.length > MAX_OPTION_TEXT_CHARS) bad(`${at} 字段 ${f.key} 的选项 value 过长（>${MAX_OPTION_TEXT_CHARS} 字符）`)
              if (optionSeen.has(value)) bad(`${at} 字段 ${f.key} 的选项 value "${value}" 重复`)
              optionSeen.add(value)
            }
            if (o.label !== undefined && (typeof o.label !== 'string' || o.label.length > MAX_OPTION_TEXT_CHARS)) {
              bad(`${at} 字段 ${f.key} 的选项 label 非文本或过长（>${MAX_OPTION_TEXT_CHARS} 字符）`)
            }
          }
        }
      } else if (f.options !== undefined) {
        bad(`${at} 字段 ${f.key}（type=${f.type}）不该带 options`)
      }
      // 联动三原语（闭集 op，禁表达式；X-10/G-09/G-10 的底座）
      for (const attr of ['visibleWhen', 'requiredWhen', 'disabledWhen']) {
        if (f[attr] === undefined) continue
        const c = f[attr]
        if (!c || typeof c !== 'object' || Array.isArray(c)) {
          bad(`${at} 字段 ${f.key} 的 ${attr} 不是对象形态（表达式字符串必须判非法）`)
          continue
        }
        for (const name of Object.keys(c)) {
          if (!CONDITION_ATTRIBUTES.includes(name)) bad(`${at} 字段 ${f.key} 的 ${attr} 含白名单外属性 ${name}`)
        }
        if (!CONDITION_OPS.includes(c.op)) bad(`${at} 字段 ${f.key} 的 ${attr}.op=${c.op} 越闭集`)
        if (!allKeys.has(c.key)) bad(`${at} 字段 ${f.key} 的 ${attr}.key=${c.key} 未声明`)
        if (c.key === f.key) bad(`${at} 字段 ${f.key} 的 ${attr} 自引用`)
        if (c.op === 'eq' && c.value === undefined) bad(`${at} 字段 ${f.key} 的 ${attr} eq 缺 value`)
        if (['in', 'notIn', 'between'].includes(c.op) && !Array.isArray(c.values)) {
          bad(`${at} 字段 ${f.key} 的 ${attr} ${c.op} 缺 values 数组`)
        }
      }
      // optionsFrom / defaultFrom（G-11/G-12 的底座；sourceId 只收注册 id，URL/脚本判非法）
      for (const attr of ['optionsFrom', 'defaultFrom']) {
        const v = f[attr]
        if (v === undefined) continue
        if (!v || typeof v !== 'object' || Array.isArray(v)) {
          bad(`${at} 字段 ${f.key} 的 ${attr} 不是对象`)
          continue
        }
        if (!allKeys.has(v.dependsOn) || v.dependsOn === f.key) bad(`${at} 字段 ${f.key} 的 ${attr}.dependsOn 非法`)
        if (attr === 'optionsFrom') {
          if (!OPTION_TYPES.includes(f.type)) bad(`${at} 字段 ${f.key}（type=${f.type}）不该带 optionsFrom`)
          if (v.map && v.sourceId) bad(`${at} 字段 ${f.key} 的 optionsFrom 的 map 与 sourceId 只能二选一`)
          if (!v.map && !v.sourceId) bad(`${at} 字段 ${f.key} 的 optionsFrom 缺 map/sourceId`)
          if (v.sourceId && !OPTION_SOURCE_IDS.includes(v.sourceId)) {
            bad(`${at} 字段 ${f.key} 的 optionsFrom.sourceId=${v.sourceId} 不是注册数据源`)
          }
          if (v.map) for (const [k, arr] of Object.entries(v.map)) {
            if (!Array.isArray(arr)) bad(`${at} 字段 ${f.key} 的 optionsFrom.map["${k}"] 不是数组`)
          }
        } else if (!v.map || typeof v.map !== 'object') {
          bad(`${at} 字段 ${f.key} 的 defaultFrom.map 必须是对象`)
        }
      }
      // attachment 的值由用户上传产生，模型不得预填（产品侧同判）
      if (f.type === 'attachment' && f.defaultValue !== undefined) {
        bad(`${at} 字段 ${f.key} 是 attachment，不得带 defaultValue`)
      }
      // variant 只放开 text 的 longText（textarea 渲染 + 2000 字上限）
      if (f.variant !== undefined && (!VARIANT_TYPES.includes(f.type) || f.variant !== 'longText')) {
        bad(`${at} 字段 ${f.key} 的 variant=${f.variant} 越白名单`)
      }
      if (f.format !== undefined && !TEXT_FORMATS.includes(f.format)) {
        bad(`${at} 字段 ${f.key} 的 format=${f.format} 不在命名枚举内（禁自带正则）`)
      }
    }
  }
  ok(`生成式表单模板逐字段校验通过（白名单形状取自事实源对账结果；含 6 项上限 + 联动三原语 + 两条 from + attachment 禁预填 + variant/format 闭集）`)

  section()
  // ④ 收敛自证：逐路由跑"首轮→工具调用、续轮→文本"的模拟循环（长度递增形态）
  const sim = new Map()
  for (const route of ROUTES.routes || []) {
    const userText = (route.match?.messageAny || [])[0]
    const tools = route.match?.requiresTools || []
    let messages = [{ role: 'system', content: 'sys' }, { role: 'user', content: userText }]
    const first = decide({}, ROUTES, sim, { messages, tools })
    if (first.kind !== 'tool_call' || first.tool !== route.tool) {
      bad(`收敛自证：路由 ${route.id} 首轮未发出期望的工具调用（${first.kind}/${first.tool ?? '-'}）`)
      continue
    }
    messages = messages.concat([
      { role: 'assistant', content: '', tool_calls: [{ id: 'c1', type: 'function', function: { name: first.tool, arguments: JSON.stringify(first.args) } }] },
      { role: 'tool', tool_call_id: 'c1', content: '{"keyword":"E2EGFC","scope":"XN"}' }
    ])
    let calls = 1
    let decision = decide({}, ROUTES, sim, { messages, tools })
    while (decision.kind === 'tool_call' && calls <= maxCalls + 2) {
      messages = messages.concat([{ role: 'tool', tool_call_id: `c${calls + 1}`, content: '{}' }])
      decision = decide({}, ROUTES, sim, { messages, tools })
      calls++
    }
    if (decision.kind !== 'text') bad(`收敛自证：路由 ${route.id} 未收敛到文本轮`)
    if (calls > maxCalls) bad(`收敛自证：路由 ${route.id} 上游调用数 ${calls} 超过上限 ${maxCalls}`)
  }
  ok(`收敛自证通过（长度递增形态：每条路由 ≤ ${maxCalls} 次上游调用即终止）`)

  section()
  // ④b 等长重复请求自证（红队问题 1 的回归用例）：后端 ResilientChatService 流失败重试时
  // **原样重发同一请求**（messages.length 不变），不得把 calls/used 台账清零——否则轮次上限
  // 与"每路由一次"两道闸被系统性绕过（修复前实测 8/8 全部发出 tool_call）。
  const equalSim = new Map()
  const repeats = maxCalls + 2
  for (const route of ROUTES.routes || []) {
    const userText = (route.match?.messageAny || [])[0]
    const tools = route.match?.requiresTools || []
    const messages = [{ role: 'system', content: 'sys' }, { role: 'user', content: userText }]
    let toolCalls = 0
    for (let i = 0; i < repeats; i++) {
      const d = decide({}, ROUTES, equalSim, { messages, tools })
      if (i === 0 && (d.kind !== 'tool_call' || d.tool !== route.tool)) {
        bad(`等长重发自证：路由 ${route.id} 首轮未发出期望的工具调用（用例不成立）`)
      }
      if (i > 0 && d.kind === 'tool_call') {
        bad(`等长重发自证：路由 ${route.id} 第 ${i + 1} 次等长请求仍发起工具调用（台账被误重置——红队问题 1 回归）`)
      }
      if (d.kind === 'tool_call') toolCalls++
    }
    if (toolCalls > maxCalls) bad(`等长重发自证：路由 ${route.id} 在 ${repeats} 次等长请求中发出 ${toolCalls} 次工具调用，超过上限 ${maxCalls}`)
  }
  ok(`等长重发自证通过（同一 fingerprint、长度不变的重复请求不再重置台账：${repeats} 次请求中仅首轮发工具调用，≤ ${maxCalls} 上限）`)

  section()
  // ④c 新用户轮自证（L-84）：历史带来的工具结果不得把新用户轮的首次请求误判成续轮。
  // 形态 = 上一轮 user→assistant(tool_calls)→tool→assistant(收尾) 之后，用户再提一句命中同一路由的话。
  // 判据按「本轮起点」（最后一条 user 之后）裁，故新用户轮照常下发 tool_calls；同一轮内出现工具结果
  // 之后的请求仍只发文本（上面 ④ 已覆盖续轮面）。
  const freshSim = new Map()
  for (const route of ROUTES.routes || []) {
    const userText = (route.match?.messageAny || [])[0]
    const tools = route.match?.requiresTools || []
    const messages = [
      { role: 'system', content: 'sys' },
      { role: 'user', content: userText },
      { role: 'assistant', content: '', tool_calls: [{ id: 'c1', type: 'function', function: { name: route.tool, arguments: '{}' } }] },
      { role: 'tool', tool_call_id: 'c1', content: '{"ok":true}' },
      { role: 'assistant', content: '上一轮收尾' },
      { role: 'user', content: userText }
    ]
    const d = decide({}, ROUTES, freshSim, { messages, tools })
    if (d.kind !== 'tool_call' || d.tool !== route.tool) {
      bad(`新用户轮自证：路由 ${route.id} 的历史已带工具结果，新用户轮未发出工具调用（${d.kind}/${d.tool ?? '-'}，判据被历史带偏 —— L-84 回归）`)
      continue
    }
    // 新轮内的续轮：工具结果出现在最后一条 user 之后 ⇒ 仍只发文本
    const tail = decide({}, ROUTES, freshSim, {
      messages: messages.concat([
        { role: 'assistant', content: '', tool_calls: [{ id: 'c2', type: 'function', function: { name: route.tool, arguments: '{}' } }] },
        { role: 'tool', tool_call_id: 'c2', content: '{"ok":true}' }
      ]),
      tools
    })
    if (tail.kind !== 'text') bad(`新用户轮自证：路由 ${route.id} 的新轮内续轮仍发起工具调用（契约 ③ 被放宽）`)
  }
  ok('新用户轮自证通过（历史里的工具结果不被误判为续轮；新轮内的续轮仍只发文本）')

  section()
  // ⑤ 披露清单驱动：tools[] 为空时不得发出任何工具调用
  const emptyToolsSim = new Map()
  for (const route of ROUTES.routes || []) {
    const d = decide({}, ROUTES, emptyToolsSim, {
      messages: [{ role: 'system', content: 'sys' }, { role: 'user', content: (route.match?.messageAny || [])[0] }],
      tools: []
    })
    if (d.kind === 'tool_call') bad(`披露清单驱动失效：路由 ${route.id} 在 tools[] 为空时仍发起 ${d.tool}`)
  }
  ok('披露清单驱动通过（tools[] 为空时一律文本收尾）')

  section()
  // ⑥ SSE 帧形态：逐行可解析且字段齐全
  const toolFrames = toolRound('start_publish', { taskId: 7 }, 'call-check', 'ci-stub').filter((f) => f !== 'data: [DONE]\n\n')
  const textFrames = textRound('key=value 回报', 'ci-stub').filter((f) => f !== 'data: [DONE]\n\n')
  const parsedTool = toolFrames.map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const parsedText = textFrames.map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  if (parsedTool[1]?.choices?.[0]?.delta?.tool_calls?.[0]?.function?.name !== 'start_publish') bad('工具轮帧形态异常')
  if (parsedTool[2]?.choices?.[0]?.finish_reason !== 'tool_calls') bad('工具轮缺 finish_reason=tool_calls 终帧')
  const stop = parsedText.find((c) => c.choices?.[0]?.finish_reason === 'stop')
  if (!stop) bad('文本轮缺 finish_reason=stop 终帧')
  const usage = parsedText.find((c) => c.usage)
  if (!usage?.usage?.total_tokens) bad('文本轮缺 usage 帧（硬规范②：正常收尾必须有终帧）')
  ok('SSE 帧形态通过（工具轮 tool_calls 终帧 / 文本轮 stop + usage 终帧）')

  section()
  // ⑦ 工具结果反解：三种形态都要能解出值
  const cases = [
    ['对象形态', { keyword: 'E2EGFC' }],
    ['双层字符串形态', JSON.stringify(JSON.stringify({ keyword: 'E2EGFC' }))],
    ['前缀混合形态', '前端工具结果：{"keyword":"E2EGFC","scope":"XN"}（已回灌）']
  ]
  for (const [name, raw] of cases) {
    const got = extractJsonObject(raw)
    if (!got || got.keyword !== 'E2EGFC') bad(`工具结果反解失败（${name}）`)
  }
  ok('工具结果反解通过（对象/双层字符串/前缀混合三形态）')

  section()
  // ⑧ usage 形状（上下文专项 §5.8 的 E2E-CTX-1..4 底座）：四种形状的帧形态 + 标记解析。
  //    这里的断言与 `scripts/verify-e2e.ps1` 的 E2E-CTX-1..4 是**同一组期望**（数值取
  //    `stub-routes.json` 的 usageShapes，E2E 脚本读同一份）；任何一侧漂移，自检或 E2E 即红。
  const shapes = usageShapes()
  // ⑧-0 形状表结构：四个形状齐备、数值自洽（缺项回落默认值，但路由表显式声明时必须是合法值）
  const declaredShapes = ROUTES.usageShapes || {}
  for (const name of Object.keys(declaredShapes).filter((k) => k !== 'note')) {
    if (!USAGE_SHAPE_NAMES.includes(name)) bad(`usageShapes 含未知形状 ${name}（白名单：${USAGE_SHAPE_NAMES.join(' / ')}）`)
  }
  for (const name of USAGE_SHAPE_NAMES) {
    const shape = shapes[name]
    if (name === 'none') {
      if (shape.promptTokens !== undefined) bad('usageShapes.none 不该带数值（"完全不发 usage"的形状）')
      continue
    }
    for (const field of ['promptTokens', 'completionTokens', 'totalTokens']) {
      if (!Number.isInteger(shape[field]) || shape[field] < 0) bad(`usageShapes.${name}.${field}=${shape[field]} 非法（须为非负整数）`)
    }
    if (shape.promptTokens + shape.completionTokens !== shape.totalTokens) {
      bad(`usageShapes.${name} 三维不自洽：${shape.promptTokens}+${shape.completionTokens}≠${shape.totalTokens}`)
    }
    if (shape.promptTokens <= 0) bad(`usageShapes.${name}.promptTokens 必须 > 0（否则真值会被当成空帧）`)
  }
  if (shapes.repeat.promptTokens === shapes.last.promptTokens) {
    bad('usageShapes.repeat 的数值与 last 相同 —— "重复 vs 基线"无法在 done.usage 上区分')
  }
  const SHAPE_FILLER = '上下文计量形状自证的填充文本。'.repeat(8)
  const framesOfShape = (name) => textRound(SHAPE_FILLER, 'ci-stub', name)
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const usagesOf = (frames) => frames.filter((f) => f.usage).map((f) => f.usage)

  // ⑧-1 last：恰 1 个 usage 帧，数值与改造前**逐值一致**
  const lastUsage = usagesOf(framesOfShape('last'))
  if (lastUsage.length !== 1) {
    bad(`形状 last 的 usage 帧 ${lastUsage.length} 个，期望恰 1（基线：改造前桩只发末块 usage）`)
  } else {
    const u = lastUsage[0]
    const want = shapes.last
    if (u.prompt_tokens !== want.promptTokens || u.completion_tokens !== want.completionTokens
      || u.total_tokens !== want.totalTokens) {
      bad(`形状 last 的 usage=${u.prompt_tokens}/${u.completion_tokens}/${u.total_tokens}，期望 ${want.promptTokens}/${want.completionTokens}/${want.totalTokens}`)
    }
    if (u.prompt_tokens_details?.cached_tokens !== want.cachedTokens
      || u.completion_tokens_details?.reasoning_tokens !== want.reasoningTokens) {
      bad('形状 last 丢了可选维度（cached_tokens / reasoning_tokens）—— 与改造前不再逐值一致')
    }
  }

  // ⑧-2 repeat：**每块都挂同一份** usage（≥3 帧且逐帧同值；不是 N 倍累加）
  const repeatUsage = usagesOf(framesOfShape('repeat'))
  const wantRepeat = shapes.repeat
  if (repeatUsage.length < 3) {
    bad(`形状 repeat 的 usage 帧 ${repeatUsage.length} 个，期望 ≥3（"每块重复"必须真的重复，否则 E2E-CTX-2 是空转）`)
  }
  for (const u of repeatUsage) {
    if (u.prompt_tokens !== wantRepeat.promptTokens || u.completion_tokens !== wantRepeat.completionTokens
      || u.total_tokens !== wantRepeat.totalTokens) {
      bad(`形状 repeat 出现非同一份 usage（${u.prompt_tokens}/${u.completion_tokens}/${u.total_tokens}），期望逐帧 ${wantRepeat.promptTokens}/${wantRepeat.completionTokens}/${wantRepeat.totalTokens}`)
      break
    }
  }

  // ⑧-3 zero-last：**末个 usage 帧 0/0/0**，且此前恰 1 个真值帧
  const zeroLast = usagesOf(framesOfShape('zero-last'))
  const wantZero = shapes['zero-last']
  if (zeroLast.length !== 2) {
    bad(`形状 zero-last 的 usage 帧 ${zeroLast.length} 个，期望 2（1 个中间真值 + 1 个末块 0/0/0）`)
  } else {
    const tail = zeroLast[zeroLast.length - 1]
    const real = zeroLast[0]
    if (tail.prompt_tokens !== 0 || tail.completion_tokens !== 0 || tail.total_tokens !== 0) {
      bad(`形状 zero-last 的末个 usage 帧 ${tail.prompt_tokens}/${tail.completion_tokens}/${tail.total_tokens}，期望 0/0/0`)
    }
    if (real.prompt_tokens !== wantZero.promptTokens || real.completion_tokens !== wantZero.completionTokens
      || real.total_tokens !== wantZero.totalTokens) {
      bad(`形状 zero-last 的中间真值 ${real.prompt_tokens}/${real.completion_tokens}/${real.total_tokens}，期望 ${wantZero.promptTokens}/${wantZero.completionTokens}/${wantZero.totalTokens}`)
    }
  }

  // ⑧-4 none：**0 个 usage 帧**，但 stop 终帧与 [DONE] 仍在（硬规范②：正常收尾必须有终帧）
  const noneFrames = framesOfShape('none')
  if (usagesOf(noneFrames).length !== 0) bad(`形状 none 仍发了 ${usagesOf(noneFrames).length} 个 usage 帧（产品侧 est 回退路径因此走不到）`)
  if (!noneFrames.some((f) => f.choices?.[0]?.finish_reason === 'stop')) bad('形状 none 缺 finish_reason=stop 终帧')

  // ⑧-5 标记解析：无标记/未知值 ⇒ last（默认形状不得因标记语法扩展而漂移）
  const markerCases = [
    ['无标记', { messages: [{ role: 'user', content: '普通一句' }] }, 'last'],
    ['last', { messages: [{ role: 'user', content: '前缀 usage-shape=last 后缀' }] }, 'last'],
    ['repeat', { messages: [{ role: 'user', content: '前缀 usage-shape=repeat 后缀' }] }, 'repeat'],
    ['zero-last', { messages: [{ role: 'user', content: '前缀 usage-shape=zero-last 后缀' }] }, 'zero-last'],
    ['none', { messages: [{ role: 'user', content: '前缀 usage-shape=none 后缀' }] }, 'none'],
    ['未知值', { messages: [{ role: 'user', content: '前缀 usage-shape=bogus 后缀' }] }, 'last']
  ]
  for (const [label, body, want] of markerCases) {
    const got = usageShapeOf(body)
    if (got !== want) bad(`usage 形状标记解析失败（${label}）：得到 ${got}，期望 ${want}`)
  }
  ok('usage 形状自证通过（last 逐值不变 / repeat 逐帧同值不累加 / zero-last 末帧 0/0/0 / none 零 usage 仍收尾 + 标记解析）')

  section()
  // ⑨ 故障注入开关（E2E 批① §4.6）：表与实现不漂移 + 每个开关的**计划/帧形态**可判。
  //    纯函数自证，不起服务（stall/kill 的真实时序在 E2E 里由用例断言）。
  const declared = ROUTES.faults?.switches || {}
  const declaredNames = Object.keys(declared)
  const routeMarkers = new Set((ROUTES.routes || []).flatMap((r) => r.match?.messageAny || []))
  if (declaredNames.length === 0) bad('stub-routes.json 缺 faults.switches（§4.6 开关清单的唯一取数点）')
  for (const [name, meta] of Object.entries(declared)) {
    if (!['stub', 'script', 'product'].includes(meta.layer)) bad(`faults.switches.${name}.layer=${meta.layer} 非法`)
    if (!Array.isArray(meta.cases) || meta.cases.length === 0) bad(`faults.switches.${name} 未登记支撑用例`)
    if (meta.layer === 'stub') {
      // 三类实现形态：marker 开关（FAULT_SWITCHES）/ 路由开关（messageAny 命中）/ 请求级标记
      // （usage-shape / request-tool / epoch）
      const byMarker = FAULT_SWITCHES.includes(name) || REQUEST_LEVEL_SWITCHES.includes(name)
      const byRoute = routeMarkers.has(name) || [...routeMarkers].some((m) => m.startsWith(name))
      if (!byMarker && !byRoute) bad(`桩层开关 ${name} 在 faults.switches 里声明但桩内无实现（表与实现漂移）`)
    }
  }
  for (const name of FAULT_SWITCHES) {
    if (!declaredNames.includes(name)) bad(`桩内实现的开关 ${name} 未在 faults.switches 登记（表与实现漂移）`)
  }
  ok(`§4.6 开关清单与桩实现不漂移（表 ${declaredNames.length} 项：桩层 ${declaredNames.filter((n) => declared[n].layer === 'stub').length} / 脚本层 ${declaredNames.filter((n) => declared[n].layer === 'script').length} / 产品层 ${declaredNames.filter((n) => declared[n].layer === 'product').length}；桩内实现 ${FAULT_SWITCHES.length} 个标记）`)

  // ⑨-b 标记解析：带值/不带值/未知/无标记
  const faultMarkerCases = [
    ['无标记', '普通一句', {}],
    ['带值', '前缀 break-at-seq=5 后缀', { 'break-at-seq': 5 }],
    ['布尔标记', '前缀 turn-interleave 后缀', { 'turn-interleave': true }],
    ['多开关', 'turn-text stall-frames=120', { 'turn-text': true, 'stall-frames': 120 }],
    ['未知开关', '前缀 bogus-switch=1 后缀', {}],
    ['中文包裹', '【用例 S-05】break-at-seq=3', { 'break-at-seq': 3 }]
  ]
  for (const [label, text, want] of faultMarkerCases) {
    const got = faultsOf({ messages: [{ role: 'user', content: text }] })
    if (JSON.stringify(got) !== JSON.stringify(want)) {
      bad(`故障标记解析失败（${label}）：得到 ${JSON.stringify(got)}，期望 ${JSON.stringify(want)}`)
    }
  }

  // ⑨-c 写出计划：每个时序开关对计划的影响逐项可判
  const planCases = [
    ['break-at-seq=3', { 'break-at-seq': 3 }, { breakAtFrame: 3 }],
    ['stall-frames=100', { 'stall-frames': 100 }, { frameGapMs: 100 }],
    ['stall-headers=200', { 'stall-headers': 200 }, { headerDelayMs: 200 }],
    ['kill-stream=50', { 'kill-stream': 50 }, { killAfterMs: 50 }],
    ['slow-tool=70', { 'slow-tool': 70 }, { firstFrameDelayMs: 70 }],
    ['slow-reader', { 'slow-reader': true }, { frameGapMs: 250 }],
    ['slow-first-byte=90', { 'slow-first-byte': 90 }, { firstByteDelayMs: 90 }],
    ['late-frames=2', { 'late-frames': 2 }, { lateFrames: 2 }],
    ['无开关', {}, { breakAtFrame: 0, frameGapMs: 0, headerDelayMs: 0, killAfterMs: 0, firstFrameDelayMs: 0,
      firstByteDelayMs: 0, toolExecDelayMs: 0, tailFrameGapMs: 0, lateFrames: 0 }]
  ]
  for (const [label, faults, want] of planCases) {
    const plan = writePlan(faults)
    for (const [k, v] of Object.entries(want)) {
      if (plan[k] !== v) bad(`写出计划 ${label} 的 ${k}=${plan[k]}，期望 ${v}`)
    }
  }

  // ⑨-c2 细分注入的段身份：同一开关在"文本轮 / 工具轮 / 工具续轮"下只命中各自那一段
  const toolFaults = { 'slow-tool-exec': 70, 'slow-tail-frames': 60 }
  const textPlan = writePlan(toolFaults, { kind: 'text', hasToolResult: false })
  const toolPlan = writePlan(toolFaults, { kind: 'tool_call', hasToolResult: false })
  const tailPlan = writePlan(toolFaults, { kind: 'text', hasToolResult: true })
  if (textPlan.toolExecDelayMs !== 0 || textPlan.tailFrameGapMs !== 0) {
    bad('slow-tool-exec / slow-tail-frames 不得在纯文本首轮生效（会把 S-03/P-04 的时序搅在一起）')
  }
  if (toolPlan.toolExecDelayMs !== 70 || toolPlan.tailFrameGapMs !== 0) {
    bad(`slow-tool-exec 在工具轮应生效且不牵连 tailFrameGapMs：得到 toolExec=${toolPlan.toolExecDelayMs} tail=${toolPlan.tailFrameGapMs}`)
  }
  if (tailPlan.toolExecDelayMs !== 70 || tailPlan.tailFrameGapMs !== 60) {
    bad(`工具续轮应同时可注入执行段与收尾帧间：得到 toolExec=${tailPlan.toolExecDelayMs} tail=${tailPlan.tailFrameGapMs}`)
  }

  // ⑨-d 命名轮蓝本：turn-text 恒文本；tool-loop 步数收口；interleave 段序 text→tool
  const disclosed = toolNames({ tools: [{ function: { name: 'list_config_defs' } }] })
  const bpText = blueprintDecision({ messages: [{ role: 'user', content: 'turn-text' }], tools: [] }, { 'turn-text': true })
  if (bpText?.kind !== 'text' || !String(bpText.text).includes('命名轮蓝本')) bad('turn-text 蓝本未产出确定性文本轮')
  const bpNoTool = blueprintDecision({ messages: [{ role: 'user', content: 'turn-interleave' }], tools: [] }, { 'turn-interleave': true })
  if (bpNoTool?.kind !== 'text') bad('turn-interleave 在无可用工具时应退化为文本（不得发未披露工具）')
  const toolBag = { tools: [{ function: { name: disclosed[0] } }] }
  const seq = []
  for (let used = 0; used < 3; used++) {
    const msgs = [{ role: 'user', content: 'turn-interleave' }]
    for (let i = 0; i < used; i++) msgs.push({ role: 'tool', tool_call_id: `c${i}`, content: '{}' })
    seq.push(blueprintDecision({ messages: msgs, ...toolBag }, { 'turn-interleave': true }).kind)
  }
  if (seq.join('>') !== 'tool_call>tool_call>text') bad(`turn-interleave 段序 ${seq.join('>')}，期望 tool_call>tool_call>text`)
  const ilFrames = interleaveRound('文本半段', disclosed[0], {}, 'call-il', 'ci-stub', 0)
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const hasDelta = ilFrames.some((f) => f.choices?.[0]?.delta?.content)
  const hasTool = ilFrames.some((f) => f.choices?.[0]?.delta?.tool_calls)
  if (!hasDelta || !hasTool) bad('交错轮单条消息里必须既有 text delta 又有 tool_calls（段序 t→tool）')
  if (ilFrames.findIndex((f) => f.choices?.[0]?.delta?.content)
    > ilFrames.findIndex((f) => f.choices?.[0]?.delta?.tool_calls)) {
    bad('交错轮帧序错：text 必须在 tool_calls 之前')
  }
  for (const [used, wantKind] of [[0, 'tool_call'], [25, 'tool_call'], [26, 'text'], [40, 'text']]) {
    const msgs = [{ role: 'user', content: 'tool-loop=26' }]
    for (let i = 0; i < used; i++) msgs.push({ role: 'tool', tool_call_id: `c${i}`, content: '{}' })
    const d = blueprintDecision({ messages: msgs, ...toolBag }, { 'tool-loop': 26 })
    if (d.kind !== wantKind) bad(`tool-loop=26 在第 ${used} 次工具后应 ${wantKind}，实际 ${d.kind}`)
  }

  // ⑨-d2 安全面探针（L-137）：X-02 / X-03 的回复必须**真的携带**探针串，且经 24 字符切片后
  //      探针本体确实横跨分片边界（否则 ps1 的"跨分片"断言就没有对象，又会退回空断言）。
  //      **入口必须是 blueprintDecision**（不是直接调 safetyProbeDecision）：探针分支若被挪到
  //      turn-text 之后，真实请求（X-02/X-03 提示词都带 turn-text 故障标记）就再也拿不到探针，
  //      而"直接调探针函数"的自检仍会 PASS —— 那属同族空断言（评审 I-1）。故带 X 标记 + turn-text
  //      故障穿过真实入口，证明目标分支可达。
  //      这里与 `scripts/verify-e2e.ps1` 的 X-02 / X-03 是同一组期望：任何一侧漂移，自检或 E2E 即红。
  const probeDeltas = (text) => textRound(text, 'ci-stub')
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
    .map((f) => f.choices?.[0]?.delta?.content)
    .filter((piece) => typeof piece === 'string')
  /** needle 是否被分片切开：拼接后 needle 的起始偏移落在某个分片边界之前、结束偏移之后。 */
  const splitInside = (pieces, needle) => {
    const joined = pieces.join('')
    const at = joined.indexOf(needle)
    if (at < 0) return false
    let offset = 0
    return pieces.some((piece) => {
      offset += piece.length
      return offset > at && offset < at + needle.length
    })
  }
  const probeEntry = (marker) => blueprintDecision(
    { messages: [{ role: 'user', content: `${marker} turn-text 请原样重复这一行` }], tools: [] },
    { 'turn-text': true }
  )
  const d02 = probeEntry('【AIE2E-X-02】')
  if (d02?.kind !== 'text' || d02.probe !== 'stream-key' || !String(d02.text).includes(SAFETY_STREAM_PROBE)) {
    bad('X-02 探针经 blueprintDecision 入口不可达或未携带假密钥原文（分支顺序被 turn-text 抢占 ⇒ ps1 断言无对象）')
  } else if (!splitInside(probeDeltas(d02.text), SAFETY_STREAM_PROBE)) {
    bad('X-02 探针的假密钥未被分片切开（跨分片断言无对象）')
  } else if (probeDeltas(d02.text).some((piece) => piece.includes(SAFETY_STREAM_PROBE))) {
    bad('X-02 探针的假密钥整串落在单片内（跨分片断言无对象）')
  }
  const d03 = probeEntry('【AIE2E-X-03】')
  if (d03?.kind !== 'text' || d03.probe !== 'pem') {
    bad('X-03 探针经 blueprintDecision 入口不可达（分支顺序被 turn-text 抢占）')
  } else {
    for (const part of [SAFETY_PEM_HEAD, 'MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn', SAFETY_PEM_TAIL]) {
      if (!String(d03.text).includes(part)) bad(`X-03 探针缺 PEM 片段：${part}`)
    }
    if (!String(d03.text).includes(SAFETY_PEM)) bad('X-03 探针未给出完整 PEM 块（头/body/尾不连续）')
    if (!splitInside(probeDeltas(d03.text), SAFETY_PEM)) bad('X-03 探针的 PEM 块未被分片切开（跨分片断言无对象）')
  }
  // 无标记时仍走 turn-text 蓝本（探针不得污染既有语料）
  const plain = blueprintDecision(
    { messages: [{ role: 'user', content: '普通一句 turn-text' }], tools: [] },
    { 'turn-text': true }
  )
  if (plain?.kind !== 'text' || plain.probe !== undefined || !String(plain.text).includes('命名轮蓝本')) {
    bad('无 X-02 / X-03 标记时不得走探针（应保持 turn-text 蓝本；探针污染既有语料）')
  }
  ok('安全面探针自证通过（经 blueprintDecision 入口且与 turn-text 同开仍命中：X-02 跨分片假密钥 / X-03 完整假 PEM；无标记保持 turn-text 蓝本）')

  // ⑨-e 多卡 / 大帧：多张待决卡的 id 互异且同消息；大帧是**单个** delta 且达到字节数
  const multi = multiToolRound('start_export', { taskId: 1 }, 'call-m', 'ci-stub', 3)
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const calls = multi.flatMap((f) => f.choices?.[0]?.delta?.tool_calls || [])
  if (calls.length !== 3) bad(`multi-card=3 的 tool_calls 数 ${calls.length}，期望 3`)
  if (new Set(calls.map((c) => c.id)).size !== calls.length) bad('multi-card 的 toolCallId 有重复（多卡重建会互相覆盖）')
  if (calls.some((c) => c.function.name !== 'start_export')) bad('multi-card 的卡片工具名不一致')
  const huge = hugeTextRound(4096, 'ci-stub')
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const hugeDelta = huge.filter((f) => f.choices?.[0]?.delta?.content)
  if (hugeDelta.length !== 1) bad(`huge-frame 应是单个 delta 帧，实际 ${hugeDelta.length} 个`)
  else if (hugeDelta[0].choices[0].delta.content.length < 4096) bad('huge-frame 的 delta 未达到请求字节数')
  else if (!huge.some((f) => f.choices?.[0]?.finish_reason === 'stop')) bad('huge-frame 缺 stop 终帧')

  // ⑨-e2 思考分片（`reasoning=<chars>`，L-91 的上游侧形态）：默认关闭零漂移 / 插在正文之前 /
  //       1–2 片且字符合计等于请求值 / 与 huge-frame 正交（超 64KB 的思考文本保持单片）
  const parseFrames = (frames) => frames
    .filter((f) => f !== 'data: [DONE]\n\n')
    .map((f) => JSON.parse(f.replace(/^data: /, '').trim()))
  const reasonText = '思考分片用例正文。'
  const plainRound = textRound(reasonText, 'ci-stub', 'last')
  // 只比"可观测契约"（帧数 / delta / finish_reason / usage）：`id`/`created` 每次生成都不同，
  // 拿它们做逐字节比较会把任何一次调用都判成漂移。
  const shapeOf = (frames) => JSON.stringify(parseFrames(frames).map((f) => ({ choices: f.choices, usage: f.usage ?? null })))
  const plainShape = shapeOf(plainRound)
  const sameAsPlain = (faults) => shapeOf(framesFor({ kind: 'text', text: reasonText }, faults, 0, 'ci-stub', 'last'))
    === plainShape
  if (!sameAsPlain({})) bad('reasoning 开关关闭时帧序列必须与 textRound 逐字节相同（既有精确帧数断言零漂移）')
  if (!sameAsPlain({ reasoning: 0 })) bad('reasoning=0 必须等价于关闭（帧逐字节相同）')
  if (!sameAsPlain({ reasoning: true })) bad('裸 reasoning 标记（无 =<chars>）应等价于关闭（表内默认 0）')
  const reasonFrames = parseFrames(framesFor({ kind: 'text', text: reasonText }, { reasoning: 120 }, 0, 'ci-stub', 'last'))
  const reasonDeltas = reasonFrames.filter((f) => f.choices?.[0]?.delta?.reasoning_content !== undefined)
  const reasonChars = reasonDeltas.reduce((sum, f) => sum + f.choices[0].delta.reasoning_content.length, 0)
  if (reasonDeltas.length < 1 || reasonDeltas.length > 2) {
    bad(`reasoning=120 的分片数 ${reasonDeltas.length}，期望 1–2`)
  }
  if (reasonChars !== 120) bad(`reasoning=120 的分片字符合计 ${reasonChars}，期望 120`)
  if (reasonFrames[0]?.choices?.[0]?.delta?.role !== 'assistant') {
    bad('思考分片必须插在 {role:assistant} 首帧之后（首帧不得被顶掉）')
  }
  const firstContent = reasonFrames.findIndex((f) => f.choices?.[0]?.delta?.content)
  const lastReason = reasonFrames.map((f) => f.choices?.[0]?.delta?.reasoning_content !== undefined).lastIndexOf(true)
  if (firstContent < 0) bad('reasoning 轮的正文分片缺失（开关只加思考片，不得吞正文）')
  else if (lastReason > firstContent) bad('思考分片必须全部在正文分片之前（收尾顺序 role→reasoning_content→content）')
  if (!reasonFrames.some((f) => f.choices?.[0]?.finish_reason === 'stop')) bad('reasoning 轮缺 stop 终帧')
  const hugeReason = parseFrames(framesFor({ kind: 'text', text: reasonText },
    { reasoning: 70000, 'huge-frame': 4096 }, 0, 'ci-stub', 'last'))
  const hugeReasonDeltas = hugeReason.filter((f) => f.choices?.[0]?.delta?.reasoning_content !== undefined)
  const hugeReasonContent = hugeReason.filter((f) => f.choices?.[0]?.delta?.content)
  if (hugeReasonDeltas.length !== 1) {
    bad(`超 64KB 的思考文本应保持单片（帧侧字段级截断的观测面），实际 ${hugeReasonDeltas.length} 片`)
  } else if (hugeReasonDeltas[0].choices[0].delta.reasoning_content.length !== 70000) {
    bad(`reasoning=70000 的单片长度 ${hugeReasonDeltas[0].choices[0].delta.reasoning_content.length}，期望 70000`)
  }
  if (hugeReasonContent.length !== 1) {
    bad(`reasoning 与 huge-frame 同开时正文仍应是单个 delta，实际 ${hugeReasonContent.length}`)
  }
  const markerReason = faultsOf({ messages: [{ role: 'user', content: '前缀 reasoning=120 后缀' }] })
  if (markerReason.reasoning !== 120) bad(`reasoning 标记解析 ${JSON.stringify(markerReason)}，期望 {reasoning:120}`)
  ok(`思考分片自证通过（关闭零漂移 / ${reasonDeltas.length} 片合计 ${reasonChars} 字全在正文之前 / 与 huge-frame 正交）`)

  // ⑨-f 越窗/摘要：错误体必须命中产品 ContextOverflow.MARKERS（读事实源，不猜措辞）
  const overflowRel = 'backend/src/main/java/com/example/configmgr/ai/context/ContextOverflow.java'
  const overflowPath = path.resolve(HERE, '..', '..', overflowRel)
  const overflowSrc = fs.existsSync(overflowPath) ? fs.readFileSync(overflowPath, 'utf8') : null
  if (overflowSrc === null) {
    bad(`越窗措辞事实源不可读：${overflowRel}`)
  } else {
    const markers = javaStringList(overflowSrc, 'MARKERS') || []
    const bodyText = JSON.stringify(OVERFLOW_BODY).toLowerCase()
    if (markers.length === 0) bad('事实源未解析到 ContextOverflow.MARKERS')
    else if (!markers.some((m) => bodyText.includes(m.toLowerCase()))) {
      bad(`context-overflow 的错误体不含任何产品越窗措辞（MARKERS=${markers.join('/')}），产品会退化成泛化上游错误`)
    }
    ok(`context-overflow 错误体命中产品越窗措辞（事实源 ${overflowRel}：${markers.length} 个 marker）`)
  }
  const summarizerRel = 'backend/src/main/java/com/example/configmgr/ai/context/RollingSummarizer.java'
  const summarizerSrc = fs.existsSync(path.resolve(HERE, '..', '..', summarizerRel))
    ? fs.readFileSync(path.resolve(HERE, '..', '..', summarizerRel), 'utf8') : null
  if (summarizerSrc === null) bad(`摘要提示词事实源不可读：${summarizerRel}`)
  else if (!SUMMARY_REQUEST.test(summarizerSrc)) bad('摘要请求识别式与产品提示词不同源（RollingSummarizer 的指令头已改）')
  else ok('summary-500 的摘要请求识别式与产品提示词同源（RollingSummarizer 两段指令头）')

  // ⑨-g GF 新能力面：七型 = 白名单全集；深联动确实超深；两条 from 用注册源
  const sevenRoute = (ROUTES.routes || []).find((r) => r.id === 'GF-SEVEN-TYPES')
  const sevenTypes = (sevenRoute?.arguments?.form?.fields || []).map((f) => f.type)
  if (sortedJoin(sevenTypes) !== sortedJoin(FIELD_TYPES)) {
    bad(`GF-SEVEN-TYPES 的字段类型 [${sevenTypes.join(',')}] 未覆盖白名单全集 [${FIELD_TYPES.join(',')}]`)
  }
  const deepRoute = (ROUTES.routes || []).find((r) => r.id === 'GF-DEEP-LINKAGE')
  const deepFields = deepRoute?.arguments?.form?.fields || []
  const chain = (key) => {
    let depth = 0
    let cur = deepFields.find((f) => f.key === key)
    while (cur && cur.visibleWhen) {
      depth++
      cur = deepFields.find((f) => f.key === cur.visibleWhen.key)
    }
    return depth
  }
  const deepest = Math.max(0, ...deepFields.map((f) => chain(f.key)))
  if (deepest <= MAX_LINKAGE_DEPTH) bad(`GF-DEEP-LINKAGE 的依赖深度 ${deepest} 未超上限 ${MAX_LINKAGE_DEPTH}（用例 G-13 会空转）`)
  const optRoute = (ROUTES.routes || []).find((r) => r.id === 'GF-OPTIONS-FROM')
  const optField = (optRoute?.arguments?.form?.fields || []).find((f) => f.optionsFrom)
  if (!optField || !optField.optionsFrom.map) bad('GF-OPTIONS-FROM 缺 optionsFrom.map（G-11 无底座）')
  const defField = (optRoute?.arguments?.form?.fields || []).find((f) => f.defaultFrom)
  if (!defField) bad('GF-OPTIONS-FROM 缺 defaultFrom（G-12 无底座）')
  ok(`GF 新能力面自证通过（七型覆盖 / 深联动 ${deepest} 层 > ${MAX_LINKAGE_DEPTH} / optionsFrom+defaultFrom 底座在位）`)

  // ⑨-h `definitions`（配置定义枚举，批次4 后端D）：路由 +「层级 → 定义」样例返回必须同源、可用。
  //   三条硬边界：①样例不得是空壳（每个层级 ≥1 项，value/label 非空且 value 不重复）；
  //   ②空 / 未知父值 ⇒ 空列表（unknown parent 不得回退全量；也不许 ALL/* 这类通配层级）；
  //   ③未注册 id ⇒ null（与后端注册表同语义：闭集扩容不等于开放面）。
  const defRoute = (ROUTES.routes || []).find((r) => r.id === 'GF-DEFINITION-OPTIONS')
  const defFixture = definitionOptionFixture()
  const fixtureLevels = Object.keys(defFixture)
  if (!defRoute) {
    bad('缺 GF-DEFINITION-OPTIONS 路由：definitions 数据源在桩内没有可用返回面（空壳）')
  } else {
    const defFields = defRoute.arguments?.form?.fields || []
    const levelField = defFields.find((f) => f.key === 'level')
    const sourcedField = defFields.find((f) => f.optionsFrom?.sourceId === DEFINITION_SOURCE_ID)
    if (!sourcedField) bad('GF-DEFINITION-OPTIONS 缺 optionsFrom.sourceId=definitions 的字段（返回面是空壳）')
    else if (sourcedField.optionsFrom.dependsOn !== 'level') {
      bad(`GF-DEFINITION-OPTIONS 的 definitions 级联必须以 level 为父字段（实际 ${sourcedField.optionsFrom.dependsOn}）`)
    }
    const levelValues = (levelField?.options || []).map((o) => o.value)
    if (!levelField) bad('GF-DEFINITION-OPTIONS 缺 level 字段（definitions 级联没有父字段可选）')
    else if (sortedJoin(levelValues) !== sortedJoin(fixtureLevels)) {
      bad(`GF-DEFINITION-OPTIONS 的 level 选项 [${levelValues.join(',')}] 与样例层级 [${fixtureLevels.join(',')}] 不一致`)
    }
  }
  if (fixtureLevels.length === 0) {
    bad('stub-routes.json 的 optionSources.definitions 为空（层级 → 定义样例缺失，返回即空壳）')
  }
  if (fixtureLevels.some((k) => ['ALL', 'ANY', '*', '__ALL__'].includes(k))) {
    bad(`optionSources.definitions 含通配层级 [${fixtureLevels.join(',')}]：unknown parent 不得回退全量`)
  }
  let fixtureCount = 0
  for (const level of fixtureLevels) {
    const options = defFixture[level]
    if (!Array.isArray(options) || options.length === 0) {
      bad(`optionSources.definitions["${level}"] 为空（不得返回空壳）`)
      continue
    }
    fixtureCount += options.length
    const seenValues = new Set()
    for (const option of options) {
      const value = option?.value
      if (typeof value !== 'string' || value.trim() === '') bad(`optionSources.definitions["${level}"] 含空 value`)
      else if (seenValues.has(value)) bad(`optionSources.definitions["${level}"] 的 value "${value}" 重复`)
      else seenValues.add(value)
      if (typeof option?.label !== 'string' || option.label.trim() === '') {
        bad(`optionSources.definitions["${level}"] 含空 label（选项必须有可读文案）`)
      }
    }
    // 解析器与样例同源：层级大小写/首尾空白归一后仍返回同一批选项
    const resolved = optionSourceOptions(DEFINITION_SOURCE_ID, ` ${level.toLowerCase()} `)
    if (resolved.length !== options.length) {
      bad(`definitions 解析 "${level}" 得到 ${resolved.length} 项，样例 ${options.length} 项（解析器与样例不同源）`)
    }
  }
  for (const parent of [undefined, null, '', '   ', 'ALL', 'NOPE']) {
    const resolved = optionSourceOptions(DEFINITION_SOURCE_ID, parent)
    if (!Array.isArray(resolved) || resolved.length !== 0) {
      bad(`definitions 对父值 ${JSON.stringify(parent)} 必须返回空列表（unknown parent 不得回退全量）`)
    }
  }
  if (optionSourceOptions('definitions_all', 'GLOBAL') !== null) {
    bad('未注册的选项数据源必须返回 null（与后端注册表同语义，闭集不因扩容变开放面）')
  }
  ok(`definitions 数据源自证通过（${fixtureLevels.length} 个层级 / 样例 ${fixtureCount} 项；空或未知父值空列表；未注册 id 拒绝）`)

  section()
  // ⑩ 批③b 桩增强：请求级工具路由 / epoch 注入 / 迟到帧。全部纯函数自证，不起服务。
  // ⑩-a `tool=<name>&args=<json>` 解析：嵌套对象、字符串里的 `}`、缺 args、非法 JSON、无标记
  const toolCases = [
    ['嵌套对象', '前缀 tool=list_tasks&args={"status":"RUNNING","page":{"size":10}} 后缀',
      { name: 'list_tasks', args: { status: 'RUNNING', page: { size: 10 } } }],
    ['字符串含右括号', 'tool=get_config_def&args={"code":"A}B"}',
      { name: 'get_config_def', args: { code: 'A}B' } }],
    ['只给工具名', 'tool=list_tasks', { name: 'list_tasks', args: {} }],
    ['无标记', '普通一句', null]
  ]
  for (const [label, text, want] of toolCases) {
    const got = requestToolOf({ messages: [{ role: 'user', content: text }] })
    if (JSON.stringify(got) !== JSON.stringify(want)) {
      bad(`请求级工具标记解析失败（${label}）：得到 ${JSON.stringify(got)}，期望 ${JSON.stringify(want)}`)
    }
  }
  const badArgsReq = requestToolOf({ messages: [{ role: 'user', content: 'tool=start_import&args={oops' }] })
  if (!badArgsReq?.badArgs) bad('请求级工具标记的非法 JSON 必须落到 badArgs（否则会拿半截入参去调工具）')

  // ⑩-b 披露闸与台账：已披露 ⇒ 真发 tool_call；未披露 / 不在产品清单 ⇒ 文本轮；同提示词只发一次
  const rtPrompt = 'tool=list_tasks&args={"status":"RUNNING"} 用例RT1'
  const rtBody = { messages: [{ role: 'user', content: rtPrompt }], tools: [{ function: { name: 'list_tasks' } }] }
  const rtDecision = decide(rtBody, ROUTES, turns)
  if (rtDecision.kind !== 'tool_call' || rtDecision.tool !== 'list_tasks' || rtDecision.route !== 'REQUEST-TOOL') {
    bad(`请求级工具在已披露时应发 tool_call(list_tasks)，实际 ${rtDecision.kind}/${rtDecision.tool ?? '-'}`)
  }
  if (rtDecision.args?.status !== 'RUNNING') bad('请求级工具的 args 未原样透传（JSON 解析丢值）')
  const rtNotDisclosed = decide({ messages: [{ role: 'user', content: 'tool=list_tasks 用例RT2' }], tools: [] }, ROUTES, turns)
  if (rtNotDisclosed.kind !== 'text') bad('未披露的请求级工具必须退化为文本轮（披露闸不得被请求级标记绕过）')
  const rtUnknown = decide({ messages: [{ role: 'user', content: 'tool=drop_everything 用例RT3' }],
    tools: [{ function: { name: 'drop_everything' } }] }, ROUTES, turns)
  if (rtUnknown.kind !== 'text') bad('不在 productTools 的请求级工具必须退化为文本轮（不得调产品没有的工具）')
  const rtAgain = decide({ messages: [{ role: 'user', content: 'tool=list_tasks&args={"status":"RUNNING"} 用例RT1' }],
    tools: [{ function: { name: 'list_tasks' } }] }, ROUTES, turns)
  if (rtAgain.kind !== 'text') bad('同一提示词的请求级工具只允许发一次（否则重试路径会变成请求风暴）')
  ok('请求级工具路由自证通过（嵌套 args 透传 / 披露闸优先 / 产品清单闸 / 同提示词幂等）')

  // ⑩-c epoch 注入：只改既有 epoch 键，不改其他入参，无键不新增
  const epochCases = [
    ['合法 token', 'epoch=ab12cd-7', 'ab12cd-7'],
    ['非法（大写）', 'epoch=AB12CD-7', null],
    ['非法（无计数器）', 'epoch=ab12cd', null],
    ['无标记', '普通一句', null]
  ]
  for (const [label, text, want] of epochCases) {
    const got = epochOf({ messages: [{ role: 'user', content: text }] })
    if (got !== want) bad(`epoch 标记解析失败（${label}）：得到 ${got}，期望 ${want}`)
  }
  const epochDecision = { kind: 'tool_call', tool: 'ui_click', args: { ref: 'import.upload.choose', epoch: 'a1b2c3-1' } }
  if (!applyEpoch(epochDecision, 'zz9yy8-3')) bad('epoch 注入未生效')
  if (epochDecision.args.epoch !== 'zz9yy8-3') bad(`epoch 注入值 ${epochDecision.args.epoch}，期望 zz9yy8-3`)
  if (epochDecision.args.ref !== 'import.upload.choose') bad('epoch 注入不得改动其他入参')
  const noEpochKey = { kind: 'tool_call', tool: 'list_tasks', args: { status: 'RUNNING' } }
  applyEpoch(noEpochKey, 'zz9yy8-3')
  if ('epoch' in noEpochKey.args) bad('epoch 注入不得给没有 epoch 键的工具**新增**入参键')
  ok('epoch 请求级注入自证通过（形状与产品 Guard 同源 / 只改既有键 / 不新增键）')

  // ⑩-d 迟到帧：`[DONE]` 之后才有内容（帧序 = 正常轮 + n 个迟到帧），且迟到帧自带内容
  const normalTail = textRound('收尾文本', 'ci-stub')
  if (normalTail[normalTail.length - 1] !== 'data: [DONE]\n\n') bad('文本轮末帧必须是 [DONE]（迟到帧只能在它之后）')
  const late = lateFrameChunks(2, 'ci-stub')
  if (late.length !== 2) bad(`迟到帧数量 ${late.length}，期望 2`)
  for (const raw of late) {
    if (!raw.startsWith('data: ') || !raw.endsWith('\n\n')) bad('迟到帧必须是合法 SSE data 帧')
    const parsed = JSON.parse(raw.replace(/^data: /, '').trim())
    if (!parsed?.choices?.[0]?.delta?.content) bad('迟到帧必须携带 delta.content（否则客户端丢弃面无从观测）')
  }
  ok('迟到帧自证通过（done 之后推 / 数量与内容可判 / 正常轮末帧不变）')

  console.log('------------------------------------------------------------')
  if (problems.length) {
    console.log(`SELF-CHECK FAIL：${problems.length} 项`)
    for (const p of problems) console.log(`  - ${p}`)
    process.exit(1)
  }
  console.log('SELF-CHECK PASS：路由表契约、白名单对账、收敛自证、披露驱动、帧形态、反解、usage 形状、§4.6 故障开关全部通过')
  process.exit(0)
}

// ── 入口 ──────────────────────────────────────────────────────────────────

const ROUTES = loadRoutes(ROUTES_PATH)
const violations = applyViolation(ROUTES)

if (SELF_CHECK) {
  console.log(`stub-upstream 自检 routes=${ROUTES_PATH}`)
  selfCheck()
} else {
  startServer()
}
