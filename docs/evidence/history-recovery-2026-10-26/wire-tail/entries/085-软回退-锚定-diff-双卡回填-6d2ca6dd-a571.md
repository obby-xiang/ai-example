> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-571` 的 wire 末条 assistant 文本（消息序号 m=27；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 09:34 → 2026-10-12 09:36（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`d60c4a6e88640a6fab63199265010ec9101c2bfd`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：9465 B / 7295 字符 / 108 行；行指纹覆盖率（本批复算）7.9%（最高单文件 7%：`docs/evidence/history-recovery-2026-10-26/reports/ui-title-open-2026-10-10-顶部菜单精简与标题入口.md`）
> - 类别：**施工复核**
> - 与既有件关系：ui-title-open；报告口径覆盖率 0.07
> - 脱敏：路径 9 / 会话标识 2 / 身份 0 / 密钥 0 / 命名纪律 1
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
四步全部完成。以下是完整回报。

## 【1】软回退 — 已执行并通过全部核验

**施工前现状**（`<REPO_ROOT>`）：`git rev-parse HEAD` = `d60c4a6e88640a6fab63199265010ec9101c2bfd` ✓；`git status --porcelain` 空 ✓；`git rev-list --count origin/main..HEAD` = `3` ✓。三笔 commit：`2030c24` step1（9 files, +694/−27）、`ca84ec9` step2（5 files, +767/−47）、`d60c4a6` step3（9 files, +1395/−52）。

**执行**：`git reset --soft 6c9c699aa680297ecf0a83ec39eeae8716b68431` → HEAD = `6c9c699aa680297ecf0a83ec39eeae8716b68431`，`rev-list --count origin/main..HEAD` = `0`（HEAD 已回到 origin/main）。

**回填后 `git status --porcelain`（16 项，全部 staged，第二列全空）**：

```
M  frontend/src/api/jobs.ts                    A  frontend/tests/import-watch-recycle.spec.ts
M  frontend/src/api/tasks.ts                   A  frontend/tests/job-watch-channel.spec.ts
M  frontend/src/components/AiPanel/AiPanel.vue A  frontend/tests/polling-controller.spec.ts
M  frontend/src/stores/task.ts                 A  frontend/tests/polling-predicate.spec.ts
M  frontend/src/utils/job-watch.ts             A  frontend/tests/task-events-lifecycle.spec.ts
A  frontend/src/utils/polling.ts               A  frontend/tests/task-events.spec.ts
A  frontend/src/utils/task-events.ts           A  frontend/tests/wait-job-final.spec.ts
M  frontend/src/views/ImportWizardView.vue
M  frontend/src/views/TaskCenterView.vue
```

`git diff --stat`（工作树 vs index）= **空**，即全部改动都在 index，无半途游离改动。

**`git diff --cached --stat` 全量统计（照贴）**：

```
 frontend/src/api/jobs.ts                     |  32 +-
 frontend/src/api/tasks.ts                    |  43 ++-
 frontend/src/components/AiPanel/AiPanel.vue  |  27 +-
 frontend/src/stores/task.ts                  | 272 +++++++++++++++--
 frontend/src/utils/job-watch.ts              | 227 +++++++++++---
 frontend/src/utils/polling.ts                | 264 +++++++++++++++++
 frontend/src/utils/task-events.ts            | 168 +++++++++++
 frontend/src/views/ImportWizardView.vue      |  19 +-
 frontend/src/views/TaskCenterView.vue        |  14 +-
 frontend/tests/import-watch-recycle.spec.ts  | 153 ++++++++++
 frontend/tests/job-watch-channel.spec.ts     | 328 +++++++++++++++++++++
 frontend/tests/polling-controller.spec.ts    | 424 +++++++++++++++++++++++++++
 frontend/tests/polling-predicate.spec.ts     | 139 +++++++++
 frontend/tests/task-events-lifecycle.spec.ts | 283 ++++++++++++++++++++++
 frontend/tests/task-events.spec.ts           | 361 +++++++++++++++++++++++
 frontend/tests/wait-job-final.spec.ts        | 162 ++++++++++
 16 files changed, 2823 insertions(+), 93 deletions(-)
```

**后端零改动红线复核**：`git diff --cached --stat -- backend/` = **空**；`git status --porcelain -- backend/` = **空**；`git diff --cached --name-only | grep -v '^frontend/'` 无输出 —— 16 项全在 `frontend/` 下。

**改动清单与 3 笔 commit 比对**：

| 项 | 值 |
|---|---|
| staged 文件数 | 16（`frontend/src/` 9 + `frontend/tests/` 7） |
| 3 笔 commit 文件并集 | 16（`diff 6c9c699..d60c4a6` 与 staged 集合 `diff` 逐行一致） |
| 新增文件 | `frontend/src/utils/polling.ts`、`frontend/src/utils/task-events.ts`（+7 个新 spec，共 9 段 `new file mode`） |
| 净 diffstat | 16 files, +2823/−93 |

与题目预期完全一致：src 9 文件、tests 7 spec、新增 `utils/polling.ts` + `utils/task-events.ts`。

**最强复现性证据**：`git write-tree` = `2c0fdebd33c1716be8ab6e230a572d1672648b7b`，与 `git rev-parse d60c4a6^{tree}` **逐字节相同** —— 软回退后的 index 树与 d60c4a6 树完全等价，还原无失真（`6c9c699^{tree}` 为 `8f5aba8b…`，即基线树）。

附注：3 笔 commit 的 `--stat` 逐笔相加为 +2856/−126，与净额 +2823/−93 的差额全部来自中间提交的往返改写（同文件在多笔中反复增删），非夹带。

## 【2】锚定 diff — 已生成并锚定

- 路径：`<LOCAL_WORK>\polling-gov\diff-轮询一期.sorted.patch`（`git diff --cached` 导出，段序固定化 = 按文件路径字典序重排 `diff --git` 段，沿用 `_anchor-diff-b3a/b3b.mjs` 惯例）
- **sha256 `e97df56ef3d60f723f61918effb86d5e0cbb923504a35ac20aa120842782f016`**
- **字节数 131552**，**段数 16**，行数 3275，+2823/−93
- diffstat 汇总行：` 16 files changed, 2823 insertions(+), 93 deletions(-)`（与 `git diff --cached --stat` 尾行逐字相同）
- 段序：`frontend/src/api/jobs.ts` → `api/tasks.ts` → `components/AiPanel/AiPanel.vue` → `stores/task.ts` → `utils/job-watch.ts` → `utils/polling.ts` → `utils/task-events.ts` → `views/ImportWizardView.vue` → `views/TaskCenterView.vue` → `tests/import-watch-recycle.spec.ts` → `tests/job-watch-channel.spec.ts` → `tests/polling-controller.spec.ts` → `tests/polling-predicate.spec.ts` → `tests/task-events-lifecycle.spec.ts` → `tests/task-events.spec.ts` → `tests/wait-job-final.spec.ts`
- 保真校验（三道全过）：① `git apply -R --check` 退出码 0（16 段全通过，零写入）；② 9 个新增文件段用段内 `+` 行重建，与 index blob **逐字节相等**（如 `polling.ts` 10121B/10121B、`task-events.ts` 6812B/6812B、`task-events.spec.ts` 13987B/13987B）；③ 7 个修改段行数算术与 index/HEAD 行数自洽
- 生成器：`<LOCAL_WORK>\polling-gov\_anchor-polling-p1.mjs`（零 git 写，仅 `git diff --cached` 只读；三次重跑 sha256 稳定不变）
- `<LOCAL_WORK>\polling-gov\anchor-轮询一期.json`：`headBaseline: "6c9c699aa680297ecf0a83ec39eeae8716b68431"`、`diffSha256`、`bytes: 131552`、`segments: 16`、`generatedAt: "2026-10-12T09:35:55+08:00"`、`source: "git diff --cached after soft-reset from d60c4a6"`，另含 perSegment 逐段 sha/字节/增删、stagedStatus、indexTree==d60c4a6 树、DC-19 自证块。JSON 文件 sha256 = `3d1668df56cc5afea4326e1d053bd9766b83d899bf8e5c1ac3ced96ea7e2f415`

## 【3】两卡回填 — 已完成（Edit，非重写）

两卡填入同一组实测值：施工报告 sha256 `3f972434a10cf43b738cfbce1c22be27dbe2048e371480f1145d5a00c71abd27`（17404 B，与磁盘实测一致）；锚定 diff sha256 `e97df56e…f016`（131552 B / 16 段）。全卡 `【待回填】` 残留数 = **0/0**；行数保持原样（22 / 21 行，仅替换占位串，未增删行）。

| 卡 | 新 sha256 | 大小 |
|---|---|---|
| `<LOCAL_WORK>\polling-gov\复核卡-轮询一期-GLM.md` | `fde7661de2926a886cfc2bd341ca536aec4c74dccf43c3cdde81751217241559` | 2073 B / 22 行 |
| `<LOCAL_WORK>\polling-gov\红队卡-轮询一期-DSPro.md` | `4fb98a3ae3dc238627d98eaf8e59c433092e334578921cb9fa5a4e9dd23c743e` | 2239 B / 21 行 |

红队卡原文括注「——你审的就是这份字节，仓库状态若与之不符即为夹带证据」原样保留，仅在 sha256 占位处补入实测值与字节/段数。

## 【4】自证（DC-19）

| 项 | 实测值 |
|---|---|
| agent id | `agent-571` |
| 会话 | `session_<KIMI_SESSION>` |
| wire 路径 | `<KIMI_SESSIONS>` |
| wire modelAlias（`llm.request` 取证） | `DeepSeek/deepseek-flash` × 24/24 条（model=`deepseek-flash`，provider=`openai`，无第二别名） |

身份互证：该 wire 内含本任务标记 `软回退` ×15、`diff-轮询一期.sorted.patch`、以及本轮才产生的 patch sha256 `e97df56e…` —— 确认取证对象即执行者本人，非旁支 agent。wire 快照 347013 B / sha256 `ec8e77aa7603a394015291194a443ca243484ac7b6138217ac0c11723203d9f6`。

## 纪律合规

- 主仓库唯一 git 写 = `reset --soft`：`git reflog` 顶端确为 `6c9c699 HEAD@{0}: reset: moving to 6c9c699…`，其下依次为三笔 commit，无第四类写（无 commit/checkout/stash/apply 落盘；`git stash list` = 0，`git apply` 仅用 `--check`）。未 push，`origin/main` 仍 = `6c9c699aa680297ecf0a83ec39eeae8716b68431`。
- `<LOCAL_WORK>\ai-example`（mtime 2026-10-04）与 `<LOCAL_WORK>\ai-example-code\ai-example-main`（mtime 2026-10-06）**未被触碰**，两者 mtime 均远早于本次作业窗口（09:28 起）。
- 未读取任何 `.env`；写入仅落在 `<LOCAL_WORK>\polling-gov\`（新增 `diff-轮询一期.sorted.patch`、`anchor-轮询一期.json`、`_anchor-polling-p1.mjs`，编辑两卡）。
- 说明一处轻微越界需报备：核验 index 树等价性时调用过 `git write-tree`。它只输出对象哈希、不改 ref/index/工作树，且该树对象已存在于对象库（等于 d60c4a6 的树），未新增对象。

## 待办 / 后续提示

- 两张审查卡现可直接派发：施工报告与锚定 diff 的 sha256 已闭合，审查方按卡内 sha256 核验即可；仓库处于"index 已含全部施工改动、工作树 = index"的未提交态，审查方 `git diff --cached` 即可看到与锚定 patch 同源的字节。
- 若后续对锚定 diff 有任何再生成，注意必须使用 `git diff --cached`（软回退后新文件已入 index，无需 `--no-index` 拼装），并保持路径字典序段序 —— 否则 sha256 会变、两卡锚点即失效。
- K3 若决定彻底放弃一期施工，撤销路径是 `git reset --hard 6c9c699` 或 `git restore --staged --worktree .`；三笔 commit 对象仍在对象库可经 reflog 找回，未被销毁。
