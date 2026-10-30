# AI 对话关键稳定性修复施工计划

## Context

浏览器复验仍有两项失败：已知 CNY 条件仍弹表单，以及创建导出任务偶尔未到启动确认。只读审查还发现多实例帧序号重分配导致漏帧、SSE 身份异常后永久落入 legacy。用户已批准按主控方案修复，并要求能并行的并行、不得冲突。

## Global Constraints

- 主仓 `<仓库根>`，直接在 main 工作树施工是用户既有明确裁决。
- 帧顺序只能由 Redis 在多实例间协调；禁止用 `IdGenerator`、数据库、实例本地发号保证顺序。
- 业务帧 seq 要求每 run 唯一、单调、按归档顺序交付，允许跳号，不要求 +1 连续。
- 不自动执行导出作业；`start_export` 只发起既有确认卡。用户拒绝、取消、明确只推进或工具失败时不做闭环纠正。
- 条件未知才收集；已知条件不弹表单；非空条件必须真实落库；明确留空才代表全量；多配置逐项判断。
- 每轮闭环纠正最多一次，不得形成模型循环或绕过任何确认。
- 严格 TDD：每项生产行为先有精确单用例 RED，再最小实现 GREEN。禁止整类、整文件、批量或循环跑测试；禁止 `clean`。
- 子 agent 不提交、不推送、不再派子 agent；主控逐批代码验收后明确文件提交推送。
- 不修改任务 1–4 清单以外的生产文件；文档与 D6 复核记录由任务 5 统一处理。

## Task 1 — 导出条件与一轮闭环

**文件面**：`backend/src/main/java/com/example/configmgr/ai/form/**`、`ai/slot/**`、`ai/tool/ContextBuilder.java`、`ai/run/ResilientChatService.java`、`ai/run/ResumeService.java`、`ai/gate/SpToolCallingManager.java`、`ai/gate/ToolPreflightService.java`、`ai/tools/AiTools.java`、`resources/ai/tools.yaml` 及对应测试。

1. 建立统一条件判定，区分已设置、明确不设、未决定/无法解析；读取全部配置项，支持 EQ 以外现有条件结构和 scopeKeys，禁止把无法解析写成全量。
2. 模型和宿主 SET_CONDITIONS 表单按 run/task/配置项共享结果；非空回灌未落库时零副作用拒绝 confirm_step 并指回 set_condition；已知未落库同样指回 set_condition，不弹表单；留空才允许按全量推进；取消不换卡重问。
3. 多配置逐项核对，禁止任一有条件即整体放行。AI confirm_step 禁止 SELECT_DEFS 跳 EXPORT；直接 start_export 使用相同步骤/条件预检。
4. 删除 ContextBuilder 与系统提示中互相矛盾的条件/收尾口径。
5. 在原导出意图已进入 EXPORT、start_export 已披露、尚未发起且未拒绝/取消/明确只推进时，收尾前最多补一次内部纠正，要求发起 start_export 确认卡；不得自动执行。
6. 宿主表单标题/标签/占位复用 ReplyLanguage 与既有 i18n，不新增翻译体系。
7. 覆盖 CNY 已知、空条件、非空未落库、重复表单、多配置部分条件、IN/scopeKeys、明确不筛选、跳步、EXPORT 收尾未发起、已拒绝/取消等精确用例。

## Task 2 — Redis 原子帧顺序与 SSE 身份

**文件面**：`RedisFrameStore.java`、`SseChatEmitter.java`、必要的回放状态后端接口及对应测试；不得改 `frontend/**`。

1. 将最终 seq 分配与 Stream 归档收敛为 Redis 原子过程，取消“先取低号、被越过后改高号”的多实例竞态；禁止使用数据库或 IdGenerator。
2. 只有实际归档成功才向订阅者交付并推进 wakeFloor；归档序号必须等于交付序号，不允许未来出现更低号的新归档帧。
3. 超时/回包未知按精确 Stream ID 与本次出帧内容判幂等，不得只查表尾；保留降级可观测，但不得在 Redis 不可用时宣称多实例顺序成立。
4. `deliveryIdentity()` 读取异常不得缓存 null 或写 legacy；可使用已校验 hint 或明确失败，下拍允许重试。
5. 更新旧注释：生产允许多实例跳号，单实例连续 conformance 不等于跨实例连续保证。
6. 覆盖两个写出器竞争、低号尚未归档、高号先交付、XADD 成功但回包失败、Redis 身份读取一次失败后恢复、重挂差量等精确用例。

## Task 3 — 前端身份、旧轮与提示

**文件面**：`frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/**`、必要的 `api/sse.ts` 类型与对应测试；不得改后端。

1. 切身份前按旧身份 best-effort cancel 旧 run；本地切换不等待后端，不重复工具。
2. 清输入草稿、编辑锚点和已失效旧身份镜像；旧后端历史保留。
3. retryModelStep、HITL、回灌的成功/失败出口统一轮次与身份守卫；内部错误原文不上屏。
4. 发号失败重试成功后统一补所有权认领；stop finally 只清理属于自己那次停止的闸门。
5. 恢复挂起轮复用 findInFlightHost，禁止污染上一轮；ownership 广播固定捕获身份。
6. 不再用 `firstSeq > lastSeq + 1` 推断归档裁剪，只消费后端明确截断状态；若任务 2 接口尚未提供，先隔离适配点并报告，不自行改后端。
7. 覆盖切换在途轮、跨身份草稿、旧重试失败、发号重试复制页签、连续停止、正常跳号等精确用例。

## Task 4 — 后端独立小修复

**文件面**：`FrameWakeListener.java`、`SessionGate.java`、`AiSessionService.java`、`AiSessionController.java` 及对应测试；不得碰 Task 1–3 文件。

1. 唤醒订阅失败与并发登记时按桶完整回滚，后续目标不得误记已订阅。
2. SessionGate 成功取锁/重入后清 degraded。
3. 同身份毫秒会话号碰撞做有界重新取号；仍只使用 IdGenerator 当前时间戳接口，不在此处自研顺序号。Redis 不可用且无法登记时必须返回不可用，不得 200 返回未登记会话。

## Task 5 — 文档、验收与推送

1. 主控逐批只读验收，失败则 resume 原实现者返修；主控不跑测试。
2. 更新 D6 的 Redis 序号、条件闭环、身份切换契约与 `d6-review.json`；每次生产提交使用有效本次确认。
3. 明确文件分批提交并推送，不夹带临时产物。
4. 重启服务后用本机 Chrome 复验三个导出场景及身份切换，每个至少两遍；先列预期再核对。
5. 最新生产提交上的 GitHub Actions 与真机 E2E 结果单独报告，不用旧 SHA 证据冒充当前全绿。

## Pre-flight 冲突表

| 任务对 | 共享面 | 裁决 |
|---|---|---|
| 1 / 2 | 无共享生产文件；导出闭环消费帧契约但不改帧实现 | 可并行 |
| 1 / 3 | 后端表单与前端展示接口 | 保持既有 schema，只改语言值与流程，可并行 |
| 2 / 3 | 后端回放截断状态与前端消费 | Task 2 保持既有响应并补明确字段；Task 3 只读该字段，缺失则报告，不改后端 |
| 2 / 4 | 同属后端但文件 disjoint | 可并行 |
| 3 / 4 | 会话发号前后端 | Task 4 负责碰撞重试，Task 3 不自造会话号，可并行 |
| 5 / 1–4 | D6 与确认记录依赖最终 diff | 最后串行 |

Ruling: 使用主仓 main 而非新 worktree — 用户此前明确要求后续都在主仓；错误代价是可按提交逐一回退。
Ruling: 允许文件面完全 disjoint 的实现 agent 并行 — 用户明确要求能并行的并行；所有 git 写操作由主控串行。
Ruling: 不执行全量测试 — 用户禁止批量测试；以每个精确单用例 RED/GREEN 和后续既有 CI 为验证边界。
