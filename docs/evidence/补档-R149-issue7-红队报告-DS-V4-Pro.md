<!-- 第 20 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\红队报告-R149.md`（4594 B，sha256 `72ad0a291ccfaf3f37cb004360a91f0efa331b3b7ac02cefb40c8a3e9aa80584`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 报告补档（第 20 期入库）；基线 HEAD = `0f46286`。本期**审查锚** = `3f7976448f51a6288139a367a65370a45bfb478344e7e486d90d0d6137a42dee`（18538 B / 2 段 / +331−19，复核与红队据其出报告）；本期**入库锚** = `54c942764ee80f7e1c62fee74e2e770a8c1feabb9083c1174ca770d01b359150`（21915 B / 2 段 / +396−19，收口修 C1~C3 后重锚）。两 SHA **不可混用**。

# 红队报告 · R-149 历史恢复工具结果丢失 + 空白消息块（DS V4 Pro 推翻式审查）

- 核验：红队卡 sha256 `276b0c77…aaa8` ✓；施工卡 `b8aeb29b…b3437` ✓；diff 锚 `3f797644…42dee` ✓。
- 零夹带：`git diff`（工作树 2 M 文件 vs HEAD `0f46286`）= 18538B，sha256 与锚 cmp EQUAL；未触碰 trae 红线目录，未改工作树。
- 署名（实测）：实际运行模型 **DeepSeek/deepseek-v4-pro（经 Kimi Code 主代理）** · 会话 id **session_45401319-a996-41b5-bac5-c2efa213e438** · wire modelAlias = 本会话 `agents/main/wire.jsonl` 首条 `profile.bind{modelAlias:"DeepSeek/deepseek-v4-pro"}`（grep 去重，非照抄卡头）。
- 结论：**PASS 无条件**（0 MAJOR / 0 m / 3 O）。

## 攻击面 1 配对逻辑错配 —— 不成立（健壮）
- **id 在场（生产常态）**：两遍扫描序无关——`responseIndex` 第一遍全建、`flatMap` 第二遍跳过 TOOL 并回填；consume-on-match（`responseIndex.delete`，ai.ts:715）+ name 闸。逐一追踪：TOOL 在前/多 TOOL 夹 ASSISTANT/无主 TOOL/空 history/全 TOOL/连续多轮，配对均正确。
- **id 全缺失**：序位兜底（ai.ts:754-764）name 相等闸 + 序保持 call↔response 对应，well-formed 下仍正确；malformed 下 best-effort（H2 已授权"仍不中 null 卡片保留"）。name 闸 exact 大小写敏感（Spring AI 工具名本就敏感，正确）。
- 无 MAJOR。唯一退化面 = 重复 id/同 name 多 call 无 id，属后端不可能产出的形态，且后果仅为卡片 result 文案错（非数据损坏）。

## 攻击面 2 index 锁死 —— 不成立
- index 锁原始数组下标（含 TOOL 空洞 → `history-0/1/4`，spec⑧ 断言）；`message.id` 唯一，无 key 复用冲突；消费方（v-for key/镜像/action 回填）把 id 当不透明唯一串，跳号不影响排序。

## 攻击面 3 镜像路径 —— 不成立
- 镜像存 live 会话**合并形态**（无 TOOL 条目），与后端合并后形态一致；spec⑦（:861）锁"后端合并↔镜像回灌"parts 深度相等、结果不丢。R-149 不触碰镜像写盘/startMirror，无回归。

## 攻击面 4 空白块消除真实性 —— 不成立（修复真实）
- TOOL 条目 `return []` 零消息产出 → 空白 assistant 块消除；id 命中回填 result → `v-if="part.result"` 结果区恢复；未中 result=null 的卡片仍渲染 header+args+status（**非空块**）。对照 issue#7 两诉求（结果区可见 + 无空白块）均成立。

## 攻击面 5 测试充分性 —— 基本充分
- 8 用例覆盖：一对多/多轮/id 缺失+name 闸/重复 id/responseData=null/无主丢弃/镜像回归/空白块+index 锁死。漏测但代码已正确处理：TOOL 在前、空 history、全 TOOL、多 TOOL 夹 ASSISTANT（两遍扫描序无关的正确性目前靠实现而非用例兜底）。

## 攻击面 6 零扩面 —— 通过
- diff 仅 2 文件（`stores/ai.ts` + `parts-residue-cleanup.spec.ts`），+331/−19 与锚一致。`status:'succeeded'` 硬编码未动、`requiresAction:false` 未动、`roleOf` 无 TOOL 分支未动、实时增量路径零改动、`deriveProjections` 保留、渲染层未动。

## 问题清单（分级带落点）

- **O-1（注释口径/防御性语义被锁为期望）**：`frontend/src/stores/ai.ts:691` 与 `frontend/tests/parts-residue-cleanup.spec.ts:789`（用例④）——"跨轮重复 id 取后出现者"被 spec 锁为期望，但**后端不产重复 id**（Spring AI toolCallId 唯一），该语义是防御性 tie-break 而非"后端实际产出序"。若未来真实复用 id，round1 call 会拿 round2 response（错配）。建议注释改"防御性 tie-break"，不宣称符合后端序。
- **O-2（测试保真）**：`frontend/tests/parts-residue-cleanup.spec.ts:861`（用例⑦）——镜像回归用静态 `sessionStorage.setItem` 模拟镜像，非真实 `writeMirror` 300ms 防抖时序；等价性结论可信，但未覆盖真实写盘时机交错面。
- **O-3（覆盖空隙）**：`frontend/src/stores/ai.ts:683-766` 的序无关正确性（TOOL 在前/空 history/全 TOOL/多 TOOL 夹 ASSISTANT）未入 spec，建议补测锁死，防未来重构回归。

## 结论

**PASS 无条件。** 修复正确且零扩面：病在消费端的前端合并路线落地无误，配对逻辑对生产形态（id 唯一）完全正确、对退化形态 best-effort 且已授权；空白块消除与结果区恢复真实成立；测试基本充分。3 项 O 均为注释口径/测试保真/覆盖空隙级，不构成放行阻断。
