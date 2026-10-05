# ai-example-kimi-1 — 配置快速实施平台（AI 辅助）

Spring Boot 3.5.14 + JDK 21 + Spring AI 1.1.8（后端 AI Runtime）+ Vue 3 + GrapeCity SpreadJS 17.1.5 的全栈示例：
动态配置项的导出/导入快速实施任务向导 + 常驻 AI 对话栏（工具调用、渐进式披露、上下文感知）。

## 目录结构

```
backend/    Spring Boot 后端（Maven）
frontend/   Vue 3 前端（Yarn + Vite）
docs/       需求/架构/API契约/测试文档
```

## 快速开始

前置：JDK 21、Maven 3.9、Node 18+、Yarn。

```bash
# 后端（端口 8090）
cd backend
mvn package -DskipTests
java -jar target/quickstart-backend-1.0.0.jar

# 前端（端口 5271，代理 /api → 8090）
cd frontend
yarn install
yarn dev
```

打开 http://localhost:5271 （默认进入任务列表页）。

可选环境变量：
- `DEEPSEEK_API_KEY`：DeepSeek API Key（application.yml 中已内置演示 Key 作为默认值）
- `VITE_SPREADJS_KEY`（frontend/.env.local）：SpreadJS 授权 Key，缺省时仅显示水印，功能可用

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-requirements.md](docs/01-requirements.md) | 需求规格说明书 |
| [docs/02-architecture.md](docs/02-architecture.md) | 技术方案与架构决策（AI Runtime、状态同步、渐进式披露等） |
| [docs/03-api-contract.md](docs/03-api-contract.md) | REST/SSE/AI 工具契约 |
| [docs/04-test-cases.md](docs/04-test-cases.md) | 测试用例 |
| [docs/05-verification.md](docs/05-verification.md) | 验证结果记录 |
