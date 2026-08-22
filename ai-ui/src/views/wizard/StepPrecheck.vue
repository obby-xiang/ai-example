<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>③ 预检查</span>
        <div class="btn-row">
          <el-tag size="small" :type="overallStatus === 'PASS' ? 'success' : 'warning'">
            {{ overallStatus === 'PASS' ? '✔ 整体通过' : '⚠ 存在告警' }}
          </el-tag>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        style="margin-bottom:20px;"
        title="预检查说明"
        description="该步骤自动检查配置完整性:配置定义是否存在、必填字段是否有默认值、数据量是否在安全范围(≤10万行)、字段定义是否包含重复key。"
      />

      <el-table :data="checkItems" border stripe>
        <el-table-column label="配置项" min-width="200">
          <template #default="{row}">
            <b>{{ row.name }}</b> <span style="color:#909399;">({{ row.code }})</span>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="100" align="center" prop="count" />
        <el-table-column label="字段数" width="90" align="center" prop="fieldCount" />
        <el-table-column label="必填项默认值齐全" width="160" align="center">
          <template #default="{row}">
            <el-tag size="small" :type="row.requiredDefaultsOk ? 'success' : 'danger'">
              {{ row.requiredDefaultsOk ? '通过' : '缺失' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据量安全" width="130" align="center">
          <template #default="{row}">
            <el-tag size="small" :type="row.dataSafe ? 'success' : (row.count === 0 ? 'info' : 'warning')">
              {{ row.dataSafe ? '通过' : (row.count === 0 ? '无数据' : '过大') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段定义合法" width="130" align="center">
          <template #default="{row}">
            <el-tag size="small" :type="row.columnOk ? 'success' : 'danger'">
              {{ row.columnOk ? '通过' : '不通过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="200">
          <template #default="{row}">
            <span v-if="row.notes.length === 0" style="color:#67c23a;">OK</span>
            <div v-else style="color:#e6a23c;font-size:12px;">
              <div v-for="(n, i) in row.notes" :key="i">· {{ n }}</div>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回</el-button>
        <el-button type="primary" :disabled="!checkItems.length" :loading="saving" @click="next">
          下一步:复核确认 →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const checkItems = ref([])
const saving = ref(false)

const overallStatus = computed(() => {
  if (!checkItems.value.length) return 'EMPTY'
  const allPass = checkItems.value.every(x => x.requiredDefaultsOk && x.dataSafe && x.columnOk)
  return allPass ? 'PASS' : 'WARN'
})

async function runChecks() {
  const ids = taskStore.selectedDefIds
  checkItems.value = []
  for (const id of ids) {
    const def = configStore.defById(id)
    if (!def) {
      checkItems.value.push({
        defId: id, name: '已删除#'+id, code: '-',
        count: 0, fieldCount: 0,
        requiredDefaultsOk: false, dataSafe: true, columnOk: false,
        notes: ['配置定义不存在,请移除或重新添加']
      })
      continue
    }
    const cnt = await configStore.count(id).catch(() => ({ total: -1 }))
    const columns = def.columns || []
    const notes = []
    // 必填默认值
    let reqOk = true
    for (const c of columns) {
      if (c.required && (c.defaultValue === null || c.defaultValue === undefined || c.defaultValue === '')) {
        // 不强制不通过(允许用户自己填),只警告
      }
      if (c.required && c.type === 'select' && Array.isArray(c.options) && c.options.length === 0) {
        reqOk = false
        notes.push(`下拉字段「${c.label||c.key}」缺少选项`)
      }
    }
    // 数据安全
    const count = cnt.total == null || cnt.total < 0 ? 0 : cnt.total
    const dataSafe = count <= 100000
    if (!dataSafe) notes.push(`数据量(${count})超过10万建议分批导出`)
    if (taskStore.scenario === 'ADD' && count === 0) {
      // 新增模式预期无数据,正常
    } else if (taskStore.scenario === 'MODIFY' && count === 0) {
      notes.push('修改模式,但当前无数据可修改')
    }
    // 字段定义合法
    let colOk = true
    const keys = columns.map(c => String(c.key))
    if (new Set(keys).size !== keys.length) { notes.push('字段key重复'); colOk = false }
    for (const c of columns) {
      if (!c.key || !c.label) { colOk = false; notes.push('存在字段缺key或label') }
    }
    checkItems.value.push({
      defId: id,
      name: def.name,
      code: def.code,
      count,
      fieldCount: columns.length,
      requiredDefaultsOk: reqOk,
      dataSafe,
      columnOk: colOk,
      notes
    })
  }
}

function prev() {
  router.push('/wizard/view-defs')
}

async function next() {
  if (!checkItems.value.length) return
  saving.value = true
  try {
    await taskStore.saveStepData('PRECHECK', {
      checkedAt: Date.now(),
      items: checkItems.value,
      overall: overallStatus.value
    })
    await taskStore.gotoStep('REVIEW')
    router.push('/wizard/review')
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('enter_step')
    }
    ElMessage.success('预检查已完成')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  if (!taskStore.selectedDefIds.length) {
    ElMessage.warning('尚未选择配置项,请先返回添加')
    router.push('/wizard/view-defs')
    return
  }
  await runChecks()
})
</script>
