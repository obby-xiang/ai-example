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

async function loadTask(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  try {
    task.value = await tasksApi.getTask(taskId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '任务加载失败')
    return
  }
  selectedDefs.value = (task.value.items ?? []).map((item) => item.defCode)
  for (const item of task.value.items ?? []) {
    draftModels[item.defCode] = draftModelFromCondition(parseConditionJson(item.conditionJson))
  }
  const restoredStep = STEP_KEYS.indexOf(task.value.currentStep as (typeof STEP_KEYS)[number])
  stepIndex.value = restoredStep >= 0 ? restoredStep : 0
  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: STEP_KEYS[stepIndex.value],
    taskId: taskId.value,
    taskStatus: task.value.status,
    selectedDefs: [...selectedDefs.value]
  })
  await loadExistingResults()
}

/** 恢复：任务里已存在的 EXPORT 文件即导出结果（刷新不丢）。 */
async function loadExistingResults(): Promise<void> {
  if (taskId.value === null) {
    return
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
  } catch {
    // 文件列表失败不影响向导本身（可能只是还没导出过）
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
    workspace.setSelectedDefs([...selectedDefs.value])
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

async function ensureGridLoaded(code: string): Promise<void> {
  if (!code || loadedGrids.has(code) || taskId.value === null) {
    return
  }
  const grid = gridRefs[code]
  if (!grid) {
    return
  }
  loadedGrids.add(code)
  try {
    const blob = await tasksApi.downloadFile(taskId.value, code)
    const { missingHeaders } = await grid.loadFromXlsx(blob, fieldsOf(code))
    if (missingHeaders.length > 0) {
      ElMessage.warning(`${code} 的文件缺少字段列：${missingHeaders.join('、')}`)
    }
  } catch (error) {
    loadedGrids.delete(code)
    ElMessage.error(error instanceof Error ? error.message : `${code} 结果文件加载失败`)
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
    if (!exportResults.value.some((item) => item.defCode === defCode)) {
      return `${defCode} 暂无导出结果文件，请先完成导出`
    }
    if (stepIndex.value !== 2) {
      goStep(2)
    }
    activeTab.value = defCode
    await nextTick()
    await ensureGridLoaded(defCode)
    return `已打开 ${defCode} 的在线编辑器（导出结果表格）`
  })

  // 前端工具 download_export_file：副作用在浏览器，必须由页面执行器完成
  registerFrontendToolExecutor('download_export_file', async (call) => {
    const defCode = String(call.args.defCode ?? '')
    if (!defCode) {
      throw new Error('缺少 defCode')
    }
    if (stepIndex.value !== 2) {
      goStep(2)
    }
    await downloadOne(defCode)
    return `已触发下载 ${defCode} 的导出文件（在线编辑后的内容）`
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
 * 先同步绑定 workspace（与 onMounted 同序），再拉任务详情：AI 侧的"绑定完成"判据因此立刻成立，
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
    taskId.value = id
    workspace.setTaskId(id, 'EXPORT', null)
    await loadTask()
  }
)

// 选中集变化时补齐条件模型（避免第 2 步出现未初始化卡片）
watch(selectedDefs, (codes) => {
  for (const code of codes) {
    draftModels[code] = draftModels[code] ?? emptyDraftModel()
  }
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
