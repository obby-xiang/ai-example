<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>④ 查看结果</span>
        <div class="btn-row">
          <el-button size="small" @click="refresh">
            <el-icon><Refresh /></el-icon> 重新计数
          </el-button>
          <el-button size="small" type="primary" :disabled="!items.length" @click="downloadJson">
            <el-icon><Download /></el-icon> 导出JSON
          </el-button>
        </div>
      </div>

      <div class="stat-box">
        <div class="stat-item">
          <div class="label">配置项总数</div>
          <div class="value">{{ items.length }}</div>
        </div>
        <div class="stat-item">
          <div class="label">导出总行数</div>
          <div class="value" style="color:#409EFF;">{{ grandTotal }}</div>
        </div>
        <div class="stat-item">
          <div class="label">导出时间</div>
          <div class="value" style="font-size:16px;">{{ exportAt }}</div>
        </div>
        <div class="stat-item">
          <div class="label">最大行数配置项</div>
          <div class="value" style="font-size:15px;">{{ topItem?.name || '—' }}</div>
        </div>
      </div>

      <el-table :data="items" border stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="name" label="配置项名称" min-width="180">
          <template #default="{row}">
            <b>{{ row.name }}</b>
            <div style="font-size:12px;color:#909399;">code: {{ row.code }} · defId: {{ row.defId }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="total" label="行数" width="120" align="center" sortable>
          <template #default="{row}">
            <el-tag type="primary" size="small" effect="plain">{{ row.total }} 行</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{row}">
            <el-button size="small" type="primary" link @click="openPreview(row)">查看/编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回查询条件</el-button>
        <el-button type="primary" :loading="finishing" @click="toComplete">
          完成并结束任务 ✓
        </el-button>
      </div>
    </div>

    <el-dialog v-model="showPreview" width="1000px" :title="`预览/编辑 - ${previewRow?.name || ''}`">
      <div v-if="previewDef" style="margin-bottom:10px;display:flex;gap:8px;align-items:center;">
        <el-button-group>
          <el-button size="small" :type="previewMode==='read'?'primary':''" @click="previewMode='read'">只读</el-button>
          <el-button size="small" :type="previewMode==='edit'?'primary':''" @click="previewMode='edit'">编辑</el-button>
        </el-button-group>
        <span style="color:#909399;font-size:12px;margin-left:8px;">编辑模式可调整列值/删行,导出时以当前表格内容为准</span>
      </div>
      <SpreadSheet
        v-if="showPreview && previewDef"
        :key="'preview-'+previewMode+'-'+(previewRow?.defId||'')"
        ref="previewSheetRef"
        :config-definition="previewDef"
        :initial-rows="previewRows"
        :mode="previewMode"
        :track-changes="false"
        height="460px"
      />
      <div v-else style="text-align:center;padding:20px;color:#909399;">暂无数据</div>
      <template #footer>
        <el-button @click="showPreview = false">关闭</el-button>
        <el-button type="primary" @click="downloadDef(previewRow)">下载JSON</el-button>
        <el-button type="success" @click="downloadDefExcel(previewRow)">导出此配置Excel</el-button>
        <el-button type="warning" @click="downloadAllExcel">导出全部Excel</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import SpreadSheet from '@/components/SpreadSheet.vue'
import { exportExcelByRows, exportExcelMultiSheet } from '@/utils/excel-io'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const items = ref([])
const atMs = ref(null)
const finishing = ref(false)

const grandTotal = computed(() => items.value.reduce((a, b) => a + (b.total || 0), 0))
const exportAt = computed(() => atMs.value ? dayjs(atMs.value).format('YYYY-MM-DD HH:mm:ss') : '—')
const topItem = computed(() => items.value.slice().sort((a, b) => (b.total || 0) - (a.total || 0))[0])

const showPreview = ref(false)
const previewRow = ref(null)
const previewRows = ref([])
const previewColumns = ref([])
// 预览模式: read 只读 / edit 编辑(切换用 :key 强制重挂,保留保护逻辑)
const previewMode = ref('read')
const previewSheetRef = ref(null)
const previewDef = computed(() => previewRow.value ? configStore.defById(previewRow.value.defId) : null)

function loadFromTaskStep() {
  const step = taskStore.currentTask?.stepData?.RESULT
  if (step && step.items && Array.isArray(step.items)) {
    items.value = step.items
    atMs.value = step.at || null
  }
}

async function refresh() {
  for (const it of items.value) {
    try {
      const r = await configStore.count(it.defId)
      it.total = r.total
    } catch (e) { /* ignore */ }
  }
  // 同步更新task step data
  const stepData = taskStore.currentTask?.stepData?.RESULT || {}
  stepData.items = items.value
  stepData.at = Date.now()
  await taskStore.saveStepData('RESULT', stepData)
  atMs.value = stepData.at
  ElMessage.success('已重新计数')
}

function downloadJson() {
  const obj = {
    type: 'export_summary',
    taskId: taskStore.currentTask?.id,
    taskName: taskStore.currentTask?.name,
    at: new Date().toISOString(),
    items: items.value
  }
  saveFile('export-summary-' + Date.now() + '.json', JSON.stringify(obj, null, 2))
  ElMessage.success('已开始下载汇总JSON')
}

async function openPreview(row) {
  if (!row?.defId) return
  previewRow.value = row
  const def = configStore.defById(row.defId)
  previewColumns.value = def?.columns || []
  // 移除 100 条限制:加载该配置项全部数据用于查看/编辑/导出
  previewRows.value = await configStore.allData(row.defId)
  previewMode.value = 'read'
  showPreview.value = true
}

async function downloadDef(row) {
  if (!row?.defId) return
  const def = configStore.defById(row.defId)
  const rows = await configStore.allData(row.defId, 100000)
  const obj = {
    defId: row.defId,
    code: row.code,
    name: row.name,
    columns: def?.columns || [],
    data: rows.map(r => ({ id: r.id, ...r.rowData }))
  }
  saveFile(`${row.code}-${Date.now()}.json`, JSON.stringify(obj, null, 2))
  ElMessage.success('已开始下载')
}

// 导出当前预览配置为 Excel(优先用预览 Sheet 当前内容,用户可能已编辑)
async function downloadDefExcel(row) {
  if (!row?.defId) return
  const def = configStore.defById(row.defId)
  if (!def) return ElMessage.warning('未找到配置定义')
  try {
    let rows = previewSheetRef.value?.collectRows?.()
    if (!rows || !rows.length) {
      rows = (await configStore.allData(row.defId, 100000))
        .map(r => ({ id: r.id, data: r.rowData, mark: 'unchanged' }))
    }
    await exportExcelByRows(def, rows, `${row.code}-${Date.now()}.xlsx`)
    ElMessage.success('Excel 已导出')
  } catch (e) {
    ElMessage.error('导出失败:' + (e?.message || e))
  }
}

// 多 sheet 合并导出全部配置项
async function downloadAllExcel() {
  if (!items.value.length) return ElMessage.warning('无可导出的配置项')
  const defsAndRows = []
  for (const item of items.value) {
    const def = configStore.defById(item.defId)
    if (!def) continue
    const rows = (await configStore.allData(item.defId, 100000))
      .map(r => ({ id: r.id, data: r.rowData, mark: 'unchanged' }))
    defsAndRows.push({ def, rows })
  }
  if (!defsAndRows.length) return ElMessage.warning('无可导出数据')
  try {
    await exportExcelMultiSheet(defsAndRows, `export-all-${Date.now()}.xlsx`)
    ElMessage.success(`已导出 ${defsAndRows.length} 个 sheet`)
  } catch (e) {
    ElMessage.error('导出失败:' + (e?.message || e))
  }
}

function saveFile(filename, content) {
  const blob = new Blob([content], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = filename; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

function prev() {
  router.push('/wizard/query-cond')
}

async function toComplete() {
  finishing.value = true
  try {
    await taskStore.complete()
    ElMessage.success('任务已完成!')
    router.push('/dashboard')
  } finally {
    finishing.value = false
  }
}

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  loadFromTaskStep()
  // 如果没有result数据(比如用户直跳),就按selectedIds计算
  if (!items.value.length && taskStore.selectedDefIds.length) {
    const arr = []
    for (const id of taskStore.selectedDefIds) {
      const d = configStore.defById(id)
      try {
        const cnt = await configStore.count(id)
        arr.push({ defId: id, code: d?.code, name: d?.name, total: cnt.total })
      } catch (e) { /* ignore */ }
    }
    items.value = arr
    atMs.value = Date.now()
  }
})
</script>
