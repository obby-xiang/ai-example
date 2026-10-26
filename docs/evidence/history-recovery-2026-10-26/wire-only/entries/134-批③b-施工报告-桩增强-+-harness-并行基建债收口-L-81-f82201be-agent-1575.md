> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1575` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：11635 B / 7846 字符 / 86 行；行指纹覆盖率（仓内 + 落盘语料）4.7%
> - 类别：**正式独立报告**
> - 脱敏：路径 4 / 会话标识 2 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 批③b 施工报告 · 桩增强 + harness 并行基建债收口（L-81）

**身份自证**：我的 wire 是 `sessions/<WORKSPACE>/session_<KIMI_SESSION>-.../agents/agent-1575/wire.jsonl`，`profile.bind` = `modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`（thinkingEffort=high）。

**未做 git commit**；未碰 `frontend/src`、`backend`。

## 0. 必须先说明的施工环境（影响所有门禁数字的解读）

并联那路缺陷修复子智能体在本次施工期间**正在改 `backend` + `frontend/src` + `frontend/e2e/10-srp.spec.ts` / `13b-nfezui.spec.ts`，并在我首轮 E2E 跑到一半（12:51）重建了 `backend/target/classes`**。首轮主 config 因此吃到"半成品后端"：`E2E-R04`/`E2E-P08` 拿到 500 而非 409（49 passed / 2 failed / 1 skipped，日志 `<LOCAL_WORK>/_b3b-probe/pw-main.log`）。

为给出可复现、可归因的证据，**全部门禁数字改为在"HEAD 干净副本 + 仅我的 diff"上跑**：`git archive HEAD` → `<LOCAL_WORK>/_b3b-head`，用 `git apply` 只贴我的 17 个文件（`<LOCAL_WORK>/_b3b-probe/mine.diff`），`node_modules` 走 junction，后端在副本里 `mvn -DskipTests package`。跑完已删除副本与 junction（仓库 `frontend/node_modules` 完好），证据日志留在 `<LOCAL_WORK>/_b3b-probe/`。

## 1. harness 统一形态（A · L-81）

`frontend/e2e/support/harness.ts` 现在是**全仓唯一**一套起停，一切隔离只靠 env（相对路径按 `frontend/` 解析）：

| 变量 | 默认 | 含义 |
|---|---|---|
| `E2E_RUN_DIR` | `test-results/.e2e-run` | 运行目录（日志 / `state.json` / H2 都在这棵树下） |
| `E2E_STATE_FILE` | `<RUN_DIR>/state.json` | pid 台账 |
| `E2E_DATA_DIR` / `E2E_DEGRADED_DATA_DIR` | `<RUN_DIR>/data(-degraded)` | 两个 H2 目录（jdbc URL 用绝对路径） |
| `E2E_BACKEND_PORT` / `E2E_DEGRADED_BACKEND_PORT` / `E2E_STUB_PORT` / `E2E_STUB_UPSTREAM_PORT` / `E2E_APP_PORT` / `E2E_DEGRADED_APP_PORT` | 18330 / 18331 / 18399 / 不启 / 18401 / 18402 | 端口 |
| `E2E_REDIS_DB` / `E2E_REDIS_DB_DEGRADED` | 5 / 6 | Redis 库 |
| `E2E_AI_LIMIT_SESSION_PER_MINUTE` / `E2E_AI_LIMIT_IP_PER_MINUTE` | 200 / 2000 | 限流抬高 |
| `E2E_EXTRA_BACKEND_ARGS` | 空 | 追加 java 参数（空格分隔，如 `--app.ai.suspend.pool-size=3`） |
| `E2E_READY_TIMEOUT_MS` / `E2E_SEED_TIMEOUT_MS` / `E2E_APP_READY_TIMEOUT_MS` / `E2E_STUB_READY_TIMEOUT_MS` | 120000 / 90000 / 90000 / 30000 | 就绪等待 |
| `E2E_STUB_SCRIPT` | `scripts/ci/stub-upstream.mjs` | 本域桩脚本（配 `E2E_STUB_UPSTREAM_PORT` 自动加 `--upstream`，NFEZUI 的双桩链就靠这两项） |

语义收口（与旧行为对齐）：降级库只在本棒真起降级面时才 FLUSH（起/收对称）；`state.json` 增 `ownerPid`，**owner 还活着就不收尾**（第二棒 setup 失败不再误杀第一棒），owner 相同或已退出才按台账 + 内存子进程表清理；新增 `redisCommand(db,args)`（NFEZUI 的 TTL 断言与清键共用）。

两份 fork 收敛为**薄壳**（删掉会破 `check-docs ⑤`「git ls-files 文件缺失」，也保留旧 import 兜底）：

- `support/harness-hgta.ts`、`support/harness-nfezui.ts` → 只剩 `export * from './harness'` + `@deprecated` 说明（各 −483 / −434 行）。
- 域默认值搬进配置加载期（`??=`，显式 env 永远优先）：
  - `playwright.hgta.config.ts`：`E2E_RUN_DIR=.e2e-run-hgta`、18730/18731/18799/18801/18802、DB 11，**baseURL 由 `E2E_APP_PORT` 派生** → 修掉 L-81② 的"裸跑落 18330 而 baseURL 是 18801 ⇒ 27 条 refused"。
  - `e2e/playwright.nfezui.config.ts`：`.e2e-run-nfezui`、19130/19131/19198/19199/19201/19202、DB 13/14、`E2E_STUB_SCRIPT=e2e/support/nfezui-upstream.mjs`、`suspend.pool-size=3`、限流 400/4000、超时 150/120/120。
  - `global-setup/teardown-nfezui.ts` 改调 `startServices/stopServices`；`hgta.ts`、`hgta-global-setup/teardown.ts` 改 import `./harness`；`helpers-nfezui.ts` 的 API/APP 基址改为从 `E2E_*_PORT` 派生（env 改端口即整域跟动）。

## 2. 桩增强四项（B）

改 `scripts/ci/stub-routes.json` + `scripts/ci/stub-upstream.mjs`（`faults.switches` 30 项：桩层 27 / 脚本层 2 / 产品层 1）：

1. **上传 ref 漂移（L-77）**：`UI-UPLOAD.arguments.ref` `import.upload.entry` → **`import.upload.choose`**（产品事实源 `ImportWizardView.vue:55` 的 `data-ai-ref`/`:1353` 的 `uploadEntry`）。A06 仍绿——它断的是"本轮没快照 ⇒ 恒拒"，与 ref 存在与否无关；注释已按新因果改写。
2. **分面延迟注入**：`slow-first-byte=<ms>`（响应头后、首帧前的**首包段**，文本轮同样生效）、`slow-tool-exec=<ms>`（**只**工具轮 / 工具续轮首帧）、`slow-tail-frames=<ms>`（**只**工具续轮的帧间）。`writePlan(faults, round)` 接收段身份（`kind` / `hasToolResult`），不传 round 时三个新开关全不生效 ⇒ 旧计划逐值不变；`stall-headers`/`stall-frames`/`slow-tool`/`slow-reader` 保持原义。
3. **请求级工具路由**：提示词写 `tool=<name>&args=<json>`（平衡括号解析，支持嵌套与字符串里的 `}`）。三道闸：`productTools` 白名单 → `requiresTools` 披露闸 → **同提示词幂等闸**（同 fingerprint 只发一次，防重试风暴）。**不占路由表 `bucket.used`**（"每路由一次"台账口径未动）。
4. **done 后注入帧 + epoch**：`late-frames=<n>` 在整轮（含 `[DONE]`）之后继续推 n 帧；`epoch=<nonce-N>`（形状与产品 Guard `^[a-z0-9]{6}-\d+$` 同源）把本轮工具入参里**已有的** `epoch` 替换为真值、不新增键。V 域模型侧成功路径的实际用法：先一轮 `tool=ui_snapshot&args={...}`（页面真快照、`snapshotRefEpoch` 落在当前 epoch），下一轮 `tool=ui_click&args={"ref":"import.upload.choose","epoch":"a1b2c3-1"} epoch=<页面读完的真值>` ⇒ 围栏与快照闸同轮次/同 epoch 通过。

`--self-check` 新增段 ⑩（请求级工具嵌套 args / 披露闸 / 产品清单闸 / 幂等；epoch 只改既有键；迟到帧帧序与内容）与 ⑨-c2（同一开关在文本轮/工具轮/工具续轮只命中各自那段）；`scripts/README.md` §2.3c 开关表同步。

## 3. 门禁证据

| 门禁 | 结果 |
|---|---|
| `stub-upstream.mjs --self-check` | **PASS**（含新段 ⑩；主仓与干净副本各跑一次均绿） |
| `verify-e2e.ps1`（自建 18399 + 18330 + Redis DB 7 + 仓外 H2，`E2E_STUB_MODE=1 -EnableGf6`） | **PASS=144 / FAIL=22**（与 V5 基线同总数）；**批① 108/130**（基线 109/130）；MANIFEST 自校验 PASS；GFSKIP=0；Q8 2/0 |
| Playwright 主 config（`00`~`09` + `10-srp`） | **51 passed + 1 skipped**，0 failed（5.5 min；P12 的 `test.fail` 在 1.64 的 line reporter 里计入 passed —— 已用微型 spec 实测该 reporter 语义） |
| HGTA config | **34 passed**（1.7 min，端口 18730/18799/18801 由配置默认值起） |
| NFEZUI config | **46 passed + 1 failed + 1 skipped**；失败是 `E2E-E07`（降级面首屏白屏，冷启动三 vite 竞争），**targeted 复跑 36s 转绿**；skipped = Z06 fixme（HEAD 口径） |
| VL（主 config + 单栈 18930/18999/19001 + `E2E_EXTERNAL=1`） | 第 1 轮 26 pass + 2 fail（V05/V06）→ V06 targeted 转绿；**第 2 轮全量 28 passed**（2.0 min）；V05 的失败点在**任何产品/harness 调用之前**的 `page.mouse.wheel`（布局相关 flake） |
| 前端全量 vitest（干净副本） | 1361/1362，唯一红是 `sse-parser-boundary C2-03` **5000ms 超时**（全量并发负载）；该文件隔离复跑 **29/29 绿** |
| `vue-tsc --noEmit` | **0**（另用一次性 tsc 对 `e2e/support/harness*.ts` + 两个 config + 四个 setup/teardown 单独严格类型检查：仅 1 条既有 `helpers-nfezui.ts` 的 `/src/stores/ai.ts` 浏览器内动态 import 报错，非本次引入） |
| `check-docs.mjs` | **6/6 绿**（删 fork 会破 ⑤，故采用"薄壳"方案） |

**既有 130 条零回归的逐条对照**：红集与 V5 基线 `diff` 仅两处 —— **新增 `AIE2E-W-40`**、**消失 `MAJOR-2`**（后者由状态偶然性转绿）。W-40 已用 A/B 复现证明**与我无关**：把 verify 运行里桩落档的 `req-227.json`（SHEET-UNDO 轮，历史里带上一轮 `role=tool`）原样回放进 **HEAD 原版桩**（18598）与**我的桩**（18599），两者判定**逐字节相同**（都走"续轮（上下文中已有工具结果）"⇒ 文本），即 HEAD 起就红，属桩"续轮只发文本"这条收敛契约对"上一轮工具结果已进历史"的既有盲区，不是本次改动引入。

## 4. 逐文件改动（全部在我的工作树，未提交）

- `scripts/ci/stub-routes.json`（+70/−…：UI-UPLOAD ref；6 个新开关登记）
- `scripts/ci/stub-upstream.mjs`（+281：段身份计划、请求级工具、epoch、迟到帧、自检 ⑨-c2/⑩）
- `scripts/README.md`（§2.3c 开关表 + 自检段说明）
- `frontend/e2e/support/harness.ts`（+213：env 全参数化、二级桩链、ownerPid 收尾、条件 FLUSH、`redisCommand`）
- `frontend/e2e/support/harness-hgta.ts` / `harness-nfezui.ts`（→ 薄壳）
- `frontend/e2e/support/hgta.ts` / `hgta-global-setup.ts` / `hgta-global-teardown.ts`（改指统一 harness）
- `frontend/e2e/support/global-setup-nfezui.ts` / `global-teardown-nfezui.ts`（改调统一 harness）
- `frontend/e2e/support/helpers-nfezui.ts`（基址从 `E2E_*_PORT` 派生；`redisCommand` 改自统一 harness）
- `frontend/e2e/playwright.nfezui.config.ts`、`frontend/playwright.hgta.config.ts`（域默认 env）
- `frontend/e2e/11a-hgta.spec.ts`（仅注释：端口/env 归属与 A06 因果更新）

## 5. 遗留（如实）

1. **W-40 是 HEAD 的存量红**（非本次引入）：桩的"续轮只发文本"用 `messages.some(role==='tool')` 判定，把"上一轮工具结果已进历史的新用户轮"也当续轮 ⇒ SHEET-UNDO 轮发不出工具。修它属桩收敛契约变更，会影响 130 条既有口径，未擅自动；建议单开小批。
2. **VL 域仍未真正合流**：`support/helpers-vl.ts` 自带栈（18930/18999/19001 + `.e2e-run-vl`，env 已可参数化）。要彻底收口需新增 `playwright.vl.config.ts` + 把 `ensureVlStack/stopVlStack` 换成统一 harness（并要求 VL 夹具不再混用 `helpers.api`——L-81③ 的跨栈耦合仍在）。
3. **新桩能力还没有用例承接**：`slow-first-byte`/`slow-tool-exec`/`slow-tail-frames`/`late-frames`/`tool=&args=`/`epoch=` 目前只有 self-check + 我的 HTTP 探针（`/e_temp/_b3b-probe/probe.mjs` 结果：请求级工具透传、未披露闸退化文本、epoch 注入只改既有键、late-frames 在 `[DONE]` 后推 2 帧、工具段延迟只命中工具轮）。A-07~A-10 / L-02~L-08 / S-09·S-11 / R-03 / P-04 / S-12 / V 域成功路径的**用例**留给后续批（本次任务只到基建）。
4. **flake（已留证，非本次引入）**：`E2E-E07` 冷启动白屏（重跑绿）、`V05` 真鼠标滚轮（二次全量绿）、vitest `C2-03` 全量并发超时（隔离绿）。
5. **看板/设计稿未改**（不在本次授权范围）：`docs/任务看板.md` 的批②行仍写着 `harness-hgta/nfezui` 副本；`docs/research/设计-真机E2E用例全集-2026-10-23.md` 未见 env 表，建议合流笔时补一行指向 `harness.ts` 抬头表格。
6. 副作用物：`backend/target/e2e-classpath.txt`（构建产物，已在 gitignore 面）；仓库根 `data/` 与 `frontend/tests/ui-undo-stop-reset.spec.ts` 是**另一路**的工作产物，我未动。8081/5202 全程零起停（pid 15880 / 16792 未变），自建端口现已全部释放。
