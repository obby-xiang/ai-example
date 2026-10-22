<script setup lang="ts">
/**
 * 上下文占用药丸（上下文统计与压缩专项批①；设计稿 §3.4(c)）。
 *
 * <h2>位置（布局裁决）</h2>
 * 放在输入框底栏**右端、发送/停止钮左侧**：`[参数位] …… [用量 pill][发送/停止]`。
 * 批 E · E1 起模型选择器整段退场，pill 的位置与顺序不变。
 *
 * <h2>批 E · E3 补充</h2>
 * 指标是**整体上下文占用**（本轮请求的 prompt 侧真值 = 整个窗口），不是"每轮新增"；
 * hover tooltip 同时给出 已用 / 上限 / 占比，文案双语（`ai.context.*`，缺键回落内置中文）。
 * 
 * <h2>口径</h2>
 * - 文本与档位全部走 `utils/context-usage.ts` 的纯函数（逐点可测）；
 * - **流式中不更新**：本组件的值来自 store 的 `contextUsage`，它只在 `done` 帧写入（C-36）；
 * - `est` ⇒ 前缀 `≈`（"这个数是估的"必须一眼可见，而不是藏在 tooltip 里）；
 * - 上限未知 ⇒ 只显示已用（没有分母就不给百分比）；
 * - 点击展开七项拆分（按需拉端点，见 ContextPopover）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiStore } from '@/stores/ai'
import {
  contextPillText,
  contextTier,
  defaultContextText,
  formatPercent,
  tierClass,
  tierTitle,
  type ContextTextFn
} from '@/utils/context-usage'
import ContextPopover from '@/components/AiPanel/ContextPopover.vue'

const { t, te } = useI18n()

const ai = useAiStore()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const text = computed(() => contextPillText(ai.contextUsage))
const tier = computed(() => contextTier(ai.contextUsage?.percent ?? null))
const visible = computed(() => text.value.length > 0)

/**
 * 双语文案出口（批 E · E3）：有 `ai.context.*` 键就按当前语言渲染，缺键回落内置中文。
 * tooltip 三件齐全 —— 已用 / 上限 / 占比（整体占用，不是每轮增量）。
 */
const contextText: ContextTextFn = (key, params) => {
  const i18nKey = `ai.context.${key}`
  return te(i18nKey) ? t(i18nKey, params) : defaultContextText(key, params)
}

const tooltip = computed(() => (ai.contextUsage ? tierTitle(ai.contextUsage, contextText) : ''))

const ariaLabel = computed(() => {
  const usage = ai.contextUsage
  if (!usage) {
    return ''
  }
  if (usage.percent === null) {
    return t('ai.context.pillAriaNoPercent', { text: text.value })
  }
  return t('ai.context.pillAria', { percent: formatPercent(usage.percent) })
})

function toggle(): void {
  open.value = !open.value
  if (open.value) {
    // 打开即拉一次端点：七项拆分不进 done 帧（每轮都发会把帧撑大），按需取
    void ai.loadContext()
  }
}

/** 点外部/Esc 收起（与面板其它浮层的交互惯例一致）。 */
function onDocumentPointerDown(event: MouseEvent): void {
  if (!open.value) {
    return
  }
  const target = event.target as Node | null
  if (rootRef.value && target && rootRef.value.contains(target)) {
    return
  }
  open.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && open.value) {
    open.value = false
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentPointerDown)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentPointerDown)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <span v-if="visible" ref="rootRef" class="relative flex-none">
    <button
      type="button"
      data-testid="context-pill"
      :data-tier="tier"
      class="rounded-[6px] border border-solid px-[6px] py-[1px] text-[10.5px] leading-[1.6] tabular-nums transition-colors duration-[150ms] hover:brightness-[0.98]"
      :class="tierClass(tier)"
      :title="tooltip"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
    >{{ text }}</button>
    <ContextPopover v-if="open" @close="open = false" />
  </span>
</template>
