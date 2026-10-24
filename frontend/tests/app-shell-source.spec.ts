/**
 * R3-57 源码护栏：App.vue 的横向菜单下边框抑制不得回退到 Tailwind `!` 工具类，
 * 必须保留实例级 scoped 选择器（无全局覆写、无 :deep）。
 */
import { describe, expect, it } from 'vitest'

describe('App.vue · 横向菜单下边框（R3-57）', () => {
  it('不使用 Tailwind ! 覆写，且保留实例级 scoped 选择器', async () => {
    const source: string = (await import('@/App.vue?raw')).default

    expect(source).not.toContain('!border-b-0')
    expect(source).not.toContain('!important')
    expect(source).not.toMatch(/\sclass="[^"]*![^\s"]*/)
    expect(source).not.toContain(':deep(')

    expect(source).toContain('class="app-nav"')
    expect(source).toContain('<style scoped>')
    expect(source).toContain('.app-nav.el-menu--horizontal.el-menu')
    expect(source).toContain('border-bottom-width: 0')
  })
})
