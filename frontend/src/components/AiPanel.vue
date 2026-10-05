<template>
  <div v-if="!aiStore.expanded"
       class="h-full flex flex-col items-center pt-4 gap-2 cursor-pointer text-[#409eff]"
       @click="aiStore.toggleExpand()">
    <el-icon><ChatDotRound /></el-icon>
    <span class="writing-vertical text-[13px]">AI 助手</span>
  </div>

  <div v-else class="h-full flex flex-col">
    <div class="flex items-center gap-2.5 px-3 py-2.5 border-b border-solid border-[#ebeef5]">
      <div class="w-[34px] h-[34px] rounded-lg bg-gradient-to-br from-[#409eff] to-[#7b61ff] text-white flex items-center justify-center font-bold">AI</div>
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-[#303133]">实施助手</div>
        <div class="text-xs text-[#909399]">流式对话 · 工具调用</div>
      </div>
      <el-button size="small" text @click="aiStore.toggleExpand()" title="收起">
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <div ref="msgWrapRef" class="flex-1 overflow-y-auto p-3">
      <el-empty v-if="!aiStore.messages.length" :image-size="60">
        <template #image>
          <el-icon :size="36" color="#409EFF"><Promotion /></el-icon>
        </template>
        <template #description>
          <div class="mb-1 text-sm font-semibold text-[#606266]">您好，我是实施助手</div>
          <div class="text-[13px] text-[#909399] leading-[1.8]">我可以帮您查询配置项、创建任务、<br />代选配置、下载模板、启动导出/导入作业。</div>
        </template>
      </el-empty>

      <div v-for="m in aiStore.messages" :key="m.id"
           class="flex gap-2 mb-3.5" :class="m.role === 'user' ? 'flex-row-reverse' : ''">
        <div class="w-7 h-7 rounded-full text-xs flex items-center justify-center shrink-0"
             :class="m.role === 'user' ? 'bg-[#409eff] text-white' : 'bg-[#e4e7ed] text-[#606266]'">
          {{ m.role === 'user' ? '我' : 'AI' }}
        </div>
        <div class="max-w-[82%] rounded-lg px-2.5 py-2 text-[13px] text-[#303133] leading-[1.6] break-words"
             :class="m.role === 'user' ? 'bg-[#d9ecff]' : 'bg-[#f4f4f5]'">
          <span v-if="m.pending && !m.content" class="typing">
            <i class="dot"></i><i class="dot"></i><i class="dot"></i>
          </span>

          <details v-if="m.reasoning" class="mb-1.5 text-xs text-[#909399]">
            <summary class="cursor-pointer">思考过程</summary>
            <div class="mt-1 px-2 py-1.5 bg-[#fafafa] border-l-2 border-solid border-[#dcdfe6] whitespace-pre-wrap max-h-40 overflow-y-auto">{{ m.reasoning }}</div>
          </details>

          <div v-if="m.content" class="whitespace-pre-wrap">{{ m.content }}</div>

          <div v-if="m.toolRuns && m.toolRuns.length" class="mt-1.5 flex flex-wrap gap-1">
            <el-tag v-for="(tr, i) in m.toolRuns" :key="i" size="small"
                    :type="tr.status === 'ok' ? 'info' : 'danger'" effect="plain">
              已执行工具 {{ tr.name }}
            </el-tag>
          </div>

          <div v-if="m.toolCall" class="mt-2 border border-solid border-[#dcdfe6] rounded-md p-2 bg-white">
            <div class="flex items-center gap-1.5 font-semibold text-[13px]">
              <el-icon color="#409EFF"><Operation /></el-icon>
              <span>{{ toolLabel(m.toolCall.name) }}</span>
              <el-tag v-if="m.toolCall.needConfirm" size="small" type="warning">需确认</el-tag>
              <el-tag v-else size="small" type="success">自动</el-tag>
            </div>
            <div v-if="hasArgs(m.toolCall)" class="mt-1.5 text-xs text-[#909399] break-all">
              <code>{{ formatArgs(m.toolCall.arguments) }}</code>
            </div>
            <div class="mt-2 flex gap-2 justify-end">
              <template v-if="m.toolCall.status === 'pending'">
                <template v-if="m.toolCall.needConfirm">
                  <el-button size="small" :disabled="aiStore.loading"
                             @click="reject(m, m.toolCall)">拒绝</el-button>
                  <el-button size="small" type="primary" :disabled="aiStore.loading"
                             @click="confirm(m, m.toolCall)">确认执行</el-button>
                </template>
                <el-tag v-else size="small" type="info">待自动执行</el-tag>
              </template>
              <el-tag v-else-if="m.toolCall.status === 'running'" size="small" type="success" effect="dark">
                执行中…
              </el-tag>
              <el-tag v-else-if="m.toolCall.status === 'succeeded'" size="small" type="success">已执行</el-tag>
              <el-tag v-else-if="m.toolCall.status === 'rejected'" size="small" type="info">已拒绝</el-tag>
              <el-tag v-else-if="m.toolCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
              <el-tag v-else size="small" type="danger">执行失败</el-tag>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="border-t border-solid border-[#ebeef5] px-3 py-2.5">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <div class="mt-2 flex items-center justify-between">
        <span class="text-xs text-[#c0c4cc]">Enter 发送 · Shift+Enter 换行</span>
        <div>
          <el-button v-if="aiStore.loading" size="small" type="danger" plain @click="aiStore.stop()">
            停止
          </el-button>
          <el-button size="small" type="primary" :loading="aiStore.loading" @click="send">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { ChatDotRound, ArrowRight, Promotion, Operation } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()
const inputText = ref('')
const msgWrapRef = ref(null)

const TOOL_LABELS = {
  navigate_to: '跳转页面',
  get_workspace_state: '读取工作区状态',
  select_config_defs: '选择配置项',
  set_query_conditions: '设置查询条件',
  download_templates: '下载模板',
  download_export_files: '下载导出文件',
  start_export: '启动导出作业',
  start_check: '启动预检查作业',
  start_import: '启动导入作业',
  start_publish: '启动发布作业'
}

function toolLabel(name) {
  return TOOL_LABELS[name] || name
}

function hasArgs(tc) {
  return tc && tc.arguments && Object.keys(tc.arguments).length > 0
}

function formatArgs(args) {
  try { return JSON.stringify(args) } catch (e) { return String(args) }
}

async function send() {
  const text = inputText.value.trim()
  if (!text) return
  inputText.value = ''
  await aiStore.sendChat(text)
}

async function confirm(msg, tc) {
  await aiStore.executeToolCall(msg, tc, false)
}

async function reject(msg, tc) {
  await aiStore.executeToolCall(msg, tc, true)
}

function scrollBottom() {
  nextTick(() => {
    if (msgWrapRef.value) msgWrapRef.value.scrollTop = msgWrapRef.value.scrollHeight
  })
}

watch(
  () => {
    const last = aiStore.messages[aiStore.messages.length - 1]
    return `${aiStore.messages.length}|${last ? last.content.length : 0}|${last && last.toolCall ? last.toolCall.status : ''}`
  },
  () => scrollBottom()
)
watch(() => aiStore.expanded, (v) => { if (v) setTimeout(scrollBottom, 60) })

onMounted(async () => {
  aiStore.startMirror()
  if (!aiStore.messages.length) {
    await aiStore.loadHistory()
  }
  scrollBottom()
})
</script>

<style scoped>
/* 等待回复的三点闪烁动画：CSS keyframes，Tailwind 无对应工具类，保留少量 scoped CSS */
.typing .dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a8abb2;
  margin-right: 4px;
  animation: blink 1.2s infinite;
}
.typing .dot:nth-child(2) { animation-delay: 0.2s; }
.typing .dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.3; }
  40% { opacity: 1; }
}
</style>
