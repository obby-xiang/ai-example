# Cancel Reliability and Purge Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让首次取消在有界重派与重挂自愈下可靠完成，同时显著减少会话销毁的固定及逐 run Redis 往返，并用状态驱动的压测脚本验证真实终帧。

**Architecture:** 取消侧复用既有 `CancelledRunFinalizer`、`ConfirmGate` 有界重派和 `ResumeService.cancelConfirmed`，不新建第二套取消逻辑。DELETE 侧将阶段续租改为单键 Lua、批量独立枚举读取及 run pending/索引操作，保持现有一致性边界。压测脚本以 RunSnapshot 状态迁移为前置，再读取真实 cancelled done。

**Tech Stack:** Java 21、Spring Boot、Spring Data Redis/Lettuce Cluster、JUnit/Mockito、Node.js built-in fetch/test。

**Spec:** 本文件即施工规格；依据第九/十轮真实压测及源码定位结论。

## Global Constraints

- 不放宽 `done{cancelled:true}` 断言，不接受 generative_form 作为终帧，不调用模型完成取消。
- 普通批准/frontend 续跑不得因新增取消分支而重复驱动；只有确认取消旗标为 CANCELLED 时才走取消专用补偿。
- 不削弱租户/用户隔离、owner/epoch/fence、活跃 run 保护、防写回 guard、durable pending、删除后复核和幂等清理。
- 不通过异步返回、提前 complete、删除发现键提前或隐藏错误来制造性能改善。
- 仅执行指定单点测试文件，不运行批量/全量测试，不启动或停止服务，不操作浏览器/CU。
- 独立代码审核通过后才提交推送；未真实复测前不得宣称运行时通过。

## Review Focus

- 取消旗标已写但 `ACTIVE_LATCH_IN_PROCESS` / `IN_PROGRESS`：必须仍有界完成，而非永久等待。
- 普通非取消轮遇到同型竞争：不得新增重复模型驱动。
- 重挂仍 PENDING 且已取消：必须触发幂等取消收尾，而不是仅按普通 park 收流。
- DELETE 部分失败/延期：发现键与 pending 必须保留供重试，不能为减少命令而提前完成。
- Cluster 跨槽键：只能 pipeline 分组，不得强行放入跨槽 Lua。
- 压测状态长期不迁移：必须明确报取消未驱动并保留 cancel 响应证据，不能只延长时间。

---

### Task 1: 首次取消的有界重派与重挂自愈

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/ResumeDispatchRetryTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/web/AiControllerEventsParkCloseTest.java`

**Interfaces:**
- Consumes: `CancellationRegistry.cancelState(runId)`、`CancelledRunFinalizer.handOff(runId, cause)`、`ResumeService.cancelConfirmed(runId)`。
- Produces: 取消专用有界补偿；普通 `triggerResumeForParked` 的非取消语义不变。

- [ ] **Step 1: 写证红用例**
  - `ResumeDispatchRetryTest`：取消旗标为 CANCELLED，首次结局 `IN_PROGRESS`，后续 `ALREADY_CANCELLED`；断言有界重试并完成，而非停首发。
  - 同文件增加非取消对照：相同 `IN_PROGRESS` 不得因新分支变成额外模型续跑。
  - `AiControllerEventsParkCloseTest`：SUSPENDED + PENDING + CANCELLED 旗标，重挂应调用幂等取消收尾，不只 completeSubscriptions；普通 PENDING 未取消仍按原收流。
- [ ] **Step 2: 分别运行两个测试类，确认旧实现按预期失败**
  - `mvn -Dtest=ResumeDispatchRetryTest test`
  - `mvn -Dtest=AiControllerEventsParkCloseTest test`
- [ ] **Step 3: 实现最小生产修复**
  - `ConfirmGate.runResume`：仅在取消事实明确时补偿 `IN_PROGRESS`；遇到活跃 latch 的取消走既有取消 finalizer，而非按普通续跑放弃。
  - `AiController.events`：已取消的挂起重挂触发幂等取消收尾；复用现有 finalizer/写门，不另写 done。
  - 保持普通批准路径、租约释放和错误分类不变。
- [ ] **Step 4: 重跑同两个测试类，确认通过**
- [ ] **Step 5: 主控只读复核取消路径与测试证据**

### Task 2: 降低 DELETE 固定与逐 run Redis 往返

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/session/SessionPurgePendingStoreTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeServiceTest.java`

**Interfaces:**
- Consumes: 现有 purge 编排、`RunStore.deleteRun`、pending owner/epoch 校验和进程内 Redis 测试替身。
- Produces: 单次续租脚本、批量存在性读取、批量 pending 读取和批量索引清理；公开 API 形状不变。

- [ ] **Step 1: 增加证红/防回归用例**
  - `SessionPurgePendingStoreTest`：续租仍严格校验 owner/epoch/purgeId/活租，但不需 Java 侧先 GET 再写；换主/过期仍失败。
  - `AiSessionPurgeServiceTest`：完整销毁结果不变，独立存在性读取与 run pending/索引操作使用 pipeline；错误仍进入 errors/deferred，发现键只在主体成功后删除。
- [ ] **Step 2: 分别运行两个测试类，确认新增性能断言在旧实现失败**
  - `mvn -Dtest=SessionPurgePendingStoreTest test`
  - `mvn -Dtest=AiSessionPurgeServiceTest test`
- [ ] **Step 3: 实现最小性能修复**
  - 用单键 Lua 在 Redis 内读取、校验并更新续租字段，替换每个 checkpoint 的 GET+EVAL 两次往返。
  - pipeline 合并 transcript/context/session/draft 等独立 `hasKey` 枚举；保持原短路 found 语义。
  - pipeline 合并 run pending + request/decision marker 读取及可独立索引清理；13 键原子 DEL 与必要活跃状态复核不变。
- [ ] **Step 4: 重跑同两个测试类，确认通过**
- [ ] **Step 5: 主控只读复核命令次数下降与正确性边界**

### Task 3: 压测脚本改为状态驱动并保留取消证据

**Files:**
- Modify: `scripts/perf/ai-api/core-coverage.mjs`
- Modify: `scripts/perf/ai-api/load-support.mjs`
- Modify: `scripts/perf/ai-api/load-support.test.mjs`
- Modify: `scripts/perf/ai-api/README.md`

**Interfaces:**
- Consumes: `GET /api/ai/events/{runId}?probe=1` 的 `status`、`GET /api/ai/cancel/{runId}` 的旗标、POST cancel 的 `statusBeforeCancel/parkedResume/cancelFinalize`。
- Produces: 有界状态轨迹、首次取消真实 done 断言；EOF 重挂只作兜底。

- [ ] **Step 1: 增加纯函数测试**
  - 状态轨迹能识别 CANCELLED、其它终态、超时和 degraded；终态停止探测，非终态有界继续。
  - 先运行 `node --test scripts/perf/ai-api/load-support.test.mjs` 确认新增断言失败。
- [ ] **Step 2: 实现状态驱动流程**
  - suspended 阶段读到本批流结束，避免半批游标。
  - cancel 后记录 `statusBeforeCancel/parkedResume/cancelFinalize/redisFlagWritten/alreadyCancelled/degraded`。
  - 有界轮询 probe 到 CANCELLED/其它终态，再读取原断言 done；长期 SUSPENDED + 旗标已写时明确报“取消已受理但未驱动”，不伪造成功。
  - 不依赖第二次取消，不把仅增加等待时间当修复。
- [ ] **Step 3: 重跑同一 Node 测试文件和 `node --check`，确认通过**
- [ ] **Step 4: README 写明状态驱动、证据边界和不得用第二次取消判通过**
- [ ] **Step 5: 主控只读复核压测与生产契约一致**

### Final Review and Delivery

- [ ] **Step 1: 三个任务分别由独立 reviewer 只读复核，失败则返工**
- [ ] **Step 2: 核对变更范围、文档过期注释和 git diff --check**
- [ ] **Step 3: 仅提交生产修复、压测脚本及本计划，不包含任何 Demo 文件**
- [ ] **Step 4: 推送 origin/main，并提供第十一轮真实复测提示词**
