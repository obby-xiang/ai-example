# scripts/ 说明

> 本目录放**可执行的验证与门禁脚本**（不是文档目录）。三条内容：E2E 验证脚本、CI 上游桩、提交前敏感信息扫描钩子。
> 文档导航见 `docs/README.md`；如何启动前后端见仓库根 `README.md`。

| 路径 | 作用 |
| --- | --- |
| `scripts/verify-e2e.ps1` | 端到端验证脚本（123 用例 = 既有 35 + 批① 桩模式 88，PowerShell）。断言对象是**已启动的后端**，脚本自身不启动任何服务 |
| `scripts/b23l1-dual-instance.ps1` | **双实例 harness**（B23L1 批①）：8081/8082 共享同一 Redis + 桩上游，脚本自己起/等/收（也可 `-NoStart` 接已就绪实例）。只交付、施工期不运行 |
| `scripts/ci/stub-upstream.mjs` | CI E2E 用的**上游替身**（OpenAI 兼容桩），让用例在零密钥、零外网下确定性通过 |
| `scripts/ci/stub-routes.json` | 桩的路由表（提示词 → 工具调用/表单）+ `usageShapes` + `faults.switches`（§4.6 桩开关清单的唯一取数点），与 `GenerativeFormRules` 白名单**事实源逐值对账** |
| `scripts/githooks/` | 提交前敏感信息扫描（DC-08 落地），详见 `scripts/githooks/README.md` |

---

## 1. `verify-e2e.ps1` —— E2E 验证脚本

### 1.1 前置条件（脚本不代管，缺项直接失败）

- **后端已在跑**，且满足：
  - AI key 经环境变量注入（`AI_API_KEY`，兼容 `DEEPSEEK_API_KEY` 兜底）；缺 key 时 AI 类用例判 FAIL；
  - **Redis 可用**（默认 `127.0.0.1:6379`）—— AI 会话记忆 / 确认门 / 会话锁都走它；
  - 建议启动参数 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`（TC11 断言 `JOB_PROGRESS` 事件流；`batch-size` 为默认 100 时小配置项不会跨分片边界）；
  - 日志级别 `com.example.configmgr=DEBUG`（TC10 与 Q8 的日志类断言依赖它）。
- **数据面安全**：脚本只创建/删除自己的 `S5B-*` / `AIE2E-*` 任务与 `E2E_TEMP` 定义；`CURRENCY` 演示数据在 TC13 被替换为 3 行，TC19 用全量导出件恢复为 5 行。
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

## 2. `scripts/ci/stub-upstream.mjs` —— CI 上游替身

### 2.1 用途边界（引用文件头口径，**不得扩大解释**）

- 本桩是**产品链路的替身，不是模型行为的替身**。CI 全绿只证明"桩给出的表单/工具调用被产品链路正确消费"，**不证明模型服从率**；
  桩下 `GFSKIP=0` 由构造保证（桩按路由表下发表单），属恒真式而非观测；
  GF6（入参闸门，桩模式启用）同口径：非法 schema 由 `--violate-form-type`（请求级）**构造保证**，故其 SKIP 分支在桩模式同样不可达（未产出即判 FAIL，不判 SKIP）；
- **真模型的 E2E 维持本地手动跑**（S5d 裁决 #3），不在 CI 内；
- 零依赖：只用 Node 内置模块（`--self-check` 会断言这一点）。

### 2.2 收敛契约（硬要求：后端工具循环无迭代上限，桩必须自证终止）

① 披露清单驱动 —— 目标工具必须出现在本请求 `tools[]` 里才允许发起调用；② 每个会话轮次的上游调用上限（`convergence.maxCallsPerTurn`，默认 6）；③ 续轮只发文本（上下文已有 `role=tool` 消息时不再发起工具调用）；④ 解析失败不猜（一律以文本收尾）。`--self-check` 用"首轮→工具调用、续轮→文本"的模拟循环逐条证明上述四条成立。

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
| `multi-card=<n>` | 一条消息 n 个 `tool_calls`（id 互异） | H-04 / H-11 |
| `huge-frame=<bytes>` / `tool-loop=<n>` | 单帧字节数 / 连续 n 次工具调用 | S-13 / S-10 |
| `fail-503-once` | 同一轮首次 503、之后正常 | S-07 |
| `context-overflow` / `summary-500` | 越窗 400（措辞取 `ContextOverflow.MARKERS`）/ 摘要请求 500 | C-12 / C-10 |
| `gf-seven-types` / `gf-linkage` / `gf-expr-linkage` / `gf-deep-linkage` / `gf-options-from` / `gf-attachment` / `gf-longtext` | GF 新能力面的路由开关（`GF-*` 路由） | G-04…G-15 / X-10 |

`fe-tool-hang` / `sheet-big` / `preflight-fail` 三项**不是桩层开关**（`faults.switches` 里标 `layer=script/product`）：
挂起由用例的处理器停住、大表由用例自建任务、预检失败走产品既有语义 —— 桩只提供触发面，不伪造业务结果。
`--self-check` 段 ⑨ 断言"表与实现不漂移"（桩层 21 项逐项有实现）、标记解析、写出计划、段序、多卡 id 互异、
大帧字节数、越窗错误体命中产品 `MARKERS`（读事实源）、摘要识别式与 `RollingSummarizer` 同源。

### 2.4 记录契约（可归因性）

每请求落 `<dir>/req-NN.json`（请求原文）、`.tools.txt`（本请求实际披露的工具名清单）、`.head.txt`（模型可见消息摘要）、`.decision.json`（桩本轮的判定：路由 id / 工具名 / 理由）——这是"桩坏 / 产品坏"的区分依据。

### 2.5 与产品侧白名单的对账（已改为事实源逐值对账，T4-5 落地）

`stub-upstream.mjs` 的 13 组常量 + 6 项上限仍是 `GenerativeFormRules` 的**桩内副本**，但 `--self-check` 的自检③已改为**读产品事实源 `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java` 逐值对账**（13 组常量 + 6 项上限 + 原型三键；不新增端点、不新增生成步骤）——产品侧收紧或改动白名单而桩未同步时，**自检即红、e2e job 即红**，"副本自证仍绿"的漂移窗口已关闭。自检输出会打印事实源路径；对事实源**文本格式**（常量声明书写形态）有依赖，采用宽松解析、解析失败即红。

> 桩 / 前端 / 后端三处"原型键黑名单"另由后端用例 `GenerativeFormContractTest#forbiddenFieldKeysMatchAcrossEnds` 逐键锚定（同一批 T4-5 落地）。

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
