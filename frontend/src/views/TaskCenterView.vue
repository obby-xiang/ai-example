<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">{{ t('views.tasks.title') }}</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          {{ t('views.tasks.subtitle') }}
          <span class="ml-1">{{ t('views.tasks.refreshHint') }}</span>
          <span v-if="asOfText" class="ml-1 as-of">{{ asOfText }}</span>
        </div>
      </div>
      <div>
        <el-button :icon="Refresh" circle :title="t('views.tasks.refresh')" @click="refresh" />
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
      v-if="taskStore.loadError"
      type="warning"
      :closable="false"
      show-icon
      :title="t('views.tasks.loadFailed', { error: taskStore.loadError })"
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
 *   控件值是**草稿**（改动不请求），`commitFilterDraft()` 在查询/翻页/刷新/写后刷新时
 *   把它提交成 store 的已应用值 —— 三个入口同一份筛选，控件与列表不再错位（P1-5）；
 * - 刷新口径（手动刷新批 M1 + F2 + AR-08）：**无任何周期自刷、零 EventSource 连接** ——
 *   挂载只拉一次列表，此后静止对后端零请求；更新入口 = 右上角刷新按钮、写操作成功后的
 *   `refreshNow()`，以及 AR-08 的**受限事件刷新**：AI 作业事件（`ui_event_result` 里的
 *   `run_job` / `cancel_job` / `start_*`）到达后，若**存在在途作业**且**页面可见**才刷一次；
 *   页面隐藏期只记待补、回可见补刷一次（不是轮询，也不补后台错过的次数）；
 *   「数据截至 HH:mm:ss」让"没刷新"与"没有变化"可区分；
 * - 点任务标题 = 一键恢复向导（`restoreWizard` 按任务类型跳对应向导页，与创建任务后跳转同口径）；
 * - 「取消」取消的是该任务最新作业（后端只有 DELETE /api/jobs/{id}；作业已终态 →
 *   409 JOB_ALREADY_FINAL，按码给中文提示，不当普通错误弹）；取消成功后本地 refreshNow() 一次；
 * - 「删除」级联清理作业/条目/问题/暂存/文件（S4.3b⑦）；
 * - 进度列是**行级**口径：total 未知（-1）时显示"处理中"而不是百分比。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { isTaskCenterRefreshEvent, useTaskStore } from '@/stores/task'
import { onWorkspaceEvent, useWorkspaceStore } from '@/stores/workspace'
import { formatDateTime, progressView, stepLabel } from '@/utils/format'
import { confirmIrreversible } from '@/utils/confirm-irreversible'
import type { TaskStatus, TaskSummary, TaskType } from '@/types/task'
import type { Job } from '@/types/job'

const { t } = useI18n()

const taskStore = useTaskStore()
const workspace = useWorkspaceStore()
const router = useRouter()

const filterKeyword = ref(taskStore.filterKeyword)
const filterType = ref<TaskType | null>(taskStore.filterType)
const filterStatus = ref<TaskStatus | null>(taskStore.filterStatus)

const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = reactive<{ type: TaskType; title: string }>({ type: 'EXPORT', title: '' })

const cancellingId = ref<number | null>(null)

/**
 * AR-08「数据截至时刻」：只显示 `HH:mm:ss`（列表最近一次**成功**加载的时刻）。
 *
 * 为什么必须显示：事件驱动刷新是**有条件**的（无在途作业 / 页面隐藏时不刷），没有这一刻，
 * 用户无法区分"列表没刷新"与"数据确实没变化"。
 */
const asOfText = computed(() => {
  if (taskStore.lastLoadedAt === 0) {
    return ''
  }
  // `toTimeString()` 前三段恒为 HH:mm:ss（与 locale 无关）——只取时间，日期在同一屏另有列
  return t('views.tasks.asOf', { time: new Date(taskStore.lastLoadedAt).toTimeString().slice(0, 8) })
})

/**
 * AR-08：AI 作业事件 → store 的三条件闸（事件后 + 仅在途 + 可见）。
 *
 * 订阅面只取工作区事件总线的 `ui_event_result`（`dispatchUiEvent` 对**每个** ui_event 都发，
 * `run_job` / `cancel_job` 都在其中）；白名单外的动作（导航 / 选配置 / 设条件）不刷新。
 * 视图只做"转交"，三条件判定全部在 `stores/task.ts`，避免两处判据漂移。
 */
let disposeJobEvents: (() => void) | null = null

/** 可见性变化：回到前台时补刷隐藏期收到的那一次（`flushAutoRefreshOnVisible` 自带判据）。 */
function onVisibilityChange(): void {
  taskStore.flushAutoRefreshOnVisible()
}

/**
 * 筛选控件是**草稿**：改动本身不发请求，与 store 里**已应用**的筛选分开存放
 * （进页面时草稿从已应用值起步，控件与列表不再各说各话）。
 *
 * 查询 / 翻页 / 刷新三个入口都先过这里把草稿提交成已应用值，再拉列表 ——
 * 三者永远同源，不会出现「下拉显示已完成、列表却按旧筛选翻页」的错位。
 * 草稿有实质改动时页码回到第一页（否则新筛选可能落在空页上）。
 */
function commitFilterDraft(): boolean {
  const keyword = filterKeyword.value.trim()
  if (
    keyword === taskStore.filterKeyword &&
    filterType.value === taskStore.filterType &&
    filterStatus.value === taskStore.filterStatus
  ) {
    return false
  }
  taskStore.$patch({
    filterKeyword: keyword,
    filterType: filterType.value,
    filterStatus: filterStatus.value,
    page: 0
  })
  return true
}

function applyFilters(): void {
  const keyword = filterKeyword.value.trim()
  filterKeyword.value = keyword
  taskStore.applyFilters({
    keyword,
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
  commitFilterDraft()
  taskStore.setPage(page - 1)
}

function onSizeChange(size: number): void {
  commitFilterDraft()
  taskStore.setSize(size)
}

/** 手动刷新：与查询/翻页同一份筛选（草稿有改动时按新筛选从第一页拉）。 */
function refresh(): void {
  commitFilterDraft()
  void taskStore.refreshNow()
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
      return t('views.tasks.active')
    case 'COMPLETED':
      return t('views.tasks.completed')
    case 'CANCELLED':
      return t('views.tasks.cancelled')
    case 'FAILED':
      return t('views.tasks.failed')
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
  return view.unknownTotal ? t('views.tasks.processing') : `${view.percent}%`
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
    title: t('views.tasks.dialog.cancelTitle'),
    message: t('views.tasks.dialog.cancelMessage', { title: row.task.title, jobId }),
    ack: t('views.tasks.dialog.cancelAck')
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
      // 走 refresh() 而非裸 refreshNow()：重载与控件当前筛选同源，不把刚选的筛选顶回旧值
      refresh()
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
    title: t('views.tasks.dialog.deleteTitle'),
    message: t('views.tasks.dialog.deleteMessage', { title: row.task.title }),
    ack: t('views.tasks.dialog.deleteAck'),
    type: 'error'
  })
  if (!confirmed) {
    return
  }
  const ok = await taskStore.removeTask(row.task.id)
  if (ok) {
    ElMessage.success(t('views.tasks.msg.deleted'))
  } else {
    ElMessage.error(taskStore.actionError ?? t('views.tasks.msg.deleteFailed'))
  }
}

/**
 * 名称留空时的默认任务名：`<类型名>任务 <本地时间>`。
 * 口径与两处向导的自动建任务一致（ExportWizardView/ImportWizardView 的 createAndBindTask）。
 */
function defaultTaskTitle(type: TaskType): string {
  // 任务名是**数据**（落库、进列表、随历史回读）：只翻「任务」这个模板词，时间戳口径保持 zh-CN
  // —— 换日期格式会改变已生成任务名的形态，与"数据不是文案"同一口径。
  return t('views.tasks.autoName', {
    type: t(type === 'EXPORT' ? 'views.tasks.exportType' : 'views.tasks.importType'),
    time: new Date().toLocaleString('zh-CN')
  })
}

async function createTask(): Promise<void> {
  // 名称非必填：留空取默认名；title 恒为非空字符串（后端列为 NOT NULL，传 null 会 500）
  const title = createForm.title.trim() || defaultTaskTitle(createForm.type)
  creating.value = true
  try {
    const task = await taskStore.createTask(createForm.type, title)
    if (!task || task.id === undefined) {
      ElMessage.error(taskStore.actionError ?? t('views.tasks.msg.createFailed'))
      return
    }
    createDialogVisible.value = false
    createForm.title = ''
    ElMessage.success(t('views.tasks.msg.created', { title: task.title }))
    await router.push(await taskStore.restoreWizard(task.id, task.type))
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  workspace.enterPage({ pageId: 'tasks', page: 'tasks', taskType: null, step: null, taskId: null })
  // 手动刷新批（M1）：只拉一次列表 —— 不起周期表、不开任务级事件订阅
  void taskStore.load()
  // AR-08：进入观察窗 + 订阅作业事件（事件后刷新；无定时器，页面静止仍零请求）
  taskStore.watchCenter()
  disposeJobEvents = onWorkspaceEvent('ui_event_result', (payload) => {
    const result = payload as { event?: unknown; handled?: unknown } | null
    // 被拒 / 失败的动作没有改变列表事实（批次5A：失败不记"已同步"）⇒ 不构成"事件后"
    if (result?.handled === false) {
      return
    }
    const event: unknown = result?.event
    if (isTaskCenterRefreshEvent(event)) {
      taskStore.notifyJobEvent(event)
    }
  })
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  disposeJobEvents?.()
  disposeJobEvents = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
  taskStore.unwatchCenter()
})
</script>
