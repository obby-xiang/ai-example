> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-112` 的 wire 末条 assistant 文本（消息序号 m=41；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-08 17:36 → 2026-10-08 17:38（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`9de199d`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：16943 B / 10818 字符 / 134 行；行指纹覆盖率（本批复算）3.5%（最高单文件 3%：`docs/evidence/补档-B23L1批①设施-复核确认-DS-Flash.md`）
> - 类别：**施工复核**
> - 与既有件关系：补档-B23L1；报告口径覆盖率 0.03
> - 脱敏：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 7
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
审计完成。全仓只读扫描（无 git 写操作、未改任何文件）。以下按"逐文件 → 汇总 → 治理建议"回报。

---

# 一、审计范围（README/说明类文件清单）【实测】

| 类别 | 文件 | 状态 |
|---|---|---|
| 根入口 | `README.md`（134 行） | 存在，**脱节最严重** |
| 脚本说明 | `scripts/githooks/README.md`（109 行） | 存在，基本准确，3 处细节过期 |
| CI 配置 | `.github/workflows/ci.yml`（222 行，含大量导读注释） | 存在，1 处计数注释过期 |
| **缺失** | `backend/README.md`、`frontend/README.md`、`scripts/README.md`、`docs/README.md`（或任何索引节）、`AGENTS.md`、`LICENSE`、`CONTRIBUTING.md` | **全部不存在**（`git ls-files '*.md'` 中 `docs/` 之外只有 `README.md` 与 `scripts/githooks/README.md`；`docs/` 下 87 份 md **无一份是索引**）|

对照实况的一手依据：`backend/src/main/resources/application.yml`、`backend/pom.xml`、`frontend/package.json`、`frontend/vite.config.ts`、`.github/workflows/ci.yml`、`scripts/verify-e2e.ps1`、`scripts/ci/stub-upstream.mjs`、`scripts/ci/stub-routes.json`、`backend/src/main/java/.../ai/**`。

---

# 二、逐文件漂移点

## 1. `README.md` —— 阻断级

**R-1 项目名仍是旧分支名** 【实测】
- `README.md:1`：`# AI 辅助快速实施系统 (ai-example-claude-opus-5.5)`
- `README.md:60`：结构块首行 `ai-example-claude-opus-5.5/`
- 应改为当前口径：仓库/分支 `main`，产品名与冻结文档一致（`docs/03-技术方案文档-v2.1.md:1` 与 `docs/02-需求设计文档-v2.2-冻结版.md:1` 均为「AI 辅助配置快速实施平台（生产落地版）」）。**这是全仓唯一残留旧目录名的地方**（其余 `claude-opus-5.5` 命中全在 `docs/evidence/`、`docs/merge/` 的分支事实描述与 legacy 参考件里，属正常）。

**R-2 违反 DC-08 的本机绝对路径，且会被仓库自己的钩子拦下** 【实测】
- `README.md:76`：``- Maven（路径 `<DRIVE>\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\`）``
- `README.md:83`：`& "<DRIVE>\Program Files\...\maven3\bin\mvn.cmd" clean package -DskipTests -s maven-settings.xml`
- 实测证据：用 `scan-lib.sh:91` 的 PATH 规则正则跑 `README.md`，两行均命中；本机 `core.hooksPath=scripts/githooks` 已启用，钩子对 `.md` 是 DC-08 口径。**方向**：换成 `<MAVEN_HOME>` / `mvn`（`<...>` 占位会被 `scan-lib.sh:187` 剔除，不误报），与 `docs/M1-出口评审材料.md:5` 的写法对齐。

**R-3 项目结构把所有 path 指错，且把 5 份 legacy 参考件当成现行设计文档** 【实测】
- `README.md:59-69`：只列 `backend/ frontend/ docs/{requirements,technical-design,implementation-plan,test-cases,verification-results}`
- 实况：`scripts/`（`verify-e2e.ps1` + `ci/` + `githooks/`）、`spike/` 完全未列；`docs/` 下 `adr/ merge/ evidence/ research/ s4/ spike/ 02-*-v2.2-冻结版.md 03-技术方案文档-v2.1.md M1-出口评审材料.md M2-排期计划.md` 全部未列。
- 更严重的是 `README.md:64-68` 指向的 5 份文件恰恰是 **legacy 参考件**（`docs/requirements.md:1` 自述"combined 基座分支遗留参考件…描述对象为源分支 v1.0 实现"，`docs/implementation-plan.md:5` 写着"项目：ai-example-claude-opus-5.5"）。新人按 README 走会**恰好被引到废弃文档**，而现行基线（`docs/02-需求设计文档-v2.2-冻结版.md`、`docs/03-技术方案文档-v2.1.md`、`docs/adr/`）零指引。

**R-4 架构图的 3 处机制描述已与裁决相反** 【实测】

| 位置 | 现状摘录 | 实况 |
|---|---|---|
| `README.md:25` | `AiSession (Caffeine 内存，TTL 30min)` | 会话状态在 **Redis**（`ai/session/AiSessionService.java:29` 用 `StringRedisTemplate`，`application.yml:98` `session-ttl: 6h` 滑动）；`pom.xml:70-75` 的 Caffeine 依赖**在 `backend/src` 中零引用**，是死依赖 |
| `README.md:26` | `AgentRuntime (自管工具循环，虚拟线程)` | 循环是 **Spring AI 官方 ChatClient 循环**（ADR-1，`ai/config/AiModelConfig.java:33-38`）；**虚拟线程已被 DC-12 禁用**（`application.yml:12-17`，`ai/exec/AiExecutorConfig` 专用有界平台线程池） |
| `README.md:30` | `SseRunEmitter (AG-UI 事件协议)` | 类名是 **`SseChatEmitter`**（`ai/run/SseChatEmitter.java`）；协议是自有帧契约，**AG-UI 已被 DC-14 N1 明确裁决不切换**（`FrameContract.java:22` 原话："借的是 ag-ui 的方法论，不是它的协议（DC-14 已裁决不切换 AG-UI）"） |

**R-5 技术栈表 2 项版本对不上** 【实测】
- `README.md:51`：`Vite | ^7` → 实况 `frontend/package.json:32` `"vite": "^6.0.7"`
- `README.md:52`：`Pinia | ^3` → 实况 `frontend/package.json:21` `"pinia": "^2.3.0"`
- 其余（Spring Boot 3.5.14 / JDK 21 / Spring AI 1.1.8 / Element Plus ^2 / SpreadJS 17.1.5 / H2）与 `pom.xml:10,21,22`、`package.json:14-16,19` **一致**。

## 2. `README.md` —— 重要级

**R-6 端口/联调口径三处不一致，照 README 走会落空** 【实测】
- `README.md:94`「访问 http://localhost:5173」、`README.md:133` 端口表「前端开发服务器 | 5173」
- 实况：`frontend/vite.config.ts:10` `DEFAULT_DEV_PORT = 5200`（`frontend/.env.example:7` 亦为 5200）。5173 只出现在 `application.yml:144` 的 `app.server.frontend-port`，而该键**无人绑定**（`AppProperties.java:18-33` 只含 `job`/`file`；全仓 `frontend-port` 仅此一行）——**死配置**。
- 连带：`README.md:74-76` 前提条件是"JDK 21 / Node 18+ / Maven"，**未提 (a) Redis 是 AI 功能硬依赖**（`application.yml:48-58`，E2E 以 `services: redis:7` 容器保证）、**(b) 前端代理默认指向 8080 而后端在 8081**（`vite.config.ts:9` `DEFAULT_API_BASE='http://localhost:8080'` vs `application.yml:2` `port: 8081`）。开箱 `yarn dev` + `yarn` 默认配置**连不上后端**，必须靠 `frontend/.env.local` 的 `VITE_API_BASE` 覆盖——README 对此零字说明。

**R-7 特性清单缺合流后的实况（覆盖面缺口）** 【实测】
README 无"功能特性"节，架构图里的业务模块（配置定义/主数据/配置数据/任务条目/作业进度/Excel 处理）与代码包（`definition/masterdata/data/task/job/excel/file/seed`）**是对的**；但以下已落地能力全无描述：`generative_form` 生成式表单与前端 `FormRenderer.vue`（DC-15，`ai/form/GenerativeFormRules.java`、`frontend/src/components/AiPanel/FormRenderer.vue`）、确认门危险工具放行/拒绝（`ai/gate/ConfirmGate.java`）、前端工具通道挂起与回灌（`ai/tool/FrontendToolGuard.java`、`ai/web/FrontendToolResultRequest.java`）、作业取消/终态补发/启动续跑（`ai/run/ResumeService.java`）、发布与乐观锁、无主体审计流水。同时**未见已删除/改名功能的残留描述**（README 未提 Auth/RBAC，与 DC-10 一致，这点是干净的）。

**R-8 测试与 CI 说明整体缺失** 【实测】
- README 全文命中 `ci`/`门禁`/`badge`/`license` **零次**。
- 现状：`.github/workflows/ci.yml` 有 3 个 job（`backend` 单测 / `frontend` typecheck+build / **`e2e` 27 用例 · 桩上游 + redis**，`ci.yml:86-90`）；`scripts/verify-e2e.ps1` 可本地复跑；`scripts/ci/stub-upstream.mjs` + `stub-routes.json` 是 M2-T1.2 新增的**新目录**。
- 全仓检索：`stub-upstream|stub-routes` 只出现在 `docs/M2-排期计划.md` 与 `docs/evidence/*`，`E2E_STUB_MODE` 只在 4 份 evidence 里——**没有任何读者向文档提到桩上游**，与预期判断一致。

**R-9 无 CI 徽章、无许可证、无贡献指引** 【实测】`README.md` 无任何 badge；全仓无 `LICENSE`/`CONTRIBUTING`。相对链接极少（仅 `docs/technical-design.md` 一处指向 `implementation-plan.md`，目标存在）→ **无失效相对链接**。

## 3. `scripts/verify-e2e.ps1` 头部用法说明 —— 重要级

**V-1 用例口径过期** 【实测】
- `scripts/verify-e2e.ps1:2`「全流程 22 用例」、同文件 `:32`「结果口径：22 个用例（TC1–TC22）计入 PASS/FAIL 与退出码」
- 实况：脚本 `:1197` 明确 GF1–GF5 计入主 PASS/FAIL；`ci.yml:87` job 名「27 用例」；`docs/M1-出口评审材料.md:31` 记 `PASS=27 FAIL=0 GFSKIP=0`（22 既有 + GF1–GF5）。应改为 27（22 既有 + GF1–GF5），并说明 Q8 项 2/2 单列计数。

**V-2 用法块漏掉一半参数与整个桩模式** 【实测】
- `:15-17` 只给了 `-Base` / `-BackendLog`；实际 `param` 还有 `-EnableGf6`（`:39`）、`-ArtifactDir`（`:42`），而 CI 正是靠 `-ArtifactDir` 归档证据（`ci.yml:196-199`）。
- 头部**未提 `E2E_STUB_MODE=1`**（桩模式零容忍语义在 `:2183`、`:2191`，CI 在 `ci.yml:92` 设置）。读者无法从脚本自述得知"CI 跑的是桩模式、GFSKIP≥1 即整棒红"。

**V-3 摘要文案硬编码 22** 【实测】`:2181` 打印 `（22 既有 + GF 新增）`，与 `:1197`/`ci.yml` 的 27 对不上；建议只打印计数、不写死基数。

## 4. `scripts/githooks/README.md` —— 建议级

- `:76`「本仓库 1464 个 blob 需数分钟」→ 实测 `git rev-list --objects --all | wc -l` = **4172**，性能提示已过期约 3 倍。
- `:88`「仓库里钩子按 `100644` 保存。…若要固化进版本库，用 `git update-index --chmod=+x …`」→ 实测 `git ls-files -s scripts/githooks/` 三个脚本均为 **100755**，该动作**已完成**，读者按文操作是空转。
- `:33`「文档类文件（`*.md`/`*.log`/`*.txt`）按 DC-08 全量查本机路径」→ 与实现有偏差：`pre-commit:40-44` 对所有文件都只扫 `git diff --cached -U0` 的 `+` 行，"全量"实际只体现在 `scan-history.sh:113`（把这些扩展名固定当文本、不做二进制探测）。建议把"全量"限定到 `scan-history`。
- `:93`「CI 侧建议再跑一次 `scan-history.sh` 兜底」→ 实测 `ci.yml` 只有 backend/frontend/e2e 三 job，**无脱敏扫描步骤**；该"建议"长期未落地（正是 README R-2 能存活至今的原因）。

## 5. `.github/workflows/ci.yml` 注释 —— 建议级

- `:41`「用例数随棒次增长（S5.c 收口时 147 → S5.e 后 152）」→ 实测 `backend/src/test` 内 `@Test` 计 **269**（`docs/M1-出口评审材料.md:30` 亦记 246）。注释里两个数字都远落后于实况，建议删数字或换成"计数据 `mvn test` 输出"。
- `:3-8`、`:126-130`、`:179`、`:203-205` 的注释与实现**逐条对得上**（含 M2-T1.2 口径补注、E2E_STUB_MODE 构造保证、actions v5 版本核对），无漂移。

---

# 三、全仓"表述统一"核查结论（任务 2a）【实测】

| 检索项 | 结果 |
|---|---|
| `<REPO>` / `<REPO>` | **未清零**：`spike/sp01ab/scripts/sp01ab-sanitize.mjs:12-13`（2 处）、`spike/sp01d/scripts/make-attachments.mjs:1,13,14,135,152,170`（6 处）、`spike/sp02/scripts/sp02-sanitize.mjs:13-14`（2 处）。性质：这些是**脱敏脚本的输出占位符**（把 `…/<REPO>` 替换成 `<REPO>`），不是文档正文；但它们是"应清零"清单里唯一的残点，且 `make-attachments.mjs` 会**继续生成** `<REPO>` 到 `docs/spike/` 附件里。当前 `docs/` 下 0 命中。 |
| `claude-opus-5.5`（旧目录名假设） | **未清零**：`README.md:1`、`README.md:60` 是唯二的"当作本项目名"残留（阻断）；其余 40+ 处均在 `docs/evidence`/`docs/merge`/legacy 参考件中作为**被核验分支的历史事实**，按纪律不应改。 |
| `<REPO_ROOT>` 占位统一 | 已生效：`docs/` 与 `scripts/verify-e2e.ps1:4` 一致使用；`scan-lib.sh:187` 会剔除尖括号占位，不误报。 |
| 分支名 | `git branch -vv` 仅 `main` 跟踪 `origin/main`，HEAD=`9de199d`；`ci.yml:11-13` 触发分支 `[main]`。**一致**。 |

---

# 四、汇总（分级）

| 级别 | 条目 |
|---|---|
| **阻断（3）** | R-1 旧分支名当项目名（`README.md:1,60`）；R-2 DC-08 绝对路径 2 处（`README.md:76,83`，实测被自身钩子命中）；R-3 结构块指向 legacy 文档且漏 `docs/` 全部现行体系（`README.md:59-69`） |
| **重要（6）** | R-4 架构图 3 处机制描述与 ADR/DC 相反（`README.md:25,26,30`）；R-5 技术栈 2 项版本错（`:51,52`）；R-6 端口/联调口径不一致 + 缺 Redis 前提（`:74-76,94,133`）；R-7 缺 generative_form/确认门等合流后特性；R-8 缺测试与 CI/桩上游说明；V-1/V-2 verify-e2e 用例口径与用法块（`:2,32,15-17`） |
| **建议** | R-9 无徽章/许可证/贡献指引；V-3 摘要硬编码；githooks README 4 处（`:33,76,88,93`）；`ci.yml:41` 测试计数注释；`spike/**/*.mjs` 的 `<REPO>` 占位符（8 处） |

---

# 五、文档治理建议

**本次更新应做（与 M2-T1.2 收口同批，一次提交内分区）**

1. **README 重写而非打补丁**。现有 134 行里合法内容是"技术栈表 + 快速启动 + AI 配置 + 端口 + 钩子"，其余全需替换。建议按：产品定位 → 目录树（含 `scripts/`、`docs/` 六类）→ 快速启动（JDK 21 / Node 22 / Redis / Maven 占位符）→ 环境变量表（`AI_API_KEY`/`AI_BASE_URL`/`AI_MODEL`/`VITE_API_BASE`/`VITE_DEV_PORT`）→ 特性与 AI 交互契约（含生成式表单、确认门）→ 测试与 CI 门禁（`mvn test` / `yarn typecheck|build` / `verify-e2e.ps1` 真 key 手动跑法 / 桩模式说明）→ 文档地图。
2. **必须先消除 DC-08 违规**（R-2），否则任何触及该两行的提交都会被自己的 pre-commit 拦下；这也是唯一"文档合规性"性质的阻断项。
3. **新增 `docs/README.md` 作为文档地图**（清单里有 87 份 md + 6 个子目录，目前零入口）。建议一张"读哪份"表：现行基线（`docs/02-…-v2.2-冻结版.md`、`docs/03-技术方案文档-v2.1.md`）/ 决策（`docs/adr/DECISION-REGISTER.md`、`DECISION-CARDS.md`）/ 排期（`docs/M2-排期计划.md`）/ 规格（`docs/s4/`）/ 证据（`docs/evidence/`，按 M1/M2-T1.2/S4x/V/W/GFx 分组）/ 研究（`docs/research/`）/ PoC（`docs/spike/`）/ **legacy 参考件区**（`docs/requirements.md`、`technical-design.md`、`implementation-plan.md`、`test-cases.md`、`verification-results.md`——已标注但需要索引点名，避免再被误引；这与 `docs/adr/S3-Challenger重审报告.md:117` 的 N6 是同一条诉求，可顺势闭合）。
4. **补 `scripts/README.md`**（或至少 `scripts/ci/README.md`）：`verify-e2e.ps1` 完整用法（3 个参数 + 桩模式 + 证据落盘）与 `stub-upstream.mjs` 的用途边界（"产品链路替身，不证明模型服从率"这句口径已在脚本头写好，直接引用即可）。同时修 `verify-e2e.ps1:2,32,15-17` 自身头部。
5. **README 面向读者**，只保留"如何跑/如何验/去哪读"，不搬运 ADR 论证——避免与 `docs/adr/` 形成第二事实源（R-4 就是这么产生的）。

**建议建的长期机制**

1. **文档一致性只在有门禁时才不漂移**。当前 CI 三 job 全是代码门禁，无任何文档检查。低成本方案（任选其一，上 CI 即长效）：`README` 关键事实做**可执行断言**——例如一个 `node scripts/ci/check-docs.mjs`，断言 (a) 根 README 无盘符路径（复用 `scan-lib.sh` 的 PATH 正则）、(b) 技术栈表里的版本号与 `pom.xml`/`package.json` 实值一致、(c) README 引用的 `docs/**` 文件全部存在、(d) 端口表中端口与 `application.yml`/`vite.config.ts` 一致。R-5/R-6 这类"复制粘贴型漂移"即可自动归零。
2. **把 `scan-history.sh` 接进 CI**（钩子 README `:93` 已建议、至今未落地）。既兜住 DC-08 回填，也顺带把 `<REPO>` 这类占位符残留暴露在 PR 上。
3. **清死配置/死依赖**（漂移的温床）：`application.yml:142-144` 的 `app.server.*` 无绑定、`pom.xml:70-75` 的 Caffeine 无引用。删掉后 R-4/R-6 的两处"看似有依据"的错误描述就失去生存土壤。
4. **`docs/evidence/` 与 legacy 文档加机器可读首行标记**（如 `> status: legacy-reference`），供索引生成与 CI 排除，避免 `docs/` 根目录再混入无标注的过期件。
5. **每次里程碑出口检查表加一行"面向读者的入口文档是否同步"**（M1/M2 出口材料目前只核 docs/ 与 evidence，README 从不在检查范围内——这正是本轮脱节的根因）。

---

**未覆盖/未能验证**：本次为纯静态审计，未启动后端、未运行 `mvn test`、未跑 `verify-e2e.ps1`、未实际执行 `pre-commit`/`scan-history.sh`（后者耗时数分钟）。凡涉及"实况"的结论均由配置与源码静态读取得出（标【实测】者为 grep/`git ls-files`/正则比对所得）；R-4 中"官方循环 vs 自管循环"与"协议非 AG-UI"两条依据是 ADR-1/DC-14 与 `FrameContract.java` 的显式声明，属【实测】读取、非运行观测。
