<!-- 第 18 期补档入库 · 轮询治理一期（R-88/R-89）· 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\红队报告-轮询一期.md`（5435 B，sha256 `aeaa453164405797e72c21fa6c2cba8fb88b700008f330d725c31910fd72d753`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `e97df56e…782f016`（复核/红队据其出报告）；本期**入库锚** = `320b3c09…b541c7`（收口修后重锚，合流提交字节据其）。两 SHA 不可混用。

# 红队报告 · 轮询治理一期 · DS V4 Pro（K3 / DC-19）

## 结论
**带条件 PASS**（无 B 级推翻项）。核心口径——超时/abort 可判别、不静默清冲突、同 kind 回收、在途快照作废、通道⇄节奏绑定、close-gate、dirty 恰好 1 次、start/stop 幂等、同 tick 无双发——经静态审查 + 仓内 spec 复核未击穿；但 7 项 M 级必修（见下）补齐前不得判全量 PASS。

## 校验
红队卡 sha256=4FB98A3A…743E（Get-FileHash ✓）；施工卡/施工报告/锚定 diff 三件 sha256 全符 ✓；锚定 diff 与仓库 staged 树字节一致（4 子棒独立复核）⇒ 无夹带。**仓库状态与施工报告不符**：实测 HEAD=6c9c699（基线）、16 文件 staged 未提交；报告 §1/§9 称"终态 HEAD=d60c4a6、工作树干净"。三 commit（2030c24/ca84ec9/d60c4a6）仅对象库可达、无 ref 指向。红队卡所载"未提交态"为真（见 O1）。

## 编号发现
- **M1** hidden 期事件驱动刷新仍打后端：polling.ts:243-250 的 `invoke()` 无 paused/started 闸（只闸 `arm`）。触发：隐藏页签下订阅任务收 JOB_DONE/TASK_CHANGED→refreshNow→load。后果：与"hidden=恰好 0 次"及报告 §5 报数冲突（该 PASS 仅全静止/无帧取得，不可外推）。落点 polling.ts:243-250、task.ts:339-342、job-watch.ts:112/125/141。
- **M2** 冷进入 30s→4s 升级推迟一整个 tick：TaskCenterView.vue:355 `load` 未 await、:356 即 `startAutoRefresh`→空 items 武装 30s；load 写回 RUNNING 后无重排。后果：§4.1-3"30s 内恢复 4s"不可达，文案"有进行中作业每 4s"最长 30s 不成立；polling-controller.spec.ts:404-410 固化此缺陷为期望。
- **M3** 反复 onerror 重置 3s 兜底表→断线自愈饿死：job-watch.ts:130-136 每次 error 无条件 `emitChannel('polling')`→setIntervals→arm 重置起点。后果：error 节拍 ≲3s 时兜底 poll 一跳不发、假进度停摆【推断】；spec 仅锁单次 onerror。修：同态短路。
- **M4** 发布跟踪跨作业无作废→旧结局覆盖/清空新冲突：workspace.ts:452 `void watchPublishOutcome(job.id??0)` 无 signal；并发两条跟踪，旧作业晚到结局走 task.ts:534-536 清空新 FAILED 冲突 / 覆盖 issues。store 无 watchingJobId 闸；报告 §8 自认 abort 源未接线。落点 task.ts:503-546、workspace.ts:452。
- **M5** FAILED 结局无用户可见反馈：task.ts:538-546 只写 `publishConflict`（全仓无读取方），新增提示仅覆盖 abort/timeout/异常，独漏失败分支。落点 task.ts:538-546。
- **M6** task-events 池不接降级挂具：task-events.ts:109 subscribe 不传 options，死通道（404/断流）按 ~3s 无限重连占名额、即时性永久丢失；与 api/tasks.ts:117"降级只能由 onerror/readyState 驱动"矛盾。落点 task-events.ts:109、api/tasks.ts:134-150。
- **M7** 测试断言空隙：job-watch-channel.spec 的 getJob 落点零约束（只 pollCount 计数）→ 误用 taskId 串作业可假绿；applyJobProgress 的 jobId 守卫（task.ts:372-380）零执行；卸载出口只锁 EventSource 未锁定时器（无 getTimerCount）；事件→refreshNow 全程走非生产分支（spec 未 startAutoRefresh，controller=null）。

- **m1** syncSnapshot catch 缺 disposed 复检：job-watch.ts:173-175 onError 无闸（resolve 面 :164-166 有）；无 onError 消费者，潜伏。
- **m2** 上限 3 选键不对齐：task-events.ts:119 取 taskId 最小（最老/底部），淘汰顶部最新行，PENDING 占名额。
- **m3** load() 失败不 resync：task.ts:212 在 try 内、catch 不动 items→上一页订阅残留 ≤3。
- **m4** 300s 边界：jobs.ts:86-89 用 `>` 且只在 getJob 落地后判定→超时晚 ~intervalMs+RTT；abort 在 sleep 期间延迟捕获。
- **m5** ImportWizardView 白开一条 SSE：attachJob(:824)+beginWatchJob(:826) 双开，recycle 即关（开完即闭）。
- **m6** 回收表与自制 dispose 不同步：job-watch.ts:169-171 不通知注册表→has()/size() 失真。
- **m7** stop() 后 refreshNow 仍 invoke：polling.ts:243-250 invoke 无 started 闸（契约级，现网三调用方恰好挡住）。
- **m8** maxDurationMs 全仓零调用（死码 api/tasks.ts:140-150）；onError 真实调用点未接（ImportWizard.vue:854 / ExportWizard.vue:729）。

- **O1** 证据包 git 口径不一致（见"校验"）。
- **O2** 死字段：publishConflict（task.ts:106）、TaskEventTarget.jobId（task-events.ts:37）。
- **O3** isPaused() src 内 0 消费（polling.ts:21 自称供 UI/判据读取暂无落点）；AiPanel HITL 倒计时 hidden 冻结（报告已声明 §4.2-4 未实测）。

## DC-19 署名自证
| 字段 | 值 |
| --- | --- |
| 执行者 | K3 红队主棒 agent id = **main** |
| 会话 id | **session_d84cda7e-ec67-4cc0-9a98-7573f54ee0a5**（workspace wd_temp_38dcbf7ced2f） |
| 实际模型 | **DeepSeek/deepseek-v4-pro** |
| wire modelAlias 实证 | main/wire.jsonl 101 行；`grep -o '"modelAlias":"[^"]*"'`→7× 全部 `"DeepSeek/deepseek-v4-pro"` 无混杂；llm.request 样本 `{..."provider":"openai","model":"deepseek-v4-pro","modelAlias":"DeepSeek/deepseek-v4-pro","thinkingEffort":"high"...}`；profile.bind 同 modelAlias |

子智能体钉模实证：agent-574/575/576/577 各自 wire.jsonl `modelAlias` 计数 3/3/3/2，全部 `"DeepSeek/deepseek-flash"` 无混杂（显式钉模，非默认）。
