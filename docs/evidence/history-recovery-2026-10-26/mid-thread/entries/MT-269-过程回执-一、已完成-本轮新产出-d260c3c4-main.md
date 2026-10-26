<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-269 · 过程回执 · 一、已完成（本轮新产出）（d260c3c4 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m5` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 21 条有正文），末条有正文消息为 `m30`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `d260c3c4` / agent `main`（`main` = 主会话）；原字符 2,542（链内 1 段、合计 2,542）。
> **链号 / 类别**：`MT-269` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m30`（8,585 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

两项并行准备都完成了。当前进展与接下来流程如下。

## 一、已完成（本轮新产出）

**1. M1 出口评审材料已起草**：`docs/M1-出口评审材料.md`（未提交，零 git 写操作）——六章：评审基本信息 / 出口标准逐项对照表（6 行，证据指针全部经真实存在性校验）/ 备查观察项（含建议处置）/ M2 候选清单摘要 / 评审议程 6 步 / 签字页。**注意**：独立复核状态列目前是「GLM-5.3 复核进行中，结论待填」占位，待 GLM 报告落库后回填，评审现场前必须回填完毕。

**2. 双 FormRenderer 冒烟帧格式已提取**（只读，未写文件）。子智能体核对源码后发现三处文档与现状的偏差，**以源码为准**：① 本项目**没有 WebSocket**（chat 是 fetch+SSE 流、reattach 是 EventSource），帧只能从 Network 面板复制 SSE `data:` 文本改造；② 双卡必须用 `handleFrame(frame, assistantId)` 的第二参数把两帧投给**两条不同消息**（投同一条只会就地覆写，永远一张卡）；③ 帧里 `runId` 实质必需，`timeoutSeconds/expiresAt` 可缺省。另：GFb 复核/红队文档中"问题 1（activeForm.runId 零读点）"在当前源码已修复落地，属文档时效问题，不影响冒烟预期，无需行动。

## 二、您现在可做的（可选、非阻塞）：双 FormRenderer 浏览器冒烟

前置：`cd frontend && yarn dev`（默认 http://localhost:5200），打开页面点头部「显示 AI 栏」，F12 进 console：

```js
// ① 取 store
const ai = document.getElementById('app').__vue_app__.config.globalProperties.$pinia._s.get('ai')
ai.expanded = true

// ② 造帧（RUN_ID/TOOL_CALL_ID 纯前端冒烟可任意 UUID 字符串）
const FRAME = {
  type: 'frontend_tool_request', runId: '<RUN_ID>', toolCallId: '<TOOL_CALL_ID>',
  name: 'generative_form', timeoutSeconds: 120, expiresAt: Date.now() + 120000,
  args: JSON.stringify({ form: { scenario: 'FILTER', title: '导出筛选条件', fields: [
    { key: 'keyword', label: '配置编码关键词', type: 'text', required: true },
    { key: 'limit', label: '最大行数', type: 'number' },
    { key: 'includeDisabled', label: '包含停用', type: 'boolean' },
    { key: 'asOfDate', label: '数据日期', type: 'date' },
    { key: 'level', label: '层级', type: 'enum', options: [{ value: 'high', label: '高' }, { value: 'mid', label: '中' }] },
    { key: 'tags', label: '标签', type: 'multi_select', options: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }] }
  ] } })
}

// ③ 同一 toolCallId 投给两条不同消息
const m1 = ai.appendAssistantMessage('（冒烟①）'); ai.handleFrame(FRAME, m1.id)
const m2 = ai.appendAssistantMessage('（冒烟②）'); ai.handleFrame(FRAME, m2.id)
```

**肉眼核对清单**：

| 看什么 | 预期（纯视觉重复即通过） |
|---|---|
| 消息流 | 2 条助手消息各挂一张表单卡，六型控件都完整渲染 |
| 倒计时 | 两卡同刻同值跳变（共享同一 `activeForm.deadline`） |
| 填值 | 卡①填值卡②**不同步**（组件本地值），但 loading/错误/取消态两卡一致 |
| 点卡①「取消」 | 两卡同时收起为「表单已取消」tag；Network 见一次 `frontend-tool-result` POST（无真实挂起条目时回 409 `UNKNOWN_TOOL_CALL`，卡内提示文案出现，属预期） |
| 清理 | `ai.resetSession()` 后刷新 |

结果只需告诉我一句话（如「双卡可见、行为一致，纯视觉重复」或异常描述），我裁定是否记 M2。

## 三、接下来的主流程（等 GLM）

1. **GLM-5.3 复核报告落盘**（`docs/evidence/M1-出口复核-GLM-5.3.md`）→ 您把结论转我或让我直接读；
2. 我做**结论级验收**（逐项对照出口标准，不符合项裁决处置）；
3. 派**提交官子智能体**：机械验收 + 脱敏 + 回填评审材料复核列 + 细分提交 + push；
4. 您主持 **M1 出口评审**（用 `docs/M1-出口评审材料.md`），**人签字**——唯一不可替代的节点。

现在没有必须您做的事；冒烟可做可等，主线程等 GLM 报告即可。
