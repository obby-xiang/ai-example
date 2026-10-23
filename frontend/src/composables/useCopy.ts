/**
 * 脚本侧文案出口（i18n L-69）。
 *
 * <p>
 * 组件模板里 `useI18n()` 的 `t` 天然可用；脚本侧（store / utils / 常量表 / 事件回调）拿不到
 * 组件实例的 `t`，本模块就是那一层的**唯一出口**，避免各处各自 `import { i18n }` 再摸
 * `i18n.global`。
 *
 * <h3>两种用法</h3>
 * <ul>
 * <li>{@link useCopy}：组件 `setup` 内的入口（与 `useI18n().t` 同源，返回可响应的 `t`）；</li>
 * <li>{@link translate}：**非组件**上下文（Pinia store / 纯函数）。同样是响应式的 —— vue-i18n 的
 * composer `t` 内部 `trackReactivityValues()`，在 `computed` / 渲染函数里调用会订阅 locale，
 * 切语言即重算；在普通回调里调用则取**调用时刻**的语言（一次性 toast / 已落库的数据字段）。</li>
 * </ul>
 *
 * <h3>什么时候**不该**用它（C5 红线）</h3>
 * 送进模型的串（`sheet-tools.ts` / `types/tools.ts` / `ai-dom-tools.ts` / `frontend-tools.ts`
 * 的工具词表、`ui_event` 回灌文本、`recentActions` 摘要）一律保持中文常量，不得经本模块翻译。
 * 判据只有一条：**这串给谁看**。
 */

import { useI18n } from 'vue-i18n'
import { i18n } from '@/locales'

/** 命名插值参数（与 vue-i18n 的 named 参数同形）。 */
export type CopyArgs = Record<string, string | number>

/** 非组件上下文的翻译出口（store / utils / 纯函数）。 */
export function translate(key: string, args?: CopyArgs): string {
  return args === undefined ? i18n.global.t(key) : i18n.global.t(key, args)
}

/** 组件 `setup` 内的翻译出口（`t(key)` / `t(key, args)`，与模板 `t` 同语义）。 */
export function useCopy(): { t: (key: string, args?: CopyArgs) => string } {
  const { t } = useI18n()
  return {
    t: (key: string, args?: CopyArgs): string => (args === undefined ? t(key) : t(key, args))
  }
}
