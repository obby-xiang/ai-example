<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>① 请选择操作场景</span>
      </div>

      <el-row :gutter="20">
        <el-col :xs="12" :sm="12" :md="6" v-for="s in scenarios" :key="s.key">
          <div
            class="scenario-card"
            :class="{active: selected === s.key}"
            @click="selected = s.key"
          >
            <div class="icon-box" :style="{ background: s.bg, color: s.color }">
              <el-icon :size="28"><component :is="s.icon" /></el-icon>
            </div>
            <div class="s-name">{{ s.name }}</div>
            <div class="s-desc">{{ s.desc }}</div>
          </div>
        </el-col>
      </el-row>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="backDashboard">返回任务中心</el-button>
        <el-button type="primary" :disabled="!selected" :loading="saving" @click="confirm">
          下一步:{{ selectedNext }} →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useAiStore } from '@/stores/ai'
import StepHeader from '@/components/StepHeader.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const aiStore = useAiStore()
const router = useRouter()

const scenarios = [
  { key: 'EXPORT', name: '导出配置', desc: '选择配置项和条件,导出业务数据清单',
    icon: 'Download', bg: '#ecf5ff', color: '#409EFF' },
  { key: 'IMPORT', name: '导入配置', desc: '从文件或粘贴批量导入业务配置数据',
    icon: 'Upload', bg: '#f0f9eb', color: '#67c23a' },
  { key: 'ADD', name: '新增配置', desc: '通过SpreadJS表格在线新增配置行',
    icon: 'Plus', bg: '#fdf6ec', color: '#e6a23c' },
  { key: 'MODIFY', name: '修改配置', desc: '在SpreadJS中修改/删除已存在的配置,追踪变更',
    icon: 'EditPen', bg: '#fef0f0', color: '#f56c6c' }
]

const selected = ref(taskStore.scenario || '')
const saving = ref(false)

const selectedNext = computed(() => {
  if (!selected.value) return ''
  const steps = taskStore.scenarioMeta.scenarioSteps?.[selected.value] || []
  return steps[1] ? ({
    SELECT_DEFS: '选择配置项',
    VIEW_DEFS: '查看配置项'
  }[steps[1]] || '下一步') : '下一步'
})

watch(selected, (n, o) => {
  if (n && n !== o) {
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('preview_scenario_' + n)
    }
  }
})

async function confirm() {
  if (!selected.value) return
  saving.value = true
  try {
    await taskStore.selectScenario(selected.value)
    ElMessage.success('已选择场景:' + scenarios.find(s => s.key === selected.value).name)
    // 触发AI自动提示(工作区联动方向1)
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('select_scenario')
    }
    const t = taskStore.currentTask
    const target = {
      EXPORT: '/wizard/select-defs',
      IMPORT: '/wizard/view-defs',
      ADD: '/wizard/view-defs',
      MODIFY: '/wizard/view-defs'
    }[t.scenario]
    router.push(target)
  } finally {
    saving.value = false
  }
}

function backDashboard() {
  router.push('/dashboard')
}

onMounted(() => {
  if (!taskStore.currentTask) router.push('/dashboard')
})
</script>
