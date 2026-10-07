<template>
  <div>
    <div v-if="job" class="flex items-center gap-3">
      <el-tag :type="statusType(job.status)" effect="dark">{{ statusLabel(job.status) }}</el-tag>
      <span v-if="running && currentDefCode" class="text-[#606266] text-[13px]">正在处理：{{ currentDefCode }}</span>
      <span v-if="channelHint" class="text-[#909399] text-xs">{{ channelHint }}</span>
      <span class="ml-auto text-[#909399] text-[13px]">{{ progressText }}</span>
      <el-button v-if="running" size="small" type="danger" plain @click="emit('cancel')">取消作业</el-button>
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
          <el-table v-if="detailOf(row).length" :data="detailOf(row)" size="small" class="my-1 mx-4">
            <el-table-column prop="rowIndex" label="行号" width="80">
              <template #default="{ row: issue }">{{ issue.rowIndex ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="字段" width="180">
              <template #default="{ row: issue }">{{ issue.fieldCode ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="级别" width="80">
              <template #default="{ row: issue }">
                <el-tag size="small" :type="severityType(issue.severity)">{{ severityLabel(issue.severity) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="说明" />
          </el-table>
          <div v-else class="py-2 px-4 text-[#909399]">无明细</div>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="顺序" width="64" />
      <el-table-column prop="defCode" label="配置项" min-width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="processed" label="已处理" width="90" />
      <el-table-column prop="total" label="总行数" width="90">
        <template #default="{ row }">{{ row.total > 0 ? row.total : '—' }}</template>
      </el-table-column>
      <el-table-column label="失败" width="80">
        <template #default="{ row }">
          <span :class="errorRowsOf(row.defCode) > 0 ? 'text-[#f56c6c]' : ''">
            {{ errorRowsOf(row.defCode) > 0 ? errorRowsOf(row.defCode) : '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ firstMessageOf(row.defCode) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!job" description="尚未启动作业" :image-size="60" />
  </div>
</template>

<script setup lang="ts">
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

const props = withDefaults(
  defineProps<{
    job: Job | null
    /** 该作业的校验问题（行级明细来源） */
    issues?: readonly ValidationIssue[]
    /** 实时通道状态（SSE 主通道 / 轮询兜底） */
    channel?: JobChannelState | null
    /** 作业失败时的错误文案（无 issue 时兜底） */
    errorMessage?: string | null
  }>(),
  {
    issues: () => [],
    channel: null,
    errorMessage: null
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
    return `处理中 · 已处理 ${props.job.progress} 行（总量待定）`
  }
  return `${props.job.progress} / ${props.job.total}`
})

const channelHint = computed(() => {
  if (!running.value) {
    return ''
  }
  if (props.channel === 'polling') {
    return '（实时通道已降级为轮询）'
  }
  if (props.channel === 'realtime') {
    return '（实时通道已连接）'
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
      return '等待中'
    case 'RUNNING':
      return '运行中'
    case 'COMPLETED':
      return '成功'
    case 'FAILED':
      return '失败'
    case 'CANCELLED':
      return '已取消'
    default:
      return String(status)
  }
}

function severityType(severity: Severity): 'danger' | 'warning' | 'info' {
  return severity === 'ERROR' ? 'danger' : severity === 'WARNING' ? 'warning' : 'info'
}

function severityLabel(severity: Severity): string {
  return severity === 'ERROR' ? '错误' : severity === 'WARNING' ? '警告' : '提示'
}
</script>
