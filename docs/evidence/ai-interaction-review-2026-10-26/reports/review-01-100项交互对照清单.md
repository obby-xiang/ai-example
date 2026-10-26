> 来源=agent-0oili5lx/output.log（2026-10-26 归档）· 全面代码审查（A–J 十类 100 项用户可触达面清单与口径冲突）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

## 覆盖范围与读法

- 只读核对：`backend/.../ai` 全包 **200 个文件**（config/context/exec/feedback/form/gate/history/kb/memory/observe/resultview/run/session/tool/tools/web）、前端 AI 相关 **34 个文件**（AiPanel 及其子件、stores、api、types、utils、views、constants）、现行规格（`technical-design.md` §2.5、`02-需求设计文档-v2.2-冻结版.md` §FR-5、`s4/S4.2-AI-Runtime-合流规格.md`、`application.yml` AI 段）与决策/任务看板。
- HEAD `61de820`（2026-10-26）。未读 `.env`/凭证，未起停服务、未跑测试/构建、未发请求。
- 状态图例：**现行**＝代码与规格一致；**退役**＝历史遗留仅兼容；**无 UI**＝后端接口在、前端无消费；**文档未施工**＝规格/设计有、代码无。

## 0. 分类总览

| 类别 | 项数 | 现行 | 退役/缺项 | 文档未施工·无 UI |
|---|---|---|---|---|
| A 消息发送/编辑/重试/取消 | 10 | 8 | 2（无 regenerate、无显式删除） | — |
| B 流式与 reasoning | 9 | 7 | 1（旧 reasoning 帧） | 1（截断提示为局部） |
| C 工具/确认/生成式表单/前端工具 | 15 | 14 | — | 1（chart/form 渲染 fallback） |
| D 业务工作区/任务向导 | 8 | 8 | — | — |
| E 会话/页签/历史/删除 | 9 | 7 | 1（无会话列表） | 1（完整历史 UI、口径冲突） |
| F 上下文计量与压缩 | 8 | 6 | — | 2（提示条、手动 pin） |
| G 断线/恢复/多实例 | 10 | 10 | — | — |
| H 错误/降级/可用性 | 9 | 9 | — | — |
| I 设置/模型/知识库 | 10 | 8 | 1（模型选择器退场） | 1（KB 无 UI） |
| J 反馈/复制/下载/无障碍/性能 | 12 | 11 | 1（列表未虚拟化=不做） | — |

## A. 消息发送 / 编辑 / 重试 / 取消

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| A1 | 输入后回车/点发送 → 立即上屏、流式回答 | 乐观用户气泡+助手空气泡；空/纯空白静默；Enter 发送、Shift+Enter 换行、IME 组合期不误发 | `AiPanel.vue:924,1779,3330`、`stores/ai.ts:2558` | 现行 | 10-22 G6 |
| A2 | 超长输入 → 明确告知而非截断 | 计数三档（常规/余量/到顶）+ 屏读播报；`maxlength=8192`；后端 400 `MESSAGE_TOO_LONG` 不截断 | `AiPanel.vue:1229,1599-1643`、`application.yml:375-382` | 现行 | 安全批① |
| A3 | 流式中再发 → 有反馈 | 不响应、零提示、草稿保留（须先停止） | `ai.ts:2563`、`AiPanel.vue:908` | 现行 | 10-22 G6 |
| A4 | 编辑自己的消息 → 改完从该条重跑 | 回填输入框+覆盖确认；发送时截断其后消息、原地改写该条、`skipUserRecord` 重跑 | `AiPanel.vue:3190,3330-3360` | 现行 | R-157/R-163 |
| A5 | 发送失败 → 草稿不丢 | 原稿回输入框并聚焦、锚到本轮用户消息；409 同时回收乐观气泡（不双份） | `AiPanel.vue:3370-3385`、`ai.ts:2688-2700` | 现行 | R-04/G6 |
| A6 | 断流/报错/取消后 → 一键续 | 末条助手消息出「继续」，发送**当前语言**的“继续”；被用户停止的轮不出 | `AiPanel.vue:756,3219` | 现行 | 10-23 A3-Y8 |
| A7 | 点“重试/重新生成” → 重跑该条 | **无消息级 regenerate**；只有错误卡「稍后重试」= 再发一轮；断流自动重连另计 | `AiPanel.vue:1421-1435`、`api/sse.ts:39` | 缺项 | — |
| A8 | 点停止 → 立即停、状态明确 | 本地先收敛（段 cancelled/decision=cancel/已停止标记/清撤销/撤预览/收交接），再 `POST /cancel` 幂等；`alreadyCancelled` 不重复提示 | `ai.ts:4632-4740`、`AiController.java:888` | 现行 | 10-26 取消终态 |
| A9 | 复制/赞踩/展开长消息 | 复制正文纯文本（含 execCommand 回退）；赞踩本地互斥+失败回退；>184px 折叠 | `AiPanel.vue:3428,1446-1493,3270-3310` | 现行 | 10-23 L-80 |
| A10 | 空态/建议 → 快速起问 | 空态 chips 点击**直接发送**；每轮完成出规则化建议条 | `AiPanel.vue:169-190,1517,1534,859` | 现行 | 10-22 E2 |

## B. 流式与 reasoning

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| B1 | 看到逐字输出 | 真流式 `delta` + 光标/呼吸点；三段式 `message_start→delta*→message_end`，带 `messageId`/`kind` | `types/sse.ts:209-241`、`AiPanel.vue:274,1844` | 现行 | 10-22 三段式 |
| B2 | 看到思考过程但不干扰正文 | `delta{kind:'reasoning'}` + 边界 `kind`；独立折叠块、默认收起、展开态存 store；无思考不显示空块 | `AiPanel.vue:287-321,5617`、`types/sse.ts:222` | 现行 | 10-23 L-91 |
| B3 | 旧会话/旧桩回放 | 仍承接 `type:'reasoning'` 帧（前向兼容，不再新增发射点） | `types/sse.ts:156,435,576` | 退役 | 10-23 |
| B4 | 乱序/迟到帧不串轮 | `seq` 单调、异轮 runId 帧整帧丢弃、终帧后到达帧一条不落地 | `api/sse.ts:254-309` | 现行 | 10-22 S-12 |
| B5 | 长时间无正文仍存活 | 流式期/挂起期心跳保活；不显示倒计时（确认门已无超时） | `api/sse.ts:401`、`types/sse.ts:401-420` | 现行 | 10-20 去超时 |
| B6 | 上游抖动自动重试 | `retry` 帧 → 通知槽“N 秒后重试(第 k 次)”；后端按三条件决定是否重试 | `ai.ts:2627`、`types/sse.ts:443` | 现行 | 韧性棒 |
| B7 | 回答被长度上限截断 | `done.truncated` → “回答不完整”提示、不重试；单帧超预算 → 截断徽标 | `AiPanel.vue:744`、`types/sse.ts:160-199`、`FrameSizeLimiter` | 现行 | A10 R-15 |
| B8 | 富文本/代码/图表 | markdown-it→DOMPurify→v-html 唯一链路；hljs 15 语言；未闭合围栏纯文本；mermaid strict + 可见才渲染 | `utils/markdown.ts:1-30,539` | 现行 | 10-24 渲染专项 |
| B9 | 全屏看 mermaid | 缩放/拖拽/复制源码/下载 SVG/Esc 关闭/`role=dialog aria-modal` | `AiPanel.vue:978-1050,2438-2514` | 现行 | R-165 S5b |

## C. 工具 / 确认 / 生成式表单 / 前端工具

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| C1 | 模型只看到当前场景该用的工具 | `@ToolScope` 标签过滤 + 提示词声明 + 执行兜底；`ui_*` 按区域存在性披露 | `tool/ToolRegistry.java`、`tools/AiTools.java` | 现行 | 10-23 批A |
| C2 | 工具结果可读、可判成败 | 统一信封 `outcome` 八态优先于 `ok`（ok/failed/rejected/blocked/timeout/cancelled/unknown/skipped） | `types/sse.ts:24-34`、`tool/ToolResultEnvelope.java` | 现行 | 治理 P0-1 |
| C3 | 高风险动作先问再执行 | 4 个 DANGER 工具（导出/预检/导入/发布）挂起弹卡；拒绝结果回填模型继续 | `AiTools.java:341-397`、`ConfirmGate.java:1291` | 现行 | — |
| C4 | 批准前知道“能不能做” | 卡上预检区（目标/检查项/影响面/reportHash）；预检失败后端**不弹卡** | `types/sse.ts:66-94`、`gate/ToolPreflightService.java` | 现行 | F5/H1 |
| C5 | 不可撤销动作防误点 | 仅 `start_publish` 叠复选框，未勾选禁用「批准」、拒绝恒可用 | `AiPanel.vue:554-580`、`utils/confirm-irreversible.ts:81` | 现行 | 10-23 批4-D |
| C6 | 卡可以慢慢想，不要超时消失 | 确认门/GF 无等待上限；请求帧后服务端收流；刷新可重建；park 不占线程 | `gate/ConfirmGate.java`、`api/sse.ts:143,539`、`application.yml:250-267` | 现行 | 10-20 + 10-25 |
| C7 | 重复点击/多标签批准 | 409 幂等 →「已提交，同步中…」；`runs/current` 权威对账，待决卡串行补出 | `AiPanel.vue:582`、`ai.ts:2988-3210` | 现行 | 10-23 R-166 |
| C8 | 前端工具不无限吊住 | 前端预算=服务端上限−60s（最低 5s），到期回灌“结局未知” | `utils/frontend-tools.ts:308-353` | 现行 | 超时治理② |
| C9 | 工具不可用时给原因 | available/degraded/unavailable 三级说明；页面就绪探测 3s；同轮同参去重 | `frontend-tools.ts:509-626,757` | 现行 | — |
| C10 | 让 AI 收集条件 | 生成式表单：七型白名单、≤20 字段/≤50 选项、联动闭集、禁模型正则；草稿只进 sessionStorage；附件 5MB；选项级联 | `GenerativeFormRules.java:15-60`、`FormRenderer.vue`、`utils/form-draft.ts` | 现行 | GF 优化批 |
| C11 | AI 改表前先看影响 | 写预览三档（计数/采样 diff/全量 diff）、大改需勾选、同文件覆盖提示、撤销 3 层 | `SheetPreviewCard.vue:1-45,113`、`ai.ts:3656-4430` | 现行 | 10-25 覆盖提示 |
| C12 | AI 要文件时由我亲手选 | 上传交接卡三态（waiting/degraded/missing），放弃=`FRONTEND_CANCELLED` | `UploadHandoffCard.vue:1-40`、`ai.ts:3764` | 现行 | — |
| C13 | 让 AI 直接点页面 | 7 原语 `ui_snapshot/click/fill/select/scroll/highlight/request_upload`；区域+元素双层围栏、epoch 失效、风险档、撤销点、播报 | `utils/ai-regions.ts:136-228`、`ai-dom-tools.ts`、`tool/UiPrimitiveGuard.java` | 现行 | 10-21/23 |
| C14 | 工具卡有结构化结果 | `resultView`：`table`/`text` 渲染；`chart`/`form` 契约有、渲染走 fallback；历史按工具名重派生 | `resultview/registry.ts`、`resultview/ResultViews.java` | 现行（部分未施工） | S6 批2 |
| C15 | 大结果不炸上下文/不炸帧 | 模型侧 20000/8000 字符、200 行；UI 单帧 64KB；回灌 8000；L1 清除留占位 | `application.yml:347-374`、`tool/ToolResultLimiter.java` | 现行 | G5 双通道 |

## D. 业务工作区 / 任务向导

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| D1 | AI 知道我在哪一页/哪一步 | 每次路由变化上报 `page/taskId/taskType/step/extra`（严格 5 键，taskType 与 step 成对） | `stores/workspace.ts:283`、`technical-design.md:293-299` | 现行 | — |
| D2 | 说“去导出向导选某个配置” | 工作区动作表 navigate/select/confirm_step/set_condition + 5 个别名，落契约层同一 action | `frontend-tools.ts:852-960` | 现行 | 补丁② |
| D3 | AI 操作与手点等价 | 页面反向注册能力 handler（导出 7 / 导入 9，含 `sheet.current`） | `ExportWizardView.vue:1596-1759`、`ImportWizardView.vue:1703-1890` | 现行 | — |
| D4 | AI 动作与我操作互相同步 | `dataVersion` 自增、`recentActions` 注入下一轮（带来源 UI/AI）、页脚显示契约/上下文/版本 | `workspace.ts:249-316`、`App.vue:47` | 现行 | — |
| D5 | AI 推进步骤但不跳过校验 | `confirm_step` 由前端校验通过才前进；`set_condition`/`select_definitions` 后端 fail-closed 复核（畸形/未知目标/越界直接拒） | `tool/WizardActionGuard.java` | 现行 | 10-25 |
| D6 | 作业进度实时 | 任务级 SSE `JOB_PROGRESS/JOB_DONE`，向导认领、job-watch 轮询兜底；`TASK_CHANGED` 链已摘除 | `utils/job-watch.ts`、`technical-design.md:184-188` | 现行 | S5 摘除 |
| D7 | 发布冲突要看见 | `tool_result` 后挂发布冲突 watch，冲突明细表卡 | `ai.ts:5630-5751`、`AiPanel.vue:486-520` | 现行 | S6 批 |
| D8 | 我未保存的编辑不被 AI 覆盖 | 快照守卫 + 外部变更同步本地表单 | `technical-design.md:374-394` | 现行 | — |

## E. 会话 / 页签 / 历史 / 删除

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| E1 | 新页签=新对话 | sid 存 sessionStorage，新页签新会话无历史 | `ai.ts:689`、`technical-design.md:392` | 现行 | — |
| E2 | 刷新后对话还在 | 先回放本地镜像（含在途标记/帧号）→ `/runs/current` → 在途轮**全量重放**静默续写 | `ai.ts:1712,2224` | 现行 | 批G G2 |
| E3 | 挂起中刷新，卡还在 | 串行重建待决卡、`lastSeq` 增量续收、无卡也挂流等请求帧 | `ai.ts:2127-2440` | 现行 | I-1 收口 |
| E4 | 复制页签不污染原会话 | Web Locks 探活判复制品 → 换新 sid + 清继承 sessionStorage，不读原历史 | `ai.ts:270-700` | 现行 | RC-4 |
| E5 | 关页签/慢重载 | `pagehide` 让渡广播 + 1500ms 宽限 + 返回意向；慢重载原页签优先，副本降级 duplicate | `ai.ts:613-700` | 现行 | RC-4③ |
| E6 | 新建对话 → 干净 | 清屏换 id、取消旧轮、purge 旧会话（含记忆/归档/DB/附件）、不完整时提示；无二次确认 | `AiPanel.vue:3459`、`ai.ts:5063` | 现行 | 10-25 销毁口径 |
| E7 | 隔天回来 | TTL 24h 滑动、活跃即续；过期/重启即新会话；410 `SESSION_PURGED` 本地换 id 不自动重发 | `application.yml:144`、`ai.ts:2674` | 现行 | 10-20 |
| E8 | 翻看全部历史 | 前端只读 `/history/{sid}`（记忆窗口 40 条）；`/history/{sid}/messages` 游标分页**无前端消费者**；`ai_chat_message` 永久落库 | `AiController.java:1284,1317`、`AiChatHistoryService.java:35-40` | 无 UI + 口径冲突 | 见 §口径冲突 |
| E9 | 删除某个会话/某条消息 | 只有“新建对话”隐式整会话 purge；**无会话列表、无单条删除** | `AiSessionPurgeController.java:78` | 缺项 | — |

## F. 上下文计量与压缩

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| F1 | 看当前上下文占用 | 输入框底栏 pill：`65.8% · 84.2k/128k`、四档配色、估算 `≈`、上限未知只显已用；**流式中不跳** | `ContextPill.vue:1-30`、`utils/context-usage.ts` | 现行 | 10-22 批① |
| F2 | 看构成 | 点开拉端点，七项固定顺序 + “分项为估算、总量为账单”，free 可为负标红 | `ContextPopover.vue:1-20`、`ai.ts:1685` | 现行 | 10-22 批① |
| F3 | 刷新后仍准 | `GET /context/{sid}`（`?ledger=1` 附台账），Redis 不可用 503 | `AiController.java:1590` | 现行 | 批① |
| F4 | 看到“发生了压缩” | 后端发 `context` 帧（16 类中唯一新增）；前端**只存不渲染**、不刷 pill | `ai.ts:5421-5430` | 现行（渲染未施工） | 批① |
| F5 | 长对话不炸窗 | L1 清工具结果 → L2 按 Turn 摘要 → L3 硬裁；每次最多升一级；熔断 3 次/10m 半开 | `context/ContextCompactor.java:203`、`RollingSummarizer`、`application.yml:162-191` | 现行 | 批③ |
| F6 | 口径自洽 | 展示用真值 `usedNow`、触发用 `nextEst`；70/80/95 对 `limit−reserve`；滞回 60%；分母优先级 `max_context > limit-tokens > 0(未知)` | `ContextMeter`、`ContextLimitResolver.java:72`、`technical-design.md:300-372` | 现行 | 批① |
| F7 | 手动 pin 某段/压缩提示条 | 未实现（D6 不做；提示条排批④） | `technical-design.md:367-368` | 文档未施工 | — |
| F8 | 记忆窗口与展示一致 | 40 条 + 48k 预算 + min 40；合成摘要可能以“用户消息”形态回显且不进完整历史 | `application.yml:147-161`、`docs/evidence/.../D-model-history.md:250` | 现行（已知不一致） | 批③ |

## G. 断线 / 恢复 / 多实例

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| G1 | 网络抖动自动恢复 | 帧间静默 45s 判断、建连 15s、最多重挂 4 次（1/2/4/8s ±25% 抖动） | `api/sse.ts:31-73` | 现行 | 超时治理② |
| G2 | 重连不重复不丢 | `?lastSeq` 只补增量；终帧即收；重连耗尽才提示断流 | `api/sse.ts:81,583` | 现行 | DC-14 T6 |
| G3 | 挂起时不占连接 | 请求帧后服务端主动收流，前端不自动重连；决策后带 `lastSeq` 显式重连 | `api/sse.ts:143,539`、`ai.ts:2440` | 现行 | 10-20 B1 |
| G4 | 上游断流有终局 | 缺终帧=断流；仅在“无产出且无副作用”重试；错误码带 `_AFTER_PARTIAL/_AFTER_TOOL_SIDE_EFFECT` | `ResilientChatService`、`UpstreamErrorClassifier`、`ErrorCodes.java:336-360` | 现行 | 韧性棒 |
| G5 | 一轮不会无限跑 | first-byte 30s / inter-event 90s / 段预算 300s / 工具 120s / 步数 25（跨挂起累计） | `application.yml:213-231` | 现行 | 10-21/10-25 |
| G6 | 批准后一定执行且只执行一次 | 决策/回灌触发 `ResumeService`，租约+fence+台账 `executedBy` 恰好一次 | `ResumeService.java:518`、`LeaseService`、`RunStore` | 现行 | 10-25/26 |
| G7 | 进程重启能续 | 启动 8s 后扫描、僵尸 180s（业务活动锚点）、最多 10 条；只续已有人类结论的轮 | `StartupResumeRunner.java:104`、`application.yml:232-249` | 现行 | 10-24 |
| G8 | 等人不占线程 | park：请求帧+收流后交还租约，决策到达再重建执行；重派发 15 次指数退避 | `application.yml:250-267`、`RunParkedException` | 现行 | 10-25 |
| G9 | 多实例可跨进程唤醒 | run 级 `PUBLISH ai:wake:<runId>`，不做帧扇出；唤醒可丢由 `lastSeq` 兜底；帧归档 64MiB 预算 | `run/RedisFrameWakeBus.java:17-31`、`application.yml:330-346` | 现行 | 10-25 |
| G10 | 同会话不并发跑两轮 | SessionGate Redis 锁 + watchdog 续期 + 降级进程内门；`/health` 暴露 heldSessions/lockLost | `session/SessionGate.java`、`AiController.java:1618+` | 现行 | 10-21/25 |

## H. 错误 / 降级 / 可用性

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| H1 | 看 AI 是否可用 | 顶部 chip（未检测/可用/降级）+「重新检测」；`/health` 现算 Redis/租约/锁/投递池 | `AiPanel.vue:43-49,1559`、`AiController.java:1618` | 现行 | 10-21 |
| H2 | 没配 key/没模型 | 四档机器码：`AI_UNAVAILABLE`/`AI_KEY_MISSING`/`AI_KEY_UNREADABLE`/`NO_MODEL_CONFIGURED`，各带专属文案与错误卡 | `config/AiAvailability.java:1-20`、`utils/error-card.ts:70-100` | 现行 | R-12 |
| H3 | 挂起并发满 | 503 `SUSPEND_POOL_SATURATED` + 专用横幅（含 poolSize） | `AiPanel.vue:58`、`ai.ts:2790` | 现行 | — |
| H4 | Redis 挂了 | 503 `AI_REDIS_UNAVAILABLE` + 提示；限流/取消/帧读各自降级并计数 | `ErrorCodes.java:79`、`observe/RedisDegradation`、`application.yml:337-346` | 现行 | 10-24 |
| H5 | 发太快/太长 | 429 `RATE_LIMITED`（会话 20/分、IP 60/分，Redis 不可用降级放行）；400 `MESSAGE_TOO_LONG` | `gate/AiRateLimiter.java`、`application.yml:383-391` | 现行 | 安全批① |
| H6 | 出错要看得懂、能行动 | 内联错误卡：429 红 / 内容过滤灰 / 上下文蓝 / 通用红 + 14 个专属码文案 + 动作（重试/改写/开新会话） | `AiPanel.vue:822-860`、`utils/error-card.ts:1-120` | 现行 | R-165 S5c |
| H7 | 断流要明确提示 | `alert-interrupted` + 通知槽；用户停止不算断流；挂起收流不算断流 | `AiPanel.vue:77`、`ai.ts:2640-2660` | 现行 | 批G |
| H8 | 空回复/取消 | `EMPTY_REPLY`、`AI_RUN_CANCELLED` 专属卡；取消轮 UI 落“已停止” | `ErrorCodes.java:284-286` | 现行 | 10-26 |
| H9 | 不可用时输入框该禁用 | 硬禁用只看“真实不可用/挂起/待决”；流式中仍可编辑 | `AiPanel.vue:1580` | 现行 | 10-22 |

## I. 设置 / 模型 / 知识库

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| I1 | 管理多个 OpenAI 兼容端点 | 设置页 provider 卡片：增/改/软删/启停，来源 ENV_SEED/DB | `AiSettingsView.vue:592-707`、`AiConfigController.java:75-137` | 现行 | 模型配置批② |
| I2 | 设置/清除 key | 只写不回显；last4 掩码；四态（已配/缺/不可读/停用）；KEK 缺失 fail-closed | `AiSettingsView.vue:613-659`、`AiKeyCipher` | 现行 | 批①/② |
| I3 | 点“测试连接” | 5s 超时探活，返回可达/模型/延迟 | `AiConfigController.java:137`、`AiConnectionProbe` | 现行 | 批② |
| I4 | 拉取远端模型清单 | `/models/fetch`（Redis 缓存 10m）+ 手工添加 + 能力位（tools/vision/reasoning）+ maxContext/maxOutput | `AiSettingsView.vue:709-780`、`application.yml:142` | 现行 | 批② |
| I5 | 设全局默认模型与采样参数 | 默认目标（provider+model 成对）、temperature/topP/maxTokens、`allowUserParams` | `AiSettingsView.vue:782-830`、`AiConfigController.java:201` | 现行 | 批③ |
| I6 | 防止被当成内网跳板 | URL 闸：仅 http/https、禁内嵌凭据、默认禁私网/回环/元数据；种子行例外 | `AiEndpointGuard`、`application.yml:136-139` | 现行 | 批② |
| I7 | 配置变更可追 | 每次变更写 `ai_config_audit`（同事务，key 只记 last4） | `config/db/AiConfigAudit` | 现行 | 批② |
| I8 | 在对话里选模型/调参 | **面板选择器与参数 popover 已整体退场**；请求不带 modelId/params；`/api/ai/models`、`/health.allowedParams` 仍在但无面板消费者 | `AiPanel.vue:934,3618`、`AiConfigController.java:93` | 退役（能力保留） | 10-22 F1 |
| I9 | 问制度/口径类问题 | KB 三工具 `kb_search/kb_read_doc/kb_lookup_term`；BM25+术语表、<knowledge> 定界、Redis 缓存、10m 增量 | `kb/KbTools.java:122,184,313`、`application.yml:289-329` | 现行 | 知识问答 v1 |
| I10 | 运维查看/重载知识库 | `/api/ai/kb/status\|reload\|docs` 仅在，**无前端页面**；文档发布走 `知识库维护手册` | `kb/AiKbController.java:38-51` | 无 UI | 10-25 |

## J. 反馈 / 复制 / 下载 / 无障碍 / 性能

| # | 用户动作 → 预期 | 系统实际交互 | 关键证据 | 状态 | 最近规格变化 |
|---|---|---|---|---|---|
| J1 | 赞/踩 → 记上 | 本地互斥可取消；失败**回退选中态**+提示；excerpt=正文前 200 字；入库前 `ExcerptGuard` 命中凭据整条替换 | `AiPanel.vue:3270-3310`、`ai.ts:4767`、`feedback/ExcerptGuard.java` | 现行 | 10-23 L-80 / 10-24 L-99 |
| J2 | 查看反馈记录 | 设置页“AI 反馈”只读表（时间/摘要/赞踩/reason） | `AiFeedbackView.vue:1-82` | 现行（无 reason 入口） | R-163 T5 |
| J3 | 复制消息/代码 | 消息复制（含非安全上下文回退）；代码块复制；绿勾 2s | `AiPanel.vue:3428,2021-2058` | 现行 | R-165 |
| J4 | 下载导出文件 | `download_export_files`：1 码直出 xlsx、多码 zip；下载前置校验 30s 超时 | `AiTools.java:433`、`frontend-tools.ts:509-523` | 现行 | L-82 |
| J5 | 屏幕阅读器可用 | `role="log"`、live region 播报操作/计数档、`aria-expanded/label/required/invalid/describedby` | `AiPanel.vue:139,148,972`、`FormRenderer.vue` | 现行 | M5 |
| J6 | 键盘可达 | Enter/Shift+Enter、浮层 Esc/点外关闭、对话框 Esc、focus-visible 环 | `AiPanel.vue:12,1762,2514` | 现行 | R-165 |
| J7 | 减少动效 | `motion-reduce` 关光标/呼吸/骨架/工具提示动画 | `AiPanel.vue:1401,3137` | 现行 | — |
| J8 | 流式渲染不卡 | Markdown 前缀缓存（18.65→1.15ms/帧）、hljs 白名单瘦身、mermaid 可见才渲染 | `utils/markdown.ts:1-30` | 现行 | 10-24 |
| J9 | 滚动跟手 | 镜像 300ms 节流、RO 观测内容层、上滑暂停跟随+回到底部滞回 | `ai.ts:217`、`AiPanel.vue:1300-1350` | 现行 | R-161/165 |
| J10 | 超长会话 | **未做列表虚拟化**（决策：先量化 >80 条再立项，挂账 L-122） | `docs/决策看板.md:45` | 不做（待量化） | 10-24 |
| J11 | 超长内容不破版 | 用户长消息 184px 折叠、代码/思考/表格限高内滚、双区横滚联动 | `AiPanel.vue:1446,2618` | 现行 | 2A 批 |
| J12 | 界面语言 | zh-CN/en-US 双份静态 locale；错误/状态文案走 key，不回显后端中文；回答语言随 `Accept-Language` | `locales/zh-CN.json`、`sse.ts:468-479`、`run/ReplyLanguage.java:31` | 现行 | 10-24 4B |

## 跨类：历史遗留 / 只文档未施工 / 接口在无 UI

| 项 | 类型 | 证据 |
|---|---|---|
| 确认门 240s 超时、前端工具分场景 120s/60s 折算 | 退役（2026-10-20 去超时） | `application.yml:192-202`、`technical-design.md:261` |
| `type:'reasoning'` 独立帧 | 退役（仅兼容旧桩/归档） | `types/sse.ts:422-440` |
| 输入/输出 DLP 三件套（InputDlp/OutputGuard/SafetyRules） | 已整删（10-24 L-99），仅留反馈摘录守卫 | `ExcerptGuard.java:9-30` |
| 模型选择器 + 参数 popover + `stores/ai-model.ts` | 已退场（10-22 F1），后端能力保留 | `AiPanel.vue:934` |
| `TASK_CHANGED` 事件链 | 已摘除（S5） | `technical-design.md:184-188` |
| 上下文压缩提示条 | 文档未施工（排批④） | `technical-design.md:367`、`ai.ts:5421` |
| 用户手动 pin 上下文 | 明确不做（D6） | `technical-design.md:368` |
| 完整历史翻页 UI | 接口在、无前端消费者 | `AiController.java:1317` |
| 知识库状态/重载/目录 UI | 接口在、无前端 | `AiKbController.java:38-51` |
| resultView `chart`/`form` 渲染 | 契约有、渲染 fallback | `resultview/registry.ts:1-20` |
| 发布审批流（FR-3.6） | 只文档/接口预留，M1 不含 | `02-需求设计文档-v2.2-冻结版.md:91,104` |
| 消息列表虚拟化 | 决策不做（先量化 L-122） | `docs/决策看板.md:45` |
| AI 报表/成本页、OTel/metrics | 明确不做（`/health` + 日志面替代） | `docs/任务看板.md:144` |
| 多用户/权限上下文 | 明确不做（DC-10） | `02-…v2.2-冻结版.md:31-38` |

## 口径冲突与无法确认项

1. **“AI 对话数据不落库” vs `ai_chat_message` 永久表**：`technical-design.md:277` 与决策看板 🏛️ 行声明状态只在 Redis；但 `V8/V9` 建表、`AiChatHistoryService.java:35-40` 明确“本表不设 TTL，删会话才物理删除”，`A-inventory.md:409` 亦记“永久”。我在决策看板未检索到把“完整历史落库”入账的裁决行，**无法确认这是“记忆不落库、展示历史落库”的分层口径，还是规格漂移**。同时 `AiChatHistoryService` 注释写“V7 + V8”，实际迁移文件是 `V8/V9`。
2. **完整历史通道两端不一致**：后端有游标分页 `/history/{sid}/messages`，前端 `api/ai.ts` 无对应函数、无 UI；用户可见历史仍只有 40 条记忆窗口。
3. **模型能力面残留**：`/api/ai/models`、`/health.allowedParams/defaultParams`、`/chat` 的 `modelId/params` 仍在，但面板入口已退场；`paramsLocked` 只在 done 帧透出。是否为“外部脚本/运维在用”未核（`scripts/` 未逐一读）。
4. **FR-6.1 无主体操作流水（含“来源=界面/AI”）**：前端有 `recentActions.source` 与 `ui_event_result` 来源标记；后端 `TaskService`/`DefinitionService` 有 audit 类痕迹，但**我未展开核对**“界面与 AI 同源记录、无操作人/IP”的落库实现，属未确认项。
5. **记忆摘要回显**：压缩摘要以合成 `UserMessage` 存在，前端刷新后可能显示“用户说过”的六段摘要（`D-model-history.md:250` 已登记），与流式时观感不一致；是否为预期口径未见裁决。
6. **深度边界**：`RunStore`(2822)、`SseChatEmitter`(2206)、`SpToolCallingManager`(1940)、`ConfirmGate`(1937)、`sheet-tools.ts`(2918)、`AiPanel.vue`(3700)、`FormRenderer`(795) 按类级契约+关键分支读取，未逐行；结论是**文件级全量、行为级到契约层**，不是逐行审计。
7. 未读 `.env`、未读取任何密钥文件；`AiKeyCipher`/`KB_HTTP_AUTH` 只核到“只从环境变量取值”的代码路径。

## 覆盖是否完整

- **文件级：完整**。后端 `ai/` 包 200 个文件全部列目并按子包归类，前端 AI 相关 34 个文件全部核对；规格侧覆盖 `technical-design.md` §2.5、冻结需求 FR-5/FR-6、S4.2 合流规格、`application.yml` AI 全段、决策/任务看板近三日 AI 条目。
- **场景级：完整到“用户可触达面 + 后端契约面”**，共 100 项，覆盖你点名的 11 类；每项均给用户动作/实际交互/文件行号或规格出处/状态/最近变化。
- **不完整处**：第 6 条所列大文件的逐行内部实现、第 3/4 条的外部消费者与审计落库、以及 `docs/evidence/**` 大量历史证据件只做了索引级取样（未逐份读），因此**不能声称“逐行审计完毕”**；本清单可作为逐项业界对标的分层底稿，深挖单点仍需按文件继续。
