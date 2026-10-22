<script setup lang="ts">
/**
 * `kind=table` 渲染器（设计稿 §3.4 / §4-规则 6 / §7.2-7）。
 *
 * 用**原生 `<table>`** 而不是 div 网格：`caption` + `th scope="col"` 是 a11y 的硬要求（§4-规则 6），
 * 原生语义直接满足，屏幕阅读器与键盘导航都不需要额外 ARIA。
 *
 * 视觉语汇沿用仓内既有表格卡（底色 `#f6f7f9` / 分隔线 `#eef0f3` / 表体长表格族 `min(480px,60vh)` 限高内滚），
 * 全部 Tailwind 类，无手写 style。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { SCROLLBAR_CLASS } from '@/utils/markdown'
import { resolveViewText } from '@/utils/resultview'
import type { ResultView, ResultViewColumn } from '@/types/resultview'

const props = defineProps<{
  view: ResultView
  /** 已解析的行（`data.inline` 或 `data.ref` 求值结果） */
  rows: Record<string, unknown>[]
}>()

const { t } = useI18n()

const visibleColumns = computed<ResultViewColumn[]>(() => (props.view.columns ?? []).filter((column) => !column.hidden))

const caption = computed(() => {
  const explicit = props.view.a11y?.label ?? props.view.title
  const text = resolveViewText((key) => t(key), explicit)
  return text || t('resultview.table.caption')
})

const truncatedNote = computed(() => {
  const truncated = props.view.meta?.truncated
  if (!truncated) {
    return ''
  }
  return t('resultview.truncated', { total: truncated.totalRows, shown: truncated.shownRows })
})

function columnLabel(column: ResultViewColumn): string {
  return resolveViewText((key) => t(key), column.label) || column.key
}

function cellText(row: Record<string, unknown>, column: ResultViewColumn): string {
  const value = row[column.key]
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'boolean') {
    return value ? t('resultview.boolean.true') : t('resultview.boolean.false')
  }
  if ((column.type === 'enum' || column.type === 'badge') && column.options) {
    const matched = column.options.find((option) => option.value === String(value))
    if (matched) {
      return resolveViewText((key) => t(key), matched.label) || matched.label
    }
  }
  return String(value)
}
</script>

<template>
  <div class="border border-solid border-[#eef0f3] rounded-[8px] overflow-hidden">
    <div :class="['max-h-[min(480px,60vh)] overflow-auto', SCROLLBAR_CLASS]">
      <table
        class="w-full border-separate border-spacing-0 text-[12.2px] leading-[1.5] text-[#1f2328]"
        data-testid="resultview-table"
        :aria-label="caption"
      >
        <caption class="sr-only">{{ caption }}</caption>
        <thead>
          <tr>
            <th
              v-for="column in visibleColumns"
              :key="column.key"
              scope="col"
              class="bg-[#f6f7f9] px-[9px] py-[5px] text-left text-[11.4px] [font-weight:650] text-[#57606a] whitespace-nowrap border-0 border-b border-solid border-[#e3e6ea]"
            >
              {{ columnLabel(column) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in rows" :key="index" data-testid="resultview-row">
            <td
              v-for="column in visibleColumns"
              :key="column.key"
              class="px-[9px] py-[6px] align-top break-words border-0 border-b border-solid border-[#eef0f3]"
            >
              {{ cellText(row, column) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="truncatedNote"
      data-testid="resultview-truncated"
      class="px-[9px] py-[5px] bg-[#f6f7f9] border-0 border-t border-solid border-[#e3e6ea] text-[11.4px] text-[#57606a]"
    >
      {{ truncatedNote }}
    </div>
  </div>
</template>
