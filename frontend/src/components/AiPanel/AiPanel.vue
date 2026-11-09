<template>
  <!-- 收起态：48px 竖排条（蓝本 AiPanel.vue:2-7） -->
  <div
    v-if="!ai.expanded"
    class="h-full flex flex-col items-center pt-4 gap-2 cursor-pointer text-[#57606a]"
    @click="ai.toggleExpand()"
  >
    <span
      class="grid h-[30px] w-[30px] flex-none place-items-center rounded-[8px]"
      :class="[collapsedActivityStyle.shell, collapsedActivityStyle.motion]"
      aria-hidden="true"
    >
      <span v-if="collapsedActivityState === 'recovering'" class="flex items-center gap-[3px]" data-testid="collapsed-recovery-loading">
        <span
          v-for="delay in RECOVERY_DOT_DELAYS"
          :key="delay"
          class="h-[4px] w-[4px] rounded-full bg-[#2563eb] animate-pulse motion-reduce:animate-none"
          :style="{ animationDelay: delay }"
        ></span>
      </span>
      <svg v-else class="h-[17px] w-[17px]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l1.6 4.4 4.4 1.6-4.4 1.6L12 15.5l-1.6-4.4L6 9.5l4.4-1.6L12 3.5Z"></path><path d="M18.4 15.4l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7.7-1.9Z"></path></svg>
    </span>
    <span class="writing-vertical text-[13px]">{{ t('ai.panel.assistant') }}</span>
  </div>

  <!-- R-165 S5a 项 9：focus-visible 聚焦环（demo v7 14b 口径）——根容器一条任意变体覆盖面板内
       **全部**原生 button（含 v-html 注入的代码块复制钮），无需逐钮挂类。 -->
  <div v-else class="ai-chat h-full flex flex-col [&_button:focus-visible]:outline-none [&_button:focus-visible]:ring-2 [&_button:focus-visible]:ring-[#2563eb]/40">
    <!-- 头部（蓝本 AiPanel.vue:10-19） -->
    <div class="flex items-center gap-2.5 px-3 py-2.5 border-0 border-b border-solid border-[#ebeef5]">
      <span class="grid h-[30px] w-[30px] flex-none place-items-center rounded-[8px] bg-[#eff4ff] text-[#2563eb]" aria-hidden="true">
        <svg class="h-[17px] w-[17px]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l1.6 4.4 4.4 1.6-4.4 1.6L12 15.5l-1.6-4.4L6 9.5l4.4-1.6L12 3.5Z"></path><path d="M18.4 15.4l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7.7-1.9Z"></path></svg>
      </span>
      <div class="flex-1 min-w-0 text-sm font-semibold text-[#303133]">{{ t('ai.panel.implementer') }}</div>
      <button
        type="button"
        class="inline-flex h-6 items-center justify-center whitespace-nowrap rounded-[3px] border-0 bg-transparent px-[11px] py-[5px] text-xs font-medium leading-none text-[#606266] shadow-none transition-colors duration-100 hover:text-[#2563eb] active:text-[#1d4ed8] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#2563eb]/40"
        data-testid="new-chat"
        :title="t('ai.panel.newChat')"
        :disabled="ai.restoringRecovery"
        @click="newChat"
      >
        {{ t('ai.panel.newChat') }}
      </button>
      <button
        type="button"
        class="ml-3 inline-flex h-6 items-center justify-center rounded-[3px] border-0 bg-transparent px-[11px] py-[5px] text-xs font-medium leading-none text-[#606266] shadow-none transition-colors duration-100 hover:text-[#2563eb] active:text-[#1d4ed8] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#2563eb]/40"
        :title="t('ai.panel.collapse')"
        :aria-label="t('ai.panel.collapse')"
        @click="ai.toggleExpand()"
      >
        <ArrowRight class="h-3 w-3" aria-hidden="true" />
      </button>
    </div>

    <!-- 降级与冲突提示（R-165 S2：EP Alert 全退场 → 自绘 demo v7 `.alert` 结构 = 描边圆图标 + 标题 + 说明 + ghost 动作钮；
         色族按 v7 语义表：故障红 / 并发·降级蓝 / 通知灰 / 结果绿；`.notice` 虚线灰底承接单行通知槽） -->
    <div v-if="ai.poolSaturated" :class="alertBox('primary')" data-testid="alert-pool-saturated">
      <span :class="alertIcon('primary')" aria-hidden="true">▦</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ t('ai.panel.suspendPoolFull') }}</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ t('ai.panel.poolSaturatedDetail') }}</span>
      </div>
    </div>

    <!-- 批 E · E1：能力位降级黄条随模型选择一起退场 —— 没有选择就没有"选了不支持工具的模型"
         这一状态；默认模型的工具能力由后端 `/health` 与真实调用结果说话。 -->

    <div v-if="healthBlocksSend" :class="alertBox('primary')" data-testid="alert-degraded">
      <span :class="alertIcon('primary')" aria-hidden="true">↓</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ t('ai.panel.unavailableDegraded') }}</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ degradeReason }}</span>
      </div>
    </div>

    <div v-if="identityRetryVisible" :class="alertBox('error')" data-testid="alert-identity-unavailable">
      <span :class="alertIcon('error')" aria-hidden="true">!</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ identityRetryTitle }}</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ identityRetryDetail }}</span>
      </div>
      <button
        type="button"
        data-testid="retry-identity"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        @click="retryIdentity"
      >
        {{ t('ai.panel.identityRetry') }}
      </button>
    </div>

    <div v-if="recoveryErrorVisible" :class="alertBox('error')" data-testid="alert-history-error">
      <span :class="alertIcon('error')" aria-hidden="true">!</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ recoveryErrorLabel() }}</b>
        <span class="block text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ recoveryErrorText() }}</span>
      </div>
      <button
        type="button"
        data-testid="retry-history"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        :disabled="ai.restoringRecovery"
        @click="retryRecoveryLoad()"
      >
        {{ t('ai.panel.retryRecovery') }}
      </button>
    </div>

    <!-- 单行通知槽——普通通知 = demo v7 `.notice`（虚线灰底 + — 描边圆），取消 / 重试 / 幂等等通知的承载位；
         R-165 S3a（R11 指挥官裁决）：error 级通知改走 v7 `.enotice` 红族（浅红底 + 浅红描边 + 圆角 9 + ! 圆标）。
         上方已有池饱和专属 Alert 时，本槽不再叠一遍同义文案。 -->
    <div
      v-if="noticeBarVisible"
      class="m-2 flex items-center gap-[8px] border px-[11px] py-[8px] text-[12.6px]"
      :class="noticeIsError
        ? 'border-solid border-[#f0d5d0] bg-[#fdf1ef] text-[#1f2328] rounded-[9px]'
        : 'border-dashed border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a] rounded-[10px]'"
      data-testid="notice-bar"
    >
      <span
        v-if="noticeIsError"
        class="flex-[0_0_14px] w-[14px] h-[14px] rounded-[50%] border-solid border-[1.3px] border-[#c0392b] text-[#c0392b] text-[9px] font-bold flex items-center justify-center"
        aria-hidden="true"
      >!</span>
      <span v-else class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.2px] border-[#8b949e] text-[#8b949e] text-[9px] flex items-center justify-center" aria-hidden="true">—</span>
      <!-- S6 批1（长内容审计 #7）：通知文本包一层 span —— 原为匿名 flex 项，不可 min-w-0 缩也不可折行，
           后端 5xx 长文案会顶开通知槽（或被他处 overflow-hidden 硬裁）。 -->
      <span class="min-w-0 flex-1 break-words [overflow-wrap:anywhere]">{{ noticeText }}</span>
      <!-- 批次2（N5）：409 SESSION_BUSY 的恢复入口 —— 通知文案不暴露 runId/重挂术语，
           动作把用户带回**仍在进行**的那一轮（store 适配器，见 recoveryBridge 注释）。 -->
      <button
        v-if="canReturnToActiveRun()"
        type="button"
        data-testid="return-active-run"
        :aria-label="t('ai.panel.returnToActiveRunHint')"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[9px] py-[3px] text-[11.8px] text-[#2563eb] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        :disabled="ai.restoringRecovery"
        @click="returnToActiveRun()"
      >
        {{ t('ai.panel.returnToActiveRun') }}
      </button>
      <button
        type="button"
        class="flex-none border-0 bg-transparent px-[3px] text-[14px] leading-none text-[#8b949e] hover:text-[#1f2328]"
        data-testid="dismiss-notice"
        :aria-label="t('ai.panel.dismiss')"
        @click="ai.dismissNotice()"
      >×</button>
    </div>

    <!-- 工作区动作回执（AI→业务方向）：成功且结果已在工具卡/业务页可见时由 store 静默
         （`SILENT_SUCCESS_UI_EVENTS`），本回执位只承接失败/被拒与视口外结果（如下载）。 -->
    <div v-if="uiEventReceiptVisible" :class="alertBox('gray')" data-testid="alert-ui-event">
      <span :class="alertIcon('gray')" aria-hidden="true">i</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328] break-words [overflow-wrap:anywhere]">{{ t('ai.panel.uiEvent', { message: ai.lastUiEventResult?.message ?? '' }) }}</b>
      </div>
      <button
        type="button"
        class="flex-none border-0 bg-transparent px-[3px] text-[14px] leading-none text-[#8b949e] hover:text-[#1f2328]"
        data-testid="dismiss-ui-event"
        :aria-label="t('ai.panel.dismiss')"
        @click="ai.dismissUiEventReceipt()"
      >×</button>
    </div>

    <!-- AI 可视可操作专项（设计稿 §3.10）：操作条落在**消息流之上、panel header 之下** ——
         不进 composer 底栏（那是模型配置批③ 的 model-picker 落点）。停止复用既有停止语义
         （`ai.stop()` 同时置"后续界面动词一律拒绝"，已执行的不回滚）。 -->
    <div
      v-if="aiOperating"
      class="mx-2 mt-2 flex items-center gap-[8px] rounded-[9px] border border-solid border-[#dbeafe] bg-[#eff6ff] px-[11px] py-[7px]"
      data-testid="ai-operating-bar"
    >
      <span class="inline-flex flex-none items-center text-[#2563eb]" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor">
          <path d="M8 1.5l1.2 3.2 3.2 1.2-3.2 1.2L8 10.3 6.8 7.1 3.6 5.9l3.2-1.2z" />
        </svg>
      </span>
      <span class="min-w-0 flex-1 text-[12.4px] leading-[1.5] text-[#1f2328] break-words [overflow-wrap:anywhere]">
        {{ uiOperatingText }}
      </span>
      <button
        type="button"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        data-testid="ai-operating-stop"
        @click="stop"
      >
        {{ t('ai.panel.stop') }}
      </button>
    </div>
    <!-- 读屏播报（只读动词也播报；视觉零占位）：与操作条同一句，前缀只在 `uiOperating` 出现一次 -->
    <div class="sr-only" aria-live="polite" data-testid="ai-live-region">{{ uiOperatingText }}</div>
    <!-- B4：压缩进入/退出各播报一次（文本只在过渡时变，秒表不在 live region 内） -->
    <div class="sr-only" aria-live="polite" data-testid="compaction-live">{{ compactionAnnouncement }}</div>

    <!-- 消息流（蓝本 AiPanel.vue:21-89）；R-154 #5：外层 relative 壳承载「回到底部」浮钮 -->
    <div class="flex-1 relative min-h-0 flex flex-col">
      <!-- R-161：role="log" 补滚动容器（无障碍，无视觉影响）；R-163B G2：overflow-x-hidden 止血横滚条。
           R-165 S5a 项 8：滚动条细化 —— 6px 圆角半透明（demo 14a 口径），任意变体类写在模板类串上
           （禁改 tailwind.config.js、禁手写 CSS）。
           2026-10-29 返修：`[overflow-anchor:none]` **只在跟随态**挂 —— 跟随态我们自己按 measured size
           补偿，原生锚定再补一次就是双份；用户上翻后我们不做任何补偿，位置只能交给浏览器锚定
           （否则 mermaid / 图片 / 字体在视口上方落地时，阅读位置会跳）。
           平滑回底（`scroll-smooth`）也挂在这条 `:class` 上（见 backToBottom 注释）—— 本绑定每次重渲
           都会整串覆写 className，命令式 `classList.add` 会在这里被静默抹掉。 -->
      <div
        ref="msgWrapRef"
        role="log"
        :aria-label="t('ai.panel.logAria')"
        :aria-busy="logBusy ? 'true' : 'false'"
        class="flex-1 overflow-y-auto overflow-x-hidden p-3"
        :class="[SCROLLBAR_CLASS, followBottom ? '[overflow-anchor:none]' : '', smoothBackActive ? 'scroll-smooth' : '']"
        @scroll.passive="onMsgScroll"
        @wheel.passive="markUserScrollIntent"
        @touchstart.passive="markUserScrollIntent"
        @keydown="markUserScrollIntent"
      >
      <!-- 批 D · D2②：**内容层**单独一层（RO 的观测目标）。滚动容器自身的盒子被 flex 定死，
           内容长高时它自己的 box 不变 ⇒ 观测它对内容增长完全不触发；观测内容层才等于
           "内容高度变了"。纯布局容器：无内边距、无定位，视觉零变化。 -->
      <div ref="msgContentRef">
      <!-- 历史对账期骨架屏（苞 B 项 4）：消费 store 的 historyLoaded —— 与空态互斥
           （无消息且对账未完 → 骨架屏；对账完成后才轮到空态/消息流）。
           R-165 S2：el-skeleton 退场 → 自绘 demo v7 `.sk` 渐变条（bg-[linear-gradient(...)] +
           bg-[length:220%_100%]）。v7 的 `animate-shimmer` 是 demo 临时 config 的私有动画
           （仓库 tailwind.config.js 禁改），改用内置 `animate-pulse`（同语义的无限循环渐隐，
           仓库无 shimmer keyframes 可挂）。 -->
      <div v-if="!ai.messages.length && !ai.historyLoaded" data-testid="history-skeleton" class="p-2" aria-hidden="true">
        <div v-for="(bar, i) in SKELETON_BARS" :key="i" :class="bar"></div>
      </div>
      <article
        v-else-if="!ai.messages.length"
        class="m-3 rounded-[14px] border border-solid border-[#dbe6ff] bg-[#f8faff] p-[16px]"
        data-testid="empty-state"
      >
        <div class="flex items-start gap-[11px]">
          <span class="grid h-[36px] w-[36px] flex-none place-items-center rounded-[11px] bg-[#2563eb] text-white shadow-[0_4px_12px_rgba(37,99,235,0.18)]" aria-hidden="true">
            <svg class="h-[19px] w-[19px]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l1.6 4.4 4.4 1.6-4.4 1.6L12 15.5l-1.6-4.4L6 9.5l4.4-1.6L12 3.5Z"></path><path d="M18.4 15.4l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7.7-1.9Z"></path></svg>
          </span>
          <div class="min-w-0">
            <h2 class="text-[15px] font-semibold leading-[1.45] tracking-[-0.01em] text-[#1f2328]">{{ t('ai.panel.greeting') }}</h2>
            <p class="mt-[3px] text-[12.2px] leading-[1.6] text-[#57606a]">{{ t('ai.panel.welcomeTagline') }}</p>
          </div>
        </div>
        <p class="mt-[15px] text-[11.8px] font-medium text-[#57606a]">{{ t('ai.panel.welcomePrompt') }}</p>
        <div class="mt-[8px] flex flex-col gap-[8px]">
          <button
            v-for="chip in visibleEmptyStateChips"
            :key="chip.id"
            type="button"
            class="group flex w-full items-center gap-[10px] rounded-[10px] border border-solid border-[#dbe6ff] bg-white px-[11px] py-[9px] text-left transition-colors duration-[150ms] motion-reduce:transition-none hover:border-[#9bbcff] hover:bg-[#fbfdff] disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="!chipsAvailable"
            data-testid="suggestion-chip"
            :data-suggestion-id="chip.id"
            :aria-label="t('ai.suggestions.fillAria', { text: t(`ai.suggestions.${chip.title}`) })"
            @click="fillPreset(t(`ai.suggestions.${chip.prompt}`))"
          >
            <span class="grid h-[26px] w-[26px] flex-none place-items-center rounded-[7px] bg-[#eff4ff] text-[13px] font-semibold text-[#2563eb]" aria-hidden="true">{{ chip.icon }}</span>
            <span class="min-w-0 flex-1">
              <span class="block text-[12.4px] font-medium leading-[1.45] text-[#1f2328]">{{ t(`ai.suggestions.${chip.title}`) }}</span>
              <span class="mt-[1px] block text-[11px] leading-[1.45] text-[#8b949e]">{{ t(`ai.suggestions.${chip.hint}`) }}</span>
            </span>
            <span class="flex-none text-[16px] leading-none text-[#c7cdd6] transition-colors motion-reduce:transition-none group-hover:text-[#2563eb]" aria-hidden="true">›</span>
          </button>
        </div>
        <div class="mt-[11px] text-[11px] leading-[1.6] text-[#8b949e]" data-testid="storage-notice">
          {{ t('ai.suggestions.fillNote') }}
        </div>
      </article>

      <!-- 分页（R-154 #16）：本机还留着更早的展示历史 ⇒ 消息流内**单个**「加载更早」入口。 -->
      <div v-if="ai.historyHasMore" class="mb-2 flex justify-center">
        <button
          type="button"
          data-testid="load-earlier-history"
          :disabled="ai.historyPageLoading"
          :class="SUGGESTION_CHIP"
          @click="loadEarlierHistory"
        >
          {{ ai.historyPageLoading ? t('ai.panel.loadEarlierHistoryLoading') : t('ai.panel.loadEarlierHistory') }}
        </button>
      </div>

      <!-- 消息间距由元信息行承担；无元信息行的纯卡片消息由消息容器承担。 -->
      <div
        v-for="(m, mi) in ai.messages"
        :key="m.id"
        :data-msg-id="m.id"
        v-memo="messageMemo(m, mi)"
        class="group flex"
        :class="[m.role === 'user' ? 'flex-row-reverse' : '', cardOnlyMessageGapClass(m, mi)]"
      >
        <!-- R-163 T3：双侧去头像（demo v6 场景 2）——消息行 = 内容 + 元信息行，无头像列 -->
        <!-- 内容列：正文/卡片 + 元信息行（R-157 #1：操作行挪气泡外、卡片下沿独立行） -->
        <div class="min-w-0 flex-1 flex flex-col" :class="m.role === 'user' ? 'items-end' : 'items-start'">
        <!-- R-160 M4（issue #14③ 方案 A）：容器层一刀切 —— overflow:hidden + min-width:0；
             R-163 T3：用户消息 = 内容-only 卡片；助手回复 = 无气泡全宽（透明底）。
             R-163B G3：用户气泡对齐 demo —— 中性灰 #f6f7f9 + 1px #eef0f3 边框 +
             圆角 12/12/3/12 + max-width 86%（色彩纪律：蓝只用于状态）。
             R-165 S2：字号改**按 role 分派**（原为同特异度两条 text-[*] 并存 —— 与 S1 §5② 同源风险：
             两条任意值工具类同为 (0,1,0)，胜负由产出顺序决定；用户气泡必须恒 13.5px / 助手 13.8px）。
             R-165 S4：① `w-full` 按 role 分派 —— 用户侧去掉后气泡按内容贴身收窄（`max-w-[86%]` 才真生效；
             原 w-full 使短文本也恒占 86% 宽）；助手侧保留全宽。
             ② 垂直内边距按 role 分派 —— 助手侧去 `py-2` 只留 `px-2.5`（v7 AI 正文无垂直内边距，
             工具卡 `mt-1.5` 得以贴正文下沿；原 py-2 把「用户末行→AI 首行」多顶 8px）。 -->
        <!-- S6 批1（长内容审计 #4：AI 正文长无空格 token）：助手侧气泡容器补 `[overflow-wrap:anywhere]`
             —— 原只有用户侧带 `break-words`，助手侧长 URL / 长标识符被外层 `overflow-hidden` 硬裁。
             （md-body 根容器仍不声明 —— 那里加会把长英文标识符按任意位置断开，口径见 markdown.ts G1③。） -->
        <div
          class="min-w-0 overflow-hidden px-2.5 leading-[1.7]"
          :class="m.role === 'user'
            ? 'max-w-[86%] rounded-[12px] bg-[#f6f7f9] border border-solid border-[#eef0f3] break-words text-[13.5px] py-2'
            : 'text-[13.8px] w-full [overflow-wrap:anywhere]'"
          :data-testid="'msg-bubble-' + m.id"
        >
          <!-- 消息体：块分派（批4④ A5' → R-154 单卡化）——groupParts 把段序列分派为 text/activity 块；
               R-154 #13+14 起 activity 恒为单段卡（组壳已拆），展开态读 store 三态
               （expandedBlocks ?? defaultExpanded，D4/D6/D-C6）；key 用块稳定 id
               （成员 toolCallId / kind-seg，加消息命名空间前缀，收口修 F1/红队 M-1），禁用数组下标（§7 R4-3 / D5）。 -->
          <template v-for="(block, bi) in blocksOf(m)" :key="block.id">
            <!-- R-163 T1 正文块：markdown-it → DOMPurify 白名单 → v-html 唯一链路。 -->
            <!-- R-163B G4 → R-165 S5b：v-html 内交互（代码块复制 / mermaid 卡四钮：复制源码 · tab 切换 ·
                 复位 · 全屏）走事件委托（onMdBodyClick），不在注入 HTML 内联任何脚本。 -->
            <!-- R-165 S4：md-body 容器按 role 挂两条修正类（全部走任意变体类）：
                 ① `[&>*:last-child]:!mb-0` —— 压掉末段下外边距（用直接子选择器覆盖
                    p/ul/ol/blockquote/pre/figure 全部末块形态。这是「用户末行→AI 首行」多出的
                    12px 根因，S3a §5.4 光学间距偏差来源；S5c 起末块外边距的声明方已从
                    ai-chat.css 换成 markdown.ts 注入的工具类，`!` 保留为双保险）。
                 ② 用户侧 `[&_p]:text-[13.5px]`+`[&_li]:text-[13.5px]` —— 根容器自身声明 13.8px
                    （MD_BODY_ROOT_CLASS）会压过气泡容器的继承值，改用内部选择器直接命中 p/li
                    才真落地（`.md-body li` 无字号声明，13.5 经 ul 继承下游，同为直命中口径）。
                 R-165 S5c：正文排版（13.8px / 1.7 / --ink）从 `.ai-chat .md-body`（ai-chat.css）
                 改为根容器随行工具类 MD_BODY_ROOT_CLASS，层级元素由渲染层逐标签注入。 -->
            <div
              v-if="block.kind === 'text'"
              :ref="m.role === 'user' ? userBodyRefFor(m.id) : undefined"
              v-memo="blockMemo(block, m)"
              :class="[
                'md-body',
                MD_BODY_ROOT_CLASS,
                blockSpacingClass(blocksOf(m), bi),
                m.role === 'user' ? MD_BODY_USER_CLASS : MD_BODY_AI_CLASS,
                m.role === 'user' && isUserMsgCollapsed(m.id) ? USER_MSG_COLLAPSE_CLASS : ''
              ]"
              v-html="markdownHtml(m, block)"
              @click="onMdBodyClick"
            ></div>
            <span
              v-if="block.kind === 'text' && textPartOf(block)?.truncated"
              class="ml-1 inline-block h-[7px] w-[7px] rounded-full bg-[#8b949e] align-middle"
              role="img"
              :aria-label="t('ai.panel.truncated')"
              :title="textTruncationTitle(block)"
              data-testid="text-truncated-badge"
            ></span>

            <div
              v-else-if="block.kind === 'event'"
              v-memo="blockMemo(block, m)"
              role="separator"
              :aria-label="t('ai.panel.compactionEventAria')"
              class="my-2.5 flex select-none items-center gap-2 text-[11.5px] text-[#8b949e]"
              data-testid="compaction-event"
            >
              <span class="h-px flex-1 bg-[#e3e6ea]" aria-hidden="true"></span>
              <span aria-hidden="true">{{ t('ai.panel.compactionEvent') }}</span>
              <span class="h-px flex-1 bg-[#e3e6ea]" aria-hidden="true"></span>
            </div>

            <template v-else>
              <template v-for="part in block.parts" :key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg">
                <!-- 思考卡保留轻量灰底、真实计时与单一正文滚动区。 -->
                <article
                  v-if="part.kind === 'reasoning' && part.text"
                  v-memo="partMemo(part, block)"
                  :class="['overflow-hidden rounded-[10px] border border-solid border-[#e3e6ea] bg-[#f6f7f9]', blockSpacingClass(blocksOf(m), bi)]"
                >
                  <ReasoningHeader
                    :part="part"
                    :block-id="block.id"
                    :expanded="isBlockExpanded(block)"
                    :truncated-title="truncationTitle(part)"
                    @toggle="toggleBlock(block)"
                  />
                  <div
                    :id="'reasoning-body-' + block.id"
                    v-show="isBlockExpanded(block)"
                    class="border-0 border-l-2 border-solid border-[#e3e6ea] px-[10px] pb-[10px]"
                    :data-testid="'reasoning-body-' + block.id"
                  >
                    <div :class="['max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-words text-[12.6px] leading-[1.7] text-[#57606a]', SCROLLBAR_CLASS]" :data-testid="'reasoning-scroll-' + block.id">{{ part.text }}</div>
                  </div>
                </article>
                <template v-else-if="part.kind === 'tool'">
                  <!-- 普通工具、HITL 与主面板 GF 共用卡头；卡体沿用各自真实业务状态。 -->
                  <article
                    v-memo="partMemo(part, block)"
                    :class="[cardShellClass(part), blockSpacingClass(blocksOf(m), bi)]"
                    :data-testid="cardTestId(part)"
                  >
                    <BusinessCardHeader
                      v-if="!hasIndependentCardHeader(part)"
                      :icon="cardIconName(part)"
                      :title="cardTitle(part)"
                      :status="cardStatusLabel(part)"
                      :tone="cardStatusTone(part)"
                      :expanded="isBlockExpanded(block)"
                      :controls="'business-card-body-' + block.id"
                      :class="isReadOnlyLike(part) ? 'ro-hd' : ''"
                      :data-testid="cardToggleTestId(part, block)"
                      :data-icon-type="cardTypeOf(part)"
                      :data-card-icon-testid="'card-icon-' + part.toolCallId"
                      :data-card-title-testid="'card-title-' + part.toolCallId"
                      :data-card-status-testid="cardStatusTestId(part)"
                      :data-card-state="cardPresentationStatusKey(part)"
                      @toggle="onCardHeadClick(block, part)"
                    />
                    <!-- 已决 HITL / GF 保持原字段或表单只读，不另建回执卡。 -->
                    <div
                      v-if="isReadOnlyLike(part)"
                      :id="'business-card-body-' + block.id"
                      v-show="isBlockExpanded(block)"
                      class="px-[10px] pb-[10px]"
                      :data-testid="'ro-body-' + part.toolCallId"
                    >
                      <template v-if="part.name !== GENERATIVE_FORM_TOOL">
                        <div :class="['flex max-h-[min(280px,35vh)] flex-col gap-[8px] overflow-y-auto', SCROLLBAR_CLASS]">

                          <div
                            v-if="gateRows(part).length > 0"
                            class="rounded-[8px] border border-solid border-[#eef0f3] bg-white px-[10px] py-[2px]"
                            :data-testid="'ro-rows-' + part.toolCallId"
                          >
                            <div
                              v-for="row in gateRows(part)"
                              :key="row.key"
                              class="flex gap-[10px] border-0 border-b border-solid border-[#eef0f3] py-[7px] text-[13px] leading-[1.5] last:border-b-0"
                            >
                              <span class="min-w-0 max-w-[40%] flex-[0_1_auto] truncate text-[12.4px] text-[#8b949e]" :title="row.label ?? row.key">{{ row.label ?? row.key }}</span>
                              <span class="min-w-0 flex-1 break-words">{{ row.value }}</span>
                            </div>
                          </div>
                          <div
                            v-if="gateConsequenceText(part)"
                            class="text-[11.9px] leading-[1.6] text-[#57606a]"
                            :data-testid="'ro-consequence-' + part.toolCallId"
                          >{{ gateConsequenceText(part) }}</div>
                          <div
                            v-if="readOnlyReasonText(part)"
                            class="rounded-[8px] border border-solid px-[10px] py-[7px] text-[11.9px] leading-[1.6] whitespace-pre-wrap break-words"
                            :class="diagnosticOf(part) === 'staleGate'
                              ? 'border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a]'
                              : 'border-[#f0d5d0] bg-[#fdf1ef] text-[#c0392b]'"
                            :data-testid="'ro-reason-' + part.toolCallId"
                            :data-diagnostic="diagnosticOf(part) ?? 'none'"
                          >{{ readOnlyReasonText(part) }}</div>
                        </div>
                      </template>
                      <template v-else>
                        <div v-if="cardFormSpec(part)" :data-testid="'ro-form-' + part.toolCallId">
                          <FormRenderer
                            :schema="(cardFormSpec(part) as FormSpec)"
                            readonly
                            :submitting="false"
                            :error="null"
                            :initial-values="readOnlyFormValues(part)"
                            :full-title="formBodyTitle(part)"
                            :read-only-reason="readOnlyReasonText(part) || null"
                            :read-only-reason-tone="diagnosticOf(part) === 'staleGate' ? 'neutral' : 'error'"
                          />
                        </div>
                        <div v-else :class="['flex max-h-[min(320px,45vh)] flex-col gap-[8px] overflow-y-auto', SCROLLBAR_CLASS]">

                          <div class="text-[12.6px] text-[#57606a]">{{ t('ai.panel.formUnavailable') }}</div>
                          <div
                            v-if="readOnlyReasonText(part)"
                            class="whitespace-pre-wrap break-words rounded-[8px] border border-solid px-[10px] py-[7px] text-[11.9px] leading-[1.6]"
                            :class="diagnosticOf(part) === 'staleGate'
                              ? 'border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a]'
                              : 'border-[#f0d5d0] bg-[#fdf1ef] text-[#c0392b]'"
                            :data-testid="'ro-reason-' + part.toolCallId"
                            :data-diagnostic="diagnosticOf(part) ?? 'none'"
                          >{{ readOnlyReasonText(part) }}</div>
                        </div>
                      </template>
                    </div>
                    <template v-else-if="canShowCardBody(part)">
                      <div
                        :id="'business-card-body-' + block.id"
                        v-show="hasIndependentCardHeader(part) || isBlockExpanded(block)"
                        :class="hasIndependentCardHeader(part) ? 'flex flex-col' : 'flex flex-col px-[10px] pb-[10px]'"
                        :data-testid="'block-body-' + block.id"
                      >
                      <div
                        v-if="!part.requiresAction && !hasIndependentCardHeader(part)"
                        :class="['flex flex-col gap-[8px] overflow-y-auto', toolContentLimitClass(part), SCROLLBAR_CLASS]"
                      >

                        <div v-if="paramRows(part).length">
                          <div class="mb-[4px] block text-[11px] uppercase tracking-[.5px] text-[#8b949e]">{{ t('ai.panel.args') }}</div>
                          <div class="rounded-[8px] border border-solid border-[#eef0f3] bg-[#f6f7f9] px-[10px] py-[2px]" :data-testid="'args-rows-' + part.toolCallId">
                            <div
                              v-for="row in paramRows(part)"
                              :key="row.key"
                              class="flex gap-[10px] border-0 border-b border-solid border-[#eef0f3] py-[6px] text-[12.6px] leading-[1.5] last:border-b-0"
                            >
                              <span class="flex-[0_0_72px] break-words text-[12px] text-[#8b949e]">{{ row.label ?? row.key }}</span>
                              <span class="min-w-0 flex-1 break-words">{{ row.value }}</span>
                            </div>
                          </div>
                        </div>
                        <div v-if="!isBlankText(toolResultText(part)) && !hasViewOutput(part) && !isOutputSupersededByDiagnostics(part)">
                          <div class="mb-[4px] block text-[11px] uppercase tracking-[.5px] text-[#8b949e]">{{ t('ai.panel.output') }}</div>
                          <div data-testid="tool-output-text" class="whitespace-pre-wrap break-words rounded-[8px] border border-solid border-[#eef0f3] bg-[#f6f7f9] px-[10px] py-[8px] text-[12.1px] leading-[1.6] text-[#606266] [overflow-wrap:anywhere]">
                            {{ toolResultText(part) }}<span
                              v-if="part.truncated"
                              class="ml-1 inline-block rounded bg-[#8b949e] px-1 align-middle text-[10px] leading-4 text-white"
                              :title="truncationTitle(part)"
                              data-testid="truncated-badge"
                            >{{ t('ai.panel.truncated') }}</span>
                          </div>
                        </div>
                        <div v-if="faceReasonText(part)" class="rounded-[9px] border border-solid border-[#f0d5d0] bg-[#fdf1ef] px-[10px] py-[7px] text-[12.4px] text-[#c0392b]" data-testid="tool-error" :data-diagnostic="diagnosticOf(part) ?? 'none'">
                          <span class="block whitespace-pre-wrap break-words">{{ t('ai.panel.reasonPrefix', { text: faceReasonText(part) }) }}</span>
                        </div>
                        <ResultViewRenderer
                          v-if="part.resultView"
                          :view="part.resultView"
                          :document-text="part.result ?? null"
                        />
                        <div v-else-if="conflictRows(part).length" class="overflow-hidden rounded-[8px] border border-solid border-[#eef0f3]" data-testid="conflict-table">
                          <div class="border-0 border-b border-solid border-[#e3e6ea] bg-[#f6f7f9] px-[9px] py-[5px] text-[11.4px] [font-weight:650] text-[#57606a]">
                            <span class="inline-block w-[34%]">{{ t('ai.panel.definition') }}</span><span class="inline-block w-[26%]">{{ t('ai.panel.rowKey') }}</span><span class="inline-block w-[40%]">{{ t('ai.panel.reason') }}</span>
                          </div>
                          <div>
                            <div
                              v-for="row in conflictRows(part)"
                              :key="row.defCode + '#' + row.rowKey"
                              class="border-0 border-b border-solid border-[#eef0f3] px-[9px] py-[6px] text-[12.2px] text-[#1f2328] last:border-b-0"
                            >
                              <span class="inline-block w-[34%] align-top font-mono text-[11.6px] break-words">{{ row.defCode }}</span>
                              <span class="inline-block w-[26%] align-top font-mono text-[11.6px] break-words">{{ row.rowKey }}</span>
                              <span class="inline-block w-[40%] align-top break-words">{{ row.message }}</span>
                            </div>
                          </div>
                        </div>
                      </div>
                      <div
                        v-if="part.toolCallId === ai.uiUndoToolCallId && ai.uiUndoEntry"
                        class="mt-[8px] flex items-center gap-[8px]"
                        data-testid="ui-undo-row"
                      >
                        <span class="text-[12.2px] text-[#57606a]">{{ t('ai.panel.undoHint') }}</span>
                        <button
                          type="button"
                          class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
                          data-testid="ui-undo-button"
                          :disabled="ai.restoringRecovery"
                          @click="onUiUndo"
                        >
                          {{ t('ai.panel.undo') }}
                        </button>
                      </div>

                      <template v-if="part.requiresAction || hasIndependentCardHeader(part)">
                        <template v-if="part.kind2 === 'CONFIRM' && part.status === 'pending'">
                          <!-- 确认卡保持单层：标题直接表达动作，字段表只保留必要业务参数，
                               状态仅由卡头 chip 表达；不重复操作行或后端开发向说明。
                               待决与只读卡均保留必要后果；不可逆操作另有勾选批准门。 -->
                          <div data-testid="hitl-gate">
                            <!-- 字段表在内滚动；后果说明、复选框与操作按钮在外。 -->
                            <div
                              v-if="gateRows(part).length > 0"
                              class="max-h-[min(280px,35vh)] overflow-y-auto rounded-[8px] border border-solid border-[#eef0f3] bg-white px-[10px] py-[2px]"
                              :class="SCROLLBAR_CLASS"
                              data-testid="gate-rows"
                            >
                              <div
                                v-for="row in gateRows(part)"
                                :key="row.key"
                                class="flex gap-[10px] border-0 border-b border-solid border-[#eef0f3] py-[7px] text-[13px] leading-[1.5] last:border-b-0"
                              >
                                <span class="min-w-0 max-w-[40%] flex-[0_1_auto] truncate text-[12.4px] text-[#8b949e]" :title="row.label ?? row.key">{{ row.label ?? row.key }}</span>
                                <span class="min-w-0 flex-1 break-words">{{ row.value }}</span>
                              </div>
                            </div>
                            <div
                              v-if="gateConsequenceText(part)"
                              class="mt-[8px] text-[12.2px] leading-[1.55] text-[#57606a]"
                              data-testid="gate-consequence"
                            >{{ gateConsequenceText(part) }}</div>
                            <div v-if="part.irreversible" class="flex pt-[10px]" data-testid="gate-ack-row">
                              <label
                                class="flex cursor-pointer items-start gap-[6px] text-[12.2px] leading-[1.5] text-[#1f2328]"
                                data-testid="gate-ack"
                              >
                                <input
                                  type="checkbox"
                                  class="mt-[2px] flex-none cursor-pointer accent-[#2563eb]"
                                  data-testid="gate-ack-box"
                                  :checked="isGateAcked(part.toolCallId)"
                                  :disabled="ai.restoringRecovery || isGateSubmitting(part.toolCallId)"
                                  @change="onGateAckChange(part.toolCallId, $event)"
                                />
                                <span class="min-w-0 break-words">{{ gateAckText(part) }}</span>
                              </label>
                            </div>
                            <div
                              class="flex flex-wrap items-center gap-[8px] pt-[10px]"
                              data-testid="gate-actions"
                              :aria-busy="isGateSubmitting(part.toolCallId)"
                            >
                              <span
                                v-if="hasGateAckRequirement(part)"
                                class="text-[12.2px] text-[#b45309]"
                                data-testid="gate-ack-hint"
                              >{{ t('ai.hitl.ackHint') }}</span>
                              <span class="ml-auto flex flex-none items-center gap-[8px] whitespace-nowrap">
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default"
                                  :disabled="ai.restoringRecovery || isGateSubmitting(part.toolCallId)"
                                  @click="decide(false, part.toolCallId)"
                                >{{ t('ai.panel.reject') }}</button>
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[72px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal"
                                  :disabled="ai.restoringRecovery || isGateSubmitting(part.toolCallId) || hasGateAckRequirement(part)"
                                  @click="decide(true, part.toolCallId)"
                                >
                                  <span
                                    v-if="isGateSubmitting(part.toolCallId)"
                                    class="mr-[6px] inline-block h-[11px] w-[11px] animate-spin rounded-[50%] border-[1.5px] border-solid border-[rgba(255,255,255,.4)] border-t-white align-[-1px] motion-reduce:animate-none"
                                    data-testid="gate-syncing"
                                    aria-hidden="true"
                                  ></span>
                                  {{ isGateSubmitting(part.toolCallId) ? t('ai.panel.submitting') : t('ai.panel.approve') }}
                                </button>
                              </span>
                            </div>
                          </div>
                        </template>

                        <div v-else-if="part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL" class="min-w-0">
                          <!-- GF-B：生成式表单挂起 —— 渲染 FormRenderer 等用户填写（同 toolCallId 可重发/取消） -->
                          <!-- T2a-D#3（D10 不扩判据）：同 toolCallId 至多一个 FormRenderer —— 只渲染在宿主
                               消息（最后一条持该挂起调用的消息）上；非宿主消息不再出独立 tag 列（D-C7 已删） -->
                          <!-- 2026-10-20 卡面一致性裁决（demo 场景四）：嵌入态**单框** —— FormRenderer 走
                               flat（自绘描边/底色/外边距整块退役，只剩字段与操作行），外框由统一卡容器承担；
                               时限脚注随去超时裁决退役（表单一直可填）。 -->
                          <FormRenderer
                            v-if="liveForm && liveForm.toolCallId === part.toolCallId && m.id === formHostMessageId"
                            :key="liveForm.toolCallId"
                            :schema="liveForm.form"
                            :submitting="liveForm.status === 'submitting'"
                            :disabled="ai.restoringRecovery"
                            :error="liveForm.error"
                            :field-errors="liveForm.fieldErrors"
                            :initial-values="liveForm.values"
                            :full-title="formBodyTitle(part)"
                            :upload-context="{ runId: liveForm.runId, toolCallId: liveForm.toolCallId, sessionId: ai.sessionId, signal: formAttachmentAbort.signal }"
                            :show-header="false"
                            flat
                            @submit="onFormSubmit"
                            @cancel="onFormCancel"
                            @draft="onFormDraft"
                          />
                          <!-- 已提交、结局未到（`isFormAwaitingOutcome`）：同一张表单**原样锁定**在同一张卡里
                               —— 此时活动表单已置 submitted（`liveForm` 不再提供表单），卡由上方
                               `isReadOnlyLike` 的只读体分支承接（`ro-form-*` + 段上的 `submittedValues`），
                               这里不再重复渲一份：不闪普通工具卡 / 不闪 schema，结局帧到达后形态不变。
                               AR-07（批次4 集成返修）：排队中的表单一律有说明 + 取消出口 ——
                               它还没轮到呈现，不能靠"卡体空白"让用户以为坏了；也不能逼用户先处理
                               前面那张才能收口这一张（取消出口恒在）。运行态到期时本块与卡头下方的
                               「本轮运行态已失效」提示并存：到期只说明后端保留期，不改变"这张仍可
                               本地收口"（取消 = 本地终态 + cancelled 回灌）。 -->
                          <div
                            v-else-if="formQueuePosition(part.toolCallId) > 0"
                            class="rounded-[8px] border border-solid border-[#eef0f3] bg-[#f6f7f9] px-[10px] py-[8px]"
                            data-testid="gf-queue-card"
                            :data-tool-call-id="part.toolCallId"
                          >
                            <div class="flex flex-wrap items-center gap-[8px] text-[12.2px] leading-[1.5] text-[#1f2328]">
                              <span class="flex-none font-semibold" data-testid="gf-queue-position">{{ t('ai.panel.gfQueue.position', { position: formQueuePosition(part.toolCallId), total: formQueueTotal() }) }}</span>
                              <span class="min-w-0 flex-1 break-words text-[#57606a]" data-testid="gf-queue-hint">{{ t('ai.panel.gfQueue.hint') }}</span>
                            </div>
                            <div class="mt-[8px] flex justify-end">
                              <button
                                type="button"
                                class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
                                data-testid="gf-queue-cancel"
                                :disabled="ai.restoringRecovery"
                                @click="onQueuedFormCancel(part.toolCallId)"
                              >{{ t('ai.panel.gfQueue.cancel') }}</button>
                            </div>
                          </div>
                        </div>

                        <!-- AI 表格操作专项 §3.3bis：写预览卡（计数 / 采样 diff / 全量 diff 三档）。
                             数据只在浏览器渲染，不上送；大批量须勾选“我了解影响范围”。 -->
                        <div v-else-if="isSheetPreviewPart(part)" class="min-w-0">
                          <SheetPreviewCard
                            :plan="sheetPreviewFor(part)"
                            :applying="isSheetPreviewApplying(part)"
                            :applied-summary="appliedSummaryFor(part)"
                            :disabled="ai.restoringRecovery"
                            @apply="onSheetPreviewApply"
                            @cancel="onSheetPreviewCancel"
                            @abandon="onSheetPreviewAbandon(part.toolCallId)"
                            @level="onSheetPreviewLevel"
                          />
                        </div>

                        <!-- AI 可视可操作专项 §3.3.7：上传交接卡（唯一新增的人围栏外观）。
                             「选择文件」= 用户手势（picker 必须在同步栈里被调）；「放弃」= cancelled=true。
                             刷新后区域仍注册 ⇒ 可继续；区域不在该页 ⇒ 主按钮禁用 + 只能放弃。 -->
                        <div v-else-if="isUploadHandoffPart(part)" class="min-w-0">
                          <UploadHandoffCard
                            :status="uploadHandoffStatus(part)"
                            :accept="uploadHandoffAccept(part)"
                            :label="uploadHandoffLabel(part)"
                            :reason="uploadHandoffReason(part)"
                            :file-name="uploadHandoffFileName(part)"
                            :interactive="part.requiresAction"
                            :disabled="ai.restoringRecovery"
                            @choose="onUploadHandoffChoose"
                            @cancel="onUploadHandoffCancel(part.toolCallId)"
                          />
                        </div>
                      </template>

                    </div>
                    </template>
                  </article>
                </template>
              </template>
            </template>
          </template>

          <!-- 批次 7C：>10k 字符的本条消息只出**一条**中性降级提示（消息 id 维度，不是段维度；
               `renderMarkdown` 只在该消息首个降级帧返回文案，后续帧/重渲不重复）。 -->
          <div
            v-if="degradeNotices[degradeNoticeKey(m.id)]"
            class="mt-[6px] flex items-start gap-[6px] rounded-[9px] border border-dashed border-solid border-[#e3e6ea] bg-[#f6f7f9] px-[9px] py-[6px] text-[11.5px] leading-[1.6] text-[#57606a]"
            :data-testid="'degrade-notice-' + m.id"
            role="note"
          >
            <span class="flex-[0_0_14px] text-center" aria-hidden="true">—</span>
            <span class="min-w-0 break-words [overflow-wrap:anywhere]">{{ degradeNotices[degradeNoticeKey(m.id)] }}</span>
          </div>

          <!-- R-165 S4 → S5a 项 6：用户长消息「展开/收起」—— 真溢出（正文高 > 184px = 8 行）才出；
               折叠态 = 限高 + overflow:hidden + 底部渐隐（mask 任意值类），展开态为该消息本地态；
               真溢出才渲染（单一 measure 读 scrollHeight 口径）。
               问题1 偏差修正批 aj11（demo 该点改后样张）：钮形态由被裁决废掉的 v7.3 现状形态
               （12.2px 蓝字 / 贴内容 6px / 「展开」）改终裁形态 —— 11.5px #57606a（hover 转蓝）·
               底部左对齐（block text-left）· 距内容 8px · 文案「展开 ▾ / 收起 ▴」+ aria-expanded。 -->
          <button
            v-if="m.role === 'user' && userMsgOverflowed.has(m.id)"
            type="button"
            class="mt-[8px] block text-left border-0 bg-transparent p-0 font-[inherit] text-[11.5px] text-[#57606a] transition-colors duration-[150ms] hover:text-[#2563eb]"
            :data-testid="'user-msg-more-' + m.id"
            :aria-expanded="userMsgExpanded.has(m.id)"
            @click="toggleUserMsg(m.id)"
          >
            {{ userMsgExpanded.has(m.id) ? t('ai.panel.collapseMsg') : t('ai.panel.expandMsg') }}
          </button>
        </div>

        <!-- 单条消息的活动态与终态组严格互斥：活动态只显示工作/等待/压缩或恢复三点；
             用户消息与已终态助手消息显示时间戳、终态标识、复制提示及消息操作按钮。 -->
        <div
          v-if="canShowMetaRow(m)"
          class="flex items-center gap-2 text-xs text-[#8b949e] w-full max-w-[82%]"
          :class="[msgGapClass(mi), m.role === 'user' ? 'mt-[6px] flex-row-reverse' : 'mt-[12px]']"
          :data-testid="'msg-ops-' + m.id"
        >
          <span class="flex items-center gap-1 min-h-[18px]">
            <span
              v-if="activityStateOf(m) === 'recovering'"
              class="flex items-center gap-[3px] px-[3px]"
              data-testid="recovery-loading"
              role="status"
              :aria-label="t('ai.panel.recoveryLoading')"
            >
              <span
                v-for="delay in RECOVERY_DOT_DELAYS"
                :key="delay"
                class="h-[4px] w-[4px] rounded-full bg-[#2563eb] animate-pulse motion-reduce:animate-none"
                :style="{ animationDelay: delay }"
                aria-hidden="true"
              ></span>
            </span>
            <span
              v-else-if="activityStateOf(m) === 'waiting'"
              data-testid="park-waiting"
              class="inline-flex items-center gap-[5px] text-[12.2px] text-[#b45309] tracking-[.3px]"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#b45309] animate-breathe" aria-hidden="true"></span>
              <span class="animate-breathe-text">{{ t('ai.panel.waiting') }}</span>
            </span>
            <span
              v-else-if="activityStateOf(m) === 'compacting'"
              data-testid="compaction-message"
              class="inline-flex items-center gap-[5px] text-[12.2px] text-[#7c3aed] tracking-[.3px]"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#7c3aed] animate-breathe" aria-hidden="true"></span>
              <span class="animate-breathe-text">{{ compactingLabel(m) }}</span>
            </span>
            <span
              v-else-if="activityStateOf(m) === 'working' || isFading(m)"
              :class="[BREATH_CLASS, isFading(m) ? FADE_OUT_CLASS : '']"
              data-testid="loading-breath"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#2563eb] animate-breathe"></span><span class="text-[12.2px] text-[#57606a] tracking-[.5px] animate-breathe-text">{{ t('ai.panel.breathing') }}</span>
            </span>
            <span v-else-if="canShowTimestamp(m)" class="text-[#8b949e]" data-testid="msg-timestamp">{{ formatProgressiveTs(m.timestamp) }}</span>
          </span>
          <div
            v-if="isTerminalMessage(m)"
            class="flex items-center gap-2"
          >
            <span
              v-if="copyState && copyState.id === m.id && !copyState.ok"
              :data-testid="'copy-hint-' + m.id"
              class="text-xs text-[#c0392b]"
            >
              {{ t(copyState.empty ? 'ai.panel.copyEmpty' : 'ai.panel.copyFailed') }}
            </span>
            <!-- 批G · S3：停止可见 —— 消息尾「已停止」标识（与「继续」入口互斥：停掉的轮不出继续） -->
            <span
              v-if="m.role === 'assistant' && m.stopped"
              :data-testid="'msg-stopped-' + m.id"
              class="inline-flex items-center gap-[4px] rounded-[6px] border border-solid border-[#e3e6ea] bg-[#f6f7f9] px-[6px] py-[1px] text-[10.5px] leading-[1.6] text-[#57606a]"
            >
              <span class="block h-[6px] w-[6px] rounded-[1px] bg-[#8b949e]" aria-hidden="true"></span>
              {{ t('ai.round.stopped') }}
            </span>
            <!-- 截断专项：模型输出达到长度上限（finish_reason=length）⇒ 这条回答不完整。
                 可见徽标用既有 warning 色调（与工具卡 warning 状态同族），sr-only 补全语义供读屏；
                 不使用 live region（持久状态，读屏顺读即可，不做打断式播报）。 -->
            <span
              v-if="m.role === 'assistant' && m.truncated"
              :data-testid="'msg-truncated-' + m.id"
              class="inline-flex items-center gap-[4px] rounded-[6px] border border-solid border-[#fde68a] bg-[#fffbeb] px-[6px] py-[1px] text-[10.5px] leading-[1.6] text-[#b45309]"
            >
              <span class="block h-[6px] w-[6px] rounded-[1px] bg-[#b45309]" aria-hidden="true"></span>
              {{ t('ai.panel.truncated') }}
              <span class="sr-only">{{ t('ai.round.truncatedHint') }}</span>
            </span>
            <!-- 失败/断流的最后一条助手消息统一给「继续」：无论有无正文/思考，都走正常发送并显示一条
                 「继续」用户消息；停止/取消、unconfirmed、非末轮不出。配额错误卡已带「继续」时，
                 元信息行不再重复（见 isErrorCardContinueVisible）。
                 普通 regenerate 本批不做 ⇒ **入口不渲染**（无禁用 / unsupported 死 UI）。 -->
            <button
              v-if="canShowContinue(m)"
              type="button"
              data-testid="continue-round"
              :aria-label="t('ai.panel.resumeChat')"
              class="group/tip relative inline-flex items-center justify-center h-7 rounded-[8px] border-0 bg-transparent px-[6px] text-xs text-[#2563eb] transition hover:bg-[#eff4ff]"
              :disabled="ai.restoringRecovery"
              @click="continueTurn(m)"
            >
              {{ t('ai.panel.continue') }}
              <span :class="TIP_CLASS">{{ t('ai.panel.resumeChat') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <!-- R-163 T3：赞踩（仅助手行）——28×28 热区、本地态、选中互斥可取消；
                 R-165 S1：全 Tailwind 图标按钮（demo v7 `.icon-btn` 配方），SVG 照搬 v7（描边 1.6/15×15）；
                 色板按态整体换（选中态不挂 hover:*，免与 hover 工具类争顺序），赞蓝族、踩红族
                 （R-163 T3 语义保留）；`is-on` 只驱动 SVG 填充。
                 R-165 S2：原生 title 换 demo v7 `.tip` 黑底气泡（见 TIP_CLASS）；无障碍名改由 aria-label 承载。 -->
            <button
              v-if="m.role === 'assistant'"
              type="button"
              :data-testid="'feedback-up-' + m.id"
              :aria-label="feedbackOf(m.id) === 1 ? t('ai.panel.unlike') : t('ai.panel.like')"
              :aria-pressed="feedbackOf(m.id) === 1"
              :class="[ICON_BTN_CLASS, feedbackOf(m.id) === 1 ? ICON_BTN_ON_BLUE : ICON_BTN_IDLE]"
              @click="rateMessage(m, 1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"></path><path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"></path></svg>
              <span :class="TIP_CLASS">{{ feedbackOf(m.id) === 1 ? t('ai.panel.unlike') : t('ai.panel.like') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              v-if="m.role === 'assistant'"
              type="button"
              :data-testid="'feedback-down-' + m.id"
              :aria-label="feedbackOf(m.id) === -1 ? t('ai.panel.undislike') : t('ai.panel.dislike')"
              :aria-pressed="feedbackOf(m.id) === -1"
              :class="[ICON_BTN_CLASS, feedbackOf(m.id) === -1 ? ICON_BTN_ON_RED : ICON_BTN_IDLE]"
              @click="rateMessage(m, -1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"></path><path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path></svg>
              <span :class="TIP_CLASS">{{ feedbackOf(m.id) === -1 ? t('ai.panel.undislike') : t('ai.panel.dislike') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              v-if="m.role === 'user'"
              type="button"
              :class="[ICON_BTN_CLASS, ICON_BTN_IDLE]"
              :data-testid="'edit-msg-' + m.id"
              :aria-label="t('ai.panel.editMessage')"
              @click="editMessage(m)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9"></path><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"></path></svg>
              <span :class="TIP_CLASS">{{ t('ai.panel.editMessage') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="[ICON_BTN_CLASS, ICON_BTN_IDLE, COPY_STATE_CLASS, isMsgCopied(m.id) ? 'is-copied' : '']"
              :data-testid="'copy-msg-' + m.id"
              :aria-label="t('ai.panel.copyMessage')"
              @click="copyMessage(m)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-copy"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-check"><polyline points="20 6 9 17 4 12"></polyline></svg>
              <span :class="TIP_CLASS">{{ t('ai.panel.copyMessage') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
          </div>
        </div>

        <!-- R-165 S5c：错误卡族（demo v7.7 裁决区 08 改后口径）。铁律 = **内联于对话流、按错误发生
             的位置插入，绝不做顶部固定**（位置 static，非 fixed/sticky）——故挂在「出错那一轮」的
             消息上（store 写 `m.errorCard`），紧随该轮元信息行渲染，与顶部 Alert 族互不替代：
             409 SESSION_BUSY / 503 挂起池饱和 / 503 AI 不可用走各自的顶部 Alert（不重复出卡），
             本卡只承载「本轮出错」的三变体分派（429 红 / 内容过滤灰 / 上下文超限蓝）+
             未分类故障的通用红卡。分类与文案见 utils/error-card.ts。 -->
        <div
          v-if="m.errorCard"
          class="w-full flex items-start gap-[8px] border border-solid rounded-[10px] px-[10px] py-[8px] mb-2.5"
          :class="ERROR_CARD_TONE[m.errorCard.variant].box"
          :data-testid="'error-card-' + m.id"
          :data-variant="m.errorCard.variant"
        >
          <span
            class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.3px] text-[9px] mt-[2px] flex items-center justify-center"
            :class="ERROR_CARD_TONE[m.errorCard.variant].icon"
            aria-hidden="true"
          >{{ ERROR_CARD_TONE[m.errorCard.variant].glyph }}</span>
          <div class="flex-1 min-w-0">
            <b class="block text-[12.2px] [font-weight:650] text-[#1f2328]">{{ m.errorCard.title }}</b>
            <span
              class="mt-[2px] block break-words text-[11.6px] leading-[1.55] text-[#57606a]"
              :data-testid="'error-card-summary-' + m.id"
            >{{ m.errorCard.summary || t('ai.card.error.summary.generic') }}</span>
            <span v-if="errorCardGuide(m)" class="mt-[3px] block text-[11.4px] leading-[1.55] text-[#57606a]">{{ errorCardGuide(m) }}</span>
          </div>
          <button
            v-if="m.errorCard.action && isErrorCardActionVisible(m)"
            type="button"
            class="flex-none border-0 bg-transparent p-0 text-[11.5px]"
            :class="ERROR_CARD_TONE[m.errorCard.variant].action"
            :data-testid="'error-card-action-' + m.id"
            :disabled="ai.restoringRecovery"
            @click="runErrorCardAction(m)"
          ><span class="border-b border-[#e3e6ea]">{{ errorCardActionLabel(m) }}</span></button>
        </div>
        </div>
      </div>

      </div>
      </div>

      <!-- R-154 #5：上滑暂停跟随后浮出的「回到底部」——点击恢复跟随并滚底。
           R-165 S4 项 5（用户直裁）：形态回「水平居中 + 常显文字胶囊」（旧形态见 70a8aa6，
           圆钮 → 胶囊 + 字面文案「回到底部」，不再靠 sr-only 承载无障碍名 —— 可见文案即名字）。
           色族按 demo 族收敛：描边 `#e3e6ea`、阴影取 v7 `.fab` 的 `0_2px_8px_rgba(0,0,0,.13)`、
           字 `#57606a` / hover 深字 `#1f2328`（原 EP 系 #dcdfe6/#606266/#409eff 退役）。
           定位仍 `bottom-3`（定位父级 = 消息滚动容器，下沿 = composer 顶，S3a 裁决保留）+
           `left-1/2 -translate-x-1/2` 水平居中；z-[6] 同 v7 `.fab`。 -->
      <button
        v-if="showBackToBottom && ai.messages.length > 0"
        type="button"
        data-testid="back-to-bottom"
        :title="t('ai.panel.backToBottom')"
        class="absolute bottom-3 left-1/2 -translate-x-1/2 z-[6] whitespace-nowrap rounded-full border border-solid border-[#e3e6ea] bg-white px-3 py-1.5 text-xs text-[#57606a] shadow-[0_2px_8px_rgba(0,0,0,0.13)] transition-colors duration-[150ms] hover:text-[#1f2328]"
        @click="backToBottom"
      >
        {{ t('ai.panel.backToBottom') }}
      </button>
    </div>

    <!-- 输入区（问题4 composer v2 阶段一，demo docs/demo/chat-ux/composer-decisions.html ①/②）：
         form 形态 = 1 行 40px 起 / [field-sizing:content] 随内容增高 / 4 行 101px 封顶后框内滚动；
         白底 shell + 整盒 focus-within 蓝环；盒内底栏左工具位虚线占位、右 28×28 圆形发送/停止钮。
         行为：生成、挂起、表单、自动工具与健康异常时 textarea 始终可编辑，发送闸独立拦截；
         普通 Enter 空闲走 send，流式中 preventDefault 后静默；Shift+Enter 不拦截；
         IME 组合期（isComposing / keyCode 229 / compositionend 约 30ms）不误发；失焦复位组合态。 -->
    <div
      class="border-0 border-t border-solid border-[#eef0f3] bg-white px-[14px] py-[10px]"
    >
      <div class="rounded-[12px] border border-solid border-[#e3e6ea] bg-white transition-[border-color,box-shadow] duration-[150ms] focus-within:border-[#2563eb] focus-within:ring-2 focus-within:ring-[#2563eb]/40">
        <textarea
          ref="composerRef"
          v-model="inputText"
          rows="1"
          data-testid="composer-input"
          :maxlength="MESSAGE_MAX_CHARS"
          class="block min-h-[40px] max-h-[101px] w-full resize-none overflow-y-auto rounded-t-[12px] border-0 bg-transparent px-[11px] pb-[7px] pt-[11px] font-[inherit] text-[13px] leading-[1.6] text-[#1f2328] [outline:none] [field-sizing:content] placeholder:text-[#8b949e]"
          :placeholder="inputPlaceholder"
          @keydown="onComposerKeydown"
          @compositionstart="onComposerCompositionStart"
          @compositionend="onComposerCompositionEnd"
          @blur.capture="onComposerBlur"
        />
        <div class="relative flex items-center gap-[6px] px-[9px] pb-[7px] pt-0">
          <!-- 批 F · F1（用户裁决 2026-10-22）：参数 popover 整段退场（批 E · E1 只移了模型选择器）。
               底栏左端不再有任何选择/调参面，默认模型与参数一律由后端配置决定
               （请求既不携带 modelId，也不携带 params）；后端 allow.userParams 门控与能力保留。
               发送/停止钮与 ContextPill 的位置不动。 -->
          <span class="flex-1"></span>
          <span
            v-if="composerCounterVisible"
            data-testid="composer-count"
            class="mr-[2px] flex-none text-[10.5px] leading-[1.6] tabular-nums"
            :class="composerCounterClass"
          >{{ composerCounterText }}</span>
          <!-- 上下文占用圆环常驻；摘要只在收到 done.context 或端点重建后更新（流式中不跳动） -->
          <ContextPill />
          <button
            v-if="ai.loading || ai.suspended"
            type="button"
            data-testid="stop-btn"
            class="group/tip relative ml-auto flex h-[28px] w-[28px] flex-none items-center justify-center rounded-full border-0 bg-[#1f2328] transition-all duration-[150ms] hover:brightness-[1.15] focus-visible:ring-2 focus-visible:ring-[#2563eb]/40 focus-visible:ring-offset-0 disabled:cursor-default disabled:opacity-60 disabled:hover:brightness-100"
            :aria-label="ai.stopping ? t('ai.round.stopping') : t('ai.round.stop')"
            :disabled="ai.stopping"
            @click="stop"
          >
            <span class="block h-[9px] w-[9px] rounded-[2px] bg-white" aria-hidden="true"></span>
            <span class="tip" :class="TIP_CLASS" aria-hidden="true">{{ ai.stopping ? t('ai.round.stopping') : t('ai.round.stop') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
          </button>
          <button
            v-else
            type="button"
            data-testid="send-btn"
            class="group/tip relative ml-auto flex h-[28px] w-[28px] flex-none items-center justify-center rounded-full border-0 bg-[#2563eb] text-[13px] leading-none text-white transition-all duration-[150ms] hover:brightness-[1.08] disabled:cursor-default disabled:bg-[#8b949e] disabled:text-white focus-visible:ring-2 focus-visible:ring-[#2563eb]/40 focus-visible:ring-offset-0"
            :aria-label="t('ai.panel.send')"
            :disabled="!canSubmit"
            @click="send"
          >↑<span class="tip" :class="TIP_CLASS" aria-hidden="true">{{ t('ai.panel.send') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span></button>
        </div>
      </div>
      <!-- M5（复审）：播报节流 —— 计数文本自身**不带** aria-live（否则每敲一个字都播报一次），
           改由这个屏读专用 live region 只在**档位切换**时说话：其文本在一个档位内是常量，
           DOM 文本因此只在"余量不足/到顶/超限"的跃迁上变化。 -->
      <span class="sr-only" data-testid="composer-count-live" aria-live="polite">{{ composerTierAnnouncement }}</span>
    </div>

    <!-- R-165 S5b：mermaid 全屏查看器（demo v7.7 :1948-1964 重做口径）——
         深色 scrim（bg-black/50）+ 居中白色圆角容器（圆角 10 · 边框 #e3e6ea · 工具条 #f6f7f9）；
         打开/复位按 stage 完整适配居中；滚轮以指针为锚点缩放、拖拽走 transform 平移；
         工具条 −/+/百分比/复位/复制源码/下载 SVG/关闭 全为图标钮（aria-label + tip）；
         Esc / 点 scrim 关闭；role=dialog + aria-modal；SVG 容器 select-text（图中文字可直接框选）。 -->
    <div
      v-if="mmdViewerOpen"
      class="fixed inset-0 z-[70] flex items-center justify-center bg-black/50 p-[22px]"
      role="dialog"
      aria-modal="true"
      :aria-label="t('ai.panel.mermaidViewer')"
      data-testid="mmd-viewer"
      @click.self="closeMmdViewer"
    >
      <div class="flex h-[min(760px,88vh)] w-[min(1180px,94vw)] flex-col rounded-[10px] border border-solid border-[#e3e6ea] bg-white shadow-[0_18px_48px_rgba(0,0,0,.28)]">
        <div class="flex flex-none items-center gap-[8px] rounded-t-[9px] border-0 border-b border-solid border-[#eef0f3] bg-[#f6f7f9] px-[12px] py-[8px] text-[12.4px]">
          <span class="text-[#9370DB] text-[11px]">◈</span>
          <b class="[font-weight:650]" data-testid="mmd-viewer-title">{{ mmdViewerTitle }}</b>
          <span class="font-mono text-[10.5px] text-[#8b949e]">适配 · 缩放</span>
          <span class="ml-auto flex items-center gap-[6px]">
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-zoom-out"
              :aria-label="t('ai.panel.zoomOut')"
              @click="zoomMmdViewer(1 / 1.25)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.zoomOut') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <span class="min-w-[46px] text-center font-mono text-[11.5px] tabular-nums text-[#57606a]" data-testid="mmd-zoom-pct">{{ mmdViewerPct }}%</span>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-zoom-in"
              :aria-label="t('ai.panel.zoomIn')"
              @click="zoomMmdViewer(1.25)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14"></path><path d="M5 12h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.zoomIn') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <span class="mx-[2px] h-[18px] w-[1px] bg-[#e3e6ea]"></span>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-reset"
              :aria-label="t('ai.panel.resetZoom')"
              @click="resetMmdViewer"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7"></path><path d="M3 4v5h5"></path></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.resetZoom') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="[MMD_COPY_ICON_BTN_CLASS, mmdViewerCopied ? 'is-copied' : '']"
              data-testid="mmd-copy"
              :aria-label="t('ai.panel.copySource')"
              @click="copyMmdViewerSource"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-copy"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-check"><polyline points="20 6 9 17 4 12"></polyline></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.copySource') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-download"
              :aria-label="t('ai.panel.downloadSvg')"
              @click="downloadMmdViewerSvg"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12"></path><path d="m7 10 5 5 5-5"></path><path d="M5 21h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.downloadSvg') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-close"
              :aria-label="t('ai.panel.closeFullscreen')"
              @click="closeMmdViewer"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
              <span class="tip" :class="TIP_CLASS">{{ t('ai.panel.closeFullscreen') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
          </span>
        </div>
        <div
          ref="mmdViewerStageRef"
          tabindex="-1"
          class="relative min-h-0 flex-1 cursor-grab select-none overflow-hidden rounded-b-[9px] bg-[#fbfbfc]"
          data-testid="mmd-viewer-stage"
          @wheel.prevent="onMmdViewerWheel"
          @mousedown="onMmdViewerDragStart"
        >
          <div ref="mmdViewerPanRef" data-testid="mmd-viewer-pan" class="absolute inset-0 flex items-center justify-center [transform:translateX(var(--px,0px))_translateY(var(--py,0px))]">
            <div ref="mmdViewerZoomRef" data-testid="mmd-viewer-zoom" v-html="mmdViewerSvg" class="flex-none select-text"></div>
          </div>
        </div>
      </div>
    </div>

    <dialog
      ref="newChatDialogRef"
      class="m-auto max-h-[calc(100dvh-32px)] w-[min(360px,calc(100vw-32px))] overflow-auto overscroll-contain rounded-[14px] border border-solid border-[#e3e6ea] bg-white p-0 text-[#1f2328] shadow-[0_24px_64px_rgba(16,24,40,0.24)] backdrop:bg-[rgba(15,23,42,0.4)]"
      aria-labelledby="new-chat-title"
      aria-describedby="new-chat-desc new-chat-note"
      data-testid="new-chat-dialog"
      @close="onNewChatDialogClose"
    >
      <form method="dialog" class="px-[18px] pb-[16px] pt-[18px]">
        <span class="grid h-[30px] w-[30px] place-items-center rounded-full border border-solid border-[#f0d5d0] bg-[#fdf1ef] text-[15px] font-bold leading-none text-[#c0392b]" aria-hidden="true">!</span>
        <h2 id="new-chat-title" class="m-0 mt-[10px] text-[15.5px] leading-[1.45] [font-weight:650]">{{ t('ai.panel.newChatConfirm.title') }}</h2>
        <p id="new-chat-desc" class="m-0 mt-[6px] text-[12.8px] leading-[1.7] text-[#57606a]">{{ t('ai.panel.newChatConfirm.description') }}</p>
        <p v-show="newChatNote !== null" id="new-chat-note" class="m-0 mt-[8px] rounded-[8px] border border-solid border-[#fde68a] bg-[#fffbeb] px-[9px] py-[7px] text-[12.2px] leading-[1.65] text-[#b45309]">{{ newChatNote }}</p>
        <div class="mt-[16px] flex justify-end gap-[8px]">
          <button type="submit" value="cancel" autofocus class="rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[14px] py-[6px] text-[12.8px] leading-[1.5] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]">{{ t('ai.panel.newChatConfirm.cancel') }}</button>
          <button type="submit" value="confirm" class="rounded-[8px] border border-solid border-transparent bg-[#c0392b] px-[14px] py-[6px] text-[12.8px] font-semibold leading-[1.5] text-white transition-all duration-[150ms] hover:brightness-[1.06]">{{ t('ai.panel.newChatConfirm.confirm') }}</button>
        </div>
      </form>
    </dialog>
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
 * - **消息体按段渲染（对话时序包 批2 权威翻转）**：`parts` 段序列（text / reasoning / tool）
 *   是渲染唯一真值，按真实时序自上而下排列 —— "正文 → 工具卡 → 正文"夹心轮次才显出真实顺序；
 *   挂起卡跟随其工具段位置（③b 把两者并成一卡前的过渡形态）；复制按同一 parts 契约组合
 *   `deriveCopyText` 正文与 `shouldShowToolPart` 过滤后的 `toolSummary`，段间空行；
 * - 工具卡四态（调用中/成功/失败/待执行·待确认按通道）并展示参数与结果 —— 蓝本是"已执行工具 X"单标签；
 * - 生成式表单挂起卡（GF-B）：generative_form 帧渲染 FormRenderer 等用户填写，取消/超时带
 *   cancelled:true 回灌 —— 蓝本无此前端工具通道；
 * - HITL 卡片：确认/拒绝双路径（无原因输入、无倒计时 —— 2026-10-20 去超时裁决与 H1 口径）；
 * - 409/503 降级提示与恢复最终失败提示（自动重试、对账与续收过程静默）；
 * - 头部「新建对话」文字按钮 + 空态页签语义小字（苞 A 项 1）：确认后清空当前会话并换新 sessionId
 *   （旧会话由 `DELETE /api/ai/session/{sessionId}` 完整销毁）—— 蓝本无"重开"入口；
 * - 逐条复制（苞 B 项 2）：消息 hover/聚焦时显现「复制」，复制正文与可见工具卡摘要；
 *   不含思考链、工具原始参数/结果诊断及普通 skipped 卡；
 *   因 `vite.config.ts` 的 `host: '0.0.0.0'` 存在经内网 http 访问的**非安全上下文**面
 *   （`navigator.clipboard` 为 undefined），保留 `document.execCommand('copy')` 回退；
 * - 历史对账期骨架屏（苞 B 项 4）：消费 store 的 `historyLoaded`，与空态互斥 —— 蓝本无骨架屏。
 *
 * 裁剪性：本组件只依赖 stores/ai 与 stores/workspace 的窄接口；App.vue 收起 AI 栏
 * （48px 竖排条）或整体移除本组件后，五页业务功能全部照常可用。
 *
 * 样式纪律：模板用 Element Plus（未重构区）+ 内联 Tailwind 工具类；R-165 S1 起，
 * **消息区三件套（工具卡 / 操作行 / 状态标签）零 EP 组件承载**，全部改原生元素 + Tailwind 工具类
 * （demo v7 配方照搬，含 preflight 关闭下的 UA 复位 bg-transparent border-0 font-[inherit]）。
 * R-165 S2 续作：**思考卡 / HITL 门卡 / 回执卡 / 6 处顶部提示（el-alert）/ 空态（el-empty）/
 * 骨架屏（el-skeleton）/ 操作行 tip 气泡** 一并零 EP 承载，同口径纯 Tailwind；
 * 门卡的 el-input/el-button 与空态的 el-button/el-icon 同步退场（原生 textarea/button/span）。
 * R-165 S3a 收口：**输入区 composer**（el-textarea + el-button 全退）改原生结构——
 * 问题4 composer v2（2026-10-19）起 rows=1 + `field-sizing:content`（1 行 40px 起 / 4 行 101px 封顶）、
 * 白底 focus-within shell + 28×28 圆形钮（停止 #1f2328 白方块 / 发送 #2563eb 白图标）；
 * 同批按指挥官裁决收三项：R6 消息行撤 `mb-3.5`（间距回归元信息行单值 18px）、
 * R7 fab 回 `bottom-3`、R11 error 级通知改 v7 `.enotice` 红族（普通通知保持灰底虚线）。
 * R-165 S4（用户六项直裁）：① 操作行按钮**常驻**（撤 opacity-0/group-hover 浮出）；② 用户长消息
 * 折叠/展开（>184px 限高 + 底部渐隐 + 「展开/收起」，本地态，全 Tailwind）；③ md-body 末块外边距
 * 归零 + 用户侧字号 13.5px 真落地 + 气泡 `w-full` 按 role 分派；④ 间距对齐 v7（助手侧去 py-2、
 * 助手元信息行 mt-[12px]）；⑤ 浮钮回「居中常显文字胶囊」+ 滞回带放宽至 160/32。
 * 唯一的 scoped CSS 是蓝本确有的 18 行打字机动画（`.typing .dot` + `@keyframes blink`）
 * 与 R-161 的 scroll-smooth/reduced-motion 两条；S2 的 tip 气泡用 Tailwind **具名组**写法 `group/tip`
 * （S4 起消息行不再需要匿名 `group` 承载浮出，但辅助类与既有 DOM 语义保持不动）。
 * S4 铁律：零手写 CSS、零行内 style、零 style 块新增，全部走 Tailwind 任意值/变体类。
 * R-165 S5a（demo v7.7 终裁十项）：① 卡片堆叠下外边距体系（工具卡 mb-2 / 门卡·GF mb-2.5 /
 * 回执·思考·流程图 mb-3.5）；② 回执卡折叠重设计（「查看详情 ⇄ 收起详情」+ 单层 mono JSON 盒，
 * 外层包裹盒/小节标签退役，`border-t border-solid` 单边写法在 preflight 关闭下的 3px 伪影一并清）；
 * ③ 用户气泡全圆角 12px；④ 限高族（纯限高内滚无展开钮；2A 批起统一双约束，见下）；
 * ⑤ 长表格双区（表头不滚 + 表体内滚 + 横滚联动）；⑥ 长消息折叠 184px
 * + 遮罩绝对值写法；⑦ 复制全图标化 + 绿勾 2s 反馈（代码块 / mermaid 源码 / 消息复制）；
 * ⑧ 6px 圆角半透明滚动条（任意变体类）；⑨ focus-visible 聚焦环；⑩ GF 取消钮聚拢右对齐。
 * R-165 S5b（demo v7.7 裁决区 12 + 07 双态重做）：mermaid 卡改「卡头分段 tab 预览 | 源码」双态
 * （底部「查看源码」钮退役）+ 卡头右端图标钮组（缩放读数 / 复位 / 全屏）；预览态固定可用高度
 * + overflow-hidden，首次/复位完整适配居中，滚轮缩放 + transform 拖拽平移，放大后局部裁切；
 * 源码态限高内滚 mono 盒 + 复制源码图标钮（绿勾反馈沿用 S5a 统一机制）；全屏查看器重做
 * （scrim + 白容器 · 指针锚定缩放 · transform 平移 · 6 钮工具条 · Esc/点 scrim 关闭 · role=dialog）。
 * 结构类名换 `mmdc` 前缀（旧 `.mmd*` CSS 规则零引用但本批禁删禁改，S5c 统一拆）。
 *
 * AI 卡片治理现行口径：普通工具、HITL 与主面板 GF 共用 BusinessCardHeader；
 * 卡头只显示当前单一状态，所有正式卡片均可手动折叠，未触碰时按状态矩阵取默认展开。
 * 普通工具与 HITL 主内容限高 min(280px,35vh)，GF 字段限高 min(320px,45vh)；
 * 每张卡最多一个纵向滚动区，操作按钮与高风险复选框固定在滚动区外。
 * 块间距统一为 Markdown→卡 16px、卡→卡 10px、卡→Markdown 12px；
 * 原始 JSON、轮次标识与机器诊断原文不上用户卡面。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { head } from 'lodash'
import { ArrowRight } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai'
import { useI18n } from 'vue-i18n'
import { formatProgressiveTs } from '@/utils/format'
import { cardFieldLabel, localizedCardFieldValue, canShowCardField } from '@/utils/card-content'
import {
  COPY_ICON_BTN_CLASS,
  COPY_ICON_SVGS,
  COPY_STATE_CLASS,
  MD_BODY_ROOT_CLASS,
  SCROLLBAR_CLASS,
  TIP_ARROW_CLASS,
  TIP_CLASS,
  DEGRADE_AT_CHARS,
  hasUnclosedFence,
  renderMarkdown,
  renderMermaidSvg,
  setStreamFlushHandler
} from '@/utils/markdown'
import { toolSummary } from '@/utils/tool-summaries'
import {
  argRows,
  cardStatusOf,
  cardTypeOf,
  formSpecOf,
  isBlankText,
  isFormAwaitingOutcome,
  isReadOnlyCard,
  parsePublishConflicts,
  readOnlyStateOf,
  reasonHeadline,
  MASKED_VALUE,
  readableValue,
  type ConflictRow,
  type FieldRow,
  groupParts,
  type Block
} from '@/utils/blocks'
import { deriveCopyText, numericArgOf, partDiagnosticOf, isPartResultInternal, isSystemRejectPhantom, userTextArgsOf, type PartDiagnostic } from '@/utils/parts'
import { projectDisplayText } from '@/utils/display-text'
import { isRenderableView } from '@/utils/resultview'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import ResultViewRenderer from '@/components/AiPanel/resultview/ResultViewRenderer.vue'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'
import UploadHandoffCard from '@/components/AiPanel/UploadHandoffCard.vue'
import ContextPill from '@/components/AiPanel/ContextPill.vue'
import ReasoningHeader from '@/components/AiPanel/ReasoningHeader.vue'
import BusinessCardHeader from '@/components/AiPanel/BusinessCardHeader.vue'
import {
  isBusinessCardDefaultExpanded,
  businessCardStatusTone,
  hasUnknownReviewRequirement,
  isUnknownUnrecoverable,
  type BusinessCardFamily,
  type BusinessCardTone
} from '@/components/AiPanel/cardPresentation'
import { GENERATIVE_FORM_TOOL, type AiLoadErrorView, type ChatMessage, type MessagePart, type SheetPreviewLevel, type SheetWritePlan, type TextPart, type ToolPart } from '@/types/ai'
import type { ActiveUploadHandoff } from '@/types/ui-handoff'
import { SHEET_PREVIEW_TOOLS, UI_HANDOFF_TOOLS } from '@/types/tools'
import { resolveAiRef } from '@/utils/ai-regions'
import { parseToolArgs } from '@/utils/frontend-tools'
import type { SheetAppliedSummary } from '@/utils/sheet-tools'
import type { FormSpec } from '@/types/form-schema'
import type { ErrorCardVariant } from '@/utils/error-card'
import { logCaughtError } from '@/utils/log-caught-error'


const { t, locale } = useI18n()
const ai = useAiStore()
const formAttachmentAbort = ref(new AbortController())
const sheetPreviewSnapshots = ref<Record<string, SheetWritePlan>>({})
const uploadHandoffSnapshots = ref<Record<string, ActiveUploadHandoff>>({})

watch(() => ai.sessionId, () => {
  sheetPreviewSnapshots.value = {}
  uploadHandoffSnapshots.value = {}
})

function currentMessageToolCallIds(): Set<string> {
  const ids = new Set<string>()
  for (const message of ai.messages) {
    for (const part of message.parts) {
      if (part.kind === 'tool') {
        ids.add(part.toolCallId)
      }
    }
  }
  return ids
}

const messageToolCallIdSignature = computed(() => JSON.stringify([...currentMessageToolCallIds()].sort()))

watch(messageToolCallIdSignature, () => {
  const retained = currentMessageToolCallIds()
  for (const toolCallId of Object.keys(sheetPreviewSnapshots.value)) {
    if (!retained.has(toolCallId)) {
      delete sheetPreviewSnapshots.value[toolCallId]
    }
  }
  for (const toolCallId of Object.keys(uploadHandoffSnapshots.value)) {
    if (!retained.has(toolCallId)) {
      delete uploadHandoffSnapshots.value[toolCallId]
    }
  }
})

watch(() => ai.activePreview, (plan) => {
  if (plan) {
    sheetPreviewSnapshots.value[plan.toolCallId] = { ...plan }
  }
}, { immediate: true })

watch(() => ai.activeUploadHandoff, (handoff) => {
  if (handoff) {
    uploadHandoffSnapshots.value[handoff.toolCallId] = { ...handoff }
  }
}, { immediate: true })

function resetFormAttachmentAbort(): void {
  formAttachmentAbort.value.abort()
  formAttachmentAbort.value = new AbortController()
}

/**
 * 批次6A：消息流（`role="log"`）的 `aria-busy` —— 加载与流式两种"还在变"的状态。
 *
 * - 历史加载期（骨架屏）：整段内容尚未对账完；
 * - 任一轮在流式（含刷新在途/续跑）：内容仍在增长。
 * 读屏据此延后播报，不把半截内容当最终结果念。
 */
const logBusy = computed(() => !ai.historyLoaded
  || ai.restoringRecovery
  || ai.loading
  || ai.messages.some((message) => message.streaming))

/**
 * B4：消息级压缩秒表 —— 只在本轮真实处于压缩中时每秒推进，组件卸载/压缩结束即停。
 * 服务端首帧给了 elapsedMs，store 已折算成客户端起点；这里只做展示，不伪造进度。
 */
const compactingNow = ref(Date.now())
let compactingTicker: ReturnType<typeof setInterval> | null = null

function stopCompactingTicker(): void {
  if (compactingTicker !== null) {
    clearInterval(compactingTicker)
    compactingTicker = null
  }
}

watch(
  () => ai.messages.some((message) => message.compactingStartedAtMs !== undefined && !message.done),
  (active) => {
    stopCompactingTicker()
    compactingNow.value = Date.now()
    if (active) {
      compactingTicker = setInterval(() => {
        compactingNow.value = Date.now()
      }, 1000)
    }
  },
  { immediate: true }
)
onBeforeUnmount(stopCompactingTicker)

function compactingSeconds(message: ChatMessage): number {
  if (message.compactingStartedAtMs === undefined) {
    return 0
  }
  return Math.max(0, Math.floor((compactingNow.value - message.compactingStartedAtMs) / 1000))
}

function compactingLabel(message: ChatMessage): string {
  const seconds = compactingSeconds(message)
  return seconds > 0
    ? t('ai.panel.compactingMessageElapsed', { seconds })
    : t('ai.panel.compactingMessage')
}

/**
 * B4 可访问播报：只播**进入压缩**与**已提交完成**（`context{committed:true,eventId}`）。
 * 紫色退场不是事实（失败/停止/未提交都会退场），因此不播退出；秒表只走可见文本，
 * 不在 live region 内 —— 读屏不会被逐秒打断。
 */
const compactingActive = computed(() => ai.messages.some((message) => isCompacting(message)))
const compactionAnnouncement = ref('')
/** 播报序号：清空→写入两步之间若又有新事件，旧的那次作废（只播最新事实）。 */
let compactionAnnounceSeq = 0
/**
 * live region 只在**文本变化**时播报：先清空并等一帧，再写入目标文案。
 * 这样连续两次相同文案（如两次 L1 提交都播「完成」）也各产生一次 DOM 变化，读屏会重读。
 */
async function announceCompaction(text: string): Promise<void> {
  const seq = ++compactionAnnounceSeq
  compactionAnnouncement.value = ''
  await nextTick()
  if (seq !== compactionAnnounceSeq) {
    return
  }
  compactionAnnouncement.value = text
}
watch(compactingActive, (active, was) => {
  if (active && !was) {
    void announceCompaction(t('ai.panel.compactingMessageAria'))
  }
})
// 没有紫色过程态（L1 / 回放）时，已提交事实到达也播一次完成（相同文案也会重播）
watch(
  () => ai.lastCommittedCompactionEventId,
  (eventId) => {
    if (eventId !== null && eventId !== '') {
      void announceCompaction(t('ai.panel.compactionFinishedAria'))
    }
  }
)
// 换会话：播报现场不跨会话（否则下一会话的首次同文案会被当成"没变化"吞掉）
watch(
  () => ai.sessionId,
  () => {
    resetFormAttachmentAbort()
    compactionAnnounceSeq += 1
    compactionAnnouncement.value = ''
  }
)

/**
 * 批次2 的恢复与轮次动作**唯一适配层**（批次3 起 store 由本批维护；适配层保留，
 * 继续隔离字段/动作名的变化，避免模板直接拼状态）。
 *
 * <p>面板只经下面这组函数读状态 / 调动作；store 侧字段或 action 名变化时**只改这里**。
 * 已对齐 store 的实际契约（类型化直读，不再按候选名嗅探）：`manualRecovery`、
 * `retryLoadHistory()` / `retryRestoreSnapshot()`、`busyRun`（`AiBusyRunRef`）+ `reattachBusyRun()`、
 * `message.unconfirmed` 与 `lastRoundOutcome='unconfirmed'`。
 * <ul>
 * <li>最终恢复错误：`manualRecovery`；按 scope 重试
 *     `retryLoadHistory`（history）/ `retryRestoreSnapshot`（current）。</li>
 * <li>409 在途轮：`busyRun.runId`；动作 `reattachBusyRun()`（一次性对账 + 静默续收）。
 *     runId 不进任何用户可见文本；没有 `busyRun` 就不出入口（不拿 `activeRunId` 猜）。</li>
 * <li>继续：失败/断流的最后一条助手消息，无论有无正文/思考，都走正常
 *     `send('继续')`，立即显示用户消息并新建普通轮次；停止/取消、unconfirmed、非末轮不出。
 *     配额错误卡带「继续」时，元信息行不重复该入口。
 *     普通 regenerate = **本批不做、入口整体隐藏**（无禁用 / unsupported 死 UI，也不本地截断重发）。</li>
 * </ul>
 */

/** 最终恢复失败的视图（scope 决定重试动作；内部恢复台账不直接上屏）。 */
interface RecoveryLoadError {
  scope: 'history' | 'current'
  label: string
  detail: string
}

function loadErrorDetail(view: AiLoadErrorView): string {
  if (view.code === 'NETWORK_ERROR' || view.status === 0) {
    return t('ai.panel.loadErrorNetwork')
  }
  if (view.status >= 500) {
    return t('ai.panel.loadErrorServer')
  }
  return t('ai.panel.loadErrorUnknown')
}

function recoveryLoadError(): RecoveryLoadError | null {
  const recovery = ai.manualRecovery
  if (recovery === null) {
    return null
  }
  if (recovery.reason === 'archive-window') {
    return { scope: 'history', label: t('ai.panel.recoveryGap'), detail: t('ai.panel.recoveryGapHint') }
  }
  if (recovery.reason === 'unconfirmed') {
    return { scope: 'history', label: t('ai.panel.turnUnconfirmed'), detail: t('ai.panel.turnUnconfirmedHint') }
  }
  if (recovery.reason === 'first-frame') {
    return { scope: 'current', label: t('ai.panel.recoveryFailed'), detail: t('ai.notice.firstFrameTimeout') }
  }
  if (recovery.reason === 'interrupted') {
    return { scope: 'current', label: t('ai.panel.recoveryFailed'), detail: t('ai.notice.interruptedKeepShort') }
  }
  if (recovery.error === null) {
    return null
  }
  return {
    scope: recovery.scope,
    label: recovery.scope === 'current' ? t('ai.panel.recoveryFailed') : t('ai.panel.historyLoadFailed'),
    detail: loadErrorDetail(recovery.error)
  }
}

function recoveryErrorLabel(): string {
  return recoveryLoadError()?.label ?? ''
}

function recoveryErrorText(): string | null {
  return recoveryLoadError()?.detail ?? null
}

function retryRecoveryLoad(): void {
  if (ai.restoringRecovery) {
    return
  }
  if (recoveryLoadError()?.scope === 'current') {
    void ai.retryRestoreSnapshot()
    return
  }
  void ai.retryLoadHistory()
}

const recoveryErrorVisible = computed(() => recoveryErrorText() !== null)

/** 409 回执里的进行中轮（`busyRun.runId`）；null = 后端没给可信 runId ⇒ 不出入口（不拿 activeRunId 猜）。 */
function busyRunId(): string | null {
  const runId = ai.busyRun?.runId
  return typeof runId === 'string' && runId.trim() !== '' ? runId : null
}

function canReturnToActiveRun(): boolean {
  return busyRunId() !== null
}

function returnToActiveRun(): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.reattachBusyRun()
}

/** 继续：以用户身份正常发送「继续」，与手动输入一样立即显示并进入对话历史。 */
function continueTurn(_message: ChatMessage): void {
  if (ai.restoringRecovery) {
    return
  }
  clearStreamMdCache()
  void ai.send(t('ai.panel.continue'))
}

const inputText = ref('')
/**
 * 安全防护专项批①：单条消息的字符上限——与后端 {@code app.ai.input.max-chars}（2000）**同值同尺**
 * （判据 = UTF-16 码元数，即 JS `String#length` / `<textarea maxlength>` 的计数口径，中文按 1 字算）。
 * 前端只做"不让写出超限文本 + 提前告知"，真正的拒绝仍在后端（400 MESSAGE_TOO_LONG，不截断）。
 */
const MESSAGE_MAX_CHARS = 2000
const msgWrapRef = ref<HTMLElement | null>(null)
/**
 * 批 D · D2②：滚动容器内的**内容层**。RO 观测它（而不是滚动容器本身）——
 * 滚动容器的盒子由 flex 定死，内容长高时它自己的 box 不变，观测它对"内容增长"零触发。
 */
const msgContentRef = ref<HTMLElement | null>(null)
/** R-165 S5c：输入框 ref —— 错误卡「改写后重发」把焦点交回输入框（改写是用户动作）。 */
const composerRef = ref<HTMLTextAreaElement | null>(null)

/**
 * R-154 #5 → R-161：钉底跟随的「用户上滑即暂停」策略。
 * followBottom = 自动钉底开关：容器 @scroll 离底判定 —— 用户上滑（离底超过阈值）置 false
 * 暂停自动跟随，回到底部附近自动恢复；唯一点「回到底部」浮钮 = 强制恢复并滚底。
 * 渲染真值仍为 parts（与自动滚动指纹 watch 同源），禁读 content。
 * R-161 阈值拆分：一值双用是「上滚一点就出钮」的根因 —— 跟随闸 32px 保留，
 * 浮钮显隐走独立滞回带（常量化与 clientHeight 无关）。
 * R-165 S4 项 5（用户直裁）：滞回带放宽 —— 显阈 64 → **160**（一屏内的轻微上滑不再弹钮）、
 * 隐阈 24 → **32**（回到贴底才收，与跟随闸同值）；FOLLOW_THRESHOLD_PX 32 不动。
 */
const followBottom = ref(true)
/** 跟随解除/恢复闸（px）：离底小于它即视为贴底。 */
const FOLLOW_THRESHOLD_PX = 32
/** R-161 浮钮滞回（R-165 S4 放宽）：离底大于它才显示。 */
const SHOW_BACK_TO_BOTTOM_PX = 160
/** R-161 浮钮滞回（R-165 S4 调整为 32）：离底小于它才隐藏。 */
const HIDE_BACK_TO_BOTTOM_PX = 32

/**
 * 批 D · D2③：跟随判定的**滞回窗**（ms）。
 *
 * 场景：pinBottom 把 scrollTop 钉到新底部之后，浏览器还会为「内容长高 / 钉底本身」补派
 * scroll 事件 —— 那一刻读到的离底距离可能已经是下一帧内容的（> 32px），跟随闸被误翻成
 * false，于是自动跟随"自己把自己停了"，后续内容增长又因 followBottom=false 整段跳过
 * （用户看到的断续 / 拖回）。
 *
 * 口径：**钉底后 150ms 内、且没有用户滚动意图时，scroll 事件只允许"恢复"跟随，不允许"解除"**。
 * 用户真滚动由 wheel / touch / 键盘的意图标记（{@link markUserScrollIntent}）识别 ——
 * 意图先于 scroll 事件到达，因此正常上滑一秒都不会被吞。
 */
const PIN_SETTLE_MS = 150
/** 用户滚动意图的保鲜期（ms）：wheel/touch/键盘之后这段时间的 scroll 事件按用户行为判定。 */
const USER_INTENT_MS = 300

/** 钉底静默期截止时刻（过去时刻 = 不在静默期）。 */
let pinSettleUntil = 0
/** 最近一次用户滚动意图时刻。 */
let userIntentAt = 0

/** wheel/touch/键盘滚动 = 用户显式意图（模板上的被动监听，零阻止、零视觉）。 */
function markUserScrollIntent(): void {
  userIntentAt = Date.now()
}

function hasRecentUserIntent(): boolean {
  return Date.now() - userIntentAt <= USER_INTENT_MS
}

/** R-161：pinBottom() 的落点。程序化钉底后浏览器异步派发的 scroll 回声按落点识别
 *  （容差 ≤1px，会审 M1）：回声只刷浮钮显隐，不动跟随闸 —— 否则「已在底部时内容插入」
 *  把 scrollHeight 顶高后回声读中间态离底（demo 打点实证 42px）会误锁跟随。 */
let pinGuardTop: number | null = null

/** 离底距离（px）。 */
function bottomDist(wrap: HTMLElement): number {
  return wrap.scrollHeight - wrap.scrollTop - wrap.clientHeight
}

function onMsgScroll(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  const dist = bottomDist(wrap)
  // 回声闸：|scrollTop − 落点| ≤ 1px ⇒ 程序化钉底的回声，跳过跟随闸
  if (pinGuardTop !== null && Math.abs(wrap.scrollTop - pinGuardTop) <= 1) {
    updateBackToBottom(dist)
    return
  }
  // D2③ 滞回：钉底静默期内、没有用户意图 ⇒ 这是布局补偿 / 内容增长引发的 scroll，
  // 不参与跟随判定（只允许它把跟随"恢复"回来）。
  if (Date.now() < pinSettleUntil && !hasRecentUserIntent()) {
    if (dist < FOLLOW_THRESHOLD_PX) {
      followBottom.value = true
    }
    updateBackToBottom(dist)
    return
  }
  pinGuardTop = null
  followBottom.value = dist < FOLLOW_THRESHOLD_PX
  updateBackToBottom(dist)
}

/** R-161 滞回带显隐：带内（HIDE ~ SHOW，R-165 S4 起 32~160）保持原状，防边界抖动；
 *  非 !followBottom 纯计算（会审 M3）。 */
function updateBackToBottom(dist: number): void {
  if (dist > SHOW_BACK_TO_BOTTOM_PX) {
    showBackToBottom.value = true
  } else if (dist < HIDE_BACK_TO_BOTTOM_PX) {
    showBackToBottom.value = false
  }
}

const showBackToBottom = ref(false)

/** R-161 平滑回底时长（demo 口径）：CSS scroll-behavior:smooth 类挂 ~600ms 后移除。 */
const SMOOTH_BACK_MS = 600
let smoothBackTimer: ReturnType<typeof setTimeout> | null = null
/**
 * 平滑回底开关 —— **必须走模板 :class**，不能再 `wrap.classList.add`：滚动容器挂着动态
 * `:class`（跟随态 `[overflow-anchor:none]` 等），每次重渲都会整串覆写 className，
 * 命令式挂上的 `scroll-smooth` 会被静默抹掉（用户点「回到底部」不再有平滑）。
 */
const smoothBackActive = ref(false)

/**
 * 「回到底部」浮钮：挂 CSS scroll-behavior:smooth 类 + pinBottom（会审 M2：禁
 * scrollTo({behavior:'smooth'})，jsdom 不同步更新 scrollTop，保既有同步断言）；
 * 显式收钮（会审 M3：滞回态非纯计算，否则「点击后钮消失」不成立）；约 600ms 后移除类，
 * 期间用户滚动可打断（浏览器语义）；流式期 pinBottom 恒瞬跳，不走 smooth。
 *
 * 类走响应式字段 ⇒ 本轮 patch 落地后容器才真正带 smooth，钉底因此排到 nextTick：
 * 先写 scrollTop 的话浏览器按 auto 瞬跳，smooth 就等于没挂。
 */
function backToBottom(): void {
  if (!msgWrapRef.value) {
    return
  }
  smoothBackActive.value = true
  if (smoothBackTimer !== null) {
    clearTimeout(smoothBackTimer)
  }
  smoothBackTimer = setTimeout(() => {
    smoothBackActive.value = false
    smoothBackTimer = null
  }, SMOOTH_BACK_MS)
  void nextTick(pinBottom)
  showBackToBottom.value = false
}

/**
 * R-165 S4 项 2（用户直裁）→ S5a 项 6（终裁 200 → 184px）：
 * **用户长消息展开/折叠**。
 * - 判据：用户消息正文（md-body）自然高 > 184px（= 8 行 @ 13.5px × 1.7 ≈ 23px/行；原 200px
 *   ≈ 8.7 行会切在行半腰）⇒ 折叠态：限高 + 裁切 + 底部渐隐；
 * - 「展开/收起」为**该消息本地态**（展开集 Set<消息 id>），不进 store、不写后端；
 * - 溢出判定 = 单一 measure（直读 scrollHeight，无观测器依赖）——
 *   但**不复用内联 style 写法**（渐隐改任意值类 `[mask-image:...]`，S4 铁律：零行内 style）。
 *   （S6 批1 起该口径只剩此处一处：CollapsibleText 组件已退役，四条内容区改纯限高内滚。）
 */
const USER_MSG_MAX_HEIGHT_PX = 184

/** S4 折叠态类 → S5a 项 6：限高 184px + 裁切 + 底部渐隐。
 *  2A 批（裁决 §14.4）：**用户长消息折叠 184px / 8 行维持不变**（本轮唯一不加双约束的限高族）。
 *  遮罩改**绝对值**写法 `calc(100% - 90px)`（末 90px 渐隐，与折叠高度解耦 —— 以后再调高度不必再
 *  改百分比；v7.7 终裁口径）。 */
const USER_MSG_COLLAPSE_CLASS = 'max-h-[184px] overflow-hidden [mask-image:linear-gradient(to_bottom,#000_calc(100%_-_90px),transparent)]'

/** md-body 修正类（S4 项 3）：末块下外边距归零；用户侧另加字号直命中（根容器自身声明 13.8px
 *  会压继承，S2 遗留 13.5px 未真生效 ⇒ 用内部选择器直命中 p/li）。
 *  R-165 S5a 项 4：代码块 `pre` 纯限高内滚；2A 批（裁决 §14.4 代码族）改双约束
 *  `min(280px,35vh)` ——`figure.code>pre` 的任意变体特异度高于渲染层注入在 `pre` 自身上的
 *  排版类，故无需 `!`（同值也保证 degraded 路与正常路一致）。
 *  R-165 S5c：末块外边距的**声明方**已是渲染层注入的工具类（markdown.ts `MD_P_CLASS` 等），
 *  这里的 `!` 从「压手写 CSS 的 (0,2,1)」变成「压渲染层同级工具类」的双保险，语义不变。 */
const CODE_PRE_LIMIT_CLASS = '[&_figure.code>pre]:max-h-[min(280px,35vh)] [&_figure.code>pre]:overflow-y-auto'
const MD_BODY_USER_CLASS = `[&>*:last-child]:!mb-0 [&_p]:text-[13.5px] [&_li]:text-[13.5px] ${CODE_PRE_LIMIT_CLASS}`
const MD_BODY_AI_CLASS = `[&>*:last-child]:!mb-0 ${CODE_PRE_LIMIT_CLASS}`

/** 活动中提示只使用真实状态；文字与圆点的呼吸动画支持减少动效。 */
const BREATH_CLASS = 'loading-breath inline-flex items-center gap-[6px] m-0 pl-[2px] text-[12.2px] text-[#57606a]'
const FADE_OUT_CLASS = 'fade-out animate-fade-out'

/**
 * R-165 S5c：错误卡三变体 + 通用红卡的配色/图标（demo v7.7 裁决区 08 改后 v7.4 定稿逐条照搬）：
 * 429 红族（! 圆标）/ 内容过滤灰族（⊘）/ 上下文超限蓝族（i）/ 未分类故障沿用红卡。
 * 卡壳与动作钮的色族同源，避免「红壳配蓝钮」这类跨族混搭。
 */
const ERROR_CARD_TONE: Record<ErrorCardVariant, { box: string; icon: string; glyph: string; action: string }> = {
  quota: { box: 'border-[#f0d5d0] bg-[#fdf1ef]', icon: 'border-[#c0392b] text-[#c0392b] font-bold', glyph: '!', action: 'text-[#c0392b]' },
  filter: { box: 'border-[#e3e6ea] bg-[#f6f7f9]', icon: 'border-[#8b949e] text-[#8b949e]', glyph: '⊘', action: 'text-[#57606a]' },
  context: { box: 'border-[#cddcff] bg-[#eff4ff]', icon: 'border-[#2563eb] text-[#2563eb] font-bold', glyph: 'i', action: 'text-[#2563eb]' },
  generic: { box: 'border-[#f0d5d0] bg-[#fdf1ef]', icon: 'border-[#c0392b] text-[#c0392b] font-bold', glyph: '!', action: 'text-[#c0392b]' }
}

/**
 * 配额错误卡上的「继续」：只在失败/断流的最后一条助手消息上出现（与元信息行同一判据）。
 * 该动作显示时，元信息行不再重复出「继续」（{@link canShowContinue} 已排除）。
 */
function isErrorCardContinueVisible(message: ChatMessage): boolean {
  const card = message.errorCard
  return card != null && card.variant === 'quota'
    && Boolean(card.action)
    && isFailedLastAssistant(message)
}

function errorCardGuide(message: ChatMessage): string {
  if (!ai.restoringRecovery && message.errorCard?.variant === 'quota' && !isErrorCardActionVisible(message)) {
    return t('ai.card.error.quota.guideUnavailable')
  }
  return message.errorCard?.guide ?? ''
}

/** 错误卡动作文案：quota 卡固定按当前语言取「继续」（旧镜像里定格的旧文案不复活）。 */
function errorCardActionLabel(message: ChatMessage): string {
  return message.errorCard?.variant === 'quota'
    ? t('ai.card.error.quota.action')
    : message.errorCard?.action ?? ''
}

/**
 * 错误卡动作是否可见（批次2 / AR-17）：quota 卡的「继续」按 {@link isErrorCardContinueVisible}；
 * 其它变体的动作维持既有入口。不满足条件的动作**直接隐藏**，不做禁用 / unsupported 死 UI。
 */
function isErrorCardActionVisible(message: ChatMessage): boolean {
  const card = message.errorCard
  if (!card?.action) {
    return false
  }
  if (card.variant === 'quota') {
    return isErrorCardContinueVisible(message)
  }
  return true
}

/** 错误卡动作（= 三变体各自引导语的落点，全部复用本组件既有动作，不新增 store 出口）：
 *  「继续」= 与消息元信息行同一出口（{@link continueTurn}，正常发送「继续」）；
 *  「改写后重发」= 把焦点交回输入框（改写是用户动作，面板只能把落点指过去）；
 *  「开新会话」= 既有 `newChat()`（与面板头「新建对话」同一出口）。
 *  分派按 **variant**（i18n L-69 后 `action` 文案随 UI 语言变，字符串比对会断；variant → 动作是一对一）。 */
function runErrorCardAction(message: ChatMessage): void {
  const card = message.errorCard
  if (card === null || card === undefined) {
    return
  }
  if (card.variant === 'quota') {
    if (isErrorCardContinueVisible(message)) {
      continueTurn(message)
    }
    return
  }
  if (card.variant === 'filter') {
    composerRef.value?.focus()
    return
  }
  if (card.variant === 'context') {
    newChat()
  }
}

/** 用户消息 md-body 元素表（按消息 id 记，供单一 measure 读 scrollHeight）。 */
const userBodyEls = new Map<string, HTMLElement>()
/** 模板函数 ref 的稳定身份（同一 id 复用同一 setter，避免每次 patch 都被当成新 ref 重登）。 */
const userBodyRefSetters = new Map<string, (el: Element | { $el?: Element } | null) => void>()
/** 本轮真正需要重量的用户消息（新挂载 / 文本变化 / 展开切换 / 内容宽度变化）——不再逐条扫全表。 */
const dirtyUserMsgIds = new Set<string>()
/** 可观测性：每次 measure 实际读了哪些消息（只保留最近若干轮，避免调试面自身泄漏）。 */
const measurePasses: string[][] = []
/** 真溢出（正文高 > 184px）的用户消息 id 集 —— 只有它为真才渲染「展开/收起」。 */
const userMsgOverflowed = ref<Set<string>>(new Set())
/** 已展开的用户消息 id 集（默认收起 = 不在集内）。 */
const userMsgExpanded = ref<Set<string>>(new Set())

function markUserMsgDirty(id: string): void {
  dirtyUserMsgIds.add(id)
}

/** 稳定 ref setter：同一消息 id 返回同一函数，元素挂载/卸载才触发登记与脏标记。 */
function userBodyRefFor(id: string): (el: Element | { $el?: Element } | null) => void {
  let setter = userBodyRefSetters.get(id)
  if (setter === undefined) {
    setter = (el: Element | { $el?: Element } | null): void => setUserBodyRef(id, el)
    userBodyRefSetters.set(id, setter)
  }
  return setter
}

/** 模板函数 ref：用户消息正文元素登记/注销（收起态下 scrollHeight 仍为内容自然高，量取稳定）。 */
function setUserBodyRef(id: string, el: Element | { $el?: Element } | null): void {
  if (el instanceof HTMLElement) {
    // 函数 ref 每次 patch 都会回调：同一元素重复登记不必再标脏（只有挂载/换元素需要重量）。
    if (userBodyEls.get(id) === el) {
      return
    }
    userBodyEls.set(id, el)
    markUserMsgDirty(id)
    return
  }
  userBodyEls.delete(id)
  dirtyUserMsgIds.delete(id)
}

/**
 * 单一 measure：**只量脏消息**（新挂载 / 文本变化 / 展开切换 / 内容宽度变化），集合真变化才换引用。
 * 无参数 = 消费全部脏标记；传 ids = 只量指定消息（如展开/收起该条本身）。
 */
function measureUserMsgOverflow(ids?: Iterable<string>): void {
  const requested = ids ?? dirtyUserMsgIds
  const measured: string[] = []
  const next = new Set(userMsgOverflowed.value)
  let changed = false
  for (const id of requested) {
    const el = userBodyEls.get(id)
    if (el === undefined) {
      dirtyUserMsgIds.delete(id)
      continue
    }
    measured.push(id)
    const overflow = el.scrollHeight > USER_MSG_MAX_HEIGHT_PX
    if (overflow !== next.has(id)) {
      if (overflow) {
        next.add(id)
      } else {
        next.delete(id)
      }
      changed = true
    }
    // 显式传入的 id 也从全局脏集清掉：这次读数已覆盖它的最新 DOM。
    dirtyUserMsgIds.delete(id)
  }
  measurePasses.push(measured)
  if (measurePasses.length > 32) {
    measurePasses.shift()
  }
  if (changed) {
    userMsgOverflowed.value = next
  }
}

/** 折叠态判定：真溢出且未展开。 */
function isUserMsgCollapsed(id: string): boolean {
  return userMsgOverflowed.value.has(id) && !userMsgExpanded.value.has(id)
}

/** 展开/收起切换（只重量该条：展开后字数变化同样以读数为准）。 */
function toggleUserMsg(id: string): void {
  const next = new Set(userMsgExpanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  userMsgExpanded.value = next
  markUserMsgDirty(id)
  void nextTick(() => measureUserMsgOverflow([id]))
}

/** 只保留仍在当前消息集里的 id；有删除才返回新引用（否则返回 null，不触发模板重渲）。 */
function withoutMissingIds(current: Set<string>, live: Set<string>): Set<string> | null {
  let next: Set<string> | null = null
  for (const id of current) {
    if (live.has(id)) {
      continue
    }
    next ??= new Set(current)
    next.delete(id)
  }
  return next
}

/**
 * 消息集差集清理（历史替换 / 消息被移除 / 换会话）：登记表、脏标记与折叠态都按 messageId 记账，
 * 消息离场后这些键不再可达 —— 不收口会随页签寿命线性增长（闭包 + 字符串，量级小但无上限）。
 * 只删当前消息集里不存在的 id：仍在屏上的消息保留测量元素、溢出读数与用户展开态。
 */
function pruneUserMessageState(): void {
  const live = new Set<string>()
  for (const message of ai.messages) {
    live.add(message.id)
  }
  for (const id of userBodyRefSetters.keys()) {
    if (!live.has(id)) {
      userBodyRefSetters.delete(id)
    }
  }
  for (const id of userBodyEls.keys()) {
    if (!live.has(id)) {
      userBodyEls.delete(id)
    }
  }
  for (const id of dirtyUserMsgIds) {
    if (!live.has(id)) {
      dirtyUserMsgIds.delete(id)
    }
  }
  const overflowed = withoutMissingIds(userMsgOverflowed.value, live)
  if (overflowed !== null) {
    userMsgOverflowed.value = overflowed
  }
  const expanded = withoutMissingIds(userMsgExpanded.value, live)
  if (expanded !== null) {
    userMsgExpanded.value = expanded
  }
}

/**
 * 逐条复制的反馈（苞 B 项 2）：**组件局部状态**，生命周期 2s。
 * 为什么不复用 `ai.notice`：它是单槽位（一次只承载一条提示），"已复制"会把并发的
 * 断流/取消等提示挤掉 —— 局部状态只作用于被点的消息。
 */
const copyState = ref<{ id: string; ok: boolean; empty?: boolean } | null>(null)
let copyTimer: ReturnType<typeof setTimeout> | null = null

/** R-165 S5a 项 7：该消息此刻处于「复制成功」绿勾态（2s 窗口内）。 */
function isMsgCopied(id: string): boolean {
  return copyState.value !== null && copyState.value.id === id && copyState.value.ok
}

/**
 * M-15（2026-10-29）：**探针失败**不等于"服务不可用"。
 *
 * <p>`available` 来自健康探针：一次网络抖动 / 代理 502 / 体形态不符就会把它打成 false，而旧实现
 * 直接拿它当发送闸 —— 发送钮、Enter 与空态 chips 一起被禁用。现在把两者分开：
 * <ul>
 * <li>探针**成功且明确说不可用**（`health !== null && available === false`）⇒ 仍按降级限制；</li>
 * <li>探针**失败 / 尚未探测**（`health === null`）⇒ 不拦发送、也不在首屏挂状态提示；
 *     发送闸只看真实轮次与挂起；真实不可用由后端在 `/chat` 上用具体错误码说话
 *     （AI_KEY_MISSING / NO_MODEL_CONFIGURED 等）。</li>
 * </ul>
 */
const healthBlocksSend = computed(() => ai.health !== null && ai.available === false)
/**
 * Task 4 返修 C2/M1：身份未就绪时**发送禁用**（不是静默失败）。
 * - 目录不可用（端点缺失/失败/空目录）⇒ 明确不可用态，面板给横幅 + 重试；
 * - 发号未完成（`sessionReady === false`）⇒ 同样关发送闸，避免"点了没反应"。
 */
const identityBlocked = computed(() => ai.directoryStatus !== 'ready' || !ai.sessionReady)
/**
 * 身份横幅（返修 I-2 / round3）：目录不可用 **或** 发号**确实失败**（`sessionIssueFailed`）。
 * "正在发号"不是失败 —— 只用 `sessionReady=false` 判会导致每次切身份/首启都闪一次红条。
 */
const identityRetryVisible = computed(
  () => ai.directoryStatus === 'unavailable' || ai.sessionIssueFailed
)
const identityRetryTitle = computed(
  () => ai.directoryStatus === 'unavailable' ? t('ai.panel.identityUnavailable') : t('ai.panel.identityNotReady')
)
const identityRetryDetail = computed(
  () => ai.directoryStatus === 'unavailable'
    ? (ai.directoryError ?? t('ai.notice.directoryFailed'))
    : t('ai.notice.sessionIssueFailed')
)
const chipsAvailable = computed(
  () => !healthBlocksSend.value && !identityBlocked.value && ai.canSend && !ai.suspended && ai.pendingToolCall === null
)
const visibleEmptyStateChips = [
  { id: 'listDefs', title: 'listDefsTitle', prompt: 'listDefsPrompt', hint: 'listDefsHint', icon: '≡' },
  { id: 'createTask', title: 'createTaskTitle', prompt: 'createTaskPrompt', hint: 'createTaskHint', icon: '+' },
  { id: 'knowledgeQa', title: 'knowledgeQaTitle', prompt: 'knowledgeQaPrompt', hint: 'knowledgeQaHint', icon: '?' }
] as const

function fillPreset(text: string): void {
  if (!chipsAvailable.value) {
    return
  }
  inputText.value = text
  const composer = composerRef.value
  composer?.focus({ preventScroll: true })
  composer?.setSelectionRange(text.length, text.length)
}

/**
 * I8（R1）/ N1（R2）→ C 端减负：常驻健康胶囊与手动「重新检测」已退场。
 * 降级限制只保留与发送闸同源的 `healthBlocksSend`（探针成功且明确不可用 ⇒ 顶部降级条）；
 * 探针失败 / 尚未探测只由后台探测与真实请求错误说话，不再在首屏挂状态胶囊。
 */
const degradeReason = computed(() => ai.unavailableReason ?? t('ai.panel.degradeUnknown'))

/** 输入始终可编辑；发送闸只拦发送，与建议 chip 共用同一份可执行状态。 */
const canSubmit = computed(
  () => chipsAvailable.value && inputText.value.trim().length > 0 && inputText.value.length <= MESSAGE_MAX_CHARS
)

/**
 * 字数提示（composer 底栏、发送钮左侧）：只在**接近上限**时出现 —— 余量 ≤ 500 转琥珀，
 * 到顶（= 2000，仍可发送）提示已达上限，超限（只可能来自程序化回填）标红并关发送闸。
 * 常态不再常驻「剩余 N 字」：没有信息量的计数不占底栏。
 */
const composerRemaining = computed(() => MESSAGE_MAX_CHARS - inputText.value.length)

const composerOverLimit = computed(() => composerRemaining.value < 0)

const composerCounterVisible = computed(() => composerRemaining.value <= 500)

/**
 * 档位（M5 播报节流的判据）：一个档位内计数文本逐字变化，但**播报**只在档位跃迁时发生。
 * `none` 余量充裕（不显示/不播报）/ `near` 余量 ≤ 500 / `limit` 正好到顶（仍可发送）/
 * `over` 超限（发送闸关闭）。
 */
type ComposerTier = 'none' | 'near' | 'limit' | 'over'

const composerTier = computed<ComposerTier>(() => {
  if (composerOverLimit.value) {
    return 'over'
  }
  if (composerRemaining.value === 0) {
    return 'limit'
  }
  return composerRemaining.value <= 500 ? 'near' : 'none'
})

/** 屏读播报文案：**每档一个常量**（不含剩余数字）⇒ DOM 文本只在跃迁时变，不会逐字打断用户。 */
const composerTierAnnouncement = computed(() => {
  switch (composerTier.value) {
    case 'near':
      return t('ai.panel.composer.near')
    case 'limit':
      return t('ai.panel.composer.limit', { max: MESSAGE_MAX_CHARS })
    case 'over':
      return t('ai.panel.composer.over')
    default:
      return ''
  }
})

const composerCounterText = computed(() => {
  if (composerOverLimit.value) {
    return t('ai.panel.composer.overBy', { n: -composerRemaining.value })
  }
  if (composerRemaining.value === 0) {
    return t('ai.panel.composer.limit', { max: MESSAGE_MAX_CHARS })
  }
  return t('ai.panel.composer.remaining', { n: composerRemaining.value })
})

const composerCounterClass = computed(() => (
  composerOverLimit.value ? 'text-[#c0392b]' : 'text-[#b45309]'
))

/** compositionend 后约 30ms 内仍视为 IME 确认窗口（Safari 可能先送 compositionend 再送 Enter）。 */
const COMPOSITION_END_GUARD_MS = 30
let composerComposing = false
let composerCompositionEndedAt: number | null = null

function onComposerCompositionStart(): void {
  composerComposing = true
}

function onComposerCompositionEnd(): void {
  composerComposing = false
  composerCompositionEndedAt = Date.now()
}

/** demo capture blur 口径：失焦即清组合态与 compositionend 守卫，避免再次聚焦后被旧状态吞掉 Enter。 */
function onComposerBlur(): void {
  composerComposing = false
  composerCompositionEndedAt = null
}

/**
 * Enter 唯一入口：空闲普通 Enter 走现有 send；流式中 preventDefault 后静默返回
 * （不发送 / 不停止 / 不提示 / 不换行）；Shift+Enter 不拦截；IME 组合期与 repeat 不发。
 * 发送闸与发送钮同源（!canSubmit），不允许键盘绕过禁用。
 */
function onComposerKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) {
    return
  }
  if (event.isComposing || event.keyCode === 229 || composerComposing) {
    return
  }
  if (
    composerCompositionEndedAt !== null &&
    Date.now() - composerCompositionEndedAt < COMPOSITION_END_GUARD_MS
  ) {
    event.preventDefault()
    return
  }
  event.preventDefault()
  if (event.repeat || ai.loading || !canSubmit.value) {
    return
  }
  void send()
}

const inputPlaceholder = computed(() => {
  // I8：与发送闸同一判据 —— 探针失败不再谎报"不可用"
  if (healthBlocksSend.value) {
    return t('ai.panel.composer.placeholderUnavailable')
  }
  if (ai.suspended || ai.pendingToolCall) {
    return t('ai.panel.composer.placeholderSuspended')
  }
  return t('ai.panel.composer.placeholder')
})

/**
 * GF-B：当前可交互的生成式表单（填写中/提交中才渲染 FormRenderer；
 * 结局帧到达后 activeForm 被清空，挂起卡随 pendingCall 一并消失）。
 */
const liveForm = computed(() => {
  const form = ai.activeForm
  return form && (form.status === 'filling' || form.status === 'submitting') ? form : null
})

/**
 * T2a-D#3：FormRenderer 的宿主消息 —— 最后一条持该 toolCallId 且**仍在途**的待操作工具段的消息。
 * 同一 toolCallId 的挂起可能残留在多条消息上（历史/重挂形态），但表单只渲染一份。
 * 宿主候选限定 requiresAction 且 pending/running —— 终态残留消息不作宿主，防表单挂到已决消息上。
 */
const formHostMessageId = computed(() => {
  const toolCallId = liveForm.value?.toolCallId
  if (!toolCallId) {
    return null
  }
  for (let i = ai.messages.length - 1; i >= 0; i -= 1) {
    const message = ai.messages[i]
    if (message?.parts.some((part) => part.kind === 'tool'
      && part.toolCallId === toolCallId
      && part.requiresAction
      && (part.status === 'pending' || part.status === 'running'))) {
      return message.id
    }
  }
  return null
})

function isLastAssistantMessage(message: ChatMessage): boolean {
  return message.role === 'assistant' && ai.messages[ai.messages.length - 1] === message
}

function isTerminalMessage(message: ChatMessage): boolean {
  if (message.role === 'user') {
    return true
  }
  return (message.done && !message.streaming)
    || message.stopped === true
    || message.interrupted === true
    || message.truncated === true
}

function isRecoveryLoading(message: ChatMessage): boolean {
  return message.role === 'assistant' && ai.restoringRecovery && !isTerminalMessage(message)
}

/**
 * 只有当前消息里真实存在**等人**的可操作卡时才显示「等待你操作」；自动前端工具与其余运行阶段都算工作。
 *
 * <p>生成式表单「已提交、结局未到」这一段，人这一侧已经没有待办
 * （卡头是「已提交」、卡体是只读表单），该窗口归**既有的工作中语义**（呼吸点），不新增状态。
 */
function isWaitingForAction(message: ChatMessage): boolean {
  if (message.role !== 'assistant' || isTerminalMessage(message) || ai.restoringRecovery || ai.runPhase !== 'parked') {
    return false
  }
  return message.parts.some((part) => part.kind === 'tool'
    && part.requiresAction
    && !isFormAwaitingOutcome(part)
    && (part.kind2 === 'CONFIRM' || part.name === GENERATIVE_FORM_TOOL || isSheetPreviewPart(part) || isUploadHandoffPart(part)))
}

/**
 * B4：真实 L2 压缩的紫色过程态。
 *
 * <p>归属 = 触发这次压缩的宿主 assistant；HITL/表单等待优先（等待中不与压缩抢同一状态位），
 * done/unconfirmed 不显示。L1/L3/MemoryWindow/no-op 后端不发 `compacting` 心跳，因此天然不出现。
 */
function isCompacting(message: ChatMessage): boolean {
  return message.role === 'assistant'
    && !isTerminalMessage(message)
    && message.compactingStartedAtMs !== undefined
    && !isWaitingForAction(message)
    && !ai.restoringRecovery
}

function isBreathing(message: ChatMessage): boolean {
  const active = message.streaming || (isLastAssistantMessage(message) && ai.loading && !message.done)
  return message.role === 'assistant'
    && active
    && !isTerminalMessage(message)
    && !isWaitingForAction(message)
    && !isCompacting(message)
}

type MessageActivityState = 'working' | 'waiting' | 'compacting' | 'recovering' | 'idle'
const RECOVERY_DOT_DELAYS = ['0ms', '150ms', '300ms']

function activityStateOf(message: ChatMessage): MessageActivityState {
  if (isRecoveryLoading(message)) {
    return 'recovering'
  }
  if (isTerminalMessage(message)) {
    return 'idle'
  }
  if (isWaitingForAction(message)) {
    return 'waiting'
  }
  if (isCompacting(message)) {
    return 'compacting'
  }
  return isBreathing(message) ? 'working' : 'idle'
}

const collapsedActivityState = computed<MessageActivityState>(() => {
  for (let i = ai.messages.length - 1; i >= 0; i -= 1) {
    const message = ai.messages[i]
    if (message === undefined || message.role !== 'assistant') {
      continue
    }
    const state = activityStateOf(message)
    if (state !== 'idle') {
      return state
    }
  }
  return 'idle'
})

const COLLAPSED_ACTIVITY_STYLES = {
  idle: {
    shell: 'bg-[#eff4ff] text-[#2563eb]',
    motion: ''
  },
  working: {
    shell: 'bg-[#eff4ff] text-[#2563eb]',
    motion: 'animate-breathe-text motion-reduce:animate-none'
  },
  waiting: {
    shell: 'bg-[#fffbeb] text-[#b45309]',
    motion: 'animate-breathe-text motion-reduce:animate-none'
  },
  compacting: {
    shell: 'bg-[#f5f3ff] text-[#7c3aed]',
    motion: 'animate-breathe-text motion-reduce:animate-none'
  },
  recovering: {
    shell: 'bg-[#eff4ff] text-[#2563eb]',
    motion: ''
  }
} as const

const collapsedActivityStyle = computed(() => COLLAPSED_ACTIVITY_STYLES[collapsedActivityState.value])

function canShowTimestamp(message: ChatMessage): boolean {
  return isTerminalMessage(message) && formatProgressiveTs(message.timestamp) !== ''
}

/**
 * 单条消息只显示活动态或完整终态组；恢复中的未终态助手消息以三点加载占位。
 */
function canShowMetaRow(message: ChatMessage): boolean {
  return isTerminalMessage(message) || activityStateOf(message) !== 'idle'
}

/**
 * 问题1 偏差修正批（aj10 同发送者间距，demo v7.4 十点锁定项）：消息容器下外边距由「全站一刀切
 * 18px」改为**按上一条发送者判定** —— 同角色相邻（同人分次补话 / AI 分段输出）10px、换人 18px。
 * 同类相邻自动成组，换人才留呼吸位；主面板消息严格交替时本判据恒取 18px（视觉量值不变）。
 * 类名走常量字面量（JIT 扫字面类名，禁运行期拼类名）。
 */
const MSG_GAP_SAME_SENDER = 'mb-[10px]'
const MSG_GAP_OTHER_SENDER = 'mb-[18px]'

function msgGapClass(index: number): string {
  const messages = ai.messages
  const prev = index > 0 ? messages[index - 1] : undefined
  const self = messages[index]
  return prev !== undefined && self !== undefined && prev.role === self.role
    ? MSG_GAP_SAME_SENDER
    : MSG_GAP_OTHER_SENDER
}

function cardOnlyMessageGapClass(message: ChatMessage, index: number): string {
  const blocks = blocksOf(message)
  return message.errorCard == null && !canShowMetaRow(message)
    && blocks.length > 0 && blocks.every((block) => block.kind === 'activity')
    ? msgGapClass(index)
    : ''
}

/** 完成淡出记账 —— streaming true→false 时该消息进 300ms 淡出，
 *  新 delta 到达（false→true）立即打断撤出；超时清账后元行换时间戳+复制。 */
const fadingIds = ref<Set<string>>(new Set())
const fadeTimers = new Map<string, ReturnType<typeof setTimeout>>()
/** 基线快照：setup 时播种一次（首见只记账不判迁移），防「首个迁移即首见」丢基线。 */
let prevStreaming = new Map<string, boolean>(ai.messages.map((m) => [m.id, m.streaming]))

function setFading(id: string, on: boolean): void {
  if (fadingIds.value.has(id) === on) {
    return
  }
  const next = new Set(fadingIds.value)
  if (on) {
    next.add(id)
  } else {
    next.delete(id)
  }
  fadingIds.value = next
}

function isFading(message: ChatMessage): boolean {
  return !isTerminalMessage(message) && !ai.restoringRecovery && fadingIds.value.has(message.id)
}

watch(
  () => ai.messages.map((m) => [m.id, m.streaming] as const),
  (list) => {
    const next = new Map(list)
    for (const [id, streaming] of next) {
      const was = prevStreaming.get(id)
      if (was && !streaming) {
        // 完成：同帧起 300ms 淡出，然后清账
        setFading(id, true)
        fadeTimers.set(id, setTimeout(() => {
          fadeTimers.delete(id)
          setFading(id, false)
        }, 300))
      } else if (!was && streaming && fadingIds.value.has(id)) {
        // 风险 3：快速连发 —— 新 delta 打断淡出
        const timer = fadeTimers.get(id)
        if (timer !== undefined) {
          clearTimeout(timer)
          fadeTimers.delete(id)
        }
        setFading(id, false)
      }
    }
    prevStreaming = next
  }
)

/**
 * R-163 T1：正文 Markdown 渲染（markdown-it → DOMPurify → v-html 唯一链路的组件侧）。
 *
 * 批次 7A：缓存拆成**两张互不挤占的表**：
 * - 流式块：按块稳定 key 只保留「最后一帧」（`text + flush tick` 相同才命中），每帧不会挤掉历史；
 * - 历史块：组件级 LRU，键含**内容指纹**（长度 + FNV-1a hash），命中时再比对原文，避免
 *   「同长不同文」误命中；容量同时受块数与字节预算约束，命中即刷新队尾。
 * mermaid 槽位在 cache 命中时同样重新水合（收起/展开后 DOM 会按 v-html 重建，不能只依赖首次渲染）。
 * R-159 #6 逐字符渐显（chSplit/CH_TAIL）按决策卡 §三.1 显式退役：其语义被 Markdown 渲染取代。
 */
interface MermaidSlotRef {
  index: number
  key: string
  source: string
}

interface MdHistoryEntry {
  text: string
  html: string
  mermaid: MermaidSlotRef[]
  degradeNotice: string | null
  bytes: number
}

interface MdStreamEntry {
  text: string
  tick: number
  locale: string
  html: string
  mermaid: MermaidSlotRef[]
  degradeNotice: string | null
  bytes: number
}

/**
 * 批次 7C：>10k 降级提示的消息级展示态。
 *
 * `renderMarkdown` 的 `degradeNotice` 只在该消息首个降级帧返回一次；组件把它记在**消息 id**
 * 维度（不是段维度），模板只在消息尾部渲染一条中性提示。命中缓存时从缓存条目取回同一条文案，
 * 所以收起/展开/重渲不会丢，也不会每帧/每段重复。
 */
const degradeNotices = ref<Record<string, string>>({})
const pendingDegradeNotices = new Map<string, string>()
let degradeNoticeFlushScheduled = false

function degradeNoticeKey(messageId: string): string {
  return `${ai.sessionId}:${messageId}`
}

function rememberDegradeNotice(messageId: string, notice: string | null): void {
  const key = degradeNoticeKey(messageId)
  if (notice === null || degradeNotices.value[key] === notice || pendingDegradeNotices.get(key) === notice) {
    return
  }
  pendingDegradeNotices.set(key, notice)
  if (degradeNoticeFlushScheduled) {
    return
  }
  degradeNoticeFlushScheduled = true
  // markdownHtml 在渲染期被调用；这里不直接改 ref，避免同一渲染周期内自触发。
  void nextTick(() => {
    degradeNoticeFlushScheduled = false
    if (pendingDegradeNotices.size === 0) {
      return
    }
    const next = { ...degradeNotices.value }
    for (const [id, text] of pendingDegradeNotices) {
      next[id] = text
    }
    pendingDegradeNotices.clear()
    degradeNotices.value = next
  })
}

const historyMdCache = new Map<string, MdHistoryEntry>()
let historyMdCacheBytes = 0
/** 块预算 + 字节预算（字节按 UTF-16 码元粗略估算；单条超预算时保留该条，不进入每帧重算）。 */
const MD_HISTORY_MAX_BLOCKS = 256
const MD_HISTORY_MAX_BYTES = 8 * 1024 * 1024
const streamMdCache = new Map<string, MdStreamEntry>()
let streamMdCacheBytes = 0
const MD_STREAM_MAX_BLOCKS = 64
const MD_STREAM_MAX_BYTES = 2 * 1024 * 1024

function deleteStreamEntry(key: string): void {
  const old = streamMdCache.get(key)
  streamMdCache.delete(key)
  streamMdCacheBytes -= old?.bytes ?? 0
}

function evictStreamEntries(): void {
  while (streamMdCache.size > MD_STREAM_MAX_BLOCKS || streamMdCacheBytes > MD_STREAM_MAX_BYTES) {
    if (streamMdCache.size <= 1) {
      return
    }
    const oldestKey = streamMdCache.keys().next().value
    if (oldestKey === undefined) {
      return
    }
    deleteStreamEntry(oldestKey)
  }
}

function setStreamEntry(key: string, entry: MdStreamEntry): void {
  deleteStreamEntry(key)
  streamMdCache.set(key, entry)
  streamMdCacheBytes += entry.bytes
  evictStreamEntries()
}

function clearStreamMdCache(): void {
  streamMdCache.clear()
  streamMdCacheBytes = 0
  degradeNotices.value = {}
  pendingDegradeNotices.clear()
}

/** 历史块缓存（含渲染期产出的 mermaid 槽引用）同样按消息 id 记账，换会话后不再可达。 */
function clearHistoryMdCache(): void {
  historyMdCache.clear()
  historyMdCacheBytes = 0
}

/**
 * 会话/身份切换：两张 Markdown 缓存与降级提示都不跨会话 —— 缓存键含消息 id 与净化后的
 * HTML，上一身份的内容驻留到组件卸载没有复用价值，只会白占 8MB 预算。
 * （身份与 sessionId 通常一起变；任一侧先落地都清一次，重复清是幂等的。）
 */
watch(
  () => `${ai.sessionId ?? ''}\u0000${ai.identity?.tenantId ?? ''}\u0000${ai.identity?.userId ?? ''}`,
  () => {
    clearStreamMdCache()
    clearHistoryMdCache()
  }
)

/** 可观测性（批次 7A）：命中/未命中不直接暴露 UI，供回归测试与后续 profile 打点。 */
let markdownHtmlCalls = 0
let historyMdHits = 0
let historyMdMisses = 0

/** 轻量内容指纹（FNV-1a 32 位）。真正的防误命中仍靠 entry.text 全等比对。 */
function hashText(text: string): string {
  let hash = 2166136261
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(36)
}

function estimateMdBytes(text: string, html: string): number {
  return (text.length + html.length) * 2
}

function touchHistoryEntry(key: string, entry: MdHistoryEntry): void {
  historyMdCache.delete(key)
  historyMdCache.set(key, entry)
}

function evictHistoryEntries(): void {
  while (historyMdCache.size > MD_HISTORY_MAX_BLOCKS || historyMdCacheBytes > MD_HISTORY_MAX_BYTES) {
    // 单条就超字节预算时保留它：删掉会让这一条每帧重算，比超预算更糟。
    if (historyMdCache.size <= 1) {
      return
    }
    const oldestKey = historyMdCache.keys().next().value
    if (oldestKey === undefined) {
      return
    }
    const oldest = historyMdCache.get(oldestKey)
    historyMdCache.delete(oldestKey)
    historyMdCacheBytes -= oldest?.bytes ?? 0
  }
}

function hydrateMermaidIfNeeded(messageId: string, mermaid: MermaidSlotRef[]): void {
  void nextTick(() => hydrateMermaid(messageId, mermaid))
}

/**
 * 问题1 偏差修正批 aj14d ②：流式块防抖的「落地」信号 —— 75ms 窗口内被合并掉的增量由
 * `setStreamFlushHandler` 回调（静默满窗后触发）bump 本计数，令 markdownHtml 重入一次真渲染。
 * 只有流式块把本计数并进「最后一帧」槽的命中判据（历史块不受本计数搅动）。
 */
const mdStreamTick = ref(0)
setStreamFlushHandler(() => {
  mdStreamTick.value += 1
})

function streamingMarkdownHtml(message: ChatMessage, blockKey: string, text: string, localeKey: string): string {
  const cached = streamMdCache.get(blockKey)
  if (cached !== undefined && cached.text === text && cached.tick === mdStreamTick.value && cached.locale === localeKey) {
    streamMdCache.delete(blockKey)
    streamMdCache.set(blockKey, cached)
    rememberDegradeNotice(message.id, cached.degradeNotice)
    hydrateMermaidIfNeeded(message.id, cached.mermaid)
    return cached.html
  }
  const { html, mermaid, degradeNotice } = renderMarkdown(text, true, blockKey)
  const carriedNotice = degradeNotice ?? cached?.degradeNotice ?? null
  setStreamEntry(blockKey, {
    text,
    tick: mdStreamTick.value,
    locale: localeKey,
    html,
    mermaid,
    degradeNotice: carriedNotice,
    bytes: estimateMdBytes(text, html)
  })
  rememberDegradeNotice(message.id, carriedNotice)
  hydrateMermaidIfNeeded(message.id, mermaid)
  return html
}

function historyMarkdownHtml(message: ChatMessage, blockKey: string, text: string, localeKey: string): string {
  const streamNotice = streamMdCache.get(blockKey)?.degradeNotice ?? null
  deleteStreamEntry(blockKey)
  const key = `${blockKey}:${localeKey}:${text.length}:${hashText(text)}`
  const hit = historyMdCache.get(key)
  if (hit !== undefined && hit.text === text) {
    historyMdHits += 1
    touchHistoryEntry(key, hit)
    rememberDegradeNotice(message.id, hit.degradeNotice)
    hydrateMermaidIfNeeded(message.id, hit.mermaid)
    return hit.html
  }
  historyMdMisses += 1
  const { html, mermaid, degradeNotice } = renderMarkdown(text, false, blockKey)
  const carriedNotice = degradeNotice ?? streamNotice
  rememberDegradeNotice(message.id, carriedNotice)
  const entry: MdHistoryEntry = { text, html, mermaid, degradeNotice: carriedNotice, bytes: estimateMdBytes(text, html) }
  const replaced = historyMdCache.get(key)
  if (replaced !== undefined) {
    historyMdCacheBytes -= replaced.bytes
  }
  historyMdCache.set(key, entry)
  historyMdCacheBytes += entry.bytes
  evictHistoryEntries()
  hydrateMermaidIfNeeded(message.id, mermaid)
  return html
}

function markdownHtml(message: ChatMessage, block: Block): string {
  markdownHtmlCalls += 1
  const part = head(block.parts)
  if (part?.kind !== 'text') {
    return ''
  }
  const text = projectDisplayText(part.text)
  const blockKey = `${message.id}:${part.seg}`
  const localeKey = currentLocaleKey()
  return message.streaming
    ? streamingMarkdownHtml(message, blockKey, text, localeKey)
    : historyMarkdownHtml(message, blockKey, text, localeKey)
}

/** 段派生值按对象与 locale 缓存，段替换或切语言时重算。 */
interface LocaleCacheEntry<T> {
  locale: string
  stamp: readonly unknown[]
  value: T
}

const parameterRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<FieldRow[]>>()
const gateRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<FieldRow[]>>()
const conflictRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<ConflictRow[]>>()
const outputByViewCache = new WeakMap<ToolPart, LocaleCacheEntry<boolean>>()
const cardTitleCache = new WeakMap<ToolPart, LocaleCacheEntry<string>>()
const cardStatusCache = new WeakMap<ToolPart, LocaleCacheEntry<ReturnType<typeof cardStatusOf>>>()
const diagnosticCache = new WeakMap<ToolPart, LocaleCacheEntry<PartDiagnostic | null>>()
const toolDeriveMisses: Record<string, number> = {
  paramRows: 0,
  gateRows: 0,
  conflictRows: 0,
  hasViewOutput: 0,
  cardTitle: 0,
  cardStatus: 0,
  diagnosis: 0
}

function currentLocaleKey(): string {
  return String(locale.value)
}

function isSameStamp(left: readonly unknown[], right: readonly unknown[]): boolean {
  if (left.length !== right.length) {
    return false
  }
  for (let i = 0; i < left.length; i += 1) {
    if (!Object.is(left[i], right[i])) {
      return false
    }
  }
  return true
}

function cachedForLocale<T>(
  cache: WeakMap<ToolPart, LocaleCacheEntry<T>>,
  part: ToolPart,
  kind: keyof typeof toolDeriveMisses,
  stamp: readonly unknown[],
  compute: () => T
): T {
  const localeKey = currentLocaleKey()
  const hit = cache.get(part)
  if (hit !== undefined && hit.locale === localeKey && isSameStamp(hit.stamp, stamp)) {
    return hit.value
  }
  toolDeriveMisses[kind] = (toolDeriveMisses[kind] ?? 0) + 1
  const value = compute()
  cache.set(part, { locale: localeKey, stamp, value })
  return value
}

/**
 * R-163B G4 → R-165 S5a 项 7 → S5b：v-html 注入内容的点击委托 —— 代码块复制图标钮 / mermaid 卡内
 * 四钮（源码态复制源码、双态 tab 切换、预览复位、全屏查看）。不在注入 HTML 内联脚本；
 * 剪贴板走既有 canLegacyCopy 回退链。复制成功反馈统一为 v7 口径：钮挂 `is-copied`（绿勾 + 绿字，
 * 见 COPY_STATE_CLASS），2s 后复原。
 */
function onMdBodyClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  if (!target) {
    return
  }
  const copyBtn = target.closest('figure.code .cpbtn')
  if (copyBtn) {
    const pre = copyBtn.parentElement?.parentElement?.querySelector('pre')
    const text = pre?.textContent ?? ''
    const ok = canLegacyCopy(text)
    flashCopied(copyBtn, ok)
    return
  }
  // R-165 S5b：卡内四钮 —— 复制源码（源码态）/ tab 切换 / 复位 / 全屏（新类名 `mmdc*`，见卡配方）
  const srcCopy = target.closest('.mmdc .mmdc-copy')
  if (srcCopy) {
    const code = srcCopy.closest('.mmdc')?.querySelector('.mmdc-src')
    flashCopied(srcCopy, canLegacyCopy(code?.textContent ?? ''))
    return
  }
  const tab = target.closest('.mmdc .mmdc-tab')
  if (tab) {
    switchMermaidTab(tab)
    return
  }
  const reset = target.closest('.mmdc .mmdc-ireset')
  if (reset) {
    resetMermaidInline(reset.closest('.mmdc'))
    return
  }
  const full = target.closest('.mmdc .mmdc-full')
  if (full) {
    openMmdViewer(full.closest('.mmdc'))
  }
}

/** 复制成功反馈（v7 `14c`）：成功挂 `is-copied` 换绿勾，2s 后复原；失败不挂（无绿勾即"没成功"）。 */
function flashCopied(btn: Element, ok: boolean): void {
  if (!(btn instanceof HTMLElement)) {
    return
  }
  const previous = copyFlashTimers.get(btn)
  if (previous !== undefined) {
    window.clearTimeout(previous)
  }
  btn.classList.toggle('is-copied', ok)
  const timer = window.setTimeout(() => {
    copyFlashTimers.delete(btn)
    btn.classList.remove('is-copied')
  }, 2000)
  copyFlashTimers.set(btn, timer)
}
/** 注入 DOM 的复制钮反馈计时器（节点随 v-html 重建，卸载时统一清理）。 */
const copyFlashTimers = new Map<Element, number>()

/* ---------- R-165 S5b：mermaid 卡双态（预览 | 源码）+ 全屏查看器 ----------
   口径 = demo v7.7 终裁（裁决区 12：卡头分段 tab「预览 | 源码」+ 右端图标钮组；预览 260px 内联
   滚轮缩放（指针锚定 0.5–4×）+ transform 拖拽平移 + 复位 + 缩放读数；源码 200px 内滚 + 复制源码钮；
   裁决区 07：全屏查看器 scrim + 白容器 · 指针锚定缩放 0.5×–4× · transform 平移 · 6 钮工具条 ·
   Esc/点 scrim 关闭）。两道缩放/平移数学同源（07 与 12 在 demo 里也是同一套）。
   类名纪律：S5b 期 ai-chat.css 的 `.mmd*` 族 12 条规则是零引用存量、结构已整体换 `mmdc` 前缀；
   R-165 S5c 后该文件已删除 ⇒ `mmdc-*` 是 mermaid 卡唯一类词条（`mmd` 前缀不再出现在任何样式源）。
   fit/缩放直接按 viewBox 基准写 stage 实际宽高，SVG 填满 stage；transform 只承担 pan 平移，
   避免 foreignObject 在 CSS scale 下沿用错误的内部布局。 */

/** 缩放范围（demo 07 / 12 同口径）。 */
const MMD_Z_MIN = 0.5
const MMD_Z_MAX = 4

/** 卡壳（demo `data-mmd-card`：细描边 + 圆角 10 + 白底 + 裁切内滚区四角）。下外边距 mb-3.5 同 S5a 卡片节奏。 */
const MMD_CARD_CLASS = 'mmdc mb-3.5 overflow-hidden rounded-[10px] border border-solid border-[#e3e6ea] bg-white'
const MMD_HEAD_CLASS = 'relative flex items-center gap-[7px] border-0 border-b border-solid border-[#eef0f3] bg-[#f6f7f9] px-[10px] py-[7px] text-[12.4px]'
const MMD_MARK_CLASS = 'text-[#9370DB] text-[11px]'
const MMD_NAME_CLASS = 'mmdc-name [font-weight:650]'
/** 卡头中段分段控件（demo：圆角 7 双层描边 + 选中态白底 600 阴影）。 */
const MMD_TABS_CLASS = 'mmdc-tabs absolute left-1/2 -ml-[24px] -translate-x-1/2 flex gap-[2px] rounded-[7px] border border-solid border-[#e3e6ea] bg-[#f6f7f9] p-[2px]'
const MMD_TAB_CLASS = 'mmdc-tab border-0 bg-transparent rounded-[5px] px-[11px] py-[3px] text-[11.5px] text-[#57606a] transition-all duration-[150ms] [&.is-on]:bg-white [&.is-on]:font-semibold [&.is-on]:text-[#1f2328] [&.is-on]:shadow-[0_1px_2px_rgba(0,0,0,.08)]'
/** 卡头图标钮 = 13 图标钮白名单形态的 26px 档（demo 卡头/查看器工具条同档；24px 档见 COPY_ICON_BTN_CLASS）。 */
const MMD_ICON_BTN_CLASS = 'group/tip relative inline-flex w-[26px] h-[26px] items-center justify-center rounded-[7px] border border-solid border-[#e3e6ea] bg-white p-0 text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328] [&_svg]:w-[14px] [&_svg]:h-[14px] [&_svg]:fill-none [&_svg]:stroke-current [&_svg]:[stroke-width:1.7] [&_svg]:[stroke-linecap:round] [&_svg]:[stroke-linejoin:round]'
const MMD_COPY_ICON_BTN_CLASS = `${MMD_ICON_BTN_CLASS} ${COPY_STATE_CLASS}`

/** 预览态容器：固定可用高度 + overflow-hidden；fit 完整显示，放大后的局部裁切由 transform 承载。 */
const MMD_PREVIEW_PANE_CLASS = 'mmdc-pane mmdc-pv h-[min(320px,45vh)] overflow-hidden bg-white cursor-grab select-none'
const MMD_PAN_CLASS = 'mmdc-pan [transform:translateX(var(--px,0px))_translateY(var(--py,0px))]'
const MMD_STAGE_CLASS = 'mmdc-stage'
const MMD_SOURCE_PANE_CLASS = 'mmdc-pane hidden'
/** 源码态：小标题行 + 复制源码图标钮（24px 档）+ mono 盒双约束 `min(280px,35vh)` 内滚
 *  （2A 批裁决 §14.4 代码族，与代码块限高同口径）。 */
const MMD_SOURCE_BAR_CLASS = 'flex items-center gap-[6px] border-0 border-b border-solid border-[#eef0f3] bg-[#fbfbfc] px-[10px] py-[5px] text-[11px] text-[#8b949e]'
/** mono 盒用 `div`（非 demo 的 `pre`）：preflight 关闭下 `<pre>` 吃 UA 默认外边距，且
 *  `.ai-chat .md-body pre`(0,2,1) 会压过全部 Tailwind 版式类（padding/字号/行高需 5 个 `!`）；
 *  内容由 `textContent` 赋值（纯文本、零注入面），div 的 box 行为与 pre 等价（`whitespace-pre`）。 */
const MMD_SOURCE_BOX_CLASS = `mmdc-src max-h-[min(280px,35vh)] overflow-auto whitespace-pre bg-[#f8f8f8] px-[10px] py-[8px] font-mono text-[12px] leading-[1.6] text-[#333] ${SCROLLBAR_CLASS}`

/** 卡头图标钮的 tip 气泡翻到**钮下方**：卡壳 `overflow-hidden`（demo 同形）会把钮上方的气泡裁掉，
 *  锚点翻转后气泡落在卡头内部、恒可见（其余钮仍用 TIP_CLASS 上方气泡）。 */
const MMD_TIP_BELOW_CLASS = 'pointer-events-none absolute top-[calc(100%+7px)] left-1/2 -translate-x-1/2 z-[6] whitespace-nowrap rounded-[6px] bg-[#1f2328] px-[8px] py-[3px] text-[11px] leading-[1.5] text-white opacity-0 transition-opacity duration-[180ms] motion-reduce:transition-none group-hover/tip:opacity-100 group-focus-visible/tip:opacity-100'
const MMD_TIP_ARROW_BELOW_CLASS = 'absolute bottom-full left-1/2 -ml-[4px] h-[8px] w-[8px] translate-y-1/2 rotate-45 rounded-[1px] bg-[#1f2328]'

/** 图标（demo v7.7 逐字照搬：复位 / 全屏）。 */
const MMD_ICON_RESET = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7"></path><path d="M3 4v5h5"></path></svg>'
const MMD_ICON_FULL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 9V4h5"></path><path d="M20 15v5h-5"></path><path d="M15 4h5v5"></path><path d="M9 20H4v-5"></path></svg>'

/** tip 气泡（`below` = 锚点翻到钮下方，见 MMD_TIP_BELOW_CLASS）。 */
function mmdTipSpan(label: string, below = false): string {
  return `<span class="tip ${below ? MMD_TIP_BELOW_CLASS : TIP_CLASS}">${label}` +
    `<span class="tip-arrow ${below ? MMD_TIP_ARROW_BELOW_CLASS : TIP_ARROW_CLASS}" aria-hidden="true"></span></span>`
}

/** SVG 填满由 fit/zoom 直接设置尺寸的 stage；viewBox 负责等比缩放。 */
function prepareMermaidSvg(svg: string, extra = ''): string {
  const cls = `block h-full w-full max-w-none${extra === '' ? '' : ` ${extra}`}`
  return svg.replace(/<svg\b([^>]*)>/, (_match: string, attrs: string) =>
    `<svg${attrs.replace(/\s(?:class|style|width|height)="[^"]*"/g, '')} class="${cls}">`
  )
}

function mermaidNaturalSize(svg: SVGElement | null): { width: number; height: number } {
  const box = (svg?.getAttribute('viewBox') ?? '').trim().split(/[\s,]+/)
  const width = Number(box[2])
  const height = Number(box[3])
  return {
    width: Number.isFinite(width) && width > 0 ? width : 0,
    height: Number.isFinite(height) && height > 0 ? height : 0
  }
}

function mermaidFitScale(naturalW: number, naturalH: number, availableW: number, availableH: number): number {
  if (naturalW <= 0 || naturalH <= 0 || availableW <= 0 || availableH <= 0) {
    return 1
  }
  return Math.min(1, availableW / naturalW, availableH / naturalH)
}

/** 卡结构（demo v7.7 裁决区 12 右栏逐条）：卡头 = ◈ + 名称 + 分段 tab + 右端图标钮组；
 *  预览态 = 固定可用高度 + overflow-hidden（实际尺寸 stage + transform pan）；源码态 = 200px 内滚 mono 盒 + 复制源码钮。
 *  `svg === null`（render error / 3s 等待预算用尽，只停止等待、不中断同步渲染）⇒ 降级为「源码态卡」：
 *  无 tab、无缩放钮（源码直显、复制可用），
 *  不留任何无 handler 的钮。 */
function buildMermaidCardHtml(svg: string | null): string {
  const hasSvg = svg !== null
  const head =
    `<div class="${MMD_HEAD_CLASS}"><span class="${MMD_MARK_CLASS}">◈</span>` +
    `<span class="${MMD_NAME_CLASS}">${t('ai.panel.diagramName')}</span>` +
    (hasSvg
      ? `<div class="${MMD_TABS_CLASS}" role="tablist" aria-label="${t('ai.panel.diagramView')}">` +
        `<button type="button" class="${MMD_TAB_CLASS} is-on" role="tab" aria-selected="true" data-tab="preview">${t('ai.panel.tabPreview')}</button>` +
        `<button type="button" class="${MMD_TAB_CLASS}" role="tab" aria-selected="false" data-tab="source">${t('ai.panel.tabSource')}</button></div>`
      : '') +
    (hasSvg
      ? '<span class="ml-auto flex items-center gap-[5px]">' +
        '<span class="mmdc-ipct min-w-[36px] text-center font-mono text-[10.5px] tabular-nums text-[#8b949e]">100%</span>' +
        `<button type="button" class="${MMD_ICON_BTN_CLASS} mmdc-ireset" aria-label="${t('ai.panel.resetZoom')}">${MMD_ICON_RESET}${mmdTipSpan(t('ai.panel.resetZoom'), true)}</button>` +
        `<button type="button" class="${MMD_ICON_BTN_CLASS} mmdc-full" aria-label="${t('ai.panel.fullscreen')}">${MMD_ICON_FULL}${mmdTipSpan(t('ai.panel.fullscreen'), true)}</button>` +
        '</span>'
      : '') +
    '</div>'
  const preview = hasSvg
    ? `<div class="${MMD_PREVIEW_PANE_CLASS}" data-pane="preview">` +
      `<div class="${MMD_PAN_CLASS}"><div class="${MMD_STAGE_CLASS}">${svg}</div></div></div>`
    : ''
  const source =
    `<div class="${hasSvg ? MMD_SOURCE_PANE_CLASS : 'mmdc-pane'}" data-pane="source">` +
    `<div class="${MMD_SOURCE_BAR_CLASS}"><span>${t('ai.panel.sourceLabel')}</span><span class="ml-auto">` +
    `<button type="button" class="${COPY_ICON_BTN_CLASS} mmdc-copy" aria-label="${t('ai.panel.copySource')}">${COPY_ICON_SVGS}${mmdTipSpan(t('ai.panel.copySource'))}</button>` +
    '</span></div>' +
    `<div class="${MMD_SOURCE_BOX_CLASS}"></div></div>`
  return head + preview + source
}

/** 每张卡唯一的定位来源：当前可用区域内的 fit + transform。 */
const mermaidInlineFit = new Map<HTMLElement, () => void>()

function pruneDetachedMermaidInlineFit(): void {
  const wrap = msgWrapRef.value
  for (const card of mermaidInlineFit.keys()) {
    if (wrap === null || !wrap.contains(card)) {
      mermaidInlineFit.delete(card)
    }
  }
}

function refitMermaidInline(): void {
  pruneDetachedMermaidInlineFit()
  for (const fit of mermaidInlineFit.values()) {
    fit()
  }
}

/**
 * 预览态内联交互：固定可用高度 + overflow-hidden；fit 保持宽高比并居中。
 * 放大后仍用指针锚定缩放和 transform 拖拽，局部裁切不产生滚动条。
 */
function initMermaidInline(card: HTMLElement): void {
  const box = card.querySelector<HTMLElement>('.mmdc-pv')
  const pan = card.querySelector<HTMLElement>('.mmdc-pan')
  const stage = card.querySelector<HTMLElement>('.mmdc-stage')
  if (!box || !pan || !stage) {
    return
  }
  const { width: naturalW, height: naturalH } = mermaidNaturalSize(stage.querySelector<SVGElement>('svg'))
  let z = 1
  let px = 0
  let py = 0
  let minZ = MMD_Z_MIN
  const apply = (): void => {
    stage.style.width = `${naturalW * z}px`
    stage.style.height = `${naturalH * z}px`
    pan.style.setProperty('--px', `${px}px`)
    pan.style.setProperty('--py', `${py}px`)
    const readout = card.querySelector('.mmdc-ipct')
    if (readout) {
      readout.textContent = `${Math.round(z * 100)}%`
    }
  }
  const clamp = (): void => {
    const maxX = box.clientWidth - naturalW * z
    const maxY = box.clientHeight - naturalH * z
    px = maxX >= 0 ? maxX / 2 : Math.min(0, Math.max(maxX, px))
    py = maxY >= 0 ? maxY / 2 : Math.min(0, Math.max(maxY, py))
  }
  const fit = (): void => {
    if (naturalW <= 0 || naturalH <= 0 || box.clientWidth <= 0 || box.clientHeight <= 0) {
      return
    }
    z = mermaidFitScale(naturalW, naturalH, box.clientWidth, box.clientHeight)
    minZ = Math.min(MMD_Z_MIN, z)
    px = (box.clientWidth - naturalW * z) / 2
    py = (box.clientHeight - naturalH * z) / 2
    apply()
  }
  const setZoom = (next: number, vx: number, vy: number): void => {
    const target = Math.min(MMD_Z_MAX, Math.max(minZ, next))
    const sx = (vx - px) / z
    const sy = (vy - py) / z
    px = vx - target * sx
    py = vy - target * sy
    z = target
    clamp()
    apply()
  }
  mermaidInlineFit.set(card, fit)
  fit()
  box.addEventListener(
    'wheel',
    (event) => {
      event.preventDefault()
      const rect = box.getBoundingClientRect()
      setZoom(z * Math.pow(1.0015, -event.deltaY), event.clientX - rect.left, event.clientY - rect.top)
    },
    { passive: false }
  )
  let dragging = false
  let startX = 0
  let startY = 0
  let originX = 0
  let originY = 0
  // 拖动期间的 move/up 只在拖动中存在（卡随 v-html 重建，常驻 window 监听会随节点泄漏）
  const onMove = (event: MouseEvent): void => {
    if (!dragging) {
      return
    }
    px = originX + (event.clientX - startX)
    py = originY + (event.clientY - startY)
    clamp()
    apply()
  }
  const onUp = (): void => {
    if (!dragging) {
      return
    }
    dragging = false
    box.classList.remove('cursor-grabbing')
    box.classList.add('cursor-grab')
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  box.addEventListener('mousedown', (event) => {
    if (event.button !== 0) {
      return
    }
    event.preventDefault()
    clamp()
    apply()
    dragging = true
    startX = event.clientX
    startY = event.clientY
    originX = px
    originY = py
    box.classList.remove('cursor-grab')
    box.classList.add('cursor-grabbing')
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  })
}

/** tab 切换只切显隐；预览重新可见后按当前可用区域重新 fit。 */
function switchMermaidTab(tab: Element): void {
  const card = tab.closest<HTMLElement>('.mmdc')
  const mode = tab.getAttribute('data-tab')
  if (!card || mode === null) {
    return
  }
  for (const item of card.querySelectorAll<HTMLElement>('.mmdc-tab')) {
    const on = item.getAttribute('data-tab') === mode
    item.classList.toggle('is-on', on)
    item.setAttribute('aria-selected', on ? 'true' : 'false')
  }
  for (const pane of card.querySelectorAll<HTMLElement>('.mmdc-pane')) {
    pane.classList.toggle('hidden', pane.getAttribute('data-pane') !== mode)
  }
  if (mode === 'preview') {
    requestAnimationFrame(() => {
      if (msgWrapRef.value?.contains(card)) {
        mermaidInlineFit.get(card)?.()
      }
    })
  }
}

/** 复位 = 按当前可用区域完整适配并居中，不再回到 100%。 */
function resetMermaidInline(card: Element | null): void {
  if (card instanceof HTMLElement) {
    mermaidInlineFit.get(card)?.()
  }
}

/* ----- 全屏查看器（demo 07 重做口径：scrim + 白容器 + 指针锚定缩放 + transform 平移） ----- */

const mmdViewerOpen = ref(false)
const mmdViewerSvg = ref('')
const mmdViewerTitle = ref('mermaid')
const mmdViewerSource = ref('')
const mmdViewerPct = ref(100)
const mmdViewerCopied = ref(false)
const mmdViewerStageRef = ref<HTMLElement | null>(null)
const mmdViewerPanRef = ref<HTMLElement | null>(null)
const mmdViewerZoomRef = ref<HTMLElement | null>(null)
let mmdViewerZ = 1
let mmdViewerX = 0
let mmdViewerY = 0
let mmdViewerMinZ = MMD_Z_MIN
let mmdViewerNaturalW = 0
let mmdViewerNaturalH = 0
let mmdViewerCopyTimer: number | null = null

function applyMmdViewer(): void {
  const zoom = mmdViewerZoomRef.value
  if (zoom) {
    zoom.style.width = `${mmdViewerNaturalW * mmdViewerZ}px`
    zoom.style.height = `${mmdViewerNaturalH * mmdViewerZ}px`
  }
  const pan = mmdViewerPanRef.value
  if (pan) {
    pan.style.setProperty('--px', `${mmdViewerX}px`)
    pan.style.setProperty('--py', `${mmdViewerY}px`)
  }
  mmdViewerPct.value = Math.round(mmdViewerZ * 100)
}

/** 指针锚定缩放（demo 07）：pan 层已居中 ⇒ 锚点坐标 = 指针相对 stage 中心的偏移。 */
function setMmdViewerZoom(next: number, anchorX = 0, anchorY = 0): void {
  const z = Math.min(MMD_Z_MAX, Math.max(mmdViewerMinZ, next))
  const k = z / mmdViewerZ
  mmdViewerX = anchorX - (anchorX - mmdViewerX) * k
  mmdViewerY = anchorY - (anchorY - mmdViewerY) * k
  mmdViewerZ = z
  applyMmdViewer()
}

function zoomMmdViewer(factor: number): void {
  setMmdViewerZoom(mmdViewerZ * factor)
}

function resetMmdViewer(): void {
  const stage = mmdViewerStageRef.value
  const zoom = mmdViewerZoomRef.value
  if (!stage || !zoom || stage.clientWidth <= 0 || stage.clientHeight <= 0) {
    return
  }
  const natural = mermaidNaturalSize(zoom.querySelector<SVGElement>('svg'))
  if (natural.width <= 0 || natural.height <= 0) {
    return
  }
  mmdViewerNaturalW = natural.width
  mmdViewerNaturalH = natural.height
  mmdViewerZ = mermaidFitScale(natural.width, natural.height, stage.clientWidth, stage.clientHeight)
  mmdViewerMinZ = Math.min(MMD_Z_MIN, mmdViewerZ)
  mmdViewerX = 0
  mmdViewerY = 0
  applyMmdViewer()
}

function onMmdViewerWheel(event: WheelEvent): void {
  const stage = mmdViewerStageRef.value
  if (!stage) {
    return
  }
  const rect = stage.getBoundingClientRect()
  setMmdViewerZoom(
    mmdViewerZ * Math.pow(1.0015, -event.deltaY),
    event.clientX - (rect.left + rect.width / 2),
    event.clientY - (rect.top + rect.height / 2)
  )
}

function onMmdViewerDragStart(event: MouseEvent): void {
  const stage = mmdViewerStageRef.value
  if (!stage || event.button !== 0) {
    return
  }
  event.preventDefault()
  const startX = event.clientX
  const startY = event.clientY
  const originX = mmdViewerX
  const originY = mmdViewerY
  stage.classList.remove('cursor-grab')
  stage.classList.add('cursor-grabbing')
  const onMove = (move: MouseEvent): void => {
    mmdViewerX = originX + (move.clientX - startX)
    mmdViewerY = originY + (move.clientY - startY)
    applyMmdViewer()
  }
  const onUp = (): void => {
    stage.classList.remove('cursor-grabbing')
    stage.classList.add('cursor-grab')
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function openMmdViewer(card: Element | null): void {
  if (!(card instanceof HTMLElement)) {
    return
  }
  const svg = card.querySelector('.mmdc-stage svg')
  if (!(svg instanceof SVGElement)) {
    return
  }
  mmdViewerSvg.value = prepareMermaidSvg(svg.outerHTML, 'select-text')
  mmdViewerTitle.value = card.querySelector('.mmdc-name')?.textContent?.trim() ?? 'mermaid'
  mmdViewerSource.value = card.querySelector('.mmdc-src')?.textContent ?? ''
  mmdViewerCopied.value = false
  mmdViewerOpen.value = true
  void nextTick(() => {
    resetMmdViewer()
    mmdViewerStageRef.value?.focus()
  })
}

function closeMmdViewer(): void {
  mmdViewerOpen.value = false
  mmdViewerCopied.value = false
  if (mmdViewerCopyTimer !== null) {
    clearTimeout(mmdViewerCopyTimer)
    mmdViewerCopyTimer = null
  }
}

/** 查看器「复制源码」：绿勾反馈与卡内复制钮同一机制（`is-copied` + COPY_STATE_CLASS），状态走响应式。 */
function copyMmdViewerSource(): void {
  mmdViewerCopied.value = canLegacyCopy(mmdViewerSource.value)
  if (mmdViewerCopyTimer !== null) {
    clearTimeout(mmdViewerCopyTimer)
  }
  mmdViewerCopyTimer = window.setTimeout(() => {
    mmdViewerCopied.value = false
    mmdViewerCopyTimer = null
  }, 2000)
}

/** 下载 SVG（demo 07）：把计算样式内联进克隆树（脱离页面仍可渲染）→ Blob + `<a download>` 落盘。 */
function downloadMmdViewerSvg(): void {
  const svg = mmdViewerZoomRef.value?.querySelector('svg')
  if (!(svg instanceof SVGElement)) {
    return
  }
  const clone = svg.cloneNode(true) as SVGElement
  const from = svg.querySelectorAll('*')
  const to = clone.querySelectorAll('*')
  const props = ['fill', 'stroke', 'stroke-width', 'stroke-dasharray', 'font-size', 'font-family', 'font-weight', 'text-anchor']
  for (let i = 0; i < from.length; i += 1) {
    const computed = window.getComputedStyle(from[i])
    for (const prop of props) {
      const value = computed.getPropertyValue(prop)
      if (value !== '' && value !== 'none' && value !== 'normal') {
        to[i].setAttribute(prop, value)
      }
    }
  }
  const box = (svg.getAttribute('viewBox') ?? '0 0 980 240').split(/[\s,]+/)
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('width', box[2] ?? '980')
  clone.setAttribute('height', box[3] ?? '240')
  clone.removeAttribute('class')
  const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(clone)], { type: 'image/svg+xml;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'mermaid-flow.svg'
  document.body.appendChild(link)
  link.click()
  window.setTimeout(() => {
    URL.revokeObjectURL(url)
    link.remove()
  }, 0)
}

/** Esc 关闭（查看器打开期间才挂监听，见下方 watch）。 */
function onMmdViewerKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    closeMmdViewer()
  }
}

watch(mmdViewerOpen, (open) => {
  if (open) {
    window.addEventListener('keydown', onMmdViewerKeydown)
    window.addEventListener('resize', resetMmdViewer)
    document.body.classList.add('overflow-hidden')
    return
  }
  window.removeEventListener('keydown', onMmdViewerKeydown)
  window.removeEventListener('resize', resetMmdViewer)
  document.body.classList.remove('overflow-hidden')
})

/**
 * R-163B G4 → R-165 S5a → S5b：mermaid 卡片化（demo v7.7 裁决区 12/07）——卡头双态 tab +
 * 预览态内联缩放/平移 + 源码态 200px 内滚 + 卡头图标钮组（复位/全屏）。
 * 卡片下外边距 `mb-3.5`（S5a 项 1 统一卡片节奏）。
 *
 * 性能专项（2026-10-24）：水合改为 **IntersectionObserver 门控** —— 只有进入消息容器视口
 * （rootMargin 200px）的源码块才真正渲染 SVG，完成后即 unobserve；长会话的历史消息不再在
 * 打开面板的瞬间排队渲染。无 IO 实现的环境（jsdom / 老浏览器）退化为立即渲染（行为兼容）。
 */
const mermaidPending = new Map<Element, { source: string; bubble: Element }>()
let mermaidObserver: IntersectionObserver | null = null

/**
 * 清掉「已脱离当前消息树」的待渲染槽：`v-html` 重建（流式续写 / 缓存失效重渲 / 切会话）会摘掉
 * 上一帧的 `code` 节点，而 IntersectionObserver 对已脱离节点永不回调 —— 只删 Map 条目不够，还要
 * `unobserve`，否则条目连同其 `bubble` 子树被观察器强引用常驻。仍在树上的槽原样保留。
 *
 * 判据用「当前容器还包含它」而不是 `isConnected`：面板在测试/离屏容器里渲染时节点本就不在
 * document 内，用 `isConnected` 会把仍在屏上的槽也一并清掉。
 */
function pruneDetachedMermaidPending(): void {
  if (mermaidPending.size === 0) {
    return
  }
  const wrap = msgWrapRef.value
  for (const [code, pending] of mermaidPending) {
    if (wrap !== null && pending.bubble.contains(code) && wrap.contains(pending.bubble)) {
      continue
    }
    mermaidPending.delete(code)
    mermaidObserver?.unobserve(code)
  }
}

/** 把一个 mermaid 源码块替换成卡片（`bubble.contains` 判活：v-html 重建后旧槽位不再回填）。 */
async function renderMermaidSlot(bubble: Element, code: Element, source: string): Promise<void> {
  const svg = await renderMermaidSvg(source)
  let target = code
  if (!bubble.contains(target)) {
    const key = target.parentElement?.getAttribute('data-fence-key') ?? ''
    const current = Array.from(bubble.querySelectorAll('pre > code.language-mermaid'))
    if (key !== '') {
      const keyed = current.find((slot) => slot.parentElement?.getAttribute('data-fence-key') === key
        && slot.textContent === source)
      if (!keyed) {
        return
      }
      target = keyed
    } else {
      const candidates = current.filter((slot) => slot.textContent === source)
      if (candidates.length !== 1) {
        return
      }
      target = candidates[0]!
    }
    if (!bubble.contains(target)) {
      return
    }
  }
  const card = document.createElement('div')
  card.className = MMD_CARD_CLASS
  // svg === null（render error / 3s 等待预算用尽——只停止等待、不中断同步渲染 / 超上限）⇒ 源码态卡兜底
  //（§三.7 口径；不留无 handler 的钮）
  card.innerHTML = buildMermaidCardHtml(svg === null ? null : prepareMermaidSvg(svg))
  const codeBox = card.querySelector('.mmdc-src')
  if (codeBox) {
    // textContent 赋值 = 纯文本，零 HTML 注入面
    codeBox.textContent = source
  }
  const pre = target.parentElement
  if (!pre) {
    return
  }
  pre.replaceWith(card)
  initMermaidInline(card)
}

/** 视口门控：可见才渲染。root = 消息滚动容器（无 IO / 无容器 ⇒ 立即渲染）。 */
function scheduleMermaidSlot(bubble: Element, code: Element, source: string): void {
  const root = msgWrapRef.value
  if (typeof IntersectionObserver === 'undefined' || root === null) {
    void renderMermaidSlot(bubble, code, source)
    return
  }
  if (mermaidObserver === null) {
    mermaidObserver = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) {
          continue
        }
        const target = entry.target
        mermaidObserver?.unobserve(target)
        const pending = mermaidPending.get(target)
        mermaidPending.delete(target)
        if (pending !== undefined) {
          void renderMermaidSlot(pending.bubble, target, pending.source)
        }
      }
    }, { root, rootMargin: '200px' })
  }
  mermaidPending.set(code, { source, bubble })
  mermaidObserver.observe(code)
}

function hydrateMermaid(messageId: string, blocks: Array<{ index: number; key: string; source: string }>): void {
  // 水合 = 重建后的重绑入口：先把上一帧留下的脱离槽摘掉，再为当前 DOM 里的槽登记。
  pruneDetachedMermaidPending()
  pruneDetachedMermaidInlineFit()
  const bubble = msgWrapRef.value?.querySelector(`[data-testid="msg-bubble-${messageId}"]`)
  if (!bubble) {
    return
  }
  const slots = Array.from(bubble.querySelectorAll('pre > code.language-mermaid'))
  let registered = blocks
  if (registered.length === 0 && slots.length > 0) {
    const message = ai.messages.find((item) => item.id === messageId)
    const text = message?.parts.map((part) => part.kind === 'text' ? part.text : '').join('') ?? ''
    if (message && text.length <= DEGRADE_AT_CHARS && !hasUnclosedFence(text)) {
      registered = slots.map((code, index) => ({ index, key: '', source: code.textContent ?? '' }))
    }
  }
  // 问题1 偏差修正批 aj14d ③：按稳定 key（`data-fence-key`）绑槽位 —— 位置下标只作兜底
  // （渲染与 hydrate 之间若又来一帧，位置会漂移，key 不会）。
  const byKey = new Map<string, Element>()
  for (const slot of slots) {
    const key = slot.parentElement?.getAttribute('data-fence-key') ?? ''
    if (key !== '') {
      byKey.set(key, slot)
    }
  }
  for (const item of registered) {
    const code = byKey.get(item.key) ?? slots[item.index]
    if (!code) {
      continue
    }
    scheduleMermaidSlot(bubble, code, item.source)
  }
}

/**
 * R-165 S5a 项 5：长表格双区横滚联动 —— 表体区（唯一横滚容器）滚动时把 `scrollLeft` 同步给
 * 同 wrapper 的表头区（表头区 `overflow-hidden`，靠程序化 scrollLeft 位移）。
 * scroll 事件不冒泡 ⇒ 在消息容器上用**捕获**监听（捕获阶段可截到后代滚动）。
 */
function onTableScrollCapture(event: Event): void {
  const el = event.target
  if (!(el instanceof HTMLElement) || !el.classList.contains('md-table-bd')) {
    return
  }
  // 问题1 偏差修正批 aj04：表头行改「flex 行 = 表头区 + gutter 格」⇒ 可程序化 scrollLeft 的
  // 容器是表头区（.md-table-hdscroll），不再是表头行自身。
  const head = el.parentElement?.querySelector('.md-table-hdscroll')
  if (head instanceof HTMLElement) {
    head.scrollLeft = el.scrollLeft
  }
}

interface BlockPartSignature {
  part: MessagePart
  kind: MessagePart['kind']
  seg: number
  text: string | undefined
  toolCallId: string | undefined
  eventId: string | undefined
  requiresAction: boolean | undefined
}

interface BlocksCacheEntry {
  parts: MessagePart[]
  signatures: BlockPartSignature[]
  blocks: Block[]
}

const BLOCKS_CACHE_LIMIT = 64
const blocksCache = new Map<ChatMessage, BlocksCacheEntry>()

function blockPartSignature(part: MessagePart): BlockPartSignature {
  return {
    part,
    kind: part.kind,
    seg: part.seg,
    text: part.kind === 'text' || part.kind === 'reasoning' ? part.text : undefined,
    toolCallId: part.kind === 'tool' ? part.toolCallId : undefined,
    eventId: part.kind === 'event' ? part.eventId : undefined,
    requiresAction: part.kind === 'tool' ? part.requiresAction : undefined
  }
}

function isBlockPartSignatureMatch(part: MessagePart, signature: BlockPartSignature): boolean {
  return Object.is(part.kind, signature.kind)
    && Object.is(part.seg, signature.seg)
    && Object.is(part.kind === 'text' || part.kind === 'reasoning' ? part.text : undefined, signature.text)
    && Object.is(part.kind === 'tool' ? part.toolCallId : undefined, signature.toolCallId)
    && Object.is(part.kind === 'event' ? part.eventId : undefined, signature.eventId)
    && Object.is(part.kind === 'tool' ? part.requiresAction : undefined, signature.requiresAction)
    && (part.kind !== 'tool' || Object.is(signature.part, part))
}

function shouldShowToolPart(part: ToolPart): boolean {
  if (isSystemRejectPhantom(part.name, part.userTextCode)) {
    return false
  }
  if (part.status === 'skipped') {
    return part.decision !== undefined || part.requiresAction
  }
  const recoveryText = `${part.userTextCode ?? ''}\n${part.reason ?? ''}\n${part.result ?? ''}`
  return part.status !== 'blocked'
    || part.decision !== undefined
    || !recoveryText.includes('TOOL_ALREADY_CLAIMED')
}

/** blocksOf 与 messageMemo 共用有界 LRU；仅在 parts 引用或逐段签名变化时重新分块。 */
function blocksOf(message: ChatMessage): Block[] {
  const cached = blocksCache.get(message)
  if (cached && cached.parts === message.parts
    && cached.signatures.length === message.parts.length
    && cached.signatures.every((signature, index) => {
      const part = message.parts[index]
      return part !== undefined && isBlockPartSignatureMatch(part, signature)
    })) {
    blocksCache.delete(message)
    blocksCache.set(message, cached)
    return cached.blocks
  }

  const visibleParts = message.parts.filter((part) => part.kind !== 'tool' || shouldShowToolPart(part))
  const entry: BlocksCacheEntry = {
    parts: message.parts,
    signatures: message.parts.map(blockPartSignature),
    blocks: groupParts(visibleParts, message.id)
  }
  blocksCache.delete(message)
  blocksCache.set(message, entry)
  if (blocksCache.size > BLOCKS_CACHE_LIMIT) {
    const oldest = blocksCache.keys().next().value
    if (oldest !== undefined) {
      blocksCache.delete(oldest)
    }
  }
  return entry.blocks
}

/** 手动展开选择优先；未触碰的卡按当前真实状态应用默认展开矩阵。 */
function isBlockExpanded(block: Block): boolean {
  const manual = ai.expandedBlocks[block.id]
  if (manual !== undefined) {
    return manual
  }
  const part = head(block.parts)
  if (part?.kind !== 'tool') {
    return false
  }
  const needsReview = hasUnknownReviewRequirement(part)
  if (part.status === 'unknown') {
    return needsReview
  }
  return isBusinessCardDefaultExpanded(cardPresentationStatusKey(part), cardTypeOf(part), needsReview)
}

/** 块级间距只由相邻块关系决定，卡片自身不再叠加上下外边距。 */
function blockSpacingClass(blocks: Block[], index: number): string {
  const current = blocks[index]
  const previous = blocks[index - 1]
  if (!current || !previous) {
    return ''
  }
  if (current.kind === 'activity') {
    return previous.kind === 'text' ? 'mt-[16px]' : 'mt-[10px]'
  }
  if (current.kind === 'text' && previous.kind === 'activity') {
    return 'mt-[12px]'
  }
  return ''
}

/** 已决只读与 GF 提交在途共用原卡锁定形态，不产生新的数据状态。 */
function isReadOnlyLike(part: ToolPart): boolean {
  return !hasIndependentCardHeader(part) && (isReadOnlyCard(part) || isFormAwaitingOutcome(part))
}

function toggleBlock(block: Block): void {
  ai.setBlockExpanded(block.id, !isBlockExpanded(block))
}

/**
 * 批次 7A → 2026-10-29 返修（导出过程整屏闪）：消息 / 块渲染边界。
 *
 * `v-memo` 的依赖必须**只覆盖本消息模板真实读取的值**。旧实现把 `ai.loading` / `ai.runPhase` /
 * `ai.activeRunId` / 末条消息 id 这些**全局**现场并进了每一条消息行：任何一次收流开始/结束、
 * 挂起、恢复或换轮都会让**整列**消息行一起失效（长会话 = 全量重渲正文 + 卡片派生重算），
 * 在单轮密集出帧的场景表现为整屏闪一下。
 *
 * 现行口径：全局现场只以**本消息的判定结果**入场（`activityStateOf` / `isBlockExpanded`
 * 等）—— 结果没变 ⇒ 本行不失效（历史消息一格不动）；结果变了 ⇒ 本行照常
 * 更新（末条消息的呼吸点、挂起等待、卡展开态、继续入口都在）。
 * 输入框 `inputText` 是组件本地态，不在这些依赖里 —— 打字只重渲 composer，不重渲消息列表。
 */
function partMemo(part: MessagePart, block: Block): unknown[] {
  const deps: unknown[] = [
    part.kind,
    part.seg,
    locale.value,
    block.id,
    block.kind,
    // 展开态是块内**唯一**读全局现场（loading / suspended / 决策在途 / 恢复期）的判定结果：
    // 结果入 memo，全局现场本身不入 —— 否则历史块跟着每一次收流翻转一起失效。
    isBlockExpanded(block)
  ]
  if (part.kind === 'reasoning') {
    deps.push(
      part.text,
      part.streaming,
      part.startedAt,
      part.endedAt,
      part.lastFrameAt,
      part.lastFrameLocalAt,
      part.truncated,
      part.noticeCode,
      part.originalChars,
      part.returnedChars
    )
    return deps
  }
  if (part.kind === 'event') {
    // 事件节点只依赖稳定 id（文案随 locale，已在基础 deps）
    deps.push(part.eventType, part.eventId)
    return deps
  }
  if (part.kind === 'text') {
    deps.push(part.text, part.streaming, part.truncated, part.noticeCode, part.originalChars, part.returnedChars)
    // 75ms 防抖的落地信号不在文本里（停吐时文本已是最新值）：流式正文块必须并进 tick，
    // 否则 messageMemo / blockMemo 都会跳过重渲，末帧永远不现身。历史块不带本依赖。
    if (part.streaming) {
      deps.push(mdStreamTick.value)
    }
    return deps
  }
  deps.push(
    part.toolCallId,
    part.name,
    part.kind2,
    part.status,
    part.requiresAction,
    part.args,
    part.result,
    part.userTextCode,
    part.reason,
    part.resultView,
    part.preflight,
    part.submittedValues,
    part.decision,
    part.decidedAt,
    part.outcome,
    part.irreversible,
    part.truncated,
    part.noticeCode,
    part.originalChars,
    part.returnedChars,
    isReadOnlyLike(part),
    cardTypeOf(part),
    ai.gateSubmissionOf(part.toolCallId),
    ai.gateAckOf(part.toolCallId),
    ai.uiUndoToolCallId,
    ai.uiUndoEntry,
    ai.sheetApplied,
    ai.sessionId,
    ai.restoringRecovery
  )
  if (part.name === GENERATIVE_FORM_TOOL) {
    // 队列面只入**标量**（本卡位次 + 队列总数）：整条 `queuedForms` 数组身份入 memo，
    // 会让任意一次入队/出队把全部历史 GF 行一起失效。
    deps.push(formQueuePosition(part.toolCallId), formQueueTotal())
    // 表单现场（`liveForm` 对象与 `values` 身份）只属于**当前宿主**那一张卡：
    // 历史 GF 卡的渲染不读它，无条件入 dep 等于"用户每敲一个字，整列 GF 卡全部重渲"。
    const hostId = formHostMessageId.value
    const liveToolCallId = liveForm.value?.toolCallId ?? null
    deps.push(hostId, liveToolCallId)
    if (liveToolCallId !== null && liveToolCallId === part.toolCallId && hostId === block.messageId) {
      deps.push(liveForm.value, liveForm.value?.status, liveForm.value?.error, liveForm.value?.fieldErrors, liveForm.value?.values)
    }
  }
  if (isUploadHandoffPart(part)) {
    deps.push(ai.activeUploadHandoff, uploadHandoffStatus(part), uploadHandoffAccept(part), uploadHandoffLabel(part), uploadHandoffReason(part), uploadHandoffFileName(part))
  }
  if (isSheetPreviewPart(part)) {
    deps.push(sheetPreviewFor(part), isSheetPreviewApplying(part))
  }
  if (appliedSummaryFor(part) !== null) {
    deps.push(appliedSummaryFor(part))
  }
  return deps
}

function blockMemo(block: Block, message: ChatMessage): unknown[] {
  const deps: unknown[] = [
    message.id,
    message.role,
    locale.value,
    block.id,
    message.streaming,
    isUserMsgCollapsed(message.id)
  ]
  for (const part of block.parts) {
    deps.push(...partMemo(part, block))
  }
  return deps
}

function messageBaseDeps(message: ChatMessage, index: number): unknown[] {
  return [
    message.id,
    message.role,
    message.parts.length,
    message.parts,
    message.streaming,
    message.done,
    message.stopped,
    message.truncated,
    message.timestamp,
    message.pendingCall,
    message.pendingCall?.status,
    message.errorCard,
    message.errorCard?.variant,
    message.errorCard?.title,
    message.errorCard?.summary,
    message.errorCard?.guide,
    message.errorCard?.code,
    message.errorCard?.detail,
    message.errorCard?.action,
    message.code,
    message.interrupted,
    index,
    msgGapClass(index),
    locale.value,
    ai.restoringRecovery,
    canShowMetaRow(message),
    canShowTimestamp(message),
    activityStateOf(message),
    activityStateOf(message) === 'compacting' ? compactingLabel(message) : '',
    isFading(message),
    recoveryErrorVisible.value,
    canShowContinue(message),
    isErrorCardContinueVisible(message),
    feedbackOf(message.id),
    isMsgCopied(message.id),
    copyState.value?.id === message.id ? copyState.value?.ok ?? null : null,
    copyState.value?.id === message.id ? copyState.value?.empty === true : null,
    userMsgOverflowed.value.has(message.id),
    userMsgExpanded.value.has(message.id),
    degradeNotices.value[degradeNoticeKey(message.id)] ?? null
  ]
}

function messageMemo(message: ChatMessage, index: number): unknown[] {
  const deps = messageBaseDeps(message, index)
  for (const block of blocksOf(message)) {
    deps.push(block.id, block.kind, isBlockExpanded(block))
    for (const part of block.parts) {
      deps.push(...partMemo(part, block))
    }
  }
  return deps
}

/** 所有正式卡片都保留可访问的折叠控件和受控卡体。 */
function canShowCardBody(_part: ToolPart): boolean {
  return true
}

type SheetPreviewTerminalStatus = 'applied' | 'failed' | 'cancelled'
type UploadHandoffCardStatus = 'waiting' | 'uploading' | 'uploaded' | 'failed' | 'unsupported' | 'missing' | 'cancelled'

function isSheetPreviewTool(part: ToolPart): boolean {
  return part.kind2 === 'FRONTEND' && SHEET_PREVIEW_TOOLS.has(part.name)
}

function sheetPreviewTerminalStatus(part: ToolPart): SheetPreviewTerminalStatus | null {
  if (part.requiresAction) {
    return null
  }
  if (part.status === 'succeeded') {
    return 'applied'
  }
  if (part.status === 'cancelled') {
    return 'cancelled'
  }
  return part.status === 'failed' || part.status === 'rejected' ? 'failed' : null
}

function isSheetPreviewPart(part: ToolPart): boolean {
  return isSheetPreviewTool(part)
    && (part.requiresAction || sheetPreviewFor(part) !== null || appliedSummaryFor(part) !== null)
}

function isUploadHandoffTool(part: ToolPart): boolean {
  return part.kind2 === 'FRONTEND' && UI_HANDOFF_TOOLS.has(part.name)
}

function uploadHandoffTerminalStatus(part: ToolPart): 'uploaded' | 'failed' | 'cancelled' | null {
  if (part.requiresAction) {
    return null
  }
  if (part.status === 'succeeded') {
    return 'uploaded'
  }
  if (part.status === 'cancelled') {
    return 'cancelled'
  }
  return part.status === 'failed' || part.status === 'rejected' ? 'failed' : null
}

function uploadHandoffSnapshot(part: ToolPart): ActiveUploadHandoff | null {
  const active = ai.activeUploadHandoff
  if (active?.toolCallId === part.toolCallId) {
    return active
  }
  return uploadHandoffSnapshots.value[part.toolCallId] ?? null
}

function isUploadHandoffPart(part: ToolPart): boolean {
  return isUploadHandoffTool(part) && (part.requiresAction
    || (uploadHandoffTerminalStatus(part) !== null && uploadHandoffSnapshot(part) !== null))
}

function hasIndependentCardHeader(part: ToolPart): boolean {
  return isSheetPreviewPart(part) || isUploadHandoffPart(part)
}

function uploadHandoffStatus(part: ToolPart): UploadHandoffCardStatus {
  const terminal = uploadHandoffTerminalStatus(part)
  if (terminal) {
    return terminal
  }
  const handoff = ai.activeUploadHandoff
  if (!handoff || handoff.toolCallId !== part.toolCallId) {
    return 'missing'
  }
  if (handoff.status === 'uploading') {
    return 'uploading'
  }
  return ai.uploadHandoffAvailability() ?? 'missing'
}

function uploadHandoffAccept(part: ToolPart): string[] | null {
  return uploadHandoffSnapshot(part)?.accept ?? null
}

function uploadHandoffReason(part: ToolPart): string | null {
  if (uploadHandoffTerminalStatus(part) !== null) {
    return part.reason || part.result || uploadHandoffSnapshot(part)?.reason || null
  }
  return uploadHandoffSnapshot(part)?.reason ?? null
}

function uploadHandoffFileName(part: ToolPart): string | null {
  return uploadHandoffSnapshot(part)?.filename ?? null
}

/** 入口的可读标签（取注册区域里该 ref 的 aria-label / 文案；取不到用兜底文案）。 */
function uploadHandoffLabel(part: ToolPart): string {
  const handoff = uploadHandoffSnapshot(part)
  if (!handoff?.ref) {
    return t('ai.panel.uploadFallbackLabel')
  }
  const hit = resolveAiRef(handoff.ref)
  const label = hit?.el.getAttribute('aria-label') ?? hit?.el.textContent?.trim() ?? ''
  return label === '' ? t('ai.panel.uploadFallbackLabel') : label
}

/** 操作条：本轮仍在跑/挂起且刚播报过界面动作（停止后自动消失）。 */
const aiOperating = computed(() => ai.uiAnnouncement !== null && (ai.loading || ai.suspended))
/**
 * 播报句（F12）：运行时只存动作文本，前缀在这里由 `ai.panel.uiOperating` 组一次；
 * 可见操作条与 aria-live 共用同一句，避免「AI 正在操作…：AI 正在操作…」双前缀。
 */
const uiOperatingText = computed(() => (ai.uiAnnouncement === null
  ? ''
  : t('ai.panel.uiOperating', { text: ai.uiAnnouncement })))

function onUiUndo(): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.undoLastUiAction()
}

/**
 * 已应用的表格写工具段 → 改动摘要（治理批 §5.4 的卡面一行）。
 *
 * <p>只对"最近一次应用"的那一段显示（`sheetApplied` 是单值），且必须在段上是 succeeded ——
 * 否则会把上一批的摘要挂到这一批失败卡上。
 */
function appliedSummaryFor(part: ToolPart): SheetAppliedSummary | null {
  const summary = ai.sheetApplied
  if (!summary || part.toolCallId !== summary.toolCallId) {
    return null
  }
  return part.status === 'succeeded' ? summary : null
}

/** 待决卡使用活动计划；终态卡只复用本卡已有快照，不从结果文本反造计划。 */
function sheetPreviewFor(part: ToolPart): SheetWritePlan | null {
  const terminal = sheetPreviewTerminalStatus(part)
  if (terminal) {
    const snapshot = sheetPreviewSnapshots.value[part.toolCallId]
      ?? (ai.activePreview?.toolCallId === part.toolCallId ? ai.activePreview : null)
    return snapshot ? { ...snapshot, status: terminal } : null
  }
  return ai.activePreview?.toolCallId === part.toolCallId ? ai.activePreview : null
}

function isSheetPreviewApplying(part: ToolPart): boolean {
  return ai.previewApplyingToolCallId === part.toolCallId
}

function onSheetPreviewApply(acknowledged: boolean): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.confirmWritePreview(acknowledged)
}

function onSheetPreviewCancel(): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.cancelWritePreview()
}

function onSheetPreviewAbandon(toolCallId: string): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.abandonStalePreview(toolCallId)
}

function onSheetPreviewLevel(level: SheetPreviewLevel): void {
  void ai.setPreviewLevel(level)
}

function onUploadHandoffChoose(): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.chooseUploadFile()
}

function onUploadHandoffCancel(toolCallId: string): void {
  if (ai.restoringRecovery) {
    return
  }
  void ai.cancelUploadHandoff(toolCallId)
}

function cardShellClass(part: ToolPart): string {
  return `${isReadOnlyLike(part) ? 'rocard ' : ''}block overflow-hidden rounded-[10px] border border-solid border-[#e3e6ea] bg-white`
}

function cardTestId(part: ToolPart): string {
  if (isReadOnlyLike(part)) {
    return 'hitl-receipt'
  }
  return part.requiresAction ? `pending-card-${part.toolCallId}` : `tool-card-${part.toolCallId}`
}

function cardToggleTestId(part: ToolPart, block: Block): string {
  return isReadOnlyLike(part) ? `ro-toggle-${part.toolCallId}` : `block-toggle-${block.id}`
}

function cardStatusTestId(part: ToolPart): string {
  return isReadOnlyLike(part) ? `ro-badge-${part.toolCallId}` : `card-status-${part.toolCallId}`
}

const FORM_PURPOSE_TITLE_KEYS = {
  SELECT_DEFINITIONS: 'ai.gf.purpose.selectDefinitions',
  SET_CONDITIONS: 'ai.gf.purpose.setConditions',
  TASK_TYPE: 'ai.gf.purpose.taskType'
} as const

type SpecificFormPurpose = keyof typeof FORM_PURPOSE_TITLE_KEYS
type FormPurpose = SpecificFormPurpose | 'GENERAL_CLARIFY'

function isSpecificFormPurpose(value: string): value is SpecificFormPurpose {
  return Object.prototype.hasOwnProperty.call(FORM_PURPOSE_TITLE_KEYS, value)
}

function formPurposeOf(part: ToolPart): FormPurpose | null {
  const rawForm = parseToolArgs(part.args).form
  if (rawForm === null || typeof rawForm !== 'object' || Array.isArray(rawForm)) {
    return null
  }
  const rawPurpose = (rawForm as Record<string, unknown>).purpose
  if (typeof rawPurpose === 'string' && rawPurpose.trim() !== '') {
    const purpose = rawPurpose.trim()
    return isSpecificFormPurpose(purpose) || purpose === 'GENERAL_CLARIFY' ? purpose : null
  }
  const spec = cardFormSpec(part)
  if (spec === null) {
    return null
  }
  if (spec.fields.some((field) => field.optionsFrom?.sourceId === 'definitions')) {
    return 'SELECT_DEFINITIONS'
  }
  return spec.scenario === 'FILTER' ? 'SET_CONDITIONS' : 'GENERAL_CLARIFY'
}

function formCardTitle(part: ToolPart): string {
  const purpose = formPurposeOf(part)
  if (purpose === null) {
    return t('ai.gf.title')
  }
  if (purpose !== 'GENERAL_CLARIFY') {
    return t(FORM_PURPOSE_TITLE_KEYS[purpose])
  }
  return cardFormSpec(part)?.title?.trim() || t('ai.gf.scenario.clarify')
}

/** 卡头只表达短动作或用途；完整业务对象由卡体字段承担。 */
function cardTitle(part: ToolPart): string {
  return cachedForLocale(cardTitleCache, part, 'cardTitle', [part.name, part.kind2, part.args], () => {
    if (isSheetPreviewTool(part)) {
      return t('ai.sheetCard.title')
    }
    if (isUploadHandoffTool(part)) {
      return t('ai.uploadHandoff.title')
    }
    return cardTypeOf(part) === 'form' ? formCardTitle(part) : toolLabel(part.name)
  })
}

function formBodyTitle(part: ToolPart): string {
  const title = cardFormSpec(part)?.title?.trim() ?? ''
  return title === cardTitle(part) || title === t('ai.gf.title') ? '' : title
}

function cardStatusView(part: ToolPart): ReturnType<typeof cardStatusOf> {
  const submitting = liveForm.value?.toolCallId === part.toolCallId && liveForm.value.status === 'submitting'
  const queued = formQueuePosition(part.toolCallId) > 0
  const syncing = isGateSyncing(part.toolCallId)
  return cachedForLocale(
    cardStatusCache,
    part,
    'cardStatus',
    [part.name, part.kind2, part.status, part.decision, part.requiresAction, part.submittedValues, submitting, queued, syncing],
    () => cardStatusOf(part, { submitting, queued, syncing })
  )
}

function cardIconName(part: ToolPart): string {
  if (isSheetPreviewTool(part)) {
    return 'sheet'
  }
  if (isUploadHandoffTool(part)) {
    return 'upload'
  }
  return cardTypeOf(part) === 'tool' ? part.name : cardTypeOf(part)
}

function cardFamilyOf(part: ToolPart): BusinessCardFamily {
  return cardTypeOf(part)
}

function cardPresentationStatusKey(part: ToolPart): string {
  return cardStatusView(part).key
}

function cardStatusLabel(part: ToolPart): string {
  const sheetStatus = isSheetPreviewTool(part) ? sheetPreviewTerminalStatus(part) : null
  if (sheetStatus) {
    return t(`ai.sheetCard.status.${sheetStatus}`)
  }
  const uploadStatus = isUploadHandoffTool(part) ? uploadHandoffTerminalStatus(part) : null
  if (uploadStatus) {
    return t(`ai.uploadHandoff.status.${uploadStatus}`)
  }
  if (cardPresentationStatusKey(part) === 'unknown' && isUnknownUnrecoverable(part)) {
    return t('ai.card.status.unrecoverable')
  }
  return cardStatusView(part).label
}

function cardStatusTone(part: ToolPart): BusinessCardTone {
  const terminal = isSheetPreviewTool(part)
    ? sheetPreviewTerminalStatus(part)
    : isUploadHandoffTool(part) ? uploadHandoffTerminalStatus(part) : null
  if (terminal) {
    return terminal === 'failed' ? 'red' : terminal === 'cancelled' ? 'gray' : 'green'
  }
  return businessCardStatusTone(cardPresentationStatusKey(part), cardFamilyOf(part))
}

function onCardHeadClick(block: Block, _part: ToolPart): void {
  toggleBlock(block)
}

/** 普通工具卡参数行与门卡共用敏感键掩码。 */
function paramRows(part: ToolPart): FieldRow[] {
  return cachedForLocale(parameterRowsCache, part, 'paramRows', [part.args], () => argRows(part, true))
}

/** 发布冲突明细行（N8）：从段 reason 解析出 `defCode#rowKey：message` 明细 → 表格数据。 */
function conflictRows(part: ToolPart): ConflictRow[] {
  return cachedForLocale(conflictRowsCache, part, 'conflictRows', [part.reason], () => parsePublishConflicts(part.reason))
}

function toolContentLimitClass(part: ToolPart): string {
  return conflictRows(part).length > 0 ? 'max-h-[min(480px,60vh)]' : 'max-h-[min(280px,35vh)]'
}

/**
 * 「输出」区是否由视图通道接管（S6 批2 **迁移步④**，设计稿 §7.1-④）。
 *
 * 判据 = {@link isRenderableView}：同一份真值在卡面上只报一次 —— 视图可渲则原文盒让位
 * （原文仍是 `part.result`，模型侧与技术详情展示不受影响）；视图渲不出 / 段上没有视图 ⇒ 原文盒照旧。
 * `data.ref` 的求值域是 `part.result` 原文（与渲染器同源），所以两处传同一份文本。
 */
function hasViewOutput(part: ToolPart): boolean {
  return cachedForLocale(outputByViewCache, part, 'hasViewOutput', [part.resultView, part.result], () => (
    part.resultView != null && isRenderableView(part.resultView, part.result ?? null)
  ))
}

/** 截断文案的公共字段（ToolPart / TextPart / ReasoningPart 同形）。 */
interface TruncationCarrier {
  noticeCode?: ToolPart['noticeCode']
  originalChars?: number
  returnedChars?: number
}

/**
 * A10 R-15：截断角标 hover 文案 —— 只按当前协议的 `noticeCode` + 字符数用 locale 拼装；
 * 没有可翻译原因时给固定兜底（旧 `truncatedReason` 回退已退场）。
 */
function truncationTitle(part: TruncationCarrier): string {
  const original = part.originalChars
  const returned = part.returnedChars
  const hasNumbers = original != null && returned != null
  if (part.noticeCode === 'frame_truncated' && hasNumbers) {
    return t('ai.panel.truncatedFrame', { original, returned })
  }
  if (part.noticeCode === 'model_view_truncated' && hasNumbers) {
    return t('ai.panel.truncatedModelView', { original, returned })
  }
  if (part.noticeCode === 'frame_limit_unreached') {
    return t('ai.panel.truncatedLimitUnreached')
  }
  return t('ai.panel.truncatedFallback')
}

const JSON_SENSITIVE_KEY_RE = /pass(word)?|secret|token|api_?key|id_?card/i
const JSON_TECHNICAL_KEYS = new Set([
  'runid', 'toolcallid', 'fileid', 'sessionid', 'messageid', 'code', 'ok', 'outcome',
  'retryable', 'truncated', 'originalchars', 'returnedchars', 'strategy', 'target'
])

function jsonRecordText(record: Record<string, unknown>, depth: number): string {
  if (depth > 6) {
    return ''
  }
  const rows: string[] = []
  if (record.message !== null && record.message !== undefined && record.message !== '') {
    const message = jsonValueText(record.message, depth + 1, false).trim()
    if (message) {
      rows.push(message)
    }
  }
  for (const [key, value] of Object.entries(record)) {
    const normalizedKey = key.replace(/[_-]/g, '').toLowerCase()
    if (key === 'message' || JSON_TECHNICAL_KEYS.has(normalizedKey) || !canShowCardField(key)) {
      continue
    }
    const sensitive = JSON_SENSITIVE_KEY_RE.test(key) || (record.format === 'idcard' && key === 'value')
    const text = localizedCardFieldValue(key, value) ?? jsonValueText(value, depth + 1, sensitive)
    if (text) {
      rows.push(`${cardFieldLabel(key)}: ${text}`)
    }
  }
  return rows.join('\n')
}

function jsonValueText(value: unknown, depth: number, sensitive: boolean): string {
  if (sensitive) {
    return MASKED_VALUE
  }
  if (value === null || value === undefined || value === '') {
    return t('ai.value.empty')
  }
  if (typeof value === 'boolean') {
    return t(value ? 'ai.value.yes' : 'ai.value.no')
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? String(value) : ''
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      if (depth >= 6) {
        return ''
      }
      try {
        return jsonValueText(JSON.parse(trimmed) as unknown, depth + 1, false)
      } catch (error) {
          logCaughtError(error, 'components/AiPanel/AiPanel.vue:3012')
        return projectDisplayText(readableValue(value))
      }
    }
    return projectDisplayText(readableValue(value))
  }
  if (Array.isArray(value)) {
    return value.map((item) => jsonValueText(item, depth + 1, false)).filter(Boolean).join('\n')
  }
  if (typeof value === 'object') {
    return jsonRecordText(value as Record<string, unknown>, depth)
  }
  return ''
}

function displayToolResult(text: string | null | undefined): string {
  const raw = (text ?? '').trim()
  if (raw.startsWith('{') || raw.startsWith('[')) {
    try {
      return jsonValueText(JSON.parse(raw) as unknown, 0, false)
    } catch (error) {
        logCaughtError(error, 'components/AiPanel/AiPanel.vue:3032')
      // 非完整 JSON 继续走既有文本清理。
    }
  }
  return projectDisplayText(text) ?? ''
}

function toolResultText(part: ToolPart): string {
  const code = part.userTextCode?.trim()
  if (!code) {
    return displayToolResult(part.result)
  }
  const args = userTextArgsOf(part) ?? {}
  const createdTaskId = code === 'CREATE_TASK_TURN_LIMIT' ? numericArgOf(args, 'taskId') : null
  const key = createdTaskId !== null
    ? 'ai.panel.toolResult.CREATE_TASK_TURN_LIMIT_WITH_ID'
    : code.startsWith('ai.') ? code : `ai.panel.toolResult.${code}`
  const translated = t(key, { ...args, id: createdTaskId ?? '' })
  return displayToolResult(translated === key ? part.result : translated)
}

function textPartOf(block: Block): TextPart | null {
  const part = head(block.parts)
  return part?.kind === 'text' ? part : null
}

function textTruncationTitle(block: Block): string {
  const part = textPartOf(block)
  return part ? truncationTitle(part) : ''
}

/**
 * 卡面 reason 红底行文案（S6 批1 / v7.10 N8 → S6 批2 §5.3）：
 * - **有 `resultView`** ⇒ 只留首行摘要，**不调用** `parsePublishConflicts`
 *   （明细由 `ResultViewRenderer` 渲，设计稿 §5.3-1/4、§7.2-6 的负断言就在这里成立）；
 * - 无视图但能解析出编号明细（旧镜像 / 旧归档）⇒ 仍只留首行，明细走旧反解表（回退路径保留）；
 * - 其余失败 / 拒绝原因 ⇒ 原文（多行错误文案不截断，v-show 口径不变）。
 */
function reasonText(part: ToolPart): string {
  const reason = part.reason ?? ''
  if (part.resultView) {
    return reasonHeadline(reason)
  }
  return conflictRows(part).length > 0 ? reasonHeadline(reason) : reason
}

// ── 批次6A（N8 / N9）：给人看的话 / 给模型与排障看的话，分层展示 ─────────────

/** 诊断分层的**人话**词条（与 `utils/parts.ts#partDiagnosticOf` 的三分类一一对应）。 */
const DIAGNOSTIC_HUMAN_KEY: Record<PartDiagnostic, string> = {
  skipped: 'ai.panel.skippedHuman',
  staleGate: 'ai.panel.gateExpiredHuman',
  internal: 'ai.panel.internalHuman',
  sheetInput: 'ai.panel.sheetInputHuman'
}

function diagnosticOf(part: ToolPart): PartDiagnostic | null {
  return cachedForLocale(diagnosticCache, part, 'diagnosis', [part.status, part.decision, part.kind2, part.reason, part.result], () => partDiagnosticOf(part))
}

function diagnosticHumanOf(part: ToolPart): string | null {
  const kind = diagnosticOf(part)
  return kind === null ? null : t(DIAGNOSTIC_HUMAN_KEY[kind])
}

/** 机器向原文不上用户卡面；分层命中时只保留对应的人话说明。 */
function isOutputSupersededByDiagnostics(part: ToolPart): boolean {
  return diagnosticOf(part) !== null && isPartResultInternal(part)
}

/** 只读卡原因保留业务说明；机器标记与整段 JSON 不上用户面。 */
function readOnlyReasonText(part: ToolPart): string {
  if (diagnosticOf(part) === 'skipped') {
    return ''
  }
  const human = diagnosticHumanOf(part)
  if (human !== null) {
    return human
  }
  return isBlankText(part.reason) ? '' : displayToolResult(part.reason)
}

function faceReasonText(part: ToolPart): string {
  const kind = diagnosticOf(part)
  if (kind === 'skipped' || kind === 'staleGate') {
    return ''
  }
  if (kind === 'internal') {
    return t('ai.panel.internalHuman')
  }
  if (kind === 'sheetInput') {
    return t('ai.panel.sheetInputHuman')
  }
  return isBlankText(part.reason) ? '' : displayToolResult(reasonText(part))
}

/**
 * 工具展示名（S2 收口）：`tool.<name>.title` 是唯一来源（zh 值与迁移前 `TOOL_LABELS` 逐字一致）。
 * 缺键时使用业务化通用名称，不渲染内部工具名或资源键。
 */
function toolLabel(name: string): string {
  const key = `tool.${name}.title`
  const value = t(key)
  return value === key ? t('tool.unknown.title') : value
}

/**
 * R-165 S2：顶部提示（demo v7 `.alert`）四族 —— 红=故障 / 蓝=并发·降级 / 绿=结果 / 灰=通知。
 * 结构与 v7 逐条对齐：flex 起排 + gap 9px + 圆角 10 + padding 9px 11px + 15×15 描边圆图标
 * + 标题 12.6px/650 + 正文 11.8px #57606a + ghost 动作钮。
 * ⚠ preflight 关闭（tailwind.config.js corePlugins.preflight=false）⇒ 无全局 border-style:solid，
 * 所有描边一律显式 `border-solid`（R-165 S1 §5① 实证：纯 border-* 只出 width、style 恒 none）。
 */
const ALERT_BOX_BASE = 'm-2 flex items-start gap-[9px] border rounded-[10px] px-[11px] py-[9px]'
const ALERT_ICON_BASE = 'flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.4px] text-[10px] font-bold flex items-center justify-center mt-[2px]'
const ALERT_TONE: Record<'error' | 'primary' | 'success' | 'gray', { box: string; ic: string }> = {
  error: { box: 'border-solid border-[#f0d5d0] bg-[#fdf1ef]', ic: 'border-[#c0392b] text-[#c0392b]' },
  primary: { box: 'border-solid border-[#cddcff] bg-[#eff4ff]', ic: 'border-[#2563eb] text-[#2563eb]' },
  success: { box: 'border-solid border-[#cbe7d6] bg-[#edf7f0]', ic: 'border-[#1a7f45] text-[#1a7f45]' },
  gray: { box: 'border-solid border-[#e3e6ea] bg-[#f6f7f9]', ic: 'border-[#8b949e] text-[#8b949e]' }
}
/** ghost 动作钮（v7 提示内按钮形制：白底细描边小圆角，悬停加深描边与字色）。
 *  批G · G5：「一键重挂」/「重挂该轮」按钮退场 —— 在途轮由刷新恢复静默续收，横幅与按钮一并不再存在。 */

/**
 * R-165 S3a（R11 指挥官裁决）→ AR-13：error 级通知判据。
 * 通知级别由**写入通知时**随 `noticeSeverity` 落库，面板只读它；`lastRoundOutcome` 是轮次状态，
 * 不能让上一轮的错误把新会话/新轮的一条无害提示染红。
 */
const noticeIsError = computed(() => ai.notice !== null && ai.noticeSeverity === 'error')

/** 池饱和已有专属 Alert 时，通知槽不再重复同一事实。 */
const noticeCoveredByAlert = computed(() => ai.poolSaturated !== null
  && (ai.noticeCode === 'ai.notice.poolSaturated' || ai.noticeCode === 'ai.notice.poolSaturatedWithSize'))

/** 自动恢复通知静默；最终恢复失败由 recoveryErrorVisible 单独呈现。 */
const noticeBarVisible = computed(() => ai.notice !== null
  && ai.noticeCode !== 'ai.notice.upstreamRetry'
  && !noticeCoveredByAlert.value)

/**
 * 工作区动作回执位是否渲染。
 *
 * <p>成功且结果已在工具卡/业务页可见的 ui_event 由 store 直接静默（不写回执位）；
 * 失败/被拒与视口外结果（如下载）仍会落到这里 —— 有内容就显示。
 */
const uiEventReceiptVisible = computed(() => ai.lastUiEventResult !== null)

/**
 * 通知槽文本（i18n L-69）：store 里 `notice` 是**设定时**的语言快照，`noticeCode/noticeArgs` 才是
 * 结构化形态 —— 渲染走后者，切语言即重译；没有 code 的（后端 message 原文）回落到 `notice`。
 */
const noticeText = computed(() => ai.noticeText)

function alertBox(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_BOX_BASE} ${ALERT_TONE[tone].box}`
}

function alertIcon(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_ICON_BASE} ${ALERT_TONE[tone].ic}`
}

/** 创建任务只说明用户需要的后果；其他确认卡保留预检给出的真实业务影响。 */
function gateConsequenceText(part: ToolPart): string | null {
  if (part.name === 'create_task') {
    const type = part.preflight?.target?.type?.toUpperCase()
    return type === 'EXPORT' || type === 'IMPORT'
      ? t(`ai.hitl.createTaskConsequence.${type}`)
      : null
  }
  const impact = part.preflight?.impact?.trim()
  return impact !== undefined && impact !== '' ? impact : null
}

/** 门卡卡体展示确认时的参数与 preflight 快照；权威快照覆盖同义参数，避免重复。 */
function gateRows(part: ToolPart): FieldRow[] {
  return cachedForLocale(
    gateRowsCache,
    part,
    'gateRows',
    [part.args, part.preflight],
    () => {
      let rows = [...argRows(part, true)]
      const preflight = part.preflight
      if (!preflight) {
        return rows.filter((row) => row.key !== 'title' || (typeof row.value === 'string' && row.value.trim() !== ''))
      }
      const target = preflight.target
      const targetRows: FieldRow[] = [
        ...(target.taskId !== undefined && target.taskId !== null && `${target.taskId}` !== ''
          ? [{ key: 'taskId', label: cardFieldLabel('taskId'), value: String(target.taskId) }]
          : []),
        { key: 'type', label: cardFieldLabel('type'), value: localizedCardFieldValue('type', target.type) ?? target.type },
        { key: 'title', label: cardFieldLabel('title'), value: target.title }
      ]
      for (const row of targetRows) {
        if (!rows.some((existing) => existing.key === row.key)) {
          rows.push(row)
        }
      }
      const conditionSummary = preflight.conditionSummary?.trim()
      if (conditionSummary) {
        rows = rows.filter((row) => row.key !== 'conditions')
        rows.push({ key: 'conditions', label: cardFieldLabel('conditions'), value: conditionSummary })
      }
      const publishMode = preflight.publishMode?.trim()
      if (publishMode) {
        rows = rows.filter((row) => row.key !== 'mode')
        rows.push({
          key: 'mode',
          label: cardFieldLabel('mode'),
          value: localizedCardFieldValue('mode', publishMode) ?? publishMode
        })
      }
      return rows.filter((row) => row.key !== 'title' || (typeof row.value === 'string' && row.value.trim() !== ''))
    }
  )
}

/**
 * R-165 S2：操作行 tip 气泡（demo v7 `.tip`：黑底 #1f2328 / 白字 11px / 圆角 6 / padding 3px 8px /
 * bottom calc(100%+7px) / 居中）。触发用 Tailwind **具名组** `group/tip`（按钮自身声明，不与消息行
 * 的 `group` 争用）；无 JS、无伪元素。v7 的 `::after` 小三角用**旋转方块 div** 复刻
 * （8×8 rotate-45 置于气泡下沿中央，下半露出即下指三角；伪元素属手写 CSS，铁律禁用）。
 * R-165 S5a：`TIP_CLASS` / `TIP_ARROW_CLASS` 字面量收归 `utils/markdown.ts` 单一来源
 * （渲染层注入 HTML 里的代码块复制钮同用一套），本组件顶部 import 直接引入。
 */

/** R-165 S2：历史分页入口胶囊（v7 `.mini` chips：999px 胶囊 + 细描边 + 悬停蓝）。 */
const SUGGESTION_CHIP = 'rounded-[999px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#cddcff] hover:text-[#2563eb] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-[#e3e6ea] disabled:hover:text-[#57606a]'

/**
 * R-165 S2：骨架屏渐变条（demo v7 `.sk`）。v7 的 `animate-shimmer` 是 demo 私有 config 的动画
 * （仓库 tailwind.config.js 禁改、仓库 CSS 无 shimmer keyframes），改用内置 `animate-pulse`
 * （同为无限循环的占位律动，语义等价、幅度略缓）。宽度**字面量**书写（禁运行期拼类名，否则 JIT 扫不到）。
 */
const SKELETON_BAR_BASE = 'bg-[linear-gradient(90deg,#f6f7f9_0,#eef0f3_40%,#f6f7f9_80%)] bg-[length:220%_100%] animate-pulse motion-reduce:animate-none rounded-[6px] h-[11px] mb-[9px]'
const SKELETON_BARS = ['w-[72%]', 'w-[92%]', 'w-[58%]', 'w-[85%]'].map((w) => `${SKELETON_BAR_BASE} ${w}`)

/**
 * R-161：统一程序化钉底入口 —— 设 scrollTop=scrollHeight + 记落点 pinGuardTop + 置
 * followBottom=true。一切钉底（挂载首钉 / 发送 force / 回到底部 / 统一调度的尺寸补偿）必经
 * 此口；调度侧的重复触发（DOM 补丁后复测 + RO）在这一层按「是否已在底部」去重。
 */
function pinBottomTo(wrap: HTMLElement, target: number): void {
  // 批次 7A：同一帧里的多个钉底信号可能都走到这里。已在底部时直接返回，
  // 不再重复写 scrollTop / 开静默期 —— 否则每帧多出一次「写-读」布局回合。
  if (bottomDist(wrap) <= 1) {
    followBottom.value = true
    updateBackToBottom(0)
    return
  }
  // D2③：只有"真的把视口往上追了一段"（内容长高了）才会引出浏览器的补偿/回声事件 ——
  // 这类钉底才开静默期。挂载首钉（scrollTop 与 scrollHeight 本就相等）不开，
  // 否则用户刚进页面就上滑会被误判成"布局回声"（既有跟随口径一格不动）。
  const caughtUp = target - wrap.scrollTop > 1
  wrap.scrollTop = target
  pinGuardTop = wrap.scrollTop
  if (caughtUp) {
    pinSettleUntil = Date.now() + PIN_SETTLE_MS
  }
  followBottom.value = true
  updateBackToBottom(bottomDist(wrap))
}

function pinBottom(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  pinBottomTo(wrap, wrap.scrollHeight)
}

/**
 * 挂载首钉：尊重跟随开关（用户上滑暂停后，任何自动路径都不把视图拽回底部）。
 * 流式 / 卡片增长的跟随走 {@link scheduleStickToBottom} 的尺寸判定，不在这里。
 */
function scrollBottom(force = false): void {
  if (!force && !followBottom.value) {
    return
  }
  pinBottom()
}

/**
 * 分页：加载更早一页并**保持视口不跳**。
 *
 * <p>用**原首条消息元素**作锚点（`getBoundingClientRect().top` 的前后差）补偿，而不是容器
 * `scrollHeight` 差 —— 后者会把底部流式新增的高度也算进去，用户上翻阅读时会被多滚一段。
 * 贴底跟随交给统一 stick 逻辑；浏览器原生滚动锚定已补偿时不重复补。
 */
async function loadEarlierHistory(): Promise<void> {
  const wrap = msgWrapRef.value
  const firstId = head(ai.messages)?.id ?? null
  const beforeTop = wrap?.scrollTop ?? 0
  const anchorBefore = firstId === null
    ? null
    : msgWrapRef.value?.querySelector(`[data-msg-id="${firstId}"]`) as HTMLElement | null
  const beforeAnchorTop = anchorBefore?.getBoundingClientRect().top ?? null
  await ai.loadEarlierHistory()
  if (!wrap || firstId === null || beforeAnchorTop === null || followBottom.value) {
    return
  }
  await nextTick()
  if (Math.abs(wrap.scrollTop - beforeTop) > 1) {
    return // 浏览器原生锚定已经补偿过
  }
  const anchorAfter = wrap.querySelector(`[data-msg-id="${firstId}"]`) as HTMLElement | null
  if (!anchorAfter) {
    return
  }
  const shifted = anchorAfter.getBoundingClientRect().top - beforeAnchorTop
  if (shifted <= 0) {
    return
  }
  wrap.scrollTop = beforeTop + shifted
  pinGuardTop = wrap.scrollTop
}

/**
 * R-154 #17 → R-162 M2：消息时间戳渲染（仅展示、可选）改走 `formatProgressiveTs`
 * （utils/format.ts 集中来源；历史路 = 后端条目 metadata.timestamp，实时 AI 路以 start /
 * message_start 记录在途兜底、done/error 终帧 atMs 确定最终值；无法解析 / 缺失 ⇒ 空串不渲染
 * 时间位）。R-158 #10 回执决策时刻同口径。
 */

/**
 * 2026-10-30 用户直裁：编辑 = **只把该条正文回填到输入框并聚焦**。
 *
 * <p>不发送、不重跑、不删除/截断原消息、不设编辑/重跑锚点：之后用户自己点发送，
 * 就是一条普通新消息（`send` 不再有改写/截断分支，见 send）。
 * 仅用户消息可编辑（模板 v-if role 过滤）。正文口径与「复制该条消息正文」同源
 * （`deriveCopyText`：parts 的 text 段，段间空行）。
 *
 * <p>覆盖保护（同日补充规格）：输入框**含任何非空白字符**时先弹 `window.confirm`
 * （`ai.panel.overwriteConfirm`）—— 确认才复制并聚焦，取消则输入框与消息完全不变；
 * 空 / 全空白字符不打扰，直接复制聚焦。确认只决定"敢不敢覆盖草稿"，与发送/重跑无关。
 */
function editMessage(m: ChatMessage): void {
  if (inputText.value.trim() !== '' && !window.confirm(t('ai.panel.overwriteConfirm'))) {
    return
  }
  inputText.value = deriveCopyText(m)
  composerRef.value?.focus({ preventScroll: true })
}

/**
 * 本轮是否仍可给出消息级恢复入口（最后一条助手消息 + 真实失败/断流 + 未被停止）。
 *
 * <p>取消（`cancelled`）**不是**可恢复的失败轮：停止是用户的终态决定，恢复入口（继续）
 * 必须彻底排除。消息级 `stopped` 是首要判据（含零产出轮）。
 * `unconfirmed` 是"终态暂不可确认"，不是普通失败，同样不出继续。
 *
 * <p>判据以**消息级事实**为准（`errorCard` / `code` / `interrupted`）—— 它们都会随镜像活过
 * 刷新；`lastRoundOutcome` 只是本场会话的即时补充（纯内存，刷新后是初值 'ok'），不得单独依赖。
 * 否则刷新后失败/断流末条会一个「继续」都不出。
 */
function isFailedLastAssistant(message: ChatMessage): boolean {
  const last = ai.messages[ai.messages.length - 1]
  return message.role === 'assistant'
    && last === message
    // 只对**已收尾**的消息出恢复入口：流式/接管中的消息不是"失败的末条"
    && message.done === true
    && message.streaming !== true
    && message.stopped !== true
    && message.unconfirmed !== true
    && ai.lastRoundOutcome !== 'unconfirmed'
    && (message.errorCard != null
      || message.code != null
      || message.interrupted === true
      || ai.lastRoundOutcome === 'error'
      || ai.lastRoundOutcome === 'interrupted')
}

/**
 * 继续：失败/断流的最后一条助手消息，无论有无正文/思考都可出（正常发送一条用户可见的
 * 「继续」，不删历史）；停止/取消、unconfirmed、非末轮不出。
 * 配额错误卡或统一恢复错误条已带动作时，元信息行不重复该入口。
 * 普通 regenerate：本批不做；恢复期间继续入口保留但禁用，不发起新轮。
 */
function canShowContinue(message: ChatMessage): boolean {
  return !recoveryErrorVisible.value && isFailedLastAssistant(message) && !isErrorCardContinueVisible(message)
}

/** R-165 S1：操作行图标按钮配方 —— demo v7 `.icon-btn` 工具类逐条照搬（含 preflight 关闭下的
 *  UA 复位 border-0 bg-transparent p-0；尺寸取等值别名 w-7 h-7 = 28×28，承既有热区断言锚）。
 *  色板**按态整体换**（不靠 `[&.is-on]` 压 hover）：实测 hover 与任意变体同特异度时胜负由
 *  Tailwind 产出顺序决定（该顺序随全仓候选集漂移，本地单文件构建与 dev server 已不一致），
 *  故选中态直接不挂 `hover:*` —— 与 v7 `.is-on:hover` 同色表现，且零顺序依赖。
 *  `is-on` 仅留给 SVG 填充（`[&.is-on_svg]:fill-current` 特异性 0,2,1 恒压 `[&_svg]` 0,1,1）。
 *  R-165 S2：追加具名组 `group/tip` —— tip 气泡的显隐锚（`group-hover/tip`），
 *  与消息行的匿名 `group`（hover 操作行浮出）互不串味。 */
const ICON_BTN_CLASS = 'group/tip relative inline-flex items-center justify-center w-7 h-7 rounded-[8px] border-0 bg-transparent p-0 transition [&_svg]:w-[15px] [&_svg]:h-[15px] [&_svg]:fill-none [&_svg]:stroke-current [&_svg]:[stroke-width:1.6] [&_svg]:[stroke-linecap:round] [&_svg]:[stroke-linejoin:round] [&.is-on_svg]:fill-current'
/** 常态（未选中）：灰字 + 悬停灰底深字（v7 `hover:text-[#1f2328] hover:bg-[#eaecf0]`）。 */
const ICON_BTN_IDLE = 'text-[#8b949e] hover:text-[#1f2328] hover:bg-[#eaecf0]'
/** 赞选中（蓝族；悬停保持蓝族 —— v7 `.is-on:hover` 口径）。 */
const ICON_BTN_ON_BLUE = 'is-on text-[#2563eb] bg-[#eff4ff]'
/** 踩选中（红族 —— R-163 T3 赞蓝/踩红语义保留，不随 v7 统一蓝）。 */
const ICON_BTN_ON_RED = 'is-on text-[#c0392b] bg-[#fdf1ef]'

/**
 * R-163 T3：赞踩本地态（选中互斥、可取消）+ 上报（ rating=0=取消，幂等 upsert 在后端）。
 *
 * L-80（反馈失败静默）：上报失败 ⇒ **回退选中态** + 一条人话提示。旧实现乐观置位后
 * `void` 掉结果，失败时按钮停在"已赞"而服务端什么都没记 —— 用户以为记上了。
 * 回退目标取**点击前**的本地态（可能是"没选"或反向选中），不是简单置 0。
 */
const feedbackState = ref<Map<string, 1 | -1>>(new Map())

function feedbackOf(messageId: string): 1 | -1 | 0 {
  return feedbackState.value.get(messageId) ?? 0
}

async function rateMessage(m: ChatMessage, rating: 1 | -1): Promise<void> {
  const before = new Map(feedbackState.value)
  const next = new Map(feedbackState.value)
  const current = next.get(m.id)
  const finalRating = current === rating ? 0 : rating // 选中互斥 + 再点取消
  if (finalRating === 0) {
    next.delete(m.id)
  } else {
    next.set(m.id, finalRating)
  }
  feedbackState.value = next
  // R-163 收口修 F2：上报携被评价消息正文前 200 字符快照（人工复盘可读摘要）
  const saved = await ai.submitFeedback(m.id, finalRating, m.content.slice(0, 200))
  if (!saved) {
    // 服务端没记上 ⇒ 本地态不得停在"已选中"
    feedbackState.value = before
    ai.showRawNotice(t('ai.panel.feedbackFailed'))
  }
}

/** 卡片表单 schema（待填写 / 已决 GF 共用）：按段缓存。 */
const formSpecCache = new WeakMap<ToolPart, FormSpec | null>()

function cardFormSpec(part: ToolPart): FormSpec | null {
  const cached = formSpecCache.get(part)
  if (cached !== undefined) {
    return cached
  }
  const spec = formSpecOf(part)
  formSpecCache.set(part, spec)
  return spec
}

/** 已受理的提交值持续回填；后续执行结局不再清空卡体快照。 */
function readOnlyFormValues(part: ToolPart): Record<string, unknown> | null {
  return part.submittedValues ?? null
}

/**
 * 发送代次（L-83）：每次 `send()` 领一个自增号，收尾只有**仍是当代**时才可写输入框。
 *
 * <p>为什么需要：停止与回车同 tick 的竞态里，"被停止的旧轮"与"刚发出的新轮"两个 `send()`
 * 同时在飞。旧轮的 `ai.send()` 因轮次身份被换掉而收尾为 `interrupted`（不是 `cancelled`），
 * 于是走失败还原把**旧轮自己的草稿**（= 上一条用户消息）写回输入框；新轮随后被 409 拒，
 * 其还原又被 `inputText !== ''`（误判成"用户刚输入了新内容"）挡掉 ⇒ 用户刚打的草稿被
 * 上一条用户消息静默覆盖。代次判定把"输入框的主人"收窄成**最后一次发送**。
 */
let sendGeneration = 0

async function send(): Promise<void> {
  const draft = inputText.value
  const text = draft.trim()
  if (!text) {
    // 空内容静默（用户没输入，不值得打扰）
    return
  }
  if (!ai.canSend) {
    // 批G · G6（用户直裁 2026-10-22）：在途轮里发送**不响应且零提示** —— 草稿保留在输入框，
    // 不弹 notice。同源规格见下方 onComposerKeydown 的「流式中 preventDefault 后静默返回」。
    return
  }
  // Task 4 返修 C2：身份目录不可用 / 尚未发号 ⇒ 发送闸关闭（按钮已 disabled，这里兜住回车路径）。
  if (identityBlocked.value) {
    return
  }
  // R-157 #4 → R-161：发送即统一 pinBottom 强制钉底（上滑暂停不挡住主动发送）。
  pinBottom()
  const generation = ++sendGeneration
  inputText.value = ''
  composerRef.value?.focus({ preventScroll: true })
  const result = await ai.send(text)
  // 已被更晚的发送取代：这一轮不再拥有输入框 —— 草稿一律不写（L-83：否则旧轮的
  // 还原会把上一条用户消息盖到新轮草稿上，新轮再因"输入框非空"放弃还原）。
  if (generation !== sendGeneration) {
    return
  }
  // 成功 / 主动停止：草稿保持已清空（既有口径：停止不是失败，不把刚发出去的文本塞回输入框）。
  if (result.outcome === 'ok' || result.outcome === 'cancelled') {
    return
  }
  // 用户已在流式中写入新内容：不覆盖。
  if (inputText.value !== '') {
    return
  }
  // 只有「**本条**确实被自动恢复接管」才不回稿 —— 判据必须同时满足三件：
  // ① 仍有连接在跑（`ai.loading`）；② 结局是 interrupted（断流/接管，不是真失败）；
  // ③ 本次乐观用户消息**仍在消息流里**（`userMessageId` 非空且找得到）。
  // 为什么不能只写 `if (ai.loading) return`：`loading` 只表示"**有任何一轮**在跑"——
  //   · 409 SESSION_BUSY 接管回滚（R-04）会把本次乐观用户+助手气泡一起删掉，随后结局被
  //     `settleOutcome` 判成 interrupted 而接管方同时置 `loading=true` ⇒ 只按 loading 跳过回稿，
  //     "草稿只留在输入框一处"就变成"零处"（提示还说"这条消息没有发出去"）；
  //   · `not-sent`（`stopping` 等待期间被续收插队）连用户气泡都没有，同样会被吞。
  // 这两类都要求消息已不在 / 没有消息 ⇒ 回落既有回稿路径。真失败（error）`loading` 本就为假，一字不变。
  const takenOverByRecovery = ai.loading
    && result.outcome === 'interrupted'
    && result.userMessageId != null
    && ai.messages.some((message) => message.id === result.userMessageId)
  if (takenOverByRecovery) {
    return
  }
  // 失败 / 断流 / 未发送：原稿回流并聚焦。
  inputText.value = draft
  composerRef.value?.focus({ preventScroll: true })
}

function stop(): void {
  resetFormAttachmentAbort()
  clearStreamMdCache()
  void ai.stop()
}

/**
 * `document.execCommand('copy')` 回退（苞 B 项 2）。
 *
 * 为什么需要：`navigator.clipboard` 只在**安全上下文**（https / localhost）可用，而
 * `vite.config.ts` 的 `host: '0.0.0.0'` 让本仓存在「经内网 IP + http 访问」的真实面，
 * 那里 `navigator.clipboard` 是 `undefined`；没有回退就是"点了没反应"的静默失败。
 * 走临时 textarea（只读、离屏）+ `select()` + `execCommand`，结束即移除。
 *
 * @returns 是否复制成功（失败必须有可见反馈，见 `copyMessage`）。
 */
function canLegacyCopy(text: string): boolean {
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.select()
    area.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch (error) {
      logCaughtError(error, 'components/AiPanel/AiPanel.vue:3591')
    return false
  } finally {
    document.body.removeChild(area)
  }
}

function messageCopyText(message: ChatMessage): string {
  const sections = [
    deriveCopyText(message),
    ...message.parts
      .filter((part): part is ToolPart => part.kind === 'tool' && shouldShowToolPart(part))
      .map((part) => toolSummary(part))
  ]
  return sections.filter((section) => section.trim() !== '').join('\n\n')
}

function setCopyFeedback(id: string, ok: boolean, empty = false): void {
  copyState.value = { id, ok, empty }
  if (copyTimer !== null) {
    clearTimeout(copyTimer)
  }
  copyTimer = setTimeout(() => {
    copyState.value = null
    copyTimer = null
  }, 2000)
}

/** 普通复制只接收正文与可见工具摘要，不读取工具原始参数、结果与诊断详情。 */
async function copyText(id: string, text: string): Promise<void> {
  if (text.trim() === '') {
    setCopyFeedback(id, false, true)
    return
  }
  let ok: boolean
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      ok = true
    } catch (error) {
        logCaughtError(error, 'components/AiPanel/AiPanel.vue:3631')
      ok = canLegacyCopy(text)
    }
  } else {
    ok = canLegacyCopy(text)
  }
  setCopyFeedback(id, ok)
}

async function copyMessage(message: ChatMessage): Promise<void> {
  await copyText(message.id, messageCopyText(message))
}

/**
 * 新建对话（苞 A 项 1 + 2026-10-25 会话销毁口径）：确认后清屏 + 换新 sessionId +
 * 完整销毁旧会话（服务端 purge；前端按响应 clientCleanup 精确清镜像/草稿）。
 */
const newChatDialogRef = ref<HTMLDialogElement | null>(null)
const newChatWaiting = computed(() => ai.suspended
  || ai.pendingConfirm !== null
  || ai.activeForm !== null
  || ai.queuedForms.length > 0)
const newChatNeedsConfirmation = computed(() => ai.messages.length > 0 || ai.loading || newChatWaiting.value)
const newChatNote = computed(() => {
  if (newChatWaiting.value) {
    return t('ai.panel.newChatConfirm.pendingNote')
  }
  return ai.loading ? t('ai.panel.newChatConfirm.runningNote') : null
})

function newChat(): void {
  if (ai.restoringRecovery) {
    return
  }
  if (!newChatNeedsConfirmation.value) {
    void ai.resetSession()
    return
  }
  newChatDialogRef.value?.showModal()
}

function onNewChatDialogClose(): void {
  const dialog = newChatDialogRef.value
  if (!dialog) {
    return
  }
  const choice = dialog.returnValue
  dialog.returnValue = ''
  if (choice === 'confirm' && !ai.restoringRecovery) {
    void ai.resetSession()
  }
}

/**
 * R-166 口径③：该门卡是否**已提交**（在途 / 已提交待同步）—— 批准与拒绝双按钮据此一并
 * 禁用（"点击即禁用"的按钮侧；store 侧另有同 toolCallId 的提交守卫兜底）。
 */
function isGateSubmitting(toolCallId: string): boolean {
  return ai.gateSubmissionOf(toolCallId) !== null
}

/** R-166 口径③：该门卡是否处于 409 之后的「已提交，同步中…」瞬态。 */
function isGateSyncing(toolCallId: string): boolean {
  return ai.gateSubmissionOf(toolCallId)?.syncing === true
}

// ── 批4-D（用户 2026-10-23 裁决）：不可撤销确认卡的复选框 ──────────────────────

/** 该卡是否已勾选（普通确认卡无复选框，恒 false —— 也不参与任何禁用判据）。 */
function isGateAcked(toolCallId: string): boolean {
  return ai.gateAckOf(toolCallId)
}

/** 不可撤销卡还差这一步：`批准` 置灰 + 提示行出现的唯一判据。 */
function hasGateAckRequirement(part: ToolPart): boolean {
  return part.irreversible === true && !isGateAcked(part.toolCallId)
}

/**
 * 勾选文案：「确认{动作}，执行后无法恢复」（中文）/ 英文动名词组（`ai.hitl.action.<tool>`）。
 *
 * <p>R-09 fix round 1：`start_publish` 单独使用 `ai.hitl.publishIrreversible`，点名
 * “若本次为整体替换，范围内旧数据将被删除”——不读本地模式，避免陈旧副本造成误导。
 *
 * 动作名两段回落，保证"永远有字"：
 * ① `ai.hitl.action.<name>`（仅英文有实质值，中文与 `tool.<name>.title` 同字）；
 * ② 缺键 ⇒ `tool.<name>.title`（工具展示名，与门卡「操作」行同源）；再缺 ⇒ 业务化通用名称。
 */
function gateAckText(part: ToolPart): string {
  // R-09（fix round 1 / I-2）：start_publish 的后果与模式有关，但这里**不读本地模式**
  // （本地副本可能陈旧）——给出对两种模式都成立的 truthful 提示，由用户按本次实际执行判断。
  if (part.name === 'start_publish') {
    return t('ai.hitl.publishIrreversible')
  }
  const key = `ai.hitl.action.${part.name}`
  const action = t(key)
  return t('ai.hitl.irreversible', { action: action === key ? toolLabel(part.name) : action })
}

/** 复选框变更即写回 store（用户取消勾选同样要落库 —— 勾选不是单向开关）。 */
function onGateAckChange(toolCallId: string, event: Event): void {
  if (ai.restoringRecovery) {
    return
  }
  const checked = (event.target as HTMLInputElement | null)?.checked === true
  if (checked) {
    ai.ackGate(toolCallId)
    return
  }
  ai.clearGateAck(toolCallId)
}

/**
 * 提交一次人工裁决（放行 / 拒绝）。
 *
 * 2A 批（裁决 §3 缺陷④ / §9 K1）：H1 **无理由框** —— 拒因输入框整段删除，拒绝只提交
 * 固定口径 `用户拒绝执行`（后端 reason 字段仍为可选，契约不变；「修正走会话」）。
 *
 * 审批身份（调研 §3.9 ③）：`toolCallId` 由按钮所在卡片逐段传入 —— 多卡并存时不得回落到
 * 全局单槽投影；按钮防重由 `isGateSubmitting(part.toolCallId)` 按卡片承担。
 */
function keepCurrentCardExpanded(toolCallId: string): void {
  for (const message of ai.messages) {
    for (const part of message.parts) {
      if (part.kind === 'tool' && part.toolCallId === toolCallId) {
        ai.setBlockExpanded(`${message.id}:${toolCallId}`, true)
      }
    }
  }
}

async function decide(approved: boolean, toolCallId: string): Promise<void> {
  if (ai.restoringRecovery) {
    return
  }
  keepCurrentCardExpanded(toolCallId)
  const reason = approved ? undefined : '用户拒绝执行'
  await ai.confirm(approved, reason, toolCallId)
}

/** GF-B：表单提交（值已由 FormRenderer 按 schema 做过客户端复核）。 */
function onFormSubmit(values: Record<string, unknown>): void {
  if (ai.restoringRecovery) {
    return
  }
  const toolCallId = liveForm.value?.toolCallId
  if (toolCallId) {
    keepCurrentCardExpanded(toolCallId)
  }
  void ai.submitGenerativeForm(values)
}

/** GF-B：表单显式取消 → store 带 cancelled:true 回灌收敛。 */
function onFormCancel(): void {
  if (ai.restoringRecovery) {
    return
  }
  const toolCallId = liveForm.value?.toolCallId
  if (toolCallId) {
    keepCurrentCardExpanded(toolCallId)
  }
  void ai.cancelGenerativeForm()
}

/**
 * AR-07（批次4 集成返修）：该 toolCallId 在队列里的**位次**（1 起；0 = 不在队列）。
 *
 * 卡面只显示"第几张 / 共几张"（到达序），不自己维护第二份顺序 —— 顺序的唯一真值在
 * store 的 `queuedForms`（实时帧与刷新恢复共用同一队列）。
 */
function formQueuePosition(toolCallId: string): number {
  const index = ai.queuedForms.findIndex((entry) => entry.toolCallId === toolCallId)
  return index < 0 ? 0 : index + (ai.activeForm === null ? 1 : 2)
}

function formQueueTotal(): number {
  return ai.queuedForms.length + (ai.activeForm === null ? 0 : 1)
}

/**
 * AR-07：取消**排队里**的那一张（不动正在填的那张）。
 *
 * 走 store 的唯一出口 `cancelGenerativeForm(toolCallId)`（内部转 `cancelQueuedForm`）：
 * 出队清草稿 → 乐观终态 → 记决策时刻 → `cancelled:true` 回灌，失败原因落段可见。
 */
function onQueuedFormCancel(toolCallId: string): void {
  if (ai.restoringRecovery) {
    return
  }
  keepCurrentCardExpanded(toolCallId)
  void ai.cancelGenerativeForm(toolCallId)
}

/**
 * GF 优化批（G-B）：草稿镜像 —— 只写本机 sessionStorage（按 toolCallId），
 * 不进模型上下文、不写服务器；提交/取消/终态由 store 清理。
 */
function onFormDraft(values: Record<string, unknown>): void {
  if (ai.restoringRecovery) {
    return
  }
  ai.updateFormDraft(values)
}

// 批2（权威翻转）：滚动指纹基本为 **parts 口径**（§6.3 清单）—— messages.length + 末消息段数 +
// 末段的 kind/文本长度/段状态。内容增量都体现在"末段文本长度"上，工具帧的状态变化体现在"末段 status"。
// 收口修 F8（红队 O-2，注释级订正）：末项**仍读旧投影** `last.pendingCall.status` —— 挂起段的
// requiresAction 翻转/收敛只改派生投影、未必改末段自身 status，故保留投影读点兜底触发跟随；
// 原注释「无需读旧投影（content/toolRuns）即可触发跟随」与实现不符，按实现残留如实对齐。
//
// 2026-10-29 返修（导出过程整屏闪）：本指纹**不再直接滚**，只做两件事 —— 标脏用户长消息的 measure，
// 以及把"内容增量"作为尺寸变化的证据交给统一调度。状态位（tailStatus / pendingCall.status）留在
// 指纹里只为"再测一次"，量出来尺寸没变就不滚。
watch(
  () => {
    const last = ai.messages[ai.messages.length - 1]
    const tail = last ? last.parts[last.parts.length - 1] : undefined
    const tailText = tail && (tail.kind === 'text' || tail.kind === 'reasoning') ? tail.text.length : 0
    const tailStatus = tail && tail.kind === 'tool' ? tail.status : ''
    return `${ai.messages.length}|${last ? last.parts.length : 0}|${tail ? tail.kind : ''}|${tailText}|${tailStatus}|${last && last.pendingCall ? last.pendingCall.status : ''}`
  },
  async () => {
    // 内容增量证据：消息数 / 末消息段数 / 末段 kind / 末段正文变长 —— 文本变长必然改尺寸，
    // 只是测量要等 DOM 补丁落地。状态位（tool status / pendingCall.status）不产生这条证据。
    const shape = readStickShape()
    if (shape.count !== stickShape.count
      || shape.parts !== stickShape.parts
      || shape.kind !== stickShape.kind
      || shape.text > stickShape.text) {
      contentChanged = true
    }
    stickShape = shape
    // R-165 S4：用户消息长度也会随历史回读变化 —— 只把**末条用户消息**标脏，
    // 不再逐 token 扫全部用户正文；助手流式 token 不产生任何 measure。
    const last = ai.messages[ai.messages.length - 1]
    if (last?.role === 'user') {
      markUserMsgDirty(last.id)
    }
    // 批 D · D2①：watch 回调跑在**组件更新之前**（flush:'pre'），此时读到的 scrollHeight 还是
    // 旧内容的高度。统一调度在 DOM 补丁之后再测量、再钉底（跟随态下才钉，尊重用户上滑）。
    await nextTick()
    if (dirtyUserMsgIds.size > 0) {
      measureUserMsgOverflow()
    }
    scheduleStickToBottom()
  }
)

/**
 * 2026-10-29 返修（导出过程整屏闪）：stick-to-bottom 的唯一入口。
 *
 * <h2>业界口径</h2>
 * 主流实现（`use-stick-to-bottom` 一类库 / Slack·ChatGPT 的做法）= **尺寸驱动的跟随**：
 * <ol>
 * <li>"是否贴底"由用户的滚动事件维护（这里 = {@link followBottom}，滞回阈值见
 *     {@link FOLLOW_THRESHOLD_PX}）；</li>
 * <li>只有**内容 measured size 变化**（正文增量 / 卡片展开 / 确认门与表单插入）才把视图钉回底部；</li>
 * <li>用户上翻即释放，绝不在"状态变化"时把视线拽回去；</li>
 * <li>同一帧的多个信号（DOM 补丁后的复测 + ResizeObserver 回调）合并成一次测量、最多一次写入；</li>
 * <li>**只有跟随态**关掉浏览器原生锚定（模板上 `followBottom ? '[overflow-anchor:none]' : ''`）：
 *     跟随态我们自己补偿，两边同时生效就是各补一次；上翻后我们不再补偿，
 *     此时锚定是唯一的"保位置"手段，必须交回浏览器。</li>
 * </ol>
 * 旧实现把钉底直接挂在滚动指纹上（含尾部状态），且 watch 与 RO 各钉一次 ⇒ 一次内容变化最多写
 * 两次 scrollTop，叠加原生锚定就是"整屏闪 / 来回跳"。
 */
/** 内容层最近一次测得的 scrollHeight；`null` = 还没测到（jsdom 无布局时恒 0）。 */
let lastMeasuredScrollHeight: number | null = null
/** 滚动指纹里的内容形态（消息数 / 末消息段数 / 末段 kind / 末段正文长度）。 */
let stickShape = readStickShape()
/** 本帧的内容增量证据（文本变长 ⇒ 尺寸必然变，测量要等 DOM 补丁）。 */
let contentChanged = false
/** 本帧的内容层尺寸变化证据（RO 回调）。 */
let contentResized = false
let stickCheckScheduled = false

/** 当前内容形态（与滚动指纹同源口径）—— 基线必须等于**当前**现场，否则第一帧就会被误判成"变过"。 */
function readStickShape(): { count: number; parts: number; kind: string; text: number } {
  const last = ai.messages[ai.messages.length - 1]
  const tail = last ? last.parts[last.parts.length - 1] : undefined
  return {
    count: ai.messages.length,
    parts: last ? last.parts.length : 0,
    kind: tail ? tail.kind : '',
    text: tail && (tail.kind === 'text' || tail.kind === 'reasoning') ? tail.text.length : 0
  }
}

/** 重建基线（挂载 / 展开 / 收起）：不把"未知"当成 0 参与比较。 */
function resetStickBaseline(): void {
  const wrap = msgWrapRef.value
  lastMeasuredScrollHeight = wrap !== null && wrap.scrollHeight > 0 ? wrap.scrollHeight : null
  stickShape = readStickShape()
  contentChanged = false
  contentResized = false
}

function scheduleStickToBottom(): void {
  if (stickCheckScheduled) {
    return
  }
  stickCheckScheduled = true
  void nextTick(() => {
    stickCheckScheduled = false
    stickToBottomIfSized()
  })
}

function stickToBottomIfSized(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  const height = wrap.scrollHeight
  const grew = lastMeasuredScrollHeight !== null && height > lastMeasuredScrollHeight
  const sized = contentChanged || contentResized || grew
  lastMeasuredScrollHeight = height
  contentChanged = false
  contentResized = false
  // 状态变但尺寸没变 ⇒ 不滚；用户上翻（followBottom=false）⇒ 不拉回。
  if (!sized || !followBottom.value) {
    return
  }
  pinBottomTo(wrap, height)
}

/**
 * 批次 7A：消息视口观察器 / 横滚捕获监听随 `ai.expanded` 迁移。
 *
 * 面板用 `v-if/v-else` 切换收起条与完整面板，但组件实例不卸载 —— 旧实现只在 onMounted 绑定、
 * onBeforeUnmount 解绑，收起后观察器仍持有已脱离的 DOM，展开后 RO / 横滚联动 / mermaid 水合都
 * 指向旧树。现在收起即解绑并清空 `mermaidPending`，展开后按新 DOM 重新绑定，不等组件卸载。
 */
let msgResizeObserver: ResizeObserver | null = null
let msgScrollBound: HTMLElement | null = null
let lastMessageContentWidth: number | null = null
let userMsgWidthMeasureScheduled = false
let collapsedMessageView: {
  scrollTop: number
  followBottom: boolean
  anchorId: string | null
  anchorOffset: number
} | null = null

function captureMessageView(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    collapsedMessageView = null
    return
  }
  const wrapTop = wrap.getBoundingClientRect().top
  let anchorId: string | null = null
  let anchorOffset = 0
  for (const row of wrap.querySelectorAll<HTMLElement>('[data-msg-id]')) {
    const rect = row.getBoundingClientRect()
    if (rect.bottom <= wrapTop) {
      continue
    }
    anchorId = row.dataset.msgId ?? null
    anchorOffset = rect.top - wrapTop
    break
  }
  collapsedMessageView = {
    scrollTop: wrap.scrollTop,
    followBottom: followBottom.value,
    anchorId,
    anchorOffset
  }
}

function restoreMessageView(): void {
  const wrap = msgWrapRef.value
  const snapshot = collapsedMessageView
  if (!wrap) {
    return
  }
  collapsedMessageView = null
  if (snapshot?.followBottom ?? followBottom.value) {
    pinBottom()
    return
  }
  followBottom.value = false
  if (snapshot) {
    const anchor = Array.from(wrap.querySelectorAll<HTMLElement>('[data-msg-id]'))
      .find((row) => row.dataset.msgId === snapshot.anchorId)
    if (anchor) {
      const currentOffset = anchor.getBoundingClientRect().top - wrap.getBoundingClientRect().top
      wrap.scrollTop += currentOffset - snapshot.anchorOffset
    } else {
      wrap.scrollTop = snapshot.scrollTop
    }
  }
  updateBackToBottom(bottomDist(wrap))
}

function detachMessageViewObservers(): void {
  msgResizeObserver?.disconnect()
  msgResizeObserver = null
  lastMessageContentWidth = null
  if (msgScrollBound !== null) {
    msgScrollBound.removeEventListener('scroll', onTableScrollCapture, { capture: true })
    msgScrollBound = null
  }
  mermaidObserver?.disconnect()
  mermaidObserver = null
  mermaidPending.clear()
  mermaidInlineFit.clear()
  lastMeasuredScrollHeight = null
  contentChanged = false
  contentResized = false
}

function attachMessageViewObservers(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  // 展开/重建后按新 DOM 重绑：上一棵树留下的待渲染槽先摘掉（收起路径已整表清空，这里兜底）。
  pruneDetachedMermaidPending()
  resetStickBaseline()
  lastMessageContentWidth = (msgContentRef.value ?? wrap).getBoundingClientRect().width
  if (typeof ResizeObserver !== 'undefined') {
    msgResizeObserver = new ResizeObserver(onContentResized)
    msgResizeObserver.observe(msgContentRef.value ?? wrap)
  }
  wrap.addEventListener('scroll', onTableScrollCapture, { capture: true, passive: true })
  msgScrollBound = wrap
}

/**
 * 消息集变化（历史替换 / 消息移除 / 换会话）：按当前 id 集合差集清理本地登记 ——
 * 用户消息的测量表与折叠态、以及已脱离消息气泡的 mermaid 待渲染槽。
 * 只删"当前不存在"的 id，流式增量（消息仍在）不触发任何清理。
 */
watch(
  () => ai.messages.map((message) => message.id).join('\u0000'),
  () => {
    pruneUserMessageState()
    // 消息树在本帧 patch 之后才摘节点：等 DOM 落地再判"是否还在当前容器里"。
    void nextTick(() => pruneDetachedMermaidPending())
  }
)

watch(() => ai.expanded, async (expanded) => {
  if (!expanded) {
    captureMessageView()
    closeMmdViewer()
    detachMessageViewObservers()
    return
  }
  // v-if 分支在本次 patch 后才挂载：等 DOM 落地再绑新树。
  await nextTick()
  attachMessageViewObservers()
  measureUserMsgOverflow()
  restoreMessageView()
  resetStickBaseline()
})

/**
 * R-161（RC-1/2 修复）→ 批 D · D2② → 2026-10-29 返修：RO 只**上报**尺寸变化。
 *
 * 「只增高度、不进滚动指纹」的内容插入（确认门 / 生成式表单 / 工具卡 / 折叠展开）由
 * ResizeObserver 兜底：观测**内容层**（{@link msgContentRef}）—— 滚动容器
 * （{@link msgWrapRef}）的盒子由 flex 布局定死，内容长高只改它的 scrollHeight、不改它的 box，
 * 观测它对"内容增长"零触发。
 *
 * 回调本身不钉底：尺寸证据交给统一调度，与 DOM 补丁后的复测合并成一次写入；
 * 仅内容宽度变化另重适配 Mermaid、标脏用户消息并重测，高度变化不扫全表。
 */
function scheduleUserMsgWidthMeasure(): void {
  if (userMsgWidthMeasureScheduled) {
    return
  }
  userMsgWidthMeasureScheduled = true
  void nextTick(() => {
    userMsgWidthMeasureScheduled = false
    if (ai.expanded) {
      measureUserMsgOverflow()
    }
  })
}

function onContentResized(entries: ResizeObserverEntry[] = []): void {
  const width = head(entries)?.contentRect.width
    ?? (msgContentRef.value ?? msgWrapRef.value)?.getBoundingClientRect().width
    ?? 0
  if (lastMessageContentWidth !== null && width !== lastMessageContentWidth) {
    refitMermaidInline()
    for (const id of userBodyEls.keys()) {
      markUserMsgDirty(id)
    }
    if (dirtyUserMsgIds.size > 0) {
      scheduleUserMsgWidthMeasure()
    }
  }
  lastMessageContentWidth = width
  contentResized = true
  scheduleStickToBottom()
}

/** 身份不可用/会话未就绪时的统一重试（横幅按钮）：先重试目录，再发号（返修 I-2）。 */
function retryIdentity(): void {
  void ai.retryIdentitySession()
}

onMounted(async () => {
  // 所有权是所有会话读写的启动闸：复制页签先清继承现场，再装镜像/工作区订阅、发探测、
  // 读目录、重挂或恢复工具。无 Web Locks 时同步按恢复处理，不增加额外等待。
  const ownership = ai.ensureTabOwnership()
  if (ownership !== null) {
    await ownership
  }
  await ai.loadTenantDirectory()
  ai.startMirror()
  ai.startWorkspaceSync()
  void ai.probeHealth()
  if (!ai.messages.length) {
    await ai.loadHistory()
  }
  // 收起态挂载时没有消息树可观测；展开 watch 会在新 DOM 落地后补绑。
  if (ai.expanded) {
    attachMessageViewObservers()
  }
  // R-165 S4：首渲落地后跑一次用户长消息 measure（历史回读路径；jsdom 零高环境恒不溢出）。
  await nextTick()
  measureUserMsgOverflow()
  scrollBottom()
  // 首钉之后重建基线：首次测量不参与"尺寸变过"的判定（否则挂载即多补一次）。
  resetStickBaseline()
})

onBeforeUnmount(() => {
  formAttachmentAbort.value.abort()
  if (copyTimer !== null) {
    clearTimeout(copyTimer)
    copyTimer = null
  }
  for (const timer of fadeTimers.values()) {
    clearTimeout(timer)
  }
  fadeTimers.clear()
  // 批次 7A：统一从绑定点解绑（RO / 横滚捕获 / mermaid IO / pending），与收起路径同一函数。
  detachMessageViewObservers()
  for (const timer of copyFlashTimers.values()) {
    window.clearTimeout(timer)
  }
  copyFlashTimers.clear()
  if (smoothBackTimer !== null) {
    clearTimeout(smoothBackTimer)
    smoothBackTimer = null
  }
  // R-165 S5b：查看器若在打开态卸载 —— 摘掉 Esc/resize 监听 + 解 body 滚动锁 + 收复制绿勾计时器
  window.removeEventListener('keydown', onMmdViewerKeydown)
  window.removeEventListener('resize', resetMmdViewer)
  document.body.classList.remove('overflow-hidden')
  if (mmdViewerCopyTimer !== null) {
    clearTimeout(mmdViewerCopyTimer)
    mmdViewerCopyTimer = null
  }
  // 问题1 偏差修正批 aj14d ②：注销流式防抖 flush 回调（面板卸载后不再重入渲染）
  setStreamFlushHandler(null)
  // 批次 7A：组件销毁时释放两张 Markdown 缓存（收起/展开不销毁，缓存保留以复用）。
  clearHistoryMdCache()
  clearStreamMdCache()
  ai.stopWorkspaceSync()
})

/** 批次 7A 可观测面：只读调试视图（回归测试 / 后续 profile 用，不参与 UI 渲染）。 */
defineExpose({
  __batch7aDebug: {
    get mermaidPendingSize() {
      return mermaidPending.size
    },
    get scrollBound() {
      return msgScrollBound
    },
    get historyCacheKeys() {
      return Array.from(historyMdCache.keys())
    },
    get historyCacheSize() {
      return historyMdCache.size
    },
    get historyCacheBytes() {
      return historyMdCacheBytes
    },
    get historyCacheHits() {
      return historyMdHits
    },
    get historyCacheMisses() {
      return historyMdMisses
    },
    get streamCacheSize() {
      return streamMdCache.size
    },
    get streamCacheBytes() {
      return streamMdCacheBytes
    },
    get markdownHtmlCalls() {
      return markdownHtmlCalls
    },
    get toolDeriveMisses() {
      return { ...toolDeriveMisses }
    },
    get measurePasses() {
      return measurePasses.map((ids) => [...ids])
    }
  }
})
</script>

<!-- 本组件不持有 scoped 样式块：正文由渲染层注入工具类，呼吸与 chevron 使用模板类串，
     动画归 tailwind.config.js，平滑回底使用内置 scroll-smooth。 -->
