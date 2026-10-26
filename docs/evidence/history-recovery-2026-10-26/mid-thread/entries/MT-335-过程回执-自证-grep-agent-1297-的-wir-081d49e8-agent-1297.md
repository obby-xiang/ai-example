<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-335 · 过程回执 · 自证（grep agent-1297 的 `wire.jsonl`，首行为 metadata）：（081d49e8 / agent-1297）

> **项目归属**：**上下文推定**（正文无路径锚点；依据该 agent 任务提示与项目作业面判定，入库前建议补锚点）。
> **归属依据**：任务提示指向本项目、正文为项目作业面；**无路径/类名锚点**（故标上下文推定）。
> **稿态**：历史中间稿 —— 该 wire 第 `m27` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 3 条有正文），末条有正文消息为 `m37`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `081d49e8` / agent `agent-1297`（`main` = 主会话）；原字符 2,432（链内 1 段、合计 2,432）。
> **链号 / 类别**：`MT-335` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `0%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m37`（2,236 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

自证（grep agent-1297 的 `wire.jsonl`，首行为 metadata）：
`{"type":"profile.bind","agentId":"agent-1297","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high","systemPrompt":"You are Kimi Code, ...`

纪要已保存到 `<LOCAL_WORK>\_i18n-toolcfg-research\调研-i18n与工具可配置业界惯例-2026-10-21.md`（174 行，20 行对照表，25 条引用）。

**要点**

- **前端 i18n**：vue-i18n v11 只使用 Composition API（`legacy:false`；Legacy v12 移除，`tc/$tc` 已删，`v-t` 弃用）。语言包按 locale 拆成 JSON，用 `import()` + `setLocaleMessage` + `nextTick` 懒加载；消息格式是 vue-i18n 自有语法（`{}` 插值、竖线复数、`@:` 链接），**不是 ICU**，中型项目不需要上 ICU。Element Plus 用 `<el-config-provider :locale>` + computed 接 Pinia，**Day.js 语言包必须单独导入**。语言优先级建议：用户偏好（后端）> localStorage > navigator > 默认；切换时同步 `html lang` 和 `Accept-Language`；冲突时以显式偏好为准。
- **后端 i18n**：`MessageSource` + `AcceptHeaderLocaleResolver` + `messages_zh/en.properties`；fallback 为 `zh_CN → zh → 默认`，所以只需两份文件；`@Valid` 用 `{key}`。错误响应采用 **RFC 9457 ProblemDetail + 稳定 `code`（扩展成员）+ 本地化 detail**：code 用来让调用方做稳定分支和日志聚合，detail 只作兜底；只返回本地化字符串是反模式。坑：`LocaleContextHolder` 是 ThreadLocal，线程池/异步会丢 locale。
- **工具可配置化**：注解（Spring AI `@Tool`、LangChain4j `@Tool`）是“声明式默认”，官方都另给了运行期出口——Spring AI 的 `MethodToolCallback`/`ToolDefinitions.builder(method).description(...)`，LangChain4j 的 `ToolSpecification`（可 `toJson/fromJson`，配合自定义 `ToolExecutor`）。Prompt registry（Langfuse/LangSmith）的通用模型是“不可变版本 + 标签指针（production/staging/tenant）+ 移标签即发布/回滚”，值得借用；不要“保存即生效”，因为改工具描述会**击穿 prompt 缓存**（Anthropic 哈希顺序 tools → system → messages），也无法评测。是否灰度：大工具集/关键路径需要；小集只需“发布 + 回滚”两态。MCP 的 `tools/list` + `listChanged` 是**工具外置的协议层答案**，不是文案可配置的全部——它管“有哪些工具、结构如何”，不管“谁编辑、怎么审校回滚”。
- **工具文案 i18n**：证据支持“模型侧描述英文单语、语言无关”：实践指南直接建议 tool descriptions 用英文；阿拉伯语基准显示，无论描述用阿语还是英语，工具调用都下降 5–10%，**描述语言不是主要杠杆**；多语言工具调用的主因是“参数值语言不匹配”（MLCL，只译了 query 未译描述，需按此边界解读）。正确杠杆是 system prompt 的“用用户语言回答”+ 前端展示层。**用户可见与模型可见必须分离**——MCP 2025-06-18 为此专门加了 `title`（展示名，优先级 title → annotations.title → name），`description` 仍只给模型。
- **对本项目**：注解判断方向对，但需精确化为“注解不能作为可编辑文案的唯一来源”。理由：注解值必须是编译期常量（JLS/Baeldung），无法读 DB/MessageSource/热更；工具描述是 prompt，要按 eval 节奏改，与 Java 发版节奏冲突；框架和 MCP 都已提供“默认 + 覆盖”的先例。
- **落地顺序**：先定错误码契约（code+args+traceId）→ 前端 vue-i18n 骨架（收益最快）→ 后端 messages 兜底 → 工具注册表外置（YAML 起步，接口留给 DB）→ 最后补工具 UI 的 `tool.<tool_key>.*` 文案。**不要**先做工具文案多语言，也不要一上来 DB 化而没有发布流程。

**裁决建议（一句话）**：注解保留为“实现绑定 + 缺省元数据”，name/description/参数说明外置到版本化工具注册表（YAML 起步、DB 版本表 + 发布指针为目标态），运行时装配 `ToolSpecification`/`MethodToolCallback`；模型侧文案只留英文一份，用户侧展示文案走前端 i18n。
