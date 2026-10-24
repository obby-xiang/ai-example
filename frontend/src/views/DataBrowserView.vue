<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">{{ t('views.data.title') }}</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          {{ t('views.data.subtitle') }}
        </div>
      </div>
      <div>
        <el-button :icon="Refresh" circle :title="t('views.data.refresh')" @click="reload" />
        <el-button @click="resetAll">{{ t('views.data.resetFilters') }}</el-button>
      </div>
    </div>

    <el-card shadow="never" class="mb-3">
      <div class="flex items-center gap-2 mb-3">
        <el-select v-model="defCode" :placeholder="t('views.data.selectDefinition')" class="w-[260px]" @change="onDefChange">
          <el-option
            v-for="item in defs"
            :key="item.code"
            :label="`${item.name}（${item.code}）`"
            :value="item.code"
          />
        </el-select>
        <el-select v-model="scopeType" :placeholder="t('views.data.scopePlaceholder')" clearable class="w-[240px]">
          <el-option :label="t('views.data.global')" value="GLOBAL" />
          <el-option :label="t('views.data.region')" value="REGION" />
          <el-option :label="t('views.data.project')" value="PROJECT" />
        </el-select>
        <el-select
          v-if="scopeType === 'REGION'"
          v-model="scopeKey"
          :placeholder="t('views.data.regionShort')"
          clearable
          class="w-[200px]"
        >
          <el-option v-for="region in regions" :key="region.code" :label="`${region.name}（${region.code}）`" :value="region.code" />
        </el-select>
        <el-select
          v-else-if="scopeType === 'PROJECT'"
          v-model="scopeKey"
          :placeholder="t('views.data.projectShort')"
          clearable
          filterable
          class="w-[220px]"
        >
          <el-option v-for="project in projects" :key="project.code" :label="`${project.name}（${project.code}）`" :value="project.code" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="reloadData">{{ t('views.data.search') }}</el-button>
        <el-button :loading="counting" @click="previewCount">{{ t('views.data.estimateRows') }}</el-button>
        <el-tag v-if="serverCount !== null" size="small" type="success">{{ t('views.data.serverCount', { count: serverCount }) }}</el-tag>
      </div>

      <ConditionForm
        v-if="currentDef"
        :fields="currentDef.fields ?? []"
        :model-value="draftModel"
        :hint="t('views.data.hint')"
        @update:model-value="onModelChange"
      />
      <el-empty v-else :description="t('views.data.emptyPick')" :image-size="60" />
    </el-card>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-3"
      :title="t('views.data.scopeHint')"
      :description="browserNote"
    />

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column type="expand">
        <template #default="{ row }: { row: ConfigDataRow }">
          <el-descriptions :column="2" border size="small" class="my-1 mx-4">
            <el-descriptions-item :label="t('views.data.rowKey')">{{ row.rowKey }}</el-descriptions-item>
            <el-descriptions-item :label="t('views.data.scope')">{{ row.scopeType }} / {{ row.scopeKey || '—' }}</el-descriptions-item>
            <el-descriptions-item :label="t('views.data.version')">{{ row.version ?? '—' }}</el-descriptions-item>
            <el-descriptions-item :label="t('views.data.updatedAt')">{{ formatDateTime(row.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item
              v-for="field in currentFields"
              :key="field.code"
              :label="field.label"
            >
              {{ displayFieldValue(field, dataOf(row)[field.code]) }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <el-table-column prop="rowKey" :label="t('views.data.rowKey')" width="160" />
      <el-table-column
        v-if="scopeType !== 'GLOBAL'"
        :label="t('views.data.scope')"
        width="160"
      >
        <template #default="{ row }: { row: ConfigDataRow }">{{ row.scopeType }} / {{ row.scopeKey || '—' }}</template>
      </el-table-column>
      <el-table-column
        v-for="field in currentFields"
        :key="field.code"
        :label="field.label"
        min-width="120"
        show-overflow-tooltip
      >
        <template #default="{ row }: { row: ConfigDataRow }">
          <el-tag v-if="field.fieldType === 'BOOLEAN'" size="small" :type="dataOf(row)[field.code] === 'true' ? 'success' : 'info'">
            {{ displayFieldValue(field, dataOf(row)[field.code]) }}
          </el-tag>
          <span v-else>{{ displayFieldValue(field, dataOf(row)[field.code]) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="version" :label="t('views.data.version')" width="80" />
      <el-table-column :label="t('views.data.updatedAt')" width="170">
        <template #default="{ row }: { row: ConfigDataRow }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty :description="defCode ? t('views.data.emptyScope') : t('views.data.emptyPick')" />
      </template>
    </el-table>

    <div class="flex justify-end mt-3">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="page + 1"
        :page-size="size"
        :page-sizes="[20, 50, 100]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 数据浏览页（五页之⑤，FR-1.1：按定义 + 范围 + 字段级条件查询）。
 *
 * 口径（S4.4c 修正：后端 P2 已给列表端点补 `conditions` 入参）：
 * - **范围过滤与字段级条件**都是服务端口径（`scopeType`/`scopeKey`/`conditions` 三个后端入参），
 *   分页由后端做，`total` 即命中总数 —— 前端不再做任何"只筛当前页"的镜像求值；
 * - 行数预估（`/count`）与列表（`/{defCode}`）同源同口径，两者对同一条件的命中数应一致；
 * - 行数据来自 `dataJson` 字符串，渲染时按定义字段做中文标签转换（ENUM/BOOLEAN）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import ConditionForm from '@/components/wizard/ConditionForm.vue'
import { dataApi } from '@/api/data'
import { definitionsApi } from '@/api/definitions'
import { masterDataApi } from '@/api/masterdata'
import { useWorkspaceStore } from '@/stores/workspace'
import {
  buildQueryCondition,
  conditionSummary,
  displayFieldValue,
  emptyDraftModel,
  isConditionEmpty,
  stringifyCondition,
  type ConditionDraftModel
} from '@/types/condition'
import { parseRowData, type ConfigDataRow, type DataQuery, type ScopeType } from '@/types/data'
import type { ConfigDefinition, ConfigField } from '@/types/definition'
import type { Project, Region } from '@/types/masterdata'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const route = useRoute()
const workspace = useWorkspaceStore()

const defs = ref<ConfigDefinition[]>([])
const defCode = ref('')
const draftModel = ref<ConditionDraftModel>(emptyDraftModel())

const scopeType = ref<ScopeType | ''>('')
const scopeKey = ref('')
const regions = ref<Region[]>([])
const projects = ref<Project[]>([])

const rows = ref<ConfigDataRow[]>([])
const total = ref(0)
const page = ref(0)
// 默认页大小 20。依据 = 业界依据 §8「分页大小主流默认」：GitHub 默认 30/上限 100、Stripe 默认 10/上限 100，
// 内网小数据量场景推荐"默认 20，可选 20/50/100"；与后端 DataController 的缺省页大小同值
// （该端点原缺省 50，2026-10-10 数字规格清点裁决⑥ 把前后端统一为 20）。
// 本值不是新值（前端一直是 20）；改本值须连带改后端 DataController 的 defaultValue 与上方 :page-sizes 选项表。
const size = ref(20)
const loading = ref(false)
const counting = ref(false)
const serverCount = ref<number | null>(null)

const currentDef = computed(() => defs.value.find((item) => item.code === defCode.value) ?? null)
const currentFields = computed<readonly ConfigField[]>(() => currentDef.value?.fields ?? [])

const condition = computed(() => buildQueryCondition(draftModel.value))

const browserNote = computed(() => {
  if (isConditionEmpty(condition.value)) {
    return t('views.data.conditionNone')
  }
  return t('views.data.conditionApplied', {
    summary: conditionSummary(condition.value),
    total: total.value
  })
})

function dataOf(row: ConfigDataRow): Record<string, unknown> {
  return parseRowData(row)
}

function onModelChange(value: ConditionDraftModel): void {
  draftModel.value = value
  serverCount.value = null
}

async function fetchDefinitions(): Promise<ConfigDefinition[] | null> {
  try {
    return await definitionsApi.listDefinitions()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.data.msg.defsLoadFailed'))
    return null
  }
}

/** 选中定义后重置范围/条件/分页（不触发请求，由调用方决定拉列表还是只拉数据）。 */
function applyDefSelection(code: string): void {
  const def = defs.value.find((item) => item.code === code)
  scopeType.value = def ? (def.level as ScopeType) : ''
  scopeKey.value = ''
  draftModel.value = emptyDraftModel()
  serverCount.value = null
  page.value = 0
  workspace.enterPage({
    pageId: 'data',
    page: 'data',
    taskType: null,
    step: null,
    selectedDefs: code ? [code] : []
  })
}

function onDefChange(code: string): void {
  applyDefSelection(code)
  void loadData()
}

async function loadData(): Promise<void> {
  if (!defCode.value) {
    return
  }
  loading.value = true
  try {
    const params: DataQuery = {
      page: page.value,
      size: size.value
    }
    if (scopeType.value) {
      params.scopeType = scopeType.value
    }
    if (scopeKey.value) {
      params.scopeKey = scopeKey.value
    }
    if (!isConditionEmpty(condition.value)) {
      params.conditions = stringifyCondition(condition.value)
    }
    const result = await dataApi.listData(defCode.value, params)
    rows.value = result.content ?? []
    total.value = result.totalElements ?? 0
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.data.msg.dataLoadFailed'))
  } finally {
    loading.value = false
  }
}

async function previewCount(): Promise<void> {
  if (!defCode.value) {
    ElMessage.warning(t('views.data.msg.pickDefinition'))
    return
  }
  counting.value = true
  try {
    const params: { scopeType?: ScopeType; scopeKey?: string; conditions?: string } = {}
    if (scopeType.value) {
      params.scopeType = scopeType.value
    }
    if (scopeKey.value) {
      params.scopeKey = scopeKey.value
    }
    if (!isConditionEmpty(condition.value)) {
      params.conditions = stringifyCondition(condition.value)
    }
    const result = await dataApi.countData(defCode.value, params)
    serverCount.value = result.count
    ElMessage.success(t('views.data.msg.serverCount', { count: result.count }))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.data.msg.estimateFailed'))
  } finally {
    counting.value = false
  }
}

/**
 * 重拉定义列表：当前 defCode 仍有效时**不动**范围/条件/页码（查询/刷新共用）；
 * 只有失效或未选时才按 route/首项初始化。失败直接返回，不回退任何用户状态。
 */
async function refreshDefinitions(): Promise<void> {
  const list = await fetchDefinitions()
  if (!list) {
    return
  }
  defs.value = list
  if (defCode.value && list.some((item) => item.code === defCode.value)) {
    return
  }
  const queryDef = typeof route.query.defCode === 'string' ? route.query.defCode : ''
  const next = queryDef || list[0]?.code || ''
  defCode.value = next
  applyDefSelection(next)
}

/** 只按当前筛选拉数据（不重拉定义、不隐式重置页码）。 */
async function reloadData(): Promise<void> {
  serverCount.value = null
  if (defCode.value) {
    await loadData()
  }
}

/** 右上「刷新」：补拉定义 + 按当前筛选重拉数据。 */
async function reload(): Promise<void> {
  await refreshDefinitions()
  await reloadData()
}

function resetAll(): void {
  draftModel.value = emptyDraftModel()
  scopeKey.value = ''
  serverCount.value = null
  page.value = 0
  void reloadData()
}

function onPageChange(next: number): void {
  page.value = next - 1
  void loadData()
}

function onSizeChange(next: number): void {
  size.value = next
  page.value = 0
  void loadData()
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'data', page: 'data', taskType: null, step: null })
  await reload()
  const [regionList, projectList] = await Promise.all([
    masterDataApi.listRegions().catch(() => []),
    masterDataApi.listProjects().catch(() => [])
  ])
  regions.value = regionList
  projects.value = projectList
})
</script>
