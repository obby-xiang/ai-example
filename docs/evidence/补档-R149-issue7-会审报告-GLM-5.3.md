<!-- 第 19 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\会审-R149-GLM.md`（6532 B，sha256 `9072583decd2171e2f6364bd75d49b59fc31beeee1a82bad23eb1a5366d421b2`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 决策 / 施工 / 会审补档（第 19 期入库）；基线 HEAD = `45b2c6a`。

# R-149 决策卡 · 独立会审报告（GLM 方）

- 会审对象：`r137-guard/会审卡-R149.md`，sha256 = `aabe89b379210175ea35f583340360c0769e1049b3430b587b2abc69c0030700`，与任务书给定值**一致**（sha256sum 实测）。
- 基线核实：主仓 HEAD = 45b2c6a（实测）。任务点名的三个文件（stores/ai.ts / AiPanel.vue / parts-residue-cleanup.spec.ts）经 `git diff --stat HEAD` 确认**无在途改动**，工作树读取即 HEAD 态；MessageJsonCodec 经 `git show HEAD:` 读取（路径实为 `ai/memory/`，卡未写路径仅行号，内容吻合）。
- 署名：实际运行模型 **GLM（Zhipu AI GLM-5.3，经 Kimi Code 主代理）** · 会话 id **DC-19** · 2026-10-12。
- 病灶复核：三锚点全坐实——①codec `toNode` 分条互斥（AssistantMessage 只写 toolCalls、ToolResponseMessage 只写 toolResponses），一条消息一个 LIST 项；②`ai.ts:678-710` 逐条映射 + `roleOf:309-318` 兜底 assistant ⇒ TOOL 条目成空白气泡；`:693` 在 ASSISTANT 条目上 `item.toolResponses ?? []` 恒 undefined ⇒ result 恒 null ⇒ `AiPanel.vue:208` `v-if="part.result"` 不渲染；③夹具 `:577-610` 确把 toolCalls+toolResponses 写同一条 ASSISTANT（后端不可能产出的假形状），绿灯掩盖属实。

## 逐条评议

**H1 前端 loadHistory 合并路线 —— 采纳（会审重点）。** 前端合并是正确取向，理由三点：①存储格式本身无病——分条互斥恰是 Spring AI 消息模型的忠实序列化（一个 Message 一个条目），病在消费端把"一条消息"误当"一条聊天记录"；②改后端格式（合并写或新增字段）只惠及新会话，旧 Redis 历史仍需兼容读取，等于两个问题都要解，而前端合并**一次修复全部存量与增量**；③前端改动面收敛于 loadHistory 单函数 + 测试，不触 Redis/序列化层（该层另有 R-141②/R-144 在排）。两遍扫描（先建 id→responseData 索引再回填）结构正确：TOOL 条目在时序上后于其 ASSISTANT 条目，先索引后消费无顺序依赖。**补充一个卡未列的前置确认**：loadHistory 仅在 sessionStorage 镜像缺失/过期时执行（镜像存的是已合并 ChatMessage），修复只影响无镜像路径——建议施工时加一条"镜像路径刷新结果不变"回归用例，防止改合并逻辑时误伤镜像写入面。

**H2 配对兜底规则 —— 采纳，附两点细化（会审重点）。** "id 为真按 id、为空退序位、不中保卡片不丢、严禁假设 1:1 相邻"四原则正确（一条 TOOL 可含多 response、一条 ASSISTANT 可含多 call 均与 codec 结构吻合）。细化建议：①**纯序位匹配有误配风险**——id 缺失且并行多工具时，第 k 个 response 配第 k 个 call 可能跨工具错配；response 载荷带 `name` 字段（codec 实测写入 id/name/responseData），建议兜底序位匹配加 **name 相等闸**（同 name 内再按序位），name 也不符才 result=null；②**重复 id 消费语义须定义**：同 id 多 call 或多 response 同 id 时，建议"首次未消费命中即消费"（consume-on-match），避免同一 response 回填到两个 call 造成重复结果区。这两点是实现细节级补充，不影响 H2 方向成立。

**H3 空白块消除 —— 采纳。** 跳过 TOOL 条目即根治（空白块本体就是 TOOL 条目被映射成无内容 ASSISTANT 气泡）。roleOf 不加 TOOL 分支：实测 roleOf 全仓消费点仅 `:679` 一处（loadHistory 内），跳过 TOOL 后永不传入 'TOOL'，加分支即死代码，同意卡的倾向。附带：跳过后条目 id 建议沿用**原始数组下标**（而非压缩后下标），保证多次刷新 ID 稳定——`:688` 的 `history-${index}-${name}` 兜底与 `:698` 的 `history-${index}` 都吃这个 index。

**H4 失真点澄清 —— 采纳，附一句边界说明。** 仓内证据核实属实：S42-P1 §4.4-1 明证官方内部工具循环不向客户端流 emit 中间轮（OpenAiChatModel.internalStream 仅递归新请求），故"前缀位置丢失"无事实基础，真实失真 = 一轮拆多气泡 + 夹空白泡（后者由 H1/H3 消除，前者是分条存储的固有形态、合并后表现与实时渲染一致）。**边界说明**：该证据是 OpenAI 系模型路径的行为；若后端接其它适配器且其 emit 行为不同，此结论须复核——建议 issue 回复中表述为"当前模型路径下不成立"而非绝对化。按修正表述回复 issue 正确。

**H5 测试 —— 采纳，补两项。** 假形状夹具改真实双条目 + 五类新用例（一对多/连续多轮/id 缺失序位/responseData=null/无主 response 丢弃）覆盖面好。**补**：①若采纳 H2 细化，加"name 闸序位兜底"与"重复 id consume-on-match"用例；②上文镜像路径回归用例。基数同步与全绿口径无异议。

**H6 边界红线 —— 采纳。** 四条红线全部正确且必要：`status:'succeeded'` 是 R-141② 已申报的占位口径（`ai.ts:670-677` 注记明文"任何后续批次不得解读为丢 unknown 可接受"，修复属 R-144 后端面）；回填走 deriveProjections（:708 现路径，保持）；requiresAction=false（历史无待决语义）；`history-${index}` 命名空间保留。**建议追加一条**：merge 逻辑只动 loadHistory 的历史分支，不得反向渗入实时增量路径（live 段的 result 回填有自己的协议帧通道）。

**H7 issue 处置 —— 采纳。** DC-21 三件套（评论附数据 → 关闭 → 存证后删除）为既定流程，无技术异议；提醒评论中失真点表述按 H4 修正版执行。

## 新增风险点与漏项汇总

1. H2 纯序位兜底的跨工具误配风险——建议 name 闸（见 H2 细化①）。
2. 重复 id 的消费语义未定义——建议 consume-on-match（见 H2 细化②）。
3. 镜像路径（sessionStorage）与 loadHistory 的交界回归未列——补用例。
4. 跳过 TOOL 后 `history-${index}` 建议用原始下标保稳定。
5. H4 证据为 OpenAI 系路径，issue 回复措辞宜限定"当前模型路径"。
6. 小项：卡未写 codec 实际路径（`ai/memory/MessageJsonCodec.java`），施工卡补全便于锚定。

## 总结论

H1/H3/H4/H5/H6/H7 采纳；H2 采纳附两点细化（name 闸序位兜底 + 重复 id 消费语义）。无驳回项——本卡病灶复核全部属实、路线选择正确，上述 6 项漏项均为细节级补强，落施工卡后可开工。
