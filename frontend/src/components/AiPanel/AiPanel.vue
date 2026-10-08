<template>
  <!-- 收起态：48px 竖排条（蓝本 AiPanel.vue:2-7） -->
  <div
    v-if="!ai.expanded"
    class="h-full flex flex-col items-center pt-4 gap-2 cursor-pointer text-[#409eff]"
    @click="ai.toggleExpand()"
  >
    <el-icon><ChatDotRound /></el-icon>
    <span class="writing-vertical text-[13px]">AI 助手</span>
  </div>

  <div v-else class="h-full flex flex-col">
    <!-- 头部（蓝本 AiPanel.vue:10-19） -->
    <div class="flex items-center gap-2.5 px-3 py-2.5 border-b border-solid border-[#ebeef5]">
      <div class="w-[34px] h-[34px] rounded-lg bg-gradient-to-br from-[#409eff] to-[#7b61ff] text-white flex items-center justify-center font-bold">AI</div>
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-[#303133]">实施助手</div>
        <div class="text-xs text-[#909399]">流式对话 · 工具调用</div>
      </div>
      <el-button size="small" text title="收起" @click="ai.toggleExpand()">
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <!-- 会话 / 工作区上下文条（本仓差异：会话恢复与契约联动状态可视化，蓝本无此行） -->
    <div class="px-3 py-1.5 text-xs text-[#909399] border-b border-solid border-[#ebeef5] bg-aiPanel">
      <div class="flex items-center gap-1.5 flex-wrap">
        <el-tag size="small" :type="healthTagType" effect="plain">{{ healthLabel }}</el-tag>
        <span>会话 {{ shortSessionId }}</span>
        <span>·</span>
        <span class="truncate">工作区 {{ workspace.contextSummary }}</span>
        <span>·</span>
        <span>数据版本 {{ workspace.dataVersion }}</span>
        <el-button link size="small" @click="probe">重新检测</el-button>
      </div>
      <div v-if="ai.contextSync" class="mt-0.5 truncate">
        工作区已同步：{{ ai.contextSync.summary }}（v{{ ai.contextSync.version }} · {{ ai.contextSync.source }}）
      </div>
    </div>

    <!-- 降级与冲突提示（EP Alert；503/409 各有专属动作） -->
    <el-alert
      v-if="ai.busyConflict"
      class="m-2"
      type="error"
      :closable="false"
      show-icon
      title="当前会话正在进行中"
      :description="`该会话已有一轮在进行（runId=${ai.busyConflict.runId}），可一键重挂继续接收这一轮的输出。`"
    >
      <el-button size="small" type="primary" class="mt-1" @click="reattach()">一键重挂收流</el-button>
    </el-alert>

    <el-alert
      v-if="ai.poolSaturated"
      class="m-2"
      type="error"
      :closable="false"
      show-icon
      title="挂起池已饱和"
      :description="`挂起专用线程池容量 ${ai.poolSaturated.poolSize} 已用满，请等当前挂起轮次确认/超时后再试。`"
    />

    <el-alert
      v-if="!ai.available"
      class="m-2"
      type="warning"
      :closable="false"
      show-icon
      title="AI 能力暂不可用（已降级，业务功能不受影响）"
      :description="degradeReason"
    />

    <el-alert
      v-if="ai.streamInterrupted"
      class="m-2"
      type="error"
      :closable="false"
      show-icon
      title="连接中断，结果可能不完整"
      description="本轮没有收到完成帧（done/error）。已生成的内容保留在上方，可重新发送或一键重挂该轮。"
    >
      <el-button v-if="ai.activeRunId" size="small" type="primary" class="mt-1" @click="reattach(ai.activeRunId)">
        一键重挂该轮
      </el-button>
    </el-alert>

    <el-alert
      v-if="ai.notice"
      class="m-2"
      :type="noticeType"
      :closable="false"
      show-icon
      :title="ai.notice"
    />

    <el-alert
      v-if="ai.lastUiEventResult"
      class="m-2"
      :type="ai.lastUiEventResult.handled ? 'info' : 'warning'"
      :closable="false"
      show-icon
      :title="`AI 驱动工作区：${ai.lastUiEventResult.message}`"
    />

    <!-- 消息流（蓝本 AiPanel.vue:21-89） -->
    <div ref="msgWrapRef" class="flex-1 overflow-y-auto p-3">
      <el-empty v-if="!ai.messages.length" :image-size="60">
        <template #image>
          <el-icon :size="36" color="#409EFF"><Promotion /></el-icon>
        </template>
        <template #description>
          <div class="mb-1 text-sm font-semibold text-[#606266]">您好，我是实施助手</div>
          <div class="text-[13px] text-[#909399] leading-[1.8]">
            我可以帮您查询配置项与字段、查看任务、预估数据行数、<br />
            打开导出结果在线编辑器、下载导出文件，<br />并启动导出/预检查/导入/发布作业。
          </div>
        </template>
      </el-empty>

      <div
        v-for="m in ai.messages"
        :key="m.id"
        class="flex gap-2 mb-3.5"
        :class="m.role === 'user' ? 'flex-row-reverse' : ''"
      >
        <div
          class="w-7 h-7 rounded-full text-xs flex items-center justify-center shrink-0"
          :class="m.role === 'user' ? 'bg-[#409eff] text-white' : 'bg-[#e4e7ed] text-[#606266]'"
        >
          {{ m.role === 'user' ? '我' : 'AI' }}
        </div>
        <div
          class="max-w-[82%] rounded-lg px-2.5 py-2 text-[13px] text-[#303133] leading-[1.6] break-words"
          :class="m.role === 'user' ? 'bg-[#d9ecff]' : 'bg-[#f4f4f5]'"
        >
          <!-- 等待回复的三点闪烁（仅在正文尚未产出时） -->
          <span v-if="isTyping(m)" class="typing">
            <i class="dot"></i><i class="dot"></i><i class="dot"></i>
          </span>

          <!-- 思考链折叠：**有 reasoning 帧才渲染**（裁决⑦，无帧则整块不出现） -->
          <details v-if="m.reasoning" class="mb-1.5 text-xs text-[#909399]">
            <summary class="cursor-pointer">思考过程</summary>
            <div class="mt-1 px-2 py-1.5 bg-[#fafafa] border-l-2 border-solid border-[#dcdfe6] whitespace-pre-wrap max-h-40 overflow-y-auto">{{ m.reasoning }}</div>
          </details>

          <div v-if="m.content" class="whitespace-pre-wrap">{{ m.content }}</div>

          <!-- 工具卡（四态 + 参数 + 结果） -->
          <div v-for="run in m.toolRuns" :key="run.toolCallId" class="mt-1.5 border border-solid border-[#dcdfe6] rounded-md p-2 bg-white">
            <div class="flex items-center gap-1.5 font-semibold text-[13px] flex-wrap">
              <el-icon color="#409EFF"><Operation /></el-icon>
              <span>{{ toolLabel(run.name) }}</span>
              <el-tag v-if="run.kind === 'FRONTEND'" size="small" effect="plain">前端工具</el-tag>
              <el-tag v-else-if="run.kind === 'CONFIRM'" size="small" type="warning" effect="plain">确认门</el-tag>
              <el-tag :size="'small'" :type="toolTagType(run.status)" :effect="run.status === 'running' ? 'dark' : 'light'">
                {{ toolStatusLabel(run.status) }}
              </el-tag>
            </div>
            <div v-if="run.args" class="mt-1.5 text-xs text-[#909399] break-all">
              <code>{{ run.args }}</code>
            </div>
            <div v-if="run.result" class="mt-1.5 px-2 py-1 text-xs text-[#606266] bg-[#fafafa] rounded max-h-24 overflow-y-auto whitespace-pre-wrap">{{ run.result }}</div>
            <div v-if="run.reason" class="mt-1 text-xs text-[#f56c6c]">原因：{{ run.reason }}</div>
          </div>

          <!-- 挂起卡片：HITL 确认（确认/拒绝双路径）或待前端执行 -->
          <div v-if="m.pendingCall" class="mt-2 border border-solid border-[#dcdfe6] rounded-md p-2 bg-white">
            <div class="flex items-center gap-1.5 font-semibold text-[13px] flex-wrap">
              <el-icon color="#409EFF"><Operation /></el-icon>
              <span>{{ toolLabel(m.pendingCall.name) }}</span>
              <el-tag v-if="m.pendingCall.kind === 'CONFIRM'" size="small" type="warning">需确认</el-tag>
              <el-tag v-else size="small" type="success">自动</el-tag>
              <el-tag v-if="m.pendingCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
            </div>
            <div v-if="argsText(m.pendingCall.args)" class="mt-1.5 text-xs text-[#909399] break-all">
              <code>{{ argsText(m.pendingCall.args) }}</code>
            </div>

            <template v-if="m.pendingCall.kind === 'CONFIRM' && m.pendingCall.status === 'pending'">
              <div class="mt-1.5 flex items-center gap-2 text-xs">
                <span v-if="remainingSeconds !== null" :class="confirmExpired ? 'text-[#c0c4cc]' : 'text-[#e6a23c]'">
                  {{ confirmExpired ? '确认已超时（按钮已置灰），本轮将按拒绝/超时收尾' : `剩余确认时间 ${formatCountdown(remainingSeconds)}` }}
                </span>
                <span v-else class="text-[#909399]">等待人工确认</span>
              </div>
              <el-input
                v-model="rejectReason"
                type="textarea"
                :rows="2"
                resize="none"
                class="mt-2"
                placeholder="拒绝原因（可选，填了会原样回填给模型）"
              />
              <div class="mt-2 flex gap-2 justify-end">
                <el-button size="small" :disabled="confirmExpired || deciding" @click="decide(false)">拒绝</el-button>
                <el-button size="small" type="primary" :disabled="confirmExpired || deciding" @click="decide(true)">确认执行</el-button>
              </div>
            </template>

            <div v-else-if="m.pendingCall.kind === 'FRONTEND' && m.pendingCall.name === 'generative_form'" class="mt-2">
              <!-- GF-B：生成式表单挂起 —— 渲染 FormRenderer 等用户填写（同 toolCallId 可重发/取消） -->
              <!-- T2a-D#3：同 toolCallId 至多一个 FormRenderer —— 只渲染在宿主消息（最后一条
                持该挂起调用的消息）上，其余消息的挂起卡只出卡头/终态 tag -->
              <FormRenderer
                v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId && m.id === formHostMessageId"
                :key="liveForm.toolCallId"
                :schema="liveForm.form"
                :submitting="liveForm.status === 'submitting'"
                :error="liveForm.error"
                :remaining-seconds="formRemainingSeconds"
                @submit="onFormSubmit"
                @cancel="onFormCancel"
              />
              <div v-else class="flex flex-col items-end gap-1">
                <!-- T2a-D#2：帧终态由 pendingCall 投影承载（含刷新后镜像里的终态残留），按终态展示 -->
                <el-tag v-if="m.pendingCall.status === 'cancelled'" size="small" type="info">表单已取消</el-tag>
                <el-tag v-else-if="m.pendingCall.status === 'succeeded'" size="small" type="success">表单已提交</el-tag>
                <el-tag v-else-if="m.pendingCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
                <el-tag v-else-if="m.pendingCall.status === 'rejected' || m.pendingCall.status === 'blocked'" size="small" type="warning">表单未提交</el-tag>
                <el-tag v-else-if="ai.activeForm?.status === 'cancelled'" size="small" type="info">表单已取消</el-tag>
                <el-tag v-else-if="ai.activeForm?.status === 'submitted'" size="small" type="success">表单已提交，等待回执</el-tag>
                <el-tag v-else size="small" type="info">表单状态已丢失（刷新后无法续填），等待后端超时收敛</el-tag>
                <!-- 红队问题 5：取消回灌失败的提示在 cancelled 态下也要可见 -->
                <div v-if="ai.activeForm?.status === 'cancelled' && ai.activeForm.error" class="text-xs text-[#f56c6c]">
                  {{ ai.activeForm.error }}
                </div>
              </div>
            </div>

            <div v-else-if="m.pendingCall.kind === 'FRONTEND'" class="mt-2 flex justify-end">
              <el-tag v-if="m.pendingCall.status === 'pending'" size="small" type="info">待浏览器自动执行</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'running'" size="small" type="success" effect="dark">执行中…</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'rejected'" size="small" type="info">已拒绝</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'cancelled'" size="small" type="info">已取消</el-tag>
              <!-- T2a 返修（S6）：新增 status 显式给口径 —— blocked 是"越 scope 未执行"，
                   不得落 else 的"已执行"（与 generative_form 分支的 rejected||blocked 口径对齐） -->
              <el-tag v-else-if="m.pendingCall.status === 'succeeded'" size="small" type="success">已执行</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'blocked'" size="small" type="warning">未执行（超出范围）</el-tag>
              <el-tag v-else size="small" type="success">已执行</el-tag>
            </div>

            <div v-else class="mt-2 flex justify-end">
              <el-tag v-if="m.pendingCall.status === 'running'" size="small" type="success" effect="dark">执行中…</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'approved'" size="small" type="success">已放行</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'rejected'" size="small" type="warning">已拒绝</el-tag>
              <el-tag v-else-if="m.pendingCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
              <el-tag v-else size="small" type="danger">执行失败</el-tag>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区（蓝本 AiPanel.vue:91-109） -->
    <div class="border-t border-solid border-[#ebeef5] px-3 py-2.5">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="3"
        resize="none"
        :disabled="!ai.canSend"
        :placeholder="inputPlaceholder"
        @keydown.enter.exact.prevent="send"
      />
      <div class="mt-2 flex items-center justify-between">
        <span class="text-xs" :class="ai.loading ? 'text-[#e6a23c]' : 'text-[#c0c4cc]'">
          {{ ai.loading ? ai.runStateLabel : 'Enter 发送 · Shift+Enter 换行' }}
        </span>
        <div>
          <el-button v-if="ai.loading" size="small" type="danger" plain @click="stop">停止</el-button>
          <el-button size="small" type="primary" :loading="ai.loading" :disabled="!canSubmit" @click="send">发送</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * AI 面板（S4.4c 完整实现，视觉 1:1 还原 kimi-k3 `AiPanel.vue`）。
 *
 * 对齐蓝本的部分（布局/色板/组件选型/间距/字体层级，逐块对应蓝本行号见模板注释）：
 * 收起态竖排条、头部徽标与标题、消息气泡与头像、思考过程折叠、工具卡边框与状态区、
 * 输入区 textarea 与发送/停止按钮、等待回复的三点闪烁动画。
 *
 * 本仓功能增量（逐条理由，均属"功能差异"）：
 * - 上下文条：会话 id / 工作区上下文 / 数据版本 / 可用性 tag / 契约同步 chip —— 蓝本无
 *   `/api/ai/health`、无契约事件，本仓需要可视化"刷新恢复"与"工作区已同步"的状态；
 * - 工具卡四态（调用中/成功/失败/等待确认）并展示参数与结果 —— 蓝本是"已执行工具 X"单标签；
 * - 生成式表单挂起卡（GF-B）：generative_form 帧渲染 FormRenderer 等用户填写，取消/超时带
 *   cancelled:true 回灌 —— 蓝本无此前端工具通道；
 * - HITL 卡片：确认/拒绝双路径 + 拒绝原因输入 + 超时倒计时置灰 —— 蓝本有双按钮但无原因与倒计时；
 * - 409/503/断流三类降级提示（EP Alert）与一键重挂 —— 蓝本是蓝本后端（无 SESSION_BUSY/挂起池）。
 *
 * 裁剪性：本组件只依赖 stores/ai 与 stores/workspace 的窄接口；App.vue 收起 AI 栏
 * （48px 竖排条）或整体移除本组件后，五页业务功能全部照常可用。
 *
 * 样式纪律：模板全部用 Element Plus + 内联 Tailwind 工具类；唯一的 scoped CSS 是
 * 蓝本确有的 18 行打字机动画（`.typing .dot` + `@keyframes blink`，Tailwind 无对应工具类）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, ChatDotRound, Operation, Promotion } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatCountdown } from '@/utils/format'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import type { PendingToolCall, ToolRunStatus } from '@/types/ai'

const ai = useAiStore()
const workspace = useWorkspaceStore()

const inputText = ref('')
const rejectReason = ref('')
const deciding = ref(false)
const msgWrapRef = ref<HTMLElement | null>(null)

/** 倒计时节拍（每秒推进一次；确认卡据此置灰）。 */
const now = ref(Date.now())
const confirmDeadline = ref<number | null>(null)
let tickTimer: ReturnType<typeof setInterval> | null = null

/** 工具名 → 中文（后端工具全集 + 前端工具/页面能力的可读名）。 */
const TOOL_LABELS: Record<string, string> = {
  list_config_defs: '列出配置定义',
  get_config_def: '读取配置定义',
  list_tasks: '列出任务',
  get_workspace_state: '读取工作区状态',
  check_job_status: '查询作业状态',
  get_row_count: '预估数据行数',
  start_export: '启动导出作业',
  start_precheck: '启动预检查作业',
  start_import: '启动导入作业',
  start_publish: '启动发布作业',
  open_export_file_editor: '打开导出文件在线编辑器',
  download_export_file: '下载导出文件',
  navigate_to: '跳转页面',
  generative_form: '生成式表单',
  select_config_defs: '选择配置项',
  set_query_conditions: '设置查询条件',
  download_templates: '下载模板',
  'export.selectDefs': '选择配置项',
  'export.setConditions': '设置查询条件',
  'export.start': '启动导出作业',
  'export.openEditor': '打开在线编辑器',
  'import.selectDefs': '选择配置项',
  'import.upload': '上传文件',
  'import.startPrecheck': '启动预检查作业',
  'import.startImport': '启动导入作业',
  'import.startPublish': '启动发布作业'
}

const shortSessionId = computed(() => ai.sessionId.slice(0, 8))

const healthLabel = computed(() => {
  if (ai.health === null && ai.healthError === null) {
    return '未检测'
  }
  return ai.available ? '可用' : '已降级'
})

const healthTagType = computed<'success' | 'warning' | 'info'>(() => {
  if (ai.health === null && ai.healthError === null) {
    return 'info'
  }
  return ai.available ? 'success' : 'warning'
})

const degradeReason = computed(() => ai.unavailableReason ?? '未检测到 AI 可用性，请点击「重新检测」。')

const noticeType = computed<'info' | 'warning' | 'error'>(() => {
  if (ai.streamInterrupted) {
    return 'error'
  }
  return ai.loading ? 'info' : 'warning'
})

const canSubmit = computed(() => ai.canSend && ai.available && inputText.value.trim().length > 0)

const inputPlaceholder = computed(() => {
  if (!ai.available) {
    return 'AI 当前不可用（已降级，可继续使用页面手动操作）'
  }
  if (ai.suspended || ai.pendingToolCall) {
    return '挂起等待中：请先在上方处理确认/执行，或点「停止」结束本轮'
  }
  return '输入消息，Enter 发送，Shift+Enter 换行'
})

const remainingSeconds = computed(() => {
  if (confirmDeadline.value === null) {
    return null
  }
  return Math.max(0, Math.ceil((confirmDeadline.value - now.value) / 1000))
})

const confirmExpired = computed(() => remainingSeconds.value !== null && remainingSeconds.value <= 0)

/**
 * GF-B：当前可交互的生成式表单（填写中/提交中才渲染 FormRenderer；
 * 结局帧到达后 activeForm 被清空，挂起卡随 pendingCall 一并消失）。
 */
const liveForm = computed(() => {
  const form = ai.activeForm
  return form && (form.status === 'filling' || form.status === 'submitting') ? form : null
})

/**
 * T2a-D#3：FormRenderer 的宿主消息 —— 最后一条持该 toolCallId 且**仍在途**的挂起投影的消息。
 * 同一 toolCallId 的挂起可能残留在多条消息上（历史/重挂形态），但表单只渲染一份。
 * T2a 返修（S5）：宿主候选限定 pending/running/approved —— 终态残留消息
 * （cancelled/succeeded/blocked/expired）不作宿主，防表单挂到已决消息上。
 */
const formHostMessageId = computed(() => {
  const toolCallId = liveForm.value?.toolCallId
  if (!toolCallId) {
    return null
  }
  for (let i = ai.messages.length - 1; i >= 0; i -= 1) {
    const pending = ai.messages[i]?.pendingCall
    if (pending?.toolCallId === toolCallId
      && (pending.status === 'pending' || pending.status === 'running' || pending.status === 'approved')) {
      return ai.messages[i].id
    }
  }
  return null
})

/** 表单剩余填写秒数（store 的本地超时计时器同口径：deadline − now）。 */
const formRemainingSeconds = computed(() => {
  const form = liveForm.value
  if (!form || form.deadline === null) {
    return null
  }
  return Math.max(0, Math.ceil((form.deadline - now.value) / 1000))
})

/** 等待回复：正文与工具卡都还没产出时的三点闪烁。 */
function isTyping(message: { streaming: boolean; content: string }): boolean {
  return message.streaming && message.content.length === 0
}

function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

/** 工具卡状态色（补丁③：REJECTED=warning「已拒绝」，FAILED=danger「执行失败」，互不混用）。 */
function toolTagType(status: ToolRunStatus): 'info' | 'warning' | 'success' | 'danger' | 'primary' {
  switch (status) {
    case 'running':
    case 'succeeded':
      return 'success'
    case 'failed':
      return 'danger'
    case 'rejected':
    // DC-14 T1：被防线③拦下（越 scope 未执行）与"人拒绝"同色（warning）——
    // 两者都是"没有执行"，与后端执行失败（danger）语义不同
    case 'blocked':
      return 'warning'
    case 'expired':
      return 'info'
    default:
      return 'primary'
  }
}

function toolStatusLabel(status: ToolRunStatus): string {
  const labels: Record<ToolRunStatus, string> = {
    pending: '等待确认',
    running: '调用中…',
    succeeded: '已成功',
    failed: '执行失败',
    rejected: '已拒绝',
    // DC-14 T1：越出当前披露范围（防线③），工具未执行
    blocked: '超出当前范围',
    expired: '会话已失效'
  }
  return labels[status]
}

function argsText(args: Record<string, unknown> | null): string {
  if (!args || Object.keys(args).length === 0) {
    return ''
  }
  try {
    return JSON.stringify(args)
  } catch {
    return String(args)
  }
}

function probe(): void {
  void ai.probeHealth()
}

function scrollBottom(): void {
  void nextTick(() => {
    const wrap = msgWrapRef.value
    if (wrap) {
      wrap.scrollTop = wrap.scrollHeight
    }
  })
}

async function send(): Promise<void> {
  const text = inputText.value.trim()
  if (!text || !ai.canSend) {
    return
  }
  inputText.value = ''
  await ai.send(text)
}

function stop(): void {
  void ai.stop()
}

function reattach(runId?: unknown): void {
  // 传进来的可能是点击事件对象（模板里若漏写括号），一律只接受字符串
  void ai.reattachActive(typeof runId === 'string' ? runId : undefined)
}

async function decide(approved: boolean): Promise<void> {
  const reason = approved ? undefined : (rejectReason.value.trim() || '用户拒绝执行')
  deciding.value = true
  try {
    rejectReason.value = ''
    await ai.confirm(approved, reason)
  } finally {
    deciding.value = false
  }
}

/** GF-B：表单提交（值已由 FormRenderer 按 schema 做过客户端复核）。 */
function onFormSubmit(values: Record<string, unknown>): void {
  void ai.submitGenerativeForm(values)
}

/** GF-B：表单显式取消 → store 带 cancelled:true 回灌收敛。 */
function onFormCancel(): void {
  void ai.cancelGenerativeForm()
}

/** 挂起调用换人即重置倒计时与拒绝原因。 */
function resetPendingWatch(pending: PendingToolCall | null): void {
  rejectReason.value = ''
  if (!pending || pending.status !== 'pending') {
    confirmDeadline.value = null
    return
  }
  // DC-14 T4：优先用服务端给的**绝对**到期时刻（挂起时刻 + hitl.timeout），
  // 不再用"本地收到帧的时刻 + timeoutSeconds"推断 —— 后者会把网络/渲染延迟算进倒计时，
  // 且重挂（帧回放）时会把已经过去的等待时间重新计一遍。
  if (typeof pending.expiresAt === 'number' && Number.isFinite(pending.expiresAt)) {
    confirmDeadline.value = pending.expiresAt
    return
  }
  // 兼容回落：老后端/前端工具挂起帧没带 expiresAt 时按相对秒数算
  confirmDeadline.value = pending.timeoutSeconds
    ? Date.now() + pending.timeoutSeconds * 1000
    : null
}

watch(
  () => {
    const last = ai.messages[ai.messages.length - 1]
    return `${ai.messages.length}|${last ? last.content.length : 0}|${last && last.pendingCall ? last.pendingCall.status : ''}|${last ? last.toolRuns.length : 0}`
  },
  () => scrollBottom()
)

watch(() => ai.expanded, (expanded) => {
  if (expanded) {
    setTimeout(scrollBottom, 60)
  }
})

watch(
  () => ai.pendingConfirm,
  (pending) => resetPendingWatch(pending),
  { immediate: true }
)

onMounted(async () => {
  ai.startMirror()
  ai.startWorkspaceSync()
  tickTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
  void ai.probeHealth()
  if (!ai.messages.length) {
    await ai.loadHistory()
  }
  scrollBottom()
})

onBeforeUnmount(() => {
  if (tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  ai.stopWorkspaceSync()
})
</script>

<style scoped>
/* 等待回复的三点闪烁动画：蓝本 AiPanel.vue 第 186-202 行原样保留
   （Tailwind v3 无 animation/关键帧工具类，属规格 §1.1 允许的唯一 scoped CSS）。 */
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
