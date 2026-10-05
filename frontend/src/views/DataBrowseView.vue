<template>
  <div>
    <div class="page-card">
      <div class="step-toolbar">
        <span style="font-weight: 600">数据浏览</span>
        <el-select v-model="defCode" size="small" style="width: 260px" placeholder="选择配置" @change="onDefChange">
          <el-option v-for="d in defsStore.defs" :key="d.code" :label="`${d.name}（${d.code}）`" :value="d.code" />
        </el-select>
        <el-radio-group v-model="published" size="small" @change="loadData">
          <el-radio-button :value="true">生效数据</el-radio-button>
          <el-radio-button :value="false">草稿数据</el-radio-button>
        </el-radio-group>
        <el-select v-if="!published" v-model="batchId" size="small" style="width: 200px" placeholder="选择批次"
                   @change="loadData">
          <el-option v-for="b in batches" :key="b.id" :label="`批次#${b.id} ${b.message || ''}`" :value="b.id" />
        </el-select>
        <div style="flex: 1"></div>
        <el-button size="small" @click="loadData">刷新</el-button>
        <el-tag size="small" type="info">共 {{ total }} 行</el-tag>
      </div>

      <el-table v-if="currentDef" :data="rows" size="small" border v-loading="loading">
        <el-table-column label="范围" width="110" v-if="currentDef.level !== 'GLOBAL'">
          <template #default="{ row }">{{ row.scope }}</template>
        </el-table-column>
        <el-table-column v-for="f in currentDef.fields" :key="f.code" :label="f.label" min-width="110"
                         show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="f.type === 'BOOLEAN'" size="small" :type="row.data[f.code] ? 'success' : 'info'">
              {{ row.data[f.code] ? '是' : '否' }}
            </el-tag>
            <span v-else>{{ row.data[f.code] }}</span>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="90">
          <template #default="{ row }">
            <span class="text-muted">{{ row.batchId || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="请选择配置查看数据" />

      <div class="pager" v-if="currentDef">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev, pager, next, sizes"
                       :page-sizes="[10, 20, 50]" @current-change="loadData" @size-change="onSizeChange" small />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useDefsStore } from '../stores/defs'
import { api } from '../api'

const defsStore = useDefsStore()
const defCode = ref('')
const published = ref(true)
const batchId = ref(null)
const batches = ref([])
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const currentDef = computed(() => defsStore.byCode(defCode.value))

onMounted(async () => {
  if (!defsStore.defs.length) await defsStore.load()
  loadBatches()
  if (defsStore.defs.length) {
    defCode.value = defsStore.defs[0].code
    loadData()
  }
  // AI 工具触发的数据刷新
  window.addEventListener('refresh-data', (e) => {
    if (e.detail && e.detail.defCode) {
      defCode.value = e.detail.defCode
      loadData()
    }
  })
})

async function loadBatches() {
  try {
    batches.value = await api.get('/api/import/batches')
  } catch {
    batches.value = []
  }
}

function onDefChange() {
  page.value = 1
  loadData()
}

function onSizeChange() {
  page.value = 1
  loadData()
}

async function loadData() {
  if (!defCode.value) return
  loading.value = true
  try {
    const q = new URLSearchParams({
      defCode: defCode.value,
      published: published.value,
      page: page.value,
      size: size.value
    })
    if (!published.value && batchId.value) q.set('batchId', batchId.value)
    const data = await api.get(`/api/data/rows?${q.toString()}`)
    rows.value = data.rows
    total.value = data.total
  } finally {
    loading.value = false
  }
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
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
