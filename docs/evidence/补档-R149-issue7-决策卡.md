<!-- 第 19 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\决策卡-R149-定稿.md`（3098 B，sha256 `73cadec374d79eef81510e99bd1c46bf2d517ecd66a1b7b945ca233c87e65547`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 决策 / 施工 / 会审补档（第 19 期入库）；基线 HEAD = `45b2c6a`。

# R-149 历史恢复工具结果丢失 + 空白消息块 · 决策卡定稿（K3 代签）

- 来源：GitHub issue #7（用户亲报，严重度高）；独立复核 agent-601；三方会审：GLM（`会审-R149-GLM.md` 6532B sha 9072583d…）+ DSPro（`会审-R149-DSPro.md` 5826B sha 9d492e74…），**H1~H7 全部采纳、零驳回**。
- 基线 HEAD = 45b2c6a；队列位 = 手动刷新批之后、R-137 之前。

## 定稿 H1~H7（含会审细化吸收）

- **H1 前端 loadHistory 合并路线**：病在消费端不在存储（分条互斥 = Spring AI 消息模型忠实序列化，续跑靠 deserialize 还原，改后端破坏往返保真 + Redis 兼容，DSPro 实证否决后端面）。两遍扫描：先建 `toolResponses[].id → responseData` 索引，再在 ASSISTANT 条目建段时回填 result 并跳过 TOOL 条目。**GLM 补前置**：loadHistory 仅在 sessionStorage 镜像缺失/过期时执行——须加「镜像路径刷新结果不变」回归用例。**红线追加（GLM）**：合并只动 loadHistory 历史分支，不得渗入实时增量路径。
- **H2 配对规则（精规格）**：①`call.id` 三态为真（非 null/undefined/''）才按 id 匹配；②无 id 或 id 未中时退**序位兜底：未配对 call 集 × 未配对 response 集按序互配，且加 name 相等闸**（GLM：防跨工具误配）；③部分有 id 部分无 id 混排时**先 id 后序位**（DSPro）；④重复 id 采 **consume-on-match**（GLM），全局索引跨轮重复取后出现者并注释注明（DSPro R4，低风险）；⑤仍不中 result=null 但**卡片必须保留不丢**；⑥严禁假设 1:1 相邻。
- **H3 空白块消除**：loadHistory 跳过 TOOL 条目；roleOf 不加 TOOL 分支（加即死代码，双方一致）；`history-${index}` 锁死 = **原始数组下标**（双方一致，防 id 空洞与镜像/action 回填漂移）。
- **H4 失真点澄清**：issue 第三失真点不成立（工具轮 ASSISTANT getText() 恒空，无前缀可丢；证据 S42-P1:388-392 等）；issue 回复限定语「当前模型路径下不成立」（GLM 边界）。
- **H5 测试**：假形状夹具（parts-residue-cleanup.spec.ts:577-610）改真实双条目；用例 = 一对多配对、连续多轮、id 缺失序位兜底（含 name 闸）、重复 id consume-on-match、responseData=null、残缺无主 response 丢弃不报错、**历史分支 vs 镜像分支 parts 深度相等断言**（DSPro R3，锁「刷新前后逐块一致」）。
- **H6 边界红线**：不动 status:'succeeded' 硬编码（R-141②/R-144 后端面）；回填后必走 deriveProjections；requiresAction 保持 false；合并逻辑不渗实时路径。
- **H7 issue 处置**：闭环后 DC-21 三件套（评论附数据按 H4 修正版表述 → 关闭 → 存证 <SCRATCH>\issue-archive\ 后删除）。

## 验收口径

vitest 新增/改用例全绿；typecheck/build/check-docs 6/6 全绿；验证：含工具调用对话刷新后与刷新前逐块一致（无空白块、结果区可见、条数不变）；施工期间禁 commit，commit 归提交官。
