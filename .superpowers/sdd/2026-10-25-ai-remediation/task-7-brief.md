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
