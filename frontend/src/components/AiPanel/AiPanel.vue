<template>
  <!-- 收起态：48px 竖排条（蓝本 AiPanel.vue:2-7） -->
  <div
    v-if="!ai.expanded"
    class="h-full flex flex-col items-center pt-4 gap-2 cursor-pointer text-[#57606a]"
    @click="ai.toggleExpand()"
  >
    <el-icon><ChatDotRound /></el-icon>
    <span class="writing-vertical text-[13px]">{{ t('ai.panel.assistant') }}</span>
  </div>

  <!-- R-165 S5a 项 9：focus-visible 聚焦环（demo v7 14b 口径）——根容器一条任意变体覆盖面板内
       **全部**原生 button（含 v-html 注入的代码块复制钮），无需逐钮挂类。 -->
  <div v-else class="ai-chat h-full flex flex-col [&_button:focus-visible]:outline-none [&_button:focus-visible]:ring-2 [&_button:focus-visible]:ring-[#2563eb]/40">
    <!-- 头部（蓝本 AiPanel.vue:10-19） -->
    <div class="flex items-center gap-2.5 px-3 py-2.5 border-0 border-b border-solid border-[#ebeef5]">
      <div class="w-[34px] h-[34px] rounded-lg bg-gradient-to-br from-[#409eff] to-[#7b61ff] text-white flex items-center justify-center font-bold">AI</div>
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-[#303133]">{{ t('ai.panel.implementer') }}</div>
        <div class="text-xs text-[#909399]" data-testid="current-model">{{ currentModelLabel }}</div>
      </div>
      <el-button
        size="small"
        text
        data-testid="new-chat"
        :title="t('ai.panel.newChat')"
        :disabled="!ai.messages.length"
        @click="newChat"
      >
        {{ t('ai.panel.newChat') }}
      </el-button>
      <el-button size="small" text :title="t('ai.panel.collapse')" @click="ai.toggleExpand()">
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <!-- 会话 / 工作区上下文条（本仓差异：会话恢复与契约联动状态可视化，蓝本无此行）。
         2026-10-29 ToC 呈现减负批：**首屏只留人话** —— 会话 id / 数据版本是排障用的机读标识，
         不进这一行，收进下方折叠的「诊断信息」（`panel-diagnostics`），需要时仍可取。 -->
    <div class="px-3 py-1.5 text-xs text-[#909399] border-0 border-b border-solid border-[#ebeef5] bg-aiPanel" data-testid="panel-context-bar">
      <!-- S6 批1（长内容审计 #13）：`truncate`（nowrap + ellipsis）在 flex 子项上不生效 —— 子项默认
           `min-width:auto` 不会缩到内容宽以下，长 `contextSummary` 会整行溢出后被外层 overflow-hidden
           硬裁（看不到省略号）。两处补 `min-w-0`，省略号才真落地。 -->
      <div class="flex items-center gap-1.5 flex-wrap" data-testid="panel-context-line">
        <span data-testid="health-chip" :class="chipClass(healthTagType)" :title="healthTitle">{{ healthLabel }}</span>
        <!-- Task 4：租户/用户切换（少量预置项，默认目录第一项）。每次实际切换都即时清屏并由后端
             新建会话（含切回旧身份）；Task 3 起同时清掉输入草稿、编辑锚点与那份已不可达的旧会话
             浏览器镜像（后端历史保留，不删），新旧身份不混用。 -->
        <span v-if="ai.tenantDirectory.length" class="flex items-center gap-1" data-testid="identity-switcher">
          <select
            data-testid="tenant-select"
            :aria-label="t('ai.panel.tenantAria')"
            :value="ai.identity?.tenantId ?? ''"
            class="h-[22px] rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[4px] text-[11.5px] text-[#57606a]"
            @change="onTenantChange"
          >
            <option v-for="entry in ai.tenantDirectory" :key="entry.tenantId" :value="entry.tenantId">
              {{ entry.tenantName ?? t('ai.panel.tenant', { id: entry.tenantId }) }}
            </option>
          </select>
          <select
            data-testid="user-select"
            :aria-label="t('ai.panel.userAria')"
            :value="ai.identity?.userId ?? ''"
            class="h-[22px] rounded-[6px] border border-solid border-[#e3e6ea] bg-white px-[4px] text-[11.5px] text-[#57606a]"
            @change="onUserChange"
          >
            <option v-for="user in currentTenantUsers" :key="user.userId" :value="user.userId">
              {{ user.displayName }}
            </option>
          </select>
        </span>
        <span class="min-w-0 truncate">{{ t('ai.panel.workspace', { summary: workspace.contextSummary }) }}</span>
        <el-button link size="small" @click="probe">{{ t('ai.panel.recheck') }}</el-button>
      </div>
      <details class="min-w-0" data-testid="panel-diagnostics">
        <summary class="cursor-pointer select-none text-[11px] text-[#8b949e]">{{ t('ai.panel.diagnostics') }}</summary>
        <div class="mt-0.5 break-words [overflow-wrap:anywhere] text-[11px] leading-[1.6] text-[#8b949e]">
          <span data-testid="diagnostics-session">{{ t('ai.panel.session', { id: shortSessionId }) }}</span>
          <span> · </span>
          <span data-testid="diagnostics-data-version">{{ t('ai.panel.dataVersion', { version: workspace.dataVersion }) }}</span>
        </div>
        <div v-if="ai.directoryError" class="text-[11px] text-[#8b949e]" data-testid="directory-error">
          {{ t('ai.notice.directoryFailed') }}
        </div>
      </details>
      <div v-if="ai.contextSync" class="mt-0.5 min-w-0 truncate" data-testid="context-synced">
        {{ t('ai.panel.contextSynced', { summary: contextSyncText }) }}
      </div>
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

    <div v-if="ai.streamInterrupted" :class="alertBox('error')" data-testid="alert-interrupted">
      <span :class="alertIcon('error')" aria-hidden="true">!</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ t('ai.panel.interrupted') }}</b>
        <span class="text-[11.8px] text-[#57606a]">{{ t('ai.panel.noDoneFrame') }}</span>
      </div>
    </div>

    <!-- 批次2（N3）：历史/快照恢复失败不再静默 —— 错误条 + 重试出口。
         只读 store 适配器（historyError/currentError，见 recoveryBridge 注释），不在此处自造错误态。
         2026-10-29 返修（同一事实只留一个提示面）：`sealRecovery` 那一类失败在 store 里同时写
         `historyError` 与 `recoveryGap`，旧版并排出两条横幅、两个按钮最终都落 `retryLoadHistory`。
         现在错误条就地合并「内容可能不完整」这条中性事实，缺口专属条只在**没有**恢复错误时出现。
         但**不是所有恢复错误都伴随缺口**（`restoreSuspendedSnapshot` 的 preparingRun 分支只写
         `currentError`）⇒ 那行事实只在 `ai.recoveryGap !== null` 时渲染，否则就是凭空的不完整声明。 -->
    <div v-if="recoveryErrorVisible" :class="alertBox('error')" data-testid="alert-history-error">
      <span :class="alertIcon('error')" aria-hidden="true">!</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ recoveryErrorLabel() }}</b>
        <span class="block text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ recoveryErrorText() }}</span>
        <!-- 中性事实（不是第二条错误）：真登记过缺口才说"可能不完整"，见上方注释 -->
        <span
          v-if="ai.recoveryGap !== null"
          class="mt-[2px] block text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]"
        >{{ t('ai.panel.recoveryGap') }}</span>
      </div>
      <button
        type="button"
        data-testid="retry-history"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        @click="retryRecoveryLoad()"
      >
        {{ t('ai.panel.retryRecovery') }}
      </button>
    </div>

    <!-- 批次2：**不可完整恢复**（recoveryGap）——中性事实，不是轮失败、不是错误卡。
         只有"本地镜像/差量回放不能保证完整"这一条信息 + 重新载入出口；不显示内部区间/原因码。
         真正的缺帧（archive-window）没有恢复错误可归并 ⇒ 本条照常单独出现。 -->
    <div v-if="recoveryGapBarVisible" :class="alertBox('gray')" data-testid="alert-recovery-gap">
      <span :class="alertIcon('gray')" aria-hidden="true">i</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">{{ t('ai.panel.recoveryGap') }}</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ t('ai.panel.recoveryGapHint') }}</span>
      </div>
      <button
        type="button"
        data-testid="retry-recovery-gap"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        @click="reloadTurnState()"
      >
        {{ t('ai.panel.reloadTurn') }}
      </button>
    </div>

    <!-- 单行通知槽——普通通知 = demo v7 `.notice`（虚线灰底 + — 描边圆），取消 / 重试 / 幂等等通知的承载位；
         R-165 S3a（R11 指挥官裁决）：error 级通知改走 v7 `.enotice` 红族（浅红底 + 浅红描边 + 圆角 9 + ! 圆标）。
         2026-10-29 ToC 呈现减负批（同一事实只留一个提示面）：`noticeBarVisible` —— 上方已有**专属 Alert**
         承担同一件事（挂起池饱和 / 断流）时，本槽不再叠一遍同义文案。 -->
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
        @click="returnToActiveRun()"
      >
        {{ t('ai.panel.returnToActiveRun') }}
      </button>
    </div>

    <!-- 工作区动作回执（AI→业务方向）。2026-10-29 ToC 呈现减负批：上方「工作区已同步」已经在报
         同一件事时（`ai.contextSync` 有值），本回执位不再叠一条同义提示 —— 同一事实只留一个提示面。 -->
    <div v-if="uiEventReceiptVisible" :class="alertBox('gray')" data-testid="alert-ui-event">
      <span :class="alertIcon('gray')" aria-hidden="true">i</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328] break-words [overflow-wrap:anywhere]">{{ t('ai.panel.uiEvent', { message: ai.lastUiEventResult?.message ?? '' }) }}</b>
      </div>
    </div>

    <!-- 批次3 阶段条（2026-10-29 ToC 呈现减负批恢复）：压缩 / 准备中**必须有一个说明位** ——
         挂载准备期（preparingRun）与压缩心跳都落在这里；组件内部自带秒表（只有这一小块每秒重渲），
         文案走 locale，runId 一律不上屏。压缩完成不另起提示（减法：`loading-breath` 已表示还在产出）。 -->
    <CompactionStatus
      v-if="stageBar !== null"
      :mode="stageBar.mode"
      :started-at-ms="stageBar.startedAtMs"
      :elapsed-ms="stageBar.elapsedMs"
      @retry="ai.retryPreparing()"
    />

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
        @click="ai.stop()"
      >
        {{ t('ai.panel.stop') }}
      </button>
    </div>
    <!-- 读屏播报（只读动词也播报；视觉零占位）：与操作条同一句，前缀只在 `uiOperating` 出现一次 -->
    <div class="sr-only" aria-live="polite" data-testid="ai-live-region">{{ uiOperatingText }}</div>

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
      <!-- 空态（R-165 S2：el-empty + el-icon 退场 → 自绘 demo v7 `.mini`：标题 600 + 副文 + 胶囊建议词行） -->
      <div
        v-else-if="!ai.messages.length"
        class="m-3 rounded-[10px] border border-solid border-[#e3e6ea] bg-white px-[14px] py-[16px]"
        data-testid="empty-state"
      >
        <div class="[font-weight:650] text-[13.2px] mb-[3px]">{{ t('ai.panel.greeting') }}</div>
        <div class="text-[12.4px] leading-[1.8] text-[#57606a]">
          {{ t('ai.panel.welcome1') }}<br />
          {{ t('ai.panel.welcome2') }}<br />{{ t('ai.panel.welcome3') }}
        </div>
        <!-- 批次6A / N12 / AR-01（2026-10-30 删表批口径）：空态如实写清存储载体 —— 本页签浏览器
             镜像（刷新可恢复）+ 服务端 24h 滑动记忆窗口；对话正文不落库（`ai_chat_message` 已由
             迁移 V10 删除、完整历史端点 /history/{sid}/messages 已移除）⇒ 没有长期归档。
             旧文案「关闭页签即结束」与实现相反，已删除。
             三句 locale（`ai.panel.storage.mirror` / `.run` / `.history`）均已按本口径改好，
             `frontend/tests/ai-panel-parts-render.spec.ts` 的 N12 用例按新文案做正/反断言
             （须含「没有长期归档」、不得出现「数据库」）—— 勿再按旧文案新增断言。 -->
        <div class="mt-[6px] text-[11.8px] leading-[1.7] text-[#8b949e]" data-testid="storage-notice">
          <div>{{ t('ai.panel.storage.mirror') }}</div>
          <div>{{ t('ai.panel.storage.run') }}</div>
          <div>{{ t('ai.panel.storage.history') }}</div>
        </div>
        <!-- 批 E · E2（用户裁决 2026-10-22）：空态快捷 chips 点击**直接发送**（不再回填输入框）。
             写操作靠既有 HITL 确认卡兜底；文案与"每轮建议"共用一份词表（ai.suggestions.*，双语）。 -->
        <div v-if="chipsAvailable && visibleEmptyStateChips.length" class="mt-[11px] flex flex-wrap gap-[6px]">
          <button
            v-for="chip in visibleEmptyStateChips"
            :key="chip"
            type="button"
            :class="SUGGESTION_CHIP"
            data-testid="suggestion-chip"
            :aria-label="t('ai.suggestions.sendAria', { text: t(`ai.suggestions.${chip}`) })"
            @click="sendPreset(t(`ai.suggestions.${chip}`))"
          >
            {{ t(`ai.suggestions.${chip}`) }}
          </button>
        </div>
      </div>

      <!-- R-165 S3a（R6 指挥官裁决）：消息行原 `mb-3.5`(=14px) 与末行元信息行 `mb-[18px]` 叠加成实测 32px，
           与 v7 turn 间距（`.afoot` 单值 18px）不符 ⇒ 撤消息行下外边距，间距回归元信息行单值 18px。
           （无元信息行的消息——仅工具卡无正文——仍由卡片自身下外边距兜底，不会贴死；
           R-165 S5a 项 1：卡片下外边距体系 = 工具卡 mb-2 / 门卡·GF mb-2.5 / 回执·思考·流程图 mb-3.5。） -->
      <div
        v-for="(m, mi) in ai.messages"
        :key="m.id"
        v-memo="messageMemo(m, mi)"
        class="group flex"
        :class="m.role === 'user' ? 'flex-row-reverse' : ''"
      >
        <!-- 批次2 / AR-01 第 5 条：历史 `synthetic=true` 的合成/系统提示（压缩摘要、步数收尾）
             转成**系统级「已压缩」提示** —— 不作为用户气泡、不渲染摘要内部键，也不出任何消息操作。 -->
        <div
          v-if="syntheticOf(m)"
          class="w-full border border-dashed border-solid border-[#e3e6ea] bg-[#f6f7f9] rounded-[10px] px-[10px] py-[6px] text-[11.6px] leading-[1.6] text-[#57606a]"
          :data-testid="'synthetic-notice-' + m.id"
        >
          <b class="[font-weight:650] text-[#1f2328]">{{ t('ai.panel.compacted') }}</b>
          <span class="ml-1">{{ t('ai.panel.compactedHint') }}</span>
        </div>
        <!-- R-163 T3：双侧去头像（demo v6 场景 2）——消息行 = 内容 + 元信息行，无头像列 -->
        <!-- 内容列：正文/卡片 + 元信息行（R-157 #1：操作行挪气泡外、卡片下沿独立行） -->
        <div v-else class="min-w-0 flex-1 flex flex-col" :class="m.role === 'user' ? 'items-end' : 'items-start'">
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
          <template v-for="block in blocksOf(m)" :key="block.id">
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
                m.role === 'user' ? MD_BODY_USER_CLASS : MD_BODY_AI_CLASS,
                m.role === 'user' && isUserMsgCollapsed(m.id) ? USER_MSG_COLLAPSE_CLASS : ''
              ]"
              v-html="markdownHtml(m, block)"
              @click="onMdBodyClick"
            ></div>
            <span
              v-if="block.kind === 'text' && textPartOf(block)?.truncated"
              class="inline-block ml-1 h-[7px] w-[7px] rounded-full bg-[#8b949e] align-middle"
              role="img"
              :aria-label="t('ai.panel.truncated')"
              :title="textTruncationTitle(block)"
              data-testid="text-truncated-badge"
            ></span>

            <!-- S6 批1（裁决 2026-10-15 第 3 条）：HITL **独立回执块退役** —— 原 `block.kind === 'receipt'`
                 分支（徽标行 + 「查看详情 ⇄ 收起详情」钮 + 单层 mono JSON 盒）整段删除；
                 已决确认门 / GF 段改由其**自己的工具卡**原地切只读态（见下方 isReadOnlyCard 分支）。 -->

            <template v-else>
              <!-- R-154 #13+14（用户直裁「彻底拆除」）：组壳退役 —— 每段恒一张卡。
                   卡头折叠单行 = 左类型裸 SVG + 标题/摘要 + 右状态标签 + chevron（可折叠时）；
                   点击卡头展开/收起 args/result/错误位（v-show：DOM 恒在，testid/可见性稳定）；
                   requiresAction 待决 HITL/GF 强制展开且无 chevron（卡头禁点，resolveBlockExpanded 兜底）。 -->
              <template v-for="part in block.parts" :key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg">
                <!-- 思考链（A6'）：受控折叠块，口径不变（默认折叠；展开态存 store，DOM 重建不丢）
                     R-165 S2：思考卡按 demo v7 `.reason` 结构重写 —— 卡容器 bg-#f6f7f9 / 1px #e3e6ea /
                     圆角 10；卡头只保留思考中/思考过程与计时，折叠仍由 chevron 承担；
                     R-166 口径⑦：图标字形由 demo 的 U+2726（四角星）改 U+22C6 —— demo 实字落在
                     U+2600-27BF（本批 emoji 复扫的判废区间）内，改取同形的数学星号保住视觉且扫描 0 命中。
                     展开体 = 左竖线（border-l-2 #e3e6ea）+ pl-10px/pr-11px/pb-10px 的 12.6px#57606a 正文。 -->
                <div
                  v-if="part.kind === 'reasoning' && part.text"
                  v-memo="partMemo(part, block)"
                  class="border border-solid border-[#e3e6ea] rounded-[10px] bg-[#f6f7f9] overflow-hidden mb-3.5"
                >
                  <!-- 批次6A：卡头独立成组件（局部秒表；aria-expanded/controls 与展开体同源）。
                       多段一卡一段 —— 每个思考段各有自己的卡头与计时，不合并、不互相影响。 -->
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
                    class="pl-[10px] pr-[11px] pb-[10px] border-0 border-l-2 border-solid border-[#e3e6ea]"
                    :data-testid="'reasoning-body-' + block.id"
                  >
                    <!-- S6 批1（demo v7.9 裁决点 21 · 2026-10-16 用户直裁通过）：思考过程展开体改
                         **纯限高内滚**（2A 批裁决 §14.4 字段 / 文本块族：双约束 `min(280px,35vh)`）——
                         CollapsibleText 的「渐隐 + 显示更多」钮退役；卡头折叠机制（.is-open）逐字不动。
                         短思考（≤ 视口收紧后的上限）零副作用，不出现滚动条。 -->
                    <div :class="['max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-words text-[12.6px] leading-[1.7] text-[#57606a]', SCROLLBAR_CLASS]" :data-testid="'reasoning-scroll-' + block.id">{{ part.text }}</div>
                  </div>
                </div>
                <template v-else-if="part.kind === 'tool'">
                  <!-- 卡片三类型统一卡头（tool / hitl / form 同一结构）：
                       左 = 固定类型裸 SVG（扳手 / 圆圈问号 / 清单，`#57606a` 中性色，整生命周期不变）；
                       中 = 标题 + 摘要（中性色，不随状态变色）；右 = 状态文字标签（唯一状态色承载）；
                       末尾 = chevron（可折叠时；待决 HITL/GF 强制展开且无 chevron）。
                       已决 HITL / GF 的卡体仍为原样 UI 只读态（2A 批 K1 / F9 / M1：门卡字段表原样、
                       GF 同一张表单 readonly、零操作按钮、卡面不渲染 decidedAt）；已决多态回合结束后
                       一律默认折叠，由 turnStillOpen 承担「回合未结束仍展开」。 -->
                  <div
                    v-memo="partMemo(part, block)"
                    :class="cardShellClass(part)"
                    :data-testid="cardTestId(part)"
                  >
                    <button
                      type="button"
                      :class="cardHeadClass(part)"
                      :aria-expanded="isBlockExpanded(block)"
                      :data-testid="cardToggleTestId(part, block)"
                      :disabled="cardHeadDisabled(part)"
                      @click="onCardHeadClick(block, part)"
                    >
                      <span
                        class="flex-none flex items-center justify-center w-[24px] text-[#57606a]"
                        :data-icon-type="cardTypeOf(part)"
                        :data-testid="'card-icon-' + part.toolCallId"
                        aria-hidden="true"
                      >
                        <svg v-if="cardTypeOf(part) === 'tool'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#57606a" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
                        <svg v-else-if="cardTypeOf(part) === 'hitl'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#57606a" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                        <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#57606a" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><rect x="8" y="2" width="8" height="4" rx="1"/><path d="M8 11h8"/><path d="M8 15h5"/></svg>
                      </span>
                      <span class="flex min-w-0 flex-1 items-center gap-[6px]">
                        <span
                          :class="['min-w-0 overflow-hidden text-ellipsis whitespace-nowrap text-[12px] leading-[1.7] [font-weight:650] text-[#1f2328]', cardTypeOf(part) === 'tool' ? 'font-mono' : '']"
                          :data-testid="'card-title-' + part.toolCallId"
                        >{{ cardTitle(part) }}</span>
                        <span
                          class="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap text-[11.5px] leading-[1.6] text-[#57606a]"
                          :data-testid="cardSummaryTestId(part)"
                        >{{ cardSummary(part) }}</span>
                      </span>
                      <span
                        :class="cardStatusClass(part)"
                        :data-testid="cardStatusTestId(part)"
                        :data-state="cardStatusOf(part).key"
                        :title="cardStatusLabel(part)"
                      >{{ cardStatusLabel(part) }}</span>
                      <span
                        v-if="cardChevronShown(part)"
                        :class="[TCHEV_CLASS, 'ml-auto leading-[1.7]', isBlockExpanded(block) ? 'is-open' : '']"
                      >›</span>
                    </button>
                    <!-- 批次3 补齐 / AR-10：本轮运行态（Redis 24h 滑动保留期）只可见化。
                         剩余时间来自后端 PTTL 的权威推导（store.runExpiry），到期转「已失效」
                         并给可达出口（刷新状态 / 新建会话）—— **不得**让卡静默消失。
                         计时文本 `aria-live="off"`：读屏不逐秒播报，只在到期这一次状态变化时由
                         状态词本身被读到（该行不在任何 polite/assertive 容器内）。 -->
                    <div
                      v-if="runExpiryView && part.requiresAction"
                      class="mx-[11px] mt-[9px] flex flex-wrap items-center gap-x-[8px] gap-y-[4px] text-[11.6px] leading-[1.55] text-[#57606a]"
                      data-testid="run-expiry-hint"
                    >
                      <span v-if="!runExpiryView.expired" data-testid="run-expiry-text" aria-live="off">{{ t(runExpiryView.key, runExpiryView.params) }}</span>
                      <template v-else>
                        <span data-testid="run-expiry-expired" class="flex-none text-[#b45309]" aria-live="off">{{ t('ai.panel.runExpiry.expired') }}</span>
                        <span class="min-w-0 flex-1 break-words" data-testid="run-expiry-expired-hint">{{ t('ai.panel.runExpiry.expiredHint') }}</span>
                        <button
                          type="button"
                          class="flex-none whitespace-nowrap border-0 bg-transparent p-0 text-[11.6px] text-[#2563eb]"
                          data-testid="run-expiry-reload"
                          @click="refreshExpiredRunState"
                        ><span class="border-b border-[#e3e6ea]">{{ t('ai.panel.runExpiry.reload') }}</span></button>
                        <button
                          type="button"
                          class="flex-none whitespace-nowrap border-0 bg-transparent p-0 text-[11.6px] text-[#2563eb]"
                          data-testid="run-expiry-new-chat"
                          @click="startNewChatAfterExpiry"
                        ><span class="border-b border-[#e3e6ea]">{{ t('ai.panel.runExpiry.newChat') }}</span></button>
                      </template>
                    </div>
                    <!-- 只读体判据 = 已决只读态 ∪ GF「已提交、结局未到」（readOnlyLike）：
                         后者让提交后的这段时间**同一张表单原样锁定**，不闪普通工具卡 / 不闪 schema。 -->
                    <div v-if="readOnlyLike(part)" v-show="isBlockExpanded(block)" class="pb-[11px]" :data-testid="'ro-body-' + part.toolCallId">
                      <!-- 门卡：参数区字段表原样（与待决态同一份 gateRows：操作 + args 全键 + 敏感掩码）；
                           字段区限高 = 字段族双约束 min(280px,35vh)（裁决 §14.4）。
                           2026-10-20 卡面一致性裁决：**删重复标题行**（标题已在统一卡头业务化），
                           卡体直接字段表（demo 场景二/三 1:1）。 -->
                      <template v-if="part.name !== GENERATIVE_FORM_TOOL">
                        <div
                          :class="['mx-[11px] mt-[9px] bg-white border border-solid border-[#eef0f3] rounded-[8px] px-[10px] py-[2px] max-h-[min(280px,35vh)] overflow-y-auto', SCROLLBAR_CLASS]"
                          :data-testid="'ro-rows-' + part.toolCallId"
                        >
                          <div
                            v-for="row in gateRows(part)"
                            :key="row.key"
                            class="flex gap-[10px] py-[7px] border-0 border-b border-solid border-[#eef0f3] text-[13px] leading-[1.5] last:border-b-0"
                          >
                            <!-- 键列口径同待决门卡（自适应内容宽 + 上限 40% + truncate + title 看全） -->
                            <span class="flex-[0_1_auto] min-w-0 max-w-[40%] truncate text-[#8b949e] text-[12.4px]" :title="row.key">{{ row.key }}</span>
                            <span class="flex-1 min-w-0 break-words">{{ row.value }}</span>
                          </div>
                        </div>
                      </template>
                      <!-- GF：同一张表单原样锁定（readonly 模式复用 FormRenderer，零操作按钮）。
                           卡体留白与门卡只读态同构：mx-[11px] mt-[9px] 包裹（demo
                           card-consistency-fix.html 场景五 data-form-fields 11px/9px 口径）。 -->
                      <template v-else>
                        <div
                          v-if="cardFormSpec(part)"
                          class="mx-[11px] mt-[9px]"
                          :data-testid="'ro-form-' + part.toolCallId"
                        >
                          <FormRenderer
                            :schema="(cardFormSpec(part) as FormSpec)"
                            readonly
                            :submitting="false"
                            :error="null"
                            :initial-values="readOnlyFormValues(part)"
                          />
                        </div>
                        <div v-else class="px-[11px] pt-[10px] text-[12.6px] text-[#57606a]">{{ t('ai.panel.formUnavailable') }}</div>
                      </template>
                      <!-- 失败 / 拒绝 / 取消收尾的原因单独一行常驻卡面（K3：执行失败原因常驻；F9 同源）。
                           批次6A（N8/N9）：诊断分层命中时本行只放**人话**；机器原文（runId/toolCallId、
                           内部码、配置键、回灌端点）收进下方「诊断详情」，不再当用户第一眼读到的原因。 -->
                      <div
                        v-if="readOnlyReasonText(part)"
                        class="mx-[11px] mt-[9px] rounded-[8px] border border-solid px-[10px] py-[7px] text-[11.9px] leading-[1.6] whitespace-pre-wrap break-words"
                        :class="diagnosticOf(part) === 'staleGate'
                          ? 'border-[#e3e6ea] bg-[#f6f7f9] text-[#57606a]'
                          : 'border-[#f0d5d0] bg-[#fdf1ef] text-[#c0392b]'"
                        :data-testid="'ro-reason-' + part.toolCallId"
                        :data-diagnostic="diagnosticOf(part) ?? 'none'"
                      >{{ readOnlyReasonText(part) }}</div>
                      <details
                        v-if="diagnosticTextOf(part)"
                        class="mx-[11px] mt-[6px]"
                        :data-testid="'ro-diagnostics-' + part.toolCallId"
                      >
                        <summary class="cursor-pointer select-none text-[11.2px] text-[#8b949e]">{{ t('ai.card.error.diagnostic.label') }}</summary>
                        <div
                          :class="['mt-[3px] max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-words text-[11.2px] leading-[1.55] text-[#57606a]', SCROLLBAR_CLASS]"
                          :data-testid="'ro-diagnostics-text-' + part.toolCallId"
                        >{{ diagnosticTextOf(part) }}</div>
                      </details>
                    </div>
                    <!-- 卡体分支保留（统一卡头之下）：已决只读态 → ro-body；普通工具 → 折叠展开体。
                         展开体渲染后 0 行 ⇒ 不渲展开体 / 不渲 chevron / 卡头 disabled（v7.10 ajv11）。 -->
                    <template v-else-if="cardBodyShown(part)">
                      <!-- 待动作卡（确认门 / GF）的卡体留白由子块自带（门卡 pb-[11px] + rows/操作行
                           mx-[11px]，demo 场景一/四 1:1）；通用工具展开体才是 px-2 pb-2 的内缩盒。 -->
                      <div v-show="isBlockExpanded(block)" :class="part.requiresAction ? 'flex flex-col' : 'px-2 pb-2 pt-0 flex flex-col gap-1.5'" :data-testid="'block-body-' + block.id">
                      <!-- S6 批1（裁决 2026-10-16 第 2 条：面板 JSON 展示全面清零 / v7.9 场景 16 口径）：
                           工具卡**参数**由 mono JSON 盒改**折叠区 label-value 字段表**（解析 args JSON → 键值行，
                           值可读化：布尔 是/否、数组 顿号、文件路径 文件名）。
                           无参（`'{}'` / 空串 / 非 JSON 脏数据）⇒ 整个参数区不渲染（不渲空盒、不露 raw 文本）。
                           requiresAction 待动作卡不渲参数区 —— 门卡字段表 / GF 表单本身就是待填内容（消双份）。 -->
                      <!-- 2A 批（裁决 §14.4 字段族）：参数字段表限高改双约束 `min(280px,35vh)`。 -->
                      <div v-if="!part.requiresAction && paramRows(part).length">
                        <div class="block text-[11px] tracking-[.5px] uppercase text-[#8b949e] mb-[4px]">{{ t('ai.panel.args') }}</div>
                        <div :class="['bg-[#f6f7f9] border border-solid border-[#eef0f3] rounded-[8px] px-[10px] py-[2px] max-h-[min(280px,35vh)] overflow-y-auto', SCROLLBAR_CLASS]" :data-testid="'args-rows-' + part.toolCallId">
                          <div
                            v-for="row in paramRows(part)"
                            :key="row.key"
                            class="flex gap-[10px] py-[6px] border-0 border-b border-solid border-[#eef0f3] text-[12.6px] leading-[1.5] last:border-b-0"
                          >
                            <span class="flex-[0_0_72px] break-words text-[#8b949e] text-[12px]">{{ row.key }}</span>
                            <span class="flex-1 min-w-0 break-words">{{ row.value }}</span>
                          </div>
                        </div>
                      </div>
                      <!-- S6 批2 迁移步④（设计稿 §7.1-④）：视图可渲时「输出」区整块让位 —— 同一份真值
                           不在卡面报两次（原文仍是 part.result，模型侧 / 复制路径不受影响）；
                           视图渲不出或段上没有视图 ⇒ 本盒照旧（回退路径不删）。 -->
                      <!-- 批次6A（N9）：skipped 的原文在旧帧/旧镜像里仍是模型向（预算、步数上限、配置键）
                           —— 不占「输出」位，收进下方「诊断详情」；卡面只留一句人话。 -->
                      <div
                        v-if="diagnosticOf(part) === 'skipped'"
                        class="flex items-start gap-[7px] px-[10px] py-[7px] text-[12.4px] text-[#57606a] bg-[#f6f7f9] border border-solid border-[#e3e6ea] rounded-[9px]"
                        data-testid="tool-skipped-human"
                      >
                        <span class="mt-px shrink-0 flex-[0_0_14px] w-[14px] h-[14px] flex items-center justify-center rounded-[50%] border-[1.2px] border-[#8b949e] text-[#8b949e] text-[9px]" aria-hidden="true">—</span>
                        <span class="flex-1 min-w-0 break-words">{{ t('ai.panel.skippedHuman') }}</span>
                      </div>
                      <div v-if="!isBlankText(part.result) && !outputByView(part) && !outputSupersededByDiagnostics(part)">
                        <div class="block text-[11px] tracking-[.5px] uppercase text-[#8b949e] mb-[4px]">{{ t('ai.panel.output') }}</div>
                        <!-- R-165 S5a 项 4：工具卡输出 = **纯限高内滚**，原 CollapsibleText 的渐隐 +
                             「显示更多」钮退役（无展开钮）；截断徽标保留。
                             滚动条同走 6px 圆角半透明任意变体类（项 8）。
                             S6 批1 长内容审计清零项：补 break-all + overflow-wrap:anywhere（长串不越界）。
                             2A 批（裁决 §14.4 代码 / 详情族）：限高改双约束 `min(280px,35vh)`。 -->
                        <div data-testid="tool-output-text" :class="['text-[12.1px] leading-[1.6] font-mono text-[#606266] bg-[#f6f7f9] border border-solid border-[#eef0f3] rounded-[8px] px-[9px] py-[8px] max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-all [overflow-wrap:anywhere]', SCROLLBAR_CLASS]">
                          {{ toolResultText(part) }}<span
                            v-if="part.truncated"
                            class="inline-block ml-1 px-1 rounded text-[10px] leading-4 bg-[#8b949e] text-white align-middle"
                            :title="truncationTitle(part)"
                            data-testid="truncated-badge"
                          >{{ t('ai.panel.truncated') }}</span>
                        </div>
                      </div>
                      <!-- AI 可视可操作专项（§3.8 / 裁 6）：操作摘要行的「撤销」按钮。
                           只有用户能撤销（**不提供** ui_undo 工具）；深度 1 —— 撤销后按钮消失。 -->
                      <div
                        v-if="part.toolCallId === ai.uiUndoToolCallId && ai.uiUndoEntry"
                        class="flex items-center gap-[8px]"
                        data-testid="ui-undo-row"
                      >
                        <span class="text-[12.2px] text-[#57606a]">{{ t('ai.panel.undoHint') }}</span>
                        <button
                          type="button"
                          class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
                          data-testid="ui-undo-button"
                          @click="onUiUndo"
                        >
                          {{ t('ai.panel.undo') }}
                        </button>
                      </div>

                      <!-- R-158 #6：卡内错误位（自绘圆环 ! + 红底 + 文案）——错误就地呈现，不再全堆顶部 Alert；
                           R-165 S1：el-icon 退场，圆环 ! 照搬 demo v7 行内错误通知形制。
                           S6 批1（v7.10 N8）：发布冲突明细收敛 —— 红底行只留一句人话摘要（reason 首行），
                           两种形态不同屏重复报同一份明细。
                           S6 批2：明细表的**来源**改为 `part.resultView`（通用渲染器），红底行仍只取首行；
                           无视图的旧段才回退到反解表（回退路径按设计稿 §7.1-⑤ 最后删）。
                           2A 批（裁决 §14.4 回执 / 文本族）：原因行限高改 `min(280px,35vh)`。 -->
                      <div v-if="faceReasonText(part)" class="flex items-start gap-[7px] px-[10px] py-[7px] text-[12.4px] text-[#c0392b] bg-[#fdf1ef] border border-solid border-[#f0d5d0] rounded-[9px]" data-testid="tool-error" :data-diagnostic="diagnosticOf(part) ?? 'none'">
                        <span class="mt-px shrink-0 flex-[0_0_14px] w-[14px] h-[14px] flex items-center justify-center rounded-[50%] border-[1.3px] border-[#c0392b] text-[#c0392b] text-[9px] font-bold" aria-hidden="true">!</span>
                        <span class="flex-1 min-w-0 whitespace-pre-wrap break-words max-h-[min(280px,35vh)] overflow-y-auto">{{ t('ai.panel.reasonPrefix', { text: faceReasonText(part) }) }}</span>
                      </div>
                      <!-- 批次6A（N8/N9）：机器原文只在「诊断详情」里出现（折叠、限高内滚）；
                           用户向与诊断分层，卡面首屏不再出现内部码 / 回灌路径 / 轮次标识。 -->
                      <details v-if="diagnosticTextOf(part)" class="mt-1" :data-testid="'card-diagnostics-' + part.toolCallId">
                        <summary class="cursor-pointer select-none text-[11.2px] text-[#8b949e]">{{ t('ai.card.error.diagnostic.label') }}</summary>
                        <div
                          :class="['mt-[3px] max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-all text-[11.2px] leading-[1.55] text-[#57606a]', SCROLLBAR_CLASS]"
                          :data-testid="'card-diagnostics-text-' + part.toolCallId"
                        >{{ diagnosticTextOf(part) }}</div>
                      </details>
                      <!-- S6 批2（§5.3 / §7.1-③）：视图通道接管明细表 —— 有 `resultView` 时由通用渲染器渲，
                           旧反解表（`conflict-table`）在本分支下**零出现**（不同屏双份报同一份明细 = 违规）。
                           旧表保留为 `v-else-if` 回退：旧镜像 / 旧归档的段没有视图，按 §7.1-⑤ 最后才删。 -->
                      <ResultViewRenderer
                        v-if="part.resultView"
                        :view="part.resultView"
                        :document-text="part.result ?? null"
                      />
                      <div v-else-if="conflictRows(part).length" class="border border-solid border-[#eef0f3] rounded-[8px] overflow-hidden" data-testid="conflict-table">
                        <div class="px-[9px] py-[5px] bg-[#f6f7f9] border-0 border-b border-solid border-[#e3e6ea] text-[11.4px] [font-weight:650] text-[#57606a]">
                          <span class="inline-block w-[34%]">{{ t('ai.panel.definition') }}</span><span class="inline-block w-[26%]">{{ t('ai.panel.rowKey') }}</span><span class="inline-block w-[40%]">{{ t('ai.panel.reason') }}</span>
                        </div>
                        <div :class="['max-h-[min(480px,60vh)] overflow-y-auto', SCROLLBAR_CLASS]">
                          <div
                            v-for="row in conflictRows(part)"
                            :key="row.defCode + '#' + row.rowKey"
                            class="px-[9px] py-[6px] border-0 border-b border-solid border-[#eef0f3] text-[12.2px] text-[#1f2328] last:border-b-0"
                          >
                            <span class="inline-block w-[34%] align-top font-mono text-[11.6px] break-words">{{ row.defCode }}</span>
                            <span class="inline-block w-[26%] align-top font-mono text-[11.6px] break-words">{{ row.rowKey }}</span>
                            <span class="inline-block w-[40%] align-top break-words">{{ row.message }}</span>
                          </div>
                        </div>
                      </div>

                      <!-- 挂起子块（段判据，A1'③）：CONFIRM 待决 → 确认块；GF → FormRenderer（宿主条件
                           逐字保留，D10）；其余通用 FRONTEND 待执行/已决态 → 无子块（消 A9'⑤ 空壳卡）。
                           D-C7：旧投影挂起卡的「会话已失效」tag（原 :206）与 GF 非宿主 v-else 三 tag
                           （原 :251-253）/ activeForm 错误位（原 :254-257）随合并整列删除——语义由段状态
                           右状态标签（cardStatusOf，expired→「会话已失效」）与段 reason 位（A4'，D11）承接。 -->
                      <template v-if="part.requiresAction">
                        <template v-if="part.kind2 === 'CONFIRM' && part.status === 'pending'">
                          <!-- 2026-10-20 卡面一致性裁决（demo chat-ux/card-consistency-fix.html 场景一
                               1:1 移植）：确认卡**单层** —— 内层蓝框与内层卡头（「需要你确认后才能
                               继续」+ 内层 chip）整段退役，字段表与按钮行直接落在统一卡头的卡体内；
                               操作/参数行一个不少（操作 + args 全键，敏感掩码口径不变）。
                               倒计时环 / 剩余秒 / 「等待人工确认」静态兜底随去超时裁决一并退役
                               （历史 TIMEOUT 卡只读展示保留）。普通确认不展示额外摘要；
                               仅不可逆操作保留后果说明与勾选批准门；409 幂等拒绝后的「已提交，同步中…」瞬态保留。 -->
                          <div class="pb-[11px]" data-testid="hitl-gate">
                            <!-- 字段区 = 纯限高内滚（字段族双约束 min(280px,35vh)，裁决 §14.4）；
                                 按钮行在滚动区之外常驻。 -->
                            <div
                              class="mx-[11px] mt-[9px] bg-white border border-solid border-[#eef0f3] rounded-[8px] px-[10px] py-[2px] max-h-[min(280px,35vh)] overflow-y-auto"
                              :class="SCROLLBAR_CLASS"
                              data-testid="gate-rows"
                            >
                              <div
                                v-for="row in gateRows(part)"
                                :key="row.key"
                                class="flex gap-[10px] py-[7px] border-0 border-b border-solid border-[#eef0f3] text-[13px] leading-[1.5] last:border-b-0"
                              >
                                <!-- 问题1 偏差修正批（同族：门卡字段表键列）：原 58px 定宽 + break-words 装不下
                                     长英文键（notifyOnFail ≈80px）⇒ 折行、值贴首行。改自适应内容宽
                                     （min-w-0 + 上限 40%）+ truncate + title 看全，与只读卡键列同口径。 -->
                                <span class="flex-[0_1_auto] min-w-0 max-w-[40%] truncate text-[#8b949e] text-[12.4px]" :title="row.key">{{ row.key }}</span>
                                <span class="flex-1 min-w-0 break-words">{{ row.value }}</span>
                              </div>
                            </div>
                            <!-- 批4-D（用户 2026-10-23 裁决）：不可撤销 = 确认卡 + 复选框。
                                 位置 = 字段内滚区**之外**（恒可见，不跟着参数一起滚走），
                                 默认不勾；文案动态带动作名。普通后果确认卡不渲染本区（一字不动）。 -->
                            <div v-if="part.irreversible" class="mx-[11px] flex pt-[10px]" data-testid="gate-ack-row">
                              <label
                                class="flex cursor-pointer items-start gap-[6px] text-[12.2px] leading-[1.5] text-[#1f2328]"
                                data-testid="gate-ack"
                              >
                                <input
                                  type="checkbox"
                                  class="mt-[2px] flex-none cursor-pointer accent-[#2563eb]"
                                  data-testid="gate-ack-box"
                                  :checked="gateAcked(part.toolCallId)"
                                  :disabled="gateSubmitting(part.toolCallId) || runExpired"
                                  @change="onGateAckChange(part.toolCallId, $event)"
                                />
                                <span class="min-w-0 break-words">{{ gateAckText(part) }}</span>
                              </label>
                            </div>
                            <!-- 2A 批（裁决 §3 缺陷③）：按钮防折行 —— 行 flex-wrap（空间不足时按钮组整行
                                 下移）、按钮组 flex-none、按钮 whitespace-nowrap + min-w；组内不再换行。
                                 2026-10-29 ToC 呈现减负批：本轮运行态已失效（`runExpired`）⇒ 批准与拒绝
                                 一并禁用 —— 决策送不到后端，允许点击只会得到一次失败；出口保留在上方
                                 「已失效」行（刷新状态 / 新建会话）。 -->
                            <div class="mx-[11px] flex flex-wrap items-center gap-[9px] pt-[11px]" data-testid="gate-actions">
                              <!-- 批4-D：未勾选时给一行提示（紧邻置灰的「批准」）—— 拒绝永远可用。
                                   2026-10-29 复审 M-1：失效门卡的勾选框已禁用 ⇒ 提示行一并退场
                                   （不留"照着做也做不了"的死指令）。 -->
                              <span
                                v-if="gateNeedsAck(part) && !runExpired"
                                class="text-[12.2px] text-[#b45309]"
                                data-testid="gate-ack-hint"
                              >{{ t('ai.hitl.ackHint') }}</span>
                              <!-- R-166 口径③：409 幂等拒绝后的「已提交，同步中…」瞬态（不写任何乐观裁决） -->
                              <span
                                v-if="gateSyncing(part.toolCallId)"
                                class="text-[12.2px] text-[#57606a]"
                                data-testid="gate-syncing"
                              >{{ t('ai.panel.submitting') }}</span>
                              <span class="ml-auto flex flex-none items-center gap-[8px] whitespace-nowrap">
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default"
                                  :disabled="gateSubmitting(part.toolCallId) || runExpired"
                                  @click="decide(false, part.toolCallId)"
                                >{{ t('ai.panel.reject') }}</button>
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[72px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal"
                                  :disabled="gateSubmitting(part.toolCallId) || gateNeedsAck(part) || runExpired"
                                  @click="decide(true, part.toolCallId)"
                                >{{ t('ai.panel.approve') }}</button>
                              </span>
                            </div>
                          </div>
                        </template>

                        <div v-else-if="part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL" class="p-[11px]">
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
                            :locked="runExpired"
                            :error="liveForm.error"
                            :field-errors="liveForm.fieldErrors"
                            :initial-values="liveForm.values"
                            :upload-context="{ runId: liveForm.runId, toolCallId: liveForm.toolCallId, sessionId: ai.sessionId }"
                            :show-header="false"
                            flat
                            @submit="onFormSubmit"
                            @cancel="onFormCancel"
                            @draft="onFormDraft"
                          />
                          <!-- 已提交、结局未到（`isFormAwaitingOutcome`）：同一张表单**原样锁定**在同一张卡里
                               —— 此时活动表单已置 submitted（`liveForm` 不再提供表单），卡由上方
                               `readOnlyLike` 的只读体分支承接（`ro-form-*` + 段上的 `submittedValues`），
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
                              <span class="flex-none font-semibold" data-testid="gf-queue-position">{{ t('ai.panel.gfQueue.position', { position: formQueuePosition(part.toolCallId), total: ai.queuedForms.length }) }}</span>
                              <span class="min-w-0 flex-1 break-words text-[#57606a]" data-testid="gf-queue-hint">{{ t('ai.panel.gfQueue.hint') }}</span>
                            </div>
                            <div class="mt-[6px] flex justify-end">
                              <button
                                type="button"
                                class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
                                data-testid="gf-queue-cancel"
                                @click="onQueuedFormCancel(part.toolCallId)"
                              >{{ t('ai.panel.gfQueue.cancel') }}</button>
                            </div>
                          </div>
                        </div>

                        <!-- AI 表格操作专项 §3.3bis：写预览卡（计数 / 采样 diff / 全量 diff 三档）。
                             数据只在浏览器渲染，不上送；大批量须勾选“我了解影响范围”。 -->
                        <div v-else-if="isSheetPreviewPart(part)" class="p-[11px]">
                          <SheetPreviewCard
                            :plan="sheetPreviewFor(part)"
                            @apply="onSheetPreviewApply"
                            @cancel="onSheetPreviewCancel"
                            @abandon="onSheetPreviewAbandon(part.toolCallId)"
                            @level="ai.setPreviewLevel"
                          />
                        </div>

                        <!-- AI 可视可操作专项 §3.3.7：上传交接卡（唯一新增的人围栏外观）。
                             「选择文件」= 用户手势（picker 必须在同步栈里被调）；「放弃」= cancelled=true。
                             刷新后区域仍注册 ⇒ 可继续；区域不在该页 ⇒ 主按钮禁用 + 只能放弃。 -->
                        <div v-else-if="isUploadHandoffPart(part)" class="p-[11px]">
                          <UploadHandoffCard
                            :status="uploadHandoffStatus(part)"
                            :accept="uploadHandoffAccept(part)"
                            :label="uploadHandoffLabel(part)"
                            :reason="uploadHandoffReason(part)"
                            @choose="ai.chooseUploadFile()"
                            @cancel="ai.cancelUploadHandoff(part.toolCallId)"
                          />
                        </div>
                      </template>

                      <!-- AI 表格工具治理 §5.4：已应用改动的一行标识（不新增工具、不新增帧字段）。
                           数据源 = store 的 sheetApplied（现算），`其后人工改动 N 格` 在手改后由
                           refreshSheetApplied 重算 —— 不显示"应用瞬间的 0"当作现状。
                           L-74：本块**不在**上面那条待动作分支链里 —— 它标的是已 `succeeded` 的段
                           （`requiresAction` 此时已落回 false），挂在 `v-if="part.requiresAction"`
                           子树内等于恒不渲染。 -->
                      <div
                        v-if="appliedSummaryFor(part)"
                        class="p-[11px] text-[11.9px] leading-[1.6] text-[#57606a]"
                        data-testid="sheet-applied-summary"
                      >
                        <div>{{ appliedLine(appliedSummaryFor(part)!) }}</div>
                        <div v-if="(appliedSummaryFor(part)!.manualChanges ?? 0) > 0" class="mt-[2px] text-[#8b949e]">
                          {{ t('sheet.preview.manualChanges', { count: appliedSummaryFor(part)!.manualChanges }) }}
                        </div>
                      </div>
                    </div>
                    </template>
                  </div>
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

        <!-- R-159 #2/#3/#4：单一元信息行（demo aiShell(fix) 结构）——左位 = 流式期「工作中…」呼吸
             常驻（发出即现，首字符不消失；完成时淡出换时间戳）/ 完成后时间戳；按钮组紧邻时间戳
             （R-162 M1：删 justify-between 两端分置改左对齐紧邻 —— AI 行 [呼吸/时间戳|按钮] 左起左排；
             用户行 row-reverse 容器内同 DOM 序，渲染为按钮贴时间戳左侧、整簇靠右内聚，行尾无孤立按钮；
             R-159 语义全保留：流式期 visibility:hidden 占位、互斥换入、淡出）。
             R-165 S4 项 1（用户直裁）：操作按钮**常驻**——撤 opacity-0/group-hover/focus-within 浮出机制，
             按钮组恒可见（hover 高亮保留在各按钮自身的 hover:* 里）。撤 `transition-opacity` 随之，
             按钮组的 `visibility:hidden`（助手流式期占位不显）是 R-159 独立语义，不属浮出机制，保留。
             行判据：有正文 或 助手消息流式中（发出即现，无正文也要能呼吸）。 -->
        <div
          v-if="showMetaRow(m)"
          class="flex items-center gap-2 text-xs text-[#8b949e] w-full max-w-[82%]"
          :class="[msgGapClass(mi), m.role === 'user' ? 'mt-[6px] flex-row-reverse' : 'mt-[12px]']"
          :data-testid="'msg-ops-' + m.id"
        >
          <span class="flex items-center gap-1 min-h-[18px]">
            <span
              v-if="waitingForAction(m)"
              data-testid="park-waiting"
              class="inline-flex items-center gap-[5px] text-[12.2px] text-[#b45309] tracking-[.3px]"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#b45309] animate-breathe" aria-hidden="true"></span>
              <span class="animate-breathe-text">{{ t('ai.panel.waiting') }}</span>
            </span>
            <span
              v-else-if="isBreathing(m) || isFading(m)"
              :class="[BREATH_CLASS, isFading(m) ? FADE_OUT_CLASS : '']"
              data-testid="loading-breath"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#2563eb] animate-breathe"></span><span class="text-[12.2px] text-[#57606a] tracking-[.5px] animate-breathe-text">{{ t('ai.panel.breathing') }}</span>
            </span>
            <span v-else-if="showTimestamp(m)" class="text-[#8b949e]" data-testid="msg-timestamp">{{ formatProgressiveTs(m.timestamp) }}</span>
          </span>
          <div
            class="flex items-center gap-2"
            :class="m.streaming && m.role === 'assistant' ? 'invisible' : ''"
          >
            <span
              v-if="copyState && copyState.id === m.id && !copyState.ok"
              :data-testid="'copy-hint-' + m.id"
              class="text-xs text-[#c0392b]"
            >
              {{ t('ai.panel.copyFailed') }}
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
              {{ t('ai.round.truncated') }}
              <span class="sr-only">{{ t('ai.round.truncatedHint') }}</span>
            </span>
            <!-- 批次2（AR-09 / AR-05 / AR-17）：三动作语义分离 —— 继续（有正文/思考的失败轮，append-drive）/
                 重试本轮（零正文零思考且归属可证明的失败轮，含仅工具已结算的轮，走仅模型步重跑窄入口）/
                 普通 regenerate 本批不做 ⇒ **入口不渲染**（无禁用 / unsupported 死 UI）。
                 前两者都不追加伪用户气泡；同轮「继续 / 重试本轮」互斥。 -->
            <button
              v-if="showContinue(m)"
              type="button"
              data-testid="continue-round"
              :aria-label="t('ai.panel.resumeChat')"
              class="group/tip relative inline-flex items-center justify-center h-7 rounded-[8px] border-0 bg-transparent px-[6px] text-xs text-[#2563eb] transition hover:bg-[#eff4ff]"
              @click="continueTurn(m)"
            >
              {{ t('ai.panel.continue') }}
              <span :class="TIP_CLASS">{{ t('ai.panel.resumeChat') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              v-if="showRetryTurn(m)"
              type="button"
              data-testid="retry-turn"
              :aria-label="t('ai.panel.retryTurnHint')"
              class="group/tip relative inline-flex items-center justify-center h-7 rounded-[8px] border-0 bg-transparent px-[6px] text-xs text-[#2563eb] transition hover:bg-[#eff4ff]"
              @click="retryTurn(m)"
            >
              {{ t('ai.panel.retryTurn') }}
              <span :class="TIP_CLASS">{{ t('ai.panel.retryTurnHint') }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
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
             断流走 alert-interrupted，本卡只承载「本轮出错」的三变体分派（429 红 / 内容过滤灰 /
             上下文超限蓝）+ 未分类故障的通用红卡。分类与文案见 utils/error-card.ts。 -->
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
            <!-- 批次2：首屏只读**用户向摘要**；后端原文与机器码收进下面的「诊断详情」（折叠）。
                 用户向与诊断分层，长错误文案不再顶开会话。 -->
            <span
              class="block mt-[2px] text-[11.6px] text-[#57606a] leading-[1.55] break-words"
              :data-testid="'error-card-summary-' + m.id"
            >{{ m.errorCard.summary || t('ai.card.error.summary.generic') }}</span>
            <span v-if="m.errorCard.guide" class="block mt-[3px] text-[11.4px] text-[#57606a] leading-[1.55]">{{ m.errorCard.guide }}</span>
            <details class="mt-[4px]" :data-testid="'error-card-diagnostic-' + m.id">
              <summary class="cursor-pointer text-[11.2px] text-[#8b949e] select-none">{{ t('ai.card.error.diagnostic.label') }}</summary>
              <div class="mt-[3px] text-[11.2px] text-[#57606a] break-all">
                <span>{{ t('ai.card.error.diagnostic.code') }}: </span>
                <code :data-testid="'error-card-code-' + m.id">{{ m.errorCard.code }}</code>
              </div>
              <!-- S6 批1（长内容审计清零项：错误卡 detail 无 cap）：detail 加限高内滚。
                   2A 批（裁决 §14.4 文本块族）：限高改双约束 `min(280px,35vh)` —— 长错误文案不再把会话顶开。 -->
              <span :class="['mt-[2px] block max-h-[min(280px,35vh)] overflow-y-auto text-[11.2px] text-[#57606a] leading-[1.55] whitespace-pre-wrap break-words', SCROLLBAR_CLASS]" :data-testid="'error-card-detail-' + m.id">{{ m.errorCard.detail }}</span>
            </details>
          </div>
          <button
            v-if="m.errorCard.action && errorCardActionVisible(m)"
            type="button"
            class="flex-none border-0 bg-transparent p-0 text-[11.5px]"
            :class="ERROR_CARD_TONE[m.errorCard.variant].action"
            :data-testid="'error-card-action-' + m.id"
            @click="runErrorCardAction(m)"
          ><span class="border-b border-[#e3e6ea]">{{ m.errorCard.action }}</span></button>
        </div>
        <!-- 批次2（AR-05）：中性「状态未确认」视图 —— 只展示、不写乐观 done，也不冒充错误卡。
             消费 store/消息侧已有的 unconfirmed 状态（字段约定见顶部 recoveryBridge 注释）；
             出口 = 重新载入（同步服务端状态），不提供自动重跑/半截替换。 -->
        <div
          v-if="unconfirmedOf(m)"
          class="w-full flex items-start gap-[8px] border border-solid border-[#e3e6ea] bg-[#f6f7f9] rounded-[10px] px-[10px] py-[8px] mb-2.5"
          :data-testid="'turn-unconfirmed-' + m.id"
        >
          <span class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.3px] border-[#8b949e] text-[#57606a] text-[9px] mt-[2px] flex items-center justify-center" aria-hidden="true">?</span>
          <div class="flex-1 min-w-0">
            <b class="block text-[12.2px] [font-weight:650] text-[#1f2328]">{{ t('ai.panel.turnUnconfirmed') }}</b>
            <span class="block mt-[2px] text-[11.6px] text-[#57606a] leading-[1.55] break-words">{{ t('ai.panel.turnUnconfirmedHint') }}</span>
          </div>
          <button
            type="button"
            class="flex-none whitespace-nowrap border-0 bg-transparent p-0 text-[11.5px] text-[#2563eb]"
            :data-testid="'reload-turn-' + m.id"
            @click="reloadTurnState()"
          ><span class="border-b border-[#e3e6ea]">{{ t('ai.panel.reloadTurn') }}</span></button>
        </div>
        <!-- 批次2：这条消息来自镜像/差量回放但不能确认完整 —— 中性局部标记（全局提示条另有重载出口）。
             2026-10-29 返修：全局条已在说同一件事时消息级不再叠一遍（同一事实只留一个提示面）。 -->
        <div
          v-if="m.recoveryGap === true && !recoveryNoticeVisible"
          class="w-full flex items-start gap-[8px] border border-dashed border-solid border-[#e3e6ea] bg-[#f6f7f9] rounded-[10px] px-[10px] py-[6px] mb-2.5 text-[11.6px] leading-[1.6] text-[#57606a]"
          :data-testid="'recovery-gap-msg-' + m.id"
        >
          <span class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.2px] border-[#8b949e] text-[9px] mt-[1px] flex items-center justify-center" aria-hidden="true">i</span>
          <span class="min-w-0 flex-1 break-words">{{ t('ai.panel.recoveryGapHint') }}</span>
        </div>
        </div>
      </div>

      <!-- 批 E · E2：每轮正常完成后的上下文建议（规则模板 + 页面/任务槽位）。
           挂起 / 出错 / 有确认卡或表单挂着时整块不出（判据见 followups computed）；
           新一轮触发（发送 / 点 chip / 点建议）即移除。位置 = 内容层最后一轮回复之下，随流滚动。 -->
      <div
        v-if="followups.length > 0"
        class="mt-1 flex flex-col items-start gap-[6px] pl-2.5"
        data-testid="followup-suggestions"
        role="group"
        :aria-label="t('ai.suggestions.ariaLabel')"
      >
        <button
          v-for="item in followups"
          :key="item.id"
          type="button"
          data-testid="followup-suggestion"
          :data-suggestion-id="item.id"
          :class="SUGGESTION_CHIP"
          :aria-label="t('ai.suggestions.sendAria', { text: suggestionText(item) })"
          @click="sendPreset(suggestionText(item))"
        >
          {{ suggestionText(item) }}
        </button>
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
      @keydown="onComposerTipEscape"
      @mouseover="onComposerTipMouseOver"
      @mouseout="onComposerTipMouseOut"
      @focusin="onComposerTipFocusIn"
      @focusout="onComposerTipFocusOut"
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
            v-if="inputText.length > 0"
            data-testid="composer-count"
            class="mr-[2px] flex-none text-[10.5px] leading-[1.6] tabular-nums"
            :class="composerCounterClass"
          >{{ composerCounterText }}</span>
          <!-- 上下文占用（只在收到 done.context 或端点重建后出现；流式中不跳动） -->
          <ContextPill />
          <button
            v-if="ai.loading || ai.suspended"
            type="button"
            data-testid="stop-btn"
            data-composer-tip="stop"
            class="group/tip relative ml-auto flex h-[28px] w-[28px] flex-none items-center justify-center rounded-full border-0 bg-[#1f2328] transition-all duration-[150ms] hover:brightness-[1.15] focus-visible:ring-2 focus-visible:ring-[#2563eb]/40 focus-visible:ring-offset-0 disabled:cursor-default disabled:opacity-60 disabled:hover:brightness-100"
            :aria-label="ai.stopping ? t('ai.round.stopping') : t('ai.round.stop')"
            :disabled="ai.stopping"
            @click="stop"
          >
            <span class="block h-[9px] w-[9px] rounded-[2px] bg-white" aria-hidden="true"></span>
            <span class="tip" :class="[TIP_CLASS, hiddenComposerTips.has('stop') ? 'hidden' : '']" aria-hidden="true">{{ ai.stopping ? t('ai.round.stopping') : t('ai.round.stop') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
          </button>
          <button
            v-else
            type="button"
            data-testid="send-btn"
            data-composer-tip="send"
            class="group/tip relative ml-auto flex h-[28px] w-[28px] flex-none items-center justify-center rounded-full border-0 bg-[#2563eb] text-[13px] leading-none text-white transition-all duration-[150ms] hover:brightness-[1.08] disabled:cursor-default disabled:bg-[#8b949e] disabled:text-white focus-visible:ring-2 focus-visible:ring-[#2563eb]/40 focus-visible:ring-offset-0"
            :aria-label="t('ai.panel.send')"
            :disabled="!canSubmit"
            @click="send"
          >↑<span class="tip" :class="[TIP_CLASS, hiddenComposerTips.has('send') ? 'hidden' : '']" aria-hidden="true">{{ t('ai.panel.send') }}<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span></button>
        </div>
      </div>
      <!-- M5（复审）：播报节流 —— 计数文本自身**不带** aria-live（否则每敲一个字都播报一次），
           改由这个屏读专用 live region 只在**档位切换**时说话：其文本在一个档位内是常量，
           DOM 文本因此只在"常规→余量不足/到顶/超限"的跃迁上变化。 -->
      <span class="sr-only" data-testid="composer-count-live" aria-live="polite">{{ composerTierAnnouncement }}</span>
    </div>

    <!-- R-165 S5b：mermaid 全屏查看器（demo v7.7 :1948-1964 重做口径）——
         深色 scrim（bg-black/50）+ 居中白色圆角容器（圆角 10 · 边框 #e3e6ea · 工具条 #f6f7f9）；
         滚轮以指针为锚点缩放 0.5×–4×、拖拽走 transform 平移（不用 overflow 滚动）；
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
          <span class="font-mono text-[10.5px] text-[#8b949e]">0.5×–4×</span>
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
            <div ref="mmdViewerZoomRef" data-testid="mmd-viewer-zoom" v-html="mmdViewerSvg" class="origin-center select-text [transform:scale(var(--z,1))]"></div>
          </div>
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
 * - **消息体按段渲染（对话时序包 批2 权威翻转）**：`parts` 段序列（text / reasoning / tool）
 *   是渲染唯一真值，按真实时序自上而下排列 —— "正文 → 工具卡 → 正文"夹心轮次才显出真实顺序；
 *   挂起卡跟随其工具段位置（③b 把两者并成一卡前的过渡形态）；复制的显示判据同取 parts，
 *   复制内容仍取 `m.content`（text 段 join 的派生投影，同源）；
 * - 上下文条：会话 id / 工作区上下文 / 数据版本 / 可用性 tag / 契约同步 chip —— 蓝本无
 *   `/api/ai/health`、无契约事件，本仓需要可视化"刷新恢复"与"工作区已同步"的状态；
 * - 工具卡四态（调用中/成功/失败/待执行·待确认按通道）并展示参数与结果 —— 蓝本是"已执行工具 X"单标签；
 * - 生成式表单挂起卡（GF-B）：generative_form 帧渲染 FormRenderer 等用户填写，取消/超时带
 *   cancelled:true 回灌 —— 蓝本无此前端工具通道；
 * - HITL 卡片：确认/拒绝双路径（无原因输入、无倒计时 —— 2026-10-20 去超时裁决与 H1 口径）；
 * - 409/503/断流三类降级提示（批G 起不再有「一键重挂」：在途轮刷新即静默续收）；
 * - 头部「新建对话」文字按钮 + 空态页签语义小字（苞 A 项 1）：清空当前会话并换新 sessionId
 *   （旧会话由 `DELETE /api/ai/session/{sessionId}` 完整销毁；旧 `/history` 路径同语义）—— 蓝本无"重开"入口；
 * - 逐条复制（苞 B 项 2）：消息 hover/聚焦时显现「复制」，复制正文纯文本（不含思考链与工具卡）；
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
 * （底部「查看源码」钮退役）+ 卡头右端图标钮组（缩放读数 / 复位 / 全屏）；预览态限高内联
 * 滚轮缩放（指针锚定 0.5×–4×）+ transform 拖拽平移 + 复位，超宽仍可内联横滚，两态各自记住滚动；
 * 源码态限高内滚 mono 盒 + 复制源码图标钮（绿勾反馈沿用 S5a 统一机制）；全屏查看器重做
 * （scrim + 白容器 · 指针锚定缩放 · transform 平移 · 6 钮工具条 · Esc/点 scrim 关闭 · role=dialog）。
 * 结构类名换 `mmdc` 前缀（旧 `.mmd*` CSS 规则零引用但本批禁删禁改，S5c 统一拆）。
 *
 * S6 应用批·批1（用户已裁决项，纯前端；蓝本 = demo v7.9 定稿 + v7.10 规则表）：
 * ① **回执卡独立块退役**（`utils/blocks.ts` 的 receipt 块 + 本组件回执渲染段整体删除）——已决
 *    确认门 / GF 段由**自己的卡**原地切只读态；「查看详情 / 收起详情」文字钮全稿删除；
 * ② **JSON 展示全面清零**：门卡删 args JSON 盒（字段表改全键 + 敏感值掩码）、普通工具卡参数改
 *    折叠区字段表、发布冲突明细收敛（reason 只留一句摘要 + 明细渲表格卡）；
 * ③ **空内容工具卡不给展开入口**（v7.10 ajv11 / 场景 19-e）：展开体渲染后 0 行 ⇒ 不渲 chevron、
 *    卡头 disabled 无 hover、点击 no-op；
 * ④ 思考过程展开体改纯限高内滚（裁决点 21），`CollapsibleText` 组件整体退役
 *    （思考 / 参数 / 输出 / 回执详情四处全部「纯限高内滚、无展开钮」）；
 * ⑤ 长内容审计清零项：错误卡 detail 限高、行内 code 撤 nowrap 改 anytime 折行（markdown.ts）、
 *    工具输出盒补 break-all、门卡英文字段 key 可换行、degraded 期代码块同挂限高。
 *
 * 2A 批（2026-10-17 用户裁决 §9 K1 / §10 F9 / §12 H2 / §14.4 / §15 K12 / §16 M1 / §20）：
 * ① **已决只读 = 原样 UI 锁定渲染**（纠偏 label-value 摘要式）：门卡参数区字段表原样、
 *    GF 复用同一张表单（FormRenderer readonly：原生 disabled + 值回填）、操作按钮整块不渲染；
 * ② 卡头 = 三类型统一结构（左固定类型裸 SVG + 中性标题/摘要 + 右状态文字标签 + chevron；
 *    36.4px、chevron ml-auto 靠右）；decidedAt 数据层照记、**卡面不渲染**；
 * ③ 已决多态（已批准 / 已拒绝 / 已超时 / 已批准·执行失败 / 已批准·未执行 / 未批准·未执行 /
 *    已提交 / 已取消）回合结束后一律默认折叠，卡头单行（标题/摘要/右状态标签）保留可辨识信息；
 *    决策当下不折叠（turnStillOpen）；
 * ④ 状态全集：门卡 = 已批准 / 已拒绝 / 已超时 / 已批准·执行失败（批G · S13 起增「已取消」：
 *    用户停止是一次决策；批4 增「已批准·未执行」「未批准·未执行」）；表单系按真实结局分列
 *    （已提交 / 已取消 / 已拒绝 / 已超时 / 已批准·执行失败 / 未批准·未执行 —— 只有真实用户
 *    取消才是「已取消」，2026-10-29 批改正旧口径的"非成功即已取消"）；
 *    审批卡旧字样（含被 R5 / §10 F6 废止的两枚）零残留；
 * ⑤ 未提交不记录：取消 / 超时收尾回初始空态再锁定（F9）；
 * ⑥ **限高新尺度（§14.4）**：字段族 `min(280px,35vh)` / 表单·组合族 `min(320px,45vh)` /
 *    长表格族 `min(480px,60vh)` / 代码·详情族 `min(280px,35vh)` / 用户长消息 184px 不动 /
 *    mermaid 预览 `min(320px,45vh)` / 源码盒 `min(280px,35vh)`；纯限高内滚、无展开钮不变。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, ChatDotRound } from '@element-plus/icons-vue'
import { useAiStore } from '@/stores/ai'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatProgressiveTs, TASK_TYPE_LABELS } from '@/utils/format'
import {
  COPY_ICON_BTN_CLASS,
  COPY_ICON_SVGS,
  COPY_STATE_CLASS,
  MD_BODY_ROOT_CLASS,
  SCROLLBAR_CLASS,
  TIP_ARROW_CLASS,
  TIP_CLASS,
  renderMarkdown,
  renderMermaidSvg,
  setStreamFlushHandler
} from '@/utils/markdown'
import { toolSummary, toolStatusWords } from '@/utils/tool-summaries'
import { buildFollowups, type FollowupSuggestion } from '@/utils/ai-suggestions'
import {
  argRows,
  cardStatusOf,
  cardTypeOf,
  formSpecOf,
  hasBodyRows,
  isBlankText,
  isFormAwaitingOutcome,
  isReadOnlyCard,
  parsePublishConflicts,
  readOnlyStateOf,
  reasonHeadline,
  submitRows,
  type CardTone,
  type ConflictRow,
  type FieldRow,
  groupParts,
  resolveBlockExpanded,
  type Block
} from '@/utils/blocks'
import { deriveCopyText, deriveProjections, partDiagnosticOf, partDiagnosticText, partResultIsInternal, type PartDiagnostic } from '@/utils/parts'
import { isRenderableView } from '@/utils/resultview'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import CompactionStatus from '@/components/AiPanel/CompactionStatus.vue'
import ResultViewRenderer from '@/components/AiPanel/resultview/ResultViewRenderer.vue'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'
import UploadHandoffCard from '@/components/AiPanel/UploadHandoffCard.vue'
import ContextPill from '@/components/AiPanel/ContextPill.vue'
import ReasoningHeader from '@/components/AiPanel/ReasoningHeader.vue'
import { RUN_EXPIRY_COUNTDOWN_WINDOW_MS, runExpiryLabel, runExpiryRemainingMs, type RunExpiryAnchor, type RunExpiryLabel } from '@/utils/run-expiry'
import { GENERATIVE_FORM_TOOL, type AiLoadErrorView, type ChatMessage, type MessagePart, type TextPart, type ToolPart } from '@/types/ai'
import type { ToolPreflight } from '@/types/sse'
import { SHEET_PREVIEW_TOOLS, UI_HANDOFF_TOOLS } from '@/types/tools'
import { resolveAiRef } from '@/utils/ai-regions'
import type { SheetAppliedSummary } from '@/utils/sheet-tools'
import type { FormSpec } from '@/types/form-schema'
import type { ErrorCardVariant } from '@/utils/error-card'

const { t, locale } = useI18n()
const ai = useAiStore()
const workspace = useWorkspaceStore()

/**
 * 批次6A：消息流（`role="log"`）的 `aria-busy` —— 加载与流式两种"还在变"的状态。
 *
 * - 历史加载期（骨架屏）：整段内容尚未对账完；
 * - 任一轮在流式（含刷新在途/续跑）：内容仍在增长。
 * 读屏据此延后播报，不把半截内容当最终结果念。
 */
const logBusy = computed(() => !ai.historyLoaded
  || ai.loading
  || ai.messages.some((message) => message.streaming))

/**
 * 批次3 补齐 / AR-10：本轮运行态寿命的**本地时钟**（只推进显示，不参与"何时到期"的定义）。
 *
 * <p>到期时刻由后端 PTTL 给出（store 的 `runExpiry`）。读秒窗口内按 1s 推进、更长按 30s
 * 粗粒度刷新（24h 量级不逐秒重渲染整个消息列表）；到 0 即停表并转「已失效」。
 */
const runExpiryNowMs = ref(Date.now())
let runExpiryTimer: ReturnType<typeof setTimeout> | null = null

const RUN_EXPIRY_TICK_MS = 30_000
const RUN_EXPIRY_COUNTDOWN_TICK_MS = 1_000

function stopRunExpiryTimer(): void {
  if (runExpiryTimer !== null) {
    clearTimeout(runExpiryTimer)
    runExpiryTimer = null
  }
}

function scheduleRunExpiryTick(anchor: RunExpiryAnchor): void {
  stopRunExpiryTimer()
  const remaining = runExpiryRemainingMs(anchor, Date.now())
  if (remaining <= 0) {
    return
  }
  const delay = remaining <= RUN_EXPIRY_COUNTDOWN_WINDOW_MS
    ? RUN_EXPIRY_COUNTDOWN_TICK_MS
    : Math.min(RUN_EXPIRY_TICK_MS, remaining)
  runExpiryTimer = setTimeout(() => {
    runExpiryNowMs.value = Date.now()
    scheduleRunExpiryTick(anchor)
  }, delay)
}

watch(() => ai.runExpiry, (anchor) => {
  runExpiryNowMs.value = Date.now()
  if (anchor === null) {
    stopRunExpiryTimer()
    return
  }
  scheduleRunExpiryTick(anchor)
}, { immediate: true })

/**
 * 卡面寿命文案（AR-10 只可见化）：`null` = 没有后端权威锚点（旧后端 / 未知 / 已换轮）。
 *
 * <p>归属必须对得上当前轮（`runExpiry.runId === activeRunId`）—— 旧轮的寿命不得落到新轮的卡上。
 */
const runExpiryView = computed<RunExpiryLabel | null>(() => {
  const anchor = ai.runExpiry
  if (anchor === null || ai.activeRunId === null || anchor.runId !== ai.activeRunId) {
    return null
  }
  return runExpiryLabel(runExpiryRemainingMs(anchor, runExpiryNowMs.value))
})

/** 到期后的可达出口①：重新拉一次 `runs/current`（不改 TTL，只对账）。 */
function refreshExpiredRunState(): void {
  void ai.retryRestoreSnapshot()
}

/** 到期后的可达出口②：新建会话（旧运行态已按保留期消失，不再假装能续）。 */
function startNewChatAfterExpiry(): void {
  newChat()
}

/** 本轮运行态是否已失效（AR-10 到期态）：失效后批准/拒绝与表单提交都不可达，界面须先锁死入口。 */
const runExpired = computed(() => runExpiryView.value?.expired === true)

/**
 * 阶段条现场（批次3 CompactionStatus）：只有**进行中**的两态才显示 ——
 * 压缩完成不另起一条提示（减法：「工作中」呼吸已表示本轮仍在产出）。
 */
const stageBar = computed<{ mode: 'compacting' | 'preparing'; startedAtMs: number | null; elapsedMs: number | null } | null>(() => {
  if (ai.compactionPhase === 'compacting') {
    return {
      mode: 'compacting',
      startedAtMs: ai.compactionStartedAtMs,
      elapsedMs: ai.compactionElapsedMs
    }
  }
  const preparing = ai.preparingRun
  if (preparing !== null) {
    return { mode: 'preparing', startedAtMs: preparing.startedAtMs, elapsedMs: preparing.elapsedMs }
  }
  return null
})

/**
 * 批次2 的恢复与轮次动作**唯一适配层**（批次3 起 store 由本批维护；适配层保留，
 * 继续隔离字段/动作名的变化，避免模板直接拼状态）。
 *
 * <p>面板只经下面这组函数读状态 / 调动作；store 侧字段或 action 名变化时**只改这里**。
 * 已对齐并行落地的实际契约（2026-10-26）：`historyError` / `currentError`（`AiLoadErrorView`，
 * 只有 code/status，不含文案）、`retryLoadHistory()` / `retryRestoreSnapshot()`、
 * `busyRun`（`AiBusyRunRef`）+ `reattachBusyRun()`、`message.unconfirmed` 与
 * `lastRoundOutcome='unconfirmed'`。其余候选名只为兼容旧基线，缺省即不渲染，不伪造状态。
 * <ul>
 * <li>历史/快照恢复错误：`historyError` / `currentError`（对象或字符串）；重试
 *     `retryLoadHistory`（history）/ `retryRestoreSnapshot`（current），缺省回落 `loadHistory()`。</li>
 * <li>409 在途轮：`busyRun.runId`；动作 `reattachBusyRun()`（一次性对账 + 静默续收），
 *     缺省回落 `reattachActive(runId)`。runId 不进任何用户可见文本。</li>
 * <li>unconfirmed：`message.unconfirmed=true`（兼容旧字段名）；重新载入优先
 *     `retryLoadHistory`，缺省 `loadHistory()`。</li>
 * <li>三动作（AR-09 / AR-05 / AR-17）：继续 = `send(..., { skipUserRecord: true })`（保持既有口径）；
 *     重试本轮 = 「仅模型步重跑」真实 store action（`retryModelStep` → 后端窄入口 → 既有 events 重挂；
 *     只认 `message.runId` 归属，无证据不出入口）；
 *     普通 regenerate = **本批不做、入口整体隐藏**（无禁用 / unsupported 死 UI，也不本地截断重发）。</li>
 * </ul>
 */
type RecoveryStoreView = Record<string, unknown>

function storeView(): RecoveryStoreView {
  return ai as unknown as RecoveryStoreView
}

/** 按候选名取第一个非 null/undefined 的 store 字段。 */
function storeField<T>(...names: string[]): T | undefined {
  const view = storeView()
  for (const name of names) {
    const value = view[name]
    if (value !== undefined && value !== null) {
      return value as T
    }
  }
  return undefined
}

/** 按候选名取第一个函数型 store action（不存在 = null，不抛错）。 */
function storeAction(...names: string[]): ((...args: unknown[]) => unknown) | null {
  const view = storeView()
  for (const name of names) {
    const value = view[name]
    if (typeof value === 'function') {
      return value as (...args: unknown[]) => unknown
    }
  }
  return null
}

/** 恢复失败的视图（scope 决定标签与重试动作；detail 是本地化人话，code/status 只作分档）。 */
interface RecoveryLoadError {
  scope: 'history' | 'current'
  detail: string
}

/**
 * store 只落机器可读现场（`AiLoadErrorView`：scope/code/status/at），**不落用户文案** ——
 * 这里按 code/status 选一条本地化短句；机器码本身不进用户可见文本。
 */
function loadErrorDetail(view: AiLoadErrorView | string): string {
  if (typeof view === 'string') {
    return view
  }
  if (view.code === 'NETWORK_ERROR' || view.status === 0) {
    return t('ai.panel.loadErrorNetwork')
  }
  if (view.status >= 500) {
    return t('ai.panel.loadErrorServer')
  }
  return t('ai.panel.loadErrorUnknown')
}

function recoveryLoadError(): RecoveryLoadError | null {
  const history = storeField<AiLoadErrorView | string>('historyError', 'restoreError', 'historyLoadError')
  if (history !== undefined) {
    return { scope: 'history', detail: loadErrorDetail(history) }
  }
  const current = storeField<AiLoadErrorView | string>('currentError', 'snapshotError')
  if (current !== undefined) {
    return { scope: 'current', detail: loadErrorDetail(current) }
  }
  return null
}

function recoveryErrorLabel(): string {
  return recoveryLoadError()?.scope === 'current'
    ? t('ai.panel.recoveryFailed')
    : t('ai.panel.historyLoadFailed')
}

function recoveryErrorText(): string | null {
  return recoveryLoadError()?.detail ?? null
}

function retryRecoveryLoad(): void {
  if (recoveryLoadError()?.scope === 'current') {
    const restore = storeAction('retryRestoreSnapshot', 'retryCurrentSnapshot')
    if (restore !== null) {
      void restore.call(ai)
      return
    }
  }
  const history = storeAction('retryLoadHistory', 'retryHistory', 'reloadHistory')
  if (history !== null) {
    void history.call(ai)
    return
  }
  void ai.loadHistory()
}

/**
 * 恢复提示面的**唯一去重口径**（2026-10-29 返修）：`sealRecovery` 那一路失败在 store 里
 * 同时写 `historyError` 与 `recoveryGap`，两者是同一个事实 —— 但 `restoreSuspendedSnapshot`
 * 的 preparingRun 分支只写 `currentError`（不登记缺口），故「错误」与「缺口」不能互相假定：
 * - 有恢复错误 ⇒ 只出错误条；条内「内容可能不完整」那行**另看 `ai.recoveryGap !== null`**
 *   （有缺口才说，没有就只说错误本身）；
 * - 无恢复错误但有缺口（archive-window 真缺帧）⇒ 只出中性缺口条；
 * - 任一全局条在显示 ⇒ 消息级 `recoveryGap` 局部标记不再重复。
 */
const recoveryErrorVisible = computed(() => recoveryErrorText() !== null)
const recoveryGapBarVisible = computed(() => !recoveryErrorVisible.value && ai.recoveryGap !== null)
const recoveryNoticeVisible = computed(() => recoveryErrorVisible.value || ai.recoveryGap !== null)

/** 409 回执里的进行中轮（`busyRun.runId`）；旧字段名仅作兼容。 */
function busyRunId(): string | null {
  const view = storeView()
  // 并行落地的 store 以 `busyRun` 为权威：为 null = 后端没给可信 runId ⇒ 不出入口（不拿旧 activeRunId 猜）。
  if ('busyRun' in view) {
    const busy = view.busyRun
    if (busy !== null && typeof busy === 'object') {
      const runId = (busy as Record<string, unknown>).runId
      if (typeof runId === 'string' && runId.trim() !== '') {
        return runId
      }
    }
    return null
  }
  const explicit = storeField<unknown>('busyRunId', 'sessionBusyRunId', 'conflictRunId', 'blockedByRunId')
  if (typeof explicit === 'string' && explicit.trim() !== '') {
    return explicit
  }
  // 旧基线（无 busyRun 字段）的兜底：409 的本地通知码 + 既有在途 runId（不显示 runId 本身）。
  if (ai.noticeCode === 'ai.notice.sendFailed'
    && typeof ai.activeRunId === 'string' && ai.activeRunId !== '') {
    return ai.activeRunId
  }
  return null
}

function canReturnToActiveRun(): boolean {
  return busyRunId() !== null
}

function returnToActiveRun(): void {
  const action = storeAction('reattachBusyRun', 'returnToActiveRun', 'resumeActiveRun')
  if (action !== null) {
    void action.call(ai)
    return
  }
  const runId = busyRunId()
  if (runId !== null) {
    void ai.reattachActive(runId)
  }
}

/**
 * 批次2 / AR-01 第 5 条：该条历史消息是**合成/系统提示**（后端 `synthetic=true`）。
 *
 * <p>面板把它渲染为系统级「已压缩」提示，而不是用户气泡；摘要正文不直接展示
 * （其中可能含内部配置键/占位符），也不参与「继续/重试/regenerate」等消息级动作。
 */
function syntheticOf(message: ChatMessage): boolean {
  return message.synthetic === true
}

function unconfirmedOf(message: ChatMessage): boolean {
  if (message.role !== 'assistant') {
    return false
  }
  const view = message as unknown as RecoveryStoreView
  if (view.turnUnconfirmed === true || view.unconfirmed === true) {
    return true
  }
  for (const key of ['turnState', 'turnStatus', 'endState', 'turnEnd']) {
    const value = view[key]
    if (value === 'unconfirmed' || value === 'UNCONFIRMED') {
      return true
    }
  }
  const messageId = storeField<string>('unconfirmedMessageId', 'unconfirmedTurnMessageId')
  if (messageId !== undefined && messageId === message.id) {
    return true
  }
  const last = ai.messages[ai.messages.length - 1]
  if (ai.lastRoundOutcome === 'unconfirmed' && last === message) {
    return true
  }
  const storeFlag = storeField<unknown>('turnUnconfirmed', 'unconfirmed')
  if (storeFlag === true && last === message) {
    return true
  }
  const storeState = storeField<unknown>('turnEnd', 'turnState', 'turnStatus')
  if ((storeState === 'unconfirmed' || storeState === 'UNCONFIRMED') && last === message) {
    return true
  }
  const runId = storeField<string>('unconfirmedRunId')
  return runId !== undefined && runId === ai.activeRunId && last === message
}

function reloadTurnState(): void {
  const action = storeAction('retryLoadHistory', 'reloadUnconfirmedTurn', 'reloadTurnState', 'reloadFromServer')
  if (action !== null) {
    void action.call(ai)
    return
  }
  void ai.loadHistory()
}

/**
 * 一轮是否已有**文字产出**：非空正文 / 非空思考。
 *
 * <p>工具段**不算**文字产出：工具已结算但模型没写出任何正文/思考的失败轮，续写没有可承接的半截答案，
 * 应走「重试本轮」（仅模型步重跑）；有文字才走「继续」（append-drive）。旧判据把工具段并入「有产出」，
 * 导致这类轮只出「继续」而不出「重试本轮」。
 */
function hasTextOrReasoning(message: ChatMessage): boolean {
  return message.parts.some(
    (part) => (part.kind === 'text' || part.kind === 'reasoning') && part.text.trim() !== ''
  )
}

/** 继续（append-drive）：追加驱动，不删历史、**不追加用户气泡**（保持既有 skipUserRecord 口径）。 */
function continueTurn(_message: ChatMessage): void {
  clearStreamMdCache()
  void ai.send(t('ai.panel.continue'), { skipUserRecord: true })
}

/**
 * 重试本轮（retry-turn）：走 store 的**真实 action**（仅模型步重跑窄入口）。
 *
 * <p>批次2 修：旧实现是"store 没有 action 时本地截断 + `send(skipUserRecord)` —— 那是**重发请求**，
 * 不是仅模型步重跑（会重建上下文、可能重放工具），属于危险假实现。这里不存在任何本地回退：
 * 后端不允许（非 FAILED / 有半截 / 待决未清）就由 store 按 `failedCheck` 显示对应状态。
 */
function retryTurn(message: ChatMessage): void {
  clearStreamMdCache()
  void ai.retryModelStep(message)
}

const inputText = ref('')
/**
 * 安全防护专项批①：单条消息的字符上限——与后端 {@code app.ai.input.max-chars}（8192）**同值同尺**
 * （判据 = UTF-16 码元数，即 JS `String#length` / `<textarea maxlength>` 的计数口径，中文按 1 字算）。
 * 前端只做"不让写出超限文本 + 提前告知"，真正的拒绝仍在后端（400 MESSAGE_TOO_LONG，不截断）。
 */
const MESSAGE_MAX_CHARS = 8192
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

function userIntentRecent(): boolean {
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
  if (Date.now() < pinSettleUntil && !userIntentRecent()) {
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

/**
 * 活动中提示、淡出与 chevron 共用模板工具类；等待中保留原琥珀样式，圆点同样使用呼吸动画。
 * 文字只呼吸透明度（animate-breathe-text / aiBreatheText，无 scale）；圆点仍用 animate-breathe。
 * keyframes 本体见 tailwind.config.js（aiBreathe / aiBreatheText / aiFadeOut）。
 */
const BREATH_CLASS = 'loading-breath inline-flex items-center gap-[6px] m-0 pl-[2px] text-[12.2px] text-[#57606a]'
/** 完成淡出：`fade-out` 为标记类（既有两态断言锚），`animate-fade-out` 承载 0.3s forwards。 */
const FADE_OUT_CLASS = 'fade-out animate-fade-out'
const TCHEV_CLASS =
  'tchev flex-none inline-block text-[#8b949e] text-[12px] transition-transform duration-[180ms] [&.is-open]:rotate-90'

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
 * 错误卡动作是否可见（批次2 / AR-17）：quota 卡的「稍后重试」只在**归属可证明的零文字产出失败轮**
 * 上出现（真实窄入口）。普通已完成 / 有半截内容没有对应的后端入口 —— 该动作**直接隐藏**，
 * 不做"禁用但仍可点出 unsupported"的死 UI。
 */
function errorCardActionVisible(message: ChatMessage): boolean {
  const card = message.errorCard
  if (!card?.action) {
    return false
  }
  if (card.variant === 'quota') {
    return showRetryTurn(message)
  }
  return true
}

/** 错误卡动作（= 三变体各自引导语的落点，全部复用本组件既有动作，不新增 store 出口）：
 *  「稍后重试」= 零文字产出且归属可证明 ⇒ **重试本轮**（仅模型步重跑窄入口）；否则该动作隐藏（AR-17）；
 *  「改写后重发」= 把焦点交回输入框（改写是用户动作，面板只能把落点指过去）；
 *  「开新会话」= 既有 `newChat()`（与面板头「新建对话」同一出口）。
 *  分派按 **variant**（i18n L-69 后 `action` 文案随 UI 语言变，字符串比对会断；variant → 动作是一对一）。 */
function runErrorCardAction(message: ChatMessage): void {
  const card = message.errorCard
  if (card === null || card === undefined) {
    return
  }
  if (card.variant === 'quota') {
    if (showRetryTurn(message)) {
      retryTurn(message)
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
/** 本轮真正需要重量的用户消息（新挂载 / 文本变化 / 展开切换）——不再逐条扫全表。 */
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
 * 单一 measure：**只量脏消息**（新挂载 / 文本变化 / 展开切换），集合真变化才换引用。
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

/**
 * 逐条复制的反馈（苞 B 项 2）：**组件局部状态**，生命周期 2s。
 * 为什么不复用 `ai.notice`：它是单槽位（一次只承载一条提示），"已复制"会把并发的
 * 断流/取消等提示挤掉 —— 局部状态只作用于被点的那条消息。
 */
const copyState = ref<{ id: string; ok: boolean } | null>(null)
let copyTimer: ReturnType<typeof setTimeout> | null = null

/** R-165 S5a 项 7：该消息此刻处于「复制成功」绿勾态（2s 窗口内）。 */
function isMsgCopied(messageId: string): boolean {
  return copyState.value !== null && copyState.value.id === messageId && copyState.value.ok
}

const shortSessionId = computed(() => ai.sessionId.slice(0, 8))

/**
 * 批 E · E2：空态快捷 chips（模板 id 与「每轮建议」同一份词表 `ai.suggestions.*`）。
 * 顺序 = 用户最常用的四件事：看定义 / 看工作区 / 估行数 / 启动作业。
 */
const EMPTY_STATE_CHIPS = ['listDefs', 'workspaceState', 'exportEstimate', 'startExport'] as const
const TERMINAL_TASK_SUGGESTIONS = new Set(['startExport', 'startImport', 'precheckImport'])
/**
 * M-15（2026-10-29）：**探针失败**不等于"服务不可用"。
 *
 * <p>`available` 来自健康探针：一次网络抖动 / 代理 502 / 体形态不符就会把它打成 false，而旧实现
 * 直接拿它当发送闸 —— 发送钮、Enter 与空态 chips 一起被禁用，用户必须手动点"重新探测"，
 * 程序化 `ai.send` 又不被拦（UI 与 store 语义分叉）。现在把两者分开：
 * <ul>
 * <li>探针**成功且明确说不可用**（`health !== null && available === false`）⇒ 仍按降级限制；</li>
 * <li>探针**失败 / 尚未探测**（`health === null`，`healthError` 承载失败文案）⇒ 只作提示，
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
const visibleEmptyStateChips = computed(() => EMPTY_STATE_CHIPS.filter((chip) => suggestionAllowedForTask(chip)))

function suggestionAllowedForTask(id: string): boolean {
  if (!TERMINAL_TASK_SUGGESTIONS.has(id)) {
    return true
  }
  if (workspace.taskStatus === 'CANCELLED') {
    return false
  }
  return !(workspace.taskStatus === 'COMPLETED' && workspace.taskType === 'EXPORT' && id === 'startExport')
}

/** 建议/胶囊的当前语言文案（模板 id + 插值参数 → 文案；缺键时 vue-i18n 回落 key 原文）。 */
function suggestionText(item: FollowupSuggestion): string {
  return t(`ai.suggestions.${item.key}`, item.params ?? {})
}

function sendPreset(text: string): void {
  if (!chipsAvailable.value) {
    return
  }
  void ai.send(text)
}

const followups = computed<FollowupSuggestion[]>(() => {
  if (!chipsAvailable.value || !ai.followupReady || ai.loading || ai.suspended || ai.streamInterrupted) {
    return []
  }
  if (ai.lastRoundOutcome !== 'ok') {
    return []
  }
  if (ai.pendingConfirms.length > 0 || ai.pendingToolCall !== null) {
    return []
  }
  if (ai.activeForm !== null || ai.activePreview !== null || ai.activeUploadHandoff !== null) {
    return []
  }
  const last = ai.messages[ai.messages.length - 1]
  if (!last || last.role !== 'assistant' || last.streaming || !last.done) {
    return []
  }
  return buildFollowups({
    page: workspace.page,
    taskType: workspace.taskType,
    step: workspace.step,
    taskId: workspace.taskId
  }).filter((item) => suggestionAllowedForTask(item.id))
})

/**
 * I8（R1）/ N1（R2）：状态胶囊 / 占位文案 / 降级条**与发送闸同一判据**（`healthBlocksSend`），
 * 且三档各自独立、互不冒充：
 * <ul>
 *   <li>`healthBlocksSend`（探针成功且明确不可用）⇒「已降级」；</li>
 *   <li>`health === null && healthError`（探针**失败**，可用性未确认）⇒「探测失败」+ 灰族，
 *       `title` 给**固定友好文案**（R3：原文不上屏）—— 既不谎报"可用"绿，也不冒充"未检测"；</li>
 *   <li>`health === null` 且无错误（还没测过）⇒「未检测」。</li>
 * </ul>
 */
const healthProbeFailed = computed(() => ai.health === null && ai.healthError !== null)

const healthLabel = computed(() => {
  if (healthBlocksSend.value) {
    return t('ai.panel.degraded')
  }
  if (healthProbeFailed.value) {
    return t('ai.panel.probeFailed')
  }
  if (ai.health === null && ai.healthError === null) {
    return t('ai.panel.untested')
  }
  return t('ai.panel.available')
})

/**
 * N-1（R3）：探针失败的 `title` 只用**固定友好文案** —— 后端/网关原文（可能含 `AI_API_KEY` /
 * provider 名 / KEK / 内部 host 等机制词）不上屏，只留在 `healthError`（诊断字段）与 DEV 日志里。
 * 既有的降级条文案口径同族：用户看到"怎么办"，机制词归诊断。
 */
const healthTitle = computed(() => (healthProbeFailed.value ? t('ai.panel.probeFailedHint') : undefined))

const healthTagType = computed<'success' | 'warning' | 'info'>(() => {
  if (healthBlocksSend.value || healthProbeFailed.value) {
    return 'warning'
  }
  return ai.health === null ? 'info' : 'success'
})

const degradeReason = computed(() => ai.unavailableReason ?? t('ai.panel.degradeUnknown'))

/** 输入始终可编辑；发送闸只拦发送，与建议 chip 共用同一份可执行状态。 */
const canSubmit = computed(
  () => chipsAvailable.value && inputText.value.trim().length > 0 && inputText.value.length <= MESSAGE_MAX_CHARS
)

/**
 * 批① 字数提示（composer 底栏、发送钮左侧）：**剩余**字数 + 到顶/超限态。
 * 四档（判据见下方 composerTier）：常态灰 `剩余 N 字` → 余量 ≤ 500 转琥珀 → 到顶（= 8192，仍可发送）
 * `已达 8192 字上限` → 超限（只可能来自程序化回填，如编辑重跑把超长原文塞回输入框）标红并关发送闸。
 * 前端不留"点了必然 400"的按钮，但绝不替用户截断。
 */
const composerRemaining = computed(() => MESSAGE_MAX_CHARS - inputText.value.length)

const composerOverLimit = computed(() => composerRemaining.value < 0)

/**
 * 档位（M5 播报节流的判据）：一个档位内计数文本逐字变化，但**播报**只在档位跃迁时发生。
 * `normal` 余量充裕 / `near` 余量 ≤ 500 / `limit` 正好到顶（仍可发送）/ `over` 超限（发送闸关闭）。
 */
type ComposerTier = 'normal' | 'near' | 'limit' | 'over'

const composerTier = computed<ComposerTier>(() => {
  if (composerOverLimit.value) {
    return 'over'
  }
  if (composerRemaining.value === 0) {
    return 'limit'
  }
  return composerRemaining.value <= 500 ? 'near' : 'normal'
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

const composerCounterClass = computed(() => {
  switch (composerTier.value) {
    case 'over':
      return 'text-[#c0392b]'
    case 'near':
    case 'limit':
      return 'text-[#b45309]'
    default:
      return 'text-[#8b949e]'
  }
})

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
 * composer tip 共享状态机（demo composer-decisions.html:382-416）：
 * 容器统一处理 Escape / mouseover / focusin / mouseout / focusout，按钮只声明 `data-composer-tip`。
 * `hidden` 只做 display 闸，移除后仍由 TIP_CLASS 的 opacity + group-hover/focus-visible 承接显隐。
 */
type ComposerTipKey = 'send' | 'stop'
const hiddenComposerTips = ref<Set<ComposerTipKey>>(new Set())
const hoveredComposerTip = ref<ComposerTipKey | null>(null)
const focusedComposerTip = ref<ComposerTipKey | null>(null)

function composerTipOwnerOf(target: EventTarget | null): { key: ComposerTipKey; owner: HTMLElement } | null {
  if (!(target instanceof Element)) {
    return null
  }
  const owner = target.closest<HTMLElement>('[data-composer-tip]')
  const key = owner?.dataset.composerTip
  if (!owner || (key !== 'send' && key !== 'stop')) {
    return null
  }
  return { key, owner }
}

function hideComposerTip(key: ComposerTipKey): void {
  if (hiddenComposerTips.value.has(key)) {
    return
  }
  const next = new Set(hiddenComposerTips.value)
  next.add(key)
  hiddenComposerTips.value = next
}

function showComposerTip(key: ComposerTipKey): void {
  if (!hiddenComposerTips.value.has(key)) {
    return
  }
  const next = new Set(hiddenComposerTips.value)
  next.delete(key)
  hiddenComposerTips.value = next
}

function onComposerTipMouseOver(event: MouseEvent): void {
  const hit = composerTipOwnerOf(event.target)
  if (!hit) {
    return
  }
  if (event.relatedTarget instanceof Node && hit.owner.contains(event.relatedTarget)) {
    return
  }
  hoveredComposerTip.value = hit.key
  showComposerTip(hit.key)
}

function onComposerTipMouseOut(event: MouseEvent): void {
  const hit = composerTipOwnerOf(event.target)
  if (!hit) {
    return
  }
  if (event.relatedTarget instanceof Node && hit.owner.contains(event.relatedTarget)) {
    return
  }
  if (hoveredComposerTip.value === hit.key) {
    hoveredComposerTip.value = null
  }
  hideComposerTip(hit.key)
}

function onComposerTipFocusIn(event: FocusEvent): void {
  const hit = composerTipOwnerOf(event.target)
  if (!hit) {
    return
  }
  focusedComposerTip.value = hit.key
  showComposerTip(hit.key)
}

function onComposerTipFocusOut(event: FocusEvent): void {
  const hit = composerTipOwnerOf(event.target)
  if (!hit) {
    return
  }
  if (focusedComposerTip.value === hit.key) {
    focusedComposerTip.value = null
  }
  hideComposerTip(hit.key)
}

/** Escape 只清当前 hover/focus 的 tip，不 preventDefault / stopPropagation（mermaid 仍收得到）。 */
function onComposerTipEscape(event: KeyboardEvent): void {
  if (event.key !== 'Escape') {
    return
  }
  if (hoveredComposerTip.value !== null) {
    hideComposerTip(hoveredComposerTip.value)
  }
  if (focusedComposerTip.value !== null) {
    hideComposerTip(focusedComposerTip.value)
  }
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

function isLastAssistantMessage(message: ChatMessage): boolean {
  return message.role === 'assistant' && ai.messages[ai.messages.length - 1] === message
}

/**
 * 只有当前消息里真实存在**等人**的可操作卡时才显示「等待你操作」；自动前端工具与其余运行阶段都算工作。
 *
 * <p>2026-10-29 复审 M-4：生成式表单「已提交、结局未到」这一段，人这一侧已经没有待办
 * （卡头是「✓ 已提交」、卡体是只读表单、连 chevron 都没有），再报「等待你操作」就是指着用户去做
 * 一件做不了的事 —— 该窗口归**既有的工作中语义**（呼吸点），不新增任何新状态。
 */
function waitingForAction(message: ChatMessage): boolean {
  if (message.role !== 'assistant' || message.done || ai.runPhase !== 'parked' || unconfirmedOf(message)) {
    return false
  }
  return message.parts.some((part) => part.kind === 'tool'
    && part.requiresAction
    && !isFormAwaitingOutcome(part)
    && (part.kind2 === 'CONFIRM' || part.name === GENERATIVE_FORM_TOOL || isSheetPreviewPart(part) || isUploadHandoffPart(part)))
}

function isBreathing(message: ChatMessage): boolean {
  const active = message.streaming || (isLastAssistantMessage(message) && ai.loading && !message.done)
  return message.role === 'assistant'
    && active
    && !message.done
    && !waitingForAction(message)
    && !unconfirmedOf(message)
}

function showTimestamp(message: ChatMessage): boolean {
  if (formatProgressiveTs(message.timestamp) === '') {
    return false
  }
  return message.role !== 'assistant' || (message.done && !message.streaming)
}

/**
 * R-159 #2/#3：元信息行判据 = 有正文（N10 操作行判据沿用）或助手流式中（无正文也要能呼吸）。
 *
 * <p>
 * 批G · S14（真机回归）：「已停止」chip 住在这一行里，所以**被停掉的轮必须有位置** ——
 * GF/确认卡轮的宿主可能只有卡、没有正文段（`streaming` 也已收口），按"有正文或流式中"就会
 * 把 chip 一起藏掉（真机 4/4 缺）。停止是消息级事实，与有没有正文无关。
 * 截断专项：「已截断」chip 同理，`truncated` 是消息级事实（长度截断必有正文，但仍按消息级
 * 判据给位置，避免正文被清空/渲染失败时标记消失）。
 * 批次2：park 等待态 / unconfirmed 中性视图同样自带位置。
 */
function showMetaRow(message: ChatMessage): boolean {
  return message.parts.some((part) => part.kind === 'text' && part.text)
    || isBreathing(message)
    || waitingForAction(message)
    || unconfirmedOf(message)
    || showContinue(message)
    || showRetryTurn(message)
    || message.stopped === true
    || message.truncated === true
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
  return fadingIds.value.has(message.id)
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

watch(() => ai.sessionId, clearStreamMdCache)

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
  if (mermaid.length > 0) {
    void nextTick(() => hydrateMermaid(messageId, mermaid))
  }
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

function markdownHtml(message: ChatMessage, block: Block): string {
  markdownHtmlCalls += 1
  const part = block.parts[0]
  const text = part.kind === 'text' ? part.text : ''
  // aj14d ③：块级稳定 key = 消息 + 段（与文本长度/流式态解耦，作防抖记账与围栏身份的命名空间）
  const blockKey = `${message.id}:${part.seg}`
  // 渲染器注入的复制按钮文案随 UI 语言变化 —— 缓存键必须带 locale，否则切语言会命中旧 HTML。
  const localeKey = currentLocaleKey()

  if (message.streaming) {
    const cached = streamMdCache.get(blockKey)
    if (cached !== undefined && cached.text === text && cached.tick === mdStreamTick.value && cached.locale === localeKey) {
      streamMdCache.delete(blockKey)
      streamMdCache.set(blockKey, cached)
      rememberDegradeNotice(message.id, cached.degradeNotice)
      hydrateMermaidIfNeeded(message.id, cached.mermaid)
      return cached.html
    }
    const { html, mermaid, degradeNotice } = renderMarkdown(text, true, blockKey)
    // 降级提示只在本条首个降级帧由渲染层给出；后续帧沿用首帧文案，避免流式过程把它冲掉。
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

  // 完成态：释放该块的流式最后一帧槽，只走历史 LRU（降级提示随槽带到历史条目）。
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
    // 指纹碰撞：同一 key 的旧条目先退字节，再以原文全等的新条目替换。
    historyMdCacheBytes -= replaced.bytes
  }
  historyMdCache.set(key, entry)
  historyMdCacheBytes += entry.bytes
  evictHistoryEntries()
  hydrateMermaidIfNeeded(message.id, mermaid)
  return html
}

/**
 * R-163B G5 摘要去重（问题1 偏差修正批项 3「工具卡头重名」：去重判据由「全等」扩为「前缀」）。
 *
 * 背景：卡头 mono 槽渲的是**工具中文名**（`toolLabel`），摘要模板（`utils/tool-summaries.ts`）
 * 的前缀往往与它同义/同形 ⇒ 卡头读作「启动导出作业　启动导出作业（任务 #12）」。原判据只挡
 * 「完全相等」，挡不住「同义打头」。
 *
 * 现判据：摘要以工具名为**前缀**时剥掉该前缀（连同紧随的分隔符 `·、，:：` 与空白）；
 * 剥后为空 ⇒ 返回 null（摘要位不渲，只留工具名一行）；剥后非空 ⇒ 渲剩余部分（信息不丢）。
 * 同义族的模板本身也已按同口径收敛（`list_config_defs` / `list_tasks` / `get_workspace_state`
 * 三个模板的「首词 = 工具名」措辞改由参数部分承载），故这三者的摘要只剩参数信息或为空。
 *
 * 修复轮 1（向导工具泄漏）：`export.start` / `download_templates` 等 10 个 FRONTEND 向导工具
 * 有中文展示名（`toolLabel`）但 `tool-summaries.ts` 无 summaryFor 映射，兜底串为
 * 「<原始 name> · <状态词>」—— 展示名前缀剥不到它。故展示名剥壳失败时**再按 part.name 剥一次**；
 * 剥后只剩状态词（`STATUS_WORD_SET`，含兜底用的「待确认」）⇒ 同样返回 null，
 * 由右状态标签（普通 tool pending = 「待执行」）唯一承载状态。
 */
const SUMMARY_PREFIX_SEP = /^[\s·、，,:：\-—]+/
/** 状态词全集（兜底摘要剥掉工具名后只剩状态词 ⇒ 右状态标签已同屏承担，摘要位不再重复）。 */
const STATUS_WORD_SET = computed(() => toolStatusWords())

/**
 * 批次 7A：ToolPart 派生值按**段对象**做 WeakMap 复用。
 *
 * 模板里同一张卡的标题 / 摘要 / 状态 / 参数行 / 卡体行数会在一次渲染中被多处调用；原实现每次都
 * `JSON.parse(args)` / 归一化 / i18n 拼装。段更新时 store 会换新对象（`upsertToolPart` 用 spread），
 * WeakMap 因此随段对象自然失效；另记 locale 标记，切语言时重算一次。
 */
interface LocaleCacheEntry<T> {
  locale: string
  stamp: readonly unknown[]
  value: T
}

const parameterRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<FieldRow[]>>()
const gateRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<FieldRow[]>>()
const conflictRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<ConflictRow[]>>()
const bodyRowsCache = new WeakMap<ToolPart, LocaleCacheEntry<boolean>>()
const outputByViewCache = new WeakMap<ToolPart, LocaleCacheEntry<boolean>>()
const toolSummaryCache = new WeakMap<ToolPart, LocaleCacheEntry<string | null>>()
const cardTitleCache = new WeakMap<ToolPart, LocaleCacheEntry<string>>()
const cardSummaryCache = new WeakMap<ToolPart, LocaleCacheEntry<string>>()
const cardStatusCache = new WeakMap<ToolPart, LocaleCacheEntry<ReturnType<typeof cardStatusOf>>>()
const diagnosticCache = new WeakMap<ToolPart, LocaleCacheEntry<PartDiagnostic | null>>()
const diagnosticTextCache = new WeakMap<ToolPart, LocaleCacheEntry<string | null>>()
const toolDeriveMisses: Record<string, number> = {
  paramRows: 0,
  gateRows: 0,
  bodyRows: 0,
  cardSummary: 0,
  conflictRows: 0,
  outputByView: 0,
  cardTitle: 0,
  cardStatus: 0,
  summary: 0,
  diagnosis: 0
}

function currentLocaleKey(): string {
  return String(locale.value)
}

function sameStamp(left: readonly unknown[], right: readonly unknown[]): boolean {
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
  if (hit !== undefined && hit.locale === localeKey && sameStamp(hit.stamp, stamp)) {
    return hit.value
  }
  toolDeriveMisses[kind] = (toolDeriveMisses[kind] ?? 0) + 1
  const value = compute()
  cache.set(part, { locale: localeKey, stamp, value })
  return value
}

function toolSummaryOrNull(part: ToolPart): string | null {
  return cachedForLocale(toolSummaryCache, part, 'summary', [part.name, part.status, part.args], () => {
    const summary = toolSummary(part)
    const stripPrefix = (prefix: string): string | null => {
      if (prefix === '' || !summary.startsWith(prefix)) {
        return null
      }
      return summary.slice(prefix.length).replace(SUMMARY_PREFIX_SEP, '').trim()
    }
    // 展示名优先；无 summaryFor 映射的向导工具再按原始 name 剥壳（兜底串以前者打头）。
    const rest = stripPrefix(toolLabel(part.name)) ?? stripPrefix(part.name)
    if (rest === null) {
      return summary
    }
    return rest === '' || STATUS_WORD_SET.value.has(rest) ? null : rest
  })
}

/**
 * R-163B G4 → R-165 S5a 项 7 → S5b：v-html 注入内容的点击委托 —— 代码块复制图标钮 / mermaid 卡内
 * 四钮（源码态复制源码、双态 tab 切换、预览复位、全屏查看）。不在注入 HTML 内联脚本；
 * 剪贴板走既有 legacyCopy 回退链。复制成功反馈统一为 v7 口径：钮挂 `is-copied`（绿勾 + 绿字，
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
    const ok = legacyCopy(text)
    flashCopied(copyBtn, ok)
    return
  }
  // R-165 S5b：卡内四钮 —— 复制源码（源码态）/ tab 切换 / 复位 / 全屏（新类名 `mmdc*`，见卡配方）
  const srcCopy = target.closest('.mmdc .mmdc-copy')
  if (srcCopy) {
    const code = srcCopy.closest('.mmdc')?.querySelector('.mmdc-src')
    flashCopied(srcCopy, legacyCopy(code?.textContent ?? ''))
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
   缩放/平移的 CSS 只存在于 Tailwind 任意值类（`[transform:scale(var(--z,1))]` /
   `[transform:translateX(var(--px,0px))_translateY(var(--py,0px))]`），JS 只写这三个变量值
   （demo 同机制：样式声明在工具类里，运行期只喂变量）。 */

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

/** 预览态容器（demo：内联横滚保留 + 拖拽手型；缩放/平移走 transform 层，不用 overflow 平移）。
 *  2A 批（裁决 §14.4 表单 / 组合族）：限高改双约束 `min(320px,45vh)`（可视图给高一档）。 */
const MMD_PREVIEW_PANE_CLASS = `mmdc-pane mmdc-pv max-h-[min(320px,45vh)] overflow-auto bg-white cursor-grab select-none ${SCROLLBAR_CLASS}`
const MMD_PAN_CLASS = 'mmdc-pan [transform:translateX(var(--px,0px))_translateY(var(--py,0px))]'
const MMD_STAGE_CLASS = 'mmdc-stage w-max origin-top-left [transform:scale(var(--z,1))]'
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

/** mermaid 产物归一：剥掉 mermaid 自带的 `width="100%"` / `max-width`（会与缩放层、横滚层打架），
 *  按 viewBox 宽高比取定宽（demo v7.7 `openOv` 口径：宽图 980px / 方图 560px）—— 超宽图因此在
 *  260px 预览容器里恒可内联横滚，在查看器里也有一致的基准尺寸。 */
function prepareMermaidSvg(svg: string, extra = ''): string {
  const box = (/viewBox="([^"]*)"/.exec(svg)?.[1] ?? '0 0 10 10').split(/[\s,]+/)
  const ratio = (parseFloat(box[2]) || 1) / (parseFloat(box[3]) || 1)
  const cls = `block ${ratio >= 2 ? 'w-[980px]' : 'w-[560px]'} max-w-none h-auto${extra === '' ? '' : ` ${extra}`}`
  return svg.replace(/<svg\b([^>]*)>/, (_match: string, attrs: string) =>
    `<svg${attrs.replace(/\s(?:class|style|width|height)="[^"]*"/g, '')} class="${cls}">`
  )
}

/** 卡结构（demo v7.7 裁决区 12 右栏逐条）：卡头 = ◈ + 名称 + 分段 tab + 右端图标钮组；
 *  预览态 = 260px 限高横滚容器（pan/scale 两层 transform）；源码态 = 200px 内滚 mono 盒 + 复制源码钮。
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

/** 预览态内联交互 API（按卡记：复位钮在委托里取用；卡随 v-html 重建，用 WeakMap 免泄漏）。 */
const mermaidInlineReset = new WeakMap<HTMLElement, () => void>()
/** 两态各自记住的滚动位置（demo 口径：切回目标态时恢复该态自己的 scroll）。 */
const mermaidPaneScroll = new WeakMap<HTMLElement, { left: number; top: number }>()

/**
 * 预览态内联交互（把 demo 07 那套搬到内联容器上）：
 * - 滚轮缩放：锚定指针位置，0.5×–4×；
 * - 拖拽平移：transform translate（mousedown 记起点 + 位移），不用 overflow 滚动；
 * - 越界钳制：内容不得被推出左上角（px/py 合法区间 = [client − z×自然尺寸, 0]）；
 * - 容器自身滚动折进 transform（视觉不变）⇒ 拖拽/缩放只有一个位置来源；
 * - 每个 pane 的 scroll 实时记账，切 tab 时恢复目标态自己的位置。
 */
function initMermaidInline(card: HTMLElement): void {
  for (const pane of card.querySelectorAll<HTMLElement>('.mmdc-pane')) {
    pane.addEventListener('scroll', () => {
      mermaidPaneScroll.set(pane, { left: pane.scrollLeft, top: pane.scrollTop })
    })
  }
  const box = card.querySelector<HTMLElement>('.mmdc-pv')
  const pan = card.querySelector<HTMLElement>('.mmdc-pan')
  const stage = card.querySelector<HTMLElement>('.mmdc-stage')
  if (!box || !pan || !stage) {
    return
  }
  let naturalW = 0
  let naturalH = 0
  let z = 1
  let px = 0
  let py = 0
  const measure = (): void => {
    naturalW = stage.offsetWidth
    naturalH = stage.offsetHeight
  }
  const apply = (): void => {
    stage.style.setProperty('--z', String(z))
    pan.style.setProperty('--px', `${px}px`)
    pan.style.setProperty('--py', `${py}px`)
    const readout = card.querySelector('.mmdc-ipct')
    if (readout) {
      readout.textContent = `${Math.round(z * 100)}%`
    }
  }
  const clamp = (): void => {
    let maxX = box.clientWidth - naturalW * z
    let maxY = box.clientHeight - naturalH * z
    if (maxX > 0) {
      maxX = 0
    }
    if (maxY > 0) {
      maxY = 0
    }
    px = px > 0 ? 0 : px < maxX ? maxX : px
    py = py > 0 ? 0 : py < maxY ? maxY : py
  }
  const absorb = (): void => {
    if (box.scrollLeft !== 0 || box.scrollTop !== 0) {
      px -= box.scrollLeft
      py -= box.scrollTop
      box.scrollLeft = 0
      box.scrollTop = 0
    }
  }
  const setZoom = (next: number, vx: number, vy: number): void => {
    absorb()
    const target = Math.min(MMD_Z_MAX, Math.max(MMD_Z_MIN, next))
    const sx = (vx - px) / z
    const sy = (vy - py) / z
    px = vx - target * sx
    py = vy - target * sy
    z = target
    clamp()
    apply()
  }
  measure()
  apply()
  mermaidInlineReset.set(card, () => {
    z = 1
    px = 0
    py = 0
    box.scrollLeft = 0
    box.scrollTop = 0
    apply()
  })
  box.addEventListener(
    'wheel',
    (event) => {
      event.preventDefault()
      measure()
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
    measure()
    absorb()
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

/** tab 切换（两态互斥 + aria-selected 同步）→ 目标态恢复**自己**记住的滚动位置（demo 二选一登记的「各自记住」）。 */
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
  const pane = card.querySelector<HTMLElement>(`.mmdc-pane[data-pane="${mode}"]`)
  if (!pane) {
    return
  }
  const saved = mermaidPaneScroll.get(pane) ?? { left: 0, top: 0 }
  requestAnimationFrame(() => {
    pane.scrollLeft = saved.left
    pane.scrollTop = saved.top
  })
}

/** 复位（预览态卡头钮）：缩放 1× + 位移归零 + 两态滚动归零。 */
function resetMermaidInline(card: Element | null): void {
  if (!(card instanceof HTMLElement)) {
    return
  }
  mermaidInlineReset.get(card)?.()
  for (const pane of card.querySelectorAll<HTMLElement>('.mmdc-pane')) {
    pane.scrollLeft = 0
    pane.scrollTop = 0
    mermaidPaneScroll.set(pane, { left: 0, top: 0 })
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
let mmdViewerCopyTimer: number | null = null

function applyMmdViewer(): void {
  mmdViewerZoomRef.value?.style.setProperty('--z', String(mmdViewerZ))
  const pan = mmdViewerPanRef.value
  if (pan) {
    pan.style.setProperty('--px', `${mmdViewerX}px`)
    pan.style.setProperty('--py', `${mmdViewerY}px`)
  }
  mmdViewerPct.value = Math.round(mmdViewerZ * 100)
}

/** 指针锚定缩放（demo 07）：pan 层已居中 ⇒ 锚点坐标 = 指针相对 stage 中心的偏移。 */
function setMmdViewerZoom(next: number, anchorX = 0, anchorY = 0): void {
  const z = Math.min(MMD_Z_MAX, Math.max(MMD_Z_MIN, next))
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
  mmdViewerZ = 1
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
  mmdViewerZ = 1
  mmdViewerX = 0
  mmdViewerY = 0
  mmdViewerPct.value = 100
  mmdViewerOpen.value = true
  void nextTick(() => {
    applyMmdViewer()
    mmdViewerStageRef.value?.focus()
  })
}

function closeMmdViewer(): void {
  mmdViewerOpen.value = false
}

/** 查看器「复制源码」：绿勾反馈与卡内复制钮同一机制（`is-copied` + COPY_STATE_CLASS），状态走响应式。 */
function copyMmdViewerSource(): void {
  mmdViewerCopied.value = legacyCopy(mmdViewerSource.value)
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
    document.body.classList.add('overflow-hidden')
    return
  }
  window.removeEventListener('keydown', onMmdViewerKeydown)
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

/** 把一个 mermaid 源码块替换成卡片（`bubble.contains` 判活：v-html 重建后旧槽位不再回填）。 */
async function renderMermaidSlot(bubble: Element, code: Element, source: string): Promise<void> {
  const svg = await renderMermaidSvg(source)
  if (!bubble.contains(code)) {
    // 期间又渲了一帧（v-html 重建）：旧槽位作废，避免把卡塞进已脱离的节点
    return
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
  initMermaidInline(card)
  code.parentElement?.replaceWith(card)
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
  const bubble = msgWrapRef.value?.querySelector(`[data-testid="msg-bubble-${messageId}"]`)
  if (!bubble) {
    return
  }
  const slots = Array.from(bubble.querySelectorAll('pre > code.language-mermaid'))
  // 问题1 偏差修正批 aj14d ③：按稳定 key（`data-fence-key`）绑槽位 —— 位置下标只作兜底
  // （渲染与 hydrate 之间若又来一帧，位置会漂移，key 不会）。
  const byKey = new Map<string, Element>()
  for (const slot of slots) {
    const key = slot.parentElement?.getAttribute('data-fence-key') ?? ''
    if (key !== '') {
      byKey.set(key, slot)
    }
  }
  for (const item of blocks) {
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

/** 批4④ A5'：消息 → 渲染块序列（groupParts 纯函数；parts 为响应式数组，帧到达即重算）。
 *  收口修 F1：传 m.id 作消息命名空间前缀，防跨消息 blockId 碰撞（红队 M-1）。 */
function blocksOf(message: ChatMessage): Block[] {
  return groupParts(message.parts, message.id)
}

/**
 * 块展开态三态判定（D4/D6/D-C6）：requiresAction 组强制展开（手动收起不生效）；
 * 否则 expandedBlocks[id] 显式值优先（用户手动展开永不自动收起 / 手动收起不被自动打开）；
 * 未触碰（键缺省）回落 defaultExpanded。
 *
 * 2A 批（裁决 2026-10-17 §15 K12：「回合结束才收」）：已决只读卡（多态全集）的折叠是
 * **回合级时序** —— 决策瞬间不折叠，等本轮结束（新建消息 / 回合文本收尾）后才收成卡头行。
 * 判据 = `turnStillOpen`（**本轮未终态**，见下）；不满足才走 defaultExpanded
 * （r15：多态 → 折叠）。用户手动触碰过的卡（expandedBlocks 有显式值）永远优先，不受时序影响。
 */
function isBlockExpanded(block: Block): boolean {
  const manual = ai.expandedBlocks[block.id]
  if (manual === undefined && turnStillOpen(block) && readOnlyTerminalCard(block)) {
    return true
  }
  return resolveBlockExpanded(block, manual)
}

/**
 * 恢复期起点的「本轮是否仍在收流」采样（2026-10-29 返修 I-1′）。
 *
 * <p>`restoringRecovery` 只说"恢复在途"，不说**恢复从哪里开始**：
 * <ul>
 * <li>刷新首屏：镜像正在落屏，进入时 `loading=false` ⇒ 恢复期必须不展开（对过去的现场做实时渲染）；</li>
 * <li>流中手动重载 / 自动恢复重试：这一轮本来就在收流，进入时 `loading=true` ⇒ 恢复期必须保持展开，
 *     否则卡会"折一次→展开"，两条自动恢复 attempt 之间（标志复位、`loading` 仍真）再折一次，
 *     1~1.5s 的失败恢复窗口里翻两轮（BASE 是保持展开）。</li>
 * </ul>
 *
 * <p>口径 = 只在 `restoringRecovery` **由假变真那一刻**采样 `ai.loading`。用 `flush:'sync'` 与
 * store 的置位同 tick，避免被后续渲染/批处理挪到别的现场；挂载时已处于恢复期 ⇒ 不触发采样，
 * 采样保持 `false`（沿用"恢复期不展开"）。恢复结束即清采样，下一次恢复重新取现场。
 */
const recoveryStartedWhileStreaming = ref(false)

watch(
  () => ai.restoringRecovery,
  (active, wasActive) => {
    if (active && !wasActive) {
      recoveryStartedWhileStreaming.value = ai.loading
    } else if (!active) {
      recoveryStartedWhileStreaming.value = false
    }
  },
  { flush: 'sync' }
)

/**
 * 本轮是否仍未终态（2026-10-29 ToC 呈现减负批：判据由「只认 `loading`」放宽到「本轮未终态」；
 * 同日「导出整屏闪」返修：恢复期按**进入时刻**这一轮是否还在收流来判）。
 *
 * <p>为什么不能只看 `loading`：B1「挂起即断」之后，等人决策的一轮是 `loading=false && suspended=true`
 * （`runPhase='parked'`）；点批准的那一刻段立刻切只读，而此时连接可能还没建起来 ——
 * 只看 `loading` 就会「批准瞬间折叠 → 重连后展开」闪一下。
 *
 * <p><b>为什么不直接写 `runPhase ≠ 'idle'`</b>：那个条件把 `recovering` 也算进来，而
 * `loadHistory` / `retryRestoreSnapshot` / `returnToActiveRun` 在 `restoringRecovery` 期间
 * 就**同步**把镜像消息落屏 —— 刷新/重新载入时，末条消息里的已决只读卡会先被强制展开、
 * HTTP 回来再折叠，正是本批要消掉的那类闪动。批准瞬间的闪动另有**决策在途 / GF 提交在途**
 * 两条证据覆盖，不依赖 `recovering`。
 *
 * <p><b>恢复期为什么要看采样</b>：只写 `loading && !restoringRecovery` 会在"流中手动重载"和
 * "自动恢复两次 attempt 之间"把卡折一次再展开（见 {@link recoveryStartedWhileStreaming}）。
 * 采样为真（进入时确实在收流）⇒ 整个恢复窗口按"仍在收流"处理；采样为假（刷新首屏）⇒ 恢复期不展开。
 *
 * 终态判据 = 仍在收流 ∨ 挂起等人，再加两条在途证据：
 * <ol>
 * <li>`ai.suspended || (ai.loading && (!ai.restoringRecovery || 恢复期采样为真))`；</li>
 * <li>该卡已有**决策在途**（`gateSubmissions` 有值：点过批准/拒绝、结局帧还没到）；</li>
 * <li>该卡是生成式表单且**提交在途**（结局帧未到的只读锁定态，见 `readOnlyLike`）。</li>
 * </ol>
 * 仍要求该卡属于**末条消息**：历史卡不因"新轮还在跑"被展开。
 */
function turnStillOpen(block: Block): boolean {
  const last = ai.messages[ai.messages.length - 1]
  if (last === undefined || last.id !== block.messageId) {
    return false
  }
  const streamingNow = ai.loading
    && (!ai.restoringRecovery || recoveryStartedWhileStreaming.value)
  if (ai.suspended || streamingNow) {
    return true
  }
  return block.parts.some((part) => part.kind === 'tool'
    && (ai.gateSubmissionOf(part.toolCallId) !== null || isFormAwaitingOutcome(part)))
}

/**
 * 呈现层的「只读卡」判据 = 已决只读态 ∪ GF 提交后在途。
 *
 * <p>为什么单起一层、不改 `isReadOnlyCard`：后者同时被 store 侧派生与纯函数状态机复用，
 * 而「已提交、结局未到」只是**呈现层**要固定的形态（同一张表单锁定 + 卡头「✓ 已提交」），
 * 不是新的数据终态。本判据只在本组件内使用，范围最小。
 */
function readOnlyLike(part: ToolPart): boolean {
  return isReadOnlyCard(part) || isFormAwaitingOutcome(part)
}

/** 已决只读卡（多态全集）—— 唯一受「回合结束才收」时序影响的卡型。 */
function readOnlyTerminalCard(block: Block): boolean {
  return block.parts.some((part) => part.kind === 'tool' && readOnlyLike(part))
}

/** 组头/思考链头点击：写 store 显式态（三态记录；requiresAction 组的强制展开由 resolveBlockExpanded 兜底）。 */
function toggleBlock(block: Block): void {
  ai.setBlockExpanded(block.id, !isBlockExpanded(block))
}

/**
 * 批次 7A → 2026-10-29 返修（导出过程整屏闪）：消息 / 块渲染边界。
 *
 * `v-memo` 的依赖必须**只覆盖本消息模板真实读取的值**。旧实现把 `ai.loading` / `ai.runPhase` /
 * `ai.activeRunId` / 末条消息 id 这些**全局**现场并进了每一条消息行：任何一次收流开始/结束、
 * 挂起、恢复或换轮都会让**整列**消息行一起失效（长会话 = 全量重渲正文 + 卡片派生重算），
 * 在「导出CNY货币字典」这类一轮里密集出帧的场景表现为整屏闪一下。
 *
 * 现行口径：全局现场只以**本消息的判定结果**入场（`isBreathing` / `waitingForAction` /
 * `isBlockExpanded` 等）—— 结果没变 ⇒ 本行不失效（历史消息一格不动）；结果变了 ⇒ 本行照常
 * 更新（末条消息的呼吸点、挂起等待、卡展开态、继续/重试入口都在）。
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
      part.returnedChars,
      part.truncatedReason
    )
    return deps
  }
  if (part.kind === 'text') {
    deps.push(part.text, part.streaming, part.truncated, part.noticeCode, part.originalChars, part.returnedChars, part.truncatedReason)
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
    part.timeoutSeconds,
    part.irreversible,
    part.truncated,
    part.noticeCode,
    part.originalChars,
    part.returnedChars,
    part.truncatedReason,
    readOnlyLike(part),
    cardTypeOf(part),
    ai.gateSubmissionOf(part.toolCallId),
    ai.gateAckOf(part.toolCallId),
    ai.uiUndoToolCallId,
    ai.uiUndoEntry,
    ai.sheetApplied,
    ai.sessionId
  )
  if (part.name === GENERATIVE_FORM_TOOL) {
    // 队列面只入**标量**（本卡位次 + 队列总数）：整条 `queuedForms` 数组身份入 memo，
    // 会让任意一次入队/出队把全部历史 GF 行一起失效。
    deps.push(formQueuePosition(part.toolCallId), ai.queuedForms.length)
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
    deps.push(ai.activeUploadHandoff, uploadHandoffStatus(part), uploadHandoffAccept(part), uploadHandoffLabel(part), uploadHandoffReason(part))
  }
  if (isSheetPreviewPart(part)) {
    deps.push(sheetPreviewFor(part))
  }
  if (part.requiresAction) {
    deps.push(runExpiryView.value, runExpiryNowMs.value)
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

function messageMemo(message: ChatMessage, index: number): unknown[] {
  const deps: unknown[] = [
    message.id,
    message.role,
    message.parts.length,
    message.parts,
    message.streaming,
    message.done,
    message.stopped,
    message.truncated,
    message.synthetic,
    message.timestamp,
    message.pendingCall,
    message.pendingCall?.status,
    message.recoveryGap,
    message.errorCard,
    message.errorCard?.variant,
    message.errorCard?.title,
    message.errorCard?.summary,
    message.errorCard?.guide,
    message.errorCard?.code,
    message.errorCard?.detail,
    message.errorCard?.action,
    index,
    msgGapClass(index),
    locale.value,
    syntheticOf(message),
    unconfirmedOf(message),
    // 元信息行 / 恢复入口的**判定结果**（各自已经把 loading / runPhase / 末条身份算进去了）。
    // 全局现场本身不入 memo：否则历史行跟着每一次收流翻转一起失效（整屏闪根因）。
    showMetaRow(message),
    showTimestamp(message),
    waitingForAction(message),
    isBreathing(message),
    isFading(message),
    showContinue(message),
    showRetryTurn(message),
    feedbackOf(message.id),
    isMsgCopied(message.id),
    copyState.value?.id === message.id ? copyState.value?.ok ?? null : null,
    userMsgOverflowed.value.has(message.id),
    userMsgExpanded.value.has(message.id),
    degradeNotices.value[degradeNoticeKey(message.id)] ?? null
  ]
  for (const block of blocksOf(message)) {
    deps.push(block.id, block.kind, isBlockExpanded(block))
    // 段级依赖必须并进消息级 memo：否则消息行被 v-memo 跳过时，卡内 v-memo 根本没有机会更新。
    for (const part of block.parts) {
      deps.push(...partMemo(part, block))
    }
  }
  return deps
}

/**
 * S6 批1（v7.10 ajv11 / 场景 19-e）→ 卡片三类型统一：
 * - 已决 HITL / GF 卡体恒在（门卡字段表 / GF 同一张表单），卡头恒可折叠；
 * - 普通工具卡：展开体渲染后 ≥ 1 行才可点；0 行 ⇒ disabled、无 chevron、无 hover、点击 no-op；
 * - requiresAction 待决 HITL/GF：强制展开、无 chevron，卡头**仍禁点**（放行/拒绝走门内双钮）。
 */
function bodyRowsOf(part: ToolPart): boolean {
  return cachedForLocale(bodyRowsCache, part, 'bodyRows', [part.args, part.result, part.reason, part.resultView, part.requiresAction], () => hasBodyRows(part))
}

function cardHeadEnabled(part: ToolPart): boolean {
  if (readOnlyLike(part)) {
    return true
  }
  return !part.requiresAction && bodyRowsOf(part)
}

/** 卡头 disabled 判据（与 enabled 互为反面，模板 `:disabled` / cursor / hover 三处同源）。 */
function cardHeadDisabled(part: ToolPart): boolean {
  return !cardHeadEnabled(part)
}

/** 展开体是否渲染：普通工具卡有内容（或待动作卡强制展开）才渲；已决只读态卡体恒在。 */
function cardBodyShown(part: ToolPart): boolean {
  return bodyRowsOf(part)
}

/** AI 表格操作专项：待用户确认的写预览段（replace_values / update_rows / normalize commit=true）。 */
function isSheetPreviewPart(part: ToolPart): boolean {
  return part.kind2 === 'FRONTEND' && part.requiresAction && SHEET_PREVIEW_TOOLS.has(part.name)
}

// ── AI 可视可操作专项：上传交接卡 + 操作条 + 撤销 ─────────────────────────────

/** 上传交接段（唯一的人围栏工具；`ui_request_upload`）。 */
function isUploadHandoffPart(part: ToolPart): boolean {
  return part.kind2 === 'FRONTEND' && part.requiresAction && UI_HANDOFF_TOOLS.has(part.name)
}

/**
 * 交接卡状态：**与「可操作性」同源** —— 一律取 store 的 `uploadHandoffAvailability()`
 * （活动交接存在 ⇒ 其档；无活动交接 / 交接不属于本卡 ⇒ missing）。
 *
 * <p>
 * L-85：旧实现活动交接存在时直接回 `handoff.status`，从不看区域是否还在，
 * 于是用户离开导入向导页后卡面仍是 `waiting`、主按钮仍可点（点下去旧实现还谎报成功）。
 * `handoff.status` 只在**建卡 / 刷新重建**那一刻算一次，是"当时"的分流；区域存活是随页面
 * 变化的，必须在渲染时重算。`uploadHandoffAvailability()` 同时覆盖 degraded（浏览器不支持
 * picker）与 missing（区域已不在本页 / 只读页），与本卡三态一一对应。
 */
function uploadHandoffStatus(part: ToolPart): 'waiting' | 'degraded' | 'missing' | 'unavailable' {
  const handoff = ai.activeUploadHandoff
  if (!handoff || handoff.toolCallId !== part.toolCallId) {
    return 'missing'
  }
  return ai.uploadHandoffAvailability() ?? 'missing'
}

function uploadHandoffAccept(part: ToolPart): string[] | null {
  const handoff = ai.activeUploadHandoff
  return handoff && handoff.toolCallId === part.toolCallId ? handoff.accept : null
}

function uploadHandoffReason(part: ToolPart): string | null {
  const handoff = ai.activeUploadHandoff
  return handoff && handoff.toolCallId === part.toolCallId ? handoff.reason : null
}

/** 入口的可读标签（取注册区域里该 ref 的 aria-label / 文案；取不到用兜底文案）。 */
function uploadHandoffLabel(part: ToolPart): string {
  const handoff = ai.activeUploadHandoff
  if (!handoff || handoff.toolCallId !== part.toolCallId || !handoff.ref) {
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

/** 一行式：`AI 改动 120 格（可撤销，深度 3 中第 1 层）`。 */
function appliedLine(summary: SheetAppliedSummary): string {
  return t('sheet.applied.line', { cells: summary.cells, layer: summary.layer })
}

/** 该段对应的本地预览计划（无 ⇒ 面板渲染“预览已失效 + 放弃”）。 */
function sheetPreviewFor(part: ToolPart) {
  return ai.activePreview && ai.activePreview.toolCallId === part.toolCallId ? ai.activePreview : null
}

function onSheetPreviewApply(acknowledged: boolean): void {
  void ai.confirmWritePreview(acknowledged)
}

function onSheetPreviewCancel(): void {
  void ai.cancelWritePreview()
}

function onSheetPreviewAbandon(toolCallId: string): void {
  void ai.abandonStalePreview(toolCallId)
}

/** chevron 是否渲染：待决 HITL/GF 强制展开 ⇒ 无；只读卡（含 GF 提交在途）恒有；普通工具卡有卡体才有。 */
function cardChevronShown(part: ToolPart): boolean {
  if (part.requiresAction) {
    return false
  }
  return readOnlyLike(part) || cardBodyShown(part)
}

/** 卡片外壳：只读卡保留 rocard 容器锚，普通卡保留原间距。 */
function cardShellClass(part: ToolPart): string {
  return readOnlyLike(part)
    ? 'rocard block border border-solid border-[#e3e6ea] bg-white rounded-[10px] overflow-hidden mb-3.5'
    : 'mt-1.5 border border-solid border-[#e3e6ea] rounded-[10px] bg-white overflow-hidden mb-2'
}

/** 卡容器 testid（既有锚：hitl-receipt / pending-card-* / tool-card-*）。 */
function cardTestId(part: ToolPart): string {
  if (readOnlyLike(part)) {
    return 'hitl-receipt'
  }
  return part.requiresAction ? `pending-card-${part.toolCallId}` : `tool-card-${part.toolCallId}`
}

/** 卡头 testid（既有锚：ro-toggle-* / block-toggle-*）。 */
function cardToggleTestId(part: ToolPart, block: Block): string {
  return readOnlyLike(part) ? `ro-toggle-${part.toolCallId}` : `block-toggle-${block.id}`
}

/** 右状态标签 testid（只读卡保留 ro-badge-*；其余 card-status-*）。 */
function cardStatusTestId(part: ToolPart): string {
  return readOnlyLike(part) ? `ro-badge-${part.toolCallId}` : `card-status-${part.toolCallId}`
}

/** 摘要 testid（只读卡保留 ro-summary-*；普通卡保留 tool-summary-*）。 */
function cardSummaryTestId(part: ToolPart): string {
  return readOnlyLike(part) ? `ro-summary-${part.toolCallId}` : `tool-summary-${part.toolCallId}`
}

/** 统一卡头 class：三类型同结构（36.4px = py 8+8 + 12px×1.7 标题行）。 */
function cardHeadClass(part: ToolPart): string {
  return [
    'flex w-full items-center gap-[6px] px-[10px] py-[8px] border-0 bg-transparent text-left font-[inherit] whitespace-nowrap transition-colors duration-[150ms]',
    readOnlyLike(part) ? 'ro-hd' : '',
    cardHeadDisabled(part) ? 'cursor-default' : 'cursor-pointer hover:bg-[#f6f7f9]'
  ].filter(Boolean).join(' ')
}

/**
 * 可观测性（2026-10-29 返修 M-1）：GF 卡的**渲染次数**（按 toolCallId）—— 回归测试用，零 UI 影响。
 * `cardTitle()` 每张卡每次渲染恰好调用一次，是"这张卡这一帧有没有重渲"的最小探针。
 */
const gfCardRenders = new Map<string, number>()

/**
 * 卡头标题。
 * - form = 表单业务标题（FormRenderer 内头已抑制，标题上提）；
 * - CONFIRM 带 `preflight.target` ⇒ **业务化标题**（demo 场景一/二/三 1:1）：
 *   `待你确认：<类型> 任务 #<id>『<title>』`（待决）/ `<类型> 任务 #<id>『<title>』`（只读）；
 * - `create_task` 的预检 target **没有任务号**（工具语义如此：任务在批准后才存在，预检只判
 *   `type/title` 是否可用，见 `ToolPreflightService#inspectCreate`）⇒ 走「创建」句式，
 *   不得套用 `任务 #<id>` 模板（旧实现在此渲染出 `任务 #undefined`）；
 * - 其余（无 preflight 的旧帧 / 普通工具）⇒ 回落工具中文名（现形态不变）。
 */
function cardTitle(part: ToolPart): string {
  if (part.name === GENERATIVE_FORM_TOOL) {
    gfCardRenders.set(part.toolCallId, (gfCardRenders.get(part.toolCallId) ?? 0) + 1)
  }
  return cachedForLocale(cardTitleCache, part, 'cardTitle', [part.name, part.kind2, part.status, part.decision, part.requiresAction, part.preflight, part.preflight?.target, part.args], () => {
    if (cardTypeOf(part) === 'form') {
      return cardFormSpec(part)?.title?.trim() || t('ai.gf.title')
    }
    const target = cardTypeOf(part) === 'hitl' ? part.preflight?.target : undefined
    if (target) {
      const createBusiness = createTaskBusinessTitle(target)
      if (createBusiness !== null) {
        return isReadOnlyCard(part) ? createBusiness : t('ai.panel.confirmTitle', { business: createBusiness })
      }
      const business = `${labelWord(TASK_TYPE_LABELS, target.type)} 任务 #${target.taskId}『${target.title}』`
      return isReadOnlyCard(part) ? business : t('ai.panel.confirmTitle', { business })
    }
    return toolLabel(part.name)
  })
}

/**
 * `create_task` 的业务化标题片段；非 create_task（target 带任务号）⇒ `null`（走既有模板）。
 *
 * 判据 = **target 里没有 taskId**：`ToolPreflightService#inspectCreate` 的 target 只有
 * `type/title`（创建后才有任务号），四个 `start_*` 的 target 恒带 `taskId`。
 * 文案随 UI 语言（类型词用建任务对话框同一份 `views.tasks.*Type`）；标题为空时用无标题句式，
 * 不留下 `『』` 空壳。
 */
function createTaskBusinessTitle(target: ToolPreflight['target']): string | null {
  if (target.taskId !== undefined && target.taskId !== null && `${target.taskId}` !== '') {
    return null
  }
  const typeKey = target.type === 'EXPORT'
    ? 'views.tasks.exportType'
    : target.type === 'IMPORT'
      ? 'views.tasks.importType'
      : null
  if (typeKey === null) {
    return null
  }
  const title = (target.title ?? '').trim()
  return title === ''
    ? t('ai.panel.createTaskBusinessUntitled', { type: t(typeKey) })
    : t('ai.panel.createTaskBusiness', { type: t(typeKey), title })
}

/** 待填写 GF 摘要：必填项计数（无必填 / 无 schema ⇒ 空串）。 */
function formRequiredSummary(part: ToolPart): string {
  const required = cardFormSpec(part)?.fields.filter((field) => field.required).length ?? 0
  return required > 0 ? t('ai.panel.requiredCount', { count: required }) : ''
}

/**
 * 卡头摘要（中性色，不承载状态）：
 * - form：待填写 = 必填计数；已提交（含提交在途）= submittedValues；已取消 = 未提交说明（F9 不回读残留值）；
 * - hitl 且已业务化标题（有 preflight.target）⇒ **空**（demo 卡头只有业务标题，标题已说清，
 *   摘要再报「工具名（任务 #N）」= 同屏重复）；
 * - 其余 tool/hitl：沿用工具摘要映射（与待决态同一份，随生命周期不变）。
 */
function cardSummary(part: ToolPart): string {
  return cachedForLocale(cardSummaryCache, part, 'cardSummary', [part.name, part.kind2, part.status, part.decision, part.requiresAction, part.submittedValues, part.args, part.preflight], () => {
    if (cardTypeOf(part) === 'form') {
      if (!readOnlyLike(part)) {
        return formRequiredSummary(part)
      }
      return readOnlyStateOf(part) === 'cancelled' ? t('ai.panel.formCancelledSummary') : generativeFormSummary(part)
    }
    if (cardTypeOf(part) === 'hitl' && part.preflight?.target) {
      return ''
    }
    return toolSummaryOrNull(part) ?? ''
  })
}

/** 右状态标签底座（demo r13/r14：9.5px / leading 1.7 / px-5 / 圆角 5 / 描边）。 */
const CARD_STATUS_BASE = 'flex-none text-[9.5px] leading-[1.7] px-[5px] rounded-[5px] border border-solid whitespace-nowrap max-w-[104px] truncate'
const CARD_STATUS_TONE: Record<CardTone, string> = {
  primary: 'text-[#2563eb] bg-[#eff4ff] border-[#cddcff]',
  success: 'text-[#1a7f45] bg-[#edf7f0] border-[#bfe3cd]',
  danger: 'text-[#c0392b] bg-[#fdf1ef] border-[#e3e6ea]',
  warning: 'text-[#b45309] bg-[#fffbeb] border-[#fde68a]',
  neutral: 'text-[#57606a] bg-[#f6f7f9] border-[#e3e6ea]'
}

function cardStatusView(part: ToolPart): ReturnType<typeof cardStatusOf> {
  // `submittedValues` 进 guard：GF「提交在途」的状态由它判定（写入即从「待填写」转「✓ 已提交」），
  // 漏掉这一项会让同一段上的旧缓存把卡头状态钉死。
  return cachedForLocale(cardStatusCache, part, 'cardStatus', [part.name, part.kind2, part.status, part.decision, part.requiresAction, part.submittedValues], () => cardStatusOf(part))
}

function cardStatusClass(part: ToolPart): string {
  return `${CARD_STATUS_BASE} ${CARD_STATUS_TONE[cardStatusView(part).tone]}`
}

function cardStatusLabel(part: ToolPart): string {
  return cardStatusView(part).label
}

/**
 * 卡头点击唯一入口：不可点卡（待动作卡 / 展开体 0 行）走 no-op —— token 上 `disabled` 已拦原生点击，
 * 此处对程序化触发（测试 `.trigger('click')` 不受 disabled 影响）再做一次守卫。
 */
function onCardHeadClick(block: Block, part: ToolPart): void {
  if (!cardHeadEnabled(part)) {
    return
  }
  toggleBlock(block)
}

/** 普通工具卡参数行（字段表）：S6 起 args JSON 盒由 label-value 行替代（值可读化，敏感不掩码）。 */
function paramRows(part: ToolPart): FieldRow[] {
  return cachedForLocale(parameterRowsCache, part, 'paramRows', [part.args], () => argRows(part))
}

/** 发布冲突明细行（N8）：从段 reason 解析出 `defCode#rowKey：message` 明细 → 表格数据。 */
function conflictRows(part: ToolPart): ConflictRow[] {
  return cachedForLocale(conflictRowsCache, part, 'conflictRows', [part.reason], () => parsePublishConflicts(part.reason))
}

/**
 * 「输出」区是否由视图通道接管（S6 批2 **迁移步④**，设计稿 §7.1-④）。
 *
 * 判据 = {@link isRenderableView}：同一份真值在卡面上只报一次 —— 视图可渲则原文盒让位
 * （原文仍是 `part.result`，模型侧与复制路径不受影响）；视图渲不出 / 段上没有视图 ⇒ 原文盒照旧。
 * `data.ref` 的求值域是 `part.result` 原文（与渲染器同源），所以两处传同一份文本。
 */
function outputByView(part: ToolPart): boolean {
  return cachedForLocale(outputByViewCache, part, 'outputByView', [part.resultView, part.result], () => (
    part.resultView != null && isRenderableView(part.resultView, part.result ?? null)
  ))
}

/** 截断文案的公共字段（ToolPart / TextPart / ReasoningPart 同形）。 */
interface TruncationCarrier {
  noticeCode?: ToolPart['noticeCode']
  originalChars?: number
  returnedChars?: number
  truncatedReason?: string | null
}

function containsCjk(text: string): boolean {
  return /[\u4e00-\u9fff]/.test(text)
}

function isEnglishLocale(): boolean {
  return String(locale.value).toLowerCase().startsWith('en')
}

/**
 * A10 R-15：截断角标 hover 文案。
 *
 * 新帧按 `noticeCode` + `originalChars/returnedChars` 用当前 locale 拼装；旧帧（无 code）
 * 回退 `truncatedReason`。**en 界面遇到旧帧的中文原因时改用 locale 兜底**，避免中文上屏；
 * zh 仍原样保留旧文案。
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
  const legacy = part.truncatedReason
  if (legacy) {
    return isEnglishLocale() && containsCjk(legacy) ? t('ai.panel.truncatedFallback') : legacy
  }
  return t('ai.panel.truncatedFallback')
}

function toolResultText(part: ToolPart): string | null | undefined {
  const code = part.userTextCode?.trim()
  if (!code) {
    return part.result
  }
  const createdTaskId = code === 'CREATE_TASK_TURN_LIMIT'
    ? /本轮已创建任务 #(\d+)/.exec(part.result ?? '')?.[1]
    : undefined
  const key = createdTaskId
    ? 'ai.panel.toolResult.CREATE_TASK_TURN_LIMIT_WITH_ID'
    : code.startsWith('ai.') ? code : `ai.panel.toolResult.${code}`
  const translated = t(key, { id: createdTaskId ?? '' })
  return translated === key ? part.result : translated
}

function textPartOf(block: Block): TextPart | null {
  const part = block.parts[0]
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
  internal: 'ai.panel.internalHuman'
}

function diagnosticOf(part: ToolPart): PartDiagnostic | null {
  return cachedForLocale(diagnosticCache, part, 'diagnosis', [part.status, part.decision, part.kind2, part.reason, part.result], () => partDiagnosticOf(part))
}

function diagnosticHumanOf(part: ToolPart): string | null {
  const kind = diagnosticOf(part)
  return kind === null ? null : t(DIAGNOSTIC_HUMAN_KEY[kind])
}

function diagnosticTextOf(part: ToolPart): string | null {
  return cachedForLocale(diagnosticTextCache, part, 'diagnosis', [part.status, part.decision, part.kind2, part.reason, part.result], () => partDiagnosticText(part))
}

/** 「输出」位让位判据：分层命中的段若 result 自身就是机器向文本，就只留诊断详情（不双显）。 */
function outputSupersededByDiagnostics(part: ToolPart): boolean {
  return diagnosticOf(part) !== null && partResultIsInternal(part)
}

/**
 * 已决只读卡的原因行文本：分层命中时用**人话**（陈旧卡 = 「这轮对话已失效」；skipped/
 * internal = 「这一步没有执行 / 没有正常完成」），没有分层时逐字沿用原 reason。
 */
function readOnlyReasonText(part: ToolPart): string {
  const human = diagnosticHumanOf(part)
  if (human !== null) {
    return human
  }
  return isBlankText(part.reason) ? '' : (part.reason as string)
}

/** 普通工具卡红色原因行文本：skipped 走独立人话行、staleGate 是只读卡形态，两者都不进本行。 */
function faceReasonText(part: ToolPart): string {
  const kind = diagnosticOf(part)
  if (kind === 'skipped' || kind === 'staleGate') {
    return ''
  }
  if (kind === 'internal') {
    return t('ai.panel.internalHuman')
  }
  return isBlankText(part.reason) ? '' : reasonText(part)
}

/**
 * 工具展示名（S2 收口）：`tool.<name>.title` 是唯一来源（zh 值与迁移前 `TOOL_LABELS` 逐字一致）。
 * 缺键时回落工具名原文 —— 保证"永远有字"，不渲染 key 片段。
 */
function toolLabel(name: string): string {
  const key = `tool.${name}.title`
  const value = t(key)
  return value === key ? name : value
}

/**
 * R-165 S1：demo v7 胶囊四族（`.stchip` + `chip-idle/run/ok/bad` 工具类配方照搬）——
 * 底座与色族分离（门卡正文胶囊 / 健康胶囊复用）；承载件由 el-tag 换自绘 span。
 * 卡片卡头的状态标签走独立的 CARD_STATUS_BASE / CARD_STATUS_TONE（三类型统一、带描边；
 * 待确认的琥珀族在那边定义）。本族只剩健康胶囊在用，warning/info 收敛中性灰（R-163 C2 口径不变）。
 */
const CHIP_BASE = 'flex-none text-[9.5px] leading-[1.7] px-[5px] rounded-[5px] whitespace-nowrap'
const CHIP_TONE: Record<'info' | 'warning' | 'success' | 'danger' | 'primary', string> = {
  primary: 'text-[#2563eb] bg-[#eff4ff]',
  success: 'text-[#1a7f45] bg-[#edf7f0]',
  danger: 'text-[#c0392b] bg-[#fdf1ef]',
  // demo 无橙族：warning/info 收敛中性灰族（R-163 C2 状态色裁决口径不变）
  warning: 'text-[#57606a] bg-[#f6f7f9]',
  info: 'text-[#57606a] bg-[#f6f7f9]'
}

function chipClass(type: keyof typeof CHIP_TONE): string {
  return `${CHIP_BASE} ${CHIP_TONE[type]}`
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

/**
 * 2026-10-29 ToC 呈现减负批（同一事实只留一个提示面）：**专属 Alert 已经在报的那条事实**，
 * 通知槽不再叠一遍。映射刻意只收两组"同义重复"：
 * - 池饱和 ⇒ `alert-pool-saturated` 已在同屏（含 503 无 body 的回落通知）；
 * - 断流 ⇒ `alert-interrupted`（"结果可能不完整 + 已生成内容保留"）已在同屏
 *   —— 收流路径写 `interruptedKeep`、**接管耗尽路径写 `interruptedKeepShort`**，两条同义，都要收。
 * 其余通知（取消 / 重试 / 幂等 / 反馈失败 …）没有专属 Alert，照旧走通知槽。
 */
const noticeCoveredByAlert = computed(() => {
  const code = ai.noticeCode
  if (code === null) {
    return false
  }
  if (ai.poolSaturated !== null && (code === 'ai.notice.poolSaturated' || code === 'ai.notice.poolSaturatedWithSize')) {
    return true
  }
  return ai.streamInterrupted
    && (code === 'ai.notice.interruptedKeep' || code === 'ai.notice.interruptedKeepShort')
})

/** 通知槽是否渲染（原有两条排除 + 专属 Alert 覆盖）。 */
const noticeBarVisible = computed(() => ai.notice !== null
  && ai.noticeCode !== 'ai.notice.copiedNewTab'
  && !noticeCoveredByAlert.value)

/**
 * 工作区动作回执位是否渲染。
 *
 * <p><b>只有「成功回执」才可能与「工作区已同步」报同一件事</b>：`ui_event_result` 的失败
 * （`handled=false`）从不广播 `workspace_changed`，此时 `contextSync` 若是别的动作（用户手改 /
 * 上一手 AI 成功），压掉失败回执就是把**另一件事**吞了。故让位条件 = 成功 **且** 有同步反馈；
 * 失败回执始终显示（信息不丢）。
 */
const uiEventReceiptVisible = computed(() => {
  const receipt = ai.lastUiEventResult
  if (receipt === null) {
    return false
  }
  return !(receipt.handled && ai.contextSync !== null)
})

/**
 * 通知槽文本（i18n L-69）：store 里 `notice` 是**设定时**的语言快照，`noticeCode/noticeArgs` 才是
 * 结构化形态 —— 渲染走后者，切语言即重译；没有 code 的（测试注入 / 后端 message 原文）回落到 `notice`。
 */
const noticeText = computed(() => ai.noticeText)

/**
 * 「工作区已同步」chip 的动作摘要（i18n L-69）：记录带 `display`（结构化 code + args）时按当前语言重译，
 * 否则回落 `summary`（送模型那一份中文摘要 / 旧记录）。页面名在 `touch()` 时按当时语言取好。
 */
const contextSyncText = computed(() => {
  const record = ai.contextSync
  if (!record) {
    return ''
  }
  return record.display ? t(record.display.code, record.display.args ?? {}) : record.summary
})

function alertBox(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_BOX_BASE} ${ALERT_TONE[tone].box}`
}

function alertIcon(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_ICON_BASE} ${ALERT_TONE[tone].ic}`
}

/**
 * S6 批1（v7.9 场景 19-b/19-f + v7.10 规则 7）→ 2A 批：确认门字段表（v7 `.grows` 行）。
 * - 首行恒为「操作」（工具中文名）；
 * - 其后渲染 **args 全键**（原上限 5 行 = 1 + 4 防压垮门卡的旧口径退役 —— 门卡字段区已限高
 *   `min(280px,35vh)` 双约束内滚，参数表压不垮卡，「看不到的参数」才是缺陷；裁决 2026-10-16 第 3 条）；
 * - 待决门卡与已决只读卡**同源**（K1「参数区字段表原样」：只读体直接复用本函数）；
 * - **敏感值掩码**：键名命中 /pass(word)?|secret|token|api_?key/i 的值显示定长圆点（不泄值、不泄长度）；
 * - 值可读化：布尔 → 是/否、数组 → 顿号、文件路径 → 文件名、对象 →（复杂值）—— 不渲 raw JSON。
 */
function gateRows(part: ToolPart): FieldRow[] {
  return cachedForLocale(gateRowsCache, part, 'gateRows', [part.args, part.name], () => (
    [{ key: t('ai.panel.actionField'), value: toolLabel(part.name) }, ...argRows(part, true)]
  ))
}

/**
 * 业务枚举名 → 中文：后端 `PreflightReport.target` 放的是 Task 枚举名（`EXPORT` / `IMPORT`，
 * `ToolPreflightService#targetOf` 的 `String.valueOf`），卡头业务标题要中文（「导出 任务 #411…」）
 * —— 按仓库既有的 `TASK_TYPE_LABELS` 归一；已是中文或未知取值原样显示（不臆造标签）。
 */
function labelWord(labels: Partial<Record<string, string>>, value: string): string {
  return labels[value] ?? value
}

/**
 * R-165 S2：操作行 tip 气泡（demo v7 `.tip`：黑底 #1f2328 / 白字 11px / 圆角 6 / padding 3px 8px /
 * bottom calc(100%+7px) / 居中）。触发用 Tailwind **具名组** `group/tip`（按钮自身声明，不与消息行
 * 的 `group` 争用）；无 JS、无伪元素。v7 的 `::after` 小三角用**旋转方块 div** 复刻
 * （8×8 rotate-45 置于气泡下沿中央，下半露出即下指三角；伪元素属手写 CSS，铁律禁用）。
 * R-165 S5a：`TIP_CLASS` / `TIP_ARROW_CLASS` 字面量收归 `utils/markdown.ts` 单一来源
 * （渲染层注入 HTML 里的代码块复制钮同用一套），本组件顶部 import 直接引入。
 */

/** R-165 S2：空态建议词胶囊（v7 `.mini` chips：999px 胶囊 + 细描边 + 悬停蓝）。 */
const SUGGESTION_CHIP = 'rounded-[999px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#cddcff] hover:text-[#2563eb] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-[#e3e6ea] disabled:hover:text-[#57606a]'

/**
 * R-165 S2：骨架屏渐变条（demo v7 `.sk`）。v7 的 `animate-shimmer` 是 demo 私有 config 的动画
 * （仓库 tailwind.config.js 禁改、仓库 CSS 无 shimmer keyframes），改用内置 `animate-pulse`
 * （同为无限循环的占位律动，语义等价、幅度略缓）。宽度**字面量**书写（禁运行期拼类名，否则 JIT 扫不到）。
 */
const SKELETON_BAR_BASE = 'bg-[linear-gradient(90deg,#f6f7f9_0,#eef0f3_40%,#f6f7f9_80%)] bg-[length:220%_100%] animate-pulse motion-reduce:animate-none rounded-[6px] h-[11px] mb-[9px]'
const SKELETON_BARS = ['w-[72%]', 'w-[92%]', 'w-[58%]', 'w-[85%]'].map((w) => `${SKELETON_BAR_BASE} ${w}`)

function probe(): void {
  void ai.probeHealth()
}

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
 * R-154 #17 → R-162 M2：消息时间戳渲染（仅展示、可选）改走 `formatProgressiveTs`
 * （utils/format.ts 集中来源；历史路 = 后端条目 metadata.timestamp，实时路 = message_start 帧
 * atMs；无法解析 / 缺失 ⇒ 空串不渲染时间位）。R-158 #10 回执决策时刻同口径。
 */

/**
 * R-157 #2 → R-163 T4：编辑 = 「改完从该条重跑」——回填该条正文并记住锚点；发送时（send）截断
 * 该条之后的消息、以输入框新文本重发。仅用户消息可编辑（模板 v-if role 过滤）。
 * T4：输入框**非空白**（非全空白字符）时先弹确认防覆盖（window.confirm，spec 桩断言）。
 *
 * 批次2（AR-09）：编辑是**不可逆删除**，点击编辑时先按实测条数提示「将删除此后 N 条消息」，
 * 用户确认后才进入编辑态；N = 该消息之后的消息条数（不是固定文案）。N=0 不弹删除确认。
 *
 * Task 3：锚点改由 store 持有（`ai.editAnchorId`）—— 它属于**当前会话**的现场，换身份/换会话
 * 由 `adoptFreshSession` 统一清空；面板只读写这一个事实源，不再自己存一份。
 */
const editingMessageId = computed<string | null>({
  get: () => ai.editAnchorId,
  set: (value) => {
    ai.editAnchorId = value
  }
})

function editMessage(m: ChatMessage): void {
  const index = ai.messages.indexOf(m)
  const laterCount = index >= 0 ? ai.messages.length - index - 1 : 0
  if (laterCount > 0) {
    const confirmed = window.confirm(t('ai.panel.editDeleteConfirm', { count: laterCount }))
    if (!confirmed) {
      return
    }
  }
  if (inputText.value.trim() !== '') {
    const confirmed = window.confirm(t('ai.panel.overwriteConfirm'))
    if (!confirmed) {
      return
    }
  }
  inputText.value = m.content
  editingMessageId.value = m.id
}

/** 本轮是否仍可给出消息级恢复入口（最后一条助手消息 + 失败/断流/取消 + 未被停止）。 */
function isFailedLastAssistant(message: ChatMessage): boolean {
  const last = ai.messages[ai.messages.length - 1]
  return message.role === 'assistant'
    && last === message
    && message.stopped !== true
    && !unconfirmedOf(message)
    && (ai.lastRoundOutcome === 'error' || ai.lastRoundOutcome === 'interrupted' || ai.lastRoundOutcome === 'cancelled')
}

/**
 * AR-09 / AR-17：动作语义分离后的显示判据。
 * - 继续（append-drive）：**有正文/思考**的失败轮才出（续写/追加驱动，不删历史、不加用户气泡）；
 * - 重试本轮（retry-turn）：**零正文零思考 + 归属可证明**的失败轮才出（工具已结算但没写出文字的
 *   轮次也归此类 —— 无半截内容可续，只能仅模型步重跑；替换本轮、不加用户气泡）；
 * - 普通 regenerate：本批不做，**不渲染按钮**（不做禁用 / unsupported 假入口）。
 * 同一轮「继续 / 重试本轮」互斥，避免把两种语义塞进同一个按钮。
 */
function showContinue(message: ChatMessage): boolean {
  return isFailedLastAssistant(message) && hasTextOrReasoning(message)
}

function showRetryTurn(message: ChatMessage): boolean {
  // 归属不可证明（无 message.runId，且不是当前在途宿主）⇒ 不出「重试本轮」入口：
  // 刷新后 activeRunId 可能为空或指向更晚的一轮，猜它就会重试错轮。
  return isFailedLastAssistant(message) && !hasTextOrReasoning(message)
    && ai.retryRunIdFor(message) !== null
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

/**
 * 生成式表单已提交摘要（RC-1 → 2A）：**只读 `submittedValues`**（用户这次实际提交的值），
 * 字段名走 schema 的 `label`；无本地提交值（他端提交）⇒ 空串
 * （状态由统一卡头右标签「✓ 已提交」唯一承担，摘要位不重复）。
 */
function generativeFormSummary(part: ToolPart): string {
  const rows = submitRows(part)
  if (rows.length === 0) {
    return ''
  }
  const items = rows.slice(0, 3).map((row) => `${row.key} ${row.value}`)
  return `${items.join('，')}${rows.length > items.length ? '…' : ''}`
}

/**
 * 卡片表单 schema（待填写 / 已决 GF 共用）：按段缓存，模板里会取多次（标题 / 必填计数 / v-if / :schema）。
 */
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

/**
 * GF 只读态的回填值（K1 + F9）：有 `submittedValues` 且不是**取消** ⇒ 一律回填。
 *
 * <p>为什么对全部非取消结局都回填：结局帧可能是 `succeeded`，也可能是 `failed` / `expired` /
 * `rejected` / `notExecuted`（结果回灌丢失时 `expireResidueToolParts` 会把仍 pending 的段收成
 * `expired`）。只要用户**真的提交过**，卡体就该显示那份值 —— 否则结局到达那一刻卡体会回落成
 * schema 默认空表单，而卡头摘要（`generativeFormSummary` 读同一份 `submittedValues`）还写着
 * 「导出范围 华北」：同一张卡自相矛盾，也违背「结局帧到达后形态不变」。
 *
 * <p>`cancelled` 是唯一例外（F9）：取消 = 从未提交，不得把半成品值当"提交过的值"回填。
 */
function readOnlyFormValues(part: ToolPart): Record<string, unknown> | null {
  if (part.submittedValues === undefined || part.submittedValues === null) {
    return null
  }
  if (readOnlyStateOf(part) === 'cancelled') {
    return null
  }
  return part.submittedValues
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

/** M-16 / I3：编辑重跑的删除现场（失败时原样回插/还原）。 */
interface EditRerunUndo {
  /** 发起这一轮编辑时的会话（跨会话不得把旧消息回插进新会话） */
  sessionId: string
  /** 编辑锚点在删除时刻的下标（目标消息已不在数组里时用它定位） */
  index: number
  target: ChatMessage
  /** 被改写前的段（原文唯一真值：正文投影由它派生） */
  parts: ChatMessage['parts']
  /** 被 splice 掉的后继消息（保持原序） */
  removed: ChatMessage[]
}

/**
 * M-16（2026-10-29）/ I8（R1）：把编辑重跑删掉的东西放回去 —— 用户原文 + 其后的消息。
 *
 * <p>回插位置 = 编辑锚点（那条用户消息）之后：被删消息本来就是"这条提问的回答与后续"，
 * 放回提问后面才与删除前的对话形态一致。本次失败轮自己的助手气泡（错误卡宿主）留在末尾。
 *
 * <p>I3：会话已换（410 SESSION_PURGED / 他端接管）时**不得回插** —— 那是旧会话的消息，
 * 复活进新会话等于把两条时间线拼在一起；此时只靠调用方回填草稿（用户刚改的文本没被服务端收过）。
 *
 * <p>I8：还原后立即落镜像 —— 否则刷新一次又把刚救回来的旧回答丢掉（镜像里还是删除后的现场）。
 */
function restoreEditRerun(undo: EditRerunUndo): void {
  if (ai.sessionId !== undo.sessionId) {
    return
  }
  undo.target.parts = undo.parts
  deriveProjections(undo.target)
  const at = ai.messages.indexOf(undo.target)
  const index = at >= 0 ? at : undo.index
  ai.messages.splice(index + 1, 0, ...undo.removed)
  ai.writeMirrorNow()
  pinBottom()
}

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
  // R-157 #2（收口 MAJOR-1 语义版）：编辑锚点在 ⇒ 「改完从该条重跑」——该条文本原地更新为
  // 输入框新文本、截断其后消息、从该条重跑（skipUserRecord：不另起新用户消息）。
  const editId = editingMessageId.value
  let skipUserRecord = false
  /** M-16（2026-10-29）：编辑重跑是"先删后发"——发送前把删除现场留一份，失败时原样还原。 */
  let editUndo: EditRerunUndo | null = null
  if (editId) {
    editingMessageId.value = null
    const cut = ai.messages.findIndex((item) => item.id === editId)
    if (cut >= 0) {
      const target = ai.messages[cut] as ChatMessage
      const originalParts = target.parts
      const removed = ai.messages.slice(cut + 1)
      target.parts = [{ kind: 'text', seg: 0, text, streaming: false }]
      deriveProjections(target)
      markUserMsgDirty(target.id)
      ai.messages.splice(cut + 1)
      editUndo = { sessionId: ai.sessionId, index: cut, target, parts: originalParts, removed }
      pinBottom()
      skipUserRecord = true
    }
  }
  const generation = ++sendGeneration
  inputText.value = ''
  composerRef.value?.focus({ preventScroll: true })
  const result = skipUserRecord
    ? await ai.send(text, { skipUserRecord: true })
    : await ai.send(text)
  // 已被更晚的发送取代：这一轮不再拥有输入框 —— 草稿/编辑锚点一律不写（L-83：否则旧轮的
  // 还原会把上一条用户消息盖到新轮草稿上，新轮再因"输入框非空"放弃还原）。
  if (generation !== sendGeneration) {
    return
  }
  // M-16（2026-10-29）/ I8（R1）：失败 / 断流 / 未发送 / **用户主动停止** ⇒ 先把"先删后发"的现场
  // 还原 —— 被删消息原序回插、被改写的用户原文还原。停止同样是"这一轮没跑成"：不还原等于
  // 让一次停止把旧回答永久删掉。I3：会话已换时 restoreEditRerun 内部直接跳过（只回填草稿）。
  if (result.outcome !== 'ok' && editUndo) {
    restoreEditRerun(editUndo)
  }
  // 成功 / 主动停止：草稿保持已清空（既有口径：停止不是失败，不把刚发出去的文本塞回输入框）。
  if (result.outcome === 'ok' || result.outcome === 'cancelled') {
    return
  }
  // 用户已在流式中写入新内容：不覆盖，也不把失败消息设成新的编辑锚点。
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
  // 失败 / 断流 / 未发送：原稿回流并聚焦；新消息锚到本轮用户消息，编辑重跑沿用发送前 editId。
  inputText.value = draft
  editingMessageId.value = result.userMessageId ?? editId ?? null
  composerRef.value?.focus({ preventScroll: true })
}

function stop(): void {
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
function legacyCopy(text: string): boolean {
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
  } catch {
    return false
  } finally {
    document.body.removeChild(area)
  }
}

/**
 * 复制一条消息的正文（苞 B 项 2 → R-158 #11 改口径）。
 *
 * 内容口径 = `deriveCopyText`（parts 的 text 段 `join('\n\n')`，段间空行）——与渲染同源
 * （parts 是唯一真值），段间换行对齐业界惯例（R-150 合并轮的两段正文复制后仍分段）；
 * 不含思考链 / 工具卡 / 挂起卡，**不做 trim**。
 *
 * 路径：优先 `navigator.clipboard.writeText`；不可用或**被拒**（权限/非聚焦/非安全上下文）
 * 时落 `legacyCopy`。反馈写在组件局部 `copyState`，2s 后自清。
 */
async function copyMessage(m: ChatMessage): Promise<void> {
  const text = deriveCopyText(m)
  if (!text) {
    return
  }
  let ok: boolean
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      ok = true
    } catch {
      ok = legacyCopy(text)
    }
  } else {
    ok = legacyCopy(text)
  }
  copyState.value = { id: m.id, ok }
  if (copyTimer !== null) {
    clearTimeout(copyTimer)
  }
  copyTimer = setTimeout(() => {
    copyState.value = null
    copyTimer = null
  }, 2000)
}

/**
 * 新建对话（苞 A 项 1 + 2026-10-25 会话销毁口径）：立即清屏 + 换新 sessionId +
 * 完整销毁旧会话（服务端 purge；前端按响应 clientCleanup 精确清镜像/草稿）。
 * 无二次确认（裁决）：点了就干净；进行中的轮次由 store 内先 abort + POST cancel 收口。
 */
function newChat(): void {
  void ai.resetSession()
}

/**
 * R-166 口径③：该门卡是否**已提交**（在途 / 已提交待同步）—— 批准与拒绝双按钮据此一并
 * 禁用（"点击即禁用"的按钮侧；store 侧另有同 toolCallId 的提交守卫兜底）。
 */
function gateSubmitting(toolCallId: string): boolean {
  return ai.gateSubmissionOf(toolCallId) !== null
}

/** R-166 口径③：该门卡是否处于 409 之后的「已提交，同步中…」瞬态。 */
function gateSyncing(toolCallId: string): boolean {
  return ai.gateSubmissionOf(toolCallId)?.syncing === true
}

// ── 批4-D（用户 2026-10-23 裁决）：不可撤销确认卡的复选框 ──────────────────────

/** 该卡是否已勾选（普通确认卡无复选框，恒 false —— 也不参与任何禁用判据）。 */
function gateAcked(toolCallId: string): boolean {
  return ai.gateAckOf(toolCallId)
}

/** 不可撤销卡还差这一步：`批准` 置灰 + 提示行出现的唯一判据。 */
function gateNeedsAck(part: ToolPart): boolean {
  return part.irreversible === true && !gateAcked(part.toolCallId)
}

/**
 * 勾选文案：「确认{动作}，执行后无法恢复」（中文）/ 英文动名词组（`ai.hitl.action.<tool>`）。
 *
 * <p>R-09 fix round 1：`start_publish` 单独使用 `ai.hitl.publishIrreversible`，点名
 * “若本次为整体替换，范围内旧数据将被删除”——不读本地模式，避免陈旧副本造成误导。
 *
 * 动作名两段回落，保证"永远有字"：
 * ① `ai.hitl.action.<name>`（仅英文有实质值，中文与 `tool.<name>.title` 同字）；
 * ② 缺键 ⇒ `tool.<name>.title`（工具展示名，与门卡「操作」行同源）；再缺 ⇒ 工具名原文。
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
 * 全局单槽投影；按钮防重由 `gateSubmitting(part.toolCallId)` 按卡片承担。
 */
async function decide(approved: boolean, toolCallId: string): Promise<void> {
  const reason = approved ? undefined : '用户拒绝执行'
  await ai.confirm(approved, reason, toolCallId)
}

/** GF-B：表单提交（值已由 FormRenderer 按 schema 做过客户端复核）。 */
function onFormSubmit(values: Record<string, unknown>): void {
  void ai.submitGenerativeForm(values)
}

/** GF-B：表单显式取消 → store 带 cancelled:true 回灌收敛。 */
function onFormCancel(): void {
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
  return index < 0 ? 0 : index + 1
}

/**
 * AR-07：取消**排队里**的那一张（不动正在填的那张）。
 *
 * 走 store 的唯一出口 `cancelGenerativeForm(toolCallId)`（内部转 `cancelQueuedForm`）：
 * 出队清草稿 → 乐观终态 → 记决策时刻 → `cancelled:true` 回灌，失败原因落段可见。
 */
function onQueuedFormCancel(toolCallId: string): void {
  void ai.cancelGenerativeForm(toolCallId)
}

/**
 * GF 优化批（G-B）：草稿镜像 —— 只写本机 sessionStorage（按 toolCallId），
 * 不进模型上下文、不写服务器；提交/取消/终态由 store 清理。
 */
function onFormDraft(values: Record<string, unknown>): void {
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
    // R-165 S4：用户消息长度也会随历史回读 / 编辑重跑变化 —— 只把**末条用户消息**标脏，
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

function detachMessageViewObservers(): void {
  msgResizeObserver?.disconnect()
  msgResizeObserver = null
  if (msgScrollBound !== null) {
    msgScrollBound.removeEventListener('scroll', onTableScrollCapture, { capture: true })
    msgScrollBound = null
  }
  mermaidObserver?.disconnect()
  mermaidObserver = null
  mermaidPending.clear()
  lastMeasuredScrollHeight = null
  contentChanged = false
  contentResized = false
}

function attachMessageViewObservers(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  resetStickBaseline()
  if (typeof ResizeObserver !== 'undefined') {
    msgResizeObserver = new ResizeObserver(onContentResized)
    msgResizeObserver.observe(msgContentRef.value ?? wrap)
  }
  wrap.addEventListener('scroll', onTableScrollCapture, { capture: true, passive: true })
  msgScrollBound = wrap
}

watch(() => ai.expanded, async (expanded) => {
  if (!expanded) {
    detachMessageViewObservers()
    return
  }
  // v-if 分支在本次 patch 后才挂载：等 DOM 落地再绑新树。
  await nextTick()
  attachMessageViewObservers()
  measureUserMsgOverflow()
  // R-161：容器自身尺寸变化导致的相对底部漂移，统一走 pinBottom()。
  pinBottom()
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
 * 回调本身不钉底：只置"本帧尺寸变过"并交给统一调度，与 DOM 补丁后的复测合并成一次写入
 * （RO 一次批量可能投递多条 entry，逐条钉底就是重复补偿）。
 */
function onContentResized(): void {
  contentResized = true
  scheduleStickToBottom()
}


// ── 副标题 ────────────────────────────────────────────────────────────────────
/**
 * 副标题（P5）：展示**后端当前生效的模型**（`/health.model`）——纯展示，不承担切换。
 * 批 E · E1 起不能读前端选择态（那里已没有选择）：健康探针是"当前到底用哪个模型"的唯一真值。
 */
const currentModelLabel = computed(() => ai.health?.model || t('ai.panel.modelFallback'))

/** Task 4：当前租户的用户列表（少量预置；切租户后用户下拉随之换组）。 */
const currentTenantUsers = computed(() => {
  const tenantId = ai.identity?.tenantId
  if (tenantId === undefined) {
    return []
  }
  return ai.tenantDirectory.find((entry) => entry.tenantId === tenantId)?.users ?? []
})

/**
 * Task 3：**只有实际切换**才清输入草稿与编辑锚点（重选当前身份不是切换，草稿必须留着）。
 *
 * 草稿属于面板本地态（用户为旧身份的上下文打的字），编辑锚点属于旧会话的消息；切走时同步清掉，
 * 不等后端（切换本身也不等）。旧会话那份已不可达的浏览器镜像由 store 的切换路径清掉，
 * **后端历史保留**（不调 purge）。
 */
function switchIdentityFromPanel(tenantId: number, userId: number): void {
  const current = ai.identity
  if (current !== null && current.tenantId === tenantId && current.userId === userId) {
    return
  }
  inputText.value = ''
  editingMessageId.value = null
  void ai.switchIdentity(tenantId, userId)
}

/** 切租户：落到该租户第一项用户（目录第一项口径不跨租户沿用旧用户）。 */
function onTenantChange(event: Event): void {
  const tenantId = Number((event.target as HTMLSelectElement).value)
  const tenant = ai.tenantDirectory.find((entry) => entry.tenantId === tenantId)
  const user = tenant?.users[0]
  if (tenant && user) {
    switchIdentityFromPanel(tenant.tenantId, user.userId)
  }
}

/** 切用户：同租户内换人；跨身份一律换新会话（store 侧负责清屏与后端取号）。 */
function onUserChange(event: Event): void {
  const tenantId = ai.identity?.tenantId
  const userId = Number((event.target as HTMLSelectElement).value)
  if (tenantId !== undefined && Number.isInteger(userId)) {
    switchIdentityFromPanel(tenantId, userId)
  }
}

/** 身份不可用/会话未就绪时的统一重试（横幅按钮）：先重试目录，再发号（返修 I-2）。 */
function retryIdentity(): void {
  void ai.retryIdentitySession()
}

onMounted(async () => {
  // 批G · R2：订阅必须在读历史**之前**装（刷新后首帧要落盘），但启动期 `messages=[]` 不是
  // "确实为空"而是"还没读" —— 下面 `ensureTabOwnership()` 的 state 变更会触发一次即时写盘，
  // 早先把上一页签留下的持久化镜像清空（真机打点 `SET n=0 → GET n=0 → SET n=3`），
  // 令 `loadHistory` 的 readMirror 读到空镜像、G3 合并在途轮空转。竞态由 store 侧闸住
  // （`writeMirrorNow` 在 historyLoaded 之前不写空态），此处挂载次序保持不动。
  ai.startMirror()
  ai.startWorkspaceSync()
  void ai.probeHealth()
  // Task 4（返修 I6）：身份目录**不阻塞**所有权判定与历史恢复 —— 目录在后台加载；
  // 首次没有会话号时所有权判定先跳过（发号成功后 `ensureSession()` 会补判），
  // `loadHistory()`/发送经引导门按需等待目录结果。
  void ai.loadTenantDirectory()
  // RC-4：先判本页签是不是**复制页签**（Web Locks 探活）再读历史 —— 复制页签会发现场换新会话，
  // 不能把原页签的历史/挂起卡读进复制页签里。
  // 返回 null = 环境无 Web Locks（判定按恢复同步收口）⇒ **不额外 await**，onMounted 既有
  // 时序（RO 观测 + nextTick 后的长消息测量）一格不动。
  const ownership = ai.ensureTabOwnership()
  if (ownership !== null) {
    await ownership
  }
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
  stopRunExpiryTimer()
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
  // R-165 S5b：查看器若在打开态卸载 —— 摘掉 Esc 监听 + 解 body 滚动锁 + 收复制绿勾计时器
  window.removeEventListener('keydown', onMmdViewerKeydown)
  document.body.classList.remove('overflow-hidden')
  if (mmdViewerCopyTimer !== null) {
    clearTimeout(mmdViewerCopyTimer)
    mmdViewerCopyTimer = null
  }
  // 问题1 偏差修正批 aj14d ②：注销流式防抖 flush 回调（面板卸载后不再重入渲染）
  setStreamFlushHandler(null)
  // 批次 7A：组件销毁时释放两张 Markdown 缓存（收起/展开不销毁，缓存保留以复用）。
  historyMdCache.clear()
  historyMdCacheBytes = 0
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
    },
    get gfCardRenders() {
      return Object.fromEntries(gfCardRenders)
    }
  }
})
</script>

<!-- 本组件不持有 scoped 样式块：正文由渲染层注入工具类，呼吸与 chevron 使用模板类串，
     动画归 tailwind.config.js，平滑回底使用内置 scroll-smooth。 -->
