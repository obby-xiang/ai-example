<template>
  <TaskWizard
    title="导出配置向导"
    :subtitle="taskSubtitle"
    :steps="WIZARD_STEPS"
    :active-index="stepIndex"
    :task-status="task?.status ?? null"
    :prev-disabled="stepIndex === 0"
    :show-next="stepIndex < 2"
    :next-loading="persisting"
    @prev="goStep(stepIndex - 1)"
    @next="nextStep"
  >
    <!-- ============ 第 1 步：选择配置 ============ -->
    <div v-show="stepIndex === 0">
      <el-alert
        v-if="!taskId"
        type="warning"
        :closable="false"
        show-icon
        title="尚未绑定任务"
        description="导出向导需要挂在一条导出任务上（作业结果按任务存储）。可直接创建任务进入，或从任务中心点「打开」。"
        class="mb-3"
      >
        <el-button size="small" type="primary" :loading="creatingTask" class="mt-2" @click="createAndBindTask">
          创建导出任务并进入
        </el-button>
      </el-alert>
      <DefSelector v-model="selectedDefs" :defs="defs" :loading="defsLoading" />
      <div class="mt-2.5 text-[13px] text-[#909399]">
        已选 {{ selectedDefs.length }} 个配置项；存在依赖关系的配置项将按依赖顺序导出。
      </div>
    </div>

    <!-- ============ 第 2 步：查询配置 ============ -->
    <div v-show="stepIndex === 1">
      <el-empty v-if="!selectedDefs.length" description="请先在第 1 步选择配置项" />
      <el-card v-for="code in selectedDefs" :key="code" shadow="never" class="mb-3">
        <template #header>
          <div class="flex items-center gap-3">
            <span class="font-semibold mr-auto">{{ defName(code) }}（{{ code }}）</span>
            <el-button size="small" :loading="countLoading[code] === true" @click="previewCount(code)">预览行数</el-button>
            <el-tag v-if="counts[code] !== undefined" size="small" type="success">命中 {{ counts[code] }} 行</el-tag>
          </div>
        </template>
        <ConditionForm
          :fields="fieldsOf(code)"
          :model-value="draftModels[code] ?? emptyDraftModel()"
          @update:model-value="(value) => (draftModels[code] = value)"
        />
      </el-card>
    </div>

    <!-- ============ 第 3 步：导出 ============ -->
    <div v-show="stepIndex === 2">
      <div class="flex items-center mb-3">
        <el-button type="primary" :loading="startingExport" :disabled="jobRunning" @click="startExport">
          {{ exportJob ? '重新导出' : '开始导出' }}
        </el-button>
        <template v-if="exportResults.length">
          <el-divider direction="vertical" />
          <el-button size="small" :loading="downloading === activeTab" @click="downloadOne(activeTab)">
            下载当前配置项 xlsx
          </el-button>
          <el-button size="small" :disabled="!checkedDefs.length" :loading="downloading === 'checked'" @click="downloadChecked">
            打包下载勾选（{{ checkedDefs.length }}）
          </el-button>
          <el-button size="small" :loading="downloading === 'all'" @click="downloadAll">全部打包 zip</el-button>
          <el-button size="small" :loading="savingFiles" @click="saveEdits">保存编辑到服务端</el-button>
        </template>
      </div>

      <JobProgress
        :job="exportJob"
        :issues="currentIssues"
        :channel="channelState"
        :error-message="jobErrorMessage"
        @cancel="cancelJob"
      />

      <template v-if="exportResults.length">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="my-3"
          title="可在线编辑表格内容；编辑只影响下载的文件与「保存编辑到服务端」写入的文件，不会直接改数据库。"
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
            :label="`${defName(item.defCode)}（${item.rowCount} 行）`"
          >
            <SpreadGrid
              :ref="(el) => setGridRef(item.defCode, el)"
              height="440px"
              :def-code="item.defCode"
              :sheet-name="defName(item.defCode)"
              @data-changed="onGridChanged"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </TaskWizard>
</template>

<script setup lang="ts">
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
import {
  buildQueryCondition,
  draftModelFromCondition,
  emptyDraftModel,
  isConditionEmpty,
  conditionSummary,
  parseConditionJson,
  stringifyCondition,
  type ConditionDraftModel
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
import { registerFrontendToolExecutor, unregisterFrontendToolExecutor, PAGE_HANDLER_OPEN_EXPORT_EDITOR } from '@/utils/frontend-tools'
import { stepLabel } from '@/utils/format'
import { watchJob, type JobChannelState } from '@/utils/job-watch'

/** 表格宿主的最小接口（defineExpose 出来的方法）。 */
interface GridApi {
  loadFromXlsx(blob: Blob, fields: readonly ConfigField[]): Promise<{ missingHeaders: string[] }>
  loadData(fields: readonly ConfigField[], rows?: readonly Record<string, unknown>[]): void
  collectRows(): Array<Record<string, string>>
  toXlsxBlob(): Promise<Blob>
  validateLocal(): ExcelRowIssue[]
}

interface ExportResultItem {
  defCode: string
  fileName: string
  rowCount: number
}

/** 步骤 key（与后端 @ToolScope 标签一致） */
const STEP_KEYS = TaskSteps.EXPORT
/** 步骤文案（与蓝本同名页一致） */
const WIZARD_STEPS = [
  { key: STEP_KEYS[0], label: '选择配置' },
  { key: STEP_KEYS[1], label: '查询配置' },
  { key: STEP_KEYS[2], label: '导出配置' }
] as const

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
const draftModels = reactive<Record<string, ConditionDraftModel>>({})
const counts = reactive<Record<string, number | null>>({})
const countLoading = reactive<Record<string, boolean>>({})

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

const gridRefs: Record<string, GridApi> = {}
const loadedGrids = new Set<string>()
let stopWatchJob: (() => void) | null = null
let gridDirty = false

const jobRunning = computed(() => exportJob.value !== null && ['PENDING', 'RUNNING'].includes(exportJob.value.status))

const taskSubtitle = computed(() => {
  if (!task.value) {
    return taskId.value ? `任务 #${taskId.value}` : ''
  }
  return `#${task.value.id} · ${task.value.title}`
})

const jobErrorMessage = computed(() => {
  if (!exportJob.value || exportJob.value.status !== 'FAILED') {
    return null
  }
  const first = currentIssues.value.find((issue) => issue.severity === 'ERROR')
  return first ? first.message : `导出作业失败（错误 ${exportJob.value.errorCount} 条），请查看下方明细`
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
    ElMessage.error(error instanceof Error ? error.message : '配置定义加载失败')
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
    return { taskOk: false, filesOk: false }
  }
  let loaded = preloaded ?? null
  if (loaded === null) {
    try {
      loaded = await tasksApi.getTask(taskId.value)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '任务加载失败')
      return { taskOk: false, filesOk: false }
    }
  }
  task.value = loaded
  selectedDefs.value = (loaded.items ?? []).map((item) => item.defCode)
  for (const item of loaded.items ?? []) {
    draftModels[item.defCode] = draftModelFromCondition(parseConditionJson(item.conditionJson))
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
    selectedDefs: [...selectedDefs.value]
  })
  return { taskOk: true, filesOk: await loadExistingResults() }
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
  try {
    const files: TaskFile[] = await tasksApi.getFiles(taskId.value)
    exportResults.value = files
      .filter((file) => file.fileType === 'EXPORT')
      .map((file) => ({
        defCode: file.defCode,
        fileName: file.fileName ?? xlsxFileName(file.defCode, defName(file.defCode)),
        rowCount: file.rowCount ?? 0
      }))
    if (exportResults.value.length > 0 && !activeTab.value) {
      activeTab.value = exportResults.value[0]?.defCode ?? ''
      await nextTick()
      await ensureGridLoaded(activeTab.value)
    }
    return true
  } catch {
    // 文件列表失败不影响向导本身（可能只是还没导出过）
    return false
  }
}

/**
 * 换任务时的页面态清理（issue #2）：上一个任务的导出结果/勾选/激活标签/表格缓存一律不带到目标任务上。
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
  if (gridDirty) {
    ElMessage.warning('表格有未保存的在线编辑内容，本次切换任务已丢弃这些改动（可先用「保存编辑到服务端」写回再切换）')
    gridDirty = false
  }
  exportResults.value = []
  checkedDefs.value = []
  activeTab.value = ''
  loadedGrids.clear()
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

function goStep(next: number): void {
  if (next < 0 || next >= STEP_KEYS.length) {
    return
  }
  stepIndex.value = next
  workspace.setStep(STEP_KEYS[next] ?? null)
  void persistStepData()
  void syncStepToBackend()
  if (next === 2) {
    void refreshResults()
  }
}

/**
 * 把当前步骤写回任务（`PUT /tasks/{id}/step`）。
 *
 * 为什么必须有这一步：后端 `currentStep` 是刷新恢复与 AI 上下文（`task:EXPORT/<step>` 的
 * 渐进披露子集）的唯一权威；只在前端切步不进后端，刷新会退回首步、且模型拿到的是旧步骤。
 */
async function syncStepToBackend(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const step = STEP_KEYS[stepIndex.value]
  if (!step) {
    return
  }
  try {
    task.value = await tasksApi.goToStep(taskId.value, step)
  } catch {
    // 步骤同步失败不阻塞界面操作（下次切步会重试）
  }
}

function nextStep(): void {
  if (stepIndex.value === 0 && selectedDefs.value.length === 0) {
    ElMessage.warning('请先选择至少一个配置项')
    return
  }
  goStep(stepIndex.value + 1)
}

/** 选中集与查询条件落库（后端是这两者的权威存储）。 */
async function persistStepData(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  persisting.value = true
  try {
    await tasksApi.selectDefs(taskId.value, [...selectedDefs.value])
    for (const code of selectedDefs.value) {
      const model = draftModels[code]
      if (!model) {
        continue
      }
      const condition = buildQueryCondition(model)
      await tasksApi.setCondition(taskId.value, code, stringifyCondition(condition))
    }
    task.value = await tasksApi.getTask(taskId.value)
    // issue #3：与 watch / AI handler 共用同一收敛点（此处落库后工作区通常已相等 ⇒ 短路，不重复记动作）
    syncSelectedDefsToWorkspace()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存选择与条件失败')
  } finally {
    persisting.value = false
  }
}

// ── 第 2 步：行数预览 ──────────────────────────────────────────────────────────

async function previewCount(code: string): Promise<void> {
  countLoading[code] = true
  try {
    const model = draftModels[code] ?? emptyDraftModel()
    const condition = buildQueryCondition(model)
    const result = await dataApiCount(code, condition)
    counts[code] = result
    ElMessage.success(
      isConditionEmpty(condition)
        ? `${code}：全部数据 ${result} 行`
        : `${code}：命中 ${result} 行（${conditionSummary(condition)}）`
    )
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '行数预览失败')
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

async function startExport(): Promise<void> {
  if (taskId.value === null) {
    ElMessage.warning('尚未绑定任务，请先创建或从任务中心打开一条导出任务')
    return
  }
  if (selectedDefs.value.length === 0) {
    ElMessage.warning('请先选择配置项')
    return
  }
  await persistStepData()
  startingExport.value = true
  try {
    const job = await jobsApi.createJob(taskId.value, 'EXPORT')
    exportJob.value = job
    exportResults.value = []
    loadedGrids.clear()
    ElMessage.success(`导出作业已启动（作业 #${job.id}）`)
    beginWatchJob(job.id ?? 0)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出作业启动失败')
  } finally {
    startingExport.value = false
  }
}

function beginWatchJob(jobId: number): void {
  stopWatchJob?.()
  currentIssues.value = []
  if (taskId.value === null || jobId <= 0) {
    return
  }
  stopWatchJob = watchJob({
    taskId: taskId.value,
    jobId,
    onProgress: (payload) => {
      if (exportJob.value) {
        exportJob.value = { ...exportJob.value, status: 'RUNNING', progress: payload.processed, total: payload.total }
      }
    },
    onSnapshot: (job) => {
      exportJob.value = job
    },
    onChannel: (state) => {
      channelState.value = state
    },
    onFinal: async (job) => {
      exportJob.value = job
      currentIssues.value = (await jobsApi.getIssues(jobId)).content ?? []
      if (job.status === 'COMPLETED') {
        ElMessage.success('导出完成')
        await refreshResults()
      } else if (job.status === 'FAILED') {
        ElMessage.error('导出失败，请查看作业明细')
      } else {
        ElMessage.info('导出作业已取消')
      }
    }
  })
}

async function refreshResults(): Promise<void> {
  loadedGrids.clear()
  await loadExistingResults()
  const first = exportResults.value[0]?.defCode ?? ''
  if (first) {
    activeTab.value = first
    await nextTick()
    await ensureGridLoaded(first)
  }
}

async function cancelJob(): Promise<void> {
  const jobId = exportJob.value?.id
  if (jobId === undefined) {
    return
  }
  try {
    await jobsApi.cancelJob(jobId)
    ElMessage.info('已请求取消作业')
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '取消作业失败')
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
 */
async function ensureGridLoaded(code: string): Promise<{ ok: boolean; error: string }> {
  if (!code) {
    return { ok: false, error: '缺少配置编码' }
  }
  if (loadedGrids.has(code)) {
    return { ok: true, error: '' }
  }
  if (taskId.value === null) {
    return { ok: false, error: '当前未绑定任务，无法读取结果文件' }
  }
  const grid = gridRefs[code]
  if (!grid) {
    return { ok: false, error: `${code} 的结果表格组件未挂载（请重开该标签页重试）` }
  }
  loadedGrids.add(code)
  try {
    const blob = await tasksApi.downloadFile(taskId.value, code)
    const { missingHeaders } = await grid.loadFromXlsx(blob, fieldsOf(code))
    if (missingHeaders.length > 0) {
      ElMessage.warning(`${code} 的文件缺少字段列：${missingHeaders.join('、')}`)
    }
    return { ok: true, error: '' }
  } catch (error) {
    loadedGrids.delete(code)
    const reason = error instanceof Error ? error.message : `${code} 结果文件加载失败`
    ElMessage.error(reason)
    return { ok: false, error: reason }
  }
}

function onTabChange(name: string | number): void {
  void ensureGridLoaded(String(name))
}

function onGridChanged(): void {
  const firstChange = !gridDirty
  gridDirty = true
  workspace.bumpVersion()
  if (firstChange) {
    // 契约联动：把"用户改了导出结果表格"同步给 AI 上下文（同一批编辑只记一次）
    workspace.touch('导出结果表格内容已修改（尚未保存到服务端文件）')
  }
}

/** 取某配置项的下载用 xlsx（优先用表格里的**当前内容**，反映在线编辑）。 */
async function buildXlsx(code: string): Promise<{ blob: Blob; fileName: string }> {
  const grid = gridRefs[code]
  const fileName = exportResults.value.find((item) => item.defCode === code)?.fileName ?? xlsxFileName(code, defName(code))
  if (grid) {
    return { blob: await grid.toXlsxBlob(), fileName }
  }
  if (taskId.value === null) {
    throw new Error('尚未绑定任务')
  }
  return { blob: await tasksApi.downloadFile(taskId.value, code), fileName }
}

async function downloadOne(code: string): Promise<void> {
  if (!code) {
    ElMessage.warning('请选择要下载的配置项')
    return
  }
  downloading.value = code
  try {
    const { blob, fileName } = await buildXlsx(code)
    downloadBlob(blob, fileName)
    ElMessage.success(`已下载 ${fileName}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下载失败')
  } finally {
    downloading.value = null
  }
}

async function downloadChecked(): Promise<void> {
  if (checkedDefs.value.length === 0) {
    ElMessage.warning('请先勾选要打包的配置项')
    return
  }
  downloading.value = 'checked'
  try {
    if (checkedDefs.value.length === 1) {
      await downloadOne(checkedDefs.value[0] ?? '')
      return
    }
    const entries = await Promise.all(checkedDefs.value.map(async (code) => {
      const { blob, fileName } = await buildXlsx(code)
      return { name: fileName, blob }
    }))
    downloadBlob(await zipBlobs(entries), `export-selected-${taskId.value ?? 0}.zip`)
    ElMessage.success(`已打包下载 ${entries.length} 个文件`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打包下载失败')
  } finally {
    downloading.value = null
  }
}

async function downloadAll(): Promise<void> {
  if (exportResults.value.length === 0) {
    ElMessage.warning('尚无导出结果')
    return
  }
  downloading.value = 'all'
  try {
    if (exportResults.value.length === 1) {
      await downloadOne(exportResults.value[0]?.defCode ?? '')
      return
    }
    const entries = await Promise.all(exportResults.value.map(async (item) => {
      const { blob, fileName } = await buildXlsx(item.defCode)
      return { name: fileName, blob }
    }))
    downloadBlob(await zipBlobs(entries), `export-${taskId.value ?? 0}.zip`)
    ElMessage.success(`已打包下载 ${entries.length} 个文件`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打包下载失败')
  } finally {
    downloading.value = null
  }
}

/**
 * 把在线编辑结果写回服务端文件（`PUT /tasks/{id}/files/{defCode}`）。
 *
 * 为什么要这一步：后端后续步骤（导入/发布/AI 工具）读的是**服务端文件**，
 * 只在浏览器里改 excel 不回写，别的入口看到的还是旧内容。
 */
async function saveEdits(): Promise<void> {
  if (taskId.value === null) {
    ElMessage.warning('尚未绑定任务')
    return
  }
  savingFiles.value = true
  try {
    for (const item of exportResults.value) {
      const grid = gridRefs[item.defCode]
      if (!grid) {
        continue
      }
      const issues = grid.validateLocal()
      if (issues.length > 0) {
        const first = issues[0]
        ElMessage.warning(`${item.defCode} 存在 ${issues.length} 处本地校验问题（如第 ${first?.rowIndex} 行：${first?.reason}），已跳过保存`)
        continue
      }
      const blob = await grid.toXlsxBlob()
      await tasksApi.saveFile(taskId.value, item.defCode, await blob.arrayBuffer())
    }
    gridDirty = false
    ElMessage.success('编辑结果已写回服务端文件')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存到服务端失败')
  } finally {
    savingFiles.value = false
  }
}

// ── AI 页面能力（裁剪性：这些登记只影响 AI，手工操作不依赖它） ────────────────

const HANDLER_NAMES = [
  'export.selectDefs',
  'export.setConditions',
  'export.start',
  'export.nextStep',
  PAGE_HANDLER_OPEN_EXPORT_EDITOR
]

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

function registerHandlers(): void {
  // 补丁②：AI 工作区动作 confirm_step 的页面实现（推进到下一步；与用户点「下一步」同路径）
  registerPageHandler('export.nextStep', (payload) => {
    const target = typeof payload.step === 'string' && payload.step !== '' ? payload.step : null
    const index = target ? STEP_KEYS.indexOf(target as (typeof STEP_KEYS)[number]) : stepIndex.value + 1
    if (index < 0 || index >= STEP_KEYS.length) {
      return `步骤 ${payload.step ?? index} 不在导出向导范围内（可选：${STEP_KEYS.join('、')}）`
    }
    goStep(index)
    return `已推进到导出向导步骤「${stepLabel(STEP_KEYS[index])}」`
  })

  registerPageHandler('export.selectDefs', (payload) => {
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
    const defCode = String(payload.defCode ?? '')
    if (!defCode) {
      return '缺少 defCode，未设置条件'
    }
    const condition = parseConditionJson(typeof payload.conditions === 'string' ? payload.conditions : JSON.stringify(payload.conditions ?? {}))
    draftModels[defCode] = draftModelFromCondition(condition)
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
      workspace.setQueryConditions(defCode, condition)
      return `已设置 ${defCode} 的查询条件并落库：${summary}`
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      return `已设置 ${defCode} 的查询条件（${summary}），但落库失败：${reason}。`
        + '请提示用户先在「选择配置」步勾选该配置项，或手动在界面上重设条件。'
    }
  })

  registerPageHandler('export.start', async () => {
    await startExport()
    return exportJob.value ? `导出作业 #${exportJob.value.id} 已启动` : '导出未启动（请检查配置项选择）'
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
    if (stepIndex.value !== 2) {
      goStep(2)
    }
    activeTab.value = defCode
    await nextTick()
    // S2-1：回灌前校验真实结局 —— 引擎加载失败时 `ensureGridLoaded` 如实返回失败，
    // 不能再无条件回灌"已打开在线编辑器"（B-07 让 `waitForReady` 必结算后，这条吞错路径
    // 由不可达变为可达）。`loadedGrids` 命中即"此前已加载成功"的等价判据：引擎一旦失败过，
    // 再要成功必须重挂组件，而重挂会重建本页状态、`loadedGrids` 随之清空。
    const loaded = await ensureGridLoaded(defCode)
    if (!loaded.ok) {
      return `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
        + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
        + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
    }
    return `已打开任务 #${taskId.value} 的 ${defCode} 在线编辑器（导出结果表格）`
  })

  // 前端工具 download_export_file：副作用在浏览器，必须由页面执行器完成
  registerFrontendToolExecutor('download_export_file', async (call) => {
    const defCode = String(call.args.defCode ?? '')
    if (!defCode) {
      throw new Error('缺少 defCode')
    }
    // issue #2：与 open 执行器同因同修 —— 否则会拿**当前任务**（或当前任务里同名配置）的文件去下载
    const gate = await ensureTaskBoundForTool(call.args.taskId)
    if (!gate.ok) {
      throw new Error(`${gate.message}。${defCode} 的导出文件未下载`)
    }
    if (!exportResults.value.some((item) => item.defCode === defCode)) {
      throw new Error(`${noResultMessage(defCode)}。请在任务 #${taskId.value} 上完成 ${defCode} 的导出后重试，或核对传入的 taskId 与 defCode；本次未触发下载`)
    }
    if (stepIndex.value !== 2) {
      goStep(2)
    }
    await downloadOne(defCode)
    return `已触发下载任务 #${taskId.value} 的 ${defCode} 导出文件（在线编辑后的内容）`
  })
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'export', page: 'export', taskType: 'EXPORT', step: STEP_KEYS[0], taskId: taskId.value })
  registerHandlers()
  await Promise.all([loadDefinitions(), loadTask()])
})

onBeforeUnmount(() => {
  stopWatchJob?.()
  stopWatchJob = null
  HANDLER_NAMES.forEach(unregisterPageHandler)
  unregisterFrontendToolExecutor('download_export_file')
  if (gridDirty) {
    ElMessage.warning('表格有未保存的在线编辑内容（可用「保存编辑到服务端」写回）')
  }
})

async function createAndBindTask(): Promise<void> {
  creatingTask.value = true
  try {
    const created = await taskStore.createTask('EXPORT', `导出任务 ${new Date().toLocaleString('zh-CN')}`)
    if (!created || created.id === undefined) {
      ElMessage.error(taskStore.error ?? '创建任务失败')
      return
    }
    taskId.value = created.id
    await router.replace({ name: 'export-wizard', query: { taskId: String(created.id) } })
    await loadTask()
    ElMessage.success(`已创建导出任务 #${created.id}`)
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
