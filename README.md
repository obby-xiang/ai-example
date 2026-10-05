# ai-example-deepseek-v4-pro

AI 辅助动态配置管理系统 —— 业务场景：动态配置项的**导出配置**与**导入配置**两大快速实施任务，右侧常驻 AI 助手（后端 AI Runtime）驱动左侧任务向导。

## 技术栈

- 后端：**Spring Boot 3.5.14 + JDK 21 + Spring AI 1.1.8**（OpenAI 兼容模式对接 DeepSeek `deepseek-flash`），Maven 构建，嵌入式 **H2 文件库**
- 前端：**Vue 3 + Element Plus + TailwindCSS 3.4 + GrapeCity SpreadJS 17.1.5**（在线编辑 Excel），yarn 管理，全中文界面
- 布局：左侧业务工作区（多步骤向导）/ 右侧 AI 对话栏（常驻，页签级会话）

## 核心能力

| 能力 | 说明 |
|---|---|
| 动态配置建模 | 配置定义（编码/名称/层级/字段/依赖）完全数据驱动；字段类型覆盖 文本/长文本/数字/布尔/日期/下拉/引用；表单与表格运行时渲染 |
| 分层级 | 全局/地区/项目；地区与项目配置携带“范围”维度 |
| 导出配置 | 选择配置（多选）→ 各配置动态查询条件 → 异步导出（SSE 实时进度）→ SpreadJS 在线编辑 → 单下载/勾选打包/全部打包 |
| 导入配置 | 模板下载（单 xlsx/多 zip，内嵌下拉数据验证）→ 上传（文件名匹配配置编码，支持 zip/序号后缀/覆盖）→ 检查（依赖拓扑+引用校验+行级明细）→ 导入（草稿，不影响生效）→ 发布（终检+替换生效数据）；全程异步进度+结果明细（可下载） |
| 任务管理 | 任务中心统一查看全部历史任务（导出+导入，H2 持久化，创建即入库）：类型/状态/进度/消息/时间，运行中自动刷新，一键跳转向导恢复任务与进度订阅 |
| AI 助手 | 后端 AI Runtime：流式输出（含思考折叠）、工具调用全程可见、**渐进式披露**（不同页面不同工具）、**事件总线+契约层解耦联动**（AI 操作与用户点击同源、可独立裁剪）、破坏性操作 **HITL 确认门**、页签唯一会话（H2 持久化：刷新与后端重启不丢历史，新页签=新会话） |

## 目录

```
backend/   后端（Spring Boot 3.5.14 / Spring AI 1.1.8 / H2）
frontend/  前端（Vue3 / Vite / yarn / SpreadJS 17.1.5）
docs/      需求规格、技术方案、实现方案、测试用例、验证结果、AI 设计、部署手册、接口文档
scripts/   verify-e2e.ps1 自动化 E2E 验证
```

## 快速开始

见 [docs/06-部署运行手册.md](docs/06-部署运行手册.md)。摘要：

```powershell
# 后端（端口 18080，启动前已检测占用）
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.12'
& 'D:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' -f backend\pom.xml -DskipTests package
cd backend; & 'D:\Program Files\Java\jdk-21.0.12\bin\java.exe' -jar target\config-admin-backend.jar

# 前端（端口 5173）
cd frontend; yarn install; yarn dev   # 打开 http://127.0.0.1:5173

# 自动验证
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\verify-e2e.ps1
```

## 文档索引

1. [需求规格说明书](docs/01-需求规格说明书.md)
2. [技术方案（含 ADR 决策记录）](docs/02-技术方案.md)
3. [实现方案](docs/03-实现方案.md)
4. [测试用例](docs/04-测试用例.md)
5. [验证结果](docs/05-验证结果.md)
6. [部署运行手册](docs/06-部署运行手册.md)
7. [AI 对话与状态同步设计](docs/07-AI对话与状态同步设计.md)
8. [接口文档](docs/08-接口文档.md)

## 备注

- SpreadJS 17.1.5 为商业组件：未配置授权为评估模式（水印），功能完整；授权经 `frontend/.env.local` 的 `VITE_SPREADJS_KEY` 注入（不入库）。
- 模型服务为 OpenAI 兼容的 DeepSeek `deepseek-flash`（推理模型）；密钥仅后端持有，通过环境变量 `DEEPSEEK_API_KEY` 注入（禁止写入仓库）。
- 演示数据于首次启动自动初始化（4 个配置定义覆盖全部层级/字段类型/依赖/引用），删除 `backend\data` 可重置。
