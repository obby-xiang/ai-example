<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-025 · 设计 · 逐处改动（行号为改后行号）（0c4dbfa1 / agent-88）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`SpToolCallingManager`、`backend/src/main/java`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m30` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 4 条有正文），末条有正文消息为 `m34`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `agent-88`（`main` = 主会话）；原字符 3,176（链内 1 段、合计 3,176）。
> **链号 / 类别**：`MT-025` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `10%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m34`（543 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

文档修订完成，仅改动 `<REPO>\docs\evidence\GFc-E2E用例设计-GLM-5.3.md` 一个文件（342 行，原 325 行），仍为未跟踪、未提交状态；`git status --porcelain` 只有该文件一条 `??`，无其他文件改动、无 git 写操作。

## 逐处改动（行号为改后行号）

| # | 位置 | 前 | 后 |
|---|---|---|---|
| 1a | :199（§4 GF6） | `【实测：SpToolCallingManager.java:310 WARN 行】` | `【实测：SpToolCallingManager.java:306 WARN 行】`。核对源码 `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:306` 确为 `log.warn("前端工具入参被安全闸拒绝 …")`；:310 是空行/方法尾 |
| 1b | :173（§4 GF3-A5） | `【实测：ConfirmGate.java:410 INFO 行】` | `【实测：ConfirmGate.java:409 INFO 行】`。核对 `.../ai/gate/ConfirmGate.java:409` 为 `log.info("前端工具被用户取消 runId={} …")`，:410 是 `return new Submission(...)`。**未动** :52 的 `ConfirmGate.java:346-357, 395-410`（范围表述已含该行） |
| 2 | :70、:274（§1.4 / §7-O1） | `AiPanel.vue:202-205` | `frontend/src/components/AiPanel/AiPanel.vue:202-205`，两处全文统一（已确认路径存在，同目录另有 `FormRenderer.vue`） |
| 3 | :68（§1.4） | `…单元素值 JSON 无需绕行。` | 追加限定：该结论**仅对"数组嵌套在值对象内"成立**；裸单元素数组须 `-InputObject`，走管道会被展开成标量；并注明 GF 值 JSON 是"对象+数组字段"形状不受影响、`New-FormValues` 拼装时不要单独管道序列化字段值。本机 PS 5.1.26100.9444 实测三式：`-InputObject @('a')`→`["a"]`、`@('a')｜ConvertTo-Json`→`"a"`、`@{f=@('a')}`→`{"f":["a"]}`，故标【实测】 |
| 4 | :183（GF4 脚本动作）、:185（GF4-A1） | `（…复刻 GFa §3.3 的反例集）`；`≥3 条（三处类型错+未知字段，GFa §3.3 同集实测为 4 条）` | 改为推演口径：`（类型错 ×3 + 未知字段，按后端校验规则推演）`；`**≥3 条**（…按后端校验规则**推演应为 4 条**；GFa §3.3 实测的是另一组字段，不作为本用例计数依据——断言取 ≥3 留余量）`。全文已无"复刻"（仅裁决节引述原措辞） |
| 5 | :225-229（§5.3） | 问题段列 `TC15/TC16/TC17/TC18`；加固判据为"`$onSuspend` 未给出专用处理时 POST cancelled" | ①用例集合补为 `TC15/TC16/TC17/TC18/TC22`；②新增"纠正（裁决 #2）"引注：`$onSuspend` 为 `$null` 时 `Invoke-AiTurn` 直接 `break`（:277）不发 POST，真正危险路径是**传了处理器但返回通用值**（`$script:continueHandler` :293-297 恒返回 `{"ok":true,"source":"e2e"}`）；③判据改为 `name=generative_form` 时**优先于** `$onSuspend` 通用回灌值直接 POST `{runId, toolCallId, cancelled:true, source:'e2e'}`，仅当处理器**显式返回表单专用载荷**（显式 `cancelled` 或非通用 `result`）才改用处理器载荷。三点均在 `scripts/verify-e2e.ps1`（:277/:283-284/:293-297）逐行核对 |
| 6 | :116（§3.3 判定表 SKIP 行） | `…不把上游模型抖动记成产品 FAIL【推断：此口径需指挥官确认，备选为直接 FAIL】` | 删去待确认标注，改为 `【已裁决：指挥官 K3，见文末裁决 #1】`，并补硬性底线：**GF1–GF4 全部 SKIP → 整棒判 FAIL（退出码 1）**；`GFSKIP=n` 摘要**单列保留**、与主计数并列打印 |
| 7 | :329-342（文末新增） | — | 新增 `## 裁决结论（指挥官 K3，2026-10-07）` 表（6 条：#1 SKIP+硬底线、#2 §5.3 语义纠正+TC22、#3 GF4 推演口径、#4 行号/路径/自查表/JSON 歧义、#5 编号/插入点/GF5 零轮次、#6 双 FormRenderer 脚本层不可覆盖+浏览器手动冒烟为收尾），风格对齐 `GFa-生成式表单后端工具验证.md:433`、`S5b-deepseek-E2E移植验证.md:302` 的既有留痕；末尾加一句"本轮仅文字修订，未改产品代码/未引入依赖/零 git 写操作" |
| 8 | :319（§9 自查表"证据等级标注"行） | `实测项均附文件:行号` | 改为如实口径：`实测项**多数**附文件:行号，另有部分实测项为无行号的**机制断言**（"既有 22 用例零断言改动""GF 块与既有块结构同构""本棒零产品代码改动"等结构性/流程性事实），其取证方式已在文中以文字说明，不强凑行号` |

## 验证

- grep 自检全绿：旧行号 `SpToolCallingManager.java:310` / `ConfirmGate.java:410` 零命中；`AiPanel.vue` 两处均为全路径；`【推断：此口径需指挥官确认` 零命中；`GFSKIP` 三处（判定表、§5.1、裁决表）一致；`TC15/TC16/TC17/TC18/TC22` 出现于 §5.3 与裁决表。
- 脱敏终检（`:\, sk-、<USER>、Users\`）零命中，文档仍只用 `<<OLD_REPO_DIR>>`/`<REPO_ROOT>`/`<TMP>`/`***` 占位。
- `git status --porcelain` 仅 `?? docs/evidence/GFc-E2E用例设计-GLM-5.3.md`，无其他改动。

## 未做/建议关注

- 文档仍是**设计稿**，`verify-e2e.ps1` 未动：§5.3 的兜底判据、`Invoke-FormTurn`、GF1–GF6、预清理 `GFC-*` 扩展段均待执行棒实现（本次未碰脚本，符合"只做文档修订"边界）。
- §0/§10 未加硬性底线的交叉引用（裁决 #1 只要求改 §3.3 判定表并登记），若希望摘要节也显式写明"全 SKIP 即 FAIL"，可再补一处。
