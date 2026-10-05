<template>
  <div class="flex h-full flex-col bg-white">
    <!-- Header -->
    <div class="flex items-center justify-between border-b border-slate-200 bg-slate-50 px-4 py-3">
      <div class="flex items-center gap-2">
        <el-icon class="text-lg text-blue-500"><ChatDotRound /></el-icon>
        <span class="text-[15px] font-semibold text-slate-800">AI 助手</span>
        <el-tag v-if="aiStore.runActive" type="warning" size="small" effect="plain">思考中…</el-tag>
      </div>
      <div class="flex items-center gap-1">
        <el-tooltip content="新对话" placement="bottom">
          <el-button :icon="RefreshRight" circle size="small" @click="handleReset" />
        </el-tooltip>
        <el-tooltip v-if="aiStore.runActive" content="取消" placement="bottom">
          <el-button :icon="Close" circle size="small" type="danger" @click="aiStore.cancelRun()" />
        </el-tooltip>
      </div>
    </div>

    <!-- Context chip -->
    <ContextChip />

    <!-- Message list -->
    <div class="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 py-4" ref="messageListRef">
      <div v-if="aiStore.messages.length === 0" class="flex flex-1 flex-col items-center justify-center gap-2 py-10 text-center text-slate-500">
        <el-icon size="36" class="text-slate-300"><ChatDotRound /></el-icon>
        <p class="text-sm">你好！我是 AI 助手，可以帮你管理配置数据。</p>
        <p class="text-xs text-slate-400">尝试告诉我你想做什么，比如"帮我导出货币配置"</p>
      </div>

      <template v-for="msg in aiStore.messages" :key="msg.id">
        <!-- User message -->
        <div v-if="msg.role === 'user'" class="flex justify-end">
          <div class="max-w-[85%] whitespace-pre-wrap break-words rounded-xl rounded-br-sm bg-blue-500 px-3 py-2 text-sm leading-relaxed text-white">
            {{ msg.text }}
          </div>
        </div>

        <!-- Assistant message -->
        <div v-else-if="msg.role === 'assistant'" class="flex justify-start">
          <div class="max-w-[85%] whitespace-pre-wrap break-words rounded-xl rounded-bl-sm bg-slate-100 px-3 py-2 text-sm leading-relaxed text-slate-800">
            <span v-if="msg.streaming">{{ msg.text }}<span class="cursor">▌</span></span>
            <span v-else>{{ msg.text }}</span>
          </div>
        </div>

        <!-- Tool call -->
        <div v-else-if="msg.role === 'tool'" class="flex justify-start">
          <ToolCallCard :msg="msg" />
        </div>

        <!-- System message -->
        <div v-else-if="msg.role === 'system'" class="w-full text-center text-xs text-slate-400">
          {{ msg.text }}
        </div>
      </template>

      <!-- HITL Interaction card -->
      <InteractionCard
        v-if="aiStore.pendingInteraction"
        :interaction="aiStore.pendingInteraction"
        @approve="handleApprove"
        @reject="handleReject"
      />
    </div>

    <!-- Suggestion chips -->
    <SuggestionChips @select="handleSuggestion" />

    <!-- Composer -->
    <div class="flex items-end gap-2 border-t border-slate-200 p-3">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        :autosize="{ minRows: 2, maxRows: 5 }"
        placeholder="输入消息，按 Enter 发送（Shift+Enter 换行）"
        :disabled="aiStore.runActive && !aiStore.pendingInteraction"
        @keydown.enter.exact.prevent="handleSend"
        @keydown.enter.shift.exact="() => {}"
        resize="none"
        class="flex-1"
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :disabled="!inputText.trim() || (aiStore.runActive && !aiStore.pendingInteraction)"
        :loading="aiStore.runActive"
        @click="handleSend"
        class="shrink-0"
      >发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { ChatDotRound, RefreshRight, Close, Promotion } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai.js'
import { ElMessageBox } from 'element-plus'
import ContextChip from './ContextChip.vue'
import ToolCallCard from './ToolCallCard.vue'
import InteractionCard from './InteractionCard.vue'
import SuggestionChips from './SuggestionChips.vue'

const aiStore = useAiStore()
const inputText = ref('')
const messageListRef = ref(null)

// Auto-scroll to bottom when new messages arrive
watch(() => aiStore.messages.length, async () => {
  await nextTick()
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}, { immediate: false })

watch(() => aiStore.messages[aiStore.messages.length - 1]?.text, async () => {
  await nextTick()
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
})

async function handleSend() {
  const text = inputText.value.trim()
  if (!text) return
  inputText.value = ''
  await aiStore.sendMessage(text)
}

async function handleReset() {
  try {
    await ElMessageBox.confirm('确定要开始新对话吗？当前对话记录将被清除。', '新对话', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await aiStore.resetSession()
  } catch (e) { /* user cancelled */ }
}

function handleSuggestion(text) {
  inputText.value = text
}

async function handleApprove() {
  const interaction = aiStore.pendingInteraction
  if (interaction?.iid) {
    await aiStore.submitInteraction(interaction.iid, true, '', {})
  }
}

async function handleReject() {
  const interaction = aiStore.pendingInteraction
  if (interaction?.iid) {
    await aiStore.submitInteraction(interaction.iid, false, '用户已拒绝此操作', {})
  }
}
</script>

<style scoped>
.cursor {
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
</style>
