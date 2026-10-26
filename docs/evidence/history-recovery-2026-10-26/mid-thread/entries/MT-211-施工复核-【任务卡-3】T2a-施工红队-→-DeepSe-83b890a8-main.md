<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-211 · 施工复核 · 【任务卡 3】T2a 施工红队 → DeepSeek V4 Pro（对抗性找茬）（83b890a8 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`AiPanel.vue`、`m2t2a-smoke`。
> **稿态**：历史中间稿 —— 该 wire 第 `m17` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt15` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m29`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `83b890a8` / agent `main`（`main` = 主会话）；原字符 3,125（链内 1 段、合计 3,125）。
> **链号 / 类别**：`MT-211` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m29`（6,271 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 1 / 旧仓名与同源仓名 2 处。

---

提交官后台执行中。以下是 T2a 施工的红队+独立复现双卡，可直接并行投放（等提交官推送完成后针对 main 最新 HEAD 执行）：

## 【任务卡 3】T2a 施工红队 → DeepSeek V4 Pro（对抗性找茬）

```
【角色】DeepSeek V4 Pro，ai-example 合流项目红队。对抗性审查，目标是推翻/找茬，不是确认。只审不改，零 git 写操作。

【仓库】<REPO>（分支 main，先 git pull 到最新 HEAD）。

【审查对象】M2-T2a 施工产出（相对 HEAD 之前的基线 a71832d，用 git diff a71832d..HEAD -- frontend/ scripts/ 看全部改动）：
- frontend/src/stores/ai.ts（applyPendingTerminal 广播守卫、localTerminal 补 succeeded/expired、cancelGenerativeForm 乐观置位、409 读响应体收敛、pendingToolCall getter 续扫、openGenerativeForm 终态守卫、isFrameDecidedPendingStatus 含乐观 cancelled、帧到达 filling 时 notice）
- frontend/src/types/ai.ts、frontend/src/types/sse.ts（status 联合扩充 + 五支映射表）
- frontend/src/components/AiPanel/AiPanel.vue（formHostMessageId 宿主门控、终态 tag 链）
- scripts/m2t2a-smoke.mjs（CDP 冒烟三断言）

【规格基准】docs/M2-T2-返修决策卡.md（T2a-D#1~#5 + 末尾勘误节 5 条，勘误是规格的组成部分）；docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md 裁决结论节。

【攻击面提示（不限于此）】
1. 竞态：cancelGenerativeForm 乐观置位与帧到达的交错时序——applyPendingTerminal 覆盖域（pending/乐观 cancelled）是否存在"该覆盖没覆盖/不该覆盖覆盖了"的缝隙；409 收敛与帧收敛双路并发时终态是否单调。
2. 投影一致性：formHostMessageId 门控与 pendingCall 投影的派生关系——消息删除/重置/镜像恢复后门控是否悬空；多 toolCallId 并存时门控是否串扰。
3. 守卫绕过：openGenerativeForm 终态守卫的判定源（跨消息 pendingCall 投影）是否被"先写入新 pending 再判守卫"的入口顺序架空。
4. 映射表完备性：五支映射之外的后端 status 值落到 null 的行为是否安全降级。
5. 冒烟脚本有效性：三断言是否真能从 DOM/store 区分"修复后"与"原缺陷形态"（原形态下断言应 FAIL——请推演或实测验证断言不是永真）；脚本本身有无误报/漏报路径。
6. 类型扩充的读侧兼容：cancelled/succeeded/blocked 进入 PendingToolCall.status 后，所有 switch/判等消费点是否穷举（Grep 所有 pendingCall.status 消费点逐一核对）。

【产出】docs/evidence/M2-T2a-施工红队-DS-V4-Pro.md：问题清单（阻断/严重/建议分级，每条附 文件:行号 证据 + 复现路径 + 修复方向）+ 攻击面无问题清单（附排查方法）+ 总体结论（通过/打回）。保持未提交。关键断言标注【实测】（跑过/读过代码）/【推断】。脱敏：盘符 <REPO_ROOT> 等占位，key 用 ***。
```

## 【任务卡 4】T2a 施工独立复现 → GLM-5.3（复核与测试）

```
【角色】GLM-5.3，ai-example 合流项目复核员。独立复现验证，不信任施工方的任何结论，一切以你亲手跑出的结果为准。零 git 写操作。

【仓库】<REPO>（分支 main，先 git pull 到最新 HEAD）。

【复现对象】K2.8 的 T2a 施工验证声明（docs/evidence/M2-T2a-施工与验证-K2.8.md）：
1. 后端 mvn test：全路径 "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"，在 backend/ 下跑 -B test，声称 249 执行 / 0 失败。请独立复跑并报 Tests run 行原文。
2. 前端：cd frontend && yarn typecheck 与 yarn build，声称双绿。独立复跑报退出码。
3. CDP 冒烟三断言（scripts/m2t2a-smoke.mjs，头部有用法注释）：需要自己起全链路——Memurai 应在 127.0.0.1:6379 运行（若无则报【待裁决】不要自行安装）；后端从 18330 起分配端口（启动前 netstat 查占用；需 AI key：export AI_API_KEY="<REDACTED_API_KEY>"，兼容 DEEPSEEK_API_KEY，base-url https://api.deepseek.com，model deepseek-flash；禁写入文件）；前端 dev server 5202（.env.local 覆盖，代理指向你起的后端端口——注意按实际端口调整）；然后跑 node scripts/m2t2a-smoke.mjs，声称三断言（①刷新后单卡 ②无双份工具卡 ③取消后 pendingCall.status 收敛 cancelled）两轮全 PASS。独立复跑至少一轮，报 result-<时间戳>.json 的断言明细。收尾：停掉你启动的所有进程并 netstat 复查无残留。
4. 冒烟脚本的断言有效性：读 scripts/m2t2a-smoke.mjs，推演三断言在"原缺陷形态"（双 FormRenderer、取消不收敛）下是否会 FAIL——若断言永真则报严重问题。

【产出】docs/evidence/M2-T2a-施工复现-GLM-5.3.md：四项复现的原始输出摘录、与施工文档的对账表（声称 vs 你的实测）、偏差清单、总体结论（复现通过/不通过）。保持未提交。关键断言标注【实测】。脱敏：盘符 <REPO_ROOT>/<MAVEN_HOME> 等占位，key 用 ***。
```

## 当前状态与交接盘点

- 本会话第 5 轮，双阈值均未触发。
- 在轨：提交官（后台，勘误+三提交入库）；等你投放双卡并带回 DS-V4-Pro 红队 + GLM-5.3 复现两份回报。
- 双回报到齐后：我处置问题清单 → （若有修复）复核 → 证据入库 → **T2a 闭环**，随后启动 T2b 施工卡（K2.8，范围=勘误后 T2b-D#1~#3）。
