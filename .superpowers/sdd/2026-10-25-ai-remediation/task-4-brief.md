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
