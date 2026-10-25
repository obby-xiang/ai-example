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
