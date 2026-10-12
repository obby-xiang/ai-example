> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r94-deadcode\复核报告-R94-DSPro.md（4093 B）；源件 sha256 = `cf9bfd358674e5ab2b82eb9c8f8d795004f5607374489d5cb33da0d2cf8b9900`；入库批次 = R-94 入库批（2026-10-13，单笔提交）；本复核所用**审查锚** = `r94-working.diff` sha256 `dbc17a1527f726e8986a31b03f6ae3ab2676682f3a1d401f786b87ef7fb7b776`（收口修前；收口修后重锚入库，见复核确认件双锚注记）；本件除本注记行外与源件逐字节一致（零改写）。
# 复核报告 · R-94 死码族清除 + 发布冲突提示落点重建（DSPro 红队）

## 一、DC-19 署名实证
- 实际模型：`DeepSeek/deepseek-v4-pro`；wire modelAlias：`"DeepSeek/deepseek-v4-pro"`（profileName=agent，thinkingEffort=high）
- 探测：`grep -m1 "profile.bind" <本会话>/agents/main/wire.jsonl` 首条原文：
  `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",...}`
- 会话 id：session_e6b55925-fc29-4740-96ce-6a23ff6c8de6

## 二、锚定
- diff sha256 = `dbc17a1527f726e8986a31b03f6ae3ab2676682f3a1d401f786b87ef7fb7b776` ✓（37186B/745 行）
- HEAD = `d3fa7d0` ✓；`git log -1` 即 d3fa7d0，零 commit ✓；工作树 8 文件全 M + `?? data/`，无越面 ✓

## 三、复核面逐条

**1 删除安全性** ✓
- 9 字段 src 零残留；`currentTask`/`issues` 仅新局部量（task.ts:95/339）与注释（:361）。
- refreshCurrent/loadIssues/closeTask/getTaskOverview：src 零残留。
- startJob 仅 ImportWizardView.vue:815 视图本地同名（未误伤）；setImportMode workspace.ts:192 活 action 保留（:463 用），task store 版已删。✓
- updateStatus 未删：TaskService.java:268 在，TaskStatusAbsorbGateTest:105/109/139 + DefinitionDeleteTest:161 实证调用。✓
- 后端 complete 零活调用：`.complete(` 命中均为 terminalWriter/emitter，无 taskService.complete。✓
- TaskOverview 保留（types/task.ts:131），api/tasks.ts 不再 re-export、无悬空 import。✓

**2 新落点正确性** ✓（1 项 MINOR，见裁决）
- (a) 明细组装 task.ts:95-100 前 5 条 + 「共 N 处」；降级 task.ts:339-349 catch→null→降级双文案。✓
- (b) reportUiEventResult(workspace.ts:348-352)=`touch(message,'AI')`+`emitWorkspaceEvent('ui_event_result',{event,handled,message})`，与 dispatchUiEvent(:390-396) 同形同通道属实；ai.ts:560 订阅→lastUiEventResult→AiPanel.vue:108-113 渲染。✓
- (c) F4 闸(task.ts:332)、300s 墙钟(:283-317)、publishWatchers(:78/277/293/311) 未破坏。✓
- (d) 异步回灌必达：onFinal(:301-305) 先 dispose() 再 `void applyPublishOutcome`（脱手异步跑完 getIssues→onConflict），dispose 不取消回灌。✓
- (e) ElMessage task.ts:354 = 决策卡 §三.2 逐字。✓

**3 测试同步** ✓：spec 重写锁双通道+截断+F4；goto-step 摘桩后 CANCELLED 守卫仍锁原红线；242/19 持平我复跑确认。

**4 门禁复测**：typecheck（必复测）`npx vue-tsc --noEmit` exit 0（0 错）✓；vitest（加验）`npx vitest run`=**242 passed/19 spec** ✓；build/后端 492/check-docs 6/6 **采信施工报告，未复测**。

**5 越面** ✓：8 文件之外零改动、`?? data/` 之外无新增、HEAD 未变。

## 四、特别关注（四项）
1. task.ts 矛盾注释（原 :414-415 vs :420-425）已随死字段写入清除；新注释 :321-326 口径一致。✓
2. sse.ts:400-402 删后 :393 const 干净：tryReattach 内声明+if 块一并删；`DEFAULT_MAX_RECONNECTS`(:25) 仍被 :425 用，无孤儿 const。✓
3. onSnapshot 死写删除不涉 R-144 载荷链：仅写 task store currentJobs 死字段，R-144 在 stores/ai.ts，无交界。✓
4. workspace.ts「issues 命中仅新注释」属实：唯一命中 workspace.ts:474 为新增注释。✓

## 五、最终裁决：有条件通过（MINOR-1）

**MINOR-1**：决策卡 §三.1 显式要求明细「落 AiPanel 工具卡 reason 位（AiPanel.vue:264-267）」未实现——施工改落 `ui_event_result` 回执 Alert，工具卡 reason 位未接线（施工报告已披露：toolCallId 不在 watcher 键域，接线需扩面 stores/ai.ts+frontend-tools）。功能上双通道覆盖人机两侧，但 (1) 未达决策卡显式落点；(2) toast「明细见 AI 面板工具卡」指向未接线工具卡、与真实落点（回执 Alert）不一致。收口建议：接线工具卡 reason 位，或修订 toast 文案改指回执位（需决策卡修订确认）。

机械验收（改动面/删除安全性/242 通过/typecheck 0 错）全部达标。
