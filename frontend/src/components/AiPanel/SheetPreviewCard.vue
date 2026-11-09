<template>
  <div class="min-w-0" data-testid="sheet-preview-card">
    <BusinessCardHeader
      icon="sheet"
      :title="headerTitle"
      :status="statusLabel"
      :tone="statusTone"
      :expanded="expanded"
      :controls="bodyId"
      @toggle="expanded = !expanded"
    />
    <span class="sr-only" role="status" aria-live="polite">{{ statusLabel }}</span>

    <div :id="bodyId" v-show="expanded" class="px-[10px] pb-[10px]">
      <div v-if="appliedSummary" class="mb-[8px] text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-applied-summary">
        <div>{{ appliedLine }}</div>
        <div v-if="(appliedSummary.manualChanges ?? 0) > 0" class="mt-[2px] text-[#8b949e]">
          {{ t('sheet.preview.manualChanges', { count: appliedSummary.manualChanges }) }}
        </div>
      </div>

      <div v-else-if="status === 'stale'" class="flex flex-col gap-[8px]">
        <div class="text-[12.6px] leading-[1.6] text-[#c0392b]" data-testid="sheet-preview-stale">
          {{ t('ai.sheetCard.expired') }}
        </div>
        <div class="flex justify-end">
          <button
            type="button"
            :class="SECONDARY_BUTTON"
            data-testid="sheet-preview-abandon"
            :disabled="disabled"
            @click="emit('abandon')"
          >{{ t('ai.sheetCard.discard') }}</button>
        </div>
      </div>

      <div v-if="status !== 'stale' && plan" class="flex flex-col gap-[8px]">
        <div>
          <div class="text-[12.8px] leading-[1.6] text-[#1f2328]" data-testid="sheet-preview-summary">
            {{ t('ai.sheetCard.impact', { rows: plan.rowsCount, cells: plan.cellsCount }) }}
          </div>
          <div class="text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-scope">
            {{ t('ai.sheetCard.scope', { scope: scopeSummary }) }}<span v-if="predicateSummary"> · {{ predicateSummary }}</span>
          </div>
          <div class="text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-rules">
            {{ t('ai.sheetCard.rules', { rules: rulesText }) }}
          </div>
          <div class="text-[11.9px] leading-[1.6] text-[#8a5a00]" data-testid="sheet-preview-overwrite">
            {{ t('ai.sheetCard.overwriteNotice') }}
          </div>
        </div>

        <div v-if="!isUndoPlan && isInteractive" class="flex flex-wrap items-center gap-[6px]" data-testid="sheet-preview-levels">
          <button
            v-for="option in LEVELS"
            :key="option.value"
            type="button"
            :class="levelClass(option.value)"
            :data-testid="'sheet-preview-level-' + option.value"
            :aria-pressed="plan.preview === option.value"
            :disabled="isApplying"
            @click="emit('level', option.value)"
          >{{ option.label }}</button>
          <span v-if="plan.skippedCount > 0" class="ml-auto text-[11.5px] text-[#8b949e]">{{ t('ai.sheetCard.skipped', { count: plan.skippedCount }) }}</span>
        </div>

        <div v-if="plan.preview === 'count'" class="text-[11.9px] leading-[1.6] text-[#57606a]" data-testid="sheet-preview-count">
          {{ t('sheet.preview.countLevel') }}
        </div>
        <div
          v-if="plan.preview === 'count' && distributionGroups.length > 0"
          class="text-[11.9px] leading-[1.6] text-[#57606a] [overflow-wrap:anywhere]"
          data-testid="sheet-preview-distribution"
        >
          <span>{{ t('ai.sheetCard.distribution', { by: distribution!.byLabel || distribution!.by, count: distributionGroups.length }) }}</span>
          <span
            v-for="(group, index) in distributionGroups"
            :key="group.key"
            :data-testid="'sheet-preview-dist-' + group.key"
          >{{ index > 0 ? ' · ' : '' }}{{ group.key }} {{ t('ai.sheetCard.groupItem', { rows: group.rows, cells: group.cells }) }}</span>
          <span v-if="(distribution?.otherGroups ?? 0) > 0"> · {{ t('sheet.preview.distributionOthers', { count: distribution?.otherGroups }) }}</span>
          <span v-if="distribution?.truncated">{{ t('ai.sheetCard.distributionTruncatedWrapped', { text: t('sheet.preview.distributionTruncated') }) }}</span>
        </div>

        <div v-if="plan.preview === 'sample-diff'" data-testid="sheet-preview-sample">
          <div
            v-for="(change, index) in plan.samples"
            :key="index"
            class="break-words text-[11.9px] leading-[1.7] text-[#57606a] [overflow-wrap:anywhere]"
          ><span :class="CELL_CHIP" data-testid="sheet-preview-cell">{{ addressOf(change) }}</span>{{ t('ai.sheetCard.rowCol', { row: change.row + 2, col: change.col + 1, from: display(change.oldValue), to: display(change.newValue) }) }}</div>
          <div v-if="plan.samples.length === 0" class="text-[11.9px] text-[#8b949e]">{{ t('ai.sheetCard.noMatches') }}</div>
        </div>

        <div v-if="plan.preview === 'full-diff'" class="flex flex-col gap-[8px]" data-testid="sheet-preview-full">
          <div :class="['max-h-[min(480px,60vh)] overflow-y-auto overflow-x-hidden rounded-[6px] border border-solid border-[#eef0f3] bg-white px-[8px] py-[4px]', SCROLLBAR_CLASS]">
            <div
              v-for="(change, index) in pageChanges"
              :key="index"
              class="break-words text-[11.7px] leading-[1.7] text-[#57606a] [overflow-wrap:anywhere]"
            ><span :class="CELL_CHIP" data-testid="sheet-preview-cell">{{ addressOf(change) }}</span>{{ t('ai.sheetCard.rowCol', { row: change.row + 2, col: change.col + 1, from: display(change.oldValue), to: display(change.newValue) }) }}</div>
          </div>
          <div class="flex items-center gap-[8px] text-[11.5px] text-[#8b949e]">
            <button type="button" :class="PAGE_BUTTON" :disabled="page === 0" @click="page -= 1">{{ t('ai.sheetCard.prevPage') }}</button>
            <span>{{ t('ai.sheetCard.page', { page: page + 1, total: pageCount, count: plan.changes.length }) }}</span>
            <button type="button" :class="PAGE_BUTTON" :disabled="page >= pageCount - 1" @click="page += 1">{{ t('ai.sheetCard.nextPage') }}</button>
          </div>
        </div>

        <div
          v-if="plan.preview !== 'count' && plan.detail !== 'none'"
          class="flex items-center gap-[5px] text-[11.5px] leading-[1.6] text-[#8b949e]"
          data-testid="sheet-preview-highlight-legend"
        >
          <span class="inline-block h-[9px] w-[9px] shrink-0 rounded-[2px] bg-[#fff4e0] ring-1 ring-inset ring-[#e6d3b3]"></span>
          <span>{{ t('sheet.preview.highlightLegend') }}</span>
        </div>

        <div
          v-if="plan.detail === 'none'"
          class="text-[11.5px] leading-[1.6] text-[#8b949e]"
          data-testid="sheet-preview-detail-pending"
        >{{ t('sheet.preview.detailPending') }}</div>

        <div v-if="statusHint" class="flex items-center gap-[6px] text-[12.2px] leading-[1.55]" :class="status === 'failed' ? 'text-[#c0392b]' : 'text-[#57606a]'" data-testid="sheet-preview-status-hint">
          <span v-if="isApplying" class="inline-block h-[11px] w-[11px] shrink-0 animate-spin rounded-[50%] border-[1.5px] border-solid border-[#cddcff] border-t-[#2563eb] motion-reduce:animate-none" aria-hidden="true"></span>
          <span>{{ statusHint }}</span>
        </div>

        <div
          v-for="(warning, index) in plan.warnings"
          :key="'w' + index"
          class="rounded-[6px] border border-solid border-[#f0d5d0] bg-[#fdf1ef] px-[8px] py-[5px] text-[11.7px] leading-[1.6] text-[#c0392b]"
          data-testid="sheet-preview-warning"
        >{{ warning }}</div>

        <label v-if="plan.level === 'large' && isInteractive" class="flex items-center gap-[6px] text-[11.9px] text-[#57606a]" data-testid="sheet-preview-ack">
          <input v-model="acknowledged" type="checkbox" :disabled="isApplying || disabled" data-testid="sheet-preview-ack-box" />
          {{ t('ai.sheetCard.ackLarge', { rows: plan.rowsCount, cells: plan.cellsCount }) }}
        </label>

        <div v-if="isInteractive" class="flex flex-wrap items-center justify-end gap-[8px] pt-[2px]">
          <button
            type="button"
            :class="SECONDARY_BUTTON"
            :disabled="isApplying || disabled"
            data-testid="sheet-preview-cancel"
            @click="onCancel"
          >{{ t('ai.sheetCard.cancel') }}</button>
          <button
            type="button"
            :class="PRIMARY_BUTTON"
            :disabled="isApplying || disabled || (plan.level === 'large' && !acknowledged)"
            data-testid="sheet-preview-apply"
            @click="onApply"
          >
            <span v-if="isApplying" class="mr-[6px] inline-block h-[11px] w-[11px] animate-spin rounded-[50%] border-[1.5px] border-solid border-[rgba(255,255,255,.4)] border-t-white align-[-1px] motion-reduce:animate-none" aria-hidden="true"></span>
            {{ isApplying ? t('ai.sheetCard.applying', { cells: plan.cellsCount }) : t('ai.sheetCard.apply', { cells: plan.cellsCount }) }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import BusinessCardHeader from '@/components/AiPanel/BusinessCardHeader.vue'
import type { SheetPreviewLevel, SheetWritePlan } from '@/types/ai'
import { cellAddress, type SheetAppliedSummary } from '@/utils/sheet-tools'
import { SCROLLBAR_CLASS } from '@/utils/markdown'
import { sheetPlanPredicateSummary, sheetPlanRulesText, sheetPlanScopeSummary } from '@/utils/card-content'

type SheetCardStatus = 'pending' | 'applying' | 'applied' | 'failed' | 'cancelled' | 'stale'
type HeaderTone = 'neutral' | 'blue' | 'green' | 'amber' | 'red' | 'purple' | 'gray'

const props = withDefaults(defineProps<{
  plan: SheetWritePlan | null
  applying?: boolean
  appliedSummary?: SheetAppliedSummary | null
  disabled?: boolean
}>(), {
  applying: false,
  appliedSummary: null,
  disabled: false
})

const emit = defineEmits<{
  (event: 'apply', acknowledged: boolean): void
  (event: 'cancel'): void
  (event: 'abandon'): void
  (event: 'level', level: SheetPreviewLevel): void
}>()

const { t } = useI18n()
const bodyId = `sheet-preview-body-${useId()}`
const expandedOverride = ref<boolean | null>(null)

const status = computed<SheetCardStatus>(() => {
  if (props.appliedSummary) {
    return 'applied'
  }
  if (props.applying && props.plan?.status === 'pending') {
    return 'applying'
  }
  return props.plan?.status ?? 'stale'
})
const isApplying = computed(() => status.value === 'applying')
const isInteractive = computed(() => status.value === 'pending' || status.value === 'applying')
const headerTitle = computed(() => t('ai.sheetCard.title'))
const scopeSummary = computed(() => props.plan ? sheetPlanScopeSummary(props.plan) : '')
const predicateSummary = computed(() => props.plan ? sheetPlanPredicateSummary(props.plan) : '')
const rulesText = computed(() => props.plan ? sheetPlanRulesText(props.plan) : '')
const expanded = computed({
  get: () => expandedOverride.value ?? !['applied', 'cancelled'].includes(status.value),
  set: (value: boolean) => {
    expandedOverride.value = value
  }
})
const statusLabel = computed(() => status.value === 'pending' || status.value === 'applying'
  ? t('ai.card.status.actionRequired')
  : t(`ai.sheetCard.status.${status.value}`))
const statusTone = computed<HeaderTone>(() => {
  switch (status.value) {
    case 'applied':
      return 'green'
    case 'failed':
      return 'red'
    case 'stale':
      return 'amber'
    case 'cancelled':
      return 'gray'
    case 'pending':
    case 'applying':
      return 'amber'
    default:
      return 'blue'
  }
})
const statusHint = computed(() => status.value === 'failed'
  ? t('ai.sheetCard.statusHint.failed')
  : '')

function onApply(): void {
  if (props.disabled === true) {
    return
  }
  expandedOverride.value = true
  emit('apply', acknowledged.value)
}

function onCancel(): void {
  if (props.disabled === true) {
    return
  }
  expandedOverride.value = true
  emit('cancel')
}

const appliedLine = computed(() => props.appliedSummary
  ? t('sheet.applied.line', {
    cells: props.appliedSummary.cells,
    layer: props.appliedSummary.layer,
    depth: props.appliedSummary.undoDepth
  })
  : '')

const distribution = computed(() => props.plan?.distribution ?? null)
const distributionGroups = computed(() => distribution.value?.groups ?? [])
const isUndoPlan = computed(() => props.plan?.tool === 'undo_last_sheet_edit')

const LEVELS = computed<Array<{ value: SheetPreviewLevel; label: string }>>(() => [
  { value: 'count', label: t('ai.sheetCard.levelCount') },
  { value: 'sample-diff', label: t('ai.sheetCard.levelSample') },
  { value: 'full-diff', label: t('ai.sheetCard.levelFullDiff') }
])

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

const BUTTON_FOCUS = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2563eb] motion-reduce:transition-none'
const PRIMARY_BUTTON = `flex-none whitespace-nowrap rounded-[8px] border border-solid border-transparent bg-[#2563eb] px-[13px] py-[5px] text-[12.6px] font-semibold text-white transition-all duration-[150ms] hover:brightness-[1.08] disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal disabled:opacity-50 ${BUTTON_FOCUS}`
const SECONDARY_BUTTON = `flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[13px] py-[5px] text-[12.6px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328] disabled:cursor-default disabled:opacity-50 ${BUTTON_FOCUS}`
const PAGE_BUTTON = `border-0 bg-transparent p-0 text-[#57606a] disabled:cursor-default disabled:text-[#c0c4cc] ${BUTTON_FOCUS}`

function levelClass(level: SheetPreviewLevel): string {
  const active = props.plan?.preview === level
  return [
    'rounded-[6px] px-[8px] py-[3px] text-[11.6px] border border-solid transition-colors duration-[150ms] disabled:cursor-default disabled:opacity-50',
    BUTTON_FOCUS,
    active ? 'border-[#2563eb] bg-[#eff4ff] text-[#2563eb]' : 'border-[#e3e6ea] bg-white text-[#57606a] hover:border-[#8b949e]'
  ].join(' ')
}

const CELL_CHIP = 'mr-[5px] inline-block rounded-[4px] bg-[#fff4e0] px-[4px] py-[1px] text-[11px] font-medium text-[#8a5a00] tabular-nums'

function addressOf(change: { row: number; col: number }): string {
  return cellAddress(change.col, change.row)
}

function display(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
</script>
