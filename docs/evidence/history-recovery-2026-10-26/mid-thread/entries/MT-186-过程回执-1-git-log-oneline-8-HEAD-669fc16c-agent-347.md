<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-186 · 过程回执 · 1. `git log --oneline -8`（HEAD 与批 A 三笔）（669fc16c / agent-347）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`workspace.ts`、`ExportWizardView.vue`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m5` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 5 条有正文），末条有正文消息为 `m12`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `669fc16c` / agent `agent-347`（`main` = 主会话）；原字符 3,306（链内 1 段、合计 3,306）。
> **链号 / 类别**：`MT-186` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `26%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m12`（1,795 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

只读核实完成，未改任何文件、未执行任何 git 写操作。

## 1. `git log --oneline -8`（HEAD 与批 A 三笔）

```
6e59e0c docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕（含 N-6 复核登记与红队观察项）
43f7d0a fix(backend): 前端工具超时文案改「结局未知（不表示未发生）」+ 回归锁负向断言
0f1b334 feat(frontend): download_export_file 即发即回 + 在途防重复与跨任务清理 + ui_event download 60s abort
6ee96c3 docs(plan): 登记 AI 发布冲突提示落点重建立项（R-94）
38139c9 docs(research): 入库步骤条交互与对话时序混排两份业界调研
0b273fb fix(ci): 命名门禁扩展三态检测与自检防掏空
c0fada5 refactor(frontend): 删除无调用方的孤儿导出并回灌需求文档注记
a98f6d5 feat(frontend): 任务中心标题即打开、拆除详情抽屉、精简顶部菜单
```

批 A 三笔**全部已落**，顺序为前端 → 后端 → 文档，与 `commit-gov/msgA1.txt`/`msgA2.txt`/`msgA3.txt` 的首行主题逐字一致；提交时间 18:12:15 / 18:12:18 / 18:12:23（+0800）。改动面与预期相符：

| 提交 | 文件 |
|---|---|
| `0f1b334` 前端 | `frontend/src/stores/workspace.ts`、`frontend/src/utils/frontend-tools.ts`、`frontend/src/views/ExportWizardView.vue` |
| `43f7d0a` 后端 | `backend/.../ai/config/AiProperties.java`、`.../ai/gate/ConfirmGate.java`、`.../ai/tools/AiTools.java`、新增 `ConfirmGateFrontendTimeoutTextTest.java` |
| `6e59e0c` 文档 | `docs/M2-排期计划.md`、`docs/requirements.md`、`docs/03-技术方案文档-v2.1.md`、`docs/spike/SP-01ab-决策卡.md` |

## 2. `git status --porcelain`

**输出为空——工作树干净（0 项）**，即 11 项（前端/后端/文档三组）已全部被三笔提交收走，无剩余、无未跟踪残留。

## 3. push 状态

- `git status -sb` → `## main...origin/main`（**无 ahead/behind 计数**）
- `git rev-parse HEAD` = `6e59e0cd19cfcd615a7699e79c957659303474aa`
- `git rev-parse origin/main` = `6e59e0cd19cfcd615a7699e79c957659303474aa`（**完全相同**）
- 佐证：`git reflog show origin/main` 首行 `6e59e0c refs/remotes/origin/main@{0}: update by push`；且 GitHub 侧最新 run（见下）的 `head_sha` 正是 `6e59e0c`，创建于 10:12:34Z（本地 18:12:34）。

**结论：push 已发生，本地与远端同点。**

## 4. CI 状态（共查 2 次：runs + jobs，未超限额）

最新 run（对应 HEAD `6e59e0c`）：

- **id `38044103814`**｜workflow「CI 门禁」｜run_number 85｜head_sha `6e59e0c`｜event push
- **status = completed｜conclusion = success**
- created 2026-10-10T10:12:34Z → 完成 10:15:41Z（约 3 分钟）
- 链接：https://github.com/<OWNER>/ai-example/actions/runs/38044103814

`/jobs` 端点——**5/5 job 全 completed / success，无失败步骤**：

| job | status | conclusion |
|---|---|---|
| 后端编译与单测 | completed | **success** |
| 前端类型检查与构建 | completed | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | completed | **success** |
| 文档一致性门禁（check-docs）｜阻断 | completed | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | completed | **success** |

（前一条 run `38038313522` / `6ee96c3` 亦为 success，属上一批，批 A 未引入回归。）

## 5. `<LOCAL_WORK>\commit-gov\提交报告.md` 末尾

**批 A 条目尚未写入（无）。** 证据：

- 用 `0f1b334|43f7d0a|6e59e0c|批 A|38044103814` 在该文件内检索，**零命中**。
- 报告头部仍是「起点 HEAD `d3020ee`｜终点 HEAD `6ee96c3`」，§4.2/§4.3/§4.4 记录的是 run `38038313522`（`6ee96c3`）与当时的 `git log`，§4.4「终态」把 `6ee96c3` 写作 HEAD。
- 文件 mtime 为 **2026-10-10 16:38**，而批 A 的落盘物均已更新：`msgA1~A3.txt`(18:11)、`batchA-diff.patch`(18:11)、`batchA-files.txt`(18:11)、`ci-poll-batchA.sh`(18:12)、`surefire-now.txt`(18:12)、`mvn-test-batchA.log`(18:12)、`head-gate-batchA.log`(18:13)、`ci-last-runs.json`(18:21)。

顺带发现一处流程瑕疵（不影响结论）：`ci-poll-batchA.log` 的 9 次轮询**全部 PARSE_ERR**（`No such file or directory: <LOCAL_WORK>/commit-gov/ci-last-runs.json`），即批 A 的 CI 轮询脚本没写出 JSON 就退出，轮询日志本身没起到取证作用——但这属事后补账：run 38044103814 的 CI 结果本身是绿的（上表为本次实测取证）。

## 一句话结论

**批 A 已提交（`0f1b334`/`43f7d0a`/`6e59e0c`）、已推送（HEAD = origin/main）、CI 已绿（run 38044103814，5/5 job success），闭环只差最后一步——`<LOCAL_WORK>\commit-gov\提交报告.md` 尚未补写批 A 条目（另需修掉 `ci-poll-batchA.log` 里那 9 次 PARSE_ERR）。**
