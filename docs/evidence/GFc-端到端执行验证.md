# GF-C 端到端执行验证（执行棒）——生成式表单 E2E 全量照跑

> 角色：提交官（验收与取证）。**零产品代码改动、零 git 写操作**；本文档为唯一新增文件，保持未提交（提交官统一入库）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `d19da30`。
> 执行日期：2026-10-07。
> **首轮**（未修复）：19:40:01 → 19:41:34（93 秒）——§1–§9 记录此轮及其根因分析。
> **最终轮**（裁决 #1/#3/#4 修复后）：19:59:51 → 20:01:22（91 秒），`PASS=27 FAIL=0 GFSKIP=0`——**见 §10**。
> 被验对象：工作区未提交的 `scripts/verify-e2e.ps1`（含 GF1–GF5 新用例）；设计稿 `docs/evidence/GFc-E2E用例设计-GLM-5.3.md`。
> 证据等级：**全部为实测**（真实后端进程 + 真实模型 `deepseek-flash` + 真实 Redis）；未见实测的一律标注【推断】/【待裁决】。
> 脱敏：不出现真实盘符（统一 `<REPO_ROOT>` / `<TMP>`）、密钥（统一 `***`）、用户名、主机名。

---

## 0. 结论速览

**最终态（修复后重跑，§10）**

| 项 | 值 |
|---|---|
| 总账 | **PASS=27  FAIL=0　GFSKIP=0**；脚本退出码 **0** ✅ |
| 既有 22 用例（TC1–TC22） | **PASS=22 / FAIL=0**（两轮皆零扰动，§5） |
| Q8 项（不计入退出码） | **PASS=2 / FAIL=0** |
| GF1–GF5 | **全部 PASS**（GF1 FILTER / GF2 CLARIFY / GF3 取消 / GF4 复核拒绝 / GF5 409 幂等） |
| 裁决 #5 观察 | 既有用例中模型自发调用 `generative_form`：**0 次**（两轮一致，§10.4） |
| 结论 | DC-15 端到端链路（schema 下发→回灌→闸门→终态→续跑收尾）+ 取消终态 + 409 幂等 **全部成立**，既有 22 用例**零扰动** |

**首轮态（未修复，§1–§9 的取证与根因链）**

| 项 | 值 |
|---|---|
| 既有 22 用例（TC1–TC22） | **PASS=22 / FAIL=0**（零扰动实证，§5） |
| Q8 项（不计入退出码） | **PASS=2 / FAIL=0** |
| GF 新增（计入主计数） | **GF3 PASS、GF5 PASS；GF1 FAIL、GF2 FAIL、GF4 FAIL** |
| 总账 | **PASS=24  FAIL=3　GFSKIP=0**；脚本退出码 **1** |
| SKIP 硬底线（裁决 #1） | **未触发**——`GFSKIP=0`，GF1–GF4 无一 SKIP（模型 4/4 首轮即触发表单） |
| 失败性质 | **3 例 FAIL 全部落在同一处"值回显"弱旁证（A6）**；三例的链路主锚点（帧契约 / 回灌 200 / `pending.resultText` 精确相等 / 结局帧与 `done`）**全部通过**。判定为**测试侧断言不可靠**，非产品缺陷（§4） |
| 处置 | 3 例均判为**假阴性**并已按裁决 #1 修复；处置结果见 §8 与 §10 |

> 一句话：**DC-15 链路端到端成立（含取消与 409 幂等），既有 22 用例零扰动；首轮的 3 个 FAIL 是脚本自身"值回显"断言的采集缺陷（非产品缺陷），已按裁决修复并重跑至全绿。**

---

## 1. 执行环境与命令（脱敏，原样可复现）

### 1.1 前置纪律（逐条先确认）

| 前置 | 命令 / 结果 |
|---|---|
| Memurai（Redis 兼容） | `"<MEMURAI>/memurai-cli.exe" ping` → `PONG`（只读，未启动任何服务） |
| 端口 18330 | `netstat -ano \| grep 18330` → 无监听，空闲（未触碰 18080 / 18290–18299 / 18301–18321） |
| AI 密钥 | 仅在同一条命令内经环境变量注入进程（`AI_API_KEY=***`），**未写入任何文件**；health 端点回显 `available=true`、`model=deepseek-flash`、`baseUrl=https://api.deepseek.com` |
| 独立 H2 库 | `--spring.datasource.url="jdbc:h2:file:./data/e2e_gfc_db;DB_CLOSE_DELAY=-1"`，与演示库 `config_mgr_db.mv.db` 隔离（该文件 mtime 全程未变） |
| 后端日志 | 落 `<TMP>/boot-gfc.log`（仓库外），`com.example.configmgr` DEBUG 级 |

### 1.2 命令（原样）

```bash
# ① 复用已构建的 jar（依据见 §1.3；本棒未改后端，故不重建）
cd <REPO_ROOT>/backend
rm -f data/e2e_gfc_db.mv.db data/e2e_gfc_db.trace.db
AI_API_KEY="***" java -jar target/config-mgr.jar \
  --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/e2e_gfc_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 > <TMP>/boot-gfc.log 2>&1 &

# ② 全量照跑（22 既有 + GF1–GF5；未开 -EnableGf6）
cd <REPO_ROOT>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog "<TMP>/boot-gfc.log" > <TMP>/e2e-gfc-run1.log 2>&1
```

### 1.3 "复用上次构建的 jar"的依据（可机械复核）

本棒判定**无需重建**，依据两条同时成立：

1. `git diff --name-only <上次构建的 HEAD>..HEAD -- backend/` → **空**，即从上一次构建到本棒 HEAD `d19da30` 之间**后端源码零改动**（该区间只有前端 `frontend/**` 与 `docs/**` 变动）；
2. `find backend/src -type f -newer backend/target/config-mgr.jar` → **空**，即没有任何后端源码比该 jar 新。

另：GF-C 的被验对象是 `scripts/verify-e2e.ps1`（后端 API 层 E2E），脚本自身扮演前端（读 SSE + POST 回灌），**不加载前端产物**，故 `frontend/**` 的 GF-B 改动不影响本轮可观测行为。

---

## 2. 逐用例结果表

执行顺序沿用脚本既有编排：TC1–TC14 → TC20/21 → TC15/16 → **GF1–GF5** → TC17 → TC18 → TC22 → TC19 收尾。

### 2.1 既有 22 用例 + Q8（全部 PASS）

| 用例 | 结果 | 断言摘要（脚本原文） |
|---|---|---|
| TC1 | PASS | 15 个定义（基座 7 + glm 8）/层级/引用字段/发布行数/过滤正确 |
| TC2 | PASS | 定义创建/重复编码 409 `DEFINITION_CODE_DUPLICATE`（无 SQL 泄漏）/字段动态追加/无主键拒绝 |
| TC3 | PASS | 四类规则（必填/主键重复/引用不存在/范围必填）全部命中且给出明细 |
| TC4 | PASS | 全量导出 3 文件 + 行数/进度正确 + 文件可下载（PK 魔数） |
| TC5 | PASS | 字段条件（code=CNY→1 行）与范围条件（HE→3 行、HE+XN→10 行）正确，3 行子集件已保存 |
| TC6 | PASS | 勾选打包（2 条目）与全量打包（3 条目）均正确 |
| TC7 | PASS | 模板：单个 xlsx + 多配置 zip + 定义级模板下载正常 |
| TC8 | PASS | zip 匹配/未匹配报告/前缀命名/非法命名拒绝均正确 |
| TC9 | PASS | 检查全通过 + 依赖拓扑（METRIC_DICT 先于 ALARM_THRESHOLD）+ 条目状态 CHECKED |
| TC10 | PASS | 检查失败明细可查（分页/总数/归属正确）+ 未上传文件报错 + 日志无写盘异常 |
| TC11 | PASS | 收到进度事件流 12 条（末条 120/120）+ `JOB_DONE(COMPLETED)` |
| TC12 | PASS | 导入写暂存（3 行 STAGED），生效数据保持 5 行不变 |
| TC13 | PASS | 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 快照冲突拦截重复发布 + 409 `JOB_ALREADY_FINAL` |
| TC14 | PASS | 守卫真实阻断（409 `PRECHECK_NOT_PASSED`，零作业行；PROJECT_ENV 28 行不变）+ 放行侧空暂存发布不误删 |
| TC20 | PASS | 统一列表/创建即持久化/类型与状态过滤/关键词转义（`%` → 0 命中）正确 |
| TC21 | PASS | 任务分页正确（total=14，第 1 页 5 条 / 第 2 页 5 条，无重复） |
| TC15 | PASS | 流式+工具调用可见+渐进披露（只读端点 `/api/ai/tools`）：`page:tasks` 13 个工具（无 `start_publish`）；`task:IMPORT/PUBLISH` 含 `start_publish` |
| TC16 | PASS | 确认放行→真实创建导出作业并产出文件；前端工具挂起+回灌续跑（`navigate_to,select_definitions`） |
| TC17 | PASS | 拒绝不执行（暂存仍 STAGED、版本零变化、409 重复决策拦截）+ 确认后真实发布（PUBLISH COMPLETED） |
| TC18 | PASS | 会话记忆隔离 + 409 `SESSION_BUSY`（携 runId/reattach）+ 取消后释放 |
| TC22 | PASS | 历史恢复：6 条（USER 2 / ASSISTANT 3，含工具卡片）+ 未知会话返回空 |
| TC19 | PASS | 清理完成（定义删除 404、任务删除级联作业 404）+ 演示数据恢复 5 行 |
| Q8① | PASS | 明细落库（未上传文件批次的明细可查，日志无写盘异常） |
| Q8② | PASS | SSE 请求不做二次 JSON 写入（日志无 `No converter`/`HttpMessageNotWritableException`） |

### 2.2 GF 新增用例（GF1–GF5）

| 用例 | 路径 | 结果 | 失败/通过点（脚本原文） |
|---|---|---|---|
| **GF1** | FILTER 回灌续跑（`page:tasks`） | **FAIL** | `值回显缺 'E2EGFC'`（A6；A1–A5、A7 未及执行即在此抛出） |
| **GF2** | CLARIFY 回灌续跑（`task:EXPORT/QUERY_COND`） | **FAIL** | `值回显缺 'includeInactive='`（A6） |
| **GF3** | 取消路径（`cancelled:true`） | **PASS** | `cancelled:true→FRONTEND_CANCELLED 终态（帧/pending/日志三面一致）+ 模型收尾` |
| **GF4** | 复核拒绝（400→改值重发） | **FAIL** | `续跑值回显缺 E2EGFC`（A5 的值回显子断言） |
| **GF5** | 幂等（409 双向，零模型轮次） | **PASS** | `值路径/取消路径重复回灌均 409 DUPLICATE（终态不改写、无新帧）` |

**GFSKIP = 0**：4 个需要模型触发表单的用例（GF1–GF4）**全部在首轮尝试即收到 `frontend_tool_request`**，无一例走 SKIP 分支，故 §3.3 的 SKIP 归因判定表（披露端点交叉取证）**本轮未被触发**（详见 §6）。

---

## 3. 逐用例关键取证（帧序列 / HTTP 码 / 日志）

原始附件全部落 `<TMP>`（仓库外、不提交）。**首轮**附件在 `<TMP>/run1/` 子目录（最终轮见 §10，落在 `<TMP>/run5/`）：

- `<TMP>/run1/e2e-gfc-run1.log` —— 脚本 stdout 全量（22 + GF 的 PASS/FAIL 与摘要行）
- `<TMP>/run1/boot-gfc.log` —— 后端 DEBUG 全量日志
- `<TMP>/run1/gfc-log-excerpts.txt` —— 后端日志关键行摘录（按 A–F 分组）
- `<TMP>/run1/gfc-GF{1..4}.sse.txt` —— 各 GF 轮的原始 SSE 状态帧（见下方口径说明）
- `<TMP>/run1/gfc-GF{1..4}.history.json`、`<TMP>/run1/gfc-GF{1..4}.pending.json` —— 会话记忆与挂起条目快照
- `<TMP>/run1/gfc-GF5-http.txt` —— GF5 两次 409 的原始请求/响应

> **附件口径说明（诚实标注）**：**首轮时**脚本自身不落帧文件（`grep 'sse\.txt' scripts/verify-e2e.ps1` → 0 命中，与设计稿 §6 "原始记录归档建议" 有偏差）；因此 §3 用的 `gfc-GF*.sse.txt` 是本棒**跑完后**用只读端点在**同一后端进程**上取得的原始回放帧（`GET /api/ai/events/{runId}?lastSeq=0`），内容**只含状态帧、不含 `delta`**（reattach 回放不含 delta）——这一限制恰是 §4 根因的关键。**该偏差已按裁决 #4 修复**：脚本现已自行落盘每用例的 SSE 帧全文（含 delta）与 HTTP 原文，最终轮附件见 §10。

### 3.1 GF1（FILTER）——链路全通，唯 A6 值回显失败

原始回放帧（`<TMP>/gfc-GF1.sse.txt`，逐帧照录，见附件的 `seq / type` 全序列）：

```
seq1  start
seq2  suspended        （toolCalls: generative_form, kind=FRONTEND, status=PENDING, executed=false）
seq3  tool_start       name=generative_form kind=FRONTEND
                      args={"form":{"scenario":"FILTER","title":"导出筛选条件","fields":[
                            {"key":"keyword","type":"text","required":true,...},
                            {"key":"minRows","type":"number"},
                            {"key":"effectiveDate","type":"date"},
                            {"key":"scope","type":"enum","options":[{"value":"XN"},{"value":"HD"}]}]}}
seq4  frontend_tool_request  toolCallId=call_00_DiW8…, timeoutSeconds=120, expiresAt=1791373364510,
                             callback="POST /api/ai/frontend-tool-result {runId, toolCallId, result}"
seq5  frontend_tool_result   ok=true executed=false status=FRONTEND_RESULT
                             result={"keyword":"E2EGFC","minRows":1,"effectiveDate":"2026-10-07","scope":"XN"}
seq6  message_start    （助手文本开始）
seq82 message_end      chars=150
seq83 done             model=deepseek-flash terminalSignal=finishReason=STOP usage.totalTokens=7224
```

- **A1 帧契约**：`toolCallId` 非空、`timeoutSeconds=120`、`expiresAt` 为数字 → 通过（断言在 suspend 回调内执行，未抛出）。
- **A2 schema**：`scenario=FILTER`、四 key 齐备、六型白名单内 → 通过。
- **A3 回灌 HTTP 码**：**200** + `status=FRONTEND_RESULT` + `accepted=true` + `executed=false` → 通过。
- **A4 挂起终态（主锚点）**：`<TMP>/gfc-GF1.pending.json` → `status=FRONTEND_RESULT`、`executed=false`，
  且 `resultText` **逐字符等于**脚本回灌的 JSON：
  `{"keyword":"E2EGFC","minRows":1,"effectiveDate":"2026-10-07","scope":"XN"}` → 通过。
- **A5 结局帧**：恰 1 条 `frontend_tool_result(ok=true)` + 恰 1 条 `done` + 无 `error` → 通过（否则会先抛出 `frontend_tool_result 异常` / `done 帧不恰为 1 条`）。
- **A6 值回显**：**失败** —— 捕获到的 `delta` 文本非空，但不含 `E2EGFC`。
- **A7 日志披露**：因 A6 先抛出而未执行（脚本为顺序断言）。

模型侧独立佐证（`<TMP>/gfc-GF1.history.json`，会话记忆原文）——**模型确实正确收到了值并原样回报**：

| # | type | 关键字段 |
|---|---|---|
| 0 | USER | 提示词原文 |
| 1 | ASSISTANT | `text=""`，`toolCalls=[{name:"generative_form", arguments:"{\"form\":{\"scenario\":\"FILTER\",…}}"}]` |
| 2 | TOOL | `toolResponses=[{name:"generative_form", responseData:"{\"keyword\":\"E2EGFC\",\"minRows\":1,\"effectiveDate\":\"2026-10-07\",\"scope\":\"XN\"}"}]` |
| 3 | ASSISTANT | `已收到你填写的表单，内容如下（按 key=值 逐行回报）：` … `- keyword=E2EGFC` / `- minRows=1` / `- effectiveDate=2026-10-07` / `- scope=XN` …（len=150） |

> 注意 msg1 的 `text=""`：模型**未产出任何挂起前的前言文本**。这一条是 §4 定位根因的关键排除性证据。

### 3.2 GF2（CLARIFY）——同构，仅 A6 失败

- 帧序列与 GF1 同构（`scenario=CLARIFY`，两字段 `exportScope` / `includeInactive`）；回灌 POST **200**、`status=FRONTEND_RESULT`。
- pending 主锚点（`<TMP>/gfc-GF2.pending.json`）：`status=FRONTEND_RESULT`，`resultText` **精确等于**回灌值
  `{"exportScope":"ALL","includeInactive":true}` → 通过。
- 模型回报（`<TMP>/gfc-GF2.history.json`）含 `exportScope=ALL`、`includeInactive=true` → 链路正确。
- **A6 失败**：`值回显缺 'includeInactive='`。
- GF2 的 fixture 任务（`GFC-TC-GF2-澄清`）按 A7 用例内自删，预清理追加段另删残留 **0 个**（见运行日志首部）。

### 3.3 GF3（取消路径）——PASS，三面一致

原始帧（`<TMP>/gfc-GF3.sse.txt`）：

```
seq1 start → seq2 suspended → seq3 tool_start
seq4 frontend_tool_request   toolCallId=call_00_F1nXy…, timeoutSeconds=120
seq5 frontend_tool_result    ok=false status=FRONTEND_CANCELLED
                             result="FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写），本次调用未获得任何数据；如需继续，请改为向用户询问或换用其它方式。"
seq6 message_start → seq54 message_end(chars=97) → seq55 done
```

- **取消 POST → HTTP 200**，`status=FRONTEND_CANCELLED`、`cancelled=true`、`executed=false`。
- **pending 终态**（`<TMP>/gfc-GF3.pending.json`）：`status=FRONTEND_CANCELLED`、`executed=false`、
  `reason=FRONTEND_DISMISSED source=e2e-gf`、`resultText` 以 `FRONTEND_CANCELLED：` 开头 → 与设计稿 A2/A4 完全一致。
- **后端日志**（`<TMP>/gfc-log-excerpts.txt` §B，INFO）：
  `前端工具被用户取消 runId=8ffa313b-… toolCallId=call_00_F1nXyqR1sbxl7plf43mG9405 name=generative_form`
- **不重复下发**：同 `toolCallId` 的 `frontend_tool_request` 仅 1 次（A6）→ 通过。
- **模型收尾**：`message_end chars=97`，模型文本为「表单已被取消：用户关闭了表单（FRONTEND_CANCELLED），本次没有收到任何数据…」→ 与取消终态一致（佐证收敛语义）。

### 3.4 GF4（复核拒绝 → 改值重发）——A1–A4 全通，仅 A5 值回显失败

原始帧（`<TMP>/gfc-GF4.sse.txt`）：

```
seq1 start → seq2 suspended → seq3 tool_start
seq4 frontend_tool_request   toolCallId=call_00_V2SB0l8uXJRkBWmBu81z3731
seq5 frontend_tool_result    ok=true status=FRONTEND_RESULT result={"keyword":"E2EGFC","scope":"XN"}
seq6 message_start → seq81 message_end(chars=139) → seq82 done
```

- **A1 违规值 400**：后端 WARN（`<TMP>/gfc-log-excerpts.txt` §C）——
  `generative_form 回灌结果未通过类型复核（回灌被拒、挂起保持未决）：[未知字段：extra（表单字段：keyword, amount, scope）, 字段 keyword 期望 text（字符串），实际是数字, 字段 amount 期望 number（JSON 数字），实际是文本："abc", 字段 scope 的值 "ZZ" 不在选项内（XN/HD）]`
  → **reasons 恰 4 条**（与裁决 #3 的"推演应为 4 条"一致，断言取 ≥3 留余量）；伴随 ConfirmGate WARN：
  `前端工具回灌被安全闸拒绝 runId=8f8e18eb-… name=generative_form code=FORM_RESULT_REJECTED（状态不变，挂起继续等待）`。
- **A2 400 后挂起仍 PENDING**：`GET /api/ai/pending/{runId}` 该条目保持未决 → 通过。
- **A3 3 秒窗口无新帧**：服务端侧独立佐证（`<TMP>/gfc-log-excerpts.txt` §E）——
  `reattach runId=8f8e18eb status=SUSPENDED lastSeq=4 回放帧数=0`：窗口读取**确实到达服务端**且回放 0 帧、run 仍 `SUSPENDED`，即"闸门不发帧、挂起不被消费"为真，**该断言非空转**（详 §7 O3）。
- **A4 同 toolCallId 改值重发 → 200** + `status=FRONTEND_RESULT` + `accepted=true` → 通过。
  随之 `reattach runId=8f8e18eb status=RUNNING lastSeq=4 回放帧数=1`（即 seq5 结局帧）。
- **A5 结局帧**：恰 1 条 `frontend_tool_result(ok=true)`（**无** `ok=false` 的前置结局帧）+ `done` 到达 + 无 `error` → 通过。
- **A5 值回显**：**失败** —— `续跑值回显缺 E2EGFC`。
- 模型回报（`<TMP>/gfc-GF4.history.json`）含 `keyword=E2EGFC`、`scope=XN`，并主动说明 `amount` 未填 → 链路正确。
- pending 主锚点：`status=FRONTEND_RESULT`，`resultText` 精确等于 `{"keyword":"E2EGFC","scope":"XN"}` → 通过。

### 3.5 GF5（幂等 409 双向）——PASS，本棒另行独立复现

GF5 零模型轮次，仅复用 GF4（已回灌）与 GF3（已取消）的 `runId/toolCallId`。脚本断言通过；本棒另在**同一后端进程**上独立重放两次（原始响应落 `<TMP>/gfc-GF5-http.txt`）：

| 路径 | 请求 | HTTP | 响应体（原文摘录） |
|---|---|---|---|
| A1 值路径 | 对 GF4 已决条目重发合规值 | **409** | `{"code":"DUPLICATE_TOOL_CALL_ID","message":"该 toolCallId 已有决策或结果，重复提交被拒绝","status":"FRONTEND_RESULT","duplicate":true}` |
| A2 取消路径 | 对 GF3 已取消条目再发 `cancelled:true` | **409** | `{"code":"DUPLICATE_TOOL_CALL_ID",…,"status":"FRONTEND_CANCELLED","duplicate":true}` |

→ 两条路径均 409 且**回显既有终态、不被改写**（取消终态不会被重复提交翻转，`duplicate=true`），与 GFa §4.2 幂等口径一致。
A4 无新帧：`reattach runId=8f8e18eb status=DONE lastSeq=82 回放帧数=0` 与 `reattach runId=8ffa313b status=DONE lastSeq=55 回放帧数=0`（§E）——两次重挂均到达服务端、回放 0 帧。

---

## 4. 三个 FAIL 的根因判定：**测试侧断言不可靠，非产品缺陷**

### 4.1 失败点精确定位

三例失败信息**全部**是该子断言的抛出文本（脚本内唯一来源）：

```
A6: $echo = (delta 帧文本拼接) -join "`n"
    if ($echo) { foreach ($piece in @('E2EGFC','XN','scope=')) { if ($echo -notlike "*$piece*") { throw "值回显缺 '$piece'" } } }
    else { Write-Host '  [INFO] … 值回显 delta 未收集到（受模型速度影响；主锚点 pending.resultText 已精确断言）' }
```

- GF1/GF4 在 `'E2EGFC'` 上失败、GF2 在 `'includeInactive='` 上失败；
- 由于脚本为**顺序断言**且 A6 位于 A3/A4/A5 之后，**A1–A5 全部通过**才可能走到 A6；
- 因此三例的**链路结论是成立的**：帧契约 ✓、回灌 200/`FRONTEND_RESULT` ✓、`pending.resultText` 与回灌 JSON **精确相等** ✓、结局帧恰 1 条且 `done` 恰 1 条 ✓。

### 4.2 根因链条（逐环有证据）

1. **脚本捕获到的 delta 文本不完整**（观测事实）：`$echo` 非空（否则走 INFO 非致命分支）却不含所填值。
2. **不是"模型没回显"**：会话记忆里三条会话的最终助手文本均含所填值（§3.1/3.2/3.4），且 `pending.resultText` 与回灌值精确相等——值确实进入了模型上下文并被原样回报。
3. **不是"模型有前言文本被误采"**（关键排除）：GF1 的 msg1 `text=""` ——模型挂起前**没有输出任何文本**，故挂起前不存在任何 delta。
   ⇒ 捕获到的 delta 只可能来自**回灌续跑之后**的那条助手消息，而该消息的文本前部含所填值（如 `- keyword=E2EGFC` 位于首行）——说明**捕获到的是该消息的不完整片段**（值所在的靠前部分未被捕获）。
4. **为什么续跑阶段的 delta 会丢**（机制，与证据一致）：
   - 脚本的读流策略是"`frontend_tool_request` 命中即断开原流 → POST 回灌 → `Start-Sleep 500ms` → 以 `GET /api/ai/events/{runId}?lastSeq=N` 重挂续读"；
   - 在这 500ms 间隙内，后端已把续跑产出的帧写给**已被脚本放弃的原始流**：日志实证
     `SSE 写出失败（客户端断开？）runId=cd113c8c-… type=frontend_tool_result`（GF1；GF2/GF3/GF4 各有一条同形记录，见 §D）；
   - 而 reattach 回放**只含状态帧、不含 delta**（`SseChatEmitter` 既有语义，设计稿 §1.2 已实测记载）；
   - 故间隙内产出的 delta **永久不可恢复**，脚本只能采到重挂连接之后仍在产出的**尾部片段**。
5. **结论**：A6 依赖"delta 文本完整可采"，而该前提在"POST 后重挂"的设计下**不成立**。这是脚本对既有 `Invoke-AiTurn` 蓝本的继承性缺陷（设计稿红队"中-6"已识别该风险），其修复（"收集不到不 FAIL"）**只覆盖了"完全为空"的情形，未覆盖"采到不完整片段"**——三例 FAIL 全落在这一未覆盖分支。

### 4.3 为什么 GF3 通过而 GF1/GF2/GF4 失败（交叉验证根因）

GF3 的同位置断言是**弱断言**（"delta 至少 1 条"），不要求含具体值 ⇒ 通过；
GF1/GF2/GF4 要求 delta 中含**位于消息前部的具体值** ⇒ 失败。
同为"delta 采集不完整"，强弱断言的不同结果与本次观测**完全一致**，反过来支持 §4.2 的根因判定。

### 4.4 判定与责任边界

| 维度 | 判定 |
|---|---|
| DC-15 端到端链路（schema 下发 → 回灌 → 闸门 → 终态 → 续跑收尾） | **成立**（GF1/GF2/GF4 的主锚点全通；GF3 取消全通；GF5 幂等双向全通） |
| 三例 FAIL 归属 | **测试侧**（脚本 A6 断言不可靠）；**不记为产品缺陷** |
| 是否属"模型行为问题"（设计稿 §3.3 的 SKIP 口径） | **不是**——SKIP 口径针对"模型未触发/未配合"，本三例模型**已正确触发并正确回显**，故**不适用 SKIP**，也不应记 SKIP |
| 既有 22 用例 | 零扰动（§5），本轮退出码 1 完全由 GF 的 A6 引起 |

---

## 5. 既有 22 用例零扰动论证（实证）

| 扰动面 | 论证 | 证据 |
|---|---|---|
| 断言 | 22 块零改动；GF 块插在 TC16 与 TC17 之间，独立 `try/catch` | **实测：22 例 PASS=22/FAIL=0**（`<TMP>/e2e-gfc-run1.log`） |
| 共享代码 | 仅设计稿 §5.3 的加法式兜底（`generative_form` 挂起优先按 `cancelled:true` 收敛） | **本轮该分支未被触发**（既有用例中 0 次表单挂起，§5.1），故对 22 例无可观测影响 |
| 数据面 | GF 只建/删 `GFC-*`，不碰 `S5B-*`/`E2E_TEMP`/演示数据 | TC19 收尾断言"演示数据恢复 5 行"+ 预清理（GF）删除残留 `GFC-*` **0 个** |
| 会话面 | GF 用独立 `gfc-<guid>`，TC22 用 `aiSession15` | TC18 会话隔离 PASS；TC22 历史恢复 PASS（6 条，未受 GF 会话干扰） |
| 模型行为面 | GF 提示词点名 `generative_form` 并显式排除其它工具 | **既有用例 sessions 中 0 次 `generative_form` 调用**（§5.1） |
| 耗时面 | GF 块整体约 18 秒（19:40:42 → 19:41:00），整轮 93 秒 | 见 §7 O2 的耗时账 |

### 5.1 "既有用例中是否自发调用 generative_form"——两态取证的实测结果

任务要求取证的两种状态，本轮落在**"0 次"**这一态：

- 全日志中 `generative_form` 共 22 处命中，逐条归类后**全部**属于：① 启动注册行 1 处
  （`Registered tool: generative_form scopes=[page:tasks, task:*]`）、② 各轮"披露工具=[…]"清单（DEBUG）、
  ③ **4 个 `gfc-` 轮次**（GF1 `cd113c8c` / GF2 `53f83c18` / GF3 `8ffa313b` / GF4 `8f8e18eb`）的
  `ToolActivityBeacon 工具执行退出 tool=generative_form`、闸门 WARN/INFO 行；
- **TC15/TC16/TC17/TC18 所在的 `s5b-*` 会话，无任何一次 `generative_form` 的 `tool_start` / `frontend_tool_request` / 闸门行**；
- 后果：设计稿 §5.3 的"cancelled 兜底"分支**未被触发**，亦**无需触发**——即"模型自发调用表单"的潜伏扰动面本轮**未出现**（未出现 400 兜底收敛的第二种状态）。
- 旁证（非表单专属）：日志中另有多条 `SSE 写出失败（客户端断开？）`（类型 `confirm_decision` / `frontend_tool_result` / `heartbeat` / `done`），
  属脚本"命中 stopOn 即断开原流、改用重挂续读"的**既有既定行为**，非本轮新增，且 TC16/TC17 等用例仍全绿（§7 O1）。

---

## 6. SKIP 归因（本轮为空，口径备查）

- **`GFSKIP=0`**：GF1–GF4 四个需模型触发的用例，**首轮尝试即收到 `frontend_tool_request`**（无一次换会话重试），
  日志中 4 个 `gfc-` 轮次均为"本轮第 1 次尝试组装请求"（无第 2/3 次），**未出现 schema 漂移**（`gfSchemaDrift` 未被置位）。
- 故设计稿 §3.3 的 SKIP 判定表（含"披露端点交叉取证"）**本轮无对象可归因**；
- 按该表"整轮无 `frontend_tool_request`"一行的取证方式，本轮的反向事实为：披露端点
  `GET /api/ai/tools?page=tasks` 含 `generative_form`（GF1 断言前置即校验，且 TC15 独立断言 `page:tasks` 13 个工具）——**披露与模型触发两侧都成立**。
- 硬底线（裁决 #1：GF1–GF4 全 SKIP ⇒ 整棒 FAIL）**未触发**；本轮退出码 1 的成因是 3 个 A6 FAIL，**不是** SKIP 底线。

---

## 7. 残留观察（含任务点 4 的两项）

> **处置状态（裁决后）**：O1 与 O4 已按裁决修复并重跑验证（见 §10）；O3 的静态结论在修复后仍成立；O2、O5 维持定性观察。

### O1 `Read-Sse-Brief` 的兜底 `catch { }` 会吞掉"重挂读取失败"——使两处"无新帧"断言可能空转【**已按裁决 #3 修复**】

`scripts/verify-e2e.ps1` 的 `Read-Sse-Brief`（GF4-A3 / GF5-A4 的定时窗口读取）结构为：

```
try {
    … SendAsync …
    if (-not $resp.IsSuccessStatusCode) { throw "SSE $path => HTTP $($resp.StatusCode)" }   // ① 在 try 内
    … 读流 …
} catch { }        // ② 全吞
return $frames     // ③ 返回可能为空的集合
```

① 的 HTTP 失败（以及连接异常）会被 ② 吞掉，函数返回空集 ⇒ 调用方的"**无新帧**"断言**恒真**，即便重挂其实失败。
**本轮该风险未实际发生**：后端日志显示两处窗口读取**都到达了服务端**
（`reattach … status=SUSPENDED 回放帧数=0`、`reattach … status=DONE 回放帧数=0`，§3.4/§3.5），
故本轮 A3/A4 断言**有服务端侧背书、非空转**。建议：把非 2xx 抛出移出 try，或改为"失败即 FAIL"。

### O2 `Read-Sse-Brief` 的窗口行为与耗时合理（任务点 4 上半）——**无意外阻塞**

- GF 块整体耗时约 **18 秒**（GF1 起 19:40:42 → TC17 首轮 19:41:00），其中 GF4-A3 的 3 秒窗口、GF5-A4 的窗口均**按设计时长收束**；
- 未出现等待 `app.ai.hitl.timeout`（120s）或原流 180s 预算的现象；整轮 **93 秒**（19:40:01→19:41:34），
  与上一棒"纯 22 用例约 70 秒"同量级 ⇒ GF 新增 5 例（4 个模型轮次 + 2 个定时窗口）的增量约 23 秒，**无异常放大**；
- 佐证：GF4 的"拒绝 → 3s 窗口 → 改值重发"链条在后端日志上的时间戳为 `19:40:54.072`（拒绝）→ `19:40:57.641`（续跑帧），即窗口未被拖长。
- 另：GF 失败分支的 `Gf-Converge`（FAIL 前对仍 `PENDING` 的挂起补发 `cancelled:true`）本轮**未产生额外挂起悬置**（三例的挂起均已终态）。

### O3 无"catch 吞错"迹象（任务点 4 下半）——静态 + 实证

- 新增 GF 代码内的兜底 `catch` 仅三处，均为**窄口径且不掩盖主错误**：
  ① `Read-Sse-Brief` 的窗口读（见 O1，已单列风险）；
  ② `Gf-Converge` 的收尾兜底（仅在**已记 FAIL 之后**尽力收敛挂起，吞掉不影响主判定）；
  ③ `Gf-Parse-Form` 的 `ConvertFrom-Json` 兜底（紧随其后 `if (-not $argsObj) { throw … }` 显式抛出）。
- 实证：三例失败**全部外显**为带原因的 `[FAIL]`（无静默通过），且失败分支走的都是 `catch → No 'GFx' <原因>`。

### O4 脚本自身不落帧文件（与设计稿 §6 归档建议有偏差）【**已按裁决 #4 修复**】

设计稿 §6 建议"SSE 帧全量落 `<TMP>/gfc-<case>.sse.txt`"，但**首轮时**脚本实现中**无任何落盘代码**（`grep 'sse\.txt\|http\.txt\|Out-File'` → 0 命中）。
本棒以"跑后只读回放 + 会话记忆 + pending 快照 + 后端日志"四通道补齐取证（§3），并如实标注回放帧**不含 delta** 的边界。**该偏差已按裁决 #4 修复**：脚本新增 `-ArtifactDir` 与 `Gf-Dump`/`Gf-Http-Note`，每用例自行落盘 SSE 帧**全文（含 delta）** 与 HTTP 请求/响应原文；最终轮 5 个用例各产出 `gfc-<case>.sse.txt` / `gfc-<case>.http.txt`（见 §10.3）。

### O5 由本轮带出的两条设计稿观察项（沿用，未新增）

- **O1（设计稿）双 FormRenderer**：仍判定为**脚本层不可覆盖**（渲染层现象），维持"浏览器手动冒烟"收尾；
  本轮服务端侧已固化"同 `toolCallId` 的 `frontend_tool_request` 不重复下发"（GF3-A6 通过）。
- **O2（设计稿）系统提示词未引导表单场景**：本轮 4/4 成功触发**依赖提示词显式点名**，
  **不能**据此推断模型会自发使用表单——与设计稿结论一致，是否补系统提示词仍属产品决策。

---

## 8. 【待裁决】清单（含各项**处置结果**）

| # | 事项 | 本棒的实测与建议 | 备选 | **裁决与处置结果** |
|---|---|---|---|---|
| 1 | **GF1/GF2/GF4 的 A6"值回显"断言如何处置**（首轮 3 个 FAIL 的唯一成因） | 建议**判定为测试侧假阴性**，按下列之一修脚本后重跑：(a) 删除 A6（主锚点 A4 `pending.resultText` 精确相等已足够证明"回灌值原样入上下文"）；(b) 保留但**降级为非致命**（采到但不含值 → 打 INFO，不 FAIL）；(c) 改用"最终助手文本"通道（`GET /api/ai/history/{sessionId}`）做值回显断言——该通道首轮已实证含全部所填值，**不受 delta 丢失影响** | 若要求"必须由 SSE delta 断言"，则需改读流策略（回灌后**不**断开、或断点续读同一连接），属脚本结构性改动，风险高于 (c) | **已采纳 (c)**（裁决 #1）：A6 改用会话记忆最终助手文本；delta 降级为纯 INFO 不判 FAIL；主锚点 A4b 不动。**另修一处**：断言对象改为**实际回灌值**（见 §10.2 R3）。重跑 GF1/GF2/GF4 **全 PASS** |
| 2 | **GF 计数口径与退出码** | 首轮 `PASS=24 FAIL=3` 退出码 1；若裁决 #1 采纳 (a)/(b)/(c) 并重跑，预期 `PASS=27 FAIL=0` | 若维持现状，请明确"GF-C 整棒判 FAIL 但仅因 A6"，以免与"产品链路不成立"混淆 | **已达成**（裁决 #2）：最终轮 `PASS=27 FAIL=0 GFSKIP=0`，退出码 **0** |
| 3 | **O1：`Read-Sse-Brief` 吞掉重挂失败** | 建议后续棒修（抛错移出 try），并在"无新帧"断言处显式核对重挂 HTTP 码 | 维持现状（首轮未发生，但有空转隐患） | **已修复**（裁决 #3）：握手（连接+状态码）移出吞错 `catch`，非 2xx/连接失败**显式抛出**；GF4-A3 / GF5-A4 两处断言旁的注释同步标注。重跑两处断言均通过且非空转 |
| 4 | **O4：脚本是否补落帧文件** | 建议补（设计稿 §6 已有要求），否则 delta 类断言不可事后复盘 | 维持"跑后回放"取证方式（含"不含 delta"边界说明） | **已修复**（裁决 #4）：新增 `-ArtifactDir` + `Gf-Dump`/`Gf-Http-Note`，每用例落盘 SSE 帧全文与 HTTP 原文（UTF-8 无 BOM）。**过程中另修一处**：`$script:gfHttp` 未按用例重置导致 `.http.txt` 累积前序请求（见 §10.2 R4），已修 |
| 5 | **既有用例中"模型自发调用表单"未出现** | 首轮 0 次，故设计稿 §5.3 的 cancelled 兜底**未被实证**；建议在 A6 修复重跑时一并观察是否出现该第二态 | 若长期不出现，可考虑以单测覆盖该分支（当前仅靠不可达兜底） | **已按要求复观察**（裁决 #5）：最终轮仍为 **0 次**（10 个 `s5b-` 会话无任何 `generative_form` 调用），兜底分支仍**未被触发**；建议保留为待观察项（§10.4） |

---

## 9. 附件清单与清理

附件全部在仓库外 `<TMP>`（不提交），按轮次分目录：`run1/`（首轮）、`run5/`（最终轮）。中间两轮（`run2/` 首次重跑、`run3/` 修复 echo 后、`run4/` 补 GF3 落盘后）亦保留备查。

**首轮（`<TMP>/run1/`）**

| 附件 | 内容 |
|---|---|
| `e2e-gfc-run1.log` | 脚本 stdout 全量（24 PASS / 3 FAIL / GFSKIP=0 摘要） |
| `boot-gfc.log` | 后端 DEBUG 全量日志 |
| `gfc-log-excerpts.txt` | 后端日志关键行摘录（A 会话与 runId / B 取消 / C 拒绝 / D 写流失败 / E reattach / F 入参闸门） |
| `gfc-GF1..4.sse.txt` | 各 GF 轮原始回放帧（**跑后**取得；状态帧，不含 delta） |
| `gfc-GF1..4.history.json` | 会话记忆原文（含模型值与回显） |
| `gfc-GF1..4.pending.json` | 挂起条目终态快照 |
| `gfc-GF5-http.txt` | GF5 两次 409 的原始请求/响应 |

**最终轮（`<TMP>/run5/`）**——明细见 §10.3。

| 附件 | 内容 |
|---|---|
| `e2e-gfc-run5.log` | 脚本 stdout 全量（27 PASS / 0 FAIL / GFSKIP=0 摘要，含 `[ART]` 行） |
| `boot-gfc5.log` | 后端 DEBUG 全量日志 |
| `gfc-GF{1..5}.sse.txt` | **脚本自行落盘**的该用例 SSE 帧全文（含 delta） |
| `gfc-GF{1..5}.http.txt` | **脚本自行落盘**的回灌 HTTP 请求/响应原文（按用例隔离） |
| `gfc-GF1..4.history.json` | 会话记忆原文（最终助手文本即值回显断言的取值通道） |
| `gfc-GF1..4.pending.json` | 挂起条目终态快照 |

清理与纪律（两轮同口径，最终态自证）：

- 只停本棒自己启动的后端进程（端口 18330，PID 见日志；逐轮 `taskkill /F`），**未触碰**其它 java 进程；
- 端口回收：`netstat -ano | grep ':18330'` 无 LISTENING；
- 临时库已删：`backend/data/e2e_gfc_db.mv.db`（含 `.trace.db`）；演示库 `config_mgr_db.mv.db` mtime 未变（仍 13:48）；
- `backend/data/files/` 无本棒时段（19:38 之后）新文件残留；
- 工作区：`git status` 仅 `scripts/verify-e2e.ps1`（M，被验对象）与 `docs/evidence/GFc-*.md`（??，含本棒新增）——无其它改动；
- 脱敏自查：本文不出现真实盘符 / 密钥 / 用户名 / 主机名（一律 `<REPO_ROOT>` / `<TMP>` / `***`）；密钥仅经环境变量注入进程，**未落文件**。

---

## 10. 修复与重跑（最终态）

### 10.1 裁决落地：改了什么

按指挥官裁决 #1/#3/#4 对 `scripts/verify-e2e.ps1` 的 GF 区块与两处共享代码做了**加法/替换式**修改，**既有 22 用例块一行未改**（`git diff` 的 hunk 全落在 param / 头部 / GF helper / GF1–GF5 区块内）。修改清单：

| 序 | 裁决 | 改动点 | 具体内容 |
|---|---|---|---|
| R1 | #1 | GF1-A6 / GF2-A6 / GF4-A5b 的**值回显断言** | 删除"delta 收集到则断言"的旧逻辑，改用**新通道** `Gf-FinalAssistantText($sessionId)`（`GET /api/ai/history/{sessionId}` 取最后一条 text 非空的 ASSISTANT 消息）做断言；`delta` 拼接结果**降级为纯 INFO**（`[INFO] … delta 弱旁证（不判 FAIL）`），不再参与判定。`pending.resultText` 精确相等主锚点（GF1-A4 / GF2 / GF4-A4b）**原样保留** |
| R2 | #1 | 值回显断言的**断言对象** | 新增 `Gf-Assert-Echo($case, $text, $postedJson)`：逐字段校验**该用例实际 POST 的值**（含布尔/数组形态）的字符串形式出现于最终助手文本，并校验字段名出现。**关键**：对象取自**实际回灌值**而非提示词列出的字段 |
| R3 | #3 | `Read-Sse-Brief`（O1） | 把**握手**（`SendAsync` + 状态码判定）移出吞错 `catch`：连接失败抛 `SSE <path> => 重挂连接失败（${millis}ms 窗口内未建立）`；非 2xx 抛 `SSE <path> => HTTP <code>（重挂失败，无新帧断言不可空转）`。仅"窗口内有界读流的中断/到点"仍允许吞（该函数的预期语义） |
| R4 | #4 | GF 证据落盘（O4） | 新增 `-ArtifactDir` 参数（默认 `<TMP>/gfc-artifacts-<guid>`）与 `Gf-Dump`/`Gf-Http-Note`；每用例（**成功与失败路径均**）落盘 `gfc-<case>.sse.txt`（脚本实际收到的帧全文，含 delta）与 `gfc-<case>.http.txt`（回灌请求/响应原文），`[IO.File]::WriteAllLines` + `UTF8Encoding($false)`（UTF-8 无 BOM）；落盘失败只告警不判 FAIL |

> R2 的由来（**重跑中又发现并修正的一处**）：R1 落地后的第一次重跑（`run2`）GF1 仍 FAIL，信息为 `值回显缺 '2026-10-07'`。取证（`run2/gfc-GF1.http.txt` + 会话记忆）显示：该轮模型把 `minRows`/`effectiveDate`/`scope` 都标为 `required=false`，而设计上 `New-FormValues` **会跳过非必填字段**（验证"可选字段缺省合法"），故实际只回灌了 `{"keyword":"E2EGFC","scope":"XN"}`；模型的回显也如实写作 `minRows=（未填写）`、`effectiveDate=（未填写）`。**即：原断言断言了一个从未回灌的值**，属同类测试侧假阴性。R2 把断言对象绑定到实际回灌值后消除。
> R4 的由来（**重跑中又发现并修正的一处**）：`$script:gfHttp` 未随用例重置，导致 `gfc-<case>.http.txt` **累积前序用例的请求**（`run4` 实测 GF3 文件含 3 条、GF5 含 7 条）。已在 GF1–GF4 的尝试循环与 GF5/GF6 起点补 `$script:gfHttp = @()`；`run5` 实测每用例 HTTP 条数为 1/1/1/2/2，与各用例实际 POST 次数**一一对应**。

### 10.2 最终轮总账

```
 E2E 验证开始  base=http://127.0.0.1:18330
 后端日志：<TMP>/run5/boot-gfc5.log
 GF 证据落盘目录：<TMP>/run5
 结果：PASS=27  FAIL=0  （22 既有 + GF 新增）
 GFSKIP=0  （模型未触发 SKIP，不计退出码；GF1–GF4 全部 SKIP 判整棒 FAIL，裁决 #1）
 Q8 项：PASS=2  FAIL=0
E2E_EXIT=0
```

- 执行时段 **19:59:51 → 20:01:22（91 秒）**，与首轮 93 秒同量级；
- 环境与首轮同口径：Memurai `PING=PONG`（只读）、18330 先 `netstat` 空闲、独立 H2 库（`e2e_gfc_db`）、密钥仅经环境变量注入、后端日志落仓库外；
- `PASS=27` = **22 既有 + GF1–GF5 全部 5 例**；`FAIL=0`；退出码 **0**。

### 10.3 最终轮逐用例结果

| 用例 | 结果 | 关键证据 |
|---|---|---|
| 既有 TC1–TC22 | **全 PASS（22/22）** | 与首轮逐条一致（§2.1）；**零扰动再次实证** |
| Q8① / Q8② | **PASS / PASS** | 同上 |
| **GF1** FILTER | **PASS** | SSE 帧 94 条（`gfc-GF1.sse.txt`，含 delta）；HTTP 1：挂起回灌 → **200** `status=FRONTEND_RESULT, accepted=true, executed=false`；`pending=FRONTEND_RESULT`；最终助手文本含 `- keyword=E2EGFC` / `- scope=XN`（本轮模型亦如实回报 `minRows=1` / `effectiveDate=2026-10-07`） |
| **GF2** CLARIFY | **PASS** | HTTP 1 → 200 `FRONTEND_RESULT`；`pending=FRONTEND_RESULT`；最终助手文本含 `exportScope=ALL` / `includeInactive=true` |
| **GF3** 取消 | **PASS** | HTTP 1：`cancelled:true` 回灌 → **200** `status=FRONTEND_CANCELLED, cancelled=true, executed=false`；`pending=FRONTEND_CANCELLED`；日志 `前端工具被用户取消 runId=90f5be06-… toolCallId=call_00_sEYvxuDywzTsBtNVD2lG6526 name=generative_form`（INFO）；最终助手文本"表单已被取消，我没有拿到任何填写数据…" |
| **GF4** 复核拒绝 | **PASS** | HTTP **2**：① 违规值 → **400**（`reasons` 恰 4 条：未知字段 `extra`、`keyword` 类型错、`amount` 类型错、`scope` 值不在选项内）+ WARN `code=FORM_RESULT_REJECTED`；② 同 toolCallId 改值 → **200**；A3 的 3.5s 窗口重挂**无新帧**（且该读取非空转，见 §7 O1 修复）；最终助手文本含 `keyword=E2EGFC` / `scope=XN` |
| **GF5** 409 幂等 | **PASS** | HTTP **2**：值路径重发 → **409** `DUPLICATE_TOOL_CALL_ID, status=FRONTEND_RESULT, duplicate=true`；取消路径再取消 → **409** `status=FRONTEND_CANCELLED`（终态不被改写）；两次 409 后重挂**无新帧** |

**每用例证据落盘（(`[ART]` 行原文）**）：

```
  [ART] gfc-GF1.sse.txt / gfc-GF1.http.txt 已落盘（帧 94，HTTP 1）
  [ART] gfc-GF2.sse.txt / gfc-GF2.http.txt 已落盘（帧 81，HTTP 1）
  [ART] gfc-GF3.sse.txt / gfc-GF3.http.txt 已落盘（帧 45，HTTP 1）
  [ART] gfc-GF4.sse.txt / gfc-GF4.http.txt 已落盘（帧 49，HTTP 2）
  [ART] gfc-GF5.sse.txt / gfc-GF5.http.txt 已落盘（帧 1，HTTP 2）
```

### 10.4 裁决 #5 观察结果：既有用例中模型是否自发调用 `generative_form`

**结论：0 次**（与首轮一致；"被兜底 cancelled 收敛"的第二态**未出现**）。

取证方式与证据：

- 全部模型轮次按会话前缀统计：`gfc-` **4 个**（= GF1–GF4）、`s5b-` **10 个**（= TC15/TC16/TC17/TC18 等既有 AI 用例）；
- 全日志中 `generative_form` 的非注册/非披露命中**全部**落在 4 个 `gfc-` runId 上：
  `720be9c6`（GF1）、`7224e4c6`（GF2，上下文 `task:EXPORT/QUERY_COND`）、`90f5be06`（GF3，`前端工具被用户取消`）、`c594114a`（GF4，`code=FORM_RESULT_REJECTED`）；
- **10 个 `s5b-` 会话无任何** `tool=generative_form` / `name=generative_form` / 表单闸门日志行；
- 另附披露面证据：`page:tasks` 的披露清单为 13 个工具（含 `generative_form`），`task:EXPORT/QUERY_COND` 为 11 个（含 `generative_form`）——即**工具已披露但既有用例未触发**，与设计稿 §5.3 的"潜伏扰动面"判断一致；该兜底分支两轮均未被触发，仍属**未经实证的不可达兜底**（建议保留观察，或以单测直接覆盖）。

### 10.5 复跑稳定性说明（诚实标注）

最终轮为**第 5 次**执行（`run1` 首轮 → `run2` R1 后 → `run3` R2 后 → `run4` GF3 落盘补齐后 → `run5` R4 后）。`run3` 与 `run4` 均已达成 `PASS=27 FAIL=0`，`run5` 为**在 R4 修复（HTTP 落盘按用例隔离）后的最终确认轮**：

| 轮 | 该轮改动 | 结果 | 说明 |
|---|---|---|---|
| run1 | （无，首轮） | PASS=24 FAIL=3 | A6 采集缺陷致 3 例假阴性 |
| run2 | R1（A6 改会话记忆通道） | PASS=26 FAIL=1 | GF1 仍失败 → 发现 R2 问题（断言了未回灌的值） |
| run3 | +R2（断言对象=实际回灌值） | **PASS=27 FAIL=0** | 但 GF3 未落盘（仅 catch 有 Gf-Dump） |
| run4 | +GF3 落盘补齐 | **PASS=27 FAIL=0** | 但 `.http.txt` 累积前序请求（R4 问题） |
| run5 | +R4（`gfHttp` 按用例重置） | **PASS=27 FAIL=0**（退出码 0） | **最终确认轮**：全绿 + 每用例证据落盘正确 |

> 稳定性观察：**GF1–GF5 在两轮全绿运行（run3/run4/run5）中均 PASS**，说明修复后的断言**能容纳模型措辞波动**（含"可选字段被跳过/未填写"的情形）。唯一仍依赖模型行为的是"表单是否被触发"（SKIP 口径已覆盖，本轮 4/4 首轮即触发）。
