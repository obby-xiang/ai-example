# AI 辅助配置快速实施平台

一个**后端 AI Agent Runtime + 动态配置项管理**的全栈实现：业务侧提供配置定义管理、数据浏览、导入/导出/发布向导与任务中心；AI 侧在对话栏内按页面渐进式披露工具，可驱动业务动作、在对话内渲染结构化表单、并在高风险操作前挂起等待人类确认。

- 需求基线：`docs/02-需求设计文档-v2.2-冻结版.md`
- 技术方案：`docs/03-技术方案文档-v2.1.md`
- 决策登记：`docs/adr/DECISION-REGISTER.md`（DC-01~18）
- 全部文档导航：`docs/README.md`

---

## 目录结构

```
<REPO_ROOT>/
├── backend/            # Spring Boot 后端（业务模块 + AI Runtime）
│   ├── pom.xml
│   ├── maven-settings.xml          # 仓库内 Maven 镜像设置（阿里云，加速拉依赖）
│   ├── config/application.yml.example   # 本地配置示例（复制为 application.yml 后填密钥，不入库）
│   └── src/main/java/com/example/configmgr/
│       ├── ai/         # AI Runtime：config/exec/form/gate/memory/run/session/tool/tools/web
│       ├── definition/ data/ masterdata/ task/ job/ excel/ file/ seed/   # 业务模块
│       └── common/ config/
├── frontend/           # Vue 3 + TypeScript 前端（五页 + 常驻 AI 面板）
│   ├── package.json  vite.config.ts  .env.example
│   └── src/{views,components,stores,api,router,types,utils}/
├── scripts/            # E2E 验证脚本 / CI 上游桩 / 提交前敏感信息钩子（见 scripts/README.md）
│   ├── verify-e2e.ps1
│   ├── ci/{stub-upstream.mjs,stub-routes.json}
│   └── githooks/{pre-commit,scan-history.sh,scan-lib.sh,README.md}
├── spike/              # 独立可运行 PoC 工程：sp01ab / sp01c / sp01d / sp02
└── docs/               # 设计、决策与证据（六类分区，见 docs/README.md）
    ├── 02-需求设计文档-v2.2-冻结版.md   # 现行需求基线
    ├── 03-技术方案文档-v2.1.md          # 现行技术方案
    ├── M1-出口评审材料.md  M2-排期计划.md
    ├── adr/            # 决策：ADR 卡 + DC 登记表
    ├── s4/             # 合流规格（S4.2 AI Runtime / S4.3 业务机制 / S4.4 前端）
    ├── evidence/       # 证据：验证记录与原始附件（按主题分组）
    ├── research/       # 外部设计研究与借鉴
    ├── spike/          # PoC 决策卡（SP-01ab / 01c / 01d / 02）
    └── merge/          # 前期合流包（历史阶段产物，勿当现行设计）
        （另有 5 份源分支遗留参考件：requirements / technical-design /
          implementation-plan / test-cases / verification-results，同样勿当现行设计）
```

---

## 快速启动

### 前提条件

| 依赖 | 要求 | 说明 |
| --- | --- | --- |
| JDK | **21** | `pom.xml` 的 `java.version=21` |
| Maven | 3.9+ | 下文用 `<MAVEN_HOME>` 指代你的 Maven 安装目录（`<MAVEN_HOME>/mvn` 或 Windows 下的 `<MAVEN_HOME>/mvn.cmd`） |
| Node.js | **22** | 与 CI 一致；包管理用 yarn（仓库内含 `yarn.lock`） |
| Redis | 7.x 或 Memurai | **AI 功能硬依赖**：会话记忆、确认门挂起态、会话锁都在 Redis。监听 `127.0.0.1:6379` |

> 无 AI key 时后端仍可正常启动，业务功能（导出/导入/发布）完全可用，仅 AI 端点惰性降级为 `503 AI_UNAVAILABLE`；Redis 不可用时业务 API 正常，AI 端点返回 `503 AI_REDIS_UNAVAILABLE`。

### 启动后端

```powershell
cd backend

# 1) 注入模型密钥（见下方「环境变量」；也可改为 backend/config/application.yml）
$env:AI_API_KEY = "***"

# 2) 打包（-s 走仓库内的镜像设置文件）
& "<MAVEN_HOME>/mvn.cmd" clean package -DskipTests -s maven-settings.xml

# 3) 启动，默认端口 8081
java -jar target/config-mgr.jar
```

Linux / macOS 把 `<MAVEN_HOME>/mvn.cmd` 换成 `<MAVEN_HOME>/mvn`，其余同上。启动后可访问 `http://localhost:8081/h2-console` 查看库（连接串见 `application.yml` 的 `spring.datasource.url`）。

### 启动前端

```powershell
cd frontend

# 1) 首次：从示例生成本地环境变量文件（.env.local 已被 .gitignore 排除）
Copy-Item .env.example .env.local

# 2) 编辑 .env.local —— 前端 dev 代理默认值已与后端（8081）对齐，开箱可用；
#    后端不在 8081 时才需要覆盖：VITE_API_BASE=http://localhost:8081

yarn install
yarn dev
```

`yarn dev` 后按 `.env.local` 的 `VITE_DEV_PORT`（默认 5200）打开页面。

---

## 环境变量

### 后端（进程环境变量，或 `backend/config/application.yml`）

| 变量 | 作用 | 默认 / 说明 |
| --- | --- | --- |
| `AI_API_KEY` | 模型 API Key | 无默认。为空则不装配模型 bean，AI 端点降级 `503 AI_UNAVAILABLE`，业务不受影响 |
| `DEEPSEEK_API_KEY` | 兜底兼容名 | 仅在 `AI_API_KEY` 未设置时生效 |
| `AI_BASE_URL` | OpenAI 兼容 base-url | 默认 `https://api.deepseek.com`（接口路径强制 `/chat/completions`） |
| `AI_MODEL` | 模型名 | 默认 `deepseek-flash` |

密钥**绝不写入版本库**。两种注入方式任选：进程环境变量（如上），或复制 `backend/config/application.yml.example` 为 `backend/config/application.yml` 后填写（该文件已在 `.gitignore` 中排除）。

### 前端（写在 `frontend/.env.local`，由 `.env.example` 复制）

| 变量 | 作用 | 默认 | 注意 |
| --- | --- | --- | --- |
| `VITE_API_BASE` | dev server 的 `/api` 代理目标 | `http://localhost:8081` | **默认值已与后端端口（8081）对齐**，开箱即可用；后端换地址时在此覆盖（`vite.config.ts` 的 `DEFAULT_API_BASE` 只作 dev 便利默认，不构成对"禁止硬编码端口"口径的翻案——禁止的是组件/业务源码内不可覆盖的硬编码） |
| `VITE_DEV_PORT` | dev server 端口 | `5200` | 端口被占用时改这里（配置了 `strictPort`，不会自动顺延） |
| `VITE_SPREADJS_KEY` | SpreadJS 授权 Key | 空 | 留空为评估模式：仅显示水印，功能可用 |

---

## 端口一览

> **端口唯一事实源**：后端只用 `backend/src/main/resources/application.yml` 的 `server.port`；前端 dev 只用 `frontend/vite.config.ts` 的 `DEFAULT_DEV_PORT`。配置树里**不存在** `app.server.backend-port` / `app.server.frontend-port` 之类无绑定死键（已于 M2-T4 整块删除）。下表四个端口由文档门禁 `scripts/ci/check-docs.mjs` 与这两个事实源逐值对账，改端口必须同批改表。

| 服务 | 端口 | 事实源 |
| --- | --- | --- |
| 后端 | 8081 | `backend/src/main/resources/application.yml` 的 `server.port` |
| 前端 dev server | 5200（可用 `VITE_DEV_PORT` 覆盖） | `frontend/vite.config.ts` 的 `DEFAULT_DEV_PORT` |
| E2E 后端 | 18330 | `scripts/verify-e2e.ps1` 的 `-Base` 默认值 |
| 上游桩（E2E/CI） | 18399 | `scripts/ci/stub-upstream.mjs` 的 `--port` 默认值 |
| H2 Console | `http://localhost:8081/h2-console` | `spring.h2.console.path` |

---

## 技术栈

| 层 | 技术 | 版本 / 事实源 |
| --- | --- | --- |
| 后端框架 | Spring Boot | 3.5.14（`backend/pom.xml` 的 parent） |
| 运行时 | JDK | 21（`pom.xml` 的 `java.version`） |
| AI | Spring AI | 1.1.8（`pom.xml` 的 `spring-ai.version`） |
| 数据库 | H2 文件库 + Flyway 迁移 | 版本随 Spring Boot 管理（`pom.xml` 只声明依赖、不写版本）；迁移脚本 `backend/src/main/resources/db/migration/` |
| 缓存/会话 | Redis（会话记忆、挂起态、分布式锁） | 外部依赖，见「快速启动」 |
| 前端框架 | Vue 3 | `^3.5.13`（`frontend/package.json`） |
| 语言 | TypeScript | `^5.7.2`（纯 JS 禁用） |
| 构建工具 | Vite | `^6.0.7`（`frontend/package.json`） |
| 状态管理 | Pinia | `^2.3.0`（`frontend/package.json`） |
| UI / 样式 | Element Plus + TailwindCSS | `^2.9.3` / `^3.4.17`（`frontend/package.json`） |
| 表格 | GrapeCity SpreadJS | 17.1.5（`frontend/package.json`） |
| 包管理 | Maven（后端） / yarn（前端） | — |

---

## 特性清单

### 业务模块

- **任务中心**：任务列表、分页与过滤、进度刷新；
- **导出向导**：配置定义选择 → 查询条件 → 导出作业 → 打包下载；
- **导入向导**：模板下载、文件上传与匹配 → 预检查（必填/主键重复/引用存在性）→ 导入 → 发布；
- **配置定义管理**：动态字段定义（类型/必填/主键/引用）与层级（GLOBAL/REGION/PROJECT）；
- **数据浏览**：SpreadJS 网格展示与编辑配置数据；
- **作业与进度**：导出/预检查/导入/发布四类作业异步执行，带进度事件流、取消与僵尸作业恢复；
- **发布语义**：范围替换（行级 upsert + 范围差集删除）+ 乐观锁冲突检测。

### AI 交互契约

| 能力 | 说明 |
| --- | --- |
| 官方工具循环 | 走 Spring AI 官方 `ChatClient` 循环，不自研循环（不启用虚拟线程，并发承载为平台线程 + 有界池） |
| 渐进式披露 | 工具按页面/任务类型披露（`@ToolScope`），模型只能看到当前上下文可用的工具 |
| 生成式表单 | `generative_form` 工具 + 前端 `FormRenderer`：对话内渲染结构化表单收集筛选条件或澄清信息；字段类型限六型白名单（text/number/boolean/date/enum/multi_select），禁止 HTML/脚本注入；后端两道闸门（schema 白名单校验 + 回灌类型复核） |
| 确认门（HITL） | 高风险工具（`start_export` / `start_precheck` / `start_import` / `start_publish`）执行前挂起，渲染确认卡片等待人类放行；拒绝或超时以"未执行"语义回填，工具不执行 |
| 挂起与回灌 | 前端通道工具（打开导出编辑器、下载文件、跳转、选择定义、设置条件、确认步骤、生成式表单）在后端是哨兵桩，副作用在浏览器，结果经 `POST /api/ai/frontend-tool-result` 回灌续跑 |
| 取消与续跑 | 轮次取消（`POST /api/ai/cancel/{runId}`）、断线重挂（`GET /api/ai/events/{runId}`，按 `lastSeq` 差量补发）、应用启动自动续跑（仅续"已有人类结论/无需外部输入"的轮次，仍挂起的确认门一律跳过） |
| 会话与帧契约 | 会话记忆存 Redis（滑动 TTL 6h，非持久化）；同 `sessionId` 串行化（占用中返回 409）；SSE 自有帧契约，业务帧带单调 `seq`，心跳不占业务序号也不入归档 |

---

## 测试与 CI 门禁

### 本地跑法

```powershell
# 后端单测（Spring Boot 上下文 + H2 内存库 + Flyway；不依赖 Redis 与 AI key）
cd backend
& "<MAVEN_HOME>/mvn.cmd" -B test -s maven-settings.xml
# 2026-10-08 实测：Tests run: 249, Failures: 0, Errors: 0, Skipped: 0（计数随测试演进，以 mvn test 输出为准）

# 前端类型检查 + 生产构建
cd frontend
yarn typecheck      # vue-tsc --noEmit
yarn build          # 含 vue-tsc + vite build

# 端到端（需后端已启动 + Redis + AI key；29 用例 —— 桩模式加 -EnableGf6 启用 GF6 入参闸门用例）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 `
    -Base http://127.0.0.1:18330 -BackendLog <后端 stdout 日志路径> -ArtifactDir <证据落盘目录>
```

E2E 脚本的参数、退出码口径、桩模式（`E2E_STUB_MODE=1` 零容忍）与证据落盘规则见 **`scripts/README.md`**。

### CI 五 job（`.github/workflows/ci.yml`）

| job | 内容 | 依赖 |
| --- | --- | --- |
| `backend` | JDK 21 + `mvn -B test`（全量单测） | — |
| `frontend` | Node 22 + `yarn install --frozen-lockfile` + `yarn typecheck` + `yarn build` | — |
| `e2e` | Redis service 容器 + 桩上游（`scripts/ci/stub-upstream.mjs --violate-form-type`）+ `scripts/verify-e2e.ps1` 29 用例（`-EnableGf6`） | `backend` |
| `docs` | 文档一致性门禁（`node scripts/ci/check-docs.mjs`，6 条断言）｜**阻断** | — |
| `scan-history` | 全历史脱敏扫描（`scripts/githooks/scan-history.sh --files-only`，独立 job、10 分钟上限）｜**报告制不计门禁** | — |

触发条件为 `push` / `pull_request` 到 `main`。门禁属性（裁决①，2026-10-09 口径调整）：`backend` / `frontend` / `e2e` / `docs` 为**阻塞门禁**；`scan-history` 为**报告制非阻断** —— 它的命中全部落在**历史 blob**（当前工作树 / 暂存区口径 0 命中；当前 HEAD 全量实跑 302 处 / 40 个历史路径，其中 **10 个**路径仅存于历史、不在工作树。口径换算：278 = CI 模拟时点实测（施工报告 §14#1，其 USER 规则未生效，含仅存于历史的 9 个路径），302 = 当前 HEAD 全量实跑（红队复核 §d），两数勿混读），删工作树文件消除不了，唯一手段是历史改写（需用户专项授权，本周期不动），故不以「失败即红」接线：命中照常全量输出 + 上传 artifact + job summary 标注，但不使 CI 红（"扫描器未完成"这类环境/参数错误仍为红）。**工作树口径的阻断面不在该 job**：由 `docs` job（断言 ⑤ 命名纪律按 `git ls-files` 全量扫描；断言 ① 盘符路径只覆盖 4 个导航/口径入口文档）与 `pre-commit` 钩子（本机暂存区）承担，二者保持阻断。`scan-history` 仍不设 `needs`、不阻塞主路径。

> **桩的口径（不得扩大解释）**：CI 的 E2E 跑在**上游替身**上，它只证明"桩给出的表单/工具调用被产品链路正确消费"，**不证明模型服从率**；真模型的 E2E 维持本地手动跑。

### 提交前敏感信息防护

仓库内置 pre-commit 钩子，提交前扫描暂存区新增行，命中疑似密钥、本机绝对路径或个人用户名即阻断：

```sh
git config core.hooksPath scripts/githooks   # 仓库根执行一次
```

规则、豁免清单、逃生口与全历史扫描见 `scripts/githooks/README.md`。

---

## 文档怎么读

入口是 **`docs/README.md`**（一张表覆盖需求基线 / 技术方案 / 决策 / 排期 / 规格 / 证据 / 研究 / PoC / legacy 参考件）。三条最短路径：

1. **想知道要做什么** → `docs/02-需求设计文档-v2.2-冻结版.md` §1；
2. **想改某个机制** → 先查 `docs/adr/DECISION-REGISTER.md` 是否已有生效决策，再读对应 ADR 卡与 `docs/s4/` 规格，最后看 `docs/evidence/` 里的实测证据；
3. **想知道为什么这么定** → `docs/adr/DECISION-CARDS.md` 与 `docs/spike/` 决策卡（含被否决方案的实测依据）。

本文档只讲"如何跑、如何验、去哪读"，不搬运决策论证。
