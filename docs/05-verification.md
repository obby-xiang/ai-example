# 验证结果记录

日期：2026-10-05　验证人：Kimi（自动化验证）
环境：Windows 11 / JDK 21.0.12 / Maven 3.9.16 / Node 22.17.1 / Yarn 1.22.22 / DeepSeek deepseek-flash（真实 API）
服务：后端 8090（`java -jar`）、前端 5271（`yarn dev`）；启动前已检测 8080/8081/5173/5174 被其他项目占用，8090/5271 空闲故选用。

## A. 后端 API（curl）— 全部通过 12/12

| # | 结果 | 实测证据摘要 |
|---|---|---|
| A1 | ✅ | GET /api/config-defs 返回 6 个配置项（COUNTRY/PROJECT_TYPE/REGION_GROUP/TAX_RATE/PROJECT_INFO/UNIT_CONVERSION），含 fields/dependsOn/rowCount |
| A2 | ✅ | LIKE 查询 COUNTRY.name 含"中" → total=1（中国/CNY） |
| A3 | ✅ | POST /api/tasks → taskNo=EXP-20261005-0001、status=DRAFT，列表可查 |
| A4 | ✅ | PUT step-data 后 Task 含 selectedDefs/queryConditions |
| A5 | ✅ | 导出作业：COUNTRY(EQ CNY)=1行、TAX_RATE=12行，轮询见进度递增，export-results 含 fields+rows，任务 COMPLETED/100 |
| A6 | ✅ | RUNNING 中重复提交返回同一作业 id，未新建 |
| A7 | ✅ | 合法行检查 SUCCESS，errorRows=0 |
| A8 | ✅ | 违规行检查：detail 精确到行/字段（"引用的值在 COUNTRY.code 中不存在: XX"、"取值不在枚举选项 [VAT, CIT, CT] 内: BAD"、"应为数字: abc"） |
| A9 | ✅ | 乱序提交 PROJECT_INFO+REGION_GROUP → items seq：REGION_GROUP=1、PROJECT_INFO=2（拓扑序正确） |
| A10 | ✅ | import→staging 可见→publish→正式区按配置项全量替换（PROJECT_INFO/REGION_GROUP 各 1 行），任务 COMPLETED |
| A11 | ✅ | REGION_GROUP 行非法 → FAILED，下游 PROJECT_INFO 自动 SKIPPED（"依赖的配置项 REGION_GROUP 处理失败，已跳过"） |
| A12 | ✅ | cancel→CANCELLED；DELETE→204；再查→"任务不存在"（关联数据级联清理） |

## B. AI 对话（curl 验证 SSE）— 全部通过 7/7

| # | 结果 | 实测证据摘要 |
|---|---|---|
| B1 | ✅ | 纯对话流式 token + reasoning 事件，done=finished |
| B2 | ✅ | "有哪些配置项？" → tool_run(list_config_defs)，回复含配置项名称与依赖说明 |
| B3 | ✅ | EXPORT 第1步"帮我选中…" → tool_call(select_config_defs, needConfirm=false) + done=waiting；tool-result 回灌后 Loop 恢复，done=finished，回复确认已选 2 项 |
| B4 | ✅ | TASKS 页"帮我启动导出" → 未出现 start_export（该页不可用），模型改用 get_workspace_state 并引导（渐进式披露生效） |
| B5 | ✅ | EXPORT 第3步"开始导出吧" → tool_call(start_export, **needConfirm=true**) |
| B6 | ✅ | 同 sessionId GET /api/ai/history 返回完整对话与工具记录 |
| B7 | ✅ | 流式中 POST /api/ai/cancel → 轮次边界生效，done=cancelled（此前已执行 3 轮工具） |

### B 系列排坑记录（重要）
- **deepseek-flash 思考模式 400**：多轮工具调用后报 400，直连 API 复现，报错 *"The `reasoning_content` in the thinking mode must be passed back to the API"*。结论：deepseek-flash 与旧模型规则**相反**，回填 assistant 消息**必须携带**本轮 reasoning_content。修复后 7 轮连续工具调用无错误（docs/02 已同步修正）。
- **作业卡 PENDING**：作业在 `@Transactional` 内创建即提交线程池，执行线程读不到未提交行静默返回。修复为事务 afterCommit 后触发（A 系列复测通过）。

## C. 前端端到端（浏览器自动化验证）— 全部通过 7/7

| # | 结果 | 实测证据摘要 |
|---|---|---|
| C1 | ✅ | 左侧工作区 + 右侧常驻 AI 栏（可折叠），全中文界面 |
| C2 | ✅ | 任务列表展示编号/名称/类型/状态/进度/步骤/时间；创建（弹窗选类型）、打开、取消、删除均可用 |
| C3 | ✅ | 导出 3 步走完：多选 → 动态条件表单（字段类型感知：ENUM 下拉/DATE 等）→ 作业进度条+逐项明细 → SpreadJS 分 tab 展示可编辑 → "全部打包 zip"下载验证（zip 内含 COUNTRY.xlsx + TAX_RATE.xlsx，可正常解压） |
| C4 | ✅ | 导入 4 步走完：AI 代选 UNIT_CONVERSION → 模板下载（xlsx 表头 `* 源单位/* 目标单位/* 换算系数` 正确）→ SpreadJS 在线录入 km→m=1000 → 预检查（进度+结果，1行通过）→ 导入（写暂存）→ 暂存核查显示该行 → 发布（正式区验证为 km/m/1000，任务 COMPLETED） |
| C5 | ✅ | 一句话"帮我创建导出任务…并选中国家字典和税率配置"：AI 连续执行 create_task → navigate_to → （修复竞态后）select_config_defs，界面实时联动勾选；后续 set_query_conditions、count_config_data 预览行数均正常 |
| C6 | ✅ | 刷新页面后 AI 对话完整恢复（sessionStorage sessionId + 后端内存会话），向导步骤/数据从 Task 持久化恢复 |
| C7 | ✅ | start_* 工具渲染"确认执行/拒绝"卡片；点拒绝后 AI 收到"用户拒绝执行"并正确回应不再重试 |

### C 系列排坑记录
- **navigate_to 页面就绪竞态**：AI 跳转后 tool-result 立即回灌，context 仍是旧页面，后端按旧页面过滤工具导致模型报"没有勾选工具"。修复：navigate_to 等待 workspace 切换到目标 page/step（≤3s）再返回。复测通过。
- **SpreadJS 布局塌陷**：导入向导编辑区在 el-tabs 未激活时初始化，容器 0 宽导致表格压缩。修复：ResizeObserver 监听宿主尺寸变化自动 `spread.refresh()`。复测渲染正常。

## D. 非功能 — 全部通过 3/3

| # | 结果 | 证据 |
|---|---|---|
| D1 | ✅ | 8080/8081/5173/5174 被占用（其他项目），选用 8090/5271 并经 netstat 确认空闲后启动 |
| D2 | ✅ | `mvn package -DskipTests` BUILD SUCCESS；`yarn install` + `yarn build` 成功（vite 产物含懒加载 chunk；本机杀毒软件占用 %TEMP% 中 esbuild 临时文件的问题由 scripts/build.mjs 将 TMP 指向项目内 .tmp-build 解决） |
| D3 | ✅ | 全量 grep 前端源码无 API Key；Key 仅存在于后端 application.yml（环境变量优先，内置演示默认值） |

## 已知限制（非缺陷，按设计取舍）

1. **SpreadJS 评估版**：无 LicenseKey 时界面显示水印、导出的 xlsx 附带一张评估说明 sheet；生产注入 `VITE_SPREADJS_KEY` 即可消除。
2. **导出第 3 步刷新后**：任务状态与步骤恢复，但历史作业的结果视图需重新导出查看（作业结果在服务端 ExportResult 表保留，接口可取；前端未做结果回填渲染）。
3. **取消语义**：AI 取消与作业取消均为轮次/配置项边界生效，单轮长回复中取消会在该轮结束后生效。
4. **作业级 status 语义**：配置项级失败体现在 `items[].status`（FAILED/SKIPPED），作业级 status 只反映执行本身是否正常完成（详见后端实现说明）。
5. **上传匹配**：按"文件名包含配置项编码"匹配，按编码长度降序防前缀误配；浏览器自动化环境无法模拟文件选择框，该路径经代码审查与模板/解析函数单测级验证（模板生成与 xlsx 导出均已实测），未做真实文件上传 E2E。
