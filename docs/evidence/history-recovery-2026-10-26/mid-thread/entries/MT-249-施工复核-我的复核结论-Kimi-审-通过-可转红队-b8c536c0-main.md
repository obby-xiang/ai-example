<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-249 · 施工复核 · 我的复核结论（Kimi 审）：**通过，可转红队**（b8c536c0 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`ExportWizardView.vue`、`AiTools.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m35` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt10` 条有正文的 assistant 消息（共 23 条有正文），末条有正文消息为 `m58`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `b8c536c0` / agent `main`（`main` = 主会话）；原字符 4,012（链内 1 段、合计 4,012）。
> **链号 / 类别**：`MT-249` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 6 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `9%`。
> **与末条归档（138 件）的关系**：末条 `m58`（8,460 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 我的复核结论（Kimi 审）：**通过，可转红队**

报告质量核验：根因复核诚实（实测现象面比 issue 更宽——发现**张冠李戴**（场景 c：打开当前任务同名文件）与**谎报成功**（场景 b：download 404 仍回灌成功）两个更坏结局）；修复设计正确——复用 issue#1 绑定链路（watch 收敛到同一 `applyTaskBinding`，无第二套切换逻辑），同步段 `taskId.value` 先于 `router.replace` 故 watch 幂等早退不重入；先探活再改绑（404 不污染工作区）；清 `loadedGrids` 是场景 c 的直接成因修复，必要非过度；同类扫描三向证实 Import 侧无同类；断言标注诚实（1 处【推断】、1 处【旁证】如实标明）。

**5 项待裁决，我裁定**：① 换任务清空旧任务页面态——**认可**（旧结果属另一任务，留着即同源错误）；② 失败文案尾句分叉——**不做**（动通用包装影响全部前端工具，收益低），登记观察项；③ 非 EXPORT 步骤 scope——**维持现状**（AI 先 `navigate_to(export, taskId)` 即达，渐进披露是既有设计），登记观察项；④ `run_job` 跨任务建作业——**维持现状**，登记遗留（涉产品口径）；⑤ 前端测试框架——与 issue#1 遗留合并，**呈你裁决**（改技术栈）。

---

下面是 **GLM-5.3 红队确认卡**，请转交 GLM；报告落盘后回报"已完成"即可：

```
【红队确认任务卡：ai-example issue #2 修复】

你是红队（GLM-5.3），对 issue #2 修复做攻击面审查与独立复核。你的结论只有三种：
放行 / 修正后放行 / 打回，附按 S1（阻断）/S2（应修）/S3（观察）分级的发现清单。

═══ 子智能体分工条款 ═══
你可派自己的子智能体（deepseek-v4-flash）执行机械活：全量扫描、批量 grep、大文件通读、
typecheck/build 实跑、日志翻找、diff 逐字节比对。你本体只做：关键判断、攻击面分析、
结论裁决、报告撰写。本卡结构即按此分为「关键判断点」与「机械活」两清单。

═══ 对象与材料 ═══
- 仓库：本地主仓（文档中一律写 <MAIN_REPO>，禁止直书真实目录名；禁止出现 main 后接
  分隔符（- _ 空格 点）再接 v2 的任意大小写组合）。分支 main，HEAD=ee1d3c4，
  工作区应仅有 1 个未提交文件：frontend/src/views/ExportWizardView.vue（+134/−22）。
- 修复报告：<LOCAL_WORK>\issue2-reports\issue2-修复报告.md（262 行，含根因复核/修复实现/
  9 场景探针矩阵/同类扫描/观察项/待裁决）
- 证据：<LOCAL_WORK>\issue2-reports\evidence\（fix.diff 243 行 SHA256 2f4d9258…5697、
  gate-typecheck.log、gate-build.log、probe-src.ts、probe.html、probe-run.mjs、
  probe-before/after-*.json、各场景截图）
- issue 原文：https://github.com/<OWNER>/ai-example/issues/2（可用只读方式访问；
  报告 §2 已全文复述根因，issue 行号 :732/:745-753 因 issue#1 修复已偏移，以 HEAD 实读为准）
- 前序：issue #1 修复（navigate_to 绑定竞态）已落盘于 HEAD 内，本次 watch 改调同一实现。

═══ 纪律 ═══
全程零 git 写操作、零仓库文件修改（typecheck/build 产物除外）。报告与产物脱敏：
<MAIN_REPO>/<SCRATCH> 占位，无盘符绝对路径、无密钥。断言标注【实测】/【推断】/【假设】/【旁证】。
红队产物（工作区 diff 存档等）落 <LOCAL_WORK>\issue2-redteam\。
报告落盘：<LOCAL_WORK>\issue2-reports\红队确认-GLM-5.3.md（用户只回路径）。

═══ 关键判断点（你本体逐项裁决）═══
K1. diff 忠实性：工作区 git diff 与 evidence/fix.diff 是否逐字节一致、有无未申报夹带；
    改动是否全部落在报告宣称的锚点（ExportWizardView.vue#applyTaskBinding /
    #resetTaskScopedState / #loadTask / #loadExistingResults / #ensureTaskBoundForTool /
    #noResultMessage / open 执行器 :827 / download 执行器 :850 / watch :915）。
K2. 绑定链路时序（核心攻击面）：applyTaskBinding 同步段 taskId.value 先于 router.replace，
    watch(route.query.taskId) 触发时应幂等早退——验证"执行器主动改绑"与"route query 变化"
    双触发路径不会重入/双重 loadTask；createAndBindTask 与 onMounted 两条旧路径行为是否不变
    （它们忽略 loadTask 新返回值）；resetTaskScopedState 清空窗口是否有可被利用的中间态
    （清空后 loadTask 失败时页面残留什么？AI 继续操作会怎样？）。
K3. 失败口径：三类失败（任务不存在/列表读取失败/确无该文件）文案是否真可区分、对 AI 可行动；
    探活失败是否真的不改 URL/不改 workspace（报告宣称，需验代码）；成功文案带任务号后
    是否存在"说的是 A 打开的是 B"的残余路径（loadedGrids 清理是否彻底）。
K4. 回归面：同任务 open/download 零额外请求（newCalls=[]）；navigate_to 四结局与
    frontend-tools.ts 未动；issue#1 修复行为（报告 §4.3 H/I）未被破坏；select_definitions/
    set_condition/confirm_step 无 taskId 参数不受影响；Import 侧无同类的三向论证是否成立。
K5. 同类扫描结论复核：全前端"读页面态却接受 taskId"的执行器是否确实只有这两个
    （run_job 登记遗留的口径你是否认同）。
K6. 脱敏/命名纪律：fix.diff、报告、evidence 全量 grep（禁则组合/盘符/sk- 形态）零命中。

═══ 机械活（派子智能体）═══
M1. git diff 与 evidence/fix.diff 逐字节比对 + SHA256 复算 + git status --porcelain 核对。
M2. 独立复跑门禁：frontend/ 下 yarn typecheck、yarn build（记录退出码与耗时，
    产物 hash 与报告 §4.4 对照）。
M3. 全文 grep 支撑 K4/K5/K6（registerFrontendToolExecutor、args.taskId/payload.taskId
    读取点、AiTools.java 工具参数面、ImportWizardView 执行器注册）。
M4.（可选）探针复跑：按 evidence/probe-run.mjs 头部注释的步骤（拷回 probe-src/probe.html、
    VITE_DEV_PORT=5211 yarn dev、运行器参数化路径），抽查场景 a/c/e 至少其一；复跑后
    删除仓库内探针文件。若跳过须写明理由（fix.diff 逐字节锚定 + 门禁独立复现是否足够）。

═══ 已知边界（报告已披露，不重复计为发现，除非你不同意其定性）═══
- loadTask 无请求序号守卫（issue#1 红队 S3 遗留，本次未做）；
- 失败文案通用尾句"请提示用户手动重试"不分叉（指挥官已裁决登记观察项）；
- 非 EXPORT 步骤 scope 拒绝跨任务 open（指挥官已裁决维持现状）；
- run_job 不切绑定（指挥官已裁决登记遗留）；
- §3.4 第三行"文件列表读取失败"文案分支未在探针构造（报告自标【推断】）；
- 历史证据文档 S44c 引用旧文案未回灌（历史存档口径）。

═══ 报告格式 ═══
裁决 → 攻击面逐项结论（K1~K6）→ 发现表（#/级别/发现/锚点）→ 裁决理由 → 建议跟进项。
子智能体实跑证据（命令+退出码）随附。
```

红队报告落盘后我验收 → 提交官收尾（单文件显式 add + 观察项登记 + push + issue #2 评论关联）。**另提醒你一件待你裁决的事项**：前端测试框架引入（issue#1、issue#2 共同遗留，加 vitest/playwright 会改 `package.json`/`yarn.lock`）——要立这项就示下，不立就继续挂观察项。
