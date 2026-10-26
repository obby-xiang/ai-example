<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-031 · 施工复核 · ✅ 已核验（子智能体执行）（17cf4a4b / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`check-docs`。
> **稿态**：历史中间稿 —— 该 wire 第 `m4` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 28 条有正文），末条有正文消息为 `m40`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `17cf4a4b` / agent `main`（`main` = 主会话）；原字符 3,077（链内 1 段、合计 3,077）。
> **链号 / 类别**：`MT-031` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m40`（901 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

基线核验 ✅ 与调研提炼 ✅ 均回。关键新事实：旧快照漂移已从报告的 17/46 扩大到 **21/47**（当前 HEAD 较报告基准 `3d20de96` 又推进 14 个提交，新增漂移 JobProgress/TaskWizard/ConditionForm/DefSelector），重取基准必须钉在当前 HEAD `4a37c40`。6 处过期断言已被子智能体对照当前源码逐条落实改法。

下面输出决策卡呈批（签字节点，只有人能签）：

---

## ✅ 已核验（子智能体执行）

| 项 | 结果 |
|---|---|
| git 基线 | **吻合**：HEAD=origin/main=`4a37c401f834…`，ahead 0，工作树干净，顶端 3 提交与存档基线链一致 |
| 前端测试基建现状 | **零残留**：package.json 无 vitest 相关、无 `tests/`、无 `vitest.config.*`、tsconfig/.gitignore/ci.yml 均未动 |
| 调研报告钉版 | 与存档口径一致：vitest **4.1.11**（安全地板，CVE-2026-84373 修复版）+ jsdom **30.1.2** + @vue/test-utils **2.5.1**，全部精确钉版 |
| 旧快照可复用度 | `sse-watchdog.spec.ts`（4 例）与 `ready.spec.ts`（4 例）**可原样复用**（对应源码 0 漂移/仅注释漂移）；`tools-and-deadline.spec.ts`（20 例）**须修 6 处断言**（常量 90s→60s 预算平移所致，改法已逐条钉死）；`vitest.config.ts`/`setup.ts` 可原样复用 |

---

## 📋 决策卡呈批（待签字）

```
═══ 决策卡：vitest 方案 A 施工批（前端单测基建引入 + 首批 28 用例）═══
呈批人：Kimi K3（指挥官）    日期：2026-10-11    签字权：用户

【背景与已裁决前提】
- vitest 引入已于 2026-10-11 由用户裁决（存档：「方案 A 安装」保留待办，8e4f「不引入」作废）。
- 首批落点沿用 R-91 已呈批口径：3 文件 / 28 用例（SpreadGrid readyPromise 4 例、
  sse.ts 双看门狗 4 例、frontend-tools 预算+foldDeadline 20 例）。
- 调研报告：<LOCAL_WORK>\vitest-prep\调研报告.md（DS V4 Flash，430 行，风险 R1~R15 已全列）。

【选项】
A. 按调研报告方案施工：yarn 精确钉版三件套 + 独立 vitest.config.ts + 首批 28 用例 + CI 插阻断步。
B. 暂缓，等对话时序包之后再做。
C. 改用 vitest 5.0.3（latest）。

【K3 选择】方案 A，原样按调研报告钉版执行。

【理由】
1. 4.1.11 是安全地板（CVE-2026-84373 中危修复版）且已被本仓可行性工程实测 28/28 全绿（1.57s）。
2. 5.x 发布仅约 5 周，clearMocks 默认翻转等新大版本风险 + peer 地板比本仓 vite ^6.0.7 更紧 → 否 C。
3. 前端零单测是质量基线空洞，R-137/对话时序包等后续批次都将受益；独立成批不阻塞他包 → 否 B。

【施工范围（签字后下发 K2.8，经 CU 直发）】
改动面 = 4 改 + 2 增 + CI 1 步：
1. frontend/package.json：devDeps += vitest@4.1.11 / jsdom@30.1.2 / @vue/test-utils@2.5.1（--exact）；
   scripts += "test": "vitest run"（+可选 test:watch）。
2. 新增 frontend/vitest.config.ts（13 行，独立配置：plugin-vue + @ 别名 + jsdom + tests/**/*.spec.ts +
   setupFiles，不设 globals）。
3. 新增 frontend/tests/setup.ts（ResizeObserver stub 三方法）。
4. frontend/tsconfig.json：include += "tests/**/*.ts"、"vitest.config.ts"。
5. .gitignore：+= frontend/.vitest/（+可选 frontend/coverage/）。
6. .github/workflows/ci.yml：frontend job 在 typecheck 与 build 之间插 "yarn test"（阻断，不新立 job）。
7. 源码快照按当前 HEAD（4a37c40）重取（漂移已 21/47，报告 17/46 口径作废，以实测为准）。
8. 三测试文件落 frontend/tests/：ready.spec.ts、sse-watchdog.spec.ts 原样复用；
   tools-and-deadline.spec.ts 修 6 处断言（90_000→60_000 预算族，含 describe/it 标题文案订正，
   逐条改法已由调研子智能体对照当前源码钉死）。
硬纪律：禁 npm install（yarn.lock 必须与 package.json 同提交）；vite 不动；不装 @vitest/coverage-v8；
测试/配置文件中不得出现命名纪律禁用字面量（check-docs 断言⑤按 git ls-files 全扫）。

【机械验收标准（施工后逐条核）】
① yarn test 本地 28/28 绿；② yarn typecheck 绿；③ yarn build 绿；④ check-docs.mjs 6/6；
⑤ 改动文件清单=恰 9 个（package.json/yarn.lock/vitest.config.ts/setup.ts/tsconfig.json/
  .gitignore/ci.yml + 3 测试文件实为 10 个，按实际清单核对，禁夹带）；
⑥ 后端零改动；⑦ lockfile 增量在预期区间（238 条→380~450 条）。

【证据】调研报告钉版口径 + 漂移重算（21/47）+ 6 处断言改法，均经子智能体独立实测复核（本轮）。
【推翻条件】华为云镜像取不到钉版精确版本；或 jsdom 30.1.2 引擎地板与 CI node-version:'22' 实测冲突（R6）。
【遗留【假设】】CI 的 node 22 是否 ≥22.22.2（jsdom 引擎地板）——首跑 CI 即验证，若红则回退 jsdom 版本再呈批。
【登记落点】施工闭环后回写 docs/M2-排期计划.md 去向表 R-91 行「已施工」+本批注记。

请裁决：批准 / 修改 / 否决。
```

---

签字后即派 K2.8（经 CU 直发）施工。若你想批量裁决其他事项或有优先级调整，一并说。
