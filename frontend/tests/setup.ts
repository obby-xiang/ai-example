// jsdom 缺 ResizeObserver：组件在 onMounted 成功路径上要用它，缺了会被 catch 吞成"引擎加载失败"
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub

// 每例归零 markdown 的自适应降级开关（`src/utils/markdown.ts#degraded`）：该开关是**模块全局**且
// 置位后粘滞（产品语义 = 本页签不再尝试高亮/mermaid），判据却是**墙钟** 24ms 预算 ——
// 并行 worker / 本机负载抖动会让某个用例的单次渲染越预算，其后十余条 mermaid/代码块用例连带变红
// （2026-10-21 实测：父提交 d8056d5 在同样 CPU 压力下可复现同一批 12 条失败）。
// 放在全局 setup 而非单个 spec：任何渲染正文的用例都不得继承上一条用例的时序判定。
import { beforeEach } from 'vitest'
import { config } from '@vue/test-utils'
import { resetDegradation } from '@/utils/markdown'
import i18n, { DEFAULT_LOCALE, applyLocale } from '@/locales'

// i18n 全局注入（一处）：242 处 `mount()` 不逐个传 `global.plugins`。
// 用 `config.global.plugins` 而非改各 spec —— 注入面只有一次，AiPanel 的导入链
// 也不会因测试而多出额外组件依赖（勘察 §5.0.1 的两个超时面）。
config.global.plugins = [i18n]

beforeEach(() => {
  // 语言是模块级单例：任何一条用例切到 en 后必须归零，否则后续用例的中文断言连带变红
  applyLocale(DEFAULT_LOCALE)
  // 防未来整模块 mock：`vi.mock('@/utils/markdown')` 后具名导出会变 undefined，
  // 无守卫时这里每例抛 TypeError，把整批用例的失败原因指错方向。
  if (typeof resetDegradation === 'function') {
    resetDegradation()
  }
})
