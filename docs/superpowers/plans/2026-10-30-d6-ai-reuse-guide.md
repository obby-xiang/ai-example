# D6 AI 对话复用与落地指南实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 编写 D6，让新项目能仅迁移 AI 对话能力，并用 pre-commit 强制后续变更显式复核 D6 是否需更新。

**Architecture:** 以当前代码为唯一事实源，按“可搬运行时 + 必换适配点 + 不迁移清单 + 11 步落地”组织正文。门禁采用轻量路径分类与人工确认记录，不伪造语义级正确性证明。

**Tech Stack:** Markdown、Node.js 内置 `node:test`、Shell pre-commit。

**Spec:** `docs/research/规划-核心技术文档体系与编写大纲-2026-10-24.md`（D6 章节）及用户本轮明确范围。

## Global Constraints

- 只写 AI 对话迁移，不写配置定义、任务、导入导出发布等业务功能迁移。
- 明确先决条件、租户用户、多语言、跨线程上下文、ID 生成、模型、Redis、SSE/HITL、存储、前端、部署、测试。
- 明确不迁移模拟多实例、模拟 Redis、模型桩、Fake/InMemory、harness、Playwright 端口段与业务测试语料。
- D1~D5 尚未成文，不虚构其章节锚点；引用改为代码路径与既有 D6 内部编号。
- 主仓施工，子 agent 编写，主控代码级验收后提交推送；文档测试只允许精确单点命令。

## Review Focus

- 把 `ai/**` 当成零业务耦合包：必须列出业务耦合文件及删改方式。
- 把演示身份头当成认证：必须要求替换真实身份源并保持前后端路径边界一致。
- 把 TTL 当成万能传播：必须列出显式记录身份和未装饰执行器。
- 把时间戳发号当分布式唯一：必须保留 `IdGenerator` 替换点与撞号语义。
- 把 Redis/模拟设施当可选或生产资产：必须区分生产硬依赖与不得迁移的测试替身。
- 门禁只证明人工确认，不证明文档内容正确；不得无条件逼改文档。

---

### Task 1: 编写 D6 正文

**Files:**
- Create: `docs/delivery/06-新项目复用与落地指南.md`

**Interfaces:**
- Consumes: 当前代码、D6 大纲、本计划 Global Constraints。
- Produces: 可独立执行的 AI 对话迁移指南，含 11 步落地表与常见失败模式。

- [ ] **Step 1: 写正文**
  - 三种复用模式，重点模式 B（仅 AI Runtime）与模式 C（单项能力）。
  - 先决条件与版本、可搬资产/适配层/禁止照抄清单。
  - 租户用户与真实身份源、前后端请求头边界、Redis 键身份段。
  - 后端 `messages_*`、前端 locales、模型侧中文常量与 `Accept-Language`。
  - `RequestContextUtils.useContext`、TTL decorator、显式 `RunIdentityScope`、未装饰执行器清单。
  - `IdGenerator.nextId()`、时间戳实现、撞号 503、后续分布式实现替换点。
  - OpenAI 兼容配置、Redis 硬依赖、会话/run/记忆/附件/反馈、16 帧、HITL、工具披露、KB、部署运维。
  - 不迁移业务功能与模拟/测试设施；需重写的契约测试判据。
  - 11 步迁移表，每步给动作、对接点、完成判据；常见失败模式不少于 12 条。
- [ ] **Step 2: 事实自查**
  - 不引用不存在的 D1~D5 章节，不写本仓测试数量为新项目指标，不虚构认证或分布式 ID。
  - 明确生产必需 Redis，明确模拟多实例/Redis/模型/E2E 设施不迁移。
- [ ] **Step 3: 提交**
  - `docs(d6): 编写AI对话复用与落地指南`

### Task 2: 增加轻量 D6 更新复核门禁

**Files:**
- Create: `scripts/ci/check-d6-sync.mjs`
- Create: `scripts/ci/check-d6-sync.test.mjs`
- Create: `docs/delivery/d6-review.json`
- Modify: `scripts/githooks/pre-commit`
- Modify: `scripts/githooks/README.md`
- Modify: `scripts/README.md`

**Interfaces:**
- Consumes: Task 1 的 D6 文件。
- Produces: pre-commit 在 AI 对话相关变更缺少有效人工确认时返回 1。

- [ ] **Step 1: 先写失败用例**
  - 覆盖核心 AI 文件无确认阻断、业务文件放行、测试设施只告警、`synced` 必须同批含 D6、`not-affected` 必须有具体理由、指纹变化使旧确认失效、坏 JSON 返回 2。
  - 自检覆盖租户、ID、SSE、前端 store、工具与多语言路径。
- [ ] **Step 2: 单点运行确认失败**
  - `node --test --test-name-pattern="<单个用例名>" scripts/ci/check-d6-sync.test.mjs`，逐个执行。
- [ ] **Step 3: 实现最小门禁**
  - 路径分 `block/warn/doc/infra`；`block` 必须确认，`warn/infra/doc` 不阻断。
  - 指纹只取本次暂存的 block 文件路径、blob 与规则版本；`d6-review.json` 自身不计指纹。
  - `synced` 要求 D6 同批变更；`not-affected` 要求非空具体理由；不要求每次改 D6。
  - pre-commit 保留敏感扫描并追加 Node 检查；无 Node 时明确告警，CI/人工复核仍是必要补充，不宣称绝对防绕。
- [ ] **Step 4: 单点运行确认通过并更新说明**
  - 同一命令逐例通过；说明暂存、确认、豁免、局限与 `--no-verify` 风险。
- [ ] **Step 5: 提交**
  - `test(d6): 增加AI对话变更同步复核门禁`

### Task 3: 主控验收与交付

**Files:**
- Review only.

- [ ] **Step 1: 审查正文覆盖与代码事实**
  - 用户点名的每项均有独立落点；不迁移清单与测试契约不混淆。
- [ ] **Step 2: 审查门禁**
  - 仅阻断需要人工确认的变更，不制造批量测试、不伪造语义验证、不破坏敏感扫描。
- [ ] **Step 3: 推送**
  - 按明确文件清单提交并推送，报告文件、验证与门禁局限。
