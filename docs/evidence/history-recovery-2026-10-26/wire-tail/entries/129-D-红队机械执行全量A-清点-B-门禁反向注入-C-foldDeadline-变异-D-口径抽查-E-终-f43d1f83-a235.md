> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-235` 的 wire 末条 assistant 文本（消息序号 m=78；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 23:39 → 2026-10-09 23:44（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`e602c3bc04a846a0ce125c84fb72d7e06d921332`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：35636 B / 23382 字符 / 330 行；行指纹覆盖率（本批复算）3.9%（最高单文件 3%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/130-D-YAML与文档口径抽查-M5+M6-红队机械审核结果-f43d1f83-agent-238.md`）
> - 类别：**施工复核**
> - 与既有件关系：同会话同题主件 `agent-239`（本目录 #130）的并行复跑稿：同模型、同提示（提示词相似度 99.9%）、79 ms 内派发；行指纹 Jaccard 0.011，发现清单 12 条 vs 主件 11 条，且含主件未出现的「27 用例 / 29 用例三口径」条目（见 README §6.2）
> - 脱敏：路径 17 / 会话标识 0 / 身份 6 / 密钥 1 / 命名纪律 7
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
全部六项任务已跑完。以下是全量结果。

---

# 前置：环境与一处必须知悉的干扰

- 主仓 `<REPO_ROOT>`，分支 `main`，`git rev-parse HEAD` = `e602c3bc04a846a0ce125c84fb72d7e06d921332`（与任务书 `e602c3b` 一致）。
- **主仓全程零 git 写操作**：只跑过 `status/diff/numstat/grep/ls-files` 与只读的 `node scripts/ci/check-docs.mjs`；结束时 `git status --porcelain | wc -l` 仍 = 21，工作树未被改动。
- ⚠ **`<LOCAL_WORK>/t4-redteam/` 下有并发进程在写**（另一个 agent 或更早的棒次）。实证：我首次进入时已存在 `full.diff`/`untracked/`；随后 `m3-scratch/docs/evidence/redteam-unicode-bypass.md` 出现又被删除，并新出现 `probe-uni.mjs`、`untracked-check-docs.mjs` 等。该文件已 `git add` 到 `m3-scratch` 的索引里，导致**在 `m3-scratch` 里跑基线得到 5/6 FAIL（7 处 ⑤ 命中）**，不是主仓的问题。
- 因此任务 B（M3 反向注入）我改在**私有隔离副本** `<LOCAL_WORK>/t4-redteam/a-scratch/` 执行（同样由主仓 tar 拷贝，排除 `node_modules`/`target`）。隔离副本与主仓**全量 tracked 文件逐字节一致**（md5 manifest 比对：唯一差异是 scratch 里 `scripts/ci/check-docs.mjs` 被我 `git add` 过，内容 `diff -q` 相同）。
- 我的产物集中在 `<LOCAL_WORK>/t4-redteam/a-artifacts/`（扫描输出、控制样本、diff 快照、manifest）；`<LOCAL_WORK>/t4-redteam/full.diff`、`untracked/` 已按任务书重写为同一内容。`a-scratch/`、`m4/` 全部保留未删。**未触碰 `m3-scratch/` 的任何文件。**

---

# 任务 A：清点与残留扫描（M1+M2）

## A.1 清点 — 19 M + 2 ?? 完全对上

`git status --porcelain` 输出 21 行 = 19 `M` + 2 `??`；`git diff HEAD --stat` = `19 files changed, 526 insertions(+), 147 deletions(-)`。两个 `??` 为 `docs/evidence/四层一致率总账.md`、`scripts/ci/check-docs.mjs`（后者 369 行、前者 159 行）。

## A.2 逐文件摘要（1–2 行/文件）

| 文件 | 实际改了什么 | 越界？ |
|---|---|---|
| `.github/workflows/ci.yml` | 头部补口径注记（29 用例、GF6 构造保证、脱敏分口径）；e2e 桩加 `--violate-form-type`、脚本加 `-EnableGf6`、28→29；新增 `docs` job（checkout@v5 + setup-node + 单步跑门禁）与 `scan-history` job（`fetch-depth: 0` + `set +e` 取退出码 + 报告/artifact） | 否 |
| `README.md` | 快速启动去"必须覆盖 8080"警示；环境变量表 `VITE_API_BASE` 8080→8081 并改述"已对齐"；端口一览加"唯一事实源"口径段 + 新增 E2E/桩两行；CI 三 job→五 job 表 + 门禁属性段；E2E 27→29；`backend/config/application.yml`→`backend/src/main/resources/application.yml` | 否（含 2 处**口径改述**，见 F1/F-口径） |
| `backend/pom.xml` | 删 `<caffeine.version>3.1.8</caffeine.version>` 属性与整段 caffeine 依赖（−7 行） | 否 |
| `backend/src/main/resources/application.yml` | 删 `app.server:` 整块（`backend-port: 8081` / `frontend-port: 5173`，−3 行） | 否 |
| `backend/.../GenerativeFormContractTest.java` | 新增 `forbiddenFieldKeysMatchAcrossEnds`（读前端 `form-schema.ts` 与桩 `stub-upstream.mjs` 逐键对账 + 反例守卫）+ `readDeclaration`/`quotedValuesOf` 私有夹具（+63 行） | 否（T4-5） |
| `docs/M2-排期计划.md` | 追加 2 行去向表（历史 blob 278 处命中→历史改写专项；实时帧 `atMs` 覆盖→下批专项卡）+ 新增「M2-T4 闭环注记 v1.7」§a~§d（+31 行，只追加） | 否 |
| `docs/README.md` | 证据分区加"探针源码"行 + 附件约定补句；`27 用例`→`29 用例`；命名纪律节加"门禁已落地"与"CI 脱敏分口径"两条 | 否 |
| `docs/adr/DECISION-REGISTER.md` | 表外追加"复核列填写口径"注记（明文非必填）+ DC-14 行脚注，历史表格行一字未改（`--numstat` = 11/0 ✓ 实测） | 否 |
| `docs/evidence/baseline-combined.md` | §1 主表 50 行的"main 重放结果/一致性"两列由"待重放/待比对"改成引用式结论 + 证据指针；4 行双列标"未对照（无独立证据）"；加头部指针注记（指向四层一致率总账） | 否 |
| `frontend/.env.example` | `VITE_API_BASE` 8080→8081 + 注释同步 | 否 |
| `frontend/src/components/AiPanel/AiPanel.vue` | `resetPendingWatch` 内联三分支回落 → 调 `foldDeadline(...)`；import 加 `foldDeadline` | 否（T4-3 落点②） |
| `frontend/src/stores/ai.ts` | 新增导出纯函数 `foldDeadline`（+31 行，含口径/回落/已知代价注释）；工具挂起帧 deadline 改用 `foldDeadline`；`pending` 装配补 `atMs` | 否（T4-3 落点①） |
| `frontend/src/types/ai.ts` | `PendingToolCall` 加 `atMs?: number` + `expiresAt`/`deadline` 注释改口径 | 否 |
| `frontend/src/types/sse.ts` | `SseFrameBase` 加 `atMs?: number`（含"实时帧无/归档回放帧有"的机理说明）+ 两处 `expiresAt` 注释改口径 | 否 |
| `frontend/vite.config.ts` | `DEFAULT_API_BASE` 8080→8081；`:6` 注释同步；`:7` **G5 注释按裁决改述**（"缺陷是硬编码且不可覆盖，不是默认值对齐"） | 否（但属口径改述） |
| `scripts/README.md` | 28→29 用例；`-EnableGf6` 语义改写（桩模式进主计数）；GF6 行移入主计数表；§2.3 违规模式二分；§2.5 由"手工副本/漂移窗口"改写为"事实源逐值对账" | 否 |
| `scripts/ci/stub-upstream.mjs` | `--violate-form-type` 由启动期全局改**请求级**（`VIOLATION_REQUEST_MARKER = /故意写成\s*html/` + `applyRequestViolation`）；`applyViolation` 只剩 keys 分支；自检③改为读 `GenerativeFormRules.java` 逐值对账（8 组常量 + 5 项上限 + KEY_PATTERN） | 否（T4-4/T4-5） |
| `scripts/githooks/README.md` | scan-history CI 接线"待实施"→"已落地"，写入报告制口径与阻断面归属 | 否 |
| `scripts/verify-e2e.ps1` | 28→29；GF6 块由 `Write-Host` 裸打印改为 `Ok/No/Gf-Skip` 计数 + `Gf-Dump` 留证 + 桩模式未产出即 `No`；`$gf6Frames` 留帧；总结文案补 GF6 | 否 |
| `docs/evidence/四层一致率总账.md`（新） | 双分母（92.0% / 100.0%）、四层归属、S4.2 例外单列、4 行未对照清单、6 条有意差异、可机械重算取数命令 | — |
| `scripts/ci/check-docs.mjs`（新） | 6 条断言的门禁实现，零第三方依赖 | — |

**结论：未发现与批次范围无关的 hunk（无夹带）。** 唯一"超出纯端口替换"的是 3 处口径改述（`vite.config.ts:6-8`、`README.md:114`、`docs/README.md` 命名纪律节），但均在 `docs/M2-排期计划.md:192` 的条目 18(e) 里被显式登记为"按裁决改述"，不构成隐形夹带。

## A.3 M2 残留扫描（主仓只读）

### a) `8080`

| 命中 | 分类 |
|---|---|
| `frontend/src/env.d.ts:7` 「`/api 代理目标（默认 http://localhost:8080）`」 | **漏改（唯一真实残留）** —— 活的源码注释仍写旧默认值 |
| `docs/M2-排期计划.md:62` / `:192` | 事实源口径（刻意说明"`DEFAULT_API_BASE` 8080→8081"） |
| `docs/evidence/DOC-文档治理批-DS-V4-Flash.md:52/90/103` | 历史证据（记录改动前的实况） |
| 其余 20 处（`G1-*`、`S44a`、`baseline-combined.md:17/237`、`V3/V4`…） | 历史证据：**全是 `18080` 端口段**（如 `18080/18290-18299` 的占用纪律），是子串误命中 |
| `spike/sp01d/evidence/sp01d-vd3-coop-cancel.json:18`、`docs/spike/SP-01d-附件-*.txt:18` | `"cancelSignalTsMs": 1791289288080` —— 时间戳里的子串，非端口 |
| `docs/verification-results.md:36` | legacy 参考件（源分支 v1.1；"8080 被占用，按要求未使用"是旧事实陈述，未指向 8080 作配置） |

### b) `app.server|frontend-port|backend-port`

| 命中 | 分类 |
|---|---|
| `README.md:122` | 事实源口径（写"配置树里**不存在**这些死键，已于 M2-T4 整块删除"，且门禁断言 ④ 会对此报红） |
| `docs/M2-排期计划.md:62/192`、`docs/evidence/DOC-文档治理批-*.md:90/91/149` | 口径说明 / 历史证据 |

**`backend/src` 与 `backend/src/main/java` 下 0 命中**（见 d）。`scripts/ci/check-docs.mjs:238` 的 `/app\.server|frontend-port|backend-port/` 是扫描器自身检测正则（口径内）。

### c) `caffeine`

`git grep -in caffeine -- .` 命中 11 处，**全部在 docs**：`docs/M2-排期计划.md:62/192`（口径说明）、`docs/evidence/DOC-文档治理批-*.md:50/90/91/134/136/149/158`、`docs/evidence/S42-P1-*.md:69/98`（历史）、`docs/merge/01-*.md:68`、`docs/merge/03-*.md:81`（他分支评价）、`docs/requirements.md:96`、`docs/technical-design.md:55/392`（legacy 源分支文档）。
- `git grep -n "benmanes" -- backend/` → **0 命中**（backend 源码无 `import com.github.benmanes.caffeine`）。
- `backend/pom.xml` 无 caffeine 依赖与版本属性 —— 被 diff 删除后无残留。**代码/配置面 0 残留。**

### d) 后端运行期是否还读 `app.server.*`

`git grep -nE "@Value.*app\.server|app\.server" -- backend/src/main/java` → **0 命中，exit 1**。确认后端不再有任何绑定。

---

# 任务 B：check-docs 反向注入（M3）

隔离副本 `<LOCAL_WORK>/t4-redteam/a-scratch/`。

**基线**：`node scripts/ci/check-docs.mjs` → 6/6 PASS，`全部通过：6/6 断言绿。` `EXIT=0`（`git ls-files 911 个路径，实扫文本 908 个；豁免 3 个`）。主仓内同样跑一次：6/6 PASS exit 0。

## B.2 Unicode 分隔符绕过（`docs/evidence/redteam-unicode-bypass.md`，20 行，每行一种形态，`git add` 后跑）

门禁规则本体：`NAME_RE = new RegExp('ma'+'in' + '[-_.\\s]+' + 'v'+'2', 'i')`（`check-docs.mjs:266-268`）。

| 行 | 形态码点 | 结果 |
|---|---|---|
| 1 | `<REPO>` U+002D | **抓到** :1 |
| 2 | `<REPO>` U+005F | **抓到** :2 |
| 3 | `<REPO>` U+0020 | **抓到** :3 |
| 4 | `<REPO>` U+002E | **抓到** :4 |
| 5 | U+2011 不换行连字符 | **漏掉** |
| 6 | U+2010 连字符 | **漏掉** |
| 7 | U+2013 en dash | **漏掉** |
| 8 | U+2014 em dash | **漏掉** |
| 9 | U+2212 减号 | **漏掉** |
| 10 | U+200B 零宽空格 | **漏掉** |
| 11 | U+200D ZWJ | **漏掉** |
| 12 | 全角 `<OLD_REPO_DIR⟨全角⟩>` | **漏掉** |
| 13 | U+00A0 NBSP（我补测） | **抓到** :13 |
| 14 | U+3000 表意空格（补测） | **抓到** :14 |
| 15 | U+2000 en quad（补测） | **抓到** :15 |
| 16 | U+FEFF BOM/ZWNBSP（补测） | **抓到** :16 |
| 17 | U+2060 word joiner（补测） | **漏掉** |
| 18 | 制表符 U+0009（补测） | **抓到** :18 |
| 19 | U+0301 组合重音（补测） | **漏掉** |
| 20 | `<REPO>` 大写 | **抓到** :20 |

输出：`[FAIL] ⑤ 命名纪律禁则（git ls-files 全量）（10 处）`，`EXIT=1`。
**关键定性**：JS `\s` 含 `\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff`，故分隔符类**比"ASCII"更宽**（NBSP/表意空格/en quad/BOM 都抓）；但 **10 个 Unicode 连接符/零宽/全角形态全漏**。绕过门槛很低（一次粘贴的破折号即可）。

## B.3 豁免滥用

**(a) 正向（滥用可行）**：`check-docs.mjs:38-42` 的 `NAMING_EXEMPT` 加一行 `'docs/evidence/redteam-evil-exempt.md'`，该文件头部第 3 行写「命名纪律豁免（DOC#5）」，正文写 `<REPO>` → `git add` 后跑门禁：`[PASS] ⑤`、`全部通过：6/6 断言绿。` `EXIT=0`（豁免数 3→4）。即：**一个含禁用字面的文件，只要有人在门禁文件里加一行，就完全不进扫描**；`git diff` 里是 1 行新增（可审计，但零成本）。

**(b) 反向（护栏有效）**：删掉 `spike/sp01ab/scripts/sp01ab-sanitize.mjs` 头部第 3 行的豁免注释 → 跑门禁：`[FAIL] ⑤ … （1 处）`，`spike/sp01ab/scripts/sp01ab-sanitize.mjs => 豁免文件头部缺「命名纪律豁免 / DOC#5」依据注释（防借豁免之名夹带）`，`EXIT=1`。恢复后 ⑥/⑤ 均回到绿。**头部注释校验是真护栏，但它只保证"豁免文件自称有依据"，不限制豁免数量**。

## B.4 `8080` 无冒号逃逸

门禁 ④ 的检测正则是 `if (/:8080\b/.test(text))`（`check-docs.mjs:258`）。

| 注入（README.md） | 结果 |
|---|---|
| `> …后端端口 8080（历史示例，无冒号形态）` | **漏报** —— `[PASS] ④`、6/6 PASS、`EXIT=0` |
| `> …后端端口 http://localhost:8080（带冒号形态）` | **抓到** —— `[FAIL] ④ … README.md:237 => 残留旧默认端口 8080（应为后端 8081 或去掉）`、`EXIT=1` |

即断言 ④ 的 8080 检查**只认 `:8080`**；`端口=8080`、`端口 8080`、`8080 端口` 等写法一律逃逸。（测完还原，README 与主仓 `diff -q` 相同。）

## B.5 断言 ① 占位豁免滥用

`PATH_EXEMPT = /(xxx|placeholder|redacted|[<>])/i`（`check-docs.mjs:88`），逐"命中片段"整体放过。

| 注入（README.md） | 结果 |
|---|---|
| `<DRIVE>/placeholder/secret/path（含 placeholder 字样）` | **放过**（漏报），仅同一行的另一条报红 |
| `<DRIVE>/zprobe/zzz（不含任何豁免词）` | **抓到** —— `[FAIL] ① … README.md:238 => 盘符/本机绝对路径：<DRIVE>/zprobe/zzz` |

即**任何真盘符路径只要片段里含 `placeholder`/`xxx`/`redacted` 就整段免疫**（大小写不敏感、最长 60 字符片段内任意位置）。仅写了 `placeholder` 字样的假路径（我用的 `<DRIVE>/placeholder/secret/path`，非真实路径、无敏感内容）即通过。（测完还原，README 逐字节回到与主仓相同。）

**注入→结果总表**

| 编号 | 注入 | 抓到/漏掉 |
|---|---|---|
| B.2 | ASCII 分隔符 4 形态 + 大写 | 抓到（4+1 处） |
| B.2 | U+2010/2011/2013/2014/2212/200B/200D/2060/0301 + 全角 | **全漏（10 处）** |
| B.2 | NBSP/2000/3000/FEFF/制表符 | 抓到（5 处，`\s` 语义意外覆盖） |
| B.3a | 往豁免清单加一行 + 头部注释 | **漏掉（6/6 绿）** |
| B.3b | 豁免文件删头部注释 | 抓到（1 处，定位精确） |
| B.4 | `8080` 不带冒号 | **漏掉** |
| B.4 | `http://localhost:8080` | 抓到 |
| B.5 | 盘符路径带 placeholder | **漏掉** |
| B.5 | 盘符路径不带豁免词 | 抓到 |

---

# 任务 C：foldDeadline 变异测试与后端权威验证（M4）

## C.1 逐字抽取 + 边界矩阵

`fold.mjs` 逐字抽自主仓 `frontend/src/stores/ai.ts:85-98`（仅去掉文档注释），驱动脚本 `m4/driver.mjs`，`nowMs` 固定 `1000000`。**22 例全部与预期一致（0 偏差）**，完整输出存 `<LOCAL_WORK>/t4-redteam/a-artifacts/m4-fold-deadline.txt`。

| # | 用例 | 返回值 | 语义判断 |
|---|---|---|---|
| 1 | E=1060000,A=1000000 | 1060000 | now+60000；`timeoutSeconds` 被忽略 |
| 2 | E=A | 1000000 | now+0 → 立即到期（fail-closed） |
| 3 | atMs 缺省 | 1060000 | 回落 E 绝对值（实时帧路径） |
| 4 | atMs=NaN | 1060000 | `isFinite` 假 → 回落 E 绝对值 |
| 5 | expiresAt=NaN,timeout=300 | 1300000 | 走 timeoutSeconds（`typeof NaN==='number'` 但 isFinite 拦下） |
| 6 | expiresAt=NaN 且 timeout 缺 | null | 不武装计时器 |
| 7 | A=-5000,E=1000 | **1006000** | now+6000 → **负 atMs 把剩余时长膨胀为 E+5000**（膨胀面） |
| 8 | A=2000000,E=1060000 | 1060000 | `E>=A` 假 → 回落 E 绝对值（**不产生负倒计时**） |
| 9 | E=1e15,A=1e9 | 999999001000000 | `new Date(...)` = 公元 33658 年，Date 仍可表示 |
| 10 | E=MAX_SAFE_INTEGER,A=1e9 | 9007198255740991 | `isSafeInteger=true` 但 **> Date 上限 8.64e15 ⇒ `Invalid Date`** |
| 11 | E=0,atMs 缺 | 0 | 返回字面 0（1970 绝对时刻）⇒ 本地判立即过期 |
| 12 | timeoutSeconds=0 | null | falsy → null（非 now+0） |
| 13 | timeoutSeconds=-30 | 970000 | now−30000（fail-closed） |
| 14 | E=Infinity,timeout=300 | 1300000 | **isFinite 假 ⇒ 静默降级到 timeoutSeconds**（不是回落 E） |
| 15 | atMs=Infinity | 1060000 | 回落 E 绝对值 |
| 16 | atMs='1000000'（字符串） | 1060000 | typeof 守护生效（防类型漂移/注入） |
| 17 | expiresAt='1060000'（字符串） | 1300000 | 字符串绝对值被静默忽略 → 回落 timeout |
| 18 | E=A=0 | 1000000 | now+0 |
| 19 | E=-1,A=-5000 | 1004999 | 过期负基准仍折算 ⇒ 未来 4999ms |
| 20 | 仅 timeout=300 | 1300000 | 既有兼容口径 |
| 21 | 仅 timeout=0 | null | — |
| 22 | A=E+1 | 1059999 | `E>=A` 假 → 回落 E；按服务端钟取，与本地 now 差 59999ms |

膨胀量（E=1000）：A=-5000 → **+6000ms**；A=-1000 → +2000ms；A=0 → +1000ms；A=1e9 → −999000ms。
**结论**：函数对缺参/非法参是 fail-closed（回落或 null），不抛异常；唯二膨胀面是 ① `atMs` 落后→按"帧落档时剩余时长"重新起算（注释已登记为已知代价）、② 负 `atMs` 线性膨胀。极端 `expiresAt` 会让本地 deadline 落到 `Invalid Date`（不影响后端权威判定）。

## C.2 前端既有测试面 —— 新纯函数零覆盖

- `frontend/package.json` 的 `scripts` = `{dev, build, typecheck, preview}`，**无 `test`**；`devDependencies` 里过滤 `vitest|jest|test|cypress|playwright|jsdom|happy-dom` = **空数组**。
- `find frontend/src -name "*.spec.*" -o -name "*.test.*"` = **0 个文件**；仓库内也没有 vitest/jest 配置（`frontend/vite.config.ts`、`.github/workflows/ci.yml` 均无引用）。
- ⇒ **`foldDeadline`（4 分支、3 级回落、2 个数值边界）无任何单测，且项目当前没有可承载单测的基建**；C.1 的 22 例只能由外部脚本（`<LOCAL_WORK>/t4-redteam/m4/`）承担，不进 CI。这是本批"新纯函数无单测"红队关注点的**坐实**。

## C.3 后端权威验证（J4 判据）

### a) 实时帧不带 `atMs` 的实证链（结论成立）

- `SseChatEmitter#emit`（`run/SseChatEmitter.java:654`）：`:678` `frame.put("seq", nextSeq())` → **`:684` `payloadBytes = FrameSizeLimiter.apply(frame, this.objectMapper, this.maxFrameBytes)`（此处完成序列化）** → `:690` `this.store.appendEvent(this.runId, frame)` → `:697` `new String(payloadBytes, UTF_8)` 才是订阅者收到的负载。
- `RunStore#appendEvent`（`run/RunStore.java:567`）：`:576` `frame.putIfAbsent("atMs", System.currentTimeMillis())` → `:578` `redis.opsForList().rightPush(eventsKey, mapper.writeValueAsString(frame))`。
- `FrameSizeLimiter.apply`（`run/FrameSizeLimiter.java`）内部 `serialize(frame, mapper)`，只在超限时改可截字段，**不添加 `atMs`**。
- ⇒ **同一个 Map 实例先被序列化（:684，无 atMs）、后被补键并二次序列化落档（:576/:578，有 atMs）**，所以**实时帧恒不带 `atMs`，只有归档/回放帧带**。`frontend/src/types/sse.ts:90-95` 的注释与实现一致；且因是 `putIfAbsent` + 前端按可选字段处理，将来若调序也不会坏（fail-safe）。

### b) 超时权威在后端（结论成立，另发现边界竞态）

- 权威计时：`ConfirmGate#await`（`gate/ConfirmGate.java:189-253`）`:204` `deadline = startedAt + timeoutSeconds*1000L`，`:222-224` `remain <= 0 → expire(...)`。`:234` 每 2s 发心跳帧（`HEARTBEAT_SECONDS=2`）。
- 结局与状态推进：`ConfirmGate#expire`（`:286-312`）→ `:294-297` 置 `status=TIMEOUT`、`reason=CONFIRM_TIMEOUT/FRONTEND_TIMEOUT`、`executed=false` → `:301` `store.putPending(...)` 落 Redis（重启后可读）→ `:306` `out.confirmDecision(fresh,"timeout", ...)`（超时结局帧与人工决策同形，`:302-303` 注释明示）/ `:309` `frontendToolResult(ok=false)`。挂起线程据此返回 `TIMEOUT` 条目，`SpToolCallingManager` 按"未执行"终止该工具调用并推进本轮。
- **晚提交会被拒**：`submitDecision`（`:390-417`）`:391` 读条目 → `:392-393` 为空 → `NOT_FOUND`；`:395-397` 状态非 `PENDING` → **`DUPLICATE`（不改状态、不发帧、不唤醒）**。超时后状态已是 `TIMEOUT` ⇒ 晚到的 approve/reject 一律被幂等拒绝；`submitFrontendResult`（`:442-471`）同构（`:445-450`）。
- **关键口径**：接受与否**只看状态，从不回读 `expiresAt`** ⇒ 客户端钟漂移完全不影响服务端判定（对 J4 有利）；`expiresAt` 纯属给客户端倒计时用的提示。
- ⚠ **发现（低-中，边界 TOCTOU）**：`submitDecision` 的 `PENDING` 判定在 `:391`（`submitLock` **外**）读，写落在 `:411-415` 的锁内；而 `expire()`（`:287` 读、`:301` 写）**完全在锁外**，`RunStore#putPending`（`:371-380`）是普通 Redis hash put、无版本/CAS。因此在 deadline 前后微秒级窗口内两种交错都可能：状态被后写者覆盖（TIMEOUT↔APPROVED），出现"回执帧说已批准 / 实际按超时收敛"或反之的**帧与终态不一致**。后果有界（工具不会误执行——挂起线程按 `expire()` 返回的 TIMEOUT 走未执行语义；用户会看到一次矛盾的决策帧），不是安全漏洞，但属权威判据的未加锁边界。

### c) `RunStore.java:451` 是什么记录

`:451` 位于 `public void appendLedger(String runId, PendingToolCall pending, String resultText)`（`:449-466`）——写的是**工具执行台账**（`ai:ledger:<runId>` 的 list，字段 `atMs/at/toolCallId/name/kind/resultText`），供 `ledgerCountSince`（`:513-522`）判"本轮是否有真实副作用"（韧性棒重试口径）、以及按名统计（`:500-505`）。
与帧的 `atMs`（`:576`）**不是同一对象**：一个是台账记录、一个是 SSE 归档帧。但**同源时钟**——两处都是 `System.currentTimeMillis()`（`:451` 与 `:576`），且帧的 `atMs` 用途是"本轮最近活动时刻"（`:652-667` 的 `lastActivity`，供僵尸判据/续跑）。结论：时钟同源，语义不同，无冲突。

---

# 任务 D：YAML 与文档口径抽查（M5+M6）

## D.1 `ci.yml` 合法性与五 job 结构

`yaml.safe_load` **解析通过**（`python -c` 实测；注意 `on:` 被 YAML 1.1 解析成布尔键 `True`，内容为 `{"push":{"branches":["main"]},"pull_request":{"branches":["main"]}}`）。`permissions: {contents: read}`。

| job | name | needs | timeout | fetch-depth | 触发 |
|---|---|---|---|---|---|
| `backend` | 后端编译与单测 | — | 20 | 默认 | push(main)+PR(main) |
| `frontend` | 前端类型检查与构建 | — | 20 | 默认 | 同上 |
| `e2e` | E2E 全流程（29 用例 · 桩上游 + redis） | **`backend`** ✓ | 25 | 默认 | 同上 |
| `docs` | 文档一致性门禁（check-docs）｜阻断 | **无** ✓ | 10 | 默认 | 同上 |
| `scan-history` | 全历史脱敏扫描（报告制 · 不计门禁） | **无** ✓ | 10 | **0** ✓ | 同上 |

- `docs` 与 `scan-history` **互相独立、均无 needs** ✓；`e2e needs backend` ✓；`scan-history` 的 `fetch-depth: 0` ✓（`.github/workflows/ci.yml:295`）。
- 两个新 job **无 job 级 `if:`**（只有 3 处 step 级 `if: always()`：e2e 清理/归档、scan-history 归档），故 `push(main)` 与 `pull_request(main)` 两根触发线都覆盖 ✓。
- `docs` job 无需装依赖（脚本只 import `child_process/fs/path/url`）✓；`scan-history` 的报告落在 `backend/target/`，`.gitignore:2` 有 `backend/target/` ✓；`sed -n '/命中路径清单/,/^$/p'` 与 `scripts/githooks/scan-history.sh:142-143`（标题行紧跟清单、其后才空行）**匹配正确** ✓；`set +e` + 显式 `exit 1`（仅 code∉{0,1}）与注释口径一致 ✓。
- action 版本全仓统一 `@v5`：checkout×5、setup-java×2、setup-node×3、upload-artifact×2。
- **与注释口径不符处**：`docs/M2-排期计划.md:192`（条目 18(b)）写 `scan-history` 接入 CI「…push+PR、**失败即红**」，与 `ci.yml:274-284` 的报告制实现相悖；同文件 `:177` 已声明该处属"T4 施工时点接线口径留痕、以本条为准"，但正文未加内联指针（读者顺序阅读会先看到"失败即红"）。

## D.2 文档真实性

**a) README 端口一览 vs 事实源 —— 逐值相等**

| 表行 | 表值 | 事实源实测 | 一致 |
|---|---|---|---|
| 后端 | 8081 | `backend/src/main/resources/application.yml:2 server.port: 8081` | ✓ |
| 前端 dev server | 5200 | `frontend/vite.config.ts:11 DEFAULT_DEV_PORT = 5200` | ✓ |
| E2E 后端 | 18330 | `scripts/verify-e2e.ps1:52 [string]$Base = 'http://127.0.0.1:18330'` | ✓ |
| 上游桩（E2E/CI） | 18399 | `scripts/ci/stub-upstream.mjs:57 PORT = Number(arg('port', '18399'))` | ✓ |
| H2 Console | `http://localhost:8081/h2-console` | `application.yml:28 path: /h2-console`，端口与 `server.port` 同值 | ✓ |

`frontend/.env.example:4 VITE_API_BASE=http://localhost:8081` 与 `:7 VITE_DEV_PORT=5200`，与 `vite.config.ts:10 DEFAULT_API_BASE='http://localhost:8081'`、`:11 DEFAULT_DEV_PORT=5200` **一致** ✓。

**b) `docs/evidence/四层一致率总账.md` 通读 + 独立复核**

- 口径 A（分母 50）：分子 46 = `一致（显式指名）` 7 + `一致（引用式）` 39 → **92.0%**；分母 = `baseline-combined.md` §1 数据行数。
- 口径 B（分母 46 = 50 − 4 未对照）：46/46 = **100.0%**。
- 独立复核（命令实测）：`grep -c '^| B[1-4]\.[0-9]'` = **50**；`待重放` = **0**、`待比对` = **0**；`一致（显式指名）` = **7**；`一致（引用式` = **39**；`未对照（无独立证据） | 未对照（无独立证据） |`（双列成对）= **4**，行号为 `B1.10/B1.11/B1.12/B3.3`，**与总账 §4 清单完全一致**；`grep -o '未对照（无独立证据）' | wc -l` = **9** = 8（4 行×2 列）+ 1（头部注记），**与总账 §6 的脚注吻合**。分层：B1=14、B2=20、B3=7、B4=9 行 → S4.3 = 34 行、S4.1 = 7 行、S4.2 = 9 行，与小计的 41 行 / 37 一致（37+9=46 ✓，41+9=50 ✓，41 层小计 37/41 = 90.2% ✓）。引用的 **19 个证据文件全部存在** ✓。
- 唯一瑕疵（低）：§6 取数命令 ③ 标注「期望 7 / 39 / 2」，其中 `一致（有意差异）` 的 2 行（`B1.3`、`B2.10`）**是 39 个子集**（这两行"main 重放结果"列写的就是「一致（引用式；有意差异…）」），三项相加 48 ≠ 46；反算只用了 7+39，**结论不错**，但行内没写"2 ⊂ 39"，读者易误加。

**c) `docs/M2-排期计划.md` 新增行核验（两条最可验证的）**

1. `:178`「`atMs` 由 `RunStore#appendEvent` 落档时 `putIfAbsent` 补，而 `SseChatEmitter#emit` 的 `payloadBytes` **早于**该落档序列化 ⇒ 实时帧不含 `atMs`」 —— **与代码一致**（`SseChatEmitter.java:684` 在 `:690` 之前），也就与 `frontend/src/types/sse.ts:90-95` 的注释同向。✓
2. `:189`「GF6 **进主计数（28→29，实读）**」—— **与 `scripts/verify-e2e.ps1` 实现一致**：GF6 块改用 `Ok`/`No`/`Gf-Skip`（`:1917-1936`），而 `Ok`(`:73-77`)/`No`(`:78-82`) 确实分别 `$script:passed++`/`$script:failed++`，总结行读的是同一计数器（`:2330` 附近）；`Gf-Skip`(`:1377-1382`) 只增 `gfskip`（GF6 不计入 `gfskip14` 硬底线）✓。算术自洽：22(TC1–22) + 1(TC23) + 5(GF1–5) + 1(GF6) = **29**。⚠ 我**未能实跑** E2E 复核那句 `PASS=29 FAIL=0 GFSKIP=0 exit 0`（需后端 + Redis + 桩全栈），仅验到机制层一致。
3. 与同文件自相矛盾（见 F7）：`:188` 的 §a 条目 14 写「**BLOCKED →【待裁决】**…**本棒未改动任何前端文件**」，而 `:178` 写「本批已按卡片落点①②在 `frontend/src/stores/ai.ts` 折算处与 `AiPanel.vue#resetPendingWatch` 落地」，且工作树 diff **确实改了这两个前端文件**；`:200`（§c）仍写"条目 14：去向 = 待裁决"，而 `:178` 已声明"取代 §c 中该旧去向"。

**d) `DECISION-REGISTER.md` 与 `baseline-combined.md`**

- `DECISION-REGISTER.md`：仅追加表外注记（复核列**非必填**）与 DC-14 行脚注（`Kimi K3（裁决）`属裁决者、不作范例），历史表格行一字未改 —— `git diff --numstat HEAD` = **`11 0`**，与 `docs/M2-排期计划.md:187` 的声明"11 增 / 0 删"**逐字吻合** ✓。无自相矛盾。
- `baseline-combined.md`：50 行两列由"待重放/待比对"改为引用式结论 + 证据指针，4 行双列"未对照"，加头部指针注记指向总账；**无"待重放/待比对"残留（各 0）**，与总账数量口径一致 ✓。数字声明（50/4/7/39/46）与 diff 其它部分（排期 §a 条目 12「50 行，缺证行 4 行双列标」）**一致，无冲突**。
- 另发现口径并存（低）：历史 blob 命中数在 `docs/M2-排期计划.md:177`/`ci.yml:275` 记"**278 处 PATH 命中**（CI 模拟口径），含 **9 个仅存于历史的路径**"，而同文件 `:202`（§d）记"本地实跑**命中 302 处 / 40 个历史路径**"。两者分母/规则面不同（PATH-only vs 全规则；40 路径总数 vs 9 个仅存于历史），**无换算说明**，并列出现易被读成矛盾。

---

# 任务 E：改动面终扫（M7）

对象 = `git diff HEAD` 的全部新增行（剔除 `diff --git/index/---/+++/@@` 等信封行与删除行）**+ 两个未跟踪新文件全文**，共 **21 个文件 / 1056 行**。快照：`<LOCAL_WORK>/t4-redteam/full.diff`（1242 行）、`<LOCAL_WORK>/t4-redteam/untracked/`、`a-artifacts/a-full.diff`、`a-artifacts/a-added-lines.txt`。

| 扫描项 | 结果 |
|---|---|
| 1a 命名纪律 ASCII `/main[-_.\s]+v2/i` | **0 命中** ✓（`check-docs.mjs` 用 `'ma'+'in'` 拼接构造，字面量确不在文本里） |
| 1b Unicode 变体（`main[\u2010-\u2015\u2212\u200b-\u200f\ufeff\u2060]+v2`） | **0 命中** |
| 1c 全角 `ｍａｉｎ.*ｖ２` | **0 命中** |
| 1d 豁免面（spike 三脚本匹配源正则） | diff 中**未改动这三脚本** → 无新增行，无从命中 |
| 2a 盘符路径（复用 `check-docs` ① 的两条正则） | **0 命中** |
| 2b `/\/[cdefgh]\//` | **0 命中** |
| 2c 宽松盘符 | 1 处：`check-docs.mjs:82` 注释里的示例 `「（<DRIVE>/…」` —— 检测正则自身的说明文字（含省略号、非真实路径），**口径内** |
| 3 SECRET 全形态（sk-/ghp_/gho_/ghs_/AKIA/ASIA/AIza/xox*/hf_/LTAI/AKID/glpat-/npm_/sk_live_/rk_live_/Bearer 长串/PRIVATE KEY/eyJ JWT） | **逐项 0 命中** ✓ |
| 4a 个人用户名 `<USER>` / `<USER>` / `<USER>`（`git config user.name` = **`<USER>`**）/ `<DRIVE>\Users` / `/Users/` | **全部 0 命中** ✓ |
| 4b 内网 IP `192.168.*`、`10.*.*.*` | **0 命中** ✓（`127.0.0.1` 未计） |
| 4c `18080` | 0 命中（历史端口段只在旧证据文档里） |

**用项目自己的规则引擎复跑（含控制样本）**：`. scripts/githooks/scan-lib.sh; scan_init; … scan_engine_blob | scan_format`
- 正控 `control.txt`（含 `<REPO_ROOT>/backend`、`<USER>`、`***`）→ 三条规则**全部触发**（`[SECRET] sk-***(len=24)`、`[PATH] …`、`[USER] <USER>`）——证明引擎有效。
- 对 1056 行新增内容 → **0 命中**。
- ⚠ 方法学提醒：该引擎默认模式要求输入是 `文件\t行号\t正文` 的**制表符**格式，直接喂纯文本会静默 0 命中（我第一次就踩了这个假阴性，已用正控纠正）。命令与输出存 `a-artifacts/a-scan-added.txt`。

**结论：改动面 0 夹带 —— 无命名纪律变体、无盘符/本机路径、无密钥形态、无个人用户名/内网 IP。**（`docs/evidence/` 历史证据文档的旧内容按要求不计。）

---

# 发现清单（按建议处置优先级）

| # | 级别 | 发现 | 证据 |
|---|---|---|---|
| **F1** | 中 | **唯一真实"漏改"**：`frontend/src/env.d.ts:7` 仍写「`/api 代理目标（默认 http://localhost:8080）`」，实际默认已 8081。门禁断言 ④ 只扫 4 个 NAV_DOCS，**覆盖不到任何源码文件**，故它不会被门禁拦住 | `git grep -n 8080 -- backend/* frontend/* scripts/*` 只此一处；`check-docs.mjs:30-35` + `:254-260` |
| **F2** | 中 | 断言 ④ 的 8080 检测只认 `:8080`，**不带冒号即漏**（实测 README 注入"后端端口 8080"→ 6/6 PASS） | `check-docs.mjs:258`；B.4 输出 |
| **F3** | 中 | 命名纪律 ⑤ 只认 ASCII 分隔符 `[-_.\s]+`：U+2010/2011/2013/2014/2212/200B/200D/2060/0301 与全角形态**全漏**（10/20）。`\s` 意外覆盖 NBSP/2000/3000/FEFF/制表符 | `check-docs.mjs:266-268`；B.2 表 |
| **F4** | 中 | 断言 ① 的 `PATH_EXEMPT` 按"片段含 placeholder/xxx/redacted"整段免疫，真盘符路径可**一句话绕过** | `check-docs.mjs:84-88`；B.5 输出 |
| **F5** | 中 | ⑤ 的豁免是**改一行门禁文件即生效**：往 `NAMING_EXEMPT` 加一项，含 `<REPO>` 的文件完全不进扫描（实测 6/6 绿）。唯一护栏是豁免文件头 12 行注释（实测删除即红） | `check-docs.mjs:38-42`、`:271-280`；B.3a/b |
| **F6** | 中 | `foldDeadline` 新纯函数**零单测**，且前端**无测试基建**（无 `test` 脚本、无 vitest/jest、0 个 spec/test 文件）——22 条边界只能由外部脚本覆盖，不进 CI | C.1/C.2 |
| **F7** | 低-中 | `docs/M2-排期计划.md:188`（§a 条目 14）「BLOCKED…**本棒未改动任何前端文件**」与同文件 `:178`「本批已落地 `stores/ai.ts`/`AiPanel.vue`」及实际 diff **自相矛盾**；`:200`（§c）旧去向已被声明取代但未加内联指针 | 引文 + `git diff HEAD -- frontend/src/stores/ai.ts` |
| **F8** | 低-中 | `ConfirmGate` 超时边界 **TOCTOU**：`submitDecision:391` 的 PENDING 判定在锁外，`expire:287/301` 读写全在锁外，`RunStore.putPending:371` 无 CAS → 恰卡在 deadline 的提交可能把 `TIMEOUT` 覆盖成 `APPROVED`（或反之），出现帧/终态不一致。后果有界（工具不误执行） | C.3b |
| **F9** | 低 | 口径残留：`docs/M2-排期计划.md:192` 条目 18(b) 仍写 `scan-history`「失败即红」（实现是报告制，`:177` 已声明但其后正文无指针）；同文件 `:25`/`:135` 仍写"27 用例"，全文 27/28/29 三个数并存（可归因于"只追加不回改"纪律） | D.1/D.2c |
| **F10** | 低 | 历史命中数三口径并存无换算：`278 处 PATH（CI 模拟）/ 9 个仅存于历史路径`（`:177`、`ci.yml:275`）vs `302 处 / 40 个历史路径`（`:202`） | D.2d |
| **F11** | 低 | 四层一致率总账 §6 取数命令 ③「期望 7 / 39 / 2」，`一致（有意差异）` 2 行是 39 的子集，相加 48 ≠ 46；反算只用 7+39，结论无误但缺"2 ⊂ 39"说明 | 总账 §1 与 §6；`grep -c` 实测 |
| **F12** | 提示 | ⑤ 的作用域是 `git ls-files`（**已跟踪**文件）——两个新增文件在 `git add` 之前不在门禁覆盖内（`pre-commit` 覆盖暂存区）。本批两文件实测干净，无实际风险 | `check-docs.mjs:283-301`；主仓跑门禁时 `git ls-files 911`（未含两个新文件） |

**J4/交付要注意的两点**：
1. `scripts/ci/check-docs.mjs` 与 `docs/evidence/四层一致率总账.md` **必须 `git add` 入库**，否则 CI 的 `docs` job 会以 `MODULE_NOT_FOUND` 红。
2. 主仓实测 `node scripts/ci/check-docs.mjs` = **6/6 PASS, exit 0**，`git status` 仍 21 项 —— 交付物本身当前是绿的（隔离副本与主仓全量 tracked 文件 md5 逐字节一致）。

**未验证项（如实声明）**：`docs/M2-排期计划.md:189` 的 `PASS=29 FAIL=0 GFSKIP=0 exit 0` 实跑结论我只验到**机制层**（`Ok/No` 计数接线、桩 `--violate-form-type` 请求级触发正则、`-EnableGf6` 已进 CI），未实跑全栈 E2E（需后端 + Redis + 桩 + 真/桩模型链路）；后端"357→358 全量测试"亦未实跑（`backend/target/surefire-reports` 现存 55 个 XML 属更早时点，不能作为本批计数证据），但 diff 只加了 1 个 `@Test` 方法，与 +1 自洽。
