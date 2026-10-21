# 补档 · AI 输入输出安全防护批①（入口闸门）施工报告

- 日期：2026-10-21
- 范围：`docs/research/调研-AI输入输出安全防护业界惯例-2026-10-21.md` §5.1 #1（输入长度）+ #2（归一化）+ #3（限流）；数据最小化 R5 同面。批②（信任隔离与输出收敛）/ 批③（DLP 与观测）**本批不做**。
- 分支：`feature/ai-guardrails-b1`，基点 `d8056d5`；施工笔 `097fa66`、复审收口笔 `65f3dc2`；合流笔 `e62d375`（`--no-ff` 合入主线）。
- 施工链：施工（后端 726 绿）→ 复审 C1 收口（728）→ 收口复跑（733）。
- 本件为**补档**：由合流提交官按**代码最终态 + 分支/合流实测**对齐转录。凡与施工自报不一致处，以代码与实测为准并写明。复审 / 复核 / 提交三件见同批另三件。
- 提交官自证行（见 `-提交报告-DS-Flash.md` §0）：`agentId=agent-1346`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。

## 0. 测试矩阵（先行登记；每条均落为用例）

### 0.1 闸门 1 长度（G1 · `AiController#chat` + `AiProperties.Input`）

| # | 判据 | 用例 | 文件 |
| --- | --- | --- | --- |
| G1-1 | 8191 / 8192 字符**放行**（边界闭区间） | `chatAccepts8191And8192Characters` | `AiControllerChatGateTest` |
| G1-2 | 8193 字符 **400 `MESSAGE_TOO_LONG`**，且**不截断**（原样不送模型） | `chatRejects8193CharactersWithoutTruncating` | 同上 |
| G1-3 | 计数口径 = **UTF-16 码元**（8192 个汉字 = 24576 字节仍放行） | `chatAccepts8191And8192Characters` 内汉字段 | 同上 |
| G1-4 | 真 Servlet 面：400 + JSON 体（`code/length/limit`） | `overlongMessageIsRejectedOverHttpWithJsonBody` | 同上（MockMvc） |
| G1-5 | `app.ai.input.max-chars` 生效（配置驱动，非硬编码） | 同上（`AiProperties` 构造注入） | 同上 |

### 0.2 闸门 2 归一化（G2 · `InputSanitizer`）

| # | 判据 | 用例 | 文件 |
| --- | --- | --- | --- |
| G2-1 | 零宽（U+200B/200C/200D）、BOM、双向控制符被剥离 | `stripsZeroWidthAndFormatCharacters` | `InputSanitizerTest` |
| G2-2 | 全角 / 兼容字符 NFKC 折叠（`ＡＢＣ`→`ABC`、`①`→`1`、`ﬁ`→`fi`） | `foldsFullWidthAndCompatibilityForms` | 同上 |
| G2-3 | 保留 `\n` `\t`；`\r` 剥离（CRLF→LF）；其余控制字符剥离 | `keepsNewlineAndTabDropsOtherControls` | 同上 |
| G2-4 | 幂等：`sanitize(sanitize(x)) == sanitize(x)` | `isIdempotentOnAlreadyCleanText`（族） | 同上 |
| G2-5 | **归一后的文本才是进模型的那份**（`ResilientChatService` 实收归一文本） | `normalizedTextIsWhatReachesTheModel` | `AiControllerChatGateTest` |
| G2-6 | 归一后为空（纯不可见字符）⇒ 400（与"必填"同一口径） | `rejectsMessageThatIsInvisibleOnly` | 同上 |
| G2-7 | 日志只记长度差与剥离计数，**不落原文** | `logsOnlyLengthDeltaWhenSanitizingChangesTheInput` | 同上 |

### 0.3 闸门 3 限流（G3 · `AiRateLimiter`）

| # | 判据 | 用例 | 文件 |
| --- | --- | --- | --- |
| G3-1 | 同 sessionId 20 次/分放行，**第 21 次 429 `RATE_LIMITED`** | `sessionIsRateLimitedAtTwentyPerMinute` | `AiControllerChatGateTest` |
| G3-2 | 429 带 `Retry-After`（秒，向上取整）+ `scope/used/limit` | `rateLimitedIsRejectedOverHttpWithRetryAfter` | 同上（MockMvc） |
| G3-3 | IP 维度在会话不是瓶颈时生效（换 sessionId 绕不过） | `ipDimensionIsReportedWhenTheSessionIsNotTheBottleneck` | 同上 |
| G3-4 | Redis 计数不可用 ⇒ **降级放行** + WARN（不把对话判死） | `rateLimitDegradesToPassThroughWhenRedisCountingFails` | 同上 |
| G3-5 | 超长请求**同样占额度**（I2 裁决后的语义） | `overlongMessagesStillGet400AndConsumeTheRateQuota` | 同上 |
| G3-6 | 脚本正文契约：`RATE_SLIDING_WINDOW` / `ZREMRANGEBYSCORE` → `ZCARD` → `ZADD` / `PEXPIRE` / `TIME` 命令序 | `slidingWindowScriptKeepsItsContract`（族） | `AiRateLimiterTest` |
| G3-7 | 窗口滑动回收：滑出窗口的成员不再计数 | 窗口族（`FakeRateRedis` 驱动） | 同上 |

### 0.4 配置守卫（M1 · `AiProperties#verifyRateLimit`）

| # | 判据 | 用例 |
| --- | --- | --- |
| M1-1 | 缺省值通过 | `defaultLimitsPassTheGuard` |
| M1-2 | `session-per-minute = 0` ⇒ 启动失败并**点名键** | `nonPositiveSessionLimitFailsFastAndNamesTheKey` |
| M1-3 | `ip-per-minute = -1` ⇒ 同上 | `negativeIpLimitFailsFastAndNamesTheKey` |
| M1-4 | `window = 0s / -30s` ⇒ 同上（`PEXPIRE 0` 会静默失效） | `nonPositiveWindowFailsFastAndNamesTheKey` |

### 0.5 前端 composer（闸门 1 的界面面）

| # | 判据 | 用例 |
| --- | --- | --- |
| FE-1 | `textarea maxlength = 8192`（与后端同值同尺） | `textarea 带 maxlength=8192…` |
| FE-2 | 空输入不显示计数；有输入显示**剩余**字数，位置在发送钮左侧 | `空输入不显示计数；有输入显示剩余字数…` |
| FE-3 | 到顶（8192）转「已达上限」琥珀色，**仍可发送**（后端边界是 8192 放行） | `到顶（8192）改为「已达上限」提示…` |
| FE-4 | 超限（程序化回填 8193）标红 + **关闭发送闸**，不替用户截断 | `超限（程序化回填 8193）标红并关闭发送闸…` |
| FE-5 | 8191 字发送时 payload **原样**（前端不做裁剪） | `8191 字正常发送：payload 原样…` |
| FE-6 | 计数节点自身**不带** `aria-live`；播报由独立 sr-only live region 承担 | `计数节点自身不带 aria-live…` |
| FE-7 | 常规档内逐字输入：live region 文本恒为空 | `常规档内逐字输入：live region 文本恒为空` |
| FE-8 | 跃迁到「余量不足」只说一次常量文案（不含剩余数字） | `跃迁到余量不足档只说一次常量文案…` |
| FE-9 | 到顶 / 超限两档各有独立文案 | `到顶与超限两档各有独立文案` |

### 0.6 回归面（签名/夹具适配，不改语义）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `SessionBusyFrameIsolationConformanceTest` | 13 增 / 4 删 | `chat` 新增 `HttpServletRequest` 形参 ⇒ 调用点补 `null`；另锁"被限流不应占 in-flight 位"的顺序前提 |
| `AiControllerSseLifetimeTest` | 9 增 / 2 删 | 同上签名适配；SSE 生命周期断言不变 |

## 1. 红测取证（先行，四条）

> 取证方式：先落断言再落实现。施工期工作日志在仓外（不入库），下列"修前"现象由施工链记录，**每条都对应一个现在仍存在的用例**——删掉实现即复红。

| # | 红测 | 修前实测 | 修后锁它的用例 |
| --- | --- | --- | --- |
| R1 | **8193 → 400** | `POST /chat` 带 8193 字符**照常 200 开流**，超长文本原样进模型（无任何长度判据） | `chatRejects8193CharactersWithoutTruncating` / `overlongMessageIsRejectedOverHttpWithJsonBody` |
| R2 | **零宽原样进模型** | `忽\u200B略` 这类串**逐字**进 `ResilientChatService`（`service.chat` 实收原文，`length` 含零宽） | `normalizedTextIsWhatReachesTheModel` / `stripsZeroWidthAndFormatCharacters` |
| R3 | **第 21 次照过** | 同 sessionId 连发 21 次全部 200（无计数设施），第 21 次不返回 429 | `sessionIsRateLimitedAtTwentyPerMinute` |
| R4 | **前端 5 例红** | composer 无 `maxlength`、无剩余字数、无档位、live region 缺席、payload 断言无落点（5 例全红） | `ai-panel-composer-limit.spec.ts` 全 9 例（R4 的 5 例 + 播报节流 4 例） |

补充红证据（复审 I2 的语义变更）：收口前 `overlongMessageDoesNotConsumeRateQuota` 断言"超长**不占**额度"；按裁决改为"超长**占**额度且仍 400"——**先证红**（现序下第 21 次为 200），再改实现（限流提到归一化/长度之前）转绿。这是**经裁决的语义改写**，不是降格：断言方向由"不占"改"占"，强度不减。

## 2. 三道闸落地（最终态）

### 2.1 顺序（`AiController#chat`，`:189` 起）

```
必填校验 → Redis 探活(503) → 限流(429) → 归一化 → 归一后空拒(400) → 长度(400) → 会话门(409) → 开流
```

- 必填在校验最前：参数错就该报 400，不该被 Redis 状态遮住（既有口径不变）。
- **限流排在归一化/长度之前**（裁决 I2）：所有 `/chat` 一律计数——超长/纯不可见字符也是请求，同样消耗额度。否则攻击者可用超长体绕过限流做免费试探。
- **限流排在会话门之前**：被限流的请求不该占住一个 in-flight 位。
- 后果（已写进注释）：Redis 不可用时，超长请求会先撞 503 而不是 400。

### 2.2 闸门 1 · 长度（`:228-231`）

- 判据 = **归一后文本**的 UTF-16 码元数（`String#length`），与前端 `maxlength` 同一把尺 ⇒ 中文按 1 字算，不会被 UTF-8 三字节提前判死。
- 超限 **400 拒绝不截断**（裁决 A1）；响应体 `{code: MESSAGE_TOO_LONG, length, limit, message}`，**不回显原文**。

### 2.3 闸门 2 · 归一化（`InputSanitizer` 124 行）

- 三件事：NFKC 折叠、剥离 `Cf`（零宽/BOM/双向控制符/软连字符）+ 孤立代理项（`Cs`）、剥离非法 `Cc`（保留 `\n` `\t`，`\r` 一并剥离）。
- **只归一不拦截**：不改写语义、不判"是不是注入"；内容变化只记一条 INFO（长度差 + 剥离计数，不落原文）。
- 幂等：收敛循环上限 3 趟（剥字符可能让基字符与组合符相邻产生新的可组合序列）。
- 归一后为空 ⇒ 400（否则会带一条空 user 消息进模型与记忆窗口）。

### 2.4 闸门 3 · 限流（`AiRateLimiter` 199 行）

- **滑动窗口**而非固定窗口：固定窗口在边界放过 2× 额度，而本闸拦的"脚本连发"正是边界突刺形态。
- 单键 Lua 原子：`ZREMRANGEBYSCORE → ZCARD → ZADD → PEXPIRE`，时间取 **Redis `TIME`**（多实例时钟漂移不会把同一窗口切两半）。
- 双维度：`ai:rate:session:<sessionId>`（20/分）+ `ai:rate:ip:<RemoteAddr>`（60/分）；顺序先会话后 IP（会话被拒不动 IP 计数）。
- IP 只认 `RemoteAddr`，**不读** `X-Forwarded-For`（可伪造；本仓无前置代理。将来上代理须在代理层覆写并显式放开）。
- 降级：Redis 异常 / 脚本无返回 ⇒ **放行 + WARN**（限流是防滥用，不是准入前提）。与 `/chat` 开流前的 `RedisAvailability` 503 是两层。
- 429 带 `Retry-After`（秒，向上取整，至少 1），客户端据此退避而不是把窗口继续打满。

### 2.5 前端 composer（`AiPanel.vue`）

- `maxlength="8192"`：浏览器层不让写出超限文本（**判定层仍是后端**）。
- 底栏剩余字数四档：常态灰「剩余 N 字」→ 余量 ≤500 琥珀 → 到顶（8192）「已达 8192 字上限」→ 超限标红并**关发送闸**。
- `canSubmit` 加 `length <= 8192`；不替用户截断。
- 播报节流（复审 M5）：计数节点**不带** `aria-live`（原实现每敲一个字播报一次），改独立 `sr-only` live region，只在四档**跃迁**时播报常量文案。

### 2.6 配置与 CI

- `application.yml`：`app.ai.input.max-chars: 8192`、`app.ai.limit.{session-per-minute: 20, ip-per-minute: 60, window: 1m}`。
- `AiProperties`：`Input` / `Limit` 两段 + `verifyRateLimit()` 启动期正数守卫（0/负值 ⇒ 启动失败并点名键）。
- `ci.yml` + `verify-e2e.ps1`：E2E 后端启动显式抬高 `--app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000`，两处头部注明"限流不在 E2E 覆盖面，由单测覆盖"（裁决 I3）。

## 3. 改动清单（按最终态，18 文件 +1657/−11）

| 面 | 文件 | 行数 |
| --- | --- | --- |
| 生产·新 | `ai/gate/InputSanitizer.java` | +124 |
| 生产·新 | `ai/gate/AiRateLimiter.java` | +199 |
| 生产·改 | `ai/config/AiProperties.java` | +86（Input/Limit 段 + 守卫） |
| 生产·改 | `ai/web/AiController.java` | +100/−3（三道闸 + 两个错误体 + `clientIp`） |
| 配置 | `backend/src/main/resources/application.yml` | +16 |
| CI | `.github/workflows/ci.yml` | +11 |
| 脚本 | `scripts/verify-e2e.ps1` | +6（限流抬高口径） |
| 测试·新 | `ai/web/AiControllerChatGateTest.java` | +311（11 例） |
| 测试·新 | `ai/gate/AiRateLimiterTest.java` | +153（7 例） |
| 测试·新 | `ai/gate/InputSanitizerTest.java` | +111（8 例） |
| 测试·新 | `ai/config/AiPropertiesLimitGuardTest.java` | +69（4 例） |
| 测试·辅助 | `ai/gate/FakeRateRedis.java` | +128（脚本替身 + 正文捕获） |
| 测试·改 | `ai/conformance/SessionBusyFrameIsolationConformanceTest.java` | +13/−4 |
| 测试·改 | `ai/web/AiControllerSseLifetimeTest.java` | +9/−2 |
| 前端·改 | `frontend/src/components/AiPanel/AiPanel.vue` | +83/−2 |
| 前端·新 | `frontend/tests/ai-panel-composer-limit.spec.ts` | +210（9 例） |
| 前端·改 | `frontend/src/utils/markdown.ts` | +16（`resetDegradation`） |
| 前端·改 | `frontend/tests/setup.ts` | +12（全局 `beforeEach` 归零） |

## 4. 施工链门禁（各段实测）

| 段 | 后端 | 前端 | 备注 |
| --- | --- | --- | --- |
| 施工笔 `097fa66` | **726** / 0F（基线 702） | 517 / 26 文件 | 前端 5 例新增（当时落在 `ai-panel-parts-render.spec.ts`） |
| 复审 C1 收口前 | 728 / 0F | **exit 1**（5 条 Unhandled Rejection） | C1：`api.history` 未桩 ⇒ `history.messages` TypeError |
| 收口笔 `65f3dc2` | **733** / 0F（118 suite） | **521 / 521 · 27 文件** | 5 例移入独立 spec 并补桩；`degraded` 每例归零 |
| 合流笔 `e62d375`（提交官复跑） | **811 / 811 · 127 suite**（连跑 2 次） | **522 / 522 · 27 文件** | 主线 780 + 分支 31 = 811；前端 513 + 9 = 522 |

## 5. 施工自决（五条，均有代码锚）

1. **长度口径取 UTF-16 码元，不取 UTF-8 字节**：与前端 `maxlength` / JS `String#length` 同尺是"同一个数字在两处都不说谎"的最低条件（`AiProperties.Input` 类注）。取值 8192 = 与工具结果回填上限（`tool-result.max-chars: 8000`）同量级。
2. **超限拒绝而非截断**（裁决 A1）：截断会让模型基于半句话下结论，且用户看不到自己被截了。错误体只给 `length/limit`，不回显原文（原文可能本身就是敏感数据）。
3. **归一化只归一不拦截，且排在长度之前**：量的是"真正进模型的那份"；零宽填充不该算进用户额度。归一后为空按"必填"同口径 400 —— 否则一条空 user 消息会进模型与记忆窗口。
4. **限流落 Redis 单键 Lua 滑动窗口（时间取 Redis TIME），不新造进程内计数**：多实例下进程内计数每实例各算一份 = 额度放大 N 倍（调研 §6.4）。降级取 fail-open：限流是防滥用，不是准入前提。
5. **闸门组件用字段注入，不改构造器签名**：既有 `new AiController(...)` 的全部测试零触碰（`AiControllerChatGateTest` 只用 `ReflectionTestUtils` 灌入）；代价是"忘了装配"要靠 `@SpringBootTest` 家族兜住（本批已有）。

## 6. 未做 / 偏差（显式登记）

| 项 | 现状 | 去向 |
| --- | --- | --- |
| 前后端 8192 **交叉校验** | 前端常量 `MESSAGE_MAX_CHARS = 8192` 与后端 `DEFAULT_MAX_CHARS = 8192` 同值，但**无自动校验**（改一处漏一处就会说谎） | 复审 M2 登记不改；批② 或配置下发时收 |
| `context` 字段**不归一化** | 只处理 `message`；`context.{page,taskType,step,taskId,extra}` 未经本类。边界是 **fail-closed**：消费方不得假设已归一/可信 | 复审 M3 只做注释（`InputSanitizer` 类注）；白名单化属批② |
| `/confirm.reason` 与 `/frontend-tool-result` 载荷**无长度闸** | 本类存在 ≠ 全入口已受控 | 复审 M4；批②③ |
| 输出侧收敛 / DLP | 本批只做入口 | 批②（信任隔离与输出收敛）、批③（DLP 与观测） |
| 限流的 E2E 覆盖 | **不在 E2E 覆盖面**（裁决 I3），由单测覆盖；E2E 显式抬高参数避免假红 | 口径已写进 `ci.yml` 与 `verify-e2e.ps1` 头部 |

## 7. 纪律留痕

- 分支施工在隔离 worktree 内完成，**未碰主工作树**；禁 push 纪律在施工/收口两段均遵守（推送归合流提交官）。
- 断言只升不降：唯一改写是 I2 裁决下的 `overlongMessageDoesNotConsumeRateQuota` → `overlongMessagesStillGet400AndConsumeTheRateQuota`（方向由"不占额度"改"占额度且仍 400"，见 §1 补充红证据）。
- `?? data/` 未动未入库；仓外工作日志（后端/前端多轮）不入库。
