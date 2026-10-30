<script setup lang="ts">
/**
 * 上下文占用圆环（上下文统计与压缩专项批①；设计稿 §3.4(c)）。
 *
 * <h2>为什么只剩一个圆环</h2>
 * 占用是"扫一眼"的状态量，不是要读的数字：默认态一律不出文字
 * （既不出 已用/上限/占比 三件，也不出七项拆分），只留一圈进度；
 * 精确值压到 hover 的一行 tooltip 里（`上下文占用 42%`，估算带「约」）。
 * 点击不展开详情 —— 详情面板（ContextPopover）与药丸的点击逻辑一并退场。
 *
 * <h2>语义源</h2>
 * 视觉是 conic-gradient + mask-image 抠出的空心圆环，语义是原生 `<progress>`：
 * 辅助技术直接读 value/max，不必另挂 role/aria-value*（modern-web-guidance · progress-ring）。
 * 填充色取 `tierClass` 的 text-* 项（档位配色只在这一处定义），轨道色用面板既有的中性色。
 *
 * <h2>口径</h2>
 * - 值来自 store 的 `contextUsage`，它只在 `done` 帧写入（C-36：流式中不跳动）；
 * - `est` ⇒ tooltip 用「约」（这个数是估的必须说出来）；
 * - 上限未知（`percent === null`）⇒ 没有分母就没有占比：不写 `value`（原生不确定态，视觉只剩轨道），
 *   tooltip 只说已用；
 * - 无数据/未测 ⇒ 不渲染（不谎报 0%）。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAiStore } from '@/stores/ai'
import { contextPillText, contextTier, formatPercent, formatTokens, tierClass } from '@/utils/context-usage'

const { t, te } = useI18n()

const ai = useAiStore()

/** 有值才露头：`contextPillText` 的"未测 ⇒ 空串"判定沿用（不谎报 0%）。 */
const visible = computed(() => contextPillText(ai.contextUsage).length > 0)

const tier = computed(() => contextTier(ai.contextUsage?.percent ?? null))

/**
 * 圆环读的就是占比本体。上限未知（`percent === null`）⇒ `null`：
 * 不写 `value` 属性（原生**不确定态**），绝不拿 0 冒充"占用为零"。
 */
const value = computed<number | null>(() => {
  const percent = ai.contextUsage?.percent
  if (percent === null || percent === undefined || !Number.isFinite(percent)) {
    return null
  }
  return Math.min(100, Math.max(0, percent))
})

/** 档位配色只取文字色：圆环用 currentColor 填，边框/底色在圆环上没有意义。 */
const ringClass = computed(
  () => tierClass(tier.value).split(/\s+/).find((name) => name.startsWith('text-')) ?? ''
)

/** 缺键回落内置中文（与批 E · E3 的文案出口同一惯例）。 */
function contextText(key: string, params: Record<string, string>, fallback: string): string {
  const i18nKey = `ai.context.${key}`
  return te(i18nKey) ? t(i18nKey, params) : fallback
}

/** 唯一的一行：`上下文占用 42%`；估算带「约」，上限未知只说已用。 */
const tooltip = computed(() => {
  const usage = ai.contextUsage
  if (!usage) {
    return ''
  }
  if (usage.percent === null) {
    const used = `${usage.est ? '≈' : ''}${formatTokens(usage.usedTokens)}`
    return contextText('used', { used }, `上下文已用 ${used} tokens`)
  }
  const percent = formatPercent(usage.percent)
  if (usage.est) {
    return contextText('pillUsageEst', { percent }, `上下文占用 约${percent}%`)
  }
  return contextText('pillAria', { percent }, `上下文占用 ${percent}%`)
})
</script>

<template>
  <progress
    v-if="visible"
    data-testid="context-pill"
    class="context-ring flex-none"
    :class="ringClass"
    :data-tier="tier"
    max="100"
    :value="value ?? undefined"
    :style="{ '--context-ring-value': value ?? 0 }"
    :title="tooltip"
    :aria-label="tooltip"
  ></progress>
</template>

<style scoped>
/* 原生进度条外观整体让位给自绘圆环；语义（value/max/role）不受影响。 */
.context-ring {
  display: block;
  width: 14px;
  height: 14px;
  border: 0;
  border-radius: 50%;
  appearance: none;
  -webkit-appearance: none;
  /* 空心圆环：conic-gradient 画弧段，mask 抠掉圆心（不依赖 background-clip: border-area）。 */
  background-image: conic-gradient(currentColor calc(var(--context-ring-value, 0) * 1%), #e3e6ea 0);
  mask-image: radial-gradient(closest-side, transparent calc(100% - 2.5px), #000 calc(100% - 2.5px + 0.5px));
  -webkit-mask-image: radial-gradient(closest-side, transparent calc(100% - 2.5px), #000 calc(100% - 2.5px + 0.5px));
}

/* 各引擎的默认填充一律透明：视觉只由上面的 conic-gradient 决定。 */
.context-ring::-webkit-progress-bar {
  background: transparent;
}

.context-ring::-webkit-progress-value {
  background: transparent;
}

.context-ring::-moz-progress-bar {
  background: transparent;
}

/* 不确定态（没有 value）：没有占比可画 ⇒ 只剩轨道；同时关掉引擎自带的流动条纹。 */
.context-ring:indeterminate::-webkit-progress-bar,
.context-ring:indeterminate::-webkit-progress-value {
  display: none;
  background: none;
}

.context-ring:indeterminate::-moz-progress-bar {
  display: none;
  background: none;
}
</style>
