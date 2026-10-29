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
