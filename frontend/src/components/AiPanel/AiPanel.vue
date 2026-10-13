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

    <!-- 消息流（蓝本 AiPanel.vue:21-89）；R-154 #5：外层 relative 壳承载「回到底部」浮钮 -->
    <div class="flex-1 relative min-h-0 flex flex-col">
      <!-- R-161：role="log" 补滚动容器（无障碍，无视觉影响） -->
      <div ref="msgWrapRef" role="log" class="flex-1 overflow-y-auto p-3" @scroll.passive="onMsgScroll">
      <!-- 历史对账期骨架屏（苞 B 项 4）：消费 store 的 historyLoaded —— 与空态互斥
           （无消息且对账未完 → 骨架屏；对账完成后才轮到空态/消息流）。 -->
      <el-skeleton
        v-if="!ai.messages.length && !ai.historyLoaded"
        data-testid="history-skeleton"
        :rows="4"
        animated
        class="p-2"
      />
      <el-empty v-else-if="!ai.messages.length" :image-size="60">
        <template #image>
          <el-icon :size="36" color="#409EFF"><Promotion /></el-icon>
        </template>
        <template #description>
          <div class="mb-1 text-sm font-semibold text-[#606266]">您好，我是实施助手</div>
          <div class="text-[13px] text-[#909399] leading-[1.8]">
            我可以帮您查询配置项与字段、查看任务、预估数据行数、<br />
            打开导出结果在线编辑器、下载导出文件，<br />并启动导出/预检查/导入/发布作业。
          </div>
          <div class="mt-2 text-xs text-[#c0c4cc]">对话保存在当前浏览器页签中，关闭页签即结束。</div>
          <!-- R-154 #12：空态静态建议提示词（runtime 驱动建议词登记挂账，本批不做）——点击回填输入框 -->
          <div class="mt-3 flex flex-wrap justify-center gap-2">
            <el-button
              v-for="suggestion in SUGGESTIONS"
              :key="suggestion"
              size="small"
              round
              plain
              data-testid="suggestion-chip"
              @click="useSuggestion(suggestion)"
            >
              {{ suggestion }}
            </el-button>
          </div>
        </template>
      </el-empty>

      <div
        v-for="m in ai.messages"
        :key="m.id"
        class="group flex gap-2 mb-3.5"
        :class="m.role === 'user' ? 'flex-row-reverse' : ''"
      >
        <div
          class="w-7 h-7 rounded-full flex items-center justify-center shrink-0"
          :class="m.role === 'user' ? 'bg-[#e4e7ed]' : 'bg-gradient-to-br from-[#409eff] to-[#7b61ff]'"
          :data-testid="'msg-avatar-' + m.id"
        >
          <!-- R-157 #5：头像去文字 —— AI = 蓝紫渐变四角星（复用头部渐变语言），用户 = 灰色人形 -->
          <svg v-if="m.role === 'user'" viewBox="0 0 24 24" fill="#909399" class="w-4 h-4" aria-hidden="true">
            <path d="M12 12a5 5 0 1 0-5-5 5 5 0 0 0 5 5zm0 2c-4 0-8 2-8 5v2h16v-2c0-3-4-5-8-5z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" class="w-4 h-4" aria-hidden="true">
            <path d="M12 2l2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4z" fill="#fff" />
          </svg>
        </div>
        <!-- 内容列：气泡 + 指示器 + 操作行（R-157 #1：操作行挪气泡外、卡片下沿独立行） -->
        <div class="min-w-0 flex-1 flex flex-col" :class="m.role === 'user' ? 'items-end' : 'items-start'">
        <!-- R-160 M4（issue #14③ 方案 A）：气泡容器层一刀切 —— overflow:hidden + min-width:0，
             长串（无空白可断）不再越出气泡；不铺分块规则（Markdown 重做时整体替换） -->
        <div
          class="max-w-[82%] min-w-0 overflow-hidden rounded-lg px-2.5 py-2 text-[13px] text-[#303133] leading-[1.6] break-words"
          :class="m.role === 'user' ? 'bg-[#d9ecff]' : 'bg-[#f4f4f5]'"
          :data-testid="'msg-bubble-' + m.id"
        >
          <!-- 消息体：块分派（批4④ A5' → R-154 单卡化）——groupParts 把段序列分派为 text/activity 块；
               R-154 #13+14 起 activity 恒为单段卡（组壳已拆），展开态读 store 三态
               （expandedBlocks ?? defaultExpanded，D4/D6/D-C6）；key 用块稳定 id
               （成员 toolCallId / kind-seg，加消息命名空间前缀，收口修 F1/红队 M-1），禁用数组下标（§7 R4-3 / D5）。 -->
          <template v-for="(block, bi) in blocksOf(m)" :key="block.id">
            <!-- 正文块：单段正文，永不进折叠组（A5' 规则③）；
                 R-157 #3：流式中正文末尾打字机光标；R-159 #6：末尾 CH_TAIL 字符包 span.ch 渐显（新字符 chIn）；
                 R-159 #4：完成时光标同帧淡出（fade-out，300ms 后随 isFading 退役）。 -->
            <div v-if="block.kind === 'text'" class="whitespace-pre-wrap">{{ chSplit(m, block).head }}<span v-for="(ch, ci) in chSplit(m, block).tail" :key="ci" class="ch">{{ ch }}</span><span v-if="showCaret(m, bi) || isFading(m)" class="caret" :class="{ 'fade-out': isFading(m) }" data-testid="stream-caret"></span></div>

            <!-- R-160 M2：HITL 只读回执**独立块**（R-158 #10 判据随块层判，块 = 已决确认门/GF 段）——
                 紧随源工具卡渲染，恒可见（不参与 expandedBlocks，无折叠卡头），刷新/历史恢复后可回溯。
                 徽标按段终态派生，decision/decidedAt/submittedValues 为回读/写回元数据。 -->
            <template v-else-if="block.kind === 'receipt'">
              <template v-for="part in block.parts" :key="'receipt-' + (part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg)">
                <div v-if="part.kind === 'tool'" class="mt-1.5 px-2 py-1.5 bg-[#f0f9ff] border border-solid border-[#d9ecff] rounded" data-testid="hitl-receipt">
                  <div class="flex items-center gap-1.5 flex-wrap">
                    <el-tag size="small" :type="hitlReceiptTagType(part.status)" effect="light">{{ hitlReceiptLabel(part) }}</el-tag>
                    <span class="text-[#606266]">{{ hitlReceiptSummary(part) }}</span>
                    <span v-if="part.decidedAt" class="text-[#c0c4cc]">{{ formatTs(part.decidedAt) }}</span>
                  </div>
                  <div v-if="part.submittedValues" class="mt-1 text-[#909399]" data-testid="receipt-values">
                    已提交：{{ submittedValuesSummary(part.submittedValues) }}
                  </div>
                  <div v-if="part.args" class="mt-1">
                    <button
                      type="button"
                      class="text-[#409eff]"
                      :data-testid="'receipt-args-toggle-' + part.toolCallId"
                      @click="toggleReceiptArgs(part.toolCallId)"
                    >
                      原始参数 {{ receiptArgsOpen.has(part.toolCallId) ? '▾' : '▸' }}
                    </button>
                    <div v-show="receiptArgsOpen.has(part.toolCallId)" class="mt-0.5" :data-testid="'receipt-args-more-' + part.toolCallId">
                      <CollapsibleText :text="part.args" pretty />
                    </div>
                  </div>
                </div>
              </template>
            </template>

            <template v-else>
              <!-- R-154 #13+14（用户直裁「彻底拆除」）：组壳退役 —— 每段恒一张卡。
                   卡头折叠单行 = 工具名 + kind tag + 状态 tag（折叠态即可辨识工具与进展）；
                   点击卡头展开/收起 args/result/错误位（v-show：DOM 恒在，testid/可见性稳定）；
                   requiresAction 审批卡强制展开（卡头禁点，resolveBlockExpanded 兜底）。 -->
              <template v-for="part in block.parts" :key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg">
                <!-- 思考链（A6'）：受控折叠块，口径不变（默认折叠；展开态存 store，DOM 重建不丢） -->
                <div v-if="part.kind === 'reasoning' && part.text" class="mb-1.5 text-xs text-[#909399]">
                  <button
                    type="button"
                    class="cursor-pointer"
                    :data-testid="'reasoning-toggle-' + block.id"
                    @click="toggleBlock(block)"
                  >
                    思考过程 {{ isBlockExpanded(block) ? '▾' : '▸' }}
                  </button>
                  <div v-show="isBlockExpanded(block)" class="mt-1 px-2 py-1.5 bg-[#fafafa] border-l-2 border-solid border-[#dcdfe6]" :data-testid="'reasoning-body-' + block.id">
                    <!-- R-158 #12：思考区同款 CollapsibleText（真溢出才渐隐+显示更多） -->
                    <CollapsibleText :text="part.text" :max-height="160" :testid="'reasoning-more-' + block.id" class="text-xs text-[#909399]" />
                  </div>
                </div>
                <template v-else-if="part.kind === 'tool'">
                  <div
                    class="mt-1.5 border border-solid border-[#dcdfe6] rounded-md bg-white"
                    :data-testid="part.requiresAction ? 'pending-card-' + part.toolCallId : 'tool-card-' + part.toolCallId"
                  >
                    <button
                      type="button"
                      class="w-full flex items-center gap-1.5 p-2 text-left font-semibold text-[13px] flex-wrap"
                      :class="part.requiresAction ? 'cursor-default' : 'cursor-pointer'"
                      :data-testid="'block-toggle-' + block.id"
                      :disabled="part.requiresAction"
                      @click="toggleBlock(block)"
                    >
                      <el-icon color="#409EFF"><Operation /></el-icon>
                      <span>{{ toolLabel(part.name) }}</span>
                      <el-tag v-if="part.kind2 === 'FRONTEND'" size="small" effect="plain">前端工具</el-tag>
                      <el-tag v-else-if="part.kind2 === 'CONFIRM'" size="small" type="warning" effect="plain">确认门</el-tag>
                      <el-tag :size="'small'" :type="toolTagType(part.status)" :effect="part.status === 'running' ? 'dark' : 'light'">
                        {{ toolStatusLabel(part.status) }}
                      </el-tag>
                    </button>
                    <div v-show="isBlockExpanded(block)" class="px-2 pb-2 pt-0 flex flex-col gap-1.5" :data-testid="'block-body-' + block.id">
                      <!-- R-158 #12：args pretty-print + 真溢出渐隐（CollapsibleText） -->
                      <div v-if="part.args" class="text-xs text-[#909399] break-all">
                        <CollapsibleText :text="part.args" pretty :testid="'args-more-' + part.toolCallId" />
                      </div>
                      <!-- R-154 #2 角标（帧侧截断标记）保留 + R-158 #12 展示层移交 CollapsibleText：
                           真溢出才渐隐 + 「显示更多」（原本地去帽 max-h 组件态随之退役） -->
                      <div v-if="part.result" class="px-2 py-1 text-xs text-[#606266] bg-[#fafafa] rounded">
                        <CollapsibleText :text="part.result" :testid="'show-more-' + part.toolCallId">
                          {{ part.result }}<span
                            v-if="part.truncated"
                            class="inline-block ml-1 px-1 rounded text-[10px] leading-4 bg-[#e6a23c] text-white align-middle"
                            :title="part.truncatedReason ?? '结果被截断'"
                            data-testid="truncated-badge"
                          >已截断</span>
                        </CollapsibleText>
                      </div>
                      <!-- R-158 #6：卡内错误位（图标 + 红底 + 文案）——错误就地呈现，不再全堆顶部 Alert -->
                      <div v-if="part.reason" class="flex items-start gap-1 px-2 py-1 text-xs text-[#f56c6c] bg-[#fef0f0] rounded" data-testid="tool-error">
                        <el-icon class="mt-px shrink-0" color="#f56c6c"><WarningFilled /></el-icon>
                        <span>原因：{{ part.reason }}</span>
                      </div>

                      <!-- 挂起子块（段判据，A1'③）：CONFIRM 待决 → 确认块；GF → FormRenderer（宿主条件
                           逐字保留，D10）；其余通用 FRONTEND 待执行/已决态 → 无子块（消 A9'⑤ 空壳卡）。
                           D-C7：旧投影挂起卡的「会话已失效」tag（原 :206）与 GF 非宿主 v-else 三 tag
                           （原 :251-253）/ activeForm 错误位（原 :254-257）随合并整列删除——语义由段状态
                           tag（toolStatusLabel，expired→「会话已失效」）与段 reason 位（A4'，D11）承接。 -->
                      <template v-if="part.requiresAction">
                        <template v-if="part.kind2 === 'CONFIRM' && part.status === 'pending'">
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

                        <div v-else-if="part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL" class="mt-2">
                          <!-- GF-B：生成式表单挂起 —— 渲染 FormRenderer 等用户填写（同 toolCallId 可重发/取消） -->
                          <!-- T2a-D#3（D10 不扩判据）：同 toolCallId 至多一个 FormRenderer —— 只渲染在宿主
                               消息（最后一条持该挂起调用的消息）上；非宿主消息不再出独立 tag 列（D-C7 已删） -->
                          <FormRenderer
                            v-if="liveForm && liveForm.toolCallId === part.toolCallId && m.id === formHostMessageId"
                            :key="liveForm.toolCallId"
                            :schema="liveForm.form"
                            :submitting="liveForm.status === 'submitting'"
                            :error="liveForm.error"
                            :remaining-seconds="formRemainingSeconds"
                            @submit="onFormSubmit"
                            @cancel="onFormCancel"
                          />
                        </div>
                      </template>
                    </div>
                  </div>
                </template>
              </template>
            </template>
          </template>

        </div>

        <!-- R-159 #2/#3/#4：单一元信息行（demo aiShell(fix) 结构）——左位 = 流式期「工作中…」呼吸
             常驻（发出即现，首字符不消失；完成时淡出换时间戳）/ 完成后时间戳；右位 = 操作
             （流式期 visibility:hidden 占位，完成换入；hover/focus-within 浮出）。
             行判据：有正文 或 助手消息流式中（发出即现，无正文也要能呼吸）。 -->
        <div
          v-if="showMetaRow(m)"
          class="mt-1 flex items-center justify-between gap-2 text-xs text-[#a8abb2] w-full max-w-[82%]"
          :class="m.role === 'user' ? 'flex-row-reverse' : ''"
          :data-testid="'msg-ops-' + m.id"
        >
          <span class="flex items-center gap-1 min-h-[18px]">
            <span
              v-if="isBreathing(m) || isFading(m)"
              class="loading-breath"
              :class="{ 'fade-out': isFading(m) }"
              style="margin:0;padding-left:2px"
              data-testid="loading-breath"
            >
              <span class="dot"></span><span class="txt">工作中…</span>
            </span>
            <span v-else-if="formatTimestamp(m)" class="text-[#c0c4cc]" data-testid="msg-timestamp">{{ formatTimestamp(m) }}</span>
          </span>
          <div
            class="flex items-center gap-1 transition-opacity"
            :class="m.streaming ? '' : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100'"
            :style="m.streaming && m.role === 'assistant' ? 'visibility: hidden' : ''"
          >
            <span
              v-if="copyState && copyState.id === m.id"
              :data-testid="'copy-hint-' + m.id"
              class="text-xs"
              :class="copyState.ok ? 'text-[#67c23a]' : 'text-[#f56c6c]'"
            >
              {{ copyState.ok ? '已复制' : '复制失败，请手动选中文本复制' }}
            </span>
            <el-button
              v-if="m.role === 'user'"
              link
              size="small"
              class="px-1"
              :data-testid="'edit-msg-' + m.id"
              title="编辑该条消息（改完从该条重跑）"
              aria-label="编辑该条消息（改完从该条重跑）"
              @click="editMessage(m)"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" class="w-3 h-3" aria-hidden="true"><path d="M3 17.2V21h3.8l11-11-3.8-3.8zM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.7 3.7z" /></svg>
            </el-button>
            <el-button
              link
              size="small"
              class="px-1"
              :data-testid="'copy-msg-' + m.id"
              title="复制该条消息正文"
              aria-label="复制该条消息正文"
              @click="copyMessage(m)"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" class="w-3 h-3" aria-hidden="true"><path d="M16 1H4a2 2 0 0 0-2 2v14h2V3h12zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11z" /></svg>
            </el-button>
          </div>
        </div>
        </div>
      </div>
      </div>

      <!-- R-154 #5：上滑暂停跟随后浮出的「回到底部」——点击恢复跟随并滚底 -->
      <button
        v-if="showBackToBottom && ai.messages.length > 0"
        type="button"
        data-testid="back-to-bottom"
        class="absolute bottom-3 left-1/2 -translate-x-1/2 px-3 py-1.5 text-xs rounded-full bg-white border border-solid border-[#dcdfe6] shadow-md text-[#606266] hover:text-[#409eff]"
        @click="backToBottom"
      >
        回到底部
      </button>
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
          <el-button size="small" type="primary" data-testid="send-btn" :loading="ai.loading" :disabled="!canSubmit" @click="send">发送</el-button>
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
 * - 工具卡四态（调用中/成功/失败/等待确认）并展示参数与结果 —— 蓝本是"已执行工具 X"单标签；
 * - 生成式表单挂起卡（GF-B）：generative_form 帧渲染 FormRenderer 等用户填写，取消/超时带
 *   cancelled:true 回灌 —— 蓝本无此前端工具通道；
 * - HITL 卡片：确认/拒绝双路径 + 拒绝原因输入 + 超时倒计时置灰 —— 蓝本有双按钮但无原因与倒计时；
 * - 409/503/断流三类降级提示（EP Alert）与一键重挂 —— 蓝本是蓝本后端（无 SESSION_BUSY/挂起池）；
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
 * 样式纪律：模板全部用 Element Plus + 内联 Tailwind 工具类；唯一的 scoped CSS 是
 * 蓝本确有的 18 行打字机动画（`.typing .dot` + `@keyframes blink`，Tailwind 无对应工具类）。
 * 项 2 的 hover 显现用 Tailwind 默认 core plugin 的 `group` / `group-hover`（本仓首用）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, ChatDotRound, Operation, Promotion, WarningFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { useAiStore, foldDeadline } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'
import { formatCountdown } from '@/utils/format'
import { groupParts, resolveBlockExpanded, type Block } from '@/utils/blocks'
import { deriveCopyText, deriveProjections } from '@/utils/parts'
import { createPollingController, type PollingController } from '@/utils/polling'
import FormRenderer from '@/components/AiPanel/FormRenderer.vue'
import CollapsibleText from '@/components/AiPanel/CollapsibleText.vue'
import { GENERATIVE_FORM_TOOL, type ChatMessage, type PendingToolCall, type ToolPart, type ToolRunStatus } from '@/types/ai'

const ai = useAiStore()
const workspace = useWorkspaceStore()

const inputText = ref('')
const rejectReason = ref('')
const deciding = ref(false)
const msgWrapRef = ref<HTMLElement | null>(null)

/**
 * R-154 #5 → R-161：钉底跟随的「用户上滑即暂停」策略。
 * followBottom = 自动钉底开关：容器 @scroll 离底判定 —— 用户上滑（离底超过阈值）置 false
 * 暂停自动跟随，回到底部附近自动恢复；唯一点「回到底部」浮钮 = 强制恢复并滚底。
 * 渲染真值仍为 parts（与自动滚动指纹 watch 同源），禁读 content。
 * R-161 阈值拆分：一值双用是「上滚一点就出钮」的根因 —— 跟随闸 32px 保留，
 * 浮钮显隐走独立滞回带（离底 >64 显 / <24 隐，常量化与 clientHeight 无关）。
 */
const followBottom = ref(true)
/** 跟随解除/恢复闸（px）：离底小于它即视为贴底。 */
const FOLLOW_THRESHOLD_PX = 32
/** R-161 浮钮滞回：离底大于它才显示（业界主流口径）。 */
const SHOW_BACK_TO_BOTTOM_PX = 64
/** R-161 浮钮滞回：离底小于它才隐藏。 */
const HIDE_BACK_TO_BOTTOM_PX = 24

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
  // 回声闸：|scrollTop − 落点| ≤ 1px ⇒ 程序化钉底的回声，跳过跟随闸
  if (pinGuardTop !== null && Math.abs(wrap.scrollTop - pinGuardTop) <= 1) {
    updateBackToBottom(bottomDist(wrap))
    return
  }
  pinGuardTop = null
  followBottom.value = bottomDist(wrap) < FOLLOW_THRESHOLD_PX
  updateBackToBottom(bottomDist(wrap))
}

/** R-161 滞回带显隐：带内（24~64）保持原状，防边界抖动；非 !followBottom 纯计算（会审 M3）。 */
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
 * 逐条复制的反馈（苞 B 项 2）：**组件局部状态**，生命周期 2s。
 * 为什么不复用 `ai.notice`：它是单槽位（一次只承载一条提示），"已复制"会把并发的
 * 断流/取消等提示挤掉 —— 局部状态只作用于被点的那条消息。
 */
const copyState = ref<{ id: string; ok: boolean } | null>(null)
let copyTimer: ReturnType<typeof setTimeout> | null = null

/** 倒计时节拍（每秒推进一次；确认卡据此置灰）。 */
const now = ref(Date.now())
const confirmDeadline = ref<number | null>(null)
/** 节拍间隔（规格：1s）。 */
const TICK_MS = 1000
/**
 * 节拍器（步2 接入可见性纪律 controller，节奏 1s/1s ⇒ 单节奏）。
 * 回可见时 controller 会错峰补刷一次，run 本身即"重写 now" ⇒ 倒计时**立即重算**，
 * 不会停在已过期的假倒计时上。
 */
let tickController: PollingController | null = null

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
  download_export_files: '下载导出文件',
  navigate_to: '跳转页面',
  [GENERATIVE_FORM_TOOL]: '生成式表单',
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

/** R-154 #12：空态静态建议提示词（点击回填输入框；runtime 驱动建议词登记挂账，本批不做）。 */
const SUGGESTIONS = ['列出全部配置定义', '查看当前工作区状态', '预估导出数据行数', '启动导出作业']

function useSuggestion(text: string): void {
  inputText.value = text
}

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

/** R-159 #6：新字符渐显 —— 末尾 CH_TAIL 字符包 span.ch（chIn .18s），其余为稳定前缀文本。
 *  纯展示切分，不写段、不调 deriveProjections（delta 性能锁步零触碰，S0-a 实证见报告）。 */
const CH_TAIL = 8

function chSplit(message: ChatMessage, block: Block): { head: string; tail: string[] } {
  if (!message.streaming) {
    return { head: textOf(block), tail: [] }
  }
  const text = textOf(block)
  if (text.length <= CH_TAIL) {
    return { head: '', tail: [...text] }
  }
  return { head: text.slice(0, -CH_TAIL), tail: [...text.slice(-CH_TAIL)] }
}

/** 批4④ A5'：消息 → 渲染块序列（groupParts 纯函数；parts 为响应式数组，帧到达即重算）。
 *  收口修 F1：传 m.id 作消息命名空间前缀，防跨消息 blockId 碰撞（红队 M-1）。 */
function blocksOf(message: ChatMessage): Block[] {
  return groupParts(message.parts, message.id)
}

/** 正文块文本（类型收窄：text 块首成员恒为 text 段）。 */
function textOf(block: Block): string {
  const part = block.parts[0]
  return part.kind === 'text' ? part.text : ''
}

/**
 * 块展开态三态判定（D4/D6/D-C6）：requiresAction 组强制展开（手动收起不生效）；
 * 否则 expandedBlocks[id] 显式值优先（用户手动展开永不自动收起 / 手动收起不被自动打开）；
 * 未触碰（键缺省）回落 defaultExpanded。
 */
function isBlockExpanded(block: Block): boolean {
  return resolveBlockExpanded(block, ai.expandedBlocks[block.id])
}

/** 组头/思考链头点击：写 store 显式态（三态记录；requiresAction 组的强制展开由 resolveBlockExpanded 兜底）。 */
function toggleBlock(block: Block): void {
  ai.setBlockExpanded(block.id, !isBlockExpanded(block))
}

function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

/** 工具卡状态色（补丁③：REJECTED=warning「已拒绝」，FAILED=danger「执行失败」，互不混用）。 */
function toolTagType(status: ToolRunStatus): 'info' | 'warning' | 'success' | 'danger' | 'primary' {
  // 批3③a D10(A)：九态全部显式 case + 去 default（原先 pending 由 default 兜底，非纯死码）——
  // 形式对齐下方 toolStatusLabel 的 Record<ToolRunStatus, …> 穷尽闸：未来新增态编译期即报错，
  // 不再被 default 静默误标。
  switch (status) {
    case 'pending':
      return 'primary'
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
    // 对话时序包 批1：取消终态与"会话已失效"同族（info）—— 都不是执行成功/失败，
    // 只是这一轮没走完（用户取消 / 渲染器不可用 / 整轮取消）
    case 'cancelled':
      return 'info'
    case 'unknown':
      // 对话时序包 批1：结局未知（未确认成功/失败，如前端执行器超时）—— 提示级 warning，
      // 与"执行失败/已拒绝"同色的待人工判断，不落 danger
      return 'warning'
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
    expired: '会话已失效',
    // 对话时序包 批1：取消终态（用户放弃 / 渲染器不可用 / 整轮取消）
    cancelled: '已取消',
    // 对话时序包 批1：结局未知 —— 未确认成功/失败，需人工再确认
    unknown: '结局未知（未确认成功/失败）'
  }
  return labels[status]
}

function probe(): void {
  void ai.probeHealth()
}

/**
 * R-161：统一程序化钉底入口 —— 设 scrollTop=scrollHeight + 记落点 pinGuardTop + 置
 * followBottom=true。一切钉底（scrollBottom / 发送 force / 回到底部 / RO 内容重钉）必经
 * 此口；渲染滞后（watch 先于 DOM 补丁触发）导致的「钉到旧底」由 RO 回调重钉兜底。
 */
function pinBottom(): void {
  const wrap = msgWrapRef.value
  if (!wrap) {
    return
  }
  wrap.scrollTop = wrap.scrollHeight
  pinGuardTop = wrap.scrollTop
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
 * R-154 #17：消息时间戳渲染（仅展示、可选）。历史路 = 后端条目 metadata.timestamp
 * （epoch 毫秒或 ISO 字符串，原样透传）；实时路 = message_start 帧 atMs。
 * 无法解析 / 缺失 ⇒ 空串（操作行不渲染时间位）。R-158 #10 复用于回执决策时刻。
 */
function formatTs(ts: unknown): string {
  if (ts === undefined || ts === null) {
    return ''
  }
  const parsed = typeof ts === 'number' ? dayjs(ts) : dayjs(String(ts))
  return parsed.isValid() ? parsed.format('MM-DD HH:mm') : ''
}

function formatTimestamp(message: ChatMessage): string {
  return formatTs(message.timestamp)
}

/**
 * R-157 #2：编辑 = 「改完从该条重跑」——回填该条正文并记住锚点；发送时（send）截断
 * 该条之后的消息、以输入框新文本重发。仅用户消息可编辑（模板 v-if role 过滤）。
 */
const editingMessageId = ref<string | null>(null)

function editMessage(m: ChatMessage): void {
  inputText.value = m.content
  editingMessageId.value = m.id
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
 * R-158 #10 → R-160 M2：回执判据上移块层（utils/blocks.ts isReceiptPart —— 判据不变：
 * 确认门 / 生成式表单工具的已决段），AiPanel 只负责渲染回执块内容。
 */

/** 回执状态徽标文案：决策态优先（收口 MINOR-1）——已确认须 approve 即显、不等 succeeded；
 *  已确认但执行失败仍显「已确认」（徽标系决策态，非工具执行态）；decision 缺省（历史数据/
 *  过期态/取消）回退 status 派生。 */
function hitlReceiptLabel(part: ToolPart): string {
  if (part.decision === 'approved') {
    return '已确认'
  }
  if (part.decision === 'rejected') {
    return '已拒绝'
  }
  switch (part.status) {
    case 'succeeded':
      return '已确认'
    case 'rejected':
      return '已拒绝'
    case 'expired':
      return '已过期'
    case 'cancelled':
      return '已取消'
    case 'running':
      return '执行中'
    case 'failed':
      return '执行失败'
    default:
      return '已处理'
  }
}

function hitlReceiptTagType(status: ToolRunStatus): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
  switch (status) {
    case 'succeeded':
      return 'success'
    case 'rejected':
    case 'cancelled':
      return 'info'
    case 'expired':
      return 'warning'
    case 'failed':
      return 'danger'
    case 'running':
      return 'primary'
    default:
      return 'info'
  }
}

/** 参数自然语言摘要：工具中文名 + args 前三键值（工具名映射表口径，非 LLM 生成） */
function hitlReceiptSummary(part: ToolPart): string {
  const label = toolLabel(part.name)
  if (!part.args) {
    return label
  }
  try {
    const parsed = JSON.parse(part.args) as Record<string, unknown>
    const pairs = Object.entries(parsed).slice(0, 3)
      .map(([key, value]) => `${key}=${typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
    return pairs.length > 0 ? `${label} · ${pairs.join('，')}` : label
  } catch {
    return label
  }
}

/** GF 提交值回执摘要（截断防长行） */
function submittedValuesSummary(values: Record<string, unknown>): string {
  const text = JSON.stringify(values)
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

/** 回执卡「原始参数」展开态（组件局部，按 toolCallId 记账） */
const receiptArgsOpen = ref<Set<string>>(new Set())

function toggleReceiptArgs(toolCallId: string): void {
  const next = new Set(receiptArgsOpen.value)
  if (next.has(toolCallId)) {
    next.delete(toolCallId)
  } else {
    next.add(toolCallId)
  }
  receiptArgsOpen.value = next
}

async function send(): Promise<void> {
  const text = inputText.value.trim()
  if (!text) {
    // 空内容静默（用户没输入，不值得打扰）
    return
  }
  if (!ai.canSend) {
    // R4（issue#5）：面板层守卫同样不静默 —— **保留输入框内容**（不消费草稿），给出可读反馈。
    // 当前 UI 下真实 Enter 到不了这里（输入区 disabled），这是纵深防御：一旦放开 disabled
    // 或走到 loading/轮次失步窗口，用户不会"消息被静默丢弃"。
    ai.noticeBusySend()
    return
  }
  // R-157 #4 → R-161：发送即统一 pinBottom 强制钉底（上滑暂停不挡住主动发送）。
  pinBottom()
  // R-157 #2（收口 MAJOR-1 语义版）：编辑锚点在 ⇒ 「改完从该条重跑」——该条文本原地更新为
  // 输入框新文本、截断其后消息、从该条重跑（skipUserRecord：不另起新用户消息）。
  const editId = editingMessageId.value
  if (editId) {
    editingMessageId.value = null
    const cut = ai.messages.findIndex((item) => item.id === editId)
    if (cut >= 0) {
      const target = ai.messages[cut] as ChatMessage
      target.parts = [{ kind: 'text', seg: 0, text, streaming: false }]
      deriveProjections(target)
      ai.messages.splice(cut + 1)
      pinBottom()
      inputText.value = ''
      await ai.send(text, { skipUserRecord: true })
      return
    }
  }
  pinBottom()
  inputText.value = ''
  await ai.send(text)
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
  // T4-3（落点②，裁决 3）：按**服务端剩余时长**折算本地 deadline —— 帧带 `atMs`（实时 / 归档帧
  // 均带，后端帧构造点取值）时取 `expiresAt − atMs`（两者同为服务端钟）再加本地 now，故客户端钟
  // 漂移 X 不再整段偏移倒计时（GFb 问题 7）；`atMs` 缺失或 ≤ 0（旧格式 / 损坏数据）回落绝对
  // `expiresAt` 对本地钟、再缺回落 timeoutSeconds。
  // 口径与「重挂按帧构造时剩余时长重新起算」的代价说明见 `foldDeadline` 的注释。
  confirmDeadline.value = foldDeadline(pending.expiresAt, pending.atMs, pending.timeoutSeconds)
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
  () => scrollBottom()
)

watch(() => ai.expanded, (expanded) => {
  if (expanded) {
    // R-161：撤 :1052 setTimeout hack —— RO 不覆盖「容器自身尺寸变」的时序（面板展开/收起
    // 导致 clientHeight 变、内容相对底部位置漂移），expanded watch 保留但改走统一 pinBottom()。
    pinBottom()
  }
})

watch(
  () => ai.pendingConfirm,
  (pending) => resetPendingWatch(pending),
  { immediate: true }
)

/**
 * R-161（RC-1/2 修复）：RO 内容重钉底 —— 确认门 / 生成式表单 / 工具卡 / 折叠展开等
 * 「只增高度、不进滚动指纹」的内容插入（指纹 watch 盲区）由 ResizeObserver 兜底：
 * msgWrapRef 的 scrollHeight 增大且处于跟随态 ⇒ pinBottom()；高度减小的回调不动作。
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

onMounted(async () => {
  ai.startMirror()
  ai.startWorkspaceSync()
  tickController = createPollingController({
    intervalMs: TICK_MS, // 单节奏（S2 起无 fallbackIntervalMs：双速语义已随任务中心自刷摘除）
    run: () => {
      now.value = Date.now()
    }
  })
  tickController.start()
  void ai.probeHealth()
  if (!ai.messages.length) {
    await ai.loadHistory()
  }
  const wrap = msgWrapRef.value
  if (wrap && typeof ResizeObserver !== 'undefined') {
    lastMsgScrollHeight = wrap.scrollHeight
    msgResizeObserver = new ResizeObserver(onMsgWrapResized)
    msgResizeObserver.observe(wrap)
  }
  scrollBottom()
})

onBeforeUnmount(() => {
  tickController?.stop()
  tickController = null
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
  if (smoothBackTimer !== null) {
    clearTimeout(smoothBackTimer)
    smoothBackTimer = null
  }
  ai.stopWorkspaceSync()
})
</script>

<style scoped>
/* R-157 #3：生成中指示器（动画照效果基准 指示器预览.html 口径）——
   首 token 前 = 呼吸点 + 「工作中…」（卡片下方），流式中 = 正文末尾打字机光标，两者接力。 */
.loading-breath {
  display: flex;
  align-items: center;
  gap: 7px;
}
.loading-breath .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #7b61ff);
  animation: breath 1.4s ease-in-out infinite;
}
.loading-breath .txt {
  font-size: 12px;
  color: #909399;
  letter-spacing: 1px;
  animation: breathTxt 1.4s ease-in-out infinite;
}
@keyframes breath {
  0%, 100% { transform: scale(0.7); opacity: 0.45; }
  50% { transform: scale(1.15); opacity: 1; }
}
@keyframes breathTxt {
  0%, 100% { opacity: 0.45; }
  50% { opacity: 0.95; }
}
.caret {
  display: inline-block;
  width: 8px;
  height: 1em;
  background: #303133;
  border-radius: 2px;
  margin-left: 1px;
  vertical-align: -2px;
  animation: caretBlink 1s steps(1) infinite;
}
/* R-159 #1：0–55% 显（demo caretBlink 口径） */
@keyframes caretBlink {
  0%, 55% { opacity: 1; }
  56%, 100% { opacity: 0; }
}
/* R-159 #6：新字符渐显（opacity .1→1） */
.ch {
  animation: chIn 0.18s ease-out;
}
@keyframes chIn {
  from { opacity: 0.1; }
  to { opacity: 1; }
}
/* R-159 #4：完成优雅淡出 300ms（光标 + 「工作中…」同帧） */
.fade-out {
  animation: caretOut 0.3s ease forwards;
}
@keyframes caretOut {
  to { opacity: 0; }
}
/* R-161：平滑回底 = CSS scroll-behavior:smooth 类切换（会审 M2：禁 scrollTo({behavior:'smooth'})，
   浮钮点击时挂上、~600ms 后移除；流式期 pinBottom 恒瞬跳不加本类）。 */
.scroll-smooth {
  scroll-behavior: smooth;
}
/* R-161（用户实测回归）：reduced-motion 豁免呼吸动画 —— 呼吸 = 生成中状态语义（非纯装饰），
   豁免后律动照常 1.4s；`.caret` 纯装饰抑制保留。 */
@media (prefers-reduced-motion: reduce) {
  .caret {
    animation: none;
  }
}
</style>
