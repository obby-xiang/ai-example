# 施工报告 · 上下文统计与压缩专项 批②③（L2 滑窗 / 递归摘要 / 熔断 / L3 兜底）

## 0. DC-19 执行者自证

| 项 | 值 | 取法 |
|---|---|---|
| 执行者 | 施工官 subagent（`agent-1364`） | 主会话派单 |
| modelAlias | `DeepSeek/deepseek-flash` | `~/.kimi-code/sessions/<session>/agents/agent-1364/wire.jsonl` 的 `profile.bind` 单行 |
| profileName | `coder` | 同上 |
| thinkingEffort | `high` | 同上 |
| 授权来源 | 用户 2026-10-21 指令「前序的任务直接实施」 | 主会话派单原文 |

- 工地：worktree `<repo-parent>/wt-ctx-b23`（分支 `feature/ctx-b23`，基线 `a5539c2`，未 commit）。
- 设计依据：`docs/research/设计-上下文统计与压缩专项-2026-10-21.md` §3.5 / §5.4 / §5.5 / §6.4。
- **帧契约冻结**：本棒不新增/不修改帧类型与字段。`context` 帧键集仍为
  `{level, before, after, turns, lostTokens, reason, est, savedTokens, clearedPairs}`，
  只是 `turns` / `lostTokens` 从"恒 0"改为如实值（L1 仍 0，见 §3 自决 2）；`done.context` 六键不动。
- 前端零改动：压缩提示条渲染属批④（与 i18n 文案 key 化同棒），本棒不碰 `AiPanel.vue` / `stores/ai.ts`。

## 1. 施工范围（批② 与批③ 的分界）

设计稿 §6.4 的批② = 「L1 工具结果清除 + 归档 + 原子落库」。这三件**已随批① 合流**
（`ToolResultClearing`、`ContextCompactor` 的"归档 → 写 `ai:ctx:` → 调用方落快照/记忆"、
`RunStore#archive`），本仓 `a5539c2` 上已在运行。因此本棒不重复实现批②，只做批③ 的谱系与兜底：

| 项 | 落点 |
|---|---|
| Turn 切分（裁剪单位 = Turn） | `ai/context/ConversationTurns.java`（新） |
| L2 按 Turn 滑窗 + 递归摘要 | `ai/context/RollingSummarizer.java`（新） |
| 摘要模型客户端（超时 + 主模型回落） | `ai/context/ContextSummaryClient.java`（新） |
| L3 按 Turn 硬截断兜底 | `ai/context/HardTrim.java`（新） |
| 摘要熔断（三态 + 半开） | `ai/context/CompactionFuse.java`（新） |
| 上游越窗识别 → `CONTEXT_OVERFLOW` | `ai/context/ContextOverflow.java`（新）+ `ResilientChatService#classify` |
| 编排（逐级升格 / P7 / 原子回滚 / 熔断接线） | `ai/context/ContextCompactor.java`（改） |
| 熔断态持久化 | `ai/context/ContextState.java`（改：`openUntilMs`） |
| 装配 | `ai/context/ContextConfig.java`（改：摘要客户端 + 4 参 compactor；`AiModelConfig` 一行未动） |
| 文档同步 | `docs/technical-design.md`、`docs/test-cases.md`、`docs/evidence/补档-NUMSPEC-清单总账.md`、`application.yml` 注释 |

## 2. 测试矩阵（动工前冻结）

> 落点：新增测试类 / 「扩」= 只在既有测试类追加用例（不删、不降格既有断言）。

| # | 用例 | 维度 | 期望 | 落点 |
|---|---|---|---|---|
| T-01 | Turn 切分 | 前导段 + 每条 `UserMessage` 起一轮 | 段边界 = 下一条 `UserMessage`；工具回环留在本 Turn | `ConversationTurnsTest` |
| T-02 | L2 窗口（C-21） | 9 轮、keep 6 | 最旧 3 轮替换为 `compacted` 摘要对；`compactedFrom=3`；`lostTokens>0` | `RollingSummarizerTest` |
| T-03 | 摘要六项保留（C-22） | 摘要请求体 | 六个段落标记全在 + 跟随用户语言 | 同上 |
| T-04 | 递归分块（C-23） | 30 轮长历史、chunk 8K | 每块/每合并正文 ≤ chunk；请求数 = 分块 + 合并轮 | 同上 |
| T-05 | 摘要失败不静默（C-24） | summarizer 抛错 | `changed=false`、`reason=summary_failed`、消息同引用 | 同上 |
| T-06 | 摘要空产物 | 返回空白 | 同 T-05（产物不可用） | 同上 |
| T-07 | pin：未决 HITL 旧轮 | 旧轮含 protected id | 摘要前缀止于该轮之前 | 同上 |
| T-08 | L3 按 Turn 硬截（C-25） | 9 轮、keep 6 | 丢最旧 3 轮、切口在 `UserMessage` 上 | `HardTrimTest` |
| T-09 | L3 pin 下限（C-26/T-21） | 全为 pin 轮 | `changed=false`、无死循环 | 同上 |
| T-10 | 配对完整性（C-17） | L1/L2/L3 各一遍 | 每个 `tool_call.id` 恰一条响应，反向亦然 | `CompactionPairingTest` |
| T-11 | 连续 3 次失败（C-27） | fuse | 第 3 次即 `open`，`openUntil = now + cooldown` | `CompactionFuseTest` |
| T-12 | 成功清零（C-28） | 失败2→成功→失败2 | `closed`、`fails=2` | 同上 |
| T-13 | 冷却半开（C-29） | 到点 | 放一次；失败重开新窗 | 同上 |
| T-14 | 双实例偏差（DC-11） | 两个实例读同一份快照 | 都看到半开窗口（各放一次；已登记为可接受） | 同上 |
| T-15 | 熔断期 L1 可用（C-30） | `open` + 比例到 L2 | 仍只做 L1，摘要器不被调用 | `ContextCompactorTest`（扩） |
| T-16 | 逐级升格 | none→L1→L2→L3 | 每次最多一级；L2 产摘要、L3 按 Turn 截 | 同上（扩） |
| T-17 | 挂起期只 L1（P7） | protected ids 非空 | 比例到 L2 也只做 L1 | 同上（扩） |
| T-18 | 归档 / 台账 | L2 真压缩 | 归档 1 次；台账含 `turns/lostTokens/summaryChars/model` | 同上（扩） |
| T-19 | L2 失败保持原状 | 摘要抛错 | 消息同引用、无归档、`fails=1` | 同上（扩） |
| T-20 | `context` 帧载荷 | L2 | **键集不变**；`turns=3`、`lostTokens>0`、`level=L2` | 同上（扩） |
| T-21 | 摘要超时 | `summary-timeout=80ms` + 慢调用 | 抛"超时"（走熔断失败路径） | `ContextSummaryClientTest` |
| T-22 | 越窗措辞识别 | 正例 5 / 反例 4 | 只认越窗措辞，不误伤断流/500 | `ContextOverflowTest` |
| T-23 | 越窗映射 + 不重试 | 上游 400 context_length | 终帧 `code=CONTEXT_OVERFLOW`，`stream()` 只调 1 次 | `ContextFrameWiringTest`（扩） |

回归面（只加不减）：后端基线 939 / 前端基线 545 全量 + typecheck + build + check-docs 6/6 + 脱敏。

## 3. 红测留痕

### 3.1 第一轮红（骨架未实现，43 用例）

命令：

```
cd backend && mvn -o test -Dtest='ConversationTurnsTest,RollingSummarizerTest,HardTrimTest,CompactionFuseTest,CompactionPairingTest,ContextSummaryClientTest,ContextCompactorTest'
```

结果：`Tests run: 43, Failures: 5, Errors: 24, Skipped: 0`（37 / 43 红）。代表性失败：

```
[ERROR] ConversationTurnsTest.splitsTurnsAtEveryUserMessage <<< ERROR!  UnsupportedOperationException: 批③ 待实现
[ERROR] RollingSummarizerTest.replacesOldestTurnsWithSummaryPair <<< ERROR!  UnsupportedOperationException: 批③ 待实现
[ERROR] HardTrimTest.dropsOldestTurnsAndCutsAtUserBoundary <<< ERROR!  UnsupportedOperationException: 批③ 待实现
[ERROR] CompactionFuseTest.opensAfterThreeConsecutiveFailures <<< ERROR!  UnsupportedOperationException: 批③ 待实现
[ERROR] ContextSummaryClientTest.timeoutFailsFast <<< FAILURE!  （未抛超时）
[ERROR] ContextCompactorTest.advancesOneLevelPerCompaction <<< FAILURE!  expected: L1 but was: NONE
```

### 3.2 第二轮红（`CONTEXT_OVERFLOW` 识别，骨架恒 false）

命令：`mvn -o test -Dtest='ContextOverflowTest'` → `Tests run: 2, Failures: 1`，
正例断言 `Expecting value to be true but was false`（5 条正例全红），反例保持绿。

### 3.3 红→绿过程中的两处**测试自身**修正（如实披露，非降格）

| 用例 | 现象 | 处置 |
|---|---|---|
| T-04 递归分块 | 合并请求用 `=== PARTIAL SUMMARIES ===` 标记，原断言只认分块标记 ⇒ 红 | 断言改为"按存在的标记取正文，正文 ≤ chunk"（覆盖分块 + 合并两类请求，更全） |
| T-16 逐级升格首棒 | 构造的历史没有工具结果 ⇒ L1 无事可做返回 `NONE`（实现正确） | 首棒改用带工具结果的历史（`turns(6)`），断言不变 |

## 4. 自决清单

1. **不做批② 的重复实现**：L1/归档/原子落库已在批① 合流，本棒只补批③ 谱系；理由 = 重复写会与既有断言冲突且无收益。
2. **L1 的 `lostTokens` 保持 0**：批① 的 `ResumeContextCompactionTest` 已把 L1 的 `context` 帧 `lostTokens=0` 钉死（断言只升不降）。口径 = "丢掉的轮次里的 token"；L1 不折叠轮次，省下的量走 `savedTokens`（全级如实）。L2/L3 折叠/丢弃整轮才计 `lostTokens`。
3. **L2/L3 失败全原子**：摘要失败时不保留 L1 的清除效果（历史逐字不变）。理由 = 部分生效会让"模型看到的"和"记忆里的"两套历史分叉，与 SM-01 同族。
4. **挂起期判定用 `protectedToolCallIds` 非空**（P7 的可执行投影），不给 `Request` 加字段：未决 HITL 条目正是"外部未决项"的定义来源，且两个调用点都已计算好。
5. **摘要客户端直连 `ChatModel`，不挂记忆 advisor**：`ProviderRuntime#chatClient` 带 `MessageChatMemoryAdvisor`，用它会把摘要提示词写进主会话记忆。`summary-model` 空/空白 = 主模型（D3）。
6. **L3 不叠加执行 L2**：逐级升格（每次最多一级）意味着进入 L3 时 L2 已在上一轮落地；L3 = L1（幂等）+ 按 Turn 丢弃。若该轮无事可丢则如实降报 L1（不谎报 L3）。
7. **摘要分块按消息边界、摘要正文不截断**：合并到单段仍超就接受（比原文小一个量级，再截即静默截断）。
8. **熔断只被摘要尝试驱动**：L1 成功不清零（否则"摘要坏 + L1 一直可用"会让计数永远归零）。冷却到点先 `half_open` 再尝试，成功清零、失败重开窗（不升指数）。
9. **`ContextState` 新增 `openUntilMs`**（HASH 字段 `openUntil`）：设计稿 §3.4 的 fuse 状态本就点名 `openUntilMs`；属内部键族，不进帧契约。
10. **越窗归为不可重试错误**：`CONTEXT_OVERFLOW` 不进 `brokenStream()`；放在断流判定之前（越窗响应体也可能带 "Response has been closed" 字样）。
11. **双实例边界（DC-11）**：fuse 的"读 → 判 → 写回"非原子，两个实例可能各放一次半开摘要尝试；后果 = 多重试一次摘要（不丢历史、不越窗、不双写快照），用单 JVM 双实例用例钉住偏差。未起任何服务/harness。
12. **帧与前端零变更**：`context` 帧键集由 T-20 锁定；提示条渲染、文案 key 化留批④（依赖 i18n）。
13. **文档同步**：`technical-design.md` 的 L1 段改为三级谱系、`test-cases.md` 增 CTX-09..CTX-15、NUMSPEC 的"批③ 消费"标记改为"已消费"、`application.yml` 注释同步。未动任务/决策看板与 `.gitignore`。

## 5. 门禁实测

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端全量单测 | `cd backend && mvn -o test` | **975 / 975 通过，0F / 0E / 0S**（基线 939 ⇒ +36，全部为新增，无删改） |
| 前端单测 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **545 / 545 通过（29 文件）**，与基线持平 |
| 前端类型 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | exit 0（0 错） |
| 前端构建 | `TMP=<tmp> TEMP=<tmp> node node_modules/vite/bin/vite.js build` | `✓ built`（exit 0） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿** |
| 脱敏（工作树口径） | 临时 index 上跑 `scripts/githooks/pre-commit` | **0 命中**（首扫 2 处 PATH 命中已按 DC-08 换占位符：测试样例路径 + 本报告工地路径） |
| E2E / 双实例 harness | 未跑 | 按派单纪律：**不得起任何服务/harness**，运行由主会话另行组织 |

## 6. 文件清单

新增（后端主）：
- `backend/src/main/java/com/example/configmgr/ai/context/ConversationTurns.java`
- `backend/src/main/java/com/example/configmgr/ai/context/RollingSummarizer.java`
- `backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java`
- `backend/src/main/java/com/example/configmgr/ai/context/HardTrim.java`
- `backend/src/main/java/com/example/configmgr/ai/context/CompactionFuse.java`
- `backend/src/main/java/com/example/configmgr/ai/context/ContextOverflow.java`

修改（后端主）：
- `backend/src/main/java/com/example/configmgr/ai/context/ContextCompactor.java`（谱系编排 / P7 / 原子回滚 / 熔断接线 / 帧载荷写实）
- `backend/src/main/java/com/example/configmgr/ai/context/ContextState.java`（`openUntilMs`）
- `backend/src/main/java/com/example/configmgr/ai/context/ContextConfig.java`（摘要客户端 + 4 参 compactor）
- `backend/src/main/java/com/example/configmgr/ai/context/ContextBreakdownService.java`（注释与实态对齐）
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`（越窗归类；注释 none/L1/L2/L3）
- `backend/src/main/java/com/example/configmgr/ai/run/StreamViolationException.java`（`CONTEXT_OVERFLOW`）
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`（注释"已消费"）
- `backend/src/main/resources/application.yml`（仅注释）

新增（测试）：
- `backend/src/test/java/com/example/configmgr/ai/context/ConversationTurnsTest.java`（4）
- `.../RollingSummarizerTest.java`（7）
- `.../HardTrimTest.java`（4）
- `.../CompactionFuseTest.java`（5）
- `.../CompactionPairingTest.java`（4）
- `.../ContextSummaryClientTest.java`（3）
- `.../ContextOverflowTest.java`（2）

修改（测试，仅追加）：
- `backend/src/test/java/com/example/configmgr/ai/context/ContextCompactorTest.java`（+6，11 → 17）
- `backend/src/test/java/com/example/configmgr/ai/run/ContextFrameWiringTest.java`（+1，6 → 7）

文档：
- `docs/evidence/施工报告-上下文统计与压缩批23-DS-Flash.md`（本文件）
- `docs/technical-design.md`（L2/L3/熔断/边界段）
- `docs/test-cases.md`（CTX-09..CTX-15）
- `docs/evidence/补档-NUMSPEC-清单总账.md`（"批③ 已消费"标记）

未改：`docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`、前端全部文件、`AiModelConfig.java`。

## 7. 遗留与待裁

- **无待裁项**（未触发帧契约变更）。
- **E2E 增量（E2E-CTX-1..4）与双实例 harness** 未运行（纪律所限），运行由主会话组织；对应单测面已覆盖（计量属批①，L2/L3/熔断属本棒）。
- **批④**：压缩提示条渲染 + 文案 key 化（依赖 i18n 批①），本棒按设计稿留空。
- **已知偏差（已登记，非缺陷）**：fuse 跨实例无锁（DC-11，见 §4-11）；首轮路径压缩无租约（设计稿 §8 既有边界）。
