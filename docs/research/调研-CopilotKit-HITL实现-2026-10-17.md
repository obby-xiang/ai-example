# 调研 · CopilotKit HITL 实现级参照（源码取证 + 官方文档双源）· 2026-10-17

> 调研问题：CopilotKit 的 HITL（human-in-the-loop）在**实现层**是怎么做的？三个 HITL API 的语义分野、agent 暂停到前端挂起到回传恢复的**逐跳报文与状态机**、多 interrupt 并发与刷新恢复机制，以及这些机制映射到本仓（确认门 / GF 表单，目标五类打断）要做什么。
> 来源①**源码**：`<REPO-URL_COPILOTKIT>` 即 `CopilotKit/CopilotKit`，本地克隆于**仓外** `_cpk-src/repo`（`blob:none` 部分克隆）。锚定 commit `ff1178a6a08ec9cc55dd2cce678e27d07e26a27a`（提交时间 2026-10-16T18:59:45Z），包版本 `1.79.1`。**本件所有行号均以该 commit 的工作树为准**；`@copilotkit/*` 源码路径一律写作仓库内相对路径 `packages/…`。
> 来源②**协议依赖**：`@ag-ui/client` / `@ag-ui/core` / `@ag-ui/encoder` 均 `1.0.2`（取自 `packages/core/package.json` 等四处 `dependencies`）。这三个包在克隆中不入工作树，故本次以 **npm 发布产物的 dist 实际类型与实现**取证（压缩单文件，以「函数名 + 字节偏移」标注），凡不能反查 TypeScript 源之处均如实标注为「发布产物口径」。
> 来源③**官方文档**：`docs.copilotkit.ai`（Mintlify，任意页 URL 后加 `.md` 直取源文）、`docs.ag-ui.com`（同法）、`www.copilotkit.ai/learning/*`（**营销/学习站，非 docs 站**，但它是「五类打断」分类法的**一手出处**，见下文订正 1）。**`reference/hooks/<name>` 与 `reference/v2/hooks/<name>` 是同一页的两个路径（逐字节相同）**，见「取证基线」的路径别名核验。
> 核验轮次：首轮取证（2026-10-17，commit `b6a88b2`）→ **第二轮核验（2026-10-17）**：按用户指认的 `/reference/v2/hooks/useHumanInTheLoop` 复核路径别名（结论：同一页）；并借此发现首轮的 **3 处缺口/误记** —— ①`packages/react-ui` 是独立包（首轮误记为「无」）；②漏读 `useInterrupt` 的 reference 页与 Vue / React Native 两页（后者揭示 **Vue 版只有 legacy 流**）；③v2 reference 页的 `available` 类型与源码不符。三处均已在本件就地更正/补写，并在「与既有件的差异订正」表登记（第 2、5、6、7 行）。
> 结论：**CopilotKit 的 HITL 只有一条底层机制 —— 前端工具 handler 返回一个「悬挂的 Promise」，或后端 run 以 `RUN_FINISHED{outcome:{type:"interrupt",interrupts}}` 终止；三个 HITL API 是这条机制上的三种薄封装**。`useCopilotAction(renderAndWaitForResponse)` 在 1.79.1 里已**被源码改写为 `useHumanInTheLoop` 的调用**，v1 与 v2 共用同一条实现；`useInterrupt` 是唯一走 AG-UI 标准 interrupt 通道的那一个。恢复（resume）在协议层是**同一 thread 上的新一轮 run**，携带 `resume[]`；刷新后的卡面**不靠任何前端持久化**，而靠「`connectAgent` 重放线程事件 → 前端以 `restore` 模式**只重跑 HITL handler**」重建。
> **第二条要紧结论（第二轮新增，见 §2.5）**：这套能力**只在 React / React Native 上是全量的**。**Vue 版的 `useInterrupt` 只有 legacy 流**（无 `interrupts[]`、无 `cancel`、无 `expiresAt`），恢复走 `command.resume` 而非标准 `resume[]`。本仓是 Vue 3，因此**参照系应取 React 契约 + AG-UI 协议，而不是 CopilotKit 的 Vue 面**。
> **对本仓最要紧的一条推论**（见 §2.1 末段）：CopilotKit 用它自己的二分法（暂停由 LLM 决定 = 链 A；由代码路径决定 = 链 B）把「确定性闸门」归入 `useInterrupt`/链 B。本仓确认门正是**代码路径确定性**的闸门，因此要抄的是**链 B**——而现有 `SpToolCallingManager` 实现形似链 A。**这是一个需要显式选定并写下理由的路线归属问题，不是必须立即改代码的问题。**
> 入库脱敏：本机路径按既有体例写作仓外相对口径；第三方仓库指代用 `<REPO-URL_…>` 占位；无 API Key。

---

## 取证基线（复现锚点）

| 项 | 值 | 用途 |
|---|---|---|
| CopilotKit commit | `ff1178a6a08ec9cc55dd2cce678e27d07e26a27a`（2026-10-16T18:59:45Z） | 源码行号基准 |
| CopilotKit 包版本 | `1.79.1`（`packages/react-core/package.json:3` 等四处同版） | 判定 v1/v2 分野 |
| `@ag-ui/client` | `1.0.2` | `Interrupt` / `ResumeEntry` / `buildResumeArray` / `pendingInterrupts` |
| `@ag-ui/core` | `1.0.2` | `RunFinishedOutcome` 判别联合类型 |
| `@ag-ui/encoder` | `1.0.2` | SSE 出帧格式（`EventEncoder`） |
| 仓库工作区 | `packages/{core,react-core,react-ui,runtime,shared}`（**5 个**）—— `react-ui` **是**独立包，但其 v2 面是**零导出的样式壳**，HITL 实现在 `react-core` | 见 §1.2 更正记录（本条在首轮普查时曾误记为「无 react-ui」） |

**官方文档出处一览（本次逐页直取 `.md` 源文，全部 HTTP 200）**

| # | URL | 抽取了什么 |
|---|---|---|
| D1 | `https://docs.copilotkit.ai/human-in-the-loop.md` | 两种暂停模式的选择矩阵（LLM vs graph）、`useHumanInTheLoop` 的 `respond` 语义 |
| D2 | `https://docs.copilotkit.ai/human-in-the-loop/useInterrupt.md` | 服务端契约、`resolve`/`cancel`、standard vs legacy 两流、多中断、`responseSchema` 只呈现不校验 |
| D3 | `https://docs.copilotkit.ai/reference/hooks/useHumanInTheLoop.md` | 三态 render props 表、`respond` 仅 `executing` 可用、「built on top of `useFrontendTool`」 |
| D4 | `https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction.md` | `renderAndWaitForResponse` 逐字定义与 v1 弃用横幅 |
| D5 | `https://docs.ag-ui.com/concepts/interrupts.md` | `RunFinishedOutcome` / `Interrupt` / `resume` 结构、contract rules 8 条、reason taxonomy、`editedArgs` 能力信号、framework integrations 表 |
| D6 | `https://www.copilotkit.ai/learning/what-is-human-in-the-loop` | **五类打断分类法的一手出处**、界面三职责、HITL vs HOTL 对照 |
| D7（旁证） | `https://docs.ag-ui.com/sdk/js/core/events` | `EventType` 全表、`BaseEvent` 三字段、各事件字段表；**该页 `RunFinishedEvent` 缺 `outcome`，见 §3.5 不一致登记** |
| D8（核验） | `https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop{,.md}`（含不带后缀变体） | **路径别名核验**：与 D3 **逐字节相同**（sha256 `b78fe4f3…`，11590 B），见下方「路径别名」 |
| D9 | `https://docs.copilotkit.ai/reference/hooks/useInterrupt.md`（别名 `/reference/v2/hooks/useInterrupt.md`，逐字节相同，sha256 `aed38beb…`，6822 B） | **`useInterrupt` 的 reference 页**（与 D2 的 guide 页不同页）：参数逐条语义、`renderInChat` 返回型表、handler 错误边界、expired 行为；首轮遗漏，本次补 |
| D10 | `https://docs.copilotkit.ai/reference/vue/hooks/useInterrupt.md` | **Vue 版只有 legacy 流**（无 `interrupts[]`、无 `cancel`、无 `expiresAt`），见 §2.5 |
| D11 | `https://docs.copilotkit.ai/reference/react-native/hooks/useInterrupt.md` | RN 版自述「Re-exported from `@copilotkit/react-core/v2`，identical … only the import path differs」；并给 legacy `cancel()` 的语义 |

**路径别名核验（本次专项，2026-10-17）**：`docs.copilotkit.ai` 上 `reference/hooks/<name>` 与 `reference/v2/hooks/<name>` **是同一页的两个可访问路径**，字节级一致：

| 路径对 | HTTP | 字节数 | sha256 |
|---|---|---|---|
| `…/reference/hooks/useHumanInTheLoop.md` ↔ `…/reference/v2/hooks/useHumanInTheLoop.md` | 200 / 200 | 11590 / 11590 | 双路径同为 `b78fe4f3f7d9ab739409c08351643e9717f47aa91a7a8a31f5d4574b96df23e4` |
| `…/reference/hooks/useInterrupt.md` ↔ `…/reference/v2/hooks/useInterrupt.md` | 200 / 200 | 6822 / 6822 | 双路径同为 `aed38beba2e74183d561fe830f74d1a7ea61e4a074734111c6836804bf4f8480` |

不带 `.md` 的两个变体均返回 `200 text/html`（295853 / 295862 B、204863 / 204872 B，差值 9 B 即 HTML 内 canonical 路径里多出的 `/v2` 字样），**正文内容无差异**；`/v2/` 页只是同一文档的别名，**不含任何 v2 特有段落**（无额外参数、无额外示例、无版本说明）。

**canonical 判定**：`sitemap.xml`（533892 B）里 `reference/hooks/useInterrupt` 与 `reference/hooks/useHumanInTheLoop` **在列**，而 `reference/v2/hooks/*` **不在列**；但 CopilotKit 文档自身的站内链接（v1 `useHumanInTheLoop` 页的 Callout、RN 页的 Callout）**写的是 `/reference/v2/hooks/…`**。即：**sitemap 认非 `/v2/` 为 canonical，站内链接认 `/v2/` 为推荐路径，两者同页**。**结论：本件首轮引用的 D3 与用户指认的 `/reference/v2/…` 是同一份内容，引用无误差。**
**另核**：`sitemap.xml` 中 `reference/v2/` 全量 19 条 URL，**除 `useCapabilities`（各框架镜像）外无任何其它页面** —— 故 `reference/v2/` 下**不存在**被漏掉的 HITL 页；本次新增的 D9–D11 三页都在 `reference/hooks/`（非 `/v2/`）路径下。

---

## 一、架构总览

### 1.1 三层与协议的关系（仓库一手声明）

`AGENTS.md:20` 逐字：

> AI agent framework with three layers: **Frontend** (React/Angular/Vanilla) → **Runtime** (Express/Hono) → **Agent** (LangGraph/CrewAI/BuiltIn/Custom), communicating via the AG-UI protocol (event-based SSE).

即：**AG-UI 不是 CopilotKit 的一个可选适配层，而是它三层之间的唯一线上协议**；传输是 **SSE**（服务端→客户端单向事件流）+ **POST 请求体**（客户端→服务端，`RunAgentInput`）。这不是「SSE 还是 AG-UI 事件流」的二选一：**AG-UI 是报文语义，SSE 是承载**。

### 1.2 包矩阵：v1 与 v2 同包共存

`AGENTS.md:25` 逐字：

> **Flat package structure** — All packages live under `packages/` with the `@copilotkit/` scope. Dual-version packages keep deprecated implementation code under `src/v1-deprecated/`, route the package root through `src/v1-deprecated-compatibility.ts`, and keep current code under `src/v2/`. These remain one published package; the deprecated source directory is not a public subpath.

实测目录：`packages/react-core/src/{v1-deprecated, v2, v1-deprecated-compatibility.ts, index.ts}`、`packages/runtime/src/{v1-deprecated, v2, langgraph.ts, …}`。`packages/runtime/src/langgraph.ts` 全文仅 9 行，内容是一个兼容转发：

```ts
/*
 * V1 SDK DEPRECATED. USE V2 INSTEAD.
 *
 * This entrypoint exists only for compatibility with
 * `@copilotkit/runtime/langgraph`. New code must use `@copilotkit/runtime/v2`
 * and https://docs.copilotkit.ai/reference/v2.
 */

export * from "./v1-deprecated/langgraph";
```

**对本仓含义**：CopilotKit 用「同包双目录 + 根导出重定向」承载 v1→v2 迁移，而不是发两个包。这是它敢把 v1 API 与 v2 API 并列讲「三个 HITL API」的结构前提。

**更正记录（2026-10-17 二次核验）**：本件首轮普查时把工作区记为「4 个包、无独立 `react-ui`」，**该记录有误**。二次核验实测为 **5 个包**：

```
packages/
├── core        @copilotkit/core        1.79.1
├── react-core  @copilotkit/react-core  1.79.1
├── react-ui    @copilotkit/react-ui    1.79.1   ← 首轮漏记
├── runtime     @copilotkit/runtime     1.79.1
└── shared      @copilotkit/shared      1.79.1
```

首轮漏记的原因：本次 CopilotKit 克隆是 `blob:none` 部分克隆，`packages/react-ui/` 的目录树（mtime 晚于其它四个包）在首轮 `ls packages/` 时尚未落盘。**但首轮那条「v2 HITL 实现不在 react-ui」的判断仍然成立**，二次核验逐条确认：

- `packages/react-ui/src/v2/index.ts` **全文只有一行** `import "./styles.css";` —— **零导出**，只是个样式壳；
- `packages/react-ui/src/index.tsx` 头部自述逐字：*"V1 SDK DEPRECATED. USE V2 INSTEAD. This is the deprecated v1 entrypoint `@copilotkit/react-ui`. … This entrypoint currently exposes 38 deprecated public exports. **V2 source entrypoint: packages/react-core/src/v2/index.ts**"*；
- v2 的 HITL 实现确实全在 `packages/react-core/src/v2/**`（`hooks/use-human-in-the-loop.tsx`、`hooks/use-interrupt.tsx`、`lib/react-core.ts` 的 `setInterruptElement`）与 `packages/core/**`（`interrupt-state.ts`、`core/run-handler.ts`）。

**react-ui 里唯一与中断有关的、值得记录的实现**在于它的 **v1** 聊天组件，且它补上了本件 §4.4 缺的**前端一侧**的「挂起阻塞新输入」证据：

- `packages/react-ui/src/components/chat/Input.tsx:95` `const { interrupt } = useCopilotChatInternal();`，紧接 `:97-104` 的 `canSend` 计算里含 `!interrupt` —— **有挂起时发送钮被禁用**（这是 UI 层；`@ag-ui/client` 里的 `onInitialize` 抛错是同一规则的运行时层，见 §4.4）。
- `packages/react-ui/src/components/chat/Messages.tsx:33` 取同一个 `interrupt`，`:118` 直接 `{interrupt}` 渲染进消息列表 —— **中断是一个「React 节点槽位」**，与 v2 的 `copilotkit.setInterruptElement(element)` 是同一设计（§2.4），只是 v1 用 context 传递、v2 用 Core 订阅发布。

### 1.3 通道：SSE 出帧的确切格式

Runtime 的出帧统一走 `packages/runtime/src/v2/runtime/handlers/shared/sse-response.ts:76` 的 `const encoder = new EventEncoder();`，两类端点的 observable 分别由 `handlers/sse/run.ts:20-54`（`runtime.runner.run`）与 `handlers/sse/connect.ts:31-70`（`runtime.runner.connect`）提供。路由见 `packages/runtime/src/v2/runtime/endpoints/express.ts:119-122`：

```
 * - `POST {basePath}/agent/:agentId/run` — start an agent run
 * - `POST {basePath}/agent/:agentId/connect` — connect to an agent run
 * - `POST {basePath}/agent/:agentId/stop/:threadId` — stop an agent run
```

`@ag-ui/encoder@1.0.2` 的 `EventEncoder.encodeSSE`（发布产物 `dist/index.mjs`）：

```js
encodeSSE(event) {
  return `data: ${JSON.stringify(omitOptionalNulls(event, "Event"))}\n\n`;
}
```

**要点**：协议出帧是**裸 `data:` + JSON 单行 + 空行**，**没有 `event:` 名**、没有 id/retry 字段——事件类型全在 JSON 体内的 `type` 字段（AG-UI `EventType`）。`omitOptionalNulls(…, "Event")` 会把可选的 `null` 字段剔掉，故前端不能假定所有可选字段恒在。

**旁证（协议本体也没有序号）**：`@ag-ui/core@1.0.2` 的 `BaseEvent`（`dist/version-Dr0GiU8W.d.mts:2862`）只有四个字段 —— `type` / `timestamp?` / `rawEvent?` / `metadata?`，**无 `id`、无序号**。故「断线续传到哪里」在 AG-UI 里不存在协议级答案，只能靠服务端重放全量事件（§4.3）。

**对本仓含义**：本仓 `SseChatEmitter` 的帧带 `seq`（`GET /api/ai/events/{runId}?lastSeq=N` 差量补发依赖它），AG-UI 的 SSE 帧**不带走序号**——它的重放粒度是「整个事件的 ReplaySubject」（见 §4.3）。两套各有取舍，不是同一件事。

### 1.4 关键发现：三个 HITL API 在实现层已收敛为一条链

`packages/react-core/src/v1-deprecated/hooks/use-copilot-action.ts` 的 `getActionConfig`（:198-213）把 `renderAndWaitForResponse` 直接判成 `hitl` 类型：

```ts
if ("renderAndWaitForResponse" in action || "renderAndWait" in action) {
  let render = action.render;
  if (!render && "renderAndWaitForResponse" in action) {
    render = action.renderAndWaitForResponse;
  }
  if (!render && "renderAndWait" in action) {
    render = action.renderAndWait;
  }

  return {
    type: "hitl" as const,
    action: { ...action, render } as UseHumanInTheLoopArgs<T>,
  };
}
```

然后在 hook 主体（:272-279）分派：

```ts
switch (currentActionConfig.type) {
  case "render":   return useRenderToolCall(currentActionConfig.action, dependencies);
  case "hitl":     return useHumanInTheLoop(currentActionConfig.action, dependencies);
  case "frontend": return useFrontendTool(currentActionConfig.action, dependencies);
```

而 v1 的 `useHumanInTheLoop`（`v1-deprecated/hooks/use-human-in-the-loop.ts:189-197`）只是**把 v1 的 `Parameter[]` 与字符串 render 规整后转发给 v2**：

```ts
useHumanInTheLoopVNext({
  name,
  description,
  followUp,
  parameters: zodParameters,
  render: ((args: HumanInTheLoopRenderArgs) =>
    renderRef.current?.(args as HitlRendererArgs) ?? null) as HumanInTheLoopOptions["render"],
});
```

**结论**：`useCopilotAction({renderAndWaitForResponse})` **不是**第二条实现路径，而是 `useHumanInTheLoop` 的 v1 语法糖。真正独立的两条是——

1. **前端工具悬挂链**：`useHumanInTheLoop` / `useCopilotAction(renderAndWaitForResponse)` / `CopilotKitProvider` 的 `humanInTheLoop` 静态 prop（三者的 handler 语义完全相同，见 §3.7）；
2. **AG-UI 标准 interrupt 链**：`useInterrupt` / 手写 headless。

---

## 二、三个 HITL API 的语义与区别

### 2.1 对照总表

| 维度 | `useHumanInTheLoop`（v2） | `useCopilotAction(renderAndWaitForResponse)`（v1） | `useInterrupt`（v2） |
|---|---|---|---|
| 暂停由谁决定 | **LLM** 决定调用一个前端注册的工具 | 同左（**已改写成前者**） | **图/后端**决定（`interrupt()`） |
| 服务端契约 | 无（前端工具描述随 run 上传） | 无 | `langgraph.interrupt()` / 框架原生 pause |
| 线上形态 | `TOOL_CALL_START`/`ARGS`/`END` 后**不出现** `TOOL_CALL_RESULT`，run 正常结束 | 同左 | `RUN_FINISHED` 带 `outcome:{type:"interrupt",interrupts}` |
| 悬挂机制 | 前端 handler 返回一个**永不 resolve 的 Promise**，`respond` 时才 resolve | 同左 | 前端 `ɵInterruptState` 累积响应，齐了才 `runAgent({resume})` |
| 回传回调 | `render` props 里的 `respond(result)`（**仅 `status==="executing"` 时有值**） | 同左（props 名同为 `respond`） | `render` props 里的 `resolve(payload?, interruptId?)` / `cancel(interruptId?)` |
| 挂起态可辨识 | `ToolCallStatus.InProgress → Executing → Complete` 三态 | 同左（v1 三态字符串 `inProgress`/`executing`/`complete`） | `interrupt`/`interrupts` 非空即挂起；无态机 |
| 挂起信息载荷 | 工具入参（模型填）+ description | 同左 | `{id, reason, message?, toolCallId?, responseSchema?, expiresAt?, metadata?}` |
| 多挂起并发 | 天然支持（每个 tool call 各一张卡） | 同左 | 需 `resolve(…, interruptId)`；**全部答复后**才发一次 `resume` |
| 本仓对应 | 确认门 + GF 表单（前者更贴） | —— | 后端 `interrupt()` 式确定性暂停 |

「暂停由谁决定 / 服务端契约」两行的文档依据是 D1 原文表格（逐字）：

| Pattern | Who decides to pause? | Backend surface |
| --- | --- | --- |
| `useHumanInTheLoop` | The **LLM**, by calling a registered client-side tool | A frontend-only tool description (Zod schema + `render`) |
| `useInterrupt` | The **graph**, by calling `interrupt(...)` during a node | A server-side `interrupt()` call in your LangGraph agent |

D1 同页对选择判据的原文（逐字）：

> **Pick `useHumanInTheLoop`** when the pause is an _agent-initiated_ decision — the model chose to ask the user — and you want the picker UI inlined into the normal tool-call flow.
> **Pick `useInterrupt`** when the pause is a _graph-enforced_ checkpoint — the code path deterministically requires a human answer — and you want `langgraph.interrupt()` as the server-side contract.

**对本仓含义**：本仓确认门（`start_export` / `import` / `publish` / `precheck`）是**代码路径确定性的**闸门（工具名硬编码，不是模型自己决定要不要问），因此在 CopilotKit 的二分法里**属于 `useInterrupt` 那一类**，而不属于 `useHumanInTheLoop`。这决定了我们要抄的是**链 B**（`RUN_FINISHED` + `resume`），而不是链 A（前端工具悬挂 Promise）——**尽管本仓 `SpToolCallingManager` 现有实现对得上链 A**。这是本次取证最有决策价值的一条。

### 2.2 `useHumanInTheLoop`（v2，当前 API）—— **不是 hook 而是「注册即工具」**

`packages/react-core/src/v2/hooks/use-human-in-the-loop.tsx:13-15` 签名：

```ts
export function useHumanInTheLoop<
  T extends Record<string, unknown> = Record<string, unknown>,
>(tool: ReactHumanInTheLoop<T>, deps?: ReadonlyArray<unknown>) {
```

官方参考页（`https://docs.copilotkit.ai/reference/hooks/useHumanInTheLoop.md`）逐字：

> `useHumanInTheLoop` registers an interactive tool that pauses agent execution until the user responds through your custom UI. Unlike `useFrontendTool`, there is no `handler` function. Instead, the hook provides an internal status machine (`InProgress` -> `Executing` -> `Complete`) and supplies a `respond` callback to the render component while the tool is in the `Executing` state. The agent remains paused until `respond` is called with the user's input.
> This hook is built on top of `useFrontendTool` with an internally managed handler that resolves the tool call promise when `respond` is invoked.

「**没有 handler 参数**」是它最容易被误解的一点：它对调用方无 handler，但对框架**自己造了一个 handler**（见 §3.7）。

三态 render props 的类型在 `packages/react-core/src/v2/types/human-in-the-loop.ts` 里是**判别联合**，`respond` 只在 `Executing` 分支存在：

```ts
  render: React.ComponentType<
    | { name; description; toolCallId; agentId?; args: Partial<T>;
        status: ToolCallStatus.InProgress; result: undefined; respond: undefined }
    | { name; description; toolCallId; agentId?; args: T;
        status: ToolCallStatus.Executing; result: undefined;
        respond: (result: unknown) => Promise<void> }
    | { name; description; toolCallId; agentId?; args: T;
        status: ToolCallStatus.Complete; result: string; respond: undefined }
  >;
```

同一文件还登记了一处对**审计归因**有价值的细节（逐字）：

> - `toolCallId` — the AG-UI tool call id, unique per interrupt. It is the stable key for correlating this interrupt with runtime (sub)agent attribution …
> - `agentId` — the agent this tool was *registered* to … This is the static registration scope; it is **NOT** necessarily the runtime (sub)agent that raised the interrupt.

`useLayoutEffect` 里的卸载清理（:136-140）有一条重要注释，解释了为什么必须是 layout effect：

```ts
  // This MUST stay a layout effect to match `useFrontendTool`, which registers
  // the renderer in its own layout effect. React runs every cleanup in a phase
  // before any effect of that same phase, but it runs the whole layout phase
  // ahead of the whole passive phase. If this teardown were passive while the
  // registration is layout, a keyed remount would order the outgoing
  // instance's removal *after* the incoming instance's registration and delete
  // the renderer that had just been added, leaving the tool unrenderable.
```

**对本仓含义**：HITL 卡渲染器的**注册/注销次序**是竞态源，且 CopilotKit 的结论是「注册与注销必须同相（layout）」。本仓 `AiPanel.vue` 用单文件内 `activeForm`/`pendingHitl` 身份管理而非插件式注册，不直接吃这个坑，但**一旦未来把卡注册改成可插拔组件**，这条注释就是先例依据。

#### 2.2.1 `deps` 的刷新语义（D3 = D8 逐字）

> An optional dependency array. When provided, the tool registration is refreshed whenever any value in the array changes.

即 `deps` 变化 = **重新注册**（不是更新已有注册）。这与源码一致：`use-human-in-the-loop.tsx:124` 把 `deps` 原样转交 `useFrontendTool(frontendTool, deps)`。

#### 2.2.2 **文档与源码的一处类型不一致（本次核验新发现）**

D3 / D8（`reference/hooks/useHumanInTheLoop`，即 `/reference/v2/…` 别名）的参数表逐字把 `available` 记为：

> `available` type `'"enabled" | "disabled" | "remote"'` default `'"enabled"'` — Controls tool availability. Set to `"disabled"` to temporarily prevent the agent from requesting human input through this tool.

但 **v2 源码里 `available` 是 `boolean`，且语义不同**。`packages/core/src/types.ts:124-130` 逐字：

```ts
  /**
   * Whether this tool is available to the agent.
   * Set to false to hide the tool from the agent without unregistering it.
   * Defaults to true when not specified.
   */
  available?: boolean;
```

字符串三值 `'enabled' | 'disabled' | 'remote'` 是 **v1** 的形状（`use-copilot-action.ts:215-227` 里正是按这三个字符串分支的）。**结论：v2 reference 页在 `available` 这一条上写的是 v1 的类型与语义**——这是本件登记的**第二处文档滞后**（第一处见 §3.5）。对本仓的直接提示：**照 v2 reference 页抄 `available: "disabled"` 会拿到 `boolean` 类型的工具，字符串值在 v2 下不等于「禁用」**；v2 的正确写法是 `available: false`。

（注：本件首轮已从既有件 `raw-copilotkit.md` 转述过 v1 的一处同类缺口——*"`available` … is currently non-functional in this v1 hook. The value is accepted for API compatibility but ignored"*。两处合并看：**`available` 这个字段在 CopilotKit 的文档里跨 v1/v2 一直不够准**。）

### 2.3 `useCopilotAction(renderAndWaitForResponse)`（v1）—— 迁移期语法糖

官方 v1 参考页（`https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction.md`）对 `renderAndWaitForResponse` 的逐字定义：

> This is similar to `render`, but provides a `respond` function in the props that you must call with the user's response. The component will remain rendered until `respond` is called. The response will be passed as the result to the action handler.

props 里 `respond` 的可用性约束逐字：

> A function that must be called with the user's response. The response will be passed as the result to the action handler. Only available when status is `"executing"`.

该页顶部同时有弃用横幅：

> ## v1 SDK deprecated. Use v2 instead
> This page is in the deprecated CopilotKit v1 reference. Do not use v1 APIs for new code; use v2 instead.

**对本仓含义**：我们没必要同时参照两个 API。**只需照 `useHumanInTheLoop` 的语义（三态 + `respond` 仅 Executing 可用）**，`renderAndWaitForResponse` 的历史价值仅在于说明「v1 时 `respond` 是可空 prop，v2 改成判别联合后类型上强制分支」。

### 2.4 `useInterrupt`（v2）—— 唯一的「协议级」中断

官方页（`https://docs.copilotkit.ai/human-in-the-loop/useInterrupt.md`）逐字：

> `useInterrupt` lets your agent pause mid-run, hand control to the user through a custom React component, and resume with whatever the user returns.
> CopilotKit's `useInterrupt` is the frontend half of that contract: it subscribes to the paused run, renders whatever component you give it, and calls the agent back with the user's answer.

选择判据逐字：

> Reach for `useInterrupt` when the pause is a **graph-enforced checkpoint** where the code path _must_ stop and wait for a human, not an LLM-initiated tool call.

配置面（源码 `use-interrupt.tsx:80-137`）：`render`（必填）、`handler?`（render 之前的预处理，可自行提前 `resolve`）、`enabled?`（谓词过滤，多中断分派）、`agentId?`（**默认取当前 chat agent，不匹配则中断永不触发**）、`renderInChat?`（默认 `true` 发布进 `<CopilotChat>`；`false` 时 hook 返回元素由调用方自处置）。

`renderInChat` 的机制在 `use-interrupt.tsx:466-484`：

```ts
  // Publish to core for in-chat rendering. Publish-only.
  useEffect(() => {
    if (config.renderInChat === false) return;
    copilotkit.setInterruptElement(element);
  }, [element, config.renderInChat, copilotkit]);

  // Nullify on true unmount only.
  useEffect(() => {
    if (config.renderInChat === false) return;
    return () => {
      copilotkit.setInterruptElement(null);
    };
  }, []);

  if (config.renderInChat === false) {
    return element as UseInterruptReturn<TRenderInChat>;
  }

  return undefined as UseInterruptReturn<TRenderInChat>;
```

**关键**：第二个 effect 的依赖数组是**空**（注释 `Nullify on true unmount only.`）——即页面每次 `interruptElement` 变化**不会**清空，只有真卸载才清空；`setInterruptElement` 是 Core 层（`react-core/src/v2/lib/react-core.ts:122-131`）向订阅者广播的**单向发布口**，`CopilotChatMessageView.tsx:552-563` 订阅它并把元素插进消息列表。

`responseSchema` 的边界（官方页逐字）：

> The `Interrupt` type exposes a `responseSchema` field (a JSON Schema object) that the agent can use to describe the expected payload shape. `useInterrupt` surfaces this field on `interrupt.responseSchema` for your UI to read (e.g. to drive a form), but it does **not** validate `resolve` payloads against it. Validation is the agent's responsibility on resume.

**对本仓含义（重要且正向）**：本仓 `FrontendToolGuard.inspectResult` **服务端复核回灌结果、不合法即 400 且状态不变**，与 AG-UI 的「验证是 agent 的职责」完全同向；而 AG-UI 明确要求**客户端也应该先验**（`docs.ag-ui.com` contract rule 6 逐字：*"Clients should validate before submitting."*）。本仓现在是「客户端少验、服务端硬验」，缺口见 §5.3。

#### 2.4.1 补：`useInterrupt` 的 **reference 页**增量（来源 D9，首轮遗漏）

本件首轮只读了 **guide 页**（D2，`human-in-the-loop/useInterrupt`），漏了 `useInterrupt` 的 **reference 页**（D9，`reference/hooks/useInterrupt`，与 `/reference/v2/hooks/useInterrupt` 同页）。D9 与 D2 是**不同页面**，前者是参数/返回值参考，本件此前未引用。逐条增量（除注明外均为 D9 逐字原文，每一项均已回源码或回到本件已有章节交叉确认）：

| # | D9 增量 | 源码交叉确认 |
|---|---|---|
| 1 | **`handler` 的错误边界**："Optional preprocessing callback. Runs before rendering and can return sync or async data that is exposed as `result` in `render`. `TResult` is automatically inferred from the handler's return type. **If the handler throws/rejects, `result` is `null`.**" | ✅ `use-interrupt.tsx:416-425`（同步 throw → `console.error` + `setHandlerResult(null)`）、`:427-438`（Promise reject → `console.error` + `null`） |
| 2 | **`enabled` 的作用域**："Optional filter. Return `false` to ignore matching interrupts **for this hook instance**." | ✅ 谓词与 `render` 都按 hook 实例各自求值（`use-interrupt.tsx:376-388` `isEnabled`，`enabled` 抛异常按「禁用」处理并 `console.error`） |
| 3 | **`result` 的静态类型**："`result` is inferred from `handler` return type and is always `TResult \| null`." | ✅ `use-interrupt.tsx:54-59` 的 `UseInterruptReturn` 与 `:179-181` 的 state 初值 `null` |
| 4 | **`renderInChat` 的返回型表**（D9 §Return Value 逐字）：`renderInChat: false` → `React.ReactElement \| null`；`true` 或省略 → `void`；动态 boolean → `React.ReactElement \| null \| void` | ✅ `use-interrupt.tsx:480-484`（`false` 分支 `return element`，否则 `return undefined`）；本件 §2.4 已引源码 |
| 5 | **`event.value` 的静态类型**："`event.value` is typed as `any` since the interrupt payload shape depends on your agent. Type-narrow it in your callbacks (e.g. `handler`, `enabled`, `render`) as needed." | ✅ `types/interrupt.ts:15-19` `InterruptEvent<TValue = unknown>`，React 侧实例化到 `any` |
| 6 | **过期行为**："Expired interrupts (past `expiresAt`) **are not resumed** — the hook logs an error and clears pending state." | ✅ `use-interrupt.tsx:268-275`（`console.error` + `interruptState.clear()` + `setPending(null)`，**不 resume**）；协议侧同义见 §4.4 |
| 7 | **legacy 的 `cancel()` 语义**（来源 D11，RN 页逐字）："`cancel(interruptId?)`: … **Not supported for legacy interrupts (dismisses without resuming)**." | ✅ `use-interrupt.tsx:318-326` `decision.kind === "dismiss"` → `console.warn` + 清 pending，**不发 run** |
| 8 | **legacy 载荷是「不透明字符串」的实证示例**（D9 §Usage）：`enabled: (event) => event.value.startsWith("approval:")`、`render` 里 `{event.value.question}` / `{event.value}` 直出 —— 即 **legacy 流的 `event.value` 可能只是个字符串**，前端不能假定它是结构体 | 与本件既有结论同向（D2 逐字：LangGraph 桥会把 interrupt value **JSON 字符串化**，前端需按需 `JSON.parse`），并解释了为什么 `useInterrupt` 的 legacy 分支不做任何解析 |

**对本仓含义**：第 1、6、7 条各有直接施工价值 ——（1）本仓 `ConfirmGate` 的**服务端复核失败**对应 CopilotKit 的「handler reject → result=null」这一「静默降级」路径；CopilotKit 选择**降级为 `null` 并继续渲染**，而不是卡死或抛到 UI，本仓的 `REJECTED`（400 + 状态不变 + 可修正重试）比它更强，**不需要向它对齐**；（6）「过期不 resume」与我们「超时作废」同向，差别只在**谁判过期**（AG-UI 客户端据 `expiresAt` 自判，本仓服务端定时器判）；（7）「legacy 无 cancel」提醒我们：**若走 legacy 式单事件通道，就没有「取消」这个语义位**，本仓的 `FRONTEND_CANCELLED` 是属于标准流的能力，别在退化实现里丢掉。

### 2.5 **跨框架分歧（本次核验新发现）：React / React Native 全量，Vue 只有 legacy**

这条对**本项目（Vue 3 前端）**有直接决策价值，故单列。

| 维度 | React（D9） | React Native（D11） | **Vue（D10）** |
|---|---|---|---|
| 标准 AG-UI 流（`RUN_FINISHED` + `outcome.interrupts`） | ✅ 支持 | ✅ 支持（D11 自述：*"Re-exported from `@copilotkit/react-core/v2`. It is identical to the React (V2) `useInterrupt`; only the import path differs."*） | ❌ **不支持** |
| `interrupts[]`（多中断） | ✅ | ✅ | ❌ 无此字段 |
| `cancel()` / `{status:"cancelled"}` | ✅ | ✅ | ❌ **无 `cancel`** |
| `expiresAt` 过期判定 | ✅ | ✅ | ❌ 未提 |
| 恢复机制 | `runAgent({resume:[…]})` | 同 React | **`forwardedProps.command.resume = response`**（D10 逐字：*"Resumes the agent, forwarding `command.resume = response` (along with the originating `interruptEvent`)"*） |
| 暂停感知来源 | `RUN_FINISHED` 或 `on_interrupt` | 同 React | **仅 `on_interrupt` 自定义事件** |
| API 形状 | `render` 渲染回调 | 同 React | **返回响应式状态**（`interrupt` / `result` / `hasInterrupt` / `slotProps`）+ `resolveInterrupt`，经 `<CopilotChat>` 的 `#interrupt` **slot** 渲染 |

D10（Vue）Overview 逐字，是最关键的一句：

> `useInterrupt` is a Vue 3 composable that **listens for agent custom events named `on_interrupt`**, captures the latest interrupt payload for a run, and surfaces it once the run finalizes. Your UI calls `resolveInterrupt(response)` to resume the agent with a resume payload.

D10 的 Behavior 全列（逐字摘要）只有四条：interrupts 来自 `on_interrupt`；`onRunFinalized` 才浮现；`onRunStartedEvent` 清场、`onRunFailed` 丢弃；`resolveInterrupt` 走 `command.resume` —— **通篇没有 `RUN_FINISHED`、`outcome`、`interrupts[]`、`cancel`、`expiresAt`**。

D10 另有一条比 React 更详细的 `result` 边界（逐字）：

> `result` … or `null` when there is no handler, the handler returned a rejected promise, or `enabled` filtered the interrupt out. (A **synchronous `throw` in the handler is not caught and leaves `result` unchanged**.)

**对本仓含义（两条，都很实）**：
1. **不要把「CopilotKit 的 Vue 版」当成可对照的实现**。它是**仅有 legacy 通道的退化实现**：没有多中断、没有取消、没有 `expiresAt`。本项目若参考 CopilotKit，能对照的是**React 的契约（D9）与 AG-UI 协议（D5）**，Vue 侧只宜作为「同一契约在弱实现下的最小可行面」参照——恰好也说明：**标准 interrupt 流的能力（`interrupts[]` / `cancel` / `expiresAt`）需要自己建，Vue 生态里没有现成的**。
2. **`slotProps` 的设计值得抄一处细节**：Vue 版把中断 UI 做成 `<CopilotChat>` 的具名 slot（`#interrupt`）并暴露 `{ event, result, resolve }`，与 React 的 `setInterruptElement`（§2.4）、v1 react-ui 的 `{interrupt}` 节点（§1.2）**三处同构**——即 CopilotKit 在三种前端里都把「中断渲染」收敛为**一个可注入的 UI 槽位**，而不是让调用方自己在消息流里找位置。这支持本件 §5.3 ② 的建议：**本仓挂起态应是一个独立可注入的槽位 + 状态机，而不是散在组件内的条件分支**。

---

## 三、完整数据流（源码逐步取证）

### 3.0 两条链的时序总览

```
链 A（useHumanInTheLoop，前端工具悬挂）
  Agent ──TOOL_CALL_START / ARGS / END (无 RESULT)──▶ 前端
        前端 handler 返回悬挂 Promise ──▶ 卡挂起（status=executing）
  用户 respond(v) ──▶ Promise 以 v resolve ──▶ 写 {role:"tool",toolCallId,content:JSON(v)} 进消息历史
                ──▶ needsFollowUp=true ──▶ 自动发起下一轮 run（同一 thread，新 runId）
  Agent ──继续推理，读到的就是这条 tool 消息──▶ TOOL_CALL_RESULT / 文本 / RUN_FINISHED

链 B（useInterrupt，AG-UI 标准中断）
  Agent ──…ToolCall*/StateSnapshot/MessagesSnapshot…──▶ 前端
        ──RUN_FINISHED {outcome:{type:"interrupt",interrupts:[…]}}──▶ 前端（run 终结）
  前端 ɵInterruptState 逐 id 累积响应，全员到齐 ──▶ runAgent({agent, resume:[{interruptId,status,payload}]})
        ──▶ 同一 thread、新 runId 的新一轮 run
  Agent ──ToolCallResult（对原 toolCallId）/ 文本──▶ RUN_FINISHED {outcome:{type:"success"}}
```

### 3.1 逐跳 A-1：前端工具被调用 → handler 悬挂

`use-human-in-the-loop.tsx:32-60` 全文（这是「暂停」的**物理实现**）：

```ts
  const handler = useCallback(
    async (_args: T, context?: { signal?: AbortSignal }) => {
      const signal = context?.signal;
      return new Promise((resolve, reject) => {
        // If the run was already aborted before the handler ran, reject
        // immediately so core records an explicit error tool result instead of
        // silently resolving to an empty string.
        if (signal?.aborted) {
          reject(new Error("Human-in-the-loop interaction aborted"));
          return;
        }

        resolvePromiseRef.current = resolve;

        if (signal) {
          const onAbort = () => {
            cleanupAbortRef.current = null;
            resolvePromiseRef.current = null;
            reject(new Error("Human-in-the-loop interaction aborted"));
          };
          signal.addEventListener("abort", onAbort, { once: true });
          cleanupAbortRef.current = () => {
            signal.removeEventListener("abort", onAbort);
          };
        }
      });
    },
    [],
  );
```

`respond` 就是那个 resolve（:23-30）：

```ts
  const respond = useCallback(async (result: unknown) => {
    if (resolvePromiseRef.current) {
      cleanupAbortRef.current?.();
      cleanupAbortRef.current = null;
      resolvePromiseRef.current(result);
      resolvePromiseRef.current = null;
    }
  }, []);
```

**注**：`respond` 幂等靠 `resolvePromiseRef` 置空实现——第二次调用静默无操作（**不报错**）。官方参考页说 "should only be called once per tool invocation"，但**没有**运行时守卫来提示重复调用。

### 3.2 逐跳 A-2：Core 调用 handler 并把返回值变成 tool 消息

`packages/core/src/core/run-handler.ts:1186-1193`：

```ts
        const result = await tool.handler!(parsedArgs as any, {
          toolCall: toolCall as any,
          agent,
          signal,
          ...(isReplay ? { isReplay } : {}),
        });
        ({ content: toolCallResult, text: toolCallText } =
          toToolResultContent(result));
```

**注意 `isReplay`**（回放标记，见 §4）也被传进 handler context。

`run-handler.ts:1310-1319`（把结果写成 tool 消息，并决定是否续跑）：

```ts
      const toolMessage = {
        id: randomUUID(),
        role: "tool" as const,
        toolCallId: toolCall.id,
        content: handlerResult.content,
      };
      agent.messages.splice(insertAt, 0, toolMessage);

      if (!handlerResult.error && tool?.followUp !== false) {
        return true; // Needs follow-up
      }
```

插入点 `insertAt` 的计算（:1298-1304 段）有一条对**多工具批**很重要的注释：

```ts
      // Find the correct insertion point: after the parent assistant message
      // and any tool-result messages already inserted for earlier tool calls
      // in the same batch. This preserves tool-result ordering relative to
      // the toolCalls array, which some providers (OpenAI) require.
```

### 3.3 逐跳 A-3：续跑（新 run，同一 thread）

`run-handler.ts:1073-1094`：

```ts
    if (needsFollowUp && agent.threadId === threadId && !signal?.aborted) {
      // Circuit breaker: bail out instead of recursing once the follow-up
      // chain exceeds an absolute safety cap. …
      if (this._runDepth >= MAX_FOLLOW_UP_DEPTH) {
        logger.warn(…);
      } else {
        // Yield to the framework scheduler before the follow-up run so that any
        // deferred state updates (e.g. React useEffect in useAgentContext) can
        // complete and write fresh values into the context store before runAgent
        // reads it. The base implementation is a no-op; React overrides this.
        await this._internal.waitForPendingFrameworkUpdates();
```

`MAX_FOLLOW_UP_DEPTH` 是一个**递归续跑断路器**——HITL 工具被反复调用即命中，防「模型反复问同一个问题」烧配额（对「带反馈重试」这一类目，这是必须复制的护栏）。

### 3.4 逐跳 B-1：agent 侧 `interrupt()` 的两种产出形态

**形态①（后端 BuiltIn agent 的工具审批）**：`packages/runtime/src/agent/index.ts:1991-1998`

```ts
                if (state.toolName && interruptToolNames.has(state.toolName)) {
                  const meta = interruptToolMeta.get(state.toolName);
                  pendingInterrupts.push({
                    id: toolCallId,
                    toolCallId,
                    reason: meta?.reason ?? "tool_call",
                    ...(meta?.message ? { message: meta.message } : {}),
                  });
                }
```

**逐字结论：工具型中断的 `interrupt.id === toolCallId`**，`reason` 默认 `"tool_call"`。这条恒等式贯穿全链（前端回传、服务端 resume 注入都对它做匹配，见 §3.5、§3.6）。

**形态②（factory 的显式 `ctx.interrupt()`）**：`runtime/src/agent/index.ts:2303-2316`

```ts
        interrupt: async (interrupts: Interrupt[]) => {
          const resume = input.resume ?? [];
          const ids = new Set(interrupts.map((i) => i.id));
          const matching = resume.filter((r) => ids.has(r.interruptId));
          // All requested interrupts addressed → resume: return responses.
          if (interrupts.length > 0 && matching.length === interrupts.length) {
            return matching;
          }
          // Fresh run (or not yet addressed) → pause.
          throw new InterruptSignal(interrupts);
        },
```

`InterruptSignal`（`:503-512`）逐字注释：

```ts
/**
 * Thrown by `AgentFactoryContext.interrupt()` on a fresh (non-resume) run to
 * pause the factory. Caught in `runFactory` and translated into a RUN_FINISHED
 * event carrying `outcome:{type:"interrupt",interrupts}`. Not a real error.
 */
export class InterruptSignal extends Error {
```

**这是「暂停」在服务端的实现**：用一个**异常**把控制流从 factory 内部抛出，靠最外层 catch 把它变成正常的 `RUN_FINISHED`。**它同时是恢复机制的一半**：resume run 上同一个 `interrupt()` 调用点**不再抛**，而是 `return matching` ——即 `interrupt()` 的返回值就是 resume 载荷。这正是 LangGraph `interrupt()` 的语义（*"execution resumes as if the interrupt call had simply returned that payload"*，官方 `useInterrupt` 页逐字）。

### 3.5 逐跳 B-2：线上报文（`RUN_FINISHED` + `interrupts`）

出帧点 `runtime/src/agent/index.ts:2127-2137` 与 `:2500-2512`（后者是 `InterruptSignal` 被捕获处）：

```ts
          if (error instanceof InterruptSignal) {
            const finishedEvent = {
              type: EventType.RUN_FINISHED,
              threadId: input.threadId,
              runId: input.runId,
              ...runFinishedDetails,
              outcome: { type: "interrupt", interrupts: error.interrupts },
            } as RunFinishedEvent;
            subscriber.next(finishedEvent);
            subscriber.complete();
```

**类型权威口径**来自 `@ag-ui/core@1.0.2` 发布产物的 `dist/version-Dr0GiU8W.d.mts`（三个独立段落）：

`:1645` `Interrupt`（**注意每字段都带设计意图注释，逐字摘要**）：

```ts
type Interrupt = {
  subagentRunId?: SubagentRunId;
  id: string;
  reason: string;          // "An open string rather than an enumeration"
  message?: string;        // "A human-readable prompt for whoever answers."
  toolCallId?: string;     // "The tool call this interrupt concerns, when it is a tool approval."
  responseSchema?: Record<string, any>;   // "Carried opaquely: the protocol does not constrain or validate it."
  expiresAt?: string;      // "Deliberately unconstrained rather than a date-time format …"
  metadata?: Metadata;     // "Extra information attached to this interrupt."
};
```

`:1699` `RunFinishedInterruptOutcome`：

```ts
type RunFinishedInterruptOutcome = {
  type: "interrupt";
  /** What the run is waiting for. At least one: an interrupt outcome with
   *  nothing to answer would leave a consumer with nothing to do. */
  interrupts: Interrupt[];
};
```

`:1728`：

```ts
type RunFinishedOutcome = RunFinishedSuccessOutcome | RunFinishedInterruptOutcome | RunFinishedCancelledOutcome;
```

**比文档多出来的一个分支**：`RunFinishedCancelledOutcome = { type: "cancelled" }`（`:1719`，注释逐字 *"The run was stopped before it completed, by whoever was running it, and did not fail."*）——**AG-UI 官方文档 `docs.ag-ui.com/concepts/interrupts` 的 `RunFinishedOutcome` 联合里没有它**（该页写的是 `| { type: "success" } | { type: "interrupt"; interrupts: Interrupt[] }`）。

`docs.ag-ui.com` 的 contract rules 8 条中，对本仓最相关的两条逐字：

> 3. **Cover all open interrupts.** A single `resume` array must address every open interrupt from the interrupted run. Partial resumes are not supported.
> 4. **Pending interrupts block new input.** If a thread has unresolved interrupts, any `RunAgentInput` on that thread must include a `resume` addressing them. Agents receiving a non-conforming input must emit `RunError`.

**文档与实现的一处不一致（如实登记；2026-10-17 已复核线上）**：AG-UI 官方 SDK 参考页 `https://docs.ag-ui.com/sdk/js/core/events` 里 `RunFinishedEvent` 的类型**没有 `outcome` 字段**，逐字只有：

```typescript
type RunFinishedEvent = BaseEvent & {
  type: EventType.RUN_FINISHED
  threadId: string
  runId: string
  result?: any
}
```

而同站 concept 页（D5）与 `@ag-ui/core@1.0.2` 的 dist 类型**都有** `outcome`。**以 concept 页 + 发布产物为准，SDK 参考页该节未同步。**（该页 `BaseEvent` 也只有 `type` / `timestamp?` / `rawEvent?`，比 `@ag-ui/core@1.0.2` dist 里的 `BaseEvent` 少一个 `metadata`——同一处的滞后，方向一致。）

### 3.6 逐跳 B-3：前端接收与「何时把中断提到 React state」

`packages/react-core/src/v2/hooks/use-interrupt.tsx:185-231` 全文——这是整条链的**状态机核心**，四处回调各有分工：

```ts
  useEffect(() => {
    const interruptState = interruptStateRef.current;
    let localLegacy: InterruptEvent | null = null;
    let localStandard: Interrupt[] | null = null;

    const subscription = agent.subscribe({
      onCustomEvent: ({ event }) => {
        if (event.name === INTERRUPT_EVENT_NAME) {
          localLegacy = { name: event.name, value: event.value };
        }
      },
      onRunFinishedEvent: (params) => {
        if (params.outcome === "interrupt") {
          localStandard = params.interrupts;
        }
      },
      onRunStartedEvent: () => {
        localLegacy = null;
        localStandard = null;
        interruptState.clear();
        setPending(null);
      },
      onRunFinalized: () => {
        // Standard wins if both somehow appear for one run.
        if (localStandard && localStandard.length > 0) {
          interruptState.setStandard(localStandard);
          setPending(interruptState.pending);
        } else if (localLegacy) {
          interruptState.setLegacy(localLegacy);
          setPending(interruptState.pending);
        }
        localLegacy = null;
        localStandard = null;
      },
      onRunFailed: () => {
        localLegacy = null;
        localStandard = null;
        interruptState.clear();
        setPending(null);
      },
    });

    return () => {
      subscription.unsubscribe();
      interruptState.clear();
    };
  }, [agent]);
```

四条纪律（可迁移到任何「事件流 → 挂起 UI」实现）：

1. **不在事件流中途浮现**：`onRunFinishedEvent` **只暂存到局部变量**，真正的 `setPending` 发生在 `onRunFinalized`（流终结后）。
2. **标准优先**：`localStandard` 与 `localLegacy` 同时出现时 standard wins（源码注释 `// Standard wins if both somehow appear for one run.`）。
3. **新一轮清场**：`onRunStartedEvent` 清 stale pending，保证新 run 从干净态起步。
4. **失败丢弃**：`onRunFailed` 清暂存，避免传输抖动把 UI 卡成「永远显示选择器」。

`INTERRUPT_EVENT_NAME` 的定义在 `packages/react-core/src/v2/types/interrupt.ts:6`：

```ts
/** Name of the legacy custom event agents emit to signal an interrupt. */
export const INTERRUPT_EVENT_NAME = "on_interrupt";
```

**另一条独立证据**：AG-UI 官方页 `docs.ag-ui.com/concepts/interrupts.md` 的 Framework integrations 表，LangGraph 一行逐字：

> ✅ Accepts `RunAgentInput.resume[]`. Can emit `RunFinishedEvent.outcome = {type:"interrupt"}` — **opt-in via `emitInterruptOutcome` / `emit_interrupt_outcome` (default off;** legacy clients that resume via `command.resume` stop resuming once they see the structured outcome). **Legacy `CustomEvent(name="on_interrupt")` emitted by default**; disable via `enableLegacyOnInterruptEvent: false`.

**这解释了为什么前端必须同时支持两条流**：LangGraph 桥**默认发 legacy 自定义事件**，标准 outcome 是**可选开关**。本仓若自建 agent 桥，可以直接只走标准流，省掉一半兼容面。

### 3.7 逐跳 B-4：累积 → 齐备 → 发 `resume`

`packages/core/src/interrupt-state.ts:123-158`（`ɵInterruptState.#respond`）—— **多中断并发与「全齐才放行」的实现**：

```ts
  #respond(
    interrupts: readonly Interrupt[],
    interruptId: string | undefined,
    response: ResumeResponse,
  ): ɵInterruptDecision {
    const id = interruptId ?? interrupts[0]?.id;
    if (!id || !interrupts.some((interrupt) => interrupt.id === id)) {
      return { kind: "ignored" };
    }
    this.#responses[id] = response;
    if (!interrupts.every((interrupt) => this.#responses[interrupt.id])) {
      return { kind: "waiting" };
    }

    const expired = interrupts.find((interrupt) =>
      isInterruptExpired(interrupt),
    );
    if (expired) {
      this.#sealed = true;
      return { kind: "expired", interrupt: expired };
    }

    const mutableInterrupts = [...interrupts];
    const resume = buildResumeArray(mutableInterrupts, this.#responses);
    const toolResults = mutableInterrupts.flatMap((interrupt) => {
      if (!interrupt.toolCallId || interrupt.reason !== "tool_call") return [];
      return [
        {
          toolCallId: interrupt.toolCallId,
          content: toolResultContent(this.#responses[interrupt.id]!),
        },
      ];
    });
    this.#sealed = true;
    return { kind: "resume", resume, toolResults };
  }
```

`toolResultContent`（:45-50）—— **拒绝/取消如何落成模型可读文本**：

```ts
function toolResultContent(response: ResumeResponse): string {
  if (response.status === "cancelled") {
    return JSON.stringify({ status: "cancelled" });
  }
  return JSON.stringify(response.payload ?? { status: "resolved" });
}
```

`ɵInterruptDecision` 的六个分支（:26-43）：`ignored` / `waiting` / `dismiss` / `expired` / `legacy-resume` / `resume`。`#sealed` 标志保证**一套中断只能被消费一次**（重复 resolve 返回 `ignored`）。

调用侧 `use-interrupt.tsx:276-298`（把 `toolResults` 补成消息、再发 resume run）：

```ts
      if (decision.kind !== "resume") return;
      for (const toolResult of decision.toolResults) {
        agent.addMessage({
          id: randomUUID(),
          role: "tool",
          toolCallId: toolResult.toolCallId,
          content: toolResult.content,
        } as Message);
      }
      try {
        return await copilotkit.runAgent({
          agent,
          resume: decision.resume,
        });
      } catch (err) {
        console.error(
          "[CopilotKit] useInterrupt resolve: runAgent rejected; clearing pending + rethrowing",
          err,
        );
        interruptStateRef.current.clear();
        setPending(null);
        throw err;
      }
```

`legacy` 分支走另一条路（:248-267）：`forwardedProps.command = { resume: payload, interruptEvent: value }`。

**为什么前端要自己补 tool 消息**？测试文件 `packages/react-core/src/v2/hooks/__tests__/use-interrupt.test.tsx:1028-1031` 的注释逐字给出原因：

```ts
      // A tool-backed interrupt (carries toolCallId). Resolving it must record
      // the result as a tool message in the thread — otherwise the next turn
      // ships a dangling tool call and the model loops on tool-calls.
```

而断言（同文件 `:1056-1070`）逐字给出**回传报文的形状**：

```ts
      expect(mockAgent.addMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          role: "tool",
          toolCallId: "tc-1",
          content: JSON.stringify({ approved: true }),
        }),
      );
      expect(runAgentMock).toHaveBeenCalledWith({
        agent: mockAgent,
        resume: [
          {
            interruptId: "int-1",
            status: "resolved",
            payload: { approved: true },
          },
        ],
      });
```

同文件另有 `cancel()` 的对照断言：

```ts
      expect(runAgentMock).toHaveBeenCalledWith({
        agent: mockAgent,
        resume: [{ interruptId: "int-1", status: "cancelled" }],
      });
```

**注意**：`cancelled` 时**不带 `payload`**。这与 `buildResumeArray` 的实现一致（`@ag-ui/client@1.0.2` 的 `dist/index.mjs` 偏移 `57139`）：

```js
function bt(e,t){let n=new Set(e.map(e=>e.id)),r=new Set(Object.keys(t)),i=[...n].filter(e=>!r.has(e));
if(i.length>0)throw Error(`buildResumeArray: missing responses for open interrupts: ${i.join(", ")}`);
let a=[...r].filter(e=>!n.has(e));
if(a.length>0)throw Error(`buildResumeArray: responses reference unknown interrupt ids: ${a.join(", ")}`);
return e.map(e=>{let n=t[e.id],r={interruptId:e.id,status:n.status};
return n.status==="resolved"&&n.payload!==void 0&&(r.payload=n.payload),
n.metadata!==void 0&&(r.metadata=n.metadata),r})}
```

`buildResumeArray` 做**双向集合相等校验**：缺响应报错、多响应（未知 id）报错。**它只调用方保证不变量，不代做协议合规**。

### 3.8 逐跳 B-5：`resume` 出网与 agent 恢复

前端把 `resume` 塞进 `RunAgentInput`：`packages/core/src/core/run-handler.ts:813-824`

```ts
      const agentRunInput = {
        forwardedProps: {
          ...this._internal.properties,
          ...forwardedProps,
        },
        ...(resume !== undefined ? { resume } : {}),
        ...(pinRunIdOnWire ? { runId } : {}),
        tools: this.buildFrontendTools(agent.agentId),
        context: this._internal.getContextForAgent(agent.agentId),
      };
      const runAgentResult = await agent.runAgent(
        agentRunInput,
        agentSubscriber,
      );
```

服务端**恢复执行**有两条路：

**路径 A（aisdk / tanstack：把 resume 注入成 tool 消息）** —— `runtime/src/agent/index.ts:1392-1440`，注释逐字：

```ts
      // Resume injection: each ResumeEntry maps to the interrupt tool call it
      // addresses (interruptId === toolCallId) and is appended as that call's
      // tool-role result so the model can continue the agentic loop.
      const resumeEntries: ResumeEntry[] = input.resume ?? [];
      if (resumeEntries.length > 0) {
```

```ts
        for (const entry of resumeEntries) {
          if (alreadyAnswered.has(entry.interruptId)) continue;
          const value =
            entry.status === "cancelled"
              ? { status: "cancelled" }
              : (entry.payload ?? { status: "resolved" });
          const toolResultMessage: ToolModelMessage = {
            role: "tool",
            content: [
              {
                type: "tool-result",
                toolCallId: entry.interruptId,
                toolName: toolNameById.get(entry.interruptId) ?? "",
                output: { type: "json", value },
              },
            ],
          };
          messages.push(toolResultMessage);
        }
```

**三个可迁移的工程细节**：① `interruptId === toolCallId` 的恒等式让 resume 能挂回原 tool call；② **幂等**——`alreadyAnswered` 跳过客户端已经写进历史的 tool 消息，避免同一个 tool call 被答复两次；③ **必须补 `toolName`**，注释逐字：*"Providers like Anthropic and Google reject a tool-result whose name doesn't match the prior tool-call; an empty name fails there (OpenAI tolerates it)."*

**路径 B（custom factory：自己读 `input.resume`）**——回到 §3.4 的 `ctx.interrupt()`，`return matching`。

### 3.9 另一条 B 侧实现：`ɵInterruptState` 是框架中立类

`interrupt-state.ts:52-56` 逐字：

```ts
/**
 * @internal Framework-neutral interrupt response state shared by framework
 * adapters. Application authors must not depend on this API.
 */
export class ɵInterruptState<TValue = unknown> {
```

并且**每个导出都标了 `@internal` / "Application authors must not depend on this contract"**。**对本仓含义**：这是「同一套中断累积状态机供多框架复用」的先例——本仓 `ConfirmGate` 承担的角色与之同构（都是「决策累积 + 齐备放行 + 过期判定」），但 `ConfirmGate` 是 `@Component` 公共类。**若本仓未来接第二种前端（如移动端），应把这层抽成内部类**。

### 3.10 逐跳 B-6：多 interrupt 并发处理的边界

- **并发是支持的**：`interrupts: Interrupt[]` 非空数组，`resolve(payload, interruptId)` 逐个答复，全员到齐才发一次 `resume`（§3.7）。
- **省略 `interruptId` 有运行时告警**（`use-interrupt.tsx:238-246`）：

```ts
      if (
        current.kind === "standard" &&
        current.interrupts.length > 1 &&
        interruptId === undefined
      ) {
        console.warn(
          `[CopilotKit] useInterrupt: resolve()/cancel() called without an interruptId while ${current.interrupts.length} interrupts are open; defaulting to the first. Pass an interruptId to address a specific interrupt.`,
        );
      }
```

- **`handler` 与 `render` 的 enabled 谓词按「主中断」判定**：`toLegacyEvent`（:72-75）把 standard 中断映射成 `{name:"on_interrupt", value: interrupts[0]}`，`enabled`/`handler` 收到的都是这个 legacy 形状——**多中断场景下逐条过滤要以 `interrupts` 数组自己遍历，`enabled` 只能按首条裁定**。
- **协议侧硬约束**：`buildResumeArray` 双向校验 + `docs.ag-ui.com` rule 3（Partial resumes are not supported）。所以「一次只批一个、其余保持挂起」在前端是允许的（`waiting`），但**出网时必须是齐备的一批**。

### 3.11 状态同步（CoAgent state / shared state）

AG-UI 官方 `concepts/interrupts.md` 有一节 **"State at the interrupt boundary"**，逐字：

> At the moment of interrupt, the agent must emit any state required for resume via `StateSnapshot` and `MessagesSnapshot` events **before** the `RunFinished` event that carries the interrupt.
> This rule makes the protocol resume-mode-agnostic: both replay-style continuations (rebuild context from messages + state) and checkpoint-style continuations (restore a suspended coroutine) must produce identical observable behavior on resume. **Framework-native checkpointing is an implementation optimization, not a protocol contract.**

事件名（`https://docs.ag-ui.com/sdk/js/core/events` / `@ag-ui/core@1.0.2`）：`STATE_SNAPSHOT`（全域快照 `snapshot`）、`STATE_DELTA`（**JSON Patch RFC 6902** 数组 `delta`）、`MESSAGES_SNAPSHOT`（`messages: Message[]`）、另有 `ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA`、`RAW`、`CUSTOM`。

**对本仓含义（重要）**：AG-UI 把「中断边界先补快照」定为**协议义务**，而不是实现选择。本仓当前在挂起期靠**定时心跳帧** + 外置 Redis 状态（`ConfirmGate` 注释：「决策状态一律先写 Redis…进程内的 Latch 只承担唤醒职责」）保证可恢复，**没有显式的「挂起前补发快照」帧**。两条路线都成立，但 AG-UI 的写法在「换设备/换标签页恢复」上更自洽（见 §4.5）。

---

## 四、持久化与恢复（刷新 / 重连）

### 4.1 结论先说：**靠「重放事件 + 只重跑 HITL handler」重建，没有前端持久化**

CopilotKit **没有任何 localStorage / sessionStorage 式的前端挂起态持久化**。刷新后卡面在，是因为：

1. `CopilotChat` 在挂载/换 thread 时调 `copilotkit.connectAgent({agent})`（`packages/react-core/src/v2/components/chat/CopilotChat.tsx:567`、`:707`）；
2. 该调用打到 runtime 的 `POST /agent/:agentId/connect`（`endpoints/express.ts:120`）；
3. runtime 的 runner **重放该 thread 的历史事件**（见 §4.3）；
4. core 收到重放后**以 `restore` 模式**跑一次工具处理，**只重跑 HITL handler**（见 §4.2）——于是卡面重新出现。

### 4.2 前端：`restore` 模式只重跑 HITL，绝不重放其他副作用

`run-handler.ts:935-946` —— **这是整个恢复机制最重要的一段**：

```ts
          const executableTool = tool ?? getWildcardTool();
          // Restoring history runs only handlers that are safe to run again:
          // human-in-the-loop prompts, and tools that opt in with
          // reconnectBehavior "resume-pending" (#6101). Every other tool stays
          // passive, so a reload never repeats its side effects.
          if (
            toolExecutionMode === "restore" &&
            (!executableTool?.handler ||
              (executableTool.type !== "human-in-the-loop" &&
                executableTool.reconnectBehavior !== "resume-pending"))
          ) {
            continue;
          }
```

`restore` 的调用点在 `connectAgent` 流程里（`run-handler.ts:624-636`）：

```ts
      const controller = new AbortController();
      const replay = this._interactionAbortControllers.get(agent) ?? {
        threadId: incomingThreadId,
        controllers: new Set<AbortController>(),
      };
      replay.controllers.add(controller);
      this._interactionAbortControllers.set(agent, replay);
      void this.processAgentResult({
        runAgentResult,
        toolExecutionMode: "restore",
        signal: controller.signal,
      })
```

同处上方还有一句关键注释（:616-618）：

```ts
      // Connecting ends when history is restored, not when a human answers.
      // Keeping this promise open would block run-activity reconnects while
      // another client answers the same interaction.
```

`processAgentResult` 的签名默认值是 `"all"`（:867-873）：`toolExecutionMode = "all"`，`toolExecutionMode?: "all" | "restore"`。

**`reconnectBehavior` 的完整语义**（`packages/core/src/types.ts:51-70`，逐字，**这是恢复机制的设计文档**）：

```ts
/**
 * What a frontend tool does with its own calls that are still unanswered when
 * a thread's history is restored (a reload, a remount, or a thread switch).
 *
 * - `"passive"` (default) — show the call, but do not run the handler. Nothing
 *   the handler did runs twice.
 * - `"resume-pending"` — run the handler for each call that has no result,
 *   with `isReplay: true` in its context, then continue the run as usual
 *   (follow-up included, unless `followUp` is false).
 *
 * Use `"resume-pending"` only for a handler that is safe to run more than
 * once. A call does not start twice at the same time in one CopilotKit
 * instance, but it can run again after a stop or a thread switch if its result
 * never reached the agent, and two tabs or devices that restore the same
 * thread can each run it.
 *
 * If one assistant message also calls a tool that stays passive, the follow-up
 * run sends that call without a result, which some model providers reject.
 * Opt in every tool the agent calls together, or set `followUp: false`.
 */
export type FrontendToolReconnectBehavior = "passive" | "resume-pending";
```

**并诚实登记了多端竞态**（逐字）：「**two tabs or devices that restore the same thread can each run it**」——即 CopilotKit **不解决**多端同时恢复同一 thread 的重复执行问题，只把它写进文档警告。

### 4.3 服务端：`connect` 重放「该 thread 的全部历史事件」

`packages/runtime/src/v2/runtime/runner/in-memory.ts:886-933`：

```ts
  connect(request: AgentRunnerConnectRequest): Observable<BaseEvent> {
    const store = sharedStore.get(request.threadId, { touch: true });
    const connectionSubject = new ReplaySubject<BaseEvent>(Infinity);

    if (!store) {
      // No store means no events
      connectionSubject.complete();
      return connectionSubject.asObservable();
    }

    // Collect all historic events from memory
    const allHistoricEvents: BaseEvent[] = [];
    for (const run of store.historicRuns) {
      allHistoricEvents.push(...run.events);
    }

    // Apply compaction to all historic events together (like SQLite)
    const compactedEvents = compactEvents(allHistoricEvents);

    // Emit compacted events and track message IDs
    const emittedMessageIds = new Set<string>();
    for (const event of compactedEvents) {
      connectionSubject.next(event);
      if ("messageId" in event && typeof event.messageId === "string") {
        emittedMessageIds.add(event.messageId);
      }
    }

    // Bridge active run to connection if exists
    if (store.subject && (store.isRunning || store.stopRequested)) {
      store.subject.subscribe({
        next: (event) => {
          // Skip message events that we've already emitted from historic
          …
```

**三个要点**：① 重放单元是**事件**（不是消息快照），经 `compactEvents` 压实；② 通过 `emittedMessageIds` 去重，**历史 + 在飞不重发**；③ 若该 thread 正在跑，`connect` 会**桥接在飞流**（这就是「另一个客户端在答，本客户端能实时看到」的机制）。

**链 B 的恢复因此是自洽的**：被中断的那个 run 的最后一帧就是 `RUN_FINISHED{outcome:interrupt}`，重放它 → `useInterrupt` 的 `onRunFinishedEvent` 重新拿到 `interrupts` → `onRunFinalized` 提交 → 卡面重现。

**链 A 的恢复**：重放给出「有 `TOOL_CALL_*` 但无对应 `TOOL_CALL_RESULT`」的 assistant 消息 → `restore` 模式对该 tool call 重跑 HITL handler → 新的悬挂 Promise 就位 → 卡面重现。

### 4.4 内存态的代价：`pendingInterrupts` 与它的两条硬规则

`@ag-ui/client@1.0.2` 在 `AbstractAgent` 上存了一个**运行态**字段（`dist/index.d.mts:545` 区域）：

```ts
  /** Interrupts emitted by the most recent run that have not yet been resolved.
   *  Populated when RUN_FINISHED arrives with outcome.type === "interrupt".
   *  Cleared when a subsequent run completes successfully. */
  pendingInterrupts: Interrupt[];
```

填充点在 `dist/index.mjs` 偏移 `13807`（`RUN_FINISHED` 分支）：

```js
pendingInterrupts=a.outcome==="interrupt"?a.interrupts.map(e=>{if(e.subagentRunId!==null)return e;let t={...e};return delete t.subagentRunId,t}):[]
```

`onInitialize` 里的**两条硬规则**（`dist/index.mjs` 偏移 `69142`）：

```js
if(Vt.has(e)&&this.pendingInterrupts.length>0){
  let t=new Set((e.resume??[]).map(e=>e.interruptId)),
      r=this.pendingInterrupts.map(e=>e.id).filter(e=>!t.has(e));
  if(r.length>0)throw new n(`Thread has ${r.length} pending interrupt(s) not addressed by resume: ${r.join(", ")}`);
  for(let t of this.pendingInterrupts)
    if(yt(t)&&(e.resume??[]).find(e=>e.interruptId===t.id)?.status!=="cancelled")
      throw new n(`Interrupt ${t.id} expired at ${t.expiresAt} and can no longer be answered. Cancel it to continue the thread.`)
}
```

配套的两个纯函数（同文件偏移 `57025` 与 `57057`）：

```js
function vt(e){return e.outcome}
function yt(e,t=new Date){return e.expiresAt===void 0?!1:new Date(e.expiresAt)<=t}
```

**四条可迁移结论**：
1. **有挂起时不许发无 `resume` 的新输入**（否则抛错）——把协议 rule 4 做成了**客户端运行时守卫**，而不是只写在文档里。
2. **过期中断的出口只有 `cancelled`**：报错文案逐字 *"Cancel it to continue the thread."* ——**超时后想继续轮次，只能显式取消**。
3. `isInterruptExpired` 对**无 `expiresAt` 恒返回 `false`**（`e.expiresAt===void 0?!1:…`），即不设 TTL 就永不过期。
4. `pendingInterrupts` 清空时机是「后续 run 成功完成」——**不成功就不清**。

### 4.5 换设备 / 多标签页

官方页 `docs.ag-ui.com/concepts/interrupts.md` 逐字：

> Splitting the interaction across two runs is what makes the pause durable: the interrupted run has already checkpointed its state, so the decision can arrive seconds or days later, from the same device or a different one, and the audit trail records the proposal, the human's response, and the executed result as separate entries.

但因 §4.4 的 `pendingInterrupts` 是**实例内存**，跨设备恢复实际靠**服务端重放 + 该实例重新收 `RUN_FINISHED`**；而 §4.2 的注释明说**两标签页各自会重跑 HITL handler**。**这是 CopilotKit 恢复机制的真实能力边界，不夸大为「多端协同」。**

---

## 五、对本仓的映射（五类打断 × 落地面）

### 5.1 先校准一处来源：五类打断是一手 CopilotKit 口径

本仓既有件 `调研-HITL交互型谱-2026-10-16.md` 把五类打断归为「来源：CopilotKit（经 Kimi 分享会话转述）」。**本次升级为直接一手源**：`https://www.copilotkit.ai/learning/what-is-human-in-the-loop`（CopilotKit 官方学习站）逐字：

> ## The interrupt patterns
> Nearly every human-in-the-loop interaction is one of five patterns. Each one pauses the run, puts a decision in front of a person, and resumes with the response.
> - **Approve or reject.** The agent proposes a complete action and the human gates it. …
> - **Edit, then continue.** The human corrects the proposal before it executes. … The edit replaces the proposed arguments outright, so there is no ambiguity about what ran.
> - **Choose among options.** The agent presents alternatives instead of a single answer …
> - **Provide missing input.** The agent hits a fact only the human knows. … The run waits for a structured answer, then continues with it.
> - **Retry with guidance.** The human rejects and says why. … The agent regenerates with the correction applied, and the loop repeats until the proposal passes.

同页还把「界面必须做什么」压成三件事（逐字），可直接作为本仓卡面验收口径：

> **Render the interrupt.** … A real interrupt carries structure: the tool being called, its arguments, the options on offer, the schema of the expected response. …
> **Collect the response.** The response must be validated against what the agent expects and correlated with the right interrupt, because a run can raise more than one. …
> **Resume the run.** … it is a transport problem before it is a UI problem. … The connection between agent and interface must be bi-directional, the agent's state must be checkpointed at the pause, and the response must reach the paused run and continue it exactly where it stopped.

（本仓既有件的概念裁决「HITL ≠ 生成式表单」**不受本件影响**：五类打断里「修改后执行/方案选择/补充信息」在 CopilotKit 侧同样是 **`resume` 决策**，而不是表单提交；这与既有件「表单系独立成轴」的严格口径一致。）

### 5.2 五类打断 → CopilotKit 机制 → 本仓落地面

| # | 打断类型 | CopilotKit 的承载机制（协议级） | 本仓现状 | 本仓要做什么 |
|---|---|---|---|---|
| 1 | **批准/否决** | `interrupt.reason="tool_call"` + `responseSchema {approved:boolean}`；`resume:[{interruptId,status:"resolved",payload:{approved:false,reason}}]`。**否决在 payload 内表达，不是单独 status**（`docs.ag-ui.com` 逐字：*"Denials are expressed inside the payload (for example, `{ approved: false }`), not as a separate status."*） | ✅ 已有 `confirm_request` 帧 + `POST /api/ai/confirm {runId,toolCallId,approved,reason}` + `ConfirmGate` 三结局（放行/拒绝超时），**语义与 AG-UI 同向** | 把 `toolCallId` 明确为**中断关联键**（对应 `interruptId`）；在 `confirm_request` 帧里补 `reason:"tool_call"` 与 `message` 人话文案，使帧自解释 |
| 2 | **修改后执行** | `responseSchema` 里出现 `editedArgs` 即**能力信号**（AG-UI 逐字：*"`editedArgs` is a full replacement, not a partial merge. Its presence in the schema is the **capability signal** that the client may offer edit UI."*）；执行的是编辑后版本 | ❌ 无。`ConfirmRequest` 只有 `approved` + `reason`，**没有任何承载「编辑后入参」的字段** | 需要**新增字段**（如 `editedArgs` / `overrides`），并明确「全量替换而非合并」；`editedArgs` 一旦存在即视为对原入参的完整替换 |
| 3 | **方案选择** | `reason:"input_required"` + `responseSchema`（`enum`），走 `frontend_tool_request` 同型通道 | ⚠️ 现有 GF 表单可承载，但**没有「候选方案」的帧内结构化表达** | 在挂起帧里加 `options[]`（label/value）或 `responseSchema` 等价物；对应既有件「拟增单选/多选询问卡」 |
| 4 | **补充信息** | 同上，`reason:"input_required"`；`responseSchema` 驱动表单 | ✅ GF（FILTER/CLARIFY）+ `form` 帧 + `POST /api/ai/frontend-tool-result` | 保持不动；补 `responseSchema` 式的**机器可读期望形状**，让前端不再靠工具名硬编码表单 |
| 5 | **带反馈重试** | 否决后 payload 带 guidance；**重复调用由 `MAX_FOLLOW_UP_DEPTH` 断路器兜底**（`run-handler.ts:1081`）；另可挂 `handler` 预处理器提前放行 | ⚠️ 拒绝原因已回填（`reason`），但**无重试计数/断路器** | 加「同类询问重复次数上限」护栏，超限改走终态；这与既有件「三指标挂账」中的 `override rate` 配套 |

### 5.3 四项施工面：CopilotKit 怎么做的 → 本仓怎么落地

**① SSE 帧类型**

CopilotKit/AG-UI 用**两个既有事件名**表达中断，不新增事件类型：run 以 `RUN_FINISHED` 终止，中断内容全在 `outcome.interrupts[]`（§3.5）。
本仓现状：`confirm_request` / `frontend_tool_request` / `form` 等**专用帧**。
**建议**：不必推翻专用帧（它们信息量大、前端已在消费），但应**把「信封字段」对齐**——在挂起帧里补 `reason` / `message` / `responseSchema`(或 `options`) / `expiresAt` / `metadata`，使「新工具的中断」不需要新帧类型；同时给 `done` 帧加一个**可见的中断终态标记**（等价于 `outcome.type`），让「这一轮是挂起结束」与「正常结束」在前端可判别。**注意**：AG-UI 的 `RUN_FINISHED` 帧**不带走序号**，而本仓靠 `seq`（`?lastSeq=N` 差量补发）——**这是本仓的相对优势，应保留**。

**② 前端挂起态**

CopilotKit 有两条实现范式可抄：
- 链 A：**handler 返回悬挂 Promise**，`respond` 时才 resolve（§3.1）——本仓 `SpToolCallingManager` 阻塞等回灌**与之同构**。
- 链 B：**独立状态机对象 + 累积响应 + 齐备放行 + `sealed` 防重复消费**（§3.7）。
**建议**：本仓把「等待确认中」的挂起态从 `AiPanel.vue` 的组件内状态提升为**独立状态机**（对应 `ɵInterruptState`），并至少实现 `ignored / waiting / expired / resume / dismiss` 五个判定的等价物。理由是 §2.2 的 layout-effect 竞态教训：**挂起态一旦与渲染生命周期耦合，卸载重挂就会丢卡**。
**但要先做一次路线归属判断（§2.1 的推论）**：本仓确认门是**代码路径确定性**的闸门，按 CopilotKit 的二分法属**链 B 家族**，而现有实现形似链 A。**这不是必须改，但必须显式选定并写下理由**——若选链 A（保持现状），则 §5.4 的 P2「客户端无『有挂起时禁发新输入』守卫」与「恢复期只重跑幂等 handler」都要由本仓自建；若选链 B，则可直接对齐 `resume` 契约（`interruptId` / `status` / `payload`），代价是后端要产出 `outcome` 式的终止语义。

**③ 回传端点**

CopilotKit/AG-UI：`resume:[{interruptId, status:"resolved"|"cancelled", payload?, metadata?}]`，**同一 thread 新一轮 run**；`cancelled` **不带 payload**；全部挂起必须齐备（§3.5/§3.7）。
本仓现状：`POST /api/ai/confirm`、`POST /api/ai/frontend-tool-result`（后者已支持 `cancelled=true` → `FRONTEND_CANCELLED` 终态），另有 `POST /api/ai/runs/{runId}/resume`。
**建议**：三处对齐——(a) 用**统一的状态词** `resolved`/`cancelled` 取代现在分散的 `APPROVED/REJECTED/TIMEOUT/FRONTEND_CANCELLED`（对外契约层）；(b) `cancelled` 分支**不接受 payload**（与 §3.7 一致，避免语义含混）；(c) 若未来一次挂起多个待决（多工具批），**必须齐备才放行**，否则前端会出现「等一个、跑一个」的半批执行——本仓现走「逐 tool call 阻塞」，多挂起并发时需显式设计。

**④ 历史回读**

CopilotKit：`POST /agent/:id/connect` → 重放 thread 事件（`compactEvents` 压实 + `emittedMessageIds` 去重 + 在飞流桥接）→ 前端 `restore` 模式**只重跑 HITL handler**（§4.2/§4.3）。
本仓现状：`GET /api/ai/events/{runId}?lastSeq=N`（外置状态帧回放 + 实时续接）、`GET /api/ai/history/{sessionId}`、`GET /api/ai/pending/{runId}`、`GET /api/ai/startup-resume`。
**建议**：本仓的**外置挂起态（Redis）比 CopilotKit 的「实例内存 `pendingInterrupts`」更稳**——CopilotKit 的跨实例恢复实际要等重放把 `RUN_FINISHED` 再送一遍（§4.5 已如实登记其边界）。但本仓应补上 CopilotKit 有而我们没有的一条**纪律**：**恢复时只允许重跑「幂等安全」的挂起 handler**，其余一律 passive（§4.2）。本仓现有 `FrontendToolGuard.inspectResult` 是「提交期复核」，缺的是**「恢复期重跑白名单」**——否则刷新可能对同一 tool call 触发两次回灌等待。

### 5.4 差距清单（按优先级）

| 优先级 | 差距 | 依据 | 建议动作 |
|---|---|---|---|
| P0 | **路线归属未显式选定**：确认门是确定性闸门（CopilotKit 归链 B），但实现形似链 A | §2.1 末段（D1 二分法 + 代码路径确定性判据） | 选定链 A 保持现状（则 P2 两条要自建）或迁链 B（对齐 `resume` 契约）；无论选哪个，**理由须落档** |
| P0 | 中断信封缺字段（`reason`/`message`/`responseSchema`/`expiresAt`/`metadata`） | `@ag-ui/core@1.0.2` `Interrupt` 类型；AG-UI contract rules | 给 `confirm_request` / `frontend_tool_request` 补信封字段，帧类型不增 |
| P0 | 「修改后执行」无协议承载 | `editedArgs` 能力信号（AG-UI） | `ConfirmRequest` 增 `editedArgs`（全量替换语义）并加服务端复核 |
| P1 | 恢复期无「只重跑幂等 handler」白名单 | `run-handler.ts:935-946` + `reconnectBehavior` 文档 | 给挂起类型打 `reconnectBehavior` 等价的标记 |
| P1 | 无重试次数断路器 | `MAX_FOLLOW_UP_DEPTH`（`run-handler.ts:1081`） | 同类询问连续重复 N 次后改终态 |
| P2 | 客户端不校验回灌载荷 | AG-UI rule 6「Clients should validate before submitting」 | 前端按 `responseSchema` 先验，服务端仍硬验（保持双向） |
| P2 | 无「挂起前补快照」帧 | AG-UI "State at the interrupt boundary" | 挂起前显式发 `MESSAGES_SNAPSHOT`/`STATE_SNAPSHOT` 等价物，换端恢复更自洽 |
| P2 | 客户端无「有挂起时禁发新输入」守卫 | `@ag-ui/client@1.0.2` `onInitialize` 抛错 + react-ui `Input.tsx:97-104` `canSend` 含 `!interrupt` | 前端在有挂起时禁用输入框/给出提示（现由后端兜底） |
| P2 | **「中断渲染槽位」未显式化** | 三个前端同构：v2 `setInterruptElement`（§2.4）/ v1 react-ui `{interrupt}` 节点（§1.2）/ Vue `#interrupt` slot（§2.5） | 把挂起 UI 收敛为一个可注入槽位，而不是散在消息流里的条件分支 |
| P3 | 超时出口只有「作废」，无「显式取消以继续轮次」 | `Cancel it to continue the thread.` | 补「取消并继续」的显式动作（与「不执行」区分展示） |
| P3 | 若参考 CopilotKit 的 **Vue** 面，会丢掉标准流的三个能力 | §2.5：Vue 版仅 legacy（无 `interrupts[]` / 无 `cancel` / 无 `expiresAt`） | 对照面取 **React 契约（D9）+ AG-UI 协议（D5）**；Vue 只作「弱实现最小面」参照。**本仓是 Vue 3，这一点必须显式写在选型依据里** |

---

## 与既有件的差异订正

| # | 既有口径 | 本次取证 | 处置 |
|---|---|---|---|
| 1 | `调研-HITL交互型谱-2026-10-16.md`：五类打断「来源：CopilotKit（经分享会话转述）」 | 一手源为 `www.copilotkit.ai/learning/what-is-human-in-the-loop`，「Nearly every human-in-the-loop interaction is one of five patterns」 | **升级为一手引用**，五类清单与原表逐条一致，无需改内容 |
| 2 | 直觉口径「react-core / react-ui / runtime 三包」；本件**首轮**记为「实测 `packages/` 仅 `core / react-core / runtime / shared`，没有独立 react-ui 包」 | **首轮记录有误，二次核验更正**：实测 **5 个包**，`react-ui` 是独立包（`@copilotkit/react-ui` 1.79.1）。但「v2 HITL 实现不在 react-ui」的实质判断成立 —— `react-ui/src/v2/index.ts` 全文只有 `import "./styles.css";`（零导出），`react-ui/src/index.tsx` 自述为 v1 弃用入口，v2 实现入口是 `react-core/src/v2/index.ts` | 本件 §1.2 已改写并附「更正记录」（含漏记原因：`blob:none` 部分克隆的 `react-ui` 树晚落盘） |
| 3 | 既有件只说「`useInterrupt` 支持 standard 与 legacy 两条流」 | **源码证实** legacy 是 LangGraph 桥的**默认**，standard 需显式 `emitInterruptOutcome` 开关（AG-UI 集成表逐字） | 本件 §3.6 补证：自建桥可直接只走 standard |
| 4 | 既有件 `raw-copilotkit.md` 的 AG-UI 事件口径取自 `docs.ag-ui.com` 的 `sdk/js/core/events`（本地快照 `sdk_js_core_events.md`）与 `concepts/interrupts` | 该 SDK 参考页的 `RunFinishedEvent` **缺 `outcome`**，与同站 `concepts/interrupts` 及 `@ag-ui/core@1.0.2` dist 类型**不一致**（2026-10-17 线上复核确认）；dist 还多一个文档未列的 `outcome:{type:"cancelled"}` | 本件 §3.5 登记为「文档未同步」，以发布产物为准 |
| 5 | 本件**首轮**只引 `reference/hooks/useHumanInTheLoop.md`，未验 `/reference/v2/…` 路径 | **核验为路径别名、逐字节相同**（sha256 `b78fe4f3…`）；`sitemap.xml` 里 `/v2/` 不在列（canonical 是非 `/v2/`），而 CopilotKit 站内链接写的是 `/v2/`；`reference/v2/` 全量 19 条 URL 除 `useCapabilities` 外无其它页面 | 本件「取证基线」新增「路径别名核验」小节；**D3 引用无误差** |
| 6 | **首轮遗漏**：`useInterrupt` 的 **reference 页**（D9）与 **Vue（D10）/ React Native（D11）** 两页 | D9 是参数/返回值参考页（与 D2 guide 页不同页），补出 handler 错误边界、`enabled` 实例作用域、返回型表、过期不 resume、legacy 无 cancel 等 8 条增量；**D10 揭示 Vue 版只有 legacy 流**（无 `interrupts[]` / 无 `cancel` / 无 `expiresAt`）—— 因本仓是 Vue 3，此条对选型有直接影响 | 本件新增 §2.4.1、§2.5，并在 §5.4 补两条差距（槽位显式化、Vue 参照面选择） |
| 7 | 直觉口径「v2 `available` 沿用 v1 的三值字符串」 | **新发现的文档滞后（本件登记第 2 处）**：v2 reference 页把 `available` 记为 `'"enabled" \| "disabled" \| "remote"'` default `'"enabled"'`，但 v2 源码是 `available?: boolean`（`packages/core/src/types.ts:130`，语义为「置 false 以对 agent 隐藏但不注销」） | 本件新增 §2.2.2 并给出正确写法（v2 用 `available: false`） |

---

## 未覆盖清单与原因

| 未覆盖内容 | 原因 |
|---|---|
| `packages/runtime/src/v1-deprecated/**`（v1 runtime / GraphQL 层） | v1 已弃用；HITL 相关 v1 面已通过 `use-copilot-action` → `useHumanInTheLoop` 的转发链说明清楚 |
| `@ag-ui/langgraph` 桥的内部实现 | 该包不在克隆工作树内；本次只取 AG-UI 官方集成表的口径（`emitInterruptOutcome` 开关 + 默认 legacy 事件），**未臆测其内部实现** |
| `packages/react-core/src/v2/headless.ts` 的完整手写 headless 范式 | 既有件 `raw-copilotkit.md` §1.5 已抄录官方手写实现；本件只取「四个订阅回调 + 提交时机」的纪律口径 |
| CopilotKit 的 `humanInTheLoop` 静态 prop 与 hook 版的**逐行差异** | 已确认二者 handler 语义相同（`CopilotKitProvider.tsx:673-700` 注释逐字：*"Park the tool call until the render calls `respond`, matching `useHumanInTheLoop`."*），差异在注册渠道而非行为，未逐行比对 |
| `@ag-ui/*` 的 TypeScript 源（非 dist） | 三个包均不在克隆工作树内，且其仓库不在本次取证范围；dist 类型与实现足以支撑全部结论，凡引用均标注为「发布产物口径」 |
| **`@copilotkit/vue` / `@copilotkit/angular` / `@copilotkit/react-native` 的源码** | 这三个包**均不在**本仓 `packages/`（实测只有 `core / react-core / react-ui / runtime / shared`），是**独立发布的包**。故 §2.5 对 Vue 版「只有 legacy 流」的判断是**文档级证据**（D10 逐字），**未经源码复核** —— 已在该节标注来源；若本仓要正式依赖 Vue 侧结论，须另行取包核验 |
| CopilotKit 的 `intelligence-*`（线程平台）与 Channels（Slack/Teams）的 HITL 面 | 属商业平台独立能力域，不在本次问题清单 |

**取证方式记录（供复现）**：CopilotKit 源码取自仓外本地克隆 `_cpk-src/repo`（`git log -1` 得 commit 与时间，`git remote -v` 得来源；**注意该克隆为 `blob:none` 部分克隆，首次 `ls packages/` 可能因目录树尚未落盘而少列 —— 本件首轮即因此漏记 `react-ui`，见 §1.2 更正记录**）；`@ag-ui/*` 三个包以 `npm pack @ag-ui/{client,core,encoder}@1.0.2` 取发布产物后解包读 `dist`（类型取自 `.d.mts` 行号，实现取自压缩 `.mjs` 的字节偏移）；文档以 `curl -sL` 直取 `docs.copilotkit.ai/<path>.md` 与 `docs.ag-ui.com/<path>.md` 并做 **sha256 + 字节数 + `diff`** 三重比对（路径别名核验即用此法）；站点 canonical 判定用 `docs.copilotkit.ai/sitemap.xml`（533892 B）逐 URL 过滤；营销站口径取自 `www.copilotkit.ai/learning/what-is-human-in-the-loop`。**未遇到 404、登录墙或反爬。**
