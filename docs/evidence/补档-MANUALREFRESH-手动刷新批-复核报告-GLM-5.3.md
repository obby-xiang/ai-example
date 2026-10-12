<!-- 第 19 期补档入库 · 手动刷新批（MANUALREFRESH） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\复核报告-手动刷新批.md`（7645 B，sha256 `102252474ff9b4ae7d74fc5cdf303cc2122419a94133a8b168382bf921757117`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本期**审查锚** = `a2d9d708c8101fde11e4e2a434cd753c85ee193665c8b86322de4b451bb28fa7`（复核 / 红队据其出报告）；本期**入库锚** = `336f03f41769f3f7605aac405cb222b290374e10429e6cbdb38a7e0c4fdec4a3`（收口修后重锚，合流提交字节据其）。两 SHA **不可混用**。

# 复核报告 · 手动刷新批（GLM-5.3 独立复现）

- 署名（DC-19 实证）：实际运行模型 **GLM（Zhipu AI/glm-5.3，thinkingEffort=high）** · 会话 id **`session_6cf39cff-ce68-4bff-ad0c-3146d7136f58`** · wire 实证 = 本会话 `agents/main/wire.jsonl` `llm.request` 记录 `"model":"glm-5.3","modelAlias":"Zhipu AI/glm-5.3"`（亲读自证，非照抄卡头）。
- 双核验：复核卡 sha256 `93182697…7efea` ✓、diff 锚 `a2d9d708…28fa7` ✓，均与派发头一致。
- diff 锚完整性：patch 23 文件与 `git diff HEAD`（含 2 个未跟踪新 spec）**逐文件内容全等**（脚本比对，mismatch=NONE）；HEAD 仍 45b2c6a，零 commit。
- 纪律：仓库零源码写入（唯一暂存 = 复现申报偏差时的 `git add -A` → check-docs → `git reset`，已还原，工作树 23 文件不变）；未读 .env；构建产物（dist/、target/、esbuild TMP）属跑测正常产出并已清理。

## 一、五项门禁独立复现

| # | 门禁 | 复现结果 | 证据 |
|---|---|---|---|
| 1 | frontend vitest | **18 文件 / 202 用例全绿** ✓ | 与施工报告基数一致（207−24+19=202 复算吻合） |
| 2 | typecheck（vue-tsc --noEmit） | **绿**（EXIT=0）✓ | — |
| 3 | build（vite build） | **绿**（✓ built in 25s，EXIT=0）⚠ | 见 OBSERVATION-1：系统 Temp 下 esbuild 临时文件被外部占用，连败 5 次后换 TMP 干净通过 |
| 4 | check-docs 6/6 | **6/6** ✓ | 复现申报偏差 1：未 stage ⇒ ⑤ FAIL（5 处已删文件）；临时 `git add -A` ⇒ 6/6；`git reset` 还原 |
| 5 | backend mvn test | **442/442，0F 0E，BUILD SUCCESS**（Maven 3.9.16/JDK 21.0.12，65s）✓ | 用例数与基线一致 |

**门禁结论：五项全过。**

## 二、M1~M5 逐条核验

| 条 | 结论 | 证据锚点（工作树实读） |
|---|---|---|
| M1 任务中心纯手动 | **落实** | `task-events.ts` 已删（git status D）；全仓 grep `task-events\|startAutoRefresh\|hasActiveJob\|TASK_CHANGED` 仅剩注释提及，无活代码；`stores/task.ts:186` refreshNow 唯一拉取入口 + refreshPending 补发（:65/:173-179）；`TaskCenterView.vue:151-153` 头注释改写、挂载仅 load()、刷新按钮（:12）接 refreshNow；types/sse.ts:337 TaskEventType 仅剩 HEARTBEAT/JOB_PROGRESS/JOB_DONE |
| M2 polling 瘦身 | **落实** | `polling.ts:1-44` 单节奏形态：shouldRun/desiredIntervalMs/resync/isPaused/导出 CATCHUP_JITTER_MS 全删（:26 转内部常量）；保留 start/stop/refreshNow/setIntervals/visibility 纪律；现存消费方注释（:21-22）如实列 job-watch + AiPanel |
| M3 job-watch 收敛+看门狗 | **落实** | `job-watch.ts:44` FRAME_IDLE_WATCHDOG_MS=90_000 可注入（:63/:75）；复位源仅帧（:150）与 onopen（:190），polling 快照不复位（专例锁 spec :216）；触发降 3s（:126-129）、新帧回升（:157-163）；REALTIME_IDLE_MS=2^31-1 等价停表（:94-97）；头注释 :13-23 明示反转一期 D8 |
| M4 消灭 waitForJobFinal | **落实** | `api/jobs.ts` waitForJobFinal/JobWaitAbortedError 全删；`task.ts:343-410` watchPublishOutcome 复用 watchJob + 300s 独立墙钟（:71/:405，onFinal 即清 :391）+ `taskId:jobId` 注册表防双开（:77/:347-350）+ F4 闸与单次提示移植（:356/:417-439）；`workspace.ts:456-457` 持有 dispose、新一轮先收旧 |
| M5 后端摘 TASK_CHANGED | **落实** | `TaskSseService.java` 无 onTaskChanged/EventListener（grep 零命中）；`TaskChangedEvent.java` 已删；`TaskService` 无 publishTaskChanged/eventPublisher；端点与 JOB_PROGRESS/JOB_DONE 发布保留；`TaskSseServiceTest:161-167` 契约改用 JOB_PROGRESS 锁帧形态（mvn 5/5 绿） |

## 三、F1~F6 逐条核验

- **F1 AiPanel 倒计时兼容：过。** `AiPanel.vue:847-852` 现用 intervalMs/run/start/stop，全部在 S2 保留面内；注释 :848 同步改写。
- **F2 删面连带+refreshNow 保留：过。** 死类型、假文档（task.ts:8、TaskCenterView:151-153、api/tasks.ts:113）均改写；refreshNow/refreshPending 按 DSPro R5 保留（task.ts:186-192）。hasActiveJob 零活引用按卡删（残留仅注释，可接受）。
- **F3 看门狗仅帧/onopen 复位：过。** 代码 :150/:190 复位源仅二；`watchdog-not-reset-by-polling-snapshot` 专例（spec :216）真断"降级后 3s 快照不复位、不自回升"。
- **F4 300s 墙钟终态即清：过。** `wall-clock-cleared-on-final`（spec :188）真断"终态后再推 300s+ 不报超时"；注册表双开（spec :264）与 F4 闸（spec :234）均有专例。
- **F5 TaskSseServiceTest 连带删改：过。** :156-167 契约用例改写非删除，mvn 用例数不减（442），注释 :161 说明改写原因。
- **F6 看门狗可注入：过。** `frameIdleWatchdogMs` 选项（:63）+ 测试注入驱动（spec 用例全部小值驱动），90s 不必真等。

## 四、测试质量抽查

新增 spec 均真断真绿，非永真：task-center-view.spec 4 例（挂载恰 1 次 + **intervalSpy 断言 0 次周期表** + FakeEventSource 断言 0 实例 + 手动/取消路径）覆盖 E2E 口径①的单测化身；watch-publish-outcome.spec 9 例含超时出口、闸、双开、abort 非上抛；job-watch-channel.spec 看门狗 7 例含 F3 专例与 onopen 复位。被删 4 spec 的覆盖面：task-events（订阅池本体，随功能整体摘除，无幸存代码裸奔——grep 零活引用）；wait-job-final（其场景被 watch-publish-outcome.spec 等价接管，300s 出口/单次提示/abort 均有新例）。基数复算 207−24+19=202 ✓。

## 五、申报偏差判定

1. **check-docs ⑤ 临时 git add/reset：可接受。** 已独立复现全流程（FAIL→stage→6/6→reset→23 文件原样、HEAD 未动）。根因 = check-docs ⑤ 按 `git ls-files` 全量读盘，与"施工期未 commit"纪律的结构性冲突，施工方处置透明且可逆。**建议（minor）**：后续批次在 check-docs.mjs 内加"工作树已删文件跳过"或改用 `git ls-files --deduplicate` 口径，消除每批的临时 stage 手工操作。
2. **realtime 停表用 setIntervals 2^31-1：可接受。** `REALTIME_IDLE_MS=2_147_483_647`（job-watch.ts:97）是 setInterval 32 位安全上界（>则溢出回 ~1ms），注释如实说明；90s 看门狗远早于 24.8 天触发，该节奏永不抵达，行为等价停表且保住 refreshNow/可见性纪律（stop 会关死即时快照面）。备选（加 suspend/resume API）改动面更大，判定不须收口修。

## 六、自由发现

- **OBSERVATION-1（环境，非施工问题）**：vite build 在系统 Temp（<USER>\AppData\Local\Temp）连败 5 次，均为 esbuild 中间文件 "remove … Access is denied"（外部占用，非本批代码引入；换 TMP 干净通过）。提交官/后续批次跑 build 若遇同错，换 TMP 或清 esbuild-* 即可。
- **OBSERVATION-2（表述精度）**：施工报告称 E2E 桩"全静止 120s 恰好 1 次（挂载那次）"，与验收口径"恰好 0 次"系同一事实的两种计数口径（0=静止期增量，1=含挂载首次），卡片口径无冲突，知悉即可。
- **OBSERVATION-3（残留注释）**：`types/api.ts:126`、`polling.ts:4`、`polling-predicate.spec.ts:2` 提及已摘除机制属"历史背景说明"性质（说明为何删除），非遗留假文档，判定可留。

## 总结论

**PASS**（无条件）。五项门禁独立复现全绿；M1~M5/F1~F6 逐条落实且证据齐；测试真断真绿、基数复算吻合；两处申报偏差均判定可接受（附 1 条 minor 建议与 3 条 OBSERVATION，均不构成收口条件）。本批可交提交官。
