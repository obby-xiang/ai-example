/**
 * 仓外可行性实测：用 vitest + jsdom + @vue/test-utils 直接挂载**仓库真实**的
 * `src/components/SpreadGrid.vue`（原样拷贝，未改一行源码），断言 B-07 修复的四条结算路径。
 *
 * 目的：验证"引入 vitest 后，B-06/B-07 这类修复能否入库固化为回归锁"，而不是只看文档。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'

// §1 mock 掉引擎懒加载入口（真实实现会去 import 4.7MB 的 spreadjs chunk）
const h = vi.hoisted(() => {
  const state: { mode: 'pending' | 'resolve' | 'reject'; resolveFn: ((v: unknown) => void) | null; rejectFn: ((e: unknown) => void) | null } = {
    mode: 'pending',
    resolveFn: null,
    rejectFn: null
  }
  return state
})

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () =>
    new Promise((resolve, reject) => {
      if (h.mode === 'resolve') {
        resolve(fakeGc())
        return
      }
      if (h.mode === 'reject') {
        reject(new Error('chunk 拉取失败（模拟网络错误）'))
        return
      }
      h.resolveFn = resolve
      h.rejectFn = reject
    }),
  loadSpreadExcelIO: () => new Promise(() => {})
}))

function makeSheet(): Record<string, unknown> {
  return {
    name: () => 'Sheet1',
    options: { isProtected: false },
    bind: () => {},
    suspendPaint: () => {},
    resumePaint: () => {},
    setRowCount: () => {},
    setColumnCount: () => {},
    setColumnWidth: () => {},
    getRowCount: () => 100,
    getCell: () => ({ formatter: () => {}, backColor: () => {}, validator: () => {} }),
    getColumnCount: () => 1
  }
}

function fakeGc(): unknown {
  const sheet = makeSheet()
  class Workbook {
    fromJSON(): void {}
    toJSON(): string {
      return '{}'
    }
    getActiveSheet(): unknown {
      return sheet
    }
    getSheetCount(): number {
      return 1
    }
    getSheet(): unknown {
      return sheet
    }
    refresh(): void {}
    destroy(): void {}
  }
  return {
    Spread: {
      Sheets: {
        Workbook,
        Events: { CellChanged: 'CellChanged', RangeChanged: 'RangeChanged', RowChanged: 'RowChanged' },
        DataValidation: {},
        ConditionalFormatting: { ComparisonOperators: {} },
        SheetArea: { viewport: 0 }
      }
    }
  }
}

async function freshComponent() {
  vi.resetModules()
  const mod = await import('@/components/SpreadGrid.vue')
  return mod.default
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  h.mode = 'pending'
  h.resolveFn = null
  h.rejectFn = null
})

afterEach(() => {
  vi.useRealTimers()
})

describe('SpreadGrid readyPromise 四条结算路径（B-07）', () => {
  it('① 引擎加载失败：引擎态置 failed、错误可见、loadFromJson 不悬挂而是抛出可读文本', async () => {
    h.mode = 'reject'
    const Comp = await freshComponent()
    const wrapper = mount(Comp)
    await flush()

    expect(wrapper.text()).toContain('表格引擎加载失败')
    expect(wrapper.text()).toContain('chunk 拉取失败')

    // 修复前：readyPromise 永不结算 ⇒ 这里会一直挂着（测试会超时）
    await expect((wrapper.vm as never as { loadFromJson: (j: string, f: unknown[]) => Promise<void> }).loadFromJson('{}', [])).rejects.toThrow(
      /chunk 拉取失败/
    )
    wrapper.unmount()
  })

  it('② 引擎永久挂起：60s 看门狗到点结算并给出超时文案（fake timers 无需真等）', async () => {
    vi.useFakeTimers()
    const Comp = await freshComponent()
    const wrapper = mount(Comp)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toContain('表格引擎加载超时')

    const pending = (wrapper.vm as never as { loadFromJson: (j: string, f: unknown[]) => Promise<void> }).loadFromJson('{}', [])
    const caught = pending.catch((error: unknown) => error) // 先挂 handler，避免 unhandled rejection 噪音
    await vi.advanceTimersByTimeAsync(0)
    await expect(caught).resolves.toMatchObject({ message: expect.stringMatching(/超时/)})
    wrapper.unmount()
  })

  it('③ 卸载竞态（红队 RR1 场景）：宿主消失后等待者被唤醒，不静默悬挂', async () => {
    const Comp = await freshComponent()
    const wrapper = mount(Comp)
    wrapper.unmount() // 引擎仍在 pending 时卸载

    const load = (wrapper.vm as never as { loadFromJson: (j: string, f: unknown[]) => Promise<void> }).loadFromJson('{}', [])
    await expect(load).rejects.toThrow(/卸载|未就绪/)
  })

  it('④ 成功路径：ready 事件发出 + loadFromJson 正常完成（看门狗被清除）', async () => {
    h.mode = 'resolve'
    const Comp = await freshComponent()
    const wrapper = mount(Comp)
    await flush()

    expect(wrapper.emitted('ready')).toBeTruthy()
    await expect((wrapper.vm as never as { loadFromJson: (j: string, f: unknown[]) => Promise<void> }).loadFromJson('{}', [])).resolves.toBeUndefined()
    wrapper.unmount()
  })
})
