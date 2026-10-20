# 补档 · B23L1 批① 设施（FrameStore / Stream / wake 扇出 / seq 收敛）提交报告（提交官 · DS-Flash · 2026-10-21）

## 0 自证行（wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1306`；wire = `<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_081d49e8-17a5-451b-befe-c0422c23a69b/agents/agent-1306/wire.jsonl`
- 取数方式：首行为 `{"type":"metadata",...}`（非 bind 行），`grep -n 'profile.bind'` 命中**第 5 行**，原文首部（逐字）：

```json
{"type":"profile.bind","agentId":"agent-1306","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high",...}
```

- 实测三字段：`modelAlias` = **`DeepSeek/deepseek-flash`**、`profileName` = **`coder`**、`thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓；未触碰并列仓）；职责边界 = 机械验收 + commit + push + CI 跟进；**零代码改动**（除看板批①行状态/哈希位 + 本报告 + 证据件 DC-08 占位符替换），零服务启停（双实例 harness **未运行**），`?? data/` 未动未入库。

## 1 机械验收逐项

### 1.1 `git status --porcelain`（验收前基线）

预期 = 批① 全部改动 + 新增 + 既有 `?? data/`；实测 **完全吻合**，无意外文件：**9 `M` + 19 `??`**（18 个本批新件 + `data/`）。

- 后端生产 4 改 = `ai/run/RunRegistry.java`、`ai/run/RunStore.java`、`ai/run/SseChatEmitter.java`、`ai/web/AiController.java`
- 后端生产 6 新 = `ai/run/{FrameStore,RedisFrameStore,FrameWakeBus,RedisFrameWakeBus,FrameWakeListener,RunStoreFrameStore}.java`
- 后端测试 2 改 = `ai/run/RunStoreSlidingTtlTest.java`、`ai/web/AiControllerRunsCurrentTest.java`；测试 8 新 = `ai/run/{FakeFrameRedis,FakeWakeBus,InMemoryFrameStore}.java` + `{FrameCompatReadTest,FrameWakeListenerTest,RedisFrameStoreTest,SseChatEmitterCrossInstanceWakeTest,SseChatEmitterFrameStoreTest}.java`
- 脚本 1 新 = `scripts/b23l1-dual-instance.ps1`；脚本文档 1 改 = `scripts/README.md`
- docs 2 改 = `docs/03-技术方案文档-v2.1.md`（文末只追加两条注记）、`docs/任务看板.md`（批①行）；docs 3 新 = `docs/evidence/补档-B23L1批①设施-{施工报告,复审报告-DS-Flash,复核确认-DS-Flash}.md`
- **前端零改动**（`git status` 无 `frontend/` 条目；复核确认 §4 同口径）
- `?? data/` = 历史遗留，**未动、未入库**（`git diff --cached --name-only | grep -c '^data/'` = 0）

第一笔入库 **27 文件 = 9 改 + 18 新建**，`+4059 / −70`；锚 = `git diff 109d348 de7095a` sha256 `15d5f13b7d4d4c67eacaa7f9d230d1ea5b3c0df7f6898cf887a2ebe5e75e125b`。

### 1.2 脱敏终扫（DC-08 · 三规则 SECRET / PATH / USER）

用仓内 `scripts/githooks/scan-lib.sh` 同一套规则，分三个面扫：①已跟踪全量 diff（`git diff --unified=0` 的 `+` 行）②未跟踪新件逐文件全文（排除 `data/`）③暂存区（提交前终扫）。

| 轮次 | 扫描面 | 命中 | 处置 |
|---|---|---|---|
| 首扫 | ① 已跟踪 diff | **0** | — |
| 首扫 | ② 未跟踪新件 | **4**（全为 PATH/USER，SECRET 0） | 按 DC-08 换占位符（见下） |
| 复扫 | ①② | **0** | — |
| 每笔提交前 | ③ 暂存区 + `git diff --cached --check` | **0 / check 0** | 三笔均过 `pre-commit`（`✓ 暂存区无命中`） |

首扫 4 处明细（均为证据件里写的本机路径，非密钥；**不改事实与结论**，只做机械替换）：

| 文件:行 | 规则 | 原文片段 | 替换为 |
|---|---|---|---|
| `docs/evidence/补档-B23L1批①设施-复审报告-DS-Flash.md:151` | PATH | `<REPORTS_ROOT>/T3-5-附件-S2-health压测前.json`（原为盘符绝对路径） | `<REPORTS_ROOT>/T3-5-…json` |
| `docs/evidence/补档-B23L1批①设施-复审报告-DS-Flash.md:185` | PATH | `<MAVEN_HOME>\bin\mvn.cmd`（原为盘符绝对路径，含 Maven 版本目录） | `<MAVEN_HOME>\bin\mvn.cmd` |
| `docs/evidence/补档-B23L1批①设施-施工报告.md:7` | PATH | 本机用户目录下的 `.kimi-code/sessions/…/agents/agent-1301/wire.jsonl`（盘符绝对路径） | `<KIMI_HOME>/sessions/…/agent-1301/wire.jsonl` |
| 同上 | USER | 同上路径中的用户名片段 | 同上（路径根已替换） |

未使用 `AI_SECRETS_ALLOW`、未 `--no-verify`；三笔 `pre-commit` 均为阻断式实跑通过。副作用留痕：证据件内 `sha256` / diff 锚为**脱敏前**工作树实测，脱敏后不再逐字节复现（体例同既有批次）。

### 1.3 文档门禁 check-docs（三个时点）

`node scripts/ci/check-docs.mjs` 均 **6/6 断言绿**（exit 0）：

1. 基线（证据件已在工作树、看板仍 🔧）：6/6
2. 看板批①行改 ✅ + 哈希位「本批」之后：6/6
3. 锚定第二笔（哈希 = `de7095a`）之后：6/6

门禁含命名纪律全量扫描（`git ls-files` 1327 路径，实扫文本 1324，豁免 3）。

### 1.4 测试门禁复跑（提交官本人实测）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 后端全量 | `<MAVEN_HOME>/bin/mvn.cmd -o -q test`（`backend/`，Maven 3.9.16 离线） | **exit 0**；surefire `TEST-*.xml` 合计 = **98 suite / 626 tests / 0 failures / 0 errors / 0 skipped** |
| 前端全量 | `cd frontend && node node_modules/vitest/vitest.mjs run`（**未加** `--reporter=basic`） | **512/512 通过 · 26 文件**（`Test Files 26 passed (26)` / `Tests 512 passed (512)`，Duration 15.74s） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6**（见 §1.3） |

前端零改动故 512/512 与批前同数；后端由基数 586/93 类升至 **626/98 类**（本批新增 5 类 28 条 + 收口 11 条 + 复核修 M′ 1 条），与施工报告 §6.5 自报一致。任一非绿即中止 —— 本批**无中止项**。typecheck / build / E2E 与 CI 数字取自施工报告 §6.5 与下文 CI 实跑（提交官不改代码，不重复跑 build；E2E 需服务，铁律禁起停，由 CI 的 E2E job 覆盖）。

### 1.5 看板核对

- 改动前 `docs/任务看板.md:14`（批① 行）状态 = **🔧**、进展首段 = `**施工中·收口修**`，与卡片预期一致。
- 第一笔同时落：状态 ✅；进展首段新增 `**已闭环（2026-10-21 入库，哈希 = 本批）**` + `**门禁（提交官复跑）**`（后端 626/626 · 98 类 · 前端 512/512 · 26 文件 · check-docs 6/6）+ `**证据件**` 三件名与复核结论（READY-WITH-NITS，M′ 关闭，残余 3N 与 L-4~L-8 同批）；原「施工中·收口修」过程留痕整段保留，仅把「证据待落」改为「证据已落（施工 / 复审 / 复核确认三件同批）」。
- 第二笔只把「本批」换成 `de7095a`（`1 file changed, 1 insertion(+), 1 deletion(-)`），其余一字未动。

## 2 提交与推送（三笔，逐笔 push）

| # | 短哈希 | message | 内容 | push |
|---|---|---|---|---|
| 1 | **`de7095a`** | `feat(ai-run): B23L1批① 事件日志 Redis Stream 化与 wake 唤醒扇出` | 27 文件（生产 6 新 + 4 改、测试 8 新 + 2 改、harness 1 新、docs 3 新 + 2 改；含双看板）。看板批①行 🔧→✅（哈希位「本批」） | `109d348..de7095a main -> main` ✓ |
| 2 | **`b4a1a7c`** | `docs(board): B23L1批①入库锚定 de7095a` | `docs/任务看板.md:14` 锚定 1 行（「本批」→ `de7095a`） | `de7095a..b4a1a7c main -> main` ✓ |
| 3 | **本件**（push 后回执） | `docs(evidence): 补档 B23L1 批① 提交报告入库` | 本报告 1 文件 | 见 §3 |

三笔均过 `pre-commit` 阻断式扫描（`✓ 暂存区无命中`）；origin 保持 **HTTPS**（`https://github.com/<GITHUB_OWNER>/ai-example.git`），未改远端配置。

## 3 CI 确认（每笔 push 后跟 run 至 conclusion，上限 12 分钟/笔）

| 笔 | run id | conclusion | 五 job |
|---|---|---|---|
| `de7095a` | **39475337597** | **success** | 前端类型检查与构建 ✓ ｜ 文档一致性门禁（check-docs）｜阻断 ✓ ｜ 全历史脱敏扫描（报告制 · 不计门禁）✓ ｜ 后端编译与单测 ✓ ｜ E2E 全流程（29 用例 · 桩上游 + redis）✓ |
| `b4a1a7c` | **39476439126** | **success** | 同上五 job 全绿 |
| 本件（第三笔） | 见提交官回执 | 推送后同 workflow 触发 | 本文件入库时该 run 尚未产生，结论由提交官在交付回执中记录 |

轮询方式 = `<GH_CLI> api "repos/<GITHUB_OWNER>/ai-example/actions/runs/<id>"` + `…/jobs`（Git Bash 下端点无前导斜杠），20s 间隔；两笔均在 **~6 分钟内**出 conclusion，未触 12 分钟上限。

## 4 异常与留痕

1. **唯一需处置项 = DC-08 路径脱敏**：首扫 4 处 PATH/USER（SECRET 0），按仓库既有提交官惯例做占位符替换，复扫 0、暂存区终扫 0；**未走逃生口**。
2. **`?? data/`**：三笔全程未入索引（终态 `git status` 仅剩 `?? data/`）；`data/` 未入 `.gitignore` 属既有挂账（复审 N-1 已登记），提交官不处置。
3. **harness 未运行**：`scripts/b23l1-dual-instance.ps1` 只交付；实例面证据（尤其 `suspend-reattach-wake`）与 P95 帧延迟随「harness 运行批」取证（挂账 **L-5**）。施工报告 §5 的偏差 D-1~D-7 与遗留 L-1~L-3 保持原样。
4. **复核新发现 M′ 已关闭**：复核确认 §3.2 报「`XRANGE` 升序读取、不夹窗口头 ⇒ >6000 帧长轮全量回放缺尾段」，施工方已随复核修落地（`RedisFrameStore#streamFramesAbove` 夹 `max(afterSeq, tail−窗口)` + 末号取「末尾 16 条最大 seq」），并补用例 `RedisFrameStoreTest#fullReplayOverAnApproximatelyTrimmedStreamReturnsTheNewestWindow`（替身 `retainExtra` 建模 `MAXLEN ~` 近似裁剪），红态取证见施工报告 §6.4；故本批门禁为 626（复核确认轮为 625）。
5. **挂账 L-4~L-8（不随本批销记）**：L-4 兼容期后删 LIST 读路径（M-2）/ L-5 P95 随 harness 运行批取证（M-7）/ L-6 唤醒差量超 `WAKE_BATCH_LIMIT` 无自续读（N-2）/ L-7 harness 就绪信号·信号清理·事件注销·HttpClient 释放（N-3 剩余）/ L-8 `mergeBySeq` 空洞观测与 `lastSeq` 低估（N-5，窗口读方向一面已由 M′ 关闭）。落点 = 施工报告 §6.6。
6. **批②③ 未开工**：fencing 租约与资源治理不在本批（设计稿 §7）；harness 中 `suspend-decision` / `instance-kill` / `resource-lock` / `idempotency` 四条用例以 SKIP 占位，不计失败。
