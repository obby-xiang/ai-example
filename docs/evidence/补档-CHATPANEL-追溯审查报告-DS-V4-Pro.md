> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

执行者=DeepSeek V4 Pro，授权来源=用户裁决呈批 3 追溯（2026-10-11）

# 追溯审查报告 · 对话面板包复核链（呈批 3 追溯执行）

- **执行者**：DeepSeek V4 Pro（反方审查/红队，本报告作者）
- **授权来源**：ai-example 合流项目用户裁决，呈批第 3 条「红队厂商链登记」升级为追溯（2026-10-11）
- **审查对象**：仓库 `<MAIN_REPO>` 的 4 笔提交 `286a337` / `ecd0395` / `157f9b9` / `3d20de9`（对话面板包），及 `<SCRATCH>/chat-panel-pkg/` 下 6 份过程报告
- **纪律**：只读审查，零代码修改、零 git 写操作、未删改任何既有报告；未读任何 `.env`；断言逐条标【实测】/【推断】/【假设】

---

## 0. 裁定结论（一句话）

**原复核链结论「部分维持 / 部分推翻」。**

- **处置面维持**：GLM 红队 0×BLOCKER / 1×MAJOR / 3×MINOR / 7×观察 的全部 11 条均有落点（修复代码位置或显式登记），逐条核对见 §5。
- **技术面部分推翻**：MAJOR-1（`loadHistory` 会话身份守卫）的收口修**核心闭环成立**（history 窗口已守卫），但**存在一处未闭环的残余窗口**（`restoreSuspendedSnapshot` 内部 await），详见 §4.2 与新问题 N1。
- **事实面无法确证为主**：厂商链（作者/复核者/收口终审的厂商身份）因过程报告未署名而不可考；红队 = GLM 可确证；「复核方自任反方」在行为层面可确证；「复核者与红队同为 GLM 系」**无法确证**（证据不足，只能给可能性排序），详见 §2。

---

## 1. 事实确认（作者 / 复核者 / 红队 / 收口终审）

### 1.1 git 提交侧（4 笔提交的客观事实）

【实测】`git show -s --format` 对 4 笔提交逐一取作者/提交者：

| 提交 | 作者（Author） | 提交者（Committer） | 提交时间 |
|---|---|---|---|
| `286a337`（后端端点） | `obby-xiang <<USER_EMAIL>>` | 同左 | 2026-10-11 01:24:06 |
| `ecd0395`（前端） | `obby-xiang <<USER_EMAIL>>` | 同左 | 2026-10-11 01:24:09 |
| `157f9b9`（冒烟脚本） | `obby-xiang <<USER_EMAIL>>` | 同左 | 2026-10-11 01:24:12 |
| `3d20de9`（文档台账） | `obby-xiang <<USER_EMAIL>>` | 同左 | 2026-10-11 01:24:26 |

【实测】4 笔提交**同一 git 身份**（作者=提交者=`obby-xiang`，无 co-author，无 trailer 署名），提交时间集中在 20 秒内（01:24:06~01:24:26），与「工作树在制品 → 事后批量提交」的时序吻合。

**关键判定【实测】**：git 作者是人类 `obby-xiang`，**不是厂商**。git 不记录"哪家 AI 模型产出了这份内容"。因此「作者厂商」这一维度**无法从 git 确证**，只能看过程报告——而过程报告未署名。

### 1.2 各角色厂商身份还原

| 角色 | 产出物 | 厂商身份 | 证据等级 |
|---|---|---|---|
| **作者**（苞 A / 苞 B 施工） | `施工报告-苞A.md`、`施工报告-苞B.md` + 代码 | **无法确证** | 两份施工报告首页无「执行者模型」署名；git 仅有 `obby-xiang`（人类） |
| **复核者** | `《复核报告.md》`、`《复核收口报告.md》` | **无法确证** | 两份复核报告首页无署名 |
| **红队**（反方） | `GLM红队报告.md` | **GLM** | 【实测】报告第 4 行自署「审计角色：GLM 红队」 |
| **收口终审** | `收口修报告-红队MAJOR1.md` | **无法确证** | 首页无署名；正文「裁决来源 = GLM 红队带条件 PASS + 指挥官裁决」 |

【实测】仓库内 `docs/evidence/` 的补档副本（`补档-CHATPANEL-*.md`）首页一律加了一行「来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，**署名信息以文内为准**）」——即补档**未新增署名**，原文即无署名，事实链仍不可考。

【实测】`docs/adr/DECISION-REGISTER.md:23` DC-19 的缘起字段明写：「2026-10-10 下午起多份过程报告未署名，致**对话面板包复核链不可考**（合规回溯审计发现）」。即本次审查对象正是 DC-19 的立法缘起。

### 1.3 「复核方自任反方」——行为层面【实测】成立

【实测】`《复核报告.md》` §0 自述复核方式为「**独立复现 + 对抗反查**」；§4 标题即「**对抗反查**」，§4.1~§4.7 实际执行了红队性质攻击面分析（威胁模型分级 / 越权 / XSS 注入面 / 竞态复现 / 特殊字符注入），并产出 F1~F10 中多条安全类发现。这与项目纪律 `docs/merge/05-合流落地执行手册.md:61`「反方审查（Challenger）——评审者的任务是『设法推翻它』」所定义的**专职反方红队职能**高度重叠。

**结论【实测】**：复核报告在行为上越过了「独立复现（Verifier）」边界、自任了「反方红队（Challenger）」职能，即**「复核方自任反方」在行为层面确证**。但这是"职能边界模糊"，与"厂商身份同一"是两个不同问题——前者可确证，后者不可确证。

### 1.4 「复核者与红队同为 GLM 系」——无法确证（可能性排序）

【实测】红队 = GLM 确证（GLM红队报告.md:4）。
【实测】复核者厂商无任何署名或自述，**无法确证**。

**可能性排序**（按证据强弱）：

1. **【推断·较可能】复核者 = GLM 系**。理由：本仓库过程证据链的「复核/独立复现」角色惯例性由 GLM-5.3 承担（`docs/evidence/M1-出口复核-GLM-5.3.md`「角色：独立复核员（Verifier，GLM-5.3）」「作者链条为 Kimi/DeepSeek 系，本复核为异厂商交叉验证」、`GFb-复核验证-GLM-5.3.md`、`M2-T3a-复现与复核-GLM-5.3.md` 等）。若该惯例在本包延续，则复核者 = GLM，与红队 = GLM **同厂商**，违反「作者/复核/红队异厂商」纪律（`docs/M2-排期计划.md:19`）。这是原怀疑的实质来源。
2. **【推断·次可能】复核者 = DS-V4 系**。理由：本包红队反常地用了 GLM（惯例红队是 DS-V4-Pro，见 `DECISION-REGISTER.md:34`「红队审查见各 *-红队*（DS-V4-Pro 棒）」），若本包把「复核」与「红队」两职对调（复核让 DS-V4、红队让 GLM），则互异仍成立。
3. **【推断·较低】复核者 = Kimi 系**。无直接证据支撑，仅因指挥官为 Kimi K3 而存在可能性。

**结论【实测+推断】**：无法确证「复核者与红队同为 GLM 系」这一具体厂商断言；但「复核者与红队是否同厂商」这一纪律问题**因未署名而不可判定**，且按项目惯例，同厂商（GLM）的可能性最高。无论哪种情况，厂商链不可考本身就是纪律违例——DC-19 已对此立法补救。

---

## 2. 无法确证清单（含可能性排序）

| # | 无法确证项 | 缺什么证据 | 可能性排序（高→低）与理由 |
|---|---|---|---|
| U1 | **作者（苞 A / 苞 B）厂商** | 施工报告首页署名、或任务卡上明示的厂商分派 | ① DeepSeek/Kimi/GLM 任一家（无倾向性证据）；git 仅见 `obby-xiang`（人类），不可判 |
| U2 | **复核者厂商** | 复核报告首页署名 | ① GLM 系（项目惯例，见 §1.4-1）；② DS-V4 系（红队/复核对调的假设）；③ Kimi 系 |
| U3 | **收口终审厂商** | 收口修报告首页署名 | ① 无倾向性证据；正文仅指向「指挥官裁决（Kimi K3）+ GLM 红队条件」，执行者厂商未知 |
| U4 | **「复核者与红队同厂商」是否成立** | 复核者署名 | ① 成立（若复核=GLM 惯例成立）；② 不成立（若复核=DS-V4）；③ 不可判（现况） |
| U5 | **「作者/复核者/红队三者是否两两互异」** | 三者署名 | ① 违例（若复核=红队=GLM）；② 合规（若复核≠GLM）；③ 不可判（现况） |

> 【实测】唯一可确证的厂商互异关系：红队 = GLM，与任何一家「非 GLM」的作者/复核者都互异；与「GLM 系」的作者/复核者都同厂商。

---

## 3. 4 笔提交 diff 全覆盖声明与范围

【实测】`git diff 03a6cbe..3d20de9 --stat` = **11 个文件，+1597 / −22**。本次审查对 4 笔提交的全部 11 个文件做了全覆盖，**无抽样遗漏**：

| 提交 | 文件 | 审查要点 |
|---|---|---|
| `286a337` | `RedisChatMemoryRepository.java`(+17)、`AiController.java`(+97)、`AiControllerHistoryDeleteTest.java`(+338) | 删除端点契约、SISMEMBER 判存、11 用例、删除范围边界 |
| `ecd0395` | `api/ai.ts`(+14)、`AiPanel.vue`(+144)、`stores/ai.ts`(+104) | 新建对话、resetSession 双写、hover 复制、骨架屏、onClose 抑制、loadHistory 守卫 |
| `157f9b9` | `chat-panel-smoke.mjs`(+823) | CDP 冒烟 13 断言、剪贴板 5s 上界、如实 SKIP |
| `3d20de9` | `README.md`、`M2-排期计划.md`(+74)、`docs/README.md`、`DECISION-REGISTER.md` | R-100~110 登记、DC-17/18、红队结论注记 |

【实测】`git log 3d20de9..HEAD -- <上述代码文件>` 为空 → 4 笔提交之后，这些代码文件**未被后续提交改动**，本次按当前 HEAD 实读的行号与 4 笔提交落盘态一致，可作【实测】行号依据。

---

## 4. MAJOR-1 收口修的闭环性验证（重点项 a）

### 4.1 核心闭环【实测】成立

GLM 红队 MAJOR-1 定义：「`loadHistory` 完成写入无会话身份守卫，旧会话历史可迟到写回、污染新会话」。收口修在 `frontend/src/stores/ai.ts` 落地如下（行号为当前 HEAD 实读）：

| 守卫点 | 位置 | 实测代码 | 作用 |
|---|---|---|---|
| ① 发起时捕获 | `ai.ts:433` | `const sid = this.sessionId` | 记录发起时会话身份 |
| ② catch 分支守卫 | `ai.ts:449-451` | `if (this.sessionId !== sid) { return }` | 过期失败不落任何状态 |
| ③ 完成路径守卫 | `ai.ts:458-460` | `if (this.sessionId !== sid) { return }` | 身份不符整体丢弃 |

【实测】③ 号守卫位于 `:458`，其**下游**（同一无 await 段）依次是：主分支 `this.messages = history.messages.map(...)`（`:475`）、`restoreSuspendedSnapshot()`（`:500`）；空分支 `pendingCall→expired` 降级（`:463-467`）、`restoreSuspendedSnapshot()`（`:471`）。故单一对账点 `:458` 同时覆盖「写 messages」与「触发两处 restore」的全部写点。**MAJOR-1 的 history 竞态窗口（`await aiApi.history` 期间换会话）已闭环**。

【实测】行为级探针（`收口修报告-红队MAJOR1.md` §3）：阳性对照（会话未变→被写入）/ 负例（会话已变→`messages=0`、sessionId 仍为新 id，未被写入）双 PASS，与代码守卫位置一致。

### 4.2 残余窗口【实测】存在（收口非 100% 闭环）

【实测】`restoreSuspendedSnapshot()`（`ai.ts:508-581`）内部**自带一个 await**：

```
:511  current = await aiApi.runsCurrent(this.sessionId)   // ← 此处 this.sessionId 在调用起点同步求值（= sid）
:524-581  基于 current.pendingEntries 重建挂起卡，写入 this.messages / this.activeForm / upsertToolRun
```

守卫 `:458` 只覆盖「是否**调用** restore」，**不覆盖 restore 内部 await 完成后**的写回。构造路径：刷新页 → `loadHistory` 到达 `:500` 的 `await restoreSuspendedSnapshot()` → 期间用户点「新建对话」（`resetSession` 换 id、清 messages）→ `runsCurrent` 返回旧会话的挂起轮次 → `:524-581` 把旧会话的**挂起卡（确认门/前端工具）与 `activeForm`** 写进新会话。

**判定【实测】**：这是 MAJOR-1 同一缺陷类的**更窄残余窗口**（仅 `runsCurrent` 一个 await，且需旧会话恰有挂起轮次），后果为 UI 层挂起卡污染、可自愈，无数据损毁、无越权。但**「MAJOR-1 收口真闭环」严格说应判「核心闭环 + 一处残余」**。收口修报告 `§5-3` 已把该落点形态列为「待裁决 #3」，但其论证「await 之后到写点之间没有第二个 await、逐点判会死代码」**对 restore 子路径不成立**——restore 内部确有第二个 await（`runsCurrent`）。详见新问题 N1。

---

## 5. 0B / 1M / 3MINOR / 7观察 逐条落点核对（重点项 b）

【实测】逐条核对 GLM 红队 `GLM红队报告.md` §4 的 11 条发现，落点如下：

| 红队条目 | 内容 | 处置 | 落点证据（文件:行）【实测】 | 核对 |
|---|---|---|---|---|
| **MAJOR-1** | loadHistory 会话身份守卫 | **修复** | `ai.ts:433` 捕获 / `:449` catch 守卫 / `:458` 主守卫 | ✅ 有落点（另见 §4.2 残余） |
| **MINOR-1** | stopped 只在 send/resetSession 复位 | **修复** | `ai.ts:609`（resumeSnapshotStream `this.stopped=false`）、`:824`（reattachActive `this.stopped=false`） | ✅ 有落点 |
| **MINOR-2** | 新端点无鉴权/无归属校验 | **登记（不修，按设计）** | `docs/M2-排期计划.md:483`「MINOR-2 处置口径=引入鉴权批须联动会话归属校验，并入 R-108 注记」+ DC-10 背书 | ✅ 有落点（显式登记） |
| **MINOR-3** | 「不删范围」无回归锁 | **修复** | `AiControllerHistoryDeleteTest.java:275` `deleteHistoryNeverWritesRunStoreOrSessionGate`，`:289-305` 对 RunStore 全 15 写口 + SessionGate acquire/release 逐一 `verify(never())` | ✅ 有落点 |
| **观察-1** | ⑫ SKIP 面 + 剪贴板回读 | **半修复+登记** | 剪贴板：`chat-panel-smoke.mjs:74` `SMOKE_CLIPBOARD_TIMEOUT_MS=5000`、`:418` `readClipboardBounded`、`:596` 记录 `clipboardReadbackStatus`；⑫ SKIP：`:367-368` 未授权报 SKIP（编排层断言仍待裁决） | ✅ 有落点 |
| **观察-2** | ⑪ 假红面（cancel 时序） | **登记（不修，fail-loud）** | `收口修报告-红队MAJOR1.md` §5-2 / GLM红队报告 §4 观察-2 登记知悉 | ✅ 有落点（登记） |
| **观察-3** | M2 台账 hunk 归属待确认 | **登记（指挥官裁决）** | 复核 F1 / 观察-3，`收口修报告` §5-1、§8-1 | ✅ 有落点（登记） |
| **观察-4** | check-docs 未跟踪文件盲区 | **登记** | 复核 F10 | ✅ 有落点（登记） |
| **观察-5** | DC-17 兼容层定性 | **登记（R-107 落地时写明）** | GLM红队报告 §4 观察-5 | ✅ 有落点（登记） |
| **观察-6** | cancel 失败提示口径不一致 | **登记知悉** | GLM红队报告 §4 观察-6 | ✅ 有落点（登记） |
| **观察-7** | F5 守卫正面确认 | **确认（无动作）** | `ai.ts:1179` `isCurrentRound = this.sessionId === nextId && this.messages.length === 0`；`:1184` 守卫内写 notice | ✅ 有落点（确认） |

【实测】测试数闭合：`AiControllerHistoryDeleteTest` 共 11 个 `@Test`（用例 1~8 原苞 A + F4 两例 `indexedCheckIsSingleSismemberNotFullIndexScan`/`absentMemberReportsRemovedFromIndexTrueWithSingleRoundTrip` + MINOR-3 一例），与提交 `286a337` 主题「11 用例」一致；后端全量 400/0/0/0（= 389 基线 + 8 苞 A + 2 F4 + 1 MINOR-3）。

**结论【实测】**：0B/1M/3MINOR/7观察 **逐条均有落点**（修复代码位置或显式登记记录），无「有结论无处置」的悬空项。

---

## 6. 对抗审查（7 轴，推翻式）

对 4 笔提交完整 diff 按任务指定七轴对抗，结论：

| 轴 | 结论 | 证据【实测】 |
|---|---|---|
| **并发** | 在飞轮次 `convergeMemory` 收盘 `clear+add` 会写回已删键——**已知代价、如实暴露**，非新缺陷；端点以 `runActive`/`activeRunId`/WARN 报告 | `AiController.java:571-577`、`:587-588`；复核报告 §4.2 活体 2/2 复现 |
| **事务** | 纯 Redis 操作，无事务需求；`DEL+SREM` 非跨键原子（`RedisChatMemoryRepository.java:145-148`），但崩溃残留仅使索引多一个惰性清理成员，无危害，属既有设计 | `deleteByConversationId` 实读 |
| **越权** | 无鉴权（DC-10 内网受信基线），任意 sessionId 可删；边际增量=记忆命名空间内首个破坏性动词——已按 MINOR-2 登记联动 | `AiController.java:562`；DC-10；M2-排期:483 |
| **注入** | sessionId 作 Redis 键二进制安全，`SISMEMBER`/`contains` 精确比较无 glob 注入；复制链路 `m.content` 走 `textarea.value`/`writeText` 属性赋值，全组件无 `v-html`，`m.id` 前端生成 | `RedisChatMemoryRepository.java:119`；`AiPanel.vue:600-611/629-643` |
| **幂等** | 不存在/重复删均 200（`deleted=true, existed=false`），不 404；`deleted` 恒 true 为幂等语义 | `AiController.java:581-583`；测试用例 2/3 |
| **会话身份守卫** | loadHistory 主窗口已守卫（`ai.ts:458`）；**restoreSuspendedSnapshot 内部 await 残余（新问题 N1）** | `ai.ts:511` 无二次对账 |
| **删除范围边界** | 仅 `chat:mem:<id>` + `__ids__` 成员，不删 `ai:session:*`/`ai:run:*`——MINOR-3 负断言锁死，复核报告活体 `--scan` 证仅剩 `ai:session:` | `RedisChatMemoryRepository.java:145-148`；`AiControllerHistoryDeleteTest.java:275-305` |

---

## 7. 新问题清单（本追溯审查新增）

| # | 级别 | 问题 | 证据【实测】 |
|---|---|---|---|
| **N1** | **MINOR（倾向 MAJOR）** | `loadHistory` MAJOR-1 守卫未覆盖 `restoreSuspendedSnapshot()` 内部 `runsCurrent` await：会话在该 await 期间被 `resetSession` 换掉时，旧会话的挂起卡（确认门/前端工具）与 `activeForm` 仍被写进新会话。守卫只覆盖「是否调用 restore」，不覆盖「restore 完成后写回」 | `ai.ts:458` 守卫 vs `:500` 调用 vs `:511` 内部 await vs `:524-581` 写回无对账；收口修报告 §5-3「无 await 间隔、逐点判死代码」论证对该子路径不成立 |
| **N2** | **MINOR** | `deleteByConversationId` 的 `DEL` 与 `SREM` 非原子，进程在两步间崩溃会在 `__ids__` 留 stale 成员（指向已删键）。后果有界（`findConversationIds` 惰性清理兜底），属既有设计，登记知悉 | `RedisChatMemoryRepository.java:145-148` |

> 修复建议（供裁决，不代改）：N1 在 `restoreSuspendedSnapshot` 内、或在其调用点之后，补一次会话身份对账（`if (this.sessionId !== sid) { return }`，或在 restore 内捕获 `sid` 后于写回前判）。一行级，与 MAJOR-1 同族。N2 若需消除，可将 DEL+SREM 改为单次 Lua 或事务；鉴于后果有界，建议登记不修。

---

## 8. 裁定

| 面 | 裁定 | 依据 |
|---|---|---|
| GLM 红队 11 条处置落点 | **维持** | §5 逐条核对全有落点 |
| MAJOR-1 闭环性 | **部分推翻**（核心闭环 + 一处残余 N1） | §4 |
| 事实链（厂商身份） | **无法确证为主** | §1/§2；红队=GLM 可确证；「复核自任反方」行为面确证；「复核者=红队=GLM」不可确证但按惯例可能性最高 |
| 复核链合规性 | **纪律违例成立**（未署名致厂商链不可考） | DC-19 缘起字段直接坐实 |

**终审结论**：对话面板包代码主体质量扎实，GLM 红队 11 条结论均有落点、无悬空；MAJOR-1 收口修核心正确但非 100% 闭环（残余 N1，建议随下批一行级收口）；过程报告的厂商链因未署名不可考，已由 DC-19 立法补救，本报告作为追溯补强留痕。

---

*本报告为只读审查产物，未修改任何代码/既有报告，未执行任何 git 写操作。所有【实测】断言均有文件:行号或 git 输出佐证；【推断】均已标注并给出理由。*
