<template>
  <div v-if="!aiStore.expanded" class="collapse-toggle" @click="aiStore.toggleExpand()">
    <span style="display:inline-flex;align-items:center;gap:6px;">
      <el-icon><ChatDotRound /></el-icon> AI助手
    </span>
  </div>

  <template v-else>
    <div class="ai-panel-head">
      <div class="logo">AI</div>
      <div style="flex:1;min-width:0;">
        <div class="ai-title">配置助手</div>
        <div class="ai-subtitle">前端 AI Runtime · Vercel SDK + DeepSeek</div>
      </div>
      <div class="actions">
        <el-button size="small" text type="danger" @click="clearAll" title="清空对话">
          <el-icon><Delete /></el-icon>
        </el-button>
        <el-button size="small" text @click="aiStore.toggleExpand()" title="收起">
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
    </div>

    <div class="ai-messages" ref="msgWrapRef">
      <div v-if="aiStore.messages.length === 0" style="padding:24px 16px;text-align:center;color:#909399;font-size:13px;">
        <el-icon :size="40" color="#409EFF"><Promotion /></el-icon>
        <div style="margin-top:12px;font-size:14px;font-weight:600;color:#606266;">您好,我是配置助手!</div>
        <div style="margin-top:8px;">您可以让我:选择场景、管理配置项、<br/>操作表格、驱动完整流程。</div>
      </div>

      <div v-for="m in aiStore.messages" :key="m.id" class="msg" :class="m.role">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <template v-if="m.pending && !m.content">
            <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
          </template>
          <div v-else class="msg-text">{{ m.content }}</div>

          <!-- 工具卡片 -->
          <div v-if="m.toolCards && m.toolCards.length" class="ai-actions-list">
            <div v-for="tc in m.toolCards" :key="tc.toolCallId" class="ai-action-card"
                 :class="{ 'auto-exec': isAuto(tc.toolName) }">
              <div class="a-title">
                <span>
                  <el-icon style="vertical-align:-2px;color:#409EFF;"><Operation /></el-icon>
                  {{ tc.title || tc.toolName }}
                  <el-tag v-if="isAuto(tc.toolName)" size="small" style="margin-left:6px;" type="success">AUTO</el-tag>
                  <el-tag v-else-if="isDoubleConfirm(tc.toolName)" size="small" style="margin-left:6px;" type="danger">二次确认</el-tag>
                </span>
                <el-tag size="small" :type="tagType(tc)">{{ tagLabel(tc) }}</el-tag>
              </div>
              <div v-if="tc.impact" class="a-impact">
                <el-icon><WarningFilled /></el-icon> 影响: {{ tc.impact }}
              </div>
              <div class="a-params" v-if="hasVisibleParams(tc)">
                <span class="p-label">参数:</span>
                <code class="p-code">{{ formatArgs(tc) }}</code>
              </div>

              <!-- 交互区:当 pendingInteraction 匹配本卡片时渲染 -->
              <div v-if="isInteractionFor(tc)" class="a-interact">
                <!-- 表单交互(collect_user_input) -->
                <SchemaFormRenderer
                  v-if="pending.type === 'form'"
                  :fields="pending.args?.fields || []"
                  :form-title="pending.args?.formTitle"
                  :submit-label="pending.args?.submitLabel"
                  :disabled="false"
                  @submit="onFormSubmit"
                  @cancel="onFormCancel"
                />
                <!-- 确认交互(table_*/run_flow) -->
                <div v-else-if="pending.type === 'confirm'" class="a-confirm">
                  <el-button size="small" :loading="false" @click="onConfirm(false)">取消</el-button>
                  <el-button size="small" type="primary" @click="onConfirm(true)">
                    {{ isDoubleConfirm(tc.toolName) ? '二次确认执行' : '确认执行' }}
                  </el-button>
                </div>
              </div>
              <!-- 已完成:显示状态 -->
              <div v-else-if="isDone(tc)" class="a-status">
                <el-tag size="small" :type="statusTagType(tc)">{{ statusLabel(tc) }}</el-tag>
                <span v-if="tc.result?.message" class="a-result">{{ tc.result.message }}</span>
              </div>
              <!-- 运行中(auto 工具) -->
              <div v-else-if="tc.status === 'running'" class="a-status">
                <el-tag size="small" type="success" effect="dark">
                  <el-icon class="el-icon-loading"><Loading /></el-icon>
                  {{ isAuto(tc.toolName) ? '自动执行中' : '执行中' }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 独立交互卡片:pendingInteraction 未匹配任何卡片时(防竞态) -->
      <div v-if="standaloneInteraction" class="msg assistant">
        <div class="avatar">AI</div>
        <div class="bubble">
          <div class="ai-actions-list">
            <div class="ai-action-card">
              <div class="a-title">
                <span><el-icon style="color:#409EFF;"><Operation /></el-icon> {{ pending?.args?.formTitle || toolTitleFn(pending?.toolName) }}</span>
              </div>
              <SchemaFormRenderer
                v-if="pending?.type === 'form'"
                :fields="pending?.args?.fields || []"
                :form-title="pending?.args?.formTitle"
                :submit-label="pending?.args?.submitLabel"
                @submit="onFormSubmit"
                @cancel="onFormCancel"
              />
              <div v-else-if="pending?.type === 'confirm'" class="a-confirm">
                <el-button size="small" @click="onConfirm(false)">取消</el-button>
                <el-button size="small" type="primary" @click="onConfirm(true)">确认执行</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="ai-input-box">
      <div class="quick">
        <span v-for="q in quickPrompts" :key="q" class="quick-btn" @click="sendQuick(q)">{{ q }}</span>
      </div>
      <div
        ref="inputRef"
        class="input-area"
        contenteditable="plaintext-only"
        :placeholder="placeholder"
        @keydown="handleKeydown"
      ></div>
      <div class="bottom-row">
        <span class="tips">Enter 发送 · Shift+Enter 换行</span>
        <el-button type="primary" size="small" :loading="aiStore.loading" @click="send()">
          发送<el-icon style="margin-left:4px;"><Promotion /></el-icon>
        </el-button>
      </div>
    </div>
  </template>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, computed } from 'vue'
import { useAiStore } from '@/stores/ai'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import { ElMessage, ElMessageBox } from 'element-plus'
import SchemaFormRenderer from '@/components/SchemaFormRenderer.vue'
import { pendingInteraction, resolveInteraction } from '@/tools/runtime'
import {
  isAutoTool, isConfirmTool, getToolMeta, toolTitle as toolTitleFn
} from '@/tools/registry'

const aiStore = useAiStore()
const taskStore = useTaskStore()
const configStore = useConfigStore()

const msgWrapRef = ref(null)
const inputRef = ref(null)

const placeholder = '问我任何问题,例如:"导出A配置项","把字段a小于10的行的c字段改为X"...'
const quickPrompts = ['有哪些配置项？', '添加配置项', '移除全部配置项', '下一步怎么做？']

// pendingInteraction 响应式引用(来自 tools/runtime)
const pending = computed(() => pendingInteraction.value)

// ====== 工具卡片状态判定 ======
function isAuto(name) { return isAutoTool(name) }
function isDoubleConfirm(name) { return !!(getToolMeta(name)?.requireDoubleConfirm) }
function isDone(tc) { return ['succeeded', 'cancelled', 'failed'].includes(tc.status) }
function isInteractionFor(tc) {
  const p = pending.value
  return !!(p && p.toolCallId === tc.toolCallId && !isDone(tc))
}
function tagType(tc) { return getToolMeta(tc.toolName)?.labelType || '' }
function tagLabel(tc) { return getToolMeta(tc.toolName)?.labelText || tc.toolName }
function statusTagType(tc) {
  if (tc.status === 'succeeded') return 'success'
  if (tc.status === 'cancelled') return 'info'
  if (tc.status === 'failed') return 'danger'
  return 'info'
}
function statusLabel(tc) {
  return { succeeded: '已执行', cancelled: '已取消', failed: '失败' }[tc.status] || tc.status
}
function hasVisibleParams(tc) {
  return tc.args && Object.keys(tc.args).length > 0 && !isInteractionFor(tc)
}
function formatArgs(tc) {
  try { return JSON.stringify(tc.args) } catch (e) { return String(tc.args) }
}

// ====== 独立交互卡片(防竞态:pendingInteraction 设置时卡片尚未渲染) ======
const standaloneInteraction = computed(() => {
  const p = pending.value
  if (!p) return false
  // 检查最后一条 assistant 消息是否已有匹配卡片
  const last = aiStore.messages[aiStore.messages.length - 1]
  if (last && last.toolCards?.find(c => c.toolCallId === p.toolCallId)) return false
  return true
})

// ====== 交互回调 ======
function onFormSubmit(vals) {
  resolveInteraction({ ok: true, data: vals })
}
function onFormCancel() {
  resolveInteraction({ cancelled: true })
}
async function onConfirm(confirmed) {
  const p = pending.value
  if (!p) return
  if (!confirmed) {
    resolveInteraction({ confirmed: false })
    return
  }
  // 二次确认(run_flow 等高危流程)
  if (isDoubleConfirm(p.toolName)) {
    try {
      await ElMessageBox.confirm(
        '⚠️ 最后确认:该操作不可撤销,确认立即执行?\n\n影响: ' + (p.impact || '无'),
        '二次确认 · ' + toolTitleFn(p.toolName),
        { type: 'error', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    } catch (e) {
      // 用户在二次确认弹框取消
      resolveInteraction({ confirmed: false })
      return
    }
  } else {
    // 单次确认弹框(说明影响)
    try {
      await ElMessageBox.confirm(
        (p.impact ? p.impact + '\n\n' : '') + '确定执行该操作?',
        '确认执行 · ' + toolTitleFn(p.toolName),
        { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' }
      )
    } catch (e) {
      resolveInteraction({ confirmed: false })
      return
    }
  }
  resolveInteraction({ confirmed: true })
}

// ====== 发送消息 ======
function sendQuick(text) {
  if (inputRef.value) inputRef.value.textContent = text
  send(text)
}
function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
function collectState() {
  return typeof window.__collectWorkspaceState === 'function'
    ? window.__collectWorkspaceState()
    : {}
}
async function send(overrideText) {
  if (aiStore.loading) return
  let text = overrideText
  if (text == null && inputRef.value) text = inputRef.value.textContent.trim()
  if (inputRef.value) inputRef.value.textContent = ''
  if (!text) return
  await aiStore.chat(text, collectState(), {})
  scrollBottom()
}
function scrollBottom() {
  nextTick(() => {
    if (msgWrapRef.value) msgWrapRef.value.scrollTop = msgWrapRef.value.scrollHeight
  })
}

// 消息变化/pendingInteraction 变化 → 滚动
watch(() => aiStore.messages.length, scrollBottom)
watch(() => {
  const last = aiStore.messages[aiStore.messages.length - 1]
  return `${aiStore.messages.length}|${last?.content?.length || 0}|${last?.toolCards?.length || 0}`
}, scrollBottom)
watch(pending, scrollBottom)
watch(() => aiStore.expanded, (v) => { if (v) setTimeout(scrollBottom, 50) })

async function clearAll() {
  try {
    await ElMessageBox.confirm('确定清空当前任务的AI对话历史?', '确认', { type: 'warning' })
    aiStore.clear()
    ElMessage.success('已清空对话')
  } catch (e) { /* ignore */ }
}

onMounted(scrollBottom)
</script>

<style scoped>
.a-interact { margin-top: 8px; }
.a-confirm { display: flex; gap: 8px; justify-content: flex-end; }
.a-status { margin-top: 6px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.a-result { font-size: 12px; color: #606266; }
.collapse-toggle {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  cursor: pointer; color: #409EFF; font-size: 13px; writing-mode: vertical-lr;
}
</style>
