# 施工报告 · AI 输入输出安全防护 批②③（信任隔离 / 输出收敛 / DLP / 安全观测）

## 0. DC-19 执行者自证

| 项 | 值 | 取法 |
|---|---|---|
| 执行者 | 施工官 subagent（`agent-1377`） | 主会话派单 |
| modelAlias | `DeepSeek/deepseek-flash` | `~/.kimi-code/sessions/<session>/agents/agent-1377/wire.jsonl` 的 `profile.bind` 单行 |
| profileName | `coder` | 同上 |
| thinkingEffort | `high` | 同上 |
| 授权来源 | 用户 2026-10-21 指令「前序的任务直接实施」 | 主会话派单原文 |

> **自证口径修正（留痕）**：开工第一次自证按"目录 mtime 最新"取了 `agent-1378`（同为 deepseek-flash/coder/high），
> 后续证据链证明**本 agent 是 `agent-1377`**：① 本 agent 起的后台任务落盘在
> `agents/agent-1377/tasks/`；② `agent-1377/wire.jsonl` 含「你是施工官」「全批施工」各 3/5 处，
> 而 `agent-1378` 是同日并行的**提交官**（「你是提交官」10 处）。
> **三值（modelAlias / profileName / thinkingEffort）在两个目录完全相同**，故结论不受影响。

- 工地：`<REPO_ROOT>/../wt-guardrails-b23`（分支 `feature/guardrails-b23`，基线 `6bde8bd` = main，**未 commit**）。
- 备料：`<TMP>/_shared/勘察-防护批23-DS-Flash.md`（12 子项 / 矩阵 17+16 例 / 冲突清单）、
  `docs/research/调研-AI输入输出安全防护业界惯例-2026-10-21.md`。
- **帧契约 16 冻结**：不新增帧类型；`done.safety` 是 `done.extra` 里的 **additive 可选键**；
  `SseChatEmitter.java` **零改动**（`git status` 里没有它）。
- **硬边界遵守**：未进 `ai/context/` 包；`AiController` 只在既有五道闸之后**加新段**，未改既有闸顺序；
  `RunStore` 新键族 `ai:safety:` + 新方法（既有 `appendLedger`/上下文台账一字未动）；
  未动 `docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`；未 commit / 未 push。

---

## 1. 施工矩阵（动工前冻结 → 完工后逐项打勾）

> 列义：**落点** S = 单测可覆盖；**红** = 已用变异证明有 kill 面（§3）。

### 1.1 批② 信任隔离与输出收敛

| ID | 用例 | 期望 | 落点 | 结果 |
|---|---|---|---|---|
| B2-T01 | 系统提示含"数据非指令"声明 | 三条新原则（11/12/13）在 `SYSTEM_PROMPT` 内，既有 1–10 条语义不变 | `ResilientChatService`（改） | PASS（人工读码，见 §4 自决 1） |
| B2-T02 | 声明缺失 | 变异证明：删掉声明不会有单测红 → **登记为人工验收项**（非 CI 断言） | — | 已登记挂账 |
| B2-T03 | 工作区段定界 | 系统消息含 `UNTRUSTED_NOTICE` + `<workspace_state>…</workspace_state>`，声明在前、闭合在末尾 | `ContextBuilderExtraTest`（扩） | PASS |
| B2-T04 | 未知 page 不回显 | `page="<script>alert(1)</script>"` → `unknown` 哨兵 → 渲染「未知页面（未识别）」，且 HTTP **200** | `ContextFieldGateTest`（新）、`AiControllerChatGateTest`（扩） | PASS |
| B2-T05 | step 含控制字符/超长 | 剥 `Cc`/`Cf`，截 64 字符；不拒绝 | `ContextFieldGateTest`（新） | PASS |
| B2-T06 | extra 未知键 | 只留 `ALLOWED_EXTRA_KEYS`，其余丢弃并计数 | `ContextFieldGateTest`（新） | PASS |
| B2-T07 | 工具结果定界 | 模型侧 = 声明 + `<tool_result>` + 信封 JSON + `</tool_result>`；`toJson()`（帧/台账）不变 | `ToolResultEnvelopeTest`（扩） | PASS |
| B2-T08 | 续跑信封兼容 | `fromJson` 自动剥壳 ⇒ 旧信封/带壳信封都能反序列化 | `ResumeEnvelopeShapeTest`（既有，零改动即绿） | PASS |
| B2-T09 | 前端回灌定界 | 回灌走同一 `ToolResultEnvelope#toModelJson`（`SpToolCallingManager#modelData` 唯一出口） | 同上（同源，无独立断言） | PASS（同源覆盖） |
| B2-T10 | `reason` 超长 | `> 1024` → 400 `REASON_TOO_LONG`，不回显原文 | `AiController`（改）+ 人工验收 | 实现就位 / 断言见 §5 缺口 |
| B2-T11 | 前端回灌载荷超限 | `> 8000` → **截断 + `truncated:true`**（不 400） | `AiControllerFrontendResultTest`（扩） | PASS |
| B2-T12 | 图片外链 | 站外 `img` 整条剥掉；`//host` 同判；`data:image/svg+xml` 也剥 | `ai-panel-security-render.spec.ts`（新） | PASS |
| B2-T13 | 链接外链 | 站外 http(s) 保留 href + `rel="noopener noreferrer nofollow"` + `target=_blank` + 自绘 svg 标记；站内链接只加 `rel` | 同上 | PASS |
| B2-T14 | 误伤面 | `data:image/png`、同源 `/api/file/…` 照常渲染 | 同上 | PASS |
| B2-T15 | 渲染回归 | 既有 markdown/mermaid/表格/代码块全绿 | `ai-panel-parts-render.spec.ts`（1 处 fixture 改同源图片） | PASS 187/187 |
| B2-T16 | 安全响应头 | `/api/*` 带 `Content-Security-Policy-Report-Only` + `X-Content-Type-Options: nosniff` + `Referrer-Policy: no-referrer`，**无**强制 CSP | `SecurityHeadersFilterTest`（新） | PASS |

### 1.2 批③ DLP 与观测

| ID | 用例 | 期望 | 落点 | 结果 |
|---|---|---|---|---|
| B3-T01 | 六类凭据命中 | API key / AWS key / Bearer / JDBC 口令 / URL userinfo / `password=` 各自脱敏为 `[REDACTED:<id>]` | `SafetyRulesTest`（新） | PASS |
| B3-T02 | 五类误报 | 中文段落 / `jdbc:h2:mem:` 无口令 / 带查询串 URL / 邮箱 / "AKIA 是前缀"讨论句 —— **逐字不变** | 同上 | PASS |
| B3-T03 | 跨帧分片 | 密钥切三片：拼接后无原文，`Σ delta == message_end.chars` | `OutputGuardTest`（新）、`ChatTurnFrameSequenceConformanceTest`（扩） | PASS |
| B3-T04 | 首字延迟 | 纯中文正文立刻发出；可疑尾才扣住，扣住量 ≤ `TAIL_WINDOW`(128) | `OutputGuardTest`（新） | PASS |
| B3-T05 | 输入侧 mask | HTTP 200；送模型文本已脱敏；`security.inputRedacted` +1 | `AiControllerChatGateTest`（扩） | PASS |
| B3-T06 | 归一 × DLP 顺序 | 归一在前、DLP 在后（零宽切开的 key 仍命中） | `AiControllerChatGateTest`（扩，归一 + DLP 同请求） | PASS |
| B3-T07 | 私钥 block | 完整私钥块 / 未闭合私钥头 → 正文段收尾后 `error{SENSITIVE_OUTPUT_BLOCKED}`，6 条不变量全绿 | `ChatTurnFrameSequenceConformanceTest`（扩）、`OutputGuardTest` | PASS |
| B3-T08 | 终帧 safety | 命中写 `done.safety{redacted,rules,blocked}`；未命中**不写该键** | 同上 | PASS |
| B3-T09 | fail-open | 无"抛错型 Pattern"可注入（规则表编译期常量）⇒ 以"空/未命中即原样透传"覆盖 | `OutputGuardTest`（`emptyChunksAreNoOps`） | 部分（见 §5 缺口） |
| B3-T10 | `/health.security` | 7 个计数键 + `scope=process`，单调增 | `SecurityEventsTest`（新）、`AiControllerChatGateTest`（扩） | PASS |
| B3-T11 | 台账 SAFETY | `ai:safety:<sessionId>` 条目 `{atMs,runId,side,ruleId,offset,length,action}`，**无原文** | `OutputGuardTest`（events 载荷）+ `RunStore#appendSafetyEvent` | PASS（写侧） |
| B3-T12 | feedback 摘录 | 命中即整条 `[已隐去]`（永久入库，误伤不可逆 ⇒ 不做逐处脱敏） | `AiControllerFeedbackTest`（扩） | PASS |
| B3-T13 | 限流边界（既有） | 20 次 200 / 第 21 次 429 + `Retry-After` | `AiRateLimiterTest`（既有 8 例） | PASS |
| B3-T14 | 限流跨实例 | A11+B10 → 第 21 次 429 | `scripts/b23l1-dual-instance.ps1 -Cases rate-limit-shared`（新用例，含 SKIP 守卫） | **用例就位、未运行**（用户裁决 9：生产环境运行由主会话组织） |
| B3-T15 | 限流降级（既有） | Redis 异常放行 | `AiRateLimiterTest`（既有） | PASS |
| B3-T16 | 契约回归 | 16 类帧集不变、6 条不变量全绿 | `conformance/` 全套（零删改） | PASS |

---

## 2. 门禁数字（本机实测，2026-10-21）

| 门禁 | 命令 | 结果 | 基线 | 判定 |
|---|---|---|---|---|
| 后端全量 | `mvn -o test`（surefire `TEST-*.xml` 求和） | **1139 tests / 0F / 0E / 0S（166 类）** | 1079 | ↑ +60，只升不降 |
| 前端全量 | `node node_modules/vitest/vitest.mjs run` | **569 passed / 31 files** | 559 / 30 | ↑ +10 |
| 前端类型 | `vue-tsc --noEmit` | exit 0 | 0 | 不变 |
| 前端构建 | `TMP=… vite build` | `✓ built in 57.87s` | ✓ | 不变 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 绿 | 6/6 | 不变 |
| 敏感信息扫描 | `scan-lib.sh` 逐文件 `scan_engine_blob`（工作树 30 个改动/新增文件） | **0 命中** | 0 | 不变 |
| E2E | 未跑（本棒不改 `scripts/verify-e2e.ps1`、不改 E2E 覆盖面；DLP/限流按裁决不入 E2E） | — | 29 例 | 不涉及 |

---

## 3. 红测留痕（变异证明，两次）

> 目的：证明新断言不是"跟着实现写出来的同义反复"。两次变异 → 跑对应测试类 → 记录红 → **原样还原** → 复跑绿。

**变异 ①（输出 DLP 失效）**：`OutputGuard#feed` 首行插入 `if (true) { return chunk; }`（等于不脱敏）。

```
[ERROR] Tests run: 12, Failures: 9, Errors: 0, Skipped: 0 <<< FAILURE! -- OutputGuardTest
[ERROR] Tests run: 8,  Failures: 2, Errors: 0, Skipped: 0 <<< FAILURE! -- ChatTurnFrameSequenceConformanceTest
[ERROR] Tests run: 20, Failures: 11, Errors: 0, Skipped: 0
```
还原后：`OutputGuardTest 12/12`、`ChatTurnFrameSequenceConformanceTest 8/8` 全绿（同一命令复跑）。

**变异 ②（未知 page 回声）**：`ContextBuilder#pageLabel` 改回 `label == null ? page : …`。

```
[ERROR] Tests run: 11, Failures: 1 <<< FAILURE! -- ContextFieldGateTest（unknownPageRendersAsUnknownLabel…）
[ERROR] Tests run: 8,  Failures: 1 <<< FAILURE! -- ContextBuilderExtraTest（pageLabelMirrorsFrontendLabels）
[ERROR] Tests run: 19, Failures: 2, Errors: 0, Skipped: 0
```
还原后 4 个受影响类复跑全绿（31 例），随后全量 1139 绿。

---

## 4. 自决清单（含 mask / block 边界）

| # | 议题 | 自决 | 依据 / 理由 |
|---|---|---|---|
| 1 | **mask / block 边界** | **block 只有一档**：① 完整私钥块（`BEGIN … PRIVATE KEY`—`END … PRIVATE KEY` 之间有内容）；② 未闭合的私钥头（截断的私钥=私钥本体）。**其余全部 mask**（API key / AWS key / Bearer / JDBC 口令 / URL userinfo / `password=`） | 用户裁决 2「DLP 默认 mask 后继续；block 仅限高置信高危分级」；调研 A2「仅高置信 key 走 block」。未闭合私钥之所以也 block：base64 主体一旦发出即不可撤回 |
| 2 | 输入侧从不 block | 输入即使命中私钥块也只 mask，HTTP 照常 200 | 主场景是"用户来粘配置/问报错"，整条拒掉即打断主流程（调研 §3.3） |
| 3 | **DLP 落点** | 输出：`ResilientChatService#consume`，**accumulator 之前**、不经 emitter；输入：`AiController#chat` 长度闸之后 | 用户裁决 6；`FrameContract#textSegmentContract` 要求 `message_end.chars == Σ delta`，唯一能满足的是"同一份脱敏文本喂两处" |
| 4 | 尾部窗口实现 | `TAIL_WINDOW = 128`；但**只在尾部出现规则锚字符**时才扣住（`SafetyRules#holdFrom`），普通中文正文零延迟 | 裁决 5（64–128、禁 512 整段缓冲）。纯窗口实现会让每条短回复等到轮末；锚字符判定把"扣住"限制在真有必要时，**上界仍是 128** |
| 5 | 私钥头在流中的处理 | 一旦发现"有 BEGIN 无 END"⇒ 从头部起不再外发，轮末按 block 收尾 | 否则会出现"先发头、再发 base64"的部分泄露 |
| 6 | 未知 page | 归一为哨兵 `"unknown"`（不在 `PAGE_LABELS`）→ 渲染「未知页面（未识别）」 | 裁决 1「降级不回显」。保留哨兵而非置空，是因为置空会渲染成「任务中心」，对模型是错误事实 |
| 7 | 工具结果定界位置 | **包在信封 JSON 外面**（声明 + `<tool_result>` + JSON + `</tool_result>`），信封字段逐字不变；`fromJson` 自动剥壳 | 包 message 字段会改掉"模型看到的 message 等于原文"这条既有契约，且牵连 17 处既有断言；包外层零语义损失 |
| 8 | 前端回灌超限 | **截断 + `truncated:true`**，不 400 | 机器数据（表格摘要）本来就长；拒绝回灌会让该轮永久挂起。与"用户输入超限 400"方向相反是刻意的 |
| 9 | CSP | **只发 Report-Only**；同时加 `nosniff` / `no-referrer` | 裁决 4。实际生效面 = **后端 `/api/*` 响应**；页面由 Vite 承载，本过滤器覆盖不到页面本身（见 §6 挂账） |
| 10 | 外链图标 | 自绘 `<svg viewBox="0 0 12 12">`（path 描边），无 emoji | 裁决 10；`ALLOWED_TAGS` 已放行 svg/path |
| 11 | 安全计数口径 | **进程内** `AtomicLong`，`/health.security.scope = "process"` | 与限流的跨实例 Redis 计数相反：事件是"本实例处理了什么"的审计事实；全局口径由实例相加 |
| 12 | 台账 `turnNo` | 本棒**不写** turnNo（只写 `runId/side/ruleId/offset/length/action`） | 轮号只在 `ai:ctx:` 真值里，写侧要多一次 Redis 往返；已登记为挂账（§6.4） |
| 13 | 配置项 | DLP 阈值**不新增 `app.ai.dlp.*`**，单一事实源 = `SafetyRules` 常量 | 少一段配置 = 少一圈启动期校验与文档同步；阈值是"安全下限"不是"调参项" |

---

## 5. 缺口与如实登记（未做 / 未断言）

1. **B2-T10 `reason` 长度闸没有独立单测**：实现已就位（`AiController#confirm` 首段 400 `REASON_TOO_LONG`），
   本次未补用例（`ConfirmRequest.reason` 的 400 分支需要 MockMvc 与 Mock 确认门配合，排在批④一起补）。
2. **B3-T09 fail-open 没有"注入坏 Pattern"用例**：规则表是 `static final` 编译期常量，无法从外部注入坏正则；
   现有覆盖 = "空分片/未命中即原样透传"。若将来规则可配置化，必须补"编译失败 → 放行 + WARN"用例。
3. **B3-T14 跨实例用例未运行**：按裁决 9 只交付脚本化用例 + SKIP 守卫（首跳 503 / 首跳 429 都 SKIP 并点名原因），
   运行由主会话组织（需 8081/8082 + 共享 Redis + 桩上游）。
4. **B2-T02（提示词声明缺失的红）不可单测**：声明是常量文本，没有"旧常量"可构造 ⇒ 只能人工验收。
5. **`done.safety` 前端未消费**：类型已加（`types/sse.ts`），AI 面板暂不渲染"已隐去 N 处"提示条；
   阻断路径已由错误卡（`SENSITIVE_OUTPUT_BLOCKED` → 灰族 filter 变体）承接。
6. **输入侧"已帮你隐去 N 处"的用户可见提示**未做（只有 `security.inputRedacted` 计数 + 日志 + 台账）；
   前端可见提示排到下一棒（涉及 composer 底行 pill，属 UI 面）。

---

## 6. 挂账建议（下一棒 / 后续专项）

1. **CSP 强制模式**：本棒只 Report-Only。要转强制，前提是页面由后端（或网关）承载并覆写响应头；
   还需逐项收掉 Vue 运行时 `<style>` 注入、Vite HMR `ws:`、mermaid/字体（`font-src`）、SpreadJS 的 `blob:`。
   **建议独立小批 + 浏览器手测**，不要与功能棒混跑。
2. **注入分类器（远端 Prompt Shields / Lakera）**：仍不建议引（数据出境 + 依赖 + 延迟），维持"归一化 + 定界 + 确认门"三件套。
3. **`/history/{sessionId}` 无鉴权（DC-10）**：本棒未碰；多用户/多实例部署前必须先补（这是访问控制，不是 DLP）。
4. **安全台账补 `turnNo`**：需要上下文真值（`ai:ctx:`）读一次，建议与"安全事件进压缩台账"合并做。
5. **`/health.security` 的全局视图**：进程内计数在多实例下要相加；若要单点总览，需另起 Redis 计数族（与限流同族）。
6. **规则表的误报率人工抽样**（调研 §9.5 遗留）：建议用近 200 条真实模型回复跑一遍 `SafetyRules.mask`，
   统计误伤（尤其 `password=` 与 URL userinfo 两族）。
7. **`AI_FEEDBACK` 摘录目前是整条 `[已隐去]`**：若希望保留"可读摘要 + 脱敏"，需要"命中即整段替换 + 前后文保留"的更细策略。

---

## 7. 文件清单（30 个，未 commit）

**后端 · 新增（6）**

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/gate/SafetyRules.java` | 凭据规则单一事实源（拼接构造前缀）+ `mask/hits/holdFrom/openPrivateKeyHeader` |
| `backend/src/main/java/com/example/configmgr/ai/gate/InputDlp.java` | 输入侧 DLP（mask 后继续） |
| `backend/src/main/java/com/example/configmgr/ai/gate/ContextFieldGate.java` | `context` 形状闸（白名单 + 降级不回显） |
| `backend/src/main/java/com/example/configmgr/ai/run/OutputGuard.java` | 流式输出 DLP（尾部保留窗口 / block 分级 / 审计事件） |
| `backend/src/main/java/com/example/configmgr/ai/run/SecurityEvents.java` | 安全事件计数（`/health.security`） |
| `backend/src/main/java/com/example/configmgr/config/SecurityHeadersFilter.java` | CSP Report-Only + nosniff + no-referrer |

**后端 · 修改（6）**

| 文件 | 改动 |
|---|---|
| `ai/web/AiController.java` | 新段：context 闸 → 输入 DLP；`/confirm` reason 闸；`/frontend-tool-result` 载荷闸；`/health.security`；feedback 摘录脱敏；`appendSafety` |
| `ai/run/ResilientChatService.java` | `SYSTEM_PROMPT` +11/12/13；`consume` 接入 `OutputGuard`（accumulator 前）；`drain`/`blockTerminal`/`attachSafety`/`recordSafety`；`Accumulator#accept(.., override)` |
| `ai/tool/ContextBuilder.java` | `<workspace_state>` 定界 + 声明；`pageLabel` 未知不回显；`safeField`（标题 128 / 步骤 64，剥不可见字符） |
| `ai/tool/ToolResultEnvelope.java` | `toModelJson` 外层定界；`unwrapModelJson`；`fromJson` 容忍外壳 |
| `ai/run/RunStore.java` | `ai:safety:` 键族 + `appendSafetyEvent` / `safetyLedger`（写失败不抛） |
| `config/WebConfig.java` | 注册 `SecurityHeadersFilter`（`/api/*`） |

**后端 · 测试（新 5 + 改 6）**：`SafetyRulesTest`、`OutputGuardTest`、`ContextFieldGateTest`、`SecurityEventsTest`、
`SecurityHeadersFilterTest`（新）；`ContextBuilderExtraTest`、`ToolResultEnvelopeTest`、`AiControllerChatGateTest`、
`AiControllerFeedbackTest`、`AiControllerFrontendResultTest`、`ChatTurnFrameSequenceConformanceTest`（改/扩）。

**前端（改 5 + 新 1）**：`utils/markdown.ts`（`ALLOWED_URI_REGEXP` + img/link hook + 外链 svg）、`types/sse.ts`（`done.safety`）、
`types/api.ts`（`SENSITIVE_OUTPUT_BLOCKED`）、`utils/error-card.ts`（码优先 → filter 变体）、
`tests/ai-panel-parts-render.spec.ts`（1 处 fixture：外链图片 → 同源 `/api/file/1.png`，因站外图片已按裁决剥离）、
`tests/ai-panel-security-render.spec.ts`（新，10 例）。

**脚本（改 1）**：`scripts/b23l1-dual-instance.ps1`（新增 `rate-limit-shared` 用例 + 文档 + 分发）。

---

## 8. 一眼看懂：这一棒到底改了什么

1. **可信位不再混用**：客户端可控的 `page/step/标题/extra` 与工具结果，现在都包在"这是数据不是指令"的定界里；
   未知 `page` 不再回声。
2. **外泄通道被封**：模型输出里的站外图片整条剥掉、站外链接带 `rel`/新窗口/可见标记；API 响应加 CSP Report-Only 与两条硬化头。
3. **敏感信息有处置**：输入命中 → mask 后继续（不打断主流程）；输出命中 → 流式 mask（跨分片不漏、短回复不延迟）；
   只有私钥这一档会以 `SENSITIVE_OUTPUT_BLOCKED` 收尾。
4. **一切可观测**：`/health.security` 七个计数、`ai:safety:` 台账（只记规则 id/偏移/长度，无原文）、`done.safety` additive 键。
