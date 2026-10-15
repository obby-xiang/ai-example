# 现状取材 issue#14（R-160）

DC-19 自证：`profile.bind` → `{"agentId":"main","modelAlias":"kimi-code/k3","profileName":"agent","thinkingEffort":"high"}`

## 1. AiPanel.vue
气泡 :178 `class="max-w-[82%] rounded-lg px-2.5 py-2 text-[13px] text-[#303133] leading-[1.6] break-words"`；:188 正文块 `whitespace-pre-wrap` + 尾 `span.caret`
折叠头 :218 `class="w-full flex items-center gap-1.5 p-2 text-left font-semibold text-[13px] flex-wrap"`；:228 `<el-tag :size="'small'" :type="toolTagType(part.status)" :effect="part.status === 'running' ? 'dark' : 'light'">`；:226/227 plain tag；requiresAction → `cursor-default`+`:disabled`（卡 testid :214、toggle testid :220）
卡体 :234 args `text-xs text-[#909399] break-all`；:239 result `px-2 py-1 text-xs text-[#606266] bg-[#fafafa] rounded`
回执卡 :303-307
```
303 <div class="mt-1.5 px-2 py-1.5 bg-[#f0f9ff] border border-solid border-[#d9ecff] rounded" data-testid="hitl-receipt">
305 <el-tag size="small" :type="hitlReceiptTagType(part.status)" effect="light">{{ hitlReceiptLabel(part) }}</el-tag>
306 <span class="text-[#606266]">{{ hitlReceiptSummary(part) }}</span>
307 <span v-if="part.decidedAt" class="text-[#c0c4cc]">{{ formatTs(part.decidedAt) }}</span>
```
:796 判据 `part.kind2 === 'CONFIRM' || part.name === GENERATIVE_FORM_TOOL`；`.caret` :1121 2px×14px #409eff caretBlink；`<style scoped>` :1092-1144 仅 typing 指示器，卡片全 Tailwind 内联类。

## 2. utils/blocks.ts :46-62
:47 `defaultExpandedOf` = `parts.some(p => p.kind === 'tool' && p.requiresAction)`；:55-62 `resolveBlockExpanded` = requiresAction 强制 true → manual → `block.defaultExpanded`。唯 requiresAction 默认展开。

## 3. CollapsibleText.vue
:84 容器 `whitespace-pre-wrap`；:86 `:style="collapsed ? { maxHeight: maxHeight + 'px', maskImage: 'linear-gradient(to bottom, #000 55%, transparent)' } : {}"`。
props 默认 `{pretty:false, maxHeight:96, testid:'collapsible-show-more'}`(:16-24)；:44-51 ResizeObserver 判 `contentHeight > maxHeight`，无 RO 恒不溢出。:90 按钮 `mt-0.5 text-xs #409eff`。容器无 `break-words`/`min-w-0` ⇒ 长无空格串横向溢出（args 区另加 `break-all`）。

## 4. FormRenderer.vue
:2-13 外层 `<div>` → 头行 `flex items-center gap-1.5 flex-wrap font-semibold text-[13px]` → 字段 `v-for` `<div class="mt-2">`+标签 `text-xs text-[#606266] mb-1`。宽度：el-input/el-switch 无；el-input-number `style="width:100%"`(:29)；el-date-picker `class="w-full"`(:45)；el-select enum(:54)/multi(:71) 同。

## 5. stores/ai.ts
:741 `kind2: kind === 'CONFIRM' || kind === 'FRONTEND' || kind === 'BACKEND' ? kind : 'UNKNOWN'`；:743 `args: call.arguments, result: null, requiresAction: false`。:727 `kind = state?.kind`，:724 `item.toolCallStates?.[call.id]`。
part 未写 decision/decidedAt/submittedValues ⇒ 刷新后徽标退 status 派生、时刻与已填值全丢；缺项 → kind2='UNKNOWN' → 回执卡不渲染。confirm() :1297-1300 写 decision/decidedAt/status/requiresAction；GF :1503-1504 写 submittedValues。写口 :2074-2076 → `utils/parts.ts:90-103`。

## 6. ResilientChatService.java
`backend/.../configmgr/ai/run/ResilientChatService.java`；`TOOL_CALL_STATES_KEY` :573。:711-719 `toolCallState(status, kind)` 只 put `status` + `kind`；:626-641 遍历 toolCalls → `resolveToolCallState`(:653-682) → `states.put(call.id(), state)`，空则原样返回，否则 `metadata.put(KEY, states)`。载荷只有 {status, kind?}，decision/decidedAt/submittedValues 后端全程不写 ⇒ 跨刷新丢失根因。

## 7. 回执卡现状视觉
见 §1 :303-307。派生：:827-843 status→el-tag type；:802-825 文案决策态优先（decision='approved'→「已确认」）；:846-858 摘要「工具中文名 · args 前 3 键值」；:759-765 `formatTs` = `MM-DD HH:mm`。:309-311 次行 `已提交：`+`submittedValuesSummary`(:862-865 JSON 截 80 字)；:312-322 末行「原始参数 ▸」折叠(:868-878 `receiptArgsOpen`)。:302 `v-else-if` 在 :286 表单分支后 ⇒ 仅 requiresAction=false 已决段。
