<template>
  <div>
    <h2 class="text-base font-medium m-0">配置定义</h2>
    <p class="text-xs text-gray-500 mt-1">
      定义与字段浏览（层级/关键词过滤 + 模板下载）。字段契约按后端实体对齐：主键标志的 JSON 名是
      <span class="font-mono">key</span>（不是 isKey，Q3 教训）。
    </p>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本棒为占位壳（b 棒实现定义列表/字段表格与模板下载）"
      description="契约层已就绪：types/definition.ts（ConfigDefinition/ConfigField/FieldType）+ api/definitions.ts 四个端点。"
      class="mt-3"
    />

    <el-descriptions class="mt-4" :column="2" border size="small">
      <el-descriptions-item label="列表端点">GET /api/definitions（level/keyword）</el-descriptions-item>
      <el-descriptions-item label="详情端点">GET /api/definitions/{code}</el-descriptions-item>
      <el-descriptions-item label="模板下载">GET /api/definitions/{code}/template</el-descriptions-item>
      <el-descriptions-item label="字段类型">STRING / NUMBER / DATE / ENUM / BOOLEAN / REFERENCE</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = useWorkspaceStore()

onMounted(() => {
  workspace.enterPage({ pageId: 'definitions', page: 'definitions', taskType: null, step: null })
})
</script>
