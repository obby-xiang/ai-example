> 来源=仓库外过程报告补档（2026-10-12，对话时序包批 4④ 仓外复核报告（GLM-5.3，结论 PASS 0B/0M/6m/6O），署名信息以文内为准；本件为 DC-19 后产出的仓外审查件，脱敏后入库，源文件未改）

# 复核报告 · 批4④「对话时序包 · 折叠规则」施工复核（GLM-5.3 · 复核与测试视角）

## 0. 复核方署名（DC-19：实际执行模型 + CU 会话 id + wire modelAlias 实证）

```
复核棒执行模型自证：
 - 执行者 = main（CU 会话 id = session_0ce59987-189e-444f-af02-aa89f60dee67）
 - wire 路径 = <KIMI_HOME>\sessions\wd_temp_38dcbf7ced2f\session_0ce59987-189e-444f-af02-aa89f60dee67\agents\main\wire.jsonl
 - 统计命令 = grep -o '"modelAlias":"[^"]*"' wire.jsonl | sort | uniq -c
 - 实测命中 = "modelAlias":"Zhipu AI/glm-5.3" × 52 次（读取时点 2026-10-12 04:51，文件 757,548 B，仍在追加）
 - 结论 = 实际运行模型 = Zhipu AI/glm-5.3（wire 实证，非配置推定，与派单口径 GLM-5.3 一致）
 - 授权来源 = 本卡（K3 指挥官派单 · 批4④ 复核卡）
```

- **机械活下沉申报（纪律①）**：两个机械核对子代理均**显式钉 `model=DeepSeek/deepseek-flash`**（禁靠默认），且各自完成 wire 自省：
  - `agent-544`（锚点/留证/计数机械核对）：`…/session_0ce59987…/agents/agent-544/wire.jsonl`，`"modelAlias":"DeepSeek/deepseek-flash"` × 14（读取瞬时值）——与父棒下发参数一致；
  - `agent-545`（CDP 证据抽验）：`…/agents/agent-545/wire.jsonl`（114 行），`"modelAlias":"DeepSeek/deepseek-flash"` × 12，无其它别名——与父棒下发参数一致。
  - 关键判断（行为基线对照、决策卡 D2~D11 逐条比对、A4' 写点、m2t2a 断言语义、锚定一致性裁定）均由复核棒本体亲读完成。
- **H4 路径红线**：对 `<OTHER_REPO>（另一条线的仓，严禁触碰）` 与 `<OTHER_REPO-2>（另一条线的仓，严禁触碰）` 零操作（未读未写未 git）。
- **主仓库零写零 git 写**：全程未 add/commit/stash/checkout；复核结束 `git status --porcelain` 仍恰 6 项在制品（与锚定记录逐字节一致）。唯一仓内临时件 `RCMR-tmp.class`（jar 类抽取比对用）已当场删除，未触碰在制品 6 文件。
- **未读任何 `.env`**；**未跑 `mvn`**。

---

## 1. 总体结论：**PASS（带 5 项 minor 文本偏差 + 1 项 minor 事实陈述错误，均不影响验收方向）**

按既往批次分级口径（0B/0M/m/O）：**BLOCKER 0 / MAJOR 0 / minor 6 / 观察 6**。

八项复核范围全部独立复现通过：锚定零夹带、门禁三连真跑全绿（152/152 · 12 spec / typecheck / build）、E2E 桩复跑 PASS=28/FAIL=0/GFSKIP=0、m2t2a 复跑退出码 0、行为基线与决策卡 D2/D3/D4/D6/D7/D8/D11 逐条吻合、RED/GREEN 双向留证核验通过（含 A8' 恒假变异互逆核验）、CDP 证据 10/10 与 6 张截图逐张吻合。发现的 6 项 minor 全部为**施工报告文本层的行号/计数/事实陈述偏差**，无一触及代码行为、证据真伪或验收判据方向。

---

## 2. 逐条复核记录（①~⑧，每条【实测】证据 + 命令 + 数字）

### ① 锚定核验（防夹带）——【实测】PASS

| 子项 | 命令 | 实测 | 结论 |
|---|---|---|---|
| 6 文件 sha256 | `sha256sum <6文件>` | `AiPanel.vue 995846a9…` / `ai.ts 6d879adf…` / `ai-panel-parts-render.spec.ts e6e15582…` / `m2t2a-smoke.mjs 07ed083d…` / `blocks.ts 3afd6262…` / `blocks.spec.ts d045d8d2…` —— 与 `anchor-b4.json` 的 `worktreeSha256` **六条逐字一致** | PASS |
| 锚定 patch | `sha256sum evidence-p17/diff-批4④-full.sorted.patch` | `b3526474…767467`（69,971 B），与派单权威值一致；anchor-b4.json 记录 6 段 +838/−142，段序按路径排序 | PASS |
| patch ↔ 工作树 | `git apply -R --check <patch>`（仓根，只读校验） | 通过（APPLY_R_CHECK_OK）—— 逆向应用可干净命中，证明 patch 与工作树状态一致 | PASS |
| git status | `git status --porcelain` | 恰 6 项（M×4 + ??×2），无第 7 项，diff 外零夹带；HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6` | PASS |
| 被复核文件 | `sha256sum 施工报告-批4④.md` | `fc55bb6c969fd4a51b02665571e5acedbbbeb3080ad3be9cf08a90239e781df9`，与派单一致 | PASS |

复核证据落点：`<SCRATCH>\chat-timeline-pkg\复核证据-批4④\`（本报告全部实测日志所在）。

### ② 门禁真实复跑——【实测】PASS（数字与施工报告一致）

| 门禁 | 命令（仓 frontend/） | 实测 | 施工报告值 | 结论 |
|---|---|---|---|---|
| `yarn test` | `yarn test`（真跑，`复核证据-批4④/vitest-rerun.log`） | **Test Files 12 passed (12) / Tests 152 passed (152) / EXIT=0**；逐文件例数：blocks 15 / sse-watchdog 4 / ready 4 / frontend-tool-outcome 9 / parts-double-write 5 / parts-reducer 15 / tools-and-deadline 21 / restore-reconcile 3 / frontend-tool-result-terminal 30 / export-wizard-step-outcome 5 / ai-panel-parts-render 19 / parts-residue-cleanup 22 = 152 | 152/152 · 12 spec | **一致** |
| `yarn typecheck` | `yarn typecheck`（`typecheck-rerun.log`） | `vue-tsc --noEmit` / `Done in 3.63s.` / **EXIT=0** | 绿 | 一致 |
| `yarn build` | `yarn build`（`build-rerun.log`） | `✓ built in 26.69s` / **EXIT=0** | 绿 | 一致 |

工具链实测：node `v24.21.0` / yarn `1.22.22`（与施工卡钉死值一致）。

### ③ E2E 桩复跑——【实测】PASS（数字与施工报告一致）

- **复跑配方（照仓内 ci.yml + 施工报告 §5）**：桩 `node scripts/ci/stub-upstream.mjs --port 18399 --violate-form-type --dir <复核证据>/stub-req`（起前 netstat 确认 18399 空闲）→ 后端 `java -jar backend/target/config-mgr.jar --server.port=18330`（`AI_API_KEY=ci-stub-placeholder AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub`，H2 落复核证据目录）→ 前端 `VITE_DEV_PORT=5202 VITE_API_BASE=http://localhost:18330 yarn dev`。
- **就绪门**：桩 `/probe` = `{"ok":true,"stub":"ci-upstream"}`；后端 `/api/ai/health` = `available:true` + `redis.available:true`（Redis 127.0.0.1:6379 为环境既有，未自起）。
- **实测结果**（`复核证据-批4④/E2E-stub-rerun.log`，命令 `E2E_STUB_MODE=1 powershell -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <复核>/backend-e2e.log`）：
  - **`结果：PASS=28  FAIL=0`** / **`GFSKIP=0`** / **`Q8 项：PASS=2 FAIL=0`** / **`E2E_EXIT=0`** —— 与施工留证（`E2E-stub-final.log`）与派单预期**逐字一致**。
- **端口纪律执行记录**：起前 netstat（5202/18330/18399/9333 全空）；复核毕按 PID `taskkill //F //T`（dev 31384、后端 7936、桩 18716，均为本复核自起进程），复验 `ALL_CLEAN`；后端运行时数据（H2）落在仓外复核证据目录，主仓 `git status` 复核前后均恰 6 项在制品（E2E 副作用零残留）。

### ④ 行为基线对照——【实测】PASS（复核棒本体亲读代码逐条比对）

折叠三规则 + 判据，对照决策卡逐项（行号 = 当前工作树实读）：

| 规则/判据 | 决策卡依据 | 亲读实现 | 结论 |
|---|---|---|---|
| 相邻同类合并 | D5/A5'规则① | `utils/blocks.ts:84-131` `groupParts`：顺序扫描，`text` 打断组（:113 `flush()`）；reasoning/tool 相邻 push 进 `current` | ✅ |
| 正文永不进组 | A5'规则③ | `blocks.ts:108-115` text 一律 flush 后独立 text 块；:92-95 DEV `console.error` 断言成员 kind（不可达防御） | ✅ |
| 三态展开（`Record<blockId, boolean>`） | D4/D-C6 | `stores/ai.ts:463` `expandedBlocks: {} as Record<string, boolean>`；`blocks.ts:64-72` `resolveBlockExpanded`：manual ≠ undefined ⇒ 优先用户值；`AiPanel.vue:597-603` 读 `ai.expandedBlocks[block.id]` | ✅ |
| requiresAction 组强制展开、手动收起不生效 | D6①/R4-5 | `blocks.ts:65-67`：`requiresAction` 判据在 manual 之前短路返回 true | ✅ |
| reason 非空 ⇒ 默认展开且不得被自动折叠（允许手动收起） | D6②/D-C1 | `blocks.ts:50-52` `defaultExpandedOf` 第②档（在 manual 判定之下、defaultExpanded 之上） | ✅ |
| 进行中展开 | D6③ | `blocks.ts:53-55` status ∈ {pending, running} | ✅ |
| reasoning 组默认折叠 | D-C8 | `blocks.ts:56` 兜底 false（纯 reasoning 组无工具成员 ⇒ 走④档） | ✅ |
| 单调用组不套壳 | D7 | `blocks.ts:102` `bare: current.length === 1`；`AiPanel.vue:171` `v-if="!block.bare"` 组壳 / `:261` bare 支直渲 | ✅ |
| blockId 稳定禁下标 | D5/R4-3 | `blocks.ts:30-32` `stablePartId`（toolCallId / `kind-seg`）；`:98` 组首成员 id | ✅ |
| resetSession 清空 | D4 | `stores/ai.ts:1462` `this.expandedBlocks = {}`（含「不进镜像」注释） | ✅ |
| 单卡不变式（挂起内容段驱动） | D2 | `AiPanel.vue:194` / `:280` testid 门控 = `part.requiresAction ? 'pending-card-' + part.toolCallId : undefined`（组壳/bare 两分支各一份）；卡头/参数/结果/reason 全取段字段；`m.pendingCall.*` 卡头读点全部删除 | ✅ |
| 多 requiresAction 段逐段出卡 | D-C9/D2 终裁补充 | `blocks.ts:120-126`：requiresAction 段独立成组（前后 flush 断组）⇒ 逐段独立卡 | ✅ |
| A4' GF 取消失败文案段 reason 位 | D3/D11 | `stores/ai.ts:1358-1380`：`form.error` 原行（:1359）保留后，`const reason = form.error`（:1365，**同源、无第二份字面量**）→ `upsertToolPart(host, { toolCallId, name: existing.name, kind2: existing.kind2, requiresAction: false, reason }, false)`（:1370-1376，**D-C2 形状逐字 + createIfMissing=false**）；**不经 applyPendingTerminal**（分支内无该调用）；DEV 警告 :1377-1378 含 toolCallId。渲染位 = 工具卡 `原因：` 行（`AiPanel.vue:209`/`:295`，段驱动无条件渲染）。文案含结构化前缀「取消回灌未送达」+ auto 前缀「表单已超时，」沿用 | ✅ |
| FormRenderer 宿主条件逐字保留 | D10 | `AiPanel.vue:243`/`:321` `liveForm && liveForm.toolCallId === part.toolCallId && m.id === formHostMessageId`（两分支各一份，条件串与基线 `:238` 一致） | ✅ |
| 旧死位删除 | D-C7/A9' | 「表单已取消」「表单已提交，等待回执」「表单状态已丢失」`frontend/src` 0 命中；`argsText` 0 命中（孤儿函数已删）；「会话已失效」由段状态 tag（expired→toolStatusLabel）承接 | ✅ |
| `<details>` 受控化 | A6' | 原生 `<details>` 0 命中（git grep HEAD 有 :174-177，工作树 0）；bare 思考链改 `reasoning-toggle-`/`reasoning-body-` testid + `v-show="isBlockExpanded(block)"` | ✅ |

测试覆盖核验（复核棒亲查 + vitest 复跑佐证）：`blocks.spec.ts` 15 例静态 `it(`（TS-1 ①~⑩ + 组头等）；`ai-panel-parts-render.spec.ts` 19 例（运行时口径，见下「与施工报告差异表」#4）；基线三例（段序 :81 / N10 :236 / isTyping :251）保留且标题逐字未改；D6② 手动收起/展开永续、D-C1 组默认展开、D4 清空、TS-7 多 requiresAction 段（RED-s1 的 0≠2 → GREEN 两卡）均有例承载。

### ⑤ RED/GREEN 双向留证核验——【实测】PASS（子代理机械核对 + 复核棒抽读）

- **S1（A1'/A4'/D-C9）**：`RED-s1.log` = 13 例执行、6 红——红因分别为工具名双卡头 2≠1（×2）、D-C9 两卡 `[] length 0 ≠ 2`、A9' 旧文案命中（×2）、TS-3 reason 缺失；失败输出含完整挂载 DOM（「AI实施助手流式对话…表单状态已丢失…」）⇒ **夹具就位仍红，非导入失败**（H6 达标）。`GREEN-s1.log` 13/13。
- **A8' 自承 RED**：`RED-a8p-mutant.diff` 变异 = testid 门控去 `requiresAction` 条件（恒真）⇒ 4 例红（A1'/重投/新宿主/历史整替）；`GREEN-a8p-revert.diff` 与之仅差该 1 行（`37c37`），恢复行与工作树 `AiPanel.vue:194` 逐字一致 ⇒ **变异/恢复互逆、恢复干净**。RED 非「恒绿断言伪装」（恒真变异下真红）。
- **S3**：`RED-s3.log` 红在导入（`@/utils/blocks` 不存在）——施工报告已按施工卡两取法之一如实申报（R-7），符合卡 S3「红在导入，须写明」的准许口径；`GREEN-s3-blocks.log` 15/15、`GREEN-s3.log` 两 spec 34/34。
- **TS-5**：`RED-m2t2a.log` = 原脚本（HEAD 提取件 `_m2t2a-orig.mjs`）对新前端 exit=1（断言② FAIL + FATAL）；`GREEN-m2t2a.log` exit=0。且本复核**独立复跑**（见⑥）证实退出码 0 可复现。
- **门禁/三连/E2E 日志**（vitest-c1 131 / vitest-c2 152 / typecheck×2 / build×2 / E2E-stub-final / ts8-redlines 含 parts.ts、frontend-tools.ts、sse.ts sha256 与基线逐字一致——复核方独立复算 sha256 亦一致）全部核对通过。
- 施工报告 §0 的自证计数（×107/×106）现读为 ×118/×117：该 wire 在报告落稿后仍被追加，计数单调增长、方向自洽，**不可回证但不构成异常**（观察项 O-6）。

### ⑥ m2t2a-smoke.mjs 改写核验 + SMOKE_SKIP_MODEL=1 复跑——【实测】PASS

- **改写正确性亲读**：`countExpr`（m2t2a-smoke.mjs:173-193）新增 `cancelledSegments` / `hangingSegments`（段态、全消息域聚合，判据与决策卡 D8 钉死口径逐字一致）+ `cancelledStatusTags`（DOM 强判据：`body *` 叶元素文本恰等「已取消」，取法与报告 §6 逐字一致）；`pendingStatuses` 改段来源（:180-181）；断言③（:311）= `cancelledSegments === 2 && hangingSegments === 0`；断言③b（:313）= `formRendererCount === 0 && hangingSegments === 0 && cancelledStatusTags >= 1`——**判据未放宽、未掩盖失败**。`injectExpr` 注入法修正（不传 assistantId、逐条 append 后立即注帧，:151-154 注释载明 R2/R6 轮次归属根因）与报告 R-4 申报一致，属「修复失效断言」的必要组成。
- **复跑（真跑）**：`SMOKE_SKIP_MODEL=1 node scripts/m2t2a-smoke.mjs`（dev:5202 + 桩/后端链路在跑）⇒ **EXIT=0**；② PASS（formRendererCount:1/cancelButtons:1）、③ PASS（`cancelledSegments:2, hangingSegments:0`）、③b PASS（`formRendererCount:0, hangingSegments:0, cancelledStatusTags:3`）、断言① skipped——**全部数值与施工留证 GREEN-m2t2a.log 逐字一致**。日志：`复核证据-批4④/m2t2a-rerun.log` + 结果 JSON `m2t2a-out/result-20261011T204939Z.json`。
- 注：脚本 `API` 缺省 18318 仅用于阶段3 /health 上界推导，SKIP_MODEL 模式不依赖该端口，与本复核桩端口不冲突（CDP 缺省 9333，起前确认空闲）。

### ⑦ 施工报告 §12 未做项确认——【实测】PASS（口径合理）

1. **断言①（真实模型挂起轮）桩环境跳过**：属实且属预期——脚本自身 `:317-325` SKIP_MODEL 分支设计即「断言① 记 skipped、退出码仅由 ②③③b 决定」，本复核复跑同样 skipped。合理。
2. **CDP 形态 2/3 页内替身 outcome**：替身与真实断网共享同一 outcome 形状（`{ok:false, message}`），文案由前端源码同一路径拼写（复核棒亲读 `stores/ai.ts:1359` 证实模板串一致）；时序卡持久 ⇒ 替身态卡面即真实态卡面。**口径合理**，报告已如实申报未构造网络级故障（H7 达标）。保留一条观察（O-4）：替身未覆盖「回灌 HTTP 层真实超时/中断」的传输层差异，属后续可选加固，非本批评据。
3. **未跑 mvn / docs-evidence 不动 / 断言① 未跑**：与施工卡口径一致（本批零后端改动、A10' 只读口径）。

### ⑧ CDP 证据抽验——【实测】PASS（子代理逐张读图 + 复核棒核 JSON）

- `result-20261011T203247Z.json`：10 断言全 `ok:true`；三必含形态齐全（折叠组行为 #1-#4 / GF 取消失败文案可见 #7 含「取消回灌未送达」逐字 / 多工具折叠组内 reason 可见 #9-#10），另有确认门 #5 与单工具轮 #6。
- **6 张截图逐张人工读图全部与命名/断言吻合**：1a 折叠组一行摘要（组体 display:none）；1b 展开含 3 卡 + 新到裸卡；1c CONFIRM 待决卡直渲（倒计时/双按钮/无组壳）；2 卡内红字「原因：取消回灌未送达（网络异常），后端将在超时后自动收尾」+「已取消」tag；3 GF 取消段与两工具同组默认展开 reason 组内可见；4 单工具裸卡无组壳。
- 时间戳自洽：文件名/JSON stamp/profile 目录同为 `20261011T203247Z`（UTC）= 本地 04:32:47，截图内应用自绘时钟 04:32:50-54 ≥ stamp，无倒挂；端口 5202 与 devserver.log 一致。
- 报告写「5 张截图」实际 6 张（4-单工具轮单列）——口径小偏差（见差异表 #1）。

---

## 3. 发现清单（分级）

### BLOCKER（0）
无。

### MAJOR（0）
无。

### minor（6）

| # | 发现 | 级别 | 依据【实测】 | 影响 |
|---|---|---|---|---|
| m-1 | 施工报告 §1 称 `utils/blocks.ts`「新建 **141 行**」，实测 **131 行**（`wc -l`；四函数行号全对） | minor | 复核棒 + agent-544 双重实读 | 行数错 10，不影响任何判据 |
| m-2 | §1「bare 支 `:243` 起」实测 bare 分支（`<template v-else>`）在 **`:261`**；`:243` 实为组分支内 FormRenderer 宿主条件行；「`textOf :586`」实测 **`:587`** | minor | 同上 | 行号偏 1/错指，内容全对 |
| m-3 | §1「A4' 写点 `:1357-1379`、`form.error` 原行 `:1358`」实测失败分支 **`:1358-1380`**、`form.error` 在 **`:1359`**、新增块 `:1360-1379` | minor | 复核棒亲读（本报告 §2-④ 表） | 行号整体少 1，形状/内容/语义全对 |
| m-4 | §1/§10 称 `ai-panel-parts-render.spec.ts` **21 例**、静态 `it(` 现 **142**（+14 面板净增）；实测静态 **19 例**、静态合计 **140**（113+15+12），且报告自身留证 `GREEN-s3.log`（19 tests）/`vitest-c2.log`（19 tests）同为其反证 | minor | agent-544 三源交叉（grep/GREEN-s3/vitest-c2） | 例数口径错 2；**运行时总数 152 正确**（19+15+…），验收判据不受影响 |
| m-5 | 施工报告两处写「工作树保留 **5 文件**改动」，实际 **6 文件**（M×4 + 新增 ??×2；报告 §1 锚点表与 anchor-b4.json 均按 6 文件记载，自相矛盾） | minor | 复核棒 `git status --porcelain` 实测 6 项 | 纯计数口误；锚定与 patch 均以 6 文件为准，无实际遗漏 |
| m-6 | **§7-5 后端 jar 定性错误**：称「jar 为 2026-10-11 20:20 构建；其后两次提交（c3f2579/3edaeb9）均为**测试类改动，不动主码**」。实测 git 历史：`c3f2579`（21:48:05）**改了主码** `RedisChatMemoryRepository.java`（saveAll 空列表分支改 Lua 委托）；且 jar 内该类字节码**已含** `deleteByConversationId` + `DEL+SREM` Lua 脚本特征串 ⇒ jar 实为包含该改动的新构建（文件 mtime 20:20 与内容不符，构建时点晚于所示）。**方向为保守侧错误**（jar 比声称的更新而非更旧），E2E 结果有效性不受损——本复核用同一 jar 复跑 E2E 得 PASS=28/GFSKIP=0 亦为旁证 | minor | 复核棒 `git log/show` + jar 类文件常量池抽取比对 | §7-5 事实陈述需更正；「E2E 有效」结论仍成立 |

### 观察（6）

| # | 观察 | 说明 |
|---|---|---|
| O-1 | `defaultExpandedOf` 第②档（reason 非空）仅扫 `tools` 成员（`blocks.ts:50`），reasoning 段无 reason 字段，语义等价 | 无影响，纯实现口径 |
| O-2 | `R-2 偏差`：`bare = parts.length === 1` 扩到单 reasoning 段（D7 条文只提 tool）| 报告已申报（Ruling），避免双折叠控件，合理 |
| O-3 | `R-3 偏差`：空 text 段不打断组 | 与渲染真值（v-if="part.text"）一致，合理 |
| O-4 | CDP 形态 2/3 回灌失败为页内替身，未构造网络级故障 | 报告 §12 已如实申报；替身与真实路径共享 outcome 形状与文案拼接代码路径，口径可接受 |
| O-5 | result JSON 若干 DOM 计数字段（cardCount/memberCount=0）为探针选择器口径问题，实质判据（bodyHidden）不受影响 | 建议后续修正字段口径防误读 |
| O-6 | 施工报告 §0 自证计数 ×107/×106 现读 ×118/×117（wire 落稿后仍追加，单调增长）| 不可回证但方向自洽，非异常 |

另：复核过程中 GF「取消回灌未送达」的**后端日志因果行**未能钉到某一行（子代理反馈：backend-e2e.log 中取消事件晚于截图时刻、属下一轮），但断言对象是前端 DOM 文案逐字可见且源码拼写路径唯一 ⇒ 断言成立（【实测】截图 + 【推断】因果时序归属），不构成发现。

---

## 4. 与施工报告差异表

| # | 施工报告原文 | 复核实测 | 差异性质 | 定级 |
|---|---|---|---|---|
| 1 | §3「5 截图」（另处又称 4 并入 1c） | cdp/ 目录 **6 张**，4-单工具轮为独立文件 | 计数口误 | minor（并入 m-5 同类口径问题，此处单列观察级） |
| 2 | §1 `blocks.ts` 141 行 | **131 行** | 行数错 | m-1 |
| 3 | §1 bare 支 :243 起 / textOf :586 | bare 支 **:261** / textOf **:587** | 行号偏 | m-2 |
| 4 | §1 A4' 写点 :1357-1379、form.error :1358 | **:1358-1380 / :1359** | 行号偏 1 | m-3 |
| 5 | §1/§10 面板 spec 21 例 / 静态 it( 142 | **19 例 / 140**（运行时 152 ✓） | 例数口径错（与自身留证矛盾） | m-4 |
| 6 | 「工作树 5 文件」 | **6 文件**（M4 + 新增2） | 计数口误 | m-5 |
| 7 | §7-5「两次提交均为测试类改动不动主码」 | `c3f2579` **改主码**且 **jar 已含该改动** | 事实陈述错误（保守侧） | m-6 |
| 8 | 其余全部（锚定/门禁数字/E2E/m2t2a/RED-GREEN/CDP/行为基线/台账承接/偏差申报） | **逐项复现一致** | 无差异 | — |

---

## 5. 复核方法与局限（H7 对复核方的对应履行）

- 本复核为**独立复现验证**：门禁三连、E2E 桩、m2t2a 全部真跑（非照抄报告数字）；锚定 sha256 与 patch 一致性独立重算；RED/GREEN 与 CDP 证据为留证文件核验 + 截图人工读图（未重做 CDP 注入实验——判据为证据一致性抽验，派单口径 ⑧ 即抽验）。
- 复核起止：2026-10-12 04:42 ~ 05:0x（本地）；全部日志与结果件落 `<SCRATCH>\chat-timeline-pkg\复核证据-批4④\`（vitest/typecheck/build/m2t2a/E2E 五份复跑日志 + e2e-work 环境 + m2t2a-out 结果件）。
- 局限：① 施工报告 §0 施工棒自证计数不可回证（O-6）；② CDP 证据为抽验非重跑（派单口径如此）；③ jar 字节码比对用常量池特征串（含 `deleteByConversationId`/`DEL+SREM` Lua 串）判定，未逐指令反编译——特征串仅存在于新版实现，足以定性。
- 红线自查：主仓库零写零 git 写 ✅；路径红线（ai-example / <OTHER_REPO-2>）零操作 ✅；未读 .env ✅；未跑 mvn ✅；自起进程（dev 5202 / 后端 18330 / 桩 18399）已按 PID 全部清理并复验端口清空 ✅。

---

## 6. 结论

**批4④ 施工验收：PASS。** 八项复核范围全部独立复现通过；发现 6 项 minor（全部为施工报告文本层偏差，其中 m-6 为事实陈述错误但方向保守、不影响结论）+ 6 项观察。建议提交官收口时：① 按 m-1~m-5 更正报告行号/计数；② 按 m-6 更正 §7-5 jar 定性（或补一次带时间戳的 mvn 构建留证）；③ m-4 的静态例数口径以本报告 19/140 为准。以上均不阻塞两提交拆分与台账销记。
