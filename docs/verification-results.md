# 验证结果文档

**项目**：ai-example-claude-opus-5.5  
**版本**：v1.1（缺陷修复 + 补全功能 + 实测验证）  
**日期**：2026-10-05

---

## 1. 构建验证

### 1.1 后端构建

| 项目 | 结果 | 说明 |
|------|------|------|
| `mvn clean package -DskipTests` | ✅ BUILD SUCCESS | `backend/target/config-mgr.jar` |
| 编译错误 | 0 | 修复过程见 §4 |

### 1.2 前端构建

| 项目 | 结果 | 说明 |
|------|------|------|
| `yarn install` | ✅ | yarn 1.22.22，lockfile 已生成 |
| `vite build`（生产构建） | ✅ | 1735 模块；注意 Windows 上需把 TEMP 指向项目内目录（esbuild 在系统 %TEMP% 删除临时文件会 Access denied），见 §4 |
| `vite dev` 启动 | ✅ | 端口 5174（5173 被其他项目的 vite 占用，未动其服务） |
| 10 个关键模块（App/AiPanel/SpreadJSEditor/各步骤页/stores）经 Vite 按需转换 | ✅ 全部 200 | 无语法/编译错误 |
| Vite 代理 → 后端 `/api/definitions` | ✅ 200 | 前后端联通 |

---

## 2. 服务启动验证

| 服务 | 端口 | 状态 | 备注 |
|------|------|------|------|
| 后端 (config-mgr.jar) | 8081 | ✅ 运行中 | 8081 启动前检测为空闲（8080 被占用，按要求未使用） |
| 前端 (vite dev) | 5174 | ✅ 运行中 | 5173 被他人占用，改用 5174 |
| H2 Console | http://localhost:8081/h2-console | ✅ | 文件库 `backend/data/config_mgr_db` |
| Flyway | V1__schema + V2__staging_base_version | ✅ | 迁移成功 |
| 种子数据 | 4 地区 / 7 项目 / 7 定义 / PROJ_PRICE 1200 行 | ✅ | DataSeedRunner 幂等 |
| ToolRegistry | 16 个工具按 @ToolScope 注册 | ✅ | 启动日志确认 |

---

## 3. 功能验证结果（实测）

### 3.1 API 批次一：基础 + 导出（20/20 通过）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| T1-definitions / regions / projects / def-detail | ✅ | 7 定义 / 4 地区 / 7 项目 / CURRENCY 4 字段 |
| N-QC-01 EQ | ✅ | code=USD → count=1 |
| N-QC-02 CONTAINS | ✅ | name 含"元" → count=3 |
| N-QC-03 GT 数值 | ✅ | 汇率>1 → count=3 |
| N-QC-04 BOOLEAN | ✅ | enabled=true → count=5 |
| N-QC-05 IN 列表 | ✅ | IN[USD,EUR] → count=2 |
| T2-total / N-QC-07 scope | ✅ | PROJ_PRICE 1200 行；HE-P001 范围 300 行 |
| B-13 GLOBAL null-safe | ✅ | CURRENCY count=5、列表 5 行 |
| T3-template | ✅ | 模板 6283 字节，含 _meta 工作表 |
| I-EX-01..07 导出全流程 | ✅ | 建任务→选 2 配置→设条件→作业 COMPLETED 2/2→USD 仅导出 1 行→子集下载/全部下载均为有效 xlsx/zip |
| N-DL-01/02 子集打包 | ✅ | 单文件直下 xlsx；两个打包 zip |

### 3.2 API 批次二：导入 + 安全（12/12 通过）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| N-UP-01 ZIP 批量上传 | ✅ | 含 2 个模板的 zip → matched=2 unmatched=0 |
| I-IM-03 单文件上传 | ✅ | 按文件名匹配 DOC_TYPE |
| N-IM-01 导入模式设置 | ✅ | settingsJson={"importMode":"REPLACE"} |
| I-IM-04..07 预检/导入/diff/发布 | ✅ | 预检 0 问题；暂存 2 行；发布 COMPLETED |
| N-IM-02 REPLACE 效果 | ✅ | 发布后 count 5→2（范围外行被删除） |
| N-IM-03 MERGE 效果 | ✅ | 再发布 1 行 → count=3（增量合并） |
| B-07 作业取消 | ✅ | 导出 PROJ_PRICE 中取消 → CANCELLED，progress 停在 0 |
| B-10 ref-bad 引用校验 | ✅ | 非法引用值 → 预检 FAILED + 问题"引用字段 [审批角色] 的值 NO_SUCH_ROLE 在配置 APPROVE_ROLE 中不存在" |
| B-10 ref-ok 合法引用 | ✅ | DEPT_MGR → 预检 COMPLETED |

### 3.3 API 批次三：AI 对话（7/7 通过，真实 DeepSeek deepseek-flash）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| I-AI-01 会话创建 | ✅ | sid 正常返回 |
| N-AI-01 真流式 | ✅ | 一次对话收到 241 个 TEXT_DELTA 块 |
| N-AI-02 token 统计 | ✅ | RUN_COMPLETED 携带 usage |
| N-AI-01 内容正确 | ✅ | 回复提及 CURRENCY |
| N-AI-05 UI_COMMAND navigate | ✅ | AI 调 create_task → 前端收到 navigate 指令（route=/tasks/N/export/SELECT_DEFS） |
| N-AI-03 HITL 请求 | ✅ | 发布指令 → INTERACTION_REQUEST 卡片（iid 已推送） |
| N-AI-04 HITL 批准 | ✅ | 批准后 PUBLISH 作业创建并 COMPLETED |

### 3.3b API 批次五：AI 功能补充验证（10/10 通过）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| A-T-01a 渐进披露-tasks 页 | ✅ | 5 个工具（create_task/list_tasks/list_config_defs/get_config_def/get_workspace_state），无 start_publish |
| A-T-01b 渐进披露-导出步骤 | ✅ | 11 个工具，含 start_export/open_export_file_editor，不含 start_publish/start_precheck |
| A-T-01c 渐进披露-发布步骤 | ✅ | 8 个工具，含 start_publish，不含 start_export |
| A-T-01d 渐进披露-上传步骤 | ✅ | 8 个工具，含 select_defs，不含 start_publish |
| A-T-02 多工具多轮 | ✅ | 指定步骤内 AI 调 select_defs，任务 items=2 |
| A-T-03a HITL 拒绝卡片 | ✅ | INTERACTION_REQUEST 携带 iid |
| A-T-03b 拒绝生效 | ✅ | 拒绝后 PUBLISH 作业 0 个、任务保持 ACTIVE、对话正常结束 |
| A-T-04 UI_COMMAND open_editor | ✅ | 流内出现 command=open_editor + defCode=CURRENCY |
| A-T-05 会话重置 | ✅ | DELETE 后访问返回"会话不存在或已过期" |

### 3.3c 取消运行（专项，HITL 等待期间取消）

| 步骤 | 实测证据 |
|------|---------|
| 发布指令 → HITL 卡片出现 | iid 推送 |
| DELETE /runs/current | `{"cancelled":true}` |
| 运行终止耗时 | **1 秒**（而非等待 5 分钟超时），输出含"取消"提示 |
| 发布作业 | 0 个（取消未产生副作用） |

### 3.3d 浏览器级 AI 对话框（真实 UI，Kimi WebBridge）

| 步骤 | 实测证据 |
|------|---------|
| AI 面板渲染 | "AI 助手"标题、输入框、发送按钮、快捷建议芯片 |
| 发送真实消息 | 用户消息"你好，请列出系统里所有的配置定义" |
| 工具调用卡片 | 显示 list_config_defs 执行卡片与工具结果 |
| 完整回复 | AI 以表格列出全部 7 个配置定义（含层级/字段数），并给出下一步建议 |
| token 消耗显示 | "本轮消耗 token：输入 1950 / 输出 296" |

> 浏览器验证中发现并修复一个前端缺陷：会话创建响应解包层级错误
> （误取 res.data.sid，应为 res.data.data.sid），导致 sessionStorage 存入
> 字符串 "undefined"、聊天永远不可用。已修复并回归通过（见 §4 缺陷 #17）。

### 3.3e 会话历史恢复（同页签刷新）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| H-01 user 消息恢复 | ✅ | GET /sessions/{sid} 返回 1 条用户消息 |
| H-02 assistant 回复恢复 | ✅ | 2 条助手文本 |
| H-03 工具卡片恢复 | ✅ | 1 条工具结果卡片 |
| H-04 内容完整 | ✅ | 含 CURRENCY 等定义内容 |
| H-05 浏览器刷新回归 | ✅ | 发消息→刷新后：用户消息/完整回复/工具卡片/token 行（"上一轮消耗 token…"）全部恢复 |

### 3.3f TailwindCSS 与 AI↔工作区联动（真实浏览器）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| T-01 Tailwind 集成 | ✅ | TailwindCSS 4.3.3 + @tailwindcss/vite；App/AiPanel/ContextChip/SuggestionChips/ToolCallCard 套用原子类；yarn build 通过，界面渲染正常 |
| T-02 AI 选择配置→工作区联动 | ✅ | 浏览器停留在导出-选择配置步骤（已选 0）→ 同一会话 AI 调 select_defs → 工作区实时变为"已选 2"（任务 SSE 事件流驱动） |
| T-03 AI 启动导出→工作区接管 | ✅ | AI 调 start_export → 工作区显示导出结果（在线编辑/全部下载）；大配置（PROJ_PRICE 1200 行）场景捕获中间态："正在导出"+进度条+取消按钮 |
| T-04 会话页签语义 | ✅ | sid 存 sessionStorage；新页签=新会话无历史；同页签刷新=历史恢复（见 3.3e） |

### 3.3g 任务中心交互与步进条（UI 调整，真实浏览器）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| U-01 操作列精简 | ✅ | 仅保留"删除"；无"继续/详情"按钮 |
| U-02 标题即入口 | ✅ | 点击任务标题直接进入向导页（非抽屉）；已完成导入任务自动展示"发布成功"结果页而非确认卡片 |
| U-03 步进条可点击 | ✅ | 点击"查询条件"步骤 → URL 跳转 /export/QUERY_COND，后端 currentStep 同步、AI 视野同步 |
| U-04 完成步骤同步 | ✅ | 发布完成 → 任务 COMPLETED 且 currentStep=PUBLISH（列表步骤标签与点击进入结果页一致） |

### 3.4 独立用例：发布冲突检测（双任务并发）

| 步骤 | 实测证据 |
|------|---------|
| 任务 A、B 同时导入同一行 TO3 | 快照版本均为 1 |
| B 先发布 | TO3 版本 1→2（OPTIMISTIC_FORCE_INCREMENT 强制递增） |
| A 后发布 | ✅ FAILED + 问题"发布冲突：行 TO3 在导入后被其他操作修改（快照版本 1，当前版本 2），请重新导入后再发布" |

### 3.5 API 批次四：历史任务管理（11/11 通过）

| 用例 | 结果 | 实测证据 |
|------|------|---------|
| TM-01 创建即持久化 | ✅ | POST /tasks 后总数 63→64，任务 #321 立即可查 |
| TM-02 filter-type / keyword / status | ✅ | EXPORT 过滤仅导出任务；关键词精确命中 1 条；ACTIVE 过滤正确 |
| TM-03 overview 接口 | ✅ | /tasks/{id}/overview 返回 task+jobs+files |
| TM-04 导入状态流转 | ✅ | 导入后条目 IMPORTED；发布后条目 PUBLISHED、任务 COMPLETED |
| TM-05 列表摘要 | ✅ | 每行 latestJob=PUBLISH/COMPLETED |
| TM-06 导出完成流转 | ✅ | 任务 COMPLETED、条目 COMPLETED |
| TM-07 删除任务 | ✅ | 删除后检索为 0 |
| TM-08 前端 | ✅ | TaskListView.vue 经 Vite 编译 200；分页/概览接口经代理 200 |
| TM-09 真实浏览器渲染 | ✅ | 经 Kimi WebBridge 打开 http://localhost:5174/：页面标题正确；筛选栏（类型/状态/关键词/查询/刷新）与"共 65 个任务"渲染；表格含"最新作业/进度"列且进度条值=100；点击任务标题打开详情抽屉，流程进度/配置项/作业历史/文件四块全部渲染（截图存于 .scratch/tm-list.png） |

---

## 4. 验证过程中发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 |
|---|------|------|------|
| 1 | AI 请求 404 | `spring.ai.openai.completions-path` 属性层级错误（应为 `chat.completions-path`），默认路径 /v1/chat/completions 对 DeepSeek 404 | 配置移至 chat 层级 |
| 2 | 定义/任务/作业接口 500 | `@OneToMany(mappedBy=标量字段)` 非法用法，Hibernate 6.6 集合加载参数类型推断为 DECFLOAT | 改标准单向 @JoinColumn(referencedColumnName, NO_CONSTRAINT) + EAGER |
| 3 | GLOBAL 级 count/列表为 0 | scopeType 缺省写死 GLOBAL；派生查询 "= null" 匹配不到 | 按定义层级推导 + null-safe JPQL |
| 4 | select-defs 返回空 items | EAGER 集合在 findById 时快照，save 后返回旧对象 | refresh 强制重读 |
| 5 | HITL 确认卡片永不出现 | 事件在阻塞等待之后才发送；事件类型/字段不匹配 | 先推 INTERACTION_REQUEST(iid) 再 awaitResponse；DTO 纯净化 |
| 6 | REPLACE 发布 NPE | `List.of((String)null)` 抛 NPE | HashSet 直接容纳 null |
| 7 | 作业取消无效 | @Async 自调用绕过代理 → 作业同步阻塞 HTTP 线程 | 独立 AsyncJobExecutor Bean |
| 8 | 作业偶发卡 RUNNING | 异步任务与创建事务竞态（ObjectOptimisticLockingFailure） | afterCommit 后再启动异步执行 + 僵尸作业启动恢复 |
| 9 | 并发冲突检测失效 | Hibernate 脏检查跳过无变化 UPDATE，版本不递增 | OPTIMISTIC_FORCE_INCREMENT 强制递增 |
| 10 | Excel 导入字段错位 | _meta 第 0 列是 def_code 元信息（标签在 row0、值在 row1），按 row1 判断跳列失败 | 按 row0 标签判断 |
| 11 | AI 不调用工具（流式） | DeepSeek API：thinking=disabled + stream=true 时不发起工具调用 | 同时设置 reasoning_effort=low |
| 12 | AI 抢在对话里问确认 | 系统提示词引导 AI 先询问而非调用工具 | 提示词明确：确认由系统 HITL 卡片负责 |
| 13 | 导出"下载已选"拿到空白模板 | 前端误调 templates 接口 | 新增 /files/download 子集打包接口 |
| 14 | SpreadJS 中文/授权丢失 | 重写 main.js 时误删初始化 | SpreadJSEditor 内懒加载恢复 zh-cn + LicenseKey |
| 15 | 前端构建失败（esbuild） | Windows 下 esbuild 在系统 %TEMP% 删临时文件 Access denied | 构建时 TEMP 指向 frontend/.tmp-esbuild（已 gitignore） |
| 16 | 测试脚本 byte[] 展开为 Object[] | PS 5.1 return 展开集合 | 脚本改用局部变量 + curl |
| 17 | 浏览器聊天永远提示"AI 服务不可用" | 会话创建响应解包层级错误（res.data.sid → 实际是 res.data.data.sid），sessionStorage 存入字符串 "undefined" | 修正解包 + 历史 "undefined" 防御清理；浏览器回归通过 |
| 18 | 刷新页面后对话历史丢失 | 后端会话保存了历史，但前端刷新后从未拉取渲染（GET /sessions/{sid} 不返回消息） | 后端返回重建的显示条目（user/assistant/tool）+ 最近 token 用量；前端 initSession 恢复消息、进行中提示、挂起的 HITL 卡片与 token 行 |

---

## 5. 已知限制

| # | 限制 | 说明 |
|---|------|------|
| 1 | SpreadJS 评估模式水印 | 需正式授权，经 `VITE_SPREADJS_KEY` 注入 |
| 2 | 前端作业进度用 1.5s 轮询 | 后端 SSE 任务流已提供（TASK_CHANGED/JOB_PROGRESS），前端可选接入 |
| 3 | 会话刷新恢复的边界 | 同一页签刷新（sessionStorage 的 sid + 后端内存会话存活）→ 历史完整恢复；后端重启或 30 分钟无活动过期 → 新会话（符合"不持久化"约束） |
| 4 | 空 api-key 时 AI 返回 401 错误 | 业务功能不受影响；建议启动前配置环境变量 |
| 5 | 演示延迟 300ms/批 | 用于展示进度条，可在 application.yml `app.job.demo-batch-delay-ms` 调小或置 0 |

---

## 6. 测试脚本

验证脚本位于 `.scratch/`（已 gitignore，不入库）：

| 脚本 | 覆盖 |
|------|------|
| api-tests-1b.ps1 | 基础数据 + 条件 + 导出全流程（20 用例） |
| api-tests-2.ps1 | zip 上传 + 导入 REPLACE/MERGE + 取消 + 引用校验（12 用例） |
| t8.ps1 | 发布冲突检测（双任务并发，独立验证） |
| api-tests-3.ps1 | AI 流式/token/UI_COMMAND/HITL（7 用例） |
| craft.py | openpyxl 构造测试 Excel（含 _meta 表） |
