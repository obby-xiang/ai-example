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
