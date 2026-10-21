/**
 * i18n 装配（vue-i18n 11，Composition 模式）。
 *
 * 三件事：
 * 1. `i18n` 实例（`legacy: false`，`fallbackLocale: 'zh-CN'`）；
 * 2. `applyLocale()`：切语言时**必须同批**做掉的三件副作用（i18n locale / `<html lang>` /
 *    localStorage `cm.locale`）；dayjs 由 `stores/locale` 的 `setLocale` 一并联动；
 * 3. `acceptLanguage()`：请求头取值（`api/http.ts` 拦截器唯一调用点）。
 *
 * 资源形态：zh-CN / en-US 均**静态 import**。两份都是几 KB 的小 JSON，同步可用换来
 * "切语言不 await、测试路径无动态 import"；懒加载会让 `AiPanel` 的导入链在测试首例多一次
 * 异步边界（勘察 §5.0.1 点名 `ai-panel-parts-render` / `ai-panel-composer-limit` 两个超时面）。
 *
 * 默认语言固定 zh-CN，**不嗅探 `navigator.language`**（设计稿 §8-1 选项 A）：
 * 首次打开即英文会让截图基线与 E2E 中文断言变成偶发缺陷。
 */

import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN.json'
import enUS from './en-US.json'

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const
export type AppLocale = (typeof SUPPORTED_LOCALES)[number]

export const DEFAULT_LOCALE: AppLocale = 'zh-CN'

/** 语言持久化键（新增键，无迁移面）。 */
export const LOCALE_STORAGE_KEY = 'cm.locale'

export const i18n = createI18n({
  legacy: false,
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  },
  // 缺键时渲染 key 原文而非空白（B2 口径），由测试守护
  missingWarn: false,
  fallbackWarn: false
})

export function isAppLocale(value: unknown): value is AppLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value)
}

/** 读持久化语言；缺失或非法值一律回落 zh-CN（不写脏值、不抛）。 */
export function readStoredLocale(): AppLocale {
  try {
    const stored = globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY)
    return isAppLocale(stored) ? stored : DEFAULT_LOCALE
  } catch {
    return DEFAULT_LOCALE
  }
}

/** 当前生效语言（唯一读点；组件禁止直接读 `i18n.global.locale`）。 */
export function activeLocale(): AppLocale {
  const value = i18n.global.locale.value
  return isAppLocale(value) ? value : DEFAULT_LOCALE
}

/** 语言 → `Accept-Language` 取值（zh-CN / en-US 原样，与 BCP 47 一致）。 */
export function acceptLanguage(): string {
  return activeLocale()
}

/**
 * 应用语言：i18n locale + `<html lang>` + localStorage 三件事**同步**完成。
 *
 * dayjs 与 Element Plus 的取值由 `stores/locale` 的派生量负责（同一 store 是单一源）。
 */
export function applyLocale(locale: AppLocale): AppLocale {
  i18n.global.locale.value = locale
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('lang', locale)
  }
  try {
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // 隐私模式/配额满：内存态已是新语言，持久化失败不阻断切换
  }
  return locale
}

/** 启动恢复：localStorage 有值优先，否则默认 zh-CN。 */
export function initLocale(): AppLocale {
  return applyLocale(readStoredLocale())
}

export default i18n
