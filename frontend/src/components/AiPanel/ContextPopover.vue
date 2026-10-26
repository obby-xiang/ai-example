<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 上下文分项浮层（上下文统计与压缩专项批①；设计稿 §3.4(c)）。
 *
 * <h2>七项、固定顺序</h2>
 * 顺序 = 服务端返回顺序（`history/toolResults/summary/system/tools/input/free`），前端**不排序**
 * —— 按对象键序渲染会让顺序在不同浏览器/不同构建下抖动（C-34）。
 *
 * <h2>守恒说明必须写在界面上</h2>
 * 分项是估算按真值比例归一的（Σ前六项 == 已用），所以这里显式写一行"分项为估算、总量为账单"：
 * 否则用户会拿它跟厂商账单逐项对，然后发现每一项都不等。
 *
 * <h2>三态（批次2）</h2>
 * `loading` / `error` / `data` 由 store 的 `contextStatus` 决定，**不再用"七项为空"冒充加载中**：
 * - loading：还没拿到（或正在重拉），显示加载文案；
 * - error：端点失败 ⇒ 失败文案 + 重试钮；**已有旧数据时数据与失败行并存**（不复用旧数据装作成功）；
 * - data：七项按服务端顺序渲染；`source=none`（会话从未记账）显示"暂无数据"，不显示 0。
 */
import { computed } from 'vue'
import { useAiStore } from '@/stores/ai'
import { formatPartTokens, formatPercent, orderedParts, partLabel } from '@/utils/context-usage'

const { t } = useI18n()

const emit = defineEmits<{ (event: 'close'): void }>()

const ai = useAiStore()

const parts = computed(() => orderedParts(ai.contextBreakdown))
const usage = computed(() => ai.contextUsage)
const hasData = computed(() => parts.value.length > 0)
const loading = computed(() => (ai.contextStatus === 'loading' || ai.contextStatus === 'idle') && !hasData.value)
const failed = computed(() => ai.contextStatus === 'error')
const empty = computed(() => ai.contextStatus === 'ready' && !hasData.value)
const percentText = computed(() => (usage.value?.percent === null || usage.value?.percent === undefined
  ? '—'
  : `${formatPercent(usage.value.percent)}%`))
const limitText = computed(() => (usage.value?.limitTokens === null || usage.value?.limitTokens === undefined
  ? '上限未知'
  : ''))

function retry(): void {
  void ai.retryLoadContext()
}
</script>

<template>
  <div
    data-testid="context-popover"
    role="dialog"
    :aria-label="t('ai.context.detailsAria')"
    class="absolute bottom-full right-0 z-[12] mb-[6px] w-[248px] rounded-[10px] border border-solid border-[#e3e6ea] bg-white p-[10px] text-[11.5px] leading-[1.6] text-[#1f2328] shadow-[0_8px_24px_rgba(0,0,0,0.13)]"
  >
    <div class="mb-[6px] flex items-center gap-[6px]">
      <b class="[font-weight:650]">{{ t('ai.context.title') }}</b>
      <span class="ml-auto tabular-nums text-[#57606a]">{{ percentText }}</span>
    </div>

    <!-- 三态之一：加载中（没有数据才占整块，有旧数据时只在上方补一行失败/加载提示） -->
    <div
      v-if="loading"
      data-testid="context-popover-loading"
      class="py-[6px] text-[#8b949e]"
    >{{ t('ai.context.loading') }}</div>

    <!-- 三态之二：失败（可重试） -->
    <div
      v-else-if="failed && !hasData"
      data-testid="context-popover-error"
      class="flex items-center gap-[8px] py-[6px] text-[#d1242f]"
    >
      <span>{{ t('ai.context.loadFailed') }}</span>
      <button
        type="button"
        data-testid="context-popover-retry"
        class="rounded-[4px] border border-solid border-[#d1242f] bg-transparent px-[6px] py-[1px] text-[11px] text-[#d1242f] hover:bg-[#fff5f5]"
        @click="retry"
      >{{ t('ai.context.retry') }}</button>
    </div>

    <!-- 三态之三：数据（失败但仍有旧数据时，失败行在列表上方并存） -->
    <template v-else>
      <div
        v-if="failed"
        data-testid="context-popover-stale"
        class="mb-[4px] flex items-center gap-[8px] text-[#d1242f]"
      >
        <span>{{ t('ai.context.loadFailed') }}</span>
        <button
          type="button"
          data-testid="context-popover-retry"
          class="rounded-[4px] border border-solid border-[#d1242f] bg-transparent px-[6px] py-[1px] text-[11px] text-[#d1242f] hover:bg-[#fff5f5]"
          @click="retry"
        >{{ t('ai.context.retry') }}</button>
      </div>
      <div
        v-if="empty"
        data-testid="context-popover-empty"
        class="py-[6px] text-[#8b949e]"
      >{{ t('ai.context.empty') }}</div>
      <ul v-else class="m-0 list-none p-0">
        <li
          v-for="part in parts"
          :key="part.key"
          :data-testid="`context-row-${part.key}`"
          class="flex items-center gap-[6px] py-[1.5px]"
        >
          <span class="text-[#57606a]">{{ partLabel(part.key) }}</span>
          <span class="ml-auto tabular-nums" :class="part.key === 'free' && part.tokens < 0 ? 'text-[#d1242f]' : ''">
            {{ formatPartTokens(part.key, part.tokens) }}
          </span>
        </li>
      </ul>
    </template>

    <div class="mt-[6px] border-0 border-t border-solid border-[#eef0f3] pt-[6px] text-[10.5px] leading-[1.6] text-[#8b949e]">
      <div v-if="limitText">{{ t('ai.context.limitOnly', { limit: limitText }) }}</div>
      <div v-if="usage?.est">{{ t('ai.context.estimateNote') }}</div>
      <div>{{ t('ai.context.splitNote') }}</div>
    </div>

    <button
      type="button"
      data-testid="context-popover-close"
      class="absolute right-[6px] top-[6px] h-[18px] w-[18px] rounded-[4px] border-0 bg-transparent text-[12px] leading-[1] text-[#8b949e] hover:bg-[#f6f7f9] hover:text-[#1f2328]"
      :aria-label="t('ai.context.close')"
      @click="emit('close')"
    >×</button>
  </div>
</template>
