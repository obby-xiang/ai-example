> 来源=<仓外证据>/tab-clear-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 复制页签 = 全新工作区（确认复制后一次性清空 sessionStorage）· 独立任务评审

- 日期：2026-10-25
- 仓库：`<主仓>`（基线 `ae883fb`；工作区含本批改动：`frontend/src/stores/ai.ts` 30+/7-、新增 `frontend/tests/duplicated-tab-clear.spec.ts`）
- 实施副本：`<验证副本>/_verify-20261025-tab-clear-final/frontend`（已核字节：`src/stores/ai.ts` 与本 spec 与主树**逐字节一致**；`diff -rq src` 无其它差异）
- 评审输入：`tab-clear-final.diff`、`tab-clear-final-tests.diff`、`tab-clear-final-report.md`、`tab-clear-final-{typecheck,build,real-browser}.log`、`tab-clear-final-full-suite.log`
- 只读评审：未改代码、未跑测试/构建（按要求不重跑并行全量；主树串行全量由主代理另行在跑，评审时仍在进行中）

## 0. 结论

| 维度 | 结论 | 说明 |
|---|---|---|
| **Spec Compliance** | **PASS** | 五项批准口径全部落地：先判据后清空、只对确认的复制页签清一次、清后重建 pageId + 新 AI 会话、原页签/localStorage/服务端零触碰、普通刷新与 bfcache 永不清、继承草稿有意丢弃 |
| **Task Quality** | **PASS WITH ISSUES** | 无 Critical、无阻塞项。1 个 Important（探活 API 失败被判成"确认复制"⇒ 本批把可恢复误判升级为不可恢复清空）+ 10 个 Minor（过时注释、重试路径/降级路径无清空断言、测试桩账本泄漏、证据可追溯性等） |
| **Blockers** | **无** | 建议可合入；Important 建议 1 行加固（或显式接受），不阻断本次验收 |

## 1. 需求逐条核对

### 1.1 清空前先证明"是复制品" —— 满足

- 判据仍只有一处裁决：`frontend/src/stores/ai.ts:557-571`（`resolveTabOwnership`，3 × 150ms 重试）→ `probeSessionLock`（`:434-469`，`ifAvailable` 收到 `null` = 另一文档持锁）。
- 清空点在 `:4636`，被 `:4628 if (role === 'duplicate')` + `:4629 if (returning) return` 双重包裹 ⇒ **只有"锁被另一文档持有 + 无返回意向"才进入**。
- 挂载次序保证窗口无害：`frontend/src/components/AiPanel/AiPanel.vue:3626-3639` 先 `startMirror()` → `ensureTabOwnership()` **await 完成后**才 `loadHistory()`。复制页签在探活的 ~450ms 内不会读继承镜像、不会拉原会话历史（`loadHistory` 仅此一处挂载调用点，另为 `takeOverSession` 内部）。
- 判据依据在清空前已离开存储：`sid` 先读入内存（`:4613`）、返回意向在探活前一次性消费（`:4615` → `takeReturnIntent:529-551`）。

### 1.2 确认复制后"清一次"，再重建页签 + AI 身份 —— 满足

- 清空：`frontend/src/stores/ai.ts:642-648`（`clearDuplicatedTabStorage`），全仓唯一一处 `sessionStorage.clear()`（`grep -rn "sessionStorage.clear" src/` 只命中 `:644` 与文档行 `:1189`）。
- 顺序：`:4636 clear → :4637 adoptFreshSession → :4638 rotatePageId → :4639 claimSessionOwnership`。先 adopt/rotate 再 clear 会抹掉新身份；测试 `tests/duplicated-tab-clear.spec.ts:181-204` 断言最终只剩 `[ai-session-id, ai-tab-page-id]`、且 `ai-session-id === store.sessionId` ⇒ **顺序回归会被这条用例抓住**。
- "只清一次"不是显式 flag，而是三道闸的合力：`tabOwnership !== 'unknown'`（`:4599`）、清后 `sessionId` 变更（`:4618` 身份守卫）、以及 `adoptFreshSession` 同步完成（`:4515-4577`）。因此即便同 tick 两次 `ensureTabOwnership`（App 只有一个 `<AiPanel>` 挂载点，`frontend/src/App.vue:41`），第二条 promise 恢复时 `this.sessionId !== sid` 直接 return，**不会二次清空**（JS 单线程：第一条的 clear+adopt 整段同步完成，中间无 await）。
- 新身份：`adoptFreshSession` 换 sessionId（`:4540-4547`）、`rotatePageId` 换 pageId（`:360-368`）、`claimSessionOwnership` 认领新锁（`:4683-4690`）。真机证据见 §6。

### 1.3 原页签 / localStorage / 服务端业务数据不动 —— 满足

- `clear()` 只作用在 `sessionStorage`（按文档隔离），代码里从未调用 `localStorage.clear()`（grep 0 命中）。
- 复制页签**没有**原会话锁的账本：`probeSessionLock` 在 occupied 分支不写 `ownedSessionId/heldLock`（`:453-457`），因此 `adoptFreshSession` 内的 `releaseOwnLock(oldSessionId)`（`:4520` → `:493-502`）是空操作 ⇒ 不会误放原页签的锁。
- 服务端：复制分支只 `claimSessionOwnership`（本地锁 + 广播），不调任何删除接口；用例断言 `api.deleteHistory` 零调用（spec `:209-231`），真机日志同。
- 真机核对：`tab-clear-final-real-browser.log` 明确 PASS「original tab still holds its lock」「original tab still has its session mirror, draft, business key and localStorage」。

### 1.4 普通刷新 / bfcache 永不清 —— 满足

- 普通刷新：锁随旧文档销毁释放 ⇒ `owner`（spec `:122-141`）。即便旧文档锁"延迟释放"超过 3 次探活，`pagehide` 写下的返回意向（`:514-523`，同 sid + 同 pageId + 60s）让 `returning=true` ⇒ `:4629` 提前 return，不清（spec `:140-166`；既有 `tests/tab-ownership-lifecycle.spec.ts` 的慢重载用例同口径）。
- bfcache：`pagehide.persisted=true` 不广播、不释放（`:592-594`），`pageshow` 走 `revalidateTabOwnership`（`:4657-4680`），该路径只可能置 `duplicate`，**没有任何 clear 调用点**（spec `:254-273`）。
- 崩溃恢复 / Chrome 标签丢弃：旧文档锁已释放、无返回意向 ⇒ 探活 granted ⇒ owner，不进入清空分支（逻辑推演，无专项用例）。

### 1.5 继承的未提交草稿有意丢弃 —— 满足

- `sessionStorage.clear()` 覆盖 `cm.ai.form.draft.*` 与业务页签键；spec `:181-204` 显式断言草稿（`FORM_DRAFT_PREFIX + 'call-1'`）与 `import-wizard-draft` / `some-business-tab-state` 均为 `null`；真机日志同（`inherited old mirror / draft / business keys are gone`）。

## 2. 判据竞态逐项核对

| 现场 / 竞态 | 代码路径 | 结果 | 评价 |
|---|---|---|---|
| 快刷新（旧文档锁已释放） | `:557-571` 第 1 次即 granted | owner，不清 | 正确；spec `:122` |
| 刷新但旧文档锁尚未释放 | 3 次重试；`returning=true` | 降级 duplicate，不清 | 正确；`pagehide` 先于新文档 load，意向必然先写（除非 `ownedSessionId===null`，见 `:588`） |
| 慢重载（> 宽限，锁已被副本接手） | `:4629 returning` | 不清、不换 id、不清屏 | 正确；spec `:140`、既有 lifecycle 套件 |
| 探活期间会话被换掉（新建对话 / 410 恢复） | `:4618 if (this.sessionId !== sid) return` | 不为旧会话落角色；`tabOwnership` 留 `unknown`，后续可重判 | 正确（保守：无确认不清） |
| 同 tick 两次初始化 | 第二条同 `:4618` 返回 | 单次清空 | 正确（详见 §1.2） |
| 无 Web Locks | `:4609-4612` → `unsupported`，同步返回 null | 不清 | 正确、有意保守；spec `:165` |
| bfcache 恢复 | `:4657-4680` 无 clear | 不清 | 正确；spec `:254` |
| 探活 API 抛错 / promise reject | `:464 .catch(() => settle('occupied'))` → 3 次后 `duplicate` | **会清空** | **风险点，见 Important I1** |
| 继承到一条未消费的返回意向（极窄：原页签 reload 的瞬间被复制） | `:4629 returning=true` | 真复制品也走"不清"，沿用原会话直到下次刷新 | 方向保守、可自愈；Minor M9 |

结论：**"误判成复制 ⇒ 清空"的唯一常规入口已不存在**（刷新有锁空闲 + 返回意向双保险）；残余误判入口是探活 API 异常（I1）。
反方向"真复制品没清"存在极窄路径（M9），后果=旧行为，无数据损失。

## 3. Web Locks / BroadcastChannel 兜底

- 无 Web Locks：`unsupported`，本批功能整体不触发（不清、不换 id），与批准口径"无法确认则不清"一致；Safari 15.4+/Chrome/Firefox 均支持，旧环境降级可接受。
- 无 BroadcastChannel：只影响让渡/接手（`:376-399` 关闭通道、`channelCtor` 为 null），**不影响本批判据与清空**（`probeSessionLock` 独立于通道）。
- 两者都没有时不存在"清空后无人接手"的悬挂：清空后本页签自持新会话锁（`:4639`）。

## 4. 清空顺序与重复初始化

- 顺序固定为 clear → adopt → rotate → claim，中间无 await（`:4636-4639`），不存在"clear 抹掉新身份"或"新身份已写再被清"的窗口；若 `adoptFreshSession` 在写 `SESSION_ID_KEY` 前抛异常，最坏结果是本页签下次刷新重新生成 id（不丢服务端数据），属可接受降级。
- 重复初始化：唯一生产调用点 `AiPanel.vue:3633`；`tabOwnership` 永不被重置回 `unknown`（grep 只有初值 `:1232`、写入 `:4610/4622/4677/4769/4797`），因此面板重挂载不会二次判定；同 tick 并发由身份守卫兜住（§1.2）。**没有 in-flight promise 记忆**（M11），只浪费一次探活。
- 清空不会触发另一次判定：`clear` 不改 store state；`adoptFreshSession` 的 state 变更只让 `$subscribe` 在新 sessionId 下写一份**新**空镜像（真机日志 `ai-mirror:<new>` 可见，符合预期）。

## 5. 410 / 锁账本 / 无关状态删除

- **410 `SESSION_PURGED` 未受影响**：恢复路径 `:2611-2629` 仍用 `adoptFreshSession()`（不清存储、只删旧镜像）+ `claimSessionOwnership`，本批只改了 `cleanupSessionLocalArtifacts` 的注释（`:1189-1191`），行为逐字未动。聚焦套件含 `session-purged-recovery`/`irreversible-lifecycle`，未红。
- **锁不被误放/泄漏**：复制分支的 `ownedSessionId` 为 null（occupied 不记账），`releaseOwnLock` 空操作；随后 claim 用 `probeSessionLock` 就地覆盖账本为**新**会话锁，旧会话锁从未进账 ⇒ 不存在"释放句柄丢失、挂到文档销毁"的旧缺陷回归（既有 `tab-ownership-lifecycle`「账本不泄漏」用例仍绿）。
- **无关状态删除**：清空面无 allow-list（M6）。今天可接受 —— 全仓 `sessionStorage` 使用点只有 `src/stores/ai.ts`（sessionId/pageId/镜像/返回意向/展开态）与 `src/utils/form-draft.ts`（草稿），未发现 iframe、未发现其它业务键写入点（grep 复核）。但任何**将来**在启动期写入 `sessionStorage` 的非 AI 键，都会在复制页签被判定的 ~450ms 内被无差别删除。当前批准口径要求"业务页签键也不继承"，故按设计接受，仅记录风险。
- 唯一"看着像无关"的删除：`ai-panel-expanded`（页签级 UI 偏好）。当前渲染沿用内存初值，下次刷新回默认（M7）。

## 6. 测试与证据评估（真实状态 vs 桩）

### 6.1 用例是否只测桩

- **不是纯 mock**：8 条用例驱动真实 store、真实 jsdom `sessionStorage`，断言的是清空后的**真实键集合**与真实值（spec `:181-204` 断言排序后的键列表必须恰为 `[PAGE_ID_KEY, SESSION_ID_KEY]`）；"只清一次"用 `Storage.prototype.clear` 真实调用计数（spec `:119`）。
- 桩的部分与仓库既有口径一致：`FakeLockManager` / `FakeBroadcastChannel`（`tests/tab-ownership-fixtures.ts`）、`@/api/ai` mock。即**唯一可靠判据（Web Locks）在单测里是假的**，真实语义只由真机探针背书 —— 这是本仓 RC-4 一贯做法，可接受。
- 缺口（均列入 Minor）：
  - 无"锁第 1/2 次占、第 3 次空闲 ⇒ owner 且不清"的重试路径清空断言（M3）—— 这正是 3×150ms 重试存在的理由；
  - 隐私模式 `clear()` 抛错 → `clearMirrorRefs` 兜底未测（M5）；
  - 模块级 `heldLock` 账本在测试间不重置，"刷新不再清第二次"（spec `:231`）实际走 `:440` 自持锁短路而非真探活（M4）。
- 结构上无回归锁被削弱：既有 `r166b-fixes` / `tab-ownership-lifecycle` 的复制页签用例仍绿（聚焦 68/68 覆盖）。

### 6.2 真机 14/14 探针是否足够

- 14 项断言都与本批口径对齐：新 sessionId/pageId、复制页签最终键集合恰为 `[新镜像, ai-session-id, ai-tab-page-id]`、旧镜像/草稿/业务键消失、localStorage 保留、原页签现场与锁完好、普通刷新保留哨兵键、零未捕获错误。
- 证据可移植：我复核实施副本的 `src/stores/ai.ts` 与新 spec 与主树**逐字节一致**，`diff -rq src` 无其它差异 ⇒ 探针针对的 `dist/` 就是主树代码。
- **native-duplicate-gesture 边界**：探针用"同源第二个页面 + 注入 A 的整份 sessionStorage + reload"复现，报告的免责声明（§6 边界 1）是诚实的。就代码读取的输入而言这是**状态等价**：新文档、继承的 sessionStorage、原文档持锁、无返回意向 —— 全是判据唯一依赖的量。仍未验证的只剩浏览器原生复制动作本身（Chrome 复制页签会克隆 sessionStorage 这一前提），以及：原生复制时若原页签正处于流式在途（inFlight 镜像）的表现、以及复制后 `ai-panel-expanded` 的 UI 观感。这三项属可接受残余，不影响判据/清空语义结论。
- 探针未覆盖：无 Web Locks 真机、慢重载真机（报告已声明只有 jsdom）、410。均为既有已知边界，非本批引入。
- 归因证据的弱点：报告 §6 的脚本名写作 `tab-clear-real-browser-probe.mjs`，实际产物是 `tab-clear-final-real-browser-probe.mjs`；68/68 聚焦跑与"4 个失败文件重跑 15/1 + truncated-panel 3/3"的说法没有归档日志（只有全量日志含本 spec 8/8 绿）。另外实施副本并非"只含本批改动"——它同时含另一代理未提交的 `src/utils/parts.ts`（与当前主树一致），所以全量 6 例失败只能证明**与本批页签路径无交集**（这 4 个失败 spec 均不安装 `navigator.locks`、不调用 `ensureTabOwnership`，已 grep 核实），不能单凭归档件断言"纯负载 flake"（M10）。主树串行全量正在另行验证，评审时（17:10）尚未出结论。

## 7. Findings

### Critical

无。

### Important

**I1｜探活 API 失败被当作"确认复制"，被本批升级为不可恢复清空** — `frontend/src/stores/ai.ts:464`（`}).catch(() => settle('occupied'))`）+ `:4636`

- `probeSessionLock` 把 `locks.request` 的 **promise reject** 归为 `occupied`；`resolveTabOwnership:557-571` 三次后即返回 `duplicate`；`ensureTabOwnership` 把它当"已确认"，进入 `clearDuplicatedTabStorage()` **整份清空**。
- 变更前同一次误判的代价只是"起个新会话"（可恢复、屏上可重读）；变更后是草稿/业务页签键**不可恢复**丢失。概率低（正常路径下 callback 不会 reject、未用 AbortSignal），但这是"确认"语义与"清空"后果之间唯一的薄弱环节。
- 建议二选一：`catch → 'unsupported'`（按无锁保守处理，不清）；或要求"至少一次真正收到 `null` 回调"才算 confirmed duplicate。1 行级加固。
- 若明确接受此风险，请在看板/报告里登记为已知取舍，避免后续把"Web Locks 失败"再当成"确认复制"。

### Minor

| # | 位置 | 问题 |
|---|---|---|
| M1 | `frontend/src/stores/ai.ts:275` | 模块级 RC-4 文档仍写复制页签"仍走『换新会话 + 清镜像』"，与本批"整份清空"矛盾（本批更新了 `:630-640`、`:1189`、`:4586`，漏了这一处）。 |
| M2 | `frontend/src/stores/ai.ts:630-641` vs `:464` | 新函数文档称调用点"已由 `resolveTabOwnership` 用 Web Locks **确认**"，但 occupied 含 API 异常（I1）；文档高估了"确认"的强度。 |
| M3 | `frontend/tests/duplicated-tab-clear.spec.ts:108,122` | 无"锁占 1–2 次后释放 ⇒ 重试得 owner 且不清"用例；8 条用例在把重试次数改成 1 的回归下仍会全绿（而该回归会在无返回意向的慢刷新上触发清空）。 |
| M4 | `frontend/tests/duplicated-tab-clear.spec.ts:231-252`（配 `src/stores/ai.ts:440`） | 模拟"刷新"时模块级 `heldLock/ownedSessionId` 未重置（无 `vi.resetModules()`），第二次判定走自持锁短路、并未真正重探；结论相同，但用例标题的保证强于实际覆盖。 |
| M5 | `frontend/src/stores/ai.ts:645-647` | `clear()` 抛错（隐私模式/存储禁用）→ 只清镜像；此时"新身份 + 继承草稿/业务键仍在"，与"全新工作区"口径不一致，且无测试覆盖该分支。报告 §8.1 已声明。 |
| M6 | `frontend/src/stores/ai.ts:644` | 整份 `clear()` 无键白名单：今天仓库内 `sessionStorage` 只有 AI 键与表单草稿（已 grep），但将来任何启动期写入的非 AI 键都会在这个 ~450ms 窗口里被无差别删除。批准口径要求"业务键不继承"，故接受，记录为未来约束。 |
| M7 | `frontend/src/stores/ai.ts:672-688` | `ai-panel-expanded` 被清出存储但内存态未同步 ⇒ 当前渲染与"下次刷新回默认"不一致（报告 §6 边界 4 已承认）。 |
| M8 | `frontend/src/stores/ai.ts:4741-4773` | 复制页签清空后仍可能在原页签关闭时接手原会话（既有 RC-4 让渡），但原会话的客户端草稿已被本批清掉 —— 会话回来了、草稿回不来。属"全新工作区"口径副作用，建议在口径文档里写一句。 |
| M9 | `frontend/src/stores/ai.ts:4629`（意向来自 `:514-523`） | 极窄窗口下复制页签会继承一条未消费的返回意向 ⇒ 真复制品走 `returning` 分支，不清、不换身份、继续与原页签共用 sessionId，直到下次刷新自愈。方向保守（无数据损失），但与"确认复制必清"的绝对表述有出入。 |
| M10 | 证据链 | 报告 §6 脚本名与实际产物不一致（缺 `-final`）；68/68 与失败文件重跑无归档日志；实施副本含另一代理未提交的 `src/utils/parts.ts`（与主树一致）⇒ 全量 6 例失败只能证明"与本批路径无交集"（已 grep 核实 4 个 spec 均不装 locks、不调 `ensureTabOwnership`），不能从归档件证明"纯负载 flake"；结论待主树串行全量。 |
| M11 | `frontend/src/stores/ai.ts:4598-4641` | 无 in-flight promise 记忆：同 tick 两次调用会各跑一次 450ms 探活（结果安全，见 §1.2）。目前只有一个挂载点，成本可忽略。 |
| M12 | `frontend/src/stores/ai.ts:465-466`（`unsupported` → `:560-562` 'unknown'） | "有 `navigator.locks` 但 `request` 同步抛错"会让角色停在 `unknown`（不换身份、不清），属另一条保守兜底，无测试。与 I1 同族但方向相反，记录以免将来被"顺手修正"。 |

## 8. 判定

- **Spec Compliance：PASS** —— 五项批准口径全部满足，且有源码级路径 + 真实浏览器证据支撑；清空顺序、只清一次、原页签/localStorage/服务端不受影响均有断言或真机核对。
- **Task Quality：PASS WITH ISSUES** —— 改动面极小（1 生产文件、1 测试文件、无依赖/配置），语义收敛在单一分支；主要问题是"确认"语义的强度（I1）与围绕**破坏性动作**的回归锁不足（M3/M5），以及若干文档/证据细节（M1/M10）。
- **Blockers：无**。建议：I1 以 1 行加固为佳（或在看板登记为已接受风险）；M1/M2 顺手改注释；M3 建议补一条"锁延迟释放 ⇒ 不清"的用例后再入库；其余可留作后续。
