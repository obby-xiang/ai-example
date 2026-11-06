<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { reasoningElapsedMs } from '@/utils/parts'
import { cardIconPaths } from '@/components/AiPanel/cardPresentation'
import type { ReasoningPart } from '@/types/ai'

const props = defineProps<{
  part: ReasoningPart
  blockId: string
  expanded: boolean
  truncatedTitle: string
}>()

const emit = defineEmits<{ toggle: [] }>()
const { t } = useI18n()
const iconPaths = cardIconPaths('reasoning')
const nowMs = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

const live = computed(() => props.part.streaming && props.part.endedAt === undefined)

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

function startTicker(): void {
  stopTicker()
  nowMs.value = Date.now()
  if (!live.value || props.part.startedAt === undefined) {
    return
  }
  ticker = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
}

watch(() => [live.value, props.part.startedAt] as const, startTicker, { immediate: true })
onBeforeUnmount(stopTicker)

const elapsedSeconds = computed(() => {
  const elapsed = reasoningElapsedMs(props.part, nowMs.value)
  return elapsed === null ? null : Math.max(0, Math.floor(elapsed / 1000))
})

const timerText = computed(() => {
  const seconds = elapsedSeconds.value
  return seconds === null ? null : t('ai.panel.thinkingSeconds', { seconds })
})

const titleText = computed(() => t(live.value ? 'ai.panel.thinkingLive' : 'ai.panel.thinking'))
const bodyId = computed(() => `reasoning-body-${props.blockId}`)
</script>

<template>
  <button
    type="button"
    class="flex w-full items-center gap-[6px] whitespace-nowrap border-0 bg-transparent px-[10px] py-[8px] text-left font-[inherit] transition-colors duration-[150ms] hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#2563eb]/40 motion-reduce:transition-none"
    :data-testid="'reasoning-toggle-' + blockId"
    :aria-expanded="expanded"
    :aria-controls="bodyId"
    @click="emit('toggle')"
  >
    <span class="flex h-[24px] w-[24px] flex-none items-center justify-center text-[#8b949e]" aria-hidden="true">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" focusable="false">
        <path v-for="path in iconPaths" :key="path" :d="path" />
      </svg>
    </span>
    <span class="min-w-0 flex-1 truncate text-[12px] font-medium leading-[1.7] text-[#57606a]" :data-testid="'reasoning-title-' + blockId">{{ titleText }}</span>
    <span
      v-if="timerText !== null"
      class="flex-none text-[11px] leading-[1.7] text-[#8b949e]"
      :data-testid="'reasoning-timer-' + blockId"
      aria-live="off"
    >{{ timerText }}</span>
    <span
      v-if="part.truncated"
      class="inline-block h-[7px] w-[7px] flex-none rounded-full bg-[#8b949e]"
      role="img"
      :aria-label="t('ai.panel.truncated')"
      :title="truncatedTitle"
      data-testid="reasoning-truncated-badge"
    ></span>
    <svg
      class="h-[12px] w-[12px] flex-none text-[#8b949e] transition-transform duration-[180ms] motion-reduce:transition-none"
      :class="expanded ? 'rotate-90' : ''"
      viewBox="0 0 12 12"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    ><path d="m4.5 2.5 3.5 3.5-3.5 3.5" /></svg>
  </button>
</template>
