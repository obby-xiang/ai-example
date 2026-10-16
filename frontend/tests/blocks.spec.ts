/**
 * 对话时序包 批4④（A5' 折叠规则本体）→ R-154 #13+14（用户直裁「彻底拆除」组壳）：`utils/blocks.ts` 纯函数锁。
 *
 * R-154 后口径（裁决依据 = 用户直裁，非调研建议）：
 * - groupParts 每段恒成一个 Block（恒 1 段），不再相邻合并成组；`bare` 概念与 blockHeader 随壳退役；
 * - defaultExpanded 重定义：唯 requiresAction 强制展开，其余（reason 非空 / pending / running / reasoning）一律默认折叠；
 * - resolveBlockExpanded 三态不变：requiresAction 强制展开 > manual 显式值 > defaultExpanded。
 */
import { describe, expect, it } from 'vitest'
import type { MessagePart, ReasoningPart, ToolPart } from '@/types/ai'
import {
  MASKED_VALUE,
  argRows,
  groupParts,
  hasReadOnlyBody,
  hasToolBody,
  isReadOnlyCard,
  parsePublishConflicts,
  readableValue,
  reasonHeadline,
  resolveBlockExpanded
} from '@/utils/blocks'

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

describe("R-154 #13+14 groupParts：每段一卡（组壳拆除）", () => {
  it('TS-1① [text, tool, tool, text] ⇒ 正文 + 两个独立单卡块 + 正文，段序与块序一致', () => {
    const t1 = makeText('前文')
    const a = makeTool({ toolCallId: 'a' })
    const b = makeTool({ toolCallId: 'b' })
    const t2 = makeText('后文')
    const blocks = groupParts([t1, a, b, t2], 'msg-1')
    expect(blocks.map((block) => block.kind)).toEqual(['text', 'activity', 'activity', 'text'])
    expect(blocks[1].parts.map((p) => (p.kind === 'tool' ? p.toolCallId : p.kind))).toEqual(['a'])
    expect(blocks[2].parts.map((p) => (p.kind === 'tool' ? p.toolCallId : p.kind))).toEqual(['b'])
    // 每块恒 1 段
    expect(blocks.every((block) => block.parts.length === 1)).toBe(true)
  })

  it('TS-1② [tool, text, tool] ⇒ 被正文隔开的工具各成一卡（两个 activity 块）', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'a' }), makeText('隔断'), makeTool({ toolCallId: 'b' })], 'msg-1')
    expect(blocks.filter((b) => b.kind === 'activity')).toHaveLength(2)
    expect(blocks.map((b) => b.kind)).toEqual(['activity', 'text', 'activity'])
  })

  it('TS-1③ [tool] 单段 ⇒ 恰好一个 activity 块（单卡不再区分 bare/组壳）', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'solo' })], 'msg-1')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].kind).toBe('activity')
    expect(blocks[0].parts).toHaveLength(1)
  })

  it('TS-1④ [reasoning, reasoning] ⇒ 两段各自成卡且均默认折叠（R-154：reasoning 不再成组）', () => {
    const blocks = groupParts([makeReasoning('想一'), makeReasoning('想二')], 'msg-1')
    expect(blocks).toHaveLength(2)
    expect(blocks.every((b) => b.defaultExpanded === false)).toBe(true)
    expect(blocks[0].id).toBe('msg-1:reasoning-' + blocks[0].parts[0].seg)
  })

  it("TS-1⑤ 任何 Block 成员 kind !== 'text'（正文永不进 activity 块；相邻混合段各成单卡）", () => {
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
    // R-154：tool/reasoning/tool 不再合并 —— 三个独立单卡块，序保持
    const activity = blocks.filter((b) => b.kind === 'activity')
    expect(activity).toHaveLength(3)
    expect(activity.map((b) => b.parts[0].kind)).toEqual(['tool', 'reasoning', 'tool'])
  })

  it('TS-1⑥ blockId 稳定性：新增相邻工具段后既有卡 id 不变（D5：业务稳定 id，禁下标）', () => {
    const a = makeTool({ toolCallId: 'a', status: 'running' })
    const b = makeTool({ toolCallId: 'b' })
    const first = groupParts([a, b], 'msg-1')
    expect(first.map((block) => block.id)).toEqual(['msg-1:a', 'msg-1:b'])
    // 后续帧又来了第三个工具（位置相邻）——前两卡 id 不变，新卡取自己成员 id
    const c = makeTool({ toolCallId: 'c' })
    const second = groupParts([a, b, c], 'msg-1')
    expect(second.map((block) => block.id)).toEqual(['msg-1:a', 'msg-1:b', 'msg-1:c'])
  })

  it(' rulings 空正文段不渲染也不成块（相邻工具各成一卡）', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'a' }),
      makeText(''),
      makeTool({ toolCallId: 'b' })
    ], 'msg-1')
    expect(blocks).toHaveLength(2)
    expect(blocks.map((block) => block.parts[0].kind)).toEqual(['tool', 'tool'])
  })
})

describe('R-154 defaultExpanded 重定义（唯 requiresAction 强制展开，其余默认折叠）', () => {
  it('TS-1⑦ requiresAction 卡强制展开：defaultExpanded=true，且 resolveBlockExpanded 手动收起不生效', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'hitl', requiresAction: true, status: 'pending' })], 'msg-1')
    expect(blocks[0].defaultExpanded).toBe(true)
    expect(resolveBlockExpanded(blocks[0], undefined)).toBe(true)
    expect(resolveBlockExpanded(blocks[0], false)).toBe(true)
    expect(resolveBlockExpanded(blocks[0], true)).toBe(true)
  })

  it('R-154：成员段 reason 非空 ⇒ 仍默认折叠（原 D-C1 档随四档判定序退役）；手动展开可达', () => {
    const reasoned = groupParts([makeTool({ toolCallId: 'r', reason: '取消回灌未送达（网络异常）' })], 'msg-1')
    expect(reasoned[0].defaultExpanded).toBe(false)
    expect(resolveBlockExpanded(reasoned[0], undefined)).toBe(false)
    expect(resolveBlockExpanded(reasoned[0], true)).toBe(true)
  })

  it('R-154：进行中工具段（pending/running）⇒ 默认折叠（状态 tag 在折叠单行即可辨识）', () => {
    expect(groupParts([makeTool({ toolCallId: 'p', status: 'pending' })], 'msg-1')[0].defaultExpanded).toBe(false)
    expect(groupParts([makeTool({ toolCallId: 'r', status: 'running' })], 'msg-1')[0].defaultExpanded).toBe(false)
  })

  it('TS-1⑨ 手动展开后不得被自动折叠：defaultExpanded=false 的卡，manual=true ⇒ 展开', () => {
    const blocks = groupParts([makeTool({ toolCallId: 'done', status: 'succeeded' })], 'msg-1')
    expect(blocks[0].defaultExpanded).toBe(false)
    expect(resolveBlockExpanded(blocks[0], true)).toBe(true)
  })

  it('TS-1⑩ 手动收起后不得被自动打开：requiresAction 卡手动收起不生效；普通卡 manual=false ⇒ 收起', () => {
    const hitl = groupParts([makeTool({ toolCallId: 'hitl', requiresAction: true, status: 'pending' })], 'msg-1')[0]
    expect(resolveBlockExpanded(hitl, false)).toBe(true)
    const run = groupParts([makeTool({ toolCallId: 'run', status: 'running' })], 'msg-1')[0]
    expect(resolveBlockExpanded(run, false)).toBe(false)
  })

  it('判定序：requiresAction 卡与相邻终态卡各自独立成卡、各自判定（不再合并取最高档）', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'hitl', requiresAction: true, status: 'pending' }),
      makeTool({ toolCallId: 'done', status: 'succeeded' })
    ], 'msg-1')
    // R-154：每段一卡 —— 两张独立卡，requiresAction 强制展开、终态卡默认折叠
    expect(blocks).toHaveLength(2)
    expect(blocks[0].defaultExpanded).toBe(true)
    expect(blocks[1].defaultExpanded).toBe(false)
  })
})

describe('R-154 D-C9 兼容面：多 requiresAction 段（每段天然独立成卡）', () => {
  it('同一消息 2 个 requiresAction 段（相邻）⇒ 2 张独立卡，均强制展开', () => {
    const blocks = groupParts([
      makeTool({ toolCallId: 'p1', requiresAction: true, status: 'pending', kind2: 'CONFIRM' }),
      makeTool({ toolCallId: 'p2', requiresAction: true, status: 'pending', kind2: 'CONFIRM' })
    ], 'msg-1')
    expect(blocks).toHaveLength(2)
    expect(blocks.every((b) => b.parts.length === 1)).toBe(true)
    expect(blocks.every((b) => b.defaultExpanded)).toBe(true)
    expect(blocks.map((b) => b.id).sort()).toEqual(['msg-1:p1', 'msg-1:p2'])
  })
})

describe('R-154 收口修 F1/F2 兼容面：跨消息 blockId 命名空间 + 单卡键稳定（红队 M-1 / m-4）', () => {
  it('F2① 跨消息 reasoning 同 seg ⇒ blockId 不碰撞：消息 A 展开不联动消息 B（expandedBlocks 不串味）', () => {
    // seg 逐消息自 0 起（parts.ts nextSeg）——两消息各持 reasoning@seg:0，block.id 靠消息命名空间前缀区分
    const blocksA = groupParts([makeReasoning('想A', { seg: 0 })], 'msg-A')
    const blocksB = groupParts([makeReasoning('想B', { seg: 0 })], 'msg-B')
    expect(blocksA).toHaveLength(1)
    expect(blocksB).toHaveLength(1)
    expect(blocksA[0].id).not.toBe(blocksB[0].id)
    // 串味复现面：消息 A 的展开写入 expandedBlocks 后，消息 B 同 seg 卡不得被联动展开
    const expandedBlocks: Record<string, boolean> = {}
    expandedBlocks[blocksA[0].id] = true
    expect(resolveBlockExpanded(blocksA[0], expandedBlocks[blocksA[0].id])).toBe(true)
    expect(resolveBlockExpanded(blocksB[0], expandedBlocks[blocksB[0].id])).toBe(false)
  })

  it('F2② 改判（R-154 单卡化）：requiresAction 翻转前后均为独立单卡，blockId 稳定（不再重并换代）', () => {
    const x = makeTool({ toolCallId: 'call_X', status: 'succeeded' })
    const a = makeTool({ toolCallId: 'call_A', requiresAction: true, status: 'pending' })
    const y = makeTool({ toolCallId: 'call_Y', status: 'succeeded' })
    const before = groupParts([x, a, y], 'msg-1')
    // 每段一卡：3 张独立卡，id = 各成员 toolCallId
    expect(before.map((b) => b.id)).toEqual(['msg-1:call_X', 'msg-1:call_A', 'msg-1:call_Y'])
    // 决议：requiresAction 翻回 false（终态）⇒ 卡集合与 id 均不变（单卡无重并）；该卡回落默认折叠
    const decided = { ...a, requiresAction: false, status: 'cancelled' as const }
    const after = groupParts([x, decided, y], 'msg-1')
    expect(after.map((b) => b.id)).toEqual(['msg-1:call_X', 'msg-1:call_A', 'msg-1:call_Y'])
    const decidedBlock = after.find((b) => b.id === 'msg-1:call_A')!
    expect(decidedBlock.defaultExpanded).toBe(false)
    expect(resolveBlockExpanded(decidedBlock, undefined)).toBe(false)
    // 手动展开记录随稳定 id 存续：决议前用户对 call_A 卡的展开，决议后仍生效（与旧组壳键换代语义不同，单卡化后的确定性口径）
    expect(resolveBlockExpanded(decidedBlock, true)).toBe(true)
  })
})

describe('S6 批1：回执独立块退役（groupParts 不再派生第二块）+ 只读态/行数判据', () => {
  it('已决 CONFIRM 段 ⇒ 恒一张 activity 卡（receipt 块整体删除，只读态由该卡承担）', () => {
    const decided = makeTool({ toolCallId: 'p1', kind2: 'CONFIRM', status: 'succeeded', requiresAction: false, decision: 'approved' })
    const blocks = groupParts([decided], 'm-1')
    expect(blocks.map((b) => b.id)).toEqual(['m-1:p1'])
    expect(blocks.map((b) => b.kind)).toEqual(['activity'])
    // 已决段默认折叠（卡头恒显行承载徽标/摘要；展开体走三态判定）
    expect(blocks[0]!.defaultExpanded).toBe(false)
    expect(isReadOnlyCard(decided)).toBe(true)
  })

  it('负例：BACKEND 段 / 待决 CONFIRM 段（requiresAction）⇒ 不是只读态卡', () => {
    const backend = makeTool({ toolCallId: 'b1' })
    expect(groupParts([backend], 'm-1').map((b) => b.kind)).toEqual(['activity'])
    expect(isReadOnlyCard(backend)).toBe(false)
    const pending = makeTool({ toolCallId: 'p1', kind2: 'CONFIRM', status: 'pending', requiresAction: true })
    expect(groupParts([pending], 'm-1').map((b) => b.kind)).toEqual(['activity'])
    expect(isReadOnlyCard(pending)).toBe(false)
  })

  it('kind2 名兜底仅 GF：kind2=UNKNOWN 的 generative_form 按名认只读态；其余 UNKNOWN 工具不认', () => {
    const gf = makeTool({ toolCallId: 'g1', name: 'generative_form', kind2: 'UNKNOWN', status: 'succeeded' })
    expect(groupParts([gf], 'm-1').map((b) => b.id)).toEqual(['m-1:g1'])
    expect(isReadOnlyCard(gf)).toBe(true)
    const other = makeTool({ toolCallId: 'x1', kind2: 'UNKNOWN', status: 'succeeded' })
    expect(groupParts([other], 'm-1').map((b) => b.id)).toEqual(['m-1:x1'])
    expect(isReadOnlyCard(other)).toBe(false)
  })

  it('hasBodyRows：0 行卡不给入口（两种空都算空）；requiresAction 例外恒 true', () => {
    // args 的 '{}' / 空串 / 全空白 + result/reason 全空 ⇒ false
    for (const args of ['{}', '', '   ', 'not-json{']) {
      expect(hasToolBody(makeTool({ toolCallId: 'e1', args, result: '', reason: '  ' }))).toBe(false)
    }
    // 任一非空 ⇒ true
    expect(hasToolBody(makeTool({ toolCallId: 'e2', args: '{"taskId":1}' }))).toBe(true)
    expect(hasToolBody(makeTool({ toolCallId: 'e3', args: '{}', result: 'ok' }))).toBe(true)
    expect(hasToolBody(makeTool({ toolCallId: 'e4', args: '{}', reason: '失败' }))).toBe(true)
    // requiresAction 待动作卡例外（门卡 / GF 强制展开）
    expect(hasToolBody(makeTool({ toolCallId: 'e5', kind2: 'CONFIRM', status: 'pending', requiresAction: true, args: '{}' }))).toBe(true)
    // 只读态卡走只读表判据
    expect(hasReadOnlyBody(makeTool({ toolCallId: 'e6', kind2: 'CONFIRM', status: 'succeeded', requiresAction: false, args: '{}' }))).toBe(false)
    expect(hasReadOnlyBody(makeTool({ toolCallId: 'e7', kind2: 'CONFIRM', status: 'succeeded', requiresAction: false, args: '{"taskId":1}' }))).toBe(true)
  })

  it('值可读化：布尔 / 数组 / 空值 / 文件路径 / 对象 / 敏感键掩码（门卡口径）', () => {
    expect(readableValue(true)).toBe('是')
    expect(readableValue(false)).toBe('否')
    expect(readableValue(['A', 'B'])).toBe('A、B')
    expect(readableValue([])).toBe('（空）')
    expect(readableValue(null)).toBe('（空）')
    expect(readableValue('')).toBe('（空）')
    expect(readableValue('/srv/app/exports/report.json')).toBe('report.json')
    expect(readableValue('/srv/app/src')).toBe('/srv/app/src') // 无扩展名 ⇒ 路径原样
    expect(readableValue({ a: 1 })).toBe('（复杂值）')
    // 门卡：敏感键掩码（定长圆点，不泄值不泄长度）；普通键照常
    expect(argRows('{"password":"p@ss","apiKey":"k-123","taskId":7}', true)).toEqual([
      { key: 'password', value: MASKED_VALUE },
      { key: 'apiKey', value: MASKED_VALUE },
      { key: 'taskId', value: '7' }
    ])
    expect(argRows('{"password":"p@ss"}', false)).toEqual([{ key: 'password', value: 'p@ss' }])
  })

  it('parsePublishConflicts / reasonHeadline：明细行解析成表格数据，首行为摘要', () => {
    const reason = [
      '发布作业 #317 校验冲突明细（共 2 处）',
      '1. CURRENCY#GLOBAL：币种精度与主数据不一致',
      '2. TAX_RULE#PRJ-07：税率区间重叠'
    ].join('\n')
    expect(reasonHeadline(reason)).toBe('发布作业 #317 校验冲突明细（共 2 处）')
    expect(parsePublishConflicts(reason)).toEqual([
      { defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' },
      { defCode: 'TAX_RULE', rowKey: 'PRJ-07', message: '税率区间重叠' }
    ])
    expect(parsePublishConflicts('作业冲突：请先取消在途作业')).toEqual([])
    expect(parsePublishConflicts(undefined)).toEqual([])
  })
})
