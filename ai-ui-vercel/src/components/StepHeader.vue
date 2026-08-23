<template>
  <div class="step-indicator">
    <div class="page-card" style="padding:16px 20px;margin-bottom:20px;">
      <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;">
        <div>
          <el-tag size="small" type="info">任务 #{{ task?.id }}</el-tag>
          <span style="margin-left:10px;font-weight:600;font-size:15px;">{{ task?.name }}</span>
          <el-tag v-if="scenarioName" size="small" type="success" style="margin-left:10px;">场景: {{ scenarioName }}</el-tag>
        </div>
        <div>
          <span style="font-size:12px;color:#909399;">步骤 {{ currentIndex + 1 }} / {{ displaySteps.length }}：</span>
          <el-tag size="small" type="primary" effect="dark">{{ stepLabelMap[currentStep] }}</el-tag>
        </div>
      </div>
      <el-steps :active="currentIndex" finish-status="success" simple :process-status="'process'">
        <el-step v-for="(s, i) in displaySteps" :key="s" :title="stepLabelMap[s]" :status="i < currentIndex ? 'success' : (i === currentIndex ? 'process' : 'wait')" />
      </el-steps>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useTaskStore } from '@/stores/task'

const taskStore = useTaskStore()
const task = computed(() => taskStore.currentTask)
const steps = computed(() => taskStore.steps)
const currentStep = computed(() => taskStore.currentStep)
const scenarioName = computed(() => taskStore.scenarioName)

const stepLabelMap = {
  SELECT_SCENARIO: '① 选择场景',
  SELECT_DEFS: '② 选择配置项',
  VIEW_DEFS: '② 查看/编辑配置项',
  QUERY_COND: '③ 配置查询',
  PRECHECK: '③ 预检查',
  REVIEW: '④ 复核确认',
  PUBLISH: '⑤ 发布',
  RESULT: '④ 查看结果'
}

// 兜底：scenario 未选或 steps 未加载时，至少渲染当前步骤节点，避免"步骤 1/0"误导
// （进度条不同步 bug 防护：scenario=null 时 taskStore.steps 返回空数组）
const displaySteps = computed(() => {
  if (steps.value && steps.value.length) return steps.value
  return currentStep.value ? [currentStep.value] : ['SELECT_SCENARIO']
})
const currentIndex = computed(() => {
  const i = displaySteps.value.indexOf(currentStep.value)
  return i < 0 ? 0 : i
})
</script>
