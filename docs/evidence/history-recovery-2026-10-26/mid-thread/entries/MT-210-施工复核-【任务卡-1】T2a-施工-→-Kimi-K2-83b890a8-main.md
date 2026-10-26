<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-210 · 施工复核 · 【任务卡 1】T2a 施工 → Kimi K2.8 Preview（主力编码）（83b890a8 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m12` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt10` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m29`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `83b890a8` / agent `main`（`main` = 主会话）；原字符 3,733（链内 1 段、合计 3,733）。
> **链号 / 类别**：`MT-210` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 4 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m29`（6,271 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 1 / 旧仓名与同源仓名 2 处。

---

返修决策卡已入库（**a71832d**，HEAD 与 origin/main 同步）。下面两张任务卡可直接转发，**并行投放**（卡复核不阻塞施工）：

## 【任务卡 1】T2a 施工 → Kimi K2.8 Preview（主力编码）

```
【角色】Kimi K2.8 Preview，ai-example 合流项目主力编码。只执行不重新裁决；拿不准报【待裁决】，不要自行变更设计。

【仓库】<REPO>（分支 main）。开工先 git pull 确认 HEAD=a71832d 或更新。全程零 git 写操作（禁 add/commit/stash），全部产出保持未提交。

【设计依据（先读，必读）】
1. docs/M2-T2-返修决策卡.md —— T2a-D#1~#5 五张已签字卡，是本次施工唯一规格来源
2. docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md —— 裁决结论节有 14 条处置全文，其中 A2/B2/C1/C2/C3 的守卫细节是验收点
3. docs/evidence/GFd-双FormRenderer冒烟验证.md —— 双卡缺陷的实测基线

【施工范围（仅以下四项，T2b 一律不碰）】
1. 双投影分离（T2a-D#1）：frontend/src/stores/ai.ts 中 frontend_tool_result 帧的 status 广播只作用 message.pendingCall 投影，且仅覆盖 status==='pending' 的消息；目标 toolRun 已终态不改写；upsertToolRun 的 localTerminal 集合（ai.ts:955-956 附近）补 'succeeded'。
2. cancelled 终态与终态单调（T2a-D#2）：
   - frontend/src/types/ai.ts PendingToolCall.status 联合补 'cancelled'；frontend/src/types/sse.ts PendingStatus 联合补 'FRONTEND_CANCELLED'；
   - 帧 status → pendingCall.status 映射表：FRONTEND_RESULT→succeeded / FRONTEND_CANCELLED→cancelled / TIMEOUT→expired / REJECTED→rejected / BLOCKED→blocked；
   - cancelGenerativeForm 本地 cancel 仅乐观置位；帧终态为最终裁决，已终态（succeeded/rejected/blocked/cancelled）不降级；
   - 镜像兼容：readMirror/loadHistory 对旧镜像中非 pending 残留按终态展示、不进 pending 扫描；
   - pendingToolCall getter（ai.ts:214-228）改为跳过非 pending 终态继续向前扫描；
   - 帧到达且本地 activeForm 仍 filling 时，给用户提示"该表单已在其他窗口提交"（用项目既有 Element Plus 组件，禁自写样式）。
3. activeForm 聚焦指针 + FormRenderer 唯一实例（T2a-D#3）：activeForm 降级为聚焦指针（按 toolCallId 引用），渲染由 pendingCall 投影驱动，同 toolCallId 至多一个 FormRenderer 实例；openGenerativeForm 加守卫：该 toolCallId 已有终态则不挂起。
4. 409 中性终态（T2a-D#4）：取消回灌 POST 收到 409 DUPLICATE 时不报错，按中性终态收敛卡片；如需后端微调限最小改动。

【禁止事项】不动 runs/current 端点、session→run 索引、reattach 重建路线（T2b 范围）；不引 vitest 等任何新依赖；不改 ci.yml；不动 docs/adr、docs/M2-排期计划.md；不改端口分配。

【验证（全部真实执行，证据进文档）】
1. 后端：mvn test 全绿（基线 249 执行通过 / 269 @Test 静态）。Maven 全路径："<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
2. 前端：yarn typecheck + yarn build 双绿。
3. CDP 冒烟脚本化（入库 scripts/ 目录，附用法注释）：Node22 内置 WebSocket+fetch 直连 CDP 驱动独立 Chrome（--remote-debugging-port + 独立 user-data-dir），参照 <LOCAL_WORK>\gfc-dualform-smoke\smoke.mjs；前端 dev 端口 5202（.env.local 覆盖）、代理目标 18318；后端新端口从 18330 起分配，启动前必 netstat 查占用；只能停自己启动的进程。三条断言必须全部通过且可重复执行：
   ① 挂起中刷新后页面只有一张表单卡（单卡）；
   ② 同一 toolCallId 不出现双份工具卡/挂起卡；
   ③ 取消后所有持该 toolCallId 的消息 pendingCall.status 收敛为 cancelled。
   AI 服务（OpenAI 兼容）：export AI_API_KEY="<REDACTED_API_KEY>"（兼容 DEEPSEEK_API_KEY；base-url https://api.deepseek.com，model deepseek-flash；禁止写入任何文件）。Memurai（Redis）127.0.0.1:6379 应在运行。

【产出】docs/evidence/M2-T2a-施工与验证-K2.8.md：改动清单（文件:行号）、每项验证的原始输出摘录、三断言执行证据、偏差与【待裁决】清单。关键断言标注【实测】/【推断】。脱敏：盘符路径用 <REPO_ROOT>/<MAVEN_HOME>/<MEMURAI_HOME>，key 用 ***。
```

## 【任务卡 2】返修决策卡复核 → GLM-5.3（复核与测试）

```
【角色】GLM-5.3，ai-example 合流项目复核员。独立复核，不施工、不改代码。全程零 git 写操作。

【仓库】<REPO>（分支 main，HEAD=a71832d 或更新）。

【任务】复核 M2-T2 返修决策卡是否完整闭合红队发现：
1. 读 docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md 全文（14 条发现）与其裁决结论节（14 条处置）；
2. 读 docs/M2-T2-返修决策卡.md（8 张新卡，已签字）；
3. 逐条核验：每条红队发现 → 裁决处置 → 新卡条款，三者是否一致闭合；重点抽查：
   - A1/B1 两阻断的返修路线（快照重建+增量续收 / session→run 索引+结构化端点）在 T2b 三卡中是否语义自洽、有无新引入矛盾；
   - C1 双守卫、A2 终态单调、C3 getter 扫描方向在 T2a-D#1/D#2 中是否条款化且可实现；
   - E1/E2 的风险承认是否在 T2a-D#5 显式留痕；
   - 新卡之间有无相互冲突（如 T2a-D#3 的 openGenerativeForm 守卫与 T2b-D#3 的快照重建路线）。
4. 对卡的引用行号做抽验（至少 5 处），确认引用仍与代码现状一致。

【产出】docs/evidence/M2-T2-返修卡复核-GLM-5.3.md：逐条闭合表（14 行：发现编号/处置/对应卡条款/闭合判定）、抽验记录、发现的问题清单（如有，按 阻断/严重/建议 分级）、总体结论（通过/打回）。保持未提交，标注【实测】/【推断】，脱敏口径同仓库惯例（盘符 <REPO_ROOT> 等占位，key 用 ***）。
```

## 当前状态

- HEAD=**a71832d**，工作区干净，CI 侧 docs-only 变更风险低。
- K2.8 施工回报后：我先验收，再并行发红队卡（DS-V4-Pro）+ 独立复现卡（GLM-5.3，若卡 2 已通过则转施工复现），合格后提交官入库。
- 交接状态：本会话第 4 轮，双阈值均未触发。
