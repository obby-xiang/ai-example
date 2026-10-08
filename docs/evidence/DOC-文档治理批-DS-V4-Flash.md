# 文档治理批（DOC#1~#4）施工与对账 —— 施工方 DS-V4-Flash

> 批次：ai-example 合流项目文档治理批 DOC#1~#4　日期：2026-10-08
> 仓库/分支：`<REPO_ROOT>` / `main`，基线 HEAD = `9de199d`（docs: M2 排期 v1.2）
> 施工性质：**只执行不重新裁决**。审计已钉死的漂移清单逐项原文复核后执行；拿不准的一律列【待裁决】，不自行补方案。
> 纪律遵守：**零 git 写操作**（无 add/commit/push/stash/restore），全部改动保持未提交；全中文写作；全部脱敏（`<REPO_ROOT>`/`<MAVEN_HOME>` 占位、无盘符字面量、密钥一律 `***`）。
> 复核原则：每处事实改动前亲自读引用源（`pom.xml` / `package.json` / `application.yml` / `vite.config.ts` / 相关 Java 类 / `git ls-files` 实测），**不照抄审计提示词里的数字**；实测值与本提示词不一致时以实测为准并在第 4 节记录偏差。

---

## 1. 改动文件清单

| # | 文件 | 性质 | 变化量 |
| --- | --- | --- | --- |
| 1 | `README.md` | **全新重写**（非补丁） | 134 行 → 229 行 |
| 2 | `docs/README.md` | 新增（文档地图） | 58 行 |
| 3 | `scripts/README.md` | 新增（脚本用法） | 113 行 |
| 4 | `scripts/verify-e2e.ps1` | 就地修订（头部 + 摘要行） | 2194 行 → 2206 行（净 +12） |
| 5 | `scripts/githooks/README.md` | 就地修订（四处） | 109 行 → 109 行（等量替换） |
| 6 | `.github/workflows/ci.yml` | 就地修订（注释一处） | 222 行（等量替换） |
| 7 | `docs/M2-排期计划.md` | 就地修订（升 v1.3 + 新条目 + 编号顺延） | 51 行 → 56 行（净 +5） |
| 8 | `docs/evidence/DOC-文档治理批-DS-V4-Flash.md` | 新增（本对账文档） | 见文末统计 |

`git status --porcelain` 实测（施工结束时）：

```
 M .github/workflows/ci.yml
 M README.md
 M docs/M2-排期计划.md
 M scripts/githooks/README.md
 M scripts/verify-e2e.ps1
?? docs/README.md
?? docs/evidence/DOC-文档治理批-DS-V4-Flash.md
?? scripts/README.md
```

（另有一个**非本批产物** `docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md` 出现在工作区未跟踪列表里，本批未创建、未读取、未改动 —— 见第 4 节边界说明。）

---

## 2. 逐项漂移点处置对账表

### 2.1 根 `README.md`（审计编号 ①~⑧）

| 编号 | 审计认定的漂移 | 复核（施工前读原文） | 处置 | 复核证据（file:line） |
| --- | --- | --- | --- | --- |
| R-① | `:1`/`:60` 项目名仍为旧分支名 | 原文 `# AI 辅助快速实施系统 (ai-example-claude-opus-5.5)`、结构树根目录同名 | 项目名改为**「AI 辅助配置快速实施平台」**，与冻结基线标题一致 | 新 `README.md:1`；口径源 `docs/02-需求设计文档-v2.2-冻结版.md:1`、`docs/03-技术方案文档-v2.1.md:1` |
| R-② | `:76`/`:83` 含本机盘符绝对路径（Maven 安装目录） | 原文两处为盘符绝对路径，指向 IDE 内置 Maven 的 `...\maven3\bin\mvn.cmd`（按 DC-08 本文档不复述该字面量） | 全部改 `<MAVEN_HOME>` 尖括号占位（`scan-lib.sh` 匹配前剔除，不误报）；并补 Linux/macOS 的 `mvn` 写法 | 新 `README.md:55`、`:70`、`:76`、`:182` |
| R-③ | `:59-69` 项目结构只指 5 份 legacy 参考件，现行体系零指引 | 原文结构树 `docs/` 下只列 requirements/technical-design/implementation-plan/test-cases/verification-results | 结构树重写：含 `scripts/`、`spike/`、`docs/` 六类分区（adr/s4/evidence/research/spike/merge）与现行基线文件；legacy 五份明确标注"勿当现行设计" | 新 `README.md:12-46`；`docs/README.md:7-25`、`:42-53` |
| R-④ | `:25` Caffeine 内存 TTL 30min；`:26` 自管工具循环 + 虚拟线程；`:30` SseRunEmitter + AG-UI | 实况：会话记忆在 Redis 且 `app.ai.session-ttl: 6h`（滑动）；工具循环走官方 ChatClient（ADR-1）；`spring.threads.virtual.enabled: false`（DC-12）；发射器类名 `SseChatEmitter`，自有帧契约（DC-14 不切 AG-UI）；Java 侧 Caffeine 零引用 | 架构概览段的陈旧机制描述**整段删除**（不搬运 ADR 论证），改以「AI 交互契约」表按实况表述（Redis 滑动 TTL 6h、官方循环、平台线程 + 有界池、自有帧契约 seq + 差量补发） | 新 `README.md:161-171`；实况源 `backend/src/main/resources/application.yml:98`、`:12-17`、`:51-58`；`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`；`docs/adr/DECISION-CARDS.md:16`（ADR-1）、`docs/adr/DECISION-REGISTER.md:16`（DC-12）、`:18`（DC-14） |
| R-⑤ | `:51` Vite `^7`、`:52` Pinia `^3` | 实测 `vite: ^6.0.7`、`pinia: ^2.3.0` | 技术栈表按 `package.json` 实况改正，并在表中标注事实源 | 新 `README.md:141-142`；源 `frontend/package.json:32`、`:21` |
| R-⑥ | `:74-76` 前提缺 Redis；`:94`/`:133` 前端端口 5173；代理默认 8080 与后端 8081 错位无说明 | 实测 Redis 配置存在且 AI 端点依赖它；`DEFAULT_DEV_PORT = 5200`、`.env.example` 同；`DEFAULT_API_BASE = 'http://localhost:8080'` 而后端 `server.port: 8081` | ①前提条件表把 Redis 列为必装（并说明不可用时的降级语义）；②端口一览改为 5200（含覆盖方式）；③环境变量表对 `VITE_API_BASE` 加"默认值与后端 8081 错位，必须覆盖"的显式警示，并在快速启动步骤里写进 `.env.local` 编辑提示 | 新 `README.md:50-58`、`:83-93`、`:97-117`、`:120-126`；源 `frontend/vite.config.ts:9-10`、`frontend/.env.example:4,7`、`backend/src/main/resources/application.yml:2` |
| R-⑦ | 缺特性描述：生成式表单 + FormRenderer、确认门、前端工具挂起/回灌、作业取消/续跑 | 实测存在：`AiTools.generativeForm`（六型白名单 + 两道闸门）、`gate/ConfirmGate`、`@ToolChannel(FRONTEND)` 七个工具 + `POST /api/ai/frontend-tool-result`、`run/ResumeService` + 取消端点 | 新增「特性清单」两节：业务模块 7 项 + AI 交互契约 7 行（含生成式表单 / 确认门 / 挂起回灌 / 取消续跑 / 会话与帧契约） | 新 `README.md:149-171`；源 `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:41-47`、`:490-495`；`ai/gate/ConfirmGate.java:122,130`；`ai/run/ResumeService.java:212,223`；`ai/web/AiController.java:233,318,389` |
| R-⑧ | 缺测试与 CI 节 | 实测：CI 三 job（backend/frontend/e2e）、E2E 27 用例、桩上游；本地 `mvn -B test` 实测 249 通过、`yarn typecheck` 通过 | 新增「测试与 CI 门禁」：本地三条命令（含实测计数与"以 mvn test 输出为准"口径）、CI 三 job 表 + 触发条件、桩口径**不得证明模型服从率**的显式声明、pre-commit 启用一行 | 新 `README.md:175-219`；源 `.github/workflows/ci.yml:19-43,45-71,86-90`；`scripts/verify-e2e.ps1:2`；实测见第 3 节 |

### 2.2 `scripts/verify-e2e.ps1`

| 编号 | 审计认定的漂移 | 处置 | 复核证据（file:line） |
| --- | --- | --- | --- |
| V-1 | `:2` "22 用例" 过期 | 改为「全流程 27 用例」 | `scripts/verify-e2e.ps1:2`；用例构成源 `:1208-1213`（GF1–GF5 计入主计数、GF6 可选） |
| V-2 | `:15-17` 用法块缺参数与桩模式说明 | 用法块补 `[-ArtifactDir <GF 证据落盘目录>] [-EnableGf6]` 与两行参数语义，并新增「桩模式（CI 路线，M2-T1.2）」段：`E2E_STUB_MODE=1` 下 GFSKIP≥1 即整棒 FAIL（零容忍），真 key 本地跑法不设该变量、SKIP 语义不变 | `scripts/verify-e2e.ps1:15-25`；判定源 `:2203-2204`（stub 零容忍）、`:2205-2206`（硬底线） |
| V-3 | `:32` 结果口径写 "22 个用例（TC1–TC22）" | 改为「27 个用例计入 PASS/FAIL 与退出码」= TC1–TC22 共 22 + GF1–GF5 共 5；Q8 两项单列；并补三条退出码口径 | `scripts/verify-e2e.ps1:40-46` |
| V-4（顺带，审计未列） | `:2145`/`:2181`/`:2189` 三处把基数写死为 "22" | 摘要行改为打印实际计数、不再写死基数；另两处注释/打印改为"主用例计数"表述（Q8 项不计入主用例计数） | `scripts/verify-e2e.ps1:2157`、`:2193`、`:2201` |

> 语法复核：改后用 PowerShell 解析器 `Parser::ParseFile` 全量解析 → `PARSE OK`（无语法错误）。

### 2.3 `scripts/githooks/README.md`

| 编号 | 审计认定的漂移 | 处置 | 复核证据（file:line） |
| --- | --- | --- | --- |
| G-1 | `:33` "文档类按 DC-08 全量查"表述会误导 | 复核 `scan-lib.sh` 与 `pre-commit`：**规则本身不按文件类型区分**；差异在扫描范围。改写为"pre-commit 只扫暂存区新增/修改行；scan-history 按 blob 扫整份文件内容" | `scripts/githooks/README.md:33`；源 `scripts/githooks/pre-commit:40-44`（`git diff --cached -U0`）、`scan-history.sh:125-127`（整 blob） |
| G-2 | `:76` "1464 个 blob" 过期 | 实测 `git rev-list --objects --all` 可达对象 **4172** = blob **1987** / tree 1939 / commit 246，且 1987 个 blob 全在默认 2048KB 上限内 → 改写为"唯一 blob 约 2000 个"，并列出三项实测分解 | `scripts/githooks/README.md:76`；实测命令与输出见第 3 节 |
| G-3 | `:88` "钩子按 100644 保存 / 需 chmod +x / update-index --chmod=+x" 误导 | 实测 `git ls-files -s scripts/githooks/` → 三个脚本均为 **100755**，检出即可执行。删除该误导段，改写为"权限位已固化入库、无需补 chmod"（并说明本机 `core.filemode=false` 的真实影响面） | `scripts/githooks/README.md:88`；实测见第 3 节 |
| G-4 | `:93` "建议 CI 跑 scan-history 兜底"未闭环 | 改写：该建议**已升级为待实施项**，指向 `docs/M2-排期计划.md` T4「文档一致性门禁」子项；并明确在该项落地前 CI 侧无全历史扫描自动门禁 | `scripts/githooks/README.md:93`；`docs/M2-排期计划.md:46-48` |

### 2.4 `.github/workflows/ci.yml`

| 编号 | 审计认定的漂移 | 处置 | 复核证据（file:line） |
| --- | --- | --- | --- |
| C-1 | `:41` 注释"S5.c 收口时 147 → S5.e 后 152"过期（实测 `@Test` 269、`mvn test` 实测 249） | 删全部具体数字，改为「计数随测试演进，以 mvn test 输出为准」 | `.github/workflows/ci.yml:41`；实测见第 3 节（计数随棒次变化，故不写死） |

### 2.5 新增文档与排期更新

| 编号 | 交付物 | 内容要点 | 位置 |
| --- | --- | --- | --- |
| N-1 | `docs/README.md` | 文档地图一张表：现行基线（02 v2.2 冻结 / 03 v2.1）、存档基线（02 v2.1）、决策（ADR-1~12 + DC-01~15，**全部"生效"**）、排期（M2 v1.3）、出口评审、规格（s4/）、证据（evidence/ 按 8 个主题分组）、研究（research/）、PoC（docs/spike 决策卡 + 仓库 `spike/` 代码）、前期合流包（merge/）、**legacy 参考件区**（五份，逐份给出"现行替代"）；附阅读顺序三条 | `docs/README.md:1-58` |
| N-2 | `scripts/README.md` | `verify-e2e.ps1` 完整用法（前置条件 / 参数表 4 项 / 调用方式 / 27 用例构成表 / 退出码口径 / 桩模式零容忍 / 证据落盘）+ `stub-upstream.mjs` 用途边界（引用其文件头口径："产品链路的替身，不证明模型服从率"）+ 收敛契约四条 + 记录契约 + 白名单副本对账窗口 + githooks 一句话指向 | `scripts/README.md:1-113` |
| M-1 | `docs/M2-排期计划.md` | ①标题升 **v1.3**；②新增 v1.3 修订注记（含编号顺延说明）；③T4 追加条目 **18「文档一致性门禁（DOC#5）」**，沿用条目 16 的子项格式写两条子项（门禁脚本与 CI 接线 / 死配置与死依赖清理）；④原「业务方决策项」条目 18 **顺延为 19**（全表编号保持唯一递增） | `docs/M2-排期计划.md:1`（标题）、`:7`（v1.3 修订注记）、`:46-48`（T4 条目 18 及两条子项）、`:52`（原 18 顺延为 19） |

> 条目 18 子项内容严格按 DOC#5 裁决原文落位：`scripts/ci/check-docs.mjs` 进 CI（断言无盘符路径 / 版本与 `pom.xml`+`package.json` 一致 / README 引用文件存在 / 端口表与 `application.yml`+`vite.config.ts` 一致）；`scan-history.sh` 接进 CI 兜底 DC-08；清理死配置（`application.yml` 的 `app.server.*` 无绑定、`frontend-port` 死键）与死依赖（`pom.xml` Caffeine 零引用）；`vite` 的 `DEFAULT_API_BASE` 8080→8081 开箱对齐评估。
> 上述四项"死配置/死依赖"的实况均已在施工中复核：`app.server.*` 在 Java 侧零绑定（`grep -rn "app\.server\|frontend-port\|backend-port" backend/src --include=*.java` 无命中）→ 仅记录待清理，**本批不代改**；Caffeine 在 Java 侧零引用（`grep -rn -i caffeine backend/src --include=*.java` 无命中）→ 同上。

---

## 3. 施工期实测证据（事实源复核）

| 复核项 | 命令 | 实测结果 |
| --- | --- | --- |
| 后端全量单测 | `mvn -B test -s maven-settings.xml`（工作目录 `backend/`） | **`Tests run: 249, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `Total time: 42.139 s`** |
| `@Test` 注解静态计数 | `grep -rn "@Test" backend/src/test --include=*.java \| wc -l` | 269（含参数化/嵌套类内部用例，**不等于执行计数**；故 README 采用 `mvn test` 输出的 249，并注明"以 mvn test 输出为准"） |
| 前端类型检查 | `yarn typecheck`（工作目录 `frontend/`） | `vue-tsc --noEmit` → **退出码 0**（Done in 10.77s） |
| 技术栈版本 | 读 `backend/pom.xml`、`frontend/package.json` | Spring Boot 3.5.14（`pom.xml:10`）、`java.version=21`（`:21`）、Spring AI 1.1.8（`:22`）；Vue `^3.5.13`（`package.json:22`）、Vite `^6.0.7`（`:32`）、Pinia `^2.3.0`（`:21`）、Element Plus `^2.9.3`（`:19`）、SpreadJS `17.1.5`（`:15`） |
| 端口与 TTL | 读 `application.yml`、`vite.config.ts`、`.env.example` | 后端 8081（`application.yml:2`）、`session-ttl: 6h`（`:98`）；前端 dev 默认 5200（`vite.config.ts:10`）、代理默认 8080（`:9`）；`.env.example:4,7` 两键一致 |
| 钩子权限位 | `git ls-files -s scripts/githooks/` | `.gitattributes`/`README.md` = 100644；`pre-commit`/`scan-history.sh`/`scan-lib.sh` = **100755** |
| 历史对象计数 | `git rev-list --objects --all \| awk '{print $1}' \| git cat-file --batch-check='%(objecttype)'` | blob **1987** / tree 1939 / commit 246（合计 4172） |
| 脚本语法 | PowerShell `[Parser]::ParseFile` | `PARSE OK` |
| Maven 版本（README 前提条件"3.9+"的依据） | `mvn -v` | Apache Maven **3.9.16**，运行时 Java 21.0.12 |

### 脱敏扫描结果（`scripts/githooks/scan-lib.sh`）

对**本批全部 8 个改动/新增文件**（含本证据文档自身）做整文件扫描（blob 模式，比 pre-commit 的"新增行"范围更严）：

```sh
. scripts/githooks/scan-lib.sh && scan_init
for f in .github/workflows/ci.yml README.md docs/README.md docs/M2-排期计划.md \
         docs/evidence/DOC-文档治理批-DS-V4-Flash.md scripts/README.md \
         scripts/githooks/README.md scripts/verify-e2e.ps1; do
  scan_engine_blob "$f" < "$f"
done | sort -u
```

**最终结果：命中 0 处（ZERO HIT）** —— SECRET / PATH / USER 三条规则全零命中；无盘符字面量、无个人用户名片段、无密钥。`<MAVEN_HOME>` 等尖括号占位按 `scan-lib.sh` 的豁免规则整段剔除，未产生误报。

> 自指命中已就地修正（留痕）：首轮扫描曾在本证据文档自身命中 1 处 PATH —— 对账表 R-② 行为说明"删掉了什么"，把原盘符路径字面量照抄了进来（`docs/evidence/DOC-文档治理批-DS-V4-Flash.md:46`）。已改写为"盘符绝对路径，指向 IDE 内置 Maven 的 `...\maven3\bin\mvn.cmd`（按 DC-08 不复述字面量）"，复扫归零。这也印证 `scan-history.sh` 的存在意义：**删除工作树里的违规内容不改变它在历史中的存在**。

（另：本次也复核了提交口径 —— 改动全部未暂存，不进入索引，故 pre-commit 不会被触发；也就是说本批的零命中是"人工主动扫描"的结论，不是钩子的自动结论。）

---

## 4. 偏差与边界说明

1. **G-2 的数字取法偏差（如实记录）**：审计提示词给的"实测 4172"是 `git rev-list --objects --all` 的**可达对象总数**（含 tree/commit），而该句原文语境是"串行逐个 **blob** 读取"。故本批按 blob 语义写为"唯一 blob 约 2000 个（1987）"，并把 4172 的总量分解一并写入，两个数字都可被同一命令复核。若指挥官要求严格对齐提示词数字，此处需回改。
2. **V-4 属超出审计清单的顺带修正**：审计只点了 `:2181` 一处硬编码基数，实测同文件另有 `:2145`、`:2189` 两处同源问题（Q8 注释与打印行），一并按"不写死基数"处理。判定口径未做任何改动，只改文案与计数呈现。
3. **R-④ 的处置方式是"删除而非改写"**：旧架构概览段（浏览器/后端分层图）中大量机制名（Caffeine、自管循环、虚拟线程、SseRunEmitter、AG-UI）已全部失效，按"只保留如何跑/如何验/去哪读，不搬运 ADR 论证"的口径不再重画该图，改以「AI 交互契约」表承载实况。若需要保留架构图，需另行绘制（属新工作，不在本批）。
4. **边界（非本批产物）**：工作区存在未跟踪文件 `docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md`（施工期间出现，疑为并行批次产物）。本批未创建、未读取、未改动、未纳入扫描与提交范围。
5. **未做的事（明确不在本批范围）**：不改 `pom.xml` Caffeine 依赖、不改 `application.yml` 死键、不改 `vite.config.ts` 的 `DEFAULT_API_BASE` —— 这四项是 DOC#5 的**待实施**内容，本批只负责把它们登记进 T4 条目 18。
6. **legacy 五份文件本身未改**：它们已各自带"源分支遗留参考件"警示头；本批只在 `docs/README.md` 的 legacy 区对其定位与替代关系做标注。

---

## 5. 待裁决

| # | 事项 | 现状 | 建议（不代决） |
| --- | --- | --- | --- |
| 待裁决-1 | G-2 的 blob 计数口径 | 审计提示词"4172"= 可达对象总数；本批按 blob 语义写 1987 并附 4172 分解 | 若要求与提示词字面一致，回改为 4172 或保留当前双数字写法 |
| 待裁决-2 | R-④ 是否保留架构概览图 | 本批删除失效分层图，改为契约表 | 若需保留"浏览器/后端/模型"视觉分层，应另起一笔重画（按实况：Redis、官方循环、自有帧契约） |
| 待裁决-3 | `docs/M2-排期计划.md` 条目编号顺延 | 「业务方决策项」由 18 → 19，以保证全表编号唯一递增；已在 v1.3 修订注记留痕 | 若无顺延必要，可改为新条目占位编号（如 18a）后回改 |
| 待裁决-4 | T4 条目 18 的批次归属 | DOC#5 门禁脚本属"门禁强化"，按排期原则本应优先于清理类小修；本批按指令只登记在 T4 | 是否将该子项前移至更早批次，由指挥官定 |
| 待裁决-5 | 死配置/死依赖清理的落地时点 | `app.server.*`/`frontend-port`/Caffeine 均已确认为零引用，本批未动 | 是否在 DOC#5 一并清理，或拆独立小修搭车 |

---

## 裁决结论（指挥官 K3）

裁决日期 2026-10-08，指挥官 K3。文档治理批 8 文件已入库（301594e/2ecabbd/cdeca0e/1515e20），逐条裁决 5 项【待裁决】：

1. DOC-P1（G-2 blob 计数口径）：保留双数字写法（blob 1987 + 可达对象总数 4172 分解），不回改。理由：两个数字语义不同，并列呈现最不易误读。
2. DOC-P2（R-④ 架构概览图）：驳回保留诉求，删除生效。旧图引用已失效机制（Caffeine/自管工具循环/虚拟线程/SseRunEmitter/AG-UI），保留即漂移源；如需架构概览图另立任务按现行契约重画。
3. DOC-P3（排期条目编号顺延 18→19）：采纳顺延，保持整数连续编号，不回改。
4. DOC-P4（T4 条目 18 批次归属）：驳回前移。DOC#5 维持登记在 T4，文档一致性门禁不阻塞 T2/T3 主线。
5. DOC-P5（死配置/死依赖清理时点）：并入 T4 条目 18（DOC#5）一并清理，本批不动、不拆独立小修。

另留痕：本文档第 2 节自述"改动全部未暂存、保持未提交"为施工时点状态；实际已于 2026-10-08 由四个提交（301594e~1515e20）全部入库并推送 origin/main，工作区干净。
