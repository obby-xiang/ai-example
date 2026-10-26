<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-182 · 施工复核 · 基线核验全绿、材料包已制备并钉死 SHA-256。锚点提取还发现两处交接前提偏差（已并入任务卡修正）：**ack 文案实际在前端 `ExportWizardView.vue:1121-1133` 且是三个分支**（`AiTools.java（669fc16c / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ExportWizardView.vue`、`AiTools.java`、`workspace.ts`、`frontend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m3` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 31 条有正文），末条有正文消息为 `m47`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `669fc16c` / agent `main`（`main` = 主会话）；原字符 4,784（链内 1 段、合计 4,784）。
> **链号 / 类别**：`MT-182` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m47`（6,575 字符）未入末条归档。
> **脱敏**：路径 5 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

基线核验全绿、材料包已制备并钉死 SHA-256。锚点提取还发现两处交接前提偏差（已并入任务卡修正）：**ack 文案实际在前端 `ExportWizardView.vue:1121-1133` 且是三个分支**（`AiTools.java` 只是哨兵桩）；**`workspace.ts` 的 AbortController/60s 属于 `ui_event download` 链路（R-11），与批 A 的 `download_export_file` 不是同一条路**。

以下是呈您转交 GLM 的批 A 红队任务卡：

```
═══ 红队任务卡：ai-example 批 A「download_export_file 即发即回」（R-95）═══

【你的角色】你是独立红队（对抗性审查者）。目标：设法攻破批 A 改造的正确性、
安全性与口径一致性主张。不是走过场——找不到问题也要证明你认真攻过。
允许使用子智能体分工：可派子智能体做机械核对（逐行比对、哈希校验、grep 取证），
但攻击面判断、结论定级、报告撰写必须由你亲自完成并署名结论。

【审查对象】Git 仓库工作树中 9 项未提交在制品（基线 HEAD=6ee96c3）：
  M frontend/src/views/ExportWizardView.vue      ← 主战场
  M frontend/src/stores/workspace.ts             ← ui_event download 链路（R-11）
  M frontend/src/utils/frontend-tools.ts         ← 纯注释改动
  M backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java
  M backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
  M backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java
  M docs/requirements.md
  M docs/M2-排期计划.md
  ?? backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java

【材料包（锚定防夹带，动手前必做）】
  <LOCAL_WORK>\redteam-batch-a\diff.patch           SHA-256=c50d446b25e18fc32fef078fd14ce5cef9b753b3ba1acf5d09715eba77671b9d
  <LOCAL_WORK>\redteam-batch-a\sha256-manifest.txt  9 个文件逐一哈希
  <LOCAL_WORK>\redteam-batch-a\baseline-report.md   git 基线核验记录
第一步：重新计算 diff.patch 与 9 个工作树文件的 SHA-256，与上述钉值逐一比对；
任何不符立即停止并在报告开头标注「材料被篡改/漂移」，不得继续。

【背景一句话】download_export_file 原是前端工具，AI 调用后前端同步 await 下载再回执，
用户实测撞 120s 挂起超时造成「假失败」。批 A 改为「即发即回」：前置体检（毫秒级）仍 await，
体检通过后不 await 下载链、立即向 AI 回执 ack；下载失败只 toast 用户、不二次回灌。
前端工具超时文案从「未获得数据」改为「结局未知（不表示未发生）」。

【五大攻击面（每个都要给出攻击过程与结论）】

A1. 即发即回真实性
  主锚：ExportWizardView.vue#startDownloadInBackground(:778) 与执行器
  registerFrontendToolExecutor('download_export_file')(:1113)。
  主张：执行器 :1114 只 await preflightDownload(:989)，:1130 调用
  startDownloadInBackground 不 await，:1131 立即 return ack；下载链
  startDownload(:728)→buildXlsx(:706)→downloadBlob(:730)→excel.ts#downloadBlob(:839, 同步 void)。
  攻击点：ack return 是否真的先于 link.click()？preflight 里有没有藏着
  网络级 await（ensureTaskBoundForTool :997、结果存在性 :1001 是否打后端）？
  unhandledrejection：后台 promise 的 .catch(:785) 是否兜住一切 rejection
  （包括 buildXlsx 内部同步 throw、downloadBlob throw）？有没有未挂 catch 的悬浮 promise？
  卸载清理：onBeforeUnmount(:1143-1151) 注销执行器后，在途下载 promise 的
  .then/.catch 回调引用已卸载组件状态会不会炸？

A2. 防重复（在途集合增删时机）
  主锚：inFlightDownloads(:226)；downloadOne(:740)：加 :749 / 删 :760(finally)；
  startDownloadInBackground(:778)：加 :779 / 删 :789(.finally)。
  攻击点：用户侧与 AI 侧并发同码能否双发？加集合与首个 await 之间有无
  让出事件循环的窗口？异常路径是否必然走到删集合（finally 覆盖面）？
  downloading 单值 ref(:208) 与 Set 双轨并行，打包哨兵值 'checked'/'all'
  与普通 defCode 会不会互相误判/漏判？

A3. ack 文案三分支口径（注意：是三个分支，不是两个）
  主锚：ExportWizardView.vue:1121-1133，注释纪律 :1100-1112。
  分支①已发起(:1131) / ②同码在途未重复发起(:1122) / ③他码在途未发起可稍后重试(:1127)。
  攻击点：逐字核对分支①只陈述「已发起」+浏览器不确定性+无需重试，有无
  暗示「已下载完成/已成功」的措辞？分支②③选择条件 :1119-1120
  （busy=downloading.value、requestedInFlight=has(defCode)）在
  busy=null 而 Set 非空、或 Set 与 downloading 不一致时会不会选错分支、
  串用措辞？前置校验失败文案(:994/:999/:1004) 会不会被误当 ack 返回成功？

A4. 文案回归锁有效性
  主锚：ConfirmGate.java#expire(:297)，两分支文案 :310（确认门）/:311（前端工具，
  新文案「未在 N 秒内收到前端回执，本次调用结局未知（不表示未发生）」）；
  回归锁 ConfirmGateFrontendTimeoutTextTest：参数化注入 {2,3} 两互异秒数(:82-85)，
  正则 SECONDS_IN_TEXT(:70) 从实际输出读回秒数与注入值比对；断言必须含
  「结局未知」「不表示未发生」、必须不含「未获得数据」「回传执行结果」(:98-104)；
  确认门分支措辞不变锁(:111)。
  攻击点：测试与实现是否「常量与断言同源」（断言字符串是不是从被测代码
  复制/派生的，实现改错测试跟着错）？正则读回能否真正防住「文案硬编码 120
  而配置注入 2」？确认门分支的负向断言够不够（若有人把 :310 也改成
  「结局未知」，测试抓得住吗）？注入值只测 {2,3}，生产默认 120s 路径
  （AiProperties.java:127）有无覆盖盲区？

A5. workspace.ts 的 AbortController 60s（ui_event download 链路，R-11，非批 A 主链）
  主锚：DOWNLOAD_FETCH_TIMEOUT_MS=60_000(:327)、case 'download'(:434)、
  AbortController(:437)/setTimeout abort(:438)/await fetch+signal(:440)/
  abort 归因(:446-450)/clearTimeout finally(:451-453)。
  攻击点：这是批 A 之外仍同步 await 的下载等待面——60s 超时会不会造成与
  批 A 要消灭的「假失败」同类问题（下载大于 60s 即 abort→fail 回灌）？
  abort 归因判断是否可靠（fetch 在 abort 前已 reject 的竞态）？
  常量注释声称与 api/http.ts 的 60000 同值——实读核对是否属实、
  依据注释是否充分？

【输出要求】
报告落盘：<LOCAL_WORK>\redteam-batch-a\红队报告.md
结构：材料锚定核对结果 → 五大攻击面逐一（攻击过程/证据:文件:行号/结论）→
发现清单（每条：严重级 BLOCKER/MAJOR/MINOR/观察项 + 复现路径 + 主锚）→
总结论（PASS / 带条件 PASS / FAIL）。
纪律：每条发现必须有文件:行号实读证据；区分【实测】/【推断】/【假设】；
找不到问题的攻击面必须写出你试过什么。不改仓库任何文件（只读+写报告）。
```

---

**本回合状态汇总**：

- **git 基线核验✅**：HEAD=origin/main=6ee96c3，9 项在制品逐一归属相符，无偏差、无夹带（实读报告 `<LOCAL_WORK>\redteam-batch-a\baseline-report.md`）。
- **材料包✅**：`diff.patch`（636 行/9 文件，`git apply --check` 验证可干净应用）+ SHA-256 清单，钉值 `c50d446b…771b9d`。
- **红队任务卡已出**，在上方代码块，可直接转交 GLM。相比交接备忘做了两处事实修正：ack 文案锚点从 AiTools.java 纠正为 `ExportWizardView.vue:1121-1133`（且为三分支）；workspace.ts 攻击面标注为 R-11 独立链路（仍是 9 项之一，作为「残留下载等待面」攻击，不改批 A 范围）。

GLM 红队报告回来后，下一步即派提交官（含 R-95 行「施工中→已施工」、两处旧口径文档注记、`docs/03-技术方案文档-v2.1.md:264` 与 `docs/spike/SP-01ab-决策卡.md:130` 修正随批 A 提交）。数字规格 7 组裁决仍等您批复。
