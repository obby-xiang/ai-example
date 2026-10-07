<template>
  <aside class="h-full flex flex-col bg-white border-l border-solid border-[#e4e7ed] w-[360px] min-w-[320px]">
    <header class="px-3 py-2 border-b border-gray-200 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="font-medium">AI 助手</span>
        <el-tag size="small" :type="healthTagType">{{ healthLabel }}</el-tag>
      </div>
      <div class="flex items-center gap-1">
        <el-button link size="small" @click="probe">检测可用性</el-button>
        <el-button link size="small" :icon="Fold" title="隐藏 AI 栏（隐藏后业务功能不受影响）" @click="ai.toggleExpand()" />
      </div>
    </header>

    <div class="px-3 py-2 text-xs text-gray-500 border-b border-gray-100">
      <div>会话 ID（页签级）：<span class="font-mono">{{ shortSessionId }}</span></div>
      <div>工作区上下文：<span class="font-mono">{{ workspace.contextKey }}</span>（数据版本 {{ workspace.dataVersion }}）</div>
    </div>

    <el-alert
      v-if="ai.notice"
      class="m-2"
      type="warning"
      :closable="false"
      show-icon
      :title="ai.notice"
    />

    <el-alert
      v-if="ai.busyConflict"
      class="m-2"
      type="error"
      :closable="false"
      show-icon
      :title="`该会话已有一轮进行中（runId=${ai.busyConflict.runId}）`"
    >
      <el-button size="small" type="primary" @click="ai.reattachActive()">一键重挂该轮</el-button>
    </el-alert>

    <div class="flex-1 layout-column px-3 py-2 space-y-3">
      <el-empty
        v-if="ai.messages.length === 0"
        description="本棒为占位壳：状态层（SSE/HITL/前端工具/恢复）已就绪，真实对话由 b 棒接入"
      />
      <div v-for="message in ai.messages" :key="message.id" class="text-sm">
        <div class="text-xs text-gray-400 mb-1">
          {{ message.role === 'user' ? '用户' : message.role === 'system' ? '系统' : '助手' }}
        </div>
        <div class="whitespace-pre-wrap break-words">{{ message.content }}</div>
        <el-collapse v-if="message.reasoning" class="mt-1">
          <el-collapse-item title="思考链" name="reasoning">
            <div class="text-xs text-gray-600 whitespace-pre-wrap">{{ message.reasoning }}</div>
          </el-collapse-item>
        </el-collapse>
        <div v-if="message.toolRuns.length > 0" class="mt-1 space-y-1">
          <div
            v-for="run in message.toolRuns"
            :key="run.toolCallId"
            class="flex items-center gap-2 text-xs border border-gray-200 rounded px-2 py-1"
          >
            <el-tag size="small" :type="toolTagType(run.status)">{{ toolStatusLabel(run.status) }}</el-tag>
            <span class="font-mono">{{ run.name }}</span>
            <span v-if="run.result" class="text-gray-500 truncate">{{ run.result }}</span>
          </div>
        </div>
        <div v-if="message.pendingCall" class="mt-2 border border-amber-300 rounded p-2 text-xs">
          <div class="mb-1">
            {{ message.pendingCall.kind === 'CONFIRM' ? '待人工确认' : '待前端执行' }}：
            <span class="font-mono">{{ message.pendingCall.name }}</span>
            <span v-if="message.pendingCall.timeoutSeconds">
              （超时 {{ formatCountdown(message.pendingCall.timeoutSeconds) }}）
            </span>
          </div>
          <div v-if="message.pendingCall.kind === 'CONFIRM'" class="flex gap-2">
            <el-button size="small" type="primary" @click="ai.confirm(true)">放行</el-button>
            <el-button size="small" type="danger" plain @click="ai.confirm(false, '用户拒绝')">拒绝</el-button>
          </div>
        </div>
      </div>
    </div>

    <footer class="border-t border-gray-200 p-2">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="对话输入由 b 棒接入（状态层 send/confirm/reattach 已可用）"
        :disabled="!inputEnabled"
      />
      <div class="flex justify-between items-center mt-1">
        <span class="text-xs text-gray-400">
          {{ ai.loading ? '生成中…' : ai.suspended ? '挂起等待中…' : '空闲' }}
        </span>
        <div class="flex gap-1">
          <el-button size="small" :disabled="!ai.loading" @click="ai.stop()">停止</el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="!inputEnabled"
            @click="submit"
          >
            发送
          </el-button>
        </div>
      </div>
    </footer>
  </aside>
</template>

<script setup lang="ts">
/**
 * AI 面板（本棒 = 占位壳）。
 *
 * 交互形态参照 kimi-k3 `AiPanel.vue:44-85`（思考链折叠 / 工具卡状态 / HITL 确认与拒绝
 * 双路径），但**不接真实对话渲染**：b 棒负责流式渲染、渐进披露提示与工具卡细节。
 *
 * 裁剪性：本组件只依赖 stores/ai 与 stores/workspace 的窄接口；
 * 隐藏它（App.vue 的 AI 栏开关）后全部业务功能照常。
 */
import { computed, onMounted, ref } from 'vue'
import { Fold } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatCountdown } from '@/utils/format'
import type { ToolRunStatus } from '@/types/ai'

const ai = useAiStore()
const workspace = useWorkspaceStore()
const draft = ref('')

/** b 棒接入前，仅当后端可用且输入非空时才允许发送 */
const inputEnabled = computed(() => ai.available && !ai.loading && draft.value.trim().length > 0)

const shortSessionId = computed(() => ai.sessionId.slice(0, 8))

const healthLabel = computed(() => {
  if (ai.health === null && ai.healthError === null) {
    return '未检测'
  }
  return ai.available ? '可用' : '降级'
})

const healthTagType = computed<'success' | 'warning' | 'info' | 'danger'>(() => {
  if (ai.health === null) {
    return 'info'
  }
  return ai.available ? 'success' : 'warning'
})

function toolTagType(status: ToolRunStatus): 'info' | 'warning' | 'success' | 'danger' {
  switch (status) {
    case 'succeeded':
      return 'success'
    case 'failed':
    case 'rejected':
      return 'danger'
    case 'running':
      return 'warning'
    default:
      return 'info'
  }
}

function toolStatusLabel(status: ToolRunStatus): string {
  const labels: Record<ToolRunStatus, string> = {
    pending: '待处理',
    running: '执行中',
    succeeded: '已成功',
    failed: '已失败',
    rejected: '已拒绝',
    expired: '已失效'
  }
  return labels[status]
}

function probe(): void {
  void ai.probeHealth()
}

function submit(): void {
  const text = draft.value
  draft.value = ''
  void ai.send(text)
}

onMounted(() => {
  ai.startMirror()
  void ai.probeHealth()
  // 刷新恢复：镜像即时恢复 + 后端历史对账（后端不可达时静默保留镜像）
  void ai.loadHistory()
})
</script>
