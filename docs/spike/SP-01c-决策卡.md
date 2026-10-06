# SP-01c 决策卡：Spring AI 1.1.8 + Redis 会话记忆

- **PoC 编号**：SP-01c
- **结论日期**：2026-10-06
- **被验证方案**：不用官方 Redis 实现（1.1.8 无、2.0 需 Redis 模块），改为**实现官方 SPI**
  `org.springframework.ai.chat.memory.ChatMemoryRepository`，用 Spring Data Redis 普通数据结构承载；
  对话循环与记忆挂载走官方 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`。
- **spike 工程**：`<MAIN_V2>/spike/sp01c`（Spring Boot 3.5.14 + JDK 21 + Spring AI 1.1.8 BOM）
- **关联决策**：DC-02（会话状态存 Redis、TTL 即可、不持久化）、DC-03（分布式多实例）、
  DC-05（官方已有能力禁止自研，官方能力不存在时可实现官方 SPI）、DC-08（脱敏纪律）
- **附件**：
  | 附件 | 内容 |
  |---|---|
  | `SP-01c-附件-A-验证原始输出.txt` | 逐阶段 HTTP 请求/响应原文 + `memurai-cli` 命令与输出原文 |
  | `SP-01c-附件-B-实例A运行日志.log` | 实例 A（18302）四次运行日志，含每次上游 `UPSTREAM ... -> HTTP nnn` |
  | `SP-01c-附件-C-实例B运行日志.log` | 实例 B（18312）运行日志 |
  | `SP-01c-附件-D-Vc5逐轮记录.json` | V-c5 七轮结构化记录（状态码/耗时/工具调用数/回复） |

---

## 0. 结论速览

| 验证项 | 结论 |
|---|---|
| V-c1 多轮记忆 | **可行** |
| V-c2 Redis 键结构与 TTL 到期 | **可行** |
| V-c3 多实例共享 | **可行** |
| V-c4 重启恢复 | **可行** |
| V-c5 连续 7 轮带工具调用无 400 | **可行** |

**总体结论：方案可行（推荐落地）。** 官方 SPI 扩展点成立，官方记忆窗口与 Advisor 全部原样可用；
两条官方替代路线（官方 2.0 实现、JVM 内存实现）实测/源码核对后在本场景不可用或不可接受。

> **一处需要业务方知晓的诚实边界**：本卡验证的是"会话记忆在 Redis 中跨实例共享并随 TTL 消失"，
> **不包含**并发写同一 sessionId 的正确性（见 §5.4），也**不包含**生产压测。

---

## 1. 环境与前置事实

| 项 | 实测值 | 证据 |
|---|---|---|
| JDK | 21.0.12 LTS | `java -version` |
| Spring Boot | 3.5.14 | 启动日志 |
| Spring AI | 1.1.8（BOM import） | 启动类路径 |
| Redis 兼容服务 | Memurai 8.2.0，`redis_version:8.2.10` | `memurai-cli INFO server` |
| Memurai 已加载模块 | **仅 `vectorset`**（无 JSON、无 Search） | `memurai-cli MODULE LIST` |
| 端口 | 实例 A = 18302（PID 22048/16496/6444 等，多次重启），实例 B = 18312（PID 27724） | 附件 B/C 启动日志 + `netstat` |

### 1.1 为什么必须自己实现这个 SPI（官方实现不可用的两条证据）

**（a）1.1.x 线官方构件从未发布，但 BOM 里"看起来有"——这是会踩的陷阱。**

- `spring-ai-bom-1.1.8.pom` 第 169 行与第 940 行**确实声明**了
  `spring-ai-model-chat-memory-repository-redis` 与 `spring-ai-starter-model-chat-memory-repository-redis`，
  版本用 `${project.version}`（即 1.1.8）。照 BOM 写依赖，Maven 会解析失败。
- Maven Central `maven-metadata.xml` 实测：两个坐标的 `versions` 里**最早版本都是 `2.0.0-M1`**，
  无任何 1.1.x：
  ```
  $ curl -s https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-model-chat-memory-repository-redis/maven-metadata.xml
  <latest>2.1.0-M1</latest><release>2.1.0-M1</release>
  <versions><version>2.0.0-M1</version> ... <version>2.1.0-M1</version></versions>
  ```
- 本地仓库侧证：`<USER_HOME>/.m2/repository/.../spring-ai-starter-model-chat-memory-repository-redis/1.1.8`
  是一个**空目录**，无 jar/pom 落盘。

**（b）官方 2.0.1 实现依赖 RedisJSON + RediSearch 两个模块，本机跑不起来，且给生产加模块约束。**

直接下载官方 `spring-ai-model-chat-memory-repository-redis-2.0.1-sources.jar` 核对源码：

- 类 Javadoc 原文：*"Redis implementation of `ChatMemoryRepository` using Redis (JSON + Query Engine).
  Stores chat messages as JSON documents and uses the Redis Query Engine for querying."*
- 关键调用实测出现在源码中：`jsonSet`（`RedisChatMemoryRepository.java:142,176`）、
  `ftCreate`（`:439`）、`ftSearch`（`:251,382,631,648,687,763,834`）、`IndexDataType.JSON`（`:436`）、
  `redis.clients.jedis.search.RediSearchUtil`（`:45,248,375`）。
- 该模块 `pom.xml` 依赖 `redis.clients:jedis` + `com.google.code.gson`——即**再引入一套 Redis 客户端**
  （本项目随 `spring-boot-starter-data-redis` 用的是 Lettuce）。
- 本机 `memurai-cli MODULE LIST` 只有 `vectorset`，`JSON.SET` / `FT.CREATE` 均不可用，
  **官方实现在本机连一次写都跑不通**。

**结论**：按 DC-05「官方能力不存在时可实现官方 SPI 扩展点」实现 `RedisChatMemoryRepository`，
只实现官方接口的 4 个方法，其余全走官方。签名由 `javap` 对 `spring-ai-model-1.1.8.jar` 核实：

```
public interface org.springframework.ai.chat.memory.ChatMemoryRepository {
  public abstract java.util.List<java.lang.String> findConversationIds();
  public abstract java.util.List<org.springframework.ai.chat.messages.Message> findByConversationId(java.lang.String);
  public abstract void saveAll(java.lang.String, java.util.List<org.springframework.ai.chat.messages.Message>);
  public abstract void deleteByConversationId(java.lang.String);
}
```

### 1.2 与官方实现的关键语义对齐（源码核对）

- **官方 2.0.1 的 `saveAll` 就是"先清后写"**——`RedisChatMemoryRepository-2.0.1` 源码：
  ```java
  public void saveAll(String conversationId, List<Message> messages) {
      clear(conversationId);          // 先清
      add(conversationId, messages);  // 再批量写
  }
  ```
  本实现对齐为 `DEL` + 批量 `RPUSH`（±TTL 刷新），语义一致。
- **官方 `MessageWindowChatMemory` 每次 add 都是"读全量 → 合并裁剪 → 全量回写"**——字节码核对
  `MessageWindowChatMemory.class`：`add()` 调用链为
  `ChatMemoryRepository.findByConversationId` → `process(List,List)` → `ChatMemoryRepository.saveAll`。
  这正是 `saveAll` 必须是**整窗口覆盖写**的原因；若实现成"追加"，窗口裁剪会失效。
- **官方 `findByConversationId` 在 2.0.1 里带读取上限**（`get(conversationId, config.getMaxMessagesPerConversation())`），
  本实现是 `LRANGE 0 -1` 全量读，**不设读取上限**。取舍见 §5.3。

---

### 1.3 复现步骤（本卡证据可重跑）

```bash
# 0) 前置：Memurai 在 127.0.0.1:6379；导出密钥（不落盘）
export DEEPSEEK_API_KEY=***

# 1) 构建（<MAVEN_HOME> = Maven 安装目录下的 bin/mvn）
cd <MAIN_V2>/spike/sp01c && <MAVEN_HOME>/mvn -B clean package

# 2) 启动两个实例（同一 Redis、不同进程、不同端口）
cd <WORK_DIR> && cp <MAIN_V2>/spike/sp01c/target/sp01c-redis-chat-memory-0.0.1-SNAPSHOT.jar sp01c.jar
SPIKE_PORT=18302 java -jar sp01c.jar --spring.profiles.active=verbose &   # 实例 A（verbose 记录上游状态码）
SPIKE_PORT=18312 java -jar sp01c.jar &                                    # 实例 B

# 3) 逐项取证（Memurai CLI 不在 PATH 时先 export MEMURAI_CLI=<MEMURAI_HOME>/memurai-cli.exe）
cd <MAIN_V2>/spike/sp01c
node scripts/spike-verify.mjs vc1       http://127.0.0.1:18302
node scripts/spike-verify.mjs vc2       http://127.0.0.1:18302
node scripts/spike-verify.mjs vc3-seed  http://127.0.0.1:18302 vc3-shared
node scripts/spike-verify.mjs vc3-cont  http://127.0.0.1:18312 vc3-shared
node scripts/spike-verify.mjs vc4-seed  http://127.0.0.1:18302 vc4-restart
#   杀 A（只杀自己启的 PID）→ 同端口重启 A → 再跑：
node scripts/spike-verify.mjs vc4-cont  http://127.0.0.1:18302 vc4-restart
node scripts/spike-verify.mjs vc5       http://127.0.0.1:18302
node scripts/spike-verify.mjs roundtrip http://127.0.0.1:18302 roundtrip-check
node scripts/spike-verify.mjs window    http://127.0.0.1:18302 window-check   # 需 --spike.chat-memory.max-messages=4 重启 A
```

原始输出写到 `spike/sp01c/evidence/`（脚本默认输出位，可用 `EVIDENCE_DIR` 覆盖）；
正式留档已汇总并脱敏为 `docs/spike/` 下的附件 A–D。
清理：`node scripts/spike-verify.mjs cleanup <baseUrl> <sessionId...>` 或
`memurai-cli KEYS "chat:mem:*"` 后逐个 `DEL`。

> 踩坑记录（值得带进生产工程）：**不要用 Git Bash + `curl -d` 发中文 JSON**——Git Bash 会按 GBK
> 发送命令行字节，服务端报 `Invalid UTF-8 middle byte 0xeb`。直接用 Node `fetch`（恒 UTF-8）
> 或写 UTF-8 文件后 `curl -d @file`。本 spike 的验证脚本因此改用 Node。

---

## 2. 逐项结论与实测证据

### V-c1 多轮记忆 —— 【实测】可行

**证据（附件 A「V-c1」段）**：

```
POST http://127.0.0.1:18302/api/chat
  request  : {"sessionId":"vc1-s1","message":"请记住这个数字：42。只回复“已记住”，不要解释。"}
  HTTP     : 200
  response : {"sessionId":"vc1-s1","reply":"已记住","model":"deepseek-flash","historySize":2,...}

POST http://127.0.0.1:18302/api/chat
  request  : {"sessionId":"vc1-s1","message":"我刚才让你记住的数字是几？只回复数字。"}
  HTTP     : 200
  response : {"sessionId":"vc1-s1","reply":"42","model":"deepseek-flash","historySize":4,...}

GET /api/chat/vc1-s1/history   →  HTTP 200
[{"type":"USER","text":"请记住这个数字：42。..."},{"type":"ASSISTANT","text":"已记住"},
 {"type":"USER","text":"我刚才让你记住的数字是几？..."},{"type":"ASSISTANT","text":"42"}]
```

**判定依据**：第二轮 `reply` 为 `42`，且 `historySize` 从 2 增至 4 —— 模型确实读到了第一轮内容
（且不是靠猜：第一轮回复只是"已记住"，数字 42 只存在于被回放的历史里）。

### V-c2 Redis 键结构与 TTL 到期 —— 【实测】可行

**（1）键结构：一个会话 = 一个 LIST，元素是 JSON 消息**

```
$ memurai-cli TYPE chat:mem:vc1-s1      → list
$ memurai-cli TTL  chat:mem:vc1-s1      → 21600
$ memurai-cli LLEN chat:mem:vc1-s1      → 4
$ memurai-cli LRANGE chat:mem:vc1-s1 0 -1
{"type":"USER","content":"请记住这个数字：42。只回复“已记住”，不要解释。",
 "metadata":{"messageType":"USER","spikeTimestamp":"..."},"timestamp":"..."}
{"type":"ASSISTANT","content":"已记住",
 "metadata":{"role":"ASSISTANT","messageType":"ASSISTANT","refusal":"","finishReason":"STOP",
             "annotations":[{}],"index":0,"id":"0d9298d4-...","spikeTimestamp":"..."},"timestamp":"..."}
...（共 4 条）
$ memurai-cli SMEMBERS chat:mem:__ids__
vc1-s1
```

键位设计：`chat:mem:<conversationId>`（LIST，本会话消息）+ `chat:mem:__ids__`（SET，会话 id 索引，
供 `findConversationIds()` 使用）。JSON 字段：`type` / `content` / `timestamp` / `metadata`，
外加按需的 `toolCalls`（AssistantMessage）与 `toolResponses`（ToolResponseMessage）。

**（2）TTL 到期 → 会话自行消失，即"不持久化"语义成立**

```
POST /api/dev/ttl?seconds=5            → {"ttlSeconds":5}
（该接口运行期把仓库 TTL 覆盖为 5 秒，随后新建一次性会话 vc2-ttl-5s）

$ memurai-cli TYPE   chat:mem:vc2-ttl-5s   → list
$ memurai-cli TTL    chat:mem:vc2-ttl-5s   → 5
$ memurai-cli EXISTS chat:mem:vc2-ttl-5s   → 1

等待 8 秒（> TTL 5 秒）

$ memurai-cli TTL    chat:mem:vc2-ttl-5s   → -2      （-2 = 键不存在）
$ memurai-cli EXISTS chat:mem:vc2-ttl-5s   → 0
$ memurai-cli LRANGE chat:mem:vc2-ttl-5s 0 -1   → （空）
GET /api/chat/vc2-ttl-5s/history           → HTTP 200  []
```

TTL 由 `saveAll` 每次写入后 `EXPIRE` 刷新（滑动过期）。恢复默认 TTL：`POST /api/dev/ttl?seconds=21600`。

**（3）补充实测：索引键的惰性清理**（附件 A「会话 id 索引的惰性清理」段）

主键靠 TTL 自灭，索引不做逐会话 TTL，过期 id 在下次 `findConversationIds()` 时被剔除：

```
（TTL=3 的会话写入后）  SMEMBERS chat:mem:__ids__  → prune-probe
（等 6 秒）              EXISTS chat:mem:prune-probe → 0
（清理前）              SMEMBERS chat:mem:__ids__  → prune-probe      ← 过期 id 仍在
GET /api/dev/info       → {"conversationIds":[],...}
（清理后）              SMEMBERS chat:mem:__ids__  → （空）
```

### V-c3 多实例共享 —— 【实测】可行

两个**不同进程**（A PID 22048 @18302、B PID 27724 @18312）共用同一 Redis：

```
【实例 A 侧】GET /api/dev/info → {"port":"18302","pid":22048,...}
POST /api/chat {"sessionId":"vc3-shared","message":"请记住这个数字：7。..."}       → HTTP 200 "已记住"
POST /api/chat {"sessionId":"vc3-shared","message":"请再记住一个词：紫水晶。..."}   → HTTP 200 "已记住"
$ memurai-cli TTL chat:mem:vc3-shared → 21600

【实例 B 侧】GET /api/dev/info → {"port":"18312","pid":27724,...}
GET /api/chat/vc3-shared/history → HTTP 200
[{"type":"USER","text":"请记住这个数字：7。..."},{"type":"ASSISTANT","text":"已记住"},
 {"type":"USER","text":"请再记住一个词：紫水晶。..."},{"type":"ASSISTANT","text":"已记住"}]

POST /api/chat {"sessionId":"vc3-shared","message":"我刚才让你记的数字是几？我让你记的词是什么？用一句话回答。"}
  HTTP : 200
  reply: "你让我记的数字是 7，让我记的词是紫水晶。"
```

**判定依据**：B 从未与 A 通信，仅凭 Redis 就复述出两个事实（7 / 紫水晶），
且 B 的 `history` 接口读到的正是 A 写下的 4 条消息。

### V-c4 重启恢复 —— 【实测】可行

```
【重启前】A = PID 22048
POST /api/chat {"sessionId":"vc4-restart","message":"请记住一个暗号：青铜时代-314。..."} → "已记住"
POST /api/chat {"sessionId":"vc4-restart","message":"再记住一个数字：1024。..."}          → "已记住"
GET /api/chat/vc4-restart/history → 4 条（基线）
$ memurai-cli TTL chat:mem:vc4-restart → 21600 / LLEN → 4

【杀进程】taskkill /PID 22048  →  成功；netstat 仅剩 18312（B 未受影响）
【键仍在】TTL chat:mem:vc4-restart → 21593 ；LLEN → 4      ← JVM 死亡不带走会话

【同端口重启】A = PID 16496（新进程）
GET /api/dev/info → {"port":"18302","pid":16496,...}
GET /api/chat/vc4-restart/history → 与重启前**完全一致**的 4 条
$ memurai-cli TTL chat:mem:vc4-restart → 21568
POST /api/chat {"sessionId":"vc4-restart","message":"我让你记的暗号和数字分别是什么？用一句话回答。"}
  HTTP : 200
  reply: "暗号是“青铜时代-314”，数字是 1024。"
```

### V-c5 连续 7 轮带工具调用无 400 —— 【实测】可行

工程内 2 个 `@Tool`：`getServerTime`、`calculate`（加法/减法/乘法/除法）。每轮都带
system prompt 硬性要求"必须调用工具"，并把工具调用次数计入 `/api/dev/tool-stats` 作为客观计数。

```
7 轮 HTTP 状态序列: 200, 200, 200, 200, 200, 200, 200
是否出现 400: 否
是否出现非 200: 否
7 轮新增工具调用总次数: 8   （每轮 ≥ 1；tool-stats: getServerTime=3, calculate=5）
```

逐轮摘要（完整见附件 D）：

| 轮 | HTTP | 本轮工具调用 | 模型回复（节选） |
|---|---|---|---|
| 1 | 200 | 1 | 服务器当前时间是 **2026-10-06 19:41:58**。 |
| 2 | 200 | 1 | 123 加 456 的结果是 **579**。 |
| 3 | 200 | 2 | 服务器当前时间：**2026-10-06 19:42:03**；88 乘 3：**264** |
| 4 | 200 | 1 | 1000 减 250 的结果是 **750**。 |
| 5 | 200 | 1 | 144 除以 12 的结果是 **12**。 |
| 6 | 200 | 1 | 服务器当前时间是 **2026-10-06 19:42:08**。 |
| 7 | 200 | 1 | 9 乘 9 的结果是 **81**。本轮是第 **7** 轮对话。 |

**上游侧独立证据**（附件 B 的 `verbose` profile 观测）：发往 OpenAI 兼容端点的每次真实调用状态码

```
$ grep -oE "UPSTREAM POST [^ ]+ -> HTTP [0-9]+" 附件-B | sort | uniq -c
     15 UPSTREAM POST /v1/chat/completions -> HTTP 200
（V-c5 的 7 轮 = 14 次上游调用，每轮 2 次：工具决策 + 最终作答；另 1 次来自 V-c4 收尾）
（4xx/5xx 匹配结果：none）
```

**关于 `reasoning_content` 的说明**：本 spike **未做任何框架补丁**，7 轮工具调用全程未出现 400，
与仓库 `docs/evidence/V7` 的核验结论一致（deepseek-flash 不强制回填 `reasoning_content`）。
另经 `javap -c org.springframework.ai.openai.OpenAiChatModel` 核对：`createRequest` 对 ASSISTANT 消息
只读 `getToolCalls()` / `getMedia()` / `getText()`，**不读 `getMetadata()`**，故 `reasoningContent`
不会随历史 assistant 消息回传。

**补充实测：上游实际组装的 messages 顺序**（从附件 B 的 `UPSTREAM_REQUEST_BODY` 解出，V-c5 第 3 轮）：

```
[0] system   "你是工具调用验证助手。硬性规则：每一轮..."     ← system 在首位
[1] user     第 1 轮…                                        ← 历史回放
[2] assistant 服务器当前时间是 **2026-10-06 19:41:58**。
[3] user     第 2 轮…
[4] assistant 123 加 456 的结果是 **579**。
[5] user     第 3 轮…
[6] assistant tool_calls=getServerTime/calculate            ← 本轮工具协议消息
[7] tool      tool_call_id=call_00_tYZa…"2026-10-06 19:42:03"
[8] tool      tool_call_id=call_01_qWfg…"88.0 multiply 3.0 = 264.0"
```

两点可确认：① system 消息**每轮都被置顶**——`MessageChatMemoryAdvisor.before` 字节码显示它把
内存消息与本次 prompt 指令拼接后，会把**遇到的第一个 SystemMessage 移到索引 0**（循环从 i=0 起，
命中第一个 SystemMessage 即 `remove(i)` + `add(0, msg)` 并跳出）。本 spike 用的是 **per-request
`.system(...)`**（实测路径）；按同一机制，`ChatClient.Builder.defaultSystem(...)` 产生的 SystemMessage
每轮也会出现在指令列表里从而被置顶——但**该用法本 spike 未单独实测**【推断】；
② 工具协议三条消息（assistant.tool_calls + 2 条 tool）的 `tool_call_id` 与 `tool_calls[].id` 正确配对，
说明"记忆回放 + 工具循环"叠加工作正常。

**补充实测：工具消息经 Redis 的往返保真**（自检接口 `/api/dev/roundtrip`，附件 A「工具消息往返自检」段）

```
{"identical": true, "differences": [], "originalSize": 4, "readBackSize": 4,
 "assistantToolCalls": [{"id":"call_1","type":"function","name":"getServerTime","arguments":"{}"},
                        {"id":"call_2","type":"function","name":"calculate",
                         "arguments":"{\"a\":6,\"operator\":\"multiply\",\"b\":7}"}],
 "toolResponses": [{"id":"call_1","name":"getServerTime","responseData":"2026-10-06 12:00:00"},
                   {"id":"call_2","name":"calculate","responseData":"6 multiply 7 = 42"}]}
```

构造 `UserMessage + 带 toolCalls 的 AssistantMessage + ToolResponseMessage + 收尾 AssistantMessage`，
经官方 `ChatMemory` 写入 Redis 再读回，逐字段比较（时间戳除外，理由见下）**无任何差异**。

> 时间戳为何要排除：`MessageJsonCodec.serialize` 不修改入参消息对象，因此对同一个**尚未落库**的内存对象
> 重复序列化会生成新时间戳；而 Redis 里的副本携带首次写入的时间戳。生产路径上时间戳按消息固定
> （旧消息读回后原样回写），故往返比较中排除它才反映真实语义。这是本 spike 首次比对报
> `identical:false` 的根因，已定位并修正比对方式，**不是数据丢失**。

**补充实测：官方窗口裁剪确实落到了 Redis LIST**（`--spike.chat-memory.max-messages=4`，附件 A「窗口裁剪」段）

```
$ memurai-cli LLEN chat:mem:window-check   → 2   （第 1 轮后）
$ memurai-cli LLEN chat:mem:window-check   → 4   （第 2 轮后）
$ memurai-cli LLEN chat:mem:window-check   → 4   （第 3 轮后，不再增长）
GET /api/chat/window-check/history →
["USER: 第 2 轮…","ASSISTANT: 已记住","USER: 第 3 轮…","ASSISTANT: 已记住"]   （第 1 轮已被裁掉）
```

即：LIST 长度由官方 `MessageWindowChatMemory` 的窗口上界约束，**不会随轮次无限增长**。
这也反向确认了 §1.2 的结论——`saveAll` 必须是整窗口覆盖写。

---

## 3. 被否决方案：坑与回归风险

### 3.1 方案一：引入官方 2.0.x 的 `RedisChatMemoryRepository`（含 backport 到 1.1.8）

| 维度 | 实测发现 |
|---|---|
| 可行性 | **不可行（本机）/ 高成本（生产）** |
| 阻塞点 1 | 依赖 RedisJSON（`JSON.SET`）+ RediSearch（`FT.CREATE`/`FT.SEARCH`）。本机 Memurai 8.2.0 仅加载 `vectorset`，无这两个模块，**连一次写入都跑不通** |
| 阻塞点 2 | 生产环境要给每个 Redis 实例装两个模块（或换 Redis Stack）。这与"会话状态用普通 Redis、TTL 即可"的轻量定位冲突 |
| 阻塞点 3 | 该模块依赖 `redis.clients:jedis` + `gson`，本项目已用 Lettuce（`spring-boot-starter-data-redis` 默认），等于**并存两套 Redis 客户端** |
| 阻塞点 4 | 2.0.x 属跨大版本。1.1.8 的 `ChatMemoryRepository` 恰好也是 4 个方法、语义相近，但 backport 需自行编译官方源码并保证 `spring-ai-model` 2.0 的传递依赖不污染 1.1.8 运行时——升级评审成本高于自己实现 4 个方法 |
| 回归风险 | 若将来升级到 Spring AI 2.x：本实现可能与官方 `RedisChatMemoryRepository` Bean 名/自动配置冲突；需显式关掉官方自动配置或删除本实现（见 §5.4） |

### 3.2 方案二：H2 / 数据库持久化会话

| 维度 | 说明 |
|---|---|
| 与决策冲突 | **直接违反 DC-02**（会话状态存 Redis、TTL 即可、不要求持久化）。合流前方案正是"ChatMemory H2 持久化"，已由 DC-02 取代 |
| 坑 | 无 TTL 概念 → 会话表只增不减，需自建清理任务；多实例下要么共享库（会话写放大、争锁）要么各自建库（V-c3 立刻不成立）；会话属于"可选恢复"的易失状态，用持久化存储承载是资源错配 |
| 回归风险 | 若落地，V-c2（键自动消失）与 V-c3（多实例共享同一份会话）两项语义都要重做 |

### 3.3 方案三：JVM 内存（官方 `InMemoryChatMemoryRepository`）

| 维度 | 说明 |
|---|---|
| 技术上是官方能力 | 1.1.8 自带 `org.springframework.ai.chat.memory.InMemoryChatMemoryRepository`，零依赖、最快 |
| 为何否决 | **直接违反 DC-03**。进程内 Map → V-c3 多实例共享不成立（B 看不到 A 的会话）；V-c4 重启即丢；多实例下同一会话被负载均衡打到不同实例会"断片"；且无 TTL 上限，长期运行内存只增不减 |
| 适用残留场景 | 仅单实例部署 + 允许重启丢失时可降级使用（DC-03 的推翻条件已覆盖） |

---

## 4. 证据等级标注

| 结论 | 等级 | 依据 |
|---|---|---|
| V-c1 / V-c2 / V-c3 / V-c4 / V-c5 五项结论 | **【实测】** | 真实进程 + 真实 Memurai + 真实 deepseek-flash 调用，原始请求/响应与 CLI 输出留档（附件 A–D） |
| 1.1.x 官方 Redis 构件不存在 | **【实测】** | Maven Central `maven-metadata.xml` 原文 + 本地 m2 空目录 + 1.1.8 BOM 声明（§1.1a） |
| 官方 2.0.1 实现依赖 RedisJSON/RediSearch | **【实测-源码】** | 下载官方 2.0.1 sources jar 逐行核对（`jsonSet`/`ftCreate`/`ftSearch`/`IndexDataType.JSON`/Jedis `RediSearchUtil`） |
| 官方 `saveAll` = clear + add | **【实测-源码】** | 官方 2.0.1 `RedisChatMemoryRepository.saveAll` 源码正文 |
| 官方 `MessageWindowChatMemory` 每次 add 全量读+全量回写 | **【实测-字节码】** | `javap -c` 调用链 + 窗口裁剪行为（§2 V-c5 补充）双重确认 |
| `MessageChatMemoryAdvisor` 把首个 SystemMessage 置顶 | **【实测-字节码 + 实测-报文】** | `javap -c` 分支逻辑 + 上游请求体 `messages[0].role=system` |
| `createRequest` 不读消息 metadata | **【实测-字节码】** | `javap -c OpenAiChatModel`，ASSISTANT 分支只出现 `getToolCalls`/`getMedia`/`getText` |
| 时间戳在往返比较中必须排除的原因 | **【推断】** | 由本实现"序列化不改入参"推导，并用 `identical:true` 反证；非官方行为，属本实现取舍 |
| 生产 TTL 取值 / maxmemory 策略 / 大消息体截断建议 | **【推断】** | 基于本方案的数据形态推算，未做压测（见 §5） |

---

## 5. 生产化注意事项

### 5.1 TTL 取值

- 本 spike 默认 `spike.chat-memory.ttl=6h`（滑动过期：每次写入刷新）。DC-06 规定"会话以浏览器页签为界，
  无长期留存"，因此建议按**页签活跃寿命**取值：`30m ~ 2h`。默认 6h 偏保守，可按上述缩短。
- 滑动过期意味着"只要用户还在说，会话就一直活着"；若希望有硬上限，需额外维护绝对过期时间
  （当前实现**只有滑动 TTL，没有绝对上限**）——这是本实现的已知缺口，若业务要求"会话最长存活 N 小时"，
  需要在 `serialize` 里额外存 `createdAt` 并自行判断。**标记【推断】，需业务方确认是否要求绝对上限。**
- TTL 刷新时机仅在 `saveAll`（写）。读操作不续期，避免"只读不写也永不过期"。
  **标记【推断】**：若产品希望"查看历史也算活跃"，需改成读取时也续期。

### 5.2 maxmemory 与淘汰策略

- `maxmemory-policy` 建议 `allkeys-lru` 或 `volatile-lru`。本方案所有会话键都带 TTL，
  因此 `volatile-lru` 可精确命中会话键；但**索引键 `chat:mem:__ids__` 当前不带 TTL**，
  若用 `volatile-*` 系列，索引不会被淘汰，可能长期驻留且与主键失配
  （失效由 §2 V-c2(3) 的惰性清理兜底，但会短暂残留）。**标记【推断】，需按实际策略评估。**
- `allkeys-lru` 更省心（索引也可被淘汰，代价是 `findConversationIds()` 结果可能不全——
  但该接口在业务上并非关键路径）。**标记【推断】。**
- 键数量级估算：1 会话 = 1 LIST + 1 索引成员，会话量×2 个键，量级很小。

### 5.3 大消息体与列表长度

- **列表长度**：实测受官方 `MessageWindowChatMemory` 窗口约束（§2 V-c5 补充，`maxMessages=4` 时 LLEN 恒为 4）。
  本 spike 设 `maxMessages=40`。**这是防 LIST 膨胀的第一道也是唯一一道闸门**——没有它
  （例如误设为极大值），LIST 会随轮次线性增长。建议按"往返 token 预算 ÷ 平均消息长度"反推，
  并注意 `MessageWindowChatMemory` 的窗口是**按消息条数**、不是按 token。
- **单条消息体**：本实现 `LRANGE 0 -1` 全量读 + 全部反序列化，**没有单条大小上限、也没有读取条数上限**。
  风险场景：用户粘贴超长文本 / 模型输出超长 → 单条 JSON 数十 KB～数 MB。
  建议（**【推断】**，需业务方定阈值）：在业务入参侧限制用户输入长度；
  并在仓库层增加“单条消息序列化后超过 N KB 则截断并标记”的保护。
  当前实现**未做截断**，是已知未覆盖项。
- 官方 2.0.1 的 `findByConversationId` 带 `maxMessagesPerConversation` 读取上限，本实现**没有**；
  若担心异常膨胀的数据被一次性读入堆，可后续补一个 `LRANGE -N -1` 式的读取上限。

### 5.4 并发与升级

- **并发写同一 conversationId 未做保护**：`saveAll` 是 `DEL` + `RPUSH`，两个请求并发时"后写者胜"，
  可能出现丢消息。单页签场景通常不会并发，但多标签页/重试会。
  建议（**【推断】**）：按 `conversationId` 加 Redis 分布式锁，或接受该风险（业务上是"少一轮对话"而非数据损坏）。
  **本 spike 未实现，属已知限制。**
- **升级到 Spring AI 2.x 时**：官方 `ChatMemoryAutoConfiguration` 会提供官方 Redis 实现，
  与本实现可能冲突。届时要么删除本实现改用官方（需 Redis 模块），要么显式排除官方自动配置。
  升级评审时须把"Redis 是否已具备 JSON + Search 模块"作为前置条件。
- **Bean 装配**：官方 `ChatMemoryAutoConfiguration` 的 `chatMemoryRepository()` 与 `chatMemory()`
  都带 `@ConditionalOnMissingBean`，本 spike 显式声明这两个 Bean 后官方自动配置自动让位，无重复 Bean。
  落地时保持这一方式，不要同时引入官方 starter。

### 5.5 可观测性

- 会话键不在 `INFO`/慢查询里体现，排查"记忆丢失"需要能按 conversationId 直接看 Redis。
  建议保留 `/api/dev/raw/{sessionId}` 这类只读诊断接口的等价能力（生产可收敛为内部管理端点）。
- 本 spike 的 `verbose` profile（`spike.evidence-logging=true`）通过 `RestClientCustomizer`
  记录每次上游 HTTP 状态码与报文，**只为取证**，生产不加载。

---

## 6. 推翻条件

本结论在下列任一条件出现时需重审：

1. Spring AI 在 **1.1.x 线**补发官方 Redis `ChatMemoryRepository`（或业务方同意升级到 2.x 且运行环境启用
   RedisJSON + RediSearch）→ 应改用官方实现，删除本 SPI 扩展。
2. 业务方要求会话**跨重启长期留存 / 审计回溯**（DC-02 的推翻条件）→ Redis + TTL 不再适用，需回到持久化存储路线。
3. 部署形态退回**单实例**（DC-03 推翻条件）→ 可降级为官方 `InMemoryChatMemoryRepository`，本实现可删。
4. 出现"同一 conversationId 并发写导致丢消息"的真实线上问题 → 需补分布式锁（§5.4）。
5. 观测到 LIST 长度或单条消息体失控（如 `maxMessages` 被误配、用户输入无上限）→ 需补读取/写入上限与截断（§5.3）。

---

## 7. 收尾确认

| 项 | 结果 |
|---|---|
| 实验 Redis 键 | 已全部删除：`chat:mem:vc1-s1`、`chat:mem:vc2-ttl-5s`、`chat:mem:vc3-shared`、`chat:mem:vc4-restart`、`chat:mem:vc5-tools`、`chat:mem:roundtrip-check`、`chat:mem:window-check`、`chat:mem:prune-probe`、`chat:mem:__ids__`，各 `DEL` 返回 `1` |
| 删除后核对 | `KEYS chat:mem:*` 空；`DBSIZE` = 0 |
| 进程 | 仅停止本任务自启实例（18302 / 18312 对应 PID），未触碰其他服务（另有 `sp01ab-*` 的 java 进程属他人任务，保持原样） |
| 端口 | `netstat` 确认 18302 / 18312 无监听（仅残留内核 TIME_WAIT，无进程占用） |
| 脱敏 | 决策卡与全部附件已复检：无本机绝对路径（`<MAIN_V2>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<USER_HOME>` / `<WORK_DIR>` 占位）、无 API key（`***`）、无用户名 |
| Redis 配置 | 未做任何配置修改（仅读操作 + 本次实验键的写入/删除） |
| git | 未执行任何 git 写操作 |
