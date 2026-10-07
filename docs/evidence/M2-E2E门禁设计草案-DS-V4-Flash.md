# M2 E2E 进 CI 门禁 设计草案（DeepSeek V4 Flash）

> 角色：设计调研者（DeepSeek V4 Flash）。**零 git 写操作、未修改任何既有文件**；本文档为唯一新增文件，保持未提交（提交官统一入库）。
> 仓库：`<MAIN_V2>`（= `<REPO_ROOT>/ai-example-code/ai-example-main-v2`），分支 `main-v2`。任务书给定的基线为 `3f0283e`；**本棒执行期间仓库 HEAD 已推进到 `123744d`**（另一棒次的 `docs(方案)` 提交），且工作区存在另一棒次在飞的后端改动（`GenerativeFormRules` 及其用例，新增"字段键原型污染黑名单"）——本草案所有代码事实取自**读到的当前工作区内容**，与该并发改动不冲突（影响见 §2.3 末行）。
> 日期：2026-10-07。范围：为「E2E 进 CI 门禁（路线 a：`services:redis` + 桩上游）」产出设计草案，供指挥官裁决；本棒不落地任何 workflow / 脚本 / 桩代码。
> 证据等级：**【实测】**＝本棒读到的文件原文或已入库/已落盘的历史证据；**【推断】**＝由实测事实推出的结论，未在本棒运行验证；**【假设】**＝尚无证据、需施工棒验证的前提。
> 脱敏：仓库内绝对路径一律 `<MAIN_V2>`；仓库外历史证据目录用 `<EV>` / `<TMP>`；CI 工作目录用 `<WS>`；密钥一律 `***`；不出现用户名、主机名、内网地址与盘符样式字面量。

---

## 0. 结论速览

| 项 | 内容 |
|---|---|
| 三选项一句话 | **a1**（真 key 进门禁）＝零桩开发但把门的稳定性与密钥面交给真实模型，不可作阻塞门禁；**a2**（桩上游）＝零密钥、确定性、可离线构造边界输入，代价是必须把桩写到"多轮工具调用 + 终帧 + 收敛"的深度，且 CI 全绿不再证明模型服从率；**a3**（CI 只跑非 AI 子集）＝改动最小，但**非 AI 用例实为 17 而非 22**（见 §2.5），且实质放弃 S5d 裁决 #3「AI 用例 CI 内走桩」。 |
| 推荐 | **a2 全量**：CI 内跑满 27 用例（22 既有 + GF1–GF5），后端指向本机桩上游、`AI_API_KEY` 填**固定占位串**（零真实密钥）；真 key 跑法维持本地/手动（与 S5d 裁决 #3 同向）。 |
| 核心理由 | 本仓历史证据已证「预制 SSE 的 Node 桩可驱动本产品完整工具循环与终帧/usage 解析」（§2.4【实测】）；GF 用例的关键断言全部落在**后端产出**（帧契约/挂起/pending/HTTP）或**桩可完全控制的 schema 与文本**上，断言粒度**允许预制**（§3.2 逐条对账）；换来的是 CI 里 **GFSKIP=0 由构造保证**、无外网依赖、无密钥面。 |
| 最大风险 | **桩缺陷会以"产品缺陷"口径报红**：脚本把「已 `tool_start(generative_form)` 但未下发 `frontend_tool_request` 且无 `REJECTED_ARGUMENTS`」明判为产品缺陷 FAIL（§2.2），桩的 schema 违规会落到这条分支上，造成**误归因**；必须靠桩自检 + 请求 dump 归档把"桩坏/产品坏"分开（§9 R1）。 |
| 另一处必须明示 | **GFSKIP=0 的语义迁移**：在桩下 GF1–GF4「模型未触发/漂移」分支不可达，硬底线**恒成立**而非"被测得成立"；是否接受该口径、以及真模型服从率的证据线归谁，需裁决（§5、§9【待裁决】）。 |
| 文档路径 | `<MAIN_V2>/docs/evidence/M2-E2E门禁设计草案-DS-V4-Flash.md`（未提交） |

---

## 1. 背景与既往裁决

### 1.1 任务背景

- **M2 排期 T1-2** 已定方向：E2E 进门禁走**路线 a（`services:redis` + 桩上游）**。
- 现有门禁（`.github/workflows/ci.yml`）只含**后端编译+单测**与**前端类型检查+构建**两个 job，E2E 明确排除在外（该文件第 4 行注释原文：「E2E（`scripts/verify-e2e.ps1`，依赖 Memurai 与真实 AI key）不在本门禁内，属后续项。」）【实测】。
- E2E 脚本现有 **27 用例**：TC1–TC22（22 例）+ GF1–GF5（生成式表单 5 例），另 Q8 两项单列计数、GF6 默认关闭；最近一轮基线为 `PASS=27 FAIL=0 GFSKIP=0`（退出码 0），**耗时 85–91 秒**【实测：`docs/evidence/M1-E2E复跑确认-DS-V4-Flash.md` §0/§3.1、`docs/evidence/GFc-端到端执行验证.md` §10.2】。

### 1.2 核心设计难题

**CI 环境没有 `AI_API_KEY`**，而 GF1–GF5 要求"模型主动发起 `generative_form` 工具调用"（脚本用提示词诱导，见 §2.2）；既有 TC15/TC16/TC17/TC18/TC22 同样依赖模型主动调用 `list_config_defs` / `start_export` / `start_publish` / 前端通道工具。即：**AI 相关用例共 10 个**（5 既有 + 5 GF），它们的通过与否都以内"模型愿不愿意按提示词调工具"为起点。

### 1.3 既往裁决原文（逐字摘录，来源 `docs/evidence/S5d-CI门禁与缺陷注入验证.md`）

裁决表（文末「裁决结论（指挥官 K3，2026-10-07）」）：

| # | 事项 | 裁决（原文） |
|---|------|------|
| 1 | `actions/*` 升 v5 清弃用告警 | 采纳，列入 M1 收尾棒-B 附带项 |
| 2 | 原始 CI 日志必须归档 | **不采纳**——三源交叉证据已满足标准，不为此配 gh/PAT |
| 3 | E2E 进门禁路线 | **选 (a)：`services:redis`+桩上游**，AI 用例 CI 内走桩、真实模型 E2E 保持本地手动；排期 M2，不阻塞 M1 出口 |
| 4 | `pull_request` 触发未验证 | 接受现状，记为已知未验证项，首个真实 PR 时顺带确认 |

同文件 §8.1 给出的路线清单（原文摘要）：(a) `services: redis` 容器 + 桩上游替代真实 AI（可参照既有 `stub-upstream.mjs` 手法），**跑在 ubuntu 上**；(b) 自托管 runner（带 Memurai/Redis 与 key 的环境变量注入）；(c) 单独的 e2e job，只在 `main-v2` 或定时（nightly）触发。

**由此确定的三条边界**（本草案不得越线）：

1. 路线 **(a)** 是既定选择，本草案的任务是把它设计清楚，不是重新比较路线；
2. 桩上游是**既定手段**（裁决 #3 明文），本草案要回答的是"桩要模拟到多深、能否预制到脚本断言粒度"；
3. 真实模型的 E2E **维持本地手动**（裁决 #3 原话），故 a1 在门禁内属**被否决**项；但"要不要额外加一条 nightly 真 key 线"仍是开放问题（§9【待裁决】#1）。

### 1.4 本草案要回答的问题清单（任务书）

① CI 内 27 用例全跑还是子集（a1/a2/a3 利弊）；② 桩的模拟深度与 GF 帧序列可预制性；③ `GFSKIP=0` 硬底线如何满足/调整；④ redis service 与后端启动方式；⑤ 门禁分层（PR/push、超时、artifact 归档，含既往"日志归档不采纳"是否翻案）；⑥ 实施步骤与验收标准。

---

## 2. 现状调研（事实摘要）

### 2.1 现有 CI 门禁（`.github/workflows/ci.yml`，67 行）【实测】

| 项 | 取值 |
|---|---|
| 触发 | `push` → `main-v2`；`pull_request` → `main-v2` |
| 权限 | `permissions: contents: read` |
| job `backend` | `ubuntu-latest`、`timeout-minutes: 20`、`working-directory: backend`、`actions/checkout@v5` + `actions/setup-java@v5`（temurin 21、maven 缓存按 `backend/pom.xml`）、`mvn -B test` |
| job `frontend` | `ubuntu-latest`、`timeout-minutes: 20`、`working-directory: frontend`、`checkout@v5` + `setup-node@v5`（node 22、yarn 缓存按 `frontend/yarn.lock`）、`yarn install --frozen-lockfile` → `yarn typecheck` → `yarn build` |
| 基线耗时 | 后端 job 1m09s / 前端 job 1m06s（S5d 基线 run）【实测：S5d §3】 |
| 注释口径 | 后端 job 刻意不用 Redis、不用 AI key、不用 Windows 专属物（S5d §2 判据）【实测】 |

**含义**：新增 E2E job 必须自带宽口径依赖（Redis、AI 上游替身），不能寄望复用 `backend` job 的环境；`actions/*@v5` 已是仓库现行版本【实测】，新增步骤应与之一致。

### 2.2 `scripts/verify-e2e.ps1`（2186 行）【实测】

**(a) 参数与用法**（第 35–44 行）：

| 参数 | 说明 |
|---|---|
| `-Base` | 后端基址，默认 `http://127.0.0.1:18330` |
| `-BackendLog` | 后端 stdout 日志路径（`com.example.configmgr` DEBUG 级）；TC10 的日志断言与 Q8 两项需要它；**未提供时日志类断言判 FAIL（不静默跳过）** |
| `-EnableGf6` | 可选观察用例 GF6，默认关闭，不计主计数 |
| `-ArtifactDir` | GF 证据落盘目录（每用例 `gfc-<case>.sse.txt` / `gfc-<case>.http.txt`）；默认 `<TMP>/gfc-artifacts-<guid>` |

**脚本自身不启动后端、不加载前端产物**（头部注释「本脚本的断言依赖，脚本自身不启动后端」原文）【实测】——E2E job 只需 后端 jar + Redis + 桩上游，**无需构建前端、无需浏览器**。

**(b) 用例结构与口径**：TC1–TC22 计入 PASS/FAIL 与退出码（有失败即 `exit 1`）；Q8 两项单列；GF1–GF5 计入主计数但"模型未触发"记 **SKIP**（`GFSKIP=n` 单列、不计退出码）；**硬底线**：`$script:gfskip14 -ge 4`（GF1–GF4 全 SKIP）⇒ 整棒 FAIL（第 2185–2186 行）【实测】。

**(c) SKIP 机制与"产品缺陷不得 SKIP"的分界**（GF1 区块原文）：同一用例最多 3 次尝试；3 次皆"schema 漂移（缺 needKeys）"或"未触发表单"⇒ 抛 `GF-SKIP:*`；但若本轮**已有** `tool_start(generative_form)` 却**无** `frontend_tool_request`、也无 `REJECTED_ARGUMENTS` 结局帧 ⇒ 判 **产品缺陷 FAIL**，明文写「不得记 SKIP」【实测】。**这条正是桩缺陷误归因风险的落点**（§9 R1）。

**(d) AI 不可用时的行为**：脚本不判断 key；AI 端点在无 key 时由后端在开流前返回 503 `AI_UNAVAILABLE`（§2.3），脚本会把这些用例判 **FAIL**（不是 SKIP）【实测：脚本头部第 19 行注释「AI key 经环境变量注入…缺 key 时 AI 用例判 FAIL」】。

**(e) AI 相关用例清单（决定 a3 的真实规模）**：`/api/ai/*` 命中落在 `Invoke-AiTurn` 辅助函数（第 118/257/267/271 行）与以下用例块内 —— **TC15（1083）、TC16（1143）、TC17（1923）、TC18（2019）、TC22（2089）**；GF1–GF5 亦全部依赖模型轮次（GF5 例外：纯 HTTP，零模型轮次，第 1834 行原文）【实测】。

**(f) 跨平台耦合（决定 CI runner 选择）**：全脚本仅两处 `$env:` 读取，且都是 `$env:TEMP`（第 417 行 `<tmp>` 工作目录、第 422 行 `-ArtifactDir` 默认值）【实测：`grep -n '\$env:'` 仅 417/422 两行】。其余 Windows 风格残留为路径拼接中的反斜杠（如 `"$tmp\DOC_TYPE_e2e.xlsx"`）——在 Linux 上退化为"文件名里带反斜杠"，上传时的文件名另由参数给出（`'DOC_TYPE_ok.xlsx'`），不影响语义【推断】。`Add-Type -AssemblyName System.Net.Http / System.IO.Compression*` 在 pwsh 7（Linux）下的可用性未实测【假设】。

**(g) 断言粒度（可预制性对账的输入）**：GF1–GF4 的断言逐条为 —— 帧契约（`toolCallId` 非空、`timeoutSeconds > 0`、`expiresAt` 为数字）、表单 schema 形状（`args.form.scenario` 精确、`needKeys` 齐备、字段类型落在六型白名单）、回灌 HTTP 语义（200 + `status=FRONTEND_RESULT` + `accepted=true` + `executed=false`）、`pending` 终态与 `resultText` **精确相等**、结局帧恰 1 条 `ok=true`、`done` 恰 1 条、`error` 0 条、**值回显走会话记忆的最终助手文本**（`Gf-Assert-Echo`：逐字段校验 `key` 与值字符串出现于该文本）、以及若干后端日志行断言【实测：脚本第 1360–1425、1459–1543 行】。**脚本对模型措辞、回答内容、delta 文本均不作判定**（delta 自 GF-C 裁决 #1 起降级为纯 INFO 弱旁证）【实测】。

### 2.3 后端 AI 客户端与"可桩化面"【实测，除标注外】

| 面 | 事实（含出处） |
|---|---|
| 密钥 | `spring.ai.openai.api-key: ${AI_API_KEY:${DEEPSEEK_API_KEY:}}`（`backend/src/main/resources/application.yml:80`） |
| **可用性判据** | `AiAvailability.isAvailable()` **只看 key 是否非空白**；非空白即"可用"，不看网络、不看 base-url、不看 key 真假（`ai/config/AiAvailability.java`）⇒ **CI 填任意占位串即可让 AI 端点进入真实链路** |
| 无 key 行为 | 模型侧 bean 由 `AiKeyPresentCondition` 条件化装配（`StringUtils.hasText(...)`）；无 key 时 `/api/ai/chat`、`/resume`、`/health` 在**开流之前**返回 503 `AI_UNAVAILABLE`；历史读与确认/回灌写不依赖 key |
| **重定向点** | `spring.ai.openai.base-url: ${AI_BASE_URL:https://api.deepseek.com}`（同文件:81）⇒ **`AI_BASE_URL` 环境变量即可把上游整体指向本机桩** |
| 路径 | `spring.ai.openai.chat.completions-path: /chat/completions`（无 `/v1` 前缀，注释说明 DeepSeek 口径）⇒ 桩需接受 `POST <base-url>/chat/completions` |
| 模型/参数 | `model: ${AI_MODEL:deepseek-flash}`、`temperature: 0.2`、`max-tokens: 4096`、`stream-usage: true`（→ 请求体带 `stream_options.include_usage=true`） |
| Redis | `spring.data.redis.host/port` 默认 `127.0.0.1:6379`、超时 2s；Redis 不可用时 AI 端点由 `RedisAvailability` 前置探活直接 503 `AI_REDIS_UNAVAILABLE`（≤2s） |
| 挂起/确认 | `app.ai.hitl.timeout: 120s`；`app.ai.suspend.pool-size: 20`；`app.ai.resilience`（first-byte 30s / inter-event 90s / max-attempts 2 / total-budget 300s）；**硬规范②：正常收尾必须有终帧（finishReason/usage），缺失即判 `UPSTREAM_STREAM_INCOMPLETE` 并进重试判定** |
| 工具循环上限 | 基座的 `max-iterations` 配置**已删除**，注释原文「由官方循环的退出条件取代」（`ai/config/AiProperties.java`）⇒ **无迭代上限** ⇒ 桩必须自证收敛（§3.3、§9 R4） |
| 工具结果回填上限 | `app.tool-result.max-rows=200 / max-chars=8000`（DC-14）——超限只截断"给模型看的"那一份，前端帧与台账不截断；**回灌值 JSON 远小于上限，故桩在第二轮能读到完整 JSON** |
| 生成式表单白名单 | `scenario ∈ {FILTER, CLARIFY}`；字段 ≤20；六型 `text/number/boolean/date/enum/multi_select`；`options` 仅对象形态；`placeholder` 仅 text/number/date；form 序列化 ≤8000 字符（`ai/form/GenerativeFormRules.java`） |
| 生成式表单白名单（在读的并发改动） | 同文件工作区版本新增**字段键黑名单** `__proto__ / constructor / prototype`（原型污染键，与前端口径一致）⇒ **桩的表单模板不得使用这三个键名**（§4.4 模板用的 `keyword/minRows/effectiveDate/amount/scope/exportScope/includeInactive` 均不受影响）【实测：`git diff` 工作区改动】 |
| 启动物 | `backend/pom.xml` `finalName = config-mgr` + `spring-boot-starter-parent 3.5.14` ⇒ `mvn package` 产出 `backend/target/config-mgr.jar`（与 `README.md` 的 `java -jar target\config-mgr.jar` 一致） |
| 种子数据 | `seed/DataSeedRunner`（`regionRepository.count() > 0` 则跳过）+ `seed/GlmSeedRunner`（`ApplicationReadyEvent` → `seedIfAbsent`）⇒ **空库启动即自动生成 15 个配置定义与演示数据**（TC1 断言依赖此） |

### 2.4 桩上游参照（仓库外只读）：`<EV>/stub-upstream.mjs`（169 行）【实测】

历史 DC-14 实验留下的 Node 桩，两种形态：`--mode proxy`（转发真实上游并**记录每次请求原文**）与 `--mode scenario`（**不调模型，按预设脚本返回 OpenAI ChatCompletionChunk 形态的 SSE**）。关键能力与实证：

| 项 | 事实 |
|---|---|
| SSE 帧构造 | `sseChunk(delta, finishReason)` 产出标准 `chat.completion.chunk`（含 `id/object/created/model/choices[].{delta,finish_reason}`）；工具调用轮 = `{role:'assistant'}` → `{tool_calls:[{index,id,type,function:{name,arguments}}]}` → `{}`（`finish_reason='tool_calls'`）→ `data: [DONE]`；文本轮 = 分片 `content` delta → `finish_reason='stop'` → **`usage` 帧（含 `prompt_tokens_details.cached_tokens` / `completion_tokens_details.reasoning_tokens`）** → `[DONE]` |
| 请求侧记录 | 每请求落 `req-NN-*.json`（原文）、`.head.txt`（消息摘要）、`.tools.txt`（**本请求实际披露的工具名清单**）——即可用于事后归因，也可作为桩路由的判据来源 |
| **多轮实证** | `<EV>/runB/req-02-scenario.json` 中，第二个上游请求的消息为 `system / user / assistant(tool_calls, arguments="{\"taskId\":193}") / tool(tool_call_id, content=工具结果文本 129 字符)`；`<EV>/runA/frames-A1.sse` 显示后端据此产出完整帧序列 `start → message_start → delta×2 → message_end → done`，且 `done.usage` 被正确解析（`promptTokens/completionTokens/totalTokens/cachedTokens/reasoningTokens`）、`terminalSignal=finishReason=STOP` |
| 结论 | **【实测】预制 SSE 的 Node 桩足以驱动本产品的完整工具循环、挂起机制与终帧/usage 解析**——即"桩上游"不是纸面构想，本仓历史上已跑通同构实验（虽非同一棒次的代码，但同一产品形态与同一 Spring AI 版本谱系）【推断：版本谱系一致性】 |

补充一条来自同批实验的**形状细节**：`tool` 消息的 `content` 是被 JSON 序列化过的**字符串**（形如 `"\"- CURRENCY（货币字典）…\""`），且在实验中被截断过（40/50 字符场景留下 `…[结果已截断：原 N 行 / M 字符，仅前 K 字符…]` 尾注）【实测：`<EV>/raw/upstream-req-chars50.json`】。**这直接决定桩的"回显"实现方式**：不要假设 tool 内容是可直接 `JSON.parse` 的对象，应先按 JSON 字符串解一层、必要时用括号配对提取首个 `{...}`。

### 2.5 一处必须纠正的前提：**"22 个非 AI 用例"实际是 17 个**【实测】

任务书 a3 的表述为"CI 只跑 22 非 AI 用例"。逐用例检读脚本后的事实是：

- 22 例中 **5 例依赖真实模型**：TC15（流式/工具可见/渐进披露，需 `list_config_defs` 被调用）、TC16（确认门放行 + 前端工具挂起，需 `start_export` 与工作区动作工具）、TC17（HITL 拒绝/确认，需 `start_publish`）、TC18（会话隔离/409 串行化/取消释放，需一次挂起在确认门的轮次）、TC22（历史恢复含工具卡片）；
- 因此 **非 AI 用例 = 17 个**（TC1–TC14、TC19–TC21），**AI 相关 10 个**（上述 5 例 + GF1–GF5），与 `docs/evidence/M1-E2E复跑确认-DS-V4-Flash.md` §2.4 的"AI 相关 10 用例"清单**互相印证**【实测：两处独立读数一致】；
- 直接推论：a3 的真实覆盖面是 **17/22 + Q8 两项**，且它**必然需要给脚本加"跳过 AI 用例"开关**（现脚本没有该能力，GF/TC 都是无条件执行），否则无 key 时 TC15–TC18/TC22 判 FAIL、GF1–GF4 全 SKIP 触发硬底线 ⇒ 整棒 FAIL。

---

## 3. 三选项利弊表

### 3.1 主表

| 维度 | **a1** repo secret 注入真 key，跑真实 AI | **a2** 桩上游（含预制 tool_calls） | **a3** CI 只跑非 AI 子集（实为 17 例） |
|---|---|---|---|
| 密钥面 | **有**：公开仓 + Actions secret。同仓分支 `push` 触发的 workflow **会**拿到 secret，贡献者代码可读取；fork PR 拿不到 secret ⇒ 同一 workflow 出现两种行为分支 | **零**：CI 内不存在真实密钥。`AI_API_KEY` 填**固定占位串**即可让端点进入真实链路（§2.3 可用性只判非空），桩不校验 `Authorization` | 零（不跑 AI 用例） |
| 确定性 | **低**：GF1–GF4 的起点是"模型是否按提示词调工具"；GF-C 首轮就出现过 schema 漂移与值集合波动（`minRows`/`effectiveDate` 被标非必填）【实测：GFc §10.1 R2 由来】 | **高**：桩按路由表吐固定 schema/文本，SKIP 分支不可达 | 高（但不覆盖 AI 面） |
| 外网依赖 | 有（直连 `api.deepseek.com`；超时/限流/抖动不可控） | **无**（全本地回环） | 无 |
| 成本 | 每个 PR 都真实调用模型（10 个 AI 用例 × 多次轮次）；GF 用例含最多 3 次重试 | 0 | 0 |
| 覆盖 | 27/27（含真实模型行为） | 27/27（产品链路全覆盖；模型行为面覆盖为 0） | 17/22 用例 + Q8；**13 项 AI 侧断言零覆盖** |
| 改动量 | 最小（加 secret 与 env 注入即可） | **大**：桩本体 + 路由表 + 收敛契约 + 自检；另有脚本跨平台最小补丁 | 中：脚本需加跳过开关（改既有脚本）+ 口径调整 |
| 与既往裁决 | **与 S5d 裁决 #3 相反**（该裁决明文"真实模型 E2E 保持本地手动"） | **一致**（裁决 #3 指名路线） | **实质推翻**裁决 #3 的"Ai 用例 CI 内走桩" |
| 门禁质量 | 假红风险高（模型抖动 → 阻塞 PR）⇒ 往往被迫改成非阻塞，门禁虚化 | 假红风险低；**新增风险是"桩坏误报为产品坏"**（§9 R1） | 无假红；但对 AI 侧回归（如本次 M1 的生成式表单链路）**完全失明** |
| 失败可归因 | 需要拉取上游原始日志（CI 内不易） | 桩可把每个请求 dump 落 artifact（**天然可归因**） | —— |

### 3.2 关键子问题：**桩要模拟到什么深度？GF1–GF5 的 SSE 帧序列能否预制？脚本断言粒度允许吗？**

**结论：可以预制，且断言粒度允许**。逐条对账（断言对象 → 由谁产出 → 预制可行性）：

| 断言（用例） | 断言对象 | 产出方 | 预制可行性 |
|---|---|---|---|
| GF1-A1 帧契约：`toolCallId` 非空 / `timeoutSeconds > 0` / `expiresAt` 数字 | 后端挂起机制 | 后端 | 与桩无关，仅要求桩的 `tool_calls.id` 非空且工具名精确 |
| GF1-A2 schema 形状：`args.form.scenario` 精确、`needKeys` 齐备、字段类型落六型白名单 | **桩的 `arguments` 字符串** | **桩完全控制** | **可预制**：模板化 `form`，且需通过后端白名单闸（`GenerativeFormRules`） |
| GF1-A3 回灌 HTTP：200 + `FRONTEND_RESULT` + `accepted=true` + `executed=false` | 后端 | 后端 | 与桩无关 |
| GF1-A4 `pending` 终态 + `resultText` **精确相等** | 后端 | 后端 | 与桩无关 |
| GF1-A5 结局帧恰 1 条 `ok=true`；`done` 恰 1；`error` 0 | 后端 | 后端（依赖桩给**合法终帧**） | **可预制**，但有硬约束：文本轮必须给 `finish_reason` 与 `usage`，否则命中 `UPSTREAM_STREAM_INCOMPLETE`（§2.3 硬规范②） |
| GF1-A6 值回显：会话记忆最终助手文本含 `key` 与值 | **桩的文本轮内容** | **桩控制** | **可预制**：从本请求最后一条 `role=tool` 的内容里解出回灌 JSON，逐行输出 `key=值`（比硬编码更稳，且自动适配脚本"按实际 schema 合成值"的规则） |
| GF1-A7 后端日志：披露清单含 `generative_form` | 后端 | 后端 | 与桩无关 |
| GF3-A7 "模型收尾 delta" | 桩的文本轮 | 桩 | 可选（脚本明文"收集不到不 FAIL"） |
| TC15：`tool_start(list_config_defs)` 且 `delta ≥ 1`、`done ≥ 1` | 桩的工具名 + 文本轮 | 桩 | 可预制（工具名必须精确） |
| TC16：`confirm_request.name == start_export`；`frontend_tool_request.name` 落工作区动作表 | 桩的工具名与参数 | 桩 | 可预制；**参数须通过工具 JSON Schema**（`taskId: Long`） |
| TC17/TC18：拒绝/确认两结局、409 重复决策、真实发布、`SESSION_BUSY` 携 `runId/reattach` | 后端（闸门/锁/作业） | 后端 | 可预制：**挂在确认门上的挂起本身**就维持了会话占用，`409` 不依赖"模型慢"这一时间窗【推断：由 TC18 读法推出——脚本停在 `confirm_request` 后即发第二轮请求】 |
| TC22：历史含 `toolCalls` | 后端持久化 | 后端 | 可预制（工具卡由后端写） |
| GF/TC 的 SKIP 分支（模型未触发 / schema 漂移） | **模型行为** | —— | **在桩下不可达** ⇒ `GFSKIP=0` 恒成立（§5 语义迁移） |
| GF6（可选，默认关） | 需桩**故意**给非法 schema（`type: html`） | 桩 | 可预制（做成 opt-in 的"违规模式"） |

**桩需要模拟的深度（明确清单）**：

1. **端点面**：接受 `POST {AI_BASE_URL}/chat/completions`（无 `/v1`），返回 `text/event-stream`；对任意路径的其他请求可回 200 空体（用于探活）。
2. **请求面**：解析 `model / stream / stream_options.include_usage / temperature / max_tokens / messages[] / tools[]`；能识别 `role ∈ {system,user,assistant(tool_calls),tool}`；能读 `tools[].function.name`（**披露清单 = 路由的合法候选集**）；能从 `user` 消息文本提取场景关键词与 `taskId`。
3. **响应面（帧序列，全部可预制）**：
   - 文本轮：`{role:assistant, content:''}` → 若需长文本再分片 `{content:"..."}` × N → `{}`（`finish_reason='stop'`）→ `usage` 帧 → `[DONE]`；
   - 工具轮：`{role:assistant, content:''}` → `{tool_calls:[{index, id, type:'function', function:{name, arguments:<JSON 字符串>}}]}` → `{}`（`finish_reason='tool_calls'`）→ `[DONE]`；
   - `usage` 帧字段沿用参照实现（`prompt/completion/total` + `prompt_tokens_details.cached_tokens` + `completion_tokens_details.reasoning_tokens`）【实测：`<EV>/runA/frames-A1.sse` 的 `done.usage`】。
4. **收敛契约（本草案的强制设计项）**：因后端**无迭代上限**（§2.3），桩必须自证收敛 —— ① 仅当"目标工具出现在本请求 `tools[]` 且 `messages` 中尚无该 `tool_call_id` 的结果"时才发起工具调用；② 每个会话/轮次设全局上限（建议 6 次上游调用），越界即只能发文本；③ 任何解析失败都不许静默发工具调用，改为文本收尾（宁可让用例 FAIL，也不制造请求风暴）。
5. **记录契约**：每请求落 `<dir>/req-NN.json`（原文）+ `.tools.txt`（披露清单）+ `.head.txt`（消息摘要），供 CI artifact 归因。这同时是"桩 vs 产品"责任划分的唯一客观依据。
6. **不模拟**：真实模型的措辞/推理、上游错误码与限流、多模态——除非某个用例**专门**要求构造错误路径（可用 `--fail-mode` 开关注入，供缺陷注入验证使用）。

### 3.3 为什么"桩的收敛契约"是硬要求（而非优化项）

后端无迭代上限（§2.3），而脚本的 GF 用例在"模型一直不理解"时会读满自己的预算；桩若陷入"发工具调用 → 后端回 `SCOPE_NOT_DISCLOSED` 工具结果 → 桩再发同一工具调用"的循环，将以**每轮一次上游请求**的速度空转，直到 `total-budget = 300s` 或 CI job 超时。参照实验里已实测到该错误路径的形状：`tool` 消息内容为 `SCOPE_NOT_DISCLOSED：工具 start_import 未执行 —— 它不在当前工作…`（**桩调用未披露工具时后端不中断轮次，而是把拒绝作为工具结果回灌**）【实测：`<EV>/runB/req-02-scenario.json`】。因此：**路由必须由 `tools[]` 披露清单驱动**，而不是由提示词文本单方面驱动。

---

## 4. 推荐方案与理由

### 4.1 推荐（一句话）

**采 a2 并跑满 27 用例**：E2E 作为**第三个 job**（`ubuntu-latest`、`services: redis:7`、`timeout-minutes: 25`）随 `push → main-v2` 与 `pull_request → main-v2` 触发；后端以**打包 jar** 启动、`AI_BASE_URL` 指向本机桩上游、`AI_API_KEY` 填**固定占位串**；真 key 的真实模型跑法**维持本地手动**（S5d 裁决 #3）。

### 4.2 理由（按权重排序）

1. **零密钥面是硬收益，不是偏好**：`AiAvailability` 只判 key 非空（§2.3），所以"让 AI 链路活着"与"持有真 key"是**两件事**——CI 只需一个占位串，公开仓库不必承担 secret 面，fork PR 与同仓分支行为一致（a1 无法做到）。
2. **确定性直接决定门禁可用性**：GF1–GF4 的 SKIP 源头是模型行为（§2.2c），桩使其**不可达**；门禁的假红率是"能不能当阻塞门禁"的唯一判据。
3. **断言粒度已经适配**：§3.2 逐条对账显示，所有 GF 断言的判定对象要么在后端、要么在桩可完全控制的 schema/文本；**没有一条断言要求"真实模型的自由回答"**。
4. **历史证据支持可落地性**：预制 SSE 驱动本产品工具循环 + 终帧/usage 解析已被同仓历史实验实测（§2.4），不是纸面推演。
5. **可归因性优于 a1**：桩把每个上游请求 dump 到 artifact（S5d 裁决 #2 之所以放弃日志归档，是因为**匿名取 CI 原始日志需要 admin 权限**——那是"取日志"的障碍；而 `actions/upload-artifact` 用的是运行期令牌，**不构成同一障碍**，见 §6.4）。
6. **成本与耗时可控**：真实模型下 27 用例实测 85–91 秒【实测】；桩只会更快（无网络往返）【推断】。E2E job 的瓶颈在打包与启动，不在用例本身。

### 4.3 GFSKIP 处置（问题 ②：硬底线在所选方案下如何满足/调整）

**先给事实**：`GFSKIP=0` 这条硬底线（GF1–GF4 全 SKIP ⇒ 整棒 FAIL，裁决 #1）在 a2 下的处境是——

| | 真实模型（现状/本地） | 桩（CI） |
|---|---|---|
| GF1–GF4 是否触发表单 | 由模型服从率决定（实测多轮 4/4 首轮即触发） | **由构造保证**（桩按路由吐 `generative_form`） |
| SKIP 分支 | 可达（3 次漂移/未触发） | **不可达** |
| `GFSKIP=0` 的含义 | "模型在这个环境里听话了"（一次真实观测） | **"桩路由与产品链路都没坏"**（恒真式，非观测） |
| 硬底线是否会被误伤 | 会（模型不听话即整棒 FAIL） | 不会 |

**建议（分三层，第二/三层需裁决）**：

1. **代码不动**：不修改脚本的 SKIP 记账与硬底线判定行（`gfskip14 >= 4 → exit 1`）。理由：CI 中若真出现 GF SKIP，说明桩路由或产品链路已异常，判 FAIL 是**正确**行为；保留该行零成本。
2. **口径补注（建议采纳，属文档层面而非裁决推翻）**：在 E2E 证据文档与 CI job 说明中明确写清 —— *CI 的 `GFSKIP=0` 只证明"桩给出的表单被产品链路正确消费"，不证明"模型服从率"*。这**不修改**裁决 #1 的判定，只是标注其在 CI 环境下的射程。
3. **补偿证据线（【待裁决】）**：若要保留"模型服从率"这条证据线，需要一条真 key 的跑法承担 —— 选项：本地手动（现状，裁决 #3 已定）、nightly 定时 job（需 key 注入通道与成本授权）、或发布前人工跑一次。**本草案不擅自决定，列 §9【待裁决】#1。**

> 反过来说：若指挥官认为"CI 全绿不再证明模型服从率"不可接受，则应当**回到 a1 或 a3**——这不是可以两全的取舍，故必须显式裁决。

### 4.4 逐用例桩路由（施工输入）【设计】

| 用例 | 桩需发出的工具调用 | 触发识别（输入） | 备注 |
|---|---|---|---|
| TC15 | `list_config_defs`（`level` 省略） | `user` 文本含"配置定义"，且 `tools[]` 含该工具 | 之后文本收尾；`delta ≥ 1` 由文本轮满足 |
| TC16a | `start_export{taskId}` | `user` 文本含 `start_export` 且带任务号 | DANGER ⇒ 后端挂起确认门，脚本放行为 `approved=true` |
| TC16b | `navigate_to{page:'export',taskId}` 或 `select_definitions{codes:['CURRENCY']}` | `user` 文本含"导出向导/勾选" | 前端通道 ⇒ 后端不执行、发 `frontend_tool_request` 等回灌 |
| TC17 | `start_publish{taskId}` | `user` 文本含 `start_publish` | 拒绝路径后脚本会**取消**该轮；确认路径换新会话再来一轮 ⇒ 桩每轮独立、无状态依赖 |
| TC18 | `start_publish{taskId}`（**不主动收尾**） | 同上 | 挂起即维持会话占用 ⇒ `409 SESSION_BUSY`；取消后桩的下一次请求（tool 内容为取消语义）以文本收尾释放会话 |
| TC22 | 无新轮次（复用 TC15 会话） | —— | 断言依赖后端持久化的工具卡 |
| GF1 | `generative_form{form:{scenario:'FILTER', fields:[…union 模板…]}}` | `user` 文本含 `scenario=FILTER` | **union 模板**：`keyword(text)` + `minRows(number)` + `effectiveDate(date)` + `amount(number)` + `scope(enum[XN,HD])`，一次模板同时满足 GF1（需 keyword/minRows/effectiveDate/scope）、GF3（需 keyword）、GF4（需 keyword/amount/scope）【实测：needKeys 逐例读数】 |
| GF2 | `generative_form{form:{scenario:'CLARIFY', fields:[exportScope(enum ALL/SELECTED), includeInactive(boolean)]}}` | `user` 文本含 `scenario=CLARIFY` | 上下文为 `task:EXPORT/QUERY_COND`，须确认 `generative_form` 已被披露（后端侧） |
| GF3 | 同 GF1 模板 | 同 GF1 | 脚本随后回灌 `cancelled:true` |
| GF4 | 同 GF1 模板 | 同 GF1 | 脚本先回灌违规值（期望 400，后端闸门），再改值重发 |
| GF5 | **无模型轮次**（纯 HTTP） | —— | 复用 GF3/GF4 的 `runId/toolCallId` |
| 所有挂起后的续跑轮 | **文本轮**：从本请求最后一条 `role=tool` 的内容中解出回灌 JSON，逐行输出 `key=值` | 存在 `role=tool` 消息 | 满足 `Gf-Assert-Echo`；取消语义则输出一句收尾文本 |

> union 模板的 `required` 取值建议：`keyword` 标 `required:true`，其余**不写** `required` 或写 `false` 均可 —— 脚本的 `New-FormValues` 会跳过显式 `required:false` 的字段（GF-C 的 A6 假阴性根因即在此），而**回显由 tool 消息反解**，故两种取值下断言都稳【实测：脚本第 1261–1288 行 + GFc §10.1 R2】。

### 4.5 redis service 与后端启动方式（问题 ③）

| 选型 | 裁决建议 | 理由 |
|---|---|---|
| **Redis** | `services: redis:7`，`ports: ['6379:6379']`，带 `--health-cmd "redis-cli ping"` 健康检查 | 与 S5d 裁决 #3 一致；后端默认 `127.0.0.1:6379`（§2.3）与 Linux runner 上 service 容器的宿主端口映射天然对接【推断】；本机 Memurai 与其差异只在"服务端实现"，产品只用到 LIST/SET/EXPIRE（application.yml 注释原文），风险低【推断】 |
| **后端启动** | **打包 jar**：`mvn -B -DskipTests package` → `java -jar backend/target/config-mgr.jar …` | ① 与本地与证据文档的既有跑法**同源**（M1/GFc 的启动参数可原样搬：`--server.port`、`--spring.datasource.url`、`--app.job.batch-size=10`、`--app.job.demo-batch-delay-ms=150`）；② stdout 可**整条重定向**到 `-BackendLog` 指定文件，不被 Maven 输出污染（脚本的日志断言按行匹配）；③ 进程 PID 明确、收尾简单 |
| `mvn spring-boot:run` | 不采纳 | Maven 包装会往 stdout 混入 `[INFO]` 行（日志断言虽按模式匹配仍可工作，但噪声无收益）；且 SIGTERM 传播路径更长 |
| testcontainers | 不采纳 | E2E 是**进程外**的 API 层测试（脚本扮演前端），testcontainers 只能托管容器内的依赖，解决不了"jar + 桩 + Redis 三者编排 + 脚本时序"的问题；引入 Java 侧测试基建会与脚本形成两套重复的编排 |

---

## 5. 门禁工程化设计（问题 ④）

> 本节全部为**提案**，本棒未落地任何 workflow 改动。

### 5.1 job 结构（提案草案，示意 YAML）

```yaml
  e2e:
    name: E2E 全流程（27 用例 · 桩上游 + redis 服务容器）
    runs-on: ubuntu-latest
    timeout-minutes: 25
    needs: backend            # 后端单测红时不浪费 E2E 机时（可选，需裁决）
    services:
      redis:
        image: redis:7
        ports: ['6379:6379']
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 5s --health-timeout 3s --health-retries 12
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5          # temurin 21 + maven 缓存
      - run: mvn -B -DskipTests package      # 产出 target/config-mgr.jar
      - name: 启动桩上游
        run: nohup node <WS>/scripts/ci/stub-upstream.mjs --port 18399 --dir <WS>/e2e-artifacts/stub > <WS>/stub.log 2>&1 &
      - name: 启动后端（占位 key + 桩上游 + 独立工作目录）
        run: |
          mkdir -p <WS>/run && cd <WS>/run
          AI_API_KEY=ci-stub-placeholder AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub \
          nohup java -jar <WS>/backend/target/config-mgr.jar \
            --server.port=18330 \
            --spring.datasource.url="jdbc:h2:file:./data/ci_e2e_db;DB_CLOSE_DELAY=-1" \
            --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 \
            > <WS>/e2e-artifacts/boot.log 2>&1 &
      - name: 就绪等待（/api/ai/health 且 redis.available=true）
        run: …            # 轮询 ≤90s；失败打印 boot.log 尾部并 exit 1
      - name: 运行 E2E（27 用例）
        shell: pwsh
        working-directory: <WS>
        run: pwsh -NoProfile -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 \
               -BackendLog <WS>/e2e-artifacts/boot.log -ArtifactDir <WS>/e2e-artifacts/gfc
      - name: 归档证据（失败也归档）
        if: always()
        uses: actions/upload-artifact@v4      # 版本随仓库现行 major 对齐（施工前核对）
        with: { name: e2e-evidence, path: <WS>/e2e-artifacts, retention-days: 7 }
      - name: 收尾（杀进程）
        if: always()
        run: …                                # kill 后端与桩的 PID
```

### 5.2 关键设计点与依据

1. **密钥**：`AI_API_KEY=ci-stub-placeholder`（固定占位串，**非密钥**，可写进 workflow）；桩不校验 `Authorization`【实测：参照桩只在 proxy 模式才用 `process.env.AI_API_KEY` 转发】。→ CI 内**不存在任何 secret 引用**，公开仓无密钥面。
2. **就绪门**：先探 `/api/ai/health`，要求 `available=true` 且 `redis.available=true` 再跑脚本。依据：无 key 时该端点 503 `AI_UNAVAILABLE`、Redis 不可用时 503 `AI_REDIS_UNAVAILABLE`（§2.3）——**把环境故障与用例失败分开**，否则一次后端没起来会让 27 个用例全红、真因埋在输出里。
3. **工作目录**：jar 在 `<WS>/run` 下启动（`./data` 落在仓库外）——与既有跑法的隔离纪律一致【实测：M1 §1.1「工作目录设在仓库外，故 `./data` 全部落在仓库外」】。
4. **artifact 归档（回应"既往裁决 #2 要不要翻案"）**：**对象不同，不构成翻案**。S5d 裁决 #2 否决的是"归档 **CI 原始作业日志**"，理由是匿名取 `actions/jobs/{id}/logs` 返回 403（需 admin 权限），故不为此配置 `gh`/PAT【实测：S5d §4.1】。而 `-ArtifactDir` 产物（`gfc-*.sse.txt` / `gfc-*.http.txt`）+ 桩请求 dump + `boot.log` 是**运行期内生成在 runner 本地文件系统的文件**，用官方 `upload-artifact` 上传即可，**不需要仓库权限、不需要 PAT**。因此建议**采纳归档**（保留 7 天）——它是 a2 方案"可归因"的实现手段，与裁决 #2 不冲突。
5. **触发与分层**：
   - **PR 与 push 均触发**（与现有两 job 一致）：桩方案确定性足够支撑阻塞门禁，且 E2E 恰是"AI 侧回归"最需要拦的地方（当前两 job 对生成式表单链路零覆盖）。
   - **超时 25 分钟**：实测用例本体 85–91s、现有 job 1m06s/1m09s【实测】；打包 + 启动 + 用例 + 归档预计 3–6 分钟【推断】，25 分钟为 4× 余量。
   - 可选：`concurrency` 组按 PR 号取消过期运行，降低排队（不影响正确性）。
   - **不采纳**：把 E2E 放进 `backend` job 的同一 step 序列 —— 会拉长主门禁反馈链，且 Redis service 与 AI 环境是 E2E 专属依赖（S5d §2 的"后端 job 不依赖 Redis/key"判据应继续成立）。

### 5.3 跨平台落地的前提：`verify-e2e.ps1` 的最小补丁（**需授权**）

Linux runner 上 `$env:TEMP` 为空 ⇒ `Join-Path $env:TEMP …`（第 417/422 行）会抛错【实测：两处引用；后果为推断】。最小补丁建议（**不改任何断言、不改既有用例块**）：

```powershell
$tmpRoot = if ($env:TEMP) { $env:TEMP } else { [IO.Path]::GetTempPath() }
```

其余 Windows 风格残留（反斜杠拼接）在 Linux 上退化为"文件名含反斜杠"，需在 S5 试跑中**实测确认**（§9 R3）；若确认不可行，回退方案是 E2E 固定在 `windows-latest` 并自带 Redis 启动步骤 —— 但那会**放弃 `services:` 容器**（GitHub 的 service 容器仅 Linux runner 支持【推断】），与 S5d 裁决 #3 的字面路线不符，故列为回退而非首选。

---

## 6. 实施步骤（可派单粒度）

> 每步都可独立派单；步骤间以"本地复跑通过"为交接门。除 S5 外**不建议在 S1–S4 阶段开启 CI 阻塞**（半成品桩会让门禁红且归因困难）。

| 步 | 内容 | 交付物 | 验收（该步的通过判据） |
|---|---|---|---|
| **S1** | 桩骨架：HTTP 服务 + `/chat/completions` SSE + 请求 dump + 文本轮（固定语句）+ 就绪探针端点 | `scripts/ci/stub-upstream.mjs`（新文件） | 本机 `curl -X POST` 得到合法 SSE；后端配 `AI_BASE_URL` 指向它后，**TC15 首个文本轮**可过（先不求全绿） |
| **S2** | 桩路由第一批（生成式表单）：FILTER union 模板 + CLARIFY 模板 + 回灌轮"从 `role=tool` 反解 JSON 逐行输出 `key=值`" + 收敛契约（披露清单驱动、全局轮次上限） | 桩路由实现 | 本机（真 Redis + 桩）跑 E2E：**GF1–GF5 全 PASS、GFSKIP=0**；`gfc-GF*.http.txt` / `.sse.txt` 落盘完整 |
| **S3** | 桩路由第二批：`list_config_defs`（TC15）、`start_export` + 工作区动作工具（TC16）、TC22 | 桩路由实现 | 本机全量跑 E2E：`PASS=27 FAIL=0 GFSKIP=0`（若 TC17/TC18 未覆盖，则先达 `PASS=25`，逐例核对失败点仅在 TC17/TC18） |
| **S4** | 桩路由第三批：`start_publish`（TC17/TC18，含从提示词提取 `taskId` 与挂起保持策略） | 桩路由实现 | 本机全量 `PASS=27 FAIL=0 GFSKIP=0`，退出码 0；连跑 2 次结果一致（桩确定性验证） |
| **S5** | `verify-e2e.ps1` 跨平台最小补丁（`$env:TEMP` 兜底） | 脚本 2 行内改动 | 在 Linux（或 CI 试跑 job）上跑通同一套 27 用例；**断言与既有用例块零改动**（`git diff` hunk 只落在第 417/422 行附近） |
| **S6** | workflow 加 `e2e` job（services:redis + 打包 + 启动 + 就绪 + 跑 + 归档） | `.github/workflows/ci.yml` | 触发一次真实 run：E2E job 绿、耗时与归档产物符合 §7 验收标准 |
| **S7** | 门禁拦截力验证（沿 S5d 缺陷注入手法）：① 注入**产品**缺陷（如放宽 `GenerativeFormRules` 类型复核）② 注入**桩**缺陷（路由故意缺 `needKeys`） | 一次性分支 + 证据文档 | ① 红线落在对应用例且不越界；② 能凭 artifact 把"桩坏"与"产品坏"区分开（这是 §9 R1 的对策验证） |
| **S8** | 口径与文档落地：`GFSKIP` 语义补注、CI 覆盖表、跑法说明（含 CI 与本地口径差异） | 文档改动 | 文档与实际 job 配置逐项一致；若仓库有 `AGENTS.md` 需同步（本仓当前无） |

**排期依赖**：S1 → S2 → S3 → S4 串行（同一文件，逐批加路由）；S5 可与 S2–S4 并行（不同文件），但 S6 依赖 S4 + S5 同时完成。

---

## 7. 验收标准

**功能验收（CI 内）**

1. E2E job 结论 `success`，脚本输出 `PASS=27 FAIL=0`、`GFSKIP=0`、`Q8 项：PASS=2 FAIL=0`、`E2E_EXIT=0`（与本地口径一致）。
2. 后端 **零 ERROR**；`GET /api/ai/health` 回 `available=true`、`model=ci-stub`、`baseUrl=http://127.0.0.1:18399`【可机械核对的后端自报事实】。
3. 桩请求 dump 条数与该轮实际上游调用次数一致（每用例可数），GF 每例的 `gfc-<case>.http.txt` 条数与实际 POST 次数一一对应（GF1/GF2/GF3 = 1，GF4 = 2，GF5 = 2）。

**工程验收**

4. **零密钥**：仓库内与 workflow 内无 `secrets.*` 引用；CI 使用的 `AI_API_KEY` 为固定占位串；artifact 中不含任何真实密钥字符串（可 `grep` 复核）。
5. **仓库洁净**：E2E 运行不在仓库工作区留下 `data/`、上传件、日志（工作目录在仓库外）；job 结束后 `git status --porcelain` 为空。
6. **耗时**：E2E job 单次 ≤ 8 分钟（含打包与归档），`timeout-minutes: 25` 作为兜底。
7. **确定性**：同一 commit 连跑 2 次结论一致；不因模型抖动产生假红。
8. **可归因**：失败时 artifact 必含 `boot.log`、`stub/req-*.json`、`gfc-*` 三件套；仅凭 artifact 即可判定"桩坏 / 产品坏 / 环境坏"。
9. **无回归**：`backend`、`frontend` 两个既有 job 的行为与耗时不受影响（E2E 为独立 job；`needs` 只影响调度顺序）。
10. **拦截力（S7）**：产品侧注入的缺陷必被对应断言拦下；桩侧注入的缺陷**不**被误判为产品缺陷（或至少 artifact 足以纠正归因）。

---

## 8. 风险与未决问题

### 8.1 风险（含缓解）

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | **桩缺陷被脚本按"产品缺陷"口径报红**：脚本把「有 `tool_start(generative_form)`、无 `frontend_tool_request`、无 `REJECTED_ARGUMENTS`」判为产品缺陷 FAIL（§2.2c），桩的 schema 违规（缺 `needKeys`、类型越白名单）会落到这条分支 | 门禁红但归因错误，浪费定位时间；严重时误改产品代码 | ① 桩的 schema 模板与 `GenerativeFormRules` 逐字段对账；② 每请求 dump + artifact（§5.2.4）；③ S7 专门注入桩缺陷验证归因链路；④ 运营口径：CI 红时**先看 stub dump** 再动产品 |
| **R2** | **`GFSKIP=0` 的语义迁移**：CI 全绿不再证明模型服从率（§4.3） | 证据链出现**空档**：产品链路有门禁，模型行为无门禁 | 口径补注 + 真模型证据线归属裁决（§9【待裁决】#1）；不得静默把"恒真"当作"已测" |
| **R3** | **pwsh 跨平台**：`$env:TEMP` 两处（已知）＋ 未知的 Windows 耦合（压缩/路径/xlsx 上传） | E2E 在 ubuntu runner 上跑不起来，需回退 windows runner | S5 先做最小补丁 + 试跑；失败则回退 `windows-latest` + 自带 Redis 启动（代价：放弃 `services:` 容器） |
| **R4** | **工具循环无迭代上限**（§2.3）＋ 桩不收敛 | 请求风暴直到 `total-budget=300s` / job 超时，机时浪费且现象难读 | 强制收敛契约（§3.3）：披露清单驱动 + 每股全局轮次上限 + 解析失败即文本收尾 + job 超时兜底 |
| **R5** | **Spring AI 版本升级导致请求/响应形状漂移** | 桩静默失配 ⇒ 用例红/桩误触发 | 请求 dump 落 artifact；桩对"关键字段缺失/`tools[]` 为空"显式报错；升级棒次必须复跑 E2E（写入升级验收清单） |
| **R6** | **Redis 实现差异**（本机 Memurai vs `redis:7`） | 会话记忆/锁/确认门行为差异 | 产品只用 LIST/SET/EXPIRE（配置注释原文）【推断：风险低】；S6 首跑即验证；必要时补一条"Redis 语义冒烟"用例 |
| **R7** | **runner 镜像变更**：`ubuntu-latest` 自 2026-10-19 起迁移 Ubuntu 26（S5d 基线注解【实测】） | pwsh/Redis 镜像行为变化、时间点重叠 | E2E job 落地时间与镜像迁移错开，或落地后立即复跑一次基线 |
| **R8** | **PR 反馈时长**：主门禁从 ~1.1 分钟变为 ~4–6 分钟【推断】 | 开发体验 | 阻塞但仍保持单一 E2E job；必要时（裁决）改为 push 触发 + PR 上只跑非 AI 子集 |
| **R9** | 桩本身的维护面（路由随产品工具签名变化而腐化） | 长期维护成本 | 桩路由与 `AiTools` 的工具签名对账写入 S7/升级清单；桩保持"零依赖单文件"以减少维护面 |

### 8.2 【待裁决】清单

| # | 事项 | 选项/建议 | 影响面 |
|---|---|---|---|
| **1** | **真模型证据线归属**：CI 走桩后，"模型服从率/GFSKIP 语义"由谁承担？ | (i) 维持**本地手动**（现状，S5d 裁决 #3）；(ii) 增设 **nightly** 真 key job（需 key 注入通道 + 成本授权 + 密钥面重新评估）；(iii) 仅发布前人工跑一次。**建议 (i) 起步，(ii) 单列后续项** | 证据链完整性；与裁决 #3 的一致性 |
| **2** | **是否授权改动 `verify-e2e.ps1`**（`$env:TEMP` 兜底，2 行内） | 建议授权，并限定"只碰 417/422 行、断言与既有用例块零改动" | 决定 ubuntu runner 可行性（否则回退 windows-latest） |
| **3** | **E2E 触发面**：PR 阻塞 / 仅 push / nightly | 建议 **PR + push 均阻塞**（桩确定性支撑）；备选"PR 非阻塞 + push 阻塞" | 门禁严格度 vs 反馈时长（R8） |
| **4** | **artifact 归档与保留期** | 建议采纳 `upload-artifact`，保留 **7 天**（依据见 §5.2.4：与裁决 #2 的对象不同，不构成翻案） | 可归因性（R1 的缓解） |
| **5** | **桩的归属与命名** | 建议 `scripts/ci/stub-upstream.mjs` + `scripts/ci/stub-routes.json`（零运行时依赖、单文件）；是否要求桩自身带契约自检（`node --test` 或 `--self-check` 模式） | 仓库结构；维护面 |
| **6** | **CI 覆盖范围**：27 全量 vs 先 22（含 GF）+ 后 5（TC15–TC18/TC22）分阶段 | 建议**目标 27 全量**，实施按 S2→S3→S4 逐批（不引入"部分用例"开关）；若要求提前开门禁，则需授权给脚本加 `-ExcludeCases` 参数（新增能力，不改断言） | 派单节奏；门禁上线时间 |
| **7** | **`needs: backend` 是否加** | 建议加（后端单测红即不跑 E2E，省机时且语义清晰） | 调度与机时 |
| **8** | **桩的"违规模式"是否纳入 CI** | 建议默认关闭（对齐 GF6 的既有口径：`-EnableGf6` 默认关、不计主计数），仅在 S7 缺陷注入时开启 | 与既有裁决 #1/#5 的口径一致性 |

### 8.3 本草案未验证的事实（诚实标注）

- **【假设】** ubuntu runner 预装 pwsh 7 且 `Add-Type -AssemblyName System.Net.Http / System.IO.Compression*` 在该环境下可用；
- **【假设】** `services:` 容器的端口映射在 runner 上以 `127.0.0.1:6379` 暴露给**非容器**进程（后端以宿主进程运行）；
- **【推断】** GitHub 的 `services:` 容器**仅 Linux runner 支持**（决定"回退 windows-latest"意味着放弃 services 路线）；
- **【推断】** 桩在 CI 上的耗时与真实模型同量级或更快（源：本地真实模型 85–91s 实测）；
- **【推断】** TC18 的 `409 SESSION_BUSY` 由"挂起在确认门"维持（而非依赖模型响应慢）——依据是脚本在读到 `confirm_request` 后才发第二轮请求；**需 S3/S4 实测确认**；
- **【推断】** 桩的 `tool_calls` 单帧聚合（`index=0` 一次给全）在当前 Spring AI 版本下被正确拼接（源：历史实验实测通过，但与本棒代码非同一提交）。

---

## 裁决结论（指挥官 K3，2026-10-07）

1. 总体方案：采纳 a2（桩上游全量 27 用例，CI 零密钥）。a1 与 S5d 裁决 #3（真实模型 E2E 保持本地手动）冲突，维持否决；a3 前提不成立（22 例中 TC15/16/17/18/22 共 5 例依赖真实模型，非 AI 子集实为 17 例），否决。
2. 待裁决 #1（真模型证据线）：采纳 (i) 维持本地手动起步；(ii) nightly 真 key job 列入 M2 排期 T4 评估项（需业务方授权 key 成本与密钥面，届时单独决策）。
3. 待裁决 #2：授权改动 verify-e2e.ps1，限定 $env:TEMP 兜底（417/422 行，2 行内），断言与既有用例块零改动。
4. 待裁决 #3：采纳 PR + push 均阻塞（桩确定性支撑）；R8 反馈时长接受 4–6 分钟，恶化再议分层。
5. 待裁决 #4：采纳 upload-artifact 保留 7 天。与 S5d"CI 日志归档不采纳"的区分成立（彼时对象为运行日志常规归档，此为失败归因 artifact），不构成翻案。
6. 待裁决 #5：采纳桩归属 scripts/ci/stub-upstream.mjs + stub-routes.json，零运行时依赖单文件，自带 --self-check 契约自检。
7. 待裁决 #6：采纳目标 27 全量、按 S2→S3→S4 逐批实施，不引入部分用例开关（-ExcludeCases 不授权）。
8. 待裁决 #7：采纳 needs: backend。
9. 待裁决 #8：采纳违规模式默认关闭（对齐 GF6 既有口径），仅 S7 缺陷注入时开启。
10. 风险处置：R3 回退策略采纳（pwsh 最小补丁试跑失败则回退 windows-latest + 自带 Redis）；GFSKIP=0 语义迁移仅做口径补注（代码不动），不得静默把"恒真"当"已测"；§8.3 各假设项在 S5/S6 首跑验证，任一不成立按 R3 回退路径执行。
