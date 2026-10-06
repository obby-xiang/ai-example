# V3 核验：deepseek-v4-pro「685 行 PowerShell E2E 脚本 22 用例全绿（含导出/导入ZIP/SSE进度/AI对话流式+工具+渐进披露+HITL确认）」

- **被核验分支**：`ai-example-deepseek-v4-pro`（`git rev-parse --abbrev-ref HEAD` = `deepseek-v4-pro`，HEAD = `25639c59380b915b9f49917f77bc642d5a4a716b`）
- **核验项定义出处**：`ai-example-main-v2/docs/merge/05-合流落地执行手册.md:121`
  `| V3 | deepseek-v4-pro：22 E2E 用例全绿 | 起服务跑 verify-e2e.ps1 | 记录真实通过数 |`
- **核验日期**：2026-10-06
- **核验方式**：把分支后端**真实启动**在 18291 端口（分支默认 18080 用环境变量覆盖），用**真实 DeepSeek 模型**跑完整 E2E 脚本 **2 轮**
- **核验端口**：18291（`SERVER_PORT=18291`）
- **结论**：**【实测-符合】** —— 线下实跑 2 轮均为 **PASS=22 / FAIL=0**，与分支自述的"22 用例全绿"一致
- **附注（不影响结论）**：实测中发现 1 处与自述矛盾的老文档残留（`docs/04-测试用例.md` 写"19 个测试组"）、1 处真实的小缺陷（未上传文件批次的问题明细写盘失败，仅 WARN 不影响用例判定），以及 1 处脚本自身的输出小瑕疵，详见 §6

---

## 1. 核验目标（自述原文与出处）

### 1.1 分支自身的声明原文

| 出处 | 原文 |
|---|---|
| `ai-example-deepseek-v4-pro/scripts/verify-e2e.ps1` | 文件实测 **685 行**（`wc -l` = `685`；HEAD 版本 md5 = `82e579dbd6bfd99e9785d11371202ae6`），头部注释声明覆盖"配置定义动态建模/数据校验/导出(条件+进度+打包)/模板/导入(匹配+检查+依赖+草稿+发布)/AI 对话(流式+工具+渐进披露+HITL 确认)/SSE 进度" |
| `ai-example-deepseek-v4-pro/docs/05-验证结果.md:101` | \| 自动化回归 \| 修复后全量 E2E **22/22 全绿** \| ✅ \| |
| `ai-example-deepseek-v4-pro/docs/05-验证结果.md:135`（架构升级轮） | \| 自动化回归 \| 升级后全量 E2E **22/22 全绿** \| ✅ \| |
| `ai-example-deepseek-v4-pro/docs/05-验证结果.md:163`（框架合规重构轮） | \| 自动化回归 \| 最终 **22/22 全绿** \| ✅ \| |
| `ai-example-deepseek-v4-pro/docs/05-验证结果.md:19` | 同一脚本连续三轮执行，**三轮均 19/19 全绿**（第二轮为幂等重跑验证） |
| `ai-example-deepseek-v4-pro/docs/03-实现方案.md:51` | │   └── verify-e2e.ps1          # E2E 自动化验证（22 个测试组） |
| `ai-example-deepseek-v4-pro/docs/04-测试用例.md:4` | - 自动化脚本：`scripts/verify-e2e.ps1`（19 个测试组，后端运行时执行） |

即被核验声明为：**该分支自带 685 行 PowerShell E2E 脚本，覆盖导出/导入/SSE 进度/AI（流式+工具+渐进披露+HITL）等 22 个用例，实测全绿。**

### 1.2 分支脚本实际包含的 22 个用例（逐个阅读脚本 `Say` 段读出，非文档转抄）

脚本执行顺序（与文档 §2 表格的编号顺序不同，脚本中 TC20/TC21 排在 TC15 之前、TC19 排在最后）：

| # | 脚本内 `Say` 标题 | 脚本行 |
|---|---|---|
| 1 | TC1 配置定义列表与种子数据 | 188 |
| 2 | TC2 动态配置定义 CRUD | 204 |
| 3 | TC3 数据行校验引擎 | 229 |
| 4 | TC4 导出任务（全量 + 进度 + 文件） | 253 |
| 5 | TC5 导出查询条件（字段条件 + 范围条件） | 271 |
| 6 | TC6 zip 打包下载 | 291 |
| 7 | TC7 导入模板下载 | 304 |
| 8 | TC8 批次创建 + 上传匹配（xlsx/zip/序号后缀/未匹配） | 317 |
| 9 | TC9 检查（合法数据全部通过 + 依赖拓扑） | 343 |
| 10 | TC10 检查失败明细（非法选项 + 引用不存在 + 未上传） | 366 |
| 11 | TC11 SSE 进度事件流 | 400 |
| 12 | TC12 导入为草稿（不影响生效数据） | 421 |
| 13 | TC13 发布（替换生效数据） | 444 |
| 14 | TC14 发布前最终检查失败保护 | 477 |
| 15 | TC20 任务中心（统一列表/持久化/类型过滤） | 492 |
| 16 | TC21 任务分页 | 519 |
| 17 | TC15 AI 对话（流式/工具调用/页面工具隔离） | 531 |
| 18 | TC16 AI 工具结果驱动工作区（ui_event） | 562 |
| 19 | TC17 AI 破坏性操作需确认（HITL） | 582 |
| 20 | TC18 页签会话隔离/清空 | 626 |
| 21 | TC22 AI 历史恢复 | 642 |
| 22 | TC19 清理 + 演示数据恢复 | 661 |

合计 **22** 个用例（TC1–TC22），与自述数量一致。

---

## 2. 环境与命令

| 项 | 实际值 |
|---|---|
| OS / Shell | Windows，Git Bash（`<GIT_HOME>`） |
| JDK | `java version "21.0.12" 2026-07-21 LTS` |
| Maven | `<MAVEN_HOME>/bin/mvn.cmd`（PATH 中无 mvn） |
| PowerShell | **`pwsh` 不存在**（`which pwsh` → not found），改用系统自带 `powershell.exe`（`$PSVersionTable.PSVersion` = `5.1.26100.9444`） |
| 数据库基线 | `backend/data/` **原本不存在**（新克隆，无历史 `*.mv.db`），首次启动由 `DataSeeder` 初始化演示数据；无需备份/删库 |
| 端口 | 18291（`netstat -ano \| grep 18291` 启动前为空闲；结束时确认无 LISTENING，见 §7） |
| 模型 | 分支 `application.yml` 配置 `api-key: ${DEEPSEEK_API_KEY:}`、`base-url: https://api.deepseek.com`、`completions-path: /chat/completions`、`model: deepseek-flash`；密钥仅经环境变量注入进程，**未写入任何文件**（对全部证据文件做了密钥串扫描：`sk-` 前缀密钥与 `DEEPSEEK_API_KEY=值` 均为 0 命中） |

### 2.1 启动命令（原样）

```bash
cd <REPO_ROOT>/ai-example-deepseek-v4-pro/backend
SERVER_PORT=18291 DEEPSEEK_API_KEY="***" \
  "<MAVEN_HOME>/bin/mvn.cmd" spring-boot:run
```

启动成功日志（`V3-附件-后端启动与运行日志.log`）：

```
2026-10-06T18:56:15.131+08:00  INFO 11148 --- [config-admin-backend] [main] o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 18291 (http) with context path '/'
2026-10-06T18:56:15.139+08:00  INFO 11148 --- [config-admin-backend] [main] c.e.configadmin.ConfigAdminApplication   : Started ConfigAdminApplication in 3.661 seconds
2026-10-06T18:56:15.298+08:00  INFO 11148 --- [config-admin-backend] [main] c.e.configadmin.service.DataSeeder       : 初始化演示数据（4 个配置定义 + 生效数据行）...
2026-10-06T18:56:15.501+08:00  INFO 11148 --- [config-admin-backend] [main] c.e.configadmin.service.DataSeeder       : 演示数据初始化完成
```

接口存活确认：`GET /api/meta` → `{"code":0,...,"model":"deepseek-flash","modelProvider":"DeepSeek (OpenAI 兼容)","port":"18291",...}`

### 2.2 执行命令（原样，2 轮）

```bash
cd <REPO_ROOT>/ai-example-deepseek-v4-pro
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18291 > e2e-run1.txt 2>&1   # 退出码 0，real 1m15.256s
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18291 > e2e-run2.txt 2>&1   # 墙钟 63.1s
```

- 脚本**未做任何修改**（md5 见 §1.1）；后端源码/配置同样未改（`git status --porcelain` 为空）。
- `-Base` 指向 18291；脚本头部 `Say ' E2E 验证开始  base=' + $Base + ...` 在 PowerShell 中被解析为多参数，`$Base` 未拼进输出（脚本自身输出瑕疵，见 §6.3），故不能从脚本回显确认 base——已用旁证确认（见 §4.3）。

---

## 3. 实际观察：两轮结果（数字来自真实运行）

| 轮次 | 命令退出码 | 脚本末行原文 | PASS | FAIL | 耗时 |
|---|---|---|---|---|---|
| 第 1 轮 | 0 | `结果：PASS=22  FAIL=0` | **22** | **0** | `real 1m15.256s` |
| 第 2 轮 | 0 | `结果：PASS=22  FAIL=0` | **22** | **0** | 63.1s |

**真实通过数 = 22 / 22（两轮均一致）**，无失败用例、无跳过用例，因此**不涉及"环境波动 vs 代码缺陷"的失败归因**。

完整原文（附件）：
- `V3-附件-e2e-run1-原始输出.txt`（51 行，逐用例 PASS 行）
- `V3-附件-e2e-run2-原始输出.txt`

### 3.1 第 1 轮逐用例原始输出（附件原文照录）

```
============================================================
 E2E 验证开始  base=
============================================================
--- 预清理：无残留 ---
--- TC1 配置定义列表与种子数据 ---
  [PASS] TC1 种子数据与层级/依赖/引用定义正确
--- TC2 动态配置定义 CRUD ---
  [PASS] TC2 定义创建/重复拦截/字段动态追加
--- TC3 数据行校验引擎 ---
  [PASS] TC3 类型/必填/选项/范围/数值校验全部生效
--- TC4 导出任务（全量 + 进度 + 文件） ---
  [PASS] TC4 全量导出 4 文件 + 行数正确 + 文件可下载
--- TC5 导出查询条件（字段条件 + 范围条件） ---
  [PASS] TC5 字段条件(env=生产→3行)与范围条件(华东区→1行)正确
--- TC6 zip 打包下载 ---
  [PASS] TC6 打包下载条目正确（选定 2 个）
--- TC7 导入模板下载 ---
  [PASS] TC7 模板单个 xlsx 与多配置 zip 下载正常
--- TC8 批次创建 + 上传匹配（xlsx/zip/序号后缀/未匹配） ---
  [PASS] TC8 文件名匹配（含(1)后缀）与未匹配报告正确
--- TC9 检查（合法数据全部通过 + 依赖拓扑） ---
  [PASS] TC9 检查通过 + 依赖拓扑排序（SERVER_PARAM 先于 SERVER_EXTEND）
--- TC10 检查失败明细（非法选项 + 引用不存在 + 未上传） ---
  [PASS] TC10 非法选项/引用不存在/未上传文件 三类错误均拦截并给出明细
--- TC11 SSE 进度事件流 ---
  [PASS] TC11 收到进度事件流，样例：snapshot|9|RUNNING ；progress|12|RUNNING ；progress|13|RUNNING
--- TC12 导入为草稿（不影响生效数据） ---
  [PASS] TC12 导入后为草稿，生效数据不变，草稿可预览
--- TC13 发布（替换生效数据） ---
  [PASS] TC13 发布替换生效（5行→发布3行文件→生效3行）+ 重复发布拦截
--- TC14 发布前最终检查失败保护 ---
  [PASS] TC14 检查失败→导入失败；非已导入批次禁止发布
--- TC20 任务中心（统一列表/持久化/类型过滤） ---
  [PASS] TC20 统一任务列表/创建即持久化/状态进度正确/类型过滤正确
--- TC21 任务分页 ---
  [PASS] TC21 任务服务端分页正确
--- TC15 AI 对话（流式/工具调用/页面工具隔离） ---
  [PASS] TC15 流式输出+工具调用可见+按页面渐进式披露
--- TC16 AI 工具结果驱动工作区（ui_event） ---
  [PASS] TC16 AI 经 ui_event 驱动导出向导（选配置+启动任务）
--- TC17 AI 破坏性操作需确认（HITL） ---
  [PASS] TC17 HITL：发布需确认，拒绝不执行、确认后执行成功
--- TC18 页签会话隔离/清空 ---
  [PASS] TC18 会话创建/计数/清空正常
--- TC22 AI 历史恢复 ---
  [PASS] TC22 AI 历史恢复（用户/助手/工具卡）+ 未知会话返回空
--- 清理：删除 E2E_TEMP 配置及其数据 ---
  [PASS] TC19 清理完成，演示数据恢复
============================================================
 结果：PASS=22  FAIL=0
============================================================
```

第 2 轮（幂等重跑）输出与之**仅第 26 行不同**（`diff` 输出 1 处差异），即 TC11 的异步进度样例数值：

```
<   [PASS] TC11 收到进度事件流，样例：snapshot|9|RUNNING ；progress|12|RUNNING ；progress|13|RUNNING     （第 1 轮）
>   [PASS] TC11 收到进度事件流，样例：snapshot|35|RUNNING ；progress|36|RUNNING ；progress|37|RUNNING   （第 2 轮）
```

属异步进度时序差异，非结果差异；其余 50 行完全一致，两轮结论均为 `PASS=22 FAIL=0`。

### 3.2 覆盖面对照：AI 专项用例确实在跑真模型

| 用例 | 断言依赖的模型行为 | 原始输出证据 |
|---|---|---|
| TC15 | 模型自发调用 `list_config_defs` 工具 | 脚本断言 `tool_start|*list_config_defs*` 通过；后端日志中栈帧为 `com.example.configadmin.ai.HooksToolCallingManager.executeToolCalls ← org.springframework.ai.openai.OpenAiChatModel.lambda$internalStream$8`（即真实 OpenAI 兼容流式响应链路，非桩） |
| TC16 | 模型串联工具并下发 `select_defs` / `export_started` 的 ui_event | 断言两条 `ui_event` 均通过，且 `GET /api/export/tasks` 计数增加（导出任务被真实创建） |
| TC17 | 模型调用 `start_publish` 触发 HITL 确认门；拒绝不执行、同意后发布 | 两次 `confirm_tool|*start_publish*` 断言通过；拒绝后批次状态非 PUBLISHED，同意后轮询到 PUBLISHED |
| TC22 | 历史中含助手/工具卡片 | 断言 `assistant.tools.Count > 0` 通过 |

### 3.3 旁证：模型服务本身可达且响应在秒级（解释整脚本 ~1 分钟量级）

```
$ curl -s -o deepseek-direct.json -w "HTTP=%{http_code} time=%{time_total}s\n" \
    https://api.deepseek.com/chat/completions -H "Content-Type: application/json" \
    -H "Authorization: Bearer ***" \
    -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"reply with exactly: OK"}],"max_tokens":50,"stream":false}'
HTTP=200 time=0.970396s
```
响应原文（`V3-附件-DeepSeek直连响应.json`）：`"model":"deepseek-flash"`，`finish_reason":"stop"`，`completion_tokens_details.reasoning_tokens:28`。

### 3.4 旁证：E2E 的 AI 会话确实落库（与 V4 交叉印证）

后端停止后直查 H2 文件库 `ai_session` 表（详见 `V4-附件-H2直查-ai_session表.txt`），可见脚本两轮各自留下的会话行：

```
SESSION_ID                           | LAST_ACCESS                | MSG_JSON_LEN
e2e-f2988e7244144edb86023ea6182a4dcf | 2026-10-06 18:58:09.411377 | 30018
e2e-49b0b041fd93428c9bb9e5f64a61d0de | 2026-10-06 19:00:02.956615 | 21078
```
（时间戳分别落在第 1 轮 18:56–18:58、第 2 轮 18:59–19:00 窗口内，与两轮执行时刻吻合。）

### 3.5 旁证：请求确实打到 18291

脚本用例自身产生可核验的副作用，且这些副作用只可能来自本次 18291 实例：
- 启动前 `backend/data/` 不存在，若脚本未执行则 TC1 的种子数据断言（4 个定义、SERVER_PARAM 5 行）不可能通过；
- 脚本结束态查 18291：`GET /api/tasks?page=1&size=1000` → `total:12`，含 `IMPORT-6 / E2E-恢复批次 / PUBLISHED`；`GET /api/ai/sessions/count` → `count:1`（TC19 清理后仅剩 AI 会话未清）；
- 运行期间 `netstat -ano | grep 18080` 无该端口监听（18080 未被本次占用，排除"打到了别的实例"）。

---

## 4. 逐项对照表

| 自述项 | 自述值 | 实测值 | 判定 |
|---|---|---|---|
| E2E 脚本行数 | 685 行 | `wc -l` = 685 | ✅ 符合 |
| 用例数量 | 22 | 脚本实含 TC1–TC22 共 22 个用例 | ✅ 符合 |
| 全绿轮次结果 | 22/22 全绿（文档 §12 称"最终 22/22"；§2 表中为 19/19 旧数据） | 第 1 轮 22/22；第 2 轮 22/22 | ✅ 符合 |
| 覆盖：导出（条件/进度/打包） | 有 | TC4/TC5/TC6/TC11 通过 | ✅ 符合 |
| 覆盖：导入 ZIP/模板/匹配/检查/草稿/发布 | 有 | TC7–TC10、TC12–TC14 通过 | ✅ 符合 |
| 覆盖：SSE 进度 | 有 | TC11 收到 `snapshot/progress/done` 事件（样例 `progress|12|RUNNING`） | ✅ 符合 |
| 覆盖：AI 流式 + 工具 | 有 | TC15 通过（模型自发调用 `list_config_defs`） | ✅ 符合 |
| 覆盖：渐进披露 | 有 | TC15 断言 `/api/ai/tools?page=export` 无 `start_publish`、有 `start_export`/`get_ui_state`；`page=import` 有 `start_publish` | ✅ 符合 |
| 覆盖：HITL 确认 | 有 | TC17 通过（拒绝不执行、同意后 PUBLISHED） | ✅ 符合 |
| 覆盖：AI 会话历史 | 有 | TC22 通过（用户/助手/工具卡齐全，未知会话返回空） | ✅ 符合 |
| 覆盖：任务中心/分页 | 有 | TC20/TC21 通过 | ✅ 符合 |
| 文档一致性 | — | `docs/05` 三个章节写 22/22，但 `docs/05:19` 与 `docs/04:4` 仍写"19/19"、"19 个测试组" | ⚠️ 老文档残留（见 §6.2） |

---

## 5. 失败用例归因

**无失败用例**，故无归因结论。两轮均在**真实模型**参与下全绿，未出现重跑才通过的情况。

---

## 6. 实测中观察到的异常（均不改变 22/22 的结论，记录备查）

### 6.1 后端日志中的 2 类 ERROR/WARN（每轮各出现 2 次）

**（a）`ClientAbortException`（ERROR，每轮 2 次）** —— 出现在 `18:58:01.689`、`18:58:08.790`（第 1 轮）：

```
org.springframework.web.context.request.async.AsyncRequestNotUsableException: ServletOutputStream failed to flush: java.io.IOException: 你的主机中的软件中止了一个已建立的连接。
Caused by: org.apache.catalina.connector.ClientAbortException
	at com.example.configadmin.ai.AiRuntimeService.lambda$doChat$0(AiRuntimeService.java:87)
	at com.example.configadmin.ai.HooksToolCallingManager.executeToolCalls(HooksToolCallingManager.java:86)
	at org.springframework.ai.openai.OpenAiChatModel.lambda$internalStream$8(OpenAiChatModel.java:373)
...
2026-10-06T18:58:01.691  WARN ... ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler GlobalExceptionHandler#handleOther(Exception)
org.springframework.http.converter.HttpMessageNotWritableException: No converter for [class com.example.configadmin.common.R] with preset Content-Type 'text/event-stream'
```

**判定：客户端主动断开导致，非代码缺陷、非用例失败。** 依据：
1. 脚本 `Read-Sse` 在 `StopOn` 命中时**设计上就 break 并关闭流**；TC17 恰好以 `@('confirm_tool')` 作为 StopOn（脚本 604、616 行），两轮各 2 次断开与该用例的 2 次订阅一一对应；
2. 异常类型是 `ClientAbortException`（对端关闭），且异常发生在 `SseEmitter.send` 时（后端仍在向已断开客户端写）；
3. 同一时刻脚本侧该用例已断言通过（`[PASS] TC17`），随后仍能继续执行 TC18/TC22/TC19；
4. 附带暴露一个**日志噪音**小问题：`GlobalExceptionHandler` 试图把 JSON 返回体写进 `text/event-stream` 响应，二次报错（不影响行为，仅使日志更难读）。

**（b）`明细写入失败`（WARN，每轮 2 次）** —— `18:56:59.550`、`18:57:03.129`（第 1 轮）与其第 2 轮对应时刻：

```
WARN ... c.e.configadmin.service.ImportService : 明细写入失败
java.nio.file.NoSuchFileException: .\data\imports\3\PROJECT_QUOTA.issues.json
	at com.example.configadmin.service.ImportService.writeIssuesDetail(ImportService.java:566)
	at com.example.configadmin.service.ImportService.performCheck(ImportService.java:366)
```

**判定：这是本分支的一处真实小缺陷，但不会让用例失败（属"非阻断"）。** 依据：
1. 触发场景是 TC10c 与 TC14——**批次内没有任何已上传文件**（批次 C / 批次保护），上传阶段没有创建 `data/imports/<batchId>/` 目录，检查阶段仍尝试写 `<批次目录>/PROJECT_QUOTA.issues.json` → 目录不存在；
2. 异常被 `catch` 成 WARN，检查流程继续，`errorCount` 由内存对象计算，因此 `TC10c`（`files[0].errorCount >= 1`）与 `TC14`（批次 FAILED、发布被拒）断言仍成立；
3. 后果面：这两个批次的"问题明细文件"没有落盘，若有接口按文件读取明细将拿不到该文件（脚本未覆盖此路径，故未被检出）。两轮均稳定复现（各 2 次），不是偶发。

### 6.2 文档残留与代码不一致（文档问题）

- `docs/05-验证结果.md:19`：「三轮均 **19/19** 全绿」；`docs/04-测试用例.md:4`：「19 个测试组」。而同一份 `docs/05` 的 §8/§10/§12 与 `docs/03:51` 均为 22。**同一分支内自述数字自相矛盾**，实测以 22 为准（HEAD 版本脚本确为 22 个用例）。
- 无遗留 TODO/FIXME 影响本次核验。

### 6.3 脚本自身的输出瑕疵

`scripts/verify-e2e.ps1:173`：`Say ' E2E 验证开始  base=' + $Base + '  tmp=' + $tmp` —— PowerShell 把 `+` 之后的部分当作 `Say` 的额外位置参数，函数只取 `$msg`，故回显为 `base=`（空）、`tmp=`（空）。属**打印瑕疵**，不影响 HTTP 请求（`$Base` 在 `$http` 调用中正常生效，§3.5 已旁证）。

---

## 7. 环境收尾确认

- 核验结束后已关闭本次启动的后端进程（`taskkill /PID 11148 /F`，`/PID 20440`、`/PID 10792` 为 V4 重启轮次）；
- `netstat -ano | grep 18291` 结果：**无 LISTENING**，仅剩少量 `TIME_WAIT` 客户端连接（TCP 四次挥手残留，非监听）；**18291 已释放**；
- 未触碰 18080（本机该端口无监听，且本次未使用）/18290/18292/18293/18294；
- 未做任何 git 写操作；被核验分支工作区 `git status --porcelain` 为空（运行产物 `backend/data/`、`target/` 均被分支 `.gitignore` 忽略）；
- API Key 仅存在于进程环境变量，证据文件不包含密钥（全文检索 0 命中）。

---

## 8. 结论

**【实测-符合】**

- 真实通过数：**22 / 22**（第 1 轮 `PASS=22 FAIL=0`，第 2 轮 `PASS=22 FAIL=0`），与自述"22 用例全绿"数量一致；脚本行数实测 685 行，与自述一致；
- 覆盖范围与自述一致：导出（条件/进度/打包）、导入（模板/ZIP/匹配/检查/依赖/草稿/发布）、SSE 进度、AI 对话（流式/工具/渐进披露/HITL 确认）、AI 会话历史均有用例且有真实断言；
- AI 相关用例由**真实 DeepSeek `deepseek-flash`** 驱动（模型链路栈帧、AI 会话落库、直连 API 200 三重旁证）；
- 遗留问题（不阻断合流，建议进 backlog）：
  1. 未上传文件批次的问题明细写盘 `NoSuchFileException`（`ImportService.writeIssuesDetail` 未建目录），稳定复现，属真实缺陷；
  2. `GlobalExceptionHandler` 对 SSE 请求二次写 JSON 报错，属日志噪音；
  3. `docs/04:4`、`docs/05:19` 仍写 19 个用例，与 22 矛盾；
  4. `verify-e2e.ps1:173` 的 base 回显为空，建议修成 `Say ("...{0}..." -f $Base)`。

---

## 附件清单

| 文件 | 内容 |
|---|---|
| `V3-附件-e2e-run1-原始输出.txt` | 第 1 轮脚本完整 stdout（51 行，含 22 条 PASS 与末行 `PASS=22 FAIL=0`） |
| `V3-附件-e2e-run2-原始输出.txt` | 第 2 轮脚本完整 stdout（幂等重跑） |
| `V3-附件-后端启动与运行日志.log` | 后端 18:56–19:01 完整日志（622 行，含异常栈原文；原文件为 UTF-8 与 javac GBK 警告混合编码，已逐行转码为 UTF-8） |
| `V3-附件-DeepSeek直连响应.json` | `https://api.deepseek.com/chat/completions` 直连 200 响应原文（模型 `deepseek-flash`） |
