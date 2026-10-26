<script setup lang="ts">
/**
 * 批次6A / AR-12：思考卡卡头（图标 + 「思考过程」+ 字数摘要 + 已用时长 + 截断角标 + chevron）。
 *
 * <p>为什么单独一个组件：秒表要每秒推进，但**只有这一小块**该重渲染 —— 长思考往往出现在长
 * 会话里，让整个 AiPanel 每秒重建一次代价过高（与 CompactionStatus 同一取舍）。计时口径：
 * 服务端两端时刻（`startedAt` / `endedAt` / `lastFrameAt`）出自帧 `atMs`；实时推进用收帧锚
 * `lastFrameLocalAt` 做钟差换算（见 `utils/parts.ts` 的 `reasoningElapsedMs`），**刷新/离线
 * 期间也计入**；`endedAt` 后静止；没有起点（历史已完成轮 / 旧帧）就不显示时长，绝不按当前
 * 时刻回算。
 *
 * <p>播报口径：秒数文本 `aria-live="off"`（不逐秒打断读屏）；折叠控件的 `aria-expanded` /
 * `aria-controls` 指向展开体（正文节点由 AiPanel 渲染，id 与本组件的 `bodyId` 同源）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { reasoningElapsedMs } from '@/utils/parts'
import type { ReasoningPart } from '@/types/ai'

const props = defineProps<{
  part: ReasoningPart
  blockId: string
  expanded: boolean
  /** 截断原因（面板的 `truncationTitle` 入参：按 noticeCode 本地化，旧帧回落原文）。 */
  truncatedTitle: string
}>()

const emit = defineEmits<{ toggle: [] }>()

const { t } = useI18n()

const nowMs = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

/** 仍在收帧的思考段才会走本地秒表（封口后静态，历史段连起点都没有）。 */
const live = computed(() => props.part.streaming
  && props.part.startedAt !== undefined
  && props.part.endedAt === undefined)

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

function startTicker(): void {
  stopTicker()
  nowMs.value = Date.now()
  if (!live.value) {
    return
  }
  ticker = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
}

watch(live, startTicker, { immediate: true })
onBeforeUnmount(stopTicker)

const elapsedSeconds = computed(() => {
  const elapsed = reasoningElapsedMs(props.part, nowMs.value)
  return elapsed === null ? null : Math.max(0, Math.floor(elapsed / 1000))
})

const timerText = computed(() => {
  const seconds = elapsedSeconds.value
  if (seconds === null) {
    return null
  }
  return t(live.value ? 'ai.panel.thinkingRunning' : 'ai.panel.thinkingSeconds', { seconds })
})

const summaryText = computed(() => {
  const action = props.expanded ? t('ai.panel.thinkingCollapse') : t('ai.panel.thinkingExpand')
  return `${t('ai.panel.thinkingChars', { n: props.part.text.length })} · ${action}`
})

const bodyId = computed(() => `reasoning-body-${props.blockId}`)

/** 与 AiPanel 卡头 chevron 同一配方（TCHEV_CLASS 的逐字副本：本组件不依赖面板内部常量）。 */
const CHEVRON_CLASS = 'tchev flex-none inline-block text-[#8b949e] text-[12px] transition-transform duration-[180ms] [&.is-open]:rotate-90'
</script>

<template>
  <button
    type="button"
    class="flex items-center gap-[8px] w-full border-0 bg-transparent px-[10px] py-[7px] text-left transition-colors duration-[150ms] hover:bg-white"
    :data-testid="'reasoning-toggle-' + blockId"
    :aria-expanded="expanded"
    :aria-controls="bodyId"
    @click="emit('toggle')"
  >
    <span class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.2px] border-[#e3e6ea] text-[#8b949e] text-[9px] flex items-center justify-center" aria-hidden="true">⋆</span>
    <span class="text-[12.4px] text-[#57606a]">{{ t('ai.panel.thinking') }}</span>
    <span class="flex-1 text-[11.5px] text-[#8b949e]" :data-testid="'reasoning-summary-' + blockId">{{ summaryText }}</span>
    <span
      v-if="timerText !== null"
      class="flex-none text-[11.5px] text-[#8b949e]"
      :data-testid="'reasoning-timer-' + blockId"
      aria-live="off"
    >{{ timerText }}</span>
    <span
      v-if="part.truncated"
      class="inline-block h-[7px] w-[7px] rounded-full bg-[#8b949e]"
      role="img"
      :aria-label="t('ai.panel.truncated')"
      :title="truncatedTitle"
      data-testid="reasoning-truncated-badge"
    ></span>
    <span :class="[CHEVRON_CLASS, expanded ? 'is-open' : '']">›</span>
  </button>
</template>
