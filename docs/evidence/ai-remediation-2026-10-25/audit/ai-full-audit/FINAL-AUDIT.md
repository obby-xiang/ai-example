> 来源=<仓外证据>/ai-full-audit/FINAL-AUDIT.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FINAL-AUDIT｜AI 全链路只读验收（终稿：A–J · G1–G5 · P1–P5 · P2-gates · FA1–FA6 全量收尾）

> **快照**：`<仓外证据>/ai-full-audit/tree` = base `ae883fbae52be36e45a635ee276cf8298ac24bd2` + 未提交 `working.patch`（SHA `6c88b47d…dab955`）。本文件所有"已证实"都指**该快照**。
> **输入**：A–J（10 份域报告）、`L`/`M`/`N` 对抗复核、G1 47/47、G2 51/51、G3 61/61、G4 63/63、G5 46/46、`P2-gates-full`（ConfirmGate/SpToolCallingManager/LeaseService/AiSessionPurgeService 共 6,034 行）、P1（RunStore/ResilientChatService/SseChatEmitter 共 7,380 行）、P3（AiPanel 3,700 + ai.ts 5,523）、P4（sheet-tools 2,918 + ai-dom-tools 1,718 + markdown 1,100）、P5（KbService/AiConfigService/AiConfigWriter/GenerativeFormRules/AiExecutorConfig/HttpKbSource 共 4,771 行）、**FA1–FA6（54 文件 / 24,372 行，6 个互斥分片作业，全部声明逐行读完）**。
> **性质**：只读合成。未改任何代码/测试/配置/工作流，未触发 CI，未启动服务，未运行任何测试，未读写任何密钥值。唯一写入物是本文件。
> **口径**：**不作绝对保证**。"已证实"= 代码路径或运行日志可证（可能未在本机复现）；"条件"= 需特定交错/配置/拓扑。**快照结论与 main 上的候选修复严格分节**（§2.2），不把"代码已改"写成"已验证"。
> **本稿相对前稿的变化**：① 计数 224 → **223**（FA 对抗裁定：撤回 1、合并 1、降级 4；两次失败归因新增 1、升级 2）；② §8.2 的"54 文件已分配未完成"**撤销**，AI 清单已获全文件覆盖证明，六份报告**已归档**；③ 验证模式 **V7** 保留，其中 22 条经 `FA-adversarial` 复推升 **V1**；④ main 候选 `34fd8f4…` 的两条 run 均 **COMPLETED failure（165/1、15/1），两处根因均已定**（§0.5/§7.1），**修复在隔离副本中、未合并未验证**，`@real` 未触发；⑤ 新增 §2.4 收窄表（8 条）、§0.5 归因小节与 §10.3 裁定全表；⑥ **删除全部"待复核/待裁定/根因进行中"占位**。

## 0. 本轮增量与已应用的更正

### 0.1 覆盖状态（**删除前稿的"未覆盖/未完成"表述**）

| 前稿说法 | 现状（终稿） |
|---|---|
| "16 个大文件只读过关键段" | **已全部逐行**：RunStore 2,822（P1）、ResilientChatService 2,352（P1）、SseChatEmitter 2,206（P1）、ConfirmGate 1,937（P2）、SpToolCallingManager 1,940（P2）、LeaseService 1,146（P2）、AiSessionPurgeService 1,011（P2）、KbService 943（P5）、AiConfigService 1,085（P5）、AiConfigWriter 942（P5）、GenerativeFormRules 1,486（P5）、AiPanel.vue 3,700（P3）、ai.ts 5,523（P3）、sheet-tools 2,918（P4）、ai-dom-tools 1,718（P4）、markdown.ts 1,100（P4） |
| "2 个单面文件（AiExecutorConfig / HttpKbSource）只审一面" | **已全读**（P5，103 + 212 行） |
| "ai-dom-tools / sheet-tools 内部未审" | **已全读**（P4，5736/5736 行） |
| "I 域缺失" | I 已恢复并按链覆盖（C1–C11） |
| **"§8.2 的 54 个文件已分配、尚未完成"** | **已完成并归档**：FA1 3,888 + FA2 3,578 + FA3 4,471 + FA4 4,022 + FA5 3,807 + FA6 4,606 = **24,372/24,372 行**；六份报告已落盘于 `final-gap/FA1-report.md … FA6-report.md`，覆盖证明见 §8.2 |
| （新增）"已建立清单内的全文件覆盖" | **成立**：AI 生产文件 196 中 **150 个逐行全读**（G1–G4 78 + P1–P5 18 + FA1–FA6 54），前端 AI 文件 42 个逐行全读；剩余文件的边界见 §8.3 |

**仍保留的边界**（不是"未覆盖"，而是**读不出来**）：真实 Redis Cluster、真实 PG、≥3 JVM 真时序、真机/真模型、跨机存储、门禁工程、以及 FA 各报告自述的运行期未决分支。清单见 §8.3。

### 0.2 已应用的更正（P/N 轮，全部保留）

| # | 来源 | 更正 | 落地 |
|---|---|---|---|
| E-1 | 指正 | `ResumeService:1414` 引用错误（全文 1,260 行）；整窗写入器是 **`ResilientChatService:1414`**（`persistMemory:1463`） | §5 RT-05 |
| E-2 | L-C1/N-C1 | G1-12-4"业务异常→信封无证据"是**假缺口**（`ToolFrameSequenceConformanceTest:599`、`AiToolsInvocationTest:214` 已覆盖） | 移入 §2.3 已否证 |
| E-3 | N-C2 | 元数据缺失 fail-open（PK-11）由 S2 **降为 S3**，定性"缺启动不变量"而非"已存在越权"；37 个 `@Tool` 均直接声明，无现发实例 | §5 PK-11 |
| E-4 | N-C3 | 压缩状态 RMW 竞态被 **`SessionGate` 串行化**，仅门 TTL 失效/接管时成立 → 条件风险；"同轮归档键覆盖"独立成立（低） | §5 MD-18 |
| E-5 | N-C4 | `SecurityEvents` 进程级计数、`actor=local-user` 属**已裁决演示边界**，不计缺陷 | §5 RT-16、BZ-24 标记 D |
| E-6 | N-C5 | 反馈 IDOR 在单用户边界外成立；边界内只剩健壮性（超长 id→500、并发唯一键→500、`rating=0` 按全局 messageId 删） | §5 ID-07、MD-14 |
| E-7 | N-C6 | park 取消（RT-04）"整轮挂死"改为"**前台页签本地已收敛**；归档零终帧，受害面是其它页签/刷新后客户端/非浏览器消费者" | §5 RT-04 |
| E-8 | N-C7 | MD-03 不是死分支：默认不可达，但 `0` 可达；定性为**显示默认值问题** | §5 MD-03 |
| E-9 | N-C8 | G1-02（双通道截断）S2→**S3（潜在）**；G1-01（两个 returned 数字）S3→**S4**；`KbService.stop()` 自旋 High→**S3（窄可达）** | §5 PK-16、PK-17、BZ-20 |
| E-10 | N-C9 | 种子 provider 信任翻转"永久"改为"**直到 `allow-private-endpoints=true` 或重新播种**"；方向是 fail-closed，非 SSRF | §5 BZ-23 |
| E-11 | N-C10 | `ToolScopeTagSpaceGuard` 下限 26 vs 实际 37 → **确认**（CI-14） | §5 CI-14 |
| E-12 | P1-C1 | RT-01 补后果：只读门丢掉的**终帧是永久缺失**（新 emitter `archivedTerminal=false` ⇒ 重挂永不收尾），不需"进程已死" | §5 RT-01 |
| E-13 | P1-C2 | PK-03 写点**不止两处**：另有 `saveSuspended`、`tryClaimExecution`、`cancelPendings`（后者不写任何 WAL） | §5 PK-03 |
| E-14 | P1-C3/N5 | RT-12 扩写：锁内是**每帧 3–4 次 Redis 往返**（INCR/EVAL/XADD/PUBLISH），不止退订 | §5 RT-12 |
| E-15 | P1-C4/N10 | RT-11 补 park 期不适用：park 模式无续租线程，"无限续命"只在有人读 `/runs/current`、`/events` 时成立 | §5 RT-11 |
| E-16 | P2-C1 | `AiSessionPurgeService` 类注顺序与代码相反（pending 早于枚举、会话键早于附件） | §5 RT-28 备注 |
| E-17 | P2-C2 | `LeaseService.release` **不校验 `created`**，"无释放权"只靠调用点自律 | §5 RT-29 |
| E-18 | P2-G3 | A 域"触发池饱和 + 15 次退避"**不成立**：`EXECUTOR_SATURATED/DISPATCH_FAILED` 直接放弃，15 次退避只在 `ResumeService` 内层 | §5 PK-19 |
| E-19 | P5-C01 | BZ-15 行号漂移改为 `HttpKbSource:62-65,115-125`（`37-40/71-73` 指向错误） | §5 BZ-15 |
| E-20 | P5-C02 | BZ-20 补终局性：`stop()` 关 analyzer 且与 `refreshNow` 不互斥 ⇒ 停机后重建**永不可能成功** | §5 BZ-20 |
| E-21 | P5-C03 | `lowConfidence` 在截断后判定 ⇒ `top_k<=2` 时**恒判 LOW**（同一机制既虚高也虚低） | §5 BZ-18 |
| E-22 | P5-C04 | 配置 3 查询撕裂窗口只在**单次 load 内**（`refreshLock` 内），不跨 load | §5 MD-20 |
| E-23 | P5-C05 | `AiExecutorConfig` 看门狗实为 **2 条线程**，javadoc 写"单条" → 第 4 处文档漂移 | §5 MD-20 |
| E-24 | P5-R01..04 | 四条疑似被**否证**：`evictAll` 单实例（已被版本闸解决）、`nanoTime` 撞名、`createProvider` 缺 baseUrl（400 非 500）、播种失败致启动失败（两者都不抛） | §2.3 |
| E-25 | P2-§4 | 八条分支**复核为非缺陷**（latch 段无可抛点、预检不触发首停、kind 先于 PENDING 是刻意、retry 不重装 guard 仍生效等） | §2.3 |
| E-26 | P-adversarial | 22 条新发现经对抗复核升 V1；5 条 S2→S3（FE-21/22/23/34、RT-23）、1 条 S3→S4（BZ-29）；FE-24 判「未验证」；BZ-29「只能手工改库」被否证；A 域「15 次退避」再确认不成立 | §5/§9.3/§10.1 |

### 0.3 本轮（FA1–FA6 全量收尾）的更正

| # | 来源 | 更正 | 落地 |
|---|---|---|---|
| F-1 | FA1-CORR-2 | PK-17 的 notice↔`returnedChars` 差值**恒 ≈ 标记长度**（`notice` 用 `kept.length()`，信封用 `visited.length()`）；测试只断言 `returnedChars == text.length()`，**从不**断言 notice 数字 | PK-17 行注 |
| F-2 | FA1-CORR-1 | PK-17 子句"`ResultViews` 未消毒"**部分否证** → 见 R-13（值/列/options 已消毒，仅 `id`/`meta.source` 未消毒；`title` 命禁形静默为 null） | §2.3 R-13、PK-17 行注 |
| F-3 | FA2-§4.1 | PK-16 覆盖面偏窄：被覆盖的不止 `noticeCode`，还有 `originalChars`（用**入帧后**值重算，双重截断时"原始长度"偏小）、`returnedChars`、`strategy`（可能被整键 remove） | PK-16 行 |
| F-4 | FA2-§4.2 | RT-09 成因更精确：`fallbackSeq` 种子是**已落档末号**（`RedisFrameStore:742`），撞号常见形态是"与另一实例**在飞未落档**的号相同"；修复点应在共享号源而非落档去重 | RT-09 行 |
| F-5 | FA4-§3.2 | **BZ-26 的前端"第二故障点"被否证**（`FormRenderer` 把非有限值折 `undefined`，NFE 只在服务端） | §2.3 R-14 |
| F-6 | FA5-§2 | FE-30 的**暴露面收窄**：`readableValue` 对对象一律"（复杂值）"、路径→文件名，实际暴露只在"顶层标量 + 键名不命中正则" | §2.4 |
| F-7 | FA5-§2 | FE-32 的 `allowSteps` **有两份同名实现**：`WorkspaceAction.allowSteps`（`frontend-tools.ts:722,988`）是**活闸**；仅 `AiRegion.allowSteps`（`ai-regions.ts:50`，唯一读者 `ai-dom-tools.ts:981`）零注册 ⇒ 死闸。**不得**把前者当死码一并删 | FE-32 行注 |
| F-8 | FA4-§3.2 | FE-16 **只适用导入向导**：导出侧有 `resetTaskScopedState` + `stopJobWatchForTaskSwitch` 且在 `applyTaskBinding` 首行调用 ⇒ 不比照外扩；导出侧残留的是"代次缺失"（FE-54） | FE-16 行注 |
| F-9 | FA4-§3.2 | I-08 机理修正：`gridRefs` 不是"随卸载被删"，跨任务字节来自 `buildXlsx`/`loadGrid` **解析时实时读 `taskId`** 且无代次 | FE-54 行注 |
| F-10 | FA2-§5 | 配置绑定复核**未发现拼写漂移/配置了但不绑定的键**；`app.ai.*` 20 子段与 `AiProperties` 一一对应；`Kb.Http.authEnv` 只声明变量名，未读取其值 | §3.4 行注 |
| F-11 | FA6-§1 | `types/*` 的类型面**无自相矛盾**；请求级 `modelId/params` 确已退场（仅保留后端能力镜像） | MD-10 行注 |
| F-12 | 本文件 | 前稿 §8.2 的"`ListTasksResultViewProvider` 约 110 行"实为 **119 行** | §8.2 |
| F-13 | FA3-§3 | **BZ-01 新增两个前端可达入口**（FE-48 空模板覆盖、FE-49 零暂存仍可发布）与两个不可逆后果；后端根因表述不变 | BZ-01 行注 |
| F-14 | FA4-§3.3 | **I-09（失败回执仍显示"工作区已同步"）在 §5 无对应行** ⇒ 补登 FE-61；I-10 并入 FE-15；I-08 并入 FE-54 | §5、§2.4 |
| F-15 | FA5-§2 | FE-33 的**第二层**在 `frontend-tools.ts` 同参去重（`ui_snapshot` 命中即直返"动作前快照"），与 `ai-dom-tools` 快照缓存**不同代码、不同修复点** | FE-33 行注 |
| F-16 | FA5/FA6 | 发布冲突数的"共 N 处"失真由 FA5（模型面 + `resultview.total` 契约）与 FA6（页面面 + WARNING 误命名）**同根**，合并为 FE-62，不重复计数 | FE-62 |
| F-17 | FA1-CORR-4/5 | CI-14 的"下限 26 vs 37"与 PK-11 的"metaMap 只扫 `getDeclaredMethods()`"均**逐行确认**；补充 `forContext` 在空 scope 数组上与 `matchesContext` 分叉（当前 0 实例） | CI-14、PK-11、§8.3 |
| F-18 | FA2-§2.3 | `SessionGate` 的 F 域"全读"成立，但"穷尽性"不成立：**续期失败静默降级**（RT-34）与 **`Busy.runId()` 回退**（RT-35）两条路 F 域未记 | RT-34/RT-35 |

### 0.4 FA 对抗裁定已并入（终局）

`FA-adversarial.md` 已对 FA1–FA6 的**全部 S1/S2/Important 与会影响验收的测试真实性断言**独立复推（静态、未构建未运行）。本轮据此执行的更正分六类：

- **否证 1 条**：FA3-N2（`ui_event.goto_step`）⇒ 原 FE-45 **整条撤回**，进 §2.3 R-22（编号留空）。
- **降级 4 条**：FA3-N1 S1→**S2**（报告建议 S1，被否决）、FA2-N1 S2→**S3**、FA3-N7 S2→**S3**、FA3-N4 S2→**S3**。
- **合并 1 组**：FA4-03 与 FA3-N3 为同一机制（陈旧作业引用 ⇒ 假成功回执）⇒ 归入 FE-46，FE-52 删除（编号留空）。
- **证据升级 22 条**：从 V7 升 **V1**（见 §10.3 全表）。
- **收窄 4 条**：FE-16（前提）、MD-21（拆时序键/配置卫生）、BZ-35（PARTLY→条件风险）、ID-15（演示边界定性）。
- **测试真实性新增 2 条**：`ResumeDispatchRetryTest` 从未覆盖外层 `RejectedExecutionException`；导入向导写回路径**没有任何挂载视图的测试**。

**没有任何裁定处于待办状态**；本文件不再有"待 agent-111""待控制器填"的占位。

### 0.5 两次失败的归因已完成（本轮新增事实）

两份独立归因报告（`<仓外证据>/cluster3-34fd8f4-analysis.md`、`<仓外证据>/main-ci-34fd8f4-analysis.md`）已完成。结论：**两条失败都是产品缺陷，各自独立**；不是 harness、不是断言过严、不是外部取消或环境极限。

| run | 唯一失败 | 根因（已定） |
|---|---|---|
| `40182796512`（cluster-3node，15 PASS / 1 FAIL） | `suspend-cross-instance`（SSE 120s 超时） | **① 主因：种子写路径不发布配置版本**——`AiConfigSeeder` 只对**本实例** `reload()`，既不 bump `ai_setting.config.version` 也不 `INCR ai:cfg:ver`；B/C 的首次 `current()` 早于种子提交（差 23ms / 230ms）⇒ 快照永久停在 version=1 的**空 provider 表** ⇒ `effectiveProviders` 合成 `id=null` 的 env 行（`/health` 仍报可用）⇒ 续跑走**精确绑定** `resolveChatTarget(1,"ci-stub")` 落空 ⇒ **假 `PROVIDER_GONE`**。反证：同一 `providerId=1 / ci-stub` 在 182s 后被 A 正常续跑并执行成功。**② 暴露面：拒绝不落终态**——`AiController.events()` 的自愈派发被拒只 `log.debug`，随即返回 200 并保留 emitter（`NO_TRANSPORT_DEADLINE`），`ConfirmGate` 对应分支只 INFO ⇒ 无终帧、快照不落 `FAILED`、客户端无界挂起 |
| `40182795560`（ci.yml，165 PASS / 1 FAIL） | `AIE2E-T-13`（`readCalls 0→0`） | **park 化把结算挪出内联批循环**：`SheetAudit.record` 的唯一调用点在 `SpToolCallingManager:665`（内联循环的 `tool_call_end` 之后），而 `resolvePending` 的**复用分支**只 `return text` ⇒ 既不发 `tool_call_end` 也不记账。红跑量化：`tool_call_start` 97 / `tool_call_end` 37，**64 个 park 过的工具 64 个都丢了 end**（绿跑 101/100）；同一用例在 `2ed8f95` 是绿的（当时是线程内等待，end 带 `dataClass=D1`）⇒ **回归**。影响面：9 个表格工具全为 FRONTEND，`sheet.counters` 8 项恒 0、8 个采样字段永久缺席、D2 `sheet_sample` 台账不落 |

**修复状态**：两处修复**正在隔离副本中进行，尚未合并、尚未在任何 CI 中验证**；`@real` 仍**未触发**。因此本文件**不把任何一条标为"已修"**，`§2.2` 的 LATER-CANDIDATE 口径不变。

**证据与快照的边界**：两次 run 跑的都是 candidate `34fd8f4`，不是本文件所审的快照补丁。就代码路径而言：种子不发布版本（`AiConfigSeeder:99,106`）与结算只在内联循环记账（`SheetAudit.record` 唯一调用点 `SpToolCallingManager:665`，复用分支 `:1816-1847`）在**快照上同样成立**，故 MD-09/PK-06 仍按快照条目记录、用这两次 run 作**外部确认证据**；而"自愈派发被拒后不收流"是**候选新增分支**（快照的 `events()` 在该状态下走 `completeSubscriptions()` 收流，不复现挂起），故新条目按 LATER-CANDIDATE 记，不计入快照缺陷。

---

## 1. 执行裁决

**一句话**：设计与代码质量高于交付证据质量。协议/状态机/租约/fencing/前端段状态机在**单实例 + Redis 正常**下自洽，且有能变红的用例；但在任务前提（Redis Cluster + 共享 PG/文件 + ≥3 JVM 无粘滞 + 多用户）下，**没有任何一条端到端保证成立**。main 候选 `34fd8f4…` 的两条 run 均已 **COMPLETED failure**，各只剩 **1** 个失败用例，且**两处根因都已定**（§7.1-D/E）：跨实例那条是"种子不发布配置版本 ⇒ 空快照 ⇒ 假 `PROVIDER_GONE`"叠加"拒绝不落终态"；CI 那条是"park 化后结算不发 `tool_call_end`/不记账"。二者是**独立的产品缺陷**。修复**正在隔离副本中进行，未合并、未验证**，`@real` 仍未触发，因此"已修"仍不成立；本文件记录的 223 条缺陷**没有一条由本次审计修复**。全文件覆盖已建立（54/54 文件、24,372/24,372 行），但"覆盖"不等于"无更多缺陷"，也不等于"已验证"。

**能成立的部分（带条件）**
1. 单实例：帧协议（seq 单调、终帧闸门、差量回放）、决策幂等、工具认领、租约 token 精确释放、销毁键族隔离、单编码下载字节一致（补丁后）。
2. 跨实例**读**面（历史分页、幂等决策、配置热更新、会话销毁、Redis 整体停机降级）在**单节点 Redis + H2 TCP** 上真跑过（`40174291244` 五项 PASS）。
3. 无粘滞路由**已实现且两项 PASS**（`proxy-no-sticky`、`same-tcp-across-abc`）；**但负载均衡顺序未断言**（8–12/30 容差）。
4. 前端：段投影/幂等/终帧闸门/多卡串行（恢复路径）/表格预览身份闸/发布 fail-closed 有真代码与单测支撑。
5. 工具契约层（FA1 全读）：披露↔执行双出口共用 `scope AND sheetAllows`、五道守卫（向导/表格/前端/截断/信封）在**形状校验**上 fail-closed，参数非法一律异常回填。
6. **main 候选的交付面**：ci.yml e2e 已跑完 165 条（1 失败）、cluster-3node 已跑完 16 条（1 失败），比快照期的"跑不完/4/4 红"实质改善；两处唯一失败**均已定因**且均为产品缺陷（§0.5/§7.1）。

**不能成立的部分**
1. **快照交付面红**：cluster-3node 4/4 failure = 5 条真产品红 + 1 假红 + 1 间歇（快照 `ae883fb`；main 候选已降至 1 条失败，见 §7.1）。
2. **门禁仍不完整**：快照 HEAD 的 PR e2e 停在 `H-01`（130 条只跑 17 条）；39 条 P2 不阻塞；纯前端改动不跑浏览器 e2e。main 候选虽跑完 165 条，但那 1 条红是真缺陷（`AIE2E-T-13` 断言合法），不是门禁噪声。
3. **前提层零证据**：真 Cluster × 产品 JVM = 0；真 PG = 0；真模型 1 次 9 条无复跑（**本轮 `@real` 未触发**）；多用户不可测。
4. **超出演示边界且补认证仍复发**：记忆键别名（ID-04、**ID-15 全局索引**）、`/runs` 全局枚举（ID-02）；密钥外发（ID-03）按 §5 限定目标范围后仍为 S1。
5. **前端契约层**：可见性/缓存/重复列/上传交接/`optionsFrom`/`step` 六类旧发现未修；本轮又加**导入写回链**（FE-16/46/47/48）、**导出保存链**（FE-50/51/53/57）、**数值保真**（FE-49）、**工作区时序**（FE-67/69）。
6. **作业侧可用性面**：建作业守卫 O(N) Redis 无快速降级（BZ-34，S3）、写侧分区不可区分（BZ-35，条件风险）、任务 SSE 扇出 k×预算（BZ-36）。

**最高优先级（按"用户可感知损害 × 证据强度"）**

| 序 | 问题 | 关键点 |
|---|---|---|
| 1 | **跨实例续跑被假 `PROVIDER_GONE` 拒绝 + 拒绝不落终态**（MD-09 + RT-39，已由 `40182796512` 证实） | 种子不发布版本 ⇒ B/C 快照空 ⇒ 精确绑定落空 ⇒ 120s 无帧；反证：同一 provider 182s 后被 A 正常续跑 |
| 2 | **park 化后结算丢 `tool_call_end` 与审计**（PK-06，已由 `40182795560` 证实） | 64/64 park 工具无 end；`sheet.counters` 8 项恒 0、8 个采样字段缺席、D2 台账不落；断言合法、非 harness |
| 3 | 决策 × park 退出丢批准（PK-01） | 200 + `wokeInProcessGate:true`，无执行；再点 409；main 有 latch 候选，仅"未观察到失败" |
| 4 | **上传件/导出件被空内容覆盖**（FE-48、FE-50） | `GET /files` 失败后无条件写回、或未加载标签页 `fields=[]` ⇒ PUT 覆盖服务端文件，界面报"已保存" |
| 5 | **导入向导换任务把 A 的数据写进 B**（FE-16） | 编辑区/上传态/作业位不重置，且 `loadEditorsNow` 对 `currentStep≠0` 的任务直接 return ⇒ 推进时按新 taskId 写回 |
| 6 | park 取消零终帧（RT-04） | 归档缺 `done`；前台页签本地收敛，其它客户端挂到看门狗 |
| 7 | 零条目假成功 + 任务锁死（BZ-01，+FE-48/FE-50 前端入口） | 四链 COMPLETED 零数据；EXPORT 还让任务永久 `TASK_FINALIZED` |
| 8 | 取消后仍执行不可逆写（PK-02） | 续跑路径跨重建/压缩的宽窗口 |
| 9 | 记忆键别名（ID-04 + ID-15） | 正常一轮即删他人记忆；`__ids__` 一次请求删全局索引（自愈，S3）；认证修不了 |
| 10 | **Excel 数值静默改写**（FE-49） | `007→7`、≥2^53 失精，写回即固化，全链按改后值落库 |
| 11 | **过滤条件丢失 ⇒ 全量导出**（BZ-31 + FE-51） | 空串值/重复字段被压平、`startExport` 不判落库结局；用户批准"按 X 过滤"，作业导出全部 |
| 12 | 前端写隐藏步骤控件 / 快照缓存不失效（FE-32/FE-33，含第二层） | 第 4 步可改第 1 步控件；动作后重拍恒陈旧 ⇒ 勾选被点回去 |
| 13 | park 状态未外置 / 卡永不公布（RT-22、PK-18） | 一次 Redis 命令失败即让轮次不可续跑、或让卡永不出现（UI 不可恢复） |
| 14 | 接管后记忆/历史被输家写、第三租约态仍出帧（RT-20、RT-21） | 刷新看到旧答案；归档可能出现第二个 `done` |
| 15 | **会话门静默降级 / 假"取门成功"**（RT-34、RT-35） | 续期失败只 WARN 且 `degraded=false`；诊断 GET 失败让 `Busy.runId()` 等于自己 ⇒ 同会话两个驱动 |
| 16 | **销毁完整性**（RT-36、RT-37） | 记忆写回可落在 purge 之后；staging 不在销毁面且 TTL=0 时永久残留 |
| 17 | **历史/展示重投影**（BZ-33、FE-33 第二层） | `/history` 每次重取实时库；同参去重返回动作前快照（测试把它钉成期望） |
| 18 | 重试重复写记忆（MD-01） | 失败轮持久可见，挤占 40 条窗口 |
| 19 | **工作区动作无串行化 / `restore_task` 丢类型**（FE-67、FE-69） | 同批并行打到导航前 taskId；IMPORT 任务被开成导出向导并改写 `taskType` |

---

## 2. 状态分层：快照 / main 候选 / 已否证

### 2.1 三层定义（本文件全文遵守）

| 层 | 含义 | 可否作为"缺陷"派工 | 可否说"已修" |
|---|---|---|---|
| **SNAPSHOT** | 在 `ae883fb + working.patch` 上可证的缺陷/风险 | 是 | 否 |
| **LATER-CANDIDATE** | main 上已有改动（按指挥清单），本地有测试/评审；**run 已 COMPLETED failure、根因已定**，修复在隔离副本中 | 仍按快照条目记录 | **否**（只能写"候选已 push、根因已定、修复未合并未验证"） |
| **REFUTED** | 经 L/M/N/P/FA 复核不成立或前提错误 | 否 | — |

### 2.2 main 上的候选修复（**只标状态，不判断实现是否充分**）

> 依据：指挥提供的清单 + 本轮文件清单。**main 已 push `34fd8f405d0a7c57cb2b7bae387d04249fce5ba1`；`40182795560`（ci.yml）与 `40182796512`（cluster-3node 三实例）均已 COMPLETED failure**（PASS=165/FAIL=1 与 PASS=15/FAIL=1，唯一失败项见 §7.1-D/E）。**两条 run 都不构成本快照的验证**；两处失败**根因已定**（§0.5、§7.1-D/E），针对性修复**正在隔离副本中进行、未合并、未在任何 CI 验证**；因此本表只记录"候选已 push、run 已跑完但未证明"，不写"已修"。具体 diff 以 main 为准，本表只做条目对账。

| # | main 候选修复 | 与本表条目的对应 | 状态 |
|---|---|---|---|
| L-1 | **latch 修复（PK-01）**：park 退出后决策不再被 `woke` 吞掉 | **PK-01** | 候选已 push；run COMPLETED failure（唯一失败 = `AIE2E-T-13`）；**PK-01 是否被覆盖无直接证据** |
| L-2 | **两处 resume `tool_start` 回填**（接管后 / 挂起批） | **RT-14**（接管重放）、**CI-02** 的 `suspend-kill-a`；可能影响 **PK-06** | 同上；`suspend-kill-a` 本轮未出现在失败清单 ⇒ **未观察到失败，不等于已验证修复** |
| L-3 | **每订阅者 wake / 自唤醒修复** | **RT-08**、**RT-14**；可能覆盖 **RT-27**（stageReplay 越过失败帧） | 同上 |
| L-4 | **迁移锁 + drain 恢复** | **RT-19**（Flyway 冷启动）、**RT-18**（draining） | 同上；`cold-start-3` 本轮未失败 ⇒ 未观察到失败 |
| L-5 | **job / file / readiness 修复** | **BZ-06**（条目残留）、**BZ-07**（单码字节）、**RT-18** | 同上；`shared-file-download`/`job-kill-a`/`readiness-removal` 本轮未失败 ⇒ 未观察到失败 |
| L-6 | **页签 clear + 锁失败加固** | **FE-01**（非安全上下文共用）、**FE-07**（降级后不重认领） | 同上；前端单测不在两条 run 内 ⇒ **无交付面证据** |
| L-7 | **探针提前 kill + 更强断言** | **CI-04**（假红修正）、**CI-07**（顺序/容差）；可能影响 **RT-14** 的可见性 | 同上；`suspend-cross-instance` 仍失败，但**已定因为产品缺陷**（§7.1-E），故本项对它的"假红修正"未生效或未覆盖该路径；"更强断言"范围本文件不猜 |
| L-8 | **JDBC 守卫** | **IN-01**（Cluster 测试前置：harness 的 `database`/`FLUSHDB`） | 同上；cluster run 仍用单节点 Redis（§7.1-E）⇒ **真 Cluster 仍未覆盖** |

**正确表述是**：**快照缺陷成立；main 有候选修复且已 push；两条 run 均已跑完、各剩 1 条失败，且两处根因都已定为产品缺陷（§0.5）；针对性修复在隔离副本中，未合并、未验证**。本文件不写"PK-01 已修"或"Flyway 已修"。另：**FA1–FA6 的 48 条新条目与本轮所有 P/N 条目均无候选修复**（§11-11）。

### 2.3 已否证 / 撤回（不再计权，22 条 + 1 子断言）

| # | 原说法 | 否证依据 |
|---|---|---|
| R-1 | "业务异常→信封无证据"（G1-12-4 缺口） | `ToolFrameSequenceConformanceTest:599`、`AiToolsInvocationTest:214` 已断言 `outcome=failed`（N-C1） |
| R-2 | A-F2b"表格预览先到卡不能取消" | 存在"放弃"入口（`abandonStalePreview`，按 toolCallId）；上传交接另属"取消错卡"（L-2b） |
| R-3 | "`evictAll()` 只清本实例 ⇒ 默认参数不跨实例" | 被 `AiProviderRegistry` 的 `config.version` 版本闸解决（P5-R01） |
| R-4 | "`build-<nanoTime>` 跨 JVM 撞名" | `nanoTime` 原点每 JVM 任意，概率可忽略（P5-R02） |
| R-5 | "`createProvider` 缺 baseUrl ⇒ 闸门 NPE ⇒ 500" | `AiEndpointGuard.normalise` 抛 invalid ⇒ 400（P5-R03） |
| R-6 | "KB 播种失败 ⇒ 应用起不来" | `seedIfAbsent` 全部 WARN+return false，`prepare` 无抛出（P5-R04） |
| R-7 | latch 已登记、`try` 未开始之间的异常泄漏 | 该段无可抛点（emit 全链路自吞/降级）（P2-§4） |
| R-8 | "预检不通过会触发首错即停" | `PRECHECK_FAILED/UNAVAILABLE` 在可重试集合，`haltsBatch` 不命中（P2-§4） |
| R-9 | "`retry()` 不重装 guard ⇒ 写回守卫失效" | `SessionPurgeGuard.isArmed` 同时看 durable pending（P2-§4） |
| R-10 | "`verifyHeld(epoch<=0)` 返回 LOST 是 bug" | 该入参语义为"调用方无号"，`authority` 给 `NEVER_HELD` 是设计（P2-§4） |
| R-11 | "K 的 J-F-02『6 条可复现』" | 5 条真红 + 1 假红 + 1 间歇（L-6b） |
| R-12 | "`markdown` 围栏 key 全局唯一" | 实为**每次渲染内唯一**（作用域=单条消息）；当前无害（P4-§3） |
| R-13 | PK-17 子句"`ResultViews` 未消毒" | **部分否证**：行值/列标签/options 出端已消毒（`ResultViews:109-114,226-256`，禁形 `:67-68`）；未消毒的只有 `id`（provider 拼 `"rv-"+toolCallId`）与 `meta.source`（常量），均非内容面。`title` 走 `optionalText:212-217` ⇒ 命中禁形静默 null（语义取舍，非缺陷）。**结论：子句收窄，不整条撤回**（FA1-CORR-1） |
| R-14 | "BZ-26 在前端还有第二故障点" | 前端把非有限 `step` 折 `undefined`（`FormRenderer:573-581`），NFE 只在服务端（FA4-§3.2） |
| R-15 | "`ui_*` 卡片摘要会露出英文工具名 `ui_click`" | `AiPanel.vue:1999-2013` 剥前缀后命中 `STATUS_WORD_SET` 返回 null ⇒ 该行不渲染；只剩注释漂移（FA5-C1） |
| R-16 | "`publish-mode` 缺字段⇒MERGE 是 fail-open 缺陷" | 与后端 `settingsJson` 空⇒MERGE 同口径，无 DTO 漂移现例（FA5-C2） |
| R-17 | "`setInterval(2^31-1)` 被折成 1ms" | 取值为 32 位有符号上界且每次 `arm` 先 `clearTimer`；有 13 条用例（FA5-C3） |
| R-18 | "`watchJob` 的 `onerror` 里 `source` 在 TDZ" | `onerror` 只由事件异步触发，返回后 `source` 已绑定；无现发路径（FA5-C4） |
| R-19 | "`resultview` 可被 `__proto__` 打穿 / `javascript:` 伪协议" | 只读 + 非数组返回 `[]` + `{{ }}` 文本插值、无 `v-html`（FA5-C5） |
| R-20 | "`undo_last_sheet_edit` 在 `runSheetTool` 被硬拒 ⇒ 撤销功能坏了" | 撤销走 store 预览面（`SHEET_PREVIEW_TOOLS`），执行器拒执是对绕过确认面的 fail-closed（FA5-C6） |
| R-21 | "路由 1 会把 `navigate_to` 拦在 scope 外" | `navigate_to` 不在 `FRONTEND_TOOLS`，走路由 2 的活闸（FA5-C7） |
| **R-22** | **"`ui_event.goto_step` 越过第 1 步导致预检查/导入读旧文件"（原 FE-45/FA3-N2，S2）** | **可达性否证（FA-adversarial）**：向导挂载期间 `goto_step` **无生产者**——`confirm_step` 声明 `handlerNames:['export.nextStep','import.nextStep']`（`frontend-tools.ts:890-902`），派发器优先 handler（`:1019-1023`）；`import.nextStep` 调 `goStep`（`ImportWizardView:1373` → `:875-905`）**会** `flushEditors`。只有"无页面 handler"时才落 `toUiEvent → goto_step`，此时向导未挂载、无可 flush；路由 3 还需一个名为 `goto_step` 的后端工具（37 个中无）。**整条撤回**，编号 FE-45 保留空缺以免外部引用错位 |
| — | BZ-29 子断言"只能手工改库" | 设置页可 `createProvider`/`addModel` 再绑定（P-adversarial） |

### 2.4 收窄 / 限定（保留条目，改边界）

| # | 条目 | 原表述 → 收窄后 | 依据 |
|---|---|---|---|
| N-1 | FE-16 | "换任务不回收"→ **仅导入向导**；导出侧有 `resetTaskScopedState`+`stopJobWatchForTaskSwitch`，导出侧残留是"无代次"（FE-54）。**提级请求被否**：机制确认但触发需"目标任务 `currentStep≠0` 或文件下载 404" ⇒ 维持 **S2**（影响是 S1 级、前提门控），不再标"建议 S1" | FA-adversarial（FA3-N1） |
| N-2 | FE-30 | "普通卡参数明文"→ 实际暴露面仅"顶层标量 + 键名不命中 `/pass(word)?\|secret\|token\|api_?key/i`"（对象一律"（复杂值）"、路径→文件名） | FA5-§2 |
| N-3 | FE-32 | "`allowSteps` 是死闸"→ **区域侧**死闸（`ai-regions.ts:50`，零注册）；**工作区侧**是活闸（`frontend-tools.ts:722,988`），不得一并删除 | FA5-§2 |
| N-4 | FE-17 / BZ-15 / BZ-29 | 保留条目、收窄条件（409 后滞后读；凭据重放需不可信上游；可经设置页恢复） | P-adversarial PARTLY |
| N-5 | FE-24 | "折叠/展开观察器不重绑定"→ **未验证（T）**，不据此派工；FA 轮亦未做端到端复核 | P-adversarial UNVERIFIED |
| **N-6** | **ID-15** | "未认证即可破坏共享状态"→ **演示边界内的定性修正**：无认证是本仓刻意边界，sessionId 由客户端声明也是设计；本缺陷是**形状校验缺失 + 键形碰撞**，不是"缺认证"。索引可自愈、无会话列表消费者 ⇒ **S3** | FA-adversarial（FA2-N1） |
| **N-7** | **BZ-35** | "非对称分区 ⇒ 判死杀活 runner"→ 判死语义是**设计如此**；新增部分只是"写侧分区与真死不可区分，且被判死者看不到 `job:cancel`" ⇒ 记为**条件风险（R）**，不是代码缺陷 | FA-adversarial（FA3-N8 PARTLY） |
| **N-8** | **MD-21** | "配置守卫覆盖不均"→ 拆开：`session.lock.*` 与 `lease.*` 两处时序键**已复推确认**（跨实例后果）；池容量/`max-attempts` 等属**配置卫生**，本轮未独立复推，不作同级断言 | FA-adversarial（FA2-N7） |

---

## 3. 后端保障矩阵

> 前提：原生 Redis Cluster（3 主 3 从）、共享 PG/文件、≥3 JVM、任意请求可落任意实例。
> 状态：**成立(条件)** / **部分成立** / **不成立** / **未验证**。"成立"仅指代码或单 JVM 用例可证。

### 3.1 Redis Cluster 与共享存储

| 能力 | 证据 | 状态 | 缺口 |
|---|---|---|---|
| run 级 13 键同槽 | `runScopedKey`（`RunStore:462-474,1019-1050`）；`ClusterSlotTest` CRC16 替身 | 成立(条件) | 非真 Cluster（IN-01） |
| 5 条租约 Lua 同槽 + 取号同序 | `LeaseService:155-256`（P2 全读） | 部分成立 | **Lua 原文从未执行**；改 `>=`→`>` 仍绿（CI-13） |
| 4 条会话锁 Lua 单键原子 | `SessionGate:80-143,269-290`（FA2 全读） | 部分成立 | 替身按脚本标记分派、Java 复刻语义 ⇒ 原子性只证意图；**续期失败静默降级**（RT-34） |
| 台账/幂等索引原子 | `LLEN` 先取 + `HSETNX`；仅 `IDEM_PUSH` 断言脚本文本 | 部分成立 | 其余脚本无文本断言 |
| 记忆 staging→RENAME 同槽 | `RedisChatMemoryRepository:193-199` | 成立(条件) | RENAME 同槽未真验；**staging 不在销毁面且 `ttl<=0` 无 TTL**（RT-37） |
| 记忆整窗写与销毁互斥 | `isArmed` 检查一次再写（`:309-313`） | **不成立（条件）** | purge 交错 ⇒ 销毁后记忆复活（**RT-36**） |
| 会话索引键隔离 | `sessionId="__ids__"` 的 legacy 键 = 全局索引键（`:99-101,202-204`） | **不成立（S3，可自愈）** | 一次 DELETE 删全库索引（无消费者、下次保存自愈）；该 sid 记忆读 `WRONGTYPE`（**ID-15**） |
| 销毁 13 键一条 DEL | 同 tag；`deleteRun` 列表已核（P2） | 成立(条件) | 观测键 3 键跨槽（RT-17 邻接） |
| **销毁索引完整性** | `AiSessionPurgeService:466-473` 只信"完整索引"；`RunStore:654-663` 会话索引是**弱写**、`:891-911` sentinel 无条件写 | **不成立（条件）** | 一次 `SADD` 失败 ⇒ `complete=true` 但 13 键残留（**RT-28**） |
| 帧流尾号探测 | `streamTailSeq` 只取末 16 条 max（`RedisFrameStore:107,580-604`） | **不成立（条件）** | 乱序/降级 ≥17 条 ⇒ 假"无新帧"（**RT-31**） |
| 回放缺口计数口径 | 写侧进程内 `observedPayloadBytes`，读侧同源（`:136-142,401,682-685`） | **不成立（跨实例）** | A 写窄窗口、B 只读 ⇒ 静默空洞而"缺口=0"（**RT-32**） |
| 逐 master SCAN | `RedisClusterScanSupport:95-135` fail-closed | 部分成立 | 真多 master 未验（U-03） |
| 单 master 故障 | `RedisAvailability:72-104` | **未消解分歧** | F 域字节码称逐 master；G5 称单连接。均同意无故障切换测试（U-02）→ 条件风险 |
| `maxmemory` 淘汰 | 部署假设 noeviction | 条件风险 | 无仓库内强制清单（U-04） |
| 静态门禁 | 308 文件 0 命中 | 成立 | `keyCommands().scan` 不匹配（IN-02） |

### 3.2 ≥3 JVM / 无粘滞 / 任意路由

| 能力 | 证据 | 状态 | 缺口 |
|---|---|---|---|
| 同会话单轮互斥 | `SessionGate` 单键 Lua + token | 成立(条件) | Redis 异常降级进程内门；**续期失败也会静默降级**（RT-34） |
| 续跑单驱动 | `ai:lease:run` + fence + `verifyHeld` | 成立(条件) | **首轮无租约**（RT-02） |
| **fencing 覆盖范围** | `RunStore:711-717` 只挡快照写；帧/台账/seq 只看 authority（`SseChatEmitter:1422-1443`、`RunStore:1863-1866,2185-2189`） | **部分成立** | ①`NEVER_HELD` plain SET（RT-02）②**HELD-but-fence-ahead** 第三态（**RT-21**）③**历史/记忆不受 fence 约束**（**RT-20**） |
| 决策恰一次 | `ai:decided` HSETNX + WAL | 成立(条件) | WAL 只 3 态（PK-04）；幂等≠派发存活（PK-01） |
| 工具副作用恰一次 | `claimExecution` HSETNX + 台账 + fence | 成立(条件) | `epoch==0` 跳过 fence 复核（RT-02） |
| 无粘滞拓扑 | HAProxy roundrobin 无 stick-table；两项 PASS | 成立（真跑过） | 顺序未断言（CI-07） |
| 跨实例读历史/配置/销毁/降级 | 4 项 PASS | 成立(条件：H2 TCP + 单节点) | 非 PG；N 会话并发交叉污染未测 |
| **session→current 指针** | `RunStore:733-739`（RUNNING 也写）、`:930-943`（只清自己的值） | **不成立** | 同会话第二轮顶掉挂起卡指针 ⇒ 刷新不重建、不可续跑（**RT-23**） |
| 3 JVM 全链 | 4/4 failure | **不成立** | 5 真红 + 1 假红 + 1 间歇 |
| 并发冷启动 | `Flyway baseline-on-migrate` | **不成立（间歇 2/3）** | RT-19（main 有 migration lock 候选） |
| 进程内映射回收 | 5 张按 runId 的映射无删除钩子（`RedisFrameStore:124-148`） | **不成立** | 内存随历史 run 数线性增长，无指标（**RT-33**） |

### 3.3 重连 / 取消 / park / 续跑 / kill / 恢复

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 挂订阅与回放原子 | 成立(条件) | `SseChatEmitter:666-714` | — |
| 终帧收尾判据=归档 | 成立(条件) | `:694-703` | **零终帧不报错**（校验器缺口，E-7/RT-04） |
| 差量 `?lastSeq` | 部分成立 | `ReattachLastSeqConformanceTest` | 窗口缺口（RT-07）；接管回卷（RT-14，main 候选）；**尾号低估假空读**（RT-31） |
| **回放队列内存** | **不成立（风险）** | `SseChatEmitter:841-877` 每订阅者最多 6000 帧入**无界队列**，`liveQueued` 不计回放帧 | ≈6000×64KB/重挂，无字节准入（**RT-26**） |
| **回放游标推进** | **不成立（罕见）** | `:869-887` 序列化失败 `break` 后仍 `wakeFloor=max(...)` 含失败帧 | 该帧对该订阅者永久不补（**RT-27**） |
| **每帧锁内 Redis I/O** | **不成立** | `:1450-1523` 锁内 INCR+EVAL+XADD+PUBLISH；`/confirm` 回执帧同步走此路径 | 慢 Redis 串行化整轮（**RT-12**）；取号侧另有 INCR+EXPIRE（FA2） |
| 取消（驱动线程） | 成立(条件) | `ResilientChatService:1286-1300` | — |
| **取消（park）** | **不成立** | `ResumeService:691-699` 只写快照+`cancelPendings` | 零终帧；派发可 skipped（RT-04） |
| 取消（Redis 写失败） | 不成立 | `AiController:862` 无条件 `cancelled:true` | 跨实例继续跑（RT-13） |
| 取消后不再副作用 | **不成立** | 无复查 | 阻塞路径毫秒级；续跑路径宽窗口（PK-02） |
| park 线程交还 | 成立(条件) | `ConfirmGate:592-623` | 真实 Spring AI 循环未验（PK-15） |
| **自愈派发被拒的收敛** | **不成立（候选）** | candidate `AiController:766-793` 只 debug 后返回 200+emitter；`NO_TRANSPORT_DEADLINE` | 拒绝后无终帧、不落 FAILED ⇒ 客户端无界挂起（**RT-39**；快照走 `completeSubscriptions()`，不复现） |
| **park 状态外置** | **不成立（条件）** | `RunStore:1297-1318` 只读时 `save` 静默 return | 快照非 SUSPENDED + pending 存在 ⇒ 不可续跑、永久 409（**RT-22**） |
| 决策 × park 退出 | **不成立** | latch 预登记 + `woke` 即不派发 | PK-01（main 有 latch 候选；两条 run 已跑完，**未证明覆盖该路径**） |
| **触发池饱和** | 部分成立 | `ConfirmGate:1661-1688` 直接 `EXECUTOR_SATURATED`，不重派 | 已批准卡停"已决无人执行"（**PK-19**） |
| **请求帧锁外发射** | 部分成立 | `ConfirmGate:487-503`、`:563-572` 在锁外 emit | 决策帧可先于请求帧（**PK-20**，窄窗） |
| **请求帧发车位强读** | 不成立（条件） | `markRequestEmitted` 内 `pendingRaw/decisionMarker` 抛错，调用点无 try | 逐张卡永不公布 + `PENDING_UNRESOLVED`（**PK-18**） |
| 即时前端工具到期 | 部分成立 | 进程内单线程 | 实例死亡即不收敛（PK-05） |
| 僵尸接管 | 条件风险 | `forceTakeover` 只校验快照 | 覆盖记忆（RT-05） |
| 丢租（LOST 墓碑） | 条件风险 | `LeaseService:686-709` | RUNNING 无接管源（RT-10） |
| kill 驱动实例 | **不成立** | 180s 409 + 前端丢 reattach + 无周期扫描 | RT-03 |
| 死轮 → purge | **不成立** | 永 `complete=false` → 永久 410 | RT-06；**记忆面另有复活与 staging 残留**（RT-36/RT-37） |
| **终态路径异常安全** | **不成立（条件）** | `fail:1386`、`cancelTerminal:1251`、`finish:980`、`ledgerCountSince:756` 无保护 | Redis 抖动恰在收尾 ⇒ 无 error 帧、快照停 RUNNING（**RT-24**） |
| **"lenient" 读口** | 部分成立 | `RunStore:1347-1377` 单条损坏仍抛 | `/runs/current` 500、取消半途而废（**RT-25**） |
| 停机 draining | **不成立（实测）** | 15s 内无 `ready=false` | RT-18（main 有 drain 候选）；`cluster` profile 无 graceful（RT-30） |
| 停机在飞轮 | 条件风险 | `AiExecutorConfig:52` `shutdownNow` | 快照停 RUNNING（RT-18） |

### 3.4 历史 / 配置 / 工具 / 业务作业 / 文件 / KB

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| DB seq 不重不跳 | 成立(条件) | 唯一约束 + 32 次重试（`AiChatHistoryService:148-168`） | PG 未真跑；`hasMore` 方向依赖（MD-07） |
| **历史写入与 fencing 的关系** | **不成立（条件）** | `recordFinalAssistant` 在 `save` **之前**（`ResilientChatService:1002-1006`）；`AiChatHistoryService:126-146` first-write-wins | 输家答案先入库 ⇒ 刷新看旧答案（**RT-20**） |
| 记忆整窗写 Cluster 安全 | 成立(条件) | staging+RENAME | **别名漏洞**（ID-04、ID-15）；**staging 残留**（RT-37） |
| 记忆配对保护 | 部分成立 | 产品写口保配对；advisor 写口不保 | MD-02；短结果变长（MD-16） |
| 上下文分母 | **不成立（显示面）** | `/context` 用 `limitTokens(null, echoModel)`；`ContextState` 无 providerId | 与压缩分母不同源（MD-04） |
| 配置热更新 | **部分成立（跨实例播种不收敛）** | 版本号 + 1s 轮询 + 版本闸；**种子写路径不 bump 版本**（`AiConfigSeeder:99,106`） | 写并发无 CAS（MD-08）；审计缺前后像（BZ-24，D）；**跨实例空快照 ⇒ 假 `PROVIDER_GONE`**（**MD-09**，已由 `40182796512` 证实） |
| **配置加载的健壮性** | **不成立（条件）** | `AiConfigService:263-265` → `Map.copyOf`（`:1020-1024`），`V4` 无 NOT NULL | 一行 NULL ⇒ 加载永久失败、冷启误报"未启用"（**BZ-28**） |
| **配置守卫覆盖** | **不成立（部分键）** | 9 个 `@PostConstruct`（`AiProperties:234-654`）；`session.lock.*`/`lease.*`/池容量/`resilience.max-*` 无校验 | `watchdog>=ttl`、`renewInterval>=ttl`、池=0 均静默改写语义（**MD-21**） |
| **env 合成行的读写对称** | **不成立（条件）** | 读 `effectiveProviders:436-445` 合成；写 `resolveDefaultProvider:586-611` 只看 DB | 设置页"列得出、存不进"，硬删后只能改库（**BZ-29**） |
| 密钥外发 | **已证实缺陷** | `AiConfigWriter:325-335` + `AiConfigController:152` + `AiConnectionProbe:151` | 任意外部主机（默认）；LAN 需开关/TOCTOU（ID-03） |
| 密钥/审计 | 部分成立 | 保留旧 key（有测试）；审计只有新 baseUrl | 无法独立回答"端点变了吗"（BZ-24） |
| 工具签名/披露/授权 | 部分成立 | 四维只来自注解；`metaMap` 只扫 `getDeclaredMethods()`（`ToolRegistry:88-113`） | 元数据缺失 fail-open（PK-11，S3）；scope 守卫下限 26<37（CI-14）；**空 scope 数组双出口分叉**（§8.3） |
| 工具守卫（形状面） | 成立（条件） | 五道闸均 fail-closed（FA1 全读） | **只判"有无"不判"语义空值/重复"**（BZ-31）；`commit` 用 `asBoolean` 弱解析 |
| 提示词定界 | 部分成立 | 闭标签中和 + 计数式强断言 | FORMAT 字符缝隙（`\p{Cf}` 未中和）；**取消收尾路径绕过信封**（PK-17） |
| 截断契约 | 部分成立 | 行/字符双闸 + 结构化整体标记 | notice↔`returnedChars` 恒差标记长度（PK-17）；**回灌闸是第三截断面**（**PK-21**）；双通道同时截断（PK-16） |
| **生成式表单白名单** | **不成立** | `GenerativeFormRules:1149-1153,1177-1181` + `runtimeOptionValues:1350-1375` | 父值缺失/无分支 ⇒ 任意值放行（**BZ-25**；前端自然触发路径见 FE-60） |
| **表单 step 校验** | **不成立** | `:1110-1114` 溢出 `NumberFormatException` 穿出守卫 | `/frontend-tool-result` 500；某些 schema 永久不可提交（**BZ-26**） |
| 业务作业互斥/终态 | 成立(条件：DB 行锁) | `JobService:156-165` 四守卫 | **无可执行性校验**（BZ-01）；**无类型一致性**（BZ-39） |
| **建作业守卫的开销/降级** | **部分成立** | `JobService:292-310` 在行锁/事务内遍历**全部** job 调 `isRunnerAlive`；不可用分支 `:298-303` **已**报 Redis 不可用 | N 次同步 Redis 持行锁 ⇒ 延迟随历史增长；错误码复用 `JOB_ALREADY_RUNNING` 被前端读成"收尾中"；无快速降级/指标（**BZ-34**，S3） |
| **作业判死的分区安全** | **条件风险（PARTLY）** | 写侧失败仅 WARN（`JobHeartbeatRegistry:409-416,625-634`），`beat` 缺失 + `\|v2` 锚点 ⇒ STALE 是**设计语义** | 写侧分区与真死不可区分 + 被判死者看不到 `job:cancel` ⇒ 双写者（**BZ-35**，需写侧分区注入） |
| runner-active 残留 | 条件风险 | TTL=2×max-exec+5m；同主机 PID 清理（`:568-593`） | 崩溃残留挡新建 65m（2h ⇒ 4h5m）；容器主机名变化 ⇒ 清理永不匹配（**BZ-38**） |
| 任务 SSE 扇出 | 部分成立 | `TaskSseService:250-285` 顺序同步等预算 | 调用线程占用 k×15s；跨实例发布无预算（**BZ-36/BZ-37**） |
| IMPORT/PRECHECK 兜底 | **不成立** | 仅 EXPORT 有 `afterCompletion` | 悬置 30m（BZ-02） |
| 条目收敛（FAILED/取消） | 成立(候选) | `JobProgressService:146-190` | main 有 job 候选（run 已跑完，未证明）；快照实测残留（BZ-06） |
| 单码下载字节 | 成立(候选) | `FileController:276-310` | main 有 file 候选（run 已跑完，未证明）；快照前一律 zip（BZ-07） |
| 共享盘多实例 | 成立(条件) | 启动守卫绝对路径 | 同 runner 本地目录（U-07） |
| KB 索引/增量 | 部分成立 | 异常 fail-open | 空清单（BZ-16）、元数据（BZ-17）、`match` 档（BZ-18）、版本先记后建（BZ-27）、同名章节取第一（BZ-30） |
| **KB 停止终局** | 不成立（窄可达） | `stop()` 关 analyzer 且与 `refreshNow` 不互斥 | 停机后重建永不可能成功 + 自旋（BZ-20，S3） |
| **KB HTTP 源** | 部分成立 | `Redirect.NORMAL` + 无条件 `Authorization` + `ofByteArray` | 302 头重放 + 无界正文（BZ-15） |
| **结果视图重投影** | **不成立** | `ListTasksResultViewProvider:69-83` 不看 `resultText`、每次重取库 | 归档卡面 ≠ 当时工具结果（**BZ-33**） |

---

## 4. 前端保障矩阵

> 前提：多页签并行 + 多用户隔离 + 业务态同步。结论先行：**页签级隔离在安全上下文可证（main 有加固候选）；用户级隔离不存在；业务态跨页签同步不存在；非安全上下文"页签共享"是缺陷；向导写回链与导出保存链存在"空内容覆盖"面。**

| # | 场景 | 机制 | 状态 | 证据 | 缺陷 |
|---|---|---|---|---|---|
| F1 | 多页签并行（各自新会话） | sessionStorage + Web Locks | 成立(条件) | `tab-ownership-lifecycle`；`13-nfezui` | 3+ 页签矩阵缺测（FE-13） |
| F2 | 并发对话（同 sid） | 服务端 `SessionGate` 409 | 成立(条件) | `SessionBusyFrameIsolationConformanceTest` | 409 后不进恢复（FE-17） |
| F3 | 复制页签（安全上下文） | duplicate → 换新 id | 成立(条件) | 同上 | 接管继承草稿（FE-08）；main 有加固候选 |
| F4 | **复制页签（http+内网 IP）** | 无 locks → `unsupported` → 按"恢复" | **不成立** | `ai.ts:419-427,4588-4590` | 共用会话、互抢 409、"新建对话"互毁（FE-01，main 候选） |
| F5 | 慢重载 | 只降级不重认领 | 部分成立 | `ai.ts:4607-4609` | 可被空白副本接走（FE-07，main 候选） |
| F6 | **多用户** | 无 | **不存在** | `api/http.ts` 无 Authorization | FE-02 |
| F7 | 一轮多张前端卡（实时） | `suspended` 单布尔 | **不成立** | `ai.ts:5090` | 最后一张不回连（FE-13/PK-13） |
| F8 | 一轮多张卡（**恢复路径**） | 串行队列 + `clearSuspendedIfNoOtherConfirms` 只数 CONFIRM | **不成立** | `ai.ts:2987-2992,2358-2360,2461-2463` | 下一张非 CONFIRM 卡提交不重连，队列永久卡住（FE-22） |
| F9 | 多张人机卡同屏 | 三单例 | 部分不成立 | `AiPanel.vue:614,632-639,645-653` | GF 无入口；预览可放弃；**任一张残留卡的"放弃"取消单例里的当前交接**（PK-12） |
| F10 | **错误终帧 × suspended** | `error` 不重置 `suspended` | **不成立** | `ai.ts:5187-5206` vs `:5159` | composer 锁死 + 幽灵停止键（FE-21） |
| F11 | **终帧 × 单例清理** | 只收 `activeForm` | 部分成立 | `ai.ts:5146-5206` | 预览色/上传交接残留；`unavailable` 态**无生产者**（FE-25） |
| F12 | **上传交接跨界** | 选择器续体无轮次闸 | **不成立** | `ai.ts:3567-3639,3724-3754` | 轮次终止后仍交文件并把 `cancelled` 段改 `succeeded`（FE-23） |
| F13 | 刷新恢复（RUNNING） | 镜像 → `/history` → 全量回放 | 成立(条件) | `ai.ts:2169-2202` | 先清空后回放无回滚（FE-06）；`/history` 失败则完全不重挂（FE-26） |
| F14 | 刷新恢复（SUSPENDED） | 多卡串行 | 成立(条件) | `restore-*` | 串行队列零测试（FE-22） |
| F15 | 停止 | 本地先封口 | 成立(条件) | `aie2e-stop` | start 前停止取消上一轮（FE-05） |
| F16 | 会话销毁（410） | 换 id + 清屏 | 成立(行为) | `session-purged-recovery` | 静默清空（FE-04） |
| F17 | **业务态跨页签** | 无 | **不存在** | 仅 `ai-session-ownership` 用 BroadcastChannel | FE-14；发布链表是模块级 Map（FA5 确认） |
| F18 | 设置页 ↔ 工作区 | 不 `enterPage` | **不成立** | `router:54-66`；`workspace:471-509` | 上下文陈旧；**保存后整会话面板标签不刷新**（I-10 并入 FE-15） |
| F19 | 导入向导换任务 | 只 `setTaskId` | **不成立** | `ImportWizardView:1665-1679,523-538,700-732,789-822` | 旧作业串新任务、误只读（FE-16）；**并可把 A 的数据写进 B**（S2，前提：目标任务 `currentStep≠0` 或文件下载 404） |
| F20 | AI 发起发布 | `START_JOB_SYNC` 缺 publish | **不成立** | `ai.ts:812-816` | 不绑页面面板（FE-18） |
| F21 | 并发发布 | 单槽（闸位 + workspace dispose 两处） | **不成立** | `task.ts:113-116,352-355`；`workspace.ts:75,551-552` | 旧冲突静默丢弃（FE-19、FE-72） |
| F22 | **DOM 可见性** | 快照/写动词只判 `isConnected` | **不成立** | `ai-dom-tools.ts:562-571,389-418,981-987` | 第 4 步可写第 1 步隐藏控件，回执"界面已同步"（FE-32） |
| F23 | **DOM 快照缓存** | 按 (epoch,args) 缓存 + 同参去重两层 | **不成立** | `ai-dom-tools.ts:119,531-535,1266-1277,1374-1387`；`frontend-tools.ts:579-591,628-635` | 动作后重拍恒陈旧（FE-33，第二层见 F-15） |
| F24 | 表格写：重复目标列 | 不去重 | **不成立** | `sheet-tools.ts:1951-1983,2010-2021,2604-2624` | 格数虚增 + 该层永久不可撤（FE-34） |
| F25 | 表格写：分块异常 | 无 try/回滚 | 条件风险 | `sheet-tools.ts:2601-2603` | 半批落盘、无撤销条目（FE-38） |
| F26 | 表格读：规模 | 无读侧尺寸闸 | 部分成立 | `sheet-tools.ts:1260-1267,867-893` | 3 行选择物化 30 万行（FE-36） |
| F27 | markdown 流式围栏 | 只认顶格 ``` | 部分成立 | `markdown.ts:197-200,917-925` | 引用块/`~~~` 不受保护（FE-41） |
| F28 | 前端提交门禁 | frontend 只开 frontend 面 | **不成立** | `changed-files.mjs:75-79` | 纯前端改动不跑浏览器 e2e（CI-06） |
| **F29** | **导入第 1 步写回链** | `flushEditors` 无条件写所有挂载表 | **不成立** | `ImportWizardView:789-822,523-538,714-724` | `GET /files` 一次失败 ⇒ 模板覆盖既有上传件；无文件项被建 0 行件（**FE-48**） |
| **F30** | **导入选中变更** | `watch(selectedDefs)` → `loadEditors(true)` | **不成立** | `ImportWizardView:1651-1655,1406-1418,700-732` | 未保存的表格编辑被静默覆盖（**FE-47**） |
| **F31** | **AI 跳步（`ui_event.goto_step`）** | 挂载期走 handler（`confirm_step`→`nextStep`→`goStep` 会 flush） | **已否证（无可达路径）** | `frontend-tools.ts:890-902,1019-1023`；`ImportWizardView:1373,875-905` | 无缺陷：`goto_step` 仅在"无页面 handler"（向导未挂载、无可 flush）时产生，且需后端有同名工具（无）⇒ §2.3 R-22 |
| **F32** | **导入/预检查起作业失败回执** | 读陈旧 ref | **不成立** | `ImportWizardView:1442-1458,963-984` | 回"旧作业已启动"，模型据此继续（**FE-46**） |
| **F33** | **导出「保存修改」/批量下载** | 只判 `gridRefs[code]` 是否存在 | **不成立** | `ExportWizardView:471-494,896-941,967-977,1155-1188`；`SpreadGrid:96` | 未加载标签页产出空工作簿 ⇒ 覆盖服务端导出文件仍报"已保存"（**FE-50**） |
| **F34** | **导出起作业** | `persistStepData` 结局未判 | **不成立** | `ExportWizardView:763-788`（`:772`） | 条件未落库仍启动作业 ⇒ 界面已筛、实际全量（**FE-51**） |
| **F35** | **导出只读判据** | `listJobs().catch(()=>[])` | **不成立** | `ExportWizardView:440-462,310-316` | 已 COMPLETED 任务变可编辑，四道守卫全放行（**FE-57**） |
| **F36** | **数值写回保真** | `String(Number(text))` | **不成立** | `excel.ts:64-82,361-412,496-506` | 前导零/≥16 位被改写并固化（**FE-49**） |
| **F37** | **工作区动作时序** | 每帧 `void runFrontendTool`，无队列 | **不成立** | `ai.ts:5061`；`workspace.ts:465-521`；`frontend-tools.ts:1019-1034` | 同批并行打到导航前 taskId（**FE-67**）；成功写双记账（**FE-68**） |
| **F38** | **`restore_task` 路由** | `resolveWizardRoute` 无 page/taskType | **不成立** | `frontend-tools.ts:852-864`；`task.ts:392-399` | IMPORT 任务被开成导出向导且改写 `taskType`（**FE-69**） |
| **F39** | **设置页探活/模型拉取** | 目标=库内端点；缓存无 provider 维 | **不成立** | `AiSettingsView:365-374,50-51,415-449` | "测通"的是旧地址；A 的模型可加进 B（**FE-58/FE-59**） |
| **F40** | **任务列表分页/筛选** | `loading` 早退 | 部分成立 | `task.ts:158-161,197-228` | 分页/筛选未重拉（**FE-70**） |
| **F41** | **数据浏览页绑定** | `enterPage` 只写传入键 | **不成立** | `workspace.ts:144-178`；`DataBrowserView:238,361` | 残旧 `taskId`、`taskStatus` 粘住（**FE-71**） |
| **F42** | **发布冲突计数** | 首页 50 条当总数 | **不成立** | `task.ts:361-375,103-110`；`resultview.ts:359` | 模型/卡面"共 50 处"（实际更多）；WARNING 被当冲突（**FE-62**） |

---

## 5. Canonical findings（**223 条**）

> **Severity**：S1 数据/密钥/不可逆写外泄或无人工干预的持久错误｜S2 用户可见错误结果、静默丢内容、整轮挂死、多实例承诺不成立｜S3 一致性/可观测性/窄窗口｜S4 文案/死码/性能。
> **Certainty**：C 代码可证｜R 条件（窗口/拓扑/配置）｜T 缺测/未验证｜D 已裁决边界。
> **Evidence（验证模式）**：**V1** 对抗复核确认（L/M/N/P）｜**V2** 全量逐行（P1–P5）｜**V3** 缺口代理静态（G1–G5）｜**V4** 域报告静态（A–J/I）｜**V5** CI 运行日志｜**V6** 本报告自行复核｜**V7** 分片逐行（FA1–FA6，**未经对抗复核**）。
> 同根因合并，跨域原始编号见"来源"列（省略前缀序号）。`V7` 条目已与既有条目去重；合并处只在宿主行加注。
> **编号空缺说明**：**FE-45**（`ui_event.goto_step` 不写回）经可达性否证**整条撤回**（§2.3 R-22）；**FE-52**（导出陈旧 `exportJob`）与 **FE-46** 同机制**合并**。两者编号保留空缺，以免与本文件早前版本的外部引用错位；其余编号连续。

### 5.1 身份 / 隔离 / 密钥（ID，15）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| ID-01 | 全链无认证/无主体（39 端点） | S1(多用户)/S3(单机) | D | V1 | `pom.xml` 无 security；`AiController` 全部端点 | 任意可访问者调用；DC-10 已登记 | 无（不可有） |
| ID-02 | `/runs` 全局枚举 sessionId+runId+`pending.arguments/resultText`；`/runs/{id}` 加 `finalText`/ledger | S1 | C | V1 | `AiController:934-951`；`ResumeService:293-360` | 一条 GET 拿全 id 与载荷 → 直接喂 ID-05 | 无；G5 判"设计即全局运维面"⇒需产品裁决 |
| ID-03 | 改 baseUrl 保留旧 key，`/test` 与 `/models/fetch` 把库中 key 发到新主机 | S1 | C | V6 | `AiConfigWriter:309-313,325-335`；`AiConfigController:143-158`；`AiConnectionProbe:151` | 4 个未认证请求 → key 外带（默认任意外部主机） | 有"保留 key"与"拦私网"两半边，无联动用例 |
| ID-04 | 记忆键别名：`sid="{x}"` 读/删/覆写 `sid="x"` 记忆 | S1 | C | V1 | `RedisChatMemoryRepository:187-204,281-291,314,338,350-355` | 正常发一轮即删（`saveAll` 的 legacy 清理） | 无花括号 id 用例 |
| ID-05 | 他人可批准/取消/续跑/销毁他人轮次 | S1(多用户) | R | V4 | `AiController:524,855,1072`；`AiSessionPurgeController:78` | 批准即真实执行 import/publish | 无负向用例 |
| ID-06 | 可向他人上下文注入任意文本（`result`/拒绝 `reason`） | S2 | R | V4 | `AiController:590-717`；`ConfirmGate:1138-1216` | 伪造"工具结果/用户决定" | schema 只覆盖结构化工具 |
| ID-07 | 反馈无会话归属（全表 top100、`rating=0` 按 messageId 全局删） | S3 | D/R | V3 | `AiFeedbackRepository:10,13`；`AiController:1933-1999` | 单用户边界内仅是"跨会话摘要/删他人赞踩"；FA4 确认列表面无过滤、`rating∈1/-1` 判定正确、`excerpt` 明文 | standalone+mock，不可见 |
| ID-08 | `sessionId`/`runId` 无形/长/熵校验（>64 落库 500、限流桶串用） | S2 | C | V3 | `AiController:313-316`；`AiFeedback:23-27` | 使能 ID-04/**ID-15**、跨用户 429 | 无 |
| ID-09 | 表单附件 `sessionId` 由客户端声明（不传即孤儿） | S3 | C | V1 | `GenerativeFormAttachmentController:80-107,142-152`；`RedisFormAttachmentStore:172-184` | 挂到他人会话、随其销毁被删 | 无 |
| ID-10 | 跨任务绑定只在上下文带 taskId 时生效 | S2 | R | V4 | `ToolTargetResolver:101-131`；`ToolTargetResolverTest:118-120` | 任务中心可对任意同类型任务起作业（仍需批准） | 缺"页面级+他人 taskId"负例 |
| ID-11 | CORS `localhost:*`+凭据+`0.0.0.0:8081` | S2 | R | V1 | `WebConfig:20-27`；`application.yml:1-3` | 本机任意网页跨源读 `/api/ai` | 无 CORS 用例 |
| ID-12 | 非安全上下文 sid 熵 ≈41bit+时间戳 | S3 | R | V1 | `ai.ts:201-211` | 猜中即共享会话 | 无 |
| ID-13 | 未认证昂贵/长连接操作（`/kb/reload`、`/scan`、SSE 无死线） | S3 | R | V4 | `AiController:894-932,1911`；`AiKbController:43` | 单实例耗尽 | 无压测 |
| ID-14 | 409 仍下发 `runId`+`reattach`；前端不消费 | S3 | R | V1 | `AiController:424-434`；`ai.ts:2589-2620` | 安全：他人可重挂；UX：用户无自救 | 409 用例给了字段却不断言 |
| **ID-15** | **记忆 legacy 键别名之二：`sessionId="__ids__"` 的 legacy 键就是全局会话索引键** | **S3** | C | **V1** | `RedisChatMemoryRepository:99-101`（`CONVERSATION_IDS_KEY="chat:mem:__ids__"`）、`:202-204`（`legacyKey` 用原样 id）、`:281-291`（读 ⇒ **WRONGTYPE**）、`:350-354`（删 ⇒ **DEL 全局索引**）；可达面 `DELETE /api/ai/session/__ids__`（`AiSessionPurgeService:717-727`）与空窗 `saveAll` | ①该 sid 的记忆读把 `range` 打在 **SET** 上 ⇒ `WRONGTYPE` 抛出（无 catch）；②一次 DELETE 删掉整个会话索引，`memoryRemovedFromIndex:731-735` 从此永远报"已移出"。**降为 S3 的理由（FA-adversarial）**：没有任何用户可见的会话列表消费 `findConversationIds()`，且每个会话下次保存会自愈索引 ⇒ 破坏可恢复、不删业务数据。同类更低：`sessionId="staging:{x}:tok"` 的 legacy 键 = 会话 x 的 staging 键。**边界**：无认证与客户端声明 sessionId 都是本仓刻意设计，缺陷是**形状校验缺失 + 键形碰撞**，不是"缺认证" | 无用例（`DeleteTest`/`ClusterSafetyTest` 只用普通 id） |

### 5.2 park / 确认门 / 工具契约（PK，21）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| PK-01 | 决策撞 park 退出：`woke=true` 无消费方 | S2 | C | V1 | `ConfirmGate:558,601-623,1596-1613,1876-1897` | 200 但无执行；再点 409；刷新后全量重放等不到 done | 无；`ConfirmGateDeadlockResumeTest:330-346` 反把窗口固化 |
| PK-02 | 取消后仍执行已放行工具 | S2 | C | V1 | `SpToolCallingManager:1320-1328,1816-1830`；`ResumeService:681-698→884-960` | 阻塞路径毫秒级（S3）；续跑路径跨重建/压缩宽窗口（S2） | 无"批准后取消"用例 |
| PK-03 | 超时/取消/重挂/认领/取消条目**五处裸写**非 CAS | S2 | C | V1 | `ConfirmGate:905-931,944-963`；`RunStore:1299-1307,1320-1328,1731-1738,2067-2083` | 丢输入、双结局帧、guard 与条目分叉；`cancelPendings` 不写 WAL | 只覆盖单侧 |
| PK-04 | WAL 只覆盖 3 个可重建态 ⇒ `APPROVED`/`FRONTEND_RESULT` 崩溃窗留永久死卡 | S2 | R | V1 | `ConfirmGate:1150,1186-1190,1296-1342`；`RunStore:1517-1554` | 不能批/拒/续跑/超时，只能取消整轮 | 无 |
| PK-05 | park 后到期收敛是进程内一次性任务 | S2 | R | V1 | `ConfirmGate:617-619,978-1011`；`StartupResumeRunner:139-148` | 实例死亡 → 卡不收敛（至 24h） | `ParkedFrontendExpiryTest` 同步执行 |
| PK-06 | **park 结算路径绕过内联循环的收尾块** ⇒ 该工具既无 `tool_call_end`/采样字段，也不进 `SheetAudit`（`sheet.counters` 恒 0、D2 台账不落）；同批"已结算未发帧"条目另缺 `tool_start/result` | **S2** | C | **V5** | `SpToolCallingManager:660-666`（**唯一** `SheetAudit.record` 调用点，紧接 `tool_call_end`）、`:1816-1847`（`resolvePending` 复用分支只 `return text` + 一条 debug）；`ResumeService:842-856`（续跑循环）；不变量自述 `:564-566`（"start 之后必然有对应 end"）；同批缺帧面 `:528-548,490-494` | `40182795560` 实测：`tool_call_start` 97 / `tool_call_end` 37 ⇒ **64 个 park 过的工具 64 个缺 end**（绿跑 `2ed8f95` 101/100）；9 个表格工具全为 FRONTEND ⇒ 生产中 100% 走 park；后果：8 个计数恒 0、8 个采样字段永久缺席、`kind=sheet_sample` 台账不落、`AIE2E-T-13` 因此 `readCalls 0→0` 红（**断言合法，非 harness**） | 无"复用分支记账/发 end"用例；`SpToolCallingManagerTraceFallbackTest` 未覆盖 |
| PK-07 | 轮内预算 armed 只在本进程本段（`MAX_ARMED_BATCHES` 跨 park 失效，无补偿） | S3 | C | V3 | `SpToolCallingManager:380-383,900-909`；`ToolActivityBeacon:242-276` | park 后继续产生新副作用 | 单实例单段 |
| PK-08 | 已公布卡被 step-limit/首错 skip ⇒ 前端"正在同步" | S3 | C | V3 | `SpToolCallingManager:495-557`；`ai.ts:3122-3129,2959-2987` | 双按钮禁用，只能刷新 | 只覆盖 REJECTED |
| PK-09 | `RunRegistry.close` 可能掐掉同实例刚启动的续跑 | S3 | R | V4 | `AiController:470-474`；`RunRegistry:189-194` | 续跑帧只进归档 | 无 |
| PK-10 | 非驱动实例 `/confirm` 丢回执帧；emitter 条目不回收 | S3 | C | V4 | `ConfirmGate:1186-1191`；`SseChatEmitter:1437-1443`；`RunRegistry:138-153` | "已批准"延迟；内存线性增长 | 只测 recycle 本身 |
| PK-11 | 元数据缺失 ⇒ 四维同时取最宽默认（**无启动不变量**） | S3 | R | V1/V7 | `ToolRegistry:88-113,202-216,317-327,371-376,415-436` | `metaMap` 只扫 `getDeclaredMethods()`、`callbackMap` 来自 `MethodToolCallbackProvider` ⇒ 继承 `@Tool` 有回调无 meta；此时 `forContext` 直通并**跳过 sheet 开关**、`matchesContext` 放行、`risk→READ`、`channel→BACKEND`；37 个 `@Tool` 均直接声明，**无现发实例** | 无 `callbackMap ⊆ metaMap` 断言（FA1 逐行确认） |
| PK-12 | 多张人机卡共用单例 | S2 | C | V1/V7 | `ai.ts:3328,3427,3533,3642-3648`；`AiPanel.vue:614,632-639,646-653`；`UploadHandoffCard:101-108` | GF 无入口；预览可放弃；**任何一张残留卡的"放弃"都会取消单例里的当前交接**（"点了这张、断的是那张"，FA4-17 给出组件级机制） | 无 live 双卡用例 |
| PK-13 | 一轮多张前端卡：首张回灌后 `suspended=false` | S2 | C | V1 | `ai.ts:5090,2461-2462,2987-2992` | 末张提交后无连接；混批同样中 | 无 live 双卡用例 |
| PK-14 | park 前置 `tool_start` ⇒ 等待期显示 running | S4 | D | V1 | `SpToolCallingManager:483-494,726-730`；`parts.ts:539-557` | 语义取舍（公告≠已执行） | `ParkToolStartOrderConformanceTest` |
| PK-15 | `RunParkedException` 穿越真实 Spring AI 循环 | S2 | T | V4 | 无真实循环证据 | 若被包一层 ⇒ 全 park 轮失败且卡悬空 | 无 |
| PK-16 | 双通道同时截断：live 帧 `noticeCode` 被覆盖；**`originalChars`/`returnedChars`/`strategy` 同被改写** | S3 | R | V1/V7 | `SseChatEmitter:1275-1292`；`FrameSizeLimiter:56,104-142,277-295` | 需"模型侧已裁 + 单帧 >64KB"（当前唯一 provider 自限 ⇒ 潜在）；双重截断时用户看到的"原始长度"是**模型侧裁后**长度（偏小），`strategy` 可能被整键 remove（FA2 更正 F-3） | 无"两侧同时"用例 |
| PK-17 | 契约层 minor 包：notice↔`returnedChars` **恒差 ≈ 标记长度**、FORMAT 字符缝隙（`\p{Cf}` 未中和）、**取消收尾路径绕过信封/裁剪**、截断前全文 `readTree`、`resultView` 预算靠约定、`defaultFrom` 允许 attachment 字面 id | S4 | C | V1/V7 | `ToolResultLimiter:146,162-176`；`PromptDelimiters:33`；`ResilientChatService:1164-1173`；`ResultViews:212-217`；`GenerativeFormRules:749,940` | 模型侧口径混乱；注入面只剩概率层；取消轮的完整工具结果未裁剪入记忆；`ResultViews` 未消毒子句**已收窄**（R-13） | `ToolResultLimiterTest:135` 只断言 `returnedChars==text.length()`，notice 数字结构上不可见 |
| PK-18 | **请求帧"发车位"路径重新引入抛异常读** | S3 | C | V1 | `ConfirmGate:533,864-882,488-493`；`RunStore:1633-1649,1425-1431` | 一次 `HGET` 失败 ⇒ 该卡永不公布 / 同批半公布 ⇒ `PENDING_UNRESOLVED` 不可续跑（需一次 HGET 失败，UI 不可恢复） | 无失败注入 |
| PK-19 | **触发池饱和不重派**（更正 A 域"15 次退避"） | S4 | C | V1 | `ConfirmGate:1661-1688`；`:1706-1736,1821` | 已批准卡停"已决无人执行"；停机窗口内全部落此支（重启扫描会兜底） | `ResumeDispatchRetryTest` 只打桩内层结局 |
| PK-20 | **请求帧在 `submitLock` 之外发射** | S4 | R | V1 | `ConfirmGate:487-503,563-572` vs 承诺 `:71-73,464-466` | 跨实例窄窗内 `confirm_decision` 先于 `confirm_request` ⇒ 已决卡被重建、409 | 用例只断言同线程顺序 |
| **PK-21** | **回灌闸的字符截断是"第三个截断面"，不接统一截断字段；帧侧对已截短文本重跑 limiter ⇒ `truncated=false`；`cancelled=true` 时结构化 JSON 被按字符切坏** | **S4** | C | **V1** | `AiController:613-655`（结构化豁免被 `!cancelled` 门控于 `:622`，通用 `substring` 截断 `:638-655` 无条件生效，四个截断字段**只写 HTTP 响应体** `:710-716`）；`SpToolCallingManager:1793-1795`；`SseChatEmitter:1279-1281` | 任一 FRONTEND 工具回灌非 JSON 文本 > `callback.max-chars`（默认 8192）⇒ 信封与 `tool_result` 帧全报"未截断"（`originalChars==returnedChars==截断后长度`），唯一信号是正文中文标记；卡面拿到的也是截断后文本（"帧里 result 仍是全文"对前端工具不成立）。可达性窄：现有 handler 文本普遍远小于 8192 | `ToolResultDualChannelContractTest` 只覆盖后端工具；无"回灌截断后再断言信封/帧字段"用例 |

### 5.3 运行 / 帧 / 租约 / 恢复（RT，39）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| RT-01 | 租约权威失败时业务帧在取号前被丢、心跳照发；**被丢的终帧永久缺失** | S2 | C | V1 | `SseChatEmitter:1421-1444,1454-1476`；`LeaseService:797-841`；`RunStore:695-699` | 正文静默缺段；丢 `done` 后新 emitter `archivedTerminal=false` ⇒ 重挂永不收尾 | **有** `SseChatEmitterAuthorityFailureTest:51-77`；缺用户面信号/指标 |
| RT-02 | 首轮从不取租约 ⇒ `NEVER_HELD ⇒ plain SET`，`tryClaimExecution` 在 epoch=0 跳过 fence | S2 | R | V1 | `AiController:299-488`；`RunStore:690-726,764-789,1697-1707` | 陈旧写者覆盖接管方终态、再发 done；需 ≥180s 静默+接管+旧写者苏醒 | 无"never-held vs 已推进 fence"用例 |
| RT-03 | kill 驱动实例：180s 409 + 前端丢 reattach + 无周期扫描 | S2 | C | V4 | `AiController:416-435`；`RunLiveness:42-62`；`StartupResumeRunner:69-108` | 3 分钟"发送失败"，提示与真实原因无关 | `AiControllerZombieGateTest` mock 化 |
| RT-04 | park 取消零终帧（快照终态、归档无 `done`） | S2 | C | V1 | `ResumeService:691-699`；`SseChatEmitter:640-644,694-710`；`AiController:872-878` | 其它页签/刷新后客户端挂到看门狗；**前台页签本地已收敛**；派发 skipped 时连 CANCELLED 都不保证 | `HumanWaitParkTest:230-243` 把"无终帧"固化为期望 |
| RT-05 | 僵尸 RUNNING 接管：重建旧历史 → 整窗覆盖记忆 → current 指回旧轮 | S2 | R | V1 | `ResumeService:326-349,710-745`；`RunStore:733-735`；`ResilientChatService:1414-1448`；`RedisChatMemoryRepository:304-340` | 模型"忘记"最近一轮；刷新回放旧轮 | 无"旧轮接管+会话已前进"用例 |
| RT-06 | 死轮 ⇒ purge 永 `complete=false`、同 sid 永久 410 | S2 | C | V4 | `AiSessionPurgeService:367-431,742-760`；`SessionPurgeGuard:106-123` | 销毁"看似成功未删净" | 活跃 run 会收敛 |
| RT-07 | 归档窗口缺口只有服务端计数、无帧内标记 | S2 | R | V4 | `RedisFrameStore:399-416,668-679`；`ai.ts:4930-4935` | 静默少一段；`tool_result` 落缺口则整卡消失 | 只断言计数；`afterSeq=0` 路径连计数都不记（FA2） |
| RT-08 | `wakeFloor` 本地扇出推进 ⇒ 晚到小号帧永久缺失 | S2 | R | V4 | `SseChatEmitter:1517-1520,1564-1573,1598-1601` | 一小段永久缺、表现为"没发生" | `WakeFloorTest` 覆盖反方向（main 有 wake 候选 L-3） |
| RT-09 | seq INCR 失败降级进程内计数 ⇒ 撞号、`mergeBySeq` 丢帧 | S3 | R | V4/V7 | `RedisFrameStore:183-197,485-499,741-743` | 回放丢帧、lastSeq 倒退；**成因更正（F-4）**：`fallbackSeq` 种子是已落档末号，撞号常见形态是"与另一实例在飞未落档的号相同"，`repairRollback` 只比进程内计数看不出该区间 ⇒ 修复点应在共享号源 | `SeqPersistenceTest` 非撞号场景 |
| RT-10 | 丢租超 TTL ⇒ LOST 墓碑终局；RUNNING 无接管源 | S2 | R | V4 | `LeaseService:686-709,844-850`；`ConfirmGate:1614-1618` | 整轮静默停摆 | 无"断 Redis>TTL"用例；**配置触发路径见 MD-21** |
| RT-11 | `RUNNING` 死轮被读流量续期 24h（**park 期不适用**） | S3 | R | V1 | `RunStore:2339-2385,815-825,930-943`；`AiController:538,748,990` | 只有持续读 `/runs/current`/`/events` 才续命；park 无续租线程 | 无 |
| RT-12 | **每帧 3–4 次 Redis 往返在 `emitLock` 内** | S3 | C | V1 | `SseChatEmitter:1450-1523,574-583,670-713,1560-1613`；取号侧另有 INCR+EXPIRE（`RedisFrameStore:186-188`） | 慢 Redis 串行化整轮，`/confirm` 回执帧同步排队 | 无锁持有时间断言 |
| RT-13 | 取消 Redis 写失败仍 200 `cancelled:true`；`wokeInProcess` 名不副实 | S2 | C | V3 | `AiController:855-884`；`CancellationRegistry:147-154,232-247` | UI 已停、另一实例继续（含副作用） | 无 |
| RT-14 | 接管后重挂从 `seq=1` 全量重放 | S2 | C | V5 | `cluster3-probe.mjs:2809-2827` | 正文/卡片重复 | main 有回填候选（L-2/L-3）；三实例 run 已跑完，本项**未出现在失败清单**（≠ 已证明） |
| RT-15 | 跨实例唤醒丢失无服务端兜底 | S3 | D | V4 | `RedisFrameWakeBus:48-56`；`sse.ts:36-40,525,645` | 最长 45s 静默后自愈；非浏览器客户端永久停 | `WakeFloorTest` |
| RT-16 | 安全台账只记 `sheet_sample`；拒绝/限流/降级仅进程内计数 | S4 | D | V1 | `SecurityEvents:24-39`；`RunStore:2756-2777`；`AiController:329,357,372,651` | 事后无法回答"被拒几次/对谁"（已裁决边界） | 无 |
| RT-17 | 唤醒容器无 `@PreDestroy`；`markDriving` 把资源锁 TTL 改写为 30s；畸形租约卡一轮 | S3 | C | V3 | `RedisFrameWakeBus:105-135`；`LeaseService:347-354,869-881,601-609` | 非守护线程阻退出；资源锁语义被静默改写 | 无 |
| RT-18 | 停机：**SIGTERM 无可观测 draining 窗口**（实测）+ 执行器 `shutdownNow` 无终态写 | S2 | C/R | V5 | `ReadinessService`；`ShutdownDrainGate`；`AiExecutorConfig:43-54` | 在途 SSE 硬切；快照停 RUNNING 至 180s | `ReadinessDrainWindowTest` 不绑真实端口（main 有 drain 候选） |
| RT-19 | 三 JVM 并发冷启动 Flyway baseline 竞态（2/3） | S2 | R | V5 | `application.yml:64-67`；`cluster3-probe.mjs:1728-1765` | 至少一实例启动失败 | 无（main 有 migration lock 候选） |
| RT-20 | **fencing 只到快照：DB 历史与记忆窗口由输家写入** | S2 | C | V1 | `ResilientChatService:1002-1006,1276-1280,1414-1487`；`AiChatHistoryService:126-146` | 输家答案先入库（first-write-wins）⇒ 刷新显示旧答案；记忆被输家覆写（需 ≥2 JVM + 冻结/接管） | `LeaseLossReadOnlyTest` 不看历史/记忆 |
| RT-21 | **第三租约态 HELD-but-fence-ahead**：帧/台账/seq 不查 fence | S2 | R | V1 | `SseChatEmitter:1422-1443,1473,1491-1498`；`RunStore:1863-1866,2185-2189,2211-2218` | 旧驱动在租约 TTL 后、续租失败前的 ≤10s 窗内仍发帧（**可能第二个 done**）、写台账 | 无该状态用例 |
| RT-22 | **`saveSuspended` 在只读时静默成功** ⇒ 硬规范①被破、轮次不可续跑 | S2 | R | V1 | `RunStore:1275-1318,695-699`；`SpToolCallingManager:473` | pending 已写、快照非 SUSPENDED、请求帧被丢 ⇒ `PENDING_UNRESOLVED` 永久 409 | 无 |
| RT-23 | **`session:current` 被 RUNNING 轮覆写** ⇒ 挂起卡指针被顶掉 | S3 | C | V1 | `RunStore:733-739,815-825,930-943`；`AiController:979-984` | 同会话第二轮结束后清指针 ⇒ 刷新不重建、不可续跑、启动扫描 skip（可发现性缺陷；out-of-band runId 仍可续跑） | `RunStoreSessionCurrentTest` 无两会话轮次 |
| RT-24 | **终态路径非异常安全**：`fail`/`cancelTerminal`/`finish`/`ledgerCountSince` 内读失败即穿出 | S3 | C | V1 | `ResilientChatService:1386,1251,980,756-757`；`RunStore:1913-1929` | 恰在 Redis 降级时 ⇒ 无 error 帧、快照停 RUNNING | 无 |
| RT-25 | **"lenient" 读口对单条坏 entry 仍抛** | S3 | C | V2 | `RunStore:1347-1377`；调用点 `:2067-2083`、`AiController:992`、`ResilientChatService:1432` | `/runs/current` 500；取消半途（前批 CANCELLED 后批 PENDING） | 无 |
| RT-26 | **回放队列按帧不按字节**：每订阅者 ≤6000 帧入无界队列 | S3 | R | V2 | `SseChatEmitter:841-877,2112,1650-1657` | ≈6000×64KB/重挂（String 双倍），k 个慢重挂线性放大 | 无字节计量 |
| RT-27 | **`stageReplay` 把失败帧也算已投递** | S3 | R | V2 | `SseChatEmitter:869-887,1598-1601` | 该帧+其后对该订阅者永不补发；只有客户端带更小 lastSeq 可救 | 无计数 |
| RT-28 | **销毁 `complete=true` 但 13 键残留**：只信"完整索引"，会话索引是弱写 | S3 | C | V1 | `AiSessionPurgeService:466-473,388,421,546-553`；`RunStore:654-663,891-911` | 一次 `SADD` 失败 ⇒ 新 run 不被枚举、不删 ⇒ 工具入参与结果留 24h、草稿键残留、无重试 | `AiSessionPurgeServiceTest:458-495` 只覆盖两种回退 |
| RT-29 | **`LeaseService.release` 不校验 `created`** | S3 | R | V2 | `LeaseService:712-741` vs `:272-292`；`ConfirmGate:829-837,620` | "无释放权"只靠调用点自律；新增释放点会继承 | 无 |
| RT-30 | `cluster` profile 缺 `server.shutdown=graceful` | S3 | C | V4 | `application-cluster.yml:38-44` vs `postgresql:68` | 仅用 cluster profile 时停机不等在途请求 | `ProfileConfigContractTest` 只断言 PG/h2tcp |
| **RT-31** | **末号探测取"末 16 条 max"：乱序/降级 ≥17 条时提早返回"没有新帧"**（报告等级，**未独立复推**） | **S3** | R | **V7** | `RedisFrameStore:107`（`TAIL_PROBE=16`）、`:580-604`、`:405-408`、`:464-476`、`:741-743` | 降级实例的进程内号持续低于真实尾巴（每次 `XADD seq-0` 被拒 ⇒ 自动 ID）≥17 条后，读者在 `afterSeq` 落在"低估末号..真实末号"时拿到**空结果且 `degraded=false`**（不失败、不计数、不告警）⇒ 永久缺段；`lastSeqIfKnown` 同向低估 ⇒ 前端锚点倒退；`fallbackSeq` 以低估尾为种子 ⇒ 自我强化 | `RedisFrameStoreTest:140-156` 只构造 1 条晚到帧；替身已能构造该场景 ⇒ 用例可写而未写 |
| **RT-32** | **读侧窗口与写侧"不同源"（跨实例）⇒ `c1.streamReplayGap` 漏计**（报告等级，**未独立复推**） | **S4** | C | **V7** | `RedisFrameStore:136-142`（进程内口径，注释自认"全局退化为各实例之和"）、`:401,682-685`（`effectiveWindow` 用本实例观察值）、`:408-416` | A 写过大帧（窗口收窄）、B 只读（`observed=0` ⇒ 6000）后被要求 `?lastSeq=` ⇒ `from` 落在幸存窗口之前，`XRANGE` 静默返回空而 `from==afterSeq` ⇒ **不记缺口**；`/health` 的"回放缺口=0"不能证明没有缺口 | `RedisStreamCapacityTest:161-176` 全在同一实例 |
| **RT-33** | **五张按 runId 的进程内映射无回收**（报告等级，**未独立复推**） | **S4** | C | **V7** | `RedisFrameStore:124,127,142,145,148`（`fallbackSeqs`/`outOfOrderWarned`/`observedPayloadBytes`/`byteCapWarned`/`byteFloorWarned`） | `deleteRun`/会话销毁/终态都不清 ⇒ 内存按历史 run 数线性增长，无指标无上限（与 RT-26 是两条不同口径） | 无 |
| **RT-34** | **会话锁续期失败/换主后静默退化为纯本地门，`degraded` 不置位** | **S3** | R | **V1** | `SessionGate:392-403`（`renewLockOnce` false ⇒ 只 WARN + 停续期，`holder.degraded` 不置位；**只有异常分支在 `:389-391` 置位**）、`:431`；类注 `:63-65` 承诺"降级事实写进返回体与 WARN，不静默" | 一次续期为 0（TTL 到期 / failover 丢写 / 被 release 删）⇒ 本实例继续驱动且只受进程内门约束，另一实例可正常取门 ⇒ 同 sid 两轮并发（ADR-5 破），而 409 体仍报 `degraded=false`、`/health.sessionLockRenewals` 停增 | `SessionGateLeaseTokenTest:117-135` 只断言返回 false 与不写 EXPIRE；无 `degraded` 传播用例 |
| **RT-35** | **`Busy.runId()` 诊断回退可等于调用方自己的 runId ⇒ 被读成"取门成功"** | **S3** | R | **V1** | `SessionGate:372-380`（读失败返回 `fallback` = 调用方 runId）、`:227-231`（此时本实例进程内门已在 `:222` 释放）；消费侧 `ResumeService:773-785`（`busy!=null && !runId.equals(busy.runId())`，**相等即当成功**且 `gateHeld=true`） | ACQUIRE 确定返回 0 且紧随的诊断 `GET` 抛错 ⇒ 续跑在没有跨实例会话门的情况下推进，与另一实例并发；`gateHeld=true` 掩盖失败 | 无该组合用例 |
| **RT-36** | **记忆写回守卫"检查一次再写"：purge 与 `saveAll` 交错 ⇒ 销毁后记忆复活** | **S3** | R | **V1** | `RedisChatMemoryRepository:309-313`（写前问一次 `isArmed`）、`:320-338`（随后 staging + RENAME）；**结构性窗口**：`ResilientChatService:1005` 写 DONE **早于** `:1006` `convergeMemory`，purge 的终局等待可在此窗口内完成删除 | 收尾 `saveAll` 通过检查 → 用户并发销毁（arm 后删记忆）→ RENAME 落地 ⇒ 销毁返回 `complete=true` 而 `chat:mem:{id}` 被重建，整轮正文再活一个会话 TTL；`deleteMemory` 只看"删没删"，不看"删完又出现" | `SessionPurgeGuardFailClosedTest` 只覆盖守卫读失败 |
| **RT-37** | **销毁面不含 `chat:mem:staging:*`；`ttl<=0` 时 staging 不设 TTL** | **S3** | C | **V1** | `RedisChatMemoryRepository:116-120,394-410`（`stagingTtlMillis` 对 null/0/负返回 0 ⇒ 不 PEXPIRE）；`AiSessionPurgeService:35,719-727` | ①`session-ttl<=0` + 保存任一步失败/进程在 STAGE_WRITE 与 RENAME 之间崩溃 ⇒ staging 键**永久**保留整窗消息 JSON；②TTL>0 时同样不被销毁面删（≤24h）。与类注"finally 的 DEL 失败也不会留下无 TTL 的孤儿"矛盾 | 无用例（`SaveAllEmptyTest`/`DeleteTest` 不查 staging 家族，无 `setTtl(0)`） |
| **RT-38** | **无 `seq` 的 legacy 帧在每次差量读里被无条件重发**（报告等级，**未独立复推**） | **S4** | C | **V7** | `RedisFrameStore:485-499`（`noSeq` 置于最前、不参与 `afterSeq` 过滤）、`:511-516` | 升级期遗留 LIST 未过期（TTL=会话 TTL）时，任何 `afterSeq>0` 的差量读/重挂都重复投递同一批老帧；前端按 seq 去重对无 seq 帧无效 ⇒ 重复渲染（重复 `tool_result` 卡） | `RedisFrameStoreTest:337-351` 只断言"保留"，未断言"不应重复" |
| **RT-39** | **自愈派发被拒后不落终态、不收流**：`PROVIDER_GONE` 只写日志，连接无 deadline ⇒ 半死轮（**候选新增分支；快照不可复现**） | **S2** | C | **V5** | candidate `AiController:766-793`（`triggerResumeForParked` 结局只 `log.debug`，随后 `return ResponseEntity.ok(emitter)`）、`:757`（`NO_TRANSPORT_DEADLINE`）；`ConfirmGate:1908-1917`（对 `PROVIDER_GONE` 仅一行 INFO）；对照类注 `ProviderGoneException:11-15`（必须落 `FAILED` + 机器可读码，不得停在非终态）。快照侧 `events()` 在该状态走 `completeSubscriptions()` 收流 ⇒ **本条不计入快照缺陷** | 决策后重挂只收到 seq5 `confirm_decision` 随后无帧；快照停在 SUSPENDED/APPROVED，直到实例重启由 `startup-resume` 收尾（实测 182s）⇒ 客户端 120s 超时；期间无任何"已失败"信号 | 无"provider 被拒/真被删 ⇒ N 秒内收尾"用例；现有 park 用例不覆盖自愈派发被拒 |

### 5.4 模型 / 记忆 / 历史 / 上下文（MD，21）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| MD-01 | 重试把 user 消息重复写进记忆并重复进请求；`fail()` 不收敛 | S2 | C | V1 | `AiModelConfig:77-81`；`AiProviderRegistry:308/341/415`；`ResilientChatService:458-471,683-706,1378-1405` | 第二次 attempt 同句出现两次；失败轮重复持久 | 服务级用例全 mock advisor |
| MD-02 | advisor 的 40 条裁剪不保 tool 配对 | S2 | R | V4 | `AiModelConfig:68-75`；`ResilientChatService:1638-1647` | 偶发整轮 `UPSTREAM_REJECTED`，错误码不指向历史结构 | 无真实 advisor+Redis 组合用例 |
| MD-03 | `max_context` 缺失静默 128000（"未知档"**默认**不可达，设 `0` 可达） | S3 | C | V1 | `ContextLimitResolver:72-125`；`AiProperties:1341`；`application.yml:168` | 32K 模型压缩永不触发；`/health` 反报 `compactionOn=true` | `ContextLimitResolverTest` 不覆盖该组合 |
| MD-04 | **上下文分母不同源**：续跑丢 providerId；`/context` 用上游回显模型名；`ContextState` 无 providerId | S2 | C | V1 | `ResilientChatService:2145` vs `:1978-1981`；`ContextBreakdownService:51`；`ContextState:37-39,78-101` | 首轮/续跑 percent 跳变；同名模型跨供应方取错行；显示错而帧对 | 51 个用例全走 properties 回落，DB 路径零覆盖 |
| MD-05 | 压缩摘要以 user 消息进 `/history`；**wrap-up 指令也持久化在 user 通道** | S2 | C | V1 | `RollingSummarizer:222-237`；`SpToolCallingManager:902`；`ResilientChatService:1420-1422,1446-1448`；`AiChatHistoryRecorder:177-186` | 用户看到没说过的话；下一轮模型收到"不要再次调用工具…"的 user 指令 | 无 |
| MD-06 | 工具执行期（≤120s）无帧，前端 45s 判断线 | S3 | R | V3 | `application.yml:206-210`；`ResilientChatService:714-721`；`sse.ts:31` | 每 ~45s 重连；多为体验退化非丢失 | 无慢工具用例 |
| MD-07 | `/history` 的 `hasMore` 方向依赖（before/after 两义） | S3 | C | V1 | `AiController:1268`；`AiChatHistoryService:305-311` | 当前无前端消费者；未来翻页客户端误判到底 | 测试与实现同源背书 |
| MD-08 | 配置行无 `@Version`/CAS；并发覆盖但两条审计都成功；重复 create→500 | S3 | C | V2/V7 | `db/AiProvider:33` 等；`AiConfigWriter:234-274,276-352` | 修改静默丢失、审计不可信；FA4 确认设置页既不读也不回传 `version`（展示侧证据） | 只覆盖单写者 |
| MD-09 | **种子/Flyway 回填不推进 `config.version`** ⇒ 跨实例快照永久停在**空 provider 表** ⇒ 续跑被**假判 `PROVIDER_GONE`** | **S2** | C | **V5** | 快照 `AiConfigSeeder:99,106`（只 `reload()` 本实例，无 `bumpVersion`/`INCR ai:cfg:ver`）、`:126-143`（直接 `saveAndFlush`）；对照正确写路径 `AiConfigWriter:686-698`（bump）+ `:711-723`（提交后 `INCR`+reload+`evictAll`）；读侧 `AiConfigService:218-229`（只按版本比对）、`:155-172`、`:244-255`；精确绑定 `ResumeService:740-747`；env 合成行 `AiConfigService:440-445` | 3 JVM 并发冷启动时 B/C 的首次 `current()` 早于种子提交（实测差 **23ms / 230ms**）⇒ 版本恒 1 且 `ai:cfg:ver` 为空 ⇒ **永不重载**；`/health` 因 env 合成行仍报可用（冷启动断言只看 `available===true`）⇒ **假绿**；park 续跑改走精确绑定 ⇒ 落空 ⇒ 409 `PROVIDER_GONE`。**这是误判**：同一 `providerId=1 / ci-stub` 在 182s 后被 A 正常续跑并执行成功。滚动重启场景同理（只一台生效） | 单测只验写库结果；cluster 冷启动只断言 `available===true`（§6.4-15），被 env 兜底掩盖 |
| MD-10 | 请求级 `modelId/params` 不可达；`/health` 不报配置版本 | S3 | C | V1 | `AiPanel.vue:936`；`ai.ts:2566`；`AiController:1579-1672` | 外部客户端会被解析到库序第一家；跨实例排查缺字段（FA6 确认类型面已退场） | `ai-panel-params-removed` 锁死"不带" |
| MD-11 | 会话 TTL 文档漂移（README 6h vs 24h，且只写时滑动） | S4 | C | V4 | `application.yml:146`；`README` | 运维估错销毁窗口 | — |
| MD-12 | 非流式/摘要路径无 HTTP 读超时 | S3 | T | V4 | `AiProviderRegistry:390-410`；`ContextSummaryClient:140-173` | 半开连接下摘要挂到父预算 | 无运行时断言 |
| MD-13 | `/history` Redis 故障返回 500（`/context`、`/events` 为 503） | S3 | C | V1 | `AiController:1256`；`GlobalExceptionHandler:320` | 不可重试语义、与类注契约矛盾 | 无该端点故障用例 |
| MD-14 | 反馈写面健壮性：超长 id→500；`findByMessageId→save` 非原子→500 | S3 | C | V3 | `AiController:1948-1976`；`AiFeedback:23-27` | "点赞偶发失败"、无解释 500 | standalone+mock |
| MD-15 | 刷新锚点用 `archiveMaxSeq`；镜像 flush 失效时有不可补缺口 | S3 | R | V4 | `ai.ts:2136,1475-1520` | 崩溃/配额后少最后一截 | 无 flush 失败用例 |
| MD-16 | `ToolResultClearing` 对短结果"清空后变长"，L1 可令 `after>before` | S3 | C | V3 | `ToolResultClearing:113-135`；`ContextCompactor:306-311` | 压缩收益虚报、触发线偏移 | 只用 ~150 字符语料 |
| MD-17 | 挂起条目读失败姿态不一致：轮内 fail-closed，预请求路径吞掉 | S2 | C | V3 | `ResilientChatService:2033-2061` vs `SpToolCallingManager:979-994` | Redis 抖动时未决轮工具结果可被清空/摘要 | 无 |
| MD-18 | 压缩状态 `HSET+EXPIRE` 无 CAS（**被 SessionGate 串行化**）；同轮归档键覆盖 | S3 | R | V1 | `RunStore:2715-2728,2813-2819,2671-2673`；`ContextCompactor:217-356,319-330` | 门 TTL 失效/接管时熔断计数丢失；首次压缩的"压缩前原文"被覆盖（取证损失） | `CompactionFuseTest` 两次调纯函数 |
| MD-19 | 计量 minor 包：`lostTokens` 含 L1、pin 窗口按 response 计数、摘要继承用户 maxTokens、`CONTEXT_OVERFLOW` 误分类、`percent=null` 但 `free=limit` | S3 | C | V3 | `ContextCompactor:312-314`；`ToolResultClearing:100-113`；`ContextSummaryClient:116-125`；`ContextOverflow:25-27`；`ContextBreakdownService:67-71` | 指标失真、偶发 L2/L3 降级、错误提示指向"开新会话" | 均无用例 |
| MD-20 | 配置面 minor 包：`AiUnavailableException` 无 handler、`reload()` 先 load 后读版本、`models-cache-ttl<=0`=永久、配置快照 3 查询仅单 load 内撕裂、`ResolvedProvider.toString` 含明文 key、KEK"轮换"=逐条重录、`done` 无 providerId、看门狗 javadoc 写"单线程"实为 2 线程 | S3 | C | V2 | `AiProviderRegistry:141-146`；`AiConfigService:175-181,257-276,1020-1024`；`AiModelCatalog:108-124`；`AiKeyCipher:52-56,190-193`；`SseChatEmitter:1329-1337`；`AiExecutorConfig:59-70` | 窄窗 500 而非稳定码；配置静默陈旧；一次 KEK 变更致全部 key 不可读；容量论证基于错误数字 | 多数无 |
| **MD-21** | **配置守卫覆盖不均：两处时序键无任何闸（已复推）；池容量/语义键属配置卫生（未独立复推）** | **S3** | C | **V1** | `AiProperties:234-654`（9 个 `@PostConstruct`）vs 无校验键：`session.lock.ttl/watchdog`（`:953-981`）、`lease.ttl/renew-interval`（`:1221-1247`）、`suspend.pool-size`（`:705`）、`sse.delivery-pool-size`（`:1008`）、`resume.trigger-pool-size/queue-capacity`（`:944-950`）、`resilience.max-attempts`（`:777`）、`max-tool-steps`（`:801`）、`input.max-chars`（`:1283`）、`resume.max-on-startup`（`:934`） | `watchdog>=ttl` 或 `ttl<=0`（`ttlMs()` 只 `max(1,…)`）⇒ 锁在两次续期间隙必然过期、几乎不存在 ⇒ 跨实例串行化**静默失效**（与 RT-34 叠加后完全无告警）；`renewInterval>=ttl` ⇒ 健康部署上每轮都丢租 ⇒ **RT-10 由配置触发**；池=0 ⇒ `SUSPEND_POOL_SATURATED`/订阅者被摘/`EXECUTOR_SATURATED`；`max-attempts=0`/`max-tool-steps=0`/`max-chars<=0` ⇒ 重试关掉/每工具 SKIPPED/每条消息 400。**已复推的唯一硬断言**是两条时序键（`session.lock.*`、`lease.*`）：`verifyLease`/`verifySessionLock` 均不存在（`AiProperties:1221-1247` 只有字段与 getter），`SessionGate.ttlMs():382-384` 只做 `max(1,…)`。池容量与 `max-attempts/max-tool-steps/max-chars` 的后果按报告保留（配置卫生级，本轮未复推）；`AiPropertiesT35DefaultsTest:19-20` 明确记载启动期容量联动校验是**刻意删除的过度设计**（R-148/B-3） | 全测试树**零**引用 `session.lock.*` / `lease.renew-interval`；`delivery-queue-capacity < EVENT_WINDOW` 只在默认值上被钉 |

### 5.5 业务交界 / 作业 / 文件 / KB（BZ，40）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| BZ-01 | **零条目 `start_*` 假成功**；EXPORT 还让任务永久 `TASK_FINALIZED` | S1 | C | V1/V7 | `JobService:156-165`（四守卫无可执行性）；`ExportJobRunner:112-121,281-292`；`PrecheckJobRunner:87-94`；`ImportJobRunner:71-78`；`PublishJobRunner:140-151` | 四链 COMPLETED 零数据；再导出 409 只能新建任务。**FA3 补两个前端可达入口**：FE-48（`GET /files` 失败 ⇒ 模板覆盖上传件 ⇒ PRECHECK 0 行）、FE-49（零暂存仍可发布 ⇒ 任务立即只读） | 无"0 条目"用例（FA3 确认 `AiToolsInvocationTest` 手写 `row_count` 是假绿） |
| BZ-02 | IMPORT/PRECHECK 缺提交期回滚/异常终态兜底 | S2 | C | V1/V7 | `ImportJobRunner:60,183-197`；`PrecheckJobRunner:65`；`AsyncJobExecutor:27-45`；`JobWatchdog:193-207` | 悬置 RUNNING 至 30m，期间互斥挡重跑。FA3 补机制：`ImportJobRunner.run` 是 `@Transactional`，catch 里的 `terminalWriter.complete` 走 **afterCommit** ⇒ 提交期回滚/commit 失败时终态写**不会执行** | EXPORT 有、此两处零用例 |
| BZ-03 | 作业期改条件被静默回退（长事务 + `TaskItem` 无 `@Version`） | S2 | R | V4 | `ExportJobRunner:112,230`；`TaskService:348-354`；`TaskItem:1-33` | 用户的修正消失，下次出口用旧条件 | 无 |
| BZ-04 | 任务态联动失败被吞 ⇒ 可二次发布 | S2 | R | V4 | `JobTerminalWriter:103-146`；`Task.java:37` | jobs 终态而 tasks 仍 ACTIVE | 只覆盖"取消胜出" |
| BZ-05 | 确认卡影响面失真（IMPORT 恒不可估、EXPORT 用上次行数） | S2 | C | V4 | `ToolPreflightService:210-240`；`FileController:145-161` | 用户在错误数字上批准 | 手工造 `row_count` ⇒ 假绿 |
| BZ-06 | 作业 FAILED 后 `job_items` 残留 RUNNING | S2 | C | V5 | `cluster3-probe.mjs:2866-2946` | "1/5 进行中"与 FAILED 矛盾 | main 有 job 候选（L-5）；`job-kill-a` 本轮未失败（≠ 已证明） |
| BZ-07 | 单编码下载返回再包一层 zip | S2 | C | V5 | `FileController:276-310` | 下载的 xlsx 打不开 | main 有 file 候选（L-5）；`shared-file-download` 本轮未失败（≠ 已证明） |
| BZ-08 | 进度帧先于数据面提交 | S3 | C | V4 | `ExportJobRunner:184-186` 等三处 | 回滚后仍显示过"已处理 500 行" | 无 |
| BZ-09 | `list_tasks` 两遍全量载入（EAGER items） | S4 | C | V4/V7 | `AiTools:176`；`ListTasksResultViewProvider:74`；`AiController:1451-1467` | 历史累积后变慢；**`/history` 对每个历史 `list_tasks` 各跑一次 `findAll`**（BZ-33 放大器） | — |
| BZ-10 | 超时"未执行"措辞与"可能已起作业"并存（重试可建第二个作业） | S3 | R | V4 | `SpToolCallingManager:1473-1484,1617-1643` | 台账与事实相反；极端下覆盖导出文件 | 无 |
| BZ-11 | AI 资源锁 60s 不续租、与业务守卫两套；假锁误导文案 | S3 | C | V4 | `ToolResourceLocks:127-171`；`SpToolCallingManager:1331-1332` | 秒级完成的作业后仍被劝退 | 只测三态 |
| BZ-12 | 旧绝对路径 `storage_path` 换挂载点不可读（迁移默认关） | S3 | R | V4 | `FileStorageService:109-133`；`application.yml:466-471` | 预检"未找到上传文件"，无法自愈 | 无 |
| BZ-13 | IMPORT 取消后暂存区"部分批次"无标记 | S3 | C | V4 | `ImportJobRunner:150-164,97` | 暂存预览看到半批数据 | 无 |
| BZ-14 | ZIP 上传无规模上限（整包入内存 + 全量解压） | S3 | C | V4 | `FileController:62-96,167-186` | OOM；同实例 AI/作业受牵连 | 无 |
| BZ-15 | KB HTTP 源无地址白名单；**跨主机 302 会重放 `Authorization`；正文/manifest 无大小上限** | S3 | C/R | V1 | `HttpKbSource:33,56,62-65,97-102,115-125,141-144` | 内容面：抓到内容进上下文；凭据面：KB 令牌交给重定向目标；可用性：超大一文档打爆堆（凭据重放取决于 JDK 行为与上游是否恶意/错配） | `KbHttpSourceTest` 无重定向/超大正文用例 |
| BZ-16 | manifest 合法但 `docs:[]` ⇒ 静默清空检索面并报 ready | S2 | C | V1 | `KbManifest:37-61`；`KbService:250,274-289,745-757`；`KbSampleSeeder:181-183` | 空索引、`no_match`、`docCount=0`、`lastError=""`；旧的不会再播种 | 无空清单用例 |
| BZ-17 | 仅改元数据 ⇒ 索引 title 新、`sectionPath` 旧 ⇒ TOC/read_doc 全失配 | S3 | C | V1 | `KbDoc:17-23`；`KbService:256-265,665-680`；`KbSection:31-37` | `SECTION_NOT_FOUND` + 候选（可恢复） | 无"仅元数据变更"用例 |
| BZ-18 | `match=high` 恒真（≥3 命中）；**`top_k<=2` 时反恒判 LOW** | S3 | C | V1 | `KbService:457-478` | 低相关被标 high；同一机制也能虚低 | 只测单命中/零命中 |
| BZ-19 | 分块器可产出超上限 chunk；chunk 偏移互相重叠且非真实偏移 | S3 | C | V3 | `MarkdownChunker:196-222,262-276` | 无现发消费者破坏（`KbText` 8000 闸仍在）；未来消费者会错位 | 只测等高行 |
| BZ-20 | `KbService.stop()` 后 `acquire()` 自旋；**停机后重建永不可能成功** | S3 | C | V1 | `KbService:131,154-158,172-186,792-801,301-308` | 窄可达（需停机期在飞调用）；`status()` 仍报旧快照 | 无 |
| BZ-21 | KB 多实例：本机盘当共享缓存源 ⇒ 版本乒乓；index 目录互删 | S2 | R | V3 | `CachedKbSource:129-135,254-262`；`KbService:311-329` | 反复重建、条目互覆；A 删 B 的 reader 目录 | 无多源用例 |
| BZ-22 | KB 明文结果无定界（候选/建议走 `plain`） | S3 | C | V3 | `KbTools:191-197,322-333`；`KbText:158-165` | manifest 可控文本进入模型，无外壳 | 只测 knowledge 两标签 |
| BZ-23 | 种子 provider 任一编辑即 `source=DB` ⇒ 私网探活被拒（**直到开开关或重新播种**） | S2 | C | V6 | `AiConfigWriter:292-294,329-331`；`AiConfigController:152`；`AiEndpointGuard:95` | 改显示名/key 后 `/test`、`/models/fetch` 全废；fail-closed 非 SSRF | `AiConfigWriteEndpointTest` 只测未编辑行 |
| BZ-24 | 配置审计无法独立回答"端点变了吗"（无前像、无"key 复用"标记、`enabled/max_context` 不入库、`actor` 恒 `local-user`） | S4 | D | V2 | `AiConfigWriter:340-347,474-476,730-738` | ID-03 事后不可发现/不可取证（"无主体流水"已裁决） | — |
| BZ-25 | **级联 enum/multi_select 在父值缺失或无匹配分支时静默放行任意值** | S3 | C | V1/V7 | `GenerativeFormRules:1149-1153,1177-1181,1350-1375`（注释 `:1343-1349` 自认应判 `VALUE_OUT_OF_OPTIONS`）；前端同源 `form-schema.ts:540-560,740,748-750` | 父字段被 `visibleWhen` 隐藏（对抗路径）；**FA4 给出非对抗路径**：父值 P1 选子 C1 → 改成 P2（无该分支）⇒ `<select>` 显示空白但 `model` 持 C1 ⇒ 提交用户看不见的值 | 只测"父值命中"一条正负例 |
| BZ-26 | **数值 `step` 校验可抛 `NumberFormatException` 出闸门** ⇒ 500，且 schema 期不判有限性/上界 | S3 | C | V1 | `GenerativeFormRules:1106-1114,597-603`；`GenerativeFormGuard:125`；`ConfirmGate:1516-1522` | `min:-1e308`+值`1e308`，或 `step:1e999` ⇒ 任何提交 500；后者使该表单本轮**永久不可提交**（pending 仍开 ⇒ 只能取消整轮）。**前端不是第二故障点**（R-14） | 只用 `step:0.5` |
| BZ-27 | **KB 版本轮询先记已见、后重建** ⇒ 一次失败吃掉该版本 | S3 | C | V1 | `KbService:203-213,154-158,161` | 之后 30s 轮询直接 return，旧索引服务到下次 bump 或 10m 定时（与 `AiConfigService` 的"失败下一拍重试"纪律相反） | 无用例引用版本字段 |
| BZ-28 | **`ai_setting` 一行 NULL ⇒ `Map.copyOf` NPE ⇒ 配置加载永久失败** | S3 | R | V1 | `AiConfigService:263-265,1020-1024,269-275`；`V4:52`（无 NOT NULL） | 热态：所有变更不生效；冷启：误报"AI 未启用"；无行标识、`/health` 不带该错误 | 无 NULL 行用例 |
| BZ-29 | **写路径只认 DB 行、读路径认 env 合成行** ⇒ 设置页"列得出、存不进" | S4 | R | V1 | `AiConfigService:436-445,463-477,747-774`；`AiConfigWriter:586-611,625-629,359-381` | 种子失败/硬删最后一家后，UI 保存 `MODEL_NOT_FOUND`；"只能手工改库"已否证（可 create+addModel） | 无"空表+env key"用例 |
| BZ-30 | **同名标题的 `read_doc(section=)` 静默取第一个** | S4 | C | V2 | `KbService:666-688` | 返回可能错的那处，`SECTION_NOT_FOUND`+候选这条安全路径不触发；与"路径后缀"路线行为分叉 | 无同名标题用例 |
| **BZ-31** | **向导动作闸只判"有值/有键"，不判"空串值/同字段重复" ⇒ 条件被压平后"无条件 = 导出全部"** | **S3** | C | **V1** | `WizardActionGuard:277-341`（`value` 为空串直接通过；`conditions.fields` 不做 fieldCode 去重，而 `codes` 有去重 `:179-182`）；前端压平 `types/condition.ts:166-196,413-441`（覆盖式写入、GTE+LTE 合并要求大小写精确）；回写 `ExportWizardView:686-700,772,1464` | 模型对 `set_condition` 发①同一 `fieldCode` 两条（`EQ`+`NE`，或小写 `gte`+`lte`）或②非无值算子配 `"value":""`；随后推进/发起导出 ⇒ 只落第一条或整条丢弃 ⇒ `conditionJson={}` ⇒ **导出全量**。这正是闸门明文要堵的语义从另一个入口复发；回执说"已设置并落库"（摘要用未压平条件） | `WizardActionGuardTest:120-125` 只覆盖 `value` 缺失与 `IN` 非数组；前端无重复字段往返用例 |
| **BZ-32** | **`list_tasks` 的"前 20 条"承诺与 `model.max-rows` 之间无启动不变量**（与 MD-21 同源；**未独立复推**） | **S4** | R | **V7** | `AiTools:165-169`（javadoc 自认"必须 < max-rows(200)，否则告知成了假话"）、`:182-183`；`ToolResultLimiter:154-160`；`AiProperties:357-360`（只校验 `>=0`）；`application.yml:352` | `max-rows` 合法配到 1..19 时，工具自述与工具描述里的"最多前 20 条"永远到不了模型（模型看到裁剪器的"仅前 N 行"）；`LIST_TASKS_MAX_ROWS` 是编译期常量，无配置面联动/校验 | `AiToolsListTasksTruncationTest` 锁 20/21 边界但不断言与 `max-rows` 的关系 |
| **BZ-33** | **`/history` 的 `list_tasks` 结果视图每次重建都重取实时库** | **S3** | C | **V1** | `ListTasksResultViewProvider:69-83`（`build` **完全不看 `resultText`**，`taskService.findAll()` 重取）、`:77,83`；`AiController:1451-1467`（每次 `/history` 对每个历史 `list_tasks` 重新 build）；对照 `SpToolCallingManager:1543-1553` | 一次 `list_tasks` 之后任务被新建/改名/推进/删除，刷新对话 ⇒ 归档表格显示**今天**的任务行，与同轮 assistant 文本/台账不一致（体验同 RT-20，但根因是展示层重投影）；`meta.truncated{shownRows,totalRows}` 随之重算，"共 N 条"可与当时所说不同；附带每次 `/history` 各跑一次全量 `findAll` | `ListTasksResultViewProviderTest` 全是单次静态 mock |
| **BZ-34** | **建作业守卫按"任务全部历史作业"逐个打 Redis（行锁内，无可用性快速路径）；故障文案复用 `JOB_ALREADY_RUNNING`** | **S3** | C | **V1** | `JobService:292-310`（遍历**全部** job 并调 `isRunnerAlive`，位于 task 行锁/事务内）；`JobHeartbeatRegistry:340-368`（内存命中或一次 `hasKey`）；不可用分支 `:298-303` **已**提示"Redis 不可用" | ①每次 `start_*`（全部 AI 作业工具与 REST）产生 N 次同步 Redis 命令并持 task 行锁 ⇒ 延迟随历史作业数增长、阻塞删除/并发建作业；②Redis 不可用时所有建作业请求失败，且错误码/前端映射把它读成"旧作业正在收尾"（文案本身已提示 Redis，但**码被复用**）⇒ 客户端按冲突重试；③无指标/健康面（对比 `RedisTaskEventBus` 的 `redisKnownDown`+`metrics()`）。**降 S3 的理由**：正确性（fail-closed）无误，实质是延迟与错误码复用 | `RunnerActiveMultiInstanceTest` 等用 mock Redis（TTL 不生效、无命令超时） |
| **BZ-35** | **非对称分区下 `STALE` 会杀活 runner 并释放闸门 ⇒ 双写者**（**PARTLY**：判死语义是设计，新增部分是"写侧分区与真死不可区分"） | **S3** | R | **V1** | `JobHeartbeatRegistry:409-416,625-634`（写失败仅 WARN、下周期重试）、`:462-488`、`:312-322`；`JobWatchdog:247-259,352-357`；`JobService:293-310` | A 到 Redis 的写失败（ACL/单向丢包/切换期）>90s，B 读正常 ⇒ 判 A 的作业"死"⇒ 写 ERROR issue + 释放闸门；A 的 runner 也读不到 `job:cancel:` ⇒ 不在分片边界止步 ⇒ **同任务两个写者**、staging/文件互覆；用户看到"失败"但数据在变 | 该族用例全是同 JVM 两 registry + mock Redis；`cluster-3node` 不注入写侧分区（本轮 run 亦无该注入） |
| **BZ-36** | **任务 SSE 顺序扇出：调用线程占用 = k×预算；同一 emitter 可被并发写** | **S3** | C | **V7** | `TaskSseService:250-285`（逐 emitter 同步等预算）、`:446-462`（每帧 15s）、`:43-45`（自述"最多一个预算"）；`JobService:546-552`；`SseWriteBudget:99-102` | k≥2 订阅者且至少一个"不读也不断开"⇒ 一次 `publish` 占用作业/取消 HTTP 线程至多 `k×15s`，分片节拍被拖慢；滞留写线程 ≈ k×并发发布者；弹性池 `max=Integer.MAX_VALUE`，单线程最长挂到容器 120s | 无"≥2 滞留订阅者"用例 |
| **BZ-37** | **跨实例发布是作业线程上的同步 Redis 命令，无预算** | **S3** | C | **V7** | `TaskSseService:192-203,288-293`；`RedisTaskEventBus:114-147`（`convertAndSend` 同步） | Redis 延迟升高（未到失败阈值）时每个分片边界被一次 RTT 阻塞 ⇒ 作业总时长随延迟线性增长；`SseWriteBudget` 的 15s 预算对这条路径**完全不适用** | 假总线/mock 模板，不体现 RTT |
| **BZ-38** | **`runner-active` 残留锚点把"新建作业"挡到长 TTL；同主机清理在容器化部署恒不匹配** | **S3** | C | **V7** | `JobHeartbeatRegistry:91`（TTL=2×max-exec+5m）、`:296-301`、`:681-722`、`:568-593`（只认同主机且本地 PID 不存在）、`:729-735`（`hostName()` 失败 ⇒ `"unknown"`） | 实例在"终态已写、`endRunner` 未执行"窗口崩溃（或删键失败），后续请求落到另一台主机（k8s pod 重启主机名变化 ⇒ 启动清理永不匹配）⇒ 该任务所有新建作业/删除被 409 挡住，最长 65m（max-exec=2h 时 4h5m），无可操作提示。附带 fail-open：`hostName()` 抛异常时全实例变 `"unknown"` ⇒ 可能删掉别人仍在跑的锚点（闸门提前释放） | `RunnerActiveTtlConfigTest` 只测 TTL 派生 |
| **BZ-39** | **`createAndStart` 无"任务类型↔作业类型"一致性校验（REST 面可跨类型建作业）** | **S3** | C | **V7** | `JobService:156-230`（四守卫无类型校验）；`JobController:29-30`（`JobType.valueOf(body)` 直接透传） | `POST /tasks/{id}/jobs {"jobType":"IMPORT"}` 打在 EXPORT 任务上（或 PUBLISH 打 EXPORT）⇒ staging/发布数据写进类型不符任务；`finalizedConflict` 与前端 `isReadOnly` 判错（如 EXPORT 任务被 PUBLISH 置 COMPLETED 后不进 `TASK_FINALIZED`），两向导对同一任务的步骤/只读判断互相矛盾 | AI 路径有 `ToolTargetResolver` 用例；REST 无类型矩阵 |
| **BZ-40** | **作业/Excel 次要包**：SSE 订阅无存在性校验与上限、`_meta`/`_` 前缀表名未排除、空行跳过致行号漂移、DATE 单元格无格式化、`import.syncJob` 只认最新作业 | **S3** | C | **V7** | `TaskSseService:159-179`；`RedisTaskEventBus:170-200`；`excel.ts:46-49,163-201,119-155,457-481`；`ExcelReader:85,143-152`；`ImportWizardView:541-560,1288-1311` | 随机/已删 taskId 反复订阅各占 5m emitter + 一个频道监听、无回收上限；定义名 `_meta`/`_dict` ⇒ 同名表 + Excel"修复"提示、POI `getSheet` 变次序相关；中间空行后"第 N 行"与后端 `issue.rowIndex` 不对齐；真 Excel 日期单元格 ⇒ `validateCell` 不过 ⇒ `flushEditors` 跳过该项 ⇒ `goStep` 中止"存在未写回的编辑内容"，用户无自解路径；同类型存在更新作业时 AI 起的作业被判"未找到" | 均无对应用例 |

### 5.6 前端（FE，73）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| FE-01 | 非安全上下文复制页签共用同一会话 | S2 | C | V1 | `ai.ts:419-427,4588-4590`；`vite.config.ts` host 0.0.0.0 | 消息不同步、互抢 409、"新建对话"互毁 | 用例全在 `127.0.0.1`；main 有加固候选（L-6） |
| FE-02 | 多用户隔离不存在；"关闭页签即结束"文案不符 | S2 | D | V3 | `api/http.ts:133-147`；`zh-CN.json:407` | 共用工作站换人即见前一人对话 | 无（不可有） |
| FE-03 | 镜像无上限、正文与投影双份、写失败静默 | S2 | R | V4 | `ai.ts:818-855,1483-1524` | 配额击穿后 park 轮正文整块丢失 | 无 |
| FE-04 | 410 静默清空整屏且不提"历史已没" | S3 | C | V4 | `ai.ts:2593-2604,4521-4527` | "对话突然清空" | 把清空当期望 |
| FE-05 | `start` 帧前点停止 ⇒ cancel 上一轮 runId | S3 | C | V1 | `ai.ts:4343,4390-4398` | 本轮无 runId 可取消 | `concurrency-matrix` 同步发 start |
| FE-06 | "静默续收"先清空再回放、归档取不到无回滚 | S3 | R | V4 | `ai.ts:2191-2201` | 空气泡 + "结果可能不完整" | 无 |
| FE-07 | 慢重载降级后不重认领；`pagehide` 兜底会在文档存活时放锁 | S3 | R | V1 | `ai.ts:596-603,4607-4609,4634-4657` | 两页签又共用会话 | 桩里文档销毁即时；main 有加固候选（L-6） |
| FE-08 | 复制/接管继承未提交表单草稿 | S3 | C | V4/V7 | `form-draft.ts`；`ai.ts:2340-2342` | 他人/他页签看到填了一半的值；FA6 限定：草稿值经 `initialModel` 按字段类型形状 + 枚举合法值过滤 ⇒ 只泄露同名字段填值，不构成 schema 绕过 | 只测同页签刷新 |
| FE-09 | 单槽通知互相覆盖 | S3 | C | V4/V7 | `ai.ts:1222-1230`；`error-card.ts:44-49,140-144` | "连接中断"顶掉"没发出去"；FA5 补：`SESSION_BUSY/SUSP_POOL_SATURATED/AI_UNAVAILABLE/AI_REDIS_UNAVAILABLE` 被**明确排除出卡** ⇒ 这四类只存在于单槽 notice、历史零痕迹，刷新后 409/503 的解释整条消失 | 各断言自己那条 |
| FE-10 | 空态建议词绕过可用性闸 | S3 | C | V4/V7 | `AiPanel.vue:1517-1522,1534-1557`；`ai-suggestions.ts:27-32,66-112` | 必然 503 的按钮仍可点（`SuggestionContext` 无 `available`，`followups` 闸不判 `ai.available`） | 无 |
| FE-11 | `onMounted` 的 `if(!messages.length)` 跳过历史对账 | S3 | T | V4 | `AiPanel.vue:3638` | 未来多面板/HMR 时上下文不同步 | 无 |
| FE-12 | `parts.ts` 防御只有纯函数 4 例，无 store/浏览器级回归锁 | S2 | T | V3 | 新增 spec 4 例 | 补丁修的真实缺陷在门禁里无回归保护 | 未运行 |
| FE-13 | 多页签/多用户真机矩阵缺失（3+、判定窗口、同卡并发） | S2 | T | V3 | `13-nfezui`（仅该域） | 只在 jsdom 与单域真机验证 | — |
| FE-14 | 业务态跨页签同步不存在 | S2 | C | V6/V7 | 全仓 `BroadcastChannel` 仅 `ai-session-ownership`；`publish-links.ts:16` 是模块级 Map | 预览/撤销、上传、草稿、作业 watch 各自为政；AI 发起的发布冲突明细只能落到发起页签 | 无 |
| FE-15 | 设置页不注销工作区上下文 ⇒ AI 可写"看不见"的任务 | S2 | C | V4/V7 | `router:54-66`；`AiSettingsView:20-25`；`workspace:471-509`；`AiPanel:3628,3141` | 模型被披露成"仍在向导"并可 `PUT` 成功；**I-10 并入**：设置页保存后整个会话面板的 model/health 标签不刷新（面板不随路由重挂、只有 5 个错误码后重探） | 无 |
| FE-16 | 导入向导同路由换任务不回收作业跟踪/作业位/编辑区/上传态 | **S2**（机制确认；影响 S1 级但前提门控） | C | **V1** | `ImportWizardView:1665-1679`（watcher 只做 `onSheetPageLeft/setTaskId/loadTask`）、`:523-538`（`loadUploadedFiles` 只增不清）、`:700-732`（`loadEditorsNow` 在 `stepIndex!==0` 时**直接 return** ⇒ 对停在 2/3 步的任务，`loadTask`→`watch(selectedDefs)`→`loadEditors(true)` 是 **no-op**）、`:789-822`（`flushEditors` 对**所有**挂载表 `saveFile(taskId.value,…)`）；`FileController:210-230`（不存在的 file 会新建行） | ①旧作业进度/冲突挂新任务、watcher 泄漏、误只读；②`uploadedFiles/savedDefs/editorLoaded` 不清 ⇒ 标签页显示 A 的"已上传/已保存"、编辑区是 A 的行数据；③推进时按**新** taskId 写回 ⇒ B 的 UPLOAD 件被创建/覆盖为 A 的内容，随后 PRECHECK/IMPORT 消费它；`sheet.current` 把陈旧表交给 AI。**前提（裁定明确）**：两个任务共用同一 defCode，且切进 B 时 `currentStep≠0`（或 B 的文件下载 404），再回到第 1 步推进 ⇒ 这是 S1 级影响、但有前提门控，故定 **S2**。**范围**：只适用导入向导（导出侧有 `resetTaskScopedState`+`stopJobWatchForTaskSwitch`） | `import-watch-recycle` 用自建接线、不挂视图 ⇒ 写入面**没有任何挂载视图的用例** |
| FE-17 | **409 后不重挂且可能永久冻结**：一次性 3s 权威检查，仍 PENDING 则卡死 | S3 | C | V1 | `ai.ts:3119-3129,2939-2946,2959-2981`；`AiPanel.vue:589-600` | 双按钮永久禁用、输入锁死，只能停止/刷新（需 409 后 `/runs/current` 仍报 PENDING 的滞后读） | `r166-gate-link:220` 只覆盖"判决到达" |
| FE-18 | AI 发起的 `start_publish` 不绑页面作业面板 | S2 | C | V4/V7 | `ai.ts:812-816,5326-5382`；`ImportWizardView:1288-1311` | 页面"未开始" vs 对话"已启动"（FA5 确认另一条 `run_job` 路由**会**绑面板，二者并存） | 只覆盖三件套 |
| FE-19 | 并发发布单槽丢弃较早冲突（测试已固化） | S3 | C | V4/V7 | `task.ts:113-116,298,352-355`；`workspace.ts:75,551-552` | 失败完全静默；**第二处单槽见 FE-72**（同任务重跑也命中） | 断言"丢弃"为预期 |
| FE-20 | 作业终态/冲突不回灌本轮模型；无取消作业工具 | S3 | D | V4/V7 | `workspace:283-302,405-409` | AI 最后一句话永远是"已启动"；FA5 部分缓和：唯一回灌口是发布冲突，EXPORT/IMPORT/PRECHECK 终态无通道 | 协议无该通道 |
| FE-21 | **`error` 终帧不重置 `suspended`**（与 `done` 不对称） | S3 | C | V1 | `ai.ts:5187-5206` vs `:5146-5185`（`:5159`）；`:2437-2444,2629-2633`；`AiPanel.vue:1580-1586,947-959` | 恢复连接上收到 error ⇒ 输入框硬禁用 + 幽灵停止键；跨实例无自愈（`stop()` 可复位） | `parts-residue-cleanup:126-145` 正是该帧序但不断言 `suspended` |
| FE-22 | **恢复路径多卡队列：下一张非 CONFIRM 卡提交不重连** | S3 | C | V1 | `ai.ts:2987-2992,1387-1399,2358-2360,2461-2463` | 卡 2 可见可填，提交后无流；队列第 3 张永不出现；下一次发送 409 | `restoredPendingQueue` 零引用、零用例 |
| FE-23 | **上传交接在选择器打开期间跨过轮次终止** ⇒ 把文件交给向导并把 `cancelled` 段改 `succeeded` | S3 | C | V1 | `ai.ts:3567-3639,3724-3754`；`parts.ts:91-104`；`UploadHandoffCard:33` | "已停止"轮显示成功上传卡；向导被改；模型只见 `FRONTEND_TIMEOUT`（文件去向正确，无资产丢失） | 无"选择器打开时轮次结束"用例 |
| FE-24 | 折叠→展开后挂载期绑定仍指向已分离节点（RO/表头同步/mermaid 观察器） | S3 | T | V2 | `AiPanel.vue:3-14,3620-3653,3641-3648,2563-2586,3578-3584` | 表头不跟随横向滚动、插入不重新贴底、**mermaid 永不再渲染**（**未验证，勿据此派工**） | 无用例切换 `expanded` |
| FE-25 | 终帧只收 `activeForm`，预览/上传单例与 `stalePreviewToolCallId` 残留 | S3 | C | V2/V7 | `ai.ts:5146-5206` vs `dropSheetPreview` 仅 `stop/adoptFreshSession/page` 调用；`:3674-3687` | 琥珀高亮留在表上无卡可清；后续 `notifyAiUploadDone` 仍打已结束轮；**FA4 补**：`uploadHandoffAvailability()` 只返回 `waiting\|missing\|degraded\|null` ⇒ 组件声明的 `unavailable`（"任务已结束，仅供查看"）守卫**无生产者** ⇒ 卡面按钮在已收口轮次仍可点 | `sheet-preview-cleanup` 无 `done/error` 用例；`ai-upload-handoff.spec.ts` 无"轮次已结束"用例 |
| FE-26 | **`/history` 刷新失败直接 return，完全不重挂** | S3 | C | V1 | `ai.ts:1713-1722`（return）vs `:1747,2002`（重挂入口） | 气泡永久 `streaming`、无提示、composer 可用但发必 409；连 `/runs/current` 都不再查询 | 无"在飞轮 + history 失败"用例 |
| FE-27 | `buildContext()` 在 POST **之前**被消费并清空 | S4 | C | V2 | `ai.ts:2563-2564` vs `:2582,2588-2623` | 409/410/503 后重试丢失 `recentActions` | 只测成功注入 |
| FE-28 | `canSend`/`send` 只判 `loading`，不判 `suspended` | S4 | C | V2 | `ai.ts:1441-1443,2487-2493`；`AiPanel.vue:1517-1522,3219-3221,1424-1436` | "继续"/错误卡动作打出必然 409 的死路按钮 | 无 |
| FE-29 | 英文界面渲染中文业务标题/步骤名；拒绝原因硬编码中文进记忆/提示 | S4 | C | V2/V7 | `AiPanel.vue:2846-2856,3528-3531`；`format.ts:90-113`；`tool-summaries.ts:70-73,124-130`；`workspace.ts:126-139`；`ContextPopover` | 英文会话里出现中文卡头/中文"用户拒绝执行"；**FA5/FA6 补三处生产点**：`create_task` 摘要 `Create 导入 task`、`contextSummary` 的 `stepLabel` 插值、`ContextPopover` 硬编码"上限未知" | 只钉 zh 快照 + `start_export` 的 en |
| FE-30 | 普通工具卡参数不掩码（仅门卡按正则掩码） | S4 | D | V2/V7 | `AiPanel.vue:2913-2916,3105-3109`；`blocks.ts:139-140,150-178,201-209` | 密钥形状参数明文上卡并进 per-tab 镜像；**收窄（N-2）**：对象渲染为"（复杂值）"、路径→文件名 ⇒ 实际暴露仅"顶层标量 + 键名不命中正则"；**正则缺口另立 FE-64** | 只覆盖门卡掩码 |
| FE-31 | 赞踩按**点击时**的 `sessionId` 绑定 ⇒ 归属漂移 | S4 | R | V2 | `ai.ts:4474-4480`；`AiPanel.vue:3263-3269` | 410/新建会话窗口内提交 ⇒ `{messageId,sessionId}` 不一致 | 无 |
| FE-32 | **DOM 快照/写动词不区分"在 DOM 里"与"在用户眼前"；区域侧 `allowSteps` 是死闸** | S2 | C | V1/V7 | `ai-dom-tools.ts:562-571,389-418,981-987`；`ai-regions.ts:49-51`；两个向导全 `v-show` 且注册未声明 `allowSteps`（`ImportWizardView:19,100,134,152,1531-1600`；`ExportWizardView:19,42,72,1682-1705`） | 第 4 步可 `ui_fill/ui_click` 第 1 步隐藏控件；六道围栏全放行；回执"已点击/已填写 + 界面已同步"而屏幕未变。**限定（N-3）**：零 `allowSteps` 注册只针对 `AiRegion.allowSteps`；`WorkspaceAction.allowSteps`（`frontend-tools.ts:722,988`）是活闸 | `ai-region-batch-b:172-201` **把该行为写成期望** |
| FE-33 | **`ui_snapshot` 按 (epoch,args) 缓存，写动词不失效（**两层**）** | S2 | C | V1/V7 | 层①：`ai-dom-tools.ts:119,531-535,588,1266-1277,1374-1387,1461-1488`；层②：`frontend-tools.ts:579-591,600,628-635,646-668`（同参去重命中直返"动作前快照"） | 动作后重拍逐字返回动作前画面 ⇒ 模型判"没生效"再点一次 ⇒ 勾选被点回原态；"可再 ui_snapshot 确认"结构性不可兑现（`beginUiTurn` 不清缓存；`bumpAiEpoch` 生产零调用）；**两层是不同代码、不同修复点** | 测试自己 `bumpAiEpoch()` 绕过；`ai-snapshot-dedupe-epoch.spec.ts:58-70` 把去重窗口写成期望 |
| FE-34 | **同一计划内重复目标列** ⇒ 格数虚增 + 该层永久不可撤且被误归因 | S3 | C | V1 | `sheet-tools.ts:1951-1983,2010-2021,2077-2118,2601-2624,2666-2682` | `set` 两条同列 / `columnCodes` 重复 ⇒ 一格算两格；撤销恒冲突并报"被你手动改过"（需模型产出两条同列 `set`） | 无重复列用例 |
| FE-35 | 已应用摘要不随换标签/离页/程序化重载失效 | S3 | C | V2 | `ai.ts:3984,3456-3466,4043-4109,4504-4556`；`AiPanel.vue:2762-2773` | 卡面仍显示"AI 改动 N 格（可撤销）"，撤销却答"没有可撤销的" | 无换标签/重载摘要用例 |
| FE-36 | 表格读侧规模包：分布分组不按 20 截断、`review_cells` 先整表物化再过滤、`sheetManualChangeCount` 每次击键 O(已应用格) | S3 | C/R | V2 | `sheet-tools.ts:1663-1746,1260-1267,867-893,2504-2559`；`SpreadGrid.vue:272-279` | 高基数列撑爆内存并全量排序；3 行选择物化 30 万行；每击键 N 次 `getCell` | 夹具都是个位数行 |
| FE-37 | `normalize_column(commit=false)` 回 5 处单元格原文但**不带 `sheetMeta`** | S4 | C | V2 | `sheet-tools.ts:2322-2351,810-812`；`frontend-tools.ts:1216-1230` | 有采样零计量，R3/D2 体积核算不可复核 | 无该路径 `sheetMeta` 断言 |
| FE-38 | 分块写入中途抛错 ⇒ 半批已落盘、无撤销条目，回执却报失败 | S3 | R | V2 | `sheet-tools.ts:2601-2603,2604-2627`；`SpreadGrid.vue:641-678` | 表已改一半、无法撤销、模型告"未应用" | 假适配器不会抛 |
| FE-39 | "只走屏幕绘制、不写 cell style"与实现不符（真实适配器写 `backColor`） | S4 | C | V1 | `sheet-tools.ts:91-102,2625-2627`；`SpreadGrid.vue:683-736` | 当前无外溢（导出只读值）；`toWorkbookJson` 已 expose，未来另存会把高亮写进文件 | 假适配器上该断言结构上不可能变红 |
| FE-40 | `ui_select` 空回读即成功；`applyUiUndo` 的 select 分支 fire-and-forget | S3 | C | V2 | `ai-dom-tools.ts:1479-1482,1422-1432,1617-1624` | 未验证却回"已选择"；撤销未生效却回"已撤销"，reject 变未处理 | 只测原生 radio / fill 撤销 |
| FE-41 | 流式"未闭合围栏"与尾围栏指纹只认**顶格 ```** | S3 | C | V2 | `markdown.ts:197-200,917-925,362-368,901-907` | 引用块内或 `~~~` 围栏不受保护 ⇒ 流式期闪烁/误高亮 | 只测顶格围栏 |
| FE-42 | `data-fence-key` 是**模型可写**属性，可碰撞并劫持 mermaid 槽位 | S3 | C | V2 | `markdown.ts:110-124,344-350,890-897`；`AiPanel.vue:2594-2610` | 模型指定元素偷走 SVG，真实 mermaid 块停源码态（非 XSS） | 无 |
| FE-43 | 2000 字符截断标记把"元素个数"写成"行"；同一 `data-ai-ref` 跨区域时围栏取"先注册区域"（审计仅 DEV） | S4 | C/R | V2 | `ai-dom-tools.ts:838-844,965-990,1017`；`ai-regions.ts:238-243,393-401` | 模型对"看全没有"失真；重复 ref 时 `mutate-local` 区域可让 `consequential` 元素通过并执行，生产无信号 | 只断言长度/审计函数本身 |
| FE-44 | 高亮定时器提前清掉后一次高亮 | S4 | C | V2 | `ai-dom-tools.ts:1541,1572,1574-1576` | 连续标注时第二个可能只剩几百毫秒 | 只测单次 |
| **FE-46** | **陈旧作业引用 ⇒ 假成功回执**（导出 `export.start` 复用旧 `exportJob`；导入 `startPrecheck`/`startImport` 读未清空的 ref） | **S3** | C | **V1** | `ExportWizardView:775-787`（只在成功时赋值、失败/被守卫拦下**不清空**）、`:1485-1492`（返回 `exportJob ? …已启动`）；`ImportWizardView:1442-1458`（`startJob` 返回后直接读 `checkJob/importJob`）、`:963-984`（catch 返回 `null` 但**不清 ref**） | `createJob` 失败（同类互斥 409 / `PRECHECK_NOT_PASSED` / `TASK_FINALIZED` / 池拒绝）或 `selectedDefs` 为空被拦 ⇒ 回执"作业 #N 已启动"（N 是**上一次**）⇒ 模型宣布"已启动/不必重试"，实际一个新作业都没有；用户侧只有一条错误 toast。**同一机制的导出/导入两个入口**（FA4-03 + FA3-N3 合并） | 页面 handler 失败分支无用例（`export-wizard-step-outcome` 未列 `export.start`） |
| **FE-47** | **勾选配置项（含 AI `select_definitions`）强制重载所有编辑区，静默丢弃未写回编辑** | **S3** | C | **V1** | `ImportWizardView:1651-1655`（`watch(selectedDefs)`→`loadEditors(true)`）、`:1406-1418`、`:700-732`（对**所有** `editorDefs` 重新 `loadFromXlsx/loadFromJson`） | 第 1 步手改 A 的表格未保存，随后勾选/取消任意项或模型 `select_definitions` ⇒ 编辑被服务端文件/空模板覆盖，无提示、无 flush；后续"下一步"写回被覆盖内容。**降 S3 的理由**：改动从未保存，可恢复（重填），不是数据丢失 | 无"编辑后改勾选"用例 |
| **FE-48** | **`GET /files` 静默失败 + 第 1 步无条件写回 ⇒ 用模板覆盖既有上传件；无文件项被创建 0 行件** | **S2** | C | **V1** | `ImportWizardView:523-538`（`catch {}`）、`:714-724`（无 `uploadedFiles[code]` 回落 `buildTemplateWorkbookJson(...,sample:false)`）、`:789-822`（无条件 `saveFile`）；`excel.ts:457-481`（空行数组 ⇒ 仅表头） | ①正常路径：选中 4 项直接点下一步 ⇒ 未上传项被创建为 **0 数据行**的 UPLOAD 件；②异常路径：页面加载时 `GET /tasks/{id}/files` 超时/500（一次即可）⇒ 已上传项编辑区回落到模板 ⇒ 推进即把该件**覆盖为仅表头**。后果：上传数据静默替换、PRECHECK 0 行 0 错误 ⇒ COMPLETED ⇒ IMPORT/PUBLISH 全链"成功但零数据"，原上传件不可恢复（覆盖同一 storage 键） | 无"files 拉取失败后禁止写回"用例 |
| **FE-49** | **`excel.ts` 的 NUMBER 规范化静默改写数值（前导零 / ≥2^53）** | **S2** | C | **V1** | `excel.ts:64-82`（`Number(text)` → `String(num)`）、`:344-358,361-412,496-506`；消费者 `SpreadGrid:478-501`、`ImportWizardView:818-819` | 某 NUMBER 字段承载零填充编码（`007`、`001234`）或 ≥16 位（订单号/手机号/身份证）⇒ `007→7`、`12345678901234567890→12345678901234568000`；写回文件（第 1 步推进/flush）即固化，本地校验（`Number.isFinite`）通过、无告警无痕迹，PRECHECK/IMPORT/导出全部按改后值落库 | excel 用例只覆盖校验器/zip/文件名，无精度与零填充断言 |
| **FE-50** | **未加载的结果标签页被当成"当前内容"：`保存修改`/下载用空工作簿覆盖服务端导出文件** | **S2** | C（前提见注） | **V1** | `ExportWizardView:471-494`（只对首个标签 `ensureGridLoaded`）、`:896-941,967-977`（只判 `gridRefs[code]` 存在）、`:1155-1188`（无条件 PUT）、`:1095-1147`（打包下载）；`SpreadGrid:96`（`fields=[]` 初值）、`:478-484,497-502`；`excel.ts:457-481` | 一个任务 ≥2 个导出结果，只激活第一个标签（`loadExistingResults`/`refreshResults` 的唯一行为）⇒ 未激活标签的 `SpreadGrid` 从未 `loadFromXlsx`，`fields=[]` ⇒ `toXlsxBlob()` 只含 `def_code` 元数据表；`validateLocal()` 对空表放行 ⇒ PUT 把服务端导出文件替换成空内容，界面报"已保存到服务端"并清 `gridDirty`；下游（发布/导入/其它入口读文件）读到 0 行 ⇒ 落进 BZ-01；下载路径同根（zip 里这份是空表，AI 回执却写"内容为在线编辑后的当前表格"） | **无**；`export-wizard-batch-download.spec.ts:84-89` 以"`gridRefs` 为空 ⇒ 走文件回落支、两支路同名同源"为前提，把"有 grid 但未加载"排除在断言外（前提本身也是错的）。**前提标注**：`el-tab-pane`（`:112-129`）未设 `lazy` ⇒ 依 Element Plus 默认会急切挂载，但**这一条未运行验证**；若实际为懒惰渲染，则降级为"下载取服务端字节"（不再覆盖文件），S2 应下调 |
| **FE-51** | **`startExport` 丢弃 `persistStepData` 结局：条件未落库仍起作业** | **S2** | C | **V1** | `ExportWizardView:763-788`（`:772 await persistStepData()` 后**不判 `ok`**）、`:1485-1492`；对照已修形态 `:1422-1430`、`:1341-1345`、`:1554-1559` | `selectDefs`/`setCondition` 失败（网络/5xx/未勾选该配置）后点「开始导出」或 `start_export` ⇒ 作业以**服务端旧/无条件**启动并 COMPLETED；界面显示的条件与实际导出不一致，AI 回执"导出作业 #id 已启动"无失败信号；四个入口里唯一没做阻断的一条 | **无**（`export-wizard-step-outcome.spec.ts` 自称覆盖"三条真实入口 + openEditor"，T7 清单未列 `export.start`） |
| **FE-53** | **`export.setConditions` 落库失败仍按字符串成功回灌** | **S3** | C | **V7** | `ExportWizardView:1454-1483`（失败支 `:1478-1482` 返回**字符串**）；同文件 `:1424-1425` 注释已把该形态定性为"卡面与回灌自相矛盾"，但只修了 `nextStep` | 字符串结局被契约层折成 ok ⇒ 工具卡 tag 显示「已成功」、卡内文却写「落库失败…请提示用户」；模型下一轮据"已设置"行事 ⇒ 导出用旧条件（= FE-51 的输入端） | 无（`ai-region-cond-errors.spec.ts` 只断言 `condErrors` 留痕） |
| **FE-54** | **跨任务绑定/落库无代次守卫：A 的选中集与条件可写进 B（**I-08 合并**）** | **S3** | R | **V7** | `ExportWizardView:399-430`（`loadTask` 无 token、读实时 `taskId.value`）、`:562-582`、`:686-716`、`:1752-1762`；文件自认 `:1226`「不新增序号守卫」 | `route.query.taskId` 连续两次变化（AI `navigate_to` 连调/加载中换任务）⇒ 两个 `loadTask` 交错，后落地的**旧**结果覆盖 `task/selectedDefs/stepIndex/workspace.enterPage`，而 `persistStepData`/文件列表/下载读实时 `taskId` ⇒ 标题与只读判据来自 A、落库与下载作用在 B；批量下载的 zip 名取自起始任务、内容可能取当前任务（**机理更正 F-9**：`gridRefs` 不是随卸载被删，而是 `buildXlsx`/`loadGrid` 解析时实时读 `taskId`） | 无（`import-watch-recycle` 是导入侧且不挂视图） |
| **FE-55** | **`refreshResults` 静默丢弃未保存的在线编辑（与换任务时的提醒口径不一致）** | **S3** | C | **V7** | `ExportWizardView:836-845`（`loadedGrids.clear()` + 重下载，无 `gridDirty` 判据）、`:587-591,641-648,1777-1787`；对照 `:508-512` 换任务会 `ElMessage.warning(unsavedDiscarded)` | 第 3 步改了结果表（未保存）→ 上一步再前进/点步骤节点/AI `goto_step`/`export.syncJob` ⇒ 编辑被服务端文件覆盖、无提示；`gridDirty` 仍 true，之后卸载又弹"有未保存编辑"，提示指向已被丢弃的内容 | 无 |
| **FE-56** | **重跑导出失败/取消后，旧导出结果从页面消失（文件仍在服务端）** | **S3** | C | **V7** | `ExportWizardView:779-781`（启动即清 `exportResults`）、`:810-832`（`onFinal` 只在 COMPLETED 时 `refreshResults()`）、`:1325-1332` | 对已有结果的任务重跑且终态 FAILED/CANCELLED ⇒ 页面"暂无导出结果"（刷新即恢复）；该窗口内 AI `download_export_files`/`open_export_file_editor` 报"该任务现有导出结果：（无）"（把"文件仍在"说成不存在） | 无 |
| **FE-57** | **只读判据 fail-open：`listJobs` 失败被吞成 `[]` ⇒ 已结束任务变可编辑** | **S3** | R | **V1** | `ExportWizardView:440-462`（`:444 .catch(()=>[])`）、`:310-316`（`isReadOnly` 依赖 `exportJob.status==='COMPLETED'`）、`:147-149`、`:1437-1492` | 打开已 COMPLETED 的导出任务时 `GET /jobs` 失败 ⇒ `exportJob=null` ⇒ `isReadOnly=false`，`export.selectDefs/setConditions/start/nextStep` 四道只读守卫**全部放行**；后端 409 兜底但界面与回执先行 | 无 |
| **FE-58** | **设置页「测试连接」测的是已保存端点，不是表单端点；探活结论不随表单/保存失效** | **S3** | C | **V7** | `AiSettingsView:365-374`（只传 `provider.id`、不先保存）、`:376-384,671-678`（结论常驻卡片）、`:287-297`（保存成功后 `load()` 不清 `probeResults`） | 改 `baseUrl`/`completionsPath`/`modelsPath` 后**直接**点「测试连接」⇒ 打的是库里旧端点 ⇒ "连接成功"却保存了不可达地址（反向亦然）；`probeResults` 按 provider id 缓存 ⇒ 编辑/保存后仍显示上一次绿/红 | `ai-settings-view.spec.ts` 覆盖文案映射，**不**断言"探活目标=表单值"或"改表单后结论失效" |
| **FE-59** | **「拉取模型」结果跨 provider 复用 ⇒ 可把 A 的模型加进 B** | **S3** | C | **V7** | `AiSettingsView:50-51`（`fetchedModels/fetchedCached` 无 provider 维）、`:415-434,436-449`（`addFetched` 用当前 `selectedProvider`）、`:713-734` | 对 A 拉取 → 下拉切到 B → 点「加入」（或先切 provider 再等 A 的响应落地）⇒ `addModel(B, modelId_of_A)` ⇒ B 的 allowlist 出现 B 不提供的模型 ⇒ 上游 404 / `UPSTREAM_REJECTED`；`pulledCached` 文案同样跨 provider 误标 | 无 fetch→switch 序列用例 |
| **FE-60** | **数据源级联异步无序号/取消 ⇒ 旧响应覆盖新选项** | **S3** | R | **V7** | `FormRenderer:603-620`（无 token/abort）、`:564-568`（父值变化循环触发） | 连续改父值 A→B，A 的响应后到 ⇒ 下拉展示 A 的选项而父值是 B ⇒ 用户选值即提交属于旧分支的值（后果链同 BZ-25）；卡面无"选项已过期"信号 | `formOptionSource` 在测试里被 mock 成立即 resolve |
| **FE-61** | **失败回执仍显示"工作区已同步"（I-09 补登）** | **S3** | C | **V4/V7** | `workspace.ts:447-452`（`dispatchUiEvent` 的 catch 翻成 `fail(...)` 后仍 `touch(...,'AI')`）；面板 chip 文案 | 工具结果失败（或被只读闸拒绝）时，回执/chip 仍表述为工作区已同步 ⇒ 用户与模型都被误导为"页面已跟上"；与 FE-52/FE-53 同族（回执与事实相反） | 无用例（`gate-matrix` 只断"恰 5 条 AI 记录"） |
| **FE-62** | **发布冲突数被第一页（50）截断却当"总数"同时喂模型与卡脚；无 ERROR 时把 WARNING 当"冲突"** | **S4** | C | **V7** | `task.ts:361-362`（`getIssues(jobId,0,50)`，`totalElements` 丢弃）、`:366-375`、`:103-110`（`共 ${issues.length} 处`）；`ai.ts:5421-5425`；`resultview.ts:359`（该入参语义是**真实**总数）；`ResultViewTable:38-44` | 发布终态 FAILED 且 ERROR >50 ⇒ 模型读到"共 **50** 处"、卡面脚注"共 50 条，显示 5 条"，实际可能 200 ⇒ 模型把"已看完冲突范围"当事实、用户按 50 条修不完；`buildPublishConflictView` 的 `total` 契约在生产中恒等于 `issues.length`（字段名不成立）；仅 WARNING 的失败被命名为"校验冲突明细" | `watch-publish-outcome.spec.ts:120-172` 的桩恒让 `totalElements==content.length` ⇒ 50 上限**结构上不可见**；而 `resultview-utils.spec.ts:91-94`、`publish-conflict-resultview.spec.ts:44,206-215` 显式喂真实 total（单测守住纯函数、恰好证明错在调用点） |
| **FE-63** | **路由 3（`ui_event` 同名动作）无任何页/步/任务闸，与路由 2 不对称（今天零触发）** | **S4** | R | **V7** | `frontend-tools.ts:690-693`（路由 3 入口）、`:1311-1337`（直接 `dispatchUiEvent`，无 `allowPages/allowSteps/needsTask`）vs `:974-1001`（路由 2 三道闸）、`:988-994`（`allowSteps` 生效） | 一旦出现一个 `@ToolChannel(FRONTEND)` 且名为 `run_job`/`download`/`cancel_job`/`set_import_mode`/`refresh_*`/`select_defs`/`set_conditions` 的工具 ⇒ 这些动作绕过三道闸直接落契约层（`run_job` 的 PUBLISH 仍有确认框、EXPORT 无）。**现状**：37 个 `@Tool` 名中无上述任何一个 ⇒ 零触发；头注释"四条路由"给人"四路安全等价"的错觉 | `r166b-fixes.spec.ts:514-517` 是唯一按名驱动路由 3 的用例，只喂 `args:{}` 走失败支 |
| **FE-64** | **敏感键正则比自述窄：门卡（唯一掩码面）仍会明文显示典型密钥名** | **S4** | C | **V1** | `blocks.ts:139-140`（`/pass(word)?\|secret\|token\|api_?key/i`）、`:201-209`（仅键名命中才掩码）；`AiPanel.vue:3105-3109`（门卡 `argRows(args,true)`）vs `:2913-2916`（普通卡不掩码） | 门卡 args 出现 `authorization`/`accessKey`/`privateKey`/`signingKey`/`credential`/`pwd` ⇒ 明文上卡并进 per-tab 镜像；当前 34 个 AiTools 的 gate 参数无此类键 ⇒ 无现发实例。即使把掩码扩到普通卡（FE-30），正则仍漏这几类 | `blocks.spec.ts:388-393` 只断言 `password`/`apiKey` |
| **FE-65** | **同参去重命中复制"上一次"结局（"未执行"被当成"这次失败"）；`UI_ALREADY_EXECUTED` 无生产者** | **S4** | C | **V7** | `frontend-tools.ts:579-591`（命中即 `code: previous.code`、复制 `outcome/availability`）、`:628-635,646-668`；`ui-receipt.ts:29,35` | 上次 `timeout`/`failed` ⇒ 本次"未执行"也落 `failed` 并携带旧码，模型无法区分"未执行（沿用旧结局）"与"真的失败"；码闭集里 `UI_ALREADY_EXECUTED` 永不出现（按码统计的台账该分支恒 0）；`UI_CODE_PATTERN` 零消费、跨端同源无断言保护 | `ai-tool-receipt.spec.ts:96-103` 只断言码的合法性 |
| **FE-66** | **错误卡变体分类会扫描自由文本 ⇒ 上游原句里的词改判色族与动作** | **S4** | R | **V7** | `error-card.ts:52-56`（三个正则，`CONTEXT_RE` 含 `too[_-]?long`）、`:122-134`（拼 `code+message`）、`:168` | 非配额/非超限的上游错误，其 `message` 恰含"配额/额度/rate limit/too long"（如回显模型输出的 400、`Request URI too long`）⇒ 用户看到"配额已用尽"与"开新会话/升级"动作钮，真实原因是参数或 5xx | 无（只有码优先路径被间接覆盖） |
| **FE-67** | **工作区写动作与导航无串行化 ⇒ 同批并行打到导航前的 taskId** | **S3** | R | **V1** | `workspace.ts:465-521`（`restore_task` 只 `router.push`；其余动作读 `workspace.taskId`）；`frontend-tools.ts:1019-1034`（无 handler 即落 `toUiEvent`）；`ai.ts:5061`（每帧 `void runFrontendTool`，**无队列**） | 一条 assistant 消息同批发 `navigate_to(page=import,taskId=B)` + `select_definitions`：前者 `await router.push`，后者立即进 `runWorkspaceAction`（`pageId` 仍 export、`taskId` 仍 A、新页 handler 未挂载 ⇒ 落 ui_event 后备）⇒ `tasksApi.selectDefs(A,…)`，AI 声称"为 B 选好"、实际写在 **A**；回执 ok。F3 的"等页面就绪"只对下一次调用有效 | 无并发用例（`workspace-run-job-publish-confirm`、`gate-matrix` 都是串行直调） |
| **FE-68** | **`ui_event` 成功写被双记账，且内部一条标 `来源：界面`** | **S4** | C | **V7** | `workspace.ts:450`（`dispatchUiEvent` 无条件 `touch(result.message,'AI')`）× `:180-190,213-224`（默认 `source='界面'`）、`:226-232,234-245`；调用点 `:481,494,507,520` 不传 source | 一次动作写**两条** `recentActions`、`dataVersion`+2；模型被告知"用户自己点的"（与 `setSelectedDefs` 注释要求相反）；窗口只有 5 条 ⇒ 两三条 AI 动作即可把用户真实点击挤出上下文 | `workspace-audience.spec.ts:25-52` 只锁 UI 手点；`ai-dom-verbs.spec.ts:198-211` 走 DOM 工具路径，不覆盖此写分支 |
| **FE-69** | **`restore_task` 丢 `page`/`taskType` ⇒ 非当前页任务回落 EXPORT 向导并改写 `taskType`** | **S3** | C | **V1** | `frontend-tools.ts:852-864`（只产出 `{type:'restore_task',taskId}`）；`types/tools.ts:496`（`taskType?` 声明了但唯一生产者不填）；`workspace.ts:465-469`；`task.ts:392-399`（`taskType ?? items.find(...) ?? 'EXPORT'`）、`:402-409` | 模型调 `navigate_to(page='import',taskId=7)` 而任务 7 不在任务中心当前页/筛选内 ⇒ 回落 EXPORT ⇒ 打开导出向导且 `enterPage({taskType:'EXPORT'})` ⇒ IMPORT 任务的 `currentStep` 在导出 `STEP_KEYS` 里 `indexOf=-1`（显示第 1 步），工作区从此**谎报** `task:EXPORT/*`，模型拿到导出工具面（含 `start_export` 的 DANGER 门） | `gate-matrix`/`goto-step-readonly-guard` 把 `useTaskStore` 整体 mock ⇒ 真身零覆盖 |
| **FE-70** | **`load()` 的 `loading` 早退吞掉分页/筛选刷新；`refresh_tasks` 回假回执** | **S4** | C | **V7** | `task.ts:158-161`（`if(this.loading) return`）、`:197-203`（只有 `refreshNow` 置 pending）、`:205-228`（三个 setter 直接 `void this.load()`）；`workspace.ts:627-629` | 首屏/手动刷新在途时点分页/改筛选 ⇒ `page/size/filter` 已改而**列表未重拉**（分页器"第 2 页"、表格仍是第 1 页），下一次任意刷新才纠正；`refresh_tasks` 在同样窗口回"任务列表已刷新" | `task-center-view.spec.ts` 对 `setPage/applyFilters/loading` 零断言；`refreshPending` 无用例 |
| **FE-71** | **`enterPage` 只写"传入的键"：数据浏览页漏传 `taskId:null` ⇒ 旧任务绑定残留** | **S4** | C | **V7** | `workspace.ts:144-178`（只写非 undefined 的键，`taskStatus/queryConditions` 从不复位）；`DataBrowserView:238,361`（缺 `taskId:null`）；对照 `TaskCenterView:361`、`DefinitionsView:495` 都显式传 | 从已绑任务 #12 的向导切到"数据浏览" ⇒ `buildContext()` 报 `page=data` 同时继续上报 `taskId=12`，面板 chip 显示"数据浏览 · 任务 #12"；`taskStatus` 也不复位 ⇒ CANCELLED 分支（`:421-423`）不要求 taskId 非空 ⇒ 回任务中心后所有只读闸一律判只读 | 无（`DataBrowserView` 无 workspace 断言） |
| **FE-72** | **发布跟踪的第二处单槽（workspace 级 dispose）⇒ 同任务重跑也静默丢上一轮失败（补 FE-19）** | **S4** | C | **V7** | `workspace.ts:75`（模块级 `publishWatcherDispose`）、`:551-552`（每次 `run_job(PUBLISH)` 先 dispose 上一轮）；`task.ts:293-297`（注册表按 `taskId:jobId` 本可并存）、`:308-313`（dispose 不判终态） | 任一第二次 `run_job(PUBLISH)`（含同任务重跑）⇒ 上一轮 watcher 被拆 ⇒ 其 FAILED 终态既走不到 `applyPublishOutcome` 闸位检查、也不被 `watchJob` 观察 ⇒ 无消息、无冲突卡、无回灌。FE-19 是"闸位被占"，本条是"watcher 被提前拆除"（两条独立机制、同一可见结局） | `watch-publish-outcome.spec.ts:255-289` 只固化闸位那条 |
| **FE-73** | **前端展示/契约 minor 包**：行数预览 `null` 也渲染且手改条件后旧计数常驻；任意保存后的 `load()` 清空其它卡片未保存输入（含刚输入的密钥）；删除当前 provider 后静默落到第一家；隐藏字段的复核问题仍进 live region；上下文浮层拉取失败永久"加载中"；结果视图列截断零提示且 `text` 空态文案不用；`cancel_job` 只读守卫不核 `jobId`；契约版本号无任何判定方；pill 在"有上限无百分比"时丢分母 | **S4** | C | **V7** | `ExportWizardView:57,724-727,1465`；`AiSettingsView:199-221,340-363,68-70`；`FormRenderer:209,732-740`；`ContextPopover:50`；`ContextPill:67-73`；`ai.ts:1654-1656,5179-5181`；`ResultViewRenderer:64`；`ResultViewTable:33-39`；`resultview.ts:284,320-322`；`workspace.ts:570-578`；`types/tools.ts:487-488`；`context-usage.ts:148-162` | 用户按错误规模判断导出范围；"保存完仍无 key"；"看着没选，实际在改另一家"；读屏播报视觉上已消失的错误；弹层永远"正在加载"且无重试；50 列被裁无提示；跨任务误拒（当前不可达）；版本漂移无人发现；pill"已用 84k"而 tooltip"84k / 128k" | 各无对应用例（`context-pill.spec.ts` 只两态；`resultview-renderer.spec.ts:179-205` 空态全是 `kind='table'`） |

### 5.7 CI / 测试真实性 / 门禁（CI，16）

| # | 结论 | Sev | Cert | Ev | path:line | 触发→效果 | 测试 |
|---|---|---|---|---|---|---|---|
| CI-01 | PR 门禁 E2E 在**快照 HEAD** 跑不完：130 条只执行 17 条，停在 `H-01` 后被 25 分钟超时取消 | S2 | C | V5 | `ci.yml:225-237`；`verify-e2e.ps1:5483-5505,117` | 快照期"红"= 没跑完；H/G/T/K/Q/C/D/X/W 全域零执行证据。**main 候选已跑完 165/1**（§7.1-D）⇒ 本行只描述快照 | — |
| CI-02 | 三实例网在**快照** 4/4 failure：**5 真红 + 1 假红 + 1 间歇** | S2 | C | V5 | `cluster-3node.yml:43-109`；`cluster3-probe.mjs:1615-1632` | 快照期多实例承诺无一条被证明。**main 候选 15/1**，唯一失败 `suspend-cross-instance` 已定因为**产品缺陷**（假 `PROVIDER_GONE` + 拒绝不落终态，§7.1-E）⇒ 不是假红 | main 有回填/探针候选（L-2/L-7） |
| CI-03 | **补丁从未被任何 CI 验证**（含 6 个新测试） | S1 | C | V5 | `working.patch`；全部可引用 run 的 headSha 为 `ae883fb`/`965e6ef`/`20c56bb`/`34fd8f4`，**无一跑该补丁** | 快照补丁上的"已修复"只能是意图；6 个新测试从未进入任何 run。**注意**：`34fd8f4` 的两条 run 跑的是 **main 候选**，不是本快照补丁 | 6 个新测试从未进入任何 run |
| CI-04 | `suspend-cross-instance` 早前被判"期望与 park 收流契约冲突 ⇒ 确定性假红" | S3 | C | V5 | `cluster3-probe.mjs:2157-2171` vs `AiController:762-768` | **本轮归因推翻该判断**：`40182796512` 的失败是"决策后重挂收不到 `done`"，根因是产品侧假 `PROVIDER_GONE` + 拒绝不落终态，不是探针期望问题；"假红"只在快照 `40174291244` 的特定形态下成立 | 修法：confirm 后再重挂（main 有探针候选 L-7）；但**不能再用"假红"解释本条** |
| CI-05 | 真模型指标把"卡被复位"计成"模型未触发" | S2 | C | V5 | `11b-hgta.spec.ts:505-520` | 产品缺陷被读成模型不服从 | 需拆"帧到达率 vs 卡可提交率" |
| CI-06 | 纯前端改动不触发浏览器 e2e | S2 | C | V6 | `changed-files.mjs:75-79` | FE 类缺陷可绿着合入 | — |
| CI-07 | 无粘滞只验分布不验顺序（8–12/30；每节点 ≥2） | S3 | C | V3 | `cluster3-probe.mjs:1812-1813,1851` | `leastconn`/部分粘滞也能过 | 应断言 A→B→C 序列（main 有更强断言候选 L-7） |
| CI-08 | 39/130 条 P2 红不阻塞（分布 17/74/39） | S2 | C | V6 | `verify-e2e.ps1:5707-5725` | 观察项与保证项无机器可读边界 | — |
| CI-09 | 作业名"35 用例"与实测 130/165 不符 | S4 | C | V6 | `ci.yml:225,345`；`verify-e2e.ps1:5507` | 按"35 条"设的预算正是 CI-01 诱因 | — |
| CI-10 | `verify-e2e.ps1` 的"从产品键派生超时"实为硬编码 300 | S4 | C | V4 | `verify-e2e.ps1:112-118` | 调大预算即新假红 | — |
| CI-11 | 永久/条件 skip 未登记为门禁缺口 | S3 | T | V3 | `17-unlock:677`、`16-kqc:61`、`10-srp:164` | 对应行为永久无自动化 | — |
| CI-12 | **`InMemoryRunStore` 复刻 `create()`** ⇒ 真实 `RunStore.create` 从未执行；删 identity/freeze 仍全绿 | S2 | C | V2 | `InMemoryRunStore:196-221`；`RunStore:648-651` | 身份冻结类回归无保护 | `RunSnapshotModelIdentityTest`/`RunParamsFreezeTest` 均跑内存 store |
| CI-13 | **7 条租约/帧脚本无脚本文本断言**；改 `>=`→`>`、删 `PEXPIRE`、换 TTL 参数仍 1,000+ 绿 | S2 | C | V2 | `FakeLeaseRedis:534-593`；`LeaseService:155-256`；`FakeSessionRedis`（FA2 确认同类） | Lua 回归不可见；`SessionGate` 的"原子性"同样只证意图 | 仅 `IDEM_PUSH` 断言文本 |
| CI-14 | **证据基础的结构性偏弱**：`LeaseServiceTest` 未播种 fence；`ToolScopeTagSpaceGuard` 下限 26 < 37（注释亦写 26）；`shippedYamlCoversEveryDeclaredTool` 只比 27 个硬编码 key；G2 有 6 个构造性不可证伪用例；34 个 web 测试仅 2 个 `@SpringBootTest`；Health 三测只断言"字段存在/值回显" | S2 | C | V2/V3/V7 | `ToolScopeTagSpaceGuardTest:46,75`；见 §6.2 | 覆盖数字好看，关键不变量无人守；**删掉 11 个 `@ToolScope` 仍绿** | — |
| CI-15 | park 配置错位（生产 true / 代码缺省 false） | S3 | C | V6 | `application.yml:258-259`；`AiProperties:855` | 8 个文件显式置 true；**58 个 `@SpringBootTest` 加载 yml ⇒ park=true** | — |
| CI-16 | `assumeTrue(connect())` 的真 Redis 用例可静默 SKIP | S3 | T | V3 | `RedisChatMemoryRepositorySaveAllEmptyTest` | CI 上常跳过 | — |

---

## 6. 测试完整性与真实性

### 6.1 分层：各自证明了什么

| 层 | 规模 | 真跑 | 替身/桩 | 能证明 | 不能证明 |
|---|---|---|---|---|---|
| 后端单测 `mvn test` | 395 文件 / 2,607 `@Test`（AI 域 327 / 2,063） | 真对象、状态机、部分真 H2/线程池 | Redis 全替身；`InMemoryRunStore`；`FakeSessionRedis`/`FakeFrameRedis` 按标记分派 | 分支/帧序/键名/降级 | 真 Redis、真网络、跨进程、Lua 原文 |
| `@SpringBootTest` | **58** | 真 H2+Bean+迁移，**加载 yml（park=true）** | 无 Redis/PG | 装配级集成 | 多 JVM、park 流程本身 |
| 前端 vitest | 139 文件 / 1,511 例 | 真 store/reducer（jsdom） | `@/api/ai` 全 mock；Fake lock/channel；部分用例整体 mock `useTaskStore` | UI 状态机、段投影、恢复账本 | 真浏览器、真网络、真多页签、`resolveWizardRoute` 真身 |
| `verify-e2e.ps1`（PR 门禁） | 165 例 | 真 JVM + 真 Redis(单节点) + 真 HTTP/SSE | 桩上游 | 帧序/门/表单/作业/历史/字节 | 多实例、Cluster、真模型、UI |
| cluster-3node | 16 例 | 3 JVM + H2 TCP + 真无粘滞 + 真 kill/pause/SIGTERM | 桩上游 | 见 §7.2 | Cluster、PG、真模型、跨机盘、补丁有效性 |
| Playwright（9 套） | 278 `test()` | 真 Chromium + 真后端（部分域双 JVM，**H2 各自独立**） | 桩上游为主 | DOM/多页签/刷新 | 不在提交门禁；HEAD 未跑 |
| 真模型 `@real` | 14 条（该次 9 条） | 真模型 + 真 UI | — | 采样服从率 | 稳定性（单次、N=10、`retries:0`） |

### 6.2 假绿 / 弱断言 / "把缺陷锁成期望"（逐条）

**A. 底座失真（影响最大）**
1. `InMemoryRunStore` 默认共享对象引用 ⇒ "读旧副本整条覆盖"结构性不可见（PK-03 根因）。
2. `InMemoryRunStore` 复刻 `create()` 的 identity/freeze ⇒ 真实 `RunStore.create` 永不被执行（CI-12）。
3. `FakeLeaseRedis` 按注释标记分派、Java 复刻语义 ⇒ 7 条脚本无文本断言（CI-13）；**`FakeSessionRedis` 同病**（`SessionGate` 四条 Lua 原文一次未执行，FA2 确认）。
4. `InMemoryContextStateStore` 不建模 TTL/`LTRIM 20`/失败；`FrameWire` 只归一化顶层；`FakeLockManager` 同步回调且不建模"同文档二次 `ifAvailable`"。
5. **跨实例/跨时间口径缺失**：`RedisStreamCapacityTest` 全在同一实例断言缺口计数（RT-32 结构上不可见）；`RedisFrameStoreTest` 只构造 1 条晚到帧（RT-31）；五张映射的回收零断言（RT-33）。
6. **作业侧 mock 不建模 TTL/命令超时**：`RunnerActiveMultiInstanceTest`/`StaleJobRecoveryMultiInstanceTest` 用 `ConcurrentHashMap`（TTL 不生效）⇒ BZ-34 的开销/降级、BZ-35 的分区、BZ-38 的残留全部不可见。

**B. 把缺陷窗口固化为期望**
7. `ConfirmGateDeadlockResumeTest:330-346`（PK-01）。
8. `HumanWaitParkTest:230-243`（RT-04 零终帧）。
9. `ToolTargetResolverTest:118-120`（ID-10）。
10. `r166b-fixes:566-575`（FE-01）。
11. `session-purged-recovery:87-113`（FE-04）。
12. `watch-publish-outcome:255-289`（FE-19）。
13. `ContextCompactorTest:381`（lostTokens 错误语义）。
14. `AiControllerHistoryPayloadTest:210-221`（`hasMore` 反语义）。
15. **`ai-region-batch-b:172-201`**（第 1 步快照里断言第 4 步控件可见 ⇒ FE-32 的行为被写成预期）；`:363-364,378` 用 `bumpAiEpoch()` 绕过缓存（FE-33 的后果被测试手法掩盖）。
16. **`ai-snapshot-dedupe-epoch.spec.ts:58-70`**（"同 epoch 内 DOM 变了仍去重"被写成期望，断言第二次仍含旧按钮 ⇒ FE-33 第二层被固化）。**这是本仓第二条"缺陷即期望"。**
17. **`sheet-preview-highlight:152`**（"不写 cell style"在无样式概念的假适配器上永绿 ⇒ FE-39）。
18. **`WizardActionGuardTest:120-125`** 只覆盖"值缺失/IN 非数组"，把"空串值/重复字段"的语义空值面留给生产（BZ-31）。
19. **`RedisFrameStoreTest:337-351`** 只断言 legacy 无 seq 帧"保留"，不覆盖"不应在 `afterSeq>0` 时重复"（RT-38）。
20. **`blocks.spec.ts:306-309`** 把 GF 段 `failed` 归 `cancelled` 钉为期望（可达性未证，故只登记不立条）。

**C. 构造性不可证伪**
21. `ContextEstimatorTest.messagesSkipSystemAndSumToolBodies` 恒真式；`:toolSchemasAreCounted…` 只断言 0。
22. `CompactionFuseTest.twoInstancesBothSeeTheHalfOpenWindow` 两次调纯函数。
23. `SystemPromptHitlConfirmTest` 的 `.contains("才")`；`LanguagePolicyTest`/`SystemPromptUiSnapshotRules` 字面 contains。
24. `AiCompactionLogTraceTest` 的 `limit=\d+` 也匹配 `limit=0`。
25. 三个 Health 测试断言"mock 值被回显"，无精确键集/敏感字段缺席断言。
26. `ToolActivityBeaconSegmentTest:30,42` 用 `sleep(60ms)` 断 50ms 下限。
27. **`ai-panel-parts-render:3118-3126`** 的 `not.toContain('language-js')` 由 `stripTailLanguage` 单独保证，不能证明"高亮被抑制"（FE-41）。

**D. 只断言 mock 调用 / 直调不可达分支 / 测低层原语**
28. `MultiCardParkResumeTest`：`verify(resolvePending,times(2))`。
29. `workspace-run-job-publish-confirm`、`gate-matrix:155-188`：直调 `dispatchUiEvent`，其中多个 ui_event 当前无模型入口；**且 `useTaskStore` 被整体 mock** ⇒ `resolveWizardRoute` 真身零覆盖（FE-69）。
30. `import-watch-recycle:61-73`：测试自建接线，不挂视图（FE-16 盲区）。
31. `AiToolsInvocationTest.upload():341-352`：手写 `row_count`（BZ-05 假绿）。
32. **`sheet-tools-undo-depth:88-141`** 直接调 `undoLastSheetEdit`（已明确非模型入口）。
33. **`ListTasksResultViewProviderTest`** 全是单次 `build` 的静态 mock ⇒ 跨时间重投影（BZ-33）结构上不可见。
34. **`ToolResultLimiterTest:53,135,187,213`** 只断言 `returnedChars == text.length()` ⇒ PK-17 的 notice 数字偏差**结构上不可能被发现**。
35. **`ToolRegistryDisclosureTest` 注释与实现不符**：`:60` 写"仅 confirm_step = WRITE"实为 3 个；`:180` 写"scope 一律 `*`"，而 7 个 `ui_*` 的 scope 是 `{"ui:on"}`（断言为真只因 `AiContext.empty()` 缺 `uiRegionCount` 而 fail-open）。

**E. 口径漂移 / 可静默消失**
36. `AiRetryDefaultsTest:28` 硬编码 60s。
37. `assumeTrue(connect())` 可 SKIP（CI-16）。
38. `AiProviderLeakTest` 只监听一个 logger；`AiModelResolverTest` 断言 5 级链而生产只走 3 级。
39. 34 个 web 测试仅 2 个 `@SpringBootTest`；`ToolScopeTagSpaceGuard` 下限 26（实际 37）。
40. **`FrameSizeLimiter.MIN_FRAME_BYTES` 与 `ResultBudget.Ui.MIN_FRAME_BYTES` 各写一份 1024**，无相等性断言（漂移风险，S4）。

**F. 完全无覆盖的前端分支（P3/P4/FA5/FA6 汇总）**
`restoredPendingQueue`/`revealNextRestoredPendingCard`（0 引用）、折叠/展开、`done/error × suspended`、`/history` 在飞失败、选择器跨轮次、动作后重拍快照、跨步写隐藏控件、重复目标列、`normalizeSuggest` 的 `sheetMeta`、`ui_select` 空回读、select 撤销、分块写异常、嵌套/`~~~` 围栏、`data-fence-key` 注入、截断标记文案；**FA 新增**：`export.start` 入口（FE-46/51/53）、"grid 存在但未加载"（FE-50）、`resolveWizardRoute` 真身（FE-69）、`dispatchUiEvent` 写分支的 source（FE-68）、分页/筛选在 `loading` 下（FE-70）、`enterPage` 缺键（FE-71）、探活目标与跨 provider 拉取（FE-58/59）、级联选项内容与子值清理（FE-60/BZ-25）、`limitTokens!==null && percent===null`（FE-73）、`contextSummary` 的 en 插值（FE-29）、发布冲突 >50 与仅 WARNING（FE-62）、同轮重跑发布（FE-72）、导入写回链（FE-16/46/47/48）、工作区并行动作（FE-67）。

### 6.3 非阻塞 / 跳过

| 项 | 事实 | 后果 |
|---|---|---|
| P2 不阻塞 | 130 条分布 **P0 17 / P1 74 / P2 39**；退出码只算 P0/P1 | 39 条真坏门禁仍可能绿 |
| 永久/条件 skip | `17-unlock:677`、`16-kqc:61`、`16-kqc-b:41`、`16-kqc-c:68`、`10-srp:164` | 对应行为无自动化且不计数 |
| 真 Redis 可跳过 | `assumeTrue(connect())` | CI 上常消失 |
| 桩模式零容忍 | GF `SKIP≥1` 即 FAIL；批① `SKIP:*` 强制 FAIL | **正面设计** |
| 三实例网无 SKIP | `RESULT_STATUSES=['PASS','FAIL']` | **正面设计** |

### 6.4 FA 组补充：断言"看起来覆盖、实际不可能变红"

| # | 事实（FA 全读所得） | 后果 |
|---|---|---|
| 1 | `export-wizard-batch-download.spec.ts:84-89` 明写"本仓测试不装 element-plus（未解析组件丢弃插槽）⇒ `gridRefs` 为空，`buildXlsx` 取文件回落支，**两支路同名同源**" | 把"grid 存在但未加载"排除在断言外（FE-50 结构性不可见），且该前提本身错误 |
| 2 | `export-wizard-step-outcome.spec.ts` 列举"三条真实入口 + openEditor"并逐一注入失败 | 缺第五入口 `export.start` ⇒ FE-51/52/53 无回归锁 |
| 3 | `ai-settings-view.spec.ts`（490 行）覆盖掩码/只带变化字段/默认目标二元组/code 文案 | 不覆盖 fetch→切 provider、探活目标、保存后卡片重置、删除当前 provider（FE-58/59/73） |
| 4 | `gf-renderer.spec.ts:182-214` 只断言"切父值会重新请求 `formOptionSource`" | 不断言选项内容、子值清理、本地成员校验（FE-60/BZ-25） |
| 5 | `resultview-renderer.spec.ts` 的空态/截断用例全部 `kind='table'` | `text` 态 `empty.text` 与列截断零提示无覆盖（FE-73） |
| 6 | `context-pill.spec.ts` 全为纯函数与两态渲染 | 无拉取失败态（FE-73）、无 `limitTokens!==null && percent===null` |
| 7 | `ai-upload-handoff.spec.ts` 覆盖三态外观 | 无"轮次已结束"、无 i18n、无双卡（FE-25/PK-12） |
| 8 | `watch-publish-outcome.spec.ts:120-172` 的 `/issues` 桩恒让 `totalElements==content.length` | 50 上限结构上不可见（FE-62）；而纯函数用例显式喂真实 total ⇒ 恰好证明错在调用点 |
| 9 | `ToolRegistrySheetGateTest:98-120` 只对 9 个表格工具 + 7 个上下文断言"逐工具一致" | 空 scope 数组的结构性分叉（§8.3-①）无覆盖，也无 `callbackMap ⊆ metaMap` 断言 |
| 10 | `ContextBuilderDelimiterInjectionTest:245-263`、`ToolResultEnvelopeDelimiterInjectionTest:82-95` 覆盖 ASCII 空白/NBSP/U+2007/U+3000/控制字符 | **无 `\p{Cf}`（U+200B/U+00AD/U+FEFF）** ⇒ `</tool_\u200Bresult>` 不被中和（PK-17 缝隙的测试侧证据） |
| 11 | `AiProperties*GuardTest` 有正例/反例 | **零**引用 `session.lock.*` 与 `lease.renew-interval`（MD-21） |
| 12 | `TaskSseServiceTest` 用 `SseWriteBudget` 桩 | 预算只约束单个 emitter，不约束一次 fan-out 的合计占用（BZ-36） |
| 13 | **`ResumeDispatchRetryTest` 从不触发外层 `RejectedExecutionException`**（只打桩内层结局） | PK-19 的"触发池饱和不重派"在测试侧**结构上不可见** |
| 14 | **导入向导的写回路径没有任何"挂载视图"的测试**（`import-watch-recycle` 自建同构接线，不挂 `ImportWizardView`） | FE-16/FE-47/FE-48 所在的 `flushEditors`/`loadEditorsNow` 写入面零覆盖 |
| 15 | **cluster 冷启动只断言 `available===true`**（`cluster3-probe.mjs:2265-2288`），而 `AiConfigService:440-445` 的 env 合成行会兜底 | 空 provider 快照被判"健康" ⇒ **冷启动绿、第七例才红**（MD-09 的假绿面）；建议断言"每实例 providers 含非 synthetic 行且 id 一致" |
| 16 | **`AIE2E-T-13` 的 `readCalls` 断言是合法的**（不是 harness 过严）：同一断言在 `2ed8f95` 绿、`34fd8f4` 红，且红跑 64/64 park 工具缺 `tool_call_end` | 该用例可作为 PK-06 的回归位，不需要另造探针 |

---

## 7. 真实 CI 边界 + GitHub Actions 可行性

### 7.1 可引用的 run

**A. `40174291244` — cluster-3node @ `ae883fb`（dispatch）**
真跑：3 JVM（`h2tcp`）、1 个 H2 TCP、**1 个单节点 Redis 7.2.16**（无 `--cluster-enabled`）、HAProxy 2.9（host 网、`roundrobin`、无 stick-table）、桩上游；真 SIGKILL/SIGSTOP/SIGCONT/SIGTERM、`docker pause`、真 multipart、21 轮历史分页。
结论 **failure**，PASS 9 / FAIL 7。PASS：`proxy-no-sticky`、`same-tcp-across-abc`、`reattach-any-instance`、`history-pagination`、`seq-cross-instance`、`idempotency-cross-instance`、`session-purge-cross-instance`、`config-hot-reload`、`redis-outage-recovery`。
FAIL：`cold-start-3`、`suspend-cross-instance`（假红）、`shared-file-download`、`suspend-kill-a`、`streaming-kill-a`、`job-kill-a`、`readiness-removal`。
**不能证明**：Redis Cluster、PG、真模型、补丁有效性、多用户、主从切换、跨机共享盘。

**B. `40174289141` — ci.yml @ `ae883fb`（push）**
backend/frontend success；**e2e cancelled**（07:56 起、07:59:23 后 20 分钟无输出、08:19:30 取消）；summary failure。只跑到 `M-09`（17 条），下一条 `H-01` 停住。

**C. `40171918873` — e2e-real @ `20c56bb`（dispatch）**
真模型+真后端+真 Chromium+单节点 Redis；8 passed / 1 failed；stub 作业 skipped；唯一红 `G-01 N=10 触发=0`（归因错误）。

**D. `40182795560` — ci.yml @ `34fd8f405d0a7c57cb2b7bae387d04249fce5ba1`（push，main 候选）**
**COMPLETED / failure**。e2e **PASS=165 / FAIL=1**；唯一失败 = **`AIE2E-T-13`**（`readCalls 0→0`）。链路本身完全正常（工具帧/挂起/回灌/结局帧/`done` 齐备），失败只在**审计计数**。

- **根因（已定，产品缺陷）**：park 化把结算移出内联批循环。`SheetAudit.record` 的唯一调用点是 `SpToolCallingManager:660-666`（紧跟内联循环的 `tool_call_end`），而 `resolvePending` 的**复用分支**（`:1816-1847`）只 `return text`，`ResumeService:842-856` 也不补 trace/audit ⇒ 该工具既无 `tool_call_end`，也不记账。
- **量化**：`tool_call_start` 97 / `tool_call_end` 37（按 toolCallId 去重），**64 个 park 过的工具 64 个缺 end**；绿跑 `2ed8f95` 为 101/100（当时是线程内等待，end 带 `dataClass=D1`）⇒ **回归，不是环境**。
- **排除项**：不是 harness（同一断言在 `2ed8f95` 绿、before/after 取同一端点）；不是外部取消（本 run 跑满 165 条、exit=1，被取消的是 `40174289141`）。
- **影响面**：9 个表格工具全为 `FRONTEND` ⇒ 生产中 100% 走 park；`sheet.counters` 8 项恒 0、`tool_call_end` 的 8 个采样字段永久缺席、`kind=sheet_sample` 的 D2 台账不落、`tool_call_start → tool_call_end` 日志不变量对 64/64 破坏。
- **修复状态**：在 `ConfirmGate.submitFrontendResult`（决策胜者之后）与续跑真正执行的那一支补一次结算；**在隔离副本中进行，未合并、未跑 CI**。本文件不把 PK-06 标为"已修"。

**E. `40182796512` — cluster-3node @ 同上（三实例）**
**COMPLETED / failure**。**PASS=15 / FAIL=1**；唯一失败 = **`suspend-cross-instance`**，表现为 **SSE 请求 120s 超时**（`?lastSeq=4`）。其余 15 例全绿（含 `cold-start-3`、无粘滞两项、`reattach-any-instance`、`history-pagination`、`seq-cross-instance`、`idempotency-cross-instance`、`session-purge-cross-instance`、`config-hot-reload`、`shared-file-download`、`suspend-kill-a`、`streaming-kill-a`、`job-kill-a`、`readiness-removal`、`redis-outage-recovery`）。

- **根因 A（决定性，产品缺陷）**：**种子写路径不发布配置版本**。`AiConfigSeeder` 在事务里写入 provider/model 后只对本实例 `reload()`，既不 bump `ai_setting.config.version` 也不 `INCR ai:cfg:ver`；而 `AiConfigService` 只在**版本变化**时重载。B/C 的首次 `current()` 早于种子提交（实测 **23ms / 230ms**）⇒ 快照永久停在 version=1 的**空 provider 表**；`effectiveProviders` 合成 `id=null` 的 env 行使 `/api/ai/health` 仍报可用（冷启动断言只看 `available===true`）⇒ 假绿；到 park 续跑改用**精确绑定** `resolveChatTarget(1,"ci-stub")` 立即落空 ⇒ **假 `PROVIDER_GONE`**。反证：同一 `providerId=1 / ci-stub` 在 182s 后被 A 正常续跑并执行成功。
- **根因 B（暴露面，独立缺陷）**：**拒绝不落终态**。`AiController.events()` 的挂起自愈派发被拒后只 `log.debug`，随即返回 200 并保留 emitter（`NO_TRANSPORT_DEADLINE`）；`ConfirmGate` 对应分支只一行 INFO ⇒ 无终帧、快照不落 `FAILED`，客户端只能等到实例重启的 `startup-resume`（182s）或自己超时（120s）。这正是 `ProviderGoneException` 类注明令要避免的"半死轮"。
- **排除项**：不是编排错误（用例严格按产品自述时序，前置断言全过，A 侧同 run 后续成功续跑证明期望值正确）；不是更强断言误杀（"决策后重挂必须收到 `done`"是产品自述口径）；不是环境极限（09:59 无 Redis/GC/端口异常，Redis 故障发生在 10:05:34 之后属第 16 例且该例 PASS）。
- **顺序依赖（必须记住）**：第 12–14 例（挂起/流式/作业接管）之所以绿，是因为第 10 例 `config-hot-reload`（10:02:03）先把版本 bump 到 2/3、把 B/C 的快照救活了；**用例顺序一旦调换，这些"接管类"用例会同样因空快照而红**。
- **修复状态**：A1（种子提交后 bump 版本 + `INCR ai:cfg:ver`）与 B1（派发被拒时落 `FAILED` + 终帧，或至少收流）**在隔离副本中进行，未合并、未跑 CI**；B2（硬否定前强制 reload）与 T1（冷启动断言非 synthetic）为建议护栏。

**F. `@real`（真模型）— 本轮未触发**。最近一次可引用结果仍是 `40171918873`@`20c56bb`（8 passed / 1 failed，唯一红 `G-01 N=10 触发=0`，归因错误）。因此"真模型达标/不达标"都**没有**新证据。

**G. 两条失败的关系：独立**。同一提交、不同机位：一条是跨实例配置快照陈旧（续跑前置门），一条是 park 结算缺审计（观测面）；症状、代码位置、是否阻塞链路都不同（前者阻塞到重启，后者链路正常收尾）。**没有共同根因**。

**对照**：`40173314741`@965e6ef 跑完 130 条但 33 红；`40131155913`@d66ab7c nightly 165 条 13 分钟 PASS 165/FAIL 1。
**横向读法**：main 候选 165/1 与夜间 165/1 的数量级一致，说明"跑完"这一层已稳定；但两次的**唯一失败项不同**（本轮为 `AIE2E-T-13`），不能按"同一条已知失败"处理。

### 7.2 现有 workflow 证明了什么

| 已 PASS（仅在其绿时） | 用例 |
|---|---|
| 无粘滞拓扑（roundrobin、无 stick-table） | `proxy-no-sticky`、`same-tcp-across-abc` |
| 跨实例重挂差量 | `reattach-any-instance`、`seq-cross-instance` |
| 跨实例读历史/双向翻页 | `history-pagination` |
| 跨实例决策幂等 | `idempotency-cross-instance` |
| 跨实例配置热更新 | `config-hot-reload` |
| 跨实例会话销毁 | `session-purge-cross-instance` |
| Redis 整体停机降级 | `redis-outage-recovery` |
| 无 key 不摘流 | `readiness-removal` 第①段 |

条件：3 JVM + H2 TCP（非 PG）+ **单节点 Redis** + 桩上游 + 同 runner 本地目录 + **未打快照补丁**。

### 7.3 明确回答（GitHub Actions 能回答什么）

**Q1 能否做"真无粘滞多实例"？** 能，已实现，且两项 PASS 证明"同一请求落不同实例"在应用层成立。限制：`workflow_dispatch`、~15 分钟、H2 非 PG、顺序未断言（CI-07）。

**Q2 能否做"真 Redis Cluster + 产品 JVM"？** **技术上能，当前没有。** 需在 job 内起 3 主 3 从（`redis-cli --cluster create`）+ 3 个 JVM 指向 `application-cluster.yml`。三个**当前阻碍**：① harness 依赖 `spring.data.redis.database` + `FLUSHDB`（Cluster 非法）—— **main 的 JDBC 守卫候选（L-8）正对此**；② 拓扑 fail-fast 是 `@Profile("cluster")` 门控，CI 必须显式激活；③ 每节点需 `maxmemory-policy noeviction`。仍不能证明主从切换（需 kill 单 master + 等 `cluster-node-timeout`）。

**Q3 能否顺带覆盖 PG？** 能（`services: postgres` + `application-postgresql.yml`），当前零覆盖；建议与 Q2 合成一条"生产形态"workflow。

**Q4 永远无法由 GH Actions 证明**：多用户隔离（无用户维度）、真实公网代理空闲超时（harness HAProxy 310s）、跨机 NFS/PVC、真模型服从率（只能采样）、`el.click()` 对隐藏控件的真机语义（FE-32 的"写入生效"强度）、浏览器 `<select>` 无匹配项时的显示（FE-60/BZ-25）、Element Plus `el-tab-pane` 的懒惰语义（FE-50 的前提）。

### 7.4 门禁结论

- **快照期** PR 门禁不可信：e2e 没跑完（17/130）、39 条 P2 不阻塞、纯前端改动不触发浏览器 e2e；这三条是**快照 `ae883fb` 的事实**，不因 main 改善而消失。
- 唯一多实例网在快照上 4/4 红；**main 候选 15/16**，唯一失败已定因为产品缺陷（种子不发布版本 + 拒绝不落终态，§7.1-E）。
- **没有任何 CI 跑过快照补丁**：所有可引用 run 的 headSha 都是 `ae883fb`/`965e6ef`/`20c56bb`/`34fd8f4`——**没有一条跑的是 `working.patch`**。这是 CI-03 的核心事实。
- **main 候选两条 run 均已 COMPLETED failure**（165/1 与 15/1）：不得记作"绿"，也不得把快照条目改判"已修"。两处失败**均已定因为产品缺陷**（不是 harness、不是外部取消），针对性的修复**在隔离副本中，未合并、未验证**。
- 门禁层面的净判断：**"候选把交付面从'跑不完'推到'各剩 1 条已定因的产品红'"**——这既证明了这两个缺陷真实存在，也说明门禁现在能抓到它们；但"修复完成"与"可交付"都还需要重跑两网来证明。

---

## 8. 覆盖对账

### 8.1 文件级状态（**AI 清单已全文件覆盖**）

| 集合 | 数量 | 状态 |
|---|---|---|
| AI 生产文件 | **196** | 196/196 有点名与实质阅读；其中 **150 个逐行全读**（G1–G4 的 78 + P1–P5 的 18 + **FA1–FA6 的 54**），其余 46 个为域报告定点阅读（大文件如 `AiController`、`ResumeService` 等以接缝阅读） |
| AI 测试文件 | **327** | 327/327 有点名；144 个由 G1–G4 逐条评估断言强度；其余由 A–J/L/M/N/FA 引用 |
| 前端 AI 文件 | 约 42 | **42 个逐行全读**（`ai.ts` 5,523、`AiPanel.vue` 3,700、`sheet-tools` 2,918、`ai-dom-tools` 1,718、`markdown` 1,100、`api/sse.ts` 752，加 FA3 的 3 个、FA4 的 11 个、FA5 的 11 个、FA6 的 11 个；`frontend-tools.ts` 1,560 与 `resultview.ts` 410 同属 FA5，`types/*` 属 FA6） |
| 逐行全读行数 | **57,516 行** | P1 7,380 + P2 6,034 + P3 9,223 + P4 5,736 + P5 4,771 + **FA 24,372** |

### 8.2 覆盖证明（**撤销前稿"已分配未完成"表述**）

六个分片作业各自声明"全文逐行"，合计 54 文件 / 24,372 行，**文件集合两两不相交、且与 P1–P5 的 18 个文件零交集**。行数守恒经本文件重算：

| 分片 | 文件数 | 行数 | 内容 | 证据（输出日志） |
|---|---:|---:|---|---|
| FA1 | 10 | **3,888** | `AiTools` 1,052、`ToolRegistry` 486、`ContextBuilder` 411、`WizardActionGuard` 393、`SheetWriteGuard` 361、`FrontendToolGuard` 226、`ToolResultLimiter` 315、`ToolResultEnvelope` 256、`SheetAudit` 269、`ListTasksResultViewProvider` 119 | `agents/main/tasks/agent-08n5bq5q/output.log` |
| FA2 | 5 | **3,578** | `RedisFrameStore` 758、`FrameSizeLimiter` 449、`SessionGate` 460、`RedisChatMemoryRepository` 412、`AiProperties` 1,499 | `…/agent-2z0mm9bc/output.log` |
| FA3 | 6 | **4,471** | `JobHeartbeatRegistry` 789、`JobService` 555、`TaskSseService` 479、`excel.ts` 865、`spreadjs.ts` 86、`ImportWizardView.vue` 1,697 | `…/agent-7f2xb8ij/output.log` |
| FA4 | 11 | **4,022** | `ExportWizardView` 1,789、`AiSettingsView` 834、`AiFeedbackView` 82、`FormRenderer` 791、`UploadHandoffCard` 111、`ContextPill` 120、`ContextPopover` 79、`ResultViewRenderer` 68、`ResultViewTable` 105、`ResultViewText` 28、`registry.ts` 15 | `…/agent-7fq5mga5/output.log` |
| FA5 | 11 | **3,807** | `frontend-tools.ts` 1,560、`blocks.ts` 585、`tool-summaries.ts` 265、`resultview.ts` 410、`ui-receipt.ts` 93、`error-card.ts` 188、`ai-suggestions.ts` 112、`job-watch.ts` 253、`polling.ts` 248、`publish-mode.ts` 61、`publish-links.ts` 32 | `…/agent-9j7hvmmd/output.log` |
| FA6 | 11 | **4,606** | `workspace.ts` 639、`task.ts` 424、`types/ai.ts` 984、`types/sse.ts` 622、`types/tools.ts` 563、`form-schema.ts` 832、`types/resultview.ts` 117、`format.ts` 118、`context-usage.ts` 200、`form-draft.ts` 87、`form-scope.ts` 20 | `…/agent-dla4fqis/output.log` |
| **合计** | **54** | **24,372** | 3,888+3,578+4,471+4,022+3,807+4,606 = **24,372** | 六份报告均附逐文件"已读区间/覆盖 100%"表 |

**结论**：本文件的 AI 清单**不再有"已分配未完成"的文件**。但这**不等于**"这 54 个文件已无更多缺陷"：六份报告一致声明**未运行任何测试**、且各自列出 6–12 条"读不出来"的运行期分支（§8.3-④）。同一文件由两人独立读（如 `workspace.ts` 由 FA6 全读、其缺陷面由 FA4/FA5 交叉引用）只提高一致性，不构成对抗复核。

> **证据可用性**：六份 FA 报告**已归档**在 `<仓外证据>/ai-full-audit/final-gap/FA1-report.md … FA6-report.md`（与上表行数一致，可直接复核逐文件覆盖表与 §2 新增条目）；对抗裁定归档于同目录上级 `FA-adversarial.md`。本节的证据链是 **报告原文 + 本文件的行数重算 + 对抗复推记录**，三者可独立核对。

**前稿错误的更正**：§8.2 曾写 `ListTasksResultViewProvider`（约 110 行）→ 实为 **119 行**（F-12）；`tree-status.txt` 与 `main-status.txt` 的差异（前者少 4 个测试文件）属采集时点差，不影响行数守恒。

### 8.3 读不出来的边界（保留为未测，不是"未覆盖"）

**① 拓扑（12）**：真 Redis Cluster（Lua 原文/CROSSSLOT/MOVED-ASK/槽迁移）、单 master 故障与主从切换、多 master SCAN 完整性、`maxmemory` 淘汰、真 PG（Flyway 锁/唯一约束/行锁/`lock_timeout`）、三 JVM 并发冷启动、跨机 NFS/PVC、反代真实空闲超时；并发：决策×park 真时序、取消×已放行、超时×回灌、僵尸接管×会话已前进、销毁×续跑、首轮无租约×接管、设施不可用×并发作业、唤醒真丢包。

**② 真机/真模型（8）**：真模型服从率、非安全上下文整条路径、3+ 页签真 Web Locks/bfcache、两页签同卡并发决策、真 Chrome 选择器/下载字节、**`el.click()` 对 `display:none` 的语义（决定 FE-32 的"写入生效"强度）**、**`el-tab-pane` 是否默认非懒惰（决定 FE-50 是"覆盖文件"还是"下载取服务端字节"）**、`<select>` 值不在 options 内时的显示（FE-60/BZ-25 的可见面）。

**③ 代码可达但缺用例（≥40，FA 新增标 ★）**：park 取消唯一终帧；`sid="{x}"` 隔离；★`sid="__ids__"` 与全局索引；★`staging:{x}:tok`；`/runs` 作用域；改 baseUrl 不外发 key；`/history` 503；`/cancel` 写失败；draining 期 `/chat`；executor 关闭在飞轮；首轮+僵尸交错；工具 >45s 心跳；advisor 重试请求体；两页签同卡"同步中"；`parts.ts` store 级；KB 空清单/仅元数据/CRLF-BOM/超宽表/`section` offset/`stop` 后请求/版本先记后建；表单多附件部分失败与 TTL/级联空集/step 极值；反馈超长与并发；`/health` 精确键集；authority 判定窗口；种子 provider 编辑后探活；KEK 轮换；DNS TOCTOU；`ContextState.fromFields` dirty；`ConversationTurns.firstPinnedTurn`；`aiResumeTrigger`/`aiStreamWatchdog` 的 MDC；摘要继承 maxTokens；`ToolScope` 下限；`session:current` 两会话轮次；`HELD-but-fence-ahead`；`saveSuspended` 只读；lenient 坏 entry；回放字节上限；★乱序 ≥17 条尾号低估；★跨实例回放缺口计数；★5 张映射回收；★会话锁续期失败后的 `degraded` 传播；★`Busy.runId()` 回退；★`session.lock.*`/`lease.renew-interval` 配置闸；★`GET /files` 失败后的写回禁止；★未加载标签页的保存/下载；★`export.start` 的条件结局；★`el-tab-pane` 语义；★同批并行工作区动作；★`restore_task` 的 `page`/`taskType`；★Excel 精度/零填充；★0 行发布；★跨类型建作业（REST）；★写侧分区注入；★≥2 滞留 SSE 订阅者；★`_meta` 同名表；★DATE 单元格；★`run_job` 同名工具出现后的路由 3 正路径；★`ui_snapshot` 去重跨 DOM 变更；★发布冲突 >50；★探活目标=表单值；★GF 段 `failed` 是否可达；**前端 §6.2-F 的 ≥30 条**。

**④ FA 自述的运行期未决（"不得据此派工"）**：`@ToolScope({})` 空数组双出口分叉（当前 0 实例）与 `L-94` 残余漏判；`ListTasksResultViewProvider.viewBudget` 在 `ui.max-frame-bytes∈[1KiB,8KiB)` 时超预算；JVM `user.language=tr/az` 下的裸 `toUpperCase()`（`import→İMPORT`）；`SheetSwitches` 是启动期本实例配置（多实例披露/执行结论不同）；`ContextBuilder.pageSelectedDefs` 以 `extra.pageId` 判选中集（客户端可控、无越权）；`FrameSizeLimiter` 对 `frame==null||mapper==null` 返回空 `byte[0]`（生产不可达）；`SessionGate.acquire` 重入分支生产几乎不可达（`resuming` 互斥）⇒ **I2 的"同 run 新 token 接管"实际只由单测驱动**；`generative_form` 的 `args` 是非可截字段、只靠 schema 上界（未做上界求和证明）；记忆装载路径是否 100% 把 `WRONGTYPE` 表现成 `/chat` 500（未逐层验证）；`isDeadRound→releaseDeadHeld` 是否删活轮锁（判据在本组文件外）；`JobTerminalWriter` 迟到终态 × `judge()` 的极窄竞态；`AiExecutorConfig` 两条看门狗线程对容量的影响；`AiProperties.ResultBudget` 与 `FrameSizeLimiter` 双常量漂移；`saveEdits` 与引擎竞速（未就绪时 `toXlsxBlob` 抛错而非覆盖）；Jackson 对 `""→Integer` 的强制转换；`withBusy` 重入与 `<el-button>` loading 是否吞双击；`resultview` 数值型 `defCode` 被消毒成空串（类型契约外输入）；`publish-links` 成功发布的 jobId 永久留表（S5，量级个位到几十）。

**⑤ 门禁工程（7）**：per-case 超时；假红修正；顺序断言；P2 边界；`frontend/**` 触发 e2e；真模型指标拆分；快照补丁与 main 候选重跑 cluster-3node（后者已在运行，见 §7.1-D/E）。

---

## 9. 计数（按实际证据类别）

### 9.1 总量

**223 条 canonical findings**（前稿 174 + FA1–FA6 48 + 失败归因确认 1）。已剔除 **23 条否证/撤回**（§2.3 R-1…R-22 + BZ-29 的"只能手工改库"子断言）。原始条目约 450 条，去重率约 50%。

> **本轮只加 1 条**（RT-39，"自愈派发被拒不落终态"），因为它在既有条目里没有宿主；另外两次失败归因把 **MD-09** 与 **PK-06** 从"条件/观测面"升级为"已由真实 run 证实的 S2"，**未新增条目**。其余 222 条一字未删、一字未改等级。

| 分组 | 条数 | C | R | T | D | 本轮净增 |
|---|---:|---:|---:|---:|---:|---:|
| ID 身份/隔离/密钥 | 15 | 6 | 7 | 0 | 2 | +1（ID-15） |
| PK park/门/工具契约 | 21 | 13 | 6 | 1 | 1 | +1（PK-21） |
| RT 运行/帧/租约/恢复 | 39 | 20 | 17 | 0 | 2 | +9（RT-31…38 + RT-39） |
| MD 模型/记忆/历史/上下文 | 21 | 16 | 4 | 1 | 0 | +1（MD-21） |
| BZ 业务/作业/文件/KB | 40 | 30 | 9 | 0 | 1 | +10（BZ-31…40） |
| FE 前端 | 71 | 53 | 11 | 4 | 3 | +27（原 29，撤 1 并 1） |
| CI 测试真实性/门禁 | 16 | 14 | 0 | 2 | 0 | 0 |
| **合计** | **223** | **152** | **54** | **8** | **9** | **+49** |

（升级而非新增：**MD-09** S3/R/V4 → **S2/C/V5**；**PK-06** S3/C/V4 → **S2/C/V5**。合并/撤回后的净变化：FE-45 撤回、FE-52 并入 FE-46。）

### 9.2 按验证模式（决定「这条有多可信」）

| 模式 | 条数 | 含义 |
|---|---:|---|
| **V1 对抗复核** | **88** | 经「重读实现 + 守卫 + 调用点 + 测试」独立复核（L/M/N/P-adversarial + FA-adversarial） |
| **V2 全量逐行（P）** | **27** | P1–P5 单人逐行，未经对抗盘问 |
| **V3 缺口代理静态** | **20** | G1–G5 单人按分配子集阅读；部分已被 N 降级/更正 |
| **V4 域报告静态** | **42** | A–J/I 单一域报告，深度不一（MD-09、PK-06 已移出本档） |
| **V5 CI 运行日志** | **13** | 有真实 run 证据（新增 MD-09、PK-06、RT-39） |
| **V6 本报告自行复核** | **7** | 本轮亲手核对的键形/行号/源码事实 |
| **V7 分片逐行（FA，未复推）** | **26** | FA1–FA6 逐行全读，但未被对抗复核覆盖（多为 S4 或 R 条件项） |
| 合计 | **223** | — |

> 可信度排序：**V1 ≈ V5 > V2 > V6 > V3 > V4 ≈ V7**。两条失败归因把 MD-09/PK-06 拉进 V5 档——这是本文件里**唯一由真实运行日志直接证实的缺陷组**，也是为什么它们排在最前。

### 9.3 按严重度与确定性

| 严重度 | 条数 | 变化 | 备注 |
|---|---:|---|---|
| **S1** | **7** | 不变 | 记忆双别名（ID-04）、key 外发、零条目假成功、`/runs` 枚举、补丁零验证（+ ID-01/ID-05 的多用户前提） |
| **S2** | **68** | +7（相对 174 基线） | 新增/升级：**MD-09**（假 `PROVIDER_GONE`）、**PK-06**（park 结算丢审计）、**RT-39**（拒绝不落终态）、FE-48/49/50/51；另有批准被吞、park 取消零终帧、取消后执行、僵尸覆盖记忆、重试重复、fencing 只到快照/第三租约态、DOM 可见性与快照缓存 |
| **S3** | **112** | +27 | 一致性/可观测性/窄窗口/条件风险；**新增**：ID-15、MD-21、RT-31/34/35/36/37、BZ-31/33/34/35/36/37/38/39/40、FE-46/47/53…61/67/69 |
| **S4** | **36** | +15 | 文案/死码/性能/策略待裁决；**新增**：PK-21、RT-32/33/38、BZ-32、FE-62/63/64/65/66/68/70/71/72/73 |

| 确定性 | 条数 |
|---|---:|
| C 代码可证 | 152 |
| R 条件（窗口/拓扑/配置） | 54 |
| T 缺测/未验证 | 8 |
| D 已裁决边界 | 9 |

### 9.4 本轮 FA 条目与失败归因条目的可信度

| 事实 | 说明 |
|---|---|
| FA 48 条中 **22 条升 V1** | FA-adversarial 复推确认：PK-21、ID-15、RT-34/35/36/37、MD-21、BZ-31/33/34/35、FE-16/46/47/48/49/50/51/57/64/67/69（BZ-35 为 PARTLY） |
| FA **26 条仍为 V7** | 报告等级、未被对抗复核覆盖（多为 S4/R） |
| **失败归因新增/升级 3 条** | **MD-09**（S3→S2，R→C，V4→V5）、**PK-06**（S3→S2，V4→V5）、**RT-39**（新增 S2/C/V5）。证据是两次 run 的产物（`cluster3-34fd8f4-*`、`main-ci-34fd8f4-*`），不是静态推断 |
| 新增 S1：**0** | 两次失败都不构成新的 S1：MD-09 的数据面可恢复（重启/真实写入即收敛）、PK-06 是观测面、RT-39 是收敛缺陷 |
| 新增覆盖面 | 工具契约（PK-21/PK-06）、记忆键族（ID-15）、帧存储（RT-31/32/33/38）、会话锁（RT-34/35）、销毁面（RT-36/37）、配置播种（MD-09）、作业运维（BZ-34…40）、导入写回链（FE-16/46/47/48）、Excel 保真（FE-49）、导出保存链（FE-50…57）、设置页（FE-58/59）、工作区时序（FE-67…72） |
| 被新证据**加强**的既有条目 | PK-11、PK-16、PK-17、RT-09、BZ-01、BZ-25、FE-09/10/14/15/18/19/20/25/29/30/32/33、CI-13/14；**新增**：MD-09（来自 CI-02 的假绿面）、PK-06（来自 CI-05 的"计数不增长"） |

### 9.5 算术校验

- 分组和：15 + 21 + 39 + 21 + 40 + 71 + 16 = **223** ✓
- 严重度和：7 + 68 + 112 + 36 = **223** ✓
- 确定性和：152 + 54 + 8 + 9 = **223** ✓
- 模式：88 + 27 + 20 + 42 + 13 + 7 + 26 = **223** ✓
- §5 各表行数：ID 15、PK 21、RT 39、MD 21、BZ 40、FE 71、CI 16 = **223** ✓

---

## 10. 对抗裁定已并入（P 轮 + FA 轮）

> 来源：`P-adversarial.md`（P 轮）与 `FA-adversarial.md`（FA 轮）。方法：对每条新的 S1/S2/Important 断言重读实现、调用点与具名测试；**未构建未运行**，全部为静态裁定。P 轮见 §10.1；FA 轮见 §10.3（全表），FA 轮未复推项见 §10.4。

### 10.1 已并入的裁定（28 行受影响）

| 裁定 | 条目 | 处理 |
|---|---|---|
| **CONFIRMED**（22 条新条目升 V1） | RT-20/21/22/23/24/28、PK-18/19/20、FE-21/22/23/26/32/33/34/39、BZ-25/26/27/28/29 | 证据升 V1；按裁定调整严重度（汇总见 §9.3） |
| **CONFIRMED 但严重度下调** | FE-21/22/23/34、RT-23（S2→S3）；BZ-29（S3→S4，且「只能手工改库」被否证） | 已改行内 Sev，并把更正写入行注 |
| **PARTLY** | FE-17（触发需滞后读）、BZ-15（凭据重放需不可信上游）、BZ-29（可经设置页恢复） | 保留条目、收窄条件 |
| **UNVERIFIED** | FE-24（观察器重绑定） | Cert 改 T，明确不据此派工 |
| **既有条目再确认** | BZ-15、BZ-18、BZ-20（升 V1）；RT-01 的「终帧永久缺失」扩展成立 | 已更新行注 |
| **测试真实性再确认** | `ai-region-batch-b` 把隐藏步可见写成期望；两个 closure 测试需自调 `bumpAiEpoch()`；`sheet-preview-highlight:152` 在假适配器上不可能变红；`parts-residue-cleanup:126-145` 不断言 `suspended`；`restoredPendingQueue`/`revealNextRestoredPendingCard`/`toggleExpand` 零引用 | 已入 §6.2 |

### 10.2 已证条目内仍未闭合的量级/前提（不是待裁定项）

| 项 | 事实 | 处理 |
|---|---|---|
| **FE-24**（折叠/展开观察器重绑定） | 只有代码路径，无端到端复核；两次对抗轮均未复现 | 仍为 **T（未验证）**，不据此派工；这是**事实缺口**，不是待填的裁定 |
| **FE-50 的决定性前提** | `el-tab-pane` 无 `lazy` ⇒ 依框架默认应急切挂载，**未运行验证** | 行内已标"前提见注"；若实为懒惰渲染则降 S3（只影响下载侧） |
| **P3-04 / P4 F-S4/F-S5 与 FA 的量级项** | 时延/内存只按结构定级（RT-26 的 6000×64KB、RT-33 的按 run 增长、BZ-36 的 k×15s） | 未复现、未压测；等级按构造，不按实测 |
| BZ-35（条件风险） | PARTLY：判死语义是设计，新增部分是"写侧分区不可区分" | 条件风险；需写侧分区注入才可判 |

### 10.3 FA-adversarial 裁定全表（**已全部执行，无待办**）

> 来源：`FA-adversarial.md`。方法：对 FA1–FA6 的**全部 S1/S2/Important 与影响验收的测试真实性断言**，从生产代码与具名测试重新推导；**未构建未运行**。下表 26 行 = 该文件的裁定逐条落地结果。

| FA 原条目 | 裁定 | 本文件落地 | 现等级 |
|---|---|---|---|
| FA3-N1（换任务跨任务写） | CONFIRMED 机制，**S1→S2** | FE-16：S2 确认、前提写明、V1、否决 S1 提级 | S2 |
| FA4-01（未加载标签页覆盖导出件） | CONFIRMED 机制 | FE-50：S2、V1、`el-tab-pane` 前提注明 | S2 |
| FA3-N5（`/files` 失败 + 无条件写回） | CONFIRMED | FE-48：S2、V1 | S2 |
| FA3-N12（NUMBER 改写） | CONFIRMED | FE-49：S2、V1 | S2 |
| FA2-N1（`__ids__` 全局索引） | CONFIRMED，**S2→S3** | ID-15：S3、V1、加"自愈/无消费者/演示边界"三条理由 | S3 |
| FA2-N5（会话锁静默降级） | CONFIRMED | RT-34：V1，补"只有异常分支置 `degraded`" | S3 |
| FA2-N6（`Busy.runId()` 回退） | CONFIRMED | RT-35：V1，补"进程内门已在 `:222` 释放" | S3 |
| FA2-N8（记忆写回 TOCTOU） | CONFIRMED | RT-36：V1，补"`DONE` 早于 `convergeMemory`"的结构窗口 | S3 |
| FA2-N9（staging 不在销毁面） | CONFIRMED | RT-37：V1 | S3 |
| FA2-N7（守卫覆盖） | CONFIRMED（仅 lease/session-lock 段） | MD-21：V1；池容量/语义键降为"配置卫生、未复推" | S3 |
| FA1-N2（向导闸空值/重复） | CONFIRMED | BZ-31：V1 | S3 |
| FA1-N4（`/history` 重投影） | CONFIRMED | BZ-33：V1 | S3 |
| FA1-N1（第三截断面） | CONFIRMED | PK-21：V1 | S4 |
| FA1-N3（`list_tasks` 20 行不变量） | **Plausible，未复推** | BZ-32：仍 V7，标注"未独立复推" | S4 |
| FA4-02（`startExport` 忽略结局） | CONFIRMED | FE-51：V1 | S2 |
| FA4-03（陈旧 `exportJob`） | CONFIRMED | 与 FA3-N3 **合并**为 FE-46；V1 | S3 |
| FA3-N3（导入起作业假成功） | 同机制（并入 FA4-03） | 同上 | S3 |
| FA3-N4（勾选重载丢编辑） | CONFIRMED，**S2→S3** | FE-47：S3、V1，理由"从未保存、可重填" | S3 |
| FA4-08（只读 fail-open） | CONFIRMED 机制 | FE-57：V1 | S3 |
| FA4-11 / BZ-25（级联旧值） | CONFIRMED | BZ-25 行注补自然触发路径（V1 保持） | S3 |
| FA3-N2（`goto_step` 不写回） | **FALSE POSITIVE（可达性）** | FE-45 **撤回**；§2.3 R-22；编号留空 | — |
| FA3-N7（建作业守卫 O(N)） | CONFIRMED，**S2→S3** | BZ-34：S3、V1，纠正"文案错误"为"码复用" | S3 |
| FA3-N8（非对称分区） | **PARTLY** | BZ-35：条件风险、Cert 改 R、V1 | S3 |
| FA5-N1（第二层陈旧快照） | CONFIRMED | FE-33 行注（第二层）+ §6.2 测试真实性 | S2（宿主） |
| FA5-N4（掩码正则） | CONFIRMED | FE-64：V1 | S4 |
| FA6-03（`restore_task` 丢类型） | CONFIRMED | FE-69：V1 | S3 |
| FA6-01（工作区无串行化） | CONFIRMED 机制（R） | FE-67：Cert 改 R、V1 | S3 |
| FA1-CORR-1（`ResultViews` 未消毒） | 接受更正 | §2.3 R-13（子句收窄） | — |

**应用规则（已执行完毕）**：FALSE → 从 §5 移除并进 §2.3；PARTLY → 保留行、收窄条件/改 Cert；CONFIRMED → 升 V1 并按裁定调级；同一机制 → 合并到一个宿主行。

### 10.4 这次对抗复推**没有**建立的东西

FA1-N3、FA3-N6/N16/N17、FA4-05/06/07/09/10/12、FA5-N2/N3/N5–N7、FA6-02/04–11、FA2-N2/N3/N4/N10 均**按报告自身等级接受**，未独立复推（多为 S4 或 R 条件项）。因此：**"V7"在本文件中就是"未复推"的同义词**；引用这 26 条时不要当成已验证。

---

## 11. 不声称清单

1. 不声称"全链路已通过"：§8.3 列出的边界仍全部未闭环（拓扑 12、真机/真模型 8、缺用例 ≥40、FA 自述未决 20+、门禁 7）。
2. 不声称"多实例可用"：快照上唯一多实例网 4/4 红（5 真红）；main 候选 15/16，唯一失败已定因为**假 `PROVIDER_GONE` + 拒绝不落终态**（§7.1-E），修复未合并未验证。
3. 不声称"Redis Cluster 已支持"：产品从未在真 Cluster 上执行，Lua 原文一次未跑。
4. 不声称"PostgreSQL 已支持"：**所有可引用 run 中零覆盖**；两条 main run 的 workflow 也不含 PG 步骤。
5. 不声称"多用户已隔离"：无用户维度；ID-04/ID-15/ID-02 补认证后仍成立。
6. **不声称"PK-01/Flyway 已修"**：main 有 latch / migration lock 候选且已 push；两条 run 都已跑完，剩余 1 条失败与 PK-01/Flyway **无对应关系**（分别是配置播种与 park 结算）⇒ 只能写"候选存在、未被证明"。
7. 不声称"补丁已修复"：§2.2 的 8 项均为 LATER-CANDIDATE，且**没有任何一条 run 跑的是快照补丁**。
8. 不声称"2000+ 单测绿 ⇒ 生产正确"：§6.2/§6.4 列出 40+ 条假绿/弱断言/自证。
9. 不声称"E2E 全绿"：快照 HEAD 的 e2e 是 `cancelled`；main 的两条 run 都是 **failure**（165/1、15/1），且两处红都已定因为产品缺陷。
10. 不声称"真模型达标/不达标"：**本轮 `@real` 未触发**；唯一可引用结果仍是 `40171918873` 的 8/1（唯一红归因错误）。
11. **不声称"新发现已修"**：FE-16…FE-73、RT-20…RT-38、PK-18…PK-21、BZ-25…BZ-40、MD-21、ID-15 **全部未修**；**这次审计本身没有、也无权做任何修复**（未改代码/测试/配置/工作流，未触发 CI）。
12. **不声称"这 54 个文件已无更多缺陷"**：它们已逐行读完（§8.2），但覆盖 ≠ 正确；只在静态层复核，未运行任何测试。
13. 不声称 FE-24 已证：仍为「未验证」（T），不得据此派工。
14. **不声称"两条失败已修/已验证"**：两处根因**已定**（§7.1-D/E），但修复只在**隔离副本**中，未合并、未跑 CI、未复跑任何 workflow；因此只能说"缺陷已定位"，不能说"缺陷已修复"。
15. 不声称"零风险"：**122 条只由单一读者支撑**（V2 27 + V3 20 + V4 42 + V6 7 + V7 26），未经对抗复核；若被推翻，最可能顺序是 **RT-02（条件苛刻）→ ID-03 的 LAN 段 → RT-04 的可见性段 → FE-50 的 `el-tab-pane` 前提**。
16. 不声称"评分/结论可替代现场验证"：本文件是**静态审计 + 现有 run 日志**的合成，不作绝对保证，也不替代真 Cluster/真 PG/真机/真模型的实测。

---

## 12. 交付边界（本审计做了什么、没做什么、什么会改变结论）

### 12.1 本审计的动作

- **只读**：未改任何代码 / 测试 / 配置 / 工作流；未触发 CI；未启动服务；未运行任何测试（`mvn`/`vitest`/Playwright 均未执行）；未读写任何密钥值。
- **没有修复任何条目**：本文件列出的 223 条缺陷，**没有一条由这次审计修复**；两次失败对应的修复也**不是本审计做的**（在隔离副本中由他人进行，未合并、未验证），与本次只读审计无关。
- **唯一写入物**：本文件 `FINAL-AUDIT.md`；只读取了 `final-gap/FA1-report.md … FA6-report.md`、`FA-adversarial.md` 与既有域报告，无其他改动。

### 12.2 快照与 main 候选的边界（不可混用）

| 维度 | 快照 `ae883fb` + `working.patch` | main 候选 `34fd8f4…` |
|---|---|---|
| 本文件所有缺陷判据 | **以此为准** | 不作判据 |
| CI 证据 | 无任何 run 跑过该补丁（CI-03） | 两条 run 均 **COMPLETED failure**（165/1、15/1） |
| 多实例网 | 4/4 failure（9 PASS / 7 FAIL） | 15 PASS / 1 FAIL（`suspend-cross-instance` SSE 超时） |
| 能否说"已修" | 否 | 否（各剩 1 条已定因的产品红；修复在隔离副本，未合并未验证） |

### 12.3 什么会改变本文件的结论（按信息价值排序）

1. **两处修复落地并重跑两网**：`AiConfigSeeder` 发布版本（+ 派发被拒落终态）后，`suspend-cross-instance` 与 `cold-start-3` 的"非 synthetic provider"断言应同时转绿；`SheetAudit.record`/`tool_call_end` 补到 `submitFrontendResult` 后，`AIE2E-T-13` 应转绿且 `tool_call_start − tool_call_end ≈ 0`。**在这两步完成前，本文件不把 MD-09/PK-06/RT-39 标为已修**。
2. **真 Cluster 矩阵**：3 主 3 从 + 3 JVM + `application-cluster.yml`（Q2 的三个阻碍解决后）。它会一次性检验 RT-31/RT-32、RT-34/RT-35、BZ-21 等 8 条"条件/跨实例"结论。
3. **真 PG**：Flyway 锁、唯一约束、行锁、`lock_timeout`——影响 RT-19、MD-07、BZ-03。
4. **一次 `@real` 复跑（N≥10）**：能判定 FE-32 的"回执报成功"、FE-33 的"点回去"是否在真模型下稳定出现。
5. **真机单条**：`el-tab-pane` 是否急切挂载（FE-50 定级）、`el.click()` 对 `display:none` 的语义（FE-32 强度）、`<select>` 无匹配项的显示（FE-60/BZ-25 可见面）。
6. **两页签/两用户并发注入**：FE-01/FE-07/FE-67、ID-02/ID-05/ID-10、RT-34/RT-35 的现发率。

### 12.4 一句话

**这份审计的结论是"哪些保证不成立、证据到什么程度"，不是"能不能上线"**；两条已定因的缺陷说明门禁现在能抓到真问题，但"修复完成"仍需要 12.3 的第 1 项（修复落地 + 重跑两网）来证明。
