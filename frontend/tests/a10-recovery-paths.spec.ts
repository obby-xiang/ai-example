/**
 * A10 恢复路径（Y-55 / Y-56 / Y-57 / Y-58）回归锁。
 *
 * <p>四条都是"错误之后无路可走"：错误态必须给**可点击的重试**，且重试真的重跑失败那一步。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { applyLocale } from '@/locales'

const api = vi.hoisted(() => ({
  feedbackList: vi.fn(),
  health: vi.fn(),
  listProviders: vi.fn(),
  getDefaults: vi.fn(),
  listDefinitions: vi.fn(),
  listData: vi.fn(),
  listRegions: vi.fn(),
  listProjects: vi.fn()
}))

const spread = vi.hoisted(() => ({
  mode: 'reject' as 'reject' | 'resolve' | 'pending',
  resolveFn: null as ((value: unknown) => void) | null
}))

vi.mock('@/api/ai', () => ({ aiApi: { feedbackList: api.feedbackList, health: api.health } }))
vi.mock('@/api/aiConfig', () => ({
  aiConfigApi: {
    listProviders: api.listProviders,
    getDefaults: api.getDefaults,
    updateProvider: vi.fn(),
    createProvider: vi.fn(),
    deleteProvider: vi.fn(),
    testProvider: vi.fn(),
    fetchModels: vi.fn(),
    addModel: vi.fn(),
    updateModel: vi.fn(),
    deleteModel: vi.fn(),
    updateDefaults: vi.fn()
  }
}))
vi.mock('@/api/definitions', () => ({ definitionsApi: { listDefinitions: api.listDefinitions } }))
vi.mock('@/api/data', () => ({ dataApi: { listData: api.listData, countData: vi.fn() } }))
vi.mock('@/api/masterdata', () => ({
  masterDataApi: { listRegions: api.listRegions, listProjects: api.listProjects }
}))
vi.mock('@/stores/workspace', () => ({ useWorkspaceStore: () => ({ enterPage: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/router', () => ({ ROUTE_NAMES: {}, default: { push: vi.fn() } }))
vi.mock('@/components/wizard/ConditionForm.vue', () => ({
  default: defineComponent({ name: 'ConditionForm', template: '<div />' })
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { confirm: vi.fn() }
  }
})

vi.mock('@/utils/spreadjs', () => ({
  loadSpreadCore: () => {
    if (spread.mode === 'reject') {
      return Promise.reject(new Error('chunk down'))
    }
    if (spread.mode === 'pending') {
      return new Promise((resolve) => {
        spread.resolveFn = resolve
      })
    }
    return Promise.resolve(fakeGc())
  },
  loadSpreadExcelIO: () => new Promise(() => {})
}))

import AiFeedbackView from '@/views/AiFeedbackView.vue'
import AiSettingsView from '@/views/AiSettingsView.vue'
import DataBrowserView from '@/views/DataBrowserView.vue'
import SpreadGrid from '@/components/SpreadGrid.vue'

const ButtonStub = defineComponent({ template: '<button v-bind="$attrs"><slot /></button>' })

const stubs = {
  'el-alert': defineComponent({
    props: { title: { type: String, default: '' } },
    template: '<div class="alert-stub">{{ title }}<slot /></div>'
  }),
  'el-button': ButtonStub,
  'el-empty': true,
  'el-table': true,
  'el-table-column': true,
  'el-tag': true,
  'el-select': true,
  'el-option': true,
  'el-card': defineComponent({ template: '<div><slot /></div>' }),
  'el-form': true,
  'el-form-item': true,
  'el-input': true,
  'el-input-number': true,
  'el-switch': true,
  'el-dialog': true,
  'el-divider': true,
  'el-pagination': true
}

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
    getColumnCount: () => 1,
    getSelections: () => []
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

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  applyLocale('zh-CN')
  Object.values(api).forEach((fn) => fn.mockReset())
  api.health.mockResolvedValue({ available: true, code: 'AI_AVAILABLE', providers: [] })
  api.getDefaults.mockResolvedValue({
    modelId: 'deepseek-flash',
    temperature: 0.3,
    topP: 0.9,
    maxTokens: 1024,
    allowUserParams: true,
    version: 4
  })
  api.listRegions.mockResolvedValue([])
  api.listProjects.mockResolvedValue([])
  api.listData.mockResolvedValue({ content: [], totalElements: 0 })
  spread.mode = 'reject'
  spread.resolveFn = null
})

describe('Y-55 AI 反馈页错误态可重试，兜底中文走 locale', () => {
  it('失败后出现「重试」；点击重跑加载并渲染表格', async () => {
    api.feedbackList
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce([
        {
          messageId: 'm-1',
          sessionId: 's-1',
          rating: 1,
          reason: null,
          excerpt: 'hello',
          updatedAt: '2026-10-24T10:00:00'
        }
      ])

    const wrapper = mount(AiFeedbackView, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('.alert-stub').exists()).toBe(true)

    await wrapper.find('[data-testid="ai-feedback-retry"]').trigger('click')
    await flushPromises()

    expect(api.feedbackList).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="ai-feedback-table"]').exists()).toBe(true)
    expect(wrapper.find('.alert-stub').exists()).toBe(false)
    wrapper.unmount()
  })

  it('非 Error 兜底在英文界面不出现中文', async () => {
    applyLocale('en-US')
    api.feedbackList.mockRejectedValueOnce('plain failure')
    const wrapper = mount(AiFeedbackView, { global: { stubs } })
    await flushPromises()

    const text = wrapper.find('.alert-stub').text()
    expect(text).toContain('Unknown error')
    expect(text).not.toMatch(/[\u4e00-\u9fff]/)
    wrapper.unmount()
  })
})

describe('Y-56 AI 设置页错误块复用已备的 T.retry', () => {
  it('错误块内有「重试」；点击后重跑 load 并恢复三段式', async () => {
    api.listProviders
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce([
        {
          id: 7,
          code: 'deepseek',
          displayName: 'DeepSeek',
          protocol: 'OPENAI_COMPAT',
          baseUrl: 'https://api.deepseek.com',
          completionsPath: '/chat/completions',
          modelsPath: '/models',
          keyConfigured: true,
          keyLast4: 'ab12',
          keyRef: 'DB',
          keyStatus: 'OK',
          source: 'DB',
          enabled: true,
          models: []
        }
      ])

    const wrapper = mount(AiSettingsView, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="ai-settings-error"]').exists()).toBe(true)

    await wrapper.find('[data-testid="ai-settings-retry"]').trigger('click')
    await flushPromises()

    expect(api.listProviders).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="ai-settings-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="provider-section"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('Y-58 数据浏览页刷新同时重拉定义', () => {
  it('定义首次加载失败后，点右上刷新会重新拉定义并恢复选择器', async () => {
    api.listDefinitions
      .mockRejectedValueOnce(new Error('defs down'))
      .mockResolvedValueOnce([{ code: 'DEMO', name: '演示配置', level: 'GLOBAL', fields: [] }])

    const wrapper = mount(DataBrowserView, { global: { stubs } })
    await flushPromises()
    expect(api.listDefinitions).toHaveBeenCalledTimes(1)
    expect(api.listData).not.toHaveBeenCalled()

    await wrapper.find('button[title="刷新"]').trigger('click')
    await flushPromises()

    expect(api.listDefinitions).toHaveBeenCalledTimes(2)
    expect(api.listData).toHaveBeenCalledWith('DEMO', expect.anything())
    wrapper.unmount()
  })
})

describe('C-1 数据浏览查询/刷新不得重置范围、条件与页码', () => {
  function dataBrowserState(wrapper: ReturnType<typeof mount>): Record<string, unknown> {
    return (wrapper.vm as unknown as { $: { setupState: Record<string, unknown> } }).$.setupState
  }

  async function mountRegionBrowser(): Promise<ReturnType<typeof mount>> {
    api.listDefinitions.mockResolvedValue([{ code: 'REGION', name: '地区', level: 'REGION', fields: [] }])
    const wrapper = mount(DataBrowserView, { global: { stubs } })
    await flushPromises()
    return wrapper
  }

  function applyUserFilters(wrapper: ReturnType<typeof mount>): void {
    const state = dataBrowserState(wrapper)
    state.scopeKey = 'R-1'
    state.draftModel = {
      scopeKeys: [],
      fields: { code: { operator: 'EQ', value: 'A', value2: '' } }
    }
    state.page = 1
  }

  function lastDataParams(): Record<string, unknown> {
    const call = api.listData.mock.calls.at(-1)
    expect(call).toBeDefined()
    return (call?.[1] ?? {}) as Record<string, unknown>
  }

  it('点「查询」只按当前筛选拉数据：不清空 REGION / 条件 / 页码，且不重复拉定义', async () => {
    const wrapper = await mountRegionBrowser()
    applyUserFilters(wrapper)
    const before = wrapper.text()

    const query = wrapper.findAll('button').find((button) => button.text() === '查询')
    expect(query).toBeDefined()
    await query!.trigger('click')
    await flushPromises()

    expect(api.listDefinitions).toHaveBeenCalledTimes(1)
    expect(lastDataParams()).toMatchObject({
      page: 1,
      scopeType: 'REGION',
      scopeKey: 'R-1',
      conditions: expect.stringContaining('"operator":"EQ"')
    })
    expect(wrapper.text()).toBe(before)
    wrapper.unmount()
  })

  it('点「刷新」重拉定义但保留 REGION / 条件 / 页码', async () => {
    const wrapper = await mountRegionBrowser()
    applyUserFilters(wrapper)

    await wrapper.find('button[title="刷新"]').trigger('click')
    await flushPromises()

    expect(api.listDefinitions).toHaveBeenCalledTimes(2)
    expect(lastDataParams()).toMatchObject({
      page: 1,
      scopeType: 'REGION',
      scopeKey: 'R-1',
      conditions: expect.stringContaining('"operator":"EQ"')
    })
    wrapper.unmount()
  })

  it('定义刷新失败时保留当前筛选并仍按原参数请求数据（失败语义不回退）', async () => {
    const wrapper = await mountRegionBrowser()
    applyUserFilters(wrapper)
    api.listDefinitions.mockRejectedValueOnce(new Error('defs down'))

    await wrapper.find('button[title="刷新"]').trigger('click')
    await flushPromises()

    expect(lastDataParams()).toMatchObject({
      page: 1,
      scopeType: 'REGION',
      scopeKey: 'R-1',
      conditions: expect.stringContaining('"operator":"EQ"')
    })
    wrapper.unmount()
  })

  it('重置条件仍是显式重置：清空范围键/条件并回第一页，不额外拉定义', async () => {
    const wrapper = await mountRegionBrowser()
    applyUserFilters(wrapper)

    const reset = wrapper.findAll('button').find((button) => button.text() === '重置条件')
    expect(reset).toBeDefined()
    await reset!.trigger('click')
    await flushPromises()

    const state = dataBrowserState(wrapper)
    expect(state.scopeKey).toBe('')
    expect(state.page).toBe(0)
    expect(state.draftModel).toEqual({ scopeKeys: [], fields: {} })
    expect(api.listDefinitions).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

describe('Y-57 表格引擎失败可重试并复位状态', () => {
  it('失败文案随 locale：英文界面不出现中文错误 + 英文按钮混排', async () => {
    applyLocale('en-US')
    const wrapper = mount(SpreadGrid)
    await flush()

    const text = wrapper.text()
    expect(text).toContain('Failed to load the spreadsheet engine')
    expect(text).toContain('Reload spreadsheet')
    expect(text).not.toMatch(/[\u4e00-\u9fff]/)
    wrapper.unmount()
  })

  it('超时文案随 locale：英文界面不出现中文错误 + 英文按钮混排', async () => {
    vi.useFakeTimers()
    try {
      applyLocale('en-US')
      spread.mode = 'pending'
      const wrapper = mount(SpreadGrid)
      await vi.advanceTimersByTimeAsync(60_000)

      const text = wrapper.text()
      expect(text).toContain('timed out')
      expect(text).toContain('Reload spreadsheet')
      expect(text).not.toMatch(/[\u4e00-\u9fff]/)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('失败态给「重新加载表格」；点击重跑 loadSpreadCore 后恢复 ready', async () => {
    const wrapper = mount(SpreadGrid)
    await flush()
    expect(wrapper.text()).toContain('表格引擎加载失败')
    expect(wrapper.find('[data-testid="spread-engine-retry"]').exists()).toBe(true)
    expect(wrapper.emitted('ready')).toBeFalsy()

    spread.mode = 'resolve'
    await wrapper.find('[data-testid="spread-engine-retry"]').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('[data-testid="spread-engine-retry"]').exists()).toBe(false)
    expect(wrapper.emitted('ready')).toHaveLength(1)
    wrapper.unmount()
  })
})
