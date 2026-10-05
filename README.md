# 配置快速实施平台（ai-example-kimi-2）

AI 辅助的动态配置管理系统：**左侧业务工作区（多步骤任务向导）+ 右侧常驻 AI 对话栏**，双通路功能等价、状态实时联动、架构解耦。

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5.14 · JDK 21 · Spring AI 1.1.8（ChatClient/ChatMemory/@Tool 高层 API）· H2（文件模式）· Maven |
| 前端 | Vue 3 · Element Plus · TailwindCSS 3 · GrapeCity SpreadJS **17.1.5**（ExcelIO+JSZip 承担全部 Excel 能力）· Vite 6 · yarn |
| AI | OpenAI 兼容接入 DeepSeek `deepseek-flash`（Function Calling + SSE 流式），后端 AI Runtime，无前端 Runtime |

## 功能总览

- **动态配置项**：8 个种子配置项覆盖全局/地区/项目三层级，字段结构（类型/必填/枚举/业务键/依赖）全部动态定义与渲染
- **导出配置任务**：选择配置 → 动态查询条件 → 异步导出（实时进度）→ SpreadJS 在线编辑 → 单个/打包下载
- **导入配置任务**：选择配置 → 模板下载/文件上传（xlsx·zip 按文件名匹配）/在线编辑 → 检查（8 类规则+跨配置依赖）→ 暂存导入 → 范围内替换发布
- **任务管理**：任务创建即持久化（H2 文件库）、任务中心查看历史/状态/进度、恢复继续、取消
- **AI 对话**：工具渐进式披露（按任务类型三组 @Tool）、页签会话（sessionStorage + 服务端内存记忆，刷新不丢、新页签全新）、AI 执行工具实时联动工作区
- **联动与解耦**：REST 与 AI 工具共用同一服务层（单一事实源），SSE 广播状态/进度/活动事件；去掉任一方另一通路完整可用

## 快速启动

### 0. 前置
- JDK 21、Maven 3.9（可用 IDEA 内置）、Node 18+ / yarn 1.22
- 端口：后端 **18290**、前端 **15271**（可在 `backend/src/main/resources/application.yml` 与 `frontend/vite.config.js` 修改；启动前请确认端口空闲）

### 1. 后端

```bash
cd backend
# API Key 经环境变量注入（不要写入任何入库文件）
# Windows PowerShell:  $env:DEEPSEEK_API_KEY="sk-xxx"
# Windows CMD:         set DEEPSEEK_API_KEY=sk-xxx
mvn -s maven-settings.xml package -DskipTests
java -jar target/quickstart-backend.jar
# 或开发模式: mvn -s maven-settings.xml spring-boot:run
```

首次启动自动建库（`backend/data/quickstart_db.mv.db`）并初始化种子数据。

### 2. 前端

```bash
cd frontend
yarn install
yarn dev          # http://localhost:15271
```

可选：SpreadJS 正式授权 key 写入 `frontend/.env.local`（`VITE_SPREADJS_KEY=xxx`，不入库）；缺省为评估模式（仅水印，功能可用）。

### 3. 运行测试

```bash
cd backend && mvn -s maven-settings.xml test   # 38 个测试
cd frontend && yarn build                       # 构建验证
```

## 使用指引

1. **工作区通路**：首页点击「导出配置/导入配置」卡片 → 按向导步骤操作；
2. **AI 通路**：右侧对话栏直接说，例如：
   - 「现在有哪些配置项？」
   - 「帮我创建一个导出任务，导出系统参数配置和成员角色字典，参数名包含 param.1，然后开始导出」
   - 「创建导入任务，选择角色字典和项目成员配置，把数据提交好并检查」
3. AI 执行工具时，左侧工作区实时同步；文件上传/下载类操作 AI 会引导你在工作区完成。

## 目录结构

```
backend/   后端（Spring Boot + Spring AI）
frontend/  前端（Vue3 + SpreadJS）
docs/      需求规格 / 技术方案 / 实现说明 / 测试用例 / 验证结果
scripts/   pre-commit 密钥扫描钩子（git config core.hooksPath scripts/githooks 启用）
```

## 文档索引

1. [需求规格说明书](docs/01-需求规格说明书.md)
2. [技术方案设计](docs/02-技术方案设计.md)（架构/ADR/数据模型/API/SSE 契约）
3. [实现方案说明](docs/03-实现方案说明.md)
4. [测试用例](docs/04-测试用例.md)
5. [测试验证结果](docs/05-测试验证结果.md)（含缺陷闭环记录）

## 安全说明

- `DEEPSEEK_API_KEY` 仅经环境变量注入，仓库内只有 `${DEEPSEEK_API_KEY:dummy-not-set}` 占位；
- 启用 pre-commit 钩子防止密钥误提交：`git config core.hooksPath scripts/githooks`；
- 前端不持有任何模型服务凭证（后端 AI Runtime）。
