<template>
  <el-container class="h-full">
    <el-main class="p-4 overflow-auto bg-[#f5f7fa]">
      <router-view />
    </el-main>
    <el-aside
      v-if="AI_PANEL_ENABLED"
      :width="aiStore?.expanded ? '400px' : '48px'"
      class="border-l border-solid border-[#e4e7ed] bg-white transition-[width] duration-200 overflow-hidden"
    >
      <AsyncAiPanel />
    </el-aside>
  </el-container>
</template>

<script setup>
import { defineAsyncComponent } from 'vue'
import { useAiStore } from '@/stores/ai'

/**
 * AI 对话栏总开关。
 * 置 false 可整体移除 AI 对话栏（连同下方 aiStore 一并删除即可），业务工作区
 * （任务列表 / 导出向导 / 导入向导）不 import ai store、不依赖任何 AI 能力，
 * 功能完全不受影响——所有 AI 工具动作在界面上均有对应按钮（选择配置项=DefSelector
 * 勾选、查询条件=第 2 步表单、下载模板/导出文件=对应按钮、启动导出/检查/导入/发布
 * =各步骤主按钮）。两侧仅通过 workspace store 的窄接口通信，可独立移除。
 */
const AI_PANEL_ENABLED = true

// 懒加载：AI 对话栏单独分包，首屏业务工作区不阻塞于 AI 相关代码
const AsyncAiPanel = defineAsyncComponent(() => import('@/components/AiPanel.vue'))

// 仅用于读取 AI 栏展开/收起状态以计算侧栏宽度；AI 禁用时不实例化
const aiStore = AI_PANEL_ENABLED ? useAiStore() : null
</script>
