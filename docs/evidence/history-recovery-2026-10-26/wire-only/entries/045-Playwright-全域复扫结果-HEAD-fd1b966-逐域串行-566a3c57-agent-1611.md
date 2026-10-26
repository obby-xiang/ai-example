> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1611` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：8255 B / 5917 字符 / 74 行；行指纹覆盖率（仓内 + 落盘语料）5.1%
> - 类别：**施工复核**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## Playwright 全域复扫结果（HEAD `fd1b966`，逐域串行，端口各自归零后跑下一域）

### 一、逐域数字

| # | 域 | 命令（cwd=`frontend`） | passed | failed | skipped | 耗时 | 对基线 |
|---|---|---|---|---|---|---|---|
| 1 | 主 config @stub 全量 | `npx playwright test` | **181** | **57** | **31** | 24.5m | 269 收集数 ✓（非绿数，见 §3） |
| 2 | HGTA | `npx playwright test --config playwright.hgta.config.ts e2e/11a-hgta.spec.ts e2e/11b-hgta.spec.ts` | **34** | 0 | 0 | 1.7m | ✓ 34 |
| 3 | NFEZUI | `npx playwright test --config e2e/playwright.nfezui.config.ts` | **48** | 0 | 0 | 2.7m | 基线 47+1skip ⇒ **+1 转绿、0 skip** |
| 4 | GF2 | `npx playwright test --config e2e/playwright.gf2.config.ts` | **1** | 0 | 0 | 21.4s | ✓ 1 |
| 5 | KQC 主档 | `E2E_STUB_SCRIPT=<repo>/frontend/e2e/support/kqc-upstream.mjs E2E_BACKEND_PORT=21730 …21802 E2E_REDIS_DB=14/15 npx playwright test e2e/16-kqc.spec.ts` | **23** | 0 | 0 | 42.5s | ✓ 23 |
| 6 | KQC 特殊档 | `E2E_KQC_SPECIAL=1` + `--app.ai.kb.dir=<repo>/scripts --app.ai.context.limit-tokens=0` + 197xx + DB 14/15 + kqc 桩 | **2** | 0 | 0 | 22.8s | ✓ 2 |
| 7 | KQC 压缩档 | `E2E_KQC_COMPACT=1` + `--app.ai.context.limit-tokens=20000` + 229xx + DB 6/6 + kqc 桩（跑前清 `E2E_RUN_DIR`） | **4** | 0 | 0 | 32.5s | ✓ 4 |
| 8 | 解锁主档 | `E2E_BACKEND_PORT=19930 …20002 E2E_REDIS_DB=8/9 npx playwright test e2e/17-unlock.spec.ts` | **20** | 0 | **1** | 59.2s | ✓ 20+1skip（首跑 19+1F/1S，见 §2） |
| 9 | 解锁缩阈值档 | 同上端口 + `--app.ai.tool.exec-timeout=1ms --app.ai.resilience.total-budget=8s --app.ai.hitl.frontend-tool-timeout=4s` | **3** | 0 | 0 | 51.6s | ✓ 3 |
| 10 | VL | `npx playwright test e2e/12a-vl-vis.spec.ts e2e/12b-vl-tol.spec.ts`（189xx/19001 自起栈，**先清 `.e2e-run-vl/stub`**） | **26** | **2** | 0 | 3.2m | 基线 28 ⇒ **−2** |
| 11 | MDXY（主域内被收集） | 213xx + DB 13/11 + `E2E_STUB_SCRIPT=…/mdxy-upstream.mjs` + `AI_SECRET_KEK=<base64 32B>`，跑 14a+14b+14c | **30** | **1** | 0 | 1.2m | 基线 31 ⇒ −1 |
| 12 | MDO（主域内被收集） | 215xx + `E2E_REDIS_DB=E2E_REDIS_DB_DEGRADED=12` + `--app.ai.hitl.frontend-tool-timeout=15s`，跑 15-mdo | **21** | 1（另见下） | 0 | 2.1m | 行为 22/22 绿（详见 §2） |

> 路径更正：HGTA config 实际在 `frontend/playwright.hgta.config.ts`（不在 `e2e/` 下）；且该 config 无 `testMatch`，必须显式传 11a+11b 两个 spec，否则会收集全量 269 条。

### 二、红条隔离复验（连跑 2 次）

| 用例 | 首次出现 | 主跑 | 隔离×2 | 结论 / 定因 |
|---|---|---|---|---|
| **E2E-P06** `10-srp:964` | 主域 | fail | **fail / fail** | **真红（用例过期）**：`#gf-field-f_text` 元素不存在。L-92（`4eee00c`）把**只读回放卡** DOM id 改为 `gf-field-<scope>-<key>`（`FormRenderer.vue:470`），停止后的表单卡正是只读卡；提交称“既有 `#gf-field-*` 用例零改动”未覆盖此路径 |
| **E2E-V19** `12a:567` | 主域 + VL 整档 | fail | **fail / fail** | **真红（用例过期）**：收到 8 个回执码（含 `UI_DOWNLOAD_TIMEOUT`），断言仍 `toHaveLength(7)`。该码由 `6321424`（L-82，15:12）加入，VL 用例在 12:36 定型后未同步 |
| **E2E-MD01** `14a:29` | 主域 + MDXY | fail | **fail / fail** | **真红（用例过期）**：`body.locator('s').count()` 期望 0 实得 1。`75530fd`（L-86）把 `s` 加入 DOMPurify 白名单，`~~删除线~~` 由“被剥元素”变“正常渲染”，旧断言按旧缺陷写成零 |
| **E2E-V17** `12a:511` | 主域 + VL + 定向 1 次 | fail（4/4） | fail / fail | **真红、未定位**：`createPublishedImportTask` 的 **PRECHECK 作业终态 FAILED**（`Expected "COMPLETED" Received "FAILED"`）。事后取不到作业原因（harness 硬杀 JVM，H2 无 checkpoint 落盘）。需后端侧看 job `RESULT_JSON` / `validation_issues` |
| **E2E-A09** `17-unlock:110` | 主域 + 解锁首跑 | fail | **pass / pass** | **flake**：整档第 2 条、连挂 2 次；单跑 2/2 绿、整档重跑转绿 ⇒ 负载/次序 flake，基线 20+1skip 复现 |
| **E2E-H08** `11a:393` | 主域 | fail | **pass / pass** | **flake**：HGTA 整档 34/34 也绿 |
| **E2E-V14** `12a:381` | 主域 + VL 首跑 | fail | fail/fail（旧桩目录）→ **清目录后 pass** | **环境/卫生 flake**：桩落档序号每棒从 `req-001` 重来，而 VL harness 不清 `stub` 目录 ⇒ `untilStubRequestAfter('002')` 永等不到新序号。清空 `.e2e-run-vl/stub` 后单跑绿（51.4s） |
| **E2E-O02b** `15-mdo:573` | MDO | “Expected to fail, but passed” | — | **挂账缺陷已修**：`test.fail` 标记过期，行为上 22/22 全绿 |

### 三、主域 57 红的构成（重要口径澄清）

**269 是 `--list` 的收集数，不是绿数**（看板原话即 `--list 269 tests / 27 files`）。主 config 无 `testMatch`，会把**别域 spec 一并收集**，而那些 spec 必须用各自的桩/端口段才可能绿：

- 结构性红 **50 条**：`13*/13b/13c/13d` 23（硬编码 19130，主配置下必 `ECONNREFUSED`）、`14a` 12、`14c` 4、`14b` 3（需 mdxy 桩 + KEK）、`15` 5（需双 JVM 同 Redis DB）、`17b` 3（需缩阈值启动参数）
- 真红 **3 条**（P06 / V19 / MD01，均为今日改动使断言过期）
- 未定位真红 **1 条**（V17）
- flake **2 条**（A09 / H08）
- `test.fail` 意外通过 **1 条**（O02b，计为 failed，`ok` 行 182 与 `passed` 181 的差额即它）
- `31 skipped` = 16-kqc 23 + 16-kqc-b 2 + 16-kqc-c 4（自带闸门）+ 10-srp S04 + 17-unlock R18

即：**主栈 + 官方桩的可跑切片（00–10、11a/11b、12a/12b、17-unlock）只余 P06/V17/V19 三个确定性红与 A09/H08 两个 flake**，其余全绿。

### 四、与看板基线的逐值对照

| 基线 | 本次 | 判定 |
|---|---|---|
| 主 269 | 269 收集（181P/57F/31S） | 收集数一致；“269 全绿”不可达（跨域收集） |
| HGTA 34 | 34P | ✓ |
| NFEZUI 47+1skip | 48P / 0skip | +1 转绿 |
| gf2 1 | 1P | ✓ |
| KQC 主 23 / 降级 2 / 压缩 4 | 23 / 2 / 4 | ✓ ✓ ✓ |
| 解锁 20+1skip / 预算 3 | 20+1skip / 3 | ✓ ✓ |
| VL 28 | 26P + 2F（V17/V19） | **−2** |
| MDXY 31（主域内收集） | 30P + 1F（MD01） | −1 |
| MDO 22（主域内收集） | 21P + 1 挂账意外通过 | 行为全绿 |

### 五、遗留问题 / 建议（均为“用例或运行卫生”，非产品回归）

1. **三处用例过期，建议改断言（不改产品）**：`e2e/10-srp.spec.ts:972`（改用 `[data-testid="gf-field-<scope>-f_text"]` 或断言“无提交钮 + 只读回执”）、`e2e/12a-vl-vis.spec.ts:592`（7→8 码）、`e2e/14a-mdxy-md.spec.ts:45`（`s` 计数 0 的旧缺陷断言应改为“渲染为 `<s>` 且不漏标记”）。
2. **`e2e/15-mdo.spec.ts:573` 的 `test.fail` 可撤销**（O02b 已修，否则每次整档都报红）。
3. **V17 待后端定位**：PRECHECK 作业 FAILED，建议在 harness 收尾前 dump `GET /api/jobs/{id}` 或改为优雅停机以保留 H2。
4. **运行卫生两处**：VL 域跑前需清 `test-results/.e2e-run-vl/stub`（同 KQC 压缩档的已知问题）；主 config 收集跨域 spec 的问题建议用 `testIgnore` 或按域分步跑（`e2e-real.yml` 的 `npx playwright test --grep @stub` 也会撞上这 50 条结构性红）。
5. **产物目录冲突**：`playwright.config.ts` 的 `outputDir` 硬编码 `test-results/artifacts`，多域串行会互相覆盖 trace（本次复验时 V17 首跑 trace 就被 MDXY 覆盖）。

### 六、现场状态

- **端口**：所有测试端口段（183xx/187xx/189xx/190xx/191xx/197xx/199xx/200xx/213xx–218xx/225xx/229xx）LISTENING 均为空，仅常开 8081/5202 在（全程未起停、未占用）。我自建的探针桩（23999）已收。
- **文件**：我未做任何写操作。但 `git status` 现显示 `M docs/任务看板.md`、`M docs/决策看板.md`（mtime 21:15，+44/−19）——**不是我改的**，应为并行写回，提请留意；仓库根 `data/` 未跟踪目录为复跑前既有。
- 日志证据：`<LOCAL_WORK>/_pw-rescan/{main,hgta,nfezui,gf2,kqc-main,kqc-special,kqc-compact,unlock,unlock-r2,unlock-b,vl,vl-r2,mdxy,mdo,*-iso*}.log`。
