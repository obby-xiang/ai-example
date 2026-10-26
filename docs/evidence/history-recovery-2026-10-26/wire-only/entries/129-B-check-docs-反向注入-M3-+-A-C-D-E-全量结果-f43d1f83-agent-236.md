> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-236` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：28757 B / 19044 字符 / 268 行；行指纹覆盖率（仓内 + 落盘语料）6.4%
> - 类别：**施工复核**
> - 脱敏：路径 19 / 会话标识 0 / 身份 1 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# B-check-docs 反向注入（M3）+ A/C/D/E 全量结果

**主仓库全程零 git 写操作、零文件改动**（末次复核：`git status --porcelain` = 21 行 = 19 M + 2 ??，HEAD = `e602c3b`，与开工时一致）。只用了 `rev-parse/status/diff/grep/ls-files/show`。
**环境异动**：开工时的 scratch `<LOCAL_WORK>/t4-redteam/m3-scratch` 内我建的测试文件被外部删除（同目录另有 agent 在写 `full.diff`/`untracked/`/`a-scratch`/`probe-uni.mjs`，疑似 A+E 与 M4 兄弟棒）。已切到独占目录 `<LOCAL_WORK>/t4-redteam/m3-checkdocs-scratch/`（177M，含 .git）重做并留证；日志在 `<LOCAL_WORK>/t4-redteam/m3-checkdocs-logs/`，C 的脚本在 `<LOCAL_WORK>/t4-redteam/m4/`。原 `m3-scratch` 保留未删。

---

## 任务 A：清点与残留扫描

### A1 清点 — 核对通过

```
$ git status --porcelain | wc -l ; grep -c '^ M' ; grep -c '^??'
21 / 19 / 2        HEAD e602c3b
```
`git diff HEAD --stat` 汇总 19 files changed, 526 insertions(+), 147 deletions(-)，与任务书批次描述一致。

### A2 逐文件"实际改了什么"（含夹带排查）

| 文件 | 改动摘要 | 越界? |
|---|---|---|
| `.github/workflows/ci.yml` | 新增 `docs` job（阻断）与 `scan-history` job（报告制，`fetch-depth: 0`、artifact、step summary）；e2e 加 `--violate-form-type` + `-EnableGf6`；28→29 用例文案 | 否 |
| `README.md` | 端口一览加"端口唯一事实源"注 + 两行新端口行（18330/18399）；`VITE_API_BASE` 默认 8080→8081 三处；"CI 三 job"→"五 job"表 + 报告制口径段；`backend/config/application.yml`→`backend/src/main/resources/application.yml`；27→29 用例 | 否 |
| `backend/pom.xml` | 删 `caffeine.version` 属性 + Caffeine 依赖块（7 行删） | 否 |
| `backend/src/main/resources/application.yml` | 删 `app.server.{backend-port,frontend-port}` 整块（3 行删） | 否 |
| `backend/.../GenerativeFormContractTest.java` | 新增 `forbiddenFieldKeysMatchAcrossEnds` + `readDeclaration`/`quotedValuesOf` 夹具（T4-5 三端原型键逐键锚定，含反例守卫） | 否 |
| `docs/M2-排期计划.md` | 仅追加：2 行去向表（历史 blob 278 处 PATH 命中→历史改写专项；实时帧 `atMs`→下批专项卡）+ 整节"M2-T4 闭环注记 v1.7"（§a 逐条状态/§b 来源注记/§c 去向/§d 治理留痕） | 否（但见 D2c 矛盾） |
| `docs/README.md` | 证据分区加"探针源码"行；附件约定补句；27→29 用例；命名纪律节加"门禁已落地"+"CI 脱敏扫描分口径"两条 | 否 |
| `docs/adr/DECISION-REGISTER.md` | 表外追加「复核」列填写口径注记 + DC-14 行脚注（声明 DC-14 的 `Kimi K3（裁决）` 是裁决者、不作范例）；表格行一字未改（`numstat` = 11/0） | 否 |
| `docs/evidence/baseline-combined.md` | §1 主表 50 行两列由"待重放/待比对"回填为引用式结论 + 证据指针（缺证 4 行双列标"未对照"）；头部加指针注记（`numstat` = 52/50） | 否 |
| `frontend/.env.example` | `VITE_API_BASE` 8080→8081 + 注释同步 | 否 |
| `frontend/src/components/AiPanel/AiPanel.vue` | `resetPendingWatch` 改调 `foldDeadline(...)`（替换原内联 expiresAt/timeoutSeconds 分支） | 否 |
| `frontend/src/stores/ai.ts` | 新增 `export function foldDeadline`（:69-98，含口径注释）；store 内两处落点（activeForm deadline :969、pendingCall.atMs 透传 :1412）改用该函数 | 否 |
| `frontend/src/types/ai.ts` | `PendingToolCall.atMs?` 新增；`expiresAt`/`ActiveGenerativeForm.deadline` 注释改述 | 否 |
| `frontend/src/types/sse.ts` | `SseFrameBase.atMs?` 新增（注释明确"实时帧不带"）；`SseConfirmRequestFrame.expiresAt` 注释改述 | 否 |
| `frontend/vite.config.ts` | `DEFAULT_API_BASE` 8080→8081；G5 注释按裁决改述（禁的是"不可覆盖的硬编码"） | 否 |
| `scripts/README.md` | 28→29 用例；`-EnableGf6` 口径改（桩模式计入主计数 + 桩侧底座）；GF6 进主计数表行；桩模式 SKIP 不可达；§2.5 改为"事实源逐值对账（T4-5 落地）" | 否 |
| `scripts/ci/stub-upstream.mjs` | `--violate-form-type` 改**请求级**（`VIOLATION_REQUEST_MARKER = /故意写成\s*html/`）；新增 `checkConstantsAgainstSource`（读 `GenerativeFormRules.java` 逐值对账 8 组常量+5 上限+3 键）；自检③拆为"对事实源"+"③-b 模板校验" | 否 |
| `scripts/githooks/README.md` | 说明 scan-history 已接 CI + 报告制口径 | 否 |
| `scripts/verify-e2e.ps1` | GF6 块改 `Ok/No/Gf-Skip` 计数 + `Gf-Dump` 留证 + 桩模式未产出判 FAIL；28→29 文案；尾行计数说明 | 否 |
| `?? docs/evidence/四层一致率总账.md` | 新建 159 行：双分母口径（92.0% / 100%）、四层归属、S4.2 例外、未对照 4 行、有意差异 6 条、取数命令 | 否 |
| `?? scripts/ci/check-docs.mjs` | 新建 369 行：6 条断言，零第三方依赖 | 否 |

**夹带排查结论：0 处越界 hunk。** 所有 hunk 均可归入 T4 批次（端口收敛/死键死依赖/CI 五 job/check-docs 门禁/scan-history 报告制/GF6 入桩/-EnableGf6/foldDeadline 折算/跨端锚定/相关文档）。

### A3 残留扫描与分类

**a) `8080`（`git grep -n` 全覆盖 + 2 个未跟踪文件）：**

| 命中 | 分类 |
|---|---|
| `docs/evidence/DOC-文档治理批-DS-V4-Flash.md:52,90,103`、`GFd-双FormRenderer冒烟验证.md:51`、`S44a-前端基座验证.md:20,79,186,224`（"代理默认 8080"） | 历史证据 |
| `docs/evidence/{G1,G2,GFc,M1-E2E复跑确认,M2-T1.2×2,S42-P3,S43a,S43b,S44-fix,S5b,S5f,V3,V4,V8}` 的各行 + `baseline-combined.md:17,237` + `docs/spike/SP-01ab-决策卡.md:238` | 历史证据 —— 注意其中数字是**禁用段 18080**（不等于默认端口 8080；门禁正则 `/:8080\b/` 也不会误伤） |
| `docs/M2-排期计划.md:62,192`（"`DEFAULT_API_BASE` 8080→8081"） | 事实源口径（刻意说明） |
| `scripts/ci/check-docs.mjs:17,254,258` | 口径内（:258 即扫描器检测正则 `/:8080\b/`） |
| `spike/sp01d/evidence/sp01d-vd3-coop-cancel.json:18`、`docs/spike/SP-01d-附件-D-Vd3取消实测记录.txt:18` | 误报（`"cancelSignalTsMs":1791289288080` 尾部数字） |
| `docs/verification-results.md:36`（"8080 被占用，按要求未使用"） | 源分支遗留参考件（非导航文档、非本批改动） |
| **`frontend/src/env.d.ts:7`** | **漏改（唯一一处）** |

> ⚠ **漏改**：`frontend/src/env.d.ts:7` 活代码 JSDoc 仍写 `/** /api 代理目标（默认 http://localhost:8080） */`，而实际默认已是 8081。该文件既不在 19 个改动文件内、也不在 `check-docs.mjs` 的 `NAV_DOCS`（4 个文档）扫范围内 ⇒ **任何现有门禁都抓不到**（`scan-history`/`pre-commit` 只扫 SECRET/PATH/USER 三条规则，不扫端口）。建议补一行同步 + 在门禁里把"端口默认值注释"纳入（或至少把 `frontend/src/**` 的 `8080` 纳入 ④ 的扫描面）。

**b) `app.server|frontend-port|backend-port`：** `README.md:122`（事实源口径：明写"不存在这些死键"）、`docs/M2-排期计划.md:62,192`（事实源口径）、`docs/evidence/DOC-文档治理批…:90,91,149`（历史证据）、`scripts/ci/check-docs.mjs:236,238`（检测正则，口径内）。**0 处漏改**；`application.yml` 内实际已无残留（diff 3 行删，门禁 ④ 亦逐行校验）。

**c) `caffeine`：** 命中全在文档 —— `docs/M2-排期计划.md:62,192`（事实源口径）、`docs/evidence/DOC-文档治理批…:50,90,91,134,136,149,158`、`docs/evidence/S42-P1…:69,98`（历史证据/旧基座实现说明）、`docs/merge/01,03`、`docs/requirements.md:96`、`docs/technical-design.md:55,392`（legacy 参考件）。
`git grep -in caffeine -- backend/` ⇒ **0 命中**；`git grep -n benmanes -- backend/` ⇒ **0 命中**；`pom.xml` 已无 caffeine 依赖与 `caffeine.version`。
*旁证（不构成 dependency:tree 证明）*：本机 `~/.m2/repository/com/github/ben-manes/caffeine/caffeine/3.1.8/` 存在（正是被删版本），且本机 `.m2` 内 spring-ai / spring-boot 的 pom 扫描中**无一**把 caffeine 声明为依赖 ⇒ 无已知传递来源。**未独立验证项**：`mvn dependency:tree` 未跑成（`mvn` 不在本 shell PATH，主仓也禁止我建 target 产物）—— T4 报告称已验，我无法复核。

**d) 后端运行期读 `app.server.*`：** `git grep -nE "@Value.*app\.server|app\.server" -- backend/src/main/java` ⇒ **0 命中**（含注释在内 0 命中）。

---

## 任务 B：check-docs 反向注入（scratch）

**B1 基线**（`<LOCAL_WORK>/t4-redteam/m3-checkdocs-scratch`，与主仓同状态）：
```
node scripts/ci/check-docs.mjs
  [PASS] ①④⑤⑥ … 全部通过：6/6 断言绿。   EXIT=0
        （命名纪律扫描：git ls-files 910 个路径，实扫文本 907 个；豁免 3 个）
```

**B2 Unicode 分隔符绕过**（`docs/evidence/redteam-unicode-bypass.md`，`git add` 后跑门禁）：

| # | 形态 | 码点 | 结果 |
|---|---|---|---|
| 1 | `<OLD_REPO_DIR>` | U+002D | **抓到**（:5） |
| 2 | `<OLD_REPO_DIR⟨_⟩>` | U+005F | **抓到**（:6） |
| 3 | `<OLD_REPO_DIR⟨␠⟩>` | U+0020 | **抓到**（:7） |
| 4 | `<OLD_REPO_DIR⟨.⟩>` | U+002E | **抓到**（:8） |
| 5 | `<OLD_REPO_DIR⟨U+2011⟩>` | U+2011 | **漏** |
| 6 | `<OLD_REPO_DIR⟨U+2010⟩>` | U+2010 | **漏** |
| 7 | `<OLD_REPO_DIR⟨U+2013⟩>` | U+2013 | **漏** |
| 8 | `<OLD_REPO_DIR⟨U+2014⟩>` | U+2014 | **漏** |
| 9 | `<OLD_REPO_DIR⟨U+2212⟩>` | U+2212 | **漏** |
| 10 | `<OLD_REPO_DIR⟨U+200B⟩>` | U+200B ZWSP | **漏** |
| 11 | `<OLD_REPO_DIR⟨U+200D⟩>` | U+200D ZWJ | **漏** |
| 12 | `<OLD_REPO_DIR⟨全角⟩>` | 全角 | **漏** |

关键输出：`[FAIL] ⑤ …（4 处）` / `docs/evidence/redteam-unicode-bypass.md:5..8`，EXIT=1。**结论：4/4 ASCII 对照组抓到，8/8 Unicode 变体全部漏过**（`NAME_RE = /main[-_.\s]+v2/i` 的分隔符类只含 ASCII；JS `\s` 不含 U+200B/ZWJ）。绕过代价 ≈ 0：把分隔符换成 U+2011 即可让禁词在 `git ls-files` 全量扫描下隐形。

**B3 豁免滥用**
- **(a) 正向**：在 scratch 的 `check-docs.mjs` 的 `NAMING_EXEMPT` 加 `'docs/evidence/redteam-evil-exempt.md'`，该文件头 12 行内注"命名纪律豁免…DOC#5"、正文写 `<OLD_REPO_DIR>` ⇒ `[PASS] ⑤` / `6/6 断言绿` EXIT=0（豁免生效）。证明**新增豁免必须改门禁文件本身**（该行在 `git diff` 里可见）—— 但同时也证明：**一行 diff 即可换取整文件无内容级审查的空白豁免**（豁免粒度是整文件，无"仅某行/仅匹配源"限制、无豁免理由校验）。
- **(b) 反向**：临时删掉 `spike/sp01ab/scripts/sp01ab-sanitize.mjs` 头 12 行内的豁免注释 ⇒
  `[FAIL] ⑤ …（1 处）` / `spike/sp01ab/scripts/sp01ab-sanitize.mjs => 豁免文件头部缺「命名纪律豁免 / DOC#5」依据注释（防借豁免之名夹带）` EXIT=1，**定位精确**；恢复后 `git diff` 为空、门禁回 6/6 绿。（仍保留 (a) 的豁免项，故实扫 908/豁免 4。）

**B4 8080 无冒号逃逸**
| 注入行（README.md:122） | 结果 |
|---|---|
| `后端端口 8080（历史示例）`（无冒号） | **漏**（`6/6 断言绿` EXIT=0） |
| `代理默认 http://localhost:8080` | **抓到**：`[FAIL] ④ … README.md:122 => 残留旧默认端口 8080（应为后端 8081 或去掉）` EXIT=1 |

⇒ ④ 只用 `/:8080\b/`，**无冒号的端口提及一律漏报**；且 ④ 的扫描面只有 4 个 `NAV_DOCS`（`docs/evidence/` 已按裁决 7 排除），所以"活文档里写 8080 但不带冒号"= 门禁盲区（`env.d.ts:7` 正是实例）。

**B5 断言 ① 占位豁免滥用**
| 注入行 | 结果 |
|---|---|
| `<DRIVE>/placeholder/secret/path`（含 placeholder） | **漏**（EXIT=0） |
| `<DRIVE>/redteam/probe/path`（对照，无豁免字样） | **抓到**：`README.md:122 => 盘符/本机绝对路径：<DRIVE>/redteam/probe/path` |
| `<DRIVE>/data/<user>/secret` | **抓到**（尖括号先被剔除 → 变成 `<DRIVE>/data//secret`，反而失去 `<` 豁免） |
| `<DRIVE>/redteam/secret`（Git Bash 形态） | **抓到** |
| `\\fileserver\share\secret\keys.pem`（UNC） | **漏**（EXIT=0，6/6 绿） |
| `../../redteam/secret`（相对路径） | **漏**（不在正则面内） |

⇒ `PATH_EXEMPT = /(xxx|placeholder|redacted|[<>])/i` 是**子串匹配整个命中 token**，任何位置出现这三个词即整条放过（`<DRIVE>/redacted-data/nothing` 同样会被放过）；且 UNC 与本机 Git-Bash 之外的形态、相对路径无覆盖。

**B6 额外发现（断言 ④ 行覆盖不全，非任务书要求但同源）**
| 注入 | 结果 |
|---|---|
| H2 Console 行 `:8081`→`:9999` | **漏**（`[PASS] ④`，6/6 绿）—— 该行不在 `expectRows` 里，URL 内嵌端口无对账 |
| `E2E 后端 18330`→`18331` | **抓到**（`README.md:128 => 端口表值 18331 != verify-e2e.ps1 的 -Base 默认值 18330`） |
| 前端 dev `5200`→`9999（可用 VITE_DEV_PORT 覆盖）` | **抓到**（`README.md:127`，`portOf()` 取单元格首个数，仍有效） |

**B 结论表（注入 → 结果）**：Unicode 分隔符（8 形态）**全漏**；整文件豁免**可被一行 diff 开通**（缺头注释仍会红）；无冒号 8080 **漏**；`placeholder/xxx/redacted` 子串**可洗白真实盘符**；UNC/相对路径 **漏**；H2 行端口漂移 **漏**；4 个对照注入均**精确抓到**。

---

## 任务 C：foldDeadline 变异测试与后端权威验证

**C1 边界用例**（`<LOCAL_WORK>/t4-redteam/m4/fold.mjs` 逐字抽取 `frontend/src/stores/ai.ts:85-98`，仅剥 TS 类型；驱动 `drive.mjs`，`nowMs=1000000`）

| 输入 | 返回 | 语义判定 |
|---|---|---|
| E=1060000, A=1000000 | 1060000 | 正常（=now+60s） |
| E=A | 1000000 | = now ✓ |
| A=undefined | 1060000 | 回落 E 绝对值 ✓ fail-safe |
| A=NaN | 1060000 | 回落 E 绝对值 ✓ fail-safe |
| E=NaN, T=30 | 1030000 | 回落 timeoutSeconds ✓ |
| E=NaN, A=1e6, T=30 | 1030000 | ✓ |
| E/A 皆缺 | null | 不武装计时器 ✓ fail-safe |
| 皆 NaN | null | ✓ |
| **A=−5000, E=1000** | **1006000** | **膨胀**：剩余时长由 1s 被放大成 6s（`E−A` 对负 atMs 无守卫） |
| A=2000000（未来）, E=1060000 | 1060000 | 回落绝对钟；客户端钟慢于服务端时倒计时被拉长 |
| E=1e15, A=1e9 | 999999001000000 | 与 BigInt 精确值一致（无精度损失），但 ≈3.2 万年，可读性失效 |
| E=MAX_SAFE_INTEGER, A=1e9 | 9007198255740991 | **> Date 上限 8.64e15 ⇒ `new Date(v)` = Invalid Date**（实测 `Date ok? false`），倒计时显示会出异常值 |
| E=0, A 缺 | 0 | 返回 `0` 而非 `null`；**下游 `AiPanel.vue:396` 用 `=== null` 判定**，故 0 会走"已过期"路径（`max(0, …)`）→ 立即置灰、按超时收尾 ⇒ **fail-closed**，非"不限时" |
| T=0 | null | ✓ fail-safe（利用 0 的假值） |
| T=−30 | 970000 | now−30s ⇒ 本地立刻判超时（cancel） |
| E=Infinity, T=30 | 1030000 | ✓（isFinite 守卫） |
| A=Infinity, E=1060000 | 1060000 | ✓ 回落 |

净判定：**除"A 为负数"（超时被放大）与"极大 E"（Date 溢出）两条外，其余分支均 fail-safe**；`expiresAt=0` 语义安全（下游用严格 `=== null`）。

**C2 前端测试面**：`frontend/package.json` 的 `scripts` 仅 `dev/build/typecheck/preview`（**无 test**），`dependencies+devDependencies` 内**无 vitest/jest**；`find frontend/src -name "*.spec.*" -o -name "*.test.*"` ⇒ **0 个**。
⇒ **`foldDeadline` 这个新纯函数零单测覆盖**，且项目当前无前端测试设施（红队关注点成立）。

**C3 后端权威验证（只读）**
- **(a) 实时帧确实不带 `atMs` —— 证据链闭合**：
  - `SseChatEmitter.java:654` `emit(frame)`，非心跳分支内：`:673` `frame.put("seq", nextSeq())` → **`:681` `payloadBytes = FrameSizeLimiter.apply(frame, objectMapper, maxFrameBytes)`（此处序列化成品字节）** → **`:690` `this.store.appendEvent(this.runId, frame)`**。
  - `FrameSizeLimiter.apply` 的 Javadoc 明写"返回**该帧的最终负载字节**（出帧路径据此免去第二次序列化）"⇒ 序列化发生在 :681。
  - `RunStore.java:576` `frame.putIfAbsent("atMs", System.currentTimeMillis())` 在 `appendEvent` 内 ⇒ **`atMs` 补贴晚于序列化**；订阅者收到的是 :697 由 `payloadBytes` 构造的 `payload`，故实时帧无 `atMs`。旁证：`SseChatEmitter.java:664` 注释自称"归档侧的同类跳过见 RunStore#appendEvent"，`sse.ts` 注释亦写明"实时帧在补 `atMs` 之前就已序列化发出" ⇒ **文档与实现一致**。
- **(b) 门超时权威在后端 —— 成立**：
  - 权威判据：`ConfirmGate.java:204` `long deadline = startedAt + timeoutSeconds * 1000L`（本进程钟）；`:210` 取消分支、**`:222-224` `remain <= 0 → expire(...)`**、`:242` 中断兜底 expire。
  - 到期后果：`ConfirmGate#expire`（:286）把挂起条目在**外置态**置 `TIMEOUT`（`store.putPending`，:295）并发**结局帧** `out.confirmDecision(fresh, "timeout", …, timeoutSeconds*1000L)`（:306，confirm 路径）/ `frontendToolResult(ok=false)`（front-end 工具路径）；终态推进由 `SpToolCallingManager` 在事件边界终止整轮（类注释 :260-262 明示）。
  - **客户端晚提交（已过服务端 expiresAt）会被拒绝**：`ConfirmGate#submitDecision`（:390）先读权威态 `store.pending(...)`，`:395` 若 `status != PENDING` ⇒ `Outcome.DUPLICATE`（**不写、不发帧、不唤醒**）；`AiController.java:215` 映射为 **HTTP 409**，body `code=DUPLICATE_TOOL_CALL_ID`、`status=TIMEOUT`（`:628` 的 `duplicateToolCall`）。⇒ 客户端钟漂移造成的"晚提交"无法翻案，收敛到后端终态。
- **(c) `RunStore.java:451`** = `appendLedger(...)` 内的**工具执行台账**记录（键 `ai:ledger:<runId>`，`RunStore.java:70/160`；`appendLedger` 注释指明"每次真实执行完成一条"）。与帧的 `atMs`（:576）**同源时钟**（都是 `System.currentTimeMillis()`），但**不是同一条记录/不是同一语义**：:451 是"执行完成时刻"（认领时刻 `claimedAtMs` 与之分列），:576 是"帧落档时刻"。二者不冲突，但也不能互相当作 `expiresAt` 的时间基准直接混用。

---

## 任务 D：YAML 与文档口径抽查

**D1 `ci.yml`**：`python -c "import yaml; yaml.safe_load(...)"` ⇒ **YAML OK**，top keys = `['name', True('on'), 'permissions', 'jobs']`。

| job | needs | timeout | checkout |
|---|---|---|---|
| backend | — | 20 | `@v5`（默认 fetch-depth） |
| frontend | — | 20 | `@v5` |
| e2e | **backend** | 25 | `@v5` |
| docs | — | 10 | `@v5` |
| scan-history | — | 10 | `@v5` + **`fetch-depth: 0`** |

触发：`on.push.branches=[main]` + `on.pull_request.branches=[main]` ⇒ 5 个 job 全覆盖。核对结论：`docs` 与 `scan-history` 相互独立、均无 `needs` ✓；e2e needs backend ✓；`fetch-depth: 0` 仅在 scan-history ✓。
**注释口径逐条对实现**：命中(1)不红 / 未完成(2)才红 ⇒ 实现 `if [ "$code" != "0" ] && [ "$code" != "1" ]; then exit 1` ✓；"报告落 `backend/target/`（.gitignore 已排除）" ⇒ `.gitignore:2 backend/target/` ✓；`--files-only` / `scan-lib.sh` 三规则共用 ⇒ `scan-history.sh:33 --files-only`、`scan-lib.sh:65/89 scan_rule_secret/scan_rule_path` ✓；"零第三方依赖，故无需安装依赖" ⇒ 脚本仅 `node:child_process/fs/path/url` ✓。
**唯一口径不符**（已被文档自己标注为"留痕、以新注为准"）：`docs/M2-排期计划.md` §a 条目 18 仍写 scan-history"失败即红"，与 ci.yml/README/`scripts/githooks/README.md` 的报告制**相反**；去向表新行已声明"§a 条目 18 行内的「失败即红」属 T4 施工时点留痕，按只追加纪律不回改，以本条为准"。

**D2a 端口表 vs 事实源（逐值相等）**

| README「端口一览」行 | 事实源 | 值 |
|---|---|---|
| 后端 8081 | `application.yml:2 server.port` | 8081 ✓ |
| 前端 dev server 5200 | `vite.config.ts:11 DEFAULT_DEV_PORT` | 5200 ✓ |
| E2E 后端 18330 | `verify-e2e.ps1:52 [string]$Base='http://127.0.0.1:18330'` | 18330 ✓ |
| 上游桩（E2E/CI）18399 | `stub-upstream.mjs:57 PORT = Number(arg('port','18399'))` | 18399 ✓ |
| H2 Console `:8081/h2-console` | `spring.h2.console.path` | **门禁不对账**（见 B6） |

`frontend/.env.example`：`VITE_API_BASE=http://localhost:8081` == `vite.config.ts:10 DEFAULT_API_BASE` ✓；`VITE_DEV_PORT=5200` == `DEFAULT_DEV_PORT` ✓（`strictPort: true` 亦与 README 描述一致）。

**D2b `docs/evidence/四层一致率总账.md` 数字复核（口径自述 + 独立重算）**

| 抽查项 | 文档声明 | 实算 | 判定 |
|---|---|---|---|
| 分母 | §1 主表数据行 `B1.1~B4.9` = 50 | `grep -c '^| B[1-4]\.[0-9]'` = **50** | ✓ |
| 分类分子 | 显式指名 7 / 引用式 39 / 有意差异 2 / 未对照 4 | **7 / 39 / 2 / 4** | ✓ |
| 一致合计 | 46 → 92.0%；78.0%/14.0%/8.0% | 46/50=92.0%、39/50=78.0%、7/50=14.0%、4/50=8.0% | ✓ |
| 口径 B | 46/(50−4)=46/46=**100.0%** | ✓ | ✓ |
| 层分母 | S4.1=B3.1–B3.7（7）、S4.3=B1(14)+B2(20)=34、小计 41、B4=9 例外 | B1=**14**/B2=**20**/B3=**7**/B4=**9** | ✓ |
| 层一致率 | 6/7=85.7%、31/34=91.2%、37/41=90.2% | 85.71/91.18/90.24 | ✓ |
| 未对照行身份 | §4 列出 B1.10/B1.11/B1.12/B3.3 | 双列标注的正是这 4 行 | ✓ |
| 残余占位 | §6 期望 `待重放`=0、`待比对`=0 | **0 / 0** | ✓ |
| §6 注的"多算 1 处" | 全量 `未对照（无独立证据）`=9（4 对 + 头部注记 1） | **9** | ✓ |

⇒ 总账自述口径完整（分母取法、未对照行处理、S4.2 例外不并入小计的说明均在文内），**未发现算错或口径含糊**；`S4.2 例外（spike 46/46）` 与 `§7 M1 复核 16/16` 的"不同源、不互相替代"声明亦无矛盾。

**D2c `docs/M2-排期计划.md` 新增声明核验**
1. *"实时帧不含 `atMs`（`emit` 的 `payloadBytes` 早于落档）"* ⇒ 与 `sse.ts` 新增注释**逐字同向**，与后端实读一致（C3a）✓。
2. *"GF6 计入主计数（28→29）"* ⇒ `verify-e2e.ps1:73-79 Ok/No` 递增 `$script:passed/$script:failed`，GF6 块已改 `Ok 'GF6 …'` / 桩模式 `No 'GF6 …'`，尾行 `:2331` 文案含 GF1–GF6 ✓；`Gf-Skip` 只对 GF1–GF4 计 `gfskip14`（`:1379`），故 GF6 不误触硬底线 ✓。
3. **❗同批自相矛盾（M6 发现）**：同一 diff 内，去向表新行称 *"T4-3 前半已完成…本批已按卡片落点①②在 `frontend/src/stores/ai.ts` 与 `AiPanel.vue#resetPendingWatch` 落地"*，而紧随其后的"M2-T4 闭环注记 §a"条目 14 仍写 **"BLOCKED →【待裁决】…**本棒未改动任何前端文件**"**、§c 仍写 **"条目 14（T4-3）：去向 = 待裁决"**。新行只声明"取代 §c 的旧去向"，**未消解 §a 的"BLOCKED / 未改动任何前端文件"**。读者先读 §a 会得到与工作树实况（ai.ts/AiPanel.vue 已改、foldDeadline 已落）相反的结论。建议加一句显式取代或标"已被 T4-3 补做棒超越"。
4. *口径差未对齐（低）*：§d 称"scan-history 本地实跑**命中 302 处 / 40 个历史路径**"，而去向表新行与 `ci.yml` 注释称"历史 blob **278 处** PATH 命中（CI 模拟口径，含仅存于历史的 **9 个路径**）"。两数并列但未说明 302→278、40→9 的差异来源（本地全量 vs CI `--files-only` 模拟），易被读成互相矛盾。
5. 未独立复核项：§a 条目 15 的"实跑 `PASS=29 FAIL=0 GFSKIP=0 exit 0`"需起动后端+Redis+桩，本棒未执行（只做了脚本侧计数通路核验）。

**D2d `DECISION-REGISTER.md` / `baseline-combined.md`**
- DECISION-REGISTER：`git diff HEAD --numstat` = **11 增 / 0 删**（与 §a 条目 13 自称一致），表格行零改动；新增仅「复核」列口径注记 + DC-14 脚注。自洽性核验：表内 `^| DC-` 行 **15** 条、含 `| — |` 的行 **14** 条 ⇒ 注记"其余 14 行的 `—`"**成立**；DC-14 复核列 `Kimi K3（裁决）` 与"裁决者≠复核者"的脚注一致。引用的 `docs/evidence/GFb-复核验证-GLM-5.3.md`、`M1-出口复核-GLM-5.3.md` 均存在（`*-红队*` 命中 10 个文件）。
  - 轻微不精确：注记写"红队审查见 `docs/evidence/` 内各 `*-红队*`（DS-V4-Pro 棒）"，实际红队文档也含 `M2-T1.2-红队修复复核-GLM-5.3.md`、`M2-T1.2-红队审查-GLM-5.3.md`、`M2-T2a-红队修复-DS-V4-Flash.md`（非 DS-V4-Pro）。
- baseline-combined：`numstat` = **52 增 / 50 删**（50 行两列回填 + 2 行指针注记），与文档自称"只追加不改历史正文…未重跑重放"一致；新增数字声明（50 行 / 缺证 4 行 / 口径 A 92.0% / 口径 B 100.0%）与总账**互相一致、无冲突**（D2b 已逐项重算）。

---

## 任务 E：改动面终扫

快照：`git diff HEAD`（1242 行，新增行 545 行 → 归入 17 个文件 + 2 个未跟踪新文件全文，去信封行后实扫 **526 行新增 / 19 个文件**）。写入 `<LOCAL_WORK>/t4-redteam/m3-checkdocs-logs/full.diff`（**未用 `<LOCAL_WORK>/t4-redteam/full.diff`**，该文件已由兄弟棒持有，避免互相覆盖）；两个 `??` 全文另存 `m3-checkdocs-logs/untracked-*.{mjs,md}`。扫描脚本 `m4/scan.mjs`、`m4/scan2.mjs`。

| # | 扫描项 | 结果 |
|---|---|---|
| 1a | 命名纪律禁词 ASCII `/main[-_.\s]+v2/i` | **0 命中** |
| 1b | Unicode 变体 `main[\u2010-\u2015\u2212\u200b-\u200f\ufeff]+v2` | **0 命中** |
| 1c | 全角 `ｍａｉｎ.*ｖ２` | **0 命中** |
| 1d | 拼接近似（`ma`+`in`）与转义写法 | **0 命中**（禁词本体只在 `check-docs.mjs:266-268` 以 `'ma'+'in'` / `'v'+'2'` 拼接构造，不在文本里成词；`docs/README.md` 亦只用"旧主仓目录名/同名分支名"指代） |
| 1e | 豁免面复核 | 本批 19 个改动/新增文件中**不含** spike 三脚本（`spike/sp01ab|sp02|sp01d`），故无需借用豁免 |
| 2a | 盘符路径（锚定正则，同 scan-lib） | **0 命中** |
| 2b | Git-Bash 形态 `<DRIVE>/… <DRIVE>/…`（锚定） | **0 命中**（`check-docs.mjs:82` 注释里的 `<DRIVE>/…`、`<DRIVE>/…` 用的是 U+2026 省略号，正则 `{1,60}` 取不到字符，**不会命中**） |
| 2c | UNC 共享路径 | **0 命中** |
| 2d | `Users\` 本机目录线索 | **0 命中** |
| 3a-3h | SECRET 形态：`sk-`/`ghp_`/`gho_`/`ghs_`；`AKIA`/`ASIA`/`AIza`；`xox[baprs]-`；`hf_`/`LTAI`/`AKID`/`glpat-`/`npm_`；`sk_live_`/`rk_live_`；`Bearer` 长串；`-----BEGIN … PRIVATE KEY`；`eyJ…` JWT 三段 | **各项均 0 命中** |
| 4a | 个人用户名 `<USER>`（大小写不敏感） | **0 命中** |
| 4b | 内网 IP（`192.168.*`、`10.*.*.*`；127.0.0.1 不计） | **0 命中**（新增行仅出现 `127.0.0.1` 回环示例） |

**E 结论：新增行在命名纪律/盘符与内网路径/SECRET/个人信息四个维度全部 0 命中**（仅 1 处需注意，且是**既有未改文件**而非本批新增行：`frontend/src/env.d.ts:7` 的 8080 陈旧注释，见 A3a）。

---

## 汇总：需处理项（按严重度）

1. **漏改（中）** `frontend/src/env.d.ts:7` 仍写默认 `http://localhost:8080`；现有任何门禁（`check-docs` 只扫 4 个导航文档；`scan-history`/`pre-commit` 不扫端口）都抓不到。
2. **门禁绕过（中）** 断言 ⑤ 的 Unicode 分隔符绕过 8/8 成立（U+2011/2010/2013/2014/2212/200B/200D/全角），零成本。
3. **门禁豁免面（中）** `NAMING_EXEMPT` 增一行 diff 即得整文件空白豁免（无理由校验、无粒度限制）；`PATH_EXEMPT` 的子串匹配让 `placeholder/xxx/redacted` 成为通用洗白词；UNC 与相对路径无覆盖；断言 ④ 只看 `:8080`（无冒号漏）且不校验 H2 行内嵌端口。
4. **文档自相矛盾（中）** `docs/M2-排期计划.md` 同批内：去向表新行称 T4-3 前半已落地 vs §a 条目 14 "BLOCKED／本棒未改动任何前端文件"、§c "去向=待裁决"（只显式取代 §c）。
5. **数字口径未对齐（低）** 同批 302 处/40 路径（本地）与 278 处/9 路径（CI 模拟）并列无差异说明。
6. **函数健壮性（低）** `foldDeadline` 对负 `atMs` 会放大剩余时长；极大 `expiresAt` 可产出 Invalid Date 值；且**整个前端无测试设施，该新纯函数零单测**。
7. **未独立复核项（须他人/CI 补）**：`mvn dependency:tree` 无 caffeine（本 shell 无 `mvn`，主仓禁建 target）；`verify-e2e.ps1` 的 `PASS=29 exit 0` 实跑（需后端+Redis+桩）；CI 上 `docs`/`scan-history` 两 job 的真实首跑结果。
8. **可回读的正向证据**：`check-docs` 6/6 绿、4 个对照注入均精确抓到并给 `文件:行`；后端"门超时权威在服务端 + 晚提交 409 拒绝"证据链完整；README 端口表四值与事实源逐值相等；总账全部数字可机械重算且自洽。
