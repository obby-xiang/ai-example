<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-319 · 施工复核 · 收尾棒完成（注释勘误+报告指针，typecheck 绿，仍 4 个 M 文件）。**包①+包⑤整批已达到红队门槛**。按纪律出 GLM 红队任务卡，请你转交 GLM-5.3：（fa72b518 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`generative_form`、`ConfirmGate`。
> **稿态**：历史中间稿 —— 该 wire 第 `m43` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt31` 条有正文的 assistant 消息（共 65 条有正文），末条有正文消息为 `m91`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `fa72b518` / agent `main`（`main` = 主会话）；原字符 2,942（链内 1 段、合计 2,942）。
> **链号 / 类别**：`MT-319` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 19 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `11%`。
> **与末条归档（138 件）的关系**：末条 `m91`（7,405 字符）未入末条归档。
> **脱敏**：路径 9 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

收尾棒完成（注释勘误+报告指针，typecheck 绿，仍 4 个 M 文件）。**包①+包⑤整批已达到红队门槛**。按纪律出 GLM 红队任务卡，请你转交 GLM-5.3：

```
你是 GLM-5.3 红队，对 ai-example 项目一批前端阻塞链修复做对抗审查。你的角色是攻击者：目标是找出施工与两轮复核都漏掉的缺陷，不是走流程放行。

【子智能体分工条款】你可以派自己的 deepseek-v4-flash 子智能体执行机械活（批量走读、grep 扫描、跑验证脚本、门禁复跑）；你的本体只做攻击面分析、关键判断与结论裁决。任务卡结构=关键判断点清单（本体）+机械活清单（可派）。

【对象】
仓库（只读，零 git 写操作、不改任何文件）：主仓库工作树，分支 main，HEAD=5cfbf33，工作树有且仅有 4 个未提交 M 文件：
- frontend/src/components/SpreadGrid.vue（+72/−5）
- frontend/src/api/sse.ts（+74/−0）
- frontend/src/stores/ai.ts（+12/−3）
- frontend/src/utils/frontend-tools.ts（+226/−9）

【施工内容】
包①（B-07+R-03，S1 悬挂双修复）：
- SpreadGrid.vue：readyPromise 四路径（成功/失败/超时60s/卸载）均经 settleReady() 唯一出口 settle（:105-112），failReady() 兜早退/catch/卸载（:121-125/:143/:182/:190），waitForReady() 按活体状态判定（:261-266）。
- sse.ts：reattach 建连看门狗 15s（:43 常量、startConnect() :506-520，open() 内 :555 武装），清除点 settle:470/startIdle:484/scheduleReconnect:528-529，不变量"任一时刻只武装一只"，超时走既有 scheduleReconnect→onClose(terminal=false) 复位 loading。
包⑤（R-06，issue#6 根治）：executeFrontendTool 统一超时预算（frontend-tools.ts 的 deriveFrontendToolBudgetMs :198-226 双钳制：下限5s/上限timeoutSeconds×1000；runWithBudget() Promise.race 超时只依赖计时器；timeoutOutcome() 结局驱动文案不谎报），stores/ai.ts runFrontendTool 传 budgetMs（:1493 一带）。
已裁决口径：拒绝 void downloadOne() 落点；同步阻塞主线程边缘接受"迟到但不丢+文案如实"；generative_form 特判不被预算掐掉。

【既有证据（可复核不可尽信）】
施工报告 <LOCAL_WORK>\pkg1-reports\施工报告.md、<LOCAL_WORK>\pkg5-reports\施工报告.md；复核报告 <LOCAL_WORK>\pkg1-reports\复核报告.md、<LOCAL_WORK>\pkg5-reports\复核报告.md（含修补轮增量复核节）；仓外验证脚本 <LOCAL_WORK>\pkg1-reports\scratch\{grid-ready.mjs,sse-watchdog.mjs}、<LOCAL_WORK>\pkg5-scratch\budget-check-v2.mjs（54 PASS）。issue#6 复现实证 <LOCAL_WORK>\issue6-reports\issue6-复现-kimibrowser.md 与 <LOCAL_WORK>\issue6-repro\（代理事件流 proxy-events.jsonl）。

【关键判断点清单（你本体裁决）】
J1 语义回归：Promise.race 超时后，迟到 settle 的执行器副作用（如下载最终完成、UI 状态变化）与已回灌的"结局未知"之间是否存在用户可见矛盾？回灌后执行器再 reject 真的零 unhandledRejection？
J2 看门狗正确性：sse.ts 建连看门狗在 快速失败→重连→再武装 高频交错、abort 与超时同帧竞争、浏览器后台节流（setTimeout  clamping）三种场景下，不变量"只武装一只/不误杀活连接"是否仍成立？
J3 SpreadGrid 60s 假阳性：4.7MB chunk 慢链路误判 failed 后，"消费点弹错可重试+晚到成功自愈"是否真的无残留坏状态（engineState/engineError/编辑器半初始化）？
J4 预算折算：双钳制在 timeoutSeconds 为 0/负/NaN/Infinity/字符串/极大值时的行为是否全部安全？120→90s 的 30s 余量是否足够覆盖回灌 POST + 后端判定窗口（找证据反驳或支持）？
J5 文案诚实性边界：timeoutOutcome 的"结局未知"文案在所有四条路由下都不谎报？下载类与通用类分工有无反例？
J6 交互面：包①与包⑤在同一 SSE 会话生命周期内叠加（挂起倒计时 foldDeadline/atMs 体系、ConfirmGate 120s），有无新的组合竞态？
J7 命名纪律与证据立规：diff 全量扫"main+分隔符+v2"任意大小写形态（含 NFKC/零宽绕过）、盘符、用户名、内网 IP。

【机械活清单（可派子智能体）】
M1 git diff 全量逐行走读 4 文件；M2 复跑三个仓外验证脚本+frontend 下 yarn typecheck/yarn build；M3 grep 全仓验证无新轮询/无计时器泄漏面（对照 setTimeout/clearTimeout 配对）；M4 命名纪律四态扫描（原文/NFKC/零宽删除/空格分隔）。

【定级与产出】
发现按 S1（阻断，必须返工）/S2（应修，可裁决缓办）/S3（建议）/S4（观察）分级，每条给 类名#方法名 锚点+攻击场景+证据。结论只有两种：放行（0×S1）或驳回（含 S1 清单）。
★报告落盘 <LOCAL_WORK>\redteam-pkg15\红队报告.md，你只回路径不贴全文。
```

**当前总盘面**：

| 线 | 状态 |
|---|------|
| 包①+包⑤（施工→双复核PASS→修补→增量复核PASS→收尾） | **等你转交红队任务卡** |
| 弹窗重叠排查（agent-280） | 后台运行中 |
| 任务名称必填链排查（agent-281） | 后台运行中 |
| 轮询治理一期包 | 已裁决立项，排队在红队放行+提交之后 |

红队结果回来后：放行→提交官两笔细分提交+CI 闭环+去向表行尾注记登记；驳回→按 S1 清单返工。
