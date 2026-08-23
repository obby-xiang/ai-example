<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>② 查看/编辑配置项数据</span>
        <div class="btn-row">
          <el-tag type="success" size="small" v-if="taskStore.scenario === 'ADD'">新增模式</el-tag>
          <el-tag type="warning" size="small" v-if="taskStore.scenario === 'MODIFY'">修改模式·追踪变更</el-tag>
          <el-tag type="info" size="small" v-if="taskStore.scenario === 'IMPORT'">导入模式</el-tag>
          <el-tag size="small">已选 {{ selectedIds.length }} 项</el-tag>
        </div>
      </div>

      <!-- 配置项标签页 -->
      <el-tabs v-if="selectedIds.length" v-model="activeDef" type="border-card">
        <el-tab-pane
          v-for="id in selectedIds"
          :key="'tab'+id"
          :label="tabLabel(id)"
          :name="String(id)"
        >
          <template #label>
            <span>
              <el-icon style="vertical-align:-2px;"><Tickets /></el-icon>
              {{ defName(id) }}
              <el-tag size="small" type="info" style="margin-left:6px;" v-if="countById[id] != null">
                {{ countById[id] }} 行
              </el-tag>
              <el-tag size="small" type="success" v-if="changesById[id]?.added" style="margin-left:4px;">
                +{{ changesById[id].added }}
              </el-tag>
              <el-tag size="small" type="warning" v-if="changesById[id]?.modified" style="margin-left:4px;">
                ~{{ changesById[id].modified }}
              </el-tag>
              <el-tag size="small" type="danger" v-if="changesById[id]?.deleted" style="margin-left:4px;">
                -{{ changesById[id].deleted }}
              </el-tag>
            </span>
          </template>

          <div style="margin-bottom:12px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
            <el-button size="small" @click="refreshActiveDef">
              <el-icon><Refresh /></el-icon> 从数据库重新加载
            </el-button>
            <el-button size="small" type="primary" plain @click="saveActiveDef">
              <el-icon><Check /></el-icon> 保存此配置页数据
            </el-button>
            <el-button size="small" type="success" plain @click="validateActive">
              <el-icon><CircleCheckFilled /></el-icon> 运行表格校验
            </el-button>
            <el-button v-if="taskStore.scenario === 'MODIFY'" size="small" type="warning" plain @click="showChanges">
              <el-icon><View /></el-icon> 查看变更明细
            </el-button>
            <el-button v-if="taskStore.scenario === 'ADD'" size="small" @click="addEmptyRow">
              <el-icon><Plus /></el-icon> 在末尾新增一行
            </el-button>
            <span style="margin-left:auto;color:#909399;font-size:12px;">
              支持Excel操作(Ctrl+C/V粘贴)、插入/删除行、列过滤;列校验必填/下拉/数字均使用 SpreadJS 原生能力。
            </span>
          </div>

          <SpreadSheet
            v-if="activeDefData"
            :key="'s'+activeDef"
            ref="sheetRef"
            :config-definition="activeDefData"
            :initial-rows="defRowsCache[Number(activeDef)] || []"
            :mode="taskStore.scenario === 'IMPORT' ? 'edit' : 'edit'"
            :track-changes="taskStore.scenario === 'MODIFY'"
            @data-changed="(rows) => onActiveDataChanged(Number(activeDef), rows)"
            @changes-collected="(c) => onChanges(Number(activeDef), c)"
          />
          <div v-else style="padding:40px;text-align:center;color:#c0c4cc;">正在加载数据...</div>
        </el-tab-pane>
      </el-tabs>
      <div v-else style="padding:40px;text-align:center;color:#909399;">
        尚未选择配置项定义。请先添加要操作的配置项定义。
        <el-button type="primary" size="small" style="margin-left:12px;" @click="goSelectDefs">去添加 →</el-button>
      </div>

      <!-- 已选配置定义芯片 -->
      <div style="margin-top:20px;padding:14px;background:#fafafa;border-radius:8px;">
        <div style="margin-bottom:8px;font-size:13px;color:#606266;">配置项选择:</div>
        <span v-if="selectedIds.length === 0" style="color:#c0c4cc;font-size:12px;">
          还未添加任何配置项。点击下方"添加配置项"进行添加。
        </span>
        <span class="chip" v-for="id in selectedIds" :key="'c'+id">
          {{ defName(id) }} <span class="close" @click="removeDef(id)">×</span>
        </span>
        <div style="margin-top:10px;">
          <el-button size="small" type="primary" plain @click="showDefPicker = true">
            <el-icon><Plus /></el-icon> 添加配置项
          </el-button>
          <el-button size="small" type="danger" plain :disabled="!selectedIds.length" @click="clearDefs">
            清空全部
          </el-button>
        </div>
      </div>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回</el-button>
        <el-button type="primary" :disabled="!selectedIds.length" :loading="saving" @click="next">
          下一步:预检查 →
        </el-button>
      </div>
    </div>

    <!-- 添加配置项对话框 -->
    <el-dialog v-model="showDefPicker" title="添加配置项" width="640px">
      <div v-if="unselectedDefs.length === 0" style="color:#909399;text-align:center;padding:20px;">
        系统中所有配置项都已添加。
      </div>
      <div class="defs-grid" v-else>
        <div
          v-for="d in unselectedDefs"
          :key="d.id"
          class="def-card"
          @click="addDef(d.id)"
          style="cursor:pointer;"
        >
          <div class="def-head">
            <div>
              <div class="def-name">{{ d.name }}</div>
              <div class="def-code">code: {{ d.code }}</div>
            </div>
            <el-tag size="small" type="success">点击添加</el-tag>
          </div>
          <div class="def-fields">
            <span class="field-tag" v-for="c in d.columns" :key="c.key">{{ c.label }}</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showDefPicker = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 变更明细对话框 -->
    <el-dialog v-model="showChangesDlg" title="变更明细 (MODIFY模式)" width="720px">
      <div v-if="!activeChange" style="color:#909399;text-align:center;padding:20px;">暂无变更</div>
      <div v-else>
        <el-alert type="info" :closable="false" style="margin-bottom:12px;">
          新增 <b style="color:#67c23a;">{{ activeChange.summary.added }}</b> 行,
          修改 <b style="color:#e6a23c;">{{ activeChange.summary.modified }}</b> 行,
          删除 <b style="color:#f56c6c;">{{ activeChange.summary.deleted }}</b> 行
        </el-alert>
        <el-table :data="activeChange.detail.slice(0, 200)" size="small" border max-height="360">
          <el-table-column prop="id" label="行ID" width="90" />
          <el-table-column label="标记" width="90">
            <template #default="{row}">
              <el-tag size="small" :type="{added:'success',modified:'warning',deleted:'danger',unchanged:'info'}[row.mark]">
                {{ {added:'新增',modified:'修改',deleted:'删除',unchanged:'未改'}[row.mark] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="行数据">
            <template #default="{row}">
              <span style="font-size:12px;">{{ JSON.stringify(row.data).slice(0, 200) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import SpreadSheet from '@/components/SpreadSheet.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const selectedIds = ref(taskStore.selectedDefIds.slice())
const activeDef = ref(String(selectedIds.value[0] || ''))
const sheetRef = ref(null)
const saving = ref(false)
const showDefPicker = ref(false)
const showChangesDlg = ref(false)
const activeChange = ref(null)

// 缓存
const countById = reactive({})
const defRowsCache = reactive({})
const changesById = reactive({})

const unselectedDefs = computed(() =>
  configStore.enabledDefinitions.filter(d => !selectedIds.value.includes(d.id))
)
const activeDefData = computed(() =>
  activeDef.value ? configStore.defById(Number(activeDef.value)) : null
)

function defName(id) {
  const d = configStore.defById(Number(id))
  return d ? `${d.name} (${d.code})` : '#' + id
}
function tabLabel(id) { return defName(id) }

async function loadDef(defId, force = false) {
  if (defRowsCache[defId] && !force) return
  const [cnt, rows] = await Promise.all([
    configStore.count(defId),
    configStore.allData(defId, 100000)
  ])
  countById[defId] = cnt.total
  defRowsCache[defId] = rows || []
}

watch(activeDef, async (n) => {
  if (n) await loadDef(Number(n))
})

function onActiveDataChanged(defId, rows) {
  defRowsCache[defId] = rows.map(r => ({
    id: r.id || null,
    rowData: r.data
  }))
}
function onChanges(defId, c) {
  changesById[defId] = c.summary
}

async function refreshActiveDef() {
  if (sheetRef.value && typeof sheetRef.value.refreshFromServer === 'function') {
    await sheetRef.value.refreshFromServer()
  } else {
    await loadDef(Number(activeDef.value), true)
    // 重新挂载以刷新
    const prev = activeDef.value
    activeDef.value = ''
    setTimeout(() => { activeDef.value = prev }, 30)
  }
}

async function saveActiveDef() {
  if (!sheetRef.value) return ElMessage.warning('请先切换到某个配置页')
  const rows = sheetRef.value.collectRows()
  if (!rows || !rows.length) return ElMessage.warning('没有要保存的数据')
  const defId = Number(activeDef.value)
  const mode = taskStore.scenario === 'ADD' ? 'append' : 'merge'
  const res = await configStore.batchSave(defId, rows, mode)
  ElMessage.success(`保存成功: 新增${res.added} 修改${res.updated} 删除${res.deleted},当前共${res.total}行`)
  // 更新缓存(拉前100条)
  const page = await configStore.pageData(defId, 1, 200)
  defRowsCache[defId] = page.content || []
  countById[defId] = res.total
  if (typeof window.__triggerAiAutoPrompt === 'function') {
    window.__triggerAiAutoPrompt('data_saved')
  }
}

function validateActive() {
  if (!sheetRef.value) return
  const res = sheetRef.value.validateAll()
  if (res.valid) {
    ElMessage.success('校验通过,未发现错误!')
  } else {
    ElMessage.warning(`发现 ${res.errors.length} 处错误,已弹出`)
    ElMessageBox({
      title: '校验错误明细',
      message: `<div style="max-height:400px;overflow:auto;">${res.errors.map(e => `<div>第${e.row}行 · ${e.field}: ${e.msg}</div>`).join('')}</div>`,
      dangerouslyUseHTMLString: true,
      confirmButtonText: '我知道了',
      showCancelButton: false
    })
  }
}

function showChanges() {
  if (sheetRef.value) {
    activeChange.value = sheetRef.value.collectChanges()
  }
  showChangesDlg.value = true
}

function addEmptyRow() {
  if (sheetRef.value) {
    const sh = sheetRef.value.getSheet()
    if (sh) sh.addRows(sh.getRowCount(), 1)
    ElMessage.success('已在末尾新增空行,请直接填入数据。')
  }
}

async function addDef(id) {
  selectedIds.value.push(id)
  await taskStore.updateSelectedDefs(selectedIds.value)
  await loadDef(id)
  if (!activeDef.value || activeDef.value === '') activeDef.value = String(id)
  showDefPicker.value = false
  ElMessage.success('已添加:' + defName(id))
  if (typeof window.__triggerAiAutoPrompt === 'function') {
    window.__triggerAiAutoPrompt('select_defs')
  }
}

async function removeDef(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  await taskStore.updateSelectedDefs(selectedIds.value)
  if (activeDef.value === String(id)) {
    activeDef.value = String(selectedIds.value[0] || '')
  }
  ElMessage.info('已移除')
}

async function clearDefs() {
  try {
    await ElMessageBox.confirm('确定移除全部已选配置项?', '确认', { type: 'warning' })
    selectedIds.value = []
    await taskStore.updateSelectedDefs([])
    activeDef.value = ''
  } catch (e) { /* cancel */ }
}

function goSelectDefs() {
  showDefPicker.value = true
}

function prev() {
  router.push('/wizard/select-scenario')
}

async function next() {
  if (!selectedIds.value.length) return
  // 先保存当前页的未保存行标记(merge模式下后端负责处理mark)
  await taskStore.updateSelectedDefs(selectedIds.value)
  // 收集VIEW_DEFS步骤的快照(选中了哪些页,以及各页数据)以便刷新恢复
  const snapshot = {
    selectedIds: selectedIds.value,
    defRows: Object.keys(defRowsCache).reduce((acc, k) => {
      acc[k] = (defRowsCache[k] || []).slice(0, 10)
      return acc
    }, {}),
    countById: { ...countById }
  }
  saving.value = true
  try {
    await taskStore.saveStepData('VIEW_DEFS', snapshot)
    await taskStore.gotoStep('PRECHECK')
    router.push('/wizard/precheck')
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('enter_step')
    }
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  for (const id of selectedIds.value) {
    try { await loadDef(id) } catch (e) { /* ignore */ }
  }
  if (!activeDef.value && selectedIds.value.length) activeDef.value = String(selectedIds.value[0])
})
</script>
