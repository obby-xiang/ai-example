<template>
  <div class="border border-solid border-[#e3e6ea] rounded-[8px] bg-[#fbfcfd] px-[10px] py-[9px]" data-testid="sheet-preview-card">
    <template v-if="plan === null">
      <div class="text-[12.6px] leading-[1.6] text-[#c0392b]" data-testid="sheet-preview-stale">
        预览已失效（页面刷新导致），本次未改动任何单元格。请让 AI 重新发起。
      </div>
      <div class="mt-[9px] flex justify-end">
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
          data-testid="sheet-preview-abandon"
          @click="emit('abandon')"
        >放弃</button>
      </div>
    </template>

    <template v-else>
      <div class="text-[12.8px] leading-[1.6] text-[#1f2328]" data-testid="sheet-preview-summary">
        将影响 {{ plan.rowsCount }} 行 / {{ plan.cellsCount }} 格
      </div>
      <div class="mt-[3px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-scope">
        范围：{{ plan.scopeSummary }}<span v-if="plan.predicateSummary"> · {{ plan.predicateSummary }}</span>
      </div>
      <div class="mt-[2px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-rules">
        规则：{{ plan.rulesText }}
      </div>

      <div class="mt-[7px] flex items-center gap-[6px]" data-testid="sheet-preview-levels">
        <button
          v-for="option in LEVELS"
          :key="option.value"
          type="button"
          :class="levelClass(option.value)"
          :data-testid="'sheet-preview-level-' + option.value"
          @click="emit('level', option.value)"
        >{{ option.label }}</button>
        <span v-if="plan.skippedCount > 0" class="ml-auto text-[11.5px] text-[#8b949e]">跳过 {{ plan.skippedCount }} 格（校验器拒绝）</span>
      </div>

      <div v-if="plan.preview === 'count'" class="mt-[7px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-count">
        计数级预览：只回计数与分布，不回业务行原文。点“看样例”可查看前 3 条。
      </div>
      <div v-else-if="plan.preview === 'sample-diff'" class="mt-[7px]" data-testid="sheet-preview-sample">
        <div
          v-for="(change, index) in plan.samples"
          :key="index"
          class="text-[11.9px] leading-[1.7] text-[#57606a]"
        >第 {{ change.row + 2 }} 行 列 {{ change.col + 1 }}「{{ display(change.oldValue) }}」→「{{ display(change.newValue) }}」</div>
        <div v-if="plan.samples.length === 0" class="text-[11.9px] text-[#8b949e]">没有匹配的单元格。</div>
      </div>
      <div v-else class="mt-[7px]" data-testid="sheet-preview-full">
        <div class="max-h-[min(240px,32vh)] overflow-y-auto border border-solid border-[#eef0f3] rounded-[6px] bg-white px-[8px] py-[4px]">
          <div
            v-for="(change, index) in pageChanges"
            :key="index"
            class="text-[11.7px] leading-[1.7] text-[#57606a]"
          >第 {{ change.row + 2 }} 行 列 {{ change.col + 1 }}「{{ display(change.oldValue) }}」→「{{ display(change.newValue) }}」</div>
        </div>
        <div class="mt-[5px] flex items-center gap-[8px] text-[11.5px] text-[#8b949e]">
          <button type="button" class="border-0 bg-transparent p-0 text-[#57606a] disabled:text-[#c0c4cc]" :disabled="page === 0" @click="page -= 1">上一页</button>
          <span>第 {{ page + 1 }} / {{ pageCount }} 页（共 {{ plan.changes.length }} 处）</span>
          <button type="button" class="border-0 bg-transparent p-0 text-[#57606a] disabled:text-[#c0c4cc]" :disabled="page >= pageCount - 1" @click="page += 1">下一页</button>
        </div>
      </div>

      <div
        v-for="(warning, index) in plan.warnings"
        :key="'w' + index"
        class="mt-[7px] rounded-[6px] border border-solid border-[#f0d5d0] bg-[#fdf1ef] px-[8px] py-[5px] text-[11.7px] leading-[1.6] text-[#c0392b]"
        data-testid="sheet-preview-warning"
      >{{ warning }}</div>

      <label v-if="plan.level === 'large'" class="mt-[8px] flex items-center gap-[6px] text-[11.9px] text-[#57606a]" data-testid="sheet-preview-ack">
        <input v-model="acknowledged" type="checkbox" data-testid="sheet-preview-ack-box" />
        我了解影响范围（本次将修改 {{ plan.rowsCount }} 行 / {{ plan.cellsCount }} 格，超过 200 行）
      </label>

      <div class="mt-[9px] flex items-center justify-end gap-[8px]">
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
          data-testid="sheet-preview-cancel"
          @click="emit('cancel')"
        >取消</button>
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent bg-[#2563eb] font-semibold text-white hover:brightness-[1.08] disabled:bg-[#8b949e] disabled:font-normal"
          :disabled="plan.level === 'large' && !acknowledged"
          data-testid="sheet-preview-apply"
          @click="emit('apply', acknowledged)"
        >应用</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 写预览卡（设计稿 §3.3bis）：档位计数 / 采样 diff / 全量 diff 三档。
 *
 * 关键红线：`plan.changes` 只在浏览器渲染，**永不上送**（P2 全量 diff = 0 行经模型）；
 * 大批量（>200 行）必须勾选“我了解影响范围”后“应用”才可用。
 */
import { computed, ref, watch } from 'vue'
import type { SheetPreviewLevel, SheetWritePlan } from '@/types/ai'

const props = defineProps<{
  plan: SheetWritePlan | null
}>()

const emit = defineEmits<{
  (event: 'apply', acknowledged: boolean): void
  (event: 'cancel'): void
  (event: 'abandon'): void
  (event: 'level', level: SheetPreviewLevel): void
}>()

const LEVELS: Array<{ value: SheetPreviewLevel; label: string }> = [
  { value: 'count', label: '计数' },
  { value: 'sample-diff', label: '看样例' },
  { value: 'full-diff', label: '全量 diff' }
]

const PAGE_SIZE = 50
const page = ref(0)
const acknowledged = ref(false)

const pageCount = computed(() => {
  const total = props.plan?.changes.length ?? 0
  return Math.max(1, Math.ceil(total / PAGE_SIZE))
})

const pageChanges = computed(() => {
  const changes = props.plan?.changes ?? []
  return changes.slice(page.value * PAGE_SIZE, (page.value + 1) * PAGE_SIZE)
})

watch(() => props.plan?.planId, () => {
  page.value = 0
  acknowledged.value = false
})

function levelClass(level: SheetPreviewLevel): string {
  const active = props.plan?.preview === level
  return [
    'rounded-[6px] px-[8px] py-[3px] text-[11.6px] border border-solid transition-colors duration-[150ms]',
    active ? 'border-[#2563eb] bg-[#eff4ff] text-[#2563eb]' : 'border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e]'
  ].join(' ')
}

function display(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
</script>
