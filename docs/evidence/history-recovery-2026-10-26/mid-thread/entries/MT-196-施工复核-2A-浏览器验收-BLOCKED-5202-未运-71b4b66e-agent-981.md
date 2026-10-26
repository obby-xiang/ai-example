<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-196 · 施工复核 · 2A 浏览器验收：BLOCKED（5202 未运行）（71b4b66e / agent-981）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiPanel.vue`、`FormRenderer.vue`、`docs/demo`。
> **稿态**：历史中间稿 —— 该 wire 第 `m52` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt1` 条有正文的 assistant 消息（共 2 条有正文），末条有正文消息为 `m309`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `71b4b66e` / agent `agent-981`（`main` = 主会话）；原字符 2,518（链内 1 段、合计 2,518）。
> **链号 / 类别**：`MT-196` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `17%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `15%`。
> **与末条归档（138 件）的关系**：末条 `m309`（3,549 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 2A 浏览器验收：BLOCKED（5202 未运行）

### 1. 模型自证（未伪造）

直接读取自身 wire 的 `profile.bind` 第 4 行：

- 文件：`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_71b4b66e/agents/agent-981/wire.jsonl`
- 原文：`{"type":"profile.bind","agentId":"agent-981","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`

| agentId | modelAlias |
|---|---|
| `agent-981` | `DeepSeek/deepseek-flash` |

### 2. 阻塞原因（与“已核实 200”的交接不符）

本次实测 5202 **不可达**，四个项目服务全部无监听：

| 端口 | 用途 | HTTP | 监听 |
|---|---|---|---|
| 5202 | 前端 | `000` | 无 |
| 8081 | 后端 | `000` | 无 |
| 18399 | 桩上游 | `000` | 无 |
| 18330 | demo | `000` | 无 |

- `Get-NetTCPConnection -State Listen` 过滤上述端口：**0 条**；进程清单无 vite / java。
- 内置浏览器 `mcp__desktop_browser__run` 实测：`browser.create_tab` → `NAVIGATION_FAILED / ERR_CONNECTION_REFUSED`；已有 tab `panel-tab-313` reload + `wait_for_load` → 同样 `ERR_CONNECTION_REFUSED`。
- `<LOCAL_WORK>/_shared/services.state` 末行明确记录：二十四棒交接已把四个后台任务全部 TaskStop、四端口无监听；二十五棒需按 §H4 重起，当前尚未重起。
- 子智能体按纪律**未启动/重启/停止任何服务**，也未改项目代码、文档、看板或 git。

### 3. 覆盖矩阵

| 项 | 状态 | 说明 |
|---|---|---|
| A 门卡四态只读（批准/拒绝/超时/执行失败） | **BLOCKED** | 无法打开 5202，未实测 |
| B GF 两态只读（提交/取消） | **BLOCKED** | 同上 |
| C 成功折叠 / 四类例外展开 | **BLOCKED** | 四类已按 §15 确认为：已拒绝、已超时、已批准·执行失败、已取消 |
| D 卡头 36.4px / chevron 靠右 / 原生 disabled | **BLOCKED** | 同上 |
| E 限高双约束 280/320/480 + 内滚 + 双视口 | **BLOCKED** | 同上 |
| F 按钮防折行 / 未提交不记录 | **BLOCKED** | 同上 |
| G 回归 / blocked·unknown·pending 不误判 | **BLOCKED** | 同上 |

源码佐证（仅供复核，**不计为通过**）：`AiPanel.vue:293-360`、`blocks.ts:312-371`、`FormRenderer.vue:32-60`、`AiPanel.vue:2147-2173`、`markdown.ts:443`、`stores/ai.ts:2190-2225`。另跑组件测试 `tests/ai-panel-parts-render.spec.ts`：**130/130 passed**（仅组件层，不能替代浏览器验收）。

### 4. 证据目录

- `<LOCAL_WORK>/_cu-verify-2a/report.md`
- `<LOCAL_WORK>/_cu-verify-2a/report.json`
- **无 PNG**：页面未加载；本子会话 `browser.switch_tab` / `activate_panel` 均返回 `BROWSER_UNAVAILABLE`（`This session is not being viewed.`），也无法做桌面截图。未拿 docs/demo 截图冒充。

### 5. 保留现场

- 活动 tab：`panel-tab-313`
- 当前 URL：`http://localhost:5202/`
- 页面状态：Chromium 错误页（`ERR_CONNECTION_REFUSED`），tab 未关闭
- 另有诊断 tab `panel-tab-314` @ `http://127.0.0.1:18460/`（非证据）

### 6. 复现

1. `curl --noproxy '*' -m 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:5202/` → `000`
2. PowerShell 查 5202 监听 → 空
3. 内置浏览器打开 `http://localhost:5202/` → `ERR_CONNECTION_REFUSED`
4. 台账末行确认四服务已净台、待二十五棒重起

### 7. 建议

**不要开工后续队列。** 请指挥官会话先按 §H4 用自己的后台 bash 重起 5202（以及验证所需的 8081 / 18399），确认 `/api/ai/health` 200 后重新派发浏览器验收；届时按 `report.md` §5 的 11 步路径逐场景截图即可。
