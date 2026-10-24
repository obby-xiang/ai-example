/**
 * A10 文案收口（R-01 / R-02 / R-03 / R-06 / R-07）回归锁。
 *
 * <p>目标不是"文案好看"，而是**换语言后用户可见面真的换掉**：
 * - R-01 Definitions 的取消按钮不得渲染裸 key；
 * - R-02 JobProgress 的状态/严重度/进度/通道提示四块全走 locale；
 * - R-03 TaskWizard 的默认按钮与任务状态标签走 locale；
 * - R-06 HTTP 兜底不把 VITE_API_BASE / HTTP 码交给用户，技术细节只进 console；
 * - R-07 不可撤销确认框不再覆盖 Element Plus 的确定/取消。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { AxiosError } from 'axios'
import { applyLocale } from '@/locales'
import type { Job, ValidationIssue } from '@/types/job'

const defsApi = vi.hoisted(() => ({ listDefinitions: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/definitions', () => ({ definitionsApi: defsApi }))
vi.mock('@/utils/excel', () => ({ downloadBlob: vi.fn() }))
vi.mock('@/stores/workspace', () => ({ useWorkspaceStore: () => ({ enterPage: vi.fn() }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
vi.mock('@/router', () => ({
  ROUTE_NAMES: { taskCenter: 'tasks', definitions: 'definitions', dataBrowser: 'data' },
  default: { push: vi.fn() }
}))
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { confirm: confirmMock }
  }
})

import { normalizeError } from '@/api/http'
import { confirmIrreversible, confirmPublishByMode } from '@/utils/confirm-irreversible'
import DefinitionsView from '@/views/DefinitionsView.vue'
import JobProgress from '@/components/JobProgress.vue'
import TaskWizard from '@/components/TaskWizard.vue'

const ButtonStub = defineComponent({
  props: { type: { type: String, default: '' }, link: Boolean, circle: Boolean, loading: Boolean },
  template: '<button v-bind="$attrs"><slot /></button>'
})

const DrawerStub = defineComponent({ template: '<div class="drawer-stub"><slot /></div>' })

const TableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  provide() {
    return { elTableRows: () => this.data }
  },
  template: '<div class="table-stub"><slot /><slot v-if="!(data && data.length)" name="empty" /></div>'
})

const ColumnStub = defineComponent({
  props: { type: { type: String, default: '' }, label: { type: String, default: '' } },
  inject: { rows: { from: 'elTableRows', default: () => () => [] } },
  template:
    '<span class="col-stub"><template v-for="(row, i) in rows()" :key="i"><slot :row="row" :$index="i" /></template></span>'
})

const stubs = {
  'el-alert': defineComponent({
    props: { title: { type: String, default: '' } },
    template: '<div class="alert-stub">{{ title }}<slot /></div>'
  }),
  'el-button': ButtonStub,
  'el-drawer': DrawerStub,
  'el-table': TableStub,
  'el-table-column': ColumnStub,
  'el-tag': defineComponent({ template: '<span class="tag-stub"><slot /></span>' }),
  'el-progress': true,
  'el-empty': true,
  'el-input': true,
  'el-select': true,
  'el-option': true,
  'el-form': true,
  'el-form-item': true,
  'el-radio-group': true,
  'el-radio-button': true,
  'el-descriptions': true,
  'el-descriptions-item': true,
  'el-divider': true,
  'el-input-number': true,
  'el-switch': true
}

function jobFixture(): Job {
  return {
    taskId: 1,
    jobType: 'IMPORT',
    status: 'RUNNING',
    progress: 3,
    total: -1,
    errorCount: 0,
    warningCount: 0,
    items: [{ jobId: 1, defCode: 'DEMO', status: 'RUNNING', processed: 1, total: 2 }]
  }
}

function issueFixture(): ValidationIssue {
  return { jobId: 1, defCode: 'DEMO', severity: 'ERROR', message: 'bad row', rowIndex: 1 }
}

beforeEach(() => {
  applyLocale('zh-CN')
  defsApi.listDefinitions.mockReset()
  defsApi.listDefinitions.mockResolvedValue([])
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(undefined)
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})

describe('R-01 Definitions 取消按钮复用 common.action.cancel', () => {
  it('抽屉里的取消按钮渲染「取消」，不是裸 i18n key', async () => {
    const wrapper = mount(DefinitionsView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('取消')
    expect(wrapper.text()).not.toContain('views.definitions.cancel')
    wrapper.unmount()
  })
})

describe('R-02 JobProgress 状态 / 严重度 / 进度 / 通道提示走 locale', () => {
  it('未知 severity 回落 INFO，不把拼出来的 key 原样上屏', async () => {
    applyLocale('en-US')
    const unknown = { ...issueFixture(), severity: 'SEVERITY' as ValidationIssue['severity'], message: 'odd row' }
    const wrapper = mount(JobProgress, {
      props: { job: jobFixture(), issues: [issueFixture(), unknown], channel: null },
      global: { stubs }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Info')
    expect(wrapper.text()).not.toContain('job.severity.SEVERITY')
    wrapper.unmount()
  })

  it('英文界面四块全换英文；切回中文按现有术语还原', async () => {
    applyLocale('en-US')
    const wrapper = mount(JobProgress, {
      props: { job: jobFixture(), issues: [issueFixture()], channel: 'polling' },
      global: { stubs }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Running')
    expect(wrapper.text()).toContain('Error')
    expect(wrapper.text()).toContain('Processing · 3 rows processed (total pending)')
    expect(wrapper.text()).toContain('Realtime channel degraded to polling')
    expect(wrapper.text()).not.toMatch(/[\u4e00-\u9fff]/)

    applyLocale('zh-CN')
    await flushPromises()
    expect(wrapper.text()).toContain('运行中')
    expect(wrapper.text()).toContain('错误')
    expect(wrapper.text()).toContain('处理中 · 已处理 3 行（总量待定）')
    expect(wrapper.text()).toContain('（实时通道已降级为轮询）')
    wrapper.unmount()
  })
})

describe('R-03 TaskWizard 默认按钮与任务状态标签走 locale', () => {
  it('不再使用中文默认值；英文界面按钮与状态标签均为英文', async () => {
    applyLocale('en-US')
    const wrapper = mount(TaskWizard, {
      props: { title: 'Task', steps: [{ key: 'a', label: 'Step' }], activeIndex: 0, taskStatus: 'COMPLETED' },
      global: { stubs }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Back to task list')
    expect(wrapper.text()).toContain('Next')
    expect(wrapper.text()).toContain('Completed')
    expect(wrapper.text()).not.toMatch(/[\u4e00-\u9fff]/)
    wrapper.unmount()
  })
})

describe('R-06 HTTP 兜底走 api.error.*，技术细节只进 console', () => {
  it('网络不可达：英文短句不含 VITE_API_BASE / HTTP 码', () => {
    applyLocale('en-US')
    const error = normalizeError(new AxiosError('Network Error', 'ERR_NETWORK'))
    expect(error.message).toBe('Cannot reach the backend. Check your network and try again.')
    expect(error.message).not.toContain('VITE_API_BASE')
    expect(error.code).toBe('NETWORK_ERROR')
    expect(console.warn).toHaveBeenCalled()
  })

  it('5xx：用户只看到可行动短句，HTTP 状态只进 console', () => {
    applyLocale('en-US')
    const response = {
      status: 502,
      statusText: 'Bad Gateway',
      headers: {},
      config: { url: '/api/x' },
      data: undefined
    }
    const error = normalizeError(
      new AxiosError('Request failed with status code 502', 'ERR_BAD_RESPONSE', { url: '/api/x' } as never, undefined, response as never)
    )
    expect(error.message).toBe('The backend service is temporarily unavailable. Try again later.')
    expect(error.message).not.toContain('502')
    const logged = JSON.stringify((console.warn as unknown as { mock: { calls: unknown[][] } }).mock.calls)
    expect(logged).toContain('502')
  })

  it('非 Error 兜底也不吐硬编码中文', () => {
    applyLocale('en-US')
    expect(normalizeError('weird').message).toBe('Something went wrong. Try again later.')
  })
})

describe('R-07 不可撤销确认框交回根级 Element Plus locale', () => {
  it('不覆盖 confirmButtonText / cancelButtonText', async () => {
    await confirmIrreversible({ title: 'Cancel job', message: 'm', ack: 'ack' })
    const options = confirmMock.mock.calls.at(-1)?.[2] as Record<string, unknown>
    expect(options).not.toHaveProperty('confirmButtonText')
    expect(options).not.toHaveProperty('cancelButtonText')
  })

  it('发布确认函数保留：REPLACE 才弹确认，MERGE 直接放行', async () => {
    confirmMock.mockClear()
    expect(await confirmPublishByMode('MERGE')).toBe(true)
    expect(confirmMock).not.toHaveBeenCalled()

    confirmMock.mockResolvedValue(undefined)
    expect(await confirmPublishByMode('REPLACE')).toBe(true)
    expect(confirmMock).toHaveBeenCalledTimes(1)
  })
})
