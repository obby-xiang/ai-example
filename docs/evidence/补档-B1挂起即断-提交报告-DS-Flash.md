# 补档 · B1 挂起即断 SSE 提交报告（提交官 · DS-Flash · 2026-10-20）

## 0 自证行（wire 实测，非照抄卡片）

- 提交官 agentId = `agent-1287`；wire = `<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1287/wire.jsonl`，`grep '"profile.bind"'` 首条第 5 行，实测：
  - `modelAlias` = **`DeepSeek/deepseek-flash`**
  - `profileName` = **`coder`**
  - `thinkingEffort` = **`high`**
- 仓根 = `<REPO_ROOT>`（唯一可操作仓；未触碰并列 `ai-example` / `ai-example-main` 仓）
- 职责边界 = 机械验收 + commit + push + CI 确认；**零代码改动**（除看板 :13 状态行 + 本报告），零服务启停，`?? data/` 未动未入库

## 1 机械验收逐项

### 1.1 `git status --porcelain`（验收前基线）

预期 = B1 批全部改动/新增 + 既有 `?? data/`。实测 **完全吻合**，无意外文件：

- 后端生产 3 改 = `ai/gate/ConfirmGate.java`、`ai/run/RunStore.java`、`ai/run/SseChatEmitter.java`
- 后端测试 12 改（conformance 5：`ChatTurnFrameSequence` / `InMemoryRunStore` / `ReattachLastSeq` / `SseFrameSequence` / `ToolFrameSequence`；gate 1：`ToolPreflightGateTest`；run 6：`EmptyReplyTerminal` / `SeqPersistence` / `SseChatEmitterFrames` / `SseChatEmitterWriteBudget` / `SseFrameSizeLimit`）**+ 1 新** = `ai/gate/SuspendDisconnectGateTest.java`
- 前端生产 4 改 = `src/api/sse.ts`、`src/components/AiPanel/AiPanel.vue`、`src/stores/ai.ts`、`src/types/ai.ts`
- 前端测试 4 改（`ai-panel-parts-render` / `parts-double-write` / `parts-reducer` / `parts-residue-cleanup`）**+ 1 新** = `tests/b1-suspend-disconnect.spec.ts`
- docs 3 改 = `03-技术方案文档-v2.1.md`、`research/README.md`、`任务看板.md`；docs **新 5** = `research/设计-B1挂起即断SSE-2026-10-20.md` + `evidence/补档-B1挂起即断-{施工报告,复审报告-DS-Flash,复核确认-DS-Flash,实机核验-DS-Flash}.md`
- **`scripts/` 零改动**（`git diff --name-only | grep -c '^scripts/'` = 0，独立复核）
- `?? data/` = 历史遗留，**未动、未入库**（staged 中 `^data/` 计数 = 0）

合计入库 **32 文件 = 25 改 + 7 新建**，`+2121 / −117`（含证据件），锚 = `git diff 4de9399 2a86e1b` sha256 `70578fed49ef5f463d0ea0169c586460950038a05abaf02b5f50e8198a27e739`。

### 1.2 脱敏终扫（密钥/令牌）

三条口径分别扫描，**均 0 命中**（未使用 `AI_SECRETS_ALLOW`，未 `--no-verify`）：

| 扫描面 | 模式 | 命中 |
|---|---|---|
| 已跟踪全量 diff（`git diff`） | `BEGIN …PRIVATE KEY` / `sk-{16,}` / `sk-proj-` / `AKIA…` / `AIza…` / `ghp_…` / `xox[baprs]-` / `api_key="…{16,}"` | **0** |
| 未跟踪新件逐文件（排除 `data/`） | 同上 | **0** |
| 暂存区（`git diff --cached`，提交前终扫） | 同上 + `git diff --cached --check` | **0**（check 0） |

真实密钥一律 `***` 口径；本批无任何真实密钥落盘。

> **DC-08 路径脱敏（提交官受理）**：pre-commit 首跑阻断 42 处，全部为 **PATH 本机绝对路径 / USER 用户名**，**SECRET 类 0 处**。按仓库既有惯例（同 `补档-去超时批-*`、`M2-T1.2-*` 证据件）由提交官做机械占位符替换，**不改事实与结论**：`<REPO_ROOT>` / `<KIMI_HOME>` / `<SESSION_ID>` / `<MAVEN_HOME>` / `<TMP>`。四件证据件各补一行「入库脱敏（提交官）」说明，终扫复跑至 **0 命中**。副作用留痕：各件内 `sha256` / diff 锚均为**脱敏前**工作树实测，脱敏后不再逐字节复现（体例同既有批「审查锚（收口前）」）。

### 1.3 文档门禁 check-docs（证据件新增后复跑）

`node scripts/ci/check-docs.mjs` 在三个时点各跑一次，均 **6/6 断言绿**（①导航无盘符 ②版本一致 ③引用存在 ④端口表一致 ⑤命名纪律 ⑥java 探针不参与构建）：

1. 基线（证据件已在工作树）：6/6
2. 看板 :13 改 ✅ 之后：6/6
3. DC-08 脱敏 + 看板锚定改动之后：6/6

### 1.4 测试门禁复跑

| 门禁 | 命令 | 实测 |
|---|---|---|
| 前端 | `cd frontend && node node_modules/vitest/vitest.mjs run`（未用 `--reporter=basic`） | **504/504 通过 · 25 文件 · 19.04s**（`Test Files 25 passed` / `Tests 504 passed`） |
| 后端 | `<MAVEN_HOME>/bin/mvn.cmd -o -q test`（`backend/`，JDK 21） | **exit 0**；surefire XML 汇总 **91 suite / 576 tests / 0 failures / 0 errors / 0 skipped**（`-q` 抑制汇总行，故按 `target/surefire-reports/TEST-*.xml` 的 `tests|failures|errors|skipped` 属性合计核数） |

任一非绿即中止；本批无中止项。typecheck / build / E2E 与 API 实机核验数字取自施工报告 §7.5 与实机核验件（提交官不改代码，不重复跑 build；E2E 需服务，铁律禁起停）。

### 1.5 看板核对

- 改动前 `docs/任务看板.md:13`（多实例专项行）状态 = **🔧 施工中**，与预期一致（首行 160 字符实测 `| 批·多实例架构专项（B1/B2/B3） | … | 🔧 | **B1 🔧 施工中（2026-10-20 二十九棒…**`）。
- 第一笔改 ✅ 口径（含日期 2026-10-20、五件证据名、门禁数字、锁短哈希位「本批」）；第二笔把「本批」替换为 `2a86e1b`。

## 2 提交与推送

| # | 短哈希 | message | 内容 | push |
|---|---|---|---|---|
| 1 | **`2a86e1b`** | `feat(ai-sse): B1 挂起即断 SSE 与决策后重连补帧（流派 B）` | 32 文件（B1 全量代码/测试/文档 + 看板 ✅ 终态） | `4de9399..2a86e1b main -> main` ✓ |
| 2 | **`cb9f14b`** | `docs(board): B1 批入库锚定 2a86e1b` | `docs/任务看板.md:13` 锚定 1 行 | `2a86e1b..cb9f14b main -> main` ✓ |
| 3 | 见 §3 | `docs(evidence): B1 提交报告入库` | 本报告 1 文件 | 见 §3 |

两笔 commit 均过 pre-commit 敏感信息扫描（`✓ 暂存区无命中`）；origin 永久 URL 保持 HTTPS（`https://github.com/<GITHUB_OWNER>/ai-example.git`），推送走既有远端配置，无改配置。

## 3 CI 确认

| 项 | 实测 |
|---|---|
| run id（批 commit `2a86e1b`） | **39448288101** → conclusion **success**（5/5 job） |
| run id（看板锚定 `cb9f14b`） | **39448326413** → conclusion **success**（5/5 job） |
| 五 job（两 run 同形） | 前端类型检查与构建 ✓ ｜ 文档一致性门禁（check-docs）｜阻断 ✓ ｜ 全历史脱敏扫描（报告制 · 不计门禁）✓ ｜ 后端编译与单测 ✓ ｜ E2E 全流程（29 用例 · 桩上游 + redis）✓ |
| 轮询方式 | `<GH_CLI> api "repos/<GITHUB_OWNER>/ai-example/actions/runs?per_page=3"`（Git Bash 下端点无前导斜杠）+ `…/runs/<id>/jobs`；两 run 在 ~5–8 分钟内出 conclusion，**未触 12 分钟上限** |
| 第三笔（本报告） | 仅新增本文件 1 件、无代码/看板改动；推送后重新触发同一 workflow，结论由提交官回执记录（本文件入库时该 run 尚未产生） |

结论：五 job 全绿，CI 闭环。

## 4 异常与留痕

1. **DC-08 路径脱敏（唯一需要处置的阻断）**：pre-commit 首跑 42 处 PATH/USER 命中（SECRET 0），按仓库既有提交官惯例做占位符替换后 0 命中；四件证据件各加一行脱敏说明。未使用 `${AI_SECRETS_ALLOW}` 逃生口、未 `--no-verify`。
2. **看板 :386-391 挂账小节**（M-2~M-6 / M-9）随第一笔入库，第二笔只动 :13 一行（`1 file changed, 1 insertion(+), 1 deletion(-)`）。
3. **`?? data/`** 三次提交全程未入索引（`git status` 终态仅剩 `?? data/`）。
4. 未发现代码问题；未触发退回条件。
