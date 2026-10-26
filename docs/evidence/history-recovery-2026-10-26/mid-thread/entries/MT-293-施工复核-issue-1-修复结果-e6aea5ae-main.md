<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-293 · 施工复核 · issue #1 修复结果（e6aea5ae / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ExportWizardView.vue`、`ai-example-<OLD_REPO_DIR>`、`frontend/src/`。
> **稿态**：历史中间稿 —— 该 wire 第 `m31` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt22` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m34`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e6aea5ae` / agent `main`（`main` = 主会话）；原字符 2,910（链内 1 段、合计 2,910）。
> **链号 / 类别**：`MT-293` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 7 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `2%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m34`（6,268 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

issue #1 修复完成，我的复核：**通过**。向用户汇报：

## issue #1 修复结果

**根因复核有一个重要修正**（红队价值已体现）：issue 描述的机制（"等 handler 注册的窗口里 taskId 未绑定"）不准确——实测发现 fresh mount 路径下绑定早于 handler 就绪，从任务中心进入其实一次成功。**真正的机制**：目标向导页**已挂载**时，`restore_task` 只改路由 query、同路由不重建组件、且视图没有 `route.query.taskId` 观察者 ⇒ 绑定永远不发生，而 `waitForPageReady` 判据早已满足 ⇒ `navigate_to` 回灌**假成功**；"第 3 次重试成功"是绑定被另一条路径偶然补上。

**修复**（仅 3 个前端文件）：
- `ExportWizardView.vue` / `ImportWizardView.vue`：新增 `watch(() => route.query.taskId)` → 同步绑定 + `loadTask()`（**真正的修复点**，导入侧同缺口同修）；
- `frontend-tools.ts`：新增 `waitForTaskBound`（沿用 3000ms/50ms 轮询超时口径），`navigate_to` 携带 taskId 时四结局回灌——就绪+已绑定→成功；**就绪但未绑定→明确报错（可行动文案）**，不再假成功。

**验证**（项目前端无测试框架，未擅自加依赖）：改用**真实浏览器探针**（真 router/pinia/store/视图）跑 8 场景 before/after——修复前复现假成功+select_definitions 报错，修复后 navigate_to 报「任务 #1 已绑定」且 select_definitions 一次成功；3 个失败出口均有界返回（≈3s）；不带 taskId 的行为与改前逐字相同。typecheck+build 双绿。报告：`<LOCAL_WORK>\issue1-reports\issue1-修复报告.md`。

**遗留**（不阻塞）：探针无 runner 已存档；不存在 taskId 仍乐观绑定（刻意未收紧）；select_definitions 不落库（既有独立问题，建议单独立项）。

修复请 GLM 轻量确认（可与 T3b 红队卡一并转交）：

```text
# issue #1 修复 红队轻量确认任务卡（GLM-5.3）

## 角色与分工条款
你是红队（GLM-5.3），确认一个前端 bugfix 的忠实性与无回归。可派子智能体（deepseek-v4-flash）
做机械活（grep/diff/构建实跑），你本体做攻击面分析与裁决。

## 攻击对象
- issue：https://github.com/<OWNER>/ai-example/issues/1（navigate_to→select_definitions 绑定竞态）
- 修复报告：<LOCAL_WORK>\issue1-reports\issue1-修复报告.md（+ evidence/fix.diff 全量改动、probe 存档）
- 仓库：<REPO>（报告写 <MAIN_REPO>），HEAD=749fa43，
  工作区含 T3b 的 31 项未提交改动（backend/scripts/docs，与本修复无关，勿动勿评）+
  本修复 3 个 frontend 文件（M）：frontend/src/utils/frontend-tools.ts、
  frontend/src/views/ExportWizardView.vue、frontend/src/views/ImportWizardView.vue。只读。

## 修复自述（攻击靶子）
- 机制修正：同路由已挂载时 restore_task 只改 query、不重建组件、无视图观察者 ⇒ 绑定永不发生
  且 waitForPageReady 假成功（修复棒称实测复现，探针存档 evidence/probe-src.ts + probe.html）。
- 修复点：两向导视图新增 watch(route.query.taskId) → setTaskId+loadTask；frontend-tools.ts 新增
  waitForTaskBound（3000ms/50ms），navigate_to 携 taskId 时四结局回灌（含"就绪未绑定→明确报错"）。
- 宣称：修复后探针 8 场景绿；不带 taskId 行为逐字不变；typecheck/build 双绿。

## 攻击面（至少覆盖）
1. diff 逐行审：fix.diff 与三文件实际改动一致？有无夹带？watch 的时序（同步 setTaskId 再 loadTask）
   对首次挂载路径是否引入双重绑定/竞态（onMounted 的 enterPage 绑定 vs watch 触发）？
2. 假成功口径：四结局回灌是否会让"页面正常但任务慢加载"的合法场景误报失败（3000ms 超时是否够）？
   AI 工具协议侧（runWorkspaceAction 的 ok:false 文案）会不会诱导模型死循环重试？
3. 回归面：grep waitForPageReady/runWorkspaceAction 全部调用点，确认其他工具（非 navigate_to）路径
   行为不变；ImportWizardView 与 ExportWizardView 的 watch 逻辑是否对称（报告承认 Import 侧未跑探针）。
4. 复现抽查：yarn typecheck / yarn build 实跑（frontend/ 下，node v22 + yarn 1.22）；探针复跑可选
   （evidence/probe-src.ts 拷回 frontend/ 跑，报告 §复跑 节若有说明参照执行）。
5. 脱敏/命名纪律：3 文件全文禁出现主仓库目录名字面量（main+分隔符+v2 任意组合）、禁绝对盘符路径。

## 硬约束
零 git 写操作；不修改仓库文件；产物落 <LOCAL_WORK>\issue1-redteam\；
报告落盘 <LOCAL_WORK>\issue1-reports\红队确认-GLM-5.3.md（只回报路径）；
发现给严重级（S1/S2/S3）+ 锚点（类名/方法名为主锚）；裁决格式：「放行 / 修正后放行 / 打回」+ 理由。
```

**当前在轨**：① T3b 红队卡（上一条消息）+ 本 issue 确认卡均待 GLM 返回；② 双卡通过后提交官统一落盘（T3b 分笔 + issue 修复一笔），届时工作区 34 项改动一次清盘。
