# 补档：去超时批 — 复核确认（DS-Flash）

- **归档来源（已脱敏）**：`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1261/wire.jsonl` 末条 assistant 正文（逐字提取，仅脱敏本机路径与会话 id）
- **归档日期**：2026-10-20
- **角色**：复核确认（explore · 只读）；批次 = HITL 去超时 + 人机预算分离 + 会话 24h 滑动 + 卡面一致性（**收口修之后**的复核）
- **自证**：自身 wire 的 `profile.bind` 单行首部 = `{"type":"profile.bind","agentId":"agent-1261","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high"`
- **存档说明**：正文结论「打回（单点机械项，非代码缺陷）」的唯一红项 = `check-docs` ⑤ 因被删测试未入索引；提交时按该报告所载修法 `git rm` 后 **6/6 绿**，其余结论与锚值原样保留。

---

## 结论：**打回（单点机械项，非代码缺陷）**

代码三点全部到位、`mvn`/`yarn test`/`typecheck` 全绿；唯一红项是 **P2-2 `check-docs` ⑤ 仍红**：被删的 `RunStoreTtlAbsoluteCapTest.java` 只在工作树消失、未入索引，脚本按 `git ls-files`（`scripts/ci/check-docs.mjs:83`、`:455`）读到不存在的路径 → `REAL_EXIT=1`（5/6）。修法零代码：`git rm backend/src/test/java/com/example/configmgr/ai/run/RunStoreTtlAbsoluteCapTest.java` 后复跑即 6/6（我受只读约束未执行）。除此之外无阻塞项。

## 1) P1 传输层死线

`AiController` 两处都真拆了：`:192`（chat）与 `:313`（reattach）均为 `new SseEmitter(NO_TRANSPORT_DEADLINE)`，`:861` 定义 `= -1L`，`budgetMillis()` 方法整体删除，全仓再无 `new SseEmitter(budget…)` 的 AI 通道用法（仅任务通道 `TaskSseService.java:74` 保留自己的 `emitter-timeout`）。保险丝没拆：`application.yml:138` `total-budget: 300s` 未动，判据仍在 `StreamWatchdog.java:140-146`，并且改成「墙钟 − 本尝试新增挂起等待」；`StreamWatchdogGateWaitBudgetTest` 3/3 锁住「跨一个整预算不误判 TOTAL_BUDGET / 无等待时预算语义不变」。兼容层 `AiControllerSseLifetimeTest` 2/2 用 50ms 短路预算验证 `getTimeout()==-1` 且 `onTimeout` 不触发。

前端两处非终态断流分支（`frontend/src/stores/ai.ts:1466-1474`、`:1642-1653`）已删掉 `activeForm = null`，只置 `streamInterrupted/lastRoundOutcome/notice`；`git diff` 对应 `-` 行确认为本批删除。新用例是真的：`ai-panel-parts-render.spec.ts:5298`（GF 挂起 → onClose terminal=false → 断言 `activeForm.toolCallId/status` 与段 `pending/requiresAction` 仍在）与 `:5330`（确认卡同型）都走 `store.send()` 真路径，不是直摆 store。唯一缺口：**重挂路径（:1642-1653）无对应用例**，两例只覆盖 `send`；代码同构、`finally` 只复位 `loading/suspended`，故判观察项非阻塞。

## 2) P2-3 / P2-5 / P2-4

- **P2-3**：`RunStore.java:285-286` `sessionCurrentTtl()` 已改为 `properties.getSessionTtl()`（不再读 10m 的 `session.lock.ttl`），`application.yml:107` `session-ttl: 24h`；写入（`:216`）与心跳续期（`:236`）同源，`RunStoreSlidingTtlTest` 5/5 含「会话有效期 6h→24h」「心跳整额养满 24h」。采用裁决允许的「索引 TTL 与会话对齐」而非启动扫描补锚，符合二选一。
- **P2-5**：`SseChatEmitter.java:663-667` 改为 `if (timeoutSeconds != null)` 才写 `timeoutSeconds/expiresAt` 两键，`confirmRequest` 路径本身不带；锁在 `SseChatEmitterFramesTest:82-104` —— 确认卡与 `timeoutSeconds=null` 的 GF 帧均 `doesNotContainKey` 双键，有时限的前端工具仍校验 `expiresAt−atMs == timeoutSeconds*1000`。`SseChatEmitterWriteBudgetTest:221` 亦有同型断言。
- **P2-4**：`AiPanel.vue:356-369` 只读 GF 卡体新增 `mx-[11px] mt-[9px]` 包裹（`:data-testid="ro-form-' + toolCallId"`），与 demo `docs/demo/chat-ux/card-consistency-fix.html:179` 的 `data-form-fields` 口径一致（demo 把 mx/mt 写在滚动盒上，代码写在外层包裹、内层仍是 `min-w-0 max-h-[min(320px,45vh)]`，视觉等价）；`FormRenderer` readonly 仍去自带描边。类名锁在 `ai-panel-parts-render.spec.ts:1363-1367`。

## 3) 门禁抽查（实测）

```
mvn -o -q -f backend/pom.xml -Dtest='AiController*Test,SseChatEmitter*Test,ConfirmGate*Test,RunStore*Test,StreamWatchdog*Test' test
  → MVN_EXIT=0（33s；新增类：AiControllerSseLifetimeTest 2/2、StreamWatchdogGateWaitBudgetTest 3/3、RunStoreSlidingTtlTest 5/5）
cd frontend && yarn test | tail -3
  → Tests 490 passed (490) / 24 files，FE_EXIT=0  ← 与「应 490」一致
cd frontend && yarn typecheck  → Done，TC_EXIT=0
node scripts/ci/check-docs.mjs → ①②③④⑥ PASS，⑤ FAIL（REAL_EXIT=1）：
  RunStoreTtlAbsoluteCapTest.java => 按 git ls-files 清单读取失败（文件缺失或被删除）
```

## 入库锚（候选）

| 项 | 值 |
|---|---|
| HEAD | `0a5d9cd` |
| `git diff \| sha256sum` | `3d67e2f1cc79c886d527c16d539335d663748074d03cd26667fd23aaa343d9bd` |
| 未跟踪 `RunStoreSlidingTtlTest.java` | `7c097b761752c8147ed2f46fead544baf167cf6ceb91236935bb9e2e127c5ca6` |
| 未跟踪 `StreamWatchdogGateWaitBudgetTest.java` | `1a3cf843cd4e80ae9ab1c42462739f703d7da2ddea5a4cc96f770d1a08e0cf53` |
| 未跟踪 `AiControllerSseLifetimeTest.java`（本批新增） | `e0b4dd8d20648b50f18fd588951ab4c9d3b8522a22d71cad39d00b1627329a17` |
| 未跟踪 `docs/demo/chat-ux/card-consistency-fix.html` | `937518d725620602eb729b10cdd911154d144570058fc2dd98d1c24eb059e2c5` |
| 已删（HEAD blob sha256） | `RunStoreTtlAbsoluteCapTest.java` = `455c84844017341b7c02092e6361c61bd3bfcaa81ef19adcd5b6c500498dcf56` |

未跟踪共 5 条，除上列 4 条外仅 `data/`（按要求排除）。相对上一锚 `4b3aad08…` 的唯一增量 = 新增 `AiControllerSseLifetimeTest.java` + 工作树改动（`frontend-tools.ts`、`format.ts`、`SpreadGrid.vue`、`types/sse.ts` 等前端碎片）。

## 残留观察（不阻塞，供入库记录）

- `docs/03-技术方案文档-v2.1.md:682` 仍写「会话 TTL 绝对上限已闭环（实现绝对上限）」并点名已删的 `RunStoreTtlAbsoluteCapTest`，与新实现（滑动 24h、无绝对封顶）相反；P3 要求的 2026-10-20 行尾注未补（该文件惯例「只追加」）。
- P3 其余漂移已清：`AiHealth.confirmTimeoutSeconds`、`TASK_STATUS_LABELS`、`stores/ai.ts` 双写 `clearGateSubmission`、`ConfirmGate` 「裁决第 6 条」错编号均已消失。

## 自证行

`<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1261/wire.jsonl` 第 5 行，原样首部：

```json
{"type":"profile.bind","agentId":"agent-1261","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, an interactive general AI agent …
```

全程只读：未改/未 commit/未起停服务（仅跑 `git diff`、`git ls-files`、`sha256sum`、`node check-docs`、`mvn test`、`yarn test`、`yarn typecheck`）。
