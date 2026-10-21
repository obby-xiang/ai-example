<script setup lang="ts">
/**
 * 上下文占用药丸（上下文统计与压缩专项批①；设计稿 §3.4(c)）。
 *
 * <h2>位置（布局裁决）</h2>
 * 放在输入框底栏**右端、发送/停止钮左侧**：`[模型选择器位][参数位] …… [用量 pill][发送/停止]`。
 * 本批只占 pill 位 —— 左端留给模型配置专项批③ 的选择器，两者不得抢同一格。
 *
 * <h2>口径</h2>
 * - 文本与档位全部走 `utils/context-usage.ts` 的纯函数（逐点可测）；
 * - **流式中不更新**：本组件的值来自 store 的 `contextUsage`，它只在 `done` 帧写入（C-36）；
 * - `est` ⇒ 前缀 `≈`（"这个数是估的"必须一眼可见，而不是藏在 tooltip 里）；
 * - 上限未知 ⇒ 只显示已用（没有分母就不给百分比）；
 * - 点击展开七项拆分（按需拉端点，见 ContextPopover）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAiStore } from '@/stores/ai'
import {
  contextPillText,
  contextTier,
  tierClass,
  tierTitle
} from '@/utils/context-usage'
import ContextPopover from '@/components/AiPanel/ContextPopover.vue'

const ai = useAiStore()

const open = ref(false)
const rootRef = ref<HTMLElement | null>(null)

const text = computed(() => contextPillText(ai.contextUsage))
const tier = computed(() => contextTier(ai.contextUsage?.percent ?? null))
const visible = computed(() => text.value.length > 0)

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
      :title="ai.contextUsage ? tierTitle(ai.contextUsage) : ''"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="toggle"
    >{{ text }}</button>
    <ContextPopover v-if="open" @close="open = false" />
  </span>
</template>
