> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# ai-example 合流项目 · 提交官报告

- 提交官：DeepSeek V4 Flash（提交官子智能体）
- 日期：2026-10-10
- 主仓库：`<REPO_ROOT>`（分支 `main`，远端 `https://github.com/obby-xiang/ai-example.git`）
- 起点 HEAD：`d3020ee`｜终点 HEAD：`6ee96c3`
- 红队材料：`<SCRATCH>\redteam-ui-pack\`（锚点 `bundle.sha256` = `1e63e0d5c4349f4554ed57d809f259e807b675d13d3890ef24dc705c32f967d1`）
- 平台：Windows + Git Bash｜`core.autocrlf=true`｜`core.hooksPath=scripts/githooks`
- 断言标注：【实测】= 本机命令实跑复现；【推断】= 由实测证据逻辑推出

---

## 一、提交前硬性核验（第一步）

### 1.1 工作树状态比对

```
$ git -c core.quotepath=false status --porcelain    # 【实测】
```

- 现态 = 恰 18 项（16 ` M` + 2 `??`）。
- 与 `<SCRATCH>\redteam-ui-pack\status-snapshot.txt` 的 porcelain 段（第 7–24 行，18 行）做 `diff`：**逐行完全一致**（`STATUS_IDENTICAL`）。
- 结论：**无工作树漂移**【实测】。

### 1.2 锚点校验

```
$ cd <SCRATCH>/redteam-ui-pack && sha256sum -c bundle.sha256
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
- 附带核对：HEAD = `d3020ee2d245c3c38a803c320f0ff90248d5d270`、分支 `main`、远端 `obby-xiang/ai-example`【实测】。

**第一步结论：全部通过，无 BLOCKER，未停手。**

---

## 二、脱敏终扫（第二步）

### 2.1 植入对照先行（证明扫描器活着）

自建独立扫描器 `<SCRATCH>\commit-gov\desense-scan.mjs`（与仓库门禁相互独立），覆盖四模式：
① 盘符绝对路径 ② 本机身份（用户名/主机名/用户目录）③ 内网或本机 IP（RFC1918+环回+链路本地）④ 命名违禁（与门禁断言⑤ 同规则：NFKC 折叠 + 隐形字符双态 + 去分隔符连写 + 词首守卫）。

```
$ node desense-scan.mjs <repo> seed
正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记；exit=0     # 【实测】
```

- 正向种子含：`E:\…` / `D:/…` / `c:/…` / `/e/…`、用户名与用户目录、`10.x` / `192.168.x` / `172.16.x` / `127.0.0.1`、命名违禁的**全部拆形形态**（连字符/下划线/空格/点/零分隔连写/全角连字符 U+FF0D/粘贴破折号 U+2011/零宽 U+200B/软连字符 U+00AD/全大写/混合大小写）——**23/23 全命中**。
- 负对照：裸前缀、裸后缀、域名尾部、词尾含后缀、前字紧邻、`E:temp`、公网 `8.8.8.8`、非私网 `192.169.x` / `172.32.x` / `11.x`、近似用户名 `Obbi`、短词根 `Leno`——**12/12 未误伤**。
- 向安全侧保守过匹配（已登记知悉，不构成缺陷）：形似 IP 的越界段 `<LAN_IP>`、以 `10.`/`127.` 开头的点分版本号 `v10.2.3`。

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
| `scripts/ci/check-docs.mjs:89` 的 `D:/…` | **已知豁免段**（断言① 正则示意串注释）。该行在本扫描器下**未触发**（路径正则要求盘符后至少 1 个路径字符，`…` 不在字符类内），登记为"豁免且未命中"【实测】 |
| 3 个 spike 脱敏脚本的匹配源正则 | 仅出现在全仓基线扫描中（6 处），与门禁 `NAMING_EXEMPT` 豁免面一致，**不在本批 18 项内**【实测】 |

**四项模式在 18 项中的最终判定：**

| 模式 | 18 项内结果 |
|---|---|
| ① 盘符绝对路径 | **0 处**【实测】 |
| ② 本机身份（用户名 `<USER>` / 主机名 `<HOST>` / 用户目录） | **0 处**【实测】 |
| ③ 内网/本机 IP | 2 处，均为环回且为既存上下文行，按豁免放过（见上表）【实测】 |
| ④ 命名违禁 | **0 处**——门禁断言⑤ 全绿（918 路径全量扫：916 已跟踪 + 2 新入库）+ 独立扫描器同规则复扫 18 项（含未跟踪 2 项）亦 0 命中【实测】 |

**全仓基线（旁证上下文）**：916 个已跟踪文件全扫 = 2551 处（主机名 1075 + IP 1475 + 用户名形态 1），**盘符路径 0 处、命名违禁 0 处（豁免面外）**；其中用户名形态那 1 处经逐行核对为 GitHub 组织名 `obby-xiang`（`docs/merge/01-分支评价报告.md`，非本批文件）。基线状态与本批无关，仅用于佐证"本批 18 项在既有噪声之上没有新增泄露"【实测】。

### 2.3 扫描器自身的两处修正（防"扫描器假活"）

- 首轮"用户名"按宽松子串匹配，全仓出现 4 处 `<USER>` 命中，逐行核对**全部为 `yarn.lock` 中 `tinyglobby` 的假阳性** ⇒ 改**词边界**判定后复跑，种子/对照仍全绿【实测】。
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
To https://github.com/obby-xiang/ai-example.git
   d3020ee..6ee96c3  main -> main                    # 【实测】退出码 0
```

- 新 HEAD = `6ee96c331d89349af69db4b0d4b1246723f24db3`；`origin/main` 与之相同，`main...origin/main` 同步【实测】。

### 4.2 CI（匿名轮询，间隔 ≥90s）

- run id **38038313522**｜workflow「CI 门禁」｜head_sha `6ee96c3`｜**status = completed｜conclusion = success**
- 链接：https://github.com/obby-xiang/ai-example/actions/runs/38038313522

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
| 后端测试（XML 口径） | 包装脚本 `<SCRATCH>\commit-gov\mvn-wrap.sh`（内含全路径含空格 Maven：`<MAVEN_HOME>/bin/mvn.cmd`，`cd backend` 后 `mvn -B test`） | **`Tests run: 380, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS**（01:00 min）【实测】 |

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
| 本报告 | `<SCRATCH>\commit-gov\提交报告.md` |
| 独立脱敏扫描器 | `<SCRATCH>\commit-gov\desense-scan.mjs` |
| 植入对照日志（可复跑） | `node <SCRATCH>\commit-gov\desense-scan.mjs <repo> seed`（本报告 §2.1 已录全文） |
| 18 项实盘扫描结果 | `<SCRATCH>\commit-gov\scan-files.txt` + 控制台输出（见 §2.2） |
| 全仓基线扫描 | `<SCRATCH>\commit-gov\repo-baseline-scan.txt`（916 文件 / 2551 处） |
| 提交信息原文 | `<SCRATCH>\commit-gov\msg1.txt` … `msg5.txt` |
| Maven 包装脚本 | `<SCRATCH>\commit-gov\mvn-wrap.sh` |
| 后端测试全量日志 | `<SCRATCH>\commit-gov\mvn-test-head.log`（含 `Tests run: 380 …`） |
| CI 轮询日志 | `<SCRATCH>\commit-gov\ci-poll.log`、`jobs.json`、`run.json` |
| surefire 前后清单 | `<SCRATCH>\commit-gov\surefire-before.txt` / `surefire-after.txt` |

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
- 链接：https://github.com/obby-xiang/ai-example/actions/runs/38044103814

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
- 匿名 API 通过 `curl --ssl-no-revoke -s "https://api.github.com/repos/obby-xiang/ai-example/actions/runs?branch=main&per_page=3"` 及其 `/jobs` 端点实查（共 2 次调用）【实测】。

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
- 主仓库：`<MAIN_REPO>`（分支 `main`，远端 `obby-xiang/ai-example`）
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
To https://github.com/obby-xiang/ai-example.git
   6ee96c3..6e59e0c  main -> main                     # 【实测】退出码 0
```

- 新 HEAD = `6e59e0cd19cfcd615a7699e79c957659303474aa`；`git rev-parse origin/main` 与之相同，`## main...origin/main` 无偏差【实测】。

### 4.2 CI（匿名轮询）

- run id **38044103814**｜workflow「CI 门禁」｜head_sha `6e59e0c`｜**status = completed｜conclusion = success**｜created 2026-10-10T10:12:34Z（与 push 同刻）
- 链接：https://github.com/obby-xiang/ai-example/actions/runs/38044103814

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
8. **报告自身脱敏口径** —— 本条目路径一律用 `<MAIN_REPO>` / `<SCRATCH>` / `<COMMIT-GOV>` 占位；文中保留的 GitHub 组织名 `obby-xiang`（远端 URL 与 run 链接）属公开标识且为 CI 取证所必需，已知会被独立扫描器按"用户名形态"报出（与沿用条目同类的假阳性）；文中 `127.0.0.1` 为对第 3 条豁免命中的**引述**，非本机地址泄露。

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

> 2026-10-10 追加。范围 = `<MAIN_REPO>`（分支 `main`，远端 `github.com/obby-xiang/ai-example`）。
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
| run URL | https://github.com/obby-xiang/ai-example/actions/runs/38059580591 |
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
> **脱敏口径**：路径一律占位符 —— `<MAIN_REPO>` = 主仓工作树、`<COMMIT-GOV>` = 提交官工作目录、`<SCRATCH>` = 仓库外材料区。按题面保留 GitHub 公开组织名（`obby-xiang`）与环回地址 `127.0.0.1`。
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
To https://github.com/obby-xiang/ai-example.git
   03a6cbe..3d20de9  main -> main          # git push origin main，EXIT=0（未加 --force / --no-verify）
```

push 后 `git rev-parse HEAD origin/main` 两者同为 `3d20de96ee03040e583520d670bc55fd86d5c12c`；`git status -sb` ⇒ `## main...origin/main`（无 ahead / behind 计数）。

### 5.2 CI 守夜（匿名轮询，间隔 ≥90s，每轮 `runs` + `jobs` 各 1 次）

| 项 | 值 |
|---|---|
| run URL | https://github.com/obby-xiang/ai-example/actions/runs/38071612710 |
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
