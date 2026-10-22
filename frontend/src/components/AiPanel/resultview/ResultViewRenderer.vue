<script setup lang="ts">
/**
 * 通用结果视图渲染器（设计稿 2026-10-22 §7.1-②）。
 *
 * 职责只有三件：
 * 1. **归一化**：入站视图过 {@link normalizeResultView}（白名单 + 规模收敛 + 降级判定）；
 * 2. **分派**：`kind → 组件` 查 {@link RESULTVIEW_RENDERERS}，查不到 / 归一失败 ⇒ 渲 `fallback.text`；
 * 3. **空态**：`status=empty` 渲 `empty.text`（不渲空表，沿用 19-e「0 行不给展开入口」口径）。
 *
 * 渲染纪律：不渲原始 HTML、不做表达式求值、不认 CSS 类名与模板串 —— 一切文本走插值（自动转义）。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ResultView } from '@/types/resultview'
import { normalizeResultView, resolveViewRows, resolveViewText } from '@/utils/resultview'
import { RESULTVIEW_RENDERERS } from './registry'

const props = defineProps<{
  /** 入站视图（来自帧 / 段 / 历史；形状不可信 —— 一律过归一化） */
  view?: ResultView | null
  /** `result` 原文：`data.ref`（RFC6901）的求值域；非 JSON 时 ref 视图走 fallback */
  documentText?: string | null
}>()

const { t } = useI18n()

const normalize = computed(() => normalizeResultView(props.view, props.documentText))
const resolved = computed<ResultView | null>(() => normalize.value.view)
const renderer = computed(() => {
  const view = resolved.value
  return view === null ? null : (RESULTVIEW_RENDERERS[view.kind] ?? null)
})
const fallbackText = computed(() => {
  const text = resolveViewText((key) => t(key), normalize.value.fallbackText)
  return text || t('resultview.fallback.generic')
})
const errorText = computed(() => {
  const view = resolved.value
  if (view === null) {
    return ''
  }
  return resolveViewText((key) => t(key), view.error?.message) || fallbackText.value
})
const emptyText = computed(() => {
  const view = resolved.value
  if (view === null) {
    return ''
  }
  return resolveViewText((key) => t(key), view.empty?.text ?? view.fallback.text)
})
const rows = computed(() => {
  const view = resolved.value
  return view === null ? [] : (resolveViewRows(view, props.documentText) ?? [])
})
</script>

<template>
  <div v-if="resolved === null || renderer === null" data-testid="resultview-fallback" class="text-[12.4px] leading-[1.6] text-[#57606a] break-words">
    {{ fallbackText }}
  </div>
  <div v-else-if="resolved.status === 'error'" data-testid="resultview-fallback" class="text-[12.4px] leading-[1.6] text-[#57606a] break-words">
    {{ errorText }}
  </div>
  <div v-else-if="resolved.kind === 'table' && resolved.status === 'empty'" data-testid="resultview-empty" class="text-[12.4px] leading-[1.6] text-[#57606a] break-words">
    {{ emptyText }}
  </div>
  <component :is="renderer" v-else :view="resolved" :rows="rows" />
</template>
