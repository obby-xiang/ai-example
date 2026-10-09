# M2-T2b 修复复核报告（GLM-5.3 复核官）

- 复核日期：2026-10-09
- 复核对象：<REPO_ROOT>（HEAD = `2354721`）工作区未提交的 T2b 成果 + 修复批改动（`git status` 实测 13 M + 5 ??，共 18 条）
- 复核纪律：全程零 git 写操作、未修改任何仓库既有文件（唯一写为本报告新建）；本次复核未使用任何 AI key、**未读取任何 .env 文件**（后端以零 key 环境启动成功，无需占位 key）
- 端口纪律：后端 18330、前端 dev 18331（18330 段）、CDP 9333；启动前 netstat 逐口确认空闲，结束后全部清理（见 §8）
- 运行产物目录（仓库外，记 <SCRATCH>）：backend.log / frontend.log / smoke-run.log / mvn-test.log（mvn 全量日志）/ smoke-out/result-20261009T013908Z.json
- 修复批 before 副本（施工方留痕于其仓库外 scratch，记 <FIX_SCRATCH>，本复核用作 diff 基线）：RunStore-before.java / RunStoreSessionCurrentTest-before.java / smoke-before.mjs
- 证据等级：**【实测】** = 本复核实际执行/读码确认；**【旁证】** = 引用他棒既有证据

---

## 项 1：smoke 脚本 SMOKE_SKIP_MODEL 开关 — **闭合**【实测】

### (a) 语法检查

```
$ node --check scripts/m2t2a-smoke.mjs   →  退出码 0（NODE_CHECK_OK）
```

### (b) 读码核对（结构四要素）

| 要求位置 | 实测 | 内容 |
|---|---|---|
| :12-21 注释 | ✓ | :12-14 用法说明（跳过阶段 3 / 断言① 记 skipped / 退出码仅由 ②③③b 决定）+ :17-21 模式专述（默认未设置行为完全不变） |
| :66-67 常量 | ✓ | `const SKIP_MODEL = process.env.SMOKE_SKIP_MODEL === '1'` |
| :248-258 守卫 | ✓ | :248-251 守卫注释 + :252-258 `if (SKIP_MODEL)` 块：置 `RESULT.assertions['①'] = { pass: null, skipped: true, detail: '…' }`，**不调 assert()、不入 failures** |
| :259-401 else 块 | ✓ | 原阶段 3 全部代码（3a 真实模型轮 / 3b 刷新重建 / 3d 收敛）整体移入 else |

零逻辑改动交叉验证【实测】：取修复批 before 副本 `<FIX_SCRATCH>/smoke-before.mjs`，抽出新旧两版的阶段 3 代码块（before :239-396 / after :261-419），**归一化缩进后逐行 diff，唯一差异为 else 块收尾 `}`**——原代码纯移位，无任何改写。文件头部新增的仅注释与 `SKIP_MODEL` 常量。

### (c) 零模型模式实跑【实测】

环境（均本复核自起）：

- 后端 18330：`<MAVEN_HOME>/bin/mvn.cmd spring-boot:run -DskipTests -Dspring-boot.run.arguments="--server.port=18330"`，**未注入任何 AI key**（后端不强制要求 key，`GET /api/ai/health` 返回 `AI_UNAVAILABLE`，服务正常起——未启用占位假 key 路径）。Redis 6379 在线（既有服务，未动）。
- 前端 dev：`VITE_API_BASE=http://localhost:18330 VITE_DEV_PORT=18331 yarn dev`（vite /api 代理指向 18330）。
- CDP Chrome：脚本自启（SMOKE_CDP_PORT 缺省 9333）。

执行命令与原始输出（完整日志 <SCRATCH>/smoke-run.log）：

```
$ SMOKE_API=http://localhost:18330 SMOKE_APP=http://localhost:18331/ \
  SMOKE_OUT=<SCRATCH>/smoke-out SMOKE_SKIP_MODEL=1 node scripts/m2t2a-smoke.mjs

[app] ready
[断言②] PASS {"formRendererCount":1,"cancelButtons":1,"expect":1}
[断言③] PASS {"pendingStatuses":["cancelled","cancelled"]}
[断言③b] PASS {"formRendererCount":0,"cancelledTags":2}
[阶段3] SMOKE_SKIP_MODEL=1，跳过真实模型挂起轮（断言① skipped）
[done] result written to <SCRATCH>/smoke-out/result-20261009T013908Z.json
SMOKE_EXIT=0
```

结果 JSON（result-20261009T013908Z.json 实读）：

| 断言 | 预期 | 实测 | 一致性 |
|---|---|---|---|
| ② | PASS | `{"pass":true, detail:{formRendererCount:1, cancelButtons:1}}` | 一致 |
| ③ | PASS | `{"pass":true, detail:{pendingStatuses:["cancelled","cancelled"]}}` | 一致 |
| ③b | PASS | `{"pass":true, detail:{formRendererCount:0, cancelledTags:2}}` | 一致 |
| ① | `{pass:null, skipped:true}` | `{"pass":null, "skipped":true, "detail":"SMOKE_SKIP_MODEL=1：零模型回归守卫模式，跳过阶段 3（3a 模型轮 / 3b 刷新重建 / 3d 收敛），不计 fail 也不计 pass，退出码仅由 ②③③b 决定"}` | 一致 |
| 退出码 | 0 | 0 | 一致 |

全程未发 POST /chat 旁证【实测】：冒烟前后后端日志行数不变（113 行不变），`grep -c "POST /api/ai/chat\|POST /chat"` 命中 **0**；且冒烟输出中无任何 `[阶段3a]` 会话创建行。阶段 1/2 为纯前端 Pinia 注入（无模型、无 /chat）。

### (d) 默认模式

未重跑。依据：本复核官上一轮复现（`docs/evidence/M2-T2b-复现验证-GLM.md`）已实测默认模式四断言全 PASS、退出码 0【旁证】，且 (b) 的归一化 diff 已证明默认路径代码零改动——重跑结论可由静态等价性 + 上轮实测联合覆盖。

---

## 项 2：RunStore.java 注释修正（红队 S3-3）— **闭合**【实测】

### 零行为改动验证【实测】

修复批 before 副本 diff（`<FIX_SCRATCH>/RunStore-before.java` vs 工作区 RunStore.java）：

```
248c248,255
< 	/** 索引 TTL：对齐会话锁 watchdog（T2b-D#1，不用快照的 session-ttl）。 */
---
> 	/** 索引 TTL（T2b-D#1，不用快照的 session-ttl）：来源分两层看 —— <b>取值</b>取自配置项
> 	 * {@code app.ai.session.lock.ttl}（{@code properties.getSession().getLock().getTtl()}，
> 	 * 与会话锁 watchdog 共用同一配置）；<b>续期</b>与会话锁 watchdog 无关，而是由
> 	 * {@code ConfirmGate} 挂起等待循环的心跳分支每拍调用
> 	 * {@link #renewSessionCurrent(String, String)} 刷新为同一 TTL，轮落到终态则由
> 	 * {@link #clearSessionCurrent(String, String)} 主动删除（红队 S3-3 措辞修正）。
> 	 */
```

**全文件唯一 1 个 hunk，纯注释变化（:248 单行 → :248-255 八行），零行为改动。** 行号位移旁证：红队报告（修复前）引用 `RunStore.java:249-251`（renew/clear 方法在 :203-231、deleteRun 在 :267-270）；当前实测 renew :203 / clear :219 / deleteRun :274——位移仅由注释扩行造成，方法体相对结构一致。

### 两层表述 vs 代码事实逐条核对【实测】

| 注释表述 | 代码事实 | 一致性 |
|---|---|---|
| 取值取自 `app.ai.session.lock.ttl`（`properties.getSession().getLock().getTtl()`） | `RunStore.java:256-258` `sessionCurrentTtl()` 实现即 `return this.properties.getSession().getLock().getTtl();`；`AiProperties.java:23` `@ConfigurationProperties(prefix = "app.ai")` + `:139-142` `Session.Lock.ttl = Duration.ofMinutes(10)` → 配置键恰为 `app.ai.session.lock.ttl` | 一致 |
| 与会话锁 watchdog 共用同一配置 | `SessionGate.java:55-57` 构造器 `this.lockProperties = properties.getSession().getLock()`，:79 `setIfAbsent(key, runId, this.lockProperties.getTtl())`、:139 watchdog 续约同源——索引与锁确共用同一配置对象 | 一致 |
| 续期与会话锁 watchdog 无关，由 ConfirmGate 挂起等待循环心跳分支每拍调 `renewSessionCurrent` 刷新为同一 TTL | `ConfirmGate.java:188-193`：心跳分支（beacon.pulse 后）`if (sessionId != null) { this.store.renewSessionCurrent(sessionId, runId); }`；`RunStore.renewSessionCurrent`（:203-217）值匹配才 `EXPIRE` 同一 `sessionCurrentTtl()` | 一致 |
| 轮落到终态则由 `clearSessionCurrent` 主动删除 | `RunStore.save()` 联动（:158-170 附近）：DONE/FAILED/CANCELLED → `clearSessionCurrent`（值匹配才 DEL） | 一致 |

红队 S3-3 指出的"对齐会话锁 watchdog 表述略松"已被两层表述精确取代：**取值层**共用配置、**续期层**来自 ConfirmGate 心跳而非 SessionGate。措辞与代码事实完全一致。

---

## 项 3：RunStoreSessionCurrentTest 去硬编码（红队 S3-7）— **闭合**【实测】

### 读码核对【实测】

| 要求位置 | 实测 | 内容 |
|---|---|---|
| 约 :47-49 | ✓（:46-49） | `/** 与 RunStore 同源注入的属性实例：TTL 期望值从这里实读，不硬编码默认值（红队 S3-7）。 */ private final AiProperties properties = new AiProperties();` + `store = new RunStore(redis, mapper, this.properties)` —— 测试与被测实现共用**同一实例** |
| 约 :80 | ✓（:78-80） | `verify(this.valueOps).set(eq("ai:session:current:s-1"), eq("r-1"), eq(expectedIndexTtl()))` |
| 约 :109 | ✓（:109） | `verify(this.redis).expire(eq("ai:session:current:s-1"), eq(expectedIndexTtl()))` |
| 约 :113-115 | ✓（:112-115） | `private Duration expectedIndexTtl() { return this.properties.getSession().getLock().getTtl(); }` |

before/after diff 实证【实测】：修复前为 `verify(...).set(..., eq(Duration.ofMinutes(10)))` 与 `eq(Duration.ofMinutes(10))`（红队 S3-7 指认的 :76 硬编码），修复后全部换成 `expectedIndexTtl()` 实读——默认值漂移（如 lock.ttl 改 15m）时期望值与实现同步移动，测试不再锁死字面量。

### mvn 全量 + XML 口径独立重算【实测】

```
$ cd backend && <MAVEN_HOME>/bin/mvn.cmd test      （后台执行，日志 <SCRATCH>/mvn-test.log）

[INFO] Tests run: 263, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  39.866 s
退出码 EXIT=0
```

XML 口径独立重算（不依赖 mvn 汇总行，Python 逐文件解析 surefire `testsuite` 根属性求和，40 个套件文件）：

```
XML TOTAL: tests=263 failures=0 errors=0 skipped=0
suite files: 40
```

| 口径 | 结果 | 与预期（263/0/0/0，计数不得变化）比对 |
|---|---|---|
| mvn 汇总行 | 263 / 0 / 0 / 0，BUILD SUCCESS | 一致 |
| XML 独立求和 | 263 / 0 / 0 / 0 | 一致 |
| 与施工轮/红队/上轮复现基线 | 263 / 0 / 0 / 0 | 计数未变化 ✓ |

---

## 项 4：K2.8 证据"修订（闭环批）"节三条事实核对 — **闭合**【实测】

（`docs/evidence/M2-T2b-施工与验证-K2.8.md` :201-229，追加修订节，正文 §0~§7 保持原貌。）

### 修订 1：§7 数字勘误（backend 5M+2 新测试+frontend 5M+scripts 1M=13）

`git status --porcelain` 本复核实测逐条清点：

| 面 | 计数 | 实测明细 | 一致性 |
|---|---|---|---|
| backend 修改 | 5M | GenerativeFormRules.java、ConfirmGate.java、RunStore.java、AiController.java、InMemoryRunStore.java | 一致 |
| backend 新增 | 2 | RunStoreSessionCurrentTest.java、AiControllerRunsCurrentTest.java | 一致 |
| frontend 修改 | 5M | api/ai.ts、AiPanel.vue、FormRenderer.vue、stores/ai.ts、types/ai.ts | 一致 |
| scripts 修改 | 1M | m2t2a-smoke.mjs | 一致 |
| 合计 | 13 | — | 一致 |

（工作区另有 2 个 docs M + 3 个 docs/evidence ?? 共 5 条文档变更，不计入施工改动面口径，与修订 1 表格口径自洽。）

### 修订 2：断流收尾口径（三处行号逐条读码）

| 引用 | 实测读码 | 一致性 |
|---|---|---|
| `stores/ai.ts:558-563` `resumeSnapshotStream` 的 onClose 仅非终态时置 `streamInterrupted` + notice，不清 activeForm、不撤表单定时器 | :558-563 实读：`onClose: (info) => { if (!info.terminal) { this.streamInterrupted = true; this.notice = '…挂起卡保留，可刷新重试。' } }`——确无 disarmFormExpiry / activeForm 操作 | 一致 |
| `stores/ai.ts:719-731` `reattachActive` 同型分支会 `disarmFormExpiry()` + `activeForm = null` | :719-731 实读：onClose 非终态时 `if (this.activeForm !== null) { disarmFormExpiry(); this.activeForm = null }`（:723-728） | 一致 |
| `stores/ai.ts:513-524` 快照重建不武装本地计时器（`deadline: null`，快照无 expiresAt 折算依据） | :513-524 实读：`if (entry.name === GENERATIVE_FORM_TOOL && !this.activeForm) { … deadline: null }`，注释"deadline = null：不武装本地计时器，后端权威超时收敛（快照路径无 expiresAt 折算依据）" | 一致 |

"系有意设计 + 残余风险转 T3（S2-1）"的登记与红队 S2-1 描述一致【旁证：红队报告 §3】。

### 修订 3：HITL 增益（ResumeService 口径比对）

| 引用 | 实测读码 | 一致性 |
|---|---|---|
| `ResumeService.java:151-155` 口径 | :151-155 实读：`pendings.stream().filter(PENDING.equals(status)).filter(!KIND_BACKEND.equals(kind)).map(getToolCallId)`——即 `status == PENDING` 且 `kind != BACKEND` | 一致 |
| `/runs/current` 端点同口径 | `AiController.java:437-438`：`if (PendingToolCall.PENDING.equals(pending.getStatus()) && !PendingToolCall.KIND_BACKEND.equals(pending.getKind()))`——逐字同口径 | 一致 |
| CONFIRM 不在排除集 ⇒ 确认门挂起也 `awaitingExternal=true` | `PendingToolCall.java:63-69`：kind 取值仅 `CONFIRM / FRONTEND / BACKEND` 三种，排除集只含 BACKEND，CONFIRM 计入 unresolvedExternal——推论成立 | 一致 |
| 前端 `restoreSuspendedSnapshot` 会照常重建（`kind = entry.kind === 'CONFIRM' ? 'CONFIRM' : 'FRONTEND'`，:478-530） | `stores/ai.ts:485` 实读恰为该三元式，落在引用范围 :478-530 内；:478-481 为 PENDING 条目过滤循环入口 | 一致 |
| 证据级别"仅静态证据、运行验证转 T3" | 与本复核读码结论一致；本复核亦未实跑 CONFIRM 挂起轮（不在复核范围） | 一致 |

---

## 项 5：决策卡勘误 5 闭环标记 + 排期 v1.5 闭环注记 — **闭合**【实测】

### 决策卡（docs/M2-T2-返修决策卡.md :70）

:69 勘误 5 原文（待 T2b 落地后复验）+ :70 追加闭环标记：**【已闭环 2026-10-09：断言① 已按 T2b-D#3 快照重建形态升级，实跑 PASS（冒烟 ①②③③b 全 PASS，两轮可重复），证据 K2.8 施工验证 + GLM 复现验证】**——引用的两份证据文件均实际存在（5 ?? 清单内），断言① 升级形态与 K2.8 §3.4 两轮 PASS、上轮 GLM 复现 §3 全 PASS 相符【旁证 + 上轮本官实测】。

### 排期计划 v1.5（docs/M2-排期计划.md）

- :1 标题 `# M2 排期计划（v1.5）` ✓；:11 v1.5 修订注记（T2b 子项闭环链路 + 闭环注记节说明 + 三份证据来源）✓。
- :65-112「M2-T2b 闭环注记」节 a~f 六项核对：

| 节 | 内容 | 核对结果 |
|---|---|---|
| a) 闭环结论 | K2.8 施工 → 验收全 PASS → 红队有条件通过（无 S1；**2×S2 + 8×S3**，均不阻断）→ GLM 独立复现 → 修复批；**263/0**（XML 独立求和 263 执行 0 失败 0 错误 0 跳过）；typecheck/build 退出 0；冒烟**四断言全 PASS**；脱敏 0 违规 | 红队报告实测 2 条 S2（S2-1/S2-2）+ 8 条 S3（S3-1~S3-8）、判定"有条件通过、无 S1"一致；263/0 与本批复核重跑一致（见项 3）；四断言与 K2.8 两轮 + 上轮复现一致【实测+旁证】 |
| b) S2 两项去向 | S2-1 → T3（含两候选方案）；CONFIRM 运行验证并入 T3；S2-2 → T4 | 三条去向齐全，与红队 §6 建议一致 |
| c) S2-2 转 T4 台账 | vitest 优先覆盖 restoreSuspendedSnapshot / resumeSnapshotStream 两 action 的要点清单；E1"不引 vitest"裁决维持 | 内容完整，与红队 S2-2 建议一致 |
| d) 观察项登记（六条） | S3-1/2/4/5/6/8 六条（S3-3、S3-7 已修不列，另注明"本批已修"） | 恰为未修复的 6 条 S3（红队 8 条 − 本批已修 2 条 = 6），编号与摘要逐条与红队报告一致；"本批已修"注记与项 2/项 3 复核结论互证 |
| e) 五项顺手项闭环 | ①断言①升级/②F1 approved 分支/③F4 residue 守卫/④data-testid/⑤GENERATIVE_FORM_TOOL 常量——全部闭环，对账指 K2.8 §5 | K2.8 §5 对账表实读存在五项落点，一致【旁证】 |
| f) 治理留痕（两条） | 红队自证表证据等级（【实测】须本棒自跑，引用他棒标【旁证】）；key 注入方式（今后施工卡禁读任何 .env） | 两条留痕与红队报告 §0 自证表、K2.8 §6 偏差 1 登记的事实相符【旁证】 |

数字与事实全部与本复核掌握的证据一致。

---

## 全量脱敏 / 命名纪律扫描（修复批触及 6 文件）— **零违规**【实测】

扫描对象：`scripts/m2t2a-smoke.mjs`、`backend/.../run/RunStore.java`、`backend/.../run/RunStoreSessionCurrentTest.java`、`docs/evidence/M2-T2b-施工与验证-K2.8.md`、`docs/M2-T2-返修决策卡.md`、`docs/M2-排期计划.md`。

| 模式 | 命令 | 命中 |
|---|---|---|
| (a) `sk-` 真实 key（≥10 位） | `grep -nE 'sk-[A-Za-z0-9_-]{10,}'` | **0**（宽口径任意 `sk-` 字面仅 1 处：K2.8 :186 `sk-…` 省略号占位符，按豁免规则不构成违规） |
| (b) 本机盘符绝对路径（各盘符与正/反斜杠、大小写变体） | （命令字面量按 DC-08 不在正文复述；按同一引擎执行） | **0** |
| (c) 含 main+分隔符+v2 的仓库名（任意大小写与分隔符组合） | （同上，字面量按 DC-08 不在正文复述） | **0** |

---

## 仓库完整性与环境清理自检【实测】

- 全程零 git 写操作（无 add/commit/stash/checkout 等）；复核结束 `git status --porcelain` 仍为 18 条（13 M + 5 ??，含本报告新增前为 5 ?? 中的 3 份既有证据文档），HEAD 仍 `2354721`。
- 自启进程全部清理：后端 mvn spring-boot:run（TaskStop 终止）、前端 yarn/vite（含残留 node 子进程 PID 14036 已 taskkill //T 清理）、冒烟脚本自启 Chrome（脚本内 `chrome.kill()` 自清，另经命令行甄别确认无使用本复核 scratch profile 的残留 Chrome 实例）。最终 netstat 确认 **18330 / 18331 / 9333 无 LISTENING** 残留。
- 用户浏览器未触碰；Redis(6379) 系统服务未动；未读取任何 .env 文件。

---

## 总体判定

**复核通过。**

五项修复/回写逐项复核全部闭合，均为【实测】原始证据：

1. smoke `SMOKE_SKIP_MODEL=1` 开关：node --check 通过；零模型实跑 ②③③b PASS、断言① `{pass:null, skipped:true}`、退出码 0、后端日志零 POST /chat；默认路径经归一化 diff 证明零改动（上轮已实测全 PASS，本轮未重跑）。
2. RunStore.java S3-3 注释修正：before/after diff 全文件唯一 hunk、纯注释（:248→:248-255）零行为改动；两层表述（取值 lock.ttl 配置 / 续期 ConfirmGate 心跳）与代码事实逐条一致。
3. RunStoreSessionCurrentTest S3-7 去硬编码：TTL 期望改从与实现同源的 AiProperties 实例实读（:46-49、:78-80、:109、:112-115）；mvn 全量 + XML 独立重算均 **263/0/0/0**，计数无变化。
4. K2.8 修订节三条：数字勘误 13=5M+2+5M+1M 与 git status 实测一致；断流收尾三处行号（ai.ts:558-563 / :719-731 / :513-524）逐条读码命中；HITL 增益口径（ResumeService.java:151-155 与 AiController.java:437-438 逐字同口径、CONFIRM 不在排除集、前端 :485 三元式）全部成立。
5. 决策卡勘误 5 闭环标记 + 排期 v1.5 闭环注记：a~f 六项内容完整，数字与事实（263/0、红队 2×S2+8×S3、冒烟四断言全 PASS）全部与证据一致。

脱敏/命名纪律三类模式对修复批触及 6 文件扫描零违规。无差异项，无保留意见。
