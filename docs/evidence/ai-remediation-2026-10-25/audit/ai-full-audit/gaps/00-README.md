> 来源=<仓外证据>/ai-full-audit/gaps/00-README.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# 缺口分配总览（G1–G4）｜AI 全链路只读验收

> 上游：`K-synthesis.md` §0.1/§9。本目录只做"把未审文件分配出去"的清单工作：未修改任何源码/测试/配置/工作流，未启动服务，未触发 CI，未开始任何新审查。
> 快照：`<仓外证据>/ai-full-audit/tree`（base `ae883fb` + `working.patch`）。

## 1. 范围与排除

| 项 | 处理 |
|---|---|
| 后端 AI 生产文件（`backend/src/main/java/.../ai/**`，196） | 在范围内 |
| 后端 AI 测试文件（`backend/src/test/java/.../ai/**`，327） | 在范围内（含 7 个夹具，见 §4） |
| **前端业务联动（I 域）** | **按指令排除**：`sheet-tools.ts`、`ai-dom-tools.ts`、`ai-regions.ts`、`resultview.ts`、`SpreadGrid.vue`、`FormRenderer.vue`、`SheetPreviewCard.vue`、`UploadHandoffCard.vue`、`workspace.ts`、`blocks.ts`、`tool-summaries.ts`、`ai-suggestions.ts`、`context-usage.ts`、`form-scope.ts`、`ui-receipt.ts`、`publish-mode.ts`、`publish-links.ts`、`AiFeedbackView.vue`、`form-schema.ts` —— 由 agent-96 恢复的 I 报告承接，本目录**不含任何前端文件** |
| `spike/*` | 不属产品面（A §1.8-11） |
| 已在 B–J 实质审过的文件 | 不在清单内（见 §3 的判定规则） |

## 2. 计数

| 分配 | 主题 | 生产 | 测试 | 合计 |
|---|---|---:|---:|---:|
| **G1** `01-tool-prompt-resultview.md` | 工具定义 / 提示词注入 / 结果视图契约 | 22 | 25 | **47** |
| **G2** `02-context-prompt-surface.md` | 上下文预算 / 压缩 / 提示词装配 / 观测与错误面 | 11 | 40 | **51** |
| **G3** `03-config-provider-lease.md` | 配置与供应方 / 租约与多实例状态 / 门与工具断言 | 14 | 47 | **61** |
| **G4** `04-kb-form-web-frames.md` | 知识库 / 表单 / Web 端点 / 帧发射 | 31 | 32 | **63** |
| **合计** | — | **78** | **144** | **222** |

另有 `05-uncovered-branches.md`：文件分配后**仍无法闭环**的端到端分支（拓扑/真机/真模型/门禁类）。

## 3. 方法与判定规则（逐条可复现）

1. **词边界匹配，不用子串**。取 196 个生产 + 327 个测试的 basename，用 `grep -o -w -F -f names domains.txt` 在 **B–J 九份**正文中匹配；`-w` 防止 `KbDoc` 被 `KbDocMeta`、`ResultViewColumn` 被 `ResultViewColumnType`、`ToolMeta` 被其他标识符"顺带命中"。
2. **A 单独按章节判定**。A 的 §1（36–165）是清单、§2（168–194）是链表、§4（380–412）是状态表、§7（568–625）是"未覆盖声明"——均**不算审阅**；只有 §3 链分析（197–378）、§5 测试可信度（414–445）、§6 发现（448–565）中的**带 `path:line` 结论**才算实质审阅。
3. **实质审阅即排除**（不重复派工）。命中者仅 6 个，其中 2 个确为实质审阅：
   - `ai/exec/AiExecutorConfig.java` → A-F4（`:506`，`AiExecutorConfig.java:43-54` 停机语义）+ A-F9-2（`:549`）→ **排除**（其余面如池容量/MDC 装饰器已在 A §1.2 计数，属既有域 F4 的补充阅读，不新派）。
   - `ai/kb/HttpKbSource.java` → A-F10（`:556`，`HttpKbSource.java:71-73,37-40` SSRF/重定向）→ **排除**。
   - 另 4 个仅在 A 的**表格/链句**里被顺带点名，保留在清单并在文件内标注：`KbText`、`PromptDelimiters`（A §3-C14 各一句、无行号验证）、`FrameWire`（A §5.2 作为夹具名出现）、`FakeKbSource`（A §5.1 替身枚举）。
4. **夹具不排除**。`FakeKbSource`、`FrameWire`、`InMemoryContextStateStore`、`TestProviderRuntime`、`FakeRenewScheduler`、`FakeStartImportTool`、`KbTestSupport` 进入清单——夹具保真度本身就是审计对象（K CI-15 的同类问题：`InMemoryRunStore` 共享引用使覆盖类缺陷不可见）。

### 3.1 对 K 的计数更正（必须记录）

K 报的是 **69 / 144**。严格词边界复核后为 **80 / 144**（`comm` 实测 224 个未命名词），其中 80 里排除上述 2 个 → **78 / 144**。
差额 **+11 个生产文件**来自 K 的子串误判（把"别的标识符里包含它"当成"被提到"），例：
`KbDoc`（被 `KbDocMeta` 顶替）、`ResultViewColumn`（被 `ResultViewColumnType` 顶替）、`ToolMeta`、`KbSection`、`KbChunk`、`KbTerm`、`ContextState`、`ToolSnapshot` 等。
**测试侧 144 未变**（说明 K 的测试计数当时未受子串影响）。

## 4. 无重复 / 无遗漏的验证（实测输出）

| 检查 | 命令要点 | 结果 |
|---|---|---|
| 总数守恒 | `78 + 144 = 222` | 222 |
| 分配总数 | `wc -l /tmp/assign.txt` | 222 |
| 路径唯一 | `cut -f2 assign.txt \| sort -u \| wc -l` | **222**（无重复） |
| 与源集合逐一比对 | `comm -3 <(cut -f2 assign.txt \| sort) <(cat prod_final test_final \| sort) \| wc -l` | **0**（无遗漏、无多余） |
| 分组行数 | `rows_g1..g4` | 47 / 51 / 61 / 63 |
| 每个文件只出现一次 | 单次 awk 生成 `g<TAB>path`，再按组切分 | 每组互斥（由单一判定函数保证，无交叠分支） |
| 前端文件混入 | 清单只由 `ai/**` 的 main/test 列表生成 | 0 个前端文件 |

> 复现方式：按 §3 的规则对 `tree/backend/src/{main,test}/java/com/example/configmgr/ai/**` 重跑即可得到同一 222 个路径。判定函数是**单值函数**（每个路径只映射到一个组），因此不存在"同一文件被两组同时认领"。

## 5. 与既有域的关系（避免重复劳动）

- 下列文件**已被 B–J 实质审过**，不在本清单，但**留有未读段**，属"域内补读"而非"新派工"：
  `RunStore`、`ResilientChatService`、`ConfirmGate`、`SpToolCallingManager`、`LeaseService`、`SseChatEmitter`、`AiConfigService`/`AiConfigWriter`、`AiSessionPurgeService`、`KbService`、`GenerativeFormRules`、`MemoryWindow`、`ContextCompactor`、`RollingSummarizer`、`AiPanel.vue`。
- 建议：G3 在处理 `RunStore*Test` 时**顺带**回答 `RunStore` 未读段的 claim/ledger/idem Lua；G2 在处理 context 测试时**顺带**回答 `ContextStateStore`。
- 本目录的任何结论**不得**作为"补丁已修复"或"多实例已成立"的证据（K §11）。
