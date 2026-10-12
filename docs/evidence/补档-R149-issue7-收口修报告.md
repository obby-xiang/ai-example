<!-- 第 20 期补档入库 · R-149（issue#7） · 已脱敏 -->
> **补档说明**：源件 = `<SCRATCH>\r137-guard\收口修报告-R149.md`（2793 B，sha256 `79bd5f5e0949a1ffc27ba944a6e059b232ba534211ddfa71da560f6810884fcf`）；除本说明块外，正文与源件逐字一致（零改写），仅做脱敏替换。
> 脱敏占位口径：`<MAIN_REPO>` = 主仓库根 · `<REPO_ROOT>` = 同族其它仓根 · `<SCRATCH>` = 仓库外工作/证据目录 · `<KIMI_HOME>` = 用户 `.kimi-code` 目录 · `<USER>` = 用户家目录/用户名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址。
> 口径注记：本件为 **R-149（issue#7）** 报告补档（第 20 期入库）；基线 HEAD = `0f46286`。本期**审查锚** = `3f7976448f51a6288139a367a65370a45bfb478344e7e486d90d0d6137a42dee`（18538 B / 2 段 / +331−19，复核与红队据其出报告）；本期**入库锚** = `54c942764ee80f7e1c62fee74e2e770a8c1feabb9083c1174ca770d01b359150`（21915 B / 2 段 / +396−19，收口修 C1~C3 后重锚）。两 SHA **不可混用**。

# 收口修报告 · R-149（C1~C3，禁扩面）

## 署名（DC-19 实证）

- 实际运行模型：`kimi-code/kimi-for-coding`（profile=agent，thinkingEffort=high）。
- CU 会话 id：`session_856bb412-5a36-4c00-b41b-610cd406fe01`。
- wire modelAlias 实证：本会话 `agents/main/wire.jsonl` 累计 192 条 `"modelAlias":"kimi-code/kimi-for-coding"`（2026-10-12 复核，首条 `profile.bind`，agentId=main）。
- 收口修卡 sha256 核验：`8f98d355…185f7`，与卡面一致。

## C1（复核 m-1，必修）：序位兜底后重派生投影

- 病灶实证：序位兜底块在 flatMap 内 `deriveProjections` **之后**写 `part.result`，投影按值快照（parts.ts deriveProjections）⇒ 兜底命中路径 `toolRuns[].result` 滞留 null。已做移除重派生的反向验证：用例③的 toolRuns 断言**转红**，恢复后转绿——m-1 实锤闭环。
- 修法（ai.ts，一行级）：兜底循环后对含工具段的受影响消息重跑 `deriveProjections`（幂等纯函数，契约「段字段落段后须同步派生投影」）。
- 测试补强（复核建议 a 采纳）：用例③补 `toolRuns[].result` 投影断言（`['ra', null]`，与 parts 一致）。

## C2（红队 O-1，注释级）：防御性 tie-break

- ai.ts 索引构建处注释：「跨轮重复 id 取后出现者」改为防御性 tie-break 口径（后端 Spring AI toolCallId 唯一、实际不产重复 id；后出现覆盖仅确定性兜底，不宣称符合后端实际产出序）。
- spec 用例④用例名与两条断言注释同步改写；断言语义未动。

## C3（红队 O-3，测试补强）：序无关正确性补测

新增 describe「两遍扫描序无关性补测」2 用例：
- TOOL 在 ASSISTANT 前 + 多 TOOL 夹一条 ASSISTANT：响应先于调用照样配对、无主响应丢弃、id 锁原始下标含空洞（history-1/3）、零空白块；
- 全 TOOL 历史：零消息、不抛异常。

## 登记不修（提交官落 M2 注记）

- 红队 O-2：镜像回归用例用静态 sessionStorage 模拟，未覆盖 writeMirror 300ms 防抖真实时序交错面（镜像写盘非本批改动面）。

## 验收（复跑实测）

- vitest：**18 文件 / 212 测试全绿**（210 → 212，+2 = C3；用例③为断言补强不计新增）。注：首跑 restore-reconcile 负例①（真实计时敏感用例）一次 flaky 失败，单跑与全量复跑均绿，与本批改动画无关（改动面仅 ai.ts 历史合并分支 + 本 spec）。
- typecheck：绿；`scripts/ci/check-docs.mjs`：**6/6**。
- 改动面 = 仅 `frontend/src/stores/ai.ts`（C1 重派生 + C2 注释）+ `frontend/tests/parts-residue-cleanup.spec.ts`（C1 断言/C2 文案/C3 用例）；禁 commit（HEAD 仍 0f46286，工作树 2 M 与在制品一致）。
