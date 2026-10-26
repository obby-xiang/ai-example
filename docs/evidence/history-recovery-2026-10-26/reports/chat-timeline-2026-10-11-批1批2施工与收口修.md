# 历史补档：对话时序包 批1 / 批2：施工报告与收口修报告

- **归档来源（已脱敏）**：4 件仓外原件，全部来自 `<LOCAL_WORK>/chat-timeline-pkg/`，源件仅仓外、未移动、未改写；分件见下表
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-11 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话**：正文未出现会话 id，来源会话见索引 `README.md` 的来源映射表（模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：458 行 / 43,852 B

| 分件 | 原件（仓外别名） | 源行 | 源字节 | 主题 |
|---:|---|---:|---:|---|
| 1 | `<LOCAL_WORK>/chat-timeline-pkg/施工报告-批1.md` | 141 | 12,365 | 批 1 施工报告（子包①双写过渡 + R-130/R-132 + R-133/R-131 Lua） |
| 2 | `<LOCAL_WORK>/chat-timeline-pkg/施工报告-批2.md` | 116 | 15,428 | 批 2 施工报告（子包②，高风险单独一棒） |
| 3 | `<LOCAL_WORK>/chat-timeline-pkg/收口修报告-批1.md` | 115 | 6,111 | 批 1 收口修报告（m-R1） |
| 4 | `<LOCAL_WORK>/chat-timeline-pkg/收口修报告-批2.md` | 86 | 9,948 | 批 2 收口修报告（红队 MAJOR-1 处置） |

---

**〔分件 1／4〕** 原件：`<LOCAL_WORK>/chat-timeline-pkg/施工报告-批1.md`

# 施工报告：对话时序包 批 1（子包①双写过渡 + R-130/R-132 + R-133/R-131 Lua）

| 项 | 值 |
|---|---|
| 执行者 | **Kimi K2.8 Preview**（主力编码；批量改写/复跑下沉 DS V4 Flash 子智能体 ×2，关键判断与整合由本执行者完成） |
| 授权来源 | **K3 决策卡 2026-10-11 代签（DC-19）**，裁决 1/2/4；只执行不重新裁决 |
| 主仓库 | `<REPO_ROOT>`（基线 HEAD=**7879f53**，未 commit/push） |
| 施工日期 | 2026-10-11 |
| 权威参照 | `<LOCAL_WORK>\chat-timeline-pkg\施工卡-刷新版.md`（§4.1/§6.4/§10.3）、`<LOCAL_WORK>\n1-design\方案.md`（R-130/R-132 候选 A、R-133 候选 1） |

---

## 1. 改动文件全清单

| 文件 | 改/新 | 行数变化 | 内容 |
|---|---|---|---|
| `frontend/src/types/ai.ts` | 改 | +100 | MessagePart 判别联合（TextPart/ReasoningPart/ToolPart/ToolPartPatch）、`ChatMessage.parts`、`ToolRunStatus` 增 `unknown`/`cancelled`、`AiSessionMirror` 增 `v`+`parts`、导出 `MIRROR_VERSION=2` |
| `frontend/src/stores/ai.ts` | 改 | +302 | 双写写入适配（18 处 case/动作）、`upsertToolPart`（:1817）、`migrateMirror`（:158）、`writeMirror` v2（:203）、R-130/R-132 对账（:635-649）、消息工厂 parts 初始化（:1352/:1369）、loadHistory 历史投影（:587-610） |
| `frontend/src/components/AiPanel/AiPanel.vue` | 改 | +13 | 仅 `toolTagType`（:527/:529）与 `toolStatusLabel`（:549/:551）各补 `cancelled`/`unknown` 两个枚举条目（ToolRunStatus 扩态的编译期强制消费点），模板零改动 |
| `backend/.../ai/memory/RedisChatMemoryRepository.java` | 改 | +18（157→175） | `DELETE_WITH_INDEX` 静态 Lua 脚本（:65-74）、`deleteByConversationId` 单次 execute（:164-165）、JSDoc 语义节 + Cluster 约束条 |
| `frontend/tests/parts-double-write.spec.ts` | 新 | — | 5 例（见 §4） |
| `frontend/tests/restore-reconcile.spec.ts` | 新 | — | 3 例（见 §4） |
| `backend/src/test/.../ai/memory/RedisChatMemoryRepositoryDeleteTest.java` | 新 | 79 行 | 2 例（见 §4） |

`git status --porcelain` 全量核对：仅上表 4 改 + 3 新（前端 2 spec + 后端 1 测试），**无越界文件**；`types/sse.ts` 零改动；`AiTools.java`/`SseChatEmitter.java`/`ResilientChatService.java`/`AiController.java` 零改动。

---

## 2. 三面实现要点与落点

### 2.1 双写（子包① B 方案）

- **类型层**：`MessagePart = TextPart | ReasoningPart | ToolPart`；`seg` 为消息内单调段号；`ToolPart.kind2` 承载 ToolKind（`kind` 是判别键）。旧字段 `content`/`reasoning`/`toolRuns`/`pendingCall` 全部保留，仍是**渲染真值**。
- **唯一写点** `upsertToolPart(message, patch, createIfMissing = true)`（`stores/ai.ts:1817`，`upsertToolRun` 正下方）：已存在 → splice spread 就地合并（语义对齐 :1559）；不存在 → 先 `sealTrailingStreamPart` 封口当前流式段，再 push 新段（seg 递增）。
- **帧落点双写**（旧投影写入旁同步落段，状态字面量两侧共用同一 const）：delta :1448（追加末尾未封口 text 段）、reasoning :1458、tool_start :1474、tool_result :1507、confirm_request :1530、confirm_decision :1554/:1570、frontend_tool_request :1591、frontend_tool_result :1630、suspended :1680（只更新已存在段，createIfMissing=false）。
- **卡外两处补写**（施工卡清单之外，属"凡写 toolRuns 必双写"总则）：`openGenerativeForm` 失败收尾 :1148、`runFrontendTool` 本地结局 :1861。
- **历史/镜像投影**（确定性现状投影，§3.7 口径）：`loadHistory` 历史分支 :587-610 = text 段 + 按 toolCalls 顺序 tool 段（result 由 toolResponses 按 id 回填，requiresAction=false），**落在会话身份守卫 :458 之后**。
- **镜像迁移**（§6.4）：`migrateMirror()` :158——`v>=2 且每条消息 parts 是数组` 直接用；否则 parts=[text(content)]+toolRuns.map(toToolPart)，pendingCall 按 toolCallId 并入（status/kind/requiresAction=status==='pending'），置 v=2 后**立即回写**（:526-542，早于 startMirror 300ms 防抖，复刻 :469-471"先行置位再触发重建"顺序）；幂等。
- **vivo 佐证**：真机 CDP 一轮触发只读工具，最后一条助手消息 `partsSeq=[text:0, tool:1, text:2]`、seg 单调、`content === text 段 join`、`toolRuns ≡ toolParts` 全对账通过（`evidence-b1/b1-parts-audit.json` + `b1-2-after-round.png`）。

### 2.2 对账（R-130/R-132，方案候选 A，净增 2 行族）

`stores/ai.ts:635-649`（`restoreSuspendedSnapshot`）：
- 入口同步捕获 `const sid = this.sessionId`（:636）与 `const abortAtEntry = currentAbort`（:637）；
- 取数显式化 `aiApi.runsCurrent(sid)`（:640）；
- **await 后、任何写回前二次对账**（:647）：`if (this.sessionId !== sid || currentAbort !== abortAtEntry) return`——sid 不等=会话已换（R-130，旧会话挂起卡/activeForm/activeRunId 不落新会话）；abort 句柄已换=同会话已起新轮（R-132，旧快照不覆盖在跑轮 activeRunId → 不触发 :617 abort 死锁链）；
- 函数 JSDoc 固化规则句："本函数内任何新增 await 之后、写回之前都必须重新对账"。
- 候选 B（~90 行搬动）按决策卡否决项未做。

### 2.3 Lua（R-133 + R-131 同段一并消解，`RedisChatMemoryRepository.java`）

- 静态常量 `DELETE_WITH_INDEX`（:65-74）：`local removed = redis.call('DEL', KEYS[1]) redis.call('SREM', KEYS[2], ARGV[1]) return removed`，`DefaultRedisScript<Long>`。
- `deleteByConversationId`（:164-165）= 单次 `redis.execute(script, List.of(key, CONVERSATION_IDS_KEY), conversationId)`，一次往返原子完成 → 同时消除 N2 崩溃窗口 stale 成员（R-131）与 N2-b 并发插队丢失 SADD（R-133）。
- JSDoc：语义节改写为"单次 Lua 原子完成"；已知未覆盖节新增 **Redis Cluster 约束**（两 key 不同 hash slot，Cluster 需 hash tag 改造；本仓单实例部署）。
- 返回值忽略；`removedFromIndex` 仍由独立 `SISMEMBER` 判定，行为不变。
- **真 Redis 实跑**：E2E 全量 29 用例走真实 Memurai（TC19 清理/新建对话路径实调 deleteByConversationId → Lua），全绿。

---

## 3. 验收命令输出摘要（全部实测）

### 3.1 仓内三连（前端，`frontend/`）

```
yarn typecheck → Done in 4.47s（vue-tsc --noEmit，0 error）
yarn build     → ✓ 1747 modules transformed，✓ built in 25.24s
yarn test      → Test Files 5 passed (5) / Tests 37 passed (37)
                 （基线 29 例全过 + 新增 8 例；Duration 1.48s）
```
（本执行者在子智能体施工完成后复跑确认，输出与施工侧一致。）

### 3.2 后端 Maven（`bash <LOCAL_WORK>/redteam-batch-a/.run-fix/mvn-run.sh`）

```
-B -q compile → MVN_EXIT=0
-B test -Dtest=RedisChatMemoryRepositoryDeleteTest,AiControllerHistoryDeleteTest
              → Tests run: 13, Failures: 0, Errors: 0, Skipped: 0（BUILD SUCCESS）
-B test（全量）→ Tests run: 439, Failures: 0, Errors: 0, Skipped: 0（BUILD SUCCESS，01:06 min）
```
总口径 **437 → 439，只增不减**，Errors/Failures/Skipped 全 0。TDD RED 证据：实现前新测试 2 例 Errors=2（NPE @旧实现:147）。

### 3.3 桩模式 E2E（29 用例）

```
E2E_STUB_MODE=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog ... -ArtifactDir ... -EnableGf6
→ 结果：PASS=29  FAIL=0（主用例计数）；GFSKIP=0（桩模式零容忍达标）；Q8 项 PASS=2 FAIL=0
```
重点链路全绿：TC15（流式+工具+渐进披露）、TC16（确认门+前端工具回灌）、TC18（会话隔离）、TC22（历史恢复含工具卡）、TC23（CONFIRM 快照重建+增量续收）、TC19（清理→**Lua 删除路径实跑**）、GF1-GF6 全链。证据：`<LOCAL_WORK>\chat-timeline-pkg\e2e-artifacts\`（boot.log / stub.log / gfc-*）。

### 3.4 视觉零变化佐证（CDP 冒烟 13 断言）

```
SMOKE_API=18330 SMOKE_APP=5202/ node scripts/chat-panel-smoke.mjs → exit 0
断言①-⑪ PASS（12 条）+ ⑫ SKIP（SMOKE_KILL_BACKEND 未授权，常态如实 SKIP 不假绿）
```
留证 `<LOCAL_WORK>\chat-timeline-pkg\evidence-b1\`：`result-20261011T100839Z.json`（13 断言全量观测值转录）、`b1-1-empty.png` / `b1-2-after-round.png`（真机截图：正文气泡+工具卡渲染形态与基线一致）、`b1-parts-audit.json`（vivo 双写对账）。

---

## 4. 新增测试清单

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `frontend/tests/parts-double-write.spec.ts` | 5 | ① text→tool→text 段序/seg 单调/封口/tool_result 就地更新不新建段/message_end 不改段结构/done 后段结构不变 + 旧投影逐字对账（content≡join、toolRuns≡toolParts）② 确认门 pendingCall↔tool 段两侧同步 ③ reasoning 段序 ④ 消息工厂形状 ⑤ 镜像 v1→v2 迁移（补 parts/pendingCall 并入/立即回写/幂等） |
| `frontend/tests/restore-reconcile.spec.ts` | 3 | 负例① sid 失配（await 期间 resetSession → 新会话零污染、未开旧轮续收）；负例② abort 失配（await 期间 send() 起新轮 → 旧快照写回丢弃、在跑轮 runId 未被顶替）；正例（挂起卡正常重建、requiresAction=true、lastSeq=archiveMaxSeq） |
| `backend/.../RedisChatMemoryRepositoryDeleteTest.java` | 2 | ① 单次原子脚本结构锁（一次 execute、键序 [chat:mem:s-1, chat:mem:__ids__]、ARGV=s-1、never delete/opsForSet）② 脚本体含 DEL+SREM 两段（防改坏仍绿） |

前端 29→**37**、后端 437→**439**，两侧基线均只增不破。

---

## 5. 偏差与遗留

### 5.1 偏差（均已记录在案）

1. **`RedisScript` import 包路径**：方案 §5.1 写 `org.springframework.data.redis.core.RedisScript`，实测该类不在该包，正确为 `org.springframework.data.redis.core.script.RedisScript`/`DefaultRedisScript`（spring-data-redis 3.5.11 jar 清单核验）。已按实读更正，首次编译错误即暴露。
2. **双写点比卡清单多 2 处**（:1148/:1861）：总则"凡写 toolRuns 必两侧一致"，漏写则 generative_form/前端工具真实路径立刻漂移。
3. **`suspended` 帧只更新已存在段**（`createIfMissing=false`）：该帧本不写 toolRuns 投影，照 confirm_request 语义新建会产生"只有 parts 才有的卡"。
4. **`pendingCall.status==='approved'` → 段 status `'running'`**：`approved` 不在 ToolRunStatus（类型强制），按 confirm 放行语义映射，注释写明。
5. 其余 minor 取舍（迁移幂等判定用 `v>=2 && parts 是数组`、迁移取 pendingCall 值等）见施工侧交接，判定理由均成立。

### 5.2 遗留（登记不修，非本批范围）

- **`requiresAction` 残留 true 面**：`done`/`error` 清 pendingCall 与本地乐观取消路径不写段。本批面板不读 parts（无视觉影响）；**批 2 权威翻转前必须收口**（连同 applyPendingTerminal/applyFrontendToolTerminal 的 pendingCall 维度同步）。
- **Redis Cluster 未实测**：无 Cluster 环境，约束已 JSDoc 明写；单测为行为契约锁（单 execute + 键序 + 脚本体），非真脚本执行。
- **前端 e2e 仍不驱动浏览器**（N9 缩小版缺口，仓库级既有登记）。
- R2-5/R3-3 等批 2/③b 风险反查点维持施工卡登记，本批未触碰。

### 5.3 提交官阶段附带（本卡留痕，施工者未执行）

`docs/M2-排期计划.md` 行尾注记内锚订正（只追加、零删、编辑前重新实读）：
- R-132 行：`stores/ai.ts:589` → 实为 **:588**；
- R-133 行：`deleteByConversationId`（:144-146 / DEL :145 / SREM :146）→ 实为**方法 :145 / DEL :146 / SREM :147**（本批已将该方法体改为单次 Lua execute，落点 :164-165，订正时建议一并注明）。
- R-131 台账状态"已登记·待排期/建议登记不修"宜随本批消解更新（与 R-133 同函数同段，Lua 一次消除 N2+N2-b）。

---

## 6. 工作树状态

`git status --porcelain` = 本报告 §1 的 4 改 + 3 新，**除此之外干净**；HEAD 仍为 7879f53；未 commit/push（提交官环节另行）；全程未读任何 `.env`；命名纪律（无 main+v2 组合/<OLD_REPO_DIR_CONCAT> 连写/盘符/用户名）对全部新增行正则扫描无命中。

**〔分件 2／4〕** 原件：`<LOCAL_WORK>/chat-timeline-pkg/施工报告-批2.md`

# 施工报告：对话时序包 批 2（子包②，高风险单独一棒）

**执行者模型 = Kimi K2.8 Preview**（施工整合）｜**授权来源 = Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）**
基线：主仓库 `<REPO_ROOT>`，分支 main，开工核 `git log --oneline -1` = `2f455ce`（批 1 已闭环入库，工作树干净）。
施工方式：机械活全部下沉子智能体（跑测试/grep/对账/统计），主力编码只做关键判断与整合；**全程零 git 写**（提交官统一处置）。
TDD 强制落实：每个新行为先 RED 留证再 GREEN；回归锁用变异法（注释写点→必红→恢复→全绿）留证。证据目录：`<LOCAL_WORK>\chat-timeline-pkg\evidence-b2\`。

---

## 1. 范围逐项处置

### 1.1 前置必做①：requiresAction 六残留点写段收口（决策卡口径，权威翻转前完成）

批 1 六点位只写 pendingCall 投影不写段；本批在**保留投影直写的双写期**先补段写入，翻转后再撤投影直写（见 1.3）。语义裁决表：

| 点位 | 段写入语义 | 状态 |
|---|---|---|
| done（原 :1698） | 该消息全部 requiresAction 段 → requiresAction=false + status='expired'（沿用 loadHistory 存量 expired 降级惯例） | ✅ |
| error（原 :1710-1716） | 同 done | ✅ |
| confirm() 乐观置位（原 :1035） | true→running / false→rejected / DUPLICATE→running（approved 并入 running，approved 非 ToolRunStatus，同 m-R1 口径）/ 提交失败回退→pending+requiresAction=true | ✅ |
| cancelGenerativeForm（原 :1224） | 段 → requiresAction=false + 'cancelled' | ✅ |
| applyPendingTerminal（原 :1755） | 全 messages 匹配段（status  pending/cancelled 或 requiresAction）→ requiresAction=false + 映射终态（approved→running） | ✅ |
| applyFrontendToolTerminal（原 :1773） | 非 GF FRONTEND 只收 requiresAction 不动 status；GF 按 first-wins 落映射终态；null 只收 requiresAction | ✅ |

**镜像持久化维度（GLM o1）**：新增读取侧 `sanitizeMirrorResidues`（仅 done 消息：requiresAction→false、status pending→expired、幂等），挂 loadHistory 镜像分支 writeMirror 回写之前，v1 迁移与 v2 intact 均生效；进行中轮（suspended 快照）不触碰，存活挂起由 restoreSuspendedSnapshot 重建路径兜底。

### 1.2 前置必做②：m-R2 六无回归锁写点补测

六写点逐点补 vitest 用例（frontend_tool_request 建段 / frontend_tool_result 就地更新 / suspended 只更新不新建 / GF 失败收尾 / runFrontendTool 本地结局 / loadHistory 段构造），**外加「工具→挂起→前端执行→回灌」端到端级用例**（pending→running→succeeded 全程同一段，toolCallId/seg 不变）。回归锁有效性用变异法留证（6 次逐点注释 upsertToolPart→必红→恢复→58 全绿）。

### 1.3 子包②本体：权威翻转（parts = 唯一真值）

- 新建 `frontend/src/utils/parts.ts` 纯函数层：`nextSeg/openStreamPart/sealTrailingStreamPart/upsertToolPart/findToolPart/deriveProjections（唯一派生写口）/applyFrameToParts（帧→段唯一写点）/alreadyApplied/markApplied`。
- `handleFrame`：帧级守卫（ownedByActiveRound/宿主选择/runSeq/旧轮迟到帧）原样保留；switch 前 seq 幂等（seq=null 旧帧照收、重复投递去重、乱序只去重不做连续性判定——心跳不占业务 seq，R2-3）；11 处段写全部经 `applyFrameToParts`；工具类帧/消息边界帧/轮终态后 deriveProjections 全量重算；delta/reasoning 高频路径锁步 `content +=`/`reasoning +=` 不调 derive（§6.1 性能注）。
- 旧字段直写清零：grep 审计 `stores/ai.ts` 旧字段写点仅剩 delta/reasoning 增量维护两处（§6.1 白名单）；`deriveProjections`（utils/parts.ts:185-188）为四旧字段唯一赋值点；`upsertToolRun`/`toPendingCall` 已删除且无残留调用点。
- **AiPanel.vue**（批 2 范围，批 3 未动工）：消息体改 `v-for="part in m.parts"` 按 kind 分派 text/reasoning/tool；挂起卡整块迁入 tool 分支、跟随其工具段位置（`pendingCall?.toolCallId===part.toolCallId`，③b 合并前过渡形态）；**两处 `v-if="m.content"` 全文件复核处置**（:168 正文迁入段循环、:172 复制块判据改 parts——N10 防"正文没了复制按钮还在"）；`isTyping` 改「无任何非空 text part」；滚动 watcher 改 parts 指纹；骨架屏 :120-126、挂起卡 tag 全集、toolTagType/toolStatusLabel、formHostMessageId 未动。
- 类型面：`ChatMessage.appliedSeqs?: number[]`（内部幂等记账，writeMirror Pick 天然不持久化）；parts 注释块翻转；镜像注释翻转。
- 历史/快照/续收/重挂：loadHistory 后端历史分支改「建段后 derive」；restoreSuspendedSnapshot 宿主选择改读 parts；镜像分支 sanitize→derive→回写；handleChatError 文案写段。

### 1.4 o-R1 ~ o-R4 逐条结论

| # | 结论 | 理由与处置 |
|---|---|---|
| **o-R1** | **修** | reasoning 渲染本批启用后生产可达：`openStreamPart` reasoning/text 交错先封口被中断段（与 sealTrailingStreamPart 对称）；轮终态封口末尾 streaming 段；message_end 按 R2-2 交集执行（只封 streaming 不改段结构）。用例锁定（交错封口/只封末尾流式段/段结构不变）。 |
| **o-R2** | **登记（不修）** | ABA 缺口实测不可达（runsCurrent 往返 ms 级 vs AI 轮次秒级）；修需单调轮次纪元重构，超出决策卡放行范围。观察项落点：`restoreSuspendedSnapshot` 对账守卫（currentAbort 采样值口径），后续批若动会话恢复一并重构。 |
| **o-R3** | **修** | **可达性先实测**：静态铁证 spring-ai 1.1.8 `MessageWindowChatMemory.add` 字节码无空列表短路（javap 存证）+ 本仓 `ResilientChatService.convergeMemory` trim 全 TOOL 尾可产生空窗 → 真 Redis 动态实证方法可执行。处置：空/null 分支委托已 Lua 化的 `deleteByConversationId`（一行，复用原子脚本），新增 SaveAllEmptyTest 3 例（2 Mockito 锁定单次 execute + 1 真 Redis 实证）。非空写入侧维持 o2 批外登记未动。 |
| **o-R4** | **登记（不修）** | 哨兵键碰撞为 pre-existing（旧实现同病）、正常 UUID 不可达、影响=索引丢失且下次 saveAll SADD 自愈；修需在 key() 加校验属后端范围扩张（决策卡仅放行 R-133 落点）。观察项落点：`RedisChatMemoryRepository.key()`，后续后端批随鉴权端点治理一并处置。 |

### 1.5 AiPanel.vue v-if="m.content" 两处复核

全文件 grep 旧投影读取点已逐条清点（content×3/reasoning×2/toolRuns×1/pendingCall×12/parts×0）：正文 :168 迁入 parts 段循环；复制块 :172 判据改 parts（`some(text part 非空)`），`copyMessage` 仍取派生 `m.content`（join 等价）；组件级用例合成「段有正文/投影为空」态锁定 N10 语义方向。

---

## 2. TDD RED/GREEN 留证（evidence-b2/）

| 证据 | 内容 |
|---|---|
| `red-residue.txt` / `green-residue.txt` | 六残留点写段：15 例行为 RED（confirm 失败回退 1 例天然绿，回退语义两侧一致，已注记）→ GREEN |
| `red-mirror-sanitize.txt` / `green-mirror-sanitize.txt` | 镜像残留 sanitize：3 例 RED→GREEN（v2 intact/v1 迁移/幂等） |
| `m2-mutation-{1..6}-red.txt` / `m2-mutation-green.txt` | m-R2 六写点变异 RED（逐点注释必红）→ 恢复 58 全绿 |
| `flip-red-parts-reducer.txt` | 模块缺失 RED |
| `flip-red-handleframe.txt` | **行为 RED**：parts.ts 就位后 store 未翻转时 3 例失败（seq 幂等/乱序去重/derive 时机） |
| `flip-red-panel-render.txt` | 渲染 RED：AiPanel 临时换回 HEAD 版跑组件用例 3/4 失败（首行留换前 md5，恢复后一致） |
| `flip-green-*.txt` / `flip-final-three.txt` | 15 例 GREEN、77 例全量 GREEN、typecheck+build、最终三连一次跑 |
| `flip-mutation-residue-guard.txt` | residue 守卫改读段后回归锁有牙：改回读投影→用例红→恢复全绿 |
| `flip-grep-audit.txt` | 收尾 grep 审计（旧字段写点逐条命中分类） |
| `o-r3/` | static-evidence.md、mwcm-javap.txt（字节码）、dynamic-evidence.md、code.diff、测试日志 |

## 3. 机械验收实测数字

| # | 项 | 基线 | 实测 | 判定 |
|---|---|---|---|---|
| ① | yarn test | 37 | **77/77 全绿**（8 文件；58 前置棒 + 19 翻转棒） | ✅ |
| ① | yarn typecheck | — | exit 0 | ✅ |
| ① | yarn build | — | 成功（40.21s） | ✅ |
| ② | Maven 全量 | 439/0/0/0 | **442/0/0/0，BUILD SUCCESS**（+3=SaveAllEmptyTest；真 Redis 例未跳过）；surefire XML **70**（基线 69，只增不减；类别集合比对 69+1，mtime 全新鲜） | ✅ |
| ③ | E2E 桩 PASS | 29 | **PASS=29 FAIL=0 GFSKIP=0，exit 0**（TC1–TC23、GF1–GF6 逐条 PASS，Q8①/② 亦 2/0；配方照批 1 报告 + ci.yml:178 两就绪闸；桩 18399/后端 18330，启动前 netstat，只按 PID 停自起进程，端口全释放） | ✅ |
| ④ | CDP 冒烟 | 12 PASS+1 SKIP | **12 PASS + 1 SKIP（⑫未授权杀后端，环境常态），exit 0**（剪贴板回读 TIMEOUT 为非断言旁证不计入；无 FAIL） | ✅ |
| ⑤ | check-docs | 6/6 | 本批未动 docs，无需 | — |

## 4. 偏差申报（逐条列名，不得打包——按各棒自报与主棒裁定）

1. **runFrontendTool 增补 running 中间态段写入**（前置棒）：卡面端到端用例写 pending→running→succeeded，但存量代码本地结局才写终态。为满足卡面断言在 `await executeFrontendTool` 前加一处 running 段写入（createIfMissing=false 只更新 + 先让出同步窗口）。最小增补，代码注释标「批 2 前置」。
2. **镜像「进行中轮不得触碰」守卫经 loadHistory 不可达**（前置棒）：镜像分支强制 `done: true`（HEAD 既有行为）且镜像 Pick 不含 done。守卫按卡面保留作未来防护；测试覆盖 done 消息三态+幂等。
3. **既有迁移用例断言改写**（前置棒）：`parts-double-write.spec.ts` 镜像迁移用例原断言 `requiresAction: true` 正是本批清除对象，改 false 并注释说明。
4. **ToolPart/ToolPartPatch 新增 timeoutSeconds/expiresAt/atMs**（翻转棒）：卡面未指明确认门倒计时元数据落点；挂起卡改由段派生后不落段会丢倒计时。按「段是唯一真值」落段，用例锁帧→段→派生三段一致。
5. **hasDecidedPendingResidue 改读段**（翻转棒）：卡面把该守卫列 ③b，但翻转后其判据（投影保留终态）不再成立，不改=静默拆掉取消竞态重挂防线。行为等价、判据换源，变异法锁回归。
6. **镜像分支回写条件保持 `migrated || sanitized`**（翻转棒）：未按字面「derive 后一律回写」——无条件回写破坏既有幂等断言与「无变化不写盘」口径；derive 幂等，不回写不影响正确性。
7. **删除 upsertToolRun/toPendingCall 两个 store action**（翻转棒）：卡面仅要求保留 upsertToolPart 薄包装；旧投影写点按审计口径须离场（映射逻辑迁 parts.ts `toPendingCallProjection`）。
8. **handleChatError「正文已有内容」判据改读 parts**（翻转棒）：与 §6.3 消费点口径一致。
9. **o-R1 message_end 封口取 R2-2 交集**（翻转棒）：只封 streaming 不改段结构，段结构摘要用例锁定。
10. **额外新增组件级用例 `ai-panel-parts-render.spec.ts`**（翻转棒）：卡面只点 parts-reducer.spec.ts；段序/挂起卡位置/N10 判据需挂载才验得到，补 RED+GREEN。
11. **前置棒 8 条既有断言改写**（翻转棒，逐条见 flip 棒汇报/报告附注）：全部锁的是「双写期投影实现细节」而非行为契约（rejected/approved/cancelled 终态后 pendingCall 投影由「保留终态」变「派生为 null」，挂起卡信息改由工具卡段状态 tag 承载）；其余 50 例原样全绿。

## 5. 新增观察项（带落点）

1. **终态挂起卡不再保留**（pendingCall 派生口径）：confirm 拒绝/放行、error 帧、GF 终态后挂起卡消失，信息由工具卡段状态 tag 承载。GF 取消回灌失败文案（原 AiPanel:270-272）在 cancelled 态失去渲染位 → 落点：③b（表单态落 part/错误提示独立槽）。
2. **suspended 帧单独到达不再产生挂起语义**（只更新已存在段+无投影可写）：仅当 request 帧缺失时可见，后端同批必发 request 帧 ⇒ 生产不可达 → 落点：若后端存在「只发 suspended」路径，③b 段状态机补「公告即建段」。
3. **error 帧现在收掉挂起卡**（行为改善）：此前留一张点了只 404 的死卡；`pendingToolCall` getter 不再返回它。
4. **存量 v2 镜像段可能带 `streaming: true`**（sanitize 只收 requiresAction 不封口）：刷新后段级 streaming 假态影响 openStreamPart 续段判断（仅工具/续流路径，已 done 消息不受影响）→ 落点：镜像读取侧补封口，或 ③b 段状态机规范化。
5. **复制按钮位置变化**（N10 要求）：由「正文之后、工具卡之前」移到消息末尾 → 落点：CDP 冒烟人工确认视觉可接受。
6. **`<details>` 展开态仍不持久化**（R4-1，④批范围）：reasoning 块迁进 parts 循环后仍 DOM 重建即丢。
7. **重复终帧被 seq 幂等跳过时不复位 loading**（按卡面「重复帧不写任何状态」）：实轮由 send()/reattachActive() finally 兜底 → 落点：若发现「重放 done 后 loading 卡住」，在该分支前置 `if (isTerminalFrame(frame)) this.loading = false`。
8. **o-R2 ABA 缺口**（登记不修）：见 1.4。
9. **o-R4 哨兵键碰撞**（登记不修）：见 1.4。
10. **Maven 日志应用级 ERROR 行**（负例测试故意触发，与构建结果无关）：治理噪声，可随后续批在 surefire 输出侧降噪。
11. **E2E/冒烟偏差**：E2E 前重建了后端 jar（批 1 jar 早于 o-R3 源码改动，不重建则验证的不是当前工作树；`mvn -DskipTests package` BUILD SUCCESS，零源码改动）——已申报，属必要口径。
12. **dist 产物未被运行时验证**：冒烟脚本按头部约定走 Vite dev 形态（5202），E2E 不触前端，`frontend/dist` 未被任何一步加载 → 落点：若需 dist 形态证据，另起 `yarn preview` 复跑 `chat-panel-smoke.mjs`（本批配方未要求，登记备查）。

## 6. 改动文件清单（工作树未提交，零 git 写）

修改 6：`stores/ai.ts`（±687 行重构）、`AiPanel.vue`（+277 渲染改造）、`types/ai.ts`、`parts-double-write.spec.ts`、`restore-reconcile.spec.ts`（1 行注释）、`RedisChatMemoryRepository.java`（o-R3，+12）。
新增 5：`utils/parts.ts`、`tests/parts-residue-cleanup.spec.ts`（21 例）、`tests/parts-reducer.spec.ts`（15 例）、`tests/ai-panel-parts-render.spec.ts`（4 例）、`backend/.../RedisChatMemoryRepositorySaveAllEmptyTest.java`（3 例）。
【批 2 收口修 F4 订正（红队补充事实）】原写「新增 4」不精确：`parts-double-write.spec.ts` 实为 HEAD 已跟踪文件（仅修改），真正新增 = 3 前端 spec + parts.ts + 后端测试类，计 5。
禁改面核验：`FormRenderer.vue/types/sse.ts/api/sse.ts/docs` 零触碰。

**〔分件 3／4〕** 原件：`<LOCAL_WORK>/chat-timeline-pkg/收口修报告-批1.md`

# 收口修报告 · 批 1（对话时序包 · m-R1）

## ① 署名头

- 执行者模型：**Kimi K2.8 Preview**
- 授权来源：Kimi K3 指挥官任务卡（2026-10-11，决策卡 K3 代签，DC-19）
- 施工项：唯一项 F1（m-R1：migrateMirror 段 status 权威源修正）
- 主仓库：`<REPO_ROOT>`（HEAD=7879f53，批 1 在制品未提交；全程零 git 写）
- 报告日期：2026-10-11

## ② F1 修复说明

**缺陷**：`migrateMirror` 在并入 `pendingCall` 时用 `pendingCall.status` 派生值（`'approved'→'running'`、其余同名直并）**覆盖**了由 `run.status` 派生的段 status。存量 v1 镜像「toolRun 已终态 + pendingCall 仍 pending/approved」时，迁移产物段 status 与旧投影不一致，违反「两侧逐字一致」核心不变式。

**修法（按 K3 裁决照做）**：段 `status` 一律以 `run.status` 派生为权威；`pendingCall` 只供 `kind2` 与 `requiresAction` 两维，不得覆盖 status。

**改动 1 — `frontend/src/stores/ai.ts:188-194`（migrateMirror 合并块）**

改前：

```ts
parts.splice(index, 1, {
  ...existing,
  kind2: pending.kind,
  // pendingCall 的 'approved' 是"确认已提交、工具即将/正在执行"（confirm() 放行口径），
  // 旧投影里对应 running；其余状态与 ToolRunStatus 同名同义，直接并入
  status: pending.status === 'approved' ? 'running' : pending.status,
  requiresAction: pending.status === 'pending'
})
```

改后：

```ts
parts.splice(index, 1, {
  ...existing,
  // 待决条目只供 kind2 与 requiresAction 两维；status 一律以 run.status 派生为权威
  // （存量 v1 镜像 toolRun 已终态、pendingCall 仍 pending/approved 时不得被覆盖）
  kind2: pending.kind,
  requiresAction: pending.status === 'pending'
})
```

**改动 2 — `frontend/src/stores/ai.ts:153-154`（migrateMirror 口径注释）**

改前：`- pendingCall 存在且能与某个 tool part 对上 toolCallId 时并入（kind→kind2、status、requiresAction = status === 'pending'）——待决条目本来就是"等人工/前端动作"的段；`

改后：`- pendingCall 存在且能与某个 tool part 对上 toolCallId 时并入（kind→kind2、requiresAction = status === 'pending'）；段 status 只由 run.status 派生，不被待决口径覆盖；`

（改动 2 为注释口径随代码行为的同步修订，未改变任何逻辑。）

**改动 3 — `frontend/tests/parts-double-write.spec.ts:269-273`（测试⑤断言）**

改前：`status: 'pending'`（把漂移显式断言成行为）；改后：`status: 'succeeded'`（以该用例实际数据 `toolRun.status='succeeded'` 的 run.status 为权威）。注释同步改为「status 以 toolRun 为权威」。只改断言，未删例。

## ③ TDD RED / GREEN 原始输出节选

**RED（先改测试、未改代码，`yarn test`）**：

```
 × 镜像迁移（§6.4）：v1 旧镜像补出 parts 并立即回写 v2，待决条目并入工具段，迁移幂等 10ms
...
 FAIL  tests/parts-double-write.spec.ts > parts 双写：文本 / 工具段的顺序与封口 > 镜像迁移（§6.4）...
AssertionError: expected { kind: 'tool', seg: 1, …(9) } to match object { kind: 'tool', seg: 1, …(8) }
...
-   "status": "succeeded",
+   "status": "pending",
...
 Test Files  1 failed | 4 passed (5)
      Tests  1 failed | 36 passed (37)
error Command failed with exit code 1.
```

仅测试⑤见红，其余 36 例全绿，符合预期。

**GREEN（修 migrateMirror 后，`yarn test`）**：

```
 ✓ tests/sse-watchdog.spec.ts (4 tests) 10ms
 ✓ tests/ready.spec.ts (4 tests) 213ms
 ✓ tests/tools-and-deadline.spec.ts (21 tests) 10ms
 ✓ tests/parts-double-write.spec.ts (5 tests) 14ms
 ✓ tests/restore-reconcile.spec.ts (3 tests) 398ms
 Test Files  5 passed (5)
      Tests  37 passed (37)
```

## ④ 机械验收三连（frontend/ 下实测）

| 项 | 命令 | 结果 |
|---|---|---|
| ① 测试 | `yarn test` | **37/37 全绿**（5 文件全过） |
| ② 类型 | `yarn typecheck`（vue-tsc --noEmit） | **0 error**（Done in 3.33s） |
| ③ 构建 | `yarn build` | **成功**（`✓ built in 25.21s`，含 vue-tsc 前置检查） |

后端未动，未跑 Maven。

## ⑤ 附带注记（只登记，不改原文）

- GLM m1（XML 计数口径 69 非 68）：已收悉。
- GLM m2（RED 证据缺失）：本次已按强制 TDD 顺序补齐范例（见 §③）。
- GLM m3（申报粒度）：已收悉。
- 红队 m-R2 与 o-R1~o-R4：登记入批 2 收口清单，本批不动。

**迁移幂等复核**：测试⑤后半段即幂等复核（v2 已迁移镜像二次走 loadHistory，savedAt 不变、不再回写），GREEN 中该例通过，既有幂等测试未破坏。

**pendingCall 覆盖维度排查**：migrateMirror 内 pendingCall 合并现仅剩 `kind2`、`requiresAction` 两维（裁决允许）；**未发现**其他被 pendingCall 维度覆盖的字段，无顺手改。

## ⑥ 偏差申报（逐条列名）

1. **测试⑤行号口径差异**：任务卡引用 `frontend/tests/parts-double-write.spec.ts:1096-1109`，当前在制品实际文件总行数 293，测试⑤为末例（:230-292），断言点 :264-277。用例内容与卡面描述一致（v1 镜像：toolRun.status='succeeded' + pendingCall.status='pending'），仅行号因文件演进漂移。已按实际定位施工。
2. **migrateMirror 行号口径差异**：卡引用 ai.ts「约 :188-195 / :175」，当前实际合并块 :188-194、status 派生点 :175，与卡面一致，无实质偏差。
3. **注释同步改动**：ai.ts:153-154 口径注释随代码行为修订（见 §② 改动 2），属注释随代码的口径同步，未改逻辑，特此申报。
4. **反例数据形态**：卡面反例①为 `toolRun succeeded + pendingCall approved → 错置 running`；现存测试⑤用例数据为 `pendingCall.status='pending'`。二者同源于同一处 status 覆盖，修复（整体移除 status 覆盖）对两种形态均生效，测试按该用例实际数据定期望 `'succeeded'`。
5. 其余与本卡无出入。

**〔分件 4／4〕** 原件：`<LOCAL_WORK>/chat-timeline-pkg/收口修报告-批2.md`

# 收口修报告：对话时序包 批 2（红队 MAJOR-1 处置）

**执行者模型 = Kimi K2.8 Preview**（施工整合）｜**授权来源 = Kimi K3 指挥官处置裁决（决策卡 K3 代签，2026-10-11，DC-19）**
仓库：<REPO_ROOT>（HEAD=2f455ce，批 2 在制品=施工基线；全程零 git 写）。
案卷：<LOCAL_WORK>\chat-timeline-pkg\（红队报告-批2.md 输入）。证据：<LOCAL_WORK>\chat-timeline-pkg\evidence-b2fix\。

## 0. 子智能体模型自证（任务卡强制条款）

| 子智能体 | 任务 | 钉定模型 | 自证 |
|---|---|---|---|
| agent-480（coder） | F1~F3 施工（映射实现 + 死分支收口 + TDD） | **显式 `model=DeepSeek/deepseek-flash`（DeepSeek V4 Flash）**，未靠会话默认 | 其汇报首行自报「实际使用模型 DeepSeek V4 Flash（本任务钉定口径）」；本棒（F4 报告订正、整合核验、本报告撰写）由主棒 Kimi K2.8 Preview 亲做，无下沉。 |

## 1. 问题与裁决回顾

红队 MAJOR-1（实测成立，K3 确认）：`applyFrameToParts` 的 `frontend_tool_result` 分支只按 `frame.ok` 置 succeeded/failed、**不读 `frame.status`**，`localTerminal` 缺 `cancelled`；叠加 `handleFrame` 先 `applyFrameToParts` 后 `applyFrontendToolTerminal`（GF 守卫要求段仍 pending/cancelled，已被前置改写⇒恒假），四类终态（FRONTEND_CANCELLED / CANCELLED / TIMEOUT / REJECTED_ARGUMENTS）的工具卡恒显红「执行失败」。纯显示层缺陷，无测试覆盖。

## 2. F1~F4 逐条落点

- **F1**（读 frame.status 映射）：`frontend/src/utils/parts.ts:210-228` 新增 `frontendResultStatus()`；`:351-375` 分支改 `localTerminal ? 本地终态 : fromFrame ?? (ok ? succeeded : failed)`；`localTerminal` 扩为 6 成员（`:358-360`）；文件头裁决口径注释 `:196-208`。
- **F2**（单写点收口）：`frontend/src/stores/ai.ts:1593-1613` 帧路删去原二次调用（`terminal` 计算 + `applyFrontendToolTerminal`）——**帧路唯一段写点 = `applyFrameToParts`**（主棒亲验 :1601 无第二个写点）；`ai.ts:1720-1732` `applyFrontendToolTerminal` 保留、doc 改写为「只剩非帧路径（409 响应体 / 续收快照）」；`ai.ts:359-368` 过时注释更新。**⚠ 机制偏差见 §5-1，需 K3 追认。**
- **F3**（TDD）：新 spec `frontend/tests/frontend-tool-result-terminal.spec.ts`（16 例）：reducer 五路终态映射 + status 缺失/未知兜底 + localTerminal 保护（含 cancelled）+ 投影级；store 链路 GF 两例（FRONTEND_CANCELLED / FRONTEND_RESULT）+ 非 GF 一例（续收 pending 段收 TIMEOUT）；F2 两例（帧路不经 applyFrontendToolTerminal 的 spy 实证 + 终态收敛幂等）。RED 先于实现落盘（21:24，实现 mtime 20:05/20:16）→ GREEN 21:25。
- **F4**：`施工报告-批2.md` §6「新增 4」已订正「新增 5」并留痕（红队补充事实：parts-double-write.spec.ts 为 HEAD 已跟踪文件；真正新增 = 3 前端 spec + parts.ts + 后端测试类）。

## 3. 后端枚举对账表（只读 grep，后端零写入）

帧装配点 `SseChatEmitter.java:613-619`：`frame.put("status", pending.getStatus())`——取值域 = `PendingToolCall` 状态常量（`PendingToolCall.java:20-60` 共 10 个），非独立实参。主棒独立复核与施工棒一致：

| 发口（文件:行） | 实发枚举 | 帧 ok | 前端映射 | 依据 |
|---|---|---|---|---|
| ConfirmGate.java:491（submitFrontendResult 过复核） | `FRONTEND_RESULT` | true | **succeeded** | :484 置 FRONTEND_RESULT |
| ConfirmGate.java:526（dismiss=用户放弃表单） | `FRONTEND_CANCELLED` | false | **cancelled** | :516 |
| ConfirmGate.java:292（cancel，frontend 支） | `CANCELLED` | false | **cancelled** | :279-280 |
| ConfirmGate.java:333（expire，frontend 支） | `TIMEOUT` | false | **expired** | :315-318 |
| SpToolCallingManager.java:305（入参被闸门拒） | `REJECTED_ARGUMENTS` | false | **rejected** | :298 |
| —（本帧型实测不发，防御映射） | `SUCCEEDED` | — | succeeded | 前向兼容别名（裁决建议表所列，后端无此常量） |
| —（本帧型实测不发，防御映射） | `REJECTED` / `BLOCKED` | — | rejected / blocked | 与 tool_result 分支同形 + ConfirmGate.cancel「发帧不受 PENDING 守卫」竞态残留值 |
| —（status 缺失/未知） | — | false | failed（ok 兜底） | F1 裁决口径 |
| —（status 缺失/未知） | — | true | succeeded（ok 兜底） | F1 裁决口径 |

实发全集 = {FRONTEND_RESULT(ok=true), FRONTEND_CANCELLED, CANCELLED, TIMEOUT, REJECTED_ARGUMENTS}，恰好覆盖 F1 要求的成功/取消/超时/入参拒绝四类。前端类型 `types/sse.ts:212` 为 `PendingStatus | string` 宽口径 ⇒ 类型层无缺口（未碰 sse.ts）。对账原文：`evidence-b2fix\backend-status-enum.txt`。
**AiPanel.vue 无缺口未碰**：`toolTagType` 已 cancelled/expired→info、rejected/blocked→warning；`toolStatusLabel` Record 九态穷举（cancelled='已取消'、expired='会话已失效'）。

## 4. F2 帧序论证（施工棒验证 + 主棒复核，结论成立且强于裁决表述）

1. F1 后 `applyFrameToParts` 对帧路段**必然**给出终态裁决且 requiresAction=false ⇒ 原第二次调用对非 GF 支恒空写。
2. GF 支守卫含 `'cancelled'`：对「本地乐观 cancelled 段」**不恒假**——二次调用会按帧 status 把 cancelled 翻成另一终态，两写点同段不同裁决（spy 实证），这正是要收掉的分裂面。删帧路第二写点后，**帧内无帧序敏感可言**（比「幂等」更强）。
3. 跨路（帧 vs 409/续收）映射同源（409 体 status 与帧同取 `pending.getStatus()`；续收走 `frameStatusToPendingStatus` 同表），顺序不改变映射结果。

## 5. 偏差申报（逐条）

1. **【需 K3 追认·最重要】F2 机制偏差**：字面「删除 applyFrontendToolTerminal 的 GF 守卫及 status 写段」与红线「不改既有 77 用例断言语义」直接冲突——该函数有 3 个调用点（帧路 / 409 DUPLICATE 响应体 ai.ts:1168 / 续收快照 ai.ts:714），后两者**无结果帧**，GF 分支是它们唯一 status 写点；既有用例 `parts-residue-cleanup.spec.ts:250/:261` 直接钉定这两路。字面删除 ⇒ 2 用例必红 + 真实语义回归（GF 段永停 pending，hasDecidedPendingResidue 失效，取消竞态重挂复活）。施工棒改按**等价目标**落地：帧路单写点（F2 目标达成、分裂面消除），函数本体保留服务非帧路径。若 K3 坚持字面，需同时授权改该 2 用例并接受非帧路径回归。
2. **F1 localTerminal 扩面**：裁决字面「补入 cancelled」，实现按该函数既有头注（`:240`）扩为 6 成员（failed/rejected/blocked/succeeded/expired/cancelled），头注与实现就此一致；均属已落定终态，符合 first-wins 单调性（「已落定的终态不被后续帧改写」既有口径）。
3. **`SUCCEEDED` 后端不存在**：本帧型成功实发值为 `FRONTEND_RESULT`；SUCCEEDED 仅作前向兼容别名保留（parts.ts:213 注释注明）。
4. **`BLOCKED`/`REJECTED` 不在本帧型实发集**：防御映射保留（与 tool_result 分支同形 + cancel 竞态残留值覆盖）。
5. **口径角（未钉测试，待 K3 定夺）**：本地乐观 cancelled 段（唯一置位点 cancelGenerativeForm→applyPendingTerminal）遇 `FRONTEND_RESULT` 终帧时**保持 cancelled**（F1 字面容许）；而既有文档口径「帧终态覆盖乐观 cancelled」（ai.ts 旧注释）与非帧路 applyFrontendToolTerminal 行为是「帧终态胜」⇒ 残余不对称。若定「帧胜」：把 parts.ts:358-360 的 'cancelled' 移出 localTerminal 即可（一行），其余测试不受影响。

## 6. F5~F7 去向登记（本卡不动工）

| 项 | 内容 | 去向 |
|---|---|---|
| m2 | sanitizeMirrorResidues 守卫死码（done!==true 经 loadHistory 不可达） | 批 3'（③b）随段状态机规范化一并处置 |
| m3 | m-R1 申报粒度（migrateMirror requiresAction 判据措辞差异打包带过） | 批 3 前置项（R-138 登记面） |
| O1 | 挂起卡终态 tag 死码（投影终态不再保留 ⇒ tag 分支不可达） | 批 3（③b）挂起卡并入 ToolPart 卡时清理 |
| O2~O5 | 红队观察余项 | 批 3 处置清单（随③a/③b 施工逐项销记） |

## 7. 验收数字（机械验收）

| 项 | 标准 | 实测 | 判定 |
|---|---|---|---|
| RED 先落盘 | 时间戳早于转绿 | red 21:24:02/21:24:41（实现 mtime 20:05/20:16 未改）→ green 21:25:03 → 定稿三连 21:26-21:27 | ✅ |
| yarn test | 77+新增全绿 | **9 文件 / 93 passed**（+16，0 改既有断言；主棒 21:28 亲跑复核 93/93） | ✅ |
| yarn typecheck | 绿 | vue-tsc --noEmit 干净（final-three.txt §31-36） | ✅ |
| yarn build | 绿 | ✓ built in 37.41s（final-three.txt） | ✅ |
| 四类终态映射断言+对账一致 | §3 表 | 16 例新用例 + 对账表与后端 PendingToolCall 10 常量逐一核对 | ✅ |
| GF 死分支 | 帧路已删、其余行为不回归 | 帧路单写点（主棒亲验 ai.ts:1593-1613）；409/续收路径既有用例 :250/:261 原样绿 | ✅（机制偏差见 §5-1） |
| 施工报告 §6 计数 | 已订正 | F4 完成留痕 | ✅ |
| 后端 | 零触碰 | 仅只读 grep 对账；未跑 mvn（本卡不要求） | ✅ |

## 8. 改动文件清单（git status 实出）

收口修本棒净改动 3 项（基线 11 文件为批 2 在制品，全数保留）：
- `M frontend/src/utils/parts.ts`（F1 映射 + localTerminal + 注释）
- `M frontend/src/stores/ai.ts`（F2 帧路删第二写点 + 注释/doc）
- `?? frontend/tests/frontend-tool-result-terminal.spec.ts`（新增 16 例）
- 另：`施工报告-批2.md` §6 行尾订正留痕（案卷文件）。
- AiPanel.vue / types/sse.ts / FormRenderer.vue / backend：零触碰（无缺口）。
