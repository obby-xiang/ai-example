# Task 1 报告：身份上下文与后端会话发号

- 状态：**完成**（含下文「顾虑」中的兼容性取舍）
- 代码提交：`cc4983ba`（`feat(ai): 租户用户身份上下文与后端会话发号（租户任务1）`）
- 范围：仅 AI 对话域的身份上下文与发号；未改 Redis 键族、未改前端、未改业务/Job/Task/AI 配置/KB。

## 交付内容

| 项 | 落点 |
| --- | --- |
| 不可变身份 | `ai/tenant/TenantUser.java`（record；`-1` 仅系统账号） |
| 身份上下文 | `ai/tenant/RequestContextUtils.java`（TTL 基座；`getTenantId/getUserId/useContext`；嵌套还原、无旧值清理、幂等关闭、跨线程关闭拒绝、缺失 fail-closed） |
| 传播基座 | `ai/tenant/RequestContextTaskDecorator.java`（`TtlRunnable`）；`AiExecutorConfig` 把 MDC + TTL 组合接入 `aiRunExecutor`/`aiResumeTrigger` |
| HTTP 边界 | `ai/tenant/TenantIdentityFilter.java` + `config/WebConfig` 注册；`X-Tenant-Id`/`X-User-Id`，long 正数，`userId=-1` 拒绝，目录组合校验，`finally` 清理；CORS 预检放行；模型配置/健康/工具/身份目录/startup-resume 不纳入 |
| 租户用户目录 | `ai/tenant/TenantUserDirectory.java`（tenant1: 101/102；tenant2: 201/202）+ `GET /api/ai/tenants` |
| 后端发号 | `ai/session/SessionIdGenerator.java`：`ts×10^6 + 实例位×10^3 + 序列`，同毫秒/多实例不重复；`POST /api/ai/session` 返回 `long`；`AiSessionService.ensure(..., tenantId, userId)` 登记归属（键族不变） |
| 快照归属 | `ai/run/RunSnapshot.java` 增加 `tenantId/userId` 并入 `asMap()`；`RunStore.create` 首轮从上下文固定（真实 JSON 持久化），续跑可读；测试替身 `InMemoryRunStore` 同口径 |
| 文档 | `docs/superpowers/specs/2026-10-30-ai-tenant-user-design.md`、`docs/superpowers/plans/2026-10-30-ai-tenant-user.md` 纳入提交 |

## TDD 红/绿命令（均为单方法，未跑整类/批量）

Maven：`<MAVEN_HOME>/bin/mvn.cmd`（IntelliJ 自带 maven3；无 `clean`），统一附 `-Djacoco.skip=true`。

| 方法 | 红（预期失败） | 绿 |
| --- | --- | --- |
| `RequestContextUtilsTest#nestedUseContextRestoresOuterIdentityAndClearsAfterOutermost` | 编译失败：`包 com.example.configmgr.ai.tenant.RequestContextUtils 不存在` | 通过 |
| `RequestContextUtilsTest#closeIsIdempotentAndNeverTouchesAnotherScope` | `IllegalStateException：缺少租户/用户身份上下文`（stale close 清掉了新作用域） | 通过 |
| `RequestContextUtilsTest#closeFromAnotherThreadIsRejected` | `Expecting actual not to be null`（未抛跨线程拒绝） | 通过 |
| `RequestContextUtilsTest#systemUserIsTheOnlyNonPositiveUserAllowed` | `IllegalArgumentException：userId 必须是正数...：-1` | 通过 |
| `TenantUserDirectoryTest#validatesTheTenantUserCombinationNotJustTheTwoNumbers` | 编译失败：`找不到符号 TenantUserDirectory` | 通过 |
| `TenantIdentityFilterTest#missingIdentityHeadersFailClosedBeforeTheChain` | 编译失败：`找不到符号 TenantIdentityFilter` | 通过 |
| `TenantIdentityFilterTest#systemUserAndNonPositiveTenantAreRejectedOverHttp` | 同上前置（类缺失） | 通过（实现后重跑） |
| `TenantIdentityFilterTest#unknownTenantUserCombinationIsRejected` | 同上 | 通过（实现后重跑） |
| `TenantIdentityFilterTest#validIdentityIsBoundForTheChainAndClearedAfterwards` | 同上 | 通过（实现后重跑） |
| `TenantIdentityFilterTest#modelConfigEndpointsAreOutsideTheTenantBoundary` | 同上 | 通过（实现后重跑） |
| `SessionIdGeneratorTest#sameMillisecondIdsDoNotRepeatWithinOneInstance` | 编译失败：`找不到符号 SessionIdGenerator` | 通过 |
| `SessionIdGeneratorTest#differentInstancesInTheSameMillisecondDoNotRepeat` | 同上前置（类缺失） | 通过 |
| `RunSnapshotTenantIdentityTest#createFixesTheTenantAndUserOfTheTurnFromTheContext` | 编译失败：`找不到符号 方法 getUserId()` | 通过 |
| `RunSnapshotTenantIdentityTest#realStoreWritesTheIdentityIntoThePersistedSnapshotJson` | 验收补充（真实 RunStore + FakeLeaseRedis 持久化断言） | 通过 |
| `AiSessionControllerTest#createsTheSessionNumberOnTheBackendAndRegistersTheCurrentIdentity` | 编译失败：`ensure` 参数列表不匹配（方法尚未存在） | 通过 |
| `RequestContextPropagationTest#decoratorCarriesTheIdentityIntoThePoolThread` | 传播基座验收补充 | 通过 |
| `AiMdcPropagationTest#productionSuspendPoolIsWiredWithTheDecorator` | 回归（组合装饰后 MDC 仍在） | 通过 |
| `AiConfigUnavailableSpringTest#chatStillReturns503WithoutBuildingAStream` | 契约适配（真实 Spring 上下文验证过滤器生效后 503 不变） | 通过 |

验证收尾：
- `git diff --cached --check`（提交前）与 `git diff --check`：均无输出。
- `mvn -q -DskipTests test-compile`：通过（exit 0）。
- 未执行任何整类/批量/循环测试，未 `clean`，未推送。

## 顾虑

1. **`RunStore.create` 缺上下文不抛异常**：当前实现是「有上下文就固定，无上下文保持 `tenantId/userId=0`」。这是为了不打断大量直接调用 `create` 的存量单测与旧快照语义；fail-closed 落在 `RequestContextUtils` 访问器与 HTTP 边界。生产首轮经过滤器 + TTL 装饰一定有身份。建议 Task 3 在驱动入口统一 `useContext` 后，把 `create` 收口为强制身份并迁移剩余直调测试。
2. **`long sessionId` 超出 JS `Number.MAX_SAFE_INTEGER`**：JSON 数字到达前端会丢精度（约 1.7e18）。Task 4 必须用 `BigInt`/原始文本或改为字符串传输后再解析；本批按需求返回 `long`，未擅自改类型。
3. **实例位默认是 `InstanceId` 散列（0..999）**，多实例存在 1/1000 碰撞概率；多实例部署应显式配置 `-Dapp.instance-slot` / `APP_INSTANCE_SLOT`。同毫秒单实例上限 1000 个号，超出会等下一毫秒（1s 超时后抛错）。
4. **前端与 E2E 尚未带身份头**：现在 `/api/ai` 对话数据面缺头会 400。Task 4（前端）与 Task 5（E2E 脚本）必须同步；`scripts/*.mjs`、`scripts/verify-e2e.ps1` 里直接打 AI 接口的请求会先红。
5. **chat 仍接受旧格式 sessionId**：本批只提供后端发号与归属登记，不在 chat 强制「必须是后端号/必须属于当前身份」，以免前端迁移前整站不可用；键形隔离与归属核对由 Task 2 接手，前端切号由 Task 4 接手。
6. **其余执行器未接 TTL**：摘要旁路、SSE 投递、看门狗、定时任务、启动恢复、跨实例接管仍未统一装饰/`useContext`（本批按要求不铺开）；Task 3 必须逐项接入，否则这些路径读身份会 fail-closed 失败。
7. **未做真实 HTTP + Redis + LLM 全链路**：本批以精确单点 + 一个 `@SpringBootTest`（过滤器注册、身份头、503 语义）验证；未启动前端，未验证 `/api/ai/session` 的真实网络往返（控制器单测已覆盖返回值与登记调用）。
8. **存量测试改写**：`AiConfigUnavailableSpringTest#chatStillReturns503WithoutBuildingAStream` 增加了身份头（契约变化所需），断言保持不变。

---

# Task 1 fix round 1（I1/I2/I3/I5/I6）

- 状态：**完成**（C1/C2/C3 按指令留给其他组，本批未触碰 `RunStore`/`AiSessionService`/`ToolExecutionEnvelope`/`AiExecutorConfig`/前端）
- 修复提交：`19b753d9`
- 改动面（仅限指令允许的 4 个源文件 + 对应测试）：
  `ai/web/AiSessionController.java`、`ai/session/SessionIdGenerator.java`、
  `ai/tenant/TenantIdentityFilter.java`、`config/WebConfig.java`；
  测试 `AiSessionControllerTest`、`SessionIdGeneratorTest`、`TenantIdentityFilterTest`、新增 `config/TenantCorsOrderTest`。

## 修复摘要

| 项 | 处理 |
| --- | --- |
| I1 | `POST /api/ai/session` 响应同时返回 `sessionIdText`（十进制字符串，**权威值**）与 `sessionId`（long，仅兼容）；类注明确消费方禁止把 JSON number 当权威值。 |
| I2 | 实例位只来自 `app.instance-slot` / `APP_INSTANCE_SLOT`（0..999）；单实例默认 0；`cluster`/`multi-instance`/`h2tcp`/`postgresql` profile 未配置即**启动失败**；删除 `InstanceId` 散列兜底；启动日志打印 `instanceSlot` 与多实例判据。 |
| I3 | 取号锁只覆盖状态读写，所有等待移到锁外（`LockSupport.parkNanos`）；大步回拨（>1s）立即抛 `SessionIdGenerator.UnavailableException`；小幅回拨限 1s 窗口等待；序列耗尽约 100ms 后失败；控制器映射 **503** `SESSION_ID_UNAVAILABLE` 且不登记会话。 |
| I5 | `corsFilter` 改为 `FilterRegistrationBean<CorsFilter>`，order = `HIGHEST_PRECEDENCE + 5`（早于身份过滤器 +20）；单点验证身份拒绝的 400 带 `Access-Control-Allow-Origin` / `-Credentials`。 |
| I6 | 排除面改为「精确路径（health/tools/tenants/startup-resume(+scan)）+ 模型配置子树（providers/models/settings）」；`/api/ai/tools/...` 等子路径与 `/api/ai` 本身默认受保护，缺头即 400。 |

## 红/绿命令（每项一个精确方法；未跑整类/批量）

> 共享工作区在本轮期间被 Task 2 在制品改动（`AiSessionService`/`RunStore` 等未完成，整体不可编译），
> 为遵守「不触碰他人文件、不整类测试」，以下单点在实际代码等价的工作树 `<WORKTREE>`（`cc4983ba` + 本批补丁）执行；
> 随后仅把本批 8 个文件同步回主仓。Maven：`<MAVEN_HOME>/bin/mvn.cmd`，统一 `-Djacoco.skip=true`。

| 方法 | 红（预期失败） | 绿 |
| --- | --- | --- |
| `SessionIdGeneratorTest#aLargeClockRollbackFailsFastWithAnUnavailableSignal` | 编译失败：`找不到符号 类 UnavailableException` | 通过 |
| `SessionIdGeneratorTest#clusterDeploymentRequiresAnExplicitAuditableInstanceSlot` | 编译失败：`SessionIdGenerator(MockEnvironment)` 无合适构造器 | 通过（日志可见 `instanceSlot=0/3`） |
| `AiSessionControllerTest#createsTheSessionNumberOnTheBackendAndRegistersTheCurrentIdentity` | 编译失败：`ApiResponse` 无法转 `ResponseEntity`；`UnavailableException` 构造器非 public | 通过 |
| `AiSessionControllerTest#unavailableNumberingIsMappedToServiceUnavailable` | 同上前置（类型/可见性） | 通过（503 + `SESSION_ID_UNAVAILABLE` + 不登记） |
| `TenantCorsOrderTest#corsFilterRunsBeforeIdentityFilterAndRejectionsCarryCorsHeaders` | 编译失败：`CorsFilter` 无法转 `FilterRegistrationBean` | 通过（400 带 CORS 头） |
| `TenantIdentityFilterTest#onlyConfigSubtreesAndExactNonDataPathsEscapeTheBoundary` | 断言失败：`/api/ai/tools/secret` 进入了业务链 | 通过 |
| 回归（逐个单点） | — | `TenantIdentityFilterTest` 其余 5 个方法、`SessionIdGeneratorTest` 同毫秒/多实例 2 个方法：全通过 |

## 顾虑

1. **多实例 profile 现在强制 `APP_INSTANCE_SLOT`**：`cluster`/`multi-instance`/`h2tcp`/`postgresql` 任一激活且未配置实例位，应用启动失败。CI/E2E 脚本与部署清单（不在本批改动面）必须补该环境变量，且**各实例取值不同**；单进程无法校验"不同"，只能靠启动日志审计。
2. **`sessionIdText` 迁移未完成**：后端已给权威字符串字段，但 Task 4 前端与 Task 5 脚本需切到该字段；`sessionId`(long) 仅为兼容保留。
3. **序列耗尽无单点**：验证耗尽需在同一毫秒发出 1000 个号（循环），与"禁止循环测试"冲突；现由代码审查保证"锁外等待 + 有限次后 503"，并有控制器 503 映射单点。
4. **本轮未在主仓跑测试**：主仓被 Task 2 在制品改动占住且不可编译；本批单点在等价工作树通过。主仓集成分支恢复可编译后，建议由主控复跑上述方法。
5. **C1/C2/C3 不在本批**：按指令由其他组处理（执行信封身份、写路径 fail-closed、入口归属校验）。其中 C3 的 chat `existed` 校验依赖 `AiSessionService`，本批未触碰。
6. **CORS 顺序单点只覆盖 `http://localhost:5173` 允许源**：生产允许源仍沿用 `http://localhost:*` / `http://127.0.0.1:*`，未扩大。
