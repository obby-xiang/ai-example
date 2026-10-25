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
