---
source: DeepSeek V4 Pro
date: 2026-10-09
scope: M2-T3a
baseline_head: 2125126
入库 commit: 8ff4d17 之后补档
---

# M2-T3a 红队攻击报告（DeepSeek V4 Pro）

对象：<REPO_ROOT> 工作区未提交全态（HEAD=2125126，14 条改动）。事实源：设计卡 v1.2、证据文档 §1~7（只作线索）。纪律：零 git 写操作；未读 .env；探针放仓库外 <SCRATCH>。

## 0. 验证基线（实测）
mvn -B test → 277/0/0/0 BUILD SUCCESS；42 XML 独立求和一致。
探针（仓库外 <SCRATCH>，复用既有 classpath）：
- 原 Probe.java（攻击 S1-1 旧缺陷）：[A 池饱和] 丢失=0、[B 200 次竞态] 0%、[D 4 线程占满] 丢失 0、[C 静态池泄漏] 装配前后均即时到达=1 → 修复批第 1 轮闭环。
- 新增 ProbeR1.java（攻击 R1 双口径）：见 S1。

## 1. 六个关键判断点结论
| # | 判断点 | 结论 |
|---|---|---|
| 1 | S1-1 并发正确性 | 通过（三路并发 completed CAS 恰好一次；terminating volatile 可见；R2 顺序正确；probe A/B/D 零丢失） |
| 2 | R1 双口径不变量 | 击破（回放批与实时帧共用一条队列，实时闸按绝对队列长度判定；慢重挂者回放排空期被心跳确定性摘除；probe 实测复现） |
| 3 | T3-2 正确性 | 通过（惰性 anchor 无重号；时钟不参与；状态帧严格递增子序列） |
| 4 | S2-1 修复 | 通过（生产零两参调用；三参恒异步池） |
| 5 | 新 5 条用例真断言 | 通过（:262/:286/:321 真断言；:360/:378 有 2 处弱断言见 S3） |
| 6 | 自由攻击面 | 1×S1、8×S3 |

## 2. 问题清单
### S1（阻塞）
S1-1 R1 双口径被击破：回放 backlog 污染实时容量闸，慢重挂者被实时帧确定性摘除 → 断点续收饥饿。
- 代码：stageReplay（SseChatEmitter.java:321）与 enqueueLive（:637-643）写同一条 sub.queue；实时闸判据是绝对长度 sub.queue.size() >= queueCapacity；emit（:617-625）对心跳与业务帧一视同仁 enqueueLive。
- 探针实测：场景 1 seed 3000 帧 → 慢订阅者 replayAndAttach staged=3000 → 首帧阻塞 → 一条心跳 → 摘除（reason=实时队列溢出容量 256），complete=true，收到 0 帧。场景 2 lastSeq=1 重挂 staged=2999 → 一条心跳再次摘除，收到 0 帧。
- 复现路径：① 归档铺 >256 条状态帧；② 慢订阅者首帧写出阻塞 replayAndAttach；③ 发任一实时帧（挂起期心跳 2s 一次必触发）；④ 观察 evict+complete。
- 影响：T3-1 要保护的慢重挂者恰是受害者；摘除后重挂若剩余回放 ≥256 再次摘除，确定性饥饿。违反 R1 裁决意图。
- 修复方向：实时段容量与回放 backlog 分离（独立计数器或回放完成后才开闸；或物理有界+回放暂存道）。证据文档 §五.2 偏差登记正是本缺陷的自我暴露。

### S3（8 条）
1. emitter 生命周期三回调（:230-232）只 remove 不清队列/不复位 draining → 死订阅者内存泄漏+drain 标志永卡；TerminalSemanticsEmitter 不触发这三回调，路径零覆盖。
2. T3-2 使 ai:events 不再被 delta 续 TTL（appendEvent delta 分支改走 touchActivity 写 ai:beat，RunStore.java:543-551）；session-ttl=6h 下仅长流式轮次可触及；seq 不受影响。
3. twoArgRegistry 用例（:360）弱守卫：未先 configureSharedDeliveryPool，隔离运行本就回落直执，修复前代码同样通过。
4. S3-1 不变量用例（:378）无法捕获等于 256 的第二处字面量。
5. completeWhenDrained 的"CAS 抢到"两分支无定点用例（:262/:286 均覆盖抢不到路径）。
6. EVICTED_TOTAL 静态累计无重置口，health evicted 是进程累计值，指标语义需文档标注（T3-5 口径影响）。
7. nextSeq 在 emitLock 内 2+ 次 Redis 往返（lastEventSeq/lastIssuedSeq 读+recordIssuedSeq SET+appendEvent），Redis 慢时放大整轮出帧延迟；登记延迟观察项。
8. stageReplay/emit 序列化失败→部分投递/提前 return（理论性，概率≈0，登记留痕）。

## 3. 总评
不通过（1×S1 阻塞）。修复批本身质量高且实测闭环；但 T3-1 R1 双口径实现（enqueueLive 绝对长度闸+单队列承载两类帧）被实测击破，慢重挂者被心跳摘除并重挂饥饿。须在 T3-5 压测前修正，并补"回放排空期混入实时帧不摘除"定点用例（现有 replayBatchBypassesLiveCapacityUpToTheEventWindow 先 awaitSize(3000) 排空后才发实时帧，绕过竞态，属假安全）。修复方向：实时段容量计数与回放 backlog 分离。
