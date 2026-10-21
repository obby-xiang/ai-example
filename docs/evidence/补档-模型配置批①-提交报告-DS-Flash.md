# 补档 · 模型配置专项批①（表 + 读路径，行为等价）提交报告（DS-Flash，2026-10-21）

## 0 自证与入库锚

- 提交官自证（wire `profile.bind` 单行，**全文件 grep 命中数 = 1，未用 `head -1`**；该行是文件第 5 行，前 4 行是 metadata / runtime 绑定 / 工具发现）：`agentId=agent-1347`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 入库锚：**合并笔 `a6a93cd`**（`a6a93cd941c8d57969e796c32d18141fb0433fb1`，`--no-ff` 合入 main，父 = `edccc1f` + `5f302f2`）；本件所在 docs 笔的 sha **不可自引用**（`git log --oneline -3`）。
- 分支态：`feature/model-config-b1` 基点 `d8056d5`，HEAD `5f302f2`（施工 `6c19f63` + 收口 `5f302f2`）。

## 1 门禁（合流后实测；口径以本表为准）

| 面 | 命令 | 实测 | 判据 |
| --- | --- | --- | --- |
| 后端 | `cd backend && mvn -q -o test` | **859 / 859 · 136 suite · 0F / 0E / 0S**（**连跑 2 次**，两跑同数） | `Tests run: 859, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`；suite = `TEST-*.xml` **136** 件（`-- in` 行 138，多 2 行为 `GenerativeFormGateTest` 重复打印） |
| 前端 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **522 / 522 · 27 文件**（`exit 0`，**0 Unhandled Rejection**） | `Test Files 27 passed (27) / Tests 522 passed (522)` |
| typecheck | `vue-tsc --noEmit` | **exit 0**（0 错） | 见 `-施工报告-` §4 |
| build | `npm run build` | **exit 0**，`✓ built in 34.58s` | 同上 |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 断言绿**（1436 路径 / 实扫 1433 / 豁免 3） | 六条逐条 `[PASS]` |
| 脱敏 | `bash scripts/githooks/pre-commit` | **0 命中**（两次提交的钩子均 ✓，未用 `AI_SECRETS_ALLOW` / `--no-verify`） | `✓ [pre-commit] 敏感信息扫描通过` |

**数字对账**：859 = 主线 811（含防护批① + B23L1 批②③ + L-19）+ 分支净增 48（施工 43 + 收口 5），与任务书预判的"811 + 48 = 859 量级"**逐数吻合**；前端 522 = 防护批① 收口后的主线数，本批**零前端文件**。

## 2 合流

`git merge --no-ff feature/model-config-b1`（`merge: 模型配置专项批①（表+读路径，行为等价）`）→ `a6a93cd`，共 43 文件（27 A / 15 M / 1 D，+3951 / −161）。

## 3 冲突处置（两处真冲突 + 一处语义冲突 + 四处自动合）

| 文件 | 复审预判 | 实测 | 处置 |
| --- | --- | --- | --- |
| `ai/config/AiProperties.java` | 可能冲突 | **冲突**（同锚点插字段：主线 `Lock`/`Input`/`Limit` vs 分支 `Config` + 嵌套类） | 三字段与嵌套类**全留**，顺序 = `lock → input → limit → config`（防护批① 既有顺序不动、模型配置放最后）；`Config` 嵌套类体整段保留；`verifyRateLimit()` 等两个 `@PostConstruct` 守卫原位 |
| `ai/web/AiController.java` | 若撞则两边都留 | **冲突**（`frameStore` 之后的字段注入区） | `inputSanitizer` / `aiRateLimiter`（防护批①，`@Autowired` 必需）在前，`aiConfigService`（模型配置，`required=false`）在后，三者全留；`/health` 扩展区与 `startupResume` 分支区各自自动合、无重叠 |
| `application.yml` | 相邻可能撞 | **自动合** | `app.ai.config.*`（近 `app.ai` 段首）与 `app.ai.input/limit`（`tool-result` 之后）分处不同段；YAML 解析复验四键齐备 |
| `ai/run/RunStore.java` | clean 或简单 | **自动合** | 分支只加 7 参 `create` 重载 + 两行 setter（轮次快照区），批③a 的 `idem`/`claim`/`fence` 区不相交 |
| `ai/run/ResumeService.java` | 区域不相交 | **自动合** | 分支只删 `@Conditional(AiKeyPresentCondition)` 与两条 import；L-19 的 `Outcome#releasesLease` / `verifyHeld(owner)` 原样 |
| `conformance/InMemoryRunStore.java` | clean 或简单 | **自动合** | 分支改覆写 7 参 `create`（`providerId`/`modelId` 两行）；批③a/b 的 `idemTable` / `tryClaimExecution` / `seedHeldRunEpoch` 原样 |
| `ai/config/AiAvailabilityDynamicTest.java` | **未预判** | **语义冲突**（编译期：`chat(AiChatRequest)` 已变 `chat(AiChatRequest, HttpServletRequest)`） | 加 `chat(controller, request)` 适配器（固定传 `null` ⇒ `clientIp` 归 `"unknown"` 桶，20 次/分对 3 次请求不构成瓶颈）+ `controller()` 用 `ReflectionTestUtils` 灌入 `InputSanitizer` / `AiRateLimiter`（与 `AiControllerChatGateTest` 同口径）。**断言零改动** |

**为什么必须适配而不是回退**：防护批① 给 `/chat` 加的 `HttpServletRequest` 是限流取来源 IP 的唯一入口；把它退化成"可空/重载旧签名"会让防护批① 的既有 11 处用例与安全语义一起松动。适配层只解决"用例不需要来源 IP"这一件事。

## 4 冒烟（"行为等价"合流质量闸）

桩模式真启动 jar（`E2E_STUB_MODE=1` 桩上游 18399 + H2 文件库 + 端口 8081，先 `netstat` 确认两端口空闲），复现 CI 的启动方式（`scripts/ci/stub-upstream.mjs` + 同一套 `AI_*` / 限流抬高参数）：

| 步骤 | 实测 |
| --- | --- |
| 桩上游 | `curl :18399/probe` → `{"ok":true,"stub":"ci-upstream"}` |
| 后端就绪 | `GET /api/ai/health` → **HTTP 200**（1s 内） |
| **`available`** | **`true`**，`code=AI_AVAILABLE` |
| **`providerId`** | **`1`** |
| **`defaultModelId`** | **`ci-stub`** |
| **`providers`** | `[{"id":1,"code":"deepseek","name":"DeepSeek","enabled":true,"keyConfigured":true,"keyRef":"ENV","source":"ENV_SEED","status":"OK"}]` |
| 旧键等价 | `model=ci-stub`、`baseUrl=http://127.0.0.1:18399` —— 与 env 三键**逐值相同** |
| `/api/ai/providers` | 1 行；键集 = `baseUrl/code/completionsPath/displayName/enabled/id/keyConfigured/keyLast4/keyRef/models/modelsPath/protocol/source`，**无 `apiKey`/`keyCipher`/`keyFingerprint`**；`keyLast4="lder"`（`ci-stub-placeholder` 末 4 位） |
| 泄密面 | `boot-smoke.log` grep 桩 key ⇒ **0 命中** |
| 收尾 | 后端与桩 `taskkill` 完成；`netstat` **LISTEN 计数 = 0**（8081 / 18399 均归零，仅剩 TIME_WAIT 客户端套接字） |

**判据**：这条冒烟同时证了两件事——(a) 空表 + env 的**种子 → 读路径 → `/health`** 全链在真启动下成立（`source=ENV_SEED`、`keyRef=ENV`、`keyConfigured=true`）；(b) 防护批① 的入口闸门与模型配置的 `/health` 扩展在同一进程里共存无冲突（`/chat` 可用性门 + 新键并存）。

## 5 CI

| 笔 | run | 结论 |
| --- | --- | --- |
| **合并笔 `a6a93cd`** | **`39549829243`** | **最终 success（5/5 全绿，含 E2E 29 用例）**：后端 ✓ 2m1s / 前端 ✓ 1m42s / check-docs ✓ 7s / 脱敏报告 ✓ / **E2E ✓ 2m7s**。**attempt1–3 各红 1 例**（同一既有抖动：`ResumeServiceLeaseTest#concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease`，`expected [ACCEPTED, IN_PROGRESS] but was [ALREADY_RUNNING, ACCEPTED]`），按 L-19 既定处置 `gh run rerun --failed` 至 **attempt4 绿**（未改用例、未跳过、未 `--no-verify`） |
| docs 笔 `9fb5564` | **`39549954121`** | **success（5/5 全绿，首跑即绿）**：后端 ✓ 2m42s / 前端 ✓ 1m16s / check-docs ✓ 8s / 脱敏报告 ✓ / **E2E ✓ 1m53s** |
| 本件回填笔 | — | sha/run **不可自引用**；结果记入提交官台账行 |

**E2E 口径**：CI 的 E2E job 跑 `verify-e2e.ps1`（主计数 **29 用例** + 2 条 GF 例外计数），桩模式；`E2E_STUB_MODE=1` 下 `GFSKIP≥1` 即整棒 FAIL（零容忍）。本批**零前端改动、零帧变更**，两次绿的 E2E 均 29 用例全过。

**关于 attempt1–3 的红（关键判据：非本批引入）**：

1. **零 diff**：`git diff edccc1f..a6a93cd -- backend/src/test/java/.../ResumeServiceLeaseTest.java` **为空**；该用例最后一次改动是 `a96cd06`（L-19 修复笔，本批之前）。
2. **同码异果**：同一份 Java 代码在 docs 笔 run `39549954121` 与合并笔 attempt4 **都绿**；本机 `mvn -o test` **连跑 2 次 859/859**。⇒ 抖动、非确定性回归。
3. **机理**：用例的 `CyclicBarrier` 只架在 `acquireRunLease` **之前**；胜方若在败方进入 `resume` **之前**已把快照推进到 RUNNING/收尾，败方看到 `ALREADY_RUNNING` —— 仍是"恰一个驱动"，L-19 的**产品面**（归还白名单）不受影响，是该断言把 `IN_PROGRESS` 钉死过严。
4. **处置**：rerun `--failed` + 在看板 L-19 行**补登记**（见 §6），**不扩权改用例**（断言收紧/放宽属该用例维护方）。

## 6 看板 / 决策 / 设计稿留痕

| 面 | 位置 | 改动 |
| --- | --- | --- |
| 任务看板 | `docs/任务看板.md`（10-21「批·模型配置功能」行） | 状态 📋 → **✅ 批①**，回填合并笔 `a6a93cd` + 门禁数字；批②③ **仍 📋**（明确"批① 已结、批②③ 待发令"） |
| 任务看板 | `docs/任务看板.md`（速览行） | 批次计数 ✅47 → **48**、📋 14 不变（总 66 不变）+ 补「10-21 补记第六批」 |
| 决策看板 | `docs/决策看板.md`（10-21 段首行） | 新增「模型配置批① 合流裁决」行：`AiProperties` 三方合流解法（lock→input→limit→config）+ 行为等价验收达成（含冒烟证据）+ 批②③ 待发令 |
| 设计稿 | `docs/research/设计-模型配置功能专项-2026-10-21.md` §11 | 批① 行标 **✅（已入库 `a6a93cd`）**，回填门禁与冒烟口径；批②③ 措辞不变 |

## 7 遗留（本提交官的边界）

1. **批②/批③ 未做**（写路径 / 设置页 / 对话面板选择器 / 按快照续跑）：本批只结批①。
2. **复审 8 条登记项**（M5/M6/M7/N1/N3/N4/N5/N6）随 `-复核确认-` §3 转批②/批③，**本轮未修**。
3. **基线既有失败 = MAJOR-2**（R-137 红队补测的取消时序，非 29 主用例）：分支与基线 `d8056d5` 逐条相同，**非本批引入**；CI 若因此红按 L-19 口径处置（rerun `--failed` 并注明）。
4. **施工 worktree `wt-model-config-b1` 只读未改**（终态 `5f302f2`）。
5. **`?? data/` 零触碰**；提交纪律 = 全程显式路径 `git add -- <path>`，**未用 `-A` / `.`**。
