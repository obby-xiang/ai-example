# M1 E2E 复跑确认（DeepSeek V4 Flash）——AI 相关用例新鲜实测

> 角色：批量工作者（DeepSeek V4 Flash，异厂商独立复跑）。**零 git 写操作、零产品代码改动**；本文档为唯一新增文件，保持未提交（提交官统一入库）。
> 仓库：`<REPO_ROOT>`，分支 `main`，**基线 HEAD `61286e2`**（执行前后均未变动，见 §6）。
> 执行日期：**2026-10-07**；执行时段 20:34:55 → 20:36:20（**85 秒**）。
> 目的：为 M1 出口评审补上「**AI 相关用例新鲜实测**」证据，闭合异厂商复核报告 `docs/evidence/M1-出口复核-GLM-5.3.md` §四 保留项 #1（该复核环境未注入 AI key ⇒ AI 相关 10 用例结论降级为【推断】）的缺口。指挥官方对此缺口的表述为「GLM-5.3 复核第 4 项」；本文按复核报告原文口径记为**保留项 #1（AI 用例实测降级）**，两者指向同一事项。
> 证据等级：**凡标注【实测】者均为本轮真实进程 + 真实模型（`deepseek-flash`）+ 真实 Redis 的可复核观测**；未见实测的一律标注【推断】。
> 脱敏：不出现真实盘符（统一 `<REPO_ROOT>` / `<TMP>`）、密钥（统一 `***`）、用户名、主机名、内网地址；本轮无任何密钥落盘。

---

## 0. 结论速览

| 项 | 值（均为【实测】） |
|---|---|
| 总账 | **PASS=27　FAIL=0　GFSKIP=0**；脚本退出码 **0** |
| 既有 22 用例（TC1–TC22） | **PASS=22 / FAIL=0** |
| Q8 项（不计入退出码） | **PASS=2 / FAIL=0** |
| GF1–GF5 | **全部 PASS**（GF1 FILTER / GF2 CLARIFY / GF3 取消 / GF4 复核拒绝 / GF5 409 幂等） |
| AI 相关 10 用例 | **全部新鲜【实测】通过**（既有 TC15/TC16/TC17/TC18/TC22 共 5 例 + GF1–GF5 共 5 例） |
| AI 是否降级 | **未降级**：`GET /api/ai/health` → HTTP 200 `available=true` / `code=AI_AVAILABLE` / `model=deepseek-flash` / `baseUrl=https://api.deepseek.com` / `redis.available=true`；后端日志 **ERROR 0 条**，全文无「AI 能力暂不可用」/`AI_UNAVAILABLE`/`AI_REDIS_UNAVAILABLE` 命中 |
| 复跑轮次 | **本轮仅 1 次即全绿，无 FAIL，故未触发复跑**（任务口径的「连续 2 次失败」未发生，无【待裁决】项） |
| 与 GF-C 基线一致性 | 计数、口径、GF 五项语义**逐项一致**；差异仅在模型输出长度/措辞等抖动面（§3.3） |

> 一句话：**在真实 AI key 注入、真实模型调用的条件下，`scripts/verify-e2e.ps1` 全量一轮即 `PASS=27 FAIL=0 GFSKIP=0`（退出码 0），其中 AI 相关 10 用例全部为新鲜实测通过——GLM-5.3 复核保留项 #1 的「AI 用例结论降级【推断】」缺口由此闭合。**

---

## 1. 跑法摘要（复刻 GF-C 记录的标准跑法，未自创）

### 1.1 前置纪律（逐条先确认，均为【实测】）

| 前置 | 命令 / 观测结果 |
|---|---|
| Memurai（Redis 兼容） | `netstat` 显示 `127.0.0.1:6379 LISTENING`（未启动任何服务，只读复用）；health 端点回显 `redis.available=true`、`probes=1`、`failures=0` |
| 端口 18330 | 启动前 `netstat` 全量筛查 183xx 段**无任何监听**（未触碰 18080 / 18290–18299 / 18301–18321 历史占用段），故取 18330（GF-C 同端口） |
| AI 密钥 | 仅在**同一条启动命令内经环境变量**注入进程（`AI_API_KEY=***`，另显式注入 `AI_BASE_URL=https://api.deepseek.com`、`AI_MODEL=deepseek-flash`）；**未写入任何文件**，未写入 `.env` |
| 数据隔离 | 独立 H2 库 `e2e_m1_db`；**工作目录设在仓库外**（`<TMP>/m1-ds-run1/work1`），故 `./data` 全部落在仓库外——本轮**未在仓库内创建任何数据/文件**，演示库 `config_mgr_db.mv.db`（mtime 20:26:50）与 `backend/data/files`（mtime 20:21:33）**mtime 全程未变**，均早于本轮启动时刻 20:34:5x |
| 后端日志 | 落 `<TMP>/m1-ds-run1/boot-m1.log`（仓库外），`com.example.configmgr` DEBUG 级 |

### 1.2 命令（原样，可机械复现）

```bash
# ① 复用预编译 jar（依据见 §1.3；本轮未改后端，故不重建）
cd <TMP>/m1-ds-run1/work1
AI_API_KEY="***" AI_BASE_URL="https://api.deepseek.com" AI_MODEL="deepseek-flash" \
  java -jar <REPO_ROOT>/backend/target/config-mgr.jar \
  --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/e2e_m1_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 > <TMP>/m1-ds-run1/boot-m1.log 2>&1 &

# ② 全量照跑（22 既有 + GF1–GF5；未开 -EnableGf6）
cd <REPO_ROOT>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 \
  -BackendLog <TMP>/m1-ds-run1/boot-m1.log \
  -ArtifactDir <TMP>/m1-ds-run1/run1 > <TMP>/m1-ds-run1/e2e-run1.log 2>&1
```

### 1.3 「复用预编译 jar」的依据（两条机械复核，均【实测】）

1. `find backend/src -type f -newer backend/target/config-mgr.jar` → **0 命中**（无任何后端源码比该 jar 新）；
2. 执行前后 `git status` 的**已跟踪文件零变化**，即工作区后端源码与 HEAD `61286e2` 一致 ⇒ jar 与 HEAD 后端源码对应。

另：本棒的被验对象是 `scripts/verify-e2e.ps1`（API 层 E2E，脚本自身扮演前端读 SSE + POST 回灌），**不加载前端产物**，且脚本本体在本轮**未被修改**（工作区无该文件的改动）。

---

## 2. 结果

### 2.1 总账（脚本 stdout 原文，均为【实测】）

```
 E2E 验证开始  base=http://127.0.0.1:18330
 后端日志：<TMP>/m1-ds-run1/boot-m1.log
 GF 证据落盘目录：<TMP>/m1-ds-run1/run1
 预清理：删除残留任务 0 个，E2E_TEMP 定义 0 个
 预清理（GF）：删除残留 GFC-* 任务 0 个
 ...
 结果：PASS=27  FAIL=0  （22 既有 + GF 新增）
 GFSKIP=0  （模型未触发 SKIP，不计退出码；GF1–GF4 全部 SKIP 判整棒 FAIL，裁决 #1）
 Q8 项：PASS=2  FAIL=0
```

- `PASS=27` = **22 既有 + GF1–GF5 全部 5 例**；`FAIL=0`；`GFSKIP=0`；`E2E_EXIT=0`。
- 执行时段 **20:34:55 → 20:36:20（85 秒）**，与 GF-C 基线最终轮 91 秒同量级【实测：脚本墙钟耗时 84.9 秒 + 日志末行 mtime 20:36:20；**起点 20:34:55 为【推断】**——由墙钟反推，其旁证为脚本发出的首条后端写操作时间戳 20:34:56.352（`CREATE_DEFINITION def#E2E_TEMP`）】。
- GF 块（GF1 轮次开始 20:35:32.318 → GF5 末次重挂 20:35:48.237）约 **16 秒**【实测】。

### 2.2 既有 22 用例 + Q8（全部 PASS，断言摘要照录脚本输出）

| 用例 | 结果 | 断言摘要（脚本输出原文） |
|---|---|---|
| TC1 | PASS | 15 个定义（基座 7 + glm 8）/层级/引用字段/发布行数/过滤正确 |
| TC2 | PASS | 定义创建/重复编码拦截(HTTP 409 + DEFINITION_CODE_DUPLICATE，无 SQL 泄漏)/字段动态追加/无主键拒绝 |
| TC3 | PASS | 四类规则（必填/主键重复/引用不存在/范围必填）全部命中且给出明细 |
| TC4 | PASS | 全量导出 3 文件 + 行数/进度正确 + 文件可下载（PK 魔数） |
| TC5 | PASS | 字段条件(code=CNY→1行)与范围条件(HE→3行、HE+XN→10行)正确，3 行子集件已保存 |
| TC6 | PASS | 勾选打包（2 条目）与全量打包（3 条目）均正确 |
| TC7 | PASS | 模板：单个 xlsx + 多配置 zip + 定义级模板下载正常 |
| TC8 | PASS | zip 匹配/未匹配报告/前缀命名/非法命名拒绝均正确 |
| TC9 | PASS | 检查全通过 + 依赖拓扑（METRIC_DICT 先于 ALARM_THRESHOLD）+ 条目状态 CHECKED |
| TC10 | PASS | 检查失败明细可查（分页/总数/归属正确）+ 未上传文件报错 + 日志无写盘异常 |
| TC11 | PASS | 收到进度事件流 12 条（末条 120/120）+ JOB_DONE(COMPLETED) |
| TC12 | PASS | 导入写暂存（3 行 STAGED），生效数据保持 5 行不变 |
| TC13 | PASS | 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 快照冲突拦截重复发布 + 409 JOB_ALREADY_FINAL |
| TC14 | PASS | 守卫真实阻断（无预检查/预检查失败 → IMPORT、PUBLISH 均 409 PRECHECK_NOT_PASSED，零作业行；PROJECT_ENV 28 行不变）+ 放行侧空暂存发布不误删 |
| TC20 | PASS | 统一列表/创建即持久化/类型与状态过滤/关键词转义（`%` 字符 → 0 命中）正确 |
| TC21 | PASS | 任务分页正确（total=14，第 1 页 5 条 / 第 2 页 5 条，无重复） |
| **TC15**（AI） | PASS | 流式+工具调用可见+渐进披露（只读端点 `/api/ai/tools`）：`page:tasks` 13 个工具（无 `start_publish`）；`task:IMPORT/PUBLISH` 含 `start_publish` |
| **TC16**（AI） | PASS | 确认放行→真实创建导出作业并产出文件；前端工具挂起+回灌续跑（`navigate_to,select_definitions`） |
| **TC17**（AI） | PASS | 拒绝不执行（暂存仍 STAGED、版本零变化、409 重复决策拦截）+ 确认后真实发布（PUBLISH COMPLETED） |
| **TC18**（AI） | PASS | 会话记忆隔离 + 409 SESSION_BUSY(携 runId/reattach) + 取消后释放 |
| **TC22**（AI） | PASS | 历史恢复：**8 条（USER 2 / ASSISTANT 4，含工具卡片）** + 未知会话返回空（断言为下界，见 §3.3①） |
| TC19 | PASS | 清理完成（定义删除 404、任务删除级联作业 404）+ 演示数据恢复 5 行 |
| Q8① | PASS | 明细落库（未上传文件批次的明细可查，日志无写盘异常） |
| Q8② | PASS | SSE 请求不做二次 JSON 写入（日志无 `No converter`/`HttpMessageNotWritableException`） |

**AI 可用性佐证【实测】**：本轮共有 **14 个模型轮次**到达上游（日志 `AI 轮次开始` 14 条），逐轮 `done` 帧携带 `usage` 与 `terminalSignal=finishReason=STOP`（如 GF1 `totalTokens=7053, attempts=1, upstreamEvents=203`），**无一轮走降级/无 key 分支**。

### 2.3 GF1–GF5 逐项状态与关键取证【实测】

| 用例 | 路径 | 结果 | HTTP | 主锚点 | 落盘产物（帧 / HTTP 条） |
|---|---|---|---|---|---|
| **GF1** | FILTER 回灌续跑（`page:tasks`） | **PASS** | 挂起回灌 **200** | `status=FRONTEND_RESULT`、`executed=false`、`pending.resultText` 精确等于回灌 JSON、结局帧恰 1 条 + `done` 恰 1 条 | 79–80 帧 / 1 |
| **GF2** | CLARIFY 回灌续跑（`task:EXPORT/QUERY_COND`） | **PASS** | 挂起回灌 **200** | 同上（+ fixture 自删） | 82–83 帧 / 1 |
| **GF3** | 取消路径（`cancelled:true`） | **PASS** | 取消回灌 **200** | `status=FRONTEND_CANCELLED`、`cancelled=true`、`executed=false`、日志三面一致 + 模型收尾 | 72–73 帧 / 1 |
| **GF4** | 复核拒绝（400→改值重发） | **PASS** | **400 → 200** | 400 `reasons` 恰 **4 条**、PENDING 保持、3s 窗口无新帧（后台 `回放帧数=0`）、同 toolCallId 改值 200 | 53–54 帧 / **2** |
| **GF5** | 幂等（409 双向） | **PASS** | **409 + 409** | 值路径/取消路径重复回灌均 `DUPLICATE_TOOL_CALL_ID`、`duplicate=true`、终态不改写、重挂无新帧 | 1 帧 / **2** |

逐例原始取证（脚本自行落盘的 HTTP 原文摘录，`instanceId` 等含主机名/pid 的字段一律以 `<HOST>` 脱敏）：

**GF1**（`gfc-GF1.http.txt`）
```
POST /api/ai/frontend-tool-result
REQUEST: {"source":"e2e-gf","toolCallId":"call_00_hRLgr…","runId":"582a58c4-…","result":"{\"keyword\":\"E2EGFC\",\"scope\":\"XN\"}"}
RESPONSE: HTTP 200
{"accepted":true,"duplicate":false,"status":"FRONTEND_RESULT","cancelled":false,"executed":false,"wokeInProcessGate":true,"storedBy":"instance-<HOST>"}
```
帧契约（`gfc-GF1.sse.txt` 的 `frontend_tool_request` 原文）：`scenario=FILTER`、`timeoutSeconds=120`、`expiresAt=1791376653795`（数字）、`callback="POST /api/ai/frontend-tool-result {runId, toolCallId, result}"`、`toolCallId` 非空；四字段 `keyword/minRows/effectiveDate/scope` 齐备（本轮模型把 `minRows`/`effectiveDate`/`scope` 标为非必填，`keyword` 必填）。终帧：`"model":"deepseek-flash","attempts":1,"terminalSignal":"finishReason=STOP"`。

**GF2**：回灌 `{"exportScope":"ALL","includeInactive":true}` → **200** `FRONTEND_RESULT`。

**GF3**（`gfc-GF3.http.txt` + 后端 INFO 日志）
```
REQUEST: {"source":"e2e-gf","cancelled":true,"toolCallId":"call_00_XGYfP6M5…","runId":"ef56364e-…"}
RESPONSE: HTTP 200
{"accepted":true,"status":"FRONTEND_CANCELLED","cancelled":true,"executed":false,…}
```
后端日志：`前端工具被用户取消 runId=ef56364e-… toolCallId=call_00_XGYfP6M5… name=generative_form`（INFO）；
取消终帧 `result` 文本以 `FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写）…` 开头。

**GF4**（`gfc-GF4.http.txt`，一次用例两次 POST）
```
① REQUEST: result="{\"keyword\":123,\"amount\":\"abc\",\"scope\":\"ZZ\",\"extra\":\"\\u003cx\\u003e\"}"
   RESPONSE: HTTP 400 {"code":"FORM_RESULT_REJECTED", …,
     "reasons":["未知字段：extra（表单字段：keyword, amount, scope）",
                "字段 keyword 期望 text（字符串），实际是数字",
                "字段 amount 期望 number（JSON 数字），实际是文本：\"abc\"",
                "字段 scope 的值 \"ZZ\" 不在选项内（XN/HD）"], "status":"PENDING","accepted":false}
② REQUEST: 同 toolCallId result="{\"keyword\":\"E2EGFC\",\"scope\":\"XN\"}"
   RESPONSE: HTTP 200 {"status":"FRONTEND_RESULT","accepted":true,"executed":false,…}
```
后端日志旁证：`generative_form 回灌结果未通过类型复核（回灌被拒、挂起保持未决）`（WARN）+ `前端工具回灌被安全闸拒绝 … code=FORM_RESULT_REJECTED（状态不变，挂起继续等待）`（WARN）；
3 秒窗口重挂的服务端侧独立佐证：`reattach runId=088025f2-… status=SUSPENDED lastSeq=4 回放帧数=0`（DEBUG）——即「闸门不发帧、挂起不被消费」为真，该「无新帧」断言**非空转**（与 GF-C §7 O1 修复口径一致）。

**GF5**（`gfc-GF5.http.txt`）
```
① 值路径重复回灌（对 GF4 已决条目）→ HTTP 409 {"code":"DUPLICATE_TOOL_CALL_ID","status":"FRONTEND_RESULT","duplicate":true}
② 取消路径再取消（对 GF3 已取消条目）→ HTTP 409 {"code":"DUPLICATE_TOOL_CALL_ID","status":"FRONTEND_CANCELLED","duplicate":true}
```
两次 409 后重挂无新帧：`reattach runId=088025f2-… status=DONE lastSeq=52 回放帧数=0`、`reattach runId=ef56364e-… status=DONE lastSeq=72 回放帧数=0`。

**GF 证据落盘（脚本 `[ART]` 行原文，UTF-8 无 BOM）**
```
  [ART] gfc-GF1.sse.txt / gfc-GF1.http.txt 已落盘（帧 79，HTTP 1）
  [ART] gfc-GF2.sse.txt / gfc-GF2.http.txt 已落盘（帧 82，HTTP 1）
  [ART] gfc-GF3.sse.txt / gfc-GF3.http.txt 已落盘（帧 72，HTTP 1）
  [ART] gfc-GF4.sse.txt / gfc-GF4.http.txt 已落盘（帧 53，HTTP 2）
  [ART] gfc-GF5.sse.txt / gfc-GF5.http.txt 已落盘（帧 1，HTTP 2）
```
（`[ART]` 的帧计数为脚本读流时计得的帧数；落盘文件内的行数略多，如 GF1 = 80 行，差值来自落盘时的表头/分块行，不影响语义。）

### 2.4 AI 相关 10 用例清单（本轮全部为新鲜【实测】）

| 用例 | 类型 | 本轮结果 |
|---|---|---|
| TC15 | 流式 / 工具可见 / 渐进披露 | PASS |
| TC16 | 确认放行 + 前端工具挂起与回灌续跑 | PASS |
| TC17 | 确认门拒绝 / 确认后真实发布 | PASS |
| TC18 | 会话记忆隔离 / 409 串行化 / 取消释放 | PASS |
| TC22 | 历史恢复 + 未知会话空返回 | PASS |
| GF1 | 生成式表单 FILTER 回灌续跑 | PASS |
| GF2 | 生成式表单 CLARIFY 回灌续跑 | PASS |
| GF3 | 生成式表单取消路径 | PASS |
| GF4 | 生成式表单复核拒绝 → 改值重发 | PASS |
| GF5 | 生成式表单回灌幂等（409 双向） | PASS |

**结论**：GLM-5.3 复核报告将「AI 相关 10 用例」结论降级为【推断】的唯一原因（复核环境无 AI key）在本轮**已消除**——上述 10 例均为真实 key + 真实模型下的新鲜实测通过【实测】。

---

## 3. 与 GF-C 五轮基线的一致性说明

### 3.1 口径与计数一致【实测】

| 维度 | GF-C 基线（run5 最终轮） | 本轮 | 判定 |
|---|---|---|---|
| 总账 | `PASS=27 FAIL=0 GFSKIP=0`，退出码 0 | 同 | **一致** |
| 既有 22 用例 | 22/22 | 22/22 | **一致（零扰动再次实证）** |
| Q8 项 | 2/2 | 2/2 | **一致** |
| GF1–GF5 | 全 PASS | 全 PASS | **一致** |
| GF1–GF4 是否 SKIP | 0 | 0（4/4 首轮即触发表单） | **一致** |
| 端口 / 库隔离 / key 注入方式 | 18330 / 独立 H2 / 仅环境变量 | 同（库名与工作目录改为仓库外，隔离性更强） | **一致** |
| 耗时量级 | 91 秒 | 85 秒 | **一致** |

### 3.2 断言通道一致【实测】

GF1/GF2/GF4 的值回显断言沿用 GF-C run3 起生效的裁决 #1 口径（**会话记忆最终助手文本通道** + 断言对象 = **实际回灌值**），`pending.resultText` 精确相等主锚点未变；`delta` 拼接结果仅为 INFO 弱旁证（本轮三处分别采到 230 / 221 / 130 字符，均非致命）——即**未复现 GF-C 首轮（run1）的 A6 假阴性**。GF-C §7 O1（`Read-Sse-Brief` 吞错）与 §7 O4（脚本不落帧）两处修复在本轮同样生效：GF4-A3 / GF5-A4 的「无新帧」断言均带服务端 `回放帧数=0` 背书，且 5 个用例的 SSE/HTTP 原文均按用例隔离落盘（HTTP 条数 1/1/1/2/2，与各例实际 POST 次数一一对应）。

### 3.3 差异项（**模型行为抖动，非断言失败、非产品缺陷**）

| # | 差异 | 来源判定 |
|---|---|---|
| ① | TC22 历史条数 **8 条（USER 2 / ASSISTANT 4）** vs 基线 6 条（USER 2 / ASSISTANT 3） | 断言为**下界**（`count ≥ 4`、`USER ≥ 2`、`ASSISTANT ≥ 1`）故通过【实测：脚本原文】；条数差来自模型额外产出一条含工具卡的助手消息【推断：成因】 |
| ② | GF1 本轮实际回灌 `{"keyword":"E2EGFC","scope":"XN"}`（`minRows`/`effectiveDate` 被模型标为非必填而未回灌） | 与 GF-C `run2` 曾出现的同类波动同源，已被裁决 #1 的 R2 修复（断言对象绑定实际回灌值）覆盖【实测：`gfc-GF1.http.txt` + 脚本 PASS】 |
| ③ | GF 各例帧数差异（GF1 94→79、GF2 81→82、GF3 45→72、GF4 49→53、GF5 1→1） | 帧数随模型输出长度与心跳节奏波动，**帧类型集合完全同构**（`start → suspended → tool_start → frontend_tool_request → frontend_tool_result → message_start → …delta… → message_end → done`）【实测：各 `gfc-GF*.sse.txt` 逐类型计数】 |
| ④ | GF4 的 400 `reasons` 条数 | **恰 4 条**，与 GF-C 基线逐条同义（未知字段 / `keyword` 类型 / `amount` 类型 / `scope` 选项外）【实测】 |

### 3.4 既有用例中模型是否自发调用 `generative_form`（沿用 GF-C 裁决 #5 观察项）

**本轮仍为 0 次**【实测】：日志中 `generative_form` 的非注册/非披露命中**全部**落在 4 个 `gfc-` 会话对应的 runId 上（GF1 `582a58c4`、GF2 `46c16d1e`、GF3 `ef56364e`、GF4 `088025f2`）；`s5b-` 前缀的 **8 个会话**（TC15–TC18、TC22 等既有 AI 用例）**无任何** `tool=generative_form` / 表单闸门日志行——各会话日志中的 `generative_form` 仅出现在「披露工具=[…]」清单里（披露与触发两侧分离，与 GF-C 两轮结论一致）。GF-C §5.3 的 cancelled 兜底分支本轮**仍未被触发**，维持「未经实证的不可达兜底」判定。

---

## 4. 稳定性与复跑记录

| 轮 | 触发原因 | 结果 | 说明 |
|---|---|---|---|
| 本轮（第 1 次） | —— | **PASS=27 FAIL=0 GFSKIP=0，退出码 0** | 一次即全绿 |

- 任务口径规定「单例偶发失败按 GF-C 口径复跑确认；同一用例连续 2 次失败即停止并上报【待裁决】」——**本轮无任何 FAIL，故未触发复跑，亦无【待裁决】项**【实测】。
- 横向对照：GF-C 修复后的 run3/run4/run5 三轮连续全绿，本轮为该序列之外的**独立复跑**（异厂商执行者、不同时点、独立进程），结果同为全绿 ⇒ 修复后断言的稳定性得到再次确认【实测】。
- 仍需说明的边界：GF1–GF4 的「表单是否被模型触发」仍依赖模型行为（本轮 4/4 首轮即触发，`GFSKIP=0`），该依赖已由脚本的 SKIP 口径与硬底线覆盖【实测】。

---

## 5. 纪律与收尾自查

| 项 | 状态 |
|---|---|
| 零 git 写操作 | 未执行任何 `git add/commit/checkout/stash/reset`【实测：仅用过 `git status`/`git rev-parse`/`git config --get` 等只读命令】 |
| 零产品代码/既有文件改动 | 工作区**已跟踪文件零变化**，HEAD 恒为 `61286e2`；本轮唯一新增文件即本文档【实测】 |
| 并行他方写入的区分 | 复跑期间另有其它未跟踪文档被并行改动（`docs/evidence/GFd-*.md` mtime 20:32、`docs/M1-出口评审材料.md` 等），其时点**早于本轮启动 20:34:55**，与本棒无关【实测：mtime 比对】 |
| 仓库内零数据写入 | 后端工作目录设在仓库外，H2 库与 `data/files` 均落在 `<TMP>/m1-ds-run1/work1` 下；演示库与 `backend/data/files` mtime 未变【实测】 |
| 进程纪律 | 仅停止本棒自己启动的后端进程（PID 经日志确认）；未触碰任何其它 java 进程【实测】 |
| 端口回收 | `netstat` 复查 18330 **无 LISTENING**（仅残留 `TIME_WAIT`，属 TCP 正常回收窗口）【实测】 |
| 密钥 | 全程仅经环境变量注入进程，**未落任何文件**；本文档一律写 `***`【实测】 |
| 脱敏 | 本文不出现真实盘符 / 密钥 / 用户名 / 主机名 / 内网地址；路径统一 `<REPO_ROOT>` / `<TMP>`，实例名统一 `<HOST>`【实测：全文自查】 |

---

## 6. 附件清单（全部仓库外，不提交）

| 附件 | 内容 |
|---|---|
| `<TMP>/m1-ds-run1/e2e-run1.log` | 脚本 stdout 全量（27 PASS / 0 FAIL / GFSKIP=0 / Q8 2/2 摘要，含 `[ART]` 行） |
| `<TMP>/m1-ds-run1/boot-m1.log` | 后端 DEBUG 全量日志（14 个模型轮次、GF 闸门行、reattach 行、零 ERROR） |
| `<TMP>/m1-ds-run1/run1/gfc-GF{1..5}.sse.txt` | 脚本自行落盘的该用例 SSE 帧全文（含 delta） |
| `<TMP>/m1-ds-run1/run1/gfc-GF{1..5}.http.txt` | 脚本自行落盘的回灌 HTTP 请求/响应原文（按用例隔离） |
| `<TMP>/m1-ds-run1/work1/` | 本轮隔离工作目录（H2 库与 `data/files`，未纳入版本库） |

---

## 裁决结论（指挥官 K3，2026-10-07）

1. 本轮复跑 27/27 全绿（85 秒、Q8 2/2、GFSKIP=0、AI 未降级实测）采纳，GLM-5.3 复核报告第 4 项的降级保留正式闭合，E2E 出口标准以新鲜【实测】成立。
2. TC22 历史条数 8 vs 基线 6、GF1 回灌字段波动、各例帧数波动：判定为模型抖动，断言口径已覆盖，非缺陷，不留行动项。
3. Invoke-AiTurn cancelled 兜底分支本轮仍 0 触发：维持原裁决，M2 以单测覆盖，不阻塞。
