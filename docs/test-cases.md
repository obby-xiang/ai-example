# 测试用例文档

**项目**：ai-example-claude-opus-5.5  
**版本**：v1.0  
**日期**：2026-10-05

---

## 1. 后端单元测试

### 1.1 DependencyResolver（拓扑排序）

| 用例ID | 描述 | 输入 | 预期输出 |
|--------|------|------|---------|
| U-DR-01 | 无依赖关系 | [A, B, C]（互不依赖） | 任意顺序均可 |
| U-DR-02 | 线性依赖 | B 依赖 A | [A, B] |
| U-DR-03 | 多层依赖 | C 依赖 B，B 依赖 A | [A, B, C] |
| U-DR-04 | 循环依赖检测 | A 依赖 B，B 依赖 A | 抛出 IllegalStateException |
| U-DR-05 | 空列表 | [] | [] |
| U-DR-06 | 任务中包含不在依赖图中的定义 | 选中了 X，X 未配置依赖 | [X] |

### 1.2 ExcelReader（Excel 解析）

| 用例ID | 描述 | 预期结果 |
|--------|------|---------|
| U-ER-01 | 读取标准模板（含 _meta 工作表） | 按 fieldCode 映射返回数据行 |
| U-ER-02 | 读取无 _meta 工作表的 Excel | 按表头文字匹配字段 |
| U-ER-03 | 空行跳过 | 不返回空行 |
| U-ER-04 | 末尾空格 trim | 值已去除首尾空格 |
| U-ER-05 | 超大文件（>5MB） | 正常读取，无 OOM |

### 1.3 FileController（文件名匹配）

| 用例ID | 描述 | 文件名 | 预期匹配 defCode |
|--------|------|--------|----------------|
| U-FM-01 | 精确匹配 | CURRENCY.xlsx | CURRENCY |
| U-FM-02 | 编码前缀 + 下划线 | PROJ_PRICE_华东.xlsx | PROJ_PRICE |
| U-FM-03 | 编码前缀 + 横线 | DOC_TYPE-v2.xlsx | DOC_TYPE |
| U-FM-04 | 大小写不敏感 | currency.xlsx | CURRENCY |
| U-FM-05 | 无法匹配 | 未知配置.xlsx | 回退到任务第一项 |

### 1.4 ToolRegistry（渐进式工具披露）

| 用例ID | 描述 | 上下文 | 预期可用工具数 |
|--------|------|--------|--------------|
| U-TR-01 | 任务中心页面 | page=tasks | 包含 create_task、list_tasks |
| U-TR-02 | 导出-选择配置步骤 | task:EXPORT/SELECT_DEFS | 包含 select_defs，不含 start_publish |
| U-TR-03 | 导入-发布步骤 | task:IMPORT/PUBLISH | 包含 start_publish，不含 start_export |
| U-TR-04 | 全局工具始终可用 | 任意上下文 | list_config_defs、get_config_def 始终存在 |

---

## 2. 后端集成测试

### 2.1 导出任务完整流程

| 用例ID | 步骤 | 预期结果 |
|--------|------|---------|
| I-EX-01 | POST /api/tasks {type:EXPORT} | 返回 201，task.id 非空 |
| I-EX-02 | POST /api/tasks/{id}/select-defs {defCodes:["CURRENCY"]} | items 包含 CURRENCY |
| I-EX-03 | PUT /api/tasks/{id}/items/CURRENCY/condition | 200 OK |
| I-EX-04 | POST /api/tasks/{id}/jobs {jobType:EXPORT} | 返回 201，job.id 非空 |
| I-EX-05 | 轮询 GET /api/jobs/{jobId} 至 COMPLETED | status=COMPLETED，progress=total |
| I-EX-06 | GET /api/tasks/{id}/files/{CURRENCY} | 返回 xlsx 二进制，Content-Type 正确 |
| I-EX-07 | GET /api/tasks/{id}/files/download-all | 返回 zip，含 CURRENCY 文件 |

### 2.2 导入任务完整流程

| 用例ID | 步骤 | 预期结果 |
|--------|------|---------|
| I-IM-01 | 创建 IMPORT 任务，选中 CURRENCY | 步骤 UPLOAD |
| I-IM-02 | GET /api/tasks/{id}/files/templates | 返回 xlsx 模板，含 _meta 工作表 |
| I-IM-03 | POST /api/tasks/{id}/files/upload（上传模板） | 匹配 CURRENCY，fileType=UPLOAD |
| I-IM-04 | POST 预检查作业 | job.status 最终 COMPLETED |
| I-IM-05 | POST 导入作业 | staging 行数 > 0 |
| I-IM-06 | GET /api/jobs/{importJobId}/diff | 返回暂存行列表 |
| I-IM-07 | POST 发布作业 | COMPLETED，config_data_rows 更新 |

### 2.3 AI 会话

| 用例ID | 步骤 | 预期结果 |
|--------|------|---------|
| I-AI-01 | POST /api/ai/sessions | 201，sid 非空 |
| I-AI-02 | PUT /api/ai/sessions/{sid}/context {page:"tasks"} | 200 OK |
| I-AI-03 | POST /api/ai/sessions/{sid}/runs {message:"列出所有配置定义"} | SSE 流，TEXT_DELTA 含配置定义列表，RUN_COMPLETED |
| I-AI-04 | 会话 TTL 过期后访问 | 404 "会话不存在或已过期" |

---

## 3. 端到端 UI 测试

### 3.1 任务中心

| 用例ID | 操作 | 预期结果 |
|--------|------|---------|
| E-TC-01 | 打开 http://localhost:5173 | 渲染任务中心，AI 面板可见 |
| E-TC-02 | 点击"新建导出任务" | 跳转到导出向导-选择配置步骤 |
| E-TC-03 | 点击"新建导入任务" | 跳转到导入向导-上传配置步骤 |
| E-TC-04 | AI 面板输入"列出最近的任务" | 返回任务列表描述 |

### 3.2 导出向导

| 用例ID | 操作 | 预期结果 |
|--------|------|---------|
| E-EX-01 | 选择 CURRENCY 和 DOC_TYPE | 表格复选框选中，下方显示"已选 2 个" |
| E-EX-02 | 点击"下一步" | 跳转到查询条件步骤 |
| E-EX-03 | 在查询条件页设置地区过滤（对 GLOBAL 无效） | 仅 REGION/PROJECT 级配置显示地区/项目选择器 |
| E-EX-04 | 点击"开始导出" | 显示进度条，每批更新，最终 COMPLETED |
| E-EX-05 | 点击"在线编辑" | SpreadJS 弹窗打开，显示 Excel 内容 |
| E-EX-06 | 点击"全部下载" | 浏览器触发 zip 下载 |

### 3.3 AI 对话-工具调用

| 用例ID | 输入 | 预期结果 |
|--------|------|---------|
| E-AI-01 | "帮我查看货币配置的字段详情" | AI 调用 get_config_def(CURRENCY)，返回字段列表 |
| E-AI-02 | 在导出-选配步骤输入"帮我选择所有全局配置" | AI 调用 select_defs，表格中全局配置被选中 |
| E-AI-03 | 在导入-发布步骤输入"发布配置" | AI 返回确认卡片；用户点击批准后调用 start_publish |
| E-AI-04 | 刷新页面 | AI 面话消失（会话内存，可接受），新会话空白 |

---

## 4. 边界与异常测试

| 用例ID | 场景 | 预期处理 |
|--------|------|---------|
| B-01 | 上传文件名无法匹配任何配置 | 匹配任务第一个配置项，提示用户确认 |
| B-02 | 上传非 xlsx 文件 | 后端拒绝，返回 400 |
| B-03 | 预检查失败（有 ERROR）时点击"下一步" | 按钮禁用，无法进入导入步骤 |
| B-04 | AI 服务不可达（错误 API Key） | SSE 推送 ERROR 事件，业务区域正常使用 |
| B-05 | 导出 0 行数据 | 生成仅含表头的 xlsx，rowCount=0 |
| B-06 | 并发两次点击"开始导出" | 第二次请求返回已有作业，不重复创建 |
