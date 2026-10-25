<template>
  <TaskWizard
    :title="t('views.import.title')"
    :subtitle="taskSubtitle"
    :steps="WIZARD_STEPS"
    :active-index="stepIndex"
    :step-statuses="stepStatuses"
    :read-only="isReadOnly"
    :task-status="task?.status ?? null"
    :prev-disabled="stepIndex === 0"
    :show-prev="!isReadOnly"
    :show-next="!isReadOnly && stepIndex < 3"
    :next-loading="busy === 'persist'"
    @prev="goPrev"
    @next="nextStep"
    @step-click="viewStep"
  >
    <!-- ============ 第 1 步：上传配置 ============ -->
    <div v-show="stepIndex === 0">
      <el-alert
        v-if="!taskId"
        type="warning"
        :closable="false"
        show-icon
        :title="t('views.import.noTask')"
        :description="t('views.import.noTaskDesc')"
        class="mb-3"
      >
        <el-button size="small" type="primary" :loading="creatingTask" class="mt-2" @click="createAndBindTask">
          {{ t('views.import.createAndEnter') }}
        </el-button>
      </el-alert>

      <div ref="aiSelectDefsRef">
        <DefSelector v-model="selectedDefs" :defs="defs" :loading="defsLoading" :disabled="isReadOnly" ai-ref-prefix="import.selectDefs" />
      </div>

      <div
        ref="aiUploadRowRef"
        class="flex items-center gap-3 my-3"
        @dragover="onAiDragOver"
        @drop="onAiDrop"
      >
        <el-button v-if="!isReadOnly" size="small" :disabled="!selectedDefs.length" :loading="busy === 'templates'" data-ai-ref="import.upload.templates" data-ai-risk="consequential" @click="downloadTemplates">
          {{ t('views.import.downloadTemplate', { format: selectedDefs.length > 1 ? 'zip' : 'xlsx' }) }}
        </el-button>
        <el-upload
          v-if="!isReadOnly"
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx,.zip"
          :disabled="!selectedDefs.length || busy === 'upload'"
          :on-change="handleUpload"
        >
          <el-button size="small" type="primary" :loading="busy === 'upload'" :disabled="!selectedDefs.length" data-ai-ref="import.upload.choose" data-ai-risk="consequential">
            {{ t('views.import.upload') }}
          </el-button>
        </el-upload>
        <el-button v-if="!isReadOnly" size="small" :disabled="!selectedDefs.length" :loading="busy === 'flush'" data-ai-ref="import.upload.flush" data-ai-risk="consequential" @click="flushEditors">
          {{ t('views.import.saveDraft') }}
        </el-button>
        <span class="text-xs text-[#909399]">
          {{ t('views.import.matchHint') }}
        </span>
      </div>

      <template v-if="editorDefs.length">
        <div ref="aiGridRef">
          <el-tabs v-model="activeEditorTab" type="border-card" @tab-change="onEditorTabChange">
            <el-tab-pane v-for="def in editorDefs" :key="def.code" :name="def.code">
              <template #label>
                <span
                  :data-ai-ref="`import.grid.tab.${def.code}`"
                  role="tab"
                  :aria-selected="activeEditorTab === def.code ? 'true' : 'false'"
                  data-ai-risk="read"
                  data-ai-verbs="click,highlight"
                  :data-ai-label="`${def.name}（${def.code}）`"
                >{{ def.name }}（{{ def.code }}）</span>
                <el-tag v-if="uploadedFiles[def.code]" size="small" type="success" class="ml-1">{{ t('views.import.uploaded') }}</el-tag>
                <el-tag v-else-if="savedDefs.has(def.code)" size="small" type="info" class="ml-1">{{ t('views.import.draftSaved') }}</el-tag>
              </template>
              <SpreadGrid
                :ref="(el) => setGridRef(def.code, el)"
                height="380px"
                :read-only="isReadOnly"
                :def-code="def.code"
                :sheet-name="def.name"
                @data-changed="onGridChanged"
                @selection-changed="onGridSelectionChanged(def.code)"
              />
            </el-tab-pane>
          </el-tabs>
        </div>
      </template>
      <el-empty v-else :description="t('views.import.emptyPick')" :image-size="60" />
    </div>

    <!-- ============ 第 2 步：检查配置 ============ -->
    <div v-show="stepIndex === 1">
      <div class="flex items-center gap-3 mb-3">
        <el-button v-if="!isReadOnly" type="primary" :loading="busy === 'check'" :disabled="jobRunning(checkJob)" @click="startCheck">
          {{ t('views.import.startPrecheck') }}
        </el-button>
        <span class="text-xs text-[#909399]">{{ t('views.import.precheckHint') }}</span>
      </div>
      <JobProgress
        :job="checkJob"
        :issues="checkIssues"
        :channel="checkChannel"
        :error-message="errorMessageOf(checkJob, checkIssues)"
        :read-only="isReadOnly"
        @cancel="cancelJob(checkJob)"
      />
      <el-alert
        v-if="checkJob && checkJob.status === 'COMPLETED'"
        type="success"
        :closable="false"
        show-icon
        class="mt-3"
        :title="t('views.import.precheckPassed')"
      />
      <el-alert
        v-else-if="checkJob && checkJob.status === 'FAILED'"
        type="warning"
        :closable="false"
        show-icon
        class="mt-3"
        :title="t('views.import.precheckFailed')"
      />
    </div>

    <!-- ============ 第 3 步：导入配置 ============ -->
    <div v-show="stepIndex === 2">
      <div class="flex items-center gap-3 mb-3">
        <el-button v-if="!isReadOnly" type="primary" :loading="busy === 'import'" :disabled="jobRunning(importJob)" @click="startImport">
          {{ t('views.import.startImport') }}
        </el-button>
        <span class="text-xs text-[#909399]">{{ t('views.import.importHint') }}</span>
      </div>
      <JobProgress
        :job="importJob"
        :issues="importIssues"
        :channel="importChannel"
        :error-message="errorMessageOf(importJob, importIssues)"
        :read-only="isReadOnly"
        @cancel="cancelJob(importJob)"
      />
    </div>

    <!-- ============ 第 4 步：发布配置 ============ -->
    <div v-show="stepIndex === 3">
      <el-card shadow="never" class="mb-3.5">
        <template #header>
          <div class="flex justify-between items-center">
            <span>{{ t('views.import.stagingTitle') }}</span>
            <div ref="aiModeRowRef" class="flex items-center gap-2">
              <el-radio-group v-model="importMode" size="small" :disabled="isReadOnly" @change="onImportModeChange">
                <el-radio-button
                  value="MERGE"
                  data-ai-ref="import.mode.merge"
                  role="radio"
                  :aria-checked="importMode === 'MERGE' ? 'true' : 'false'"
                  data-ai-option="MERGE"
                  data-ai-risk="consequential"
                  data-ai-label="增量合并"
                >{{ t('views.import.mergeMode') }}</el-radio-button>
                <el-radio-button
                  value="REPLACE"
                  data-ai-ref="import.mode.replace"
                  role="radio"
                  :aria-checked="importMode === 'REPLACE' ? 'true' : 'false'"
                  data-ai-option="REPLACE"
                  data-ai-risk="consequential"
                  data-ai-label="范围内替换"
                >{{ t('views.import.replaceMode') }}</el-radio-button>
              </el-radio-group>
              <el-button size="small" :loading="stagingLoading" @click="loadStaging">{{ t('views.import.refresh') }}</el-button>
            </div>
          </div>
        </template>
        <el-empty v-if="!staging.length" :description="t('views.import.emptyStaging')" :image-size="60" />
        <el-collapse v-else v-model="stagingOpen">
          <el-collapse-item v-for="group in staging" :key="group.defCode" :name="group.defCode">
            <template #title>
              <!-- 行高必须落在这个块级元素上：inline 子元素的小 line-height 会被 el-collapse-item__title 的 48px 行高「撑」回去 -->
              <span class="block whitespace-normal leading-snug">{{ t('views.import.groupTitle', { name: defName(group.defCode), code: group.defCode, rows: group.rowCount }) }}</span>
            </template>
            <el-table :data="group.rows.slice(0, 100)" size="small" border max-height="300">
              <el-table-column prop="rowKey" :label="t('views.import.rowKey')" width="150" />
              <el-table-column prop="opType" :label="t('views.import.actions')" width="90">
                <template #default="{ row }: { row: ConfigStagingRow }">
                  <el-tag size="small" :type="row.opType === 'DELETE' ? 'danger' : 'success'">
                    {{ row.opType === 'DELETE' ? t('views.import.opDelete') : t('views.import.opWrite') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-for="field in fieldsOf(group.defCode)"
                :key="field.code"
                :label="field.label"
                min-width="120"
              >
                <template #default="{ row }: { row: ConfigStagingRow }">{{ cellValue(row, field.code) }}</template>
              </el-table-column>
            </el-table>
            <div v-if="group.rowCount > 100" class="py-1.5 text-xs text-[#909399]">{{ t('views.import.first100') }}</div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <div class="flex items-center gap-3 mb-3">
        <el-button v-if="!isReadOnly" type="primary" :loading="busy === 'publish'" :disabled="jobRunning(publishJob)" @click="startPublish">
          {{ t('views.import.publish') }}
        </el-button>
        <span class="text-xs text-[#909399]">
          {{ importMode === 'REPLACE' ? t('views.import.publishReplace') : t('views.import.publishMerge') }}
        </span>
      </div>

      <!-- Q4 异步形态：发布请求恒 201，冲突只从作业终态 + issues 读出 -->
      <el-alert
        v-if="publishConflict"
        type="error"
        :closable="false"
        show-icon
        class="mb-3"
        :title="t('views.import.conflictTitle', { message: publishConflict.message })"
        :description="t('views.import.conflictDesc', { jobId: publishConflict.jobId, status: publishConflict.jobStatus, keys: publishConflict.issueKeys.slice(0, 8).join('、'), more: publishConflict.issueKeys.length > 8 ? t('views.import.conflictMore') : '' })"
      />

      <JobProgress
        :job="publishJob"
        :issues="publishIssues"
        :channel="publishChannel"
        :error-message="errorMessageOf(publishJob, publishIssues)"
        :read-only="isReadOnly"
        @cancel="cancelJob(publishJob)"
      />
    </div>
  </TaskWizard>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 导入配置向导（四步：上传配置 → 检查配置 → 导入配置 → 发布配置）。
 *
 * 与蓝本的关键差别（功能差异，理由见证据文档 V6）：
 * 1. **文件即接口**：后端预检查/导入/发布读取的是任务下已落盘的 UPLOAD 文件
 *    （ImportJobRunner/PrecheckJobRunner 经 ExcelReader 读盘），不是请求体里的行数组，
 *    因此在线编辑的结果必须先 `PUT /tasks/{id}/files/{defCode}` 写回（本页在离开第 1 步前自动 flush）；
 * 2. 发布冲突按 **Q4 异步形态**处理：`POST .../jobs` 恒 201，冲突表现为作业 FAILED +
 *    `GET /jobs/{id}/issues` 文案 —— 前端只盯作业终态，绝不按同步错误判错；
 * 3. 进度通道 SSE（JOB_PROGRESS/JOB_DONE）+ 轮询兜底；蓝本是纯轮询。
 */
import { computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import DefSelector from '@/components/wizard/DefSelector.vue'
import JobProgress from '@/components/JobProgress.vue'
import SpreadGrid from '@/components/SpreadGrid.vue'
import TaskWizard from '@/components/TaskWizard.vue'
import { definitionsApi } from '@/api/definitions'
import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { useTaskStore } from '@/stores/task'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { useAiStore } from '@/stores/ai'
import { noteAiPage, registerAiRegion, unregisterAiRegion } from '@/utils/ai-regions'
import { buildTemplateWorkbookJson, downloadBlob, xlsxFileName, type ExcelRowIssue } from '@/utils/excel'
import type { SheetAdapter } from '@/utils/sheet-tools'
import { watchJob, type JobChannelState } from '@/utils/job-watch'
import { confirmIrreversible, confirmPublishByMode } from '@/utils/confirm-irreversible'
import { resolvePublishMode } from '@/utils/publish-mode'
import { stepLabel } from '@/utils/format'
import type { PublishConflictAsync } from '@/types/api'
import { parseRowData, type ConfigStagingRow } from '@/types/data'
import type { ConfigDefinition, ConfigField } from '@/types/definition'
import type { Job, ValidationIssue } from '@/types/job'
import { readImportMode, TaskSteps, type ImportMode, type Task } from '@/types/task'
import type { WorkspaceActionSource } from '@/types/tools'

const { t } = useI18n()

interface GridApi {
  loadFromXlsx(blob: Blob, fields: readonly ConfigField[]): Promise<{ missingHeaders: string[] }>
  loadFromJson(json: string, fields: readonly ConfigField[]): Promise<void>
  loadData(fields: readonly ConfigField[], rows?: readonly Record<string, unknown>[]): void
  collectRows(): Array<Record<string, string>>
  toXlsxBlob(): Promise<Blob>
  validateLocal(): ExcelRowIssue[]
  getSheetAdapter(): SheetAdapter
  /** L-115：等表格内容落定（载入在途时挂起，无在途载入立即结算） */
  whenLoaded(): Promise<void>
}

interface StagingGroup {
  defCode: string
  rowCount: number
  rows: ConfigStagingRow[]
}

type BusyKind = 'persist' | 'templates' | 'upload' | 'flush' | 'check' | 'import' | 'publish' | null

const STEP_KEYS = TaskSteps.IMPORT
/** 步骤文案（i18n L-69：label 由 locale 派生，渲染期读 ⇒ 切语言即重算） */
const WIZARD_STEPS = computed(() => [
  { key: STEP_KEYS[0], label: t('views.import.steps.upload') },
  { key: STEP_KEYS[1], label: t('views.import.steps.check') },
  { key: STEP_KEYS[2], label: t('views.import.steps.import') },
  { key: STEP_KEYS[3], label: t('views.import.steps.publish') }
])

const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const taskStore = useTaskStore()
/** AI 可视可操作专项：上传交接的成功联动（用户自己点了上传也要收口 AI 的等待）。 */
const aiStore = useAiStore()

const taskId = ref<number | null>(readTaskId())
const task = ref<Task | null>(null)
const defs = ref<ConfigDefinition[]>([])
const defsLoading = ref(false)
const creatingTask = ref(false)
const busy = ref<BusyKind>(null)

const stepIndex = ref(0)
const selectedDefs = ref<string[]>([])
const activeEditorTab = ref('')
const uploadedFiles = reactive<Record<string, string>>({})
const savedDefs = reactive(new Set<string>())

const checkJob = ref<Job | null>(null)
const importJob = ref<Job | null>(null)
const publishJob = ref<Job | null>(null)
const checkIssues = ref<ValidationIssue[]>([])
const importIssues = ref<ValidationIssue[]>([])
const publishIssues = ref<ValidationIssue[]>([])
const checkChannel = ref<JobChannelState | null>(null)
const importChannel = ref<JobChannelState | null>(null)
const publishChannel = ref<JobChannelState | null>(null)
const publishConflict = ref<PublishConflictAsync | null>(null)

const staging = ref<StagingGroup[]>([])
const stagingLoading = ref(false)
const stagingOpen = ref<string[]>([])
const importMode = ref<ImportMode>('MERGE')
/** R-09 fix round 2：发布确认/读取模式期间的 pending 闸（防双击弹两个确认框）。 */
const publishPending = ref(false)

const gridRefs: Record<string, GridApi> = {}
const editorLoaded = new Set<string>()
/**
 * 作业跟踪 dispose 柄表（check / import / publish 三个 kind 可并存，R-148 B2 直调形态）。
 *
 * 为什么不是裸数组：同 kind 重跑（例如再次「预检查」）时旧 watcher 必须被回收 ——
 * 否则它继续按旧 jobId 打后端，且在途快照会把**上一个作业**的状态写回新作业位
 * （蓝本：ExportWizardView 改绑时的 stopJobWatchForTaskSwitch + beginWatchJob 首行 stopWatchJob?.()）。
 * 回收语义 = 原 createJobWatcherRegistry 的收窄（唯一消费者即本页）：同 kind 先释放旧柄再登记，
 * 卸载/结束全释放；在途快照作废由 job-watch.ts 的 disposed 复检承载（与注册表时代同一道闸）。
 */
const jobWatchDisposes = new Map<string, () => void>()

const editorDefs = computed(() =>
  selectedDefs.value
    .map((code) => defs.value.find((item) => item.code === code))
    .filter((item): item is ConfigDefinition => item !== undefined)
)

const taskSubtitle = computed(() => {
  if (!task.value) {
    return taskId.value ? t('views.import.taskBound', { id: taskId.value }) : ''
  }
  return `#${task.value.id} · ${task.value.title}`
})

/**
 * 步骤条节点级状态（下标与 `WIZARD_STEPS` 对齐）：按**该步作业的终态**逐节点给色，
 * 与当前浏览到哪一步无关。失败步红 + 「（失败）」文字；取消步中性灰（`el-step`
 * 无 cancelled 档，用 'wait' 表达）—— 第 1 步是纯页面步骤、无作业，恒为 ''。
 */
const stepStatuses = computed<('' | 'error' | 'wait')[]>(() => [
  '',
  checkJob.value?.status === 'FAILED' ? 'error' : checkJob.value?.status === 'CANCELLED' ? 'wait' : '',
  importJob.value?.status === 'FAILED' ? 'error' : importJob.value?.status === 'CANCELLED' ? 'wait' : '',
  publishJob.value?.status === 'FAILED' ? 'error' : publishJob.value?.status === 'CANCELLED' ? 'wait' : ''
])

/**
 * 只读态判定（施工卡 §2.2-A）：终案「COMPLETED / CANCELLED 即结束」**不能照字面实现** ——
 * 后端 `JobTerminalWriter` 对**任何**作业终态都调 `TaskService.applyJobOutcome`，`PrecheckJobRunner`
 * 预检查 0 错误即把作业置 COMPLETED ⇒ 任务当场变 COMPLETED。照字面实现会在预检查通过瞬间进入只读、
 * 页脚「下一步 / 启动导入 / 确认发布」全消失，**导入主流程死锁**。
 *
 * 因此"已结束"取「该类型的**最终作业**已成功」为收口判据：导入流程走完 = 最新 PUBLISH 作业 COMPLETED。
 * `publishJob` 为 null（作业未恢复 / 绑定的是导出任务）时 `=== 'COMPLETED'` 天然为 false ⇒ 仍视为进行中。
 * `currentStep` 不能当判据：`TaskService.complete()`（唯一写 `DONE` 的方法）在仓内无调用点，
 * `currentStep` 实测永不为 `DONE`。
 */
const isReadOnly = computed(
  () =>
    task.value?.status === 'CANCELLED' ||
    (task.value?.type === 'IMPORT' &&
      task.value?.status === 'COMPLETED' &&
      publishJob.value?.status === 'COMPLETED')
)

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
    editorLoaded.delete(code)
    return
  }
  gridRefs[code] = el as GridApi
}

function jobRunning(job: Job | null): boolean {
  return job !== null && ['PENDING', 'RUNNING'].includes(job.status)
}

function errorMessageOf(job: Job | null, issues: readonly ValidationIssue[]): string | null {
  if (!job || job.status !== 'FAILED') {
    return null
  }
  const first = issues.find((issue) => issue.severity === 'ERROR')
  return first ? first.message : t('views.import.jobFailed', { count: job.errorCount })
}

function cellValue(row: ConfigStagingRow, fieldCode: string): string {
  const data = parseRowData(row)
  const value = data[fieldCode]
  return value === null || value === undefined ? '' : String(value)
}

// ── 选中集 → 工作区（issue #3：本页唯一写入口） ────────────────────────────────

/**
 * 把页面选中集收敛进 workspace（AI 上下文 `extra.selectedDefs` 的唯一来源）。
 *
 * 与导出向导同因同修：手动勾选（`DefSelector` 的 v-model）与 AI 的 `select_definitions`
 * 页面 handler 此前都只改本页 `selectedDefs` ref，`workspace.selectedDefs` 要等到
 * `persistSelection()`（下载模板/上传等动作）才更新 ⇒ AI 侧 `buildContext()` 读到旧值。
 * 相等短路 ⇒ 恢复任务（`loadTask` 同步块内先赋 ref、紧接着 `enterPage({selectedDefs})`）
 * 不会二次 touch；比较按集合（顺序不敏感，裁决⑤）。
 */
function syncSelectedDefsToWorkspace(source: WorkspaceActionSource = '界面'): void {
  const codes = [...selectedDefs.value]
  const current = new Set(workspace.selectedDefs)
  if (current.size === codes.length && codes.every((code) => current.has(code))) {
    return
  }
  workspace.setSelectedDefs(codes, 'REPLACE', source)
}

// ── 加载 ──────────────────────────────────────────────────────────────────────

async function loadDefinitions(): Promise<void> {
  defsLoading.value = true
  try {
    defs.value = await definitionsApi.listDefinitions()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.defsLoadFailed'))
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
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.taskLoadFailed'))
    return
  }
  selectedDefs.value = (task.value.items ?? []).map((item) => item.defCode)
  const mode = readImportMode(task.value)
  if (mode) {
    importMode.value = mode
  }
  const restored = STEP_KEYS.indexOf(task.value.currentStep as (typeof STEP_KEYS)[number])
  stepIndex.value = restored >= 0 ? restored : 0
  activeEditorTab.value = selectedDefs.value[0] ?? ''
  workspace.enterPage({
    pageId: 'import',
    page: 'import',
    taskType: 'IMPORT',
    step: STEP_KEYS[stepIndex.value],
    taskId: taskId.value,
    taskStatus: task.value.status,
    selectedDefs: [...selectedDefs.value],
    importMode: importMode.value
  })
  await loadUploadedFiles()
  await resumeJobs()
  if (stepIndex.value === 3) {
    await loadStaging()
  }
}

/** 已上传文件（UPLOAD 类型）→ 标签与编辑区内容来源。 */
async function loadUploadedFiles(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  try {
    const files = await tasksApi.getFiles(taskId.value)
    for (const file of files) {
      if (file.fileType === 'UPLOAD') {
        uploadedFiles[file.defCode] = file.fileName ?? `${file.defCode}.xlsx`
        savedDefs.add(file.defCode)
      }
    }
  } catch {
    // 文件列表失败不影响向导
  }
}

/** 恢复未完成的作业（刷新页面后仍能看到进度与明细）。 */
async function resumeJobs(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const jobs = await jobsApi.listJobs(taskId.value).catch(() => [])
  const latest = (type: 'PRECHECK' | 'IMPORT' | 'PUBLISH'): Job | undefined =>
    jobs.filter((job) => job.jobType === type)[0]
  const check = latest('PRECHECK')
  if (check) {
    await attachJob('check', check)
  }
  const importOne = latest('IMPORT')
  if (importOne) {
    await attachJob('import', importOne)
  }
  const publish = latest('PUBLISH')
  if (publish) {
    await attachJob('publish', publish)
  }
}

async function attachJob(kind: 'check' | 'import' | 'publish', job: Job): Promise<void> {
  if (kind === 'check') {
    checkJob.value = job
  } else if (kind === 'import') {
    importJob.value = job
  } else {
    publishJob.value = job
  }
  if (job.id !== undefined) {
    await refreshIssues(kind, job.id)
  }
  if (jobRunning(job) && job.id !== undefined) {
    beginWatchJob(kind, job.id)
  } else if (kind === 'publish' && job.status === 'FAILED') {
    await buildPublishConflict(job)
  }
}

// ── 第 1 步：模板 / 上传 / 在线编辑 ────────────────────────────────────────────

async function persistSelection(): Promise<void> {
  if (taskId.value === null || selectedDefs.value.length === 0) {
    return
  }
  await tasksApi.selectDefs(taskId.value, [...selectedDefs.value])
  // issue #3：与 watch / AI handler 共用同一收敛点（勾选早已同步时此处短路）
  syncSelectedDefsToWorkspace()
}

async function downloadTemplates(): Promise<void> {
  if (taskId.value === null) {
    ElMessage.warning(t('views.import.msg.noTaskBound'))
    return
  }
  if (selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.import.msg.pickDefs'))
    return
  }
  busy.value = 'templates'
  try {
    await persistSelection()
    const blob = await tasksApi.downloadTemplates(taskId.value, [...selectedDefs.value])
    const name = selectedDefs.value.length === 1
      ? xlsxFileName(selectedDefs.value[0] ?? '', defName(selectedDefs.value[0] ?? ''))
      : `templates-${taskId.value}.zip`
    downloadBlob(blob, name)
    ElMessage.success(t('views.import.msg.templateDownloaded', { name }))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.templateDownloadFailed'))
  } finally {
    busy.value = null
  }
}

/**
 * AI 可视可操作专项：单个文件的上传（`handleUpload` 与上传交接共用的**唯一**链路）。
 *
 * 抽出来后两条入口（用户手点 el-upload / 交接卡的 `import.receiveFile`）行为完全一致：
 * 选择持久化 → `tasksApi.uploadFile` → 匹配回填 → 刷新编辑区 → 通知 AI 交接收口。
 * 返回一句页面摘要（**不含路径**），作为 `ui_request_upload` 的回灌素材。
 */
async function uploadOne(file: File): Promise<string> {
  if (taskId.value === null || selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.import.msg.pickDefs'))
    return '未上传：请先勾选配置项'
  }
  busy.value = 'upload'
  try {
    await persistSelection()
    const result = await tasksApi.uploadFile(taskId.value!, file)
    const matched = result.matchedFiles ?? []
    for (const item of matched) {
      uploadedFiles[item.defCode] = item.fileName
      savedDefs.add(item.defCode)
      editorLoaded.delete(item.defCode)
      // 上传新文件 ⇒ 该配置项的旧预览立即失效（内容即将被新文件替换）
      aiStore.onSheetReloaded(item.defCode)
    }
    if (result.unmatchedFiles && result.unmatchedFiles.length > 0) {
      // 多条目须换行展示：ElMessageBox 的 message 支持 VNode，用 Tailwind whitespace-pre-line 还原 \n
      await ElMessageBox.alert(
        h('span', { class: 'whitespace-pre-line' }, result.unmatchedFiles.join('\n')),
        t('views.import.unmatchedFiles'),
        { type: 'warning' }
      )
    }
    ElMessage.success(t('views.import.msg.uploadedMatched', { count: matched.length }))
    // 用服务端刚存下的文件刷新编辑区（保证"所见即服务端所有"）
    await nextTick()
    await loadEditors(true)
    const summary = `已上传并匹配 ${matched.length} 个文件`
    // AI 上传交接联动收口：用户自己点了上传也立即回灌，避免 AI 一直等（无活动交接时是空操作）
    void aiStore.notifyAiUploadDone(summary)
    return summary
  } catch (error) {
    const message = error instanceof Error ? error.message : '文件上传失败'
    ElMessage.error(message)
    return `上传失败：${message}`
  } finally {
    busy.value = null
  }
}

async function handleUpload(uploadFile: UploadFile): Promise<void> {
  const file = uploadFile.raw
  if (!file) {
    return
  }
  await uploadOne(file)
}

/**
 * 载入各配置项的编辑区：有 UPLOAD 文件用文件，否则用客户端生成的模板。
 *
 * <p>L-115：`editorLoaded` 是"载入完成"判据，但并发调用会各扫一遍（同一张表被重复 `fromJSON`）。
 * 用 `editorLoadInFlight` 把并发调用并到同一次载入上 —— 页面能力 `sheet.current` 等待的必须是
 * **这一次真的载入**，而不是"已经有人在载入了"就提前放行。
 */
let editorLoadInFlight: Promise<void> | null = null

async function loadEditors(force = false): Promise<void> {
  if (editorLoadInFlight !== null) {
    await editorLoadInFlight
    if (!force) {
      return
    }
  }
  const run = loadEditorsNow(force)
  editorLoadInFlight = run
  try {
    await run
  } finally {
    if (editorLoadInFlight === run) {
      editorLoadInFlight = null
    }
  }
}

async function loadEditorsNow(force: boolean): Promise<void> {
  if (stepIndex.value !== 0 || taskId.value === null) {
    return
  }
  await nextTick()
  for (const def of editorDefs.value) {
    if (editorLoaded.has(def.code) && !force) {
      continue
    }
    const grid = gridRefs[def.code]
    if (!grid) {
      continue
    }
    try {
      if (uploadedFiles[def.code]) {
        const blob = await tasksApi.downloadFile(taskId.value, def.code)
        const { missingHeaders } = await grid.loadFromXlsx(blob, def.fields)
        if (missingHeaders.length > 0) {
          ElMessage.warning(t('views.import.msg.missingHeaders', { code: def.code, headers: missingHeaders.join(', ') }))
        }
      } else {
        // sheetName 是写进 xlsx 的工作表名（数据面，不随 UI 语言变）
        const json = await buildTemplateWorkbookJson(def.fields, { defCode: def.code, sheetName: '数据', sample: false })
        await grid.loadFromJson(json, def.fields)
      }
      editorLoaded.add(def.code)
      // 交互专项：程序化载入（新文件/重载）⇒ 旧预览立即失效（硬闸仍是 previewId）
      aiStore.onSheetReloaded(def.code)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.editorLoadFailed', { code: def.code }))
    }
  }
}

function onEditorTabChange(name: string | number): void {
  // B12：换到另一张表才让旧预览计划失效（切回同一张表不动）
  aiStore.onSheetTabChanged(String(name))
  void ensureEditorLoaded(String(name))
}

/** B12：用户换选区 ⇒ 选区作用域的预览计划失效（整表/条件计划不受影响）。 */
function onGridSelectionChanged(defCode: string): void {
  aiStore.onSheetSelectionChanged(defCode)
}

/**
 * 等某个配置项的编辑区**真的载入完成**（L-115：页面能力交出适配器前的关闸）。
 *
 * <p>返回真实结局而不是静默 void：载入失败时 `editorLoaded` 不会置位，调用方（`sheet.current`）
 * 必须能把失败如实说出来，而不是交出一张空表让工具读成「0 行」。
 */
async function ensureEditorLoaded(code: string): Promise<{ ok: boolean; error: string }> {
  if (editorLoaded.has(code)) {
    return { ok: true, error: '' }
  }
  await loadEditors()
  if (editorLoaded.has(code)) {
    return { ok: true, error: '' }
  }
  return { ok: false, error: '编辑区表格未能载入（请重试，或重新打开该配置的编辑标签页）' }
}

function onGridChanged(): void {
  // 契约联动：导入编辑区的内容改动同步进 AI 上下文（表格类改动只递增版本，避免刷爆动作记录）
  workspace.touch('导入编辑区内容已修改（尚未写回服务端文件）')
  // AI 表格工具治理 §5.4：手改之后重算「AI 改动 / 你的改动」的现算部分（只计数，不记坐标）
  void useAiStore().refreshSheetApplied(activeEditorCode())
}

/** 当前编辑区的配置编码（刷新 AI 改动摘要用；无编辑区 ⇒ 空串，摘要保持现状）。 */
function activeEditorCode(): string {
  return String(activeEditorTab.value ?? '')
}

/**
 * 把编辑区内容写回服务端文件（作业读的是文件，不是浏览器内存）。
 *
 * 本地动态校验有问题的配置项**跳过**写回并提示，避免把明显不合法的数据送进检查。
 *
 * 返回**实际写入的配置项**（R3-02）：`confirm_step` 从第 1 步推进时会把整表写回服务端文件，
 * 回执必须按这份结果如实说明，不能一边写盘一边说"未保存、仅在浏览器内存"。
 */
interface FlushOutcome {
  ok: boolean
  savedDefs: string[]
  /** 失败原因（底层错误 / 本地校验摘要），供回执原样带出，不统一甩锅“保存失败” */
  reason?: string
}

async function flushEditors(): Promise<FlushOutcome> {
  if (taskId.value === null || selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.import.msg.pickDefs'))
    return { ok: false, savedDefs: [], reason: t('views.import.msg.pickDefs') }
  }
  const written: string[] = []
  busy.value = 'flush'
  let ok = true
  let failureReason: string | undefined
  try {
    await persistSelection()
    for (const def of editorDefs.value) {
      const grid = gridRefs[def.code]
      if (!grid) {
        continue
      }
      const issues = grid.validateLocal()
      if (issues.length > 0) {
        const first = issues[0]
        ElMessage.warning(t('views.import.msg.localIssues', {
          code: def.code,
          count: issues.length,
          row: first?.rowIndex ?? '',
          reason: first?.reason ?? ''
        }))
        ok = false
        failureReason = `${def.code} 存在 ${issues.length} 处本地校验问题（如第 ${first?.rowIndex ?? ''} 行：${first?.reason ?? ''}）`
        continue
      }
      const blob = await grid.toXlsxBlob()
      await tasksApi.saveFile(taskId.value, def.code, await blob.arrayBuffer())
      savedDefs.add(def.code)
      written.push(def.code)
    }
    if (ok) {
      ElMessage.success(t('views.import.msg.draftSaved'))
    }
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.draftSaveFailed'))
    ok = false
    failureReason = detail
  } finally {
    busy.value = null
  }
  return { ok, savedDefs: written, reason: failureReason }
}

// ── 步骤导航 ──────────────────────────────────────────────────────────────────

/**
 * 按步骤下标补齐该步的懒加载数据（写侧切步 `goStep` 与只读查看 `viewStep` 共用同一个钩子）。
 *
 * <b>顺序纪律</b>：调用方必须**先**改 `stepIndex.value` 再调本函数 —— `loadEditors` 在
 * `stepIndex.value !== 0` 时提前 return，顺序反了第 1 步的编辑区就是空白（数据空洞）。
 */
async function ensureStepLoaded(index: number): Promise<void> {
  await nextTick()
  if (index === 0) {
    await loadEditors()
  }
  if (index === 3) {
    await loadStaging()
  }
}

/**
 * 步骤导航（返回"是否真的推进了"）。
 *
 * <p>
 * <b>F2（DC-14）：本方法必须把结局回给调用方</b>。第 1 步的 flushEditors 失败时会**提前返回**
 * （不推进步骤），而 AI 侧 handler 此前无条件回"已推进到…" —— 于是模型收到成功语义、
 * 用户看到的却是没动的界面（S4.4d/f 实测的"报已推进但步骤未推进"）。
 */
/** goStep 推进结局（批3③b D15 对齐导出侧 StepAdvanceOutcome，原 boolean 形态升级）。 */
interface StepAdvanceOutcome {
  /** 是否真的推进了步骤 */
  ok: boolean
  /** 未推进的原因（越界 / 第 1 步 flushEditors 失败） */
  reason?: string
  /** syncStepToBackend 失败 ⇒ 不阻断，调用方须把该警告带进回灌/界面 */
  syncWarning?: string
  /** 离开第 1 步时**实际写回服务端文件**的配置项（R3-02：回执必须按事实说明） */
  savedDefs?: string[]
}

async function goStep(next: number): Promise<StepAdvanceOutcome> {
  if (next < 0 || next >= STEP_KEYS.length) {
    return { ok: false, reason: `目标步骤 ${next} 不在导入向导范围内` }
  }
  let writtenDefs: string[] = []
  if (next > stepIndex.value && stepIndex.value === 0) {
    const flushed = await flushEditors()
    if (!flushed.ok) {
      ElMessage.warning(t('views.import.msg.unsavedEdits'))
      return {
        ok: false,
        reason: `存在未写回的编辑内容（${flushed.reason ?? '保存草稿失败'}），已中止推进`,
        savedDefs: flushed.savedDefs
      }
    }
    writtenDefs = flushed.savedDefs
  }
  stepIndex.value = next
  workspace.setStep(STEP_KEYS[next] ?? null)
  // 批3③a D9（D7 同款语义最小接入）/ 批3③b D15（对齐导出侧）：服务端步骤同步失败**不阻断**
  // 推进，但警告必须可见（单点 warning 在本函数弹）且**带进 AI 回灌** —— 原实现只弹
  // ElMessage、回灌文本仍是干净成功文案（与导出侧不对称，M6 承接项）。
  const synced = await syncStepToBackend()
  let syncWarning: string | undefined
  if (!synced.ok) {
    syncWarning = '步骤已推进但服务端步骤同步失败，下次切步会重试'
    ElMessage.warning(syncWarning)
  }
  await ensureStepLoaded(next)
  return { ok: true, syncWarning, savedDefs: writtenDefs }
}

/**
 * 只读态点节点查看（施工卡 §S3b）：只切本地步骤 + 补齐该步数据，**零服务端写入**。
 *
 * 与 `goStep` 的分工（终案"点节点 = 查看，不是编辑"）：
 * 不调 `flushEditors`（不写文件）、不调 `workspace.setStep`（不把浏览动作写进 AI 渐进披露集）、
 * 不调 `syncStepToBackend`（不发 `PUT /step`）—— 刷新后浏览位置仍以后端 `currentStep` 为准。
 */
async function viewStep(index: number): Promise<void> {
  if (index < 0 || index >= STEP_KEYS.length) {
    return
  }
  stepIndex.value = index
  await ensureStepLoaded(index)
}

/**
 * 把当前步骤写回任务（`PUT /tasks/{id}/step`）。
 *
 * 后端 `currentStep` 是刷新恢复与 AI 上下文（`task:IMPORT/<step>` 的渐进披露子集）
 * 的唯一权威：只在前端切步不进后端，刷新会退回首步，模型也会读到旧步骤。
 */
async function syncStepToBackend(): Promise<{ ok: boolean; reason?: string }> {
  if (taskId.value === null) {
    return { ok: true }
  }
  const step = STEP_KEYS[stepIndex.value]
  if (!step) {
    return { ok: true }
  }
  try {
    task.value = await tasksApi.goToStep(taskId.value, step)
    return { ok: true }
  } catch (error) {
    // 批3③b D11（R-143①）：单点提示 —— 本函数**不自弹**（原 ElMessage.error 删除），由调用方
    // goStep 只弹一条 warning（与导出侧同改；全仓仅两个调用方，不留静默路径）。
    // 结构化失败结局照常返回，由 goStep 带进回灌。
    const reason = error instanceof Error ? error.message : String(error)
    return { ok: false, reason }
  }
}

/** 上一步（goStep 现在返回"是否真的推进"，模板里包一层避免悬空 Promise）。 */
function goPrev(): void {
  void goStep(stepIndex.value - 1)
}

async function nextStep(): Promise<void> {
  if (stepIndex.value === 0 && selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.import.msg.pickAtLeastOne'))
    return
  }
  await goStep(stepIndex.value + 1)
}

// ── 作业：预检查 / 导入 / 发布 ────────────────────────────────────────────────

async function startJob(kind: 'check' | 'import' | 'publish'): Promise<Job | null> {
  if (taskId.value === null) {
    ElMessage.warning(t('views.import.msg.noTaskBound'))
    return null
  }
  const jobType = kind === 'check' ? 'PRECHECK' : kind === 'import' ? 'IMPORT' : 'PUBLISH'
  busy.value = kind
  try {
    const job = await jobsApi.createJob(taskId.value, jobType)
    ElMessage.success(t('views.import.msg.jobStarted', { kind: t(`views.import.kinds.${kind}`), id: job.id ?? '' }))
    await attachJob(kind, job)
    if (job.id !== undefined) {
      beginWatchJob(kind, job.id)
    }
    return job
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.jobStartFailed'))
    return null
  } finally {
    busy.value = null
  }
}

async function startCheck(): Promise<void> {
  await startJob('check')
}

async function startImport(): Promise<void> {
  await startJob('import')
}

/** 发布启动结局（按钮 / handler / 旧 run_job 三入口共用同一套确认语义）。 */
type PublishStartOutcome =
  | { kind: 'started'; job: Job }
  | { kind: 'cancelled' }
  | { kind: 'failed'; reason: string }
  | { kind: 'readonly' }

async function startPublish(): Promise<PublishStartOutcome> {
  // 施工卡 §S3e：只读态不得 createJob
  if (isReadOnly.value) {
    return { kind: 'readonly' }
  }
  // Minor 1：getTask + 确认框都在 startJob 之前，必须在这之前挡住第二次点击
  // （否则快速双击会弹两个对话框，后端只受理一个，另一次变成多余 409/报错）。
  if (publishPending.value) {
    return { kind: 'failed', reason: '发布请求正在处理中，请勿重复点击' }
  }
  publishPending.value = true
  busy.value = 'publish'
  try {
    const id = taskId.value
    if (id === null) {
      const reason = t('views.import.msg.noTaskBound')
      ElMessage.warning(reason)
      return { kind: 'failed', reason }
    }
    // C-1：模式判定只认任务服务端权威值。读取失败 / settings 不可解析 ⇒ fail-closed（不建作业）。
    const resolution = await resolvePublishMode(id)
    if (!resolution.ok) {
      const reason = `${resolution.reason}。本次未启动发布作业`
      ElMessage.error(reason)
      return { kind: 'failed', reason }
    }
    if (resolution.mode !== importMode.value) {
      // 本地副本与服务端不一致时以服务端为准，并让单选回到真实模式。
      importMode.value = resolution.mode
    }
    // Minor 2：同一次权威读取也回写工作区（模型上下文/get_workspace_state 的事实源）。
    // 直接赋字段而非 setImportMode()，避免记一条并不存在的“界面动作”。
    if (workspace.importMode !== resolution.mode) {
      workspace.importMode = resolution.mode
    }
    // R-09：REPLACE 会删除范围内旧行 ⇒ 复用复选框确认；MERGE 增量更新，免确认。
    const confirmed = await confirmPublishByMode(resolution.mode)
    if (!confirmed) {
      return { kind: 'cancelled' }
    }
    // Minor：只有“确认通过、真的要发起”时才清理旧冲突提示；取消不得清掉既有告警。
    publishConflict.value = null
    const job = await startJob('publish')
    return job ? { kind: 'started', job } : { kind: 'failed', reason: t('views.import.msg.jobStartFailed') }
  } finally {
    publishPending.value = false
    // startJob 自己会清 busy；只有我们这层仍持有 'publish'（取消/读取失败）时才复位。
    if (busy.value === 'publish') {
      busy.value = null
    }
  }
}

function beginWatchJob(kind: 'check' | 'import' | 'publish', jobId: number): void {
  if (taskId.value === null) {
    return
  }
  // 同 kind 重跑：先回收旧 watcher（不同 kind 互不回收，三者可并存）——原 registry.recycle 语义
  const previous = jobWatchDisposes.get(kind)
  if (previous) {
    jobWatchDisposes.delete(kind)
    previous()
  }
  const dispose = watchJob({
    taskId: taskId.value,
    jobId,
    // F10：跟踪器自制 dispose（终态）不经登记入口 —— 结束时同步摘柄；对已摘 kind 是幂等空操作，
    // 外部回收路径不会双 dispose。
    onDisposed: () => {
      if (jobWatchDisposes.get(kind) === dispose) {
        jobWatchDisposes.delete(kind)
      }
    },
    onProgress: (payload) => {
      const current = jobOf(kind)
      if (current) {
        setJob(kind, { ...current, status: 'RUNNING', progress: payload.processed, total: payload.total })
      }
    },
    onSnapshot: (job) => {
      setJob(kind, job)
    },
    onChannel: (state) => {
      setChannel(kind, state)
    },
    onFinal: async (job) => {
      setJob(kind, job)
      await refreshIssues(kind, jobId)
      if (kind === 'publish') {
        // Q4：发布恒 201，冲突只能从终态 + issues 读出
        if (job.status === 'FAILED') {
          await buildPublishConflict(job)
          ElMessage.error(t('views.import.msg.publishConflict'))
        } else if (job.status === 'COMPLETED') {
          ElMessage.success(t('views.import.msg.publishDone'))
        } else {
          ElMessage.info(t('views.import.msg.publishCancelled'))
        }
        // 任务状态由后端 JobTerminalWriter 联动作业终态，这里一律刷新快照（失败也要显示 FAILED）
        await refreshTaskSnapshot()
        return
      }
      if (job.status === 'COMPLETED') {
        ElMessage.success(t('views.import.msg.jobDone', { kind: t(`views.import.kinds.${kind}`) }))
      } else if (job.status === 'FAILED') {
        ElMessage.error(t('views.import.msg.jobFailed', { kind: t(`views.import.kinds.${kind}`) }))
      }
      if (kind === 'import' && job.status === 'COMPLETED') {
        await loadStaging()
      }
      await refreshTaskSnapshot()
    }
  })
  jobWatchDisposes.set(kind, dispose)
}

/** 刷新任务快照（状态/步骤），失败时不让页面停在旧状态上。 */
async function refreshTaskSnapshot(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  try {
    task.value = await tasksApi.getTask(taskId.value)
    workspace.setTaskId(taskId.value, 'IMPORT', task.value.status)
  } catch {
    // 任务快照刷新失败不阻断作业结果的展示
  }
}

function jobOf(kind: 'check' | 'import' | 'publish'): Job | null {
  return kind === 'check' ? checkJob.value : kind === 'import' ? importJob.value : publishJob.value
}

function setJob(kind: 'check' | 'import' | 'publish', job: Job): void {
  if (kind === 'check') {
    checkJob.value = job
  } else if (kind === 'import') {
    importJob.value = job
  } else {
    publishJob.value = job
  }
}

function setChannel(kind: 'check' | 'import' | 'publish', state: JobChannelState): void {
  if (kind === 'check') {
    checkChannel.value = state
  } else if (kind === 'import') {
    importChannel.value = state
  } else {
    publishChannel.value = state
  }
}

async function refreshIssues(kind: 'check' | 'import' | 'publish', jobId: number): Promise<void> {
  try {
    const page = await jobsApi.getIssues(jobId, 0, 200)
    const list = page.content ?? []
    if (kind === 'check') {
      checkIssues.value = list
    } else if (kind === 'import') {
      importIssues.value = list
    } else {
      publishIssues.value = list
    }
  } catch {
    // 问题清单拉取失败不阻断主流程
  }
}

/** Q4：作业 FAILED + issues 文案 = 冲突的完整证据链。 */
async function buildPublishConflict(job: Job): Promise<void> {
  const jobId = job.id ?? 0
  const errorIssues = publishIssues.value.filter((issue) => issue.severity === 'ERROR')
  publishConflict.value = {
    jobId,
    jobStatus: 'FAILED',
    issueKeys: errorIssues.map((issue) => `${issue.defCode}#${issue.rowKey ?? issue.rowIndex ?? '-'}`),
    message: errorIssues[0]?.message ?? t('views.import.msg.publishJobFailed')
  }
}

async function cancelJob(job: Job | null): Promise<void> {
  // R-136 收口修 F1（复核 MAJOR-1，红队修正版）：只读态守卫。任务 CANCELLED 后 IMPORT/PUBLISH
  // 等异类型作业可同时在途（后端在途互斥按作业类型），只读壳内「取消作业」仍可发出 DELETE —— 拦在此。
  if (isReadOnly.value) {
    ElMessage.info(t('views.import.msg.taskEndedNoCancel'))
    return
  }
  if (!job?.id) {
    return
  }
  // 批4-D（用户 2026-10-23 裁决）：取消作业不可撤销 —— 由「一点即发」改为复选框确认。
  const confirmed = await confirmIrreversible({
    title: t('views.import.dialog.cancelTitle'),
    message: t('views.import.dialog.cancelMessage'),
    ack: t('views.import.dialog.cancelAck')
  })
  if (!confirmed) {
    return
  }
  try {
    await jobsApi.cancelJob(job.id)
    ElMessage.info(t('views.import.msg.cancelRequested'))
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : t('views.import.msg.cancelFailed'))
  }
}

// ── 第 4 步：暂存核查与发布模式 ────────────────────────────────────────────────

async function loadStaging(): Promise<void> {
  const jobId = importJob.value?.id
  if (!jobId) {
    staging.value = []
    return
  }
  stagingLoading.value = true
  try {
    const rows = await jobsApi.getDiff(jobId)
    const grouped = new Map<string, ConfigStagingRow[]>()
    for (const row of rows) {
      const list = grouped.get(row.defCode) ?? []
      list.push(row)
      grouped.set(row.defCode, list)
    }
    staging.value = [...grouped.entries()].map(([defCode, list]) => ({
      defCode,
      rowCount: list.length,
      rows: list
    }))
    stagingOpen.value = staging.value.map((group) => group.defCode)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.stagingLoadFailed'))
  } finally {
    stagingLoading.value = false
  }
}

async function onImportModeChange(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const requested = importMode.value
  // C-1：PUT 失败时服务端保持原值；本地 v-model 必须回滚，不能停在“未生效的模式”上。
  const previous = readImportMode(task.value ?? { settingsJson: null }) ?? 'MERGE'
  try {
    const updated = await tasksApi.setImportMode(taskId.value, requested)
    if (task.value && updated !== null && typeof updated === 'object') {
      task.value = { ...task.value, ...updated }
    }
    workspace.setImportMode(requested)
    ElMessage.success(t('views.import.msg.importModeSet', {
      mode: requested === 'REPLACE' ? t('views.import.replaceMode') : t('views.import.mergeMode')
    }))
  } catch (error) {
    importMode.value = previous
    ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.importModeFailed'))
  }
}

// ── AI 页面能力 ───────────────────────────────────────────────────────────────

const HANDLER_NAMES = [
  'import.selectDefs',
  'import.upload',
  'import.startPrecheck',
  'import.startImport',
  'import.startPublish',
  'import.nextStep',
  'sheet.current',
  // AI 可视可操作专项：上传交接把用户手势选到的 File 交回页面既有上传链
  'import.receiveFile',
  // 批 D · D3（问题 6）：AI 触发 start_precheck / start_import 后把作业绑进本页跟踪面
  'import.syncJob'
]

/** AI 可视可操作专项：上传区（区域围栏的 root；`data-ai-ref` 挂在其中的三个按钮上）。 */
const aiUploadRowRef = ref<HTMLElement | null>(null)
/** visop 批 B：配置项选择区 / 导入模式区 / 编辑区标签页（三个新增区域的 root）。 */
const aiSelectDefsRef = ref<HTMLElement | null>(null)
const aiModeRowRef = ref<HTMLElement | null>(null)
const aiGridRef = ref<HTMLElement | null>(null)

function registerHandlers(): void {
  // 批 D · D3（问题 6）：AI 触发 start_precheck / start_import 后把作业绑进本页的跟踪面。
  // 与既有 resumeJobs() 同一条恢复链（刷新/回页也走它）：三种作业各归各位（check/import/publish）；
  // taskId 对不上就不动本页（别的任务的作业显示在这里 = 说 A 实际给 B）。
  registerPageHandler('import.syncJob', async (payload) => {
    const jobId = Number(payload.jobId)
    const jobTaskId = Number(payload.taskId)
    if (!Number.isInteger(jobId) || jobId <= 0) {
      return '未同步：作业号无效'
    }
    if (taskId.value === null || taskId.value !== jobTaskId) {
      return `未同步：该作业属于任务 #${jobTaskId}，当前导入向导绑定的是任务 #${taskId.value ?? '（无）'}`
    }
    await resumeJobs()
    const bound = [checkJob.value, importJob.value, publishJob.value].find((job) => job?.id === jobId)
    if (!bound) {
      return `已刷新导入作业列表（未在本任务的作业中找到 #${jobId}）`
    }
    // 终态：结果面刷一次（问题清单由 attachJob 按作业拉过；暂存行只在导入完成时才有内容）。
    // 跑得快的小作业会在结果帧到达前就进入终态 ⇒ 不补这一次，页面会停在空进度上。
    if (!jobRunning(bound)) {
      await refreshTaskSnapshot()
      if (bound.jobType === 'IMPORT' && bound.status === 'COMPLETED') {
        await loadStaging()
      }
    }
    return `已同步${bound.jobType === 'PRECHECK' ? '预检查' : '导入'}作业 #${jobId}（${bound.status}）到导入向导`
  })

  // AI 表格操作专项：当前激活编辑标签页的表格适配器（与导出向导同一套 `sheet.current` 契约）。
  registerPageHandler('sheet.current', async (payload) => {
    const code = String(payload.defCode ?? activeEditorTab.value ?? '')
    if (!code) {
      return { ok: false, reason: '当前没有激活的编辑标签页。请先在第 1 步勾选配置项并打开编辑区。' }
    }
    if (code !== activeEditorTab.value) {
      return {
        ok: false,
        reason: `当前激活的表格是 ${activeEditorTab.value || '无'}，不是 ${code}。请先切换到该配置的编辑标签页。`
      }
    }
    const grid = gridRefs[code]
    if (!grid || typeof grid.getSheetAdapter !== 'function') {
      return { ok: false, reason: `${code} 的编辑区表格未挂载（请重开该标签页后重试）。` }
    }
    try {
      // L-115：编辑区可能还在载入（首次勾选 / 换文件后 `loadEditors` 在飞）—— 等**内容**落定
      // 再交出适配器，否则工具读到空表（T05 的「0 行」/ T13 的「0 组」）。
      const loaded = await ensureEditorLoaded(code)
      if (!loaded.ok) {
        return { ok: false, reason: `${code} 的${loaded.error}` }
      }
      await grid.whenLoaded()
      return {
        ok: true,
        defCode: code,
        sheetName: defName(code),
        readOnly: isReadOnly.value,
        version: workspace.dataVersion,
        fields: defOf(code)?.fields ?? [],
        adapter: grid.getSheetAdapter()
      }
    } catch (error) {
      return { ok: false, reason: `${code} 的编辑区表格未就绪：${error instanceof Error ? error.message : String(error)}` }
    }
  })

  // 补丁②：AI 工作区动作 confirm_step 的页面实现（推进到下一步；与用户点「下一步」同路径）
  registerPageHandler('import.nextStep', async (payload) => {
    // 施工卡 §S3e：只读态拦在 goStep 之前 —— 已结束任务不得借 AI 通道推进步骤（零写）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，不能推进步骤'
    }
    const target = typeof payload.step === 'string' && payload.step !== '' ? payload.step : null
    const fromIndex = stepIndex.value
    const index = target ? STEP_KEYS.indexOf(target as (typeof STEP_KEYS)[number]) : stepIndex.value + 1
    if (index < 0 || index >= STEP_KEYS.length) {
      return {
        ok: false,
        reason: `步骤 ${payload.step ?? index} 不在导入向导范围内（可选：${STEP_KEYS.join('、')}）`
      }
    }
    // I-4 / Minor 3：目标就是当前步 ⇒ 在 goStep 之前短路，既不发起无意义的 PUT /step，
    // 也不产生一个会被丢弃的 syncWarning。
    if (index === fromIndex) {
      return `步骤未改变：当前已在「${stepLabel(STEP_KEYS[index])}」，本次未触发写回。`
    }
    // F2（DC-14）：回灌文本必须与真实结局一致 —— 未推进就如实说"未推进 + 为什么"，
    // 否则模型会基于假的成功语义继续往下走（例如直接 start_import，而用户还停在第一步）。
    const advanced = await goStep(index)
    if (!advanced.ok) {
      const partial = advanced.savedDefs ?? []
      const partialNote = partial.length > 0
        ? `其中 ${partial.length} 个配置项（${partial.join('、')}）的编辑内容已写回服务端文件；`
        : ''
      // I-3：确定性失败必须返回结构化 `{ok:false}`，否则工具卡会把“未推进”渲染成成功；
      // Minor：`advanced.reason` 原样带出（底层错误 / 本地校验），不再统一甩锅“保存失败”。
      return {
        ok: false,
        reason: `步骤未推进：当前仍在「${stepLabel(STEP_KEYS[stepIndex.value] ?? '')}」。`
          + `${advanced.reason ?? '原因未知'}。`
          + partialNote
          + '请提示用户修正编辑区数据（或先点「保存草稿到服务端」）后重试。'
      }
    }
    // I-4：只有“真正从第 1 步往后离开”才可能触发整表写回；回退 / 从第 2 步往后
    // 都不能套用写回话术（否则会出现“已推进到当前步”“没有可写回内容”两句假话）。
    if (index < fromIndex) {
      return `已回退到导入向导步骤「${stepLabel(STEP_KEYS[index])}」，本次未从第 1 步离开，未触发整表写回。`
    }
    // R3-02：离开第 1 步会 saveFile（整表写回服务端文件）—— 回执必须按实际写入结果说明，
    // 不能让模型/用户以为改动还停留在浏览器内存里。
    const saved = advanced.savedDefs ?? []
    const writeNote = fromIndex === 0
      ? (saved.length > 0
        ? `；离开第 1 步前已把 ${saved.length} 个配置项（${saved.join('、')}）的编辑内容写回服务端文件，后续预检查/导入读取的是这份文件`
        : '；本次未写回服务端文件（没有可写回的编辑区内容）')
      : '；本次未从第 1 步离开，未触发整表写回'
    const syncNote = advanced.syncWarning ? `（${advanced.syncWarning}）` : ''
    return `已推进到导入向导步骤「${stepLabel(STEP_KEYS[index])}」${syncNote}${writeNote}。`
  })

  registerPageHandler('import.selectDefs', (payload) => {
    // 施工卡 §S3e：只读态不改变所选配置项（含收敛工作区与重载编辑区一并短路）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未变更所选配置项'
    }
    const codes = Array.isArray(payload.defCodes) ? payload.defCodes.map((item) => String(item)) : []
    selectedDefs.value = payload.mode === 'ADD' ? [...new Set([...selectedDefs.value, ...codes])] : codes
    // issue #3：AI handler 分支同样要显式收敛（来源记 'AI'；watch 随后比较已相等 ⇒ 短路）
    syncSelectedDefsToWorkspace('AI')
    activeEditorTab.value = selectedDefs.value[0] ?? ''
    void loadEditors(true)
    return `已选择配置项：${selectedDefs.value.join('、') || '（空）'}`
  })

  registerPageHandler('import.upload', () => {
    // 收口修 F5（红队 minor-3）：只读态如实回灌 —— 上传入口在只读页已隐藏（v-if="!isReadOnly"），
    // 若仍引导用户去点「上传 xlsx / zip」就是指向一个不存在的按钮（违反 DC-14 回灌与真实结局一致）。
    if (isReadOnly.value) {
      return '任务已结束，仅供查看；上传入口在只读页面不可用，本次未执行上传。'
    }
    return '上传文件需要浏览器文件句柄：请提示用户点击「上传 xlsx / zip」选择文件（文件名需包含配置项编码）。'
      + '若对话内已出现「选择文件」按钮，请让用户点它（那是同一套上传链的交接入口）。'
  })

  // AI 可视可操作专项：上传交接回手 —— 只收 File 句柄（**永不接受路径**），走既有 uploadOne 链
  registerPageHandler('import.receiveFile', async (payload) => {
    if (isReadOnly.value) {
      return '任务已结束，仅供查看；本次未上传任何文件。'
    }
    const file = payload.file
    if (!(file instanceof File)) {
      return { ok: false, reason: '未接收到文件句柄，本次未上传任何文件。' }
    }
    return await uploadOne(file)
  })

  registerPageHandler('import.startPrecheck', async () => {
    // 施工卡 §S3e：只读态不得 createJob（不许在已结束任务上建新作业）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未启动预检查作业'
    }
    await startJob('check')
    return checkJob.value ? `预检查作业 #${checkJob.value.id} 已启动` : '预检查未启动'
  })

  registerPageHandler('import.startImport', async () => {
    // 施工卡 §S3e：只读态不得 createJob
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未启动导入作业'
    }
    await startJob('import')
    return importJob.value ? `导入作业 #${importJob.value.id} 已启动` : '导入未启动'
  })

  registerPageHandler('import.startPublish', async () => {
    // R-09：与页面按钮、旧 run_job 分支同一套确认语义（REPLACE 复选框 / MERGE 免确认）。
    // 失败/取消统一返回结构化 `{ok:false}`：工具结局落 failed，避免“卡面成功、正文未启动”。
    const outcome = await startPublish()
    if (outcome.kind === 'readonly') {
      return { ok: false, reason: '任务已结束，仅供查看，未启动发布作业' }
    }
    if (outcome.kind === 'cancelled') {
      return { ok: false, reason: '用户取消了发布确认，未启动发布作业' }
    }
    if (outcome.kind === 'failed') {
      return { ok: false, reason: outcome.reason }
    }
    if (outcome.job.id === undefined) {
      return '发布作业已启动（服务端未返回作业号）；发布结果按异步形态给出（作业终态 + 问题清单）'
    }
    return `发布作业 #${outcome.job.id} 已启动；发布结果按异步形态给出（作业终态 + 问题清单）`
  })
}

/**
 * AI 可视可操作专项（原裁 4 = A）：上传回退的**拖拽**入口。
 *
 * 只在存在活动交接时接管拖放（其余时刻保持页面原有行为，视觉零变化）：
 * 交接卡在浏览器无 `showOpenFilePicker` 时提示「拖到高亮区域」，这里就是那个区域。
 * 拖进来的 `File` 与手势选中的走**同一条** `uploadOne`。
 */
function onAiDragOver(event: DragEvent): void {
  if (aiStore.activeUploadHandoff) {
    event.preventDefault()
  }
}

function onAiDrop(event: DragEvent): void {
  if (!aiStore.activeUploadHandoff) {
    return
  }
  event.preventDefault()
  const file = event.dataTransfer?.files?.[0]
  if (file) {
    void uploadOne(file)
  }
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: STEP_KEYS[0], taskId: taskId.value })
  // AI 可视可操作专项：声明 AI 当前页（换页才推进 epoch），再注册上传区
  noteAiPage('import')
  registerHandlers()
  registerAiUploadRegion()
  registerAiSelectDefsRegion()
  registerAiModeRegion()
  await Promise.all([loadDefinitions(), loadTask()])
  await loadEditors()
  // 编辑区标签页是 `v-if` 渲染的：挂载期可能还不存在，出现后再补注册（批 B / import.grid）
  await nextTick()
  registerAiGridRegion()
})

/**
 * AI 可视可操作专项：注册上传区（区域围栏的唯一入口）。
 *
 * 三个按钮都显式标 `consequential`（下载模板 = 数据出域；上传 = 交出字节；保存草稿 = 服务端写）
 * ⇒ 通用原语 `ui_click` **一次都不会点**它们；上传只能走 `ui_request_upload` 的交接卡。
 * 视觉零变化：这里只加属性，不改任何 class / 结构。
 */
function registerAiUploadRegion(): void {
  const root = aiUploadRowRef.value
  if (!root) {
    return
  }
  registerAiRegion({
    id: 'import.upload',
    root,
    risk: 'consequential',
    page: 'import',
    label: '上传区（导入向导第 1 步）',
    uploadEntry: 'import.upload.choose',
    readOnly: () => isReadOnly.value,
    receiveFile: (file: File) => uploadOne(file)
  })
}

/**
 * visop 批 B / O-2：配置项选择区（勾选框 = `mutate-local`，搜索与层级筛选同档）。
 *
 * 勾选只改本页/工作区的选中集，落库发生在推进步骤（`persistStepData`）⇒ 不是后果性动作。
 */
function registerAiSelectDefsRegion(): void {
  const root = aiSelectDefsRef.value
  if (!root) {
    return
  }
  registerAiRegion({
    id: 'import.selectDefs',
    root,
    risk: 'mutate-local',
    page: 'import',
    label: '配置项选择（导入向导第 1 步）',
    readOnly: () => isReadOnly.value
  })
}

/**
 * visop 批 B / O-2：导入模式单选（MERGE / REPLACE）。
 *
 * **与 42 件 §3.4.3 的登记不同**：那里记的是 `mutate-local`，但本页 `onImportModeChange`
 * 直接 `PUT /tasks/{id}/import-mode`（`ImportWizardView.vue:1075`）—— 是服务端写。
 * 按 `read` = 不产生服务端写的定义，这里必须标 `consequential`：通用原语一次不点，
 * 指路注册动作 `set_import_mode`（它进后端确认门）。
 */
function registerAiModeRegion(): void {
  const root = aiModeRowRef.value
  if (!root) {
    return
  }
  registerAiRegion({
    id: 'import.mode',
    root,
    risk: 'consequential',
    page: 'import',
    label: '导入模式（导入向导第 4 步）',
    readOnly: () => isReadOnly.value
  })
}

/** visop 批 B / O-2：编辑区表格标签页（切 tab = 只改视口，`read`）。 */
function registerAiGridRegion(): void {
  const root = aiGridRef.value
  if (!root) {
    unregisterAiRegion('import.grid')
    return
  }
  registerAiRegion({
    id: 'import.grid',
    root,
    risk: 'read',
    page: 'import',
    label: '编辑区标签页（导入向导第 1 步）',
    readOnly: () => isReadOnly.value
  })
}

// 编辑区随勾选集出现/消失：出现即注册、消失即注销（漏收 = 脏 ref）。
watch(
  () => editorDefs.value.length,
  async () => {
    await nextTick()
    registerAiGridRegion()
  }
)

onBeforeUnmount(() => {
  // B12：离开承载表格的页面 ⇒ 预览计划失效（屏幕色同步清），不回灌
  aiStore.onSheetPageLeft()
  // 卸载/结束：释放全部在途订阅（原 registry.disposeAll 语义；dispose 会经 onDisposed 摘柄，
  // 先拷贝再迭代避免遍历中改表）
  for (const dispose of [...jobWatchDisposes.values()]) {
    dispose()
  }
  jobWatchDisposes.clear()
  HANDLER_NAMES.forEach(unregisterPageHandler)
  // AI 可视可操作专项：区域漏收 = 跨页脏 ref（isConnected 拦得住"元素没了"，
  // 拦不住"ref 指向另一页同名元素"）
  unregisterAiRegion('import.upload')
  unregisterAiRegion('import.selectDefs')
  unregisterAiRegion('import.mode')
  unregisterAiRegion('import.grid')
})

async function createAndBindTask(): Promise<void> {
  creatingTask.value = true
  try {
    const created = await taskStore.createTask('IMPORT', t('views.tasks.autoName', {
      type: t('views.tasks.importType'),
      time: new Date().toLocaleString('zh-CN')
    }))
    if (!created || created.id === undefined) {
      ElMessage.error(taskStore.actionError ?? t('views.import.msg.taskCreateFailed'))
      return
    }
    taskId.value = created.id
    await router.replace({ name: 'import-wizard', query: { taskId: String(created.id) } })
    await loadTask()
    ElMessage.success(t('views.import.msg.taskCreated', { id: created.id }))
  } finally {
    creatingTask.value = false
  }
}

// 勾选变化：先收敛进工作区（issue #3，同步调用放在首行，避免被编辑器加载耗时挤后），再补齐编辑区
watch(selectedDefs, async () => {
  syncSelectedDefsToWorkspace()
  activeEditorTab.value = selectedDefs.value[0] ?? ''
  await loadEditors(true)
})

/**
 * 契约联动（issue #1）：AI 侧 `navigate_to(page="import", taskId=N)` 经 `restore_task`
 * **只改路由 query**；同一路由记录下本组件不会重建（onMounted 不再执行），任务绑定必须
 * 由本页自己跟上 —— 否则 URL 已带 `?taskId=N` 而 `workspace.taskId` 仍是 null，
 * 紧随的 `select_definitions` / `set_import_mode` 会被 needsTask 判成"当前没有进行中的任务"。
 *
 * 先同步绑定 workspace（与 onMounted 同序），再拉任务详情；query 变为无 taskId 时不动绑定。
 */
watch(
  () => route.query.taskId,
  async (raw) => {
    const parsed = Number(raw)
    const id = Number.isFinite(parsed) && parsed > 0 ? parsed : null
    if (id === null || id === taskId.value) {
      return
    }
    // 交互专项：换任务 ⇒ 旧任务的表格预览一律失效（不允许拿旧计划改新任务的数据）
    aiStore.onSheetPageLeft()
    taskId.value = id
    workspace.setTaskId(id, 'IMPORT', null)
    await loadTask()
  }
)

/**
 * 契约联动：外部（AI 经 `ui_event.goto_step` → 契约层）改了工作区步骤时，页面跟随。
 * 只读 `workspace.step`，不反向写回（用户切步走 goStep），因此不会形成回环。
 */
watch(
  () => workspace.step,
  (step) => {
    const index = step ? STEP_KEYS.indexOf(step as (typeof STEP_KEYS)[number]) : -1
    if (index >= 0 && index !== stepIndex.value) {
      stepIndex.value = index
      if (index === 3) {
        void loadStaging()
      }
    }
  }
)
</script>
