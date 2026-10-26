<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-027 · 调研 · 侦察报告：Issue #9（<OWNER>/ai-example）（160a4866 / agent-809）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiTools.java`、`ExportWizardView.vue`、`backend/src/main/java`、`frontend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m7` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 6 条有正文），末条有正文消息为 `m9`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `160a4866` / agent `agent-809`（`main` = 主会话）；原字符 2,049（链内 1 段、合计 2,049）。
> **链号 / 类别**：`MT-027` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `23%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m9`（1,137 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 1 / 身份 3 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

# 侦察报告：Issue #9（<OWNER>/ai-example）

## 自证（wire profile.bind）
- **自身 agent**：`agent-809`；profileName `explore`；thinkingEffort `high`
- **modelAlias**：`DeepSeek/deepseek-flash`
- 来源单行（逐字）：`{"type":"profile.bind","agentId":"agent-809","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high",...}`
- 路径：`<KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_160a4866\agents\agent-809\wire.jsonl`

## Issue #9 逐字
| 字段 | 值 |
|---|---|
| number | 9 |
| title | AI 批量下载导出文件触发多次浏览器下载，可能被浏览器拦截 |
| state | OPEN |
| author | <OWNER> |
| createdAt | 2026-10-13T01:13:00Z |
| labels | []（无） |
| comments | []（无评论） |

### body 全文
```
## Bug
### 复现步骤
1. 完成一个包含多个配置定义的导出任务（如 CURRENCY + DOC_TYPE + TAX_RATE）
2. 对 AI 说"把这些导出文件都下载下来"
3. AI 逐个调用 `download_export_file(taskId, defCode)`，每个配置定义一次
### 预期
AI 应能一次性触发打包下载（zip），与前端用户侧"全部打包 zip"按钮行为一致。
### 实际
AI 对每个配置定义单独调用 `download_export_file`，触发 N 次独立浏览器下载。浏览器可能弹出"是否允许此站点下载多个文件"权限提示，或直接拦截后续下载。用户必须逐个确认或手动放行。
### 根因
AI 工具 `download_export_file`（`AiTools.java:357`）只接受单个 `defCode` 参数，无批量下载工具。但前端已有 `downloadChecked()` / `downloadAll()`（`ExportWizardView.vue:949/976`）用 `zipBlobs` 打包成单次 zip 下载，后端也有 `GET /tasks/{id}/files/download-all` 和 `GET /tasks/{id}/files/download?codes=...` 两个批量 API。代码中已存在蓝图名 `download_export_files`（复数，`frontend-tools.ts:423`），但注释明确标注"当前未登记于任何路由"——曾被设计但从未实现。
此外，`download_export_file` 每次调用都走完整 preflight（`ensureTaskBoundForTool` 串行 HTTP + 结果存在性检查），N 个文件 = N 遍 preflight，即使任务绑定状态未变。
### 相关文件
- `backend/src/main/java/.../ai/tools/AiTools.java` — `download_export_file` 工具定义（单 defCode）
- `frontend/src/utils/frontend-tools.ts:423` — `download_export_files` 蓝图名存在但未注册
- `frontend/src/views/ExportWizardView.vue:949/976` — `downloadChecked()` / `downloadAll()` 用户侧批量 zip 下载
- `backend/src/main/java/.../task/controller/FileController.java:189/205` — 后端批量下载 API
### 严重度
中 — 多文件场景下浏览器拦截导致下载失败，用户需手动逐个放行；preflight 重复执行浪费请求。
```

## open issue 全清单
- **#9** `AI 批量下载导出文件触发多次浏览器下载，可能被浏览器拦截`（OPEN，2026-10-13）

仅此一条，仓库当前无其它 open issue。
