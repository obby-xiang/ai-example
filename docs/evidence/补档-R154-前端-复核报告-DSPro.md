# 复核报告 R-154 · 前端面（#1~#7/#9/#12/#13+14/#17）· 复核方 = DS 系

## 一、自证（DC-19，亲读 wire）
- 实际运行模型：DeepSeek/deepseek-v4-pro
- 会话 id：session_e6b55925-fc29-4740-96ce-6a23ff6c8de6（wd_temp_38dcbf7ced2f）
- wire profile.bind 原文（agents/main/wire.jsonl 首条）：
  `{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",...}`

## 二、锚定与改动面（机械验收）
- 锚件 sha256 实测 = `49ca73f31b02a695c723f8c7a65197eb0e6fbb31557dd4cbae8d60b33f711c4e` ✓（1352 行 / 78856B）
- `git diff -- <9 文件>` 与锚件**逐字节一致**（sha256 相同、双方 0 CRLF 无换行符差异、numstat 合计 +614/-300）✓
- 改动面：工作树仅 9 前端文件 M（白名单内）+ backend 2 文件（#16 DSPro 在途，未纳入/未碰）+ 未跟踪 `data/`（未碰）；无越界 ✓

## 三、八项逐项核查
- **#1 截断标记**：sse.ts `SseToolResultFrame` 补 `truncated?/truncatedReason?`；types/ai.ts `ToolPart`/`ToolPartPatch` 同补；parts.ts `tool_result` 落 `truncated: frame.truncated ?? false`、`truncatedReason ?? null`（false/null 缺省兼容）；AiPanel 角标(title=原因)+「显示更多」去帽 max-h。✓
- **#2 上滑暂停跟随**：`@scroll.passive` → onMsgScroll 离底 32px 阈值写 `followBottom`；`scrollBottom(force)` 尊重跟随闸；「回到底部」浮钮 `showBackToBottom`；`backToBottom` 恢复跟随+强滚底。✓
- **#3 消息内错误位**：卡内 `v-if="part.reason"` → WarningFilled 图标 + 红底 `bg-[#fef0f0]` + 「原因：」文案就地渲染；顶部 Alert 群保留。✓
- **#4 生成中标识**：消息头 el-tag「生成中」`v-if="m.streaming && role==='assistant'"`（正文产出后仍在）；done/error 置 `streaming=false` 收敛（stores/ai.ts:1898/1918）。✓
- **#5 操作行**：显示判据改 parts（弃 `m.content`）；复制 `m.content` + execCommand 回退；编辑 `editMessage` 仅 `inputText=m.content`，不截断/不分支/不改 store 数组。✓
- **#6 建议词**：`SUGGESTIONS` 静态 4 枚；空态 chip 点击 `useSuggestion` 回填输入框。✓
- **#7 工具卡纯单卡折叠**：`groupParts` 每段恒一 Block；`defaultExpandedOf` 唯 requiresAction 强制展开；`blockHeader`/`bare` 退役零引用；`data-activity-group` 零残留；requiresAction 卡头禁点。✓
- **#17 时间戳**：`ChatMessage.timestamp?: unknown`；三来源（历史 item.timestamp / 用户 Date.now() / message_start 帧 atMs 且 only-if-undefined）；dayjs `MM-DD HH:mm`，缺失/坏值不渲染。✓

## 四、零超面
仅 9 文件；`dayjs@^1.11.13`、`@element-plus/icons-vue@^2.3.1` 均既有依赖（无新增）；`blockHeader`/`.bare`/`data-activity-group` 生产零残留（仅测试注释/断言提及）；`groupParts`/`resolveBlockExpanded` 消费方仅 AiPanel.vue 与 tests。✓

## 五、复跑核验（fresh evidence）
- `npx vitest run`：**242 passed / 19 spec 全绿**（= 施工自报 242）
- `npx vue-tsc --noEmit`：exit 0（0 错）
- `npm run build`：✓ built in 25.76s（exit 0）

## 六、覆盖评估（复核要点 4）
新增：parts-reducer +2（截断落段/缺省、message_start 时间戳）；ai-panel-parts-render +9（截断角标±、跟随暂停±、生成中、编辑回填、建议词、时间戳渲染、历史透传）；blocks.spec 改写（单卡分派/defaultExpanded 重定义/跨消息命名空间）。关键边界已覆盖。
遗漏（非阻断，登记）：① 未直接断言「followBottom=false 时帧增量 scrollBottom 被闸住」；② 用户消息 Date.now() 时间戳未单测；③ 窗口 resize 无跟随重钉底 handler（卡面未要求）。

## 七、最终裁决
**通过**。改动面、八项覆盖、测试通过数（242）三项机械验收全绿；锚件逐字节一致；零超面、无新增依赖、无悬挂引用。
