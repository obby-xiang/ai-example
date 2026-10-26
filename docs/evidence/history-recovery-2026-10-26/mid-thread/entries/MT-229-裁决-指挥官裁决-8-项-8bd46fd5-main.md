<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-229 · 裁决 · 指挥官裁决（8 项）（8bd46fd5 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`workspace.ts`、`ExportWizardView.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m15` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt12` 条有正文的 assistant 消息（共 22 条有正文），末条有正文消息为 `m31`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8bd46fd5` / agent `main`（`main` = 主会话）；原字符 4,116（链内 1 段、合计 4,116）。
> **链号 / 类别**：`MT-229` / **裁决**；判定：助手裁决意见——**不构成用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m31`（757 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

施工报告已通读验收。质量合格：证据链完整（18 JSON+18 PNG 探针、XML 口径基线/完工对比、gate 日志），7 条偏离全部明示理由且站得住，第 1 轮测试失败如实登记且修正正确，s6 镜像诚实标注【旁证】。**复核通过**，以下是 8 项待裁决的裁定 + GLM 红队卡。

## 指挥官裁决（8 项）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | L-1 `selectDefs` 强制回退 currentStep | 维持原裁：不动，不单独立 issue，随提交官批登记排期去向表 |
| 2 | 后端判别式形态 | **维持契约镜像**（`SELECTION_PAGE_IDS` + 镜像锁用例），不加 `selectedDefsSynced` 标记键——不动已锁 6 键白名单的保守口径正确 |
| 3 | 补跑 LLM 桩全链路 E2E | **补跑**。两侧真值虽各有证据，但本批改了 `ContextBuilder`（E2E 链路必经），桩 E2E 28 用例是最便宜的回归背书，纳入提交官批前验证（CI 修复后本地桩 E2E 不受影响） |
| 4 | 窄窗口残留 (a)(b) | 登记观察项，本批不修。(a) 是本次语义放大的真实新面（页面真值优先后，窗口期读到的是**上个任务**的集合而非任务条目），虽需「导航完成前抢发消息」才可达，但登记时注明「新引入面」；(b) 既有形态，合并登记 |
| 5 | 路由 3 不对称（ui_event 不改页面 ref） | 既有行为维持不修，登记观察项（AI 勾选界面不显示的相邻缺口），不单独立 issue |
| 6 | 导入/导出文案统一 | 不统一，登记（低价值） |
| 7 | 顺序不敏感的纯重排静默 | 接受（裁决⑤已知代价），登记一句话 |
| 8 | 前端测试框架缺口 | 维持挂业务方决策项（已在队列），不引依赖 |

## GLM-5.3 红队卡（请转交）

```
你是 ai-example 合流项目 issue#3 修复的红队复核官（GLM-5.3）。任务：对刚完成、尚未提交的一批改动做攻击性复核，裁决「放行 / 修正后放行 / 打回」。

═══ 子智能体分工条款 ═══
你可派自己的子智能体（deepseek-v4-flash）执行机械活：全量扫描、批量 grep、大文件通读、测试实跑、探针 JSON 逐字段 diff、日志翻找。你本体只做关键判断、攻击面分析、结论裁决与报告撰写。机械活清单见 §D。

═══ 背景 ═══
issue#3：用户手动勾选配置定义 AI 无感知（watch(selectedDefs) 只更 draftModels 不同步 workspace）。修复三面缺口：
① ExportWizardView watch 不同步；② 向导已挂载时 AI 走 page-handler 分支同样不同步；③ 后端 ContextBuilder 权威行取任务 items 而非页面真值（减选场景对立）。
设计：页面级唯一收敛点 syncSelectedDefsToWorkspace（顺序不敏感 Set 比较、相等短路、幂等、恢复不二次 touch、不调 tasksApi.selectDefs）；store setSelectedDefs 加可选第三参 source；Import 侧同构修复；后端「页面已同步（含空集合）⇒ 页面真值，否则退回任务条目」，null=从未同步 / 空列表=同步了空集合两态区分。

═══ 材料 ═══
- 主仓库：<REPO>（分支 main，HEAD=56883f6；工作区 5 个未提交文件即本批全部改动，git diff 可见）
- 施工报告（必读）：<LOCAL_WORK>\issue3-reports\issue3-施工报告.md（241 行，含改动清单/验证证据/7 条被迫偏离/8 项裁决/断言标注）
- 只读阶段报告：<LOCAL_WORK>\issue3-reports\issue3-修复报告.md
- 探针证据：<LOCAL_WORK>\issue3-reports\evidence\（18 JSON+18 PNG+gate/mvn 日志+探针源码存档）
- 参考：issue#2 红队确认报告 <LOCAL_WORK>\issue2-reports\红队回灌确认-GLM-5.3.md（你上一轮的稽核范式可沿用）

改动清单（git diff --stat 应与此吻合）：
- frontend/src/stores/workspace.ts +9/-2（setSelectedDefs 可选 source 参）
- frontend/src/views/ExportWizardView.vue +35/-2（收敛点 257-264、watch 1029-1035、handler 884-895、persistStepData 511-515）
- frontend/src/views/ImportWizardView.vue +27/-2（同构：368-375、1011-1015、948-955、489-491）
- backend/.../ai/tool/ContextBuilder.java +47/-3（SELECTION_PAGE_IDS 44-49、buildContextMessage 74-90、pageSelectedDefs 103-127）
- 新增 backend/src/test/java/.../ContextBuilderSelectedDefsTest.java（153 行 8 例）

═══ §K 关键判断点清单（你本体负责）═══
K1 相等短路正确性：Set 顺序不敏感比较——空集 vs 从未同步、大小写、codes 来源数组是否可能含重复/脏值导致假不等（重复 touch 风暴）？
K2 恢复路径幂等：onMounted enterPage（不带 selectedDefs）→ loadTask 赋 ref+enterPage（带 selectedDefs）→ watch(flush:'pre') 时序——是否真能保证恢复不二次 touch？构造反例。
K3 窄窗口攻击：onMounted→loadTask 返回前，ws.selectedDefs 残留上个任务集合，后端按 pageId 当页面真值——施工报告自列为观察项（待裁决 4a），你判定该窗口的可达性与危害定级（S2/S3），是否必须本批修。
K4 后端两态区分实现正确性：pageSelectedDefs 的 null/空列表判别是否可能被「客户端带了 selectedDefs 键但值为 null/非法」击穿？SELECTION_PAGE_IDS 与前端 enterPage 调用点逐页对应是否完备（grep 全部 enterPage 调用点核对）？
K5 来源标注注入面：上下文行新增「（来源：页面实时勾选）/（来源：任务条目）」——是否破坏既有解析/测试断言/模型行为？与 complete 语义外溢等既有观察项有无交互？
K6 persistStepData 换收敛点后：落库行为是否变化（原来裸 setSelectedDefs 现在走短路，会不会该落库时没落库）？s3「newCalls=[]」断言是否覆盖该面？
K7 与 issue#1/#2 修复的交互：inFlightBinding/ensureTaskBoundForTool/resetTaskScopedState（不清 selectedDefs）与本批 watch/handler 改动的交错场景。
K8 探针证据可信度：before 相「git archive 快照+junction node_modules」法是否可信；抽查 2-3 个场景 JSON 原件核对施工报告宣称值；s6 镜像【旁证】定性是否恰当。
K9 diff 忠实性与夹带：工作区 diff 与报告宣称 +118/-9+153 是否一致、有无夹带。
K10 命名纪律与脱敏：diff 与报告全文 main+分隔符+v2 组合、盘符、key 扫描。

═══ §M 机械活清单（派子智能体）═══
M1 git diff 全量导出+统计核对（K9）；M2 enterPage/SELECTION_PAGE_IDS 全仓 grep（K4）；M3 yarn typecheck+build 复跑、后端 mvn 全量复跑 XML 求和核对 354/0/0/0；M4 探针 JSON 抽查比对（K8）；M5 命名纪律/脱敏三模式扫描（K10）；M6 既有 ContextBuilderExtraTest 等相邻测试未被改动核验。

═══ 输出 ═══
报告落盘：<LOCAL_WORK>\issue3-reports\红队确认-GLM-5.3.md。结构：§0 裁决（放行/修正后放行/打回）；K1-K10 逐项结论（发现编号 R1…，定级 S1 阻断/S2 放行前必修/S3 登记跟进/S4 观察）；§M 复跑证据摘要。断言标注【实测】/【推断】/【假设】/【旁证】。报告禁止盘符绝对路径（<MAIN_REPO>/<SCRATCH-REPORTS> 占位）、禁止 main 加分隔符再接 v2 任意大小写组合。完成后只需回复报告路径。
```

## 当前并行状态

- **CI 修复棒**仍在后台（`78aa192`+`56883f6` 已推，正在轮询 CI run 核验）——通知到后我验收 CI 是否转绿。
- 红队报告落盘后你回报「已完成」，我即派提交官（三笔细分 A/B/C + 桩 E2E 补跑 + push + **push 后 CI 核验**新纪律）→ webbridge 评论/关闭/删除/存档。
