> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-570` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：14407 B / 11095 字符 / 220 行；行指纹覆盖率（仓内 + 落盘语料）5.4%
> - 类别：**施工复核**
> - 脱敏：路径 33 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 定性结论：**D = 其它**（施工正确落在 `<REPO_ROOT>`；3 笔 commit 属施工卡明示授权且**未 push**；trae 线**零触碰、零污染**；报告「终态 HEAD=d60c4a6e、工作树干净」经核**为真**）

三项证伪：
- **B / C 被直接证伪**：trae 线仓库 `<LOCAL_WORK>\ai-example-code\ai-example-main` HEAD=`198604f4`，`git status --porcelain` 零输出，reflog 只有一条 `clone`，且 `d60c4a6e`、`6c9c699a` 两个对象在其库里**根本不存在**（`fatal: git cat-file: could not get object info`）—— 施工成果与 trae 线无任何交集，两仓库未被同时改动。
- **A 的「违规 commit」不成立**：施工卡明文授权「**每步一个 commit**」（`<LOCAL_WORK>/polling-gov/施工卡-定稿.md:159`「**提交纪律**：**每步一个 commit**，便于按步回退」），且 3 笔 commit **全部本地未推送**（`## main...origin/main [ahead 3]`）。
- **盯盘「改动路径 = ai-example-main/frontend/...」是误读**：全 `<LOCAL_WORK>` 下（`*.md/*.txt/*.json/*.log`）grep `ai-example-code/ai-example-main/frontend` **0 命中**，而 `-v2/frontend` 变体 25+ 命中；wire 里 104 次文件写/改全部落在 `-v2`。该说法系把 `<REPO_ROOT>` 截断成 `ai-example-main` 所致。

唯一实际偏差：**主仓库 HEAD 已从「预期 6c9c699a」前进 3 笔**（6c9c699a 是开工基线，见 `evidence-p18/baseline-git.txt`，并非终态预期）。

---

### 1. 主仓库 `<REPO_ROOT>`

```
$ git rev-parse HEAD
d60c4a6e88640a6fab63199265010ec9101c2bfd

$ git status --porcelain
(零输出)

$ git log --oneline -3
d60c4a6 poll-gov step3: task-events SSE subscriptions, channel fallback, close-gate
ca84ec9 poll-gov step2: polling controller with visibility discipline, single dynamic interval
2030c24 poll-gov step1: 条件轮询(hasActiveJob+refreshNow)+30s兜底 / waitForJobFinal 判别超时与可识别 abort / 导入侧同 kind watcher 回收与在途快照复检
```

谱系与推送状态（补充取证）：
```
$ git cat-file -t 6c9c699a…            → commit
$ git merge-base --is-ancestor 6c9c699a… HEAD → YES ancestor
$ git log --oneline -8
d60c4a6 poll-gov step3: …
ca84ec9 poll-gov step2: …
2030c24 poll-gov step1: …
6c9c699 docs(m2,evidence): 批 4④ 决策卡/施工卡/四报告/两会审入库 + R-145 落行 + 台账逐项销记/注记
0a52f98 feat(frontend): 对话时序包批 4④ 折叠规则三规则本体…
$ git status -sb
## main...origin/main [ahead 3]
$ git rev-list --left-right --count origin/main...HEAD
0	3
$ git remote -v
origin  <REPO-URL> (fetch/push)
```
三笔 commit 的作者/时间与 wire 命令时间戳逐秒吻合：
```
d60c4a6  authorDate=2026-10-12T09:11:41+08:00
ca84ec9  authorDate=2026-10-12T09:01:11+08:00
2030c24  authorDate=2026-10-12T08:51:13+08:00
6c9c699  = origin/main, origin/HEAD  authorDate=2026-10-12T07:46:21+08:00
```
`--stat`（仅 `frontend/`，后端零改动）：
```
2030c24: frontend/src/api/jobs.ts |32+, api/tasks.ts |6+, stores/task.ts |137+, utils/job-watch.ts |68+,
         views/ImportWizardView.vue |19+, views/TaskCenterView.vue |5+,
         tests/{import-watch-recycle,polling-predicate,wait-job-final}.spec.ts  → 9 files, +694/-27
ca84ec9: AiPanel.vue |27, stores/task.ts |73, utils/job-watch.ts |27, utils/polling.ts |263(+),
         tests/polling-controller.spec.ts |424(+)                              → 5 files, +767/-47
d60c4a6: api/tasks.ts |39, stores/task.ts |106+, utils/job-watch.ts |148, utils/polling.ts |5,
         utils/task-events.ts |168(+), views/TaskCenterView.vue |9,
         tests/{job-watch-channel,task-events-lifecycle,task-events}.spec.ts → 9 files, +1395/-52
```

### 2. trae 线仓库 `<LOCAL_WORK>\ai-example-code\ai-example-main`（仅只读 git）

```
$ git rev-parse HEAD
198604f4d704c9c9037abd6fa0c5d7e98a28f342

$ git log --oneline -5
198604f feat(ai): 落地会话生命周期与状态同步改造 A-H（两范式对齐 plan v1.1）
76e65e4 docs: 行业调研评审沉淀 + plan 升 v1.1 + 快捷选项设计方案
8d499f3 docs: 会话生命周期与状态同步方案设计 + 当前会话完整归档
38dfe75 security: 提交前敏感信息强制扫描(pre-commit hook)
afe9381 docs(memory): 沉淀密钥泄露补救流程经验

$ git status --porcelain
(零输出)        # 追加 -uall 复核：0 行

$ git reflog -5
198604f HEAD@{0}: clone: from <REPO-URL>
$ git log -1 --format="%cI %H"
2026-09-05T02:07:30+08:00 198604f4d704c9c9037abd6fa0c5d7e98a28f342

$ git cat-file -t d60c4a6e88640a6fab63199265010ec9101c2bfd
fatal: git cat-file: could not get object info
$ git cat-file -t 6c9c699aa680297ecf0a83ec39eeae8716b68431
fatal: git cat-file: could not get object info
```
**定性**：该库自 clone 以来**无任何后续 git 操作**（reflog 仅 clone 一条），最后提交是 2026-09-05，工作树干净。施工期间未新增任何对象、未改任何文件。（因 HEAD≠d60c4a6e，指令中「若 HEAD=d60c4a6e 则 show --stat」条件不成立，其等价 --stat 已在 <OLD_REPO_DIR> 侧照贴于第 1 条。）

### 3. K2.8 会话 wire 取证（权威）

统计口径：解析每个 `context.append_loop_event` 中 `event.type=="tool.call"` 的 `args`，抽出所有绝对路径（Windows `<DRIVE>/…` 与 bash `<DRIVE>/…` 两种写法），按仓库前缀归类。

```
=== 文件写/改读工具调用（Write/Edit/MultiEdit/Read）按仓库归类 ===
main:      v2 = 0,   trae = 0      (主棒只写卡/报告/证据 json，不碰代码)
agent-565: v2 = 0,   trae = 0      (探路棒：仅 Bash/Read)
agent-566: v2 = 37,  trae = 0
agent-567: v2 = 33,  trae = 0
agent-568: v2 = 34,  trae = 0
agent-569: v2 = 0,   trae = 0      (验收棒：仅 Bash)
TOTAL file-op tools v2=104  trae=0

=== 全部工具调用路径前缀明细（含 bash 路径与 cd 目标）===
main:      P:…/<REPO_ROOT> = 16   CD:…/<REPO_ROOT> = 4
agent-565: P:…/<REPO_ROOT> = 15   CD:… = 5
agent-566: P:…/<REPO_ROOT> = 42   CD:… = 7
agent-567: P:…/<REPO_ROOT> = 34   CD:… = 8
agent-568: P:…/<REPO_ROOT> = 49   CD:… = 13
agent-569: P:…/<REPO_ROOT> = 8    CD:… = 5
→ 无一条 P/CD 指向 ai-example-main（非 -v2）
```

落 `<REPO_ROOT>` 的样本 5 条（写/改）：
```
agent-566 Write <REPO_ROOT>/frontend/tests/polling-predicate.spec.ts
agent-566 Write <REPO_ROOT>/frontend/tests/wait-job-final.spec.ts
agent-566 Edit  <REPO_ROOT>/frontend/src/api/jobs.ts
agent-566 Edit  <REPO_ROOT>/frontend/src/stores/task.ts
agent-568 Write <REPO_ROOT>/frontend/tests/task-events.spec.ts
```

落 trae 线 `ai-example-main`（非 -v2）的样本：**写/改/读操作 0 条**。全 wire 中该前缀仅出现 **3 次**，且全部是主线 `main` 给下游 Agent 派发卡里的**红线禁令原文**（`tool=Agent`，非文件操作），照贴：
```
[2026-10-12T00:46:06.317Z] tool=Agent
…【红线】只改 frontend/ 内文件；后端零改动（完工时 `git status --short backend/` 必须为空）；不新增任何依赖/框架、不改 package.json 依赖面；不读任何 .env；严禁触碰 <LOCAL_WORK>/ai-example 与 <LOCAL_WORK>/ai-example-code/ai-example-main 两个目录；除本卡指定文件外不做任何顺手改。…

[2026-10-12T00:53:03.628Z] tool=Agent
…【红线】只改 frontend/；后端零改动（完工 `git status --short backend/` 为空）；不新增依赖；不读 .env；严禁触碰 <LOCAL_WORK>/ai-example 与 <LOCAL_WORK>/ai-example-code/ai-example-main；不做顺手改。每 spec 先 RED（失败原文存证据）再 GREEN。…

[2026-10-12T01:02:58.353Z] tool=Agent
…【红线】只改 frontend/；后端零改动（完工 `git status --short backend/` 为空）；不新增依赖；不读 .env；严禁触碰 <LOCAL_WORK>/ai-example 与 <LOCAL_WORK>/ai-example-code/ai-example-main；不做顺手改。…
```
（另有若干 `ai-example-main/` 命中来自 `ls <LOCAL_WORK>/ai-example-code` 的目录清单文本与 tool 系统提示词，均非路径操作。）

**`git add` / `git commit` 命令痕迹（谁、何时、commit 了什么）——命令原文照贴：**
```
[agent-566] 2026-10-12T00:51:13.570Z
cd <REPO_ROOT> && git add -A frontend/ && git commit -q -m "poll-gov step1: 条件轮询(hasActiveJob+refreshNow)+30s兜底 / waitForJobFinal 判别超时与可识别 abort / 导入侧同 kind watcher 回收与在途快照复检" && echo "COMMIT OK" && git log -1 --format="%H%n%s"
  → 产生 2030c249f17c93eed43a7463675cd7194b04adc0（08:51:13）

[agent-567] 2026-10-12T01:01:11.316Z
cd <REPO_ROOT> && git add -A frontend/ && git commit -q -m "poll-gov step2: polling controller with visibility discipline, single dynamic interval" && { echo "=== HEAD ==="; git log -1 --format="%H%n%s%n%ad"; … } 2>&1 | tee <LOCAL_WORK>/polling-gov/evidence-p18/git-step2.txt
  → 产生 ca84ec90ce43d61ad66493ca9c07edfc3ebf6a22（09:01:11）

[agent-568] 2026-10-12T01:11:41.707Z
cd <REPO_ROOT> && git add -A frontend/ && git commit -m "poll-gov step3: task-events SSE subscriptions, channel fallback, close-gate" 2>&1 | tail -8 && git log --oneline -3
  → 产生 d60c4a6e88640a6fab63199265010ec9101c2bfd（09:11:41）
```
`main` 棒与 565/569 棒**无任何 `git add`/`git commit`/`git push`**（仅 `rev-parse`/`status`/`diff` 只读）。全程**无 push 痕迹**。

### 4. `<LOCAL_WORK>\polling-gov\evidence-p18\` 清单与路径前缀

```
$ ls -1 | wc -l
41
```
（目录内 41 件：baseline-{git,greps,typecheck,yarn-test}、callsite-grep-step1、git-step{1,2,3}、green-step{1,2,3(+postcommit)}、red-step1×4/step2×1/step3×3、runtime-*.json、runtime-taskcenter.png、typecheck-step{1,2,3}、final-gates.txt、backend-run.log、frontend-dev.log、verify-open-task-events-step3.txt）

抽 3 件 grep 仓库路径前缀：
```
########## baseline-greps.txt
      2 <REPO_ROOT>
trae 前缀命中：0

########## callsite-grep-step1.txt
trae 前缀命中：0      # 文件内为相对路径 src/… 形态（cwd 已固定在 -v2/frontend）

########## verify-open-task-events-step3.txt
      1 <REPO_ROOT>
      1 <REPO_ROOT>
trae 前缀命中：0
```
四个 gate 证据同样是 `-v2`：
```
git-step1.txt: 2030c249f17c… / status 空 / backend 空
git-step2.txt: ca84ec90ce43… / status 空 / backend 空
git-step3.txt: d60c4a6e8864… / git status --porcelain 空 / backend 空 / --ignored=no | wc -l = 0
final-gates.txt: STEP1 HEAD=d60c4a6e8864…；STEP2 status 空；STEP3 RUN v4.1.11 <REPO_ROOT>/frontend
baseline-git.txt: 6c9c699aa680297ecf0a83ec39eeae8716b68431 / BACKEND: 空 / 命令 `cd <REPO_ROOT> && git rev-parse HEAD …`
```
**证据文件内无任何 trae 前缀改动路径。**

### 5. 施工报告 `<LOCAL_WORK>\polling-gov\施工报告-轮询一期.md` 相关行（`grep -n`，全文 166 行）

`grep -n -E "ai-example-main|<OLD_REPO_DIR>|d60c4a6e"` 全文仅 **1 命中**：
```
163:| 终态 HEAD | `d60c4a6e88640a6fab63199265010ec9101c2bfd` |
```
`grep -n "ai-example"` → **0 命中**（报告全文**从未写出仓库全路径**，这正是盯盘误判的温床）。相关上下文行：
```
4:> 施工基线 `HEAD = 6c9c699aa680297ecf0a83ec39eeae8716b68431`，开工 `git status --porcelain` 零输出（干净，非异常）。
40:| 后端零改动 | `git status --short backend/` 全程空；`git diff --stat 6c9c699..HEAD -- backend/` **空**（D16 红线满足） |
50:- HEAD = `6c9c699…`，全仓与 `backend/` git status 均零输出；
97:| 终态门禁 | `final-gates.txt`（HEAD/porcelain/yarn test 19/197 exit 0/typecheck exit 0/build exit 0/后端空，`git diff --stat 6c9c699..HEAD -- backend/` 空） |
158:- **施工卡 §10 条款核对**：… 10-3 仓库红线（基线干净起步、未读 .env、未触碰 trae 线两目录、无 git 写越界）✓；10-5 后端零改动（diff 级证据）✓
161:| 终态 HEAD | `d60c4a6e88640a6fab63199265010ec9101c2bfd` |
162:| 工作树 | 干净（运行时验证后复查） |
163:| 证据目录 | `<LOCAL_WORK>\polling-gov\evidence-p18\`（43 件） |
```
（行号 161-163 为收尾表体，`grep -n` 单条命中落在 163 是因为 161/163 同名前缀的匹配差异，实际同一张表。）

---

### 自证（agent id + wire modelAlias，取自 `llm.request`）
```
main:      "agentId":"main",      "model":"kimi-for-coding",  "modelAlias":"kimi-code/kimi-for-coding"   （llm.request 88 命中，全为同一别名，无混杂）
           profile.bind: {"agentId":"main","modelAlias":"kimi-code/kimi-for-coding","profileName":"agent","thinkingEffort":"high"
agent-565: "agentId":"agent-565", "modelAlias":"DeepSeek/deepseek-flash"  (12)
agent-566: "agentId":"agent-566", "modelAlias":"DeepSeek/deepseek-flash"  (73)
agent-567: "agentId":"agent-567", "modelAlias":"DeepSeek/deepseek-flash"  (69)
agent-568: "agentId":"agent-568", "modelAlias":"DeepSeek/deepseek-flash"  (77)
agent-569: "agentId":"agent-569", "modelAlias":"DeepSeek/deepseek-flash"  (16)
```

### 纪律与遗留
- 未对 trae 线做任何写操作：只执行了只读 git（`rev-parse`/`log`/`status`/`reflog`/`cat-file`/`remote -v`/`branch -vv`）与 `ls`；主仓库零写；未读任何 `.env`；**本次核查未创建/修改任何文件**。
- 需上级决断的口径问题：题面「<OLD_REPO_DIR> 预期 6c9c699a」与施工卡「每步一个 commit」相冲突。若「6c9c699a」是本轮**不可前进**的终态要求，则 3 笔本地 commit 属越权（可用 `git reset --hard 6c9c699a` 无损回退，未 push、无外部影响）；若它是开工基线，则现状完全合规。证据支持后者：`baseline-git.txt`（08:44）即记 6c9c699a 为基线，且证据链全程把 `-v2` 当主仓库。
- 轻微不符（不影响定性）：报告记 `evidence-p18` **43 件**，实测 **41 件**；主棒 wire 显示其曾写入 22 个 `wb-*.json`（浏览器自动化回执），现目录内已无 `wb-*` 文件，应为本会话自行清理，建议后续核查该清理是否有意为之。
