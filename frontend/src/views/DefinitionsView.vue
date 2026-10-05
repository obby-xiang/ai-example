<template>
  <div>
    <div class="page-header">
      <div>
        <h2>配置定义管理</h2>
        <p class="subtitle">查看和管理动态配置项的结构定义</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建配置定义
      </el-button>
    </div>

    <el-row :gutter="16">
      <!-- Left: definition list -->
      <el-col :span="8">
        <div class="def-list-panel card">
          <div class="list-toolbar">
            <el-select v-model="levelFilter" clearable size="small" placeholder="层级" style="width:100px">
              <el-option label="全局" value="GLOBAL" />
              <el-option label="地区" value="REGION" />
              <el-option label="项目" value="PROJECT" />
            </el-select>
            <el-input v-model="keyword" clearable size="small" placeholder="搜索" style="flex:1" />
          </div>
          <div class="def-item-list" v-loading="loading">
            <div
              v-for="def in filteredDefs" :key="def.code"
              class="def-item"
              :class="{ active: selected?.code === def.code }"
              @click="select(def)"
            >
              <div class="def-item-main">
                <span class="def-code">{{ def.code }}</span>
                <el-tag :type="levelType(def.level)" size="small">{{ levelLabel(def.level) }}</el-tag>
              </div>
              <div class="def-name">{{ def.name }}</div>
            </div>
            <el-empty v-if="filteredDefs.length === 0" description="无配置定义" />
          </div>
        </div>
      </el-col>

      <!-- Right: definition detail -->
      <el-col :span="16">
        <div v-if="selected" class="card">
          <div class="detail-header">
            <div>
              <h3>{{ selected.name }} <span class="code-badge">{{ selected.code }}</span></h3>
              <p class="def-desc">{{ selected.description || '暂无描述' }}</p>
            </div>
            <div>
              <el-button size="small" @click="downloadTpl(selected)">
                <el-icon><Download /></el-icon> 下载模板
              </el-button>
            </div>
          </div>

          <el-divider />

          <el-table :data="selected.fields || []" border size="small">
            <el-table-column prop="code" label="字段编码" width="130" />
            <el-table-column prop="label" label="标签" width="120" />
            <el-table-column label="类型" width="100">
              <template #default="{row}">
                <el-tag size="small" effect="plain">{{ row.fieldType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70">
              <template #default="{row}">
                <el-icon v-if="row.required" color="#67c23a"><Select /></el-icon>
                <el-icon v-else color="#dcdfe6"><Minus /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="70">
              <template #default="{row}">
                <el-icon v-if="row.isKey" color="#409eff"><Select /></el-icon>
                <el-icon v-else color="#dcdfe6"><Minus /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="引用" show-overflow-tooltip>
              <template #default="{row}">
                <span v-if="row.refDefCode">{{ row.refDefCode }}.{{ row.refFieldCode }}</span>
                <span v-else-if="row.optionsJson">
                  <el-tag v-for="opt in parseOpts(row.optionsJson).slice(0,3)" :key="opt.value" size="small" effect="plain" style="margin-right:2px">{{ opt.label }}</el-tag>
                  <span v-if="parseOpts(row.optionsJson).length > 3">+{{ parseOpts(row.optionsJson).length - 3 }}</span>
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-else description="从左侧选择一个配置定义" />
      </el-col>
    </el-row>

    <!-- Create dialog (simplified) -->
    <el-dialog v-model="createVisible" title="新建配置定义" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" placeholder="例如：PROJ_COST" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：项目成本表" />
        </el-form-item>
        <el-form-item label="层级" required>
          <el-select v-model="form.level" placeholder="选择层级">
            <el-option label="全局" value="GLOBAL" /><el-option label="地区级" value="REGION" /><el-option label="项目级" value="PROJECT" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listDefinitions, createDefinition, downloadTemplate } from '@/api/definitions.js'
import { ElMessage } from 'element-plus'
import { Plus, Download, Select, Minus } from '@element-plus/icons-vue'

const defs = ref([])
const loading = ref(false)
const selected = ref(null)
const levelFilter = ref('')
const keyword = ref('')
const createVisible = ref(false)
const creating = ref(false)
const form = ref({ code: '', name: '', level: 'GLOBAL', description: '' })

const filteredDefs = computed(() => defs.value.filter(d => {
  if (levelFilter.value && d.level !== levelFilter.value) return false
  if (keyword.value && !d.code.includes(keyword.value) && !d.name.includes(keyword.value)) return false
  return true
}))

onMounted(async () => {
  loading.value = true
  try {
    const res = await listDefinitions()
    defs.value = res.data.data || []
    if (defs.value.length) selected.value = defs.value[0]
  } finally { loading.value = false }
})

async function select(def) {
  const { getDefinition } = await import('@/api/definitions.js')
  const res = await getDefinition(def.code)
  selected.value = res.data.data
}

function openCreate() { form.value = { code: '', name: '', level: 'GLOBAL', description: '' }; createVisible.value = true }

async function handleCreate() {
  creating.value = true
  try {
    const res = await createDefinition({ ...form.value, fields: [] })
    defs.value.unshift(res.data.data)
    createVisible.value = false
    ElMessage.success('配置定义已创建')
  } finally { creating.value = false }
}

async function downloadTpl(def) {
  const res = await downloadTemplate(def.code)
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a'); a.href = url; a.download = def.code + '_' + def.name + '.xlsx'; a.click(); URL.revokeObjectURL(url)
}

function parseOpts(json) {
  try { return JSON.parse(json) } catch { return [] }
}
const levelType = (l) => ({ GLOBAL: 'success', REGION: 'warning', PROJECT: 'primary' })[l] || ''
const levelLabel = (l) => ({ GLOBAL: '全局', REGION: '地区', PROJECT: '项目' })[l] || l
</script>

<style lang="scss" scoped>
.def-list-panel { height: calc(100vh - 200px); display: flex; flex-direction: column; overflow: hidden; }
.list-toolbar { display: flex; gap: 8px; padding: 8px 0; flex-shrink: 0; }
.def-item-list { flex: 1; overflow-y: auto; }
.def-item {
  padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-lighter); cursor: pointer;
  &:hover { background: var(--el-fill-color-light); }
  &.active { background: var(--el-color-primary-light-9); }
}
.def-item-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; }
.def-code { font-weight: 600; font-size: 13px; }
.def-name { font-size: 12px; color: var(--el-text-color-secondary); }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; }
h3 { font-size: 16px; margin-bottom: 4px; }
.code-badge { font-size: 12px; color: var(--el-text-color-secondary); font-weight: normal; font-family: monospace; }
.def-desc { font-size: 13px; color: var(--el-text-color-secondary); }
</style>
