> 来源=<仓外证据>/ai-full-audit/gaps/02-context-prompt-surface.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# 缺口分配 G2｜上下文预算 / 压缩 / 提示词装配 / 观测与错误面

- **主责域**：D1（流与帧运行面）　**副域**：D3（历史/记忆）、D5（错误面文案）
- **规模**：51 个文件（生产 11 / 测试 40）
- **为什么算缺口**：`ai/context/{ContextBreakdown,ContextBreakdownService,ContextOverflow,ContextState,ConversationTurns,HardTrim,ToolResultClearing,TurnBudgetPlan}` 与 `ai/history/{AiChatMessage,AiChatMessageRepository}`、`ai/observe/AiMdcTaskDecorator` 在 B–J 中零命名词；对应 40 个测试文件同样未被任何域评估断言强度。
- **风险定位**：D 域已把 `ContextCompactor`/`RollingSummarizer`/`MemoryWindow` 审到"位置与时序"，但**分母、预算、清空、配对**这层的实现与测试没有域级复核。K 的 MD-03/MD-04/MD-05/PK-07 都落在这里。

## 必读清单（51）

| 路径 | 类型 | 重点核查点 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/context/ContextBreakdown.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/ContextBreakdownService.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/ContextOverflow.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/ContextState.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/ConversationTurns.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/HardTrim.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/ToolResultClearing.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/context/TurnBudgetPlan.java` | 生产 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/main/java/com/example/configmgr/ai/history/AiChatMessage.java` | 生产 | 历史实体与仓储（seq 唯一/幂等键/写入失败口径） |
| `backend/src/main/java/com/example/configmgr/ai/history/AiChatMessageRepository.java` | 生产 | 历史实体与仓储（seq 唯一/幂等键/写入失败口径） |
| `backend/src/main/java/com/example/configmgr/ai/observe/AiMdcTaskDecorator.java` | 生产 | 观测锚点（MDC 传播/日志模式） |
| `backend/src/test/java/com/example/configmgr/ai/conformance/FrameWire.java` | 测试 | 夹具保真度（帧契约测试能否变红） |
| `backend/src/test/java/com/example/configmgr/ai/context/AiCompactionLogTraceTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/CompactionFuseTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/CompactionPairingTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextBreakdownServicePercentTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextBreakdownTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextEstimatorTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextMeterTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextOverflowTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextSummaryClientTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ContextTriggerTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ConversationTurnsTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/HardTrimTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/InMemoryContextStateStore.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/MemoryWindowLogTraceTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/SystemPrefixPreservationTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/TestProviderRuntime.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/ToolResultClearingTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/context/TurnBudgetPlanTest.java` | 测试 | 上下文计量/预算/裁剪与 tool 配对保护 |
| `backend/src/test/java/com/example/configmgr/ai/memory/ReasoningMetadataCodecTest.java` | 测试 | 记忆编码/删除断言 |
| `backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositoryDeleteTest.java` | 测试 | 记忆编码/删除断言 |
| `backend/src/test/java/com/example/configmgr/ai/observe/AiLogPatternConfigTest.java` | 测试 | 观测锚点（MDC 传播/日志模式） |
| `backend/src/test/java/com/example/configmgr/ai/observe/AiMdcPropagationTest.java` | 测试 | 观测锚点（MDC 传播/日志模式） |
| `backend/src/test/java/com/example/configmgr/ai/observe/AiTraceTest.java` | 测试 | 观测锚点（MDC 传播/日志模式） |
| `backend/src/test/java/com/example/configmgr/ai/observe/AiTurnLogTraceTest.java` | 测试 | 观测锚点（MDC 传播/日志模式） |
| `backend/src/test/java/com/example/configmgr/ai/run/ChatModelContractTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/DeltaFrameSplitLimitGuardTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/EmptyReplyTerminalTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/LanguageDirectiveWiringTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/LanguagePolicyTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SystemPromptHitlConfirmTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SystemPromptPlainTextTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SystemPromptUiSnapshotRulesTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SystemPromptUntrustedDataTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerDeniedLogTraceTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerErrorBodyLocaleTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHealthParamsTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHealthSheetTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHealthSseDeliveryTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerPreStreamLocaleTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |

## 必须回答的验收问题

1. `TurnBudgetPlan` 与 `SpToolCallingManager.applyTurnBudget`、`ToolActivityBeacon.budgetArmed` 的**三层预算**是否同源？armed 在 park 时被 `beacon.clear` 后的语义（PK-07）是否有补偿？
2. `HardTrim` 与 `ToolResultClearing` 的裁剪顺序与幂等占位符：会不会把"未决 HITL 保护"裁掉？
3. `ContextBreakdown(Service)` 的百分比分母是否与 `ContextLimitResolver` 同源（MD-04 的续跑丢 providerId 是否也污染这里）？
4. `ConversationTurns` 的 turn 边界判据（tool 配对、system 前移）与 `MemoryWindow` 是否一致？
5. `ContextState` 落 `ai:ctx:*` 的读写是否原子；多实例并发压缩是否可能互相覆盖（F 域只"核了非原子这一事实"，未审实现）？
6. `AiChatMessage`/`AiChatMessageRepository` 的 `(session,seq)`/`(session,run,messageId)` 唯一键与 `AiChatHistoryService` 的 32 次重试是否真的同源（MD-07 的 `hasMore` 反语义是否源于此处）？
7. `AiMdcTaskDecorator` 的 MDC 传播在平台线程池下是否真的不丢失（`AiMdcPropagationTest`/`AiTurnLogTraceTest` 是否只测了同线程）？
8. 本组 40 个测试中，哪些是"断言 mock 调用"而非"断言内容"（K §7.2 第 6 条的方法论）？逐条标注 `断言强度：强/中/弱`。

## 与 K 结论的挂接

| K 条目 | 依赖本组回答什么 |
|---|---|
| MD-03（128000 兜底） | 分母解析链是否只有一条 |
| MD-04（续跑丢 providerId） | 计量与压缩是否各自解析 |
| MD-05（摘要成用户气泡） | `ConversationTurns`/历史实体是否可识别 synthetic |
| PK-07（预算跨实例失效） | armed 标记的持有者与生命周期 |
| MD-07（hasMore 反语义） | 仓储查询的排序与切片口径 |

## 边界

- 只读；不得改代码/测试/配置。不得以本组结论替代真实 3 JVM 的并发证据。
