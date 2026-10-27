<template>
  <TaskWizard
    :title="t('views.export.title')"
    :subtitle="taskSubtitle"
    :steps="WIZARD_STEPS"
    :active-index="stepIndex"
    :step-statuses="stepStatuses"
    :read-only="isReadOnly"
    :task-status="task?.status ?? null"
    :prev-disabled="stepIndex === 0"
    :show-prev="!isReadOnly"
    :show-next="!isReadOnly && stepIndex < 2"
    :next-loading="persisting"
    @prev="goStep(stepIndex - 1)"
    @next="nextStep"
    @step-click="viewStep"
  >
    <!-- ============ 第 1 步：选择配置 ============ -->
    <div v-show="stepIndex === 0">
      <el-alert
        v-if="!taskId"
        type="warning"
        :closable="false"
        show-icon
        :title="t('views.export.noTask')"
        :description="t('views.export.noTaskDesc')"
        class="mb-3"
      >
        <el-button size="small" type="primary" :loading="creatingTask" class="mt-2" @click="createAndBindTask">
          {{ t('views.export.createAndEnter') }}
        </el-button>
      </el-alert>
      <div ref="aiExportSelectDefsRef">
        <DefSelector v-model="selectedDefs" :defs="defs" :loading="defsLoading" :disabled="isReadOnly" ai-ref-prefix="export.selectDefs" />
      </div>
      <div class="mt-2.5 text-[13px] text-[#909399]">
        {{ t('views.export.selectedSummary', { count: selectedDefs.length }) }}
      </div>
    </div>

    <!-- ============ 第 2 步：查询配置 ============ -->
    <div v-show="stepIndex === 1" ref="aiCondRef">
      <el-empty v-if="!selectedDefs.length" :description="t('views.export.emptySelect')" />
      <el-card v-for="code in selectedDefs" :key="code" shadow="never" class="mb-3">
        <template #header>
          <div class="flex items-center gap-3">
            <span class="font-semibold mr-auto">{{ defName(code) }}（{{ code }}）</span>
            <el-button
              v-if="!isReadOnly"
              size="small"
              :loading="countLoading[code] === true"
              :data-ai-ref="`export.cond.${code}.count`"
              data-ai-verbs="click,highlight"
              :data-ai-label="`预览 ${code} 行数`"
              @click="previewCount(code)"
            >{{ t('views.export.previewRows') }}</el-button>
            <el-tag v-if="counts[code] !== undefined" size="small" type="success">{{ t('views.export.hits', { count: counts[code] }) }}</el-tag>
          </div>
        </template>
        <ConditionForm
          :fields="fieldsOf(code)"
          :model-value="draftModels[code] ?? emptyDraftModel()"
          :disabled="isReadOnly"
          :ai-ref-prefix="`export.cond.${code}`"
          :error="condErrors[code] ?? ''"
          @update:model-value="(value) => onConditionChange(code, value)"
        />
      </el-card>
    </div>

    <!-- ============ 第 3 步：导出 ============ -->
    <div v-show="stepIndex === 2">
      <el-alert v-if="listsLoadError" type="error" :closable="false" show-icon class="mb-3">
        <div class="text-[13px]">{{ t('views.export.listsError.text', { reason: listsLoadError }) }}</div>
        <el-button size="small" type="primary" class="mt-2" :loading="retryingLists" @click="retryLoadLists">
          {{ t('views.export.listsError.retry') }}
        </el-button>
      </el-alert>
      <div class="flex items-center mb-3">
        <el-button v-if="!isReadOnly" type="primary" :loading="startingExport" :disabled="jobRunning" @click="onStartExportClick">
          {{ exportJob ? t('views.export.reExport') : t('views.export.startExport') }}
        </el-button>
        <template v-if="exportResults.length">
          <el-divider direction="vertical" />
          <el-button size="small" :loading="downloading === activeTab" @click="downloadOne(activeTab)">
            {{ t('views.export.downloadCurrent') }}
          </el-button>
          <el-button size="small" :disabled="!checkedDefs.length" :loading="downloading === 'checked'" @click="downloadChecked">
            {{ t('views.export.downloadChecked', { count: checkedDefs.length }) }}
          </el-button>
          <el-button size="small" :loading="downloading === 'all'" @click="downloadAll">{{ t('views.export.downloadAll') }}</el-button>
          <el-button v-if="!isReadOnly" size="small" :loading="savingFiles" @click="saveEdits">{{ t('views.export.saveEdits') }}</el-button>
        </template>
      </div>

      <JobProgress
        :job="exportJob"
        :issues="currentIssues"
        :channel="channelState"
        :error-message="jobErrorMessage"
        :read-only="isReadOnly"
        @cancel="cancelJob"
      />

      <el-alert v-if="resultsStale" type="warning" :closable="false" show-icon class="my-3">
        <div class="text-[13px]">{{ staleResultsText }}</div>
      </el-alert>

      <template v-if="exportResults.length">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="my-3"
          :title="t('views.export.editorHint')"
        />
        <el-checkbox-group v-model="checkedDefs" class="mb-2">
          <el-checkbox v-for="item in exportResults" :key="item.defCode" :value="item.defCode">
            {{ item.defCode }}
          </el-checkbox>
        </el-checkbox-group>
        <el-tabs v-model="activeTab" type="border-card" @tab-change="onTabChange">
          <el-tab-pane
            v-for="item in exportResults"
            :key="item.defCode"
            :name="item.defCode"
            :label="tabLabel(item)"
          >
            <SpreadGrid
              :ref="(el) => setGridRef(item.defCode, el)"
              height="440px"
              :read-only="isReadOnly"
              :def-code="item.defCode"
              :sheet-name="defName(item.defCode)"
              @data-changed="onGridChanged"
              @selection-changed="onGridSelectionChanged(item.defCode)"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </TaskWizard>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 导出配置向导（三步：选择配置 → 查询配置 → 导出）。
 *
 * 与蓝本的关键差别（功能差异，理由见证据文档 V6）：
 * 1. 条件表单与选中集**落库**（`POST /tasks/{id}/select-defs`、`PUT /tasks/{id}/items/{code}/condition`），
 *    刷新页面/换机恢复不丢；蓝本是前端 stepData 单存；
 * 2. 导出结果来自后端已落盘的 EXPORT 文件（`GET /tasks/{id}/files/{code}`）而不是内存行数组，
 *    与后端 ExportJobRunner/发布链路同源；
 * 3. 进度通道为 SSE（`JOB_PROGRESS`/`JOB_DONE`）+ 轮询兜底，蓝本是纯轮询。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefSelector from '@/components/wizard/DefSelector.vue'
import ConditionForm from '@/components/wizard/ConditionForm.vue'
import JobProgress from '@/components/JobProgress.vue'
import SpreadGrid from '@/components/SpreadGrid.vue'
import TaskWizard from '@/components/TaskWizard.vue'
import { definitionsApi } from '@/api/definitions'
import { dataApi } from '@/api/data'
import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { useTaskStore } from '@/stores/task'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { noteAiPage, registerAiRegion, unregisterAiRegion } from '@/utils/ai-regions'
import {
  assertConditionBuildable,
  buildConditionOutcome,
  buildQueryCondition,
  draftModelFromCondition,
  emptyDraftModel,
  InvalidConditionInputError,
  isConditionEmpty,
  conditionSummary,
  parseConditionJson,
  stringifyCondition,
  type ConditionDraftModel,
  type ConditionInputIssue,
  type FieldQueryCondition
} from '@/types/condition'
import type { ConfigDefinition, ConfigField } from '@/types/definition'
import type { Job, ValidationIssue } from '@/types/job'
import { TaskSteps, type Task, type TaskFile } from '@/types/task'
import type { WorkspaceActionSource } from '@/types/tools'
import {
  downloadBlob,
  xlsxFileName,
  zipBlobs,
  type ExcelRowIssue
} from '@/utils/excel'
import {
  DOWNLOAD_PREFLIGHT_TIMEOUT_MS,
  makeExecution,
  registerFrontendToolExecutor,
  unregisterFrontendToolExecutor,
  PAGE_HANDLER_OPEN_EXPORT_EDITOR
} from '@/utils/frontend-tools'
import { formatUiReceipt } from '@/utils/ui-receipt'
import type { SheetAdapter } from '@/utils/sheet-tools'
import { useAiStore } from '@/stores/ai'
import { stepLabel } from '@/utils/format'
import { watchJob, type JobChannelState } from '@/utils/job-watch'
import { confirmIrreversible } from '@/utils/confirm-irreversible'

const { t } = useI18n()

/** 表格宿主的最小接口（defineExpose 出来的方法）。 */
interface GridApi {
  loadFromXlsx(blob: Blob, fields: readonly ConfigField[]): Promise<{ missingHeaders: string[] }>
  loadData(fields: readonly ConfigField[], rows?: readonly Record<string, unknown>[]): void
  collectRows(): Array<Record<string, string>>
  toXlsxBlob(): Promise<Blob>
  validateLocal(): ExcelRowIssue[]
  getSheetAdapter(): SheetAdapter
  /** L-115：等表格内容落定（载入在途时挂起，无在途载入立即结算） */
  whenLoaded(): Promise<void>
}

interface ExportResultItem {
  defCode: string
  fileName: string
  rowCount: number
}

/** 步骤 key（与后端 @ToolScope 标签一致） */
const STEP_KEYS = TaskSteps.EXPORT
/** 步骤文案（与蓝本同名页一致；i18n L-69：label 由 locale 派生，渲染期读 ⇒ 切语言即重算） */
const WIZARD_STEPS = computed(() => [
  { key: STEP_KEYS[0], label: t('views.export.steps.select') },
  { key: STEP_KEYS[1], label: t('views.export.steps.query') },
  { key: STEP_KEYS[2], label: t('views.export.steps.export') }
])

const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const taskStore = useTaskStore()

const taskId = ref<number | null>(readTaskId())
const task = ref<Task | null>(null)
const defs = ref<ConfigDefinition[]>([])
const defsLoading = ref(false)
const creatingTask = ref(false)

const stepIndex = ref(0)
const selectedDefs = ref<string[]>([])
/**
 * 任务详情是否已**应用**到本页（选中集 / 条件草案 / 步骤 / 状态已按服务端事实恢复并 `enterPage`）。
 *
 * 为什么单独成一个事实：`restore_task` / 同页绑定只同步写 `workspace.taskId`，`/tasks/{id}`
 * 还在路上时本页的勾选集仍是空的。契约层据此等（`export.taskDetailReady` 探针，见
 * `utils/frontend-tools.ts` 的 `TASK_DETAIL_READY_PROBES`），否则紧随的 `confirm_step`
 * 会拿空选中集推进并向服务端落库（"AI 说按勾选导出，实际导出全量"）。
 *
 * 口径：只在 `enterPage` **之后**置 true，且不等定义列表 / 结果文件 / 作业 / 表格引擎 / 编辑器。
 */
const taskDetailReady = ref(false)
const draftModels = reactive<Record<string, ConditionDraftModel>>({})
const counts = reactive<Record<string, number | null>>({})
const countLoading = reactive<Record<string, boolean>>({})
/**
 * 第 2 步的**服务端**校验失败原因（按配置项编码）。挂账 L-67：这类失败此前只有
 * 一闪而过的 `ElMessage`，模型在 `export.cond` 上读不到「为什么不行」；现在它落到
 * 条件表单区域内的 `role=alert` 承载里，直到用户/AI 改动条件才清空。
 */
const condErrors = reactive<Record<string, string>>({})

const persisting = ref(false)
const startingExport = ref(false)
const savingFiles = ref(false)
const downloading = ref<string | null>(null)

const exportJob = ref<Job | null>(null)
const currentIssues = ref<ValidationIssue[]>([])
const channelState = ref<JobChannelState | null>(null)
const exportResults = ref<ExportResultItem[]>([])
const checkedDefs = ref<string[]>([])
const activeTab = ref('')

/**
 * 导出批次（代次）与"当前展示的是哪一批"。
 *
 * 重导出**不再**在建作业成功时清空结果面（那会让用户在作业跑完前既看不到旧结果、也不知道
 * 下载到的是哪一批文件）：旧结果继续展示，只把 `jobBatch` 推进；`jobBatch > resultsBatch`
 * 即"下面是旧结果"，模板据此标明代次，等新作业完成、`refreshResults` 重新铺开文件后追平。
 */
const jobBatch = ref(1)
const resultsBatch = ref(1)
/**
 * 清单读取失败（fail-closed 判据 + 可见重试入口）。
 *
 * 读取失败此前只落一条 toast（甚至静默吞成空列表）⇒ 页面拿"空结果"当"确实没有文件"，
 * 接着的保存/建作业会按错误前提写服务端。现在失败即置原因：写入口一律拒绝（禁写），
 * 模板给出「重试读取」按钮，读成功才清除。
 */
const filesLoadError = ref<string | null>(null)
const jobsLoadError = ref<string | null>(null)
const retryingLists = ref(false)
/**
 * 条件草案被**改过**的配置项编码。
 *
 * 为什么必须有：`persistStepData` 此前对每个选中项无条件 `PUT condition`，草案未载入/未改动时
 * 解析结果是 `{}`，一次切步就把服务端（AI 刚设置的）条件覆盖成"无条件 = 全量导出"。现在未改过的
 * 草案只在解析出**非空**条件时才写（与 `condition.ts` 的既有接口保持一致，不改该文件）。
 */
const touchedConditions = new Set<string>()

const gridRefs: Record<string, GridApi> = {}
/**
 * **已成功载入**的结果表格（内容字节确实在表格里）。
 *
 * 语义严格是"载入成功之后"才置位：`loadGrid` 原先在下载**之前**就 `add`，于是"下载在途"与
 * "已载入"无法区分 —— 在途时点「保存编辑到服务端」会拿 `toXlsxBlob()`（= 空工作簿）覆盖服务端
 * 真实导出结果（C2）。并发去重由 `gridLoads` 承担，不需要这个集合提前置位。
 */
const loadedGrids = new Set<string>()
/**
 * 任务代次（A→B 改绑 = 新一代）：在途的保存/建作业/载入结局一旦发现代次变了就作废。
 *
 * 导出页此前完全没有代次：`saveEdits` 在 `await toXlsxBlob()` 之后实时读 `taskId.value` 发 PUT，
 * `startExport` 在 `persistStepData()` 的多个 await 之后实时读 `taskId.value` 建作业 ——
 * 换任务发生在这些窗口里时，A 的选中集/条件/表格字节会落到 B（"A 写进 B"的同族）。
 */
let taskGeneration = 0
/**
 * 在途下载的配置编码（批 A 补：同码并发防重）。
 *
 * `downloading` 是**单值**（页面态，只能表示"当前哪一个在下载"），挡不住"同一次工具调用
 * 被重复投递 / 用户连点"这类**同码**并发 —— 后者在即发即回后必须挡住：同一文件被触发两次
 * 会弹出两次保存框。用户点击侧（`downloadOne`）与 AI 侧（`startDownloadInBackground`）共用本集合。
 */
const inFlightDownloads = new Set<string>()
/**
 * AI 批量打包中的页面态哨兵（R-156 `download_export_files` ≥2 码）。
 *
 * 与用户侧打包的两个哨兵（`'checked'` / `'all'`）区分开：它们的语义是"哪一批用户勾选"，
 * 而本哨兵表示"AI 发起的一笔多文件打包"—— 两者在途时互不冒充（`downloadingLabel` 也各给文案）。
 */
const AI_BATCH_DOWNLOADING = 'ai-batch'
let stopWatchJob: (() => void) | null = null
let gridDirty = false

const jobRunning = computed(() => exportJob.value !== null && ['PENDING', 'RUNNING'].includes(exportJob.value.status))

/** 下面展示的结果是否属于**上一批**（新作业已启动但还没成功换掉文件）。 */
const resultsStale = computed(() => resultsBatch.value < jobBatch.value)
/** 旧结果横幅（含代次与作业状态；切语言即重算）。 */
const staleResultsText = computed(() =>
  t('views.export.staleResults.text', {
    batch: resultsBatch.value,
    count: exportResults.value.length,
    jobBatch: jobBatch.value,
    jobId: exportJob.value?.id ?? '—',
    status: exportJob.value?.status ?? t('views.export.staleResults.unknownStatus')
  })
)
/** 清单读取失败的可见原因（文件清单优先，两处都失败则并列）。 */
const listsLoadError = computed(() => {
  const parts: string[] = []
  if (filesLoadError.value) {
    parts.push(t('views.export.listsError.files', { reason: filesLoadError.value }))
  }
  if (jobsLoadError.value) {
    parts.push(t('views.export.listsError.jobs', { reason: jobsLoadError.value }))
  }
  return parts.length > 0 ? parts.join('; ') : null
})

const taskSubtitle = computed(() => {
  if (!task.value) {
    return taskId.value ? t('views.export.taskBound', { id: taskId.value }) : ''
  }
  return `#${task.value.id} · ${task.value.title}`
})

/**
 * 步骤条节点级状态（下标与 `WIZARD_STEPS` 对齐）：只有第 3 步（导出）有作业，
 * 按该作业终态给色 —— 失败红 + 「（失败）」文字；取消中性灰（`el-step` 无
 * cancelled 档，用 'wait' 表达）。前两步是纯页面步骤，恒为 ''。
 */
const stepStatuses = computed<('' | 'error' | 'wait')[]>(() => [
  '',
  '',
  exportJob.value?.status === 'FAILED' ? 'error' : exportJob.value?.status === 'CANCELLED' ? 'wait' : ''
])

/**
 * 只读态判定（施工卡 §2.2-A）：终案「COMPLETED / CANCELLED 即结束」**不能照字面实现** ——
 * 后端 `JobTerminalWriter` 对**任何**作业终态都调 `TaskService.applyJobOutcome`，
 * 导入侧预检查 0 错误即把任务置 COMPLETED（同仓 `docs/evidence/S44d` 实测）。导出侧只有 EXPORT
 * 一种作业，故照字面实现与本口径同值，但仍统一取「该类型的**最终作业**已成功」为收口判据：
 * 导出流程走完 = 最新 EXPORT 作业 COMPLETED。
 *
 * `exportJob` 为 null（作业未恢复 / 绑定的是导入任务）时 `=== 'COMPLETED'` 天然为 false ⇒ 仍视为进行中。
 * `currentStep` 不能当判据：`TaskService.complete()`（唯一写 `DONE` 的方法）在仓内无调用点。
 */
const isReadOnly = computed(
  () =>
    task.value?.status === 'CANCELLED' ||
    (task.value?.type === 'EXPORT' &&
      task.value?.status === 'COMPLETED' &&
      exportJob.value?.status === 'COMPLETED')
)

const jobErrorMessage = computed(() => {
  if (!exportJob.value || exportJob.value.status !== 'FAILED') {
    return null
  }
  const first = currentIssues.value.find((issue) => issue.severity === 'ERROR')
  return first ? first.message : t('views.export.jobFailed', { count: exportJob.value.errorCount })
})

// ── 选中集 → 工作区（issue #3：本页唯一写入口） ────────────────────────────────

/**
 * 把页面选中集收敛进 workspace（AI 上下文 `extra.selectedDefs` 的唯一来源）。
 *
 * 为什么需要：手动勾选（`DefSelector` 的 v-model）与 AI 的 `select_definitions` 页面 handler
 * 此前都只改本页 `selectedDefs` ref，`workspace.selectedDefs` 仍停在 `enterPage` 时的值 ⇒
 * `buildContext().extra.selectedDefs` 永远滞后，模型看不到"用户刚勾了什么"（issue #3 根因）。
 *
 * **相等短路**（幂等收敛）：程序性恢复时 `loadTask` 在同一同步块内先赋 ref、紧接着
 * `enterPage({selectedDefs})`，watch 回调（flush: 'pre'）必然在其之后执行 ⇒ 此时工作区已等于
 * 页面值 ⇒ 短路，不重复 touch（否则每次恢复任务都会多记一条"界面"假动作、版本多 +1）。
 * 真实变化（手动勾选/减选、AI handler）才写入，并记入 `recentActions` 供模型感知。
 *
 * 比较按**集合**（顺序不敏感，裁决⑤）：同一集合仅顺序不同（表格行序 vs 任务 items 序）不算变化。
 * 本函数**不**调 `tasksApi.selectDefs`：逐个勾选都落库会把后端 `currentStep` 强制回退到
 * 选择步（遗留 L-1，已登记），落库仍只发生在切步/开始导出时的 `persistStepData`。
 */
function syncSelectedDefsToWorkspace(source: WorkspaceActionSource = '界面'): void {
  const codes = [...selectedDefs.value]
  const current = new Set(workspace.selectedDefs)
  if (current.size === codes.length && codes.every((code) => current.has(code))) {
    return
  }
  workspace.setSelectedDefs(codes, 'REPLACE', source)
}

function readTaskId(): number | null {
  const raw = Number(route.query.taskId)
  return Number.isFinite(raw) && raw > 0 ? raw : null
}

function defOf(code: string): ConfigDefinition | null {
  return defs.value.find((item) => item.code === code) ?? null
}

function defName(code: string): string {
  return defOf(code)?.name ?? code
}

function fieldsOf(code: string): readonly ConfigField[] {
  return defOf(code)?.fields ?? []
}

/**
 * 结果标签文案：旧批次（新作业已启动、还没换文件）在标签上点明代次，避免把上一批文件当成本次结果。
 */
function tabLabel(item: ExportResultItem): string {
  const base = t('views.export.optionLabel', { name: defName(item.defCode), rows: item.rowCount })
  return resultsStale.value
    ? `${base}${t('views.export.staleResults.tabSuffix', { batch: resultsBatch.value })}`
    : base
}

function setGridRef(code: string, el: unknown): void {
  if (!el) {
    delete gridRefs[code]
    return
  }
  gridRefs[code] = el as GridApi
}

// ── 页面加载 ───────────────────────────────────────────────────────────────────

async function loadDefinitions(): Promise<void> {
  defsLoading.value = true
  try {
    defs.value = await definitionsApi.listDefinitions()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.export.msg.defsLoadFailed'))
  } finally {
    defsLoading.value = false
  }
}

/** 任务详情与导出结果的加载结局（issue #2：跨任务执行器据此区分失败类型，给出不同文案）。 */
interface TaskLoadOutcome {
  /** 任务详情已加载（false ⇒ 任务不存在 / 请求失败） */
  taskOk: boolean
  /** 导出文件列表已读取（false ⇒ 列表请求失败；注意与"列表为空"是两回事） */
  filesOk: boolean
}

async function loadTask(preloaded?: Task): Promise<TaskLoadOutcome> {
  if (taskId.value === null) {
    taskDetailReady.value = false
    return { taskOk: false, filesOk: false }
  }
  const generation = taskGeneration
  // 详情重新起飞：在 `enterPage` 之前一律不认"已应用"
  taskDetailReady.value = false
  let loaded = preloaded ?? null
  if (loaded === null) {
    try {
      loaded = await tasksApi.getTask(taskId.value)
    } catch (error) {
      if (generation === taskGeneration) {
        taskDetailReady.value = false
        ElMessage.error(error instanceof Error ? error.message : t('views.export.msg.taskLoadFailed'))
      }
      return { taskOk: false, filesOk: false }
    }
  }
  // 旧任务的详情晚到：不得覆盖新任务的标题/步骤/勾选/条件
  if (generation !== taskGeneration) {
    return { taskOk: false, filesOk: false }
  }
  // 类型相反 ⇒ 本页不是它的向导：**静默**改投对应向导（不弹提示、不回灌"打开失败"），
  // 否则导出页会拿一条导入任务恢复自己的选中集/步骤（"导入任务显示成导出向导"）。
  if (loaded.type === 'IMPORT') {
    taskDetailReady.value = false
    await router.replace({ name: 'import-wizard', query: { taskId: String(taskId.value) } })
    return { taskOk: false, filesOk: false }
  }
  task.value = loaded
  selectedDefs.value = (loaded.items ?? []).map((item) => item.defCode)
  // 批次5A：条件必须随进页声明一起回填。`enterPage` 是「声明即全集」—— 未声明的
  // `queryConditions` 会被复位成 `{}`；只复位不恢复会让 `get_workspace_state` 把**有条件的**
  // 任务报成"全量"（模型据此以为用户没设过条件）。权威来源 = 任务条目的 `conditionJson`，
  // 与 `draftModels` 同一份解析（不前移成两份口径）。
  const restoredConditions: Record<string, FieldQueryCondition> = {}
  for (const item of loaded.items ?? []) {
    const condition = parseConditionJson(item.conditionJson)
    draftModels[item.defCode] = draftModelFromCondition(condition)
    // 空条件（null）= 该配置项按全量查询，没有可上报的条件事实（不写 null 占位）
    if (condition) {
      restoredConditions[item.defCode] = condition
    }
  }
  const restoredStep = STEP_KEYS.indexOf(loaded.currentStep as (typeof STEP_KEYS)[number])
  stepIndex.value = restoredStep >= 0 ? restoredStep : 0
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: STEP_KEYS[stepIndex.value],
    taskId: taskId.value,
    taskStatus: loaded.status,
    selectedDefs: [...selectedDefs.value],
    queryConditions: restoredConditions
  })
  // 详情已应用：选中集 / 条件草案 / 步骤 / 状态都已按服务端事实恢复并声明进工作区
  //（**不等**下面的作业恢复与结果文件列表 —— 它们不影响后续动作的正确性）
  taskDetailReady.value = true
  await resumeJobs()
  if (generation !== taskGeneration) {
    return { taskOk: false, filesOk: false }
  }
  return { taskOk: true, filesOk: await loadExistingResults() }
}

/**
 * 恢复未完成的导出作业（刷新页面后仍能看到进度与明细）。
 *
 * 镜像 `ImportWizardView.vue` 的 `resumeJobs`（O-3）：`listJobs` 是后端 `createdAt DESC, id DESC`
 * 序 ⇒ 取 `[0]` 即**最近一次** EXPORT 作业。只挂在 `loadTask` 上，**不进** `loadExistingResults`
 * （后者被 `refreshResults()` 反复调用，否则每次导出完成都会重挂一轮 watcher）。
 * 终态作业不再 `beginWatchJob`：结果已由 `loadExistingResults` 铺开，重复 watch 只会多一轮轮询。
 */
async function resumeJobs(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const generation = taskGeneration
  let jobs: Job[]
  try {
    jobs = await jobsApi.listJobs(taskId.value)
  } catch (error) {
    // fail-closed：作业清单读不到就不能"当没有作业"——否则页面上既看不出在跑的作业，
    // 又允许再建一个（重复导出）。原因落到可见重试入口，写入口据此禁写。
    if (generation === taskGeneration) {
      jobsLoadError.value = error instanceof Error ? error.message : t('views.export.msg.jobsListLoadFailed')
    }
    return
  }
  // 旧任务的作业列表晚到：不得挂到新任务的作业面板，更不得为新任务登记旧作业的 watcher
  if (generation !== taskGeneration) {
    return
  }
  jobsLoadError.value = null
  const job = jobs.filter((item) => item.jobType === 'EXPORT')[0]
  if (!job) {
    return
  }
  exportJob.value = job
  const jobId = job.id
  if (jobId === undefined) {
    return
  }
  try {
    const issues = (await jobsApi.getIssues(jobId)).content ?? []
    if (generation === taskGeneration) {
      currentIssues.value = issues
    }
  } catch {
    // 问题清单拉取失败不阻断主流程（同导入页 refreshIssues 的兜底口径）
  }
  if (generation !== taskGeneration) {
    return
  }
  if (job.status === 'PENDING' || job.status === 'RUNNING') {
    // 恢复的是**在途**作业：服务端此刻仍是上一批文件（作业在终态才写文件）⇒ 推进批次号，
    // 让 `resultsStale` 成立。否则刷新/重进页面后，旧文件会被当成"本次结果"展示与下载。
    jobBatch.value = Math.max(jobBatch.value, resultsBatch.value + 1)
    beginWatchJob(jobId, generation)
  }
}

/**
 * 恢复：任务里已存在的 EXPORT 文件即导出结果（刷新不丢）。
 *
 * @returns true = 文件列表已成功读取（列表为空也算成功）；false = 未绑定任务或读取失败。
 *   issue #2：调用方必须分清"目标任务确实没有导出文件"与"文件列表根本没读到"——
 *   后者若被当成空列表，就会给出假阴性（"暂无导出结果文件"）；沿用它任务的旧列表，则更糟。
 */
async function loadExistingResults(): Promise<boolean> {
  if (taskId.value === null) {
    return false
  }
  const generation = taskGeneration
  const forTask = taskId.value
  try {
    const files: TaskFile[] = await tasksApi.getFiles(forTask)
    // 旧任务的文件列表晚到：不得渲染到新任务的结果面
    if (generation !== taskGeneration) {
      return false
    }
    exportResults.value = files
      .filter((file) => file.fileType === 'EXPORT')
      .map((file) => ({
        defCode: file.defCode,
        fileName: file.fileName ?? xlsxFileName(file.defCode, defName(file.defCode)),
        rowCount: file.rowCount ?? 0
      }))
    filesLoadError.value = null
    // 作业在途时服务端仍是**上一批**文件（导出作业在终态才写文件）：此时读到的是旧批次，
    // 不能把旧结果贴上"本批"的标签（否则"重导出前保留旧结果 + 标明代次"就名存实亡）。
    resultsBatch.value = jobRunning.value ? Math.max(1, jobBatch.value - 1) : jobBatch.value
    if (exportResults.value.length > 0 && !activeTab.value) {
      activeTab.value = exportResults.value[0]?.defCode ?? ''
      await nextTick()
      await ensureGridLoaded(activeTab.value)
    }
    return true
  } catch (error) {
    // fail-closed：读不到清单 ≠ 没有文件。保留已有结果面（新作业成功前不抹掉旧结果），
    // 但把原因亮出来并禁止写服务端（保存编辑 / 开始导出），由用户点「重试读取」。
    if (generation === taskGeneration) {
      filesLoadError.value = error instanceof Error ? error.message : t('views.export.msg.filesListLoadFailed')
    }
    return false
  }
}

/**
 * 可见重试（清单读取 fail-closed 的配套出口）：重读作业清单与文件清单。
 *
 * 只有读成功才会清掉对应错误（`resumeJobs` / `loadExistingResults` 内部负责），
 * 失败则原因继续挂在模板上 —— 不允许"点一下就当好了"。
 */
async function retryLoadLists(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  retryingLists.value = true
  try {
    await resumeJobs()
    await loadExistingResults()
  } finally {
    retryingLists.value = false
  }
}

/**
 * 换任务时的页面态清理（issue #2）：上一个任务的导出结果/勾选/激活标签/表格缓存/在途下载集合一律不带到目标任务上。
 *
 * `loadedGrids` 必须清：`ensureGridLoaded` 拿它当"已加载"判据，跨任务遇到同名 defCode
 * （如两个任务都有 CURRENCY）时会直接跳过加载，编辑器里就还是**上一个任务**的字节
 * —— 与 issue 同源的"说 A 实际给 B"。
 *
 * R2（红队 S2）：清之前先看 `gridDirty` —— 未保存的在线编辑会随本次切换**丢弃**，
 * 必须显式提醒（READ 风险级的 open/download 不该静默吃掉用户数据）；只提醒、不阻断
 * （README 语义：READ 工具不加阻断门）。提醒后把 `gridDirty` 归零：内容确实已被丢弃，
 * 留着它只会在组件卸载时再报一次"有未保存编辑"的假警报。
 */
function resetTaskScopedState(): void {
  taskGeneration += 1
  // 换任务 = 详情重新起飞：在新任务 `enterPage` 之前，本页不得对外的"详情已应用"作任何背书
  taskDetailReady.value = false
  if (gridDirty) {
    ElMessage.warning(t('views.export.msg.unsavedDiscarded'))
    gridDirty = false
  }
  jobBatch.value = 1
  resultsBatch.value = 1
  filesLoadError.value = null
  jobsLoadError.value = null
  retryingLists.value = false
  touchedConditions.clear()
  exportResults.value = []
  checkedDefs.value = []
  activeTab.value = ''
  loadedGrids.clear()
  // 在途载入也是任务级：旧任务的下载/解析不能给新任务的 `sheet.current` 交底（L-115）。
  // 旧 promise 仍会自行收尾（finally 里有 `gridLoads.get(code) === run` 身份守卫），不会误删新条目。
  gridLoads.clear()
  // 在途集合也是任务级状态：跨任务改绑后残留的是**旧任务**的编码，AI 侧 preflight 读到它会把
  // 「旧任务在途的同码下载」当成新任务的在途，回灌 ack 分支②的"无需重试"（issue #2 的同源失真），
  // 用户侧 downloadOne 也会被误拦。复位安全：在途 promise 的 finally 有 `downloading.value === code`
  // 守卫、`Set.delete` 幂等，中途清空不会误改新任务的状态。
  inFlightDownloads.clear()
  downloading.value = null
  // 任务级 loading 标志（N1）：在途操作的 finally 有"仅同代次复位"的守卫，换任务后不会自己清；
  // 不复位就是新任务页面上的按钮永久转圈（下一步/保存编辑/开始导出/预览行数，直到刷新页面）。
  persisting.value = false
  startingExport.value = false
  savingFiles.value = false
  for (const code of Object.keys(countLoading)) {
    delete countLoading[code]
  }
}

/**
 * R3（红队 S3）：改绑时停掉**上一个任务**的作业跟踪并清空作业面板。
 *
 * 为什么必须做：`stopWatchJob` 原先只在组件卸载或新作业启动时调用，旧 watcher 会跨改绑继续
 * 轮询旧作业，其 `onFinal → refreshResults` 还会按**新** taskId 重载并 `loadedGrids.clear()`
 * ——既把新任务的未保存编辑冲掉，又在界面上显示另一个任务的作业号/进度。
 */
function stopJobWatchForTaskSwitch(): void {
  stopWatchJob?.()
  stopWatchJob = null
  exportJob.value = null
  currentIssues.value = []
  channelState.value = null
}

/**
 * 在途任务绑定记录（R1，红队 S2）。
 *
 * 为什么需要：绑定的"完成"由两方各自判据。`navigate_to` 侧等的是 `waitForTaskBound`
 * （只认 `workspace.taskId`），它在**同步绑定的那一刻**就放行；而本页的 `loadTask` /
 * `loadExistingResults` 还在路上。此窗口内同任务的 `open`/`download` 若因
 * `target === taskId.value` 直接放行，读到的就是刚被清空的 `exportResults=[]` ——
 * 报出"该任务现有导出结果：（无）"这种**带着任务号、更具误导性的假阴性**（探针实测）。
 */
let inFlightBinding: { taskId: number; promise: Promise<TaskLoadOutcome> } | null = null

/**
 * 绑定目标任务并**等详情与导出结果加载完**（issue #1 的 watch 与 issue #2 的跨任务执行器共用这一条链路）。
 *
 * 顺序与 `onMounted` 一致：先同步改绑（workspace + 路由 query），再拉详情与文件列表。
 * 返回加载结局供调用方判定（执行器在读 `exportResults` 之前必须拿到这个结论）；
 * 同时把"在途"登记进 {@link inFlightBinding}，供**由他人发起**绑定的场景（watch 路径）
 * 在紧随的动作里等待（R1）。
 */
async function applyTaskBinding(id: number, preloaded?: Task): Promise<TaskLoadOutcome> {
  // 交互专项：换任务 ⇒ 旧任务的表格预览一律失效（不允许拿旧计划改新任务的数据）
  useAiStore().onSheetPageLeft()
  resetTaskScopedState()
  stopJobWatchForTaskSwitch()
  taskId.value = id
  workspace.setTaskId(id, 'EXPORT', null)
  const promise = (async (): Promise<TaskLoadOutcome> => {
    await router.replace({ name: 'export-wizard', query: { taskId: String(id) } })
    return await loadTask(preloaded)
  })()
  inFlightBinding = { taskId: id, promise }
  try {
    return await promise
  } finally {
    // 只清理自己那一次登记（交错时会话已被后一次覆盖，不能误清别人）
    if (inFlightBinding?.promise === promise) {
      inFlightBinding = null
    }
  }
}

// ── 步骤导航与持久化 ───────────────────────────────────────────────────────────

/** 按步骤下标补齐该步的懒加载数据（写侧切步 `goStep` 与只读查看 `viewStep` 共用同一个钩子）。 */
async function ensureStepLoaded(index: number): Promise<void> {
  if (index === 2) {
    await refreshResults()
  }
}

/**
 * 步骤推进的结构化结局（批3③a D7）：goStep **永不 reject**（失败也 resolve，模板 `:14`
 * 直接绑定 `@prev`，reject 会变未处理拒绝）。
 * - persistStepData 失败 ⇒ **阻断推进**（勾选集/条件未落库，W-06 根面："AI 说设了条件、
 *   导出却是全量"）；
 * - syncStepToBackend 失败 ⇒ **不阻断**，但回灌/界面附带警告（网络抖动不得卡死向导，R-d）。
 */
interface StepAdvanceOutcome {
  /** 界面步骤是否真的推进了 */
  ok: boolean
  /** persistStepData 失败 ⇒ 阻断推进的原因 */
  reason?: string
  /** syncStepToBackend 失败 ⇒ 不阻断，调用方须把该警告带进回灌/界面 */
  syncWarning?: string
}

async function goStep(next: number): Promise<StepAdvanceOutcome> {
  if (next < 0 || next >= STEP_KEYS.length) {
    return { ok: false, reason: `目标步骤 ${next} 不在导出向导范围内` }
  }
  // 代次捕获（N2 导出孪生）：落库与步骤同步都是 await，换任务后不得把 A 的推进/响应算到 B 上
  const generation = taskGeneration
  const forTask = taskId.value
  // D7：先落库当前步数据再切步 —— persist 失败时界面必须停在原步（阻断推进）。
  const persisted = await persistStepData(generation, forTask)
  if (generation !== taskGeneration) {
    return { ok: false, reason: '任务已切换，本次未推进步骤' }
  }
  if (!persisted.ok) {
    return { ok: false, reason: persisted.reason ?? '保存选择与条件失败' }
  }
  stepIndex.value = next
  workspace.setStep(STEP_KEYS[next] ?? null)
  // ensureStepLoaded 不纳入结局（D7）：懒加载/结果刷新失败不改变"步骤已推进"的事实，
  // 由页面自身错误提示承载，发后不管。
  void ensureStepLoaded(next)
  // D7/D8：服务端 currentStep 同步失败不阻断界面，但警告必须可见、可回灌。
  const synced = await syncStepToBackend(generation, forTask)
  if (generation !== taskGeneration) {
    return { ok: false, reason: '任务已切换，本次未推进步骤' }
  }
  if (!synced.ok) {
    const syncWarning = '步骤已推进但服务端步骤同步失败，下次切步会重试'
    ElMessage.warning(syncWarning)
    return { ok: true, syncWarning }
  }
  return { ok: true }
}

/**
 * 只读态点节点查看（施工卡 §S3b）：只切本地步骤 + 补齐该步数据，**零服务端写入**。
 *
 * 与 `goStep` 的分工（终案"点节点 = 查看，不是编辑"）：不调 `persistStepData`（不落库选中集与条件）、
 * 不调 `workspace.setStep`（不把浏览动作写进 AI 渐进披露集）、不调 `syncStepToBackend`（不发 `PUT /step`）
 * —— 刷新后浏览位置仍以后端 `currentStep` 为准。
 * 第 3 步的标签懒加载仍走 `onTabChange → ensureGridLoaded`（`ensureStepLoaded(2)` 只负责刷结果与首个标签）。
 */
async function viewStep(index: number): Promise<void> {
  if (index < 0 || index >= STEP_KEYS.length) {
    return
  }
  stepIndex.value = index
  await nextTick()
  await ensureStepLoaded(index)
}

/**
 * 把当前步骤写回任务（`PUT /tasks/{id}/step`）。
 *
 * 为什么必须有这一步：后端 `currentStep` 是刷新恢复与 AI 上下文（`task:EXPORT/<step>` 的
 * 渐进披露子集）的唯一权威；只在前端切步不进后端，刷新会退回首步、且模型拿到的是旧步骤。
 *
 * **代次纪律（N2，与导入同形）**：任务号入口捕获、响应写回前复检 —— A 的步骤响应晚到时
 * 不得写进 B 的 `task.value`（那会让 B 的只读态/按钮按 A 的状态算）。
 */
async function syncStepToBackend(
  generation: number = taskGeneration,
  forTask: number | null = taskId.value
): Promise<{ ok: boolean; reason?: string }> {
  if (forTask === null) {
    return { ok: true }
  }
  const step = STEP_KEYS[stepIndex.value]
  if (!step) {
    return { ok: true }
  }
  try {
    const updated = await tasksApi.goToStep(forTask, step)
    if (generation === taskGeneration) {
      task.value = updated
    }
    return { ok: true }
  } catch (error) {
    // 批3③b D11（R-143①）：单点提示 —— 本函数**不自弹**（原 ElMessage.error 删除），由调用方
    // goStep 只弹一条 warning（全仓仅两个调用方：Export/Import 向导的 goStep，不留静默路径）。
    // 结构化失败结局照常返回，由 goStep 带进回灌/界面。
    const reason = error instanceof Error ? error.message : String(error)
    return { ok: false, reason }
  }
}

function nextStep(): void {
  if (stepIndex.value === 0 && selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.export.msg.pickAtLeastOne'))
    return
  }
  // 批3③a D7：goStep 永不 reject，结局由回灌/界面消费
  void goStep(stepIndex.value + 1)
}

/**
 * 非法空输入 → 本地化成文（批次1A）。
 *
 * `issues` 只带 fieldCode / issue code，文案全部在 `views.export.conditionInput.*`：
 * 逐条成文后再由完整模板包住。落库闸与行数预览共用同一套模板，本文件不新增硬编码中文。
 */
function describeConditionInputIssues(issues: readonly ConditionInputIssue[]): string {
  const separator = t('views.export.conditionInput.separator')
  const rendered = issues
    .map((issue) =>
      t('views.export.conditionInput.issue', {
        field: issue.fieldCode,
        reason: t(
          issue.code === 'EMPTY_IN'
            ? 'views.export.conditionInput.emptyIn'
            : 'views.export.conditionInput.emptyValue'
        )
      })
    )
    .join(separator)
  return t('views.export.conditionInput.invalid', { issues: rendered })
}

/**
 * `InvalidConditionInputError` 的成文（落库闸 catch 用）。
 *
 * 错误对象只带结构化 `issues`（不硬编码中文）；普通 Error 不走这里 —— 仍按原样透传
 * `error.message`，行为不变。
 */
function describeConditionInputError(error: InvalidConditionInputError): string {
  return describeConditionInputIssues(error.issues)
}

/**
 * 选中集与查询条件落库（后端是这两者的权威存储）。返回结构化结局（批3③a D7 阻断判据）。
 *
 * 任务号与代次都在**入口捕获**：落库跨多个 await，实时读 `taskId.value` 会在"落库途中换任务"时
 * 把 A 的选中集/条件写到 B（I2）。代次变了即中止并如实回报，调用方不得据此建作业。
 */
async function persistStepData(
  generation: number = taskGeneration,
  forTask: number | null = taskId.value
): Promise<{ ok: boolean; reason?: string }> {
  if (forTask === null) {
    return { ok: true }
  }
  persisting.value = true
  try {
    await tasksApi.selectDefs(forTask, [...selectedDefs.value])
    if (generation !== taskGeneration) {
      return { ok: false, reason: '任务已切换，本次未落库（未把上一个任务的选中集写到新任务上）' }
    }
    for (const code of selectedDefs.value) {
      const model = draftModels[code]
      if (!model) {
        continue
      }
      // 批次1A 的 fail-closed 入口：值算子空串 / IN 空取值会被压平成"无条件"。它抛出的
      // `InvalidConditionInputError` 由下方 catch 统一成文（condErrors + 阻断建作业），
      // 绝不把"非法空输入"当"全部数据"落库（否则一次切步就变成全量导出）。
      const condition = assertConditionBuildable(model)
      // 草案未改且解析为空 ⇒ **什么都不写**。服务端可能已有 AI 设置的条件（页面草案只是
      // 还没载入/没回填）；这里一度无条件下发 `{}`，等于把"AI 说设了条件"改成"无条件导出全量"。
      // 草案改过的（用户/AI 动过表单，含"故意清空"）与非空条件照原样落库。
      if (!touchedConditions.has(code) && isConditionEmpty(condition)) {
        continue
      }
      await tasksApi.setCondition(forTask, code, stringifyCondition(condition))
      if (generation !== taskGeneration) {
        return { ok: false, reason: '任务已切换，本次未完成落库（未把上一个任务的条件写到新任务上）' }
      }
      touchedConditions.delete(code)
    }
    const refreshed = await tasksApi.getTask(forTask)
    if (generation !== taskGeneration) {
      return { ok: false, reason: '任务已切换，本次未完成落库' }
    }
    task.value = refreshed
    // issue #3：与 watch / AI handler 共用同一收敛点（此处落库后工作区通常已相等 ⇒ 短路，不重复记动作）
    syncSelectedDefsToWorkspace()
    return { ok: true }
  } catch (error) {
    const reason = error instanceof InvalidConditionInputError
      ? describeConditionInputError(error)
      : error instanceof Error ? error.message : '保存选择与条件失败'
    if (generation !== taskGeneration) {
      return { ok: false, reason: '任务已切换，本次未完成落库' }
    }
    // 挂账 L-67：落库失败同样落在条件表单区域内（只弹一次 ElMessage 读不到「为什么不行」）
    for (const code of selectedDefs.value) {
      condErrors[code] = reason
    }
    ElMessage.error(reason)
    return { ok: false, reason }
  } finally {
    if (generation === taskGeneration) {
      persisting.value = false
    }
  }
}

// ── 第 2 步：行数预览 ──────────────────────────────────────────────────────────

/**
 * 条件表单改动（用户 / AI 回填都走这里）：写入草案模型并清掉该配置项上一次的服务端错误
 * —— 陈旧告警会误导模型「仍然不合法」（挂账 L-67）。
 */
function onConditionChange(code: string, value: ConditionDraftModel): void {
  draftModels[code] = value
  // 改过就登记：即使改成了空条件，也要落库（"故意清空"是用户的显式决定，不能被当成"未改"跳过）
  touchedConditions.add(code)
  delete condErrors[code]
}

/**
 * 行数预览：先判"压平结局"，非法空输入不得按"无条件 = 全部数据"去问服务端。
 *
 * - `invalid`（值算子空串 / IN 空取值）：**不发** count 请求；本地化原因落 `condErrors`
 *   （条件表单区域内 `role=alert` 可读），并清掉上一次的旧行数 —— 否则"全部数据 N 行"
 *   会把畸形输入说成合法全量；
 * - `cleared`（显式清空）：语义不变，仍按无条件预览"全部数据 N 行"；
 * - `ok`：正常计数。
 */
async function previewCount(code: string): Promise<void> {
  countLoading[code] = true
  try {
    const model = draftModels[code] ?? emptyDraftModel()
    const outcome = buildConditionOutcome(model)
    if (outcome.status === 'invalid') {
      const reason = describeConditionInputIssues(outcome.issues)
      condErrors[code] = reason
      delete counts[code]
      ElMessage.error(reason)
      return
    }
    const condition = outcome.condition
    const result = await dataApiCount(code, condition)
    counts[code] = result
    delete condErrors[code]
    ElMessage.success(
      isConditionEmpty(condition)
        ? `${code}：全部数据 ${result} 行`
        : `${code}：命中 ${result} 行（${conditionSummary(condition)}）`
    )
  } catch (error) {
    const reason = error instanceof Error ? error.message : '行数预览失败'
    // 挂账 L-67：服务端校验失败必须留痕在区域里（`ElMessage` 只负责即时提示，不承担可读性）
    condErrors[code] = reason
    ElMessage.error(reason)
  } finally {
    countLoading[code] = false
  }
}

async function dataApiCount(code: string, condition: ReturnType<typeof buildQueryCondition>): Promise<number> {
  const params: { conditions?: string } = {}
  if (!isConditionEmpty(condition)) {
    params.conditions = stringifyCondition(condition)
  }
  const response = await dataApi.countData(code, params)
  return response.count
}

// ── 第 3 步：导出作业 ──────────────────────────────────────────────────────────

/** `startExport` 的结构化结局；`notified` = 失败原因已自行 toast（按钮侧不重复提示）。 */
interface StartExportOutcome {
  ok: boolean
  reason?: string
  notified?: boolean
  job?: Job
  taskId?: number
  detached?: boolean
  attachError?: string
}

/**
 * 启动导出作业（按钮 / `export.start` 页面能力共用）。
 *
 * **落库闸**：导出作业读的是服务端 `TaskItem.conditionJson`，所以"条件没落库"就等于按**旧条件**
 * 导出（旧条件为空 ⇒ 导出全量），而界面会用用户看到的条件去解释结果 —— W-06 的"说 A 实际给 B"。
 * 故 `persistStepData()` 失败时**不建作业**，并把原因如实交给调用方。
 *
 * **读闸（fail-closed）**：作业/文件清单读不到时无法确认"现有结果与在途作业"，此时建作业可能
 * 重复导出、也可能把旧结果当成新结果 —— 一律不建，先让用户走「重试读取」。
 *
 * **脏闸**：重导出会替换服务端导出文件，表格里未保存的编辑随之丢失。界面入口先确认再带
 * `discardUnsaved`；AI 入口拿不到确认，故直接失败并给出可行动文案（不静默丢弃）。
 *
 * **真实性**：`createJob` 一旦成功，后续失败（挂接抛错、建作业期间换任务）都不得退化成"未启动"——
 * 作业已在后端跑，回执必须是"已建在任务 #N、本页未跟踪、请勿重复发起"（`detached`/`attachError`）。
 */
async function startExport(options: { discardUnsaved?: boolean } = {}): Promise<StartExportOutcome> {
  if (taskId.value === null) {
    const reason = t('views.export.msg.noTaskBound')
    ElMessage.warning(reason)
    return { ok: false, reason, notified: true }
  }
  if (selectedDefs.value.length === 0) {
    const reason = t('views.export.msg.pickDefs')
    ElMessage.warning(reason)
    return { ok: false, reason, notified: true }
  }
  if (listsLoadError.value) {
    return {
      ok: false,
      reason: t('views.export.msg.exportBlockedByLists', { reason: listsLoadError.value })
    }
  }
  if (gridDirty && exportResults.value.length > 0 && options.discardUnsaved !== true) {
    return {
      ok: false,
      reason: t('views.export.msg.exportBlockedByDirty')
    }
  }
  // 任务号与代次在入口捕获：落库 + createJob 都是 await，实时读 taskId.value 会在换任务后
  // 把 A 的条件落到 B、并在 B 上建作业（"旧条件启动作业"的跨任务变体）
  const generation = taskGeneration
  const forTask = taskId.value
  const persisted = await persistStepData(generation, forTask)
  if (!persisted.ok) {
    const reason = `查询条件与选中集未能落库（${persisted.reason ?? '保存失败'}），本次未启动导出作业`
      + ' —— 否则会按服务端旧条件导出（可能是全量数据）'
    return { ok: false, reason }
  }
  if (generation !== taskGeneration || forTask !== taskId.value) {
    return { ok: false, reason: '任务已切换，本次未启动导出作业（避免把作业建到另一个任务上）' }
  }
  startingExport.value = true
  let created: Job | null = null
  try {
    const job = await jobsApi.createJob(forTask, 'EXPORT')
    created = job
    // 建作业在途时换了任务：作业属于 forTask，但不得显示/跟踪在新任务的页面上
    if (generation !== taskGeneration || forTask !== taskId.value) {
      return { ok: true, job, taskId: forTask, detached: true }
    }
    exportJob.value = job
    // 新作业成功前**保留旧结果**，只推进批次号：模板据此把下面这批文件标成"第 N 次（旧结果）"，
    // 作业完成并 `refreshResults` 铺开新文件后自然追平。此前在这里清空结果面 ⇒ 作业跑完前
    // 页面既无结果也无从判断"下载到的是哪一批"。
    jobBatch.value += 1
    ElMessage.success(t('views.export.msg.jobStarted', { id: job.id ?? '' }))
    beginWatchJob(job.id ?? 0, generation)
    return { ok: true, job, taskId: forTask, detached: generation !== taskGeneration }
  } catch (error) {
    const reason = error instanceof Error ? error.message : t('views.export.msg.jobStartFailed')
    if (created !== null) {
      // 作业已建：不能说"未启动"
      return { ok: true, job: created, taskId: forTask, detached: generation !== taskGeneration, attachError: reason }
    }
    if (generation === taskGeneration) {
      ElMessage.error(reason)
    }
    return { ok: false, reason, notified: true }
  } finally {
    if (generation === taskGeneration) {
      startingExport.value = false
    }
  }
}

/**
 * 界面「开始导出」按钮：先判脏，未保存编辑必须显式确认丢弃才继续。
 *
 * 与 AI 入口的分工：`export.start` 拿不到用户确认，脏时一律失败并给出可行动文案；
 * 用户点击侧多这一步复选框确认（不可撤销：重导出会替换服务端文件）。
 */
async function onStartExportClick(): Promise<void> {
  if (gridDirty && exportResults.value.length > 0) {
    const confirmed = await confirmIrreversible({
      title: t('views.export.dialog.dirtyTitle'),
      message: t('views.export.dialog.dirtyMessage'),
      ack: t('views.export.dialog.dirtyAck')
    })
    if (!confirmed) {
      ElMessage.info(t('views.export.msg.dirtyCanceled'))
      return
    }
    const outcome = await startExport({ discardUnsaved: true })
    if (!outcome.ok && outcome.reason && outcome.notified !== true) {
      ElMessage.warning(outcome.reason)
    }
    return
  }
  const outcome = await startExport()
  if (!outcome.ok && outcome.reason && outcome.notified !== true) {
    ElMessage.warning(outcome.reason)
  }
}

function beginWatchJob(jobId: number, generation: number = taskGeneration): void {
  stopWatchJob?.()
  currentIssues.value = []
  if (taskId.value === null || jobId <= 0 || generation !== taskGeneration) {
    return
  }
  const forTask = taskId.value
  stopWatchJob = watchJob({
    taskId: forTask,
    jobId,
    onProgress: (payload) => {
      if (generation !== taskGeneration) {
        return
      }
      if (exportJob.value) {
        exportJob.value = { ...exportJob.value, status: 'RUNNING', progress: payload.processed, total: payload.total }
      }
    },
    onSnapshot: (job) => {
      if (generation !== taskGeneration) {
        return
      }
      exportJob.value = job
    },
    onChannel: (state) => {
      if (generation !== taskGeneration) {
        return
      }
      channelState.value = state
    },
    onFinal: async (job) => {
      if (generation !== taskGeneration) {
        return
      }
      exportJob.value = job
      const issues = (await jobsApi.getIssues(jobId)).content ?? []
      if (generation !== taskGeneration) {
        return
      }
      currentIssues.value = issues
      if (job.status === 'COMPLETED') {
        ElMessage.success(t('views.export.msg.exportDone'))
        await refreshResults()
      } else if (job.status === 'FAILED') {
        ElMessage.error(t('views.export.msg.exportFailed'))
      } else {
        ElMessage.info(t('views.export.msg.exportCancelled'))
      }
      // 任务状态由后端 JobTerminalWriter 联动作业终态，这里一律刷新快照（失败也要显示 FAILED），
      // 与导入页 refreshTaskSnapshot 同口径（收口修 F3：补上 workspace.setTaskId 状态同步与
      // try/catch 兜底 —— getTask 失败时不再裸 reject）；刷新后 isReadOnly 才能在场内随导出成功翻为 true。
      if (forTask !== null && generation === taskGeneration) {
        try {
          const snapshot = await tasksApi.getTask(forTask)
          if (generation === taskGeneration) {
            task.value = snapshot
            workspace.setTaskId(forTask, 'EXPORT')
            // 批次4 集成返修：状态单独写回（`buildContext` 顶层上报 ⇒ 后端披露面终态收窄）。
            // 不能只靠 setTaskId 的第三参：那条路径只在"绑定任务时"带一次状态，终态后刷新到的
            // COMPLETED/FAILED 就丢在这里，模型下一轮仍看到"可导出"的假入口。
            workspace.setTaskStatus(snapshot.status)
          }
        } catch {
          // 任务快照刷新失败不阻断作业结果的展示（同导入页 refreshTaskSnapshot 口径）
        }
      }
    }
  })
}

async function refreshResults(): Promise<void> {
  const generation = taskGeneration
  loadedGrids.clear()
  // 结果面被整批重载（作业完成 / 重新进入导出步）⇒ 表格内容会被服务端文件替换，
  // 之前"未保存"的在线编辑事实上已不存在。脏标记必须同时归零：否则新加的判脏闸会
  // 把每次重导出都拦成"有未保存编辑"，而用户根本没有可保存的内容。
  gridDirty = false
  await loadExistingResults()
  if (generation !== taskGeneration) {
    return
  }
  const first = exportResults.value[0]?.defCode ?? ''
  if (first) {
    activeTab.value = first
    await nextTick()
    await ensureGridLoaded(first)
  }
}

async function cancelJob(): Promise<void> {
  // R-136 收口修 F1（红队 MAJOR-1 修正版）：纵深防御 —— 红队已证导出侧不可达
  // （EXPORT 单作业类型，任务 CANCELLED ⟹ 该作业同事务 CANCELLED，无「在途残留」窗口），
  // 本守卫与同形未守卫代码对称收口，非「修复可达漏洞」。
  if (isReadOnly.value) {
    ElMessage.info(t('views.export.msg.taskEndedNoCancel'))
    return
  }
  const jobId = exportJob.value?.id
  if (jobId === undefined) {
    return
  }
  // 批4-D（用户 2026-10-23 裁决）：取消作业不可撤销 —— 由「一点即发」改为复选框确认。
  const confirmed = await confirmIrreversible({
    title: t('views.export.dialog.cancelTitle'),
    message: t('views.export.dialog.cancelMessage'),
    ack: t('views.export.dialog.cancelAck')
  })
  if (!confirmed) {
    return
  }
  try {
    await jobsApi.cancelJob(jobId)
    ElMessage.info(t('views.export.msg.cancelRequested'))
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : t('views.export.msg.cancelFailed'))
  }
}

// ── 结果表格（懒加载 + 编辑） ──────────────────────────────────────────────────

/**
 * S2-1：本函数**返回真实结局**，不再把失败只吞进 `ElMessage` —— 调用方
 * `PAGE_HANDLER_OPEN_EXPORT_EDITOR` 的 handler 必须据此回灌，否则引擎加载失败时会向模型
 * 谎报"已打开在线编辑器"（B-07 让 `waitForReady` 必结算后，这条吞错路径由不可达变可达）。
 * `error` 用 `error.message` 原文（`SpreadGrid#waitForReady` 已把引擎失败原文抛出）。
 * `ElMessage.error`（用户可见）与 `loadedGrids` 回滚（允许重试）保留不变。
 *
 * 与预算的关系（S2-2 后同值，如实登记）：`SpreadGrid` 的就绪看门狗同为 60s
 * （`READY_TIMEOUT_MS`），而前端工具预算折算后也是 60s（120s − 60s）——两者同值时预算可能
 * 先到，那次回灌会是**通用超时文案**（同样声明"结局未知"、不谎报），本函数的失败结局来不及
 * 被回灌；只有预算后到时才会回灌下面 handler 产出的"表格未就绪 + 引擎原文"。
 *
 * <p>**L-115 并发去重**：并发调用会拿到"已加载"的假肯定 —— 后来者若据此提前放行，读到的是
 * 还没载入的空表。故这里按 code 记住在途 promise：后来者**等同一个结局**，而不是提前放行；
 * `loadedGrids` 只在 `loadFromXlsx` **成功之后**置位（"在途"≠"已载入"，见 C2）。
 */
const gridLoads = new Map<string, Promise<{ ok: boolean; error: string }>>()

function ensureGridLoaded(code: string): Promise<{ ok: boolean; error: string }> {
  if (!code) {
    return Promise.resolve({ ok: false, error: '缺少配置编码' })
  }
  const inFlight = gridLoads.get(code)
  if (inFlight) {
    return inFlight
  }
  if (loadedGrids.has(code)) {
    return Promise.resolve({ ok: true, error: '' })
  }
  const run = loadGrid(code)
  gridLoads.set(code, run)
  void run.finally(() => {
    if (gridLoads.get(code) === run) {
      gridLoads.delete(code)
    }
  })
  return run
}

async function loadGrid(code: string): Promise<{ ok: boolean; error: string }> {
  if (taskId.value === null) {
    return { ok: false, error: '当前未绑定任务，无法读取结果文件' }
  }
  const generation = taskGeneration
  const forTask = taskId.value
  const grid = gridRefs[code]
  if (!grid) {
    return { ok: false, error: `${code} 的结果表格组件未挂载（请重开该标签页重试）` }
  }
  try {
    const blob = await tasksApi.downloadFile(forTask, code)
    // 下载在途时换了任务：旧任务的字节不得画进新任务的面板
    if (generation !== taskGeneration) {
      return { ok: false, error: '任务已切换，本次结果未载入' }
    }
    const { missingHeaders } = await grid.loadFromXlsx(blob, fieldsOf(code))
    if (generation !== taskGeneration) {
      return { ok: false, error: '任务已切换，本次结果未载入' }
    }
    // 内容真的进了表格才置位（C2：此前在下载前就置位 ⇒ 在途保存会写空工作簿）
    loadedGrids.add(code)
    if (missingHeaders.length > 0) {
      ElMessage.warning(t('views.export.msg.missingHeaders', { code, headers: missingHeaders.join(', ') }))
    }
    // 交互专项：重跑导出 / 换任务重载 ⇒ 旧预览立即失效（硬闸仍是 previewId）
    useAiStore().onSheetReloaded(code)
    return { ok: true, error: '' }
  } catch (error) {
    if (generation === taskGeneration) {
      loadedGrids.delete(code)
      const reason = error instanceof Error ? error.message : `${code} 结果文件加载失败`
      ElMessage.error(reason)
      return { ok: false, error: reason }
    }
    return { ok: false, error: '任务已切换，本次结果未载入' }
  }
}

function onTabChange(name: string | number): void {
  // B12：换到另一张表 ⇒ 旧预览计划失效（切回同一张表不动）
  useAiStore().onSheetTabChanged(String(name))
  void ensureGridLoaded(String(name))
}

/** B12：用户换选区 ⇒ 选区作用域的预览计划失效（整表/条件计划不受影响）。 */
function onGridSelectionChanged(defCode: string): void {
  useAiStore().onSheetSelectionChanged(defCode)
}

function onGridChanged(): void {
  const firstChange = !gridDirty
  gridDirty = true
  workspace.bumpVersion()
  if (firstChange) {
    // 契约联动：把"用户改了导出结果表格"同步给 AI 上下文（同一批编辑只记一次）
    workspace.touch('导出结果表格内容已修改（尚未保存到服务端文件）')
  }
  // AI 表格工具治理 §5.4：手改之后重算「AI 改动 / 你的改动」的现算部分（只计数，不记坐标）
  void useAiStore().refreshSheetApplied(activeTab.value)
}

/**
 * 一次取字节的结局：字节来源（在线编辑表格 / 服务端导出文件）+ 服务端记录行数。
 *
 * `source` 是"真实 0 行 / 未载入 / 失败"三态可区分的载体：
 * - `grid` = 表格里确实是这个文件（含在线编辑）⇒ 行数以表格为准，`rowCount` 仅作服务端参考；
 * - `server` = 该标签未载入（或载入失败）⇒ 回落服务端导出文件字节；`rowCount === 0` 是
 *   **真实的空结果**，不是"没载入"；
 * - 失败一律 throw（调用方必须显式失败，不允许产出空文件）。
 */
interface XlsxSource {
  blob: Blob
  fileName: string
  source: 'grid' | 'server'
  /** 服务端记录的行数（file.rowCount；缺失记 0） */
  rowCount: number
}

/**
 * 取某配置项的下载用 xlsx：**先确保表格已载入**，载入不了才回落服务端字节。
 *
 * 修复面：此前只要表格组件在，就直接 `toXlsxBlob()` —— 未载入的标签返回的是**空工作簿**，
 * 于是"下载"静默产出空文件（用户以为拿到了导出结果）。现在：
 * 1. 表格已载入 ⇒ 用表格当前内容（在线编辑不丢）；
 * 2. 表格未载入/载入失败 ⇒ 回落 `GET /tasks/{id}/files/{code}` 的服务端字节；
 * 3. 回落也失败 ⇒ 抛错点名"未载入原因 + 回落失败原因"，批量调用方整批不下载。
 */
async function buildXlsx(code: string): Promise<XlsxSource> {
  const item = exportResults.value.find((entry) => entry.defCode === code)
  const fileName = item?.fileName ?? xlsxFileName(code, defName(code))
  const rowCount = item?.rowCount ?? 0
  // 任务号与代次在**入口**捕获：`ensureGridLoaded` / `toXlsxBlob` / 回落下载都是 await，
  // 期间换任务会把 A 的表格字节（或 A 的结果文件）当成 B 的文件交出去。代次一变即中止。
  const generation = taskGeneration
  const forTask = taskId.value
  const switched = (): boolean => generation !== taskGeneration || forTask !== taskId.value
  const grid = gridRefs[code]
  if (grid) {
    // 在途载入会等同一个结局（L-115）；已载入则立即结算，不会重复下载
    const loaded = await ensureGridLoaded(code)
    if (switched()) {
      throw new Error(t('views.export.msg.taskSwitchedNoDownload'))
    }
    if (loaded.ok) {
      const blob = await grid.toXlsxBlob()
      if (switched()) {
        throw new Error(t('views.export.msg.taskSwitchedNoDownload'))
      }
      return { blob, fileName, source: 'grid', rowCount }
    }
    const loadReason = loaded.error || t('views.export.msg.gridNotLoaded')
    if (forTask === null) {
      throw new Error(t('views.export.msg.downloadNotLoadedNoTask', { code, reason: loadReason }))
    }
    let fallback: Blob
    try {
      fallback = await tasksApi.downloadFile(forTask, code)
    } catch (error) {
      throw new Error(t('views.export.msg.downloadFallbackFailed', {
        code,
        reason: loadReason,
        error: error instanceof Error ? error.message : String(error)
      }))
    }
    if (switched()) {
      throw new Error(t('views.export.msg.taskSwitchedNoDownload'))
    }
    return { blob: fallback, fileName, source: 'server', rowCount }
  }
  if (forTask === null) {
    throw new Error(t('views.export.msg.noTask'))
  }
  const blob = await tasksApi.downloadFile(forTask, code)
  if (switched()) {
    throw new Error(t('views.export.msg.taskSwitchedNoDownload'))
  }
  return { blob, fileName, source: 'server', rowCount }
}

/** 逐码的字节来源说明（AI 回执用：发起时即可区分"在线编辑内容 / 回落服务端 / 真实 0 行"）。 */
function describeDownloadSource(code: string): string {
  const item = exportResults.value.find((entry) => entry.defCode === code)
  const rows = item === undefined ? '服务端行数未知' : item.rowCount === 0 ? '服务端记录 0 行（真实空结果，不是未载入）' : `服务端记录 ${item.rowCount} 行`
  return gridRefs[code] && loadedGrids.has(code) ? `${code} = 在线编辑后的当前表格（${rows}）` : `${code} = 标签未载入，回落服务端导出文件字节（${rows}）`
}

function describeDownloadSources(codes: readonly string[]): string {
  return codes.map(describeDownloadSource).join('；')
}

/** 下载成功 toast 的成文（含字节来源与真实行数 —— 三态可区分，文案全部走 locale）。 */
function downloadedText(info: XlsxSource): string {
  return t('views.export.msg.downloadedWithNote', {
    name: info.fileName,
    origin: info.source === 'grid'
      ? t('views.export.msg.sourceGrid')
      : t('views.export.msg.sourceServerFallback'),
    rows: info.rowCount === 0
      ? t('views.export.msg.serverRowsZero')
      : t('views.export.msg.serverRows', { count: info.rowCount })
  })
}

/** 一次下载的结局（确定性面：字节构建 + 交给浏览器下载链路这一步是否成功）。 */
type DownloadOutcome =
  | { ok: true; fileName: string; source: 'grid' | 'server'; rowCount: number }
  | { ok: false; reason: string }

/**
 * 一次下载**发起时**的「结果批次绑定」：批次号 + 任务代次 + 任务号。
 *
 * 为什么需要：`buildXlsx`（表格载入 / 服务端回落）与 `zipBlobs` 都是 await —— 期间一个导出作业
 * 完成会把**新一批**结果整批铺开（`resultsBatch` 前进、`exportResults` 整体替换）。没有这道绑定，
 * 构建晚到的字节照样交给浏览器 ⇒ 用户 / AI 拿到的是"上一批"的导出文件，而页面已显示新批次。
 */
interface DownloadBinding {
  batch: number
  generation: number
  task: number | null
}

function captureDownloadBinding(): DownloadBinding {
  return { batch: resultsBatch.value, generation: taskGeneration, task: taskId.value }
}

/** 本次下载的绑定是否已失效（批次被换掉 / 任务被换掉 ⇒ 已构建的字节不属于当前结果面）。 */
function downloadBindingBroken(binding: DownloadBinding): boolean {
  return binding.batch !== resultsBatch.value
    || binding.generation !== taskGeneration
    || binding.task !== taskId.value
}

/**
 * 下载在构建/打包期间被换批**作废**（不是失败）：不触发 `link.click`、不下载旧批次字节。
 *
 * 用独立错误类型而不是普通 `Error`：两侧 `catch` 要把"作废"与"真失败"分开 —— 前者只给一句
 * 简短提示（实际冲突才提示，不新增常驻横幅），也不计入"打包下载失败"。
 */
class DownloadSuperseded extends Error {
  constructor() {
    super(t('views.export.msg.downloadSuperseded', { batch: resultsBatch.value }))
    this.name = 'DownloadSuperseded'
  }
}

/**
 * 「发起」一次下载（**不吞异常**）：取该配置项的当前内容并交给浏览器下载链路。
 *
 * 与 {@link downloadOne} 的分工：本函数不捕获任何异常（`buildXlsx` 的引擎/网络错误、
 * 未绑定任务一律向调用方传播），页面态与 toast 由调用方决定 —— 因此既能支撑用户点击侧的
 * 结局 toast，也能支撑 AI 侧「即发即回」（{@link startDownloadInBackground}）。
 */
async function startDownload(code: string): Promise<XlsxSource> {
  // 发起时绑定结果批次：`buildXlsx` 的 await 期间新一批结果可能已经铺开 ⇒ 作废本次下载
  const binding = captureDownloadBinding()
  const built = await buildXlsx(code)
  if (downloadBindingBroken(binding)) {
    throw new DownloadSuperseded()
  }
  downloadBlob(built.blob, built.fileName)
  return built
}

/**
 * 用户侧下载入口（按钮 / 勾选下载 / 全部下载）：在途防重 + 结局 toast。
 *
 * 结局以 {@link DownloadOutcome} 如实捎回 —— 失败不再只是"一条 toast"，而是可被调用方
 * 判读的结构化结局（旧版返回 `void` + 只 toast，是"下载失败却回灌成功"的一半成因，见 W-02）。
 */
async function downloadOne(code: string): Promise<DownloadOutcome> {
  if (!code) {
    ElMessage.warning(t('views.export.msg.pickDownload'))
    return { ok: false, reason: '未提供配置编码' }
  }
  if (inFlightDownloads.has(code)) {
    ElMessage.info(t('views.export.msg.downloadInFlight', { code }))
    return { ok: false, reason: `${code} 的下载已在进行中` }
  }
  inFlightDownloads.add(code)
  downloading.value = code
  try {
    const built = await startDownload(code)
    ElMessage.success(downloadedText(built))
    return { ok: true, fileName: built.fileName, source: built.source, rowCount: built.rowCount }
  } catch (error) {
    if (error instanceof DownloadSuperseded) {
      // 批次在构建期间被换掉：只提示一句，不当成失败（也确实没有文件被下载）
      ElMessage.info(error.message)
      return { ok: false, reason: error.message }
    }
    const reason = error instanceof Error ? error.message : '下载失败'
    ElMessage.error(reason)
    return { ok: false, reason }
  } finally {
    inFlightDownloads.delete(code)
    if (downloading.value === code) {
      downloading.value = null
    }
  }
}

/**
 * AI 侧「即发即回」（批 A = W-01 + W-02）：发起下载后**不等**浏览器下载链路的结局。
 *
 * 为什么可以不等：AI 不消费这个结局（`download_export_files` 的回灌只需"已发起"这一事实），
 * 而 `await` 它的代价是回灌被链路拖住 —— issue#6 实测：宿主浏览器弹出保存框时回灌 POST
 * 130 秒仍未发出，后端按超时收尾并回"本次调用未获得数据"（假失败，文件其实已落盘）。
 *
 * 迟到的结局只落在**用户侧**：toast + `downloading` 复位 + 在途集合清理。本批**不新增
 * "迟到结局二次回灌"通道**（`ConfirmGate#submitFrontendResult` 对已决条目一律 409 幂等丢弃），
 * 故下面的 `.catch` 只把真实原因 toast 给用户，绝不再产出一份回执。
 */
function startDownloadInBackground(code: string): void {
  inFlightDownloads.add(code)
  downloading.value = code
  startDownload(code)
    .then((built) => {
      ElMessage.success(downloadedText(built))
    })
    .catch((error: unknown) => {
      if (error instanceof DownloadSuperseded) {
        // 换批作废：一句提示即可（不弹错误、也不产出回执）
        ElMessage.info(error.message)
        return
      }
      ElMessage.error(error instanceof Error ? error.message : t('views.export.msg.downloadFailed'))
    })
    .finally(() => {
      inFlightDownloads.delete(code)
      if (downloading.value === code) {
        downloading.value = null
      }
    })
}

/**
 * AI 侧批量打包（R-156，`download_export_files` 的 ≥2 码分支）：逐码取当前表格字节 →
 * `zipBlobs` 合成**一个** zip → `downloadBlob` **单次**浏览器下载。
 *
 * 与 {@link startDownloadInBackground} 同一条「即发即回」口径：本函数不 `await`（调用方
 * 立即回灌 ack），落盘成败只落在用户侧（toast + 在途集合清理）—— 即 N 个文件也只产生
 * 一次下载，绕过浏览器对"自动连续下载"的拦截（issue #9 的根因）。打包链路与用户侧
 * 「全部打包 zip」完全同源（`buildXlsx` 取在线编辑后的表格 + `zipBlobs`），未保存的编辑不丢。
 */
function startBatchDownloadInBackground(codes: readonly string[]): void {
  const batch = [...codes]
  const task = taskId.value ?? 0
  // 同 `startDownload`：逐码取字节与 `zipBlobs` 都是 await，期间换批即作废整包
  // （否则会把上一批的字节打成 zip 交给浏览器，而页面已显示新一批结果）
  const binding = captureDownloadBinding()
  for (const code of batch) {
    inFlightDownloads.add(code)
  }
  downloading.value = AI_BATCH_DOWNLOADING
  Promise.all(batch.map(async (code) => {
    const { blob, fileName } = await buildXlsx(code)
    return { name: fileName, blob }
  }))
    .then((entries) => {
      // 逐码取字节这一段是 await：换批就到此为止，连 zip 都不必合成
      if (downloadBindingBroken(binding)) {
        throw new DownloadSuperseded()
      }
      return zipBlobs(entries)
    })
    .then((zipped) => {
      // `zipBlobs` 也是异步：交付浏览器前再复检一次，绝不把上一批的 zip 交出去
      if (downloadBindingBroken(binding)) {
        throw new DownloadSuperseded()
      }
      downloadBlob(zipped, `export-${task}.zip`)
      ElMessage.success(t('views.export.msg.zipDownloaded', { count: batch.length }))
    })
    .catch((error: unknown) => {
      if (error instanceof DownloadSuperseded) {
        // 换批作废 ≠ 打包失败：一句提示，且确实一个字节都没交给浏览器
        ElMessage.info(error.message)
        return
      }
      // 整批显式失败：不产出残缺 zip、不下载一部分（否则用户以为拿到了全部文件）
      ElMessage.error(t('views.export.msg.zipBatchFailed', {
        count: batch.length,
        reason: error instanceof Error ? error.message : t('views.export.msg.zipFailed')
      }))
    })
    .finally(() => {
      for (const code of batch) {
        inFlightDownloads.delete(code)
      }
      if (downloading.value === AI_BATCH_DOWNLOADING) {
        downloading.value = null
      }
    })
}

async function downloadChecked(): Promise<void> {
  if (checkedDefs.value.length === 0) {
    ElMessage.warning(t('views.export.msg.pickZipDefs'))
    return
  }
  const batch = [...checkedDefs.value]
  downloading.value = 'checked'
  try {
    if (batch.length === 1) {
      // 单文件：结局（含失败原因）由 `downloadOne` 自己 toast，此处不判读返回值、也不重复报错
      await downloadOne(batch[0] ?? '')
      return
    }
    const entries = await Promise.all(batch.map(async (code) => {
      const { blob, fileName } = await buildXlsx(code)
      return { name: fileName, blob }
    }))
    downloadBlob(await zipBlobs(entries), `export-selected-${taskId.value ?? 0}.zip`)
    ElMessage.success(t('views.export.msg.zipDownloaded', { count: entries.length }))
  } catch (error) {
    ElMessage.error(t('views.export.msg.zipBatchFailed', {
      count: batch.length,
      reason: error instanceof Error ? error.message : t('views.export.msg.zipFailed')
    }))
  } finally {
    if (downloading.value === 'checked') {
      downloading.value = null
    }
  }
}

async function downloadAll(): Promise<void> {
  if (exportResults.value.length === 0) {
    ElMessage.warning(t('views.export.msg.noResults'))
    return
  }
  const batch = exportResults.value.map((item) => item.defCode)
  downloading.value = 'all'
  try {
    if (batch.length === 1) {
      // 单文件：结局（含失败原因）由 `downloadOne` 自己 toast，此处不判读返回值、也不重复报错
      await downloadOne(batch[0] ?? '')
      return
    }
    const entries = await Promise.all(batch.map(async (code) => {
      const { blob, fileName } = await buildXlsx(code)
      return { name: fileName, blob }
    }))
    downloadBlob(await zipBlobs(entries), `export-${taskId.value ?? 0}.zip`)
    ElMessage.success(t('views.export.msg.zipDownloaded', { count: entries.length }))
  } catch (error) {
    ElMessage.error(t('views.export.msg.zipBatchFailed', {
      count: batch.length,
      reason: error instanceof Error ? error.message : t('views.export.msg.zipFailed')
    }))
  } finally {
    if (downloading.value === 'all') {
      downloading.value = null
    }
  }
}

/**
 * 把在线编辑结果写回服务端文件（`PUT /tasks/{id}/files/{defCode}`）。
 *
 * 为什么要这一步：后端后续步骤（导入/发布/AI 工具）读的是**服务端文件**，
 * 只在浏览器里改 excel 不回写，别的入口看到的还是旧内容。
 *
 * **读闸**：只写 `loadedGrids` 里的配置项（= `loadFromXlsx` 已成功，表格里确实是这个文件的内容；
 * "下载在途"不在其中）。未载入的表格 `toXlsxBlob()` 是一本空工作簿 —— PUT 上去就是拿空文件
 * 覆盖服务端已有导出结果（下载失败 / 在途 / 只打开部分标签页都会走到这条路径）。
 *
 * **代次闸**：任务号与代次入口捕获，逐项复检 —— `toXlsxBlob()` 是 await，期间换任务时
 * A 的表格字节不得写到 B。
 *
 * **结局口径**：只有"每一个结果文件都成功写回"才算成功并清掉脏标记。校验不过、未载入、
 * 写回失败、任务切换一律算**失败**（如实回报 + 脏标记保留）—— 此前校验失败只是 `continue`，
 * 最后照样 `gridDirty = false` + 成功 toast，等于把"没保存"说成"已保存"。
 */
interface SaveOutcome {
  ok: boolean
  saved: string[]
  skipped: string[]
  invalid: string[]
  reason?: string
}

async function saveEdits(): Promise<SaveOutcome> {
  if (taskId.value === null) {
    ElMessage.warning(t('views.export.msg.noTask'))
    return { ok: false, saved: [], skipped: [], invalid: [], reason: t('views.export.msg.noTask') }
  }
  if (listsLoadError.value) {
    // fail-closed：清单都没读到，无法确认服务端现有文件是否就是要写的这份
    const reason = t('views.export.msg.saveBlockedByLists', { reason: listsLoadError.value })
    ElMessage.warning(reason)
    return { ok: false, saved: [], skipped: [], invalid: [], reason }
  }
  const generation = taskGeneration
  const forTask = taskId.value
  savingFiles.value = true
  const saved: string[] = []
  const skipped: string[] = []
  const invalid: string[] = []
  let aborted = false
  let failed: string | null = null
  try {
    for (const item of exportResults.value) {
      if (generation !== taskGeneration || forTask !== taskId.value) {
        aborted = true
        break
      }
      const grid = gridRefs[item.defCode]
      if (!grid || !loadedGrids.has(item.defCode)) {
        skipped.push(item.defCode)
        continue
      }
      const issues = grid.validateLocal()
      if (issues.length > 0) {
        invalid.push(item.defCode)
        const first = issues[0]
        ElMessage.warning(t('views.export.msg.localIssues', {
          code: item.defCode,
          count: issues.length,
          row: first?.rowIndex ?? '',
          reason: first?.reason ?? ''
        }))
        continue
      }
      const blob = await grid.toXlsxBlob()
      if (generation !== taskGeneration || forTask !== taskId.value) {
        aborted = true
        break
      }
      await tasksApi.saveFile(forTask, item.defCode, await blob.arrayBuffer())
      saved.push(item.defCode)
    }
  } catch (error) {
    failed = error instanceof Error ? error.message : t('views.export.msg.saveToServerFailed')
    if (generation === taskGeneration) {
      ElMessage.error(failed)
    }
  } finally {
    if (generation === taskGeneration) {
      savingFiles.value = false
    }
  }
  if (aborted) {
    ElMessage.warning('任务已切换，本次保存中止（未把上一个任务的内容写到新任务上）')
    return { ok: false, saved, skipped, invalid, reason: t('views.export.msg.saveAborted') }
  }
  const incomplete = skipped.length + invalid.length
  if (failed !== null || incomplete > 0) {
    const parts: string[] = []
    if (saved.length > 0) {
      parts.push(t('views.export.msg.savePartSaved', { count: saved.length, codes: saved.join('、') }))
    }
    if (invalid.length > 0) {
      parts.push(t('views.export.msg.savePartInvalid', { count: invalid.length, codes: invalid.join('、') }))
    }
    if (skipped.length > 0) {
      parts.push(t('views.export.msg.savePartSkipped', { count: skipped.length, codes: skipped.join('、') }))
    }
    if (failed !== null) {
      parts.push(t('views.export.msg.savePartWriteFailed', { reason: failed }))
    }
    const reason = t('views.export.msg.saveIncomplete', { parts: parts.join('; ') })
    ElMessage.warning(reason)
    return { ok: false, saved, skipped, invalid, reason }
  }
  gridDirty = false
  ElMessage.success(t('views.export.msg.savedToServer'))
  return { ok: true, saved, skipped: [], invalid: [] }
}

// ── AI 页面能力（裁剪性：这些登记只影响 AI，手工操作不依赖它） ────────────────

const HANDLER_NAMES = [
  'export.selectDefs',
  'export.setConditions',
  'export.start',
  'export.nextStep',
  PAGE_HANDLER_OPEN_EXPORT_EDITOR,
  'sheet.current',
  // 批 D · D3（问题 6）：AI 触发 start_export 后把作业绑进本页跟踪面
  'export.syncJob',
  // 任务详情就绪探针（只读）：契约层导航/幂等判据据此等"选中集/条件/步骤已恢复"
  'export.taskDetailReady'
]

/** visop 批 B：两个新增区域的 root（第 1 步配置项选择 / 第 2 步查询条件）。 */
const aiExportSelectDefsRef = ref<HTMLElement | null>(null)
const aiCondRef = ref<HTMLElement | null>(null)

/** 跨任务绑定的结局（供两个执行器给出互相可区分、对 AI 可行动的文案）。 */
type ToolTaskGate = { ok: true; switched: boolean } | { ok: false; message: string }

/**
 * issue #2：AI 传进来的 `taskId` 可能**不是**当前页面绑定的任务（"帮我打开任务 1 的导出文件编辑器"）。
 *
 * 执行器若直接读 `exportResults`，读到的永远是**当前任务**的页面状态，于是同一句调用有两种坏结局
 * （均已在真实浏览器探针里复现）：
 * - 目标任务有文件、当前任务没有 ⇒ 假阴性："暂无导出结果文件，请先完成导出"（issue 原文）；
 * - 当前任务恰好有**同名配置**的导出文件 ⇒ 张冠李戴：打开了另一个任务的同名文件却回灌成功。
 *
 * 因此：不一致时先完成绑定与导出结果加载（复用 issue #1 的绑定链路 {@link applyTaskBinding}），
 * 再做 `exportResults` 检查。三类失败互不相同：
 * 1. 未提供有效 taskId ⇒ 沿用当前绑定（与改动前一致，不误伤只传 defCode 的调用）；
 * 2. 任务不存在/加载不了 ⇒ **不改绑、不改路由**，向导停在原任务，AI 应改用 list_tasks 核对；
 * 3. 已切换但文件列表读取失败 ⇒ 已改绑，但结论不可用，AI 应稍后重试或让用户在界面上确认。
 *
 * R1（红队 S2）：`target === taskId.value` **不代表加载已完成** —— 同一 id 的绑定可能是
 * 由 watch（`navigate_to`）刚发起的、仍在途；此处先 `await` 那次在途绑定再放行。
 * 异 id 的在途绑定按既有竞态口径处理（本批不新增序号守卫，R5 已合并 issue #1 遗留排期）。
 */
async function ensureTaskBoundForTool(rawTaskId: unknown): Promise<ToolTaskGate> {
  const parsed = typeof rawTaskId === 'number' ? rawTaskId : Number(rawTaskId)
  const target = Number.isFinite(parsed) && Math.trunc(parsed) > 0 ? Math.trunc(parsed) : null
  if (target === null || target === taskId.value) {
    const pending = inFlightBinding
    if (pending !== null && pending.taskId === target) {
      const outcome = await pending.promise
      if (!outcome.taskOk) {
        return {
          ok: false,
          message: `任务 #${target} 的绑定没有完成（任务不可加载）：未执行本动作`
            + `。请用 list_tasks 核对任务号后重试，或提示用户在任务中心打开目标任务`
        }
      }
      if (!outcome.filesOk) {
        return { ok: false, message: filesLoadFailedMessage(target) }
      }
    }
    return { ok: true, switched: false }
  }
  let preloaded: Task
  try {
    preloaded = await tasksApi.getTask(target)
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    const current = taskId.value === null ? '未绑定任务' : `绑定任务 #${taskId.value}`
    return {
      ok: false,
      message: `任务 #${target} 无法加载（${reason}）：未切换向导，当前仍${current}。`
        + `请用 list_tasks 核对任务号后重试，或提示用户在任务中心打开目标任务`
    }
  }
  const outcome = await applyTaskBinding(target, preloaded)
  if (!outcome.filesOk) {
    return { ok: false, message: filesLoadFailedMessage(target) }
  }
  return { ok: true, switched: true }
}

/** 文件列表读取失败（"已切换但结论不可用"）的统一文案。 */
function filesLoadFailedMessage(taskId: number): string {
  return `已切换到任务 #${taskId}，但它的导出文件列表读取失败（无法确认文件是否就绪）`
    + '。请稍后重试，或提示用户在任务中心打开该任务确认导出结果'
}

/** 目标任务里没有该配置的导出结果时的可区分文案（带上究竟是哪个任务、现有结果有哪些）。 */
function noResultMessage(defCode: string): string {
  const existing = exportResults.value.map((item) => item.defCode).join('、')
  return `任务 #${taskId.value} 里没有 ${defCode} 的导出结果文件（该任务现有导出结果：${existing || '（无）'}）`
}

/** `downloading` 单值/在途编码的中文口径（'checked' / 'all' / 'ai-batch' 是打包下载的哨兵值，其余是配置编码）。 */
function downloadingLabel(value: string): string {
  if (value === 'checked') {
    return '勾选项打包下载'
  }
  if (value === 'all') {
    return '全部打包下载'
  }
  if (value === AI_BATCH_DOWNLOADING) {
    return 'AI 批量打包下载'
  }
  return `${value} 的下载`
}

/**
 * `download_export_files` 的前置校验（方案 A 的"AI 真正该等的那一段"，**逐码**）：参数 →
 * 任务绑定闸门 → 结果存在性，外加切页（让用户看到下载发生的位置；只读态走零写的 `viewStep`，
 * 非只读态 `goStep` —— 见 §S3e）。
 *
 * 为什么这一段必须 `await` 且必须失败即回灌失败：三者任一不成立都**确定"未触发下载"**，
 * 可以如实回灌 `failed`（卡片显示原因）；而浏览器侧下载链路的结局是"可能已发生但不可知"，
 * 不在这段里等（见执行器与 {@link startDownloadInBackground}）。
 *
 * 无效码**整体失败**（R-156 会审 DS-6）：只要有一个码不在当前可导出清单里就整批不下，
 * 回执点名无效码并给出可用码清单 —— 只下"能下的那几个"会让模型以为整批都已完成，
 * 而用户拿到的文件不全（静默丢弃）。
 *
 * 切页（非只读态 `goStep(2)`）同步失败的面（W-06/W-07）属批次 C，本批不改。
 */
async function preflightDownload(
  args: Record<string, unknown>
): Promise<{ ok: true; defCodes: string[] } | { ok: false; message: string }> {
  const rawCodes = Array.isArray(args.defCodes)
    ? args.defCodes.map((item) => String(item)).filter((item) => item !== '')
    : []
  // 去重：同一编码重复传入（模型抖动）不该退化成"2 个码"的 zip 分支，更不该在 zip 里出现
  // DEMO.xlsx / DEMO(2).xlsx 两份同源文件。
  const defCodes = [...new Set(rawCodes)]
  if (defCodes.length === 0) {
    return { ok: false, message: '缺少 defCodes（本工具必填非空）：请传入至少一个配置定义编码' }
  }
  // issue #2：与 open 执行器同因同修 —— 否则会拿**当前任务**（或当前任务里同名配置）的文件去下载
  const gate = await ensureTaskBoundForTool(args.taskId)
  if (!gate.ok) {
    return { ok: false, message: `${gate.message}。${defCodes.join('、')} 的导出文件未下载` }
  }
  const available = exportResults.value.map((item) => item.defCode)
  if (available.length === 0 && filesLoadError.value) {
    // 清单读取失败 ⇒ "可用码为空"不是事实，禁止据此回"没有导出结果"（假阴性）
    return {
      ok: false,
      message: `任务 #${taskId.value} 的导出文件清单读取失败（${filesLoadError.value}），无法确认 ${defCodes.join('、')} 是否有导出文件。请稍后重试（或让用户点导出向导里的「重试读取」）；本次未触发下载`
    }
  }
  const invalid = defCodes.filter((code) => !available.includes(code))
  if (invalid.length > 0) {
    return {
      ok: false,
      message: `无效码 ${invalid.join('、')}；可用码清单 = ${available.join('、') || '（无）'}。`
        + `请在任务 #${taskId.value} 上完成上述配置的导出后重试，或核对传入的 taskId 与 defCodes；本次未触发下载`
    }
  }
  // 施工卡 §S3e：切页属"让用户看到下载发生的位置"，只读态（任务已结束）必须走零写的 `viewStep`
  if (stepIndex.value !== 2) {
    if (isReadOnly.value) {
      await viewStep(2)
    } else {
      // 批3③a D7：W-06 残余入口收口 —— persist 失败阻断推进时必须如实上报，不得照旧
      // 无条件回 { ok:true }（那会让执行器继续触发下载，而用户根本看不到导出结果页）。
      const advanced = await goStep(2)
      if (!advanced.ok) {
        return { ok: false, message: `已预检通过，但无法切换到导出结果步骤：${advanced.reason}。本次未触发下载` }
      }
    }
  }
  return { ok: true, defCodes }
}

/** 前置校验的结局（L-82）：正常落地 vs 显式上限到点。 */
type GatedPreflight =
  | { kind: 'settled'; result: { ok: true; defCodes: string[] } | { ok: false; message: string } }
  | { kind: 'timeout' }

/**
 * L-82：给 {@link preflightDownload} 加**显式等待上限**。
 *
 * <p>为什么要这一层：前置里的任务绑定 / 结果清单是串行 HTTP，编辑器未预热时可能长时间不落地
 * （真机 VL 域实测 >90s 无结局）。外层统一预算（`FRONTEND_TOOL_TIMEOUT_BUDGET_MS`）虽是最终兜底，
 * 但它按"服务端上限 − 回灌余量"折算（默认 60s），且下载类到点只给"**结局未知**"（副作用可能已发生）；
 * 而前置卡住时其实**一次下载都没发起**，可以、也应该给确定性 `timeout` 结局。
 *
 * <p>上限取 {@link DOWNLOAD_PREFLIGHT_TIMEOUT_MS}（30s）：先于外层默认预算收口，又给"前置通过后
 * 真正发起下载"留足余量。计时器是唯一判定源（不依赖 preflight 是否 settle）。
 */
async function preflightWithDeadline(args: Record<string, unknown>): Promise<GatedPreflight> {
  let timer: ReturnType<typeof setTimeout> | null = null
  const expired = new Promise<GatedPreflight>((resolve) => {
    timer = setTimeout(() => resolve({ kind: 'timeout' }), DOWNLOAD_PREFLIGHT_TIMEOUT_MS)
  })
  try {
    return await Promise.race([
      preflightDownload(args).then((result): GatedPreflight => ({ kind: 'settled', result })),
      expired
    ])
  } finally {
    if (timer !== null) {
      clearTimeout(timer)
    }
  }
}

function registerHandlers(): void {
  // 任务详情就绪探针（只读，返回布尔）：契约层在导航/幂等早退处等它变 true 才放行后续动作。
  // 与 `PAGE_READY_PROBES` 在同一处注册 —— 页面挂载即存在，不会出现"页面就绪但探针缺席"。
  registerPageHandler('export.taskDetailReady', () => taskDetailReady.value)
  // 批 D · D3（问题 6）：AI 触发 start_export 后把作业绑进本页的跟踪面。
  // 与既有 resumeJobs() 同一条恢复链（刷新/回页时也走它）——不重复一套绑定逻辑；
  // taskId 对不上就不动本页（别的任务的作业显示在这里 = 说 A 实际给 B）。
  // 批次 5B：失败分支返回**结构化** `{ ok:false, reason }` —— store 侧据此把"没同步上"
  // 落成可见回执（字符串一律按成功解读，静默失败正是本批要收掉的面）。
  registerPageHandler('export.syncJob', async (payload) => {
    const jobId = Number(payload.jobId)
    const jobTaskId = Number(payload.taskId)
    if (!Number.isInteger(jobId) || jobId <= 0) {
      return { ok: false, reason: '未同步：作业号无效' }
    }
    if (taskId.value === null || taskId.value !== jobTaskId) {
      return { ok: false, reason: `未同步：该作业属于任务 #${jobTaskId}，当前导出向导绑定的是任务 #${taskId.value ?? '（无）'}` }
    }
    await resumeJobs()
    const bound = exportJob.value
    if (bound?.id !== jobId) {
      return { ok: false, reason: `已刷新导出作业列表（未在本任务的作业中找到 #${jobId}）` }
    }
    // 终态作业 resumeJobs 不建 watch（结果已由文件列表铺开）⇒ 这里把结果面刷一次，
    // 否则 AI 触发后页面停在上一次的导出结果上。
    if (bound.status === 'COMPLETED') {
      await refreshResults()
    }
    return `已同步导出作业 #${jobId}（${bound.status}）到导出向导`
  })

  // 补丁②：AI 工作区动作 confirm_step 的页面实现（推进到下一步；与用户点「下一步」同路径）
  registerPageHandler('export.nextStep', async (payload) => {
    // 施工卡 §S3e：只读态拦在 goStep 之前 —— 已结束任务不得借 AI 通道推进步骤（零写）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，不能推进步骤'
    }
    const target = typeof payload.step === 'string' && payload.step !== '' ? payload.step : null
    const index = target ? STEP_KEYS.indexOf(target as (typeof STEP_KEYS)[number]) : stepIndex.value + 1
    if (index < 0 || index >= STEP_KEYS.length) {
      return `步骤 ${payload.step ?? index} 不在导出向导范围内（可选：${STEP_KEYS.join('、')}）`
    }
    // W-06 撒谎点本体收口（批3③a D7）：取 goStep 真实结局再回灌 —— persist 失败阻断推进
    // 时必须如实说"未推进"，否则模型会带着假的成功语义继续往下走。
    const advanced = await goStep(index)
    if (!advanced.ok) {
      // 结构化失败原样上报（A3 解读支）——不得返回字符串：字符串走「兼容⇒ok」会让卡面 tag
      // 显示「已成功」而卡内文写「步骤未推进」，卡面与回灌自相矛盾（复核 I1）。
      return {
        ok: false,
        reason: `步骤未推进：${advanced.reason ?? '保存当前步骤数据失败'}。界面仍停在「${stepLabel(STEP_KEYS[stepIndex.value] ?? '')}」，`
          + '请提示用户核对网络后重试，或手动在界面上操作。'
      }
    }
    if (advanced.syncWarning) {
      return `已推进到导出向导步骤「${stepLabel(STEP_KEYS[index])}」（${advanced.syncWarning}）。`
    }
    return `已推进到导出向导步骤「${stepLabel(STEP_KEYS[index])}」`
  })
  registerPageHandler('export.selectDefs', (payload) => {
    // 施工卡 §S3e（收口修 F4，复核 minor-2）：只读态不改变所选配置项 —— 与导入侧
    // import.selectDefs 守卫对称（本批 10 处守卫的最后一块拼图）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未变更所选配置项'
    }
    const codes = Array.isArray(payload.defCodes) ? payload.defCodes.map((item) => String(item)) : []
    selectedDefs.value = payload.mode === 'ADD' ? [...new Set([...selectedDefs.value, ...codes])] : codes
    for (const code of codes) {
      draftModels[code] = draftModels[code] ?? emptyDraftModel()
    }
    // issue #3：AI 走的是 handler 分支（`runPageHandler` 无 store 副作用），必须显式收敛；
    // 用 'AI' 保住来源（watch 随后比较已相等 ⇒ 短路，不会多记一条"界面"假动作）。
    syncSelectedDefsToWorkspace('AI')
    return `已选择配置项：${selectedDefs.value.join('、') || '（空）'}`
  })

  registerPageHandler('export.setConditions', async (payload) => {
    // 施工卡 §S3e（§2.4 零写清单：setCondition 页面 + AI handler 两处）：只读态拦在落库之前
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未设置查询条件'
    }
    const defCode = String(payload.defCode ?? '')
    if (!defCode) {
      return '缺少 defCode，未设置条件'
    }
    const condition = parseConditionJson(typeof payload.conditions === 'string' ? payload.conditions : JSON.stringify(payload.conditions ?? {}))
    draftModels[defCode] = draftModelFromCondition(condition)
    // 条件被 AI 改过：登记为"已改"，后续 `persistStepData` 必须照此落库（含故意清空为无条件）
    touchedConditions.add(defCode)
    counts[defCode] = null
    const summary = conditionSummary(condition)
    if (taskId.value === null) {
      return `已设置 ${defCode} 的查询条件：${summary}（当前未绑定任务，条件只存在于本页表单，推进步骤时会尝试落库）`
    }
    // F1（DC-14）：与 confirm_step 同口径 —— 设置即落库，不再"只在推进步骤时落库"。
    // 为什么必须落库：后端 conditionJson 是导出作业与 data 列表的**唯一**条件来源；
    // 只写表单模型的话，AI 说"已设置条件"之后若用户没点下一步就刷新（或直接由 AI 触发起作业），
    // 落库的条件仍是旧的 —— 表现为"AI 说设了但导出全量"。
    try {
      await tasksApi.setCondition(taskId.value, defCode, stringifyCondition(condition))
      // 已落库 ⇒ 草案与服务端一致，后续切步不必重复写（失败则保留登记，切步时重试）
      touchedConditions.delete(defCode)
      workspace.setQueryConditions(defCode, condition)
      return `已设置 ${defCode} 的查询条件并落库：${summary}`
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      return `已设置 ${defCode} 的查询条件（${summary}），但落库失败：${reason}。`
        + '请提示用户先在「选择配置」步勾选该配置项，或手动在界面上重设条件。'
    }
  })

  registerPageHandler('export.start', async () => {
    // 施工卡 §S3e（§2.4 零写清单：createJob 在列）：只读态不得借 AI 通道新建导出作业
    if (isReadOnly.value) {
      return { ok: false, reason: '任务已结束，仅供查看，未启动导出作业' }
    }
    const outcome = await startExport()
    if (!outcome.ok) {
      // 条件没落库 / 清单读不到 / 有未保存编辑 ⇒ 未建作业：结构化失败。
      // 各闸门的 `reason` 自带可行动指引（先重试读取 / 先保存或确认丢弃），这里不再追加
      // 通用"重试"话术 —— 那会诱导用户在未处理脏编辑时重复发起。
      return { ok: false, reason: outcome.reason ?? t('views.export.msg.jobStartFailed') }
    }
    if (outcome.detached || outcome.attachError !== undefined) {
      // 作业**已建**：成功形回执 + 明确"请勿重复发起"（不得再追加重试引导，否则会重复导出）
      const where = outcome.taskId === undefined ? '原任务' : `任务 #${outcome.taskId}`
      const why = outcome.detached ? '页面已切换到别的任务，本页未跟踪' : `本页未能跟踪（${outcome.attachError}）`
      return {
        ok: true,
        result: `导出作业 #${outcome.job?.id ?? ''} 已建在${where}（${why}）。作业仍在后端继续，请勿重复发起；结果可在任务中心查看`
      }
    }
    return `导出作业 #${outcome.job?.id ?? ''} 已启动`
  })

  // AI 表格操作专项：把**当前激活结果标签页**的表格适配器反向暴露给 AI 工具。
  // 只作用于当前标签页（设计稿 §7-裁 5）；非激活/未挂载一律给出可行动文案，不代开标签。
  registerPageHandler('sheet.current', async (payload) => {
    const code = String(payload.defCode ?? activeTab.value ?? '')
    if (!code) {
      return { ok: false, reason: '当前没有激活的表格标签页。请先切到导出结果步骤并打开对应配置的标签。' }
    }
    if (code !== activeTab.value) {
      return {
        ok: false,
        reason: `当前激活的表格是 ${activeTab.value || '无'}，不是 ${code}。请先切换到该配置的结果标签页。`
      }
    }
    const grid = gridRefs[code]
    if (!grid || typeof grid.getSheetAdapter !== 'function') {
      return { ok: false, reason: `${code} 的结果表格未挂载（请重开该标签页后重试）。` }
    }
    try {
      // L-115：表格可能仍在载入（首次进入该步 / 换任务重载）—— 必须等**内容**落定再交出适配器，
      // 否则工具读到空表（T05 的「0 行」/ T13 的「0 组」）。`ensureGridLoaded` 会并入同一在途载入，
      // 不会重复下载；`whenLoaded()` 覆盖"已过下载、还在 fromJSON/写行"的那一段。
      const loaded = await ensureGridLoaded(code)
      if (!loaded.ok) {
        return { ok: false, reason: `${code} 的结果表格未就绪：${loaded.error}` }
      }
      await grid.whenLoaded()
      return {
        ok: true,
        defCode: code,
        sheetName: defName(code),
        readOnly: isReadOnly.value,
        version: workspace.dataVersion,
        fields: fieldsOf(code),
        adapter: grid.getSheetAdapter()
      }
    } catch (error) {
      return { ok: false, reason: `${code} 的表格未就绪：${error instanceof Error ? error.message : String(error)}` }
    }
  })

  registerPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR, async (payload) => {
    const defCode = String(payload.defCode ?? '')
    if (!defCode) {
      return '缺少 defCode，无法打开在线编辑器'
    }
    // issue #2：payload.taskId 可能不是当前绑定的任务，先把目标任务绑定好再读 exportResults
    const gate = await ensureTaskBoundForTool(payload.taskId)
    if (!gate.ok) {
      throw new Error(`${gate.message}。${defCode} 的在线编辑器未打开`)
    }
    if (!exportResults.value.some((item) => item.defCode === defCode)) {
      throw new Error(`${noResultMessage(defCode)}。请在任务 #${taskId.value} 上完成 ${defCode} 的导出后重试，或核对传入的 taskId 与 defCode；本次未打开在线编辑器`)
    }
    // 施工卡 §S3e：切页同 `preflightDownload` —— 只读态走零写的 `viewStep`，非只读态取 goStep 结局
    if (stepIndex.value !== 2) {
      if (isReadOnly.value) {
        await viewStep(2)
      } else {
        // 批3③a D7：W-06 残余入口收口 —— persist 失败阻断推进时结构化如实上报（A3 解读支），
        // 不得谎报"已打开在线编辑器"。
        const advanced = await goStep(2)
        if (!advanced.ok) {
          return {
            ok: false,
            reason: `无法推进到导出结果步骤：${advanced.reason}。${defCode} 的在线编辑器未打开。`
          }
        }
      }
    }
    activeTab.value = defCode
    await nextTick()
    // S2-1：回灌前校验真实结局 —— 引擎加载失败时 `ensureGridLoaded` 如实返回失败，
    // 不能再无条件回灌"已打开在线编辑器"（B-07 让 `waitForReady` 必结算后，这条吞错路径
    // 由不可达变为可达）。`loadedGrids` 命中即"此前已加载成功"的等价判据：引擎一旦失败过，
    // 再要成功必须重挂组件，而重挂会重建本页状态、`loadedGrids` 随之清空。
    const loaded = await ensureGridLoaded(defCode)
    if (!loaded.ok) {
      // 批3③a D2 点名：这是**确定性失败**（引擎加载失败，可判定未完成）——结构化 {ok:false}
      // 原样上报（文案逐字保留），不得再经字符串兼容支被包装层折成"已成功"。
      return {
        ok: false,
        reason: `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
          + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
          + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
      }
    }
    return `已打开任务 #${taskId.value} 的 ${defCode} 在线编辑器（导出结果表格）`
  })

  // 前端工具 download_export_files：副作用在浏览器，必须由页面执行器完成（R-156 单工具按数量分流）
  //
  // 即发即回（批 A = W-01 + W-02，方案 A）：AI 侧只等**前置校验**（{@link preflightDownload}：
  // 参数 / 任务绑定 / 结果存在性 / 切页）—— 校验失败 ⇒ 抛错（回灌确定性失败、卡片 failed）；
  // 校验通过 ⇒ **发起下载后立即返回**，不再 `await` 浏览器下载链路。旧写法
  // `await downloadOne(defCode)` 的代价：宿主浏览器拖住链路时回灌迟到 ⇒ 后端按超时收尾并回
  // "本次调用未获得数据"（假失败，而文件其实已落盘，issue#6 实测 120,004ms）。
  //
  // 数量分流（issue #9 的根因：逐个调用单文件工具 = N 次独立浏览器下载，会被浏览器的连续
  // 下载拦截挡掉）：**1 个码**走既有单文件 xlsx 路径（与换名前同一条路径、同文案）；**≥2 个码**
  // 逐码 `buildXlsx` → `zipBlobs` → **单次 zip 下载**（复用用户侧「全部打包 zip」链路，
  // 字节同样取自在线编辑后的表格，未保存的编辑不丢）。
  //
  // 回灌文案口径（只陈述已发生的事 + 显式交代浏览器侧不确定性）—— 分三种场景，措辞不得互相串用：
  // ① 发起成功 ⇒ 「已发起下载…无需重试」；② 在途目标就是本次请求的编码 ⇒ 「本次未重复发起…无需重试」；
  // ③ 在途目标是**别的**文件/打包 ⇒ 本次从未发起，必须如实说「本次未发起 <编码> 的下载」并允许
  // 「可稍后重试本项」—— 否则模型会以为该文件已在下载、不再补发，而用户实际拿不到它（复核 N-1）。
  // 三处共同纪律：**禁止失败暗示**（不得读作「下载成功」或「下载失败」）。
  registerFrontendToolExecutor('download_export_files', async (call) => {
    const gated = await preflightWithDeadline(call.args)
    if (gated.kind === 'timeout') {
      // L-82：前置校验在显式上限内没落地 ⇒ 确定性 timeout 结局（8 态回执 + 三段式人话），
      // 不再把"卡住"留给外层预算（真机实测 >90s 回执悬空）。此时**未发起任何下载**，
      // 文案必须如实这么说 —— 不暗示"已下载"、也不诱导"重复触发"。
      return makeExecution({
        outcome: 'timeout',
        availability: 'degraded',
        code: 'UI_DOWNLOAD_TIMEOUT',
        result: formatUiReceipt({
          tool: '下载导出文件（download_export_files）',
          conclusion: `前置校验超时，本次未完成（等待 ${Math.round(DOWNLOAD_PREFLIGHT_TIMEOUT_MS / 1000)} 秒）`,
          reason: '任务绑定 / 导出结果清单没有在时限内返回（编辑器或后端链路可能仍在加载）',
          next: '提示用户核对任务与导出结果是否就绪后重试；本次未触发下载，不需要去下载目录确认'
        })
      })
    }
    const preflight = gated.result
    if (!preflight.ok) {
      throw new Error(preflight.message)
    }
    const { defCodes } = preflight
    // 内容来源在**发起时**就如实交代（"在线编辑内容 / 标签未载入回落服务端 / 真实 0 行"三态可区分）；
    // 失败只能落在异步侧（toast 点名"表格未载入 + 回落失败"），回执不预设成败。
    const sources = describeDownloadSources(defCodes)
    const busy = downloading.value
    if (defCodes.length === 1) {
      const defCode = defCodes[0] ?? ''
      const requestedInFlight = inFlightDownloads.has(defCode)
      if (requestedInFlight || busy === defCode) {
        return `任务 #${taskId.value} 的 ${defCode} 导出文件：页面上已有一次下载在进行中`
          + `（${downloadingLabel(busy ?? defCode)}），本次未重复发起。`
          + '下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。'
      }
      if (busy !== null) {
        return `任务 #${taskId.value} 的 ${defCode} 导出文件：本次未发起 ${defCode} 的下载`
          + `（页面正有 ${downloadingLabel(busy)}在进行中），可稍后重试本项。`
      }
      startDownloadInBackground(defCode)
      return `已发起下载：任务 #${taskId.value} 的 ${defCode} 导出文件（${sources}）。`
        + '下载由浏览器执行 —— 若弹出保存位置选择框或提示被拦截，请让用户在浏览器侧放行并确认；'
        + '文件落盘以浏览器下载目录为准。本次调用到此结束，无需重试。'
    }
    const codeList = defCodes.join('、')
    // 场景②的判据：**本次请求的每个码都已在途**才算"重复投递"（同一笔打包被投递两次）。
    // 只重叠一部分（或页面上在途的是别的打包）走场景③ —— 否则会把"没在下的那几个"也说成
    // "已在下载"，模型随即不再补发，而用户实际拿不到它们（复核 N-1 的同型失真）。
    const requestedInFlight = defCodes.every((code) => inFlightDownloads.has(code))
    if (requestedInFlight) {
      return `任务 #${taskId.value} 的 ${codeList} 导出文件（共 ${defCodes.length} 个）：页面上已有一次下载在进行中`
        + `（${downloadingLabel(busy ?? defCodes[0] ?? '')}），本次未重复发起。`
        + '下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。'
    }
    if (busy !== null) {
      return `任务 #${taskId.value} 的 ${codeList} 导出文件（共 ${defCodes.length} 个）：本次未发起这笔打包下载`
        + `（页面正有 ${downloadingLabel(busy)}在进行中），可稍后重试本项。`
    }
    startBatchDownloadInBackground(defCodes)
    return `已发起打包下载：任务 #${taskId.value} 的 ${defCodes.length} 个导出文件（${codeList}）`
      + `将合并为单个 zip（内容来源：${sources}）。`
      + '下载由浏览器执行 —— 若弹出保存位置选择框或提示被拦截，请让用户在浏览器侧放行并确认；'
      + '文件落盘以浏览器下载目录为准。本次调用到此结束，无需重试。'
  })
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: STEP_KEYS[0], taskId: taskId.value })
  // visop 批 B：声明 AI 当前页（换页才推进 epoch），再注册两个新区域
  noteAiPage('export')
  registerHandlers()
  await Promise.all([loadDefinitions(), loadTask()])
  await nextTick()
  registerAiExportRegions()
})

/**
 * visop 批 B / O-2：导出向导的两个区域。
 *
 * - `export.selectDefs`（`mutate-local`）：勾选/筛选只改本页与工作区的选中集，落库在推进步骤时；
 * - `export.cond`（`read`）：条件表单是**查询意图的可视面**，选择语义由注册动作 `set_condition`
 *   承担（O-2 原文「原语只补看得见/点得着」）；只有文本取值框在元素级升为 `mutate-local`
 *   （`data-ai-risk` 显式授权）以获得 `fill` 写入口，其余控件动词收紧到 highlight/scroll。
 */
function registerAiExportRegions(): void {
  const selectDefsRoot = aiExportSelectDefsRef.value
  if (selectDefsRoot) {
    registerAiRegion({
      id: 'export.selectDefs',
      root: selectDefsRoot,
      risk: 'mutate-local',
      page: 'export',
      label: '配置项选择（导出向导第 1 步）',
      readOnly: () => isReadOnly.value
    })
  }
  const condRoot = aiCondRef.value
  if (condRoot) {
    registerAiRegion({
      id: 'export.cond',
      root: condRoot,
      risk: 'read',
      page: 'export',
      label: '查询条件（导出向导第 2 步）',
      readOnly: () => isReadOnly.value
    })
  }
}

onBeforeUnmount(() => {
  // B12：离开承载表格的页面 ⇒ 预览计划失效（屏幕色同步清），不回灌（用户没点应用/取消）
  useAiStore().onSheetPageLeft()
  stopWatchJob?.()
  stopWatchJob = null
  HANDLER_NAMES.forEach(unregisterPageHandler)
  unregisterFrontendToolExecutor('download_export_files')
  unregisterAiRegion('export.selectDefs')
  unregisterAiRegion('export.cond')
  if (gridDirty) {
    ElMessage.warning(t('views.export.msg.unsavedEdits'))
  }
})

/**
 * 新建并绑定任务：与路由换任务走**同一条**绑定链路（{@link applyTaskBinding}）。
 *
 * 之前这里自己赋值 `taskId` 再 `router.replace`，路由 watch 因 `id === taskId.value` 直接 return
 * ⇒ 既不复位页面态也不换代次：上一个任务的导出结果/勾选/在途下载集合会残留到新任务上。
 */
async function createAndBindTask(): Promise<void> {
  creatingTask.value = true
  try {
    const created = await taskStore.createTask('EXPORT', t('views.tasks.autoName', {
      type: t('views.tasks.exportType'),
      time: new Date().toLocaleString('zh-CN')
    }))
    if (!created || created.id === undefined) {
      ElMessage.error(taskStore.actionError ?? t('views.export.msg.taskCreateFailed'))
      return
    }
    await applyTaskBinding(created.id)
    ElMessage.success(t('views.export.msg.taskCreated', { id: created.id }))
  } finally {
    creatingTask.value = false
  }
}

/**
 * 契约联动（issue #1）：AI 侧 `navigate_to(page="export", taskId=N)` 经 `restore_task`
 * **只改路由 query**；同一路由记录下本组件不会重建（onMounted 不再执行），于是任务绑定
 * 必须由本页自己跟上 —— 否则 URL 已带 `?taskId=N` 而 `workspace.taskId` 仍是 null，
 * 紧随的 `select_definitions` 会被 needsTask 判成"当前没有进行中的任务"（实测复现）。
 *
 * 绑定走 {@link applyTaskBinding}（与 issue #2 的跨任务执行器同一条链路）：先同步绑 workspace
 * （与 onMounted 同序），再拉详情与导出结果；AI 侧的"绑定完成"判据因此立刻成立，
 * 不必等 `/tasks/{id}` 回来（详情慢或失败时，向导自身的空/错误态照旧展示）。
 * query 变为无 taskId 时不动绑定（导航回同一向导不该把已打开的任务清掉）。
 */
watch(
  () => route.query.taskId,
  async (raw) => {
    const parsed = Number(raw)
    const id = Number.isFinite(parsed) && parsed > 0 ? parsed : null
    if (id === null || id === taskId.value) {
      return
    }
    await applyTaskBinding(id)
  }
)

// 选中集变化：补齐条件模型（避免第 2 步出现未初始化卡片）+ 收敛进工作区（issue #3）
watch(selectedDefs, (codes) => {
  for (const code of codes) {
    draftModels[code] = draftModels[code] ?? emptyDraftModel()
  }
  syncSelectedDefsToWorkspace()
})

/**
 * 契约联动：外部（AI 经 `ui_event.goto_step` → 契约层）改了工作区步骤时，页面跟随。
 *
 * 只读 `workspace.step`，不反向写回（用户切步走 goStep），因此不会形成回环。
 */
watch(
  () => workspace.step,
  (step) => {
    const index = step ? STEP_KEYS.indexOf(step as (typeof STEP_KEYS)[number]) : -1
    if (index >= 0 && index !== stepIndex.value) {
      stepIndex.value = index
      if (index === 2) {
        void refreshResults()
      }
    }
  }
)
</script>
