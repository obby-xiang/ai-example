<template>
  <div v-if="!aiStore.expanded" class="collapse-toggle" @click="aiStore.toggleExpand()">
    <el-icon><ChatDotRound /></el-icon>
    <span class="collapse-text">AI 助手</span>
  </div>

  <div v-else class="ai-panel">
    <div class="ai-head">
      <div class="ai-logo">AI</div>
      <div class="ai-head-text">
        <div class="ai-title">实施助手</div>
        <div class="ai-subtitle">流式对话 · 工具调用</div>
      </div>
      <el-button size="small" text @click="aiStore.toggleExpand()" title="收起">
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <div ref="msgWrapRef" class="ai-messages">
      <div v-if="!aiStore.messages.length" class="ai-empty">
        <el-icon :size="36" color="#409EFF"><Promotion /></el-icon>
        <div class="ai-empty-title">您好，我是实施助手</div>
        <div>我可以帮您查询配置项、创建任务、<br />代选配置、下载模板、启动导出/导入作业。</div>
      </div>

      <div v-for="m in aiStore.messages" :key="m.id" class="msg" :class="m.role">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <span v-if="m.pending && !m.content" class="typing">
            <i class="dot"></i><i class="dot"></i><i class="dot"></i>
          </span>

          <details v-if="m.reasoning" class="reasoning">
            <summary>思考过程</summary>
            <div class="reasoning-body">{{ m.reasoning }}</div>
          </details>

          <div v-if="m.content" class="content">{{ m.content }}</div>

          <div v-if="m.toolRuns && m.toolRuns.length" class="tool-runs">
            <el-tag v-for="(tr, i) in m.toolRuns" :key="i" size="small"
                    :type="tr.status === 'ok' ? 'info' : 'danger'" effect="plain">
              已执行工具 {{ tr.name }}
            </el-tag>
          </div>

          <div v-if="m.toolCall" class="tool-card">
            <div class="tool-card-title">
              <el-icon color="#409EFF"><Operation /></el-icon>
              <span>{{ toolLabel(m.toolCall.name) }}</span>
              <el-tag v-if="m.toolCall.needConfirm" size="small" type="warning">需确认</el-tag>
              <el-tag v-else size="small" type="success">自动</el-tag>
            </div>
            <div v-if="hasArgs(m.toolCall)" class="tool-args">
              <code>{{ formatArgs(m.toolCall.arguments) }}</code>
            </div>
            <div class="tool-card-btns">
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
              <el-tag v-else size="small" type="danger">执行失败</el-tag>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="ai-input">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <div class="ai-input-bar">
        <span class="tips">Enter 发送 · Shift+Enter 换行</span>
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
  if (!aiStore.messages.length) {
    await aiStore.loadHistory()
  }
  scrollBottom()
})
</script>

<style scoped>
.collapse-toggle {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 16px;
  gap: 8px;
  cursor: pointer;
  color: #409eff;
}
.collapse-text {
  writing-mode: vertical-rl;
  font-size: 13px;
  letter-spacing: 2px;
}
.ai-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.ai-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid #ebeef5;
}
.ai-logo {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff, #7b61ff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.ai-head-text { flex: 1; min-width: 0; }
.ai-title { font-size: 14px; font-weight: 600; color: #303133; }
.ai-subtitle { font-size: 12px; color: #909399; }
.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.ai-empty {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 32px 12px;
  line-height: 1.8;
}
.ai-empty-title {
  margin: 8px 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: #606266;
}
.msg {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e4e7ed;
  color: #606266;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.msg.user .avatar { background: #409eff; color: #fff; }
.bubble {
  max-width: 82%;
  background: #f4f4f5;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  color: #303133;
  line-height: 1.6;
  word-break: break-word;
}
.msg.user .bubble { background: #d9ecff; }
.content { white-space: pre-wrap; }
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
.reasoning {
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}
.reasoning summary { cursor: pointer; }
.reasoning-body {
  margin-top: 4px;
  padding: 6px 8px;
  background: #fafafa;
  border-left: 2px solid #dcdfe6;
  white-space: pre-wrap;
  max-height: 160px;
  overflow-y: auto;
}
.tool-runs {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.tool-card {
  margin-top: 8px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 8px;
  background: #fff;
}
.tool-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
}
.tool-args {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.tool-card-btns {
  margin-top: 8px;
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.ai-input {
  border-top: 1px solid #ebeef5;
  padding: 10px 12px;
}
.ai-input-bar {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tips { font-size: 12px; color: #c0c4cc; }
</style>
