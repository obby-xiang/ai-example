/**
 * 对话时序包 批4④（A5' 折叠规则本体）：`utils/blocks.ts` 纯函数锁。
 *
 * 锁的口径 = 施工卡 §5 TS-1（①~⑩）+ TS-7（多 requiresAction 段配对）：
 * groupParts 只分派、不改段（纯函数，不依赖 store，循 utils/parts.ts 分层自述）。
 */
import { describe, expect, it } from 'vitest'
import type { MessagePart, ReasoningPart, ToolPart } from '@/types/ai'
import { blockHeader, groupParts, resolveBlockExpanded } from '@/utils/blocks'

let seq = 0
function makeTool(overrides: Partial<ToolPart> = {}): ToolPart {
  seq += 1
  return {
    kind: 'tool',
    seg: seq,
    toolCallId: `tc-${seq}`,
    name: 'probe.tool',
    kind2: 'BACKEND',
    status: 'succeeded',
    requiresAction: false,
    ...overrides
  } as ToolPart
}

function makeReasoning(text = '想了一下', overrides: Partial<ReasoningPart> = {}): ReasoningPart {
  seq += 1
  return { kind: 'reasoning', seg: seq, text, streaming: false, ...overrides } as ReasoningPart
}

function makeText(text: string, overrides: Partial<MessagePart> = {}): MessagePart {
  seq += 1
  return { kind: 'text', seg: seq, text, streaming: false, ...overrides } as MessagePart
}

describe('批4④ groupParts：块分派（A5\' 规则①相邻连续合并 / 规则③正文永不进组）', () => {
  it('TS-1① [text, tool, tool, text] ⇒ 正文 + 一个 2 成员活动组 + 正文，段序与块序一致', () => {
    const t1 = makeText('前文')
    const a = makeTool({ toolCallId: 'a' })
    const b = makeTool({ toolCallId: 'b' })
    const t2 = makeText('后文')
    const blocks = groupParts([t1, a, b, t2], 'msg-1')
    expect(blocks.map((block) => block.kind)).toEqual(['text', 'activity', 'text'])
    const group = blocks[1]
    expect(group.parts.map((p) => (p.kind === 'tool' ? p.toolCallId : p.kind))).toEqual(['a', 'b'])
    expect(group.bare).toBe(false)
  })

  it('TS-1② [tool, text, tool] ⇒ 被正文隔开的工具分属两个组', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'a' }), makeText('隔断'), makeTool({ toolCallId: 'b' })], 'msg-1')
    expect(blocks.filter((b) => b.kind === 'activity')).toHaveLength(2)
    expect(blocks.map((b) => b.kind)).toEqual(['activity', 'text', 'activity'])
  })

  it('TS-1③ [tool] 单元素组 ⇒ bare === true（D7：不套壳，直渲该卡）', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'solo' })], 'msg-1')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].bare).toBe(true)
    expect(blocks[0].kind).toBe('activity')
  })

  it('TS-1④ [reasoning, reasoning] ⇒ 一个组且默认折叠（D-C8：reasoning 组维持现 UX 默认折叠）', () => {
    const blocks = groupParts([makeReasoning('想一'), makeReasoning('想二')], 'msg-1')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].defaultExpanded).toBe(false)
    expect(blocks[0].bare).toBe(false)
  })

  it("TS-1⑤ 任何 Block 成员 kind !== 'text'（正文永不进组；含 reasoning+tool 混合组）", () => {
    const blocks = groupParts([
      makeText('头'),
      makeTool({ toolCallId: 'a' }),
      makeReasoning('组内思考'),
      makeTool({ toolCallId: 'b' }),
      makeText('尾')
    ], 'msg-1')
    for (const block of blocks) {
      for (const member of block.parts) {
        expect(member.kind === 'text' ? block.kind === 'text' : true).toBe(true)
      }
    }
    const group = blocks.find((b) => b.kind === 'activity')!
    expect(group.parts.map((p) => p.kind)).toEqual(['tool', 'reasoning', 'tool'])
  })

  it('TS-1⑥ blockId 稳定性：新成员加入后既有组 id 不变（D5：业务稳定 id，禁下标）', () => {
    const a = makeTool({ toolCallId: 'a', status: 'running' })
    const b = makeTool({ toolCallId: 'b' })
    const first = groupParts([a, b], 'msg-1')
    expect(first[0].id).toBe('msg-1:a')
    // 后续帧又来了第三个工具（位置相邻）——重组后组 id 仍是首成员 toolCallId
    const c = makeTool({ toolCallId: 'c' })
    const second = groupParts([a, b, c], 'msg-1')
    expect(second).toHaveLength(1)
    expect(second[0].id).toBe('msg-1:a')
    expect(second[0].parts).toHaveLength(3)
  })

  it(' rulings 空正文段不渲染也不打断组（视觉相邻即同组）', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'a' }),
      makeText(''),
      makeTool({ toolCallId: 'b' })
    ], 'msg-1')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].parts).toHaveLength(2)
  })
})

describe('批4④ defaultExpanded 判定序（D6 四档短路 + D-C1 + D-C8）', () => {
  it('TS-1⑦ requiresAction 组强制展开：defaultExpanded=true，且 resolveBlockExpanded 手动收起不生效', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'hitl', requiresAction: true, status: 'pending' })], 'msg-1')
    expect(blocks[0].defaultExpanded).toBe(true)
    expect(resolveBlockExpanded(blocks[0], undefined)).toBe(true)
    expect(resolveBlockExpanded(blocks[0], false)).toBe(true)
    expect(resolveBlockExpanded(blocks[0], true)).toBe(true)
  })

  it('TS-1⑧ 成员段 reason 非空 ⇒ 默认展开（D-C1）；允许用户手动收起', () => {
    const reasoned = groupParts([makeTool({ toolCallId: 'r', reason: '取消回灌未送达（网络异常）' })], 'msg-1')
    expect(reasoned[0].defaultExpanded).toBe(true)
    expect(resolveBlockExpanded(reasoned[0], undefined)).toBe(true)
    expect(resolveBlockExpanded(reasoned[0], false)).toBe(false)
    expect(resolveBlockExpanded(reasoned[0], true)).toBe(true)
  })

  it('进行中工具成员（pending/running）⇒ 默认展开（D6③）', () => {
    expect(groupParts([makeTool({ toolCallId: 'p', status: 'pending' })], 'msg-1')[0].defaultExpanded).toBe(true)
    expect(groupParts([makeTool({ toolCallId: 'r', status: 'running' })], 'msg-1')[0].defaultExpanded).toBe(true)
  })

  it('TS-1⑨ 手动展开后不得被自动折叠：defaultExpanded=false 的组，manual=true ⇒ 展开', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'done', status: 'succeeded' })], 'msg-1')
    expect(blocks[0].defaultExpanded).toBe(false)
    expect(resolveBlockExpanded(blocks[0], true)).toBe(true)
  })

  it('TS-1⑩ 手动收起后不得被自动打开：defaultExpanded=true（running）的组，manual=false ⇒ 收起', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'run', status: 'running' })], 'msg-1')
    expect(blocks[0].defaultExpanded).toBe(true)
    expect(resolveBlockExpanded(blocks[0], false)).toBe(false)
  })

  it('判定序短路：requiresAction 优先于 reason/reasoning 折叠档（同组多成员混合时取最高档）', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'hitl', requiresAction: true, status: 'pending' }),
      makeTool({ toolCallId: 'done', status: 'succeeded' })
    ], 'msg-1')
    // D-C9：requiresAction 段独立成组 —— 这里是两个独立组，各自判定
    expect(blocks).toHaveLength(2)
    expect(blocks[0].defaultExpanded).toBe(true)
    expect(blocks[1].defaultExpanded).toBe(false)
  })
})

describe('批4④ D-C9：多 requiresAction 段配对（分组层）', () => {
  it('同一消息 2 个 requiresAction 段（相邻）⇒ 2 个独立 bare 组，defaultExpanded 均 true', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'p1', requiresAction: true, status: 'pending', kind2: 'CONFIRM' }),
      makeTool({ toolCallId: 'p2', requiresAction: true, status: 'pending', kind2: 'CONFIRM' })
    ], 'msg-1')
    expect(blocks).toHaveLength(2)
    expect(blocks.every((b) => b.bare)).toBe(true)
    expect(blocks.every((b) => b.defaultExpanded)).toBe(true)
    expect(blocks.map((b) => b.id).sort()).toEqual(['msg-1:p1', 'msg-1:p2'])
  })
})

describe('批4④ 收口修 F1/F2：跨消息 blockId 命名空间 + requiresAction 翻转键换代（红队 M-1 / m-4）', () => {
  it('F2① 跨消息 reasoning 同 seg ⇒ blockId 不碰撞：消息 A 展开不联动消息 B（expandedBlocks 不串味）', () => {
    // seg 逐消息自 0 起（parts.ts nextSeg）——两消息各持 reasoning@seg:0，修复前 block.id 同为 `reasoning-0`
    const blocksA = groupParts([makeReasoning('想A', { seg: 0 })], 'msg-A')
    const blocksB = groupParts([makeReasoning('想B', { seg: 0 })], 'msg-B')
    expect(blocksA).toHaveLength(1)
    expect(blocksB).toHaveLength(1)
    expect(blocksA[0].id).not.toBe(blocksB[0].id)
    // 串味复现面：消息 A 的展开写入 expandedBlocks 后，消息 B 同 id 组不得被联动展开
    const expandedBlocks: Record<string, boolean> = {}
    expandedBlocks[blocksA[0].id] = true
    expect(resolveBlockExpanded(blocksA[0], expandedBlocks[blocksA[0].id])).toBe(true)
    expect(resolveBlockExpanded(blocksB[0], expandedBlocks[blocksB[0].id])).toBe(false)
  })

  it('F2② requiresAction 翻转 ⇒ 组重并 + 键换代：决议段并入活动组，旧键孤儿、新键回默认展开态', () => {
    const x = makeTool({ toolCallId: 'call_X', status: 'succeeded' })
    const a = makeTool({ toolCallId: 'call_A', requiresAction: true, status: 'pending' })
    const y = makeTool({ toolCallId: 'call_Y', status: 'succeeded' })
    const before = groupParts([x, a, y], 'msg-1')
    // D-C9：requiresAction 段独立成组 ⇒ 3 个 bare 组（红队 m-4 复现形态）
    expect(before.map((b) => b.id)).toEqual(['msg-1:call_X', 'msg-1:call_A', 'msg-1:call_Y'])
    expect(before.every((b) => b.bare)).toBe(true)
    // 决议：requiresAction 翻回 false（终态）⇒ 3 组重并为 1 组，组首 = call_X（键集合整体换代）
    const decided = { ...a, requiresAction: false, status: 'cancelled' as const }
    const after = groupParts([x, decided, y], 'msg-1')
    expect(after).toHaveLength(1)
    expect(after[0].id).toBe('msg-1:call_X')
    expect(after[0].parts.map((p) => (p.kind === 'tool' ? p.toolCallId : p.kind))).toEqual(['call_X', 'call_A', 'call_Y'])
    expect(after[0].defaultExpanded).toBe(false)
    // 键换代语义钉死：决议前用户对 call_A 组的展开记录，对重并后的新组不生效 ⇒ 回默认展开态（收起）
    const expandedBlocks: Record<string, boolean> = { 'msg-1:call_A': true }
    expect(resolveBlockExpanded(after[0], expandedBlocks[after[0].id])).toBe(false)
  })
})

describe('批4④ 组头文案（blockHeader）', () => {
  it('纯 reasoning 组 ⇒ 「思考过程」；含工具组 ⇒ 「工具活动」+ 段数', () => {
    const reasoningGroup = groupParts([makeReasoning('一'), makeReasoning('二')], 'msg-1')
    expect(blockHeader(reasoningGroup[0])).toContain('思考过程')
    const toolGroup = groupParts([makeTool({ toolCallId: 'a' }), makeTool({ toolCallId: 'b' }), makeTool({ toolCallId: 'c' })], 'msg-1')
    expect(blockHeader(toolGroup[0])).toContain('工具活动')
    expect(blockHeader(toolGroup[0])).toContain('3')
  })
})
