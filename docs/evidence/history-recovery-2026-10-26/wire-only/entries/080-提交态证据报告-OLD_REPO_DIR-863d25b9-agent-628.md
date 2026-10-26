> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-628` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：35672 B / 28997 字符 / 572 行；行指纹覆盖率（仓内 + 落盘语料）1.2%
> - 类别：**施工复核**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 提交态证据报告（`<REPO_ROOT>`）

## 0. 提交态核验

```
$ git -C .../<REPO_ROOT> rev-parse --short HEAD
4260651
$ git -C .../<REPO_ROOT> log -1 --format='%h %s'
4260651 docs(m2,evidence): R-149 issue#7 四份报告入库 + M2:338 闭环注记（O-2 登记不修）
```

草案预期 `HEAD=4260651` —— **一致**。

工作树脏（在途施工，未取用）：`M backend/.../AiTools.java`、`TaskService.java`、`JobService.java`、`AiToolsInvocationTest.java`、`frontend/src/stores/workspace.ts`、`scripts/m2t2a-smoke.mjs`、`scripts/verify-e2e.ps1`，另有 4 个 untracked 测试文件（`AiToolsThrowPathTest.java`、`TaskFinalizedGuardTest.java`、`TaskGoToStepGuardTest.java`、`frontend/tests/goto-step-readonly-guard.spec.ts`）。注意 `frontend/src/stores/ai.ts` **不在**脏清单内；下列证据一律取自 `HEAD:` blob。

---

## 1. `frontend/src/stores/ai.ts` — loadHistory

### 1.1 函数边界

草案引 700-754 属实，但那是函数体中部；函数完整范围是 **603-781**（`loadHistory(): Promise<void>`，行 603；闭合 `},` 在 781）。

### 1.2 前段上下文（603-668，镜像分支 + 取历史）

```
603	    async loadHistory(): Promise<void> {
604	      const sid = this.sessionId
605	      this.startMirror()
606	      const mirror = readMirror(this.sessionId)
607	      if (mirror && Array.isArray(mirror.messages) && mirror.messages.length > 0) {
...
611	        const migrated = migrateMirror(mirror)
612	        this.messages = migrated.messages.map((message) => ({
613	          ...message,
614	          streaming: false,
615	          done: true,
620	          parts: message.parts.map((part) => ('streaming' in part && part.streaming ? { ...part, streaming: false } : part))
621	        }))
622	        this.activeRunId = mirror.lastRunId ?? null
625	        const sanitized = sanitizeMirrorResidues(this.messages)
628	        this.messages.forEach((message) => deriveProjections(message))
629	        if (migrated.migrated || sanitized) {
632	          writeMirror(this.sessionId, this.messages, this.activeRunId)
633	        }
634	      }
635
636	      let history: AiHistory
637	      try {
638	        history = await aiApi.history(this.sessionId)
639	      } catch {
640	        if (this.sessionId !== sid) {
641	          return // 会话已换：过期失败不落任何状态（新会话的 historyLoaded 由新会话自己落）
642	        }
643	        this.historyLoaded = true
644	        return // 后端不可达：保留镜像展示
645	      }
646
647	      // 会话身份对账（红队 MAJOR-1）：...
649	      if (this.sessionId !== sid) {
650	        return
651	      }
652
653	      if (!history.messages || history.messages.length === 0) {
...
656	        for (const message of this.messages) {
657	          if (!message.parts.some((part) => part.kind === 'tool' && part.requiresAction)) {
658	            continue
659	          }
660	          expireResidueToolParts(message)
661	          deriveProjections(message)
662	        }
663	        this.historyLoaded = true
666	        await this.restoreSuspendedSnapshot()
667	        return
668	      }
```

镜像分支（607-634）**整体替换** `this.messages` 为镜像消息（`...message` 原样保留 id），只归一段/消息级 `streaming` 并收 `requiresAction` 残留；不做任何跨消息合并。

### 1.3 主体（676-781，含草案所引 700-754，前后各约 20 行）

```
676	      // 口径，**任何消费方不得以该 succeeded 值做业务判定**。
677	      // R-149 双条目合并：后端历史里工具结果是**独立 TOOL 条目**（Spring AI 形态），
678	      // 第一遍遍历建响应索引，第二遍构建 ChatMessage；TOOL 条目跳过（不生成消息，杜绝空白块）。
679	      // 配对规则：① call.id 三态为真按 id 匹配（consume-on-match：命中即从索引摘除）；
680	      // ② 未中退序位兜底：未配对 call × 未消费 response 按序互配 + name 相等闸；
681	      // ③ 混排先 id 后序位；④ 仍不中 result=null（卡片保留不丢）。
683	      const responsePool: NonNullable<AiHistoryMessage['toolResponses']>[number][] = []
684	      const responseIndex = new Map<string, NonNullable<AiHistoryMessage['toolResponses']>[number]>()
685	      for (const item of history.messages) {
686	        if ((item.type || '').toUpperCase() !== 'TOOL') {
687	          continue
688	        }
689	        for (const response of item.toolResponses ?? []) {
690	          responsePool.push(response)
...
693	          if (response.id !== null && response.id !== undefined && response.id !== '') {
694	            responseIndex.set(response.id, response)
695	          }
696	        }
697	      }
698	      const consumedResponses = new Set<object>()
699	      const unmatchedCalls: Array<{ name: string; part: ToolPart }> = []
700	      this.messages = history.messages.flatMap((item, index) => {
701	        if ((item.type || '').toUpperCase() === 'TOOL') {
702	          return [] // TOOL 条目：响应已并入 ASSISTANT 工具段，本身不再产生消息
703	        }
704	        const role = roleOf(item.type)
708	        const parts: MessagePart[] = [
709	          { kind: 'text', seg: 0, text: item.text ?? '', streaming: false },
710	          ...(item.toolCalls ?? []).map((call, toolIndex): ToolPart => {
711	            const name = call.name ?? '未知工具'
712	            let matched: ... | undefined
713	            if (call.id !== null && call.id !== undefined && call.id !== '') {
714	              const hit = responseIndex.get(call.id)
715	              if (hit) {
716	                responseIndex.delete(call.id) // consume-on-match：命中即从索引摘除
717	                matched = hit
718	              }
719	            }
720	            const part: ToolPart = {
721	              kind: 'tool',
722	              seg: toolIndex + 1,
723	              toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
724	              name,
725	              kind2: 'UNKNOWN',
726	              status: 'succeeded',
727	              args: call.arguments,
728	              result: null,
729	              requiresAction: false
730	            }
731	            if (matched) {
732	              consumedResponses.add(matched)
733	              part.result = matched.responseData ?? null
734	            } else {
735	              unmatchedCalls.push({ name, part }) // 序位兜底候选（见下方统一互配）
736	            }
737	            return part
738	          })
739	        ]
740	        const message: ChatMessage = {
741	          // R-149：index 锁死 = 原始数组下标（含被跳过 TOOL 条目的空洞，不用合并后下标）
742	          id: `history-${index}`,
743	          role,
744	          content: '',
745	          parts,
746	          reasoning: '',
747	          toolRuns: [],
748	          pendingCall: null,
749	          streaming: false,
750	          done: true
751	        }
752	        deriveProjections(message)
753	        return [message]
754	      })
755	      // 序位兜底（R-149②）：未配对 call × 未消费 response 按序互配，name 相等才配；
756	      // 仍不中的 call 保持 result=null —— 卡片保留不丢（残缺历史无主 response 在此一并兜底丢弃）
757	      for (const unmatched of unmatchedCalls) {
758	        const hitIndex = responsePool.findIndex(
759	          (response) => !consumedResponses.has(response) && (response.name ?? '未知工具') === unmatched.name
760	        )
761	        if (hitIndex < 0) {
762	          continue
763	        }
764	        const hit = responsePool[hitIndex] as ...
765	        consumedResponses.add(hit)
766	        unmatched.part.result = hit.responseData ?? null
767	      }
768	      // C1（复核 m-1）：序位兜底在 deriveProjections（flatMap 内）**之后**才写 part.result ——
769	      // 投影按值快照（parts.ts）会滞留 null。对含工具段的受影响消息重跑 deriveProjections
770	      // （幂等纯函数；契约：段字段落段后须同步派生投影），兜底命中的 toolRuns[].result 随之就位。
771	      this.messages.forEach((message) => {
772	        if (message.parts.some((part) => part.kind === 'tool')) {
773	          deriveProjections(message)
774	        }
775	      })
776	      // 历史里若存在未决挂起，补一张待处理卡片（前端工具/确认门都可能出现在这里）
777	      this.historyLoaded = true
778	      // T2b-D#3：historyLoaded 先行置位后再触发快照重建（loadHistory 先行完成，
779	      // 消除历史整体替换与挂起重建之间的写后写竞态）
780	      await this.restoreSuspendedSnapshot()
781	    },
```

### 1.4 逐问回答

- **相邻 ASSISTANT 合并逻辑：不存在。** 该文件内 700 起是 `history.messages.flatMap(...)` 的**逐条目映射**，每个非 TOOL 条目无条件产出恰好 1 条 `ChatMessage`（`return [message]`，753），没有任何跨条目聚合/拼接/合并分支。全文件（2023 行）grep `合并|merge|相邻|adjacent` 仅命中：678（注释，指 "TOOL 响应并入 ASSISTANT 工具段"）、702（TOOL 条目跳过）、741（id 口径注释）、1936（`upsertToolRun` 按 toolCallId 就地 spread 合并，属帧路径而非 loadHistory）。**R-149 的"双条目合并"= TOOL 条目并入 ASSISTANT 工具段，方向是 TOOL→ASSISTANT，不是 ASSISTANT+ASSISTANT。**
- **id 构造**：`id: `history-${index}``（742），`index` 是 `history.messages` 的**原始数组下标**，含被跳过 TOOL 条目造成的空洞（741 注释明确"不用合并后下标"）。有 `"history-"` 前缀，且**基于 index**（非 uuid、非内容哈希）。
- **每条 ChatMessage 赋的字段**（740-751）：`id / role / content('') / parts / reasoning('') / toolRuns([]) / pendingCall(null) / streaming(false) / done(true)`。**未赋 `messageId`**（`ChatMessage.messageId` 为可选字段，见 §2），也**未赋 `appliedSeqs`**。
- **toolCalls 但 text 为空**：**无特殊分支**。`parts` 首元素恒为 `{ kind:'text', seg:0, text: item.text ?? '', streaming:false }`（709）——text 为空即产出一个**空 text 段（seg 0）**，其后照常按 `toolCalls` 顺序产出 `seg = toolIndex + 1` 的工具段（720-730）。`status` 恒 `'succeeded'`、`kind2` 恒 `'UNKNOWN'`、`requiresAction` 恒 `false`。工具段的 `toolCallId` = `call.id ?? `history-${index}-${call.name ?? 'tool'}``。渲染侧空 text 段不会出块（见 §3，`blocks.ts:112-115` 空正文段 `continue`）。

---

## 2. `frontend/src/types/ai.ts` — 类型形状

草案引 133-143 属实（`AiHistoryMessage` 定义体）。相邻定义（133-150）：

```
133	/** 会话历史消息（GET /api/ai/history/{sessionId} → data.messages 元素）。 */
134	export interface AiHistoryMessage {
135	  /** Spring AI MessageType 名称：USER / ASSISTANT / SYSTEM / TOOL */
136	  type: string
137	  text: string
138	  /** assistant 的 toolCalls（Spring AI AssistantMessage.ToolCall 列表，字段结构由上游协议决定） */
139	  toolCalls?: Array<{ id?: string; type?: string; name?: string; arguments?: string }>
140	  /** tool 消息的响应（ToolResponseMessage.ToolResponse 列表） */
141	  toolResponses?: Array<{ id?: string; name?: string; responseData?: string }>
142	  timestamp?: unknown
143	}
144
145	/** 会话历史响应（GET /api/ai/history/{sessionId} 的 data）。 */
146	export interface AiHistory {
147	  sessionId: string
148	  count: number
149	  messages: AiHistoryMessage[]
150	}
```

`ChatMessage` 本体（184-217）：

```
183	/** 前端展示用的对话消息。 */
184	export interface ChatMessage {
185	  id: string
186	  role: 'user' | 'assistant' | 'system'
187	  /** DC-14 T5：服务端给的消息身份（message_start/end 与 delta 帧的 messageId） */
188	  messageId?: string
189	  content: string
194	   * ... `parts` 是**唯一真值**（面板按它渲染，帧只写它）；下方 `content` / `reasoning` /
195	   * `toolRuns` / `pendingCall` 四个字段是同一份数据的**派生投影** ...
200	  parts: MessagePart[]
202	  reasoning: string
203	  toolRuns: ToolRun[]
204	  /** 当前挂起（待确认 / 待前端执行）的工具调用：= 最后一个 requiresAction 的工具段派生 */
205	  pendingCall: PendingToolCall | null
206	  /** 正文生成中 */
207	  streaming: boolean
208	  /** 已收到终帧 */
209	  done: boolean
216	  appliedSeqs?: number[]
217	}
```

段基类型带 `seg`（219-230）：`interface MessagePartBase { seg: number }`；`TextPart extends MessagePartBase { kind:'text'; text: string; streaming: boolean }`。

镜像结构（337-344，`Pick` 不含 `messageId`/`appliedSeqs`）：

```
337	export interface AiSessionMirror {
339	  v?: number
340	  savedAt: number
341	  messages: Array<Pick<ChatMessage, 'id' | 'role' | 'content' | 'parts' | 'reasoning' | 'toolRuns' | 'pendingCall'>>
343	  lastRunId?: string | null
344	}
```

---

## 3. `frontend/src/components/AiPanel/AiPanel.vue` — key / 锚点

文件位置：`frontend/src/components/AiPanel/AiPanel.vue`。

### 3.1 草案所引 185 行及其上下文（155-215）

```
155	          :class="m.role === 'user' ? 'bg-[#d9ecff]' : 'bg-[#f4f4f5]'"
158	          <span v-if="isTyping(m)" class="typing">
162	          <!-- 消息体：块分派（批4④ A5'）——groupParts 把段序列分派为 text/activity 块；活动组可折叠，
163	               展开态读 store 三态（expandedBlocks ?? defaultExpanded，D4/D6/D-C6）；key 用块稳定 id
164	               （组首成员 toolCallId / kind-seg，加消息命名空间前缀，收口修 F1/红队 M-1），禁用数组下标（§7 R4-3 / D5）。 -->
165	          <template v-for="block in blocksOf(m)" :key="block.id">
166	            <!-- 正文块：单段正文，永不进折叠组（A5' 规则③） -->
167	            <div v-if="block.kind === 'text'" class="whitespace-pre-wrap">{{ textOf(block) }}</div>
169	            <template v-else>
171	              <div v-if="!block.bare" class="mt-1.5 border ..." data-activity-group>
172	                <button
175	                  :data-testid="'block-toggle-' + block.id"
176	                  @click="toggleBlock(block)">
180	                    {{ blockHeader(block) }}
184	                <div v-show="isBlockExpanded(block)" class="..." :data-testid="'block-body-' + block.id">
185	                  <template v-for="part in block.parts" :key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg">
186	                    <!-- 组内思考链：组头已控折叠，内容随组展开可见（D-C8：组默认折叠） -->
187	                    <div v-if="part.kind === 'reasoning' && part.text" ...>
189	                      <div class="...">{{ part.text }}</div>
190	                    </div>
191	                    <template v-else-if="part.kind === 'tool'">
192	                      <div class="..."
194	                        :data-testid="part.requiresAction ? 'pending-card-' + part.toolCallId : undefined">
```

### 3.2 `seg` 如何派生

`part.seg` 直接取 `MessagePart.seg`（`types/ai.ts:219-222`，`MessagePartBase`）。写入侧 `utils/parts.ts:28-30` `nextSeg(parts) = max(seg)+1`（单调递增，封口后不复用）。loadHistory 分支则是字面量：text 段 `seg:0`，工具段 `seg: toolIndex + 1`（`stores/ai.ts:709,722`）。**key = `part.kind + '-' + part.seg`，无消息命名空间前缀**（对比 `block.id` 有前缀，见下）。

块 id（`block.id`）的派生在 `frontend/src/utils/blocks.ts:31-35`：

```
31	function stablePartId(part: MessagePart, messageId: string): string {
32	  const base = part.kind === 'tool' ? part.toolCallId : `${part.kind}-${part.seg}`
33	  // F1（红队 M-1）：seg 逐消息自 0 起（parts.ts nextSeg），跨消息同 seg 会撞 id ⇒ 全量加消息命名空间前缀
34	  return `${messageId}:${base}`
35	}
```

即 `block.id = `${messageId}:${toolCallId | kind-seg}``；`groupParts(message.parts, message.id)` 调用点 `AiPanel.vue:592`。`groupParts` 空正文段直接 `continue`（`blocks.ts:111-115`）。

### 3.3 该文件其他按 message id / seg 作 key / 锚点的地方（逐处）

| 行 | 用法 |
|---|---|
| 143 | `v-for="m in ai.messages" :key="m.id"` —— 消息级 key 用 `ChatMessage.id` |
| 165 | `:key="block.id"`（= `${m.id}:${组首成员稳定 id}`） |
| 185 / 262 | `:key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg"`（两处模板分支，同一表达式） |
| 243 / 321 | `v-if="liveForm && liveForm.toolCallId === part.toolCallId && m.id === formHostMessageId"` —— FormRenderer 宿主判定用 `m.id` |
| 244 / 322 | `:key="liveForm.toolCallId"` |
| 345-346 | `v-if="copyState && copyState.id === m.id"` + `:data-testid="'copy-hint-' + m.id"` |
| 356 | `:data-testid="'copy-msg-' + m.id"` —— 复制按钮按 **message id** 对齐 |
| 175 / 184 | `data-testid="'block-toggle-' + block.id"` / `'block-body-' + block.id` |
| 194 | `data-testid="part.requiresAction ? 'pending-card-' + part.toolCallId : undefined"` —— 挂起卡按 **toolCallId** 锚定 |
| 122 | `data-testid="history-skeleton"`（非 id 锚点） |
| 592 | `groupParts(message.parts, message.id)`（id 作命名空间） |
| 756 | `copyState.value = { id: m.id, ok }` |

**滚动锚定**：只有 `scrollBottom()`（`AiPanel.vue:671-675`）整体滚到底，`onMounted` 尾部 `scrollBottom()`（858）与 watch（829、834）；**没有任何按 message id / seg 的滚动锚定**。

**动作按钮**：模板里唯一的按消息对齐动作是「复制」（339-363，`copy-msg-<m.id>` / `copy-hint-<m.id>`）。全前端 grep `重新生成|regenerate|重新回答` **零命中**（`retry` 仅作为 SSE 帧类型存在于 `types/sse.ts:72,251` 与 `stores/ai.ts:1808` 的 case）。

---

## 4. 测试

### 4.1 `frontend/tests/parts-residue-cleanup.spec.ts`（共 987+ 行）

草案引 748-751 属实（在用例 ② 内）。730-780 原文：

```
722	  it('② 连续多轮工具：各 TOOL 只吃自己那份，不串结果', async () => {
723	    const store = useAiStore()
724	    const sid = store.sessionId
725	    api.history.mockResolvedValue({
726	      sessionId: sid,
727	      count: 4,
728	      messages: [
729	        { type: 'USER', text: '开工' },
730	        {
731	          type: 'ASSISTANT',
732	          text: '第一轮',
733	          toolCalls: [{ id: 'a1', type: 'function', name: 'probe_tool', arguments: '{}' }]
734	        },
735	        { type: 'TOOL', text: '', toolResponses: [{ id: 'a1', name: 'probe_tool', responseData: 'ra' }] },
736	        {
737	          type: 'ASSISTANT',
738	          text: '第二轮',
739	          toolCalls: [{ id: 'b1', type: 'function', name: 'probe_tool', arguments: '{}' }]
740	        },
741	        { type: 'TOOL', text: '', toolResponses: [{ id: 'b1', name: 'probe_tool', responseData: 'rb' }] }
742	      ]
743	    })
744	    api.runsCurrent.mockResolvedValue(noSuspension())
745
746	    await store.loadHistory()
747
748	    expect(store.messages).toHaveLength(3) // USER + 两条 ASSISTANT；两条 TOOL 消 blank
749	    const [first, second] = [store.messages[1], store.messages[2]] as ChatMessage[]
750	    expect(soleToolPart(first).result).toBe('ra')
751	    expect(soleToolPart(second).result).toBe('rb')
752	  })
753
754	  it('③ id 缺失序位兜底 + name 闸：同 name 才按序互配，name 不同 result=null 但卡片保留', async () => {
...
781	    await store.loadHistory()
782
783	    const parts = toolPartsOf(store.messages[0] as ChatMessage)
784	    expect(parts[0]?.result).toBe('ra') // alpha ↔ alpha 序位配中
785	    expect(parts[1]?.result).toBeNull() // beta vs gamma：name 闸拦截
786	    expect(parts[1]).toMatchObject({ kind: 'tool', name: 'beta', status: 'succeeded' }) // 卡片保留不丢
787	    // C1（复核 m-1）：序位兜底命中后投影须同步 —— toolRuns[].result 非 null 且与 parts 一致
789	    const runs = (store.messages[0] as ChatMessage).toolRuns
790	    expect(runs.map((run) => run.result)).toEqual(['ra', null])
```

（748-751 断言：消息数 3；两条 ASSISTANT 各自唯一工具段 result 分别为 `'ra'`/`'rb'`。）

900-950 原文：

```
899	    const viaMirrorRoundTrip = store.messages.map((message) => message.parts)
900	    expect(JSON.parse(JSON.stringify(viaMirrorRoundTrip))).toEqual(viaBackend)
901	    expect(soleToolPart(store.messages[1] as ChatMessage).result).toBe('ok')
902	  })
903
904	  it('⑧ 空白块消除：TOOL 条目零消息产出，id 锁死原始数组下标（含被跳过条目的空洞）', async () => {
905	    const store = useAiStore()
906	    const sid = store.sessionId
907	    api.history.mockResolvedValue({
908	      sessionId: sid,
909	      count: 5,
910	      messages: [
911	        { type: 'USER', text: '问题' },
912	        {
913	          type: 'ASSISTANT',
914	          text: '回答',
915	          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{}' }]
916	        },
917	        { type: 'TOOL', text: '', toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }] },
918	        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan', name: 'probe_tool', responseData: 'ro' }] },
919	        { type: 'SYSTEM', text: '系统提示' }
920	      ]
921	    })
922	    api.runsCurrent.mockResolvedValue(noSuspension())
923
924	    await store.loadHistory()
925
926	    expect(store.messages).toHaveLength(3) // 两条 TOOL 都不产生消息
927	    expect(store.messages.map((message) => message.id)).toEqual(['history-0', 'history-1', 'history-4'])
928	    // 无任何"TOOL 条目落成的空白 assistant 块"（空 text 段 + 无工具段）
929	    for (const message of store.messages) {
930	      const isBlankToolBlock = message.parts.length === 1
931	        && message.parts[0]?.kind === 'text'
932	        && message.parts[0].text === ''
933	        && message.role === 'assistant'
934	      expect(isBlankToolBlock).toBe(false)
935	    }
936	  })
937	})
940	describe('R-149 收口修 C3：两遍扫描序无关性补测', () => {
941	  it('TOOL 在 ASSISTANT 前 + 多 TOOL 夹一条 ASSISTANT：配对正确、无主响应丢弃、零空白块', async () => {
944	    api.history.mockResolvedValue({
945	      sessionId: sid,
946	      count: 5,
947	      messages: [
948	        // 响应先于调用出现（序无关判据①）
949	        { type: 'TOOL', text: '', toolResponses: [{ id: 'c1', name: 'probe_tool', responseData: 'rc1' }] },
950	        {
951	          type: 'ASSISTANT',
952	          text: '回答',
953	          toolCalls: [{ id: 'c1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }]
954	        },
955	        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan', name: 'probe_tool', responseData: 'ro' }] },
956	        { type: 'ASSISTANT', text: '补充' },
957	        { type: 'TOOL', text: '', toolResponses: [{ id: 'orphan2', name: 'probe_tool', responseData: 'ro2' }] }
958	      ]
959	    })
```

（926-927：消息数 3；id 数组 `['history-0','history-1','history-4']` —— 印证 §1.4 的 index 空洞口径。965 行同族：`expect(store.messages.map((m) => m.id)).toEqual(['history-1', 'history-3'])`。）

该文件 `describe` 结构：`施工项1`（100）、`施工项2`（277）、`施工项3：m-R2 六写点回归锁`（386，其中写点⑥ = 577-618 的 loadHistory 后端分支锁）、`R-149：loadHistory 双条目合并`（684）、`R-149 收口修 C3`（940）。辅助夹具函数：`toolPartsOf`（43）、`soleToolPart`（48）、`armConfirm`（55）、`armGenerativeForm`（72）、`noSuspension`（88）。

**夹具形状（hybrid）：存在「单条 ASSISTANT 同时带 text + toolCalls」的夹具，且是本案多数**：

| 出处 | 形状 |
|---|---|
| `parts-residue-cleanup.spec.ts:585-589`（写点⑥） | `{ type:'ASSISTANT', text:'回答', toolCalls:[{id:'h1',...}] }` |
| `:692-699`（① 一对多） | `text:'双工具回答'` + `toolCalls:[c1,c2]` |
| `:730-734` / `:736-740`（②） | `text:'第一轮'/'第二轮'` + 各 1 个 toolCall |
| `:912-916`（⑧） | `text:'回答'` + `toolCalls:[h1]` |
| `:950-954`（C3） | `text:'回答'` + `toolCalls:[c1]` |
| `frontend/tests/ai-panel-parts-render.spec.ts:466-472` | `{ type:'ASSISTANT', text:'历史正文', toolCalls:[{id:'h1',...}], toolResponses:[], timestamp:... }`（**同一条 ASSISTANT 上同时给 toolCalls 与空 toolResponses**） |

反向：全文件 `text: ''` 出现 18 处，**全部属于 `type:'TOOL'` 条目**；不存在「非 TOOL 条目且 text 为空 + 带 toolCalls」的夹具。

### 4.2 `frontend/tests/ai-panel-parts-render.spec.ts`（共 486 行，末尾 486 行结束）

夹具：`buildMessage(parts, pendingCall=null, streaming=false)`（50-63，`id: 'a-1'` 固定，`content = text 段 join`）、`makeActiveForm`（66）、`mountPanel(messages)`（70-78，直接 `store.messages = messages; store.historyLoaded = true` 后 mount）。Element Plus 全 stub（28-37）。

现有断言概述：

- **段序**：text→tool→text 三段在 DOM 中顺序保持（81-98，用 `rendered.indexOf` 比较位置）。
- **单卡/挂起卡**：`[data-testid^="pending-card-"]` 数 1（100-120）、复数 requiresAction 段逐段各出一卡 = 2 且 testid `['pending-card-p1','pending-card-p2']`（129-144）、工具名在 HTML 中只出现 1 次（117、192）、历史整替后同 id 单卡且无挂起卡（460-485）。
- **死文案零命中**（A9'）：`expect(text).not.toContain('表单已取消' | '表单已提交，等待回执' | '表单状态已丢失')`（146-178）。
- **复制按钮判据取 parts**（236-249）：两条消息（`inconsistent` 段有正文但 `content=''`；`empty` 段空但 `content` 有正文）→ 断言 `[data-testid^="copy-msg-"]` 数 **1**，且 testid = `'copy-msg-a-1'`。
- **三点闪烁**（251-262）：两条消息（一条空 text 流式、一条有 text 流式）→ `.typing` 数 **1**，文本含 `'正在说'`。
- **折叠组与展开态**：`[data-activity-group]` 数 1、组头文本 `'工具活动 · 3 段'`、组头不含正文（279-286）；`block-toggle-a-1:a` / `block-body-a-1:run1` / `reasoning-body-a-1:reasoning-0` 存在性与 `style` 里 `display: none`（305-359、364-375）；`store.expandedBlocks` 断言 `{ g1:true, g2:false }` / `{}`（383-385）。
- **续收/终态**：`reattachActive` 后挂起卡 0、`segments`（按 `toolCallId` 过滤）长度 1、`status:'unknown'`（450-457、421-425、453-456）。

**该文件未断言头像数量**：全仓 grep `avatar|头像` 仅命中模板类名（`AiPanel.vue:148`）与文档注释（`:397`），测试文件中零命中。也**没有"每消息一段"的断言**——多消息用例只数「复制按钮 1 个」「typing 1 个」（243、257），不断言每条消息的段数。

---

## 5. 全前端 `ChatMessage.id` / history 消息相关用法（逐处）

### 5.1 生产代码

| file:line | 上下文（摘） |
|---|---|
| `frontend/src/stores/ai.ts:292-300` | `writeMirror`：`messages: messages.map((message) => ({ id: message.id, role: ..., content: ..., parts, reasoning, toolRuns, pendingCall }))` —— **id 入镜像**（`Pick` 含 `id`，不含 `messageId`/`appliedSeqs`） |
| `frontend/src/stores/ai.ts:1035` | `const target = this.messages.find((message) => message.id === assistant.id)` —— 轮 finally 收尾按 id 定位 |
| `frontend/src/stores/ai.ts:1046` | `const message = this.messages.find((item) => item.id === assistantId)` —— `handleChatError(error, assistantId)` 按 id 定位 |
| `frontend/src/stores/ai.ts:1631` | `const message = assistantId ? this.messages.find((item) => item.id === assistantId) ?? (ownedByActiveRound ? this.currentAssistantMessage() : null) : this.currentAssistantMessage()` —— 帧归属判定按 id；未命中才退 `currentAssistantMessage()`，否则整帧丢弃（1634-1638） |
| `frontend/src/stores/ai.ts:1590-1606` | `appendAssistantMessage` 生成 id：`` id: `a-${Date.now()}-${Math.random().toString(36).slice(2,6)}` ``（与 `history-*` 不同命名空间） |
| `frontend/src/stores/ai.ts:1609-1620` | `currentAssistantMessage()`：**逆序找最后一条 `role==='assistant' && !done`** —— 按角色+done 状态定位，**不按 id** |
| `frontend/src/stores/ai.ts:845-849` | `restoreSuspendedSnapshot` 宿主定位：先按 `parts` 里 `toolCallId` 匹配，否则 `[...this.messages].reverse().find((item) => item.role === 'assistant')`，都没有才 append（851）—— **按 toolCallId / 角色，不按消息 id** |
| `frontend/src/stores/ai.ts:1195-1197` | `confirm()` 宿主：按 `part.kind==='tool' && part.toolCallId === pending.toolCallId` 查找 |
| `frontend/src/stores/ai.ts:1310-1315` | `hasDecidedPendingResidue(toolCallId)`：按 toolCallId 匹配段 |
| `AiPanel.vue:143` | `:key="m.id"` |
| `AiPanel.vue:243` / `:321` | `m.id === formHostMessageId`（FormRenderer 宿主） |
| `AiPanel.vue:345-346` / `:356` / `:756` | `copyState.id === m.id` / `'copy-hint-' + m.id` / `'copy-msg-' + m.id` / `copyState.value = { id: m.id, ok }` |
| `AiPanel.vue:592` | `groupParts(message.parts, message.id)`（id 作 blockId 命名空间前缀） |
| `frontend/src/types/ai.ts:185, 188, 341` | `id: string`；可选 `messageId?: string`；镜像 `Pick` 含 `'id'` |

### 5.2 `history-` 字符串全量用法（全仓 grep `history-`）

```
frontend/src/components/AiPanel/AiPanel.vue:122   data-testid="history-skeleton"
frontend/src/stores/ai.ts:723                     toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`
frontend/src/stores/ai.ts:742                     id: `history-${index}`
frontend/tests/parts-residue-cleanup.spec.ts:927  expect(...message.id).toEqual(['history-0','history-1','history-4'])
frontend/tests/parts-residue-cleanup.spec.ts:965  expect(...message.id).toEqual(['history-1','history-3'])
```

即 `history-*` 只作为 **ChatMessage.id 与未知 toolCallId 的回退值**出现，无其他消费者（不参与查找、不作 key 之外的对齐）。

### 5.3 镜像分支 / 深度相等断言（测试）

| file:line | 断言 |
|---|---|
| `frontend/tests/parts-residue-cleanup.spec.ts:886`、`899-900` | `` const viaBackend = JSON.parse(JSON.stringify(store.messages.map((m) => m.parts))) ``；镜像回环后 `` expect(JSON.parse(JSON.stringify(viaMirrorRoundTrip))).toEqual(viaBackend) `` —— **只对 `parts` 做深度相等，不含 id** |
| `frontend/tests/parts-double-write.spec.ts:264-299` | 迁移后 `expect(store.messages[0]?.parts).toEqual([{ kind:'text', seg:0, text:'你好', streaming:false }])`、`messages[1].parts[1]` `toMatchObject({...})`、回写镜像 `written.messages[1].parts` 长度 2、`written.messages[1].appliedSeqs` `toBeUndefined()`、二次 loadHistory 后 `savedAt` 不变 —— **全为 parts/字段级，无整条 ChatMessage 深度相等、无 id 相等断言** |
| `frontend/tests/frontend-tool-result-terminal.spec.ts:293-298` | `loadHistory()` 后 `const host = store.messages[0]`（按位置取，不按 id），再断言 `soleToolPart(host)` 与 `host.pendingCall` |
| `frontend/tests/restore-reconcile.spec.ts:90-104` | `const load = store.loadHistory()` 与 `resetSession()` 交错，断言 `store.messages` 长度 0（会话身份对账负例） |
| `frontend/tests/ai-panel-parts-render.spec.ts:477-484` | 历史整替后按 `store.messages.flatMap((m) => m.parts.filter(...toolCallId==='h1'))` 取段断言长度 1（**按 toolCallId 而非消息 id**） |

全仓 grep `message.id ... toEqual` 只有 927/965 两处（id 数组相等）；无任何测试断言「历史消息 id 与镜像回环后一致」或整条 `ChatMessage` 深度相等。

---

## 6. `loadHistory` 调用方与取数 API

**生产调用方：唯一一处** —— `frontend/src/components/AiPanel/AiPanel.vue:844-859`：

```
844	onMounted(async () => {
845	  ai.startMirror()
846	  ai.startWorkspaceSync()
847	  tickController = createPollingController({ ... })
853	  tickController.start()
854	  void ai.probeHealth()
855	  if (!ai.messages.length) {
856	    await ai.loadHistory()
857	  }
858	  scrollBottom()
859	})
```

即 `onMounted` 阶段、`ai.messages` 为空时调用；**无入参**（会话 id 由 store 内部 `this.sessionId` 决定，入口即 `const sid = this.sessionId`，`stores/ai.ts:604`）。

测试调用方（`await store.loadHistory()`）：`parts-residue-cleanup.spec.ts`（303、317、348、375、599、712、746、781、816、842、861、885、897、924、962、983）、`ai-panel-parts-render.spec.ts:477`、`frontend-tool-result-terminal.spec.ts:293`、`parts-double-write.spec.ts:264,296`、`restore-reconcile.spec.ts:90`。

**历史读取 API 客户端**：`frontend/src/api/ai.ts:63-66`

```
63	/** GET /api/ai/history/{sessionId}（刷新恢复用的会话记忆原文）。 */
64	export function history(sessionId: string): Promise<AiHistory> {
65	  return get<AiHistory>(`/ai/history/${encodeURIComponent(sessionId)}`)
66	}
```

导出为 `aiApi.history`（`frontend/src/api/ai.ts:137`，`aiApi` 对象 130-145）。loadHistory 内取数点：`stores/ai.ts:638` `history = await aiApi.history(this.sessionId)`。相关：`deleteHistory` → `DELETE /ai/history/{sessionId}`（`:75-77`，resetSession 使用）；`runsCurrent` → `GET /ai/runs/current?sessionId=...`（`:126-127`，loadHistory 收尾经 `restoreSuspendedSnapshot` 调用）。

---

## 事实性总结（无评价）

1. `HEAD=4260651`，与草案预期一致；`frontend/src/stores/ai.ts` 属干净提交态。
2. `loadHistory` 实体范围 **603-781**（草案引 700-754 覆盖其 flatMap 主体）；草案所引 748-751、926-927、`types/ai.ts:133-143`、`AiPanel.vue:185` 均**行号与原文相符**。
3. `loadHistory` 的 ASSISTANT 处理是**逐条目一一映射**：每个非 TOOL 条目产出恰好一条 `ChatMessage`，**不存在相邻 ASSISTANT 合并**；"合并"仅指 TOOL 条目被跳过、其 `toolResponses` 按 `call.id`（consume-on-match）或序位+name 闸回填进 ASSISTANT 的工具段。
4. 历史消息 `id` 恒为 `` `history-${原始数组下标}` ``（含空洞）；赋字段为 `id/role/content('')/parts/reasoning('')/toolRuns([])/pendingCall(null)/streaming(false)/done(true)`，**不含 `messageId`/`appliedSeqs`**。text 为空的 ASSISTANT 条目仍产出 `seg:0` 的空 text 段（无特殊分支），空 text 段在渲染侧被 `groupParts` 跳过。
5. `AiPanel.vue` 有三层 key/锚点：消息级 `m.id`（143）、块级 `${messageId}:${toolCallId|kind-seg}`（165，`blocks.ts:31-35`）、段级 `part.kind + '-' + part.seg`（185/262，**无消息前缀**）。按 id 对齐的动作只有「复制」（`copy-msg-<m.id>`、`copy-hint-<m.id>`）；无重新生成等按钮；无按 id 的滚动锚定；挂起卡按 `toolCallId` 锚定（194）。
6. 测试侧：`parts-residue-cleanup.spec.ts` 的 R-149 套件存在多条「单条 ASSISTANT 同时带 text + toolCalls」hybrid 夹具（585-589、692-699、730-741、912-916、950-954；另 `ai-panel-parts-render.spec.ts:466-472`），但**无 text 为空 + 带 toolCalls 的非 TOOL 夹具**；镜像/历史一致性断言只覆盖 `parts` 深度相等（`parts-residue-cleanup.spec.ts:886-901`、`parts-double-write.spec.ts:264-299`），只有 2 处断言 id 数组（927、965）。`ai-panel-parts-render.spec.ts` 不断言头像数，也不断言每消息段数。
