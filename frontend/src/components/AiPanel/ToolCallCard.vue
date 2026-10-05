<template>
  <div class="tool-card" :class="{ success: msg.success === true, failed: msg.success === false }">
    <el-icon class="tool-icon">
      <Loading v-if="msg.success === null" class="spin" />
      <Check v-else-if="msg.success" />
      <Close v-else />
    </el-icon>
    <div class="tool-info">
      <span class="tool-name">{{ msg.title || msg.toolName }}</span>
      <span class="tool-summary">{{ msg.summary }}</span>
    </div>
  </div>
</template>

<script setup>
import { Loading, Check, Close } from '@element-plus/icons-vue'
defineProps({ msg: Object })
</script>

<style lang="scss" scoped>
.tool-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-extra-light);
  font-size: 12px;

  &.success { border-color: #b7eb8f; background: #f6ffed; }
  &.failed { border-color: #ffccc7; background: #fff2f0; }
}

.tool-icon { font-size: 14px; color: var(--el-text-color-secondary); flex-shrink: 0; }
.tool-info { display: flex; flex-direction: column; gap: 2px; }
.tool-name { font-weight: 500; color: var(--el-text-color-primary); }
.tool-summary { color: var(--el-text-color-secondary); }
.spin { animation: rotate 1.2s linear infinite; }
@keyframes rotate { to { transform: rotate(360deg); } }
</style>
