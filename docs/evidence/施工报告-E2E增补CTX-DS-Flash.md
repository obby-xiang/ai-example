# 施工报告 · E2E 增补批（上下文 CTX 面）（DS-Flash）

## 0. DC-19 首页

| 项 | 值 |
|---|---|
| 执行者模型 | `DeepSeek/deepseek-flash`（profile `coder`，thinkingEffort `high`） |
| 授权来源 | 用户 2026-10-21 指令「前序的任务直接实施」（E2E 增补批 · CTX 部分） |
| 工地 | `<REPO_ROOT>`（worktree，分支 `feature/e2e-ctx`，基线 main `a5539c2`） |
| 本批范围 | E2E-CTX-1..4（4 条用例，主计数 **31 → 35**）+ 三形态 usage 桩（`scripts/ci/stub-upstream.mjs` / `stub-routes.json`） |
| 不在本批 | 「模型配置 E1」子项（依赖批②/③ 合流）→ 见 §8 待办 |
| 纪律 | 禁 commit / 禁 push；未动 `docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`；**零服务启停**（未起 18330/18399/8081/5202 任一端口）；SSE 帧契约 16 **零改动**；密钥/组织名 0 字面（桩内仍只有 `ci-stub-placeholder` 一类占位串） |

### 0.1 wire 自证（第 0 步，先报）

```
$HOME/.kimi-code/sessions/wd_temp_38dcbf7ced2f/session_ced79706-.../agents/agent-1366/wire.jsonl
{"type":"profile.bind","agentId":"agent-1366","modelAlias":"DeepSeek/deepseek-flash",
 "profileName":"coder","thinkingEffort":"high","systemPrompt":"..."}
```

- `modelAlias` = **`DeepSeek/deepseek-flash`**
- `profileName` = **`coder`**
- `thinkingEffort` = **`high`**

## 1. 缺口登记的逐条确认（施工前读到的原文）

| 缺口编号 | 出处 | 设计稿 §5.8 原文（逐条含义） |
|---|---|---|
| E2E-CTX-1 | 设计稿 §5.8 · 看板本批行「缺口登记」① | 正常一问一答（桩返回末块 usage）⇒ `done.usage` 真值 + pill 显示一致 |
| E2E-CTX-2 | 同上 | 桩返回**每块重复 usage** ⇒ 计数不放大（对标业界"1.7M tokens"事故） |
| E2E-CTX-3 | 同上 | 桩返回**末块 0/0/0** ⇒ 取中间真值，`est=false` |
| E2E-CTX-4 | 同上 | 桩**完全不发 usage** ⇒ `est=true` + `≈` 展示 + 压缩仍按估算触发 |
| 三形态 usage 桩 | 设计稿 §6.3「`stub-upstream.mjs` + `stub-routes.json`（三种 usage 桩）」 | 上游替身能按用例需要产出上述 usage 形态 |

**「三形态」vs「四条用例」的口径对账（自决，先登记）**：§5.8 列四条（正常 / 重复 / 末块零 / 无 usage），§6.3 说「三种 usage 桩」。本批按**四种形状**实现（`last`/`repeat`/`zero-last`/`none`），其中 `last` = **既有基线**（今天桩就发的那一份，逐帧逐字节不变，已机械验证见 §3.4），「三形态」= 三个**非基线**形状 `repeat`/`zero-last`/`none`（对应 E2E-CTX-2..4）。两种读法都成立，基线行为零改动。

## 2. 矩阵（场景 × 步骤 × 期望断言 × 落点文件）—— 动工前先行登记

> 「pill 显示一致」在**无浏览器 CI** 里的等价物 = `GET /api/ai/context/{sessionId}`（pill 的唯一数据源）与 `done.usage` / `done.context` **三方同源**；pill 渲染本身由前端 `tests/context-pill.spec.ts`（C-31..C-36）锁，本批不起浏览器、不碰前端。

| 用例 | 场景 | 步骤 | 期望断言 | 落点文件 |
|---|---|---|---|---|
| **E2E-CTX-1** | 正常一问一答，桩发**末块 usage**（基线形状） | ① 新 session `s5b-e2e-ctx-1-*`；② `POST /api/ai/chat`（`context={page:tasks}`，提示词含 `usage-shape=last`）读至终帧；③ `GET /api/ai/context/{sid}` | ① `done` 恰 1 帧；② `usage.est=false`/`source=stream`/`1200`/`40`/`1240`/`dirty=0`/`frames≥1`；③ `done.context.usedTokens=1200`、`est=false`、`level=none`；④ 本轮**无 `context` 帧**（自决⑥：只在该轮真压缩时发）；⑤ 端点 `usedTokens=1200`、`source=stream`、`estimated=false`、`turnNo≥1`；⑥ 端点 `breakdown` 六项 Σ == `usedTokens`；⑦ `percent` 在 `limitTokens` 非空时 == `round(used×1000/limit)/10` | `scripts/verify-e2e.ps1`（E2E-CTX 段 + `Get-CtxShapes`/`Test-CtxCase`）· `scripts/ci/stub-upstream.mjs`（`usageShapeOf`/`textRound`/`usageChunk`）· `scripts/ci/stub-routes.json`（`usageShapes.last`） |
| **E2E-CTX-2** | 桩**每块重复同一份 usage** | ① 新 session；② 同一 `/chat` + `usage-shape=repeat`；③ 端点回读 | ① `4321`/`77`/`4398`（**不是 ×N**）；② `est=false`、`source=stream`、`dirty=0`；③ `frames≥2`（证明桩真发了多帧 usage ⇒ 去重是**被行使过**的，不是没数据）；④ `done.context.usedTokens=4321`；⑤ 端点同源且 Σ分项 == 4321 | 同上（`repeat` 分支 + `usageShapes.repeat`） |
| **E2E-CTX-3** | 桩**末块 0/0/0**（中间块有真值） | ① 新 session；② `/chat` + `usage-shape=zero-last`；③ 端点回读 | ① `3100`/`50`/`3150`（取中间真值）；② `est=false`、`source=stream`、`dirty=0`；③ `frames` **== E2E-CTX-1 的实测值**（同构对照，见 §3.3；首轮实测双方均 2 —— **不是** 1）；④ 端点同源 | 同上（`zero-last` 分支 + `usageShapes.zero-last`） |
| **E2E-CTX-4** | 桩**完全不发 usage** | ① 新 session；② `/chat` + `usage-shape=none`；③ 端点回读；④ 同 session 第二轮（长提示词）；⑤ `GET /api/ai/health` | ① `est=true`、`source=estimate`、`frames=0`、`completionTokens=0`、`totalTokens==promptTokens>0`；② `done.context.est=true` 且 `usedTokens==usage.promptTokens`；③ 端点 `source=estimate`、`estimated=true`、同值；④ **第二轮 est `usedTokens` > 第一轮**（估算随历史增长 = 压缩触发判定的输入）；⑤ `/health.compactionOn === (contextLimitTokens != null)`（两者同源） | 同上（`none` 分支 + `usageShapes.none`） |
| **三形态 usage 桩自证** | 桩自证四种形状帧形态正确 + 标记解析 | `node scripts/ci/stub-upstream.mjs --self-check` | ① `last`：恰 1 个 usage 帧，`1200/40/1240` + `cached_tokens=640`/`reasoning_tokens=16`；② `repeat`：≥3 个 usage 帧且**逐帧同值**；③ `zero-last`：末个 usage 帧 `0/0/0`、此前恰 1 个真值帧；④ `none`：0 个 usage 帧但 `stop` 与 `[DONE]` 仍在；⑤ 标记解析：无标记/未知值 ⇒ `last`；⑥ 形状表三维自洽（Σ == total、`prompt>0`、`repeat ≠ last`） | `scripts/ci/stub-upstream.mjs`（`selfCheck` 段 ⑧）· `scripts/ci/stub-routes.json` |

### 2.1 「压缩仍按估算触发」的覆盖边界（先登记，免事后解释）

E2E-CTX-4 的这一段**无法在真 E2E 里直接复现**：压缩触发要把会话占比抬到 `trigger-ratio`（默认 70%）以上，而分母 `app.ai.context.limit-tokens` / `ai_model.max_context` 是**后端启动参数/DB 种子**，压小会连带改变其余 31 例的 `context` 帧与端点行为。本批的处理（如实登记，不冒充）：

1. E2E 断言「估算路径被真实走过」（`est=true` 全链）+「估算值随历史增长」（触发判定的输入真实）；
2. 触发本身由后端 `ContextTriggerTest` 锁（单测面，既有）；
3. **不等价宣称**：本批不声称"已端到端验证压缩触发"。

## 3. 红测留痕

### 3.1 首次红（改造前的桩 + 新断言，实测输出）

做法：先给桩加「形状 API + 自检 ⑧」——`textRound(text, model, shapeName)` 收下参数但**忽略**、`usageShapeOf()` 恒返回 `last`（= 改造前行为），断言代码与终态**一字不差**。实测：

```
$ node scripts/ci/stub-upstream.mjs --self-check
  [PASS] 零运行时依赖 …  [PASS] 路由表结构自洽（6 条路由）…  [PASS] 生成式表单白名单与产品事实源逐值对账通过 …
  [PASS] 收敛自证通过（长度递增形态）…  [PASS] 等长重发自证通过 …  [PASS] 披露清单驱动通过 …
  [PASS] SSE 帧形态通过（工具轮 tool_calls 终帧 / 文本轮 stop + usage 终帧）
  [PASS] 工具结果反解通过（对象/双层字符串/前缀混合三形态）
------------------------------------------------------------
SELF-CHECK FAIL：7 项
  - 形状 repeat 的 usage 帧 1 个，期望 ≥3（"每块重复"必须真的重复，否则 E2E-CTX-2 是空转）
  - 形状 repeat 出现非同一份 usage（1200/40/1240），期望逐帧 4321/77/4398
  - 形状 zero-last 的 usage 帧 1 个，期望 2（1 个中间真值 + 1 个末块 0/0/0）
  - 形状 none 仍发了 1 个 usage 帧（产品侧 est 回退路径因此走不到）
  - usage 形状标记解析失败（repeat）：得到 last，期望 repeat
  - usage 形状标记解析失败（zero-last）：得到 last，期望 zero-last
  - usage 形状标记解析失败（none）：得到 last，期望 none
```

判读：`last` 组在改造前**本来就绿**（基线不变），三条非基线形状与标记解析**全红** —— 正是 E2E-CTX-2/3/4 的失败原因在用例之外（上游替身）的确定性投影。

### 3.2 用例断言层的红/绿（**无服务**的判别力证明）

E2E 脚本自身不启动后端（仓库铁律），故把新用例的断言核心 `Test-CtxCase` 抽成**纯函数**（吃帧数组 + 端点快照，不碰 HTTP/全局计数），用合成件在**零服务**环境里跑红绿对照：

`<SCRATCH>/_ctx-harness/ctx-assert-harness.ps1`（仓外一次性工具，未入库）

| 组 | 场景 | 期望 | 实测 |
|---|---|---|---|
| A1 | 旧桩帧（1 个 1200 帧）vs `last` 期望 | 绿 | ✅ 绿 |
| A2 | 旧桩帧 vs `repeat` 期望 | 红 | ✅ 红 |
| A3 | 旧桩帧 vs `zero-last` 期望 | 红 | ✅ 红 |
| A4 | 旧桩帧 vs `none` 期望 | 红 | ✅ 红 |
| B1 | 新形状帧（4321/77/4398，frames=6）vs `repeat` 期望 | 绿 | ✅ 绿 |
| B2 | 新形状帧（3100/50/3150，frames=**2** = CTX-1 实测值）vs `zero-last` 期望 | 绿 | ✅ 绿 |
| B3 | 新形状帧（est，frames=0）vs `none` 期望 | 绿 | ✅ 绿 |
| C1 | 重复 usage 被**累加**（4398×5） | 红 | ✅ 红 |
| C2 | 末帧 0/0/0 被当真值 | 红 | ✅ 红 |
| C3 | 端点 `usedTokens` 与 `done` 不一致 | 红 | ✅ 红 |
| C4 | 分项 Σ ≠ `usedTokens` | 红 | ✅ 红 |
| C5 | 未压缩却发 `context` 帧 | 红 | ✅ 红 |
| C6 | `done` 帧非唯一 | 红 | ✅ 红 |
| C7 | `est=false` 而端点 `estimated=true` | 红 | ✅ 红 |
| C8 | `zero-last` 的 frames 与 `last` **不同值**（3 vs 2，形状没走对路径/聚合形态变了） | 红 | ✅ 红 |

结论行：`HARNESS PASS: A/B/C 全部符合预期（8 红对照组全部咬住，4 绿组全部通过）`（15/15）。
首次跑该工具时还额外暴露一条自身口径问题：合成 `done.context` 缺 `level` 字段 ⇒ `A1` 误红，修正合成件后转绿（说明断言真的在逐字段核，不是空转）。

### 3.3 实跑首轮红 → 判据更正（2026-10-21 · 主会话实跑 + 指挥官裁决）

| 项 | 内容 |
|---|---|
| 首轮实跑结果 | `PASS=34 FAIL=1`；唯一红 = `E2E-CTX-3` 的 `usage.frames=2，期望恰 1` |
| 实测末帧序列 | **真值×2 + `0/0/0`×2** —— `buffer(2,1)` 聚合把**每个 usage 候选各投递 2 次**，`last` 与 `zero-last` 在这一维度**同构** |
| 本报告原推演（§4.3 旧文） | 曾推演「`zero-last` ⇒ 1 帧」（依据 `UsageCalculator.isEmpty` 会吞掉末帧）——**与实测相反**：末帧 `0/0/0` 没有被吞，而是与真值一样被投递两次。错因：把"合并进前一个响应"当成了"只投递一次"，忽略了同一份候选还会在下一个窗口里被再次投递 |
| 裁决（指挥官 2026-10-21） | **修判据**，不改产品代码、不改桩、不放宽数值断言；核心断言（末块 `0/0/0` 不覆盖真值、`3100/50/3150` 与端点同源守恒）保持不动 |
| 改法（自决：二选一里取工程上更稳的） | **同构对照**：`E2E-CTX-3` 的 `framesExact` 由**运行期取 `E2E-CTX-1` 的实测 `frames`** 填充，不写死数字；基准缺失（CTX-1 没取到 `frames`）即判红，不允许退化成"不判"。写死 `2` 今天也能过，但 Spring AI 升级改投递次数时会变成"魔法常量红"；同构判据在本维度上**恒等于**基线形状的行为，升级后自动跟随 |
| 复跑结果 | `PASS=35 FAIL=0` · `GFSKIP=0` · `Q8 项：PASS=2` · 退出码 0；四条实测 `frames` = **2 / 4 / 2 / 0**（见 §4.1） |

## 4. 验证情况（已跑 / 未跑，含原因与数字）

### 4.1 已跑

| 验证 | 命令 | 结果 |
|---|---|---|
| 桩自检（全量 11 条，含新增段 ⑧） | `node scripts/ci/stub-upstream.mjs --self-check`（cwd = worktree） | **PASS**：路由表契约 / 白名单与 Java 事实源逐值对账 / 收敛自证 / 等长重发自证 / 披露驱动 / 帧形态 / 反解 / **usage 形状** 全绿 |
| 基线帧等价性（改造前 vs 改造后，默认路径） | `node <SCRATCH>/_ctx-harness/baseline-equiv.mjs` | **PASS**：text 长度 0/2/120 三组，各 5/5/9 帧，去掉 `id`/`created` 后**逐帧逐字节相同** |
| 断言层红绿对照（无服务） | `powershell -NoProfile -ExecutionPolicy Bypass -File <SCRATCH>/_ctx-harness/ctx-assert-harness.ps1` | **PASS**（15/15，见 §3.2；判据修正后新增 C8 负对照） |
| 脚本语法 | `[Parser]::ParseFile('scripts/verify-e2e.ps1')` | **PARSE OK**（0 错误） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（命名纪律扫描 1472 路径） |
| 后端与桩的跨端契约用例（唯一读桩源文件的后端用例） | `cd backend && mvn -o -Dtest=GenerativeFormContractTest test` | **8/8 · 0F/0E/0S，BUILD SUCCESS**（40.2s）。该用例以正则读 `stub-upstream.mjs` 的 `const FORBIDDEN_KEYS = [...]` 声明行——本批未动该行，读取不受影响 |
| **E2E 实跑 · 首轮**（主会话执行；服务由其起，本批零启停） | `E2E_STUB_MODE=1 powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <SCRATCH>/_ctx-run/boot.log -ArtifactDir <SCRATCH>/_ctx-run/gfc2 -EnableGf6` | `PASS=34 FAIL=1`：唯一红 = `E2E-CTX-3 usage.frames=2，期望恰 1`（**判据错，非产品错**，见 §3.3） |
| **E2E 实跑 · 判据修正后**（同一命令；日志 `<SCRATCH>/_ctx-run/e2e-run3.log`） | 同上 | **`PASS=35 FAIL=0` · `GFSKIP=0` · `Q8 项：PASS=2` · 退出码 0**。E2E-CTX 四条全 PASS，实测 `usage / frames` = `1200/40/1240 frames=2`（CTX-1）· `4321/77/4398 frames=4`（CTX-2）· `3100/50/3150 frames=2`（CTX-3，与 CTX-1 **同值**）· `3068/0/3068 est=true frames=0`（CTX-4） |
| 「末块 usage 能到计量器」的既有实跑佐证（非本批跑） | 读既有产物 `<SCRATCH>/_b1-e2e-artifacts-r3/*.sse.txt` | 历史桩模式的 `done` 帧为 `"usage":{"promptTokens":1200,"completionTokens":40,"totalTokens":1240}` —— 正是桩的末块 usage 帧值，证明 `usage-only(choices:[])` 帧经 Spring AI 聚合后确实进入产品计量面（E2E-CTX-1 的前提） |

### 4.2 未跑（如实说明）

| 未跑项 | 原因 |
|---|---|
| **E2E 35 用例**（施工期） | 脚本**自身不启动**后端/Redis/桩（仓库铁律 + 本批授权明确"不得起任何常驻服务"）。施工期只交付「用例代码 + 无服务可验证的三层证据（桩自检 ⑧ / 断言层红绿 / 基线逐帧等价）」；**实跑由主会话在有服务的前提上执行**（服务其起，本批零启停），结果见 §4.1 末两行 |
| 真模型 E2E | 同上，且真模型 E2E 按 S5d 裁决 #3 本来只在本地手动跑 |
| 前端 pill 渲染（`≈`、三档色） | 前端零改动；既有 `tests/context-pill.spec.ts`（C-31..C-36）覆盖。本批不跑前端门禁（未触碰前端） |
| 后端全量单测 | 后端**零改动**（本批只动 `scripts/` 与 `docs/`），全量单测不构成本批证据；仅跑了读桩文件的那一例做交叉验证 |

### 4.3 风险点（给主会话/提交官）

1. **`frames` 判据已按实测更正，残留风险只剩"升级漂移"**：`spring-ai-openai` 1.1.8 的流式管线是 `map(chunk→ChatResponse) → buffer(2,1) → map(窗口→前一个响应)`（`OpenAiChatModel.lambda$internalStream$7`，合并条件 `streamOptions.includeUsage`；本项目 `spring.ai.openai.chat.options.stream-usage=true`）。**实测（2026-10-21 首轮 E2E）**：`last` = 2 帧、`zero-last` = 2 帧、`repeat` = 4 帧、`none` = 0 帧 —— 即每个 usage 候选被投递 **2 次**（真值与末帧 `0/0/0` 同构），本报告 §4.3 旧推演（`zero-last`=1 帧）**已被实测推翻**，更正与依据见 §3.3。判据因此改为**同构对照**（CTX-3 的 `frames` == CTX-1 实测值），升级后投递次数若变化会**自动跟随**而不是变红。残留风险 = CTX-3 依赖 CTX-1 先跑（循环顺序已固定，且基准缺失即判红，不会静默跳过）。
2. **E2E-CTX-4 的 est 增长断言**依赖"第二轮历史比第一轮长"：第二轮提示词加了 140 字填充（≈82 tokens），远大于取整误差；若产品侧将来把 `promptEstimate` 改成"只算输入文本"，该断言会红——那是口径变更，应当红。
3. **端点依赖 Redis**：`GET /api/ai/context/{sid}` 在 Redis 不可用时 503。CI 有 `services:redis`；本地手动跑必须先起 Redis，否则本组 4 条会以 503 红（属环境坏，不是产品坏）。
4. **`done.context` 装配依赖**：`contextCompactor == null` 时 `done` 不带 `context` 键（"未装配"与"计量为 0"可区分）。新用例把它当硬前提（缺即红）——这是有意的契约收紧。
5. **桩的请求级标记**：提示词里出现 `usage-shape=<x>` 即改变该**请求**的帧；除本组 4 条外全仓无其他用例带该标记（`grep` 已确认），故 31 条既有用例的帧不受影响（另有 §4.1 的逐帧等价机械证据）。

## 5. 主会话运行手册（精确命令行）

### 5.1 桩自检（最先跑，0 依赖）

```bash
cd <REPO_ROOT>
node scripts/ci/stub-upstream.mjs --self-check
```

### 5.2 本地手动跑 E2E 35 用例（桩模式；三个组件都要自己起，脚本不代管）

```bash
# ① 上游桩（18399）
cd <REPO_ROOT>
mkdir -p <SCRATCH>/_ctx-run/stub
node scripts/ci/stub-upstream.mjs --port 18399 --violate-form-type --dir <SCRATCH>/_ctx-run/stub > <SCRATCH>/_ctx-run/stub.log 2>&1 &
curl -sS -m 5 http://127.0.0.1:18399/probe

# ② Redis（默认 127.0.0.1:6379）必须已就绪；本机 Memurai 直接可用

# ③ 打包后端并起在 18330（工作目录在仓库外，避免写 backend/data/）
cd <REPO_ROOT>/backend
<MAVEN_HOME>/bin/mvn -o -DskipTests package
mkdir -p <SCRATCH>/_ctx-run/be && cd <SCRATCH>/_ctx-run/be
AI_API_KEY=ci-stub-placeholder AI_BASE_URL=http://127.0.0.1:18399 AI_MODEL=ci-stub \
  java -jar <REPO_ROOT>/backend/target/config-mgr.jar \
  --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/ctx_e2e_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 \
  --app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000 \
  > <SCRATCH>/_ctx-run/boot.log 2>&1 &

# ④ 就绪等待（available + redis.available 双 true，再等种子 15 个定义）
curl -s http://127.0.0.1:18330/api/ai/health
curl -s http://127.0.0.1:18330/api/definitions

# ⑤ 跑用例（Windows PowerShell 原生，脚本内 exit N 即 step 退出码）
cd <REPO_ROOT>
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 \
  -BackendLog <SCRATCH>/_ctx-run/boot.log \
  -ArtifactDir <SCRATCH>/_ctx-run/gfc \
  -EnableGf6

# ⑥ 只跑新增的 4 条（脚本无按用例过滤参数；按输出行筛）
#   期望看到 4 行 `[PASS] E2E-CTX-1..4`，且末尾 `PASS=35 FAIL=0  GFSKIP=0`
#   每条成功行都带实测取样：`usage=<p>/<c>/<t> est=<bool> frames=<n>`（CTX-3 的 frames 必须与 CTX-1 同值）
```

**实测（2026-10-21，服务由主会话起、本批零启停）**：同一命令首轮 `PASS=34 FAIL=1`（CTX-3 判据错，见 §3.3），判据修正后 **`PASS=35 FAIL=0` · `GFSKIP=0` · `Q8 项：PASS=2` · 退出码 0**，日志 `<SCRATCH>/_ctx-run/e2e-run3.log`。

### 5.3 CI 口径（无需人肉操作）

`.github/workflows/ci.yml` 的 `e2e` job 已按 35 用例更新 job/step 名称；桩的启动参数**未变**（usage 形状是**请求级**标记，不需要新 CLI flag）——即本批对 CI 脚本面只改了标签与注释，不改启动命令。

### 5.4 断言层回归（无服务，改断言后先跑这个）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File <SCRATCH>/_ctx-harness/ctx-assert-harness.ps1
node <SCRATCH>/_ctx-harness/baseline-equiv.mjs
```

## 6. 文件清单（均在 worktree `<REPO_ROOT>`，未 commit）

| 文件 | 动作 | 内容 |
|---|---|---|
| `scripts/ci/stub-upstream.mjs` | 改 | ① 文件头补「usage 形状」段；② 新增 `USAGE_SHAPE_MARKER`/`USAGE_SHAPE_NAMES`/`USAGE_SHAPE_DEFAULTS`/`usageShapes()`/`usageShapeOf()`/`usageOf()`/`usageChunk()`/`ZERO_USAGE`；③ `chunk()` 加可选 `usage`（不传则键不写 ⇒ 基线逐字节不变）；④ `textRound()` 按形状落 usage；⑤ `handleChat` 传形状 + 非默认形状写进 `decision.json`（归因面）；⑥ `selfCheck` 段 ⑧（形状表结构 + 四形状帧形态 + 标记解析） |
| `scripts/ci/stub-routes.json` | 改 | 新增顶层 `usageShapes`（`last`/`repeat`/`zero-last`/`none` + `note`）；桩与 E2E 脚本的**唯一取数点** |
| `scripts/verify-e2e.ps1` | 改 | ① 新增 E2E-CTX 段：`Get-CtxShapes`（读形状表）+ `Test-CtxCase`（纯函数断言核）+ 4 条用例循环 + CTX-4 的第二轮/`/health` 附加断言；② 头部「结果口径」29 → 35，并补 R-137 两例（此前文档口径 29 漏计，真实基数 31）与 CTX 组；③ `/chat` 次数注记 21 → 26、最坏 ~40 → ~45；④ 摘要行补 `E2E-CTX-1..4`；⑤ R-137 口径注记改指向现基数；⑥ **判据修正（§3.3）**：CTX-3 的 `framesExact` 改为运行期取 CTX-1 实测值（同构对照，基准缺失即判红），成功行补 `usage/frames` 取证字段 |
| `scripts/README.md` | 改 | ① 「29 用例」→「35 用例」（首表 / CI 口径 / §1.4 / §3.2 一处）；② §1.4 加 R-137（2）与 E2E-CTX-1..4（4）两行；③ 新增 §2.3b「usage 形状」表 |
| `.github/workflows/ci.yml` | 改 | job 名与 step 名 29 → 35；两处注释同步（GF6 那句改为「登记时 29，现 35」保留历史口径）；**启动命令零改动** |
| `docs/E2E-桩模式校准配方.md` | 改 | 第 4 条基线口径 29 → 35（附 31 = 29 + R-137 两例的算式与 usage-shape 说明） |
| `docs/README.md` | 改 | 两处「29 用例」→ 35（证据分区·操作配方行 / 口径表 `test-cases.md` 行） |
| `docs/test-cases.md` | 改 | §5.4 AI 表新增 E2E-CTX-1..4 四行（与 CTX-01..08 同表） |
| `docs/evidence/施工报告-E2E增补CTX-DS-Flash.md` | 新 | 本报告 |

仓外（一次性验证工具，**未入库**，供主会话复跑）：

| 文件 | 用途 |
|---|---|
| `<SCRATCH>/_ctx-harness/ctx-assert-harness.ps1` | 抽 `Test-CtxCase` 做无服务红绿对照（§3.2） |
| `<SCRATCH>/_ctx-harness/baseline-equiv.mjs` | 改造前(commit `a5539c2`) vs 改造后默认路径的逐帧等价（§4.1） |

**未改动**：`docs/任务看板.md`、`docs/决策看板.md`、`.gitignore`、后端任何 Java、前端任何文件、`docs/technical-design.md`（全文不引用 E2E/桩，无待改内容）、SSE 帧契约（16 帧原样）。

## 7. 自决清单

| # | 自决 | 理由 / 反向代价 |
|---|---|---|
| 1 | 「三形态」实现为**四形状**（`last` 基线 + 三个非基线） | §5.8 四条 vs §6.3「三种」的矛盾；把基线也参数化才能让 E2E-CTX-1 与 2..4 共用同一套断言核（否则 1 走特例）。基线逐帧等价已机械验证 |
| 2 | 形状选择用**提示词标记**（`usage-shape=<x>`）而非 CLI flag / 路由字段 | CLI flag 需重启桩（一棒内做不到）；路由字段要扩 `decide()` 的 text 路由语义（会动收敛自证 ④）。请求级标记与仓库既有 `--violate-form-type` 的"用例级开关由提示词承担"完全同族 |
| 3 | 形状数值放 `stub-routes.json`，「桩 + E2E 脚本」读同一份 | 消灭"桩改数字、脚本没跟"的漂移窗口（同 §2.5 白名单对账的动机）；代价 = E2E 多一次文件读，读不到即 4 条全红（不静默跳过） |
| 4 | 断言核心抽成**纯函数** `Test-CtxCase` | 让"用例有判别力"这件事能在**零服务**下被证明（§3.2）。若把断言写死在循环里，本批就只能交"未经执行的代码" |
| 5 | E2E-CTX-4 的「压缩按估算触发」改为「est 路径全链 + 估算随历史增长」 | 真触发要改全局分母（会连带改其余 31 例），且分母是启动参数/DB 种子；边界在 §2.1 显式登记，不冒充已端到端验证 |
| 6 | 非默认形状写进 `req-NN.decision.json`（`usageShape` 键） | 失败归因三件套的口径（"桩坏 / 产品坏"可区分）；默认形状不写键 ⇒ 既有落盘件不变 |
| 7 | 不改 CI 的桩启动命令 | 形状是请求级开关，不需要新 flag；少改一处即少一处 CI 风险 |
| 8 | 未把两个仓外 harness 入库 | 它们读 `verify-e2e.ps1`/`git show` 的内部结构，属一次性工具；入 `scripts/` 会变成需要长期维护的门禁件，而入 `docs/evidence/probes/` 会触发 check-docs ⑥ 的显式登记约束 |
| 9 | **E2E-CTX-3 的 `frames` 判据改为「与 CTX-1 实测同值」（同构对照），而非写死 `2`** | 首轮实跑红（`frames=2 ≠ 期望 1`）+ 指挥官裁决「修判据、不改产品/桩、不放宽数值」。两种改法里取同构对照：写死 `2` 只描述"今天这台 Spring AI 的投递次数"，升级即红；同构判据描述的是**形状间关系**（每个 usage 候选投递次数相同），升级后自动跟随。代价 = CTX-3 依赖 CTX-1 先跑，故加了"基准缺失即判红"的显式分支（不静默跳过） |

## 8. 「模型配置 E1」待办（本批不做，单列）

| # | 待办 | 依据 / 依赖 |
|---|---|---|
| E1-1 | **模型配置 E1**（对话面板选择器 / `provider`+`model` 字段 / `AiModelConfig` 重建装配 / `spring.ai.openai.*` 配置面） | 任务书明示：依赖**模型配置批②/③ 合流**；本批为 CTX 面，不动 `AiModelConfig`/`application.yml`/`AiPanel.vue`（三者是与模型配置专项的**硬碰撞点**） |
| E1-2 | 与 E1 同棒的 E2E 增量（选择器端到端 + `/health.providers` 断言 + 模型切换后的 `done.usage.model` 归属） | 需 E1-1 落地后才有可断言的 API 形状；本批的 `E2E-CTX-1..4` 已把 `done.model` 留作**未断言观察项**（桩返回 `ci-stub`） |
| E1-3 | `docs/任务看板.md` 的「29 → 35」（并登记 29 漏计 R-137 两例）与缺口登记①闭合 | **本批禁动看板**：由主会话/提交官在看板补「E2E-CTX-1..4 + 三形态 usage 桩已落（本批）」并改计数 |

## 9. 提交官须知（口径与锚）

- 本批**未 commit**；改动 8 个文件（`scripts/` 3 + `.github/workflows/ci.yml` + `docs/` 4，其中本报告为新文件）。
- 门禁建议顺序：`node scripts/ci/stub-upstream.mjs --self-check` → `node scripts/ci/check-docs.mjs` →（起服务后）`E2E_STUB_MODE=1 ./scripts/verify-e2e.ps1 … -EnableGf6`，期望 `PASS=35 FAIL=0 GFSKIP=0`、`Q8 项：PASS=2`（2026-10-21 实测已达，见 §4.1）。
- 后端零改动 ⇒ 后端单测基数不变（939）；前端零改动 ⇒ 545 不变。
