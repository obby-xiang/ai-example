/**
 * 契约层 `ui_event.set_conditions` 的**载荷校验**（R3 返修，与页面 handler 同判据链）。
 *
 * 页面未挂载时 `dispatchUiEvent` 兜底执行 `set_conditions`：它拿到的 `conditions` 是
 * **模型给的原始载荷**（对象或 JSON 字符串）。旧实现原样 `PUT` 并写工作区，于是
 * `IN []` / 值算子空串会落库成"0 行/空值"条件，坏 JSON 甚至会被当成字符串或空条件
 * （= 无条件 = 导出全部）。
 *
 * 收口口径：`parseConditionJson` → `draftModelFromCondition` → `buildConditionOutcome`
 * （与 `export.setConditions` / `persistStepData` 完全同一判据）：
 * - 不可解析 / 结构坏 / 非法空值 ⇒ 失败（调用方零 PUT、零工作区写）；
 * - **显式空条件**（`{}` / 无任何字段与范围）⇒ 清空（与页面一致：PUT `{}`）；
 * - 合法复杂条件 ⇒ 按内容原样落库（同字段多条件、含逗号 IN 都不改写）。
 */

import {
  buildConditionOutcome,
  draftModelFromCondition,
  parseConditionJson,
  stringifyCondition,
  type FieldQueryCondition
} from '@/types/condition'

export type ConditionPayloadVerdict =
  | {
      ok: true
      /** 解析后的条件（显式清空为 null） */
      condition: FieldQueryCondition | null
      /** 落库用的规范化 JSON（清空恒为 `{}`） */
      serialized: string
    }
  | { ok: false; reason: string }

const REJECT_SUFFIX = '本次未设置查询条件（未落库、未写入工作区）'

function reject(reason: string): ConditionPayloadVerdict {
  return { ok: false, reason: `${reason}：${REJECT_SUFFIX}` }
}

/** 结构检查：坏载荷不能靠 `parseConditionJson` 静默丢条目后变成"无条件"。 */
function structuralProblem(value: unknown): string | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return '查询条件必须是 JSON 对象'
  }
  const candidate = value as { scopeKeys?: unknown; fields?: unknown }
  if (Object.prototype.hasOwnProperty.call(candidate, 'scopeKeys')) {
    const keys = candidate.scopeKeys
    if (!Array.isArray(keys) || keys.some((key) => typeof key !== 'string')) {
      return 'scopeKeys 必须是字符串数组'
    }
  }
  if (Object.prototype.hasOwnProperty.call(candidate, 'fields')) {
    const fields = candidate.fields
    if (!Array.isArray(fields)) {
      return 'fields 必须是数组'
    }
    for (const entry of fields) {
      if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
        return '查询条件条目必须是对象'
      }
      const item = entry as { fieldCode?: unknown; operator?: unknown }
      if (typeof item.fieldCode !== 'string' || item.fieldCode === '') {
        return '查询条件条目缺少 fieldCode'
      }
      if (typeof item.operator !== 'string' || item.operator === '') {
        return '查询条件条目缺少 operator'
      }
    }
  }
  return null
}

/**
 * 校验并规范化一份 `set_conditions` 载荷。
 *
 * @param payload 模型给的原始载荷：对象或 JSON 字符串
 * @returns 失败原因（可直接回灌）或 `{condition, serialized}`
 */
export function validateConditionPayload(payload: unknown): ConditionPayloadVerdict {
  let parsed: unknown
  if (typeof payload === 'string') {
    if (payload.trim() === '') {
      return reject('缺少 conditions（查询条件载荷为空）')
    }
    try {
      parsed = JSON.parse(payload)
    } catch {
      return reject('查询条件无法解析为 JSON（载荷损坏）')
    }
  } else {
    try {
      // 深拷贝一次：避免调用方对象与后续判读共享引用（也顺带吃掉 undefined / 函数值）
      parsed = JSON.parse(JSON.stringify(payload))
    } catch {
      return reject('查询条件载荷无法序列化')
    }
  }
  const problem = structuralProblem(parsed)
  if (problem !== null) {
    return reject(problem)
  }
  const condition = parseConditionJson(JSON.stringify(parsed))
  const outcome = buildConditionOutcome(draftModelFromCondition(condition))
  if (outcome.status === 'invalid') {
    const issues = outcome.issues.map((issue) => `${issue.fieldCode}:${issue.code}`).join('、')
    return reject(`查询条件不合法（${issues}）；值算子必须填值、IN 至少一个值（空值判断请用 EMPTY / NOT_EMPTY）`)
  }
  // `cleared`（响应里没有任何条件）在这里等价于**显式清空**：与页面 handler 的
  // `buildConditionOutcome === 'cleared'` 分支一致；坏载荷已在结构检查处被拒，不会走到这里。
  return { ok: true, condition: outcome.condition, serialized: stringifyCondition(outcome.condition) }
}
