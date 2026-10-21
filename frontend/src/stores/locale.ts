/**
 * 语言状态（单一源）。
 *
 * 组件只读 `locale` / `elLocale`，切语言只调 `setLocale()`；**禁止**组件直接改
 * `i18n.global.locale`（否则 `<html lang>`、localStorage、dayjs 会与界面不同步）。
 *
 * dayjs 与 Element Plus 的 locale 都在这里联动：前者是全局单例（必须显式 `dayjs.locale`），
 * 后者经 `App.vue` 的 `<el-config-provider :locale>` 下发 —— 入口不再传静态 `locale`，
 * 否则切语言后 Element Plus 内建文案（分页 `共 N 条` 等）仍是旧语言。
 */

import { defineStore } from 'pinia'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import {
  SUPPORTED_LOCALES,
  activeLocale,
  applyLocale,
  readStoredLocale,
  type AppLocale
} from '@/locales'

/** 语言 → dayjs locale 名（en 为 dayjs 内建，无需额外语言包）。 */
function dayjsLocaleOf(locale: AppLocale): string {
  return locale === 'en-US' ? 'en' : 'zh-cn'
}

export const useLocaleStore = defineStore('locale', {
  state: () => ({
    /** 当前 UI 语言（持久化值优先；非法值已在 readStoredLocale 内回落）。 */
    locale: readStoredLocale() as AppLocale
  }),

  getters: {
    availableLocales: (): readonly AppLocale[] => SUPPORTED_LOCALES,
    /** Element Plus 的 locale 对象（`<el-config-provider :locale>` 唯一来源）。 */
    elLocale: (state) => (state.locale === 'en-US' ? en : zhCn),
    isEnglish: (state): boolean => state.locale === 'en-US'
  },

  actions: {
    /** 启动恢复：把持久化语言同步到 i18n / `<html lang>` / dayjs。 */
    init(): void {
      this.setLocale(readStoredLocale())
    },

    /** 切语言（四件事原子生效：i18n、HTML lang、localStorage、dayjs；elLocale 由派生量跟随）。 */
    setLocale(locale: AppLocale): void {
      applyLocale(locale)
      this.locale = locale
      dayjs.locale(dayjsLocaleOf(locale))
    },

    /** 在中英之间切换（菜单/设置页用）。 */
    toggle(): void {
      this.setLocale(activeLocale() === 'en-US' ? 'zh-CN' : 'en-US')
    }
  }
})
