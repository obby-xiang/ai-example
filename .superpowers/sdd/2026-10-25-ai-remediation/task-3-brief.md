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
