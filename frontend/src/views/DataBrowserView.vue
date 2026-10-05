<template>
  <div>
    <div class="page-header">
      <div>
        <h2>配置数据浏览</h2>
        <p class="subtitle">查看已发布的配置数据</p>
      </div>
    </div>

    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="6">
        <el-select v-model="selectedCode" filterable placeholder="选择配置定义" style="width:100%" @change="loadData">
          <el-option-group v-for="group in defGroups" :key="group.level" :label="groupLabel(group.level)">
            <el-option v-for="d in group.defs" :key="d.code" :label="`${d.code} - ${d.name}`" :value="d.code" />
          </el-option-group>
        </el-select>
      </el-col>
      <el-col :span="4" v-if="selectedLevel === 'REGION'">
        <el-select v-model="scopeKey" clearable placeholder="选择地区" style="width:100%" @change="loadData">
          <el-option v-for="r in regions" :key="r.code" :label="r.name" :value="r.code" />
        </el-select>
      </el-col>
      <el-col :span="4" v-else-if="selectedLevel === 'PROJECT'">
        <el-select v-model="scopeKey" clearable placeholder="选择项目" style="width:100%" @change="loadData">
          <el-option v-for="p in projects" :key="p.code" :label="p.name" :value="p.code" />
        </el-select>
      </el-col>
      <el-col :span="4">
        <el-tag v-if="total !== null" type="info">共 {{ total }} 行</el-tag>
      </el-col>
    </el-row>

    <div v-if="!selectedCode" class="empty-hint">
      <el-empty description="请从上方选择一个配置定义查看数据" />
    </div>

    <div v-else>
      <el-table :data="rows" v-loading="loading" border stripe size="small" max-height="600">
        <el-table-column
          v-for="col in columns"
          :key="col.code"
          :prop="col.code"
          :label="col.label"
          show-overflow-tooltip
          min-width="120"
        />
      </el-table>

      <el-pagination
        v-if="total > pageSize"
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top:12px;justify-content:flex-end;display:flex"
        @current-change="loadData"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listDefinitions, getDefinition } from '@/api/definitions.js'
import { getRegions, getProjects } from '@/api/masterdata.js'
import request from '@/api/request.js'

const selectedCode = ref('')
const scopeKey = ref('')
const defs = ref([])
const regions = ref([])
const projects = ref([])
const rows = ref([])
const columns = ref([])
const total = ref(null)
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 50

const defGroups = computed(() => {
  const map = {}
  for (const d of defs.value) {
    if (!map[d.level]) map[d.level] = { level: d.level, defs: [] }
    map[d.level].defs.push(d)
  }
  return Object.values(map)
})

const selectedDef = computed(() => defs.value.find(d => d.code === selectedCode.value))
const selectedLevel = computed(() => selectedDef.value?.level)

onMounted(async () => {
  const [defRes, regionRes, projectRes] = await Promise.all([
    listDefinitions(), getRegions(), getProjects()
  ])
  defs.value = defRes.data.data || []
  regions.value = regionRes.data.data || []
  projects.value = projectRes.data.data || []
})

async function loadData() {
  if (!selectedCode.value) return
  loading.value = true
  try {
    // Load full definition to get fields
    const defRes = await getDefinition(selectedCode.value)
    const def = defRes.data.data
    columns.value = (def.fields || []).map(f => ({ code: f.code, label: f.label }))

    // Load data
    const params = {
      scopeType: selectedLevel.value || 'GLOBAL',
      page: currentPage.value - 1,
      size: pageSize
    }
    if (scopeKey.value) params.scopeKey = scopeKey.value

    const res = await request.get(`/data/${selectedCode.value}`, { params })
    const page = res.data.data
    total.value = page.totalElements || 0

    // Parse each row's dataJson
    rows.value = (page.content || []).map(r => {
      try { return JSON.parse(r.dataJson) } catch { return {} }
    })
  } finally {
    loading.value = false
  }
}

const groupLabel = (l) => ({ GLOBAL: '全局配置', REGION: '地区级配置', PROJECT: '项目级配置' })[l] || l
</script>

<style lang="scss" scoped>
.empty-hint { padding: 60px 0; }
</style>
