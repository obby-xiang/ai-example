# 测试用例与验证记录

版本：v1.0　日期：2026-10-05
验证环境：Windows 11 / JDK 21 / Maven 3.9.16 / Node 22 / Yarn 1.22。验证结果在实施完成后填写，详见 `05-verification.md`。

## A. 后端 API（curl 验证）

| # | 用例 | 步骤 | 预期 |
|---|---|---|---|
| A1 | 配置项列表 | GET /api/config-defs | ≥6 个配置项，含 fields/dependsOn/rowCount |
| A2 | 条件查询 | POST /api/config-defs/query {code:COUNTRY, conditions:[LIKE name "中"]} | total≥1，rows 含 data |
| A3 | 创建任务即持久化 | POST /api/tasks {type:EXPORT,name:测试} 后 GET /api/tasks | 列表含新任务，status=DRAFT，有 taskNo |
| A4 | 保存向导数据 | PUT /api/tasks/{id}/step-data | 返回 Task 含 stepData |
| A5 | 导出作业全流程 | POST jobs/export（2 个配置项）→ 轮询 GET /api/jobs/{id} 至 SUCCESS → GET export-results | 进度递增、items 逐个 SUCCESS、结果含 fields+rows |
| A6 | 作业幂等 | 作业 RUNNING 时重复 POST jobs/export | 返回同一作业，不新建 |
| A7 | 检查作业-正常数据 | POST jobs/check（合法行） | SUCCESS，errorRows=0 |
| A8 | 检查作业-违规数据 | 缺必填/枚举越界/ref 不存在 | FAILED，detail 含行号+字段+原因 |
| A9 | 导入依赖拓扑 | 一次提交 PROJECT_INFO+REGION_GROUP（乱序） | items seq 按拓扑序（REGION_GROUP 在前） |
| A10 | 导入→暂存→发布 | import（合法行）→ GET staging → publish → 再查正式区 | 暂存可见；发布后正式区全量替换，任务 COMPLETED |
| A11 | 依赖失败跳过 | import 含 REGION_GROUP 非法行 + PROJECT_INFO | REGION_GROUP FAILED，PROJECT_INFO SKIPPED |
| A12 | 任务取消/删除 | POST cancel、DELETE | 状态正确，关联数据清理 |

## B. AI 对话（curl 验证 SSE）

| # | 用例 | 步骤 | 预期 |
|---|---|---|---|
| B1 | 纯对话 | POST /api/ai/chat "你好，你能做什么？" | SSE 流式 token，done=finished，无 tool_call |
| B2 | 后端工具 | "现在有哪些配置项？" | 出现 tool_run(list_config_defs)，最终 token 回复包含配置项名称 |
| B3 | 前端工具暂停-恢复 | context page=EXPORT step1，"帮我选中国家字典和税率配置" | 收到 tool_call(select_config_defs)；POST tool-result 后流继续并 done |
| B4 | 渐进式披露 | context page=TASKS，"帮我启动导出" | 不调用 start_export（工具不可用），改为引导或 navigate_to |
| B5 | 需确认工具 | EXPORT 第3步，"开始导出吧" | tool_call(start_export, needConfirm=true) |
| B6 | 历史恢复 | 同 sessionId GET /api/ai/history | 返回之前消息 |
| B7 | 取消 | 流式中 POST /api/ai/cancel | done=cancelled 或流终止 |

## C. 前端端到端（浏览器验证）

| # | 用例 | 预期 |
|---|---|---|
| C1 | 布局 | 左侧工作区+右侧常驻 AI 栏，全中文 |
| C2 | 任务列表 | 展示任务，可创建/打开/取消/删除 |
| C3 | 导出向导 3 步 | 多选配置项→动态条件表单→导出进度可见→SpreadJS 展示→单 xlsx/zip 下载 |
| C4 | 导入向导 4 步 | 模板下载（单/zip）→上传 xlsx/zip 按文件名匹配→在线编辑→检查/导入/发布各步进度与结果 |
| C5 | AI 辅助导出 | 对话让 AI 建任务、导航、选配置、填条件、（确认后）启动导出 |
| C6 | AI 页签会话 | 刷新页面会话恢复；新页签为独立会话 |
| C7 | 确认卡片 | start_* 类工具渲染确认按钮，拒绝后 AI 收到拒绝结果 |

## D. 非功能

| # | 用例 | 预期 |
|---|---|---|
| D1 | 端口检测 | 启动脚本/记录中确认端口未被占用，占用则更换 |
| D2 | 构建 | `mvn -q package -DskipTests` 成功；`yarn build` 成功 |
| D3 | API Key 安全 | 前端任何请求/源码中不出现真实 API Key |
