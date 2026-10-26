> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-286` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：9378 B / 6808 字符 / 143 行；行指纹覆盖率（仓内 + 落盘语料）35.1%
> - 类别：**过程核验**
> - 脱敏：路径 13 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. 起始 `git status --porcelain` 快照

命令：`cd <REPO_ROOT> && git rev-parse HEAD && git status --porcelain`（退出码 0）

```
5cfbf331fd8ec1347e581ddb9dea674eb64e6a73
main
--- status ---
 M frontend/src/api/sse.ts
 M frontend/src/components/SpreadGrid.vue
 M frontend/src/stores/ai.ts
 M frontend/src/utils/frontend-tools.ts
 M frontend/src/views/DefinitionsView.vue
 M frontend/src/views/ImportWizardView.vue
 M frontend/src/views/TaskCenterView.vue
```

HEAD=`5cfbf33`、分支 `main` 与任务描述一致，但**起始就不是 4 个 M 文件，而是 7 个**：除指定的 4 个外，`DefinitionsView.vue`、`ImportWizardView.vue`、`TaskCenterView.vue` 也处于已修改状态。无未跟踪文件。

## 2. 三个仓外脚本的路径指向（前 30 行）

三个脚本**全部硬编码指向同一个路径，且正是目标工作树**：

| 脚本 | 硬编码路径 | 指向 |
|---|---|---|
| `<LOCAL_WORK>\pkg1-reports\scratch\grid-ready.mjs` | L16 `const REPO = '<REPO_ROOT>'`，L17 `FE = REPO/frontend` | ✅ 就是 <OLD_REPO_DIR> |
| `<LOCAL_WORK>\pkg1-reports\scratch\sse-watchdog.mjs` | L18 `const REPO = '<REPO_ROOT>'`，L19 `FE = REPO/frontend` | ✅ 就是 <OLD_REPO_DIR> |
| `<LOCAL_WORK>\pkg5-scratch\budget-check-v2.mjs` | L18 `const REPO = '<REPO_ROOT>'`，L19–21 读 `frontend/src/utils/frontend-tools.ts`、`frontend/src/stores/ai.ts`、`frontend/src/views/ExportWizardView.vue` | ✅ 就是 <OLD_REPO_DIR> |

没有指向 `ai-example-main`、`ai-example-glm-5.3` 等其它 checkout。三个脚本都**不支持参数化仓库路径**：无 `process.env` 读取、无 REPO 相关的 argv（只有 `grid-ready.mjs`/`sse-watchdog.mjs` 接受位置参数 `before|after`）。因此"改指向 <OLD_REPO_DIR> 再跑一遍"不适用——它们本来就已经指向 <OLD_REPO_DIR>。

需要知晓的两个细节：
- `grid-ready.mjs:83` 与 `sse-watchdog.mjs:51` 对 `before` 变体会执行 `git show HEAD:<file>`（只读，cwd=REPO）。我未做任何 git 写操作。
- `budget-check-v2.mjs` 额外读 `ExportWizardView.vue`——该文件**不在** M 列表中（工作树与 HEAD 一致）。

## 3. 运行三个脚本

### 3a. 原样执行（任务给出的命令，无额外参数）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `node <LOCAL_WORK>\pkg1-reports\scratch\grid-ready.mjs` | **1** | `Error: usage: node grid-ready.mjs before|after`（脚本强制要求位置参数） |
| `node <LOCAL_WORK>\pkg1-reports\scratch\sse-watchdog.mjs` | **1** | `Error: usage: node sse-watchdog.mjs before|after` |
| `node <LOCAL_WORK>\pkg5-scratch\budget-check-v2.mjs` | **0** | **54 PASS / 0 FAIL**，末行 `结论：ALL PASS` |

因此脚本自身参数必须补上；下面补 `after`（当前工作树）和 `before`（HEAD）复跑。

### 3b. `grid-ready.mjs`（输出 JSON 结果，无 PASS/FAIL 计数）

`node .../grid-ready.mjs after` 退出码 **0**，`node .../grid-ready.mjs before` 退出码 **0**。5 个用例对照（`state` / `engineState`）：

| 用例 | after（工作树） | before（HEAD） |
|---|---|---|
| early-return（host=null，引擎已加载） | rejected `表格引擎未挂载…`，engineState=failed | **pending**，engineState=loading（挂死 5015ms） |
| unmount-while-loading | rejected `表格引擎未就绪…`，failed | **pending**，loading（5002ms） |
| engine-reject（chunk 404） | rejected 透传 `表格引擎加载失败：chunk 404…`，failed | **pending**（5009ms），底层 error 已置但 Promise 不结算 |
| success（对照组） | resolved，ready | resolved，ready |
| engine-hang（永不 settle） | rejected `表格引擎加载超时（超过 60 秒未就绪）…`，failed，59954ms | **pending**，loading，70011ms |

即 after 侧四条结算路径全部收敛（3 rejected + 1 resolved），before 侧三条挂在 pending。这正是修复的目标行为。

### 3c. `sse-watchdog.mjs`（输出 JSON，无 PASS/FAIL 计数）

`after` 退出码 **0**（`variantIsHead:false`），`before` 退出码 **0**（`variantIsHead:true`）。关键字段对照：

| 用例 | after | before |
|---|---|---|
| A.healthy（onopen+done） | settled=true, terminal=true, 227ms, 构造 1 次 | settled=true, terminal=true, 236ms（回归未破坏） |
| B.silent（黑洞） | **settled=true**, 48046ms, terminal=false, reason=`重挂连接建立超时（未收到 onopen）（重挂已尝试 2 次）`, reconnects=2, 构造 3 次 | **settled=false**, 60016ms, reconnects=null, 构造 1 次（永久挂死） |
| C.error-first（退避重连成功） | settled=true, terminal=true, 1328ms, reconnects=1 | settled=true, terminal=true, 1327ms（一致） |
| D.abort（静默窗口点停止） | settled=true, `连接已取消`, 1010ms；`constructionsAfterAbortPlus4s:1`、`closedAfterAbortPlus4s:1`（不再重连） | 同：1010ms，1/1 |

### 3d. `budget-check-v2.mjs`（有 PASS/FAIL 统计）

执行两次（一次原样、一次为计数重跑），均退出码 **0**：

```
PASS count: 54
FAIL count: 0
脚本总耗时 1455 ms
结论：ALL PASS
```

**54 PASS / 0 FAIL 与施工报告声称的"54 PASS"完全吻合。** 覆盖：源码常量核对（90_000 / 30_000 / MIN 5_000 / 双道钳制）、5 组语义场景（①快执行器 ②异步挂起按预算先到并回灌 1 次 ③慢成功但超预算 ④契约外 reject 原样上抛 ⑤折算表 120→90s…10→5s、⑤b 小上限无倒挂、⑥计时器清理、⑦无 unhandledRejection、⑧a/⑧b 同步阻塞），以及防漂移自检（"本脚本的文案副本与仓库逐字一致"、"折算逻辑副本与仓库一致，无漂移"）。

### 3e. 参数化结论

`grid-ready.mjs` / `sse-watchdog.mjs` 仅参数化 `before|after`（改取 HEAD 版还是工作树版被测算源），**不支持**改写仓库路径；`budget-check-v2.mjs` 完全不接受参数。无需也不能"改指向"——三者已指向 <OLD_REPO_DIR>。

## 4. `yarn typecheck` 与 `yarn build`

`frontend/package.json` 的 scripts：`build` = `vue-tsc --noEmit && node scripts/build.mjs`，`typecheck` = `vue-tsc --noEmit`（脚本存在，无需替跑）。包管理器为 yarn（存在 `yarn.lock`），yarn 1.22.22。

**`yarn typecheck`** — 退出码 **0**：
```
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 4.00s.
TYPECHECK_EXIT=0
```

**`yarn build`** — 退出码 **0**：
```
yarn run v1.22.22
$ vue-tsc --noEmit && node scripts/build.mjs
vite v6.4.4 building for production...
✓ 1747 modules transformed.
dist/index.html                                0.63 kB │ gzip:     0.38 kB
...
dist/assets/spreadjs-T7tmZ_9V.js           6,198.64 kB │ gzip: 1,783.03 kB
✓ built in 29.97s
Done in 34.61s.
BUILD_EXIT=0
```
无报错、无告警。产物落在 `frontend/dist/`，已确认被忽略：`git check-ignore -v frontend/dist` → `.gitignore:17:frontend/dist/`。

## 5. 结束 `git status --porcelain`

```
 M backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
 M backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java
 M backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java
 M frontend/src/api/sse.ts
 M frontend/src/components/SpreadGrid.vue
 M frontend/src/stores/ai.ts
 M frontend/src/utils/frontend-tools.ts
 M frontend/src/views/DefinitionsView.vue
 M frontend/src/views/ImportWizardView.vue
 M frontend/src/views/TaskCenterView.vue
?? backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java
```

**工作树并非"干净如初"，且变动集中在 backend。这不是我造成的**，证据：

- 我这轮只执行：三个 node 脚本（只写 `<LOCAL_WORK>/pkg1-reports/scratch/**`、`<LOCAL_WORK>/pkg5-scratch/**` 及 `/tmp/bc.txt`，或仅读仓库）、`yarn typecheck`、`yarn build`（只写已忽略的 `frontend/dist/`）。没有任何 `git` 写操作、没有 touches 到 backend。
- 3 个 backend 文件的 mtime 是 `11:55:15`、`11:55:21`、`11:55:25`，新目录 `common/sse/SseWriteBudget.java` 也是 `11:55`——与我 `yarn build` 的 `frontend/dist` mtime `11:55:18` 同一时间窗，即**在我跑构建期间有另一个进程/代理正在并发编辑 backend**；且 `SseChatEmitter.java` 在我 11:55:23 与 11:55:30 两次快照之间又新增为 M，说明该写入仍在进行中。
- 7 个 frontend M 文件 mtime 为 11:25–11:49，**早于我本轮开始（约 11:50）**，全程未被任何命令触碰。

所以工作树从"起始 7 M"演变为"9 M + 1 未跟踪目录"，增量全部是 backend 文件，来源是并发写入，不是本次验证。

---

**总结一行：** 三个脚本的硬编码仓库路径全部正确指向 `<REPO_ROOT>`（无脚本支持改写路径，但本就无需改写）；`budget-check-v2` 复现 **54 PASS / 0 FAIL（ALL PASS）**，`grid-ready`/`sse-watchdog` 因缺位置参数原样运行会报 usage 错（退出码 1），补 `after|before` 后均退出码 0 且 after 侧行为与修复目标一致（grid 三条 pending→rejected、sse B.silent 由 settled=false 永挂变为 48s 超时结算）；`yarn typecheck` 与 `yarn build` 均退出码 0；但**工作树未保持原状**——起始即为 7 个 M（非 4 个），结束时为 9 个 M + 1 个未跟踪 backend 目录，增量是并发进程在 11:55 写入的 backend 文件，非本次命令所致。
