/**
 * 批次7B（P0-2 / AR-15 生命周期修复）：`ToolPart` 的 args 解析与派生值 **WeakMap 缓存**。
 *
 * <p>渲染面同一帧会多次向同一个段要「参数行 / 表单定义 / 卡体是否有行 / 提交摘要 / 一行摘要」，
 * 旧实现每次都 `JSON.parse(part.args)`（blocks.ts 与 tool-summaries.ts 各一份），长会话里同一帧
 * 重复解析数十次。本文件锁两件事：
 * <ol>
 * <li><b>同段同 args 只解析一次</b>：跨 blocks / tool-summaries 共用同一份缓存；</li>
 * <li><b>args 变更即失效</b>：原地改 args（旧写点形态）与帧路径换新段对象都能拿到新值，不误命中。</li>
 * </ol>
 *
 * 只断言「解析次数」与派生结果，不锁缓存实现（WeakMap / 单槽都不影响断言）。
 */
import { describe, expect, it, vi } from 'vitest'
import type { ToolPart } from '@/types/ai'
import { argRows, formSpecOf, hasToolBody } from '@/utils/blocks'
import { toolSummary } from '@/utils/tool-summaries'

let seq = 0

function makeTool(overrides: Partial<ToolPart> = {}): ToolPart {
  seq += 1
  return {
    kind: 'tool',
    seg: seq,
    toolCallId: `tc-cache-${seq}`,
    name: 'get_row_count',
    kind2: 'BACKEND',
    status: 'succeeded',
    requiresAction: false,
    ...overrides
  } as ToolPart
}

const FORM_ARGS = JSON.stringify({
  defCode: 'CUR',
  form: {
    scenario: 'probe',
    fields: [{ key: 'currency', label: '币种', type: 'enum', options: [{ value: 'CNY', label: '人民币' }] }]
  }
})

describe('批次7B · ToolPart 派生缓存', () => {
  it('同一段的多条渲染路径合计只解析一次；args 变更新段才重算', () => {
    const part = makeTool({ args: FORM_ARGS, submittedValues: { currency: 'CNY' } })
    const parse = vi.spyOn(JSON, 'parse')
    const before = parse.mock.calls.length

    // 一帧内的真实调用面：字段表（普通 + 门卡掩码）、表单定义、卡体判据、一行摘要。
    expect(argRows(part)).toHaveLength(2)
    expect(argRows(part, true)).toHaveLength(2)
    expect(formSpecOf(part)?.fields.map((field) => field.key)).toEqual(['currency'])
    expect(hasToolBody(part)).toBe(true)
    expect(toolSummary(part)).toContain('预估数据行数（CUR）')

    expect(parse.mock.calls.length - before).toBe(1)

    // 再取一轮（下一帧同段）：缓存命中，不新增解析。
    expect(argRows(part)).toHaveLength(2)
    expect(toolSummary(part)).toContain('预估数据行数（CUR）')
    expect(parse.mock.calls.length - before).toBe(1)

    // 原地改 args（历史存量写点形态）⇒ 失效一次，且后续派生共用这次解析。
    part.args = JSON.stringify({ beta: 'y' })
    expect(argRows(part)).toEqual([{ key: 'beta', value: 'y' }])
    expect(formSpecOf(part)).toBeNull()
    expect(parse.mock.calls.length - before).toBe(2)

    // 换一个段对象（帧路径 upsert 的形态）⇒ 各自解析一次，不串缓存。
    const next = makeTool({ args: JSON.stringify({ gamma: 'z' }) })
    expect(argRows(next)).toEqual([{ key: 'gamma', value: 'z' }])
    expect(parse.mock.calls.length - before).toBe(3)

    parse.mockRestore()
  })
})
