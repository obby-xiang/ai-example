> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# AI 对话面板包 · 施工卡（定稿 v1.0（2026-10-10））

- 起草日期：2026-10-10
- 起草者：施工卡起草棒（设计腿活；最终裁决权归指挥官）
- 目标仓库：`<MAIN_REPO>`（主仓库目录；本卡一律用该占位符指代，遵守命名纪律）
- 仓库状态（实读，只读未动）：分支 `main`，HEAD `6ee96c3`，工作树 `10 改 + 1 未跟踪`（批 A 在制品，本次**零 git 写操作、零文件改动**）
- 产出位置：本卡及后续修正只落 `<SCRATCH>\chat-panel-pkg\`
- 标记约定：**【实测】** = 本次实读代码/配置所见；**【推断】** = 由实读事实推导；**【待裁决】** = 需指挥官拍板，施工方不代决

---

## 0. 输入与基线核对（锚点漂移审计）

### 0.1 三份必读输入

| 输入 | 用途 |
|---|---|
| `<SCRATCH>\chat-panel-research\现状盘点.md` | 当前能力盘点 + 主锚（文件:行号） |
| `<SCRATCH>\chat-panel-research\业界调研-页签会话-结论.md` | 页签级会话的清空/重开设计结论（命名/确认/服务端口径/页签语义文案） |
| `<SCRATCH>\chat-panel-research\业界调研-结论.md` | 49 功能点对照（复制=普遍、Stop=普遍、错误态/空态规范） |

### 0.2 锚点实读复核结果：**漂移 = 0【实测】**

盘点报告引用的全部行号在本次实读中**逐点一致，无漂移**，可直接作为施工主锚：

| 盘点报告锚点 | 实读复核 | 结论 |
|---|---|---|
| `stores/ai.ts#resetSession:1077-1102` | 文件共 1513 行；`resetSession` 确在 `:1077-1102` | 一致 |
| `stores/ai.ts:265`（historyLoaded）/`:267`（stopped） | `historyLoaded: false`@265、`stopped: false`@267 | 一致 |
| `stores/ai.ts#stop:1049-1074`、`#send:635-718`、`#loadHistory:421-480` | 逐点一致 | 一致 |
| `stores/ai.ts#initSessionId:103-115`、`:28`/`:29` 键、`createSessionId:58-67` | 一致 | 一致 |
| `AiPanel.vue:14-23`（头部）、`:20-22`（收起）、`:108-119`（空态）、`:121-258`（消息流）、`:148`（正文）、`:262-280`（输入区）、`:510-529`（send/stop）、`:592-603`（onMounted） | 文件共 632 行，逐点一致 | 一致 |
| `AiController.java:325-344`（cancel）、`:500-524`（history）、`:144`/`:203`/`:236`/`:288`/`:418`/`:469`/`:527`/`:669` | 文件共 678 行，逐点一致（含 `:418` `/runs/current`） | 一致 |
| `RedisChatMemoryRepository.java#deleteByConversationId:132-135` | 文件共 144 行，确在 `:131-135` | 一致（报告写 132，实读 `@Override` 在 131、方法签名在 132） |

### 0.3 本卡新增的实读事实（施工前必须知道）

1. **`stopped` 是彻底的只写字段【实测】**：`stores/ai.ts` 全仓命中 3 处 —— 声明 `:267`、复位 `:651`（`send` 内）、置位 `:1050`（`stop` 内）；**零读取点**。项 3 是"接上一根现成导线"，不是新增机制。
2. **`historyLoaded` 是彻底的只写字段【实测】**：命中 5 处 —— 声明 `:265`、置位 `:437`/`:447`/`:476`；`AiPanel.vue` 零命中。项 4 同上。
3. **前端零测试基建【实测】**：`frontend/package.json` 的 scripts 仅 `dev`/`build`/`typecheck`/`preview`；无 vitest / jest / playwright 依赖；`frontend/tests` 目录不存在；全 `frontend/src` 无 `*.spec.*` / `*.test.*`。CI 的前端 job（`.github/workflows/ci.yml:53-79`）只跑 `yarn typecheck` + `yarn build`。
4. **`navigator.clipboard` 在本仓存在真实降级面【推断】**：`frontend/vite.config.ts:23-24` 为 `host: '0.0.0.0'`（局域网可访问），而 Clipboard API 仅在**安全上下文**（https / localhost）可用。仓库存在 LAN 相关验证与登记（`docs/M1-出口评审材料.md` 引 `S44d-LAN403修复验证.md`、排期条目 7「CORS 白名单配置化（LAN 方案 B）」）⇒ 经 `http://<内网IP>:5200` 访问时 `navigator.clipboard` 为 `undefined`。项 2 是否需要 `execCommand` 回退，见 §2.3。
5. **`el-skeleton` 可直接用【实测】**：`frontend/src/main.ts` 全量 `app.use(ElementPlus)`（非按需导入）⇒ 骨架屏组件零额外引入成本。全仓现无任何 `el-skeleton` 用法（新增形态）。
6. **Tailwind `group-hover` 可用【实测】**：`frontend/tailwind.config.js` 仅关 `preflight`，`group`/`group-hover` 属默认 core plugin，可用；全仓现无 `group-hover` 用法（项 2 首次引入该写法）。
7. **记忆端点在无 AI key 时也活着【实测】**：`ai/config/AiModelConfig.java:62-101` 的 `messageJsonCodec` / `chatMemoryRepository` / `chatMemory` / `messageChatMemoryAdvisor` 四个 bean 位于**外层** `@Configuration`，而"仅在有 key 时装配"的 `AiKeyPresentCondition` 只加在内层 `static class OpenAiModelConfiguration`（`:105-107`）上 ⇒ 无 key 时 `chatMemory` 仍可注入（与 `AiController.java:77-78` 类注「历史的读、确认/回灌的写入不依赖 key」口径一致）。此事实决定新端点的鉴权/可用性分支设计（见 §1.5）。
8. **记忆在每轮终局都会被整窗口重写【实测 — 本次最重要的风险来源】**：`ai/run/ResilientChatService.java#convergeMemory:576-592` 在三处终局被调用 —— `finish:467`（成功）、`emptyReply:508`（空回复失败）、`cancelTerminal:530`（**取消**）。其实现为 `chatMemory.clear(sessionId)` + `chatMemory.add(sessionId, windowed)`（`:586-587`）⇒ **若在轮次仍在跑时删掉记忆，该轮收尾时会把刚删掉的会话原样写回**。这不是理论风险：`cancel` 终局也在重写路径上。
9. **`resetSession` 的换 id 从未生效【实测 — 项 1 的硬前置缺陷】**：`stores/ai.ts` 全文对 `this.sessionId` 的**赋值命中数为 0**；`resetSession:1090` 只把新 id 写进 `sessionStorage`，响应式 state 仍是旧值（6 处 `this.sessionId` 命中全为读取：`:360/423/435/490/695/1089`）⇒ `send:695` 仍带旧 id 请求、"新对话"实际继承旧会话记忆。详见 §1.2「第一位缺陷」。
10. **`AiController` 构造器被多处测试直接调用【实测】**：`new AiController(...)` 至少 5 处（`AiControllerRunsCurrentTest:166`、`AiControllerHealthSseDeliveryTest:72`、`AiControllerFrontendResultTest:141`、`conformance/SessionBusyFrameIsolationConformanceTest:103` 等）⇒ 新端点**不得**改构造器签名（否则 5+ 调用点全断）。

---

## 1. 项 1「新建对话」按钮

### 1.0 已裁决口径（不得推翻）

- 位置：面板**头部右侧**；形态：**中文文字按钮「新建对话」**（非图标）；对话为空时**置灰**；**无二次确认**。
- 点击效果：**立即清屏 + 前端换新 `sessionId` + 调后端新端点删除旧会话记忆**。
- 空态补一行**页签语义小字**（口径「对话保存在当前浏览器页签中，关闭页签即结束」；措辞可优化，**只陈述事实、禁隐私承诺**）。
- 后端接 `RedisChatMemoryRepository#deleteByConversationId` 这行死代码。

---

### 1.1 步骤 A —— 面板头部加文字按钮

- **主锚**：`<MAIN_REPO>/frontend/src/components/AiPanel/AiPanel.vue:14-23`（头部容器；`:16-19` 标题块；`:20-22` 收起按钮）
- **改什么**：在标题块与收起按钮之间插入 `<el-button size="small" text>` 文字按钮。
- **怎么改**（示意，最终形态由施工方按既有模板风格落定）：

```vue
<el-button
  size="small"
  text
  data-testid="new-chat"
  :disabled="!ai.messages.length"
  title="新建对话"
  @click="newChat"
>
  新建对话
</el-button>
```

- **要点**：
  - 文案**必须是中文文字**「新建对话」（调研结论 a：文档/开发助手品类 12+ 独立官方来源一致用 `New chat`；图标会被读成"关闭"，而"关闭=什么都没发生"，与"重开"语义相反 —— 见 `业界调研-页签会话-结论.md:340-345`）。
  - 置灰判据 = `ai.messages.length === 0`（对齐空态判据，见 `AiPanel.vue:108`）。
  - **不加 `×` / `+` / 垃圾桶 / 循环箭头图标**；不引入 tooltip 依赖（`title` 原生属性足够）。
  - 与既有「收起」按钮的视觉层级要区分：收起仍是图标按钮（保持不动），新按钮是文字按钮。
- **验收**：
  1. 新页签打开（`messages` 为空）→ 按钮可见且**置灰不可点**；
  2. 发送一轮后 → 按钮**可点**；
  3. 点击后消息区立即清空 → 按钮**回到置灰**。

---

### 1.2 步骤 B —— store：`resetSession` 扩展为「删旧记忆 → 换 id」

- **主锚**：`<MAIN_REPO>/frontend/src/stores/ai.ts#resetSession:1077-1102`
- **改什么**：把现在的"只清前端 + 换 id"扩为"中止在跑轮 → 通知后端取消 → 删旧记忆 → 换 id"；签名由 `resetSession(): void` 改为 `async resetSession(): Promise<void>`。
- **怎么改（按现有代码顺序，逐行说明）**：

| 位置 | 现状 | 改动 |
|---|---|---|
| `:1078-1084` | `currentAbort?.abort()`（R5① 已落地） | **保留**：abort 先行（这是"生成中点新建对话"的第一道收口，见 §5.1） |
| `:1056` 同款 | `stop()` 里另有 `const runId = this.activeRunId` | **新增**：在 `resetSession` 内取 `const oldSessionId = this.sessionId` 与 `const oldRunId = this.activeRunId`（**必须在清 `activeRunId` 之前取**） |
| 新增 | — | 若 `oldRunId` 存在：`await aiApi.cancel(oldRunId)`，**用 `try/catch` 吞掉失败**（后端不可达不能阻断本地清屏；`stop():1068-1073` 是同款口径） |
| 新增 | — | `try { await aiApi.deleteHistory(oldSessionId) } catch { this.notice = '旧会话记忆删除失败（不影响新对话）' }` —— **删除放最后**，理由见 §5.2（`convergeMemory` 复活窗口）；**失败提示带「仍是本轮」守卫**（复核 F5 收口：异步落地（最坏等 HTTP 超时）的提示不得覆盖新会话/新轮的 `notice`） |
| `:1088-1093` | 删镜像 + `sessionStorage.setItem(SESSION_ID_KEY, createSessionId())` | **保留**，并**必须补 state 双写**（先算 `const nextId = createSessionId()`，`sessionStorage.setItem` 与 `this.sessionId = nextId` 用**同一个** id，禁止两次调用生成两个 id）—— 见下方「第一位缺陷」 |
| `:1094-1101` | 清 `messages/activeRunId/notice/busyConflict/poolSaturated/suspended/activeForm` | **保留**，另补 `this.historyLoaded = true`、`this.streamInterrupted = false`、`this.stopped = false` |

> ### 第一位缺陷（本棒已实测坐实，项 1 的硬前置）
>
> **`resetSession` 换 id 是无效的 —— 它只改 `sessionStorage`，不改 store 的响应式 `sessionId`。**
>
> - **实读证据【实测】**：`stores/ai.ts` 全文对 `this.sessionId` 的**赋值命中数为 0**（唯一出现是 state 初始化 `:245` 的 `sessionId: initSessionId()`）。`grep -n "this.sessionId" stores/ai.ts` 的 6 处命中**全部是读取**：`:360`（写镜像）、`:423`（读镜像）、`:435`（`aiApi.history`）、`:490`（`runsCurrent`）、`:695`（`aiApi.chat` 请求体）、`:1089`（删镜像键）。`:1090` 只执行 `sessionStorage.setItem(SESSION_ID_KEY, createSessionId())`。
> - **后果（三项，均为功能级）**：
>   1. `send`（`:695`）仍以**旧 id** 请求 ⇒ "新建对话"实际**继续使用旧会话的服务端记忆**，与特性目的完全相反；
>   2. `startMirror` 的防抖写入（`:360`）把新对话的镜像写进 `ai-mirror:<oldId>` ⇒ 刷新后 `loadHistory`（`:423`）从**旧键**恢复，前后端都继续挂在旧 id 上；
>   3. 若项 1 按"删 `oldId` 的记忆"实现，就会出现"**删了旧记忆，却在旧 id 上继续对话**"的最坏组合。
> - **为何至今无人发现**：`resetSession` 的唯一调用方是 `<MAIN_REPO>/scripts/m2t2a-smoke.mjs:406`（E2E 收尾清理，跑完即退出，不校验 id）⇒ **该缺陷从未暴露在用户可见路径上**。这一点也解释了盘点报告为什么能把它描述成"半成品"而没标为缺陷 —— 它不只是"未接线"，**接线前必须先修**。
> - **修法（两行以内）**：在 `:1088-1093` 的 `try` 块内改为
>   ```ts
>   const nextId = createSessionId()
>   sessionStorage.removeItem(MIRROR_PREFIX + this.sessionId)
>   sessionStorage.setItem(SESSION_ID_KEY, nextId)
>   this.sessionId = nextId
>   ```
>   （`sessionId` 是 state 字段，直接赋值即可触发响应式；注意**先取值再覆盖**，避免 `removeItem` 用了新 id。）
> - **顺序要求**：本修必须与项 1 的其他步骤**同批落地**（它是"换新 sessionId"这一裁决动作的实际生效点）；单独立项无意义，但**必须在本苞的施工报告里单列一条**（属"新发现缺陷 + 修复"，不是既有设计）。
> - **验收**：CDP `evaluate` 断言 `ai.sessionId === sessionStorage.getItem('ai-session-id')` 且两者都不等于点击前的值。

- **返回值/调用方**：唯一的既有调用方是 `<MAIN_REPO>/scripts/m2t2a-smoke.mjs:406`（`ai.resetSession(); return true`，**不 await、不读返回值**）⇒ 改成 async **不破坏该脚本**。但该脚本会在收尾时真发一次 DELETE；若后端未起或端点不存在，异常被 store 吞掉，`return true` 不受影响。**【实测：调用形式】**
- **验收**：
  1. 点「新建对话」后 `ai.sessionId` 与 `sessionStorage['ai-session-id']` **都**是新值且彼此相等（CDP `evaluate` 断言 —— 这也是「第一位缺陷」的验收动作）；
  2. `sessionStorage['ai-mirror:<oldId>']` 已删除；
  3. 后端 `GET /api/ai/history/{oldId}` 返回 `count: 0`；
  4. 在跑轮次不会被"孤儿帧"污染（`:1157-1188` 归属判定 + G5 丢帧路径已有，回归确认即可）。

---

### 1.3 步骤 C —— api 层加一个函数

- **主锚**：`<MAIN_REPO>/frontend/src/api/ai.ts`（现有：`history`@63-66、`cancel`@53-56、导出对象 `aiApi`@119-133）；`<MAIN_REPO>/frontend/src/api/http.ts#del:121-125`（**现成的 DELETE helper，无需新增**）
- **改什么**：加 `deleteHistory(sessionId)` 并挂进 `aiApi`。

```ts
/** DELETE /api/ai/history/{sessionId}（新建对话：删旧会话记忆；幂等，不存在也 200）。 */
export function deleteHistory(sessionId: string): Promise<Record<string, unknown>> {
  return del<Record<string, unknown>>(`/ai/history/${encodeURIComponent(sessionId)}`)
}
```

- **注意**：`http.ts` 的响应拦截器对裸对象原样放行、对 `ApiResponse` 信封拆包（`http.ts:88-101`）⇒ 新端点用哪种返回体都不影响前端读取（见 §1.5 的返回体选型）。
- **验收**：`yarn typecheck` 0 error（新函数与 `aiApi` 成员齐全）。

---

### 1.4 步骤 D —— 空态补一行页签语义小字

- **主锚**：`<MAIN_REPO>/frontend/src/components/AiPanel/AiPanel.vue:112-118`（`el-empty` 的 `#description` 插槽）
- **改什么**：在能力清单 `<div>` 之后追加一行小字。

```vue
<div class="mt-2 text-xs text-[#c0c4cc]">对话保存在当前浏览器页签中，关闭页签即结束。</div>
```

- **措辞纪律（调研结论 e 与"设计启示 4/5"）**：
  - **必须**只陈述事实：**禁** `Temporary chat` / `隐私模式` / `不会记住` / `不保存` / `不记录` / `不会用于训练` 这类承诺 —— 本仓服务端 6h 内**确实持有**上下文（`application.yml:98` + `RedisChatMemoryRepository:137-142`），写这类文案属失实承诺（`业界调研-页签会话-结论.md:361-365`）。
  - 建议口径二选一（**【待裁决】措辞**）：
    - **A（保守版，只讲用户可见）**：`对话保存在当前浏览器页签中，关闭页签即结束。`
    - **B（精确版，多讲一句刷新与时限）**：`本页签的对话刷新后仍在；关闭页签即结束这段对话。`
  - 施工建议：取 A 为默认，B 作为可选；**不**写"6 小时"这类服务端时限（对用户无行动价值，且会引出"那 6 小时内算不算留存"的追问）。
- **验收**：空态（新页签 / 新建对话后）可见该行；有消息时随 `el-empty` 一起不渲染。

---

### 1.5 步骤 E —— 后端端点设计建议【待指挥官裁决】

#### E1 事实基线（实读）

| 事实 | 锚点 |
|---|---|
| 端点全集**无任何 DELETE**；`/history/{sessionId}`@500 是记忆唯一读侧，返回 `ApiResponse.ok({sessionId,count,messages[]})` | `AiController.java:500-524` |
| 端点风格**两族并存**：裸 `Map` + `ResponseEntity`（`/chat`@144、`/confirm`@203、`/frontend-tool-result`@236、`/cancel`@325、`/runs/current`@418、`/health`@527）；`ApiResponse` 信封（`/cancel` 只读态@347、`/startup-resume`@355、`/runs`@378、`/runs/{id}`@387、`/history`@500、`/pending`@669） | 同文件 |
| 错误体统一 `json(HttpStatus, Map)` 私有 helper（`:496-498`），**显式指定 JSON 内容类型**（类注 `:492-495` 说明 SSE 端点必须显式，否则 "No converter"） | `AiController.java:492-498` |
| 幂等先例：`/cancel` 重复调用**返回同一事实**（`alreadyCancelled=true`），**不报错**；不存在该轮也返回 200 + `runExists=false` | `AiController.java:325-344` |
| Redis 不可用 → **开流/执行前 503**（`redisUnavailable()`，机器码 `AI_REDIS_UNAVAILABLE`） | `AiController.java:155-157`、`:476-478`、`:600-605` |
| 无 AI key → `/chat`、`/resume` 503 `AI_UNAVAILABLE`；但**历史读、确认/回灌写不依赖 key**（类注 `:76-78`），且 `chatMemory` bean 在无 key 时仍存在（§0.3 第 7 条） | `AiController.java:76-78`、`AiModelConfig.java:62-107` |
| 鉴权：**无认证鉴权**（DC-10；`docs/02-需求设计文档-v2.2-冻结版.md:143`、`docs/03-技术方案文档-v2.1.md:489`）⇒ 新端点同样**不加任何鉴权**，与既有端点一致 | — |
| 死代码：`RedisChatMemoryRepository#deleteByConversationId` = `DEL chat:mem:<id>` + `SREM chat:mem:__ids__`，全仓无调用方 | `RedisChatMemoryRepository.java:131-135` |
| 官方等价路径：`ChatMemory#clear(conversationId)` 已被本仓使用（`ResilientChatService.java:586`），`MessageWindowChatMemory` 会落到**同一个** `deleteByConversationId` | `AiModelConfig.java:81-88` |

#### E2 建议形态（主推，标【待裁决】）

| 项 | 建议 | 理由 |
|---|---|---|
| **方法 + 路径** | `DELETE /api/ai/history/{sessionId}` | 与既有读侧 `GET /api/ai/history/{sessionId}`@500 **同资源、同路径、异动词**：被删的正是该 GET 返回的那份"记忆窗口原文"，语义自洽、路径最短、前端零路径拼接歧义。备选 `DELETE /api/ai/sessions/{sessionId}`（若指挥官想把语义定为"会话结束"而非"删消息"）；**不推荐** `POST .../clear`（本仓 POST 一律是"动作/命令"语义，DELETE 更贴事实） |
| **鉴权** | **无**（不新增任何鉴权） | DC-10 明确本期无认证鉴权；新端点若单独加锁会与全仓不一致 |
| **可用性门** | **不查 AI key**；**查 Redis**：`!redisAvailability.isAvailable()` → `503` + `redisUnavailable()` | 记忆纯 Redis 操作（与 `/history` 同类，无 key 也应可用）；Redis 不可用时无法保证删除生效，必须显式 503 而不是假装成功（对齐 R4 口径 `:150-157`、`:475-478`） |
| **实现落点** | 优先 `this.chatMemory.clear(sessionId)`（官方能力，DC-05 口径），**而非**直接调 `deleteByConversationId` —— 两者最终落到同一行 Redis `DEL + SREM` | DC-05「Spring AI 已有能力禁止自研」；`chatMemory` 已在 `AiController` 构造器注入（`:93`、`:128`），**零构造器改动 ⇒ 零测试调用点churn**（见 §6.2） |
| **返回体** | 裸 `Map` + `ResponseEntity.ok`（**不用 `ApiResponse` 信封**，理由是 `/cancel` 这个最近的"状态变更 + 幂等"先例就是裸 Map，且前端 `aiApi` 一律按裸对象处理） | 风格与最近邻一致；`http.ts:88-101` 两种形态都容得下，属于风格一致性问题而非技术问题 |
| **HTTP 语义** | **删除不存在也返回 200**（**不返回 404**） | ① `/cancel` 先例：不存在的 run 也是 200 + `runExists=false`；② 记忆键本来就会自然 TTL 过期（6h 滑动），"已不存在"是**正常终态**而非错误；③ 前端"新建对话"绝不能因旧键已过期而报错（幂等是硬要求）。**这三点里 ①③ 是硬约束** |
| **幂等性** | 幂等：重复调用返回同一事实，第二次 `existed=false` | 同上；`DEL`/`SREM` 天然幂等 |
| **SessionGate 配合** | **建议做，但只做"报告 + 弱拒绝"，不做硬阻塞** —— 见下方 E3 | 硬阻塞会与"无二次确认、点击即生效"的裁决冲突 |

#### E3 SessionGate 配合：建议与取舍【待裁决，本卡最重要的裁决点之一】

- **为什么必须回答这个问题**：§0.3 第 8 条 —— 若该 `sessionId` 仍有在跑轮次，该轮终局会执行 `convergeMemory` → `chatMemory.clear + add`，把刚删的记忆**写回来**。删除动作在并发下会被"复活"。
- **必须说清的缓解事实**：由于前端**同时换了新 `sessionId`**，复活的数据挂在**同一个旧 id** 上，而该 id 已被前端丢弃、不会再被任何一轮请求使用 ⇒ **对用户可见行为无影响**（新对话不会被旧记忆污染），影响面 = Redis 上多留一段无人读取的记忆直到 6h TTL 到期。即：**这是"清理不彻底"，不是"功能错误"**。
- **三个候选（建议按 ①+③，② 留作备选）**：

| 候选 | 形态 | 代价 | 评价 |
|---|---|---|---|
| ① **先取消后删除（前端顺序收口）** | 前端在 DELETE 之前先 `POST /api/ai/cancel/{runId}`（§1.2 已含），并**接受**删除必然被取消收尾的收敛重写 | 极小；**不关闭**复活窗口 —— `cancel` 只是标志 + 唤醒，轮次收尾异步 ⇒ 在飞路径下 DELETE **必然先于**收盘重写（复核 F2 订正：原写"毫秒~秒级竞态"偏轻，实为顺序必然，顺序收口缩不小该窗口） | **建议采纳（基线）**。收益最大成本最低 |
| ② **后端弱拒绝** | 端点在删除前查 `sessionGate.currentRunId(sessionId)`：若非空 → **仍然执行删除**，但返回体带 `runActive: true` + `activeRunId`，让前端 `notice` 提示"该会话本轮仍在收尾，记忆删除可能被收尾重写" | 引入"删除成功但可能有残留"的模糊返回，与幂等简洁口径有张力 | **备选**：若指挥官要"事实完整披露"就采纳；若要求端点语义单一则否 |
| ③ **后端硬拒绝（409）** | `sessionGate.currentRunId(sessionId) != null` → `409 SESSION_BUSY` + `runId`（复用 `:164-171` 的现有错误体形态） | 与裁决「点击 = 立即清屏…无二次确认」冲突（用户会看到一次失败）；需前端重试逻辑 | **不建议**：会把"新建对话"变成可能失败的动作，破坏"点了就干净"的心智 |

- **建议结论**：**端点内不查 SessionGate（保持语义单一 + 幂等），前端顺序（先 cancel 再 delete）**—— 但须如实登记：该顺序**不缩小**复活窗口（复核 F2 订正），复活在在飞路径下**必然发生**；把这个窗口登记为已知代价（≤6h 的 Redis 残留，用户不可见）。若指挥官要更强的保证，走候选②的"报告式"，**不要**走 409。

#### E4 建议返回体（草案）

```json
{
  "sessionId": "<oldId>",
  "deleted": true,
  "existed": true,
  "memoryKey": "chat:mem:<oldId>",
  "removedFromIndex": true,
  "runActive": false,
  "activeRunId": null,
  "storedBy": "<instanceId>",
  "note": "删除以 Redis 为准；同一会话若仍有在跑轮次，该轮收尾的记忆收敛可能重新写入同名键"
}
```

- `existed` 取 `chatMemory.get(sessionId)` 是否非空（或 `redis.hasKey`）**在删除之前**判定；`deleted` 恒为 `true`（幂等语义 = "处于已删除状态"）。
- 是否顺带删 `ai:session:<sessionId>`（`AiSessionService.java:33`）与 `ai:session:current:<sessionId>`（`RunStore`）：**【待裁决】**。建议**不删**（两者各有自己的 TTL；删 run 索引会让运维取证面变窄），若要"会话结束"更彻底，可只删 `ai:session:<id>`。**不建议动任何 `ai:run:*` 快照**（那是轮次台账，删除会让"重启续跑/取证"失去依据）。

#### E5 端点的单元测试锚

- 新用例类建议命名 `AiControllerHistoryDeleteTest`，构造方式照抄 `<MAIN_REPO>/backend/src/test/java/com/example/configmgr/ai/web/AiControllerRunsCurrentTest.java:160-170`（直接 `new AiController(...)` + Mockito mock）。
- **关键约束**：**不得改 `AiController` 构造器签名**（现有至少 5 处测试直接按固定参数表 `new AiController(...)`：`AiControllerRunsCurrentTest:166`、`AiControllerHealthSseDeliveryTest:72`、`AiControllerFrontendResultTest:141`、`conformance/SessionBusyFrameIsolationConformanceTest:103`，以及可能的其他点）。本设计**只需要已注入的 `chatMemory` / `redisAvailability` / `runStore`**，构造器零改动 ⇒ 上述调用点零 churn。**【实测：调用点存在】**
- 用例四条（对应 §6.1）：存在删除 → 200/deleted；不存在 → 200/`existed=false`；Redis 不可用 → 503 + `AI_REDIS_UNAVAILABLE`；无 key（`availability=false`）→ 仍 200（不得 503 `AI_UNAVAILABLE`）。

#### E6 一致性自查（供裁决时对照）

- 无 key 可用：是（§0.3-7）
- 无鉴权：是（DC-10）
- 幂等、不 404：是（`/cancel` 先例）
- Redis 不可用快速失败：是（R4 口径）
- 不引入新依赖、不动构造器：是（零测试 churn）
- 风格：裸 Map + JSON 内容类型：是（`json()` helper 复用）

**【以上 E2–E5 整体待指挥官裁决后方可施工】**

---

## 2. 项 2 单条复制

### 2.1 步骤 A —— 气泡内加 hover 复制按钮

- **主锚**：`<MAIN_REPO>/frontend/src/components/AiPanel/AiPanel.vue:121-136`（消息行 + 气泡容器；`:133-136` 气泡 div）、`:148`（正文渲染点，复制内容的口径参照）
- **改什么**：消息行加 `group`，气泡内加一个 hover 显现的复制按钮。
- **怎么改（示意）**：

```vue
<!-- :121-126 消息行加 group -->
<div v-for="m in ai.messages" :key="m.id" class="group flex gap-2 mb-3.5" ...>

<!-- 气泡内（建议置于气泡顶部一行，或正文之后） -->
<div v-if="m.content" class="mt-1 flex justify-end">
  <el-button
    link
    size="small"
    class="opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity"
    :data-testid="'copy-msg-' + m.id"
    aria-label="复制该条消息"
    @click="copyMessage(m)"
  >
    复制
  </el-button>
</div>
```

- **要点**：
  - 用户消息与 AI 消息**都给**（业界做法是逐条消息级操作条 —— `业界调研-结论.md:46`「hover 消息出现 Copy；桌面 hover、移动常驻」）。
  - 显现条件：`m.content` 非空（流式中正文为空的空气泡不出现按钮，跳过无意义的复制）。
  - 可访问性：`focus-within:opacity-100` 让键盘用户可见；`aria-label` 给出语义（按钮文字已是「复制」，`aria-label` 可省但保留更稳）。
  - 触屏（`hover: none`）下 hover 不成立 —— 建议**不**为此单独加媒体查询：本仓 AI 面板是桌面工具形态（`App.vue:70` 固定 400px），**【待裁决】**是否要为平板补"常驻显示"。
- **验收**：hover 任一消息 → 出现「复制」；点击 → 剪贴板内容 === `m.content`；无正文的流式空气泡无按钮。

### 2.2 步骤 B —— 复制实现 + 内容口径

- **主锚**：`AiPanel.vue:148`（`{{ m.content }}` 是气泡正文的唯一来源）；无现成 clipboard 工具（§0.3 第 4 条）
- **复制内容口径（即裁决要求的"正文纯文本"）**：
  - **复制 `m.content` 原样**（`ChatMessage.content`，`types/ai.ts:185`）。
  - **不含** `m.reasoning`（思考链，`:143-146`）、**不含** `m.toolRuns`（工具卡，`:151-166`）、**不含** `m.pendingCall`（挂起卡，`:169-255`）、**不含**头像/角色标签/时间。
  - **不 trim**：正文以 `whitespace-pre-wrap` 渲染（`:148`），缩进是可见语义；trim 会让"复制结果 ≠ 看到的内容"。
- **建议（实读后的判断，供裁决参考）**：**维持"正文纯文本"口径，不建议把思考链/工具卡并入复制内容**。理由三条：
  1. 调研里"复制含 thinking 与 tool calls"的范本是 VS Code Copilot（`业界调研-结论.md:46`）——那是**开发者工具**，工具卡的产物是"复现步骤"；本仓工具卡是**业务副作用回执**（启动导出/导入/发布作业、下载文件；`AiPanel.vue:329-356` 的 `TOOL_LABELS` 可证），把它并入复制内容会让人误以为"复制=复现这套操作"，而参数里含任务号/配置项编码，脱离上下文传播有误导风险。
  2. 工具卡与思考链在 UI 上本就是**折叠/附属**形态（`:143` 用 `<details>`、工具卡是卡片），把附属内容悄悄塞进"复制正文"会违背"复制所见正文"的直觉。
  3. 若未来需要，正确形态是**另给**「复制全部（含思考与工具）」二级动作，而不是改本按钮语义。
- **实现（含降级）**：

```ts
async function copyMessage(m: ChatMessage): Promise<void> {
  const text = m.content
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      legacyCopy(text)   // 见下
    }
    ai.notice = '已复制'
  } catch {
    legacyCopy(text)     // clipboard 存在但被拒（权限/非聚焦）时也走回退
    ai.notice = '已复制'
  }
}
```

> `ai.notice` 是**单槽位**（`stores/ai.ts:256`，排期已登记瑕疵②`M2-排期计划.md:174`）—— 用 `notice` 报"已复制"会覆盖其它提示。**【待裁决】**：建议项 2 用**局部组件状态**（`AiPanel.vue` 内的 `copiedMsgId` ref，2s 后清空）显示"已复制"，**不要占用 `ai.notice`**，以免放大既有瑕疵②。

### 2.3 是否需要 `execCommand` 回退？ —— **建议需要（轻量版）【推断】+【待裁决】**

- **事实**：`navigator.clipboard` 需安全上下文；本仓 dev server `host: '0.0.0.0'`（`vite.config.ts:23-24`），且仓库确有 LAN 访问相关的验证与登记（§0.3 第 4 条）⇒ 存在 `http://<内网IP>:5200` 的真实访问面，那里 `navigator.clipboard` 为 `undefined`。
- **结论**：**建议保留一个约 10 行的 `document.execCommand('copy')` 回退**（临时 `textarea` + `select()` + `execCommand` + 移除 + `revokeObjectURL` 无关）。理由：① 成本极低（无新依赖、无 polyfill）；② 没有回退时 LAN 用户会看到"点了没反应"，属静默失败，正是调研要点 9 明确反对的错误态形态（`业界调研-结论.md:132`）；③ 若指挥官认为生产必是 HTTPS、LAN 面可忽略，则可以不要回退 —— 但**必须显式裁决**，因为这是"静默失败 vs 10 行代码"的取舍，不是技术细节。
- **验收**：在 `http://localhost:5200`（安全上下文）下 clipboard 路径生效；在 `http://<内网IP>:5200`（非安全上下文）下回退路径生效、复制成功；两条路径都不得抛未捕获异常。

---

## 3. 项 3 停止误闪红条修复

### 3.1 步骤 A —— 用 `stopped` 抑制 `onClose(terminal=false)`

- **主锚**：`<MAIN_REPO>/frontend/src/stores/ai.ts#send` 的 `onClose:670-686`（`:674` 的 `if (!info.terminal)`）；标志 `stopped` 声明 `:267`、置位 `:1050`、复位 `:651`
- **改什么**：把 `if (!info.terminal)` 改为 `if (!info.terminal && !this.stopped)`。
- **根因链（实读，逐跳可核）**：
  1. 点「停止」→ `AiPanel.vue:276` → `stop():527-529` → `stores/ai.ts#stop:1049-1074`；
  2. `stop()` 第一件事 `this.stopped = true`（`:1050`），随后 `currentAbort?.abort()`（`:1052`）；
  3. abort 让 `api/sse.ts#readStream` 走 abort 分支（`sse.ts:299-307` 返回 `{aborted:true}`）；
  4. `streamChat` 在 `result.aborted` 且**非**帧间静默超时时**不做 tryReattach**，直接 `finish(..., terminal=false, '连接已中止')`（`sse.ts:372-379`）；
  5. `onClose` 收到 `terminal=false` → `stores/ai.ts:674-677` 置 `streamInterrupted = true` + 写"连接中断，结果可能不完整…"；
  6. `AiPanel.vue:74-86` 渲染红色 `el-alert`「连接中断，结果可能不完整」+「一键重挂该轮」→ **用户点了主动停止，却被告知"连接中断"**，并且随后 `notice` 被 `stop()` 的「已请求取消本轮对话」（`:1070`）覆盖，形成"红条闪现 + 文案打架"。
- **为什么用 `stopped` 是正确判据**：`stopped` 的语义就是"本轮是用户主动停的"（`:266` 注释）；它在本轮 `send` 开头复位（`:651`），因此**只影响用户按过停止的这一轮**。
- **怎么改（含两处同型路径）**：**建议一处不漏**，三处 `onClose` 全部加同款守卫 —— 否则"停止"在重挂路径上仍会闪红条：

| 路径 | 锚点 | 现状 | 建议 |
|---|---|---|---|
| `send` | `:670-686`（`if (!info.terminal)` @674） | 会误闪 | 加 `&& !this.stopped` |
| `reattachActive` | `:815-829`（@819） | 若"重挂中用户点停止"→ 同样误闪 | 加 `&& !this.stopped` |
| `resumeSnapshotStream` | `:604-612`（@608） | 同上（刷新后挂起续收中停止） | 加 `&& !this.stopped` |

> 是否三处全改属**口径选择**：只改 `send` 则"停止"在重挂/续收路径上仍有红条；全改则"用户主动停止"在所有路径统一为"不报断流"。**建议全改**（一致性优先，且三处判据同一个 `stopped`）。**【待裁决（低风险，可施工方按建议执行并留痕）】**

- **顺带项（可选，不在本项范围）**：`stop()` 置 `stopped=true` 后，若本轮**从未**收到终帧（后端 cancel 也没生效，见盘点 §5.2 缺口），红条被抑制后用户就再也看不到"结果可能不完整"的提示，只剩「已请求取消本轮对话」。**【推断】** 可接受：用户主动停止后，`notice` 已明示取消请求，且部分正文保留在气泡里（调研要点 5「Stop 后绝不清空部分输出」，本仓现状已满足）。**不建议**为此加新提示。

### 3.2 会不会影响断流重挂路径？—— **不会【推断，附前提】**

- **判据独立性**：`tryReattach` 在 abort 分支**本来就被跳过**（`sse.ts:372-379`：只有 `idleTriggered` 才重挂）⇒ 停止后的红条与"一键重挂该轮"按钮本来就不该出现（后端那一轮**并没有断流**，是用户取消的）。
- **真断流路径不受影响**：真断流时 `stopped === false`（`send` 开头复位 `:651`）⇒ `streamInterrupted` 照常置位、红条照常出现、`AiPanel.vue:83` 的「一键重挂该轮」按钮照常可用。
- **前提（须写入施工报告）**：结论依赖"`stopped` 只在 `stop()` 中置位、只在 `send()` 开头复位"。若将来新增调用方直接置 `stopped`（例如"取消并重发"），该守卫的语义会被稀释 —— 建议施工时在 `:267` 上方补一行注释说明"只用于抑制主动停止轮的断流提示"。
- **待确认的邻接事实【推断】**：`stop()` 只 abort 本地连接 + POST cancel；轮次收尾仍走 `send` 的 `finally`（`:703-717`），`loading` 复位时序不变（R3 口径 `:1057-1059` 未动）。本项**不触碰** `loading`/`activeRunId`/`finally`，故不改变 R1/R3/R5 的任何既有收敛行为。

### 3.3 验收

1. 发出可长跑的一轮 → 点「停止」→ **不出现**红色「连接中断，结果可能不完整」；仅出现「已请求取消本轮对话」；
2. 真断流（如 kill 后端进程 / 断网）→ **仍出现**红条 + 「一键重挂该轮」按钮；
3. `ai.streamInterrupted === false` 可作为 CDP 断言（脚本可 `evaluate` 读 pinia 状态，`scripts/m2t2a-smoke.mjs` 已有该手法：`:404-407`）。

---

## 4. 项 4 骨架屏

### 4.1 步骤 A —— 消费 `historyLoaded`

- **主锚**：`<MAIN_REPO>/frontend/src/stores/ai.ts:265`（声明）、`:437`/`:447`/`:476`（置位，均在 `#loadHistory:421-480` 内）；消费点 `<MAIN_REPO>/frontend/src/components/AiPanel/AiPanel.vue:107-119`（消息区 + 空态）
- **改什么**：消息区内加骨架屏，与空态互斥。

```vue
<!-- :107 容器内，替换现在的 el-empty 开头 -->
<el-skeleton v-if="!ai.messages.length && !ai.historyLoaded" :rows="4" animated class="p-2" />
<el-empty v-else-if="!ai.messages.length" :image-size="60"> ... </el-empty>
```

（是否加 `data-testid="history-skeleton"`：**建议加**，便于冒烟断言，与既有 `pending-card-*` / `tag-form-cancelled` 的 testid 纪律一致。）

### 4.2 步骤 B —— `resetSession` 补 `historyLoaded = true`

- **主锚**：`stores/ai.ts#resetSession:1094-1101`（清理块）
- **为什么必须补**：`historyLoaded` 只在 `loadHistory` 中置位；`resetSession` 把 `messages` 清空后，若因任何路径使 `historyLoaded` 仍为 `false`（例如 `loadHistory` 尚未返回时用户就点了新建），清空后的面板会**又长出骨架屏**，与"点了新建就该看到欢迎空态"冲突。显式置 `true` 是零成本的确定性收口。
- **验收**：点「新建对话」后立即看到**空态欢迎语**（不是骨架屏）。

### 4.3 骨架屏与空态的切换关系（须在施工报告中写清）

| 状态 | `messages.length` | `historyLoaded` | 渲染 |
|---|---|---|---|
| 首屏 / 刷新且无镜像，等后端历史 | 0 | false | **骨架屏** |
| 对账完成（历史为空或已重建） | 0 或 N | true | **空态**（0 条）或消息流（N 条） |
| 刷新且有镜像 | N>0（`loadHistory:423-431` 同步恢复） | false→true | 消息流（骨架屏不出现，因为 `messages.length !== 0`） |
| 新建对话后 | 0 | true（§4.2） | **空态** |

- **已知时序代价【实测 + 推断】**：`loadHistory` 在 `AiPanel.vue#onMounted:592-603` 里被 `await`，而**镜像恢复发生在 `loadHistory` 内部**（`:423-431`）⇒ 挂载到镜像恢复之间有一个极短窗口（同一次微任务/宏任务链内），此时 `messages` 为空、`historyLoaded` 为 false ⇒ **有镜像的刷新也会闪一下骨架屏**（数毫秒级）。**【推断】** 影响可忽略；若指挥官要求"绝不闪现"，需把镜像恢复提到 `onMounted` 同步段（**不建议**：会打散"镜像+对账"两步对账的既有结构，收益不抵风险）。
- **与挂起快照重建的关系**：`historyLoaded` 在 `restoreSuspendedSnapshot` 之前置位（`:448`、`:477-479`），故骨架屏在"挂起卡重建 + 增量续收"期间**已经消失**，此时面板显示的是镜像/历史消息（非空白）⇒ 不会出现"骨架屏掩盖挂起卡"的空窗。**【实测：置位顺序；推断：视觉结果】**
- **验收**：刷新页面（无镜像，如清掉 `sessionStorage['ai-mirror:*']` 后）→ 可见骨架屏 → 随后转为空态或历史消息；有消息时骨架屏不出现。

---

## 5. 风险与边界

### 5.1 项 1 × 进行中轮次（`activeRunId`）：生成中点「新建对话」怎么办？—— **abort 先行，但不够，需补 POST cancel**

- **abort 先行是必须的且已存在**：`resetSession:1078-1084`（R5①）已 abort 本地连接 —— 否则 `loading` 不复位（输入区永久禁用）且后续帧会写进已清空的 `messages`（孤儿帧）。
- **abort 只断本地，不停后端【实测】**：盘点 §5.2 已坐实"客户端 `abort()` 不通知后端"，只有紧随的 `POST /api/ai/cancel/{runId}` 才让后端停（`stores/ai.ts:1071-1073` 只提示失败）。**生成中点新建对话 = 一个必然的后端孤儿轮**（会跑到终态、消耗 token、走 `convergeMemory` 把记忆写回）。
- **建议改动**：在 `resetSession` 内补 `await aiApi.cancel(oldRunId)`（best-effort，失败只写 notice），**取 `oldRunId` 必须在清 `activeRunId`（`:1095`）之前**。
- **边界（须登记）**：`cancel` 只是"标志 + 唤醒"，后端轮次收尾是异步的 ⇒ **在飞路径下 DELETE 必然先于收尾重写**（复核 F2 订正：原写"毫秒~秒级竞态"偏轻），结果 = 旧会话记忆被写回、残留到 6h TTL（用户不可见，见 §1.5-E3）。**不做更强的保证**（硬 409 已否，见 E3）。
- **另需确认的边界**：`resetSession` 清 `activeRunId` 但**不清 `runSeq`**（排期 `issue#5 红队 K8` 已登记"旧 runId 水位残留无害"）⇒ 本项沿用现状，不新增清理。

### 5.2 项 1 × 记忆复活（本节是项 1 的最大技术风险）

- 见 §0.3 第 8 条与 §1.5-E3：`convergeMemory` 在**成功 / 空回复 / 取消**三种终局都会 `clear + add`。**任何"删除记忆"的实现都必须接受"并发轮次终局可能写回同名键"这一事实**；本卡的建议是"顺序收口（先 cancel）+ 登记为已知代价"，而不是试图用锁消灭它。
- **明确不做**：不改 `convergeMemory`、不引入墓碑键、不在删除端点上加分布式锁（超卡片范围，且会碰韧性与并发两个高风险面）。

### 5.3 项 2 的边界

- **`m.content` 是唯一正文来源**（`:148`），工具卡/思考链**不在**其中 ⇒ "复制正文纯文本"的实现不会误带附属内容（实读确认：`delta` 帧只 `message.content += frame.text`，`:1204-1207`）。
- **忙态与流式中**：流式进行中 `m.content` 持续增长 ⇒ 复制得到的是**点击那一刻的片段**（可接受，无需禁用；若指挥官要求，可在 `m.streaming` 时隐藏按钮 —— **不建议**，会削弱"想留个底"的主要用途）。
- **失败可见性**：clipboard 被拒 / 非安全上下文必须**有可见反馈**（不得静默）——见 §2.2、§2.3；**不要**用 `ai.notice` 承载（单槽位瑕疵②）。

### 5.4 项 3 的边界

- 见 §3.2：不影响真断流路径；前提是"`stopped` 只由 `stop()` 置位、只由 `send()` 复位"，建议加注释固化。
- **不触碰**：`finally` 的 `loading` 复位、`activeRunId`、`runSeq`、`reattachActive` 的双连接收口。

### 5.5 项 4 的边界

- 见 §4.3：与空态互斥（`v-if` / `v-else-if` 保证不可能同时渲染）；与挂起卡重建不冲突。
- **不引入**新 loading 态字段、不改 `loadHistory` 的置位时机（`:437/447/476` 三处置位本身是既有裁决 T2b-D#3 的产物，**不动**）。

### 5.6 跨项风险总表

| 风险 | 触发条件 | 后果 | 处置 |
|---|---|---|---|
| 记忆复活 | 生成中点新建对话 | 旧 id 下记忆残留至 6h TTL（用户不可见） | 先 cancel 再 delete；登记为已知代价（§5.2） |
| `sessionId` 未同步 state（**已实测坐实为缺陷**） | `resetSession` 现状 | "新对话"复用旧 id ⇒ 继承旧记忆（**功能级错误**） | **必修**，两行改动（§1.2「第一位缺陷」）；与本苞同批落地并单列留痕 |
| 前端零测试基建 | 三项前端改动 | 只能 typecheck/build + CDP 冒烟验证 | 不引入 vitest（**已有登记待业务裁决**，本包不代决）；用 data-testid + 既有 CDP 冒烟手法 |
| backlog API 非安全上下文 | LAN 经 http 访问 | 复制静默失败 | `execCommand` 回退（§2.3，待裁决） |
| `notice` 单槽位 | 项 2 若用 `ai.notice` 报"已复制" | 覆盖其它提示（放大既有瑕疵②） | 用组件局部状态（§2.2） |
| 端点加锁引发 409 | 若采纳 E3 候选③ | "新建对话"变成可失败动作，与裁决冲突 | **明确否掉候选③** |
| 构造器签名变更 | 若为新端点改 `AiController` 构造器 | 5+ 处既有测试调用点全断 | 只用已注入 bean（§1.5-E4） |

---

## 6. 测试建议

### 6.1 后端（XML 口径只增不减）

- **口径**：后端测试基线以 **surefire XML 求和**为准，**只增不减**（`docs/M2-T3-设计卡.md:114`、`docs/M2-T3b-施工报告-DS-V4-Flash.md:491/667`）。
- **当前基线数字的可靠性【重要】**：`docs/M2-排期计划.md` 最新登记为 **361 / 0 / 0 / 0**（T4-3 后半行，来源为该卡施工报告）—— 属**【旁证（排期表登记）】**，不是本棒实测。**本棒实测反例**：`<MAIN_REPO>/backend/target/surefire-reports/` 现在只有 **1 个 XML、合计 3 个用例**，并伴随多个 `.dumpstream` 文件 ⇒ 该目录**不是**一次完整跑测的产物，**不得**用它当基线。
- **施工要求**：新苞开工时先 `cd <MAIN_REPO>/backend && "<MAVEN_HOME>/mvn.cmd" -B clean test`（本机 `mvn` 不在 PATH，需用 `<MAVEN_HOME>` 全路径；见 `docs/evidence/DC14-微调实施验证.md:100` 的同款命令），**以本次实跑的 XML 求和作为唯一基线**，收尾再复跑一次同口径对比，并在施工报告里给出两个数字与「+N = 新用例类」的分解。
- **项 1 必须配的新用例**（四条）：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 会话有记忆 → DELETE | 200；`deleted=true`、`existed=true`；`chatMemory.clear(sessionId)` 被调用（或仓储 mock 收到 `deleteByConversationId`） |
| 2 | 会话无记忆 / TTL 已过期 → DELETE | **200**（**不得 404**）；`existed=false`、`deleted=true`（幂等） |
| 3 | Redis 不可用 → DELETE | 503 + `code=AI_REDIS_UNAVAILABLE`（对齐 `/chat`、`/resume` 口径） |
| 4 | 无 AI key（`AiAvailability=false`）→ DELETE | **200**（**不得** 503 `AI_UNAVAILABLE`；对齐"历史读写不依赖 key"类注 `:76-78`） |
| 5（建议） | 重复 DELETE | 两次都 200，第二次 `existed=false` |

- **构造方式**：照抄 `AiControllerRunsCurrentTest.java:160-170` 的 `new AiController(mock(...), ...)` 形态；**不得改构造器**（§1.5-E5）。
- **项 2/3/4 无后端改动 ⇒ 无后端用例新增**（XML 数不变，这也满足"只增不减"）。

### 6.2 前端（现状：无测试基建）

- **实读结论**：无 vitest / jest / playwright；无 spec/test 文件；`frontend/package.json` 无 `test` 脚本；CI 前端 job 只有 `yarn typecheck` + `yarn build`（§0.3 第 3 条）。
- **本包可执行的验证**（不引入新依赖、不改测试基建）：
  1. `cd <MAIN_REPO>/frontend && yarn typecheck` → 0 error；
  2. `yarn build` → 成功（与 CI 同口径，`.github/workflows/ci.yml:76-79`）；
  3. **CDP 冒烟**（项 1/2/3/4 唯一的行为级证据）：沿用 `<MAIN_REPO>/scripts/m2t2a-smoke.mjs` 的既有手法（Chrome 远程调试 + `evaluate` 读 `$pinia._s.get('ai')` 状态 + `data-testid` 定位，见 `:404-407` 与脚本头 36-40 行的定位口径纪律）。建议新增一个 `scripts/chat-panel-smoke.mjs`（**独立脚本，不改动既有冒烟**），断言：
     - **项 1**：点 `[data-testid="new-chat"]` → `ai.sessionId` 改变、`sessionStorage['ai-session-id']` 同步、`ai.messages.length === 0`、后端 `GET /api/ai/history/{oldId}` 的 `count === 0`；
     - **项 2**：hover + 点 `[data-testid^="copy-msg-"]` → 读剪贴板（CDP `Browser.grantPermissions` + `Runtime.evaluate` 读 `navigator.clipboard.readText()`）或至少断言按钮存在与文案；
     - **项 3**：起一轮 → 点停止 → 断言 `ai.streamInterrupted === false` 且**页面无**「连接中断，结果可能不完整」文本；
     - **项 4**：清 `sessionStorage` 镜像后 reload → 断言骨架屏元素存在（建议给 `data-testid`）→ 随后消失。
  - **脚本的可信度边界（必须写在报告里）**：CDP 下的 `navigator.clipboard` 常因"文档未聚焦"被拒（HTTP 非 localhost / 无窗口焦点），**项 2 的剪贴板内容断言可能不稳定**；备选证据 = 断言按钮存在 + 回退路径被调用（可注入桩）。
- **前端测试框架引入（vitest/playwright）**：**已有登记待业务裁决**（`docs/M2-排期计划.md` T4-3 后半相关行、S3-2 状态更新行）⇒ **本包不引入、不代决**。若指挥官本包一并裁决引入，则项 2/3/4 可各补一条最小单测（项 3 的 `stopped` 抑制、项 4 的渲染分支最容易用组件测覆盖）。

### 6.3 门禁与纪律（沿用既有）

- 脱敏自查（DC-08 口径：以 `git status` 全量为准、含报告自身；盘符/仓库名/用户名占位化）；
- 命名纪律（`check-docs.mjs` 断言 ⑤）；
- 每苞独立提交、独立验证；证据文档落 `docs/evidence/`（是否入库由指挥官定）。

---

## 7. 切分建议：**分包（2 苞），不建议四项一苞**

### 建议

| 苞 | 内容 | 依赖 | 验证面 |
|---|---|---|---|
| **苞 A（对话面板包 · 新契约面）** | 项 1：新端点（`DELETE /api/ai/history/{sessionId}`）+ `aiApi.deleteHistory` + `resetSession` 扩展 + 头部按钮 + 空态小字 | **依赖裁决**（§1.5-E2/E3/E4 端点形态；§1.4 文案口径） | 后端 `mvn -B test`（XML 只增不减 + 新用例 5 条）+ 前端 typecheck/build + CDP 冒烟（项 1 断言） |
| **苞 B（对话面板包 · 纯前端小修三件）** | 项 2 复制、项 3 停止抑制、项 4 骨架屏 | **无裁决依赖**（除 §2.3 回退与否、§2.2 notice 口径两个小裁决） | 前端 typecheck/build + CDP 冒烟（项 2/3/4 断言）。**零后端改动 ⇒ 后端 XML 数不变** |

### 理由（四条，按重要性排序）

1. **契约面隔离**：项 1 是**四项里唯一新增前后端契约**的一项（新端点、新 api 函数、幂等语义、错误码、SessionGate 取舍）。把它的裁决点与其他三项混在一苞，会让"另外三项已经能交付"被"端点还没裁决"整体堵住。
2. **依赖方向单向**：苞 B 三项**完全不依赖**项 1（复制只读 `m.content`；停止抑制只碰 `onClose`；骨架屏只碰 `AiPanel.vue` 的渲染分支 + `resetSession` 三行）。反过来项 1 也不依赖 B 三项。⇒ 两苞可并行、可独立验收。
3. **验证面天然不同**：苞 A 必须跑 Maven（XML 只增不减 + 新用例），苞 B 只需前端门禁。混苞会让一次提交同时承担"后端用例数变化"和"前端行为变化"两种证据链，违反"每任务包独立可交付、独立验证"（`docs/M2-排期计划.md` 排期原则）。
4. **风险面不同**：苞 A 的风险集中在"并发删除 vs 记忆复活"（§5.2，需要裁决与登记）；苞 B 的风险是局部 UI 行为（可 CDP 直证）。把高风险机制与低风险 UI 修混在一起，不利于红队按风险级别分配攻击面。

### 若指挥官坚持一苞（记下代价）

- 可一苞，但**必须**把 §1.5 的裁决点前移到开工前（否则苞内会出现"前端已完成、后端端点未定"的半成品状态）；
- 且**建议同一苞内先落苞 B 三项（可先提交）再落苞 A**，形成"两个可验收的提交"，不要合成一个巨型提交。

---

## 8. 拟立项的 M2 行文本草案（R 编号待分配，只写内容）

### 8.1 编号事实【实测】

- `docs/M2-排期计划.md` 的**去向表**（位于「M2-T3a 闭环注记 · b) 遗留去向」节）现有编号连续且最大为 **`R-99`**（实读：`R-95`~`R-99` 为最后五条，表内 `^\| R-` 行共 **99** 行）。
- 登记体例（实读两处登记注记）：**只追加**，紧接既有末行之后、表体闭合空行之前，既有行**零改动**；引用一律用占位符与抽象篇名，不含实盘路径与仓库名（`docs/M2-排期计划.md` 的「阻塞审计登记注记」/「等待治理排查登记注记」/「批 A 红队观察项登记」三节）。
- ⇒ **下一个可用编号 = `R-100`**（由指挥官分配；本卡只写内容）。

### 8.2 行文本草案 — 苞 A（项 1）

> `| R-??（对话面板包 · 苞 A · 新契约面，来源：AI 对话面板现状盘点 + 页签会话业界调研 + 指挥官设计定案）\`components/AiPanel/AiPanel.vue\` 头部**无「新建对话」入口**（\`AiPanel.vue:14-23\` 仅「收起」按钮；全 frontend 无清空/新建相关文案）；\`stores/ai.ts#resetSession:1077-1102\` 为**无调用方半成品**（唯一调用者 \`scripts/m2t2a-smoke.mjs:406\`），只清前端并换 id，**不删服务端记忆** ⇒ \`chat:mem:<old>\` 一直泄漏到 \`app.ai.session-ttl\`（6h 滑动）；**且"换 id"本身从未生效**——全文对 \`this.sessionId\` 的赋值命中数为 0（\`:1090\` 只写 \`sessionStorage\`），\`send:695\` 仍带旧 id 请求 ⇒ 新对话实际继承旧记忆（实测缺陷，从未暴露在用户可见路径）；\`RedisChatMemoryRepository#deleteByConversationId:132-135\`（DEL + SREM）**全仓无调用方**；\`AiController.java\` 端点全集**无任何 DELETE**（\`GET /history/{sessionId}\`@500 为记忆唯一读侧）；空态（\`AiPanel.vue:108-119\`）无页签语义告知 | **拟立项（苞 A）**：面板头部右侧加**中文文字按钮「新建对话」**（对话为空置灰、**无二次确认**；不取 \`×\`/\`+\`/垃圾桶类图标，避免"关闭 vs 重开"误读），点击 = **立即清屏 + 换新 \`sessionId\` + 调新端点删旧会话记忆**；端点拟 \`DELETE /api/ai/history/{sessionId}\`（与既有读侧同资源异动词；**无鉴权**（DC-10）；**不查 AI key**、Redis 不可用 503 \`AI_REDIS_UNAVAILABLE\`；**删除不存在也 200**（幂等，不 404，对齐 \`/cancel\`@325-344 先例）；实现走官方 \`ChatMemory#clear\` ⇒ 落到该死代码行，**不改 \`AiController\` 构造器**以避免 5+ 处测试调用点 churn）；空态补一行页签语义小字（**只陈述事实、禁隐私承诺**：不得出现 Temporary/隐私模式/不会记住 类表述）；\`resetSession\` 内**先** \`abort\` + \`POST /api/ai/cancel/{runId}\`（best-effort）**再**删记忆、**最后**换 id，并**同批修复**"换 id 未同步 state"的实测缺陷（补 \`this.sessionId = nextId\` 双写）。**已知代价（登记）**：与在跑轮次的 \`convergeMemory\`（\`ResilientChatService.java:467/508/530\` → \`:576-592\` 的 \`clear+add\`）**在飞路径下必然**发生收尾重写（复核 F2 订正：原写"毫秒~秒级竞态"偏轻，实为顺序必然 —— \`cancel\` 只是标志 + 唤醒、收盘异步，\`cancel→delete\` 顺序不缩小该窗口），旧 id 的记忆**会被**写回并残留至 6h TTL（**用户不可见**：旧 id 已被前端丢弃）；**明确否掉**端点内 409 硬拒绝（会把"新建对话"变成可失败动作）。端点定案（方法/路径/返回体/是否报告 \`runActive\`/是否顺带删 \`ai:session:<id>\`）**待指挥官裁决**。详见施工卡 \`<对话面板包施工卡>\` §1 与 §5 |`

### 8.3 行文本草案 — 苞 B（项 2/3/4）

> `| R-??（对话面板包 · 苞 B · 纯前端三件小修，来源同上）① 消息气泡**无逐条操作**——全 \`frontend/src\` 无 \`clipboard\` / \`navigator.clipboard\` 命中，气泡只有渲染（\`AiPanel.vue:121-257\`）；② \`stores/ai.ts:267\` 的 \`stopped\` 是**只写字段**（\`:1050\` 置位 / \`:651\` 复位，**零读取点**）⇒ 点「停止」后本轮 \`onClose(terminal=false)\`（\`:670-686\`，abort 分支见 \`api/sse.ts:372-379\` 不做 tryReattach）仍置 \`streamInterrupted\`，**误闪红条**「连接中断，结果可能不完整」+「一键重挂该轮」（\`AiPanel.vue:74-86\`）；③ \`stores/ai.ts:265\` 的 \`historyLoaded\` 在 \`:437/447/476\` 置位但 **\`AiPanel.vue\` 零消费** ⇒ 刷新对账期无骨架屏 | **拟立项（苞 B）**：① 用户/AI 消息 hover 出现「复制」（\`navigator.clipboard.writeText\`），复制内容 = \`m.content\` **正文纯文本**（**不含**思考链 \`m.reasoning\` 与工具卡 \`m.toolRuns\`/\`m.pendingCall\`；不 trim，因正文按 \`whitespace-pre-wrap\` 渲染）；因 \`vite.config.ts:23-24\` 为 \`host:0.0.0.0\` 存在经内网 http 访问的非安全上下文面（\`navigator.clipboard\` 为 undefined），**建议保留约 10 行 \`document.execCommand('copy')\` 回退**，且"已复制"反馈用**组件局部状态**而非单槽位的 \`ai.notice\`（\`:256\`，排期已登记瑕疵②）；② 三处 \`onClose\`（\`send:674\` / \`reattachActive:819\` / \`resumeSnapshotStream:608\`）统一加 \`&& !this.stopped\` 抑制，**不影响真断流路径**（真断流时 \`stopped=false\`，红条与一键重挂照常）；③ 消息区消费 \`historyLoaded\`：\`el-skeleton\`（\`!messages.length && !historyLoaded\`）与 \`el-empty\`（\`v-else-if\`）互斥，并在 \`resetSession\` 清理块补 \`historyLoaded = true\`（防"新建对话后又长骨架屏"）。三项**零后端契约、零新依赖**；验收 = \`yarn typecheck\` + \`yarn build\`（CI 同口径）+ CDP 冒烟（建议**新增**独立 \`scripts/chat-panel-smoke.mjs\`，沿用既有冒烟的 \`$pinia\` 直读 + \`data-testid\` 定位手法，\`m2t2a-smoke.mjs:404-407\`；前端测试框架引入**仍随业务裁决**，本包不代决）。详见施工卡 §2/§3/§4 |`

### 8.4 若指挥官选择"四项一苞"

把 8.2 与 8.3 合并为一行（编号只占一个），并把 8.2 末尾的"端点定案待裁决"与 8.3 的验证面并列写入处置列；**不建议**，理由见 §7。

---

## 9. 未决清单（提交指挥官裁决）

| # | 待裁决项 | 本卡建议 | 影响面 |
|---|---|---|---|
| 1 | 项 1 后端端点：方法/路径（`DELETE /api/ai/history/{sessionId}` vs `DELETE /api/ai/sessions/{sessionId}`） | `DELETE /api/ai/history/{sessionId}`（与读侧同资源异动词） | 苞 A |
| 2 | 项 1 端点返回体形态（裸 Map vs `ApiResponse` 信封）与字段集 | 裸 Map，字段见 §1.5-E4 | 苞 A |
| 3 | 项 1 端点是否与 `SessionGate` 配合 | **不硬拒绝**；仅在返回体报告 `runActive`（候选②）或干脆不查（建议基线）；**否掉 409** | 苞 A |
| 4 | 项 1 是否顺带删 `ai:session:<id>` / `ai:session:current:<id>` | 均不删（各有 TTL；删 run 索引会削弱取证面） | 苞 A |
| 5 | 项 1 空态小字口径（A 保守版 / B 精确版） | A：「对话保存在当前浏览器页签中，关闭页签即结束。」 | 苞 A |
| 6 | 项 2 是否需要 `execCommand` 回退 | **要**（LAN http 非安全上下文真实存在） | 苞 B |
| 7 | 项 2 "已复制"反馈载体 | 组件局部状态，**不占** `ai.notice`（单槽位瑕疵②） | 苞 B |
| 8 | 项 3 三处 `onClose` 是否全改 | **全改**（一致性） | 苞 B |
| 9 | 项 4 是否给骨架屏加 `data-testid` | 加（便于冒烟断言） | 苞 B |
| 10 | 切分：2 苞 vs 1 苞 | **2 苞**（苞 A 待裁决先/苞 B 可立即施工） | 全局 |
| 11 | 是否借本包引入前端测试框架（vitest/playwright） | **不引入**（已有登记待业务裁决，本包不代决） | 全局 |
| 12 | R 编号分配 | 建议 `R-100`（苞 A）/`R-101`（苞 B）；实读当前最大 = `R-99` | 台账 |

### 9.1 非裁决项 —— 但必须单列留痕的实测缺陷

| # | 事实 | 处置 |
|---|---|---|
| D-1 | `resetSession` 的"换 id"**从未生效**：`stores/ai.ts` 全文对 `this.sessionId` 的**赋值命中数 = 0**（`:245` 为 state 初值），`:1090` 只写 `sessionStorage`，6 处 `this.sessionId` 命中全为读取（`:360/423/435/490/695/1089`）⇒ `send:695` 仍带旧 id ⇒ "新对话"继承旧记忆 | **必修**（苞 A，两行改动，见 §1.2「第一位缺陷」）；**不属设计变更**，属"接线前必须发现并修掉的既存缺陷"，须在苞 A 施工报告里**单列一条**并给出修复前后证据 |
| D-2 | `AiController` 构造器被 ≥5 处测试直接 `new`（`AiControllerRunsCurrentTest:166`、`AiControllerHealthSseDeliveryTest:72`、`AiControllerFrontendResultTest:141`、`conformance/SessionBusyFrameIsolationConformanceTest:103` 等） | **约束**（非缺陷）：新端点实现**不得**新增构造器参数（只用已注入的 `chatMemory`/`redisAvailability`/`runStore`），否则上述调用点全断 |
| D-3 | `backend/target/surefire-reports/` 现存 **1 个 XML / 3 个用例** + 多个 `.dumpstream` ⇒ 该产物**不是**一次完整跑测 | **约束**：不得据此当测试基线；苞 A 开工先 `clean test` 实跑取基线（§6.1） |
