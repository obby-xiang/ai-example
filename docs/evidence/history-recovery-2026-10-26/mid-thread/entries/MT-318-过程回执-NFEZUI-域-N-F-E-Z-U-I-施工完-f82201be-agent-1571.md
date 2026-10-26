<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-318 · 过程回执 · NFEZUI 域（N/F/E/Z/U/I）施工完成 · 交班（f82201be / agent-1571）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m329` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 8 条有正文），末条有正文消息为 `m330`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `f82201be` / agent `agent-1571`（`main` = 主会话）；原字符 2,921（链内 1 段、合计 2,921）。
> **链号 / 类别**：`MT-318` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 46 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `10%`。
> **与末条归档（138 件）的关系**：末条 `m330`（2,456 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

# NFEZUI 域（N/F/E/Z/U/I）施工完成 · 交班

## 身份自证
读本会话自身 wire（`agents/agent-1571/wire.jsonl`）命中 `{"type":"profile.bind","agentId":"agent-1571","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}` —— **modelAlias = `DeepSeek/deepseek-flash`，profileName = `coder`**。

## 实跑（唯一口径）
```
cd frontend && npx playwright test --config e2e/playwright.nfezui.config.ts
→ 1 skipped / 47 passed (2.2m)，0 failed
```
skipped 仅 `E2E-Z06`（`test.fixme` 显式挂账缺陷，非静默跳过）。跑完 19130/19131/19198/19199/19201/19202 全部 free，无残留进程。六域无 @real 条目（蓝本「真模型」列仅 G-01/K-10/Q-02/C-13/X-07/V-14），无需 `--grep` 排除。

## 交付清单（编号沿用蓝本；批①已覆盖的 N-01/F-05/E-01/E-03 跳过，其中 E-03 我补齐了另三型）
- **N（10）**：N-02 骨架屏/互斥、N-04 删会话全链、N-05 刷新沿用、N-06 关页签新会话、N-07 双页签所有权、N-08 复制页签换会话、N-09 接管会话、N-10 滑动 TTL（Redis 先掉后回）、N-11 到期后发送、N-12 面板收起记忆。
- **F（9+1）**：F-01 赞全链、F-02 踩+excerpt、F-02b（补强）真鼠标 aria、F-03 幂等、F-04 反馈页四列、F-06 编辑重跑（真 `window.confirm`）、F-07「继续」入口、F-08 呼吸点/时间戳/操作行、F-09 长消息折叠、F-10 失败不落库。
- **E（9+E-03 补齐）**：E-02 骨架/空态互斥、E-03(quota/filter/generic)、E-04 通用卡非空壳、E-05 详情+行动、E-06 断流 vs 停止互斥、E-07 降级、E-08 池饱和 503、E-09 notice 单槽、E-10 ui_event 回执、E-13 空态矩阵。
- **Z（5）**：Z-01 table、Z-04 空视图、Z-05 截断、Z-02 text 视图、Z-03 未知 kind fallback（后两条用「真卡+契约合法视图值」合成，产品无该 kind 造口，文件头有声明）。
- **U（4）**：U-01 撤销行归属、U-02 撤销恢复原值、U-03 深度 1、U-07 不跨刷新/会话（真模块+真 DOM+真按钮；仅「帧→store」两跳合成，已在文件头声明）。
- **I（8）**：I-01 全局切换、I-02 键集对称、I-03 零 CJK、I-04 工具面、I-06 chips/aria、I-07 结果视图文案、I-08 切语言不丢状态、I-09 窄栏不溢出。

## 未承载（10 条，逐条理由）
N-03（需 1000 条历史夹具，无后端造口且超单例预算）；E-11（`conflict-table` 已成兼容回退，断言面过期）；E-12（需 >8000 字工具结果，无造口）；E-14（产品未 i18n 错误卡，写必红）；Z-06（**登记未通过**，见缺陷 1）；Z-07（需真实发布冲突数据）；Z-08（视图上限 20 行，无 200 行来源）；U-04/U-05（需真 SpreadJS 写入链，属 T 域）；U-06（产品未实现，见缺陷 8）；I-05（需 GF 400 issues 夹具，与 G 域重叠）。

## 缺陷（均未硬修：AiPanel.vue 与 stores/ai.ts 正被他人在改）
1. **Z-06** `resultView` 不随 `/history` 恢复（历史只带 toolCallStates）→ 刷新后视图丢失（P1，已 fixme 挂账）。
2. **E-14** `utils/error-card.ts` 的 `VARIANT_TEXT`/「本轮出错」硬编码中文。
3. **i18n 残留四处**：`AiPanel.vue:1534` `'可用'/'已降级'`；`utils/blocks.ts` `toolStatusLabelOf` 全表中文（en 卡头实显「已成功」）；呼吸点「工作中…」；工作区事件消息中文拼装。→ I-01/I-03 用「已知残留白名单 + 其余零 CJK」断言，出现新中文即红。
4. **F-10** 反馈失败静默（`submitFeedback` 吞错、不回退选中态、无失败提示）。
5. **F-02** 后端支持 `reason`，前端无入口未接线。
6. **F-07** 空正文错误轮无「继续」（按钮在元信息行内，`showMetaRow` 要求有正文）。
7. **N-04** `DELETE /history` 不清 `ai:ctx`（后端 note 自述）⇒ 断言改 `removedFromIndex`+`usedTokens` 保留。
8. **U-06** `stop()` 不清撤销点（蓝图「停止后撤销点清除」不成立）。
9. 两条测试口径经验：镜像在 `pagehide` 强制回写（刷新前删键无效，须 `addInitScript`）；同 context 的 `newPage()` 会继承 sessionStorage（「关页签重开」须新 context）。

## 新增文件（仅新建，未改任何既有文件，未 commit）
`frontend/e2e/13-nfezui.spec.ts`、`13b-nfezui.spec.ts`、`13c-nfezui.spec.ts`、`13d-nfezui.spec.ts`、`playwright.nfezui.config.ts`、`support/{harness-nfezui.ts,global-setup-nfezui.ts,global-teardown-nfezui.ts,helpers-nfezui.ts,nfezui-upstream.mjs}`。

另起基建的原因：共享 `support/harness.ts` 的运行目录/H2 数据/`state.json` 是进程内常量，并行会互相删数据面、互相按 pid 收尾（实测发生过一次误杀），故本域独占端口段与 Redis DB 13/14，并给 harness 加了 `ownerPid` 归属校验。
