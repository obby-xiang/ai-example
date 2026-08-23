# AI 辅助配置管理系统（ai-example）

一个演示 **AI Agent 驱动业务向导** 的全栈示例项目。包含两种 AI 运行时范式：后端 Agent Loop（传统）和前端 AI Runtime（Vercel AI SDK），共享同一套业务场景和工具体系。

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    ai-example                           │
│                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │  ai-service  │  │    ai-ui     │  │ ai-ui-vercel  │ │
│  │  (后端服务)   │  │ (前端·范式1)  │  │ (前端·范式2)  │ │
│  │              │  │              │  │               │ │
│  │ Spring Boot  │  │ Vue3 + Vite  │  │ Vue3 + Vite   │ │
│  │ JDK 21       │  │ Pinia        │  │ Pinia         │ │
│  │ Spring AI    │  │ Element Plus │  │ Element Plus  │ │
│  │ H2 Database  │  │ SpreadJS     │  │ SpreadJS      │ │
│  │ DeepSeek API │  │              │  │ Vercel AI SDK │ │
│  │              │  │ 后端Agent驱动 │  │ 前端Agent驱动  │ │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘ │
│         │                 │                  │         │
│         │  REST API       │  透明SSE代理      │         │
│         └─────────────────┤◄─────────────────┤         │
│                           │                  │         │
│                           ▼                  │         │
│                   DeepSeek API ◄─────────────┘         │
│               (api.deepseek.com)                        │
└─────────────────────────────────────────────────────────┘
```

## 两种 AI 运行时范式

| 维度 | ai-ui（范式1：后端 Agent Loop） | ai-ui-vercel（范式2：前端 AI Runtime） |
|------|----------------------------------|----------------------------------------|
| Agent Loop 位置 | **后端**（Spring AI while 循环） | **前端**（Vercel AI SDK streamText） |
| 工具执行位置 | 前端（通过 resumeToken 暂停-恢复） | 前端（execute Promise 内暂停） |
| 数据持久化 | 后端 H2 数据库 | 前端 localStorage |
| API Key | 后端持有，前端不接触 | 后端代理注入，前端用占位符 |
| 后端职责 | 全业务逻辑 + Agent Loop | 仅透明 SSE 代理（注入 api-key） |
| AI SDK | Spring AI 1.1.8（OpenAI 兼容） | Vercel AI SDK 6.0.221 |
| 暂停-恢复机制 | resumeToken + DB 状态机 | Promise await + 响应式 pendingInteraction |

## 技术栈

### 后端（ai-service）

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.14 | Web 框架 |
| JDK | 21 | 运行时 |
| Spring AI | 1.1.8 | OpenAI 兼容模型调用 |
| H2 Database | 内置 | 文件模式持久化 |
| Jackson | 随 Boot | JSON/YAML 解析 |
| Lombok | 随 Boot | 样板代码消除 |

### 前端（ai-ui / ai-ui-vercel 共享）

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | ^3.5.13 | Composition API |
| Vite | ^6.0.7 | 构建工具 |
| Pinia | ^2.3.0 | 状态管理 |
| Element Plus | ^2.9.3 | UI 组件库 |
| SpreadJS | ^17.1.10 | 表格组件（GrapeCity） |
| Vue Router | ^4.5.0 | 路由 |
| Sass | ^1.83.4 | 样式预处理 |

### ai-ui-vercel 额外依赖

| 技术 | 版本 | 用途 |
|------|------|------|
| ai (Vercel AI SDK) | 6.0.221 | 前端 AI Runtime（streamText） |
| @ai-sdk/openai | 3.0.97 | OpenAI 兼容 Provider |
| zod | 3.25.76 | 工具参数 Schema 校验 |

## 运行环境要求

- **JDK** 21+
- **Node.js** 18+（推荐 20+）
- **yarn** 1.22+（前端包管理器）
- **Maven** 3.9+（或使用 IDE 内置 Maven）
- **DeepSeek API Key**（在 https://platform.deepseek.com 申请）

## 配置说明

### 1. 配置 DeepSeek API Key

后端通过环境变量注入 API Key，**不硬编码到源码**。

**Windows (PowerShell)：**

```powershell
$env:DEEPSEEK_API_KEY = "sk-your-real-api-key"
```

**Linux / macOS：**

```bash
export DEEPSEEK_API_KEY=sk-your-real-api-key
```

或在 IDE 启动配置中添加环境变量 `DEEPSEEK_API_KEY`。

> 配置文件 `ai-service/src/main/resources/application.yml` 中使用
> `${DEEPSEEK_API_KEY:your-deepseek-api-key-here}` 占位，启动时由环境变量覆盖。

### 2. 端口规划

| 服务 | 端口 | 说明 |
|------|------|------|
| ai-service | 8081 | 后端（8080 被占用时用 8081） |
| ai-ui (dev) | 5173 | 范式1 前端开发服务器 |
| ai-ui-vercel (dev) | 5174 | 范式2 前端开发服务器 |

### 3. AI 模型配置

默认使用 DeepSeek `deepseek-v4-flash` 模型，温度 0.2，最大 token 4096。
可在 `application.yml` 的 `app.ai` 节点修改。

## 快速启动

### 启动后端（ai-service）

```bash
cd ai-service

# 设置环境变量（PowerShell 示例）
$env:DEEPSEEK_API_KEY = "sk-your-api-key"

# 编译
mvn clean package -DskipTests -s maven-settings.xml

# 启动
java -jar target/ai-service.jar
```

> **Maven 镜像说明**：项目自带 `maven-settings.xml`，配置了阿里云公共仓库镜像，
> 解决国内访问 Maven Central 慢的问题。使用 `-s maven-settings.xml` 指定。

### 启动前端·范式1（ai-ui，后端 Agent Loop）

```bash
cd ai-ui
yarn install
yarn dev
# 访问 http://localhost:5173
```

### 启动前端·范式2（ai-ui-vercel，前端 AI Runtime）

```bash
cd ai-ui-vercel
yarn install
yarn dev
# 访问 http://localhost:5174
```

> ai-ui-vercel 需要后端运行（透明代理在 8081 端口）。
> 前端通过 Vite dev proxy 将 `/api/ai/proxy` 转发到后端。

## 项目结构

```
ai-example/
├── README.md                        # 本文档
├── .gitignore
│
├── ai-service/                      # 后端服务
│   ├── pom.xml
│   ├── maven-settings.xml           # 阿里云镜像配置
│   └── src/main/
│       ├── java/com/example/ai/
│       │   ├── AiServiceApplication.java    # 启动类
│       │   ├── config/                      # 配置类
│       │   ├── controller/                  # REST 控制器
│       │   │   ├── AiController.java        # AI 对话入口（范式1）
│       │   │   ├── AiProxyController.java   # 透明 SSE 代理（范式2）
│       │   │   ├── ConfigController.java    # 配置项 CRUD
│       │   │   └── TaskController.java      # 任务管理
│       │   ├── dto/                         # 数据传输对象
│       │   ├── entity/                      # JPA 实体
│       │   ├── repository/                 # JPA 仓库
│       │   ├── service/                    # 业务逻辑
│       │   │   ├── AiService.java           # Agent Loop + DeepSeek 调用
│       │   │   ├── TaskService.java         # 任务状态机
│       │   │   ├── PlannerService.java     # 执行计划生成
│       │   │   └── DataSeedService.java     # 数据初始化
│       │   └── tool/                       # 工具系统
│       │       ├── ToolDefinition.java
│       │       ├── ToolDiscoveryService.java
│       │       ├── ScenarioDefinition.java
│       │       └── FrontendTools.java       # 前端工具影响描述
│       └── resources/
│           ├── application.yml              # Spring Boot 配置
│           ├── tools.yaml                   # 13 个工具定义
│           └── scenarios.yaml               # 场景状态图定义
│
├── ai-ui/                           # 前端·范式1（后端 Agent Loop）
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── api/                    # REST API 调用层
│       ├── components/             # AiPanel / SpreadSheet / StepHeader / SchemaFormRenderer
│       ├── router/
│       ├── stores/                 # Pinia: ai / task / config
│       ├── styles/
│       ├── utils/
│       │   ├── excel-io.js                  # SpreadJS ExcelIO 封装（模板/导入/导出）
│       │   └── frontend-tool-registry.js   # 工具信任分级 + 执行逻辑
│       └── views/
│           ├── DashboardView.vue
│           └── wizard/             # 向导步骤视图（9 个步骤页）
│
├── ai-ui-vercel/                    # 前端·范式2（前端 AI Runtime）
│   ├── package.json
│   ├── vite.config.js               # 含 /api/ai/proxy 代理配置
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── ai/
│       │   ├── provider.js          # createOpenAI({ baseURL: '/api/ai/proxy', apiKey: 'placeholder' })
│       │   └── system-prompt.js     # 系统提示词构造
│       ├── components/             # 复用 ai-ui 的组件
│       ├── mock/
│       │   └── configDefs.js        # 配置定义 + 初始行数据（从后端 DataSeedService 平移）
│       ├── router/
│       ├── stores/
│       │   ├── ai.js               # streamText + stopWhen(stepCountIs(10)) + fullStream 消费
│       │   ├── task.js             # localStorage 持久化
│       │   └── config.js           # 本地内存数据操作
│       ├── styles/
│       ├── tools/
│       │   ├── index.js             # 13 个工具定义（带 execute，在浏览器内执行）
│       │   ├── registry.js         # 工具 UI 元数据 + 信任分级
│       │   └── runtime.js          # 暂停-恢复运行时（waitForConfirm / waitForForm）
│       ├── utils/
│       │   └── excel-io.js          # SpreadJS ExcelIO 封装（与 ai-ui 同签名）
│       └── views/                   # 复用 ai-ui 的向导视图
│
├── docs/
│   └── design-and-implementation.md # 详细设计与实现文档
│
├── .trae/
│   └── documents/                    # 阶段设计文档（需求拆解 + 实施计划）
│       ├── ai-ui-vercel-frontend-ai-runtime.md
│       ├── collect-user-input-and-progress-sync.md
│       └── excel-capabilities-plan.md
│
└── memory/                          # AI 助手记忆文件（经验沉淀）
    ├── user-profile.md
    ├── project-memory.md
    └── topics.md
```

## 业务场景与工具系统

### 4 种业务场景

| 场景 | 代码 | 步骤状态图 |
|------|------|------------|
| 导出配置 | EXPORT | SELECT_SCENARIO → SELECT_DEFS → QUERY_COND → RESULT |
| 导入配置 | IMPORT | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH |
| 新增配置 | ADD | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH |
| 修改配置 | MODIFY | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH |

### 13 个工具

| 工具 | 类型 | 说明 |
|------|------|------|
| `navigate_step` | 自动执行 | 跳转向导步骤 / 切换场景 |
| `get_workspace_state` | 自动执行 | 查询当前工作区状态（只读） |
| `list_config_defs` | 自动执行 | 列出配置定义摘要 |
| `get_config_def` | 自动执行 | 查询单个配置定义字段详情 |
| `select_definitions` | 自动执行 | 批量选择 / 取消配置项 |
| `confirm_complete` | 自动执行 | 完成当前步骤 / 结束任务 |
| `table_batch_set_field` | 需确认 | 按条件批量修改字段（破坏性） |
| `table_delete_rows` | 需确认 | 删除满足条件的行（破坏性） |
| `table_replace_values` | 需确认 | 字段值搜索替换 |
| `run_flow` | 二次确认 | 一键执行完整流程（高危不可逆） |
| `collect_user_input` | 表单输入 | Schema 驱动表单收集用户输入 |
| `excel_import` | 表单输入 | 上传 xlsx 灌入指定配置定义（依赖 SchemaFormRenderer file 类型） |
| `excel_export` | 自动执行 | 导出配置项为 xlsx 下载（单配置/多 sheet 合并） |

### 工具信任分级

```
autoExec (自动执行)          → 只读 / 导航 / 选择类，无需用户点击
needConfirm (需确认)          → 破坏性 / 不可逆，暂停等用户确认
requireDoubleConfirm (二次确认) → 高危流程（run_flow），ElMessageBox 弹框二次确认
inputMode (表单输入)          → collect_user_input / excel_import，渲染 Schema 驱动表单
```

## Excel 能力（SpreadJS ExcelIO）

两范式各维护一份 `src/utils/excel-io.js`（接口签名一致，业务逻辑可原样复制），由 `@grapecity/spread-excelio` 在浏览器内完成 xlsx 处理，无需后端介入。

| 能力 | 入口 | 实现 |
|------|------|------|
| 模板下载 | StepViewDefs「下载 Excel 模板」按钮 | `generateTemplate(configDef)` 离线 Workbook + 内嵌 Excel 原生 DataValidation（下拉/数字范围/必填） |
| Excel 导入 | StepViewDefs「上传 Excel 导入」按钮 / AI 触发 `excel_import` | `importExcel(file, configDef)` 解析表头映射 + 类型转换 + 必填/选项校验 → `batchSave` 灌入 |
| 导出页在线编辑 | StepResult 预览弹窗「只读↔编辑」切换 | SpreadSheet 组件 + `:key` 强制重挂切换 `isProtected` |
| Excel 导出 | StepResult 弹窗「导出此配置 Excel」/「导出全部 Excel」 / AI 触发 `excel_export` | `exportExcelByRows` / `exportExcelMultiSheet` 离线 Workbook + ExcelIO.save |

**长选项兜底**：select 列 `options.join(',').length > 200` 时，改用本 sheet 远离数据区的隐藏列（`colIdx+200`）写入选项 + DV 公式 `=$L$1:$L$N` 引用，规避 Excel List 公式 255 字符上限。

**大文件提示**：导入 `>5MB` 文件时 console 警告 + `setTimeout(0)` 让出一帧避免 UI 冻结。

**SpreadJS LicenseKey**：评估模式有水印和功能限制。两范式 `main.js` 已通过 `import.meta.env.VITE_SPREADJS_KEY` 注入（`.env.local` 配置，不入库），正式使用需购买授权。

## API 接口

### ai-ui（范式1）使用的后端 REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/chat` | AI 对话（后端 Agent Loop） |
| POST | `/api/ai/tool-result` | 提交前端工具执行结果（暂停-恢复） |
| GET | `/api/tasks` | 任务列表 |
| POST | `/api/tasks` | 创建任务 |
| GET | `/api/config/definitions` | 配置定义列表 |
| GET | `/api/config/data/{defId}` | 配置项数据 |

### ai-ui-vercel（范式2）使用的后端接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/proxy/**` | 透明 SSE 代理（注入 api-key，转发到 DeepSeek） |

> 范式2 的所有业务逻辑（Agent Loop、工具执行、数据管理）都在前端完成，
> 后端仅负责透明代理，避免 api-key 泄露。

## 核心设计决策

1. **API Key 安全**：真实 DeepSeek API Key 仅存在于后端环境变量中，前端代码使用 `'placeholder'` 占位符，由后端 `AiProxyController` 注入真实 Key 转发请求。

2. **Vercel AI SDK 6.x 多步工具调用**：使用 `stopWhen: stepCountIs(10)` 而非已移除的 `maxSteps`（SDK 6.x API 变更），使 AI 能链式调用多个工具并基于结果续行。

3. **DeepSeek thinking 模式**：代理层统一注入 `thinking={"type":"disabled"}` 关闭思考链，避免带 tools 参数时中间轮 `reasoning_content` 回传 400 错误。

4. **暂停-恢复机制**：工具 `execute` 函数内 `await waitForConfirm()` / `waitForForm()` 设置 `pendingInteraction` 响应式状态，AiPanel 据此渲染交互卡片，用户操作后 `resolveInteraction()` 解除 Promise，SDK 续行 multi-step loop。

5. **Progressive Disclosure**：按 `scenario + step` 动态过滤工具集，AI 每轮只能看到当前步骤暴露的工具，减少 token 消耗并防止越权操作。

6. **本地数据持久化**（范式2）：任务和配置数据通过 localStorage 持久化，实现"创建即持久化、刷新可恢复"，无需后端数据库。

## 开发文档

- [详细设计与实现文档](docs/design-and-implementation.md) — 系统架构、工具系统、交互机制、前后端实现、接口契约、经验沉淀
- [AI 助手记忆文件](memory/) — 项目开发过程中的经验沉淀和决策记录

## License

MIT
