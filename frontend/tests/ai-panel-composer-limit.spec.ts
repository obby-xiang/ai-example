/**
 * 安全防护专项批① 闸门 1（前端面）：composer 的 2000 字上限、剩余字数提示、以及 M5 的播报节流。
 *
 * <h2>为什么单独成文件（复审 C1 + M6 收口）</h2>
 * 这 5 例原先落在 `ai-panel-parts-render.spec.ts` 的末尾，那里的 `@/api/ai` 桩只在**部分**用例里
 * 补 `api.history`，于是空 messages 挂载时 `loadHistory()` 拿到 `undefined` →
 * `history.messages` 抛 TypeError → vitest 记 5 条 **Unhandled Rejection**、整轮 exit 1（CI 红）。
 * 本文件自带最小装配（health / history / runsCurrent 三桩都给实值），因此：
 * ① 不依赖任何其它文件的桩残留；② 不再往那个 flaky 大文件里追加用例。
 *
 * <h2>分工</h2>
 * 浏览器层靠 `maxlength` 不让写出超限文本；提示层给出剩余字数与档位；**判定层仍是后端**
 * （400 MESSAGE_TOO_LONG，不截断）。所以这里断言"前端不留超限路径 + 到顶不说谎 + 不逐字播报"，
 * 而不是"前端拒绝"。
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { installIdentityFixture } from './ai-identity-fixture'

const api = vi.hoisted(() => ({
  health: vi.fn(),
  history: vi.fn(),
  runsCurrent: vi.fn(),
  frontendToolResult: vi.fn(),
  reattachRun: vi.fn(),
  chat: vi.fn(),
  feedbackSubmit: vi.fn(),
  confirm: vi.fn()
}))

vi.mock('@/api/ai', () => ({ aiApi: api }))

/** Element Plus 全部退场（R-165 S3a 起 composer 已是原生结构）；FormRenderer 与本文件无关。 */
const stubs = {
  'el-alert': true,
  'el-button': { template: '<button><slot /></button>' },
  'el-empty': true,
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': true,
  'el-skeleton': true,
  'el-tag': { template: '<span class="tag"><slot /></span>' },
  FormRenderer: true
}

/**
 * 预热：`AiPanel.vue` 是 3000+ 行的单文件组件，**冷导入/编译**的代价会落在文件的首个用例上
 * （并行 worker 下实测可越过 vitest 的 5s 默认超时）。这是一次性编译成本、不是产品慢，
 * 故在这里先付掉，并把本文件的用例超时显式放宽（见两个 describe 的 options）。
 */
beforeAll(async () => {
  await import('@/components/AiPanel/AiPanel.vue')
}, 30_000)

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
  api.health.mockReset()
  api.history.mockReset()
  api.runsCurrent.mockReset()
  api.chat.mockReset()
  api.health.mockResolvedValue({
    available: true,
    code: 'OK',
    model: 'm',
    baseUrl: 'b',
    memoryBackend: 'redis',
    sessionTtl: '1h',
    frontendToolTimeoutSeconds: 120,
    suspendPoolSize: 4,
    activeSuspendGates: 0,
    heldSessions: 0,
    sessionLockRenewals: 0,
    resumeOnStartup: false,
    instanceId: 'i',
    checkedAt: '2026-01-01T00:00:00Z'
  })
  // C1 的根因就是这两个桩缺值：给实值 ⇒ 挂载期不再产生 Unhandled Rejection
  api.history.mockResolvedValue({ count: 0, messages: [] })
  api.runsCurrent.mockResolvedValue({ found: false, runId: null, awaitingExternal: false, pendingEntries: [] })
  api.chat.mockResolvedValue(undefined)
  installIdentityFixture()
})

/** 空 messages 挂载（本文件只关心 composer，不需要历史/挂起夹具）。 */
async function mountPanel(): Promise<ReturnType<typeof mount>> {
  const { default: AiPanel } = await import('@/components/AiPanel/AiPanel.vue')
  const wrapper = mount(AiPanel, { global: { stubs } })
  await wrapper.vm.$nextTick()
  await flushPromises()
  return wrapper
}

describe('批① 闸门 1：composer 2000 字上限（maxlength / 剩余字数 / 到顶与超限）', { timeout: 20_000 }, () => {
  it('textarea 带 maxlength=2000（与后端 app.ai.input.max-chars 同值同尺）', async () => {
    const wrapper = await mountPanel()
    const box = wrapper.find('[data-testid="composer-input"]')
    expect(box.attributes('maxlength')).toBe('2000')
    wrapper.unmount()
  })

  it('空输入不显示计数；有输入显示剩余字数，且在底栏内紧邻上下文圆环左侧', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="composer-count"]').exists()).toBe(false)

    await wrapper.find('[data-testid="composer-input"]').setValue('列出全部配置定义')

    const count = wrapper.find('[data-testid="composer-count"]')
    expect(count.exists()).toBe(true)
    expect(count.text()).toBe('剩余 1992 字')
    expect(count.classes()).toEqual(expect.arrayContaining(['text-[10.5px]', 'tabular-nums', 'text-[#8b949e]']))
    // 位置：底栏最后一个 flex 子元素 = 发送钮；其左是上下文圆环（056ac3a9 起常驻，不再按有无记账隐藏），
    // 计数再往左 —— 三者相邻，计数因此是倒数第三个子元素（不再是"发送钮前一个"）。
    const box = wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement
    const footer = box.parentElement!.lastElementChild as HTMLElement
    const children = Array.from(footer.children)
    expect((children[children.length - 1] as HTMLElement).getAttribute('data-testid')).toBe('send-btn')
    expect((children[children.length - 2] as HTMLElement).querySelector('[data-testid="context-pill"]')).not.toBeNull()
    expect(children.indexOf(count.element as HTMLElement)).toBe(children.length - 3)
    wrapper.unmount()
  })

  it('到顶（恰好 2000）改为「已达上限」提示 + 琥珀色，且不被前端拦下（后端边界仍是 2000 放行）', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="composer-input"]').setValue('中'.repeat(2000))

    const count = wrapper.find('[data-testid="composer-count"]')
    expect(count.text()).toBe('已达 2000 字上限')
    expect(count.classes()).toContain('text-[#b45309]')
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('超限（程序化回填 2001）标红并关闭发送闸 —— 不替用户截断', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="composer-input"]').setValue('中'.repeat(2001))

    const count = wrapper.find('[data-testid="composer-count"]')
    expect(count.text()).toBe('已超 1 字上限')
    expect(count.classes()).toContain('text-[#c0392b]')
    expect(wrapper.find('[data-testid="send-btn"]').attributes('disabled')).toBeDefined()
    // 输入框内容一字不少（截断是后端明确否掉的方案）
    expect((wrapper.find('[data-testid="composer-input"]').element as HTMLTextAreaElement).value).toHaveLength(2001)
    wrapper.unmount()
  })

  it('1999 字正常发送：payload 原样（不做前端裁剪）', async () => {
    const wrapper = await mountPanel()
    const text = `${'中'.repeat(1998)}!`
    await wrapper.find('[data-testid="composer-input"]').setValue(text)
    expect(wrapper.find('[data-testid="composer-count"]').text()).toBe('剩余 1 字')

    await wrapper.find('[data-testid="send-btn"]').trigger('click')
    await flushPromises()

    expect(api.chat).toHaveBeenCalledTimes(1)
    expect(api.chat.mock.calls[0][0].message).toBe(text)
    wrapper.unmount()
  })
})

describe('批① 闸门 1（M5）：计数不逐字播报，只在档位跃迁时播报', { timeout: 20_000 }, () => {
  it('计数节点自身不带 aria-live；播报由独立的屏读 live region 承担', async () => {
    const wrapper = await mountPanel()
    await wrapper.find('[data-testid="composer-input"]').setValue('一')

    const count = wrapper.find('[data-testid="composer-count"]')
    expect(count.attributes('aria-live')).toBeUndefined()

    const live = wrapper.find('[data-testid="composer-count-live"]')
    expect(live.exists()).toBe(true)
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.classes()).toContain('sr-only')
    wrapper.unmount()
  })

  it('常规档内逐字输入：live region 文本恒为空（不打扰）', async () => {
    const wrapper = await mountPanel()
    const box = wrapper.find('[data-testid="composer-input"]')
    const live = () => wrapper.find('[data-testid="composer-count-live"]').text()

    for (const text of ['列', '列出', '列出全', '列出全部配置定义']) {
      await box.setValue(text)
      expect(live()).toBe('')
    }
    wrapper.unmount()
  })

  it('跃迁到余量不足档只说一次常量文案（不含剩余数字）', async () => {
    const wrapper = await mountPanel()
    const box = wrapper.find('[data-testid="composer-input"]')
    await box.setValue('中'.repeat(1499)) // 余量 501：仍是常规档
    expect(wrapper.find('[data-testid="composer-count-live"]').text()).toBe('')

    await box.setValue('中'.repeat(1500)) // 余量 500：进 near 档
    expect(wrapper.find('[data-testid="composer-count-live"]').text()).toBe('剩余字数不足 500 字')

    await box.setValue('中'.repeat(1503)) // 同档内继续输入：文案不变（不逐字播报）
    expect(wrapper.find('[data-testid="composer-count-live"]').text()).toBe('剩余字数不足 500 字')
    wrapper.unmount()
  })

  it('到顶与超限两档各有独立文案', async () => {
    const wrapper = await mountPanel()
    const box = wrapper.find('[data-testid="composer-input"]')

    await box.setValue('中'.repeat(2000))
    expect(wrapper.find('[data-testid="composer-count-live"]').text()).toBe('已达 2000 字上限')

    await box.setValue('中'.repeat(2001))
    expect(wrapper.find('[data-testid="composer-count-live"]').text()).toBe('已超出上限，无法发送')
    wrapper.unmount()
  })
})
