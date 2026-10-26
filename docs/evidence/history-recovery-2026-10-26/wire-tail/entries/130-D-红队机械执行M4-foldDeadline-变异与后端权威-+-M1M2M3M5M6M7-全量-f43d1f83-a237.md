> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-237` 的 wire 末条 assistant 文本（消息序号 m=83；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-09 23:39 → 2026-10-09 23:45（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`e602c3b`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：31851 B / 21121 字符 / 310 行；行指纹覆盖率（本批复算）4.1%（最高单文件 3%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/130-D-YAML与文档口径抽查-M5+M6-红队机械审核结果-f43d1f83-agent-238.md`）
> - 类别：**施工复核**
> - 与既有件关系：同会话同题主件 `agent-239`（本目录 #130）的并行复跑稿；行指纹 Jaccard 0.016，发现清单 15 条 vs 主件 11 条，含主件未出现的判据（`atMs` 同名不同义、`stub-upstream.mjs` 注释「27 个用例」、上限 100→999 反向验证）
> - 脱敏：路径 12 / 会话标识 0 / 身份 2 / 密钥 0 / 命名纪律 16
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
All verification complete. Main repo confirmed untouched (19 M + 2 ??, HEAD `e602c3b`).

---

# 红队机械执行结果：C-foldDeadline 变异与后端权威（M4）+ M1/M2/M3/M5/M6/M7 全量

**总判**：批次事实面基本可复现，**零夹带 hunk**，核心门禁与后端权威在实测下成立；但发现 **1 处活代码漏改**、**3 处门禁可绕过面**、**1 处后端竞态窗口（代码读取级）**、**5 处文档/数字口径瑕疵**。全部变异只在私有 scratch 完成，主仓零 git 写操作（终态复核 `19 M + 2 ??`、HEAD `e602c3b` 未变）。

> ⚠ 环境提示：`<LOCAL_WORK>/t4-redteam/m3-scratch` 在我作业期间被**另一并发进程**写入（`redteam-evil-exempt.md`、`probe-uni.mjs` 出现在该目录，且同名测试文件被换成了另一份措辞）。我据此改用私有 scratch `<LOCAL_WORK>/t4-redteam/rt-m4/` 重做全部注入，下述 B 段结果均来自私有副本。**若父代理并行派发了同任务，M3 结论可能被互相污染，建议以本份（rt-m4）为准。**

---

## 任务 A：清点与残留扫描（M1+M2）

### A1 清点
`git status --porcelain` = **19 M + 2 ??**，`git diff HEAD --stat` 19 files changed, 526 insertions(+), 147 deletions(-)。HEAD=`e602c3bc04a846a0ce125c84fb72d7e06d921332`，分支 main。**与任务描述一致。**

### A2 逐文件摘要（21 个）

| # | 文件 | 实际改了什么（1-2 行） | 越界? |
|---|---|---|---|
| 1 | `.github/workflows/ci.yml` | 28→29 用例表述；桩加 `--violate-form-type`、脚本加 `-EnableGf6`；新增 `docs` job（阻断）与 `scan-history` job（报告制、fetch-depth:0、artifact） | 否 |
| 2 | `README.md` | 8081 开箱对齐口径（原「必须覆盖」→「默认已对齐」）；后端配置文件路径订正为 `src/main/resources/`；端口一览加「唯一事实源」注 + 新增 E2E/桩两行；CI 三 job→五 job 并区分门禁属性 | 否 |
| 3 | `backend/pom.xml` | 删 `caffeine.version` 属性 + `com.github.ben-manes.caffeine` 依赖 | 否 |
| 4 | `backend/src/main/resources/application.yml` | 删 `app.server.backend-port/frontend-port` 死键整块 | 否 |
| 5 | `.../ai/form/GenerativeFormContractTest.java` | 新增 `forbiddenFieldKeysMatchAcrossEnds`（读 `../frontend/src/types/form-schema.ts` 与 `../scripts/ci/stub-upstream.mjs` 逐键锚定 + 反例守卫）+ 两个夹具方法 | 否 |
| 6 | `docs/M2-排期计划.md` | 追加 2 条去向行（历史 blob 278 处、实时帧 atMs）+ §「M2-T4 闭环注记 v1.7」（a 逐条状态 / b 来源 / c 去向 / d 治理留痕） | 否 |
| 7 | `docs/README.md` | 证据分区新增「探针源码」行 + 附件约定补句；命名纪律段落追加「门禁已落地」「CI 脱敏扫描分口径」两注 | 否 |
| 8 | `docs/adr/DECISION-REGISTER.md` | 12~34 行后追加「复核列填写口径」（明文非必填）+ DC-14 行脚注（`Kimi K3（裁决）` 非复核者）；表格行 0 删 | 否 |
| 9 | `docs/evidence/baseline-combined.md` | §1 主表 50 行两列引用式回填（`待重放/待比对` 清零，4 行双列标未对照）+ 头部指针注记 2 行 | 否 |
| 10 | `frontend/.env.example` | `VITE_API_BASE` 8080→8081 + 注释同步 | 否 |
| 11 | `frontend/src/components/AiPanel/AiPanel.vue` | `resetPendingWatch` 改调 `foldDeadline`（删原绝对/回落两分支） | 否 |
| 12 | `frontend/src/stores/ai.ts` | 新增导出 `foldDeadline`（:85-98）；前端工具挂起帧（:966）与 pendingToolCall（:1409-1412）两处改用它并透传 `atMs` | 否 |
| 13 | `frontend/src/types/ai.ts` | `PendingToolCall.atMs?` 新增；`expiresAt`/`deadline` 注释改口径 | 否 |
| 14 | `frontend/src/types/sse.ts` | `SseFrameBase.atMs?` 新增 + 实时帧不含 atMs 的成因注释；confirm/frontendTool 帧注释改口径 | 否 |
| 15 | `frontend/vite.config.ts` | `DEFAULT_API_BASE` 8080→8081 + 注释改述（G5 判缺陷的是"不可覆盖"） | 否 |
| 16 | `scripts/README.md` | 28→29、GF6 入参口径、§2.5 改为「事实源逐值对账（已落地）」+ 原型三键锚定句 | 否 |
| 17 | `scripts/ci/stub-upstream.mjs` | `--violate-form-type` 改**请求级**（`/故意写成\s*html/` 标记）；自检③改为读 `GenerativeFormRules.java` 逐值对账（新增 4 个解析器 + `checkConstantsAgainstSource`） | 否 |
| 18 | `scripts/githooks/README.md` | 命中退出码解释改为「CI 侧按报告制解读」；「待实施项」改「已落地 + 报告制口径」 | 否 |
| 19 | `scripts/verify-e2e.ps1` | GF6 由 `Write-Host` 改 `Ok/No/Gf-Skip`（进主计数）+ `Gf-Dump` 留证；桩模式未产出即 FAIL；28→29 全量表述 | 否 |
| 20 | `docs/evidence/四层一致率总账.md`（新） | 双分母（92.0%/100.0%）+ 四层归属 + 未对照清单 + 取数命令 | 否 |
| 21 | `scripts/ci/check-docs.mjs`（新） | 6 条断言门禁，零第三方依赖 | 否 |

**夹带判定：0 处无关 hunk。** 21 个文件全部可归入 T4 批次范围。

### A3 残留扫描

**a) `8080`**

| 命中 | 分类 |
|---|---|
| **`ai-example-code/<REPO>/frontend/src/env.d.ts:7`** `/** /api 代理目标（默认 http://localhost:8080） */` | **⚠ 漏改** —— 活的前端源码注释，未随 8080→8081 收敛；且不在门禁扫描面内（见 M3-4） |
| `docs/M2-排期计划.md:62,192` | 事实源口径（刻意写明「8080→8081」） |
| `scripts/ci/check-docs.mjs:17,254,258` | 门禁自身检测正则/说明（口径内） |
| `docs/evidence/*`（DOC-文档治理批、GFd、S44a 等约 30 处） | 历史证据（裁决 7 明确排除） |
| `docs/verification-results.md:36`、`frontend/src/../` 无、`spike/sp01d/evidence/*.json:18` | legacy 参考件 / 历史证据 |

**b) `app.server|frontend-port|backend-port`**：仅 `README.md:122`（「已整块删除」口径说明）、`docs/M2-排期计划.md:62,192`、`docs/evidence/DOC-*.md`（历史）、`check-docs.mjs:236,238`（检测正则）。**活配置/源码 0 命中。**

**c) `caffeine`**：`pom.xml` **0 命中**（diff 已删）；`git grep -n "benmanes" -- backend/` **exit 1（0 命中）**；`backend/src` 0 命中。其余命中全在 legacy 文档（`docs/requirements.md:96`、`docs/technical-design.md:55,392`、`docs/merge/*`）与历史证据（DOC-文档治理批、S42-P1），属既有历史陈述。

**d) 后端运行期读 `app.server.*`**：`git grep -nE "@Value.*app\.server|app\.server" -- backend/src/main/java` → **exit 1（0 命中）**。✅

---

## 任务 B：check-docs 反向注入（M3，私有 scratch）

**B1 基线**：`node scripts/ci/check-docs.mjs` → 6/6 PASS，`全部通过：6/6 断言绿。` EXIT=0（命名纪律扫描：`git ls-files 910 个路径，实扫文本 907 个；豁免 3 个`）。

**B2 Unicode 分隔符（16 行注入，文件 `docs/evidence/rt-b2-unicode.md`，`git add` 后跑）**

| 注入行 | 形态 | 结果 |
|---|---|---|
| L01 | `<REPO>` ASCII 连字符 | **抓到**（:3） |
| L02 | `<REPO>` | **抓到**（:4） |
| L03 | `<REPO>` | **抓到**（:5） |
| L04 | `<REPO>` | **抓到**（:6） |
| L05 | `<REPO>` U+2011 | **漏掉** |
| L06 | `<REPO>` U+2010 | **漏掉** |
| L07 | `<REPO>` U+2013 | **漏掉** |
| L08 | `<REPO>` U+2014 | **漏掉** |
| L09 | `<REPO>` U+2212 | **漏掉** |
| L10 | `<REPO>` U+200B | **漏掉** |
| L11 | `<REPO>` U+200D | **漏掉** |
| L12 | `<OLD_REPO_DIR⟨全角⟩>` 全角 | **漏掉** |
| L13 | `main\ufeffv2` | **抓到**（:15，JS `\s` 含 U+FEFF） |
| L14 | `main\u00a0v2` NBSP | **抓到**（:16） |
| L15 | `main\u3000v2` 表意空格 | **抓到**（:17） |
| L16 | `main-\u200bv2` 混合 | **漏掉** |

**合计：7 抓到 / 9 漏掉。** 断言 ⑤ 报 `[FAIL] （7 处）`，EXIT=1。根因：`check-docs.mjs:268` 的 `NAME_RE = new RegExp(prefix + '[-_.\\s]+' + suffix, 'i')` 只覆盖 ASCII 分隔符 + JS `\s` 类；`\s` 不含 U+2010–U+2015/U+2212/U+200B/U+200D，也不含全角形。**因断言 ⑤ 是唯一命名纪律门禁且按 `git ls-files` 全量，该绕过是实际可利用面。**

**B3(a) 豁免滥用**：在 scratch 的 `NAMING_EXEMPT` 加 `'docs/evidence/redteam-evil-exempt.md'` + 造该文件（头 3 行含「命名纪律豁免」「DOC#5」，正文写 `<REPO>`）→ 断言 ⑤ **PASS，EXIT=0**，该文件 0 报错 → **豁免生效**，且**必须改门禁文件本体（diff 可见）**，控制点存在。
- 附加观察：脚本的 `NAMING_EXEMPT`(:38-42) 与 `docs/README.md:66`「唯一豁免 = 3 个 spike 脚本」**无交叉校验** → 静默扩面不会被门禁发现；豁免判据只是「头 12 行出现两个字符串」(`:276-279`)，可用无关句子满足。

**B3(b) 反向**：临时删掉 `spike/sp01ab/scripts/sp01ab-sanitize.mjs` 第 3 行豁免注释 → `[FAIL] ⑤（1 处）`，明细 `spike/sp01ab/scripts/sp01ab-sanitize.mjs => 豁免文件头部缺「命名纪律豁免 / DOC#5」依据注释（防借豁免之名夹带）`；恢复后 6/6 绿。✅

**B4 `8080` 无冒号逃逸**（README 端口一览节内插行）

| 注入形态 | 结果 |
|---|---|
| `后端端口 8080（历史示例，不带冒号形态）` | **漏报** —— 断言 ④ `[PASS]`，EXIT=0（检测式 `/:8080\b/`，`:255-259`） |
| `后端端口 http://localhost:8080（带冒号形态）` | **抓到** —— `README.md:122 => 残留旧默认端口 8080（应为后端 8081 或去掉）`，EXIT=1 |

**B5 断言 ① 占位豁免滥用**

| 注入形态 | 结果 |
|---|---|
| `<DRIVE>/placeholder/secret/path` | **被放过**（`PATH_EXEMPT = /(xxx\|placeholder\|redacted\|[<>])/i`，`:88`，`:107` 对**整 token** 做子串判定） |
| `<DRIVE>/proj/secret/path`（对照组） | 抓到 `README.md:100 => 盘符/本机绝对路径：：<DRIVE>/proj/secret/path` |
| `<DRIVE>/proj/actualxxx-backup/secret/path` | **被放过** —— 只要路径任一段含 `xxx` 子串即逃逸（比占位符本意宽松） |

测后 README 全量还原，复查 6/6 PASS。

---

## 任务 C：foldDeadline 变异与后端权威（M4）

### C1 边界用例（`t4-redteam/rt-m4/m4/fold.mjs` 逐字抽取自 `frontend/src/stores/ai.ts:85-98`，仅剥 TS 标注；`drive.mjs` 固定 nowMs=1000000）

**19/19 与期望一致（EXIT=0）**，关键行：

| 输入 (E,A,T) | 返回 | 语义判定 |
|---|---|---|
| (1060000, 1000000, –) | 1060000 | 正常：now+剩余时长 ✅ |
| (1000000, 1000000, –) | 1000000 | E=A → now ✅ |
| (1060000, undefined/null/NaN/Infinity, –) | 1060000 | 回落绝对 expiresAt ✅ fail-safe |
| (NaN, –, 60) | 1060000 | 走 timeoutSeconds ✅ |
| (NaN, NaN, undefined) | **null** | 三者皆无 → 不武装计时器 ✅ |
| **(1000, -5000, –)** | **1006000** | ⚠ **负 atMs 使剩余时长膨胀为 6000ms**（`E-A > 真实剩余`） |
| (1060000, 2000000, –) | 1060000 | atMs 在未来 → 回落绝对 ✅ |
| (-5000, -10000, –) | **1005000** | ⚠ **双负（已过期帧）被折算成"未来 5s"** → 本地不会立刻判过期（fail-open） |
| (1e15, 1e9, –) | 999999001000000 | 在 Date 域内（`new Date()` = 33658-09-15），但 `remainingSeconds`（`AiPanel.vue:395-400`）→ 约 1e12 秒，显示荒谬 |
| (MAX_SAFE_INTEGER, 1e9, –) | 9007198255740991 | ⚠ >8.64e15 → `new Date()` = **Invalid Date**；差值仍是有限数，不抛错 |
| (0, –, –) / (0, –, 120) | **0** | ⚠ 1970 → 立刻过期（fail-closed，正确） |
| (–, –, **0**) | **null** | ⚠ **falsy → 不武装本地计时器**（fail-open；改动前既有行为，非本批引入） |
| (–, –, -30) | 970000 | now-30s → 立刻过期 ✅ |
| (Infinity, –, 60) | 1060000 | isFinite 拦截 → 走 timeoutSeconds ✅ |

### C2 前端测试面
- `frontend/package.json` scripts 仅 `dev/build/typecheck/preview`，**无 `test` 脚本**；devDeps/deps 中 **无 vitest/jest/cypress/playwright/mocha**（过滤器结果为空数组）。
- `git ls-files "frontend/**/*.spec.*" "frontend/**/*.test.*" "frontend/**/__tests__/**"` → **0 命中**。
- 结论：**`foldDeadline` 零单测覆盖**（红队关注点成立）。补充实测：`node node_modules/vue-tsc/bin/vue-tsc.js --noEmit`（scratch + node_modules junction）→ **EXIT=0**，即新代码类型安全，但只有类型面被覆盖。
- 唯一"测试"是间接的：`GenerativeFormContractTest` 只覆盖后端/跨端常量，不覆盖本函数。

### C3 后端权威（只读证据链）

**a) 实时帧不带 `atMs` —— 调用顺序证据链（成立）**

```
SseChatEmitter.emit (:654)
  :684  payloadBytes = FrameSizeLimiter.apply(frame, objectMapper, maxFrameBytes)
           └─ FrameSizeLimiter.java:196  return mapper.writeValueAsBytes(frame)   ← 此刻已序列化，帧内无 atMs
  :690  this.store.appendEvent(this.runId, frame)
           └─ RunStore.java:576  frame.putIfAbsent("atMs", System.currentTimeMillis())  ← 之后才补 atMs
              RunStore.java:578  redis rightPush(eventsKey, JSON(frame))                ← 归档副本带上 atMs
  :697  final String payload = new String(payloadBytes, UTF_8)
  :703  enqueueLive(sub, payload)        ← 订阅者收到的是 :684 早已序列化的字节，不含 atMs
```
反向确认「归档/回放帧有 atMs」：`SseChatEmitter.stageReplay(:379)` → `:380 store.events(runId)`（读归档）→ `:391 objectMapper.writeValueAsString(frame)` **重序列化** → 重挂客户端拿到的帧含 atMs。→ `sse.ts:90-95` 的注释与实况**一致**。

**b) 门超时权威在后端（成立；但存在一处竞态，见下）**

| 环节 | 证据 |
|---|---|
| 超时值 | `application.yml:101-103 hitl.timeout: 120s`；`ConfirmGate.confirmTimeoutSeconds():167-170` 取该键，`Math.max(1, …)` |
| 服务端本地 deadline | `ConfirmGate.java:203-204` `startedAt = System.currentTimeMillis()`；`deadline = startedAt + timeoutSeconds*1000`；`:222-225` `remain<=0 → expire()` |
| 帧内 expiresAt | `SseChatEmitter.expiresAtEpochMs():877-878` = `System.currentTimeMillis() + max(0,timeoutSeconds)*1000`，在 `ConfirmGate:198`（**早于 :203**）调用 → 帧 expiresAt ≤ 服务端 deadline（差数毫秒） |
| **晚提交被拒** | `ConfirmGate.submitDecision():395-397` `status != PENDING → Outcome.DUPLICATE`；`AiController.confirm():215-218` → **HTTP 409** + `duplicateToolCall():626-636`（`code=DUPLICATE_TOOL_CALL_ID`、带当前 `status`=TIMEOUT） |
| 客户端不能自助延期 | `ConfirmRequest.java:11` = `(runId, toolCallId, approved, reason)` —— **提交体无任何时间字段**，客户端无法把自己的到期时刻塞给后端 |
| 超时后的结局帧/状态推进 | `expire():286-311`：`status=TIMEOUT`、`reason=CONFIRM_TIMEOUT`、`executed=false`、`putPending` 落库 → `:306 out.confirmDecision(fresh,"timeout",...)`（同形结局帧，`SseChatEmitter.confirmDecision():530-539`）→ `SpToolCallingManager.awaitConfirm():313-319` 非 APPROVED 分支发 `tool_result(ok=false)` 并返回「未执行」文本，**循环继续**（与 `AiProperties.java:93` 注释一致） |

**⚠ 新增发现（代码读取级，未做并发实证）：`expire()` 与 `submitDecision()` 是"读状态 → 整条覆盖写"，未共用同一把锁。**
- `RunStore.putPending():371-380` 是**裸 HSET，无 CAS/WATCH**（last-write-wins）。
- `submitDecision` 在 `submitLock` 内写（`:411-415`），但其**读**在锁外（`:391`）；`expire()` 的**读(:287) 与写(:301) 都在 `submitLock` 之外**（唯一持锁的是等待循环的读 `:215-217`）。
- 理论交错：expire 读 PENDING(:287) → submit 读 PENDING(:391) 通过 :395 检查 → expire 写 TIMEOUT(:301)+发 timeout 帧(:306) → submit 进锁写 APPROVED(:412)+发 approve 帧(:413)+signal(:414) → **HTTP 200 `accepted:true, status=APPROVED`**，但循环已从 `:224 return expire(...)` 拿到 TIMEOUT 对象 → **工具不执行**。表现为「客户端被告知已批准 + 两个互相矛盾的结局帧 + 服务端未执行」。
- 建议：把 `expire()` 的读改写纳入 `submitLock`，或改用条件写（Lua/HGET 后 HSET 的比较写）。**该结论为静态阅读所得，未构造并发复现。**

**c) `RunStore.java:451` 是什么记录**
- `:449 appendLedger(...)`（工具执行**台账**）：`:451 record.put("atMs", System.currentTimeMillis())`，`:459 rightPush(ledgerKey(runId), JSON)` → 消费点 `ledgerCountSince():513-521`（韧性棒"本次尝试是否有真实副作用"）与 `ledgerFor()`。
- 与帧的 `:576` **同源时钟**：二者都是同 JVM 的 `System.currentTimeMillis()`（非 Instant/Redis 时间），可直接比较。
- 但**同名不同义**：`:451` = 工具**执行完成时刻**（台账条目字段）；`:576` = SSE 帧**落档时刻**（帧字段）。另 `ResumeService.java:273` 取「max(快照 updatedAtMs, 末帧 atMs)」用的是后者。**同名键承载两种语义**，属可读性/误用风险点（建议改名如 `executedAtMs`）。

---

## 任务 D：YAML 与文档口径抽查（M5+M6）

### D1（M5）`.github/workflows/ci.yml`
`python yaml.safe_load` → **YAML 合法**（顶层键 `name, on, permissions, jobs`）。

| job | name | needs | 独立? | timeout | 触发覆盖 | checkout |
|---|---|---|---|---|---|---|
| backend | 后端编译与单测 | – | – | 20 | push+PR(main) | 默认 |
| frontend | 前端类型检查与构建 | – | – | 20 | push+PR | 默认 |
| e2e | E2E 全流程（29 用例） | **backend** ✅ | – | 25 | push+PR | 默认（services: redis） |
| docs | 文档一致性门禁（check-docs）｜**阻断** | **None** ✅ | 与 scan-history **互相独立无 needs** ✅ | 10 | push+PR | 默认（默认 fetch-depth:1） |
| scan-history | 全历史脱敏扫描（报告制）｜**报告制不计门禁** | **None** ✅ | 同上 | 10 | push+PR | **fetch-depth: 0** ✅ |

- 触发：workflow 级 `on: push[main] / pull_request[main]`，两新 job 均无 `if`/job 级 on → **两者都被覆盖** ✅。`permissions: contents: read` 全局收窄。
- 计数口径：`29 用例/29 个` 出现于 `ci.yml:4,9,103,180,207`，无 27/28 残留 ✅。
- ci.yml 头注释口径与实况一致：`docs job（断言⑤ git ls-files 全量；断言① 只覆盖 4 个导航/口径入口文档）` 对应 `check-docs.mjs:30-35` 的 `NAV_DOCS`（确实 4 个）✅；`报告落在 backend/target/` 与 `.gitignore:2 backend/target/` 一致 ✅。
- **与注释口径不符处 1 处（已自披露）**：`docs/M2-排期计划.md:192` 条目 18 原文仍写 scan-history「**(b) … 失败即红**」，与同批新增的报告制口径冲突；该文件的 `:177` 新注记已声明「条目 18 行内的『失败即红』属 T4 施工时点留痕，按只追加纪律不回改，以本条为准」→ **已知口径冲突，非隐性错误**。
- 风险提示（非缺陷）：`scan-history` 10 分钟上限 vs 本地实测 4.9 分钟，且 CI 冷机需先 `fetch-depth:0` 全量克隆，余量偏紧。

### D2（M6a）README 端口表 vs 事实源 —— **4/4 全等**

| README 行 | 表值 | 事实源 | 实测 |
|---|---|---|---|
| 后端 | 8081 | `application.yml:1-2 server.port` | 8081 ✅ |
| 前端 dev server | 5200 | `vite.config.ts:11 DEFAULT_DEV_PORT` | 5200 ✅ |
| E2E 后端 | 18330 | `verify-e2e.ps1:52 [string]$Base` | 18330 ✅ |
| 上游桩（E2E/CI） | 18399 | `stub-upstream.mjs:57 PORT = Number(arg('port','18399'))` | 18399 ✅ |
| H2 Console | `/h2-console` | `application.yml:25-28 spring.h2.console.path` | `/h2-console` ✅ |

`frontend/.env.example:4,7` = `VITE_API_BASE=http://localhost:8081` / `VITE_DEV_PORT=5200`，与 `vite.config.ts:10-11` **一致** ✅。
**门禁覆盖缺口（小）**：`check-docs.mjs:219-224 expectRows` 只断言 4 行，**H2 Console 行的端口未被断言**（表实为 5 行）。

### D2（M6b）`docs/evidence/四层一致率总账.md` 复核 —— **全部可机械重算**

92.0% / 100% 的口径（文档自述）：**口径 A** 分母=主表 50 行（未对照行计入分母）→ 46/50=92.0%；**口径 B** 分母=50−4=46（仅计有对照证据行）→ 46/46=100.0%。

实跑 `§6 取数命令`（`baseline-combined.md`）：

```
① 主表数据行数            50   （期望 50 ✅）
② 待重放 / 待比对          0 / 0（期望 0/0 ✅）
③ 显式指名 / 引用式 / 有意差异  7 / 39 / 2（期望 7/39/2 ✅）
④ 双列未对照成对            4   （期望 4 ✅）
```
未对照 4 行经复核确为 **B1.10 / B1.11 / B1.12 / B3.3**（与文档 §4 清单一致 ✅）。按 B 行号独立重算分层：S4.1 7 行 6 一致 = **85.7%**、S4.3 34 行 31 一致 = **91.2%**、B 行小计 41/37 = **90.2%**、S4.2 9 行单列 —— **与 §3 表逐格一致** ✅。引用的 12 个证据文档/测试类**全部存在** ✅。

**2 处数字瑕疵**：
1. `§7` 把 `M1-出口复核-GLM-5.3.md` §2.5 的「对照项 **≈16**，一致 16/16」写成平铺的「16 项」，而该源表自身列值 `1(S4.1) + 6(S4.3) + 8+(S4.4)` = 15 —— **16 无法由源表机械重算**（源文档用 `≈` 与 `8+（视觉页组）` 兜底，总账丢了这个限定）。
2. `§6` 注记称「`grep -o '未对照（无独立证据）' … 会**多算 1 处**」→ 实测该命令输出 **9**（4 行 × 双列 8 + 头部注记 1），相对数据行数多算 **5**，注记少算 4。（其选用的 ④「双列成对」命令本身正确。）

### D2（M6c）`docs/M2-排期计划.md` 新增声明（逐条 + 2 条实证）

新增声明清单：去向行①「历史 blob 278 处 PATH 命中 → 观察项→历史改写专项」；去向行②「实时 SSE 帧 atMs 覆盖 → 下批专项卡」；§a 逐条状态（12 闭环/13 闭环/14 **BLOCKED→待裁决**/15 闭环/16 闭环/17 BLOCKED/18 闭环）；§b 条目 18 f/g 来源；§c 未闭环去向；§d 治理留痕 3 条。

核验 2 条最可验证者：
1. **「实时帧 atMs 刻意不做」与 sse.ts 注释一致？→ 一致，且与代码一致。** `sse.ts:90-95` 明写「仅归档/回放帧带本字段——实时帧在补 atMs 之前就已序列化发出（`SseChatEmitter#emit` 的 `payloadBytes` 早于落档）」；代码实证见 C3a（`emit:684` → `appendEvent:690/576` → `:697` 用旧 payload；回放 `stageReplay:380-391` 重序列化含 atMs）。
2. **「GF6 计入主计数」与 verify-e2e.ps1 实现一致？→ 一致。** `verify-e2e.ps1:73-82` `Ok()`→`$script:passed++`、`No()`→`$script:failed++`；GF6 块成功走 `Ok`、失败走 `No`、真模型未产出走 `Gf-Skip`(:1377-1382，只加 `gfskip`，不进 passed/failed，且 GF6 不在 GF1–GF4 硬底线名单) → **GF6 确进主 PASS/FAIL 与退出码**；CI 侧 `-EnableGf6` 已接线（`ci.yml:213-221`）。
   - 附加实证：桩侧请求级开关不污染 GF1/GF3/GF4 —— `VIOLATION_REQUEST_MARKER = /故意写成\s*html/`；全仓 `故意写成` 仅出现在 `verify-e2e.ps1:1917`(注释) 与 `:1918`(GF6 提示词)，GF1/GF3/GF4 提示词**不含**该字样 ✅；且 `resolveArguments():249-262` 递归**新建对象**（深拷贝）→ 改 `decision.args.form.fields` 不触达共享路由表 ✅。
- 未能独立复现的声明（需活后端+Redis+key）：条目 15「实跑 PASS=29 FAIL=0 GFSKIP=0」、条目 16「后端全量 357→358」、§d「scan-history 本地命中 302 处/40 路径、耗时 4.9 分钟」。**mark 为"未复现"，非"证伪"。**

### D2（M6d）`DECISION-REGISTER.md` + `baseline-combined.md`

- **DECISION-REGISTER**：`git diff HEAD --numstat` = **11 增 / 0 删** ✅（与总账 §a 条目 13 的自述完全一致，"历史表格行一字未改"成立）。新增内容 = 「复核列填写口径」注记（明文非必填 + 取值口径 + 判定对象）+ DC-14 脚注；**正确对齐** `M1-出口复核-GLM-5.3.md` 「四-①」的两选一建议（选「明文规定非必填」），且**明确声明不宣称已补齐**，无夸大。无自相矛盾。
- **baseline-combined**：`52 增 / 50 删` = 50 数据行回填 + 2 行指针注记 ✅。新增数字仅指向总账（「双分母 50 行全量覆盖/可审计口径」），**无与 diff 其他部分冲突的数字**。头部指针注记表述与 §1 双列标注方式一致。

---

## 任务 E：改动面终扫（M7）

对象 = `git diff HEAD` 新增行 526 行（已排除删除行与 diff 信封行）+ 两个未跟踪文件全文 528 行，合计 **1054 行**。快照：`t4-redteam/rt-m4/full.diff`、`untracked-check-docs.mjs`、`untracked-四层一致率总账.md`。

| 扫描项 | 结果 |
|---|---|
| 1 命名纪律禁词（ASCII `/main[-_.\s]+v2/i`） | **0 命中** ✅（与 `check-docs` 断言 ⑤ 对 910 个 `git ls-files` 路径实扫一致；`check-docs.mjs:266-268` 用 `'ma'+'in'` 拼接，字面量不在文本中，属口径内） |
| 1b Unicode 变体（U+2010–2015/2212/200B–200F/FEFF） | **0 命中** ✅ |
| 1c 全角 `<OLD_REPO_DIR⟨全角·省略号⟩>` | **0 命中** ✅ |
| 2 盘符路径 `[A-Za-z]:[\\/]…`（严格边界版） | **1 命中，非泄漏**：`scripts/ci/check-docs.mjs:82` 注释 `（<DRIVE>/… 与 <DRIVE>/… 两种形态）` —— 形态示意（含省略号），非真实路径；且断言①不扫 `scripts/`。真实本机路径 **0** |
| 2b `/c|d|e|f|g/…` | 同上 1 处示意（`<DRIVE>/…`），真实路径 0 |
| 3 SECRET（`sk-`/`ghp_`/`gho_`/`ghs_`/`AKIA`/`ASIA`/`AIza`/`xox[baprs]-`/`hf_`/`LTAI`/`AKID`/`glpat-`/`npm_`/`sk_live_`/`rk_live_`/`Bearer 长串`/`PRIVATE KEY`/`eyJ` JWT） | **0 命中** ✅ |
| 4a 个人用户名（`<USER>`/`/Users/X`/`\Users\X`） | **0 命中** ✅ |
| 4b 内网 IP（192.168.*、10.*） | **0 命中** ✅ |

**独立交叉验证（比自写扫描器更权威）**：在私有 scratch 中用 `GIT_INDEX_FILE` 无关方式仅暂存本批 21 个文件，跑仓库自带 `sh scripts/githooks/pre-commit`（SECRET/PATH/USER 三规则，`scan-lib.sh` 引擎）→ **`✓ [pre-commit] 敏感信息扫描通过（暂存区无命中）`**，即**本批 changed lines 的工作树/暂存区口径 0 命中**，与排期/README 的自述一致 ✅。（同一次实验里该钩子先抓到了我自己的辅助脚本 `scanE.py:54 [USER 个人用户名] <USER>` —— 反向证明钩子规则生效。）

---

## 发现清单（按严重度）

| # | 级别 | 发现 | 位置 / 证据 |
|---|---|---|---|
| F1 | **中** | 活源码注释漏改：默认代理仍写 8080 | `ai-example-code/<REPO>/frontend/src/env.d.ts:7` |
| F2 | **中** | 断言 ④ 的 8080 检测依赖冒号 → `8080`（无冒号）完全逃逸；且只扫 4 个 NAV_DOCS，故 F1 永不被门禁发现 | `scripts/ci/check-docs.mjs:255-259`；实测 `[PASS] ④` EXIT=0 |
| F3 | **中** | 断言 ⑤ 命名纪律对 9 种 Unicode 分隔符变体全盲（U+2010/2011/2013/2014/2212/200B/200D/全角/混合），而它是唯一命名纪律门禁 | `check-docs.mjs:266-268`；B2 实测 7 抓到/9 漏掉 |
| F4 | **中** | 断言 ① `PATH_EXEMPT` 是**子串**豁免：路径任一段含 `xxx`/`placeholder`/`redacted` 即放过 | `check-docs.mjs:88,107`；实测 `<DRIVE>/proj/actualxxx-backup/…` 逃逸 |
| F5 | **中** | `expire()` 与 `submitDecision()` 的读改写未共用一把锁、`putPending` 无 CAS → 晚提交竞态窗口（200 accepted + approve 帧 vs 服务端 TIMEOUT 不执行） | `ConfirmGate.java:287,301`（锁外写）vs `:391`（锁外读）`:411-415`（锁内写）；`RunStore.java:371-380`（裸 HSET）。**未并发实证** |
| F6 | **低-中** | `foldDeadline` 无任何单测（前端无测试基建）；含 3 处 fail-open/异常语义：负 atMs 膨胀剩余时长、`timeoutSeconds=0`→`null` 不武装计时器、双负时间戳折算为未来 | `frontend/src/stores/ai.ts:85-98`；`package.json` 无 test/vitest；C1 表 |
| F7 | 低 | 历史命中数字口径不一：278 处 PATH（CI 模拟）vs 302 处/40 路径（本地实跑），未给 PATH-only vs 全规则的分解说明 | `ci.yml:275`、`docs/M2-排期计划.md:177` vs `:206` |
| F8 | 低 | 总账把源文档「≈16」写成「16 项」，源表列值仅 1+6+8=15，16 不可机械重算 | `docs/evidence/四层一致率总账.md` §7 vs `docs/evidence/M1-出口复核-GLM-5.3.md` §2.5 |
| F9 | 低 | 总账 §6 注记「多算 1 处」实为多算 5 处（`grep -o` 实测 9） | `docs/evidence/四层一致率总账.md` §6 注 |
| F10 | 低 | 排期条目 18 正文（`失败即红`）与同批报告制口径冲突（已在 `:177` 自披露为只追加留痕） | `docs/M2-排期计划.md:192` vs `:177` |
| F11 | 低 | `stub-upstream.mjs` 头部注释仍写「27 个用例」（该文件本批被改但未同步） | `scripts/ci/stub-upstream.mjs:5` |
| F12 | 低 | 断言 ④ 未断言 H2 Console 行的端口（README 表 5 行、门禁只 4 行） | `check-docs.mjs:219-224` vs `README.md` 端口一览 |
| F13 | 提示 | 命名纪律豁免清单与 `docs/README.md:66`「唯一豁免」口径无交叉校验，可静默扩面；豁免判据弱（头 12 行含两字符串即可） | `check-docs.mjs:38-42,276-279`；B3(a) 实测 |
| F14 | 提示 | `atMs` 同名不同义（台账执行完成时刻 vs 帧落档时刻），同源时钟但语义不同 | `RunStore.java:451` vs `:576`；`ResumeService.java:273` |
| F15 | 提示 | `scan-history` 10 分钟上限 vs 本地 4.9 分钟 + CI 冷机全量克隆，余量偏紧 | `ci.yml` scan-history 节 |

## 已验证为"好"的项（可直接采信）

- 清点与范围：19 M + 2 ?? 精确；**21 文件零夹带**。
- 8080 收敛的**配置与代码面**：`app.server.*` 0 残留、backend java 0 命中、Caffeine（pom/benmanes/src）0 残留；端口表 4/4 = 事实源；`.env.example` 对齐。
- 门禁：基线 6/6 exit 0；豁免必须改门禁本体（diff 可见）；豁免文件缺注释即红（反向实测）。
- 后端权威：晚提交 → 409 DUPLICATE（`ConfirmGate:395-397` + `AiController:215-218`）；`ConfirmRequest` 无时间字段 → 客户端不可自助延期；超时 → `status=TIMEOUT` + 同形结局帧 + 循环继续。
- 实时帧无 `atMs` 的**完整调用顺序链**（`emit:684 → 690 → 576`；回放 `stageReplay:380-391` 有 atMs）。
- T4-5 双向反向验证**独立复现**：改事实源上限 100→999 → 桩自检红（`上限 MAX_TITLE_CHARS 与事实源不一致：桩 100 vs 事实源 999`）；改桩副本 100→200 → 自检红；改前端副本（删 `prototype`）→ Java 用例红（`GenerativeFormContractTest.forbiddenFieldKeysMatchAcrossEnds:91`，`Tests run: 8, Failures: 1`）；基线 8 tests 全绿（`mvn -o -Dtest=GenerativeFormContractTest test` BUILD SUCCESS）；`node scripts/ci/stub-upstream.mjs --self-check` 10/10 PASS。
- 前端 `vue-tsc --noEmit` EXIT=0（新增 `foldDeadline` 导出与 import 类型安全）。
- CI YAML 合法、五 job、needs 关系、fetch-depth:0、双触发覆盖全部正确。
- 总账 50/7/39/2/4 与分层 85.7/91.2/90.2 全部机械复算一致；引用证据文档 12/12 存在。
- 本批暂存 diff 通过仓库自带 `pre-commit`（SECRET/PATH/USER）0 命中。

## 未完成/未验证（交父代理决策）

1. **F5 竞态未做并发实证**（需构造 expire/submit 交错或注入延迟），当前为代码读取级判定 —— 建议由动手能力更强的一棒写复现用例后再定性。
2. **条目 15 的 `PASS=29 FAIL=0 GFSKIP=0`、条目 16 的「357→358」、§d 的「302 处/40 路径/4.9 分钟」**未复现（需活后端 + Redis + key / 全历史扫描 5 分钟）；已在本报告标为「未复现」。
3. 任务 B 中 `<LOCAL_WORK>/t4-redteam/m3-scratch` 的并发污染未清理（非我产物）；我的**全部**产物在 `<LOCAL_WORK>/t4-redteam/rt-m4/`（scratch 保留未删，含 `m4/fold.mjs`、`m4/drive.mjs`、`harness/scanE.py`、`full.diff`）。
4. 主仓终态复核：`git status --porcelain` = 19 M + 2 ??，HEAD `e602c3b`，**零 git 写操作、零文件改动**。
