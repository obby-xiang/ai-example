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
