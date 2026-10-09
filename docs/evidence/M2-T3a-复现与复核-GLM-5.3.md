---
source: GLM-5.3
date: 2026-10-09
scope: M2-T3a
baseline_head: 2125126
入库 commit: 8ff4d17 之后补档
---

# M2-T3a 复现与修复批第 2 轮复核（GLM-5.3）

（说明：GLM-5.3 先后执行两棒——复现卡针对修复批第 1 轮全态，复核卡针对修复批第 2 轮全态。以下为两棒合并正文。）

## 第一棒：独立复现（修复批第 1 轮全态，277 基线）
基线：HEAD=2125126，工作区 14 条未提交（11 改+3 未跟踪）。全程零 git 写操作、未读 .env、脚本在仓库外 <SCRATCH>。
逐条裁决：
1. mvn 全量 277/0/0/0、基线 263 只增不减（+14）——通过。mvn -B test 退出码 0，日志末行 Tests run: 277 + BUILD SUCCESS；42 个 surefire XML 求和 277/0/0/0（mtime 全在运行窗口）；grep 口径 277，双口径零差异（无 ParameterizedTest/RepeatedTest/Disabled，2 个 @Nested 合并入父类 XML）。git grep @Test HEAD=263、工作树=277，净增 +14；5 个被修改测试文件 @Test 数 HEAD=工作树（28=28）无用例删减。分解：施工批 9（DeliveryTest 6+SeqPersistenceTest 3）+修复批 5=14。
2. yarn typecheck 绿——通过。vue-tsc 2.2.12/TS 5.9.3 退出码 0；非空洞性验证（--listFiles 实际检查 46 文件；仓库外负向对照工程正确报错）。
3. S1-1 修复有效性（:262/:286）——通过。TerminalSemanticsEmitter 桩件 complete 后 send 抛 IllegalStateException 与真实 SseEmitter 同口径，防住"先 complete 再排空"假绿；两用例全量 277 中实跑通过。
4. R3 池拒绝摘除用例真实覆盖——有条件通过（覆盖真实，行号锚定漂移：任务卡记 626-634 为施工期旧值，证据文档修复批 §7.3 已更正为 655-664，与实测吻合）。
5. S2-1 两参固定直执+生产三参共享池——通过（:360 非恒绿；三参半句无直接单测，读码旁证 RunRegistry.java:60-65 分流明确，可接受）。
6. frontend_tool_result 帧无 kind 新增字段——通过（git diff HEAD 全量过滤，kind 相关行仅 deliveryPoolMetrics 3 处新增）。
7. 脱敏合规 14 项含证据文档自身——通过（盘符/凭证/命名禁则组合三规则 0/0/0 命中，正则 27 条正向对照自检）。
总评：复现通过。问题：S3-1 行号锚定漂移（建议统一以终版态为准或改方法名锚定 submitDrain#catch）；S3-2 三参装配路径无直接自动化用例（可选增强）。

## 第二棒：修复批第 2 轮复核（284 基线）
基线：HEAD=2125126，工作区 14 条未提交，收尾复核未变。
8 条声称逐条复核：
1. 实时闸改按 liveQueued 计数——通过。闸判定 SseChatEmitter.java:693；入队 :696-697；drain 递减 :755-756→:709-711（兜底不为负）；清理归零 :279；回放入队 :370 包装 QueuedFrame(payload,false) 不计数；主代码无 queue.size() 绝对长度判定（grep 仅测试注释 :179）。单队列 FIFO 混合序保留（QueuedFrame :966-978）。
2. 红队场景两定点用例——通过。:185 在 3000 回放仅排出 1 帧时混入心跳+delta，修复前闸判 2999+1≥256 必摘除、断言 completes()==0 确定性红；:218 修复前第一条心跳即红。另断言 3003 帧全达、FIFO 序、seq 3001 续接。
3. 假安全用例声明收窄——通过（:144-149 javadoc 明确只锚定入队豁免并指向真锚点用例）。
4. 生命周期三回调统一走 closeSubscription——通过（:250-252→:275-282；drain finally :772-776 改 canTakeOverDrain CAS 后置；三回调用例 :495/:500/:505 经共用断言 :516-545）。
5. twoArgRegistry 加固——通过（:448-467 先装配静态池断言前置成立再断言直执，修复前两参读静态池必红）。
6. completeWhenDrained 两分支用例——通过（:550-564、:576-597，RecordingExecutor 可观测可定点驱动）。
7. 全量 284/0/0/0+typecheck 绿——通过（mvn 控制台+42 XML 求和双口径；单类连跑 3 轮 18/0/0/0 耗时波动<3%；vue-tsc strict 退出码 0；基线算术 272→277→284 与证据链一致，277 系第 1 轮态无法独立复跑，采旁证+算术一致）。
8. 脱敏合规——通过（14 项全量内容扫描，三规则 0 命中含证据文档自身）。
红队探针闭环复跑：ProbeR1 两场景修复后 complete=false、deliveredAtComplete=-1（从未收尾）=未摘除。
5 个关键判断点：
1. liveQueued 全路径正确性——基本正确，存在极窄理论性多计窗口（add :696 与 increment :697 非原子间隙可产生永久+1 幽灵；需 emit 线程恰在两条相邻指令间被抢占且池线程瞬间调度，概率极低；仅近饱和订阅者可能提前摘除，重挂自愈；判 S3；一行加固可闭合：incrementAndGet 前置于 queue.add，无界队列 add 不失败无反向幽灵）。【后续：指挥官已采纳，微修落地，284 复跑全绿】
2. 红队击破路径闭合——闭合（ProbeR1 复跑+读码推演一致；seq 续接 :611-631）。
3. 新用例真伪——全部真锚定，无恒绿构造。
4. drain finally CAS 后置无新竞态——未引入（R2 协议保留：入队锁内紧跟 kickDrain，守卫复查必见非空补投；池拒绝由 R3 路径兜底；终态收尾无早收尾窗口）。
5. 结论：复核通过。
问题清单：S1 无、S2 无；S3-观察 1 add→increment 非原子幽灵计数（已随微修闭合）；S3-观察 2 twoArgRegistry 用例装配进程级静态池（首装配者胜，登记备查）。
总评：复核通过。8 条声称全通过；红队击破路径探针外部复跑+用例内部定点双重实证闭合；全量、抗抖、typecheck、脱敏四项机械验证全绿。
