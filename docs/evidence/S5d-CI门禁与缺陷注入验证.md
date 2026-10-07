# S5d CI 门禁骨架与缺陷注入验证

> 日期：2026-10-07　执行：施工子智能体（DeepSeek V4 Flash）　仓库：`<REPO_ROOT>`（分支 `main`）
> 范围：新增 `.github/workflows/ci.yml`（1 个提交）+ 本证据文档（1 个提交）。
> 缺陷注入只发生在一次性分支 `ci-defect-injection-s5d` 内，该分支已删除，main 历史未受污染。
>
> **结论**：① 门禁骨架在 main 上基线为绿（两个 job 全绿）；② 两次注入缺陷分别被**对应 job** 拦住
> （后端注入只红后端 job、前端注入只红前端 job，且经"有/无注入"2×2 对照确认因果）；③ 一次性分支已删除。
>
> 脱敏口径：本机绝对路径一律用 `<REPO_ROOT>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<USER_HOME>` 占位；
> 仓库属主名用 `<GITHUB_OWNER>` 占位（DC-08：个人用户名不入库），run/job 以自增 ID 定位。

---

## 1. 提交清单

| 提交 | 分支 | 说明 |
|---|---|---|
| `3cf5f9b` | `main` | `ci(s5d): GitHub Actions 门禁骨架——后端编译+147 单测、前端构建+TS 类型检查` |
| 本文件所在提交 | `main` | `docs(evidence): S5d CI 门禁与缺陷注入验证——基线绿+两次注入均拦截` |

一次性分支 `ci-defect-injection-s5d` 内的提交（**已随分支删除，均为游离提交**）：

| 提交 | 说明 |
|---|---|
| `1cae997` | `test(s5d): 一次性分支触发脚手架——push 触发临时加 ci-defect-injection-s5d` |
| `ca3eeaa` | `test(s5d): 注入缺陷验证-后端——ToolResultLimiterTest 行数上限断言 200 改为 201` |
| `fdf3ce1` | `test(s5d): 注入缺陷验证-前端——main.ts ElementPlus locale 改传字符串字面量` |
| `c74ca9b` | `test(s5d): 注入缺陷验证-后端 回滚做隔离对照——复原 200，只留前端缺陷` |

其中 `1cae997` 的触发脚手架（`on.push.branches` 临时加入一次性分支名）**只为让 Actions 能在侧分支上被跑到**：
`main` 上的门禁触发始终严格为 `push → main` 与 `pull_request → main`，该行不回流 main。

---

## 2. workflow 方案要点（`.github/workflows/ci.yml`）

| 项 | 取值 |
|---|---|
| 触发 | `push` 到 `main`；`pull_request` 指向 `main` |
| 权限 | `permissions: contents: read`（门禁只读，显式收窄 `GITHUB_TOKEN`） |
| job `backend`（名：后端编译与单测） | `ubuntu-latest`，`actions/checkout@v4` + `actions/setup-java@v4`（temurin 21，maven 缓存按 `backend/pom.xml`），`run: mvn -B test`（`working-directory: backend`，`timeout-minutes: 20`） |
| job `frontend`（名：前端类型检查与构建） | `ubuntu-latest`，`actions/checkout@v4` + `actions/setup-node@v4`（node 22，yarn 缓存按 `frontend/yarn.lock`），`yarn install --frozen-lockfile` → `yarn typecheck` → `yarn build`（`working-directory: frontend`，`timeout-minutes: 20`） |
| Maven 取得方式 | 仓库无 `backend/mvnw`，故直接用 setup-java 自带 Maven（`mvn -B test`） |

**为什么 job 用 `ubuntu-latest` 而不是 `windows-latest`（判据，非偏好）**

1. 静态盘点：测试资产全部是 JUnit 5；13 个用例类用 `@SpringBootTest` 起真实 Spring 容器（H2 文件库 + Flyway 迁移），
   但用例中**无一处使用 Redis**（Redis 只出现在注释里）；`backend/src/test` 下**没有 `resources/` 目录**，
   也不依赖 `backend/config/application.yml`（该文件被 `.gitignore` 排除，CI 里必然不存在）。
2. 与 AI key 无关：`spring.ai.model.chat: none` + 无 key 时模型 bean 条件化不装配（见 `application.yml` 注释），
   业务 API 与测试不依赖 key。
3. 实测（等价环境验证）：在「无 `backend/config/`、Redis 指向无监听端口、仅 Maven Central、无镜像 settings」的
   条件下跑 `mvn -B test` → **147 例全绿 / BUILD SUCCESS（1:40）**。既无平台耦合，也就没有理由付 Windows runner 的启动与排队成本。

**E2E 不在本门禁内**：`scripts/verify-e2e.ps1` 依赖 `<MEMURAI_HOME>`（Memurai）与真实 AI key，本棒不进门禁，见 §7。

---

## 3. 基线 run（main @ `3cf5f9b`）

- run：`https://github.com/<GITHUB_OWNER>/ai-example/actions/runs/37586108801` → **success**（run id `37586108801`）

| job | job id | 结论 | 起止（UTC） |
|---|---|---|---|
| 后端编译与单测 | `112676489366` | **success** | 07:14:26 → 07:15:35（1m09s） |
| 前端类型检查与构建 | `112676489156` | **success** | 07:14:26 → 07:15:32（1m06s） |

步骤级结论（均 success）：

- 后端：`Set up job` → `Run actions/checkout@v4` → `安装 JDK 21（temurin）` → `编译并运行单测（mvn test，147 例）` → `Post 安装 JDK 21（temurin）` → `Post Run actions/checkout@v4` → `Complete job`
- 前端：`Set up job` → `Run actions/checkout@v4` → `安装 Node 22` → `安装依赖（严格按锁文件）` → `类型检查（vue-tsc --noEmit）` → `生产构建（vue-tsc + vite build）` → `Post 安装 Node 22` → `Post Run actions/checkout@v4` → `Complete job`

基线注解（**未阻断**，2 warning + 1 notice）：`actions/checkout@v4`/`setup-java@v4`/`setup-node@v4` 目标 Node 20 已被强制跑在 Node 24；
`setup-java v4` 已弃用建议升 v5；`ubuntu-latest` 自 2026-10-19 起迁移到 Ubuntu 26。→ 列入 §7 后续项。

---

## 4. 缺陷注入验证

### 4.1 取证条件与替代方案（客观限制，先行说明）

- 本机**没有 `gh`**（`gh --version` 不可用），按任务书改用 `git push` + 轮询 GitHub REST API 观察 workflow run。
  仓库为公开仓库，匿名可读 `actions/runs`、`actions/runs/{id}/jobs`、`check-runs`、`check-runs/{id}/annotations`。
- **原始作业日志（`gh run view --log-failed` 的等价物）需要仓库权限**：
  `GET /actions/jobs/{id}/logs` 匿名返回 `403 Must have admin rights to Repository.`；
  在桌面浏览器打开作业页（未登录 GitHub）显示 `Sign in to view logs`。故**本棒无法摘录 CI 原始日志正文**。
- 替代取证：用三个互相独立的来源交叉印证失败与注入点吻合 —— ① run/job/step 级结论（匿名 API）；
  ② check-run 注解（匿名 API，含失败退出码与类型错误的文件行列号）；③ 在 **CI 提交的完全相同文件内容**上、
  用 **CI 的相同命令**做本地复现（§6）。
- 另补一个「有/无注入」**2×2 全因子对照**（§4.4）：在一次性分支内把后端注入回滚后再跑一次，
  使两个 job 的失败都能被**因果地**归因到自己那条注入，而不只依赖日志文字。

### 4.2 注入 1（后端：单测断言必失败）

- 提交 `ca3eeaa`，最小改动 1 行：`backend/src/test/java/com/example/configmgr/ai/tool/ToolResultLimiterTest.java:27`
  `assertThat(...getMaxRows()).isEqualTo(200)` → `isEqualTo(201)`（生产默认值仍为 200，断言必失败）。
- run：`https://github.com/<GITHUB_OWNER>/ai-example/actions/runs/37586343278` → **failure**（run id `37586343278`）

| job | job id | 结论 | 失败步骤 |
|---|---|---|---|
| 后端编译与单测 | `112677227735` | **failure** | 步骤 4 `编译并运行单测（mvn test，147 例）` |
| 前端类型检查与构建 | `112677227513` | success | —（**同 run 内前端为绿 ⇒ 失败只出在后端**） |

- 该 job 的 check-run 注解（匿名可读）：`[failure] Process completed with exit code 1`。
- 与注入点吻合的本地复现（CI 提交内容 + CI 同命令）：见 §6.2 —— 全量 **147 例中恰好 1 例失败**，
  失败者正是被改的那条断言：`ToolResultLimiterTest.defaultLimitsAre200RowsAnd8000Chars:27　expected: 201 but was: 200`。

### 4.3 注入 2（前端：TypeScript 类型错误）

- 提交 `fdf3ce1`，最小改动 1 行：`frontend/src/main.ts:28`
  `app.use(ElementPlus, { locale: zhCn })` → `app.use(ElementPlus, { locale: 'zh-cn' })`
  （`locale` 期望 `Language` 类型，传字符串即类型不匹配）。
- run：`https://github.com/<GITHUB_OWNER>/ai-example/actions/runs/37586356892` → **failure**（run id `37586356892`）

| job | job id | 结论 | 失败步骤 |
|---|---|---|---|
| 前端类型检查与构建 | `112677272065` | **failure** | 步骤 5 `类型检查（vue-tsc --noEmit）` |
| 后端编译与单测 | `112677272173` | failure | 步骤 4（此 run 同时带着注入 1，故后端也红） |

- 该 job 的 check-run 注解（匿名可读，**行列号即吻合证据**）：

| 注解 | 行:列 | 文本 |
|---|---|---|
| failure | 28:5 | `No overload matches this call.` |
| failure | 13:1 | `'zhCn' is declared but its value is never read.` |
| failure | — | `Process completed with exit code 2` |

- 与注入点吻合：注解行号 `28:5` / `13:1` 与本地 `vue-tsc` 报的 `src/main.ts(28,5)` / `src/main.ts(13,1)` **完全一致**；
  本地完整诊断文本见 §6.3。`13:1` 是同一处改动连带产生的"导入未再被使用"，同源同因。
- 失败发生在**类型检查步骤**而不是构建步骤，正是本门禁把 `yarn typecheck` 单列一步的目的：类型错误与打包错误可区分。

### 4.4 隔离对照：2×2 全因子（有/无注入）

为补足"后端 job 的失败确实由那条断言引起"的因果证据（原始日志不可得，见 §4.1），在一次性分支内再提交
`c74ca9b`：**只回滚后端注入**（`200` 复原），前端注入保留。四次 run 的 job 结论构成完整 2×2：

| run id | 提交 | 后端注入 | 前端注入 | 后端 job | 前端 job | run 结论 |
|---|---|---|---|---|---|---|
| `37586330226` | `1cae997` | 无 | 无 | success | success | success |
| `37586343278` | `ca3eeaa` | **有** | 无 | **failure** | success | failure |
| `37586356892` | `fdf3ce1` | **有** | **有** | **failure** | **failure** | failure |
| `37586814785` | `c74ca9b` | 无（已回滚） | **有** | success | **failure** | failure |

读法：每个 job 的结论**只跟随自己那条注入**，与另一 job 的注入无关 —— 后端 job 在两个"有后端注入"的 run 必红、
在两个"无后端注入"的 run 必绿；前端 job 同理。故两次注入各自被**对口的 job** 拦住，不存在"红得莫名其妙"的可能。

对照 run `37586814785` 的 job：后端 `112678746305` = success（**零条 failure 注解**）；
前端 `112678746669` = failure，失败步骤 5 `类型检查（vue-tsc --noEmit）`，注解与 §4.3 完全相同（28:5 / 13:1 / exit 2）。

### 4.5 吻合性判定汇总

| 注入 | 注入点 | 被哪个 job 拦 | 被哪一步拦 | 吻合证据 |
|---|---|---|---|---|
| 后端断言 | `ToolResultLimiterTest.java:27` 期望 200→201 | 后端编译与单测（前端同 run 为绿） | `mvn test` | 该 run 前端绿+后端红；后端 job 在"回滚注入"的对照 run 转绿；本地同内容同命令唯一失败点即 27 行 `expected: 201` |
| 前端类型 | `frontend/src/main.ts:28` locale 传字符串 | 前端类型检查与构建 | `vue-tsc --noEmit` | CI 注解行号 `28:5`/`13:1` 与本地 `src/main.ts(28,5)`/`(13,1)` 完全一致；对照 run 中前端 job 仍红而后端绿 |

---

## 5. workflow 的本地静态校验

- YAML 解析：用本地已有的 snakeyaml 2.2（取自 `<USER_HOME>/.m2` 仓库，未安装新依赖）解析 `.github/workflows/ci.yml`
  → 解析成功；结构回读：顶层键 `name / on / permissions / jobs`，`jobs = [backend, frontend]`，
  `backend.runs-on = ubuntu-latest`（3 steps）、`frontend.runs-on = ubuntu-latest`（5 steps）。
- 锁文件一致性：对 `frontend/package.json` + `frontend/yarn.lock` 的副本执行 `yarn install --frozen-lockfile` → 退出码 0（32s），
  即 `--frozen-lockfile` 不会因锁文件与清单不一致而失败。
- 可解析性最终由真实运行背书：基线 run 与四次注入 run 共 5 次 run 均正常调度执行（无 YAML 解析错误）。

---

## 6. 本地复现（CI 同命令 / 同内容）

复现环境刻意对齐 CI，而非对齐本机默认：**无 `backend/config/application.yml`**、Redis 端口指向无监听端口
（`SPRING_DATA_REDIS_PORT` 覆盖为 `6399`，本机 `<MEMURAI_HOME>` 的 6379 不参与）、**不加载镜像 settings**（只走 Maven Central）、
JDK 21、Maven 3.9.9（`<MAVEN_HOME>/mvn.cmd`）。

### 6.1 基线（main 内容）

```
$ cd <REPO_ROOT>/backend && mvn -B test          # 无 config/、Redis 端口置空、无镜像 settings
[INFO] Tests run: 147, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:40 min

$ cd <REPO_ROOT>/frontend && yarn typecheck && yarn build
$ vue-tsc --noEmit          → Done in 11.13s（退出码 0）
$ node scripts/build.mjs    → ✓ built in 41.39s（退出码 0）
```

结论：与 CI 基线 run 的绿一致；也证明 §2 的 ubuntu 判据成立（无 Windows 依赖）。

### 6.2 后端注入（取 CI 提交 `ca3eeaa` 的**同一份文件内容**，同命令）

```
$ mvn -B test
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in com.example.configmgr.ai.tool.ToolResultLimiterTest
[ERROR] com.example.configmgr.ai.tool.ToolResultLimiterTest.defaultLimitsAre200RowsAnd8000Chars -- Time elapsed: 0.020 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
expected: 201
 but was: 200
        at ...ToolResultLimiterTest.defaultLimitsAre200RowsAnd8000Chars(ToolResultLimiterTest.java:27)
[ERROR] Tests run: 147, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

全量 147 例中**有且仅有 1 例失败**，且正是注入的那条断言（第 27 行）—— CI 后端 job 的 `exit code 1` 与之一一对应。

### 6.3 前端注入（取 CI 提交 `fdf3ce1` 的同一份 `main.ts`，同命令）

```
$ yarn typecheck
$ vue-tsc --noEmit
src/main.ts(13,1): error TS6133: 'zhCn' is declared but its value is never read.
src/main.ts(28,5): error TS2769: No overload matches this call.
  Argument of type '[{ locale: string; }]' is not assignable to parameter of type '[options?: Partial<ConfigProviderProps> | undefined]'.
      Types of property 'locale' are incompatible.
        Type 'string' is not assignable to type 'Language'.
error Command failed with exit code 2.
```

CI 注解的行列号 `28:5`、`13:1` 与上表逐条吻合，退出码 2 亦一致。

---

## 7. 一次性分支删除与 main 洁净性

```
$ git push origin --delete ci-defect-injection-s5d
 - [deleted]         ci-defect-injection-s5d
$ git branch -D ci-defect-injection-s5d
Deleted branch ci-defect-injection-s5d (was c74ca9b).
```

删除后清点：

- `git branch -a`：本地仅 `main`；远程仅 `origin/{HEAD→main, claude-opus-5.5, claude-opus-5.5+deepseek-v4-pro, deepseek-v4-pro, glm-5.3, kimi-k3, trae}`，
  **无 `ci-defect-injection-s5d` 残留**；除本任务创建的那一条外未删除/改动任何分支。
- `git log --oneline -5`（分支 main）：顶端为 `3cf5f9b`（门禁）与 `61c9d08`（S5c 证据），
  **两个缺陷注入提交只存在于已删除的侧分支，main 内不含任何注入代码**。
- `git status`：工作区干净；`backend/src/test/.../ToolResultLimiterTest.java` 与 `frontend/src/main.ts` 均为 `main` 原状（`200` / `zhCn`）。
- 全程未对 main 强推、未提交任何注入代码到 main、workflow 与文档中不含任何 API key。

---

## 8. 后续项与建议

1. **E2E 进门禁（任务书明确的后续项）**：`scripts/verify-e2e.ps1` 依赖 `<MEMURAI_HOME>`（Memurai）与真实 AI key，
   在 GitHub 托管 runner 上不能直接跑。可选路线（择一或组合，需裁决）：
   (a) `services: redis` 容器 + 桩上游替代真实 AI（可参照既有 `stub-upstream.mjs` 手法），跑在 ubuntu 上；
   (b) 自托管 runner（带 Memurai/Redis 与 key 的环境变量注入）；
   (c) 单独的 e2e job，只在 `main` 或定时（nightly）触发，避免拖慢每个 PR。
2. **action 版本与 runner 镜像**：基线注解提示 `setup-java@v4` 已弃用（建议 v5）、`checkout@v4`/`setup-node@v4` 有 Node 20 弃用告警、
   `ubuntu-latest` 自 **2026-10-19** 起迁移 Ubuntu 26。本棒**未擅自升级**（超出任务书范围），建议下一棒统一升 v5 并复跑基线。
3. **原始作业日志归档**：需要凭据（`gh` 或 PAT）。本棒以三源交叉取证替代（§4.1）；若归档原始日志是硬需求，请提供凭据或改由有 `gh` 的环境补跑。
4. **`pull_request` 触发路径本棒未经真实 PR 验证**：本机无 `gh`/PAT，无法开 PR。`on.pull_request` 分支只在配置层校验，
   建议下一次真实 PR 时顺带确认。
5. **注入方式的边界**：本棒按任务书注入的是"改单测断言"与"破坏前端类型"，二者证明**测试步骤真的在执行断言/类型检查**
   （非空跑），并证明门禁对已知缺陷有拦截力。若还要证明"生产代码回归能否被拦"，需另做一次 `src/main` 侧注入（本棒未做，属任务书外）。

## 9. 【待裁决】

1. 是否授权下一棒把 `actions/*` 升到 v5（清除 3 条弃用告警）并复跑基线？本棒保持任务书范围未动。
2. 原始 CI 日志是否必须归档？若必须，请指定凭据来源（`gh` 登录 / PAT 注入），否则沿用本棒的三源交叉证据。
3. E2E 进门禁按 §8.1 的哪条路线推进（服务容器+桩上游 / 自托管 runner / nightly 独立 job）？

---

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|------|------|
| 1 | actions/* 升 v5 清弃用告警 | 采纳，列入 M1 收尾棒-B 附带项 |
| 2 | 原始 CI 日志必须归档 | 不采纳——三源交叉证据已满足标准，不为此配 gh/PAT |
| 3 | E2E 进门禁路线 | 选 (a)：services:redis+桩上游，AI 用例 CI 内走桩、真实模型 E2E 保持本地手动；排期 M2，不阻塞 M1 出口 |
| 4 | pull_request 触发未验证 | 接受现状，记为已知未验证项，首个真实 PR 时顺带确认 |
