<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-263 · 调研 · AI 对话超时面全图（main @ cb420ff）（ced79706 / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiController.java`、`ConfirmGate`、`ResilientChatService.java`、`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m134` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt41` 条有正文的 assistant 消息（共 88 条有正文），末条有正文消息为 `m216`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `ced79706` / agent `main`（`main` = 主会话）；原字符 2,844（链内 1 段、合计 2,844）。
> **链号 / 类别**：`MT-263` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 40 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m216`（1,722 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

盘点完成（勘察官逐项核到代码行，含默认值与到点行为）。全量如下，供你审视：

## AI 对话超时面全图（main @ cb420ff）

### 一、传输层
| 项 | 现值 | 落点 | 到点行为 |
|---|---|---|---|
| Tomcat `connection-timeout` | 120s | `application.yml:6` | 兼作 socket 写超时 |
| AI 流 emitter 死线 | **无（-1L）** | `AiController.java:1277` | 等人可无限挂（设计） |
| 单帧写预算 `sse.write-timeout` | 15s | `application.yml:259` → `SseWriteBudget.java:87` | 摘除慢订阅者，前端带 lastSeq 重挂 |
| 任务通道 emitter | 15s 单帧 / 5m 总 | `application.yml:272-273` | 到点 complete，浏览器自重连 |
| 保活节奏 | 挂起 2s / 流式 5s，其余时段**无** | `ConfirmGate.java:119`、`ResilientChatService.java:462` | 上游静默时只有 watchdog 判死 |

### 二、机器预算（一轮唯一硬死线）
| 项 | 现值 | 到点行为 |
|---|---|---|
| **`resilience.total-budget`** | **300s**（扣人工等待，`application.yml:197`） | `TOTAL_BUDGET` error 终帧，不可重试 |
| `first-byte-timeout` / `inter-event-timeout` | 30s / 90s | 可重试档断流 |
| `max-attempts` | 2（**尝试间零退避**） | 仅断流+未产出+无副作用才重试 |
| 后端工具执行超时 | **无**，工具循环无轮数上限 | 都由 300s 兜底 |
| Redis 命令/建连 | 2s | 探活失败 → 503 |
| 取消标志 TTL | 600s | 超 10min「取消过」不再拦续跑 |
| 会话/轮键 TTL | 24h 滑动（挂起期 2s 心跳整额续） | 到期整轮数据消失 |

### 三、等人面
- 确认门 + 6 个无超时工具（`generative_form`/表格 4 写工具/`ui_request_upload`）：**永不等出超时**，出口只有批准/拒绝/取消/中断，中断不写 TIMEOUT。
- 唯一机器兜底：`hitl.frontend-tool-timeout=120s`（只管即时执行类前端工具回执）→ `TIMEOUT/executed=false` 回填，轮不结束。
- 挂起即断流：请求帧发出即 complete 释放连接，决策后带 lastSeq 重连。

### 四、租约与锁（**全部不在 yml 明面**，仅代码默认值）
续跑权 30s / 续租 10s / 抢租重试 35s / 动作锁 60s 不续租 / 会话锁 10m+30s watchdog / zombie-age 60s / 启动延迟扫描 8s。这是多实例正确性核心时钟，配置面不可见是个管理缺口。

### 五、上游调用
探活 5s（`/test`）/ 模型目录缓存 10m / 配置版本比对 1s 节流 / **流式上游无 read timeout**（靠看门狗）/ 非流式走 Spring Retry 默认模板（3 次、**无退避、任何异常都重试**）/ 摘要 `summary-timeout=60s`。

### 六、压缩熔断
fuse 3 连败开闸 → 冷却 10m 半开一次；触发线 70/80/95 滞回 60%。

### 七、限流
1m 滑动窗口、session 20 / IP 60、`Retry-After=ceil(剩余)`、Redis 挂降级放行。

### 八、前端
axios 60s / SSE 帧间静默 90s / 建连看门狗 15s / 重连 2 次（1s、2s）/ 前端工具预算 60s（=后端 120 − 回灌 60）/ 409 后权威校正 3s / 发布作业 300s / 预览卡与上传交接**零计时器**（与确认门同路，符合设计）。

---

## 值得审视的矛盾与漂移（12 条，挑重点）

1. **`/health` 写预算指标说谎**：固定报 15000 不读配置（`SseChatEmitter.java:401`）。
2. **前端 90s 与后端事件间 90s 同刻**——上游静默时前端重挂与后端重试零安全余量。
3. **前端 60s 余量是字面复制**（`frontend-tools.ts:285` vs `http.ts:104`），改一失三。
4. **非流式重试语义被低估**：默认 RetryTemplate 3 次无退避任意异常都重试，摘要一轮最坏打 3 次上游；外层流式重试也是零退避。
5. **摘要路径违反「禁用虚拟线程」纪律**（`ContextSummaryClient.java:54`），且 `future.cancel(true)` 对阻塞 WebClient 未必生效，可能线程泄漏。
6. **NUMSPEC 总账已过期**：session-ttl 写 6h（现 24h）、`hitl.timeout 120s`（键已退役）等多处，按它调参会调错。
7. **退役键设置无效不报错**：`confirm-timeout`/`session-ttl-minutes`/`max-iterations` 写进 yml 静默无效。
8. E2E 观察窗 180s < 产品 300s 且 5min 上限零余量；`resume.on-startup` 代码默认 false 出厂 yml true。

## 最该先看的 5 行（勘察官建议）
① `total-budget=300s` 一身兼三职（工具循环/工具超时/上游读超时兜底）；② `120s↔60s↔60s` 三处算式耦合；③ 租约组 30/10/35 + 会话锁 10m/30s（配置面不可见）；④ 传输三层 emitter −1 / 15s / Tomcat 120s；⑤ 摘要 60s + 3 次无退避 + 熔断 10m 的组合拳。

你审。有要改的项告诉我哪些、改成什么方向，我按批次立项（测试矩阵先行）；漂移类（NUMSPEC/注释/health 指标说谎）可直接安排小批订正，也可与可观测性批合并——可观测性的三个裁决（路线/开工/分期）也还等你。
