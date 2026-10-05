<template>
  <div class="interaction-card">
    <div class="interaction-header">
      <el-icon color="#e6a23c"><Warning /></el-icon>
      <span>需要你的确认</span>
    </div>
    <div class="interaction-summary">{{ interaction.summary }}</div>
    <div v-if="interaction.details" class="interaction-details">{{ interaction.details }}</div>
    <div class="interaction-actions">
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

<style lang="scss" scoped>
.interaction-card {
  border: 1px solid #faad14;
  border-radius: 8px;
  padding: 12px;
  background: #fffbe6;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.interaction-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}
.interaction-summary { font-size: 13px; line-height: 1.5; }
.interaction-details {
  font-size: 12px;
  color: #595959;
  background: #fff;
  border-radius: 4px;
  padding: 6px 8px;
  white-space: pre-wrap;
}
.interaction-actions { display: flex; gap: 8px; }
</style>
