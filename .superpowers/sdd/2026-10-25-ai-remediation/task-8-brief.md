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
