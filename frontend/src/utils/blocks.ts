/**
 * 对话时序包 批4④（A5'）→ R-154 #13+14（用户直裁「彻底拆除」组壳）：消息段的**块分派**纯函数 —— `groupParts`。
 *
 * R-154 改判后口径（裁决依据 = 用户直裁，非调研建议）：
 * - **组壳彻底拆除**：每个 reasoning / tool 段独立成一个 Block（恒 1 段，不再相邻合并成组）；
 *   原「相邻连续 activity 合成组」「requiresAction 独立成组」特判随之退役（每段天然独立）；
 * - `text` 段一律独立成块（正文永不进组，A5' 规则③）；空正文段不渲染、不成块；
 * - `blockId` 取成员业务稳定 id（toolCallId / `reasoning-${seg}`），禁数组下标（D5），
 *   并加消息命名空间前缀 `${messageId}:`（F1：seg 逐消息自 0 起，跨消息会撞 id 致 expandedBlocks 串味）。
 *
 * 本文件**不依赖 store**（纯函数层，循 utils/parts.ts 分层自述）；展开态三态判定在
 * {@link resolveBlockExpanded}，用户触碰记录存 store 的 `expandedBlocks`（D4：会话内内存态）。
 */
import type { MessagePart, ReasoningPart, ToolPart } from '@/types/ai'

export type ActivityPart = ToolPart | ReasoningPart

/** 渲染块：text = 单段正文；activity = 单段 reasoning/tool 卡（R-154 起恒 1 段，组壳已拆）。 */
export interface Block {
  /** 成员稳定 id（加消息命名空间前缀，F1）：`${messageId}:${toolCallId}` 或 `${messageId}:reasoning-${seg}`（D5） */
  id: string
  kind: 'text' | 'activity'
  /** 恒 1 个元素（R-154 #13+14：每段一卡）；成员 kind ∈ {'reasoning','tool'} */
  parts: MessagePart[]
  /** R-154 重定义：requiresAction 段 true（强制展开）；其余一律 false（每卡默认折叠，单行摘要） */
  defaultExpanded: boolean
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
 * defaultExpanded 判定（R-154 #13+14 重定义，用户直裁「每卡默认折叠（单行摘要）」）：
 * 唯 requiresAction === true ⇒ true（强制展开，R4-5 + D-C6，手动收起不生效）；
 * 其余（含 reason 非空、pending/running 进行中、纯 reasoning 段）一律 false。
 * 原 D6 四档短路判定序随组壳整体退役 —— 卡面折叠单行已带状态 tag（toolStatusLabel），
 * 进行中/失败态在折叠行即可辨识，详情点击展开。
 */
export function defaultExpandedOf(parts: ActivityPart[]): boolean {
  return parts.some((part) => part.kind === 'tool' && part.requiresAction)
}

/**
 * 展开态三态判定（D-C6）：manual = store.expandedBlocks[block.id]。
 * true = 显式展开 / false = 显式收起 / undefined（键缺省）= 未触碰 —— 自动逻辑只作用于未触碰卡。
 * 例外：requiresAction 卡强制展开优先级最高（用户手动收起不生效）。
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

/**
 * 段序列 → 渲染块序列（纯函数，不改段）。
 * R-154 #13+14：每个 activity 段独立成块（恒 1 段）；DEV 下断言 activity 成员 kind ∈ {'reasoning','tool'}。
 */
export function groupParts(parts: MessagePart[], messageId: string): Block[] {
  const blocks: Block[] = []

  for (const part of parts) {
    if (part.kind === 'text') {
      if (!part.text) {
        // 空正文段不渲染（模板 v-if="part.text"），不成块
        continue
      }
      blocks.push({ id: stablePartId(part, messageId), kind: 'text', parts: [part], defaultExpanded: true })
      continue
    }
    if (!isActivityPart(part)) {
      continue
    }
    // R-154：每段一卡 —— requiresAction 段不再需要"独立成组"特判（天然独立）
    blocks.push({
      id: stablePartId(part, messageId),
      kind: 'activity',
      parts: [part],
      defaultExpanded: defaultExpandedOf([part])
    })
  }
  return blocks
}
