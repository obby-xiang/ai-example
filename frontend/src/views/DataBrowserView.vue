<template>
  <div>
    <h2 class="text-base font-medium m-0">数据浏览</h2>
    <p class="text-xs text-gray-500 mt-1">
      已发布配置数据分页浏览与条件筛选（行数据为 dataJson 字符串，按需解析为对象渲染）。
    </p>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本棒为占位壳（b 棒实现数据表格与条件筛选）"
      description="契约层已就绪：types/data.ts（ConfigDataRow/ConfigStagingRow）+ api/data.ts（列表/计数）。"
      class="mt-3"
    />

    <el-descriptions class="mt-4" :column="2" border size="small">
      <el-descriptions-item label="列表端点">GET /api/data/{defCode}（scopeType/scopeKey/page/size）</el-descriptions-item>
      <el-descriptions-item label="行数估算">GET /api/data/{defCode}/count（与导出过滤同口径）</el-descriptions-item>
      <el-descriptions-item label="范围类型">GLOBAL / REGION / PROJECT（未传时按定义层级推导）</el-descriptions-item>
      <el-descriptions-item label="乐观锁">ConfigDataRow.version（发布并发冲突检测依据）</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = useWorkspaceStore()

onMounted(() => {
  workspace.enterPage({ pageId: 'data', page: 'data', taskType: null, step: null })
})
</script>
