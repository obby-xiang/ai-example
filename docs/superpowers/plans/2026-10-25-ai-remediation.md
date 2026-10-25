# AI Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用确定性小规模复现证明已审查出的 AI 缺陷，只修复被证实的问题，再统一完成全量与真实 CI 验证。

**Architecture:** 以主仓当前未提交候选为唯一合流基线，各实现 agent 在独立副本工作。按文件所有权并行，ResumeService/ConfirmGate/RunStore 等交叉文件的最后串行处理；只允许控制器合流，不允许 agent 提交或推送。

**Tech Stack:** Java 21 / Spring Boot / JUnit 5 / Flyway / Redis / Vue 3 / TypeScript / Vitest / Playwright / Node 22 / PowerShell / GitHub Actions。

**Spec:** `<仓外证据>/ai-full-audit/FINAL-AUDIT.md`；用户已批准“先复现、再修改、少量快测、最终统一验证”，并排除“多用户”专题。

## Global Constraints

- 先写最小确定性失败用例并观察 RED；只有证明是缺陷才改生产代码。无法证实的项只报告，不修改。
- 每个任务只跑新增测试及必要相邻测试，原则上不超过 6 个测试类或 6 个前端 spec；不得在各任务阶段跑全量。
- Maven 只用 `<IDEA_MAVEN>/mvn.cmd`（IDEA 内置 Maven），禁止 `clean`，测试 Redis 端口用 `1`。
- 所有子 agent 显式 `DeepSeek/deepseek-flash`；产品模型保持 OpenAI 兼容和配置驱动，不绑定 DeepSeek。
- 不改 `8081/5202`，不读写或泄露密钥，不在本机启动真实三实例/Redis Cluster，不新增依赖。
- “多用户”不处理；不改变已批准的无粘滞路由、park 非阻塞、业务数据不删除、通用模型兼容等裁决。
- 不改写历史结论，不写九册正文；只更新与代码行为直接相关的注释/现有文档。
- 各 agent 只交付差异、测试证据和报告，不提交。控制器逐项核对后合流并做最终全量、独立复审、提交和 CI。
- 主仓已有配置与结算候选补丁；先核对现有实现，不重来、不用旧副本覆盖主仓。

## Review Focus

- DB/Redis/解密失败必须区别于“确实不存在”：只能进入 UNKNOWN/只读恢复，不得永久判死（Task 1、8）。
- 同一 toolCallId 在跨实例决策、乱序多卡和重挂中，审计和 `tool_call_end` 必须恰好一次（Task 2）。
- 换任务、未加载工作簿、读取失败和特殊数值不得破坏真实文件（Task 4）。
- 多卡、409、隐藏步骤、陈旧快照和终态残留不得造成错误操作（Task 5）。
- Redis 故障、锁失效、销毁与写回竞态不得造成双驱动、数据残留或复活（Task 3、8）。

---

### Task 1: 配置播种、恢复判定与供应商密钥边界

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConfigController.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiConnectionProbe.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiEndpointGuard.java`
- Test: 仅上述区域的现有测试及新增聚焦测试类。

**Interfaces:**
- Consumes: 当前未提交的 `AiConfigVersionPublisher`、`ResumeService.resolveForResume` 和 `convergeProviderGone` 候选实现。
- Produces: 配置版本只发布一次的种子路径；可区分配置 UNKNOWN 与确定缺失的恢复路径；不得外泄旧密钥的探测路径。

- [ ] **Step 1: 写最小失败测试**
  - `seedDoesNotPublishTwiceForNoOpOrConcurrentSeed`：第二次/no-op 和并发同值播种不得重复 bump。
  - `reloadFailureCannotBecomeProviderGone`：读库失败必须返回 UNKNOWN/503，快照不得变 FAILED。
  - `keyFailureCannotBecomeProviderGone`：provider/model 行存在但 key 不可解，不得判 GONE。
  - `missingAfterSuccessfulReloadConvergesTerminal`：成功重载后确定删除才可 FAILED + error 终帧。
  - `providerProbeCannotSendRetainedKeyToChangedHost`：改 host 后不得把旧 key 发往新 host；私有地址规则单独断言。
- [ ] **Step 2: 只跑这些聚焦测试，确认各自因目标缺陷而 RED**
- [ ] **Step 3: 最小修改现有事务、版本发布、恢复判定和探测守卫**
  - 保留 no-op 不 bump、实际变化同事务 bump、提交后发布。
  - 用现有快照 `ok`/行存在性区分 UNKNOWN 与确定缺失；不改变 `reload()` 的既有调用/Mockito 语义。
  - 不以测试桩替代真实出网；只验证请求构造和守卫行为，禁用真实密钥。
- [ ] **Step 4: 运行新增测试及最多 4 个相邻类，确认 GREEN**
- [ ] **Step 5: 输出差异、RED/GREEN 日志和未验证边界，不提交**

### Task 2: 多实例结算审计恰好一次

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolCallEndTrace.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify only if a durable marker is unavoidable: 新增独立 observation store；不得改坏 `RunStore` 决策记录。
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementAuditTest.java` 及一个新的多实例/多卡聚焦测试类。

**Interfaces:**
- Consumes: 当前 `ToolCallEndTrace` 候选、决策唯一写者、执行 claim、resume/lease 结果。
- Produces: 同一 `toolCallId` 在跨实例决策、乱序多卡、重挂下的恰好一次审计与 `tool_call_end`。若新增键，必须给出 TTL、purge、迁移排除清单，交 Task 3 对齐。

- [ ] **Step 1: 写两个确定性失败场景**
  - 前序 backend 工具未结束时，B 实例决策，A 随后继续批循环：计数不得为 2。
  - 多卡乱序回答且 latch 已被后续 park 清理：计数不得为 0。
- [ ] **Step 2: 只跑新场景，确认 RED**
- [ ] **Step 3: 以真实所有权或最小持久幂等标记修复，不依赖进程内 woke**
  - 不得在通用回放分支放大；不得重复业务副作用；普通 inline 行为不变。
- [ ] **Step 4: 运行新旧聚焦测试及最多 4 个相邻类，确认 GREEN**
- [ ] **Step 5: 输出差异、测试与键生命周期（如有），不提交**

### Task 3: 会话互斥、记忆键与销毁完整性

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- Test: 各区域新增聚焦测试，不跑全量。

**Interfaces:**
- Consumes: Task 2 可能新增的 observation key 与 TTL/迁移清单。
- Produces: 锁失效时明确只读/拒绝写；无键碰撞；销毁覆盖全部 AI 键及写回竞态，不碰业务数据。

- [ ] **Step 1: 写失败测试**
  - 锁续期失败/诊断 GET 失败不得进入可写或假取锁成功。
  - `sessionId="{x}"` 不得读取/删除 `x` 的记忆；`__ids__` 不得删除全局索引。
  - purge 与记忆写回交错、staging、索引缺成员均不得报 complete 后残留。
- [ ] **Step 2: 只跑这些测试，确认 RED**
- [ ] **Step 3: 最小修复键命名、写权限判定、销毁与写回顺序**
  - 禁止通过放宽 fencing、删除保护或全清业务数据来转绿。
- [ ] **Step 4: 运行新增测试及每域最多 2 个相邻类，确认 GREEN**
- [ ] **Step 5: 输出差异、RED/GREEN 和真实 Redis 未验证边界，不提交**

### Task 4: 文件、任务切换与导出保真

**Files:**
- Modify: `frontend/src/views/ImportWizardView.vue`
- Modify: `frontend/src/views/ExportWizardView.vue`
- Modify: `frontend/src/utils/excel.ts`
- Test: 对应 wizard/excel 的少量 Vitest 与最多一个真实 Chromium 聚焦探针。

**Interfaces:**
- Consumes: 当前任务绑定、上传/导出文件、编辑区和条件持久化接口。
- Produces: 换任务隔离、禁止空文件覆盖、数值保真、过滤条件不丢失。

- [ ] **Step 1: 写最小失败测试**
  - A→B 换任务不得写 A 的内容到 B；未加载 grid 不得 PUT 空工作簿；`GET /files` 失败不得写回。
  - `007`、超过 `2^53` 的标识符不得失真；保存/导出失败不得启动旧条件作业或导出全量。
  - 对 `el-tab-pane` 的实际挂载前提只做最小浏览器探针，前提不成立则收窄该子项。
- [ ] **Step 2: 运行聚焦测试，确认 RED 并记录真实前提**
- [ ] **Step 3: 最小修复任务代次、状态重置、读写闸和值保真**
- [ ] **Step 4: 运行新增测试及最多 4 个相邻 spec，确认 GREEN**
- [ ] **Step 5: 输出差异与浏览器证据，不提交**

### Task 5: 前端多卡、恢复与 DOM 状态

**Files:**
- Modify: `frontend/src/stores/ai.ts`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/utils/parts.ts`
- Modify: `frontend/src/utils/ai-dom-tools.ts`
- Modify: `frontend/src/utils/frontend-tools.ts`
- Modify: `frontend/src/stores/workspace.ts`
- Modify: `frontend/src/stores/task.ts`
- Test: 多卡/恢复/DOM 的少量 Vitest；必要时一个真实 Chromium 聚焦探针。

**Interfaces:**
- Consumes: 当前 park 帧契约、页签所有权、工作区 handler/epoch。
- Produces: 多卡不丢续跑、409 可恢复、终态无残留、隐藏步骤不可写、动作后快照新鲜。

- [ ] **Step 1: 写失败测试**
  - 多卡/混合卡处理一张后，其余卡仍可提交并续流；409 后可恢复。
  - 终态后不得继续上传/预览；隐藏步骤不得披露为可写；动作后同参快照不得返回动作前内容。
  - 原生点击/折叠行为不确定的，先用浏览器确认再决定是否修改。
- [ ] **Step 2: 只跑聚焦测试，确认 RED**
- [ ] **Step 3: 最小修正状态机、可见性和缓存失效，不恢复阻塞等待**
- [ ] **Step 4: 运行新增测试及最多 4 个相邻 spec，确认 GREEN**
- [ ] **Step 5: 输出差异与真实浏览器边界，不提交**

### Task 6: 作业与历史真实性

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/job/service/JobService.java`
- Modify: 必要的 job runner/terminal recovery 文件。
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/ListTasksResultViewProvider.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` 仅限历史投影。
- Test: 作业零条目/终态恢复/历史投影聚焦测试。

**Interfaces:**
- Consumes: 作业条目、条件、终态扫描、历史工具结果。
- Produces: 零数据不假成功、异常作业有界终态、历史保留当时结果。

- [ ] **Step 1: 写失败测试**
  - 零条目 EXPORT/IMPORT/PRECHECK/PUBLISH 不得 COMPLETED；IMPORT/PRECHECK 不得无限 RUNNING。
  - 历史 `list_tasks` 不得用当前 DB 改写当时结果。
- [ ] **Step 2: 只跑聚焦测试，确认 RED**
- [ ] **Step 3: 最小修复空任务校验、终态恢复与历史快照投影**
- [ ] **Step 4: 运行新增测试及最多 4 个相邻类，确认 GREEN**
- [ ] **Step 5: 输出差异和证据，不提交**

### Task 7: 表单与知识库健壮性

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/kb/KbManifest.java`
- Modify: `frontend/src/components/AiPanel/FormRenderer.vue`
- Modify: `frontend/src/types/form-schema.ts`
- Test: 少量表单规则与 KB 生命周期测试。

**Interfaces:**
- Consumes: 表单 schema、级联父值、KB manifest/version/retire。
- Produces: 严格校验、字段错误而非 500、空 KB 不假 ready、失败可重试、停机不自旋。

- [ ] **Step 1: 写失败测试**
  - 父值缺失时级联值不得任意通过；非有限 `step` 必须成为字段错误。
  - 空 manifest 不得 ready；重建失败不得标记版本已见；stop 后 acquire 不得自旋。
- [ ] **Step 2: 只跑这些测试，确认 RED**
- [ ] **Step 3: 最小修正校验、版本发布与停机状态**
- [ ] **Step 4: 运行新增测试及最多 4 个相邻类/spec，确认 GREEN**
- [ ] **Step 5: 输出差异与证据，不提交**

### Task 8: park、取消与终态（依赖 Task 1–3 合流后串行）

**Files:**
- Modify only after Tasks 1–3 merge: `ResumeService.java`、`ConfirmGate.java`、`RunStore.java`、`ResilientChatService.java`、`SseChatEmitter.java` 及聚焦测试。
- Do not modify: Task 1 的配置语义、Task 2 的审计幂等、Task 3 的锁/销毁语义。

**Interfaces:**
- Consumes: Task 1 的 UNKNOWN 判定、Task 2 的恰好一次审计、Task 3 的锁/销毁规则。
- Produces: 取消后不执行不可逆写、所有退出有终态或有界收流、挂起状态可恢复。

- [ ] **Step 1: 基于合流代码写失败测试**
  - 取消发生在历史重建/压缩后，不得再执行不可逆工具。
  - park cancel、saveSuspended 写失败、markRequestEmitted 读失败、第二轮回合不得留下无终态或不可发现的轮次。
- [ ] **Step 2: 只跑这些测试，确认 RED**
- [ ] **Step 3: 最小修复取消复核、失败外置与终态/指针收敛**
- [ ] **Step 4: 运行新增测试及最多 4 个相邻类，确认 GREEN**
- [ ] **Step 5: 输出差异与证据，不提交**

### Task 9: 最终统一验证与交付

- [ ] 合流时逐文件核对，确认无 `MUTATION-RED`、调试残留、重复目录或旧副本覆盖。
- [ ] 运行一次 IDEA Maven 后端全量、一次前端全量和构建；失败只按根因继续修复，不缩减断言。
- [ ] 运行 `forbid-redis-cluster-unsafe`、`check-docs`、i18n、迁移 52 例、探针自证、`git diff --check` 和 pre-commit。
- [ ] 做独立只读复审，Critical/Important 修复后再验证。
- [ ] 提交推送后跑主 CI、真实三实例；全绿后跑 `@real`。真实三实例未绿不得触发 `@real`。
- [ ] 在最终 SHA 干净工作树执行 53 项自检和实际 Maven package；真实 PG、Redis Cluster、原生浏览器手势等未验证边界单列。
