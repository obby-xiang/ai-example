# 红队报告 · R-150（DS V4 Pro 推翻式审查）

- 锚核验：core diff sha256 `7c4df53c…503382` ✓；零夹带重生成（两路径 `git diff -- ai.ts parts-residue-cleanup.spec.ts`）= 19859B，与锚 **cmp 逐字节 EQUAL** ✓。HEAD=`9211adcb`（锚载 921da769 后另有 docs 提交，但两文件 baseBlob 未变，diff 稳定）。verify-e2e.ps1/data/ 未触碰。
- 署名（实测）：实际运行模型 **DeepSeek/deepseek-v4-pro（经 Kimi Code 主代理）** · 会话 id **session_45401319-a996-41b5-bac5-c2efa213e438** · wire modelAlias = 本会话 `agents/main/wire.jsonl` 首条 `profile.bind{modelAlias:"DeepSeek/deepseek-v4-pro"}`（grep 去重，非照抄卡头）。
- 机械复跑下沉 agent-675（钉 DeepSeek/deepseek-flash）。

## S0 反证（悬空 A(T) 可达路径）—— 未找到反例，施工报告 S0 成立

逐一排除的写入路径（`git show HEAD:` 取证）：
1. **挂起外置 saveSuspended**（RunStore:354-369）：`setMessageJson(serializeAll(history))`，其 `history` = `prompt.getInstructions()`（SpToolCallingManager:162 传入的是 instructions，**不含 assistant**）。assistant 只入 `assistantContent` + `inFlightToolCalls(pendings)`，不进 messageJson。
2. **settle**（SpToolCallingManager:203-205）：唯一把 assistant(tool_calls) 写进 messageJson 处，恒 `history.add(assistant); history.add(toolMessage)` 成对。
3. **取消/空回复/成功三终局**（ResilientChatService:449-530）：均读 `snapshot.getMessageJson()` 现状 → convergeMemory。挂起期取消 ⇒ messageJson=instructions（无 assistant）；settle 后取消 ⇒ 配对完整。**悬空 A(T) 不进 chat:mem**。
4. **超时**：confirm timeout 走 ConfirmGate.expire → awaitConfirm 返回 → 循环续 → settle 配 toolMessage（"未执行"也产 toolMessage）。
5. **异常中断**：checkCancelled 抛 StreamViolationException → settle 未达 → messageJson 停留 instructions。
6. **窗口裁剪 trimKeepingToolPairs**：只防"首条孤立 TOOL"，不产悬空 A(T)。
结论：悬空 A(T) 后端不可达；R-150 闸是纵深防御（代码注释自证"S0 实证后端不可达，闸保留防旧镜像/未来形态"）。**未推翻。**

## S2 合并逻辑边界 —— 正确，无 mis-merge / id 碰撞 / seg 碰撞

- builtCursor 与 history.messages 的 non-TOOL 项 1:1 对齐（blank drop 时 `fetch 但 builtCursor++`，不 push，对齐不漂）。
- seg 全局单调重排 `map((part, seg)=>({...part, seg}))` 唯一，渲染 key `kind+'-'+seg` 无重复。
- 兜底 toolCallId 用 R-149 flatMap 已钉的**自身原始 index**（R-150 不触碰），测试⑨锁 `history-1-alpha`，无撞号。
- 窗断点 = USER/SYSTEM / blank-drop / 悬空 flush，均正确。
- 小瑕（非缺陷）：用例② fixture 用非空 A(T) text（'第一轮'/'第二轮'），与真实后端（A(T) text 恒空）不符，属夹具现实性，不影响合并语义锁。

## S3 回归面 —— R-149 与 R-150 叠加正确；一处未锁

- R-150 后置 pass 钉死在 R-149 两遍扫描 + 序位兜底 + C1 deriveProjections **全部完成之后**（注释自证），叠加无三次态；合并后重 deriveProjections，content=toolRuns 随 parts 正确（用例②锁 content='第一轮第二轮'）。
- **MINOR**：`restoreSuspendedSnapshot` 交互未测试——9 用例全 `noSuspension()`。合并后 pending 卡重新挂到 merged 消息（toolCallId 保留、理论上正确），但无锁。建议补「历史合并 + 挂起快照重建」交互用例。

## S4 测试充分性 —— 基本充分，两处缺口

- +9 用例覆盖：真形状基准/夹 USER 不并/responseData=null 仍并（闸按结构性判据，不看内容）/配对失败仍并/悬空不并不吞/drop 规则/镜像深度相等/兜底 namespace/多轮同轮合并。
- 断言迁移 3 处（②/④/C3-1）逐处对 diff：② 改为合并形态（条数 2、parts kind/seg/content、id=history-1）；④ 改锁「实现确定性」并注明"防御性 tie-break 非后端序"（吸收我上一轮 O-1）；C3-1 改合并形态。**均削弱的是"逐条"断言、升级为"合并形态"断言，语义等价且更贴合 R-150 目标，无实质削弱。**
- 缺口：① restoreSuspendedSnapshot 交互（见 S3）；② SYSTEM 夹断无专测（USER 有）。

## S5 独立复跑 —— 确认非自述

- `yarn test` = **19 spec / 225 用例全绿**（exit 0）；`yarn typecheck` = exit 0 零报错；`yarn build` = exit 0。复跑未改任何文件，工作树 2 M + ?? data/ 原态。

## 分级清单

- **MINOR-1**：restoreSuspendedSnapshot 与 R-150 合并的交互零测试（9 用例全 noSuspension），挂起轮 pending 卡重挂合并消息面未锁。落点：parts-residue-cleanup.spec.ts R-150 describe 缺失交互用例。
- **MINOR-2**：用例② fixture 用非空 A(T) text，与后端 A(T) text 恒空不符，夹具现实性小瑕。落点：parts-residue-cleanup.spec.ts:②。
- **登记**：锚载 HEAD=921da769 与当前 9211adcb 差一笔 docs 提交（两文件 baseBlob 未变，diff 稳定）；verify-e2e.ps1 现非 M 态（GLM 热修已还原/另落），非 R-150 面。

## 结论

**未推翻。** S0 反证不成立（悬空 A(T) 后端不可达，六条路径逐一排除）；合并逻辑正确、无 id/seg 碰撞；R-149/R-150 叠加语义正确；独立复跑 225/19 + typecheck + build 全绿。2 项 MINOR（restoreSuspendedSnapshot 交互未锁、夹具现实性），不构成推翻。零夹带通过。
