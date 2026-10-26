<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-290 · 施工复核 · 一、复核结论（Kimi K3 复核卡）：**通过，附 2 个红队必查点**（e6aea5ae / main）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`backend/src/`、`ai-example-<OLD_REPO_DIR>`、`RunStore`、`SseChatEmitter`。
> **稿态**：历史中间稿 —— 该 wire 第 `m14` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt9` 条有正文的 assistant 消息（共 25 条有正文），末条有正文消息为 `m34`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e6aea5ae` / agent `main`（`main` = 主会话）；原字符 3,954（链内 1 段、合计 3,954）。
> **链号 / 类别**：`MT-290` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `9%`。
> **与末条归档（138 件）的关系**：末条 `m34`（6,268 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

复核（Kimi 卡）完成，六项裁决如下，随后是 GLM-5.3 红队任务卡（请用户转交）。

---

## 一、复核结论（Kimi K3 复核卡）：**通过，附 2 个红队必查点**

抽查 S1/S1.2/S1.5/S5/待裁决全文 + S2~S9 摘要与附件清单，数据自洽性核验：

- S1 矩阵 20/20 格无留白，饿死规律「M < poolSize ⟺ 不饿死」在四档池容量（4/8/16/20）一致复现，机制解释（慢 drain 占满工作线程）与 R3 语义吻合；
- S1.5 boundedElastic=160 为反射实读非假设，且有 S8 日志线程名旁证；
- S5 建议表约束校验链完整（256 双向校验：不误摘+及时摘 414ms）；
- 诚实性良好：二分结论不圆场、【推断】项不冒充实测、6b/6c/6d 缺口如实上报。
- **存疑交红队**：① S6「SET 失败」用不可达端口 6399 注入——需确认抛的是连接异常而非真 SET 失败语义，路径覆盖是否等价；② S1 饿死是探针 latch 构造的必然产物还是真实 TCP 背压下同样成立（S8 只有 1 条慢连接，未覆盖 M≥4 真实 HTTP 形态）。

## 二、六项裁决

| # | 裁决 |
|---|---|
| 1 | **采纳**：`delivery-pool-size` 4→**8**、`delivery-queue-capacity` 维持 **256**。红队通过后授权回灌 |
| 2 | **采纳**：冻结文档闭环措辞只声明三项已实测（池定值/16 核联动公式/判据①④），resilience 四值与心跳不随包冻结 |
| 3 | **采纳第 3 选**：联动公式改为准入式——启动期 WARN 级校验（不 fail-fast）+ 文档明示最低核数（pool=8 ⇒ CPU≥5）；回灌小棒落实 |
| 4 | **采纳**：不复活分片/游标续灌（保 S5c-2 交接原子性），三条触发观察项登记（跨机 Redis/窗口>3000/p99 SLA<10ms）；S4 二分结论原话入证据与闭环措辞 |
| 5 | **采纳**：EVICTED_TOTAL 本轮不改，登记观察项（最小形态=三 AtomicLong 拆分，留给后续） |
| 6 | 6a **授权回灌小棒顺带实现** `/health` 补三键（deliveryQueueCapacity/boundedElastic 估算值标来源/心跳间隔），纳入红队范围；6b/6c/6d **登记为「真 key 压测路线遗留项」**，挂到 nightly 真 key 授权业务决策项下，不阻塞本轮闭环；6e 差值口径接受；6f 知悉 |

## 三、GLM-5.3 红队任务卡（请转交 GLM-5.3）

```text
# M2-T3-5 压测报告 红队攻击任务卡（GLM-5.3）

## 角色与分工条款
你是红队（GLM-5.3），任务是攻击一份已完成的压测报告，找出不实、夸大、口径错误或复现不了的结论。
【子智能体分工】你可以派自己的子智能体（deepseek-v4-flash）执行机械活：全量扫描、批量 grep、
大文件通读、长测试实跑、探针复跑、日志翻找；你本体只做攻击面分析、关键判断、结论裁决与报告撰写。
子任务描述可直接下发自包含提示词。

## 攻击对象
1. 主报告：<LOCAL_WORK>\t35-reports\T3-5-压测报告-DS-V4-Flash.md（+ 同目录 17 个附件，逐一可查）
2. 仓库在途产物（主仓库工作区，分支 main，HEAD=1a28216，以下均为未提交未跟踪文件，只读勿动）：
   - docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md（入仓脱敏版）
   - docs/evidence/probes/M2-T3-5-Probe{Matrix,Lock,Replay,Delta}.java
   - backend/src/test/java/com/example/configmgr/ai/conformance/ThreeArgAssemblyConformanceTest.java
   主仓库路径：<REPO>（报告引用写作 <MAIN_REPO>，你产出同样用此占位）

## 报告核心结论（你攻击的靶子）
- S1：「N 挂起 × M 慢订阅者」矩阵 20/20 格；健康订阅者不被饿死 ⟺ M < deliveryPoolSize；
  现值 4 时 M≥4 即确定性饿死全部健康订阅者；池扫描 4/8/16/20 四档一致。
- S3：锁内 Redis 往返占状态帧 emit 延迟 79.1%（p50 359.6µs，本机 Memurai）。
- S4 二分结论：回放 3000 帧「网络写出不阻塞 ✅ / 锁内收集+序列化不可忽略 ❌（p50 8.86ms）」；
  回放豁免实时容量（3000 帧入队 liveQueued=0，第 257 条实时帧才摘）。
- S5 定值建议：delivery-pool-size 4→8，queue-capacity 维持 256。
- S6：delta 不入档、跳档续号 max+1 无重无跳、SET 失败（不可达端口 6399 注入）WARN+继续出帧。
- S7：新增 5 条 conformance 用例，全量 289/0/0/0（XML 求和口径，基线 284 只增不减）。
- S8：真实 HTTP 慢客户端 414ms 被摘除，健康侧最大帧间隔 3ms，6004/6004 零丢，重挂 seq 无洞。
- S9：E2E 桩模式 PASS=27 FAIL=0 GFSKIP=0 退出码 0；前端 typecheck/build 绿。

## 重点攻击面（至少覆盖，不限于）
1. 【指挥官指定】S6 SET 失败注入口径：6399 不可达端口抛的是连接异常——这与真实「SET 命令失败」
   （如 Redis 只读副本、OOM noeviction 拒绝写）是否同一路径？RunStore#recordIssuedSeq 的 catch
   是否两种都覆盖？读码+（如可行）构造更真实的失败注入验证。
2. 【指挥官指定】S1 饿死规律是否为探针 latch 构造的必然产物：慢 SseEmitter 用 latch 永久阻塞
   send——真实 TCP 背压（内核缓冲满）是否同样占死池线程？S8 只测了 1 条慢连接，M≥4 的真实
   HTTP 形态未覆盖，评估此外推风险等级。
3. S4 豁免实测「第 257 条才摘」与 liveQueued 计数器实现是否一致（读 SseChatEmitter#enqueueLive/
   #liveDequeued 验证探针结论不是误读）。
4. 测试计数独立复现：跑 mvn -f backend/pom.xml -B test（Maven 全路径
   "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"），
   XML 求和是否确为 289/0/0/0；ThreeArgAssemblyConformanceTest 的静态态卫生（反射复位）
   是否留下跨用例污染隐患。
5. S1 饿死规律独立复现：至少复跑 1 格（如 N=10,M=4,pool=4 应饿死 + pool=8 同格应绿）。
   探针复跑口径：把 docs/evidence/probes/M2-T3-5-ProbeMatrix.java 另存仓库外为 Probe.java
   （类名 public），classpath = backend/target/classes + test-classes + dependency:build-classpath 产物。
6. 报告内部一致性：S2 的 evicted 差值口径（起点非零）是否在所有引用处一致标注「进程累计语义」；
   S5 建议表约束校验与本机核数（16）是否匹配；有无【旁证】冒充【实测】。
7. 环境：JDK 21 在 PATH；Memurai 127.0.0.1:6379（启动前 netstat 查占用）；端口 18330 起、
   启动前必 netstat、只停自己启动的进程；AI key 不需要（本卡全部桩/进程内路线）。

## 硬约束
- 零 git 写操作，不修改仓库任何文件（含在途产物）；探针复跑一律另存仓库外。
- 报告落盘：<LOCAL_WORK>\t35-reports\红队报告-GLM-5.3.md（你只回报路径，不贴全文）。
- 每条发现给：严重级（S1 阻断/S2 重要/S3 观察）、证据锚点（类名#方法名 为主锚）、是否独立复现。
- 打破任何一条核心结论都算红队胜；打不破也要写明「攻击了哪里、为何不成立」。
```

---

**当前在轨**：等 GLM 红队报告返回 → 若通过，派回灌小棒（AiProperties 4→8 + 注释、启动期核数 WARN 校验、/health 补三键、冻结文档三处闭环注记）→ 提交官落盘（在途 6 文件 + 回灌改动）。工作区现有 6 个未跟踪在途文件属预期，勿动。
