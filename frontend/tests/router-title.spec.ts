/**
 * M-A（路由标题）：7 条路由的 `meta.titleKey` 随语言变，`document.title` 同步。
 *
 * 证红证据（施工前）：`meta.title` 是中文常量、`document.title` 无人维护 ⇒
 * 切语言后标题仍是进入时语言（本文件的 en 断言直接红）。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { applyDocumentTitle, routes } from '@/router'
import { useLocaleStore } from '@/stores/locale'
import { createPinia, setActivePinia } from 'pinia'

/** 只取带 name 的路由（重定向项没有标题契约）。 */
const titled = routes.filter((route) => typeof route.name === 'string' && route.meta?.titleKey)

describe('路由标题（A7）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useLocaleStore().setLocale('zh-CN')
  })

  it('A7a 7 条路由全部 titleKey 化，且无遗留中文 meta.title', () => {
    expect(titled).toHaveLength(7)
    for (const route of routes) {
      expect(route.meta?.title).toBeUndefined()
      if (typeof route.name === 'string') {
        expect(typeof route.meta?.titleKey).toBe('string')
      }
    }
  })

  it('A7b 同一路由在中英下产出不同标题，且都是已登记译文', () => {
    const to = { meta: { titleKey: 'route.taskCenter' } }
    applyDocumentTitle(to)
    expect(document.title).toBe('任务中心')
    useLocaleStore().setLocale('en-US')
    applyDocumentTitle(to)
    expect(document.title).toBe('Tasks')
  })

  it('A7c 无 titleKey 的路由不改标题（防御）', () => {
    document.title = '保持原样'
    applyDocumentTitle({ meta: {} })
    expect(document.title).toBe('保持原样')
  })
})
