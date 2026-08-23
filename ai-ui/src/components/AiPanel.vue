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
        <div class="ai-subtitle">结构化工具调用 · DeepSeek v4 flash</div>
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
        <div style="margin-top:12px;font-size:14px;font-weight:600;color:#606266;">您好,我是配置助手！</div>
        <div style="margin-top:8px;">您可以让我:<br/>选择场景、管理配置项、<br/>操作表格、驱动完整流程。</div>
      </div>
      <div v-for="m in aiStore.messages" :key="m.id" class="msg" :class="m.role">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <template v-if="m.pending">
            <span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>
          </template>
          <template v-else>{{ m.content }}</template>

          <!-- ===================== 工具调用卡片：消费主协议 m.toolCalls ===================== -->
          <div v-if="m.toolCalls && m.toolCalls.length" class="ai-actions-list">
            <div v-for="tc in m.toolCalls" :key="tc.callId || uid()" class="ai-action-card"
                 :class="{ 'legacy-card': tc.legacy, 'auto-exec': isAutoExec(tc) }">
              <div class="a-title">
                <span>
                  <el-icon style="vertical-align:-2px;color:#409EFF;"><Operation /></el-icon>
                  {{ tc.title || tc.toolName }}
                  <el-tag v-if="tc.legacy" size="small" style="margin-left:6px;" type="info">兼容</el-tag>
                  <el-tag v-if="isAutoExec(tc)" size="small" style="margin-left:6px;" type="success">AUTO</el-tag>
                  <el-tag v-else-if="tc.requireDoubleConfirm" size="small" style="margin-left:6px;" type="danger">二次确认</el-tag>
                </span>
                <el-tag size="small" :type="toolTagType(tc)">{{ toolTagLabel(tc) }}</el-tag>
              </div>
              <div v-if="tc.impact" class="a-impact">
                <el-icon><WarningFilled /></el-icon> 影响: {{ tc.impact }}
              </div>
              <div class="a-params" v-if="hasVisibleParams(tc)">
                <span class="p-label">参数:</span>
                <code class="p-code">{{ formatArgs(tc) }}</code>
              </div>
              <!-- INPUT 模式：Schema-driven 表单（业界 Adaptive Cards 模式） -->
              <div v-if="tc.mode === 'INPUT' && !isToolDone(m, tc)" class="a-form">
                <SchemaFormRenderer
                  :fields="tc.args?.fields || []"
                  :form-title="tc.args?.formTitle"
                  :submit-label="tc.args?.submitLabel"
                  :disabled="isToolRunning(m, tc)"
                  @submit="(vals) => executeAndResume(m, tc, false, vals)"
                  @cancel="executeAndResume(m, tc, true)"
                />
              </div>
              <div class="a-btns">
                <!-- 已执行 / 已取消 / 失败：显示状态 -->
                <template v-if="isToolDone(m, tc)">
                  <el-tag size="small" :type="toolStatusTagType(m, tc)">
                    {{ toolStatusLabel(m, tc) }}
                  </el-tag>
                </template>
                <!-- INPUT 模式：表单已渲染在上方，仅显示状态提示 -->
                <template v-else-if="tc.mode === 'INPUT'">
                  <el-tag v-if="isToolRunning(m, tc)" size="small" type="success" effect="dark">
                    <el-icon class="el-icon-loading"><Loading /></el-icon> 提交中
                  </el-tag>
                  <el-tag v-else size="small" type="info">请在上方填写并提交</el-tag>
                </template>
                <!-- AUTO 模式：自动执行中，无按钮 -->
                <template v-else-if="isAutoExec(tc)">
                  <el-tag v-if="!isToolRunning(m, tc)" size="small" type="info">待自动执行</el-tag>
                  <el-tag v-else size="small" type="success" effect="dark">
                    <el-icon class="el-icon-loading"><Loading /></el-icon> 自动执行中
                  </el-tag>
                </template>
                <!-- 等待用户操作 -->
                <template v-else-if="resolveNeedConfirm(tc)">
                  <el-button size="small" :loading="isToolRunning(m, tc)" @click="cancelTool(m, tc)">取消</el-button>
                  <el-button size="small" type="primary" :loading="isToolRunning(m, tc)" @click="confirmAndRun(m, tc)">
                    {{ tc.requireDoubleConfirm ? '二次确认执行' : '确认执行' }}
                  </el-button>
                </template>
                <template v-else>
                  <el-button size="small" type="primary" :loading="isToolRunning(m, tc)" @click="runTool(m, tc)">立即执行</el-button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ Phase 3: Plan-and-Execute Plan 卡片 ============ -->
    <div v-if="aiStore.hasPendingPlan" class="ai-plan-card">
      <div class="plan-head">
        <el-icon style="color:#409EFF;"><List /></el-icon>
        执行计划
        <el-tag v-if="aiStore.planFallback" size="small" type="warning" style="margin-left:6px;">兜底</el-tag>
        <el-tag size="small" type="info" style="margin-left:auto;">{{ aiStore.plan.length }} 步</el-tag>
      </div>
      <div v-if="aiStore.planSummary" class="plan-summary">{{ aiStore.planSummary }}</div>
      <div class="plan-steps">
        <div v-for="(s, i) in aiStore.plan" :key="s.stepId || i" class="plan-step">
          <span class="step-order">{{ s.order || i + 1 }}</span>
          <span class="step-desc">{{ s.description || s.stepId }}</span>
          <el-tag v-if="s.autoExec === false" size="small" type="warning">需确认</el-tag>
          <el-tag v-else size="small" type="success">自动</el-tag>
        </div>
      </div>
      <div class="plan-btns">
        <el-button size="small" :disabled="aiStore.loading" @click="cancelPlan">取消</el-button>
        <el-button size="small" type="primary" :loading="aiStore.loading" @click="confirmPlanAndExecute">
          确认执行
        </el-button>
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
          <el-button size="small" :loading="aiStore.loading" @click="generatePlanFromInput()" title="先让 AI 生成执行计划,再由您确认">
            <el-icon style="margin-right:2px;"><List /></el-icon>规划
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
import { ref, watch, nextTick, onMounted, computed } from 'vue'
import { useAiStore } from '@/stores/ai'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import { aiApi } from '@/api/ai'
import { ElMessage, ElMessageBox } from 'element-plus'
import { v4 as uuidv4 } from 'uuid'
import SchemaFormRenderer from '@/components/SchemaFormRenderer.vue'
import {
  getToolEntry,
  resolveNeedConfirm,
  executeFrontendTool
} from '@/utils/frontend-tool-registry'

const aiStore = useAiStore()
const taskStore = useTaskStore()
const configStore = useConfigStore()

const msgWrapRef = ref(null)
const inputRef = ref(null)
const uid = () => uuidv4()

const placeholder = '问我任何问题,例如:"导出A配置项","把字段a小于10的行的c字段改为X"...'

const quickPrompts = [
  '有哪些配置项？',
  '添加配置项',
  '移除全部配置项',
  '下一步怎么做？'
]

/**
 * 已自动触发执行的 callId 集合，防止重复触发（autoExec 工具自动执行去重）。
 * 业界 Cursor Composer 模式：AUTO 工具只触发一次，避免 watch 死循环。
 */
const autoExecutedCallIds = new Set()

// ================================================
//          工具卡片状态展示 + AUTO 判定
// ================================================
function getToolStatus(m, tc) {
  if (!m || !m.toolStatus || !tc) return 'pending'
  return m.toolStatus[tc.callId] || 'pending'
}
function isToolRunning(m, tc) {
  return getToolStatus(m, tc) === 'running'
}
function isToolDone(m, tc) {
  const s = getToolStatus(m, tc)
  return s === 'succeeded' || s === 'cancelled' || s === 'failed'
}
function toolStatusTagType(m, tc) {
  const s = getToolStatus(m, tc)
  if (s === 'succeeded') return 'success'
  if (s === 'cancelled') return 'info'
  if (s === 'failed') return 'danger'
  return 'info'
}
function toolStatusLabel(m, tc) {
  const s = getToolStatus(m, tc)
  if (s === 'succeeded') return '已执行'
  if (s === 'cancelled') return '已取消'
  if (s === 'failed') return '失败'
  return s
}

/**
 * Phase 3: 判断工具是否为 AUTO 模式（自动执行，无需用户点击）。
 * 业界 Cursor Composer / Claude Code 模式：只读/导航/选择类工具 autoExec=true，
 * 后端通过 ToolDiscoveryService 注入，前端据此决定渲染 loading 还是卡片。
 */
function isAutoExec(tc) {
  if (!tc) return false
  return tc.autoExec === true && (tc.mode === 'AUTO' || tc.mode == null || tc.mode === undefined)
}

function toolTagType(tc) {
  if (!tc) return ''
  const entry = getToolEntry(tc.toolName)
  if (entry && entry.labelType) return entry.labelType
  return {
    navigate_step: 'primary', select_definitions: 'success', run_flow: 'warning',
    table_batch_set_field: 'danger', table_delete_rows: 'danger',
    table_replace_values: 'warning', confirm_complete: 'info'
  }[tc.toolName] || ''
}
function toolTagLabel(tc) {
  if (!tc) return ''
  const entry = getToolEntry(tc.toolName)
  if (entry && entry.labelText) return entry.labelText
  return {
    navigate_step: '跳转', select_definitions: '配置项', run_flow: '流程',
    table_batch_set_field: '表格更新', table_delete_rows: '表格删除',
    table_replace_values: '替换', confirm_complete: '完成'
  }[tc.toolName] || tc.toolName
}

function hasVisibleParams(tc) {
  if (!tc || !tc.args) return false
  return Object.keys(tc.args).length > 0
}
function formatArgs(tc) {
  try { return JSON.stringify(tc.args) } catch (e) { return String(tc.args) }
}

// ================================================
//       工具执行入口 —— 执行后自动回灌后端恢复 loop
// ================================================
/**
 * 关键闭环逻辑（响应经验 100029435）：
 *   工具执行后必须把结果回灌给后端 → 后端恢复 agent loop → 可能返回下一轮 toolCalls
 *   如果不回灌，模型永远不知道工具执行结果，agent loop 就断了。
 */
async function executeAndResume(message, tc, userCancelled = false, formData = null) {
  // 必须有 resumeToken 才能回灌（done=true 的旧消息没有，跳过）
  if (!message.resumeToken) {
    // 旧协议消息（done=true），直接执行即可
    if (!userCancelled) {
      try {
        await executeFrontendTool(tc)
      } catch (e) {
        ElMessage.error('执行工具失败: ' + (e?.message || e))
      }
    } else {
      ElMessage.info('已取消动作: ' + (tc.title || tc.toolName))
    }
    return
  }

  // 标记 running
  aiStore.setToolCallStatus(tc.callId, 'running')

  let result
  if (userCancelled) {
    result = { ok: false, message: 'user_cancelled', reason: '用户取消了执行' }
    aiStore.setToolCallStatus(tc.callId, 'cancelled')
  } else if (formData !== null && formData !== undefined) {
    // INPUT 模式（collect_user_input）：直接把用户填写的表单值作为结果回灌，
    // 不调用 executeFrontendTool（表单收集本身无副作用，值即结果）
    result = { ok: true, message: '已收集用户输入', data: formData }
    aiStore.setToolCallStatus(tc.callId, 'succeeded')
  } else {
    try {
      result = await executeFrontendTool(tc)
      // 如果 executeFrontendTool 抛异常或返回 ok=false，标记 failed
      if (result && result.ok === false) {
        aiStore.setToolCallStatus(tc.callId, 'failed')
      } else {
        aiStore.setToolCallStatus(tc.callId, 'succeeded')
      }
    } catch (e) {
      aiStore.setToolCallStatus(tc.callId, 'failed')
      result = { ok: false, message: e?.message || String(e) }
      ElMessage.error('执行工具失败: ' + (e?.message || e))
    }
  }

  // 回灌给后端 → 恢复 agent loop
  // 后端可能返回下一轮 toolCalls（继续暂停）或 done=true 的最终回复
  await aiStore.submitToolResult(message.resumeToken, tc.callId, result)
  scrollBottom()
}

/** 无需确认的工具直接执行 */
async function runTool(message, tc) {
  await executeAndResume(message, tc, false)
}

/**
 * 确认后执行（破坏性 / 批量工具）。
 * Phase 3 改造：支持 requireDoubleConfirm 二次确认弹框（高危流程如 run_flow）。
 *   - requireDoubleConfirm=true：先弹一次 ElMessageBox 说明影响，确认后再弹一次最终确认
 *   - requireDoubleConfirm=false：单次确认即可
 */
async function confirmAndRun(message, tc) {
  try {
    // 第一道：说明影响 + 确认
    const firstMsg = (tc.impact ? tc.impact + '\n\n' : '') +
      (tc.requireDoubleConfirm ? '⚠️ 该操作为高危流程,需要二次确认。\n' : '') +
      '确定执行该操作？\n工具名: ' + tc.toolName
    await ElMessageBox.confirm(
      firstMsg,
      '确认执行 · ' + (tc.title || tc.toolName),
      { type: tc.requireDoubleConfirm ? 'error' : 'warning', confirmButtonText: '继续', cancelButtonText: '取消' }
    )

    // 第二道：高危流程二次确认
    if (tc.requireDoubleConfirm) {
      await ElMessageBox.confirm(
        '⚠️ 最后确认：该操作不可撤销,确认立即执行？\n\n影响: ' + (tc.impact || '无'),
        '二次确认 · ' + (tc.title || tc.toolName),
        { type: 'error', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    }

    await executeAndResume(message, tc, false)
  } catch (e) {
    if (e === 'cancel') {
      // 用户在任意一道 ElMessageBox 点取消 → 走工具取消流程，回灌给后端
      await executeAndResume(message, tc, true)
    } else {
      ElMessage.warning('执行失败: ' + (e?.message || e))
    }
  }
}

/** 用户点击工具卡片上的"取消"按钮（直接取消，不走 ElMessageBox） */
async function cancelTool(message, tc) {
  await executeAndResume(message, tc, true)
}

/**
 * Phase 3: AUTO 模式自动执行入口。
 * 当后端返回 done=false + autoExec=true 的 toolCalls 时，前端自动执行并自动回灌，
 * 无需用户点击。业界 Cursor Composer / Claude Code "auto mode" 核心机制。
 *
 * 去重：通过 autoExecutedCallIds 集合保证每个 callId 只自动执行一次。
 */
async function tryAutoExecute(message) {
  if (!message || message.done || !message.resumeToken) return
  if (!message.toolCalls || !message.toolCalls.length) return
  for (const tc of message.toolCalls) {
    if (!isAutoExec(tc)) continue
    if (autoExecutedCallIds.has(tc.callId)) continue
    // 已执行/取消/失败的不再自动触发
    const st = getToolStatus(message, tc)
    if (st !== 'pending') continue
    autoExecutedCallIds.add(tc.callId)
    // 异步执行，不阻塞 watch
    executeAndResume(message, tc, false).catch(err => {
      console.error('[AiPanel] AUTO 执行失败', tc.toolName, err)
    })
  }
}

// ================================================
//          Plan-and-Execute Plan 卡片
// ================================================
/**
 * 点击"规划"按钮：用当前输入框内容调 /api/ai/plan 生成执行计划。
 * 业界主流 Plan-and-Execute：规划阶段不执行,只生成 plan,用户确认后再执行。
 */
async function generatePlanFromInput(overrideText) {
  if (aiStore.loading) return
  let text = overrideText
  if (text == null && inputRef.value) text = inputRef.value.textContent.trim()
  if (!text) {
    ElMessage.warning('请先输入要规划的目标')
    return
  }
  // 用户输入不立即清空,等确认执行后再清空（保留可编辑）
  await aiStore.generatePlan(text, collectState(), {
    taskId: taskStore.currentTaskId,
    currentScenario: taskStore.scenario || aiStore.currentScenario || null,
    currentStep: taskStore.currentStep || aiStore.currentStep || null
  })
  scrollBottom()
}

/** 用户确认 Plan → 进入执行阶段（调 chat 走 agent loop） */
async function confirmPlanAndExecute() {
  if (!aiStore.plan.length) return
  // 取输入框原文作为执行指令（规划时保留了输入）
  let text = ''
  if (inputRef.value) text = inputRef.value.textContent.trim()
  if (!text) {
    // 输入框已空，用 plan summary 兜底
    text = aiStore.planSummary || '请按计划执行'
  }
  if (inputRef.value) inputRef.value.textContent = ''
  // 清空 plan，进入 EXECUTE 模式
  const planSummary = aiStore.planSummary
  aiStore.clearPlan()
  aiStore.mode = 'EXECUTE'
  // 调 chat 启动 agent loop（携带 scenario/step 上下文）
  await aiStore.chat(text, collectState(), {
    taskId: taskStore.currentTaskId,
    currentScenario: taskStore.scenario || aiStore.currentScenario || null,
    currentStep: taskStore.currentStep || aiStore.currentStep || null
  })
  // 把 plan summary 作为本次执行的注解追加到消息（可选）
  void planSummary
  scrollBottom()
}

/** 用户取消 Plan */
function cancelPlan() {
  aiStore.clearPlan()
  ElMessage.info('已取消执行计划')
}

// ================================================
//                    发送消息
// ================================================
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
  return {
    route: location?.hash ? location.hash : '',
    task: (() => {
      const t = taskStore.currentTask
      if (!t) return null
      return {
        id: t.id,
        name: t.name,
        scenario: t.scenario,
        scenarioName: taskStore.scenarioName,
        currentStep: t.currentStep,
        status: t.status,
        selectedDefIds: taskStore.selectedDefIds,
        selectedDefs: taskStore.selectedDefIds.map(id => {
          const d = configStore.defById(id)
          return d ? { id: d.id, code: d.code, name: d.name } : null
        }).filter(Boolean),
        steps: taskStore.steps
      }
    })(),
    definitions: configStore.enabledDefinitions.map(d => ({ id: d.id, code: d.code, name: d.name }))
  }
}

async function send(overrideText) {
  if (aiStore.loading) return
  let text = overrideText
  if (text == null && inputRef.value) text = inputRef.value.textContent.trim()
  if (inputRef.value) inputRef.value.textContent = ''
  if (!text) return
  await aiStore.chat(text, collectState(), {
    taskId: taskStore.currentTaskId,
    // Phase 2: 附带 scenario+step 让后端动态过滤工具集
    currentScenario: taskStore.scenario || aiStore.currentScenario || null,
    currentStep: taskStore.currentStep || aiStore.currentStep || null
  })
  scrollBottom()
}

function scrollBottom() {
  nextTick(() => {
    if (msgWrapRef.value) msgWrapRef.value.scrollTop = msgWrapRef.value.scrollHeight
  })
}

// 最后一条消息的 done/toolCalls 变化 → 滚动 + 检查 AUTO 自动执行
// 注意：updateLastMsg 是更新已有消息（length 不变），故不能只监听 length。
// 监听签名 length|done|toolCalls.length，同时覆盖"新消息追加"和"同消息更新"两种情况。
watch(() => {
  const last = aiStore.messages[aiStore.messages.length - 1]
  return `${aiStore.messages.length}|${last ? last.done : ''}|${last ? (last.toolCalls ? last.toolCalls.length : 0) : 0}`
}, () => {
  scrollBottom()
  const last = aiStore.messages[aiStore.messages.length - 1]
  if (last && last.role === 'assistant' && last.done === false) {
    tryAutoExecute(last)
  }
})
// toolStatus 变化也触发滚动（执行中状态更新）
watch(() => aiStore.expanded, (v) => { if (v) setTimeout(scrollBottom, 50) })

async function clearAll() {
  try {
    await ElMessageBox.confirm('确定清空当前任务的AI对话历史?', '确认', { type: 'warning' })
    if (taskStore.currentTaskId) {
      await aiApi.clearHistory(taskStore.currentTaskId)
    }
    aiStore.clear()
    autoExecutedCallIds.clear()
  } catch (e) { /* ignore */ }
}

onMounted(scrollBottom)
</script>

<style scoped>
/* INPUT 模式表单容器（Schema-driven 表单卡片，业界 Adaptive Cards 模式） */
.a-form {
  margin-top: 8px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 6px;
  border: 1px dashed #dcdfe6;
}
</style>
