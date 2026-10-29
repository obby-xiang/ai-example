# AI 对话租户与用户隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 对话域完整支持租户/用户隔离、后端 long 会话发号与全链路上下文恢复。

**Architecture:** HTTP 边界建立身份上下文；普通异步由 TTL/装饰器传播，恢复型任务按快照身份 `useContext`。所有对话数据 key 带身份，run 级 hash tag 保持同槽；前端切换身份向后端取新号。业务与模型配置不隔离，不扫描租户列表。

**Tech Stack:** Java 21、Spring Boot、TransmittableThreadLocal、Redis、Vue 3、TypeScript。

**Spec:** `docs/superpowers/specs/2026-10-30-ai-tenant-user-design.md`

## Global Constraints

- 仅 AI 对话域；业务数据和 AI 模型配置不做租户隔离。
- 所有对话 Redis key、全局索引成员和浏览器会话存储带 tenantId/userId。
- 不扫描租户列表；定时/恢复按记录身份执行。
- 不能自动传播的入口一律 `useContext`，即使当前未读身份字段。
- 只能精确单点测试；主控代码验收后立即提交推送。

## Review Focus / 全覆盖矩阵

- 身份：两租户、每租户两用户；同租户不同用户、跨租户同用户ID、跨租户相同session/run/toolCallId、非法身份组合、缺失/伪造系统账号。
- 接口：chat/history/context/runs/current/feedback/delete/attachments/SSE，以及只带runId的events/cancel/resume/pending；全部验证归属，不只验证key形状。
- Redis：逐族验证40个前缀的写读删、全局复合索引、staging、legacy拒绝、purge重试、附件、wake频道；13键同槽与所有多键Lua。
- 前端：首次取号、切换租户、切换用户、复制页签、同身份接管、跨身份拒绝、刷新、410、旧响应丢弃、旧镜像/草稿保留且不混用。
- 异步：普通线程池、@Async、CompletableFuture、Reactor/SSE、HITL、取消、超时、重试、看门狗、启动恢复、跨实例接管；验证身份恢复和嵌套清理。
- 边界：同毫秒/多实例发号、系统账号-1、无上下文fail-closed、无归属legacy、不得出现租户列表循环。
- 每项均以独立精确单点测试覆盖；不得用同源自证或空断言替代真实行为。

### Task 1: 身份上下文与后端会话发号

- [ ] 新增不可变身份上下文、RequestContextUtils、SafeCloseable、HTTP Filter/Interceptor。
- [ ] 增加 TTL 依赖与统一执行器装饰/适配，缺失身份 fail-closed。
- [ ] RunSnapshot 增加 tenantId/userId，创建时固定；所有 AI HTTP 入口校验归属。
- [ ] 新增后端建会话接口，long 时间戳+实例序列+实例位发号。
- [ ] 单点覆盖嵌套还原、系统账号、缺失拒绝、同毫秒发号。

### Task 2: 全部对话 Redis 键族隔离

- [ ] chat:mem 与 session/run/ctx/events/form/purge/rate/wake 全部数据键带身份。
- [ ] run 级 hash tag 同时含 tenant/user/runId，保持13键删除与全部Lua同槽。
- [ ] 全局索引仅存复合成员，按记录恢复，不引入租户列表扫描。
- [ ] 无归属 legacy 键不进入新身份读路径；同步迁移辅助与键形契约。
- [ ] 单点覆盖两租户两用户、键形、同槽、全局索引隔离。

### Task 3: 全部异步与恢复入口

- [ ] 普通线程池/@Async/CompletableFuture 接 TTL或统一装饰器。
- [ ] Reactor/SSE/官方工具线程通过信封恢复上下文。
- [ ] HITL、取消、超时、重试、看门狗、启动恢复、跨实例接管全部 useContext。
- [ ] 不增加业务/模型配置租户维度，不扫描租户列表。
- [ ] 单点覆盖异步、定时、续跑、跨实例身份恢复与嵌套清理。

### Task 4: 前端身份切换与存储隔离

- [ ] 增加租户/用户选择及统一身份请求头。
- [ ] 切换即调用后端取新会话，旧会话保留在原身份下，不跨身份接管。
- [ ] 复制页签、刷新、410恢复统一取号；所有请求等待会话就绪。
- [ ] 镜像、草稿、页签锁和所有权消息带身份指纹。
- [ ] 单点覆盖切换新建、隔离、复制页签与旧响应丢弃。

### Task 5: 分批验收与交付

- [ ] 每批主控只做代码验收，通过立即提交推送。
- [ ] 静态扫描无遗漏键族、无租户列表循环、无未包装执行器。
- [ ] 同步现行文档与测试，不留下旧单用户口径。
