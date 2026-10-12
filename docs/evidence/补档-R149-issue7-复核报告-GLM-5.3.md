<!-- 第 20 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\复核报告-R149.md`（5252 B，sha256 `04074a48a468684fbd02c2d08d74e86c92296ec0c8f6a5b7f541b89c629a72e4`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 报告补档（第 20 期入库）；基线 HEAD = `0f46286`。本期**审查锚** = `3f7976448f51a6288139a367a65370a45bfb478344e7e486d90d0d6137a42dee`（18538 B / 2 段 / +331−19，复核与红队据其出报告）；本期**入库锚** = `54c942764ee80f7e1c62fee74e2e770a8c1feabb9083c1174ca770d01b359150`（21915 B / 2 段 / +396−19，收口修 C1~C3 后重锚）。两 SHA **不可混用**。

# 复核报告 · R-149（issue#7 历史恢复工具结果丢失+空白消息块）

- 署名（DC-19 实证）：实际运行模型 **GLM（Zhipu AI/glm-5.3，thinkingEffort=high）** · 会话 id **`session_6cf39cff-ce68-4bff-ad0c-3146d7136f58`** · wire 实证 = 本会话 `agents/main/wire.jsonl` `llm.request` `"model":"glm-5.3","modelAlias":"Zhipu AI/glm-5.3"`（亲读，非照抄卡头）。
- 输入件核验：复核卡 `9293995b…e176` ✓、施工卡 `b8aeb29b…4b3437` ✓、审查锚 `3f797644…2dee` ✓；施工报告 4498B ✓。仓库 HEAD=`0f46286` ✓；本复核零工作树改动、零 commit；红线两目录零操作。机械读取本轮量小（diff 2 文件），未下沉子智能体，亲读完成（如实申报）。

## 复核项 1 · 零夹带 — **PASS**

`git diff` 恰 2 文件（ai.ts / parts-residue-cleanup.spec.ts），与审查锚 `cmp` **逐字节 EQUAL**（18538B，段数 2=2）。

## 复核项 2 · 卡规符合性 — **PASS**

逐项对照施工卡 S1：两遍扫描（`responsePool`/`responseIndex` 先建 ai.ts:683-696）✓；consume-on-match（:715 命中即 delete，索引+pool 双记，consumedResponses 按对象身份判重 :697/:731）✓；name 闸（:758 `response.name ?? '未知工具' === unmatched.name`）✓；序位兜底循环后统一执行（:756-766）✓；result=null 卡片保留（:760-761 continue 不删段）✓；index 锁死原始下标（:741 `history-${index}` 用 flatMap 原始 index，含空洞）✓；deriveProjections（:751）✓；requiresAction:false（:728）✓；status:'succeeded' 硬编码未动（:725 原样）✓；roleOf 未加 TOOL 分支（diff hunk 仅 :32 import 与 :676-766 块，:309-318 零触达）✓；实时增量路径零改动（diff 范围核实）✓。S2：写点⑥夹具已改真实双条目（spec:583-595 ASSISTANT+TOOL 分离，:602 断言条数=2）✓；8 用例 ①~⑧ 全部在位（spec:685-930）✓。

## 复核项 3 · 独立复跑 — **PASS**

`yarn test` = **18 spec / 210 全绿**（EXIT=0，6.2s，与施工报告 202→210 吻合）；`vue-tsc --noEmit` EXIT=0；`check-docs` **6/6**（本批无文件删除，⑤ 天然过，无 stage 需求）。

## 复核项 4 · 代码审查 — **PASS（附 1 minor，见问题清单）**

配对规则正确：①id 三态为真判定 :712（null/undefined/'' 均排除）✓；②跨轮重复 id 索引覆盖式 set 取后出现者（:691-693 注释注明）✓；③无主 response 静默丢弃（pool 残项无异常路径）✓；④TOOL 零消息产出（:700-702 `return []`）✓；⑤空洞 index 不冲突（原始下标）✓。**一致性断言成立**：「序位兜底统一在循环后执行」实现正是「混排先 id 后序位」的全局序保障——所有 id 匹配在 flatMap 内先行消费，无 id / id 未中者进 unmatchedCalls 循环后互配，后出现 ASSISTANT 的 id 命中不会被先行序位抢占，与卡 H2③ 一致，施工报告说明准确。**发现 1 处投影滞后**（详见问题清单 m-1）。

## 复核项 5 · 测试充分性 — **PASS（附补强建议）**

8 用例真断真绿、锁定生产可达路径（Spring AI 双条目形态即 MessageJsonCodec 实产形态；④ 的「后出现者优先+后来者退序位」断言精确锁定 consume-on-move 语义；⑧ 断言 history-0/1/4 空洞锁死 + isBlankToolBlock 逐消息反断言，非永真）。**应补未补**：a) **toolRuns 投影断言**（⑦ 只断 parts 深度相等——若同时断 toolRuns 即可抓出 m-1，见下）；b) ASSISTANT 带 toolCalls 但 TOOL 条目整体缺失（截断历史→全 null 卡片保留，语义已被 ③beta 部分覆盖，建议显式化）；c) response.name 为 null 的 '未知工具' 闸路径未测（低危）。

## 复核项 6 · 行为基线对照 — **PASS**

`AiPanel.vue` 不在 diff 内，`v-if="part.result"`（:208/:294）原样未改；回填后 result 非空 ⇒ 结果区自动可见成立（写点⑥ :615 `result:'ok'` 断言 + 渲染门未动，链路闭合）；空白块消除断言有效（⑧ 条数=非 TOOL 数 + 反断言循环）。

## 问题清单

- **m-1（minor，收口修）序位兜底后未重派生投影**：`deriveProjections`（ai.ts:751）在 flatMap 内执行，而序位兜底 :765 在其后才写 `part.result` ⇒ 兜底命中路径（测试③alpha、④后来者）的 `toolRuns[].result` 滞留 null（parts.ts:198 按值快照）。**违仓内既定契约**——ai.ts:1442-1444（收口修 F4 红队 m-2）明文「段字段落段后须同步派生投影，否则 toolRuns 滞留旧值违『parts 唯一真值』契约」，本批为同类问题。**实际影响受限**：UI 工具卡走 parts（唯一真值）不受影响；镜像写盘虽存滞后 toolRuns（:298），但水合时 :628 全量重算自愈。修法 = 兜底循环后对受影响 message 重跑 deriveProjections（一行），建议随下批收口。

## 结论

**带条件 PASS**。复核项 1~6 全过、门禁全绿、零夹带、卡规逐项落实；唯一问题 m-1 为 minor（投影滞后，无用户可见影响、镜像自愈，有先例定级支撑），不构成本批打回理由，条件 = m-1 随下批收口修 + 补 toolRuns 投影断言。本批可交提交官。
