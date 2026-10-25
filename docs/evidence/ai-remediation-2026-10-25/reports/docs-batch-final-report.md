> 来源=<仓外证据>/docs-batch-final-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 文档批最终报告（更正版）

> 生成时间：2026-10-25（更正轮）· 执行者：文档批子任务
> 性质：**只准备、不提交、不推送**；零产品代码 / 测试 / 脚本改动；未构建、未跑测试、未起停服务；未派子 agent。
> 基准：主仓当前 HEAD **`cb0761d`**（「fix: 修正零条目作业终态与历史投影」）。隔离副本 `<验证副本>/_verify-20261025-docs-batch` 已 fetch 并 hard-reset 到 `origin/main` = `cb0761d`，因此下面的补丁**是相对 `cb0761d` 的累计补丁**。
> **上一轮的 `docs-batch-report.md` 与 `docs-batch.patch` 已作废**（含"总纲原样/逐字节一致""Task 1–9 零施工""用 `AI_SECRETS_ALLOW=1` 放行"等说法）。以本报告与 `docs-batch-final.patch` 为准。

---

## 1. 本轮更正了什么（逐条）

| 上一轮表述 | 更正后（本批文件里的实际文字） |
|---|---|
| 总纲「**原样入库、一字未改**」「与主仓件 `cmp` 逐字节一致（sha256 `dddd0978…`，15,017 B）」 | 总纲**只做两处路径合规改写**：`Spec` → `<仓外证据>/ai-full-audit/FINAL-AUDIT.md`；Maven 可执行 → IDEA 内置 `<本机路径>/…/maven3/bin/mvn.cmd`；**其余内容未改**。不再写"原样 / 逐字节 / 旧 sha / 旧体积" |
| 「Task 1–9 **零施工**（未建分支、未写测试、未改码）」 | **Task 6 已提交 `cb0761d`**（13 文件，含 `ZeroItemJobTerminalTest` / `JobRollbackTerminalFallbackTest` / `ListTasksHistoryProjectionTest` 等新测试）+ **主树 50/50 定向测试通过**，**干净提交验证另行进行中（不写成已验证）**；Task 1–5 / 7–9 **在实现或评审中、未完成、未验证**；本批**不并入**其代码/测试证据、不改任何任务状态 |
| 「提交须 `AI_SECRETS_ALLOW=1` 放行 / 或经批准改写」 | **删除该指令**；改为「路径合规收口，不绕过钩子」。并如实写明**实测仍有 1 处 PATH 命中**（见 §4） |
| 隔离副本基点 `34fd8f4` | 基点 **`cb0761d`**（补丁相对 main 当前 HEAD） |
| 挂账只提 `L-144 / L-145` | 全面统一为 **`L-144 / L-145 / L-146`**（含 research README 边界条） |

**保留未动**：223 条 canonical（22 条 + 1 子断言已否证）、两条 run 事实（`40182795560` 165/1、`40182796512` 15/1）、L-144/145/146 三条挂账、`@real` 未触发、两行 **📤 完成待提交**（文档面确实尚未提交/推送）、历史行文与 L-143 措辞、审计件正文与外部证据边界。

---

## 2. 产物（精确路径）

### 2.1 四件非总纲文件（补丁承载）

| 路径（仓内） | 变化 |
|---|---|
| `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md` | **新增** 1,064 行（原文 1,062 + 入库注记 2）；正文原样，仅 4 处盘符路径 → `<仓外证据>/…` |
| `docs/research/README.md` | 修改：索引第 68 件、收录 67 → 68、边界条 1 条（挂账指针改 `L-144 / L-145 / L-146`）、索引日期 10-25 |
| `docs/任务看板.md` | 修改：`## 10-25` 区（区注记 + 2 行 📤）、挂账 **L-144/145/146**、当前计数、补记 1 条（③ 总纲形态与任务状态已更正、⑧ 改为路径合规收口） |
| `docs/决策看板.md` | 修改：`## 10-25` 组 2 行 ✅、当前计数、补记 1 条（Task 6 已提交、其余在途） |

- 补丁：**`<仓外证据>/docs-batch-final.patch`**（1,204 行；`4 files changed, 1096 insertions(+), 7 deletions(-)`）。
- 四件在隔离副本中的实际文件：`<验证副本>/_verify-20261025-docs-batch/{docs/research/README.md, docs/research/排查-…-2026-10-25.md, docs/任务看板.md, docs/决策看板.md}`。

### 2.2 总纲（不放进补丁，由提交官单独 stage）

- 仓内路径：`docs/superpowers/plans/2026-10-25-ai-remediation.md`（228 行，未跟踪）。
- 来源：**主仓工作树已脱敏件**（`Spec` = `<仓外证据>/ai-full-audit/FINAL-AUDIT.md`；Maven = `<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`）。本报告**不重复其哈希/体积**，也**不称其"未改"**——它相对初版确实被改了两处路径。

### 2.3 计数（未变，仍自洽）

任务看板：批次/笔行 **154**（✅144 + 📤2 + 📋6 + 🔧1 + ❌1）、挂账 **212**（展开 187 + 折叠 25，真 open 174）；决策看板：**186**（✅147 + 🏛️25 + 📌7 + ⏳1 + ⏸️3 + ❌2 + 🔧1）；research 索引 **68**。实测复核：`| 批·/笔·` 行 = 154；10-25 组决策行 = 2；L-144/145/146 各 1 行、无重复。

---

## 3. 控制器操作顺序（建议）

```text
# 0) 先确认 main 工作树的三份文档仍是"旧版"（等于上一轮批次的未更正内容）
#    若是，先还原到 cb0761d，否则累计补丁会冲突：
git checkout -- docs/research/README.md docs/任务看板.md docs/决策看板.md

# 1) 应用四件累计补丁（相对 cb0761d）
git apply --whitespace=nowarn <仓外证据>/docs-batch-final.patch

# 2) 单独 stage 主仓已脱敏的总纲（未跟踪）
git add docs/superpowers/plans/2026-10-25-ai-remediation.md

# 3) 自检（预期：check-docs 6/6；pre-commit 仍有 1 处 PATH，见 §4）
node scripts/ci/check-docs.mjs
git diff --cached --name-only
```

等价的更简单路径：直接把隔离副本的 4 个文件拷进主仓同名路径，再 `git add` 总纲。

提交信息建议（docs-only，单笔，措辞不含"已验证"）：
`docs: 入库 AI 全链路只读验收审计与修复总纲`

---

## 4. 验证结果（实际命令 / 实际输出）

1. **隔离副本 `check-docs`**（5 件全部 staged，`git ls-files` 2215 / 实扫 2212）：
   ```
   ① 导航/口径文档无盘符绝对路径            [PASS]
   ② 技术栈版本与 pom.xml / package.json 一致 [PASS]
   ③ 导航/口径文档引用的文件存在            [PASS]
   ④ 端口表与 application.yml / vite.config.ts 一致 [PASS]
   ⑤ 命名纪律禁则（git ls-files 全量）       [PASS]
   ⑥ .java 探针不参与构建                   [PASS]
   全部通过：6/6 断言绿。  exit 0
   ```
2. **隔离副本 `pre-commit`（5 件 staged）**：`exit 1`，**仅 1 处命中**：
   ```
   docs/superpowers/plans/2026-10-25-ai-remediation.md:17  [PATH]  `<本机路径>/Program
   ```
3. **隔离副本 `pre-commit`（只 stage 4 件非总纲文件）**：`✓ 暂存区无命中`，**exit 0**。
   ⇒ 四件文档本身**完全合规**；唯一命中来自总纲的 Maven 路径。
4. **干净克隆复验**（新建 `<验证副本>/_applycheck-docsbatch` @ 逐字 `cb0761d`）：
   - `git apply --check` → **exit 0**；实际 apply 成功后，四件与隔离副本对应文件 **`cmp` 逐字节相同**（4/4 SAME）；
   - 再把主仓脱敏总纲拷入并 stage → `check-docs` **6/6 exit 0**；`pre-commit` **同样只报 1 处**（总纲 `:17`）。
5. 补丁生成口径：`git diff --cached --binary -- <4 路径>`（不含总纲）。

### 4.1 ⚠ 与"pre-commit 不需要逃生口"的差距（必须由主控决策）

你给的前提是"Maven 改用 IDEA 内置路径后，pre-commit 不必 `AI_SECRETS_ALLOW=1`"。**实测不成立**：仓库 `scripts/githooks/scan-lib.sh` 的 PATH 规则除了盘符，还覆盖 Git Bash 挂载路径 `/c…/d…`：

```
scan_rule_path:
  (^|[^A-Za-z0-9_])[A-Za-z]:[\\/][-A-Za-z0-9_.\\/]{1,60}
  (^|[^A-Za-z0-9_/])\/[cdefgh]\/[-A-Za-z0-9_.\\/]{1,60}
```

`<工具安装目录>/…` 命中第二条（token = `<本机路径>/Program`），而豁免只放行含 `<…>` / xxx / placeholder / redacted 的片段。因此**当前总纲仍会阻断提交**。

**最小合规收口（不由我改，总纲归主控）**：把该 token 换成与 `Spec` 同风格的尖括号占位符，例如
`<IDEA_MAVEN>/mvn.cmd`（或 `<IDEA_HOME>/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`），一行替换即可让 `pre-commit` 变为 0 命中——**这才是"不需要逃生口"的形态**。是否这样改由主控/用户定；另一个选项是主控把该文档排除在提交外，或接受钩子阻断（不建议）。

> 四件非总纲文档已实测 0 命中 ⇒ **只要总纲这一处收口，整批即可在无放行的情况下过钩**。

---

## 5. 两行状态与"是否已推送"

- `笔·AI 全链路只读验收审计终稿入库`、`笔·AI 修复总纲入库（先复现、再修、统一终验）` **均保持 📤 完成待提交**。
- 明确写了「**未见推送即不算已推送**」「已准备 ≠ 已入库」；未出现"已 commit / 已 push"字样（本批确未提交、未推送）。
- Task 6 的 `cb0761d` 是**代码面已提交事实**，与文档批是否推送无关，行内已分开表述；其**干净提交验证仍在跑**，未写成已验证。

---

## 6. 未做 / 边界（如实）

- 未提交、未推送（任务明令）；隔离副本 5 件已 stage、无 commit。
- 未改任何产品代码 / 测试 / 脚本 / 工作流；未改主仓文件（主仓三份文档仍是上一轮的未更正版本，需按 §3 替换）。
- 未改 `docs/README.md`「一张表」；未把 `## 10-23` 折上（会动 ~45 行结构，与本批"只追加"冲突）。
- 外部证据（FA1–FA6 报告 / `FA-adversarial.md` / 两份失败归因报告 / 运行日志）**留在仓外、未入库** ⇒ 审计 §8.2 覆盖表在仓内只有指针，无法独立复核；审计 §11 十六条不声称、§12 交付边界全部保留。
- 代码面结论未变：`34fd8f4` 之后的候选非最终版、非全部复审；两条 run 均 failure、根因已定；`@real` 未触发；**无新增 CI 绿**（最近可引用绿 `40146770423`@`bbc2089`，早于候选）。

---

## 7. 残留物

| 路径 | 说明 |
|---|---|
| `<仓外证据>/docs-batch-final.patch` | **本批应使用的补丁**（4 件，相对 `cb0761d`） |
| `<仓外证据>/docs-batch-final-report.md` | 本报告 |
| `<验证副本>/_verify-20261025-docs-batch/` | 隔离副本（HEAD `cb0761d`，5 件已 stage、未 commit；可直接取文件） |
| `<验证副本>/_applycheck-docsbatch/` | 干净克隆复验用（可删） |
| `<仓外证据>/docs-batch.patch`、`docs-batch-report.md` | **上一轮产物，已作废**（勿用） |
