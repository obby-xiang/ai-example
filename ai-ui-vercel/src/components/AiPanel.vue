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
                  :disabled="isPendingExpired"
                  @submit="onFormSubmit"
                  @cancel="onFormCancel"
                />
                <!-- 确认交互(table_*/run_flow) -->
                <div v-else-if="pending.type === 'confirm'" class="a-confirm">
                  <el-button size="small" :loading="false" :disabled="isPendingExpired" @click="onConfirm(false)">取消</el-button>
                  <el-button size="small" type="primary" :disabled="isPendingExpired" @click="onConfirm(true)">
                    {{ isDoubleConfirm(tc.toolName) ? '二次确认执行' : '确认执行' }}
                  </el-button>
                </div>
                <!-- 改造 C3：pending 倒计时（AG-UI 准则：超时有可见性） -->
                <div class="a-ttl">
                  <template v-if="!isPendingExpired">
                    <el-icon><Clock /></el-icon> 交互剩余 {{ pendingRemaining }}，超时将自动取消
                  </template>
                  <el-tag v-else size="small" type="danger">已过期，请重新发起</el-tag>
                </div>
                <!-- 改造 D4：pending 期间工作区被手动修改的轻提示条 -->
                <div v-if="workspaceChangedDuringPending" class="a-changed">
                  <el-icon><WarningFilled /></el-icon> 等待期间工作区有变更，AI 将在继续前重新获取最新状态
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
                :disabled="isPendingExpired"
                @submit="onFormSubmit"
                @cancel="onFormCancel"
              />
              <div v-else-if="pending?.type === 'confirm'" class="a-confirm">
                <el-button size="small" :disabled="isPendingExpired" @click="onConfirm(false)">取消</el-button>
                <el-button size="small" type="primary" :disabled="isPendingExpired" @click="onConfirm(true)">确认执行</el-button>
              </div>
              <!-- 改造 C3：pending 倒计时（独立卡片同样展示） -->
              <div class="a-ttl">
                <template v-if="!isPendingExpired">
                  <el-icon><Clock /></el-icon> 交互剩余 {{ pendingRemaining }}，超时将自动取消
                </template>
                <el-tag v-else size="small" type="danger">已过期，请重新发起</el-tag>
              </div>
              <!-- 改造 D4：pending 期间工作区变更轻提示 -->
              <div v-if="workspaceChangedDuringPending" class="a-changed">
                <el-icon><WarningFilled /></el-icon> 等待期间工作区有变更，AI 将在继续前重新获取最新状态
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
        <div style="display:flex;gap:6px;">
          <!-- 改造 F1：停止按钮（生成中/等待交互中可见）。协作式取消：轮次边界停止+保留现场，不回滚已完成操作 -->
          <el-button v-if="aiStore.loading || pending" size="small" type="danger" plain
                     @click="aiStore.stopRun()" title="停止当前生成/等待的交互(已完成的内容保留)">
            <el-icon style="margin-right:2px;"><CircleClose /></el-icon>停止
          </el-button>
          <el-button type="primary" size="small" :loading="aiStore.loading" @click="send()">
            发送<el-icon style="margin-left:4px;"><Promotion /></el-icon>
          </el-button>
        </div>
      </div>
    </div>
  </template>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue'
import { useAiStore } from '@/stores/ai'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import { ElMessage, ElMessageBox } from 'element-plus'
import SchemaFormRenderer from '@/components/SchemaFormRenderer.vue'
import { pendingInteraction, resolveInteraction } from '@/tools/runtime'
import {
  isAutoTool, isConfirmTool, getToolMeta, toolTitle as toolTitleFn
} from '@/tools/registry'
import { dataVersionRef, getDataVersion } from '@/utils/workspace-version'

const aiStore = useAiStore()
const taskStore = useTaskStore()
const configStore = useConfigStore()

const msgWrapRef = ref(null)
const inputRef = ref(null)

const placeholder = '问我任何问题,例如:"导出A配置项","把字段a小于10的行的c字段改为X"...'
const quickPrompts = ['有哪些配置项？', '添加配置项', '移除全部配置项', '下一步怎么做？']

// pendingInteraction 响应式引用(来自 tools/runtime)
const pending = computed(() => pendingInteraction.value)

// ================================================
//   改造 C3：pending 交互倒计时（AG-UI 准则：超时有可见性）
//   pending.expiresAt 由 runtime 在挂起/恢复时设置（TTL 10 分钟）。
// ================================================
const nowTs = ref(Date.now())
let ttlTimer = null

/** 是否已过期（超时 runtime 会以 interaction_expired 自动解决/标记） */
const isPendingExpired = computed(() => {
  const p = pending.value
  if (!p || !p.expiresAt) return false
  return p.expiresAt - nowTs.value <= 0
})
/** 格式化剩余时间：mm分ss秒 / ss秒 */
const pendingRemaining = computed(() => {
  const p = pending.value
  if (!p || !p.expiresAt) return ''
  const s = Math.max(0, Math.ceil((p.expiresAt - nowTs.value) / 1000))
  const mm = Math.floor(s / 60)
  const ss = s % 60
  return mm > 0 ? `${mm}分${String(ss).padStart(2, '0')}秒` : `${ss}秒`
})

// ================================================
//   改造 D4：pending 期间「工作区有变更」轻提示（可选 UX）
//   pending 开始时快照 dataVersion；订阅 dataVersionRef，变化则提示。
//   仅提示不阻断——回灌时 D3d 会在结果前置系统提示强制 AI 现查。
// ================================================
const pendingStartVersion = ref(null)
watch(
  () => pending.value?.toolCallId || null,
  (key) => {
    const p = pending.value
    pendingStartVersion.value = key ? (p?.suspendedDataVersion ?? getDataVersion()) : null
  },
  { immediate: true }
)
const workspaceChangedDuringPending = computed(() =>
  pendingStartVersion.value != null && dataVersionRef.value !== pendingStartVersion.value
)

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
/**
 * 统一交互结果出口。
 * 改造 B4：恢复态交互（p.recovered，刷新后旧 Promise 已销毁）不走 resolveInteraction,
 * 改调 aiStore.submitRecoveredInteraction 回填卡片终态并重开一轮 loop。
 */
function submitInteractionResult(result) {
  const p = pending.value
  if (p && p.recovered) {
    aiStore.submitRecoveredInteraction(result, collectState())
    return
  }
  resolveInteraction(result)
}
function onFormSubmit(vals) {
  submitInteractionResult({ ok: true, data: vals })
}
function onFormCancel() {
  submitInteractionResult({ cancelled: true })
}
async function onConfirm(confirmed) {
  const p = pending.value
  if (!p) return
  if (!confirmed) {
    submitInteractionResult({ confirmed: false })
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
      submitInteractionResult({ confirmed: false })
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
      submitInteractionResult({ confirmed: false })
      return
    }
  }
  submitInteractionResult({ confirmed: true })
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

onMounted(() => {
  scrollBottom()
  // 改造 C3：秒级刷新倒计时显示
  ttlTimer = setInterval(() => { nowTs.value = Date.now() }, 1000)
})
onBeforeUnmount(() => { if (ttlTimer) { clearInterval(ttlTimer); ttlTimer = null } })
</script>

<style scoped>
.a-interact { margin-top: 8px; }
.a-confirm { display: flex; gap: 8px; justify-content: flex-end; }
.a-status { margin-top: 6px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.a-result { font-size: 12px; color: #606266; }
/* 改造 C3：pending 倒计时 */
.a-ttl {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
  display: flex;
  align-items: center;
  gap: 4px;
}
/* 改造 D4：pending 期间工作区变更轻提示条 */
.a-changed {
  margin-top: 6px;
  font-size: 12px;
  color: #b88230;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 4px 8px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.collapse-toggle {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  cursor: pointer; color: #409EFF; font-size: 13px; writing-mode: vertical-lr;
}
</style>
