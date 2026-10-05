<template>
  <div class="space-y-4">
    <!-- 步骤1：选择配置 -->
    <section v-if="taskStore.currentStep === 'SELECT_CONFIG'" class="wizard-card">
      <div class="wizard-card__title">选择要导出的配置项（可多选）</div>
      <div class="text-xs text-gray-400 mb-3">配置项按层级分组；选择后进入下一步设置查询条件。</div>
      <div v-for="group in groups" :key="group.level" class="mb-4">
        <div class="flex items-center gap-2 mb-2">
          <el-tag size="small" :type="levelType(group.level)" effect="dark">{{ levelText(group.level) }}</el-tag>
          <span class="text-xs text-gray-400">{{ group.items.length }} 个配置项</span>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2">
          <div v-for="c in group.items" :key="c.code"
            class="config-chip" :class="{ selected: selected.includes(c.code) }" @click="toggle(c.code)">
            <el-checkbox :model-value="selected.includes(c.code)" @click.stop />
            <div class="min-w-0 flex-1">
              <div class="text-sm font-medium text-gray-800 truncate">{{ c.name }}</div>
              <div class="text-xs text-gray-400 font-mono truncate">{{ c.code }}</div>
            </div>
            <div class="text-right flex-shrink-0">
              <div class="text-xs text-gray-500">{{ c.rowCount }} 行</div>
              <div class="text-xs text-gray-400">{{ c.fieldCount }} 字段</div>
            </div>
          </div>
        </div>
      </div>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100">
        <el-button type="primary" :disabled="!selected.length" @click="saveSelection">
          下一步：设置查询条件（已选 {{ selected.length }} 项）
        </el-button>
      </div>
    </section>

    <!-- 步骤2：查询条件 -->
    <section v-else-if="taskStore.currentStep === 'SET_CONDITION'" class="wizard-card">
      <div class="wizard-card__title">设置查询条件</div>
      <div class="text-xs text-gray-400 mb-3">不同配置项结构不同，条件分别设置；不设条件导出该配置项全部已发布数据。</div>
      <el-collapse v-model="activeNames">
        <el-collapse-item v-for="code in selection" :key="code" :name="code">
          <template #title>
            <div class="flex items-center gap-2">
              <span class="font-medium">{{ configOf(code)?.name }}</span>
              <span class="text-xs text-gray-400 font-mono">{{ code }}</span>
              <el-tag size="small" type="info" effect="plain">
                {{ conditions[code]?.length || 0 }} 个条件
              </el-tag>
            </div>
          </template>
          <div class="space-y-2">
            <div v-for="(cond, i) in conditions[code]" :key="i"
              class="flex items-center gap-2 bg-gray-50 rounded p-2">
              <el-select v-model="cond.field" placeholder="字段" size="small" style="width: 150px"
                @change="cond.op = ''; cond.value = ''">
                <el-option v-for="f in fieldsOf(code)" :key="f.code" :label="f.name" :value="f.code" />
              </el-select>
              <el-select v-model="cond.op" placeholder="操作符" size="small" style="width: 110px">
                <el-option v-for="op in opsOf(cond.field, code)" :key="op" :label="opText(op)" :value="op" />
              </el-select>
              <el-select v-if="valueOptionsOf(cond.field, code).length" v-model="cond.value"
                placeholder="值" size="small" style="width: 170px" filterable>
                <el-option v-for="o in valueOptionsOf(cond.field, code)" :key="o" :label="scopeLabel(o)" :value="o" />
              </el-select>
              <el-input v-else v-model="cond.value" placeholder="比较值" size="small" style="width: 170px" />
              <el-button text type="danger" size="small" @click="removeCond(code, i)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="addCond(code)">
              <el-icon class="mr-1"><Plus /></el-icon>添加条件
            </el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100 mt-3">
        <el-button @click="saveConditions" :loading="saving">保存条件</el-button>
        <el-button type="primary" @click="startExport">开始导出</el-button>
      </div>
    </section>

    <!-- 步骤3：导出结果 -->
    <section v-else-if="taskStore.currentStep === 'EXECUTE_EXPORT'" class="wizard-card">
      <div class="wizard-card__title">导出结果</div>
      <div class="text-xs text-gray-400 mb-3">
        表格可在线编辑；勾选后可打包下载（zip）或单个下载（xlsx）。下载内容包含当前编辑结果与数据校验下拉。
      </div>
      <el-tabs v-model="activeTab" type="card">
        <el-tab-pane v-for="r in results" :key="r.configCode" :name="r.configCode">
          <template #label>
            <el-checkbox :model-value="checked.includes(r.configCode)"
              @change="toggleCheck(r.configCode)" @click.stop />
            <span class="ml-1">{{ r.configName }}（{{ r.rowCount }} 行）</span>
          </template>
          <SpreadGrid :fields="r.fields" :rows="r.rows" :sheet-name="r.configName"
            :ref="el => setGridRef(r.configCode, el)" height="480px" />
        </el-tab-pane>
      </el-tabs>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100 mt-3">
        <el-checkbox :model-value="allChecked" :indeterminate="checked.length > 0 && !allChecked"
          @change="toggleAll">全选</el-checkbox>
        <el-button type="primary" :disabled="!checked.length" :loading="downloading" @click="downloadChecked">
          下载选中（{{ checked.length }} 个{{ checked.length > 1 ? '，zip 打包' : '' }}）
        </el-button>
        <el-button :loading="downloading" @click="downloadAll">全部下载（zip）</el-button>
      </div>
    </section>

    <!-- 终态 -->
    <section v-else class="wizard-card text-center py-10">
      <el-result :icon="taskStore.task?.status === 'SUCCESS' ? 'success' : 'info'"
        :title="statusTitle" :sub-title="taskStore.task?.status === 'CANCELLED' ? '任务已取消' : ''">
        <template #extra>
          <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
        </template>
      </el-result>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { useTaskStore } from '@/stores/task'
import SpreadGrid from '@/components/SpreadGrid.vue'
import { workbookToBlob, downloadBlob, zipAndDownload, safeSheetName } from '@/utils/excel'

const taskStore = useTaskStore()
const selected = ref([])
const conditions = reactive({})
const activeNames = ref([])
const activeTab = ref('')
const results = ref([])
const checked = ref([])
const saving = ref(false)
const downloading = ref(false)
const gridRefs = {}

const groups = computed(() => {
  const byLevel = {}
  for (const c of taskStore.catalog) {
    (byLevel[c.level] ||= []).push(c)
  }
  return Object.entries(byLevel).map(([level, items]) => ({ level, items }))
})

const selection = computed(() => taskStore.selection)
const allChecked = computed(() => results.value.length > 0 && checked.value.length === results.value.length)
const statusTitle = computed(() =>
  taskStore.task?.status === 'SUCCESS' ? '导出完成' : '任务已结束')

onMounted(async () => {
  const params = taskStore.task?.params || {}
  selected.value = [...(params.selection || [])]
  const saved = params.conditions || {}
  for (const code of selected.value) {
    conditions[code] = (saved[code] || []).map((c) => ({ ...c }))
  }
  activeNames.value = selected.value.slice(0, 1)
  if (taskStore.currentStep === 'EXECUTE_EXPORT') {
    await loadResults()
  }
})

function configOf(code) {
  return taskStore.catalog.find((c) => c.code === code)
}
function fieldsOf(code) {
  return configOf(code)?.fields || []
}
function opsOf(field, code) {
  const f = fieldsOf(code).find((x) => x.code === field)
  return f ? f.operators || opList(f.dataType) : ['EQ', 'NE']
}
function opList(dataType) {
  const map = {
    TEXT: ['EQ', 'NE', 'CONTAINS', 'IN'],
    INT: ['EQ', 'NE', 'GT', 'LT', 'BETWEEN'],
    DECIMAL: ['EQ', 'NE', 'GT', 'LT', 'BETWEEN'],
    DATE: ['EQ', 'NE', 'GT', 'LT', 'BETWEEN'],
    BOOL: ['EQ', 'NE'],
    ENUM: ['EQ', 'NE'],
    SCOPE: ['EQ', 'NE']
  }
  return map[dataType] || ['EQ', 'NE']
}
function opText(op) {
  return { EQ: '等于', NE: '不等于', CONTAINS: '包含', IN: '属于', GT: '大于', LT: '小于', BETWEEN: '区间' }[op] || op
}
function valueOptionsOf(field, code) {
  const f = fieldsOf(code).find((x) => x.code === field)
  return (f && (f.dataType === 'ENUM' || f.dataType === 'SCOPE') && f.options) ? f.options : []
}
function scopeLabel(o) {
  const idx = o.indexOf('|')
  return idx > 0 ? `${o.slice(idx + 1)}（${o.slice(0, idx)}）` : o
}

function toggle(code) {
  const i = selected.value.indexOf(code)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(code)
}

async function saveSelection() {
  await api.setSelection(taskStore.task.id, selected.value)
  ElMessage.success('已保存选择')
  await taskStore.loadTask(taskStore.task.id)
  activeNames.value = selected.value.slice(0, 1)
}

function addCond(code) {
  conditions[code] ||= []
  const first = fieldsOf(code)[0]
  conditions[code].push({ field: first?.code || '', op: 'EQ', value: '' })
}
function removeCond(code, i) {
  conditions[code].splice(i, 1)
}

async function saveConditions() {
  saving.value = true
  try {
    const perConfig = {}
    for (const code of selection.value) {
      perConfig[code] = (conditions[code] || []).filter((c) => c.field && c.op && c.value !== '')
    }
    await api.setConditions(taskStore.task.id, perConfig)
    ElMessage.success('条件已保存')
    await taskStore.loadTask(taskStore.task.id)
  } finally {
    saving.value = false
  }
}

async function startExport() {
  await saveConditions()
  await api.startExport(taskStore.task.id)
  ElMessage.success('导出已开始，请稍候…')
}

async function loadResults() {
  const res = await api.exportResults(taskStore.task.id)
  results.value = res || []
  if (results.value.length) {
    activeTab.value = results.value[0].configCode
    checked.value = results.value.map((r) => r.configCode)
  }
}

function setGridRef(code, el) {
  if (el) gridRefs[code] = el
}

function toggleCheck(code) {
  const i = checked.value.indexOf(code)
  if (i >= 0) checked.value.splice(i, 1)
  else checked.value.push(code)
}
function toggleAll(v) {
  checked.value = v ? results.value.map((r) => r.configCode) : []
}

async function buildFile(r) {
  const grid = gridRefs[r.configCode]
  const spread = grid?.getSpread()
  let blob
  if (spread) {
    blob = await workbookToBlob(spread)
  } else {
    // 兜底：无实例时离线构建
    const { createHiddenWorkbook, applyColumnsToSheet, setRows } = await import('@/utils/excel')
    const hw = createHiddenWorkbook()
    try {
      const sheet = hw.workbook.getSheet(0)
      sheet.name(safeSheetName(r.configName))
      applyColumnsToSheet(sheet, r.fields)
      setRows(sheet, r.fields, r.rows)
      blob = await workbookToBlob(hw.workbook)
    } finally {
      hw.dispose()
    }
  }
  return { name: `${r.configCode}.xlsx`, blob }
}

async function downloadChecked() {
  downloading.value = true
  try {
    const files = []
    for (const r of results.value) {
      if (checked.value.includes(r.configCode)) {
        files.push(await buildFile(r))
      }
    }
    if (files.length === 1) {
      downloadBlob(files[0].blob, files[0].name)
    } else if (files.length > 1) {
      await zipAndDownload(files, `配置导出_${dateStr()}.zip`)
    }
    ElMessage.success('下载完成')
  } catch (e) {
    ElMessage.error('下载失败：' + e.message)
  } finally {
    downloading.value = false
  }
}

async function downloadAll() {
  checked.value = results.value.map((r) => r.configCode)
  await downloadChecked()
}

function dateStr() {
  return new Date().toISOString().slice(0, 10).replace(/-/g, '')
}

function levelText(l) {
  return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l
}
function levelType(l) {
  return { GLOBAL: '', REGION: 'warning', PROJECT: 'success' }[l] || 'info'
}
</script>

<style>
.wizard-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px;
}
.wizard-card__title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
  margin-bottom: 4px;
}
.config-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all .15s;
}
.config-chip:hover {
  border-color: #b3d8ff;
}
.config-chip.selected {
  border-color: #409eff;
  background: #ecf5ff;
}
</style>
