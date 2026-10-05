<template>
  <div class="ai-panel flex flex-col h-full">
    <div class="ai-panel__header flex items-center justify-between px-4 h-12 border-b border-gray-200">
      <div class="flex items-center gap-2">
        <el-icon :size="18" class="text-blue-600"><ChatDotRound /></el-icon>
        <span class="font-semibold text-sm">AI 助手</span>
        <el-tag size="small" type="success" effect="plain">DeepSeek</el-tag>
      </div>
      <div class="flex items-center gap-1">
        <el-tooltip content="清空当前页签的对话">
          <el-button text size="small" @click="onClear"><el-icon><Delete /></el-icon></el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- 活动卡片 -->
    <div v-if="aiStore.activities.length" class="ai-panel__activities px-4 py-2 border-b border-gray-100 bg-blue-50/50">
      <div class="text-xs text-gray-500 mb-1">最近操作</div>
      <transition-group name="act">
        <div v-for="a in aiStore.activities.slice(-3)" :key="a.id"
          class="text-xs text-gray-700 flex items-center gap-1 py-0.5">
          <el-icon :size="12" class="text-green-600"><CircleCheckFilled /></el-icon>
          <span class="truncate">{{ a.text }}</span>
          <span class="ml-auto text-gray-400 flex-shrink-0">{{ a.ts }}</span>
        </div>
      </transition-group>
    </div>

    <!-- 消息列表 -->
    <div ref="listRef" class="ai-panel__messages flex-1 overflow-y-auto px-4 py-3 space-y-3">
      <div v-if="!aiStore.messages.length" class="text-center text-sm text-gray-400 mt-8">
        <el-icon :size="36" class="mb-2"><ChatLineSquare /></el-icon>
        <p>你好！我是配置实施助手，可以帮你：</p>
        <div class="mt-2 leading-7">
          <p>· 创建导出/导入任务并选择配置项</p>
          <p>· 设置导出查询条件、启动导出</p>
          <p>· 提交导入数据、检查、导入与发布</p>
          <p>· 查询配置项结构与任务进度</p>
        </div>
        <p class="mt-3 text-xs">对话基于浏览器页签，刷新不丢失；新页签是新对话。</p>
      </div>
      <div v-for="m in aiStore.messages" :key="m.id"
        class="flex" :class="m.role === 'user' ? 'justify-end' : 'justify-start'">
        <div class="msg-bubble" :class="m.role === 'user' ? 'msg-bubble--user' : 'msg-bubble--ai'">
          <div class="whitespace-pre-wrap break-words text-sm leading-6">{{ m.content }}</div>
          <span v-if="m.streaming" class="stream-cursor"></span>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="ai-panel__input border-t border-gray-200 p-3">
      <div class="flex gap-2">
        <el-input v-model="draft" type="textarea" :rows="2" resize="none" maxlength="2000"
          placeholder="描述你想做的事，例如：帮我创建一个导出任务，导出系统参数配置"
          @keydown.enter.exact.prevent="onEnter" />
        <div class="flex flex-col gap-1">
          <el-button type="primary" :loading="aiStore.sending" :disabled="!draft.trim() && !aiStore.sending"
            @click="onSend">
            <el-icon v-if="!aiStore.sending"><Promotion /></el-icon>
            <span v-else class="text-xs">…</span>
          </el-button>
          <el-button v-if="aiStore.sending" @click="aiStore.stop()" title="停止">
            <el-icon><VideoPause /></el-icon>
          </el-button>
        </div>
      </div>
      <div class="text-xs text-gray-400 mt-1.5 flex justify-between">
        <span>Enter 发送 · Shift+Enter 换行</span>
        <span>会话 {{ sessionStore.sessionId.slice(-6) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { useAiStore } from '@/stores/ai'
import { useSessionStore } from '@/stores/session'

const aiStore = useAiStore()
const sessionStore = useSessionStore()
const draft = ref('')
const listRef = ref(null)

function onEnter(e) {
  if (!e.shiftKey) {
    onSend()
  }
}

async function onSend() {
  const text = draft.value
  if (!text.trim() || aiStore.sending) return
  draft.value = ''
  await aiStore.send(text)
}

async function onClear() {
  await aiStore.clear()
}

watch(() => aiStore.messages.length, () => {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
})

watch(() => aiStore.messages[aiStore.messages.length - 1]?.content, () => {
  if (listRef.value) {
    listRef.value.scrollTop = listRef.value.scrollHeight
  }
})
</script>

<style scoped>
.msg-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
}
.msg-bubble--user {
  background: #409eff;
  color: #fff;
  border-top-right-radius: 2px;
}
.msg-bubble--ai {
  background: #f4f4f5;
  color: #303133;
  border-top-left-radius: 2px;
}
.stream-cursor {
  display: inline-block;
  width: 2px;
  height: 14px;
  background: #409eff;
  margin-left: 2px;
  animation: blink 1s infinite;
  vertical-align: middle;
}
@keyframes blink {
  50% { opacity: 0; }
}
.act-enter-active { transition: all .3s; }
.act-enter-from { opacity: 0; transform: translateY(-6px); }
</style>
