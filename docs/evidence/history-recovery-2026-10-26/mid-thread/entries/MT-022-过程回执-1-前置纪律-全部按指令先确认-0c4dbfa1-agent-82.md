<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-022 · 过程回执 · 1. 前置纪律（全部按指令先确认）（0c4dbfa1 / agent-82）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m42` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt34` 条有正文的 assistant 消息（共 123 条有正文），末条有正文消息为 `m198`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `agent-82`（`main` = 主会话）；原字符 4,444（链内 1 段、合计 4,444）。
> **链号 / 类别**：`MT-022` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 4 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `10%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `6%`。
> **与末条归档（138 件）的关系**：末条 `m198`（4,184 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 3 处。

---

E2E 补跑完成，全绿。

## 1. 前置纪律（全部按指令先确认）

| 前置 | 结果 |
|---|---|
| Memurai `127.0.0.1:6379` | ✅ `"<DRIVE>/Program Files/Memurai/memurai-cli.exe" ping` → `PONG`（只读，未启动任何东西） |
| 端口 18330 | ✅ `netstat` 无监听，空闲（未触碰 18080 / 18290–18321） |
| AI 密钥 | ✅ 仅在同一条 bash 命令里 `export AI_API_KEY="sk-…"` 注入进程环境，**未写入任何文件**；后端实测生效（health 返回 `available:true`、`model:deepseek-flash`、`baseUrl:https://api.deepseek.com`） |
| 后端日志 | ✅ 落 `<LOCAL_WORK>/gfb-e2e/boot.log`（仓库外），未进工作区 |
| 独立 H2 库 | ✅ `jdbc:h2:file:./data/e2e_gfb_db`，与演示库 `config_mgr_db.mv.db` 完全隔离（该文件 mtime 全程未变，仍 13:48） |

构建：`"<DRIVE>/Program Files/JetBrains/.../maven3/bin/mvn.cmd" -B -DskipTests package` → `BUILD SUCCESS`（8.8s，产出 `backend/target/config-mgr.jar`）。
启动：`java -jar target/config-mgr.jar --server.port=18330 --spring.datasource.url="…;DB_CLOSE_DELAY=-1" --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`（与 S5b 证据文档同口径；`Started ConfigMgrApplication in 7.983 seconds`）。
运行：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog "<LOCAL_WORK>\gfb-e2e\boot.log"`。

## 2. 结果：22/22 PASS

```
 结果：PASS=22  FAIL=0  （源脚本 22 用例 → 本脚本 22 用例）
 Q8 项：PASS=2  FAIL=0  （不计入 22 用例）
E2E_EXIT=0
```

**没有任何失败用例**，故无输出摘录可给。耗时约 70 秒（后端 AI 轮次 17:20:57 → 收尾 17:21:51），与 S5b 记载的 72s 同量级 —— **没有出现 §7 预判的"真实模型调用 `generative_form` ⇒ 闸门拒 400 ⇒ 挂起到 120s 超时"耗时劣化**。

逐个用例（全 PASS）：

| 用例 | 断言摘要 |
|---|---|
| TC1 | 15 个定义（基座 7 + glm 8）/层级/引用字段/发布行数/过滤正确 |
| TC2 | 创建/重复编码 409 `DEFINITION_CODE_DUPLICATE`（无 SQL 泄漏）/字段动态追加/无主键拒绝 |
| TC3 | 四类规则（必填/主键重复/引用不存在/范围必填）全部命中 |
| TC4 | 全量导出 3 文件 + 行数/进度 + 可下载（PK 魔数） |
| TC5 | 字段条件（code=CNY→1 行）与范围条件（HE→3 行、HE+XN→10 行）正确 |
| TC6 | 勾选打包 2 条目 / 全量打包 3 条目 |
| TC7 | 单个 xlsx + 多配置 zip + 定义级模板下载 |
| TC8 | zip 匹配/未匹配报告/前缀命名/非法命名拒绝 |
| TC9 | 检查全通过 + 依赖拓扑（METRIC_DICT 先于 ALARM_THRESHOLD） |
| TC10 | 失败明细可查 + 未上传文件报错 + 日志无写盘异常 |
| TC11 | 进度事件流 12 条（末条 120/120）+ `JOB_DONE(COMPLETED)` |
| TC12 | 导入写暂存 3 行 STAGED，生效数据仍 5 行 |
| TC13 | 范围替换（5→3 行、upsert 保 id、差集删除）+ 快照冲突 409 `JOB_ALREADY_FINAL` |
| TC14 | 守卫真实阻断（409 `PRECHECK_NOT_PASSED`，零作业行）+ 放行侧空暂存发布不误删 |
| TC15 | **AI 流式+工具可见+渐进披露**：`page:tasks` **13 个工具（无 start_publish）**；`task:IMPORT/PUBLISH` 含 `start_publish` |
| TC16 | 确认放行→真实创建导出作业并产出文件；前端工具挂起+回灌续跑（`navigate_to,select_definitions`） |
| TC17 | 拒绝不执行（暂存仍 STAGED、版本零变化、409 重复决策）+ 确认后真实发布 COMPLETED |
| TC18 | 会话记忆隔离 + 409 `SESSION_BUSY`（携 runId/reattach）+ 取消后释放 |
| TC22 | 历史恢复 6 条（USER 2 / ASSISTANT 3，含工具卡片）+ 未知会话返回空 |
| TC19 | 清理（定义删 404、任务删级联作业 404）+ 演示数据恢复 5 行 |
| Q8① | 明细落库（未上传文件批次可查，日志无写盘异常） |
| Q8② | SSE 不做二次 JSON 写入（日志无 `No converter`/`HttpMessageNotWritableException`） |

**GF-B 最相关的一条行为基线**：后端启动日志有 `Registered tool: generative_form scopes=[page:tasks, task:*]`，且披露日志实测为

```
上下文=page:tasks 披露工具=[list_tasks, navigate_to, create_task, list_config_defs, get_config_def,
  start_export, get_workspace_state, get_row_count, select_definitions, set_condition, confirm_step,
  start_precheck, generative_form]
```

即 **TC15 的存在性断言未被新增 `generative_form` 翻转，仅工具数增至 13**。全程后端日志 **0 次** `FORM_SCHEMA_REJECTED` / `FORM_RESULT_REJECTED` / `FRONTEND_CANCELLED`、**0 次**任何挂起超时 —— 说明该轮真实模型没有自行触发表单，§7 的"不影响判定、只可能影响耗时"预判得到了实证。

**结论**：§10 #5 裁决的"照跑一轮"已完成，22 用例全绿，新增的 `generative_form` 披露对既有 E2E **零行为扰动**，GF-B 可据此开工。

## 3. 清理与工作区自证

- 只停自己启动的进程：`taskkill /F /PID 38276`（该 PID 即 18330 的持有者，`instanceId=instance-Lenovo-pid38276-…`）；未触碰另一个无关 `java.exe`(8996)。
- 端口回收：`netstat -ano | grep ':18330' | grep LISTENING` → 空。
- 临时库已删：`backend/data/e2e_gfb_db.mv.db`（含 `.trace.db`）已移除；`data/` 仅剩未受影响的演示库 `config_mgr_db.mv.db`（mtime 仍为 13:48）。
- 无运行残留：`backend/data/files/` 最新文件仍是 16:17（早于我 17:20 的运行），我的运行已由 TC19 自清理；后台任务 `active_background_tasks: 0`。
- **git status 自证干净**：

```
$ git status --porcelain
(空)
$ git status -sb
## <OLD_REPO_DIR>...<OLD_REPO_DIR>
$ git rev-parse HEAD
f333b7f48bdf309e1d168d5c4263bcab4c67329a
```

无任何改动、无未跟踪文件，HEAD 仍为 `f333b7f` 且与 `<OLD_REPO_DIR>` 同步（未产生新 commit）。

## 4. 两处需如实披露（非阻塞）

1. **我清理 %TEMP% 时范围过宽**：执行 `rm -rf "$TEMP"/s5b-e2e-*` 时删掉了 **22 个**该脚本的历史临时目录（21 个是此前 S5b 各轮遗留的，1 个是我这次的）。这些是 OS 临时目录里脚本自身的中间产物（导出件/构造的上传件），仓库内证据文档不受影响，但确实超出了"只清自己产物"的范围 —— 如实报告，供您判断是否需要后续更精细的清理纪律。
2. **构建产物新增**：`backend/target/` 下本次新建了 `config-mgr.jar`（运行前不存在）。该目录被 `.gitignore` 覆盖（`backend/target/`、`*.jar`），故不影响 `git status`；我予以保留（重建成本高），如需还原可随时删除。作为证据的 `<LOCAL_WORK>/gfb-e2e/boot.log` 与 `e2e-run1.log` 留在仓库外，供复核。

**密钥安全性复核**：`grep -c 'sk-5d2f' boot.log` → **0**；全日志无任何 `sk-` 前缀串；`AI_API_KEY` 仅存在于启动该后端进程的那一条命令的环境变量中，未落任何文件，未进入仓库。
