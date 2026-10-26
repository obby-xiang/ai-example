<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-332 · 施工复核 · 一、机械验收（第一步）（fc21d1da / agent-107）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`scripts/githooks`、`scan-lib`、`pre-commit`。
> **稿态**：历史中间稿 —— 该 wire 第 `m27` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m93`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `agent-107`（`main` = 主会话）；原字符 2,952（链内 1 段、合计 2,952）。
> **链号 / 类别**：`MT-332` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m93`（2,620 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 3 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

提交完成，全流程机械验收通过。

## 一、机械验收（第一步）

`git status --porcelain` 恰好 9 项，与清单逐字比对一致（`?? scripts/ci/` 目录展开为 2 个文件）：

| 文件 | 状态 | numstat 核对 |
|---|---|---|
| `scripts/ci/stub-upstream.mjs` | 新增 | 745 行 |
| `scripts/ci/stub-routes.json` | 新增 | 148 行 |
| `docs/evidence/M2-T1.2-E2E门禁实施-DS-V4-Flash.md` | 新增 | 583 行 |
| `docs/evidence/M2-T1.2-独立复现-GLM-5.3.md` | 新增 | 197 行 |
| `docs/evidence/M2-T1.2-红队审查-GLM-5.3.md` | 新增 | 138 行 |
| `docs/evidence/M2-T1.2-红队修复复核-GLM-5.3.md` | 新增 | 109 行 |
| `scripts/verify-e2e.ps1` | 修改 | **+16/−8** ✅ |
| `.github/workflows/ci.yml` | 修改 | **+156/−1** ✅ |
| `docs/M2-排期计划.md` | 修改 | **+1/−0** ✅ |

无多无少，数字全部相符。起始 HEAD `7045c06`，分支 `main`，与 `origin/main` 同步。

## 二、脱敏复查

用 `scripts/githooks/scan-lib.sh` 引擎（`scan_init` + `scan_engine_blob`，全文件粒度，比 pre-commit 的「仅新增行」更严）扫描 9 个文件：**SECRET / PATH / USER 三规则全启用状态下零命中**（`SCAN_RE_USER=[<OWNER>|<USER>|<USER_FRAGMENT>]`，非空未跳过）。回填三份 GLM 文档后复扫仍零命中，提交后按 `HEAD:path` 对已入库 blob 再复扫，9 个文件全部零命中。

## 三、细分提交与 push

每个 commit 均通过 `scripts/githooks/pre-commit`（输出 `✓ 敏感信息扫描通过（暂存区无命中）`），未使用 `--no-verify`，未 force push：

| # | SHA | 标题 | 内容 | push 结果 |
|---|---|---|---|---|
| 1 | `af28755` | feat(ci): E2E 桩上游——零依赖 stub-upstream 与 tools[] 清单驱动路由表（M2-T1.2 S1-S4，含红队 P1/P2/P4 修复：等长重试不重置台账、异常兜底同步落盘、副本对账义务与 5 项上限自检） | 2 files, +893 | `7045c06..af28755  main -> main` ✅ |
| 2 | `7ad8e9e` | feat(ci): E2E 进 CI 门禁——脚本跨平台兜底/桩模式 GFSKIP 零容忍/五卡 SKIP 落盘，workflow 增 e2e job（needs backend、PR+push 阻塞、artifact 7 天、setup-node 钉版、jq 就绪门、种子就绪等待）（M2-T1.2 S5-S8，T12-E#1/#4/#7/#8，红队 P5/P6/P7） | 2 files, +172/−9 | `af28755..7ad8e9e  main -> main` ✅ |
| 3 | `a0e2c32` | docs: M2-T1.2 证据链与裁决留痕——实施/独立复现/红队审查/修复复核四份证据 + T4 增桩白名单事实源对账子项 | 5 files, +1028 | `7ad8e9e..a0e2c32  main -> main` ✅ |

三个 commit 的消息均经 UTF-8 消息文件（`git commit -F`）写入，中文与全角符号逐字保真。第 3 步的 `git add docs/evidence/` 只纳入 4 个新文件，无既有文档被误带（`git diff --cached --name-status` 已核对）。

## 四、最终状态

- `git status --porcelain`：**空（干净）**
- `git rev-parse HEAD` = `a0e2c32f569922d4f97200e03f2cf27b54d7eef4`
- `git rev-parse origin/main` = `a0e2c32f569922d4f97200e03f2cf27b54d7eef4` → **一致**
- `git ls-remote origin refs/heads/main` = `a0e2c32...` 确认远端真实更新

## 五、回填内容的处理方式（需你确认的一点）

三份文档的裁决原文以**纯追加**方式写入文末（`## 裁决结论（指挥官 K3）` 节内），未改动任何既有文字。因此原占位行「（留空，待指挥官裁决）」按「不动其他内容」的要求**予以保留**，现已位于结论段之上，语义上是一个已失效的占位符。已入库内容确认为：

- `M2-T1.2-独立复现-GLM-5.3.md`：`复核结论采信（指挥官 K3，2026-10-08）：8 条待复现项 7 成立 + 1 记账笔误（verify-e2e.ps1 diff −6→−7…`
- `M2-T1.2-红队审查-GLM-5.3.md`：`红队 7 条全部处置（指挥官 K3，2026-10-08）：P1 收敛契约等长绕过、P2 桩异常静默死亡——采纳…`
- `M2-T1.2-红队修复复核-GLM-5.3.md`：`复核结论采信（指挥官 K3，2026-10-08）：6/6 成立，红队修复轮正式成立。§2.7 就绪门竞态观察已裁决为 T12-E#8…`

若你要的是「用结论替换占位行」，那需要一次额外的单行编辑 + 一个 docs 提交（我未擅自执行，因为这与你「纯追加，不动其他内容」的指令字面相冲突）。请明示是否需要补这一次提交。

## 六、其他观察（均非阻塞）

- `scripts/ci/stub-upstream.mjs` 在磁盘上带执行位，但因本仓库 `core.fileMode=false`（Windows），入库为 `100644`。已核对 `ci.yml:130`、`ci.yml:139` 均以 `node scripts/ci/stub-upstream.mjs`（显式 node，非 `./`）调用，Linux runner 上不依赖执行位，无影响。
- 过程中创建的临时脚本与提交消息文件（`<LOCAL_WORK>\m2submit-*`）已全部删除，工作区无残留。
