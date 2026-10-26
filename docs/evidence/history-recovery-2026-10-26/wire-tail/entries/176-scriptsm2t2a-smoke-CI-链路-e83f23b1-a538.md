> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-538` 的 wire 末条 assistant 文本（消息序号 m=18；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 03:46 → 2026-10-12 03:48（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：14314 B / 9280 字符 / 83 行；行指纹覆盖率（本批复算）0.0%（无单文件重合）
> - 类别：**过程核验**
> - 与既有件关系：—
> - 脱敏：路径 1 / 会话标识 1 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完毕。主仓库 HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`，`git status --porcelain` 为空；全程零写、零 git 写、未触 `<LOCAL_WORK>\ai-example` 与 `ai-example-main`、未读任何 `.env`。

## 1. scripts/m2t2a-smoke.mjs — PASS（一处行号订正）

- 行数 488 ✔（27,258 B）。
- `tag-form-cancelled` 3 处：`:39` 注释（`// data-testid（gf-submit / gf-cancel / pending-card-<toolCallId> / tag-form-cancelled），`）、`:53` = `const TESTID_TAG_CANCELLED = 'tag-form-cancelled'` ✔、`:166` 注释（`// tag-form-cancelled 计数；store 侧 pendingStatuses 仅供 detail；…`）✔。
- `:176-180` `cancelledTags`: `cancelledTags: document.querySelectorAll('[data-testid="${TESTID_TAG_CANCELLED}"]').length,`（`:177` toolRunCount、`:178-179` 收尾）✔。
- assert 函数实为 `:183-187`（`RESULT.assertions[name]={pass:!!cond,detail}` / 打印 / `if(!cond) failures.push(name)`），`:188` 为空行 ⇒ 草案 `:183-188` 实质成立、末行是空白。
- `grep -n "assert('"` 调用点 = 恰好 5 处，与草案口径逐条一致：`:285 ②`、`:297 ③`、`:299 ③b`、`:346 ①`（阶段 3a 未收到 `frontend_tool_request` 的失败分支）、`:457 ①`（正断言）。
- `:299` 原文 ✔：`assert('③b', c2.formRendererCount === 0 && c2.cancelledTags === 2,`
- 链路概括：②=阶段 1 经 Pinia 注入帧造「同 toolCallId 双消息」，断言 `gf-submit`/`gf-cancel` 各恰好 1（唯一表单实例）；③=阶段 2 点 `gf-cancel` 后轮询，断言两条消息的 `pendingCall.status` 全部收敛 `cancelled`；③b=同轮取消后 DOM 上 `gf-submit`=0 且 `tag-form-cancelled`=2（表单已收起、两条消息各一枚已取消 tag）；①=阶段 3 真实挂起轮全链：`a` Node 直连 `POST /api/ai/chat` 读到 `frontend_tool_request` 并保持连接 → `b` 清镜像 + 整页刷新 → `loadHistory`/`GET /api/ai/runs/current` 快照重建挂起卡 → `c` 断言重建后 `pendingCards`/`formRendererCount`/`toolRunCount` 各 1 → `d` 合成值提交（400 则降级 `gf-cancel`）并收 `frontend_tool_result`+`done` 帧。
- SMOKE_SKIP_MODEL 逻辑 **有**：`:67 const SKIP_MODEL = process.env.SMOKE_SKIP_MODEL === '1'`，`:303` 起整段跳过阶段 3，断言① 记 `pass:null, skipped:true`（不计 fail 也不计 pass）；退出码由 `:487 process.exit(failures.length === 0 ? 0 : 1)` 决定（另有 CDP/应用未就绪的 `exit 2 / exit 3` 前置硬退）。

## 2. scripts/chat-panel-smoke.mjs — PASS（重叠度需订正）

- 存在，`47408` B ✔（与草案完全一致；47,408 B / 10-10 23:59）。
- `:737 assert('⑬', heldId !== null && s13.skeleton === true && s13.historyLoaded === false …` ✔；`:797 assert('⑫', !!inflight2 && !!sawInterrupt && s12.streamInterrupted === true …` ✔。
- 具名断言 ①~⑬ **共 13 个**（`:454① :472② :487③ :514④ :529⑤ :558⑥ :570⑦ :602⑧ :638⑨ :669⑩ :696⑪ :737⑬ :797⑫`，无缺号；⑬/⑫ 在码内为倒序）。
- 重叠度：**零断言重叠**。全脚本 `FormRenderer|gf-submit|pending-card|tag-form-cancelled|generative_form|重投` 命中数 = **0** ⇒ 「双 FormRenderer」「取消后投影收敛」均**不覆盖**。⑬（阶段 13）是「清镜像 + reload + 用 Fetch 域把 `/api/ai/history` 挂住 ⇒ 骨架屏必现、放行后与空态互斥」——属快照/刷新相邻面，但**不是**「真实挂起轮→刷新→快照重建」（无模型轮、无 `runs/current`）；⑪/⑫ 是确认门挂起后 `stop()` 与真断流（kill 后端）回归。仅**方法论重叠**，且脚本头部 `:52-53` 自陈「其余断言走 `$pinia` 直读 store 状态（手法与 scripts/m2t2a-smoke.mjs 一致）」。

## 3. m2t2a 在 CI/测试链路 — PASS

`grep -rn "m2t2a" .github/ frontend/package.json scripts/verify-e2e.ps1` = 零命中（exit 1）。补充事实：仓库根**无** `package.json`（只有 `frontend/package.json`）；`.github/workflows/ci.yml` 存在，其内 `smoke|m2t2` 亦零命中。ℹ️ 唯一脚本内提及是 `scripts/chat-panel-smoke.mjs:52` 的注释（手法参考，非调用），与 `docs/` 域大量引用（含 `docs/M2-排期计划.md:296/:408/:424-425` 的既有缺陷登记）。

## 4. scripts/verify-e2e.ps1 — PASS（一处口径说明）

- `:2344`：`Say (" 结果：PASS={0}  FAIL={1}  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6（GF6 需 -EnableGf6）；基数不写死，随用例演进）" -f $script:passed, $script:failed)` ✔
- `:2346`：`$gfskipNote = if ($env:E2E_STUB_MODE -eq '1') {` —— 即 **GFSKIP 摘要块首行**（块 `:2346-2351`，实际 `Say (" GFSKIP={0} （{1}）" …)` 在 `:2352`）；桩模式文案 = 「桩模式零容忍：GFSKIP≥1 即整棒 FAIL…」，真模型 = 「模型未触发 SKIP，不计退出码；GF1–GF4 全部 SKIP 判整棒 FAIL，裁决 #1」。
- `:1218`：`# GF1–GF6 生成式表单 E2E 用例（设计稿 docs/evidence/GFc-E2E用例设计-GLM-5.3.md）` ✔（`:1219` 续「GF1–GF5 计入主 PASS/FAIL 与退出码…」）。

## 5. blocks.ts 与折叠函数 — PASS

`frontend/src/utils/blocks.ts` 不存在（`ls` 报 No such file）；全域 `expandedBlocks|groupParts|blocksOf` **零命中**（Grep 工具：No non-sensitive matches found）。

## 6. docs/M2-排期计划.md — PASS

- 499 行 ✔。`:327-333` 逐行 = R-138 / R-139 / R-140 / R-141 / R-142 / R-143 / R-144（以 `:325`=R-136、`:326`=R-137 反推确认，7 行对齐）✔。
- R-140（`:329`）①-1 = 「1 终态挂起卡不再保留（落点 ③b）」；行尾 ③b 注记 = 「**1**（终态挂起卡不再保留）⇒ **维持移交**（A4 渲染位随**批 4④** 模板合并给独立槽位办理…）」✔；①-6 = 「6 `<details>` 展开态仍不持久化（属 R4-1、④批范围）」✔。
- R-139（`:328`）行尾 DS Pro M-2 订正 ✔：`**口径订正（DS Pro M-2 办理）**：四块中**终态 tag 列确不可达**…但 **FRONTEND `pending`「待浏览器自动执行」tag 可达**…——**不得笼统表述「全部不可达」**`；同行含 `tag-form-cancelled` 引用域口径（代码/测试域仅该脚本 2 处；`docs/evidence/` 下 3 份历史文档）。
- R-144（`:333`）后端历史携带状态立项 ✔：「`loadHistory` 整替会丢本地真实终态，根治须**后端历史载荷携带段级状态**」（判定词「未闭合」，去向 = 后端批）。
- `:276` R-84 行 ✔（含 ③a/③b 两段行尾注记：③b 补登 `ExportWizardView.vue:1340` ⇒ 同族面 **21→22**，判定词「未闭合」）。

## 7. 施工卡-刷新版.md（仓外包） — PASS

1020 行 ✔。`:475-499` §4.4 完整命中：标题「### 4.4 子包④ 折叠规则（三规则照抄业界）」+ Block 表 4 行（`utils/blocks.ts`（新）`groupParts(parts): Block[]`「相邻连续 reasoning + tool 合成活动组、不按类型桶」／`stores/ai.ts` `expandedBlocks: Record<blockId, true>`「blockId 必须稳定（toolCallId 或 seg，**禁止下标**）」／`AiPanel.vue :163-166`／`:195-208`「工具卡改为在块内渲染；单调用组**直接展开该卡**，不套壳（**LibreChat 规则**）」／`:142-300` `v-for="block in blocksOf(m)"`）+ **三规则**（1 相邻连续块折叠／2 用户展开过永不自动收起、默认态=进行中展开/完成后折叠／3 正文永不进折叠组）+ **预期行为**（"正文→3 连续工具→正文"⇒ 一个可折叠活动组，后续加 2 个工具帧仍保持展开）+ 验证方式（★改写为入库单测 `frontend/tests/blocks.spec.ts`，`[t,tool,tool,t]`→1 组、`[tool,t,tool]`→2 组、`[tool]` 覆盖单调用不留壳）+ 依赖 ②→④ + 风险反查 R4-1～R4-5 ✔。
`:669-675` = R4-1～R4-5 全文 ✔（R4-1 原生 `<details>` 致"刷新后若要求保持则需落 sessionStorage"、R4-2、R4-3、R4-4 单调用组套壳、R4-5 `requiresAction === true` 强制展开）。
`:679-690` §8 不做清单 ✔（标题「## §8 不做清单（★ 已按决策卡 + R-91 落地情况修订）」，7 行含 #4「新增前端测试框架」**作废**、#7「N1 候选 B 不做」）。

## 8. pkg-health/体检报告.md §9 — PASS

§9 位于 `:569`「## 9. 2026-10-12 批4④前置刷新注记」，其列出的刷新后锚点/行号：
- 刷新范围 = 卡② 区间（`§0 :21` / `§3 :201-384` / `§6 :510-531` / `§7 :540`）；刷新时 HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`、工作树干净。
- 计数：**刷新 114 处（105 实变 / 9 未变）+ 失锚 3 处**；§9.1 分布（`stores/ai.ts` 54 处、最大位移 `runFrontendTool` **+362**、`handleFrame :1151-1399→:1517-1756`；`AiPanel.vue` 18 处 +20～+152 含 2 失锚；`types/ai.ts` 8；`frontend-tools.ts` 6；`ExportWizardView.vue :969-976→:1263-1310`；`RedisChatMemoryRepository.java :33-35→:35-37` 等）。
- §9.2 失锚 3 处：`toPendingCall`（`stores/ai.ts:1488` 锚）HEAD **零命中** ⇒ 改锚 `utils/parts.ts:152`；`AiPanel.vue` 卡锚 `:245`/`:253` 系 ③b D2 删死码 ⇒ 改锚活分支 `:247-258` / CONFIRM 块 `:212-231`。
- §9.3 关键现值：`AiPanel.vue` 18 处新旧行号对照（`:121-126→:142` … `:558-570→:707-719`、`:169→:201`）；§4 轮询治理旁证现值（`setInterval :595-597→:750-752`、`clearInterval →:760-768`）；「卡② §1.3 已入库替代事实」（`types/ai.ts:184-294`+`utils/parts.ts` 段模型真值、`MIRROR_VERSION=2`/`migrateMirror :158-210`、`isFrameDecidedPendingStatus :400-404`、跨宿主已决终帧守卫 `:1569-1574`、三 case 守 `:1615-1617`/`:1636-1641`/`:1686-1690`、`runFrontendTool :1856-1885`）；残面（R-84 同族 22 处、R-144 未闭合）。
- §9.4 自限 4 条（未跑 typecheck/build/vitest/verify-e2e、未补读 ci.yml 两行、后端仅实读 3 文件、台账只读引用未改一字）。

## 9. 仓外包三文档 — PASS（`:254-256` 一处口径订正）

- 行数：决策卡 341 ✔ / 批3③b-施工卡 564 ✔ / 施工报告 224 ✔。
- 决策卡 D1(B)：**D1 块 = `:115-121`**；`:117` 即 K3 终裁原文「**(B) 只做状态机与死码**（采纳 + GLM 修正）。① 模板合并**移交批 4④**；②收工报告须如实写「模板合并未做，挂起卡与工具卡仍并列渲染（`AiPanel.vue:179-288`）」；③ D1(B) 后 ③b **不触 `AiPanel.vue` 渲染区**…」✔；`:118-120` = 两方评议 / 采纳驳回（「A3 归 ③b 删、A4 移交批 4④ 并本批登记」）/ 被否决方案的坑。草案标 `:117-120` 成立，仅提示 (B) 正文起点是 `:117`。
- `:251-262` D16 ✔（`:251` 标题 / `:253` K3 终裁 (A) / `:254` M7/M8/M9 移出登记不修 / `:255` 清悬空引用 / `:256` A22 归登记（`ExportWizardView.vue:1340` ⇒ 21→22）/ `:257` 串行序 ③b→批4④→轮询治理一期 + CDP 至少 1 张「重投后仍只有一张卡」截图 / `:258` **A3/A4 归属消歧** / `:259-262` 评议·采纳·坑·推翻条件）。
- ⚠️ 订正：草案「`:254-256 排除项锚点`」与实际内容不符 —— `:254-256` = D16 第 1/2/3 条（M7/M8/M9 移出、清悬空引用、A22 归登记）；「**排除清单**」字样与排除项裁决在 **`:258`**（`A3 死码归 ③b 删`（D2 定序殿后 + **排除清单**）+ `A4` 移交批 4④）。排除项逐行号清单实体在同包施工卡 §5.2 = `批3③b-施工卡.md:349-355`（`:213-232` 活分支、`:254-256` GF 提交窗口、`:258-260` A4 移交）。
- 施工卡 `:339-361` §5 = §5.1 删除范围逐行号表（`:206`／`:250-253` 含 `data-testid="tag-form-cancelled"`／`:264-277` 8 tag／`:280-286`）+ §5.2 排除项表 + §5.3 链结构约束 ✔；`:365-373` §6 测试锁 (a)~(e) 含「既有 110 例不红」✔；`:462-464` §9.2 串行序「③b → 批 4④（模板合并，含 A1/A4）→ 轮询治理一期」✔。
- 施工报告 `:172-182` §8 台账销记建议共 7 条 ✔；**第 7 条**原文含：`该脚本断言③b（:299 cancelledTags === 2；TESTID 常量 :53）依赖的正是 A3 被删旁路 ⇒ D8 关旁路 + A3 删 tag 后按设计不再成立；脚本不在 yarn test（vitest 只收 tests/*.spec.ts）与 verify-e2e.ps1 / CI 链路内 ⇒ 静默假绿、不会假红`、`须随批 4④ 改写或退役落行`、`批 4④ 施工卡同时应把「重投路渲染锁改为自承 RED」（GLM O-4 互证链重估）列入测试锁要求` ✔（第 1 条另含「R-140①-1（A4 移交批 4④…本批仅登记）」）。

## 10. 批3b-evidence — PASS

- `E2E-stub-final.log` 末段三段全在 ✔：`结果：PASS=28  FAIL=0  （主用例计数，含 TC1–TC22、TC23 与 GF1–GF6…）`、`GFSKIP=0`、`Q8 项：PASS=2 FAIL=0`、`e2e_exit=0`。
- `批3b-evidence/cdp/` 含 `20261011T182213Z-重投后仍只有一张卡-baseline.png`、`20261011T182213Z-重投后仍只有一张卡-final.png`、`result-20261011T182213Z.json` ✔（即该时间戳确实留下一张卡的重投后证据，baseline+final 两帧）。

## 11. tag-form-cancelled 引用域 — FAIL（按草案限定的 docs/evidence/ 口径）

- `docs/evidence/` = **7 份**（< 8）：`M2-T2b-施工与验证-K2.8.md`、`补档-CHATPANEL-施工卡.md`、`补档-CHATPANEL-施工报告-苞B.md`、`补档-CHATTIMELINE-批3③b-复核报告-GLM-5.3.md`、`补档-CHATTIMELINE-批3③b-施工卡.md`、`补档-CHATTIMELINE-批3③b-施工报告-K2.8.md`、`补档-CHATTIMELINE-批3③b-红队报告-DS-V4-Pro.md`。
- 若按 **docs/ 全域** 计 = **8 份**（上述 7 + `docs/M2-排期计划.md`，R-139 行尾注记）→ 此时 ≥8 成立；可执行代码域仅 `scripts/m2t2a-smoke.mjs`（2 处），`frontend/tests/` 零引用。建议草案把口径写成「docs/ 域 ≥8」或回落为「docs/evidence/ 7 份」。

## 12. 批4④-施工卡草案.md 指纹 — PASS

`sha256sum` = `d6835ebcb0404c93d7f17e33020984e1efbc6a38914a0b4424ab851dc5a57e82` ✔（与给定值逐字符相同）；大小 `83334` B ✔；`485` 行 ✔。

---

**自证**：我实际运行的模型 = **`DeepSeek/deepseek-flash`**（provider `openai`），自证方式 = 只读本会话 `<KIMI_SESSIONS>`（该文件含本次任务串 `d6835ebcb0404c93` 与 `m2t2a-smoke`，其中 `"model":"DeepSeek/deepseek-flash"`）。
