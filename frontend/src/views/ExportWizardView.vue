<template>
  <div>
    <div class="page-card">
      <el-steps :active="store.step - 1" finish-status="success" align-center>
        <el-step title="选择配置" description="多选要导出的配置项" />
        <el-step title="查询条件" description="按各配置结构设置过滤" />
        <el-step title="导出与编辑" description="进度 / SpreadJS 在线编辑 / 打包下载" />
      </el-steps>
    </div>

    <!-- 第一步：选择配置 -->
    <div class="page-card" v-if="store.step === 1">
      <div class="step-toolbar">
        <el-input v-model="keyword" placeholder="按名称/编码过滤" clearable style="width: 240px" size="small" />
        <el-select v-model="levelFilter" placeholder="层级" clearable style="width: 130px" size="small">
          <el-option label="全局" value="GLOBAL" />
          <el-option label="地区" value="REGION" />
          <el-option label="项目" value="PROJECT" />
        </el-select>
        <el-button type="primary" size="small" :disabled="!store.selectedDefs.length"
                   @click="store.goStep(2)">下一步：设置查询条件</el-button>
      </div>
      <el-table :data="filteredDefs" size="small" border
                @selection-change="onSelectionChange" ref="tableRef">
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column prop="code" label="配置编码" width="150">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="配置名称" width="170" />
        <el-table-column label="层级" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="levelTag(row.level)">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段（表头）" min-width="260">
          <template #default="{ row }">
            <span class="text-muted">{{ row.fields.map(f => f.label).join('、') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="publishedRowCount" label="生效行数" width="90" />
        <el-table-column label="依赖" width="140">
          <template #default="{ row }">
            <span class="text-muted">{{ row.dependsOn.length ? row.dependsOn.join(', ') : '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 第二步：查询条件 -->
    <div class="page-card" v-if="store.step === 2">
      <div class="step-toolbar">
        <el-button size="small" @click="store.goStep(1)">上一步</el-button>
        <el-button type="primary" size="small" @click="startExport" :loading="starting">
          开始导出（{{ store.selectedDefs.length }} 个配置）
        </el-button>
        <span class="text-muted">条件留空 = 导出全部；地区/项目配置可按“范围”过滤</span>
      </div>
      <el-collapse v-model="openDefs">
        <el-collapse-item v-for="code in store.selectedDefs" :key="code"
                          :name="code" :title="defTitle(code)">
          <div v-if="isScoped(code)" class="cond-row">
            <span class="cond-label">范围</span>
            <el-input v-model="condModel(code).__scope__.value" size="small" style="width: 220px"
                      :placeholder="scopeLabel(code) + '（留空=全部）'" clearable />
          </div>
          <DynamicForm :def="defsStore.byCode(code)" mode="conditions"
                       :model="condModel(code)" />
        </el-collapse-item>
      </el-collapse>
    </div>

    <!-- 第三步：导出结果 / 在线编辑 / 下载 -->
    <div class="page-card" v-if="store.step === 3">
      <div class="step-toolbar">
        <el-button size="small" @click="store.goStep(2)">返回修改条件</el-button>
        <el-button size="small" @click="store.refresh()">刷新状态</el-button>
        <template v-if="store.task && store.task.status === 'SUCCESS'">
          <el-button type="primary" size="small" @click="downloadZip(false)">打包下载（选中）</el-button>
          <el-button type="success" size="small" @click="downloadZip(true)">全部下载</el-button>
        </template>
      </div>

      <TaskProgress :snapshot="store.task || {}" />

      <el-table v-if="files.length" :data="files" size="small" border style="margin-top: 12px"
                @selection-change="sel = $event">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="defCode" label="配置编码" width="150">
          <template #default="{ row }"><span class="mono">{{ row.defCode }}</span></template>
        </el-table-column>
        <el-table-column prop="defName" label="配置名称" width="170" />
        <el-table-column prop="rowCount" label="行数" width="80" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEditor(row)">在线编辑</el-button>
            <el-button size="small" text type="primary" @click="downloadOne(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="editorVisible" :title="'在线编辑 - ' + editorDef" width="92%" top="4vh" destroy-on-close>
        <SpreadSheet v-if="editorVisible" :source="editorUrl" :filename="editorDef + '.xlsx'" height="62vh" />
      </el-dialog>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useExportStore } from '../stores/exportTask'
import { useDefsStore } from '../stores/defs'
import DynamicForm from '../components/DynamicForm.vue'
import TaskProgress from '../components/TaskProgress.vue'
import SpreadSheet from '../components/SpreadSheet.vue'

const store = useExportStore()
const defsStore = useDefsStore()
const keyword = ref('')
const levelFilter = ref('')
const starting = ref(false)
const openDefs = ref([])
const sel = ref([])
const editorVisible = ref(false)
const editorDef = ref('')
const editorUrl = ref('')
const tableRef = ref(null)

const filteredDefs = computed(() => defsStore.enabled.filter(d =>
  (!keyword.value || d.name.includes(keyword.value) || d.code.toLowerCase().includes(keyword.value.toLowerCase()))
  && (!levelFilter.value || d.level === levelFilter.value)))

const files = computed(() => {
  const t = store.task
  return t && t.detail && t.detail.files ? t.detail.files : []
})

onMounted(async () => {
  if (!defsStore.defs.length) await defsStore.load()
  // 页面刷新恢复：若存在运行中的任务则重新订阅
  if (store.task && !['SUCCESS', 'FAILED'].includes(store.task.status)) {
    store.subscribe(store.task.id)
  } else if (store.task) {
    store.refresh()
  }
})

function onSelectionChange(rows) {
  store.selectDefs(rows.map(r => r.code))
}

function levelLabel(l) { return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l }
function levelTag(l) { return { GLOBAL: '', REGION: 'warning', PROJECT: 'success' }[l] || 'info' }

function defTitle(code) {
  const d = defsStore.byCode(code)
  return d ? `${d.name}（${d.code} · ${levelLabel(d.level)}）` : code
}

/** 每个配置的条件对象（含范围条件 __scope__）。 */
const condModels = reactive({})
function condModel(code) {
  if (!condModels[code]) {
    condModels[code] = reactive({ __scope__: { op: 'eq', value: undefined } })
  }
  return condModels[code]
}

function isScoped(code) {
  const d = defsStore.byCode(code)
  return d && d.level !== 'GLOBAL'
}

function scopeLabel(code) {
  const d = defsStore.byCode(code)
  return d && d.level === 'REGION' ? '地区' : '项目'
}

async function startExport() {
  starting.value = true
  try {
    // 收拢条件（去掉空条件）
    const conditions = {}
    for (const code of store.selectedDefs) {
      const m = condModel(code)
      const conds = {}
      for (const [k, v] of Object.entries(m)) {
        if (!v || !v.op) continue
        const hasValue = v.value !== undefined && v.value !== null && v.value !== ''
        if (k === '__scope__') {
          if (hasValue) conds[k] = { op: 'eq', value: v.value }
        } else if (hasValue) {
          conds[k] = { op: v.op, value: v.value, value2: v.value2 }
        }
      }
      conditions[code] = conds
    }
    await store.startExport(conditions)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    starting.value = false
  }
}

function downloadOne(row) {
  const url = `/api/export/tasks/${store.task.id}/files/${row.defCode}`
  const a = document.createElement('a')
  a.href = url
  a.download = `${row.defCode}.xlsx`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function downloadZip(all) {
  const codes = all ? [] : sel.value.map(r => r.defCode)
  const url = `/api/export/tasks/${store.task.id}/package` + (codes.length ? `?defCodes=${codes.join(',')}` : '')
  const a = document.createElement('a')
  a.href = url
  a.download = `export-task-${store.task.id}.zip`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function openEditor(row) {
  editorDef.value = row.defCode
  editorUrl.value = `/api/export/tasks/${store.task.id}/files/${row.defCode}`
  editorVisible.value = true
}

function fmtSize(n) {
  if (!n) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}
</script>

<style scoped>
.step-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.cond-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cond-label {
  width: 110px;
  text-align: right;
  font-size: 13px;
  color: #606266;
}
</style>
