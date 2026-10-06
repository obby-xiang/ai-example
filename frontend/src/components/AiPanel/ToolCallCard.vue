<template>
  <div
    class="flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-xs"
    :class="{
      'border-green-200 bg-green-50': msg.success === true,
      'border-red-200 bg-red-50': msg.success === false,
      'border-slate-200 bg-slate-50': msg.success === null || msg.success === undefined
    }"
  >
    <el-icon class="shrink-0 text-slate-500">
      <Loading v-if="msg.success === null" class="animate-spin" />
      <Check v-else-if="msg.success" class="text-green-600" />
      <Close v-else class="text-red-500" />
    </el-icon>
    <div class="flex min-w-0 flex-col gap-0.5">
      <span class="font-medium text-slate-700">{{ msg.title || msg.toolName }}</span>
      <span class="truncate text-slate-500">{{ msg.summary }}</span>
    </div>
  </div>
</template>

<script setup>
import { Loading, Check, Close } from '@element-plus/icons-vue'
defineProps({ msg: Object })
</script>

