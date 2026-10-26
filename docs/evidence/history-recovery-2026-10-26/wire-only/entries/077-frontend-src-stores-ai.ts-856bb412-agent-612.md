> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-612` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：10303 B / 8599 字符 / 200 行；行指纹覆盖率（仓内 + 落盘语料）6.3%
> - 类别：**施工复核**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. `frontend/src/stores/ai.ts`（HEAD=0f46286 工作树，文件共 1958 行）

**`loadHistory` 起止行号**：`frontend/src/stores/ai.ts:603` – `:716`（`async loadHistory(): Promise<void> {` … `},`）

方法内部结构（旧锚全部未漂移）：

| 区段 | 行号 |
|---|---|
| 镜像分支（sessionStorage） | 605–634 |
| `aiApi.history` 取数 + 不可达 catch | 636–645 |
| 会话身份对账 | 647–651 |
| 后端历史为空 → expired 降级 | 653–668 |
| **history.messages 循环构建 ChatMessage** | **678–710**（旧卡锚 678-710 一致） |
| `historyLoaded = true` + `restoreSuspendedSnapshot()` | 712–715 |

循环段全文（678–710）：

```ts
      this.messages = history.messages.map((item, index) => {
        const role = roleOf(item.type)
        // 批2：历史整体替换的分支同样**只建段**（正文段 + 按 toolCalls 顺序的工具段，
        // result 由 toolResponses 按 id 回填；历史条目没有待决语义，故 requiresAction=false），
        // 旧投影由 deriveProjections 一次产出，不再手工拼 content / toolRuns
        const parts: MessagePart[] = [
          { kind: 'text', seg: 0, text: item.text ?? '', streaming: false },
          ...(item.toolCalls ?? []).map((call, toolIndex): MessagePart => ({
            kind: 'tool',
            seg: toolIndex + 1,
            toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
            name: call.name ?? '未知工具',
            kind2: 'UNKNOWN',
            status: 'succeeded',
            args: call.arguments,
            result: (item.toolResponses ?? []).find((response) => response.id === call.id)?.responseData ?? null,
            requiresAction: false
          }))
        ]
        const message: ChatMessage = {
          id: `history-${index}`,
          role,
          content: '',
          parts,
          reasoning: '',
          toolRuns: [],
          pendingCall: null,
          streaming: false,
          done: true
        }
        deriveProjections(message)
        return message
      })
```

**`roleOf`**：当前 `frontend/src/stores/ai.ts:309-318`（旧锚 309-318 一致），全文：

```ts
/** 历史消息 type → 展示角色。 */
function roleOf(type: string): ChatMessage['role'] {
  const normalized = (type || '').toUpperCase()
  if (normalized === 'USER') {
    return 'user'
  }
  if (normalized === 'SYSTEM') {
    return 'system'
  }
  return 'assistant'
}
```

**关键字面**（`frontend/src/stores/ai.ts`）：
- `deriveProjections`：import 在 `:23`；`loadHistory` 内两处调用 `:628`（镜像分支）、`:661`（空历史降级）、`:708`（历史循环）
- `requiresAction`：方法体出现 `:657`（空历史判据 `part.kind === 'tool' && part.requiresAction`）、`:694`（历史段显式 false）、`:684-695` 段构造内；文件其他命中：`:190/203/231/234/248/253/285/460/1139/1156/1162/1169/1280/1362-63/1383/1661/1667/1675/1679/1703/1725/1733/1801/1804/1816-17/1826-28/1834/1854-63/1919/1949` 等
- `` `history-${...}` `` 字符串：`frontend/src/stores/ai.ts:688`（`toolCallId: \`history-${index}-${call.name ?? 'tool'}\``）、`:698`（`id: \`history-${index}\``）。**全 frontend 仅这两处**，无测试断言这两个 id

**sessionStorage 镜像路径**（存在，且 `loadHistory` 读写都走它）：

- `loadHistory:605` `this.startMirror()`；`:606` `readMirror(this.sessionId)`；`:611` `migrateMirror(mirror)`；`:625` `sanitizeMirrorResidues`；`:628` `deriveProjections`；`:632` `writeMirror(this.sessionId, this.messages, this.activeRunId)`（仅 migrated/sanitized 时回写）→ **读 + 条件写**
- 相关函数：`readMirror` `:146-162`（`sessionStorage.getItem(MIRROR_PREFIX + sessionId)`）、`migrateMirror` `:168-210`、`sanitizeMirrorResidues` `:227-242`、`expireResidueToolParts` `:246-256`、`writeMirror` `:281-306`（`sessionStorage.setItem` 在 `:302`）、`startMirror` action `:528-539`（`$subscribe` 300ms 防抖写盘）、`MIRROR_PREFIX = 'ai-mirror:'` `:39`、`initSessionId` `:113-121`、`resetSession` `:1440+`（`removeItem(MIRROR_PREFIX+old)` `:1459`）

## 2. `frontend/src/types/ai.ts`（共 442 行）

| 区块 | 旧锚 | 当前行号 |
|---|---|---|
| `AiHistoryMessage`（id 对齐字段，含 toolCalls/toolResponses） | 139-146 | **`frontend/src/types/ai.ts:133-143`**（注释 :133、interface :134-143）；`AiHistory` :145-150 |
| `ChatMessage` | 184-215 | **`frontend/src/types/ai.ts:184-217`**（`export interface ChatMessage {` :184 … `appliedSeqs?: number[]` :216，`}` :217） |
| `ToolPart` | 243-268 | **`frontend/src/types/ai.ts:243-268`**（`MessagePartBase` :220-222；`TextPart` :225-230；`ReasoningPart` :233-237；`MessagePart` 联合 :271；`ToolPartPatch` :280-294） |

`ToolPart` 全文（243-268）：

```ts
export interface ToolPart extends MessagePartBase {
  kind: 'tool'
  toolCallId: string
  name: string
  kind2: ToolKind | 'UNKNOWN'
  status: ToolRunStatus
  /** 入参 JSON 字符串（原样展示，可折叠） */
  args?: string
  /** 结果文本摘要 */
  result?: string | null
  /** 拒绝原因 / 失败原因 */
  reason?: string | null
  /** DC-14 T5：结果铸为消息后的消息 id（`tool-<toolCallId>`） */
  messageId?: string
  /** 该工具段当前是否在等人工/前端动作（确认门待放行、前端工具待执行） */
  requiresAction: boolean
  /**
   * 确认门 / 前端工具的等待上限与到期口径（T4 / T4-3 原文，仅 CONFIRM / FRONTEND 段有）。
   * ...
   */
  timeoutSeconds?: number
  expiresAt?: number
  atMs?: number
}
```

## 3. history 接口形态 / TOOL 条目的前端落点

后端形态（`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:509-533`）：每条 entry 为 `{type: MessageType.name(), text, toolCalls?, toolResponses?, timestamp?}`；`ToolResponseMessage` 走 `item.put("toolResponses", toolResponse.getResponses())`（`:519-521`），即 Spring AI `ToolResponse(id, name, responseData)` 记录 → 字段名 `id` / `name` / `responseData`。

前端定义/引用点（全 frontend 命中）：

- `frontend/src/types/ai.ts:134-143` — `AiHistoryMessage`：
  - `:135-136` `type: string`（注释明写 `USER / ASSISTANT / SYSTEM / TOOL`）
  - `:139` `toolCalls?: Array<{ id?, type?, name?, arguments? }>`
  - `:141` `toolResponses?: Array<{ id?: string; name?: string; responseData?: string }>`
- `frontend/src/api/ai.ts:15`（import）、`:64-65` `history(sessionId): Promise<AiHistory>` → `GET /ai/history/{sessionId}`
- `frontend/src/stores/ai.ts:32`（import）、`:636`、`:693`（唯一消费 `response.id` / `responseData` 的回填表达式）
- 测试夹具：`frontend/tests/parts-residue-cleanup.spec.ts:589`（`toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }]`）、`frontend/tests/ai-panel-parts-render.spec.ts:470`（`toolResponses: []`）

**R-149 关键缺口（构建循环当前完全不含 TOOL 类型分支）**：全 frontend 无任何 `'TOOL'` 字面量命中（仅后端出现）。因此 TOOL 类型 entry 现在走 `roleOf()` 的兜底 `'assistant'`（`:317`），且因 `toolCalls` 缺席 → `parts` 只落一个 `{kind:'text', text: item.text ?? ''}`（通常空串），其 `toolResponses` 被整体丢弃，无法回填到前一条 assistant 消息的工具段上 —— 这正是 R-149 合并改造要动的位置（`ai.ts:678-710`，重叠判据 `:693` 的按 id 回填）。

## 4. `frontend/tests/parts-residue-cleanup.spec.ts`（共 675 行）

当前行号与旧锚 577-610 一致，实际夹具体为 **577-612**，全文：

```ts
  it('写点⑥ loadHistory 后端历史分支：text 段 + 工具段按序构造（result 按 id 回填、requiresAction=false）', async () => {
    const store = useAiStore()
    const sid = store.sessionId
    api.history.mockResolvedValue({
      sessionId: sid,
      count: 2,
      messages: [
        { type: 'USER', text: '问题' },
        {
          type: 'ASSISTANT',
          text: '回答',
          toolCalls: [{ id: 'h1', type: 'function', name: 'probe_tool', arguments: '{"a":1}' }],
          toolResponses: [{ id: 'h1', name: 'probe_tool', responseData: 'ok' }]
        }
      ]
    })
    api.runsCurrent.mockResolvedValue(noSuspension())

    await store.loadHistory()

    const user = store.messages[0] as ChatMessage
    const assistant = store.messages[1] as ChatMessage
    expect(user.parts).toEqual([{ kind: 'text', seg: 0, text: '问题', streaming: false }])
    expect(assistant.parts.map((part) => part.kind)).toEqual(['text', 'tool'])
    expect(assistant.parts[1]).toMatchObject({
      kind: 'tool',
      seg: 1,
      toolCallId: 'h1',
      name: 'probe_tool',
      kind2: 'UNKNOWN',
      status: 'succeeded',
      args: '{"a":1}',
      result: 'ok',
      requiresAction: false
    })
  })
```

同文件相关辅助：`api` 桩 `:23`、`noSuspension()` `:88-98`、镜像用例 `:278/:323/:354`（`MIRROR_PREFIX` 写读，行 `:281/:308-309/:319/:326/:358/:380`）。

## 5. `loadHistory` 与 `history-` 全 frontend 命中

`loadHistory`（全部为调用/注释，无定义重复）：

| 文件 | 行号 |
|---|---|
| `frontend/src/stores/ai.ts` | 4（注释）, 216, 218, 221, 603（定义）, 655, 664, 713, 721 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 856 |
| `frontend/tests/ai-panel-parts-render.spec.ts` | 10（注释）, 477 |
| `frontend/tests/frontend-tool-result-terminal.spec.ts` | 293 |
| `frontend/tests/parts-double-write.spec.ts` | 15（注释）, 264, 296 |
| `frontend/tests/parts-residue-cleanup.spec.ts` | 9, 87, 119（注释）, 303, 317, 348, 375, 577（it 名）, 595 |
| `frontend/tests/restore-reconcile.spec.ts` | 90, 113, 144 |

`history-` 键：

| 文件 | 行号 | 内容 |
|---|---|---|
| `frontend/src/stores/ai.ts` | 688 | `` toolCallId: `history-${index}-${call.name ?? 'tool'}` `` |
| `frontend/src/stores/ai.ts` | 698 | `` id: `history-${index}` `` |
| `frontend/src/components/AiPanel/AiPanel.vue` | 122 | `data-testid="history-skeleton"`（与历史消息 id 无关） |

无测试对 `history-${index}` 形态的 message.id / toolCallId 做断言（改造该 id 生成不会破现有 spec，但 R-149 合并 id 对齐时需自行新增断言）。
