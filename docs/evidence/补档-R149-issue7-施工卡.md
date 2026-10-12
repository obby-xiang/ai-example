<!-- 第 19 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\施工卡-R149.md`（3706 B，sha256 `b8aeb29b1559641fb586a427570abef8dbede5ca8beb8ab0a0bf9f4fff4b3437`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 决策 / 施工 / 会审补档（第 19 期入库）；基线 HEAD = `45b2c6a`。

# 施工卡 · R-149 历史恢复工具结果丢失 + 空白消息块（K2.8 Preview 施工，TDD——前端任务按面分轴；2026-10-12 用户直裁：前端直接 K2.8、后端直接 GLM-5.3、难度次之）

- 决策依据：`<SCRATCH>\r137-guard\决策卡-R149-定稿.md`（H1~H7 + 会审细化，K3 代签，零驳回）。
- **开工条件：手动刷新批闭环提交之后**（串行防撞车）；开工时按当时 HEAD 重取全部行号锚点。
- 主仓库 = <MAIN_REPO>（唯一可操作；严禁碰 trae 线两目录）。**施工期间禁 commit**；禁读 .env；机械读取/扫描下沉子智能体（显式钉 model=DeepSeek/deepseek-flash）。

## S1 loadHistory 合并改造（唯一施工面，frontend/src/stores/ai.ts :603/:678-710 一带）

1. **两遍扫描**：第一遍遍历 history.messages，对 TOOL 类型条目建索引 `Map<response.id, responseData>`（consume-on-match：命中即从索引摘除；跨轮重复 id 取后出现者并注释注明）；第二遍构建 ChatMessage：
   - ASSISTANT 条目：toolCalls 段回填 `result = 配对(responseData)`，配对规则 = ①call.id 三态为真（非 null/undefined/''）按 id 匹配 ②未中退序位兜底：**未配对 call 集 × 未配对 response 集按序互配 + name 相等闸**（call.name === response.name 才配）③混排先 id 后序位 ④仍不中 result=null **卡片保留不丢**；
   - **TOOL 条目跳过**（不生成 ChatMessage）；
   - USER/SYSTEM/纯文本 ASSISTANT 条目照旧。
2. `history-${index}` 的 index **锁死 = 原始数组下标**（含被跳过条目的空洞，不得用合并后下标）。
3. 回填后必走 `deriveProjections`；`requiresAction` 保持 false（不得复活挂起卡）；合并逻辑**只在 loadHistory 历史分支**，实时增量路径零改动。
4. **不得动** `status:'succeeded'` 硬编码（R-141②/R-144 后端面，误改即新增失真）。
5. roleOf(:309-318) 不加 TOOL 分支。
6. 残缺历史（TTL/裁剪）无主 response：兜底丢弃，不报错不抛异常。

## S2 测试（先写失败测试）

- `frontend/tests/parts-residue-cleanup.spec.ts:577-610` 假形状夹具（toolCalls+toolResponses 同条 ASSISTANT）改为**真实双条目**（ASSISTANT 带 toolCalls + 紧随 TOOL 带 toolResponses）。
- 新增用例：①一对多配对（一条 ASSISTANT 多 call ↔ 一条 TOOL 多 response）②连续多轮工具（TOOL 只吃自己那份）③id 缺失序位兜底（含 name 闸：name 不同不配）④重复 id consume-on-match ⑤responseData=null（result=null 卡片仍在）⑥残缺无主 response 丢弃 ⑦**镜像路径回归**：同一历史分别走 loadHistory 与 sessionStorage 镜像路径，parts 深度相等 ⑧空白块消除断言（TOOL 条目不产生 ChatMessage、消息条数 = 刷新前）。
- 参考触点（行号以开工时 HEAD 重取）：types/ai.ts:139-146（id 对齐字段两边都叫 `id`）/:184-215 ChatMessage/:243-268 ToolPart；utils/parts.ts:29-31/69-74/90-103/178-217；渲染无需改（AiPanel.vue:208 v-if="part.result" 自动生效）。

## 验收口径

1. vitest 全绿（基数 = 手动刷新批闭环后基数 + 本批新增；施工报告记录新旧基数）。
2. typecheck / build / `scripts/ci/check-docs.mjs` 6/6 全绿。
3. 手工或桩验证：含工具调用对话刷新后与刷新前逐块一致——无空白块、结果区可见、条数不变。
4. 施工报告 ≤8KB 落 `<SCRATCH>\r137-guard\施工报告-R149.md`，首页署名**实际运行模型 + CU 会话 id + wire modelAlias 实证**（DC-19，照抄卡头不合格）；行号锚点全部按开工时 HEAD 重取并在报告注明。
