<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\收口修报告-轮询一期.md`（7628 B，sha256 `4fd29972c8eaf56e008c42504414c7b58abb33c0898d8efeaf8e6a2cad45e92b`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 收口修报告 · 轮询治理一期 · K3 派单

日期 2026-10-12。依据：`收口修卡-轮询一期.md`（sha256 `6DA08F23…892BE47`，Get-FileHash 实测相符，未停手）+ 复核报告（带条件 PASS）+ 红队报告（7×M + 8×m）。主仓库 `<MAIN_REPO>`，施工前 HEAD=`6c9c699`（全哈希 `6c9c699aa680297ecf0a83ec39eeae8716b68431`），16 文件 staged 在制品态；**全程零 commit**，收口修全部改动保持未提交态。

## DC-19 署名自证

| 行 | 项 | 实证 |
| --- | --- | --- |
| 1 | 实际模型（主棒） | `kimi-code/kimi-for-coding` |
| 2 | 会话 id | `session_6d0d9b9d-ed9f-4728-9b2f-00b95dd3aea9`（workspace `wd_temp_38dcbf7ced2f`） |
| 3 | wire modelAlias（主棒） | `agents/main/wire.jsonl`（625 行）`grep -o '"modelAlias":"[^"]*"'` → **64 × `kimi-code/kimi-for-coding`，无混杂**；`profile.bind` 样本 `agentId":"main","modelAlias":"kimi-code/kimi-for-coding"` |
| 4 | wire modelAlias（子智能体钉模） | E2E 桩复跑下沉 `agent-579`：`agents/agent-579/wire.jsonl` `grep -o '"modelAlias":"[^"]*"'` → **25 × `DeepSeek/deepseek-flash`，无混杂**（显式钉模，非会话默认） |

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

## 门禁输出摘要（全部亲跑）

- `yarn test`（vitest run）：**19 spec / 207 tests 全 passed，exit 0**（基线 197 + 新增 10：F1×2、F3、F6、F7①、F7②、F8、F10、F4、F5；F2 为改写固化缺陷用例）。两次全量复跑一致。
- `yarn typecheck`（vue-tsc --noEmit）：exit 0（首次暴露 spec 未用变量 TS6133 一处，已修后复跑通过）。
- `yarn build`：exit 0（built in 25.08s，dist 正常）。
- `git status --short backend/`：**空**；`git status --porcelain` 仅 frontend/ 路径（MM/AM = staged+工作树在制，未提交）。
- 红线零触碰：`git diff HEAD -- api/sse.ts stores/ai.ts SpreadGrid.vue excel.ts frontend-tools.ts backend/` 零输出。
- **E2E 桩复跑**（下沉 agent-579，配方=仓内 ci.yml e2e job 等价复刻 + `scripts/verify-e2e.ps1 -EnableGf6`，`E2E_STUB_MODE=1`）：**PASS=29 FAIL=0 GFSKIP=0，退出码 0**（Q8 项 PASS=2；后端 boot.log ERROR=0）⇒ FAIL=0 ∧ GFSKIP=0 ∧ PASS≥28（基线）全部满足。工作产物全在仓库外 `<USER>\AppData\Local\Temp\e2e-b4r2-stub\`；跑前跑后 HEAD 均 `6c9c699`。

## F13 报告补记（三项口径更正）

1. 证据目录实 **41 件**（原施工报告写 43 系笔误）。
2. 「终态 HEAD=`d60c4a6`」系施工期本地 commit 口径（提交链 `2030c24→ca84ec9→d60c4a6`）。
3. 该提交链已经 K3 裁决 **soft reset 回 `6c9c699`**，恢复未提交在制品态（三 commit 仅存 reflog 留痕）。

## 登记不修（观察项，逐条列名）

- 红队 m3：`load()` 失败下 tick 自愈不 resync（catch 不动 items，上一页订阅残留 ≤3）。
- 红队 m4：`jobs.ts` 300s 边界用 `>` 且 getJob 落地后判定，超时晚 ~intervalMs+RTT；abort 在 sleep 期间延迟捕获。
- 红队 m5：`ImportWizardView` attachJob+beginWatchJob 双开一条 SSE（recycle 即关，开完即闭）。
- GLM m-1：D14 运行时三项请求数基线未实测（报告已如实声明静态替代口径）。
- GLM m-2：施工卡 §4.3-7 B1 判死链反查等运行时验收未执行（语义由 spec 锁定）。
- 两方 O 级全组（O1 证据包 git 口径、O2 wait-job-final abort 真时钟轻 flaky 面、O3 workspace.ts:452 abort 触发源未接（E6 红线）、O4 DevTools/HAR 留档以 WebBridge 抓包替代）。

—— K3 收口修棒 · 2026-10-12 · 结论：F1~F13 全落地，门禁全绿，E2E 桩达标，未提交在制品态交付。
