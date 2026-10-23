/**
 * 测试扩充 V5 · B12 事件源：`SpreadGrid` 的 `selection-changed` 是"预览计划可能不再匹配"的
 * 唯一信号源（面板/页面据此收口）。这里只锁两件事：
 *
 * 1. 引擎的 `SelectionChanged` 事件被**真的**接到组件 emit 上（不是只写在文档里）；
 * 2. 换算口径与适配器 `selection()` 同源（视口第 0 行是表头 ⇒ 数据行 = 视口行 − 1）。
 *
 * 不测程序化载入期间的抑制（`restoring` 是组件内部态，需要整套 SpreadJS 行为才能触发；
 * 属真引擎面，留给批③浏览器验收）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const capture = vi.hoisted(() => ({
  handlers: new Map<string, (event?: unknown) => void>(),
  selection: [{ row: 2, rowCount: 3, col: 1, colCount: 2 }] as Array<{ row: number; rowCount: number; col: number; colCount: number }>
}))

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () => Promise.resolve(fakeGc()),
  loadSpreadExcelIO: () => new Promise(() => {})
}))

function fakeSheet(): Record<string, unknown> {
  return {
    name: () => 'Sheet1',
    options: { isProtected: false },
    bind: (event: string, handler: (payload?: unknown) => void) => {
      capture.handlers.set(event, handler)
    },
    suspendPaint: () => {},
    resumePaint: () => {},
    setRowCount: () => {},
    setColumnCount: () => {},
    setColumnWidth: () => {},
    getRowCount: () => 100,
    getColumnCount: () => 5,
    getCell: () => ({ formatter: () => {}, backColor: () => {}, validator: () => {} }),
    getValue: () => '',
    getSelections: () => capture.selection
  }
}

function fakeGc(): unknown {
  const sheet = fakeSheet()
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
        Events: {
          CellChanged: 'CellChanged',
          RangeChanged: 'RangeChanged',
          RowChanged: 'RowChanged',
          SelectionChanged: 'SelectionChanged'
        },
        DataValidation: {},
        ConditionalFormatting: { ComparisonOperators: {} },
        SheetArea: { viewport: 0 }
      }
    }
  }
}

beforeEach(() => {
  vi.resetModules()
  capture.handlers.clear()
  capture.selection = [{ row: 2, rowCount: 3, col: 1, colCount: 2 }]
})

describe('SpreadGrid selection-changed（B12 事件源）', () => {
  it('引擎 SelectionChanged ⇒ emit 数据面选区（视口行 − 1）', async () => {
    const { default: SpreadGrid } = await import('@/components/SpreadGrid.vue')
    const wrapper = mount(SpreadGrid, { props: { defCode: 'DEMO' } })
    await new Promise((resolve) => setTimeout(resolve, 0))

    const handler = capture.handlers.get('SelectionChanged')
    expect(handler).toBeTypeOf('function')
    handler!(undefined)
    await wrapper.vm.$nextTick()

    const events = wrapper.emitted('selection-changed')
    expect(events).toHaveLength(1)
    expect(events![0]![0]).toEqual({ row: 1, col: 1, rowCount: 3, colCount: 2 })
    wrapper.unmount()
  })

  it('选区被清空 ⇒ emit null（不吞事件，让上游能收口）', async () => {
    capture.selection = []
    const { default: SpreadGrid } = await import('@/components/SpreadGrid.vue')
    const wrapper = mount(SpreadGrid, { props: { defCode: 'DEMO' } })
    await new Promise((resolve) => setTimeout(resolve, 0))

    capture.handlers.get('SelectionChanged')!(undefined)
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('selection-changed')![0]![0]).toBeNull()
    wrapper.unmount()
  })
})
