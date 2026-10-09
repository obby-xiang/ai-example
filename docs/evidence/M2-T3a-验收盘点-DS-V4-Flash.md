---
source: DeepSeek V4 Flash
date: 2026-10-09
scope: M2-T3a
baseline_head: 2125126
入库 commit: 8ff4d17 之后补档
---

# M2-T3a 验收盘点报告（基线 HEAD=2125126）

纪律：全程零 git 写操作；未读写任何 .env/密钥；未改动仓库内任何文件。探针脚本放仓库外 <SCRATCH>。前端目录为 frontend/。

## 验收清单逐项结论（摘要，关键证据保留）

### T3-1 慢订阅者隔离
| # | 条款 | 结论 |
|---|---|---|
| 1 | emitLock 收窄（锁内只留取号/落档/touchActivity/入队） | PASS（SseChatEmitter.java:562-597） |
| 2 | 共享有界投递池（容量初值 4 可配、AbortPolicy 溢出摘除） | PASS（:161-179；AiProperties.java:160-183） |
| 3 | 每订阅者有界队列+CAS drain 独占 | PASS（:608-621、:731-753） |
| 4 | R2 收尾顺序"先清标志→复查非空→重新 CAS 抢回" | PASS（:665-670；并发用例 :144-191） |
| 5 | R3 池拒绝⇒摘除 | 代码 PASS（:626-634）/覆盖 FAIL（无池拒绝用例）→ S2-2 |
| 6 | replay 并轨锁内原子交接 | PASS（:218-238、:281-301） |
| 7 | 溢出摘除=主动断连+断点续收无洞用例 | PASS（:685-694；用例 :89-112、:195-224） |
| 8 | 不变量"队列容量<归档窗口 3000"单测锚定 | 部分（无显式断言、256 双处字面量）→ S3-1 |
| 9 | R1 裁决双口径落地 | PASS（:281-301 无容量闸；RunStore EVENT_WINDOW=3000） |
| 10 | S2-8 容量联动公式注释 | PASS（AiProperties.java:53-62、:149-159） |
| 11 | 验收①单慢订阅者不阻塞同轮其他（<1s） | PASS（:60-85） |
| 12 | 验收②回放 3000 帧不阻塞 emit | 部分（未直接测量；锁内序列化成本）→ S3-2 |
| 13 | 验收③摘除后重挂 seq 无洞 | PASS（:195-224） |
| 14 | 验收④8 路并发 0 丢消息回归 | 未验证（E2E 口径，归 T3-5；另见 S1-1） |
| 15 | /api/ai/health 投递池指标 | PASS（T3-5 观测面提前落地，:181-190、AiController.java:531） |

### T3-2 seq 持久化 + delta 跳过归档
| # | 条款 | 结论 |
|---|---|---|
| 16 | ai:seq 键 TTL 与归档同寿 | PASS（RunStore.java:90,146-148、:588-600） |
| 17 | nextSeq=max(归档末帧,ai:seq)+1 发放即 SET | PASS（SseChatEmitter.java:535-556） |
| 18 | SET 失败 WARN+继续出帧+分支用例 | PASS（:588-600；用例 :228-243） |
| 19 | delta 跳过归档与 ai:seq 同批 | PASS（RunStore.java:87,537-556） |
| 20 | includeDelta/死分支/persistedFrameCount 处置 | PASS（persistedFrameCount 零引用独立 grep 确认） |
| 21 | events 端点全量读为回放唯一读者 | PASS |
| 22 | 验收①归档部分失败续号无重号 | PASS（SeqPersistenceTest.java:63-85） |
| 23 | 验收②delta 不进归档 | PASS（:88-108） |
| 24 | 验收③既有 263 全绿 | PASS（272/0/0/0，263=272−9 自证） |

## 复跑验证（实测）
- 后端 mvn -B test：surefire XML 42 文件求和 272/0/0/0；控制台 272；grep 口径 272；三口径一致。基线 263，+9=SseChatEmitterDeliveryTest 6+SeqPersistenceTest 3；5 个既有测试文件无 @Test 增删。
- 前端 yarn typecheck（vue-tsc --noEmit）PASS；yarn build PASS。前端零代码改动。

## 范围外改动检查
git diff --stat 10 改 +580/−165；13 条全映射设计卡条目。范围外改动 1 处（S3 级）：SseChatEmitter.java:461 frontend_tool_result 帧新增 field kind，设计卡无对应要求、证据未登记；无行为影响（前端不读该字段），要求撤销或补登记。

## 独立发现（探针实测）
探针输出：[A 池饱和] 出帧=4 队列已投递=0 丢失=4；[D 4 线程占满] 丢失 3；[B 竞态] 200 次试验尾帧未送达 4 次（2%）；[C 静态池泄漏] 装配前即时到达=1、装配后即时=0、200ms 后=1。
- S1-1（阻塞）：轮终态 complete()/registry.close() 不排空队列 → 慢订阅者/池饱和下确定性丢帧（含终帧与不可复原 delta 正文）。RunRegistry.java:69-74、SseChatEmitter.java:717-722 直接 completeOne 不 drain；生产调用点 AiController.java:185-188、ResumeService.java:356-361 轮末 finally。终态回放路径 :671-674 已有"队空才 complete"，轮终态路径漏了。
- S2-1：进程级静态投递池泄漏到两参 RunRegistry（单测路径），测试顺序相关隐性竞态（ChatTurnFrameSequenceConformanceTest.java:73）。
- S2-2：R3 池拒绝分支零单测；施工证据 1.2 口径偏差（把队列溢出用例记为 R3 验证）。
- S3-1 不变量无显式断言；S3-2 "不阻塞 emit"未测+锁内序列化成本未量化；S3-3 真实 RunStore 跳档分支无直接单测+锁内每帧 SET；S3-4 delta 不入档后正文尾部不可回放复原未登记；S3-5 范围外改动未登记。

## 结论
有条件通过（机械面全 PASS），但独立发现 1 项 S1 阻塞缺陷，严格口径等价不通过。M2-T3a 未闭环：T3-5 压测与 E2E 27 用例未执行。
【待裁决】1. S1-1 修复或登记接受；2. S2-1 两参构造固定直执与否；3. S2-2 补 R3 单测+证据口径更正；4. 范围外改动撤销或补登记；5. T3-5+E2E 归 T3a 收尾棒；6. S3-1~S3-4 随批处置。
