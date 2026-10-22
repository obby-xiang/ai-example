<template>
  <div class="border border-solid border-[#e3e6ea] rounded-[8px] bg-[#fbfcfd] px-[10px] py-[9px]" data-testid="sheet-preview-card">
    <template v-if="plan === null">
      <div class="text-[12.6px] leading-[1.6] text-[#c0392b]" data-testid="sheet-preview-stale">
        {{ t('ai.sheetCard.expired') }}
      </div>
      <div class="mt-[9px] flex justify-end">
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
          data-testid="sheet-preview-abandon"
          @click="emit('abandon')"
        >{{ t('ai.sheetCard.discard') }}</button>
      </div>
    </template>

    <template v-else>
      <div class="text-[12.8px] leading-[1.6] text-[#1f2328]" data-testid="sheet-preview-summary">
        {{ t('ai.sheetCard.impact', { rows: plan.rowsCount, cells: plan.cellsCount }) }}
      </div>
      <div class="mt-[3px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-scope">
        {{ t('ai.sheetCard.scope', { scope: plan.scopeSummary }) }}<span v-if="plan.predicateSummary"> · {{ plan.predicateSummary }}</span>
      </div>
      <div class="mt-[2px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-rules">
        {{ t('ai.sheetCard.rules', { rules: plan.rulesText }) }}
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
        <span v-if="plan.skippedCount > 0" class="ml-auto text-[11.5px] text-[#8b949e]">{{ t('ai.sheetCard.skipped', { count: plan.skippedCount }) }}</span>
      </div>

      <div v-if="plan.preview === 'count'" class="mt-[7px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-count">
        {{ t('sheet.preview.countLevel') }}
      </div>
      <!-- 计数档分布（AI 表格工具治理 §6.4）：一行式、可折行；长 key 截断 12 字符 + title 全量。
           样式沿用卡内既有灰度文字，不新增颜色语义（避免与状态色四族冲突）。 -->
      <div
        v-if="plan.preview === 'count' && distributionGroups.length > 0"
        class="mt-[4px] text-[11.9px] leading-[1.6] text-[#57606a]"
        data-testid="sheet-preview-distribution"
      >
        <span>{{ t('ai.sheetCard.distribution', { by: distribution!.byLabel || distribution!.by, count: distributionGroups.length }) }}</span>
        <span
          v-for="(group, index) in distributionGroups"
          :key="group.key"
          :data-testid="'sheet-preview-dist-' + group.key"
          :title="group.key"
        >{{ index > 0 ? ' · ' : '' }}{{ truncateKey(group.key) }} {{ t('ai.sheetCard.groupItem', { rows: group.rows, cells: group.cells }) }}</span>
        <span v-if="(distribution?.otherGroups ?? 0) > 0"> · {{ t('sheet.preview.distributionOthers', { count: distribution?.otherGroups }) }}</span>
        <span v-if="distribution?.truncated">（{{ t('sheet.preview.distributionTruncated') }}）</span>
      </div>
      <!-- 三档各自独立（不用 v-else-if 链）：中间夹了分布块，链式判定会被前一个 v-if 抢走配对 -->
      <div v-if="plan.preview === 'sample-diff'" class="mt-[7px]" data-testid="sheet-preview-sample">
        <div
          v-for="(change, index) in plan.samples"
          :key="index"
          class="text-[11.9px] leading-[1.7] text-[#57606a]"
        ><span :class="CELL_CHIP" :data-testid="'sheet-preview-cell'">{{ addressOf(change) }}</span>{{ t('ai.sheetCard.rowCol', { row: change.row + 2, col: change.col + 1, from: display(change.oldValue), to: display(change.newValue) }) }}</div>
        <div v-if="plan.samples.length === 0" class="text-[11.9px] text-[#8b949e]">{{ t('ai.sheetCard.noMatches') }}</div>
      </div>
      <div v-if="plan.preview === 'full-diff'" class="mt-[7px]" data-testid="sheet-preview-full">
        <div class="max-h-[min(240px,32vh)] overflow-y-auto border border-solid border-[#eef0f3] rounded-[6px] bg-white px-[8px] py-[4px]">
          <div
            v-for="(change, index) in pageChanges"
            :key="index"
            class="text-[11.7px] leading-[1.7] text-[#57606a]"
          ><span :class="CELL_CHIP" :data-testid="'sheet-preview-cell'">{{ addressOf(change) }}</span>第 {{ change.row + 2 }} 行 列 {{ change.col + 1 }}「{{ display(change.oldValue) }}」→「{{ display(change.newValue) }}」</div>
        </div>
        <div class="mt-[5px] flex items-center gap-[8px] text-[11.5px] text-[#8b949e]">
          <button type="button" class="border-0 bg-transparent p-0 text-[#57606a] disabled:text-[#c0c4cc]" :disabled="page === 0" @click="page -= 1">{{ t('ai.sheetCard.prevPage') }}</button>
          <span>{{ t('ai.sheetCard.page', { page: page + 1, total: pageCount, count: plan.changes.length }) }}</span>
          <button type="button" class="border-0 bg-transparent p-0 text-[#57606a] disabled:text-[#c0c4cc]" :disabled="page >= pageCount - 1" @click="page += 1">{{ t('ai.sheetCard.nextPage') }}</button>
        </div>
      </div>

      <!-- L-23 预览态高亮的图例：表里那层浅琥珀色只画屏幕，不进导出文件（导出链只读值） -->
      <div
        v-if="plan.preview !== 'count' && plan.detail !== 'none'"
        class="mt-[5px] flex items-center gap-[5px] text-[11.5px] leading-[1.6] text-[#8b949e]"
        data-testid="sheet-preview-highlight-legend"
      >
        <span class="inline-block h-[9px] w-[9px] shrink-0 rounded-[2px] bg-[#fff4e0] ring-1 ring-inset ring-[#e6d3b3]"></span>
        <span>{{ t('sheet.preview.highlightLegend') }}</span>
      </div>

      <!-- 明细未加载（计数档）：独立块放在三档链之外 —— 插在链中间会改写 v-else-if 的配对 -->
      <div
        v-if="plan.detail === 'none'"
        class="mt-[4px] text-[11.5px] leading-[1.6] text-[#8b949e]"
        data-testid="sheet-preview-detail-pending"
      >{{ t('sheet.preview.detailPending') }}</div>

      <div
        v-for="(warning, index) in plan.warnings"
        :key="'w' + index"
        class="mt-[7px] rounded-[6px] border border-solid border-[#f0d5d0] bg-[#fdf1ef] px-[8px] py-[5px] text-[11.7px] leading-[1.6] text-[#c0392b]"
        data-testid="sheet-preview-warning"
      >{{ warning }}</div>

      <label v-if="plan.level === 'large'" class="mt-[8px] flex items-center gap-[6px] text-[11.9px] text-[#57606a]" data-testid="sheet-preview-ack">
        <input v-model="acknowledged" type="checkbox" data-testid="sheet-preview-ack-box" />
        {{ t('ai.sheetCard.ackLarge', { rows: plan.rowsCount, cells: plan.cellsCount }) }}
      </label>

      <div class="mt-[9px] flex items-center justify-end gap-[8px]">
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328]"
          data-testid="sheet-preview-cancel"
          @click="emit('cancel')"
        >{{ t('ai.sheetCard.cancel') }}</button>
        <button
          type="button"
          class="rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent bg-[#2563eb] font-semibold text-white hover:brightness-[1.08] disabled:bg-[#8b949e] disabled:font-normal"
          :disabled="plan.level === 'large' && !acknowledged"
          data-testid="sheet-preview-apply"
          @click="emit('apply', acknowledged)"
        >{{ t('ai.sheetCard.apply') }}</button>
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
import { useI18n } from 'vue-i18n'
import type { SheetPreviewLevel, SheetWritePlan } from '@/types/ai'
import { cellAddress } from '@/utils/sheet-tools'

const props = defineProps<{
  plan: SheetWritePlan | null
}>()

const emit = defineEmits<{
  (event: 'apply', acknowledged: boolean): void
  (event: 'cancel'): void
  (event: 'abandon'): void
  (event: 'level', level: SheetPreviewLevel): void
}>()

const { t } = useI18n()

/** 分布块（无命中/无分组列 ⇒ null ⇒ 不渲染空块，§6.3）。 */
const distribution = computed(() => props.plan?.distribution ?? null)
/** 渲染开关与数据同源：模板里只用 `groups`，避免"有块没组"的半渲染。 */
const distributionGroups = computed(() => distribution.value?.groups ?? [])

/** 长 key 截断到 12 字符（全量走 title，避免卡片被一个长业务值撑开）。 */
function truncateKey(key: string): string {
  return key.length > 12 ? `${key.slice(0, 12)}…` : key
}

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

/** 逐格地址片（L-23）：让「我改了 B3」在卡上直接可核对，颜色与表里预览底色同族。 */
const CELL_CHIP =
  'mr-[5px] inline-block rounded-[4px] bg-[#fff4e0] px-[4px] py-[1px] text-[11px] font-medium text-[#8a5a00] tabular-nums'

function addressOf(change: { row: number; col: number }): string {
  return cellAddress(change.col, change.row)
}

function display(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
</script>
