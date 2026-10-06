<template>
  <div class="flex flex-col gap-2 rounded-lg border border-amber-400 bg-amber-50 p-3">
    <div class="flex items-center gap-1.5 text-[13px] font-semibold">
      <el-icon color="#e6a23c"><Warning /></el-icon>
      <span>需要你的确认</span>
    </div>
    <div class="text-[13px] leading-relaxed">{{ interaction.summary }}</div>
    <div v-if="interaction.details" class="whitespace-pre-wrap rounded bg-white p-1.5 text-xs text-slate-600">{{ interaction.details }}</div>
    <div class="flex gap-2">
      <el-button type="primary" size="small" @click="$emit('approve')">
        <el-icon><Check /></el-icon> 批准执行
      </el-button>
      <el-button size="small" @click="$emit('reject')">
        <el-icon><Close /></el-icon> 拒绝
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { Warning, Check, Close } from '@element-plus/icons-vue'
defineProps({ interaction: Object })
defineEmits(['approve', 'reject'])
</script>
