<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-358 · 设计 · 先答你的问题：知识问答不只是存文档（f82201be / main）

> **项目归属**：**上下文推定**（正文无路径锚点；依据该 agent 任务提示与项目作业面判定，入库前建议补锚点）。
> **归属依据**：任务提示指向本项目、正文为项目作业面；**无路径/类名锚点**（故标上下文推定）。
> **稿态**：历史中间稿 —— 该 wire 第 `m55` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt24` 条有正文的 assistant 消息（共 150 条有正文），末条有正文消息为 `m335`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `f82201be` / agent `main`（`main` = 主会话）；原字符 3,309（链内 1 段、合计 3,309）。
> **链号 / 类别**：`MT-358` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 14 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `3%`。
> **与末条归档（138 件）的关系**：末条 `m335`（162 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

核实完毕。回答你的问题 + 完整方案如下，请审视。

## 先答你的问题：知识问答不只是存文档

KB 的数据 = **文档正文（20 篇 .md）+ 一份 `manifest.json`**。manifest 不是简单清单，每篇文档带：`id / title / summary（摘要）/ tags / aliases（别名，检索用）/ sha / updated_at / file` + **`terms[]` 术语表**（每条术语含 name/aliases/所属章节/标准答案 —— 这是结构化问答数据）。所以**迁移单位 = 文档 + manifest 一起**，缺了 manifest 检索和术语问答就残了。

Lucene 索引（`data/kb-index/`）和 Redis 缓存（`kb:cache:*`）都是**派生态，不迁移**，拿到文件后重建即可。KB 本来就没有任何 DB 表。

## 一、需迁移数据清单（列全）

**A. AI 服务配置（4 张表 + 派生态）**

| 表 | 内容 | 迁移口径 |
|---|---|---|
| `ai_provider` | code / displayName / protocol / baseUrl / completionsPath / modelsPath / enabled / sortOrder / keyRef / source（**13 字段**；keyCipher/keyLast4/keyFingerprint 是 key 材料） | **迁**，但 key 材料一律不进文件 —— 文件只写 `key_ref=ENV`，key 永远走环境变量 |
| `ai_model` | providerId / modelId / displayName / maxContext / maxOutput / supportsToolCalling / supportsVision / supportsReasoning / enabled / isDefault / sortOrder（12 字段） | **迁**（文件里按 provider 分组嵌套） |
| `ai_setting` | 6 个键：`config.version` / `default.modelId` / `default.temperature` / `default.topP` / `default.maxTokens` / `allow.userParams` | **迁**（`config.version` 由文件 etag/sha 替代） |
| `ai_config_audit` | actor/action/targetType/targetId/diffJson/at（变更审计流水） | **建议不迁**：文件模式下配置变更发生在文件侧，审计改写应用日志一行。待你裁 |
| 派生态 | Redis `ai:cfg:ver`、`ai:cfg:models:*`、进程内快照 | **不迁**，重建 |

**B. 知识问答**：20 篇 .md + manifest.json（如上）。零 DB 表。

## 二、目标形态（DB 方案原样保留，新增文件模式）

```
app.ai.config.source = db（默认，既有行为一字不动）| remote-file
app.ai.kb.source     = local（默认，既有）| http（生产指远程）
```

- **远程文件契约**（消费方）：复用 KB 手册 §8.2 已定契约 —— `GET {base}/manifest.json`（带 ETag）+ `GET {base}/{file}`（支持 If-None-Match → 304）。AI 配置文件也作为其中一个文件（如 `ai-config.yml`，自带头部版本字段）。
- **模拟服务端点（我们提供）**：`GET /api/ai/files/{path}` —— 从可配根目录按相对路径返回文件，越界闸 + ETag/304 + Content-Type。本地/演示时 AI 配置与 KB 的 base-url 都指向它，即模拟远程；生产指向真远程，代码零差别。
- **更新文件接口（仅模拟用）**：`PUT /api/ai/files/{path}`，带开关默认关 —— 真实场景文件由外部系统更新，这个口只是本地模拟器的一部分。
- **懒加载 + 缓存 + 热更（定时检测）**：
  - **KB**：基本现成 —— `HttpKbSource` 已有 304 增量、`CachedKbSource` Redis 缓存、`refresh-interval` 定时刷新。只需配置切 http。
  - **AI 配置**：新增 `RemoteFileAiConfigSource` —— 启动不拉全量（懒加载：首次 `current()` 才拉）；进程内快照即内存缓存；**定时任务按可配间隔（建议 60s）HEAD/GET manifest 比 etag/sha，变了才重拉并走既有 `reload()` 通道**（`AiProviderRegistry` 指纹机制保证只在真变化时重建连接，在飞轮不受影响）。多实例各拉各的，无需 Redis 广播（`ai:cfg:ver` 在文件模式停用）。

## 三、修改点清单（按组）

| 组 | 修改点 | 量 |
|---|---|---|
| A 模拟文件服务 | ①`GET /api/ai/files/{path}`（越界闸/ETag/304）②`PUT` 同款路径（开关默认关）③`FileStorageService.read()` 补 `startsWith` 路径闸 | 小 |
| B AI 配置文件源 | ④`ai-config.yml` schema（镜像三表字段，key 只写 `key_ref=ENV`）⑤`AiConfigSource` 接口 + `DbAiConfigSource`（现有逻辑原样搬）+ `RemoteFileAiConfigSource`（懒加载+定时检测+etag 版本）⑥`AiConfigService` 装配分叉（source=db 时行为零变化） | 中 |
| C 无表启动不崩 | ⑦4 实体/4 仓储/Seeder/Writer 按 `source=db` 条件装配 ⑧V4 迁移处置（`ignore-migration-patterns` 或空占位迁移，否则 Flyway 校验崩） | 中 |
| D 设置页 | ⑨file 模式下 7 个写端点返回明确「文件模式只读」错误码；前端设置页只读态（**待你裁**，见下） | 小-中 |
| E KB 切换 | ⑩`source=http` 指模拟端点的样例配置；⑪样例自播种在 http 模式跳过（加开关） | 小 |
| F 配套 | ⑫新增配置键 + `application.yml(.example)` ⑬文档（维护手册 §8.2 契约→已实现、部署篇）⑭测试：文件源解析/缺文件/坏 YAML/越界/304/无表启动/热更轮询 | 中 |

## 四、待你拍板（4 点）

1. **设置页在 file 模式**：建议**只读展示**（文件是唯一真源，界面不再可写）——可否？
2. **`ai_config_audit` 在 file 模式**：建议**不迁表、变更只写应用日志一行**——可否？
3. **热更检测间隔**：建议 **60 秒**定时比 etag（KB 侧沿用各自 refresh-interval）——可否？
4. **模拟文件服务是否带进生产包**：建议代码带、`PUT` 写口开关默认关、`GET` 口可配开——可否？

你审视后给结论，我出施工计划排批次。
