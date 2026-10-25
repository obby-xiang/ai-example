> 来源=<仓外证据>/ai-full-audit/K-synthesis.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# K｜AI 全链路审计综合与对账（只读验收 · 汇总报告）

> 输入：`A-inventory.md`、`B-runtime-sse.md`、`C-park-tools.md`、`D-model-history.md`、`E-business-ai.md`、`F-redis-infra.md`、`G-identity-isolation.md`、`H-frontend-chat.md`、`J-test-ci-integrity.md`（九份）。
> 快照：`<仓外证据>/ai-full-audit/tree`（base `ae883fbae52be36e45a635ee276cf8298ac24bd2` + 未提交 `working.patch`，SHA256 `6c88b47d…dab955`）。
> 本报告只读合成：未修改任何源码/测试/配置/工作流/Git 历史，未启动服务，未触发 CI，未读写密钥。唯一写入物是本文件。
> 口径：**不给"全部保证"结论**。每条结论都带证据类型与成立条件；「已证实」= 代码路径可证（本报告对其中 18 条做了二次独立复核），不等于运行期复现。

---

## 0. 元信息：输入完整性缺陷（必须先讲）

### 0.1 第十分报告 `I-frontend-workspace.md` 不存在

任务要求的十份报告中，`<仓外证据>/ai-full-audit/` 目录下只有 **9 份**（A、B、C、D、E、F、G、H、J）。
`I-frontend-workspace.md` **缺失**，因此 A 的清单 §8 划给 D6（前端业务联动）的必读面，没有被任何域报告承接。

这不是形式问题。经逐文件清点（§9.2 有证据），以下前端 AI 生产文件在任何域报告中出现次数为 0，或仅出现在"未覆盖"清单里：

| 文件 | 行数 | 域报告提及 | 后果 |
|---|---:|---:|---|
| `frontend/src/utils/sheet-tools.ts` | 2918 | 1（仅"未覆盖"） | 表格读/写/聚合/撤销/预览档位全部未审 |
| `frontend/src/utils/ai-dom-tools.ts` | 1718 | 0 | 浏览器 DOM 动作（点击/填值/选择/撤销）未审 |
| `frontend/src/utils/ai-regions.ts` | 467 | 0 | 区域注册表与"页面就绪"判据未审 |
| `frontend/src/utils/resultview.ts` | 410 | 0 | 工具结果视图重建未审 |
| `frontend/src/components/SpreadGrid.vue` | 760 | 0 | AI 写目标（表格内核）未审 |
| `frontend/src/components/AiPanel/FormRenderer.vue` | 791 | 1（仅"未覆盖"） | 表单渲染/联动/校验未审 |
| `frontend/src/components/AiPanel/SheetPreviewCard.vue` | 221 | 0 | 预览卡交互未审 |
| `frontend/src/components/AiPanel/UploadHandoffCard.vue` | 111 | 0 | 上传交接卡未审 |
| `frontend/src/stores/workspace.ts` | 639 | 2（均"只到契约面"） | `buildContext` 五键与 recentActions 未实质审 |
| `utils/{blocks,tool-summaries,ai-suggestions,context-usage,form-scope,ui-receipt,publish-mode,publish-links}.ts` | 585/265/112/200/20/93/61/32 | 0 | 段→块投影、工具卡摘要、建议词、上下文条、发布链接未审 |
| `frontend/src/views/AiFeedbackView.vue` | 82 | 0 | 赞踩视图未审 |
| `frontend/src/types/form-schema.ts` | 832 | 0 | 表单 schema 类型契约未审 |

**结论**：前端"业务联动"整域（A 的 D6）**没有域级审查**，只有 A 的登记与 E 的契约面。任何"前端业务动作用例已覆盖"的表述都不成立。

### 0.2 九份报告的基线一致性

九份都声明同快照、同补丁 SHA、未启动服务、未跑拓扑。**但只有 J 真正核对了 CI 运行记录**（`gh run view`），并发现关键事实：所有 CI/集群证据都产生于 **`ae883fb` 未打补丁的树**上，`working.patch` **零 CI 验证**。
这点被其余八份普遍忽略——它们读的是"打补丁后的快照内容"，却把结论写成"当前状态"。

**因此本报告对所有补丁相关项统一标注 `补丁已改·未验证`，交指挥官对账，不猜测已修。**

---

## 1. 结论摘要

### 1.1 已证实缺陷（代码级；标 ★ 的经本报告独立复核）

| # | 结论 | 严重度 | 为什么是它 |
|---|---|---|---|
| S-01 ★ | **park 退出与决策的竞态：`woke=true` 但无人消费 → 批准被静默吞掉、整轮挂死** | Important | C 域唯一，A/B 未覆盖。latch 在 park 分支之前登记、park 分支不消费、`triggerResume` 见 `woke` 即不派发 |
| S-02 ★ | **记忆键命名空间别名：`sessionId="{victim}"` 可读并删除受害者真实记忆** | Critical/Important | `legacyKey("{a}") == key("a")`。绕过"id 不可猜"这一唯一登记前提 |
| S-03 ★ | **`GET /api/ai/runs` 全局枚举**：一次 GET 拿到所有 sessionId + runId + 待决工具参数 | Critical/Important | 直接推翻 CH-P7 前提（登记的是"没有绑定"，没登记"系统自己发 id"） |
| S-04 ★ | **配置写 + 探活可把库中 API Key 发到任意主机** | Critical（受信网内） | PUT 省略 apiKey=保留原 key + `/test` 对 baseUrl 发 Bearer；闸只拦私网/元数据 |
| S-05 ★ | **park 场景取消不产生终帧** | Important | 快照写 CANCELLED，事件日志无 `done`/`error`；归档违反"恰好一个终帧" |
| S-06 ★ | **首轮从不取续跑租约 → 常规轮全程无 fence，`save()` 走 plain SET** | Important | 取租点只有 3 处；僵尸接管后旧实例苏醒可覆盖新终态、再发一个 `done` |
| S-07 | **取消之后仍会执行已放行的工具** | Important | `awaitConfirm→executeOnce`、`resolvePending→executeOnce` 之间无取消复查；对 import/publish 实质危害 |
| S-08 | **超时/取消写回不经 guard、非 CAS** → 丢用户输入 + 双结局帧 | Important | `expireFrontend`/`cancel` 裸写 `putPending` |
| S-09 | **空任务/未选配置的 `start_*` 假成功** | Important | 四条链都能"COMPLETED 但零数据" |
| S-10 | **Redis 抖动期业务帧被静默丢弃、心跳照常** | Important | 正文缺段且前后端都无法察觉 |
| S-11 | **重试把用户消息重复写进记忆并重复进请求**（advisor `before` 无条件 add） | Important | 服务级用例全部 mock 掉 advisor，结构性盲区 |
| S-12 | **IMPORT/PRECHECK 缺终态兜底** → 悬置 RUNNING 最长 30 分钟 | Important | EXPORT 侧已修、此两处漏修 |
| S-13 | **作业运行期改条件被静默回退**（长事务 + `TaskItem` 无 `@Version`） | Important | 错误发生在"用户的修正"上，决定数据出口 |
| S-14 | **三实例网 4/4 全红，6 条同签名可复现** | Critical（交付面） | 唯一有运行证据的结论（`40174291244`@ae883fb、`40173316364`@965e6ef） |
| S-15 | **提交门禁 E2E 在 HEAD 跑不完**：130 条只执行 17 条后停住，被 25 分钟超时取消 | Critical（门禁可信度） | "红"= 没跑完（`40174289141`） |
| S-16 ★ | **park=true 是出厂配置，但只有 7 个测试文件显式开启** | Important（假绿） | 2000+ 单测多数验证阻塞老路径 |

### 1.2 完全未被任何域审到的地方

1. **前端业务联动整域**（§0.1 的 14 类文件，含 2918 行 `sheet-tools.ts`、1718 行 `ai-dom-tools.ts`）。
2. **真实 Redis Cluster × 产品 JVM**：零自动化；3 实例网是单节点 Redis（本报告复核 `cluster3-run.sh`，无 `--cluster-enabled`）。
3. **真实 PostgreSQL**：`grep -rn postgres .github scripts` 零命中。
4. **真实模型服从率**：一次 dispatch、9 条、`retries:0`、无复跑。
5. **多用户隔离**：产品无用户维度，不可测。
6. **Lua 原文执行**：13 条脚本从未在真 Redis 上跑过，替身按注释复刻。
7. **多 master SCAN / 单 master 故障**：只有 fake。
8. **≥3 JVM 下 park/决策/取消的真实竞争**：只有同 JVM 双对象。
9. **非安全上下文（http + 内网 IP）前端路径**：全部测试跑在 `127.0.0.1`/jsdom。
10. **补丁本体**：6 个新测试从未进入任何 run。

---

## 2. 判定口径

### 2.1 状态分类（统一各域自造词）

| 标签 | 含义 | 可否作为缺陷派工 |
|---|---|---|
| **已证实缺陷（代码级）** | 读代码即可推出，触发条件明确 | 是 |
| **已证实缺陷（运行级）** | 有真实 run 证据 | 是（最强） |
| **条件风险** | 机制确定，需特定交错/拓扑/配置才触发 | 是（附条件） |
| **仅缺测试** | 无证据表明坏，也无验证 | 否（补测试） |
| **未验证** | 无法在本快照证伪，需外部拓扑/真机 | 否（补证据） |
| **已登记的 demo 限制** | 文档明文声明并接受 | 否（产品裁决） |
| **已裁决非缺陷** | 明确裁决过的语义取舍 | 否 |

### 2.2 证据类型编码

`E1-复核` = 本报告作者独立复核源码（18 条）；`E2-代码` = 报告声明代码可证、本报告未二次复核；`E3-CI` = 真实 run（附 id）；`E4-测试` = 单 JVM/fake 断言支撑；`E5-缺证` = 仅缺测试或未验证。

### 2.3 严重度刻度

`Critical` = 数据/密钥/不可逆写外泄、无人工干预的持久错误、或门禁失效致结论不可信。
`Important` = 用户可见错误结果、静默丢内容、整轮挂死、多实例承诺不成立。
`Minor` = 文案/一致性/可观测性/窄窗口性能。`Info` = 观察项/已裁决取舍。

---

## 3. 后端保障矩阵

> 前提：原生 Redis Cluster（3 主 3 从）、共享 DB/文件、≥3 JVM、同一对话请求可无粘滞落任意实例。
> 状态列：**成立(条件)** / **部分成立** / **不成立** / **未验证** / **未覆盖**。"成立"指该条件下代码/测试可证，不是生产保证。

### 3.1 Redis Cluster

| 能力 | 机制 | 证据 | 状态 | 缺陷 |
|---|---|---|---|---|
| run 级 13 键同槽 | `runScopedKey` 统一 `{run:<id>}` tag | `RunStore:466-474`；`ClusterSlotTest:149-178` | 成立(条件：键都经该入口) | — |
| 租约三键同槽 + 取号与占用同序 | `ACQUIRE` Lua，`epoch=max(INCR,fence+1)` | `LeaseService:155-180,1042-1067` | 部分成立：**Lua 原文未执行** | F-03 |
| 台账+幂等索引原子 | `LLEN` 先取、`HSETNX` 首写胜 | `RunStore:340-352` | 部分成立（同上） | F-03 |
| 记忆 staging→RENAME 同槽 | staging tag 与 final tag 逐字相同 | `RedisChatMemoryRepository:193-199,304-340` | 成立(条件) | — |
| 销毁 13 键一条 DEL | 同 tag | `RunStore:1029-1063` | 成立(条件) | F-14（观测键 3 键跨槽） |
| 逐 master SCAN | `keyCommands().scan` / `cluster.scan(node,…)` | `RedisClusterScanSupport:86,118` | 部分成立（fake 上验控制流） | F-10（门禁不匹配该写法） |
| **真实 Cluster 行为** | — | 单测不连 Redis；无 Testcontainers；3 实例网单节点 | **未覆盖** | F-03 / J-F-08 |
| 单 master 故障 | `ping()` 逐 master | `RedisAvailability:86-104`（复核调用点） | **不成立（反向）**：任一 master 异常 ⇒ 全站 AI 503 | F-02 |
| CROSSSLOT/MOVED-ASK/5.0 语法 | — | 替身复刻 | **未验证** | F-03 |

### 3.2 ≥3 JVM 无粘滞

| 能力 | 机制 | 证据 | 状态 | 缺陷 |
|---|---|---|---|---|
| 同会话单轮互斥 | 单键 Lua + token 精确释放 | `SessionGate:80-143`；`SessionBusyFrameIsolationConformanceTest` | 成立(条件：Redis 可用) | 降级进程内门时跨实例失效 |
| 跨实例驱动唯一 | `ai:lease:run` + fence + `verifyHeld` | `ResumeService:613-631` | 成立(条件) | **never-held 绕过整机制**（S-06） |
| 决策恰一次 | `ai:decided` HSETNX + 复判 + WAL | `ConfirmGate:1150,1186-1190` | 成立(条件) | 幂等成立≠派发存活（S-01）；WAL 只 3 态（C-F4） |
| 工具副作用恰一次 | `claimExecution` + 台账 + fence | `RunStore:1688-1781` | 成立(条件：slot 可用) | — |
| 跨实例帧补帧 | `ai:xevents` + `ai:wake` + 逐订阅者 floor | `SseChatEmitter:1556-1633` | 部分成立 | 补丁前实测 seq 回卷（J-F-06）；窗口缺口不可见（B-F-D） |
| 决策/历史/配置/销毁跨实例 | — | `idempotency-cross-instance`、`history-pagination`、`config-hot-reload`、`session-purge-cross-instance` **PASS** | 成立(条件：单节点 Redis + H2 TCP) | 非 Cluster、非 PG |
| 无粘滞拓扑 | HAProxy `roundrobin`、无 stick-table | `cluster3-run.sh:113-120`（复核） | 成立(拓扑)；**顺序未断言** | J-F-13 |
| 真实 ≥3 JVM 全链 | — | cluster-3node 4/4 红 | **不成立** | J-F-02 |

### 3.3 重连（reattach）

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 挂订阅与回放原子 | 成立(条件：写方存活) | `SseChatEmitter:666-714` | — |
| 终帧收尾判据 = 归档有无终帧 | 成立(条件) | `AiController:740-779` | park 取消无终帧时挂到看门狗（S-05） |
| 差量 `?lastSeq` 不重不漏 | 部分成立 | `ReattachLastSeqConformanceTest`；`reattach-any-instance` PASS | 窗口外静默跳过（B-F-D）；接管后 seq=1 回卷（J-F-06，补丁已改未验证） |
| 唤醒丢失兜底 | 部分成立 | 仅前端 45s 看门狗 | 服务端无周期补帧（F-08）；非浏览器客户端永久停住 |
| 死轮被重读续命 | 不成立 | `RunStore:2363-2385` | 每次刷新续 24h，purge 永不 deferred 自愈（B-F-H） |

### 3.4 取消

| 场景 | 状态 | 证据 | 结果 |
|---|---|---|---|
| 驱动线程在跑 | 成立(条件) | `cancelTerminal:1249-1302` | `done{cancelled:true}` |
| **park 中取消** | **不成立** | `ResumeService:689-698`（复核）；`AiController:873-878` | 快照 CANCELLED，**无终帧**（S-05） |
| 跨实例取消 | 成立(条件：Redis 读成功) | `ai:cancel` 权威 + 1s 轮询 | `UNKNOWN` 时继续等（不误判） |
| Redis 写失败 | 不成立 | `AiController:860-862`（复核 `cancelled:true` 无条件） | 200 + `cancelled:true`，轮次在别实例继续（A-F6/B-F-J） |
| 取消后不再产生副作用 | **不成立** | `SpToolCallingManager:1320-1328,1816-1831` | S-07 |

### 3.5 park（人工等待）

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 线程交还 + 状态全外置 | 成立(条件：`RunParkedException` 原样穿出) | `ConfirmGate:592-623` | **U1 未验证**：Reactor 包一层即全 park 轮失效（C-U1） |
| 帧序 `suspended→tool_start→request` | 成立(补丁后) | `ParkToolStartOrderConformanceTest`（新） | 补丁零 CI 验证；代价是等待期"running"（已裁决非缺陷） |
| 多卡整批请求帧 | 成立(条件) | `publishRequestsForParkedBatch:471-504` | — |
| **决策 × park 退出** | **不成立** | latch 预登记（复核 `ConfirmGate:558` 先于 `601-623`）+ `triggerResume` 见 `woke` 即 skip | S-01 |
| 即时前端工具到期收敛 | 部分成立 | `scheduleParkedFrontendExpiry` 进程内单线程 | 实例死亡后永不收敛（C-F5） |
| 前端多张人机卡同时 live | 不成立 | `stores/ai.ts:3328,3371,3479` 单例覆盖 | 只有最后一张可交互（A-F2b） |

### 3.6 续跑（resume）

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 准入序（取消/终态/RUNNING/PROVIDER_GONE/PENDING） | 成立(条件) | `ResumeService:613-802` | — |
| 精确 provider 绑定 | 成立(条件) | `AiResumeExactProviderBindingTest` | 旧快照走名字兼容（已登记） |
| 会话门一次性 token | 成立(条件) | `ResumeService:772-786` | — |
| park 期间"又发一轮" | 部分成立 | 15 次退避 ≈360s | 超时停在"已决无人执行"，用户面只有日志（C-U6） |
| 启动扫描配额 | 部分成立 | `StartupResumeRunner:136-167` | 按"尝试数"计，11..N 永不自动续跑（F-06） |
| 僵尸 RUNNING 接管 | 部分成立 | `forceTakeover` 只校验快照自身 | 不校验"会话是否已前进" → 旧轮复活覆盖记忆（B-F-E2） |
| **startup 之后无周期扫描** | 不成立 | `StartupResumeRunner.onReady` 一次性 | 死轮只能人工/重启（B-F-B） |

### 3.7 kill / 接管 / 恢复

| 场景 | 状态 | 证据 | 用户可见 |
|---|---|---|---|
| kill 时在 park 等人工 | 成立(条件) | 3 实例网设计路径 + 租约 30s TTL | 正常（B §5.1） |
| kill 时在流式（RUNNING） | **不成立** | `AiController:416-435`；`RunLiveness:42-62` | 180s 内 409 "发送失败"；旧轮无人接管；seq 回卷 |
| 接管后 tool_start 恰一次 | **不成立（实测）** | `40174291244` 期望 1 实得 0 | 卡片不显示/不可提交 |
| 丢租后（Redis 断 > 30s） | **不成立** | `LeaseService:686-709,844-850`；`triggerResume` 只认 SUSPENDED | RUNNING 轮静默停摆（B-F-O） |
| 死轮 → 会话销毁 | **不成立** | `AiSessionPurgeService:742-760` | 永久 `complete=false`、同 sid 永久 410（B-F-F2） |
| 三 JVM 并发冷启动 | **不成立（间歇 2/3）** | `flyway.baseline-on-migrate` + run 证据 | 至少一个实例启动失败（J-F-07） |
| 销毁 vs 在飞写入 | 条件风险 | guard 只查一次 | 已销毁会话内容"复活"（F-04） |

### 3.8 停机 / 就绪

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| `/health/ready` 依赖探测 + 2s 预算 | 成立(条件) | `ReadinessService`；`ReadinessProbeRunner` | — |
| Redis 不摘 ready | 成立 | `redis-outage-recovery` PASS | — |
| 无 key 不摘 ready | 成立 | `readiness-removal` 第①段 PASS | — |
| SIGTERM draining 可观测 | **不成立（实测）** | 两次 run 同签名：15s 内无 `ready=false`，全 ECONNREFUSED | 补丁已加 gate 未验证；`cluster` profile 无 `server.shutdown=graceful`（F-11） |
| AI 运行面收敛 | **不成立** | `AiExecutorConfig:52`（复核）；`/chat` 无 `isDraining()`（复核 grep 零命中） | 在飞轮被 `shutdownNow` 硬切，快照停 RUNNING（A-F4） |
| 唤醒监听容器回收 | 不成立 | `RedisFrameWakeBus:105-135` 无 `@PreDestroy` | 非守护线程可阻 JVM 退出（F-09） |

### 3.9 配置

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 热更新跨实例 | 成立(条件：单节点 Redis) | `config-hot-reload` PASS | — |
| 写路径 SSRF 闸 | 成立(条件) | `AiEndpointGuard:64-113` | 只挡私网/元数据；公网 http 放行（S-04 前门） |
| **写 + 探活组合的密钥保护** | **不成立** | `AiConfigWriter:309-310`；`AiConnectionProbe:151`（复核） | S-04 |
| 写并发（两实例同时保存） | 不成立 | `DefaultsCommand` 无 version | 后写覆盖（D-F-08） |
| 种子/Flyway 回填推进版本 | 不成立 | `AiConfigSeeder:111-150` 不 bump | 滚动重启只一台生效（D-F-09） |
| `/health` 报配置版本 | 不成立 | 输出无 `configVersion` | 跨实例一致性不可观测（D-F-12） |

### 3.10 历史 / 记忆 / 上下文

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| DB seq 不重不跳 | 成立(条件：共享 DB) | 唯一约束 + 32 次重试；`AiChatHistoryServiceTest`（真 H2） | PG 未真跑 |
| 游标分页 | 成立(条件) | `history-pagination` PASS（跨实例） | — |
| 记忆整窗写 Cluster 安全 | 成立(条件) | staging+RENAME | **别名漏洞**（S-02） |
| 记忆窗口配对保护（产品写口） | 成立 | `trimKeepingToolPairs`；`MemoryWindow` | **advisor 写口不保配对**（D-F-02） |
| 重试不污染记忆 | **不成立** | advisor `before` 无条件 add | S-11 |
| `/history` Redis 故障语义 | 不成立 | `AiController:1256` 未包 try | 500 而非 503（A-F5） |
| `hasMore` 语义 | 不成立 | `AiController:1268` | 语义反了（D-F-07，当前无现网影响） |
| 摘要不污染用户可见历史 | 不成立 | `RollingSummarizer:222-237` → `/history` 透传 | 用户气泡出现"我没说过的话"（D-F-05） |
| 上下文分母 | 不成立 | `ContextLimitResolver:72-98` | 缺 `max_context` 静默 128000（D-F-03）；续跑丢 providerId（D-F-04） |

### 3.11 工具执行

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| kind 闸→重放→目标→资源锁→认领→执行→台账 | 成立(条件) | `SpToolCallingManager:1372-1533` | — |
| 未执行三路径不写台账 | 成立 | 同上 | — |
| 超时语义 | 部分成立 | `cancel(true)` 仅请求中断；饱和退化同步无超时 | "未执行"措辞与已起作业并存（E-10） |
| 轮内预算 | 部分成立 | 步数跨进程；armed 进程内且 park 时 clear | `MAX_ARMED_BATCHES` 跨实例失效（C-F7） |
| AI 资源锁 | 部分成立 | 60s 不续租；设施不可用放行 | 假锁误导文案（E-11）；失去互斥（F-05） |
| 前端工具两道闸 | 成立(条件) | 下发前/回灌前 inspect | — |
| **已公布卡被 skip** | 不成立 | `SpToolCallingManager:495-557` | 点击得"正在同步"，永不收敛（C-F8 ≡ H-H7） |

### 3.12 业务作业（AI 触发）

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 同类互斥/终态守卫/删除互斥 | 成立(条件：DB 行锁) | `JobService:156-166` 全在 task 行锁内 | — |
| 终态先到者胜 | 成立 | 条件 UPDATE；`JobTerminalStateCasTest` | — |
| 预检指纹失效拦截 | 成立 | `PrecheckFingerprintGuardTest` | — |
| **可执行性校验（非空/步骤/文件）** | **不成立** | `JobService:156-166` 无此类校验 | S-09 |
| 终态兜底（IMPORT/PRECHECK） | **不成立** | `ImportJobRunner:183-197`、`PrecheckJobRunner:65` | S-12 |
| 条目收敛（FAILED） | 成立(补丁后) | `JobProgressService:146-190` | 补丁零验证；实测残留（J-F-04） |
| 取消时暂存完整性 | 不成立 | `ImportJobRunner:150-164` | 半批数据无标记（E-14） |
| 任务态联动 | 条件风险 | `JobTerminalWriter:103-146` 吞异常 | 可二次发布（E-04） |
| 运行期改条件 | 条件风险 | `TaskItem` 无 `@Version` | 静默回退（S-13） |

### 3.13 文件

| 能力 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| 单编码下载字节一致 | 成立(补丁后) | `FileController:276-310`（复核）；`FileDownloadByteIdentityTest` | **ae883fb 上一律 buildZip**（J-F-03 实测字节不符）；补丁零验证 |
| 多实例共享盘 | 成立(条件) | `FileStorageMultiInstanceGuard` | 同 runner 本地目录，非跨机 |
| 旧绝对路径迁移 | 条件风险 | `key-migration.enabled: false` | 换挂载点即读不到（E-12） |
| ZIP 上传规模 | 不成立 | `FileController:62-96,167-186` 全量入内存 | OOM 面（E-07） |

---

## 4. 前端保障矩阵

> 任务前提：多页签并行 + 多用户隔离 + 业务态同步。
> 结论先行：**"多页签不互相破坏"仅在安全上下文（HTTPS/localhost）+ Web Locks 可用时成立；"多用户隔离"产品不存在；"业务态跨页签同步"根本不存在（设计上就没有）。**

| # | 场景 | 机制 | 状态 | 证据 | 缺陷 |
|---|---|---|---|---|---|
| F1 | 多页签并行（各自新会话） | `sessionStorage` 页签级 + Web Locks | 成立(条件：安全上下文) | `tab-ownership-lifecycle`(10，桩)；`13-nfezui` N06–N09 真机（@5b468f3，HEAD 未跑） | 3+ 页签矩阵无用例（H-13） |
| F2 | 同 sessionId 并发对话 | 服务端 `SessionGate` 409 | 成立(条件) | `SessionBusyFrameIsolationConformanceTest` | 前端只提示"没发出去" |
| F3 | 复制页签（安全上下文） | 判 duplicate → 换新 id + 清镜像 | 成立(条件) | 同上（真机旧证据） | 接管路径继承表单草稿（H-9） |
| F4 | **复制页签（非安全上下文 http+内网 IP）** | 无 `navigator.locks` → `unsupported` → 按"恢复" | **不成立** | `stores/ai.ts:4587-4590`（复核）；`vite host 0.0.0.0` | 共用同一会话：消息不同步、互抢 409、"新建对话"互毁（H-1） |
| F5 | 慢重载（刷新慢于探活预算） | 有返回意向 → 只降级不清屏 | 部分成立 | `ai.ts:4607-4609` | 降级后**不再重认领**，可能被空白副本接走（H-8） |
| F6 | 多用户隔离 | 无 | **不存在** | `api/http.ts:133-147` 无 Authorization；无登录态 | S-02/S-03 使"id 即主人"；文案"关闭页签即结束"与事实不符（H-2） |
| F7 | 一轮内两张前端卡 | `suspended` 单布尔 + 三个单例（form/preview/handoff） | **不成立** | `ai.ts:5090` 无条件 `suspended=false`；`:3328/3371/3479` | 最后一张提交后不再重连（A-F2）；先到的卡无任何交互入口（A-F2b） |
| F8 | 刷新恢复（RUNNING 轮） | 镜像 → `/history` → `lastSeq=0` 全量回放 | 成立(条件：镜像可写) | `r2-midstream-refresh`；`aie2e-refresh` | 先清空后回放、`/events` 失败无回滚（H-6）；镜像配额击穿后 park 轮正文整块丢失（H-3） |
| F9 | 刷新恢复（SUSPENDED 轮） | 挂起卡重建 + 增量续收 | 成立(条件) | `restore-*` | 多卡串行只覆盖恢复路径（A-F2b） |
| F10 | 停止 | 本地先封口 → POST cancel | 成立(条件) | `aie2e-stop`(11) | `start` 帧前停止会 cancel 上一轮 runId（H-5） |
| F11 | 会话被销毁（410） | 换新 id + 清屏 | 成立(行为) | `session-purged-recovery` | 静默清空整屏且通知不提"历史已没"（H-4） |
| F12 | 通知/错误呈现 | 单槽 notice | 部分成立 | `ai.ts:1222-1230` | 多条互相覆盖（H-10）；空态建议词绕过可用性闸（H-11） |
| F13 | **业务态跨页签同步** | 无：`workspace.ts`/`task.ts` 无 BroadcastChannel、无 storage 事件 | **不存在** | 复核：全仓 `BroadcastChannel` 仅用于 `ai-session-ownership` | 表格预览/撤销、上传交接、表单草稿、作业 watch 各页签各自为政；表格写入无服务端互斥 |
| F14 | 前端工具执行面（sheet/DOM/region/resultview） | — | **未覆盖** | 无域报告 | §0.1 的 14 类文件；A 的 C05/C06/C07 只有边界 |
| F15 | 前端提交门禁 | `changed-files.mjs` frontend 规则只开 frontend 面 | **不成立** | 复核 `changed-files.mjs:75-79` | 纯前端改动不跑任何 Playwright（J-F-12） |

**补充**：多页签的"隔离"粒度是 `(origin, 页签)`，不是"用户"。同一浏览器换人即看到前一人完整对话；浏览器 session restore 会连 `sessionStorage` 一起还原（H-2，未实测）。

---

## 5. 严重度/结论冲突的消解

> 九份报告对同一事实给出不同定级，必须裁决，否则派工会被"最重要的一条"误导。

| # | 冲突点 | 各域口径 | 裁决 | 依据 |
|---|---|---|---|---|
| X-1 | **无鉴权/无主体** | A Important(条件升 Critical)／B Minor(域外)／C Important／D Minor／E Important／F 备注／G Critical／H Important／J Critical | **拆成两层**：①"没有认证"= 已登记的 demo 限制（DC-10，文档明文）；②由它派生的**四个具体缺陷超出登记前提**：`/runs` 枚举（S-03）、key 外发（S-04）、记忆别名（S-02）、反馈 IDOR。②按 Critical（多用户/内网多人）/Important（单机）计 | DC-10 登记的是"没有绑定"，前提是"sessionId 不可枚举"；S-03 直接推翻该前提 |
| X-2 | **park 取消是否有终帧** | A（C09）称取消后 `done{cancelled:true}`；B（F-C）称 park 路径无终帧 | **B 对**。A 的描述只对"驱动线程在跑"成立；park 路径 `ResumeService:689-698` 直接 return | 本报告复核 |
| X-3 | **fencing 是否成立** | B §6.1-7 列为"可证明正确"；F-F-01 称首轮无租约 | **F 对，B 的表述需限缩**：只有"曾持租"的轮才有 fence；常规纯文本轮 `NEVER_HELD` ⇒ plain SET | 本报告复核取租点仅 3 处 + `RunStore:718-726` |
| X-4 | **决策恰一次 = 决策一定被执行？** | A/C/E 均把"决策恰一次"列为可证明正确；C-F1 另指出派发会丢 | 两者不矛盾但必须分开写：**幂等成立**（HSETNX），**派发存活不成立**（`woke` 即 skip） | 本报告复核 |
| X-5 | **前端是否应消费 409 的 `reattach`** | B-F-B：前端不用 ⇒ 用户无自救；G-F-16：契约把他人 runId 交出去 ⇒ 越权 | **两条都成立且互为反证**：UX 缺陷与安全缺陷同源。裁决方向需产品定：要么 409 不带 reattach，要么重挂必须校验归属 | `AiController:424-434`；`ai.ts:2589-2620` |
| X-6 | **多用户隔离的定级** | D-F-11 Minor／E-E06 Important／G Critical／H Important／J Critical | 按部署前提分档（同 X-1）。**不得写成"已隔离"**，也不得写成"仅是文案问题" | — |
| X-7 | **tool_start 前移的语义** | A §6.1 已裁决非缺陷；B-F-N 记为"语义取舍"；J-F-11 指出其**反向**（request 先于 tool_start）导致卡片复位被误计为"模型不服从" | 三者不是同一现象：快照已是"tool_start 先"（补丁）；J 的 G-01 证据来自 **ae883fb**（未打补丁，顺序相反）。**不得用 J-F-11 反证补丁无效** | J §5.3 明确 headSha=20c56bb |
| X-8 | **`/history` Redis 故障返回码** | A-F5 称 500（代码可证）；G U-2 称 `/confirm` 未验证 | 不冲突：A 讲 `/history`（未包 try ⇒ 500），G 讲 `/confirm`（走全局异常处理，未验证） | — |
| X-9 | **seq 回卷的修复状态** | J-F-06 实测"从 seq=1 回卷"；补丁 `SseChatEmitter` 加 `wakeFloor=max(...,lastSeq)` | **补丁已改·未验证**。B 的 `SseChatEmitterWakeFloorTest` 只证算法，不证真实 pub/sub 与 kill 时序 | 本报告复核 patch 片段 + J |
| X-10 | **镜像 300ms 节流丢帧** | A-F8 初稿称"正常刷新会丢"，后自行更正为"仅 flush 未执行时" | 采信更正后的口径（Minor，低概率残留），不得当主要缺陷计权 | A §F8 更正说明 |

---

## 6. 去重发现总表（canonical）

> 去重规则：同一根因只留一行，把各域原始编号全部列在"来源"列。共 **126 行**（原始条目 133 条，含跨域重复）。
> 严重度取各域裁决后的统一值（见 §5）。证据列见 §2.2 编码。

### 6.1 身份 / 隔离 / 授权（ID）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| ID-01 | 全链无认证/无主体/无归属校验（39 个 AI 端点） | Important→Critical | 已登记 demo 限制（架构事实） | `backend/pom.xml`（无 security）；`AiController:299,524,590,740,855,934,943,974,1072,1251,1284,1550,1917,1933,1984`；`AiSessionPurgeController:78` | E1-复核（pom/端点）+ E2 | A-F1、B-F-M、C-F9、D-F-11、G-F-1、H-H2、J-F-14 |
| ID-02 | `GET /api/ai/runs` 全局枚举 sessionId+runId+pending 参数 | Critical/Important | **已证实缺陷**（超出登记前提） | `AiController:934-941`；`ResumeService:293-309` | E1-复核 | G-F-2、J-F-19（部分） |
| ID-03 | 配置写+探活把库中 key 发到任意公网主机 | Critical（受信网内） | **已证实缺陷** | `AiConfigController:113-158`；`AiConfigWriter:309-310`；`AiConnectionProbe:151`；`AiEndpointGuard:64-113` | E1-复核 | G-F-4 |
| ID-04 | 记忆键别名：`sid="{x}"` 读/删 `sid="x"` 的真实键 | Critical/Important | **已证实缺陷** | `RedisChatMemoryRepository:187-204,281-291,304-340,350-354` | E1-复核 | G-F-5 |
| ID-05 | 他人可批准/取消/续跑/销毁他人轮次 | Critical/Important | 已证实缺陷（依赖 ID-01/02） | `AiController:524-588,855-883,1072-1098`；`ConfirmGate:1103-1216` | E2 | G-F-3、A-F1、C-F9 |
| ID-06 | 可向他人模型上下文注入任意文本（回灌 result / 拒绝 reason） | Important | 已证实缺陷 | `AiController:590-717`；`ConfirmGate:1138-1216,1444-1512` | E2 | G-F-6 |
| ID-07 | 反馈接口全局读 + 按 messageId 覆盖/删除他人 | Important | 已证实缺陷 | `AiController:1933-1978,1984-1999` | E2 | G-F-11 |
| ID-08 | `sessionId`/`runId` 无形态/长度/熵校验（Redis 键膨胀、DB 500、跨用户限流 DoS） | Important | 已证实缺陷 | `AiController:313`；`AiFeedback`（64）；`AiChatHistoryService:200` | E2 | G-F-7、F-F-12 |
| ID-09 | 表单附件 `sessionId` 由客户端声明 | Important | 条件风险 | `GenerativeFormAttachmentController:78-125`；`RedisFormAttachmentStore:70-95` | E2 | G-F-12 |
| ID-10 | 跨任务绑定只在上下文带 taskId 时生效（任务中心恒 null） | Important | 条件风险（测试把风险固化为期望） | `ToolTargetResolver:101-131`；`ToolTargetResolverTest:118-120`；`AiTools:170-220` | E2 | E-06、G-F-13 |
| ID-11 | CORS `localhost:*` + 凭据 + 默认监听 `0.0.0.0:8081` | Important | 条件风险 | `config/WebConfig:20,23,27`；`application.yml:1-3` | E2 | G-F-9 |
| ID-12 | 前端非安全上下文 sid 熵不足（≈41bit+时间戳） | Important | 条件风险 | `stores/ai.ts:201-211,637-649` | E2 | G-F-10、H 附注 |
| ID-13 | 未认证的昂贵/长连接操作（`/kb/reload`、`/startup-resume/scan`、`/events` 无死线） | Important | 条件风险 | `AiController:918-932,1911`；`AiKbController:43-49` | E2 | G-F-8 |
| ID-14 | 409 `SESSION_BUSY` 仍下发 `runId`+`reattach` | Important | 条件风险（契约层） | `AiController:424-434`；`ai.ts:2589-2620` | E2 | G-F-16、B-F-B |
| ID-15 | `/health`、`/providers` 未认证暴露 baseUrl/keyLast4/实例 id | Minor | 已证实缺陷 | `AiController:1578-1672`；`AiConfigController:75-82` | E2 | G-F-14 |
| ID-16 | 安全口径文档漂移（限流来源、DC-10 类注位置） | Minor | 已证实缺陷（文档） | `AiRateLimiter:33-34`；`docs/evidence/补档-CHATPANEL-复核报告.md:149` | E2 | G-F-15 |

### 6.2 park / 门 / 工具（PK）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| PK-01 | **决策 × park 退出：`woke=true` 无人消费 ⇒ 批准被吞、整轮挂死** | Important | **已证实缺陷（竞态）** | `ConfirmGate:558`（预登记 latch）、`601-623`（park 不消费）、`1596-1613`（`woke` 即 skip）、`1876-1897`；`SpToolCallingManager:713-715` | E1-复核 | C-F1 |
| PK-02 | 取消后仍有残留执行（`awaitConfirm`/`resolvePending` → `executeOnce` 无复查） | Important | 已证实缺陷 | `SpToolCallingManager:515,607,1320-1328,1816-1831`；`ResumeService:681-698,906-943` | E2 | C-F2 |
| PK-03 | 超时/取消写回裸写非 CAS ⇒ 丢输入 + 双结局帧 + guard 与条目分叉 | Important | 已证实缺陷 | `ConfirmGate:905-931,944-963`；`RunStore:1320-1328,1557-1560` | E2 | C-F3 |
| PK-04 | WAL 只覆盖 3 个可重建状态 ⇒ APPROVED/FRONTEND_RESULT 崩溃窗留永久死卡 | Important | 条件风险 | `ConfirmGate:1150,1186-1190,1296-1342`；`RunStore:1517-1527` | E2 | C-F4 |
| PK-05 | park 后即时前端工具到期是进程内一次性任务，实例死亡即永不收敛 | Important | 条件风险 | `ConfirmGate:617-619,978-1011,374-382`；`StartupResumeRunner:139-148` | E2 | C-F5 |
| PK-06 | park 后同批"已结算未发帧"条目永远没有 `tool_start/tool_result` | Minor | 已证实缺陷 | `SpToolCallingManager:528-548,490-494,1839-1842` | E2 | C-F6 |
| PK-07 | 预算 armed 只在本进程本段有效（park 即 clear） | Minor | 条件风险 | `SpToolCallingManager:380-383,900-909`；`ToolActivityBeacon:242-276`；`ResilientChatService:473-477` | E2 | C-F7 |
| PK-08 | 已公布卡被 step-limit/首错 skip ⇒ 前端卡在"正在同步" | Minor | 已证实缺陷 | `SpToolCallingManager:495-557`；`ai.ts:3120-3128,2959-2981` | E2 | C-F8、H-H7 |
| PK-09 | `RunRegistry.close` 可能掐掉同实例刚启动的续跑 | Minor | 条件风险 | `AiController:470-474`；`RunRegistry:189-194`；`ResumeService:967-1026` | E2 | C-F10 |
| PK-10 | 非驱动实例 `/confirm` 丢回执帧；emitter 条目从不回收 | Minor | 已证实缺陷 | `ConfirmGate:1186-1191`；`SseChatEmitter:1437-1443`；`RunRegistry:138-153` | E2 | B-F-I |
| PK-11 | `publishRequestsForParkedBatch` 缺 M-1 复判（**不可达**，记录已检查） | Info | 观察项 | `ConfirmGate:487-501` vs `864-882` | E2 | C-F11 |
| PK-12 | 前端一轮多张人机卡共用单例，先到的卡无交互入口 | Important | 已证实缺陷 | `stores/ai.ts:3328,3371,3479`；`AiPanel.vue:613,632-656` | E2 | A-F2b |
| PK-13 | 一轮多张前端卡：第一张回灌后 `suspended=false` ⇒ 最后一张不再重连 | Important | 条件风险 | `ai.ts:5090,2461-2463,3211`；`AiController:766-769` | E2 | A-F2 |
| PK-14 | park 前置 `tool_start` ⇒ 等待期后端工具显示 running | Info | **已裁决非缺陷**（语义取舍） | `SpToolCallingManager:483-494,726-730`；`parts.ts:539-557` | E1-复核（注释）+ E2 | A §6.1、B-F-N |
| PK-15 | `RunParkedException` 穿越真实 Spring AI 循环**未验证** | Important | 未验证 | 无真实循环证据（替身 `Flux.error`） | E5 | C-U1 |

### 6.3 运行 / 帧 / 租约 / 恢复（RT）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| RT-01 | Redis 抖动期业务帧被静默丢弃、心跳照常 ⇒ 正文缺段不可感知 | Important | 已证实缺陷（路径） | `SseChatEmitter:1421-1444,1454-1469`；`LeaseService:797-841`；`RunStore:695-699` | E2 | B-F-A |
| RT-02 | 首轮无租约 ⇒ 全程无 fence，plain SET 可覆盖接管方终态 | Important | 已证实缺陷（机制） | `AiController:412-476`；`RunStore:690-726`；`LeaseService:797-813` | E1-复核 | F-F-01 |
| RT-03 | kill 驱动实例：180s 内 409、"发送失败"、无周期扫描接管 | Important | 已证实缺陷 | `AiController:416-435`；`RunLiveness:42-62`；`StartupResumeRunner:69-108` | E2 | B-F-B |
| RT-04 | park 场景取消不产生终帧 | Important | 已证实缺陷 | `ResumeService:689-698`；`AiController:873-878,1249-1302` | E1-复核 | B-F-C |
| RT-05 | 僵尸 RUNNING 轮被后续扫描复活：重建旧历史 → 整窗覆盖记忆 → `/runs/current` 指回旧轮 | Important | 条件风险 | `ResumeService:326-349,710-729`；`RunStore:733-735`；`RedisChatMemoryRepository:304-340`；`ai.ts:2169-2202` | E2 | B-F-E2 |
| RT-06 | 死轮 ⇒ purge 永 `complete=false`、同 sid 永久 410、数据残留至 TTL | Important | 已证实缺陷（条件：存在死轮） | `AiSessionPurgeService:367-431,742-760`；`SessionPurgeRetryRunner:62`；`SessionPurgeGuard:106-123` | E2 | B-F-F2 |
| RT-07 | 归档窗口/字节预算缺口只有服务端计数，无帧内标记 ⇒ 前端静默少一段且补不回 | Important | 条件风险 | `RedisFrameStore:399-416,668-679`；`FrameStore:58-65`；`ai.ts:4930-4935` | E2 | B-F-D、D-F-10 |
| RT-08 | `wakeFloor` 在本地扇出时推进 ⇒ 晚到小号帧永久缺失 | Important | 条件风险 | `SseChatEmitter:1517-1520,1564-1573,1598-1601` | E2 | B-F-G |
| RT-09 | seq INCR 失败降级进程内计数 ⇒ 撞号、回放 `mergeBySeq` 丢帧 | Important/Minor | 条件风险 | `RedisFrameStore:183-197,217-236,485-499,741-743` | E2 | B-F-F ≡ F-F-07 |
| RT-10 | 丢租超 TTL 后 LOST 墓碑终局 ⇒ RUNNING 轮永久只读且无接管源 | Important | 条件风险 | `LeaseService:686-709,844-850`；`ConfirmGate:1614-1618`；`SseChatEmitter:1437-1443` | E2 | B-F-O |
| RT-11 | `RUNNING` 死轮被 `/events`、`/runs/current` 反复续期 24h | Minor | 已证实缺陷 | `RunStore:2339-2385`；`AiController:748,990` | E2 | B-F-H |
| RT-12 | 溢出摘除在 `emitLock` 内做 pub/sub 退订（锁内 Redis I/O） | Minor | 条件风险 | `SseChatEmitter:1511-1515,574-583`；`RedisFrameWakeBus:86-98` | E2 | B-F-K |
| RT-13 | 取消 Redis 写失败仍 200 `cancelled:true`；`wokeInProcess` 名不副实 | Minor | 已证实缺陷 | `AiController:855-882`；`CancellationRegistry:147-154,232-247` | E1-复核（端点） | A-F6 ≡ B-F-J |
| RT-14 | 接管后重挂从 seq=1 全量重放（实测） | Critical | 已证实缺陷（运行级）；**补丁已改·未验证** | `cluster3-probe.mjs:2809-2827`；`SseChatEmitter` patch | E3（`40174291244`、`40173316364`） | J-F-06 |
| RT-15 | 跨实例唤醒丢失无服务端兜底（只靠前端 45s） | Minor | 已证实缺陷 | `RedisFrameWakeBus:48-56`；`SseChatEmitter:1526-1538`；`sse.ts:36-40,525,645` | E2 | F-F-08 |
| RT-16 | 唤醒监听容器无 `@PreDestroy` | Minor | 已证实缺陷 | `RedisFrameWakeBus:105-135` | E2 | F-F-09 |
| RT-17 | 启动续跑配额按"尝试数"计 ⇒ 第 11 个起永不自动续跑 | Minor | 已证实缺陷 | `StartupResumeRunner:136-167` | E2 | F-F-06 |
| RT-18 | 优雅停机不收敛 AI 运行面：`shutdownNow` 无终态写；`/chat` 不查 draining | Important | 已证实缺陷（代码）+ 实测无 draining | `AiExecutorConfig:52`；`AiController:299-488`；`ResilientChatService:473-477` | E1-复核 + E3（J-F-05） | A-F4、J-F-05 |
| RT-19 | `cluster` profile 无 `server.shutdown=graceful` | Minor | 已证实缺陷（配置） | `application-cluster.yml:38-44` vs `application-h2tcp:42`/`postgresql:68` | E2 | F-F-11 |
| RT-20 | 三 JVM 并发冷启动 Flyway baseline 竞态（2/3 失败） | Critical | 已证实缺陷（运行级，间歇） | `application.yml:64-67`；`cluster3-probe.mjs:1728-1765` | E3（`40174291244`、`40171916232`） | J-F-07 |
| RT-21 | purge 与在飞写入 TOCTOU（guard 只查一次） | Important | 条件风险 | `AiSessionPurgeService:246-330,301,315`；`AiChatHistoryService:136-150,288-301`；`RedisChatMemoryRepository:304-340` | E2 | F-F-04 |
| RT-22 | 恢复侧不查 purge 守卫；purge 与续跑无互斥 | Minor | 条件风险 | `ResumeService:613-1041`；`AiSessionPurgeService:258-328` | E2 | B-F-P |
| RT-23 | 租约/资源锁设施不可用时"降级放行" ⇒ 破坏性动作失去跨实例互斥 | Important | 已证实缺陷（刻意降级） | `ToolResourceLocks:143-169`；`LeaseService:562-567`；`ResumeService:530-543` | E2 | F-F-05 |
| RT-24 | 单 master 故障 ⇒ `ping()` 逐 master 失败 ⇒ 全站 AI 503 | Important | 条件风险 | `RedisAvailability:86-104`；`AiController:318,751,1079` | E2（字节码由 F 提供） | F-F-02 |
| RT-25 | 会话门 Redis 异常静默降级为进程内门（跨实例互斥失效） | Important | 条件风险 | `SessionGate:192-237` | E2 | B/F §5.5、G §5 |
| RT-26 | `RunRegistry` 每次 `of()` 新建、重挂/决策路径不 close（条目泄漏） | Minor | 已证实缺陷 | `RunRegistry:138-153` | E2 | B-F-I（与 PK-10 同源分列） |

### 6.4 模型 / 记忆 / 历史 / 上下文（MD）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| MD-01 | 重试把 user 消息重复写进记忆并重复进请求；`fail()` 不收敛记忆 ⇒ 重复持久可见 | Important | 已证实缺陷（机制，字节码+代码） | `AiModelConfig:79-81`；`AiProviderRegistry:308/341/415`；`ResilientChatService:458-471,1378-1400` | E2 | D-F-01 |
| MD-02 | advisor 的 40 条裁剪不保 tool 配对 ⇒ 孤儿 `role:tool` 致整轮 400 | Important | 条件风险 | `AiModelConfig:68-75`；`ResilientChatService:1638-1647`；`MemoryWindow:219-243` | E2 | D-F-02 |
| MD-03 | `max_context` 缺失静默按 128000 ⇒ 小窗口模型压缩永不触发，`/health` 反而报 `compactionOn=true` | Important | 条件风险 | `ContextLimitResolver:72-125`；`AiProperties:1341`；`ContextConfig:75-90` | E2 | D-F-03 |
| MD-04 | 续跑轮上下文分母丢 providerId ⇒ `done.context` limit/percent 说谎 | Minor–Important | 已证实缺陷 | `ResilientChatService:2145` vs `:1961-1962`；`ResumeService:920-925` | E2 | D-F-04 |
| MD-05 | 压缩摘要以"用户消息"进入 `/history`（用户看到自己没说过的话），且与完整历史库不一致 | Important | 已证实缺陷 | `RollingSummarizer:222-237`；`ResilientChatService:1420-1422`；`AiController:1331-1334`；`ai.ts:905-914,1783-1802` | E2 | D-F-05 |
| MD-06 | 工具执行期（≤120s）无任何帧，前端 45s 判"断线"⇒ 长工具轮反复假断线 | Important | 条件风险 | `application.yml:209`；`ConfirmGate:699`；`ResilientChatService:714-721`；`sse.ts:31,73,39` | E2 | D-F-06 |
| MD-07 | `/history` 的 `hasMore` 语义反了 | Minor | 已证实缺陷 | `AiController:1268` | E2 | D-F-07 |
| MD-08 | 配置写路径无乐观并发（后写覆盖） | Minor | 条件风险 | `AiConfigWriter:222-230,505-545` | E2 | D-F-08 |
| MD-09 | 种子/Flyway 回填不推进 `config.version`/不 INCR ⇒ 其他实例看不到 | Minor | 条件风险（多实例） | `AiConfigSeeder:111-150`；`AiConfigWriter:686-720`；`V5` | E2 | D-F-09 |
| MD-10 | 请求级 `modelId`/`params` 与同名模型选择在产品中不可达；`/health` 不报配置版本 | Minor | 已证实缺陷 + 覆盖缺口 | `AiPanel.vue:936`；`ai.ts:2566`；`AiConfigService:611-636`；`AiController:1579-1672` | E2 | D-F-12 |
| MD-11 | 会话 TTL 文档漂移（README 6h vs 实现 24h，且只在写时滑动） | Minor | 已证实缺陷（文档） | `application.yml:146`；`AiProperties:36`；`README.md` | E2 | D-F-13 |
| MD-12 | 非流式/摘要路径无 HTTP 读超时，只靠线程中断 | Minor | 仅缺测试 | `AiProviderRegistry:390-410`；`ContextSummaryClient:140-173` | E5 | D-F-14 |
| MD-13 | `/history` Redis 故障返回 500（`/context` 同族 503） | Minor | 条件风险（代码可证未捕获） | `AiController:1256`；`RedisChatMemoryRepository:281-291`；`GlobalExceptionHandler:198-208` | E2 | A-F5 |
| MD-14 | 反馈并发首写撞唯一键 → 500 | Minor | 条件风险 | `AiController:1954-1976`；`V3__ai_feedback.sql` | E2 | A-F7 |
| MD-15 | 刷新恢复锚点用 `archiveMaxSeq`，镜像 flush 失效时有不可补 delta | Minor | 条件风险（已大幅缓解） | `ai.ts:2136,1475-1520,851,193` | E2 | A-F8（作者已自我更正） |

### 6.5 业务交界 / 作业 / 文件（BZ）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| BZ-01 | `start_*` 不校验可执行性（空选择/错步骤也"COMPLETED 零数据"） | Important | 已证实缺陷 | `JobService:156-166`；`AiTools:319-374`；`ExportJobRunner:112-121` 等四 runner | E2 | E-01 |
| BZ-02 | IMPORT/PRECHECK 缺提交期回滚/异常终态兜底 ⇒ 悬置 RUNNING 最长 30m | Important | 已证实缺陷 | `ImportJobRunner:60,183-197`；`PrecheckJobRunner:65`；`AsyncJobExecutor:27-45`；`JobWatchdog:269-271` | E2 | E-02 |
| BZ-03 | 作业期改条件被静默回退（长事务 + `TaskItem` 无 `@Version`） | Important | 条件风险 | `ExportJobRunner:112,230`；`TaskService:348-354`；`TaskItem:1-33` | E2 | E-03 |
| BZ-04 | 任务态联动失败被吞 ⇒ 作业终态与任务态长期不一致 → 可二次发布 | Important | 条件风险 | `JobTerminalWriter:103-146`；`Task.java:37` | E2 | E-04 |
| BZ-05 | 确认卡"影响面"数字失真（IMPORT 恒不可估、EXPORT 用上次导出行数） | Important | 已证实缺陷 | `ToolPreflightService:210-240`；`FileController:145-161`；`ExportJobRunner:222` | E2 | E-05 |
| BZ-06 | 作业 FAILED 后 `job_items` 残留 RUNNING（实测） | Critical | 已证实缺陷（运行级）；**补丁已改·未验证** | `cluster3-probe.mjs:2866-2946`；`JobProgressService` patch | E3（两次 run 同签名） | J-F-04 |
| BZ-07 | 单编码下载返回再包一层 zip（实测字节不符） | Critical | 已证实缺陷（运行级）；**补丁已改·未验证** | `FileController:276-310`（快照已修）；`ae883fb` 为 `buildZip` | E3 + E1-复核（快照已修） | J-F-03 |
| BZ-08 | 进度帧先于数据面提交（崩溃后显示"不存在的进度"） | Minor | 已证实缺陷（语义层） | `ExportJobRunner:184-186`；`ImportJobRunner:147-149`；`PublishJobRunner:214-216` | E2 | E-08 |
| BZ-09 | `list_tasks` 两遍全量载入（含 EAGER items） | Minor | 已证实缺陷（性能） | `AiTools:176`；`ListTasksResultViewProvider:74`；`Task.java:40-46` | E2 | E-09 |
| BZ-10 | 工具超时"未执行"措辞与"可能已起作业"并存（重试可能建第二个作业） | Minor | 条件风险 | `SpToolCallingManager:1473-1484,1617-1643,751` | E2 | E-10 |
| BZ-11 | AI 资源锁 60s、不续租、与业务守卫两套；假锁给出误导文案 | Minor | 已证实缺陷 | `ToolResourceLocks:127-171`；`SpToolCallingManager:1331-1332` | E2 | E-11 |
| BZ-12 | 旧绝对路径 `storage_path` 在换实例/挂载点后不可读（迁移默认关） | Minor | 条件风险 | `FileStorageService:109-133`；`application.yml:466-471` | E2 | E-12 |
| BZ-13 | IMPORT 取消后暂存区是"部分批次"且无标记 | Minor | 已证实缺陷 | `ImportJobRunner:150-164,97` | E2 | E-14 |
| BZ-14 | ZIP 上传无规模上限（整包入内存 + 全量解压） | Minor | 已证实缺陷 | `FileController:62-96,167-186` | E2 | E-07 |
| BZ-15 | KB HTTP 源无地址白名单且跟随重定向 | Minor | 条件风险（配置面） | `HttpKbSource:71-73,37-40`；对照 `AiEndpointGuard:64-113` | E2 | A-F10 |
| BZ-16 | KB local 源把本机磁盘当共享缓存源 ⇒ 多实例版本抖动 | Minor | 条件风险 | `CachedKbSource:126-137,44-49`；`KbService:312-315` | E2 | F-F-13 |

### 6.6 基础设施门禁（IN）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| IN-01 | **真实 Redis Cluster × 产品 JVM 零自动化**（3 实例网是单节点） | Important（证据缺口） | 未覆盖 | `cluster3-run.sh:357-366`（复核，无 `--cluster-enabled`）；`cluster3-probe.mjs:932-938`（h2tcp+单节点）；`ci.yml:241-245` | E1-复核 + E3 | F-F-03、J-F-08、A-5.2、E-13 |
| IN-02 | Cluster 安全静态门禁有盲点（`keyCommands().scan` 不匹配） | Minor | 已证实缺陷 | `forbid-redis-cluster-unsafe.mjs:41,71-76`；`RedisClusterScanSupport:86,118` | E2 | F-F-10 |
| IN-03 | 拓扑 fail-fast 是 profile 门控而非属性门控 | Minor | 已证实缺陷 | `RedisClusterTopologyGuard:29-31`；`application-cluster.yml:27-33` | E2 | F-F-16 |
| IN-04 | 限流第二维按 `RemoteAddr`；反代未配 trusted-proxies 时全站共用一个桶 | Minor | 已证实缺陷（配置依赖） | `AiRateLimiter:120-128`；`AiController:326`；`application.yml`（缺省空） | E2 | F-F-15 |
| IN-05 | purge 观测键 3 键 `DEL` 跨槽（非原子、失败只记 error） | Minor | 已证实缺陷 | `AiSessionPurgeService:775-781` | E2 | F-F-14 |
| IN-06 | MDO "双 JVM" 不共享 DB/文件，且依赖 Redis 库号 | Important（结论范围） | 已证实（配置事实） | `playwright.mdo.config.ts:19-24`；`harness.ts:53-54,357,222` | E2 | J-F-09 |
| IN-07 | 真实 PostgreSQL 零 CI 覆盖 | Important（证据缺口） | 未覆盖 | `grep -rn postgres .github scripts` 零命中 | E2 | F-G4、J §1.2-2 |
| IN-08 | 三实例网同 runner 本地目录，"跨机共享盘"未验证 | Important（证据缺口） | 未验证 | `cluster3-run.sh`（`RUN.sharedFiles`） | E2 | J §5.1 |

### 6.7 前端（FE）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| FE-01 | 无 Web Locks（http+内网 IP）时复制页签共用同一会话 | Important | 已证实缺陷（条件：非安全上下文） | `ai.ts:420-427,4587-4590,4679-4691`；`vite.config.ts` host 0.0.0.0 | E2 | H-H1、G-P9 条件 |
| FE-02 | 多用户隔离不存在；"关闭页签即结束"文案与事实不符 | Important | 已证实（架构事实 + 文案） | `api/http.ts:133-147`；`zh-CN.json:407` | E2 | H-H2、D-F-11、J-F-14 |
| FE-03 | 镜像无体积上限、正文与投影双份、写失败静默 ⇒ 配额击穿后 park 轮正文丢失 | Important | 条件风险 | `ai.ts:818-855,1483-1524,1537-1550,2124-2154` | E2 | H-H3 |
| FE-04 | 410 静默清空整屏且通知不提"历史已没" | Minor | 已证实（行为） | `ai.ts:2593-2604,4521-4527` | E2 | H-H4 |
| FE-05 | `start` 帧前点停止会 cancel 上一轮 runId | Minor | 已证实缺陷 | `ai.ts:4343,4390-4398,4951-4954` | E2 | H-H5 |
| FE-06 | "静默续收"先清空再全量回放，归档取不到时无回滚 | Minor | 条件风险 | `ai.ts:2191-2201` | E2 | H-H6 |
| FE-07 | 慢重载降级 duplicate 后不再重认领；`pagehide` 兜底会在文档活着时放锁 | Minor | 条件风险 | `ai.ts:596-603,4634-4657,4607-4609` | E2 | H-H8 |
| FE-08 | 复制页签/接管继承未提交的表单草稿 | Minor | 已证实（行为） | `form-draft.ts`；`ai.ts:2340-2342` | E2 | H-H9 |
| FE-09 | 单槽通知互相覆盖 | Minor | 已证实（设计） | `ai.ts:1222-1230,1140-1174` | E2 | H-H10 |
| FE-10 | 空态建议词绕过可用性闸 | Minor | 已证实（行为） | `AiPanel.vue:1517-1522`；`ai.ts:1441-1443` | E2 | H-H11 |
| FE-11 | `onMounted` 的 `if(!messages.length)` 会跳过历史对账（未来多面板/HMR 脆弱点） | Minor | 仅缺测试 | `AiPanel.vue:3638` | E5 | H-H12 |
| FE-12 | `parts.ts` 帧序防御只有纯函数 4 例，无 store/浏览器级回归锁 | Important | 仅缺测试 | `frontend/tests/park-request-tool-start-order.spec.ts`（新） | E5 | H-H14、J-F-11 邻接 |
| FE-13 | 多页签/多用户真机矩阵缺失（3+ 页签、判定窗口内发送、同卡并发决策、非安全上下文） | Important | 仅缺测试 | `13-nfezui.spec.ts:155-235`（仅该域） | E5 | H-H13、J-F-15 |
| FE-14 | 业务态跨页签同步不存在 | Important | 已证实（设计缺口） | 全仓 `BroadcastChannel` 仅 `ai-session-ownership` | E1-复核 | K 新增（I 域缺失所致） |

### 6.8 CI / 测试 / 真实性（CI）

| ID | 结论 | 严重度 | 状态 | path:line | 证据 | 来源 |
|---|---|---|---|---|---|---|
| CI-01 | 提交门禁 E2E 在 HEAD 跑不完（130 条只执行 17 条，停在 H-01，25 分钟超时取消） | Critical（门禁可信度） | 已证实（运行级） | `ci.yml:225-237`；`verify-e2e.ps1:117,5481-5506` | E3（`40174289141`） | J-F-01 |
| CI-02 | 三实例网从未绿过（4/4 failure；9 PASS/7 FAIL；6 条同签名） | Critical | 已证实（运行级） | `cluster-3node.yml:43-109`；`cluster3-probe.mjs:1615-1632` | E3（`40174291244`、`40173316364`、`2ed8f95`、`20c56bb`） | J-F-02 |
| CI-03 | **补丁（含 6 个新测试）零 CI 验证**，而它覆盖 6 条红中的 5 条 | Critical（证据边界） | 已证实 | `working.patch`；`tree-status.txt` | E3（各 run 的 headSha 均为 ae883fb/965e6ef/20c56bb） | J-F-16 |
| CI-04 | `working.patch` 修的内容不在任何 run 里 ⇒ "已修复"只能是意图 | Critical | 同上（与 CI-03 同源分列，便于派工） | 同上 | E3 | J-F-16、K §10 |
| CI-05 | 真模型 G-01 把"卡片被 `tool_start` 复位成只读"计成"模型未触发" | Important | 条件风险（归因错误） | `11b-hgta.spec.ts:487-556,510-528` | E3（`40171918873`：N=10 触发=0） | J-F-11 |
| CI-06 | 纯前端改动不触发 e2e 面（UI 层无提交门禁） | Important | 已证实（配置） | `changed-files.mjs:75-79`（复核）；`ci.yml:162,186-189` | E1-复核 | J-F-12 |
| CI-07 | `suspend-cross-instance` 用例期望与产品 park 收流契约冲突（假红） | Important | 可证明测试错误 | `cluster3-probe.mjs:2157-2175` vs `AiController:770-779` | E2 | J-F-10 |
| CI-08 | 集群网多处宽松窗口可掩盖缺陷（代理分布 8–12/30；同连接每节点 ≥2 不断顺序） | Important | 条件风险（部分已发生） | `cluster3-probe.mjs:1811-1814,1850-1852` | E2 | J-F-13 |
| CI-09 | 39/130 条 P2 红不阻塞退出码 | Important | 已证实（门禁口径） | `verify-e2e.ps1:5723-5725`（复核） | E1-复核 | J §3.2 |
| CI-10 | 作业名"35 用例"与实际 160+（130 AI + TC/GF/CTX）不一致 | Minor | 已证实（文档口径） | `ci.yml:225,345`；`verify-e2e.ps1:5507` | E1-复核 | J-F-18 |
| CI-11 | `verify-e2e.ps1` 的"从产品键派生超时"实为硬编码 300 | Minor | 已证实 | `verify-e2e.ps1:112-118` | E2 | J-F-17 |
| CI-12 | 永久/条件 skip 未登记为门禁缺口 | Minor | 仅缺测试 | `17-unlock:677`、`16-kqc:61`、`10-srp:164` | E5 | J-F-20、A-5.2-6 |
| CI-13 | `/runs`、`/pending/{runId}` 无专属测试 | Minor | 仅缺测试 | `AiController:934,1917` | E5 | J-F-19 |
| CI-14 | `assumeTrue(connect())` 让真 Redis 证据可静默消失 | Minor | 仅缺测试 | `RedisChatMemoryRepositorySaveAllEmptyTest` | E5 | B §3.2-5、G §6 |
| CI-15 | 单测底座 `InMemoryRunStore` 默认共享对象引用 ⇒ "旧副本整条覆盖"类缺陷结构性不可见 | Important（测试底座） | 已证实 | `InMemoryRunStore:129-143` | E2 | C §3.1、PK-03 根因 |
| CI-16 | park=true 生产 vs 单测缺省 false（仅 7 文件开启） | Important | 已证实（配置事实） | `application.yml:258-259`；`AiProperties:855` | E1-复核 | A-F3 |

---

## 7. 测试与真实性缺口 + 精确的 CI 证据边界

### 7.1 各层实际证明了什么（统一口径）

| 层 | 规模 | 真跑的部分 | 替身部分 | **能证明** | **不能证明** |
|---|---|---|---|---|---|
| 后端单测 `mvn test` | 2,607 例（AI 域 2,063） | 真 Java 对象/状态机；部分真 H2；部分真线程池 | **Redis 全部替身**：`FakeLeaseRedis`/`FakeFrameRedis`/`FakeMemoryRedis`/`FakeRateRedis`/`PurgeFakeRedis`/`InMemoryRunStore`/`InMemoryFrameStore` | 分支/帧序/键名/脚本入参/降级路径 | 真 Redis 语义（Lua 原子性/跨槽/pub-sub/Stream 截断）、真实网络与容器 |
| 后端 `@SpringBootTest` | 16 个（含补丁新增 4 个） | 真 H2 + 真 Bean + 真迁移 | 无 Redis / 无 PG / 单进程 | 装配级集成（单进程） | 多 JVM、跨进程 |
| 前端 vitest | 139 文件 / 1,511 例 | 真 store/reducer（jsdom） | **`@/api/ai` 全量 `vi.mock`**；`FakeLockManager`/`FakeBroadcastChannel`；jsdom 无 Web Locks | UI 状态机、段/投影、恢复账本 | 真实浏览器 API、真实网络、真实多页签 |
| `verify-e2e.ps1`（PR 门禁） | 165 例（35 + 130） | 真 JVM + 真 Redis(**单节点**) + 桩上游 + 真 HTTP/SSE | 上游是 `stub-upstream.mjs` | 帧序/门/表单/作业/取消/历史/字节（接口级） | 多实例、Cluster、真模型、UI 渲染、非安全上下文 |
| cluster-3node | 16 例 | 3 JVM + H2 TCP + HAProxy **真无粘滞** + 真 kill/pause/SIGTERM + 单节点 Redis | 桩上游 | 见 §8.2 | Redis Cluster、PostgreSQL、真模型、跨机共享盘、补丁有效性 |
| Playwright（9 套 config） | 278 `test()` | 真 Chromium + 真后端（部分域双 JVM） | 桩上游为主 | DOM/多页签/刷新（该域内） | **不在任何提交门禁**；HEAD 未跑 |
| 真模型 `@real` | 14 条（该次跑 9 条） | 真模型 + 真 UI | — | 采样服从率 | 稳定性（单次、无复跑、N=10） |

### 7.2 结构性"假绿"点（按影响排序）

1. **park=true 生产 vs 单测缺省 false**（CI-16）：2000+ 单测多数走阻塞老路径，"全绿"不能推出 park 控制流正确。
2. **`InMemoryRunStore` 默认共享对象引用**（CI-15）：`putPending` 存同一引用 ⇒ "读旧副本整条覆盖"这类缺陷**结构性不可见**（正是 PK-03 的根因）。
3. **Lua 原文从未执行**（IN-01 同源）：改脚本不改替身 ⇒ 全绿地放过。
4. **服务级用例 mock 掉 advisor**（MD-01/MD-02）：框架在 `before/after` 的写入与裁剪完全不在测试视野。
5. **测试把缺陷窗口固化为期望**：
   - `ConfirmGateDeadlockResumeTest:330-346` 把"arm 后 await 前决策 ⇒ `woke=true` 且不续跑"钉成契约 —— 正是 PK-01 的缺陷形态；
   - `ToolTargetResolverTest:118-120` 把"上下文无 taskId ⇒ 放行任意 taskId"钉成期望（ID-10）；
   - `r166b-fixes.spec.ts:566-575` 把"无 Web Locks ⇒ 按恢复处理"钉成期望（FE-01）；
   - `session-purged-recovery.spec.ts:87-113` 把"410 清空"当期望（FE-04）；
   - `AiControllerHistoryPayloadTest:210-221` 与实现同源背书 `hasMore` 反语义（MD-07）。
6. **只断言 mock 调用**：`MultiCardParkResumeTest`（`verify(resolvePending, times(2))`）证明"派发了几次"，不证明"副作用发生了几次"。
7. **测试自造生产不可达状态**：`AiToolsInvocationTest.upload():341-352` 手写 `row_count`，而生产上传从不写（BZ-05 的假绿）。
8. **墙钟断言**：`ReadinessDrainWindowTest`（700ms 窗口 + ≥80%）、`JobWatchdogItemConvergenceTest`（`sleep(1300ms)` + `dead-multiplier=1` 把 90s 压成 1s）、`SseChatEmitterDrainHandoffTest`（含 `<1000ms`）——CI 负载下可假红/假绿。
9. **P2 不阻塞**（CI-09）：39 条即使真坏，门禁仍可能绿。
10. **`assumeTrue(connect())`**（CI-14）：真 Redis 证据可静默消失。

### 7.3 精确的 CI 证据边界（只列可引用的三个 run）

#### A. `40174291244` — cluster-3node @ `ae883fb`（workflow_dispatch）
- **真跑**：3 JVM（`--spring.profiles.active=h2tcp`）、1 个 H2 TCP 进程、**1 个单节点 Redis 7.2.16**、HAProxy 2.9（host 网、`roundrobin`、无 stick-table）、桩上游。
- **真做过**：SIGKILL/SIGSTOP/SIGCONT/SIGTERM、`docker pause` Redis、真 multipart 上传下载、真 HAProxy stats、21 轮历史分页。
- **结论**：`failure`，自报 PASS 9 / FAIL 7。
- **可引用的红（6 条同签名）**：`cold-start-3`（Flyway）、`shared-file-download`（字节）、`suspend-kill-a`（tool_start 缺）、`streaming-kill-a`（seq 回卷）、`job-kill-a`（item 残留）、`readiness-removal`（无 draining）。
- **不可引用**：`suspend-cross-instance`（harness 期望错误，CI-07）。
- **不能证明**：Redis Cluster、PostgreSQL、真模型、补丁有效性（树是 ae883fb）、多用户、主从切换（只 pause）、跨机文件共享。

#### B. `40174289141` — ci.yml @ `ae883fb`（push）
- backend `success`（`mvn test` 全量，4 分钟）；frontend `success`（typecheck+vitest+build）；**e2e `cancelled`**（起跑 07:56:11，07:59:23 后 20 分钟无输出，08:19:30 取消）；summary `failure`（如实记红）。
- **只跑到 `M-09`（AIE2E 累计 17 条）**，下一条 `H-01` 停住 ⇒ H/G/T/K/Q/C/D/X/W 全域在 HEAD **零执行证据**。
- **边界**：这次 run 不能作为"AI 主链路绿"的证据；只能证明"TC1–TC23、GF1–GF6、CTX-1..4、AIE2E-S/P/M 共 17 条在单实例桩环境通过"。

#### C. `40171918873` — e2e-real @ `20c56bb`（dispatch，suite=real）
- 真模型 + 真后端 + 真 Chromium + 单节点 Redis；`8 passed / 1 failed`（20.7 分钟）。
- 覆盖 9 条（RM01/RM02/S15/G01/X07/K10/C13/Q02r/V14r）；**stub 作业 skipped**。
- **可引用**：G-01 `N=10 触发=0`（但归因错误，见 CI-05）。
- **不能证明**：UI 桩全量、多页签域、多用户、集群、补丁有效性（20c56bb 更早）、稳定性（单次无复跑）。

#### 对照数据点（供趋势判断，不可当 HEAD 证据）
- `40173314741`@965e6ef：e2e 5 分钟跑完 130 条，**33 条红**（29 AIE2E + GF4/5/6 + TC23；W 域 21 条报"done 帧 0 个"）。
- `40131155913`@d66ab7c（nightly）：165 条 **13 分钟**跑完，PASS 165 / FAIL 1（`AIE2E-W-21` 同样"done 0 个"）。
- ⇒ 健康时 13 分钟、25 分钟预算**本来够**；真问题是"单用例失控无上限"，且"done 缺失"是一族反复出现的系统性缺陷。

### 7.4 测试覆盖缺口清单（代码可达、用例缺失）

后端：park 取消终帧｜决策×park 退出｜取消×已决未执行｜超时×回灌竞争｜guard 已写/条目未写｜到期任务重启重建｜同批已结算未发帧｜跨 park 预算｜已公布卡被 skip 的点击后果｜`/history` Redis 500｜`/cancel` 写失败语义｜`/feedback` 并发唯一键｜镜像落后丢帧｜`/chat` 在 draining 的行为｜executor 关闭对在飞轮｜首轮+僵尸接管交错｜`sid="{x}"` 别名红用例｜`/runs` 不返他人会话｜改 baseUrl 不外发旧 key｜CORS 白名单｜`cluster` profile 停机契约｜`sessionId` 长度｜KB local 双源｜多键 DEL 跨槽｜启动配额多实例｜单 master 故障可用性。
前端：3+ 页签并行｜判定窗口内发送｜两页签同卡并发决策｜非安全上下文全路径｜镜像配额｜`/events` 重连失败回滚｜判定完成前可发窗口｜`syncGateFromAuthority` 条目消失｜`takeOverSession` 二次判据拒绝｜`stop` 早于 `start`｜`parts.ts` 防御的 store 级锁｜真模型"帧到达率 vs 卡可提交"拆分指标。

---

## 8. 明确回答：GitHub Actions 能否测"多实例 + 无粘滞"？

### 8.1 能，而且已经实现了 —— 但只测了"应用层多实例"，没测"存储层集群"

**机制上可以**，不需要任何特殊条件，理由与证据：

1. **单 runner 内起 3 个 JVM**（不同 `--server.port`），共享一个 H2 TCP 库与一个共享目录 —— `cluster3-run.sh` 已实现。
2. **真无粘滞代理**：HAProxy `balance roundrobin`、**无 `stick-table`**、`option httpchk GET /health/ready`，三台 `server node-a/b/c`（本报告复核 `cluster3-run.sh:113-120`，脚本另有静态自证 `sc_not_contains "stick-table"`）。
3. 因此"同一对话的请求落不同实例"**是可复现的**，并且已被断言过（`idempotency-cross-instance`、`history-pagination`、`reattach-any-instance` PASS）。
4. 触发方式是 `workflow_dispatch`（**仅手工**），不是 PR 门禁。

### 8.2 现有 workflow **证明**了什么（仅当它绿时）

| 已 PASS 的能力 | 用例 |
|---|---|
| 跨实例重挂差量（B 全量/B 差量逐 seq 相等） | `reattach-any-instance`、`seq-cross-instance` |
| 跨实例决策幂等（重复 409 + 并发双决策恰一胜 + Redis 幂等索引存在） | `idempotency-cross-instance` |
| 跨实例读历史与双向翻页（42 条三元组同源） | `history-pagination` |
| 跨实例配置热更新（版本递增、三元组一致、pid 不变、`ai:cfg:ver` 递增） | `config-hot-reload` |
| 跨实例会话销毁（无残留 + 在途 deferred + 重试清 pending + 旧 sid 410 + 业务 task 保留） | `session-purge-cross-instance` |
| Redis 整体停掉后降级（ready 仍 200、`checks.redis=DOWN`、AI 端点 503、业务面 200、恢复后可用） | `redis-outage-recovery` |
| 无 key 不摘流（能力面与就绪面分层） | `readiness-removal` 第①段 |
| 代理确实在三节点间分发 | `proxy-distribution`（**但只验范围 8–12/30，不验顺序**） |

**条件必须随结论一起写**：3 JVM + H2 TCP（非 PG）+ **单节点 Redis（非 Cluster）** + 桩上游 + 同一 runner 本地目录 + **未打补丁的 ae883fb**。

### 8.3 现有 workflow **不证明**什么

1. **Redis Cluster**：没有 `--cluster-enabled`、没有 3 主 3 从、没有 `spring.data.redis.cluster.nodes`；MOVED/ASK、slot 迁移、单 master 故障、CROSSSLOT、多 master SCAN 全部未在产品链路上执行。而 E2E harness 自身依赖 `spring.data.redis.database` + `FLUSHDB`（Cluster 下非法）。
2. **PostgreSQL**：零 CI 覆盖（`grep -rn postgres .github scripts` 零命中）。
3. **真模型**：桩上游决定"答案"，不证明模型会产出该输出。
4. **补丁有效性**：最近一次集群网跑的是 `ae883fb`（未打补丁）。
5. **无粘滞的"顺序"**：允许 8–12/节点、同连接每节点 ≥2 ⇒ `leastconn` 或"部分粘滞"也能过；题目要求的 A→B→C 序列**未被断言**。
6. **跨机共享盘**：同一 runner 的本地目录，不是 NFS/PVC 语义。
7. **主从切换**：只 `docker pause` 整实例，不 kill 单 master。
8. **多用户**：产品无用户维度，不可能被该网证明。
9. **当前是否成立**：4/4 全红，6 条同签名 —— 所以"多实例已收敛"**现在不成立**。

### 8.4 一句话

**GitHub Actions 完全有能力测"多实例 + 无粘滞"，现有 `cluster-3node.yml` 就是这么做的，且设计可信（无 SKIP、逐用例证据落盘、真 kill/pause）；但它从未绿过，并且它测的是"应用层多实例 + 单节点 Redis"，不是任务前提里的"Redis Cluster + 共享 PostgreSQL"。** 要满足任务前提，缺的是一条"3 主 3 从 Redis Cluster + 3 JVM + 共享 PG"的 workflow（J 的建议 3）。

---

## 9. A 清单 × 各域 的覆盖对账

### 9.1 对账方法与结论

A 声明"196 个 AI 生产文件 / 50,180 行全部**文件级清点**"，并明确自己是"文件级完整 + 关键链路段级分析"，L3 清单为"仅清点、未审内部"。
本报告按"文件名出现在 B–J 的正文（而非仅清单）"重算：

- **196 / 196** 个 AI 主文件在至少一份报告中出现过（因 A 全列）——这只是**登记完整**，不是审阅完整。
- **69 / 196** 个 AI 主文件**只出现在 A 的清单里**，B–J 从未讨论（详见 9.3）。
- **327 个 AI 测试文件中，144 个**只出现在 A 的清单里，没有任何域报告评估其断言强度。
- 前端：**14 类文件为 0 提及**（§0.1），整域（D6）缺报告。

### 9.2 前端覆盖缺口（逐文件，出现次数）

`ai-dom-tools.ts` 0｜`ai-regions.ts` 0｜`resultview.ts` 0｜`SpreadGrid.vue` 0｜`SheetPreviewCard.vue` 0｜`UploadHandoffCard.vue` 0｜`tool-summaries.ts` 0｜`blocks.ts` 0｜`ai-suggestions.ts` 0｜`context-usage.ts` 0｜`form-scope.ts` 0｜`ui-receipt.ts` 0｜`publish-mode.ts` 0｜`publish-links.ts` 0｜`AiFeedbackView.vue` 0｜`form-schema.ts` 0｜`sheet-tools.ts` 1（仅"未覆盖"）｜`FormRenderer.vue` 1（仅"未覆盖"）｜`workspace.ts` 2（均"只到契约面"）｜`AiPanel.vue` 部分（H 读 30%，A 读 composer/停止按钮）。

### 9.3 后端 69 个"只列未审"文件（按风险分级）

**中风险（含策略/注入/截断/预算语义，建议补审）**
`PlainTextToolResultConverter`、`PromptDelimiters`、`ToolResultClearing`、`TruncationContract`、`ToolSchemaNormalizer`、`ToolSchemaReconciler`、`YamlToolDefinitionSource`、`ToolDefinitionOverride`、`LocalDirKbSource`、`KbText`、`ResultViews`、`ToolResultViewProvider`、`UsageDetails`、`TurnBudgetPlan`、`SheetSwitches`、`SecurityEvents`、`ReplyLanguage`、`StreamViolationException`。

> 说明：这些文件被 A 归入"已清点"，C 域声明审了 `ToolResultLimiter`/`ToolResultEnvelope`/`PromptDelimiters` 的"接缝"，但**没有一份报告逐行审过**。它们正好是"提示词注入 + 结果截断 + 工具定义来源"的边界，属于本次审计的**薄弱环节**（A 的 F10/IN 系列只覆盖了 KB HTTP 源）。

**低风险（DTO/枚举/仓储/常量）**
`AiChatMessage`、`AiChatMessageRepository`、`AiConfigAudit`、`AiConfigAuditRepository`、`AiConfigException`、`AiConfigWriter` 以外的小类、`AiFeedbackRepository`、`AiMdcTaskDecorator`、`AiProviderModelRepository`、`AiProviderRepository`、`AiRetiredKeysWarner`、`AiRetryConfig`、`AiSettingRepository`、`AiUnavailableException`、`{AvailableModelView,FetchModelsView,ModelView,ProbeView}`、`ContextBreakdown`、`ContextBreakdownService`、`ContextOverflow`、`ConversationTurns`、`FormIssue`、`FormIssueCodes`、`FormOptionSource`、`FormOptionSourceRegistry`、`HardTrim`、`Kb*`（余下 14 个）、`OverriddenToolCallback`、`ResultViewColumn*`、`ResultViewKind`、`ResultViewStatus`、`ToolCategory`、`ToolChannel`、`ToolMeta`、`ToolRisk`、`ToolSnapshot`、`AiExecutorConfig`（A 读过补丁段）。

### 9.4 端到端链覆盖对账（A 的 C01–C18）

| 链 | 域覆盖 | 判定 |
|---|---|---|
| C01 首轮对话 | B（全）、D（全）、J（门禁边界） | **实质覆盖** |
| C02 后端工具轮 | C（全）、E（业务契约面） | 实质覆盖 |
| C03 HITL 确认门 | C（全）、B（接缝）、G（越权面） | 实质覆盖 |
| C04 前端工具回灌 | C（边界）、E（守卫） | 边界覆盖；**浏览器侧未审** |
| C05 生成式表单 | A（段级）、E（规则表契约） | **执行面未审**（`FormRenderer` 仅 1 次"未覆盖"） |
| C06 表格写预览/应用/撤销 | A（段级） | **未审**（`sheet-tools` 2918 行、`SpreadGrid` 0 次） |
| C07 上传交接 | A（段级） | **未审**（`UploadHandoffCard` 0 次） |
| C08 刷新/重连 | B（全）、H（全） | 实质覆盖 |
| C09 取消 | B、C、H、A | 实质覆盖（并发现新缺陷 S-05） |
| C10 断流/重试/看门狗 | B、D、H | 实质覆盖 |
| C11 会话销毁 | B、F、G | 实质覆盖 |
| C12 历史与上下文 | D（全）、F、G | 实质覆盖 |
| C13 模型配置/密钥/健康 | D（全）、G（越权） | 实质覆盖 |
| C14 知识库 | D（prompt/分页）、A（SSRF） | **部分**：`KbService` 增量索引/缓存穿透/并发播种未审（A 自认 L3） |
| C15 业务工具→作业 | E（全）、J（运行证据） | 实质覆盖 |
| C16 文件下载 | E、J、A | 实质覆盖 |
| C17 赞踩反馈 | A（并发）、G（IDOR） | 部分（`AiFeedbackView` 0 次、`ExcerptGuard` 语义未审） |
| C18 多实例与停机 | B、F、J、A | 实质覆盖（结论多为"不成立"） |

**结论**：18 条链中 **C05/C06/C07 的执行面、C14 的内部、C17 的前端** 未实质审查；其余 13 条有域级覆盖。

### 9.5 未被任何报告覆盖的"端到端链"（A 的链表之外）

1. **AI 设置页全链**（`AiSettingsView` 834 行）：只有 D 读"composer/model/param 相关段"，G 读端点；页面级状态机（保存/回滚/掩码/探活展示）未审。
2. **AI 反馈视图**（`AiFeedbackView` 82 行）：0 提及。
3. **上下文条**（`ContextPill/ContextPopover` + `context-usage.ts`）：A 只登记，D 只提"轮末 recordTurn"。
4. **markdown 渲染的流式前缀复用 + mermaid + DoS 上限**：A 自认"~700 行未读"，H 只审"XSS 白名单"结论。
5. **表格预览的三档 plan（计数/采样/全量）与跨页签失效语义**：仅 A 的段级描述。
6. **`spike/*`**：A 明确"不属产品面，仅登记"——一致，无异议。

---

## 10. 需指挥官对账的"后期补丁"清单（**不猜测已修**）

以下项在快照中**代码已改**，但**零 CI/零真实拓扑验证**。对账时要确认三件事：① 是否真的进了本批补丁；② 是否有能变红的用例；③ 是否重跑了 cluster-3node。

| 对账项 | 快照状态 | 对应缺陷 | 验证状态 |
|---|---|---|---|
| 单编码下载回原字节 | 已改（`FileController:276-310`，本报告复核） | BZ-07 / J-F-03 | 新测试 `FileDownloadByteIdentityTest`（真 H2+真存储）**未跑过** |
| FAILED 时条目收敛 | 已改（`JobProgressService:146-190`） | BZ-06 / J-F-04 | `JobWatchdogItemConvergenceTest` **未跑过** |
| 停机 draining | 已加 `ShutdownDrainGate` + drain-window | RT-18 / J-F-05 | `ReadinessDrainWindowTest` **不绑真实端口**；集群网未复跑 |
| park 帧序 `tool_start` 前移 | 已改（`SpToolCallingManager:490-500`） | J 的 G-01/W 域"done 缺失"同源 | `ParkToolStartOrderConformanceTest` **未跑过**；前端只有纯函数 4 例 |
| `wakeFloor=max(...,lastSeq)` | 已改（`SseChatEmitter`） | RT-14 / J-F-06 | `SseChatEmitterWakeFloorTest` 只证算法 |
| Flyway 冷启动竞态 | **未改**（patch 内无 flyway） | RT-20 / J-F-07 | 仍 2/3 失败，无修复无测试 |
| 首轮取租约/fence | **未改** | RT-02 | 仍 plain SET |
| 记忆键别名 | **未改** | ID-04 | 仍可复现（建议补红用例） |
| advisor 双写 / 40 条裁剪 | **未改** | MD-01/MD-02 | 仍无测试 |
| park 取消终帧 | **未改** | RT-04 | 仍无终帧 |
| 决策×park 竞态 | **未改** | PK-01 | latch 预登记仍在 park 分支前 |

**判定**：补丁覆盖了 6 条红中的 5 条（BZ-06/BZ-07/RT-14/RT-18 + G-01 相邻），但**没有一条新测试进入过任何 run**（CI-03/CI-04）。因此"已修复"当前只能表述为"代码意图已改、证据为零"。

---

## 11. 不得出现的表述（防止过度声称）

1. 不得写"AI 全链路已通过审计"——§9.4 有 3 条链的执行面未审，§0.1 有整域未审。
2. 不得写"多实例已验证/已收敛"——cluster-3node 4/4 红，6 条同签名。
3. 不得写"Redis Cluster 已支持/已验证"——产品从未跑在真 Cluster 上；Lua 原文一次都没执行过。
4. 不得写"共享 PostgreSQL 已支持"——零 CI 覆盖。
5. 不得写"多用户隔离已实现"——产品无用户维度；且 ID-02/03/04 超出已登记限制。
6. 不得写"补丁已修复"——零 CI 验证；只能写"补丁已改·未验证"。
7. 不得写"2000+ 单测全绿 ⇒ 生产正确"——park 覆盖错位（CI-16）+ 替身 Redis + mock advisor。
8. 不得写"E2E 全绿"——HEAD 的 E2E 是 cancelled（没跑完），不是 passed。
9. 不得写"真模型服从率达标"——一次 dispatch、9 条、无复跑，且 G-01 归因错误。
10. 不得写"前端业务动作用例已覆盖"——`sheet-tools`/`ai-dom-tools`/`resultview` 等零审阅。
11. 不得写"取消一定会停止"——RT-13（写失败仍 200）+ PK-02（取消后仍执行）。
12. 不得写"批准一定会执行"——PK-01（`woke` 吞掉派发）+ PK-04（永久死卡）。

---

## 12. 一页结论

- **可以认为成立（带条件）**：单实例 + Redis 正常 + 无并发外部输入下，帧协议/序号/差量/幂等/租约/fencing 的**逻辑设计**质量高；补丁修的 `wakeFloor` 与 park 帧序是真实缺陷的正确修法；3 实例网在"读历史/幂等/配置热更新/销毁/Redis 降级"五项上是可用的真证据。
- **不能认为成立**：以上全部只证明"单进程 + 语义等价替身"或"单节点 Redis + H2"；真实 Cluster、真实 PG、真实 ≥3 JVM 全链、真实模型、多用户，**一条都没有自动化证据**；而唯一的多实例网当前是红的。
- **最高优先级（按损害）**：S-01（批准被吞）、S-02/S-03/S-04（隔离与密钥，超出登记前提）、S-05（无终帧）、S-09（假成功）、S-06（无 fence）、S-07（取消后执行）、S-14/S-15（交付与门禁不可信）。
- **最大盲区**：前端业务联动整域（缺 I 报告）+ 真实 Redis Cluster/PG 零覆盖 + 144/327 测试文件未被任何域评估。
