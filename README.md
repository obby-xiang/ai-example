# AI 辅助快速实施系统 (ai-example-claude-opus-5.5)

一个演示**后端 AI Agent Runtime + 动态配置项管理**的全栈示例项目。

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                     浏览器（Vue3 SPA）                            │
│  ┌────────────────────────┐    ┌─────────────────────────────┐  │
│  │  左：业务工作区          │    │  右：AI 对话栏（常驻）         │  │
│  │  - 任务中心             │◄──►│  - 消息列表                  │  │
│  │  - 导出向导（4 步）      │    │  - 工具执行卡片              │  │
│  │  - 导入向导（5 步）      │    │  - HITL 确认卡片            │  │
│  │  - 配置定义管理         │    │  - 上下文芯片（AI 视野）      │  │
│  │  - 配置数据浏览         │    │  - 建议快捷按钮              │  │
│  └────────────┬───────────┘    └──────────────┬──────────────┘  │
└───────────────┼──────────────────────────────┼─────────────────┘
                │ REST + SSE                   │
                ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   后端 (Spring Boot 3.5.14)                       │
│  ┌──────────────┐  ┌──────────────────────────────────────────┐  │
│  │ 业务模块     │  │ AI 模块                                    │  │
│  │ - 配置定义   │  │ - AiSession (Caffeine 内存，TTL 30min)     │  │
│  │ - 主数据     │  │ - AgentRuntime (自管工具循环，虚拟线程)      │  │
│  │ - 配置数据   │  │ - ToolRegistry (按步骤渐进式披露)           │  │
│  │ - 任务/条目  │  │ - ContextBuilder (工作区快照注入)          │  │
│  │ - 作业/进度  │  │ - HITL (toolCallId→等待→回填)             │  │
│  │ - Excel处理  │  │ - SseRunEmitter (AG-UI 事件协议)           │  │
│  └──────┬───────┘  └───────────────────────┬──────────────────┘  │
│         └────────────────┬─────────────────┘                     │
│                          ▼                                        │
│                   H2 文件数据库                                    │
│                   (backend/data/)                                 │
└─────────────────────────────────────────────────────────────────┘
                           │ OpenAI 兼容 API
                           ▼
                  DeepSeek (deepseek-flash)
```

## 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.5.14 |
| 运行时 | JDK | 21 |
| AI | Spring AI | 1.1.8 |
| 数据库 | H2 (文件模式) | 内置 |
| 前端框架 | Vue 3 | ^3.5 |
| 构建工具 | Vite | ^7 |
| 状态管理 | Pinia | ^3 |
| UI 库 | Element Plus | ^2 |
| 表格 | GrapeCity SpreadJS | 17.1.5 |
| 包管理 | yarn (前端) / Maven (后端) | — |

## 项目结构

```
ai-example-claude-opus-5.5/
├── backend/          # Spring Boot 后端
├── frontend/         # Vue3 前端
└── docs/             # 设计与验证文档
    ├── requirements.md          # 需求规格说明书
    ├── technical-design.md      # 技术方案
    ├── implementation-plan.md   # 实现方案
    ├── test-cases.md            # 测试用例
    └── verification-results.md  # 验证结果（运行后填写）
```

## 快速启动

### 前提条件
- JDK 21
- Node.js 18+ + yarn
- Maven（路径 `D:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\`）

### 启动后端

```powershell
cd backend
$env:DEEPSEEK_API_KEY = "<你的密钥>"   # 详见下方「AI 配置」
& "D:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" clean package -DskipTests -s maven-settings.xml
java -jar target\config-mgr.jar
# 访问 http://localhost:8081
```

### 启动前端

```powershell
cd frontend
yarn install
yarn dev
# 访问 http://localhost:5173
```

## AI 配置

- 模型：`deepseek-flash`（DeepSeek V4.1-Flash）
- 接口：`https://api.deepseek.com/chat/completions`（OpenAI 兼容）
- **API Key 绝不写入版本库**，通过以下任一方式注入：

**方式一：环境变量（推荐）**

```powershell
$env:DEEPSEEK_API_KEY = "<你的密钥>"
java -jar target\config-mgr.jar
```

**方式二：本地未追踪配置文件**

```powershell
# 复制示例后填入密钥；backend/config/application.yml 已在 .gitignore 中排除
Copy-Item config\application.yml.example config\application.yml
```

未配置密钥时，服务仍可正常启动，业务功能（导出/导入/发布）完全可用，仅 AI 对话会提示未配置。

### 提交前敏感信息防护

仓库内置 pre-commit 钩子，提交前自动扫描暂存区新增行，命中疑似密钥即阻止提交：

```powershell
git config core.hooksPath scripts/githooks
```


## 端口规划

| 服务 | 端口 |
|---|---|
| 后端 | 8081 |
| 前端开发服务器 | 5173 |
| H2 Console | http://localhost:8081/h2-console |
