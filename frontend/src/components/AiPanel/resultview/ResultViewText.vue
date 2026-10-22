<script setup lang="ts">
/**
 * `kind=text` 渲染器（设计稿 §3.3）：只有一句话的结果。
 *
 * 用途是替换"把 JSON / 明细整串塞进 reason"的历史做法 —— 一句人话 + 必填 fallback，
 * 没有列、没有数据文档、没有展开体。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { resolveViewText } from '@/utils/resultview'
import type { ResultView } from '@/types/resultview'

const props = defineProps<{ view: ResultView }>()

const { t } = useI18n()

const body = computed(() => {
  const text = resolveViewText((key) => t(key), props.view.description ?? '')
    || resolveViewText((key) => t(key), props.view.fallback.text)
  return text || t('resultview.fallback.generic')
})
</script>

<template>
  <div data-testid="resultview-text" class="text-[12.6px] leading-[1.6] text-[#57606a] break-words whitespace-pre-wrap">
    {{ body }}
  </div>
</template>
