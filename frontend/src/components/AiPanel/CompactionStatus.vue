<script setup lang="ts">
/**
 * B4：顶部「准备中」阶段条（原压缩/准备中两态组件）。
 *
 * <p>压缩过程态已下沉到消息元信息行，本组件只保留 `preparing`（准备中 + 立即重试）；
 * 压缩的 compacting/done 分支与词条随 B4 删除。
 *
 * <p>为什么单独一个组件：秒数要每秒推进，但**只有这一小块**该重渲染。这里把本地时钟关在
 * 组件内，面板其余部分零影响。
 *
 * <p>播报口径（"不逐秒播报"）：秒数文本**不是** live region 的后代；旁边的 `aria-live` 只随
 * 阶段变化播一次，不随 N 秒重复念。只显示真实已用时间，不编造进度/百分比。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

type StageMode = 'preparing'

const props = defineProps<{
  mode: StageMode
  startedAtMs: number | null
  elapsedMs: number | null
}>()

const emit = defineEmits<{ retry: [] }>()

const { t } = useI18n()

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

function startTicker(): void {
  stopTicker()
  now.value = Date.now()
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

watch(() => [props.mode, props.startedAtMs] as const, startTicker, { immediate: true })
onBeforeUnmount(stopTicker)

const seconds = computed(() => {
  const fallback = props.elapsedMs ?? 0
  if (props.startedAtMs === null) {
    return Math.max(0, Math.floor(fallback / 1000))
  }
  return Math.max(0, Math.floor((now.value - props.startedAtMs) / 1000))
})

const titleKey = 'ai.panel.preparing'
const ariaKey = 'ai.panel.preparingAria'
</script>

<template>
  <div
    class="mx-2 mt-2 flex items-center gap-[8px] rounded-[9px] border border-solid border-[#dbeafe] bg-[#eff6ff] px-[11px] py-[7px]"
    data-testid="compaction-status"
    :data-stage="mode"
  >
    <span class="w-[7px] h-[7px] flex-none rounded-full bg-[#2563eb] animate-breathe" aria-hidden="true"></span>
    <div class="min-w-0 flex-1">
      <div class="text-[12.4px] leading-[1.5] text-[#1f2328]" data-testid="compaction-status-text">
        {{ t(titleKey, { seconds }) }}
      </div>
      <div class="text-[11.5px] leading-[1.5] text-[#57606a]" data-testid="compaction-status-hint">
        {{ t(`ai.panel.${mode}Hint`) }}
      </div>
    </div>
    <button
      v-if="mode === 'preparing'"
      type="button"
      class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
      data-testid="compaction-retry"
      @click="emit('retry')"
    >
      {{ t('ai.panel.preparingRetry') }}
    </button>
    <!-- 读屏只在阶段变化时播一次；秒数不在 live region 内（不逐秒打断） -->
    <span class="sr-only" aria-live="polite" data-testid="compaction-aria">{{ t(ariaKey) }}</span>
  </div>
</template>
