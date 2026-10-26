<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-325 · 设计 · M2-T2 表单态一致性包 · 设计决策卡（指挥官 K3，2026-10-08）（fc21d1da / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`FormRenderer.vue`、`SessionGate.java`、`SseChatEmitter.java`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m33` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt25` 条有正文的 assistant 消息（共 31 条有正文），末条有正文消息为 `m40`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fc21d1da` / agent `main`（`main` = 主会话）；原字符 6,041（链内 1 段、合计 6,041）。
> **链号 / 类别**：`MT-325` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 9 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `2%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m40`（837 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

探查质量极高——三域共同根源假设有五条代码事实支撑，且把"①修复会常态化②"的耦合也点出来了。事实充分，我现在出 M2-T2 同源设计决策卡。

---

# M2-T2 表单态一致性包 · 设计决策卡（指挥官 K3，2026-10-08）

**根源诊断（采信探查【推断】，证据见各行号）**：表单态在前端有三份互不同步的副本——全局单例 `activeForm`（`stores/ai.ts:204`）、每条消息一份 `pendingCall`（`types/ai.ts:190`）、每个 FormRenderer 实例一份本地值（`FormRenderer.vue:138`）；服务端权威态（`ai:pending:<runId>` + `frontend_tool_request` 帧归档）齐备但没有一条"按 `(runId, toolCallId)` 幂等投影到唯一消息 + 唯一渲染实例"的收敛路径。三域是同一断层的三个出口，必须一并修。

## T2-D#1 核心模型：服务端权威 + 唯一投影

- **选择**：建立单一投影入口 `upsertPendingCall(runId, toolCallId, patch)`——镜像恢复、历史对账、reattach 回放、`frontend_tool_result` 帧四条写路径全部改走它；同一 `toolCallId` 在 `messages` 中**至多一条挂起卡**，已存在则更新、不新写
- **理由**：②的双卡正是"镜像恢复写一份 + reattach 新消息再写一份"（`ai.ts:346-353` 与 `:528/:937-944`）两个写点造成的；不统一写入口，任何局部修补都会被下一条新路径绕过
- **被否决方案**："渲染层去重"（AiPanel 按 toolCallId 只渲染首卡）——数据层仍有两份 `pendingCall`，③的终态广播和 getter 计数（`ai.ts:214-228`）继续错乱，治标不治本
- **推翻条件**：发现某条写路径语义上必须允许同 toolCallId 多实例存在（目前无证据）
- **验证用例**：GFd 双卡场景（注入同 toolCallId 两条消息）收敛为单卡；刷新→reattach 全程消息数不膨胀

## T2-D#2 刷新续填：自动 reattach + 补 sessionId→runId 端点

- **选择**：①新增端点 `GET /api/ai/runs/current?sessionId=`（复用 `SessionGate.currentRunId`，`session/SessionGate.java:116-127` 已有实现零调用）；②前端 `loadHistory` 对账时若存在未决挂起（镜像 `pendingCall` 或端点返回 `pendingUnresolved>0`）→ **自动 `reattachActive`**，回放 `frontend_tool_request` 帧重建表单卡（后端回放机制已齐备，`SseChatEmitter.java:363` 不跳过该帧）；③`loadHistory` 历史非空时不再无脑 `pendingCall: null`（`ai.ts:389`），改为交给投影入口裁决
- **理由**：队列语义是"刷新可续填"；手动恢复条（备选）多一次点击且"表单已丢失"文案（`AiPanel.vue:217`）本就是缺陷承认。自动 reattach 的双卡副作用由 D#1 唯一投影兜住——这就是三域必须同批修的硬理由
- **被否决方案**："只把 activeForm 入镜像"（纯前端恢复）——镜像 TTL 与后端挂起超时各自为政，两源权威必然漂移；且镜像字段表注释（`ai.ts:202-203`）当初的决定前提是"无续填需求"，该前提已被 M2-T2 推翻
- **表单已输入值**：**不恢复**（schema 级恢复，值域重置）。理由：用户输入不落 localStorage 是有意的安全边界；续填语义是"表单还在、可继续填写提交"，不是"草稿恢复"。如需草稿级恢复另立需求
- **推翻条件**：自动 reattach 在"挂起已超时但前端未知"边界产生死循环/刷屏（验收用例须覆盖：挂起已过期时 reattach 应落到终态帧优雅收尾）
- **验证用例**：E2E 新增——挂起中"模拟刷新"（断开→走恢复链路）→ 表单卡复现可提交，后端 `submitFrontendResult` 接受且不再 409/404

## T2-D#3 activeForm 降级为指针，渲染实例唯一

- **选择**：`activeForm` 从"表单态真源"降级为"当前聚焦 toolCallId 的指针 + 该卡瞬时态（submitting/error/remainingSeconds）"；表单 schema 与挂起事实以消息级 `pendingCall`（经投影唯一）为准；`FormRenderer` 按 `toolCallId` 全局至多挂载一个实例
- **理由**：单例 × N 消息的基数错配（`AiPanel.vue:204-213`）是②的渲染侧根因；D#1 保证 N=1 后，单例指针不再矛盾
- **验证用例**：双卡冒烟（GFd 场景复跑）两卡本地值隔离问题随单卡消解；卡②"假已提交"文案（`ai.ts:705` duplicate 判成功）随唯一卡不再可达

## T2-D#4 取消的消息级终态：`cancelled` 入取值域 + 帧 status 广播

- **选择**：①`PendingToolCall.status` 取值域补 `'cancelled'`（`types/ai.ts:213`）；②`frontend_tool_result` 帧处理器消费 `frame.status`（`SseChatEmitter.java:282` 已下发）：`FRONTEND_CANCELLED` → **所有**持该 toolCallId 的消息 `pendingCall.status='cancelled'`（广播，修多消息遗留）；③本地主动取消（`cancelGenerativeForm`/`stop`）在 abort 断流**之前**先置消息级 `cancelled` 本地兜底（帧到不了也能收敛，`ai.ts:744-759` 顺序问题）；④**显式驳回"置 null"口径**（`ai.ts:965-967` 现状）：取消是用户可见的业务终态，卡片应显示"已取消"而非消失——队列原文"消息级 pendingCall.status 收敛"即此义；探查上报的模糊点以此裁决闭合
- **被否决方案**：维持"结局帧置 null"——帧在 abort/断流/多卡场景不可达（探查③断点 2），遗留 pending 占位恒"挂起等待中"
- **验证用例**：取消后消息卡显示"已取消"终态、`pendingToolCall` getter 不再计未决、输入区占位复原；断流（stop）路径同样收敛

## T2-D#5 409 duplicate 前端语义修正

- **选择**：`submitFrontendResult` 收到 `DUPLICATE_TOOL_CALL_ID` 不再判 `submitted`（`ai.ts:705`），改为该卡收敛为"该表单已在其他实例提交"中性终态
- **理由**：D#1/D#3 落地后此分支理论上不可达，但作为纵深防御必须修正误导文案——它现在是"值未被采纳却显示已提交"的假成功
- **验证用例**：构造双实例竞态（CDP 注入），卡②不出现"已提交，等待回执"

## T2-D#6 测试策略：不引入前端测试框架（本包）

- **选择**：验收三层——后端单测（`ReattachLastSeqConformanceTest` 补 `frontend_tool_request` 回放断言；新端点单测；取消帧 status 断言）+ E2E 服务端侧新增"刷新续填"用例（脚本自扮前端，断言重放帧与提交接受）+ 前端 CDP 冒烟脚本自动化（复用 `<LOCAL_WORK>\gfc-dualform-smoke` 经验，Node22 内置 WebSocket 直连 CDP，覆盖三域 UI 终态，**不进 CI**——CI 无浏览器）
- **被否决方案**：本包引入 vitest——前端从零搭测试基建（配置/CI job/组件挂载桩）是独立项目级工作，塞进 T2 会稀释焦点；**记为 T4 或观察项：前端测试基建评估**
- **风险留痕**：前端逻辑长期无 CI 门禁，回归靠手工/冒烟——这是已知并接受的技术债，显式记录

## 随包小项（独立棒，不耦合主设计）

- **条件写侧操作符校验前移**：后端校验逻辑前移到前端表单交互侧即时反馈（复用后端既有校验清单，双端同源问题注意——参考 P4 教训，前端侧是副本，注释注明对账义务）
- **CORS 白名单配置化（LAN 方案 B）**：配置项外置，单测覆盖

---

**请您签字（决策请求）**：T2-D#1~#6 六张卡。其中真正需要您拍板的取舍点有两个——**D#2 的"表单已输入值不恢复"**（若业务上要草稿级恢复，复杂度上一个台阶）与 **D#6 的"不引入 vitest"**（前端测试债继续挂着）。其余四张若您无意见即视为通过。

**红队任务卡（DS V4 Pro，可直接转发）**——设计我是作者（Kimi 系），按纪律红队给 DS 系：

```text
你是 DeepSeek V4 Pro，担任 ai-example 合流项目 M2-T2「表单态一致性包」设计的反方审查（红队）。设计作者是 Kimi K3，你的职责是推翻，不是附和。

【仓库】<REPO>（分支 main，HEAD=9de199d）。
【审查对象】本次对话产出的六张设计决策卡 T2-D#1~#6（要点如下，完整事实依据在仓库）：
- D#1 服务端权威+唯一投影：单一 upsertPendingCall(runId,toolCallId) 入口统一四条写路径，同 toolCallId 至多一条挂起卡
- D#2 刷新续填：新增 GET /api/ai/runs/current?sessionId= 端点 + loadHistory 发现未决挂起时自动 reattachActive 重建表单卡；已输入值不恢复
- D#3 activeForm 降级为聚焦指针，FormRenderer 按 toolCallId 全局唯一实例
- D#4 PendingToolCall.status 补 cancelled；frontend_tool_result 帧 status 广播到所有持该 toolCallId 的消息；本地取消先于 abort 置 cancelled；驳回"置 null"口径
- D#5 409 DUPLICATE 前端从"假已提交"改为中性终态
- D#6 不引入 vitest；验收=后端单测+E2E 服务端侧新用例+CDP 冒烟（不进 CI）

【事实基线（你应独立核实再攻击）】docs/evidence/GFd-双FormRenderer冒烟验证.md、GFc/GFb 系列证据；关键代码点：frontend/src/stores/ai.ts（115-134 镜像字段、202-204 activeForm 注释、343-396 loadHistory、528/937-944 reattach 双写、705 duplicate 判成功、723-759 取消与 stop、950-975 帧处理、1003 done 兜底）、frontend/src/types/ai.ts:213（status 取值域）、AiPanel.vue:169/202-223/217/553-564、backend ConfirmGate.java:346-417、SseChatEmitter.java:263-286/358-375/424-446、ResumeService.java:118-140/290-298、SessionGate.java:116-127、AiController.java:371-407。

【必查攻击面（不限于此）】
a. 唯一投影的并发/时序：reattach 回放帧与 loadHistory 对账赛跑时投影会不会写丢/写重；SSE 帧到达顺序与本地兜底的冲突（先本地 cancelled 后帧到达 rejected/succeeded 谁赢？）
b. D#2 自动 reattach 的边界：挂起已超时/已被他端提交/会话 409 占用时自动重挂的行为是否定义完备；多标签页同时打开同会话会怎样
c. D#4 广播语义：同 toolCallId 多消息全标 cancelled 是否误伤"一条 pending 一条已 succeeded"的历史场景；status 域扩充对既有迁移/镜像反序列化的兼容
d. 服务端侧是否需要配套改动被低估（如 reattach 对已终态轮次的回放语义 AiController.java:283-295）
e. D#6 测试缺口：三域前端逻辑零 CI 门禁，本包改动量大，冒烟不进 CI 的残余风险是否被如实低估
f. 整体：这个"同源一并修"的scope 是否反而过大，可否再拆出独立可交付的最小闭环

【产出】写入 docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md（未提交，文末留空「裁决结论（指挥官 K3）」）：问题清单逐条编号+严重级+证据（文件:行号）+【实测】/【推断】/【假设】；无问题攻击面也列出排查方法。零 git 写操作，不改被审文件。脱敏：路径 <REPO_ROOT>、key ***、无盘符样式字面量。
【汇报】问题总数、阻断/严重/建议分布、最关键 3 条摘要。
```

**交接状态记账**：本会话第 8 轮。在轨=零（探查棒已回报，无后台任务）；待您动作=①T2-D#1~#6 签字（重点 D#2 值恢复口径、D#6 vitest 取舍）②转发红队卡给 DS V4 Pro ③M1 签字与业务决策项照旧。红队回报后我逐条处置，再出施工任务卡（K2.8 主力编码）。
