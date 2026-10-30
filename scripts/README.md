# scripts/ 说明

> 本目录放**可执行的验证与门禁脚本**（不是文档目录）。五类内容：E2E 验证脚本、多实例 harness、CI 上游桩与门禁守卫、Redis 目标形态冒烟与迁移工具、提交前敏感信息扫描钩子。
> 文档导航见 `docs/README.md`；如何启动前后端见仓库根 `README.md`。

| 路径 | 作用 |
| --- | --- |
| `scripts/verify-e2e.ps1` | 端到端验证脚本（123 用例 = 既有 35 + 批① 桩模式 88，PowerShell）。断言对象是**已启动的后端**，脚本自身不启动任何服务。AI 对话请求统一带 `X-Tenant-Id`/`X-User-Id`（默认 tenant=1 / user=101），会话号一律取自后端 `POST /api/ai/session` 的 `sessionIdText` |
| `scripts/b23l1-dual-instance.ps1` | **双实例 harness**（B23L1 批①）：8081/8082 共享同一 Redis + 桩上游，脚本自己起/等/收（也可 `-NoStart` 接已就绪实例）。AI 请求统一带身份头。只交付、施工期不运行 |
| `scripts/redis5-cluster-smoke.sh` | **Redis 5.0.14 真集群冒烟网**（3 主 3 从、Docker）。`--static` 只做静态检查（**不连 Redis，不得当"5.0.14 已验证"**）；真集群模式才产出环境级证据，且**不替代**产品链路断言 |
| `scripts/redis-run-migration.mjs` | **AI run 键族离线迁移**（旧裸键 → 带身份 tagged 键 `{<tenantId>:<userId>:run:<runId>}`；未声明身份时落到 legacy `{run:<runId>}`）。**默认 dry-run**，`--apply` 才写、`--rollback` 反向且支持带身份/legacy tagged 双形回退；正向带身份迁移把 `ai:run` 快照的 `tenantId/userId` 写成声明值（写后回读校验身份相等）并把 `ai:runs` 成员重写为 `tenantId/userId/runId`；未声明身份逐字节保留、仅归档。只 `SCAN` 不用 `KEYS`，目标已存在则逐值对账、不一致即失败且不覆盖 |
| `scripts/ci/forbid-redis-cluster-unsafe.mjs` | **Cluster / 5.0 兼容面 grep 守卫**：禁多库、禁 `KEYS`/`FLUSHDB`/跨键 `SCAN`、禁 6.2+ 的排他流 ID。`--self-check` 自证规则（命中/不命中/命中数），CI 两步均跑 |
| `scripts/ci/stub-upstream.mjs` | CI E2E 用的**上游替身**（OpenAI 兼容桩），让用例在零密钥、零外网下确定性通过 |
| `scripts/ci/stub-routes.json` | 桩的路由表（提示词 → 工具调用/表单）+ `usageShapes` + `faults.switches`（§4.6 桩开关清单的唯一取数点），与 `GenerativeFormRules` 白名单**事实源逐值对账** |
| `scripts/githooks/` | 提交前两道检查：敏感信息扫描（DC-08）+ **D6 同步复核**，详见 `scripts/githooks/README.md` |
| `scripts/ci/check-d6-sync.mjs` | **D6 同步复核门禁**：暂存 AI 对话核心文件（block 面）时要求同批新增 `docs/delivery/d6-review.json` 有效确认；测试/模拟设施只告警，docs/infra/业务文件不阻断。指纹 = 规则版本 + 暂存 block 文件的状态/blob/路径 |
| `docs/delivery/d6-review.json` | D6 复核确认记录（`synced` / `not-affected`，每条带指纹）。门禁阻断时会打印可直接粘贴的模板；该文件自身不计入指纹 |

---

## 1. `verify-e2e.ps1` —— E2E 验证脚本

### 1.1 前置条件（脚本不代管，缺项直接失败）

- **后端已在跑**，且满足：
  - AI key 经环境变量注入（`AI_API_KEY`，兼容 `DEEPSEEK_API_KEY` 兜底）；缺 key 时 AI 类用例判 FAIL；
  - **Redis 可用**（默认 `127.0.0.1:6379`）—— AI 会话记忆 / 确认门 / 会话锁都走它；
  - 建议启动参数 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`（TC11 断言 `JOB_PROGRESS` 事件流；`batch-size` 为默认 100 时小配置项不会跨分片边界）；
  - 日志级别 `com.example.configmgr=DEBUG`（TC10 与 Q8 的日志类断言依赖它）。
- **数据面安全**：脚本只创建/删除自己的 `S5B-*` / `AIE2E-*` 任务与 `E2E_TEMP` 定义；`CURRENCY` 演示数据在 TC13 被替换为 3 行，TC19 用全量导出件恢复为 5 行。
- **租户/用户身份**：AI 对话数据面（`/api/ai` 下除 `health` / `tools` / `tenants` / `startup-resume` 与 `providers` / `models` / `settings` 子树）的每个请求都要带 `X-Tenant-Id` / `X-User-Id`，后端缺头即 400。脚本统一在 HTTP/SSE 帮助函数里附加（默认 tenant=1 / user=101），业务数据与模型配置**不**加；会话号只取后端 `POST /api/ai/session` 的权威字段 `sessionIdText`（十进制字符串，禁止用 JSON number，>2^53 会丢精度）。
- **批① 88 条（AIE2E-*）**：接口级、桩模式下**零 SKIP**（本节不调用 `Gf-Skip`），末尾 `AIE2E-MANIFEST` 自校验「条数 88 / 逐域分布 / P0-P1-P2=12-50-26」；P0/P1 红 ⇒ `exit 1`，**P2 红只计数不阻塞**（设计稿 §4.2 第 5 项）。用例名单推导链见脚本第 3 节的注释（154 = 88 + 10 nightly + 41 UI + 7 多实例 + 8 收编）。

### 1.2 参数表

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `-Base` | string | `http://127.0.0.1:18330` | 后端基地址（脚本会在末尾自动去掉 `/`） |
| `-BackendLog` | string | 空 | 后端 stdout 日志文件路径。TC10 的日志断言（Q8①）与 Q8 两项回归需要它；**未提供时日志类断言判 FAIL，不静默跳过** |
| `-EnableGf6` | switch | 关闭 | 启用 GF6 入参闸门用例（**桩模式（CI）启用并计入主计数**；真模型模式默认关——诱导非法 schema 的服从率不可控）。桩侧底座 = `stub-upstream.mjs --violate-form-type`（请求级，只在 GF6 提示词上触发） |
| `-ArtifactDir` | string | 系统临时目录下的 `gfc-artifacts-<guid>`（仓库外） | GF 证据落盘目录；每个 GF 用例写 `gfc-<case>.sse.txt`（实收 SSE 帧）与 `gfc-<case>.http.txt`（回灌 POST 请求/响应原文） |

### 1.3 调用方式

本地手动（真模型白盒验证）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 `
    -Base http://127.0.0.1:18330 `
    -BackendLog <后端 stdout 日志路径> `
    -ArtifactDir <证据落盘目录>
```

CI 内（桩模式）：

```powershell
$env:E2E_STUB_MODE = '1'
./scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <boot.log> -ArtifactDir <artifacts/gfc> -EnableGf6
```

> CI 口径：桩以 `--violate-form-type` 启动（GF6 的确定性底座），脚本带 `-EnableGf6`，主用例计数 **35**（原有 31 = 文档口径 29 + R-137 两条，+ E2E-CTX-1..4）。

### 1.4 用例构成（35 用例）

| 分组 | 计数 | 计入主 PASS/FAIL 与退出码 |
| --- | --- | --- |
| TC1–TC22（业务全链路 + AI 交互） | 22 | 是 |
| TC23（CONFIRM 卡快照重建，T3b/T3-6b） | 1 | 是 |
| GF1–GF5（生成式表单端到端：FILTER/CLARIFY 回灌续跑、取消路径、复核拒绝、幂等 409 双向） | 5 | 是 |
| GF6（入参闸门：非法 schema 不挂起 + `REJECTED_ARGUMENTS` 结局帧；需 `-EnableGf6`，桩模式启用） | 1 | **是**（M2-T4 T4-4 裁决 4 起计入主计数） |
| R-137 红队补测（MAJOR-2 终态任务拒建作业 / MAJOR-3 只读联动在位） | 2 | 是（此前未计入文档口径 29，故真实基数为 31） |
| E2E-CTX-1..4（上下文计量：末块 usage 真值 / 每块重复不放大 / 末块 0/0/0 取中间值 / 无 usage 回退估算；桩侧由请求级 `usage-shape` 标记供形） | 4 | 是 |
| TENANT-AI-1..4（租户验收：身份边界 400 / 后端发号 `sessionIdText` / 会话归属按身份销毁 / 同 sessionId 记忆与 run 读面隔离） | 4 | 是 |
| Q8①/Q8②（明细落库、SSE 请求不做二次 JSON 写入） | 2 | **否**，单列计数 |

### 1.5 退出码与判定口径

| 情形 | 结果 |
| --- | --- |
| 任一主用例 FAIL | `exit 1` |
| GF1–GF4 **全部 SKIP**（本棒零有效验证，硬底线） | `exit 1` |
| `E2E_STUB_MODE=1` 且 `GFSKIP ≥ 1` | `exit 1`（**桩模式零容忍**） |
| 仅 Q8 项 FAIL | 不影响退出码（单列展示） |

**桩模式零容忍（`E2E_STUB_MODE=1`）**：上游是确定性替身，"模型没触发工具"这件事在桩下不可能由模型引起——任何 GF SKIP 都意味着桩路由或产品链路异常，因此 GFSKIP≥1 即整棒 FAIL。真 key 本地手动跑法不设该环境变量，SKIP 语义不变（模型未触发，不计退出码）。

### 1.6 输出与证据

- 逐用例 `[PASS]`/`[FAIL]`/`[SKIP]` 行，末尾摘要打印 `PASS=`/`FAIL=`（实时计数）、`GFSKIP=`、`Q8 项 PASS/FAIL`；
- GF 用例另落盘：`gfc-<case>.sse.txt`（脚本实际收到的 SSE 帧，按到达顺序）与 `gfc-<case>.http.txt`（脚本实际发出的回灌 POST 原文）——失败时用于区分"桩坏 / 产品坏"。

---

### 1.7 安全面探针 X-01 / X-02 / X-03（**请求级**，L-99 删 DLP 后的新口径）

这一组用例的语义是**原文透传**：AI 输入/输出不再脱敏、不再阻断。因此断言方向与旧 DLP 时代**相反** —— 锁「原文确实到达」，并额外锁「掩码痕迹确实不存在」。

| 用例 | 级别 | 探针 | 判据（任一条不成立即 FAIL） |
| --- | --- | --- | --- |
| `X-01` | P0 | **输入侧**假密钥：`sk-` + 40 个 `A`（拼字符串生成，不是真 key） | ①无 `error` 帧（不 block）②`GET /api/ai/history/{sid}` 里**有**密钥原文（被掩码即回归）③历史里**无** `[REDACTED` 痕迹 ④DLP 三计数零增长 |
| `X-02` | P1 | **输出侧**假密钥：`sk-` + 40 个 `B`，由桩在 `【AIE2E-X-02】` 标记下回出 | ①`done` 帧**不带** `safety` 段 ②`delta` ≥ 3 片 ③拼回正文含**完整确定性回复**（`密钥一：token=<探针> 结束。`，前缀/后缀被改写即红）④正文无 `[REDACTED` ⑤**任一单片都不含完整密钥**、且分片边界落在密钥内部（跨分片实证，防「断言还在、对象没了」）⑥DLP 三计数零增长 |
| `X-03` | P1 | **输出侧**假 PEM 块（头 / body / 尾三段由**片段拼接**生成，避开提交前密钥扫描规则） | ①无 `error` 帧、终态必须是 `done`（不再阻断）②头/body/尾三片段齐全、**连续成块**、首尾顺序不倒置 ③正文无 `[REDACTED` ④`delta` ≥ 2 片且**完整 PEM 块不落在单片内**（跨分片实证）⑤DLP 计数（`outputBlocked` / `outputRedacted`）零增长 |

**DLP 三计数零增长的判法**（`Ai-AssertDlpFlat`，三条都是防假绿）：

1. 起始基线为 `null` **一律判 FAIL** —— 跳过比较会把「计数面不可观测」伪装成零增长；
2. 逐键先断言 `before` / `after` **两处键都存在** —— 键被删或改名时 `0 == 0` 同样是假绿；
3. 再比值。三键 = `inputRedacted` / `outputRedacted` / `outputBlocked`；L-99 后已无写入点，键位保留属 `/health` 兼容面，故「值不增长」才是正确形态。

**分片证据落盘**（`-ArtifactDir`，仓库外）：X-02 / X-03 各写一份 `<case>-safety-probe.txt`（`x02-safety-probe.txt` / `x03-safety-probe.txt`），内容是**实收 `delta` 分片按到达顺序逐条列出**（每行带 `off` / `len` / `touchesNeedle`），并在首行给「拼回后 needle 起始偏移」。用途 = 事后复核「上游确实把探针切开并发出」——帧是上游 chunk 经产品 SSE 的中继，切点即上游切点。未传 `-ArtifactDir` 时该步静默跳过，不影响判定。

**桩侧底座（`scripts/ci/stub-upstream.mjs`）**：`safetyProbeDecision` 只认**最后一条 `user` 消息**里的用例标记（`【AIE2E-X-02】` / `【AIE2E-X-03】`），不看工具披露、不看其它开关 ⇒ 探针**不污染**既有语料；无标记时仍走 `turn-text` 蓝本。探针分支挂在 **`blueprintDecision` 入口之内且先于 `turn-text`**（X-02 / X-03 的提示词同时带 `turn-text` 故障标记，顺序被抢占就会退回「空断言」）。`--self-check` 段 ⑨-d2 从**真实入口**验证可达性 + 探针串确实跨分片 + 无标记不被污染；与 `verify-e2e.ps1` 的 X-02 / X-03 是同一组期望，任何一侧漂移，自检或 E2E 即红。

> 探针串全部是测试用**假串**：`sk-` 前缀与 PEM 头尾都按片段拼接，避免与提交前敏感信息扫描规则自撞；仓内不写真实密钥。

---

## 2. `scripts/ci/stub-upstream.mjs` —— CI 上游替身

### 2.1 用途边界（引用文件头口径，**不得扩大解释**）

- 本桩是**产品链路的替身，不是模型行为的替身**。CI 全绿只证明"桩给出的表单/工具调用被产品链路正确消费"，**不证明模型服从率**；
  桩下 `GFSKIP=0` 由构造保证（桩按路由表下发表单），属恒真式而非观测；
  GF6（入参闸门，桩模式启用）同口径：非法 schema 由 `--violate-form-type`（请求级）**构造保证**，故其 SKIP 分支在桩模式同样不可达（未产出即判 FAIL，不判 SKIP）；
- **真模型的 E2E 维持本地手动跑**（S5d 裁决 #3），不在 CI 内；
- 零依赖：只用 Node 内置模块（`--self-check` 会断言这一点）。

### 2.2 收敛契约（硬要求：后端工具循环无迭代上限，桩必须自证终止）

① 披露清单驱动 —— 目标工具必须出现在本请求 `tools[]` 里才允许发起调用；② 每个会话轮次的上游调用上限（`convergence.maxCallsPerTurn`，默认 6）；③ 续轮只发文本（**本轮**已出现 `role=tool` 消息——即最后一条 `user` 之后有工具结果——时不再发起工具调用；历史带来的工具结果不算续轮：上一轮的工具结果已进历史、其后又有新用户消息，那属新轮、必须照常下发 `tool_calls`，否则新用户轮的首次请求会被误判成续轮 ⇒ 永不下发工具调用，见挂账 L-84）；④ 解析失败不猜（一律以文本收尾）。`--self-check` 用"首轮→工具调用、续轮→文本、新用户轮（历史带工具结果）→工具调用"的模拟循环逐条证明上述四条成立。

### 2.3 命令行

```sh
node scripts/ci/stub-upstream.mjs --port 18399 --dir <落盘目录> [--routes scripts/ci/stub-routes.json]
node scripts/ci/stub-upstream.mjs --self-check [--routes scripts/ci/stub-routes.json]
#      [--violate-form-keys | --violate-form-type]   # 违规模式，默认关闭
#        --violate-form-keys：全局（S7 缺陷注入口径不变）
#        --violate-form-type：**请求级** —— 只有提示词明写"把字段 type 故意写成 html"的用例（E2E 的 GF6）
#          触发越白名单注入，不连带污染共用 GF-FILTER 路由的 GF1/GF3/GF4（M2-T4 T4-4 裁决 4）
```

### 2.3b usage 形状（E2E-CTX-1..4 的确定性底座）

桩按提示词里的 **`usage-shape=<last|repeat|zero-last|none>`** 标记发 usage —— **请求级**开关（与 `--violate-form-type` 同族），
不带标记即 `last`（改造前的行为，帧逐字节不变）；工具轮不受影响。

| 形状 | 帧形态 | 消费它的用例 |
| --- | --- | --- |
| `last` | 末个 usage-only 帧发一份真值（基线） | E2E-CTX-1（真值 + 端点同源） |
| `repeat` | **每个 content 分片都挂同一份** usage，末帧再挂一次 | E2E-CTX-2（不放大） |
| `zero-last` | 首个分片真值 + **末个 usage-only 帧 0/0/0** | E2E-CTX-3（取中间真值） |
| `none` | 整轮不发 usage（`stop` 终帧与 `[DONE]` 仍在） | E2E-CTX-4（回退估算） |

数值唯一取数点 = `stub-routes.json` 的 `usageShapes`（`verify-e2e.ps1` 读同一份 ⇒ 桩与用例不会漂移）；
`--self-check` 的段 ⑧ 逐形状断言帧形态与标记解析，并校验形状表三维自洽。

### 2.3c 故障注入与命名轮蓝本（E2E 批① 的 §4.6 开关；同样是**请求级**提示词标记）

| 开关 | 形态 | 消费它的用例 |
| --- | --- | --- |
| `turn-text` / `turn-interleave` | 确定性文本轮 / 文本↔工具交错轮（段序基线） | AIE2E-S-01/S-02（S-02 归批② nightly） |
| `break-at-seq=K` | 写满 K 帧后销毁 socket（不发 `[DONE]`） | S-05 / M-08 / M-09 |
| `stall-frames=<ms>` / `stall-headers=<ms>` | 帧间静默 / 写响应头前静默 | S-04 / S-03 |
| `kill-stream=<ms>` | 到点销毁 socket（上游 TCP 断） | S-06 / D-09 |
| `slow-tool=<ms>` / `slow-reader` | 工具轮首帧前静默 / 慢生产者 20×250ms | S-09·S-11 / S-14·D-10 |
| `slow-first-byte=<ms>` | **只慢首包**：响应头之后、首帧之前（文本轮同样生效） | S-03 / R-03 |
| `slow-tool-exec=<ms>` | **只慢工具执行段**：工具轮 / 工具续轮（上下文已有 `role=tool`）的首帧 | S-09 / S-11 / R-03 |
| `slow-tail-frames=<ms>` | **只慢帧间·收尾段**：仅工具续轮的帧间静默（首帧不延迟） | P-04 |
| `late-frames=<n>` | `[DONE]` 之后继续推 n 帧（迟到帧闸门） | S-12 |
| `tool=<name>&args=<json>` | **请求级工具路由**：直接指定本轮工具与入参（仍受 `requiresTools` 披露闸 + 同提示词幂等闸） | L-02…L-08 |
| `epoch=<nonce-N>` | **请求级 epoch 注入**：把工具入参里既有的 `epoch` 换成页面真值（形状与产品 Guard 正则同源） | V-02 / V-03 / V-04 / V-06（模型侧成功路径） |
| `multi-card=<n>` | 一条消息 n 个 `tool_calls`（id 互异） | H-04 / H-11 |
| `huge-frame=<bytes>` / `tool-loop=<n>` | 单帧字节数 / 连续 n 次工具调用 | S-13 / S-10 |
| `reasoning=<chars>` | **思考分片**：正文分片之前插 1–2 个 `delta.reasoning_content`（L-91 的上游侧形态；`0` / 裸标记 = 关闭，帧逐字节不变）。与 `huge-frame` 可同开：>64KB 的思考文本保持单片，覆盖产品帧侧字段级截断面 | S-15s |
| `fail-503-once` | 同一轮首次 503、之后正常 | S-07 |
| `context-overflow` / `summary-500` | 越窗 400（措辞取 `ContextOverflow.MARKERS`）/ 摘要请求 500 | C-12 / C-10 |
| `gf-seven-types` / `gf-linkage` / `gf-expr-linkage` / `gf-deep-linkage` / `gf-options-from` / `gf-attachment` / `gf-longtext` | GF 新能力面的路由开关（`GF-*` 路由） | G-04…G-15 / X-10 |
| `【AIE2E-X-02】` / `【AIE2E-X-03】`（提示词标记，非 `faults` 键） | **安全面探针**：桩主动回出跨分片假密钥 / 完整假 PEM（请求级，只认最后一条 `user` 消息、先于 `turn-text` 判定） | X-02 / X-03（见 §1.7） |

`fe-tool-hang` / `sheet-big` / `preflight-fail` 三项**不是桩层开关**（`faults.switches` 里标 `layer=script/product`）：
挂起由用例的处理器停住、大表由用例自建任务、预检失败走产品既有语义 —— 桩只提供触发面，不伪造业务结果。
`--self-check` 段 ⑨ 断言"表与实现不漂移"（表 31 项，桩层 28 项逐项有实现）、标记解析、写出计划（含三个细分段的段身份）、
段序、多卡 id 互异、大帧字节数、思考分片（默认关闭零漂移 / 1–2 片全在正文之前 / 与 `huge-frame` 正交）、
越窗错误体命中产品 `MARKERS`（读事实源）、摘要识别式与 `RollingSummarizer` 同源；
段 ⑩ 断言请求级工具路由（嵌套 args / 披露闸 / 产品清单闸 / 幂等）、epoch 注入（只改既有键）与迟到帧。

### 2.4 记录契约（可归因性）

每请求落 `<dir>/req-NN.json`（请求原文）、`.tools.txt`（本请求实际披露的工具名清单）、`.head.txt`（模型可见消息摘要）、`.decision.json`（桩本轮的判定：路由 id / 工具名 / 理由）——这是"桩坏 / 产品坏"的区分依据。

### 2.5 与产品侧白名单的对账（已改为事实源逐值对账，T4-5 落地）

`stub-upstream.mjs` 的 13 组常量 + 6 项上限仍是 `GenerativeFormRules` 的**桩内副本**，但 `--self-check` 的自检③已改为**读产品事实源 `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java` 逐值对账**（13 组常量 + 6 项上限 + 原型三键；不新增端点、不新增生成步骤）——产品侧收紧或改动白名单而桩未同步时，**自检即红、e2e job 即红**，"副本自证仍绿"的漂移窗口已关闭。自检输出会打印事实源路径；对事实源**文本格式**（常量声明书写形态）有依赖，采用宽松解析、解析失败即红。

> 桩 / 前端 / 后端三处"原型键黑名单"另由后端用例 `GenerativeFormContractTest#forbiddenFieldKeysMatchAcrossEnds` 逐键锚定（同一批 T4-5 落地）。

### 2.6 域专用 Playwright config（本域桩 / 本域端口段）

有些专用域与官方桩的两条收敛契约（"本轮已有 `role=tool` 只发文本" / "每条路由每会话只发一次"）直接冲突，
而 `stub-routes.json` 是共享契约、不为本域扩表 ⇒ 这些域**换桩脚本 + 单开一个 config**（`testMatch` 只收本域 spec）。
config 只做"加载期 `??=` 补默认值"（显式 env 永远优先），起停仍由统一 `support/harness.ts` 负责。

| 域 | config | 桩脚本 | 端口段 / Redis DB | 跑法（`cd frontend`） |
| --- | --- | --- | --- | --- |
| GF2（同会话两轮表单） | `e2e/playwright.gf2.config.ts` | `e2e/support/gf2-upstream.mjs` | 22530 / 22531 / 22599 / 22601 / 22602 + DB 3 | `npx playwright test --config e2e/playwright.gf2.config.ts` |

> CI 侧：`.github/workflows/e2e-real.yml` 的 `e2e-stub` job 在 `--grep @stub` 之后**追加一步**跑 GF2 域
> （同一步骤序列、同一 Redis service，端口段与主 config 的 183xx 完全错开）；`ci.yml` 不动。

---

## 3. `scripts/b23l1-dual-instance.ps1` —— 双实例 harness（B23L1 批①+批②+批③）

### 3.1 用途边界

多实例专项（`docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md`）的**实例面验证载体**：两个后端进程（8081/8082）共享同一 Redis 与同一上游桩，验证"任何实例可 serve 任意 run 的 reattach"与"跨实例唤醒扇出"。**不进 CI 的 35 主用例**，先脚本化（设计稿 §5.3）。

### 3.2 形态与前置

| 组件 | 默认 | 说明 |
| --- | --- | --- |
| 实例 A / B | `8081` / `8082` | 默认各自独立 H2 文件（避免文件锁冲突）；`-SharedDb` 时共享同一 H2 文件（绝对路径 + `AUTO_SERVER=TRUE`）；Redis 共享 |
| 共享 Redis | `127.0.0.1:6379` | 事件日志 `ai:xevents:`、唤醒频道 `ai:wake:`、序号 `ai:seq:`、续跑权 `ai:lease:run:`、资源锁 `ai:lock:*`、幂等索引 `ai:idem:` 都在这里 |
| 上游桩 | `18399` | `scripts/ci/stub-upstream.mjs`（零密钥）；实例以 `AI_API_KEY=b23l1-stub-placeholder` 启动 |

前置：后端 jar 已打包（`mvn -DskipTests package`）、环境有 `java` 与 `node`。脚本自己起子进程并做健康等待：判据 = `/api/ai/health` **扁平响应体**的 `available && redis.available`（该端点无 `data` 包装、也无 `ai` 子对象；AI 不可用时返回 503 但体形不变），缺项整棒 FAIL。起进程前先做 8081/8082/18399 的端口连通性预检（端口被占 ⇒ 直接 FAIL，避免在"旧的健康实例"上产出假绿证据），并对 `RedisHost:RedisPort` 做连通预检（harness 不自起 Redis，不通即 FAIL）。挂起类用例会在 A 侧自建一个真实 `EXPORT` 任务并 `select-defs`：确认门前的只读预检要求任务存在，chat 也必须带 `context`（否则 `start_export` 不在披露清单里，桩会退化为文本轮）。

**AI 身份与发号（租户任务 5）**：所有 `/api/ai` 对话数据面请求（chat / events / confirm / runs / history / session / pending / frontend-tool-result / cancel）统一带 `X-Tenant-Id` 与 `X-User-Id`（默认 tenant=1 / user=101；业务 `POST /api/tasks`、`select-defs` 不加）；会话号一律 `POST /api/ai/session` 取 `sessionIdText`，脚本不本地造号。

**L-12 / `-SharedDb` 边界**：默认两实例各用独立 H2，跨实例同 `taskId` 时另一侧预检会先失败（任务不存在），走不到资源锁；`instance-kill` 的 B 接管也会因 B 库无任务而被 `ToolTargetResolver` 前置拦截（`blocked/TARGET_INVALID`，不写台账、不执行）——这是设计内形态。`-SharedDb` 把两实例指到同一 H2 文件（`jdbc:h2:file:<绝对路径>/h2/b23l1_shared;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1`），`resource-lock` 才会跑跨实例竞态版，`instance-kill` 才可能走"台账 1 条 executedBy=B"的形态甲。**I-2 竞态重试**：跨实例版的临界区是毫秒级 `createAndStart`，两轮可能不重叠（两轮都执行）——该变体最多跑 **3 轮**（每轮新 task + 两个新 run 同刻发令），任一命中「恰一个 RESOURCE_LOCKED + 恰一个执行 + 台账合计 1 + 作业 1」即 PASS；3 轮全未命中判 **SKIP**（不判 FAIL，行为可能完全正确；跨实例资源互斥证据以 H3 确定性变体为准）。H2 共享不可行（启动不健康）时 harness **直接 FAIL 并点名该边界**，可去掉 `-SharedDb` 改用 H3 预置锁变体。**桩指纹约束**：跨实例变体同一 taskId 的两条消息必须带互异后缀（桩的会话指纹 = 首条 user 消息文本；同 taskId + 同一句话会被判成同一会话，后到者因 `bucket.used` 已占 TC16-EXPORT 退化为文本轮、不弹确认卡）。**守卫**：`-SharedDb` 与 `instance-kill`/`lease-scripts` 同棒会因"杀 A = 杀 H2 AUTO_SERVER 服务端、B 的连接随之断开"而必然半路崩，脚本在起进程前直接 FAIL 要求分两棒跑。`idempotency` 不需要 SharedDb：第二次决策只在 Redis 上读 `ai:decided:`/pending，不触碰任务库。

### 3.3 用法

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/b23l1-dual-instance.ps1 `
    -Jar backend/target/config-mgr.jar `
    -ArtifactDir backend/target/b23l1-artifacts

# 只用已就绪的 8081/8082（不自己起服务）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/b23l1-dual-instance.ps1 -NoStart

# 只跑某一组
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/b23l1-dual-instance.ps1 -Cases suspend-reattach-wake

# 批③跨实例资源锁（共享 H2；其余用例仍可在同一棒里跑）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/b23l1-dual-instance.ps1 `
    -SharedDb -Cases resource-lock
```

退出码：任一用例 FAIL ⇒ `1`；SKIP 不计退出码。默认用例 = `reattach-full-replay,reattach-diff,suspend-reattach-wake,suspend-decision,resource-lock,idempotency,instance-kill`；`resource-lock` 默认走 H3 预置锁变体（不加 `-SharedDb`），加 `-SharedDb` 走跨实例共享库版；`instance-kill` 会 kill `instance-a`，恒为最后一条。`lease-scripts` 也会 kill A，且耗时较长（等待方续租/租约过期），须单独跑或排在最后。

### 3.4 用例面

| 用例 | 归属 | 断言 |
| --- | --- | --- |
| `reattach-full-replay` | 批① | A 侧一轮跑完，B 侧从 0 回放得到**逐帧同序同号**（连接层无状态） |
| `reattach-diff` | 批① | B 侧带 `?lastSeq` 只拿到差量，不重不漏 |
| `suspend-reattach-wake` | 批① | A 侧挂起 → B 侧重挂 → 在 A 批准确认 → 续跑帧**经 `ai:wake:` 扇出到 B 的连接**（终帧到达且 seq 推进）；并记录 confirm→B 首帧延迟（P50/P95） |
| `suspend-decision` | 批② | 决策打 B：`accepted=true` 且 `resumeOutcome=LEASE_HELD_ELSEWHERE`（A 的等待方持租）；`tool_start`/`tool_result` 各恰 1 帧；A、B 双侧重挂都能收到终帧 |
| `resource-lock` | 批③ | 同 task 双 `start_export`：恰一个真实执行、恰一个 `tool_result` 为 `code=RESOURCE_LOCKED / status=BLOCKED / outcome=blocked / executed=false / retryable=true`；两条流都收到 done；台账 `start_export` 合计 1（预置锁变体为 0）；作业数 1（预置锁变体为 0，解锁后重跑为 1）；终态后 `ai:lock:job:<taskId>:EXPORT` 不存在。默认 H3 必跑；`-SharedDb` 跨实例版最多重试 3 轮，任一命中即 PASS，3 轮均未命中判 SKIP（竞态窗口未命中，证据以 H3 为准） |
| `idempotency` | 批③ | 首轮执行后再用同一 `runId+toolCallId` 打 B → 409 `DUPLICATE_TOOL_CALL_ID`；台账仍 1 条、全量回放 `tool_start` 仍 1 帧、作业数仍 1；真 Redis `HGET ai:idem:<runId> <runId>:<toolCallId>` 非空 |
| `instance-kill` | 批② | `Stop-Child -Name instance-a` 杀真 A；B 的 `/runs/current` 能重建挂起轮；决策落 B 后租约过期由延迟重试接管（预算 `-KillTakeoverTimeoutSec`，默认 90s）；`tool_start` 恰 1 帧、终帧到达。中间证据**两形态皆可**并打印实际形态：甲（共享库/生产）= 台账恰 1 条且 `executedBy=B`、`ledgerCounts.start_export=1`；乙（默认，H2 隔离 L-12）= 台账 0 条 + 全量回放该 toolCallId 有 `TARGET_INVALID / status=BLOCKED / executed=false`（设计内前置拦截） |
| `suspend-decision-concurrent` | 批②（可选） | 同一 runId/toolCallId 双路并发 POST：恰一个 200（accepted）+ 一个 409（`DUPLICATE_TOOL_CALL_ID`）；`confirm_decision`/`tool_start` 各恰 1 帧 |
| `lease-scripts` | 批②（可选，终止性） | 真 Redis 租约冒烟：`/health.leaseOk=true`；挂起期租约键存在（ACQUIRE）、等待 12s 后 PTTL 回升（RENEW）、fence 键存在且快照 `runEpoch>0`、台账 `epoch>0`（FENCED-SET）、终态后租约键消失（RELEASE）；并发两路 `POST /runs/{runId}/resume` 恰一个 202、一个 409 |

### 3.5 证据

每用例把帧原文落 `<ArtifactDir>/<case>*.sse.txt`；两个实例与桩的 stdout 落 `<ArtifactDir>/{instance-a,instance-b,stub}.log`。失败时先看"哪一侧没收到/号断在哪"。**终态回放窄窗**：后端 `finish()` 先 `save(DONE)` 再 `out.done`，且终态回放不挂订阅；恰在"快照已 DONE、done 未落档"窗口到达的重挂会拿到到 `message_end` 为止的回放并直接收流。harness 的全量回放遇到"无终帧"会短延迟重读（最多 4 次，`<tag>-a1..a4.sse.txt` 留证），4 次仍无终帧才 FAIL（不掩盖真实的 done 缺失）。`suspend-reattach-wake` 的订阅就绪哨兵为 `<ArtifactDir>/wake-B.ready.<runId>`（作业写、主线程轮询）；摘要前会打印 `帧延迟（A confirm 完成 → B 首续跑帧）：P50/P95`（L-5 取证，单样本时 P50=P95）。批③ 的资源锁/幂等索引用只读 RESP 直读真 Redis（`Invoke-RedisCommand`：GET/PTTL/HGET）；H3 变体的预置锁走 `Invoke-RedisWrite`（**仅** `SET key value PX ms` 与 `DEL key`，作用域固定为 harness 的 Redis，仅供测试预置/清理）。

---

## 4. `scripts/githooks/` —— 提交前敏感信息扫描

两条防线（`pre-commit` 扫暂存区新增行、`scan-history.sh` 扫全历史 blob），规则共用 `scan-lib.sh`。启用方式、三条规则、豁免清单与自测方法见 **`scripts/githooks/README.md`**。

**扫描性能（2026-10-24，笔 `904cd20`/`784f0ce`）**：`scan-history.sh` 改**并行扫历史 blob**、NUL 走 `printf`（避开 awk 实现差异）⇒ 修复后一次全历史扫描 **4m26s**；**扫描面未缩小**（仍是全历史 blob × 三条规则，命中语义不变），CI 里 `scan-history` job 已随 run `40099230125` 绿。

---

## 5. Redis 目标形态面：集群冒烟 / 迁移工具 / 静态守卫（2026-10-24 批增补）

> **一句话边界**：这三件是**环境与键形态**的验证与迁移设施，不构成"产品已在 Cluster 上验收"的证据。**当前事实（2026-10-24）**：脚本已入库（`882e6c5` 迁移工具 + 冒烟网 + 守卫 + ci 接线 / `b38a95a` 冒烟网改用容器 IP 建 5.0 集群 / `03dbb84` 冒烟网等待 3 主 3 从落定并修 Pub/Sub 缓冲）；**真集群环境级已通过** = run `40097120796`（`completed/success`，headSha `c2f8b2f`）**PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3**（GitHub Actions Ubuntu + Docker `redis:5.0.14` 临时 3 主 3 从）；整仓 CI run `40099230125`（`784f0ce`）**success、全部 job 绿**。**范围限定**：这不是生产连接验证，也不是产品 `available`/503 的证明（网内该条 SKIP、且网内不启动后端），更不是迁移工具的真集群执行证明；**真集群上的迁移执行、双 JVM runner 删除/恢复仍未验证**。迁移工具用例 **61/61** 通过；守卫 `--self-check` 通过（12 正向样例）。

### 5.1 `redis5-cluster-smoke.sh` —— 3 主 3 从真集群冒烟

```sh
bash scripts/redis5-cluster-smoke.sh            # 真集群（需 Docker；CI 用这个）
bash scripts/redis5-cluster-smoke.sh --static   # 只跑静态检查（无 Docker 也能跑）
```

- **退出码**：`0` 全绿 / `1` 有 FAIL / `2` 环境或参数错误。
- **证据目录**（默认 `backend/target/redis5-smoke`，已被 `.gitignore` 覆盖）：`summary.md` / `commands.log` / `cluster-*.txt` / `failover-*.txt` / `log-scan.txt` / `redis-*.log` / `pubsub-subscriber.log` / `slot-map.txt`。
- **口径（必须如实交代）**：①`--static` 模式**不连接任何 Redis**，不得用来声称"5.0.14 集群已验证"；②故障注入只断言确定性事实（死节点探针的连接失败证据、存活主 PONG、超时内收敛、收敛后可写读），**不对** `RedisAvailability.available` 做瞬时单值预期（取决于连接落点与重连时序，是 flaky 源）；③节点 `docker logs` 只落盘备查、不设门禁，窄门禁只针对 `commands.log` 里三条预期探针之外的错误；④只用公共镜像、容器间走 user-defined network、**不映射宿主端口**（从根上消除端口冲突）；⑤CI 载体 = `.github/workflows/redis-5-smoke.yml`（先 `bash -n` 静态检查，再跑真集群）；产品契约门 = 主代码不得出现 `Range.Bound.exclusive` / 字符串 `(123-0`（5.0 会报非法 stream ID）——该门**修前必红、修后转绿**；⑥**结论（如实，含留痕）** = 最终跑 `40097120796`（headSha `c2f8b2f`）：**PASS 73 / FAIL 0 / KNOWN-LIMIT 1 / SKIP 3**，产品契约静态门已随 `c2f8b2f` 转绿。**历史两跑非当前状态**：`03dbb84` 环境面 72 PASS + 旧后端产品契约门 1 FAIL（`XRANGE` 排他区间）；更早 `40096255321` 集群创建失败。**SKIP 两条要记住**：`RedisAvailability.available` 未在本网验证、未提供 `SMOKE_BACKEND_LOG`（本网不启动后端）。

### 5.2 `redis-run-migration.mjs` —— run 键族迁移（带身份 / legacy 双命名空间）

```sh
node scripts/redis-run-migration.mjs                                          # dry-run，只打印计划
node scripts/redis-run-migration.mjs --tenant-id 1 --user-id 101 --apply     # 正向带身份真写（源默认保留）
node scripts/redis-run-migration.mjs --tenant-id 1 --user-id 101 --rollback  # 反向（tagged → 旧裸键）
```

- **目标键形**：带身份 = `<prefix>{<tenantId>:<userId>:run:<runId>}`；未声明 `--tenant-id/--user-id` 时只得到 legacy `<prefix>{run:<runId>}`（独立命名空间，带身份的新路径读不到，dry-run 会警告）。
- **迁 10 族**：`ai:run:` / `ai:seq:`（STRING）、`ai:pending:` / `ai:claim:` / `ai:idem:` / `ai:req:` / `ai:decided:`（HASH）、`ai:ledger:` / `ai:events:`（LIST）、`ai:xevents:`（STREAM）。
- **快照身份同步（正向带身份必做）**：写 `ai:run` 目标前解析快照 JSON，把 `tenantId/userId` 置为命令行声明值；写后回读校验“记录身份 = 声明身份，且除身份外其他字段逐值不变”，不通过即失败；`--delete-source` 的校验阶段也按同一口径复核，不通过不删任何源。未声明身份时快照逐字节保留（含 0/0），dry-run/apply 都明确警告“仅具归档价值、不属于任何新身份”。
- **`ai:runs` 索引（正向带身份必做）**：数据键落位后，把本次 run 的成员从裸 `runId` 重写为 `tenantId/userId/runId`（`SREM` 旧形 + `SADD` 新形，同一 `MULTI`）。配合快照身份写入，迁移后的轮次可被显式身份读口 `RunStore.get(runId, t, u)` 命中（键存在 + 记录身份复核），同时可被 `allRunRecords()` 按成员身份发现 —— 不是只证明“索引成员/键还在”。索引缺失时补写，两种形态并存时去重，已是目标形态时 0 写；数据已迁完、只剩索引陈旧时用 `--run-id` + 身份可只修索引。未声明身份不重写。
- **rollback 双形回退**：源逐族先试带身份 tag，`absent` 再回退 legacy tagged；声明身份时把 `ai:runs` 成员反向恢复为裸 `runId`。
- **明确不迁**：`ai:fence:run:` / `ai:lease:run:` / `ai:lease:epoch:`（含旧全局 `ai:lease:epoch`）、`ai:runs` 的 10 族数据（索引成员按上一条单独重写）、`ai:session:current:`、`ai:beat:` / `ai:cancel:` / `ai:activity:` / `ai:lock:` / `ai:resume:lock:`、`ai:ctx:` / `ai:safety:` / `ai:cfg:ver` / `ai:rate:`；`chat:mem` legacy 由仓储回退读 + 首次成功保存自然迁移。
- **安全口径**：①节点只从 `AI_REDIS_CLUSTER_NODES` 读、无默认值；②目标不存在才写，已存在则逐值对账（一致跳过、不一致即失败且**绝不覆盖**）；③`seq` 与 `xevents` 必须成对，任一缺失则整 run 跳过（不留下活快照无帧）；④按类型保留全部值/字段/顺序/显式 stream ID/PTTL；⑤`--delete-source` 先做“全部候选值（带身份族按快照身份 + 其余字段对账）+ PTTL 充分性”校验（校验阶段失败不删任何源）；⑥发现 key 只 `SCAN`（逐 master），**禁止 `KEYS`**；⑦`--apply` 用全局迁移锁串行化、按 token 续租，锁丢失即在下一检查点停止（**不承诺当前命令回滚**）。
- **离线窗口**：写前停业务写入；同一时间只允许一个迁移进程；任一错误即停并非 0 退出，**不打印成功结论**。
- **用例** = `node --test scripts/redis-run-migration.test.mjs` **61/61 通过**（含索引重写、快照身份回读、legacy 归档与 rollback 双形回退用例）。**注意**：这些是**离线用例**，不构成"迁移工具已在真集群执行过"的证据（真集群冒烟网只验环境面，不跑迁移）。

### 5.3 `ci/forbid-redis-cluster-unsafe.mjs` —— 静态守卫

```sh
node scripts/ci/forbid-redis-cluster-unsafe.mjs              # 扫 backend/src/main
node scripts/ci/forbid-redis-cluster-unsafe.mjs --self-check # 规则自证
node scripts/ci/forbid-redis-cluster-unsafe.mjs --root <dir> # 指定目录（排障）
```

禁止进入 `backend/src/main` 的面：**多库**（`database:` / URL 库号 / `setDatabase`）、**单机全量命令**（`KEYS` / `FLUSHDB` / `FLUSHALL` / 跨键 `SCAN`）、**5.0 不支持的排他流 ID**（`Range.Bound.exclusive` / 字符串 `(123-0`）。keys/scan 规则只认 Redis 侧接收者（避开 JPA 原生 SQL 与业务方法 `scan(` 的误报），配置规则要求文件处于 Redis 上下文；原生命令字面量同样按接收者判定，且豁免 fail-closed：先剥离注释与字符串字面量、再按"同文件 + 同作用域包住调用点 + 同名无其它类型遮蔽"认定 `Statement` / `PreparedStatement` / `CallableStatement` 变量为 JDBC，Redis 侧别名（`conn` / `client` / `connection` …）永不豁免 —— 例如 `statement.execute("SELECT pg_advisory_lock(...)")` 是 PostgreSQL SQL，不是 Redis `SELECT`。`ci.yml` 里 `--self-check` 与主扫各一步。

---

## 6. `scripts/ci/check-d6-sync.mjs` —— D6 同步复核门禁（2026-10-30，D6 Task 2）

> **一句话边界**：这是一道**人工确认门禁**，不是语义验证。它只保证"改 AI 对话核心文件的人在同一笔提交里留了一条当次专用确认"，**不保证** D6 正文写对了，也**不校验** `reason` 是否属实。

```sh
node scripts/ci/check-d6-sync.mjs             # 读当前暂存区（pre-commit 就是这么调的）
node scripts/ci/check-d6-sync.mjs --root <仓库根>
node scripts/ci/check-d6-sync.mjs --help
```

| 退出码 | 含义 |
| --- | --- |
| `0` | 暂存区没有 block 面文件，或确认有效 |
| `1` | 有 block 面文件但确认缺失 / 指纹不符 / `synced` 未同批改 D6 / `not-affected` 理由不具体 / 复用了历史记录 |
| `2` | `d6-review.json` 非法 JSON、`reviews` 结构错误、`version` 不符、非 git 仓库（**fail-closed**，不会被当成"无确认"放行） |

**路径分类（轻量，只 block 一处阻断）**

| 分类 | 覆盖 | 阻断 |
| --- | --- | --- |
| `block` | `backend/src/main/java/.../ai/**`、`backend/src/main/resources/ai/**`、`common/id/**`、`common/sse/**`、`frontend/src/components/AiPanel/**`、`stores/ai.ts`、`api/{ai,aiConfig,sse,http}.ts`、`types/{ai,sse,tools}.ts`、`utils/{ai-*,frontend-tools,context-usage,parts}.ts`、`locales/**` | 是 |
| `warn` | 后端 AI/发号/SSE 测试、前端单测与 `e2e/**`、`scripts/ci/stub-*`、`verify-e2e.ps1`、`b23l1-dual-instance.ps1`、`messages_*.properties`、`stores/locale.ts` | 否（只告警） |
| `doc` | `docs/**` 与任意 `*.md`（含 `docs/delivery/d6-review.json`） | 否 |
| `infra` | `scripts/**`、`.github/**`、构建配置（`pom.xml` / `package.json` / `tsconfig*` / `vite.config.*` / `application*.yml`） | 否 |
| 其余 | 业务文件 | 否（忽略） |

> 缩写口径：后端路径前缀 `backend/src/main/java/com/example/configmgr/`，前端路径前缀 `frontend/src/`；完整判定表见 `scripts/githooks/README.md` §4.1.1。

**确认记录**（`docs/delivery/d6-review.json`，`version: 1` + `reviews` 数组）：每条含 `fingerprint` / `status` / `reason` / `reviewedAt` / `reviewer`。

- `status=synced`：本批**同时**更新了 `docs/delivery/06-新项目复用与落地指南.md`（门禁核对它确实在暂存区）；
- `status=not-affected`：本次改动不影响 D6 描述的迁移面，`reason` 必须具体（去空白后 ≥8 字，且不是 `n/a` / `无` / `待补`）；
- **确认必须本次新增**：HEAD 里已有的同指纹记录不复用；改文件内容或增减 block 文件都会改变指纹，旧确认立即失效（重跑门禁即可拿到新指纹与可粘贴模板）；
- `d6-review.json` 自身不参与指纹计算。

**单测**（**逐条**跑，禁止一次跑全部；本仓库测试口径要求精确单点命令）：

```sh
node --test --test-name-pattern="核心 AI 文件未带 d6-review.json 变更时阻断" scripts/ci/check-d6-sync.test.mjs
node --test --test-name-pattern="synced 确认且同批含 D6 时通过" scripts/ci/check-d6-sync.test.mjs
node --test --test-name-pattern="指纹变化使旧确认失效" scripts/ci/check-d6-sync.test.mjs
```

**局限（如实）**：① 未安装 Node.js 时 pre-commit **明确告警并跳过**这一道（提交不被拦）；② 本批**没有**新增 CI job，未启用 `core.hooksPath` 的克隆、图形客户端与 `--no-verify` 都不会执行；③ 门禁只证明"有人留了确认"，不证明"确认是对的"。改分类表或指纹算法必须升 `RULE_VERSION`（`scripts/ci/check-d6-sync.mjs`），否则旧确认不会失效。
