<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-266 · 过程回执 · 完工范围（worktree `<LOCAL_WORK>/ai-example-code/wt-e2e-ctx`，分支 `feature/e2e-ctx`，基线 `a5539c2`，**未 commit**）（ced79706 / agent-1366）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`check-docs`、`docs/任务看板`。
> **稿态**：历史中间稿 —— 该 wire 第 `m143` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 9 条有正文），末条有正文消息为 `m178`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `ced79706` / agent `agent-1366`（`main` = 主会话）；原字符 2,123（链内 1 段、合计 2,123）。
> **链号 / 类别**：`MT-266` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m178`（1,787 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

施工完成。以下是给主会话的交接。

## 完工范围（worktree `<LOCAL_WORK>/ai-example-code/wt-e2e-ctx`，分支 `feature/e2e-ctx`，基线 `a5539c2`，**未 commit**）

**自证**：`agent-1366` · `DeepSeek/deepseek-flash` · profile `coder` · thinkingEffort `high`（wire `profile.bind` 单行实测）。

1. **三形态 usage 桩**（`scripts/ci/stub-upstream.mjs` + `scripts/ci/stub-routes.json`）
   - 请求级标记 `usage-shape=<last|repeat|zero-last|none>`（与 `--violate-form-type` 同族）；无标记 = `last` = 改造前行为。
   - 数值唯一取数点 = `stub-routes.json` 的 `usageShapes`（桩与 E2E 脚本读同一份，杜绝漂移）。
   - `--self-check` 新增段 ⑧：形状表结构自洽 + 四形状帧形态 + 标记解析；全量 **11/11 PASS**。

2. **E2E-CTX-1..4**（`scripts/verify-e2e.ps1`，29 → **33**）
   - 断言核抽成纯函数 `Test-CtxCase`（帧 + 端点快照），4 条用例 + CTX-4 二轮/`/health` 附加断言；端点/`done.usage`/`done.context` 三方同源、分项 Σ 守恒、未压缩不发 `context` 帧。

3. **文档/CI 同步**：`scripts/README.md`（§1.4 加行、新增 §2.3b）、`.github/workflows/ci.yml`（job/step 名 33，**启动命令零改动**）、`docs/E2E-桩模式校准配方.md`、`docs/README.md`、`docs/test-cases.md`。

4. **施工报告**：`docs/evidence/施工报告-E2E增补CTX-DS-Flash.md`（DC-19 首页 + 矩阵 + 红测 + 验证 + 运行手册 + 文件清单 + E1 待办）。

## 验证状态

| 项 | 结果 |
|---|---|
| 红测（改造前桩 + 新断言） | **7 项红**（repeat/zero-last/none/标记解析），`last` 基线本就绿 —— 已留痕 |
| 桩自检全量 | **11/11 PASS** |
| 基线帧等价（`git show a5539c2` vs 现行，去 id/created） | **逐帧逐字节相同**（3 组 5/5/9 帧） |
| 断言层无服务红绿（`<LOCAL_WORK>/_ctx-harness/ctx-assert-harness.ps1`） | **14/14**：旧桩帧上 3 红、新形状上 3 绿、7 条负对照全咬住 |
| `verify-e2e.ps1` 语法 | PARSE OK |
| `check-docs` | **6/6** |
| 后端读桩文件的那例 | `GenerativeFormContractTest` **8/8 · BUILD SUCCESS** |
| **E2E 33 用例实跑** | **未跑**（脚本自身不起服务；授权禁止起 18330/18399/Redis）。运行手册见报告 §5 |

## 风险点

1. `repeat` 用例断言 `frames≥2`。已按 `spring-ai-openai 1.1.8` 字节码核实聚合是 `buffer(2,1)`（短文本 4 分片 ⇒ 3 帧），推演成立；若 CI 实测 `frames=1`，应改判据而非放宽数值。
2. `E2E-CTX-4` 的「估算随历史增长」是「压缩按估算触发」的**等价物**（真压缩需压小全局分母，会连带改其余用例）——边界已在报告 §2.1 显式登记，不声称已端到端验证触发。
3. 端点依赖 Redis（503 即组内 4 条红，属环境坏）；`contextCompactor` 未装配时 `done.context` 缺键会被判红（有意收紧）。
4. 桩标记 `usage-shape=` 全仓仅本组 4 条使用，29 条既有用例帧不受影响（另有逐帧等价机械证据）。

## 待办（本批不做）

- **模型配置 E1**：依赖批②/③ 合流；含 E1-1 选择器/`AiModelConfig` 装配、E1-2 同棒 E2E 增量（`done.model` 归属本批留作未断言观察项）。
- **看板**（本批禁动）：`docs/任务看板.md` 需把「29 → 33」并闭合缺口登记①「E2E-CTX-1..4 + 三形态 usage 桩已落」。
