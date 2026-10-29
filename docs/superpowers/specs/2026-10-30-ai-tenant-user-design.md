# AI 对话租户与用户隔离设计

## 目标与边界

AI 对话域增加 `tenantId/userId`，保证会话、运行态、浏览器存储和异步执行不串身份。业务数据与 AI 模型配置（provider/model/setting/audit/KB）不做租户隔离；不增加登录或权限系统。

预置少量租户，每个租户有不同用户。前端可切换身份，所有 AI 请求携带 `X-Tenant-Id`、`X-User-Id`。

## 身份与上下文

- Filter/Interceptor 校验身份并建立上下文，请求结束 `finally` 清理。
- `RequestContextUtils` 提供 `getTenantId()`、`getUserId()`、`useContext(long tenantId,long userId)`。
- `useContext` 返回实现 `AutoCloseable` 的 `SafeCloseable`，关闭时恢复之前的状态，支持嵌套与幂等关闭；`userId=-1` 只供系统任务，HTTP 请求不得使用。
- 缺少上下文直接失败，不允许默认租户或默认用户。

普通异步使用阿里 TransmittableThreadLocal/统一装饰器；Reactor/SSE 使用专门适配。不能自动传播的 HITL、定时任务、取消、看门狗、启动恢复、跨实例接管，统一从 RunSnapshot/Job 读取身份并调用 `useContext`，即使当前业务尚未读取租户字段也接入。所有任务按记录恢复身份，禁止扫描租户列表。

## 会话 ID

新增后端建会话接口，返回 `long`。使用时间戳 + 实例内原子序列 + 实例标识，避免同毫秒和多实例重复。前端不再生成 sessionId；切换租户或用户后调用后端取号并展示新会话，旧会话保留在原身份下，不删除、不混用。复制页签、接管、刷新和 410 恢复均走同一取号与身份校验路径。

## Redis 隔离

所有对话数据 key 纳入 `tenantId/userId`：chat:mem、ai:session:*、ai:run/pending/ctx/events/xevents/seq/claim/req/decided/fence/lease/ledger/idem/beat/activity/cancel/observed/form/purge/rate:wake 等。run 级键继续使用同一个 hash tag，tag 同时含租户、用户和 runId，保持 13 键原子删除及全部多键 Lua 同槽。

全局协调索引只保存复合成员 `tenantId/userId/recordId`，用于按记录恢复身份，不通过遍历租户发现任务。全局调度锁、模型配置、KB、业务数据及任务/作业锁保持现状。无归属 legacy 键不得在新身份路径回落读取，避免旧数据跨身份泄露。

浏览器镜像、会话 ID、表单草稿和页签所有权消息都带租户/用户指纹；不同身份不得接管或复用镜像。

## ID 与归属约束

- AI 对话域身份相关的唯一性以 `(tenantId, userId, objectId)` 为准；`sessionId/runId/toolCallId` 均不得作为跨租户唯一性或归属证明。
- 只带 runId 的 API（事件、取消、续跑、待决等）必须同时读取快照并核对当前 tenantId/userId，不能只凭 runId 操作。
- 会话发号即使在实现上全局不重复，也不得据此省略 key、索引、锁或查询中的 tenantId。
- 附件、销毁、重试和 Redis Pub/Sub 同样按复合身份校验与隔离。

## 验证

- 两租户、每租户两用户交叉执行，验证记忆、运行、HITL、附件、事件和销毁不串。
- 同毫秒、多实例发号不重复。
- 普通线程池、@Async、CompletableFuture、Reactor、SSE、定时、启动恢复和跨实例接管均恢复原身份。
- 静态扫描禁止租户列表循环、未包装执行器及无归属 legacy 回落。
- 仅执行精确单点测试；主控只做代码验收，分批提交推送。
