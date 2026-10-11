> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 红队报告：M2-T3-5 定值回灌 · 轻量确认（GLM-5.3）

> 角色：红队（GLM-5.3），对已双卡通过的 T3-5 压测结论的**回灌落地动作**做忠实性确认。
> 不攻击已裁决口径（deliveryPoolSize 4→8、queueCapacity 维持 256、WARN 校验不 fail-fast、
> /health 补三键、SseChatEmitter 类注一行保留、ADR-2/DC-12 语义锚点）。
> 攻击对象：`<MAIN_REPO>` HEAD=`1a28216`，工作区 16 条未提交改动（7 M + 9 ??）【实测：`git status --porcelain` 与
> 回灌报告 §0.3 逐条一致】。零 git 写操作；复跑产物落 `<SCRATCH-REDTEAM>\`。

## 0. 复现抽查（攻击面 6，实测）

- **mvn 全量复跑**（子智能体 deepseek-v4-flash 机械执行，日志 `<SCRATCH-REDTEAM>\mvn-test-redteam-rerun.log`）：
  `EXIT_CODE=0`，surefire XML 求和 `XML_COUNT=46 / tests=298 / failures=0 / errors=0 / skipped=0`，
  与回灌报告 §3.1 完全一致（289→298 只增不减成立）。三新类逐行：
  `AiPoolCapacityBudgetTest` 5/0/0/0、`AiPropertiesT35DefaultsTest` 2/0/0/0、
  `AiControllerHealthSseDeliveryTest` 2/0/0/0。`[ERROR]` 行 0。
- **复跑合规**：复跑前后 `git status --porcelain` 与 HEAD 均不变（16 条 / `1a28216`），未动仓库任何文件。
- /health 起后端实测未重复执行（回灌报告 §3.4 已有真机 curl 七键原文，且本红队经 MockMvc 端点测试
  独立覆盖，见 §3）；复现抽查"至少一项"已满足。

## 1. 发现清单

### F1（S3 · 观察，建议随批修正）「官方硬编码不可配」与 WARN「实测值」表述与 reactor 实情不符

- **锚点**：`AiPoolCapacityBudget#warnMessage`（文案「boundedElastic 缺省容量实测值 = …（= 10 × CPU，官方硬编码不可配）」）、
  `AiPoolCapacityBudget#verify`（消费该文案）、类注「由 reactor 硬编码推导为 10 × availableProcessors()（不可配）」。
- **实证**（`<SCRATCH-REDTEAM>\reactor-check\RtProbe.java`，reactor-core 3.7.18 = 本项目实际解析版本）：
  - 默认：`DEFAULT_BOUNDED_ELASTIC_SIZE = 160`（16 核 × 10）——与推导口径一致；
  - 设 `-Dreactor.schedulers.defaultBoundedElasticSize=20` 后：常量**直接变为 20**（绝对值覆盖，非乘数）。
  即该常量**可经系统属性配置**，「不可配」为事实性错误；类文件常量池亦含该属性名（双证）。
- **影响**（限设置了该 sysprop 的部署，仓库自身零引用【实测 grep 零命中】）：
  ① 若运维用该 sysprop **上调**容量（恰是本 WARN 场景的常见处置）→ 启动 WARN 仍按 10×CPU 报「不满足」，
  数字与「实测值」标签双失真（`/health` 的 `boundedElasticCapacity` 走反射实读，不受影响——两口径会互相矛盾）；
  ② 若**下调**（如 32 < 48）→ 启动校验按推导值 160 判「满足」而**漏报**真实容量不足——校验器在唯一需要它报警的形态下静默。
  `minRequiredProcessors` 的 /10 系数在覆盖形态下同样失真。
- **修正建议**（一行级，不要求本棒返工）：WARN 文案与类注改为「推导口径（未设
  `reactor.schedulers.defaultBoundedElasticSize` 系统属性时 = 10 × CPU）」，或 `verify` 前先反射实读容量参与判定。
- **不构成 S2/S1 的理由**：默认路径（本项目全部现有配置与 CI 形态）推导值 ≡ 实读值，行为与门禁全绿；
  风险仅在仓库外的部署参数介入时显现。

### F2（S3 · 观察）/health 每请求一次反射；反射失败路径每请求一条 WARN

- **锚点**：`AiController#sseDeliverySnapshot` → `AiPoolCapacityBudget#boundedElasticCapacity`（每次 `Schedulers.class.getField(...)`）。
- 反射 `getField` 线程安全、`Map.of` 不可变、快照局部变量——**无线程安全问题**【实测审读】；低频诊断端点上性能代价可忽略。
  唯一毛边：若 reactor 未来改字段名，失败回落不但改值还会**每次 /health 请求打一条 WARN**（日志放大器）。可缓存为惰性
  static 一次读。观察级，不阻断。

### F3（S3 · 观察）冻结文档注记与 §13.2#2 的闭环措辞有字面落差

- **锚点**：`DECISION-CARDS.md` ADR-2 修订条与 `DECISION-REGISTER.md` 表后注记均写「§13.2 #2 定值表同批标闭环」，
  而 `docs/03-技术方案文档-v2.1.md` §13.2 #2 行的实际标注是「**T3-5 部分闭环（2026-10-09）**」。
- **实质未越界**【实测逐字核对】：注记正文只声明「投递池定值 8/256 回灌 + 联动式改启动期校验 + 判据①④复跑通过」，
  对 resilience 四值与心跳 2s/5s 的措辞均为「维持现值，真 key 路线遗留」——**未声明未实测项闭环**；技术方案 §13.2#2
  标「部分闭环」+ 口径边界段（显式列举双超时/max-attempts/total-budget/心跳四项不在此列）——**符合攻击面 5 的要求**。
  仅「同批标闭环」四字与「部分闭环」存在措辞落差，建议统一为「部分闭环」。观察级。

## 2. 攻击面逐项结论（未列发现即通过）

| # | 攻击面 | 结论 |
|---|---|---|
| 1 | diff 逐行审（7 M + 3 新生产/测试文件） | **通过**。7 个 M 文件改动面逐行核对：`AiProperties`（import + `@PostConstruct` 一行委派 + 4→8 + 两段 javadoc，`DEFAULT_DELIVERY_QUEUE_CAPACITY` 值 256 未动仅注释）、`AiController`（`health()` 一行改调 + 新私有 `sseDeliverySnapshot()`）、`SseChatEmitter` **仅 2 行类注（javadoc），零字节行为变化**【实测 diff】，其余 4 个 M 全为文档追加。无夹带行为变更；越界项仅类注一行，且系已裁决保留项 |
| 2 | AiPoolCapacityBudget 生命周期/回落/算式 | **通过**。`@PostConstruct` 在绑定后执行由 `AiPropertiesT35DefaultsTest#startupCheckIsWiredAndOnlyWarnsWhenTheBudgetIsExceeded` 实证（绑定 `suspend.pool-size=1000` → WARN 文案含按绑定值算的要求值，`context.refresh()` 成功 = 不 fail-fast）——若先于绑定执行则读到缺省 20 必然无 WARN，测试即红，逻辑自洽。反射 `getField`（public 字段，无需 setAccessible）+ `ReflectiveOperationException \| RuntimeException` 回落推导值并 WARN 标注口径——回落路径正确。算式验算：`ceil(48/10)=5`【test 断言】、边界 `40 ≤ 40` 判满足（`≤` 非 `<`）【test 断言】、报告 §3.4B `ceil(2040/10)=204` 复核无误。唯「不可配」表述见 F1 |
| 3 | /health 七键 | **通过（附 F2 观察）**。七键齐：`poolSize/active/queueDepth/evicted`（原四键）+ `queueCapacity/boundedElasticCapacity/heartbeatIntervalMs`。`queueCapacity` 读 `AiProperties.Sse#deliveryQueueCapacity`——单一事实源【生产侧 grep：默认值仅 `AiProperties.java` 一处定义，`delivery-pool-size` 字面量其余命中均为文案/告警串】。心跳键「常量变更时测试先红」成立：`AiControllerHealthSseDeliveryTest#healthExposes...` 锚**字面量 2000/5000**（常量一改即红），`#heartbeatKeysFollow...` 锚常量表达式（链路跟随）——双断言设计正确。性能/线程安全见 F2（无安全问题，每请求反射为观察项） |
| 4 | 测试真实性 9 例 | **通过**。三测试类逐例审读：断言对象全部为真行为（ListAppender 捕获 WARN 恰一条 + 级别 + 配置键名；Spring 最小上下文 refresh 实证生命周期；MockMvc standalone 打真端点断 jsonPath）。无恒真断言。「无需改断言」grep 结论**独立复核成立**：`getDeliveryPoolSize()` 6 处全为动态读默认值；`setDeliveryPoolSize` 仅 `ThreeArgAssemblyConformanceTest:88(3)/:145(4)` 两条**自定义值**用例（:145 只断帧数 200/seq 连续/FIFO，非默认值断言）；`isEqualTo(4)` 唯一命中 `SseChatEmitterFramesTest:169` 为回放帧数；`assertEquals(4` 零命中；测试资源无 yml/properties 覆盖。无漏网硬编码 4 |
| 5 | 冻结文档注记措辞 | **通过（附 F3 措辞落差）**。见 F3：实质未越界，§13.2#2 正确标「部分闭环」，口径边界段显式排除 resilience 四值与心跳 |
| 6 | 复现抽查 | **通过**。见 §0：mvn 复跑 298/0/0/0（46 XML）与报告一致 |
| 7 | 命名纪律终扫 | **通过**。16 条改动展开为 17 个文件（`ai/config/` 目录含 2 测试类，与报告「16 条」为 git 条目口径、无出入），对 `main⟨-⟩v2/main⟨_⟩v2/main⟨.⟩v2/main⟨无分隔⟩v2/"main⟨空格⟩v2"/<SCRATCH>/<MAIN_REPO>` 七形态 + 放宽正则 `main[ _.-]?v2` 与 `ai-example` 任意后缀**全部零命中**【子智能体 grep 实测】 |

## 3. 附带核对项

- `SseChatEmitter#configureSharedDeliveryPool` 消费点（`RunRegistry.java:61-62`）读 `getDeliveryPoolSize()` 动态值 → 8 已生效于装配链【旁证：回灌报告 §3.2 E2E 日志线程名 `sse-delivery-8` 同形佐证】。
- `application.yml` 的 `app.ai` 段未定义 `sse.delivery-pool-size` → 8 完全由代码缺省提供，配置覆盖语义未变。
- `RunStore#EVENT_WINDOW = 3000 > 256`（R1 不变量由 `AiPropertiesT35DefaultsTest#defaultsAreTheT35Values` 断言）。
- 三个新测试类编译进 46 XML 且 `ThreeArgAssemblyConformanceTest` 5/5 在套件中通过（在途件未被本棒破坏）。

## 4. 裁决

**放行。**

理由：回灌动作与已裁决口径**逐项忠实**——diff 零夹带、`SseChatEmitter` 零行为变化、生命周期/回落/算式全部有真实测试
实证、七键单一事实源成立、文档注记实质未越界、命名纪律零命中；复现抽查（mvn 298/0/0/0）与回灌报告自述完全一致，
且复跑过程未扰动工作区。三条发现均为 S3 观察级：F1（reactor 常量实可经系统属性覆盖，「不可配/实测值」表述失真，
漏报方向仅存在于仓库外部署参数介入时）与 F3（「同批标闭环」vs「部分闭环」措辞落差）建议随下一小批一行级修正；
F2 仅为性能毛边。均不构成阻断，不要求本棒返工。

—— 红队 GLM-5.3，2026-10-09
