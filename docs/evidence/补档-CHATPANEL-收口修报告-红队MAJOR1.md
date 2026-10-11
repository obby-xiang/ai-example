> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 收口修报告 · 红队 MAJOR-1（对话面板包）

- **收口对象**：`<MAIN_REPO>`（分支 `main`，HEAD=`03a6cbe`）工作树**未提交**改动（对话面板包）
- **裁决来源**：GLM 红队带条件 PASS + 指挥官裁决的三处收口修（MAJOR-1 · MINOR-1 · MINOR-3）
- **执行纪律**：允许编辑的范围严格限定为三处目标文件；**零 `git add` / 零 `git commit` / 零 `git push`**；未还原任何在制品；未改动既有测试断言；测试期间自启的后端/桩/前端/CDP Chrome 进程已按监听端口逐一收尾（只杀自启进程）
- **脱敏**：正文一律占位符 —— `<MAIN_REPO>` = 主仓根；`<SCRATCH>` = 本报告所在的临时证据目录；`<RUNFIX>` = 给定 Maven 包装脚本（`mvn-run.sh`）所在目录。不含实盘路径、盘符、用户名、内网 IP、仓库名
- **环境**：Windows + Git Bash；JDK/Maven（`<RUNFIX>/mvn-run.sh` 内为 Maven 全路径）、Node、yarn、`powershell.exe`、Chrome 均就位；Redis 复用系统既有实例（`127.0.0.1:6379`，全程未启停）

---

## 0. 结论

三处收口修**全部落地并实跑验证**：

| 项 | 内容 | 结果 |
|---|---|---|
| 修 1（MAJOR-1） | `loadHistory()` 补会话身份守卫 | 已改 + 行为级探针 A/B 双 PASS |
| 修 2（MINOR-1） | `reattachActive()` / `resumeSnapshotStream()` 建立新连接处补 `this.stopped = false` | 已改 + 端到端回归绿 |
| 修 3（MINOR-3） | 删除端点负断言（不触碰台账面） | 已补 1 例：定向 11/0/0/0 |

七项验证**全部实跑**（定向单测、后端全量 400/0/0/0、前端 typecheck+build、文档门禁 6/6、E2E 29/0、CDP 冒烟 12 PASS + ⑫ SKIP、MAJOR-1 行为级探针 2/2），**零红**。待裁决项见 §5。

---

## 1. 三处改动的 diff 摘要

### 修 1（MAJOR-1）· `<MAIN_REPO>/frontend/src/stores/ai.ts` · `loadHistory()`

**改法**：action 开头捕获发起时的会话身份，完成路径每次落盘前对账，身份不符即整体 `return` 丢弃。

```diff
     async loadHistory(): Promise<void> {
+      const sid = this.sessionId                     // ← :433
       this.startMirror()
       const mirror = readMirror(this.sessionId)
@@
       try {
         history = await aiApi.history(this.sessionId)
       } catch {
+        if (this.sessionId !== sid) {                // ← :449
+          return // 会话已换：过期失败不落任何状态（新会话的 historyLoaded 由新会话自己落）
+        }
         this.historyLoaded = true
         return // 后端不可达：保留镜像展示
       }
 
+      // ← :456-460 会话身份对账：不匹配则整体丢弃
+      if (this.sessionId !== sid) {
+        return
+      }
+
       if (!history.messages || history.messages.length === 0) {
```

外加 action 文档注释一段（`:427-431`），说明守卫目的与口径来源（同文件 `isCurrentRound` 同族守卫）。

**落点说明（如实口径）**：任务书要求「完成路径两处写入（含 `restoreSuspendedSnapshot` 调用点）前判」。实现上把 await 之后的**单一身份对账点**放在分支之前，它同时覆盖了：

- 空历史分支：`this.messages` 的 `pendingCall → expired` 就地降级、`historyLoaded = true`、以及 `restoreSuspendedSnapshot()` 调用点 ①；
- 主分支：`this.messages = history.messages.map(...)` 历史覆盖、`historyLoaded = true`、以及 `restoreSuspendedSnapshot()` 调用点 ②。

之所以不逐写点各判一次：await 之后到这些写点之间**没有第二个 await**（`this.messages` 赋值是同步的），逐点判会形成死代码。catch 分支另加一处，保证「过期失败也不落状态」。

### 修 2（MINOR-1）· 同文件 · 两处新连接入口

```diff
     async resumeSnapshotStream(runId: string, lastSeq: number, hostMessageId: string): Promise<void> {
       const previous = currentAbort
       this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, lastSeq)
       this.loading = true
       this.streamInterrupted = false
+      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 …
+      this.stopped = false                          // ← :610
       const controller = new AbortController()
```

```diff
       const assistant = this.appendAssistantMessage('（正在重挂进行中的轮次…）')
       this.loading = true
       this.streamInterrupted = false
+      // 红队 MINOR-1 收口：新连接 ≠ 被停止的那条连接 …
+      this.stopped = false                          // ← :825
       this.notice = lastSeq === undefined
```

复位点随之由「仅 `send()`(:679) + `resetSession()`(:1158)」扩为「两处新连接入口 + 上述两点」，语义自洽：`stopped` 的用途仅限抑制**被停止那一轮**的断流提示，`onClose` 的判据（`!info.terminal && !this.stopped`）不该被上一条连接遗留的置位吞掉。

### 修 3（MINOR-3）· `<MAIN_REPO>/backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java`

新增 1 例负断言 `deleteHistoryNeverWritesRunStoreOrSessionGate()`（`:274-305`），最危险形态（`runActive=true`）下逐一对 `RunStore` 的**全部 15 个写口**与 `SessionGate` 的 `acquire`/`release` 断言 `verify(..., never())`：

```
create / save / deleteRun / saveSuspended / putPending / claimExecution / appendLedger /
cancelPendings / appendEvent / recordIssuedSeq / touchActivity /
markSessionCurrent / renewSessionCurrent / clearSessionCurrent      （RunStore）
acquire / release                                                    （SessionGate）
```

其中 `deleteRun` 是"最像删除"的那个写口（一锅端 `ai:run:*` / `ai:pending:*` / `ai:claim:*` / `ai:ledger:*` / `ai:events:*` / `ai:seq:*`），单列注释点明。测试类 javadoc 追加一段口径（`:64-68`），新增导入 `ArgumentMatchers.any` / `anyLong`（`:34-35`）。**未改动任何既有断言**。

### 附：随改动的注释同步（零行为、必要）

- `stopped` 状态字段的文档注释（`:266-272`）：原文「只由 `stop()` 置位、只由 `send()` 开头复位」→ 订正为「复位点 = 新连接建立处（`send` / `reattachActive` / `resumeSnapshotStream`）+ `resetSession()`」，否则修 2 落地即让该注释失真。
- `send()` 内 `onClose` 的注释（`:700-702`）：同上口径订正。

---

## 2. 验证：命令与实测结果（逐条实跑）

| # | 验证项 | 命令 | 实测结果 | 判定 |
|---|---|---|---|---|
| 1 | 后端定向单测 | `bash <RUNFIX>/mvn-run.sh test -Dtest=AiControllerHistoryDeleteTest` | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · `MVN_EXIT=0` | ✅ 11/0/0/0（原 10 + 新 1） |
| 2 | 后端全量 | `bash <RUNFIX>/mvn-run.sh test` | `Tests run: 400, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · `MVN_EXIT=0`；`target/surefire-reports/*.xml` = **61** 个 | ✅ 400/0/0/0 · 61 XML |
| 3a | 前端类型检查 | `cd <MAIN_REPO>/frontend && yarn typecheck` | `$ vue-tsc --noEmit` · `Done in 11.73s` · 退出码 0 | ✅ 绿 |
| 3b | 前端构建 | `yarn build` | `vue-tsc --noEmit && node scripts/build.mjs` · `✓ built in 50.52s` · `Done in 58.56s` · 退出码 0 | ✅ 绿 |
| 4 | 文档门禁 | `node scripts/ci/check-docs.mjs`（**路径偏差见 §5-④**） | 六条断言逐条 `[PASS]` · `全部通过：6/6 断言绿。` · 退出码 0（⑤ 扫 `git ls-files` 920 路径 / 实扫 917 / 豁免 3） | ✅ 6/6 |
| 5 | E2E 全量（桩模式） | `E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <Scratch>/artifacts-e2e/boot.log -ArtifactDir <Scratch>/artifacts-e2e/gfc -EnableGf6` | `结果：PASS=29 FAIL=0` · `GFSKIP=0` · `Q8 项：PASS=2 FAIL=0` · `E2E_EXIT=0` · `elapsed=58s` | ✅ 29/0 · 零 SKIP |
| 6 | CDP 冒烟 | `SMOKE_API=…:18330 SMOKE_APP=…:5211/ SMOKE_OUT=<Scratch>/smoke-out SMOKE_CDP_PORT=9334 node scripts/chat-panel-smoke.mjs` | 断言 ①②③④⑤⑥⑦⑧⑨⑩⑪⑬ **全 PASS**（12 条）+ ⑫ **SKIP**（未授权 `SMOKE_KILL_BACKEND`）+ 剪贴板回读旁证 SKIP（非断言） · `SMOKE_EXIT=0` · `elapsed=18s` | ✅ 12 PASS + 1 SKIP |
| 7 | **附加**：MAJOR-1 行为级探针 | `node <SCRATCH>/runs/probe-major1.mjs`（编排：`<SCRATCH>/runs/run-probe.sh`） | 阳性对照 **PASS** / 负例 **PASS** · `PROBE_EXIT=0` | ✅ 2/2（见 §3） |

**主要台账/证据文件（均在 `<SCRATCH>`，不入仓）**：`runs/mvn-focused.log`、`runs/mvn-full.log`、`runs/fe-typecheck.log`、`runs/fe-build.log`、`runs/check-docs.log`、`runs/e2e-run.log`、`runs/chat-panel-smoke.log`、`runs/probe-major1.log`。

**关键运行事实（便于复核复现）**：

- E2E：桩上游 `127.0.0.1:18399 --violate-form-type`（先 `--self-check` 通过）+ 后端 jar `18330`（占位 key + `AI_MODEL=ci-stub`）+ 工作目录在仓外；就绪判据 = `/api/ai/health` 双 true + `/api/definitions` ≥ 15。
- 冒烟/探针：前端 dev `5211`（`VITE_API_BASE=…:18330`）+ 后端 `18330` + 桩 `18399` + 自启 Chrome（远端调试口 9334 / 9335）。启动前均 `netstat` 预检（除 `6379` 外端口全空），收尾仅按监听端口杀自启进程，实测端口已释放。
- 后端 jar 为工作树已构建产物（mtime 晚于在制品的 `AiController.java` / `RedisChatMemoryRepository.java`）；本次后端改动**只在测试代码**，故 E2E/冒烟直接复用该 jar 有效。
- 为对齐 CI 的"未跟踪文件"口径，跑门禁前后均确认 `git status --porcelain` 恒为起手的 **9 改 + 2 未跟踪 = 11 条**，HEAD 恒为 `03a6cbe`：**未新增/减少任何工作树文件，未提交任何东西**。

---

## 3. 附加证据：MAJOR-1 行为级探针（本轮新增，超出任务书清单）

为把「守卫真的拦住了写」证成**行为事实**（而非"代码看上去对 + 构建绿"），另写了一个独立探针（`<SCRATCH>/runs/probe-major1.mjs`，零依赖、只驱动自启 Chrome，不触碰用户浏览器）。

**手法要点（两次实跑的教训，值得留档）**：

1. **必须在「响应阶段」拦**（`Fetch.enable{requestStage:'Response'}`），不能在请求阶段拦。

   请求阶段拦是把请求扣在浏览器侧 ⇒ 放行后后端才执行 ⇒ 那时旧记忆已被 `DELETE` 删掉，回来的必然是**空历史**，`loadHistory` 走空分支 —— 测不出 MAJOR-1 的危险分支，属**假验证**。响应阶段拦则是"请求已真实打到后端、响应体已生成（= 旧会话的 N 条历史），只是扣住不交给页面"，放行后页面收到的正是那份旧历史。
2. **负例阶段不能清 `messages`**：「新建对话」按钮在空态是置灰的（冒烟断言①），清了 `messages` 就点不动 ⇒ 变成"没换会话"的伪负例（首跑实测 `afterReset=null`，探针自曝）。故负例保留既有消息、只做"扣响应 → 真实点击 → 放行"。

**实测（两次跑，第二次为修正后定稿；下方为定稿数据）**：

| 阶段 | 构造 | 实测 |
|---|---|---|
| 0 基线 | 新会话 + 真实一轮对话 | `backendHistoryCount=2`，store `messages=2` |
| A **阳性对照**（会话**未**变） | 扣住该会话历史响应 → 放行 | 扣住期间 `messages=0`；放行响应体 `data.messages.length=2` → `messages=2`（**被写入**） |
| B **负例**（会话**已**变） | 同类响应扣住 → 真实点击「新建对话」（换 id + 清消息 + DELETE 旧记忆）→ 放行 | 扣住期间 `messages=2`、按钮可点；`afterReset.sessionId` 已变、`messages=0`；放行响应体 `data.messages.length=2`（**非空**）→ 放行后 `messages=0`、sessionId 仍为新 id（**未被写入**） |

拦截台账（探针落盘 JSON 内的 `interceptLog`）：

```
{"method":"GET","url":"…/api/ai/history/<旧会话 id>","action":"hold"}
{"method":"DELETE","url":"…/api/ai/history/<旧会话 id>","action":"auto-continue"}
```

**判读**：A 与 B 的输入同质（同一份"旧会话 2 条历史"响应体、同一次放行动作），**唯一差异是 await 期间会话身份是否被换掉** —— A 被写入、B 被整体丢弃。这正是守卫的作用面：若守卫缺失，B 会与 A 一样把 2 条旧历史灌进新会话（即 MAJOR-1 的原始危害）。A（看得见写）与 B（不写）互为正反证，排除了"探针瞎了"导致的假绿。

---

## 4. 纪律与边界

- **改动文件集合**：仅 `frontend/src/stores/ai.ts`（修 1、修 2 + 2 处注释同步）与 `backend/src/test/java/com/example/configmgr/ai/web/AiControllerHistoryDeleteTest.java`（修 3）。**未动其它文件**，未改既有测试断言，未新增既有行为分支。
- **未提交**：`git status --porcelain` 与 `git rev-parse HEAD` 起止一致（9 改 + 2 未跟踪；HEAD=`03a6cbe`）。工作树里另有 4 个与本次收口无关的文档类改动（`README.md`、`docs/README.md`、`docs/adr/DECISION-REGISTER.md`、`docs/M2-排期计划.md`）与 2 个未跟踪文件，均为**在制品原样**，本报告不主张其归属（与复核报告 C1/F1 同源）。
- **进程与端口**：每次编排先 `netstat` 预检、收尾只杀自启进程；实测 `18399/18330/5211/9334/9335` 均已释放。未授权任何破坏性动作（`SMOKE_KILL_BACKEND` 未置 1）。
- **构建产物**：`backend/target`、`frontend/dist` 为既有 `.gitignore` 豁免范围，非交付物。

---

## 5. 【待裁决】

1. **探针脚本是否入库**：`<SCRATCH>/runs/probe-major1.mjs` 目前只在临时证据目录（零依赖、可独立复跑）。它把 MAJOR-1 的守卫变成可回归的行为断言；是否要收编为仓内 `scripts/` 的正式探针（需另开施工卡，涉及端口/夹具约定），请裁决。
2. **冒烟断言⑫（真断流回归对照）本轮如实 SKIP**：⑫ 未授权 `SMOKE_KILL_BACKEND=1`（破坏性：需按端口 PID 杀后端 java 进程，并等约 92s 的静默看门狗收尾）。⑫ 恰好是修 2 的**反面**（真实断流仍必须亮红条，即守卫不得削弱真断流路径），若要求闭环可显式授权；本次不硬闯。
3. **修 1 守卫落点形态**：任务书字面是"完成路径两处写入前判"，我实现为 await 之后**单一身份对账点**（覆盖两处写点与两处 `restoreSuspendedSnapshot` 调用）+ catch 分支一处；逐写点各判会落在无 await 间隔处、形成死代码。若指挥官要求严格逐点（接受死代码形态），可再调整。
4. **门禁脚本路径偏差（仅报告口径）**：任务书写的 `node scripts/check-docs.mjs`，仓内实际为 `scripts/ci/check-docs.mjs`（CI 工作流的调用口径）。本轮按后者运行，6/6 绿；无待办。
5. **前端无单测基建**：`frontend/package.json` 无 `test` 脚本、无 vitest/jest 依赖，故修 1/修 2 的证据面是"类型检查 + 生产构建 + 端到端（E2E 29 用例含走 `loadHistory → restoreSuspendedSnapshot` 同族路径的 TC23；CDP 冒烟 12 断言）+ 行为级探针"，**没有 store 级单测**。是否立"引入前端单测基建"条目，超出本次收口范围，请裁决。
