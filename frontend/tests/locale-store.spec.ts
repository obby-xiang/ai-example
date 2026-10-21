/**
 * M-A（切换与联动）：语言 store 的"四件事 + 两处联动"。
 *
 * 证红证据（施工前）：本仓零 i18n 设施、locale 硬编码在 `main.ts` 一处 ⇒
 * 本文件 import `@/stores/locale` 即失败（文件不存在）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import dayjs from 'dayjs'
import i18n, { LOCALE_STORAGE_KEY, readStoredLocale } from '@/locales'
import { useLocaleStore } from '@/stores/locale'
import { http } from '@/api/http'

describe('语言 store（A3/A4/A5/A6）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('A3 切换四件事原子生效：i18n / html lang / localStorage / Accept-Language', async () => {
    const store = useLocaleStore()
    store.setLocale('en-US')

    expect(i18n.global.locale.value).toBe('en-US')
    expect(document.documentElement.getAttribute('lang')).toBe('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')

    // 下一请求的 Accept-Language：用自定义 adapter 截获配置（不发真实请求）
    let captured: Record<string, unknown> | null = null
    await http.get('/probe', {
      adapter: async (config) => {
        captured = config.headers as unknown as Record<string, unknown>
        return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
      }
    })
    expect(captured && (captured as Record<string, string>)['Accept-Language']).toBe('en-US')

    store.setLocale('zh-CN')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(document.documentElement.getAttribute('lang')).toBe('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN')
  })

  it('A4 持久化优先序：localStorage 有值 > 默认；坏值回落 zh-CN 且不写脏值', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'en-US')
    expect(readStoredLocale()).toBe('en-US')

    localStorage.setItem(LOCALE_STORAGE_KEY, 'ko-KR')
    expect(readStoredLocale()).toBe('zh-CN')

    localStorage.removeItem(LOCALE_STORAGE_KEY)
    expect(readStoredLocale()).toBe('zh-CN')
  })

  it('A4b init() 从持久化恢复', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'en-US')
    const store = useLocaleStore()
    store.init()
    expect(store.locale).toBe('en-US')
    expect(i18n.global.locale.value).toBe('en-US')
  })

  it('A5 Element Plus 联动：en 下 elLocale 为英文包', () => {
    const store = useLocaleStore()
    store.setLocale('zh-CN')
    expect(store.elLocale.name).toBe('zh-cn')
    store.setLocale('en-US')
    expect(store.elLocale.name).toBe('en')
  })

  it('A6 Day.js 联动：en 下英文、zh 下中文', () => {
    const store = useLocaleStore()
    store.setLocale('en-US')
    expect(dayjs.locale()).toBe('en')
    store.setLocale('zh-CN')
    expect(dayjs.locale()).toBe('zh-cn')
  })

  it('A6b toggle 在中英之间往返', () => {
    const store = useLocaleStore()
    store.setLocale('zh-CN')
    store.toggle()
    expect(store.locale).toBe('en-US')
    store.toggle()
    expect(store.locale).toBe('zh-CN')
  })
})
