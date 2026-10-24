// jsdom 缺 ResizeObserver：组件在 onMounted 成功路径上要用它，缺了会被 catch 吞成"引擎加载失败"
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub

import { beforeEach } from 'vitest'
import { config } from '@vue/test-utils'
import i18n, { DEFAULT_LOCALE, applyLocale } from '@/locales'

// i18n 全局注入（一处）：242 处 `mount()` 不逐个传 `global.plugins`。
// 用 `config.global.plugins` 而非改各 spec —— 注入面只有一次，AiPanel 的导入链
// 也不会因测试而多出额外组件依赖（勘察 §5.0.1 的两个超时面）。
config.global.plugins = [i18n]

beforeEach(() => {
  // 语言是模块级单例：任何一条用例切到 en 后必须归零，否则后续用例的中文断言连带变红
  applyLocale(DEFAULT_LOCALE)
})
