/**
 * M-A（底座）：i18n 装配与键集契约。
 *
 * 证红证据（装包前）：`frontend/src` + `package.json` 对 `vue-i18n|useI18n|createI18n` 零命中、
 * `node_modules` 无任何 intlify 包 ⇒ 本文件 import 即失败（勘察 §1.3 实测）。
 */
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useI18n } from 'vue-i18n'
import i18n, { DEFAULT_LOCALE, SUPPORTED_LOCALES } from '@/locales'
import zhCN from '@/locales/zh-CN.json'
import enUS from '@/locales/en-US.json'

type Dict = { [key: string]: string | Dict }

/** 拍平成 `a.b.c` → 叶子值。 */
function flatten(dict: Dict, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(dict)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') {
      out[path] = value
    } else {
      Object.assign(out, flatten(value, path))
    }
  }
  return out
}

/** 取出消息里的 `{name}` 占位符集合（B3）。 */
function placeholders(message: string): string[] {
  return [...message.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()
}

describe('i18n 底座（A1/A2）', () => {
  it('A1 装配：默认 zh-CN，$t 可用', () => {
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(i18n.global.t('common.app.title')).toBe('配置管理平台')
  })

  it('A1b Composition 模式：组件内 useI18n 拿到同一实例', () => {
    const Probe = defineComponent({
      setup() {
        const { t } = useI18n()
        return () => h('span', t('common.app.title'))
      }
    })
    const wrapper = mount(Probe)
    expect(wrapper.text()).toBe('配置管理平台')
  })

  it('A2 不嗅探 navigator.language：伪造 en-US 仍为 zh-CN', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis.navigator, 'language')
    Object.defineProperty(globalThis.navigator, 'language', { value: 'en-US', configurable: true })
    try {
      expect(DEFAULT_LOCALE).toBe('zh-CN')
      expect(i18n.global.locale.value).toBe('zh-CN')
    } finally {
      if (original) Object.defineProperty(globalThis.navigator, 'language', original)
    }
  })

  it('A2b 支持语言表恰好两枚（zh-CN / en-US）', () => {
    expect([...SUPPORTED_LOCALES]).toEqual(['zh-CN', 'en-US'])
  })
})

describe('键集与插值契约（B1/B3）', () => {
  const zh = flatten(zhCN as Dict)
  const en = flatten(enUS as Dict)

  it('B1 键集双向对齐（zh ⊆ en 且 en ⊆ zh）', () => {
    const zhKeys = Object.keys(zh).sort()
    const enKeys = Object.keys(en).sort()
    expect(enKeys.filter((k) => !(k in zh))).toEqual([])
    expect(zhKeys.filter((k) => !(k in en))).toEqual([])
  })

  it('B3 同一 key 的 {占位符} 两侧同名同数', () => {
    const mismatched = Object.keys(zh)
      .filter((key) => key in en)
      .filter((key) => placeholders(zh[key]).join(',') !== placeholders(en[key]).join(','))
      .map((key) => ({ key, zh: placeholders(zh[key]), en: placeholders(en[key]) }))
    expect(mismatched).toEqual([])
  })

  it('B4 导入预检失败文案不再承诺“可直接继续”，改为先修复并重跑预检（双语）', () => {
    const zhCopy = zh['views.import.precheckFailed']
    const enCopy = en['views.import.precheckFailed']
    expect(zhCopy).toContain('重新执行预检查')
    expect(zhCopy).not.toContain('直接继续')
    expect(enCopy).toContain('run the pre-check again')
    expect(enCopy).not.toMatch(/continue/i)
  })

  it('B2 缺 key：渲染 key 原文而非空白（不抛）', () => {
    expect(i18n.global.t('no.such.key.exists')).toBe('no.such.key.exists')
  })
})
