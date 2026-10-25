> 来源=<仓外证据>/ai-full-audit/gaps/01-tool-prompt-resultview.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# 缺口分配 G1｜工具定义 / 提示词注入 / 结果视图契约

- **主责域**：D2（门/工具/表单/表格）　**副域**：D7（提示词与结果契约，建议新设）
- **规模**：47 个文件（生产 22 / 测试 25）
- **为什么算缺口**：这批文件在 B–J 九份报告中的**词边界出现次数为 0**（`ai/tool/*`、`ai/resultview/*` 仅被 A 的清单登记）。A 自述 §0.2「L3 仅清点、未审内部」。
- **风险定位**：这是"模型输出→工具调用→结果回填→UI 呈现"的**契约层**，也是提示词注入与结果截断的唯一收口。K 的 S-10/S-11、BZ-05、PK-06 都依赖这一层的语义。

## 必读清单（47）

| 路径 | 类型 | 重点核查点 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultView.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultViewColumn.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultViewColumnType.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultViewKind.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultViewStatus.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ResultViews.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/resultview/ToolResultViewProvider.java` | 生产 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/main/java/com/example/configmgr/ai/tool/OverriddenToolCallback.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/PlainTextToolResultConverter.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/PromptDelimiters.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/SheetSwitches.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolCategory.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolChannel.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolDefinitionOverride.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolDefinitionSource.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolMeta.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolRisk.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolSchemaNormalizer.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolSchemaReconciler.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/ToolSnapshot.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/TruncationContract.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/main/java/com/example/configmgr/ai/tool/YamlToolDefinitionSource.java` | 生产 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/resultview/ResultViewFrameTest.java` | 测试 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/test/java/com/example/configmgr/ai/resultview/ResultViewTest.java` | 测试 | 结果视图重建与列类型契约（只对成功结局） |
| `backend/src/test/java/com/example/configmgr/ai/tool/ContextBuilderDelimiterInjectionTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ContextBuilderExtraTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ContextBuilderSelectedDefsTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/PlainTextToolResultConverterTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/PromptDelimitersTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/SheetAuditTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/SheetSwitchesTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolDefinitionOverridesTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolOutcomeResourceCodesTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolRegistryStartupFailureTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolResultDualChannelContractTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolResultEnvelopeDelimiterInjectionTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolResultEnvelopeTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tool/ToolResultLimiterTest.java` | 测试 | 工具定义来源/覆盖/schema 归一/截断与提示词注入面 |
| `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsThrowPathTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsToolResultPlainTextTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/GenerativeFormDisclosureTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolRegistryDisclosureTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolRegistrySheetGateOnTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolRegistrySheetGateTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolRegistryUiScopeTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolSchemaRefIntegrityTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |
| `backend/src/test/java/com/example/configmgr/ai/tools/ToolScopeTagSpaceGuardTest.java` | 测试 | 断言强度（注入/信封/截断/披露） |

## 必须回答的验收问题

1. `PromptDelimiters` 与 `ToolResultEnvelope` 的定界/中和规则，是否能被"结果里含闭标签"绕过？（配合 `PromptDelimitersTest`、`ToolResultEnvelopeDelimiterInjectionTest`、`ContextBuilderDelimiterInjectionTest` 的断言强度）
2. `TruncationContract` + `PlainTextToolResultConverter` + `ToolResultLimiter` 三者的截断口径是否一致（字符/行/字节；`KbTools` 的 `next_offset` 与 `ToolResultLimiter` 是否同尺）？
3. `ToolSchemaNormalizer` / `ToolSchemaReconciler` 对非法 schema 是拒还是改？改的情况下是否可能扩权（把未披露工具变成可调用）？
4. `YamlToolDefinitionSource` + `ToolDefinitionOverride` + `ToolDefinitionSource` 的定义来源优先级与覆盖边界：配置能否覆盖 `@ToolScope`/risk？
5. `ResultViews` / `ToolResultViewProvider` / `ResultView*` 的形状契约：失败结局是否真的不重建视图（K 的 A 段声称"只对成功结局"）？列类型越界如何处理？
6. `SheetSwitches` 与 `UiPrimitiveGuard`/`SheetWriteGuard` 的开关语义是否一致（关闭时"不披露"还是"披露但拒绝"）？
7. `ToolCategory`/`ToolChannel`/`ToolRisk`/`ToolMeta`/`ToolSnapshot`/`OverriddenToolCallback` 的枚举与元数据是否被任何执行路径当作**权限**使用（若被当权限，则是越权面）。

## 与 K 结论的挂接

| K 条目 | 依赖本组回答什么 |
|---|---|
| S-10（帧/内容静默缺失） | 截断与信封是否会把"缺失"表达成"正常" |
| PK-06（park 后无帧） | `OverriddenToolCallback`/`ToolSnapshot` 是否承担补帧职责 |
| BZ-05（影响面数字失真） | `ResultViews` 是否复用旧行数 |
| ID-06（上下文注入） | 定界与中和是否是唯一防线 |

## 边界

- 只读；不得改代码/测试/配置。
- 本组结论不得作为"补丁已修复"的证据（补丁零 CI 验证，见 K §10）。
