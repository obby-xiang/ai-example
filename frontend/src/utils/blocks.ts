/**
 * 对话时序包 批4④（A5'）：消息段的**块分派**纯函数 —— `groupParts`。
 *
 * 把 `m.parts` 段序列分派为渲染块（Block）：
 * - 相邻连续的 `reasoning` / `tool` 段合成一个"活动组"（不按类型桶）；被正文隔开的工具调用分属不同组；
 * - `text` 段一律 `break`（正文永不进组，A5' 规则③）；空正文段不渲染、不打断组（视觉相邻即同组）；
 * - `requiresAction` 段独立成组（D-C9：HITL 卡不与其它段同组，逐段出卡的前提）；
 * - `blockId` 取组首成员业务稳定 id（toolCallId / `reasoning-${seg}`），禁数组下标（D5），
 *   并加消息命名空间前缀 `${messageId}:`（F1：seg 逐消息自 0 起，跨消息会撞 id 致 expandedBlocks 串味）。
 *
 * 本文件**不依赖 store**（纯函数层，循 utils/parts.ts 分层自述）；展开态三态判定在
 * {@link resolveBlockExpanded}，用户触碰记录存 store 的 `expandedBlocks`（D4：会话内内存态）。
 */
import type { MessagePart, ReasoningPart, ToolPart } from '@/types/ai'

export type ActivityPart = ToolPart | ReasoningPart

/** 渲染块：text = 单段正文（永不进组）；activity = reasoning/tool 组。 */
export interface Block {
  /** 组首成员稳定 id（加消息命名空间前缀，F1）：`${messageId}:${toolCallId}` 或 `${messageId}:reasoning-${seg}`（D5） */
  id: string
  kind: 'text' | 'activity'
  /** text 块恒 1 个元素；activity 块成员 kind ∈ {'reasoning','tool'} */
  parts: MessagePart[]
  /** D6 判定序四档（见 defaultExpandedOf） */
  defaultExpanded: boolean
  /** D7：单成员组不套壳，直渲成员（工具卡 / 思考链受控块） */
  bare: boolean
}

function stablePartId(part: MessagePart, messageId: string): string {
  const base = part.kind === 'tool' ? part.toolCallId : `${part.kind}-${part.seg}`
  // F1（红队 M-1）：seg 逐消息自 0 起（parts.ts nextSeg），跨消息同 seg 会撞 id ⇒ 全量加消息命名空间前缀
  return `${messageId}:${base}`
}

function isActivityPart(part: MessagePart): part is ActivityPart {
  return part.kind === 'tool' || part.kind === 'reasoning'
}

/**
 * defaultExpanded 判定序（终裁 D6，按序短路）：
 * ① 组内任一成员段 requiresAction === true ⇒ true（强制展开，R4-5 + D-C6，手动收起不生效）；
 * ② 组内任一成员段 reason 非空 ⇒ true（D-C1：未触碰组不得被自动折叠；允许用户手动收起）；
 * ③ 组内存在工具成员段且 status ∈ {pending, running} ⇒ true（"进行中"，R4-1/D6）；
 * ④ 其余（含纯 reasoning 组、全部工具成员已终态）⇒ false（D-C8：reasoning 组维持现 UX 默认折叠）。
 */
export function defaultExpandedOf(parts: ActivityPart[]): boolean {
  const tools = parts.filter((part): part is ToolPart => part.kind === 'tool')
  if (tools.some((part) => part.requiresAction)) {
    return true
  }
  if (tools.some((part) => part.reason)) {
    return true
  }
  if (tools.some((part) => part.status === 'pending' || part.status === 'running')) {
    return true
  }
  return false
}

/**
 * 展开态三态判定（D-C6）：manual = store.expandedBlocks[block.id]。
 * true = 显式展开 / false = 显式收起 / undefined（键缺省）= 未触碰 —— 自动逻辑只作用于未触碰组。
 * 例外：requiresAction 组强制展开优先级最高（用户手动收起不生效）。
 */
export function resolveBlockExpanded(block: Block, manual: boolean | undefined): boolean {
  if (block.parts.some((part) => part.kind === 'tool' && part.requiresAction)) {
    return true
  }
  if (manual !== undefined) {
    return manual
  }
  return block.defaultExpanded
}

/** 组头文案：纯 reasoning 组「思考过程 · N 段」；含工具「工具活动 · N 段」。 */
export function blockHeader(block: Block): string {
  const allReasoning = block.parts.every((part) => part.kind === 'reasoning')
  return allReasoning ? `思考过程 · ${block.parts.length} 段` : `工具活动 · ${block.parts.length} 段`
}

/**
 * 段序列 → 渲染块序列（纯函数，不改段）。
 * DEV 下断言 activity 成员 kind ∈ {'reasoning','tool'}（A5' 规则③的反向闸）。
 */
export function groupParts(parts: MessagePart[], messageId: string): Block[] {
  const blocks: Block[] = []
  let current: ActivityPart[] = []

  const flush = (): void => {
    if (current.length === 0) {
      return
    }
    if (import.meta.env.DEV && current.some((part) => (part as MessagePart).kind === 'text')) {
      // 不可达防御：activity 组混入正文段即分组器缺陷，必须炸在开发期
      console.error('[blocks] activity 组混入 text 成员（正文永不进组，A5\' 规则③）', current)
    }
    const first = current[0]
    blocks.push({
      id: stablePartId(first, messageId),
      kind: 'activity',
      parts: [...current],
      defaultExpanded: defaultExpandedOf(current),
      bare: current.length === 1
    })
    current = []
  }

  for (const part of parts) {
    if (part.kind === 'text') {
      if (!part.text) {
        // 空正文段不渲染（模板 v-if="part.text"），视觉上不打断相邻工具 ⇒ 不打断组
        continue
      }
      flush()
      blocks.push({ id: stablePartId(part, messageId), kind: 'text', parts: [part], defaultExpanded: true, bare: true })
      continue
    }
    if (!isActivityPart(part)) {
      continue
    }
    if (part.kind === 'tool' && part.requiresAction) {
      // D-C9：HITL 段独立成组（前后都断），不与其它段同组
      flush()
      current = [part]
      flush()
      continue
    }
    current.push(part)
  }
  flush()
  return blocks
}
