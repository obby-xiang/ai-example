<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-354 · 其它 · 零新增组件（不需要报批，纯仓内）（ced79706 / main）

> **项目归属**：**上下文推定**（正文无路径锚点；依据该 agent 任务提示与项目作业面判定，入库前建议补锚点）。
> **归属依据**：任务提示指向本项目、正文为项目作业面；**无路径/类名锚点**（故标上下文推定）。
> **稿态**：历史中间稿 —— 该 wire 第 `m148` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt54` 条有正文的 assistant 消息（共 88 条有正文），末条有正文消息为 `m216`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `ced79706` / agent `main`（`main` = 主会话）；原字符 2,026（链内 1 段、合计 2,026）。
> **链号 / 类别**：`MT-354` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 50 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `9%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `6%`。
> **与末条归档（138 件）的关系**：末条 `m216`（1,722 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

对，三条路线的差别主要就在**要新引入哪些组件**。按「批」给你列清单，你拿去确认（观②/观① 不需要任何新东西，可以先做）：

## 零新增组件（不需要报批，纯仓内）

| 批 | 内容 | 依赖 |
|---|---|---|
| 观① run 摘要层 | Redis 结构化摘要 + `/runs` 查询 | 零新增 |
| 观② MDC 关联 id | logback pattern + MDC | 零新增 |

## 观③ 指标面（仅 Java 依赖，无新服务器）

| 组件 | 作用 | License | 形态 |
|---|---|---|---|
| `spring-boot-starter-actuator` | 端点暴露（/actuator） | Apache-2.0 | Java 依赖 |
| `micrometer-registry-prometheus` | 指标格式导出 | Apache-2.0 | Java 依赖 |

> 若公司**已有** Prometheus/Grafana 基建，观③ 到此为止、零新服务器。若自建则需下表前两行。

## 观④ 路线 1：OTel 主干（我推荐）

| 组件 | 作用 | License | 形态 | 数据敏感性 |
|---|---|---|---|---|
| `micrometer-tracing-bridge-otel` + OTel Java SDK/exporter | 应用侧 trace 产生与导出 | Apache-2.0 | Java 依赖 | 默认不采内容 |
| **OpenTelemetry Collector** | 收拢/脱敏/tail sampling/分发 | Apache-2.0 | **新服务器**（单进程容器/二进制） | 流经 trace，可配置不落内容 |
| **Grafana Tempo** | trace 存储与查询 | **AGPL-3.0** | **新服务器** | 存 span 元数据 |
| **Prometheus** | 指标存储 | Apache-2.0 | 新服务器（或复用公司已有） | 仅数值 |
| **Grafana** | 可视化 | **AGPL-3.0** | 新服务器（或复用已有） | — |

注意点：Tempo/Grafana 是 **AGPL-3.0**——内部自用不改源码不分发通常可过，但有的公司一刀切禁 AGPL，**这点最可能需要确认**；替代 = Jaeger（Apache-2.0，查询体验略弱）换 Tempo。

## 观④ 路线 2：Langfuse 一把梭

| 组件 | 作用 | License | 形态 |
|---|---|---|---|
| Langfuse server + worker | LLM 观测平台（trace/会话视图/评分） | **MIT（核心）+ 商业 ee 模块**（同仓，需确认只用核心） | **新服务器** |
| PostgreSQL | Langfuse 元数据 | PostgreSQL License（宽松） | 新服务器（或复用） |
| **ClickHouse** | trace/事件存储 | Apache-2.0 | 新服务器 |
| Redis | Langfuse 队列/缓存 | RSALv2/SSPL（新版）⚠️ | 可复用现有 Memurai? 需确认 |
| S3/MinIO | 原始事件 blob | MinIO 是 **AGPL-3.0** ⚠️ | 新服务器 |

维护成本最高（4 个有状态组件）；ClickHouse 与 Langfuse ee 边界要确认。

## 观④ 路线 3：双写 = 路线 1 + 路线 2 全量（不推荐除非两刚需）

## 附：曾评估过但不推荐的

- **Arize Phoenix**：单进程最轻，但 **ELv2**（非 OSI，源码可用限制商用 SaaS 方向），公司审 License 大概率要解释；
- **OpenTelemetry Java Agent**（无侵入自动埋点）：Apache-2.0，可作为路线 1 的备选接入方式（不改代码，但自定义 span 仍要码）。

**给你的确认清单（一句话版）**：① AGPL（Grafana/Tempo/MinIO）能不能用？② Langfuse 的 MIT 核心+ee 同仓能不能用？③ 新增服务器额度：Collector×1 + Tempo×1（路线1）vs 4 组件（路线2）批哪个？④ 公司是否已有 Prometheus/Grafana/ClickHouse 可复用？

你确认期间，超时批①施工 + 文书入库已在跑；观②/观① 不依赖任何批复，你点头我就先排。
