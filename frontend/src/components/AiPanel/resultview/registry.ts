import type { Component } from 'vue'
import ResultViewTable from './ResultViewTable.vue'
import ResultViewText from './ResultViewText.vue'

/**
 * `kind → 组件` 注册表（设计稿 §7.1-②）：渲染器唯一的查表入口。
 *
 * 契约口径：表里**没有**的 kind ⇒ 走 `fallback` 文案（不是空白）；所以新增形态只有两种改法 ——
 * 在契约里加一个 kind（并同步后端造口），或什么都不做（旧前端按 fallback 渲）。
 * v1 只有 `table` / `text`；`chart` / `form` 的枚举留在契约里，渲染走 fallback（§3.3）。
 */
export const RESULTVIEW_RENDERERS: Record<string, Component> = {
  table: ResultViewTable,
  text: ResultViewText
}
