> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-578` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：11157 B / 7750 字符 / 83 行；行指纹覆盖率（仓内 + 落盘语料）43.9%
> - 类别：**过程核验**
> - 脱敏：路径 9 / 会话标识 6 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 派发与盯盘完成报告

### 1. 会话与模型（四门复核）

| 门 | 实证 |
|---|---|
| 新会话 id | `session_<KIMI_SESSION>-ed9f-4728-9b2f-00b95dd3aea9`，URL `app://renderer/sessions/session_<KIMI_SESSION>-…`，工作区 `state.json cwd=<LOCAL_WORK>` |
| pill（改后） | `按钮 name="K2.8 Preview · High" class="model-pill" rect=783,427 172x33` —— 改前继承自上一会话为 `DeepSeek V4 Pro · High`，本轮**显式改选**（picker 行名 `K2.8 Preview managed:kimi-code`，非 API-key 行 `kimi-for-coding Kimi`） |
| wordmark | `图形 name="Kimi Code" class="wordmark empty-logo" rect=491,215 304x65` 在 |
| composer | `组合框 name="消息输入框" class=ProseMirror rect=303,346 680x64` → 点 (643,378) → FOCUS=`ProseMirror ProseMirror-focused` 确认 |
| 空新会话 URL | 发送前 `文档 name="Kimi Code" value="app://renderer/"` |
| 权限 pill | `完全自动`（全程零权限弹窗） |

picker 行扫描复核（`<LOCAL_WORK>\_cu-pgov\s-picker-pgov.png`，rowscan.ps1 X0=685 X1=950 Y0=45 Y1=475）：band 中心 65.5/91.5/117.5/144.5/170/196/236.5/267/293/332.5/…；本轮窗口**非最大化**（1024x720, 1:1），UIA 行框中心系统性比墨迹带中心低 6.0px（所有行同偏差，行距同为 26px，属墨迹在 27px 行框内顶对齐 + UIA 文本框比墨迹高 ~6px，非行定位错误）。据此取两者中点 **(814,66)** 点击——同时落在 UIA 行框 58..85 与墨迹带 59..72 内、距 K3 行墨迹起点 18px。点后 pill 变 `K2.8 Preview · High` 且菜单关闭，选行确认。

### 2. 字节校验（派发头逐字节）

载荷来源即权威源：用 `<LOCAL_WORK>\_cu-pgov\extract-header.mjs` 从**我自己 turn.prompt**（`session_<KIMI_SESSION>/agents/agent-578/wire.jsonl` 的 `turn.prompt.input[0].text`）切出 `【K3 派单·轮询治理一期收口修】…—— K3` → `<LOCAL_WORK>\_cu-pgov\payload-pgov.txt`（783 B = 782 B + 1 LF；sha256 `be0e849e…f2c7e`；去尾 LF 后 sha256 `749ce10e…f8e2f`）。

- wire 回读：`agents/main/wire.jsonl` `turn.prompt`，`promptId=msg_01M4PX3TQEVRAZDQB8FMY9HV14`，`turnId=0`，`clientMetadata[0].display_text` = **782 B / 479 字符**，sha256 `749ce10ed49fd715d245a3b8bd62f95fc0512401906844f361f33ba7055f8e2f`
- 结论：`byteEqualIgnoringTrailingLF=true`、`firstDiff` 在 i=479 =「前缀全等、仅末尾长度差 1」，即**与派发头逐字节相同，唯一差异 = 末尾少 1 个 LF**（在卡允许的「末尾差 1 LF」内）。证据 `bytecheck-pgov.json`
- 发送方式：`type_text` 直贴（String.raw）+ 点 `发送 ↵`（element_index=216，非 Enter）；发后 UI 确认 URL 变为会话 URL、发送键变停止键、「请求中…」、**无「加入队列」**、K2.8 立即开工（读取了 1 个文件·运行了 1 条命令）

### 3. 报告与盯盘

- 报告：`<LOCAL_WORK>\polling-gov\收口修报告-轮询一期.md`，**7628 B / 54 行**，sha256 `4fd29972c8eaf56e008c42504414c7b58abb33c0898d8efeaf8e6a2cad45e92b`，mtime 10:24:19
- `lastTurnReason=completed`（state.updatedAt 10:25:04），`turn.prompt` 计数=1、queue=null（无追加轮次）
- 时间线（30s 轮询，`monitor-pgov2.log`）：09:54:53 发送 → 01:55:11Z 开盯（tick#1 wire 148 KB）→ 02:01 wire 546 KB（Get-FileHash 核卡 sha256）→ 02:06 agent-579 出现（DeepSeek V4 Flash 下沉）→ 02:11 wire 706 KB（vitest/yarn test/typecheck）→ **02:24:19 报告落盘 7628 B → 02:25:22 tick#38 `reason=completed` DONE**，总耗时 **30.2 分钟**（上限 90 分钟未触）。中途因后台任务默认 600s 超时被掐一次，已用 5400s 重启续盯（tick 连续，无盲区）。
- 无锁屏；CU 视图盯盘中期被外部切到父会话（K3），未强行切回（文件为准）；Overlay 仅 KimiCU 自身窗口，无遮挡。

### 4. 自证（agent id + wire modelAlias）

- 主棒：`agents/main/wire.jsonl` 1 821 303 B，`modelAlias` 计数 **70 × kimi-code/kimi-for-coding，无混杂**；首条 `llm.request`：`model=kimi-for-coding, modelAlias=kimi-code/kimi-for-coding, provider=openai, turnStep=0.1`
- 子棒：`agents/agent-579/wire.jsonl` 814 106 B → **31 × DeepSeek/deepseek-flash**（显式钉模）
- 本派发子智能体自身：`session_<KIMI_SESSION>/agents/agent-578`，**60 × DeepSeek/deepseek-flash**（我=DeepSeek V4 Flash，主棒 K2.8 的模型证据见上）
- 卡 sha256 独立复核：`收口修卡-轮询一期.md` = 4005 B，sha256 `6da08f23d5d8ce24c64cbfebf22366f78e7f8a727d97c6f490705c710892be47`，与派发头一致

### 5. 报告开头 15 行（照贴）

```
# 收口修报告 · 轮询治理一期 · K3 派单

日期 2026-10-12。依据：`收口修卡-轮询一期.md`（sha256 `6DA08F23…892BE47`，Get-FileHash 实测相符，未停手）+ 复核报告（带条件 PASS）+ 红队报告（7×M + 8×m）。主仓库 `<REPO_ROOT>`，施工前 HEAD=`6c9c699`（全哈希 `6c9c699aa680297ecf0a83ec39eeae8716b68431`），16 文件 staged 在制品态；**全程零 commit**，收口修全部改动保持未提交态。

## DC-19 署名自证

| 行 | 项 | 实证 |
| --- | --- | --- |
| 1 | 实际模型（主棒） | `kimi-code/kimi-for-coding` |
| 2 | 会话 id | `session_<KIMI_SESSION>-ed9f-4728-9b2f-00b95dd3aea9`（workspace `<WORKSPACE>`） |
| 3 | wire modelAlias（主棒） | `agents/main/wire.jsonl`（625 行）`grep -o '"modelAlias":"[^"]*"'` → **64 × `kimi-code/kimi-for-coding`，无混杂**；`profile.bind` 样本 `agentId":"main","modelAlias":"kimi-code/kimi-for-coding"` |
| 4 | wire modelAlias（子智能体钉模） | E2E 桩复跑下沉 `agent-579`：`agents/agent-579/wire.jsonl` `grep -o '"modelAlias":"[^"]*"'` → **25 × `DeepSeek/deepseek-flash`，无混杂**（显式钉模，非会话默认） |

## F1~F13 逐条处置（TDD：先 RED 后 GREEN，失败原文见各 spec 首次运行输出）
```

### 6. F1~F13 处置结论段（照贴，第 14–28 行）

```
## F1~F13 逐条处置（TDD：先 RED 后 GREEN，失败原文见各 spec 首次运行输出）

- **F1**（M1/m7）`polling.ts` invoke() 补双闸：`!started` 直接丢弃、`paused` 只置 dirty 不发请求；dirty 清除移入真正落地路径。新增断言 `paused-refreshnow-only-dirties`（hidden 三次 refreshNow→0 请求，回 visible 恰好 1 次补刷）、`stopped-refreshnow-no-invoke`。RED：`expected 3 to be +0` / `expected 3 to be 1`；GREEN 同文件 13/13。
- **F2**（M2）冷进入升级：`PollingController` 增 `resync()`（armed≠判定才重排，起点重置；一致则无操作），`stores/task.ts#load()` 写回 items 后调用。改写原固化缺陷的 `store-autorefresh-single-timer` 为 `store-autorefresh-cold-entry-upgrade`（30s 保底 tick 写回 RUNNING 后 4s 起跳，t=34000 恰为第 2 次，无双发，stop 归零）。RED：`expected "vi.fn()" to be called 2 times, but got 1 times`。
- **F3**（M3）`job-watch.ts#emitChannel` 同态短路：新增 `armedChannel`（初值 `polling`），同态时只通知不重排 arm 起点。新增 `same-state-onerror-short-circuit`（连续 onerror×3 于 t=0/1000/2000，3s 兜底仍在原节拍 t=3000/6000 发出）。RED：`expected [ 1, 2, 3 ] to deeply equal [ 3, 4, 5 ]`（F9 同批暴露）+ 计数不符。
- **F4**（M4）`stores/task.ts` 加模块级 `watchingJobId` 闸：入口占据闸位，`waitForJobFinal` 结局落地前 `watchingJobId !== jobId` 即返回（不 refreshCurrent/不碰 publishConflict）。新增 `stale-outcome-gated`（并发 9/10 两条跟踪，旧作业晚到 FAILED 不覆盖新冲突）。RED：`expected 9 to be 10`。
- **F5**（M5）FAILED 分支补 `notifyJobWatch('发布作业 #N 失败，请查看校验问题清单')` 单次提示（与 abort/timeout 同出口纪律），未重建渲染位。新增 `failed-outcome-notice-once`。RED：`expected "vi.fn()" to be called 1 times, but got 0 times`。
- **F6**（M6）`task-events.ts#subscribe` 接 `onerror` 挂具：onerror 且 `readyState !== OPEN` ⇒ 记入 `dead` 集合、close 并释放名额、`notify()`；`plan()` 排除 dead（该任务退回列表条件轮询兜底，sync 不再重订阅）。新增 `dead-channel-releases-slot`。RED：`expected false to be true`（死通道未关闭）。
- **F7**（M7）spec 加固四项：①`getjob-called-with-configured-jobid`（全部 getJob 调用参数=101）；②`applyjobprogress-jobid-guard`（jobId 999 不直更、10 直更，守卫真执行）；③`task-events-lifecycle` 卸载用例补 `vi.getTimerCount()).toBe(0)`；④三个 dirty 用例改走 `startAutoRefresh()` 生产分支（controller 非 null）。
- **F8**（m1）`syncSnapshot` catch 面补 `disposed` 复检（与 resolve 面对称）。新增 `onerror-not-fired-after-dispose`（在途 reject 于 dispose 后 ⇒ onError 不透传）。RED：同用例失败（onError 被调用）。
- **F9**（m2）订阅淘汰改 taskId **降序**取前 3（`plan()` sort `b-a`；列表 createdAt DESC/id DESC ⇒ 保最新行），T3① 断言同步改写（cap 用例改期望订阅 {5,4,3}（liveTaskIds 升序视图 `[3,4,5]`）、four-exits 改从最新行 5 发终态帧）；task-events.ts 文件头/TaskEventsPool 注释、task.ts、TaskCenterView.vue 注释随改。
- **F10**（m6）`job-watch.ts` 增 `onDisposed` 挂具（dispose 内调用、幂等一次）；`ImportWizardView.beginWatchJob` 接线 `onDisposed: () => jobWatchers.recycle(kind)`，自制终态 dispose 同步注销注册表。新增 `self-dispose-syncs-registry`。RED：`expected true to be false`（has('check') 未注销）。
- **F11**（m8）删 `api/tasks.ts` `maxDurationMs` 死码（接口字段 + setTimeout 包装 + 注释），grep 全仓零调用（除定义处）确认。
- **F12**（m3 复核）`hasActiveJob` 注释补「以 state.items 为准（后端过滤后等价承载 filterStatus 口径）」。
- **F13** 复跑 E2E 桩 + 报告补记：见下节。
```

报告其余关键结论（第 30–54 行摘要）：`yarn test` 19 spec/207 tests 全绿 exit 0（基线 197 + 新增 10）；`typecheck` exit 0；`build` exit 0；`git status backend/` 空、porcelain 仅 frontend/ 在制；红线文件 diff 零输出；E2E 桩复跑 **PASS=29 FAIL=0 GFSKIP=0**；F13 三项口径更正（证据实 41 件、终态 HEAD=d60c4a6 系施工期本地 commit、已 soft reset 回 `6c9c699`）；登记不修 6 项逐条列名（红队 m3/m4/m5、GLM m-1/m-2、两方 O 组）。

### 7. 交付物与纪律

- 本次产出全部落 `<LOCAL_WORK>\_cu-pgov\`：`dispatch-evidence-pgov2.json`（总证）、`payload-pgov.txt`、`bytecheck-pgov.json`、`monitor-pgov2.log/.mjs`、`extract-header.mjs`、`progress-pgov.mjs`、`s-picker-pgov.png`/`s-gates-pgov.png`/`s-typed-pgov.png`/`s-sent-pgov.png`
- 未对主仓库做任何 git 操作或文件改动（K2.8 的在制品态由它自己维护）；未触碰 `<LOCAL_WORK>\ai-example`、`<LOCAL_WORK>\ai-example-code\ai-example-main`；未读 `.env`
- 建议 K3 侧复核点：报告自报主棒 alias 计数 64（其取证时刻）vs 我终态计数 70（wire 收尾后仍在写），差值属取证时点不同；报告 sha256 声称与卡片哈希实测相符，我已独立复核一致
