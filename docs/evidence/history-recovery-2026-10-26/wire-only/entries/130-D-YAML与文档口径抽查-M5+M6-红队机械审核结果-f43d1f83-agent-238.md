> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-238` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：31874 B / 21222 字符 / 271 行；行指纹覆盖率（仓内 + 落盘语料）6.0%
> - 类别：**施工复核**
> - 脱敏：路径 16 / 会话标识 0 / 身份 1 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# D-YAML与文档口径抽查（M5+M6）红队机械审核结果

## 0. 前置校正：scratch 竞争 + 主仓零写确认

**主仓完整性（开工↔收工两次核对一致）**：`HEAD=e602c3bc04a846a0ce125c84fb72d7e06d921332`，`git status --porcelain` = 19 M + 2 ??，无变化；全程只用 `status/diff/grep/show/log/rev-parse/stat`（`git diff HEAD > <scratch>` 为纯读重定向，未改主仓）。主仓 `.git/index` mtime = `2026-10-09 22:33`（早于本会话 23:39+），未发生 git 写操作。

**发现的干扰（重要，需要父代理知道）**：`<LOCAL_WORK>/t4-redteam/m3-scratch` 在**我开工前/开工时即被另一进程写入**——该目录下 `scripts/ci/check-docs.mjs` 已含任务 B 步骤 3a 才该出现的豁免条目 `'docs/evidence/redteam-evil-exempt.md'`，且 `git status` 显示 `A scripts/ci/check-docs.mjs`、`A docs/evidence/redteam-evil-exempt.md` 已入暂存区（我只 `git add` 过自己的 unicode 文件）。旁证：同目录另有 `a-scratch/`、`rtE-m3/`、`rt-m4/`、`m3-checkdocs-scratch/` 等多套痕迹。**结论：可能有并行同任务子代理在写同一路径，或该路径残留上一轮红队产物。**

**应对**：任务 B 全部在私有新 scratch `<LOCAL_WORK>/t4-redteam/rtd-m3scratch` 重做（`tar --exclude=frontend/node_modules --exclude=backend/target` 全量拷贝，`git ls-files=910`、豁免 3 个，与主仓一致，确认干净）。下文 B 段数据全部来自私有副本。`m3-scratch` 未删除（保留）。

---

## 任务 A：清点与残留扫描

### A1 数量核对
`git status --porcelain` → 19 `M` + 2 `??` ✓；`git diff HEAD --stat` = 19 files, 526 insertions(+), 147 deletions(-)（`--numstat` 求和校验 = 526 ✓）。

### A2 逐文件摘要（21 个）与夹带判定

| # | 文件 | 实际改了什么 | 夹带？ |
|---|---|---|---|
| 1 | `.github/workflows/ci.yml` (+118/-6) | 头部口径补注（29 用例/GF6/脱敏分口径）；e2e 桩加 `--violate-form-type`、脚本加 `-EnableGf6`、名称与文案 28→29；**新增 `docs` job（:254）与 `scan-history` job（:286）** | 无 |
| 2 | `README.md` (+14/-8) | 快速启动 .env 提示改写；env 表 `VITE_API_BASE` 默认改 8081；端口表加 2 行（E2E 18330/桩 18399）+「端口唯一事实源」注（:122）；E2E 29 用例；CI「三 job」→「五 job」表 + 门禁属性段（:207） | 无 |
| 3 | `backend/pom.xml` (0/-7) | 删 `caffeine.version` 属性与 Caffeine 依赖 | 无 |
| 4 | `application.yml` (0/-3) | 整块删 `app.server.backend-port/frontend-port` | 无 |
| 5 | `GenerativeFormContractTest.java` (+63) | 新用例 `forbiddenFieldKeysMatchAcrossEnds`（跨端原型三键 + 反例守卫）+ 两个私有夹具 | 无 |
| 6 | `docs/M2-排期计划.md` (+31) | 去向表 2 条新行（历史 blob 278 处；实时帧 atMs 转下批）+ 全新 §「M2-T4 闭环注记 v1.7」a~d 四节 | 无 |
| 7 | `docs/README.md` (+5/-1) | 证据分区加「探针源码」行 + probes 附件约定；test-cases 行 27→29；命名纪律落地注 + 脱敏分口径注 | 无 |
| 8 | `docs/adr/DECISION-REGISTER.md` (+11/-0) | 表外追加「复核列填写口径」注记 + DC-14 行脚注（历史行零改动，numstat 11/0 ✓） | 无 |
| 9 | `docs/evidence/baseline-combined.md` (+52/-50) | §1 主表 50 行两列回填（50 删 ↔ 50 增）+ 头部指针注 2 行 | 无 |
| 10 | `frontend/.env.example` (+2/-2) | `VITE_API_BASE` 8080→8081 | 无 |
| 11 | `AiPanel.vue` (+6/-12) | 改 import（:309）+ `resetPendingWatch` 用 `foldDeadline`（:568）替换原内联回落 | 无 |
| 12 | `stores/ai.ts` (+36/-5) | 新增 `foldDeadline`（:85-98）+ 落点①（:971）+ `pendingCall.atMs`（:1412） | 无 |
| 13 | `types/ai.ts` (+8/-2) | `atMs?` 字段 + `deadline` 注释口径更新 | 无 |
| 14 | `types/sse.ts` (+11/-2) | `SseFrameBase.atMs?` + confirm/frontend-tool 帧注释 | 无 |
| 15 | `vite.config.ts` (+4/-3) | `DEFAULT_API_BASE`→8081，:6/:7 注释改述 | 无 |
| 16 | `scripts/README.md` (+16/-8) | 29 用例、`-EnableGf6` 主计数、桩违规模式两口径、白名单事实源对账（§2.5 重写） | 无 |
| 17 | `scripts/ci/stub-upstream.mjs` (+119/-21) | `--violate-form-type` 改请求级（`VIOLATION_REQUEST_MARKER` :138 / `applyRequestViolation` :141）；自检③改读 `GenerativeFormRules.java` 逐值对账 | 无（但有语义变更，见 F1） |
| 18 | `scripts/githooks/README.md` (+2/-2) | scan-history 进 CI 已落地 + 报告制口径 | 无 |
| 19 | `scripts/verify-e2e.ps1` (+28/-15) | GF6 改 `Ok`/`No`/`Gf-Skip` 计数 + `Gf-Dump`；文案 27/28→29 | 无 |
| 20 | `?? scripts/ci/check-docs.mjs` (369 行) | 新门禁：6 条断言 | — |
| 21 | `?? docs/evidence/四层一致率总账.md` (159 行) | 新总账：双分母 + 四层归属 + 未对照清单 + 取数命令 | — |

**越界 hunk：0 处**（逐 hunk 通读 19 份 diff，所有删行都属"旧值被新值替换"：旧 8080 / 旧 28 用例 / 死键 / Caffeine / 旧内联 deadline / 旧 `applyViolation` 全局注入 / 旧「待重放·待比对」单元格）。但发现 **2 处本批该改未改**（见 F3）：`frontend/src/env.d.ts:7`、`scripts/ci/stub-upstream.mjs:5`。

### A3 M2 残留扫描（主仓只读）

**a) `8080`**：`git grep -n "8080"` 共 **37 处**；其中 **8080 作为端口本体 12 处**、仅子串命中 25 处（`18080`/`18301–18321` 禁用段清单、`spike/sp01d` JSON 里的时间戳 `1791289288080`）。

| 分类 | 处数 | 位置（摘） |
|---|---|---|
| 【事实源口径】刻意说明"原 8080 已改" | 2 | `docs/M2-排期计划.md:62`（`DEFAULT_API_BASE 8080→8081` 评估）、`:192`（同批同步说明） |
| 【历史证据】`docs/evidence/` 及历史轮记录 | 9 | `DOC-文档治理批-DS-V4-Flash.md:52,90,103`；`GFd-双FormRenderer冒烟验证.md:51`；`S44a-前端基座验证.md:20,79,186,224`；`docs/verification-results.md:36`（legacy 区，`8080 被占用，按要求未使用`） |
| 【口径内】门禁自身检测正则/注释 | 3 | `scripts/ci/check-docs.mjs:17`、`:254`、**`:258` `/:8080\b/`** ✅ 任务书所指 |
| **【漏改】** 活源码注释 | **1** | **`frontend/src/env.d.ts:7`：`/** /api 代理目标（默认 http://localhost:8080） */`** —— 默认值已改 8081，此注释未同批更新；且该文件不在 `NAV_DOCS`（`check-docs.mjs:30-35`）内，门禁结构上抓不到 |

**b) `app\.server|frontend-port|backend-port`**：全部命中皆**口径内/历史**，**0 处漏改**：
- `README.md:122`【事实源口径】「配置树里**不存在** … 已于 M2-T4 整块删除」；
- `docs/M2-排期计划.md:62,192`【事实源口径】；`docs/evidence/DOC-文档治理批-…md:90,91,149`【历史证据】；
- `scripts/ci/check-docs.mjs:236,238`【口径内】检测正则。

**c) `caffeine`**：命中均属【事实源口径】（`docs/M2-排期计划.md:62,192`）或【历史证据/legacy 参考件】（`DOC-文档治理批…:50,90,91,134,136,149,158`、`S42-P1-核心运行时验证.md:69,98`、`docs/merge/01-分支评价报告.md:68`、`docs/merge/03-技术方案文档.md:81`、`docs/requirements.md:96`、`docs/technical-design.md:55,392`——后两者在 `docs/README.md` 明示的 legacy 参考区）。**无活配置/代码命中**：
- `git grep -n "benmanes" -- backend/` → **exit 1（0 命中）** ✓；
- `backend/pom.xml` 无 caffeine 依赖/属性 ✓（diff 已删）。
- 未能独立验证项：`docs/M2-排期计划.md:192` 的「`dependency:tree` 无 caffeine」需跑 Maven，本审核未跑（未做网络/构建操作）→ 标记为**未验证声明**。

**d) 后端是否仍读 `app.server.*`**：`git grep -nE "@Value.*app\.server|app\.server" -- backend/src/main/java` → **exit 1（0 命中）**；放宽到 `backend/src` 亦 **0 命中** ✓。

---

## 任务 B：check-docs 反向注入（私有 scratch，全部实测）

**基线**：`node scripts/ci/check-docs.mjs` → **6/6 PASS，EXIT=0**（`git ls-files 910 个路径，实扫文本 907 个；豁免 3 个`）。私有副本与 `m3-scratch` 两次独立重跑结果一致。

### B2 Unicode 分隔符绕过（`docs/evidence/redteam-unicode-bypass.md`，git add 后跑门禁）

| 行 | 形态 | 结果 |
|---|---|---|
| 3 | `<OLD_REPO_DIR>`（ASCII 连字符，对照） | **抓到** |
| 4 | `<OLD_REPO_DIR⟨_⟩>` | **抓到** |
| 5 | `<OLD_REPO_DIR⟨␠⟩>` | **抓到** |
| 6 | `<OLD_REPO_DIR⟨.⟩>` | **抓到** |
| 7 | `<OLD_REPO_DIR⟨U+2011⟩>` (U+2011) | **漏** |
| 8 | `<OLD_REPO_DIR⟨U+2010⟩>` (U+2010) | **漏** |
| 9 | `<OLD_REPO_DIR⟨U+2013⟩>` (U+2013 en dash) | **漏** |
| 10 | `<OLD_REPO_DIR⟨U+2014⟩>` (U+2014 em dash) | **漏** |
| 11 | `<OLD_REPO_DIR⟨U+2212⟩>` (U+2212 减号) | **漏** |
| 12 | `<OLD_REPO_DIR⟨U+200B⟩>` (U+200B ZWSP) | **漏** |
| 13 | `<OLD_REPO_DIR⟨U+200D⟩>` (U+200D ZWJ) | **漏** |
| 14 | `<OLD_REPO_DIR⟨全角⟩>`（全角） | **漏** |

关键输出：`[FAIL] ⑤ 命名纪律禁则（git ls-files 全量）（4 处）`，逐行 `docs/evidence/redteam-unicode-bypass.md:3/4/5/6`。**结论：抓 4/12，漏 8/12** —— 门禁 `NAME_RE`（`check-docs.mjs:266-268`）只含 ASCII `[-_.\s]`，无 Unicode 归一化（NFKC / dash 折叠 / 零宽剥离）。

### B3 豁免滥用验证
**(a) 滥用可行但必须改门禁文件本身（diff 可见）**：在 scratch 的 `check-docs.mjs:38-43` 增一行 `'docs/evidence/redteam-evil-exempt.md'`，该文件头 12 行内写含「命名纪律豁免」「DOC#5」的注释、正文写 `<OLD_REPO_DIR>` → 跑门禁：豁免数 3→4，**该文件 0 报红**（仅我那个 unicode 文件仍 4 处红）。
双向证明：撤掉豁免条目后同一文件被点名 → `[⑤] docs/evidence/redteam-evil-exempt.md:4 => 命中命名纪律禁则`（总命中 5 处）。
diff 可见性：`diff <主仓原版> <scratch 版>` →
```
41c41,42
<   'spike/sp01d/scripts/make-attachments.mjs'
---
>   'spike/sp01d/scripts/make-attachments.mjs',
>   'docs/evidence/redteam-evil-exempt.md'
```
**结论：豁免放行成立，但"新增豁免必须改门禁文件本体"成立 → 属可审计面（评审能看到那一行）。**

**(b) 豁免文件缺头部注释确认真红**：删 `spike/sp01ab/scripts/sp01ab-sanitize.mjs` 第 3 行（含「命名纪律豁免」+「DOC#5」）→
`[FAIL] ⑤ …（5 处）` + `spike/sp01ab/scripts/sp01ab-sanitize.mjs => 豁免文件头部缺「命名纪律豁免 / DOC#5」依据注释（防借豁免之名夹带）`；恢复后回落到 4 处。✓ 断言有效。

### B4 8080 无冒号逃逸
| 注入 | 断言④ | 结论 |
|---|---|---|
| `后端端口 8080（历史示例）`（无冒号）+ `端口：8080`（全角冒号） | **PASS（漏报）** | `/:8080\b/`（`check-docs.mjs:258`）只认 ASCII 冒号 |
| `http://localhost:8080` | **FAIL**：`README.md:133 => 残留旧默认端口 8080` | 只有 URL 形态可抓 |

### B5 断言① 占位豁免滥用
| 注入 | 断言① | 结论 |
|---|---|---|
| `证据落在 <DRIVE>/placeholder/secret/path 下` | **PASS（漏报）** | `PATH_EXEMPT = /(xxx\|placeholder\|redacted\|[<>])/i`（`:88`）按**子串**放过 |
| `日志在 <DRIVE>/some/real/deep/path/x.log`（对照） | **FAIL**：`README.md:135 => 盘符/本机绝对路径：<DRIVE>/some/real/deep/path/x.log` | 豁免口外正常 |
| 附加：`<USER_HOME>/secret-xxx-dir/x.log` | **PASS（漏报）** | `xxx` 同为子串豁免词 → 任意真实路径嵌入 `xxx` 即可洗白 |

**B 段汇总**：注入 17 项 → 抓到 8（4 命名 ASCII + 2 豁免双向 + 1 冒号 8080 + 1 真实盘符）、漏 9（8 Unicode 命名 + 无冒号 8080，另 2 项占位豁免滥用漏报）。

---

## 任务 C：foldDeadline 变异测试与后端权威验证

### C1 边界用例（`<LOCAL_WORK>/t4-redteam/m4/fold.mjs` 逐字抽出 `frontend/src/stores/ai.ts:85-98`；`nowMs=1000000`）

| 用例 | 返回 | 语义判定 |
|---|---|---|
| E=1060000,A=1000000 | `1060000` | 正常：now+剩余 60000 ✓ |
| E=A=1000000 | `1000000` | now+0 ✓ |
| atMs 缺省 | `1060000` | 回落绝对值（实时帧路径）✓ |
| atMs=NaN | `1060000` | 回落绝对值 ✓ |
| E=NaN,T=30 | `1030000` | 走 timeoutSeconds ✓ |
| E=NaN,A=NaN,T=30 | `1030000` | ✓ |
| 三者皆缺 / 皆 NaN | `null` | 不武装计时器（fail-closed）✓ |
| **A=-5000,E=1000** | **`1006000`** | ⚠ **膨胀**：帧按服务端钟早已过期，本地却再等 6s |
| A=2000000>E=1060000 | `1060000` | 回落绝对值（fail-closed）✓ |
| E=1e15,A=1e9 | `999999001000000`（≈+31.7 万年，Date 可表示） | ⚠ 超长 deadline |
| E=Number.MAX_SAFE_INTEGER | `9007198255740991` → **`new Date()` = Invalid Date** | ⚠ 返回不可表示值 |
| **E=0, atMs 缺** | **`0`** | 1970 → **立刻过期**（fail-closed） |
| E=0, atMs=0 | `1000000`（=now） | 也立刻过期，但路径不同（走了差值分支） |
| **T=0（无 E）** | **`null`** | ⚠ 与 `E=0 → 立刻过期`**方向相反**：0 被当"无超时" |
| T=-30 | `970000` | now−30000 → 立刻过期（fail-closed）✓ |
| A=0, E=1060000 | `2060000` | ⚠ **最严重膨胀**：剩余被算成 1060000ms；A 为 0/极小值（坏帧）时无上界 |
| E=null,T=30 | `1030000` | ✓ |
| A="1000000"（字符串） | `1060000` | 类型守卫生效，回落绝对值 ✓ |
| E=Infinity,T=30 | `1030000` | ✓ |

**语义判定汇总**：常见分支 fail-closed（缺字段/NaN/未来 atMs → 绝对值；过期 → 立刻）。**风险面在"atMs 存在但异常（0/负数/极小）"**：`now + (E - A)` 无上界校验，可把"已过期"变成"再等 T 毫秒"甚至 31.7 万年；且 `MAX_SAFE_INTEGER` 场景返回值超出 `Date` 可表示范围（若下游用 `new Date(deadline)` 会 `Invalid Date`，用 `setTimeout(delay)` 会按浏览器规范被钳到 ~1ms → 立刻触发）。`timeoutSeconds=0 → null` 与 `expiresAt=0 → 立刻过期` 语义相反，属口径不一致（低危）。

### C2 前端既有测试面 —— **零覆盖**
- `frontend/package.json`：**无 `test` 脚本**、**无 vitest/jest 依赖**（deps 仅 element-plus/vue/pinia/axios/dayjs/jszip/spreadsheet；devDeps 仅 vite/vue-tsc/typescript/tailwind 等）；
- `find frontend -name "*.spec.*" -o -name "*.test.*" -o -name "vitest.config*"`（排除 node_modules）→ **空**；`frontend/` 下无 `tests/`；
- `foldDeadline` 仅出现在 `stores/ai.ts` / `AiPanel.vue` / 两个 types 文件的注释里，无任何测试引用；
- 对照后端有 59 个测试类。**红队关注点成立：T4-3 新增纯函数 + 两个调用点，零单测，且前端无测试基础设施可放。**

### C3 后端权威验证（主仓只读）

**(a) 实时帧不带 atMs —— 调用顺序证据链成立**
- `SseChatEmitter#emit`（`SseChatEmitter.java:654`）在 `emitLock` 内：
  `:684 payloadBytes = FrameSizeLimiter.apply(frame, this.objectMapper, this.maxFrameBytes)`（**序列化 → 已成最终 payload**）→ `:690 this.store.appendEvent(this.runId, frame)`（**此处才补 atMs**）→ `:697 final String payload = new String(payloadBytes, …)`（投递用的是**序列化早于补 atMs** 的那份字节）。→ **实时订阅者收到的帧不含 atMs** ✓ 与 `types/sse.ts` 新注释逐字一致。
- `RunStore#appendEvent`（`RunStore.java:567-576`）：`:576 frame.putIfAbsent("atMs", System.currentTimeMillis())` 后写 Redis 归档 → 回放侧 `stageReplay`（`SseChatEmitter.java:381-396`）从 `store.events()` 重新序列化 → **只有归档/回放帧含 atMs** ✓。
- 补充实测（非任务书要求，但影响口径）：`expiresAt` 在**发射时**算（`SseChatEmitter.java:877 expiresAtEpochMs = now + max(0,T)*1000`，`confirmRequest :525` / `frontendToolRequest :550` 调用），而 `appendEvent` 紧接其后补 `atMs` ⇒ 归档帧里 `expiresAt − atMs ≈ T`（**几乎恒等于整个 timeouts**），即重挂后本地倒计时"重新起算满额 T"，与 `ai.ts:69-98` 注释承认的代价一致。

**(b) 服务端是权威超时判据 —— 成立，但有一个 ≤2s 的窗口**
- 权威路径：`ConfirmGate#await`（`:189`）内 `long deadline = startedAt + timeoutSeconds*1000L`（`:204`，注意 `startedAt` 取在**发帧之后**）；循环 `:222-224 remain<=0 ⇒ return expire(...)`；`expire`（`:286-310`）把 `status=TIMEOUT`、`reason=CONFIRM_TIMEOUT/FRONTEND_TIMEOUT` 写 Redis，并发**结局帧** `confirmDecision(fresh,"timeout",…)`（`:306`）/ `frontendToolResult(...)`（`:309`）。
- 迟到提交被后端拒绝：`submitDecision`（`:399-419`）只做 `pending==null → NOT_FOUND`、`status!=PENDING → DUPLICATE`，**不做时间戳比对**；因 `expire()` 已把状态写成 `TIMEOUT`，故迟到提交返回 `DUPLICATE` → `AiController#confirm :215-218` 回 **409 DUPLICATE_TOOL_CALL_ID**（`AiController.java:626-637` 文案「该 toolCallId 已有决策或结果，重复提交被拒绝」）。
- ⚠ **窗口**：`submitDecision` 无 `expiresAt` 校验，权威判定只在等待循环里、按 `min(remain, HEARTBEAT_SECONDS=2s)` 节拍轮询（`:234`）。若客户端在"服务端 deadline 已过、循环尚未轮询到"的 ≤2s 内提交，`status` 仍是 `PENDING` ⇒ 被接受为 APPROVED，随后 `await` 读到 `fresh.resolved()` 直接返回（而非 `expire`）⇒ **该次操作会越过 expiresAt 实际执行**。属**非 fail-closed 的窄竞态**（低危，建议后续把 `expiresAt` 落到 `PendingToolCall` 并在提交侧做一次时间校验）。
- 挂起超时后的推进：状态写 Redis（跨进程可读）+ 结局帧与人工决策同形 ⇒ 前端不必区分"人拒绝/等超时"；`ResumeService` 对仍 PENDING 的外部项回 `409 PENDING_UNRESOLVED`（`ResumeService.java:290-292`），不死循环。

**(c) `RunStore.java:451` 是什么**：`appendLedger(...)` 里的 `record.put("atMs", System.currentTimeMillis())` —— 是**工具执行台账**（execution ledger）行的"执行完成时刻"，`rightPush` 到 `ledgerKey(runId)`（`:451-460`），被 `ledgerCountSince`（`:509-517`）用于"本次尝试是否有真实副作用"。与帧归档的 `:576` **不是同一份记录**（台账 vs SSE 帧归档），但**同源时钟**：两处都是 `System.currentTimeMillis()` ✓；`expiresAt` 亦同源（`SseChatEmitter.java:877`）⇒ 前端"`expiresAt` 与 `atMs` 同为服务端钟、其差即剩余时长"的注释成立 ✓。

---

## 任务 D：YAML 与文档口径抽查（M5+M6）

### D1 `.github/workflows/ci.yml`
- **合法 YAML**：`python -c "import yaml; yaml.safe_load(open(...))"` → OK（Python 3.14.8；`python3` 在本机不可用）。注：PyYAML 把裸 `on:` 解析为布尔键 `True`（YAML 1.1 行为），非 workflow 缺陷；`d[True] = {push:{branches:[main]}, pull_request:{branches:[main]}}` ✓。
- **五 job**：

| job | name | needs | if | services | checkout fetch-depth |
|---|---|---|---|---|---|
| backend | 后端编译与单测 | — | 无 | — | 默认(1) |
| frontend | 前端类型检查与构建 | — | 无 | — | 默认(1) |
| e2e | E2E 全流程（29 用例 · 桩上游 + redis） | **backend**（`ci.yml:106`） | 无 | `redis` | 默认(1) |
| docs | 文档一致性门禁（check-docs）｜阻断 | **—（:254，无 needs）** | 无 | — | 默认(1) |
| scan-history | 全历史脱敏扫描（报告制 · 不计门禁） | **—（:286，无 needs）** | 无 | — | **0（:295）** ✓ |

- 触发：`push: branches:[main]` + `pull_request: branches:[main]`，两个新 job 均无 job 级 `if` ⇒ **两个触发面都覆盖** ✓。docs 与 scan-history **互相独立、均不 needs** ✓。
- 与注释口径逐条核对：`ci.yml:275` 注释「历史 blob 有 **278 处** PATH 命中」↔ 实测无冲突但与 `docs/M2-排期计划.md:206` 的「**302 处 / 40 个历史路径**」**数字不一致且互不引用**（见 F2）；`:284`「fetch-depth: 0」↔ `:295` ✓；`docs` job 头部注释「断言①只覆盖 4 个导航/口径入口文档」↔ `check-docs.mjs:30-35` NAV_DOCS 恰为 4 个 ✓；e2e 注释「29 用例」↔ `verify-e2e.ps1` 计数 ✓。**未发现其它注释与实现不符。**

### D2 M6 文档真实性

**(a) README「## 端口一览」逐行 vs 事实源 —— 全部相等 ✓**

| 表行（`README.md:126-130`） | 表值 | 事实源（读值） | 一致 |
|---|---|---|---|
| 后端 | 8081 | `backend/src/main/resources/application.yml:2 server.port: 8081` | ✓ |
| 前端 dev server | 5200 | `frontend/vite.config.ts:11 DEFAULT_DEV_PORT = 5200`（`:16 env.VITE_DEV_PORT \|\| …` 可覆盖） | ✓ |
| E2E 后端 | 18330 | `scripts/verify-e2e.ps1:52 $Base = 'http://127.0.0.1:18330'` | ✓ |
| 上游桩（E2E/CI） | 18399 | `scripts/ci/stub-upstream.mjs:57 PORT = Number(arg('port','18399'))` | ✓ |
| H2 Console | `http://localhost:8081/h2-console` | `application.yml:25-28 spring.h2.console.path: /h2-console` + `server.port 8081` | ✓ |

`.env.example`：`VITE_API_BASE=http://localhost:8081` ✓、`VITE_DEV_PORT=5200` ✓，与 `vite.config.ts:10 DEFAULT_API_BASE='http://localhost:8081'`/`DEFAULT_DEV_PORT=5200` 一致 ✓。

**(b) `docs/evidence/四层一致率总账.md` 口径与抽查复核 —— 全部对得上 ✓**
- 口径 A（`:50-60`）：分母=主表数据行 50；分子=显式指名 7 + 引用式 39 = **46 → 92.0%**；未对照 4（8.0%）。
- 口径 B（`:62-69`）：分母=50−4=**46**，分子 46 → **100.0%**；并明示与 `M1-出口复核-GLM-5.3.md:147` 的 16/16 口径**不同源、不互相替代**（`:143-151`）。
- 独立重算（主仓只读，`docs/evidence/baseline-combined.md` 为唯一事实源）：

| 取数命令 | 期望 | 实测 |
|---|---|---|
| `grep -c '^\| B[1-4]\.[0-9]'` | 50 | **50** ✓（B1=14, B2=20, B3=7, B4=9 ✓） |
| `grep -c '待重放'` / `'待比对'` | 0 / 0 | **0 / 0** ✓ |
| `grep -c '一致（显式指名）'` | 7 | **7** ✓ |
| `grep -c '一致（引用式'` | 39 | **39** ✓ |
| `grep -c '未对照（无独立证据） \| 未对照（无独立证据） \|'` | 4 | **4** ✓（B1.10/B1.11/B1.12/B3.3，与 `:93-102` 清单一致） |

- 四层归属算术自洽：S4.1 7 行/6 一致=85.7% ✓；S4.3 34 行/31 一致=91.2% ✓；小计 41/37=90.2% ✓；B4 九行单列不计入（`:81,:83-87`）；41+9=50、37+9=46 ✓。
- 规格漂移登记（`:206` 与 M2 注记 §d）可复核：`M1-出口复核-GLM-5.3.md:190` 与 `M1-出口评审材料.md:47` 均为**区间串**「主表 B1.1–B4.9 共 50 行」（非逐行指名）✓，故总账把 B1.1/B4.9 归"引用式"而非"显式指名"**证据成立**。
- 轻观察：§5「有意差异」列 6 条（`:104-113`），但主表「一致性」列只有 **2 行**标 `一致（有意差异）`（B1.3/B2.10），§5 无 B 行号列 ⇒ 读者无法把另 4 条（发布 upsert/前端分页/文件名后缀/任务中心过滤）映射回行号。非数字矛盾，建议补映射列。

**(c) `git diff HEAD -- docs/M2-排期计划.md` 新增声明核验**（新增 31 行 = 去向表 2 行 + §a~§d）
1. **「实时 SSE 帧不含 atMs，只有归档/回放帧含」（§a 条目 14、去向表第 2 行）** → **与代码一致** ✓：证据链见 C3(a)（`SseChatEmitter.java:684` 序列化早于 `:690 appendEvent` 补 atMs），且与 `frontend/src/types/sse.ts:87-95` 新注释逐字同构。
2. **「GF6 计入主计数（28→29）」** → **与实现一致** ✓：`verify-e2e.ps1:1912` 用 `Ok`（`:73-77` 递增 `$script:passed`），桩模式下未产出走 `No`（`:78-82`，`:1935-1936`）而非 `Gf-Skip`（`:1377-1382` 只计 `gfskip`）；`Gf-Dump 'GF6'` 仅落证不计数（`:1454-1475`）；基数 22(TC)+1(TC23)+5(GF1–5)+1(GF6)=**29** ✓。
3. **「实跑 PASS=29 FAIL=0 GFSKIP=0 exit 0」「关闭态对照 PASS=28」** → **本次未独立复现**（需起后端+Redis+桩全链路；未做）。仅确认与代码逻辑自洽。同批「后端全量 357→**358**」亦未跑 Maven，但 diff 恰新增 **1** 个 `@Test`（`forbiddenFieldKeysMatchAcrossEnds`）✓ 算术自洽。
4. 附带独立验证（超出任务书）：桩自检③事实源对账**真生效**——`node scripts/ci/stub-upstream.mjs --self-check` 全绿（含「与产品事实源逐值对账通过」）；把 `GenerativeFormRules.java:106 MAX_TITLE_CHARS 100→101` 变异后自检即 **SELF-CHECK FAIL：- 上限 MAX_TITLE_CHARS 与事实源不一致：桩 100 vs 事实源 101**，还原即绿 ✓。跨端三键声明齐备（后端 `GenerativeFormRules.FORBIDDEN_FIELD_KEYS`、`frontend/src/types/form-schema.ts:43`、`scripts/ci/stub-upstream.mjs:77`），与 Java 用例的两条正则 `const FORBIDDEN_FIELD_KEYS = new Set\(\[([^\]]*)\]\)` / `const FORBIDDEN_KEYS = \[([^\]]*)\]` 逐字匹配 ✓。

**(d) `DECISION-REGISTER.md` 与 `baseline-combined.md` 摘要与自洽性**
- `DECISION-REGISTER.md`（+11/-0）：表外追加「复核列填写口径」注记（明文**非必填**、`—` 语义=`未设记录`、将来格式示例）+ DC-14 行脚注。核验：DC-14 行「复核」列现值确为 `Kimi K3（裁决）`（`:18`），其余 14 行均为 `—` ⇒ 脚注「其余 14 行」**数目正确** ✓；「历史表格行一字未改」↔ `git diff --numstat` = **11 增 / 0 删** ✓。无自相矛盾。
- `baseline-combined.md`（+52/-50）：头部指针注 + §1 主表两列引用式回填；**无任何百分比/一致率数字声明**（`grep` `92%|100.0|46/50|46/46` → 0 命中）⇒ 数字只在总账与 M2 注记，不存在"两处各写一套"。50 删行 ↔ 50 个表数据行 ✓，无段落被误删。
- 同批「新写行引用旧数字」的轻观察：回填行 B1.1 写「实跑 M1-E2E复跑确认 **27/27**」（`baseline-combined.md:47`）——该证据文档当时确为 27/27，属历史引用，但新行未标「历史口径/现为 29」，易被读成现状。

---

## 任务 E：改动面终扫（M7）

对象 = `git diff HEAD` 的 **526 条新增行**（不含 `+++` 信封行）+ 2 个未跟踪新文件的 **530 行**（`check-docs.mjs` 369 + `四层一致率总账.md` 159）；快照 `<LOCAL_WORK>\t4-redteam\rtd-full.diff`（124 KB）、`rtd-untracked-check-docs.mjs`、`rtd-untracked-四层一致率总账.md`。

| 扫描项 | 结果 |
|---|---|
| 1 命名纪律 ASCII `/ma in[-_.\s]+v2/i` | **0 命中** ✓ |
| 1 命名纪律 Unicode 变体（U+2010–2015/2212/200B–200F/FEFF/全角） | **0 命中** ✓ |
| 2 盘符/本机路径（门禁同构正则 `(^\|[^A-Za-z0-9_])[A-Za-z]:[\\/][-A-Za-z0-9_.\\/]{1,60}` 与 `/[cdefgh]/…`，含豁免扣除） | **0 命中** ✓（唯一含 `<DRIVE>/…` 的是 `scripts/ci/check-docs.mjs:82` 的**说明性省略号文本** `<DRIVE>/… 与 <DRIVE>/… 两种形态`，反斜杠形态亦 0 命中） |
| 3 SECRET 形态（`sk-`/`ghp_`/`gho_`/`ghs_`/`AKIA`/`ASIA`/`AIza`/`xox[baprs]-`/`hf_`/`LTAI`/`AKID`/`glpat-`/`npm_`/`sk_live_`/`rk_live_`/`Bearer <长串>`/`-----BEGIN … PRIVATE KEY`/`eyJ…` 三段 JWT，共 17 类） | **0 命中** ✓ |
| 4 个人用户名（`<USER>` 等）/ `<DRIVE>/Users/<真实名>` | **0 命中** ✓ |
| 4 内网 IP（`192.168.x` / `10.x.x.x` / `172.16-31.x`） | **0 命中** ✓ |

方法学说明：首轮用宽松正则 `[A-Za-z]:[\\/][^\s`]*` 得 13 处，全部是 `http://localhost:8081` 一类的**假阳性**（`<DRIVE>//…` 被当盘符），改用门禁同构正则后归零——**E 段结论以门禁同构正则为准**。`docs/evidence/` 历史文档的旧内容未计入（只看新增行）。

---

## 发现汇总（按建议优先级）

| # | 级别 | 发现 | 证据 |
|---|---|---|---|
| **F1** | **中** | **`--violate-form-type` 语义由"全局"改为"请求级"（`applyViolation` 提前 `return` + 新增 `applyRequestViolation`）带来两处未登记的后果**：① 红队 A7 验证过的防误配不变量「违规模式 + `--self-check` → **FAIL**」**失效**——A/B 实测：HEAD 版 `exit=1`（`SELF-CHECK FAIL：2 项 - 路由 GF-FILTER.form 字段 scope 类型 html 越白名单…`）vs 工作树版 **`exit=0`（SELF-CHECK PASS）**；② M2-T1.2 证据线文档化的"缺陷注入 ⇒ 违规变红"复现路径失效（`M2-T1.2-独立复现-GLM-5.3.md:62-72` 与 `M2-T1.2-红队修复复核-GLM-5.3.md:57-65` 都以"起 `--violate-form-type` 桩 → 重跑同一脚本 ⇒ GF1/GF3/GF4 SKIP、GFSKIP=4、桩模式 exit 1"为判据；新代码在无 `故意写成 html` 标记提示词时不注入 → 该跑法变绿）。新文档只写「GF1/GF3/GF4 不受影响」与「`--violate-form-keys`：全局（S7 缺陷注入口径不变）」，**未披露 form-type 的全局态已消失、亦未登记为能力变更/观察项**。新机制本身功能正确（实测：带标记请求 `scope.type=html`、无标记 `enum`）。 | `stub-upstream.mjs:120-132` vs HEAD 版 `applyViolation`；`:138,:141-151,:542`；A/B 命令与退出码见正文 |
| **F2** | 中 | **同一批次内历史 blob 命中数字自相矛盾**：`ci.yml:275` 与 `docs/M2-排期计划.md:177` 写「**278 处** PATH 命中（含仅存于历史的 **9** 个路径）」，同文件 `:206` 写「本地实跑命中 **302 处 / 40 个历史路径**」，两处不互相引用、口径（PATH-only vs 全规则 / CI 模拟 vs 本地）未标注差异。 | `ci.yml:275`；`docs/M2-排期计划.md:177,206` |
| **F3** | 低-中 | **本批该改未改 2 处**：① `frontend/src/env.d.ts:7` 注释仍写「默认 http://localhost:8080」（同批 `.env.example`/`vite.config.ts`/README 已改 8081，且该文件不在门禁 `NAV_DOCS` 内→结构上抓不到）；② `scripts/ci/stub-upstream.mjs:5` 头注释仍写「让 `verify-e2e.ps1` 的 **27 个用例**…确定性通过」（同文件本批大改却漏此行，现口径 29）。 | `env.d.ts:7`；`stub-upstream.mjs:5`（diff 中该行未出现在任何 hunk） |
| **F4** | 低-中 | **门禁四类绕过面**（全部 scratch 实测）：⑤ 命名纪律漏 8/12 Unicode 变体；① `PATH_EXEMPT` 子串豁免可滥用（`placeholder`/`xxx` 任一子串即整token放过，实测 `<USER_HOME>/secret-xxx-dir/x.log` 不报）；④ `/:8080\b/` 无 ASCII 冒号即漏（`后端端口 8080`、`端口：8080` 均 PASS）；`docs/evidence/` 被①②③④ 排除属**裁决 7 明示口径**（非缺陷），但 ⑤ 全量扫描与①②③④ 的落差应写入判读须知。 | B2/B4/B5 输出原文 |
| F5 | 低 | `foldDeadline` 缺口：`atMs` 存在但异常（0/≤0/极小）时 `now+(E−A)` 无上界 → 已过期变"再等 T"甚至 +31.7 万年；`MAX_SAFE_INTEGER` 返回 `Invalid Date`；`timeoutSeconds=0 → null` 与 `expiresAt=0 → 立刻过期` 语义相反；**零单测且前端无测试基础设施**。 | C1 表；`ai.ts:85-98`；`frontend/package.json` |
| F6 | 低 | 服务端"权威超时"存在 ≤2s 竞态：`submitDecision` 不校验时间戳，等待循环按 2s 节拍轮询；窗口内迟到提交会被接受并可能执行。 | `ConfirmGate.java:399-419`、`:222-234`；`AiController.java:215-218` |
| F7 | 低 | 总账 §5「有意差异」6 条 vs 主表仅 2 行标注，缺 B 行映射列；`baseline-combined.md:47` 新写行引用「27/27」未标历史口径。 | `四层一致率总账.md:104-113`；`baseline-combined.md:47` |

**未验证项（明确声明）**：`PASS=29 FAIL=0 GFSKIP=0 exit 0`、关闭态 `PASS=28`、后端 `357→358`、`mvn dependency:tree 无 caffeine` 均需起全链路/Maven，本次未跑（铁律下未做构建与网络操作），仅做静态自洽校验。

**残留物**：主仓**未改动**（19 M + 2 ??、HEAD e602c3b 全部原样）。scratch 保留：`<LOCAL_WORK>/t4-redteam/rtd-m3scratch`（私有洁净副本，含注入文件 `docs/evidence/redteam-unicode-bypass.md`、`redteam-evil-exempt.md` 与门禁改动）、`rtd-full.diff`、`rtd-untracked-*.{mjs,md}`、`m4/{fold.mjs,drive.mjs}`、`scan-e*.py`、`scan-8080.py`、`rtd-stub-dump/`、`rtd-stub.log`；测试用桩进程（PID 10672，端口 18997）已 `taskkill` 清理。`<LOCAL_WORK>/t4-redteam/m3-scratch` 未删除但**内容被并行进程污染，其 B 段结果不可用于结论**。
