<template>
  <div class="flex items-center rounded-lg border border-slate-200 bg-white px-5 py-4">
    <template v-for="(s, i) in steps" :key="s.key">
      <div
        class="group flex cursor-pointer items-center gap-2 select-none"
        :title="'点击查看：' + s.title"
        @click="$emit('select', s.key)"
      >
        <div
          class="flex h-7 w-7 items-center justify-center rounded-full border-2 text-xs font-semibold transition-all"
          :class="circleClass(s, i)"
        >
          <el-icon v-if="stateOf(s, i) === 'done'" size="13"><Check /></el-icon>
          <span v-else>{{ i + 1 }}</span>
        </div>
        <span
          class="whitespace-nowrap text-sm transition-colors"
          :class="labelClass(s, i)"
        >{{ s.title }}</span>
      </div>
      <div
        v-if="i < steps.length - 1"
        class="mx-3 h-0.5 min-w-6 flex-1 rounded"
        :class="connectorClass(s, i)"
      ></div>
    </template>
  </div>
</template>

<script setup>
import { Check } from '@element-plus/icons-vue'

const props = defineProps({
  steps: { type: Array, required: true }, // [{key, title}]
  current: { type: String, default: '' }, // 当前步骤 key
  completed: { type: Boolean, default: false } // 任务是否已完成
})
defineEmits(['select'])

function currentIndex() {
  const idx = props.steps.findIndex(s => s.key === props.current)
  return idx < 0 ? 0 : idx
}

function stateOf(s, i) {
  if (props.completed) return 'done'
  if (i < currentIndex()) return 'done'
  if (i === currentIndex()) return 'active'
  return 'pending'
}

function circleClass(s, i) {
  const st = stateOf(s, i)
  if (st === 'done') return 'border-green-500 bg-green-500 text-white'
  if (st === 'active') return 'border-blue-500 bg-blue-500 text-white shadow ring-2 ring-blue-100'
  return 'border-slate-300 bg-white text-slate-400 group-hover:border-blue-400 group-hover:text-blue-500'
}

function labelClass(s, i) {
  const st = stateOf(s, i)
  if (st === 'done') return 'text-slate-500'
  if (st === 'active') return 'font-semibold text-blue-600'
  return 'text-slate-400 group-hover:text-slate-600'
}

function connectorClass(s, i) {
  // 步骤 i 与 i+1 之间的连线：i < currentIndex 时（即 i+1 <= currentIndex）已完成
  if (props.completed || i < currentIndex()) return 'bg-green-500'
  return 'bg-slate-200'
}
</script>
