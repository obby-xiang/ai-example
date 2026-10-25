> 来源=<仓外证据>/ai-full-audit/gaps/04-kb-form-web-frames.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# 缺口分配 G4｜知识库 / 表单 / Web 端点 / 帧发射

- **主责域**：D2（KB/表单/表格工具）　**副域**：D1（帧发射）、D3（端点面）
- **规模**：63 个文件（生产 31 / 测试 32）
- **为什么算缺口**：`ai/kb/*` 21 个未命名词（含 `MarkdownChunker` 452 行、`KbSampleSeeder` 242、`LocalDirKbSource` 130、`KbManifest`/`KbHashing`）、`ai/form/*` 5、`ai/web/dto/*` 4、`ai/feedback/AiFeedbackRepository` 1；对应 `kb` 16、`form` 4、`web` 12、帧发射 4 个测试。A 只对 KB 的"prompt 段与 readDoc 分页"给了结论，未审索引/分块/缓存/播种；表单只审到"附件一次性"。
- **风险定位**：C14（KB）是 A 自认的部分覆盖；C17（反馈）前端与仓储未审；`AiController*` 的 12 个端点测试是"健康/错误面/披露面"的唯一自动化证据，其断言强度直接决定 K 的 ID-15、MD-13 是否可claim。

## 必读清单（63）

| 路径 | 类型 | 重点核查点 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java` | 生产 | 反馈仓储查询面（全表/排序） |
| `backend/src/main/java/com/example/configmgr/ai/form/FormAttachmentStore.java` | 生产 | 表单附件存储/问题码/选项源注册 |
| `backend/src/main/java/com/example/configmgr/ai/form/FormIssue.java` | 生产 | 表单附件存储/问题码/选项源注册 |
| `backend/src/main/java/com/example/configmgr/ai/form/FormIssueCodes.java` | 生产 | 表单附件存储/问题码/选项源注册 |
| `backend/src/main/java/com/example/configmgr/ai/form/FormOptionSource.java` | 生产 | 表单附件存储/问题码/选项源注册 |
| `backend/src/main/java/com/example/configmgr/ai/form/FormOptionSourceRegistry.java` | 生产 | 表单附件存储/问题码/选项源注册 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbCacheException.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbCacheStore.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbChunk.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbConfig.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbDoc.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbDocMeta.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbHashing.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbLookupResult.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbManifest.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbReadResult.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSampleSeeder.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSearchHit.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSearchResult.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSection.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSource.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbSourceException.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbStatus.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbTerm.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/KbText.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/kb/MarkdownChunker.java` | 生产 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/main/java/com/example/configmgr/ai/web/dto/AvailableModelView.java` | 生产 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/main/java/com/example/configmgr/ai/web/dto/FetchModelsView.java` | 生产 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/main/java/com/example/configmgr/ai/web/dto/ModelView.java` | 生产 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/main/java/com/example/configmgr/ai/web/dto/ProbeView.java` | 生产 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormContractTest.java` | 测试 | 表单附件存储/问题码/选项源注册 |
| `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormProBatchRulesTest.java` | 测试 | 表单附件存储/问题码/选项源注册 |
| `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormSchemaRulesTest.java` | 测试 | 表单附件存储/问题码/选项源注册 |
| `backend/src/test/java/com/example/configmgr/ai/form/GenerativeFormSubmissionRulesTest.java` | 测试 | 表单附件存储/问题码/选项源注册 |
| `backend/src/test/java/com/example/configmgr/ai/kb/FakeKbSource.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbCitationIdConformanceTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbDisclosurePromptTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbHttpSourceTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbI18nTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbLocalSourceTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbLookupTermTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbPagingBudgetTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbPropertiesTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbReadDocTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbSampleCorpusTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbSampleSeedingTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbTestSupport.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbToolResultPlainTextTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/KbToolsTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/kb/MarkdownChunkerTest.java` | 测试 | KB 索引/分块/缓存/源适配与清单 |
| `backend/src/test/java/com/example/configmgr/ai/run/ReasoningMemoryConvergenceTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/ReasoningOriginStampTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/RetryBackoffTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterBusinessActivityTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterReasoningFramesTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWriteBudgetTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/run/SseChatEmitterWriteTimeoutMetricTest.java` | 测试 | 语言策略/安全事件/流违规/用量明细 |
| `backend/src/test/java/com/example/configmgr/ai/web/AiChatRequestCompatTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerChatModelTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerContextEndpointTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiControllerSheetMetaTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |
| `backend/src/test/java/com/example/configmgr/ai/web/AiToolsDisclosureEndpointTest.java` | 测试 | 配置面 DTO 形状（掩码/字段白名单） |

## 必须回答的验收问题

1. `MarkdownChunker` 的切块边界：是否会切断代码块/表格导致引用 `[doc_id §章节]` 指向错误段落；字符/行/Term 三套口径是否一致（`KbPagingBudgetTest` 只测预算，不测边界）？
2. `KbHashing`/`KbManifest`/`KbCacheStore` 的增量判据（ETag/sha/version）是否可能"内容变了但 hash 没变"（如本地源 mtime 相同、HTTP 304 误判）？
3. `KbSampleSeeder` 的多实例并发播种（`KbSampleSeederConcurrencyTest`）是否真的用锁而非"先查后写"？
4. `LocalDirKbSource` 与 `CachedKbSource` 的"本机盘当共享缓存源"（K BZ-16/F-F-13）是否有版本抖动防护？
5. `KbSource`/`KbSourceException`/`KbCacheException` 的失败姿态：源不可用时是"保留旧索引 fail-open"还是"清空"？与 A 的 C14 描述是否一致？
6. `FormAttachmentStore` 与 `RedisFormAttachmentStore` 的接口契约：单机回退实现是否存在（若存在，则与"fail-closed 到 Redis"矛盾）？`FormIssueCodes` 的码表是否与前端 `issues[].key` 对齐？
7. `FormOptionSourceRegistry` 的 `sourceId` 闭集是否可被客户端自述（ID-09 的相邻面）？
8. 12 个 `web/*Test` 中，哪些是 `standaloneSetup + mock`（只证分支）、哪些有真 H2/真 Bean？逐条标注；`AiControllerHealth*` 三个是否是"字段存在性"断言（K §7.2 的"只锁暴露面"）？
9. 4 个帧发射测试（`SseChatEmitterBusinessActivity`/`ReasoningFrames`/`WriteBudget`/`WriteTimeoutMetric`）能否让"业务帧被 authority 丢弃"（S-10）变红？

## 与 K 结论的挂接

| K 条目 | 依赖本组回答什么 |
|---|---|
| C14（KB 部分覆盖） | 索引/分块/缓存/播种是否真正确 |
| ID-15（/health 暴露面） | 端点测试是否只锁定暴露面 |
| MD-13（/history 500） | `AiController*` 是否覆盖 Redis 失败 |
| S-10（业务帧丢弃） | 帧发射测试的可红性 |
| ID-07（反馈 IDOR） | 仓储查询面是否分页/过滤 |

## 边界

- 只读；不得改代码/测试/配置；不得以 stub 上游的结论替代真实模型证据。
