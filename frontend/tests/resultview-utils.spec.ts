/**
 * S6 批 2 · resultView 契约（设计稿 §3/§4/§7.2）的前端纯函数锁。
 *
 * 三组判据：
 * - **契约构造**（发布冲突）：确定性、列=配置/行键/原因、截断三件套、必填 fallback；
 * - **白名单降级**：未知 kind / 未知列类型 / major 不兼容 / data 解不出 ⇒ 一律 fallback，不抛错；
 * - **规模上限**：>200 行 / >50 列由归一化收敛，且与后端同值（{@link RESULTVIEW_LIMITS}）。
 */
import { describe, expect, it } from 'vitest'
import {
  buildPublishConflictView,
  containsForbiddenMarkup,
  isRenderableView,
  normalizeResultView,
  resolveJsonPointer,
  resolveViewRows,
  RESULTVIEW_EMPTY_TEXT,
  RESULTVIEW_LIMITS,
  RESULTVIEW_REDACTED,
  sanitizeResultViewText
} from '@/utils/resultview'
import type { ResultView } from '@/types/resultview'

function view(overrides: Partial<ResultView> = {}): ResultView {
  return {
    version: '1.0',
    id: 'rv-1',
    revision: 1,
    kind: 'table',
    status: 'ok',
    data: { inline: { rows: [{ a: '1', b: '2' }] } },
    columns: [
      { key: 'a', type: 'string' },
      { key: 'b', type: 'string' }
    ],
    fallback: { text: 'i18n:resultview.fallback.generic', format: 'plain' },
    ...overrides
  }
}

describe('resultview · 契约常量与消毒', () => {
  it('limits 与后端 ResultViews 同值（200 行 / 50 列 / 256KB）', () => {
    expect(RESULTVIEW_LIMITS).toEqual({ maxRows: 200, maxColumns: 50, maxBytes: 256 * 1024 })
  })

  it('HTML / 事件串在出端被替换，裸尖括号的业务文本不受影响', () => {
    expect(sanitizeResultViewText('<script>alert(1)</script>')).not.toContain('<script')
    expect(sanitizeResultViewText('javascript:alert(1)')).not.toContain('javascript:')
    expect(sanitizeResultViewText('值 <ABC> 不合法')).toBe('值 <ABC> 不合法')
    expect(sanitizeResultViewText('配置 onerror = alert(1)')).toContain(RESULTVIEW_REDACTED)
    expect(containsForbiddenMarkup('<iframe src=//x>')).toBe(true)
    expect(containsForbiddenMarkup('正常原因文本')).toBe(false)
  })
})

describe('resultview · 发布冲突视图构造（§5.3）', () => {
  const issues = [
    { defCode: 'CURRENCY', rowKey: 'GLOBAL', message: '币种精度与主数据不一致' },
    { defCode: 'TAX_RULE', rowKey: 'PRJ-07', message: '税率区间重叠' }
  ]

  it('列=配置/行键/原因，data 指向结构化 issues，fallback 必填', () => {
    const built = buildPublishConflictView({ id: 'rv-call_1', issues, total: 2 })
    expect(built.version).toBe('1.0')
    expect(built.kind).toBe('table')
    expect(built.status).toBe('ok')
    expect(built.columns?.map((column) => column.key)).toEqual(['defCode', 'rowKey', 'message'])
    expect(built.columns?.map((column) => column.label)).toEqual([
      'i18n:resultview.publishConflict.col.defCode',
      'i18n:resultview.publishConflict.col.rowKey',
      'i18n:resultview.publishConflict.col.message'
    ])
    expect(built.data?.inline).toEqual({ rows: issues })
    expect(built.fallback.text).toBe('i18n:resultview.publishConflict.fallback')
    expect(built.a11y?.label).toBe('i18n:resultview.publishConflict.label')
    expect(built.meta?.source).toBe('validation_issue')
    expect(built.meta?.catalogId).toBe('configmgr/resultview/v1')
    expect(built.title).toBe('i18n:resultview.publishConflict.title')
  })

  it('同一输入 ⇒ 同一载荷（可快照对账）', () => {
    const a = buildPublishConflictView({ id: 'rv-1', issues, total: 9 })
    const b = buildPublishConflictView({ id: 'rv-1', issues, total: 9 })
    expect(JSON.stringify(b)).toBe(JSON.stringify(a))
  })

  it('只展示前 5 条；超出即 partial + meta.truncated{shownRows,totalRows}', () => {
    const many = Array.from({ length: 9 }, (_, index) => ({
      defCode: `D${index}`, rowKey: `R${index}`, message: `冲突 ${index}`
    }))
    const built = buildPublishConflictView({ id: 'rv-1', issues: many, total: 9 })
    expect((built.data?.inline as { rows: unknown[] }).rows).toHaveLength(5)
    expect(built.status).toBe('partial')
    expect(built.meta?.truncated).toEqual({ shownRows: 5, totalRows: 9 })
  })

  it('rowKey 缺失渲占位横线（与旧摘要同口径），脏文本被消毒', () => {
    const built = buildPublishConflictView({
      id: 'rv-1',
      issues: [{ defCode: 'A', rowKey: null, message: '<script>alert(1)</script>' }],
      total: 1
    })
    const rows = (built.data?.inline as { rows: Record<string, string>[] }).rows
    expect(rows[0].rowKey).toBe('-')
    expect(JSON.stringify(built)).not.toContain('<script')
  })
})

describe('resultview · 白名单降级（§4-规则 1 / §7.2-3）', () => {
  it('合法视图原样通过', () => {
    const normalized = normalizeResultView(view())
    expect(normalized.view).not.toBeNull()
    expect(normalized.view?.kind).toBe('table')
  })

  it('未知 kind ⇒ 走该视图自带的 fallback，不抛错', () => {
    const raw = view({
      kind: 'gauge' as unknown as ResultView['kind'],
      fallback: { text: 'i18n:custom.viewFallback', format: 'plain' }
    })
    const normalized = normalizeResultView(raw)
    expect(normalized.view).toBeNull()
    expect(normalized.fallbackText).toBe('i18n:custom.viewFallback')
  })

  it('major 不兼容 ⇒ 走该视图自带的 fallback 文案（不尝试兼容解析）', () => {
    const raw = view({ version: '2.0', fallback: { text: 'i18n:custom.viewFallback', format: 'plain' } })
    const normalized = normalizeResultView(raw)
    expect(normalized.view).toBeNull()
    expect(normalized.fallbackText).toBe('i18n:custom.viewFallback')
  })

  it('连 fallback 都没有 ⇒ 通用兜底文案', () => {
    expect(normalizeResultView(null).fallbackText).toBe('i18n:resultview.fallback.generic')
    expect(normalizeResultView(view({ version: '3.1', fallback: undefined as never })).fallbackText)
      .toBe('i18n:resultview.fallback.generic')
  })

  it('未知列类型 / 缺列定义 ⇒ 降级', () => {
    const unknownColumn = view({ columns: [{ key: 'a', type: 'rich-html' as never }] })
    expect(normalizeResultView(unknownColumn).view).toBeNull()
    expect(normalizeResultView(view({ columns: [] })).view).toBeNull()
  })

  it('缺 fallback / 空 fallback.text ⇒ 降级（contract 必填）', () => {
    expect(normalizeResultView(view({ fallback: undefined as never })).view).toBeNull()
    expect(normalizeResultView(view({ fallback: { text: '  ', format: 'plain' } })).view).toBeNull()
  })

  it('chart / form 在 v1 只占位 ⇒ 走 fallback', () => {
    expect(normalizeResultView(view({ kind: 'chart' })).view).toBeNull()
    expect(normalizeResultView(view({ kind: 'form' })).view).toBeNull()
  })

  it('text 视图无需 columns / data', () => {
    const text = normalizeResultView(view({
      kind: 'text', columns: undefined, data: undefined, description: 'i18n:some.sentence'
    }))
    expect(text.view?.kind).toBe('text')
  })

  it('非对象输入不崩', () => {
    expect(normalizeResultView(null).view).toBeNull()
    expect(normalizeResultView('x').view).toBeNull()
  })
})

describe('resultview · 数据引用与规模上限', () => {
  it('RFC6901 指针求值（含转义 ~0/~1、越界返回 undefined）', () => {
    const doc = { rows: [{ a: 1 }], 'a/b': 2, 'm~n': 3 }
    expect(resolveJsonPointer(doc, '/rows')).toEqual([{ a: 1 }])
    expect(resolveJsonPointer(doc, '/a~1b')).toBe(2)
    expect(resolveJsonPointer(doc, '/m~0n')).toBe(3)
    expect(resolveJsonPointer(doc, '/')).toBe(doc)
    expect(resolveJsonPointer(doc, '/nope')).toBeUndefined()
  })

  it('inline 行取自 rows / issues，或直接是数组', () => {
    expect(resolveViewRows(view())).toHaveLength(1)
    expect(resolveViewRows(view({ data: { inline: { issues: [{ a: 1 }, { a: 2 }] } } }))).toHaveLength(2)
    expect(resolveViewRows(view({ data: { inline: [{ a: 1 }] } }))).toHaveLength(1)
    expect(resolveViewRows(view({ data: { inline: { nothing: true } } }))).toEqual([])
  })

  it('ref 指向 result JSON 文档；文档不是 JSON ⇒ null（渲染走 fallback）', () => {
    const refView = view({ data: { ref: '/rows' } })
    expect(resolveViewRows(refView, '{"rows":[{"a":"1"},{"a":"2"}]}')).toHaveLength(2)
    expect(resolveViewRows(refView, '作业已启动')).toBeNull()
    expect(resolveViewRows(view({ data: { ref: '/' } }), '[{"a":1}]')).toHaveLength(1)
  })

  it('超 256KB（行/列都在限内）⇒ 不渲，走 fallback（第三把尺）', () => {
    const huge = [{ a: 'x'.repeat(300 * 1024) }]
    const normalized = normalizeResultView(view({ data: { inline: { rows: huge } } }))
    expect(normalized.view).toBeNull()
    expect(normalized.fallbackText).toBe('i18n:resultview.fallback.generic')
  })

  it('>200 行 / >50 列被归一化收敛，且标 partial + truncated', () => {
    const rows = Array.from({ length: 240 }, (_, index) => ({ a: String(index) }))
    const columns = Array.from({ length: 60 }, (_, index) => ({ key: `c${index}`, type: 'string' as const }))
    const normalized = normalizeResultView(view({ data: { inline: { rows } }, columns }))
    expect(normalized.view?.columns).toHaveLength(RESULTVIEW_LIMITS.maxColumns)
    const kept = resolveViewRows(normalized.view as ResultView)
    expect(kept).toHaveLength(RESULTVIEW_LIMITS.maxRows)
    expect(normalized.view?.status).toBe('partial')
    expect(normalized.view?.meta?.truncated).toEqual({ shownRows: 200, totalRows: 240 })
  })
})

/**
 * S6 批2 迁移步④（设计稿 §7.1-④）：卡面「输出」区由视图接管还是回落旧原文盒，判据必须收敛在
 * **一个纯函数**里 —— 渲染器与旧路径各自判一次就等于两套口径（迟早出现"渲了视图又渲原文"）。
 */
describe('resultview · 旧卡迁移判据 isRenderableView', () => {
  it('可渲的 table / text 视图 ⇒ true（旧原文盒让位）', () => {
    expect(isRenderableView(view())).toBe(true)
    expect(isRenderableView(view({ kind: 'text', columns: undefined, data: undefined, description: '一句话' })))
      .toBe(true)
  })

  it('不可渲（未知 kind / 未实现 kind / 未知列类型 / 缺 fallback）⇒ false（回落旧原文盒）', () => {
    expect(isRenderableView(view({ kind: 'chart' }))).toBe(false)
    expect(isRenderableView(view({ kind: 'form' }))).toBe(false)
    expect(isRenderableView(view({ columns: [{ key: 'a', type: 'rich-html' as never }] }))).toBe(false)
    expect(isRenderableView(view({ fallback: undefined as never }))).toBe(false)
    expect(isRenderableView(null)).toBe(false)
    expect(isRenderableView(undefined)).toBe(false)
  })

  it('data.ref 指向非 JSON 结果 ⇒ false；指向 JSON 文档 ⇒ true（求值域 = result 原文）', () => {
    const refView = view({ data: { ref: '/rows' } })
    expect(isRenderableView(refView, '作业已启动，无结构化文档')).toBe(false)
    expect(isRenderableView(refView, '{"rows":[{"a":"1","b":"2"}]}')).toBe(true)
    expect(isRenderableView(refView, null)).toBe(false)
  })

  it('status=error（视图构造失败）⇒ false：原文比一句错误更可信', () => {
    expect(isRenderableView(view({ status: 'error', error: { code: 'FRAME_PAYLOAD_INVALID', message: '视图数据无法解析' } })))
      .toBe(false)
  })
})

/**
 * 空态文案的归一化（设计稿 §3.2：`empty` = 空态文案与出口提示，**不是** `fallback`）。
 *
 * 后端若把失败出口文案原样塞进 `empty.text`（旧造口行为），卡面会把"暂时没有记录"读成
 * "无法以表格展示" —— 空 ≠ 失败。归一化把它修成中性空态文案；工具自己的空态 key 不动。
 */
describe('resultview · 空态文案归一化', () => {
  const emptyView = (overrides: Partial<ResultView> = {}): ResultView => view({
    status: 'empty',
    data: { inline: { rows: [] } },
    ...overrides
  })

  it('empty.text 与 fallback.text 相同（失败出口被复用）⇒ 换成中性空态文案', () => {
    const normalized = normalizeResultView(emptyView({
      fallback: { text: 'i18n:resultview.listTasks.fallback', format: 'plain' },
      empty: { text: 'i18n:resultview.listTasks.fallback' }
    }))
    expect(normalized.view?.status).toBe('empty')
    expect(normalized.view?.empty?.text).toBe(RESULTVIEW_EMPTY_TEXT)
    expect(normalized.view?.fallback.text).toBe('i18n:resultview.listTasks.fallback')
  })

  it('工具自己的空态 key ⇒ 原样保留', () => {
    const normalized = normalizeResultView(emptyView({
      fallback: { text: 'i18n:resultview.listTasks.fallback', format: 'plain' },
      empty: { text: 'i18n:resultview.listTasks.empty' }
    }))
    expect(normalized.view?.empty?.text).toBe('i18n:resultview.listTasks.empty')
  })

  it('缺 empty / 空串 ⇒ 补中性空态文案（status=empty 必填）', () => {
    expect(normalizeResultView(emptyView()).view?.empty?.text).toBe(RESULTVIEW_EMPTY_TEXT)
    expect(normalizeResultView(emptyView({ empty: { text: '  ' } })).view?.empty?.text)
      .toBe(RESULTVIEW_EMPTY_TEXT)
  })

  it('非空态（ok / partial / error）不动：empty 不参与渲染', () => {
    const ok = normalizeResultView(view())
    expect(ok.view?.empty).toBeUndefined()
  })
})
