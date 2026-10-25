# 仓外 AI 整改正式报告归档 · 2026-10-25

> **用途**：把 2026-10-25「AI 全链路整改 + 只读验收审计」周期里落在**仓外**的正式过程报告与审计分片，按原相对路径脱敏归档进仓，使这批结论在仓内可检索、可复核。
> **性质**：**历史证据**。本目录里的每一份都是**当时**的施工/复核/红队/CI/审计留痕，**不代替**任何终稿或现行基线；与现行文档冲突时，优先级为 **决策（`docs/adr/`） > 现行基线（`docs/02`、`docs/03`） > 证据（`docs/evidence/`）**。
> **不改结论**：入库副本只把**本机盘符绝对路径的根段**与**旧主仓目录名**改写为占位符（§4）。技术结论、缺陷编号与等级、测试数字、状态（FAIL/PASS/红/绿）、历史结论**一字未改**；`OpenAI` / `DeepSeek` 等模型兼容语义未做任何全局替换。
> **上游终稿**：只读验收审计的**唯一终稿**是 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`（已 tracked）。本目录的 `audit/ai-full-audit/FINAL-AUDIT.md` 是**该终稿的源件**，仅供溯源；两者冲突以 tracked 终稿 + 其「归档 addendum」（2026-10-25）为准。
> **一个都不能漏的边界**：本批入档 88 份；未入档的 9 份 `.md` 全为**运行产物**，逐份列在 §7。

---

## 1. 计数对账（本目录 vs 盘点口径）

| # | 面（按**物理位置**拆行，互不重叠） | 盘点口径 | 实测源 `.md` | 本批入档 | 未入档 | 说明 |
|---|---|---|---|---|---|---|
| 1 | 仓外顶层正式报告 `<仓外证据>/*.md` | 49 | 50 | 49 | 1 | 文件名原样保留，落 `reports/`；未入档的 1 份是**与已 tracked 终稿逐字节相同的重复件**（主仓侧移出物，按裁决不入库也不删除，见 §7.1 第 9 行） |
| 2 | 审计目录 `<仓外证据>/ai-full-audit/**`（含终稿源件 `FINAL-AUDIT.md`） | 38 ᵃ | 38 | 38 | 0 | 原相对路径原样，落 `audit/ai-full-audit/` |
| 3 | 其余 `*-evidence/` 目录下的 `.md` | 未列 | 9 | 1 | 8 | 1 份人工取证说明入档（`main-ci-cb0761d-evidence/README.md` → `audit/evidence-notes/`）；其余 8 份为运行产物（§7.1） |
| — | **合计** | 88 ᵃ（49 + 39） | **97** | **88** | **9** | |

> ᵃ **「49 + 39」的口径对账**（逐文件核过，无遗漏、无重复）：
> 任务盘点写「顶层 49 + 审计 39」，其中 39 = **37 份 `ai-full-audit` 正式件**（`A`–`J` 10 + `K`/`L`/`M`/`N`/`P`/`P2` 6 + `FA-adversarial` 1 + `FINAL-AUDIT-DRAFT` 1 + `final-gap/` 13 + `gaps/` 6）+ **2 份被记作「`ai-full-audit/real-*` 侧」的 Playwright 运行产物**（物理位置在 `real-e2e-evidence/`，故本表落在行 3）。
> 本批入档的 38 份 `ai-full-audit` = 上述 37 份 + **终稿源件 `FINAL-AUDIT.md`**（盘点把它算作"已映射"、未计入 39；本批把源件一并放回，使审计目录在本目录内**自成完整一套**）。
> 结论：**39 里的 37 份正式件 + 终稿源件全部入档**（38 份）；其中那 2 份 Playwright 产物按「排除运行数据」口径未入档，逐份见 §7.1。`ai-full-audit/` 下（排除 clone 树 `tree/`）实测 `38` 份 `.md`，与入档数**相等**。

---

## 2. 目录结构

```
docs/evidence/ai-remediation-2026-10-25/
├── README.md                              ← 本文件（自包含索引）
├── reports/                               ← 仓外顶层正式报告 49 份（文件名原样）
│   ├── STATUS.md
│   ├── final-backend-review.md
│   └── …
└── audit/
    ├── ai-full-audit/                     ← 只读验收审计 38 份（原相对路径原样，含 final-gap/、gaps/）
    │   ├── A-inventory.md … P2-gates-full.md
    │   ├── final-gap/{00-README,FA1..FA6,FA1-report..FA6-report}.md
    │   └── gaps/{00-README,01..05-*}.md
    └── evidence-notes/
        └── main-ci-cb0761d-evidence-README.md
```

**每份副本的页首标识**：归档副本第一行（有 YAML front matter 的插在其后）为一行业源注记，形如
`> 来源=<仓外证据>/<原相对路径>（2026-10-25 归档）· <类别说明>`，
其中类别说明区分「审计分片/草稿」「仓外正式过程报告」「取证说明」，并显式声明**属历史证据、不代替终稿/现行基线**。这是本目录相对源件的**唯一新增文字行**（88 份各 +1 行）。

除这行页首标识外，与源件的差异**只允许三类**：① §4.1 的**路径占位符**（盘符根段 / 旧仓目录名）；② §4.6 的**个人用户名占位符**（`<仓库所有者>`）；③ §4.5 的**纯空白清理**（行尾空格/tab、末尾多余空行、缺失的结尾换行）。**不允许任何技术文字/结论/数字/状态差异** —— 判据见 §8.1 第 2 行（逐文件"除路径、个人用户名与纯空白外逐字一致"）。

---

## 3. 占位符语义（本目录通用）

| 占位符 | 指代 | 备注 |
|---|---|---|
| `<主仓>` | 旧主仓工作树根（本仓本体） | 原盘符全路径的根段被替换，其余相对段原样保留 |
| `<仓外证据>` | 本批报告的仓外根目录（原路径里的 `_p2-…` 目录） | 报告正文里大量 `./xxx.md` 相对引用可按此落地 |
| `<隔离副本>` | 本交付副本根目录（`_delivery-…`） | 仅在本文件与 addendum 中使用，源报告里无此形态 |
| `<会话证据>` | 会话日志目录（盘点/取证的会话证据根） | 仅在本文件使用（§8 的盘点来源） |
| `<验证副本>` | 报告当时使用的**仓外验证/应用检查副本**根（`_verify-*`、`_applycheck-*`、`_cluster3-*` 等，目录名保留在占位符之后） | 与 `<隔离副本>` 不是一个东西 |
| `<工具安装目录>` | 本机 IDE/构建工具安装根（`…/Program Files/…`） | 如 Maven 可执行的全路径 |
| `<本机路径>` | 无法归类的本机绝对路径（含被截断的扫描输出、省略式 `C:\…\data\…`） | 见 §4.3 |
| `<用户主目录>` | 本机用户主目录 | 路径根段形态，见 §4.1 |
| `<仓库所有者>` | **个人用户名 / 仓库所有者账号名**（含全名与长度 ≥4 的独立片段；本文件**不复述**原值） | 见 §4.6；主仓 pre-commit 的 `USER` 规则会拦，故必须占位化 |

> 说明：源报告里本来就存在的占位符（如 `<主仓当前工作树>`、`<main>`、`<SCRATCH>` 形态）**原样保留**，不在上表语义内。

---

## 4. 脱敏口径

### 4.1 三类替换（路径 × 2 + 个人用户名）

1. **本机盘符绝对路径的根段** → 占位符（§3）；路径的**其余相对段原样保留**（含原分隔符、大小写、引号、反引号）。
2. **旧主仓目录名**（裸形态，即不带盘符的仓库目录名）→ `<主仓>`；含 `…-<提交短号>` 后缀时后缀保留。
3. **个人用户名 / 仓库所有者账号名** → `<仓库所有者>`（口径与主仓 `scripts/githooks/scan-lib.sh` 的 `USER` 规则同构：取 `git config user.name` 全名 + 按 `[._@空白-]` 拆出的长度 ≥4 的片段，前后不得紧邻 ASCII 字母数字，故 `hobby`、`xiangqi` 这类普通词不会被误替换）。明细见 §4.6。

除此以外**不做任何改写**：不删不改技术结论、缺陷编号、等级、计数、测试通过/失败数字、提交哈希、状态词、历史结论；不对 `OpenAI`/`DeepSeek`/`gpt-*`/`deepseek-*` 等模型标识做全局替换。

### 4.2 替换总量（本批 88 份）

| 占位符 | 替换处数 | 命中文件数 |
|---|---|---|
| `<仓外证据>` | 186 | 67 |
| `<验证副本>` | 73 | 37 |
| `<主仓>` | 58 | 41 |
| `<工具安装目录>` | 16 | 13 |
| `<本机路径>` | 5 | 3 |
| `<仓库所有者>` | 2 | 2 |
| 合计 | **340** | **75 / 88**（其余 13 份源文件本就不含本机路径、旧仓目录名与个人用户名） |

### 4.3 已知的三处「占位符粒度差异」（不影响语义，如实登记）

| # | 文件 | 现象 | 原因 |
|---|---|---|---|
| 1 | `reports/docs-batch-final-report.md`、`reports/docs-batch-report.md` | 同一被引用的工具路径，完整形态落 `<工具安装目录>`，被截断的扫描输出 `…/Program` 落 `<本机路径>` | 源文引的是**扫描器打印的截断 token**（`…/Program`），按 token 边界替换后自然分裂；两处指同一路径 |
| 2 | `audit/ai-full-audit/E-business-ai.md` | 省略式路径落 `<本机路径>` | 源文写的是 `C:\…\data\files\x.xlsx`（`…` 省略中间段），无稳定前缀可归类 |
| 3 | 多份报告 | 正文用「隔离副本」一词指其**当时的验证副本** | 该词在源文里是普通名词，本目录把路径改写为 `<验证副本>`；`<隔离副本>` 专指本交付副本，勿混用（§3） |

### 4.4 未读取、未改写的内容

- **未读任何密钥文件**：`.env`、`backend/config/*`、SSH 私钥等一律未打开；未使用 `AI_SECRETS_ALLOW=1` 之类的放行开关。
- 源 `.md` 内**未发现**任何密钥形态（扫描结论见 §8.2），故本目录不含密钥值，无需打码。

### 4.5 排版清理（纯空白，不改字面）

落库前对每份副本做了**只涉及空白**的三件清理（行首缩进一律不动，代码块与表格结构不受影响）：

1. 去掉**每行行尾的空格 / tab**（"只有空格的空行"→ 真空行）；
2. 去掉**文件末尾多余的空行**（保留恰好一个结尾换行）；
3. **缺失结尾换行的文件补一个**（换行符沿用该文件自身风格：LF 文件补 LF、CRLF 文件补 CRLF）。

本批实际命中 14 份、命中面如下（逐文件；`wc -l` 口径的行数变化已体现在 §5/§6 的「归档行」列）：

| # | 归档路径 | 行尾空格/tab | 文件末尾多余空行 | 补结尾换行 | 源行 | 归档行 |
|---:|---|---:|---:|---:|---:|---:|
| 1 | `reports/cluster3-34fd8f4-analysis.md` | — | — | 1 | 159 | 160 |
| 2 | `reports/redis-guard-pg-report.md` | 3 | — | — | 332 | 333 |
| 3 | `reports/redis-guard-scope-fix-report.md` | 5 | — | — | 523 | 524 |
| 4 | `audit/ai-full-audit/D-model-history.md` | — | 1 | — | 359 | 359 |
| 5 | `audit/ai-full-audit/FA-adversarial.md` | — | — | 1 | 58 | 59 |
| 6 | `audit/ai-full-audit/J-test-ci-integrity.md` | — | 1 | — | 467 | 467 |
| 7 | `audit/ai-full-audit/N-gap-adversarial.md` | — | — | 1 | 121 | 122 |
| 8 | `audit/ai-full-audit/P-adversarial.md` | — | — | 1 | 61 | 62 |
| 9 | `audit/ai-full-audit/final-gap/FA1-report.md` | — | — | 1 | 133 | 134 |
| 10 | `audit/ai-full-audit/final-gap/FA2-report.md` | — | — | 1 | 183 | 184 |
| 11 | `audit/ai-full-audit/final-gap/FA3-report.md` | — | — | 1 | 209 | 210 |
| 12 | `audit/ai-full-audit/final-gap/FA4-report.md` | — | — | 1 | 291 | 292 |
| 13 | `audit/ai-full-audit/final-gap/FA5-report.md` | — | — | 1 | 190 | 191 |
| 14 | `audit/ai-full-audit/final-gap/FA6-report.md` | — | — | 1 | 172 | 173 |

合计：**行尾空格/tab 8 行 · 文件末尾多余空行 2 处 · 补结尾换行 10 份**；清理后本目录**零行尾空白、每份恰好一个结尾换行**（这是 `git diff --check` / `git diff --cached --check` 零告警的前提，见 §8.1 第 7 行）。

> 因此行数口径修正为：**归档行 = 清理后源行 + 1（页首标识）**；对源件直接比对时则为 **源行 + 1 − 该文件被清掉的末尾空行数**（本批 2 份各 −1），另 10 份为"补结尾换行"，其逻辑行数不变（只多出一个换行符）。全批合计 源 18002 行 → 归档 18088 行（**+86**）。
> **判断基准不变**：清理只动空白，**不改任何字**；"除路径、个人用户名与纯空白外逐字一致"逐文件校验结果见 §8.1。

### 4.6 个人用户名脱敏（`<仓库所有者>`）

**为什么要做**：主仓 `git config user.name` 的取值（一个账号串，本文件**不复述原值**）会被主仓 `scripts/githooks/pre-commit` 的 `USER` 规则判为「个人用户名」并**阻断提交** —— 该规则同时收录**全名**与按 `[._@空白-]` 拆出的**长度 ≥4 的独立片段**。因此本目录把它作为**第三类脱敏**，一律替换为 `<仓库所有者>`。

**替换口径**（与引擎逐字同构，不做"只查这两处"的定点补丁）：

| 项 | 值 |
|---|---|
| 规则取法 | `git config user.name` → 全名（小写）+ 按 `[._@空白-]` 拆出的长度 ≥4 片段 |
| 本机实测 token 集合 | 共 3 个：1 个全名 + 长度 ≥4 的独立片段（**不在此复述原值**，避免本文件自身命中 `USER` 规则） |
| 替换目标 | 全部 → `<仓库所有者>`（同一语义：仓库所有者账号名） |
| 词边界守卫 | token 前后**不得紧邻 ASCII 字母数字**（故 `hobby` / `xiangqi` 一类词不受影响，与引擎豁免策略一致） |
| 与本批其它替换的顺序 | **路径根段先替换**（`<用户主目录>` 等先吃掉路径里的用户名），再做用户名替换 |

**本批实际命中（2 处 / 2 份）**：

| # | 归档路径 | 归档行 | 原 token 形态 | 替换为 |
|---:|---|---:|---|---|
| 1 | `reports/main-ci-cancel-analysis.md` | 24 | 全名（含分隔符） × 1 | `<仓库所有者>` |
| 2 | `audit/evidence-notes/main-ci-cb0761d-evidence-README.md` | 12 | 全名（含分隔符） × 1 | `<仓库所有者>` |

> 替换后整批 88 份 ＋ 本索引对 `USER` 规则**零命中**（复核见 §8.1 第 8 行、§8.2 末条）；行数不受影响（同一行内等量替换），故 §5/§6 的「归档行」与 §4.5 口径一致。
> 说明：这两个位置引的是 `gh api repos/<所有者>/<仓库>/…` 形式的命令与表格，替换只动账号名，**命令结构、endpoint、run/job 编号、结论与数字均未变**。

---

## 5. 映射表 · 仓外顶层正式报告（49 份 → `reports/`）

列说明：**源行/归档行** = `wc -l` 口径（`归档行` 已按 §4.5 的排版清理计，即 `源行 + 1 − 末尾空行数`）；**替换** = 该文件内各占位符替换处数（`—` = 0）。

| # | 源（相对 `<仓外证据>`） | 归档路径（相对本目录） | 源行 | 归档行 | 替换 |
|---:|---|---|---:|---:|---|
| 1 | `P0-frame50-evidence-note.md` | `reports/P0-frame50-evidence-note.md` | 108 | 109 | 主仓×1 |
| 2 | `STATUS.md` | `reports/STATUS.md` | 115 | 116 | 主仓×1 |
| 3 | `agent-56-final-report.md` | `reports/agent-56-final-report.md` | 163 | 164 | 验证副本×4 · 工具安装目录×2 · 主仓×1 |
| 4 | `agent-75-final-report.md` | `reports/agent-75-final-report.md` | 265 | 266 | 验证副本×3 · 主仓×1 · 工具安装目录×1 |
| 5 | `cluster-config-resume-fix2-report.md` | `reports/cluster-config-resume-fix2-report.md` | 166 | 167 | 仓外证据×3 · 验证副本×3 · 工具安装目录×1 |
| 6 | `cluster-config-resume-report.md` | `reports/cluster-config-resume-report.md` | 128 | 129 | 仓外证据×3 · 验证副本×3 · 主仓×1 · 工具安装目录×1 |
| 7 | `cluster-config-resume-review.md` | `reports/cluster-config-resume-review.md` | 148 | 149 | 主仓×3 · 仓外证据×2 · 验证副本×1 |
| 8 | `cluster-infra-fix1-report.md` | `reports/cluster-infra-fix1-report.md` | 143 | 144 | 仓外证据×3 · 主仓×1 · 工具安装目录×1 · 验证副本×1 |
| 9 | `cluster-infra-rereview1.md` | `reports/cluster-infra-rereview1.md` | 109 | 110 | 仓外证据×2 · 主仓×1 · 验证副本×1 |
| 10 | `cluster-infra-task-review.md` | `reports/cluster-infra-task-review.md` | 144 | 145 | 主仓×1 · 仓外证据×1 · 验证副本×1 |
| 11 | `cluster-probe-fix1-report.md` | `reports/cluster-probe-fix1-report.md` | 251 | 252 | 仓外证据×16 · 验证副本×5 · 主仓×2 |
| 12 | `cluster-probe-fix2-report.md` | `reports/cluster-probe-fix2-report.md` | 211 | 212 | 仓外证据×12 · 验证副本×3 |
| 13 | `cluster-probe-rereview1.md` | `reports/cluster-probe-rereview1.md` | 165 | 166 | 主仓×1 |
| 14 | `cluster-probe-rereview2.md` | `reports/cluster-probe-rereview2.md` | 146 | 147 | — |
| 15 | `cluster-probe-task-review.md` | `reports/cluster-probe-task-review.md` | 248 | 249 | 主仓×2 · 验证副本×1 |
| 16 | `cluster-protocol-report.md` | `reports/cluster-protocol-report.md` | 277 | 278 | 仓外证据×12 · 验证副本×5 · 主仓×2 |
| 17 | `cluster-r3-triage.md` | `reports/cluster-r3-triage.md` | 294 | 295 | 验证副本×4 · 仓外证据×3 · 主仓×1 |
| 18 | `cluster3-34fd8f4-analysis.md` | `reports/cluster3-34fd8f4-analysis.md` | 159 | 160 | 仓外证据×3 · 主仓×1 |
| 19 | `docs-batch-final-report.md` | `reports/docs-batch-final-report.md` | 142 | 143 | 仓外证据×5 · 验证副本×5 · 本机路径×3 · 工具安装目录×2 |
| 20 | `docs-batch-report.md` | `reports/docs-batch-report.md` | 136 | 137 | 仓外证据×7 · 验证副本×2 · 主仓×1 · 本机路径×1 |
| 21 | `final-backend-review.md` | `reports/final-backend-review.md` | 95 | 96 | 主仓×1 · 仓外证据×1 |
| 22 | `final-frontend-guard-review.md` | `reports/final-frontend-guard-review.md` | 94 | 95 | 主仓×1 · 仓外证据×1 · 验证副本×1 |
| 23 | `final-guard-rereview.md` | `reports/final-guard-rereview.md` | 125 | 126 | 仓外证据×2 · 验证副本×2 · 主仓×1 |
| 24 | `frontend-final-validation.md` | `reports/frontend-final-validation.md` | 142 | 143 | 仓外证据×4 · 主仓×2 |
| 25 | `frontend-result-start-fix-report.md` | `reports/frontend-result-start-fix-report.md` | 184 | 185 | 验证副本×2 · 主仓×1 · 仓外证据×1 · 工具安装目录×1 |
| 26 | `frontend-result-start-review.md` | `reports/frontend-result-start-review.md` | 58 | 59 | 主仓×1 · 仓外证据×1 · 验证副本×1 |
| 27 | `main-ci-34fd8f4-analysis.md` | `reports/main-ci-34fd8f4-analysis.md` | 145 | 146 | 仓外证据×1 |
| 28 | `main-ci-cancel-analysis.md` | `reports/main-ci-cancel-analysis.md` | 122 | 123 | 仓外证据×2 · 主仓×1 · 仓库所有者×1 |
| 29 | `main-ci-cb0761d-analysis.md` | `reports/main-ci-cb0761d-analysis.md` | 165 | 166 | 主仓×2 · 仓外证据×1 |
| 30 | `migration-drain-restore-report.md` | `reports/migration-drain-restore-report.md` | 157 | 158 | 主仓×3 · 仓外证据×3 · 验证副本×1 |
| 31 | `migration-fix1-report.md` | `reports/migration-fix1-report.md` | 160 | 161 | 仓外证据×3 · 工具安装目录×2 · 主仓×1 · 验证副本×1 |
| 32 | `migration-rereview1.md` | `reports/migration-rereview1.md` | 104 | 105 | 仓外证据×3 · 主仓×1 |
| 33 | `migration-task-review.md` | `reports/migration-task-review.md` | 119 | 120 | 主仓×2 · 仓外证据×2 |
| 34 | `park-audit-fix-report.md` | `reports/park-audit-fix-report.md` | 168 | 169 | 仓外证据×6 · 验证副本×2 · 工具安装目录×1 |
| 35 | `park-audit-review.md` | `reports/park-audit-review.md` | 111 | 112 | 仓外证据×2 · 验证副本×1 |
| 36 | `park-heal-error-report.md` | `reports/park-heal-error-report.md` | 155 | 156 | 仓外证据×5 · 工具安装目录×1 · 验证副本×1 |
| 37 | `park-latch-fix-report.md` | `reports/park-latch-fix-report.md` | 206 | 207 | 验证副本×2 · 主仓×1 · 仓外证据×1 · 工具安装目录×1 |
| 38 | `park-latch-wake-rereview.md` | `reports/park-latch-wake-rereview.md` | 119 | 120 | 仓外证据×5 · 主仓×1 · 验证副本×1 |
| 39 | `park-seq-rereview1.md` | `reports/park-seq-rereview1.md` | 75 | 76 | 主仓×1 · 仓外证据×1 · 验证副本×1 |
| 40 | `park-seq-task-review.md` | `reports/park-seq-task-review.md` | 113 | 114 | 仓外证据×2 · 主仓×1 |
| 41 | `redis-guard-pg-report.md` | `reports/redis-guard-pg-report.md` | 332 | 333 | 主仓×3 · 仓外证据×3 · 验证副本×2 |
| 42 | `redis-guard-scope-fix-report.md` | `reports/redis-guard-scope-fix-report.md` | 523 | 524 | 仓外证据×3 · 主仓×2 · 验证副本×2 |
| 43 | `resume-start-task-review.md` | `reports/resume-start-task-review.md` | 68 | 69 | 主仓×1 · 仓外证据×1 · 验证副本×1 |
| 44 | `resume-tool-start-fix-report.md` | `reports/resume-tool-start-fix-report.md` | 190 | 191 | 验证副本×2 · 主仓×1 · 仓外证据×1 · 工具安装目录×1 |
| 45 | `tab-clear-final-report.md` | `reports/tab-clear-final-report.md` | 238 | 239 | 仓外证据×7 · 主仓×3 · 验证副本×2 |
| 46 | `tab-clear-fix1-report.md` | `reports/tab-clear-fix1-report.md` | 146 | 147 | 仓外证据×7 · 主仓×2 · 验证副本×1 |
| 47 | `tab-clear-task-review.md` | `reports/tab-clear-task-review.md` | 142 | 143 | 主仓×1 · 验证副本×1 |
| 48 | `tab-fresh-session-report.md` | `reports/tab-fresh-session-report.md` | 162 | 163 | 主仓×1 · 验证副本×1 |
| 49 | `wake-self-fix-report.md` | `reports/wake-self-fix-report.md` | 145 | 146 | 仓外证据×7 · 主仓×2 · 验证副本×2 · 工具安装目录×1 |

---

## 6. 映射表 · 只读验收审计（38 份 → `audit/ai-full-audit/`）

### 6.1 域报告与对抗复核（18 份）

| # | 源（相对 `<仓外证据>`） | 归档路径（相对本目录） | 源行 | 归档行 | 替换 |
|---:|---|---|---:|---:|---|
| 1 | `ai-full-audit/A-inventory.md` | `audit/ai-full-audit/A-inventory.md` | 667 | 668 | 仓外证据×3 |
| 2 | `ai-full-audit/B-runtime-sse.md` | `audit/ai-full-audit/B-runtime-sse.md` | 490 | 491 | 仓外证据×1 |
| 3 | `ai-full-audit/C-park-tools.md` | `audit/ai-full-audit/C-park-tools.md` | 575 | 576 | 仓外证据×1 |
| 4 | `ai-full-audit/D-model-history.md` | `audit/ai-full-audit/D-model-history.md` | 359 | 359 | 仓外证据×1 |
| 5 | `ai-full-audit/E-business-ai.md` | `audit/ai-full-audit/E-business-ai.md` | 298 | 299 | 仓外证据×1 · 本机路径×1 |
| 6 | `ai-full-audit/F-redis-infra.md` | `audit/ai-full-audit/F-redis-infra.md` | 543 | 544 | 仓外证据×2 |
| 7 | `ai-full-audit/FA-adversarial.md` | `audit/ai-full-audit/FA-adversarial.md` | 58 | 59 | 仓外证据×1 |
| 8 | `ai-full-audit/FINAL-AUDIT-DRAFT.md` | `audit/ai-full-audit/FINAL-AUDIT-DRAFT.md` | 622 | 623 | 仓外证据×1 |
| 9 | `ai-full-audit/FINAL-AUDIT.md` | `audit/ai-full-audit/FINAL-AUDIT.md` | 1062 | 1063 | 仓外证据×4 |
| 10 | `ai-full-audit/G-identity-isolation.md` | `audit/ai-full-audit/G-identity-isolation.md` | 501 | 502 | 仓外证据×1 |
| 11 | `ai-full-audit/H-frontend-chat.md` | `audit/ai-full-audit/H-frontend-chat.md` | 319 | 320 | 仓外证据×2 · 验证副本×1 |
| 12 | `ai-full-audit/I-frontend-workspace.md` | `audit/ai-full-audit/I-frontend-workspace.md` | 284 | 285 | 仓外证据×1 |
| 13 | `ai-full-audit/J-test-ci-integrity.md` | `audit/ai-full-audit/J-test-ci-integrity.md` | 467 | 467 | 仓外证据×3 |
| 14 | `ai-full-audit/K-synthesis.md` | `audit/ai-full-audit/K-synthesis.md` | 703 | 704 | 仓外证据×2 |
| 15 | `ai-full-audit/L-adversarial.md` | `audit/ai-full-audit/L-adversarial.md` | 287 | 288 | 仓外证据×1 |
| 16 | `ai-full-audit/M-adversarial.md` | `audit/ai-full-audit/M-adversarial.md` | 352 | 353 | 仓外证据×1 |
| 17 | `ai-full-audit/N-gap-adversarial.md` | `audit/ai-full-audit/N-gap-adversarial.md` | 121 | 122 | 仓外证据×2 |
| 18 | `ai-full-audit/P-adversarial.md` | `audit/ai-full-audit/P-adversarial.md` | 61 | 62 | 仓外证据×1 |
| 19 | `ai-full-audit/P2-gates-full.md` | `audit/ai-full-audit/P2-gates-full.md` | 204 | 205 | 仓外证据×1 |

### 6.2 缺口收尾分片 `final-gap/`（13 份 · 草稿/分片，历史证据）

| # | 源（相对 `<仓外证据>`） | 归档路径（相对本目录） | 源行 | 归档行 | 替换 |
|---:|---|---|---:|---:|---|
| 1 | `ai-full-audit/final-gap/00-README.md` | `audit/ai-full-audit/final-gap/00-README.md` | 79 | 80 | 仓外证据×1 |
| 2 | `ai-full-audit/final-gap/FA1-report.md` | `audit/ai-full-audit/final-gap/FA1-report.md` | 133 | 134 | 仓外证据×1 |
| 3 | `ai-full-audit/final-gap/FA1.md` | `audit/ai-full-audit/final-gap/FA1.md` | 27 | 28 | — |
| 4 | `ai-full-audit/final-gap/FA2-report.md` | `audit/ai-full-audit/final-gap/FA2-report.md` | 183 | 184 | 仓外证据×1 |
| 5 | `ai-full-audit/final-gap/FA2.md` | `audit/ai-full-audit/final-gap/FA2.md` | 22 | 23 | — |
| 6 | `ai-full-audit/final-gap/FA3-report.md` | `audit/ai-full-audit/final-gap/FA3-report.md` | 209 | 210 | 仓外证据×1 |
| 7 | `ai-full-audit/final-gap/FA3.md` | `audit/ai-full-audit/final-gap/FA3.md` | 23 | 24 | — |
| 8 | `ai-full-audit/final-gap/FA4-report.md` | `audit/ai-full-audit/final-gap/FA4-report.md` | 291 | 292 | 仓外证据×1 |
| 9 | `ai-full-audit/final-gap/FA4.md` | `audit/ai-full-audit/final-gap/FA4.md` | 28 | 29 | — |
| 10 | `ai-full-audit/final-gap/FA5-report.md` | `audit/ai-full-audit/final-gap/FA5-report.md` | 190 | 191 | 仓外证据×1 |
| 11 | `ai-full-audit/final-gap/FA5.md` | `audit/ai-full-audit/final-gap/FA5.md` | 28 | 29 | — |
| 12 | `ai-full-audit/final-gap/FA6-report.md` | `audit/ai-full-audit/final-gap/FA6-report.md` | 172 | 173 | — |
| 13 | `ai-full-audit/final-gap/FA6.md` | `audit/ai-full-audit/final-gap/FA6.md` | 28 | 29 | — |

### 6.3 缺口清单 `gaps/`（6 份 · 草稿/分片，历史证据）

| # | 源（相对 `<仓外证据>`） | 归档路径（相对本目录） | 源行 | 归档行 | 替换 |
|---:|---|---|---:|---:|---|
| 1 | `ai-full-audit/gaps/00-README.md` | `audit/ai-full-audit/gaps/00-README.md` | 64 | 65 | 仓外证据×1 |
| 2 | `ai-full-audit/gaps/01-tool-prompt-resultview.md` | `audit/ai-full-audit/gaps/01-tool-prompt-resultview.md` | 82 | 83 | — |
| 3 | `ai-full-audit/gaps/02-context-prompt-surface.md` | `audit/ai-full-audit/gaps/02-context-prompt-surface.md` | 87 | 88 | — |
| 4 | `ai-full-audit/gaps/03-config-provider-lease.md` | `audit/ai-full-audit/gaps/03-config-provider-lease.md` | 99 | 100 | — |
| 5 | `ai-full-audit/gaps/04-kb-form-web-frames.md` | `audit/ai-full-audit/gaps/04-kb-form-web-frames.md` | 100 | 101 | — |
| 6 | `ai-full-audit/gaps/05-uncovered-branches.md` | `audit/ai-full-audit/gaps/05-uncovered-branches.md` | 80 | 81 | — |

### 6.4 审计侧边界件（1 份）

| # | 源（相对 `<仓外证据>`） | 归档路径（相对本目录） | 源行 | 归档行 | 替换 |
|---:|---|---|---:|---:|---|
| 1 | `main-ci-cb0761d-evidence/README.md` | `audit/evidence-notes/main-ci-cb0761d-evidence-README.md` | 23 | 24 | 仓外证据×1 · 仓库所有者×1 |

> `audit/ai-full-audit/` 内的分片与草稿（尤其 `FINAL-AUDIT-DRAFT.md`、`FA*-report.md`、`gaps/*`）是**终稿的取材过程**，**任何一份都不代替终稿**；终稿按其 §0.1/§8.2 的口径收口。

---

## 7. 未包含项（逐项列明，含原因）

### 7.1 未入档的 `.md`（9 份：8 份运行产物 + 1 份重复件）

| # | 源（相对 `<仓外证据>`） | 未入档原因 |
|---|---|---|
| 1 | `cluster-protocol-evidence/summary.md` | 集群验证脚本**自动生成**的结论摘要（含实例启动日志尾部）→ 运行数据 |
| 2 | `cluster-protocol-evidence/results.md` | 同上，逐用例结果表（自动生成）→ 运行数据 |
| 3 | `cluster3-r2-evidence/summary.md` | 同上 → 运行数据 |
| 4 | `cluster3-r2-evidence/results.md` | 同上 → 运行数据 |
| 5 | `cluster3-34fd8f4-evidence/cluster3-evidence-40182796512/summary.md` | 同上 → 运行数据 |
| 6 | `cluster3-34fd8f4-evidence/cluster3-evidence-40182796512/results.md` | 同上 → 运行数据 |
| 7 | `real-e2e-evidence/artifacts/…/<用例名>/error-context.md` | Playwright 失败用例**自动抓取**的 DOM/错误上下文 → 运行数据 |
| 8 | `real-e2e-evidence/html/data/<hash>.md` | Playwright HTML 报告的**数据分片**（`# Instructions` 模板 + 附件）→ 运行数据 |
| 9 | `排查-AI全链路只读验收审计-duplicate-2026-10-25.md` | 与已 tracked 终稿逐字节相同的重复件（主仓侧移出物），按裁决不入库也不删除（**不入档、不删除**；与已 tracked 终稿逐字节相同） |

> 第 1–8 份的**结论面**已被入档报告覆盖（`reports/cluster-protocol-report.md`、`reports/cluster3-34fd8f4-analysis.md`、`reports/cluster-r3-triage.md`、`reports/frontend-final-validation.md` 等），删掉原本运行物不影响任何结论的可复核性。
> 1 份重复件**既不入档也不删除**：它与已 tracked 的终稿**逐字节相同**，入档等于在证据树里再造一份终稿（且会让"顶层 49"变成 50），故只登记不入库。

### 7.2 非 `.md` 全量排除（运行物/临时件）

`<仓外证据>` 实测 **8074 个文件**（不含 clone 树）。其中 `.md` 96 份（本批取 88 份，§7.1 列 8 份），其余按类型**整体不入档**：

| 类型 | 实测份数 | 为何排除 |
|---|---|---|
| `.txt` | 3768 | 命令回显/日志转存/e2e 原始流 |
| `.json` / `.jsonl` | 3633 / 35 | 运行产物、SSE 流、Playwright 报告数据 |
| `.log` | 270 | 构建/测试/CI 日志 |
| `.diff` / `.patch` | 67 / 18 | 补丁副本；对应改动已在提交或工作树中，入库即成第二份真相 |
| `.java` 等代码件 | 40 + 少量 | 合并副本、探针源码（已在主仓或属临时件） |
| `.pid` / `.zip` / `.png` / 其它 | 31 + 少量 | 进程号、打包件、截图 |
| `ai-full-audit/tree/`（完整 clone） | **2201** | 审计时点的仓库整树快照；与主仓重复，且含自身历史，入库会污染证据树 |

> 口径依据：任务裁决明确排除 `.log` / `.patch` / `.diff` / clone 树 / 运行数据 / `.json` / `test-results`；本目录只收**正式 `.md` 报告**。

---

## 8. 校验与门禁（可复现）

### 8.1 完整性（本目录 88 份）

| # | 检查 | 命令要点 | 结果 |
|---|---|---|---|
| 1 | 源清单 ↔ 归档清单一一对应 | 独立枚举源目录 + 归档树 `find`，双向差集 | 88 → 88，**无缺项、无多余文件** |
| 2 | 逐文件「**除路径、个人用户名与纯空白外**正文逐字一致」 | 对**各自一侧**文本先脱敏、再折叠占位符、再做排版归一，然后逐字比对（88 份逐份） | **PASS**；差异只出现在路径段、个人用户名与行尾空白 |
| 3 | 行数 | 源 18002 行 → 清理后源 18000 行 → 归档 18088 行 | **+86** = 页首标识 88 行 − 文件末尾多余空行 2 行（见 §4.5）；源文件 EOL 风格（76 LF / 12 CRLF）原样保留 |
| 4 | 幂等 | 归档件再跑一次「脱敏 + 排版」 | 零变化 |
| 5 | URL 未误伤 | `http://`/`https://` 计数两侧相等 | 相等 |
| 6 | 排版零残留 | 逐行扫行尾空格/tab + 结尾换行数（88 份逐份） | **0 行行尾空白**；每份**恰好一个**结尾换行 |
| 7 | 落库零告警 | 隔离克隆内 `git add` 后跑 `git diff --check` 与 `git diff --cached --check` | **0 告警**（无 trailing whitespace / new blank line at EOF） |
| 8 | **个人用户名（USER）零残留** | ①`_tools/verify.mjs` 按 `scan-lib.sh` 的 `USER` 口径逐份自扫；②沙箱内跑**主仓真实 `scripts/githooks/pre-commit`** | ①88 份 + 本索引 **0 命中**（替换 2 处 / 2 份）；②`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` |

### 8.2 门禁口径（`scripts/ci/check-docs.mjs`）

- **断言 ⑤ 命名纪律禁则**：按 `git ls-files` **全量**扫描，规则 = 词首守卫 + `main` + 分隔符类（可零个）+ `v2`，且做 NFKC / 隐形字符双态 / 去分隔符连写三态归一。本目录 88 份**命中 0 处**（自查脚本逐字复刻该算法，见 §8.3）。
- **断言 ① 盘符绝对路径**：该断言只扫 4 份导航文档（不含 `docs/evidence/`），但本批按 **DC-08 纪律**对 88 份全量自扫：盘符/POSIX 形态残留 **0 处**（命中的都是 `<…>` 占位形态，属语言内既有豁免口）。
- **断言 ⑥ 探针清单**：本批不新增 `docs/evidence/probes/` 下 `.java`，不影响。
- **断言 ②③④**：只扫导航文档；本批未改 `README.md`/`docs/README.md`/`scripts/README.md`/`scripts/githooks/README.md`，不影响。
- **实跑**：在**只读克隆**（同 HEAD）+ 本目录 + 终稿 addendum 的沙箱里跑 `node scripts/ci/check-docs.mjs` → **6/6 断言绿**（证据见交付报告，主仓本体未被执行任何写操作）。
- **pre-commit 敏感信息三规则（SECRET / PATH / USER，阻断式）**：在同一个沙箱里对暂存区跑**主仓真实钩子** `sh scripts/githooks/pre-commit`，结论 **0 命中**（未使用 `AI_SECRETS_ALLOW`、未使用 `--no-verify`）。三者与 §8.1 第 8 行、§8.2 第 1–2 条互为交叉验证：命名纪律（check-docs ⑤）与敏感信息（pre-commit）是**两套不同规则**，本批两套都过。

### 8.3 本批自查脚本（不入库）

- 生成：`_tools/build.mjs`（+ `_tools/rules.mjs` 三类替换规则 + `tidyTypography()` 排版清理）—— 按源清单生成归档树与 `build-stats.json` / `build-excluded.json`。
- 校验：`_tools/verify.mjs` —— 完整性 / 除路径·用户名·纯空白外一致 / 行数 / 幂等 / 排版零残留 / 禁则 / 盘符 / 密钥形态 / 个人用户名（复刻 `scripts/githooks/scan-lib.sh` 的 SECRET·PATH·USER 规则集）。
- 复议：`_tools/scan-with-repo-engine.sh` —— **直接 source 主仓自带 `scan-lib.sh`**，对任意文件逐行全量扫描（规则、豁免、尖括号占位符剥离、长行跳过与 pre-commit 完全一致），用来"不复刻、直接用真引擎"复核归档与 SDD 面。
- `_tools/gate-sim.sh` —— 只读克隆 + 归档 + addendum + `.gitignore` 补丁 + `git diff --check` / `git diff --cached --check` + **真实 pre-commit** + `check-docs.mjs`，一次跑全。
- 这些脚本与 `build-stats.json`、`build-excluded.json`、`verify-per-file.json` 都**留在交付副本内**（`_delivery-…/_tools/`），**不入 `docs/`**、不随本目录入库。

---

## 9. 与终稿 addendum 的关系

2026-10-25 的新裁决把「分片报告留在仓外」改为「正式分片/报告全量归档」，因此 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md` 已追加一节 **addendum**（`## 附录 Z. 归档 addendum（2026-10-25 新裁决）`），说明：

1. 该终稿页首「入库注记」里"FA1–FA6 分片报告、`FA-adversarial.md`、两份失败归因报告与全部运行日志**留在仓外**"的表述，就**正式 `.md` 报告**部分已被取代 —— 这些件现已归档于本目录；
2. 归档**不改写**终稿的任何结论、计数（223 条）与等级，`§12` 的"未修复任何一条"等历史结论**照旧成立**；
3. 仍**留在仓外**的只有运行物（日志/补丁/JSON/clone 树/Playwright 产物，§7）。

> 引用建议：讲结论引终稿；讲**过程与取证**引本目录对应文件；两者不要互相代替。

---

## 10. 变更纪律

- 本批**不改生产代码**、不改测试、不改配置、不改工作流；**零 `git add` / `commit` / `push`**（主仓只读，合流由控制器统一执行）。
- 未读任何 `.env` / 密钥文件；未使用任何密钥放行开关。
- 本目录**只追加**：如需补档，新增文件 + 本 README 补一行映射，**不改已归档文件正文**。
