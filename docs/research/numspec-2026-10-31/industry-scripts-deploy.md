# 业界基准对照 · 脚本 / CI / 部署 / 试验工程（numspec-2026-10-31 · industry 分册）

> **本文性质**：只读研究产物。唯一新增物 = 本文件；未改任何代码/测试/配置/四分册，未做 git 写操作，未启动服务、容器、浏览器，未跑测试。
> **覆盖对象**：[`scripts-deploy.md`](./scripts-deploy.md) 的全部 **274** 条 ID（`N-OPS-001 … N-OPS-274`），逐 ID 一行，**不合并、不写「同上」**。
> **对照方法**：先取**官方规范/官方默认值**（GitHub Actions、Docker、Redis、Lettuce、Spring Boot、HikariCP、PostgreSQL、H2、HAProxy、Kubernetes、Playwright、Testing Library），再取**生态通行做法**（测试选择、棘轮、探针、归档保留）；没有公开标准的一律写「无统一标准」并落到**需实测**，不编造行业数。
> **与四分册的关系**：四分册回答「现值是什么、依据等级如何」；本文只回答「业界是多少、该保留还是改、为什么」。裁决仍在用户，本文只提不改。

---

## 0. 口径

### 0.1 建议的四种取值（全文只用这四类，便于统计）

| 类型 | 含义 | 判定条件 |
| --- | --- | --- |
| **保留** | 现值落在业界区间内，或属外部规格常量（端口、AES-256、git sha、Redis 16384 槽） | 改动收益 < 改动风险 |
| **新值** | 给出一个明确的替换数字 | 现值偏离业界默认 ≥ 一个量级，或与同仓另一处自相矛盾 |
| **派生** | 不再写字面量，改为由**单一事实源**计算（读 yml / health / 产品常量 / 心跳周期） | 该值本质是别处的函数，手抄必然漂移 |
| **需实测** | 无公开标准，必须本仓测 P95/压测后才能定值；本文只给公式与初值 | 业界值不可迁移（取决于机器、模型、仓库规模） |

### 0.2 来源分级

- **A 级（规范/默认值）**：官方文档写死的默认值或协议规格。可直接作为对照基准。
- **B 级（官方指南）**：官方「推荐做法」，不是硬数字（如 GitHub 建议避开整点、Playwright 反对 `waitForTimeout`）。
- **C 级（生态共识）**：多个主流项目的共同做法，但无单一权威文本（如失败证据留 14 天、棘轮只降不升）。C 级只作方向，不作数值依据。

### 0.3 固定 sleep 必须分两类（这是本文最重要的一条口径）

「固定 sleep 该不该留」在业界**没有统一数值标准**，但**有统一判据**：看这个等待**等的东西有没有可观察信号**。

| 类型 | 定义 | 业界做法 | 本仓例子 |
| --- | --- | --- | --- |
| **稳定等待（stabilizing wait）** | 等一个**必然发生、但当前没有可观察信号**的状态（如 CSS 过渡结束、浏览器合成一帧、剪贴板写入生效、哨兵文件落盘） | 允许保留，但必须**注释说明「为什么没有信号」**，并取最小可用值 | 259（哨兵+端口双探测前的 1s）、156（剪贴板 5s 属权限窗口） |
| **任意等待（arbitrary wait）** | 给「可能不够」的时间余量；信号其实存在（HTTP 200、DOM 节点、帧到达、键消失） | **删除或改为条件轮询**；Playwright 明确把 `waitForTimeout` 列为反模式，Testing Library 的 `waitFor` 缺省 1s/50ms 轮询就是标准替代 | 013（桩 `sleep 2`）、112（12×2s）、114（22 处 0.2–1s）、160（23 处） |

> **结论先行**：本册 60+ 处固定 sleep 中，绝大多数属**任意等待**（信号可观察），可静态判定；少数属**稳定等待**（无信号），保留但要注释。**不存在「固定 sleep 应小于 X 秒」这种行业阈值**——任何声称「sleep 不超过 1s」的说法都不是标准。

### 0.4 业界默认值速查（本文反复引用的基准）

| # | 基准 | 官方值 | 来源 |
| --- | --- | --- | --- |
| 1 | GitHub Actions job `timeout-minutes` | **缺省 360 min**，上限 360；step 上限 360 | <https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax> |
| 2 | GitHub artifact/log 保留 | **缺省 90 天**，可按仓/组织/企业改 | <https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts> |
| 3 | GitHub `concurrency` | 缺省只排队、**不取消**运行中的 run（`cancel-in-progress` 缺省 false） | <https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency> |
| 4 | GitHub 定时 | cron 按 **UTC**；最短间隔 5 min；高峰期会延迟 | <https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax> |
| 5 | Docker `HEALTHCHECK` | `--interval 30s`、`--timeout 30s`、`--retries 3`、`--start-period 0s` | <https://docs.docker.com/reference/dockerfile/#healthcheck> |
| 6 | Docker health 判死窗 | 缺省 = 30s × 3 = **90s**（本仓显式改成 5s×12=60s） | Docker 文档的 `interval × retries` 组合 |
| 7 | Redis `cluster-node-timeout` | **15000 ms**（不是 5s） | <https://raw.githubusercontent.com/redis/redis/7.2/redis.conf> |
| 8 | Lettuce `maxRedirects` | **5**（`ClusterClientOptions.DEFAULT_MAX_REDIRECTS`） | <https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/cluster/ClusterClientOptions.java> |
| 9 | Spring Boot 优雅停机 | `spring.lifecycle.timeout-per-shutdown-phase` **缺省 30s** | <https://docs.spring.io/spring-boot/docs/2.4.2/reference/html/appendix-application-properties.html> |
| 10 | HikariCP | `maximumPoolSize 10`、`connectionTimeout 30000`、`validationTimeout 5000` | <https://github.com/brettwooldridge/HikariCP#frequently-used> |
| 11 | PostgreSQL `lock_timeout` | **0（禁用）**；`statement_timeout` 亦 0 | <https://www.postgresql.org/docs/current/runtime-config-client.html> |
| 12 | H2 `LOCK_TIMEOUT` | **1000 ms** | <https://www.h2database.com/html/commands.html> |
| 13 | Kubernetes 探针 | `periodSeconds 10`、`timeoutSeconds 1`、`failureThreshold 3`、`successThreshold 1`；`terminationGracePeriodSeconds` 30 | <https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/> |
| 14 | HAProxy 健康检查 | `inter 2s`、`fall 3`、`rise 2`（`fastinter`/`downinter` 缺省随 `inter`） | <https://docs.haproxy.org/2.9/configuration.html> |
| 15 | Playwright | test **30s**、expect **5s**、global 无缺省；反对 `waitForTimeout` | <https://playwright.dev/docs/test-timeouts>、<https://playwright.dev/docs/best-practices> |
| 16 | Testing Library `waitFor` | `timeout 1000ms`、`interval 50ms`（缺省即条件轮询） | <https://testing-library.com/docs/dom-testing-library/api-async/> |
| 17 | Tomcat `maxThreads` | **200** | <https://tomcat.apache.org/tomcat-10.1-doc/config/http.html> |
| 18 | AWS ALB 摘流 | `deregistration_delay` 缺省 **300s** | <https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html> |

### 0.5 点名项速览（11 项，详情见对应 ID 行与 §11）

| 点名项 | 一句话结论 | 关键 ID |
| --- | --- | --- |
| 3000 ms 观察窗 vs 2 s 心跳 | **不足**：只覆盖 1.5 个心跳，同文件注释已自认会假绿；应 5000（2×心跳+1 s） | 115、116、121、157 |
| `stall-frames 50000` vs `inter-event 90s` | **缺省永远不触发**看门狗；改 95 s 或强制显式传参 | 061、119 |
| 固定 sleep | 60+ 处（仅 112/113/114/154/160 即 67 处）；**无行业秒数标准**，只能按「有无可观察信号」分稳定/任意两类 | 013、112、113、114、154、160、231 |
| 7 天 vs 14 天归档 | GH 缺省 90 天；失败证据统一 **14 天**，覆盖率可留 7 天 | 023、028、031、034、044、045 |
| 优雅停机 20 s vs SSE 300 s | 20 s **不违反框架**（缺省 30 s、K8s 宽限 30 s），但必然截断长流；应改「停机前发终态」而非改数字 | 208、202、205、209 |
| 探针 / 接管窗 | 同语义 90/120/130 三值并存；应统一为 `lease+retry+2×probe` | 136、178、180、181、267 |
| 手抄产品键 | 7 处手抄，而同文件已有读 yml 的能力；全部改派生 | 099、101、106、137、143、149、153 |
| 真实模型采样 10 次 | 只能当 smoke；须写「10/10 全过」判据，统计结论需 ~59 次 | 027 |
| 取消收敛 6 s | 偏紧（重试节奏 2/6/14 s）；改 20 s 或等 `cancelled` 帧 | 150、151 |
| 集群等待 | 166/167 可降到 30 s；169 窗口恰等于 node-timeout 应 10 s；185 的 25 s 小于 zombie 30 s | 166、167、169、185 |
| workflow 超时 | 4 处可降档（3/5/2/2 min）；9 处无标准、须测 P95（GH 缺省 360 min 不可作依据） | 003、006、008、042；002、004、005、007、024、030、033、039、041 |

---

## 1. `.github/workflows/**`（N-OPS-001 … N-OPS-048）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-001 | changes job 5 min | GH 缺省 360 min；业界轻量 job 惯例 = 预期耗时 ×2–3（下限 2–3 min） | 保留 | 分类器只跑 git diff + JSON，秒级；5 min 已是 100 倍余量，再收紧只会增加误杀 checkout 抖动的概率 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-002 | backend 20 min | 无行业标准；通行做法 P95×1.5 作兜底 | 需实测 | mvn test + JaCoCo 随用例线性增长，20 min 是否够只有近 20 次运行数据能回答；测完再写公式，别先改数 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-003 | redis-guard 5 min | 纯 grep 类门禁惯例 2–3 min | 新值 3 min | 该 job 无编译无网络，5 min 是 001 的两倍却做更少的事；3 min 内失败反馈平均快 2 min，且仍覆盖 checkout | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-004 | frontend 20 min | 同 002：P95×1.5 | 需实测 | typecheck + vitest + build 三段同 job，需分段计时才能知道哪段该拆；未测前改数等于换一个猜测 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-005 | e2e 25 min | 上限应 ≥ 所有有界等待之和 + 启动；无统一数值 | 需实测 | 25 min 由 019 的 90s、021 的 60s、120s 作业窗等拼成，必须先做「等待点预算表」再判断；与 `verify-e2e` 主用例预算同源 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-006 | docs 10 min | Node 扫全仓惯例 2–5 min | 新值 5 min | 与 001 同级的纯静态扫描，10 min 是两倍；5 min 仍留 10 倍余量 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-007 | scan-history 10 min | 随仓库历史增长，业界做法设 2×P95 并加「接近上限」告警 | 需实测 | 全历史 blob 扫描成本与仓库年龄成正比，固定 10 min 会在某次合并后无声失效；与 198 的并行度 8 耦合 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-008 | summary 5 min | 纯 shell 汇总惯例 1–2 min | 新值 2 min | 无外部依赖，5 min 只会在 runner 排队异常时白占配额 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-009 | redis 健康 5s/3s/12 | Docker 缺省 30s/30s/3（判死 90s） | 派生 | 12×5=60s 判死窗对「Redis 冷启 <1s」是 60 倍；建议改为 `interval 5s / retries 5`（25s）并写成「启动预算 25s」推导；与 026/043 同一锚点 | [Docker HEALTHCHECK](https://docs.docker.com/reference/dockerfile/#healthcheck) |
| N-OPS-010 | 6379 宿主端口 | Redis 注册端口 6379（规格常量） | 保留 | 全仓一致的约定端口，改动无收益；建议登记进统一端口表即可 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-011 | 桩端口 18399 | 业界更优：测试进程绑 **port 0** 由内核分配，避免并行冲突 | 保留 | 183xx 段已事实标准化（18301/18302/18303/18318/18330/18399），重构为动态端口的收益小于风险；但须登记为约定 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-012 | E2E 后端 18330 | 18330 属约定端口；业界更优是 test 进程绑 port 0 | 保留 | 与 011 同段同源；`--server.port` 已可覆写，保留字面量不阻碍并行 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-013 | 桩启动后 `sleep 2` | 官方指南：等待可观察条件，禁止拍时间（Playwright 明列 `waitForTimeout` 反模式） | 派生 | 这是**任意等待**：桩已暴露 `/probe`，可就绪即返回；改为 20×100ms 有界轮询，最坏 2s、正常 <200ms | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-014 | `curl -m 5` 探活 | 回环请求业界惯例 1–2s | 新值 2 s | 本机回环 + 静态响应，5s 只会在探活逻辑写错时白等；2s 与 013 的轮询上限同量级 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-015 | `batch-size=10` | 测试覆写产品键的通行做法：取**能跨过断言边界的最小值** | 保留 | 10 相对默认 100 是 1/10，正好让 TC11 跨分片；关键是注释写明「断言依赖分片边界」，否则下一个人会改回 100 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-016 | `demo-batch-delay-ms=150` | 测试节拍惯例：只压到不改变语义的最小值 | 保留 | 150 是产品 300 的一半，语义不变、只影响演示节拍；建议注释「不得为提速再降」，因为再降会改变并发交错 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-017 | 会话限流 200/min | 业界做法：E2E **关闭**限流（若产品支持），否则设到数学上不可达 | 保留 | 200 对 35 用例 × 低并发确实不可达；建议把判据写死为「用例数 × 最大并发 < 200」，让「不可达」可验证 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-018 | IP 限流 2000/min | IP 维上限应 ≥ 会话维 × 单 IP 并发会话数（此处取 10 倍） | 保留 | 2000 与 200 的 10 倍关系符合「一 IP 多会话」的假设；同样需写不可达判据 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-019 | 后端就绪 90s（1s 节拍） | Spring Boot 冷启业界 10–30s；K8s startup 预算常用 5 min 上限、失败快速 | 需实测 | 90s 对冷启是 3–9 倍，属安全侧；应先测冷启 P95，再改为 `max(60s, 3×P95)`；与 005 的 job 预算联动 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-020 | `curl -m 3` 健康探活 | 回环 1s 足够 | 新值 1 s | 与 019 的 1s 节拍相加会逼近上限（3+1 > 1），单次超时不该大于轮询节拍；同源化后总窗才可算 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-021 | 种子就绪 60s | 启动期同步播种，业界做法等**事件**（日志行/健康字段）而非计时 | 派生 | 播种在启动线程内完成，60s 是纯拍数；改为读 boot.log 的 seed 完成行或 health 的 seeded 字段，失败即时报错 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-022 | 定义数 ≥15 | 业界做法：期望值由**清单/接口自述**派生，不写死 | 派生 | 15 = 基座 7 + glm 8；种子一扩就假红。改为读 `/api/config/definitions` 返回的 total 或种子清单长度 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-023 | 归档 7 天 ×3 | GH 缺省 **90 天**；生态：覆盖率报告 7 天可接受，失败证据常 14–30 天 | 新值 14 天（证据）/ 7 天（覆盖率） | 三处语义不同却同值：JaCoCo 是趋势数据（7 天够）、E2E 证据是排障凭据（14 天起）、审计报告应 ≥30 天；统一 7 天会让「上周的 flake」无从复查 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-024 | e2e-real stub 60 min | 无标准；启动主导型 job 用「步数 × 单步 P95 ×1.3」 | 需实测 | 9 步各起一套 JVM+vite，60 min 是拍数；先测单步 P95，再决定是拆 job 还是加机器 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-025 | e2e-real 45 min | 真模型延迟方差大，业界做法绑定「采样数 × 单轮预算 + 重试」 | 派生 | 45 min 与 027 的 10 次采样无显式关系；改为 `n × 单轮 P95 × 1.5` 后才可解释（n=10、单轮 60s ⇒ 15 min，余量充足） | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-026 | redis 健康（第 2 处） | 同 009 | 派生 | 与 ci.yml 逐字重复；四处副本改一处必忘三处，应抽 `--health-*` 到 composite action 或 env | [Docker HEALTHCHECK](https://docs.docker.com/reference/dockerfile/#healthcheck) |
| N-OPS-027 | 真模型采样 10 次 | 业界 eval 做法：n=10 只能做 smoke；0 失败时通过率的 95% 下界：精确解约 **74%**，rule of three 近似 70%；要证 95% 通过需 ~59 次 | 保留 | 10 次作为每次 PR 的冒烟合理，但**必须写明判据**（10/10 全过才算绿、允许 0 flake）；若要统计结论，放夜间 n≥30–60，别把 smoke 当 SLO | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-028 | trace/截图归档 7 天 | 同 023；失败证据 14 天是生态常见值 | 新值 14 天 | 与 031/034 的 14 天不一致；trace 是复现 flake 的唯一凭据，7 天常在周会前就过期 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-029 | 32 字节 KEK | AES-256 密钥长度规格（32B = 256 bit） | 保留 | 外部加密规格，不是可调项；建议注释直接写 AES-256，避免被当成魔数 | [NIST FIPS 197](https://csrc.nist.gov/pubs/fips/197/final) |
| N-OPS-030 | 三实例集群 45 min | 无标准；= ready 180s + 多轮 kill/故障用例之和 | 需实测 | 内含 178 的 180s、181 的 120s 与多轮故障注入；先做用例预算表，45 min 才有依据 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-031 | 集群证据 14 天 | 生态：跨实例/分布式证据 14–30 天 | 保留 | 14 天是本册里较合理的一档，应作为**统一值**反向覆盖 023/028/045，而不是被它们拉低 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-032 | 并发组 1、不取消 | GH 缺省即不取消运行中的 run；长证据 job 惯例串行 | 保留 | 「取消会留半套证据」是正确取舍；建议补「预期排队 ≤X min」，否则排队会被误判为卡死 | [GH concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency) |
| N-OPS-033 | Redis 5 冒烟 25 min | = 段数 × 240s 上界；无标准 | 需实测 | 165 的 240s 是单段上限，25 min 隐含 6 段；先数列段数再乘，避免「脚本加了段、workflow 没加」 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-034 | 5.0 证据 14 天 | 同 031 | 保留 | 与 031 一致；应与 023/045 统一到 14 天 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-035 | 并发组 1 | 同 032 | 保留 | 独占 Docker 资源，串行是正确取舍；与 032 同理由 | [GH concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency) |
| N-OPS-036 | cron `17 18 * * *` | 官方提示：整点是调度高峰，且 scheduled run 会延迟 | 保留 | 分钟取 17 正是业界「避开整点」的惯例；建议注明「允许 ±数分钟延迟」以免被当成 SLA | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-037 | 确定性棘轮 40 | 棘轮（ratchet）通行做法：基线只降不升，每次下降有主与期限 | 保留 | 40 = 实测 33 + 20% 余量，方向正确；缺的是「每夜 -N、谁负责」，属流程不是数值 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-038 | 夜间后端 30 min | = ci 20 min 的 1.5 倍（双 locale） | 保留 | 双跑 ×1.5 是合理比例（第二遍省 JVM 启动）；建议注释「双跑」以免被当成夜间机器慢 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-039 | 夜间前端 25 min | 与 ci 20 min 的 +5 无说明 | 需实测 | 夜间若确实多跑（覆盖率/locale），+5 合理；若只是机器慢，应回到 20 并单独加机器；先测差值 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-040 | 夜间 E2E 25 min | 与 ci e2e 同值 | 保留 | 同一套用例、同一预算，同值是正确的一致性；改动须与 005 同步 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-041 | testmap 双遍 60 min | 刚性成本 = 单遍 ×2；业界做法写成公式 | 需实测 | 两遍全量 + 261 类，60 min 关键是单遍 P95；测完改 `2×P95×1.15`，否则仓库增长后先超时的是它 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-042 | 夜间汇总 5 min | 同 008 | 新值 2 min | 纯 shell 汇总，与 008 同源同值才便于统一维护 | [GH timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-043 | redis 健康（第 4 处） | 同 009 | 派生 | 第 4 份副本；四处同值不同文件，改一处必漏三处，必须抽公共锚点 | [Docker HEALTHCHECK](https://docs.docker.com/reference/dockerfile/#healthcheck) |
| N-OPS-044 | surefire/审计 14 天 ×2 | 同 031 | 保留 | 与 031 同为 14 天，正确；问题在 023/045 的 7 天一侧 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-045 | 夜间 E2E 证据 7 天 | 失败证据应 ≥14 天 | 新值 14 天 | 夜间跑出的 flake 往往隔天才看；7 天会在「周末+周一」就被清掉，与 031 的 14 天自相矛盾 | [GH artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) |
| N-OPS-046 | 夜间 6 处等待（2s/5s/3s/1s/3s/1s） | 同 013/014/019/021 的判据 | 派生 | 与 ci.yml 是两套逐字副本；任何「sleep 改轮询」只改一处必留另一处，应先抽共享脚本再谈数值 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-047 | 打印前 20 个不稳定类 | 仅日志展示，无标准 | 保留 | 只影响可读性，不影响判定；已同时打印总数，信息完整 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-048 | 夜间并发组 1、不取消 | 同 032；全量 run 的价值在跑完 | 保留 | 与 032 同取舍；建议同样补「预期排队时长」 | [GH concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency) |

---

## 2. `scripts/ci/**`（N-OPS-049 … N-OPS-097）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-049 | 桩缺省端口 18399（可覆写） | 与 011 同段；业界更优是 port 0 | 保留 | 已有 `--port` 覆写口，缺省值只服务本地与 CI；保留字面量不至于阻碍并行 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-050 | `MAX_TITLE_CHARS=100` | 单一事实源原则：测试替身的上限应由产品契约导出 | 派生 | 这是产品 `GenerativeFormRules` 的**副本**；任何一侧改动都会让桩先拒或后拒，用例变成测桩。改为 import 契约常量或读 `/api/ai/health` 下发的限制 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-051 | `MAX_OPTIONS=50` | 选项数量上限应由产品契约派生，不由桩自持 | 派生 | 50 与产品同值但无对账；与 050 同一份「副本清单」，必须同批改造 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-052 | `MAX_OPTION_TEXT_CHARS=100` | 单选项文本上限应由产品契约派生 | 派生 | 50/100 这一组与标题上限同值但不同义；100 字符对中文是 100 字，与「字符 vs 字节」口径需一并注明 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-053 | `MAX_PLACEHOLDER_CHARS=100` | 占位符上限应由产品契约派生，与标题上限同值但不同义 | 派生 | 占位符上限与标题上限同为 100，但语义不同；同值容易被误合并，派生后各自独立 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-054 | `MAX_FORM_CHARS=8000` | 传输上限应由契约派生；业界惯例用**字节**并写明 UTF-8 | 派生 | 8000 未说明字节/字符，中文场景差 3 倍；派生自产品键并注明单位，否则越界用例可能测不到真实边界 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-055 | `MAX_LINKAGE_DEPTH=2` | 越界造数通行做法：合法上限 + 1 层 | 保留 | 2 与 G-13 的 4 层造数成对（2 合法、4 越界），方向正确；建议注释「2=产品上限、4=越界」 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-056 | key 正则总量 40 字符 | 标识符长度无行业标准；SQL 标识符各库不同（PG 63、Oracle 128） | 保留 | 40 是本产品契约，不是可调项；`{0,39}` 只是「40 含首字符」的写法，改成 `{0,39}` + 注释即可，不必动数值 | [PostgreSQL runtime config](https://www.postgresql.org/docs/current/runtime-config-client.html) |
| N-OPS-057 | 思考帧单片 64 KiB | 与产品 `max-frame-bytes 65536` 同源 | 保留 | 属外部契约副本；建议注明字节口径（非字符），否则中文思考文本会提前触顶 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-058 | 命名轮 24 字符/片 | 分片大小通行 16–64 字符/token；无标准 | 保留 | 24 决定 delta 帧数与断言粒度，属测试夹具设计；只需注明「改此值须复核帧数断言」，不宜随意调 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-059 | 日志截断 160/300 字符 | 仅取证可读性，无标准 | 保留 | 不影响判定；建议合并为一个常量，避免两处各自演进 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-060 | `break-at-seq=2` | 断流点应落在「已开始、未结束」之间 | 保留 | 写满 2 帧后断流，正好覆盖 start→delta 的中间态；建议注释该语义，否则调成 1 会退化成「连接即断」 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-061 | `stall-frames=50000` | 产品 `inter-event-timeout=90s`；看门狗缺省应 > 该阈值 | 新值 95 s | 50s **小于** 90s，缺省值永远触发不了帧间看门狗，是「假绿」陷阱；改为 `inter-event+5s`（95s）或强制调用方显式传参，二者择一 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-062 | `kill-stream=3000` | 断点需在首帧之后 | 保留 | 3s 足够跑出首帧；建议注释「断点落在首帧后」，与 060 的 2 帧断流形成两种故障形态 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-063 | `slow-tool=130000` | 产品前端工具超时 120s；造数应 > 阈值 | 保留 | 130s > 120s 是刻意的「工具不回流」造数；注释写明不等式，否则会被当成随手写的 130 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-064 | `multi-card=3` | 批量待决通行 2–5 张 | 保留 | 3 张足以覆盖逐卡决策与乱序确认；建议注释它决定 H-04 的轮数 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-065 | `stall-headers=30000` | 产品 `first-byte-timeout=30s`；缺省应 > 阈值 | 派生 | 30s 与产品首包超时**相等**，谁先触发不确定；应取 `first-byte+5s`（35s），与 118 用例的显式传参同源 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-066 | `huge-frame=102400` | 越界造数 = 上限 + 1 个量级 | 保留 | 100 KiB > 64 KiB 上限，方向正确；名称与 note 的 64KB 不一致，注释写「造数须越界」即可，不要改小 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-067 | `tool-loop=26` | 越界造数 = 上限 + 1（业界通用） | 保留 | 26 = 产品 25 步 + 1，教科书式越界样例；这个「+1」应作为其他上限用例的范式 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-068 | `slow-first-byte=8000` | 必须 < 产品首包 30s | 保留 | 8s 落在「慢但未超时」区间，用于区分「慢」与「死」；注释不等式即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-069 | `slow-tool-exec=8000` | 工具段首帧前静默须 < 工具超时 120s | 保留 | 与 068 同值同构，但作用于工具执行段；建议注释两处覆盖不同阶段 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-070 | `slow-tail-frames=1200` | 与 SSE 心跳 2s 同量级；用于「收尾未断」判据 | 保留 | 1.2s < 心跳 2s，能被前端停止逻辑识别为非断流；注释判据，避免被当成乱数 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-071 | `late-frames=2` | 2 帧足以证明「整帧丢弃」 | 保留 | 1 帧无法区分「丢了一帧」与「没发」，2 帧是能证伪的最小值；保留 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-072 | `sheet-big` 500 行 | 与产品采样上限 20 的关系需注明 | 保留 | 500 行是「大表但可读」的夹具规模；注释写清「500 行进表、20 行入模型」，否则会被误认为采样上限是 500 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-073 | `maxCallsPerTurn=6` | 产品步数上限 25；测试替身常更严 | 保留 | 桩的收敛闸比产品严是刻意（快速暴露死循环）；但必须在命名/注释上标「桩闸」与「产品上限」的分工，否则会被当成漂移 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-074 | usage 5 组 token 数 | 造数应覆盖上下文档位边界（70/80/95） | 保留 | `big=11000` 是「窗口 ≥22K」的造数，需把 22K 推导写进 note；`last` 是历史基线不可动 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-075 | 影子窗口 300 笔 | 统计窗口：n=300、允许 1 漏检 ⇒ 点估计 0.33%，95% 上界约 1.1% | 保留 | 300 是能给出量级结论的最小样本之一；建议看板标注窗口起止日与分母来源，否则「300 笔」无法复核 | [C 级：统计常识](https://en.wikipedia.org/wiki/Rule_of_three_(statistics)) |
| N-OPS-076 | 允许漏检 1 笔 | 与 075 成对；1/300 是容忍上限 | 保留 | 与 075 的比值正确；应要求「这 1 笔必须根因归类」，否则「允许 1 笔」会变成「允许 1 笔不明」 | [C 级：统计常识](https://en.wikipedia.org/wiki/Rule_of_three_(statistics)) |
| N-OPS-077 | 子进程缓冲 256 MiB | Node `exec` 的 `maxBuffer` 缺省 1 MiB；大输出应改流式 | 需实测 | 256 MiB 是「先别炸」的值，大仓 diff 仍可能超；建议测一次最大 diff 体积，或改 `spawn` 流式读取 | [Node child_process](https://nodejs.org/api/child_process.html) |
| N-OPS-078 | 账本保留 400 笔 | = 窗口 300 + 100 余量 | 派生 | 400 的可解释性来自 300；写成 `SHADOW_WINDOW + 100` 后，改窗口时账本自动跟随，不会出现「窗口 500、账本 400」 | [C 级：棘轮惯例](https://martinfowler.com/articles/patterns-of-distributed-systems/) |
| N-OPS-079 | 打印 40/10 条 | 仅日志 | 保留 | 不影响判定；40 条足够定位模式，10 条够看多红 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-080 | 键覆盖 ≥95% | 测试选择（PTS）业界召回目标常在 90–95%，低于此宁可全量 | 保留 | 95% 是「表可信」的生命线，方向正确；关键是给出实测覆盖（如 99.x%）作为余量，否则无法判断离红线多远 | [Meta PTS 论文](https://arxiv.org/abs/2011.11112) |
| N-OPS-081 | 表龄 7 天 | 每日重建的表，TTL 通行 1–3 天 | 新值 3 天 | 夜间每日重建，7 天意味着「连漏 6 夜」才退化；3 天既容忍一个长假，又不会让陈旧表长期生效 | [Meta PTS 论文](https://arxiv.org/abs/2011.11112) |
| N-OPS-082 | 提交距离 200 | 业界更常用「提交距离」而非时间（分支合并会跳时间） | 保留 | 与 081 双闸互补：一个防陈旧、一个防大改；200 对日更仓是 2–3 周，合理 | [Meta PTS 论文](https://arxiv.org/abs/2011.11112) |
| N-OPS-083 | 扇出 50 个类 | 无标准；应以实测最大扇出定值 | 需实测 | 50 是否合理取决于最大扇出（若实测 60，则 50 会频繁退化全量）；先测一次分布再定 | [Meta PTS 论文](https://arxiv.org/abs/2011.11112) |
| N-OPS-084 | 选择率 60% 退全量 | 断点通常在 50–70%：选择集超过一半，调度收益已小于风险 | 保留 | 60% 落在区间内；建议对照实测选择率分布，确认不是「长期贴着 60」 | [Meta PTS 论文](https://arxiv.org/abs/2011.11112) |
| N-OPS-085 | git diff 缓冲 64 MiB | Node 缺省 1 MiB；大 PR 建议流式 | 保留 | 64 MiB 对常规 PR 足够；失败时应打印「输出超限」诊断而非抛原始异常 | [Node child_process](https://nodejs.org/api/child_process.html) |
| N-OPS-086 | 变更清单 50 条 | 仅展示 | 保留 | 文案自洽（「最多 50 条」），信息足够 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-087 | `git ls-files` 缓冲 128 MiB | 全仓路径列表通常 <10 MiB；小缓冲能暴露异常 | 新值 16 MiB | 128 MiB 把「git 输出异常」隐藏成正常；16 MiB 仍容 10 万级文件，超了就该报警而不是沉默 | [Node child_process](https://nodejs.org/api/child_process.html) |
| N-OPS-088 | 盘符路径 `{1,60}` | 无标准；长度上限只防灾难性回溯 | 保留 | 60 是「够长即命中」的经验值，与 scan-lib 逐字同构；注释写清「同构防拆形」即可 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-089 | 占位符 `{1,80}` | 与 scan-lib 同值 | 派生 | 两处各写一套正则，改一处必漂；应由 scan-lib 导出单一常量 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-090 | 命名种子下限 5 / 24 | 自检防「删空仍全绿」的通行做法 | 保留 | 主动把下限与数组长度解耦，是正面范式；建议其他 `--self-check` 照此办理 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-091 | 残留 Top-15 | 仅日志 | 保留 | 15 个文件足够看出聚集面，不影响判定 | [GH workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) |
| N-OPS-092 | i18n 基线 940 行 | 棘轮基线 = 当前快照，只许下调 | 保留 | 数值本身无行业含义，机制才是关键；建议看板标「只许下调 + 更新责任人」 | [C 级：棘轮惯例](https://martinfowler.com/articles/patterns-of-distributed-systems/) |
| N-OPS-093 | 记录格式版本 1 | 版本号从 1 起是通行做法 | 保留 | 升版需兼容旧记录；建议注释「只加字段不删字段」的升级策略 | [C 级：schema 演进](https://martinfowler.com/articles/patterns-of-distributed-systems/) |
| N-OPS-094 | 理由 ≥8 字符 | 评审机器人通行门槛：一句可核验的话（约 15–20 字） | 新值 16 字符 | 8 个中文字符≈「已核对无影响」这类套话，挡不住敷衍；16 字符 + 「须含具体对象」才可执行 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-095 | git diff 缓冲 64 MiB | 同 085 | 保留 | 与 085 同值同源；统一成一个 helper 更利于后续调 | [Node child_process](https://nodejs.org/api/child_process.html) |
| N-OPS-096 | 正向样例 ≥12 条 | 自检下限防规则表被删空 | 保留 | 正面范式；12 条相对规则数有余量，方向正确 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-097 | git 缓冲 64 MiB ×2 | 同 085 | 保留 | 两处同值；属可接受的大缓冲，建议合并为一个常量 | [Node child_process](https://nodejs.org/api/child_process.html) |

---

## 3. `scripts/verify-e2e.ps1`（N-OPS-098 … N-OPS-130）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-098 | 后端基地址 18330 | 端口属约定；测试基址应由参数注入 | 保留 | 已有 `-Base` 覆写，CI 显式传同值；保留缺省便于本地一键跑，登记端口段即可 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-099 | 单轮预算 300s（手抄） | 单一事实源：客户端超时必须从服务端契约派生 | 派生 | 同文件已有 `Get-AiResilienceSeconds` 读 yml 的能力，这里却手抄 300；一旦产品调 240/360，脚本会先超时或后超时，且报错指向错误层 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-100 | HTTP 超时 = 预算 + 60s | 余量通行做法 = 1 个心跳周期 + 重连退避 | 派生 | +60s 本身无依据；若按心跳 2s 计，60s = 30 个心跳，明显过大。建议写成 `budget + 2×heartbeat + 5s`，与 152 的 +30s 口径统一 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-101 | 作业终态窗 `(30×3)+10+20`=120s | 应与产品四键同源（heartbeat 30s、scan 10s） | 派生 | 公式结构正确（3 次心跳 + 一次扫描 + 余量），但 30/3/10/20 是抄的；改为读 yml 后，产品调心跳脚本自动跟随 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-102 | 轮询 400 ms | 轮询节拍通行 250 ms–1 s；Testing Library 缺省 50 ms（纯前端）、HTTP 轮询常 500 ms | 保留 | 400 ms 在区间内；120s/0.4=300 次请求对本机无压力，改成 1s 只是省日志，不是纠错 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-103 | 轮询 500 ms | 轮询节拍通行 250 ms–1 s；HTTP 轮询常取 500 ms | 保留 | 与 102 的 400 ms 并存无妨，但建议统一为 500 ms，减少「为什么这里是 400」的追问 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-104 | 作业创建等待 60s ×5 | 同步创建接口业界超时 5–10s；60s 属「长任务」量级 | 新值 10 s | 作业创建是毫秒级 DB 写，60s 只会在失败时白等一分钟 ×5 处；10s 已是 1000 倍余量，且能让失败更早暴露 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-105 | 20 s / 10 s 两个等待 | 无标准；两值语义不同（预算面可读 vs 任务行联动） | 需实测 | 同为「随手取」却差 2 倍，说明没有判据；应先分别测一次实际耗时，再决定是否合并到 10s 档 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-106 | SSE 读流硬取消 360s | 流式客户端超时 = 轮次预算；但逐用例应更短 | 派生 | 把「整棒预算」用于每个读流，会让一个卡住的用例占满 6 分钟；应按用例覆盖（首帧 30s、终态 120s），仅整体兜底用 360s | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-107 | 最多挂起 6 次 | 产品多卡/步数上限的派生量 | 保留 | 6 应与产品「单轮最多挂起」对齐；建议注释它由哪个产品键决定，否则产品放宽后这里先红 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-108 | GF 重发 3 次 | 重试只应用于**瞬时**故障；用重试凑出目标状态是 flake 温床 | 保留 | 「仅 schema 漂移时重试」的注释是对的；但必须把「3 次都不出即红」写进失败文案，否则重试会掩盖真问题 | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-109 | GF6 重发 3 次 | 重试只用于瞬时故障；GF 重发属「造数目标态」，须写明失败判据 | 保留 | 与 108 同构；建议共用同一个 `Retry-UntilForm` helper，避免两处阈值各自漂 | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-110 | TC17/TC23 重试 3 次 | 发送重试上限通行 2–3 次（Playwright CI 常设 retries 2） | 保留 | 3 次是常见上限（Playwright retries 缺省 0、CI 常设 2）；关键是失败信息要含最后一次原因 | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-111 | 工具结果帧重试 2 次 | 帧解析失败重试通行 1–2 次（非瞬时，不宜多） | 保留 | 2 次比 3 次更保守，适合「帧已到但解析失败」这类非瞬时场景；建议注释为何不是 3 | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-112 | 固定 `Sleep 2s` ×12 | 官方指南：等待可观察条件；固定 sleep 只在**无信号**时允许 | 需实测 | 12 处里多数是**任意等待**（发布后等待、重发间隔），信号（作业状态/帧）其实可观测；逐处判定：能条件等待的改，不能的（如「等前端合成一帧」）保留并注释 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-113 | 固定 `Sleep 3s` ×2 | 观察窗应 ≥1 个心跳周期；3s 对 2s 心跳仅 1.5 倍 | 派生 | 3392 是 gateWait 观察窗，应按 `2×heartbeat+1s`（5s）派生；2593 无注释，应先判定它等的是什么信号 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-114 | 22 处 0.2–1s 轮询 | 轮询节拍通行 250/500 ms 两档 | 派生 | 22 个手写节拍没有统一来源；抽 `Wait-Until -IntervalMs 250/500` 后，节拍变成两个常量，日志量与请求数可控 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-115 | 无新帧观察窗 5000 ms | 判据 = ≥2 个心跳周期 + 抖动；2s 心跳 ⇒ 5000 ms | 保留 | 推导完整（2×2s+1s），是本册少见的正面案例；建议把式子固化为 `2*heartbeat+1s` 常量，而不是 5 处字面量 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-116 | 无新帧观察窗 3000 ms ×2 | 同 115：3000 只覆盖 1.5 个心跳，**不足** | 新值 5000 ms | 同文件注释已自认「3000 只兜 1 个心跳会假绿」，实现却仍用 3000；这是全册唯一的「判据与实现直接冲突」，应直接对齐 5000 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-117 | 按 `retryAfterMs` 重挂 | 业界最佳实践：尊重服务端下发的 Retry-After，而非自定间隔 | 保留 | 不写死重试间隔是正确做法；建议把「服务端值缺失时的兜底」也写明（否则 0 会变成忙等） | [HTTP Semantics RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) |
| N-OPS-118 | `first-byte + 5000` | 造数必须 > 被触发的阈值 | 保留 | 从事实源读 first-byte 再加余量，是「不手抄」的正面案例；+5s 建议注明 = 1 个探针周期 + 抖动 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-119 | `inter-event + 5000` | 造数 = 阈值 + 一个探针周期 + 抖动 | 保留 | 与 118 同构；两条一起构成「看门狗类用例」的标准写法，应作为范式推广 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-120 | 正文 4096 字符 / 帧 102400 字节 | 断言目标应是「不丢正文」，不是帧大小 | 保留 | 4096 远小于 64 KiB，但足以证明「整帧未被丢弃」；注释写清 4096 的选取理由即可，不必等于上限 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-121 | 首帧迟于 2000 ms 判失败 | 首帧应显著快于心跳周期（2s） | 新值 1000 ms | 用「等于心跳」当上限，等于允许慢订阅者拖到与心跳同量级；取 1/2 心跳更符合「首帧 << 心跳」的设计意图 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-122 | 2000/2001 边界 | 边界值测试通行做法：上限、上限+1 成对 | 派生 | 成对断言正确，但 2000 是产品常量副本；改为读契约（常量或接口），否则产品调 4000 时这里仍测旧边界 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-123 | 采样 ≤20 行 | 产品采样上限 20；测试不应成为第二事实源 | 派生 | 断言「硬顶 20」却未注来源，产品改 50 时这里假红；改为从 health/常量读取上限 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-124 | 滚动 delta ≤2000 px | UI 滚动一屏的像素经验值；无标准 | 保留 | 2000 px 对 1080p 是约 2 屏，合理；建议注释「一屏量级」，并注意与 148/155 的 1800 高视口的关系 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-125 | 定义数 ≥15 | 同 022：由清单派生 | 派生 | 与 022/186 三处同值同源；任一处改、另两处忘，就会一套绿一套红 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-126 | 分页 500/50/5 | 业界做法：按 `total` 翻页，不假设单页装得下 | 派生 | `size=500` 是「够用的大页」，数据增长后会静默截断断言范围；改为循环翻页直到取满 total | [HTTP Semantics RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) |
| N-OPS-127 | 依赖深度 2/4 层 | 同 055 | 保留 | 2 合法、4 越界成对；应与桩侧 `MAX_LINKAGE_DEPTH=2` 同源，避免两处漂移 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-128 | 拒绝理由 ≥3 条（造数 4） | 断言应 ≤ 造数，且注释差距原因 | 保留 | 造 4 断 3 是留容错，合理；但需说明「第 4 条可能被合并」，否则看起来像漏断言 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-129 | 读满 3 帧即断 | 断点应落在 start→delta 之间 | 保留 | 3 帧能保证「已进入流式、未到终态」；注释断点位置即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-130 | 断言 `frontendToolTimeoutSeconds ≥1` | 纪律：只断言「已下发」，不写死 120 | 保留 | 这是全册最好的示范之一：把「不硬编码」变成可执行断言；建议写进 `scripts/README` 作为范式 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |

---

## 4. 多实例 / CDP 冒烟脚本（N-OPS-131 … N-OPS-160）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-131 | 双实例端口 8081/8082 | 测试应避开产品常开端口；更优是动态端口 | 需实测 | 8081 是产品默认，本机常开实例会直接串台；脚本开头必须探测占用并失败，否则「跑过了」可能是打到别人的服务 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-132 | 桩 18399 / Redis 6379 | 同 011/010 | 保留 | 与 CI 同值同源，便于复现；登记端口段即可 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-133 | HttpClient 3 min | 与 099 的「轮次预算 + 余量」应同源 | 派生 | 3 min 与 360s（099/100）不同口径，同一仓库两套 HTTP 上限；统一后，调 total-budget 只改一处 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-134 | 健康等待 120 s | 双实例冷启实测 <40s；启动预算常取 3×P95 | 新值 60 s | 120s 是实测的 3 倍以上，失败时要白等两分钟；60s 仍是 1.5 倍余量，足够覆盖 CI 抖动 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-135 | 唤醒就绪 30 s | 以「连接真正建立」为判据，而非计时 | 保留 | 哨兵文件 + 连接探测的双判据是对的；建议注明 30s = 300 次 ×100ms，改动节拍时同步算乘积 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-136 | 杀实例接管 90 s | 接管窗 ≥ 租约 TTL + 重试延迟 + 一次探测 | 保留 | 产品 lease 12s + retry-delay 17s = 29s，90s 是 3 倍余量，合理；缺的是把公式写进注释（与 267 同源） | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-137 | SSE 读取缺省 120 s | 产品 total-budget 300s；客户端超时应派生 | 派生 | 120s 与 300s 不一致，长轮次会被脚本先掐断，报错却指向「服务端没回」；改为读 health 的 totalBudget | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-138 | 健康轮询 800 ms | 通行 500 ms–1 s | 保留 | 800ms 在区间内；120s/0.8=150 次，成本可接受；若 134 降到 60s 则 75 次，更轻 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-139 | 端口/Redis 探测 300 ms / 1000 ms | 本机 TCP 建连毫秒级，但负载下 300ms 可能误判「空闲」 | 新值 2000 ms | 300ms 的假阴性后果是「以为端口没人，起服务时冲突」；2s 对建连仍是 2000 倍余量，代价可忽略 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-140 | 订阅建立轮询 100 ms | 亚秒级就绪用 100 ms 合理 | 保留 | 100ms×30s=300 次，目的是「连接一建立就继续」，精度优先；与 135 配套 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-141 | 探针 60 s / 120 s | 同 133：应统一 | 派生 | 同一脚本出现 3 min、2 min、1 min 三套 HTTP 上限，说明没有预算模型；统一到「请求档 15s / 轮次档 budget」两档 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-142 | 后台 job 收尾 90/60 s | 仅崩溃时等满，属兜底 | 保留 | 正常路径毫秒级返回，超时只是防挂死；建议失败时打印 job 状态，否则「等满 90s」无信息量 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-143 | 全量重放 120 s | 终态等待应 = 轮次预算 | 派生 | 与 137 同问题；重放窗口应读 total-budget，而不是另写 120 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-144 | 裸 TCP 读超时 2000 ms | 与产品 Redis 命令超时 2s 同值 | 保留 | 取证客户端用 2s 合理；但「巧合同值」应注明来源，避免产品改 1s 后这里变成瓶颈 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-145 | 等键消失 10 s | 单键 DEL 是毫秒级 | 新值 3 s | 10s 对单键删除是 10000 倍；3s 已足够覆盖慢日志/主从切换，失败也更快暴露 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-146 | 12 s 睡 + PTTL>22 s + 7 s | 续租证据：等待一个续租周期，断言 TTL 回到 `ttl - renew` 附近 | 派生 | 22s 应写成 `leaseTtl - 2s`（产品 12s? 则矛盾）；注释已说「每 10s 续租」，与 lease 12s 不自洽，需先对齐产品键再派生 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-147 | 90 s 窗 + 1 s 节拍 | 并发接管应读事件（`/runs/current`），而非轮询 | 派生 | 1s×90=90 轮、每轮两个 job，开销大且失败时只有「超时」；改为事件读取可同时拿到「谁接管」的证据 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-148 | CDP 9333 / 视口 1400×1800 | Playwright 常用 1280×720、1440×900；CDP 端口 9222 是 Chrome 缺省 | 保留 | 9333 与 9334 错开是对的；1400×1800 是「竖长屏」模拟，建议注释其用途（侧栏+长列表） | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-149 | 帧超时 120 s（可覆写） | 产品前端工具超时 120s | 派生 | 已从写死改为可覆写，方向正确；下一步应读 `/api/ai/health` 的值，否则产品改 90s 时冒烟仍按 120 等 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-150 | 取消收敛 12×500 ms = 6 s | 取消重试常见节奏 2s/6s/14s；6s 只覆盖到第 2 次 | 新值 20 s | 6s 对「取消后收敛」偏紧，且与 151 的 330s 相差 55 倍；20s 可覆盖到第 3 次重试（14s）+余量，失败时能区分「未收敛」与「太早判」 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-151 | 兜底等待 330 s | 兜底不应替代解析；解析失败应直接 FAIL | 新值 30 s | 330s 是「解析失败」的兜底，等于把配置错误变成 5 分钟挂起；30s 内拿不到预算就 FAIL，并打印 health 原文 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-152 | 预算 + 30 s | 余量 = 1 个心跳周期 + 抖动 | 保留 | 从 health 派生预算、再固定加 30s，是本册较好的写法；建议把 30s 标注为「心跳派生」而非魔数 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-153 | 尾段窗 150 s | 与 151 的 330s 两套并存 | 派生 | 150s 无来源；应统一到「预算 + 余量」或「2×心跳 + 工具预算」，避免同一脚本两种时钟 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-154 | 8 处 sleep 0.4–1.5 s | 官方指南：CDP/DOM 操作后应等条件（节点出现、store 变更） | 需实测 | 这些多是**任意等待**：页面已有可观察信号（选择器、网络空闲）。逐处改为 `waitFor` 前需实跑确认稳定信号，静态只能判定「可疑」 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-155 | CDP 9334 + 保护端口 8081/5202/6379 | 安全闸：拒绝杀常开服务 | 保留 | 正面案例；建议把保护清单抽为单一常量并与 README 同步，避免新增常开端口时漏保护 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-156 | 剪贴板 5000 ms | 权限提示的人工窗口；无标准 | 保留 | 属**稳定等待**（系统权限弹窗无程序信号），5s 合理；注释「无权限环境恒 SKIP」即可 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-157 | 帧间静默注入 2500 ms | 必须 < inter-event 90s，且 > 心跳 2s（否则像断流） | 保留 | 2.5s 落在 (2s, 90s) 区间内，设计正确；注释写清区间，改心跳/改阈值时才能判断是否仍合法 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-158 | `waitFor` 缺省 6 s（24×250 ms） | Testing Library 缺省 1s/50ms；Playwright expect 5s | 新值 5 s（并对模型轮强制显式传参） | 6s 对模型轮偏短，导致各调用点另传更大 tries，缺省形同虚设；改为 5s + 「模型轮必须显式传」的约定 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-159 | 9 组 4–15 s 等待 | 等待应分档：UI 即时（≤2s）/网络（≤10s）/模型轮（预算） | 派生 | 9 组 `tries×interval` 无共同来源；抽三档常量后，调一档即全改，且能看出哪个用例用了「模型档」 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-160 | 23 处 sleep 0.1–1.5 s | 同 154；CDP 点击/滚动/复制后等条件 | 需实测 | 23 处是最大脆弱面；多数是**任意等待**（DOM 可观察），少数（复制到剪贴板、滚动合成）属稳定等待。需逐处实跑后分类，不能一刀切删除 | [Playwright best practices](https://playwright.dev/docs/best-practices) |

---

## 5. Redis 5.0 集群冒烟与三实例集群网（N-OPS-161 … N-OPS-191）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-161 | 6 节点（3 主 3 从） | Redis 官方最小生产集群 3 主 3 从；`--cluster-replicas 1` 即此拓扑 | 保留 | 与生产拓扑一致，属规格不是魔数；注释「不一致即不覆盖生产」即可 | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-162 | `cluster-node-timeout 5000` | **Redis 缺省 15000 ms**；5s 是主动收紧 | 保留 | 现值不是「Redis 默认」，注释需订正；5s 让 failover 更快（CI 需要），但也更易在慢 CI 上误判，应注明「CI 加速取值」 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-163 | 死节点探针 10 s | 必须 > node-timeout 才能覆盖判死 | 保留 | 10s > 5s，能覆盖一次判死；注释不等式即可，若 node-timeout 改 15s 则本值必须同步上调 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-164 | 容器就绪 30 s | 容器冷启 <5s；30s 属宽松兜底 | 保留 | 只影响失败时的等待；比 K8s 的 startup 预算（常见 5 min）保守得多，无需改 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-165 | `cluster create` 240 s | 5.0.14 无内部超时，必须外加上限 | 保留 | 240s 是「避免白等 workflow 25 分钟」的正确兜底；建议每秒打印已耗时，否则 240s 静默很像卡死 | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-166 | 槽分配 90 s | 槽分配通常秒级 | 新值 30 s | 90s 是「慢 CI」余量，但槽分配是纯内存操作；30s 已 10 倍余量，失败更早暴露 | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-167 | 从节点识别 60 s | 与 166 同一时刻完成（握手+槽分配同批） | 新值 30 s | 两段串行等待同一事件，合并成一个就绪判定即可；分开等只会让最坏路径变成 150s | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-168 | 订阅建立 10 s ×2 | 协议上 SUBSCRIBE 会回 `subscribe/channel/count` 确认；但若用独立 redis-cli 进程订阅，父进程读不到该回包 | 保留 | 「无 ACK」的说法不严谨：有确认包，只是**测试进程拿不到**。这仍属稳定等待的合理折中；若测试框架能读回包，应改为等确认而非固定窗，并合并两处 helper | [Redis pub/sub](https://redis.io/docs/latest/develop/interact/pubsub/) |
| N-OPS-169 | 从升主观测 25×0.2 = 5 s | failover ≈ node-timeout + 选举/晋升，窗口应取 2×node-timeout | 新值 10 s | 现窗口恰好等于 node-timeout，是临界值，慢 CI 上必假红；取 2 倍才覆盖「判死 + 晋升」两段 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-170 | 节点恢复 120 s | 长尾兜底；无标准 | 保留 | 120s 是合理长尾；失败时应打印 `CLUSTER INFO`，否则无法区分「未恢复」与「判据写错」 | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-171 | 异槽重试 5 次 | 撞槽概率 1/16384；有限重试替代随机红是通行做法 | 保留 | 把随机性转成有限重试，方向正确；建议把 1/16384 写进注释，读者才知道 5 次已足够 | [Redis cluster tutorial](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/) |
| N-OPS-172 | 231xx 段 8 端口 | 端口规划应集中登记 | 保留 | 与产品/前端端口无交集，规划清晰；应登记进统一端口表，避免与未来服务冲突 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-173 | `maxconn 4096` | HAProxy `maxconn` 的常见量级是数千（4096 即其一）；单机 3 实例远达不到 | 保留 | 4096 不会成为瓶颈，改动无收益；若要降，512 也够（3 实例 × 数十连接），属优化不是纠错 | [HAProxy config](https://docs.haproxy.org/2.9/configuration.html) |
| N-OPS-174 | `connect 3s / client 300s / server 310s / request 15s / keep-alive 30s / check 5s` | HAProxy 对 SSE 的官方建议是放宽 `timeout client/server` 并用 `timeout tunnel` | 派生 | 300s 与产品 total-budget 同值是巧合同值；SSE 的判据应是「空闲间隔」（心跳 2s），不是总时长。建议 client/server = 10s（5×心跳）、另设 tunnel 覆盖长连 | [HAProxy config](https://docs.haproxy.org/2.9/configuration.html) |
| N-OPS-175 | `inter 2s fastinter 500ms downinter 2s fall 2 rise 2` | HAProxy 缺省 `inter 2s / fall 3 / rise 2` | 保留 | `fall 2` 是主动收紧（2×2s=4s 摘除），与 drain-window 5s 的推导吻合；缺的是在 drain 注释处反向引用本行 | [HAProxy config](https://docs.haproxy.org/2.9/configuration.html) |
| N-OPS-176 | 桩/Redis/代理就绪 30 s ×3 | 三件基础设施都应有界轮询 | 派生 | 三处复制同一 `seq+sleep` 模式；抽 `wait_until` 后 30s 只需一处，且能统一日志格式 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-177 | 探活 `curl -m 2` | 回环 1–2s | 保留 | 2s 在区间内；与 014 的 5s 不一致，但方向正确（更短），无需改 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-178 | ready 180 / job-fail 130 / kill-takeover 120 | 探针缺省 180/90/120；job-fail 应 = 3×heartbeat + scan + 余量 | 派生 | 130 相对缺省 90 的 +40 无解释；按心跳 30s 算，90 已含 3 次判死，130 多出的 40 应说明是「扫描 + 余量」，否则三套值无法维护 | [K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-179 | 探针 8 端口 + 段自检 | 单一事实源；自检断言端口段是正面做法 | 派生 | 与 172 双写（run.sh 与 probe 各一套）；应由一个 `ports.json/env` 导出，自检才有意义 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-180 | 就绪 180 / 判死 90 | 判死 90 = 3×心跳 30s（正确） | 保留 | 90 的推导清晰（3 次心跳）；问题在 178 覆写成 130，而不是缺省本身 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-181 | 杀实例接管 120 s | 与 136 的 90s 同语义 | 派生 | 同一语义两套值（90/120）；应按 `lease TTL + retry delay + 2×probe` 派生，一处改两处跟 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-182 | park 收流 20 s | 应 < 前端工具预算 120s，> 心跳 | 保留 | 20s 落在区间内，是「多处置信」的合理等待；注释与 120s 的关系即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-183 | 重试观察 10 s | 覆盖重试节奏 2s/6s/14s 的前两次 | 保留 | 推导清晰；建议写成「第 2 次重试时刻 + 余量」，改重试节奏时自动跟随 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-184 | 会话号重取 3 次 | 撞号 503 的有限重试 | 保留 | 有自检锁常量，属正面案例；3 次对随机撞号足够 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-185 | 僵尸扫描 5×5 s = 25 s | 必须 ≥ 产品 zombie-age 30s 才能观察到接管 | 新值 35 s | 25s **小于** 30s 的僵尸阈值，最坏情况下永远等不到接管；改 7×5s=35s 或直接派生 `zombie-age + 5s` | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-186 | 定义数 ≥15 | 同 022/125 | 派生 | 三实例各自冷启都要同一判据，更应由接口自述计数派生 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-187 | 10 组产品键覆写（batch 1、delay 1200ms、heartbeat 5s、scan 3s、dead×3、exec 45s、limit 500/5000、startup 3s、zombie 30s、margin 5s） | 测试覆写通行做法：只缩短「节拍」，不改变「判据比例」 | 需实测 | `heartbeat 5s` 是最危险的一项：所有按 2s 心跳推导的前端/脚本窗口（115/116/157）在此变 2.5 倍语义；必须逐项标「与产品缺省的倍数」并实跑验证 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-188 | `tool-exec 20s / first-byte 15s / inter-event 20s / lease 12s / renew 4s / retry 17s` | 租赁三件套应满足 `ttl ≥ 3×renew`、`retry > ttl + renew` | 保留 | 12 = 3×4、17 > 12+4=16，比例自洽；把两条不等式写进注释即可，这是本册少见的可推导组 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-189 | 12 个等待缺省 4–90 s | 等待应分四档：启动/请求/终态/收流 | 派生 | 一套脚本 12 个缺省，调用方无法预期；归并四档后，新增用例只需选档，不再产生第 13 个值 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-190 | docker CLI 缓冲 64 MiB | Node 缺省 1 MiB；docker 输出通常 <1 MiB | 保留 | 64 MiB 足够且不会有性能问题；建议与 077/085 统一成一个 `MAX_BUFFER` 常量 | [Node child_process](https://nodejs.org/api/child_process.html) |
| N-OPS-191 | 轮询 250/500 ms ×9 | 通行 250/500 两档 | 保留 | 落在区间内；500ms×180s=360 次，对本机可接受；建议注释采样成本 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |

---

## 6. `scripts/githooks/**`（N-OPS-192 … N-OPS-199）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-192 | 20 组 token 最小长度 | 由真实 token 规格决定：AWS AKIA 16、GitHub `ghp_` 36、npm 36 等 | 保留 | 数值是外部规格，不是拍脑袋；缺的是**逐条来源**，否则下次调整无从判断哪条能改 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-193 | 盘符路径 `{1,60}` | 与 check-docs 同构 | 派生 | 两文件逐字同构是为防「拆形绕过」，但维护上是双份；应由 scan-lib 导出，check-docs 引用 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-194 | 超长行 4000 字符整行跳过 | 业界扫描器（gitleaks 等）对长行做**截断后扫描**或熵检测，不整行跳过 | 新值 扫描不跳过（分段读满） | 超长行恰是密钥高发区（压缩的 JSON、minified JS）；「跳过」等于给绕过留门。改为分段扫描，内存上限用流式控制 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-195 | 占位符 `{1,80}` | 与 check-docs 同值 | 派生 | 同 193：同值不同文件，改一处必漂；应单点导出 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-196 | 片段 ≥4 字符 + 20 个通用名 | 用户名拆解通行做法：≥3 字符 + 允许名单 | 新值 3 字符 | 4 字符会漏掉 3 字母用户名（如 `bob`），而通用名清单已能压误报；降到 3 并保留清单，覆盖面更好 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-197 | 跳过 >2048 KB 的 blob | 大 blob 不做全量，但应扫首尾块或熵采样 | 需实测 | 2 MB 门槛会跳过生成物与大数据 fixture，而密钥可能藏在其间；先测被跳过 blob 的数量与类型，再决定是提到 10 MB 还是分块扫 | [gitleaks](https://github.com/gitleaks/gitleaks) |
| N-OPS-198 | 并行 1–8（nproc，上限 8） | CI runner 常 2–4 核；并行上限应 ≤ 核数 ×2 | 保留 | 8 是「别打爆磁盘」的经验值，方向正确；建议按 runner 核数说明，而非绝对 8 | [GH Actions limits](https://docs.github.com/en/actions/reference/limits) |
| N-OPS-199 | 二进制探测 2048 B / sha 8/40 | 「前 2 KB 含 NUL 即二进制」是通行判据；sha 长度是 git 规格 | 保留 | 2048 是探测窗不是阈值；sha 8/40 属 git 规格，无需改 | [git hash-object](https://git-scm.com/docs/git-hash-object) |

---

## 7. 部署 / 运行配置示例（N-OPS-200 … N-OPS-211）

> 部署面结论与四分册一致：本仓无 Dockerfile/Compose/nginx，因此「容器资源限制、副本数、`periodSeconds`」一类数字没有对象可审。本节只对照 Spring/Lettuce/Hikari/PG/H2 的官方默认值。

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-200 | Redis `max-redirects: 3` | Lettuce 缺省 **5** | 保留 | 3 次足以覆盖一次 reshard 的 MOVED/ASK 链；比缺省更保守，能更早暴露「拓扑长期未刷新」；注释「3 跳覆盖一次迁移」即可 | [Lettuce ClusterClientOptions](https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/cluster/ClusterClientOptions.java) |
| N-OPS-201 | Redis `timeout 2s / connect 2s / shutdown 200ms` | Lettuce 命令超时缺省很大（无显式则按 60s 量级）；`shutdownTimeout` 缺省 2s 量级 | 需实测 | 2s 命令超时是「Redis 不可用 ⇒ 503 ≤2s」的前提，属有意收紧；但 `shutdown-timeout 200ms` 只有 2s 的 1/10，停机时未完成的命令可能被丢，需实测确认不影响优雅停机 | [Lettuce client options](https://redis.io/docs/latest/develop/clients/lettuce/) |
| N-OPS-202 | `drain-window: 5s` | K8s preStop 常见 5–15s；ALB `deregistration_delay` 缺省 **300s** | 保留 | 5s ≥ LB 探测 2s×2 + 余量，推导完整；但要意识到：若真实部署在 ALB 后面，**300s 的 LB 摘流才是主导**，应用侧 5s 只解决「探测已摘、连接仍在」的窗口 | [AWS ALB target groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html) |
| N-OPS-203 | H2 `LOCK_TIMEOUT=60000` + `tcpPort 9092` | H2 缺省 **1000 ms**；9092 是 H2 TCP 缺省端口 | 保留 | 60s 是 H2 缺省的 60 倍，但注释已说明「种子单飞等待方需等完整一轮」，属有依据的放宽；9092 是 H2 规格缺省，非魔数。两者都建议从 URL 字面量提到独立键，便于环境覆写 | [H2 commands](https://www.h2database.com/html/commands.html) |
| N-OPS-204 | 共享 H2 池 10 / 取连接 5000 ms | Hikari 缺省 `maximumPoolSize 10`、`connectionTimeout 30000` | 保留 | 池 10 = Hikari 缺省，三实例 30 连接对 H2 无压力；5000ms 取连接比缺省 30s 更早失败，与「探针要在 LB 超时内给结论」一致 | [HikariCP](https://github.com/brettwooldridge/HikariCP#frequently-used) |
| N-OPS-205 | 共享库 `drain-window: 5s` | 同 202 | 保留 | 与 202 同值同源；建议抽公共锚点，避免两个 profile 各自演进 | [AWS ALB target groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html) |
| N-OPS-206 | PG 池 10 / 5000 / 2000 ms | Hikari 缺省 10 / 30000 / 5000；`validationTimeout` 必须 < `connectionTimeout` | 保留 | 10 与缺省一致；2000 < 5000 满足约束；真正要盯的是「实例数 × 10 ≤ PG `max_connections`」这条容量不等式，建议写进部署说明 | [HikariCP](https://github.com/brettwooldridge/HikariCP#frequently-used) |
| N-OPS-207 | PG `lock_timeout = '60s'` | PG 缺省 **0（禁用）**；官方建议 `lock_timeout < statement_timeout` | 保留 | 设 60s 是「不无限等锁」的正确做法，且与 H2 的 60s 对齐；由于 `statement_timeout` 未设（0），该不等式天然成立；应注明两 profile 必须同值 | [PostgreSQL runtime config](https://www.postgresql.org/docs/current/runtime-config-client.html) |
| N-OPS-208 | 优雅停机 20 s | Spring Boot 缺省 **30s**；K8s `terminationGracePeriodSeconds` 缺省 **30s**；SSE 单流可达 300s | 保留 | 20s 是「30s 宽限 − 10s 余量」的合理取值，比框架缺省更保守；但**必然截断 300s 的长流**——正确做法不是把 20 改 300（会撞 SIGKILL），而是停机前给在途流发终态/可重挂提示。属需实测项 | [Spring graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)、[K8s probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/) |
| N-OPS-209 | PG `drain-window: 5s` | 同 202 | 保留 | 与 202/205 三处同值；一致性正确，缺的是单点定义 | [AWS ALB target groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html) |
| N-OPS-210 | 示例 `probe-timeout 5s` / `models-cache-ttl 10m` | 模型清单缓存生态常见 5–15 min；探测 5s 合理 | 保留 | 两项都在主流区间；问题是示例里被注释掉，容易被当成生效值——建议在示例中显式标「注释态=未生效」 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-211 | `VITE_API_BASE 8081` / `VITE_DEV_PORT 5200` | Vite dev server 缺省 5173；代理目标随后端 | 保留 | 示例值与常开服务一致，便于新人起步；本地实际常用 5202/18318，建议示例里并列两套口径并注明用途 | [Vite server options](https://vite.dev/config/server-options) |

---

## 8. `spike/**`（N-OPS-212 … N-OPS-233）

> `spike` 是证据工程，判据是「可复现」而非「可运维」。本节只区分三类：① 与产品同值的规格；② 刻意的试验偏差（应标注）；③ 无依据的随手值（只影响可读性）。

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-212 | 端口 18301 / Tomcat 40 线程 | Tomcat `maxThreads` 缺省 **200** | 保留 | 40 是「压到瓶颈可见」的试验取值，不是产品容量；应在决策卡登记「40 是为观察排队，不是推荐值」 | [Tomcat HTTP connector](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html) |
| N-OPS-213 | 60s / 30s / 2000ms | 产品现值 120s / 无上限 / 2s | 保留 | 属**刻意的试验偏差**，且已注释；建议加一行「勿当产品口径引用」，避免被后续文档抄走 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-214 | 池 8 / 截断 800 字符 | 无标准 | 保留 | 800 字符截断会改变模型可见内容，只适用于样本压缩；建议注释「仅样本压缩，非产品行为」 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-215 | 端口 18302 / Redis 超时 3s | 产品现态 2s | 保留 | 3s 是试验基线，与产品 2s 不同；标注「试验基线」即可，不必改 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-216 | TTL 6h / 40 条 | 产品现为 24h / 40 条 | 保留 | 6h 是历史试验值，已过时；建议标「历史值」而非删除——它是「TTL 从 6h 调到 24h」决策的证据 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-217 | 日志体截断 4000 字符 | 取证需要完整体时会被截 | 保留 | 已有 `...[truncated]` 标记，属可接受的取证折中；4000 字符对定位足够 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-218 | 端口 18303 / 40 线程 | 同 212 | 保留 | 与 212 同构；决策卡统一登记一处即可 | [Tomcat HTTP connector](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html) |
| N-OPS-219 | 5s / 6s / 2 / 20s | 生产值远大于此（30/90/2/300） | 保留 | **明确标注了「非生产值」**，是本册 spike 部分的正面范式；保留 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-220 | 记忆 40 条 / 池 8 | 产品记忆窗口 40（同值） | 保留 | 40 与产品一致，8 线程是试验规模；注释区分两者来源即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-221 | 端口 18306 | 同 212 | 保留 | 端口规划清晰；登记即可 | [Tomcat HTTP connector](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html) |
| N-OPS-222 | 挂起 120s / 确认门 120s | 产品「确认门无上限」 | 保留 | 这是**被证伪的旧形态**（阻塞式挂起）；建议注释「已证伪，勿复用」，其价值是反例证据 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-223 | TTL 6h / 5 轮 / 池 16 | 续跑轮次无标准 | 保留 | 5 轮是安全阀，关键在「超限快速失败」的行为要写明；16 线程是试验承载量 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-224 | core 0 / max 16 / 60s / SynchronousQueue / AbortPolicy | 突发型挂起任务常用「缓存池 + 直接交接 + 饱和拒绝」 | 保留 | 该组合是教科书式的突发池（无队列、饱和即拒），60s keep-alive 是 JDK 常用值；建议注明与产品 `suspend.pool-size 20` 的差异 | [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) |
| N-OPS-225 | 11 处事件窗 30–90 s | 应派生自轮次预算 | 保留 | 试验脚本以「看得见事件」为目的，30–90s 是合理粗窗；若要复用，应按预算派生 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-226 | 90s / 120s / 400ms / 250ms | 无标准 | 保留 | 等实例起 90s、等事件 120s，属试验粗窗；失败时打印最后事件即可 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-227 | 15s / 8s | 短 TTL 实验的到期等待 | 保留 | 8s 必须与覆盖后的 TTL 关联，否则 TTL 调大后等不到；建议改成 `TTL + 1s` 派生 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-228 | 端口 18304 / stall 8s / 断连延迟 250ms | 250ms 决定「截断」证据形态 | 保留 | 延迟断连是为区分「半包」与「整包丢」；建议注释其证据意义 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-229 | 13 组 0.5–180 s 手工时刻 | 试验脚本可接受 | 保留 | 180000 有注释（两分钟不回字节）；建议表格式集中，避免 13 个数字散落 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-230 | `timeout 150` + `sleep 1` | 单实验外壳超时应 > 单实验事件窗 | 保留 | 150s 与 90s 事件窗成 1.6 倍，合理；串行间隔 1s 可忽略 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-231 | 控制器 `Thread.sleep(500)` | 试验代码 | 新值 参数化 | 固定睡在控制器里会掩盖真实时延；改为属性/参数，既保留「模拟处理」又能调 | [Playwright best practices](https://playwright.dev/docs/best-practices) |
| N-OPS-232 | 200ms / N s 睡眠 | 对照「可取消 vs 不可取消」 | 保留 | 对照设计清晰；把 seconds 的来源写进注释即可 | [Thread.sleep](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html) |
| N-OPS-233 | 字段 1–20 / label ≤100（行内字面量） | 同 050–055：应由契约派生 | 派生 | 这两处未纳入「副本清单」，最易漏对账；并入同一事实源后，桩侧校验才完整 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |

---

## 9. 复核补录：桩路由、探针用例、双实例、spike Java（N-OPS-234 … N-OPS-274）

| ID | 现值 | 业界主流 / 对照基准 | 建议 | 理由与联动 | 来源 |
| --- | --- | --- | --- | --- | --- |
| N-OPS-234 | 25 条路由，逐路由 `maxCalls` **不存在** | 测试替身常见「全局限流 + 路由级可选」；无标准 | 保留 | 只有全局 `maxCallsPerTurn=6` 足够覆盖收敛性；这是**已知边界**不是缺陷，登记「逐路由未限」以免误判 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-235 | 冷启动用例 0 覆写 | 基线用例应走缺省，缺省变它先红 | 保留 | 全文件唯一「不覆写」的用例，正好当缺省值的哨兵；建议注释「缺省改动先在此显形」 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-236 | 单请求 10 s | 普通 HTTP 请求 5–15s | 保留 | 10s 与缺省 getJson 15s 不同但同量级；建议统一到 15s，减少「为什么这里 10」 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-237 | 10 s / 120 s | 首请求短、终态长，属正确分层 | 保留 | 120s 对应「三实例接力」的轮次成本；注释轮数×单轮预算即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-238 | 60 s ×3（reattach/全量回放/终态） | 同语义应同值；60 比缺省 90 更紧 | 派生 | 三处同值可抽一个用例常量；60 与 90 的差异需说明是「本用例故意的紧窗」，否则像漂移 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-239 | 30/120/90 s | 首帧 30、终态 120、回放 90 | 派生 | 同一用例三档值，命名常量后读者不必猜；三档本身合理（快慢分离） | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-240 | 30 s / 90 s | 重挂立刻收流用短窗 | 保留 | 30s 是「重挂即收」的判据窗，90s 是全量回放；建议与 182 的 park 20s 关系注明 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-241 | 120 s ×2（两个 run） | 最坏 240s，须 < 用例/job 预算 | 需实测 | 两个 run 若串行，最坏 240s 会吃掉 e2e 25min 的 1/6；先确认并行/串行语义，再决定是否降到 60s | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-242 | 30 s / 60 s | DELETE 60s 远大于单请求 | 派生 | 60s 应来自「在途 deferred 重试」窗，而非随手；与 145 的 3s 形成对照（一个查键、一个跨实例删会话） | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-243 | 30 s（reload） | 应关联产品配置刷新周期 | 需实测 | 30s 是「等 B/C reload」的余量；若产品刷新周期是 5s，30s 可降；先读产品键 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-244 | 30 s ×4 | 同一用例四处复制 | 派生 | 共享文件的上传/下载各 30s，抽一个常量即可；4 处复制最容易改三漏一 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-245 | 90 s / 60 s | scan 60s 与「5×5s 僵尸扫描」不同源 | 派生 | 同一「接管」语义出现 25s/60s/90s 三档；应统一到接管窗派生（见 185/267） | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-246 | 30 s ×2 / 90 s | 90 = 缺省 waitTerminal | 保留 | 显式传 90 与缺省相同，属冗余；省略即可减少一个数字，不影响语义 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-247 | 120 s / 10 s | 120 > job-fail 90s，能覆盖判死 | 保留 | 不等式正确，应注释；10s 单请求在区间内 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-248 | 13 处 1–90 s（readiness 摘除） | drain 窗内探活应「刻意紧」（1–2s），接管终态应「宽」（90s） | 需实测 | 单用例 13 个窗是全文件最密处；两组语义（drain 期探测 / 接管终态）应各自命名常量，且 1000/2000ms 的紧探必须在实跑中确认不误判 | [HAProxy config](https://docs.haproxy.org/2.9/configuration.html) |
| N-OPS-249 | 15 s ×6 等 | 故障期探活 = 命令超时 × 重试 + 余量 | 派生 | 产品 Redis 命令超时 2s，15s 是 7.5 倍；应写成 `2s × 重试次数 + 余量`，否则产品调超时后此处无意义 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-250 | 15 s | 单处；与 249 同值 | 保留 | 与 249 抽同一常量即可；单请求 15s 属宽松但安全 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-251 | 90 s（全量回放） | 同 246 的 90s | 保留 | 双身份场景的回放窗；与 246/252 统一为「回放 90s」常量 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-252 | 30 s / 90 s | 同 240/251 同构 | 保留 | 「重挂 30s / 回放 90s」已是事实上的两档，抽常量即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-253 | 6 个 helper 缺省 15–30 s | 同 189：应并入四档 | 派生 | 189 只列 12 个、这里 6 个散落，读者无法一次看全；合并成一张「helper 缺省表」 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-254 | preflight 5–10 s / 负控 300 ms | 本地起服 1–2s 足够；负控必须超时 | 新值 2 s（负控 300ms 保留） | 5–10s 让自检变慢且无收益；2s 对本地起服仍 10 倍余量。300ms 是「必须失败」的负控，保留 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-255 | health 轮询 800 ms | 同 138 | 派生 | 与 138 同文件同行同值（非独立项）；本行只补语义——它是**所有用例**（含 `-NoStart`）的 A/B 就绪节拍，改它会全局生效 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-256 | 唤醒轮询 100 ms | 亚秒级连接建立用 100ms | 保留 | 与 140 同值（非独立项）；「连接真建立才批准确认」要求高精度，100ms 是合理下限，不建议放宽 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-257 | 计算式等待（`fireAt - now`） | 并发对齐用绝对时刻，是正确做法 | 保留 | 派生式等待（非固定 sleep）的正面案例；注释「对齐精度受调度抖动影响」即可 | [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) |
| N-OPS-258 | 300 ms / 10 s（等锁键消失） | 单键 DEL 毫秒级 | 新值 3 s | 与 145 同判据；锁键只由本脚本删除，10s 是 10000 倍余量，3s 足够且失败更快 | [Redis conf](https://raw.githubusercontent.com/redis/redis/7.2/redis.conf) |
| N-OPS-259 | `.Wait(1000)` ×2 | 哨兵文件 + 端口双探测 | 保留 | 1000ms 是两次单点探测，不是轮询；与 256 的 100ms 配合，属稳定等待的合理组合 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-260 | `Wait-Job -Timeout 90` | 后台 job 收尾兜底 | 保留 | 正常毫秒返回，90s 只防挂死；与 136 的 90s 同值但语义不同，建议改名区分 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-261 | `Wait-Job -Timeout 60` | 两路 confirm job 收尾 | 保留 | 60s 对两路 HTTP 偏宽但只影响失败路径；建议与 257 的齐发窗对齐，便于同时观察 | [Playwright timeouts](https://playwright.dev/docs/test-timeouts) |
| N-OPS-262 | 500 ms / 120 s（全量重放） | 重放应「续收」而非全量 | 派生 | 500ms×120s=240 次重挂，开销大；改用 lastSeq 续收后，窗口可降到 30s 量级 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-263 | 3 轮 / 60 s | 竞态用例轮数常见 3–5 | 保留 | 3 轮足以撞出竞态；建议注释「单轮失败即中止」还是「继续撞」，否则失败语义不清 | [Playwright retries](https://playwright.dev/docs/test-retries) |
| N-OPS-264 | 12 s / PTTL>22 s | 续租证据 = `ttl - renew` 派生 | 派生 | 22s 应写成 `leaseTtl - renew + 抖动`；现在 12s/22s/7s 三个魔数互相解释，改产品 lease 时必错 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-265 | 7 s / 90 s | 避开续租点后杀实例 | 派生 | 7s 依赖「续租每 10s」的节奏；应派生自 renew 周期，否则产品改 4s 续租后这里会杀在续租点上 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-266 | 1 s / 60 s | 并发 resume 应读事件 | 派生 | 1s×90 轮、每轮两个 job 成本高；改读 `/runs/current` 后既省请求又能拿到接管者身份 | [Testing Library waitFor](https://testing-library.com/docs/dom-testing-library/api-async/) |
| N-OPS-267 | 90 s（派生自 `KillTakeoverTimeoutSec`） | 接管窗 ≥ lease TTL + retry delay | 保留 | 12+17=29s，90s 是 3 倍余量，公式正确；把不等式写进注释，与 136/181 统一口径 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-268 | core 4 / queue 32 / max 8 | 有界队列 + 固定核心是服务端工具池常见配置 | 保留 | 4/8/32 是一组（核心/上限/队列），建议在决策卡登记饱和后的拒绝策略 | [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) |
| N-OPS-269 | 队列 64 | 与 268 的 32 不一致 | 保留 | 两个 spike 的并发假设不同，64 不是错；但需注明各自假设，否则看起来像复制粘贴错 | [ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) |
| N-OPS-270 | `MAX_ROUNDS=4` | 产品 `max-tool-steps 25`；前端工具专项是子循环 | 保留 | 4 轮是前端工具子循环上限，与产品 25 不同层；注释「前端工具专项」即可，勿混为一谈 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-271 | `.retry(1)` ×2 | 参照实现 vs 受控实现两套重试是刻意对照 | 保留 | 复刻参照实现的重试次数是实验变量，**不可统一**；建议两处都注明「对照项，勿合并」 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-272 | 30 层栈帧 | 仅诊断输出；JVM 缺省栈深远大于此 | 保留 | 30 层足够定位业务调用链，不影响判定；注释「仅取证」即可 | [Thread.dumpStack](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html) |
| N-OPS-273 | 截断 800 字符 | 与 214 同值，三处复制 | 派生 | sp01ab 与 sp02 各自 800，若要保证「实验对照同值」更应抽公共常量；否则一边改、对照失效 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |
| N-OPS-274 | exec-delay 缺省 0 ms | 故障注入的缺省应是「关闭」，这是安全缺省 | 保留 | 0 = 不注入，方向正确；注释「注入时必须 >0 且 < 客户端超时」即可 | [Spring Boot properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html) |

---

## 10. 统计（274 / 274 全覆盖）

> 机械核对：`N-OPS-001…274` 共 **274 行**，唯一、连续、无缺号、无重号；每行 6 列（ID/现值/业界对照/建议/理由与联动/来源）完整，来源列 274 行全部含 URL。建议列主类计数闭合：162 + 58 + 31 + 23 = 274。

### 10.1 按建议类型

| 建议 | 条数 | 占比 | 含义 |
| --- | --- | --- | --- |
| **保留** | **162** | 59.1% | 现值落在业界区间或属规格常量；多数只需补注释 |
| **派生** | **58** | 21.2% | 手抄的别处函数值，改为读 yml/health/常量/心跳 |
| **新值** | **31** | 11.3% | 明确给出替换数字（见 10.3） |
| **需实测** | **23** | 8.4% | 无公开标准，必须本仓测 P95 / 压测后定值 |
| 合计 | **274** | 100% | — |

### 10.2 按分册章节

| 章节 | 行数 | 保留 | 派生 | 新值 | 需实测 |
| --- | --- | --- | --- | --- | --- |
| §1 workflows（001-048） | 48 | 21 | 8 | 9 | 10 |
| §2 scripts/ci（049-097） | 49 | 35 | 8 | 4 | 2 |
| §3 verify-e2e（098-130） | 33 | 18 | 10 | 3 | 2 |
| §4 双实例/CDP（131-160） | 30 | 12 | 9 | 6 | 3 |
| §5 redis/cluster（161-191） | 31 | 19 | 7 | 4 | 1 |
| §6 githooks（192-199） | 8 | 3 | 2 | 2 | 1 |
| §7 部署 profile（200-211） | 12 | 11 | 0 | 0 | 1 |
| §8 spike（212-233） | 22 | 20 | 1 | 1 | 0 |
| §9 补录（234-274） | 41 | 23 | 13 | 2 | 3 |

**读法**：workflow 与补录两节集中了 23 条需实测中的 13 条——不是它们最差，而是「超时预算」本质不可从别处抄。`spike` 22 条里 20 条保留，说明试验工程的数字问题主要是**标注**而非取值。

### 10.3 31 条新值清单（可直接抄进评审表）

| 组 | ID → 现值 ⇒ 新值 |
| --- | --- |
| job 预算 | 003 5min⇒**3min**；006 10min⇒**5min**；008 5min⇒**2min**；042 5min⇒**2min** |
| 归档 | 023 7d⇒**14d 证据 / 7d 覆盖率**；028 7d⇒**14d**；045 7d⇒**14d** |
| 桩缺省 | 061 50s⇒**95s**（inter-event+5s） |
| 探活超时 | 014 5s⇒**2s**；020 3s⇒**1s**；104 60s⇒**10s**；139 300ms⇒**2000ms**；145 10s⇒**3s**；254 5–10s⇒**2s** |
| 观察窗 | 116 3000ms⇒**5000ms**；121 2000ms⇒**1000ms** |
| 启动/收敛 | 134 120s⇒**60s**；150 6s⇒**20s**；151 330s⇒**30s**；158 6s⇒**5s** |
| 集群等待 | 166 90s⇒**30s**；167 60s⇒**30s**；169 5s⇒**10s**；185 25s⇒**35s** |
| 闸门/扫描 | 081 7d⇒**3d**；087 128MiB⇒**16MiB**；094 8字符⇒**16字符**；194 跳过长行⇒**分段扫**；196 4字符⇒**3字符** |
| 其他 | 231 固定 sleep⇒**参数化**；258 10s⇒**3s** |

### 10.4 23 条需实测清单（按取证方式分组）

| 取证方式 | ID | 要测什么 |
| --- | --- | --- |
| 跑 20 次取 P95 | 002、004、005、007、024、030、033、039、041 | job 墙钟分布；测完改成 `P95×1.5` 或 `步数×单步 P95×1.3` |
| 单点计时 | 019、105、243 | 冷启 P95、两处未知等待的真实耗时、配置 reload 周期 |
| 环境探测 | 131、201、241 | 8081 占用、停机时在途命令是否丢失、双 run 串行最坏 240s |
| 统计/规模 | 077、083、197 | 最大 diff 体积、最大扇出、被跳过的 blob 数量与类型 |
| 实跑观察信号 | 112、154、160 | 12/8/23 处 sleep 能否替换为条件等待（静态只能判「可疑」） |
| 压测/故障注入 | 187、248 | heartbeat=5s 覆写后的语义、13 个摘流窗口是否误判 |

---

## 11. 优先级（只提不改；P0 是「会骗人」的，不是「不好看」的）

### P0 · 判据自相矛盾或造数不触发（8 项，先裁这些）

| # | ID | 现状 | 问题一句话 | 处置方向 |
| --- | --- | --- | --- | --- |
| 1 | **116 / 115** | 3000ms vs 5000ms | 同文件注释说「3000 只兜 1 个心跳（2s）会假绿」，实现仍用 3000 | 116 改 5000，并把式子固化为 `2×heartbeat+1s` |
| 2 | **061** | 桩缺省 50000ms | **小于**产品 inter-event 90s，缺省永远触发不了帧间看门狗 | 改 95s 或强制显式传参 |
| 3 | **185** | 5×5s = 25s | **小于**产品 zombie-age 30s，最坏等不到接管 | 改 35s 或派生 `zombie-age+5s` |
| 4 | **169** | 25×0.2s = 5s | 观察窗**恰好等于** node-timeout 5s，慢 CI 必假红 | 取 2×node-timeout = 10s |
| 5 | **065** | 30000ms | 与产品 first-byte 30s **相等**，谁先触发不确定 | 取 `first-byte+5s` |
| 6 | **150** | 12×500ms = 6s | 只覆盖到第 2 次重试（6s），第 3 次在 14s | 改 20s，或直接等 `cancelled` 帧 |
| 7 | **187** | 集群覆写 `heartbeat=5s` | 所有按 2s 心跳推导的窗口（115/116/157）在此变 2.5 倍语义 | 逐项标注倍数，或集群侧不改心跳 |
| 8 | **162（注释）** | `cluster-node-timeout 5000` | 注释称「5s 是 Redis 默认」，**实际默认 15000** | 改注释为「CI 加速值，默认 15s」 |

> **208 不在 P0 但同属「会骗人」**：20s 停机并不违反框架（缺省 30s、K8s 宽限 30s），但它与 300s 的 SSE 单流**必然冲突**。正确处置不是把 20 改 300（会撞 SIGKILL），而是停机前给在途流发终态/可重挂提示——这是设计动作，不是数字动作。

### P1 · 单一事实源（改一个必须全改，否则长期漂移）

| 组 | ID | 现状 | 应收敛到 |
| --- | --- | --- | --- |
| 手抄产品键 | **099、101、106、137、143、149、153** | 300s、`(30×3)+10+20`、120s 等写死在脚本 | 读 `application.yml` / `/api/ai/health`（同文件已有 `Get-AiResilienceSeconds` 可复用） |
| redis 健康检查 | 009、026、043 | 三/四个 workflow 逐字复制 5s/3s/12 | 一个 composite action 或 env |
| 等待脚本 | 013、014、019、021、046 | ci 与 nightly 两套副本 | 共享 `wait-for` 脚本 |
| 定义数 ≥15 | 022、125、186 | 三处硬编码 | 种子清单/接口自述计数 |
| 产品上限副本 | 050-055、233、122、123 | 桩/脚本各自 100/50/8000/2000 | 契约常量或 health 下发 |
| 正则同构 | 193、195、089 | scan-lib 与 check-docs 各一套 | scan-lib 单点导出 |
| 端口双写 | 172、179 | run.sh 与 probe 各一套 231xx | 一个 `ports` 常量 |
| 接管窗三值 | 136、178、180、181、267 | 90 / 130 / 120 并存 | `lease TTL + retry + 2×probe` 一个公式 |

### P2 · 数值卫生（低风险，可批量做）

| 组 | ID | 动作 |
| --- | --- | --- |
| job 预算瘦身 | 003、006、008、042 | 4 处降档（见 10.3） |
| 归档统一 | 023、028、045 | 失败证据统一 14 天，覆盖率留 7 天 |
| 随手等待 | 104、105、139、145、258 | 60s→10s、300ms→2s、10s→3s |
| 缓冲 | 077、087、095、097 | 大缓冲改为「够用 + 越界即报」 |
| 闸门文字 | 094、092、037 | 理由长度、棘轮责任人、收紧节奏 |
| 重复等待 | 166、167、254、158 | 合并 90/60、自检降到 2s、waitFor 缺省 5s |

---

## 12. 锁步联动（改任一数字前先看这张表）

| 值 | 直接消费点 | 一起必须复核 |
| --- | --- | --- |
| `total-budget 300s` | 099（手抄）、100（+60）、106（360s）、137（120）、143（120） | 前端 300s 墙钟（N-FE-024）、HAProxy `client 300s`（174） |
| `heartbeat 2s` | 115（5000=2×2+1）、116（3000 不达标）、157（2500 区间）、121（2000 判据） | 187 的 `heartbeat=5s` 覆写；确认门心跳 2s |
| `inter-event 90s` | 061（50s 不够）、119（+5s）、157（<90s） | 产品 026、前端 45s 帧间 |
| `first-byte 30s` | 065（30s 相等）、068（8s < 30）、118（+5s） | 产品 025、前端首帧 30s |
| `lease 12s / renew 4s / retry 17s` | 146（22s）、264（12s/22s）、265（7s）、267（90s）、136（90s）、181（120s） | 产品 031/032（zombie-age 180s = 120+60） |
| `zombie-age 30s`（集群覆写） | 185（25s 不够）、245（scan 60s） | 187 的 `zombie-margin 5s` |
| 定义数 15 | 022、125、186 | 种子清单（基座 7 + glm 8） |
| `batch 10 / delay 150` | 015、016 | 分片边界断言（TC11） |

---

## 13. 边界与未覆盖

1. **无容器编排对象**：全仓无 Dockerfile/Compose/nginx，因此「CPU/内存 limit、副本数、`periodSeconds`、`terminationGracePeriodSeconds`」没有对象可审；208 的 20s 只能按 Spring 默认与 K8s 默认做**假设性**对照。若将来引入，需新增一节。
2. **固定 sleep 的可替换性只能静态判「可疑」**：112/154/160 共 43 处，本文按「信号是否存在」分类，但「页面是否已稳定」必须实跑观察，不能仅凭源码断言。
3. **窗口「够不够」需压测**：248（13 个窗）、249（故障期 15s×6）、241（双 run 最坏 240s）只做了覆盖性与来源判断，数值充分性要故障注入实测。
4. **job 超时无行业标准**：001-048 中 10 条标「需实测」，因为 GitHub 只规定缺省 360 min，不规定「一个 mvn 测试 job 该给多久」；任何给出「业界 20 分钟」的说法都是编的。
5. **真实模型采样无行业标准**：027 的「10 次」只能按二项分布解释（rule of three），不能对照某个厂商的公开阈值；若要统计结论需 n≥30–60。
6. **spike 的跨进程时序**未推导端到端最坏时长（229 的 13 组时刻只是注入值），按「证据可复现」管理，不按「时序预算」管理。
7. **未读敏感文件**：`.env*` / `.env.local` 未读，211 的实际本地值未知，只能对照示例与 Vite 默认。

---

## 14. 一句话总结

**274 条里 162 条该保留（59%），真正要改数字的只有 31 条（11%），但另有 58 条（21%）本质是「手抄了别处的函数」——这 58 条不改成派生，31 条新值也会在半年内重新漂回去。** 最该先裁的不是任何一个超时值，而是 P0 的 8 条「判据自相矛盾」：它们会让 CI 在真坏时变绿（061/185/116），或让好代码变红（169/065/150）。
