<!-- 第 19 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-R149-DSPro.md`（5826 B，sha256 `9d492e745a4e55f91a279a42bbda2a6eaebc8da2df6d8490ecfbd269854801c7`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 决策 / 施工 / 会审补档（第 19 期入库）；基线 HEAD = `45b2c6a`。

# R-149 历史恢复工具结果丢失 + 空白消息块 · 独立会审报告（DSPro 方）

- 会审对象：`r137-guard/会审卡-R149.md`，sha256 = `aabe89b379210175ea35f583340360c0769e1049b3430b587b2abc69c0030700`，与任务书给定值**一致**（sha256sum 实测）。
- 基线核实：主仓 `<MAIN_REPO>` HEAD = `45b2c6abd4eaf0c1a23ea8bc6bfa25ac1d233837`（= 45b2c6a），与卡声明一致。**全程以 `git show HEAD:` 读源码，规避工作树在途施工**（task.ts/polling.ts 已被他批改动）。
- 署名：实际运行模型 **DeepSeek（deepseek-v4-pro，经 Kimi Code 主代理）** · 会话 id **DC-19** · 日期 2026-10-12。
- 评议方式：卡外独立实读（MessageJsonCodec.java / stores/ai.ts / AiPanel.vue / parts-residue-cleanup.spec.ts），无其它会审方结论参考。

## 逐条评议

**H1 修复路线 = 前端 loadHistory 合并 —— 采纳（路线取向正确）。** 病灶已实读坐实：`MessageJsonCodec.toNode`（HEAD:97-117）对 AssistantMessage 只写 `toolCalls`、对 ToolResponseMessage 只写 `toolResponses`，`serialize` 一条消息一个 LIST 项，故后端天然产出 `[assistant(toolCalls), tool(toolResponses)]` 分条。**改后端格式是错误方向**：该分条是 Spring AI `List<Message>` 的忠实序列化（续跑要靠 `deserialize` 还原同一消息列表，模型侧也是 assistant→tool 两跳），合并存储会破坏往返保真且触发 Redis 历史兼容。前端 loadHistory（HEAD:678-710）本就在建 `parts`，两遍扫描（先建 `toolResponses[].id→responseData` 索引、再回填 assistant 段并跳过 TOOL 条目）是**展示层**增量，层正确、风险低。**结论：无理由改后端面，采纳 H1。**

**H2 配对规则 —— 采纳（附两处须精规格）。** `call.id` 真值才按 id 匹配、为空退回序位、仍不中 result=null 但卡片保留、"一条 ASSISTANT 可多 call / 一条 TOOL 可多 response、严禁假设 1:1 相邻"——四条均正确（实读：现 `result` 行本就是 `find(response.id === call.id)`，ASSISTANT 条目上 `toolResponses` 恒 undefined 故恒 null，正是病灶）。附注：
- **序位兜底须定义精确算法**：多 call 无 id 或多 response 未配对时，"按序位"必须是"未配对 call 集合与未配对 response 集合按序互配"，且须说明**部分有 id、部分无 id** 混排时的先后（先 id 匹配、剩余未配对者再序位）——否则多 call 无 id 场景会错配。
- **"id 为真"须明确三态**：null / undefined / `''` 均视为空走序位，与 `toolCallId: call.id ?? history-…` 的兜底口径一致。

**H3 空白块消除 —— 采纳。** 跳过 TOOL 条目即消除空白气泡（实读：roleOf HEAD:308-318 对 TOOL 兜底 'assistant'，ToolResponseMessage text 空 ⇒ 空白泡）。roleOf 不加 TOOL 分支合理：roleOf 仅 loadHistory 消费、跳过后 TOOL 类型到不了 roleOf，加分支即死代码。

**H4 失真点澄清 —— 采纳。** "前缀位置丢失"不成立，代码机制可证：工具轮 `AssistantMessage.getText()` 恒空，`MessageJsonCodec` 存 `content=getText()`（HEAD:99）即空，无前缀文本可丢；配合 SpToolCallingManager 内部工具轮不向客户端 emit 中间轮，结论一致。按 H4 修正 issue 表述正确。

**H5 测试 —— 采纳。** 假形状夹具确在 `parts-residue-cleanup.spec.ts:577-610`（写点⑥ 夹具把 `toolCalls`+`toolResponses` 写**同一条** ASSISTANT，后端不可能产出，绿灯掩盖属实）。改真实双条目 + 新增五类用例（一对多配对/连续多轮/id 缺失序位兜底/responseData=null/无主 response 丢弃）覆盖到位。**建议补一条**："历史分支 vs 镜像分支 parts 深度相等"断言，锁住合并形状与 live 会话形状一致（见 R3）。

**H6 边界红线 —— 采纳（附 index 语义须明确）。** 四条红线正确：不动 `status:'succeeded'`（历史载荷无状态，属 R-141②/R-144 后端面，范围纪律正确）；回填后走 deriveProjections；requiresAction=false 不复活挂起卡；`history-${index}` 命名空间保留 ASSISTANT 自身 index。附注：跳过 TOOL 条目后，`history-${index}` 的 `index` 是**原始数组下标**还是**合并后下标**未定——若用原始下标会留 id 空洞（history-0/2/4…），虽唯一性无碍，但与前端的 action 记录回填、镜像侧 id 对齐可能漂移；施工卡应显式锁定其一。

**H7 issue 处置 —— 采纳。** DC-21 三件套（评论附数据→关闭→存证后删除）为流程项，无技术风险。

## 新增风险点与漏项

- **R1（H2 序位兜底歧义）**：部分 call 有 id、部分无 id 混排时，"先 id 匹配、剩余未配对者序位互配"的先后必须写死，否则错配。
- **R2（H6 index 语义漂移）**：跳过 TOOL 条目后 `history-${index}` 下标口径未定，影响 toolCallId 兜底命名空间与镜像/action 记录侧一致性。
- **R3（H1 形状一致性）**：合并产物须与镜像（live 会话）parts 形状严格一致（seg 编号、toolCallId、result 回填），否则"刷新前后逐块一致"验收可能因微差翻车——建议 H5 补深度相等断言。
- **R4（跨轮 id 撞号）**：两遍扫描的全局 `id→responseData` 索引假设 id 唯一；若异常历史存在跨轮重复 id，last-wins 会错配。正常历史 toolCallId 唯一，属低风险，建议施工卡注明"重复 id 取后出现者"或按轮分组。

## 结论

H1/H2/H3/H4/H5/H6/H7 全部采纳（H2/H6 各附规格化要求，H5 建议补一断言）。H1 前端合并路线正确、无理由改后端面；H2 配对规则正确但序位兜底与 id 三态须施工卡精规格。新增风险 R1~R4 均为施工细节级，不构成改判。
