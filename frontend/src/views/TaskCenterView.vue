<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">{{ t('views.tasks.title') }}</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          {{ t('views.tasks.subtitle') }}
          <span class="ml-1">{{ t('views.tasks.refreshHint') }}{{ lastLoadedText }}</span>
        </div>
      </div>
      <div>
        <el-button :icon="Refresh" circle :title="t('views.tasks.refresh')" @click="taskStore.refreshNow()" />
        <el-button type="primary" :icon="Plus" @click="createDialogVisible = true">{{ t('views.tasks.create') }}</el-button>
      </div>
    </div>

    <div class="flex items-center gap-2 mb-3">
      <el-input v-model="filterKeyword" :placeholder="t('views.tasks.keywordPlaceholder')" clearable class="w-[220px]" @keyup.enter="applyFilters" />
      <el-select v-model="filterType" :placeholder="t('views.tasks.type')" clearable class="w-[130px]">
        <el-option :label="t('views.tasks.exportType')" value="EXPORT" />
        <el-option :label="t('views.tasks.importType')" value="IMPORT" />
      </el-select>
      <el-select v-model="filterStatus" :placeholder="t('views.tasks.status')" clearable class="w-[130px]">
        <el-option :label="t('views.tasks.active')" value="ACTIVE" />
        <el-option :label="t('views.tasks.completed')" value="COMPLETED" />
        <el-option :label="t('views.tasks.cancelled')" value="CANCELLED" />
        <el-option :label="t('views.tasks.failed')" value="FAILED" />
      </el-select>
      <el-button type="primary" @click="applyFilters">{{ t('views.tasks.search') }}</el-button>
      <el-button @click="resetFilters">{{ t('views.tasks.reset') }}</el-button>
    </div>

    <el-alert
      v-if="taskStore.error"
      type="warning"
      :closable="false"
      show-icon
      :title="t('views.tasks.loadFailed', { error: taskStore.error })"
      class="mb-3"
    />

    <el-table v-loading="taskStore.loading" :data="taskStore.items" border>
      <el-table-column :label="t('views.tasks.task')" min-width="240">
        <template #default="{ row }: { row: TaskSummary }">
          <div class="flex items-center gap-2">
            <span class="text-[#c0c4cc]">#{{ row.task.id }}</span>
            <el-button link type="primary" @click="restoreWizard(row)">{{ row.task.title }}</el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.type')" width="100">
        <template #default="{ row }: { row: TaskSummary }">
          <el-tag size="small" :type="row.task.type === 'EXPORT' ? 'success' : 'primary'">
            {{ row.task.type === 'EXPORT' ? t('views.tasks.exportType') : t('views.tasks.importType') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.status')" width="110">
        <template #default="{ row }: { row: TaskSummary }">
          <el-tag size="small" :type="statusType(row.task.status)">{{ statusLabel(row.task.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.progress')" width="190">
        <template #default="{ row }: { row: TaskSummary }">
          <div v-if="row.latestJob" class="flex items-center gap-2">
            <el-progress
              class="flex-1"
              :percentage="progressOf(row.latestJob)"
              :indeterminate="row.latestJob.total <= 0"
              :status="progressStatusOf(row.latestJob)"
              :stroke-width="8"
              :show-text="false"
            />
            <span class="w-[74px] text-right text-[13px] text-[#606266]">{{ progressTextOf(row.latestJob) }}</span>
          </div>
          <span v-else class="text-[#c0c4cc]">—</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.currentStep')" width="110">
        <template #default="{ row }: { row: TaskSummary }">{{ stepLabel(row.task.currentStep) }}</template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.createdAt')" width="170">
        <template #default="{ row }: { row: TaskSummary }">{{ formatDateTime(row.task.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.updatedAt')" width="170">
        <template #default="{ row }: { row: TaskSummary }">{{ formatDateTime(row.task.updatedAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('views.tasks.actions')" width="160" fixed="right">
        <template #default="{ row }: { row: TaskSummary }">
          <el-button
            size="small"
            type="warning"
            link
            :disabled="!canCancel(row)"
            :loading="cancellingId === row.task.id"
            @click="cancelTask(row)"
          >
            {{ t('views.tasks.cancel') }}
          </el-button>
          <el-button size="small" type="danger" link @click="removeTask(row)">{{ t('views.tasks.remove') }}</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="t('views.tasks.empty')" />
      </template>
    </el-table>

    <div class="flex justify-end mt-3">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        :total="taskStore.total"
        :current-page="taskStore.page + 1"
        :page-size="taskStore.size"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>

    <el-dialog v-model="createDialogVisible" :title="t('views.tasks.create')" width="440px">
      <el-form label-width="auto">
        <el-form-item :label="t('views.tasks.taskType')" required>
          <el-radio-group v-model="createForm.type" class="flex flex-col items-start gap-1">
            <el-radio value="EXPORT" class="!h-auto !whitespace-normal !items-start !mr-0">
              {{ t('views.tasks.exportType') }}
              <div class="text-xs text-[#909399] font-normal">{{ t('views.tasks.exportDesc') }}</div>
            </el-radio>
            <el-radio value="IMPORT" class="!h-auto !whitespace-normal !items-start !mr-0">
              {{ t('views.tasks.importType') }}
              <div class="text-xs text-[#909399] font-normal">{{ t('views.tasks.importDesc') }}</div>
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('views.tasks.taskName')">
          <el-input v-model="createForm.title" maxlength="60" :placeholder="t('views.tasks.taskTitlePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">{{ t('views.tasks.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">{{ t('views.tasks.createTitle') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 任务中心（五页之①）。
 *
 * - 过滤（类型/状态/关键词）+ 分页：参数原样交后端（key 排序与 LIKE 转义后端已处理）；
 * - 刷新口径（手动刷新批，M1 + F2）：**无任何周期自刷、零 EventSource 连接** ——
 *   挂载只拉一次列表，此后静止对后端零请求；右上角刷新按钮与写操作成功后的本地 refreshNow()
 *   是唯一更新入口（轮询治理一期步1~步3 的条件自刷与任务级 SSE 订阅池已整体摘除）；
 * - 点任务标题 = 一键恢复向导（`restoreWizard` 按任务类型跳对应向导页，与创建任务后跳转同口径）；
 * - 「取消」取消的是该任务最新作业（后端只有 DELETE /api/jobs/{id}；作业已终态 →
 *   409 JOB_ALREADY_FINAL，按码给中文提示，不当普通错误弹）；取消成功后本地 refreshNow() 一次；
 * - 「删除」级联清理作业/条目/问题/暂存/文件（S4.3b⑦）；
 * - 进度列是**行级**口径：total 未知（-1）时显示"处理中"而不是百分比。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { useTaskStore } from '@/stores/task'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatDateTime, progressView, stepLabel, TASK_TYPE_LABELS } from '@/utils/format'
import { confirmIrreversible } from '@/utils/confirm-irreversible'
import type { TaskStatus, TaskSummary, TaskType } from '@/types/task'
import type { Job } from '@/types/job'

const { t } = useI18n()

const taskStore = useTaskStore()
const workspace = useWorkspaceStore()
const router = useRouter()

const filterKeyword = ref('')
const filterType = ref<TaskType | null>(null)
const filterStatus = ref<TaskStatus | null>(null)

const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = reactive<{ type: TaskType; title: string }>({ type: 'EXPORT', title: '' })

const cancellingId = ref<number | null>(null)

const lastLoadedText = computed(() => {
  if (taskStore.lastLoadedAt === 0) {
    return ''
  }
  return `（最近 ${formatDateTime(new Date(taskStore.lastLoadedAt).toISOString())}）`
})

function applyFilters(): void {
  taskStore.applyFilters({
    keyword: filterKeyword.value,
    type: filterType.value,
    status: filterStatus.value
  })
}

function resetFilters(): void {
  filterKeyword.value = ''
  filterType.value = null
  filterStatus.value = null
  applyFilters()
}

function onPageChange(page: number): void {
  taskStore.setPage(page - 1)
}

function onSizeChange(size: number): void {
  taskStore.setSize(size)
}

function statusType(status: TaskStatus): 'success' | 'danger' | 'info' | 'warning' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    default:
      return 'warning'
  }
}

function statusLabel(status: TaskStatus): string {
  switch (status) {
    case 'ACTIVE':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
    case 'FAILED':
      return '已失败'
    default:
      return String(status)
  }
}

/** 进度百分比：total 未知时返回 0（配合 indeterminate 不显示假百分比）。 */
function progressOf(job: Job): number {
  return progressView(job.progress, job.total).percent ?? 0
}

function progressTextOf(job: Job): string {
  const view = progressView(job.progress, job.total)
  return view.unknownTotal ? '处理中' : `${view.percent}%`
}

function progressStatusOf(job: Job): 'success' | 'exception' | 'warning' | undefined {
  if (job.status === 'COMPLETED') {
    return 'success'
  }
  if (job.status === 'FAILED') {
    return 'exception'
  }
  if (job.status === 'CANCELLED') {
    return 'warning'
  }
  return undefined
}

function canCancel(row: TaskSummary): boolean {
  const status = row.latestJob?.status
  return status === 'PENDING' || status === 'RUNNING'
}

async function restoreWizard(row: TaskSummary): Promise<void> {
  const target = await taskStore.restoreWizard(row.task.id ?? 0, row.task.type)
  await router.push(target)
}

async function cancelTask(row: TaskSummary): Promise<void> {
  const jobId = row.latestJob?.id
  if (jobId === undefined) {
    return
  }
  const confirmed = await confirmIrreversible({
    title: '取消作业',
    message: `确定取消任务「${row.task.title}」当前运行的作业 #${jobId}？已处理的部分不会回滚。`,
    ack: '确认取消作业，执行后无法恢复'
  })
  if (!confirmed) {
    return
  }
  cancellingId.value = row.task.id ?? null
  try {
    const outcome = await taskStore.cancelJob(jobId)
    if (outcome.handled) {
      ElMessage.success(outcome.message)
      // 写操作成功后本地刷新一次（F2 手动刷新通道；任务中心无周期表，不刷就看不到取消落地）
      void taskStore.refreshNow()
    } else {
      ElMessage.warning(outcome.message)
    }
  } finally {
    cancellingId.value = null
  }
}

async function removeTask(row: TaskSummary): Promise<void> {
  if (row.task.id === undefined) {
    return
  }
  const confirmed = await confirmIrreversible({
    title: '删除任务',
    message: `确定删除任务「${row.task.title}」？关联作业、暂存与导出结果将一并删除。`,
    ack: '确认删除任务，执行后无法恢复',
    type: 'error'
  })
  if (!confirmed) {
    return
  }
  const ok = await taskStore.removeTask(row.task.id)
  if (ok) {
    ElMessage.success('任务已删除')
  } else {
    ElMessage.error(taskStore.error ?? '任务删除失败')
  }
}

/**
 * 名称留空时的默认任务名：`<类型名>任务 <本地时间>`。
 * 口径与两处向导的自动建任务一致（ExportWizardView/ImportWizardView 的 createAndBindTask）。
 */
function defaultTaskTitle(type: TaskType): string {
  return `${TASK_TYPE_LABELS[type]}任务 ${new Date().toLocaleString('zh-CN')}`
}

async function createTask(): Promise<void> {
  // 名称非必填：留空取默认名；title 恒为非空字符串（后端列为 NOT NULL，传 null 会 500）
  const title = createForm.title.trim() || defaultTaskTitle(createForm.type)
  creating.value = true
  try {
    const task = await taskStore.createTask(createForm.type, title)
    if (!task || task.id === undefined) {
      ElMessage.error(taskStore.error ?? '任务创建失败')
      return
    }
    createDialogVisible.value = false
    createForm.title = ''
    ElMessage.success(`任务「${task.title}」已创建`)
    await router.push(await taskStore.restoreWizard(task.id, task.type))
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
  // 手动刷新批（M1）：只拉一次列表 —— 不起周期表、不开任务级事件订阅
  void taskStore.load()
})
</script>
