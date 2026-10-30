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
          :accept="UPLOAD_ACCEPT"
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
                @data-changed="onGridChanged(def.code, $event)"
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
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
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
import { uiAbandonGuard, uiAbandoned, uiAbandonedReply, uiStepAbandonedReply } from '@/utils/ui-lifetime'
import { resolvePublishMode } from '@/utils/publish-mode'
import { stepLabel } from '@/utils/format'
import { definitionCodesProblem, normalizeDefinitionCodes } from '@/utils/definition-codes'
import { UPLOAD_ACCEPT, checkUploadFile } from '@/constants/upload'
import type { PublishConflictAsync } from '@/types/api'
import { parseRowData, type ConfigStagingRow } from '@/types/data'
import type { ConfigDefinition, ConfigField } from '@/types/definition'
import type { Job, ValidationIssue } from '@/types/job'
import { readImportMode, TaskSteps, type ImportMode, type Task } from '@/types/task'
import type { WorkspaceActionSource } from '@/types/tools'

const { t, locale } = useI18n()

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
/**
 * 任务详情是否已**应用**到本页（选中集 / 导入模式 / 步骤 / 状态已按服务端事实恢复并 `enterPage`）。
 *
 * 与导出页同口径（见 `ExportWizardView.vue` 同名 ref）：契约层经 `import.taskDetailReady`
 * 探针等它变 true 才放行后续动作，避免 `confirm_step` 拿空选中集推进并落库。
 * 置 true 的时点在 `enterPage` 之后，**不等**作业恢复 / 暂存行 / 表格引擎 / 编辑器。
 */
const taskDetailReady = ref(false)
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
/**
 * 编辑区当前内容的**来源**（按任务代次记账）。
 *
 * 为什么不是"载入过"的布尔：这里必须回答"表格里现在是什么字节"，否则两类失真无法区分：
 * - `file` = 服务端文件字节（写回它可以覆盖同名文件，内容一致）；
 * - `template` = 客户端空模板 —— 只有**确认过服务端没有该文件**时才允许写回，
 *   否则就是把模板当内容覆盖真文件（换任务、文件清单晚到都会走到这里）。
 * 代次写在记录里：旧代次的记录天然失效，不需要"谁去 delete 谁的标记"这种会误清新标记的操作。
 */
interface EditorState {
  generation: number
  source: 'file' | 'template'
}
const editorState = new Map<string, EditorState>()

/**
 * 第 1 步编辑区的**未保存改动**标记（按配置项编码）。
 *
 * <p>为什么必须记：表格里的字节与「服务端文件」是两回事 —— 整页 `loadEditors(true)`（旧实现里
 * 勾选/上传都会这么干）、取消勾选（标签页卸载）、换任务复位、离开页面都会用服务端字节
 * （或空模板）覆盖表格，用户刚改的行就此消失（2026-10-26 审查 P1-1）。有了这份标记，
 * 重载/复位/卸载前才能"先判脏再决定写回还是确认"。
 *
 * <p>置位 = `@data-changed`（真 `SpreadGrid` 在程序化载入/上色期间（`restoring`）不触发该事件，
 * 所以载入不会把自己标脏）；清零 = 载入成功 / 写回成功 / 上传替换 / 换任务复位。
 */
const dirtyDefs = reactive(new Set<string>())

/** 该配置项的编辑区是否已按**当前代次**载入过内容。 */
function editorReady(code: string): boolean {
  return editorState.get(code)?.generation === taskGeneration
}

/**
 * 任务代次（A→B 改绑 = 新一代）：在途的载入/写回结局一旦发现代次变了就作废。
 *
 * 为什么需要：`editorState` / `uploadedFiles` / `savedDefs` 是进程级状态，而任务绑定可以在
 * **同一个组件实例**上换掉（`navigate_to` 只改路由 query）。不换代次，旧任务回来的文件清单、
 * 载入完成标记、甚至整本工作簿字节都会落到新任务的页面上（A 的数据写进 B）。
 */
let taskGeneration = 0
/**
 * 当前任务的服务端文件清单是否读到（`GET /files`）。
 *
 * `false` 的含义是"**不知道**服务端有哪些文件"，不是"没有文件"：把它当空清单会让第 1 步
 * 用客户端空模板 `PUT` 上去，直接覆盖服务端已有文件。所以它是**载入的前置条件**：
 * 清单未知时 `loadEditors` 不构造模板（见 `ensureFileList`），写回闸也不放行（`flushEditors`）。
 */
const filesOk = ref(false)
/** 文件清单的在途请求（单飞：清单只拉一次，载入/写回/重试共用同一笔）。 */
let filesInFlight: Promise<boolean> | null = null
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

/**
 * `select_definitions` 的编码校验（与导出向导、契约层兜底同源；判据与文案在
 * `utils/definition-codes`）。非法编码不得写进选中集 / 工作区，也不得让回执说"已选择"。
 *
 * @returns `null` = 全部编码可用；否则为可直接回灌的失败原因（点名无效码 + 可用码清单）
 */
function selectDefsProblem(codes: readonly string[]): string | null {
  return definitionCodesProblem(codes, defs.value.map((item) => item.code), { loading: defsLoading.value })
}

/**
 * 字段可用性（编辑区载入 / 写回的公共前置）。
 *
 * 0 字段时 `loadFromXlsx` / `loadFromJson` 会"成功"载入一张 0 列的表，`editorState` 被置位，
 * 随后 `toXlsxBlob()` 产出空工作簿 —— 预检查/导入读到的就是这个空文件（覆盖了用户上传的
 * 真实文件），页面上却显示"已保存草稿"。
 */
function fieldsUnavailableReason(code: string): string | null {
  if (defOf(code) === null) {
    return `配置定义 ${code} 不在当前定义列表里（定义列表可能尚未加载或加载失败）`
  }
  if (fieldsOf(code).length === 0) {
    return `配置定义 ${code} 没有字段`
  }
  return null
}

function setGridRef(code: string, el: unknown): void {
  if (!el) {
    delete gridRefs[code]
    // 只摘同代次的记录：卸载发生在换任务之后时不得清掉新代次已载入的标记
    if (editorState.get(code)?.generation === taskGeneration) {
      editorState.delete(code)
    }
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
 * `persistSelection()`（推进步骤等动作）才更新 ⇒ AI 侧 `buildContext()` 读到旧值。
 * 相等短路 ⇒ 恢复任务（`loadTask` 同步块内先赋 ref、紧接着 `enterPage({selectedDefs})`）
 * 不会二次 touch；比较按集合（顺序不敏感，裁决⑤）。
 */
function syncSelectedDefsToWorkspace(source: WorkspaceActionSource = 'UI'): void {
  const codes = [...selectedDefs.value]
  const current = new Set(workspace.selectedDefs)
  if (current.size === codes.length && codes.every((code) => current.has(code))) {
    return
  }
  workspace.setSelectedDefs(codes, 'REPLACE', source)
}

// ── 加载 ──────────────────────────────────────────────────────────────────────

/**
 * 等待定义列表就绪的上限（毫秒；与导出向导同口径）。
 *
 * <p>挂载时 `loadDefinitions()` 与 `loadTask()` 并行，`selectedDefs` 的 watch 会在定义到达前
 * 触发 `loadEditors()` —— 此刻 `editorDefs` 为空，一次什么都没载入就结束。首轮定义**失败**后
 * 换任务（`bindTask → loadTask → watch`）也没有任何重试 ⇒ 编辑区永久空白。这里补上与导出
 * 侧同族的"等待 / 补一次 / 到达后恢复"，失败仍 fail-closed（不按 0 字段载入）。
 */
const DEFINITIONS_READY_TIMEOUT_MS = 10_000

/**
 * 定义补请求的**冷却时长**（毫秒）：一次失败后，冷却期内的编辑区载入直接快速失败，
 * 不再为每个 AI 工具调用各发一次 `GET /api/definitions`（挂起/失败时会把 60s 工具预算吃满）。
 * 显式换任务或用户显式重载会重置冷却（= 新的重试意图）。
 */
const DEFINITIONS_RETRY_COOLDOWN_MS = 5_000

/** 定义列表是否成功读到过（空列表也算成功；失败保持 false）。 */
const defsLoaded = ref(false)
/** 定义列表的在途请求（单飞：挂载 / 编辑区等待 / 补载共用同一笔）。 */
let defsInFlight: Promise<boolean> | null = null
/** 本轮定义补请求的冷却截止（毫秒时间戳）。失败后在冷却期内快速失败，不再每个工具调用重发 GET。 */
let definitionsRetryBlockedUntil = 0
/** 本轮定义请求的起始时刻（用于把"等待上界"按**每次请求**计，而不是每次调用各等一轮）。 */
let defsAttemptStartedAt = 0

async function loadDefinitions(): Promise<boolean> {
  if (defsInFlight !== null) {
    return await defsInFlight
  }
  defsLoading.value = true
  if (defsAttemptStartedAt === 0) {
    defsAttemptStartedAt = Date.now()
  }
  const run = (async (): Promise<boolean> => {
    try {
      defs.value = await definitionsApi.listDefinitions()
      defsLoaded.value = true
      // 定义晚到：此前因"没定义"没能载入的编辑区自动补载（不必等用户重开标签 / 刷新整页）
      void recoverEditorsAfterDefinitions()
      return true
    } catch (error) {
      defsLoaded.value = false
      // 失败即进入冷却：AI 工具的连续调用不得各自再发一次 GET（挂起/失败时会把预算吃满）
      definitionsRetryBlockedUntil = Date.now() + DEFINITIONS_RETRY_COOLDOWN_MS
      ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.defsLoadFailed'))
      return false
    } finally {
      defsLoading.value = false
      defsAttemptStartedAt = 0
    }
  })()
  defsInFlight = run
  try {
    return await run
  } finally {
    if (defsInFlight === run) {
      defsInFlight = null
    }
  }
}

/**
 * 等定义列表就绪（有界）：在途就等它；没在途就按当前事实判定。
 *
 * 等待预算是**按本次请求**计的：同一个挂起请求被多次调用等待时，后续调用只等"剩余额度"，
 * 不会每次调用各等满一轮（否则一个挂起请求会被 AI 的连续工具调用反复消费预算）。
 *
 * @returns true = 已成功读到**非空**定义列表（可建编辑区）；false = 加载中到点 / 失败 / 列表为空
 */
async function awaitDefinitionsReady(timeoutMs = DEFINITIONS_READY_TIMEOUT_MS): Promise<boolean> {
  if (defsInFlight === null) {
    return defsLoaded.value && defs.value.length > 0
  }
  const elapsed = defsAttemptStartedAt === 0 ? 0 : Date.now() - defsAttemptStartedAt
  const remaining = Math.max(0, timeoutMs - elapsed)
  if (remaining === 0) {
    return false
  }
  let timer: ReturnType<typeof setTimeout> | null = null
  const expired = new Promise<boolean>((resolve) => {
    timer = setTimeout(() => resolve(false), remaining)
  })
  try {
    const ok = await Promise.race([defsInFlight, expired])
    return ok === true && defs.value.length > 0
  } finally {
    if (timer !== null) {
      clearTimeout(timer)
    }
  }
}

/**
 * 编辑区载入前的定义闸：已有定义 ⇒ 直接过；在途 ⇒ 有界等待；首轮失败 ⇒ **冷却期内快速失败**，
 * 冷却结束（或用户显式换任务 / 重载）后才再补一次请求。
 *
 * 补请求只在 `!defsLoaded` 时发生（成功读到空列表后不再重复）—— 它由"编辑区真的要载入"
 * 这一动作触发，且同一失败后有 5s 冷却（不是轮询、也不是每次工具调用都重发）。
 */
async function ensureDefinitionsForEditors(): Promise<void> {
  if (defs.value.length > 0) {
    return
  }
  if (defsInFlight !== null) {
    await awaitDefinitionsReady()
    return
  }
  if (defsLoaded.value) {
    return
  }
  if (Date.now() < definitionsRetryBlockedUntil) {
    return
  }
  await loadDefinitions()
}

/** 重置定义补请求冷却（显式换任务 / 用户显式重载 = 新的重试意图）。 */
function resetDefinitionsRetryCooldown(): void {
  definitionsRetryBlockedUntil = 0
}

/**
 * 定义不可用时的**可行动成因**（给用户与模型的失败文案用）。
 *
 * 为什么需要：冷却期内编辑区载入会快速失败，但旧文案只有"编辑区表格未能载入（请重试…）"
 * —— 模型读不出"是定义列表读不到 / 正在冷却"，只会原地重试同一调用（并继续撞冷却）。
 */
function definitionsUnavailableHint(): string | null {
  if (defs.value.length > 0) {
    return null
  }
  const remainingMs = definitionsRetryBlockedUntil - Date.now()
  if (remainingMs > 0) {
    const seconds = Math.max(1, Math.ceil(remainingMs / 1000))
    return `配置定义列表最近一次读取失败，${seconds} 秒内不再自动重试（冷却中）`
  }
  if (defsLoaded.value) {
    return '配置定义列表为空（服务端没有任何配置定义）'
  }
  return '配置定义列表尚未就绪（加载中或读取失败）'
}

/**
 * 定义到达后的自动恢复：第 1 步有选中项时补载尚未载入的编辑区（`loadEditors` 只补未载入的）。
 */
async function recoverEditorsAfterDefinitions(): Promise<void> {
  if (stepIndex.value !== 0 || taskId.value === null || selectedDefs.value.length === 0) {
    return
  }
  await loadEditors()
}

/**
 * 加载任务详情 + 文件清单 + 作业/暂存面。
 *
 * **顺序纪律（C1 根面）**：文件清单必须在 `selectedDefs` 之前落定。`selectedDefs` 的赋值会
 * 触发 watch → `loadEditors()`，而编辑区走"下载服务端文件"还是"构造空模板"取决于
 * `uploadedFiles`。若先赋选中集再等清单，编辑区必然先构造出模板并置位"已载入"，
 * 清单回来后**没有任何东西会重载它** ⇒ 第 1 步「下一步」就把模板 PUT 覆盖 B 的真实文件。
 */
async function loadTask(): Promise<void> {
  if (taskId.value === null) {
    taskDetailReady.value = false
    return
  }
  const generation = taskGeneration
  // 详情重新起飞：在 `enterPage` 之前一律不认"已应用"
  taskDetailReady.value = false
  let loaded: Task
  try {
    loaded = await tasksApi.getTask(taskId.value)
  } catch (error) {
    if (generation === taskGeneration) {
      taskDetailReady.value = false
      ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.taskLoadFailed'))
    }
    return
  }
  // 旧任务的详情晚到：不得覆盖新任务的标题/步骤/勾选（I5）
  if (generation !== taskGeneration) {
    return
  }
  // 类型相反 ⇒ 本页不是它的向导：**静默**改投导出向导（不弹提示），否则导入页会拿一条
  // 导出任务恢复上传清单与编辑区（"导出任务显示成导入向导"）。
  if (loaded.type === 'EXPORT') {
    taskDetailReady.value = false
    await router.replace({ name: 'export-wizard', query: { taskId: String(taskId.value) } })
    return
  }
  task.value = loaded
  // 清单先落定：编辑区的"文件 / 模板"分支只能基于已知清单决定
  await ensureFileList()
  if (generation !== taskGeneration) {
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
  // 详情已应用：选中集 / 导入模式 / 步骤 / 状态都已按服务端事实恢复并声明进工作区
  //（**不等**下面的作业恢复与暂存行 —— 它们不影响后续动作的正确性）
  taskDetailReady.value = true
  await resumeJobs()
  if (generation !== taskGeneration) {
    return
  }
  if (stepIndex.value === 3) {
    await loadStaging()
  }
}

/**
 * 已上传文件（UPLOAD 类型）→ 标签与编辑区内容来源。
 *
 * @returns 文件清单是否读到。`false` 时页面**不知道**服务端有哪些文件，写回闸必须关闭。
 */
async function loadUploadedFiles(): Promise<boolean> {
  if (taskId.value === null) {
    filesOk.value = false
    return false
  }
  const generation = taskGeneration
  try {
    const files = await tasksApi.getFiles(taskId.value)
    // 换任务期间回来的清单属于旧任务：不得挂到新任务的标签上
    if (generation !== taskGeneration) {
      return false
    }
    for (const file of files) {
      if (file.fileType === 'UPLOAD') {
        uploadedFiles[file.defCode] = file.fileName ?? `${file.defCode}.xlsx`
        savedDefs.add(file.defCode)
      }
    }
    filesOk.value = true
    // 清单晚到（首次失败后补读成功 / 迟到的首次清单）：此前按"无文件"构造的模板不是服务端内容，
    // 必须作废，等调用方按文件重载（`flushEditors` 会 `await loadEditors(true)`）。
    for (const [code, state] of [...editorState]) {
      if (state.generation === generation && state.source === 'template' && uploadedFiles[code] !== undefined) {
        editorState.delete(code)
      }
    }
    return true
  } catch {
    // 文件列表失败不影响向导本身 —— 但"不知道有哪些文件"绝不能当成"没有文件"
    if (generation === taskGeneration) {
      filesOk.value = false
    }
    return false
  }
}

/**
 * 保证文件清单已落定（单飞：并发调用共享同一笔请求）。
 *
 * 这是编辑区载入的**前置条件**：清单未知时不允许构造模板（否则模板会被当成内容写回）。
 */
async function ensureFileList(): Promise<boolean> {
  if (filesOk.value) {
    return true
  }
  if (filesInFlight !== null) {
    return await filesInFlight
  }
  const run = loadUploadedFiles()
  filesInFlight = run
  try {
    return await run
  } finally {
    if (filesInFlight === run) {
      filesInFlight = null
    }
  }
}

/**
 * 换任务（A→B）时的页面态复位。
 *
 * 与 `ExportWizardView#resetTaskScopedState` 同因同修：下列状态此前是**跨任务**的进程级状态。
 * 同一路由记录下换成任务 B、而 B 有同名配置项时：
 * 1. `editorState` 仍说"已载入" ⇒ 不重新载入，编辑区里还是 **A 的字节**；
 * 2. `uploadedFiles` 仍是 A 的文件名 ⇒ 载入分支选错（去下载 B 根本没有的文件）；
 * 3. 随后第 1 步的 `flushEditors` 把 A 的字节 `PUT /tasks/B/files/{code}` ⇒ **A 的数据写进 B**。
 * 另外旧任务的作业跟踪（watcher）会继续按旧 jobId 取数、把旧作业的进度/终态写回本页。
 */
function resetTaskScopedState(): void {
  taskGeneration += 1
  // 换任务 = 详情重新起飞：新任务的 `enterPage` 之前不认"已应用"（导出页同名口径）
  taskDetailReady.value = false
  filesOk.value = false
  filesInFlight = null
  editorLoadInFlight = null
  for (const code of Object.keys(uploadedFiles)) {
    delete uploadedFiles[code]
  }
  savedDefs.clear()
  editorState.clear()
  // 脏标记是任务级状态：换任务时已经结算（写回或用户确认放弃）过，这里清零
  dirtyDefs.clear()
  // 导入模式是任务设置（settingsJson）：不复位会让 B 继承 A 的模式（界面与 AI 上下文都错）
  importMode.value = 'MERGE'
  // 表格视口里显示的仍是上一个任务的内容：清空，B 的载入成功后才会有内容
  for (const [code, grid] of Object.entries(gridRefs)) {
    try {
      grid.loadData(fieldsOf(code), [])
    } catch {
      // 引擎未就绪等情形：真正的载入由 loadEditors 覆盖，清屏失败不影响读闸
    }
  }
  for (const dispose of [...jobWatchDisposes.values()]) {
    dispose()
  }
  jobWatchDisposes.clear()
  checkJob.value = null
  importJob.value = null
  publishJob.value = null
  checkIssues.value = []
  importIssues.value = []
  publishIssues.value = []
  checkChannel.value = null
  importChannel.value = null
  publishChannel.value = null
  publishConflict.value = null
  staging.value = []
  stagingOpen.value = []
  // 任务级 loading 标志（N1）：在途操作的 finally 有"仅同代次复位"的守卫，换任务后不会自己清；
  // 不在这里复位，新任务的页面上就是永久转圈的按钮（保存草稿/刷新暂存/发布…直到刷新页面）。
  busy.value = null
  stagingLoading.value = false
  publishPending.value = false
}

/** 恢复未完成的作业（刷新页面后仍能看到进度与明细）。 */
async function resumeJobs(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const generation = taskGeneration
  const jobs = await jobsApi.listJobs(taskId.value).catch(() => [])
  // 旧任务的作业列表晚到：不得挂到新任务的作业面板，更不得为新任务登记旧作业的 watcher（I5）
  if (generation !== taskGeneration) {
    return
  }
  const latest = (type: 'PRECHECK' | 'IMPORT' | 'PUBLISH'): Job | undefined =>
    jobs.filter((job) => job.jobType === type)[0]
  const check = latest('PRECHECK')
  if (check) {
    await attachJob('check', check, generation)
  }
  const importOne = latest('IMPORT')
  if (importOne) {
    await attachJob('import', importOne, generation)
  }
  const publish = latest('PUBLISH')
  if (publish) {
    await attachJob('publish', publish, generation)
  }
}

async function attachJob(
  kind: 'check' | 'import' | 'publish',
  job: Job,
  generation: number = taskGeneration
): Promise<void> {
  if (generation !== taskGeneration) {
    return
  }
  if (kind === 'check') {
    checkJob.value = job
  } else if (kind === 'import') {
    importJob.value = job
  } else {
    publishJob.value = job
  }
  if (job.id !== undefined) {
    await refreshIssues(kind, job.id, generation)
  }
  if (generation !== taskGeneration) {
    return
  }
  if (jobRunning(job) && job.id !== undefined) {
    beginWatchJob(kind, job.id, generation)
  } else if (kind === 'publish' && job.status === 'FAILED') {
    await buildPublishConflict(job)
  }
}

// ── 第 1 步：模板 / 上传 / 在线编辑 ────────────────────────────────────────────

/**
 * 选中集落库（默认写到当前绑定任务；换任务在途的调用方传入口捕获的任务号）。
 *
 * <p>**唯一提交点是"下一步"**（`flushEditors`）：配置项选择在页面上是草案，下载模板/上传
 * 只读草案、各自携带编码请求即可（见 {@link downloadTemplates} / {@link uploadOne}），
 * 不得借这两个动作把选择集写进 task_items。
 */
async function persistSelection(forTask: number | null = taskId.value): Promise<void> {
  if (forTask === null || selectedDefs.value.length === 0) {
    return
  }
  await tasksApi.selectDefs(forTask, [...selectedDefs.value])
  // issue #3：与 watch / AI handler 共用同一收敛点（勾选早已同步时此处短路）
  syncSelectedDefsToWorkspace()
}

/**
 * 下载模板：只按当前草案的选中集取模板字节（`GET /files/templates?codes=…`）。
 *
 * <p>不提交选择集 —— 下载模板不改变服务端任务，选择集的提交点是"下一步"（`flushEditors`）。
 */
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
 * 未保存编辑的**确认放弃**对话框（批次 1C 的唯一确认入口）。
 *
 * <p>只问一件事："这些编辑写不回服务端，你还要继续吗？"——调用方按返回值决定是否维持原状
 * （勾选/导航/任务都不动，脏标记保留）。文案全部走 locales（`views.import.msg.*`），
 * 动态内容按完整模板插值（codes / reason / taskId）。
 *
 * @returns true = 用户确认放弃这些编辑；false = 用户取消（调用方必须回滚到原状）
 */
async function confirmDiscardEdits(title: string, message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

/**
 * 上传前的脏区确认（批次 1C）。
 *
 * <p>为什么是"确认"而不是"写回"：上传的文件就是该配置项要落盘的新内容，把编辑区里的旧编辑写回
 * 只会把刚上传的文件盖回旧数据；所以这里只能让用户明确知道"匹配到的编辑区会被刷新"。
 * 未匹配到的配置项不受影响（{@link uploadOne} 只重载被替换的编码）。
 *
 * @returns false = 用户取消本次上传（调用方必须中止，一个字节都不发）
 */
async function confirmDirtyBeforeUpload(): Promise<boolean> {
  if (dirtyDefs.size === 0) {
    return true
  }
  return await confirmDiscardEdits(
    t('views.import.dialog.unsavedUploadTitle'),
    t('views.import.msg.unsavedUploadConfirm', { codes: [...dirtyDefs].join('、') })
  )
}

/**
 * AI 可视可操作专项：单个文件的上传（`handleUpload` 与上传交接共用的**唯一**链路）。
 *
 * 抽出来后两条入口（用户手点 el-upload / 交接卡的 `import.receiveFile`）行为完全一致：
 * 携带当前草案 `defCodes` 上传 → 匹配回填 → 刷新编辑区 → 通知 AI 交接收口。
 * 返回一句页面摘要（**不含路径**），作为 `ui_request_upload` 的回灌素材。
 *
 * <p>上传**不提交选择集**：落库对象是 `task_files` + 文件存储，文件名匹配由后端按请求携带的
 * `defCodes` 完成（选择集的提交点仍是"下一步"）。
 */
async function uploadOne(file: File): Promise<string> {
  // F9：扩展名 / 大小前置拦截。三条入口（手点 el-upload、AI 交接 `import.receiveFile`、
  // 交接高亮区的拖放）都汇到本函数，因此**只这一处**就覆盖全部入口：
  // - 扩展名：`.xlsx` / `.zip`（常量与选择器 accept 同源，见 constants/upload）；
  // - 大小：后端 `spring.servlet.multipart.max-file-size: 5MB` 的前置镜像 —— 只是提前给
  //   可行动提示，后端仍是权威（放行不等于受理，拒绝也绝不替它补收）。
  const rejection = checkUploadFile(file)
  if (rejection) {
    const message = t(rejection.key, { name: rejection.name })
    ElMessage.warning(message)
    return `未上传：${message}`
  }
  if (taskId.value === null || selectedDefs.value.length === 0) {
    ElMessage.warning(t('views.import.msg.pickDefs'))
    return '未上传：请先勾选配置项'
  }
  // 批次 1C：上传会替换匹配项的编辑区内容 ⇒ 有未保存编辑时先明确确认（确认框期间可能换任务，
  // 故代次/任务号在确认之后才捕获）
  if (!(await confirmDirtyBeforeUpload())) {
    return t('views.import.msg.uploadCancelledUnsaved')
  }
  const generation = taskGeneration
  const forTask = taskId.value
  // 草案选择集的快照：上传在途时用户可能继续勾选，匹配口径必须是"发起这一刻"的选中集
  const draftDefCodes = [...selectedDefs.value]
  busy.value = 'upload'
  try {
    const result = await tasksApi.uploadFile(forTask, file, draftDefCodes)
    // 上传在途时换了任务：结果属于旧任务，不得把文件名/标签挂到新任务上（I5）
    if (generation !== taskGeneration) {
      return '上传已提交给原任务，但页面已切换到别的任务，本次结果未应用到当前页面'
    }
    const matched = result.matchedFiles ?? []
    for (const item of matched) {
      uploadedFiles[item.defCode] = item.fileName
      savedDefs.add(item.defCode)
      editorState.delete(item.defCode)
      // 该配置项的内容被新文件替换：编辑区里的旧编辑已无意义（确认框已如实告知）
      dirtyDefs.delete(item.defCode)
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
    // 批次 1C：**只**重载本次被替换的配置项 —— 整页 `loadEditors(true)` 会把其它配置项
    // 未保存的编辑一并换成服务端旧文件（这正是不该被上传偷偷带走的数据）
    await loadEditors(true, matched.map((item) => item.defCode))
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
 * <p>L-115：并发调用会各扫一遍（同一张表被重复 `fromJSON`），用 `editorLoadInFlight` 并到
 * 同一次载入上 —— 页面能力 `sheet.current` 等待的必须是**这一次真的载入**。
 *
 * <p>**前置条件（C1）**：文件清单必须先落定。清单未知时既不能下载（不知道有没有文件），
 * 更不能构造模板（模板会被当成内容写回）。所以这里先 `ensureFileList()`；拉不到就不载入，
 * 由写回闸如实拦下并提示重试。
 *
 * @param force `true` = 已载入的也重载（只有**内容确实被替换**时才该这么用：上传新文件）。
 *   默认 `false` 只补未载入的 —— 勾选配置项时若整页强制重载，会把其它配置项未保存的编辑
 *   换成服务端旧文件（2026-10-26 审查 P1-1）。
 * @param only 只处理这些配置项（`[]` = 一个都不处理）；省略 = 全部 `editorDefs`。
 */
let editorLoadInFlight: Promise<void> | null = null

async function loadEditors(force = false, only?: readonly string[]): Promise<void> {
  // 显式重载（force）= 用户重试意图：允许重新尝试定义列表（冷却期不再挡住重试）
  if (force) {
    resetDefinitionsRetryCooldown()
  }
  if (editorLoadInFlight !== null) {
    await editorLoadInFlight
    if (!force) {
      return
    }
  }
  const run = loadEditorsNow(force, only)
  editorLoadInFlight = run
  try {
    await run
  } finally {
    if (editorLoadInFlight === run) {
      editorLoadInFlight = null
    }
  }
}

async function loadEditorsNow(force: boolean, only?: readonly string[]): Promise<void> {
  if (stepIndex.value !== 0 || taskId.value === null) {
    return
  }
  const generation = taskGeneration
  const forTask = taskId.value
  const listed = await ensureFileList()
  if (generation !== taskGeneration) {
    return
  }
  if (!listed) {
    // 清单未知：不构造模板（否则模板会被当成 B 的文件内容写回）。用户可见地说明一次。
    ElMessage.warning('服务端文件清单读取失败，编辑区暂不能载入（请稍后重试或点「保存草稿到服务端」重试）')
    return
  }
  await nextTick()
  // 定义闸：选中项里有人"定义还没到"时，先等（在途）/ 补一次（首轮失败）再算 targets。
  // 否则 `editorDefs` 为空 ⇒ 一次什么都没载入就结束（首轮失败后换任务时还永不重试）。
  if (selectedDefs.value.some((code) => defOf(code) === null) && defs.value.length === 0) {
    await ensureDefinitionsForEditors()
    if (generation !== taskGeneration) {
      return
    }
    if (selectedDefs.value.length === 0) {
      return
    }
  }
  const targets = only === undefined
    ? editorDefs.value
    : editorDefs.value.filter((def) => only.includes(def.code))
  for (const def of targets) {
    // 换任务后本次载入属于旧任务：直接丢弃，不写标记、不画字节
    if (generation !== taskGeneration) {
      return
    }
    if (editorReady(def.code) && !force) {
      continue
    }
    const grid = gridRefs[def.code]
    if (!grid) {
      continue
    }
    // 字段闸（fail-closed）：0 字段的表格会"载入成功"但没有任何列，随后写回就是空文件。
    // 直接不载入、不置位（`editorState` 缺失 ⇒ 写回闸也不放行）。
    const fieldsProblem = fieldsUnavailableReason(def.code)
    if (fieldsProblem !== null) {
      if (generation === taskGeneration) {
        ElMessage.error(`${fieldsProblem}：编辑区本次未载入（按 0 字段载入会产出空文件并覆盖服务端文件）`)
      }
      continue
    }
    try {
      if (uploadedFiles[def.code]) {
        const blob = await tasksApi.downloadFile(forTask, def.code)
        // 下载在途时换了任务：A 的字节不得画进 B 的面板（I3）
        if (generation !== taskGeneration) {
          return
        }
        const { missingHeaders } = await grid.loadFromXlsx(blob, def.fields)
        if (missingHeaders.length > 0) {
          ElMessage.warning(t('views.import.msg.missingHeaders', { code: def.code, headers: missingHeaders.join(', ') }))
        }
        if (generation !== taskGeneration) {
          return
        }
        editorState.set(def.code, { generation, source: 'file' })
      } else {
        // sheetName 是写进 xlsx 的工作表名（数据面，不随 UI 语言变）
        const json = await buildTemplateWorkbookJson(def.fields, { defCode: def.code, sheetName: '数据', sample: false })
        if (generation !== taskGeneration) {
          return
        }
        await grid.loadFromJson(json, def.fields)
        if (generation !== taskGeneration) {
          return
        }
        editorState.set(def.code, { generation, source: 'template' })
      }
      // 表格里现在是服务端文件（或模板）的字节：脏标记随载入清零
      dirtyDefs.delete(def.code)
      // 交互专项：程序化载入（新文件/重载）⇒ 旧预览立即失效（硬闸仍是 previewId）
      aiStore.onSheetReloaded(def.code)
    } catch (error) {
      if (generation === taskGeneration) {
        ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.editorLoadFailed', { code: def.code }))
      }
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
 * <p>返回真实结局而不是静默 void：载入失败时 `editorState` 不会置位，调用方（`sheet.current`）
 * 必须能把失败如实说出来，而不是交出一张空表让工具读成「0 行」。
 */
async function ensureEditorLoaded(code: string): Promise<{ ok: boolean; error: string }> {
  if (editorReady(code)) {
    return { ok: true, error: '' }
  }
  await loadEditors()
  if (editorReady(code)) {
    return { ok: true, error: '' }
  }
  const hint = definitionsUnavailableHint()
  return {
    ok: false,
    error: `编辑区表格未能载入${hint === null ? '' : `（${hint}）`}（请重试，或重新打开该配置的编辑标签页）`
  }
}

function onGridChanged(defCode: string | undefined, origin: 'user' | 'ai' = 'user'): void {
  // 批次 1C：编辑区与「服务端文件」是两回事 —— 记下脏区，重载/换任务/离页前才知道要结算
  if (defCode) {
    dirtyDefs.add(defCode)
  }
  // 契约联动：导入编辑区的内容改动同步进 AI 上下文（表格类改动只递增版本，避免刷爆动作记录）
  // 来源按实际写入者记（`origin='ai'` = AI 预览应用后的落笔）：AI 的写入不得冒充用户动作。
  workspace.touch(
    origin === 'ai'
      ? 'AI 已把改动写入导入编辑区（尚未写回服务端文件）'
      : '导入编辑区内容已修改（尚未写回服务端文件）',
    origin === 'ai' ? 'AI' : 'UI',
    // 面板 chip 的结构化展示面（2026-10-30）：现役写点给 display，只有旧记录才落兜底词条
    { code: origin === 'ai' ? 'ai.workspace.importSheetEditAi' : 'ai.workspace.importSheetEditUser' }
  )
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
  const generation = taskGeneration
  const forTask = taskId.value
  const written: string[] = []
  const skipped: string[] = []
  busy.value = 'flush'
  let ok = true
  let failureReason: string | undefined
  try {
    // 写回闸①：文件清单没落定时不得写回 —— 此时编辑区可能什么都没有，或（更糟）是
    // "清单未知期间"留下的模板；PUT 上去就是拿空工作簿覆盖服务端真文件。先补读一次。
    if (!filesOk.value) {
      const listed = await ensureFileList()
      if (generation !== taskGeneration || forTask !== taskId.value) {
        return { ok: false, savedDefs: [], reason: '任务已切换，本次写回中止（未把上一个任务的内容写到新任务上）' }
      }
      if (!listed) {
        const reason = `任务 #${forTask} 的服务端文件清单读取失败（GET /files），本次未写回任何文件`
          + '（否则会用空模板覆盖服务端已有文件）'
        ElMessage.error(reason)
        return { ok: false, savedDefs: [], reason }
      }
      // 清单刚补到：编辑区必须按文件重来（此前若按"无文件"构造过模板，那内容不是服务端文件）
      await loadEditors(true)
      if (generation !== taskGeneration || forTask !== taskId.value) {
        return { ok: false, savedDefs: [], reason: '任务已切换，本次写回中止（未把上一个任务的内容写到新任务上）' }
      }
    }
    await persistSelection(forTask)
    for (const def of editorDefs.value) {
      // 写回闸②：换任务期间（A→B）一律停手 —— 字节属于 A，任务号已经是 B
      if (generation !== taskGeneration || forTask !== taskId.value) {
        ok = false
        failureReason = '任务已切换，本次写回中止（未把上一个任务的内容写到新任务上）'
        break
      }
      const grid = gridRefs[def.code]
      if (!grid) {
        continue
      }
      // 字段闸（fail-closed）：0 字段的表写回去就是空文件（覆盖用户上传的真实文件）；
      // 这里挡住，并如实说明（不能落进下面"编辑区未载入"的统一话术）。
      const fieldsProblem = fieldsUnavailableReason(def.code)
      if (fieldsProblem !== null) {
        ok = false
        const fieldsReason = `${def.code}：${fieldsProblem}（本次未写回，避免空文件覆盖服务端文件）`
        ElMessage.warning(fieldsReason)
        failureReason = failureReason ? `${failureReason}；${fieldsReason}` : fieldsReason
        continue
      }
      // 写回闸③：内容来源必须与"服务端文件清单"一致才可写：
      // - `file`：表格里就是服务端文件字节，写回不会改内容；
      // - `template`：只有清单确认过"没有该文件"时才是合法的新建导入，否则一律不写。
      const state = editorState.get(def.code)
      const current = state !== undefined && state.generation === generation ? state : null
      const writable = current !== null
        && (current.source === 'file' || uploadedFiles[def.code] === undefined)
      if (!writable) {
        skipped.push(def.code)
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
      // 序列化期间可能已经换了任务：再确认一次才真正发 PUT
      if (generation !== taskGeneration || forTask !== taskId.value) {
        ok = false
        failureReason = '任务已切换，本次写回中止（未把上一个任务的内容写到新任务上）'
        break
      }
      await tasksApi.saveFile(forTask, def.code, await blob.arrayBuffer())
      savedDefs.add(def.code)
      // 写回成功 = 该配置项与服务端文件重新一致（脏标记清零；否则换任务/离页还会再提醒一次）
      dirtyDefs.delete(def.code)
      written.push(def.code)
    }
    if (skipped.length > 0) {
      ok = false
      const skippedReason = `${skipped.join('、')} 的编辑区未载入（文件读取失败或载入未完成），本次未写回`
        + ' —— 请先重试打开对应编辑区，再推进'
      ElMessage.warning(skippedReason)
      failureReason = failureReason ? `${failureReason}；${skippedReason}` : skippedReason
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

/**
 * 把**脏**编辑区写回服务端文件（"重载/换任务前先结算"的唯一实现，批次 1C）。
 *
 * <p>与 {@link flushEditors} 共用同一套写回闸（清单未知不写、内容来源不明不写、本地校验失败不写），
 * 但**只碰脏区**，也不弹「草稿已保存」的成功提示 —— 它是切换/重载前替用户兜底，不是一次显式保存。
 *
 * @param forTask 字节归属的任务号（调用方在改绑前捕获，不能读实时 `taskId`）
 * @returns `ok:false` 时 `reason` 逐项点名没写回的配置项（可直接给用户看；调用方据此决定"确认放弃"）
 */
async function flushDirtyDefs(
  forTask: number,
  codes: readonly string[],
  generation: number
): Promise<{ ok: boolean; reason?: string }> {
  if (codes.length === 0) {
    return { ok: true }
  }
  busy.value = 'flush'
  try {
    if (!filesOk.value) {
      const listed = await ensureFileList()
      if (generation !== taskGeneration || forTask !== taskId.value) {
        return { ok: false, reason: t('views.import.msg.writeBackAbortedOnSwitch') }
      }
      if (!listed) {
        return { ok: false, reason: t('views.import.msg.fileListUnreadable', { taskId: forTask }) }
      }
    }
    const failed: string[] = []
    for (const code of codes) {
      if (generation !== taskGeneration || forTask !== taskId.value) {
        return { ok: false, reason: t('views.import.msg.writeBackAbortedOnSwitch') }
      }
      const grid = gridRefs[code]
      if (!grid) {
        failed.push(t('views.import.msg.editorUnmounted', { code }))
        continue
      }
      // 字段闸（fail-closed）：0 字段的表写回去就是空文件（覆盖服务端真实文件）
      const fieldsProblem = fieldsUnavailableReason(code)
      if (fieldsProblem !== null) {
        failed.push(`${code}：${fieldsProblem}（未写回，避免空文件覆盖服务端文件）`)
        continue
      }
      const state = editorState.get(code)
      const current = state !== undefined && state.generation === generation ? state : null
      const writable = current !== null
        && (current.source === 'file' || uploadedFiles[code] === undefined)
      if (!writable) {
        failed.push(t('views.import.msg.editorSourceUnknown', { code }))
        continue
      }
      const issues = grid.validateLocal()
      if (issues.length > 0) {
        const first = issues[0]
        failed.push(t('views.import.msg.localIssuesDetail', {
          code,
          count: issues.length,
          row: first?.rowIndex ?? '',
          reason: first?.reason ?? ''
        }))
        continue
      }
      try {
        const blob = await grid.toXlsxBlob()
        if (generation !== taskGeneration || forTask !== taskId.value) {
          return { ok: false, reason: t('views.import.msg.writeBackAbortedOnSwitch') }
        }
        await tasksApi.saveFile(forTask, code, await blob.arrayBuffer())
        savedDefs.add(code)
        dirtyDefs.delete(code)
      } catch (error) {
        // 单个配置项写失败不拖累其它配置项：能保住几项是几项
        failed.push(t('views.import.msg.writeBackFailedFor', {
          code,
          reason: error instanceof Error ? error.message : String(error)
        }))
      }
    }
    // 尽量写回后仍失败的那些：把「丢数据」的决定交回用户（reason 逐项点名，不统一甩锅）
    return failed.length === 0 ? { ok: true } : { ok: false, reason: failed.join('；') }
  } catch (error) {
    return { ok: false, reason: error instanceof Error ? error.message : String(error) }
  } finally {
    // 与 N1 同一条纪律：只有代次没变时才由本层复位（换任务后由 resetTaskScopedState 清）
    if (generation === taskGeneration) {
      busy.value = null
    }
  }
}

/**
 * 脏区结算（批次 1C 的**统一实现**）：先写回，写不回才让用户明确确认放弃。
 *
 * <p>为什么先写回：三条路径（换任务 / 取消勾选 / 离开页面）都会让未保存的编辑失去载体
 * （复位清屏、标签页卸载、组件销毁），写回是无损的那条路；只有写回真的失败（清单读不到 /
 * 本地校验不过 / PUT 失败 / 内容来源不明）才把"丢数据"的决定交回用户。
 *
 * @param message 由 `reason` 生成正文（各路径文案不同，但都按 locales 完整模板插值）
 * @returns true = 已写回或用户确认放弃；false = 用户取消（调用方必须维持原状：勾选/导航/任务都不动）
 */
async function settleDirtyOrConfirm(
  forTask: number,
  codes: readonly string[],
  title: string,
  message: (reason: string) => string
): Promise<boolean> {
  if (codes.length === 0) {
    return true
  }
  const generation = taskGeneration
  const outcome = await flushDirtyDefs(forTask, codes, generation)
  if (outcome.ok) {
    return true
  }
  if (generation !== taskGeneration) {
    return false
  }
  return await confirmDiscardEdits(title, message(outcome.reason ?? t('views.import.msg.unknownReason')))
}

/**
 * 换任务前的脏区结算（走 {@link settleDirtyOrConfirm}）。
 *
 * @returns false = 用户选择留在原任务（调用方必须中止改绑，连地址栏一起还原）
 */
async function settleDirtyBeforeSwitch(from: number): Promise<boolean> {
  const codes = [...dirtyDefs]
  return await settleDirtyOrConfirm(
    from,
    codes,
    t('views.import.dialog.unsavedSwitchTitle'),
    (reason) => t('views.import.msg.unsavedSwitchConfirm', {
      codes: codes.join('、'),
      taskId: from,
      reason
    })
  )
}

/**
 * 取消勾选前的脏区结算（走 {@link settleDirtyOrConfirm}）。
 *
 * <p>为什么必须在这里写回：取消勾选会让标签页卸载、表格引擎销毁 —— 那时连 `toXlsxBlob()`
 * 都取不到，编辑就真的没了。所以调用方必须**先把勾选回滚**（保持编辑区挂载）再调本函数。
 *
 * @returns true = 已写回，或用户确认放弃（调用方清脏后照常应用新勾选）；false = 用户取消（保持原勾选）
 */
async function settleDirtyBeforeDeselect(codes: readonly string[]): Promise<boolean> {
  const forTask = taskId.value
  if (forTask === null) {
    return true
  }
  return await settleDirtyOrConfirm(
    forTask,
    codes,
    t('views.import.dialog.unsavedDeselectTitle'),
    (reason) => t('views.import.msg.unsavedDeselectConfirm', {
      codes: codes.join('、'),
      reason
    })
  )
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

/**
 * 推进到目标步骤（界面「下一步」与 AI `confirm_step` 共用）。
 *
 * @param guard 存活判据（AI 通道的预算到点 / 停止）：写回服务端文件是 await，之后若已作废
 *   ⇒ 不切步骤、不写工作区（"超时/已停止"不得被迟到的步骤推进推翻）。
 */
async function goStep(next: number, guard?: () => boolean): Promise<StepAdvanceOutcome> {
  if (next < 0 || next >= STEP_KEYS.length) {
    return { ok: false, reason: `目标步骤 ${next} 不在导入向导范围内` }
  }
  const generation = taskGeneration
  let writtenDefs: string[] = []
  if (next > stepIndex.value && stepIndex.value === 0) {
    const flushed = await flushEditors()
    // 换任务途中写回已中止：不得把这次推进算到新任务的页面上（步骤/工作区都是任务级状态）
    if (generation !== taskGeneration) {
      return { ok: false, reason: '任务已切换，本次未推进步骤' }
    }
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
  if (guard?.() === true) {
    return {
      ok: false,
      reason: '前端工具预算已到点或本轮已停止：步骤未推进（工作区与页面步骤均未改动）',
      savedDefs: writtenDefs
    }
  }
  stepIndex.value = next
  workspace.setStep(STEP_KEYS[next] ?? null)
  // 批3③a D9（D7 同款语义最小接入）/ 批3③b D15（对齐导出侧）：服务端步骤同步失败**不阻断**
  // 推进，但警告必须可见（单点 warning 在本函数弹）且**带进 AI 回灌** —— 原实现只弹
  // ElMessage、回灌文本仍是干净成功文案（与导出侧不对称，M6 承接项）。
  const synced = await syncStepToBackend(generation, taskId.value)
  let syncWarning: string | undefined
  if (!synced.ok) {
    syncWarning = '步骤已推进但服务端步骤同步失败，下次切步会重试'
    ElMessage.warning(syncWarning)
  }
  if (generation === taskGeneration) {
    await ensureStepLoaded(next)
  }
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

/**
 * `startJob` 的结局。
 *
 * 判据只有一个：**作业有没有真的被建出来**。只要建出来了，回执就必须以"已发生的事实"叙述
 * （`job` 非空 ⇒ 调用方回成功/信息形，绝不回"未启动"），因为"未启动"会诱发模型重发；
 * `detached`/`attachError` 只说明**本页有没有跟踪它**。
 */
interface JobStartOutcome {
  job: Job | null
  /** 发起时的任务/代次已变：作业确实建在**原任务**上，但不得挂到当前任务的面板/跟踪面 */
  detached: boolean
  /** 发起时绑定的任务号（回执里点明作业归属，换任务后仍能说清） */
  taskId: number | null
  /** 作业已建但本页挂接（问题清单/watcher）失败：仍然不是"未启动" */
  attachError?: string
  error?: string
}

/**
 * 创建并挂接作业。
 *
 * **代次纪律（N2）**：任务号与代次在**入口捕获**。`createJob` 在途时换任务的话，返回的作业属于
 * 原任务 —— 此时不写 `checkJob/importJob/publishJob`、不拉 issues、不登记 watcher（reset 已经先跑过，
 * 那时登记的 watcher 不会被回收，会一直替旧作业打后端）。回执由调用方按 `detached` 如实说明。
 *
 * **真实性纪律**：`createJob` 一旦成功，后续任何失败（含 `attachJob`/`beginWatchJob` 抛错、或
 * 挂接期间换任务）都**不得**退化成 `job: null` / `detached: false` —— 那会让回执说"未启动"
 * 而作业其实已经在后端跑（同一类"说没发生、其实发生了"的失真）。
 */
async function startJob(kind: 'check' | 'import' | 'publish'): Promise<JobStartOutcome> {
  if (taskId.value === null) {
    ElMessage.warning(t('views.import.msg.noTaskBound'))
    return { job: null, detached: false, taskId: null }
  }
  const generation = taskGeneration
  const forTask = taskId.value
  const jobType = kind === 'check' ? 'PRECHECK' : kind === 'import' ? 'IMPORT' : 'PUBLISH'
  let created: Job | null = null
  let attachError: string | undefined
  busy.value = kind
  try {
    const job = await jobsApi.createJob(forTask, jobType)
    created = job
    if (generation !== taskGeneration) {
      return { job, detached: true, taskId: forTask }
    }
    ElMessage.success(t('views.import.msg.jobStarted', { kind: t(`views.import.kinds.${kind}`), id: job.id ?? '' }))
    try {
      await attachJob(kind, job, generation)
      if (job.id !== undefined) {
        beginWatchJob(kind, job.id, generation)
      }
    } catch (error) {
      // 作业已建、只是本页没挂上：带着错误回去，让回执说"已启动但本页未跟踪"
      attachError = error instanceof Error ? error.message : String(error)
    }
    // 挂接期间也可能换了任务：返回值必须与真实归属一致（否则回执会说"在本页已启动"）
    return { job, detached: generation !== taskGeneration, taskId: forTask, attachError }
  } catch (error) {
    if (created === null) {
      const reason = error instanceof Error ? error.message : String(error)
      if (generation === taskGeneration) {
        ElMessage.error(reason || t('views.import.msg.jobStartFailed'))
      }
      return { job: null, detached: generation !== taskGeneration, taskId: forTask, error: reason }
    }
    return {
      job: created,
      detached: generation !== taskGeneration,
      taskId: forTask,
      attachError: error instanceof Error ? error.message : String(error)
    }
  } finally {
    if (generation === taskGeneration) {
      busy.value = null
    }
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
  | { kind: 'started'; job: Job; warning?: string }
  | { kind: 'cancelled' }
  /** 确认窗口期间任务被切换：A 的确认不得落到 B（**没有**建任何作业） */
  | { kind: 'switched'; from: number; to: number | null }
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
  // **确认窗口的代次纪律（N2/③）**：模式读取（网络）与用户确认（人）是两个长 await。
  // 用户确认的是**发起时那个任务**；期间换了任务就必须中止 —— 否则会拿 A 的确认在 B 上建发布作业
  // （REPLACE 会删范围内旧行），而用户从未对 B 确认过。
  const generation = taskGeneration
  const forTask = taskId.value
  publishPending.value = true
  busy.value = 'publish'
  try {
    const id = forTask
    if (id === null) {
      const reason = t('views.import.msg.noTaskBound')
      ElMessage.warning(reason)
      return { kind: 'failed', reason }
    }
    // C-1：模式判定只认任务服务端权威值。读取失败 / settings 不可解析 ⇒ fail-closed（不建作业）。
    const resolution = await resolvePublishMode(id)
    if (generation !== taskGeneration) {
      return { kind: 'switched', from: id, to: taskId.value }
    }
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
    if (generation !== taskGeneration || forTask !== taskId.value) {
      return { kind: 'switched', from: id, to: taskId.value }
    }
    if (!confirmed) {
      return { kind: 'cancelled' }
    }
    // Minor：只有“确认通过、真的要发起”时才清理旧冲突提示；取消不得清掉既有告警。
    publishConflict.value = null
    const started = await startJob('publish')
    if (!started.job) {
      // 作业真的没建出来（createJob 失败）——这才是"未启动"
      return { kind: 'failed', reason: t('views.import.msg.jobStartFailed') }
    }
    // 作业已建：若期间换了任务/挂接失败，回执必须说"已建在任务 #N、本页未跟踪"，绝不能说失败
    const note = started.detached || started.attachError !== undefined
      ? `发布作业 #${started.job.id} 已建在任务 #${started.taskId ?? id}`
        + (started.detached ? '（页面已切换到别的任务，本页未跟踪）' : '（本页未能跟踪该作业）')
        + '；作业仍在后端继续，请勿重复发起'
      : undefined
    return { kind: 'started', job: started.job, warning: note }
  } finally {
    publishPending.value = false
    // startJob 自己会清 busy；只有我们这层仍持有 'publish'（取消/读取失败）时才复位。
    if (busy.value === 'publish') {
      busy.value = null
    }
  }
}

function beginWatchJob(
  kind: 'check' | 'import' | 'publish',
  jobId: number,
  generation: number = taskGeneration
): void {
  if (taskId.value === null || generation !== taskGeneration) {
    return
  }
  const forTask = taskId.value
  // 同 kind 重跑：先回收旧 watcher（不同 kind 互不回收，三者可并存）——原 registry.recycle 语义
  const previous = jobWatchDisposes.get(kind)
  if (previous) {
    jobWatchDisposes.delete(kind)
    previous()
  }
  const dispose = watchJob({
    taskId: forTask,
    jobId,
    // F10：跟踪器自制 dispose（终态）不经登记入口 —— 结束时同步摘柄；对已摘 kind 是幂等空操作，
    // 外部回收路径不会双 dispose。
    onDisposed: () => {
      if (jobWatchDisposes.get(kind) === dispose) {
        jobWatchDisposes.delete(kind)
      }
    },
    onProgress: (payload) => {
      if (generation !== taskGeneration) {
        return
      }
      const current = jobOf(kind)
      if (current) {
        setJob(kind, { ...current, status: 'RUNNING', progress: payload.processed, total: payload.total })
      }
    },
    onSnapshot: (job) => {
      if (generation !== taskGeneration) {
        return
      }
      setJob(kind, job)
    },
    onChannel: (state) => {
      if (generation !== taskGeneration) {
        return
      }
      setChannel(kind, state)
    },
    onFinal: async (job) => {
      if (generation !== taskGeneration) {
        return
      }
      setJob(kind, job)
      await refreshIssues(kind, jobId, generation)
      // 取问题清单也是 await：之后一律按**入口捕获**的 (generation, forTask) 继续，
      // 不得用实时 taskId 去刷新另一个任务的快照（N2）
      if (generation !== taskGeneration) {
        return
      }
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
        await refreshTaskSnapshot(generation, forTask)
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
      await refreshTaskSnapshot(generation, forTask)
    }
  })
  jobWatchDisposes.set(kind, dispose)
}

/**
 * 刷新任务快照（状态/步骤），失败时不让页面停在旧状态上。
 *
 * **代次纪律（N2）**：用入口捕获的 `(generation, forTask)`。旧作业的 `onFinal` 在换任务后回来时，
 * 实时读 `taskId.value` 会把 A 的状态写到 B 的任务位（`workspace.setTaskId(B, A 的状态)`），
 * 进而让 B 的 `isReadOnly` 判错（按钮消失/出现）。
 */
async function refreshTaskSnapshot(
  generation: number = taskGeneration,
  forTask: number | null = taskId.value
): Promise<void> {
  if (forTask === null) {
    return
  }
  try {
    const snapshot = await tasksApi.getTask(forTask)
    if (generation !== taskGeneration) {
      return
    }
    task.value = snapshot
    workspace.setTaskId(forTask, 'IMPORT')
    // 批次4 集成返修：状态单独写回（`buildContext` 顶层上报 ⇒ 后端披露面终态收窄）。
    // 与导出页同一口径：终态后刷新到的 COMPLETED/CANCELLED/FAILED 必须进工作区，
    // 否则下一轮请求仍带旧状态，模型看到"还能预检查/导入"的假入口。
    workspace.setTaskStatus(snapshot.status)
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

async function refreshIssues(
  kind: 'check' | 'import' | 'publish',
  jobId: number,
  generation: number = taskGeneration
): Promise<void> {
  try {
    const page = await jobsApi.getIssues(jobId, 0, 200)
    // 旧任务的作业问题晚到：不得写进新任务的问题清单
    if (generation !== taskGeneration) {
      return
    }
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
  const generation = taskGeneration
  const jobId = importJob.value?.id
  if (!jobId) {
    staging.value = []
    return
  }
  stagingLoading.value = true
  try {
    const rows = await jobsApi.getDiff(jobId)
    // 旧任务的暂存行晚到：不得渲染到新任务（I5）
    if (generation !== taskGeneration) {
      return
    }
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
    if (generation === taskGeneration) {
      ElMessage.error(error instanceof Error ? error.message : t('views.import.msg.stagingLoadFailed'))
    }
  } finally {
    if (generation === taskGeneration) {
      stagingLoading.value = false
    }
  }
}

async function onImportModeChange(): Promise<void> {
  if (taskId.value === null) {
    return
  }
  const requested = importMode.value
  // B：PUT 之后的工作区/本地写回必须核对任务代次 —— 请求在途时换了任务，这条模式属于旧任务，
  // 写到新任务上就是"说 A 实际给 B"（服务端已落库的是旧任务，本地却显示成新任务的模式）。
  const generation = taskGeneration
  const forTask = taskId.value
  // C-1：PUT 失败时服务端保持原值；本地 v-model 必须回滚，不能停在“未生效的模式”上。
  const previous = readImportMode(task.value ?? { settingsJson: null }) ?? 'MERGE'
  try {
    const updated = await tasksApi.setImportMode(forTask, requested)
    if (generation !== taskGeneration || taskId.value !== forTask) {
      return
    }
    if (task.value && updated !== null && typeof updated === 'object') {
      task.value = { ...task.value, ...updated }
    }
    workspace.setImportMode(requested)
    ElMessage.success(t('views.import.msg.importModeSet', {
      mode: requested === 'REPLACE' ? t('views.import.replaceMode') : t('views.import.mergeMode')
    }))
  } catch (error) {
    if (generation !== taskGeneration || taskId.value !== forTask) {
      return // 旧任务失败：不回滚当前任务的选择，也不弹错
    }
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
  'import.syncJob',
  // 任务详情就绪探针（只读）：契约层导航/幂等判据据此等"选中集/模式/步骤已恢复"
  'import.taskDetailReady'
]

/** AI 可视可操作专项：上传区（区域围栏的 root；`data-ai-ref` 挂在其中的三个按钮上）。 */
const aiUploadRowRef = ref<HTMLElement | null>(null)
/** visop 批 B：配置项选择区 / 导入模式区 / 编辑区标签页（三个新增区域的 root）。 */
const aiSelectDefsRef = ref<HTMLElement | null>(null)
const aiModeRowRef = ref<HTMLElement | null>(null)
const aiGridRef = ref<HTMLElement | null>(null)

function registerHandlers(): void {
  // 任务详情就绪探针（只读，返回布尔）：契约层在导航/幂等早退处等它变 true 才放行后续动作。
  registerPageHandler('import.taskDetailReady', () => taskDetailReady.value)
  // 批 D · D3（问题 6）：AI 触发 start_precheck / start_import 后把作业绑进本页的跟踪面。
  // 与既有 resumeJobs() 同一条恢复链（刷新/回页也走它）：三种作业各归各位（check/import/publish）；
  // taskId 对不上就不动本页（别的任务的作业显示在这里 = 说 A 实际给 B）。
  // 批次 5B：`start_publish` 也走本能力（发布作业落同一作业面板），失败分支返回结构化
  // `{ ok:false, reason }` —— store 侧据此落可见回执（字符串一律按成功解读）。
  registerPageHandler('import.syncJob', async (payload) => {
    const jobId = Number(payload.jobId)
    const jobTaskId = Number(payload.taskId)
    if (!Number.isInteger(jobId) || jobId <= 0) {
      return { ok: false, reason: '未同步：作业号无效' }
    }
    if (taskId.value === null || taskId.value !== jobTaskId) {
      return { ok: false, reason: `未同步：该作业属于任务 #${jobTaskId}，当前导入向导绑定的是任务 #${taskId.value ?? '（无）'}` }
    }
    await resumeJobs()
    const bound = [checkJob.value, importJob.value, publishJob.value].find((job) => job?.id === jobId)
    if (!bound) {
      return { ok: false, reason: `已刷新导入作业列表（未在本任务的作业中找到 #${jobId}）` }
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
    // 定义不可用（含补请求冷却）⇒ 先给**成因**：此时编辑区根本没被建立，
    // 若只说"表格未挂载"，模型只会重复同一调用并继续撞冷却。
    const definitionProblem = definitionsUnavailableHint()
    if (definitionProblem !== null) {
      return {
        ok: false,
        reason: `${definitionProblem}：${code} 的编辑区无法建立（未按 0 字段载入）。`
          + '请稍后重试；若持续失败，请让用户刷新页面重新加载配置定义。'
      }
    }
    const grid = gridRefs[code]
    if (!grid || typeof grid.getSheetAdapter !== 'function') {
      return { ok: false, reason: `${code} 的编辑区表格未挂载（请重开该标签页后重试）。` }
    }
    // 字段闸（fail-closed）：0 字段 = 没有任何可映射的列，交出适配器等于让工具在空表上
    // 读写（写进不存在的列 / 读成 0 行）。先如实拒绝。
    const fieldsProblem = fieldsUnavailableReason(code)
    if (fieldsProblem !== null) {
      return { ok: false, reason: `${fieldsProblem}：表格列映射不可用，本次不交出表格能力。请确认定义列表已加载后重试。` }
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
        // 表内版本由 `resolveSheetContext` 从适配器取；不得回报全局 `workspace.dataVersion`
        //（那是工作区计数，与"这张表是否被改过"无关，会把无关 touch 误判成表被改）。
        fields: defOf(code)?.fields ?? [],
        adapter: grid.getSheetAdapter()
      }
    } catch (error) {
      return { ok: false, reason: `${code} 的编辑区表格未就绪：${error instanceof Error ? error.message : String(error)}` }
    }
  })

  // 补丁②：AI 工作区动作 confirm_step 的页面实现（推进到下一步；与用户点「下一步」同路径）
  registerPageHandler('import.nextStep', async (payload, lifetime) => {
    // 施工卡 §S3e：只读态拦在 goStep 之前 —— 已结束任务不得借 AI 通道推进步骤（零写）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，不能推进步骤'
    }
    // 预算到点 / 本轮已停止 ⇒ 零写（交回执为确定性失败，不返回字符串）
    if (uiAbandoned(lifetime)) {
      return uiAbandonedReply('推进步骤')
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
    // guard：写回服务端文件是 await；期间预算到点/停止 ⇒ goStep 停在原步、不写工作区
    const advanced = await goStep(index, uiAbandonGuard(lifetime))
    if (!advanced.ok) {
      // 复审 Minor-4：作废不是"数据问题" —— 不追加"修正数据/保存草稿"这类错误指引。
      // 复审 M7：`flushEditors` 可能已把若干配置写回服务端文件 —— 这部分事实必须带出。
      if (uiAbandoned(lifetime)) {
        return uiStepAbandonedReply(advanced.reason, advanced.savedDefs ?? [])
      }
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

  registerPageHandler('import.selectDefs', async (payload, lifetime) => {
    // 施工卡 §S3e：只读态不改变所选配置项（含收敛工作区与重载编辑区一并短路）
    if (isReadOnly.value) {
      return '任务已结束，仅供查看，未变更所选配置项'
    }
    // 预算到点 / 停止 ⇒ 选中集、工作区、编辑区一律不动
    if (uiAbandoned(lifetime)) {
      return uiAbandonedReply('变更所选配置项')
    }
    const codes = normalizeDefinitionCodes(Array.isArray(payload.defCodes) ? payload.defCodes : [])
    // 编码必须存在于**当前**定义列表（空清单 = 不知道可用编码，同样拒绝）：非法码不写
    // 选中集、不收敛工作区、不落库
    const problem = selectDefsProblem(codes)
    if (problem !== null) {
      return { ok: false, reason: problem }
    }
    selectedDefs.value = payload.mode === 'ADD' ? [...new Set([...selectedDefs.value, ...codes])] : codes
    // issue #3：AI handler 分支同样要显式收敛（来源记 'AI'；watch 随后比较已相等 ⇒ 短路）
    syncSelectedDefsToWorkspace('AI')
    // B：下面的写回（标签页 / 编辑区载入 / 回执）要等 watch 与落定 Promise —— 期间可能换任务，
    // 换过之后旧任务的选中集不得再驱动新任务的编辑区（代次判据与 uploadOne 同源）。
    const generation = taskGeneration
    // 批次 1C：取消勾选含脏项时 watch 会先回滚再结算（写回或确认放弃）。这里等它落定再回灌 ——
    // 否则用户刚点了"不丢弃"，回执却已经说"已选择配置项：B"（说 A 实际给 B）。
    await nextTick()
    if (selectionSettle !== null) {
      await selectionSettle
    }
    if (generation !== taskGeneration) {
      return '任务已切换：本次所选配置项属于原任务，未应用到当前页面的编辑区'
    }
    if (uiAbandoned(lifetime)) {
      // 已作废：选中集与工作区已在作废前同步（那一步发生在超时/停止之前），
      // 但**编辑区切换与载入**是作废之后才要写的页面态 —— 一律不做。
      return {
        ok: false,
        reason: '前端工具预算已到点或本轮已停止：本次未切换/载入编辑区（工作区与选中集以作废前状态为准）'
      }
    }
    // 当前标签页仍被选中就别跳回第一张表；新增的编辑区由 loadEditors() 补齐，
    // 已载入的**不重载**（整页强制重载会把它们未保存的编辑换成服务端旧文件）
    if (!selectedDefs.value.includes(activeEditorTab.value)) {
      activeEditorTab.value = selectedDefs.value[0] ?? ''
    }
    void loadEditors()
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
      return { ok: false, reason: '任务已结束，仅供查看，未启动预检查作业' }
    }
    const started = await startJob('check')
    if (!started.job) {
      return { ok: false, reason: started.error ?? '预检查未启动' }
    }
    // 作业已建但本页没跟踪（切任务/挂接失败）⇒ 成功形回执 + 明确的"请勿重复发起"
    const note = jobStartedNote('预检查', started)
    if (note) {
      return note
    }
    return `预检查作业 #${started.job.id} 已启动`
  })

  registerPageHandler('import.startImport', async () => {
    // 施工卡 §S3e：只读态不得 createJob
    if (isReadOnly.value) {
      return { ok: false, reason: '任务已结束，仅供查看，未启动导入作业' }
    }
    const started = await startJob('import')
    if (!started.job) {
      return { ok: false, reason: started.error ?? '导入未启动' }
    }
    const note = jobStartedNote('导入', started)
    if (note) {
      return note
    }
    return `导入作业 #${started.job.id} 已启动`
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
    if (outcome.kind === 'switched') {
      // 没有建任何作业：如实说明"没在哪个任务上发布"，并要求重新确认（不是盲重重发）
      return {
        ok: false,
        reason: `发布确认期间任务已切换（确认的是任务 #${outcome.from}，当前是任务 #${outcome.to ?? '（无）'}）：`
          + '本次未发起任何发布作业。如仍需发布，请在当前任务上重新发起并再次确认'
      }
    }
    if (outcome.kind === 'failed') {
      return { ok: false, reason: outcome.reason }
    }
    if (outcome.warning) {
      return { ok: true, result: outcome.warning }
    }
    if (outcome.job.id === undefined) {
      return '发布作业已启动（服务端未返回作业号）；发布结果按异步形态给出（作业终态 + 问题清单）'
    }
    return `发布作业 #${outcome.job.id} 已启动；发布结果按异步形态给出（作业终态 + 问题清单）`
  })
}

/**
 * "作业已建但本页没跟踪"的回执（成功/信息形）。
 *
 * 为什么不能是 `{ok:false}`：`interpretHandlerOutcome` 把 `{ok:false}` 判成**确定性失败**，
 * 模型会照"失败"重发 —— 而作业其实已经在后端跑（发布作业带 REPLACE 语义会真删行）。
 * 这里的语义是"事情已发生，只是不在这个页面上跟踪"，所以回成功形 + 明确的"请勿重复发起"。
 */
function jobStartedNote(label: string, started: JobStartOutcome): { ok: true; result: string } | null {
  if (!started.job || (!started.detached && started.attachError === undefined)) {
    return null
  }
  const where = started.taskId === null ? '原任务' : `任务 #${started.taskId}`
  const why = started.detached ? '页面已切换到别的任务，本页未跟踪' : `本页未能跟踪（${started.attachError ?? '未知原因'}）`
  return {
    ok: true,
    result: `${label}作业 #${started.job.id ?? ''} 已建在${where}（${why}）。作业仍在后端继续，请勿重复发起。`
  }
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

// ── 批次 1C：离页保护（导航守卫） ──────────────────────────────────────────────

/**
 * 离开本页前结算脏区（`onBeforeRouteLeave`：vue-router 既有机制，无新依赖）。
 *
 * <p>为什么不能只靠 `onBeforeUnmount`：卸载钩子跑在**导航已经成立之后** —— 那时只能补一条
 * toast，用户的数据已经没了，也拦不住导航。守卫在导航提交前执行，可以真写回、也可以中止导航：
 * - 写回成功（或用户确认放弃）⇒ 放行；
 * - 用户取消 ⇒ 返回 false 中止导航，页面与编辑区原样保留（脏标记不清）。
 *
 * <p>与换任务 / 取消勾选共用同一套结算（{@link settleDirtyOrConfirm} → `flushDirtyDefs`），
 * 只换文案；同路由 query 变化换任务不会触发本守卫（那条路走 `settleDirtyBeforeSwitch`），
 * 不会双弹框。
 */
onBeforeRouteLeave(async () => {
  const forTask = taskId.value
  if (forTask === null || dirtyDefs.size === 0) {
    return true
  }
  const codes = [...dirtyDefs]
  return await settleDirtyOrConfirm(
    forTask,
    codes,
    t('views.import.dialog.unsavedLeaveTitle'),
    (reason) => t('views.import.msg.unsavedLeaveConfirm', {
      codes: codes.join('、'),
      reason
    })
  )
})

/**
 * 绑定任务（**唯一**的改绑入口）：结算脏区 → 换代次 → 复位页面态 → 同步工作区 → 拉详情/清单/作业。
 *
 * 路由换任务、新建任务两条入口都走这里 —— 之前 `createAndBindTask` 自己赋值 `taskId` 再
 * `router.replace`，而路由 watch 见到 `id === taskId.value` 直接 return ⇒ 既不复位也不换代次，
 * 旧任务的 `uploadedFiles` / `filesOk` / `importMode` 会残留到新任务（新任务里勾同名配置时
 * 会去下载根本不存在的文件，编辑区永远载不进来）。
 *
 * @returns false = 用户在有未保存编辑时选择「留在原任务」（调用方不得改地址栏/继续建任务流程）
 */
async function bindTask(id: number): Promise<boolean> {
  // 批次 1C：复位会清屏并换文件清单 ⇒ 先把 A 的未保存编辑写回 A（写不回才问用户是否放弃）。
  // 必须放在 resetTaskScopedState 之前：那之后 gridRefs 已被清、字节也无从取出。
  const from = taskId.value
  if (from !== null && from !== id && !(await settleDirtyBeforeSwitch(from))) {
    return false
  }
  // 交互专项：换任务 ⇒ 旧任务的表格预览一律失效（不允许拿旧计划改新任务的数据）
  aiStore.onSheetPageLeft()
  // 显式换任务 = 新的重试意图：允许重新尝试定义列表（否则冷却期会把新任务的编辑区也挡掉）
  resetDefinitionsRetryCooldown()
  // 换任务 = 新一代：旧任务的载入态/文件清单/作业跟踪一律作废（否则 A 的字节会写进 B）
  resetTaskScopedState()
  taskId.value = id
  workspace.setTaskId(id, 'IMPORT', null)
  await loadTask()
  return true
}

async function createAndBindTask(): Promise<void> {
  creatingTask.value = true
  try {
    const created = await taskStore.createTask('IMPORT', t('views.tasks.autoName', {
      type: t('views.tasks.importType'),
      // 2026-10-30 结构化批：自动名的时间戳跟随当前 locale（原写死 zh-CN，en 界面出中文格式）
      time: new Date().toLocaleString(locale.value)
    }))
    if (!created || created.id === undefined) {
      ElMessage.error(taskStore.actionError ?? t('views.import.msg.taskCreateFailed'))
      return
    }
    // 未保存编辑结算被取消 ⇒ 不切任务、不改地址栏（新任务已建出，可在任务中心打开）
    if (!(await bindTask(created.id))) {
      ElMessage.info(t('views.import.msg.taskCreated', { id: created.id }))
      return
    }
    await router.replace({ name: 'import-wizard', query: { taskId: String(created.id) } })
    ElMessage.success(t('views.import.msg.taskCreated', { id: created.id }))
  } finally {
    creatingTask.value = false
  }
}

// 勾选变化：先收敛进工作区（issue #3，同步调用放在首行，避免被编辑器加载耗时挤后），再补齐编辑区。
//
// 批次 1C 两条纪律：
// ① **不再整页强制重载** —— 勾选 B 时把 A 的编辑区也用服务端旧文件重画一遍，就是把 A 未保存的
//    编辑静默丢掉（审查 P1-1）。`loadEditors()` 按 `editorReady` 短路，只补新出现的配置项。
// ② **取消勾选含脏项 ⇒ 先写回再卸载** —— 标签页卸载后表格引擎销毁，连 `toXlsxBlob()` 都取不到；
//    所以先同步回滚勾选（DOM 尚未更新 ⇒ 编辑区仍挂载），结算完再决定应用新勾选还是保持原状。
//
// 重入：回滚/复原都会再次触发本 watch（`selectionGuardInFlight` 期间的中间态只同步工作区），
// 不会二次弹框、也不会形成循环。手动勾选（DefSelector v-model）与 AI `import.selectDefs`
// 都只改 `selectedDefs`，因此走的是同一条保护；`selectionSettle` 让 AI 回执等结算落定
// （否则会出现"说已勾选、其实用户刚取消"的失真回执）。
let selectionGuardInFlight = false
/** 在途的勾选结算（AI handler 在回灌前 await 它）。 */
let selectionSettle: Promise<void> | null = null

/**
 * 勾选变化后的视图收口：激活标签页仍被选中就保留（换回第一页会打断正在编辑的那张表），
 * 否则切到第一个；再补齐未载入的编辑区（`loadEditors()` 只补未载入的，不整页重载）。
 */
async function syncEditorsWithSelection(codes: readonly string[]): Promise<void> {
  if (!codes.includes(activeEditorTab.value)) {
    activeEditorTab.value = codes[0] ?? ''
  }
  await loadEditors()
}

watch(selectedDefs, async (next, previous) => {
  if (selectionGuardInFlight) {
    // 守卫自造的中间态（回滚到 previous / 结算后应用 next）：只收敛工作区，不再走一遍守卫
    syncSelectedDefsToWorkspace()
    return
  }
  syncSelectedDefsToWorkspace()
  const previousCodes = previous ?? []
  const removedDirty = previousCodes.filter((code) => !next.includes(code) && dirtyDefs.has(code))
  if (removedDirty.length === 0) {
    await syncEditorsWithSelection(next)
    return
  }
  if (selectionSettle !== null) {
    // 已有一次结算在途（用户在确认框未答时又改了勾选）：不叠加守卫，等前一次落定
    return
  }
  // 先回滚勾选：同步赋值 ⇒ 渲染任务拿到的是 previous，被取消的标签页不会卸载（表格仍可用，
  // 才序列化得出要写回的字节）
  selectedDefs.value = [...previousCodes]
  selectionGuardInFlight = true
  const settle = (async () => {
    if (!(await settleDirtyBeforeDeselect(removedDirty))) {
      // 用户取消：保持 previous、脏标记原样保留（编辑内容一行都没动）
      return
    }
    // 已写回，或用户确认放弃 ⇒ 清脏并应用用户真正想要的勾选
    removedDirty.forEach((code) => dirtyDefs.delete(code))
    selectedDefs.value = [...next]
    // 不依赖「复原赋值触发的下一次 watch」：它可能被守卫吞掉（调度先后不确定），这里直接收口
    await syncEditorsWithSelection(next)
  })()
  selectionSettle = settle
  try {
    await settle
  } finally {
    if (selectionSettle === settle) {
      selectionSettle = null
    }
    selectionGuardInFlight = false
  }
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
    const from = taskId.value
    if (await bindTask(id)) {
      return
    }
    // 用户选择「留在原任务」：地址栏已指向 B，必须还原 —— 否则刷新/复制链接就会跑到 B
    await router.replace({
      name: 'import-wizard',
      query: from === null ? {} : { taskId: String(from) }
    })
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
