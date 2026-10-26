<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# SX-003 · 设计 · S1 执行单：风险验证 PoC（07fa7a17 / main）

> **项目归属**：**同源 ai-example 分支/评测附录**（不计入主仓成果）。
> **归属依据**：会话属 ai-example 分支合流/早期分支评测（非本主仓）。
> **稿态**：历史中间稿 —— 该 wire 第 `m40` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt27` 条有正文的 assistant 消息（共 151 条有正文），末条有正文消息为 `m337`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `07fa7a17` / agent `main`（`main` = 主会话）；原字符 6,364（链内 1 段、合计 6,364）。
> **链号 / 类别**：`SX-003` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 13 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `7%`。
> **与末条归档（138 件）的关系**：末条 `m337`（617 字符）未入末条归档。
> **脱敏**：路径 15 / 会话标识 0 / 身份 0 / 密钥 3 / 旧仓名与同源仓名 17 处。

---

接下来是 **S1 风险验证 PoC**——把合流最大的技术未知数在动主干之前用最小工程实测掉。我根据 S0.5 证据和你新增的三条决策（DC-01/02/03）重新裁切了 PoC 范围，原手册的 SP-01c 已并入 Redis 专项。

---

# S1 执行单：风险验证 PoC

## 总体安排

| 对话 | 模型 | 验证内容 | 端口 |
|---|---|---|---|
| 对话 1 | **Kimi K3**（Plan 模式） | SP-01a+b：官方循环内前端工具暂停-恢复 + HITL 确认门 | 18301 |
| 对话 2 | **Kimi K3**（Plan 模式） | SP-01c：Redis 会话记忆（DC-02/DC-03 落地验证） | 18302 |
| 对话 3 | **Kimi K3**（Plan 模式） | SP-01d：韧性三件套（断流重试/去重/取消） | 18303 |
| 对话 4（K3 全部完成后我再给提示词） | **GLM-5.3** | 独立复现复核（防单模型假象） | — |

三个 K3 对话**可以并行开**，都是新对话。按手册纪律：模型须先输出验证计划，**你确认后**才让它动手建工程。

---

## 对话 1 提示词（Kimi K3：SP-01a+b 工具循环与 HITL）

```text
你是技术可行性验证工程师。任务：做一个不改主干代码的技术 PoC，只产实测结论。
你在 Windows（Git Bash）下工作。

【项目背景】
ai-example 是"AI 辅助配置快速实施平台"（Spring Boot 3.5 + JDK 21 + Spring AI 1.1.8 +
Vue 3），正做六分支合流，合流仓库 <OLD_REPO_DIR> 在 <LOCAL_WORK>/<REPO>。
AI Runtime 拟采用 Spring AI 官方循环（ChatClient + ToolCallingManager 扩展点）。
本 PoC 验证官方循环能否表达两个关键机制，验证不过就要回退备选架构，所以结论必须真实。

【关键参照代码（先读再动手）】
- <LOCAL_WORK>/ai-example-code/ai-example-deepseek-v4-pro/backend/src/main/java/.../ai/
  下的 HooksToolCallingManager（HITL 确认门挂起实现）、SSE 事件与心跳实现、@Tool 工具定义
- <LOCAL_WORK>/ai-example-code/ai-example-kimi-k3/backend/.../ReasoningAwareOpenAiChatModel.java
  （reasoning_content 回填补丁）
- 核验证据：<LOCAL_WORK>/<REPO>/docs/evidence/ 下
  V4-deepseek-会话持久化核验.md、V7-kimi-reasoning_content-400核验.md
- 决策约束：docs/adr/DECISION-REGISTER.md 的 DC-01（OpenAI 兼容协议，全配置化）

【环境】
- JDK 21 在 PATH；Maven 全路径：
  "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
- AI 服务（OpenAI 兼容）：base-url https://api.deepseek.com，model deepseek-flash，
  key 仅用环境变量注入：export DEEPSEEK_API_KEY="<REDACTED_API_KEY>"
  禁止把 key 写入任何文件
- 本 PoC 分配端口 18301（18290-18294/18080 禁用，可能被占）
- spike 工程建在 <LOCAL_WORK>/<REPO>/spike/sp01ab/，
  禁止改动 <OLD_REPO_DIR> 的其他任何目录与文件，禁止任何 git 提交

【验证内容】
SP-01a（前端工具）：在官方 ChatClient 循环内，把某个工具标记为"前端执行"：
工具调用从循环透出 → 经 SSE 下发给模拟前端 → 前端结果回灌 → 循环恢复正常结束。
通过条件：链路完整走通，Loop 正常结束，事件时序有日志证据。
SP-01b（HITL 确认门）：基于 HooksToolCallingManager 扩展点实现确认门，验证三种结局：
① 确认放行后工具正常执行；② 拒绝后拒绝原因回填给模型且 Loop 继续；
③ 超时自动取消。挂起等待期间 SSE 心跳保活（连接不断）。
通过条件：三种结局行为均正确 + 挂起期心跳有实测证据。

【流程纪律】
1. 先输出验证计划（工程结构、实验步骤、判定标准），等我确认后再动手；
2. 结论只能来自真实运行的日志/请求响应原文，禁止外推与"理论上可行"；
3. 单实验失败超 3 次：停下如实报告；
4. 完成后关闭所有进程，netstat 确认 18301 释放。

【产出】
<LOCAL_WORK>/<REPO>/docs/spike/SP-01ab-决策卡.md，含：
结论（可行/不可行/有条件可行）/ 每条结论的实测日志证据 / 被否决方案的坑与回归风险 /
证据等级标注（【实测】/【推断】）/ 推翻条件。大日志存 docs/spike/ 附件文件。
最后给我 ≤10 行摘要。
```

---

## 对话 2 提示词（Kimi K3：SP-01c Redis 会话记忆）

```text
你是技术可行性验证工程师。任务：做一个不改主干代码的技术 PoC，只产实测结论。
你在 Windows（Git Bash）下工作。

【项目背景】
ai-example 正做六分支合流，合流仓库 <OLD_REPO_DIR> 在 <LOCAL_WORK>/<REPO>。
已登记的架构决策（docs/adr/DECISION-REGISTER.md，先读）：
- DC-02：AI 对话会话状态存 Redis（多实例共享、TTL 即可），不要求持久化，
  存储层用 Spring AI 官方 RedisChatMemoryRepository 替换原 H2 方案
- DC-03：部署为分布式多实例，会话必须跨实例共享
- DC-01：AI 服务走 OpenAI 兼容协议（base-url/model/key 全配置化）
本 PoC 是 DC-02/DC-03 的落地可行性验证。

【关键参照】
- <LOCAL_WORK>/ai-example-code/ai-example-deepseek-v4-pro/backend/.../ai/ 包：
  其 ChatClient + MessageChatMemoryAdvisor 用法（原 H2 持久化方案，本次换成 Redis）
- 核验证据：<OLD_REPO_DIR>/docs/evidence/V4-deepseek-会话持久化核验.md（机制基线）、
  V7-kimi-reasoning_content-400核验.md（修正结论：deepseek-flash 不强制
  reasoning_content 回填，deepseek-v4-pro 才强制）

【环境】
- JDK 21 在 PATH；Maven 全路径：
  "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
- Memurai（Redis 兼容）：127.0.0.1:6379，CLI 在
  "<DRIVE>/Program Files/Memurai/memurai-cli.exe"（可用于直查键结构验证）
- AI 服务：base-url https://api.deepseek.com，model deepseek-flash，
  key 仅环境变量：export DEEPSEEK_API_KEY="<REDACTED_API_KEY>"
- 本 PoC 分配端口 18302（模拟第二实例用 18312；18290-18294/18080/18301/18303 禁用）
- spike 工程建在 <OLD_REPO_DIR>/spike/sp01c/，禁止动 <OLD_REPO_DIR> 其他目录，禁止 git 提交

【验证内容】
用 Spring Boot 3.5 + Spring AI 1.1.8 最小工程（ChatClient +
MessageChatMemoryAdvisor + RedisChatMemoryRepository）实测：
1. 多轮对话记忆生效（第二轮能引用第一轮内容）；
2. 用 memurai-cli 直查 Redis：会话键结构、TTL 行为，记录原文；
3. 多实例共享：实例 A（18302）完成 2 轮对话后，实例 B（18312，同 Redis 不同进程）
   用同一 sessionId 继续对话，验证 B 能看到 A 的历史——这是 DC-03 的核心证据；
4. 进程重启后会话在 TTL 内仍可恢复（对应 DC-02"不持久化到磁盘、Redis 内存态共享"语义）；
5. 连续 7 轮带工具调用的对话不出现 400（deepseek-flash，参照 V7 修正结论；
   若工程里带 ReasoningAwareOpenAiChatModel 补丁需说明并验证无害）。
通过条件：5 项全部实测通过，每项有日志/CLI 输出原文。

【流程纪律】
1. 先输出验证计划等我确认；2. 结论只来自真实运行；3. 单实验失败超 3 次停下报告；
4. 完成后关闭所有进程、清理测试用 Redis 键（记录清理结果）、netstat 确认两端口释放。

【产出】
<OLD_REPO_DIR>/docs/spike/SP-01c-决策卡.md（结论/实测证据/被否决方案（H2 持久化、JVM 内存）
的坑与回归风险/证据等级/推翻条件），大日志存 docs/spike/ 附件。最后 ≤10 行摘要。
```

---

## 对话 3 提示词（Kimi K3：SP-01d 韧性三件套）

```text
你是技术可行性验证工程师。任务：做一个不改主干代码的技术 PoC，只产实测结论。
你在 Windows（Git Bash）下工作。

【项目背景】
ai-example 正做六分支合流，合流仓库 <OLD_REPO_DIR> 在 <LOCAL_WORK>/<REPO>。
AI Runtime 拟采用 Spring AI 官方循环。SSE 长连接在真实网络下有三类典型故障：
上游断流、tool-result 重复到达、执行中需要取消。合流仓库的测试用例参考
docs/merge/04-测试用例文档.md 中 TC-F-04、TC-AI-14、TC-AI-15 的预期（先读）。
本 PoC 验证官方循环下这三件事能否做到。

【关键参照】
- <LOCAL_WORK>/ai-example-code/ai-example-deepseek-v4-pro/backend/.../ai/ 包：
  断流 90s 自愈重试、确认门等待期 SSE 心跳、SSE 首包 start 事件、统一执行器的实现
- <LOCAL_WORK>/ai-example-code/ai-example-claude-opus-5.5+deepseek-v4-pro/backend/.../
  JobCancellationRegistry（作业取消注册表的语义参考）
- 决策约束：docs/adr/DECISION-REGISTER.md 的 DC-01/DC-03

【环境】
- JDK 21 在 PATH；Maven 全路径：
  "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
- AI 服务：base-url https://api.deepseek.com，model deepseek-flash，
  key 仅环境变量：export DEEPSEEK_API_KEY="<REDACTED_API_KEY>"
- 本 PoC 分配端口 18303（18290-18294/18080/18301/18302 禁用）
- spike 工程建在 <OLD_REPO_DIR>/spike/sp01d/，禁止动 <OLD_REPO_DIR> 其他目录，禁止 git 提交

【验证内容】
1. 断流有界重试：制造上游断流（可用本地代理截断或其他可控方式，说明你用的方法），
   验证：重试策略有界（次数/时长上限）、流式已产出内容不重试、最终行为符合预期；
2. tool-result 重复去重：同一 tool-result 重复回灌，循环不产生重复执行；
3. 执行中取消：工具执行/循环进行中收到取消信号，能在合理时间内终止并落正确状态，
   取消语义对齐 JobCancellationRegistry（JVM 内版本即可，Redis 化是 S3 的事）。
通过条件：三项均有实测证据；做不了的要明确说"官方循环表达不了"并给出最小绕行方案。

【流程纪律】
1. 先输出验证计划等我确认；2. 结论只来自真实运行，断流实验必须说明你如何可控地
   制造故障（禁止"拔网线"式不可复核方法）；3. 单实验失败超 3 次停下报告；
4. 完成后关进程、netstat 确认 18303 释放。

【产出】
<OLD_REPO_DIR>/docs/spike/SP-01d-决策卡.md（结论/实测证据/被否决方案的坑/证据等级/推翻条件），
大日志存 docs/spike/ 附件。最后 ≤10 行摘要。
```

---

## 你的操作要点

1. 三个对话**都用 Kimi K3**，都选 Plan 模式（它们会先出计划，你过目确认再放行）
2. 三个对话可并行开；若你机器资源有限，优先顺序：**对话 2（Redis）> 对话 1（HITL）> 对话 3（韧性）**
3. 每个对话结束把摘要发我（或说"完成"，我直接去 `docs/spike/` 核验决策卡）
4. 若某个 PoC 结论是"不可行"——这是正常且有价值的，**不要让它返工硬凑**，如实拿回结论，我启动备选架构预案（kimi-k3 受控薄 Loop，S0.5 已佐证其可用）

三张决策卡齐了以后，我给**对话 4（GLM-5.3 独立复现复核）**的提示词——复核通过，S1 关卡才算过。
