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
| U-FM-05 | 最长前缀优先 | PROJECT_PRICE.xlsx | PROJECT_PRICE（不被 PROJECT 抢先命中）|
| U-FM-06 | 无法匹配 | 未知配置.xlsx | 返回 null，接口 400 并给出命名指引 |

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
| E-AI-04 | 刷新页面 | 同一页签刷新：对话历史（用户消息/AI回复/工具卡片/token 行）完整恢复；后端重启或会话过期则新会话 |

---

## 4. 边界与异常测试

| 用例ID | 场景 | 预期处理 |
|--------|------|---------|
| B-01 | 上传文件名无法匹配任何配置 | 返回 400 与命名指引；zip 场景列入未匹配清单 |
| B-02 | 上传非 xlsx 文件 | 后端拒绝，返回 400 |
| B-03 | 预检查失败（有 ERROR）时点击"下一步" | 按钮禁用，无法进入导入步骤 |
| B-04 | AI 服务不可达（错误 API Key） | SSE 推送 ERROR 事件，业务区域正常使用 |
| B-05 | 导出 0 行数据 | 生成仅含表头的 xlsx，rowCount=0 |
| B-06 | 并发两次点击"开始导出" | 第二次请求返回已有作业，不重复创建 |
| B-07 | 作业执行中取消 | 下一批次边界停止，作业与逐项状态置 CANCELLED |
| B-08 | zip 中文文件名（GBK 编码） | UTF-8 解析出现乱码后自动回退 GBK，正确匹配 |
| B-09 | zip 内含非 xlsx / 无法匹配文件 | 不影响其余文件导入，响应携带未匹配清单 |
| B-10 | 引用值不存在 | 预检查 ERROR：`引用字段 [X] 的值 Y 在配置 Z 中不存在` |
| B-11 | REPLACE 模式发布 | 受影响范围内不在文件中的已发布行被删除 |
| B-12 | 导入后行被他人修改再发布 | 发布失败并报冲突（快照版本 vs 当前版本），需重新导入 |
| B-13 | GLOBAL 级数据浏览/发布 | scope_key=NULL 也能正确查询（null-safe JPQL） |
| B-14 | AI 触发高风险工具（发布） | 先收到 INTERACTION_REQUEST 卡片；批准后执行，拒绝后以拒绝结果回填继续对话 |
| B-15 | HITL 超时（5 分钟无响应） | 自动按拒绝处理，对话正常结束 |

## 5. 增量功能测试用例（本轮修复/补全）

### 5.1 字段级查询条件

| 用例ID | 描述 | 预期 |
|--------|------|------|
| N-QC-01 | 条件 EQ 命中 | `/data/CURRENCY/count?conditions={"fields":[{"fieldCode":"code","operator":"EQ","value":"USD"}]}` → count=1 |
| N-QC-02 | CONTAINS / STARTS_WITH | 文本字段模糊匹配正确 |
| N-QC-03 | GT/GTE/LT/LTE 数字比较 | 数值字段按数值比较（非字典序） |
| N-QC-04 | GT/LTE 日期比较 | 日期字段按日期比较（yyyy-MM-dd） |
| N-QC-05 | IN 列表（逗号分隔） | 值在列表中才命中 |
| N-QC-06 | EMPTY / NOT_EMPTY | 空值判定正确（null 或空白字符串视为空） |
| N-QC-07 | scopeKeys + fields 组合 | 范围过滤与字段过滤为 AND 关系 |
| N-QC-08 | 导出应用同款条件 | 导出行数与 count 口径一致 |

### 5.2 打包下载 / ZIP 上传

| 用例ID | 描述 | 预期 |
|--------|------|------|
| N-DL-01 | 勾选 2 个配置打包下载 | zip 含 2 个文件，中文名不乱码 |
| N-DL-02 | 只勾选 1 个 | 直接下载 xlsx 而非 zip |
| N-UP-01 | 上传含 2 个匹配文件的 zip | 返回 matchedFiles 2 条，均入库 |
| N-UP-02 | zip 内混杂不匹配文件 | matched + unmatched 并存，前端提示未匹配清单 |
| N-UP-03 | GBK 编码中文名 zip | 回退 GBK 后正确解出文件名 |

### 5.3 导入模式与发布安全

| 用例ID | 描述 | 预期 |
|--------|------|------|
| N-IM-01 | 默认 MERGE 发布 | 只 upsert 暂存行，不动其他已发布行 |
| N-IM-02 | REPLACE 发布 | 受影响范围内不在文件中的行被删除 |
| N-IM-03 | 冲突检测-版本变更 | 发布 FAILED，问题列表含冲突说明 |
| N-IM-04 | 冲突检测-新行被抢先创建 | 同上（快照为 null 但行已存在） |

### 5.4 AI 交互

| 用例ID | 描述 | 预期 |
|--------|------|------|
| N-AI-01 | 对话流式输出 | 文本逐块到达（多次 TEXT_DELTA），非一次性 |
| N-AI-02 | token 统计 | RUN_COMPLETED 携带 usage，前端显示输入/输出 token |
| N-AI-03 | HITL 批准路径 | 卡片 → 批准 → 工具执行 → 对话继续 |
| N-AI-04 | HITL 拒绝路径 | 卡片 → 拒绝 → 工具结果回填拒绝原因 → 模型回应 |
| N-AI-05 | UI_COMMAND navigate | AI 创建任务后前端自动跳转到向导页 |
| N-AI-06 | UI_COMMAND open_editor | 导出步骤 AI 请求后编辑器对话框打开 |
| N-AI-07 | 渐进式披露 | 不同步骤可用工具集不同（发布工具只在 PUBLISH 步骤暴露） |

### 5.5 配置定义字段编辑

| 用例ID | 描述 | 预期 |
|--------|------|------|
| N-DE-01 | 编辑字段保存 | PUT 后详情刷新，模板按新字段生成 |
| N-DE-02 | 字段编码重复/无主键 | 后端 400 拒绝并提示 |
| N-DE-03 | ENUM 选项行编辑 | optionsJson 正确序列化 |
| N-DE-04 | REFERENCE 引用联动 | 引用配置变更后引用字段列表联动刷新 |

### 5.6 历史任务管理

| 用例ID | 描述 | 预期 |
|--------|------|------|
| TM-01 | 创建即持久化 | POST /tasks 后任务总数 +1，刷新仍在 |
| TM-02 | 列表筛选 | type/status/keyword 三个维度过滤正确 |
| TM-03 | 概览接口 | /tasks/{id}/overview 返回任务+作业+文件 |
| TM-04 | 导入状态流转 | 导入成功条目 IMPORTED；发布成功条目 PUBLISHED、任务 COMPLETED |
| TM-05 | 列表摘要进度 | 每行含 latestJob（类型/状态/进度） |
| TM-06 | 导出完成流转 | 导出成功任务 COMPLETED、条目 COMPLETED |
| TM-07 | 删除任务 | 删除后列表不再出现 |
| TM-08 | UI 详情抽屉 | 步骤进度/条目状态/作业历史/文件四块渲染 |
