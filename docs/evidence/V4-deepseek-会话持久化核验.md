# V4 核验：deepseek-v4-pro「AI 会话经 ChatMemory H2 持久化 —— 刷新页面和后端重启均不丢对话历史」

- **被核验分支**：`ai-example-deepseek-v4-pro`（`git rev-parse --abbrev-ref HEAD` = `deepseek-v4-pro`，HEAD = `25639c59380b915b9f49917f77bc642d5a4a716b`）
- **核验项定义出处**：`ai-example-main-v2/docs/merge/05-合流落地执行手册.md:122`
  `| V4 | deepseek：会话 H2 持久化（重启不丢） | 对话→重启后端→取历史 | 历史完整 |`
- **核验日期**：2026-10-06
- **核验方式**：真实后端 + 真实 DeepSeek 模型完成 **5 轮**对话 → **停进程**（不删库）→ **重启 2 次** → 每次用 API 拉取同一会话历史并**逐条比对**；期间在后端停止状态下**直查 H2 文件库**验证落盘
- **核验端口**：18291（`SERVER_PORT=18291`）
- **结论**：**【实测-符合】**（附 2 项边界说明，见 §7）
- **一句话结果**：同一 `sessionId` 的历史在两次后端重启前后 **完全一致**（JSON 严格相等、md5 相同）；重启后模型仍记得重启前的用户信息（答出"7"、"14"），工具卡片（`list_config_defs:ok`）也完整恢复

---

## 1. 核验目标（自述原文与出处）

| 出处 | 原文 |
|---|---|
| `ai-example-deepseek-v4-pro/README.md`（核心能力·AI 助手行） | …页签唯一会话（H2 持久化：刷新与后端重启不丢历史，新页签=新会话） |
| `docs/05-验证结果.md:133`（架构升级轮） | \| **后端重启恢复** \| 重启后端 → 刷新页面 → 6 条消息 + 5 工具卡自 H2 恢复（ai_session 表） \| ✅ \| |
| `docs/05-验证结果.md:99`（体验修复轮） | \| 刷新恢复对话历史 \| 发送消息（2 条）→ 刷新页面 → 自动恢复 3 条消息（用户+助手+工具卡片与完整文本），无需重新发送 \| ✅ \| |
| `docs/05-验证结果.md:158`（框架合规重构轮） | \| 历史恢复 \| 浏览器：恢复消息+工具卡（后端重启后自 H2 恢复） \| ✅ \| |
| `docs/05-验证结果.md:30`（05 文件头） | 结论：全部测试用例通过（…）。另见 `docs/07-AI对话与状态同步设计.md` |

即被核验声明为：**AI 会话按页签持久化到 H2 文件库的 `ai_session` 表；刷新页面与后端重启后，对话历史（含工具调用卡片）均不丢失。**

---

## 2. 机制事实（先读源码，再设计实验）

| 问题 | 结论 | 源码位置（分支内） |
|---|---|---|
| 会话如何创建 | 前端在 `sessionStorage` 中生成/复用 `ai_session_id`（页签唯一，刷新同页签复用），首次对话请求携带该 id，后端 `AiSessionStore.getOrCreate(sessionId)` 无记录则新建 | `frontend/src/stores/ai.js:8-11`（`sessionStorage.getItem/setItem('ai_session_id')`）、`ai/AiSessionStore.java:50-68` |
| 对话历史通过哪个 API 查询 | `GET /api/ai/history?sessionId=xxx`（返回 user/assistant 展示序列，assistant 带 tools 卡片；无记录返回空列表） | `controller/AiController.java:78-117` |
| 前端刷新如何恢复 | 聊天面板挂载/刷新时调用 `/api/ai/history?sessionId=`，按页签会话恢复消息与工具卡 | `frontend/src/stores/ai.js:113-120` |
| 持久化在哪张表 | **`ai_session`**，主键 `session_id`；`messages_json`（CLOB，Spring AI 消息的 JSON 表示，不含系统提示与推理内容）+ `context_json`（CLOB 工作区快照）+ `last_access` | `entity/AiSessionRecord.java:16-43`（`@Entity @Table(name="ai_session")`）、`ai/AiSessionStore.java:93-108`（`save()`） |
| 读写策略 | 内存 `ConcurrentHashMap`（热）+ H2（冷）：命中内存直接返回；未命中则 `recordRepo.findById` 从 H2 反序列化并回填内存 | `ai/AiSessionStore.java:50-91, 177-239` |
| 内存→H2 的落库时机 | 每次对话结束后 `save()` 覆盖写入整段消息与上下文 | `ai/AiSessionStore.java:94-108` |
| 过期清理 | `@Scheduled(fixedDelay=60_000)` 清理 `last_access` 早于 `app.ai.session-ttl-minutes`（默认 **30 分钟**）的内存会话与 H2 行 | `ai/AiSessionStore.java:122-137`、`application.yml:64` |
| 注意 | `GET /api/ai/sessions/count` 统计的是**内存**会话数（`sessions.size()`），不是 H2 行数，不能用作持久化指标 | `ai/AiSessionStore.java:118-120`、`AiController.java:124-127` |

---

## 3. 环境与命令

| 项 | 实际值 |
|---|---|
| OS / Shell | Windows，Git Bash |
| JDK / 端口 | `java 21.0.12`；18291（`SERVER_PORT=18291`，`netstat` 启动前空闲） |
| 数据库 | H2 文件库 `backend/data/config_admin_db.mv.db`，**沿用同一库、重启时未删除**（重启日志中 `DataSeeder` 输出"检测到已有配置定义，跳过演示数据初始化"，证明复用了原库） |
| 模型 | DeepSeek `deepseek-flash`（分支 `application.yml:42-56`），密钥仅经环境变量 `DEEPSEEK_API_KEY` 注入，**未落任何文件** |
| 会话 id | `v4-persist-7f3a91c2`（≤64 字符约束满足） |

启动命令：

```bash
cd <REPO_ROOT>/ai-example-deepseek-v4-pro/backend
SERVER_PORT=18291 DEEPSEEK_API_KEY="***" \
  "<MAVEN_HOME>/bin/mvn.cmd" spring-boot:run
```

对话请求（SSE，请求体为 UTF-8 JSON 文件，避免命令行编码干扰）：

```bash
curl -sN --max-time 300 -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @bodyN.json http://127.0.0.1:18291/api/ai/chat
# bodyN.json = {"sessionId":"v4-persist-7f3a91c2","message":"...","context":{"page":"export","export":{"step":1,"selectedDefs":[],"conditions":{}}}}
```

历史查询：

```bash
curl -s "http://127.0.0.1:18291/api/ai/history?sessionId=v4-persist-7f3a91c2"
```

直查 H2（后端**停止**状态下执行，排除内存态干扰）：

```bash
cd backend
java -cp "<USER_HOME>/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar" \
  org.h2.tools.Shell -url "jdbc:h2:file:./data/config_admin_db;MODE=MySQL" -user sa -password "" \
  -sql "SELECT SESSION_ID, LAST_ACCESS, LENGTH(MESSAGES_JSON) FROM AI_SESSION ORDER BY LAST_ACCESS;"
```

---

## 4. 实验过程与原始观察（时间线）

### 4.1 启动 #1（PID 11148，18:56:15 启动，18291 监听中）

| 时刻 | 操作 | 原始结果 |
|---|---|---|
| 19:00:56 | 第 1 轮：`我的幸运数字是 7，请确认收到。` | 模型回复 `确认收到，您的幸运数字是 7。`（`event:start`→10 个 `event:delta`→`event:done`） |
| 19:00:59 | 第 2 轮：`我刚才告诉你的幸运数字是多少？` | 模型回复 `您的幸运数字是 7。`（**跨轮记忆生效**） |
| 19:01:02 | 第 3 轮：`请调用 list_config_defs 工具列出系统中所有配置项编码，然后用一句话总结。` | 事件链含 `reasoning`→`tool_start {"name":"list_config_defs"}`→`tool_result {"ok":true}`→`delta…`→`done`；回复"系统中共有 4 个启用的配置定义——SERVER_PARAM…" |
| 19:01:04 | `GET /api/ai/history?sessionId=v4-persist-7f3a91c2` | HTTP 200，`data` 长度 **7**：user/assistant 各 3 条 + 1 条工具卡 assistant（`tools:[{name:list_config_defs,status:ok}]`） |

原始 SSE 全文见 `V4-附件-五轮对话SSE原始输出.txt`；历史原文见 `V4-附件-历史-重启前.json`。

### 4.2 停止后端 + 直查 H2（不删库）

- `taskkill /PID 11148 /F` → `netstat -ano | grep 18291` 无 LISTENING；
- 停止状态下直查 H2（`V4-附件-H2直查-ai_session表.txt`）：

```
== [Q1] ai_session 全部行 ==
SESSION_ID                           | LAST_ACCESS                | MSG_JSON_LEN
e2e-f2988e7244144edb86023ea6182a4dcf | 2026-10-06 18:58:09.411377 | 30018
e2e-49b0b041fd93428c9bb9e5f64a61d0de | 2026-10-06 19:00:02.956615 | 21078
v4-persist-7f3a91c2                  | 2026-10-06 19:02:46.637238 | 1464
(3 rows, 12 ms)

== [Q2] 表结构 ==
SESSION_ID    | CHARACTER VARYING      | 64
CONTEXT_JSON  | CHARACTER LARGE OBJECT | 9223372036854775807
LAST_ACCESS   | TIMESTAMP              | null
MESSAGES_JSON | CHARACTER LARGE OBJECT | 9223372036854775807
```

停止后端那一刻 `v4-persist-7f3a91c2` 行的 `MSG_JSON_LEN` 为 **1265**（后续第 4/5 轮对话使其增至 1464），其 `MESSAGES_JSON` 原文（`V4-附件-H2直查-ai_session表.txt` [Q3]）即 **8 条记录**（用户 3 / 助手 4 / 工具结果 1；其中"助手 toolCalls + 工具结果"两条在展示层被合并为 1 张工具卡，故 `/history` 返回 7 条）：

```json
[{"role":"user","content":"请只回答一句话…幸运数字是 7，请确认收到。"},
 {"role":"assistant","content":"确认收到，您的幸运数字是 7。"},
 {"role":"user","content":"…我刚才告诉你的幸运数字是多少？"},
 {"role":"assistant","content":"您的幸运数字是 7。"},
 {"role":"user","content":"请调用 list_config_defs 工具…"},
 {"role":"assistant","content":"","toolCalls":[{"id":"call_00_wFA0lnWJrnzYsZkNszUm8604","name":"list_config_defs","arguments":"{}"}]},
 {"role":"tool","content":"…共 4 个配置定义…","toolCallId":"call_00_wFA0lnWJrnzYsZkNszUm8604","name":"list_config_defs"},
 {"role":"assistant","content":"系统中共有 4 个启用的配置定义——…"}]
```

→ **落盘的三要素齐全：用户消息、助手文本、助手 toolCalls 与 tool 结果**。

### 4.3 重启 #1（PID 20440，19:01:26 启动）

| 时刻 | 操作 | 原始结果 |
|---|---|---|
| 19:01:30 | 启动完成 | `Tomcat started on port 18291`；`DataSeeder : 检测到已有配置定义，跳过演示数据初始化`（= 复用原库，未初始化新库） |
| 19:01:56 | `GET /api/ai/history?sessionId=v4-persist-7f3a91c2` | HTTP 200，**7 条**，与重启前**逐条一致** |
| — | 严格比对 | `diff` **0 处差异**；`md5sum` 两个文件均为 `0111b6c51558a129a7b1c0da1310a6e4` → **JSON 文本严格相等** |

### 4.4 重启后继续真实对话（验证恢复的记忆是否被模型真正使用）

| 时刻 | 操作 | 原始结果 |
|---|---|---|
| 19:02:04 | 第 4 轮（重启后首条）：`我前面说的幸运数字乘以 2 是多少？` | `event:done data:{"content":"14。"}` → **模型用到了重启前第 1 轮告诉它的 7**，说明恢复的不只是展示文本，而是真实进入模型上下文 |

### 4.5 重启 #2（PID 10792）——先对话、不先查历史，以捕捉"从 H2 恢复"的日志

- `taskkill /PID 20440 /F` → 18291 无 LISTENING；
- 再次启动（19:02:4x，`Tomcat started on port 18291`），**不调用 `/api/ai/history`**，直接发第 5 轮对话；
- 后端日志原文（`V4-附件-后端重启2-日志.log`）：

```
2026-10-06T19:02:45.703+08:00  INFO 10792 --- [config-admin-backend] [omcat-handler-0] c.example.configadmin.ai.AiSessionStore : 会话从持久化恢复：v4-persist-7f3a91c2（消息 10 条）
```

- 同一次请求的模型回复：`event:done data:{"cancelled":false,"content":"7。"}` → 重启后模型仍答出重启前告知的幸运数字；
- 19:02:51 再查历史：**11 条**（= 重启前 7 条 + 第 4 轮 user/assistant + 第 5 轮 user/assistant），前 7 条与重启前完全一致。

### 4.6 三次拉取的逐条比对（机器比对，脚本 `cmp.js` + 报告 `V4-附件-历史逐条比对报告.txt`）

| 比对 | 结果 |
|---|---|
| 条数 | before=**7**，after（重启#1）=**7**，after2（重启#2）=**11** |
| before vs after 逐条（role+content+工具卡 name/status） | **7/7 条一致**，0 处差异 |
| before vs after JSON 文本严格相等 | **true**（md5 相同） |
| after2 前 7 条 vs after | **7/7 条一致** |
| after2 新增 4 条 | 第 4 轮 `user|…乘以 2 是多少？` + `assistant|14。`；第 5 轮 `user|…一开始告诉你的幸运数字是多少？` + `assistant|7。` |

**历史原文与比对报告**

```
--- before（重启前）逐条 ---
1. user|请只回答一句话，且不要调用任何工具：我的幸运数字是 7，请确认收到。|tools=
2. assistant|确认收到，您的幸运数字是 7。|tools=
3. user|请只回答一句话，且不要调用任何工具：我刚才告诉你的幸运数字是多少？|tools=
4. assistant|您的幸运数字是 7。|tools=
5. user|请调用 list_config_defs 工具列出系统中所有配置项编码，然后用一句话总结。|tools=
6. assistant||tools=list_config_defs:ok
7. assistant|系统中共有 4 个启用的配置定义——SERVER_PARAM（全局，服务器参数）、REGION_BILLING（地区，计费规则）、PROJECT_QUOTA（项目，资源配额）和 SERVER_EXTEND（全局，服务器扩容，引用并依赖 SERVER_PARAM）。|tools=
```

### 4.7 附加观察：工具调用记录是否完整恢复

| 观察项 | 结果 |
|---|---|
| 工具卡片本体（名称/参数/状态） | 恢复：`tools=[{name:list_config_defs, args:{}, status:ok}]` |
| 工具结果摘要 | 恢复：`summary` 为 `list_config_defs` 的完整返回串（含 4 个配置定义的字段清单，服务端截断至 500 字符 + `…`） |
| 工具调用 id 与 tool 消息的配对 | H2 中 `toolCalls[0].id = call_00_wFA0lnWJrnzYsZkNszUm8604` 与 `tool.toolCallId` 相同，反序列化时被合并为一条 `ToolResponseMessage`（`AiSessionStore.fromRecord` 的合并逻辑），未出现"孤立 tool 消息导致上游 400" |
| 助手文本 | 恢复（含工具轮之后的总结性回复） |
| 系统提示 / 推理内容 | 不入库（`toMaps` 跳过 SYSTEM；`docs/05:157` 亦自述"钩子管理器直写会话记忆"），**属设计如此**，不影响"历史完整"判定 |

---

## 5. 逐项对照表

| 自述项 | 自述值 | 实测值 | 判定 |
|---|---|---|---|
| 会话按页签 id 持久化 | 页签唯一会话 | `sessionId=v4-persist-7f3a91c2` 全程唯一，历史按该 id 查询 | ✅ 符合 |
| 持久化位置 = H2 `ai_session` 表 | ai_session 表 | 后端停止时直查 H2 命中该行（`SESSION_ID`/`MESSAGES_JSON`(CLOB)/`CONTEXT_JSON`(CLOB)/`LAST_ACCESS`），`MSG_JSON_LEN` 1265→1464 | ✅ 符合 |
| 后端重启不丢历史 | 不丢 | 重启 #1：7 条 → 7 条，md5 严格相等；重启 #2：11 条，前 7 条一致 | ✅ 符合 |
| 重启后历史"可继续用"（非仅展示） | — | 重启 #1 后模型答出 `14。`（= 重启前的 7×2）；重启 #2 后模型答出 `7。` | ✅ 符合（超出原自述的加强验证） |
| 工具卡片随历史恢复 | 05:133 称"6 条消息 + 5 工具卡自 H2 恢复" | 恢复 `list_config_defs:ok` 卡片与完整结果摘要，toolCallId 与 tool 结果配对完好 | ✅ 符合（工具卡数量随本次实验的对话内容而定，未复现"5 个工具卡"的具体数字，属样本差异） |
| 刷新页面不丢 | 05:99 称浏览器实测 | **未在浏览器中实测**（本次未启动前端 dev server）；已做代码级确认：刷新后 `sessionId` 仍取自 `sessionStorage`（`frontend/src/stores/ai.js:8-11`），面板恢复逻辑调用同一个 `/api/ai/history`（`ai.js:113-120`），而该 API 与持久化路径已被重启实验充分实证 | ⚠️ 见 §7 边界一 |
| 未知会话返回空列表 | TC22c | 未纳入本次 V4 实验（V3 的 TC22 已实测通过：`GET /api/ai/history?sessionId=no-such-session` 返回空） | ✅ 符合（交叉引用 V3） |

---

## 6. 未观察到的问题

- 重启前后无 `ERROR` 级日志（`V4-附件-后端重启1-日志.log` 84 行、`重启2` 85 行，均无 ERROR/WARN 异常栈）；
- 无"会话反序列化失败"日志（`AiSessionStore.fromRecord` 的告警路径未被触发），说明 `messages_json` 的存/取闭环无信息损失；
- 无 401/400 类上游报错（工具消息配对正确，回灌模型未触发 DeepSeek 的协议校验错误）。

---

## 7. 边界说明（不构成"不符"，但影响结论适用范围）

**边界一：`刷新页面不丢` 一项未做浏览器端实测。**
本次核验未启动前端（`yarn dev` + 浏览器）——核验环境只保证后端 18291 可用。已用代码级 + 同构 API 实证替代：
- `sessionId` 存于 `sessionStorage`，刷新同页签复用同一 id（`frontend/src/stores/ai.js:8-11`）；
- 刷新恢复走 `GET /api/ai/history`（`ai.js:113-120`），而该接口的"重启后仍返回完整历史"已被 §4.3/§4.6 严格证明。
因此把"刷新不丢"判为**机制成立但未直接观测**；若需强证据，需补一次浏览器刷新实验（成本约 5 分钟：起前端 → 对话 → F5 → 看消息条数）。

**边界二：30 分钟空闲 TTL 未覆盖。**
分支设计里 `app.ai.session-ttl-minutes: 30`，`@Scheduled` 每分钟清理 `last_access` 超时会话（内存 + H2 行）。本次实验的重启窗口约 40 秒，远小于 TTL，故**只证明了"短时重启不丢"**；"空闲超过 30 分钟后仍不丢"与设计相反（设计上就会丢），**不属自述声明范围**，此处仅提示合流后不要误把 30 分钟 TTL 当作"永久持久化"。

**附注（观察，非缺陷）**：重启后 `GET /api/ai/sessions/count` 返回 `count:1`（在已调用 `/history` 之后取值）。该接口统计的是**内存**会话数（`AiSessionStore.size()` = `sessions.size()`），因此**不能**用它在重启后盘点 H2 中的持久化会话数；盘点请直接查 H2 或逐个 `sessionId` 调 `/history`。

---

## 8. 环境收尾确认

- 本次依次启动过 3 个后端实例（PID 11148 → 20440 → 10792），**均已 `taskkill` 关闭**；
- `netstat -ano | grep 18291` 最终结果：**无 LISTENING**（仅剩历史 `TIME_WAIT` 客户端连接），**18291 已释放**；18080 全程未被占用（`netstat` 检查无 LISTENING），未触碰 18290/18292/18293/18294；
- 数据库沿用原库、未删除；`backend/data/`（H2 文件 + exports/imports）保留；
- 未做任何 git 写操作，未修改分支源码/脚本/配置（`git status --porcelain` 为空）；
- API Key 仅存在于进程环境变量，本文档与全部附件均不含密钥（对全部证据文件做了密钥串扫描：`sk-` 前缀密钥与 `DEEPSEEK_API_KEY=值` = 0 命中）。

---

## 9. 结论

**【实测-符合】**

被核验声明「AI 会话经 H2 持久化，后端重启不丢对话历史」在真实运行下成立，且证据强于原自述：

1. **落盘实证**：后端停止时直查 H2，`ai_session` 表中存在该会话行（CLOB 消息 JSON 内含 8 条记录：用户/助手/工具调用/工具结果齐全；展示层合并为 7 条，见 §4.2）；
2. **两次重启、三次拉取**：重启 #1 后历史 7 条与重启前 **JSON 文本严格相等（md5 相同）**；重启 #2 后 11 条，其中前 7 条完全一致；
3. **功能性验证**：重启后模型仍能答出重启前告知的"7"（及其 2 倍"14"），且后端日志给出 `会话从持久化恢复：v4-persist-7f3a91c2（消息 10 条）`，说明历史被重新载入模型上下文而非仅前端展示；
4. **工具调用记录**同样完整恢复（`list_config_defs:ok` + 结果摘要 + toolCallId 配对）；
5. 适用边界：本次未做浏览器刷新实测（代码级确认机制成立），且持久化带 30 分钟空闲 TTL——合流时若要求"长期不丢"，需调整 `app.ai.session-ttl-minutes`。

---

## 附件清单

| 文件 | 内容 |
|---|---|
| `V4-附件-五轮对话SSE原始输出.txt` | 5 轮对话的 SSE 原始流（第 1/2/3 轮在重启前，第 4 轮在重启 #1 后，第 5 轮在重启 #2 后，含 `tool_start`/`tool_result`/`done` 原文） |
| `V4-附件-历史-重启前.json` | 19:01:04 `GET /api/ai/history` 响应原文（7 条） |
| `V4-附件-历史-重启1后.json` | 19:01:56 同一会话历史响应原文（7 条，md5 与上一文件相同） |
| `V4-附件-历史-重启2后.json` | 19:02:51 同一会话历史响应原文（11 条） |
| `V4-附件-历史逐条比对报告.txt` | `cmp.js` 生成的逐条比对报告（条数、role/content/工具卡一致性与差异明细） |
| `V4-附件-H2直查-ai_session表.txt` | 后端停止状态下 H2 Shell 直查原文（[Q1] 全部行 / [Q2] 表结构 / [Q3] 会话消息 JSON 原文） |
| `V4-附件-后端重启1-日志.log` | 重启 #1 完整日志（84 行） |
| `V4-附件-后端重启2-日志.log` | 重启 #2 完整日志（85 行，含 `会话从持久化恢复：v4-persist-7f3a91c2（消息 10 条）`） |
