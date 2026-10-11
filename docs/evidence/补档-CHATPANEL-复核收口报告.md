> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 复核收口报告 · 对话面板包（复核报告 F2 / F4 / F5 修 + F3/F6 登记）

- **收口对象**：`<MAIN_REPO>`（分支 `main`，HEAD=`03a6cbe`）工作树上的对话面板包（苞 A · R-100 / 苞 B · R-101）未提交改动
- **收口依据**：独立复核报告 `<SCRATCH>/chat-panel-pkg/《复核报告.md》`（结论 = 带条件 PASS，0×BLOCKER / 1×MAJOR / 5×MINOR / 4×观察）
- **收口范围**：该报告 **F2 / F4 / F5** 三条 MINOR 的代码修（F2 只改口径文案）+ **F3 / F6① / F6② / F8 / F9 / F10** 的台账登记 + **F4 附带用例**
- **未做**：F1（流程/归属，属指挥官裁决）、F3 字段改名（只补口径注释）、F6①/F6②/F8/F9/F10 的实现改动（按指令只登记）
- **纪律**：零 `git add/commit/push`；工作树上苞 A/苞 B + 台账暂缓登记的既有改动**零还原、零覆盖**，本棒的改动一律叠加其上
- **脱敏**：正文一律 `<>` 占位符（`<MAIN_REPO>` / `<SCRATCH>`），不含盘符、用户名、内网 IP、仓库名

---

## 1. 改动行级清单

行号为改动落盘后的**现态实读**行号。

### 1.1 后端 · F4 判存改单次 `SISMEMBER`

| # | 文件 | 位置 | 改动 |
|---|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java` | `:107-121` | **新增** `public boolean isConversationIndexed(String conversationId)`：一次 `SISMEMBER chat:mem:__ids__ <id>`（`Boolean.TRUE.equals(...)`，null 安全）→ 单点判存、无 `SMEMBERS`、无逐 id `EXISTS`、无惰性清理副作用。javadoc 写明与 `findConversationIds()` 的成本差异与其存在理由 |
| 2 | 同上 | `:35-36` | 类注「存储结构」同步：索引 SET 现在服务两个读取面（列全部 / 单点判存） |
| 3 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | `:585-586` | 判存调用点由 `!this.chatMemoryRepository.findConversationIds().contains(sessionId)` 改为 `!isConversationIndexed(sessionId)`；行内注释点明"只问这一个 id" |
| 4 | 同上 | `:596-619` | **新增**私有方法 `isConversationIndexed(String)`：`chatMemoryRepository instanceof RedisChatMemoryRepository` → 走单次 `SISMEMBER`；否则**回落** `findConversationIds().contains(...)`（SPI 通用契约面，测试桩 / 将来换实现）。javadoc 含三块：F4 成本与语义等价论证、回落口径、F3 时点口径（响应时点事实） |

**响应键语义不变的论证**（F4 要求「保持响应键语义不变」）

- 旧式 `findConversationIds().contains(id)` = "在索引里 **且** 数据键存在"（`SMEMBERS` 后逐 id `hasKey`，顺带清理过期成员）；新式 `SISMEMBER` = "在索引里"。
- 区别只可能出现在"索引里残留了数据键已过期的成员"这一形态；而本端点在上面刚执行 `chatMemory.clear(sessionId)`（= `DEL chat:mem:<id>` + `SREM chat:mem:__ids__`）⇒ 此刻成员仍存在**只可能**被在跑轮次收尾的 `convergeMemory`（`clear + add` → `saveAll`）重新写回，而那个 `add` **同时建数据键** ⇒ 两种写法在本调用点的判定**逐值一致**。
- 其余 8 个响应键（`sessionId` / `deleted` / `existed` / `memoryKey` / `runActive` / `activeRunId` / `storedBy` / `note`）**零改动**。
- 非 `RedisChatMemoryRepository` 实现（含既有 8 条用例的 `ChatMemoryRepository` 桩）走回落分支 ⇒ 既有断言零失效（实测 8/8 保持绿）。

### 1.2 后端 · 用例适配与新增（F4 附带）

| # | 文件 | 位置 | 改动 |
|---|---|---|---|
| 5 | `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java` | `:7-8`, `:21-22`, `:28` | 新增 import（`MessageJsonCodec` / `RedisChatMemoryRepository` / `SetOperations` / `StringRedisTemplate` / `Duration`） |
| 6 | 同上 | `:58-60` | 类注追加「复核 F4 追加两条」段（说明既有 6 条走回落分支） |
| 7 | 同上 | `:80-81` | 新增字段：`StringRedisTemplate` 桩（真仓储背后） |
| 8 | 同上 | `:210-235` | **新增用例 9** `indexedCheckIsSingleSismemberNotFullIndexScan`：真 `RedisChatMemoryRepository` 装配，`isMember→true` ⇒ `removedFromIndex=false`，且 `verify(isMember)` **恰好一次** + `verify(members, never())` + `verify(redis, never()).hasKey(...)`（把"不得 `SMEMBERS`、不得逐 id `EXISTS`"钉成断言） |
| 9 | 同上 | `:237-255` | **新增用例 10** `absentMemberReportsRemovedFromIndexTrueWithSingleRoundTrip`：`SISMEMBER` 回 null（无成员 / 未回话）⇒ `removedFromIndex=true`，同样只走那一次往返 |
| 10 | 同上 | `:256-279` | 新增 `controller(AiAvailability, ChatMemoryRepository)` 重载（原单参方法改为委托，**构造器签名与调用表零变**）+ `redisRepository(...)` 真仓储装配 + `setOperations()` 桩工厂 |
| 11 | 同上 | `:184` | 用例 8（`blankSessionIdIsBadRequest`）说明行追加 **F8 口径补注**：`%20` 空白段"走 HTTP 到不了"只在 MockMvc 下成立，生产容器可达并 400 |

用例数：**8 → 10**（既有 8 条断言零改动、零失效）。

### 1.3 前端 · F5 守卫 + F2 口径

| # | 文件 | 位置 | 改动 |
|---|---|---|---|
| 12 | `frontend/src/stores/ai.ts` | `:1147-1160` | **F5 修**：`catch` 路径写 `notice` 前加「仍是本轮」守卫 `const isCurrentRound = (): boolean => this.sessionId === nextId && this.messages.length === 0`（同 store 既有 `isCurrentRound` 的"闭包快照比对"用法）。判据两侧：① 会话 id 仍是本次换出的 `nextId`（未被再次「新建对话」换掉）；② `messages` 仍为空 —— `send` 会**同步** `recordUserMessage`，故 `messages` 非空 ⇔ 新会话里已经起过轮次。注释同时写明动机（提示异步落地、最坏等 HTTP 超时，不得盖掉新会话/新轮的 `notice`） |
| 13 | 同上 | `:1147-1148` | **F2 口径订正**：删掉「先 cancel 再 delete，把记忆收敛的复活窗口压到最小」，改为「**顺序不缩小复活窗口**：`cancel` 只置取消标志 + 唤醒收尾、轮次收盘异步 ⇒ 在飞路径下本 DELETE **必然先于**收盘重写；此处的顺序只为尽量早地把旧记忆清掉」 |
| 14 | 同上 | `:1093` | 函数 javadoc 第 4 步补「提示带「仍是本轮」守卫（复核 F5）」 |
| 15 | 同上 | `:1095-1099` | **F2 口径订正**：把「与删除之间存在毫秒~秒级竞态」整段改为如实版 —— 在飞路径下 DELETE **必然先于**收盘重写（顺序必然、不是概率竞态，`cancel → delete` 也**缩不小**该窗口），旧键**必被写回**、残留至 TTL；旧 id 已被前端丢弃（用户不可见）；409 硬拒仍被否 |

### 1.4 后端 · F2/F3 注释口径（`AiController` 端点 javadoc）

| # | 文件 | 位置 | 改动 |
|---|---|---|---|
| 16 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | `:555-557` | **F2 口径订正**：删掉「收敛窗口由前端顺序收口（先 POST cancel 再调本端点）」，改为「**前端顺序不关闭该覆盖**：`cancel` 只是置取消标志 + 唤醒收尾，轮次收盘是同步不了的 ⇒ 在飞路径下本 DELETE **必然先于**收盘重写（不是概率竞态，顺序收口也缩不小该窗口）；残留由会话 TTL 约束」 |
| 17 | 同上 | `:610-612` | **F3 口径注释**（登记知悉，不改字段/不改名）：`removedFromIndex` 是**响应时点**事实 —— `true` 只表示"此刻已不在索引里"，不保证随后不被在跑轮次的收盘重写 |

> 端点响应体（含 `note` 文案）**一字未改** ⇒ 对外契约零变化。

### 1.5 台账登记（只追加）

| # | 文件 | 位置 | 改动 |
|---|---|---|---|
| 18 | `docs/M2-排期计划.md` | `:409-421`（文件由 408 → 422 行） | 「等待治理排查登记注记」节末**新小节**「对话面板包复核 MINOR / 观察项登记（2026-10-10，只追加）」，+14 行、`git diff --numstat` = **+29 / −0**（含苞 A 落盘前既有的 15 行暂缓登记 hunk；该 15 行**一字未动**，其原末尾行经核验仍在原位）。登记对象见 §4 |

### 1.6 仓库外报告口径订正（F2，非仓库内容）

| # | 文件 | 改动 |
|---|---|---|
| 19 | `<SCRATCH>/chat-panel-pkg/施工报告-苞A.md` | `:65` `removedFromIndex` 描述订正（响应时点事实 + 判存已改 `SISMEMBER`）；`:97` 「复活窗口的收敛由前端顺序收口」订正为如实口径；`:173`/`:183` 用例矩阵标题与第 7/8 行补 F4/F8 注记并**追加第 9/10 行**；`:278` D2 偏差行订正（成本低估 N 倍 + 收益口径不成立） |
| 20 | `<SCRATCH>/chat-panel-pkg/施工卡.md` | `:104` 删除行补 F5 守卫说明；`:211` 候选① 评价订正（非毫秒~秒级竞态、顺序不关闭窗口）；`:215` 建议结论订正；`:414` 边界段订正；`:527` 立项行草案内的「毫秒~秒级竞态」订正 |

> 两份报告/施工卡均为**仓库外文档**，正文一律抽象篇名（不含仓库名/盘符/账号）。

---

## 2. 验证数字（全部实跑）

| # | 验证面 | 命令 | 实测 | 退出码 |
|---|---|---|---|---|
| 1 | 后端 · 定向（新类） | `bash <SCRATCH>/.run-fix/mvn-run.sh -B test -Dtest=AiControllerHistoryDeleteTest -DfailIfNoSpecifiedTests=false` | **`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`**（原 8 + F4 新 2） | 0 |
| 2 | 后端 · 全量（首轮，含 F4 改动 + 新 2 例） | `bash <SCRATCH>/.run-fix/mvn-run.sh -B clean test` | **`Tests run: 399, Failures: 0, Errors: 0, Skipped: 0`**、`BUILD SUCCESS`（01:20 min）；surefire 求和 **61 个 XML / 399 / 0 / 0 / 0**（复核基线 397 + 2） | 0 |
| 2b | 后端 · 全量（**冻结树终验**，F4 注释末次微调后复跑） | 同上 | **`Tests run: 399, Failures: 0, Errors: 0, Skipped: 0`**、`BUILD SUCCESS`（01:12 min）；XML 求和 **61 / 399 / 0 / 0 / 0** | 0 |
| 2c | 其中 flaky 类（R-105） | 同上两轮 | `SseChatEmitterWriteBudgetTest` **6/6 绿**（两轮均绿，无需单类重跑） | — |
| 2d | 用例增量口径 | — | 399 = 复核基线 397 + **2**（F4 两例）；XML **61 → 61**（未新增测试类，只增方法）⇒「≥397 / 0 / 0 / 0、61 XML 只增不减」达成 | — |
| 3 | 前端 · typecheck | `cd frontend && yarn typecheck`（`vue-tsc --noEmit`） | `Done in 5.74s`，**0 error** | 0 |
| 4 | 前端 · build | `yarn build`（`vue-tsc --noEmit && node scripts/build.mjs`） | `✓ 1747 modules transformed`、`✓ built in 32.64s`、dist 分块正常（element / spreadjs / vendor） | 0 |
| 5 | 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 PASS**（⑤：`git ls-files` 920 路径 / 实扫 917 / 豁免 3；规则自检 连写 5/5、分隔 24/24、负对照 11） | 0 |
| 6 | CDP 冒烟（**不设** `SMOKE_KILL_BACKEND`） | `node scripts/chat-panel-smoke.mjs` | **12 PASS + ⑫ SKIP、0 FAIL**；⑫ 原因逐字落盘 `SMOKE_KILL_BACKEND 未置 1（默认不杀进程；本阶段按 SKIP 处理）`；结果 JSON `result-20261010T154847Z.json` | 0 |
| 7 | **F5 守卫活体验证**（仓库外一次性脚本 `<SCRATCH>/chat-panel-fix-run/verify-f5.mjs`，CDP `Fetch` 域挂住 DELETE 后人为失败） | `node verify-f5.mjs` | **3/3 PASS**：A（守卫不吞提示，正向对照）PASS；B（期间又新建对话 → 过期提示不写入，`notice` 保持 `null`）PASS；C（期间新会话已起一轮 → `messages=2` 且过期提示不覆盖）PASS | 0 |
| 7b | **F5 反向对照**（临时把守卫改为无条件写入，验完按 sha256 逐字节还原） | 同 7 | **B / C 双红**（`notice` 被过期提示覆盖为「旧会话记忆删除失败（不影响新对话）」）⇒ 脚本确有判别力；还原后复跑 **3/3 PASS** | 1（对照态，预期） |
| 8 | jar 字节级复核（证明冒烟所用 jar 与冻结树等价） | `unzip -p target/config-mgr.jar BOOT-INF/classes/...AiController.class \| sha256sum` | 冒烟前 jar 与冻结树重建 jar：`AiController.class` = `676bbd79…f6df9a1`、`RedisChatMemoryRepository.class` = `9bc0a112…79bb9f6b`，**两者逐字节相同**（末次改动仅 javadoc，不进 class 文件） | — |

**未跑的验证面（如实登记）**：E2E 29 用例（`scripts/verify-e2e.ps1`）**未复跑** —— 本次改动面（新端点的诊断字段判存 + `resetSession` 失败提示守卫）不在 E2E 29 用例的覆盖路径内（苞 A 端点由 CDP 冒烟 ③/④ 覆盖，已跑）；若指挥官要求全链复跑，可在同一环境加跑（约 6~8 分钟）。

---

## 3. F5 守卫的判据与边界（供裁决）

**判据**（`stores/ai.ts:1154`）：`this.sessionId === nextId && this.messages.length === 0`

| 场景 | 守卫结论 | 理由 |
|---|---|---|
| 点「新建对话」后用户什么都没做，旧记忆删除失败 | **写提示**（保持原行为） | 会话 id 仍是本次换出的 `nextId`、`messages` 仍空 ⇒ 仍是"本轮" |
| 期间又点了一次「新建对话」 | **不写** | 会话 id 已换成第二次的 `nextId` ⇒ 过期提示不落到更新的会话上（活体 B） |
| 期间在新会话里发了消息 / 起了轮次 | **不写** | `send` 同步 `recordUserMessage` ⇒ `messages` 非空（活体 C） |
| 后端不可达、HTTP 超时 60s 后才失败 | 由上面同一判据决定 | 提示落地时点越晚，越可能已被"新会话/新轮次"事实判为非本轮 |

**已知边界（如实登记）**：判据是"会话级 + 轮次级"的**必要不充分**近似 —— 新会话里起过一轮并已收尾（`messages` 非空、`loading=false`、`notice` 可能已被用户读走）时，该提示同样不写。**这是刻意取的安全方向**：宁可少提示一条（旧记忆到期自然清理、无任何用户可见后果），也不覆盖新会话的现场提示。

**前端无测试框架**（`frontend/package.json` 无 vitest/jest，仓内零 `*.spec.ts` / `*.test.ts`）⇒ F5 无法在仓内补用例；代偿 = 仓库外 CDP 活体脚本（含反向对照），脚本与结果 JSON 落 `<SCRATCH>/chat-panel-fix-run/`，**不新增仓内测试脚手架**（避免为一条守卫引入测试依赖）。

---

## 4. 台账登记（`docs/M2-排期计划.md:409-421`，只追加）

- **F3（MINOR · `removedFromIndex` 时点性）**：登记知悉 + 已补口径注释；**不改名、不改字段**（改名动契约面，前端不读任何字段）。
- **F6①（观察 · 未知路由 → 500）**：`GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜住 `NoResourceFoundException`；三条与本次改动无关的路径实测同为 500 ⇒ **既有缺陷、非本包引入，待收口**。
- **F6②（观察 · 鉴权/会话归属）**：全仓无鉴权（DC-10）；本次在同命名空间内**新增了一个破坏性动词**（`DELETE`，幂等 200 使试探性删除无 404 噪声）；**登记口径 = 未来引入鉴权时必须同步补会话归属校验**（不在无鉴权框架里提前造假归属校验）。
- **F8 / F9 / F10**：一句话知悉登记（MockMvc 口径的注释边界；冒烟⑧的剪贴板证据边界；命名纪律门禁按 `git ls-files` 扫描的未跟踪文件盲区）。
- **F1（MAJOR · 流程/归属）**：一并登记待指挥官确认（工作树文件集合 6 → 7：本文件被追加 15 行「Markdown / 图表渲染 暂缓登记」，两份施工报告均声明未动台账）。
- **F2 / F4 / F5**：留痕（已在收口棒内改动）。

---

## 5. 偏差与处置

| # | 偏差 | 处置 |
|---|---|---|
| D1 | **冒烟首跑在阶段 8 卡死**（非本次改动引起）：`chat-panel-smoke.mjs:553` 的 `navigator.clipboard.readText()` 用 `awaitPromise:true` 且**无超时**，剪贴板读权限在自动化下弹出不可作答的原生提示 ⇒ 脚本挂起（日志停在「阶段7」57s+ 无新增，进程仍在）。复核报告实跑时其 profile 已带该权限（回读值 `Shiki ` 即环境无关残留），故未暴露 | 未改仓内脚本（苞 B 交付物，本棒无授权）；改为在**夹具层**修环境 —— 停自启 Chrome 后向冒烟用 profile 写入 `http://localhost:5202` 的 `content_settings.exceptions.clipboard.setting=1`，复跑即通（回读值 = 上一轮助手回复原文）。**观察项登记 + 【待裁决】**：是否给该断言补超时（`Promise.race` + 回落 `READ_TIMEOUT`），使脚本在无权限环境**如实降级**而非挂死 |
| D2 | F8 的测试注释**未改** | 按指令「一句话知悉登记」处置；仓内已在该用例说明行补注生产容器可达（`:184`），是否回改原句【待裁决】 |
| D3 | F5 无仓内用例 | 见 §3 末（无前端测试框架；以仓库外活体 + 反向对照代偿） |
| D4 | F3 未改名/未改字段 | 按指令只登记 + 补注释；改名的取舍【待裁决】 |
| D5 | 本棒**追加写入了 `docs/M2-排期计划.md`**（与苞 A/B 的"未动台账"声明并存） | 纯追加（`+29/−0`）、既有 15 行暂缓登记 hunk 一字未动；登记内容 = 本次复核发现。该文件当前的提交归属仍属 F1 待裁决面 |
| D6 | 全量后端复跑两次（首轮 + 冻结树终验） | 因末次只做 javadoc 空格微调，为满足"交付物即受测物"重跑一次；两次均 399/0/0/0，且 class 文件 sha256 证明字节等价（§2-8） |

---

## 6. 环境与清理（端口纪律）

- 启动前 `netstat` 预检：**18330 / 18399 / 5202 / 9334 全空**；共享 Redis（`127.0.0.1:6379`）在线、**未触碰**。
- 自启进程一律按**监听 PID** 收尾：后端 `java.exe` 30224（:18330）、桩 `node.exe` 30320（:18399）、前端 dev `node.exe` 24040（:5202）、冒烟/F5 脚本各自的 Chrome（按命令行 `*chat-panel-fix-run*` 核对，**零残留**）。收尾后四端口**全部释放**。
- 用户的 Chrome 与共享 Redis **未被触碰**；未使用真实 AI key（全程占位串经环境变量注入，密钥零落盘）。
- 构建产物仅 `backend/target/`、`frontend/dist/`（均在 `.gitignore` 内）。

**证据落盘（仓库外）**

| 证据 | 位置 |
|---|---|
| 后端全量日志（两轮） | `<SCRATCH>/chat-panel-fix-run/` 与任务日志 `<SCRATCH>` 会话目录 |
| CDP 冒烟日志 + 结果 JSON | `<SCRATCH>/chat-panel-fix-run/logs/smoke2.log`、`smoke-out/result-20261010T154847Z.json` |
| F5 活体脚本 + 结果（正/负/还原） | `<SCRATCH>/chat-panel-fix-run/verify-f5.mjs`、`f5-out/`、`f5-out-neg/`、`f5-out-restored/` |
| 前端 build 输出 | 终端（`frontend/dist/`，`.gitignore` 已豁免） |

---

## 7. 复现命令

```bash
# 1) 后端（包装脚本透传 Maven 全路径，规避含空格路径）
bash <SCRATCH>/.run-fix/mvn-run.sh -B clean test                                  # → 399/0/0/0, 61 XML, exit 0
bash <SCRATCH>/.run-fix/mvn-run.sh -B test -Dtest=AiControllerHistoryDeleteTest -DfailIfNoSpecifiedTests=false   # → 10/0/0/0

# 2) 前端
cd <MAIN_REPO>/frontend && yarn typecheck && yarn build                            # 均 exit 0

# 3) 文档门禁
cd <MAIN_REPO> && node scripts/ci/check-docs.mjs                                   # → 6/6, exit 0

# 4) 冒烟环境（工作目录在仓库外；桩 127.0.0.1:18399 → 后端 jar :18330）
node <MAIN_REPO>/scripts/ci/stub-upstream.mjs --port 18399 --dir <SCRATCH>/chat-panel-fix-run/stub
bash <SCRATCH>/.run-fix/mvn-run.sh -B package -DskipTests
cd <SCRATCH>/chat-panel-fix-run/run && \
  AI_API_KEY=<占位串> AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub \
  java -jar <MAIN_REPO>/backend/target/config-mgr.jar --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/ci_e2e_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150
cd <MAIN_REPO>/frontend && VITE_API_BASE=http://localhost:18330 VITE_DEV_PORT=5202 npx vite

# 5) CDP 冒烟（未设 SMOKE_KILL_BACKEND）
cd <MAIN_REPO> && SMOKE_OUT=<SCRATCH>/chat-panel-fix-run/smoke-out SMOKE_CDP_PORT=9334 \
  SMOKE_API=http://localhost:18330 SMOKE_APP=http://localhost:5202/ node scripts/chat-panel-smoke.mjs   # → 12 PASS + ⑫ SKIP

# 6) F5 守卫活体（仓库外脚本，内含 A/B/C 三断言；反向对照见 §2-7b）
SMOKE_OUT=<SCRATCH>/chat-panel-fix-run/f5-out SMOKE_CDP_PORT=9334 \
  SMOKE_APP=http://localhost:5202/ node <SCRATCH>/chat-panel-fix-run/verify-f5.mjs                      # → ALL PASS, exit 0
```

---

## 8. 【待裁决】

1. **F1（MAJOR · 归属）**：`docs/M2-排期计划.md` 上的 **15 行**「Markdown / 图表渲染 暂缓登记」（复核期间 23:30 落盘，非苞 A/苞 B 所为）+ 本棒追加的 **14 行**复核登记，随哪次提交入库、是否单列提交。
2. **F9 观察的脚本硬化**：是否给 `scripts/chat-panel-smoke.mjs:553` 的 `clipboard.readText()` 补超时/降级（现形态在无剪贴板权限的环境会**挂死**而非如实 SKIP；本次已用夹具权限绕过，属环境补丁不是代码修）。
3. **F3 字段**：是否把 `removedFromIndex` 改名（如 `removedAtResponseTime`）或在 `note` 里补时点说明 —— 现状 = 只补了 javadoc 口径，响应体一字未改。
4. **F8 注释**：是否回改 `AiControllerHistoryDeleteTest` 用例 8 的原注释句（"走 HTTP 到达不了这一分支"）。
5. **F6①（未知路由 → 500）** 与 **F6②（引入鉴权时补会话归属校验）** 是否另立排期条目 / 归入既有条目族。
6. **E2E 29 用例**是否需要在本环境补跑一次（本次判定为覆盖不到改动面，未跑）。

---

## 9. 追加棒 · 冒烟剪贴板超时修（F9）+ E2E 桩模式终验（§8 第 6 条）

- **本棒授权面**：§8 第 2 条（F9：是否给 `chat-panel-smoke.mjs` 的回读补超时/降级）+ 第 6 条（E2E 29 用例是否补跑）。**§8 其余各项（F1/F3/F6①/F6②/F8）状态不变**。
- **受测物**：`<MAIN_REPO>` 工作树**终态**（苞 A/苞 B + 本收口棒 + 本棒的冒烟修），分支 `main`、HEAD=`03a6cbe`。
- **纪律**：零 `git add/commit/push`；工作树既有改动（6 改 + 2 未跟踪）**零还原、零覆盖**，本棒改动叠加其上；**未触碰**邻棒面 `docs/M2-排期计划.md`、`docs/adr/DECISION-REGISTER.md`、`docs/README.md`、`README.md`；`git status --porcelain` 前后同为 **6 改 + 2 未跟踪**（集合逐项不变）。

### 9.1 改动（`scripts/chat-panel-smoke.mjs`，未跟踪交付物；行号 = 改动后**现态实读**）

| # | 位置 | 改前 | 改后 |
|---|---|---|---|
| 1 | `:12` | 用法块无该旋钮 | 新增 `SMOKE_CLIPBOARD_TIMEOUT_MS=5000` 用法行（回读上界；夹具可置极小值以复现无权限环境的降级路径） |
| 2 | `:34-35` | 「另附剪贴板回读（非断言，CDP 下常被拒）」 | 补「**带 5s 上界**——超时/无权限如实 SKIP 并把理由落盘，详见 `readClipboardBounded`」 |
| 3 | `:73-74` | （无） | 新增 `CLIPBOARD_READ_TIMEOUT_MS = Number(process.env.SMOKE_CLIPBOARD_TIMEOUT_MS ?? 5000)` + 动机注释（D1：无权限环境下 promise 永不 settled ⇒ 挂死阶段 8） |
| 4 | `:404-434` | （无） | 新增函数 `readClipboardBounded()`：`Promise.race([evaluate(readText, awaitPromise:true), sleep(上界)])`；四条分支 —— `OK`（拿到字符串）/ `SKIP:TIMEOUT>…ms` / `SKIP:NO_PERMISSION(<name>)` / `SKIP:EVAL_ERR:…`；javadoc 写明「⑧ 判据不含本项」「悬空即弃、不影响阶段 9/10」 |
| 5 | `:591` | `const read8 = await evaluate('(async () => { try { return await navigator.clipboard.readText() } catch (e) { return 'READ_DENIED:' + … } })()')`（`awaitPromise:true`，**无上界**）`.catch(() => 'READ_ERROR')` | `const read8 = await readClipboardBounded()` |
| 6 | `:593-597` | `clipboardReadback: read8`（裸字符串） | `clipboardReadback: read8.value`（仅 OK 时有值）+ **新增** `clipboardReadbackStatus`（`OK`/`SKIP`）+ `clipboardReadbackReason`（理由原文） |
| 7 | `:598-601` | （无） | SKIP 时写 `RESULT.skipped['⑧·旁证(剪贴板回读)'] = "SKIP: <理由>"`，并打印 `[阶段8 回读旁证] SKIP <理由>` |
| 8 | `:602-607` | ⑧ 的 detail 内 `clipboardReadback: String(read8).slice(0, 60)` | detail 拆为 `clipboardReadback`（仅 OK 给值）+ `clipboardReadbackStatus` + `clipboardReadbackReason`；**⑧ 的判据表达式一字未改**（仍为 `clipReady === true && click8 !== 'none' && exact8 && s8.hintText === '已复制' && s8.notice === null`） |

**「保持既有断言语义」的论证**：回读在改前改后都**不是** ⑧ 的判定输入（脚本头 `:32` 原文即「非断言」）。改后 SKIP 只影响旁证的记录形态，⑧ 的 PASS/FAIL 仍只由 `__copyCalls` 的**逐字符比对** + 「已复制」+ `notice === null` 决定 ⇒ 既不假绿（旁证没拿到就报 SKIP，不谎报值）也不假红（不因环境无权限而判 ⑧ FAIL）。

### 9.2 修前后对照（同一环境、同一工作树，唯一差异 = 回读有无上界）

环境：桩 `127.0.0.1:18399` + 后端 jar `:18330` + 前端 dev `:5211` + 自启 Chrome（CDP `:9334`），**全新 Chrome profile**（无剪贴板授权 —— 正是 F9/D1 的事发形态）。

| 形态 | 脚本 | 结果 |
|---|---|---|
| **修前** | 仓外对照件（现态脚本，唯一差异 = 回读恢复为**无上界**的原始等待） | **挂死复现**：日志停在 `[阶段7 hover 显现]` 之后**无任何新增**，⑧ 从未执行，看门狗 120s 强杀（exit 124），**无 result JSON 落盘** —— 与 D1 观测（「阶段 7 后 57s+ 无新增、进程仍在」）同形 |
| **修后 · A**（默认上界 5s） | 仓内脚本 | `[阶段8 回读旁证] SKIP TIMEOUT>5000ms（剪贴板读未在限内答复：无权限环境/权限提示未作答）` → **12 PASS + ⑫ SKIP**，exit 0，18s 跑完 |
| **修后 · B**（`SMOKE_CLIPBOARD_TIMEOUT_MS=1`） | 仓内脚本 | 同一 SKIP 分支（`TIMEOUT>1ms`），降级路径**可确定性复现** → 12 PASS + ⑫ SKIP，exit 0，22s |
| **修后 · C**（夹具注入拒绝形态） | 仓外对照件（唯一差异 = 阶段 8 前把页面侧 `readText` 置为 `Promise.reject(DOMException('…','NotAllowedError'))`） | `[阶段8 回读旁证] SKIP NO_PERMISSION(NotAllowedError)：剪贴板读未授权（CDP 下常被拒）` → 12 PASS + ⑫ SKIP，exit 0 |

> 注（如实留痕）：⑫ 的 SKIP **理由**在 A 轮是「`SMOKE_KILL_BACKEND` 未置 1（未授权）」、B/C 轮是「未进入挂起态（未执行杀进程）」——两轮都**没有**执行任何杀进程动作（未授权面），⑫ 一律 SKIP；B/C 的差异来自阶段 12 起长跑轮次未在 8s 内进入挂起态（非本棒改动面，且不影响 12 条断言：⑫ 本就按 SKIP 处理）。

**SKIP 降级证据（A/B/C 三轮落盘一致）**

```json
// smoke-out-a/result-*.json（C 同理，仅 reason 不同）
"skipped": {
  "⑧·旁证(剪贴板回读)": "SKIP: TIMEOUT>5000ms（剪贴板读未在限内答复：无权限环境/权限提示未作答）",
  "⑫": "SMOKE_KILL_BACKEND 未置 1（默认不杀进程；本阶段按 SKIP 处理）"
},
"phases.copyClipboardPath": { "clipboardReadback": null, "clipboardReadbackStatus": "SKIP",
  "clipboardReadbackReason": "TIMEOUT>5000ms（…）", "exactMatch": true, "hintText": "已复制", "notice": null }
```

- **关键读数**：本环境 `navigator.clipboard.readText()` 在 5s 内**从未 settled**（A 的 SKIP 理由即证据）⇒ 无上界的原实现按定义会**永久等待**；修后同一处行为 = 如实 SKIP。C 轮另证「有权限判定但被拒」的形态同样落到 SKIP 分支，理由带浏览器真实错误名。
- **后置断言未受污染**：⑨（回退 A）/ ⑩（回退 B）/ ⑪（停止不误闪）/ ⑬（骨架屏）在超时那次**悬空 evaluate 之后**全部 PASS ⇒「悬空即弃」不泄漏到后续阶段。
- **CDP `Browser.setPermission` 路线作废留痕**：Chrome 154 对本机返回 `-32602 Invalid PermissionDescriptor name: clipboardReadWrite`（改用页面侧拒绝形态 C，理由名与真实一致）。

### 9.3 E2E 桩模式终验（29 用例）

- 口径：`E2E_STUB_MODE=1` + `-EnableGf6` + `-ArtifactDir`（复现 CI e2e job）；桩 `:18399`（`--violate-form-type`）、后端 `:18330`（工作目录在仓库外、每次清空 DB 目录）、共享 Redis 未触碰、真实 key 未使用（占位串经环境变量注入）。
- **结果：`PASS=29  FAIL=0`，`GFSKIP=0`（桩模式零容忍达标），`Q8 项：PASS=2  FAIL=0`，脚本 exit 0，E2E 段 59s**（含桩自检与就绪等待全程约 75s）。
- 用例面：TC1–TC14、TC20、TC21、TC15–TC18、TC22、TC23、TC19 + GF1–GF6 全绿；GF6 本轮**非 SKIP**（桩 `--violate-form-type` 确定性触发 `REJECTED_ARGUMENTS` 结局帧）。
- **受测物一致性**：`mvn -B package -DskipTests`（exit 0，3.6s）后核对 `BOOT-INF/classes/**/AiController.class`、`RedisChatMemoryRepository.class` 的 sha256 与 `target/classes` **逐字节相等**（`mvn` 报 "Nothing to compile"，class 比源新 23:51 > 23:46）⇒ 跑的就是终态树，不存在「jar 落后于源」。
- 与苞 B 终验数字一致：`29/0`、`GFSKIP=0`、`Q8 2/0`。

### 9.4 门禁

| 门禁 | 命令 | 结果 |
|---|---|---|
| 文档一致性 | `node scripts/ci/check-docs.mjs` | **6/6 全绿，exit 0**（含断言⑤命名纪律：`git ls-files` 920 路径 / 实扫 917 / 豁免 3；规则自检连写 5/5 + 分隔 24/24 + 负对照 11 不误伤） |
| 命名纪律（**含未跟踪**，`-z` 精确路径） | 仓外自查脚本（`git status --porcelain -z`；同 check-docs 断言⑤三态 + 规则自检） | 11/11 文件实扫（含 2 个未跟踪），**「main」+分隔符+「v2」命中 = 0**，规则自检 0 失败 |
| 脱敏（同一脚本） | 盘符 / 本机用户名 / 私有网段 IP | 盘符 **0**、用户名 **0**、私有网段 **0**；`127.0.0.1` 仅 5 行**既有行**（README、DECISION-REGISTER、冒烟脚本 `:101`/`:109`），本棒新增行零命中 |
| 门禁盲区（复核 F10 澄清） | 同上 | 本棒自查已覆盖未跟踪文件；盲区仅在 check-docs 的 `git ls-files` 口径（未跟踪文件不入扫描），**本棒未改该门禁**（无授权，仍登记） |

### 9.5 端口纪律与清理

- 启动前 `netstat` 预检：**18399 / 18330 / 5211 / 9334 全空**；共享 Redis（`:6379`）在线、**未触碰**。
- 自启进程一律按**监听 PID** 收尾：桩 `node`、后端 `java`、前端 dev `node`、各轮 Chrome（CDP 端口反查；另按命令行核对 `*chatpanel-final-run*` Chrome 残留 = **0**）；收尾后 18399/18330/5211/9334 **全部释放**。用户的 Chrome 与共享 Redis 未触碰。
- 构建产物仅 `backend/target/`（`.gitignore` 内）；前后端源码零改动（本棒唯一仓内改动 = 冒烟脚本）。

### 9.6 证据落盘（仓库外）

| 证据 | 位置 |
|---|---|
| 冒烟 A / B 日志 + 结果 JSON | `<SCRATCH>/chatpanel-final-run/chat-panel-smoke.log`、`chat-panel-smoke-timeout.log`、`smoke-out-a|b/result-*.json` |
| 修前对照（挂死复现） | `<SCRATCH>/chatpanel-final-run/before-smoke.log` + 对照件 `before-smoke.mjs` |
| 无权限降级复现（C） | `<SCRATCH>/chatpanel-final-run/noperm2-smoke.log` + 对照件 `noperm2-smoke.mjs`、`smoke-out-noperm2/result-*.json` |
| E2E 全量日志 + GF 证据 + 后端/桩日志 | `<SCRATCH>/chatpanel-final-run/e2e-run.log`、`artifacts-e2e/{gfc,boot.log,stub.log}` |
| 编排与门禁脚本 | `<SCRATCH>/chatpanel-final-run/{run-smoke.sh,up-smoke.sh,down-smoke.sh,run-e2e.sh,naming-check-final.mjs}` |
| 后端重建日志 | `<SCRATCH>/chatpanel-final-run/mvn-package.log` |

### 9.7 复现命令

```bash
# 0) 后端 jar 由终态树重建（工作目录 = <MAIN_REPO>/backend；Maven 全路径含空格，故用包装调用）
"<MAVEN_BIN>/mvn.cmd" -B package -DskipTests                 # → BUILD SUCCESS；class 与 jar 内 sha256 逐字节相等

# 1) 冒烟环境（桩 :18399 + 后端 jar :18330 + 前端 dev :5211）
bash <SCRATCH>/chatpanel-final-run/up-smoke.sh
# 2) 冒烟 A（默认上界 5s，未设 SMOKE_KILL_BACKEND）
SMOKE_API=http://localhost:18330 SMOKE_APP=http://localhost:5211/ SMOKE_CDP_PORT=9334 \
  SMOKE_OUT=<SCRATCH>/chatpanel-final-run/smoke-out-a node <MAIN_REPO>/scripts/chat-panel-smoke.mjs   # → 12 PASS + ⑫ SKIP, exit 0
# 3) 冒烟 B（超时降级路径可确定性复现）
SMOKE_CLIPBOARD_TIMEOUT_MS=1 …（同上）                                                              # → ⑧·旁证 SKIP TIMEOUT>1ms
# 4) 修前对照（仓外对照件，唯一差异 = 回读无上界）
timeout 120 node <SCRATCH>/chatpanel-final-run/before-smoke.mjs                                     # → 停在阶段 7，exit 124
# 5) 收尾（只按监听 PID）
bash <SCRATCH>/chatpanel-final-run/down-smoke.sh
# 6) E2E 全量（桩 18399 + 后端 18330；E2E_STUB_MODE=1 + -EnableGf6）
bash <SCRATCH>/chatpanel-final-run/run-e2e.sh                                                       # → PASS=29 FAIL=0, GFSKIP=0, exit 0
# 7) 门禁
node <MAIN_REPO>/scripts/ci/check-docs.mjs                                                          # → 6/6
node <SCRATCH>/chatpanel-final-run/naming-check-final.mjs <MAIN_REPO>                               # → 命名/盘符/用户名/私有网段 全 0
```

### 9.8 【待裁决】（本棒）

1. **§8 第 2 条（F9）已闭环**：脚本硬化落地 + 三形态验证（超时 / 无权限 / 无权限-拒绝）；请在提交信息里注明「承接复核 D1/F9」，或按需要并入苞 B 的提交。
2. **§8 第 6 条（E2E 补跑）已闭环**：29/29 全绿（§9.3）。是否把它作为对话面板包的**终验证据**定稿（取代「未跑」的旧判定）。
3. `RESULT.skipped` 新增键 `'⑧·旁证(剪贴板回读)'`：当前无下游消费者；若日后加「SKIP 计数」类统计脚本，需按键名含「旁证」人工排除（勿计入断言 SKIP 数）。
4. `SMOKE_CLIPBOARD_TIMEOUT_MS` 默认 5000ms 是否写入编排脚本/CI 环境（当前仅脚本内默认值；夹具侧本轮显式传值仅在对照实验里用）。
5. 本棒的仓内改动仍为**未跟踪**交付物的一部分（`scripts/chat-panel-smoke.mjs`），其提交归属与 F1（工作树文件集合归属）一并裁决。
