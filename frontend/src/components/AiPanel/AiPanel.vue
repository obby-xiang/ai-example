<template>
  <div class="ai-panel">
    <!-- Header -->
    <div class="ai-header">
      <div class="ai-header-left">
        <el-icon class="ai-icon"><ChatDotRound /></el-icon>
        <span class="ai-title">AI 助手</span>
        <el-tag v-if="aiStore.runActive" type="warning" size="small" effect="plain">思考中…</el-tag>
      </div>
      <div class="ai-header-actions">
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
    <div class="message-list" ref="messageListRef">
      <div v-if="aiStore.messages.length === 0" class="empty-state">
        <el-icon size="36" color="#c0c4cc"><ChatDotRound /></el-icon>
        <p>你好！我是 AI 助手，可以帮你管理配置数据。</p>
        <p class="hint">尝试告诉我你想做什么，比如"帮我导出货币配置"</p>
      </div>

      <template v-for="msg in aiStore.messages" :key="msg.id">
        <!-- User message -->
        <div v-if="msg.role === 'user'" class="msg msg-user">
          <div class="msg-bubble">{{ msg.text }}</div>
        </div>

        <!-- Assistant message -->
        <div v-else-if="msg.role === 'assistant'" class="msg msg-assistant">
          <div class="msg-bubble">
            <span v-if="msg.streaming" class="streaming-text">{{ msg.text }}<span class="cursor">▌</span></span>
            <span v-else>{{ msg.text }}</span>
          </div>
        </div>

        <!-- Tool call -->
        <div v-else-if="msg.role === 'tool'" class="msg msg-tool">
          <ToolCallCard :msg="msg" />
        </div>

        <!-- System message -->
        <div v-else-if="msg.role === 'system'" class="msg msg-system">
          <div class="msg-system-text">{{ msg.text }}</div>
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
    <div class="composer">
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
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :disabled="!inputText.trim() || (aiStore.runActive && !aiStore.pendingInteraction)"
        :loading="aiStore.runActive"
        @click="handleSend"
        class="send-btn"
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

<style lang="scss" scoped>
.ai-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--el-bg-color);
  border-left: 1px solid var(--el-border-color);
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color-page);

  &-left {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.ai-icon { color: var(--el-color-primary); font-size: 18px; }
.ai-title { font-weight: 600; font-size: 15px; }

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 40px 20px;

  p { font-size: 14px; }
  .hint { font-size: 12px; color: var(--el-text-color-placeholder); }
}

.msg {
  display: flex;
  &-user { justify-content: flex-end; }
  &-assistant { justify-content: flex-start; }
}

.msg-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;

  .msg-user & {
    background: var(--el-color-primary);
    color: white;
    border-bottom-right-radius: 4px;
  }

  .msg-assistant & {
    background: var(--el-fill-color);
    color: var(--el-text-color-primary);
    border-bottom-left-radius: 4px;
  }
}

.cursor {
  animation: blink 1s step-end infinite;
  @keyframes blink { 50% { opacity: 0; } }
}

.msg-system-text {
  width: 100%;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 4px 0;
}

.composer {
  padding: 12px;
  border-top: 1px solid var(--el-border-color);
  display: flex;
  gap: 8px;
  align-items: flex-end;

  .el-textarea { flex: 1; }

  .send-btn { align-self: flex-end; }
}
</style>
