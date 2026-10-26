# 历史补档：提交官总报告（第 1–22 期超集，2026-10-10 ~ 10-12 全景）

- **归档来源（已脱敏）**：`<LOCAL_WORK>/commit-gov/提交报告.md`（源 3718 行 / 385,956 B）· 源件仅仓外，未移动、未改写
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-10 ~ 2026-10-12 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话（体内代号）**：<KIMI_SESSION_A> / <KIMI_SESSION_B>（缩写；模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：3718 行 / 385,956 B
- **与仓内旧版的关系**：仓内 `docs/evidence/补档-COMMITGOV-提交报告.md`（1022 行）是本件的**真子集**（= 本件前 1022 行，实测 diff 差异 64 行、全部为旧版额外脱敏：页首来源注 / `<USER>` / `<HOST>` / `<LAN_IP>` / `<SCRATCH>`）；本件新增第 5–22 期（docs 合并批、M1 签署闭环、批 B / R-96、对话时序包批 1/2/3③a/3'③b/4④、轮询治理一期、手动刷新批、R-149、issue#7、R-137 终态守卫、看板落库等）。**旧版未被覆盖、未被修改**。

---

# ai-example 合流项目 · 提交官报告

- 提交官：DeepSeek V4 Flash（提交官子智能体）
- 日期：2026-10-10
- 主仓库：`<REPO_ROOT>`（分支 `main`，远端 `<REPO-URL>`）
- 起点 HEAD：`d3020ee`｜终点 HEAD：`6ee96c3`
- 红队材料：`<LOCAL_WORK>\redteam-ui-pack\`（锚点 `bundle.sha256` = `1e63e0d5c4349f4554ed57d809f259e807b675d13d3890ef24dc705c32f967d1`）
- 平台：Windows + Git Bash｜`core.autocrlf=true`｜`core.hooksPath=scripts/githooks`
- 断言标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出

---

## 一、提交前硬性核验（第一步）

### 1.1 工作树状态比对

```
$ git -c core.quotepath=false status --porcelain    # 【实测】
```

- 现态 = 恰 18 项（16 ` M` + 2 `??`）。
- 与 `<LOCAL_WORK>\redteam-ui-pack\status-snapshot.txt` 的 porcelain 段（第 7–24 行，18 行）做 `diff`：**逐行完全一致**（`STATUS_IDENTICAL`）。
- 结论：**无工作树漂移**【实测】。

### 1.2 锚点校验

```
$ cd <LOCAL_WORK>/redteam-ui-pack && sha256sum -c bundle.sha256
bundle.patch: OK                                # 【实测】退出码 0
```

### 1.3 `diff-tracked.patch` 与现态 `git diff` 逐字节比对

```
$ git -c core.quotepath=false --no-pager diff -U3 --output=now4.patch
$ cmp diff-tracked.patch now4.patch   → 逐字节相同      # 【实测】
```

- 两者同为 **65535 字节**，且完全一致。为排除"64KB 截断巧合"，另做两项反证：
  - **非截断反证**：同一管道下 `git diff -U10` = 95099 字节、`-U20` = 127699 字节，均越过 65535 ⇒ **不存在 65535 上限**【实测】。
  - **完整性反证**：解析该 patch，逐文件 `+/-` 计数合计 = **+201 / -200**，与 `git diff --shortstat`（`16 files changed, 201 insertions(+), 200 deletions(-)`）**完全吻合**，且末行以换行收尾 ⇒ patch 完整、未截断【实测】。
- 结论：自锚点钉死后 **工作树零漂移**【实测】。

### 1.4 逐文件归属核对（18 项）

对 `file-manifest.txt` 的 18 行逐项核验 5 个字段（sha256 / bytes / lines / eol / eof-newline）：

```
MANIFEST_OK=18 MANIFEST_FAIL=0        # 【实测】单项 sha256 核对与全字段核对均 18/18 通过
```

- EOL 实测：仅 `TaskController.java` 为 CRLF（170 处 CRLF / 0 处孤立 LF），其余 17 项为 LF，**无混合行尾**，与清单一致【实测】。
- 文件集一致：`git status` 18 项 = 清单 18 项，无多无少【实测】。
- 附带核对：HEAD = `d3020ee2d245c3c38a803c320f0ff90248d5d270`、分支 `main`、远端 `<USER>/ai-example`【实测】。

**第一步结论：全部通过，无 BLOCKER，未停手。**

---

## 二、脱敏终扫（第二步）

### 2.1 植入对照先行（证明扫描器活着）

自建独立扫描器 `<LOCAL_WORK>\commit-gov\desense-scan.mjs`（与仓库门禁相互独立），覆盖四模式：
① 盘符绝对路径 ② 本机身份（用户名/主机名/用户目录）③ 内网或本机 IP（RFC1918+环回+链路本地）④ 命名违禁（与门禁断言⑤ 同规则：NFKC 折叠 + 隐形字符双态 + 去分隔符连写 + 词首守卫）。

```
$ node desense-scan.mjs <repo> seed
正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记；exit=0     # 【实测】
```

- 正向种子含：`<DRIVE>/…` / `<DRIVE>/…` / `<DRIVE>/…` / `<DRIVE>/…`、用户名与用户目录、`10.x` / `192.168.x` / `172.16.x` / `127.0.0.1`、命名违禁的**全部拆形形态**（连字符/下划线/空格/点/零分隔连写/全角连字符 U+FF0D/粘贴破折号 U+2011/零宽 U+200B/软连字符 U+00AD/全大写/混合大小写）——**23/23 全命中**。
- 负对照：裸前缀、裸后缀、域名尾部、词尾含后缀、前字紧邻、`E:temp`、公网 `8.8.8.8`、非私网 `192.169.x` / `172.32.x` / `11.x`、近似用户名 `Obbi`、短词根 `Leno`——**12/12 未误伤**。
- 向安全侧保守过匹配（已登记知悉，不构成缺陷）：形似 IP 的越界段 `192.168.1.999`、以 `10.`/`127.` 开头的点分版本号 `v10.2.3`。

**门禁自带植入对照（权威判定之一）**：

```
$ node scripts/ci/check-docs.mjs
（规则自检：连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤）
全部通过：6/6 断言绿。                                                      # 【实测】退出码 0
```

### 2.2 实盘扫描（18 个文件，含 2 个未跟踪新文件）

```
$ node desense-scan.mjs <repo> realscan < 18 项清单
  [命中] docs/evidence/S44a-前端基座验证.md:190 内网/本机 IP :: 127.0.0.1
  [命中] docs/evidence/S44b-五页视图验证.md:18  内网/本机 IP :: 127.0.0.1
实盘：18 个文件，命中 2 处                                                 # 【实测】
```

**按豁免登记放过（非误报、非新引入）：**

| 命中 | 判定依据 |
|---|---|
| `S44a:190`、`S44b:18` 的 `127.0.0.1:10086` | 均为**环回地址**（非内网/局域网地址），且是**本批未触碰的上下文行**（本批新增行中含 `127.0.0.1` 的行数实测 = **0**）；HEAD 版两文件各已含 1 处（`git show HEAD:…` 实测），属历史证据文档既存留痕；裁决 7 明确 `docs/evidence/` 历史证据文档的端口/历史记录不回改；全仓 `127.0.0.1` 为既有常态（`git grep` 命中 152 个已跟踪文件）【实测】 |
| `scripts/ci/check-docs.mjs:89` 的 `<DRIVE>/…` | **已知豁免段**（断言① 正则示意串注释）。该行在本扫描器下**未触发**（路径正则要求盘符后至少 1 个路径字符，`…` 不在字符类内），登记为"豁免且未命中"【实测】 |
| 3 个 spike 脱敏脚本的匹配源正则 | 仅出现在全仓基线扫描中（6 处），与门禁 `NAMING_EXEMPT` 豁免面一致，**不在本批 18 项内**【实测】 |

**四项模式在 18 项中的最终判定：**

| 模式 | 18 项内结果 |
|---|---|
| ① 盘符绝对路径 | **0 处**【实测】 |
| ② 本机身份（用户名 `<USER>` / 主机名 `<HOST>` / 用户目录） | **0 处**【实测】 |
| ③ 内网/本机 IP | 2 处，均为环回且为既存上下文行，按豁免放过（见上表）【实测】 |
| ④ 命名违禁 | **0 处**——门禁断言⑤ 全绿（918 路径全量扫：916 已跟踪 + 2 新入库）+ 独立扫描器同规则复扫 18 项（含未跟踪 2 项）亦 0 命中【实测】 |

**全仓基线（旁证上下文）**：916 个已跟踪文件全扫 = 2551 处（主机名 1075 + IP 1475 + 用户名形态 1），**盘符路径 0 处、命名违禁 0 处（豁免面外）**；其中用户名形态那 1 处经逐行核对为 GitHub 组织名 `<USER>`（`docs/merge/01-分支评价报告.md`，非本批文件）。基线状态与本批无关，仅用于佐证"本批 18 项在既有噪声之上没有新增泄露"【实测】。

### 2.3 扫描器自身的两处修正（防"扫描器假活"）

- 首轮"用户名"按宽松子串匹配，全仓出现 4 处 `<USER_FRAGMENT>` 命中，逐行核对**全部为 `yarn.lock` 中 `tinyglobby` 的假阳性** ⇒ 改**词边界**判定后复跑，种子/对照仍全绿【实测】。
- 首轮全仓基线清单误用未加 `core.quotepath=false` 的 `git ls-files`，非 ASCII 路径被八进制转义 ⇒ 相关文件被 `SKIP`（未真正扫描）⇒ 改用 `-c core.quotepath=false` 重取清单后复跑，`SKIP` 归零【实测】。

**第二步结论：18 个文件脱敏终扫通过，无新增泄露；2 处环回命中按豁免登记。**

---

## 三、细分提交（第三步）

逐笔均以**显式逐文件 `git add <具体路径>`** 暂存（全程未用 `git add -A` / `git add .`），暂存后先核对清单再 `git commit`；每笔 pre-commit 钩子均**实跑通过**（未使用 `--no-verify`、未使用 `AI_SECRETS_ALLOW=1`）。

| # | commit | 标题 | 文件数 | 增删 |
|---|---|---|---|---|
| 1 | `a98f6d5` | feat(frontend): 任务中心标题即打开、拆除详情抽屉、精简顶部菜单 | 10 | +22 / -159 |
| 2 | `c0fada5` | refactor(frontend): 删除无调用方的孤儿导出并回灌需求文档注记 | 2 | +4 / -17 |
| 3 | `0b273fb` | fix(ci): 命名门禁扩展三态检测与自检防掏空 | 2 | +172 / -23 |
| 4 | `38139c9` | docs(research): 入库步骤条交互与对话时序混排两份业界调研 | 3 | +437 / -0 |
| 5 | `6ee96c3` | docs(plan): 登记 AI 发布冲突提示落点重建立项（R-94） | 1 | +2 / -1 |

全文 hash：

1. `a98f6d559019a44c8bac30eaad7a0595b1c7fd10`
2. `c0fada560d14a8f574cc806e83d4e41635f10dc3`
3. `0b273fb815b6d492f10ce9f2a300eee94e38d439`
4. `38139c9b9a53690502767da3c1917318eac9651d`
5. `6ee96c331d89349af69db4b0d4b1246723f24db3`

### 逐笔文件清单（`git show --name-only` 实取，与计划逐项一致）

**第 1 笔 `a98f6d5`（10 项）**【实测】

```
backend/src/main/java/com/example/configmgr/task/controller/TaskController.java
docs/evidence/S44a-前端基座验证.md
docs/test-cases.md
docs/verification-results.md
frontend/src/App.vue
frontend/src/api/tasks.ts
frontend/src/stores/task.ts
frontend/src/views/ExportWizardView.vue
frontend/src/views/ImportWizardView.vue
frontend/src/views/TaskCenterView.vue
```

- 钩子输出：`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`。
- 信息体已按指令注明：菜单 5 项→3 项（删导出/导入向导直达项、向导页高亮归算任务中心）；`TaskCenterView.vue` 466→357 行；`stores/task.ts` 含同文件并入的孤儿导出 `jobStatusLabel` 删除；后端**仅删一句过时 javadoc**（无行为变化）；**AI 发布冲突提示渲染点缺失的已知缺口（已立项 R-94）**。

**第 2 笔 `c0fada5`（2 项）**【实测】

```
docs/requirements.md
frontend/src/utils/format.ts
```

**第 3 笔 `0b273fb`（2 项）**【实测】

```
docs/evidence/S44b-五页视图验证.md
scripts/ci/check-docs.mjs
```

- 信息体已注明：S44b 脱敏修正 + 断言⑤ 三态（NFKC / 隐形字符 / 去分隔符连写）+ 词首守卫 + 连写组独立计数自检；同形字母缺口已登记。

**第 4 笔 `38139c9`（3 项）**【实测】

```
docs/README.md
docs/research/对话时序混排-业界调研.md
docs/research/步骤条交互-业界调研.md
```

**第 5 笔 `6ee96c3`（1 项）**【实测】

```
docs/M2-排期计划.md
```

### 交付口径核对

- 5 笔累计变更文件 = **18 项**，与起点 18 项**一一对应、无遗漏无多带**（`git diff --name-only d3020ee HEAD | wc -l` = 18）【实测】。
- 提交后工作树 **clean**（`git status --porcelain` 空）【实测】。
- **锚点内容一致性**：逐文件比对 `git show HEAD:<path>` 的 blob sha256 与 `file-manifest.txt` 锚点 —— **17/18 逐字节相同**；唯一差异 `TaskController.java` 为**纯行尾规范化**（blob 7352 字节 = 工作树 7522 − 170 个 CRLF→LF，且 `工作树内容 LF 归一后 == blob` 实测为 True；该文件上一版 blob 同为 LF 存储），工作树侧字节 sha256 仍等于锚点值 `6f1664f9ac497f28…`，属 `core.autocrlf=true` 的标准行为，**非内容漂移**【实测】。

---

## 四、push 与 CI 核验闭环（第四步）

### 4.1 push

```
$ git push origin main
To <REPO-URL>
   d3020ee..6ee96c3  main -> main                    # 【实测】退出码 0
```

- 新 HEAD = `6ee96c331d89349af69db4b0d4b1246723f24db3`；`origin/main` 与之相同，`main...origin/main` 同步【实测】。

### 4.2 CI（匿名轮询，间隔 ≥90s）

- run id **38038313522**｜workflow「CI 门禁」｜head_sha `6ee96c3`｜**status = completed｜conclusion = success**
- 链接：<REPO-URL>/actions/runs/38038313522

| job | job id | 结论 |
|---|---|---|
| 后端编译与单测 | 114173312841 | **success** |
| 文档一致性门禁（check-docs）｜阻断 | 114173313056 | **success** |
| 前端类型检查与构建 | 114173313074 | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | 114173313161 | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | 114173548293 | **success** |

- 停止条件达成：run 级 `completed` 且 **5/5 job 全 `completed`**（轮询第 2 次即满足，创建 08:33:51Z → 完成约 08:37:07Z）【实测】。
- 关键步骤级证据（jobs API）【实测】：`文档一致性门禁（6 条断言）→ success`；`类型检查（vue-tsc --noEmit）→ success`、`生产构建（vue-tsc + vite build）→ success`；`运行 E2E（29 用例）→ success`；`全历史脱敏扫描（--files-only · 报告制不计门禁）→ success`。
- **无任何 failure**，未触发"拉日志摘要上报"分支。报告制 scan-history 的详细日志因匿名调用无 admin 权限（HTTP 403）不可下载；改以 jobs API 的 step 级结论取证（上条），job 级与 step 级均为 success【实测】。

### 4.3 HEAD 态门禁四连（push 后干净 HEAD 态复跑）

| 门禁 | 命令 | 结果 |
|---|---|---|
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 全绿**，退出码 0【实测】 |
| 前端 typecheck | `frontend/` → `yarn typecheck` | `vue-tsc --noEmit` 无错，`Done in 6.36s`，退出码 0【实测】 |
| 前端 build | `frontend/` → `yarn build` | `✓ built in 31.11s`（1747 modules），退出码 0；产物 `frontend/dist/` 已被 `.gitignore:17` 忽略，未污染工作树【实测】 |
| 后端测试（XML 口径） | 包装脚本 `<LOCAL_WORK>\commit-gov\mvn-wrap.sh`（内含全路径含空格 Maven：`<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，`cd backend` 后 `mvn -B test`） | **`Tests run: 380, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS**（01:00 min）【实测】 |

后端 XML 口径明细【实测】：

- surefire XML 文件数：**前 58 → 后 58**，`comm -23`（减少集）为**空**，**只增不减口径满足**。
- 汇总四值：**tests=380, failures=0, errors=0, skipped=0**（与要求口径 380/0/0/0 精确一致）。
- 耗时 60 秒，远低于 20 分钟上限，未触发"报进展继续等"分支。

### 4.4 终态

```
$ git log --oneline -8
6ee96c3 docs(plan): 登记 AI 发布冲突提示落点重建立项（R-94）
38139c9 docs(research): 入库步骤条交互与对话时序混排两份业界调研
0b273fb fix(ci): 命名门禁扩展三态检测与自检防掏空
c0fada5 refactor(frontend): 删除无调用方的孤儿导出并回灌需求文档注记
a98f6d5 feat(frontend): 任务中心标题即打开、拆除详情抽屉、精简顶部菜单
d3020ee docs: 闭环登记——包①/包②/包⑤/UI 包已修注记 + SSE 写超时口径回灌 + 红队②观察项 + 轮询治理与测试框架立项
bf8aa6d fix(frontend): 编辑器打开结局校验回灌 + 工具预算公式严格化（上限−60s）与计时器溢出钳制
ad36ad7 fix(frontend): 弹窗排版同类治理——radio 溢出重叠/label 自适应/表格列宽/消息框换行 + 任务名称非必填默认名

$ git status
On branch main
Your branch is up to date with 'origin/main'.
nothing to commit, working tree clean
```

---

## 五、异常与遗留

**异常：无 BLOCKER，无流程中断，无 push 失败，无 CI failure。**

需要登记的非阻断事项：

1. **锚点 `diff-tracked.patch` 恰为 65535 字节（= 2^16−1）**——已用 `-U10`/`-U20` 越过该值反证不存在截断上限，并用 `+201/-200` 与 `--shortstat` 对账证明其完整；但该"整数巧合"值得后续锚点生成工具留意（若工具链真存在缓冲上限，本次恰未触及）【实测 + 推断】。
2. **`TaskController.java` 入库行尾为 LF（工作树为 CRLF）**——`core.autocrlf=true` 的标准规范化，凭 blob 字节数差（170）与"LF 归一后逐字节相等"双重实测确认；工作树侧 sha256 仍与锚点一致，不影响内容正确性【实测】。
3. **报告制 scan-history job 日志匿名不可下载（HTTP 403）**——已用 jobs API 的 step 级结论替代取证；若需完整日志，须具备仓库 admin 权限的凭证【实测】。
4. **扫描器保守过匹配（向安全侧，登记知悉）**：形似 IP 的越界段与非私网前缀点分版本号（`v10.2.3`）会被报为内网 IP；本次 18 项未实际触发此类误报【实测】。
5. **红队 MAJOR-1 的缺口按计划未在本批修复**——AI 侧发布冲突提示渲染点缺失已随第 1 笔提交信息留痕并正式立项 R-94（第 5 笔登记）。该缺口在本批交付后**仍然存在**，属已知并已立项的未修项【实测】。
6. 工作树既有噪声（历史证据文档中的 `127.0.0.1:10086` 环回、`docs/spike/logs` 中的主机名、`docs/merge` 中的 GitHub 组织名）**非本批引入**，按裁决 7"历史记录不回改"口径未处理【实测】。

## 六、产出物路径

| 内容 | 路径 |
|---|---|
| 本报告 | `<LOCAL_WORK>\commit-gov\提交报告.md` |
| 独立脱敏扫描器 | `<LOCAL_WORK>\commit-gov\desense-scan.mjs` |
| 植入对照日志（可复跑） | `node <LOCAL_WORK>\commit-gov\desense-scan.mjs <repo> seed`（本报告 §2.1 已录全文） |
| 18 项实盘扫描结果 | `<LOCAL_WORK>\commit-gov\scan-files.txt` + 控制台输出（见 §2.2） |
| 全仓基线扫描 | `<LOCAL_WORK>\commit-gov\repo-baseline-scan.txt`（916 文件 / 2551 处） |
| 提交信息原文 | `<LOCAL_WORK>\commit-gov\msg1.txt` … `msg5.txt` |
| Maven 包装脚本 | `<LOCAL_WORK>\commit-gov\mvn-wrap.sh` |
| 后端测试全量日志 | `<LOCAL_WORK>\commit-gov\mvn-test-head.log`（含 `Tests run: 380 …`） |
| CI 轮询日志 | `<LOCAL_WORK>\commit-gov\ci-poll.log`、`jobs.json`、`run.json` |
| surefire 前后清单 | `<LOCAL_WORK>\commit-gov\surefire-before.txt` / `surefire-after.txt` |

---

## 七、批 A（等待治理 R-95）提交留痕（2026-10-10 追加）

> 本节为批 A（等待治理排查 R-95 = W-01 + W-02 合一案）交付的**事后追加留痕**。前序提交官子智能体被用户中途停掉，本节事实由只读现场核实补齐（`git log` / `git status` / `git reflog` / 匿名 GitHub API），**未改任何文件、未执行任何 git 写操作**。
> 路径按脱敏纪律以占位符给出：`<MAIN_REPO>` = 主仓库工作树，`<SCRATCH>` = 本机证据/暂存根目录（`commit-gov`、`redteam-batch-a` 所在层）。

### 7.1 三笔提交（前端 / 后端 / 文档）

| # | commit | 标题 | 文件数 | 增删 |
|---|---|---|---|---|
| 1 | `0f1b334` | feat(frontend): download_export_file 即发即回 + 在途防重复与跨任务清理 + ui_event download 60s abort | 3 | +212 / -36 |
| 2 | `43f7d0a` | fix(backend): 前端工具超时文案改「结局未知（不表示未发生）」+ 回归锁负向断言 | 4 | +189 / -5 |
| 3 | `6e59e0c` | docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕（含 N-6 复核登记与红队观察项） | 4 | +32 / -1 |

全文 hash【实测】：

1. `0f1b334431140c8bc93ede421dd6698d2fd0a742`
2. `43f7d0a6fc574a5f1569ea3d0909f30658bdc2c9`
3. `6e59e0cd19cfcd615a7699e79c957659303474aa`

逐笔文件清单（`git show --name-status` 实取）【实测】：

**第 1 笔 `0f1b334`（前端，3 项）**

```
frontend/src/stores/workspace.ts
frontend/src/utils/frontend-tools.ts
frontend/src/views/ExportWizardView.vue
```

**第 2 笔 `43f7d0a`（后端，4 项，含 1 个新增测试）**

```
backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java
backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java   （新增）
```

**第 3 笔 `6e59e0c`（文档，4 项）**

```
docs/M2-排期计划.md
docs/requirements.md
docs/03-技术方案文档-v2.1.md
docs/spike/SP-01ab-决策卡.md
```

- 提交时间（`%ad` iso）：**18:12:15 / 18:12:18 / 18:12:23（+0800）**，即批 A 三笔在同分钟内连续落下【实测】。
- 三笔标题与提交信息原文 `<SCRATCH>/msgA1.txt` / `msgA2.txt` / `msgA3.txt` 首行**逐字一致**，顺序为前端 → 后端 → 文档【实测】。
- 工作树终态：`git status --porcelain` **输出为空（0 项）** —— 批 A 的 11 项改动（3 + 4 + 4）**已全部被三笔提交收走，无剩余、无未跟踪残留**【实测】。
- `git log --oneline -8` 现态（HEAD 起）【实测】：

```
6e59e0c docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕（含 N-6 复核登记与红队观察项）
43f7d0a fix(backend): 前端工具超时文案改「结局未知（不表示未发生）」+ 回归锁负向断言
0f1b334 feat(frontend): download_export_file 即发即回 + 在途防重复与跨任务清理 + ui_event download 60s abort
6ee96c3 docs(plan): 登记 AI 发布冲突提示落点重建立项（R-94）
38139c9 docs(research): 入库步骤条交互与对话时序混排两份业界调研
0b273fb fix(ci): 命名门禁扩展三态检测与自检防掏空
c0fada5 refactor(frontend): 删除无调用方的孤儿导出并回灌需求文档注记
a98f6d5 feat(frontend): 任务中心标题即打开、拆除详情抽屉、精简顶部菜单
```

### 7.2 push

```
$ git status -sb
## main...origin/main                              # 无 ahead/behind 计数     # 【实测】
$ git rev-parse HEAD        → 6e59e0cd19cfcd615a7699e79c957659303474aa
$ git rev-parse origin/main → 6e59e0cd19cfcd615a7699e79c957659303474aa       # 【实测】
$ git reflog show origin/main
6e59e0c refs/remotes/origin/main@{0}: update by push                          # 【实测】
```

- **push 已发生**：本地 `HEAD` 与 `origin/main` **同点**（`6e59e0c`），远端跟踪引用末次更新记录为 `update by push`【实测】。
- 远端旁证：GitHub 侧最新 workflow run 的 `head_sha` 即 `6e59e0c`（见 §7.3），说明该 commit 确已落在远端 `main`【实测】。

### 7.3 CI（run id 38044103814）

- run id **38044103814**｜workflow「CI 门禁」（run_number 85）｜head_sha `6e59e0c`｜event `push`
- **status = completed｜conclusion = success**｜created 2026-10-10T10:12:34Z → 完成 10:15:41Z（约 3 分钟）【实测】
- 链接：<REPO-URL>/actions/runs/38044103814

| job | 结论 |
|---|---|
| 后端编译与单测 | **success** |
| 前端类型检查与构建 | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | **success** |
| 文档一致性门禁（check-docs）｜阻断 | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | **success** |

- **5/5 job 全 `completed` 且 `success`**，job 级与 step 级均无 failure（`/jobs` 端点：非 `success`/`skipped` 的 step 数为 0）【实测】。
- 本表未采 job id（本轮仅取 job 名与结论，匿名 API 调用限额内省用）—— 如需 job id，可用 §7.3 链接对应的 `/jobs` 端点复核。
- 前序 run `38038313522`（head_sha `6ee96c3`，上一批）同为 success，批 A 未引入回归【实测】。
- 匿名 API 通过 `curl --ssl-no-revoke -s "https://api.github.com/repos/<USER>/ai-example/actions/runs?branch=main&per_page=3"` 及其 `/jobs` 端点实查（共 2 次调用）【实测】。

### 7.4 材料钉值

| 内容 | 值 | 来源 |
|---|---|---|
| 批 A 变更锚点 `diff.patch` SHA-256 | **`b0a0bc00b1d0bc338dc4b554f64d02954f8e174ad9191af60c20e2b40093a31a`** | 钉于 `<SCRATCH>/redteam-batch-a/复核与重钉报告.md`（重钉对照表）与同目录 `sha256-manifest.txt`【实测】 |

- 复核动作：对 `<SCRATCH>/redteam-batch-a/diff.patch` 现态实跑 `sha256sum`，得 **`b0a0bc00b1d0bc338dc4b554f64d02954f8e174ad9191af60c20e2b40093a31a`**，与上述钉值**逐字符相同**（重钉值有效，锚点未再漂移）【实测】。
- `sha256-manifest.txt` 另含 10 个改动文件的逐文件 SHA-256（`AiProperties.java` / `ConfirmGate.java` / `AiTools.java` / 新增测试 / 4 份 docs / `workspace.ts` / `frontend-tools.ts` / `ExportWizardView.vue`）【实测】。

### 7.5 红队结论

- 批 A 红队复评结论：**带条件 PASS** —— **0 BLOCKER / 0 MAJOR / 2 MINOR**，两项 MINOR（跨任务在途集合、回归锁负向断言）**已随批 A 处置**；另 **5 项观察项**（`downloading` 双轨覆写 / 哨兵值碰撞 / 卸载重挂同码双发窗口 / `ui_event download` 双 60s 竞速文案高估 / 前置体检口径不实）**已登记**排期（`docs/M2-排期计划.md`「批 A 红队观察项登记」节，随第 3 笔入库）【实测】。
- 复核发现 `N-6` 锚点（`ConfirmGate.java:478` `submitFrontendResult` 非取消回灌硬编码 `ok=true`）已并入 **R-97 行**登记【实测】。

### 7.6 流程瑕疵留痕（登记待修）

1. **`<SCRATCH>/ci-poll-batchA.log` 9 次轮询全部 `PARSE_ERR`**——报错为 `No such file or directory: '<SCRATCH>/ci-last-runs.json'`，即批 A 的 CI 轮询脚本**未写出 JSON 结果文件即退出**，轮询日志本身**不构成有效取证**；本节的 CI 结论因此**以事后实查 `api.github.com` 为准**（§7.3），非以该轮询日志为准【实测】。
2. **该轮询脚本问题登记待修**（`<SCRATCH>/ci-poll-batchA.sh`）：需补「请求失败/写盘失败时的显式失败退出与原因落盘」，避免再出现"轮询空转 9 轮、日志全 PARSE_ERR 而无人发现"的静默失效【实测 + 推断】。

### 7.7 本批产出物（`<SCRATCH>` 根）

| 内容 | 文件名 |
|---|---|
| 提交信息原文 | `msgA1.txt` / `msgA2.txt` / `msgA3.txt` |
| 批 A 变更锚点 | `batchA-diff.patch`、`batchA-files.txt` |
| 后端测试日志 | `mvn-test-batchA.log` |
| HEAD 态门禁日志 | `head-gate-batchA.log`、`frontend-gate-batchA.log` |
| surefire 清单 | `surefire-now.txt` |
| CI 轮询（**失效，见 §7.6**） | `ci-poll-batchA.sh`、`ci-poll-batchA.log`、`ci-last-runs.json` |

**批 A 终态：三笔已提交（`0f1b334` / `43f7d0a` / `6e59e0c`）、已推送（HEAD = origin/main = `6e59e0c`）、CI 已绿（run 38044103814，5/5 job success）；唯一遗留为 CI 轮询脚本静默失效（§7.6），已登记待修。**

---
---

# ai-example 合流项目 · 提交官报告（第 2 期：等待治理批 A「download_export_file 即发即回」）

- 提交官：Kimi K3（提交官子智能体，独立于施工者与复核员）
- 日期：2026-10-10
- 主仓库：`<MAIN_REPO>`（分支 `main`，远端 `<USER>/ai-example`）
- 起点 HEAD：`6ee96c3`｜终点 HEAD：`6e59e0c`
- 材料包：`<SCRATCH>`（红队报告 / 修复报告-MINOR / 复核与重钉报告 / diff.patch / sha256-manifest.txt）
- 平台：Windows + Git Bash｜`core.autocrlf=true`｜`core.hooksPath=scripts/githooks`
- 断言标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出

---

## 一、提交前硬性核验

### 1.1 工作树状态逐条归属（第 1 步）

```
$ git status --porcelain | cat -n            # 【实测】core.quotepath 默认 true，中文路径按八进制转义显示
  1  M backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
  2  M backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java
  3  M backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
  4  M docs/03-技术方案文档-v2.1.md
  5  M docs/M2-排期计划.md
  6  M docs/requirements.md
  7  M docs/spike/SP-01ab-决策卡.md
  8  M frontend/src/stores/workspace.ts
  9  M frontend/src/utils/frontend-tools.ts
 10  M frontend/src/views/ExportWizardView.vue
 11  ?? backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java
$ total=11  M=10  ??=1
```

- 与 `<SCRATCH>/复核与重钉报告.md` §二 证据 1 的 11 项清单**逐条比对：完全一致**（同一集合、同一状态字母、同一数量）⇒ **无多、无少、无异，未触发停手条件**【实测】。
- 归属：批 A 9 项（第 1/2/3/5/6/8/9/10/11 项）+ 文档棒 2 项（第 4/7 项）。
- `HEAD` = `origin/main` = `6ee96c331d89349af69db4b0d4b1246723f24db3`，分支 `main`【实测】。

### 1.2 门禁全量真跑（第 2 步）

#### 2a 后端全量测试（`mvn -B test`，未加 `-Dtest`）

```
$ bash <SCRATCH>/.run-fix/mvn-run.sh -B test        # 包装脚本内含全路径 Maven（路径含空格）
[INFO] Tests run: 383, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
MVN_EXIT=0                                          # 【实测】
```

surefire XML 口径明细【实测】：

- XML 文件数：**基线 58 → 现态 59**，新增项恰为 `TEST-com.example.configmgr.ai.gate.ConfirmGateFrontendTimeoutTextTest.xml`。
- `comm -23`（基线有而现态无）= **0 行** ⇒ **只增不减口径满足**。
- 汇总四值：**tests=383, failures=0, errors=0, skipped=0**。
- 该新增类单项：`Tests run: 3` ⇒ **383 − 3 = 380 = 基线**，闭合。

> **与题面预期的偏差（已据实登记，非失败）**：题面写「应 = 381 通过（基线 380 + 新增 1 个测试类）」，**实测 383**。差异原因 = 新增测试类 `ConfirmGateFrontendTimeoutTextTest` 含 **3 个用例**（参数化 2s / 3s 两例 + 确认门 1s 一例，实测 `Tests run: 3`），故基线 380 + 3 = 383；「+1 个测试类 ⇒ +1 用例」的算法与类内容不符。失败/错误/跳过三项均为 **0**，方向为**只增**，故未按「失败即停手」处置，继续执行并在本报告与交付说明中显著标注。

#### 2b 前端类型检查与构建

```
$ cd frontend && npx vue-tsc --noEmit    → 无诊断，TYPECHECK_EXIT=0        # 【实测】
$ cd frontend && yarn build             → ✓ built in 31.20s / Done in 38.42s / BUILD_EXIT=0   # 【实测】
```

- `yarn build` = `vue-tsc --noEmit && node scripts/build.mjs`（与 `package.json` 实读一致）；产物 `frontend/dist/` 由 `.gitignore:17` 忽略，未污染工作树【实测】。

#### 2c 文档一致性门禁

```
$ node scripts/ci/check-docs.mjs        → 全部通过：6/6 断言绿，CHECKDOCS_EXIT=0   # 【实测】
```

- 门禁自带植入对照：命名纪律扫 `git ls-files` 918 路径 / 实扫文本 915 / 豁免 3；规则自检连写组 5/5、分隔组 24/24、负对照 11 全不误伤。

#### 代码批次落地后的 HEAD 态复跑（题面要求）

```
[提交 1、2 落地后] node scripts/ci/check-docs.mjs   → 6/6 绿，退出码 0        # 【实测】
```

---

## 二、脱敏终扫（第 3 步）

### 2.1 植入对照先行（证明扫描器活着）

```
$ node <COMMIT-GOV>/desense-scan.mjs <COMMIT-GOV> seed
正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记；exit=0      # 【实测】
```

- 四模式：① 盘符绝对路径 ② 本机身份（用户名/主机名/用户目录）③ 内网或本机 IP ④ 命名违禁（NFKC 折叠 + 隐形字符双态 + **零分隔连写** + 词首守卫 + 大小写不敏感，与仓库门禁断言⑤ 同规则）。

### 2.2 实盘扫描

```
$ node desense-scan.mjs <COMMIT-GOV> realscan batchA-diff.patch
实盘：1 个文件，命中 0 处，按豁免登记放过 0 处        # 【实测】退出码 0 —— 11 项 diff 全文零命中
$ node desense-scan.mjs <MAIN_REPO> realscan < 11 项清单
  [命中] docs/spike/SP-01ab-决策卡.md:50 内网/本机 IP :: 127.0.0.1
实盘：11 个文件，命中 1 处
```

| 模式 | 11 项 diff 全文 | 11 项文件全文 |
|---|---|---|
| ① 盘符绝对路径 | **0** | 0 |
| ② 本机身份 | **0** | 0 |
| ③ 内网/本机 IP | **0** | 1（见下，既存豁免） |
| ④ 命名违禁（含零分隔连写 / 全大写 / 混合大小写） | **0** | 0 |

**唯一命中的判定与处置（按既存豁免放过，非本批引入）**【实测】：

- 位置 `docs/spike/SP-01ab-决策卡.md:50`：`export AI_BASE_URL="http://127.0.0.1:18305"`。
- 与 `git show HEAD:…` 第 50 行逐字比对 = **IDENTICAL**；该文件本批唯一 hunk 在 `@@ -128,6 +128,8 @@`（只追加批 A 注记）⇒ **该行是未改动的既存上下文行**，非本批新增。
- `127.0.0.1` 为**环回地址**（非内网/局域网地址），按既有裁决（历史证据/PoC 文档不回改）登记放过。

### 2.3 API key / 凭据独立扫描

```
$ grep -nEi 'sk-[A-Za-z0-9]{12,}|ghp_[A-Za-z0-9]{20,}|github_pat_|AKIA[0-9A-Z]{12,}|api[_-]?key…|secret…|Bearer …|password…' batchA-diff.patch   → 0 命中，exit=1
$ grep -nE 'BEGIN [A-Z ]*PRIVATE KEY|BEGIN OPENSSH|BEGIN RSA' batchA-diff.patch                                                                  → 0 命中，exit=1
$ 三份提交信息脱敏预扫（msgA1/2/3.txt）→ 各 0 命中
```

**第 3 步结论：11 项 diff 全文四模式零命中、凭据类零命中；1 处环回命中为既存上下文行并已按豁免登记。未触发停手条件。**

---

## 三、细分提交（第 4 步）

三笔均以**显式逐文件 `git add <具体路径>`** 暂存（全程未用 `git add -A` / `git add .`），每笔先核对暂存清单再 `git commit -F <信息文件>`；**未使用 `--no-verify`**，pre-commit 钩子三笔均实跑通过并输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`。

| # | commit | 标题 | 文件数 | 增删 |
|---|---|---|---|---|
| 1 | `0f1b334` | feat(frontend): download_export_file 即发即回 + 在途防重复与跨任务清理 + ui_event download 60s abort | 3 | +212 / −36 |
| 2 | `43f7d0a` | fix(backend): 前端工具超时文案改「结局未知（不表示未发生）」+ 回归锁负向断言 | 4 | +189 / −5 |
| 3 | `6e59e0c` | docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕（含 N-6 复核登记与红队观察项） | 4 | +32 / −1 |

全文 hash：

1. `0f1b334431140c8bc93ede421dd6698d2fd0a742`
2. `43f7d0a6fc574a5f1569ea3d0909f30658bdc2c9`
3. `6e59e0cd19cfcd615a7699e79c957659303474aa`

### 切分理由（与题面建议一致，未调整）

- **提交 1 前端**：三项改动同属一个用户可见行为（AI 侧「即发即回」+ 在途防重复/跨任务清理 + `ui_event download` 补 60s 上限），且共用同一份在途状态，拆开会出现「执行器改了、防重没改」的不可运行中间态。
- **提交 2 后端**：三处改动只有一处是行为（超时文案），其余两处是注释与新增测试；测试文件是文案口径的回归锁，必须与文案同笔落地，否则中间态即「改了文案没有锁」。
- **提交 3 文档**：四份文档同属「批 A 口径回灌 + 去向表登记」，内容互相引用（`requirements` 注记 ↔ `M2` 的 R-95 ↔ 两份旧口径注记），拆开会造成引用悬空。

### 逐笔文件清单（`git diff --cached --name-only` 实取）

**第 1 笔 `0f1b334`（3 项）**【实测】

```
frontend/src/stores/workspace.ts
frontend/src/utils/frontend-tools.ts
frontend/src/views/ExportWizardView.vue
```

**第 2 笔 `43f7d0a`（4 项）**【实测】

```
backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java
backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java    ← create mode 100644
```

**第 3 笔 `6e59e0c`（4 项）**【实测】

```
docs/03-技术方案文档-v2.1.md
docs/M2-排期计划.md
docs/requirements.md
docs/spike/SP-01ab-决策卡.md
```

### 交付口径核对

- 三笔累计变更文件 = **11 项**，与起点 11 项**一一对应、无遗漏无多带**（`git diff --name-only 6ee96c3 HEAD` 与提交前在制品清单 `diff` → `IDENTICAL`）【实测】。
- 累计 `git diff --shortstat 6ee96c3 HEAD` = **11 files changed, 433 insertions(+), 42 deletions(-)** —— 与材料包钉死的 `diff.patch` 的 `git apply --numstat` 合计（433 / 42）**完全吻合**，证明入库内容与送审内容同源【实测】。
- 提交后工作树 **clean**（`git status --porcelain` 空）【实测】。

---

## 四、push 与 CI 核验闭环（第 5、6 步）

### 4.1 push

```
$ git push origin main
To <REPO-URL>
   6ee96c3..6e59e0c  main -> main                     # 【实测】退出码 0
```

- 新 HEAD = `6e59e0cd19cfcd615a7699e79c957659303474aa`；`git rev-parse origin/main` 与之相同，`## main...origin/main` 无偏差【实测】。

### 4.2 CI（匿名轮询）

- run id **38044103814**｜workflow「CI 门禁」｜head_sha `6e59e0c`｜**status = completed｜conclusion = success**｜created 2026-10-10T10:12:34Z（与 push 同刻）
- 链接：<REPO-URL>/actions/runs/38044103814

| job | job id | 状态 | 起止（UTC） |
|---|---|---|---|
| 前端类型检查与构建 | 114190035606 | **success** | 10:12:37 → 10:13:19 |
| 文档一致性门禁（check-docs）｜阻断 | 114190035718 | **success** | 10:12:37 → 10:12:44 |
| 后端编译与单测 | 114190035770 | **success** | 10:12:37 → 10:14:00 |
| 全历史脱敏扫描（报告制 · 不计门禁） | 114190035784 | **success** | 10:12:37 → 10:14:44 |
| E2E 全流程（29 用例 · 桩上游 + redis） | 114190271872 | **success** | 10:14:03 → 10:15:40 |

- 停止条件达成：run 级 `completed` 且 **5/5 job 全 `completed` + 全 `success`**（`ALL_COMPLETED=True ALL_SUCCESS=True N_GE_5=True`）【实测】。
- 关键步骤级证据（jobs API 逐 step）【实测】：
  - 文档一致性门禁 job → `文档一致性门禁（6 条断言）→ success`；
  - 前端 job → `类型检查（vue-tsc --noEmit）→ success`、`生产构建（vue-tsc + vite build）→ success`；
  - 后端 job → `编译并运行单测（mvn test，全量用例）→ success`。
- **无任何 failure**，未触发「抓 job 日志诊断上报」分支；报告制 scan-history 的详细日志匿名无 admin 权限不可下载，改以 job/step 级结论取证（同上）【实测】。

### 4.3 push 后干净 HEAD 态门禁复跑（四连）

| 门禁 | 命令 | 结果 |
|---|---|---|
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 全绿**，退出码 0【实测】 |
| 前端类型检查 | `frontend/` → `npx vue-tsc --noEmit` | 无诊断，`TYPECHECK_EXIT=0`【实测】 |
| 后端全量测试 | `<SCRATCH>/.run-fix/mvn-run.sh -B test`（clean HEAD） | **`Tests run: 383, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS + `MVN_EXIT=0`**【实测】 |
| 工作树 | `git status --porcelain \| wc -l` | **0**（仍 clean）【实测】 |

### 4.4 终态

```
$ git log --oneline -4
6e59e0c docs: 入库等待治理排查 R-95~R-99 立项与批 A 已施工留痕（含 N-6 复核登记与红队观察项）
43f7d0a fix(backend): 前端工具超时文案改「结局未知（不表示未发生）」+ 回归锁负向断言
0f1b334 feat(frontend): download_export_file 即发即回 + 在途防重复与跨任务清理 + ui_event download 60s abort
6ee96c3 docs(plan): 登记 AI 发布冲突提示落点重建立项（R-94）
```

### 4.5 材料钉值（本次送审 → 入库同源）

| 项 | 值 |
|---|---|
| `diff.patch` sha256 | `b0a0bc00b1d0bc338dc4b554f64d02954f8e174ad9191af60c20e2b40093a31a` |
| diff.patch 规模 | 708 行 / 11 文件 / 433+ 42− |
| sha256-manifest.txt | 12 行（diff.patch + 11 工作树文件），复核时 11 文件 `sha256sum -c` 全 OK |
| 复核与重钉报告 | `<SCRATCH>/复核与重钉报告.md`（本次门禁与 CI 证据的另一方留痕） |

---

## 五、异常与遗留

**异常：无 BLOCKER、无 push 失败、无 CI failure、无工作树漂移。**

需登记的非阻断事项：

1. **后端全量测试计数与题面预期不符（383 vs 381，非失败）** —— 差异已定位为「新增测试类含 3 个用例」而非「题目所称 1 个」；`failures/errors/skipped` 全为 0，XML 数 58 → 59 且基线集合是现态的**真子集**；383 − 3 = 380 = 基线，闭合。**未按停手处置**，理由与证据见 §1.2 2a；如指挥官的 381 是硬口径（而非推导值），需回滚重跑或调整预期。
2. **首次 CI 轮询脚本失效（已定位、已修正、未影响结论）** —— 我的轮询脚本内嵌 Python 用 Git Bash 形态的路径（盘符小写 + 前导斜杠）读中间文件，而 Windows Python 不认该形态，导致前 14 次轮询全部输出 `PARSE_ERR`（脚本仍未按"无法确认 run 状态"提前退出，是我的脚本缺陷）。**已改为直接调用 API 复核**：run `38044103814` = completed/success、5/5 job success（§4.2），结论未受影响；脚本已同步修正为标准盘符形态并留档（`<COMMIT-GOV>/ci-poll-batchA.sh`）。附带代价：本次消耗约 20 次匿名 API 配额（限额 60/小时）。
3. **`docs/spike/SP-01ab-决策卡.md:50` 的 `127.0.0.1:18305` 环回命中** —— 与 `HEAD` 版逐字相同、本批 hunk 在 `:128`，属**既存上下文行**，按既有裁决（历史 PoC 文档不回改）登记放过，非本批引入【实测】。
4. **`core.autocrlf=true` 行尾规范化** —— 三笔提交前 git 均提示 `LF will be replaced by CRLF the next time Git touches it`（工作树 LF → 库内 CRLF 的标准行为）；入库后内容与送审 `diff.patch` 的增删计数**逐项吻合**（433/42），不影响内容正确性【实测】。
5. **未跑的项（超出本单范围）** —— 红队「观察项」1~5 仍为登记知悉（其中观察项 1 与已修的 `MINOR-1` 同根但本轮不修）；`scan-history` job 的完整日志匿名不可下载（报告制，非阻断）。
6. **测试类新入库带来 +3 用例** —— 后续周期的「380 基线」口径应更新为 **383**，否则会重复出现本条第 1 号的计数偏差。
7. **本报告定稿时工作树已被并发写入（非本单动作，须周知）** —— 我在 §3/§4.3 记录的「11 项全部入库、工作树 clean」为**当时实测状态**；报告定稿时复查 `git status` 发现 `main` 已再前进 2 笔（`a56613d` axios 超时根 60s 命名常量化、`6d5162b` 数据列表分页缺省 50→20），并有 2 项新在制品（`backend/.../config/AsyncConfig.java`、`backend/src/main/resources/application.yml`），`main` 相对 `origin/main` **ahead 2**。这些提交与文件**不是本单产出**，属另一并发执行者的工作，我未触碰、未 push。**本单的提交 hash / 门禁证据 / CI 结论均以 `6e59e0c` 为准**（该 sha 已在 `origin/main`，run `38044103814` 绿）；并发提交的验收需另跑门禁与 CI。
8. **报告自身脱敏口径** —— 本条目路径一律用 `<MAIN_REPO>` / `<SCRATCH>` / `<COMMIT-GOV>` 占位；文中保留的 GitHub 组织名 `<USER>`（远端 URL 与 run 链接）属公开标识且为 CI 取证所必需，已知会被独立扫描器按"用户名形态"报出（与沿用条目同类的假阳性）；文中 `127.0.0.1` 为对第 3 条豁免命中的**引述**，非本机地址泄露。

## 六、产出物路径

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的「第 2 期」条目） |
| 提交信息原文 | `<COMMIT-GOV>/msgA1.txt` / `msgA2.txt` / `msgA3.txt` |
| 后端全量测试日志（提交前） | `<COMMIT-GOV>/mvn-test-batchA.log`（含 `Tests run: 383 …`） |
| 前端门禁日志（型检 + 构建） | `<COMMIT-GOV>/frontend-gate-batchA.log` |
| push 后干净 HEAD 门禁四连日志 | `<COMMIT-GOV>/head-gate-batchA.log` |
| surefire XML 复核 | `<COMMIT-GOV>/surefire-now.txt`（59 项）、`surefire-before.txt`（58 项基线） |
| 脱敏扫描器 + 植入对照日志 | `<COMMIT-GOV>/desense-scan.mjs`（§2.1 已录全文） |
| diff 全文扫描件 | `<COMMIT-GOV>/batchA-diff.patch`（= `<SCRATCH>/diff.patch`，sha256 `b0a0bc00…a31a`） |
| 在制品清单 | `<COMMIT-GOV>/batchA-files.txt`（11 项） |
| CI 证据 | `<COMMIT-GOV>/ci-jobs-run38044103814.txt`、`ci-jobs-steps-run38044103814.txt`、`ci-last-runs.json` |
| CI 轮询脚本 | `<COMMIT-GOV>/ci-poll-batchA.sh`（已修正路径形态） |

---

# ai-example 合流项目 · 提交官报告（第 3 期：数字规格小批「苞 A/B/F/G + 红队 MINOR 收口」）

> 2026-10-10 追加。范围 = `<MAIN_REPO>`（分支 `main`，远端 `github.com/<USER>/ai-example`）。
> 入库前库存 = 本地 **ahead 3**（`a56613d` / `6d5162b` / `51ee49c`，苞 C/D/E，已提交未 push）+ **工作树 24 项未提交改动**（23 项修改 + 1 项未跟踪新测试类）。
> 本批内容 = 苞 A / 苞 B / 苞 F / 苞 G + 修编 + 独立复核与 GLM 红队 MINOR 收口（sanctioned 变更）。
> 路径一律用 `<MAIN_REPO>` / `<SCRATCH>` / `<COMMIT-GOV>` 占位。

## 一、锚钉核对（第 2 步）

| 项 | 钉值（题面） | 实取值 | 判定 |
|---|---|---|---|
| 工作树锚钉（收口前）`git diff HEAD` sha256 | `74b6d4af…9eeea` | 锚钉件 `<SCRATCH>/numeric-spec-audit/_anchor_worktree.diff` = `74b6d4af08d644eb7e6485f3f64d1518c1c3c3232f23e2d0557ce2f3dd19eeea` | 锚钉件与钉值一致（自校验） |
| 已提交三笔 `git diff 6e59e0c..HEAD` sha256 | `81a3f869…1944f` | 实取 = `81a3f869092471f6f12f125223752d19d8da1e365865be72c0b76597c471944f`，与锚钉件 `_anchor_commits.diff` 逐字节一致 | ✅ 三笔无漂移 |
| 现工作树（收口后）`git diff HEAD` sha256 | 应 ≠ 锚钉 | `d3c7197683dda7fd0cfa3c29a170e0492226bea061161d21be69ac8189e9285f` | 差异须只等于收口（见下） |

**逐文件分片比对**（工具 `<COMMIT-GOV>/split-diff.mjs`；把两份 diff 按 `diff --git` 分片，逐片 sha256 对齐，全文见 `<COMMIT-GOV>/diffcmp/`）：

- 23 项受跟踪文件两两对齐，其中 **21 项分片 sha256 完全相同**（逐字节无漂移）。
- **只有 2 项不同，且恰为红队 MINOR 收口**：
  1. `backend/src/main/java/com/example/configmgr/data/controller/DataController.java` —— 锚钉件中**不存在**该文件分片，现态新增一段 javadoc（**7 增 / 0 删**）= 收口①（「硬上限 100 属业界推荐值、本端点未强制」口径澄清，实现零改动）。
  2. `docs/M2-排期计划.md` —— 分片差异**仅三处**：① `index` 行 blob 值（`dc11167..5a63cda` → `dc11167..291377a`）；② 末段 hunk 头 `+353,34` → `+353,41`；③ 内容 = 「小批苞 A 观察项登记」节**行内最小改写补句**（红队观察⑤：用例数 387→389 的增量来源）+ **新增小节**「GLM 红队小批复核 MINOR-1 登记」= 收口②（收口①的登记面）。
- 排除这 2 项后重算两片合集：锚钉 = `74b6d4af…`（不变）、现态 = `17e04419ca7a06e8f184d14dc30ec053b226722c677b78a414d7f190fde1edc5`。

**结论：锚钉核对 PASS** —— 收口前锚钉 → 现态的全部差异 = 红队 MINOR 收口（DataController javadoc +7 行、docs/M2 追加小节与行内补句），**无其他漂移、无越界改写**。

## 二、机械验收与脱敏终扫（第 1 步）

### 2.1 逐项归属（`git status` / `git diff HEAD --stat` 实取：23 M + 1 未跟踪 = 24 项）

| 归属 | 项数 | 文件 |
|---|---|---|
| **苞 A**（hitl 双键拆分） | 13 | `backend/…/ai/config/AiProperties.java`、`…/ai/exec/AiExecutorConfig.java`、`…/ai/gate/ConfirmGate.java`、`…/ai/run/ResilientChatService.java`、`…/ai/run/StreamWatchdog.java`、`…/ai/web/AiController.java`、`backend/src/main/resources/application.yml`、`…/ai/config/AiPropertiesT35DefaultsTest.java`、`…/ai/gate/ConfirmGateFrontendTimeoutTextTest.java`、`…/ai/web/AiControllerHealthSseDeliveryTest.java`、`frontend/src/components/SpreadGrid.vue`、`frontend/src/types/ai.ts`、`frontend/src/utils/frontend-tools.ts` |
| **苞 B**（`list_tasks` 上限 10→20 + 截断告知） | 2 | `backend/…/ai/tools/AiTools.java`（同文件含苞 A 的 `:498` 键名注释 → 按跨苞并入规则**整体归苞 B**）、`backend/…/ai/tools/AiToolsListTasksTruncationTest.java`（**未跟踪新类**） |
| **苞 F**（E2E 读窗与等待改造） | 2 | `scripts/verify-e2e.ps1`（含苞 A 键名注释同步）、`scripts/m2t2a-smoke.mjs` |
| **苞 G / 文档**（含苞 A 注记） | 6 | `README.md`、`docs/README.md`、`docs/adr/DECISION-REGISTER.md`、`docs/M2-排期计划.md`、`docs/03-技术方案文档-v2.1.md`、`docs/s4/S4.2-AI-Runtime-合流规格.md` |
| **红队 MINOR 收口** | 1 | `backend/…/data/controller/DataController.java` |

归属依据 = `<SCRATCH>/numeric-spec-audit/小批施工卡.md`（`A-4-13` 明确把 `docs/s4/S4.2` 键表同步列为苞 A 条目；`docs/03` 三处行尾注记文本自述「数字规格小批苞 A」；故这两份文档随文档提交入库，提交信息内已注明）。合计 13 + 2 + 2 + 6 + 1 = 24 ✅ 无多、无漏。

**数量闭合交叉校验**：工作树 23 项受跟踪文件 = `411` 增 / `81` 删；+ 未跟踪新类 80 行 ⇒ 应入库 `491` 增。五笔提交实取增删合计 = `231+101+96+56+7 = 491` 增、`49+3+19+10+0 = 81` 删 —— **逐项吻合**（无行丢失、无夹带）。

### 2.2 脱敏终扫（独立于仓库门禁）

| 检查 | 方法 | 结果 |
|---|---|---|
| 扫描器自检 | `node <COMMIT-GOV>/desense-scan.mjs . seed` | 正向种子 **23/23 命中**、负对照 **12/12 未误伤**、保守过匹配 2/2 已登记 ⇒ 规则活着 |
| 新增行实扫 | 从 `git diff HEAD` 抽 `+` 行（411 行）+ 未跟踪新类全文（80 行），四模式（盘符路径 / 本机身份 / 内网 IP / 命名违禁） | **命中 0 处**，豁免 0 处 |
| 凭据形态独立扫 | `grep -Ei` 私钥头 / `ghp_` / `AKIA…` / `sk-…` / `xox…` / `password`/`passwd`/`secret`/`token`/`api-key` 赋值 | **0 命中**；新增行内 **无任何 http(s) URL**、无 `.env` 提及 |
| 命名纪律 | 独立扫描器第四模式（与 `check-docs.mjs` 断言⑤ 同规则）+ `check-docs.mjs` 断言⑤ 全量 | **PASS**（无禁用组合；`git ls-files` 919 → **920**，恰为新测试类入库） |
| 阻断式钩子 | `core.hooksPath = scripts/githooks` 的 `pre-commit`（暂存区 SECRET / PATH / USER 三规则，**未用 `--no-verify`**） | 五笔提交均输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` |

## 三、细分提交（第 3 步）

| # | SHA | 提交信息（首行） | 规模 |
|---|---|---|---|
| 1 | `ffb5fcd` | `feat(backend): hitl 双键拆分 confirm-timeout=240s/frontend-tool-timeout=120s + /health 增 frontendToolTimeoutSeconds（苞 A）` | 13 文件 / +231 −49 |
| 2 | `73f5b08` | `feat(backend): list_tasks 上限 10→20 + 截断告知 + 新测试 AiToolsListTasksTruncationTest（苞 B）` | 2 文件 / +101 −3 |
| 3 | `a16bc10` | `test(e2e): 读窗 3000/3500→5000 三处 + AWAIT_MS 事件驱动改造（苞 F）` | 2 文件 / +96 −19 |
| 4 | `bd49c65` | `docs: DC-16 数字规格纪律四条立法 + R-100/101/104/105/106 立项与重编号留痕 + DC-01~16 口径同步（苞 G）` | 6 文件 / +56 −10 |
| 5 | `03a6cbe` | `docs(backend): DataController 分页 javadoc「硬上限100」口径澄清（红队 MINOR-1，不改行为）` | 1 文件 / +7 −0 |

- 每笔均**显式逐文件 `git add <file>`**（全程未使用 `git add -A` / `git add .`），每笔后 `git log --oneline -1` 确认（见下「五、终态」）。
- 切分口径与题面建议一致；**跨苞同文件按「整体归主苞 + 提交信息注明」处理**：`AiTools.java`（苞 A 注释 + 苞 B 主体）归苞 B；`scripts/verify-e2e.ps1` 归苞 F 并在信息内注明含苞 A 键名同步。
- **一处机械切分说明**：`docs/M2-排期计划.md` 同文件内含苞 A/B/F/G 行尾注记 + 红队收口两处（行内补句 + 新增小节），四处内容在同一条附录 hunk 内交织，hunk 级拆分需人工拼装补丁；按「跨苞同文件整体归主苞」规则**整体随文档提交入库**，故第 5 笔仅含 `DataController.java`。收口面两半（代码措辞 + 登记小节）在提交信息与本节均已点明，不构成漏提交。
- 提交信息全文留档：`<COMMIT-GOV>/msg-spec-A.txt` / `msg-spec-B.txt` / `msg-spec-F.txt` / `msg-spec-DOCS.txt` / `msg-spec-MINOR.txt`。

### 门禁复跑（第 4 步：全部提交后、HEAD 态）

`node scripts/ci/check-docs.mjs` ⇒ **6/6 断言绿**（EXIT=0；`git ls-files` 920 路径 / 实扫 917 / 豁免 3）。提交前同命令亦为 6/6。

## 四、push 与 CI 核验闭环（第 5、6 步）

### 4.1 push

```
6e59e0c..03a6cbe  main -> main        # git push origin main，EXIT=0
## main...origin/main                 # 无 ahead/behind 计数（ahead 0）
```

### 4.2 CI run

| 项 | 值 |
|---|---|
| run URL | <REPO-URL>/actions/runs/38059580591 |
| head_sha | `03a6cbec79cc4d9a4a589abc9d9ef93d2df9b03b`（= 本批 push 的新 HEAD，匹配） |
| 触发 | `push` / run_number 86；created `2026-10-10T14:26:02Z` → updated `2026-10-10T14:29:50Z` |
| run 级 | **status = completed，conclusion = success** |

五 job（`/jobs` 端点实取，全部 completed）：

| job | status | conclusion | 起止（UTC） |
|---|---|---|---|
| 后端编译与单测 | completed | **success** | 14:26:05 → 14:27:41 |
| 前端类型检查与构建 | completed | **success** | 14:26:05 → 14:26:56 |
| 文档一致性门禁（check-docs）｜**阻断** | completed | **success** | 14:26:05 → 14:26:13 |
| 全历史脱敏扫描（**报告制 · 不计门禁**） | completed | **success** | 14:26:05 → 14:27:29 |
| E2E 全流程（29 用例 · 桩上游 + redis） | completed | **success** | 14:27:44 → 14:29:49 |

停止条件（run 级 completed + ≥5 job 全 completed）已满足，故仅轮询 1 次（22:30:32 首次即 completed），匿名配额消耗极小。

> 说明：本单未自行重跑后端/E2E/前端（题面称本批验证已过，且属上游施工与复核方证据）；**CI 的独立复跑是本次提交的真正把关**——阻断式 `docs` job 与后端单测、E2E 均在同一 HEAD 上全绿，构成「入库 HEAD = 送审内容」的独立确认。

## 五、终态与遗留

### 5.1 工作树最终状态

```
03a6cbec79cc4d9a4a589abc9d9ef93d2df9b03b   # HEAD
git status --short            ⇒ 空（无修改、无未跟踪、无暂存残留）
git status -sb                ⇒ ## main...origin/main（ahead 0 / behind 0）
git log --oneline -8          ⇒ 03a6cbe → bd49c65 → a16bc10 → 73f5b08 → ffb5fcd → 51ee49c → 6d5162b → a56613d
```

### 5.2 异常与流程瑕疵（如实留痕）

1. **无 BLOCKER**：无 push 失败、无 CI failure、无门禁红、无工作树漂移、无锚钉漂移。
2. **轮询脚本路径形态缺陷（自查自纠，未影响结论）**：首两版 `ci-poll-spec.sh` 把 Git Bash 形态路径（前导斜杠 + 小写挂载点）直接交给 Windows Node 读取，Node 按**当前盘符根**解析（挂载点被当成根下同名目录）而报 `ENOENT`；两次尝试共消耗约 6 次匿名 API 调用。已改为显式盘符形态（脚本内分设 shell 形态与 Node 形态两个变量），修正后一次取到 run + jobs 全量；结论以最终实取为准。
3. **轮询次数说明**：因 CI 约 4 分钟内完成，实际只轮询 1 次（约 20s 延迟后），远低于题面「每 60–90 秒」的上限节奏。
4. **报告自身脱敏口径**：路径一律占位符；文中保留 GitHub 组织名（远端 URL 与 run 链接）属公开标识且为 CI 取证所必需，已知会被独立扫描器按「用户名形态」报出（与既有第 2 期报告同类假阳性，沿用登记）。
5. **红队 MINOR-1 的两处「待裁决」顺延（非本单授权面）**：① `DataController` 分页 `size` 是否落地强制（`Math.min(size,100)` 静默夹取 vs `@Max(100)` + 400，含是否连带复核前端 20/50/100 选项表与 E2E 显式传参面）；② 是否补一条「`application.yml` 绑定进 `AiProperties`」的断言锁（现两条锚定分别用类默认值与 mock 桩值，只改 yml 不误红）。两项均已登记于 `docs/M2-排期计划.md` 对应小节。
6. **N-7 / N-9 / N-10 等登记项**仍为【待指挥官裁决】（连接池未回显、是否增 `/health` 键、冻结文档旧键名是否回改），已随文档提交入库。

## 六、本批产出物（`<COMMIT-GOV>` 根）

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的第 3 期条目） |
| 现态工作树 diff（收口后） | `<COMMIT-GOV>/current-worktree.diff`（sha256 `d3c71976…`） |
| 锚钉分片比对工具与产物 | `<COMMIT-GOV>/split-diff.mjs`、`<COMMIT-GOV>/diffcmp/` |
| 收口增量 diff（docs/M2 分片对拍） | `<COMMIT-GOV>/closure-m2.diff` |
| 脱敏扫描器 / 扫描输入 | `<COMMIT-GOV>/desense-scan.mjs`、`<COMMIT-GOV>/dscan/added-lines.txt`（411 行）、`dscan/untracked-AiToolsListTasksTruncationTest.java` |
| 五笔提交信息原文 | `<COMMIT-GOV>/msg-spec-{A,B,F,DOCS,MINOR}.txt` |
| CI 证据 | `<COMMIT-GOV>/cipoll/run-1.json`（run 详情）、`cipoll/jobs-final.json`（五 job） |
| CI 轮询脚本（已修正路径形态） | `<COMMIT-GOV>/ci-poll-spec.sh` |
| 锚钉件（上游复核方留档） | `<SCRATCH>/numeric-spec-audit/_anchor_worktree.diff`、`_anchor_commits.diff` |

---

# ai-example 合流项目 · 提交官报告（第 4 期：对话面板包「R-100 / R-101」细分提交 + 红队收口）

> **范围**：工作树 9 改 + 2 未跟踪（共 11 条目，全部归属对话面板包）→ 4 笔细分提交 → push → CI 守夜 → HEAD 态门禁复跑。
> **脱敏口径**：路径一律占位符 —— `<MAIN_REPO>` = 主仓工作树、`<COMMIT-GOV>` = 提交官工作目录、`<SCRATCH>` = 仓库外材料区。按题面保留 GitHub 公开组织名（`<USER>`）与环回地址 `127.0.0.1`。
> **上游链条**：施工苞 A / 苞 B → 复核（带条件 PASS：1×MAJOR / 5×MINOR）→ 收口修（复核 F2/F4/F5）→ GLM 红队（带条件 PASS：0×BLOCKER / 1×MAJOR / 3×MINOR / 7×观察）→ 红队收口修（MAJOR-1 / MINOR-1 / MINOR-3，已终审）。

## 一、机械验收（步骤 1）

### 1.1 `git status --porcelain=v1` 实取（提交前）

```
 M README.md
 M backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java
 M backend/src/main/java/com/example/configmgr/ai/web/AiController.java
 M "docs/M2-排期计划.md"
 M docs/README.md
 M docs/adr/DECISION-REGISTER.md
 M frontend/src/api/ai.ts
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
?? backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java
?? scripts/chat-panel-smoke.mjs
```

### 1.2 逐条归属（11 条目，与题面清单逐项对齐）

| # | 条目 | 题面清单 | 归属 |
|---|---|---|---|
| 1 | `README.md` | ✔ | 文档（DC-01~18 索引同步） |
| 2 | `backend/.../ai/memory/RedisChatMemoryRepository.java` | ✔ | 后端（`isConversationIndexed` 单点判存） |
| 3 | `backend/.../ai/web/AiController.java` | ✔ | 后端（`DELETE /api/ai/history/{sessionId}`） |
| 4 | `docs/M2-排期计划.md` | ✔ | 文档（台账登记 + 已施工回写 + 红队注记） |
| 5 | `docs/README.md` | ✔ | 文档（索引同步） |
| 6 | `docs/adr/DECISION-REGISTER.md` | ✔ | 文档（DC-17 / DC-18 登记） |
| 7 | `frontend/src/api/ai.ts` | ✔ | 前端（`deleteHistory` 封装） |
| 8 | `frontend/src/components/AiPanel/AiPanel.vue` | ✔ | 前端（新建对话 / hover 复制 / 骨架屏） |
| 9 | `frontend/src/stores/ai.ts` | ✔ | 前端（双写修复 / 身份守卫 / stopped 抑制） |
| 10 | `backend/.../ai/web/AiControllerHistoryDeleteTest.java`（未跟踪） | ✔ | 后端（11 用例） |
| 11 | `scripts/chat-panel-smoke.mjs`（未跟踪） | ✔ | 脚本（CDP 冒烟 13 断言） |

**清单外条目 = 0**（无漂移、无临时产物、无构建残留）。

### 1.3 差异规模（`git diff HEAD --stat`，提交前）

```
 README.md                                          |   2 +-
 .../ai/memory/RedisChatMemoryRepository.java       |  17 ++-
 .../com/example/configmgr/ai/web/AiController.java |  97 +++++++++++++-
 docs/M2-排期计划.md                                 |  66 ++++++++++
 docs/README.md                                     |   2 +-
 docs/adr/DECISION-REGISTER.md                      |   4 +-
 frontend/src/api/ai.ts                             |  14 +-
 frontend/src/components/AiPanel/AiPanel.vue        | 144 ++++++++++++++++++++-
 frontend/src/stores/ai.ts                          | 104 +++++++++++++--
 9 files changed, 430 insertions(+), 20 deletions(-)      # 未跟踪 2 件不在 `git diff` 面内
```

### 1.4 新哈希锚（本步重算）

| 锚 | 值 |
|---|---|
| 提交前工作树 `git diff HEAD` 口径 sha256 | `1995c0f4c5f7500ed6cac6a698995dc0adb1d18a3f9b29f75ada67452d1521f0` |
| 未跟踪 `AiControllerHistoryDeleteTest.java` sha256 | `1afca29cf71a38f5b106bee2c9c4f8e9d873302ee3a7fcb68085d8439bafffd9` |
| 未跟踪 `chat-panel-smoke.mjs` sha256 | `33aad0a2e2fe6cf19838a6e874345c642671a88cab58179efdd2549e58ef63d7` |
| 基线 HEAD | `03a6cbec79cc4d9a4a589abc9d9ef93d2df9b03b`（`main` = `origin/main`） |
| 红队旧锚 `ee1e68fd…` | **不复现**（系收口修前口径，已预期变化 —— 红队 MAJOR-1 / MINOR-1 / MINOR-3 三笔收口在本次工作树内） |

## 二、文档微修（步骤 2，`docs/M2-排期计划.md`，编辑前均重新实读）

| # | 指令 | 实做 |
|---|---|---|
| ① | R-100 / R-101 行状态回写「已施工」 | 去向表 `:292` / `:293` 两行：状态列由 `**已定稿待施工**` 改写为 `**已施工**（原状态「已定稿待施工」…）`（**原状态词保留在注记内，实质零删**），并在**行尾**追加「行尾注记（2026-10-11 回写，只追加）」= 终验基线 + 锚点 + **提交留痕**（三笔代码提交短 sha，锚点落表体内） |
| ② | 约 `:441` 处登记日期订正 | 原文为 `2026-10-10 登记（只追加）`，**非**目标口径 ⇒ 已订正为 `2026-10-11 登记（裁决日 2026-10-10）（只追加）`（属唯一非追加式改写，见 §七·偏差③） |
| ③ | 追加一行红队结论注记 | 文末新增小节「对话面板包 GLM 红队结论注记（2026-10-11，只追加）」+ 一行注记：GLM 红队 = **带条件 PASS**（0×BLOCKER / 1×MAJOR / 3×MINOR / 7×观察）；MAJOR-1（`loadHistory` 会话身份守卫）/ MINOR-1（`stopped` 复位）/ MINOR-3（不删范围负断言）**均已收口并终审通过**；**MINOR-2** 处置口径 = 引入鉴权批须联动**会话归属校验**，**并入 `R-108` 注记**（与复核 `F6②` 同口径，勿双记） |

**净变化**（相对基线 HEAD 的该文件 numstat）：`66 增 / 0 删` → **`74 增 / 2 删`** ⇒ 本步净新增 **6 行**（4 行新小节骨架 + 2 行状态行改写产出）+ **改写 2 行**（即 2 行删除，原文词保留）；②的日期订正落在工作树既有新增块内，相对 HEAD 不产生差异行。

## 三、脱敏终扫（步骤 3，11 文件全量）

- **扫描器**：`<COMMIT-GOV>/desense-scan.mjs`（提交官自建，与仓库门禁 `scripts/ci/check-docs.mjs` 相互独立；四模式 = 盘符绝对路径 / 本机身份 / 内网·环回 IP / 命名纪律）。
- **植入对照先行**（证明扫描器活着，本次实跑）：正向种子 **23/23 命中**、负对照 **12/12 未误伤**、保守过匹配 2/2 已登记（形似 IP 的越界段与点分版本号向安全侧报出）。
- **实盘 11 文件**：SECRET **0**、PATH **0**、USER **0**、命名纪律 **0**；命中仅 **5 处环回 `127.0.0.1`**（题面明示保留，非违禁）——
  - `README.md:57`（Redis 监听地址，既有上下文行）、`README.md:196`（E2E 脚本示例参数，既有上下文行）
  - `docs/adr/DECISION-REGISTER.md:6`（既有行；本次改行不含该串）
  - `scripts/chat-panel-smoke.mjs:101` / `:109`（新脚本的 CDP 端口探测 `/json/version` 与 `/json`）
- **SECRET 独立正则复扫**（`sk-…` / `AKIA…` / `gh[pousr]_…` / `api-key|apikey|secret|token|password|passwd|passphrase|bearer` 赋值形态）：**0 命中**。
- **命名纪律复扫**：与仓库门禁断言⑤ 同规则（NFKC 折叠 + 隐形字符双态 + 零分隔连写 + 词首守卫）实扫 11 文件 **0 命中**；仓库门禁自身自检同步为绿（连写组 5/5「仅第三态可捕获」、分隔组 24/24 经前两态捕获、负对照 11 全不误伤）。

## 四、细分提交（步骤 4，4 笔）

**纪律**：逐文件显式 `git add <path>`（**无 `git add -A`**、**无 `--no-verify`**）；`core.hooksPath = scripts/githooks`，`pre-commit` 敏感信息扫描钩子（SECRET / PATH / USER 三规则，阻断式）在**每一笔均实跑并输出「✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）」**。

| # | sha | 提交信息 | 文件数 / 行数 |
|---|---|---|---|
| 1 | `286a337` | `feat(backend): DELETE /api/ai/history/{sessionId} 端点 + SISMEMBER 单点判存 + 11 用例` | 3 / +449 −3 |
| 2 | `ecd0395` | `feat(frontend): 新建对话（resetSession 双写修复+删除守卫）+ hover 复制（execCommand 回退）+ 骨架屏 + onClose 三处 stopped 抑制 + loadHistory 会话身份守卫` | 3 / +248 −14 |
| 3 | `157f9b9` | `test(scripts): chat-panel-smoke.mjs CDP 冒烟 13 断言 + clipboard 5s 上界 + 如实 SKIP` | 1 / +823 |
| 4 | `3d20de9` | `docs: M2 台账 R-100~110 登记与 R-100/R-101 已施工回写 + DC-17/18 登记 + README 索引同步 + 红队结论注记` | 4 / +77 −5 |

**逐笔文件面**（与题面指定的分组逐项一致）：

1. `286a337` = `AiController.java` + `RedisChatMemoryRepository.java` + `AiControllerHistoryDeleteTest.java`（提交信息注明：幂等 200 / Redis 不可用 503 / 只删 `chat:mem` 记忆面 / 不删 `ai:session:*` 与 `ai:run:*`；SISMEMBER 单点判存；11 用例）。
2. `ecd0395` = `frontend/src/api/ai.ts` + `AiPanel.vue` + `stores/ai.ts`（提交信息注明：承接红队 MAJOR-1 / MINOR-1 收口）。
3. `157f9b9` = `scripts/chat-panel-smoke.mjs`（提交信息注明：clipboard 5s 上界 + 未授权时如实 SKIP，不假绿）。
4. `3d20de9` = `README.md` + `docs/M2-排期计划.md` + `docs/README.md` + `docs/adr/DECISION-REGISTER.md`（承接复核 D1/F9 与后端 400/0/0/0（61 XML）、E2E 桩 29/29 终验；GLM 红队带条件 PASS 收口）。

**合计**：11 文件、+1597 −22（`git diff --stat 03a6cbe..3d20de9` 实取，11 行文件清单与工作树 11 条目**完全一致**，无多无少）。提交信息原文：`<COMMIT-GOV>/msg-p4-1-backend.txt` / `msg-p4-2-frontend.txt` / `msg-p4-3-scripts.txt` / `msg-p4-4-docs.txt`。

## 五、push 与 CI 守夜（步骤 5）

### 5.1 push

```
To <REPO-URL>
   03a6cbe..3d20de9  main -> main          # git push origin main，EXIT=0（未加 --force / --no-verify）
```

push 后 `git rev-parse HEAD origin/main` 两者同为 `3d20de96ee03040e583520d670bc55fd86d5c12c`；`git status -sb` ⇒ `## main...origin/main`（无 ahead / behind 计数）。

### 5.2 CI 守夜（匿名轮询，间隔 ≥90s，每轮 `runs` + `jobs` 各 1 次）

| 项 | 值 |
|---|---|
| run URL | <REPO-URL>/actions/runs/38071612710 |
| head_sha | `3d20de96ee03040e583520d670bc55fd86d5c12c`（= 本批 push 的 HEAD，匹配） |
| 触发 | `push`（`branch=main`）；created `2026-10-10T17:24:36Z` → updated `2026-10-10T17:28:09Z` |
| run 级 | **status = completed，conclusion = success** |

五 job（`/jobs` 端点实取，全部 completed）：

| job | status | conclusion | 起止（UTC） |
|---|---|---|---|
| 全历史脱敏扫描（报告制 · 不计门禁） | completed | **success** | 17:24:39 → 17:26:57 |
| 文档一致性门禁（check-docs）｜**阻断** | completed | **success** | 17:24:39 → 17:24:47 |
| 后端编译与单测 | completed | **success** | 17:24:39 → 17:26:16 |
| 前端类型检查与构建 | completed | **success** | 17:24:39 → 17:25:18 |
| E2E 全流程（29 用例 · 桩上游 + redis） | completed | **success** | 17:26:19 → 17:28:08 |

**停止条件已满足**（run 级 completed + ≥5 job 全 completed + 全 success）⇒ **闭环**。轮询节律：3 轮调用组（每轮 2 次请求、间隔 90s），共约 12 次匿名 API 调用，远低于 60/h 配额。取证：`<COMMIT-GOV>/p4-ci/runs-1.json`、`jobs-1.json`、`run-cur.json`、`jobs-cur.json`、`jobs-final.json`；轮询脚本 `<COMMIT-GOV>/p4-poll.sh`。

> 观察（非缺陷）：E2E job（id `114270329525`）在首轮 `jobs` 响应中尚未出现 —— 该 job 由上游 job 完成后再创建，故首轮 `NJOBS=4`、次轮起 `NJOBS=5`。守夜停止条件按「≥5 job」判定，未因首轮计数偏小而误判闭环。

## 六、HEAD 态门禁复跑（步骤 6）

push 后于 HEAD 干净态（`HEAD=3d20de9`、`git status --porcelain` 空、0 条）复跑文档一致性门禁：

```
文档一致性门禁（check-docs）
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 922 个路径，实扫文本 919 个；豁免 3 个）
         （规则自检：连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。      # EXIT=0
```

## 七、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无工作树漂移**：push、五 job、HEAD 复跑全绿，最终 `git status` 空。
2. **偏差①（预期内，非问题）**：红队旧锚 `ee1e68fd…` 不复现 —— 系红队收口修（MAJOR-1 / MINOR-1 / MINOR-3）之前的口径；本报告改记**新锚**（§1.4 三项 + 基线 HEAD `03a6cbe`）。
3. **偏差②（如实）**：本次是**唯一**一处非「纯追加」的内容改写 —— 降级节登记日期由 `2026-10-10` 订正为 `2026-10-11`（裁决日 `2026-10-10` 保留括号内），系题面步骤 2② 的明确指令；其余编辑均为行内保留原文词的改写或纯追加。
4. **偏差③（口径说明）**：§1.4 的 `git diff HEAD` sha256 锚是**步骤 1 时点（提交前）**取值；步骤 2 的文档微修会自然地改变该值（题面亦预告「以交付时点为准」），故报告不作前后同锚断言，只保留对拍用途。
5. **偏差④（归属项，随本次文档提交入库）**：`docs/M2-排期计划.md` 中「AI 对话正文 Markdown / 图表渲染 暂缓登记」小节（工作树既有 hunk，即复核 **F1（MAJOR·流程/归属）** 指出的「文件集合 6 → 7」那一笔）本次**随文档提交一并入库**。该 hunk 的**提出方非本单**（施工方声明未动台账），内容已独立复核（门禁 6/6、脱敏复扫 0 命中）；指挥官若判定其不应入库，须单独 `revert` 该小节 hunk。
6. **遗留（非本单授权面，已登记）**：`R-107`（上下文压缩）/ `R-108`（轨迹留痕）/ `R-109`（重新生成）三行于 `2026-10-11` 登记降级为**低优先·暂缓·材料备查**；`DC-17` / `DC-18` 为纪律 / 口径级登记，**保留生效**；红队 **MINOR-2**（引入鉴权批须联动会话归属校验）已按要求并入 `R-108` 注记；`R-110`（确认门规则化）仅**预留编号**，本次不落行。
7. **观察（假阳性，沿用既有登记）**：环回地址 `127.0.0.1` 命中 5 处按题面明确保留；报告自身含 GitHub 公开组织名与 run 链接（CI 取证必需），会被独立扫描器按「用户名形态」报出，属已知假阳性（同第 2 / 3 期登记）。

## 八、本批产出物（`<COMMIT-GOV>` 根）

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 4 期**条目） |
| 四笔提交信息原文 | `<COMMIT-GOV>/msg-p4-{1-backend,2-frontend,3-scripts,4-docs}.txt` |
| 脱敏扫描器（复用第 3 期） | `<COMMIT-GOV>/desense-scan.mjs`（植入对照 23/12 + 实盘 11 文件） |
| CI 守夜脚本与取证 | `<COMMIT-GOV>/p4-poll.sh`、`<COMMIT-GOV>/p4-ci/`（runs-1 / jobs-1 / run-cur / jobs-cur / jobs-final 五份 JSON） |
| 新哈希锚（三项 + 基线 HEAD） | 见本报告 §1.4（值内联，无独立文件） |


---

# ai-example 合流项目 · 提交官报告（第 5 期：docs-only 合并批「治理登记 + E2E 配方 + 失物招领去向表 + 过程报告补档 24 份」）

执行者 = Kimi K3（Kimi Code 提交官子智能体），授权来源 = 本站指挥官任务卡（2026-10-11，用户已裁决呈批 1/2/4~13 与两轮停手裁决均为「方案 A」）。

## 一、批次性质与豁免声明（DC-20）

- 批次性质：**纯文档批**（只改 `docs/` 与仓库根 `README.md`，零代码改动）。按 **DC-20** 引用小批次红队豁免：**本批豁免红队审查环节**；**提交官的机械验收与脱敏终扫不豁免**（本条声明即为 DC-20 要求的「引用豁免须在提交报告注明」）。
- 纪律遵守：全程**未读任何 `.env` 文件**；第 4 笔提交**未使用 `--no-verify`**（钩子实跑，以钩子自带误报程序放行，见 §六）；除两轮裁决明确授权的改动外**未改任何其他文件内容**。
- 事实源时点：开工 HEAD = `origin/main` = `3d20de9`（工作树首次 `git status --porcelain` 全文见 §二，共 **29** 行）。

## 二、全量归属（第 1 步：硬性）

`git status --porcelain` 实取 = **29 行**，逐行归属与任务卡预期**逐条相符、零预期外条目**：

| 类别 | 行数 | 明细 |
|---|---|---|
| `M` 已跟踪修改 | 4 | `README.md`、`docs/README.md`、`docs/adr/DECISION-REGISTER.md`、`docs/M2-排期计划.md` |
| `??` 未跟踪 | 25 | `docs/E2E-桩模式校准配方.md` ×1 + `docs/evidence/补档-*.md` ×24 |

- `docs/evidence/` 下未跟踪文件经 `git ls-files --others --exclude-standard docs/evidence/` 实取 = **24 个，且全部匹配 `补档-`**（无其他未跟踪件）⇒ 第 4 笔以 `docs/evidence/补档-*.md` 显式展开暂存是安全口径。
- 提交前 `git diff --numstat`：`README.md` 1/1、`docs/README.md` 13/1、`docs/adr/DECISION-REGISTER.md` 5/0、`docs/M2-排期计划.md` 20/0（与苞1施工报告自述一致）。

## 三、脱敏终扫（第 2 步：不豁免）

扫描面 = `git diff` 全文 + 24 个新文件全文（语料 **7487 行** = diff 65 行 + 新文件 7422 行）。**两轮**扫描：第一轮发现 2 处预期外命中（§四），按裁决改产物后**第二轮全量重扫**，最终计数如下。

| 类别 | 计数（新文件 / diff） | 甄别结论 |
|---|---|---|
| 独立大写 `<USER>` | **0 / 0** | 上轮 1 处（`补档-索引-2026-10-11.md:65`）已按裁决改写为 `<USER>`，**归零** |
| 独立 `[Oo]bby`（词边界） | **0 / 0** | 上轮 4 处 = 2 处真实（索引 `:65` 大写、`:154` 小写）+ 2 处 `tinyglobby` 依赖名假阳性；真实 2 处已改写，**归零** |
| `<LOCAL_WORK>`、`<HOST>`、`DESKTOP-`/`LAPTOP-`、`169.254.`、私钥头 `-----BEGIN … PRIVATE KEY-----` | 0 | 无命中 |
| 盘符形态 `[A-Za-z]:[\\/]` | 110 / 0（含 `https://` 的 `<DRIVE>/` 假阳性）；**剔除 scheme 后 4 行** | 4 行全部是**省略号截断的扫描器种子/正则描述文本**（`<DRIVE>/…` / `<DRIVE>/…` / `<DRIVE>/…` / `<DRIVE>/…`，落点 `补档-COMMITGOV-提交报告.md:74`、`:100`、`补档-NUMSPEC-红队报告-GLM-小批.md:188`），**无真实绝对路径**；同形串已存在于基线已跟踪的 `scripts/ci/check-docs.mjs:89` |
| 内网 IP | `192.168.` 1、`10.x` 2、`172.16-31.` 1 / diff 0 | 全部在 `补档-COMMITGOV-提交报告.md:74,76,275`，为**扫描器种子清单与保守过匹配说明**（`10.x`、`192.168.x`、`172.16.x`、`v10.2.3`），**无真实地址** |
| 疑似 key（`sk-`/`ghp_`/`AKIA`/`AIza`/`Bearer `/`rk-`） | 共 10 行 / diff 0 | 全部是报告表格内的**正则规则字面量**（如 `sk-[A-Za-z0-9]{12,}`、`ghp_[A-Za-z0-9]{20,}`、`AKIA[0-9A-Z]{12,}`），**无密钥值**；另有 1 处假阳性 `rk-` 落在 `--network-alias` 内 |
| 长 hex（32/40/64） | 若干 | 40 位 = git SHA；64 位 = `git diff HEAD`/测试文件的 SHA-256 锚钉；32 位 = CI 日志内 Docker 容器名·网络 ID 与 `ci.yml` md5。**无密钥** |
| 命名纪律禁词（`main` + 分隔符 + `v2` 任意大小写、连写 `<OLD_REPO_DIR_CONCAT>`） | **0 / 0** | 全批零命中 |
| 灰区豁免 `<OLD_REPO_DIR_CONCAT>-db-backup.mv.db` | 0 处提及 | 本批未触发该豁免 |
| R-129 新增行（第 5 笔） | **0** | 按要求**行内不写组织名原文**，故零命中（§五） |

**允许面实测计数**（与任务卡允许清单一致）：`<USER>` 31 处（`补档-CI-失败诊断-2026-10-09.md` 13 + `补档-COMMITGOV-提交报告.md` 16 + `补档-索引-2026-10-11.md` 2）、`127.0.0.1` 33 处、`ci-stub-placeholder` 10 处。

## 四、两轮「停手汇报 → 方案 A 裁决」留痕（如实）

本批**两次触发提交官的硬性停手规则**，两次均由指挥官裁决为**方案 A**，留痕如下。

### 4.1 第一轮：脱敏终扫发现 2 处用户名命中 → 裁决「方案 A：改产物，不扩允许面」

| 项 | 内容 |
|---|---|
| 命中 1 | `docs/evidence/补档-索引-2026-10-11.md:65` 独立大写 `<USER>`（原文：``用户名 2 处（`<USER>` 独立词 + 被当作扫描 token 引述的小写同形词）``） |
| 命中 2 | `docs/evidence/补档-索引-2026-10-11.md:154` 独立小写 `` `<USER_FRAGMENT>` ``（原文：``另核：本批全部 `<USER_FRAGMENT>` 出现（29 处）**逐一落在 `<USER>` 内**，无一为独立用户名形态。``） |
| 成因 | 苞2 §2.4 的「用户名 2 处均改 `<USER>`」对**被脱敏的补档副本**成立（`补档-COMMITGOV-提交报告.md:108` 实为 `<USER>`），但苞2**自建的索引文件不在脱敏器作业面内**，它在描述脱敏工作时把被脱敏的词原样写出 |
| 改写后 `:65` | `用户名 2 处（独立用户名 1 处 + 被当作扫描 token 引述的小写同形词 1 处，均改写为 \`<USER>\`）` |
| 改写后 `:154` | `另核：本批全部用户名小写形态出现（29 处）**逐一落在允许的公开组织名内**，无一为独立用户名形态。` |
| 复扫 | 改写后该文件独立 `<USER>`/`<USER_FRAGMENT>` **0 命中**；`<USER>` 2 处保留（允许面） |

### 4.2 第二轮：第 4 笔被 `pre-commit` 钩子阻断 → 裁决「方案 A：放行公开组织名 + 留痕后缀 + R-129 登记」

- 阻断原文：`✖ [pre-commit] 暂存区命中 31 处敏感信息，提交已阻止：`
- 命中构成：**31 处，规则全部为 `[USER   个人用户名]`，命中片段全部为同一 token `<USER>`**（`PATH` 0、`SECRET` 0）。分布：`补档-CI-失败诊断-2026-10-09.md` 13 处、`补档-COMMITGOV-提交报告.md` 16 处、`补档-索引-2026-10-11.md` 2 处。
- **根因**：`git config user.name = <USER>`；`scripts/githooks/scan-lib.sh:117-132` 把 `user.name` 按 `[._@空格-]` 拆成 `<USER_FRAGMENT>` / `<USER_FRAGMENT_2>` 两个 ≥4 字符片段并生成词边界正则 ⇒ 正文中**任何 `<USER>` 必然命中**。该规则**无法区分「公开组织名」与「个人用户名」**，钩子亦**无 token 级允许清单**（仅 `AI_SECRETS_ALLOW=1` / `--no-verify` 两个逃生口）。
- 语境性质（提交官抽取的代表性原文，详见本报告期的取证）：31 处**全部是「仓库自己的公开地址」** —— GitHub REST API 端点（`api.github.com/repos/<org>/…` 与无 host 的 `GET /repos/<org>/…`）12 处、`actions/runs` 链接 5 处、远端地址引用 4 处、`git push` 回显（`To https://github.com/<org>/<repo>.git`）3 处、报告标题仓库标识 1 处、`raw.githubusercontent.com` URL 1 处、**元叙述**（报告自述"为何保留组织名"）5 处。
- 冲突性质：**内容层与指挥官允许清单一致**（`<USER>` = 公开组织名，明示允许），**仅本机 pre-commit 与之一致性冲突**；CI 的 `docs` job（`check-docs.mjs`）**不检查用户名**、`scan-history` job 为**报告制非阻断** ⇒ 该 block 为**本机局部**。
- 裁决动作（本次执行）：钩子自带误报程序**放行**（**未用 `--no-verify`**）+ **提交信息留痕后缀** + **R-129 登记改规则**（§五、§六）。

## 五、细分提交（第 3 步：5 笔，逐文件显式 add，禁 `--no-verify`）

逐笔均以**显式逐文件 `git add <具体路径>`** 暂存（全程未用 `git add -A` / `git add .`），暂存后先核对清单再 `git commit`。基线 `3d20de9` → 终态 `372da77`。

| # | SHA | 主题 | 文件 | 增/删 |
|---|---|---|---|---|
| 1 | **`398357d`** | `docs: DC-19/20/21 治理立法登记（报告署名/小批次红队豁免/issue 生命周期）+ 过程纪律节 + 根 README 口径同步` | 3（`README.md` / `docs/README.md` / `docs/adr/DECISION-REGISTER.md`） | 19/2 |
| 2 | **`d0e8164`** | `docs: E2E 桩模式校准配方入库（交接知识沉淀，呈批 8）` | 1（`docs/E2E-桩模式校准配方.md`，新建） | 13/0 |
| 3 | **`d570428`** | `docs(M2): 失物招领 18 项登记去向表 R-111~R-128（呈批 11）` | 1（`docs/M2-排期计划.md`） | 20/0 |
| 4 | **`0266ed6`** | `docs(evidence): 本周期过程报告脱敏补档 24 份（对话面板包/数字规格小批/CI/提交治理/T3-5/T3b，呈批 1）`（**含留痕后缀，见 §六**） | 24（`docs/evidence/补档-*.md`） | 7409/0 |
| 5 | **`372da77`** | `docs(M2): R-129 登记 pre-commit 钩子公开组织名误报改规则（用户裁决 A 配套项）` | 1（`docs/M2-排期计划.md`） | 1/0 |

**批次合计**：29 个路径、**7462 增 / 2 删**（唯一 2 处删除 = `docs/README.md:15` 决策行由 `DC-01~18` 改写为 `DC-01~21`，与苞1报告声明一致）。

### 5.1 第 4 笔的留痕后缀（提交信息原文实取，第二段）

> 留痕：pre-commit USER 规则对公开组织名 <USER>×31 误报（本机 git user.name 与公开组织名同名所致），内容与脱敏政策允许面一致，依 scripts/githooks/README §4 误报程序放行。

### 5.2 第 5 笔 = R-129 登记（追加到 `docs/M2-排期计划.md` 去向表表体末尾）

- 落点：紧接既有末行 `R-128` 之后、表体闭合空行之前；**只追加、零删**（`git diff --numstat` 实取 = **1 增 / 0 删**）。
- 行格式对齐苞1 的 `R-111`~`R-128` 行（主题列 + 「**已登记·待排期**（2026-10-11 登记，用户裁决 A 配套项）」状态列）。
- 主题：`pre-commit` 钩子 `USER` 规则对公开组织名误报 —— 本机 `git config user.name` 与公开 GitHub 组织名同名，致远端 URL / API 端点 / run 链接等 **31 处地址引用**全被拦为「个人用户名」；状态行注明应改 `scripts/githooks/scan-lib.sh` 把公开组织名排除出用户名判定，落地须另立项并同步 `docs/README.md` 命名纪律节与 `scripts/githooks/README.md` §3/§5.1。
- **行内未写出组织名原文**（按要求），故该笔钩子实跑**通过**（`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`），复扫 0 命中。

## 六、第 4 笔钩子放行留痕（原样保留）

**执行命令**（**未用 `--no-verify`**）：

```sh
AI_SECRETS_ALLOW=1 git commit -m "docs(evidence): 本周期过程报告脱敏补档 24 份（…，呈批 1）" \
  -m "留痕：pre-commit USER 规则对公开组织名 <USER>×31 误报（…），依 scripts/githooks/README §4 误报程序放行。"
```

**钩子放行输出原文摘录**（先阻断、后放行两次实跑，均为原文）：

```
✖ [pre-commit] 暂存区命中 31 处敏感信息，提交已阻止：
   docs/evidence/补档-CI-失败诊断-2026-10-09.md:28   [USER   个人用户名]  <USER>
   docs/evidence/补档-CI-失败诊断-2026-10-09.md:3    [USER   个人用户名]  <USER>
   …（共 31 行，全部形如 `<路径>:<行号>  [USER   个人用户名]  <USER>`）…
   docs/evidence/补档-索引-2026-10-11.md:154         [USER   个人用户名]  <USER>
```

```
⚠ [pre-commit] 逃生口已启用（AI_SECRETS_ALLOW=1）：以下 31 处命中仅告警，本次提交被放行
   …（同上 31 行）…
```

> 钩子该分支固定输出的收尾行（依 `scripts/githooks/pre-commit` 源码）：`⚠ 放行即人为豁免：请在提交说明中留痕，并按 DC-08 在后续提交清理。` —— 本批已按此要求在第 4 笔提交信息内留痕（§5.1），并把「清理 = 改规则」立项为 **R-129**（§5.2）。
>
> 独立性复核：提交官另以**钩子自带引擎**对第 4 笔的 diff 复算（`git diff d570428 0266ed6 -U0 … | scan_engine_stream | sort -u`，只读），得 **31 处命中、规则全为 `USER`、token 全为 `<USER>`**，与钩子实跑输出**逐条一致**。

## 七、HEAD 态门禁复跑（第 4 步：全部提交后、push 前）

5 笔提交完成、HEAD = `372da77`、工作树干净（`git status --porcelain` 空）后执行：

```
文档一致性门禁（check-docs）
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 947 个路径，实扫文本 944 个；豁免 3 个）
         （规则自检：连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。        # EXIT=0
```

- **947 个路径** = 基线 922 + 本批新增 25（24 份补档 + 1 份 E2E 配方）⇒ 新文件**已 tracked**，断言⑤ 扫描面**全覆盖**（苞2 曾以自建等口径脚本预验证同一结论）。
- 补充：**暂存态**（第 4 笔提交前、24 文件仅在索引内）时复跑亦为 947 路径 **6/6 全绿**，与 HEAD 态一致。

## 八、push 与 CI 守夜（第 5 步）

### 8.1 push

| 项 | 值 |
|---|---|
| push 前 HEAD | `372da7773fc9520b892e9ee822f2a774fe4657c9` |
| push 前 `origin/main` | `3d20de96ee03040e583520d670bc55fd86d5c12c` |
| 命令 | `git push origin main`（exit 0） |
| 输出 | `To <REPO-URL>` / `3d20de9..372da77  main -> main` |
| push 后 `origin/main`（fetch 实取） | **`372da7773fc9520b892e9ee822f2a774fe4657c9`**（= HEAD，无 ahead/behind） |

### 8.2 CI 守夜（匿名轮询，`curl --ssl-no-revoke`，间隔 75s，每轮 `runs` + `jobs` 各 1 次）

| 项 | 值 |
|---|---|
| run id | **`38104935913`** |
| run URL | <REPO-URL>/actions/runs/38104935913 |
| head_sha | `372da7773fc9520b892e9ee822f2a774fe4657c9`（= 本批 push 的 HEAD，**匹配**） |
| 触发 | `push`（`branch=main`）；`run_attempt=1`；created `2026-10-11T02:22:21Z` → updated `2026-10-11T02:25:27Z` |
| run 级 | **status = completed，conclusion = success** |

五 job（`/jobs` 端点实取，**全部 completed / 全部 success**）：

| job | id | status | conclusion | 起止（UTC） |
|---|---|---|---|---|
| 全历史脱敏扫描（报告制 · 不计门禁） | `114368316781` | completed | **success** | 02:22:24 → 02:24:44 |
| 前端类型检查与构建 | `114368316845` | completed | **success** | 02:22:24 → 02:23:18 |
| 文档一致性门禁（check-docs）｜**阻断** | `114368316862` | completed | **success** | 02:22:24 → 02:22:30 |
| 后端编译与单测 | `114368316956` | completed | **success** | 02:22:24 → 02:23:34 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114368522325` | completed | **success** | 02:23:37 → 02:25:26 |

**停止条件已满足**（run 级 completed + ≥5 job 全 completed + 全 success）⇒ **闭环**。轮询节律：**4 轮**调用组（每轮 2 次请求，间隔 75s），共约 **8 次**匿名 API 调用，远低于 60/h 配额。取证：`<COMMIT-GOV>/ci-run-372da77.json`、`<COMMIT-GOV>/ci-jobs-372da77.json`；轮询脚本 `<COMMIT-GOV>/ci-poll.mjs`。

> 观察（非缺陷，与既有报告同现象）：**E2E job（id `114368522325`）晚出现** —— 首轮 `jobs` 响应仅 4 个（`NJOBS=4`，E2E 尚未创建），第二轮起为 5 个。守夜按「≥5 job」判定停止条件，**未因首轮计数偏小而误判失败或闭环**。

## 九、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无工作树漂移**：5 笔提交、门禁 6/6、push、五 job 全绿，终态 `git status --porcelain` 为空、`origin/main == HEAD`。
2. **偏差①（预期外被发现，已收口）**：苞2 自建索引 `补档-索引-2026-10-11.md` 含 2 处独立用户名 token（`:65` / `:154`），系**脱敏器作业面未覆盖自撰索引**所致；已按裁决方案 A 改写（§4.1），复扫归零。**建议**：后续「脱敏入库」类批次把**自建索引/汇总件**一并纳入脱敏作业面。
3. **偏差②（结构性误报，已登记）**：本机 `pre-commit` 的 `USER` 规则对公开组织名 `<USER>` 长期误报（根因 = `git config user.name` 与组织名同名），本批以钩子自带误报程序放行并留痕；**已立项 `R-129`**（改 `scan-lib.sh` 把公开组织名排除出用户名判定）。**遗留**：R-129 落地前，任何含仓库公开 URL 的提交都会再次触发该 block。
4. **偏差③（口径说明）**：`R-111`~`R-128` 与 `R-129` 分属**两笔提交**（第 3 / 第 5 笔），因 R-129 系第二轮裁决的配套项、形成于本批执行中途；两者在去向表内**相邻且编号连续**（`R-110` 仍为预留号，`R-102`/`R-103` 留空号口径不变）。
5. **遗留（非本批授权面）**：`docs/M2-排期计划.md` 的 `R-107`/`R-108`/`R-109`（低优先·暂缓·材料备查）与 `DC-17`/`DC-18`（保留生效）状态未变；`R-110` 仍仅预留编号。

## 十、本批产出物（`<COMMIT-GOV>` 根）

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 5 期**条目） |
| CI 守夜脚本 | `<COMMIT-GOV>/ci-poll.mjs` |
| CI 取证 JSON | `<COMMIT-GOV>/ci-run-372da77.json`、`<COMMIT-GOV>/ci-jobs-372da77.json` |
| 施工报告（本批上游） | `<SCRATCH>/docs-gov-pkg/施工报告-苞1.md`、`<SCRATCH>/docs-gov-pkg/施工报告-苞2.md` |
| 钩子放行/阻断输出留痕 | 内联于本报告 §六（无独立文件；裁决链见 §四） |

# ai-example 合流项目 · 提交官报告（第 6 期：docs 批「追溯审查报告入库 + R-130 / R-131 登记」）

执行者 = Kimi K3（Kimi Code 提交官子智能体），授权来源 = 本站指挥官任务卡（2026-10-11，呈批 3 追溯收口）。

## 一、批次性质与范围

- 批次性质：**纯文档批**（3 个路径全在 `docs/`，**零代码改动**）。
- 事实源时点：开工 `git status --porcelain` **为空**；基线 HEAD = `origin/main` = `372da77`。
- 纪律：全程**未读任何 `.env`**；**未用 `--no-verify`**；`git add` **全程显式逐文件列名**（3 个路径，未用 `-A` / `.`）；源报告（仓库外）**只读未改**。

## 二、脱敏入库（步骤 1）

| 项 | 值 |
|---|---|
| 源（仓库外） | `<SCRATCH>/chat-panel-pkg/追溯审查报告-DS-V4-Pro.md`（**197 行，源文件未改**） |
| 落点 | `docs/evidence/补档-CHATPANEL-追溯审查报告-DS-V4-Pro.md`（**199 行**） |
| 行数差 | **+2** = 页首标识行 1（`> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）`，与本目录既有 24 份补档同款）+ 页首空行 1 |
| 正文改动 | **5 行定点改写**（逐行 diff 核对；除该 5 行与页首 2 行外与源**逐字节一致**） |

**脱敏计数 = 6 处**：

| 类别 | 处数 | 改写后 |
|---|---|---|
| 盘符绝对路径 | 1 | `<SCRATCH>/chat-panel-pkg/`（原「审查对象」行的仓库外目录） |
| 裸主仓目录名（命名禁词形态） | 1 | `<MAIN_REPO>`（原「审查对象」行的仓库名） |
| 提交者邮箱 | 4 | `<USER_EMAIL>`（4 笔提交作者栏的引述） |
| 主机名 / 内网 IP / 密钥 / 独立大写 `<USER>` / 独立小写 `<USER_FRAGMENT>` | 0 | 无命中 |

- **保留面**（按既定口径）：公开组织名 8 处（分落 6 行，**全部为 `<USER>` 形态，无独立 `<USER>` / `<USER_FRAGMENT>` 词**）；`127.0.0.1` 与 `ci-stub-placeholder` 的保留口径不变（**本件后两项均未出现**）。
- 页首原署名行（`执行者=DeepSeek V4 Pro，授权来源=…`）**原样保留，未改一字**。
- 复扫（独立 ripgrep：盘符 / 命名禁词三态 / 邮箱 / 内网 IP / 密钥前缀五模式）：**全部 0 命中**。

## 三、索引登记（步骤 2）

`docs/evidence/补档-索引-2026-10-11.md` **末尾追加 1 行**（只追加、零删；`git diff --numstat` 实取 = **2 增 / 0 删**，含 1 个 Markdown 分隔空行）。登记内容 = 源→落点 + 源/入库行数 + 一句话主题 + 脱敏计数 6 处 + 定性为「对话面板包源目录补档组的第 8 份」（对齐 §1.1 既有 7 份）。

## 四、M2 去向表登记（步骤 3，`R-130` / `R-131`）

- 落点：紧接既有末行 `R-129` 之后、表体闭合空行之前；**只追加、零删**（`git diff --numstat` 实取 = **2 增 / 0 删**）。
- 列格式对齐 `R-111`~`R-129`（主题列 + 「**已登记·待排期**（2026-10-11 登记，追溯审查批）：本批只登记，不施工、不排期占位」状态列）。
- **`R-130`** = 追溯 **N1**：`loadHistory` 会话身份守卫未覆盖 `restoreSuspendedSnapshot` 内部 `runsCurrent` await 窗口（旧会话挂起卡 / `activeForm` 可写入新会话；严重度 **MINOR 倾向 MAJOR**；去向 = 随对话时序包或独立小批修）。
- **`R-131`** = 追溯 **N2**：`RedisChatMemoryRepository.deleteByConversationId` 的 `DEL` 与 `SREM` 非原子，两步间崩溃留 stale 成员、**后果有界**（惰性清理兜底）；**MINOR**，建议登记不修。
- **行内未写盘符路径与组织名原文**（按要求以「公开组织名」抽象表述）⇒ 该笔内容对钩子**零命中**（§七 的 8 处命中**全部来自新入库报告**，与本文件无关）。
- 如实留痕：本批两行**未附批次注记 blockquote**（`R-111`~`R-128` 批有、`R-129` 无；本批沿 `R-129` 单行先例，只落行）。

## 五、门禁（步骤 4）

| 时点 | 断言 | 扫描面 |
|---|---|---|
| **暂存前** | **6/6 全绿**（EXIT=0） | `git ls-files` **947** 路径，实扫文本 944，豁免 3 |
| **暂存后**（新文件已入索引） | **6/6 全绿**（EXIT=0） | **948** 路径，实扫文本 945 ⇒ 新入库件**已纳入断言⑤ 命名纪律扫描面**，非「未跟踪盲区」 |

两次实跑均含规则自检：连写组 5/5 例「仅第三态可捕获」、分隔组 24/24 例经前两态捕获、负对照 11 全不误伤。

## 六、提交（步骤 4，1 笔）

| # | SHA | 主题 | 文件 | 增/删 |
|---|---|---|---|---|
| 1 | **`d897096`** | `docs(evidence): 追溯审查报告入库 + R-130/R-131 登记（呈批 3 追溯收口）`（**含留痕后缀，见 §七**） | 3（`docs/evidence/补档-CHATPANEL-追溯审查报告-DS-V4-Pro.md`（新建）/ `docs/evidence/补档-索引-2026-10-11.md` / `docs/M2-排期计划.md`） | **203 / 0** |

- 提交前 `git status --porcelain` = **3 条**（`M` ×2 + `??` ×1），**逐条归属本批、零预期外条目**。
- 提交后工作树 `git status --porcelain` **为空**。

## 七、钩子处置留痕（原样保留，**未用 `--no-verify`**）

**第一次实跑 = 被阻断**（原文摘录）：

```
✖ [pre-commit] 暂存区命中 8 处敏感信息，提交已阻止：
  docs/evidence/补档-CHATPANEL-追溯审查报告-DS-V4-Pro.md:32  [USER   个人用户名]  <USER>
  …（共 8 行，行号 32 / 33 / 34 / 35 / 37 / 39 / 45 / 79；规则**全部**为 `USER`，`PATH` 0、`SECRET` 0）…
  规则: [SECRET 疑似密钥/令牌] [PATH 本机绝对路径] [USER 个人用户名]
```

- **命中构成**：8 处**全部**是公开组织名（= 本机 `git config user.name` 同名）被 `USER` 规则词边界匹配**误报**，根因与已登记的 **`R-129` 完全同一**；命中项与用户 2026-10-11 裁决的**允许面一致**。
- **处置**：依任务卡与 `scripts/githooks/README` §4，以 `AI_SECRETS_ALLOW=1` 放行（**非 `--no-verify`**），提交信息追加留痕行；第二段原文：

> `pre-commit USER 规则对公开组织名误报，依 scripts/githooks/README §4 放行（用户 2026-10-11 裁决口径）`

- 钩子放行分支的固定收尾提示（出处 `scripts/githooks/pre-commit`）：`⚠ 放行即人为豁免：请在提交说明中留痕，并按 DC-08 在后续提交清理。` ⇒ 留痕已随 §六 的提交信息落地；「清理 = 改规则」的载体即已登记的 `R-129`（**本批未改动钩子**）。
- **交界声明**：本批命中的规则**只有 `USER` 一种**，**无 `USER` 以外规则、无新形态** ⇒ **未触发**任务卡的「停手汇报」分支。

## 八、push 与 CI 守夜（步骤 4）

### 8.1 push

| 项 | 值 |
|---|---|
| push 前 HEAD / `origin/main` | `372da7773fc9520b892e9ee822f2a774fe4657c9` |
| 命令 | `git push origin main`（exit 0） |
| 输出 | `To <REPO-URL>` / `372da77..d897096  main -> main` |
| push 后（fetch 实取） | HEAD = `origin/main` = **`d8970965bea216e62c2c64e1a1dc289427401570`**，无 ahead/behind |

### 8.2 CI 守夜（匿名轮询，`curl --ssl-no-revoke`，间隔 30s，每轮 `run` + `jobs` 各 1 次）

| 项 | 值 |
|---|---|
| run id | **`38105553177`** |
| run URL | <REPO-URL>/actions/runs/38105553177 |
| head_sha | `d8970965bea216e62c2c64e1a1dc289427401570`（= 本批 HEAD，**匹配**） |
| 触发 / 时点 | `push`（`branch=main`）；created `2026-10-11T02:33:42Z` → updated `2026-10-11T02:37:01Z` |
| run 级 | **status = completed，conclusion = success** |

五 job（`/jobs` 端点实取，**全部 completed / 全部 success**）：

| job | id | status | conclusion | 起止（UTC） |
|---|---|---|---|---|
| 文档一致性门禁（check-docs）｜**阻断** | `114370211861` | completed | **success** | 02:33:46 → 02:33:52 |
| 前端类型检查与构建 | `114370212031` | completed | **success** | 02:33:46 → 02:34:38 |
| 后端编译与单测 | `114370211996` | completed | **success** | 02:33:46 → 02:34:53 |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114370212056` | completed | **success** | 02:33:46 → 02:35:41 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114370405656` | completed | **success** | 02:34:56 → 02:37:01 |

**停止条件已满足**（run 级 completed + **5 job 全 success**）⇒ **闭环**。轮询 **8 轮**（每轮 2 次匿名 API 调用，共 **16 次**，远低于 60/h 配额）。取证：`<COMMIT-GOV>/ci-run-d897096.json`、`<COMMIT-GOV>/ci-jobs-d897096.json`；轮询脚本 `<COMMIT-GOV>/ci6/poll.sh`，逐轮日志 `<COMMIT-GOV>/ci-poll-d897096.log`（清洗版，仅本批成功轮）+ `<COMMIT-GOV>/ci6/poll-raw-both-attempts.log`（原样，含首版脚本空转段）。

> 观察（与第 5 期同现象，**非缺陷**）：**E2E job 晚出现** —— iter 1~3 的 `jobs` 响应仅 **4** 个（E2E 尚未创建），iter 4 起为 **5** 个。守夜按「run completed **且** job ≥ 5」判定停止条件，**未因早期计数偏小而误判失败或提前闭环**。

## 九、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无提交时刻工作树漂移**：1 笔提交、门禁 6/6 × 2、push、五 job 全绿，提交后 `git status --porcelain` 为空、`origin/main == HEAD`。
2. **偏差①（结构性误报，已按裁决口径放行）**：本机 `pre-commit` 的 `USER` 规则对公开组织名 8 处误报，根因与处置同第 5 期；**已由 `R-129` 登记改规则**。**遗留**：`R-129` 落地前，任何含公开组织名的入库件都会再次触发该 block。
3. **偏差②（登记形态口径）**：`R-130` / `R-131` 两行**未附批次注记 blockquote**（沿 `R-129` 单行先例）；若后续批次要求「每批一行注记」，本批需补。
4. **偏差③（提交官自查发现的脚本缺陷，不涉仓库产物）**：CI 轮询脚本**首版**把 Git Bash 挂载形路径直接交给 Node 解析，Node 按当前盘拼接后报 `ENOENT`（相对路径误判）；**已改为 `cd` 到取证目录 + 相对文件名**重跑。首版仅空转数轮即被我主动终止，**未影响任何仓库文件与最终 CI 结论**。
5. **批 B 在途改动（非本批产出，实读留痕）**：push 完成后再看工作树，出现 `M backend/src/main/java/com/example/configmgr/config/AppProperties.java` 与 `?? backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java` —— 系另一棒（批 B / K2.8）在途施工。本批**未 add、未 commit、未触碰**（本批提交时该两文件尚不在工作树内）。如实提醒：本批 push 的 `d897096` **不含**该两文件。

## 十、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 6 期**条目） |
| 入库件（仓库内） | `docs/evidence/补档-CHATPANEL-追溯审查报告-DS-V4-Pro.md` |
| 索引登记（仓库内） | `docs/evidence/补档-索引-2026-10-11.md`（末行追加） |
| M2 登记（仓库内） | `docs/M2-排期计划.md`（去向表 `R-130` / `R-131`） |
| CI 取证 | `<COMMIT-GOV>/ci-run-d897096.json`、`<COMMIT-GOV>/ci-jobs-d897096.json`、`<COMMIT-GOV>/ci6/`（`poll.sh` / `poll.log` / `last.txt` / `run.json` / `jobs.json`） |
| 源报告（仓库外，**未改**） | `<SCRATCH>/chat-panel-pkg/追溯审查报告-DS-V4-Pro.md` |

# ai-example 合流项目 · 提交官报告（第 7 期：M1 出口评审材料 v1.3 修订 + 签署闭环）

执行者 = Kimi K3（Kimi Code 提交官子智能体），授权来源 = 本站指挥官任务卡（2026-10-11，M1 出口评审签署 + 提交闭环）。

## 一、批次性质与范围

- 批次性质：**单文件文档批**（`docs/M1-出口评审材料.md`，**零代码改动**）。
- 事实源时点：开工时该文件**已带 v1.3 修订在工作树内**（未暂存；`git diff` = 34 增 / 15 删）；基线 HEAD = `origin/main` = `d897096`（左右计数 0/0）；工作树另有**批 B 在制品**（15 ` M` + 4 `??`，其中 1 个 ` M` 即本批唯一文件）。
- 纪律：`git add` **只显式列名本批唯一文件**（未用 `-A` / `.`）；全程**未读任何 `.env`**；**未用 `--no-verify`**；「只许碰一个文件」经 `git show --stat` 复核成立（提交实含 **1** 个文件）。
- 授权边界：只做任务卡列明的 4 项填写（出口结论、签署口径注记、签字区四行、备注栏）；**§一 基本信息三行占位（评审日期 / 主持人 / 签字人）与 §七 修订记录不在填写清单内，本批未改**（如实留痕见 §七-2）。

## 二、签署落笔（步骤 1）

文件：`docs/M1-出口评审材料.md`（v1.3，**117 行 → 117 行，行数不变**）；换行按任务卡要求归一为 **CRLF**（工作树 117 CRLF；仓库侧由 `core.autocrlf=true` 归 LF，`git diff` **未出现整文件重写**——实取 41 增 / 22 删，且 blob 侧 CRLF/LF 计数与 HEAD 版一致，证明仅变更内容、未变更换行）。

| # | 落点 | 填写内容 |
|---|---|---|
| 1 | §六 **出口结论**行（原 `□ 通过`） | **`☑ 通过`**（「有条件通过」「不通过」两框保持 `□`） |
| 2 | §六 签署基线提示**下方新增 1 行** blockquote | 签署口径注记：签字栏已按受审代码态口径完成四栏签署；签署口径＝按受审代码态 `61286e2` 评审；核验时点（2026-10-11）当前 HEAD `d897096` 的横切结论已独立复现；材料所引后端 `@Test` 384（61 文件）为 v1.3 定稿时点值，并行批次推进中会继续增长，属增量、不影响出口结论 |
| 3 | 签字表 **业务方**行 | 姓名 `用户本人`｜签字 `用户本人（2026-10-11 当场授权 K3 代行签署，存档新增规则⑬）`｜日期 `2026-10-11` |
| 4 | 签字表 **技术负责人**行 | 姓名 `Kimi K3`｜签字 `Kimi K3（代行评审与签署）`｜日期 `2026-10-11` |
| 5 | 签字表 **复核人**行 | 姓名 `DeepSeek V4 Flash`｜签字 `DeepSeek V4 Flash（签署前独立核验：打回 v1.2→复核 v1.3 可签）`｜日期 `2026-10-11` |
| 6 | 签字表 **记录人**行 | 姓名 `Kimi K3`｜签字 `Kimi K3`｜日期 `2026-10-11` |
| 7 | §六 **备注栏**（原 2 条填空下划线行 → 2 条要点） | ① 核验报告与修订说明在仓库外 m1-review 目录（随本材料一并归档备查，**抽象表述不落盘符**）；② 两处行文级瑕疵留待会后勘误、不阻塞签署：L33「全文不含」应为「§7 全节不含」；L16 成文时点标签折衷（保留原表述并就地加「2026-10-11 核验时点实测＝…」注记的口径） |

- 表格列结构与既有 Markdown 表格格式（`角色 \| 姓名 \| 签字 \| 日期`，分隔行不变）**未改**；姓名列填身份、签字列填任务卡给定括号注记原文（口径一致、四行齐备）。
- 两处行文级瑕疵按任务卡**只记录、不修改**（留待会后勘误）。

## 三、门禁（步骤 2，`node scripts/ci/check-docs.mjs`）

| 时点 | 结果 | 扫描面 |
|---|---|---|
| **签署填写后 / 暂存前** | **6/6 全绿**（EXIT=0） | `git ls-files` **948** 路径，实扫文本 945，豁免 3 |
| **暂存后**（唯一文件已入索引） | **6/6 全绿**（EXIT=0） | 同上（本批未增删路径，计数不变） |

两次实跑均含规则自检：连写组 5/5 例「仅第三态可捕获」（下限 5）、分隔组 24/24 例经前两态捕获（下限 24）、负对照 11 全不误伤。逐条断言 PASS：① 无盘符绝对路径 ② 技术栈版本一致 ③ 引用文件存在 ④ 端口表一致 ⑤ 命名纪律 ⑥ 探针不参与构建。

## 四、提交（步骤 3，1 笔）

| # | SHA | 主题 | 文件 | 增/删 |
|---|---|---|---|---|
| 1 | **`ae9678d`** | `docs(M1): 出口评审材料 v1.3 修订（核验打回 14 条处置）+ 签署（结论=通过，K3 代行，用户 2026-10-11 授权）` | 1（`docs/M1-出口评审材料.md`） | **41 / 22** |

- `git show --stat` 复核：**实含 1 个文件**，与「只许碰一个文件」一致；**批 B 在制品零混入**（未 add、未 commit、未触碰）。
- 提交信息**按任务卡原样**（未追加留痕行——本批钩子零命中，见 §五）。

## 五、钩子处置（`pre-commit`，未用 `--no-verify`）

- **提交前预扫**（复用 `scripts/githooks/scan-lib.sh`，对暂存区 diff 实跑同一管道）：**零命中**（SECRET 0 / PATH 0 / USER 0）。
- **实跑输出**：`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`，exit 0。
- ⇒ **未进入** `AI_SECRETS_ALLOW=1` 放行分支：本批内容不含公开组织名（`<USER>` 形态 0 处）、不含盘符路径、不含密钥形态，故 `USER` 规则**未误报**；提交信息**未追加留痕行**，任务卡预案分支**未触发**。

## 六、push 与 CI 守夜（步骤 3）

### 6.1 push

| 项 | 值 |
|---|---|
| push 前 HEAD / `origin/main` | `d897096`（左右计数 0/0） |
| 命令 | `git push origin main`（exit 0） |
| 输出 | `To <REPO-URL>` / `d897096..ae9678d  main -> main` |
| push 后 | HEAD = `origin/main` = **`ae9678dd9b2d0fd3ce05089074faba1ffca84f42`**，左右计数 0/0 |

### 6.2 CI 守夜（匿名轮询，`curl --ssl-no-revoke`，间隔 45s）

| 项 | 值 |
|---|---|
| run id | **`38106334634`** |
| run URL | <REPO-URL>/actions/runs/38106334634 |
| head_sha | `ae9678dd9b2d0fd3ce05089074faba1ffca84f42`（= 本批 HEAD，**匹配**） |
| 触发 / 时点 | `push`（`branch=main`）；created `2026-10-11T02:48:24Z` → updated `2026-10-11T02:51:55Z`（约 **3 分 31 秒**），run_attempt = 1 |
| run 级 | **status = completed，conclusion = success** |

五 job（`/actions/runs/{id}/jobs` 端点实取，**全部 completed / 全部 success**）：

| job | id | conclusion | 起止（UTC） |
|---|---|---|---|
| 文档一致性门禁（check-docs）｜**阻断** | `114372559112` | **success** | 02:48:27 → 02:48:36 |
| 前端类型检查与构建 | `114372558978` | **success** | 02:48:27 → 02:49:21 |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114372559074` | **success** | 02:48:26 → 02:49:46 |
| 后端编译与单测 | `114372559139` | **success** | 02:48:27 → 02:50:01 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114372820071` | **success** | 02:50:04 → 02:51:55 |

**停止条件已满足**（run 级 completed + **5 job 全 success**）⇒ **闭环**。轮询 **6 轮**（run 查找 1 次 + jobs 快照 6 次 + run 复核 1 次 = **8 次**匿名 API 调用，远低于 60/h 配额）。

> 观察（与第 5 / 第 6 期同现象，**非缺陷**）：**E2E job 晚出现** —— iter 1~2 的 `jobs` 响应仅 **4** 个（E2E 依赖 backend、尚未创建），iter 3 起为 **5** 个。守夜按「run completed **且** job ≥ 5 全 success」判定停止条件，**未因早期计数偏小而误判失败、亦未提前闭环**。

## 七、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无钩子阻断**：1 笔提交、门禁 6/6 × 2、push、五 job 全绿、`origin/main == HEAD`。
2. **偏差①（授权边界外、非缺陷）**：§一「评审日期 / 主持人 / 签字人」三行仍为 `________（待填）`，§七 修订记录 v1.3 行亦未追加「签署」条目 —— 任务卡填写清单**未包含**这两处，故**未改**。签署事实以 §六 签字页 + 备注栏承载；如需页首元信息与签署同步，建议会后一并勘误。
3. **偏差②（行文级瑕疵，按指示留待会后）**：§二 后端单测行的 v1.3 勘误文字「全文不含」应为「§7 全节不含」；§一 成文时点标签为折衷写法。两处**只记录、未修改**（已写入 §六 备注栏）。
4. **批 B 在途（非本批产出，实读留痕）**：开工时工作树 = 15 ` M` + 4 `??`；本批提交后 = 14 ` M` + 6 `??`；报告撰写时点再读 = **14 ` M` + 7 `??`**（新增 `backend/src/test/java/com/example/configmgr/config/` 目录等）⇒ **并行批次仍在推进**。本批**未触碰**其中任何文件。
5. **报告自身脱敏口径**：路径一律占位符（`<COMMIT-GOV>` / `<TEMP_ROOT>` 等）；文中保留 GitHub 组织名（远端 URL 与 run 链接）属公开标识且为 CI 取证所必需，已知会被独立扫描器按「用户名形态」报出（沿用第 2 / 3 / 5 / 6 期同类假阳性登记）。

## 八、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 7 期**条目） |
| 入库件（仓库内，唯一） | `docs/M1-出口评审材料.md`（v1.3 + 签署落笔：结论=通过） |
| CI 取证 | `<COMMIT-GOV>/ci-run-ae9678d.json`、`<COMMIT-GOV>/ci-jobs-ae9678d.json`、`<COMMIT-GOV>/ci-poll-ae9678d.log`（守夜逐轮原始输出） |
| 轮询脚本（仓库外，临时） | `<TEMP_ROOT>/m1-sign-poll.py` |
| 核验报告与修订说明（仓库外，**未改**） | `m1-review` 目录（抽象表述，不落盘符） |

# ai-example 合流项目 · 提交官报告（第 8 期：M1 出口评审材料「页首元信息 + 修订记录同步签署事实」）

执行者 = Kimi K3（Kimi Code 提交官子智能体），授权来源 = 本站指挥官任务卡（2026-10-11，补签同步，从简报告）。

## 一、内容与范围（1 笔）

- 批次性质：**单文件文档批**（`docs/M1-出口评审材料.md`，零代码改动）。`git add` **只显式列名该文件**，批 B 在制品**零触碰**（未 add / 未 commit）；**未读任何 `.env`**、**未用 `--no-verify`**。
- 改动 2 处（`git diff --stat` 实取 **+4 / −3**；**118 行（117→118）**，CRLF 保持）：
  1. §一 页首元信息三行占位 → 实值：评审日期 `2026-10-11`；主持人 `Kimi K3（用户授权代行）`；签字人 `用户本人（授权 K3 代行签署）／Kimi K3／DeepSeek V4 Flash（复核）`。
  2. §七 修订记录**追加 1 行** `v1.3（签署）`（2026-10-11）：结论＝通过（§六 ☑ 通过）；四栏签署事实（业务方＝用户本人当场授权 Kimi K3 代行签署，存档新增规则⑬；技术负责人 Kimi K3；复核人 DeepSeek V4 Flash；记录人 Kimi K3）；签署口径指向 §六 签署口径注记；页首元信息已同步；两处行文级瑕疵留待会后勘误、不阻塞签署。

## 二、门禁（暂存前实跑）

`node scripts/ci/check-docs.mjs` = **6/6 全绿（EXIT=0）**；扫描面 948 路径 / 实扫 945 / 豁免 3；规则自检 连写组 5/5、分隔组 24/24、负对照 11 全不误伤。

## 三、提交 / push / 钩子

| 项 | 值 |
|---|---|
| SHA | **`1c29d67d780a95dd7514296497d438a879371b00`**（短 `1c29d67`，父 `ae9678d`） |
| 主题 | `docs(M1): 页首元信息与修订记录同步签署事实` |
| 文件 | 1（`docs/M1-出口评审材料.md`，`git show --stat` 复核） |
| 钩子 | 预扫**零命中**；实跑 `✓ [pre-commit] 敏感信息扫描通过`，exit 0 ⇒ **未触发** `AI_SECRETS_ALLOW=1` 放行分支，提交信息无留痕后缀 |
| push | `git push origin main` → `ae9678d..1c29d67  main -> main`；push 后 `HEAD..origin/main` = 0/0 |

## 四、CI 守夜（匿名轮询，`curl --ssl-no-revoke`，45s）

| 项 | 值 |
|---|---|
| run id | **`38106652510`** |
| run URL | <REPO-URL>/actions/runs/38106652510 |
| head_sha | `1c29d67d780a95dd7514296497d438a879371b00`（匹配本批 HEAD） |
| 触发 / 时点 | `push`（`branch=main`）；created `2026-10-11T02:54:18Z` → updated `2026-10-11T02:57:57Z`（约 3 分 39 秒） |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion |
|---|---|---|
| 文档一致性门禁（check-docs）｜**阻断** | `114373502294` | **success** |
| 前端类型检查与构建 | `114373502144` | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114373502252` | **success** |
| 后端编译与单测 | `114373502319` | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114373761576` | **success** |

**停止条件已满足**（run completed + **5 job 全 success**）⇒ 闭环。轮询 7 轮（runs 查找 3 + jobs 5 + run 复核 1 = **9 次**匿名 API 调用）。

> 观察（同前几期，**非缺陷**）：本批 **run 延迟出现**（前 2 轮 `runs` 查询尚无本 SHA 的 run，守夜按「未出现即继续等」处理，未误判）；**E2E job 亦晚出现**（iter 1 = 4 job，iter 2 起 = 5 job）。守夜统一按「run completed **且** job ≥ 5 全 success」判定，未提前闭环。

## 五、偏差与遗留

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无钩子阻断**。
2. 第 7 期登记的**偏差①**（页首三行占位、§七 未记签署）已由本期闭环；第 7 期**偏差②**（L33 / L16 两处行文级瑕疵）按签署当场口径**仍留待会后勘误**（本期只在新行内复述、未改原文）。
3. 批 B 在制品仍在途（**零触碰**）；报告撰写时点工作树 ` M` / `??` 计数见第 7 期体例，本批未再介入。

## 六、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 8 期**条目） |
| 入库件（仓库内，唯一） | `docs/M1-出口评审材料.md`（页首元信息 + §七 v1.3（签署）行） |
| CI 取证 | `<COMMIT-GOV>/ci-run-1c29d67.json`、`<COMMIT-GOV>/ci-jobs-1c29d67.json`、`<COMMIT-GOV>/ci-poll-1c29d67.log` |


---

# ai-example 合流项目 · 提交官报告（第 9 期：批 B / R-96「作业看门狗 + W-03/W-04 fail-fast」细分提交 3 笔 + K2.8 收口修 F1~F8 + 终审微补丁 F1b/F2b）

- 本期首部署执行者：**DeepSeek V4 Flash 子智能体**（提交官：提交前硬性核验 → 脱敏终扫 → 细分提交 → push → CI 守夜 → HEAD 态门禁复跑 → 本报告）
- **授权来源**：用户 **2026-10-11 授权 K3 指挥链（DC-19）**——本笔为提交官任务卡（批 B 收口提交，明示授权 `git commit` + `git push`；**除此之外未做任何代码改动**）
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式，规则 = SECRET / PATH / USER）
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出

## 一、提交前硬性核验【实测】

- **工作树**：`git status --porcelain` = 17 ` M` + 8 `??`（折叠口径）；`-uall` 展开 = **17 ` M` + 9 文件**（`??` 的 `config/` 目录折 2 文件）＝ **26 文件**，与任务卡记账的「25 项」为折叠/展开两种数法之差。
- **归属确认**：26 文件**逐条全属批 B**，无预期外文件 ——
  - 后端生产 7 改：`AiController` / `AppProperties` / `JobRepository` / `ExportJobRunner` / `JobProgressService` / `PublishJobRunner` / `application.yml`；新增 2：`JobHeartbeatRegistry` / `JobWatchdog`。
  - 测试 6 改：`SessionBusyFrameIsolationConformanceTest` / `AiControllerFrontendResultTest` / `AiControllerHealthSseDeliveryTest` / `AiControllerHistoryDeleteTest` / `AiControllerRunsCurrentTest` / `ExportJobRunnerJobLevelIssueTest`；**新增 7**（见 §1.1 偏差）。
  - 文档 3 改：`docs/M2-排期计划.md` / `docs/README.md` / `docs/evidence/补档-NUMSPEC-清单总账.md`；脚本 1 改：`scripts/verify-e2e.ps1`。
- **基线**：HEAD = `1c29d67d780a95dd7514296497d438a879371b00`（父 `ae9678d`），`ahead=0 / behind=0`【实测】。
- **终态锚定**（提交前工作树口径）：落盘 `<TEMP_ROOT>/batch-b-fix/_anchor-manifest-final.txt` = **2198 行 / 140,404 字节**；**sha256 = `3a63e31b449488b464df2e36b87d8d82ee08a27f27f0bd6a8ae743d3c08e229e`**。
  - 方法（同批 B 红队报告附录命令）：`git diff`（tracked 全量）+ `git ls-files --others --exclude-standard` 排序后逐文件内容拼接为单一清单，再 `sha256sum`。
  - 与红队旧锚 `71869257153ccc0c…`（收口修前口径）不同属**预期变化**（F1~F8 + F1b/F2b 已落盘）。

### 1.1 项数偏差（如实留痕，非缺陷）

任务卡记账「untracked 2 生产类 + 6 测试类」「6 改 + 6 新」，实盘测试类为 **6 改 + 7 新**；多出的 1 个新测试类 = `backend/src/test/java/com/example/configmgr/config/AppPropertiesJobDurationBindingTest.java`（F4 `@DurationUnit(SECONDS)` 绑定面），与同为批 B 产出的 `AppProperties` 改动同源，**归属无疑**。除此之外**逐文件与任务卡清单一致**，无停手条件触发。

## 二、脱敏终扫【实测】

- 工具：`<COMMIT-GOV>/desense-scan.mjs`（提交官自建，与仓库门禁相互独立的四模式扫描器）`realscan` 模式，输入 = 26 个改动文件全量路径清单。
- **盘符绝对路径 / 本机身份（用户名、主机名、用户目录）/ 命名违禁（含 NFKC 折叠、隐形字符、**零分隔连写**、大小写混合）：0 命中。**
- **IP 类命中 9 处，逐条裁决（均非本批新增的敏感信息）**：

| 位置 | 命中 | 裁决 |
|---|---|---|
| `backend/src/main/resources/application.yml:64` | `127.0.0.1` | 环回（redis host）；**HEAD 既有**，本批 diff 未触碰该行 |
| `backend/src/main/resources/application.yml:183` | `10.1.54` | 实为文案「tomcat-embed-core 10.1.54」= **版本号**（扫描器自述「点分版本号」保守过匹配项）；**HEAD 既有** |
| `scripts/verify-e2e.ps1:17 / :30 / :52` | `127.0.0.1` | 本机 E2E 端点；**HEAD 既有**（本批 diff 新增行不含该类行） |
| `PublishImportModeFailFastTest.java:105 / :111` | `10.9.9.9` | **合成测试夹具**，与 HEAD 既有 `PublishSemanticsTest` / `ImportFlowJobTest` 同值同族约定 |
| `PublishImportModeFailFastTest.java:124 / :127` | `10.222.0.1` | 同上（合成夹具，非真实内网主机） |

- **AI key 字样**：仅出现环境变量名与配置键（`AI_API_KEY` / `DEEPSEEK_API_KEY` / `spring.ai.openai.*`），**零真实密钥值**（「密钥只经环境变量注入，绝不写入版本库」为既有口径注释）。
- 结论：**无新增敏感信息，可提交。**

## 三、细分提交（3 笔）【实测】

`git add` 三笔均**显式逐文件列名**（未用 `git add -A` / `.`），工作树无其他在途改动混入。

| 序 | SHA | 主题 | 文件 | 统计 |
|---|---|---|---|---|
| 1 | **`89d688f`** | `feat(job): R-96 作业看门狗 + W-03/W-04 fail-fast（批 B + 收口修）` | 9（后端 7 改生产 + 2 新类） | +629 / −75 |
| 2 | **`8415540`** | `test(job): R-96 看门狗/fail-fast/终态守卫用例族（436→437，68 XML）` | 13（测试 6 改 + 7 新） | +1121 / −6 |
| 3 | **`69a12ea`** | `chore(e2e,docs): Wait-Job 派生式 120s + R-96 台账留痕` | 4（`verify-e2e.ps1` + 3 docs） | +10 / −4 |

- 提交信息均带 R-96 锚，正文逐文件写明改动面（W-03/W-04、F1~F8、F1b/F2b、四键取值依据与派生式）。
- **钩子留痕**：三笔均在提交前用 `scan-lib.sh` 引擎对暂存区 diff 做**预扫 = 0 命中**；实跑三次均为 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`，exit 0。
  - ⇒ **本次未触发 `AI_SECRETS_ALLOW=1` 放行分支**（亦无 R-129 的 `<USER>` 公开组织名误报命中），**未使用 `--no-verify`**。
- 提交后工作树 `git status --porcelain` = **空**（26 文件全部入库；批 B 在制品清零）。

## 四、push 与远端 HEAD【实测】

- `git push origin main` → `1c29d67..69a12ea  main -> main`（exit 0）。
- **推送前 HEAD** = `1c29d67d780a95dd7514296497d438a879371b00`（`ahead=0`）。
- **推送后 HEAD** = `origin/main` = `69a12ea85d4ae4aef0a5a4748e24d77a118115fd`，`ahead=0 / behind=0`。
- 三笔一次推送 ⇒ GitHub Actions 仅对**推送锥顶提交**产生 1 个 run（中间两笔无独立 run，`runs?branch=main` 列表核对无缺）。

## 五、CI 守夜（匿名轮询 · 预算 60/h · 实耗 4 次调用）

| 项 | 值 |
|---|---|
| run id | **`38110633994`** |
| run URL | <REPO-URL>/actions/runs/38110633994 |
| head_sha | `69a12ea85d4ae4aef0a5a4748e24d77a118115fd`（= 本批 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T04:08:32Z` → updated `2026-10-11T04:12:16Z`（约 **3 分 44 秒**） |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion |
|---|---|---|
| 后端编译与单测 | `114385270790` | **success** |
| 前端类型检查与构建 | `114385270579` | **success** |
| 文档一致性门禁（check-docs）｜**阻断** | `114385270716` | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114385270744` | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114385543340` | **success** |

- **停止条件已满足**（run completed + **5 job 全 success**）⇒ 闭环；**无 failure，未取 annotations，未自行修复/重推**。
- 轮询 2 轮（iter1 仅 4 job、iter2 全 5 job），每次 `runs` + `jobs` 各 1 次 ⇒ 累计 4 次匿名 API 调用，间隔 ≥3 分钟。
- 观察（同前几期，**非缺陷**）：**E2E job 晚出现**（iter1 = 4 job，iter2 起 = 5 job）—— 守夜按「run completed **且** job ≥ 5 全 success」判定，未因早期计数偏小而误判缺失 / 未提前闭环。

## 六、push 后 HEAD 态门禁复跑【实测】

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端全量单测 | `<TEMP_ROOT>/redteam-batch-a/.run-fix/mvn-run.sh -B test`（Maven 全路径包装器，透传 `-B test`） | **Tests run: 437, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**（01:04 min）；**surefire XML 求和口径 = tests 437 / failures 0 / errors 0 / skipped 0，XML 文件数 68**（与预期 `437/0/0/0·68 XML` **逐位一致**） |
| 文档一致性门禁 | `node scripts/ci/check-docs.mjs` | **6/6 全绿**（EXIT=0）；命名纪律扫描面 957 路径 / 实扫 954 / 豁免 3；规则自检 连写组 5/5、分隔组 24/24、负对照 11 全不误伤 |
| 前端（本批未动，HEAD 态仍须绿） | `yarn typecheck && yarn build` | **typecheck 绿**（`vue-tsc --noEmit`，5.12s）＋ **build 绿**（vite 6.4.4，1747 模块，26.72s，产物落 `dist/assets/*`） |

## 七、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无钩子阻断 / 无放行逃生口**：3 笔提交、push、五 job 全绿、三件套门禁全绿、`origin/main == HEAD`。
2. **偏差①（记账口径，非缺陷）**：任务卡「25 项（17 M + 8 ??）／6 改 + 6 新测试类」与实盘「26 文件（17 M + 9 文件）／6 改 + 7 新测试类」差 1，差项 = `config/AppPropertiesJobDurationBindingTest.java`（F4 绑定面，批 B 同源产出）。已按任务卡语义**全量归属核验**，未漏未多。
3. **偏差②（观察，非缺陷）**：脱敏终扫 9 处 IP 类命中全部为「HEAD 既有 + 合成夹具 + 版本号形态」（§二 逐条已裁决）；其中 `application.yml` / `verify-e2e.ps1` 命中的行本批**未被 diff 触碰**。
4. **报告自身脱敏口径**：路径一律占位符（`<COMMIT-GOV>` / `<TEMP_ROOT>` / `<REPO>` / `<SCRATCH>`）；文中保留 GitHub **公开组织名**（远端 URL 与 run 链接）属公开标识且为 CI 取证所必需，已知会被独立扫描器按「用户名形态」报出（沿用第 2/3/5/6/7/8 期同类假阳性登记口径）。**本期自扫留痕**：对本期条目单独跑 `desense-scan.mjs realscan` = **10 处命中，全部已登记**——4 处 GitHub 公开组织名（R-129 假阳性，含远端 URL 与 run 链接）、6 处为 §二 裁决表**照引**的被判 IP 值（环回 `127.0.0.1` / 版本号 `10.1.54` / 合成夹具 `10.9.9.9`、`10.222.0.1`，引证所需）；**盘符路径 0、本机用户名 0、主机名 0、命名违禁 0**。
5. **遗留（不属本笔，如实留痕）**：`docs/M2-排期计划.md` R-96 行尾已登记批 B 延期项（复核 C3 demo 节拍计入 30min 上限、红队 m5 时钟回拨、红队 m8 判死 issue 含内部阈值、复核 O1~O4/O6/O7 与红队 o1~o4 观察照录），供 K3 后续落 M2。

## 八、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 9 期**条目） |
| 入库件（仓库内，26 文件 / 3 笔提交） | `89d688f`（后端生产 9）、`8415540`（测试 13）、`69a12ea`（`scripts/verify-e2e.ps1` + 3 docs） |
| 终态锚定留痕 | `<TEMP_ROOT>/batch-b-fix/_anchor-manifest-final.txt`（2198 行 / 140,404 字节；sha256 `3a63e31b449488b464df2e36b87d8d82ee08a27f27f0bd6a8ae743d3c08e229e`） |
| 改动文件清单（供复核） | `<TEMP_ROOT>/batch-b-fix/_changed-files.txt`（26 行，`-uall` 展开） |
| CI 取证 | `<COMMIT-GOV>/ci-run-batchB.json`、`ci-jobs-batchB.json`、`ci-runs-batchB.json`、`ci-runs-batchB-post.json` |
| HEAD 态门禁原始输出 | `<COMMIT-GOV>/head-gate-batchB-mvn.log`、`head-gate-batchB-frontend.log` |
| 脱敏扫描器（仓库外工具） | `<COMMIT-GOV>/desense-scan.mjs` |

---

# ai-example 合流项目 · 提交官报告（第 10 期：docs 小批 · 去向表 `R-132`~`R-135` 落行）

- 本期首部署执行者：**Kimi K3 子智能体**（提交官：基线核验 → 台账实读定锚 → 4 行落盘 → 门禁 → 独立脱敏终扫 → 单笔提交 → push → CI 守夜 → 本报告）
- **授权来源**：用户 **2026-10-11 授权 K3 指挥链（DC-19）**——本笔为提交官任务卡（docs 小批落行，明示授权 `git commit` + `git push`；**除此之外未做任何代码改动**）
- 仓库：`<REPO>`（分支 `main`）
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式，规则 = SECRET / PATH / USER）
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出

## 一、基线核验【实测】

- **工作树**：`git status --porcelain` = **空**（干净）⇒ 任务卡的停手条件（「若有任何未提交改动立即停手报告」）**未触发**。
- **基线**：`HEAD` = `origin/main` = **`69a12ea85d4ae4aef0a5a4748e24d77a118115fd`**，`ahead=0 / behind=0`，分支 `main`。
- **唯一改动面声明**：本笔**只**改 `docs/M2-排期计划.md`（+4 / −0），未触碰任何代码、脚本、配置、测试。

## 二、台账实读与落行【实测】

- **编辑前实读锚点**：去向表（「M2-T3a 闭环注记 · b) 遗留去向」节）表体末行 = **`R-131`（原 `:320`）**，其后为表体闭合空行 + 「2026-10-11 登记（只追加）：去向表本次追加 18 行」注记（原 `:322`）。
- **落点**：紧接 `R-131` 之后、表体闭合空行之前，**追加 4 行**（现态 `:321`~`:324`）；**既有行零改动**（`git diff --stat` = `1 file changed, 4 insertions(+)`，**0 删除**）。
- **行格式**：与既有 `R-129` / `R-130` / `R-131` 行**严格同构**（2 列：第 1 列 = ``| R-1xx（标题）…正文…**来源 = …**``；第 2 列 = `**已登记·待排期**（2026-10-11 登记，批 B 合并后落行批）：本批只登记，不施工、不排期占位`）。

| 落点行号 | 编号 | 主题（摘要） | 来源 | 处置去向 | 状态 |
|---|---|---|---|---|---|
| `:321` | **`R-132`** | 追溯审查 N1 同族残余 `R2` · 同会话并发轮被旧快照抢连接（轮次身份轴） | 追溯审查报告 §7 N1 同族残余 + 方案设计件 `<SCRATCH>/n1-design/方案.md` §3.1 残余项 `R2` | 对话时序包 | 已登记·待排期 |
| `:322` | **`R-133`** | 追溯审查 N2 的补记 `N2-b` · `DEL` 与 `SREM` 之间被并发写回插队的交错失配（存活会话从索引消失、**无兜底**） | 追溯审查报告 §6「事务」轴 / §7 N2 + 方案设计件 §4.2 ② / §6.3 | 对话时序包子包① | 已登记·待排期 |
| `:323` | **`R-134`** | 步骤条包 S4 追加台账的 `R-94` 编号冲突项 · **编号预留** | 步骤条包体检报告 §2.d / §5①（`<SCRATCH>/pkg-health/体检报告.md`） | 步骤条包 | 已登记·待排期（预留号） |
| `:324` | **`R-135`** | 批 B 延期登记 · `demo-batch-delay-ms` 装饰睡眠运行期计入 30min 绝对上限（「不计入」仅注释口径） | 批 B 复核报告 §J4 / C3、批 B 红队报告 m6、批 B 收口修报告 §4-1 | 后续批（处置方向二选一） | 已登记·待排期 |

- **行内锚点按本机实读复核**（不照抄仓外报告）【实测】：`frontend/src/stores/ai.ts` `:589`（`this.activeRunId = current.runId`）/ `:614`（`isCurrentRound = currentAbort === controller`）/ `:617`（`previous?.abort()`）/ `:344-346`（`canSend = !loading`）；`RedisChatMemoryRepository` `:144-146`（`DEL :145` / `SREM :146`）；`ResilientChatService:576`（`convergeMemory`）；`ExportJobRunner:150` / `ImportJobRunner:145` / `PrecheckJobRunner:188` / `PublishJobRunner:214`（4 处 `Thread.sleep(getDemoBatchDelayMs())`）；`application.yml:205`（`demo-batch-delay-ms: 300`）/ `:217`、`AppProperties.java:159`（「不计入」注释）。
- **偏差①（锚点口径，非缺陷）**：R-132 的方案设计件原锚 `:588` / `:345-347` 与本机现态实读 `:589` / `:344-346` **差 1 行**；本表一律按**实读值**落，未沿用设计件锚点。
- **脱敏抽象化**：素材中的仓外路径在仓内文档一律改 `<SCRATCH>/…` 占位 + 抽象篇名（沿用 `R-129`~`R-131`「抽象篇名 + 占位符」口径）；批 B 三份仓外报告按**抽象篇名**引用（不带实盘路径）。

## 三、门禁（`node scripts/ci/check-docs.mjs`）【实测】

| 时点 | 结果 |
|---|---|
| 编辑前基线 | **6/6 全绿**（EXIT=0） |
| 编辑后（暂存前） | **6/6 全绿**（EXIT=0） |
| push 后 HEAD 态复跑 | **6/6 全绿**（EXIT=0） |

- 命名纪律扫描面 957 路径 / 实扫 954 / 豁免 3；规则自检 = 连写组 5/5（下限 5）、分隔组 24/24（下限 24）、负对照 11 全不误伤 —— 与编辑前**逐位一致**。
- 断言⑤ 按 `git ls-files` 路径清单 + **工作树内容**（`fs.readFileSync`）实扫 ⇒ 编辑后那一次已覆盖本笔新增的 4 行（非仅扫 blob）。

## 四、脱敏终扫【实测】

- 工具：`<COMMIT-GOV>/desense-scan.mjs`（提交官自建，与仓库门禁**相互独立**的四模式扫描器：盘符绝对路径 / 本机身份（用户名·主机名·用户目录）/ 内网与环回 IP / 命名违禁四态折叠）。
- **扫描对象 = 本笔新增的 4 行本身**（`git diff -U0 HEAD~1 HEAD` 取 `+` 行 → `<COMMIT-GOV>/ci-poll/added-3027ff5.txt`，4 行），非整文件（避免既有语料噪声）：**命中 0 处，按豁免登记放过 0 处**。
- **植入对照（规则武装自检，R-92 纪律）**：`seed` 模式 = **正向种子 23/23 全命中**（盘符 4 形态 / 用户名 2 形态 / 用户目录 / 主机名 / IP 4 段 / 命名违禁 11 形态）＋ **负对照 5/5 全不命中** ⇒ 扫描器已武装，「0 命中」非假绿。
- 钩子侧预扫（`scan-lib.sh` 引擎对暂存区 diff）：**0 命中**；`USER` 规则（由本机 `git config user.name` 拆形生成的词边界正则，含全名与两个 ≥4 字符片段，**本期不落该字面**）本笔**未误报**（新增行不含 GitHub 公开组织名 / 本机 `user.name` 形态）。
- 结论：**新增内容无盘符 / 无本机用户名 / 无主机名 / 无内网 IP / 无命名违禁，可提交。**

## 五、提交（1 笔）【实测】

| 序 | SHA | 主题 | 文件 | 统计 |
|---|---|---|---|---|
| 1 | **`3027ff5`** | `docs(M2): R-132~R-135 落行（追溯 R2/N2-b、步骤条编号冲突预留、批 B C3 延期登记）` | `docs/M2-排期计划.md` | **+4 / −0** |

- `git add` **显式列名单文件**（未用 `git add -A` / `.`）⇒ 工作树无其他在途改动混入。
- **钩子留痕**：`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`，exit 0。
  - ⇒ **未使用 `--no-verify`**，**未触发 `AI_SECRETS_ALLOW=1` 放行分支**。
- 提交后 `git status --porcelain` = **空**（文件行数 486 → **490**）。

## 六、push 与远端 HEAD【实测】

- `git push origin main` → **`69a12ea..3027ff5  main -> main`**（exit 0）。
- **推送前 HEAD** = `69a12ea85d4ae4aef0a5a4748e24d77a118115fd`（`ahead=0`）。
- **推送后 HEAD** = `origin/main` = **`3027ff5d377201eea6dc08bc65ae08efe902e77c`**，`ahead=0 / behind=0`。
- 单笔推送 ⇒ GitHub Actions 对推送锥顶产生 1 个 run。

## 七、CI 守夜（匿名轮询 · 预算 60/h · 实耗 6 次调用）

| 项 | 值 |
|---|---|
| run id | **`38111055476`** |
| run URL | `<REPO-URL>/actions/runs/38111055476` |
| head_sha | `3027ff5d377201eea6dc08bc65ae08efe902e77c`（= 本批 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T04:16:26Z` → updated `2026-10-11T04:19:51Z`（约 **3 分 25 秒**） |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion |
|---|---|---|
| 前端类型检查与构建 | `114386525530` | **success** |
| 后端编译与单测 | `114386525609` | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114386525652` | **success** |
| 文档一致性门禁（check-docs）｜**阻断** | `114386525660` | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114386726293` | **success** |

- **停止条件已满足**（run completed + **5 job 全 success**）⇒ 闭环；**无 failure，未取 annotations，未自行修复 / 未重推**。
- 轮询 **3 轮**（iter1 = 4 job（E2E 未出现）→ iter2 = 5 job → iter3 全绿），每轮 `runs` + `jobs` 各 1 次 ⇒ 累计 **6 次匿名 API 调用**，间隔 **180s ≥ 3 分钟**（任务卡下限）。
- 观察（同前几期，**非缺陷**）：**E2E job 晚出现**（iter1 = 4 job，iter2 起 = 5 job）—— 守夜按「run completed **且** job ≥ 5 全 success」判定，**未因早期计数偏小而误判缺失、未提前闭环**。

## 八、偏差与遗留（如实留痕）

1. **无 BLOCKER / 无 CI failure / 无门禁红 / 无钩子阻断 / 无放行逃生口**：1 笔提交、push、五 job 全绿、`check-docs` 三时点全绿、`origin/main == HEAD`。
2. **偏差①（锚点口径，非缺陷）**：`R-132` 的方案设计件锚点 `:588` / `:345-347` 与本机现态实读 `:589` / `:344-346` **差 1 行**；本表按实读值落，行内已按【实测】口径标明。
3. **偏差②（编号口径，如实留痕）**：步骤条包体检报告原建议的空号 **`R-132` 已被本批（追溯 `R2`）取用** ⇒ `R-134` 行内已写明该冲突项的改号结论（**一律以 `R-134` 为准**）；**步骤条包施工时不得再取 `R-132`**。
4. **报告自身脱敏口径**：本报告路径一律占位符（`<COMMIT-GOV>` / `<TEMP_ROOT>` / `<REPO>` / `<REPO-URL>` / `<SCRATCH>`）；**按用户本期脱敏口径，本期起不再保留 GitHub 公开组织名字面**（第 1~9 期保留该字面并登记为 `R-129` 型假阳性），run 以 **run id / job id** 为唯一取证标识 ⇒ 与第 1~9 期在「run 链接形态」上**有意收紧、非笔误**。
5. **遗留（不属本笔，如实留痕）**：`R-135` 只承接批 B 延期登记的 **①（复核 C3）**；红队 `m5`（时钟回拨）/ `m8`（判死 issue 含内部阈值，低敏）与全部观察项**仍由 `R-96` 行尾「延期登记」承载**，本次**未改动该行**。

## 九、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 10 期**条目） |
| 入库件（仓库内，1 文件 / 1 笔提交） | `3027ff5`（`docs/M2-排期计划.md`，`R-132`~`R-135` @ `:321`~`:324`） |
| CI 取证 | `<COMMIT-GOV>/ci-poll/ci-run-3027ff5.json`、`ci-jobs-3027ff5.json`、`poll-3027ff5.log` |
| 新增行快照（脱敏扫描输入） | `<COMMIT-GOV>/ci-poll/added-3027ff5.txt` |
| 守夜脚本（本笔） | `<COMMIT-GOV>/ci-poll/poll-3027ff5.mjs` |
| 脱敏扫描器（仓库外工具） | `<COMMIT-GOV>/desense-scan.mjs` |


---

# ai-example 合流项目 · 提交官报告（第 13 期：对话时序包批 1「parts 双写 + 挂起快照对账 + 删除路径 Lua 原子化」细分提交 3 笔 + 双内锚订正 + 审查报告入库 + CI 五 job 全绿）

- 本期执行者：**Kimi Code 子智能体（提交官）**——流程 = 阶段 0 前置机械验收 → 脱敏终扫 → 外部审查报告入库 → M2 落行 → 细分提交 3 笔 → push → push 后核验 + CI 守夜 → 本报告
- 授权来源：**K3 指挥官任务卡**（用户 **2026-10-11 授权链**，决策卡 K3 代签，DC-19 署名）
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**全文无 GitHub 组织名字面**（第 10 期起格式纪律），run 以 **run id / job id** 取证
- 起点 HEAD：`7879f53`｜push 后 HEAD = `origin/main`：**`2f455ce`**
- 上游案卷（仓外）：`<SCRATCH>/chat-timeline-pkg/`（决策卡 / 施工报告-批1 / 复核报告-批1 / 红队报告-批1 / 收口修报告-批1 / 红队锚定-批1 / 任务卡×3）
- 取证件目录（仓外）：`<SCRATCH>/chat-timeline-pkg/evidence-p13/`
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜yarn `1.22.22`｜node `v24.21.0`｜JDK 21
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出
- **结论：闭环成功** —— 3 笔中文细分提交落库并推送，CI run `38134430906` 五 job 全 completed / 全 success，`check-docs` 4 个时点全 6/6

---

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 0 前置机械验收 | ✅ 五项全绿（前端 test **37/37** · typecheck exit 0 · build exit 0 · Maven **439/0/0/0（69 XML）** · check-docs **6/6**）+ 锚定对照 **逐字节一致** |
| 1 脱敏终扫 | ✅ 代码 7 文件 **0 命中**；M2 改动 **0 命中**；入库证据 2 份仅既有保留口径内的环回地址 1 处（已书面登记） |
| 2 审查报告入库 | ✅ 2 份脱敏入库 `docs/evidence/`（复核 105→107 行 / 红队 131→133 行），通过 check-docs 断言⑤ |
| 3 M2 落行 | ✅ 4 行行尾「完成留痕」+ 2 处内锚订正（`:321`/`:322`）+ 新增 `R-138` 行；**只追加、内容零删**，机械复验通过 |
| 4 细分提交 | ✅ 3 笔（`1efab09` / `9a06c5b` / `2f455ce`），逐文件显式 `git add`，全程未用 `--no-verify` |
| 5 push + 核验 + 守夜 | ✅ push `7879f53..2f455ce`；HEAD 态门禁复跑全绿；CI 五 job 全绿 |
| 6 提交报告 | ✅ 本期（本报告追加至 `<COMMIT-GOV>/提交报告.md`） |

---

## 一、阶段 0 · 前置机械验收（HEAD=7879f53 工作树态，7 在制品文件在树）【实测】

| # | 门禁 | 命令 | 期望 | 实测 | 判定 |
|---|---|---|---|---|---|
| 1 | 前端单测 | `cd frontend && yarn test` | 37/37 | Test Files 5 passed (5) / Tests **37 passed (37)**（sse-watchdog 4 + ready 4 + tools-and-deadline 21 + parts-double-write 5 + restore-reconcile 3），exit 0 | ✅ |
| 2 | 前端类型 | `cd frontend && yarn typecheck` | exit 0 | `vue-tsc --noEmit` Done in 3.77s，exit 0 | ✅ |
| 3 | 前端构建 | `cd frontend && yarn build` | exit 0 | `✓ built in 27.07s`（spreadjs chunk 6,198.64 kB，与既有批次同形），exit 0 | ✅ |
| 4 | 后端全量 | `bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test` | 439/0/0/0，69 XML | `Tests run: 439, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，MVN_EXIT=0；surefire XML **69** 个，逐文件解析求和 `tests=439 failures=0 errors=0 skipped=0` | ✅ |
| 5 | 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 | 6/6 断言绿（`git ls-files` 964 路径 / 实扫文本 961 / 豁免 3），exit 0 | ✅ |

**锚定对照（提交前复验，防夹带）**【实测】：按「红队锚定-批1/锚定说明-v2.txt」§5 同口径重生成补丁（4 个已跟踪文件逐文件 `git diff HEAD` + 3 个新文件 `git diff --no-index -- /dev/null`，固定段序），重生成件 SHA-256 = `e8658dc9a0007b58af79437a1c9c9030a3cf1a11a198348ce34c54a2e515e24c`（59560 B），与任务下发值一致，`cmp` 逐字节 **IDENTICAL_BYTES** ⇒ **工作树与 v2 锚定完全一致、零夹带**。重生成件存档 `evidence-p13/regen-diff-b1-v2.patch` + `anchor-verify.txt`。

任何一项不绿即停工——本批五项全绿，进入阶段 1。

---

## 二、阶段 1 · 脱敏终扫【实测】

**扫描器**：`<COMMIT-GOV>/desense-scan.mjs`（提交官自建，与仓库门禁相互独立；四模式 = 盘符绝对路径 / 本机身份 / 内网或本机 IP / 命名违禁 + 隐形字符双态。**规则自检**：正向种子 **23/23** 命中、负对照 **12/12** 未误伤、保守过匹配 2/2 已登记）。

| 扫描面 | 文件数 | 命中 | 说明 |
|---|---|---|---|
| A 在制品（4 改 + 3 新） | 7 | **0** | 无盘符 / 无用户名 / 无主机名 / 无内网 IP / 无 key 形态 |
| B 本次入库的证据件 | 2 | **1** | 唯一项 = 环回地址 `127.0.0.1`（`补档-CHATTIMELINE-复核报告-GLM-5.3.md:18`） |
| C `docs/M2-排期计划.md`（本批改动） | 1 | **0** | 同上五类规则 0 命中 |

**唯一非零项的处置（如实登记）**：`127.0.0.1` 属**环回地址**，且 `docs/evidence/` 既有 **37 份**文件同形；`docs/evidence/补档-索引-2026-10-11.md` 已有**书面保留口径**（「**保留** …… 环回地址 `127.0.0.1` ……」）。故按既有口径**保留**并在此书面登记，**非本批新增泄漏面**（若指挥官要求严格字面 0 命中，可另行裁决改写为占位符——属口径选择，非缺陷）。另：该文件 `:17` / `:51` 被宽松 grep 命中的 `10.3` 系章节号 `§10.3`，**非 IP**。

**独立复扫（grep 实现交叉验证）**：三份 docs 逐行扫**命名纪律禁则**（`main`+分隔符+`v2` 及零分隔连写）**0 命中**；RFC1918（`10.` / `192.168.` / `172.16-31.`）**0 命中**。

**入库证据件的脱敏动作**：红队报告**定点改写 1 处** —— 「锚定 patch」行的盘符绝对路径改写为 `<SCRATCH>/chat-timeline-pkg/红队锚定-批1/diff-b1.patch` 形态；其余正文（含 SHA-256 与字节数）**逐字保留**；复核报告 **0 处改写**（本就无盘符/身份/RFC1918）。两份页首各加 1 行来源标识 + 1 空行 ⇒ 行数各 +2。

---

## 三、阶段 2 · 外部审查报告入库 `docs/evidence/`【实测】

命名沿用该族既有风格（`补档-<包代号>-<报告类型>-<执行者模型>.md`，参照「补档-VITESTA-*」先例）：

| 源（仓外，占位符形式） | 落点 `docs/evidence/` | 源行 | 入库行 | 定点改写 |
|---|---|---|---|---|
| `<SCRATCH>/chat-timeline-pkg/复核报告-批1.md` | `补档-CHATTIMELINE-复核报告-GLM-5.3.md` | 105 | **107** | 0 |
| `<SCRATCH>/chat-timeline-pkg/红队报告-批1.md` | `补档-CHATTIMELINE-红队报告-DS-V4-Pro.md` | 131 | **133** | 1（盘符 → `<SCRATCH>/…`） |

页首标识行（两件同文）：`> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 1 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）`。**源文件（仓外）一律未改**。

**门禁**：两份入库件在 `git add` 后进入 `git ls-files` 扫描面 ⇒ `check-docs` 断言⑤ 实测覆盖（`ls-files` 964 → 967 → **969**，`实扫文本 966，豁免 3`），**6/6 全绿**。

---

## 四、阶段 3 · `docs/M2-排期计划.md` 落行【实测】

**纪律执行**：只追加 + 内容零删 + 行尾注记；整行重写为载体且**旧文本逐字节保留**；编辑前重新实读；锚点落表体内（该表头位于 `:133`，2 列；新增行同为 2 列、`|` 计数 = 3，与既有行一致）。

| 项 | 落点 | 内容摘要 |
|---|---|---|
| (a) 回写已施工 | `:319` R-130 | 行尾「完成留痕（2026-10-11，只追加）」= 已施工并入仓 + 本批代码提交 `1efab09`；`restoreSuspendedSnapshot` 二次对账口径 + 回归锁 `restore-reconcile.spec.ts` 3 例 + 复核/红队结论 + 两份入库件路径 |
| (a) 回写已施工 | `:320` R-131 | 同上 + 本批代码提交 `9a06c5b`；单次 Lua `execute` 新落点 `:164-165`；N2 与 N2-b 双向失配同段消解；回归锁 DeleteTest 2 例 + E2E TC19 |
| (a) 回写已施工 + (b) 内锚订正 | `:321` R-132 | 已施工（`1efab09`，与 R-130 同族一次改）；**内锚订正**：`frontend/src/stores/ai.ts` `:589` 经实读应为 **`:588`**；同行 `:614` / `:617` / `:344-346` 实读**无误、不改** |
| (a) 回写已施工 + (b) 内锚订正 | `:322` R-133 | 已施工（`9a06c5b`，见 R-131 同一落点）；**内锚订正**：`deleteByConversationId` 经实读应为 **方法 `:145` / `DEL :146` / `SREM :147`**（原文 `:144-146` / `:145` / `:146`），本批新 Lua 落点 `:164-165` |
| (c) 批 2 前置项登记 | 新增 `:327` **R-138** | ①红队 **m-R2**（11 处 `upsertToolPart` 中 5 处 + `loadHistory` 历史分支 `:587-601`，共 **6 个双写写点无回归锁** ⇒ 批 2 补测）；②**o-R1**（段 `streaming` 封口不对称）③**o-R2**（abort 句柄 ABA 缺口）④**o-R3**（`saveAll` 空列表分支 `:151-156` 未 Lua 化的删除路径）⑤**o-R4**（哨兵键碰撞，pre-existing）⇒ 随批 2 评估；⑥复核 **o1**（`requiresAction` 残留 `true` 含**镜像持久化维度** ⇒ 批 2 前置收口）。去向 = 对话时序包子包②（批 2） |

**机械复验（防"零删"被违反）**【实测】：以 `git show HEAD:<M2>` 与改动后工作树逐行对照，结果为 ——
- 目标 4 行：**旧文本 100% 保留**（`new.startswith(old[:-2])` 且 `new.endswith(old[-2:])` 且长度恰为 `len(old)+append`；追加 500 / 519 / 457 / 361 字符）；
- **非目标行逐字节全等**（含 R-137 行）；
- **恰 1 行插入**（新 `:327` = `| R-138…`，2 列表体行）；
- 文件仍为 **LF**（未发生行尾转换）；总行数 **492 → 493**。
⇒ 「只追加 + 内容零删 + 行尾注记」纪律**成立**。

**回写 hash 口径**：`R-130`~`R-133` 行尾完成留痕引用本批**前两笔代码提交** `1efab09` / `9a06c5b`（提交时序安排为「先码后档」，使 docs 落行时 hash 已存在，满足「提交后补 hash」的操作可行性，**无需第二笔补丁提交**）；docs 笔自身 hash 即本报告 §五 第 3 笔。

**编号纪律核对**：`R-130`~`R-137` 行内容逐行实读核对后按实况落；`R-132` 编号冲突（步骤条包已取 `R-136`、冲突项以 `R-134` 为准）由既有 `R-134` / `R-136` 行承载，**本批未改动其正文**；本批新号取**下一空号 `R-138`**（与任务卡给定一致）。

---

## 五、阶段 4 · 细分提交（3 笔）【实测】

| 序 | SHA | 主题 | 文件（逐文件显式 `add`） | 统计 |
|---|---|---|---|---|
| 1 | **`1efab09`** | `feat(ai): 工具段 parts 双写 + 挂起快照对账守卫（R-130/R-132，对话时序包批 1）` | `frontend/src/types/ai.ts`、`frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue`、`frontend/tests/parts-double-write.spec.ts`、`frontend/tests/restore-reconcile.spec.ts` | **5 files / +866 −18**（含 2 新） |
| 2 | **`9a06c5b`** | `refactor(ai): RedisChatMemoryRepository 删除路径 Lua 原子化（R-131/R-133，对话时序包批 1）` | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java`、`backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositoryDeleteTest.java` | **2 files / +101 −4**（含 1 新） |
| 3 | **`2f455ce`** | `docs(m2,evidence): R-130~R-133 回写已施工 + :321/:322 内锚订正 + 批 2 前置项登记 + 两份外部审查报告入库（对话时序包批 1）` | `docs/M2-排期计划.md`、`docs/evidence/补档-CHATTIMELINE-复核报告-GLM-5.3.md`、`docs/evidence/补档-CHATTIMELINE-红队报告-DS-V4-Pro.md` | **3 files / +245 −4**（含 2 新） |

- **逐文件显式 `git add`**（未用 `git add -A` / `.`）⇒ 无其他在途改动混入；三次提交的暂存区文件清单与上表**逐项一致**。
- **钩子留痕**：三次提交均输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`，exit 0。
  ⇒ **未使用 `--no-verify`**，**未触发 `AI_SECRETS_ALLOW=1` 放行分支**（R-129 口径的公开组织名误报本期**未出现**：本期报告与入库件全文无组织名字面）。
- 全 3 笔提交后 `git status --porcelain -uall` = **空**（工作树干净）。

---

## 六、阶段 5 · push 与远端 HEAD【实测】

- `git push origin main` → **`7879f53..2f455ce  main -> main`**（exit 0）；**无 non-fast-forward 拒绝、未使用 force**。
- **推送前** HEAD = `2f455ceed9a797bc7f86b4b76e03f08cfe932d4b`、`ahead=3`。
- **推送后** HEAD = `origin/main` = **`2f455ceed9a797bc7f86b4b76e03f08cfe932d4b`**，`ahead=0 / behind=0`（`git rev-list --left-right --count origin/main...HEAD` = `0 0`）。

**HEAD 态门禁复跑**【实测】（源码在阶段 0 后**零变动**，故 Maven 亦可引用；为稳妥仍**在 HEAD 态复跑** Maven）：

| # | 门禁 | HEAD 态实测 |
|---|---|---|
| 1 | `cd frontend && yarn test` | Test Files 5 passed (5) / Tests **37 passed (37)**，exit 0 |
| 2 | `cd frontend && yarn typecheck` | exit 0（阶段 0 同源实跑） |
| 3 | `cd frontend && yarn build` | exit 0（阶段 0 同源实跑） |
| 4 | Maven 全量（HEAD 态复跑） | `Tests run: 439, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，MVN_EXIT=0；surefire XML **69** |
| 5 | `node scripts/ci/check-docs.mjs` | **6/6** 断言绿（`ls-files` 969 / 实扫 966 / 豁免 3），exit 0 |

---

## 七、CI 守夜（匿名轮询 · 预算 60/h · 实耗 6 次调用）【实测】

| 项 | 值 |
|---|---|
| run id | **`38134430906`** |
| run URL | `<REPO-URL>/actions/runs/38134430906` |
| run_number | `96` |
| head_sha | `2f455ceed9a797bc7f86b4b76e03f08cfe932d4b`（= 本批 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T11:07:46Z` → updated **`2026-10-11T11:11:32Z`**（约 **3 分 46 秒**） |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion |
|---|---|---|
| 后端编译与单测 | `114455285370` | **success** |
| 文档一致性门禁（check-docs）｜**阻断** | `114455285382` | **success** |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114455285433` | **success** |
| 前端类型检查与构建 | `114455285494` | **success** |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114455589120` | **success** |

- **停止条件已满足**（run completed + **5 job 全 completed 全 success**）⇒ 闭环；**无 failure，未取 annotations，未自行修复、未重推**。
- **前端 job 内含单测步**【实测】：步骤 `类型检查（vue-tsc --noEmit）` → **`单元测试（vitest run）`** → `生产构建（vue-tsc + vite build）` **三步均 success** ⇒ CI 侧**实际执行**了 37 例单测（非仅本地取证）。E2E job `运行 E2E（29 用例）` success。
- 轮询 **3 轮**（iter1 = 4 job（**E2E 未出现**）→ iter2 = 5 job（E2E `in_progress`）→ iter3 全绿），每轮 `runs` + `jobs` 各 1 次 ⇒ 累计 **6 次匿名 API 调用**，间隔 **185s / 185s ≥ 3 分钟**（远低于 60/h）。
- 观察（同前几期，**非缺陷**）：**E2E job 晚出现**（依赖 backend）；守夜按「run completed **且** job ≥ 5 全 success」判定，**未因早期计数偏小而误判缺失、未提前闭环**。

---

## 八、锚定 SHA-256（v1 + v2）与零夹带核对

| 版本 | 文件 | SHA-256 | 字节数 | 用途 |
|---|---|---|---|---|
| v1 | `<SCRATCH>/chat-timeline-pkg/红队锚定-批1/diff-b1.patch` | `60aacbbb91f2bf444218d1579b30c414bb9e1fb8f62a5302c089a64df6409e65` | 59554 | 红队审查锚定（m-R1 未收口前） |
| **v2** | `<SCRATCH>/chat-timeline-pkg/红队锚定-批1/diff-b1-v2.patch` | **`e8658dc9a0007b58af79437a1c9c9030a3cf1a11a198348ce34c54a2e515e24c`** | **59560** | **收口修后重锚 = 本批提交依据** |

- 两值均经本机 `sha256sum` **实跑复算**，与「锚定说明-v2.txt」所载一致。
- **提交前复验**：按同口径重生成补丁 → SHA-256 与 v2 **逐字节一致**（`cmp` = IDENTICAL_BYTES）⇒ 送入提交的字节集合 = 经红队审查的字节集合，**零夹带**。重生成件与 `sha256sum` 记录存档 `evidence-p13/`。
- **提交后终验（committed == anchored，双向零夹带）**【实测】：对**已推送**的 HEAD 区间 `7879f53..HEAD` 按同一固定段序重生成 7 文件补丁，SHA-256 = `e8658dc9a0007b58af79437a1c9c9030a3cf1a11a198348ce34c54a2e515e24c`（59560 B），与锚定物 v2 `cmp` **IDENTICAL_BYTES**；且 7 个文件的 `git hash-object`（工作树）与 `git rev-parse HEAD:<file>`（提交后）**逐文件相等**（`e12b023` / `eed5055` / `dca00d1` / `33cea9a` / `6985165` / `329b9f2` / `d5cd774`，与锚定说明-v2 §6 的 v1→v2 blob 差异记载互洽）⇒ **入库字节集合 == 红队审查字节集合**。取证存档 `evidence-p13/committed-equals-anchor.txt`。
- v1→v2 差异（收口修）：仅 `frontend/src/stores/ai.ts`（−1 行 status 覆盖）与 `frontend/tests/parts-double-write.spec.ts`（+1 行断言同步），其余 5 文件段逐字节相同（详见 锚定说明-v2.txt §6）。

---

## 九、偏差与遗留（如实留痕）

1. **期号落点（非漏记）**：本期为**第 13 期**，按任务卡追加至 `<COMMIT-GOV>/提交报告.md`；但**第 11 / 12 期分别落在分立文件** `<SCRATCH>/step-wizard-pkg/提交报告-第11期.md` 与 `<SCRATCH>/vitest-run/提交报告-第12期.md`，故本**累积文件的期号自第 10 期直接跳至第 13 期**。本报告照录，供台账口径澄清（若需并档，属单独动作）。
2. **M2 回写 hash 的可行口径**：`R-130`~`R-133` 行尾留痕引用本批**代码提交** `1efab09` / `9a06c5b`（提交时序为「先码后档」）⇒ docs 落行时 hash 已存在，避免「在作者自身 hash 未知时写 hash」的空转。docs 笔自身 hash 见 §五 第 3 笔。
3. **脱敏唯一放过项**：环回地址 `127.0.0.1` 1 处**按 `docs/evidence` 既有书面保留口径保留**（既有 37 份同形文件；`补档-索引-2026-10-11.md` 明文「保留环回地址」）。属**口径选择而非缺陷**；如要求严格字面 0 命中，请裁决改写为占位符。
4. **CI 原始响应未保留（派生摘要代替）**：匿名 `runs` / `jobs` 响应含**含组织名的 URL 字段与 `actor` 等身份字段**，与本期「组织名 0 字面」口径冲突 ⇒ 不保留原始 JSON，改写为**字段白名单**派生摘要 `evidence-p13/ci-run-summary.json`（仅 run id / job id / 名称 / 状态 / conclusion / 时间戳 / 步骤清单；已实测 `grep -i` 组织名字面 **0 命中**）。原始响应目录已删除。轮询共 **6 次**调用。
5. **钩子与逃生口**：三次提交 pre-commit **全通过**；**未使用 `--no-verify`**；**未触发 `AI_SECRETS_ALLOW=1`**。R-129 口径的「公开组织名误报」本期**未出现**（本期全文 0 组织名字面）。
6. **未发生项**：无 BLOCKER / 无 CI failure / 无门禁红 / 无钩子阻断 / 无 force push / 无 non-fast-forward / 未读任何 `.env` / 未改动本批 7 文件之外的任何源码。
7. **遗留（不属本笔，已造册）**：红队 **m-R1** 已由收口修闭环并**随本批入库**（回归锁已同步断言）；**m-R2** 与 **o-R1~o-R4**、复核 **o1** 已登记为 **`R-138`**（§四表）交批 2。`R-137`（后端 AI 工具终态守卫批）与 `R-94` 死码族等仍在待办队列，**本期零触碰**。

---

## 十、本批产出物

| 内容 | 路径 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 13 期**条目） |
| 入库件（仓库内，3 笔提交 / 10 文件） | `1efab09`（前端 5）+ `9a06c5b`（后端 2）+ `2f455ce`（docs 3：`docs/M2-排期计划.md` + `docs/evidence/补档-CHATTIMELINE-*` ×2） |
| 基线链 | `7879f53` → **`1efab09`** → **`9a06c5b`** → **`2f455ce`**（= `origin/main`） |
| CI 取证 | `<SCRATCH>/chat-timeline-pkg/evidence-p13/ci-run-summary.json`、`ci-poll-iter1/2/3.log`、`ci-poll-p13.sh` |
| 门禁输出 | 同目录 `phase0-gates.txt`、`frontend-gates-p13.log`、`mvn-p13.log`、`check-docs-pre.txt`、`check-docs-post-m2.txt`、`phase5-head-gates.txt`、`head-gates-p13.log`、`mvn-head-p13.log`、`status-after.txt` |
| 锚定取证 | 同目录 `anchor-verify.txt`、`regen-diff-b1-v2.patch`、`committed-equals-anchor.txt`（提交后终验） |
| 脱敏取证 | 同目录 `phase1-desense.txt`、`desense-seed.txt`、`desense-7files.txt`、`desense-evidence.txt`、`desense-m2.txt`、`desense-scan.mjs` |
| 提交信息留痕 | 同目录 `msg-p13-1.txt` / `msg-p13-2.txt` / `msg-p13-3.txt` |
| 入库脚本 | 同目录 `make-evidence.py`（仓库外工具，不入库） |

---

# ai-example 合流项目 · 提交官报告（第 14 期：对话时序包批 2「子包②权威翻转 + 收口修 MAJOR-1」细分提交 3 笔 + 批 3 前置项登记 + 审查报告入库；**CI 后端 job 红、E2E 被跳过 ⇒ 按卡停手上报**）

- 本期执行者：**DeepSeek V4 Flash**（Kimi Code 子智能体 = 提交官；首页署名 + 文内自证，DC-19）——流程 = 阶段 1 前置机械验收（含锚定零夹带复算）→ 阶段 2 外部审查报告脱敏入库 → 阶段 3 M2 落行 → 阶段 4 细分提交 3 笔 → push → 阶段 5 CI 守夜（匿名）→ 阶段 6 本报告
- 授权来源：**Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签、终审 PASS，DC-19）**；上游终审对象 = 收口修重锚补丁 SHA-256 `09b003d25678a1167f705dbd642bcc91db8db4fb2fd603978b81f2a84c33572c`
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**全文无 GitHub 组织名字面**（第 10 期起格式纪律），run / job 一律以 **run id / job id** 取证
- 起点 HEAD：`2f455ce`｜push 后 HEAD = `origin/main`：**`6093f06`**
- 上游案卷（仓外）：`<SCRATCH>/chat-timeline-pkg/`（决策卡 / 施工卡-刷新版 / 施工报告-批2 / 复核报告-批2 / 红队报告-批2 / 收口修任务卡-批2 / 收口修报告-批2 / 红队锚定-批2）
- 取证件目录（仓外）：`<SCRATCH>/chat-timeline-pkg/evidence-p14/`（含 `probe/`）
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜yarn `1.22.22`｜node `v24.21.0`｜JDK 21（Oracle 21.0.12）｜Maven = IDEA 内置 `maven3`（全路径含空格，经引号直调）
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测/文档证据逻辑推出
- **结论：提交与推送成功，CI 守夜未全绿 ⇒ 按卡「停手如实上报」，未自行修复、未重推**
  - 3 笔中文细分提交已入库并推送（`2f455ce..6093f06`）；`check-docs` 两个时点（提交前 / HEAD 态）均 **6/6**
  - CI run **`38144704456`**（run_number `97`，head_sha = 本批 HEAD）= **completed / failure**：**后端 job 红**、E2E **skipped**（`needs: backend`），其余 3 job 全 success
  - 红因**根因已定位（实测探针 + 本机对照 + 工作流文档三重证据）**：批 2 新增测试类 `RedisChatMemoryRepositorySaveAllEmptyTest` 的「真 Redis 例」把环境守卫（`Assumptions.assumeTrue`）放在 `ping()` **之后**，而 `ping()` 在不可达时**抛异常**（探针实测 `RedisConnectionFailureException: Unable to connect to Redis`）⇒ 在**无 Redis** 的 CI 后端 job 上该用例是 **ERROR 而非 skip**；CI 后端 job 无 `services: redis`（工作流 `:32` 明示「不依赖 Redis」），E2E job 才有（`:114-120`）

---

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 1 前置机械验收 | ✅ `git status -uall` 恰 **12 条**（6 M + 6 ??）全属批 2、与重锚段序清单**逐条一致**；锚定复算 SHA-256 = `09b003d2…72c`（187172 B / 12 段）、`cmp` **IDENTICAL_BYTES** ⇒ **零夹带**；前端三连 **9 文件 / 93 passed / 0 failed** + typecheck exit 0 + build exit 0；`check-docs` **6/6** |
| 2 脱敏入库 | ✅ 2 份入库 `docs/evidence/`（复核 153→**155** 行 / 红队 176→**178** 行，红队定点改写 **2 处**），入库后 `check-docs` 仍 **6/6**（断言⑤ 覆盖） |
| 3 M2 落行 | ✅ `R-138` 行尾「完成留痕」+ 新增 `R-139`/`R-140`；**只追加、内容零删**，机械复验通过（numstat 3 增 / 1 删、恰 1 hunk、非目标行字节全等） |
| 4 细分提交 | ✅ 3 笔（`6bea4ca` / `c3f2579` / `6093f06`），逐文件显式 `git add`，pre-commit 三次全通过，**未用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1`** |
| 5 push + HEAD 门禁 | ✅ push `2f455ce..6093f06`（无 force / 无 non-fast-forward）；HEAD = `origin/main` = `6093f06`（ahead 0）；HEAD 态 `check-docs` **6/6** + `yarn typecheck` exit 0 |
| 5′ CI 守夜 | ❌ **未全绿**：run completed / **failure**；后端 job `failure`、E2E `skipped`、其余 3 job `success`（详见 §六）；已按卡停手 |
| 6 提交报告 | ✅ 本期（本报告追加至 `<COMMIT-GOV>/提交报告.md`） |

---

## 一、阶段 1 · 前置机械验收（HEAD=2f455ce 工作树态，12 文件在制品）【实测】

**1.1 `git status --porcelain -uall` 逐一归属（`evidence-p14/phase1-status.txt`）**

改 6（相对 HEAD 已跟踪 + 已修改）＋ 新 6（untracked），**共 12 条，逐条比对重锚说明 §「git status 全清单」完全一致，无外来夹带、无遗漏**：

| # | 状态 | 文件 | 归属 |
|---|---|---|---|
| 1 | ` M` | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java` | 批 2（o-R3 空写委托） |
| 2 | ` M` | `frontend/src/components/AiPanel/AiPanel.vue` | 批 2（段序渲染） |
| 3 | ` M` | `frontend/src/stores/ai.ts` | 批 2 + 收口修 F2 |
| 4 | ` M` | `frontend/src/types/ai.ts` | 批 2 |
| 5 | ` M` | `frontend/tests/parts-double-write.spec.ts` | 批 2 |
| 6 | ` M` | `frontend/tests/restore-reconcile.spec.ts` | 批 2 |
| 7 | `??` | `backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositorySaveAllEmptyTest.java` | 批 2 |
| 8 | `??` | `frontend/src/utils/parts.ts` | 批 2 + 收口修 F1 |
| 9 | `??` | `frontend/tests/ai-panel-parts-render.spec.ts` | 批 2 |
| 10 | `??` | `frontend/tests/frontend-tool-result-terminal.spec.ts` | **收口修新增 spec** |
| 11 | `??` | `frontend/tests/parts-reducer.spec.ts` | 批 2 |
| 12 | `??` | `frontend/tests/parts-residue-cleanup.spec.ts` | 批 2 |

**1.2 锚定对照（提交前复验，防夹带）**【实测】：按「红队锚定-批2/锚定说明-b2fix.md」§固定段序清单同口径重生成 12 段补丁（改 6 = `git diff HEAD -- <file>`，新 6 = `git diff --no-index -- /dev/null <file>`，段序不变、段 12 追加于末尾），重生成件与锚定物**同为 187172 B / 12 个 `^diff ` 段 / CR 行数 0**：

```
锚定物  <SCRATCH>/chat-timeline-pkg/红队锚定-批2/diff-b2fix.patch  09b003d25678a1167f705dbd642bcc91db8db4fb2fd603978b81f2a84c33572c
重生成  evidence-p14/regen-diff-b2fix.patch                        09b003d25678a1167f705dbd642bcc91db8db4fb2fd603978b81f2a84c33572c
cmp → IDENTICAL_BYTES
```

⇒ **送入提交的字节集合 = 经 K3 终审 / 红队锚定的字节集合，零夹带**（取证 `evidence-p14/anchor-verify.txt`）。

**1.3 前端门禁三连（工作树态实跑）**

| # | 命令 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | `cd frontend && yarn test` | 9 文件 / 93 passed / 0 failed | Test Files **9 passed (9)**，Tests **93 passed (93)**，Duration 38.18s，exit 0（`ready`4 / `sse-watchdog`4 / `parts-double-write`5 / `parts-reducer`15 / `frontend-tool-result-terminal`**16** / `tools-and-deadline`21 / `restore-reconcile`3 / `parts-residue-cleanup`21 / `ai-panel-parts-render`4） | ✅ |
| 2 | `cd frontend && yarn typecheck` | exit 0 | `vue-tsc --noEmit` Done in 12.16s，exit 0 | ✅ |
| 3 | `cd frontend && yarn build` | exit 0 | `✓ built in 42.44s`（spreadjs chunk 6,198.64 kB，与既有批次同形），exit 0 | ✅ |
| 4 | `node scripts/ci/check-docs.mjs`（仓库根） | 6/6 | 6/6 断言绿（`git ls-files` **969** / 实扫文本 966 / 豁免 3），exit 0 | ✅ |

**后端 Maven 全量**：按卡「收口修零触碰 backend 已由重锚逐字节证实（段 6 / 段 11 与批 2 基线**逐字节相同**）」⇒ 阶段 1 **不重跑**；本机全量复跑在**阶段 5′ 红因诊断**中补做（见 §六，442/0/0/0 · 70 XML，exit 0）。

---

## 二、阶段 2 · 外部审查报告脱敏入库 `docs/evidence/`【实测】

命名沿用该族既有风格（`补档-<包代号>-<批号>-<报告类型>-<执行者模型>.md`，与批 1「补档-CHATTIMELINE-*」同构且不撞名）：

| 源（仓外，占位符形式） | 落点 `docs/evidence/` | 源行 | 入库行 | 定点改写 |
|---|---|---|---|---|
| `<SCRATCH>/chat-timeline-pkg/复核报告-批2.md` | `补档-CHATTIMELINE-批2-复核报告-GLM-5.3.md` | 153 | **155** | **0** |
| `<SCRATCH>/chat-timeline-pkg/红队报告-批2.md` | `补档-CHATTIMELINE-批2-红队报告-DS-V4-Pro.md` | 176 | **178** | **2**（①主仓库盘符绝对路径 + 仓库目录名 → `<MAIN_REPO>`；②锚定 patch 的盘符 POSIX 分隔形路径 → `<SCRATCH>/chat-timeline-pkg/…`） |

- 页首标识行（两件同文，+1 行 + 1 空行 ⇒ 行数各 +2）：`> 来源=仓库外过程报告补档（2026-10-11，对话时序包批 2 仓外审查报告，署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）`
- **源文件（仓外）一律未改**；改写面经 `diff <(sed 's/\r$//' 源) <(sed 's/\r$//' 入库)` 逐行确认 = **仅上述 2 处 + 页首 2 行**，其余**零增删**（取证 `evidence-p14/desense-evidence.txt`、`make-evidence.txt`）
- **门禁**：两份入库件 `git add` 后进入 `git ls-files` 扫描面 ⇒ `check-docs` 断言⑤ 实测覆盖（`ls-files` 969 → 974 → **977**），**6/6 全绿**
- `docs/README.md` **未改**：该索引在批 1 入库时**没有**登记行（批 1 的 docs 笔只动 `docs/M2-排期计划.md` + 2 份 evidence），故按同口径**不新增登记**（详见 §七-2 的计数漂移申报）

---

## 三、阶段 3 · `docs/M2-排期计划.md` 落行【实测】

**纪律执行**：只追加 + 内容零删 + 行尾注记；整行重写为载体且旧文本逐字节保留；**编辑前重新实读** `:315-335`（现态最大号 = `R-138`，编号 `R-139`/`R-140` 实读为空号）；锚点落**表体内**（`:327` R-138 行之后、表体闭合空行之前）。

| 项 | 落点 | 内容摘要 |
|---|---|---|
| (a) R-138 行尾收尾注记 | `:327` R-138 | 「完成留痕（2026-10-11，只追加）」= **批 2 已闭环入库**（代码提交 `6bea4ca` 前端 / `c3f2579` 后端）：①`m-R2` 六写点补测（21 例 + 6 次逐点变异留证）②`o-R1` 封口对称（3 例）③`o-R2` 登记不修 ④`o-R3` 空写单行委托 `deleteByConversationId` + `SaveAllEmptyTest` 3 例 ⑤`o-R4` 登记不修 ⑥`o1` 镜像 sanitize 收口；并回写**收口修 MAJOR-1**（`frontend_tool_result` 按 `frame.status` 映射终态 + 帧路唯一段写点 + 16 例回归锁 / 全量 **9 文件 93 passed**）、复核与红队结论、重锚 SHA-256、两份入库件指针 |
| (b) 批 3 前置项登记（新增行） | `:328` **R-139** | GLM **m2**（`sanitizeMirrorResidues` 守卫死码，落点 `ai.ts:225-227`）→ **批 3'③b**；GLM **m3**（`m-R1` 申报粒度；修复本体已落地 `ai.ts:197-203`）→ **批 3 ③a**；DS 红队 **O1**（挂起卡终态 tag 死码，`AiPanel.vue:250-285`）→ **批 3 ③b**；**O2~O5** → **批 3 处置清单**（逐条带落点） |
| (b) 批 3 处置清单登记（新增行） | `:329` **R-140** | 施工报告-批2 §5 **观察 12 条逐条带落点**（含 5 复制按钮位置 → **CDP 人工面确认**、4 存量镜像 `streaming` 假态、7 重复终帧不复位 loading、12 `dist` 未验证等）+ 复核报告-批2 §5 **`o1`~`o4`**（含 **`o3` CDP 复现须显式 `VITE_DEV_PORT=5202` 且 `VITE_API_BASE` 指向后端**，因仓库 vite 默认 dev 端口为 5200 且 `strictPort:true`）+ 收口修报告-批2 §5-5 **口径角**（本地乐观 `cancelled` 遇后到 `FRONTEND_RESULT` 终帧时**本地胜**；**帧胜路径留候选**，一行可切）；去向 = 批 3 处置清单 |

**机械复验（防「零删」被违反）**【实测】（取证 `evidence-p14/m2-verify.txt`）：

- `git diff --numstat` = **3 增 / 1 删**；`git diff -U0` 恰 **1 个 hunk** = `@@ -327 +327,3 @@`（仅目标行重写 + 2 行插入，无第二处改动）
- **`1..326` 行与 `HEAD` 逐行字节全等**；`HEAD :328` 及其后（含文件尾）**逐行字节全等**
- 目标行：旧文本（去尾表格分隔符 ` |`）为新行**前缀**，新行以 ` |` 收尾 ⇒ 「旧文本逐字节保留 + 行尾注记」成立（与批 1 `R-130` 的行尾形态同构；追加内容 1476 字符）
- 行数 **493 → 495**；文件仍为 **CRLF**（与既有行尾一致，未发生行尾转换）

**回写 hash 口径**：`R-138` 完成留痕引用本批**前两笔代码提交** `6bea4ca` / `c3f2579`（提交时序安排为「先码后档」，使 docs 落行时 hash 已存在，无需第二笔补丁提交）；docs 笔自身 hash 见 §四 第 3 笔。

---

## 四、阶段 4 · 细分提交（3 笔）【实测】

| 序 | SHA | 主题（首行） | 文件（逐文件显式 `add`） | 统计 |
|---|---|---|---|---|
| 1 | **`6bea4ca`** | `feat(frontend): 对话时序包批 2 子包②权威翻转（parts 唯一真值）+ 收口修 MAJOR-1` | `frontend/src/stores/ai.ts`、`frontend/src/components/AiPanel/AiPanel.vue`、`frontend/src/types/ai.ts`、`frontend/src/utils/parts.ts`、`frontend/tests/parts-double-write.spec.ts`、`frontend/tests/restore-reconcile.spec.ts`、`frontend/tests/parts-residue-cleanup.spec.ts`、`frontend/tests/parts-reducer.spec.ts`、`frontend/tests/ai-panel-parts-render.spec.ts`、`frontend/tests/frontend-tool-result-terminal.spec.ts` | **10 files / +2561 −529**（含 5 新） |
| 2 | **`c3f2579`** | `test(backend): saveAll 空列表委托 deleteByConversationId（o-R3）+ SaveAllEmptyTest` | `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java`、`backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositorySaveAllEmptyTest.java` | **2 files / +139 −3**（含 1 新） |
| 3 | **`6093f06`** | `docs(m2,evidence): R-138 落行收尾（批 2 已闭环）+ 批 3 前置项登记 R-139/R-140 + 两份外部审查报告入库（对话时序包批 2）` | `docs/M2-排期计划.md`、`docs/evidence/补档-CHATTIMELINE-批2-复核报告-GLM-5.3.md`、`docs/evidence/补档-CHATTIMELINE-批2-红队报告-DS-V4-Pro.md` | **3 files / +336 −1**（含 2 新） |

- **逐文件显式 `git add`**（未用 `git add -A` / `.` / `-N`）⇒ 无其他在途改动混入；三次提交的暂存区清单与上表**逐项一致**（前端笔暂存清单实读 = 恰 10 条、后端笔 = 恰 2 条、docs 笔 = 恰 3 条）
- **钩子留痕**：三次提交均输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`，exit 0 ⇒ **未使用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1` 放行分支**（近五批未触发口径维持）
- 全 3 笔提交后 `git status --porcelain -uall` = **空**（工作树干净）
- **提交后零夹带终验**【实测】（取证 `evidence-p14/committed-equals-anchor.txt`）：对**已入库**的 HEAD 区间 `2f455ce..HEAD` 按同一固定段序重生成 12 段补丁，SHA-256 = `09b003d25678a1167f705dbd642bcc91db8db4fb2fd603978b81f2a84c33572c`，与锚定物 `cmp` **IDENTICAL_BYTES**；且 12 个文件的 `git hash-object`（工作树）与 `git rev-parse HEAD:<file>`（提交后）**逐文件相等**（`00109b0` / `a5d1f82` / `4108fd5` / `d782744` / `0448442` / `f7c0be2` / `90e1588` / `36c1f62` / `2653796` / `9e9c6ae` / `d6ec7ec` / `544245a`）⇒ **入库字节集合 == 红队锚定字节集合**。

---

## 五、push、远端 HEAD 与 HEAD 态门禁复跑【实测】

- `git push origin main` → **`2f455ce..6093f06  main -> main`**（exit 0）；**无 non-fast-forward 拒绝、未使用 force**；推送前已确认远端 = `<REPO-URL>`（GitHub 公开仓，fetch/push 同源）
- 推送后：HEAD = `6093f06572154ab859535036f294f28658621cbf` = `origin/main`；`git rev-list --left-right --count origin/main...HEAD` = `0 0`（ahead 0 / behind 0）；`git status --porcelain -uall` = 0 条
- **HEAD 态门禁复跑**：`node scripts/ci/check-docs.mjs` = **6/6**（`git ls-files` **977** / 实扫文本 974 / 豁免 3，exit 0）；`cd frontend && yarn typecheck` = `vue-tsc --noEmit` Done in 6.38s，**exit 0**（`evidence-p14/check-docs-post.txt`、`head-typecheck.txt`）。`yarn test` / `yarn build` 在阶段 1 已对**同一字节**（工作树 == 入库内容，§四 终验）实跑绿，按卡未重复。

---

## 六、CI 守夜（匿名轮询 · 预算 60/h · 实耗 6 次调用）【实测】——**未全绿，按卡停手上报**

| 项 | 值 |
|---|---|
| run id | **`38144704456`**（run_number `97`） |
| head_sha | `6093f06572154ab859535036f294f28658621cbf`（= 本批 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T13:49:23Z` → updated **`2026-10-11T13:51:57Z`**（约 **2 分 34 秒**） |
| run 级 | **status = completed，conclusion = failure** |

| job | id | conclusion | 备注 |
|---|---|---|---|
| 后端编译与单测 | `114485515510` | **failure** | 失败步 = `编译并运行单测（mvn test，全量用例）`（13:49:26 → 13:51:10） |
| 前端类型检查与构建 | `114485515698` | success | 步骤 `类型检查（vue-tsc --noEmit）` → **`单元测试（vitest run）`** → `生产构建（vue-tsc + vite build）` 三步均 success（CI 侧**真实执行**了单测步，非仅类型/构建；**用例数 93 系本机同字节实测值**，CI 步骤日志不可匿名取，故不声称 CI 侧计数） |
| 文档一致性门禁（check-docs）｜阻断 | `114485515636` | success | 6/6（CI 侧独立复算） |
| 全历史脱敏扫描（报告制） | `114485515600` | success | 报告制，不计门禁 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114485867191` | **skipped** | `needs: backend` ⇒ 后端红即跳过（**非缺失、非误判**） |

**取数受限**：`GET …/actions/jobs/<job>/logs` 匿名返回 **HTTP 403「Must have admin rights to Repository」**；`check-runs/<job>/annotations` 仅给出通用注记 `Process completed with exit code 1.`。⇒ **CI 侧失败用例名无法匿名直取**，故根因以**本机实测探针 + 本机对照 + 工作流文档**三路证据判定（下述），并如实标注推断边界。

**根因诊断（【实测】+【推断】混合，证据链）**：

1. **【实测】探针**（仓外 `evidence-p14/probe/PingNoRedis.java`，同源 spring-data-redis 3.5.11 / lettuce 6.6.0 / netty 4.1.132，指向**无监听端口**以模拟无 Redis；不动本机 Memurai、不改仓库任何文件）：
   - `LettuceConnectionFactory.afterPropertiesSet()` → **OK（不建连、不抛）**
   - `template.getConnectionFactory().getConnection().ping()` → **抛 `org.springframework.data.redis.RedisConnectionFailureException: Unable to connect to Redis`**
   ⇒ 新测试类 `emptySaveAllOnRealRedisRemovesDataAndIndex` 把守卫 `Assumptions.assumeTrue("PONG".equalsIgnoreCase(pong), …)` 放在 **`ping()` 之后**：Redis 不可达时**根本走不到守卫**，用例是 **ERROR（不是 skip）**。
2. **【实测】工作流文档**：`.github/workflows/ci.yml` 后端 job（`:29-50`）**无 `services:` 块**，且 `:32` 注释明写「**不依赖 Redis**、AI key 与 Windows 专属物」；只有 E2E job 有 `services.redis`（`:114-120`）。⇒ CI 后端 job 的 `127.0.0.1:6379` **无监听**。
3. **【实测】本机对照**：本批**同一字节**在本机（Memurai 在线）跑全量 Maven = `Tests run: 442, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、`MVN_EXIT=0`、surefire XML **70**（逐文件属性聚合 `tests=442 failures=0 errors=0 skipped=0`）；其中 `RedisChatMemoryRepositorySaveAllEmptyTest` **3 例 / Skipped 0**（真 Redis 例真跑通过）。⇒ 本地绿 / CI 红之间的**唯一环境差异变量 = Redis 可达性**。
4. **【推断】结论**：CI 后端 job 红因 = 该真 Redis 例在无 Redis 环境下 ERROR；E2E 因 `needs: backend` 连锁跳过。其余 4 job 全绿（前端 job 含单测步 success，其 93 例为本机同字节实测值）⇒ 前端侧（本批主体）不受影响。
   - **未直接确证之处（如实）**：CI 日志不可匿名取得，故「失败用例 = 本类」系由「后端唯一 delta = 该类 + 其 3 行源码改动」与上述三点推出，**非** CI 原始日志直证。

**处置（严格按卡）**：结论非全绿 ⇒ **停手，未自行修复、未重推、未改任何源码**。供 K3 裁决的**候选修法（本棒未实施）**：把环境守卫移到 `ping()` **之前**（先 `try { ping() } catch (Throwable) { … }` 归一为「不可达」再 `assumeTrue`），或改用 `@EnabledIf`/`@DisabledOnCi` 语义、或在后端 job 内也起 `services: redis`——三者均属**新任务卡范围**，须另行授权与重锚。

---

## 七、偏差与遗留（如实留痕）

1. **【最重要】CI 未全绿（唯一未闭环项）**：后端 job 红、E2E skipped；根因 = 批 2 新增「真 Redis 例」的环境守卫失效（§六）。本棒按卡**停手上报**，**未修复、未重推**；该缺陷属**测试环境适配缺陷**，不属本批**代码内容**缺陷（前端 job 与 check-docs 在 CI 侧均绿，前端 93 例 / 后端 442 例为本机同字节实测）；但**它使本批 CI 无法闭环**，是否补一笔修正提交由指挥官裁决。
2. **`docs/README.md` 计数漂移（未改，如实申报）**：`:44` 行「`docs/evidence/补档-*.md` 共 24 个文件 = 23 份补档 + 本索引」自 VITESTA / CHATTIMELINE 两次入库起即陈旧（**现态实测 31 个文件 = 30 份补档 + 本索引**）。本批**未改**：① 卡面口径为「若批 1 入库时有登记行则同构补登记」，而批 1 **未**登记该索引行；② 该行主题是「苞2 补档批」，直接改写文件数会把后续批次的文件**误归**苞2 批。建议另立 docs 小批，按「苞2 批 23 份 + 后续入库 N 份」分列口径统一订正。
3. **本机全量 Maven 复跑（超出卡面要求，属诊断性补充）**：卡面明示「本地不必重跑 mvn 全量」（收口修零触碰 backend 由重锚逐字节证实）。因 §六 CI 后端红，本棒在**同一字节**上补跑一次本机全量作为「本地绿 / CI 红」的对照证据（442/0/0/0 · 70 XML），**未改任何源码、未 repackage 入库**；该次运行只落 surefire 报告与 `target/`（gitignored），工作树 `git status` 全程为空。
4. **脱敏终扫（新增行口径 0 命中）**【实测】：扫描器 = `evidence-p14/desense-scan.mjs`（规则 = 盘符路径 / POSIX 挂载形 / 用户名 / 主机名 / 内网 IP / 密钥形态 / **命名纪律三态禁则**（NFKC + 隐形字符双态 + 去分隔符连写），占位符整段先剔除，与 `check-docs` 断言 ①/⑤ 同口径）
   - **本批新增行口径**（三处：docs 暂存 diff、`6bea4ca`、`c3f2579`）**0 命中**（`desense-staged-added.txt`）
   - 全文件口径下仅两类**既有保留项**：① 后端新测试类内**环回地址 `127.0.0.1`** 2 处（卡面明示「保留 127.0.0.1」）；② `docs/M2-排期计划.md:283` 的**公开组织名字面** 1 处（**既有行、非本批新增**，属「保留 GitHub 公开信息」口径）。本批新增/改写内容**无任何**盘符、用户名、主机名、内网 IP、密钥形态
   - 仓外两份源报告**零改动**；入库件改写面 = 2 处 + 页首 2 行（§二）
5. **`%TEMP%` 环境变量写法保留**：两份源报告含 `%TEMP%/glu-b2-*`、`%TEMP%/dsb2-*`（Windows 环境变量，非盘符绝对路径、不披露用户名/主机名），按**不属脱敏改写面**保留，与批 1 入库件的「技术事实保留」口径一致。
6. **期号与落点**：本期 = **第 14 期**，按卡追加至 `<COMMIT-GOV>/提交报告.md`（与第 13 期同目录、同结构）；第 11 / 12 期另落在分立文件（承接第 13 期报告 §九-1 的台账口径澄清，非本期问题）。
7. **未发生项**：无 force push、无 `--no-verify`、无 `AI_SECRETS_ALLOW` 放行、无 `git add -N`、无 `stash`/`clean`/`checkout`、未读任何 `.env`、未触碰 6 个源分支克隆目录、未改动本批 12 文件之外的任何源码、`git status` 全程无卡面清单外文件混入。
8. **遗留（非本期动作）**：`R-139` / `R-140` 交批 3（③a/③b 与处置清单）；红队 `O1` 死码清理、`o3` CDP 配方口径、`§5-5` 帧胜候选均随批 3；`R-137`（后端 AI 工具终态守卫批）与 `R-94` 死码族仍待办，**本期零触碰**。

---

## 八、本批产出物

| 内容 | 路径 / 值 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 14 期**条目） |
| 入库件（仓库内，3 笔提交 / 15 文件） | `6bea4ca`（前端 10）+ `c3f2579`（后端 2）+ `6093f06`（docs 3：`docs/M2-排期计划.md` + `docs/evidence/补档-CHATTIMELINE-批2-*` ×2） |
| 基线链 | `2f455ce` → **`6bea4ca`** → **`c3f2579`** → **`6093f06`**（= `origin/main`） |
| CI 取证 | `<SCRATCH>/chat-timeline-pkg/evidence-p14/ci-run-summary.json`（字段白名单派生摘要，实测 0 组织名/URL 字面）、`ci-poll-p14.log` |
| 红因诊断取证 | 同目录 `probe/PingNoRedis.java` + `probe/probe-run.txt` + `probe/cp.txt`；`mvn-head-p14.log`（本机全量对照） |
| 门禁输出 | 同目录 `phase1-status.txt`、`fe-test-pre.txt`、`fe-typecheck-pre.txt`、`fe-build-pre.txt`、`check-docs-pre.txt`、`check-docs-post.txt`、`head-typecheck.txt`、`m2-verify.txt`、`push.txt`、`commit-{1,2,3}.txt` |
| 锚定取证 | 同目录 `anchor-verify.txt`、`regen-diff-b2fix.patch`、`committed-diff-b2fix.patch`、`committed-equals-anchor.txt` |
| 脱敏取证 | 同目录 `desense-scan.mjs`、`desense-final.txt`、`desense-staged-added.txt`、`desense-evidence.txt`、`make-evidence-p14.mjs`、`make-evidence.txt` |
| 提交信息留痕 | 同目录 `msg-p14-1.txt` / `msg-p14-2.txt` / `msg-p14-3.txt` |

---

# ai-example 合流项目 · 提交官报告（第 14 期补记：CI 热修 —— `SaveAllEmptyTest` 真 Redis 例探活前置化）

- 本补记执行者署名（按任务卡，DC-19）：**DeepSeek V4 Flash**（提交官子智能体）
- **【自证 · 如实】** 本棒运行时可自证的实际模型标识 = **Kimi Code 子智能体（Kimi 系列）**；本棒**无法自证为 DeepSeek V4 Flash**。署名按卡写定，与实际标识的差异在此留痕申报（见 §7-1）。
- 授权来源：**Kimi K3 指挥官裁决（2026-10-11，小批次 CI 修复批）**；口径 **DC-20：红队豁免，机械验收 + 脱敏终扫不豁免**
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**本段全文无 GitHub 组织名字面**，CI 一律以 **run id / job id** 取证
- 起点 HEAD = `6093f06`（批 2 入库态）｜本次热修提交：**`3edaeb9`**｜push 后 HEAD = `origin/main` = `3edaeb900dcc446b918913738a6f527c780e7b81`
- 取证件目录（仓外）：`<COMMIT-GOV>/evidence-p14/`（本补记新增件，文件名以 `hotfix` 标识；同名副本另存 `<SCRATCH>/chat-timeline-pkg/evidence-p14/`，与第 14 期取证件同目录）
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测/文档证据逻辑推出
- **结论：两态实测通过、全量回归 442/0/0/0、门禁 6/6、CI 全绿（5 job 全 success）⇒ 闭环**

## 1. 根因与修法【实测】

**根因（一句话）**：批 2 新增测试类 `backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositorySaveAllEmptyTest.java` 的「真 Redis 例」把环境守卫 `Assumptions.assumeTrue("PONG".equalsIgnoreCase(pong), …)` 放在 `ping()` **之后**，而 `ping()` 在 Redis 不可达时**直接抛 `RedisConnectionFailureException`**（不是返回非 PONG 字符串）⇒ 守卫永远走不到，**无 Redis** 的 CI 后端 job 上该用例是 **ERROR 而非 SKIP**（CI 后端 job 无 `services:` 块，工作流 `:32` 注释明示「不依赖 Redis」，仅 E2E job 有 `services.redis`）。

**修法 = 探活前置化**（`try { ping } catch → reachable=false` → `assumeTrue(reachable, …)`），使无 Redis 时用例 **SKIP**、有 Redis 时行为**逐字不变**。本文件是本补记**唯一**改动文件（**未动**被测主类、**未动** `ci.yml`、未采用「后端 job 也起 `services: redis`」路线）。

实测暴露的**两处必要伴随改动**（同一文件内，如实申报；卡面只给了「探活前置」一处）：

| # | 改动 | 必要性（实测依据） |
|---|---|---|
| ① | `@AfterEach cleanup()` 的两条 Redis 调用加 `try/catch` | **不加则 SKIP 仍变 ERROR**：实测中间态 `Tests run: 3, Failures: 0, Errors: 1, Skipped: 0`，错误行 = `RedisChatMemoryRepositorySaveAllEmptyTest.cleanup:98 » RedisConnectionFailure Unable to connect to Redis`（JUnit 5 中 assume 中止后 `@AfterEach` 仍执行，清理抛错即把 SKIP 升级为 ERROR）⇒ 见 `hotfix-red-down-try1.txt` |
| ② | 端点由硬编码 `127.0.0.1:6379` 改为可覆盖（`-Dtest.redis.host` / `-Dtest.redis.port`，**默认值与原字面完全相同**） | 该类是**直接 `new LettuceConnectionFactory(host, port)`**，**不读** Spring 配置 ⇒ 卡面建议的 `-Dspring.data.redis.port=18399` **注入不进去**，死端口死态无法实测；加覆盖后卡面要求的「死端口注入」才可实施（**不动本机 Memurai 服务**） |

- 语义不变性【实测】：覆盖仅走系统属性，默认 `127.0.0.1:6379`；上表 ② 不改变任何既有断言、不改其余 2 例（Mockito 桩例）、不改主类。
- 端点覆盖**已被实证生效**（非静默失效）：死态报错栈与断言消息均指向 `127.0.0.1:18399`（`hotfix-red-down-try1.txt` 栈顶 `Connection refused: getsockopt: /127.0.0.1:18399`）。

## 2. 两态实测【实测】

| 态 | 注入 | surefire 结果 | Maven 退出码 | 证据件 |
|---|---|---|---|---|
| **① Redis 可达**（本机 Memurai 在线；端口探活实测回 `+PONG`） | 无（默认 `127.0.0.1:6379`） | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` | `BUILD SUCCESS` / `MVN_EXIT=0` | `hotfix-red-up.txt` |
| **② Redis 不可达**（死端口，**未停本机 Memurai**） | `-Dtest.redis.port=18399` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 1` | `BUILD SUCCESS` / `MVN_EXIT=0` | `hotfix-red-down.txt` + `surefire-down-RedisChatMemoryRepositorySaveAllEmptyTest.xml`（`tests="3" errors="0" skipped="1" failures="0"`，`<skipped type="org.opentest4j.TestAbortedException">`） |

- 结论：**无 Redis ⇒ SKIP（非 ERROR），有 Redis ⇒ 3 例照常真跑全绿**；CI 后端 job 的失败模式被消除。
- 反向留痕（中间态）：`hotfix-red-down-try1.txt` = 仅改探活、未加清理守卫时的实测 = `Errors: 1 / Skipped: 0`（cleanup 抛错），证实 §1-① 是必要条件。

## 3. 回归与门禁【实测】

| 项 | 命令 | 结果 |
|---|---|---|
| 全量后端测试（可达态） | 包装脚本 `mvn-run.sh -B test`（Maven 全路径含空格经引号直调） | `Tests run: 442, Failures: 0, Errors: 0, Skipped: 0`·`BUILD SUCCESS`·`MVN_EXIT=0`；surefire XML **70** 个，逐文件属性聚合 `tests=442 failures=0 errors=0 skipped=0`（与批 2 基线同数字） |
| 文档门禁（工作树/暂存区口径，提交前） | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**，exit 0 |
| 文档门禁（HEAD 态复跑，push 后） | 同上 | **6/6 断言绿**，exit 0（`check-docs-head-hotfix-p14.txt`） |

## 4. 提交、push 与 CI 守夜【实测】

**提交前机械验收**：`git status --porcelain -uall` = **恰 1 条**（` M` 该测试类），无卡面清单外文件混入 ⇒ 继续；**显式逐文件 `git add`**（未用 `-N`/`-A`）。

**提交信息**（`-F` 落件 `msg-p14-hotfix.txt`）：

```
fix(backend): SaveAllEmptyTest 真 Redis 例探活前置化（CI 无 Redis 环境 ERROR→SKIP，R-140 关联批 2 收口）
```

| 项 | 值 |
|---|---|
| commit | **`3edaeb9`**（`1 file changed, 24 insertions(+), 5 deletions(-)`） |
| pre-commit 钩子 | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` ⇒ **未使用 `AI_SECRETS_ALLOW` 放行**，**未用 `--no-verify`** |
| push | `git push origin main` → `6093f06..3edaeb9  main -> main`，`PUSH_EXIT=0`；push 后工作树 `git status` 条数 **0** |

**CI 守夜（匿名轮询 · `--ssl-no-revoke` · 间隔 150s · 预算 60 次/h · 实耗 3 轮 × 2 次 = 6 次调用）**：

| 项 | 值 |
|---|---|
| run id | **`38145905293`**（run_number `98`） |
| head_sha | `3edaeb900dcc446b918913738a6f527c780e7b81`（= 本补记 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T14:07:25Z` → updated `2026-10-11T14:11:12Z` |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion | 时段（UTC） |
|---|---|---|---|
| 后端编译与单测 | `114489072669` | **success**（上轮红点所在 job，本轮绿） | 14:07:29 → 14:09:11 |
| 前端类型检查与构建 | `114489072708` | **success** | 14:07:28 → 14:08:19 |
| 文档一致性门禁（check-docs）｜阻断 | `114489072747` | **success** | 14:07:29 → 14:07:39 |
| 全历史脱敏扫描（报告制 · 不计门禁） | `114489072558` | **success** | 14:07:28 → 14:09:58 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114489426071` | **success**（上轮因 `needs: backend` 跳过，本轮真跑并绿） | 14:09:13 → 14:11:11 |

- **五 job 全 completed 且全 success，run 级 completed/success ⇒ 闭环。**停止条件按卡执行（run completed 且 job 数 ≥5 全 completed）；E2E job 属**晚出现**（`needs: backend`），第 1 轮仅见 4 job 时**未误判**，第 2 轮见 5 job、第 3 轮见全 completed。
- 对比：上轮同一 workflow 的 run `38144704456`（批 2 HEAD `6093f06`）= 后端 **failure** / E2E **skipped** / 其余 3 job success（见第 14 期正文 §六）。

## 5. 脱敏终扫（新增行口径 0 命中）【实测】

- 扫描器 = `<SCRATCH>/chat-timeline-pkg/evidence-p14/desense-scan.mjs`（规则 = 盘符路径 / POSIX 挂载形 / 用户名 / 主机名 / 内网 IP / 密钥形态 / 命名纪律三态禁则；占位符整段先剔除，与 `check-docs` 断言 ①/⑤ 同口径）
- **新增行口径**（本次提交 24 增 / 5 删，`git diff -U0` 取 `+` 行 = `_added-hotfix-p14.txt`）：**硬命中 0**；仅 `环回地址(仅统计)` **2 处**（`127.0.0.1` 写于注释与默认值，卡面既有书面口径「保留 127.0.0.1」）
- 全文件口径（`desense-hotfix-file.txt`）：**硬命中 0**；环回地址 3 处（1 处为既有行）
- 另做字面复核 `grep -i`：新文件与新增行内 **无组织名、无仓库 URL、无用户名、无主机名、无盘符路径**（0 命中）
- 本次提交**未涉及** `.env`/密钥文件、未改动历史、未触碰其他源分支克隆目录

## 6. 未发生项

无 force push｜无 `--no-verify`｜无 `AI_SECRETS_ALLOW` 放行｜无 `git add -N`/`-A`｜无 `stash`/`clean`/`checkout`｜未停本机 Memurai 服务｜未改 `ci.yml`｜未改被测主类｜未 `repackage` 入库｜未读任何 `.env`｜除本文件（及为实测而新增的覆盖点）外**零改动**。

## 7. 异常申报与诚实边界（如实留痕）

1. **【最重要】执行者署名 vs 运行时可自证模型不一致**：卡面署名 = DeepSeek V4 Flash，而本棒运行时可自证标识 = **Kimi Code 子智能体（Kimi 系列）**。本棒**不声称**自己具备 DeepSeek V4 Flash 身份 —— 署名按卡写定、差异在此留痕；是否需要改署名口径由指挥官裁决。
2. **取证件目录路径与卡面不符**：卡面给 `<COMMIT-GOV>/evidence-p14/`，该目录**原不存在**（第 14 期取证件实际在 `<SCRATCH>/chat-timeline-pkg/evidence-p14/`）。处置 = 按卡面路径新建并落件，同时**副本**回第 14 期证据目录，两处互为一致（`cmp` 同字节）。
3. **修法超出卡面「唯一改动」的字面范围（同一文件内）**：除「探活前置」外，另加 `@AfterEach` 清理守卫（§1-①，实测为必要条件：不加则仍 ERROR）与端点覆盖钩子（§1-②，为实施卡面要求的死端口死态实测所必需）。三者合计仍是**唯一 1 个改动文件**、**未动**主类与 `ci.yml`；如指挥官判定端点覆盖钩子不宜留在测试类内，可另开小卡改为「跑前临时改常量」的变通（本棒未采用，因那会改动工作树字节并与"入库件即被测件"口径冲突）。
4. **【推断】成分**：CI 侧失败用例名/断言文本**无法匿名直取**（`jobs/<id>/logs` 匿名 403），故上轮「失败用例 = 本类」系由「后端唯一 delta = 该类 3 处改动 + 探针实测 + 工作流文档」三路推定（第 14 期正文已留痕）；本补记的**修复有效性**由本轮 CI 后端 job 转绿 + 本机两态实测**双重实证**，不依赖上述推断。
5. **CI 侧计数**：CI 后端 job 的用例数无法匿名取值（日志 403），故**不声称** CI 侧 442 这一数字；442/0/0/0·70 XML 为**本机同一字节**实测值（CI 侧只主张「后端 job 绿」这一结论）。
6. **`docs/README.md` 计数漂移**（第 14 期正文 §七-2 已申报的遗留）：本补记**未触碰**，仍属另立 docs 小批的范畴。

## 8. 本补记产出物

| 内容 | 路径 / 值 |
|---|---|
| 入库件（仓库内，1 笔提交 / 1 文件） | `3edaeb9`：`backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryRepositorySaveAllEmptyTest.java`（+24 / −5） |
| 基线链 | `6093f06` → **`3edaeb9`**（= `origin/main`） |
| 两态取证 | `<COMMIT-GOV>/evidence-p14/hotfix-red-up.txt`、`hotfix-red-down.txt`、`hotfix-red-down-try1.txt`（中间态反证）、`surefire-down-RedisChatMemoryRepositorySaveAllEmptyTest.xml` |
| 回归 / 门禁取证 | 同目录 `mvn-full-hotfix-p14.log`、`mvn-full-hotfix-p14-summary.txt`、`check-docs-head-hotfix-p14.txt` |
| 提交 / push 取证 | 同目录 `msg-p14-hotfix.txt`、`commit-hotfix-p14.txt`、`push-hotfix-p14.txt` |
| CI 取证 | 同目录 `ci-poll-hotfix-p14.mjs`、`ci-poll-hotfix-p14.log`、`ci-run-summary-hotfix-p14.json`（字段白名单派生摘要：run id / run_number / head_sha / status / conclusion / 时间戳 / 5 job 的 id/name/status/conclusion，**0 组织名字面**） |
| 脱敏取证 | 同目录 `_added-hotfix-p14.txt`（= 入库 24 行，与 `git diff 6093f06 3edaeb9` 的 `+` 行 `cmp` **IDENTICAL**）、`desense-hotfix-added.txt`、`desense-hotfix-file.txt`、`desense-hotfix-report-section.txt`（本报告段自身口径） |

---

# ai-example 合流项目 · 提交官报告（第 15 期：对话时序包批 3③a「工具结局不得谎报 · 三态收口」全量入库 —— 10 文件单笔原子提交 + 决策卡/施工卡/三报告/两会审报告入库 + M2 落行 R-141/R-142/R-143 与 R-139/R-140 逐项销记；**CI 五 job 全绿 ⇒ 闭环**）

- 本期执行者：**DeepSeek V4 Flash**（Kimi Code 子智能体 = 提交官；首页署名 + 文内自证，DC-19）——流程 = 阶段 1 前置机械验收（含锚定零夹带双向复算 + 脱敏终扫）→ 阶段 2 施工报告订正（仓外文书，入库前）→ 阶段 3 提交 1（10 文件原子红线）→ 阶段 4 M2 落行 + evidence 入库 → 阶段 5 提交 2（docs）→ push → 阶段 6 CI 守夜（匿名）→ 阶段 7 本报告
- **【自证 · 如实】** 本棒运行时可自证的实际模型标识 = **Kimi Code 子智能体（Kimi 系列）**；本棒**无法自证为 DeepSeek V4 Flash**。署名按卡写定（提交官钉版口径 DC-19），与实际标识的差异在此留痕申报（见 §八-1）。
- 授权来源：**Kimi K3 指挥官批 3 ③a 提交指令（2026-10-12）**；上游依据 = 《决策卡-批3③a.md》（K3 代签，2026-10-11）+《批3③a-施工卡.md》（定稿）；口径 **DC-20：机械验收 + 脱敏终扫不豁免**
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**本段全文无 GitHub 组织名字面**，CI 一律以 **run id / job id** 取证
- 起点 HEAD = **`3edaeb9`**（= `origin/main`，批 2 热修态）｜本批两笔提交：**`f0210f2`**（feat）+ **`505f6d4`**（docs）｜push 后 HEAD = `origin/main` = `505f6d4e0fa1360f04a3dbe3313842f1513a0952`
- 取证件目录（仓外）：`<SCRATCH>/chat-timeline-pkg/evidence-p15/`
- 上游案卷（仓外）：`<SCRATCH>/chat-timeline-pkg/`（决策卡-批3③a / 批3③a-施工卡 / 施工报告-批3③a / 复核报告-批3③a / 红队报告-批3③a / 会审-批3③a-GLM / 会审-批3③a-DSPro / `批3a-evidence/`）
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜`core.autocrlf=true`｜yarn `1.22.22`｜node `v24.21.0`
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测/文档证据逻辑推出
- **结论：机械验收 3/3 PASS、锚定双向核验 PASS（10/10 逐文件字节相等）、脱敏终扫 0 命中、两笔提交入库并推送、CI run 级 success + 五 job 全 success ⇒ 闭环**

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 1 机械验收 | ✅ `git status --porcelain` 恰 **10 条**（8 ` M` + 2 `??`），与卡面清单**逐条一致**、无卡外夹带；锚定复算 SHA-256 = `6d2eeb1c…55e7`（10 段 / 62211 B / +771 −102），`cmp` **IDENTICAL_BYTES**，EOL 归一化后亦相等；前端三连 **11 文件 / 110 passed / 0 failed** + typecheck exit 0 + build exit 0；`check-docs` **6/6** |
| 1′ 提交后零夹带终验 | ✅ 从已入库区间 `3edaeb9..HEAD` 按同一段序重生成补丁 = **原样字节相等**、SHA-256 仍 `6d2eeb1c…55e7`；**10/10 逐文件 blob 哈希**与锚定 `index` 行 b 侧相等 |
| 2 施工报告订正（仓外） | ✅ 入库前订正 2 处：§11 统计 `433+/100-` → 「8 跟踪文件 +441/-102（含 2 新测试文件合计 +771/-102）」；§5「CDP 9334」→「CDP 9344」；行数 186 不变、CR 0，其余零改动 |
| 3 提交 1（原子红线） | ✅ **`f0210f2`** 单笔 10 文件（`+771 −102`），A4+A5+A8+A9+A10+X1+`ai.ts:1705/:246`+D3(iii)+T1/T2/T3 **不可拆面同提交**；逐文件显式 `git add`；pre-commit 通过，**未用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW`** |
| 4 M2 落行 | ✅ 新增 **R-141（`:330`）/ R-142（`:331`）/ R-143（`:332`）**；行尾注记 **R-84（`:276`）/ R-97（`:289`）**；**R-139（`:328`）/ R-140（`:329`）逐项销记**——**只追加、内容零删**、机械复验通过（numstat 7 增 / 4 删、恰 3 处 hunk、非目标行逐行字节全等、4 条目标行「旧文本为新行前缀 + 新行以 ` \|` 收尾」全成立） |
| 5 evidence 入库 | ✅ **7 份**入 `docs/evidence/`（决策卡 / 施工卡 / 施工报告 / 复核报告 / 红队报告 / 两份会审报告）；页首来源标识 + 定盘脱敏后入库，**仓外源件零改动** |
| 6 提交 2（docs） | ✅ **`505f6d4`**（8 文件，`+1570 −4`，含 7 新）；`check-docs` 提交后 **6/6**（`git ls-files` 977 → 986） |
| 7 push + HEAD 门禁 | ✅ push `3edaeb9..505f6d4`（无 force / 无 non-fast-forward）；HEAD = `origin/main`（ahead 0 / behind 0）；工作树 0 条；HEAD 态 `check-docs` **6/6** + `yarn typecheck` exit 0 |
| 8 CI 守夜 | ✅ **全绿**：run **`38157503438`**（run_number `99`，head_sha = 本批 HEAD）= **completed / success**，**五 job 全 completed 且全 success**（E2E job 晚出现，第 3 轮一并拿全） |
| 9 提交报告 | ✅ 本期（追加至 `<COMMIT-GOV>/提交报告.md`） |

## 一度基线口径更新（批 3③a 后的启用值）【实测 / 批内留证】

| 口径 | 值 | 来源 |
|---|---|---|
| 前端单测 | **110 passed / 110（11 spec 文件）** | 本棒亲跑 `yarn test`（工作树 == 入库字节，见 §二）；施工棒 `FINAL-full-test.log` 同数字 |
| 前端类型检查 | `vue-tsc --noEmit` exit 0 | 本棒亲跑 |
| 前端生产构建 | `vite build` ✓ built（1748 modules）exit 0 | 本棒亲跑（施工棒 `S4-build.log` 为 26.68s，机器负载差异，同为绿） |
| E2E 桩模式 | **PASS=28 FAIL=0**（GFSKIP=0；Q8 2/2） | 施工棒实跑留证 `批3a-evidence/e2e-stub-4.log`（`e2e_exit=0`）——**本棒未复跑**（见 §八-3） |
| CDP 冒烟 | **15/15 PASS**（A2 五项 + A6 三项 + R-84 五项 + 前置两项） | 施工棒实跑留证 `批3a-evidence/cdp-smoke-final.log` + `cdp/` 4 组时间戳截图——**本棒未复跑** |

## 一、阶段 1 · 前置机械验收（HEAD=`3edaeb9` 工作树态，10 文件在制品）【实测】

**1.1 `git status --porcelain` 逐一归属（`evidence-p15/phase1-status.txt`）**

` M`（8，跟踪已修改）＋ `??`（2，未跟踪新增）＝ **恰 10 条**，与卡面清单**逐条同名同序**，无外来夹带、无遗漏；`-uall` 口径亦恰 10 条：

| # | 状态 | 文件 | 归属 |
|---|---|---|---|
| 1 | ` M` | `frontend/src/components/AiPanel/AiPanel.vue` | A13（`toolTagType` 去 `default` + `pending` 显式） |
| 2 | ` M` | `frontend/src/stores/ai.ts` | A8 / A10 / D5②③ / D6(A) |
| 3 | ` M` | `frontend/src/types/tools.ts` | A1（`FrontendToolOutcome` 三态） |
| 4 | ` M` | `frontend/src/utils/frontend-tools.ts` | A2~A7（21 处生产点工厂收口） |
| 5 | ` M` | `frontend/src/utils/parts.ts` | A9 / X1 |
| 6 | ` M` | `frontend/src/views/ExportWizardView.vue` | A11 / A12（W-06 / W-07）+ D2 点名 |
| 7 | ` M` | `frontend/src/views/ImportWizardView.vue` | D9（W-07 双生面） |
| 8 | ` M` | `frontend/tests/frontend-tool-result-terminal.spec.ts` | T1 / T2 / T3（16 → 20 例） |
| 9 | `??` | `frontend/tests/export-wizard-step-outcome.spec.ts` | 新增（T7，4 例） |
| 10 | `??` | `frontend/tests/frontend-tool-outcome.spec.ts` | 新增（T4/T5/T6/T8，9 例） |

**1.2 锚定对照（提交前复验，防夹带）**【实测】（取证 `evidence-p15/anchor-verify.txt`、`anchor-regen.json`）

按锚定生成脚本 `_anchor-diff-b3a.mjs`（固定段序 = 段内路径字典序；改 8 段取 `git diff`，新 2 段取 `git diff --no-index -- /dev/null <file>`）重生成：

```
锚定物  <SCRATCH>/chat-timeline-pkg/diff-b3a.sorted.patch   6d2eeb1cee62962bd8642098798eef94b28641b6be943502b06b3b15d30255e7
重生成  evidence-p15/diff-b3a.sorted.patch                  6d2eeb1cee62962bd8642098798eef94b28641b6be943502b06b3b15d30255e7
cmp → IDENTICAL_BYTES    段数 10    字节 62211    统计 +771 / −102
EOL 归一化（CRLF→LF）后两侧 SHA-256 亦同为 6d2eeb1c…55e7 → EOL_NORMALIZED_IDENTICAL
```

**行尾实况（如实申报）**：工作树为**混合行尾** —— `frontend/src/views/ExportWizardView.vue`（1431 行，CR 1431）与 `frontend/tests/export-wizard-step-outcome.spec.ts`（178 行，CR 178）为 **CRLF**，其余 8 文件为 **LF**；仓库 `core.autocrlf=true`（无 `.gitattributes`），故 `git diff` 输出侧已归一为 LF（**锚定物本身 CR 计数 = 0 / LF 1271**）。⇒ 卡面「比对须 EOL 归一化」的要求**已满足（且强于之：原样字节即相等）**；工作树 CRLF 两文件按仓库既有 EOL 策略归一入库，此属**既有策略产物、非本批引入**。

⇒ **送入提交的字节集合 = 经施工棒实读 / 复核 / 红队锚定的字节集合，零夹带。**

**1.3 前端门禁三连 + 文档门禁（工作树态实跑）**

| # | 命令 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | `cd frontend && yarn test` | 110 / 11 spec | Test Files **11 passed (11)**，Tests **110 passed (110)**，exit 0（`ready`4 / `sse-watchdog`4 / `parts-double-write`5 / `parts-reducer`15 / **`frontend-tool-result-terminal`20** / `tools-and-deadline`21 / `restore-reconcile`3 / `parts-residue-cleanup`21 / `ai-panel-parts-render`4 / **`frontend-tool-outcome`9** / **`export-wizard-step-outcome`4**） | ✅ |
| 2 | `cd frontend && yarn typecheck` | exit 0 | `vue-tsc --noEmit` Done in 4.52s，exit 0 | ✅ |
| 3 | `cd frontend && yarn build` | exit 0 | `✓ 1748 modules transformed` / `✓ built in 17.70s`（spreadjs chunk 6,198.64 kB，与既有批次同形），exit 0 | ✅ |
| 4 | `node scripts/ci/check-docs.mjs`（仓库根） | 6/6 | 6/6 断言绿（`git ls-files` **977** / 实扫文本 974 / 豁免 3），exit 0 | ✅ |

**后端**：本批**零后端改动**（锚定 10 段全为 `frontend/**`）⇒ 按卡不重跑 Maven；后端 job 的 CI 结果见 §七（**success**）。

## 二、阶段 2 · 仓外文书订正（施工报告，入库前）【实测】

`<SCRATCH>/chat-timeline-pkg/施工报告-批3③a.md`（仓外件）：**仅改 2 处，其余零改动**（取证 `evidence-p15/report-edit-verify.txt`）

| # | 位置 | 改前 | 改后 | 核验 |
|---|---|---|---|---|
| 1 | §11 交付物清单表体（`:156`） | `` `git diff --stat`：8 文件 433+/100-，含测试扩展 `` | `` `git diff --stat`：8 跟踪文件 +441/-102（含 2 新测试文件合计 +771/-102）；两向导仅按 D3(iii)/D9 范围动 `` | 残留 `433+` **0**、新值命中 **1** |
| 2 | §5 CDP 冒烟配方（`:109`） | `CDP 9334` | `CDP 9344` | 残留 `CDP 9334` **0**、新值命中 **1** |

- 行数 **186 → 186**（未增删行）；CR 计数 **0**（纯 LF，与源件一致）；订正后 `git diff --stat` 实测 **8 跟踪文件 +441/−102**（+ 2 新测试文件 178+152 = **合计 +771/−102**）与改后文字逐字相符。

## 三、阶段 3 · 提交 1（feat，原子红线 R-a 单笔）【实测】

**暂存前逐项复核**：`git status` 10 条 ⇒ **逐文件显式 `git add`**（未用 `-A` / `.` / `-N`）；暂存清单实读 **恰 10 条**（8 `M` + 2 `A`），`git diff`（未暂存残留）**空**；逐文件 `git ls-files -s` 的 index blob == `git hash-object <file>` **10/10 相等**（⇒ 暂存字节 == 工作树归一后字节）。

| 项 | 值 |
|---|---|
| commit | **`f0210f2dc5d75a219d1deee3d4094539f968b971`** |
| 主题 | `feat(frontend): 对话时序包批 3③a 三态 outcome 契约 + unknown 终态链闭合 + W-06/W-07 修复` |
| 统计 | **10 files changed, 771 insertions(+), 102 deletions(-)**（含 2 新测试文件 178 / 152 行） |
| pre-commit 钩子 | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` ⇒ **未使用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1` 放行分支**（近六批未触发口径维持） |

提交信息要点（`-F` 落件 `evidence-p15/msg-p15-1.txt`）：基线 `3edaeb9`；R-a 九项不可拆面逐项点名，并**逐条说明缺一即回归**的原因（A4/A5 缺 ⇒ `{ok:false}` 被 `defaultExecutor` 折成成功；A9 缺 ⇒ 结局未知被回执帧翻成已成功；A10/X1/`:1705`/`:246` 缺 ⇒ 挂起卡复活 / 终态被改写）；A1~A14 + X1 逐条改动要点；A14 归属订正（`unknown`/`cancelled` 为批 1 `1efab09` 落地，6→7→9 态，「批 2 完成 7 态收敛」叙述作废）；D11（不加机器可读前缀）；D2 点名项；测试 T1~T9 双向留证；验收口径（110/110·11 spec / typecheck 0 / build 绿 / E2E PASS=28 / CDP 15/15）；锚定 SHA；复核与红队结论。

**提交后零夹带终验**【实测】（取证 `evidence-p15/committed-equals-anchor.txt`、`anchor-final.txt`）：

| 核验面 | 结果 |
|---|---|
| 从已入库区间 `3edaeb9..HEAD` 按同一 10 段固定段序重生成补丁 | 与锚定物 **原样字节相等 = true**；SHA-256 = `6d2eeb1cee62962bd8642098798eef94b28641b6be943502b06b3b15d30255e7`（= 锚定值） |
| EOL 归一化 / 去 `index` 行后二次比对 | 两者亦相等（`true` / `true`），SHA-256 分别为 `6d2eeb1c…55e7` 与 `581c7bc0…090e`（两侧同值） |
| 逐文件 blob 哈希（锚定 `index` 行 b 侧 vs `git rev-parse HEAD:<file>`） | **10/10 EQUAL** —— `eed5f14` / `101718b` / `45cdb94` / `1c16199` / `c904ac6` / `c9c6dc8` / `ce6f13d` / `bcca360` / `730474d` / `b54321f` |
| 段数 / 增删 | `segments=10 additions=771 deletions=102 bytes=62211`（与锚定物一致） |

⇒ **入库字节集合 == 锚定字节集合（逐文件 10/10 + 全量补丁字节相等）**。

## 四、阶段 4 · `docs/M2-排期计划.md` 落行与销记【实测】

**纪律执行**：只追加 + 内容零删 + 行尾注记；整行重写为载体且**旧文本逐字节保留**；**编辑前逐条重新实读**；锚点落**表体内**（`:329` R-140 行之后、表体闭合空行之前，实测空行在 `:333`）。

| 项 | 落点 | 内容摘要 |
|---|---|---|
| (a) 新增 **R-141** | **`:330`** | ③a 移交 ③b 包四项：① re-attach 新宿主消息（`stores/ai.ts:1008`，`lastSeq` 刷新后全量回放 ⇒ 新宿主建 `succeeded` 新卡、旧 `unknown` 卡并存）② `loadHistory` 整替（`stores/ai.ts:631-663`，硬编码点 `:644` 工具段状态恒 `succeeded`）③ 通用 `FRONTEND` 工具重投帧不经守卫（`ai.ts:1573`/`:1620` GF 前缀、`:1590` 直跑）**＋行尾注记：`failed` + `requiresAction=true` 经 suspended 支仍属残余可达面（DS Pro O-5）** ④ `frontendResultStatus`（`parts.ts:210-228`）与 `frameStatusToPendingStatus`（`ai.ts:333-357`）两翻译表穷举口径分叉防漂移；并记「`unknown` 经 ①② 两路仍可被翻 `succeeded` = ③a 已知代价，③b 卡须显式承接方可销记」 |
| (b) 新增 **R-142** | **`:331`** | `stop()` 之后在途 `executeFrontendTool` 仍写段 + 回灌残留（`ai.ts:1798-1818`；`send` `finally` `:923-927` 只置 `done` 不清段） |
| (c) 新增 **R-143** | **`:332`** | ③a 审查遗留 minor 包四项：① `syncStepToBackend` 失败 error + warning 双弹待统一（`ExportWizardView.vue:624` + `:579`、`ImportWizardView.vue:773` + `:731`，DS Pro m-2）② `batch3a-outcome-smoke.mjs:147-150` 裸路径探针对 HMR 换版脆弱（GLM O1）③ `assertUnknownTerminalInvariant` 缺 `import.meta.env.DEV` 门（`parts.ts:134-139`，口径订正，DS Pro O-1）④ T7③/④ RED 留证红在夹具未就位而非断言本体（GREEN 终态断言为真锁，DS Pro O-4） |
| (d) 行尾注记 **R-97** | **`:289`** | 原文「两个调用方都不取结局」**订正为 5 处**（`ExportWizardView.vue:14` / `:599` / `:1128` / `:1146` / `:1225`，GLM 会审修正；原「两处」系把 `goStep` 体内 `void` 调用误当外部调用方）；并记 W-06/W-07 已由本批闭环、W-05（后端 `AiTools` 8 处 `catch`）与 N-6（`ConfirmGate` 硬编码 `ok=true`）仍属 R-137 / 后端批，**本行台账状态维持「立项」不动** |
| (e) 行尾注记 **R-84** | **`:276`** | DS Pro **m-1** 同族残留面：`ExportWizardView.vue:1182-1184` / `:1187-1189` 的**只读 / 越界拒绝**字符串经「字符串⇒`ok`」兼容支仍**折成 `ok`**（与 `export.selectDefs` / `setConditions` / `start` / `import.*` 只读守卫同族）；本批未动、随 R-84 批一并结构化 |
| (f) 逐项销记 **R-139** | **`:328`** | C8 逐项点名（不打包）：`m2` 已承接·移交 ③b；`m3` **已施工**（申报粒度面 = 施工报告 §1 锚点表逐条点名）；`O1` 已承接·移交 ③b；`O2`/`O3`/`O4` **维持登记**；`O5` 已承接（指向 R-140 行尾注记） |
| (g) 逐项销记 **R-140** | **`:329`** | ① 观察 12 条逐条终态：1 / 4 **已承接·移交 ③b**；2 / 6 / 7 / 8 / 9 / 10 **维持登记**；3 **维持（行为改善，无动作）**；5 **未销记**（本批 CDP 为 headless 自动断言 15/15，人工视觉面留待 ③b 或专项 `chat-panel-smoke.mjs` 目检）；11 **已承接**（本批零后端改动、未重建 jar）；12 **已承接·部分销记**（build 绿 + CDP 走 dev 形态，`dist` 形态证据留待 `yarn preview` 复跑）② `o1`~`o4`：`o3` CDP 配方 **已销记**（本批照方实证 `VITE_DEV_PORT=5202` + `VITE_API_BASE` 复跑成功、CDP 15/15）；`o1` / `o2` / `o4` **维持登记**（`o4` 同族新增残留 → R-142）③ §5-5 口径角 **已定案**：D5(甲)「本地胜」维持且 `localTerminal` 已纳入 `'unknown'`，帧胜候选**未采纳** |

**决策卡 §6「M2 收尾待办」四条办理核验**：1 R-141/R-142 落行 ✅（另按提交指令追加 R-143）；2 R-97 行尾注记订正 ✅；3 逐项销记 C8 不打包 ✅（R-139 ①~④ + R-140 ① 12 条 / ② `o1`~`o4` / ③ §5-5）；4 本批新增残留登记 ✅（R-142 + `unknown` 经 re-attach/loadHistory 的已知代价双向留痕）。

**机械复验（防「零删」被违反）**【实测】（取证 `evidence-p15/m2-verify.txt`）：

- `git diff --numstat` = **7 增 / 4 删**；`git diff -U0` 恰 **3 个 hunk** = `@@ -276 +276 @@` / `@@ -289 +289 @@` / `@@ -328,2 +328,5 @@`（无第四处改动）
- 非目标行**逐行字节全等**：`1..275` / `277..288` / `290..327` 与 HEAD 全等；`330..495`(HEAD) 与 `333..498`(现态) 逐行全等 ⇒ **差异 0 处**
- 4 条目标行（`:276` / `:289` / `:328` / `:329`）：旧文本（去尾 ` |`）为新行**前缀** = true、新行以 ` |` 收尾 = true（追加字数 458 / 846 / 546 / 1039）
- 行数 **495 → 498**；文件仍为 **LF**（CR 0，与既有行尾一致，无行尾转换）

## 五、阶段 5 · evidence 入库（7 份）【实测】

命名沿用该族既有风格（`补档-<包代号>-<批号>-<报告类型>-<执行者模型>.md`）：

| # | 源（仓外，占位符形式） | 落点 `docs/evidence/` | 源行 | 入库行 | 定点改写 | 页首标识 |
|---|---|---|---|---|---|---|
| 1 | `<SCRATCH>/chat-timeline-pkg/决策卡-批3③a.md` | `补档-CHATTIMELINE-批3③a-决策卡-K3.md` | 286 | **288** | **2** | +2 行 |
| 2 | `<SCRATCH>/chat-timeline-pkg/批3③a-施工卡.md` | `补档-CHATTIMELINE-批3③a-施工卡.md` | 382 | **384** | **8** | +2 行 |
| 3 | `<SCRATCH>/chat-timeline-pkg/施工报告-批3③a.md` | `补档-CHATTIMELINE-批3③a-施工报告-K2.8.md` | 187 | **189** | **5** | +2 行（另注记入库前订正 2 处） |
| 4 | `<SCRATCH>/chat-timeline-pkg/复核报告-批3③a.md` | `补档-CHATTIMELINE-批3③a-复核报告-GLM-5.3.md` | 213 | **215** | **1** | +2 行 |
| 5 | `<SCRATCH>/chat-timeline-pkg/红队报告-批3③a.md` | `补档-CHATTIMELINE-批3③a-红队报告-DS-V4-Pro.md` | 226 | **228** | **3** | +2 行 |
| 6 | `<SCRATCH>/chat-timeline-pkg/会审-批3③a-GLM.md` | `补档-CHATTIMELINE-批3③a-会审报告-GLM-5.3.md` | 136 | **138** | **0** | +2 行 |
| 7 | `<SCRATCH>/chat-timeline-pkg/会审-批3③a-DSPro.md` | `补档-CHATTIMELINE-批3③a-会审报告-DS-V4-Pro.md` | 126 | **128** | **2** | +2 行 |

- **会审两份的必要性核验（卡面要求先查）**：`6093f06`（批 2 入库笔）实际入库的是 `补档-CHATTIMELINE-批2-复核报告-GLM-5.3.md` 与 `补档-CHATTIMELINE-批2-红队报告-DS-V4-Pro.md`（对话时序包**批 2** 的两份外部审查报告）；**`会审-批3③a-*.md` 两份在入库前不存在于 `docs/evidence/`**（`ls | grep` 实测 0 命中）⇒ 按卡「不在则一并入库」办理。
- 页首标识行（七件同构，+1 行 + 1 空行 ⇒ 行数各 +2）：`> 来源=仓库外过程报告补档（<日期>，对话时序包批 3③a <件类型>，署名信息以文内为准；本件为 DC-19 后产出的仓外过程/审查件，脱敏后入库，源文件未改）`
- **改写面**（仅盘符绝对路径类，四条映射）：主仓绝对路径 → `<MAIN_REPO>`；过程目录 / CDP 脚本路径 → `<SCRATCH>/chat-timeline-pkg/…`；临时批目录下的 Maven 包装脚本路径 → `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`；另一条线仓路径 → `<OTHER_REPO>（另一条线的仓，严禁触碰）`。逐条字面映射（含逐文件改写计数与改写面之外零增删的逐行对照）存于取证 `evidence-p15/make-evidence-p15.txt`，**本报告不复制其字面**。改写脚本 `_ingest-evidence-p15.mjs` **只读源件**（`readFileSync` 单次读 + 写入 `docs/evidence/` 新路径）。
- **仓外源件零改动**：源件 sha256 与入库件 sha256 已登记（取证 `evidence-p15/evidence-hashes.txt`），供后续复核。
- **门禁**：7 份 `git add` 后进入 `git ls-files` 扫描面 ⇒ `check-docs` 断言 ⑤ 实测覆盖（`ls-files` 979 → **986**），**6/6 全绿**。
- `docs/README.md` **未改**：`:44` 行「`docs/evidence/补档-*.md` 共 24 个文件 = 23 份补档 + 本索引」自 VITESTA / CHATTIMELINE 两次入库起即陈旧（**现态实测 38 份补档**）；本批按「批 1 入库未登记该索引行 ⇒ 同口径不新增」办理，漂移随下次 docs 小批统一订正（承接第 14 期 §七-2 的申报口径）。

## 六、阶段 6 · 提交 2（docs）与 push【实测】

| 项 | 值 |
|---|---|
| commit | **`505f6d4e0fa1360f04a3dbe3313842f1513a0952`** |
| 主题 | `docs(m2,evidence): 批3③a 决策卡/施工卡/三报告入库 + R-141/R-142/R-143 落行 + R-97 注记 + R-139/R-140 销记` |
| 文件 | 8（`docs/M2-排期计划.md` + 7 份 `docs/evidence/补档-CHATTIMELINE-批3③a-*`），**+1570 −4** |
| pre-commit 钩子 | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` ⇒ **未用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1`** |
| 提交后 | `git status --porcelain` = **0 条**（工作树干净） |

| push 项 | 值 |
|---|---|
| 提交前复核 | HEAD 两笔（`f0210f2` / `505f6d4`）✅；工作树 **0 条** ✅；锚定核验已过（§一 / §三）✅；`ahead/behind = 0 2`（相对 `origin/main`） |
| `git push origin main` | **`3edaeb9..505f6d4  main -> main`**，`PUSH_EXIT=0`；**无 force、无 non-fast-forward** |
| push 后 | HEAD = `505f6d4e0fa1360f04a3dbe3313842f1513a0952` = `origin/main`；`rev-list --left-right --count origin/main...HEAD` = **`0 0`**；`git status --porcelain` = 0 条 |
| HEAD 态门禁复跑 | `node scripts/ci/check-docs.mjs` = **6/6**（`git ls-files` **986** / 实扫文本 983 / 豁免 3）；`cd frontend && yarn typecheck` = `vue-tsc --noEmit` Done in 3.34s，exit 0（`evidence-p15/push.txt`、`check-docs-pre.txt`） |

## 七、CI 守夜（匿名轮询 · `--ssl-no-revoke` · 间隔 180s · 预算 60 次/h · 实耗 4 次调用）【实测】

| 项 | 值 |
|---|---|
| run id | **`38157503438`**（run_number `99`） |
| head_sha | `505f6d4e0fa1360f04a3dbe3313842f1513a0952`（= 本批 HEAD） |
| 触发 | `push`（`branch=main`）；created `2026-10-11T16:55:55Z` → updated `2026-10-11T16:59:38Z`（约 **3 分 43 秒**） |
| run 级 | **status = completed，conclusion = success** |

| job | id | conclusion | 时段（UTC） |
|---|---|---|---|
| 全历史脱敏扫描（报告制 · 不计门禁） | `114523404603` | **success** | 16:55:59 → 16:57:29 |
| 前端类型检查与构建 | `114523404650` | **success** | 16:55:58 → 16:56:59 |
| 文档一致性门禁（check-docs）｜阻断 | `114523404675` | **success** | 16:55:59 → 16:56:04 |
| 后端编译与单测 | `114523404793` | **success** | 16:55:59 → 16:57:35 |
| E2E 全流程（29 用例 · 桩上游 + redis） | `114523732300` | **success**（**晚出现** job，`needs: backend`） | 16:57:39 → 16:59:38 |

- **五 job 全 completed 且全 success，run 级 completed/success ⇒ 闭环。**停止条件按卡执行（run completed 且 job 数 ≥5 全 completed）；E2E job 属晚出现，**第 1、2 轮仅见 run 未 completed 时未误判**，第 3 轮见 5 job 全 completed 一并取全。
- 轮询节律：第 1 轮 16:56:29（in_progress）→ 第 2 轮 16:59:30（in_progress）→ 第 3 轮 17:02:32（completed/success，同轮取 jobs）⇒ 共 **4 次 API 调用**。
- 对照：上一轮 run `38145905293`（run_number 98，批 2 热修 HEAD `3edaeb9`）亦为全绿；再上一轮 `38144704456`（97，`6093f06`）= 后端 failure / E2E skipped（第 14 期已留痕并热修闭环）。
- **取数受限（如实）**：`GET …/actions/jobs/<job>/logs` 匿名 403（`Must have admin rights`），故 **CI 侧用例数不主张**（`110` / `442` 均为**本机同一字节**实测值）；CI 侧只主张「job 绿」这一结论。派生摘要 `ci-run-summary.json` 为**字段白名单**（run id / run_number / head_sha / event / status / conclusion / 时间戳 / workflow name / 5 job 的 id·name·status·conclusion·时间戳），实测 **0 组织名字面、0 URL、0 盘符**。

## 八、脱敏终扫（0 命中）【实测】

扫描器 = `evidence-p15/desense-scan.mjs`（规则 = 盘符路径 / POSIX 挂载形 / 用户名 / 主机名 / 内网 IP / **环回地址（仅统计）** / 密钥形态 / **命名纪律三态禁则**（NFKC + 隐形字符双态 + 去分隔符连写），占位符整段先剔除，与 `check-docs` 断言 ①/⑤ 同口径）。

| 扫面 | 结果 |
|---|---|
| **10 份施工文件全量**（`desense-files.txt`） | 硬命中 **0**；环回地址 **0**（10 文件内 `127.0.0.1` 零出现） |
| **全部新增行**（771 行，`git diff -U0` 的 `+` 行 + 2 新文件 `--no-index` 行；`desense-added.txt`、`_added-all.txt`） | 硬命中 **0**；环回地址 **0** |
| **M2 新增 7 行**（`desense-staged-added.txt`） | 硬命中 **0** |
| **7 份入库件全量**（`desense-evidence.txt`） | 硬命中 **0**；环回地址 **0** |
| **仓外 7 份源件预扫**（`desense-evidence-src-scan.txt`） | 硬命中 **28**（盘符 21 + 命名纪律禁则 7）⇒ **即为本次改写面**，改写后归 0 |
| **独立字面复核**（`desense-final.txt`、`desense-final-2.txt`，仅计数不打印匹配） | 组织名字面 / 组织名主干 / 仓库域名 / 仓库 URL 形 / `sk-` 形 / `apiKey`·`secret` 赋值形 / `Bearer` / `AKIA` / `127.0.0.1` / 盘符 —— **全 0**（M2 新增行与 7 入库件逐文件均 0） |
| **本报告段自身**（`_report-section-p15.md`、`desense-report-section.txt`） | 硬命中 **0**（首轮清扫出 4 处：2 处盘符 + 1 处组织名主干 + 1 处仓库域名，**均已改写为占位符口径**后复扫归 0）；仅 **环回地址 2 处（仅统计）**，为「扫面规则自述」行内的规则字面，按既有书面口径保留 |
| **取证目录自清**（DC 口径） | 匿名 API 原始响应 3 件（`runs-latest` / `jobs-latest` / `runs-1`）、轮询脚本与 push 日志内的 **owner 段**已替换为 `<REPO-OWNER>`，留 `_masking-note.txt` 逐项说明；`grep -rl` 复核**全目录 0 命中** |

- 本批**未涉及** `.env` / 密钥文件、未改动历史、未触碰另一条线克隆目录；**未停任何本机服务**；7 份仓外源件**零改动**。

## 九、未发生项与异常申报（如实留痕）

**未发生项**：无 force push｜无 `--no-verify`｜无 `AI_SECRETS_ALLOW=1` 放行｜无 `git add -N`/`-A`/`.`｜无 `stash`/`clean`/`checkout`｜未读任何 `.env`｜未触碰另一条线克隆目录（`<OTHER_REPO>`，红线）｜未改 10 个施工文件以外的任何源码（红线遵守）｜未改 `docs/README.md`｜未改 `ci.yml`｜机械验收 3/3 全 PASS（**无 FAIL ⇒ 全程未触发停手条件**）。

1. **【最重要】执行者署名 vs 运行时可自证模型不一致**：卡面署名 = **DeepSeek V4 Flash**，而本棒运行时可自证标识 = **Kimi Code 子智能体（Kimi 系列）**。本棒**不声称**自己具备 DeepSeek V4 Flash 身份 —— 署名按卡写定（DC-19 钉版口径）、差异在此留痕；口径是否调整由指挥官裁决（承接第 14 期 §七-1 的同型申报）。
2. **工作树 CRLF 两文件与锚定物的行尾关系（已充分核验，非缺陷）**：`ExportWizardView.vue` / `export-wizard-step-outcome.spec.ts` 工作树为 CRLF，仓库 `core.autocrlf=true`（无 `.gitattributes`）⇒ `git diff` 侧归一为 LF，锚定物 CR 计数 = 0。**入库字节按仓库既有 EOL 策略归一**，与「锚定物」为同一视图 ⇒ 逐文件 blob 10/10 相等、全量补丁原样字节相等。**该策略为仓库既有配置，非本批引入，本批未改任何 EOL 配置**。
3. **E2E / CDP 由施工棒实跑，本棒未复跑（如实）**：卡面阶段口径只要求本棒跑「工作树态前端三连」，E2E 桩与 CDP 冒烟（批内口径 28 / 15）**取自施工棒留证**（`批3a-evidence/e2e-stub-4.log`、`cdp-smoke-final.log` + 4 组截图），本棒**只读核对日志末行数字**（`PASS=28 FAIL=0` / `PASS=15 FAIL=0`）与 `FINAL-full-test.log` 的 11/110，**未重跑**。CI 侧 E2E job 亦为 success（§七）。
4. **会审两份入库属卡面「先查后办」的补办**：`6093f06` 的提交信息作「两份外部审查报告入库」系指**批 2** 的复核/红队两件；批 3③a 的**会审**两件此前从未入库，本批按卡面授权一并入库（§五-必要性核验）。
5. **M2 追加了 R-143 一项**：卡面 §「M2 落行」明确要求落 R-141 / R-142 / R-143 三条（R-143 为 ③a 审查遗留 minor 包四项），已按卡办理；决策卡 §6 原文只列 R-141/R-142，**R-143 属提交指令的范围扩展**，此处如实标注来源差异。
6. **R-140 ① 第 5 条（复制按钮位置）与 R-143 ② 未在本批销记**：前者须**人工视觉面**、后者须改**仓外 CDP 脚本**，均超本批授权范围 ⇒ 维持登记并写明承接条件（不静默留白）。
7. **`docs/README.md` 计数漂移（未改，承接申报）**：`:44`「补档-*.md 共 24 个文件」vs 现态实测 **38 份**；本批按批 1 同口径不动，建议另立 docs 小批统一订正。
8. **遗留（非本期动作）**：R-141①②③④ / R-142 / R-143 交后续批（③b 与处置清单）；R-137（后端 AI 工具终态守卫批）、R-94 死码族、R-84 m-1 只读越界拒绝字符串结构化仍待办，**本期零触碰**。

## 十、本批产出物

| 内容 | 路径 / 值 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 15 期**条目，紧接第 14 期补记之后） |
| 入库件（仓库内，2 笔提交 / 18 文件） | `f0210f2`（前端 **10**，+771 −102）+ `505f6d4`（docs **8**：`docs/M2-排期计划.md` + 7 份 `docs/evidence/补档-CHATTIMELINE-批3③a-*`，+1570 −4） |
| 基线链 | `3edaeb9` → **`f0210f2`** → **`505f6d4`**（= `origin/main`） |
| 锚定取证 | `<SCRATCH>/chat-timeline-pkg/evidence-p15/anchor-verify.txt`、`anchor-regen.json`、`diff-b3a.sorted.patch`（重生成件）、`committed-diff-b3a.sorted.patch`、`committed-equals-anchor.txt`、`anchor-final.txt` |
| 机械验收 / 门禁输出 | 同目录 `phase1-status.txt`、`fe-test-pre.txt`、`fe-gates-p15.txt`、`check-docs-pre.txt`、`m2-verify.txt`、`report-edit-verify.txt` |
| 提交 / push 取证 | 同目录 `msg-p15-1.txt`、`msg-p15-2.txt`、`commit-1.txt`、`commit-2.txt`、`commits-p15.txt`、`push.txt`（owner 段已屏蔽，另存 `push-masked.txt`） |
| CI 取证 | 同目录 `ci-poll-p15.sh`、`ci-poll-p15.log`、`ci-run-summary.json`（字段白名单派生，0 组织名/URL/盘符）、`runs-latest-masked.json`、`jobs-latest-masked.json`、`_masking-note.txt` |
| 脱敏取证 | 同目录 `desense-scan.mjs`、`desense-files.txt`、`desense-added.txt`、`desense-staged-added.txt`、`desense-evidence.txt`、`desense-evidence-src-scan.txt`、`desense-final.txt`、`desense-final-2.txt`、`_added-all.txt`、`_added-m2.txt` |
| evidence 入库取证 | 同目录 `make-evidence-p15.txt`（含 `_ingest-evidence-p15.mjs` 的改写面与逐行对照）、`evidence-hashes.txt`（源件 / 入库件 sha256） |
| 施工棒批内留证（仓外，本棒只读核对） | `<SCRATCH>/chat-timeline-pkg/批3a-evidence/`（T1~T9 RED/GREEN 日志、三连日志、`e2e-stub-4.log`、`cdp-smoke-final.log`、`cdp/` 4 组截图） |

---

**签字**：提交官 **DeepSeek V4 Flash**（第 15 期；**运行时可自证标识 = Kimi Code 子智能体（Kimi 系列）**，差异见 §九-1），2026-10-12。**授权来源 = K3 指挥官批 3 ③a 提交指令（2026-10-12）**。本报告所列证据均可于 `<SCRATCH>/chat-timeline-pkg/evidence-p15/` 复核；**全文无 GitHub 组织名字面**，仓库一律以 `<REPO>` / `<REPO-URL>`、CI 一律以 run id / job id 指称。

---

# ai-example 合流项目 · 提交官报告（第 16 期：对话时序包批 3'③b「一卡全生命周期 · 段状态机收口 + 死码清除」全量入库 —— 10 文件单笔原子提交 + 决策卡/施工卡/三报告/两会审报告入库 + M2 落行 R-144 与逐项销记/注记 + 文书层 7 条件 + 3 minor 全办理（含**注释级收口修与重锚**）；**CI 五 job 全绿 ⇒ 闭环**）

- 本期执行者：**DeepSeek V4 Flash**（Kimi Code 子智能体 = 提交官；首页署名 + 文内自证，DC-19）——流程 = 阶段 1 前置机械验收（含锚定零夹带双向复算 + 脱敏终扫）→ 阶段 2 仓外施工报告文书修订（7 条件 + 3m）+ **注释级收口修与重锚** → 阶段 3 提交 1（10 文件原子红线 R-a）→ 阶段 4 `docs/M2-排期计划.md` 落行 + evidence 入库 → 阶段 5 提交 2（docs）→ push → 阶段 6 CI 守夜（匿名）→ 阶段 7 本报告
- **【自证 · 如实】** 本棒运行时可自证的实际模型标识 = **Kimi Code 子智能体（Kimi 系列）**；本棒**无法自证为 DeepSeek V4 Flash**。署名按卡写定（提交官钉版口径 DC-19），与实际标识的差异在此留痕申报（见 §九-1）。
- 授权来源：**Kimi K3 指挥官批 3' ③b 提交指令（2026-10-12）**；上游依据 = 《决策卡-批3③b.md》（K3 代签，2026-10-12）+《批3③b-施工卡.md》（定稿）；口径 **DC-20：机械验收 + 脱敏终扫不豁免**
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**本段全文无 GitHub 组织名字面**，CI 一律以 **run id / job id** 取证
- 起点 HEAD = **`505f6d4`**（= `origin/main`，批 3③a 提交批）｜本批两笔提交：**`fc6e5a2`**（feat）+ **`296ffe2`**（docs）｜push 后 HEAD = `origin/main` = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（ahead 0 / behind 0）
- 取证件目录（仓外）：`<SCRATCH>/chat-timeline-pkg/evidence-p16/`
- 上游案卷（仓外）：`<SCRATCH>/chat-timeline-pkg/`（决策卡-批3③b / 批3③b-施工卡 / 施工报告-批3③b / 复核报告-批3③b / 红队报告-批3③b / 会审-批3③b-GLM / 会审-批3③b-DSPro / `批3b-evidence/`）
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜`core.autocrlf=true`｜yarn `1.22.22`｜node `v24.21.0`
- 标注：【实测】= 本机命令实跑复现；【推断】= 由实测/文档证据逻辑推出
- **结论：机械验收 3/3 PASS、锚定双向核验 PASS（10/10 逐文件字节相等）+ 注释级收口修后重锚 PASS、脱敏终扫 0 硬命中、两笔提交入库并推送、CI run 级 success + 五 job 全 success ⇒ 闭环**

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 1 机械验收 | ✅ `git status --porcelain` 恰 **10 条**（5 源码 + 5 测试），与卡面清单**逐条同名**、无卡外夹带（`-uall` 同值 10）；锚定复算 SHA-256 = `8b7149be…a1eda0c1`（10 段 / 58420 B / +529 −79，零新文件，raw == sorted），`cmp` **IDENTICAL_BYTES**；保真验证器（archive HEAD → 临时目录 apply → 逐文件比对）**10/10** |
| 2 施工报告文书修订 + 注释级收口修 | ✅ 仓外 `施工报告-批3③b.md` 25606 B / 217 行 → **33163 B / 225 行**（CR 0，纯 LF），7 条改正条件 + 3 项 minor **全部落位**（§六-表）；**唯一授权的代码级例外** = D9 注记自引用两行改相对锚（`parts.ts` / `ai.ts` 各一行，零行数变化、零语义变化）⇒ **重锚** SHA-256 = `8718040d…7daa6d76`（58471 B / 10 段 / +529 −79） |
| 3 提交 1（原子红线 R-a） | ✅ **`fc6e5a2`** 单笔 **10 文件**（`+529 −79`）；逐文件显式 `git add`（未用 `-A` / `.` / `-N`）；暂存清单实读恰 10 条；**暂存 blob == 工作树 blob 10/10 EQUAL**；pre-commit 通过（**未用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1`**） |
| 3′ 提交后零夹带终验 | ✅ 从已入库区间 `505f6d4..fc6e5a2` 按同一 10 段固定段序重生成补丁 = **原样字节相等**、SHA-256 仍 `8718040d…7daa6d76`；**10/10 逐文件 blob 哈希**与锚定 `index` 行 b 侧相等 |
| 4 M2 落行 | ✅ 新增 **R-144（`:333`）**；行尾注记/销记 6 行：**R-84（`:276`）/ R-139（`:328`）/ R-140（`:329`）/ R-141（`:330`）/ R-142（`:331`）/ R-143（`:332`）**——**只追加、内容零删**、机械复验通过（numstat 7 增 / 6 删、恰 2 处 hunk、6 条目标行「旧文本（去尾 ` \|`）为新行前缀 + 新行以 ` \|` 收尾」全成立、R-144 为唯一新增行、7 行单元格数一致 = 2） |
| 5 evidence 入库 | ✅ **7 份**入 `docs/evidence/`（决策卡 / 施工卡 / 施工报告 / 复核报告 / 红队报告 / 两份会审报告）；会审卡两张照 ③a 先例**不入库**；页首来源标识 + 定盘脱敏，**仓外源件零改动**（逐件 sha256 对照见 §五） |
| 6 提交 2（docs）与 push | ✅ **`296ffe2`**（8 文件，`+2021 −6`，含 7 新建）；`check-docs` 提交前 **6/6**（`git ls-files` 986 → 993）、提交后 HEAD 态亦 **6/6**；push `505f6d4..296ffe2`（无 force / 无 non-fast-forward） |
| 7 CI 守夜 | ✅ **全绿**：run **`38166395544`**（run_number `100`，head_sha = 本批 HEAD）= **completed / success**，**五 job 全 completed 且全 success**（E2E job 晚出现，第 3 轮一并拿全）；匿名轮询实耗 **4 次 API 调用**（60/h 预算内） |
| 8 脱敏终扫 | ✅ 10 文件 + M2 加入行 + 7 入库件 + 两笔提交信息：**硬命中 0**（127.0.0.1 与 `import.meta.env.DEV` 代码标识符按既有口径仅统计 / 防误报） |
| 9 提交报告 | ✅ 本期（追加至 `<COMMIT-GOV>/提交报告.md`） |

## 一度基线口径更新（批 3'③b 后的启用值）【实测 / 批内留证】

| 口径 | 值 | 来源 |
|---|---|---|
| 前端单测 | **125 passed / 125（11 spec 文件）** | 本棒亲跑 `yarn test`（工作树 == 入库字节，见 §一-1.4） |
| 前端类型检查 | `vue-tsc --noEmit` exit 0（Done in 3.37s） | 本棒亲跑 |
| 前端生产构建 | `vite build` ✓ built in 25.12s（spreadjs chunk 6,198.64 kB，与既有批次同形）exit 0 | 本棒亲跑 |
| E2E 桩模式 | **PASS=28 FAIL=0**（GFSKIP=0，Q8①② PASS，`e2e_exit=0`） | 施工棒实跑留证 `批3b-evidence/E2E-stub-final.log` + 复核棒独立复现 —— **本棒未复跑**（见 §九-4） |
| CDP 冒烟 | **10/10 PASS**（①~⑨ + 前置；底线项「重投后仍只有一张卡」截图 + result JSON 断言双证） | 施工棒实跑留证 `批3b-evidence/cdp/result-20261011T182213Z.json` + 复核棒独立复现 —— **本棒未复跑** |
| 文档门禁 | `check-docs` **6/6**（`git ls-files` **993**） | 本棒亲跑（提交前 + HEAD 态各一次） |

## 一、阶段 1 · 前置机械验收（HEAD=`505f6d4` 工作树态，10 文件在制品）【实测】

**1.1 `git status --porcelain` 逐一归属（`evidence-p16/phase1-status.txt`）**

` M`（10，跟踪已修改）＝ **恰 10 条**，无 `??` 未跟踪新增、无外来夹带、无遗漏；`-uall` 口径亦恰 10 条：

| # | 状态 | 文件 | 归属 |
|---|---|---|---|
| 1 | ` M` | `frontend/src/components/AiPanel/AiPanel.vue` | A3/D2（死码四块删除 + 链收拢） |
| 2 | ` M` | `frontend/src/stores/ai.ts` | D6(B) / D8 / D9 / D10 + A2 / A8 |
| 3 | ` M` | `frontend/src/utils/parts.ts` | D5 建段 + A6/D4 + D9 注记 + D12 DEV 门 |
| 4 | ` M` | `frontend/src/views/ExportWizardView.vue` | D11（双弹统一）+ D15 |
| 5 | ` M` | `frontend/src/views/ImportWizardView.vue` | D11 + 导入侧 `goStep` 结构化（D17/D15 对齐） |
| 6 | ` M` | `frontend/tests/ai-panel-parts-render.spec.ts` | 渲染锁三路（重投 / 新宿主 / 历史整替） |
| 7 | ` M` | `frontend/tests/export-wizard-step-outcome.spec.ts` | T7② 扩展（D11 单弹）+ D14 反向留证 |
| 8 | ` M` | `frontend/tests/frontend-tool-result-terminal.spec.ts` | D8 / D9 / D10 锁 + T1 生死线 20 例 |
| 9 | ` M` | `frontend/tests/parts-reducer.spec.ts` | D5 建段锁 + 生命周期锁 |
| 10 | ` M` | `frontend/tests/parts-residue-cleanup.spec.ts` | D5 写点③建段锁 |

**1.2 锚定对照（提交前复验，防夹带）**【实测】（取证 `evidence-p16/anchor-verify.txt`、`anchor-regen.json`、`fidelity.json`）

按锚定生成脚本 `_anchor-diff-b3b.mjs`（固定段序 = 段内路径字典序；无未跟踪新文件故 `raw == sorted`）重生成：

```
锚定物  <SCRATCH>/chat-timeline-pkg/diff-b3b.sorted.patch   8b7149bedbd2350f4641068ed9a95bce4eea13117abbc38bd2a51bc2a1eda0c1
重生成  evidence-p16/diff-b3b.sorted.patch（收口修前）      8b7149bedbd2350f4641068ed9a95bce4eea13117abbc38bd2a51bc2a1eda0c1
cmp → IDENTICAL_BYTES（raw 亦 IDENTICAL_BYTES）   段数 10   字节 58420   统计 +529 / −79
CR = 0 / LF = 949（锚定物本身纯 LF）
保真验证器 _verify-fidelity-b3b.mjs：archive HEAD → 临时目录 tar -x → git apply -p1 → 10/10 文件 EOL 归一化 sha256 逐一致
```

⇒ **送入提交的字节集合 = 经施工棒实读 / 复核 / 红队锚定的字节集合，零夹带**（收口修前的基线态）。

**1.3 脱敏终扫（DC-20 不豁免）**【实测】（取证 `evidence-p16/desense-files.txt`、`desense-evidence.txt`、`_added-m2.txt`、`_added-fe.txt`）

| 扫描面 | 工具 / 规则 | 结果 |
|---|---|---|
| 10 文件（源码 5 + 测试 5） | 规则扫描器（盘符路径 / POSIX 挂载形 / 用户名 / 主机名 / 内网 IP / 密钥形态 / 命名纪律禁则三态） | **每件 0 命中（0 字面）**；M2 全量单文件扫描**唯一命中** = `tinyglobby`（npm 包名，含本地用户名主干子串，**PRE-EXISTING 行（`:283`，非本批新增）**，判**误报**） |
| M2 **加入行**（`git diff -U0` 的 `+` 行，7 行） | 独立字面复核器（组织名(owner) / 用户名(user.name) / 本地用户名主干 / github 域 / 仓库 URL 形 / `sk-` / apiKey 赋值 / secret·token·password 赋值 / Bearer / AKIA·ASIA / 私钥头 / 内网 IP / 127.0.0.1 / 盘符 / POSIX 挂载） | **全项 0**（硬命中 0）；`import.meta.env` 出现 3 次 = **代码标识符**（D12 的 DEV 门与注释），按卡面防误报口径**不判 .env 泄漏** |
| 前端 10 文件**加入行**（529 行） | 同上 | **全项 0**（硬命中 0）；`import.meta.env` 3 次同为代码标识符 |
| 7 份入库件（全量） | 规则扫描器 + 独立字面复核器 | **硬命中 0**；仅 127.0.0.1 各 1~2 处（施工报告 / 复核报告 / 施工卡 —— **既有书面口径保留、仅统计**） |
| 两笔提交信息 | 独立字面复核器 | **硬命中 0**（组织名 / 用户名 / 盘符 / URL / 密钥形态全 0） |

**1.4 前端门禁三连 + 文档门禁（工作树态实跑）**

| # | 命令 | 期望 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | `cd frontend && yarn test` | 125 / 11 spec | Test Files **11 passed (11)**，Tests **125 passed (125)**，exit 0（`ready`4 / `sse-watchdog`4 / `frontend-tool-outcome`9 / `parts-double-write`5 / `parts-reducer`15 / `tools-and-deadline`21 / `restore-reconcile`3 / **`frontend-tool-result-terminal`30** / `ai-panel-parts-render`7 / `export-wizard-step-outcome`5 / `parts-residue-cleanup`22） | ✅ |
| 2 | `cd frontend && yarn typecheck` | exit 0 | `vue-tsc --noEmit` Done in 3.37s，exit 0 | ✅ |
| 3 | `cd frontend && yarn build` | exit 0 | `✓ built in 25.12s`（1748 modules 级同形），exit 0 | ✅ |
| 4 | `node scripts/ci/check-docs.mjs`（仓库根） | 6/6 | 6/6 断言绿（`git ls-files` **986** / 实扫文本 983 / 豁免 3），exit 0 | ✅ |

**后端**：本批**零后端改动**（锚定 10 段全为 `frontend/**`）⇒ 按卡不重跑 Maven；后端 job 的 CI 结果见 §七（**success**）。

## 二、阶段 2 · 仓外文书修订（施工报告）+ 注释级收口修与重锚【实测】

**2.1 `施工报告-批3③b.md` 文书修订（仓外件，入库前）**（取证 `evidence-p16/report-edit-b3b.txt`）

- 体量 **25606 B / 217 行 → 33163 B / 225 行（内容 224 行）**；CR 计数 **0**（纯 LF，与源件一致）；**零代码语义改动**（唯一代码例外见 §2.2）。

| # | 条件（来源） | 落点 | 内容摘要 |
|---|---|---|---|
| ① | 红队 **M-1** / 条件① | §6（`:148`） | 补申 **D6(B) 取 `return` 形态**：守卫位于 `handleFrame` **switch 之前**、被拦帧**跳过** `deriveProjections` / `markApplied`、**`runSeq` 记账仍执行** ⇒ 不得与 D8 三 case 的 `break` 混同；**幂等由守卫条件自身保证、非 seq 幂等兜底**；推演无功能后果（O-4 同证），属申报口径缺口 |
| ② | 红队 **M-2** / 条件② | §3-S3（`:104`） | **死码口径精确化**：四块删除中**终态 tag 列确不可达**（不变式：终态 ⇒ `requiresAction=false`）；**FRONTEND `pending`「待浏览器自动执行」tag 瞬态可达**（`frontend_tool_request` 同步建段至写 `running` 的**亚 tick 窗口**，D8 只拦已决重投、与此无关）⇒ 后果为非功能性（可能不 paint），原「不可达死码」表述过宽已订正 |
| ③ | 红队 **M-3** / 条件③ | §3-S4（`:108`）+ §7(e)（`:165`）+ **代码注释收口修** | D9 注记自引用改**相对锚**；见 §2.2 |
| ④ | 红队 **O-6** / 条件④ | §3-S3（`:103`）、§8-7（`:182`）、§10-3（`:205`） | `data-testid="tag-form-cancelled"` 引用域限定为「**代码 / 测试域**」= 仅 `scripts/m2t2a-smoke.mjs` 2 处（`frontend/tests/` 零引用；`docs/evidence/` 3 份历史文档非可执行代码、不受影响）；并把 **m2t2a-smoke 断言③b 按设计失效**（`:299` `cancelledTags === 2`，静默假绿、不在 CI 链路）**补入 §8 台账项**，随批 4④ 改写或退役 |
| ⑤ | 红队 **O-8** / 条件⑤ | §6（`:144`） | 契约前提清单**补第 4 条「`suspended` 先于 `confirm_request` / 前端执行到达」**（实发序依据 `SpToolCallingManager.java:163` 先发 / `:169` 循环内 `checkCancelled`；`:172` 系 GLM·终裁稿偏差值、实质结论不变）—— 明示为 §7-add「迟到 suspended 降 pending」的**唯一缓释前提**，标题「三条」改「四条」 |
| ⑥ | 红队 **O-9** / 条件⑥ | §7-**6b**（`:164`） | 残余面**逐条点名补列 `appliedSeqs` 消息级幂等跨宿主失效**（声明 `types/ai.ts:216`、`markApplied` `:1749-1751`、不入镜像 `:290-298`）；本批缓释 = D6(B)+D8 判据落 toolCallId；四象限逐一实读、**无新增幂等缺陷**；残余两条（跨宿主+未决 / `runSeq` 跨会话） |
| ⑦ | 红队 **O-10** / 条件⑦ | §5（`:135`） | **CDP 截图 baseline 命名注记**：`shotBaseline` 实取在重投断言②~⑤**之后**、`shotFinal` 在⑥⑦之后（`batch3b-lifecycle-smoke.mjs:176`/`:191`），两图**同 md5 逻辑自洽、非造假**；命名易误读 ⇒ 本批**不改名不补拍**（红线：仓外脚本非本批授权面），移交后续批次办理；证据效力 = 截图 + result JSON 断言双证 |
| 3m | 红队 **M-1/M-2/M-3** | 同上 | 三项 minor 与上表 ①②③ 为同一办理面（逐项点名，不打包） |

**2.2 注释级收口修 + 重锚（本批唯一授权的代码级例外）**【实测】

- **动因**：红队 M-3 —— D9 防漂移注记**自引用绝对行号** `localTerminal`（`parts.ts:383-386`）恰被 ③b **自身新增注记**下移（工作树实读 **`:401`**），即「防漂移注记自身漂移」。
- **动作（仅两行措辞，零行数变化 / 零语义变化）**：
  - `frontend/src/utils/parts.ts:246`：`（本文件 :383-386）` → **`（本文件 \`frontend_tool_result\` case 内）`**
  - `frontend/src/stores/ai.ts:389`：`（parts.ts:383-386）` → **`（parts.ts 的 \`frontend_tool_result\` case 内）`**
- **重锚双 SHA（留痕）**：

| 口径 | SHA-256 | 段数 / 字节 / 增删 |
|---|---|---|
| **锚定物（入库前基线）** —— 复核 / 红队据此审查 | `8b7149bedbd2350f4641068ed9a95bce4eea13117abbc38bd2a51bc2a1eda0c1` | 10 / 58420 / +529 −79 |
| **重锚后（= 入库字节）** —— 本棒入库前重生成 | `8718040dc73507d5334432b8406211e08be01bc2f3833377774399887daa6d76` | 10 / 58471 / +529 −79 |

- 增量 = **+51 B**，恰好等于两行注释措辞的字节差；**段数 / 文件集 / 增删行数不变**（收口修落在 ③b 已新增的注释行内，不新增/删除行）⇒ 可被独立复核以「两行 diff」形式完全验证（`anchor-regen-r2.json`）。
- **未越界留痕**：`parts.ts` 同段 ① 条内的 `ai.ts:347-349` 交叉引用**未改**（超「仅该行」授权范围，且该值对基线 HEAD `505f6d4` / `3edaeb9` 而言**属实**）——见 §九-2 申报。

## 三、阶段 3 · 提交 1（feat，原子红线 R-a 单笔）【实测】

**暂存前逐项复核**：`git status` 10 条 ⇒ **逐文件显式 `git add`**（未用 `-A` / `.` / `-N`）；暂存清单实读 **恰 10 条**（全 ` M`），`git diff`（未暂存残留）对 10 文件**空**（仅余 M2 一条留给提交 2）；逐文件 `git ls-files -s` 的 index blob == `git hash-object <file>` **10/10 EQUAL**（⇒ 暂存字节 == 工作树字节）。

| 项 | 值 |
|---|---|
| commit | **`fc6e5a2ab4c5b503ca71f3f2487724228c3374e5`** |
| 主题 | `feat(frontend): 对话时序包批 3'③b 段状态机闸（挂起语义帧已决不重挂不重执行）+ 死码清除 + R-142 轮次身份校验` |
| 统计 | **10 files changed, 529 insertions(+), 79 deletions(-)** |
| pre-commit 钩子 | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` ⇒ **未使用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1` 放行分支** |
| 提交信息落件 | `evidence-p16/msg-p16-1.txt` |

提交信息要点（`-F` 落件）：基线 `505f6d4`；**双锚留痕**（锚定物 SHA + 重锚后 SHA + 收口修两行自陈）；D1~D16 逐条改动要点（D8 三 case 闸取 break / D6(B) switch 前四型守卫取 return / D5 公告即建段 + 双兜底 / D2 死码四块 + 两排除项 + 链收拢 / D3·D4 / D7 维持硬编码 → R-144 / D9 两表 + 三对注记 / D10 轮次身份校验 / D11 单弹 / D12 DEV 门 / D13 重启纪律 / D14 四例反向留证 / D15 导入侧结构化 / D16 不搭车）；验证口径（125/125·11 spec / typecheck 0 / build 绿 / E2E 桩 28 / CDP 10/10 / check-docs 6/6）；审查结论（GLM 复核 PASS 0/0/0/4O、DS Pro 红队带条件 PASS 0B/0M/3m/8O + 7 条件、K3 终审 PASS）。

**提交后零夹带终验**【实测】（取证 `evidence-p16/committed-equals-anchor.json`、`blob-reconcile.txt`、`commits-p16.txt`）：

| 核验面 | 结果 |
|---|---|
| 从已入库区间 `505f6d4..fc6e5a2` 按同一 10 段固定段序重生成补丁 | 与**重锚物原样字节相等 = true**；SHA-256 = `8718040dc73507d5334432b8406211e08be01bc2f3833377774399887daa6d76`（= 入库前重生成值）；EOL 归一化后 / 去 `index` 行后二次比对**亦相等** |
| 段数 / 增删 / 字节 | `segments=10 additions=529 deletions=79 bytes=58471`（与重锚物一致） |
| 逐文件 blob 哈希（锚定 `index` 行 b 侧 vs `git rev-parse HEAD:<file>`） | **10/10 EQUAL** —— `336ceb91` / `586e4c45` / `3129c315` / `4b3295f4` / `a6a16056` / `f6211f7c` / `7bc6a707` / `e767c386` / `32835b4b` / `a831fcdc` |

⇒ **入库字节集合 == 重锚字节集合（逐文件 10/10 + 全量补丁字节相等）；与入库前锚定物之差 = §2.2 的两行注释收口修，无第三处差异（补丁段数/文件集/增删行数全同）**。

## 四、阶段 4 · `docs/M2-排期计划.md` 落行与销记【实测】

**纪律执行**：只追加 + 内容零删 + 行尾注记；整行重写为载体且**旧文本逐字节保留**；**编辑前逐条重新实读**；锚点落**表体内**（`:332` R-143 行之后、表体闭合空行之前）。机械复验：`git diff -U0` 恰 **2 处 hunk**（`@@ -276 +276 @@` / `@@ -328,5 +328,6 @@`）、**numstat 7 增 / 6 删**、`1..275` 与 `277..327` 行与 HEAD **逐行字节全等**、6 条目标行「旧文本（去尾 ` |`）为新行前缀 + 新行以 ` |` 收尾」**全成立**、7 行单元格数一致（= 2）、R-144 为**唯一新增行**（取证 `evidence-p16/m2-verify-r2.json`）。

| 项 | 落点 | 内容摘要 |
|---|---|---|
| (a) 新增 **R-144** | **`:333`** | **后端历史携带状态立项候选**（出 ③b）：① 历史载荷**无状态字段**（`types/ai.ts:133-150` 的 `AiHistoryMessage` = type/text/toolCalls/toolResponses/timestamp；`:146-150` 的 `AiHistory`；后端同构）⇒ 前端无从还原真态；② 前端整替点**硬编码** `kind2:'UNKNOWN'` + 状态恒 `succeeded`（实读硬编码点 **`:659`**，全段 `:646-678`）⇒ 本地 `unknown`/`failed` 连段带状态被丢弃（与 R-141② 同族）；③ ③b 经 **D7(B) 维持硬编码**（占位值非事实断言）+ 段注释逐字注记，**未闭合**；去向 = 后端批；轴别 = 历史载荷保真（异于 R-130/R-132 身份轴）；R-141② 行尾注记指向本行。表体闭合空行随之 `:333 → :334`，③a 时期注记块 `:334 → :335` |
| (b) 行尾注记 **R-84** | **`:276`** | ① 补登 `views/ExportWizardView.vue:1340` ⇒ 同族面 **21 → 22**；② GLM §四-5 登记口径（机制 = `interpretHandlerOutcome` `:974-975` 字符串折 ok；**判定词「未闭合」**；后果 = 只读/越界拒绝卡面显「已成功」；去向 = R-84 批结构化）；③ 逐处行号（导出 11 / 导入 11 + 标注不计入 `ImportWizardView.vue:1057`）；④ 批 3③b **不搭车** |
| (c) 逐项销记 **R-139① / ③** | **`:328`** | ① `m2`（`sanitizeMirrorResidues` 的 `done !== true` 守卫死码）⇒ **已销记**（A2/D3 删守卫 + 入参契约与反向风险注释）；③ `O1`（挂起卡终态 tag 全集）⇒ **已销记**（A3/D2 四块删除 + 排除项保留 + 链收拢；**口径订正 M-2**：终态列不可达、`pending` 瞬态可达）；**连带台账补登（GLM O-2 / 红队条件④）**：m2t2a-smoke 断言③b 失效、静默假绿、随批 4④ 改写或退役；引用域限「代码/测试域」；② `m3` 维持、④ `O2`~`O5` 维持登记 |
| (d) 交付对齐注记 **R-140** | **`:329`** | ①-1 **维持移交**（A4 渲染位随批 4④）；①-2 ⇒ **已销记**（A5/D5 公告即建段 + 双兜底 + CDP ⑧⑨）；①-4 ⇒ **已销记**（A6/D4 镜像 `streaming` 段级归一）；①-5 ⇒ **未销记**（headless 截图无法静态目检）；7/8/9/10/11/12 维持登记；② `o1`~`o4` 维持（CDP 照 `o3` 配方复跑 10/10 + 脚本头重启纪律）；③ §5-5 口径角维持原状 |
| (e) 逐项销记 + 注记 **R-141** | **`:330`** | ① re-attach 新宿主消息 ⇒ **部分闭合（只闭合已决面）**（D6(B) 四型守卫 + 判据 + `return` 幂等自证），**未决双卡残余移交后续**、**不得表述「A7 已闭合」**、空宿主消息属**可接受 UX 残留**（登记）；② `loadHistory` 整替 ⇒ **维持硬编码（未闭合）**、**指向 R-144**；③ 通用 FRONTEND 重投帧不经守卫 ⇒ **已销记**（去 GF 前缀 + `confirm_request` 补守卫 + 调用点 4 + 遍历口径 + rogue 面双证消解）；④ ⇒ **已销记**（维持两表 + 三对注记 + **误述订正**：`CANCELLED` 注释**实存**（基线 `3edaeb9`/HEAD `505f6d4` 处 = `:347-349`，③b 工作树因 D9 注记落码下移为 `:354-356`），**唯一真实分叉 = `SUCCEEDED`**；区间终点 `parts.ts:232-249` → **`:232-250`**） |
| (f) 销记 **R-142** | **`:331`** | **已销记（主体闭合，残余窗口如实申报）**：A12/D10 入口捕获 `controllerAtStart` + await 后失配丢弃段写/回灌；锁 = D10 用例（重挂换句柄代理）；**残余** = `stop()` 至 `finally` 复位之间的窗口残留（本批未动，随 dispose 面治理批），不得据此表述「已彻底闭合」 |
| (g) 逐项销记 + 追加 **R-143** | **`:332`** | ① **已销记**（A13/D11 单弹 + T7② 锁）；② **已处置**（(A) 已排查无应用内入口 + (B) 重启纪律写入仓外两脚本头 + 03:00 旧 vite 进程处置留痕）；③ **已销记**（A14/D12 DEV 门 + 双向留证）；④ **已销记**（A16/D14 四例夹具就位仍红 + 恢复核验）；⑤ **行尾追加 M7/M8/M9**（逐条点名：`ExportWizardView.vue:699-720`（`:708`）/ `:561-583`（`:566`）/ `frontend-tools.ts:908-933`（`:921-925`）——本批零改动）；⑥ **行尾追加 `runSeq` 跨会话残留**（`resetSession` `ai.ts:1376-1437` 未清；声明 `:444`、写点 `:800`/`:1022`/`:1514`；key=runId，仅后端复用 runId 才串味，登记不修） |

**自查缺陷与自纠留痕（如实申报）**：首轮行尾注记在 **R-141 / R-142 两行**误置于「状态单元格之前」（该两行实为 2 单元格行），机械复验的 `prefixKeep=false` **即时暴露**（`evidence-p16/m2-verify.json` 首版留证）；随即自纠 4 处编辑（恢复原分隔 + 注记移至行尾）并复验**全 OK**（`m2-verify-r2.json`）；另发现 **R-144 新行首编辑缺行尾 ` |`**，同法自纠。见 §九-3。

## 五、阶段 5 · evidence 入库（7 份）【实测】

**纪律**：源件只读、页首来源标识、统一脱敏（盘符路径 → `<MAIN_REPO>` / `<SCRATCH>` / `<OTHER_REPO>` 占位，最长优先有序替换）。

| 源件（仓外） | 入库件（`docs/evidence/`） | 源行数 → 入库行数 | 替换面 | 源件 sha256 / 入库件 sha256（前 16） |
|---|---|---|---|---|
| `决策卡-批3③b.md` | `补档-CHATTIMELINE-批3③b-决策卡-K3.md` | 342 → 344 | 3 处路径 | `39e0d398206b5c3b` / `34810199bfa9c71f` |
| `批3③b-施工卡.md` | `补档-CHATTIMELINE-批3③b-施工卡.md` | 565 → 567 | 11 处路径 | `52292ed31a73377f` / `faf87bddcfd53594` |
| `施工报告-批3③b.md` | `补档-CHATTIMELINE-批3③b-施工报告-K2.8.md` | 225 → 227 | 3 处路径 | `29594487df4094df` / `13029e4629b36e81` |
| `复核报告-批3③b.md` | `补档-CHATTIMELINE-批3③b-复核报告-GLM-5.3.md` | 209 → 211 | 1 处路径 | `2b3f653ae584850e` / `d66f79071eb0222e` |
| `红队报告-批3③b.md` | `补档-CHATTIMELINE-批3③b-红队报告-DS-V4-Pro.md` | 242 → 244 | **0 处**（源件已无路径字面） | `07c47f27e2794f2d` / `b5fbf21aedc93a5c` |
| `会审-批3③b-GLM.md` | `补档-CHATTIMELINE-批3③b-会审报告-GLM-5.3.md` | 155 → 157 | 1 处路径 | `b0b3e1ba2d8c7a4a` / `e2a3ea07eda3bd0c` |
| `会审-批3③b-DSPro.md` | `补档-CHATTIMELINE-批3③b-会审报告-DS-V4-Pro.md` | 269 → 271 | 3 处路径 | `c49edc45af24c202` / `50faf6b3cf24d6b8` |

- 会审**卡**两张（`会审卡-批3③b-GLM.md` / `-DSPro.md`）**不入库**——照 ③a 先例（③a 亦未入会审卡），与提交卡「可入可不入」条款一致。
- `施工报告-批3③b.md` 的入库件页首来源标识中**逐条记明**入库前经提交官按 7 条件 + 3m 的文书层修订面（可追溯）。
- 入库后全量重扫：**硬命中 0**（`evidence-p16/desense-evidence.txt`）。逐件 sha256 对照见 `evidence-p16/evidence-hashes.txt`。

## 六、阶段 6 · 提交 2（docs）与 push【实测】

| 项 | 值 |
|---|---|
| commit | **`296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`** |
| 主题 | `docs(m2,evidence): 批 3'③b 决策卡/施工卡/三报告入库 + R-144 落行 + 台账逐项销记/注记` |
| 统计 | **8 files changed, 2021 insertions(+), 6 deletions(-)**（7 新建 + `docs/M2-排期计划.md`） |
| pre-commit | `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）` |
| 门禁 | 提交前（暂存态）`check-docs` **6/6**（`git ls-files` **993** = 986 + 7）/ HEAD 态 **6/6** |
| push | `git push origin main` ⇒ `505f6d4..296ffe2  main -> main`（**无 force、无 non-fast-forward**）；`git fetch` 后 `origin/main...HEAD` = **0 / 0** |
| 提交信息落件 | `evidence-p16/msg-p16-2.txt` |

## 七、CI 守夜（匿名轮询 · `--ssl-no-revoke` · 间隔 180s · 预算 60 次/h · **实耗 4 次调用**）【实测】

取证件：`evidence-p16/ci-poll-p16.sh`、`ci-poll-p16.log`、`ci-run-summary-p16.json`（字段白名单派生）、`runs-1-masked.json` / `jobs-1-masked.json`（owner 段遮蔽，见 `_masking-note-p16.txt`）。

| 项 | 值 |
|---|---|
| run | **`38166395544`**（run_number **`100`**，event `push`，workflow `CI 门禁`） |
| head_sha | `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（= 本批 HEAD，第 1 轮即锁定本批 run，未误取他批） |
| 结论 | **completed / success**（created 2026-10-11T19:03:23Z → updated 19:06:36Z，约 3 分 13 秒） |

| # | job id | job 名 | status | conclusion |
|---|---|---|---|---|
| 1 | `114549733740` | 前端类型检查与构建 | completed | **success** |
| 2 | `114549733933` | 全历史脱敏扫描（报告制 · 不计门禁） | completed | **success** |
| 3 | `114549733953` | 文档一致性门禁（check-docs）｜阻断 | completed | **success** |
| 4 | `114549733978` | 后端编译与单测 | completed | **success** |
| 5 | `114549975243` | E2E 全流程（29 用例 · 桩上游 + redis） | completed | **success** |

- **E2E job 晚出现**（第 1/2 轮 job_count 未达 5）：本轮按卡「勿误判」口径**不提前判负**，第 3 轮 run completed 后一次取全 **5 job**；`_poll-state-p16.txt` = `ALLDONE` 后停止。
- 无失败 job ⇒ **无 annotations 需带回**（未触发「失败取 annotations 不得自修」分支）。

## 八、脱敏终扫（0 硬命中）【实测】

| 面 | 工具 | 结果 |
|---|---|---|
| 10 文件全量 | `desense-scan.mjs`（盘符 / POSIX 挂载 / 用户名 / 主机名 / 内网 IP / 密钥形态 / 命名纪律禁则） | 10 件**全部 0 命中** |
| M2 加入行（7 行）+ 前端加入行（529 行） | `_desense-p16.mjs`（组织名(owner) / user.name / 本地用户名主干 / github 域 / URL 形 / `sk-` / apiKey / secret·token·password / Bearer / AKIA·ASIA / 私钥头 / 内网 IP / 127.0.0.1 / 盘符 / 挂载形） | **硬命中合计 0** |
| 7 入库件全量 | 双工具 | **硬命中 0**（仅 127.0.0.1 各 1~2 处，按既有书面口径保留） |
| 两笔提交信息 | `_desense-p16.mjs` | **硬命中 0** |
| 防误报口径 | —— | ① `import.meta.env.DEV`（D12 DEV 门）为**代码标识符**、非 `.env` 文件读取；② M2 `:283` 的 `tinyglobby`（npm 包名）含本地用户名主干子串，**PRE-EXISTING 行、非本批新增**，判误报；③ `127.0.0.1` 按既有书面口径保留；④ **本段残余复核** = 本地用户名主干规则 **2 处，全部为 ② 同一 npm 包名**（真实依赖名、非用户名 / 非组织名；改述即失真，故保留并声明）⇒ **组织名 / github 域 / 仓库 URL / 盘符路径 / 用户名(user.name) / 密钥形态 全为 0** |

## 九、未发生项与异常申报（如实留痕）

1. **【自证 · 如实】署名与运行时标识差异**：本棒运行时可自证标识 = **Kimi Code 子智能体（Kimi 系列）**；卡面/index 署名为 **DeepSeek V4 Flash（显式钉版）**。二者差异**无法在本棒自证为同一**，按 DC-19 口径**署名从卡、差异留痕**（第 15 期同款申报，第 16 期延续）。父棒（K3 指挥官）下发的执行者参数 = DeepSeek V4 Flash，本报告首页与签字按该参数署名。
2. **注释级收口修（唯一授权代码例外）**：`parts.ts` / `ai.ts` 各一行改相对锚 ⇒ **重锚**（`8b7149be…` → `8718040d…`，+51 B，段数/文件集/增删行数不变）。**未越界留痕**：同段 ① 条内 `ai.ts:347-349` 交叉引用**未改**（超「仅该行」授权）；该值对基线 `3edaeb9` / `505f6d4` **属实**，对 ③b 工作树为 `:354-356`（差异已在 R-141④ 行尾注记内双方并列写明）。**建议**：后续批次将 D9 注记内的**全部**绝对行号一律改相对锚（含该交叉引用），并考虑在施工卡层面立「注记自引用禁写绝对行号」纪律。
3. **M2 落行自查缺陷与自纠**：首轮在 R-141 / R-142 两行将注记置于「状态单元格之前」（两行实为 2 单元格行）⇒ 机械复验 `prefixKeep=false` 即时暴露 ⇒ 自纠 4 处编辑后全 OK；另 R-144 新行首编辑缺行尾 ` |`，同法自纠。**两版复验件均留证**（`m2-verify.json`（含 FAIL）/ `m2-verify-r2.json`（全 OK））。**未产生任何内容删除**（旧文本逐字节保留、只追加）。
4. **E2E 与 CDP 本棒未复跑**：口径沿用 ③a —— 施工棒实跑留证（`批3b-evidence/E2E-stub-final.log` 28-0 + `cdp/result-20261011T182213Z.json` 10/10）由**复核棒（GLM-5.3）独立复现**（五次独立复现全绿，其两次配方性失败已定位为复现者环境/参数问题并修正、与产品无关）。本棒仅**核读**留证件，未自跑（无后端改动、无前端行为改动面以外的依赖变化）。
5. **台账落行范围申报（超卡面枚举的三项，逐项点名）**：卡面「销记」清单枚举 6 项 + R-143② 标已处置；本棒另按**施工报告 §8-1「③b 可销记（16 项）」**补办 **R-139③、R-140①-2、R-140①-4** 三项销记（依据：施工报告 §8 表系「提交官办理」项 + 红队条件④要求 m2t2a-smoke 失效入台账）。全部为**只追加 + 逐项点名 + 内容零删**，可逐行定位、可回退。**③a §12 M6 无需落行**：M2 台账内 `M6` 字面实测 **0**（非本表载体）。
6. **R-140①-5（复制按钮位置）与 R-143② 的口径**：前者须**人工视觉面**（③b 截图 hover 显形、无法静态目检）⇒ 维持**未销记**；后者按卡标**已处置**（(A) 无应用内入口 + (B) 重启纪律入脚本头）。
7. **`docs/README.md` 计数漂移（未改，承接申报）**：`:44` 的「补档-*.md 共 24 个文件」vs 现态实测（本批再增 7 份）**持续漂移**；本批按批 1 / 第 15 期同口径**不动**，建议另立 docs 小批统一订正（`check-docs` 6/6 不涉该计数字面）。
8. **本批未发生项**：未动 `<OTHER_REPO>`（另一条线的仓，全程零访问）；未读任何 `.env`；未使用 `--no-verify` / `AI_SECRETS_ALLOW`；未 force push；未改任何仓外源件（施工报告修订面已由入库件页首标识与 §2.1 表留痕）；未触发 CI 失败分支 ⇒ 无 annotations 带回。

## 十、本批产出物

| 内容 | 路径 / 值 |
|---|---|
| 本报告 | `<COMMIT-GOV>/提交报告.md`（本次为追加的**第 16 期**条目，紧接第 15 期之后） |
| 入库件（仓库内，2 笔提交 / 18 文件） | `fc6e5a2`（前端 **10**，+529 −79）+ `296ffe2`（docs **8**：`docs/M2-排期计划.md` + 7 份 `docs/evidence/补档-CHATTIMELINE-批3③b-*.md`） |
| 基线链 | `505f6d4` → **`fc6e5a2`** → **`296ffe2`**（= `origin/main`） |
| 锚定取证 | `<SCRATCH>/chat-timeline-pkg/evidence-p16/anchor-verify.txt`、`anchor-regen.json`（收口修前）、`anchor-regen-r2.json`（重锚后）、`committed-equals-anchor.json`、`blob-reconcile.txt`、`fidelity.json` / `fidelity-r2.json`、`diff-b3b.sorted.patch`（重锚件）、`committed-diff-b3b.patch` / `.sorted.patch` |
| 机械验收 / 门禁输出 | 同目录 `phase1-status.txt`、`fe-gates-p16.txt`、`m2-verify.json`（首版，含 FAIL 留痕）、`m2-verify-r2.json`（终版）、`report-edit-b3b.txt` |
| 提交 / push 取证 | 同目录 `msg-p16-1.txt`、`msg-p16-2.txt`、`commit-1.txt`、`commit-2.txt`、`commits-p16.txt`、`commit-msgs-p16.txt`、`push.txt` / `push-masked.txt` |
| CI 取证 | 同目录 `ci-poll-p16.sh`、`ci-poll-p16.log`、`ci-run-summary-p16.json`、`runs-1-masked.json`、`jobs-1-masked.json`、`_masking-note-p16.txt`、`_poll-state-p16.txt` |
| 脱敏取证 | 同目录 `desense-scan.mjs`、`desense-files.txt`、`_added-m2.txt`、`_added-fe.txt`、`desense-evidence.txt`、`desense-evidence-src-scan.txt` |
| evidence 入库取证 | 同目录 `_ingest-evidence-p16.mjs`、`ingest-report.json`、`evidence-hashes.txt` |
| 施工棒 / 复核棒 / 红队棒批内留证（仓外，本棒只读核对） | `<SCRATCH>/chat-timeline-pkg/批3b-evidence/`（RED-pre / GREEN-s4 / GREEN-final、BUILD、RED-T7-1~4、E2E-stub / E2E-stub-final、stub / backend / vite-dev、`cdp/` 2 png + result JSON） |

---

**签字**：提交官 **DeepSeek V4 Flash**（第 16 期；**运行时可自证标识 = Kimi Code 子智能体（Kimi 系列）**，差异见 §九-1），2026-10-12。**授权来源 = K3 指挥官批 3' ③b 提交指令（2026-10-12）**。本报告所列证据均可于 `<SCRATCH>/chat-timeline-pkg/evidence-p16/` 与仓库两笔提交（`fc6e5a2` / `296ffe2`）处复核；CI 一律以 run id / job id 取证，全文 0 组织名字面。

---

# ai-example 合流项目 · 提交官报告（第 17 期：对话时序包批 4④「折叠规则」全量入库 —— **三笔细分提交**（feat + feat + docs，19 文件）+ 8 份证据入库 + M2 落行 **R-145** 与逐项销记/注记；**CI 五 job 全绿 ⇒ 闭环**）

- 本期执行者：**提交官**（Kimi Code 子智能体，本棒 id = **`agent-549`**）——**实测实际运行模型 = `DeepSeek/deepseek-flash`**（**DC-19：读本棒自身 `agents/agent-549/wire.jsonl` 的 `llm.request.modelAlias` 实证，非配置推定**，见 §零）
- 授权来源：**K3 指挥官派单 · 提交官第 17 期 · 批4④ 入库收口（本卡）**；上游依据 = 《决策卡-批4④.md》（K3 代签）+《批4④-施工卡.md》（定稿）
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**本段全文 0 组织名字面**，CI 一律以 **run id / job number** 取证
- 起点 HEAD = **`296ffe2`**（= `origin/main`，批 3'③b 提交批）｜本批三笔提交：**`437999e`**（feat）+ **`0a52f98`**（feat）+ **`6c9c699`**（docs）｜push 后 HEAD = `origin/main` = `6c9c699aa680297ecf0a83ec39eeae8716b68431`（ahead 0 / behind 0）
- 取证件目录（仓外）：`<SCRATCH>/chat-timeline-pkg/evidence-p17/`
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜`core.autocrlf=true`｜yarn `1.22.22`｜node `v24.21.0`

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 1 机械验收 | ✅ `git status --porcelain` 实读**恰 6 项**（4 M + 2 ??，无第 7 项）；HEAD = `296ffe2` = `origin/main`；**逐文件 sha256 6/6 EQUAL**（vs `anchor-b4-fix.json`.`worktreeSha256`）；入库锚 `diff-批4④-fix.sorted.patch` sha256 = `c780ef28…0ea3f`（**75306 B**）复算一致；报告结论抽验全中（vitest **154/154 · 12 spec** / typecheck 绿 / build 绿 / E2E 桩 **PASS=28 FAIL=0 GFSKIP=0** / CDP **10/10** / m2t2a exit **0**） |
| 2 脱敏终扫 | ✅ **硬命中 0**（6 在制品全量 + 888 行加入行 + 8 份入库件全量 + M2 加入行 + 三笔提交信息）；`localhost` / `127.0.0.1` / `import.meta.env` 按既有口径仅统计 / 防误报 |
| 3 细分提交 + push | ✅ 三笔**逐文件显式 `git add`**（未用 `-A` / `.`）；暂存清单实读**逐笔恰 3 / 3 / 9**；**index blob == 工作树 blob 逐笔全 EQUAL**；三笔 **pre-commit 全通过（未用 `--no-verify`、未触发 `AI_SECRETS_ALLOW=1`）**；push `296ffe2..6c9c699` |
| 4 evidence 入库 + M2 | ✅ **8 份**入 `docs/evidence/`（决策卡 / 施工卡 / 四报告 / 两会审报告；**会审任务卡 2 张照先例不入库**，见 §四-1 份数说明）；页首来源标识 + 定盘脱敏，**仓外源件零改动**（sha256 对照见 §四-2）；M2 **R-145 落行 `:334`** + **3 处行尾销记/注记**（追加式、零内容删除，前缀校验 PASS）；`check-docs` **6/6 绿** |
| 5 CI 守夜 | ✅ **全绿**：run **`38185540742`**（run_number **`101`**，head_sha = `6c9c699`）= **completed / success**，**五 job 全 completed 且全 success**（E2E job 第 2 轮才出现，未误判）；匿名轮询 **3 轮 / 6 次 API 调用**（60/h 预算内） |
| 6 提交后终验 | ✅ HEAD 态复跑：vitest **154/154 · 12 spec** / typecheck 绿 / build 绿 / `check-docs` **6/6**；**从零夹带终验：提交后 blob ↔ 入库前工作树 sha256 6/6 EQUAL**；工作树干净、`origin/main` = HEAD |
| 双 SHA 纪律 | **审查锚（已消费，复核/红队据此）= `b3526474…7467`**（69971 B）；**入库锚（本批入库字节据此）= `c780ef28…0ea3f`**（75306 B）。二者**不得混用**：本报告全部验收数字对**入库锚**，审查结论链对**审查锚** |

---

## 零、（DC-19）执行者署名与自证 —— 实际模型读 wire 实证

- **本棒 id**：`agent-549`（Kimi Code 子智能体；父会话 = K3 指挥官所在 CU 会话 `<KIMI_SESSION_A>`）
- **自证方式**：读**本棒自身** wire 文件 `<KIMI_HOME>/sessions/wd_temp_38bf…/<KIMI_SESSION_A>-…/agents/agent-549/wire.jsonl`（**禁止配置推定**）
- **实证命令**：`grep -o '"modelAlias":"[^"]*"' wire.jsonl | sort | uniq -c`
- **实测命中**：`"modelAlias":"DeepSeek/deepseek-flash"` —— `llm.request` 记录 **77 条，77/77 全为该值**（读取时点 2026-10-12 07:46，文件 784,062 B，仍在追加）；`model` = `deepseek-flash` ×77；`provider` = `openai` ×77
- **身份佐证**（同 wire 实读命中，证明该 wire 即本棒）：本批三笔 commit 哈希 `437999e` ×9 / `0a52f98` ×8、入库件名 `补档-CHATTIMELINE-批4④-决策卡-K3` ×15、`R-145 取号落行` ×5
- **结论**：**实际运行模型 = `DeepSeek/deepseek-flash`**（wire 实证，非配置推定），与「**机械活下沉条款**钉 model=DeepSeek/deepseek-flash」的口径一致
- **机械活下沉申报（如实）**：本棒**未做子智能体下沉** —— 本棒工具面**不含子智能体派生能力**（无 `Agent` / task-spawn 工具，仅 Bash / Read / Write / Edit / Glob / Grep / 浏览器 / CU 等），故**脱敏扫描、逐文件 sha256、台账落行均由本棒内联执行**；三者脚本化（`_desense-p17.mjs` / `_verify-m2-p17.mjs` / `_verify-fidelity-p17.mjs`）并全部留证，**无「钉 model 未落实」风险**（本棒自身即 deepseek-flash）

---

## 一、阶段 1 · 前置机械验收（HEAD=`296ffe2` 工作树态，6 项在制品）【实测】

**1.1 基线实读** —— `git status --porcelain` **恰 6 项**（`M`×4 + `??`×2，与派单基线**逐条一致**，无第 7 项、无 `D`/`R`/`A` 等第三类条目）：`frontend/src/components/AiPanel/AiPanel.vue` / `frontend/src/stores/ai.ts` / `frontend/tests/ai-panel-parts-render.spec.ts` / `scripts/m2t2a-smoke.mjs`（M）+ `frontend/src/utils/blocks.ts` / `frontend/tests/blocks.spec.ts`（??）。`git rev-parse HEAD` = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6` = `origin/main`；索引无暂存（`indexCachedBefore` / `After` 均空）。

**1.2 逐文件 sha256（vs 入库锚 `anchor-b4-fix.json`.`worktreeSha256`）** —— **6/6 EQUAL**：

| 文件 | bytes | sha256（前 16） | 结论 |
|---|---|---|---|
| `frontend/src/components/AiPanel/AiPanel.vue` | 41676 | `726c17e168b5185e` | EQUAL |
| `frontend/src/stores/ai.ts` | 100081 | `569de94868321bd9` | EQUAL |
| `frontend/tests/ai-panel-parts-render.spec.ts` | 24762 | `cefec273447e7a39` | EQUAL |
| `scripts/m2t2a-smoke.mjs` | 28862 | `07ed083d6279f093` | EQUAL |
| `frontend/src/utils/blocks.ts` | 5769 | `3893b002f30465b1` | EQUAL |
| `frontend/tests/blocks.spec.ts` | 10907 | `83a6ef6c5066ad61` | EQUAL |

**入库锚本体复算**：`sha256sum diff-批4④-fix.sorted.patch` = `c780ef289af049aa8e35eef93c2ed9658b25290e3373a1512fe2536b6b30ea3f`（**75306 B**，与卡面权威值一致）；`anchor-b4-fix.json` 自述 `allAssertionsPassed=true`（含「新锚定逐段重建 == 工作树实际文件 6/6 true」「旧锚定 patch sha256 == `b3526474…7467`」）。

**1.3 施工/收口修报告结论抽验**（证据 `evidence-p17/` 与 `evidence-p17/fix/`）—— 全中：

| 抽验项 | 证据 | 实读 |
|---|---|---|
| vitest **154/154 · 12 spec** | `fix/vitest.log` | `Test Files 12 passed (12)` / `Tests 154 passed (154)` |
| typecheck 绿 | `fix/typecheck.log` | **0 B**（无输出即无诊断）|
| build 绿 | `fix/build.log` | `✓ built in 25.80s` / `Done in 29.85s` |
| E2E 桩 **PASS=28 / FAIL=0 / GFSKIP=0** | `fix/E2E-stub-fix.log` | `结果：PASS=28  FAIL=0` / `GFSKIP=0` / `Q8 项：PASS=2 FAIL=0` / `e2e-exit=0` |
| CDP 冒烟 **10/10** | `fix/cdp-smoke-fix.log` | `[done] 10/10 PASS`（形态 1a/1b/1c/2/3/4；截图 6 张） |
| m2t2a exit 0 | `fix/GREEN-m2t2a-fix.log` | `[断言②] PASS` / `[断言③] PASS {"cancelledSegments":2,"hangingSegments":0}` / `[断言③b] PASS {"formRendererCount":0,"hangingSegments":0,"cancelledStatusTags":3}` |

**1.4 批次结论链核对**：K2.8 施工 V1~V12 全绿 → GLM 复核 PASS（0B/0M/6m/6O）→ DS Pro 红队带条件 PASS（0B/1 MAJOR-latent/5m/5O）→ K3 处置 F1~F8 → K2.8 收口修全绿 → 重锚 → K3 终审 PASS —— **链内数字与 1.3 抽验逐项自洽**。

---

## 二、阶段 2 · 脱敏终扫（DC-20 不豁免，**硬命中 0**）【实测】

**工具**：`<SCRATCH>/chat-timeline-pkg/_desense-p17.mjs`（自研独立字面复核器；**仅计数、不打印匹配内容**；组织名 / 本机用户名 / 主机名由 `git config` / env **动态取、不写死**）。规则面 = 组织名(owner) · 用户名(user.name) · 本地用户名主干（动态取 `git config user.name` 的主干，`-` 前段） · 主机名 · github 域 · 仓库 URL 形 · **仓库命名纪律（`main`+分隔符+`v2` 任意组合）** · `sk-` 形 · apiKey 赋值 · secret/token/password 赋值 · Bearer 形 · AKIA/ASIA · 私钥头 · 内网 IP(10/172/192/169) · 127.0.0.1（仅统计）· `localhost`（仅统计）· 盘符路径 · POSIX 挂载形 · 用户目录前缀（仅统计）· `import.meta.env`（仅统计/防误报）。

| 扫描面 | 取证 | 结果 |
|---|---|---|
| 6 个在制品**全量** | `desense-files.txt` | **硬命中 0**（`AiPanel.vue` `localhost`=1、`m2t2a-smoke.mjs` `127.0.0.1`=3 / `localhost`=4、`ai.ts` 与 `blocks.ts` `import.meta.env`=1 —— 均为代码常量/标识符，按口径仅统计） |
| 6 文件**加入行**（888 行） | `_added-fe.txt` → `desense-added.txt` | **硬命中 0**（`import.meta.env`=2，同为代码标识符） |
| 8 份入库件**全量** | `desense-evidence.txt` | **硬命中 0**（脱敏后复扫；`127.0.0.1` 仅在复核/收口修报告各 1~3 处，**按既有书面口径保留、仅统计**） |
| 库外源件（入库前）预扫 | `desense-evidence-src-scan.txt` | **92 硬命中**（= 待脱敏面，逐类落盘：盘符 65 / 命名纪律 20 / 用户名主干 7 / …），经 RE/占位替换后降为 0 |
| M2 加入行（3 行） | `_added-m2.txt` → `desense-added.txt` | **硬命中 0** |
| 三笔提交信息 | `desense-msg.txt` | **硬命中 0**（`127.0.0.1` 仅 1 处出现在提交 3 正文的「保留」说明中） |

**防误报口径（如实声明）**：① `import.meta.env.DEV` 为**代码标识符**（DEV 门），非 `.env` 文件读取；② M2 `:283` 的 `tinyglobby`（npm 包名）含本地用户名主干子串，**PRE-EXISTING 行、非本批新增**，判误报；③ `127.0.0.1` / `localhost` 按既有书面口径保留（仅统计）；④ 代码文件中「批4④」「msg-1」「a-1:」等**测试键名与注释属正常内容**，非敏感信息；⑤ **本批全文残余复核** = 组织名 / github 域 / 仓库 URL 形 / 盘符路径 / 用户名(user.name) / 主机名 / 密钥形态 **全为 0**；⑥ **本报告自段复核（`:2780` 起，`desense-report-p17.txt`）** = 本地用户名主干规则**命中 2 处，全部为同一 npm 包名「`tinyglobby`」的两处提及**（本项 ② 与本项 ⑥ 各一；真实依赖名、改述即失真，故保留并声明——与第 16 期 §八 ④ 同口径）；**组织名 / 用户名 / 主机名 / 盘符路径 / 仓库 URL 形 / 密钥形态仍全 0**（本轮已就地删除本报告文中两处**自指式**规则名字面，使盘符类硬命中由 1 降为 0）。

**pre-commit 钩子（R-129 族）**：三笔提交钩子**均输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`** —— **未触发误报、未启用 `AI_SECRETS_ALLOW=1` 逃生口、未使用 `--no-verify`**。

---

## 三、阶段 3 · 细分提交 + push（三笔，逐笔归属确认）【实测】

**暂存纪律**：三笔**逐文件显式 `git add`**（未用 `git add -A` / `.`；一次性 `add -N` 仅用于「写入前取加入行」**取证**，随即 `git reset` 复原，见 §二 加入行扫描，**未进入任何提交**）；逐笔 `git diff --cached --name-only` 实读恰 3 / 3 / 9 条；逐笔 `index blob == git hash-object <file>` **全 EQUAL**（⇒ 暂存字节 == 工作树字节，零夹带）；`git diff`（未暂存残留）**逐笔为空**。

| # | commit | 主题 | 文件（逐件） | 增删行 |
|---|---|---|---|---|
| 1 | **`437999e`** | `feat(frontend): 对话时序包批 4④ 挂起卡/工具卡合并（段驱动单卡）+ A4 GF 取消回灌失败文案段 reason 位` | `AiPanel.vue`（202/113）+ `stores/ai.ts`（51/1）+ `ai-panel-parts-render.spec.ts`（256/13） | **+509 −127** |
| 2 | **`0a52f98`** | `feat(frontend): 对话时序包批 4④ 折叠规则三规则本体（groupParts/expandedBlocks 三态）+ m2t2a 断言③③b 段态化` | `utils/blocks.ts`（134/0，新建）+ `tests/blocks.spec.ts`（213/0，新建）+ `m2t2a-smoke.mjs`（32/18） | **+379 −18** |
| 3 | **`6c9c699`** | `docs(m2,evidence): 批 4④ 决策卡/施工卡/四报告/两会审入库 + R-145 落行 + 台账逐项销记/注记` | `docs/M2-排期计划.md`（3/2）+ 8 份 `docs/evidence/补档-CHATTIMELINE-批4④-*.md`（188+182+464+182+149+567+175+193 / 0） | **+2103 −2** |

**逐笔信息正文均含**：折叠三规则 / A1 / A4 摘要、收口修 **F1~F8** 办理摘要、**入库锚 sha256 `c780ef28…`**、测试计数（**154/154 · 12 spec**、**E2E 28**、**CDP 10/10**、m2t2a exit 0）。

**锚定等价性双证**：① 提交 1+2（6 文件面）增删 = `509+379` / `127+18` = **+888 −145**，与**入库锚** `+888/−145` **逐数相等**；② 提交后 blob ↔ 入库前工作树 sha256 **6/6 EQUAL**（§六-2）。⇒ **零夹带、零遗漏**。

**push**：`git push origin main` ⇒ `296ffe2..6c9c699  main -> main`（取证 `push.txt`，脱敏版 `push-masked.txt`：owner 段替换为 `<REPO-OWNER>`）；push 后 `git rev-list --left-right --count origin/main...HEAD` = `0  0`。

---

## 四、阶段 4 · `docs/evidence` 入库 + M2 台账 + check-docs【实测】

**4-1 入库（8 份；份数说明见末条）** —— 工具 `<SCRATCH>/chat-timeline-pkg/_ingest-evidence-p17.mjs`（**源件只读**、页首来源标识、**最长优先有序替换**）；落 `docs/evidence/`：

1. `补档-CHATTIMELINE-批4④-决策卡-K3.md`（← `决策卡-批4④.md`）
2. `补档-CHATTIMELINE-批4④-施工卡.md`（← `批4④-施工卡.md`，定稿）
3. `补档-CHATTIMELINE-批4④-施工报告-K2.8.md`（← `施工报告-批4④.md`）
4. `补档-CHATTIMELINE-批4④-复核报告-GLM-5.3.md`（← `复核报告-批4④.md`）
5. `补档-CHATTIMELINE-批4④-红队报告-DS-V4-Pro.md`（← `红队报告-批4④.md`）
6. `补档-CHATTIMELINE-批4④-收口修报告-K2.8.md`（← `收口修报告-批4④.md`）
7. `补档-CHATTIMELINE-批4④-会审报告-GLM-5.3.md`（← `会审-批4④-GLM.md`）
8. `补档-CHATTIMELINE-批4④-会审报告-DS-V4-Pro.md`（← `会审-批4④-DSPro.md`）

**份数说明（如实申报，请 K3 核）**：卡面写「入库 **9 份**」，但其**枚举清单为 8 份**（决策卡 / 施工卡 / 施工报告 / 复核报告 / 红队报告 / 收口修报告 / 会审 GLM / 会审 DSPro）并另注「会审任务卡不入库」。本棒按**枚举清单**入库 **8 份**（与批 3③a / ③b 先例同构：③a/③b 各 7 份 = 决策卡+施工卡+施工报告+复核报告+红队报告+两会审，本批+收口修报告 = 8）；**会审任务卡 2 张、复核/红队/收口修任务卡照先例不入库**。若「9 份」另有指定第 9 件（如 `批4④-施工卡草案.md`），请示下，可另起 docs 追加提交。

**4-2 脱敏规则与源件零改动** —— 占位替换：`<MAIN_REPO>`（主仓库路径）/ `<SCRATCH>` / `<OTHER_REPO>`（另一条线，严禁触碰）/ `<OTHER_REPO-2>`（主仓库同目录被红线的另一仓）/ `<KIMI_HOME>`（wire 路径前缀）/ `<HOME>` / `<USER>` / `<HOST>`；**保留** = GitHub 公开组织名、`127.0.0.1`、`localhost`、sha256、行号、会话 id（既往口径）。逐件 sha256（源件 ↔ 入库件）落 `evidence-hashes.txt`；**8 件源件 mtime 全为入库前时点（2026-10-12 03:51~05:28 本地），零改动**。

**4-3 M2 台账（`docs/M2-排期计划.md`；纪律 = 只追加 + 内容零删 + 行尾注记 + 编辑前实读 + 锚点落表体内）**：

| 项 | 落点 | 内容 |
|---|---|---|
| **R-145 取号落行** | **`:334`**（紧接 `R-144`（`:333`）之后、表体闭合空行之前） | AiPanel.vue **宿主候选 `'approved'` 死判据**（段派生投影 `pendingStatusOf` 永不产出 `approved`；G-C9 登记，去向 = **后续清理批**）；本棒**先实读确认 R-144 在 `:333`** 后落行，行结构 = 3 个未转义管道（表行合法）|
| **R-140①-1 销记** | `R-140` 行尾（`:329`，+1734 字符） | 批 4④ **A1 模板合并 + A4 GF 取消回灌失败文案段 reason 位**已施工承接；其余未列项一律维持原登记 |
| **R-106 关联注记** | `R-106` 行尾（`:296`，+674 字符） | `m2t2a-smoke.mjs` 断言③+③b 已随批 4④**段态化改写修复**、注入口改 `ai.handleFrame(FRAME)`（本行候选修法）；`SMOKE_SKIP_MODEL=1` **退码 0**（断言②/③/③b 全 PASS）|
| **观察项挂账注记（逐条点名不打包，落 R-140 系）** | `R-140` 行尾同段 | **F6** 键换代代价（`requiresAction` 翻转 ⇒ 组重并 + `expandedBlocks` 键集合换代 ⇒ 用户展开态回默认；测试已锁 = `blocks.spec.ts` F2② 双向留证）；**复核 O-1**（第②档仅扫 tools 成员，语义等价）/ **O-3**（空 text 段不打断组，已由施工 R-3 申报）/ **O-4**（CDP 页内替身，未构造网络级故障）/ **O-5**（探针字段口径）/ **O-6**（wire 自证计数单调增长，不可回证）；**红队 O-1**（不可达防御分支）/ **O-3**（`cancelledStatusTags` 取法特异性，已由施工 R-5 申报）/ **O-4**（施工报告 §1 行数笔误 ⇒ 已由 F7① 订正销记；**附注**：F7① 的 `131 行` 系 F1 前口径，F1 后实读 **134 行** = 锚定 `additions`，供后续批核）/ **O-5**（`expandedBlocks` 键滞留，内存态卫生）；**红队 O-2**（滚动指纹投影注释残留）⇒ 已由 **F8** 注释级订正销记 |

**只追加校验（程序化）**：`git diff -U0` 得 **+3 行 / −2 行**，其中 2 条「删行」经**前缀校验**确认 = 2 处**纯行尾追加**（旧行去掉表体闭合 ` |` 后为新增行**真前缀**，尾部增量 674 / 1734 字符）⇒ **原文本逐字节保留、零内容删除**（与 R-145 整行新增合计 3 行加入）。M2 总行数 500 → **501**。

**4-4 check-docs 门禁**：`node scripts/ci/check-docs.mjs` ⇒ **全部通过 6/6 断言绿**（① 无盘符绝对路径 ② 版本一致 ③ 引用文件存在 ④ 端口表一致 ⑤ 命名纪律禁则〔`git ls-files` **993** 个路径，实扫 **990**，豁免 **3**〕 ⑥ `.java` 探针不参与构建）。

---

## 五、阶段 5 · CI 守夜（匿名轮询 · `curl --ssl-no-revoke` · 间隔 170s · 预算 60 次/h · **实耗 3 轮 / 6 次调用**）【实测】

| run | **`38185540742`**（run_number **`101`**，event `push`，head_sha **`6c9c699`**）= **completed / success** |
|---|---|

| # | job（五 job） | status / conclusion |
|---|---|---|
| 1 | 后端编译与单测 | completed / **success** |
| 2 | 前端类型检查与构建 | completed / **success** |
| 3 | E2E 全流程（29 用例 · 桩上游 + redis） | completed / **success** |
| 4 | 文档一致性门禁（check-docs）｜阻断 | completed / **success** |
| 5 | 全历史脱敏扫描（报告制 · 不计门禁） | completed / **success** |

**轮询取证（`ci-poll-p17.log`，3 轮全文）**：
- round 1（23:46:45Z）：run `in_progress`，jobs(**4**) —— 仅 `check-docs` completed/success，其余 3 job in_progress；**E2E job 尚未出现**
- round 2（23:49:37Z）：run `in_progress`，jobs(**5**) —— **E2E job 本轮才出现**、in_progress；其余 4 job 全 completed/success
- round 3（23:52:30Z）：run **completed / success**，jobs(5) **全 completed/success** ⇒ **STOP：run completed & ≥5 jobs all completed ⇒ allSuccess=true**

⇒ **闭环达成**（run 级 completed 且五 job 全 completed/success）；**未触发**「属本批问题立即上报 K3」分支，**无需热修**。

---

## 六、阶段 6 · 提交后终验【实测】

**6-1 HEAD 态门禁复跑**（工作树已干净，即提交态本体）：

| 门禁 | 结果 |
|---|---|
| `cd frontend && yarn test` | **Test Files 12 passed (12) / Tests 154 passed (154)**（3.26s）|
| `yarn typecheck`（`vue-tsc --noEmit`）| `Done in 3.64s`（exit 0，零诊断）|
| `yarn build` | `✓ built in 26.02s` / `Done in 30.18s` |
| `node scripts/ci/check-docs.mjs` | **全部通过：6/6 断言绿** |

**6-2 从零夹带终验（提交后 blob ↔ 入库前工作树 sha256）** —— 工具 `_verify-fidelity-p17.mjs`，**6/6 EQUAL**：

| 文件 | rev | blob bytes | 提交后 blob sha256（前 16） | 入库前工作树 sha256（前 16） |
|---|---|---|---|---|
| `frontend/src/components/AiPanel/AiPanel.vue` | `HEAD~2` | 41676 | `726c17e168b5185e` | `726c17e168b5185e` |
| `frontend/src/stores/ai.ts` | `HEAD~2` | 100081 | `569de94868321bd9` | `569de94868321bd9` |
| `frontend/tests/ai-panel-parts-render.spec.ts` | `HEAD~2` | 24762 | `cefec273447e7a39` | `cefec273447e7a39` |
| `frontend/src/utils/blocks.ts` | `HEAD~1` | 5769 | `3893b002f30465b1` | `3893b002f30465b1` |
| `frontend/tests/blocks.spec.ts` | `HEAD~1` | 10907 | `83a6ef6c5066ad61` | `83a6ef6c5066ad61` |
| `scripts/m2t2a-smoke.mjs` | `HEAD~1` | 28862 | `07ed083d6279f093` | `07ed083d6279f093` |

**6-3 收尾**：`git status --porcelain` **空**（0 项）；`git rev-parse HEAD` = `git rev-parse origin/main` = `6c9c699aa680297ecf0a83ec39eeae8716b68431`（ahead/behind = 0/0）。

---

## 七、未发生项 / 异常申报（如实留痕）

1. **未触发任何逃生口**：三笔提交 pre-commit 均**零命中通过**；**未用** `--no-verify`；**未用** `AI_SECRETS_ALLOW=1`（R-129 族误报在本批**未发生**）。
2. **未做子智能体下沉**（见 §零 末条）：本棒工具面无派生能力 ⇒ 机械活全部内联脚本化执行、全留证。
3. **卡面与实况的 1 处计数差**：阶段四「入库 9 份」vs 枚举 8 份 —— 本棒**按枚举入库 8 份**并在此申报（可追加办理，见 §四-1 末条）。
4. **R-106 行尾注记的 1 处措辞订正说明**：本棒注记明确「原『修复前该脚本任何红/绿不得作为其他主张依据』的限制随本次修复解除」，同时**保留**「脚本仍未接 CI（`R-91` 盲区登记维持）」的下游约束 —— **未作「已彻底闭合」表述**。
5. **红队 O-4 附注（新增事实）**：`blocks.ts` 现态 **134 行**（锚定 `additions=134` 一致），而入库施工报告 §1（经 F7① 订正）写 **131 行** —— 原因是 **F7① 的订正值取 F1 命名空间改造之前口径，而 F1 改动使该文件 +3 行**。已在 M2 `R-140` 行尾以「附注」点名（**未另开行、未改入库文书正文**）；**供货位/后续批核**，本棒不擅自改已入库证据件。
6. **锚点/行号漂移**：无。R-145 精确落在卡面预期区（`:334`，且先实读确认 R-144 在 `:333`）；`check-docs` 路径数随入库 8 件增加而增长（豁免仍 3，禁则 0 命中）。
7. **本报告组织名 0 字面**：全文以 `<REPO>` / `<REPO-URL>` / `<REPO-OWNER>` / run id / run_number 取证。

---

## 八、本批产出物与取证件清单

| 类别 | 内容 |
|---|---|
| 入库件（三笔提交 / **19 文件**） | `437999e`（前端 **3**，+509 −127）+ `0a52f98`（前端+脚本 **3**，+379 −18）+ `6c9c699`（docs **9**：`docs/M2-排期计划.md` + 8 份 `补档-CHATTIMELINE-批4④-*`，+2103 −2） |
| 基线链 | `296ffe2` → **`437999e`** → **`0a52f98`** → **`6c9c699`**（= `origin/main`） |
| 锚定取证（仓外，本棒只读消费） | `<SCRATCH>/chat-timeline-pkg/evidence-p17/anchor-b4-fix.json`、`diff-批4④-fix.sorted.patch`（入库锚）、`diff-批4④-full.sorted.patch`（审查锚）、`锚点实读表.md` |
| 机械验收 / 门禁输出 | 同目录 `desense-files.txt`、`desense-added.txt`、`desense-evidence.txt`、`desense-evidence-src-scan.txt`、`desense-msg.txt`、`_added-fe.txt`、`_added-m2.txt`、`evidence-hashes.txt`、`make-evidence-p17.txt`、`m2-verify-p17.txt`、`fidelity-p17.txt` |
| 提交 / push 取证 | 同目录 `msg-p17-1.txt`、`msg-p17-2.txt`、`msg-p17-3.txt`、`commit-1.txt`、`commit-2.txt`、`commit-3.txt`、`push.txt`（`push-masked.txt`，owner 段已屏蔽） |
| CI 取证 | 同目录 `ci-poll-p17.mjs`、`ci-poll-p17.log`、`ci-run-p17.json`、`ci-jobs-p17.json`（字段白名单派生：run id / run_number / status / conclusion / head_sha / job 名与结论；0 组织名 / URL / 盘符） |
| 工程工具（仓外） | 同目录 `_desense-p17.mjs`、`_ingest-evidence-p17.mjs`、`_verify-m2-p17.mjs`、`_verify-fidelity-p17.mjs` |

---

**签字**：提交官（第 17 期；**本棒 id = `agent-549`，运行时可自证实际模型 = `DeepSeek/deepseek-flash`（wire 实证 77/77，见 §零）**），2026-10-12。**授权来源 = K3 指挥官派单 · 提交官第 17 期 · 批4④ 入库收口（本卡）**。本报告所列证据均可于 `<SCRATCH>/chat-timeline-pkg/evidence-p17/` 与仓库三笔提交（`437999e` / `0a52f98` / `6c9c699`）处复核；CI 一律以 run id `38185540742` / run_number `101` 取证，**全文 0 组织名字面**。

---

# ai-example 合流项目 · 提交官报告（第 18 期：轮询治理一期「条件轮询 + visibilitychange 纪律 + SSE 事件触发刷新」全量入库 —— **两笔细分提交**（feat + docs，27 文件）+ 8 份补档入库 + M2 行尾注记 R-88/R-89 + 落行 **R-146** + ADR 登记 **DC-22**；**CI 五 job 全绿 ⇒ 闭环**）

- 本期执行者：**提交官**（Kimi Code 子智能体，本棒 id = **`agent-584`**）——**实测实际运行模型 = `DeepSeek/deepseek-flash`**（**DC-19：读本棒自身 `agents/agent-584/wire.jsonl` 的 `llm.request.modelAlias` 实证，非配置推定**，见 §六）
- 授权来源：**K3 指挥官派单 · 提交官第 18 期 · 轮询治理一期入库（本卡）**；上游依据 = 《决策卡-轮询一期.md》+《施工卡-定稿.md》+《收口修卡-轮询一期.md》
- 仓库：`<REPO>`（分支 `main`，远端 `<REPO-URL>`）；**本段全文 0 组织名字面**，CI 一律以 **run id / run_number / job id** 取证
- 起点 HEAD = **`6c9c699`**（= `origin/main`，第 17 期提交批）｜本批两笔提交：**`93230ad`**（`feat(frontend)`，16 文件）+ **`45b2c6a`**（`docs`，11 文件）｜push 后 HEAD = `origin/main` = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`（ahead 0 / behind 0）
- 取证件目录（仓外）：`<SCRATCH>/polling-gov/evidence-p18/`
- 平台：Windows + Git Bash｜`core.hooksPath=scripts/githooks`（pre-commit 阻断式）｜`core.autocrlf=true`｜yarn `1.22.22`

## 0. 结论速览

| 阶段 | 结果 |
|---|---|
| 1 机械验收 | ✅ `git status --porcelain` 实读**恰 16 行**（M/MM/A/AM 混合，**全在 `frontend/`**、非 frontend 行 0）；HEAD = `6c9c699` = `origin/main`；**零夹带核验**：重跑生成器实得 patch sha256 = `320b3c09d51edafd382dea0ccd1dd96b61506c32c29b8d619959736083b541c7`、**147461 B**、`cmp` **BYTE-IDENTICAL**；门禁：vitest **207/207 · 19 spec** / typecheck 绿 / build 绿 / `check-docs` **6/6** / `-- backend/` **空** |
| 2 脱敏终扫 + 8 份补档入库 | ✅ 8 份入库 `<REPO>/docs/evidence/`（命名 `补档-POLLGOV-轮询一期-*`）；**硬命中 0**（盘符 / 用户名 / 主机名 / 内网 IP / 密钥 / 命名纪律禁则，staged 与入库原位两轮全扫）；**逐行同构保真校验 8/8 PASS**（行数守恒、正文零改写，仅替换点变化） |
| 3 台账与 ADR | ✅ M2 **R-88（`:280`）/ R-89（`:281`）行尾注记**「已施工（轮询治理一期，第 18 期）」（旧文本逐字节保留、追加式）+ **R-146 落行 `:335`**（紧接 R-145 之后、表体闭合空行之前）+ **DC-22 登记**（末号 `DC-21` → `DC-22`）+ DC-22 追加注记 + `docs/README.md` 补档计数 drift 注记；`check-docs` **6/6 绿** |
| 4 细分提交 + push | ✅ 两笔**逐文件显式 `git add`**（未用 `-A` / `.`）；暂存清单实读**逐笔恰 16 / 11**；提交后 `git status --porcelain` **空**；**pre-commit 两笔全通过**（未用 `--no-verify`、未触发 `AI_SECRETS_ALLOW=1`）；push `6c9c699..45b2c6a` |
| 5 CI 守夜 | ✅ **全绿**：run **`38196730825`**（run_number **`102`**，head_sha = `45b2c6a`）= **completed / success**，**五 job 全 completed 且全 success**（E2E job 上游 job 完成前不出现，未误判）；匿名轮询 **5 次 API 调用**（60/h 预算内） |
| 6 提交后终验 | ✅ **从零夹带终验：27/27 EQUAL**（16 代码文件 + 3 docs + 8 入库文档，工作树 ↔ 提交后 HEAD blob 逐文件 sha256 一致）；HEAD = `origin/main` = `45b2c6a`；工作树干净 |
| 双 SHA 纪律 | **审查锚（本期复核 / 红队据此出报告）= `e97df56ef3d60f723f61918effb86d5e0cbb923504a35ac20aa120842782f016`**（131552 B / 16 段 / +2823 −93）；**入库锚（本批合流提交字节据此）= `320b3c09d51edafd382dea0ccd1dd96b61506c32c29b8d619959736083b541c7`**（147461 B / 16 段 / +3116 −92）。**二者不得混用**：本报告全部入库验收数字对**入库锚**，本期复核 / 红队结论链对**审查锚**（当期读数 16 文件 / 19 spec / 197 tests 属该时点口径） |

## 一、阶段一 · 机械验收与零夹带核验

### 1-1 工作树状态（全量照贴，`git status --porcelain`【实测】）

```
M  frontend/src/api/jobs.ts
MM frontend/src/api/tasks.ts
M  frontend/src/components/AiPanel/AiPanel.vue
MM frontend/src/stores/task.ts
MM frontend/src/utils/job-watch.ts
AM frontend/src/utils/polling.ts
AM frontend/src/utils/task-events.ts
MM frontend/src/views/ImportWizardView.vue
MM frontend/src/views/TaskCenterView.vue
AM frontend/tests/import-watch-recycle.spec.ts
AM frontend/tests/job-watch-channel.spec.ts
AM frontend/tests/polling-controller.spec.ts
A  frontend/tests/polling-predicate.spec.ts
AM frontend/tests/task-events-lifecycle.spec.ts
AM frontend/tests/task-events.spec.ts
AM frontend/tests/wait-job-final.spec.ts
```

行数 **16**；`grep -vc '^.. frontend/'` = **0**；`git ls-files --others --exclude-standard` = **空**（0 未跟踪件，故无需 `--intent-to-add`）。

### 1-2 零夹带核验（重跑生成器，**不等即停手**）

工具 = `<SCRATCH>/polling-gov/_anchor-polling-p1-fix.mjs`（只读 `git diff` / `git archive`，零仓库写），重跑输出目录 = `<SCRATCH>/polling-gov/evidence-p18/_reanchor-check/`（连同锚定记录副本，**未改写仓外基准 `anchor-轮询一期.json`**）：

| 项 | 基准（入库锚） | 重跑实得 | 判定 |
|---|---|---|---|
| patch sha256 | `320b3c09d51edafd382dea0ccd1dd96b61506c32c29b8d619959736083b541c7` | **同值** | ✅ |
| patch 字节数 | 147461 | **147461** | ✅ |
| 段数 / 增删 | 16 段 / +3116 −92 | **16 段 / +3116 −92** | ✅ |
| `cmp` 全量 | — | **BYTE-IDENTICAL** | ✅ |

生成器自带两项保真校验亦全绿：`applyCheckOk = true`（`git apply --check` 到 `git archive 6c9c699` 的 HEAD 树）；`reconstructedAllMatch = true`（9 个新增文件段逐字节重建 ↔ 工作树 LF 规范化后 sha256 全 MATCH，其中 `frontend/src/utils/task-events.ts` 工作树为 CRLF、规范化后 MATCH）。**⇒ 零夹带成立，未触发停手分支。**

### 1-3 门禁复跑（真跑留证）

| 门禁 | 结果 |
|---|---|
| `cd frontend && yarn test` | **Test Files 19 passed (19) / Tests 207 passed (207)**（5.48s）|
| `npx vitest run tests/restore-reconcile.spec.ts`（隔离复跑佐证） | **1 passed (1) / Tests 3 passed (3)** —— 负例①（取数期间换会话）**2667ms / 2583ms 通过、未超时**，**未发生**已知高负载抖动 |
| `yarn typecheck`（`vue-tsc --noEmit`） | `Done in 3.70s`（exit 0，零诊断）|
| `yarn build` | `✓ built in 25.57s` / `Done in 29.78s`（exit 0）|
| `node scripts/ci/check-docs.mjs` | **全部通过：6/6 断言绿**（断言⑤ 命名纪律按 `git ls-files` 实扫 1009 个文本，豁免 3）|
| `git status --porcelain -- backend/` | **空**（0 行）|

**证据指针**：`<SCRATCH>/polling-gov/evidence-p18/gates-p18.txt`（70079 B，含 [0] 零夹带 … [7] 门禁后状态全段原文、【实测】）。

## 二、阶段二 · 脱敏终扫与 8 份证据入库

### 2-1 入库清单（源 → 落点，均 `<REPO>/docs/evidence/`）

| 源件（仓外 `<SCRATCH>/polling-gov/`） | 源 sha256（全量） | 入库件 | 字节 | 入库 sha256（前 16） |
|---|---|---|---|---|
| `决策卡-轮询一期.md` | `fa06045e…e1b298` | `补档-POLLGOV-轮询一期-决策卡.md` | 52175 | `45ddc5a1d1ac2f86` |
| `施工卡-定稿.md` | `bba860e6…cfd1fa` | `补档-POLLGOV-轮询一期-施工卡-定稿.md` | 92867 | `ed2b2ef41940a502` |
| `施工报告-轮询一期.md` | `3f972434…1abd27` | `补档-POLLGOV-轮询一期-施工报告.md` | 18176 | `e442caeaf9293a84` |
| `复核报告-轮询一期.md` | `74877946…697073` | `补档-POLLGOV-轮询一期-复核报告-GLM-5.3.md` | 6837 | `e366f0986ed7a271` |
| `红队报告-轮询一期.md` | `aeaa4531…72d753` | `补档-POLLGOV-轮询一期-红队报告-DS-V4-Pro.md` | 6259 | `fa91cd39f185def0` |
| `收口修报告-轮询一期.md` | `4fd29972…45e92b` | `补档-POLLGOV-轮询一期-收口修报告.md` | 8417 | `68e76be6266d8033` |
| `会审-轮询一期-GLM.md` | `e1c71204…f2b978` | `补档-POLLGOV-轮询一期-会审报告-GLM-5.3.md` | 30453 | `a740cea723e1250c` |
| `会审-轮询一期-DSPro.md` | （见 manifest） | `补档-POLLGOV-轮询一期-会审报告-DS-V4-Pro.md` | 27158 | `9f909ebb053dc7ee` |

**任务卡类不入库**（照先例）：施工卡草案（62476 B）/ 会审卡-GLM（3754 B）/ 会审卡-DSPro（4083 B）/ 复核卡-GLM / 红队卡-DSPro / 收口修卡——**均未入库、未入仓**。

### 2-2 脱敏规则与结论

替换映射（顺序即优先级，长前缀优先；**内网 IP → `<LAN_IP>` 与 AI key → `***` 两规则本轮源件 0 处、未触发**；GitHub 公开组织名与 `127.0.0.1` 本轮源件亦 0 处）：

| 命中形态 | 替换为 | 本轮命中次数（8 份合计） |
|---|---|---|
| `<LOCAL_WORK>\ai-example-code\<主仓目录名>`（含 `/` 变体） | `<MAIN_REPO>` | 4 |
| `<LOCAL_WORK>\ai-example-code\ai-example-main` / `<LOCAL_WORK>\ai-example` | `<REPO_ROOT>` | 13 |
| `<LOCAL_WORK>\polling-gov` | `<SCRATCH>` | 19 |
| `<DRIVE>/Users\<用户名>\.kimi-code` | `<KIMI_HOME>` | 6 |
| `<DRIVE>/Users\<用户名>`（残余） | `<USER>` | 1 |

**结论 = 硬命中 0**：对 staged 与入库原位两轮全扫，**盘符路径 / 用户名 / 主机名（本机 hostname，值不落盘）/ 内网 IP / 任意 IPv4 / OpenAI 风格密钥 / GitHub token / AWS AKIA / Bearer 明文 / 命名纪律禁则** 十类**逐文件逐类 0 命中**；**保真校验 8/8 PASS**（staged 正文行数 = 源行数，逐行同构，经替换改变行数 0~10/件）。
**证据指针**：`<SCRATCH>/polling-gov/evidence-p18/sanitize-scan.txt`（含 manifest、映射表、两轮全文扫描、保真校验四段原文）、`_sanitize-p18.mjs` / `_sanitize-scan-p18.mjs` / `_sanitize-fidelity-p18.mjs`。

## 三、阶段三 · 台账与 DC-22 呈批入库（docs 面）

### 3-1 `docs/M2-排期计划.md`（只追加 + 内容零删 + 编辑前重新实读 + 行尾注记以整行重写为载体）

| 动作 | 行号 | 载体 | 校验 |
|---|---|---|---|
| R-88 行尾注记 | `:280` | 整行重写，锚点取该行唯一尾串，**前缀逐字节保留**后追加「已施工（轮询治理一期，第 18 期）…」 | 唯一性 `grep -c` = 1 |
| R-89 行尾注记 | `:281` | 同上 | 唯一性 `grep -c` = 1 |
| **R-146 落行**（表体追加） | **`:335`** | 紧接既有末行 R-145（`:334`）之后、表体闭合空行之前；编辑前实读现态最大号 = `R-145`【实测】 | 追加后行数 501 → **502**（净 +1 行）|

R-146「轮询治理一期观察项包」逐项 = ①红队 **m3**（`load()` 失败下 tick 自愈不 resync）/ ②红队 **m4**（300s 边界精度，`>` 判定晚 ~`intervalMs`+RTT）/ ③红队 **m5**（`attachJob`+`beginWatchJob` 双开即闭）/ ④GLM **m-1 / m-2**（运行时 DevTools 判据挂账）/ ⑤**两方 O 组**（`publishConflict` 与 `TaskEventTarget.jobId` 死字段 / `isPaused` 零消费 / AiPanel HITL hidden 冻结未实测）/ ⑥**格式面**（`task-events.spec.ts` 两处行粘连 `:660` / `:767`）/ ⑦**E2E 桩基数口径**（`PASS=29` 实测；`ci.yml` 与 M2 `:347` 记 29、批 3 时期记 28 —— **基数随用例演进不写死**）。末列 = **已登记·待排期**。

### 3-2 `docs/adr/DECISION-REGISTER.md`

**DC-22**（提示词成本治理，**日期 2026-10-12，状态 = 生效（用户直裁）**）落行接 `DC-21` 之后、表体闭合之前（既有行零改动）；要点 = **PC-1 直发改引用**（短派发头 + sha256 核验，禁粘贴大载荷）/ **PC-2 卡文上限**（施工 15KB、审查 6KB、收口修 4KB）/ **PC-3 报告 ≤8KB** / **PC-4 最小轮次** / **PC-5 派发提示词模板化** / **PC-6 质量门不动**；同批追加「DC-22 追加注记」（末号 `DC-21` → `DC-22`；**指明 DC-22 只裁成本面、不裁环节**）。源件 = 仓外 `<SCRATCH>/governance/提示词成本治理-DC22候选.md`，已**改写为登记格式**。

### 3-3 `docs/README.md`

补档计数 **drift 注记**（只追加）：原「`docs/evidence/补档-*.md` 共 24 个文件 = 23 份补档 + 本索引」为该句**成文时点（2026-10-11）的历史留痕、不回改**；**入库后实测 61 个 = 60 份补档 + 1 索引**（已含本批 8 份）。

## 四、阶段四 · 细分提交

| 笔 | commit | 类型 | 文件数 | 增删 | 暂存方式 |
|---|---|---|---|---|---|
| 1 | **`93230ad`** | `feat(frontend)`：轮询治理一期三步 + 收口修 F1~F13 | **16** | **+3116 −92** | 16 文件**逐文件显式 `git add`** |
| 2 | **`45b2c6a`** | `docs(m2,evidence,adr)`：8 份补档入库 + R-88/R-89 注记 + R-146 落行 + DC-22 登记 | **11** | +1785 −3 | 11 文件**逐文件显式 `git add`** |

- 暂存清单**逐笔实读核对**：笔 1 恰 16（`git diff --cached --name-only | wc -l` = 16，`--stat` 尾行 = `16 files changed, 3116 insertions(+), 92 deletions(-)`，**与入库锚逐项吻合**）；笔 2 恰 11；**提交前 `git status --porcelain` 逐一归属，无杂件混入**（笔 1 后剩余 = 3 M + 8 ??，笔 2 前 = 0）。
- **`pre-commit` 钩子：两笔均 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`** —— **未用 `--no-verify`**、**未触发 `AI_SECRETS_ALLOW=1` 逃生口**（本批**零误报**）。
- 提交信息均可核（`<SCRATCH>/polling-gov/evidence-p18/commit-{1,2}-msg.txt`，含双 SHA 口径注记与门禁数字）。

## 五、阶段五 · push 与 CI 守夜

`git push origin main` → `6c9c699..45b2c6a  main -> main`（exit 0）；`git rev-parse HEAD` = `git rev-parse origin/main` = **`45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`**。

| # | job（五 job） | status / conclusion |
|---|---|---|
| 1 | 前端类型检查与构建 | completed / **success** |
| 2 | 后端编译与单测 | completed / **success** |
| 3 | 文档一致性门禁（check-docs）｜阻断 | completed / **success** |
| 4 | 全历史脱敏扫描（报告制 · 不计门禁） | completed / **success** |
| 5 | E2E 全流程（29 用例 · 桩上游 + redis） | completed / **success** |

- run **`38196730825`** / run_number **`102`** / event `push` / head_sha `45b2c6a` ⇒ **completed / success**；`/jobs` 取回 **total_count = 5** 且五 job 全 `completed` + 全 `success`。
- **轮询取证**（匿名 `curl --ssl-no-revoke`，本批共 **5 次** API 调用，60/h 预算内）：round 1（02:40:59Z）`in_progress`；round 2（02:41:59Z）`in_progress`（**此时 `/jobs` 未单独取，`runs` 端点仅给 run 级态**）；round 3（02:44:16Z，+60s 采样）⇒ **run `completed / success`**；随后 `/jobs` 一次取回五 job 全绿。**E2E job 在上游 job 完成前不出现**的已知形态本批**未造成误判**（未按 4 job 判早收口）。
- **闭环判据达成**（run 级 completed ∧ 五 job 全 success）⇒ **未触发「属本批问题立即停手上报 K3」分支，无需热修**。

## 六、阶段六 · 提交后终验与自证

### 6-1 从零夹带终验（27/27 EQUAL）

工具 = `<SCRATCH>/polling-gov/evidence-p18/pre-push-hashes.json`（push **前**工作树快照）↔ push **后** `git show HEAD:<path>` blob，逐文件 sha256（LF 规范化口径，与仓库 `core.autocrlf=true` 一致）：

| 组 | 文件数 | EQUAL | DIFF |
|---|---|---|---|
| 16 代码文件（`frontend/`） | 16 | **16** | 0 |
| 3 docs（M2 / README / DECISION-REGISTER） | 3 | **3** | 0 |
| 8 入库文档（`docs/evidence/补档-POLLGOV-*`） | 8 | **8** | 0 |
| **合计** | **27** | **27** | **0** |

`HEAD` = `origin/main` = `prePushHead` = `45b2c6a` ⇒ **RESULT: PASS 零夹带终验**。证据 = `<SCRATCH>/polling-gov/evidence-p18/final-verify-p18.txt`。

### 6-2 DC-19 署名自证（wire 实证，非配置推定）

| 行 | 项 | 实证 |
|---|---|---|
| 1 | 实际模型（本棒） | **`DeepSeek/deepseek-flash`** —— `agents/agent-584/wire.jsonl` 的 `llm.request.modelAlias` **99/99 全为 `DeepSeek/deepseek-flash`，无混杂**（`model` = `deepseek-flash` × 99；`provider` = `openai` × 99） |
| 2 | agent id | **`agent-584`**（主仓库合流会话） |
| 3 | wire 文件 | `agents/agent-584/wire.jsonl`：**973094 B / 859 行**，sha256 = `bf4e2b150a39a7e0d66d3cfe5690d063ca03d74fb1d7ddc45dca306060a41460`（读数为报告落稿时点；本棒仍在运行 ⇒ 计数与字节只增不减，上列值为该时点实测 / 后续读数的下界） |
| 4 | 取证命令口径 | 逐行 JSON 解析 `type == "llm.request"` 计 99 条、聚合 `modelAlias` 键——**非 `grep` 字面计数、非配置推定** |

### 6-3 成本治理执行行（DC-22 / PC-1~PC-5 首次适用留痕）

| 件 | 字节数 | PC 上限 | 判定 |
|---|---|---|---|
| 派发头（本棒 3 次分发：主派发 / 复核派发 / 红队派发） | **460 / 462 / 782 B** | ≤2KB（PC-1） | ✅ 全部远低于上限 |
| `复核卡-轮询一期-GLM.md` | **2073 B** | ≤6KB（PC-2） | ✅ |
| `红队卡-轮询一期-DSPro.md` | **2239 B** | ≤6KB（PC-2） | ✅ |
| `收口修卡-轮询一期.md` | **4005 B** | ≤4KB（PC-2） | ✅（4005 ≤ 4096） |
| 本报告 | 见落盘 | ≤8KB（PC-3） | ⚠ 本报告段**未达 PC-3**（提交报告承载阶段留证与逐项取证，已远超 8KB；**如实申报**，供 K3 决定是否对提交报告单列上限） |

**对照上批（第 17 期）= 施工卡全文粘贴 92KB（92312 B）进会话、每轮重扛上下文**：本期按 **PC-1 直发改引用**改走「短派发头 + 卡文件路径 + sha256 核验」，**卡文本体不再进派发消息**（派发头 460~782 B，为上一期单次粘贴量的 **0.5%~0.85%**）；**主循环轮次**本棒实读 **99 条 `llm.request`**（含门禁复跑与证据取数的机械轮），机械读取面（重跑生成器 / 扫描 / 门禁 / 比对）**以仓外一次性脚本承载、不占会话轮次**。

## 七、未发生项 / 异常申报（如实留痕）

1. **未触发任何逃生口**：两笔提交 pre-commit 均**零命中通过**；**未用** `--no-verify`；**未用** `AI_SECRETS_ALLOW=1`。
2. **R-146⑥ 格式面未能定位（如实登记，未掩盖）**：卡面所指 `frontend/tests/task-events.spec.ts` 两处行粘连（`:660` / `:767`）在本棒可见的全部产物中**无法定位** —— 该文件入库态实为 **410 行**（收口修前 blob 361 行），`:660` / `:767` **越界**；全仓 `grep 粘连` **零命中**，`<SCRATCH>/polling-gov/` 全语料亦无 `660` / `767` 相关登记。故 R-146⑥ **按原口径登记 + 附「提交官实读未能定位」注记**，**不得据此判为「已修」或「无此事」**，留待后续批核定位。
3. **口径纠偏备案（两处，均未擅改证据件）**：① `docs/M2-排期计划.md` 的「R-88 行尾注记」按本表只追加纪律落笔，**未回改**施工报告 / 复核报告内的当期读数（16 文件 / 19 spec / 197 tests 属**审查锚时点**口径，入库存档时原样保留）；② `docs/README.md` 的补档计数注记**只记现态实测值与 drift 说明**，**不回改**成文时点的「24 个文件」。
4. **仓库外文件零改动**：8 份源件、6 份任务卡、仓外基准 `anchor-轮询一期.json` 与 `diff-轮询一期-fix.sorted.patch` **一字未改**（重跑输出落在独立 `_reanchor-check/` 目录，含独立副本）。
5. **红线零触碰**：禁触的两条线仓库与本仓 `backend/` 全程零操作（`git status --porcelain -- backend/` 空）；**未读任何 `.env`**；除本卡授权的 git 操作（`add` / `commit` / `push`）与授权证据落盘外**无任何顺手改**。
6. **锚点 / 行号漂移**：无。R-146 精确落在 `:335`（先实读确认 R-145 在 `:334`）；M2 行数 501 → 502，净 +1 行 = 恰为 R-146 一行。

## 八、本批产出物与取证件清单

| 类别 | 内容 |
|---|---|
| 入库件（两笔提交 / **27 文件**） | `93230ad`（`frontend/` **16**：9 源 + 7 spec，+3116 −92）+ `45b2c6a`（docs **11**：`M2-排期计划.md` / `README.md` / `adr/DECISION-REGISTER.md` + 8 份 `补档-POLLGOV-轮询一期-*`，+1785 −3） |
| 基线链 | `6c9c699`（第 17 期）→ **`93230ad`** → **`45b2c6a`**（= `origin/main`）|
| 锚定取证（仓外，本棒只读消费） | `<SCRATCH>/polling-gov/diff-轮询一期-fix.sorted.patch`（入库锚）、`diff-轮询一期.sorted.patch`（审查锚）、`anchor-轮询一期.json`、`_anchor-polling-p1-fix.mjs` |
| 机械验收 / 门禁输出 | `<SCRATCH>/polling-gov/evidence-p18/`：`gates-p18.txt`（70079 B，九段原文）、`_reanchor-check/`（零夹带重跑产物 + 锚定记录副本） |
| 脱敏 / 入库 | 同目录：`sanitize-scan.txt`、`_scan-src.py`、`_sanitize-p18.mjs`、`_sanitize-scan-p18.mjs`、`_sanitize-fidelity-p18.mjs`、`staged/`（8 份 + `_sanitize-manifest.json`） |
| 提交 / push 取证 | 同目录：`commit-1-msg.txt`、`commit-2-msg.txt`、`pre-push-hashes.json`、`final-verify-p18.txt` |
| CI 取证 | 本报告 §五 表（run id `38196730825` / run_number `102` / 五 job 名与结论），匿名 API 直读，**0 组织名 / 0 URL 字面** |

---

**签字**：提交官（第 18 期；**本棒 id = `agent-584`，运行时自证实际模型 = `DeepSeek/deepseek-flash`（wire 实证 `llm.request.modelAlias` 99/99 无混杂，见 §6-2）**），2026-10-12。**授权来源 = K3 指挥官派单 · 提交官第 18 期 · 轮询治理一期入库（本卡）**。本报告所列证据均可于 `<SCRATCH>/polling-gov/evidence-p18/` 与仓库两笔提交（`93230ad` / `45b2c6a`）处复核；CI 一律以 run id `38196730825` / run_number `102` 取证，**全文 0 组织名字面**。
---
# ai-example 合流项目 · 提交官报告（第 19 期：手动刷新批全量入库 —— **三笔细分提交**（feat backend + feat frontend + docs）+ 17 份卡/报告补档入库 + M2 落行 **R-147 / R-148 / R-149** + technical-design 同步 S5 摘除面）

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 卡核验 | ✅ 卡 **10901 B** / sha256 `65536d09…b85c5c` = 派发头给定值，未触发停手 |
| 1 机械验收 / 零夹带 | ✅ 工作树**恰 25 条**（18 M / 5 D / 2 ??）；HEAD = `origin/main` = **`45b2c6a`**（ahead 0、暂存区空）；**重锚 `cmp` 逐字节 EQUAL**（`336f03f4…` / 177732 B / **25 段** / +986 −1883） |
| 2 门禁复跑 | ✅ 五门禁全绿：vitest **18 spec / 202 passed** · typecheck exit 0 · build exit 0 · check-docs **6/6** · 后端 **442 / 0 / 0 / 0** + BUILD SUCCESS（XML 70） |
| 3 脱敏 + 入库 | ✅ **17 份**入 `docs/evidence/`（手动刷新批 10 / R-149 4 / R-137 3）；**硬命中 0**、**保真 17/17 PASS**；源件零改动 |
| 4 台账 | ✅ M2 **只追加 3 行**：R-147 `:336` / R-148 `:337` / R-149 `:338`（紧接 R-146 `:335`）；check-docs 复跑 6/6；**ADR 无变更** |
| 5 三笔 + push | ✅ `ee6d646` → `0a60e78` → `0f46286`；**pre-commit 三笔全绿零命中**；push `45b2c6a..0f46286`（无 force） |
| 6 终验 | ✅ **38/38 EQUAL** + 5 删除件 **5/5 不在 HEAD**；HEAD = `origin/main` = `prePushHead` = `0f46286`；工作树 0 条 |
| 7 CI 守夜 | ✅ run **`38207392396`**（`0f46286`）**completed / success**，**五 job 全 completed 全 success**；轮询 **3 轮 / 6 次 API 调用** |
| 双 SHA（**勿混用**） | **审查锚** `a2d9d708…28fa7`（**175069 B / 23 段**）；**入库锚** `336f03f4…c4a3`（**177732 B / 25 段**）；全值见 §一-2 |

## 一、阶段一 · 机械验收与零夹带核验

### 1-1 工作树状态（全量照贴，`git status --porcelain`【实测】）

```
 D backend/src/main/java/com/example/configmgr/task/service/TaskChangedEvent.java
 M backend/src/main/java/com/example/configmgr/task/service/TaskService.java
 M backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java
 M backend/src/test/java/com/example/configmgr/task/service/TaskSseServiceTest.java
 M docs/technical-design.md
 M frontend/src/api/jobs.ts
 M frontend/src/api/tasks.ts
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/task.ts
 M frontend/src/stores/workspace.ts
 M frontend/src/types/api.ts
 M frontend/src/types/sse.ts
 M frontend/src/utils/job-watch.ts
 M frontend/src/utils/polling.ts
 D frontend/src/utils/task-events.ts
 M frontend/src/views/TaskCenterView.vue
 M frontend/tests/job-watch-channel.spec.ts
 M frontend/tests/polling-controller.spec.ts
 M frontend/tests/polling-predicate.spec.ts
 D frontend/tests/task-events-lifecycle.spec.ts
 D frontend/tests/task-events.spec.ts
 D frontend/tests/wait-job-final.spec.ts
 M scripts/verify-e2e.ps1
?? frontend/tests/task-center-view.spec.ts
?? frontend/tests/watch-publish-outcome.spec.ts
```

**25 条** = 18 ` M` + 5 ` D` + 2 `??`，与卡面口径逐条一致、**无卡外夹带**；暂存区 0 条。

### 1-2 零夹带对重锚（`cmp` 逐字节 EQUAL）

入库锚 = `<SCRATCH>\diff-手动刷新批-重锚.sorted.patch`（sha256 **`336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`** / 177732 B / 25 段；卡面值复核相符）。重生成按卡：2 未跟踪件**逐个** `git add -N`（**未用** `add -N -A`）→ `git diff` → 按 `sort-patch-reanchor.mjs` 同口径（段序 = 路径字典序）重排 → `git reset -q` 还原。实得 **同值 / 177732 B / 25 段 / +986 −1883 / `cmp` 逐字节 EQUAL** ⇒ **零夹带成立**。

### 1-3 门禁复跑（提交前对工作树真跑）

vitest `yarn test` ⇒ **18 spec / 202 passed**（5.71s，exit 0）；`yarn typecheck` ⇒ exit 0；`yarn build` ⇒ `✓ built in 28.57s`（exit 0，**首跑即过**、esbuild Temp 已知坑未触发 ⇒ 重试 0 次）；`node scripts/ci/check-docs.mjs` ⇒ **6/6**（`git ls-files` **1034** / 实扫 1031 / 豁免 3，exit 0）；后端 `bash <SCRATCH>\redteam-batch-a\.run-fix\mvn-run.sh -B clean test` ⇒ **442 / 0 / 0 / 0** + **BUILD SUCCESS** + XML **70**。

## 二、阶段二 · 脱敏终扫与 evidence 入库（17 份）

**组别判定**（先 Read 每份开头确认**评审对象**再命名；入库前缀 = `补档-MANUALREFRESH-手动刷新批-` / `补档-R149-issue7-` / `补档-R137-`，均落 `docs/evidence/`）：

| 组 | 份数 | 入库件（前缀之后） |
|---|---|---|
| 手动刷新批（MANUALREFRESH） | **10** | 决策卡 / 施工卡-定稿 / 施工报告 / 复核报告-GLM-5.3 / 红队报告-DS-V4-Pro / 收口修报告 / 会审报告-{归零轮,终版}-{GLM-5.3,DS-V4-Pro} |
| R-149（issue#7） | **4** | 决策卡 / 施工卡 / 会审报告-{GLM-5.3,DS-V4-Pro} |
| R-137 | **3** | 决策卡-定稿 / 会审报告-{GLM-5.3,DS-V4-Pro} |

六份会审件的组别：`会审-GLM/DSPro` 自陈对象 = `会审卡-R137.md`（`8ef374a6…`）⇒ **R-137 组**（卡 §4-7 预设分支命中）；`会审-归零-*` 对象 = `会审卡-轮询归零.md`（Z1~Z6 前序轮次）⇒ 手动刷新批组**归零轮**；`会审-终版-*` 对象 = `决策卡-手动刷新批-定稿.md`（`0b0f864e…`）⇒ **终版**。合计 **17 份**（与卡 §6-3 subject 自洽）；派单卡类 6 份照先例**不入库**。

**格式 / 脱敏 / 保真**：每份照 `45b2c6a` 先例 = 首行 `<!-- 第 19 期补档入库 · <批次> · 已脱敏 -->` + 引用块横幅（补档说明 = 源件 `<SCRATCH>\r137-guard\<原名>`，**全量字节 + 全量 sha256**；占位口径行；口径注记行 = 本期双锚勿混用）。替换：盘符路径 → `<MAIN_REPO>` / `<REPO_ROOT>` / `<SCRATCH>`；家目录 / 用户名 → `<USER>`；`<HOST>` / `<LAN_IP>`（**源件 0 处**）；AI key → `***`（**0 处**）；**组织名 0 字面**（一律 `<REPO-URL>`；裸目录名 `…-<OLD_REPO_DIR>` 一并 `<MAIN_REPO>` 化 —— 该字面含命名纪律禁则组合，留存会直接打红 `check-docs` 断言⑤）。**硬命中 0**：十类扫描器实盘 17 文件 **0 处** + 仓库自建 `desense-scan.mjs`（含命名纪律三态）**0 处**；规则自检 **17/17 + 11/11 不误伤** / **23/23 + 12/12**。**保真 17/17 PASS**：去横幅后逐处占位**回溯展开**（`<MAIN_REPO>` 2 源值、`<REPO_ROOT>` 3 源值；同一行可含多个不同原值）与源件逐行逐字相等、行数守恒（明细见 `ingest-p19.json`）。源件 17 份**一字未改**。

## 三、阶段三 · 台账与 ADR

`docs/M2-排期计划.md`（**只追加**、既有行零改动、表体闭合空行之前；落笔前 `sed -n '335p'` 取样 R-146 格式）：**R-147 `:336`** = ConfirmGate `ok` 语义契约批（引源件 `决策卡-R137-定稿.md` §T7③ / `会审-GLM.md` §「新增风险点与漏项汇总」第 3 项 / `会审-DSPro.md` §逐条评议 ③；缺口 = `FrontendToolResultRequest`（`types/ai.ts:66-78`）无 outcome/ok 字段 + `ok` 现语义 = 回灌成功非业务成败；落点 `ConfirmGate.java:491`，卡写 :478；去向 = **独立小批**；另注来源 = 交接稿（仓外））；**R-148 `:337`** = 过度设计清理批（**21 处**，用户直裁 2026-10-12；**清单落点** = 决策卡 §M6 族别 A1~A7 / B1~B4 / C1~C6 / D2~D4 / E1~E5 + 拆除 Top 5 + 复核锚 `会审-终版-DSPro.md` §M6；去向 = **过度设计清理批**，串行序在 R-137 后）；**R-149 `:338`** = issue#7 历史恢复后工具结果丢失 + 空白消息块（用户亲报高危、独立复核 agent-601 证实、**第三失真点不成立已澄清**（回复用 H4 修正版表述）、三方会审 **H1~H7 零驳回**、决策卡定稿 + 施工卡齐（GLM-5.3 施工、**难度中高**）；去向 = **紧随手动刷新批闭环施工**，闭环后按 **DC-21 三件套**处置 issue）。三行均 `已登记·待排期`（2026-10-12，手动刷新批第 19 期提交批）。落行后 check-docs **6/6**（已复跑）；R-146 `:335` 原文本逐字节保留、净 +3 行。**ADR：本批未新增 / 未变更 / 未销记任何 DC** ⇒ 未发生项（§七-9）。

## 四、阶段四 · 细分提交（三笔，feat 先行 docs 收尾）

暂存 = **逐路径**显式 `git add`（删除件随 `git add <path>` 落删；**未用** `git add -A` / `.` / `add -N -A`）。**笔 1 `ee6d646`** = `feat(backend): 手动刷新批 S5 —— SSE TASK_CHANGED 发布链摘除…`（4 文件 = 1 删 + 3 改；+8 −52）；**笔 2 `0a60e78`** = `feat(frontend): 手动刷新批 —— 任务中心纯手动刷新（轮询/SSE 自刷全砍）+ watch 链路收口修 C1~C3`（20 = 13 M + 4 D + 2 A + script；+972 −1827）；**笔 3 `0f46286`** = `docs(m2,evidence,design): 手动刷新批 17 份卡/报告入库 + R-147/R-148/R-149 落行 + technical-design 同步 S5 摘除面`（19 = 17 新 + 2 docs；+763 −4）。message 照 `93230ad` / `45b2c6a` 风格：`-` 分点逐文件 / 逐子项点名 + 增删与文件数 + 末段 = 门禁数字行 + **锚定双 SHA 块（两锚分列、明示勿混用）**；草稿 `msg-p19-{1-backend,2-frontend,3-docs}.txt`（2061 / 3630 / 4040 B）。**pre-commit 三笔全绿**（`✓ 敏感信息扫描通过（暂存区无命中）`）、**未用** `--no-verify` / `AI_SECRETS_ALLOW=1`（留痕 `commit-p19-{1,2,3}.txt`）。归属：笔 1 = backend 4 路径；笔 2 = `frontend/` 19 条 + `scripts/verify-e2e.ps1`（注释同步随此笔）；笔 3 = `docs/technical-design.md` + `docs/M2-排期计划.md` + `docs/evidence/` 17 份新件。

## 五、阶段五 · push 与 CI 守夜

1. **pre-push 快照** `pre-push-hashes-p19.json`：交付路径 43 条，存在件 **38** 逐文件 sha256（LF 规范化）、删除件 **5** 记名。
2. **push** ⇒ **`45b2c6a..0f46286  main -> main`**，exit 0，**无 force / 无 non-fast-forward**；push 后 HEAD = `origin/main` = `0f46286`、ahead 0、工作树 0 条。组织名 **0 字面**（一律 `<REPO-URL>` + run id 取证）。
3. **CI 守夜**（匿名 API · `curl --ssl-no-revoke` · 间隔 170s · 预算 60/h · **实耗 3 轮 / 6 次 API 调用**）：run **`38207392396`**（head_sha `0f46286`）= **completed / success**，**五 job 全 completed 且全 success** —— 前端类型检查与构建 / 后端编译与单测 / 文档一致性门禁（check-docs）｜阻断 / 全历史脱敏扫描（报告制·不计门禁）/ E2E 全流程（29 用例 · 桩上游 + redis）。首轮实读 jobs=**4**（E2E 上游完成前不出现），**未误判**；停判 = run completed 且 ≥5 job 全 completed ⇒ **五 job 全 success ⇒ 闭环**。脚本 / 日志 = `ci-poll-p19.mjs` / `ci-poll-p19.log`；原始 JSON = `ci-run-0f46286.json` / `ci-jobs-0f46286.json`。

## 六、阶段六 · 提交后终验与自证

**6-1 从零夹带终验（38/38 EQUAL + 5/5 删除确认）**：pre-push 快照（LF 规范化）↔ push 后 `git show HEAD:<path>` blob 逐文件 sha256：backend 3 / frontend 15 / scripts 1 / docs 2 / `docs/evidence/` 17 = **38/38 EQUAL、DIFF 0**；5 删除件 **5/5 不在 HEAD**；`HEAD` = `origin/main` = `prePushHead` = **`0f46286`**、工作树干净 ⇒ **PASS 零夹带终验**。

**6-2 DC-19 署名自证（wire 实证，非配置推定、非照抄卡头）**：实际模型 = **`DeepSeek/deepseek-flash`** —— `agents/agent-610/wire.jsonl` 逐行 JSON 解析 `llm.request` **84 条**，`modelAlias` **84/84 全为 `DeepSeek/deepseek-flash`，无混杂**（`model` = `deepseek-flash` × 84）；agent id = **`agent-610`**（主代理直派子智能体）；wire 文件 = **858479 B / 760 行**、sha256 `2ff964f36455fea673e28795ef5e0fe7c36a0331bff3c132a8fd9b23130a0ea5`（落稿时点读数；本棒仍在运行 ⇒ 只增不减，上值为**下界**）；`profile.bind` 实证 `profileName` = `coder`、`thinkingEffort` = `high`；**无静默换模**，与卡头钉定值一致（互备 GLM-5.3-Flash 未启用）。

**6-3 成本治理（DC-22 / PC-1~PC-5 适用留痕）**：卡 `提交官卡-第19期.md` **10901 B**（**PC-1 直发改引用**：派发头给路径 + sha256，**卡正文未进派发消息**）；message 草稿 2061 / 3630 / 4040 B；本报告正文 ≤16KB（**PC-3 本期豁免**）；门禁 / 扫描 / 重锚比对 / CI 轮询**全部由仓外一次性脚本承载**，不占会话轮次（PC-4）。

## 七、未发生项 / 异常申报（如实留痕）

1. **check-docs 断言⑤「批中态」口径（最关键，留痕防误判）**：**工作树口径**下断言⑤ 报 **5 处「按 git ls-files 清单读取失败（文件缺失或被删除）」** ⇒ 5/6（红）—— 5 个删除件**提交前仍在 index**（`git ls-files` 含之、文件已删），属**批中态**非夹具缺陷。按第 14 / 16 / 18 期先例改**暂存口径**后 **6/6 全绿**，并于三笔提交后 **HEAD 态复跑 6/6**。**未放宽断言、未用逃生口**。
2. **卡面计数与实读差异（两处，照实读落笔）**：① 卡称 frontend「18 条」，**实读 19 条**（13 M / 4 D / 2 ??）⇒ 笔 2 实为 **20 路径**（含 `scripts/verify-e2e.ps1`）；② 卡 §4 会审件组别**两个预设分支均命中**（2 份归 R-137、4 份归手动刷新批，含 2 前序轮次），合计 17 份与 subject 自洽。两处属**卡面算术口径**，**不减任何交付件**。
3. **交付路径另有 5 处非密钥类命中（登记，非夹带）**：`TaskSseService.java:23` / `TaskSseServiceTest.java:30` 的 **`10.1.54`（tomcat-embed-core 版本号）**被 IP 正则**保守过匹配**；`scripts/verify-e2e.ps1:17/30/52` 的 **`127.0.0.1`** 属环回。两类**均非本批引入**（HEAD 同址既有）、非敏感，按第 16 / 18 期口径留痕。**入库件硬命中 0**。
4. **R-148「21 处」逐条原文未随批落盘（如实登记缺口）**：`grep` `<SCRATCH>` 全目录与交接存档**零命中**逐条编号清单；故按**决策卡 §M6 族别口径**登记并注记「族别名与『21 处』计数口径差异；须回取 agent-597 原始审计件方可逐条」——**不臆造内容、不据此宣告已完成**。
5. **未触发任何逃生口**：三笔 pre-commit **零命中通过**；**未用** `--no-verify` / `AI_SECRETS_ALLOW=1`；`.env` **未读**。
6. **红线零触碰 + 仓外零改动**：`<REPO_ROOT>` 两目录**零操作**；`r137-guard` 源件只读；除授权的 `add` / `commit` / `push` 与证据落盘外**无任何顺手改**。
7. **锚点 / 行号漂移**：无（入库锚 `cmp` EQUAL；M2 三行精确落在 `:336~:338`，先实读确认 R-146 在 `:335`，净 +3 行 = 恰为三条落行）。8. **build 已知坑未触发**（首跑即过 ⇒ 重试 **0** 次）。9. **ADR 未发生**（本批无 DC 新增 / 变更 / 销记）。

## 八、本批产出物与取证件清单

| 类别 | 内容 |
|---|---|
| 入库提交（三笔 / **43 路径**） | `ee6d646`（backend 4）+ `0a60e78`（frontend 19 + script 1）+ `0f46286`（docs 19，含 17 份新件） |
| 基线链 | `45b2c6a`（第 18 期）→ **`ee6d646`** → **`0a60e78`** → **`0f46286`**（= `origin/main`）；锚定取证（本棒**只读**）= `<SCRATCH>\diff-手动刷新批-重锚.sorted.patch` + `sort-patch-reanchor.mjs` |
| 零夹带 / 门禁 / 脱敏 | `regen-p19.sorted.patch`、`reanchor-p19.log`、`gates-p19-frontend.txt`、`gates-p19-backend.txt`、`gates-p19-checkdocs.txt`、`ingest-p19.mjs` + `ingest-p19.json`（17 份源 / 入库字节 + sha256 + 替换计数 + 保真行数）、`ingest-p19.log`、`scan-p19.mjs` + `scan-p19-seed.txt`（规则自检） |
| 提交 / push / CI | `msg-p19-{1,2,3}*.txt`、`commit-p19-{1,2,3}.txt`（含 pre-commit 输出）、`pre-push-hashes-p19.json`、`push-p19.txt`、`ci-poll-p19.mjs` / `ci-poll-p19.log`、`ci-run-0f46286.json` / `ci-jobs-0f46286.json`（run id `38207392396`） |

---

**签字**：提交官（第 19 期；**本棒 id = `agent-610`，运行时自证实际模型 = `DeepSeek/deepseek-flash`**（wire 实证 `llm.request.modelAlias` **84/84 无混杂**，见 §6-2）），2026-10-12。**授权来源 = K3 指挥官派单 · 提交官第 19 期 · 手动刷新批入库（本卡 sha256 `65536d09…b85c5c`）**。本报告证据均可于 `<SCRATCH>\` 取证件与仓库三笔提交（`ee6d646` / `0a60e78` / `0f46286`）处复核；CI 一律以 run id **`38207392396`** 取证，**全文 0 组织名字面**。

---

# ai-example 合流项目 · 提交官报告（第 20 期：R-149 施工+收口修入库 —— **两笔细分提交**（feat frontend + docs）+ issue#7 四份报告补档入库 + M2:338 闭环注记 + **DC-21 三件套处置 issue#7**）

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 卡核验 | ✅ 卡 **8134 B** / sha256 `ae9128f91f9a22fcc9ecb2962d6e736ae7b7c16787a86429011b73dd1de82792` = 派发头给定值，未触发停手 |
| 1 零夹带 | ✅ 工作树**恰 2 条**（2 M，无未跟踪）；HEAD = `origin/main` = **`0f46286`**（ahead 0、暂存区空）；**重锚 `cmp` 逐字节 EQUAL**（入库锚 `54c94276…9150` / 21915 B / **2 段** / +396 −19） |
| 2 门禁 · 3 入库 · 4 台账 | ✅ 五门禁全绿（vitest **18 spec / 212**、typecheck 0、build 0、check-docs **6/6**、后端 **442 / 0 / 0 / 0** + BUILD SUCCESS / XML 70）；**4 份**入 `docs/evidence/`（**硬命中 0**、**保真 4/4**）；M2 **`:338` 行尾补注**（**只改 1 行**），复跑 check-docs 6/6（`ls-files` 1038 = 1034 + 4） |
| 5 两笔 + push · 6 终验 | ✅ `e1b9314` → `4260651`（**pre-commit 两笔全绿零命中**；push `0f46286..4260651` 无 force）；**7/7 EQUAL**，HEAD = `origin/main` = `prePushHead` = `4260651`，工作树 0 条 |
| 7 CI 守夜 | ✅ run **`38211357951`**（`head_sha=4260651`）**completed / success**，**五 job 全 completed 全 success**；轮询 **3 轮 / 6 次 API 调用** |
| 8 DC-21 三件套 | ✅ issue#7：**评论** ID `6118891028`（1714 字符与拟稿逐字全等）→ **关闭**（`state_reason=completed`）→ **存证后删除**；删除后 REST **410 Gone** + `/issues?state=all` **空数组**；**13 件证据 + README 登记行**落 `issue-archive\` |

## 一、阶段一 · 机械验收与零夹带核验

### 1-1 工作树状态（全量照贴，`git status --porcelain --untracked-files=all`【实测】）

```
 M frontend/src/stores/ai.ts
 M frontend/tests/parts-residue-cleanup.spec.ts
```
**恰 2 条 ` M`**、0 未跟踪、暂存区空；`origin/main...HEAD` = `0  0`。**判别**：R-149 在制品，与卡 §1 预期态一致 ⇒ 未触发停手。入库后变 **3 M + 4 ??**，逐路径 `git add` 后归零。

### 1-2 零夹带对重锚（`cmp` 逐字节 EQUAL）

- 锚件 `r137-guard\diff-R149-重锚.sorted.patch` sha256 = `54c94276…9150` = **卡面给定值** ✅；审查锚 `3f797644…42dee` ✅ 与派单一致。**双 SHA 勿混用**：审查锚 = 18538 B / 2 段 / **+331 −19**；入库锚 = 21915 B / 2 段 / **+396 −19**。
- `git diff` 全量 21915 B / **2 段** / +396 −19（无未跟踪 ⇒ 无需 `add -N`）；按路径字典序重排（复用 `sort-patch-reanchor.mjs`）后 sha256 同值，**`cmp` 逐字节 EQUAL**（**原始 `git diff` 亦同值**）；入库后 porcelain 仍 2 条、暂存区空 ⇒ **零夹带 PASS，未触发 DIFF 停手**。

### 1-3 门禁复跑（提交前对工作树真跑，全绿后才 commit）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 前端 vitest | `cd frontend && yarn test` | **18 passed (18) / 212 passed (212)**，EXIT 0（`gates-p20-frontend.txt`） |
| typecheck | `cd frontend && yarn typecheck` | EXIT 0（`vue-tsc --noEmit`） |
| build | `cd frontend && yarn build` | EXIT 0（`✓ built in 28.61s`） |
| check-docs | `node scripts/ci/check-docs.mjs`（仓库根） | **6/6**（入库后 `git ls-files` **1038** = 1034 + 4） |
| 后端 | `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B clean test` | `442, Failures: 0, Errors: 0, Skipped: 0` + **BUILD SUCCESS** + XML **70** |

**已知抖动 / 坑均未触发**：`restore-reconcile` 负例①（计时敏感）与 esbuild Temp 占用坑均首跑即过、重试 **0**。

## 二、阶段二 · 脱敏终扫与 evidence 入库（4 份）

| 源件（只读） | 字节 | 入库件（`docs/evidence/`） | 入库字节 | 保真 |
|---|---|---|---|---|
| `施工报告-R149.md` | 4498 | `补档-R149-issue7-施工报告.md` | 5546 | **46/46 PASS** |
| `复核报告-R149.md` | 5252 | `补档-R149-issue7-复核报告-GLM-5.3.md` | 6300 | **37/37 PASS** |
| `红队报告-R149.md` | 4594 | `补档-R149-issue7-红队报告-DS-V4-Pro.md` | 5642 | **37/37 PASS** |
| `收口修报告-R149.md` | 2793 | `补档-R149-issue7-收口修报告.md` | 3844 | **36/36 PASS** |

- 源 sha256 全值 / 替换计数见 `ingest-p20.json`（首 8 位 = `154b9921` / `04074a48` / `72ad0a29` / `79bd5f5e`）。**格式照 45b2c6a / 0f46286 先例**：首行 `<!-- 第 20 期补档入库 · R-149（issue#7） · 已脱敏 -->` + 引用块横幅（源件路径 + 字节数 + **sha256 全值** + 逐字一致零改写声明 + 占位口径行 + 双锚注记）。
- **脱敏终扫（仓门外独立自建十类扫描器）**：规则自检 **正向 17/17 + 负对照 11/11 未误伤**（`scan-p20-seed.txt`）；4 件全量实扫 **硬命中 0**（`scan-p20-realscan.txt`）。源件本身零敏感项 ⇒ 替换计数 = **none**，即「零替换即达标」，非「扫描器失明」（seed 自检 + 盘符残留检查双兜底）。**未入库（留仓外）**：复核 / 红队 / 收口修卡-R149、`重锚增量比对-R149.md`、两锚 patch 件（照卡 §4）。

## 三、阶段三 · 台账（M2 闭环注记）

- `docs/M2-排期计划.md:338` R-149 行**行尾补注**（照 R-146 / R-88 惯例，**只改 1 行**，实测 `1 insertion / 1 deletion`）：**已闭环（2026-10-12，第 20 期提交批）**：K2.8 施工 + GLM 复核带条件 PASS（m-1）+ DSPro 红队 PASS 无条件（3O）+ 收口修 C1~C3；**登记不修 = 红队 O-2**（镜像回归用例静态模拟 sessionStorage、未覆盖 writeMirror 300ms 防抖真实时序交错面，非本批面）。
- 改后复跑 `check-docs` = **6/6**。**ADR 无变更**（DC-21 为既有决策，本批首次执行）。

## 四、阶段四 · 细分提交（两笔，禁裸 `add -A`）

| 笔 | commit | subject | 路径 | 增删 |
|---|---|---|---|---|
| 1 | **`e1b9314`** | `feat(frontend): R-149 issue#7 历史恢复双条目合并（loadHistory 两遍扫描+consume-on-match+序位兜底 name 闸）+ 收口修 C1~C3（投影重派生/tie-break 注释/序无关补测）` | 2（`stores/ai.ts` + `parts-residue-cleanup.spec.ts`） | +396 −19 |
| 2 | **`4260651`** | `docs(m2,evidence): R-149 issue#7 四份报告入库 + M2:338 闭环注记（O-2 登记不修）` | 5（M2 + 4 新增补档件） | +173 −1 |

- 暂存**逐路径 `git add`**，未用 `add -A` / `.`；两笔 message 草稿（`msg-p20-{1,2}*.txt`）均含**锚定双 SHA 块** + 勿混用口径。**pre-commit 两笔均输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`**；**未用** `--no-verify` / `AI_SECRETS_ALLOW`（留痕 `commit-p20-12.txt`）。

## 五、阶段五 · push 与 CI 守夜

1. **push 前快照**：交付路径 **7 文件**逐文件 sha256（LF 规范化）落 `pre-push-hashes-p20.json`（base `0f46286` / head `4260651`）；**push** = `git push origin main` ⇒ `0f46286..4260651  main -> main`（**无 force**；`push-p20.txt`）。
2. **CI 守夜（匿名 API 轮询，60/h 省用）**：轮 1 `06:11:58` → run `38211357951` `queued`、jobs=**4**（E2E 未现，符合卡面预告）；轮 2 `06:14:50` → `in_progress`、jobs=**5**；轮 3 `06:17:42` → **`completed` / `conclusion=success`、5 job 全 success ⇒ DONE**。总 API 调用 **6 次**。
- **五 job** = 前端类型检查与构建 / 后端编译与单测 / 文档一致性门禁（check-docs）｜阻断 / 全历史脱敏扫描（报告制·不计门禁） / E2E 全流程（桩上游+redis）。**闭环判据达成；无 failure，未触发自行修复再推。**

## 六、阶段六 · 提交后终验与自证

**6-1 零夹带终验（7/7 EQUAL）**：pre-push 快照（LF 规范化）↔ push 后 `git show HEAD:<path>` blob 逐文件 sha256 ⇒ **7/7 EQUAL、DIFF 0**（frontend 2 / `M2-排期计划.md` 1 / `docs/evidence/` 4）；`HEAD` = `origin/main` = `prePushHead` = **`42606518dac1180a8c4606212f652e297da61b6d`**（短 `4260651`）；`git status --porcelain` **0 条**、ahead 0 ⇒ **PASS**。

**6-2 DC-19 署名自证（wire 实证，非配置推定、非照抄卡头）**：实际模型 = **`DeepSeek/deepseek-flash`** —— `agents/agent-616/wire.jsonl` 逐行 JSON 解析，`"modelAlias"` 去重计数 **`DeepSeek/deepseek-flash` × 132 / 其它 0**（**132/132 无混杂**）；`llm.request` **131 条**，`"model"` 全为 `DeepSeek/deepseek-flash`。agent id = **`agent-616`**；wire = **1061649 B / 1150 行**、sha256 `88d3d22d26a38653b194a52a6c0eba68f42bb9525019965e0730c67dc258e2be`（落稿时点读数 ⇒ 上值为**下界**）；`profile.bind` 首条实证 `profileName=coder`、`thinkingEffort=high`。**无静默换模**，与卡头钉定值一致；**互备 GLM-5.3-Flash 未启用、零替换事件**。取证 `wire-p20-attest.txt`。

**6-3 成本治理（DC-22 / PC-1~PC-5）**：卡 **8134 B**（**PC-1 直发改引用**：派发头给路径 + sha256，卡正文未进派发消息）；message 草稿 **4142 / 2462 B**；本报告正文 **≤16KB（PC-3 本期豁免**，K3 已裁）；门禁 / 重锚 / 脱敏 / CI / issue 处置**全部由仓外一次性脚本承载**（`ingest-p20.mjs`、`scan-p20.mjs`、`ci-poll-p20.mjs`、`wb-p20.mjs`、`wb-seq.mjs`、`dc21-*.mjs`；锚比对复用 `sort-patch-reanchor.mjs`），不占会话轮次（PC-4）。

## 七、阶段七 · **DC-21 三件套处置 issue#7** 与异常申报

### 7-1 DC-21 执行明细（**通道 = kimi-webbridge**，依据见 7-2①）

| 步 | 动作 | 回执 / 实测（证据前缀 `evidence-issue7` 从略） |
|---|---|---|
| 存证前置 | issue+comments+events 全量快照 | `state=open`、`comments=0`、`labels=[]`、`created_at=2026-10-12T03:50:08Z`、正文 2616 字符 → `-api-predelete.json` |
| 存档 | 删除前全文存档（政策强制） | 正文 + 评论逐字 + 时间线 + 关联提交 + 留证清单，**10784 B**；盘符 / 组织名各 **0 命中** → `issue-7-历史恢复后工具结果丢失+空白消息块.md` |
| **评论附数据**（H4 修正版） | 填 `textarea` → 逐字校验 → 点 `Comment` | 评论 ID **`6118891028`**、`06:19:57Z`、**body 1714 字符 = 拟稿逐字全等**；发表后 issue **仍 `open`**（评论与关闭分离） → `-api-comment.json`、`-commented-before-close.png` |
| **关闭** | 点 `Close issue` | `state=closed`、**`state_reason=completed`**、`closed_at=06:20:50Z`、`comments=1` → `-api-closed.json`、`-closed-before-delete.png` |
| **删除** | `Delete issue` → 确认框 `Delete` | 确认框 = `Delete issue? / This cannot be undone / Only administrators can delete issues / Cancel / Delete`；删除后跳回 `…/issues` → `-delete-confirm.png` |
| 删除后核验 | ① 直访 issue URL ② 列表 ③ 匿名 REST | ① 占位页「**This issue has been deleted.**」；② `state:open` / `state:closed` 两查均 `No results matched your search`（分段计数 **Open 0 / Closed 0**）；③ `GET /issues/7` → **HTTP 410 Gone**、`GET /issues?state=all` → **空数组（总数 0）** → `-deleted-placeholder.png`、`-{open,closed}-list-after-delete.png`、`-api-deleted-410.json`、`-api-all-list-after.json` |
| README 登记 | `issue-archive\README.md` 追加 **#7 行 + 6 条** | 照既有格式；**既有行零改动** |

**评论要点（照卡 §8-2 / H4 修正版）**：①工具结果丢失已修复（两遍扫描合并回填）；②空白消息块已修复（`TOOL` 条目不产消息）；③文本前缀位置丢失**不成立（限定语「当前模型路径下不成立」：工具轮 `ASSISTANT` `getText()` 恒空、无前缀可丢）**；数据 = `e1b9314` / `4260651` + vitest 18 spec / 212 + run `38211357951` 五 job success；末段按政策关闭并删除。**13 件证据**（7 PNG + 5 JSON + 1 MD）落 `issue-archive\`。

### 7-2 异常申报（如实留痕）

1. **`gh` CLI 不可用 ⇒ 通道走 kimi-webbridge（防误判为「自行变通」）**：卡 §8-2 钉定用 `gh`，实测本机**无 `gh`** —— PATH、`Program Files` / `AppData` / winget 包目录 / scoop / choco 均无 `gh.exe`，亦无 gh 配置目录，环境变量无 GH token。**依据非本棒自创**：项目既定环境口径明文「**gh CLI 不可用，issue 走 kimi-webbridge**（daemon `127.0.0.1:10086`；评论附数据→关闭→删除→存档 `issue-archive\`）」（交接档【环境与凭证】节 + `issue-archive\README.md` #1~#6 先例 + `issue6-reports\关闭报告.md`「gh CLI 未使用」）。故**未装工具、未改处置步骤与证据形态**；已写入该 README 供后续棒复核。
2. **取数坑 · 第二类（#7 首次现形）**：`github.com` / `api.github.com` **两处页内 `fetch` 均被 CSP 阻断**（前者经 GitHub `fetch-utilities` 包装抛 `TypeError: Failed to fetch`；后者 `default-src 'none'`）⇒ 快照改**标签直读 `api.github.com` 原始 JSON**；故不适用 #6 的 `cache:'no-store'` 条款（无缓存介入面），响应体逐字一致（含 `status:"410"`），差异仅在通道并已在证据件 `note` 注明。
3. **删除定位偏差**：`Delete issue` **首次单一 `click()` 未唤起确认框**（React 侧不响应）；改发完整指针序列（pointerdown→mousedown→pointerup→mouseup→click）后确认框如期出现。**未用坐标盲点**，两次均经 DOM 文本 × 可访问性树核验。
4. **组织名 0 字面落地**：新增件（1 MD + 5 JSON）做占位替换（`<REPO-URL>` / `<REPO-URL>/api` / `<ORG>`），复核**盘符 0 命中、组织名 0 命中**；**未脱敏原始件**留 `issue7-reports\_raw\`、过程件 `_wb\`。本报告与仓库交付件**全文 0 组织名字面**。
5. **索引滞后未复现 + 锚点零漂移**：删除后**即时**读到 `Open 0 / Closed 0`（#4/#5/#6 曾现形约 1 分钟陈旧值）⇒ 无「回落前后」双截图，登记为「本期未现形」而非「未做核验」；入库锚 EQUAL、M2 精确落 `:338`、净变更 1 行。
6. **源件零改写 + 无逃生口 + 红线零触碰**：`r137-guard\` 4 件**只读**（源字节 4498 / 5252 / 4594 / 2793）；两笔 pre-commit 零命中，**未用** `--no-verify` / `AI_SECRETS_ALLOW`，**未读** `.env`，**key 未落盘**；`ai-example` 与 `ai-example-code\ai-example-main` **零操作**，唯一可操作仓 = `本仓`。**浏览器标签组遗留**（非仓库改动）：会话 `issue7-dc21` 标签组保留在用户浏览器（skill 规定关闭须用户显式发起），如需清理请告知。

## 八、本批产出物与取证件清单

| 类别 | 内容 |
|---|---|
| 入库提交（两笔 / **7 路径**） | `e1b9314`（frontend 2：`stores/ai.ts` + `parts-residue-cleanup.spec.ts`，+396 −19）+ `4260651`（docs 5：`M2-排期计划.md` + `docs/evidence/` 4 新件，+173 −1） |
| 基线链 | `0f46286`（第 19 期）→ **`e1b9314`** → **`4260651`**（= `origin/main`）；锚件（本棒**只读**）= `r137-guard\diff-R149-{重锚,}.sorted.patch` + `sort-patch-reanchor.mjs` |
| 零夹带 / 门禁 / 脱敏 / CI | `p20-raw.patch`、`regen-p20.sorted.patch`、`reanchor-p20.log`、`gates-p20-{frontend,fe-build,backend,checkdocs}.txt`、`ingest-p20.{mjs,json,log}`、`scan-p20.{mjs,seed.txt,realscan.txt}`、`ci-poll-p20.{mjs,log}`、`ci-run-p20.json` / `ci-jobs-p20.json` |
| 提交 / push / DC-19 | `msg-p20-{1,2}*.txt`、`commit-p20-12.txt`（含 pre-commit 输出）、`pre-push-hashes-p20.json`、`prepush-head-p20.txt`、`push-p20.txt`、`wire-p20-attest.txt`（agent-616 / `DeepSeek/deepseek-flash` 132/132） |
| DC-21 处置 | 脚本 `wb-p20.mjs`、`wb-seq.mjs`、`dc21-{evidence,archive}-p20.mjs`；证据 13 件 + README 登记行落 `issue-archive\`；过程件 `issue7-reports\`（`_wb\` / `_raw\`） |

---

**签字**：提交官（第 20 期；**本棒 id = `agent-616`，运行时自证实际模型 = `DeepSeek/deepseek-flash`**（wire 实证 `modelAlias` **132/132 无混杂**，见 §6-2）），2026-10-12。**授权来源 = K3 指挥官派单 · 提交官第 20 期 · R-149 issue#7 入库 + DC-21 三件套（本卡 sha256 `ae9128f9…82792`）**。本报告证据均可于 `<SCRATCH>\` 取证件与仓库两笔提交（`e1b9314` / `4260651`）处复核；CI 一律以 run id **`38211357951`** 取证，issue#7 处置以评论 ID **`6118891028`** + REST **410** 取证，**全文 0 组织名字面**。

---

# ai-example 合流项目 · 提交官报告（第 21 期：R-137 后端 AI 工具终态守卫批入库 —— **两笔细分提交**（feat 代码/测试 + docs 补档）+ 四份外部报告脱敏入库 + M2:326 闭环注记；**CI 四绿一红：E2E job `failure`**）

> 组织名 0 字面：本段全文以 `<REPO-URL>` / 主仓 / `<SCRATCH>` 指代远端与仓外路径，未出现 GitHub 组织名字面、用户名、内网 IP、盘符路径与任何密钥。

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 卡核验 | ✅ 卡 `提交官卡-第21期.md` **27 行** / sha256 `7244a422523099e5bfcf13aa24ceeffa2cee1706614b79d6f5bc305a3dd240bf`（现算，派发头未给值 ⇒ 以现算值为本批基准） |
| 署名自证（DC-19） | ✅ 本 run `agents/agent-654/wire.jsonl` 首条 `profile.bind`：`modelAlias = "DeepSeek/deepseek-flash"` / `profileName=coder` / `thinkingEffort=high`；全文 `DeepSeek/deepseek-flash` 命中 **18 次**、无其它 modelAlias 记录注入（详见 §6-2） |
| 1 锚核验 | ✅ porcelain **11 条**（7 M + 4 ??）= 锚 `files[]` 逐路径一致；4 个未跟踪逐个 `git add -N`（未用 `-A`）→ `git diff HEAD -- .` → sha256 **`ce1b133120bb10cf524eadbd9e8d52ccdba60c5745fe4e3727e700871d5d1832`** / **53274 B** / **11 段**，与锚 `diff.sha256` **cmp 逐字节 EQUAL**；`git reset -q` 后 index 空、porcelain 复原 11 条 |
| 2 机械验收（提交官亲跑，DC-20 不豁免） | ✅ 后端 **466 / 0F 0E 0S · 73 XML · BUILD SUCCESS**；前端 **216 用例 / 19 spec 全绿**、`typecheck` exit 0、`build` exit 0（28.00s）；`check-docs` **6/6**（`ls-files` 1038） |
| 3 脱敏入库 | ✅ 4 件落 `docs/evidence/`（保真 **4/4 逐字节 EQUAL**、脱敏终扫**六类硬命中 0**）；入库后复跑 `check-docs` **6/6**（`ls-files` **1046**） |
| 4 M2 落行 | ✅ `docs/M2-排期计划.md:326` R-137 行**行尾闭环注记**（只改 1 行、行正文逐字节保留） |
| 5 两笔 + push | ✅ **`79ec764`**（feat，11 路径）→ **`b1f8f22`**（docs，5 路径）；逐路径 `git add`、两笔 pre-commit 均 `✓ 暂存区无命中`；push `4260651..b1f8f22  main -> main`（**无 force**） |
| 6 终验 | ✅ **feat 笔提交后 diff（`git diff 4260651 79ec764`）sha256 仍 = 入库锚 `ce1b1331…1832`（cmp EQUAL）**；4 件补档 blob ↔ 入库字节 **4/4 EQUAL**；`HEAD = origin/main = b1f8f22…`、ahead 0、porcelain **0 条** |
| 7 CI 守夜 | ⚠️ run **`38222153002`**（`head_sha=b1f8f22…`）**completed / conclusion=failure**；**4 job success**（前端 / 后端 / check-docs / 全历史脱敏扫描）、**E2E job `failure`**（step 12「运行 E2E（29 用例）」exit 1）⇒ **如实报红，未自行修补**（见 §7） |
| 8 提交报告 | ✅ 本段追加落盘 |

## 一、阶段一 · 锚核验与零夹带（`cmp` 逐字节 EQUAL）

### 1-1 工作树状态（全量照贴，实测）

```
 M backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
 M backend/src/main/java/com/example/configmgr/job/service/JobService.java
 M backend/src/main/java/com/example/configmgr/task/service/TaskService.java
 M backend/src/test/java/com/example/configmgr/ai/tools/AiToolsInvocationTest.java
 M frontend/src/stores/workspace.ts
 M scripts/m2t2a-smoke.mjs
 M scripts/verify-e2e.ps1
?? backend/src/test/java/com/example/configmgr/ai/tools/AiToolsThrowPathTest.java
?? backend/src/test/java/com/example/configmgr/job/service/TaskFinalizedGuardTest.java
?? backend/src/test/java/com/example/configmgr/task/service/TaskGoToStepGuardTest.java
?? frontend/tests/goto-step-readonly-guard.spec.ts
```
**恰 11 条（7 M + 4 ??）**，与锚 `files[].path` 逐路径一致（含 4 个 `A(新)` 对应 `??`）；`HEAD = 42606518dac1180a8c4606212f652e297da61b6d`、`ahead 0` ⇒ **未触发停手**。

### 1-2 入库锚重生成（零夹带 PASS）

- 逐个 `git add -N`（4 个未跟踪文件，**未用 `-A`**）→ `git diff HEAD -- .` → `diff-repro.diff`：
  sha256 = **`ce1b133120bb10cf524eadbd9e8d52ccdba60c5745fe4e3727e700871d5d1832`** / **53274 B** / `grep -c '^diff --git '` = **11 段**，
  与锚件 `diff-r137-final.diff`（同 sha256 / 同字节）**`cmp` → EQUAL（byte-identical）** ✅
- 还原：`git reset -q` → `git diff --cached --stat` 行数 **0**、porcelain 复原原 11 条、HEAD 未动。
- 锚件自身：`diff-anchor-r137-final.json` 22567 B（`generatedAtUtc 2026-10-12T08:06:15Z` / `generator.agentId=agent-651` / `anchors.previous` = 审查锚 `dd74d616…3195`、`anchors.current` = 入库锚 `ce1b1331…1832`）。**双 SHA 勿混用**：审查锚 = 51982 B / 11 段（复核与红队据其出报告）；入库锚 = 53274 B / 11 段（收口修 C-3/R-2 + R-1 后重锚，本批入库字节据其）。

### 1-3 机械验收复跑（工作树真跑，全绿后才 commit）

| 门禁 | 命令（实测） | 实测结果 |
|---|---|---|
| 后端 | `bash <SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh -B test`（卡指定透传脚本，脚本内 `cd backend`） | `Tests run: 466, Failures: 0, Errors: 0, Skipped: 0` + **BUILD SUCCESS**（01:09 min）；`target/surefire-reports/*.xml` = **73**；XML 内含 `failures="[1-9]\|errors="[1-9]` 的文件 = **0**；包装器 `WRAPPER_EXIT=0` |
| 前端单测 | `cd frontend && yarn test`（vitest run） | **`Test Files 19 passed (19)` / `Tests 216 passed (216)`**，`TEST_EXIT=0`；`✓ tests/` 行计数 = **19** |
| 类型检查 | `yarn typecheck`（vue-tsc --noEmit） | `TYPECHECK_EXIT=0`，零报错 |
| 生产构建 | `yarn build`（vue-tsc + vite） | `BUILD_EXIT=0`，`✓ built in 28.00s`（vite v6.4.4）——**esbuild Temp 占用连败坑未触发、重试 0 次** |
| 文档门禁 | `node scripts/ci/check-docs.mjs`（仓库根） | **6/6 断言绿**（`CHECK_DOCS_EXIT=0`）；命名纪律扫描 `git ls-files` **1038** 路径 / 实扫文本 1035 / 豁免 3；规则自检「连写组 5/5、分隔组 24/24、负对照 11 不误伤」 |

**与卡期望逐项一致**：后端 466 / 0F 0E 0S / 73 XML ✅；前端 216 · 19 spec ✅；check-docs 6/6 ✅。构建后 `frontend/dist/` 被 `.gitignore` 排除（`git check-ignore` 命中 `.gitignore:17`），porcelain 仍 11 条 ⇒ 未污染锚态。

## 二、阶段二 · 脱敏终扫与 evidence 入库（4 件）

| 源件（只读，`<SCRATCH>\r137-guard\`） | 字节 | 入库件（`docs/evidence/`） | 入库字节 | 保真 |
|---|---|---|---|---|
| `施工报告-R137.md` | 7063 | `补档-R137-施工报告.md` | 8234 | **EQUAL**（正文 7063 B `cmp` 逐字节） |
| `复核报告-DSPro-R137.md` | 5721 | `补档-R137-复核报告-DSPro.md` | 6904 | **EQUAL**（正文 5721 B） |
| `红队报告-K28-R137.md` | 7052 | `补档-R137-红队报告-K28.md` | 8231 | **EQUAL**（正文 7052 B） |
| `收口修报告-R137.md` | 3411 | `补档-R137-收口修报告.md` | 4588 | **EQUAL**（正文 3411 B） |

- 源件 sha256 全值：`1a4d58b3…3c2e67b` / `c6679bf6…d106b3f` / `910f4967…f78f238` / `87f7661a…b4fe14b8`（全值见各件首行横幅与 `msg-docs.txt`）。入库件 sha256（`git show HEAD:` blob 实测）：`4572849f…5ec0f74a1` / `59941a66…7a988e1eff` / `04187367…1d749d9a` / `0466abcde…17a8ad56bd`（首 8 位：`4572849f` / `59941a66` / `04187367` / `0466abcde`）。
- **格式照 45b2c6a / 4260651 先例**：首行 `<!-- 第 21 期补档入库 · R-137（后端 AI 工具终态守卫批） · 已脱敏 -->` + 引用块横幅（源件路径 `<SCRATCH>\r137-guard\<原名>` + 字节数 + **sha256 全值** + 「除本说明块外正文与源件逐字一致（零改写）」声明 + 脱敏占位口径行 + 审查锚/入库锚双 SHA 不可混用注记）。
- **脱敏终扫（仓外一次性脚本 + 逐文件 grep 复核，双留证）**：四件对 **六类** 硬命中 — 盘符/本机绝对路径 `[A-Za-z]:[\\/]`、用户名、组织名字面、内网 IP（10/192.168/172.16-31 三段式）、密钥族（`sk-*` / `AI_SECRETS` / `api_key[:=]`）、命名纪律禁则（main+分隔符(0+)v2 三态口径）— **全部 = 0**；主机名 = 0。**源件本即零命中 ⇒ 替换计数 = 0（「零替换即达标」，非扫描器失明）**；**未启用 `AI_SECRETS_ALLOW` / 未用 `--no-verify`**。
- 入库后 worktree：`check-docs` 复跑 **6/6**，`git ls-files` **1046**（= 1038 + 代码笔 4 件 + 本笔 4 件，路径数随入库件增加属预期）。

## 三、阶段三 · 台账（M2 闭环注记）

- `docs/M2-排期计划.md:326`（R-137 行）**行尾补注**，格式仿 `4260651` 的 R-149 落行（期号 + 去向 + 基线转正口径一句）；**行正文逐字节保留**（实测 `1 insertion / 1 deletion`）。注记要点：**已闭环（2026-10-12，第 21 期提交批）** = GLM 施工 + DSPro 复核带条件 PASS（C-3 minor）+ K2.8 红队 PASS（0 MAJOR）+ 收口修 C-3/R-2 与 R-1 全修（注释级、零逻辑改动）；**基线转正** = 后端 **466 / 0F 0E 0S · 73 XML**、前端 **216 例 · 19 spec**；**去向** = 代码笔 `79ec764` + 本笔 4 件补档；**移出项** = ConfirmGate `ok` 语义契约批立项 **R-147**（登记不修项 = 红队 R-1 已以源码注释明示、R-3~R-5、R-6/R-7、复核 F-1~F-3 留后续批）。
- 改后复跑 `check-docs` = **6/6**（见 §2）。ADR 无变更。

## 四、阶段四 · 细分提交（两笔，禁裸 `add -A`）

| 笔 | commit | subject | 路径 | 增删 |
|---|---|---|---|---|
| 1 | **`79ec764`** | `feat(backend,frontend,scripts): R-137 后端 AI 工具终态守卫批（终态守卫下沉 + goToStep 双守卫 + catch 分级重抛 + N-6 收编）` | **11**（7 改 + 4 新增） | +767 −34 |
| 2 | **`b1f8f22`** | `docs(m2,evidence): R-137 四份报告入库 + M2:326 闭环注记（基线转正 后端 466 · 前端 216/19 spec）` | **5**（M2 改 + 4 新增补档） | +226 −1 |

- 暂存**逐路径 `git add`**（11 次 + 5 次，**未用 `add -A` / `.`**）；message 草稿 `msg-feat.txt` / `msg-docs.txt`（仓外），均含**锚定双 SHA 块**与勿混用口径，四源件 sha256 全值**逐字符与源件复算值相等**（脚本复核 `IN-MSG-OK` ×6）。
- **feat 笔提交前最后一次复核**：对其暂存态重生成 `git diff HEAD -- .` ⇒ sha256 **仍 = `ce1b1331…1832`**、与锚 `cmp` **EQUAL**（未打折、未因新增件漂移）。
- 两笔 pre-commit 钩子输出均 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`；提交前另用 `scan-lib.sh` 同管道预扫暂存区 = **零命中**；**未用** `--no-verify` / `AI_SECRETS_ALLOW`；**未 force push、未改历史**（`git log` 线性：`4260651 → 79ec764 → b1f8f22`）。

## 五、阶段五 · push 与 CI 守夜

1. **push**：`git push origin main` ⇒ `4260651..b1f8f22  main -> main`（`PUSH_EXIT=0`，**无 force**）；push 后 `ahead 0`、`origin/main = b1f8f22…`。
2. **CI 轮询**（匿名 API、间隔 **170s**（≥90s）、`curl --ssl-no-revoke`、轮 3 次 = 6 次 API 调用，远低于 60/h）：
   - 轮 1 `08:12:31Z` → run **`38222153002`** `queued`、jobs=**4**（E2E 未现，符合卡面预告）；
   - 轮 2 `08:15:23Z` → `in_progress`、jobs=**5**（E2E 出现，`in_progress`）；
   - 轮 3 `08:18:15Z` → **`completed` / `conclusion=failure`**、5 job 全 completed，其中 **E2E = `failure`** ⇒ 按脚本判据 `STOP: job failure (not fixing, reporting only)`，**未重跑、未修补**。
3. **run id `38222153002`（`head_sha=b1f8f22ef6df5b0c31e165081ba844075f8b9594`）五 job conclusion**：

| job | conclusion | 说明 |
|---|---|---|
| 前端类型检查与构建 | **success** | 步骤 5/6/7（typecheck / vitest / build）全 success |
| 后端编译与单测 | **success** | `mvn test` 全量用例 success |
| 文档一致性门禁（check-docs）｜阻断 | **success** | 新入库 4 件补档 + M2 改动**未致门禁红** |
| 全历史脱敏扫描（报告制 · 不计门禁） | **success** | 全历史扫描报告已归档 |
| E2E 全流程（29 用例 · 桩上游 + redis） | **failure** | **step 12「运行 E2E（29 用例）」exit 1**；其前置步骤（Initialize containers / 打包后端 / 桩自检 / 桩启动 / 后端启动 / 健康就绪 / 种子就绪）全 success；step 13「归档证据」success |

## 六、阶段六 · 提交后终验与自证

**6-1 提交字节终验（比快照法更强的口径：直接核提交对象）**
- `git diff 4260651 79ec764 -- .` → `diff-feat-commit.diff`：sha256 = **`ce1b133120bb10cf524eadbd9e8d52ccdba60c5745fe4e3727e700871d5d1832`** / 53274 B，与锚 `cmp` **EQUAL** ⇒ **feat 笔提交对象字节 = 入库锚字节**（自证「零夹带入库」，非仅暂存态一致）。
- 4 件补档：`git show HEAD:docs/evidence/<件>` 的 sha256 ↔ 入库前 staged 件 sha256 **4/4 EQUAL**（LF 归一化下逐字节一致）。
- `HEAD = origin/main = b1f8f22ef6df5b0c31e165081ba844075f8b9594`；`git status --porcelain` = **0 条**；`origin/main..HEAD` = **0**；工作树干净（`frontend/dist` 被 gitignore 排除、无残留件）。

**6-2 DC-19 署名自证（wire 实证，非配置推定、非照抄卡头）**
- 作用域：本 run 的 agent 目录 = `<KIMI_HOME>\sessions\<WORKSPACE>\<KIMI_SESSION_B>\agents\`，同批目录 = `agent-640…645 / 651 / 652 / 654 / main`。**任务标志串交叉定位**：`提交官卡-第21期` 命中 **agent-654 = 8 次**（`main` = 4、`agent-652` = 0、`agent-651` = 0）、`commit-gov` 命中 **agent-654 = 9 次**（`main` = 7、其余 0）、`diff-r137-final` 命中 agent-654 = 6 / agent-651 = 39（锚定棒，非本 run）⇒ **agent-654 = 本 run 唯一对应 wire**。
- 实证记录：`agent-654/wire.jsonl` **第 5 行**首条 `profile.bind` = `{"type":"profile.bind","agentId":"agent-654","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",…}`；全文件 `DeepSeek/deepseek-flash` 命中 **18 次**。**结论：本 run 实际模型 = `DeepSeek/deepseek-flash`，与卡头钉定值一致，无静默换模。**
- 同一 `profile.bind` 的 `activeToolNames` 实读，是 D3（无法下沉子智能体）的**直接证据**：工具面为 `NotifyUser / Bash / Cron* / Edit / EnterPlanMode / ExitPlanMode / Glob / Grep / Read / ReadMediaFile / Skill / TaskList / TaskOutput / TaskStop / TodoList / WaitFor / WebSearch / FetchURL / Write / mcp__*`，其中 `TaskList/TaskOutput/TaskStop` 只用于**查看/控制后台任务**，**不含任何 Agent/Task 派发（spawn）工具**。

## 七、阶段七 · 偏差与异常登记（**含落点**）

| # | 类型 | 事实与证据 | 落点 / 处置 |
|---|---|---|---|
| **D1** | 步骤顺序微调（**为满足卡内两条硬约束的必然取舍**） | 卡 §3/§4 在 §5 之前，但 §5 又要求「feat 笔提交前最后一次 diff sha 复核 = 锚」；而 4 件补档 + M2 一旦落工作树，`git diff HEAD -- .` 字节必变（不再等于锚）⇒ 本批执行序 = **先 feat 笔（此时工作树恰为锚态，复核 EQUAL）→ 落 4 件补档 + M2 → docs 笔**。两笔内容与卡一致（11 路径 / 5 路径），锚核验**未打折**（§1-2、§4、§6-1 三重留证） | 落点 = 本节 D1；后续卡片若沿用同口径，建议把「feat 笔先落」写进卡面步骤序，消除歧义 |
| **D2** | **CI 红（唯一非绿项）：E2E job `failure`** | run `38222153002` step 12「运行 E2E（29 用例）」**exit 1**（`.github` 侧 annotation = `failure / Process completed with exit code 1`）。**对照留证**：前一 run（`head_sha=4260651`，第 20 期）E2E = **success** ⇒ 本批引入新红，非历史性红。**日志/证据件匿名取不到**：job logs = **403**（`Must have admin rights`）、artifact `e2e-evidence`(184258B) 下载 = **401**（`Requires authentication`），本机无 `gh` CLI、无 `pwsh` ⇒ **未取到失败断言原文（如实申报：结论为「哪一步红」确定、「哪条断言红」未证实）** | 上报，**禁自行修补**（卡 §纪律）：未改任何代码、未重跑、未二次 push、未 force、未动卡外文件。**候选机理与建议落点（供裁决，均属推断、未由日志证实）**：① **退出码闸与 C-3 注记不同轴** —— `scripts/verify-e2e.ps1:2311-2312`（R-137 收口修补的「Ok/No 不计入主用例计数」注记）只豁免**打印计数**，而 `No()` 实增 `$script:failed`（`:78-80`），`:2412` 判据 `if ($script:failed -gt 0 -or $script:gfskip14 -ge 4) { exit 1 }` ⇒ **新增 MAJOR-2/MAJOR-3 两块任一战败即直接红 E2E job**（口径注记与实际闸门不一致，是本批最可疑的结构性缺口）；② **MAJOR-3 源断言本地实读命中**（`frontend/src/components/SpreadGrid.vue:225 watch(() => props.readOnly, applyReadOnly)`、`:219 isProtected = props.readOnly`，两条正则均命中）⇒ 该块非首要嫌疑；③ 首要嫌疑 = **MAJOR-2 运行期断言**（终态任务四类作业 409 `TASK_FINALIZED` + 不留行；涉及 3 个新任务构造与取消联动）或**既有 TC 与新终态守卫的真跑交互**（新守卫居 `createAndStart` 首道，可能改变终态任务上的既有错误码路径，如 `PRECHECK_NOT_PASSED → TASK_FINALIZED`）。**建议下一步 = 由有权面（管理员 token / `gh`）取 job 日志或 `e2e-evidence` 归档后定论**；本地复现受 `pwsh` 缺失限制（仅 PS 5.1） |
| **D3** | 子智能体下沉**未执行** | 卡允许「机械活可再下沉（显式钉 model=DeepSeek/deepseek-flash）」，但本 run 的可用工具面**不含派发工具**（证据 = 自身 `profile.bind` 的 `activeToolNames`，见 §6-2）⇒ 无法产出合规的 agent 目录与 wire，**全部机械活（锚比对 / 门禁复跑 / 脱敏 / 提交 / 轮询）由本 agent 亲跑**（本 agent 自身即 `DeepSeek/deepseek-flash`，署名一致） | 落点 = 本节 D3；后续如仍需下沉，派发侧需给到「可派发」的工具面 |
| **D4** | 脱敏口径说明（**非偏差，留痕备核**） | 卡面写「仓外盘符路径 → `<MAIN_REPO>` 或相对表述」：本批 4 件源件**零盘符路径**，仓外件路径按仓内既有先例（45b2c6a / 4260651）统一用 `<SCRATCH>\r137-guard\…` 表述；`<MAIN_REPO>` 保留其原义（主仓库根，指本仓路径）以免张冠李戴。替换计数 = 0 | 落点 = §2；若审方要求仓外件一律用 `<MAIN_REPO>`，属口径可调项（不涉事实） |
| **D5** | 后端复跑命令口径 | 按卡指定脚本 `<SCRATCH>/redteam-batch-a/.run-fix/mvn-run.sh`（透传型），实跑 `-B test`（**未加 `clean`**）；surefire XML = **73**，与卡期望值一致（本批新增 3 类未改变 XML 总数口径） | 落点 = §1-3 |
| **D6** | 非阻断告警（外部因素） | CI annotation 中含 2 条环境级告警：`actions/upload-artifact@v5` 的 Node 20 弃用提示、`ubuntu-latest` 将于 2026-10-19 迁移 Ubuntu 26 的 notice —— 二者与本批改动无关 | 落点 = 本节 D6（无需处置） |

**未发生项（如实声明）**：未进入 `<SCRATCH>\ai-example` 与 `<SCRATCH>\ai-example-main` 两目录（红线零操作）；未读任何 `.env`；未落任何 AI key 明文；未 force push、未改历史、未动卡外文件；未使用 `git add -A`；未启用扫描逃生口；CI 红后**未自行修补**。

## 八、本批产出物与取证件清单（全部落 `<SCRATCH>\commit-gov\p21\`，除入库件外均仓外）

- **入库件（仓内）**：`docs/evidence/补档-R137-{施工报告,复核报告-DSPro,红队报告-K28,收口修报告}.md`（4 新增）+ `docs/M2-排期计划.md`（1 行尾补注）+ 11 路径代码/测试笔。
- **取证件（仓外）**：`porcelain-before.txt`、`diff-repro.diff`（锚重生成）、`diff-precommit.diff`（提交前复核）、`diff-feat-commit.diff`（**提交对象 = 锚字节**）、`mvn-test.log`（466/73 XML）、`frontend.log`（216/19 + 退出码）、`check-docs.log` / `check-docs-after.log`（6/6 ×2）、`build-banners.mjs` + `staged/`（脱敏件与逐字节保真复算）、`msg-feat.txt` / `msg-docs.txt`、`ci-poll-p21.mjs` + `ci-poll.log`、`ci-run-p21.json` / `ci-jobs-p21.json`（**run 38222153002 五 job 结论**）、`artifacts-p21.json`（E2E 证据件清单）、`annotations-p21.json`（check-run 注解 = `exit code 1`）、`e2e-job-log.txt`（403 原文，证明日志不可匿名取得）。

---

# ai-example 合流项目 · 提交官报告（第 22 期：**看板落库** —— docs 小批次，**DC-20：红队豁免、机械验收 + 脱敏终扫不豁免**，单笔 docs 提交；CI 四绿一红：E2E job `failure`（已知在修项，非本笔引入））

## 0. 结论速览

- **落库面**：`docs/任务看板.md`（10553 B）+ `docs/决策看板.md`（5103 B），**2 路径全新增**、零改零删、零夹带。
- **单笔提交**：`921da76`（`docs(kanban): 新增任务看板与决策看板（人看进度/决策流水）`）⇒ `git push origin main` = `b1f8f22..921da76`（`PUSH_EXIT=0`，**无 force**）。
- **零夹带双锚**：写入后与源件 `cmp` 逐字节 **EQUAL（2/2）**；提交对象 blob 与源件 `cmp` **EQUAL（2/2）**，且 `git hash-object` 源件/入库件**同值**。
- **脱敏终扫六类**（盘符路径 / 用户名 / 组织名字面 / 内网 IP / 密钥族 / 命名纪律含零分隔连写）：**硬命中 0 处**；`AI_SECRETS_ALLOW` **未启用**（硬命中为 0，无放行需求）。
- **门禁**：`node scripts/ci/check-docs.mjs` ⇒ **6/6** 断言绿；`git ls-files` **1046 → 1048**（实测两次：暂存态 / 提交后各 1 次均 6/6）。
- **CI**：run **`38224188131`**（`head_sha=921da76…`）5 job **4 绿 1 红**，唯一红 = E2E job `failure`（step 12），与第 21 期 R-137 笔同型，**已知在修项**（`docs/任务看板.md` 首行即登记「R-137 ⚠️ CI E2E 红·热修在途」）。

## 一、自证（DC-19：实际模型 / 会话 / 本棒 id）

- **本棒 id = `agent-661`**；agent 目录 = `<KIMI_HOME>\sessions\<WORKSPACE>\<KIMI_SESSION_B>\agents\agent-661\`（`wire.jsonl` 137141 B）。
- **wire 实证**：`wire.jsonl` **第 5 行**首条 `profile.bind` = `{"type":"profile.bind","agentId":"agent-661","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",…}`；全文件 `"modelAlias":"…"` 取值**唯一** = `DeepSeek/deepseek-flash`（40 次，**0 混杂**）⇒ **本 run 实际模型 = `DeepSeek/deepseek-flash`，与卡头钉定值一致，无静默换模**。
- **任务标志串交叉定位**（同批 `agent-657…661` + `main` 六个 wire 逐串 grep）：`desense6-scan`（本棒自建扫描器名）命中 **agent-661 = 7**、其余五者**全 0**；`921da76`（本笔短哈希）命中 agent-661 = 22 / agent-658 = 17（派单侧）/ 其余 0 ⇒ **agent-661 = 本 run 唯一对应 wire**。
- 工具面实读（同条 `profile.bind` 的 `activeToolNames`）= `NotifyUser / Bash / Cron* / Edit / EnterPlanMode / ExitPlanMode / Glob / Grep / Read / ReadMediaFile / Skill / TaskList / TaskOutput / TaskStop / TodoList / WaitFor / WebSearch / FetchURL / Write / mcp__*`，**不含任何子智能体派发（spawn）工具** ⇒ 本棒全部机械活（拷贝 / `cmp` / 脱敏复扫 / 门禁 / 提交 / 轮询）**由本 agent 亲跑**，无下沉（DC-20 卡面本就未要求下沉）。

## 二、零夹带与机械验收（`cmp` 逐字节 EQUAL）

1. **写入**：`cp <SCRATCH>\kanban\任务看板.md → docs/任务看板.md`、`cp <SCRATCH>\kanban\决策看板.md → docs/决策看板.md`（卡面两条复制指令，源件路径按既有先例以 `<SCRATCH>` 表述）。
2. **写入后 `cmp`（2/2 EQUAL）**：
   - `cmp <SCRATCH>\kanban\任务看板.md docs/任务看板.md` ⇒ **EQUAL**
   - `cmp <SCRATCH>\kanban\决策看板.md docs/决策看板.md` ⇒ **EQUAL**
   - `sha256sum` 源件 ↔ 入库件**同值**：任务看板 = `172da89ff93be691c66f9ea74dd28a199485176ec7e6f2ecfad1e61932a9f1db`；决策看板 = `e990755fdb7b5e53d1ea6500e4dfd8092840bd6efafa9916e50127fc7e7e24db`。
3. **提交对象再验（比工作树更强的口径：直接核已入库 blob）**：
   - `git cat-file -p 921da76:docs/任务看板.md > blob-taskboard.md` ⇒ 与源件 `cmp` **EQUAL**
   - `git cat-file -p 921da76:docs/决策看板.md > blob-decision.md` ⇒ 与源件 `cmp` **EQUAL**
   ⇒ **入库字节 = 源件字节**，零改写、零换行漂移（`core.autocrlf=true` 下两件均为 LF，clean 过滤不改字节）。
4. **暂存纪律（卡 §4 硬约束）**：`git add` **逐路径 2 次**（`git add -- docs/任务看板.md docs/决策看板.md`），**未用** `-A` / `.` / 任何其他路径。
   - 提交前 `git status --porcelain --untracked-files=all`（实测照贴，**仅 2 条**）：
     ```
     ?? "docs/任务看板.md"
     ?? "docs/决策看板.md"
     ```
   - `git diff --cached --name-only` = **恰 2 路径**；`git diff --cached --stat`：
     ```
      docs/任务看板.md | 105 +++++++++++++++++++++++++++++++++++++++++++++++++++++++
      docs/决策看板.md |  41 ++++++++++++++++++++++++++++++
      2 files changed, 146 insertions(+)
     ```
   - **卡面预告的「工作树含 R-150 施工在途改动」本次未观察到**：提交前工作树除本笔 2 件外**零改动、零其他未跟踪件**（见上照贴，`git status --porcelain` 全量仅 2 条）⇒ R-150/K2.8 在途改动未落在本仓工作树，故「只加这两条」的约束**未遭遇压力面**，如实留痕（落 §七 D1）。
   - 提交后 `git status --porcelain` = **0 条**；`git rev-list --left-right --count origin/main...HEAD` = `0  0`。

## 三、脱敏终扫六类（**独立复核**，不沿用上一遍的自证）

- **扫描器**：本棒自建 `<SCRATCH>\commit-gov\p22\desense6-scan.mjs`（与仓内 `scripts/ci/check-docs.mjs` 断言⑤ 及 `scripts/githooks/scan-lib.sh` **相互独立**的第三份实现）。六类 = ① 盘符路径（`<DRIVE>/` / `<DRIVE>/` / `<DRIVE>/`）② 用户名（`git config user.name` 及其 ≥4 字片段 + 用户目录形态 + 主机名）③ **组织名字面**（运行时从 `git remote get-url origin` 解析 owner 及其 ≥4 字片段，**脚本内不落字面量**）④ 内网/本机 IP（RFC1918 + 环回 + 链路本地）⑤ **密钥族**（与 `scan_rule_secret` 同族 20 条，前缀按 DC-08 拼接构造）⑥ 命名纪律（与断言⑤ **逐字同口径**：NFKC 折叠 + 隐形字符双态 + 去分隔符连写 + 词首守卫）。
- **规则自检（防"规则被改窄后静默全绿"）**：正向种子 **21/21 命中**、负对照 **10/10 未误伤**（`SEED_EXIT=0`）。种子覆盖六类各形态：盘符 3 形态、用户名、组织名、IP 4 形态、密钥族 4 族、命名 8 形态（连字符/下划线/零分隔连写/全角连字符/U+2011/零宽/软连字符/全大写）；负对照含域名尾部、裸前缀/裸后缀、公网 IP、非私网 192.169/172.32、无盘符冒号、`docs/` 相对路径、短串 `s k-` 前缀、`Bearer <token>` 占位（**全 CLEAN**）。
- **实盘复扫（2 件全量、逐行）**：
  ```
  [扫毕] docs/任务看板.md（10553 B）
  [扫毕] docs/决策看板.md（5103 B）
  实盘六类复扫：2 个文件，命中 0 处    （REALSCAN_EXIT=0）
  ```
  ⇒ **六类硬命中 0 处**；两件本即零命中，**零替换、零豁免**。
- **旁证（三条独立通道同向）**：① 仓内 `pre-commit` 钩子（`scan-lib.sh`）输出 `✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`；② 手工以 `scan-lib.sh` 同管道对暂存 diff 预扫（`<SCRATCH>\commit-gov\p22\staged.diff`，16121 B）⇒ **零命中**；③ 提交信息文本复扫（`msgscan.mjs`，六类同口径含命名连写）⇒ **0 命中**。
- **逃生口**：`AI_SECRETS_ALLOW` **未设置/未启用**（硬命中为 0，无放行需求）；**未用** `--no-verify` / `--allow-secrets`。

## 四、门禁实测（`node scripts/ci/check-docs.mjs`）

| 时点 | `git ls-files` | 结果 |
|---|---|---|
| 复制后（未暂存） | **1046** | **6/6** 绿 |
| 暂存后（2 件入 index） | **1048** | **6/6** 绿 |
| 提交后（HEAD = `921da76`） | **1048** | **6/6** 绿 |

- 提交后实测全文：
  ```
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的仓库内文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 1048 个路径，实扫文本 1045 个；豁免 3 个）
         （规则自检：连写组 5/5 例「仅第三态可捕获」（下限 5）；分隔组 24/24 例经前两态捕获（下限 24）；负对照 11 全不误伤）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建

  全部通过：6/6 断言绿。
  ```
- **卡面预期吻合**：`git ls-files` **1046 → 1048**（= 1046 + 本笔 2 件），实测一致。
- 断言①③④ 的扫描面只含 `README.md` / `docs/README.md` / `scripts/README.md` / `scripts/githooks/README.md`，两件看板**不在其内**；断言⑤ 按 `git ls-files` 全量 ⇒ 本笔两件**已入 ⑤ 扫描面且未致红**（上表 1048 即含两件）。

## 五、提交与 push

| 项 | 值 |
|---|---|
| commit | **`921da769ffcd47647165bdf9b2b34023309283d1`**（短哈希 **`921da76`**） |
| subject | `docs(kanban): 新增任务看板与决策看板（人看进度/决策流水）` |
| 路径 | **2**（2 新增：`docs/任务看板.md` / `docs/决策看板.md`） |
| 增删 | `2 files changed, 146 insertions(+)`（105 + 41） |
| blob | 任务看板 = `f32cd2c4c31f5063b1c34e9895ebcb9e759d0d2f`；决策看板 = `36fe12fbda5cbca118fd4ef346d8162c0980b9ea` |
| message 草稿 | `<SCRATCH>\commit-gov\p22\msg-docs.txt`（仓外；`git commit -F` 投喂，含 blob sha / cmp 结论 / 六类复扫结论 / `ls-files` 1046→1048 实测） |
| push | `git push origin main` ⇒ **`b1f8f22..921da76  main -> main`**（`PUSH_EXIT=0`，**无 force、无改历史**） |
| 提交后对账 | `origin/main` = `921da769ffcd47647165bdf9b2b34023309283d1`；`git log --oneline -3` 线性 = `b1f8f22 → 921da76`；`ahead/behind = 0/0` |

- **message 合规**：仿近期 docs 笔格式（`docs(m2,evidence): …（…）` 一族）改写为 `docs(kanban): …`；**0 组织名字面、0 盘符路径、0 用户名、0 命名纪律形态**（`msgscan.mjs` 六类复扫 **0 命中**，含零分隔连写态）。

## 六、CI 状态（run `38224188131`）

- **轮询口径**：匿名 REST、**间隔 170s（≥90s 卡面下限）**、`curl --ssl-no-revoke`、共 3 轮 API（远低于 60/h 限流）；**组织名 0 字面**（脚本运行时由 remote 拼接，报告内不落字面量）。
- **run 时间线**：
  | 轮 | 时间（UTC） | 状态 | jobs |
  |---|---|---|---|
  | 1 | `08:32:40` | `in_progress` | **4**（E2E 未现，符合卡面预告） |
  | 2 | `08:35:32` | `in_progress` | **5**（E2E 出现，`in_progress`） |
  | 3 | `08:38:25` | **`completed` / `conclusion=failure`** | 5 全 completed |
- **job 级结论（卡面要求：只核 job 级状态留证）**：

| job | conclusion | 步骤证据 |
|---|---|---|
| 后端编译与单测 | **success** | 7 步全 success |
| 全历史脱敏扫描（报告制 · 不计门禁） | **success** | 6 步全 success |
| 前端类型检查与构建 | **success** | 10 步全 success |
| **文档一致性门禁（check-docs）｜阻断** | **success** | 7 步全 success ⇒ **本笔新增 2 件未致门禁红** |
| E2E 全流程（29 用例 · 桩上游 + redis） | **failure** | **step 12「运行 E2E（29 用例）」`completed/failure`**；其前置 11 步（Initialize containers / checkout / JDK / Node / 打包后端 / 桩自检 / 桩启动 / 后端启动 / 健康就绪 / 种子就绪）全 success；step 13「归档证据」success |

- **红项定性（非本笔引入，明确不计本笔失败）**：E2E 红与**第 21 期 R-137 笔 run `38222153002` 同型同点**（同 job、同 step 12、同 `exit 1`）；本笔为**纯文档新增（2 件 .md，零代码零配置零脚本）**，`frontend/` / `backend/` / `scripts/` 字节未动 ⇒ E2E 红**不可能由本笔引入**，属 R-137 已知在修项（`docs/任务看板.md` 表内已登记「⚠️ CI E2E 红·热修在途」，`docs/决策看板.md` 亦登记「CI E2E 红热修在途」）。**按卡 §5 不视为本笔失败**；呈报侧未重跑、未修补、未二次 push。

## 七、偏差与异常登记

| # | 类型 | 事实与证据 | 落点 / 处置 |
|---|---|---|---|
| **D1** | 卡面预告条件**未出现**（**非偏差，留痕备核**） | 卡 §4 预告「工作树**可能**含 R-150 施工在途改动（K2.8 在途，属正常批中态）」；实测提交前 `git status --porcelain --untracked-files=all` **全量仅 2 条**（即本笔 2 件未跟踪），**零 R-150 在途改动、零其他未跟踪件** ⇒ 该条件为「可能」而非「必有」，本笔未命中；「只加这两条」的约束因此**未遭遇压力面**，暂存隔离性反而是**最干净的形态**（cached 恰 2 路径，见 §二 4） | 落点 = 本节 D1；无处置需求（若有 R-150 在途改动同批落树，仍按逐路径 `add` 执行，不受影响） |
| **D2** | CI 红（唯一非绿项，**已知在修项**） | run `38224188131` E2E job `failure`，step 12「运行 E2E（29 用例）」`completed/failure`。**对照留证** = 前一 run `38222153002`（`head_sha=b1f8f22`，第 21 期 R-137 笔）E2E 同为 `failure`、同 job 同 step ⇒ **非本笔引入**；本笔 2 件 .md 零代码面 | **不处置**（卡 §5 明示「E2E 红为已知在修项，不视为本笔失败」）：未重跑、未改码、未 force。E2E 根治归 R-137 热修面 |
| **D3** | 未取 job 日志（**如实申报**） | 卡面仅要求「**只核 job 级状态**」，已达标（§六表）；匿名 API 侧 job logs 需 admin 权限（第 21 期实测 403），本笔**未尝试**取日志原文 ⇒ 「哪一步红」确定（step 12）、「哪条断言红」**未证实且本笔无需证实** | 落点 = 本节 D3；如需断言级定位，归 R-137 热修面由有权面取证 |
| **D4** | 非阻断项 | 两件看板正文含 `docs/M2-排期计划.md` 等**仓内相对路径引用**，非引用文件存在性断言的扫描面（①③ 仅扫 4 个 README），故不触发断言③；两件亦不在 NAV_DOCS 内 ⇒ **不影响门禁口径** | 落点 = 本节 D4（知悉项，无需处置） |
| **D5** | **并发在途改动后到（卡面预告条件的真实命中面，如实留痕）** | 本笔 **提交 + push 完成之后**（commit `08:31Z` 级、push 后），工作树**新出现**一条外部改动：`git status --porcelain` = ` M frontend/tests/parts-residue-cleanup.spec.ts`（实测 mtime = `2026-10-12 16:39:37 +0800`，`git diff --stat` = **1 file changed, 22 insertions(+), 8 deletions(-)**）。**非本棒所为**：`git show --stat 921da76` 证实本笔**仅含 2 件 docs**，未触碰 `frontend/`；即卡 §4 预告的「R-150 施工（K2.8 在途）并发落树」形态 —— 它在本笔**暂存窗口之后**才落到工作树，故本笔暂存隔离性未被污染（cached 恰 2 路径，§二 4 留证） | **不处置**（卡 §纪律：禁自行修补、禁动卡外文件）：本棒**零触碰**该文件、未 `add`、未 commit、未还原；如实上报，归 R-150/K2.8 施工棒 |

**未发生项（如实声明）**：未用 `git add -A` / `.`；未 add 任何卡外路径；未 force push、未改历史、未 rebase；未启用 `AI_SECRETS_ALLOW` / `--no-verify`；未读任何 `.env`；未落任何密钥明文；未进入红线目录；CI 红后**未自行修补/未重跑**；未动卡外仓库文件；**未触碰** post-commit 后并发出现的 `frontend/tests/parts-residue-cleanup.spec.ts`（D5）。

## 八、本批产出物与取证件清单（取证件全部落 `<SCRATCH>\commit-gov\p22\`，均仓外）

- **入库件（仓内）**：`docs/任务看板.md`（blob `f32cd2c4…`）+ `docs/决策看板.md`（blob `36fe12fb…`），单笔 `921da76`。
- **取证件（仓外）**：`desense6-scan.mjs`（六类扫描器，自建）+ `seed.log`（**正向 21/21 / 负对照 10/10**）、`realscan.log`（**六类 0 命中**）、`msgscan.mjs` + `msgscan.log`（提交信息 0 命中）、`porcelain-before.txt` / `porcelain-after-add.txt` / `porcelain-after-commit.txt`、`diff-cached-stat.txt` / `diff-cached-names.txt`（cached 恰 2 路径）、`staged.diff`（16121 B）、`sha256.txt`、`blob-taskboard.md` / `blob-decision.md`（**提交对象字节 ↔ 源件 `cmp` EQUAL**）、`check-docs.log`（1046，复制后）/ `check-docs-staged.log`（1048，暂存态）/ `check-docs-postcommit.log`（1048，提交后）、`msg-docs.txt`、`commit.log`、`push.log`、`ci-poll-p22.mjs` + `ci-poll.log`、`ci-run-p22.json` / `ci-jobs-p22.json`（**run 38224188131 五 job 结论 + E2E step 12 定位**）、`porcelain-postpush-d5.txt`（**D5 并发在途改动照贴**：` M frontend/tests/parts-residue-cleanup.spec.ts` + mtime + diffstat）。

**落库段结束（第 22 期 · 看板落库）。**
