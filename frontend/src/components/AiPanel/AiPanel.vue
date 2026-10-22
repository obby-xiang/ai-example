<template>
  <!-- 收起态：48px 竖排条（蓝本 AiPanel.vue:2-7） -->
  <div
    v-if="!ai.expanded"
    class="h-full flex flex-col items-center pt-4 gap-2 cursor-pointer text-[#57606a]"
    @click="ai.toggleExpand()"
  >
    <el-icon><ChatDotRound /></el-icon>
    <span class="writing-vertical text-[13px]">AI 助手</span>
  </div>

  <!-- R-165 S5a 项 9：focus-visible 聚焦环（demo v7 14b 口径）——根容器一条任意变体覆盖面板内
       **全部**原生 button（含 v-html 注入的代码块复制钮），无需逐钮挂类。 -->
  <div v-else class="ai-chat h-full flex flex-col [&_button:focus-visible]:outline-none [&_button:focus-visible]:ring-2 [&_button:focus-visible]:ring-[#2563eb]/40">
    <!-- 头部（蓝本 AiPanel.vue:10-19） -->
    <div class="flex items-center gap-2.5 px-3 py-2.5 border-0 border-b border-solid border-[#ebeef5]">
      <div class="w-[34px] h-[34px] rounded-lg bg-gradient-to-br from-[#409eff] to-[#7b61ff] text-white flex items-center justify-center font-bold">AI</div>
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-[#303133]">实施助手</div>
        <div class="text-xs text-[#909399]" data-testid="current-model">{{ currentModelLabel }}</div>
      </div>
      <el-button
        size="small"
        text
        data-testid="new-chat"
        title="新建对话"
        :disabled="!ai.messages.length"
        @click="newChat"
      >
        新建对话
      </el-button>
      <el-button size="small" text title="收起" @click="ai.toggleExpand()">
        <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <!-- 会话 / 工作区上下文条（本仓差异：会话恢复与契约联动状态可视化，蓝本无此行） -->
    <div class="px-3 py-1.5 text-xs text-[#909399] border-0 border-b border-solid border-[#ebeef5] bg-aiPanel">
      <!-- S6 批1（长内容审计 #13）：`truncate`（nowrap + ellipsis）在 flex 子项上不生效 —— 子项默认
           `min-width:auto` 不会缩到内容宽以下，长 `contextSummary` 会整行溢出后被外层 overflow-hidden
           硬裁（看不到省略号）。两处补 `min-w-0`，省略号才真落地。 -->
      <div class="flex items-center gap-1.5 flex-wrap">
        <span :class="chipClass(healthTagType)">{{ healthLabel }}</span>
        <span>会话 {{ shortSessionId }}</span>
        <span>·</span>
        <span class="min-w-0 truncate">工作区 {{ workspace.contextSummary }}</span>
        <span>·</span>
        <span>数据版本 {{ workspace.dataVersion }}</span>
        <el-button link size="small" @click="probe">重新检测</el-button>
      </div>
      <div v-if="ai.contextSync" class="mt-0.5 min-w-0 truncate">
        工作区已同步：{{ ai.contextSync.summary }}（v{{ ai.contextSync.version }} · {{ ai.contextSync.source }}）
      </div>
    </div>

    <!-- 降级与冲突提示（R-165 S2：EP Alert 全退场 → 自绘 demo v7 `.alert` 结构 = 描边圆图标 + 标题 + 说明 + ghost 动作钮；
         色族按 v7 语义表：故障红 / 并发·降级蓝 / 通知灰 / 结果绿；`.notice` 虚线灰底承接单行通知槽） -->
    <div v-if="ai.poolSaturated" :class="alertBox('primary')" data-testid="alert-pool-saturated">
      <span :class="alertIcon('primary')" aria-hidden="true">▦</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">挂起池已饱和</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">挂起专用线程池容量 {{ ai.poolSaturated.poolSize }} 已用满，请等当前挂起轮次确认/超时后再试。</span>
      </div>
    </div>

    <!-- 批 E · E1：能力位降级黄条随模型选择一起退场 —— 没有选择就没有"选了不支持工具的模型"
         这一状态；默认模型的工具能力由后端 `/health` 与真实调用结果说话。 -->

    <div v-if="!ai.available" :class="alertBox('primary')" data-testid="alert-degraded">
      <span :class="alertIcon('primary')" aria-hidden="true">↓</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">AI 能力暂不可用（已降级，业务功能不受影响）</b>
        <span class="text-[11.8px] text-[#57606a] break-words [overflow-wrap:anywhere]">{{ degradeReason }}</span>
      </div>
    </div>

    <div v-if="ai.streamInterrupted" :class="alertBox('error')" data-testid="alert-interrupted">
      <span :class="alertIcon('error')" aria-hidden="true">!</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328]">连接中断，结果可能不完整</b>
        <span class="text-[11.8px] text-[#57606a]">本轮没有收到完成帧（done/error）。已生成的内容保留在上方，可重新发送或刷新页面继续接收。</span>
      </div>
    </div>

    <!-- 单行通知槽——普通通知 = demo v7 `.notice`（虚线灰底 + — 描边圆），取消 / 重试 / 幂等等通知的承载位；
         R-165 S3a（R11 指挥官裁决）：error 级通知改走 v7 `.enotice` 红族（浅红底 + 浅红描边 + 圆角 9 + ! 圆标）。 -->
    <div
      v-if="ai.notice"
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
      <span class="min-w-0 flex-1 break-words [overflow-wrap:anywhere]">{{ ai.notice }}</span>
    </div>

    <div v-if="ai.lastUiEventResult" :class="alertBox('gray')" data-testid="alert-ui-event">
      <span :class="alertIcon('gray')" aria-hidden="true">i</span>
      <div class="flex-1 min-w-0">
        <b class="block text-[12.6px] [font-weight:650] text-[#1f2328] break-words [overflow-wrap:anywhere]">AI 驱动工作区：{{ ai.lastUiEventResult.message }}</b>
      </div>
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
        AI 正在操作左侧工作区：{{ ai.uiAnnouncement }}
      </span>
      <button
        type="button"
        class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
        data-testid="ai-operating-stop"
        @click="ai.stop()"
      >
        停止
      </button>
    </div>
    <!-- 读屏播报（只读动词也播报；视觉零占位） -->
    <div class="sr-only" aria-live="polite" data-testid="ai-live-region">{{ ai.uiAnnouncement ?? '' }}</div>

    <!-- 消息流（蓝本 AiPanel.vue:21-89）；R-154 #5：外层 relative 壳承载「回到底部」浮钮 -->
    <div class="flex-1 relative min-h-0 flex flex-col">
      <!-- R-161：role="log" 补滚动容器（无障碍，无视觉影响）；R-163B G2：overflow-x-hidden 止血横滚条。
           R-165 S5a 项 8：滚动条细化 —— 6px 圆角半透明（demo 14a 口径），任意变体类写在模板类串上
           （禁改 tailwind.config.js、禁手写 CSS）。 -->
      <div
        ref="msgWrapRef"
        role="log"
        class="flex-1 overflow-y-auto overflow-x-hidden p-3"
        :class="SCROLLBAR_CLASS"
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
        <div class="[font-weight:650] text-[13.2px] mb-[3px]">您好，我是实施助手</div>
        <div class="text-[12.4px] leading-[1.8] text-[#57606a]">
          我可以帮您查询配置项与字段、查看任务、预估数据行数、<br />
          打开导出结果在线编辑器、下载导出文件，<br />并启动导出/预检查/导入/发布作业。
        </div>
        <div class="mt-[6px] text-[11.8px] text-[#8b949e]">对话保存在当前浏览器页签中，关闭页签即结束。</div>
        <!-- 批 E · E2（用户裁决 2026-10-22）：空态快捷 chips 点击**直接发送**（不再回填输入框）。
             写操作靠既有 HITL 确认卡兜底；文案与"每轮建议"共用一份词表（ai.suggestions.*，双语）。 -->
        <div class="mt-[11px] flex flex-wrap gap-[6px]">
          <button
            v-for="chip in EMPTY_STATE_CHIPS"
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
        class="group flex"
        :class="m.role === 'user' ? 'flex-row-reverse' : ''"
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
            <!-- R-163 T1 正文块：markdown-it → DOMPurify 白名单 → v-html 唯一链路
                 （chSplit/CH_TAIL 逐字符渐显退役——R-159 该语义被 Markdown 渲染取代，spec 口径同步改写）；
                 R-159 #3：流式正文末尾打字机光标保留（移出正文 div，随块同帧淡出）。 -->
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
              :ref="(el) => setUserBodyRef(m.id, el)"
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
              v-if="block.kind === 'text' && (showCaret(m, bi) || isFading(m))"
              :class="[CARET_CLASS, isFading(m) ? FADE_OUT_CLASS : '']"
              data-testid="stream-caret"
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
                     圆角 10；卡头 = 圆环⋆ 图标 + 「思考过程」12.4px#57606a + 摘要 11.5px#8b949e + chevron；
                     R-166 口径⑦：图标字形由 demo 的 U+2726（四角星）改 U+22C6 —— demo 实字落在
                     U+2600-27BF（本批 emoji 复扫的判废区间）内，改取同形的数学星号保住视觉且扫描 0 命中。
                     展开体 = 左竖线（border-l-2 #e3e6ea）+ pl-10px/pr-11px/pb-10px 的 12.6px#57606a 正文。 -->
                <div
                  v-if="part.kind === 'reasoning' && part.text"
                  class="border border-solid border-[#e3e6ea] rounded-[10px] bg-[#f6f7f9] overflow-hidden mb-3.5"
                >
                  <button
                    type="button"
                    class="flex items-center gap-[8px] w-full border-0 bg-transparent px-[10px] py-[7px] text-left transition-colors duration-[150ms] hover:bg-white"
                    :data-testid="'reasoning-toggle-' + block.id"
                    @click="toggleBlock(block)"
                  >
                    <span class="flex-[0_0_15px] w-[15px] h-[15px] rounded-[50%] border-solid border-[1.2px] border-[#e3e6ea] text-[#8b949e] text-[9px] flex items-center justify-center" aria-hidden="true">⋆</span>
                    <span class="text-[12.4px] text-[#57606a]">思考过程</span>
                    <span class="flex-1 text-[11.5px] text-[#8b949e]">{{ reasoningSummary(part.text, isBlockExpanded(block)) }}</span>
                    <span :class="[TCHEV_CLASS, isBlockExpanded(block) ? 'is-open' : '']">›</span>
                  </button>
                  <div v-show="isBlockExpanded(block)" class="pl-[10px] pr-[11px] pb-[10px] border-0 border-l-2 border-solid border-[#e3e6ea]" :data-testid="'reasoning-body-' + block.id">
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
                       GF 同一张表单 readonly、零操作按钮、卡面不渲染 decidedAt）；六态回合结束后
                       一律默认折叠，由 turnStillOpen 承担「回合未结束仍展开」。 -->
                  <div
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
                    <div v-if="isReadOnlyCard(part)" v-show="isBlockExpanded(block)" class="pb-[11px]" :data-testid="'ro-body-' + part.toolCallId">
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
                        <div v-else class="px-[11px] pt-[10px] text-[12.6px] text-[#57606a]">表单定义不可用，无法还原原卡</div>
                      </template>
                      <!-- 失败 / 拒绝 / 取消收尾的原因单独一行常驻卡面（K3：执行失败原因常驻；F9 同源） -->
                      <div
                        v-if="!isBlankText(part.reason)"
                        class="mx-[11px] mt-[9px] rounded-[8px] border border-solid border-[#f0d5d0] bg-[#fdf1ef] px-[10px] py-[7px] text-[11.9px] leading-[1.6] text-[#c0392b] whitespace-pre-wrap break-words"
                        :data-testid="'ro-reason-' + part.toolCallId"
                      >{{ part.reason }}</div>
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
                        <div class="block text-[11px] tracking-[.5px] uppercase text-[#8b949e] mb-[4px]">参数</div>
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
                      <div v-if="!isBlankText(part.result)">
                        <div class="block text-[11px] tracking-[.5px] uppercase text-[#8b949e] mb-[4px]">输出</div>
                        <!-- R-165 S5a 项 4：工具卡输出 = **纯限高内滚**，原 CollapsibleText 的渐隐 +
                             「显示更多」钮退役（无展开钮）；截断徽标保留。
                             滚动条同走 6px 圆角半透明任意变体类（项 8）。
                             S6 批1 长内容审计清零项：补 break-all + overflow-wrap:anywhere（长串不越界）。
                             2A 批（裁决 §14.4 代码 / 详情族）：限高改双约束 `min(280px,35vh)`。 -->
                        <div :class="['text-[12.1px] leading-[1.6] font-mono text-[#606266] bg-[#f6f7f9] border border-solid border-[#eef0f3] rounded-[8px] px-[9px] py-[8px] max-h-[min(280px,35vh)] overflow-y-auto whitespace-pre-wrap break-all [overflow-wrap:anywhere]', SCROLLBAR_CLASS]">
                          {{ part.result }}<span
                            v-if="part.truncated"
                            class="inline-block ml-1 px-1 rounded text-[10px] leading-4 bg-[#8b949e] text-white align-middle"
                            :title="part.truncatedReason ?? '结果被截断'"
                            data-testid="truncated-badge"
                          >已截断</span>
                        </div>
                      </div>
                      <!-- AI 可视可操作专项（§3.8 / 裁 6）：操作摘要行的「撤销」按钮。
                           只有用户能撤销（**不提供** ui_undo 工具）；深度 1 —— 撤销后按钮消失。 -->
                      <div
                        v-if="part.toolCallId === ai.uiUndoToolCallId && ai.uiUndoEntry"
                        class="flex items-center gap-[8px]"
                        data-testid="ui-undo-row"
                      >
                        <span class="text-[12.2px] text-[#57606a]">界面已改动，可撤销最近 1 步。</span>
                        <button
                          type="button"
                          class="flex-none whitespace-nowrap rounded-[8px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-all duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328]"
                          data-testid="ui-undo-button"
                          @click="onUiUndo"
                        >
                          撤销
                        </button>
                      </div>

                      <!-- R-158 #6：卡内错误位（自绘圆环 ! + 红底 + 文案）——错误就地呈现，不再全堆顶部 Alert；
                           R-165 S1：el-icon 退场，圆环 ! 照搬 demo v7 行内错误通知形制。
                           S6 批1（v7.10 N8）：发布冲突明细收敛 —— 红底行只留一句人话摘要（reason 首行），
                           两种形态不同屏重复报同一份明细。
                           S6 批2：明细表的**来源**改为 `part.resultView`（通用渲染器），红底行仍只取首行；
                           无视图的旧段才回退到反解表（回退路径按设计稿 §7.1-⑤ 最后删）。
                           2A 批（裁决 §14.4 回执 / 文本族）：原因行限高改 `min(280px,35vh)`。 -->
                      <div v-if="!isBlankText(part.reason)" class="flex items-start gap-[7px] px-[10px] py-[7px] text-[12.4px] text-[#c0392b] bg-[#fdf1ef] border border-solid border-[#f0d5d0] rounded-[9px]" data-testid="tool-error">
                        <span class="mt-px shrink-0 flex-[0_0_14px] w-[14px] h-[14px] flex items-center justify-center rounded-[50%] border-[1.3px] border-[#c0392b] text-[#c0392b] text-[9px] font-bold" aria-hidden="true">!</span>
                        <span class="flex-1 min-w-0 whitespace-pre-wrap break-words max-h-[min(280px,35vh)] overflow-y-auto">原因：{{ reasonText(part) }}</span>
                      </div>
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
                          <span class="inline-block w-[34%]">配置</span><span class="inline-block w-[26%]">行键</span><span class="inline-block w-[40%]">原因</span>
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
                               （历史 TIMEOUT 卡只读展示保留）。预检摘要区撤除（types 的 preflight
                               字段保留不渲染）；409 幂等拒绝后的「已提交，同步中…」瞬态保留。 -->
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
                            <!-- 2A 批（裁决 §3 缺陷③）：按钮防折行 —— 行 flex-wrap（空间不足时按钮组整行
                                 下移）、按钮组 flex-none、按钮 whitespace-nowrap + min-w；组内不再换行。 -->
                            <div class="mx-[11px] flex flex-wrap items-center gap-[9px] pt-[11px]">
                              <!-- R-166 口径③：409 幂等拒绝后的「已提交，同步中…」瞬态（不写任何乐观裁决） -->
                              <span
                                v-if="gateSyncing(part.toolCallId)"
                                class="text-[12.2px] text-[#57606a]"
                                data-testid="gate-syncing"
                              >已提交，同步中…</span>
                              <span class="ml-auto flex flex-none items-center gap-[8px] whitespace-nowrap">
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[56px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-[#e3e6ea] transition-all duration-[150ms] bg-white text-[#57606a] hover:border-[#8b949e] hover:text-[#1f2328] disabled:opacity-50 disabled:cursor-default"
                                  :disabled="gateSubmitting(part.toolCallId)"
                                  @click="decide(false, part.toolCallId)"
                                >拒绝</button>
                                <button
                                  type="button"
                                  class="flex-none whitespace-nowrap min-w-[72px] rounded-[8px] px-[13px] py-[5px] text-[12.6px] border border-solid border-transparent transition-all duration-[150ms] bg-[#2563eb] text-white font-semibold hover:brightness-[1.08] disabled:opacity-50 disabled:cursor-default disabled:bg-[#8b949e] disabled:font-normal"
                                  :disabled="gateSubmitting(part.toolCallId)"
                                  @click="decide(true, part.toolCallId)"
                                >批准</button>
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
                        </div>

                        <!-- AI 表格操作专项 §3.3bis：写预览卡（计数 / 采样 diff / 全量 diff 三档）。
                             数据只在浏览器渲染，不上送；大批量须勾选“我了解影响范围”。 -->
                        <div v-else-if="isSheetPreviewPart(part)" class="p-[11px]">
                          <SheetPreviewCard
                            :plan="sheetPreviewFor(part)"
                            @apply="onSheetPreviewApply"
                            @cancel="onSheetPreviewCancel"
                            @abandon="onSheetPreviewAbandon"
                            @level="ai.setPreviewLevel"
                          />
                        </div>

                        <!-- AI 表格工具治理 §5.4：已应用改动的一行标识（不新增工具、不新增帧字段）。
                             数据源 = store 的 sheetApplied（现算），`其后人工改动 N 格` 在手改后由
                             refreshSheetApplied 重算 —— 不显示"应用瞬间的 0"当作现状。 -->
                        <div
                          v-else-if="appliedSummaryFor(part)"
                          class="p-[11px] text-[11.9px] leading-[1.6] text-[#57606a]"
                          data-testid="sheet-applied-summary"
                        >
                          <div>{{ appliedLine(appliedSummaryFor(part)!) }}</div>
                          <div v-if="(appliedSummaryFor(part)!.manualChanges ?? 0) > 0" class="mt-[2px] text-[#8b949e]">
                            {{ t('sheet.preview.manualChanges', { count: appliedSummaryFor(part)!.manualChanges }) }}
                          </div>
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
                            @cancel="ai.cancelUploadHandoff()"
                          />
                        </div>
                      </template>
                    </div>
                    </template>
                  </div>
                </template>
              </template>
            </template>
          </template>

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
            {{ userMsgExpanded.has(m.id) ? '收起 ▴' : '展开 ▾' }}
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
              v-if="isBreathing(m) || isFading(m)"
              :class="[BREATH_CLASS, isFading(m) ? FADE_OUT_CLASS : '']"
              data-testid="loading-breath"
            >
              <span class="w-[7px] h-[7px] rounded-full bg-[#2563eb] animate-breathe"></span><span class="text-[12.2px] text-[#57606a] tracking-[.5px]">工作中…</span>
            </span>
            <span v-else-if="formatProgressiveTs(m.timestamp)" class="text-[#8b949e]" data-testid="msg-timestamp">{{ formatProgressiveTs(m.timestamp) }}</span>
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
              {{ '复制失败，请手动选中文本复制' }}
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
            <!-- R-163 T4：「继续」入口（error/断流/取消后，最后一条助手消息）——点击直发「继续」 -->
            <button
              v-if="showContinue(m)"
              type="button"
              data-testid="continue-round"
              aria-label="继续本轮对话"
              class="group/tip relative inline-flex items-center justify-center w-7 h-7 rounded-[8px] border-0 bg-transparent p-0 text-xs text-[#2563eb] transition hover:bg-[#eff4ff]"
              @click="continueRound"
            >
              继续
              <span :class="TIP_CLASS">继续本轮对话<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
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
              :aria-label="feedbackOf(m.id) === 1 ? '取消赞' : '赞'"
              :aria-pressed="feedbackOf(m.id) === 1"
              :class="[ICON_BTN_CLASS, feedbackOf(m.id) === 1 ? ICON_BTN_ON_BLUE : ICON_BTN_IDLE]"
              @click="rateMessage(m, 1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3H14z"></path><path d="M7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"></path></svg>
              <span :class="TIP_CLASS">{{ feedbackOf(m.id) === 1 ? '取消赞' : '赞' }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              v-if="m.role === 'assistant'"
              type="button"
              :data-testid="'feedback-down-' + m.id"
              :aria-label="feedbackOf(m.id) === -1 ? '取消踩' : '踩'"
              :aria-pressed="feedbackOf(m.id) === -1"
              :class="[ICON_BTN_CLASS, feedbackOf(m.id) === -1 ? ICON_BTN_ON_RED : ICON_BTN_IDLE]"
              @click="rateMessage(m, -1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3H10z"></path><path d="M17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"></path></svg>
              <span :class="TIP_CLASS">{{ feedbackOf(m.id) === -1 ? '取消踩' : '踩' }}<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              v-if="m.role === 'user'"
              type="button"
              :class="[ICON_BTN_CLASS, ICON_BTN_IDLE]"
              :data-testid="'edit-msg-' + m.id"
              aria-label="编辑该条消息（改完从该条重跑）"
              @click="editMessage(m)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9"></path><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"></path></svg>
              <span :class="TIP_CLASS">编辑该条消息（改完从该条重跑）<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="[ICON_BTN_CLASS, ICON_BTN_IDLE, COPY_STATE_CLASS, isMsgCopied(m.id) ? 'is-copied' : '']"
              :data-testid="'copy-msg-' + m.id"
              aria-label="复制该条消息正文"
              @click="copyMessage(m)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-copy"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-check"><polyline points="20 6 9 17 4 12"></polyline></svg>
              <span :class="TIP_CLASS">复制该条消息正文<span :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
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
            <!-- S6 批1（长内容审计清零项：错误卡 detail 无 cap）：detail 加限高内滚。
                 2A 批（裁决 §14.4 文本块族）：限高改双约束 `min(280px,35vh)` —— 长错误文案不再把会话顶开。 -->
            <span :class="['block max-h-[min(280px,35vh)] overflow-y-auto text-[11.6px] text-[#57606a] leading-[1.55] whitespace-pre-wrap break-words', SCROLLBAR_CLASS]" :data-testid="'error-card-detail-' + m.id">{{ m.errorCard.detail }}</span>
            <span v-if="m.errorCard.guide" class="block mt-[3px] text-[11.4px] text-[#57606a] leading-[1.55]">{{ m.errorCard.guide }}</span>
          </div>
          <button
            v-if="m.errorCard.action"
            type="button"
            class="flex-none border-0 bg-transparent p-0 text-[11.5px]"
            :class="ERROR_CARD_TONE[m.errorCard.variant].action"
            :data-testid="'error-card-action-' + m.id"
            @click="runErrorCardAction(m.errorCard)"
          ><span class="border-b border-[#e3e6ea]">{{ m.errorCard.action }}</span></button>
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
        title="回到底部"
        class="absolute bottom-3 left-1/2 -translate-x-1/2 z-[6] whitespace-nowrap rounded-full border border-solid border-[#e3e6ea] bg-white px-3 py-1.5 text-xs text-[#57606a] shadow-[0_2px_8px_rgba(0,0,0,0.13)] transition-colors duration-[150ms] hover:text-[#1f2328]"
        @click="backToBottom"
      >
        回到底部
      </button>
    </div>

    <!-- 输入区（问题4 composer v2 阶段一，demo docs/demo/chat-ux/composer-decisions.html ①/②）：
         form 形态 = 1 行 40px 起 / [field-sizing:content] 随内容增高 / 4 行 101px 封顶后框内滚动；
         白底 shell + 整盒 focus-within 蓝环；盒内底栏左工具位虚线占位、右 28×28 圆形发送/停止钮。
         行为：流式生成中 textarea 保持可编辑（硬禁用只看真实不可用/挂起/pendingToolCall）；
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
          class="block min-h-[40px] max-h-[101px] w-full resize-none overflow-y-auto rounded-t-[12px] border-0 bg-transparent px-[11px] pb-[7px] pt-[11px] font-[inherit] text-[13px] leading-[1.6] text-[#1f2328] [outline:none] [field-sizing:content] placeholder:text-[#8b949e] disabled:cursor-default"
          :disabled="composerHardDisabled"
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
            aria-label="发送"
            :disabled="composerHardDisabled || !canSubmit"
            @click="send"
          >↑<span class="tip" :class="[TIP_CLASS, hiddenComposerTips.has('send') ? 'hidden' : '']" aria-hidden="true">发送<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span></button>
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
      aria-label="mermaid 全屏查看器"
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
              aria-label="缩小"
              @click="zoomMmdViewer(1 / 1.25)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">缩小<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <span class="min-w-[46px] text-center font-mono text-[11.5px] tabular-nums text-[#57606a]" data-testid="mmd-zoom-pct">{{ mmdViewerPct }}%</span>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-zoom-in"
              aria-label="放大"
              @click="zoomMmdViewer(1.25)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14"></path><path d="M5 12h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">放大<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <span class="mx-[2px] h-[18px] w-[1px] bg-[#e3e6ea]"></span>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-reset"
              aria-label="复位到 100%"
              @click="resetMmdViewer"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7"></path><path d="M3 4v5h5"></path></svg>
              <span class="tip" :class="TIP_CLASS">复位到 100%<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="[MMD_COPY_ICON_BTN_CLASS, mmdViewerCopied ? 'is-copied' : '']"
              data-testid="mmd-copy"
              aria-label="复制源码"
              @click="copyMmdViewerSource"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-copy"><rect x="9" y="9" width="13" height="13" rx="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
              <svg viewBox="0 0 24 24" aria-hidden="true" class="i-check"><polyline points="20 6 9 17 4 12"></polyline></svg>
              <span class="tip" :class="TIP_CLASS">复制源码<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-download"
              aria-label="下载 SVG"
              @click="downloadMmdViewerSvg"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12"></path><path d="m7 10 5 5 5-5"></path><path d="M5 21h14"></path></svg>
              <span class="tip" :class="TIP_CLASS">下载 SVG<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
            </button>
            <button
              type="button"
              :class="MMD_ICON_BTN_CLASS"
              data-testid="mmd-close"
              aria-label="关闭全屏"
              @click="closeMmdViewer"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 6 6 18"></path><path d="m6 6 12 12"></path></svg>
              <span class="tip" :class="TIP_CLASS">关闭全屏<span class="tip-arrow" :class="TIP_ARROW_CLASS" aria-hidden="true"></span></span>
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
 *   （旧会话的服务端记忆由 `DELETE /api/ai/history/{sessionId}` 删除）—— 蓝本无"重开"入口；
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
 * ③ 已决六态（已批准 / 已拒绝 / 已超时 / 已批准·执行失败 / 已提交 / 已取消）回合结束后
 *    一律默认折叠，卡头单行（标题/摘要/右状态标签）保留可辨识信息；决策当下不折叠（turnStillOpen）；
 * ④ 状态全集：门卡 = 已批准 / 已拒绝 / 已超时 / 已批准·执行失败（无已取消）；
 *    表单系 = 已提交 / 已取消；审批卡旧字样（含被 R5 / §10 F6 废止的两枚）零残留；
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
import { deriveCopyText, deriveProjections } from '@/utils/parts'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import ResultViewRenderer from '@/components/AiPanel/resultview/ResultViewRenderer.vue'
import SheetPreviewCard from '@/components/AiPanel/SheetPreviewCard.vue'
import UploadHandoffCard from '@/components/AiPanel/UploadHandoffCard.vue'
import ContextPill from '@/components/AiPanel/ContextPill.vue'
import { GENERATIVE_FORM_TOOL, type ChatMessage, type ToolPart } from '@/types/ai'
import { SHEET_PREVIEW_TOOLS, UI_HANDOFF_TOOLS } from '@/types/tools'
import { resolveAiRef } from '@/utils/ai-regions'
import type { SheetAppliedSummary } from '@/utils/sheet-tools'
import type { FormSpec } from '@/types/form-schema'
import type { ErrorCard, ErrorCardVariant } from '@/utils/error-card'

const { t } = useI18n()
const ai = useAiStore()
const workspace = useWorkspaceStore()

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
 * false，于是自动跟随"自己把自己停了"，下一帧起 `scrollBottom()` 又因 followBottom=false
 * 整段跳过（用户看到的断续 / 拖回）。
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
 * 「回到底部」浮钮：加 CSS scroll-behavior:smooth 类 + pinBottom（会审 M2：禁
 * scrollTo({behavior:'smooth'})，jsdom 不同步更新 scrollTop，保既有同步断言）；
 * 显式收钮（会审 M3：滞回态非纯计算，否则「点击后钮消失」不成立）；约 600ms 后移除类，
 * 期间用户滚动可打断（浏览器语义）；流式期 pinBottom 恒瞬跳，不走 smooth。
 */
function backToBottom(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  wrap.classList.add('scroll-smooth')
  if (smoothBackTimer !== null) {
    clearTimeout(smoothBackTimer)
  }
  smoothBackTimer = setTimeout(() => {
    wrap.classList.remove('scroll-smooth')
    smoothBackTimer = null
  }, SMOOTH_BACK_MS)
  pinBottom()
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
 * R-165 S5c：三段状态动画/装饰元素的模板类串（原 ai-chat.css `.caret` / `.loading-breath` /
 * `.fade-out` / `.tchev` 逐值迁移）。**语义标记类保留**（`caret` / `loading-breath` / `fade-out` /
 * `tchev`）——它们与 `.md-body` / `cpbtn` / `mmdc-*` 同属「结构标记」命名法，既有断言（fade-out
 * 两态、`.tchev` 存在）与外部探针靠它们定位；承载视觉的是随行的 Tailwind 工具类。
 * keyframes 本体见 tailwind.config.js（aiBlink / aiBreathe / aiFadeOut）。
 */
const CARET_CLASS =
  'caret inline-block w-[6px] h-[14px] bg-[#57606a] align-[-2px] rounded-[1px] animate-caret motion-reduce:animate-none'
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

/** 错误卡动作（= 三变体各自引导语的落点，全部复用本组件既有动作，不新增 store 出口）：
 *  「稍后重试」= 重发本轮（`continueRound`，与「继续」同一条路）；「改写后重发」= 把焦点交回输入框
 *  （改写是用户动作，面板只能把落点指过去）；「开新会话」= 既有 `newChat()`（与面板头「新建对话」同一出口）。 */
function runErrorCardAction(card: ErrorCard): void {
  if (card.action === '稍后重试') {
    continueRound()
    return
  }
  if (card.action === '改写后重发') {
    composerRef.value?.focus()
    return
  }
  if (card.action === '开新会话') {
    newChat()
  }
}

/** 用户消息 md-body 元素表（按消息 id 记，供单一 measure 读 scrollHeight）。 */
const userBodyEls = new Map<string, HTMLElement>()
/** 真溢出（正文高 > 184px）的用户消息 id 集 —— 只有它为真才渲染「展开/收起」。 */
const userMsgOverflowed = ref<Set<string>>(new Set())
/** 已展开的用户消息 id 集（默认收起 = 不在集内）。 */
const userMsgExpanded = ref<Set<string>>(new Set())

/** 模板函数 ref：用户消息正文元素登记/注销（收起态下 scrollHeight 仍为内容自然高，量取稳定）。 */
function setUserBodyRef(id: string, el: Element | { $el?: Element } | null): void {
  if (el instanceof HTMLElement) {
    userBodyEls.set(id, el)
    return
  }
  userBodyEls.delete(id)
}

/** 单一 measure：逐条读 scrollHeight 与限高比较，集合真变化才换引用（防无谓重渲）。 */
function measureUserMsgOverflow(): void {
  const next = new Set<string>()
  for (const [id, el] of userBodyEls) {
    if (el.scrollHeight > USER_MSG_MAX_HEIGHT_PX) {
      next.add(id)
    }
  }
  if (next.size !== userMsgOverflowed.value.size || [...next].some((id) => !userMsgOverflowed.value.has(id))) {
    userMsgOverflowed.value = next
  }
}

/** 折叠态判定：真溢出且未展开。 */
function isUserMsgCollapsed(id: string): boolean {
  return userMsgOverflowed.value.has(id) && !userMsgExpanded.value.has(id)
}

/** 展开/收起切换（同回落单一 measure：展开后字数变化同样以读数为准）。 */
function toggleUserMsg(id: string): void {
  const next = new Set(userMsgExpanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  userMsgExpanded.value = next
  void nextTick(measureUserMsgOverflow)
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

/** 建议/胶囊的当前语言文案（模板 id + 插值参数 → 文案；缺键时 vue-i18n 回落 key 原文）。 */
function suggestionText(item: FollowupSuggestion): string {
  return t(`ai.suggestions.${item.key}`, item.params ?? {})
}

/**
 * 点 chip / 点建议 = **直接发送**（批 E · E2：不再回填输入框）。
 *
 * 直接走 `ai.send()`（与用户手打发送同一入口）：写操作该走确认卡的自然会走确认卡，
 * 面板不在这里做任何"要不要先问一句"的判断。在途轮里点了不响应、**不提示**（批G · G6 用户直裁：
 * 输入/快捷操作都不响应，用户要发就自己先停止）。
 */
function sendPreset(text: string): void {
  if (!ai.canSend) {
    return
  }
  void ai.send(text)
}

/**
 * 每轮正常完成后的建议（2–3 条；规则模板 + 现有上下文槽位）。
 *
 * <h2>闸门（缺一不出）</h2>
 * <ul>
 * <li>本轮以**非取消的 done** 收尾（store 的 `followupReady`）、且没有在生成/挂起；</li>
 * <li>没有待确认卡 / 待前端执行的调用 / 表单 / 写预览 / 上传交接（挂着卡时建议会抢注意力）；</li>
 * <li>最后一条是助手消息且已封口（流式中不出，免得建议随 token 抖动）。</li>
 * </ul>
 */
const followups = computed<FollowupSuggestion[]>(() => {
  if (!ai.followupReady || ai.loading || ai.suspended || ai.streamInterrupted) {
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
  })
})

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

/**
 * 问题4 composer v2：输入区硬禁用只看真实不可用 / 挂起 / 待处理工具调用。
 * health 尚未探测（null 且无 healthError）= 未知态，不硬禁用（避免首帧闪禁 / 吞掉输入）；
 * `ai.loading`（流式生成中）**不**硬禁用 —— 生成中仍可继续编辑，发送位换停止钮。
 */
const composerHardDisabled = computed(
  () =>
    (ai.health !== null && !ai.health.available) ||
    ai.healthError !== null ||
    ai.suspended ||
    ai.pendingToolCall !== null
)

/** 发送闸只认 health available + 非空 + 未超限；不依赖 loading（流式中的 Enter 在 keydown 层静默）。 */
const canSubmit = computed(
  () => ai.available && inputText.value.trim().length > 0 && inputText.value.length <= MESSAGE_MAX_CHARS
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
      return '剩余字数不足 500 字'
    case 'limit':
      return `已达 ${MESSAGE_MAX_CHARS} 字上限`
    case 'over':
      return '已超出上限，无法发送'
    default:
      return ''
  }
})

const composerCounterText = computed(() => {
  if (composerOverLimit.value) {
    return `已超 ${-composerRemaining.value} 字上限`
  }
  if (composerRemaining.value === 0) {
    return `已达 ${MESSAGE_MAX_CHARS} 字上限`
  }
  return `剩余 ${composerRemaining.value} 字`
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
 * 发送闸与发送钮同源（composerHardDisabled || !canSubmit），不允许键盘绕过禁用。
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
  if (event.repeat || ai.loading || composerHardDisabled.value || !canSubmit.value) {
    return
  }
  void send()
}

const inputPlaceholder = computed(() => {
  if (!ai.available) {
    return 'AI 当前不可用（已降级，可继续使用页面手动操作）'
  }
  if (ai.suspended || ai.pendingToolCall) {
    return '挂起等待中：请先在上方处理确认/执行，或点「停止」结束本轮'
  }
  return '输入消息，Enter 发送，Shift+Enter 换行'
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

/**
 * R-159 #2：「工作中…」呼吸全程常驻判据 —— 助手消息 streaming 全期成立（发出即现，
 * 首字符上屏不消失；原 isTyping 的「无正文」判据退役，消费点仅元信息行左位一处）。
 */
function isBreathing(message: ChatMessage): boolean {
  return message.role === 'assistant' && message.streaming
}

/** R-159 #2/#3：元信息行判据 = 有正文（N10 操作行判据沿用）或助手流式中（无正文也要能呼吸）。 */
function showMetaRow(message: ChatMessage): boolean {
  return message.parts.some((part) => part.kind === 'text' && part.text)
    || isBreathing(message)
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

/** R-159 #4：完成淡出记账 —— streaming true→false 时该消息进 300ms 淡出（caretOut），
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
 * R-163 T1：正文 Markdown 渲染（markdown-it → DOMPurify → v-html 唯一链路的组件侧）——
 * 结果 memo（键 = 消息 + 段 + 文本长度 + 流式态）；mermaid 闭合围栏占位 div 在 nextTick
 * 后异步渲染 SVG 回填（失败保留源码块）。R-159 #6 逐字符渐显（chSplit/CH_TAIL）按决策卡
 * §三.1 显式退役：其语义被 Markdown 渲染取代。
 */
const mdCache = new Map<string, { html: string }>()
const MD_CACHE_MAX = 128

/**
 * 问题1 偏差修正批 aj14d ②：流式块防抖的「落地」信号 —— 75ms 窗口内被合并掉的增量由
 * `setStreamFlushHandler` 回调（静默满窗后触发）bump 本计数，令 markdownHtml 重入一次真渲染。
 * 只有流式块把本计数并进 memo key（非流式块 cache 键仍是 `...:f`，不被本计数搅动）。
 */
const mdStreamTick = ref(0)
setStreamFlushHandler(() => {
  mdStreamTick.value += 1
})

function markdownHtml(message: ChatMessage, block: Block): string {
  const part = block.parts[0]
  const text = part.kind === 'text' ? part.text : ''
  // aj14d ③：块级稳定 key = 消息 + 段（与文本长度/流式态解耦，作防抖记账与围栏身份的命名空间）
  const blockKey = `${message.id}:${part.seg}`
  const key = `${blockKey}:${text.length}:${message.streaming ? `s${mdStreamTick.value}` : 'f'}`
  const hit = mdCache.get(key)
  if (hit) {
    return hit.html
  }
  const { html, mermaid } = renderMarkdown(text, message.streaming, blockKey)
  if (mermaid.length > 0) {
    void nextTick(() => hydrateMermaid(message.id, mermaid))
  }
  if (mdCache.size >= MD_CACHE_MAX) {
    const first = mdCache.keys().next().value
    if (first !== undefined) {
      mdCache.delete(first)
    }
  }
  mdCache.set(key, { html })
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

function toolSummaryOrNull(part: ToolPart): string | null {
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
  btn.classList.toggle('is-copied', ok)
  const timer = window.setTimeout(() => btn.classList.remove('is-copied'), 2000)
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
 *  `svg === null`（render error / 3s 超时）⇒ 降级为「源码态卡」：无 tab、无缩放钮（源码直显、复制可用），
 *  不留任何无 handler 的钮。 */
function buildMermaidCardHtml(svg: string | null): string {
  const hasSvg = svg !== null
  const head =
    `<div class="${MMD_HEAD_CLASS}"><span class="${MMD_MARK_CLASS}">◈</span>` +
    `<span class="${MMD_NAME_CLASS}">流程图</span>` +
    (hasSvg
      ? `<div class="${MMD_TABS_CLASS}" role="tablist" aria-label="流程图视图">` +
        `<button type="button" class="${MMD_TAB_CLASS} is-on" role="tab" aria-selected="true" data-tab="preview">预览</button>` +
        `<button type="button" class="${MMD_TAB_CLASS}" role="tab" aria-selected="false" data-tab="source">源码</button></div>`
      : '') +
    (hasSvg
      ? '<span class="ml-auto flex items-center gap-[5px]">' +
        '<span class="mmdc-ipct min-w-[36px] text-center font-mono text-[10.5px] tabular-nums text-[#8b949e]">100%</span>' +
        `<button type="button" class="${MMD_ICON_BTN_CLASS} mmdc-ireset" aria-label="复位到 100%">${MMD_ICON_RESET}${mmdTipSpan('复位到 100%', true)}</button>` +
        `<button type="button" class="${MMD_ICON_BTN_CLASS} mmdc-full" aria-label="全屏查看">${MMD_ICON_FULL}${mmdTipSpan('全屏查看', true)}</button>` +
        '</span>'
      : '') +
    '</div>'
  const preview = hasSvg
    ? `<div class="${MMD_PREVIEW_PANE_CLASS}" data-pane="preview">` +
      `<div class="${MMD_PAN_CLASS}"><div class="${MMD_STAGE_CLASS}">${svg}</div></div></div>`
    : ''
  const source =
    `<div class="${hasSvg ? MMD_SOURCE_PANE_CLASS : 'mmdc-pane'}" data-pane="source">` +
    `<div class="${MMD_SOURCE_BAR_CLASS}"><span>mermaid 源码</span><span class="ml-auto">` +
    `<button type="button" class="${COPY_ICON_BTN_CLASS} mmdc-copy" aria-label="复制源码">${COPY_ICON_SVGS}${mmdTipSpan('复制源码')}</button>` +
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
 */
async function hydrateMermaid(messageId: string, blocks: Array<{ index: number; key: string; source: string }>): Promise<void> {
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
    const svg = await renderMermaidSvg(item.source)
    const card = document.createElement('div')
    card.className = MMD_CARD_CLASS
    // svg === null（render error / 3s 超时）⇒ 源码态卡兜底（§三.7 口径；不留无 handler 的钮）
    card.innerHTML = buildMermaidCardHtml(svg === null ? null : prepareMermaidSvg(svg))
    const codeBox = card.querySelector('.mmdc-src')
    if (codeBox) {
      codeBox.textContent = item.source // textContent 赋值 = 纯文本，零 HTML 注入面
    }
    initMermaidInline(card)
    code.parentElement?.replaceWith(card)
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
 * 2A 批（裁决 2026-10-17 §15 K12：「回合结束才收」）：已决只读卡（六态全集）的折叠是
 * **回合级时序** —— 决策瞬间不折叠，等本轮结束（新建消息 / 回合文本收尾）后才收成卡头行。
 * 判据 = `turnStillOpen`：本轮 loading 仍在，且该卡属于**末条消息**；不满足才走 defaultExpanded
 * （r15：六态 → 折叠）。用户手动触碰过的卡（expandedBlocks 有显式值）永远优先，不受时序影响。
 */
function isBlockExpanded(block: Block): boolean {
  const manual = ai.expandedBlocks[block.id]
  if (manual === undefined && turnStillOpen(block) && readOnlyTerminalCard(block)) {
    return true
  }
  return resolveBlockExpanded(block, manual)
}

/** 回合是否仍在进行中（末条消息 + 本轮 loading）：已决只读卡据此保持展开到回合结束。 */
function turnStillOpen(block: Block): boolean {
  if (!ai.loading) {
    return false
  }
  const last = ai.messages[ai.messages.length - 1]
  return last !== undefined && last.id === block.messageId
}

/** 已决只读卡（六态全集）—— 唯一受「回合结束才收」时序影响的卡型。 */
function readOnlyTerminalCard(block: Block): boolean {
  return block.parts.some((part) => part.kind === 'tool' && isReadOnlyCard(part))
}

/** 组头/思考链头点击：写 store 显式态（三态记录；requiresAction 组的强制展开由 resolveBlockExpanded 兜底）。 */
function toggleBlock(block: Block): void {
  ai.setBlockExpanded(block.id, !isBlockExpanded(block))
}

/**
 * S6 批1（v7.10 ajv11 / 场景 19-e）→ 卡片三类型统一：
 * - 已决 HITL / GF 卡体恒在（门卡字段表 / GF 同一张表单），卡头恒可折叠；
 * - 普通工具卡：展开体渲染后 ≥ 1 行才可点；0 行 ⇒ disabled、无 chevron、无 hover、点击 no-op；
 * - requiresAction 待决 HITL/GF：强制展开、无 chevron，卡头**仍禁点**（放行/拒绝走门内双钮）。
 */
function cardHeadEnabled(part: ToolPart): boolean {
  if (isReadOnlyCard(part)) {
    return true
  }
  return !part.requiresAction && hasBodyRows(part)
}

/** 卡头 disabled 判据（与 enabled 互为反面，模板 `:disabled` / cursor / hover 三处同源）。 */
function cardHeadDisabled(part: ToolPart): boolean {
  return !cardHeadEnabled(part)
}

/** 展开体是否渲染：普通工具卡有内容（或待动作卡强制展开）才渲；已决只读态卡体恒在。 */
function cardBodyShown(part: ToolPart): boolean {
  return hasBodyRows(part)
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

/** 交接卡状态：活动交接 → 其 status；刷新后丢了本地态 → missing（只能放弃）。 */
function uploadHandoffStatus(part: ToolPart): 'waiting' | 'degraded' | 'missing' | 'unavailable' {
  const handoff = ai.activeUploadHandoff
  if (handoff && handoff.toolCallId === part.toolCallId) {
    return handoff.status
  }
  return 'missing'
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
    return '上传 xlsx / zip'
  }
  const hit = resolveAiRef(handoff.ref)
  const label = hit?.el.getAttribute('aria-label') ?? hit?.el.textContent?.trim() ?? ''
  return label === '' ? '上传 xlsx / zip' : label
}

/** 操作条：本轮仍在跑/挂起且刚播报过界面动作（停止后自动消失）。 */
const aiOperating = computed(() => ai.uiAnnouncement !== null && (ai.loading || ai.suspended))

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

function onSheetPreviewAbandon(): void {
  void ai.abandonStalePreview()
}

/** chevron 是否渲染：待决 HITL/GF 强制展开 ⇒ 无；已决只读卡恒有；普通工具卡有卡体才有。 */
function cardChevronShown(part: ToolPart): boolean {
  if (part.requiresAction) {
    return false
  }
  return isReadOnlyCard(part) || cardBodyShown(part)
}

/** 卡片外壳：只读卡保留 rocard 容器锚，普通卡保留原间距。 */
function cardShellClass(part: ToolPart): string {
  return isReadOnlyCard(part)
    ? 'rocard block border border-solid border-[#e3e6ea] bg-white rounded-[10px] overflow-hidden mb-3.5'
    : 'mt-1.5 border border-solid border-[#e3e6ea] rounded-[10px] bg-white overflow-hidden mb-2'
}

/** 卡容器 testid（既有锚：hitl-receipt / pending-card-* / tool-card-*）。 */
function cardTestId(part: ToolPart): string {
  if (isReadOnlyCard(part)) {
    return 'hitl-receipt'
  }
  return part.requiresAction ? `pending-card-${part.toolCallId}` : `tool-card-${part.toolCallId}`
}

/** 卡头 testid（既有锚：ro-toggle-* / block-toggle-*）。 */
function cardToggleTestId(part: ToolPart, block: Block): string {
  return isReadOnlyCard(part) ? `ro-toggle-${part.toolCallId}` : `block-toggle-${block.id}`
}

/** 右状态标签 testid（只读卡保留 ro-badge-*；其余 card-status-*）。 */
function cardStatusTestId(part: ToolPart): string {
  return isReadOnlyCard(part) ? `ro-badge-${part.toolCallId}` : `card-status-${part.toolCallId}`
}

/** 摘要 testid（只读卡保留 ro-summary-*；普通卡保留 tool-summary-*）。 */
function cardSummaryTestId(part: ToolPart): string {
  return isReadOnlyCard(part) ? `ro-summary-${part.toolCallId}` : `tool-summary-${part.toolCallId}`
}

/** 统一卡头 class：三类型同结构（36.4px = py 8+8 + 12px×1.7 标题行）。 */
function cardHeadClass(part: ToolPart): string {
  return [
    'flex w-full items-center gap-[6px] px-[10px] py-[8px] border-0 bg-transparent text-left font-[inherit] whitespace-nowrap transition-colors duration-[150ms]',
    isReadOnlyCard(part) ? 'ro-hd' : '',
    cardHeadDisabled(part) ? 'cursor-default' : 'cursor-pointer hover:bg-[#f6f7f9]'
  ].filter(Boolean).join(' ')
}

/**
 * 卡头标题。
 * - form = 表单业务标题（FormRenderer 内头已抑制，标题上提）；
 * - CONFIRM 带 `preflight.target` ⇒ **业务化标题**（demo 场景一/二/三 1:1）：
 *   `待你确认：<类型> 任务 #<id>『<title>』`（待决）/ `<类型> 任务 #<id>『<title>』`（只读）；
 * - 其余（无 preflight 的旧帧 / 普通工具）⇒ 回落工具中文名（现形态不变）。
 */
function cardTitle(part: ToolPart): string {
  if (cardTypeOf(part) === 'form') {
    return cardFormSpec(part)?.title?.trim() || '请填写以下信息'
  }
  const target = cardTypeOf(part) === 'hitl' ? part.preflight?.target : undefined
  if (target) {
    const business = `${labelWord(TASK_TYPE_LABELS, target.type)} 任务 #${target.taskId}『${target.title}』`
    return isReadOnlyCard(part) ? business : `待你确认：${business}`
  }
  return toolLabel(part.name)
}

/** 待填写 GF 摘要：必填项计数（无必填 / 无 schema ⇒ 空串）。 */
function formRequiredSummary(part: ToolPart): string {
  const required = cardFormSpec(part)?.fields.filter((field) => field.required).length ?? 0
  return required > 0 ? `${required} 项必填` : ''
}

/**
 * 卡头摘要（中性色，不承载状态）：
 * - form：待填写 = 必填计数；已提交 = submittedValues；已取消 = 未提交说明（F9 不回读残留值）；
 * - hitl 且已业务化标题（有 preflight.target）⇒ **空**（demo 卡头只有业务标题，标题已说清，
 *   摘要再报「工具名（任务 #N）」= 同屏重复）；
 * - 其余 tool/hitl：沿用工具摘要映射（与待决态同一份，随生命周期不变）。
 */
function cardSummary(part: ToolPart): string {
  if (cardTypeOf(part) === 'form') {
    if (!isReadOnlyCard(part)) {
      return formRequiredSummary(part)
    }
    return readOnlyStateOf(part) === 'cancelled' ? '未提交，填写内容不作记录' : generativeFormSummary(part)
  }
  if (cardTypeOf(part) === 'hitl' && part.preflight?.target) {
    return ''
  }
  return toolSummaryOrNull(part) ?? ''
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

function cardStatusClass(part: ToolPart): string {
  return `${CARD_STATUS_BASE} ${CARD_STATUS_TONE[cardStatusOf(part).tone]}`
}

function cardStatusLabel(part: ToolPart): string {
  return cardStatusOf(part).label
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
  return argRows(part.args)
}

/** 发布冲突明细行（N8）：从段 reason 解析出 `defCode#rowKey：message` 明细 → 表格数据。 */
function conflictRows(part: ToolPart): ConflictRow[] {
  return parsePublishConflicts(part.reason)
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
 * R-165 S3a（R11 指挥官裁决）：error 级通知判据。
 * store 的 `notice` 是**单槽纯字符串**（无级别字段），本轮以 error 帧收尾时 store 同时置
 * `lastRoundOutcome = 'error'`（stores/ai.ts:2009）—— 这是"这条 notice 来自故障"在本仓
 * 唯一可信的既有信号（S2 §4-R11 实测的上游 503 `UPSTREAM_STREAM_ERROR` 正走此路径）。
 * 局限：HTTP/网络异常（catch 分支，如「对话请求失败」）不置 `lastRoundOutcome`，仍按普通通知呈现。
 */
const noticeIsError = computed(() => ai.notice !== null && ai.lastRoundOutcome === 'error')

function alertBox(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_BOX_BASE} ${ALERT_TONE[tone].box}`
}

function alertIcon(tone: keyof typeof ALERT_TONE): string {
  return `${ALERT_ICON_BASE} ${ALERT_TONE[tone].ic}`
}

/**
 * R-165 S2：思考卡摘要（v7 `.rsum` 位）。蓝本写「已用 N 秒 · 点击展开」——本仓 reasoning 段
 * 无时长元数据（帧只带 text），故摘要取**字数 + 折叠动作**，不伪造时长。
 */
function reasoningSummary(text: string, expanded: boolean): string {
  return `共 ${text.length} 字 · ${expanded ? '点击收起' : '点击展开'}`
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
  return [{ key: '操作', value: toolLabel(part.name) }, ...argRows(part.args, true)]
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
const SUGGESTION_CHIP = 'rounded-[999px] border border-solid border-[#e3e6ea] bg-white px-[11px] py-[4px] text-[12.2px] text-[#57606a] transition-colors duration-[150ms] hover:border-[#cddcff] hover:text-[#2563eb]'

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
 * followBottom=true。一切钉底（scrollBottom / 发送 force / 回到底部 / RO 内容重钉）必经
 * 此口；渲染滞后（watch 先于 DOM 补丁触发）导致的「钉到旧底」由 nextTick 后的二次钉底
 * （批 D · D2①）与 RO 内容重钉（D2②）双重兜底。
 */
function pinBottom(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  const target = wrap.scrollHeight
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

function scrollBottom(force = false): void {
  // R-154 #5：自动钉底须尊重跟随开关 —— 用户上滑暂停后，帧增量的滚动指纹不得把视图拽回底部
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
 */
const editingMessageId = ref<string | null>(null)

function editMessage(m: ChatMessage): void {
  if (inputText.value.trim() !== '') {
    const confirmed = window.confirm('输入框已有内容，继续编辑将覆盖现有输入。是否覆盖？')
    if (!confirmed) {
      return
    }
  }
  inputText.value = m.content
  editingMessageId.value = m.id
}

/** R-163 T4：「继续」按钮判据 —— error/断流/取消后，最后一条助手消息给出消息级入口。
 *  批G · S3：被用户停止的轮**不出**「继续」（与「已停止」标识互斥）。 */
function showContinue(message: ChatMessage): boolean {
  const last = ai.messages[ai.messages.length - 1]
  return message.role === 'assistant'
    && last === message
    && message.stopped !== true
    && (ai.lastRoundOutcome === 'error' || ai.lastRoundOutcome === 'interrupted' || ai.lastRoundOutcome === 'cancelled')
}

/** 「继续」= 把「继续」当作用户消息直接发送（参考 Kimi）。 */
function continueRound(): void {
  void ai.send('继续')
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

/** R-163 T3：赞踩本地态（选中互斥、可取消）+ 上报（ rating=0=取消，幂等 upsert 在后端）。 */
const feedbackState = ref<Map<string, 1 | -1>>(new Map())

function feedbackOf(messageId: string): 1 | -1 | 0 {
  return feedbackState.value.get(messageId) ?? 0
}

function rateMessage(m: ChatMessage, rating: 1 | -1): void {
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
  void ai.submitFeedback(m.id, finalRating, m.content.slice(0, 200))
}

/**
 * R-157 #3：打字机光标显示判据 —— 消息流式中且该正文块是末尾块（与呼吸点接力）。 */
function showCaret(message: ChatMessage, blockIndex: number): boolean {
  if (!message.streaming) {
    return false
  }
  const blocks = blocksOf(message)
  return blockIndex === blocks.length - 1 && blocks[blockIndex]?.kind === 'text'
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
 * 已决 GF 的回填值（K1 + F9）：**只有提交收尾**才回填 `submittedValues`；
 * 取消 / 超时（未提交）一律 null ⇒ FormRenderer 回落初始空态（不保留半成品值）。
 */
function readOnlyFormValues(part: ToolPart): Record<string, unknown> | null {
  return readOnlyStateOf(part) === 'submitted' ? (part.submittedValues ?? null) : null
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
  // R-157 #4 → R-161：发送即统一 pinBottom 强制钉底（上滑暂停不挡住主动发送）。
  pinBottom()
  // R-157 #2（收口 MAJOR-1 语义版）：编辑锚点在 ⇒ 「改完从该条重跑」——该条文本原地更新为
  // 输入框新文本、截断其后消息、从该条重跑（skipUserRecord：不另起新用户消息）。
  const editId = editingMessageId.value
  let skipUserRecord = false
  if (editId) {
    editingMessageId.value = null
    const cut = ai.messages.findIndex((item) => item.id === editId)
    if (cut >= 0) {
      const target = ai.messages[cut] as ChatMessage
      target.parts = [{ kind: 'text', seg: 0, text, streaming: false }]
      deriveProjections(target)
      ai.messages.splice(cut + 1)
      pinBottom()
      skipUserRecord = true
    }
  }
  inputText.value = ''
  composerRef.value?.focus({ preventScroll: true })
  const result = skipUserRecord
    ? await ai.send(text, { skipUserRecord: true })
    : await ai.send(text)
  // 成功 / 主动停止：保持已清空，草稿不回流。
  if (result.outcome === 'ok' || result.outcome === 'cancelled') {
    return
  }
  // 用户已在流式中写入新内容：不覆盖，也不把失败消息设成新的编辑锚点。
  if (inputText.value !== '') {
    return
  }
  // 失败 / 断流 / 未发送：原稿回流并聚焦；新消息锚到本轮用户消息，编辑重跑沿用发送前 editId。
  inputText.value = draft
  editingMessageId.value = result.userMessageId ?? editId ?? null
  composerRef.value?.focus({ preventScroll: true })
}

function stop(): void {
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
 * 新建对话（苞 A 项 1）：立即清屏 + 换新 sessionId + 删旧会话的服务端记忆。
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
// 原注释「无需读旧投影（content/toolRuns）即可触发跟随」与实现不符，按实现残留如实对齐（读点属
// D2 明确保留面，逻辑不动）。
watch(
  () => {
    const last = ai.messages[ai.messages.length - 1]
    const tail = last ? last.parts[last.parts.length - 1] : undefined
    const tailText = tail && (tail.kind === 'text' || tail.kind === 'reasoning') ? tail.text.length : 0
    const tailStatus = tail && tail.kind === 'tool' ? tail.status : ''
    return `${ai.messages.length}|${last ? last.parts.length : 0}|${tail ? tail.kind : ''}|${tailText}|${tailStatus}|${last && last.pendingCall ? last.pendingCall.status : ''}`
  },
  async () => {
    scrollBottom()
    // R-165 S4：用户消息长度也会随历史回读 / 编辑重跑变化 —— 同一帧后重跑单一 measure
    // （内容变化不进滚动指纹的折叠判据，故在本 watch 内顺带兜底）。
    void nextTick(measureUserMsgOverflow)
    // 批 D · D2①：watch 回调跑在**组件更新之前**（flush:'pre'），此时读到的 scrollHeight 还是
    // 旧内容的高度 —— 钉底会停在旧底，等 DOM 补丁把内容垫高后视图就离底一截（长段补发尤其明显）。
    // 因此在同一帧的 DOM 补丁之后再钉一次（跟随态下才钉，尊重用户上滑）。
    await nextTick()
    scrollBottom()
  }
)

watch(() => ai.expanded, (expanded) => {
  if (expanded) {
    // R-161：撤 :1052 setTimeout hack —— RO 不覆盖「容器自身尺寸变」的时序（面板展开/收起
    // 导致 clientHeight 变、内容相对底部位置漂移），expanded watch 保留但改走统一 pinBottom()。
    pinBottom()
  }
})

/**
 * R-161（RC-1/2 修复）→ 批 D · D2②：RO 内容重钉底。
 *
 * 「只增高度、不进滚动指纹」的内容插入（确认门 / 生成式表单 / 工具卡 / 折叠展开）由
 * ResizeObserver 兜底：**内容层**（{@link msgContentRef}）长高且处于跟随态 ⇒ pinBottom()；
 * 高度减小的回调不动作。
 *
 * 观测目标改内容层的原因：滚动容器（{@link msgWrapRef}）的盒子由 flex 布局定死，内容在
 * `overflow-y-auto` 里长高只改它的 scrollHeight、**不改它的 box**，RO 观测它等于观测一个
 * 不变的目标 —— 真正的"内容长高"信号只有内容层的尺寸变化才给得出。
 */
let msgResizeObserver: ResizeObserver | null = null
let lastMsgScrollHeight = 0

function onMsgWrapResized(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  const height = wrap.scrollHeight
  if (height > lastMsgScrollHeight && followBottom.value) {
    pinBottom()
  }
  lastMsgScrollHeight = height
}


// ── 副标题 ────────────────────────────────────────────────────────────────────
/**
 * 副标题（P5）：展示**后端当前生效的模型**（`/health.model`）——纯展示，不承担切换。
 * 批 E · E1 起不能读前端选择态（那里已没有选择）：健康探针是"当前到底用哪个模型"的唯一真值。
 */
const currentModelLabel = computed(() => ai.health?.model || '流式对话 · 工具调用')

onMounted(async () => {
  // 批G · R2：订阅必须在读历史**之前**装（刷新后首帧要落盘），但启动期 `messages=[]` 不是
  // "确实为空"而是"还没读" —— 下面 `ensureTabOwnership()` 的 state 变更会触发一次即时写盘，
  // 早先把上一页签留下的持久化镜像清空（真机打点 `SET n=0 → GET n=0 → SET n=3`），
  // 令 `loadHistory` 的 readMirror 读到空镜像、G3 合并在途轮空转。竞态由 store 侧闸住
  // （`writeMirrorNow` 在 historyLoaded 之前不写空态），此处挂载次序保持不动。
  ai.startMirror()
  ai.startWorkspaceSync()
  void ai.probeHealth()
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
  const wrap = msgWrapRef.value
  if (wrap && typeof ResizeObserver !== 'undefined') {
    lastMsgScrollHeight = wrap.scrollHeight
    msgResizeObserver = new ResizeObserver(onMsgWrapResized)
    // D2②：观测内容层（滚动容器自身的 box 不随内容变，观测它对内容增长零触发）
    msgResizeObserver.observe(msgContentRef.value ?? wrap)
  }
  // R-165 S5a 项 5：长表格双区横滚联动（scroll 不冒泡 ⇒ 消息容器上捕获监听）
  wrap?.addEventListener('scroll', onTableScrollCapture, { capture: true, passive: true })
  // R-165 S4：首渲落地后跑一次用户长消息 measure（历史回读路径；jsdom 零高环境恒不溢出）。
  await nextTick()
  measureUserMsgOverflow()
  scrollBottom()
})

onBeforeUnmount(() => {
  if (copyTimer !== null) {
    clearTimeout(copyTimer)
    copyTimer = null
  }
  for (const timer of fadeTimers.values()) {
    clearTimeout(timer)
  }
  fadeTimers.clear()
  // R-161：RO disconnect（照上件纪律）+ 平滑回底类计时器收尾
  msgResizeObserver?.disconnect()
  msgResizeObserver = null
  msgWrapRef.value?.removeEventListener('scroll', onTableScrollCapture, { capture: true })
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
  ai.stopWorkspaceSync()
})
</script>

<!-- R-165 S5c：本组件不再持有任何 scoped 样式块（曾承载 .md-body 排版 / 光标 / 呼吸 / chevron /
     scroll-smooth / reduced-motion 抑制）。全部改工具类：
     - v-html 正文族 = 渲染层逐标签注入（utils/markdown.ts，故 v-html 节点无 scope 属性也不再是问题）；
     - 光标 / 呼吸 / chevron = 模板类串（CARET_CLASS / BREATH_CLASS / TCHEV_CLASS）；
     - 三个 keyframes = tailwind.config.js 的 theme.extend.keyframes/animation；
     - 平滑回底 = Tailwind 内置 `scroll-smooth` 工具类（JS 只做 classList 切换，类名不变）；
     - reduced-motion：呼吸**豁免**（= 生成中状态语义，非纯装饰，R-161 用户实测回归口径）
       改在光标元素上写 `motion-reduce:animate-none`（纯装饰抑制保留），媒体查询不再手写。 -->
