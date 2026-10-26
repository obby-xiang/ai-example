> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-510` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：12757 B / 9967 字符 / 173 行；行指纹覆盖率（仓内 + 落盘语料）5.8%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# streaming 属性读点穷尽扫描报告

**范围**：`<REPO_ROOT>`，HEAD = `505f6d4e0fa1360f04a3dbe3313842f1513a0952`（`git rev-parse HEAD` 实测），`git status --porcelain` 为空（零改动、零 git 写、未跑 typecheck/test/build/dev）。

## 1. 命中清单（`frontend/src`，`grep -rn "streaming"` 全量 token 命中，共 31 行）

### 1.1 `frontend/src/components/AiPanel/AiPanel.vue`（渲染层，仅 1 处）

| 文件:行号 | 代码原文 | 分类 |
|---|---|---|
| `AiPanel.vue:529` | `  return message.streaming && !message.parts.some((part) => part.kind === 'text' && part.text.length > 0)` | **读** `message.streaming`（消息级）；回调里只读 `part.kind` / `part.text`，**不读** `part.streaming` |

### 1.2 `frontend/src/utils/parts.ts`

| 文件:行号 | 代码原文 | 分类 |
|---|---|---|
| `parts.ts:46` | `  if (last && last.kind === kind && last.streaming) {` | **读** `part.streaming`（判断条件） |
| `parts.ts:49` | `  if (last && (last.kind === 'text' \|\| last.kind === 'reasoning') && last.streaming) {` | **读** `part.streaming`（判断条件） |
| `parts.ts:50` | `    last.streaming = false` | **写** `part.streaming` |
| `parts.ts:54` | `    const part: ReasoningPart = { kind: 'reasoning', seg, text: '', streaming: true }` | **写** `part.streaming`（对象字面量） |
| `parts.ts:58` | `  const part: TextPart = { kind: 'text', seg, text: '', streaming: true }` | **写** `part.streaming`（对象字面量） |
| `parts.ts:71` | `  if (last && (last.kind === 'text' \|\| last.kind === 'reasoning') && last.streaming) {` | **读** `part.streaming`（判断条件） |
| `parts.ts:72` | `    last.streaming = false` | **写** `part.streaming` |
| `parts.ts:271` | `      message.streaming = true` | **写** `message.streaming` |
| `parts.ts:281` | `      part.streaming = true` | **写** `part.streaming` |
| `parts.ts:282` | `      message.streaming = true` | **写** `message.streaming` |
| `parts.ts:288` | `      part.streaming = true`（`case 'reasoning'`） | **写** `part.streaming` |
| `parts.ts:34/40/41/42/67` | 注释文本（"streaming=true"、"\`streaming\` 标志无人消费"、"两段同时 streaming"、"仍有 streaming 段"） | 注释，非代码读点 |

### 1.3 `frontend/src/stores/ai.ts`

| 文件:行号 | 代码原文 | 分类 |
|---|---|---|
| `ai.ts:177` | `      { kind: 'text', seg: 0, text: message.content ?? '', streaming: false },` | **写** `part.streaming`（`migrateMirror` v1→v2 建段） |
| `ai.ts:595` | `          streaming: false,` | **写** `message.streaming`（`loadHistory` 镜像分支） |
| `ai.ts:652` | `          { kind: 'text', seg: 0, text: item.text ?? '', streaming: false },` | **写** `part.streaming`（`loadHistory` 历史整体替换分支建段） |
| `ai.ts:673` | `          streaming: false,` | **写** `message.streaming`（同上消息对象） |
| `ai.ts:755` | `          host.streaming = false` | **写** `message.streaming`（`restoreSuspendedSnapshot` 兜底宿主） |
| `ai.ts:940` | `          target.streaming = false` | **写** `message.streaming`（旧轮收尾） |
| `ai.ts:951` | `          message.streaming = false` | **写** `message.streaming`（`handleChatError`） |
| `ai.ts:964` | `            part.streaming = false` | **写** `part.streaming`（错误文案段写后即封口；**全文件唯一 `part.streaming` 直写点**） |
| `ai.ts:1447` | `        parts: [{ kind: 'text', seg: 0, text: content, streaming: false }],` | **写** `part.streaming`（`recordUserMessage`） |
| `ai.ts:1451` | `        streaming: false,` | **写** `message.streaming`（同上） |
| `ai.ts:1465` | `        parts: [{ kind: 'text', seg: 0, text: initial, streaming: true }],` | **写** `part.streaming`（`appendAssistantMessage`） |
| `ai.ts:1469` | `        streaming: true,` | **写** `message.streaming`（同上） |
| `ai.ts:1525` | `          message.streaming = false` | **写** `message.streaming`（旧轮迟到终帧收敛） |
| `ai.ts:1654` | `          message.streaming = false` | **写** `message.streaming`（`done` 帧） |
| `ai.ts:1674` | `          message.streaming = false` | **写** `message.streaming`（`error` 帧） |
| `ai.ts:1656` | 注释："…仍留着 streaming 段的假态…" | 注释 |

### 1.4 `frontend/src/types/ai.ts`（仅类型声明）

| 文件:行号 | 代码原文 | 分类 |
|---|---|---|
| `types/ai.ts:207` | `  streaming: boolean`（`ChatMessage`，注释「正文生成中」） | **声明** `message.streaming` |
| `types/ai.ts:229` | `  streaming: boolean`（`TextPart`，注释「该段是否仍在接收增量（封口后为 false）」） | **声明** `part.streaming` |
| `types/ai.ts:236` | `  streaming: boolean`（`ReasoningPart`） | **声明** `part.streaming` |

> `ToolPart`（:243 起）**无** `streaming` 字段；`MessagePart` 联合中只有 text/reasoning 两型带该字段。

### 1.5 次要命中：`frontend/tests/`（测试夹具/断言，非生产读点，共 33 处）

- `parts-reducer.spec.ts` 11 处、`ai-panel-parts-render.spec.ts` 10 处、`parts-double-write.spec.ts` 7 处、`parts-residue-cleanup.spec.ts` 3 处、`frontend-tool-result-terminal.spec.ts` 2 处。
- 其中唯一"读"是 `parts-double-write.spec.ts:146`：`expect(assistant.streaming).toBe(false)`（消息级断言）；其余为 `streaming: true/false` 夹具字面量与 `toMatchObject` 期望值，**无渲染层读点**。

### 1.6 全仓外溢核对

- `frontend/src/components/**` 只有 `AiPanel.vue:529` 一处（`components/AiPanel/` 仅 `AiPanel.vue`、`FormRenderer.vue` 两个文件，后者零 `streaming`）；`views/`、`api/`、`router/` 零命中。
- `grep -rn "part\.streaming"`（全仓，排除 node_modules/.git/dist/target）只命中：`utils/parts.ts:281`、`:288`、`stores/ai.ts:964`，加两份 docs 里的历史表述。
- 后端 `"streaming"` 命中全是 Java 字符串字面量（`beat.put("phase", "streaming")`、`SXSSFWorkbook` 包名、`ReasoningAwareOpenAiChatModel` 注释），**与前端属性无关**。
- `frontend/` 下 `src/`、`tests/` 之外零命中（无脚本/配置读该属性）。

## 2. 三问回答

**(a) 渲染层有没有任何一处读 `part.streaming`？—— 没有。**
`frontend/src` 全部 `.vue` 文件中 `streaming` 只有一个命中（`AiPanel.vue:529`），读的是 `message.streaming`；同行回调 `(part) => part.kind === 'text' && part.text.length > 0` 只碰 `part.kind` / `part.text`。模板里也没有 `v-if="x.streaming"` 类用法（grep 已覆盖整个 `.vue` 文件）。`part.streaming` 的生产读取只存在于非渲染层 `utils/parts.ts:46/49/71`（结果只用于"续写哪一段/是否封口"，不参与任何渲染分支）。

**(b) `AiPanel.vue:529` 是否真是渲染层唯一 `streaming` 读点？—— 是。**
在整个 `frontend/src` 中，它是唯一一处把 `streaming` 当条件读的渲染层位置；且它是全仓唯一一处 `message.streaming` **读**点（其余 `message.streaming` 全是赋值写点，`types/ai.ts:207` 是声明）。断言成立。唯一措辞校准：该行严格说是"渲染层唯一 `streaming` token 读点"，它读的是消息级而非段级——与断言原文一致。

**(c) `part.streaming` 的写点与读点全集**

写点（9 处，全部在 `frontend/src`，无后端/测试外写点）：

| # | 位置 | 形态 |
|---|---|---|
| 1 | `utils/parts.ts:50` | `last.streaming = false`（`openStreamPart` 交错先封口被中断段） |
| 2 | `utils/parts.ts:54` | 新 `ReasoningPart` 字面量 `streaming: true` |
| 3 | `utils/parts.ts:58` | 新 `TextPart` 字面量 `streaming: true` |
| 4 | `utils/parts.ts:72` | `last.streaming = false`（`sealTrailingStreamPart`） |
| 5 | `utils/parts.ts:281` | `part.streaming = true`（`delta` 帧） |
| 6 | `utils/parts.ts:288` | `part.streaming = true`（`reasoning` 帧） |
| 7 | `stores/ai.ts:964` | `part.streaming = false`（错误文案段写完即封口） |
| 8 | `stores/ai.ts:177` / `:652` / `:1447` | 建段字面量 `streaming: false`（迁移 / 历史整体替换 / 用户消息） |
| 9 | `stores/ai.ts:1465` | 建段字面量 `streaming: true`（`appendAssistantMessage`） |

读点（3 处，全在 `utils/parts.ts`，全部是"末尾段是否未封口"的续写/封口判断）：`utils/parts.ts:46`、`:49`、`:71`。**`stores/ai.ts`、`components/**`、`views/**` 对 `part.streaming` 零读**。

## 3. 五段锚点核对（逐段贴原文）

**① `stores/ai.ts:279-304`（`writeMirror`）—— 与断言基本一致，一处定位需校准。**
`:294` 确认是 `parts: message.parts,`（原样引用入镜像，浅拷贝 ⇒ 每个 `part.streaming` 随 `JSON.stringify` 持久化）；字面量键恰为 `id/role/content/parts/reasoning/toolRuns/pendingCall`，**不含** `done`、`streaming`、`appliedSeqs`。但"`Pick`"不在本段：本段是普通对象字面量，`Pick` 类型在 `types/ai.ts:341`：

```ts
341  messages: Array<Pick<ChatMessage, 'id' | 'role' | 'content' | 'parts' | 'reasoning' | 'toolRuns' | 'pendingCall'>>
```
该 `Pick` 同样不含 `done`/`streaming`/`appliedSeqs`。结论：**语义一致，但断言把 `Pick` 写在 `ai.ts:279-304` 的表述不精确**（应为"写侧字面量 + `types/ai.ts:341` 的 Pick 都不含 `done`"）。

**② `stores/ai.ts:593-597`（`loadHistory` 镜像分支，函数声明于 `:584`）—— 完全一致。**

```ts
593        this.messages = migrated.messages.map((message) => ({
594          ...message,
595          streaming: false,
596          done: true
597        }))
```
只清消息级 `streaming`（并置 `done`）；`...message` 展开保留 `parts` 原状 ⇒ `part.streaming` 残留 `true` **不被清除**。断言成立。（历史整体替换分支另在 `:652/:673` 现场新建段并置 `streaming:false`+`done:true`，也不"清"已有 parts。）

**③ `stores/ai.ts:228-237`（`sanitizeMirrorResidues` 主体）—— 完全一致。**

```ts
228    message.parts.forEach((part, index) => {
229      if (part.kind === 'tool' && part.requiresAction) {
230        message.parts.splice(index, 1, {
231          ...part,
232          requiresAction: false,
233          status: part.status === 'pending' ? 'expired' : part.status
234        })
235        changed = true
236      }
237    })
```
函数体 `:222` 起、前置守卫 `:225-227`（`message.done !== true` 即 `continue`）。**只收 `requiresAction`（且仅 tool 段），完全不触碰 `streaming`**，即"不封口"断言成立。

**④ `utils/parts.ts:44-51`（`openStreamPart` 头部）—— 完全一致。**

```ts
44 export function openStreamPart(message: ChatMessage, kind: 'text' | 'reasoning'): TextPart | ReasoningPart {
45   const last = message.parts[message.parts.length - 1]
46   if (last && last.kind === kind && last.streaming) {
47     return last
48   }
49   if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
50     last.streaming = false
51   }
```

**⑤ `utils/parts.ts:69-74`（`sealTrailingStreamPart`）—— 完全一致。**

```ts
69 export function sealTrailingStreamPart(message: ChatMessage): void {
70   const last = message.parts[message.parts.length - 1]
71   if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
72     last.streaming = false
73   }
74 }
```

## 4. 断言判定汇总

| 断言子句 | 判定 |
|---|---|
| 渲染层不读 `part.streaming` | **成立**（渲染层零 `part.streaming` 读点） |
| 全仓唯一 `streaming` 渲染读点 = `AiPanel.vue:529` 的 `message.streaming` | **成立**（`frontend/src` 内唯一；亦为全仓唯一 `message.streaming` 读点） |
| 写侧 Pick 不含 `done` | **成立，定位需校准**（`Pick` 实在 `types/ai.ts:341`；`ai.ts:279-304` 是等价字面量） |
| `:294` 把 `message.parts` 原样入镜像（含每个 `part.streaming`） | **成立**（浅引用 + `JSON.stringify` 落盘） |
| `loadHistory:593-597` 只清 `message.streaming` 不清 `part.streaming` | **成立** |
| `sanitizeMirrorResidues:228-237` 只收 `requiresAction` 不封口 | **成立** |

**补充事实（A6 草案可能漏记）**：`part.streaming` 的写点不止 `utils/parts.ts:281/288` 两处，还有 `utils/parts.ts:50/54/58/72` 与 `stores/ai.ts:964/177/652/1447/1465` —— 早期红队报告 `docs/evidence/补档-CHATTIMELINE-红队报告-DS-V4-Pro.md:118` 与 `docs/M2-排期计划.md:327` 中"仅 :1454/:1463 两处写入"的说法在当前 HEAD 上已过时（行号亦已漂移）。

实际执行模型自证：本子智能体运行环境无法自省底层模型 ID，仅能声明按父棒显式下发 `model=DeepSeek/deepseek-flash` 派发，未做独立自证。
