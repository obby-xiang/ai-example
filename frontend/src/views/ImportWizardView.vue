<template>
  <TaskWizard
    title="导入配置向导"
    :subtitle="taskSubtitle"
    :steps="WIZARD_STEPS"
    :active-index="stepIndex"
    :task-status="task?.status ?? null"
    :prev-disabled="stepIndex === 0"
    :show-next="stepIndex < 3"
    :next-loading="busy === 'persist'"
    @prev="goPrev"
    @next="nextStep"
  >
    <!-- ============ 第 1 步：上传配置 ============ -->
    <div v-show="stepIndex === 0">
      <el-alert
        v-if="!taskId"
        type="warning"
        :closable="false"
        show-icon
        title="尚未绑定任务"
        description="导入向导需要挂在一条导入任务上（文件与暂存行按任务存储）。可直接创建任务进入，或从任务中心点「打开」。"
        class="mb-3"
      >
        <el-button size="small" type="primary" :loading="creatingTask" class="mt-2" @click="createAndBindTask">
          创建导入任务并进入
        </el-button>
      </el-alert>

      <DefSelector v-model="selectedDefs" :defs="defs" :loading="defsLoading" />

      <div class="flex items-center gap-3 my-3">
        <el-button size="small" :disabled="!selectedDefs.length" :loading="busy === 'templates'" @click="downloadTemplates">
          下载模板（{{ selectedDefs.length > 1 ? 'zip' : 'xlsx' }}）
        </el-button>
        <el-upload
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx,.zip"
          :disabled="!selectedDefs.length || busy === 'upload'"
          :on-change="handleUpload"
        >
          <el-button size="small" type="primary" :loading="busy === 'upload'" :disabled="!selectedDefs.length">
            上传 xlsx / zip
          </el-button>
        </el-upload>
        <el-button size="small" :disabled="!selectedDefs.length" :loading="busy === 'flush'" @click="flushEditors">
          保存草稿到服务端
        </el-button>
        <span class="text-xs text-[#909399]">
          按文件名中的配置项编码匹配（如 COUNTRY.xlsx）；zip 内可含多个 xlsx；未上传的配置项按模板在线编辑。
        </span>
      </div>

      <template v-if="editorDefs.length">
        <el-tabs v-model="activeEditorTab" type="border-card" @tab-change="onEditorTabChange">
          <el-tab-pane v-for="def in editorDefs" :key="def.code" :name="def.code">
            <template #label>
              {{ def.name }}（{{ def.code }}）
              <el-tag v-if="uploadedFiles[def.code]" size="small" type="success" class="ml-1">已上传</el-tag>
              <el-tag v-else-if="savedDefs.has(def.code)" size="small" type="info" class="ml-1">草稿已存</el-tag>
            </template>
            <SpreadGrid
              :ref="(el) => setGridRef(def.code, el)"
              height="380px"
              :def-code="def.code"
              :sheet-name="def.name"
              @data-changed="onGridChanged"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else description="请先勾选配置项，再下载模板 / 上传文件 / 在线编辑" :image-size="60" />
    </div>

    <!-- ============ 第 2 步：检查配置 ============ -->
    <div v-show="stepIndex === 1">
      <div class="flex items-center gap-3 mb-3">
        <el-button type="primary" :loading="busy === 'check'" :disabled="jobRunning(checkJob)" @click="startCheck">
          启动预检查
        </el-button>
        <span class="text-xs text-[#909399]">校验必填、类型、枚举与跨配置项引用；不写入任何数据。</span>
      </div>
      <JobProgress
        :job="checkJob"
        :issues="checkIssues"
        :channel="checkChannel"
        :error-message="errorMessageOf(checkJob, checkIssues)"
        @cancel="cancelJob(checkJob)"
      />
      <el-alert
        v-if="checkJob && checkJob.status === 'COMPLETED'"
        type="success"
        :closable="false"
        show-icon
        class="mt-3"
        title="预检查通过，可进入下一步导入。"
      />
      <el-alert
        v-else-if="checkJob && checkJob.status === 'FAILED'"
        type="warning"
        :closable="false"
        show-icon
        class="mt-3"
        title="存在校验错误，可返回第 1 步修改数据后重新检查，或直接继续（导入时会再次校验）。"
      />
    </div>

    <!-- ============ 第 3 步：导入配置 ============ -->
    <div v-show="stepIndex === 2">
      <div class="flex items-center gap-3 mb-3">
        <el-button type="primary" :loading="busy === 'import'" :disabled="jobRunning(importJob)" @click="startImport">
          启动导入
        </el-button>
        <span class="text-xs text-[#909399]">再次校验后写入暂存区（暂不发布）；有错误的配置项其下游依赖将被跳过。</span>
      </div>
      <JobProgress
        :job="importJob"
        :issues="importIssues"
        :channel="importChannel"
        :error-message="errorMessageOf(importJob, importIssues)"
        @cancel="cancelJob(importJob)"
      />
    </div>

    <!-- ============ 第 4 步：发布配置 ============ -->
    <div v-show="stepIndex === 3">
      <el-card shadow="never" class="mb-3.5">
        <template #header>
          <div class="flex justify-between items-center">
            <span>暂存数据核查</span>
            <div class="flex items-center gap-2">
              <el-radio-group v-model="importMode" size="small" @change="onImportModeChange">
                <el-radio-button value="MERGE">增量合并</el-radio-button>
                <el-radio-button value="REPLACE">范围内替换</el-radio-button>
              </el-radio-group>
              <el-button size="small" :loading="stagingLoading" @click="loadStaging">刷新</el-button>
            </div>
          </div>
        </template>
        <el-empty v-if="!staging.length" description="暂存区暂无数据，请先完成导入" :image-size="60" />
        <el-collapse v-else v-model="stagingOpen">
          <el-collapse-item v-for="group in staging" :key="group.defCode" :name="group.defCode">
            <template #title>
              <!-- 行高必须落在这个块级元素上：inline 子元素的小 line-height 会被 el-collapse-item__title 的 48px 行高「撑」回去 -->
              <span class="block whitespace-normal leading-snug">{{ `${defName(group.defCode)}（${group.defCode}）— ${group.rowCount} 行` }}</span>
            </template>
            <el-table :data="group.rows.slice(0, 100)" size="small" border max-height="300">
              <el-table-column prop="rowKey" label="业务键" width="150" />
              <el-table-column prop="opType" label="操作" width="90">
                <template #default="{ row }: { row: ConfigStagingRow }">
                  <el-tag size="small" :type="row.opType === 'DELETE' ? 'danger' : 'success'">
                    {{ row.opType === 'DELETE' ? '删除' : '写入' }}
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
            <div v-if="group.rowCount > 100" class="py-1.5 text-xs text-[#909399]">仅展示前 100 行</div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <div class="flex items-center gap-3 mb-3">
        <el-button type="primary" :loading="busy === 'publish'" :disabled="jobRunning(publishJob)" @click="startPublish">
          确认发布
        </el-button>
        <span class="text-xs text-[#909399]">
          {{ importMode === 'REPLACE' ? '发布将按配置项范围内替换正式区数据（范围内未覆盖的旧行删除）。' : '发布将按业务键增量更新正式区数据。' }}
        </span>
      </div>

      <!-- Q4 异步形态：发布请求恒 201，冲突只从作业终态 + issues 读出 -->
      <el-alert
        v-if="publishConflict"
        type="error"
        :closable="false"
        show-icon
        class="mb-3"
        :title="`发布冲突（异步形态）：${publishConflict.message}`"
        :description="`作业 #${publishConflict.jobId} 终态 ${publishConflict.jobStatus}；冲突行：${publishConflict.issueKeys.slice(0, 8).join('、')}${publishConflict.issueKeys.length > 8 ? ' 等' : ''}。发布请求本身已受理（201），请按提示重新导入后再发布。`"
      />

      <JobProgress
        :job="publishJob"
        :issues="publishIssues"
        :channel="publishChannel"
        :error-message="errorMessageOf(publishJob, publishIssues)"
        @cancel="cancelJob(publishJob)"
      />
    </div>
  </TaskWizard>
</template>

<script setup lang="ts">
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
import { buildTemplateWorkbookJson, downloadBlob, xlsxFileName, type ExcelRowIssue } from '@/utils/excel'
import { watchJob, type JobChannelState } from '@/utils/job-watch'
import { stepLabel } from '@/utils/format'
import type { PublishConflictAsync } from '@/types/api'
import { parseRowData, type ConfigStagingRow } from '@/types/data'
import type { ConfigDefinition, ConfigField } from '@/types/definition'
import type { Job, ValidationIssue } from '@/types/job'
import { readImportMode, TaskSteps, type ImportMode, type Task } from '@/types/task'
import type { WorkspaceActionSource } from '@/types/tools'

interface GridApi {
  loadFromXlsx(blob: Blob, fields: readonly ConfigField[]): Promise<{ missingHeaders: string[] }>
  loadFromJson(json: string, fields: readonly ConfigField[]): Promise<void>
  loadData(fields: readonly ConfigField[], rows?: readonly Record<string, unknown>[]): void
  collectRows(): Array<Record<string, string>>
  toXlsxBlob(): Promise<Blob>
  validateLocal(): ExcelRowIssue[]
}

interface StagingGroup {
  defCode: string
  rowCount: number
  rows: ConfigStagingRow[]
}

type BusyKind = 'persist' | 'templates' | 'upload' | 'flush' | 'check' | 'import' | 'publish' | null

const STEP_KEYS = TaskSteps.IMPORT
const WIZARD_STEPS = [
  { key: STEP_KEYS[0], label: '上传配置' },
  { key: STEP_KEYS[1], label: '检查配置' },
  { key: STEP_KEYS[2], label: '导入配置' },
  { key: STEP_KEYS[3], label: '发布配置' }
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

const gridRefs: Record<string, GridApi> = {}
const editorLoaded = new Set<string>()
let stopWatchers: Array<() => void> = []

const editorDefs = computed(() =>
  selectedDefs.value
    .map((code) => defs.value.find((item) => item.code === code))
    .filter((item): item is ConfigDefinition => item !== undefined)
)

const taskSubtitle = computed(() => {
  if (!task.value) {
    return taskId.value ? `任务 #${taskId.value}` : ''
  }
  return `#${task.value.id} · ${task.value.title}`
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
  return first ? first.message : `作业失败（错误 ${job.errorCount} 条），请展开明细查看`
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
    jobs.filter((job) => job.jobType === type).slice(-1)[0]
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
    ElMessage.warning('尚未绑定任务')
    return
  }
  if (selectedDefs.value.length === 0) {
    ElMessage.warning('请先勾选配置项')
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
    ElMessage.success(`已下载模板 ${name}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板下载失败')
  } finally {
    busy.value = null
  }
}

async function handleUpload(uploadFile: UploadFile): Promise<void> {
  const file = uploadFile.raw
  if (!file) {
    return
  }
  if (taskId.value === null || selectedDefs.value.length === 0) {
    ElMessage.warning('请先勾选配置项')
    return
  }
  busy.value = 'upload'
  try {
    await persistSelection()
    const result = await tasksApi.uploadFile(taskId.value, file)
    const matched = result.matchedFiles ?? []
    for (const item of matched) {
      uploadedFiles[item.defCode] = item.fileName
      savedDefs.add(item.defCode)
      editorLoaded.delete(item.defCode)
    }
    if (result.unmatchedFiles && result.unmatchedFiles.length > 0) {
      // 多条目须换行展示：ElMessageBox 的 message 支持 VNode，用 Tailwind whitespace-pre-line 还原 \n
      await ElMessageBox.alert(
        h('span', { class: 'whitespace-pre-line' }, result.unmatchedFiles.join('\n')),
        '以下文件未匹配到配置项',
        { type: 'warning' }
      )
    }
    ElMessage.success(`已上传并匹配 ${matched.length} 个文件`)
    // 用服务端刚存下的文件刷新编辑区（保证"所见即服务端所有"）
    await nextTick()
    await loadEditors(true)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '文件上传失败')
  } finally {
    busy.value = null
  }
}

/** 载入各配置项的编辑区：有 UPLOAD 文件用文件，否则用客户端生成的模板。 */
async function loadEditors(force = false): Promise<void> {
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
          ElMessage.warning(`${def.code} 的文件缺少字段列：${missingHeaders.join('、')}`)
        }
      } else {
        const json = await buildTemplateWorkbookJson(def.fields, { defCode: def.code, sheetName: '数据', sample: false })
        await grid.loadFromJson(json, def.fields)
      }
      editorLoaded.add(def.code)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : `${def.code} 编辑区加载失败`)
    }
  }
}

function onEditorTabChange(name: string | number): void {
  void ensureEditorLoaded(String(name))
}

async function ensureEditorLoaded(code: string): Promise<void> {
  if (editorLoaded.has(code)) {
    return
  }
  await loadEditors()
}

function onGridChanged(): void {
  // 契约联动：导入编辑区的内容改动同步进 AI 上下文（表格类改动只递增版本，避免刷爆动作记录）
  workspace.touch('导入编辑区内容已修改（尚未写回服务端文件）')
}

/**
 * 把编辑区内容写回服务端文件（作业读的是文件，不是浏览器内存）。
 *
 * 本地动态校验有问题的配置项**跳过**写回并提示，避免把明显不合法的数据送进检查。
 */
async function flushEditors(): Promise<boolean> {
  if (taskId.value === null || selectedDefs.value.length === 0) {
    ElMessage.warning('请先勾选配置项')
    return false
  }
  busy.value = 'flush'
  let ok = true
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
        ElMessage.warning(
          `${def.code} 存在 ${issues.length} 处本地校验问题（如第 ${first?.rowIndex} 行：${first?.reason}），已跳过写回`
        )
        ok = false
        continue
      }
      const blob = await grid.toXlsxBlob()
      await tasksApi.saveFile(taskId.value, def.code, await blob.arrayBuffer())
      savedDefs.add(def.code)
    }
    if (ok) {
      ElMessage.success('草稿已保存到服务端（作业将读取该文件）')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '草稿保存失败')
    ok = false
  } finally {
    busy.value = null
  }
  return ok
}

// ── 步骤导航 ──────────────────────────────────────────────────────────────────

/**
 * 步骤导航（返回"是否真的推进了"）。
 *
 * <p>
 * <b>F2（DC-14）：本方法必须把结局回给调用方</b>。第 1 步的 flushEditors 失败时会**提前返回**
 * （不推进步骤），而 AI 侧 handler 此前无条件回"已推进到…" —— 于是模型收到成功语义、
 * 用户看到的却是没动的界面（S4.4d/f 实测的"报已推进但步骤未推进"）。
 */
async function goStep(next: number): Promise<boolean> {
  if (next < 0 || next >= STEP_KEYS.length) {
    return false
  }
  if (next > stepIndex.value && stepIndex.value === 0) {
    const saved = await flushEditors()
    if (!saved) {
      ElMessage.warning('存在未写回的编辑内容，请先修正后再继续')
      return false
    }
  }
  stepIndex.value = next
  workspace.setStep(STEP_KEYS[next] ?? null)
  await syncStepToBackend()
  await nextTick()
  if (next === 0) {
    await loadEditors()
  }
  if (next === 3) {
    await loadStaging()
  }
  return true
}

/**
 * 把当前步骤写回任务（`PUT /tasks/{id}/step`）。
 *
 * 后端 `currentStep` 是刷新恢复与 AI 上下文（`task:IMPORT/<step>` 的渐进披露子集）
 * 的唯一权威：只在前端切步不进后端，刷新会退回首步，模型也会读到旧步骤。
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

/** 上一步（goStep 现在返回"是否真的推进"，模板里包一层避免悬空 Promise）。 */
function goPrev(): void {
  void goStep(stepIndex.value - 1)
}

async function nextStep(): Promise<void> {
  if (stepIndex.value === 0 && selectedDefs.value.length === 0) {
    ElMessage.warning('请先选择至少一个配置项')
    return
  }
  await goStep(stepIndex.value + 1)
}

// ── 作业：预检查 / 导入 / 发布 ────────────────────────────────────────────────

async function startJob(kind: 'check' | 'import' | 'publish'): Promise<void> {
  if (taskId.value === null) {
    ElMessage.warning('尚未绑定任务')
    return
  }
  const jobType = kind === 'check' ? 'PRECHECK' : kind === 'import' ? 'IMPORT' : 'PUBLISH'
  busy.value = kind
  try {
    const job = await jobsApi.createJob(taskId.value, jobType)
    ElMessage.success(`${kind === 'check' ? '预检查' : kind === 'import' ? '导入' : '发布'}作业已启动（作业 #${job.id}）`)
    await attachJob(kind, job)
    if (job.id !== undefined) {
      beginWatchJob(kind, job.id)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '作业启动失败')
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

async function startPublish(): Promise<void> {
  publishConflict.value = null
  await startJob('publish')
}

function beginWatchJob(kind: 'check' | 'import' | 'publish', jobId: number): void {
  if (taskId.value === null) {
    return
  }
  const dispose = watchJob({
    taskId: taskId.value,
    jobId,
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
          ElMessage.error('发布未成功：请查看冲突明细后重新导入再发布')
        } else if (job.status === 'COMPLETED') {
          ElMessage.success('发布成功，任务已完成')
        } else {
          ElMessage.info('发布作业已取消')
        }
        // 任务状态由后端 JobTerminalWriter 联动作业终态，这里一律刷新快照（失败也要显示 FAILED）
        await refreshTaskSnapshot()
        return
      }
      if (job.status === 'COMPLETED') {
        ElMessage.success(`${kind === 'check' ? '预检查' : '导入'}作业完成`)
      } else if (job.status === 'FAILED') {
        ElMessage.error(`${kind === 'check' ? '预检查' : '导入'}作业失败，请展开明细查看`)
      }
      if (kind === 'import' && job.status === 'COMPLETED') {
        await loadStaging()
      }
      await refreshTaskSnapshot()
    }
  })
  stopWatchers.push(dispose)
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
    message: errorIssues[0]?.message ?? '发布作业失败，请查看校验问题清单'
  }
}

async function cancelJob(job: Job | null): Promise<void> {
  if (!job?.id) {
    return
  }
  try {
    await jobsApi.cancelJob(job.id)
    ElMessage.info('已请求取消作业')
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '取消作业失败')
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
    ElMessage.error(error instanceof Error ? error.message : '暂存数据加载失败')
  } finally {
    stagingLoading.value = false
  }
}

async function onImportModeChange(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  try {
    await tasksApi.setImportMode(taskId.value, importMode.value)
    workspace.setImportMode(importMode.value)
    ElMessage.success(`导入模式已设为${importMode.value === 'REPLACE' ? '范围内替换' : '增量合并'}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入模式设置失败')
  }
}

// ── AI 页面能力 ───────────────────────────────────────────────────────────────

const HANDLER_NAMES = [
  'import.selectDefs',
  'import.upload',
  'import.startPrecheck',
  'import.startImport',
  'import.startPublish',
  'import.nextStep'
]

function registerHandlers(): void {
  // 补丁②：AI 工作区动作 confirm_step 的页面实现（推进到下一步；与用户点「下一步」同路径）
  registerPageHandler('import.nextStep', async (payload) => {
    const target = typeof payload.step === 'string' && payload.step !== '' ? payload.step : null
    const index = target ? STEP_KEYS.indexOf(target as (typeof STEP_KEYS)[number]) : stepIndex.value + 1
    if (index < 0 || index >= STEP_KEYS.length) {
      return `步骤 ${payload.step ?? index} 不在导入向导范围内（可选：${STEP_KEYS.join('、')}）`
    }
    // F2（DC-14）：回灌文本必须与真实结局一致 —— 未推进就如实说"未推进 + 为什么"，
    // 否则模型会基于假的成功语义继续往下走（例如直接 start_import，而用户还停在第一步）。
    const advanced = await goStep(index)
    if (!advanced) {
      return `步骤未推进：当前仍在「${stepLabel(STEP_KEYS[stepIndex.value] ?? '')}」。`
        + '第 1 步离开前会把在线编辑内容写回服务端文件，存在本地校验问题或保存失败时会中止。'
        + '请提示用户修正编辑区数据（或先点「保存草稿到服务端」）后重试。'
    }
    return `已推进到导入向导步骤「${stepLabel(STEP_KEYS[index])}」`
  })

  registerPageHandler('import.selectDefs', (payload) => {
    const codes = Array.isArray(payload.defCodes) ? payload.defCodes.map((item) => String(item)) : []
    selectedDefs.value = payload.mode === 'ADD' ? [...new Set([...selectedDefs.value, ...codes])] : codes
    // issue #3：AI handler 分支同样要显式收敛（来源记 'AI'；watch 随后比较已相等 ⇒ 短路）
    syncSelectedDefsToWorkspace('AI')
    activeEditorTab.value = selectedDefs.value[0] ?? ''
    void loadEditors(true)
    return `已选择配置项：${selectedDefs.value.join('、') || '（空）'}`
  })

  registerPageHandler('import.upload', () => {
    return '上传文件需要浏览器文件句柄：请提示用户点击「上传 xlsx / zip」选择文件（文件名需包含配置项编码）。'
  })

  registerPageHandler('import.startPrecheck', async () => {
    await startJob('check')
    return checkJob.value ? `预检查作业 #${checkJob.value.id} 已启动` : '预检查未启动'
  })

  registerPageHandler('import.startImport', async () => {
    await startJob('import')
    return importJob.value ? `导入作业 #${importJob.value.id} 已启动` : '导入未启动'
  })

  registerPageHandler('import.startPublish', async () => {
    await startJob('publish')
    return publishJob.value
      ? `发布作业 #${publishJob.value.id} 已启动；发布结果按异步形态给出（作业终态 + 问题清单）`
      : '发布未启动'
  })
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'import', page: 'import', taskType: 'IMPORT', step: STEP_KEYS[0], taskId: taskId.value })
  registerHandlers()
  await Promise.all([loadDefinitions(), loadTask()])
  await loadEditors()
})

onBeforeUnmount(() => {
  stopWatchers.forEach((dispose) => dispose())
  stopWatchers = []
  HANDLER_NAMES.forEach(unregisterPageHandler)
})

async function createAndBindTask(): Promise<void> {
  creatingTask.value = true
  try {
    const created = await taskStore.createTask('IMPORT', `导入任务 ${new Date().toLocaleString('zh-CN')}`)
    if (!created || created.id === undefined) {
      ElMessage.error(taskStore.error ?? '创建任务失败')
      return
    }
    taskId.value = created.id
    await router.replace({ name: 'import-wizard', query: { taskId: String(created.id) } })
    await loadTask()
    ElMessage.success(`已创建导入任务 #${created.id}`)
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
