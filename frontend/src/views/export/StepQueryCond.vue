<template>
  <div>
    <div class="mb-4 text-sm text-slate-500">为每个选中的配置项设置查询条件（范围过滤 + 字段级过滤），不设条件则导出全部数据</div>

    <el-tabs v-model="activeTab" type="card">
      <el-tab-pane v-for="item in task?.items || []" :key="item.defCode" :label="item.defCode" :name="item.defCode">
        <div class="py-4" v-if="conds[item.defCode]">
          <!-- Scope filter -->
          <div class="mb-2.5 flex items-center gap-2.5" v-if="defMap[item.defCode]?.level === 'REGION'">
            <span class="w-20 shrink-0 text-[13px] font-medium">地区范围</span>
            <el-select v-model="conds[item.defCode].scopeKeys" multiple clearable placeholder="不限地区（全部）" style="width:320px">
              <el-option v-for="r in regions" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
          </div>
          <div class="mb-2.5 flex items-center gap-2.5" v-else-if="defMap[item.defCode]?.level === 'PROJECT'">
            <span class="w-20 shrink-0 text-[13px] font-medium">项目范围</span>
            <el-select v-model="conds[item.defCode].scopeKeys" multiple clearable placeholder="不限项目（全部）" style="width:320px">
              <el-option v-for="p in projects" :key="p.code" :label="p.name" :value="p.code" />
            </el-select>
          </div>

          <!-- Field conditions -->
          <div class="my-3 text-[13px] font-semibold text-slate-800">字段条件（AND 关系）</div>
          <div v-for="(row, idx) in conds[item.defCode].rows" :key="idx" class="mb-2.5 flex items-center gap-2.5">
            <el-select v-model="row.fieldCode" placeholder="选择字段" style="width:180px" @change="onFieldChange(item.defCode, idx)">
              <el-option v-for="f in defMap[item.defCode]?.fields || []" :key="f.code" :label="`${f.label}(${f.code})`" :value="f.code" />
            </el-select>
            <el-select v-model="row.operator" placeholder="操作符" style="width:150px">
              <el-option v-for="op in operatorsFor(item.defCode, row.fieldCode)" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <div class="inline-flex items-center">
              <el-select v-if="isEnumField(item.defCode, row.fieldCode) && row.operator === 'IN'"
                         v-model="row.value" multiple clearable placeholder="选择一个或多个选项" style="width:260px">
                <el-option v-for="opt in optionsFor(item.defCode, row.fieldCode)" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
              <el-select v-else-if="isEnumField(item.defCode, row.fieldCode)" v-model="row.value" clearable placeholder="选择选项" style="width:260px">
                <el-option v-for="opt in optionsFor(item.defCode, row.fieldCode)" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
              <el-select v-else-if="isBooleanField(item.defCode, row.fieldCode)" v-model="row.value" clearable placeholder="选择" style="width:200px">
                <el-option label="是" value="true" /><el-option label="否" value="false" />
              </el-select>
              <el-date-picker v-else-if="isDateField(item.defCode, row.fieldCode)"
                              v-model="row.value" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:200px" />
              <el-input v-else v-model="row.value"
                        :placeholder="row.operator === 'IN' ? '逗号分隔多个值' : '输入值'"
                        style="width:220px" />
            </div>
            <el-button :icon="Delete" circle plain size="small" type="danger" @click="conds[item.defCode].rows.splice(idx, 1)" />
          </div>

          <div class="mt-2 flex items-center">
            <el-button size="small" :icon="Plus" @click="addRow(item.defCode)">添加条件</el-button>
            <el-button size="small" type="primary" :icon="Search" :loading="estimating === item.defCode" @click="estimate(item.defCode)">
              预估行数
            </el-button>
            <el-tag v-if="estimates[item.defCode] !== undefined" type="info" style="margin-left:8px">
              预计 {{ estimates[item.defCode] }} 行
            </el-tag>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="wizard-footer">
      <el-button @click="$emit('back')">
        <el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步
      </el-button>
      <el-button type="primary" @click="handleNext" :loading="saving">
        下一步：执行导出
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { listDefinitions } from '@/api/definitions.js'
import { getRegions, getProjects } from '@/api/masterdata.js'
import { useTaskStore } from '@/stores/task.js'
import request from '@/api/request.js'
import { ElMessage } from 'element-plus'
import { Plus, Delete, Search, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next', 'back'])

const taskStore = useTaskStore()
const defs = ref([])
const regions = ref([])
const projects = ref([])
// 已保存的条件 JSON 快照（防外部同步覆盖本地未保存编辑）
const syncedJson = reactive({})
const activeTab = ref('')
const saving = ref(false)
const estimating = ref('')
const estimates = reactive({})

const defMap = computed(() => Object.fromEntries(defs.value.map(d => [d.code, d])))
// defCode -> { scopeKeys: [], rows: [{fieldCode, operator, value}] }
const conds = reactive({})

const STRING_OPS = [
  { value: 'EQ', label: '等于' }, { value: 'NE', label: '不等于' },
  { value: 'CONTAINS', label: '包含' }, { value: 'STARTS_WITH', label: '开头是' },
  { value: 'IN', label: '在列表中' }, { value: 'EMPTY', label: '为空' }, { value: 'NOT_EMPTY', label: '不为空' }
]
const NUMBER_OPS = [
  { value: 'EQ', label: '等于' }, { value: 'NE', label: '不等于' },
  { value: 'GT', label: '大于' }, { value: 'GTE', label: '大于等于' },
  { value: 'LT', label: '小于' }, { value: 'LTE', label: '小于等于' },
  { value: 'EMPTY', label: '为空' }, { value: 'NOT_EMPTY', label: '不为空' }
]
const BOOL_OPS = [{ value: 'EQ', label: '等于' }, { value: 'EMPTY', label: '为空' }, { value: 'NOT_EMPTY', label: '不为空' }]

onMounted(async () => {
  const [defRes, regionRes, projectRes] = await Promise.all([
    listDefinitions(), getRegions(), getProjects()
  ])
  defs.value = defRes.data.data || []
  regions.value = regionRes.data.data || []
  projects.value = projectRes.data.data || []

  if (props.task?.items?.length > 0) {
    activeTab.value = props.task.items[0].defCode
    for (const item of props.task.items) {
      const parsed = parseExisting(item.conditionJson)
      conds[item.defCode] = reactive({
        scopeKeys: parsed.scopeKeys || [],
        rows: parsed.fields || []
      })
      syncedJson[item.defCode] = item.conditionJson || ''
    }
  }
})

// 外部变更（AI 设置条件等）同步；本地编辑保存后 syncedJson 更新，避免覆盖
watch(() => (props.task?.items || []).map(i => i.defCode + ':' + (i.conditionJson || '')).join('|'),
  (val, oldVal) => {
    if (val === oldVal) return
    for (const item of (props.task?.items || [])) {
      const cur = item.conditionJson || ''
      if (syncedJson[item.defCode] !== cur) {
        const parsed = parseExisting(cur)
        if (!conds[item.defCode]) {
          conds[item.defCode] = reactive({ scopeKeys: [], rows: [] })
        }
        conds[item.defCode].scopeKeys = parsed.scopeKeys || []
        conds[item.defCode].rows = parsed.fields || []
        syncedJson[item.defCode] = cur
      }
    }
  })

function parseExisting(json) {
  if (!json) return {}
  try {
    const p = JSON.parse(json)
    return { scopeKeys: p.scopeKeys || [], fields: p.fields || [] }
  } catch {
    return {}
  }
}

function fieldOf(defCode, fieldCode) {
  return (defMap.value[defCode]?.fields || []).find(f => f.code === fieldCode)
}
function isEnumField(defCode, fieldCode) { return fieldOf(defCode, fieldCode)?.fieldType === 'ENUM' }
function isBooleanField(defCode, fieldCode) { return fieldOf(defCode, fieldCode)?.fieldType === 'BOOLEAN' }
function isDateField(defCode, fieldCode) { return fieldOf(defCode, fieldCode)?.fieldType === 'DATE' }

function operatorsFor(defCode, fieldCode) {
  const f = fieldOf(defCode, fieldCode)
  if (!f) return []
  if (f.fieldType === 'BOOLEAN') return BOOL_OPS
  if (f.fieldType === 'NUMBER' || f.fieldType === 'DATE') return NUMBER_OPS
  return STRING_OPS
}

function optionsFor(defCode, fieldCode) {
  const f = fieldOf(defCode, fieldCode)
  if (!f?.optionsJson) return []
  try { return JSON.parse(f.optionsJson) } catch { return [] }
}

function addRow(defCode) {
  conds[defCode].rows.push({ fieldCode: '', operator: 'EQ', value: '' })
}

function onFieldChange(defCode, idx) {
  const row = conds[defCode].rows[idx]
  // 切换字段后重置操作符为默认
  row.operator = row.fieldCode ? (operatorsFor(defCode, row.fieldCode)[0]?.value || 'EQ') : ''
  row.value = ''
}

async function estimate(defCode) {
  estimating.value = defCode
  try {
    const cond = buildCondition(defCode)
    const res = await request.get(`/data/${defCode}/count`, {
      params: { conditions: JSON.stringify(cond) }
    })
    estimates[defCode] = res.data.data?.count ?? 0
  } catch (e) {
    ElMessage.error('预估失败: ' + (e.response?.data?.message || e.message))
  } finally {
    estimating.value = ''
  }
}

function buildCondition(defCode) {
  const c = conds[defCode]
  return {
    scopeKeys: c.scopeKeys || [],
    fields: (c.rows || [])
      .filter(r => r.fieldCode && r.operator)
      .map(r => {
        let value = r.value
        // IN 且非 ENUM：逗号分隔文本 → 数组
        if (r.operator === 'IN' && !Array.isArray(value) && value !== '') {
          value = String(value).split(/[,，]/).map(s => s.trim()).filter(Boolean)
        }
        // EMPTY / NOT_EMPTY 不需要值
        if (r.operator === 'EMPTY' || r.operator === 'NOT_EMPTY') value = null
        return { fieldCode: r.fieldCode, operator: r.operator, value }
      })
  }
}

async function handleNext() {
  saving.value = true
  try {
    for (const item of (props.task?.items || [])) {
      const condJson = JSON.stringify(buildCondition(item.defCode))
      await taskStore.setCondition(props.task.id, item.defCode, condJson)
      syncedJson[item.defCode] = condJson
    }
    emit('next')
  } finally {
    saving.value = false
  }
}
</script>

