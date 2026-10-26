> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1557` 的 wire 末条 assistant 文本（消息序号 m=33；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-23 08:54 → 2026-10-23 09:00（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`e00692f`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：21502 B / 12375 字符 / 116 行；行指纹覆盖率（本批复算）1.3%（最高单文件 1%：`docs/任务看板.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：裁决）
> - 与既有件关系：施工报告；报告口径覆盖率 0.01
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
已完成仓库事实核对与 9 组联网核验（JaCoCo 官方 API/变更日志/FAQ、Maven Surefire 参数表与 SUREFIRE-1910、Spring TestContext 生命周期、GitHub status checks、SE@Google 第 23 章、微软 TIA、Develocity PTS）。以下是评审报告。

---

# 评审报告：设计-选择性测试落地方案（2026-10-23）

**评审方自证**：读本 agent wire.jsonl 的 `profile.bind` 记录 —— `agentId=agent-1557`，`modelAlias=DeepSeek/deepseek-flash`，`profileName=coder`，`thinkingEffort=high`。全程零文件改动、零服务起停、未触碰 8081/5202、仅用只读 git 命令。

## 总判：**认可但需改**

一句话理由：JaCoCo 逐测试录制 + 类级倒排的技术底座成立且有官方 API 支撑，但**连接键、diff 基准、Spring 上下文归属、surefire 开关四处会直接让选择器“静默空转或错选”**，不修则 B 期一上线就是“看起来在工作、实际永远全量或永远漏选”。

仓内事实基线（本次实测，用于校验设计稿数字）：测试类 256、`@Test` 标注 1671、`@SpringBootTest` 35、使用 Mockito 106 个文件、引用 Redis 87 个文件、异步原语（ExecutorService/CompletableFuture/new Thread）46 个文件、嵌套类 32 处、匿名类 3 处、`src/test/resources` 不存在、`ai/conformance/` 实为 8 个文件、当前 HEAD `e00692f`（设计稿写 `e754453`）、近 14 天 382 笔提交、工作树有 4 个未提交改动 + 未跟踪文件。

## 一、联网核验五问的结论（先给结论，细节见后文条目）

| # | 问题 | 核验结论 |
|---|---|---|
| 1 | JaCoCo 逐测试录制可行性/坑 | **可行**：`IAgent.getExecutionData(boolean reset)` 是官方 API，“reset=true 随后清空”；0.7.7 起“空探测数组不写盘，显著减小逐测试 dump 体积”说明官方预期该用法。**坑**：①类名是 VM 名（斜杠分隔）不是点分 FQN；②运行期被改写类（Mockito 等 mock 框架）与 class id 不匹配，FAQ 明写；③探针是类级全局数组，无线程归属；④`reset` 是全局语义，只能靠“串行执行”维持。 |
| 2 | 覆盖率倒排的失效面 + 各家对冲 | Google：presubmit 只跑“快而稳”，postsubmit 兜底全量，全公司级 flake 分类，flaky 测试临时移出 presubmit；Meta：99.9% 捕获口径 + 训练前对失败测试激进重试；Gradle Develocity：最近新增/改动/失败/flaky **必须**选中，历史不足回退“全部 impacted”，且**覆盖率统计与 PTS 不同构建**；微软：未知文件类型回退全量 + 周期全量（官方标注 recommended）+ T1/T2 对照法。 |
| 3 | “提交级选择集 + 合流级全量”是否主流 | **是主流，但落点相反**。业界（Google/微软/Develocity/Nx）把选择层放在 **CI 的 presubmit**（有日志、可审计、可回放），全量放 postsubmit/nightly；本方案把选择层放在**人工本地提交步**、CI 侧保持全量——分层方向对，可审计性缺。 |
| 4 | F1–F9 有无漏 | **父任务点名的四类在本仓基本不适用或已覆盖**：JNI/LoadLibrary 0 处；ServiceLoader 0 处（`META-INF/services` 若存在也在 `src/main/resources/**`，被 F2 覆盖）；`implements Serializable` 0 处；时区/locale 反而**有实际雷**（2 个测试类调用全局 `Locale.setDefault`）。真正的漏是“结构/顺序/生命周期”三类：配置类与上下文归属、选择器自身变更、测试类改名/删除、表确定性、异步与顺序依赖（详见 K3/M3/M4）。 |
| 5 | 前端“不选择化”是否合理 | **合理**。全量 46.33 s、`related` 中位命中 76/110 个 spec、公共类型 0 收益，把 46 s 的门禁换成“漏测信任成本”不划算；Jest/Vitest 官方都只说 `related/--changed` 用于本地。 |

## 二、必改项（7 项，未改不建议进入 B 期）

### K1 · blocker · §5.1（:301-311）+ §7.3：选择集的 diff 基准对“提交前门禁”是错的

`--base "$(git merge-base origin/main HEAD)" --head HEAD` 比较的是**已提交历史**，而 L1 是“提交官在提交前跑”（调研件 §3.4：`scripts/githooks/pre-commit` 只做脱敏、全量复跑是提交前纪律）。也就是说：**被测试的这笔变更根本不在 diff 里**，选择器会按“上一笔提交”选测试；未跟踪的新测试/新类对 `git diff` 完全不可见（本仓当前就有 `?? docs/research/…` 与 `?? data/`）。
依据：设计稿 :306-311 自身命令行；仓内 `scripts/githooks/pre-commit`。
修法：门禁改为对**暂存树**取 diff —— `git diff --cached --name-status <merge-base>` + `git ls-files --others --exclude-standard`；或把门禁移到 commit 之后（pre-push），并在文档里写清“L1 测的是已提交内容”。无论哪种，**新增文件必须单独处理**（新增测试必跑、新增生产类触发 F5）。

### K2 · blocker · §3.3/§3.4（:155-163、:187-188）：倒排表的连接键两端不是同一种名字

`ExecutionData.getName()` 返回的是 **VM 名（斜杠分隔）**，设计伪代码却用 `n.startsWith("com.example.configmgr")` 过滤、用点分 FQN 作 `index` 键 —— 一条都匹配不上，倒排表会退化为空表（表现为“每次都 F5 无键 ⇒ 永远全量”，最难察觉的那种失败）。第二处不一致：`git diff` 得到的是 `Outer.java → com.example.Outer`，而覆盖率记录里嵌套类是 `com/example/Outer$Inner`（本仓 32 处嵌套类、3 处匿名类），不归一就丢边 ⇒ **欠选**。
依据：JaCoCo `ExecutionData.getName()` 定义为“VM name”，`IClassCoverage` 明确“fully qualified class name in VM notation (slash separated)”。
修法：`name.replace('/','.')`，再反复剥掉 `$...` 归到最外类，**同时保留原名**作为第二键；并在 B 期自测里加一条断言“解析出的键集合与 `git ls-files src/main/java` 的 FQN 集合交集 ≥ 95%”，空表直接 fail-fast。

### K3 · blocker · §3.7 / §6.1（:229、:339）：Spring 上下文缓存让“上下文测试会被自然选中”不成立

Spring TestContext 的上下文是**按配置缓存复用**的：“ApplicationContext 通常在准备测试类实例时加载”，之后按 LRU 淘汰而非每类重建。因此 **Bean 构造、`@Configuration`、`@ConditionalOn*` 判定、`@ConfigurationProperties` 绑定这些“只在建上下文时执行一次”的代码，只会记到每个上下文配置的“第一个测试类”头上**；其余 `@SpringBootTest` 类复用缓存，只记它们测试方法真正调到的 bean。设计稿因此高估了覆盖：改一个纯装配型 bean，选中的是“最先建该上下文的那个类”，不是 35 个。更糟的是 surefire 默认 `runOrder=filesystem`，谁是“第一个”取决于目录扫描顺序，**倒排表在机器间不稳定**。
依据：Spring 官方 TestContext 生命周期文档（上下文在测试类实例准备时加载、按 LRU 从缓存淘汰）。
修法：加 **F10 兜底规则**：变更集触达 `@Configuration/@Component/@Service/@Repository` 类、或 `@ConditionalOn*`、或 `@ConfigurationProperties` 类 ⇒ 直接选中**全部 35 个 `@SpringBootTest`**（这仍是 40% 时长里的子集，可接受）。§3.6 里“1–2 个上下文冒烟类”补不了这个洞，除非那个冒烟类恰好是该上下文的 first-mover。

### K4 · blocker · §3.5/§3.8/§5.1（:210、:250、:311）：`-DfailIfNoTests=false` 不是那个开关

Surefire 参数表：`failIfNoSpecifiedTests`（`surefire.failIfNoSpecifiedTests`，默认 **true**）负责“`-Dtest` 指定的测试一个都没跑到就失败”；`failIfNoTests`（默认 **false**）负责“项目里压根没测试”。SUREFIRE-1910 专门记录了这个误导性提示：“This is misleading as failIfNoTests is false by default. Actually I have to set surefire.failIfNoSpecifiedTests=false”。设计稿写的是后者（等于无操作），而调研件 §4.4 第 4 条写的是前者 —— 两件自相矛盾。后果很实在：**表里只要有一个被改名/删除的测试类（改名在 256 类里必然发生），每笔提交都会以“No tests were executed”变红**，一次就足以摧毁信任。
修法：命令固定为 `-Dsurefire.failIfNoSpecifiedTests=false`；选择器输出前**过滤掉磁盘上不存在的测试类**（`target/test-classes/<fqn>.class` 存在性校验），并在输出里列出被丢弃的陈旧条目。

### M2 · major（必改）· §6.1（:338）：“间接覆盖 能”是过强的表述

覆盖率记的是**执行过**，不是**依赖过**。`Class.forName` + `getDeclaredMethods()`、SPI 查表、Jackson/BeanUtils 只读元数据、`Class#getResource` 这类“加载但不执行”的依赖一条都录不到；JaCoCo 的 exec 数据其实**对每个加载过的类都有记录**（探针全 false），设计却主动用“有任一 probe 为 true”把它们滤掉了。Ekstazi 走的是文件/加载级依赖，正是为了这层安全。
修法：改为“**出现在 execution data 即算依赖**”（`hasHits()` 不作为过滤条件，只作为元信息），或双写 `dep`（加载）与 `hit`（执行）两套边、选择时取并集。代价是表变大（252 类规模可忽略），收益是把“只读元数据”的整类漏检面堵掉。

### M5 · major（必改）· §7.2 B5（:396-400）：验收方法学不成立

三点：①**历史提交重放用不了当前表**——表记录的是 HEAD 的类 id/边，重放旧提交必须在该提交上重跑录制（20 笔 × 录制全量 ≈ 2 小时），设计稿没预算；②设计稿**自己**的过期规则（`headSha` 差 > 200 提交 ⇒ 全量）会让多数重放直接返回 `--all`，测不出任何东西；③“在 10 个包各注入 1 个已知缺陷，验证选择集能变红”对覆盖率法是**同义反复**——缺陷注入到 A 类，跑过 A 的测试必被选中，这不验证任何失效面。
修法：注入**结构性缺陷**，每类至少 1 例：反射调用的方法改名、yml 默认值变化、新增 `@Configuration`、i18n message key 改名、依赖前序测试留下的 Redis 键、时区跨日断言；判据改为“该缺陷必须让选择集变红，否则算选择器 bug”。历史重放要么删掉，要么改成“在旧提交上重建表再选”。

### M6 · major（必改）· §7.2 B5 / §7.3（:396、:408）：“0 漏”阈值不现实且统计上无效

n=30 的“0 漏”只能把漏检率上界压到约 10%（rule of three：3/30），比 Meta 的 99.9% 低两个数量级、比 Google TRANSPRED“25% 预算下 85% 召回”也没有可比性。真正的统计力量在 C 期影子期：本仓近 14 天 382 笔提交，2 周影子能得到 ~380 个样本，0 漏可把上界压到 ~0.8% —— 这才是可以写进验收的数字。
修法：B5 降级为“冒烟/回归自检”，判据写成“30 例不允许连续 2 例同类漏”；**正式门槛放 C 期**：“2 周影子期漏检 ≤ 1 笔/300 笔提交，且每一笔漏都有根因归类并回填一条兜底规则或一个冒烟类”。另外，影子期“选择集与全量同跑”在现状下只增加 ~1–1.5 分钟（全量本来就要跑两遍 locale），这一点是现实可行的，可以在文档里写明以消除“影子期拖慢两周”的顾虑。

## 三、建议项

**M1 · major · §5.1/§5.3：选择层缺“可审计”环节。** 业界把选择放 presubmit 的核心原因不是快，而是**可回放**。本方案 L1 在人工本地跑，事后无法证明“当时选了什么、为什么”。修法：每次选择落一份 `backend/target/selection/<headSha>.json`（base、规则命中、选中集合、表 sha），提交信息里附一行摘要；影子期让 CI 也跑一次 T1（选择集）+ T2（全量）——这正是微软官方推荐的验证法，且比在开发机上比对更可信。

**M3 · major · §2.4（F1–F9）：缺 3 条规则。** F11“选择器/门脚本自身变更 ⇒ 本次全量 + 重建表”（RTSCHECK 27 个 bug 的先例）；F12“同一 headSha 两次录制的表不一致 ⇒ 该批测试标记 unstable，进永久冒烟集”（这正是 K3/M4 的可观测化）；F13“locale/时区敏感面变更 ⇒ 合流级强制 en-US”（本仓已有 2 个测试做全局 `Locale.setDefault`，见下条）。父任务点名的 JNI/SPI/序列化三项**经实测在本仓不存在**（0/0/0），不必为它们加规则，但建议在设计稿里写一句“已核验不适用”，避免下一轮评审重复提问。

**M4 · major · §3.3 要点 2/3（:170-171）：类边界 dump 在异步仓里会同时“多算”和“少算”。** 46 个测试文件用 ExecutorService/CompletableFuture/new Thread、87 个引用 Redis。JaCoCo 探针是类级全局数组、**无线程归属**：上游测试遗留的线程在下一个类的窗口里执行 ⇒ 边记到错误的类（多算，安全方向）；测试在 `AfterAll` 之后才完成的异步工作 ⇒ 边丢失（**欠选，危险方向**，且本仓 SSE/watchdog/异步 run 正是主力面）。修法：`afterTestExecution` 与 `afterAll` 各 dump 一次并合并；JVM 结束时把“最后一个窗口”并入最后一个类；再加 M3 的 F12 确定性校验兜底。

**M7 · major · §7.1/§5.3：A 期三处具体错误。**
- `e2e` 加 `if: always() && …` 会在 `backend` **失败**时照样跑 25 分钟 E2E（还会拿坏代码打包）；应为 `if: ${{ !cancelled() && needs.changes.outputs.e2e == 'true' }}`。
- workflow 也由 `push: [main]` 触发，此时 `github.base_ref` 为空，`git diff origin/main...HEAD` 退化；push 事件应取 `github.event.before`。
- §5.3 第一行说反了：GitHub 官方明确“**被跳过的 job 报告为 Success，即使它是 required check 也不会阻止合并**”（pending 只发生在 workflow 级 `paths` 过滤）。所以真实风险不是“卡住”，而是**静默放行**——`后端编译与单测` 若是 required，A 期上线后它会在纯文档 PR 上自动变绿。修法就是设计稿已有的“恒跑汇总 job”，但要把理由从“防卡住”改成“防假绿”，并要求汇总 job 显式断言“backend 要么 success 要么因变更集不相关而 skipped，且 changes 输出已归档”。

**M8 · major · §2.4 F9（:120）/§5.1：命令行长度这条退路过早。** 8191 是 `cmd.exe` 限额；本仓门禁在 Git Bash 下跑，超限表现是 `Argument list too long`（E2BIG），阈值也不同。更重要的是 F9 一命中就退回全量，等于把收益全丢掉。Surefire 自带 `includesFile`（`-Dsurefire.includesFile=…`，since 2.13），把类清单写文件即可彻底消灭 F9。

**m1 · minor · §3.6（:220）：** `ai/conformance/` 实际 8 个文件（`CancelLedger`、`ChatTurnFrameSequence`、`FrameTypeSetFrozen`、`ReattachLastSeq`、`SessionBusyFrameIsolation`、`SseFrameSequence`、`ThreeArgAssembly`、`ToolFrameSequence`），第 9 个 `KbCitationIdConformanceTest` 在 `ai/kb/`。要么改口径为“目录 glob”，要么显式列出 9 个全名——否则按枚举实现会漏 3 个（设计稿正文只点名了 4 个）。

**m2 · minor · §2.3（:103）表保鲜判据用“7 天 / 200 提交”，而非内容哈希。** 一次跨类搬代码的重构可以让大量条目失真却不触发过期。轻度修法：表里为每个生产类存 `git blob sha`（或 class 文件 CRC），选中运行后校验；不一致即视为“无键”走 F5 全量。Ekstazi 用的就是 checksum 差分。

**m3 · minor · §3.3/§3.4：Mockito 会污染倒排表。** 106 个测试文件用 Mockito，Mockito 5 默认 inline mock maker 在运行期生成并重定义 `Foo$MockitoMock$<随机数>` 同包类 —— ①随机后缀会让表每次重建都不一样（与 F12 的确定性格格不入）；②JaCoCo FAQ 明说 mock 框架改写字节码会导致“类被执行却显示未覆盖”的 class id 不匹配。修法：录制时排除 `$MockitoMock$`、`$ByteBuddy$`、`$$EnhancerBySpringCGLIB$$`、`$Proxy` 前缀；并在 B 期验收里加一条“两次录制的表条目集合差异 = 0”。

**m4 · minor · §4.2（:269-276）：** `xargs` 缺 `-r`，变更集为空时仍会以无参调用一次 `vitest related --run`；且未处理含空格/中文的路径。补 `xargs -r -d '\n'`。

**m5 · minor · §3.8（:248）与 §2.4 F8（:119）互相矛盾：** “变更集为空 ⇒ 只跑冒烟集”与“选中结果为空 ⇒ 全量”冲突（冒烟集非空时永远到不了 F8）。建议明确：空变更集**只跑冒烟并打印“未跑全量”**，同时把 F8 收窄为“命中兜底后仍为空”。

**m6 · nit · §6.3（:359）：** “粘性失败 3 次成功后退场”无出处；Develocity 的口径是“最近失败的**永远**选上”，没有失败计数退场机制。建议改成时间窗（7 天内失败过就常跑），比计数更容易解释。

**m7 · nit · §3.1（:126）事实正确但可加强：** 已复核 `spring-boot-dependencies-3.5.14.pom` 内 jacoco 出现 0 次（“父 POM 不管理版本”成立）；0.8.15 = 2026/06/04、官方支持 Java 26，也成立。建议补一句“0.8.16 目前只有 snapshot（2026/10/19），故钉 0.8.15 是当前最新正式版”，避免后人误以为落后一版。另 §9 的 `meta.headSha: e754453` 应改占位符，当前 HEAD 已是 `e00692f`。

**m8 · nit · §1.1 G1 目标（≤90 s）缺下限论证：** 35 个 `@SpringBootTest` 里 30 个占 54.4 s（留痕），加冒烟集与启动开销，理论下限已接近 60–70 s；建议把 G1 写成“中位数 ≤90 s，且不早于 B 期实测重定”，别把推算值当承诺。

## 四、业界证据链接

- JaCoCo 运行时 API `getExecutionData(boolean reset)` — https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/agent/rt/IAgent.html
- JaCoCo `ExecutionData`（“VM name of the class”、class id = CRC64） — https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/core/data/ExecutionData.html
- JaCoCo `IClassCoverage`（“fully qualified class name in **VM notation (slash separated)**”） — https://www.jacoco.org/jacoco/trunk/doc/api/org/jacoco/core/analysis/IClassCoverage.html
- JaCoCo 变更日志（0.8.15=2026/06/04 支持 Java 26；0.7.7“空探测数组不写盘，显著减小逐测试 dump 体积”；0.8.16 snapshot 2026/10/19） — https://www.jacoco.org/jacoco/trunk/doc/changes.html
- JaCoCo FAQ（JVM 内 API / remote dump+reset；**mock 框架改写字节码导致 class id 不匹配**；“类已执行却显示未覆盖”） — https://www.jacoco.org/jacoco/trunk/doc/faq.html
- Maven Surefire `test` 参数表（`failIfNoSpecifiedTests` 默认 true / `failIfNoTests` 默认 false / `runOrder` 含 filesystem / `includesFile` since 2.13 / `-Dtest` 模式语义） — https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html
- SUREFIRE-1910（“Set -DfailIfNoTests=false” 是误导，真正要设 `surefire.failIfNoSpecifiedTests=false”） — https://github.com/apache/maven-surefire/issues/2945
- Spring TestContext 生命周期（上下文在测试类实例准备时加载、缓存并按 LRU 淘汰） — https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html
- GitHub Status checks（“**A job that is skipped will report its status as Success**，即使它是 required check 也不阻止合并”） — https://docs.github.com/en/pull-requests/reference/status-checks
- SE@Google 第 23 章（presubmit 只跑快而稳、接受覆盖损失、postsubmit 兜底；TAP 会把部分 presubmit 测试优化到 postsubmit；全公司 flake 分类） — https://abseil.io/resources/swe-book/html/ch23.html
- Azure DevOps TIA（安全回退全量、周期全量“recommended”、先前失败+新增测试纳入、T1/T2 对照法、范围限制） — https://learn.microsoft.com/en-us/azure/devops/pipelines/test/test-impact-analysis?view=azure-devops
- Gradle Develocity PTS（历史不足回退全部 impacted；“always selects tests that were recently added, changed, failed, or found flaky”） — https://docs.develocity.ai/2026.3/using-develocity/predictive-test-selection/
- Meta PTS（99.9% 捕获 / 约 1/3 子集 / flake 激进重试） — https://engineering.fb.com/2018/11/21/developer-tools/predictive-test-selection/
- Google TRANSPRED（ICST 2025：25% 预算下 85% 召回；静态图选择在大规模不可持续） — https://hackthology.com/speculative-testing-at-google-with-transition-prediction.html
- Ekstazi（文件级动态依赖、checksum 差分，与本方案“按内容而非按时间保鲜”的对照） — https://github.com/gliga/ekstazi
- RTSCHECK（三个 Java RTS 工具共 27 个 bug） — https://www.cs.cornell.edu/~legunsen/pubs/ZhuETAL19RTSCheck.pdf
- Vitest CLI（`related`/`--changed`/`experimental.recordDependencies`，静态 import 限制） — https://vitest.dev/guide/cli.html

**未做的事**：未实测 JaCoCo 录制开销（B1 的 ≤2× 基线仍属未验证承诺）；未跑 `-Dtest` 传入 FQCN 的形式验证（本仓 256 个测试类简单名互不重复，两种形式应等价，建议 B 期第一步用 1 个类实跑 30 秒确认）；§7.2 B4 的“选中中位数 ≤25%”在 86% 测试集中在 `ai/` 且 8 个类扇出 >50 的结构下偏乐观，必须等首轮录制实测再定。
