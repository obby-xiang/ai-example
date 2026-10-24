<template>
  <div>
    <div v-if="job" class="flex items-center gap-3">
      <el-tag :type="statusType(job.status)" effect="dark">{{ statusLabel(job.status) }}</el-tag>
      <span v-if="running && currentDefCode" class="text-[#606266] text-[13px]">{{ t('job.processing', { code: currentDefCode }) }}</span>
      <span v-if="channelHint" class="text-[#909399] text-xs">{{ channelHint }}</span>
      <span class="ml-auto text-[#909399] text-[13px]">{{ progressText }}</span>
      <!-- R-136 收口修 F1（复核 MAJOR-1）：只读壳内「取消作业」一律隐藏 —— 异类型作业并发态下
           任务 CANCELLED 时本组件仍可能有 PENDING/RUNNING 作业，按钮可见性只看作业态不够 -->
      <el-button v-if="running && !readOnly" size="small" type="danger" plain @click="emit('cancel')">{{ t('job.cancelJob') }}</el-button>
    </div>
    <el-progress
      v-if="job"
      :percentage="view.percent ?? 0"
      :indeterminate="view.unknownTotal"
      :duration="2"
      :status="progressStatus"
      :stroke-width="8"
      :show-text="false"
      class="mt-2 mb-3"
    />
    <div v-if="job && errorMessage" class="mb-2.5">
      <el-alert type="error" :title="errorMessage" :closable="false" show-icon />
    </div>
    <el-table v-if="job && items.length" :data="items" size="small" border>
      <el-table-column v-if="hasAnyDetail" type="expand">
        <template #default="{ row }">
          <el-table v-if="detailOf(row.defCode).length" :data="detailOf(row.defCode)" size="small" class="my-1 mx-4">
            <el-table-column prop="rowIndex" :label="t('job.rowIndex')" width="80">
              <template #default="{ row: issue }">{{ issue.rowIndex ?? '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('job.field')" width="180">
              <template #default="{ row: issue }">{{ issue.fieldCode ?? '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('job.level')" width="80">
              <template #default="{ row: issue }">
                <el-tag size="small" :type="severityType(issue.severity)">{{ severityLabel(issue.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" :label="t('job.message')" />
          </el-table>
          <div v-else class="py-2 px-4 text-[#909399]">{{ t('job.noDetail') }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" :label="t('job.sortOrder')" width="64" />
      <el-table-column prop="defCode" :label="t('job.definition')" min-width="160" />
      <el-table-column :label="t('job.statusLabel')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="processed" :label="t('job.processed')" width="90" />
      <el-table-column prop="total" :label="t('job.totalRows')" width="90">
        <template #default="{ row }">{{ row.total > 0 ? row.total : '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('job.failedRows')" width="80">
        <template #default="{ row }">
          <span :class="errorRowsOf(row.defCode) > 0 ? 'text-[#f56c6c]' : ''">
            {{ errorRowsOf(row.defCode) > 0 ? errorRowsOf(row.defCode) : '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('job.message')" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ firstMessageOf(row.defCode) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!job" :description="t('job.notStarted')" :image-size="60" />
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 行级作业进度（口径：jobs.progress / jobs.total 是**行级** processed/total，
 * job_items.processed/total 才是配置项级 —— 两者不混用）。
 *
 * - total 未知（后端约定 -1）→ 不显示百分比，走 indeterminate 的"处理中"形态；
 * - 明细展开：按配置项分组展示 `GET /api/jobs/{id}/issues` 的行级校验问题
 *   （发布冲突的 Q4 文案也从这里读，见 ImportWizardView 第 4 步）；
 * - `channel` 显示实时通道状态（SSE 实时 / 已降级为轮询），让"进度停了"可解释。
 */
import { computed } from 'vue'
import type { Job, JobStatus, Severity, ValidationIssue } from '@/types/job'
import { progressView } from '@/utils/format'
import type { JobChannelState } from '@/utils/job-watch'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    job: Job | null
    /** 该作业的校验问题（行级明细来源） */
    issues?: readonly ValidationIssue[]
    /** 实时通道状态（SSE 主通道 / 轮询兜底） */
    channel?: JobChannelState | null
    /** 作业失败时的错误文案（无 issue 时兜底） */
    errorMessage?: string | null
    /** 只读壳（任务已终态）：隐藏「取消作业」，与两向导「仅供查看」口径一致（收口修 F1） */
    readOnly?: boolean
  }>(),
  {
    issues: () => [],
    channel: null,
    errorMessage: null,
    readOnly: false
  }
)

const emit = defineEmits<{ (event: 'cancel'): void }>()

const running = computed(() => props.job !== null && ['PENDING', 'RUNNING'].includes(props.job.status))

const view = computed(() => progressView(props.job?.progress ?? 0, props.job?.total ?? 0))

const progressText = computed(() => {
  if (!props.job) {
    return ''
  }
  if (view.value.unknownTotal) {
    return t('job.progressUnknown', { processed: props.job.progress })
  }
  return `${props.job.progress} / ${props.job.total}`
})

const channelHint = computed(() => {
  if (!running.value) {
    return ''
  }
  if (props.channel === 'polling') {
    return t('job.channelPolling')
  }
  if (props.channel === 'realtime') {
    return t('job.channelRealtime')
  }
  return ''
})

const items = computed(() =>
  [...(props.job?.items ?? [])].sort((a, b) => (a.defCode > b.defCode ? 1 : a.defCode < b.defCode ? -1 : 0))
)

/** 正在处理的配置项（取第一个 RUNNING 的条目，没有则取最后一个已处理的）。 */
const currentDefCode = computed(() => {
  const list = props.job?.items ?? []
  const running = list.find((item) => item.status === 'RUNNING')
  if (running) {
    return running.defCode
  }
  const done = [...list].reverse().find((item) => item.processed > 0)
  return done?.defCode ?? ''
})

const hasAnyDetail = computed(() => props.issues.some((issue) => issue.severity === 'ERROR' || issue.severity === 'WARNING'))

function detailOf(defCode: string): ValidationIssue[] {
  return props.issues.filter((issue) => issue.defCode === defCode)
}

function errorRowsOf(defCode: string): number {
  return props.issues.filter((issue) => issue.defCode === defCode && issue.severity === 'ERROR').length
}

function firstMessageOf(defCode: string): string {
  return props.issues.find((issue) => issue.defCode === defCode)?.message ?? ''
}

const progressStatus = computed<'success' | 'exception' | 'warning' | undefined>(() => {
  switch (props.job?.status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'exception'
    case 'CANCELLED':
      return 'warning'
    default:
      return undefined
  }
})

function statusType(status: JobStatus | string): 'primary' | 'success' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case 'RUNNING':
      return 'primary'
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'warning'
    default:
      return 'info'
  }
}

function statusLabel(status: JobStatus | string): string {
  switch (status) {
    case 'PENDING':
      return t('job.status.PENDING')
    case 'RUNNING':
      return t('job.status.RUNNING')
    case 'COMPLETED':
      return t('job.status.COMPLETED')
    case 'FAILED':
      return t('job.status.FAILED')
    case 'CANCELLED':
      return t('job.status.CANCELLED')
    default:
      return String(status)
  }
}

function severityType(severity: Severity): 'danger' | 'warning' | 'info' {
  return severity === 'ERROR' ? 'danger' : severity === 'WARNING' ? 'warning' : 'info'
}

function severityLabel(severity: Severity): string {
  switch (severity) {
    case 'ERROR':
      return t('job.severity.ERROR')
    case 'WARNING':
      return t('job.severity.WARNING')
    default:
      return t('job.severity.INFO')
  }
}
</script>
