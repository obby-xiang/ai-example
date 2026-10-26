> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1596` 的 wire 末条 assistant 文本（消息序号 m=172；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-23 18:46 → 2026-10-23 19:02（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`dd88c58`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：11206 B / 7281 字符 / 87 行；行指纹覆盖率（本批复算）4.8%（最高单文件 5%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-091-设计-一、任务元数据核对-45653e92-agent-1747.md`）
> - 类别：**施工复核**
> - 与既有件关系：MT-091；报告口径覆盖率 0.05
> - 脱敏：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
L-91 施工完成。以下是给主会话的交付报告。

## 1. 后端：分支与提交

- worktree：`<LOCAL_WORK>/_w-reason`，分支 **`w/reason-l91`**（基点 `dd88c58`）
- commit：**`30c1a6e`** `feat(ai): 思考段（reasoning）帧通路 —— delta 增可选 kind + 复用既有段边界（L-91）`（提交信息含「技术核查」与「语义变更」逐条小节）
- 改动文件（7 件，均 `backend/`）：
  - `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:761`（新增 `reasoningDelta` / `reasoningEnd`；`:816`/`:827` 的 `textStart`/`textEnd` 先闭合思考段）
  - `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1440`（`consume` 出思考帧）、`:1488`（`chunkReasoning`）、`:262`（`REASONING_METADATA_KEY`）、`:1605`/`:1649`（`Accumulator.reasoning` / `emittedAny` 纳入思考）
  - `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:431`（种子模型 `supports_reasoning=true`）
  - `backend/src/test/.../conformance/FrameContract.java:357`（新增 `reasoningSegmentContract`，并让 `textSegmentContract` 只数正文 delta）
  - `backend/src/test/.../conformance/FrameContractFalsificationTest.java`（+5 条：4 负向 fixture + 1 正向控制）
  - 新增 `backend/src/test/.../run/ReasoningContentUpstreamProbeTest.java`、`backend/src/test/.../run/SseChatEmitterReasoningFramesTest.java`
  - `backend/src/test/.../conformance/ChatTurnFrameSequenceConformanceTest.java`（+2 条编排层用例）

## 2. 帧契约扩张方案：**additive 可选字段，不新增帧型**（16 → 16）

形态：思考段 = **既有三段式 + delta 侧一个可选键**

- `message_start/message_end`：沿用已存在的 `kind`（`SseChatEmitter#messageBoundary` 早已支持 `reasoning`，契约里一直有、只是没人调）；`message_end` 带 `chars` 与正文段同口径。
- `delta`：新增**可选**键 `kind`。**缺省 = 正文**（`delta(String)` 一字不动，正文 delta 键集合逐字不变，旧客户端/旧归档零影响），`kind:"reasoning"` = 思考增量。

为什么不新增 `reasoning` 帧型（尽管前端早已有 `case 'reasoning'` 兜底）：帧冻结的代价全部落在机制面 —— `FrameTypeSetFrozenTest` 是**双端源码扫描比对**（后端 `frame("...")` 字面量 vs 前端 `SseFrameTypes`），而我的前端改动不在 worktree 里（CI 里两侧同源，但本仓的守卫读的是 worktree 的 `frontend/src/types/sse.ts`）；一旦 16→17，桩、conformance 白名单、frozen 集、若干 exact-types 断言都要同步，收益仅是"少一个可选键"。而 `kind` 位在边界帧上已存在，补 delta 侧同一位是最小扩张。**段归属由帧上显式的 `kind` 决定**（不靠"当前段状态机"），前端分流无歧义。

## 3. spring-ai 1.1.7 是否吞 `reasoning_content`：**不吞（已双证据核实）**

- 字节码（`~/.m2/.../spring-ai-openai-1.1.7.jar`，javap）：`ChatCompletionMessage` 组件 9 = `reasoningContent`，注解 `@JsonProperty("reasoning_content")`；`OpenAiChatModel#lambda$internalStream$6` 构造 `Map.of(..., "reasoningContent", choice.message().reasoningContent(), ...)` 交给 `buildGeneration`，后者 `AssistantMessage.builder().properties(map)`。
- **可执行证据**（新用例 `ReasoningContentUpstreamProbeTest`，2 例绿）：① 线格式 JSON 反序列化后 `delta().reasoningContent()` 保留；② mock `OpenAiApi#chatCompletionStream` + 真实 `OpenAiChatModel#stream()`，`ChatResponse.getResult().getOutput().getMetadata().get("reasoningContent")` 就是分片思考文本，正文分片为空串。
- 结论：**不需要自定义反序列化、也不需要自建原始流处理**；且"本片无 reasoning"用空串占位（不是缺键）—— 所以 `reasoningDelta("")` 必须不发帧（已实现并由用例锁定）。该用例同时是升版守卫。

## 4. 红测证据（先红）

- `mvn -o -Dtest=SseChatEmitterReasoningFramesTest,ChatTurnFrameSequenceConformanceTest test` → `COMPILATION ERROR ... 找不到符号: 方法 reasoningDelta(java.lang.String)`（6 处），即缺口本身的形态。
- 实现后同一组用例 2 轮修红（`chars` 断言按实际字符数校正：`先想`+`一步`=4、`答案是 2`=5；`atLeast(0)` 捕获"零帧"），最终全绿。

## 5. 门禁数字

| 面 | 命令 | 结果 |
|---|---|---|
| 后端 | `_w-reason/backend`：`rm -rf target/surefire-reports && mvn -o -s maven-settings.xml test` | **258 个测试类 / 1698 例，0 失败 0 错误 0 跳过，BUILD SUCCESS**（TEST-*.xml 求和） |
| 前端单测 | `frontend && node node_modules/vitest/vitest.mjs run` | **113 文件 / 1386 例全绿** |
| 前端类型 | `TMP=TEMP=<LOCAL_WORK>/_tmpx node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **0 错** |
| 新增用例 | `tests/ai-reasoning-frame.spec.ts` | 10 例绿 |

> 第一次全量前端跑出现 1 例 `tests/r2-midstream-refresh.spec.ts` 5s 超时（与后端全量并行跑、机器负载高）；单独跑 1.4s 通过，静默重跑全量 1386 例全绿 —— 判为负载抖动，不是本批引入。`e2e/` 不在 tsconfig 内，S-15 改动我另用 esbuild transform 做了语法校验（OK）。

## 6. 主仓未提交前端改动清单（未做任何 git 写操作，HEAD 仍 `dd88c58`）

| 文件 | 我的改动 |
|---|---|
| `frontend/src/types/sse.ts:198` | `SseDeltaFrame.kind?: 'text' \| 'reasoning'`；改写 3 处"后端今天不产 reasoning 帧"的过期注释（边界帧注释、`SseReasoningFrame` 改为旧流兜底、判别联合注释） |
| `frontend/src/stores/ai.ts:4347` | `case 'delta'` 按 `kind` 分流：`reasoning` 投影 vs `content` 投影；`case 'reasoning'` 保留为旧流兜底 |
| `frontend/src/utils/parts.ts:517` | `case 'delta'` 按 `kind` 选 `openStreamPart('reasoning' \| 'text')`；覆盖帧集注释同步 |
| `frontend/src/types/ai.ts` | `ReasoningPart` / `ChatMessage.reasoning` 的"后端不产出"注释订正 |
| `frontend/src/components/AiPanel/AiPanel.vue:2946` | `reasoningSummary` 改走词条（原本是中文硬编码，属"死位变活"的 i18n 收口）；渲染结构未动（`reasoning-toggle-*` / `-body-*` / `-scroll-*` 早已存在） |
| `frontend/src/locales/zh-CN.json:107`、`en-US.json` | 新增 3 键 `ai.panel.thinkingChars` / `thinkingExpand` / `thinkingCollapse`（双 locale 对称、占位符一致） |
| `frontend/e2e/10-srp.spec.ts:391-441` | S-15 定责文案 + 两处**判据面**修正（见下） |
| `frontend/tests/ai-reasoning-frame.spec.ts` | 新增（解析 4 例 / store 3 例 / 渲染 3 例） |

⚠️ 同文件里另有他 agent 的在途改动：`AiPanel.vue`、`stores/ai.ts`（我的是上面那几处局部 hunk，未触碰其余）；`FormRenderer.vue`、`form-scope.ts`、`11b/11c`、`scripts/`、`.github/` 我**一律未碰**（`scripts/ci/stub-upstream.mjs` 现显示 modified = 另一 agent 的改动）。

### S-15 判据的两处"不可满足条件"修正（不是放宽，请复核）

原写法在缺口补上后仍会红，属判据面错误：
1. ① 直接断言 `reasoning-scroll` 可见 —— 但思考块**默认折叠**（`blocks.ts` 对纯 reasoning 段 `defaultExpanded=false`），旧写法恒假。改为：先断言 `reasoning-body` 隐藏 → 点开 → 断言可见且思考文本非空。
2. ③ 用 `assistantText(page)`（= 整条气泡 `innerText`）判"思考不混入正文" —— 思考卡渲染在气泡**内部**，该判据必然恒假。改为只看正文块 `[data-testid^="msg-bubble-"].w-full .md-body`，并**增加** `prose.length > 0` 断言（比原来更强，防真空通过）。

失败信息已改为双定责：先看后端本轮流里有没有 `delta{kind:"reasoning"}`（帧出口已就绪），再确认真模型本轮是否真返回 `reasoning_content`；文末标注**真模型验证待 CI e2e-real key**（本机无 key，不 SKIP、未放宽）。该条本轮未实跑。

## 7. 桩侧需要补的形态（`scripts/ci/stub-upstream.mjs`，我未改）

现状：`chunk(delta, finishReason, model, usage)` 组装 `choices[0].delta`，思考字段没有出口。需要：

1. **新轮型/开关**（建议加进 `SHAPES`，例如 `reasoning=<chars>`，默认 0 = 关闭，避免既有精确帧数断言漂移）：在正文分片**之前**插入 1–2 个分片 `chunk({ reasoning_content: '<N 字思考文本>' })`（OpenAI 兼容线格式，key 必须是 snake_case `reasoning_content`；spring-ai 映射到 metadata `reasoningContent`）。
2. 收尾顺序：`[{role}, {reasoning_content}, {content…}, {}, finish_reason]` —— 后端会在首个思考片自动补 `message_start{kind:"reasoning"}`、正文首片前补 `message_end{kind:"reasoning",chars}`，桩自己**不发**任何 SSE 帧（桩只发上游 chunk）。
3. 建议留 `reasoning` 与 `huge-frame` 的正交组合，用来覆盖单帧思考文本超 64KB 的字段级截断面（当前与正文同一套 `FrameSizeLimiter`）。
4. 需要新 `turn-*` 用例时：断言 `delta` 计数会 +N（思考片数），既有 `delta ≥ 1` 类断言不受影响；**任何断言 exact `delta` 条数**的桩用例需先把该开关关掉或改判据。
5. `@stub` 版 S-15 可行（思考块渲染与折叠不依赖真模型）；`@real` 版仍需 CI key。

## 8. 遗留问题 / 需主会话裁决

1. **ADR-4 回填仍未落地**（本批范围外，但相关）：`ResilientChatService#finish`（`:824` 附近）写记忆时是 `AssistantMessage.builder().content(text)`，**不写** `metadata.reasoningContent`；而 `ReasoningAwareOpenAiChatModel#createRequest` 靠读这个键回填多轮请求（注释称 deepseek 缺失会 400）。本批只做了"响应侧透传"，没有做"请求侧回填数据的持久化"。若要顺手补齐，改动点是 `Accumulator` 保留思考全文 + `finish` 带 properties 入库（会让思考文本落 Redis 记忆，需评估体积/隐私，建议单独一条挂账）。
2. **思考文本不走 DLP**：`OutputGuard` 只作用于正文（既有口径），思考文本不过脱敏闸。若产品上思考也会被看到，需要单独裁决是否纳入。
3. **思考 delta 的单片 64KB 截断**：与正文同一套 `FrameSizeLimiter`；`message_end.chars` 用的是发出前长度（与正文段现有口径一致，不是本批引入），极端大思考片会出现"帧声明 chars > 实际收到文本"。若在意，需两段一起改。
4. **E2E-S15 未实跑**：本机无 key；`@stub` 版可等桩补好 reasoning 形态后由主会话安排。集成时请把 `frontend/e2e/10-srp.spec.ts` 的两处判据修正纳入评审（理由见 §6）。
5. **worktree 的 `frontend/` 仍是基点版本**：`FrameTypeSetFrozenTest` 读的是 worktree 副本，因帧型未变所以两侧仍一致；集成后请跑一次主仓 backend 全量确认（我按约束没在主仓跑 mvn）。
6. 在跑的 8081/5202 未受任何影响（未启停、未占用）；`supports_reasoning=true` 只对 CI 新库种子生效，旧库那行不会自动刷新（`AiConfigSeeder` 只重灌 `ENV_SEED` 行且不改该列已有值 —— 如需存量库也置 true，要一条 UPDATE 或届时走设置页）。
