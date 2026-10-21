# 补档 · AI 输入输出安全防护批①（入口闸门）复审报告（DS-Flash，2026-10-21）

> 本件为入库补档。复审链原始报告未入库，条目编号沿用收口链下达的裁决原文（`C1` / `I1`–`I3` / `M1`–`M6` / `N1`–`N4`），逐条按**代码最终态与复跑实测**对齐。凡与施工自报不一致处，以代码与实测为准并写明。

- 复审对象：`feature/ai-guardrails-b1` 相对基点 `d8056d5` 的全部改动（生产 2 新 + 2 改、配置 1、CI 1、脚本 1；测试 4 新 + 1 辅助 + 2 改；前端 3 改 1 新）
- 只读纪律：零 commit/push/stash、零服务启停、零代码改动
- 复审时点门禁：后端 **726**/726（施工自报）、前端 **517 passed / 26 文件但 exit 1**（5 条 Unhandled Rejection）、typecheck 0、check-docs 6/6
- 自证：本补档由合流提交官转录（`agentId=agent-1346`，见 `-提交报告-` §0），复审链原始自证行以复审方报告为准，**不代签**

## 0. 结论：**CHANGES-REQUESTED**（1 Critical + 3 Important + 6 Minor + 4 Nit）

一句话：**三道闸的语义方向都对，但"闸门之间的顺序"与"测试面自身是否干净"两处会决定这批能不能信**。C1 是新增前端 5 例把整轮 vitest 打成 exit 1（CI 红）——不是产品坏，而是"新用例寄生在一个桩不全的 flaky 大文件里"；I2 是限流排在长度之后 ⇒ 超长请求可以**不付额度**做免费试探，与"防洪"目的相反；I1 是限流脚本正文没有契约断言（改错脚本、桩照过）。M 系六条里 M1（配置 0/负值静默改写语义）与 M5（计数器逐字播报）各对应一条真实可用性/无障碍路径。

**准予修后入库**：C1、I1–I3、M1、M5 必修；M2/M3/M4 与 N1–N4 允许"注释 / 登记"处置，但**必须显式写明边界**。

## 1. 关键路径核对（先看"钱在哪"）

| 路径 | 核对结论 |
| --- | --- |
| `AiController#chat` 闸门顺序 | **有问题**（见 I2）：限流在归一化/长度之后 ⇒ 超长与纯不可见请求不计额度 |
| 长度判据的"尺" | 正确：UTF-16 码元，与前端 `maxlength` 同尺；8192 汉字 = 24576 字节仍放行（用例已锁） |
| 归一化产物是否真的进模型 | 正确：`sanitize` 结果换 `AiChatRequest` 再交给 `service.chat`（用例用 ArgumentCaptor 锁住实参） |
| 限流原子性 | 正确：单键 Lua（`ZREMRANGEBYSCORE → ZCARD → ZADD → PEXPIRE`），时间取 Redis `TIME` |
| 限流降级 | 正确：异常/空返回 ⇒ 放行 + WARN，且与 `/chat` 的 `RedisAvailability` 503 分层 |
| 前端上限面 | 正确：`maxlength` 只防"写出"，判定仍在后端；`canSubmit` 加长度条件但不截断 |
| 测试面自身 | **有问题**（见 C1）：5 条新用例在 `ai-panel-parts-render.spec.ts` 里触发 Unhandled Rejection |

## 2. Critical（1 条）

### C1 · 新增的 5 条 composer 用例把整轮 vitest 打成 exit 1

- **现象**：5 例从 `ai-panel-parts-render.spec.ts` 末尾新增后，`vitest run` 输出 `517 passed` 但 **exit 1**，并伴随 **5 条 Unhandled Rejection**（`Cannot read properties of undefined (reading 'messages')`）。
- **根因**：该 spec 的 `@/api/ai` 桩只在**部分**用例里补 `api.history`；空 messages 挂载时 `loadHistory()` 拿到 `undefined` ⇒ `history.messages` 抛 TypeError，被 promise rejection 吞成 Unhandled Rejection。**不是本批产品逻辑坏**，但 CI 是红的。
- **修法（采纳复审建议一并做）**：①补 `api.history` / `api.runsCurrent` 实值桩；②**把 5 例移出该 flaky 大文件**，新建 `frontend/tests/ai-panel-composer-limit.spec.ts`（M6 一并收）。
- **收口实测**：修前 `vitest exit=1`；修后 `exit=0`、**0 Unhandled Rejection**、521/521 · 27 文件。新 spec 自带三桩（health/history/runsCurrent）+ 冷导入预热（`beforeAll` 30s）+ 用例超时 20s，不再依赖任何其它文件的桩残留。

## 3. Important（3 条）

### I1 · 限流脚本正文没有契约断言

- **问题**：`AiRateLimiterTest` 只验"行为"（20/21、降级、IP 维度），若 Lua 正文被改错（漏 `PEXPIRE`、`ZCARD` 放到 `ZADD` 之后），行为用例仍可能全绿 —— 尤其替身 `FakeRateRedis` 是手写的。
- **修法**：`FakeRateRedis` 补 `lastScriptBody()` 捕获正文；断言含 `RATE_SLIDING_WINDOW`、`'ZREMRANGEBYSCORE', KEYS[1]`、`'ZCARD'`、`'ZADD'`、`'PEXPIRE'`、`'TIME'`，且 `indexOf(ZREMRANGEBYSCORE) < indexOf(ZCARD) < indexOf(ZADD)`。脚本分派标记提为 `AiRateLimiter.SCRIPT_MARK` **单一来源**，桩与单测同取该常量（避免"改脚本忘了改桩"的静默失配）。

### I2 · 限流排在归一化/长度之后（**裁决：方案①，提到最前**）

- **问题**：原顺序"归一化 → 长度 → 限流"⇒ 超长请求与纯不可见字符请求**不消耗额度**；攻击者用超长体做免费试探，正是限流该拦的形态。这还让 `overlongMessageDoesNotConsumeRateQuota` 这条用例把错误行为**锁死**了。
- **裁决**：**防洪优先于"额度洁癖"** —— 所有 `/chat` 一律计数，超长也占额度。
- **修法**：`AiController#chat` 顺序改为 必填 → Redis 探活 → **限流** → 归一化 → 空拒 → 长度；用例按裁决改写为 `overlongMessagesStillGet400AndConsumeTheRateQuota`（先证红：现序下第 21 次为 200），理由是**语义变更经裁决批准**，不是降格（断言方向由"不占"改"占"，强度不减）。
- **附带口径**：由此产生的既定顺序是"Redis 不可用时，超长请求先撞 503 而不是 400"，已写进代码注释与施工报告。

### I3 · E2E 会不会撞限流：显式抬高，不靠运气

- **问题**：E2E 全程 21 次 `/chat`，默认 60 次/分 IP 本可跑通 —— 但用例增长 / 同机并跑就会以 429 变成"看似产品坏"的假红。
- **裁决采纳**：`scripts/verify-e2e.ps1` 与 `.github/workflows/ci.yml` 两处 E2E 后端启动参数显式抬高到实际不可达量级（session 200/分、IP 2000/分），并在头部注明"**限流不在 E2E 覆盖面，由单测覆盖**"。
- **harness 核对**：`scripts/b23l1-dual-instance.ps1` 每次调用都生成新 sessionId ⇒ 单会话 ≤1 次 `/chat`，**不需要**改（已读代码确认）。

## 4. Minor（6 条）

| # | 项 | 处置 |
| --- | --- | --- |
| M1 | `app.ai.limit.*` 无启动期校验：0/负值不是"降级"而是**语义被静默改写**（`count < limit` 恒假 ⇒ 每条 `/chat` 都 429；`PEXPIRE 0` ⇒ 计数永归零、限流静默失效） | **已修**：`AiProperties#verifyRateLimit()` fail-fast 并点名键与当前值；4 例单测（`AiPropertiesLimitGuardTest`） |
| M2 | 前后端 8192 是两处字面量，**无交叉校验** | **登记不改**：改一处漏一处会让两侧说谎；批② 或配置下发时收 |
| M3 | `InputSanitizer` 只处理 `message`，`context.*` 不归一 | **已修（只做注释）**：类注写清 **fail-closed 边界** —— 消费方不得假设 `context` 已归一/可信，未知 `page` 不得原样回显、不得据此放宽工具披露 |
| M4 | `/confirm.reason` 与 `/frontend-tool-result` 载荷**无长度闸** | **登记**：留防护批②③；类注写明"本类的存在不代表全入口已受控" |
| M5 | 计数字符串带 `aria-live` ⇒ **每敲一个字播报一次** | **已修**：计数节点去掉 `aria-live`；独立 `sr-only` live region 只在 `normal/near/limit/over` 四档**跃迁**时播报常量文案（不含剩余数字）；4 例专测 |
| M6 | 新用例塞进 `ai-panel-parts-render.spec.ts`（187 例、时敏）会放大 flake | **已修**：随 C1 拆出独立 spec |

## 5. Nit（4 条，登记不改）

| # | 项 | 处置 |
| --- | --- | --- |
| N1–N4 | Nit 级：措辞/口径类（含注释与命名的边角），无行为落点 | **登记不改**（本轮无代码落点）。原文条目不逐条转述：复审原文未入库，本补档只按编号与处置记录，不代拟内容 |

## 6. 攻击面结论（a–h）

> 条目沿用复审原文编号；结论按最终态代码复述，每条给出代码锚。

| # | 攻击面 | 结论 |
| --- | --- | --- |
| a | **超长请求体**（内存/token 放大、把模型当免费存储） | **已封**：8192 UTF-16 码元上限，超限 400 不截断（`AiController:228`） |
| b | **零宽 / 全角兼容字切词**（`忽\u200B略` 在模型侧可能被读成别的词） | **已平整**：NFKC + `Cf`/孤立代理/非法 `Cc` 剥离；但**只归一不判注入**（`InputSanitizer:75`） |
| c | **换 sessionId 绕会话额度** | **已封**：IP 维度 60/分（`AiRateLimiter:127`） |
| d | **伪造 `X-Forwarded-For` 冒充来源** | **不读 XFF**，只认 `RemoteAddr`（`AiController:663` 注释写明将来上代理的放开条件） |
| e | **固定窗口边界突刺**（第 59 秒 20 次 + 第 61 秒 20 次 = 2× 额度） | **已封**：滑动窗口 ZSET + 窗口外回收（`AiRateLimiter:79` 脚本） |
| f | **多实例下进程内计数放大额度 N 倍** | **已封**：Redis 单键 Lua；时间取 Redis `TIME`，不吃各实例时钟 |
| g | **限流设施宕机把对话一起判死** | **刻意 fail-open**：异常/空返回 ⇒ 放行 + WARN；与 `RedisAvailability` 503 分层（`AiRateLimiter:131`） |
| h | **侧门未覆盖**：`context.*`、`/confirm.reason`、`/frontend-tool-result` | **显式登记**：`context` 走 fail-closed 注释（M3），另两处留批②③（M4）—— 本批**不声称**全入口已受控 |

## 7. 复审时点门禁

| 门禁 | 实测 | 备注 |
| --- | --- | --- |
| 后端 `mvn -q -o test` | 726/726 · 0F/0E | 施工自报口径 |
| 前端 `vitest run` | 517 passed / 26 文件，**exit 1** | 5 条 Unhandled Rejection = C1 |
| 前端 `vue-tsc --noEmit` | 0 | — |
| check-docs | 6/6 | — |

## 8. 遗留与去向

- **必修项去向**：C1 / I1 / I2 / I3 / M1 / M5 / M6 全部落成代码或用例（收口笔 `65f3dc2`），逐条核验见 `-复核确认-DS-Flash.md` §2。
- **登记项去向**：M2（交叉校验）→ 批②；M3（注释）已落；M4 → 批②③；N1–N4 不修。
- **残余风险（已知接受）**：`context` 与另两个入口未受控（h）；限流为"防滥用"而非"准入"，设施抖动时放行（g）。
