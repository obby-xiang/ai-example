# SP-01 独立复核报告（GLM / Challenger）

- **复核日期**：2026-10-06（本地时间 21:22–21:38）
- **复核人**：独立复核工程师（GLM），与原 PoC 作者（Kimi K3）无共享上下文
- **复核对象**：`SP-01c-决策卡.md`、`SP-01ab-决策卡.md`、`SP-01d-决策卡.md` 及其附件 A–E
- **方法**：不采信卡内任何结论，按各卡 §"复现步骤"独立构建并运行核心验证；对每张卡的
  "有条件可行"条件与【推断】标注设计反例实验主动攻击；对【实测】标注逐项核对原始留档。
- **环境**：JDK 21.0.12、Maven（<MAVEN_HOME>）、Memurai（<MEMURAI_HOME>，127.0.0.1:6379，复核前 DBSIZE=0）、
  deepseek-flash（key 仅环境变量 `DEEPSEEK_API_KEY=***`）。端口 18301–18305/18312 复核前逐一 `netstat` 确认空闲，**未发生换端口**。
- **原始证据**：全部归档于 `docs/spike/logs/glm-review/`（已脱敏：无本机绝对路径、无 key、无用户名）。
- **纪律**：仅停止自启进程；无 git 写操作；未修改 spike 源码与决策卡原文。

---

## 1. SP-01c（Redis 会话记忆）复核

### 1.1 复现项逐条比对

独立构建 `spike/sp01c`（`mvn clean package` 成功），双实例启动（A=18302/PID 15676、B=18312/PID 10192，
A 加载 `verbose` profile），按卡 §1.3 原步骤执行。

| # | 验证项 | 卡声称 | 我实测 | 判定 |
|---|---|---|---|---|
| 1 | V-c1 多轮记忆 | 第 2 轮 reply="42"，historySize 2→4，history 4 条 | reply=**42**，historySize 2→4，history 恰 4 条（USER/ASSISTANT 交替） | **一致** |
| 2 | V-c2 TTL 到期 | TTL=5s 键 8s 后消失（TTL=-2/EXISTS=0/history=[]） | TYPE=list→TTL 5→等 8s→**TTL -2、EXISTS 0、LRANGE 空、history []**；索引 `chat:mem:__ids__` 仍残留过期 id（与卡 §2(3) 惰性清理声明一致） | **一致** |
| 3 | V-c3 双实例共享 | B 复述 A 写入的 7/紫水晶 | A(18302) 写 2 轮 → B(18312) history 读到同一 4 条 → B 续问 reply=**"你让我记的数字是 7，让我记的词是紫水晶。"** | **一致** |
| 4 | V-c5 七轮工具调用 | 7×HTTP 200、零 400、工具 8 次（getServerTime=3/calculate=5）、LLEN=14 | **7×200、零 400、tool-stats {total:8, getServerTime:3, calculate:5}、LLEN=14**；实例 A verbose 日志 `UPSTREAM POST /v1/chat/completions -> HTTP 200` 全部为 200，无 4xx/5xx | **一致**（数字逐项吻合） |
| 5 | 上游无 4xx/5xx（verbose 侧证） | 附件 B 15 次 200 | 我全程 19 次 200、0 次非 200 | **一致**（次数差异见 §1.3-3，系归档口径问题，不影响结论） |
| 6 | 读操作不续期 TTL（§5.1 声明） | 【推断】读不续期 | 独立探针：SET EX 6 → 4s 时 GET history（返回 []）→ 8s 后 EXISTS=0，**读取未续期** | **一致**（把卡的推断升级为实测确认） |

V-c4（重启恢复）按任务书三项必跑未强制要求，未单独执行；其机制（Redis 外置 + TTL 滑动）已被 V-c2/V-c3 覆盖，
不构成结论风险。

### 1.2 反例实验

**反例 1：同 sessionId 并发写（攻击卡 §5.4"未做并发保护、可能丢消息"的【推断】）**

设计：8 个并发 `POST /api/chat` 打同一 sessionId `glm-conc`，每轮记住不同编号 1–8，完成后核对 Redis 历史。

结果（`logs/glm-review/sp01c/evidence/glm-conc-write.json`）：

- 8 个请求全部 HTTP 200（**没有任何并发拒绝机制**，与 sp01ab 的 409 形成对照）；
- 最终历史 15 条 = **7 USER + 8 ASSISTANT**（正常应为 8+8=16）：**编号 5 的用户消息被彻底丢弃**；
- 8 个响应的 `historySize` 互相矛盾（9/0/14/13/12/15/9/11），每个请求都以为自己是"对的"；
- 后续追问"有没有编号 5"，模型据损坏历史回答"没有"——**丢消息对用户不可见、无法自愈**；
- 未引发上游 400（多退少补的 USER/ASSISTANT 恰好没有破坏 tool 配对）。

**判定：卡的诚实边界成立且被低估——并发丢消息不是"可能"，8 并发下必现 1 条丢失。卡已把它排除在验证范围外并列入推翻条件 4，处理诚实；但"业务上是少一轮对话而非数据损坏"（§5.4）的说法偏乐观：实测还会产生 7:8 的失配历史长期驻留在会话里。**

**反例 2：读不续期 TTL**（见 §1.1-6）——证实卡的【推断】标注准确。

### 1.3 证据诚实性评价

1. 【实测】标注可信：附件 A/B/D 的关键数字（reply、historySize、TTL、LLEN、tool-stats）与我独立运行逐项吻合；
   附件 D 七轮记录结构完整（轮次/HTTP/耗时/回复原文）。
2. **一处口径瑕疵**：卡 §2 V-c5 声称 `grep ... 附件-B | uniq -c` 得 **15** 次 UPSTREAM 200，但我 grep 同一附件 B
   实得 **25** 次（且全文共 76 处 UPSTREAM 行）。差异原因：附件 B 是"实例 A 四次运行"的合并日志（卡自己在 §1
   环境表里写明 PID 22048/16496/6444 多次重启），15 只是其中一次运行的数字，卡引用时未加"本轮"限定。**不影响
   "零 400"结论**（25 次全为 200），但"15"作为引用数字与附件原文对不上，属小幅不严谨。
3. 【推断】标注（TTL 绝对上限、maxmemory 策略、并发写风险）均如实标注为推断，且推断 1 已被我的反例 1 证实。

**SP-01c 总判定：复现一致**（分歧仅附件 B 计数口径一处，不影响任何结论）。

---

## 2. SP-01ab（前端工具暂停-恢复 + HITL 确认门）复核

### 2.1 复现项逐条比对

独立构建 `spike/sp01ab`，启动取证代理（18305）与应用（18301，base-url 指代理），按卡 §1 原命令跑两条断言套件。

| # | 验证项 | 卡声称 | 我实测 | 判定 |
|---|---|---|---|---|
| 1 | 非流式断言套件（A 场景前端工具） | A0–A10 共 11/11 PASS | **11/11 PASS**（最终回答含 42 与前端令牌 姓名张三-FRONTEND-HUQ76G） | **一致** |
| 2 | 非流式 HITL ① 放行 | B1–B6 6/6，delete_config 计数 +1 | **6/6 PASS**，计数 +1，回复"def-777 已删除" | **一致** |
| 3 | 非流式 HITL ② 拒绝 | C1–C7 7/7，计数 +0 | **7/7 PASS**，计数 +0，回复"未执行——已被拒绝" | **一致** |
| 4 | 非流式 HITL ③ 超时(30s) | D1–D9 9/9，waitedMs=30002，15 帧心跳 | **9/9 PASS**，waitedMs=30001，**15 帧携 pending 心跳**、连接不断 | **一致** |
| 5 | 流式断言套件 | 同上 33 项（11/6/7/9） | 流式 **同样 33/33 全 PASS**（A/B/C/D 四场景） | **一致** |
| 6 | 上游侧（代理留档） | 16 次模型请求零 400；reasoning_content 存在但不回填、无 400 | 我两轮套件共 **20 次上游请求、20×HTTP 200、零 400**；12 个响应体含 `reasoning_content` | **一致**（请求总数因我多跑了反例而更多；零 400 与 reasoning_content 结论吻合） |

### 2.2 反例实验

**反例 1：确认门挂起期间杀死应用进程（攻击卡 §2.4 条件 2"挂起态在进程内存，重启即丢"的【推断】）**

设计：发起敏感删除对话 → 捕获 `confirm_request`（+1405ms 挂起，toolCallId=call_00_YsDH…）→ 在挂起第 ~2s
`taskkill` 应用（PID 26832）→ 重启（新 PID 8928）→ 探测会话状态并补交 confirm。

结果（`logs/glm-review/sp01ab/kill-during-suspend-*.log`）：

- 杀进程前 SSE 一直有心跳；**30s 确认门超时在杀进程前先到**（挂起后 30.0s `confirm_decision{decision:timeout}` + `tool_result{executed:false}`），未执行删除——与套件 D 一致；
- 重启后 `GET /api/events/{sid}/timeline` → `{"exists":false,"events":[]}`：**挂起会话彻底消失**；
- 补交 `POST /api/confirm(approve)` → **HTTP 404 `{"error":"unknown sessionId"}`**；
- 工具计数器归零（`toolCounters` 全 0）——**若用户在死机前已放行、删除尚未执行，决策与执行意图都会无声丢失**。

**判定：卡的条件 2【推断】被证实且应加重：不只是"挂起流程续跑不了"，已做的人工决策也会丢，且没有任何"会话曾在挂起中死掉"的痕迹留给前端。多实例/重启续跑必须走卡 §5 方案 B，这一点卡的边界划分正确。**

**反例 2：同会话并发对话（验证卡 §2.4 条件 6 声称的 409）**

设计：第一轮发敏感删除（进入确认门挂起）→ 4s 后同 sessionId 再发一轮普通对话。

结果：第二轮 **HTTP 409 `{"error":"session already running"}`**；随后补交第一轮的 reject → 200，第一轮正常以拒绝语义收尾。
**与卡的条件 6 完全一致**（会话级单值门 + 并发拒绝；且拒绝并发不影响挂起中的那一轮）。

### 2.3 证据诚实性评价

1. 卡的【实测】全部有留档支撑：`docs/spike/logs/sp01ab/{blocking,streaming}/` 中 wire-01..08 两轮各 8 个
   请求/响应原文、timeline、sse-raw、checks 俱全，且我抽查的 status 全为 200、与卡 §7.2 的 16 次零 400 声明吻合。
2. 卡 §7.4 主动披露了 A5 曾失败（模型不复述令牌）并说明修正方式——这种"记录失败再修正"的写法可信度高。
3. 未发现把推断写成实测的情况；§2.4 的条件 2/3、§7.6 均如实标注【推断】（其中条件 2 已被我的反例 1 实测证实）。

**SP-01ab 总判定：复现一致**（33+33 断言全过，三结局与心跳行为、并发 409、进程死亡行为全部与卡一致）。

---

## 3. SP-01d（断流重试/去重/取消）复核

### 3.1 复现项逐条比对

独立构建 `spike/sp01d`，启动故障代理（18304）与应用（18303，base-url 指代理，verbose），按卡 §1.1 原命令逐 case 执行。

**V-d1 断流有界重试：**

| case | 卡声称 | 我实测 | 判定 |
|---|---|---|---|
| vd1-baseline（对照） | 17 delta / 28 字符 / done OK | 17 delta / 28 字符（"当前服务器时间：2026-10-06 21:31:52。"）/ done{terminal:OK} | **一致**（delta 数与字符数逐项吻合） |
| vd1-guarded-first（首包静默） | attempts=2 / 10.0s / `UPSTREAM_SILENT_TIMEOUT` / 代理台账 2 次请求 | start@7ms → retry@5024ms → error@10030ms；attempts=2、两次尝试各 ~5000ms；代理台账 2 次 `STALLED_THEN_SOCKET_RESET` | **一致** |
| vd1-guarded-mid（产出后静默） | attempts=1 / `..._AFTER_PARTIAL` / emittedChars=2 / 6.9s | delta"我是"(2 字符) → error@6894ms；attempts=1、`UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL`、partial=true | **一致** |
| vd1-guarded-tooldup（工具后静默） | attempts=1 / `..._AFTER_TOOL_SIDE_EFFECT` / 工具台账仅 1 条 | attempts=1 / 同 code / 台账 1 条 `getServerTime:OK` | **一致** |
| vd1-ref-first（ChatClient+retry） | 重订阅抛 `IllegalStateException: No StreamAdvisors available`、代理仅 1 次真实请求 | error=UPSTREAM_STREAM_ERROR，rawReason=**`IllegalStateException: No StreamAdvisors available to execute`**；refSubs=2、代理台账仅 1 次请求 | **一致** |
| vd1-refbare-first | 同上（与自定义 advisor 无关） | 同样 IllegalStateException、refSubs=2 | **一致** |
| vd1-refmodel-first（ChatModel+retry） | 能重订阅、语义正确（2 次真实上游请求） | `UPSTREAM_SILENT_TIMEOUT`、refSubs=2、代理 2 次真实请求 | **一致** |
| vd1-refmodel-mid | 客户端收到 **"我是我是"**（重放前缀） | concat=**"我是我是"**、dupPrefixLen=2、attempts=2 | **一致**（逐字复现） |
| vd1-fin-content（静默截断） | terminal=OK、emittedChars=4（被当成功） | done{terminal:**OK**, emittedChars:4, deltas:2}，无 error | **一致** |
| vd1-truncate-rst | `UPSTREAM_CONNECTION_BROKEN`、attempts=2 | 同 code、attempts=2（EOFException） | **一致** |
| vd1-truncate-fin | 空回复也算 OK | done{terminal:OK, emittedChars:0} | **一致** |

**V-d2 去重：**

| case | 卡声称 | 我实测 | 判定 |
|---|---|---|---|
| vd2-spi-dup（官方循环同 id 两次） | 执行 2 次、历史落 2 条同 id tool 响应、不报错 | executionCount=**2**（getServerTime×2）、toolResponseIdsInOrder=[同 id, 同 id]、error=null | **一致** |
| vd2-fronttool（前端回灌幂等） | ①首次 200 accepted=true/DONE/round=2/messageCount=5；②同 id 200 duplicate=true；③未知 id 409 | ①200 accepted=true status=DONE round=2 messageCount=5 finalText 含回灌时间；②200 `DUPLICATE_TOOL_CALL_ID` 且 messageCount 仍 5、round 不前进；③409 `UNKNOWN_TOOL_CALL_ID` | **一致**（三次提交行为逐字吻合） |

**V-d3 取消：**

| case | 卡声称 | 我实测 | 判定 |
|---|---|---|---|
| vd3-coop-cancel | CANCELLED_AFTER_6_SLICES、durationMs=1004、取消→终止 776ms、done{cancelled,CANCELLED,content 保留} | outcome=**CANCELLED_AFTER_6_SLICES**、durationMs=1004、取消→终止 **883ms**、done{cancelled:true,terminal:CANCELLED,content:"我来执行这个长任务。"} | **一致**（883ms vs 776ms 属正常波动，含取消后一次上游往返） |
| vd3-hard-cancel | 工具跑满 3s、取消→终止 2590ms（轮次边界） | durationMs=3000（跑满）、取消→终止 **2881ms**、done{cancelled:true,CANCELLED}、注册表终态 registered=[] | **一致**（2881 vs 2590 同为"工具剩余+一次往返"量级） |

### 3.2 反例实验

**反例 1：超时阈值 < 工具耗时（复现并加固卡 §2.1④"超时须大于最长工具耗时"）**

设计：以 `SPIKE_INTER_EVENT_TIMEOUT=2s` 重启应用（其余默认 5s 首包/2 次尝试/20s 预算），无故障注入，
让模型调 `longTask(seconds=5, cooperative=false)`，预测 5s 工具在 2s 处被误杀。

结果（`logs/glm-review/sp01d/evidence/glm-short-timeout-5s-tool*.json`，两次运行）：

- 两次运行工具台账均为 `longTask ... durationMs=1989 / 2001, outcome=OK` —— **5s 的工具在 ~2.0s 处被
  InterruptedException 打断**（与卡 8s/6s→5999ms 同构：截断点=interEventTimeout）；
- 终态 `UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT`、attempts=1（**信标正确阻止了重试**，工具没有被执行第二次——
  这同时验证了卡 §5.4 修正后的副作用判定）；
- 客户端已收到的部分文本保留在 content 但流以 error 终止。

**判定：卡的硬约束 3 在另一组参数（2s/5s）下复现成立；guarded 的三条件门禁在误杀场景下行为正确。**

**反例 2：同 sessionId 并发两轮（攻击卡 §6.5"会话状态按 sessionId 持有、并发互相污染"）**

设计：同一 sessionId 并发发起两轮 guarded SSE（一轮"用一句话介绍"、一轮"用两句话介绍"）。

结果（`logs/glm-review/sp01d/evidence/glm-conc-sid.json`）：

- 两轮 HTTP 200 且各自正常 done（各 26/46 个事件）——**sp01d 不像 sp01ab 那样拒绝并发**；
- 但会话终态 `content` = **两轮回复文本拼接在一起**（"我是韧性验证助手……我是韧性验证助手……"），
  `state.runId` 只剩其中一轮——**终态/内容/attempts 互相覆盖，与卡 §6.5 描述完全一致**；
- 取消接口只认 sessionId，并发下取消哪一轮取决于注册表里最后写入的 runId（由源码结构推出，未单独实验）。

**判定：证实卡 §6.5。生产必须按 runId 隔离运行态或禁止同会话并发，卡已把该点写入生产注意事项，处理正确。**

### 3.3 证据诚实性评价

1. 附件 B/C/D 的关键数字（error code、attempts、emittedChars、工具台账、时间线）与我的独立运行**逐 case 吻合**，
   包括最反直觉的三处：`"我是我是"` 重放、静默截断报 OK、`IllegalStateException: No StreamAdvisors available to execute`。
2. 卡 §5.4 主动披露了第一版实现的缺陷（只判"已产出"导致工具被重复执行）并注明早期日志未归档、复现方式明确——
   属于诚实的自我纠错记录，且"信标修正后 attempts=1"被我的反例 1 间接复验。
3. 【推断】标注（90s 确认门超时外推、生产阈值取值、多实例 Redis 化）均如实标注未实测；其中"超时误杀"的外推
   已被我的 2s/5s 反例在另一参数点证实。
4. 未发现把推断写成实测的情况。

**SP-01d 总判定：复现一致**（11 个 V-d1 case + 2 个 V-d2 + 2 个 V-d3 全部吻合，两组反例均证实卡声明的边界）。

---

## 4. 总结论

| 卡 | 总判定 | 分歧/问题清单 |
|---|---|---|
| SP-01c | **复现一致** | ①（轻微）卡 §2 V-c5 引用附件 B "15 次 UPSTREAM 200"，grep 同一附件实得 **25** 次——附件是四次运行的合并日志，15 缺"本轮"限定；不影响零 400 结论。② 并发丢消息风险（卡已声明未覆盖）实测 8 并发必现 1 条丢失，且产生 7 USER/8 ASSISTANT 失配历史永久驻留，建议生产化时把 §5.4 从"可接受"升级为"必须处理"（分布式锁或按 sessionId 串行化）。 |
| SP-01ab | **复现一致** | 无实质分歧。补充实测证据：挂起期杀进程后人工决策（若已放行）会无声丢失、无任何痕迹（404 unknown sessionId），比卡的条件 2 表述更严重一档，落地时需前端可感知的挂起态外置。 |
| SP-01d | **复现一致** | 无实质分歧。两组新参数反例（2s 超时误杀 5s 工具、同 sessionId 并发状态串写）均证实卡的硬约束与已知限制；信标修正被验证有效。 |

**总体评价**：三张决策卡的核心链路全部独立复现成功，关键数字逐项吻合，未发现任何"把推断写成实测"的证据造假；
唯一问题是 SP-01c 一处附件计数引用口径不严谨（15 vs 25）。卡中所有"有条件可行"的条件与诚实边界标注经反例攻击后
**全部成立**，其中两处（并发写丢消息的严重度、挂起期进程死亡的决策丢失）实际后果比卡的措辞更重，建议在技术方案
阶段将对应条目升级为硬性前置工作项。

---

## 5. 收尾确认

| 项 | 结果 |
|---|---|
| 自启进程 | sp01c 实例 A/B（PIDs 15676/10192）、sp01ab 应用与代理（PIDs 26832/8928/13160）、sp01d 应用与代理（PIDs 26792/24320/25928）全部停止；未触碰任何非本任务进程 |
| 端口 | `netstat` 复核 **18301/18302/18303/18304/18305/18312 均无 LISTENING** |
| Redis | 实验键（`chat:mem:*` 5 个，含 `__ids__`）已逐一 DEL；`KEYS chat:mem:*` 为空、`glm-*` 无残留、**DBSIZE=0**（与复核前一致） |
| 脱敏 | 报告与 `docs/spike/logs/glm-review/` 全量扫描：无本机绝对路径（`<MAIN_V2>`/`<MAVEN_HOME>`/`<MEMURAI_HOME>`/`<EVIDENCE_DIR>` 占位）、无 API key（`***`）、无用户名 |
| git | 未执行任何 git 写操作 |
| 修改范围 | 仅新增 `docs/spike/SP-01-GLM独立复核报告.md` 与 `docs/spike/logs/glm-review/**`；spike 源码与三张决策卡原文未动 |
