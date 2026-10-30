# 数字规格审视 · 脚本 / CI / 部署 / 试验工程（numspec-2026-10-31 · 分册）

> 范围：`scripts/**`（含 `scripts/ci`、`scripts/githooks`、测试脚本里的产品等待/重试/上限）、`.github/workflows/**`、根目录与 `backend`/`frontend` 运行配置示例与部署 profile、`spike/**`。
> 边界：**只读**。本文件是唯一新增物；未改任何现有代码/测试/配置，未做 git 写操作，未启动服务、容器、浏览器，未跑测试。未读取 `.env*` / `.env.local` 等敏感文件。
> 基线：分支 `main`，HEAD `405e74ebf96b3340c9443c6e3087708814e996f6`；`git status --porcelain` 共 **73** 项（M/D/??）。本分册覆盖的文件在 HEAD 上**均无改动**（`scripts/**`、`.github/**`、`spike/**`、`backend/config/application.yml.example`、`frontend/.env.example` 全干净）；`backend/src/main/resources/application.yml` 有他人改动，本册只审其**副本/引用面**（cluster / h2tcp / postgresql profile），不重复登记主表。
> 与旧 NUMSPEC 的关系：逐项复核了旧表 `docs/evidence/补档-NUMSPEC-清单总账.md` 的 S-01…S-33（CI/脚本段），按**现态**重查而非照抄。**迁移工具已删除**（`FileKeyMigrationRunner` / `FileKeyMigrationLock` / `RedisFileKeyMigrationLock` 及其测试），脚本/CI/spike 内已无任何 `FileKeyMigration*` 引用（全文检索 0 命中），故旧表中与迁移锁、迁移重试、迁移批量相关的条目**整体作废**，本册不设对应行。
> **计数口径（两种口径并列）**：**ID 行数 = 274**；**扣除完全重复后的独立项 = 257**。扣除 17 = 部署 profile 跨册重复 9 行（200-202、204-209；203 因 `tcpPort 9092` 独立增量保留）+ 前端 env 跨册同源 1 行（211，同 N-FE-170/171）+ 双实例完全重复 7 行（255/256 同 138/140 同行同值；260/261/264/265/266 见 142/146/147）。完全重复行**保留**以供对账，行内均标注「非独立项」。

## 0. 口径

- **来源**：`配置` = 有 yml/JSON/env/参数可改；`硬编码` = 脚本字面量或常量；`框架缺省` = 本仓未显式设置、取 GitHub Actions / Docker / HAProxy / Spring 默认值。
- **依据**：`有` = 有数值论证、实测或裁决记录（注释/设计稿）；`部分` = 说明了用途或对齐来源但取值本身无论证；`裸数字` = 无解释。
- 「旧ID」列仅在与旧表条目**确为同一条**时给出；同一旧行被拆成多行时，各行共享同一旧 ID。
- 审视建议一律**只提不改**；"不修改"是本次任务的硬约束。

---

## 1. `.github/workflows/**`

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-001 | 工作流预算 | `.github/workflows/ci.yml:49` | `timeout-minutes` | 5 min | changes job（变更集分类器自检 + 分类）墙钟上限 | 硬编码 | 裸数字 | 与实测分类耗时（秒级）留 2 个数量级余量；建议记录一次 P95 供后续收紧 | S-01 |
| N-OPS-002 | 工作流预算 | `.github/workflows/ci.yml:103` | `timeout-minutes` | 20 min | backend job（mvn test + JaCoCo） | 硬编码 | 裸数字 | 后端全量单测随用例增长；建议以近 20 次运行时长 P95×1.5 复核 | S-01 |
| N-OPS-003 | 工作流预算 | `.github/workflows/ci.yml:166` | `timeout-minutes` | 5 min | redis-guard job | 硬编码 | 裸数字 | 纯 grep，5 min 富余；可降到 2–3 min 以加快失败反馈 | — |
| N-OPS-004 | 工作流预算 | `.github/workflows/ci.yml:187` | `timeout-minutes` | 20 min | frontend job（typecheck + vitest + build） | 硬编码 | 裸数字 | 同 002：以实测 P95 复核 | S-01 |
| N-OPS-005 | 工作流预算 | `.github/workflows/ci.yml:254` | `timeout-minutes` | 25 min | e2e job（35 用例 + 桩 + redis） | 硬编码 | 裸数字 | 该值是脚本内 90s+60s 等待、120s 作业窗等派生的上界之和；建议与 verify-e2e 的主用例预算对齐复核 | S-01 |
| N-OPS-006 | 工作流预算 | `.github/workflows/ci.yml:433` | `timeout-minutes` | 10 min | docs job（check-docs + i18n 报告） | 硬编码 | 裸数字 | 纯 Node 扫全仓，10 min 偏宽；建议按实测收紧 | S-01 |
| N-OPS-007 | 工作流预算 | `.github/workflows/ci.yml:474` | `timeout-minutes` | 10 min | scan-history job（全历史 blob 扫描） | 硬编码 | 裸数字 | 与 `scan-history.sh` 的并行上限（8）耦合；建议注明「仓库增长后需调」 | S-01 |
| N-OPS-008 | 工作流预算 | `.github/workflows/ci.yml:547` | `timeout-minutes` | 5 min | summary job（纯 shell 汇总） | 硬编码 | 裸数字 | 无外部依赖，可降至 2 min | — |
| N-OPS-009 | 阈值 | `.github/workflows/ci.yml:273-276` | `--health-interval 5s --health-timeout 3s --health-retries 12` | 5s / 3s / 12 次（≈60s 判死） | e2e job 的 redis service 健康检查 | 硬编码 | 部分（注释说明 noeviction 与 7.2.16 口径，未论证健康参数） | 与 Docker 默认 30s/30s/0 不同，属主动收紧；建议注释写明 60s 判死窗的推导 | S-02 |
| N-OPS-010 | 端口 | `.github/workflows/ci.yml:108` `.github/workflows/ci.yml:270` | `6379:6379` | 6379 | backend/e2e 的 redis service 宿主端口 | 硬编码 | 部分（全仓一致，无端口规划说明） | 与本地 Memurai 6379 同值；runner 上无冲突，建议登记为「全仓 Redis 约定端口」 | S-07 |
| N-OPS-011 | 端口 | `.github/workflows/ci.yml:325` | `--port 18399` | 18399 | 桩上游监听端口 | 硬编码 | 部分（脚本与文档多处一致） | 183xx 段已成事实标准（18301/18302/18303/18318/18330/18399），建议集中登记端口段 | S-07 |
| N-OPS-012 | 端口 | `.github/workflows/ci.yml:346` | `--server.port=18330` | 18330 | E2E 后端端口 | 硬编码 | 部分（同上） | 同 011 | S-07 |
| N-OPS-013 | 等待 | `.github/workflows/ci.yml:329` | `sleep 2` | 2 s | 桩进程拉起后的固定等待 | 硬编码 | 裸数字 | 桩是纯 ESM 秒起；建议改为「轮询 /probe 直到 200」而非固定等 | S-06 |
| N-OPS-014 | 超时 | `.github/workflows/ci.yml:330` | `curl -sS -m 5` | 5 s | 桩 /probe 探活单次上限 | 硬编码 | 裸数字 | 本地回环 + 静态响应，1–2s 足够；建议与 013 合并为有界轮询 | S-05 |
| N-OPS-015 | 阈值 | `.github/workflows/ci.yml:348` | `--app.job.batch-size=10` | 10 行/片 | E2E 覆写作业分片大小（默认 100 的 1/10） | 硬编码 | 部分（README/脚本注「TC11 需跨分片边界」） | 属「为测试而调」的产品键；建议在 workflow 注里写明断言依赖点 | S-08 |
| N-OPS-016 | 阈值 | `.github/workflows/ci.yml:349` | `--app.job.demo-batch-delay-ms=150` | 150 ms/片 | E2E 覆写作业节拍（默认 300 的 1/2） | 硬编码 | 部分（同上，150 无独立论证） | 与产品默认 300ms 成 2 倍关系；建议注明只影响演示节拍、不改语义 | S-08 |
| N-OPS-017 | 阈值 | `.github/workflows/ci.yml:350` | `--app.ai.limit.session-per-minute=200` | 200 次/分/会话 | E2E 把限流抬到不可达量级 | 硬编码 | 有（注释：限流不在 E2E 覆盖面，由单测覆盖） | 该「抬到不可达」是正面做法；建议把「不可达」判据（用例数×并发 < 200）写进注释 | — |
| N-OPS-018 | 阈值 | `.github/workflows/ci.yml:351` | `--app.ai.limit.ip-per-minute=2000` | 2000 次/分/IP | 同上（IP 维） | 硬编码 | 有（同上） | 同 017 | — |
| N-OPS-019 | 轮询 | `.github/workflows/ci.yml:360-367` | `seq 1 90` + `sleep 1` | 90 s 上限（1 s 节拍） | 后端 `/api/ai/health` 双 true 就绪等待 | 硬编码 | 部分（注释说明「环境坏 vs 用例坏」分工，90s 无论证） | 建议与 backend 启动实测（冷启 <30s）对齐后收紧；或改为读 boot.log 的就绪行 | S-03 |
| N-OPS-020 | 超时 | `.github/workflows/ci.yml:361` | `curl -s -m 3` | 3 s | 健康探活单次上限 | 硬编码 | 裸数字 | 回环请求，1s 足够；与 019 的 1s 节拍相加会逼近上限，建议同源化 | S-05 |
| N-OPS-021 | 轮询 | `.github/workflows/ci.yml:376-379` | `seq 1 60` + `sleep 1` | 60 s 上限 | 种子就绪等待（定义数 ≥15） | 硬编码 | 部分（注释引 T12-E#8 说明为何不用 health 判定） | 种子是启动期同步播种，60s 偏宽；建议以 boot.log 的 seed 完成行做事件等待 | S-04 |
| N-OPS-022 | 阈值 | `.github/workflows/ci.yml:378` | `>= 15` | 15 条定义 | 种子就绪判据（基座 7 + glm 8） | 硬编码 | 有（与 DataSeedRunner/GlmSeedService 种子事实对齐） | 种子扩增时此数须同步（建议由接口返回的自述计数替代硬编码） | S-18 |
| N-OPS-023 | 缓冲 | `.github/workflows/ci.yml:153` `.github/workflows/ci.yml:408` `.github/workflows/ci.yml:527` | `retention-days: 7` | 7 天 ×3 处 | JaCoCo / E2E 证据 / 历史扫描报告归档保留 | 硬编码 | 裸数字 | 三处语义不同（覆盖率、失败证据、审计报告）；建议分别论证（证据链留存周期常 ≥ 30 天） | S-09 |
| N-OPS-024 | 工作流预算 | `.github/workflows/e2e-real.yml:35` | `timeout-minutes` | 60 min | e2e-stub job（9 步各起一套 JVM+vite） | 硬编码 | 部分（注释「35 分钟不够全量」，未给实测） | 启动耗时主导；建议记录单步 P95 后把 60 改成「步数×单步 P95×1.3」 | S-01 |
| N-OPS-025 | 工作流预算 | `.github/workflows/e2e-real.yml:201` | `timeout-minutes` | 45 min | e2e-real job（真模型采样） | 硬编码 | 裸数字 | 真模型延迟方差大；建议绑定采样次数（默认 10）× 单次轮次预算 | S-01 |
| N-OPS-026 | 阈值 | `.github/workflows/e2e-real.yml:41-45` `.github/workflows/e2e-real.yml:209-213` | `--health-interval 5s --health-timeout 3s --health-retries 12` | 5s / 3s / 12 次 | 两个 job 的 redis 健康检查 | 硬编码 | 部分（同 009） | 与 ci.yml 三处重复；建议抽为统一约定并注释 | S-02 |
| N-OPS-027 | 行数/样例 | `.github/workflows/e2e-real.yml:24` | `default: '10'` | 10 次采样 | 真模型 E2E 的采样次数缺省 | 配置（workflow_dispatch 输入） | 部分（注释称「C 级概率口径 §0.3」，未附概率推导） | 建议在注释写清置信度与失败判定（10 次全过才绿？），否则采样数无判据 | — |
| N-OPS-028 | 缓冲 | `.github/workflows/e2e-real.yml:181` `.github/workflows/e2e-real.yml:194` `.github/workflows/e2e-real.yml:282` | `retention-days: 7` | 7 天 ×3 处 | trace/screenshot/harness 日志归档 | 硬编码 | 裸数字 | 同 023；失败证据建议 ≥14 天（与 cluster3 的 14 天不一致） | S-09 |
| N-OPS-029 | 阈值 | `.github/workflows/e2e-real.yml:131` | `head -c 32 \| base64` | 32 字节（AES-256 KEK） | MDXY 域本步内生成测试 KEK | 硬编码 | 有（32B = AES-256 规格，外部事实） | 属外部加密规格，非可调项；建议注释直接写「AES-256」以免被当魔数 | — |
| N-OPS-030 | 工作流预算 | `.github/workflows/cluster-3node.yml:46` | `timeout-minutes` | 45 min | 三实例集群 job（3 JVM + H2 TCP + Redis + HAProxy） | 硬编码 | 裸数字 | 内含 ready 180s + 多轮 kill/故障用例；建议按用例预算求和复核 | S-01 |
| N-OPS-031 | 缓冲 | `.github/workflows/cluster-3node.yml:108` | `retention-days: 14` | 14 天 | 集群证据归档 | 硬编码 | 裸数字 | 与 e2e-real 的 7 天不一致；建议统一策略 | S-09 |
| N-OPS-032 | 并发/池 | `.github/workflows/cluster-3node.yml:32-34` | `concurrency.group: cluster-3node` + `cancel-in-progress: false` | 并发度 1（排队不取消） | 独占 Docker 资源，避免半套证据 | 硬编码 | 有（注释：取消会留半套证据） | 该取舍清晰；建议补「最长排队时间」预期，避免误判为卡住 | — |
| N-OPS-033 | 工作流预算 | `.github/workflows/redis-5-smoke.yml:36` | `timeout-minutes` | 25 min | Redis 5.0.14 集群冒烟 | 硬编码 | 部分（脚本注「集群创建 240s 硬上限，避免白等 workflow 25 分钟」——反向引用） | 25 min 是脚本内 240s×多段的容器；建议注释给出段数预算 | S-01 |
| N-OPS-034 | 缓冲 | `.github/workflows/redis-5-smoke.yml:65` | `retention-days: 14` | 14 天 | 5.0 集群证据 | 硬编码 | 裸数字 | 同 031 | S-09 |
| N-OPS-035 | 并发/池 | `.github/workflows/redis-5-smoke.yml:24-26` | `concurrency.group: redis-5-smoke` + `cancel-in-progress: false` | 并发度 1 | 独占 Docker，保留完整证据 | 硬编码 | 有（同 032 理由） | 同 032 | — |
| N-OPS-036 | 定时 | `.github/workflows/nightly.yml:38` | `cron: '17 18 * * *'` | UTC 18:17（北京 02:17） | 夜间全量触发时刻 | 硬编码 | 部分（注释：避开整点排队） | 分钟取 17 属「避开整点」经验；建议保留并注明天文/负载考虑即可 | — |
| N-OPS-037 | 阈值 | `.github/workflows/nightly.yml:55` | `DETERMINISM_MAX_DIFF: '40'` | 40 类差异 | 倒排表确定性棘轮（并集修复后允许的最大差异类数） | 配置（env，可改） | 有（注释：实测 33 类，+20% 余量，只许下调） | 现为「实测+7」；建议注明收紧节奏（每夜 -N）与责任人 | — |
| N-OPS-038 | 工作流预算 | `.github/workflows/nightly.yml:67` | `timeout-minutes` | 30 min | 后端全量 ×2（默认 locale + en-US） | 硬编码 | 裸数字 | 是 ci backend 20 min 的 1.5 倍，比值合理；建议注明「双跑」推导 | S-01 |
| N-OPS-039 | 工作流预算 | `.github/workflows/nightly.yml:118` | `timeout-minutes` | 25 min | 前端全量 | 硬编码 | 裸数字 | 比 ci 的 20 min 多 5 min 无说明；建议对齐或注明夜间机器差异 | S-01 |
| N-OPS-040 | 工作流预算 | `.github/workflows/nightly.yml:154` | `timeout-minutes` | 25 min | 夜间 E2E | 硬编码 | 裸数字 | 与 ci e2e 同值 | S-01 |
| N-OPS-041 | 工作流预算 | `.github/workflows/nightly.yml:293` | `timeout-minutes` | 60 min | testmap 双遍录制 | 硬编码 | 部分（注释：两遍全量 + 本机实测 261 类） | 两遍全量是刚性成本；建议按「单遍实测 ×2 + 余量」写成公式 | — |
| N-OPS-042 | 工作流预算 | `.github/workflows/nightly.yml:407` | `timeout-minutes` | 5 min | 夜间汇总 | 硬编码 | 裸数字 | 同 008 | — |
| N-OPS-043 | 阈值 | `.github/workflows/nightly.yml:162-166` | `5s / 3s / 12` | 同 009 | 夜间 e2e 的 redis 健康检查 | 硬编码 | 部分 | 同 009，且为第 4 处复制；建议抽公共约定 | S-02 |
| N-OPS-044 | 缓冲 | `.github/workflows/nightly.yml:108` `.github/workflows/nightly.yml:394` | `retention-days: 14` | 14 天 ×2 | surefire 报告 / 倒排表与审计 | 硬编码 | 裸数字 | 与 ci 的 7 天不一致（同 023） | S-09 |
| N-OPS-045 | 缓冲 | `.github/workflows/nightly.yml:273` | `retention-days: 7` | 7 天 | 夜间 E2E 证据 | 硬编码 | 裸数字 | 同 023 | S-09 |
| N-OPS-046 | 等待 | `.github/workflows/nightly.yml:209` `.github/workflows/nightly.yml:210` `.github/workflows/nightly.yml:234` `.github/workflows/nightly.yml:239` `.github/workflows/nightly.yml:249` `.github/workflows/nightly.yml:251` | `sleep 2` / `curl -m 5` / `curl -m 3` / `sleep 1` / `curl -m 3` / `sleep 1` | 2s、5s、3s、1s、3s、1s | 夜间 E2E 的桩/健康/种子等待（与 ci.yml 逐字重复） | 硬编码 | 部分（整体口径同 013/014/019/021） | 与 ci.yml 是两套副本，改一处必忘另一处；建议抽成共享脚本 | S-03/S-04/S-05/S-06 |
| N-OPS-047 | 行数/样例 | `.github/workflows/nightly.yml:358` | `.slice(0, 20)` | 20 个类名 | 不稳定类列表打印上限 | 硬编码 | 裸数字 | 仅日志展示；建议同时打印总数（已打印）即可，无需调 | — |
| N-OPS-048 | 并发/池 | `.github/workflows/nightly.yml:45-47` | `concurrency.group: nightly-full` + `cancel-in-progress: false` | 并发度 1 | 夜间与手工重跑串行，避免抢 runner/缓存 | 硬编码 | 有（注释：全量的价值在跑完） | 同 032 | — |

---

## 2. `scripts/ci/**`（桩、门禁、选择器、守卫）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-049 | 端口 | `scripts/ci/stub-upstream.mjs:89` | `PORT = Number(arg('port','18399'))` | 18399（可 `--port` 覆写） | 桩上游缺省端口 | 配置（CLI 参数） | 部分（workflow 显式传同值） | 与 011 同源；建议端口段集中登记 | S-07 |
| N-OPS-050 | 阈值 | `scripts/ci/stub-upstream.mjs:135` | `MAX_TITLE_CHARS = 100` | 100 字符 | 桩对 `GenerativeFormRules` 标题上限的副本 | 硬编码（事实源副本） | 部分（:98-108 明写「副本清单」，自检③改为读事实源对账） | 副本与事实源需逐值对账；建议把 5 项上限也纳入自动对账（现注释承认只对账了部分） | S-19 |
| N-OPS-051 | 阈值 | `scripts/ci/stub-upstream.mjs:136` | `MAX_OPTIONS = 50` | 50 个选项 | 选项数量上限副本 | 硬编码（副本） | 部分 | 同 050 | S-19 |
| N-OPS-052 | 阈值 | `scripts/ci/stub-upstream.mjs:137` | `MAX_OPTION_TEXT_CHARS = 100` | 100 字符 | 单选项文本上限副本 | 硬编码（副本） | 部分 | 同 050 | S-19 |
| N-OPS-053 | 阈值 | `scripts/ci/stub-upstream.mjs:138` | `MAX_PLACEHOLDER_CHARS = 100` | 100 字符 | 占位符上限副本 | 硬编码（副本） | 部分 | 同 050 | S-19 |
| N-OPS-054 | 阈值 | `scripts/ci/stub-upstream.mjs:139` | `MAX_FORM_CHARS = 8000` | 8000 字符 | 表单序列化总长上限副本 | 硬编码（副本） | 部分 | 同 050；8000 与产品一致但未说明是字节还是字符 | S-19 |
| N-OPS-055 | 阈值 | `scripts/ci/stub-upstream.mjs:140` | `MAX_LINKAGE_DEPTH = 2` | 2 层 | 依赖链深度上限副本（G-13 断言四层被拒） | 硬编码（副本） | 部分 | 与 G-13 的 4 层造数成对；建议注释写「2 = 产品上限，4 = 越界造数」 | — |
| N-OPS-056 | 阈值 | `scripts/ci/stub-upstream.mjs:133` | `KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,39}$/` | 总量词 40 字符 | 字段 key 校验（首字符 + 39） | 硬编码 | 部分（注释说明模式来源） | `{0,39}` 是「40 含首字符」的写法，易误读；建议改为 `{0,39}` + 注释 | S-20 |
| N-OPS-057 | 缓冲 | `scripts/ci/stub-upstream.mjs:801` | `REASONING_FRAME_CAP = 64 * 1024` | 64 KiB | 思考文本单片上限（超过则单片不再切） | 硬编码 | 部分（注释关联产品 64KB 帧上限） | 与 S-13 的 64KB 帧断言同源；建议注明「字节 vs 字符」口径 | — |
| N-OPS-058 | 行数/样例 | `scripts/ci/stub-upstream.mjs:831` | `text.match(/[\s\S]{1,24}/g)` | 24 字符/片 | 命名轮文本切片大小（决定 delta 帧数） | 硬编码 | 部分（注释：切片数由串长决定，不依赖模型） | 24 直接决定用例的 delta 断言粒度；建议注明「改此值须复核帧数断言」 | S-21 |
| N-OPS-059 | 行数/样例 | `scripts/ci/stub-upstream.mjs:626` `scripts/ci/stub-upstream.mjs:883` | `.slice(0,160)` / `.slice(0,300)` | 160 / 300 字符 | 日志取证截断（首帧摘要 / 上下文摘要） | 硬编码 | 裸数字 | 仅取证可读性；建议统一成一个常量并注明「不影响判定」 | S-21 |
| N-OPS-060 | 阈值 | `scripts/ci/stub-upstream.mjs:311` | `'break-at-seq': 2` | 第 2 帧 | 故障开关缺省：写满 2 帧后断流 | 硬编码（JSON 有同值） | 部分（`stub-routes.json` 有 note，无数值论证） | 2 决定「断流点落在 start 之后」，建议注释该语义 | — |
| N-OPS-061 | 等待 | `scripts/ci/stub-upstream.mjs:312` | `'stall-frames': 50000` | 50000 ms（50 s） | 帧间静默缺省（S-04 验帧间看门狗） | 硬编码（JSON 同值） | 部分（须 > 产品 inter-event 90s 才触发？见审视） | **50 s < 产品 inter-event-timeout 90 s** ⇒ 该缺省不足以触发帧间看门狗，用例必须显式传更大值；建议核对 S-04 实际传参 | — |
| N-OPS-062 | 等待 | `scripts/ci/stub-upstream.mjs:312` | `'kill-stream': 3000` | 3000 ms | 到点销毁 socket（模拟上游 TCP 断） | 硬编码（JSON 同值） | 部分 | 3 s 需落在首帧产出之后；建议注释断点位置 | — |
| N-OPS-063 | 等待 | `scripts/ci/stub-upstream.mjs:313` | `'slow-tool': 130000` | 130000 ms（130 s） | 工具轮首帧前静默（建模编排慢） | 硬编码（JSON 同值） | 部分 | 130 s > 前端工具超时 120 s，用于「工具不回流」类用例；建议注释该不等式 | — |
| N-OPS-064 | 阈值 | `scripts/ci/stub-upstream.mjs:313` | `'multi-card': 3` | 3 张卡 | 一批多张待决卡数量 | 硬编码（JSON 同值） | 部分 | 3 决定 H-04 的逐卡决策轮数；建议注释 | — |
| N-OPS-065 | 等待 | `scripts/ci/stub-upstream.mjs:314` | `'stall-headers': 30000` | 30000 ms | 写响应头前静默（建连看门狗用例 S-03） | 硬编码（JSON 同值） | 部分 | 用例会传 firstByte+5s，缺省值仅兜底；建议注明「用例应显式传」 | — |
| N-OPS-066 | 缓冲 | `scripts/ci/stub-upstream.mjs:314` | `'huge-frame': 102400` | 102400 字节（100 KiB） | 单个 delta 帧字节数（验 64KB 帧上限） | 硬编码（JSON 同值） | 部分（note 说「64KB 帧上限」，值是 100KB） | 名与值不一致（100 KiB 造数 vs 64 KiB 上限）；建议注释说明「造数须越界」 | — |
| N-OPS-067 | 重试 | `scripts/ci/stub-upstream.mjs:314` | `'tool-loop': 26` | 26 次 | 连续工具调用次数（产品步数上限 25 ⇒ 26 越界） | 硬编码（JSON 同值） | 有（note 写明「步数上限 25」） | 26=上限+1 的越界造数，方向正确；建议保留 | — |
| N-OPS-068 | 等待 | `scripts/ci/stub-upstream.mjs:315` | `'slow-first-byte': 8000` | 8000 ms | 只慢首包（首帧前静默） | 硬编码（JSON 同值） | 部分 | 8 s 需小于产品首包 30 s；建议注释不等式 | — |
| N-OPS-069 | 等待 | `scripts/ci/stub-upstream.mjs:315` | `'slow-tool-exec': 8000` | 8000 ms | 只慢工具执行段首帧 | 硬编码（JSON 同值） | 部分 | 同上 | — |
| N-OPS-070 | 等待 | `scripts/ci/stub-upstream.mjs:315` | `'slow-tail-frames': 1200` | 1200 ms/帧间 | 收尾段拉长帧间静默（P-04） | 硬编码（JSON 同值） | 部分 | 1.2 s 与前端停止判定相关；建议注释判据 | — |
| N-OPS-071 | 阈值 | `scripts/ci/stub-upstream.mjs:315` | `'late-frames': 2` | 2 帧 | [DONE] 之后继续推的迟到帧数 | 硬编码（JSON 同值） | 部分 | 2 足够证明「整帧丢弃」；建议保留 | — |
| N-OPS-072 | 行数/样例 | `scripts/ci/stub-routes.json:402` | `'sheet-big': default 500` | 500 行 | 用例自建 500 行任务（script 层开关） | 硬编码（JSON） | 部分（note：桩不造业务数据） | 500 行是表格主用例的规模；建议注明与产品分页/采样上限 20 的关系 | — |
| N-OPS-073 | 阈值 | `scripts/ci/stub-routes.json:35` | `convergence.maxCallsPerTurn = 6` | 6 次/轮 | 桩单轮最多工具调用次数（收敛性硬闸） | 配置（JSON） | 部分（路由表 note 说明设计口径，6 无独立论证） | 6 与产品 max-tool-steps（25）不一致且更严；建议注明「桩收敛闸」与产品上限的分工 | S-23 |
| N-OPS-074 | 行数/样例 | `scripts/ci/stub-routes.json:78-103` | `last 1200/40/1240/640/16`、`repeat 4321/77/4398/2048/12`、`zero-last 3100/50/3150`、`big 11000/200/11200` | 5 组 token 数 | usage 终帧造数（E2E-CTX-1..4 唯一取数点） | 配置（JSON） | 部分（`big` 有 note：前缀约 6.7K token，需跨 70/80/95 三档） | `last` 是历史基线不可动；`big=11000` 为「窗口 ≥22K」造数，建议把 22K 推导写进 note | S-22 |
| N-OPS-075 | 阈值 | `scripts/ci/gate.mjs:49` | `SHADOW_WINDOW = 300` | 300 笔 | 影子期漏检率分母（O4 判据） | 硬编码 | 有（注释引 §7.3-③ / O4「漏检 ≤1 笔 / 300 笔」） | 300 笔是统计窗口裁决值；建议保留并在看板标注窗口起止日 | — |
| N-OPS-076 | 阈值 | `scripts/ci/gate.mjs:50` | `SHADOW_MAX_MISSED = 1` | 1 笔 | 窗口内允许的最大漏检数 | 硬编码 | 有（同上） | 与 075 成对（1/300 ≈ 0.33%）；建议把「1 笔必须根因归类」写进门禁输出 | — |
| N-OPS-077 | 缓冲 | `scripts/ci/gate.mjs:78` | `maxBuffer: 256 * 1024 * 1024` | 256 MiB | git/mvn 子进程 stdout 缓冲 | 硬编码 | 裸数字 | 大仓 diff 可能超 256 MiB；建议改用流式或按需提高并注明原因 | — |
| N-OPS-078 | 行数/样例 | `scripts/ci/gate.mjs:267-268` | `ledger.entries.length > 400` → `slice(-400)` | 400 笔 | 影子账本滚动保留条数（> 判据窗口 300 有余量） | 硬编码 | 部分（注释说明余量关系） | 400 = 300 + 100 的余量，方向可解释；建议写成 `SHADOW_WINDOW + 100` | — |
| N-OPS-079 | 行数/样例 | `scripts/ci/gate.mjs:382` `scripts/ci/gate.mjs:385` | `missed.slice(0,40)` / `extra.slice(0,10)` | 40 / 10 条 | 漏检/多红打印上限 | 硬编码 | 裸数字 | 仅日志；可保留 | — |
| N-OPS-080 | 阈值 | `scripts/ci/select-tests.mjs:28` | `MIN_KEY_OVERLAP = 0.95` | 95% | 倒排表键覆盖硬闸（可记录类口径） | 硬编码 | 部分（注释：与录制器 B0 闸同口径；95% 未论证） | 95% 是「表可信」的生命线；建议给出实测覆盖率（如 99.2%）与下限余量 | — |
| N-OPS-081 | TTL | `scripts/ci/select-tests.mjs:29` | `MAX_TABLE_AGE_MS = 7 * 24 * 3600 * 1000` | 7 天 | 倒排表过期即退化为全量 | 硬编码 | 部分（注释说明过期退全量，7 天无论证） | 夜间每日重建 ⇒ 7 天=漏 6 夜才失效；建议按夜间节奏复核 | — |
| N-OPS-082 | 阈值 | `scripts/ci/select-tests.mjs:30` | `MAX_TABLE_COMMITS = 200` | 200 提交 | 表与 HEAD 距离上限 | 硬编码 | 裸数字 | 与 081 双闸；建议说明为何用「提交数」而非「时间」之一即可 | — |
| N-OPS-083 | 阈值 | `scripts/ci/select-tests.mjs:31` | `FANOUT_LIMIT = 50` | 50 个测试类 | 单文件扇出超限即全量（F7） | 硬编码 | 部分（规则名/理由有，阈值无论证） | 扇出分布应实测；建议记一次最大扇出（如 38）作为 50 的依据 | — |
| N-OPS-084 | 阈值 | `scripts/ci/select-tests.mjs:32` | `SIZE_CAP_RATIO = 0.6` | 60% | 选中类数超过全量 60% 即退全量 | 硬编码 | 部分（有规则，无比值论证） | 60% 决定「选择集是否还有意义」；建议与实测选择率分布对照 | — |
| N-OPS-085 | 缓冲 | `scripts/ci/changed-files.mjs:161` | `maxBuffer: 64 * 1024 * 1024` | 64 MiB | git diff 输出缓冲 | 硬编码 | 裸数字 | 大 PR 可能逼近；建议失败时打印明确诊断（现为异常） | — |
| N-OPS-086 | 行数/样例 | `scripts/ci/changed-files.mjs:196` | `.slice(0, 50)` | 50 条 | step summary 变更文件清单上限 | 硬编码 | 部分（文案「最多 50 条」自洽） | 仅展示；可保留 | — |
| N-OPS-087 | 缓冲 | `scripts/ci/check-docs.mjs:83` | `maxBuffer: 128 * 1024 * 1024` | 128 MiB | `git ls-files -z` 输出缓冲 | 硬编码 | 裸数字 | 全仓路径列表远小于 128 MiB；建议缩小以暴露异常 | S-24 |
| N-OPS-088 | 阈值 | `scripts/ci/check-docs.mjs:92-93` | `[-A-Za-z0-9_.\\/]{1,60}` | 60 字符 | 盘符路径匹配的片段长度上限（与 scan-lib 同构） | 硬编码 | 部分（注释：与 scan-lib.sh 逐字同构，防拆形绕过） | 60 是「够长即命中」的经验值；建议把上限写进注释理由 | S-25 |
| N-OPS-089 | 阈值 | `scripts/ci/check-docs.mjs:108` | `/<[^>]{1,80}>/g` | 80 字符 | 占位符 `<…>` 剔除长度上限 | 硬编码 | 部分 | 与 scan-lib.sh:187 同值但两处各写一套；建议同源化 | S-25 |
| N-OPS-090 | 阈值 | `scripts/ci/check-docs.mjs:338-339` | `NAMING_SEEDS_CONCAT_MIN = 5` / `NAMING_SEEDS_SEPARATED_MIN = 24` | 5 / 24 条 | 命名纪律自检种子数量下限（与数组长度解耦） | 硬编码 | 有（:330-337 注完整论证：删种子行会被下限抓住） | 正面案例（主动避免「下限=数组长度」）；建议保留并作为其他自检的范式 | S-26 |
| N-OPS-091 | 行数/样例 | `scripts/ci/check-i18n-residue.mjs:106` | `.slice(0, 15)` | 15 个文件 | 残留中文 Top-N 打印 | 硬编码 | 裸数字 | 仅日志；可保留 | — |
| N-OPS-092 | 阈值 | `scripts/ci/i18n-residue-baseline.json:2` | `"total": 940` | 940 行 | i18n 残留中文棘轮基线（--strict 时超基线即红） | 配置（基线文件） | 部分（棘轮口径有，940 是快照事实） | 基线须随收口持续下调；建议在看板标注「只许下调」与更新责任 | — |
| N-OPS-093 | 其他 | `scripts/ci/check-d6-sync.mjs:40` | `REVIEW_VERSION = 1` | 1 | D6 复核记录格式版本 | 硬编码 | 部分（版本号语义自洽，无迁移说明） | 升版需兼容旧记录；建议注释升级策略 | — |
| N-OPS-094 | 阈值 | `scripts/ci/check-d6-sync.mjs:45` | `MIN_REASON_CHARS = 8` | 8 字符 | `not-affected` 理由最小长度（去空白） | 硬编码 | 部分（注释：排除 n/a、无、待补 等占位） | 8 字符很短（中文 8 字≈一句话片段）；建议提高并加「须含具体对象」判据 | — |
| N-OPS-095 | 缓冲 | `scripts/ci/check-d6-sync.mjs:391` | `maxBuffer: 64 * 1024 * 1024` | 64 MiB | git diff 子进程缓冲 | 硬编码 | 裸数字 | 同 085 | — |
| N-OPS-096 | 阈值 | `scripts/ci/forbid-redis-cluster-unsafe.mjs:588` | `positive < 12` | ≥12 条 | 自检正向样例数量下限（防规则表被删空仍全绿） | 硬编码 | 有（注释：防止整段删空后仍全绿） | 正面案例；建议其他 `--self-check` 脚本也设同类下限 | — |
| N-OPS-097 | 缓冲 | `scripts/ci/frontend-related.mjs:52` `scripts/ci/frontend-related.mjs:84` | `maxBuffer: 64 * 1024 * 1024` | 64 MiB ×2 | git 子进程缓冲 | 硬编码 | 裸数字 | 同 085 | — |

---

## 3. `scripts/verify-e2e.ps1`（产品链路 E2E，35+ 主用例）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-098 | 端口 | `scripts/verify-e2e.ps1:95` | `$Base = 'http://127.0.0.1:18330'` | 18330 | 后端基地址缺省（可 `-Base` 覆写，CI 显式传同值） | 配置（参数） | 部分（与 CI/文档一致） | 同 011/012 端口段登记 | S-07 |
| N-OPS-099 | 超时 | `scripts/verify-e2e.ps1:132` | `$script:TurnBudgetSeconds = 300` | 300 s | 单轮对话机器预算（= `app.ai.resilience.total-budget` 的手抄值） | 硬编码（注释指向产品键） | 有（:127-131 注：根源键为 total-budget，公式 +60s 余量） | 值仍是**手抄**而非读 yml（对比 `Get-AiResilienceSeconds` 已读文件）；建议同一取数点 | S-13 |
| N-OPS-100 | 超时 | `scripts/verify-e2e.ps1:133` | `HttpTimeoutMs = (TurnBudgetSeconds + 60) * 1000` | 360000 ms | 单请求/SSE 读流总预算 | 硬编码（派生式） | 有（注释：+60s 覆盖建连/收尾/抖动；原为 5min 与 180000 两个魔数） | 派生关系清晰；建议余量 60 s 也给出依据（一次重连 + 服务端收尾） | S-13 |
| N-OPS-101 | 超时 | `scripts/verify-e2e.ps1:291` | `((30*3)+10+20)*1000` | 120000 ms | 作业终态等待窗（= heartbeat 30s ×3 + scan 10s + 余量 20s） | 硬编码（派生式） | 有（:287-290 注：与 application.yml 四键对应，键值调整须同步） | 派生式但**常量 30/3/10/20 是抄的**；建议读 yml 或注明同步检查点 | S-10 |
| N-OPS-102 | 轮询 | `scripts/verify-e2e.ps1:296` | `Start-Sleep -Milliseconds 400` | 400 ms/次 | `Wait-Job` 轮询节拍 | 硬编码 | 裸数字 | 400ms 与 120s 窗口 = 300 次请求；建议 1s 节拍降噪 | S-12 |
| N-OPS-103 | 轮询 | `scripts/verify-e2e.ps1:308` | `Start-Sleep -Milliseconds 500` | 500 ms/次 | `Wait-Until` 轮询节拍 | 硬编码 | 裸数字 | 同 102 | S-12 |
| N-OPS-104 | 超时 | `scripts/verify-e2e.ps1:1520` `.ps1:2619` `.ps1:2936` `.ps1:3605` `.ps1:6133` | `Wait-Until … 60` | 60 s ×5 | 「确认后作业创建/发布」等待上限 | 硬编码 | 裸数字 | 60 s 与作业创建实测（毫秒级）差 4 个数量级；建议按健康探针或作业列表事件等待 | S-11 |
| N-OPS-105 | 超时 | `scripts/verify-e2e.ps1:4740` `.ps1:6556` | `Wait-Until … 20` / `… 10` | 20 s / 10 s | 预算面可读 / 任务行联动终态等待 | 硬编码 | 裸数字 | 两值语义不同却同为「随手取」；建议各自给出判据 | — |
| N-OPS-106 | 超时 | `scripts/verify-e2e.ps1:377` | `$cts.CancelAfter($script:HttpTimeoutMs)` | 360000 ms | SSE 读流（`Read-SseCore`）硬取消 | 硬编码（派生） | 有（同 100） | 「读流总预算 = 单轮预算」放大了整棒失败等待；建议按用例覆盖短窗 | S-13 |
| N-OPS-107 | 重试 | `scripts/verify-e2e.ps1:588` `.ps1:1562` | `[int]$maxSuspend = 6` | 6 次 | 单轮对话最多处理的挂起次数 | 硬编码 | 裸数字 | 6 决定「多卡多轮」用例上限；建议与产品 max-tool-steps/multi-card 关联说明 | S-15 |
| N-OPS-108 | 重试 | `scripts/verify-e2e.ps1:1908` `.ps1:2000` `.ps1:2080` `.ps1:2174` | `while ($attempt -lt 3)` | 3 次 | GF 用例：重发以凑出生成式表单挂起 | 硬编码 | 部分（注释：仅 schema 漂移时重试，非「换会话凑绿」） | 3 次重发会掩盖偶发不通；建议把「3 次都不出即红」的判据写进失败文案 | — |
| N-OPS-109 | 重试 | `scripts/verify-e2e.ps1:2304` | `while ($gf6Attempt -lt 3 …)` | 3 次 | GF6 入参闸门用例重发上限 | 硬编码 | 裸数字 | 同 108 | — |
| N-OPS-110 | 重试 | `scripts/verify-e2e.ps1:2609` `.ps1:2856` | `for ($attempt = 1; $attempt -le 3; …)` | 3 次 | TC17/TC23 发送重试 | 硬编码 | 裸数字 | 同 108 | — |
| N-OPS-111 | 重试 | `scripts/verify-e2e.ps1:1421` | `$attempt -le 2` | 2 次 | 工具结果帧获取重试 | 硬编码 | 裸数字 | 同 108 | — |
| N-OPS-112 | 等待 | `scripts/verify-e2e.ps1:1917` `.ps1:1924` `.ps1:2009` `.ps1:2016` `.ps1:2089` `.ps1:2096` `.ps1:2183` `.ps1:2190` `.ps1:2326` `.ps1:2605` `.ps1:2613` `.ps1:2868` | `Start-Sleep -Seconds 2` | 2 s ×12 处 | 各用例的固定等待（GF 重发间隔、发布后等待等） | 硬编码 | 裸数字 | 12 处固定 2s 是时序脆弱面（旧表 H-06 同判）；建议逐处替换为条件等待或注明不可替换的理由 | S-16 |
| N-OPS-113 | 等待 | `scripts/verify-e2e.ps1:2593` `.ps1:3392` | `Start-Sleep -Seconds 3` | 3 s ×2 | 发布后等待 / 挂起停留观察窗 | 硬编码 | 裸数字 | 3392 有注释（gateWait 观察窗）；2593 无；建议只保留有注释的固定等待 | S-16 |
| N-OPS-114 | 轮询 | `scripts/verify-e2e.ps1:547` `.ps1:579` `.ps1:637` `.ps1:1604` `.ps1:3076` `.ps1:3106` `.ps1:3355` `.ps1:3625` `.ps1:3653` `.ps1:3683` `.ps1:3690` `.ps1:3705` `.ps1:3805` `.ps1:3867` `.ps1:4427` `.ps1:4831` `.ps1:5349` `.ps1:5361` `.ps1:5373` `.ps1:6544` `.ps1:6564` `.ps1:6587` | `Start-Sleep -Milliseconds 200/300/400/500/800` 与 `-Seconds 1` | 0.2–1 s | 各轮询/观察节拍（确认门、快照、帧面） | 硬编码 | 部分（部分点有注释说明观察期） | 体量最大的时序面（22 处）；建议统一成 `Wait-Until` 且节拍常量化 | S-16 |
| N-OPS-115 | 超时 | `scripts/verify-e2e.ps1:2212` `.ps1:2282` `.ps1:2891` `.ps1:3436` `.ps1:3822` | `Read-Sse-Brief … 5000` | 5000 ms ×5 | 「无新帧」观察窗（GF4/GF5/TC23/S-13/S-14） | 硬编码 | 有（:1644-1650 注：窗口 ≥ 2 个心跳周期 2s×2 + 1s 抖动；2026-10-10 裁决 3000→5000） | 推导完整，属正面案例；建议把该式写成 `2*heartbeat+1s` 常量而非多处字面量 | S-14 |
| N-OPS-116 | 超时 | `scripts/verify-e2e.ps1:3410` `.ps1:3680` | `Read-Sse-Brief … 3000` | 3000 ms ×2 | 短窗观察（S-12 前帧 / H-04 逐卡确认） | 硬编码 | 部分（同文件注释称 3000 只兜得住 1 个心跳周期） | **与 115 的判据冲突**：注释说 3000 不达标，此处仍用 3000；建议复核这两处是否该升到 5000 | S-14 |
| N-OPS-117 | 轮询 | `scripts/verify-e2e.ps1:3125-3130` | `retryAfterMs` 重挂 | 服务端下发（≥1 ms） | 202 准备中按契约重试，而非固定 sleep | 配置（服务端值） | 有（注释：按产品自己的信号有界等待） | 正面案例（不写死重试间隔）；建议保留 | — |
| N-OPS-118 | 阈值 | `scripts/verify-e2e.ps1:3202` `.ps1:3209` | `first-byte-timeout` + 5000 | 服务端值 + 5 s | S-03：stall-headers 必须大于首包超时才会触发看门狗 | 配置（读 application.yml） | 有（:3196-3208 注） | 正面案例（阈值从事实源读）；+5000 的余量建议注明来源 | — |
| N-OPS-119 | 阈值 | `scripts/verify-e2e.ps1:3222` `.ps1:3226` | `inter-event-timeout` + 5000 | 服务端值 + 5 s | S-04：静默 > 帧间超时须由看门狗判死 | 配置（读 yml） | 有（同 118） | 同 118 | — |
| N-OPS-120 | 阈值 | `scripts/verify-e2e.ps1:3416-3430` | `'L' * 4096` / `huge-frame=102400` | 4096 字符 / 102400 字节 | S-13：单帧 64KB 上限不破 JSON（正文 ≥4096 且可解析/落库） | 硬编码 | 部分（用例标题写 64KB，造数用 100KB） | 断言的是「不丢正文」而非帧大小；建议注释 4096 的选取（远小于 64KB 但足证未整帧丢弃） | — |
| N-OPS-121 | 阈值 | `scripts/verify-e2e.ps1:3440` | `$firstMs -gt 2000` | 2000 ms | S-14：慢订阅者不得拖慢第一路首帧 | 硬编码 | 裸数字 | 2 s 与心跳 2 s 同量级；建议按「首帧应 << 心跳」给更紧的判据 | — |
| N-OPS-122 | 阈值 | `scripts/verify-e2e.ps1:3902-3915` | `2001` / `2000` 字符 | 2000 字符上限 | G-08：longText 2000 上限（2001 拒、2000 过） | 硬编码 | 有（用例即边界值，2000/2001 成对） | 属产品上限的边界断言；建议改为从产品常量/接口读取上限（避免双写） | — |
| N-OPS-123 | 行数/样例 | `scripts/verify-e2e.ps1:4153` `.ps1:4155` `.ps1:5209` | `sampleMaxRows > 20` / `< 1` | ≤20 且 ≥1 行 | 读侧采样上限（防整表进模型） | 硬编码 | 部分（断言「硬顶 20」，来源未注） | 20 是产品侧采样上限；建议注明产品键名，避免脚本成为第二事实源 | — |
| N-OPS-124 | 阈值 | `scripts/verify-e2e.ps1:5450` | `Abs(delta) -gt 2000` | ±2000 | W-05：ui_scroll 只改视口，入参 delta 有界 | 硬编码 | 裸数字 | 2000 是「滚一屏」的像素经验值；建议注释 | — |
| N-OPS-125 | 阈值 | `scripts/verify-e2e.ps1:797` | `$map.Count -lt 15` | 15 条 | 定义数前置断言（基座 7 + glm 8） | 硬编码 | 有（同 022） | 同 022 | S-18 |
| N-OPS-126 | 行数/样例 | `scripts/verify-e2e.ps1:762` `.ps1:1148` `.ps1:1201` `.ps1:1385-1386` `.ps1:2562` | `size=500` / `size=50` / `size=5` | 500 / 50 / 5 行/页 | 各用例分页取数（全量列表、issues、数据行、两页不重复） | 硬编码 | 部分（:1380 注：size=5 两页不重复、total ≥5） | 500 是「够用的大页」；建议改为按 total 翻页，避免数据增长后截断 | — |
| N-OPS-127 | 阈值 | `scripts/verify-e2e.ps1:3981` | 依赖深度上限 2 | 2 层 | G-13：四层依赖被后端拒 | 硬编码 | 有（用例即越界造数） | 同 055；建议与桩侧 `MAX_LINKAGE_DEPTH=2` 同源 | — |
| N-OPS-128 | 阈值 | `scripts/verify-e2e.ps1:2204` | `reasons ≥ 3` | 3 条 | GF6：非法 schema 的拒绝理由条数 | 硬编码 | 部分（注释「推演 4 条」） | 断言 ≥3 而造数 4；建议注释为何不是 ≥4 | — |
| N-OPS-129 | 行数/样例 | `scripts/verify-e2e.ps1:1701` | `$stopAfterFrames = 3` | 3 帧 | `Read-Sse-Brief-Post`：读满 3 帧即主动断流（模拟客户端离开） | 硬编码 | 部分（注释：等价于前端用完即走） | 3 帧决定「断点」位置；建议注明断点须落在 start/delta 之间 | — |
| N-OPS-130 | 阈值 | `scripts/verify-e2e.ps1:3335-3336` `.ps1:4921-4922` | `frontendToolTimeoutSeconds < 1` | ≥1 s | S-09/D-11：断言预算已下发但**不写死 120** | 配置（读 /api/ai/health） | 有（旧表 S-17 记录的「不硬编码」纪律在此延续） | 正面案例；建议保留并把该纪律写进 README | S-17 |

---

## 4. 多实例 / CDP 冒烟脚本（`scripts/*.ps1`、`scripts/*.mjs`）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-131 | 端口 | `scripts/b23l1-dual-instance.ps1:64-65` | `$PortA = 8081` / `$PortB = 8082` | 8081 / 8082 | 双实例 harness 的 A/B 端口（**禁用端口**，产品常开） | 配置（参数） | 部分（注释：与常开服务同端口，故须避开并行） | 8081/8082 是产品默认；建议脚本开头校验「本机无 8081 常开实例」再跑 | S-07 |
| N-OPS-132 | 端口 | `scripts/b23l1-dual-instance.ps1:66` `.ps1:68` | `$StubPort = 18399` / `$RedisPort = 6379` | 18399 / 6379 | 桩上游与共享 Redis 端口 | 配置（参数） | 部分（同 011） | 同 011 | S-07 |
| N-OPS-133 | 超时 | `scripts/b23l1-dual-instance.ps1:104` | `$script:http.Timeout = FromMinutes(3)` | 3 min | harness 内 HttpClient 单请求上限 | 硬编码 | 裸数字 | 与 verify-e2e 的 360s 不同口径；建议统一为「轮次预算 + 余量」 | — |
| N-OPS-134 | 超时 | `scripts/b23l1-dual-instance.ps1:71` | `$HealthTimeoutSec = 120` | 120 s | 实例 /api/ai/health 就绪等待 | 配置（参数） | 裸数字 | 双实例冷启 <40s；建议按实测收紧 | — |
| N-OPS-135 | 超时 | `scripts/b23l1-dual-instance.ps1:72` | `$WakeReadyTimeoutSec = 30` | 30 s | 唤醒用例：等后台 SSE 订阅真正建立 | 配置（参数） | 部分（注释：连接真建立了才批准确认） | 30 s 是「哨兵文件 + 连接探测」的上界；建议注明探测节拍 100ms 的乘积关系 | — |
| N-OPS-136 | 超时 | `scripts/b23l1-dual-instance.ps1:73` | `$KillTakeoverTimeoutSec = 90` | 90 s | 杀实例后接管/恢复等待 | 配置（参数） | 部分（注释：LEASE_HELD_ELSEWHERE 时靠延迟重试接管） | 90 s 须 ≥ 租约 TTL + 重试延迟；建议把公式写进注释（见 178 探针的 12s lease） | — |
| N-OPS-137 | 超时 | `scripts/b23l1-dual-instance.ps1:408` | `[int]$timeoutMs = 120000` | 120 s | 单次 SSE 读取缺省上限 | 硬编码（参数默认值） | 裸数字 | 与产品 total-budget 300s 不一致；建议从 health 读取 | — |
| N-OPS-138 | 轮询 | `scripts/b23l1-dual-instance.ps1:401` | `Start-Sleep -Milliseconds 800` | 800 ms | 健康等待轮询节拍 | 硬编码 | 裸数字 | 120s/0.8s = 150 次；建议 1–2s | — |
| N-OPS-139 | 超时 | `scripts/b23l1-dual-instance.ps1:188` `.ps1:206` | `.Wait(300)` / `.Wait(1000)` | 300 ms / 1000 ms | 端口占用探测 / Redis 可达探测 | 硬编码 | 裸数字 | 本机 TCP 建连毫秒级；300ms 在负载下可能误判「端口空闲」；建议 1–2s | — |
| N-OPS-140 | 轮询 | `scripts/b23l1-dual-instance.ps1:500` | `Start-Sleep -Milliseconds 100` | 100 ms | 等 B 侧订阅建立（哨兵轮询） | 硬编码 | 部分（注释：连接建立才继续） | 100ms × 30s = 300 次；可接受，建议注明 | — |
| N-OPS-141 | 超时 | `scripts/b23l1-dual-instance.ps1:536` `.ps1:777` | `FromSeconds(60)` / `FromMinutes(2)` | 60 s / 120 s | 后台作业内探针 / 唤醒作业的 HTTP 上限 | 硬编码 | 裸数字 | 两值与 133 的 3 min 三套并存；建议统一 | — |
| N-OPS-142 | 超时 | `scripts/b23l1-dual-instance.ps1:842` `.ps1:918` `.ps1:1129` `.ps1:1463` | `Wait-Job -Timeout 90` / `60` | 90 s / 60 s ×3 | PowerShell 后台作业收尾等待 | 硬编码 | 裸数字 | 后台作业崩溃时才会等满；建议失败时打印作业状态 | — |
| N-OPS-143 | 超时 | `scripts/b23l1-dual-instance.ps1:962` | `[int]$timeoutSec = 120` | 120 s | 全量重放到终态等待 | 硬编码（参数默认值） | 裸数字 | 同 137 | — |
| N-OPS-144 | 超时 | `scripts/b23l1-dual-instance.ps1:575` | `$client.ReceiveTimeout = 2000` | 2000 ms | 裸 TCP Redis 客户端读超时（写锁预置/取证） | 硬编码 | 裸数字 | 与产品 Redis 2s 命令超时同值（巧合还是同源未注）；建议注明 | — |
| N-OPS-145 | 重试 | `scripts/b23l1-dual-instance.ps1:622-626` | `$timeoutSec = 10` + `Start-Sleep -Milliseconds 300` | 10 s / 300 ms | 等 Redis 键消失（销毁/锁释放） | 硬编码（参数默认值） | 裸数字 | 10 s 远大于单键 DEL；建议降到 2–3 s | — |
| N-OPS-146 | 等待 | `scripts/b23l1-dual-instance.ps1:1432` `.ps1:1435` `.ps1:1439` | `Sleep 12` + `PTTL < 22000` + `Sleep 7` | 12 s / 22 s / 7 s | 租约续租证据：等待 12s 后 PTTL 应 >22s | 硬编码 | 部分（注释：每 10s 续租一次，睡 12s 后应回到 ~28s） | 22s 阈值来自「12s lease + 续租」，建议写成 `ttl - 2s` 派生式 | — |
| N-OPS-147 | 轮询 | `scripts/b23l1-dual-instance.ps1:1455` `.ps1:1459` | `AddSeconds(KillTakeoverTimeoutSec)` + `Sleep 1` | 90 s / 1 s | 并发 resume 观测「恰一个 202 + 一个 409」 | 硬编码（派生 + 1s） | 部分（注释说明预算来源） | 1 s 节拍配 90 s 窗；建议改为事件读 `/runs/current` | — |
| N-OPS-148 | 端口 | `scripts/m2t2a-smoke.mjs:65` `.mjs:160` | `SMOKE_CDP_PORT ?? 9333` + `--window-size=1400,1800` | 9333 / 1400×1800 px | CDP 调试端口与浏览器窗口 | 配置（env） | 裸数字 | 与 chat-panel-smoke 的 9334 错开；建议端口段登记（93xx） | S-33 |
| N-OPS-149 | 阈值 | `scripts/m2t2a-smoke.mjs:143` | `FRAME_TIMEOUT_SECONDS ?? 120` | 120 s | 合成挂起帧的 timeoutSeconds / expiresAt | 配置（env） | 有（注释：不再裸写 120，与 `app.ai.hitl.frontend-tool-timeout` 同值但可覆盖） | 从「写死 120」改为可覆盖，方向正确；仍应改为读 health | S-31 |
| N-OPS-150 | 轮询 | `scripts/m2t2a-smoke.mjs:147-148` | `CONVERGE_POLLS = 12` × `500 ms` | 12 次 / 6 s 上限 | 取消后收敛轮询 | 硬编码 | 有（注释：与挂起超时无语义关系） | 6 s 上限对「取消收敛」偏紧；建议按事件（cancelled 帧）等待 | S-31 |
| N-OPS-151 | 超时 | `scripts/m2t2a-smoke.mjs:289` | `FALLBACK_AWAIT_MS = 330_000` | 330 s | 等服务端轮次预算的回落值（total-budget 300s + 30s） | 硬编码（派生） | 有（注释：原 240s 大于后端挂起上限 ⇒ 恒真断言；改为事件驱动） | 330s 是「解析失败」的兜底；建议解析失败直接 FAIL（而非等 330s） | S-30 |
| N-OPS-152 | 超时 | `scripts/m2t2a-smoke.mjs:317` | `budgetMs + 30_000` | 服务端预算 + 30 s | 由 `/api/ai/health` 的 resilience.totalBudget 派生硬上限 | 配置（服务端值） | 有（注释：余量 = 一个心跳周期 + 抖动） | 正面案例；建议把「+30s」也标注为心跳周期派生 | — |
| N-OPS-153 | 超时 | `scripts/m2t2a-smoke.mjs:528` | `Date.now() + 150_000` | 150 s | 读 frontend_tool_result 与 done 的尾段窗口 | 硬编码 | 裸数字 | 150 s 与 151 的 330s 两套；建议统一到派生上限 | S-30 |
| N-OPS-154 | 等待 | `scripts/m2t2a-smoke.mjs:172` `.mjs:212` `.mjs:244` `.mjs:374` `.mjs:469` `.mjs:504` `.mjs:513` `.mjs:583` | `sleep 500/500/400/600/500/500/1500/1500` | 0.4–1.5 s | CDP 操作与页面状态等待 | 硬编码 | 裸数字 | 8 处固定等待；建议以 DOM/store 条件轮询替代 | S-32 |
| N-OPS-155 | 端口 | `scripts/chat-panel-smoke.mjs:121` `.mjs:130` `.mjs:196` | `SMOKE_CDP_PORT ?? 9334` + `PROTECTED_PORTS = {8081,5202,6379}` + `--window-size=1400,1800` | 9334 / 3 个保护端口 / 1400×1800 px | CDP 端口、「拒杀常开服务」硬闸、浏览器窗口 | 配置（env）+ 硬编码 | 有（注释：8081/5202/6379 即使授权也拒杀） | 正面案例（安全闸）；建议把保护端口清单从 README 同步为单一常量 | — |
| N-OPS-156 | 超时 | `scripts/chat-panel-smoke.mjs:133` | `CLIPBOARD_READ_TIMEOUT_MS ?? 5000` | 5000 ms | 剪贴板回读等待上界（超时降级 SKIP） | 配置（env） | 部分（注释：取不到即如实 SKIP） | 5 s 是权限提示的人工窗口；建议注明「无权限环境会恒 SKIP」 | — |
| N-OPS-157 | 等待 | `scripts/chat-panel-smoke.mjs:116` | `RLD_FRAME_GAP_MS ?? 2500` | 2500 ms | 帧间静默注入值（刷新恢复用例） | 配置（env） | 部分（与产品帧间看门狗相关） | 2.5 s 需 < 产品 inter-event 90s 且 > 心跳 2s；建议注释该区间 | — |
| N-OPS-158 | 轮询 | `scripts/chat-panel-smoke.mjs:378` | `{ tries = 24, intervalMs = 250 }` | 24 次 × 250 ms = 6 s | `waitFor` 缺省上界 | 硬编码 | 部分（注释：默认 6s 上界，每 250ms 采样） | 6 s 对模型轮偏短（故各调用点另传更大 tries）；建议把缺省改为「按场景必传」 | — |
| N-OPS-159 | 轮询 | `scripts/chat-panel-smoke.mjs:394` `.mjs:415` `.mjs:445` `.mjs:520` `.mjs:730` `.mjs:1169` `.mjs:1181` `.mjs:1226` `.mjs:1496` | `tries 60×200` / `100×100` / `120×100` / `20×200` / `20×250` / `150×100` / `60×250` / `120×100` / `100×100` | 4–15 s | 各断言的等待上界（文本轮、流中断、复制反馈、历史持有等） | 硬编码 | 裸数字 | 9 组不同的「tries × interval」；建议按语义分三档（UI 即时 / 网络 / 模型轮） | — |
| N-OPS-160 | 等待 | `scripts/chat-panel-smoke.mjs:215` `.mjs:261` `.mjs:285` `.mjs:431` `.mjs:565` `.mjs:618` `.mjs:632` `.mjs:649` `.mjs:654` `.mjs:777` `.mjs:810` `.mjs:840` `.mjs:856` `.mjs:1111` `.mjs:1125` `.mjs:1172` `.mjs:1215` `.mjs:1314` `.mjs:1417` `.mjs:1461` `.mjs:1541` `.mjs:1623` `.mjs:1665` | `sleep 100–1500 ms` | 0.1–1.5 s ×23 处 | CDP 点击/滚动/复制的固定等待 | 硬编码 | 裸数字 | 23 处固定等待是最大脆弱面；建议逐处改为 `waitFor` 条件 | — |

---

## 5. Redis 5.0 集群冒烟与三实例集群网

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-161 | 并发/池 | `scripts/redis5-cluster-smoke.sh:457` `.sh:523` | `for i in 1..6` + `--cluster-replicas 1` | 6 节点（3 主 3 从） | 目标生产拓扑规模 | 硬编码 | 有（设计依据 §十；拓扑正是「3 主 3 从」） | 与生产一致，属规格而非魔数；建议注释「不一致即不覆盖生产拓扑」 | — |
| N-OPS-162 | 等待 | `scripts/redis5-cluster-smoke.sh:471` | `--cluster-node-timeout 5000` | 5000 ms | 节点失联判定（故障注入用例的节拍基准） | 硬编码 | 部分（5s 是 Redis 默认；本脚本显式写） | 5s 决定 failover 观测窗（见 169），建议注明两者关系 | — |
| N-OPS-163 | 超时 | `scripts/redis5-cluster-smoke.sh:204` | `with_timeout 10` | 10 s | 死节点探针命令上限（rc=124 为强证据） | 硬编码 | 有（注释：rc=124 + 空输出为弱证据、另列） | 10 s > node-timeout 5s，能覆盖判死；建议注释该不等式 | — |
| N-OPS-164 | 轮询 | `scripts/redis5-cluster-smoke.sh:480-485` | `seq 1 30` + `sleep 1` | 30 s | 6 容器启动就绪等待 | 硬编码 | 裸数字 | 容器冷启 <5s；30s 偏宽但只影响失败时；可保留 | — |
| N-OPS-165 | 超时 | `scripts/redis5-cluster-smoke.sh:521-527` | `with_timeout 240` | 240 s | `--cluster create` 总上限（5.0.14 无内部超时） | 硬编码 | 有（注释：避免白等 workflow 25 分钟） | 240 s 与 workflow 25 min 的关系已注明；建议同时打印已耗时 | — |
| N-OPS-166 | 轮询 | `scripts/redis5-cluster-smoke.sh:535-542` | `seq 1 90` + `sleep 1` | 90 s | 等 16384 槽分配完成 + cluster ok | 硬编码 | 部分（判据明确，90s 无论证） | 槽分配通常秒级；90s 是「慢 CI」余量，建议按实测收紧 | — |
| N-OPS-167 | 轮询 | `scripts/redis5-cluster-smoke.sh:555-563` | `seq 1 60` + `sleep 1` | 60 s | 等 3 从节点识别 | 硬编码 | 裸数字 | 从节点握手与 166 同一时刻完成；建议合并为一个就绪判定 | — |
| N-OPS-168 | 轮询 | `scripts/redis5-cluster-smoke.sh:776-781` `.sh:796-802` | `seq 1 20` + `sleep 0.5` | 10 s ×2 | 等 SUBSCRIBE 订阅建立（两处） | 硬编码 | 部分（订阅建立无 ACK，只能等） | 10 s 是经验值；建议注释「无 ACK 故用固定窗」 | — |
| N-OPS-169 | 等待 | `scripts/redis5-cluster-smoke.sh:945-948` | `seq 1 25` + `sleep 0.2` | 5 s | 等从节点升主（failover 观测） | 硬编码 | 部分（5s ≈ node-timeout） | 与 162 同量级、恰为临界；建议窗口取 2×node-timeout | — |
| N-OPS-170 | 轮询 | `scripts/redis5-cluster-smoke.sh:1018-1030` | `seq 1 60` + `sleep 2` | 120 s | 等节点恢复/集群回到 ok | 硬编码 | 裸数字 | 120 s 是长尾；建议失败时打印 CLUSTER INFO 以区分「未恢复」与「判据错」 | — |
| N-OPS-171 | 重试 | `scripts/redis5-cluster-smoke.sh:662` | `for i in 1..5` | 5 次 | 两键异槽的有限重试（1/16384 撞槽概率） | 硬编码 | 有（注释：撞槽概率 1/16384，改用有限重试避免随机红） | 正面案例（把随机性转为有限重试）；建议把概率写进注释 | — |
| N-OPS-172 | 端口 | `scripts/ci/cluster3-run.sh:39-46` | `23179/23180/23181/23182/23183/23189/23190/23199` | 231xx 段 8 个端口 | 三实例集群网的端口规划 | 硬编码 | 有（注释：与产品默认/前端端口无交集；`--self-check` 断言 231xx） | 端口规划清晰；建议把该表登记进统一端口表 | — |
| N-OPS-173 | 并发/池 | `scripts/ci/cluster3-run.sh:90` | `maxconn 4096` | 4096 连接 | HAProxy 全局最大连接 | 硬编码 | 裸数字 | 单机 3 实例远达不到；可降到 512 并注释 | — |
| N-OPS-174 | 超时 | `scripts/ci/cluster3-run.sh:99-104` | `connect 3s` / `client 300s` / `server 310s` / `http-request 15s` / `http-keep-alive 30s` / `check 5s` | 3/300/310/15/30/5 s | HAProxy 各阶段超时 | 硬编码 | 部分（server 310 = client 300 + 10，暗示 SSE 长连；其余无论证） | `client 300s` 与产品 total-budget 300s 同值（巧合同值）；建议注释 SSE 通道的配套关系 | — |
| N-OPS-175 | 阈值 | `scripts/ci/cluster3-run.sh:105` | `inter 2s fastinter 500ms downinter 2s fall 2 rise 2` | 2s/0.5s/2s/2/2 | HAProxy readiness 探测节拍与阈值 | 硬编码 | 部分（drain-window 5s 的注释引用了「inter 2s × fall 2」） | 该组数与 drain-window 5s 有推导关系；建议在 drain-window 注释处反向引用本行 | — |
| N-OPS-176 | 轮询 | `scripts/ci/cluster3-run.sh:344-349` `.sh:365-370` `.sh:392-397` | `seq 1 60` + `sleep 0.5` | 30 s ×3（桩 / Redis / HAProxy） | 三件基础设施就绪等待 | 硬编码 | 部分（失败文案写明「30s 内未就绪」） | 三处复制同一模式；建议抽 `wait_until` 函数并注明 30s 依据 | — |
| N-OPS-177 | 超时 | `scripts/ci/cluster3-run.sh:345` `.sh:393` | `curl -fsS -m 2` | 2 s | 桩 / HAProxy stats 探活单次上限 | 硬编码 | 裸数字 | 同 014 | — |
| N-OPS-178 | 超时 | `scripts/ci/cluster3-run.sh:489-491` | `--ready-timeout 180 --job-fail-timeout 130 --kill-takeover-timeout 120` | 180 s / 130 s / 120 s | 探针三类等待窗（秒） | 硬编码 | 部分（探针默认 180/90/120，此处 job-fail 抬到 130） | 130 与探针默认 90 不一致；建议注释「为何 +40s」（看门狗判死窗） | — |
| N-OPS-179 | 端口 | `scripts/ci/cluster3-probe.mjs:110-119` `.mjs:6659` | `PORTS = {redis:23179, proxy:23180, a/b/c:23181-83, stats:23189, h2:23190, stub:23199}` + 自检 `23100 ≤ p ≤ 23199` | 8 端口 + 段校验 | 探针端口常量与段约束 | 硬编码 | 有（自检断言端口落在 231xx 段） | 与 172 双写（run.sh 与 probe 各一套）；建议单一取数点 | — |
| N-OPS-180 | 超时 | `scripts/ci/cluster3-probe.mjs:303-304` | `readyTimeoutMs: 180000` / `jobFailTimeoutMs: 90000` | 180 s / 90 s | 探针缺省：就绪等待 / 作业判死等待 | 硬编码 | 部分（job-fail 90s 与产品看门狗判死窗同源） | 与 178 的 130 形成三值；建议明确「默认值 vs workflow 覆写」的语义 | — |
| N-OPS-181 | 超时 | `scripts/ci/cluster3-probe.mjs:305` `.mjs:326` | `killTakeoverTimeoutMs: 120000` | 120 s | 杀实例后接管等待 | 硬编码 | 部分（同 136 语义） | 与 b23l1 的 90s 不一致；建议统一「接管窗」口径 | — |
| N-OPS-182 | 超时 | `scripts/ci/cluster3-probe.mjs:2290` | `PARK_SETTLE_TIMEOUT_MS = 20000` | 20 s | 前端工具 park 收流等待（多处置信） | 硬编码 | 裸数字 | 20 s 决定「park 是否收流」判定；建议与产品前端工具预算 120s 的关系注明 | — |
| N-OPS-183 | 超时 | `scripts/ci/cluster3-probe.mjs:4636` | `R1_REPLAY_OBSERVE_MS = 10000` | 10 s | 取消态 UNKNOWN 后重试观察窗（覆盖前 2 次重试） | 硬编码 | 有（注释：覆盖 2s/6s/14s 重试中的前两次） | 推导清晰；建议写成「前两次重试时刻 + 余量」 | — |
| N-OPS-184 | 重试 | `scripts/ci/cluster3-probe.mjs:1983` | `SESSION_ID_MAX_ATTEMPTS = 3` | 3 次 | 会话号撞号 503 的重取上限 | 硬编码 | 有（自检 :7252 断言 =3） | 正面案例（自检锁常量）；建议保留 | — |
| N-OPS-185 | 重试 | `scripts/ci/cluster3-probe.mjs:3994-4006` | `attempt < 5` + `sleep 5000` | 5 次 / 25 s | 僵尸扫描接管重试（startup-resume/scan） | 硬编码 | 部分（文案写明 5 次） | 5×5s 与产品 zombie-age 30s 相关；建议注释该关系 | — |
| N-OPS-186 | 阈值 | `scripts/ci/cluster3-probe.mjs:1628` `.mjs:4512` | `defs.length >= 15` | 15 条 | 种子就绪判据（三实例各自冷启） | 硬编码 | 有（同 022） | 同 022 | — |
| N-OPS-187 | 阈值 | `scripts/ci/cluster3-probe.mjs:221-243` | `batch-size=1`、`demo-batch-delay-ms=1200`、`heartbeat-interval=5s`、`scan-period=3s`、`dead-multiplier=3`、`max-execution-time=45s`、`limit 500/5000`、`startup-delay=3s`、`zombie-age=30s`、`zombie-margin=5s` | 10 组产品键覆写 | 三实例统一启动参数（为 CI 时间预算收紧） | 硬编码（命令行覆写） | 部分（注释：数值是「合法配置值」不是绕过，租约/僵尸阈值仍满足启动校验） | 10 个键共同决定用例时长；建议逐个标注「与产品缺省的倍数」 | — |
| N-OPS-188 | 超时 | `scripts/ci/cluster3-probe.mjs:221-243` | `tool-exec-timeout=20s`、`first-byte=15s`、`inter-event=20s`、`lease.ttl=12s`、`renew=4s`、`retry-delay=17s` | 20/15/20/12/4/17 s | 三实例的流式/租约时间参数 | 硬编码（覆写） | 部分（同 187） | lease 12s + renew 4s + retry 17s 三者关系（12 ≈ 3×4；17 > 12+4）建议写注释 | — |
| N-OPS-189 | 超时 | `scripts/ci/cluster3-probe.mjs:1080` `.mjs:1111` `.mjs:1125` `.mjs:1415` `.mjs:1435` `.mjs:1528` `.mjs:1544` `.mjs:1617` `.mjs:1665` `.mjs:1766` `.mjs:1874` `.mjs:1884` | `waitFor 60s`、`waitTerminal 90s`、`waitClosed 30s`、`waitPortClosed 15s`、`waitTcpOpen 30s`、`health/readiness 4s`、`waitSeeded 60s`、`stopInstance 20s`、`waitH2Ready 30s`、`getJson 15s`、`postJson 20s` | 4–90 s（12 个缺省） | 探针各类等待的默认上界 | 硬编码 | 部分（部分与产品窗同源，多数裸） | 一套脚本内 12 个不同的等待缺省；建议归并为「启动 / 请求 / 终态 / 收流」四档 | — |
| N-OPS-190 | 缓冲 | `scripts/ci/cluster3-probe.mjs:1282` | `maxBuffer = 64 * 1024 * 1024` | 64 MiB | docker CLI 子进程输出缓冲 | 硬编码 | 裸数字 | 同 085 | — |
| N-OPS-191 | 轮询 | `scripts/ci/cluster3-probe.mjs:1607` `.mjs:1632` `.mjs:1660` `.mjs:2167` `.mjs:2256` `.mjs:2273` `.mjs:2772` `.mjs:2991` `.mjs:3033` | `sleep(500)` / `sleep(250)` | 0.25–0.5 s ×9 | 各轮询节拍 | 硬编码 | 裸数字 | 500ms 节拍配 180s 窗 = 360 次；建议注释采样成本 | — |

---

## 6. `scripts/githooks/**`（脱敏扫描）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-192 | 阈值 | `scripts/githooks/scan-lib.sh:67-86` | `{20,}`、`{30,}`、`{16}`、`{36}`、`{10,}`、`{12,}`、`{13,}`、`{5,}` 等 20 组 | 各 token 最小长度（如 sk- ≥20、ghp_ ≥30、AKIA 恰 16、npm_ 恰 36） | 密钥前缀后的长度阈值（降低误报） | 硬编码 | 部分（多数由真实 token 规格决定＝外部事实，但仓内未逐条注明来源） | 建议对每个前缀附「规格来源」一行注释；否则调整时无从判断 | S-27 |
| N-OPS-193 | 阈值 | `scripts/githooks/scan-lib.sh:91-92` | `[A-Za-z]:[\\/][-A-Za-z0-9_.\\/]{1,60}` | 60 字符 | 盘符路径命中长度上限（与 check-docs 同构） | 硬编码 | 部分（与 check-docs.mjs:92-93 逐字同构） | 两处同值不同文件；建议由 scan-lib 单一取数点导出 | S-28 |
| N-OPS-194 | 缓冲 | `scripts/githooks/scan-lib.sh:186` | `if (length(text) > 4000) next` | 4000 字符/行 | 超长行整行跳过（防压缩包/单行大 JSON 拖慢） | 硬编码 | 裸数字 | 超长行恰是密钥可能藏身处（如单行 JSON）；建议改为「截断后扫」而非「跳过」 | S-28 |
| N-OPS-195 | 阈值 | `scripts/githooks/scan-lib.sh:187` | `gsub(/<[^>]{1,80}>/,"")` | 80 字符 | 占位符剔除长度上限 | 硬编码 | 部分（与 check-docs.mjs:108 同值） | 同 089 | S-28 |
| N-OPS-196 | 阈值 | `scripts/githooks/scan-lib.sh:121` `.sh:50` | `[ ${#_part} -lt 4 ] && continue` + 通用名清单 20 个 | 4 字符 / 20 个通用名 | USER 规则：user.name 拆解片段最小长度与豁免名单 | 硬编码 | 部分（注释：避免误伤；清单长度无依据） | 4 字符会漏掉 3 字母用户名；建议说明取舍 | — |
| N-OPS-197 | 缓冲 | `scripts/githooks/scan-history.sh:21` | `max_size_kb=2048` | 2048 KB/blob | 跳过超大 blob 的阈值（可 `--max-size` 覆写） | 配置（参数缺省） | 部分（:13 用法示例给 `--max-size 512`，说明可调；2048 无论证） | 2048 KB 会跳过生成物与大数据 fixture；建议注明「被跳过的 blob 不参与扫描」的风险 | S-29 |
| N-OPS-198 | 并发/池 | `scripts/githooks/scan-history.sh:145-151` | `nproc` else `4`；`>8` 截到 `8` | 1–8 并行 | 历史 blob 并行扫描 worker 数 | 硬编码 | 部分（注释：并行与串行同构；上限 8 无依据） | 上限 8 是「别打爆磁盘」的经验值；建议与 CI runner 核数（2–4）对齐说明 | — |
| N-OPS-199 | 阈值 | `scripts/githooks/scan-history.sh:135` `.sh:86` `.sh:94` | `od -An -c -N 2048` / `substr(b,1,8)` / `substr($0,1,40)` | 2048 字节 / 8 字符 / 40 字符 | 未知扩展名的二进制探测长度 / blob 短 sha / 完整 sha | 硬编码 | 部分（2048 是「前 2KB 含 NUL 即二进制」的常用判据；sha 长度是 git 规格） | 2048 建议注释为「探测窗」；sha 长度属 git 规格，无需改 | — |

---

## 7. 部署 / 运行配置示例（profile 与 .env 示例）

> 无 Dockerfile / docker-compose / nginx 配置文件（全仓检索 0 命中，`backend/target/site/jacoco` 的命中是覆盖率报告目录名）。部署面只由三份 Spring profile、`.env.example` 与 CI/集群脚本承载。

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-200 | 重试 | `backend/src/main/resources/application-cluster.yml:31` | `max-redirects: 3` | 3 跳 | Redis Cluster MOVED/ASK 重定向上限（resharding 期间） | 配置 | 部分（注释说明用途，3 无论证） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】与 Lettuce/常见客户端默认（5）不同；建议注明「3 跳已覆盖一次迁移」 | — |
| N-OPS-201 | 超时 | `backend/src/main/resources/application-cluster.yml:33-36` | `timeout: 2s` / `connect-timeout: 2s` / `shutdown-timeout: 200ms` | 2s / 2s / 200ms | cluster profile 的 Redis 超时（与 standalone 同口径） | 配置 | 有（注释引 R4 裁决：2s 是「Redis 不可用 ⇒ 503 ≤2s」的前提）；200ms 部分 | 【跨册交叉引用、非独立项：与 backend-business 分册重复】主表已登记；此处仅登记 cluster 副本，建议保持与主表同源、勿各自演进 | — |
| N-OPS-202 | 阈值 | `backend/src/main/resources/application-cluster.yml:44` | `drain-window: 5s` | 5 s | readiness 摘流窗口（≥ LB 探测 2s×2 + 余量） | 配置 | 有（注释含不等式与上界 60s） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】推导完整；建议把「上界 60s 校验」的错误文案也写在本行旁 | — |
| N-OPS-203 | 超时 | `backend/src/main/resources/application-h2tcp.yml:29` | `LOCK_TIMEOUT=60000` + 默认 `tcpPort 9092` | 60 s / 9092 | 共享 H2 TCP 库的行锁等待与默认端口 | 配置（URL 内字面量） | 部分（注释：种子单飞等待方需等完整一轮，H2 缺省 1s 不够） | 【跨册交叉引用：`LOCK_TIMEOUT=60000` 与 backend-business 分册重复；**`tcpPort 9092` 默认值为本册独立增量**，保留计数】60000 写在 URL 字符串里，env 无法覆盖；建议提到独立键或注明「改需改 URL」 | — |
| N-OPS-204 | 并发/池 | `backend/src/main/resources/application-h2tcp.yml:35-36` | `maximum-pool-size: 10` / `connection-timeout: 5000` | 10 连接 / 5000 ms | 三实例共享 H2 的连接池 | 配置 | 有（注释：三实例=30 连接，H2 无上限；与默认口径一致） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】池 10 与 PG profile 同值（Hikari 默认 10）；建议注明「实例数×10 须与库侧核对」 | — |
| N-OPS-205 | 阈值 | `backend/src/main/resources/application-h2tcp.yml:54` | `drain-window: 5s` | 5 s | 共享库形态的摘流窗口 | 配置 | 有（注释：5s ≥ HAProxy inter 2s × fall 2 + 余量） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】与 202 同值同源；建议抽公共锚点 | — |
| N-OPS-206 | 并发/池 | `backend/src/main/resources/application-postgresql.yml:25-29` | `maximum-pool-size: 10` / `connection-timeout: 5000` / `validation-timeout: 2000` | 10 / 5000 / 2000 ms | 生产 PG 连接池与取连接上限 | 配置 | 有（注释：池上限取 Hikari 官方默认 10；5s 是为让探针在 LB 超时内给出结论） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】「实例数×10」与库 max_connections 的核对责任建议写明运维侧 | — |
| N-OPS-207 | 超时 | `backend/src/main/resources/application-postgresql.yml:36` | `SET lock_timeout = '60s'` | 60 s | 会话级锁等待上限（与 H2 LOCK_TIMEOUT 同一上界） | 配置 | 有（注释：不设 statement_timeout，避免误杀长作业） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】60s 与 203 同源；建议注明「两 profile 必须同值」 | — |
| N-OPS-208 | 超时 | `backend/src/main/resources/application-postgresql.yml:61` | `timeout-per-shutdown-phase: 20s` | 20 s | 优雅停机在途请求收尾上限 | 配置 | 部分（注释：与 server.shutdown=graceful 配套，20s 无论证） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】20s 应 ≥ 最长在途请求（SSE 可 300s）；建议复核：graceful 停机可能仍截断长流 | — |
| N-OPS-209 | 阈值 | `backend/src/main/resources/application-postgresql.yml:76` | `drain-window: 5s` | 5 s | PG profile 的摘流窗口 | 配置 | 有（同 202） | 【跨册交叉引用、非独立项：与 backend-business 分册重复】同 202 | — |
| N-OPS-210 | 超时/缓存 | `backend/config/application.yml.example:27-29` | `probe-timeout: 5s` / `models-cache-ttl: 10m` | 5 s / 10 min | 模型配置探测超时与远端模型清单缓存 | 配置（示例，注释态） | 部分（注释只说明用途） | 示例里是**注释掉**的，易被误认为现状；建议标注「非生效值」 | — |
| N-OPS-211 | 端口 | `frontend/.env.example:4` `.env.example:7` | `VITE_API_BASE=http://localhost:8081` / `VITE_DEV_PORT=5200` | 8081 / 5200 | 前端 dev 代理目标与 dev server 端口缺省 | 配置（示例） | 部分（与 README 口径一致；实际本地 `.env.local` 常用 5202/18318，未读） | 【与 N-FE-170/171 同源交叉引用（同一文件同一对值），按跨册同源计入非独立项；本行只登记前端侧落点】示例值与「常开服务」8081/5200 一致，但本地实际多为 5202/18318；建议示例注两套口径的用途 | — |

---

## 8. `spike/**`（试验工程，非产品路径）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-212 | 端口/池 | `spike/sp01ab/src/main/resources/application.yml:2` `.yml:6` | `${SPIKE_PORT:18301}` / `tomcat.threads.max: 40` | 18301 / 40 线程 | 试验实例端口与 Tomcat 线程上限 | 配置 | 裸数字 | spike 是证据工程；建议在其 README/决策卡登记「40 线程」的取因 | — |
| N-OPS-213 | 超时 | `spike/sp01ab/src/main/resources/application.yml:22-26` | `frontend-tool-timeout-seconds: 60` / `confirm-timeout-seconds: 30` / `heartbeat-ms: 2000` | 60 s / 30 s / 2000 ms | 前端工具挂起上限 / 确认门上限 / SSE 心跳 | 配置 | 部分（注释说明语义） | 与产品现值（120s / 无上限 / 2s）不同，是**试验取值**；建议注明「勿当产品口径引用」 | — |
| N-OPS-214 | 并发/池 | `spike/sp01ab/src/main/java/com/example/spike/config/SpikeConfig.java:86` `spike/sp01ab/src/main/java/com/example/spike/ai/SpikeToolCallingManager.java:275` | `executor.setMaxPoolSize(8)` / `length() > 800 ? substring(0,800)` | 8 线程 / 800 字符 | 工具调用池容量 / 工具结果截断 | 硬编码 | 裸数字 | 800 字符截断会改变模型可见内容；建议注明只用于样本压缩 | — |
| N-OPS-215 | 端口/超时 | `spike/sp01c/src/main/resources/application.yml:2` `.yml:12` | `${SPIKE_PORT:18302}` / `spring.data.redis.timeout: 3s` | 18302 / 3 s | 试验实例端口与 Redis 命令超时 | 配置 | 裸数字 | 3s 与产品现态 2s 不同；建议注明试验基线 | — |
| N-OPS-216 | TTL/缓冲 | `spike/sp01c/src/main/resources/application.yml:23-24` | `ttl: 6h` / `max-messages: 40` | 6 h / 40 条 | 会话记忆 TTL 与窗口 | 配置 | 部分（决策卡提「生产建议」，ttl 6h 为当时值） | 产品现为 24h；spike 值已过时，建议标注「历史试验值」 | — |
| N-OPS-217 | 缓冲 | `spike/sp01c/src/main/java/com/example/spike/config/UpstreamHttpLoggingConfig.java:40` | `MAX_BODY_CHARS = 4000` | 4000 字符 | 上游请求/响应体日志截断 | 硬编码 | 裸数字 | 取证需要完整体时会被截；建议注明截断标记（已加 `...[truncated]`） | — |
| N-OPS-218 | 端口/池 | `spike/sp01d/src/main/resources/application.yml:2` `.yml:6` | `${SPIKE_PORT:18303}` / `tomcat.threads.max: 40` | 18303 / 40 | 试验实例端口与线程 | 配置 | 裸数字 | 同 212 | — |
| N-OPS-219 | 超时/重试 | `spike/sp01d/src/main/resources/application.yml:23-26` | `first-event-timeout: 5s` / `inter-event-timeout: 6s` / `max-attempts: 2` / `total-budget: 20s` | 5s / 6s / 2 / 20s | 流式韧性四参数（缩短实验时长） | 配置 | 有（注释：生产取值见决策卡，spike 为缩短实验改小并已标注偏差） | 明确标注了「非生产值」，属正面做法；建议保留 | — |
| N-OPS-220 | 缓冲/池 | `spike/sp01d/src/main/resources/application.yml:28` `.yml:30` | `chat-memory.max-messages: 40` / `executor.threads: 8` | 40 条 / 8 线程 | 记忆窗口与执行池 | 配置 | 裸数字 | 同 216 | — |
| N-OPS-221 | 端口 | `spike/sp02/src/main/resources/application.yml:2` | `${SP02_PORT:18306}` | 18306 | 试验实例端口 | 配置 | 裸数字 | 同 212 | — |
| N-OPS-222 | 超时 | `spike/sp02/src/main/resources/application.yml:23-25` | `frontend-tool-timeout-seconds: 120` / `confirm-timeout-seconds: 120` | 120 s / 120 s | 阻塞式挂起上限 / 确认门上限 | 配置 | 部分（注释：阻塞式形态，挂起态外置 Redis） | 与产品「确认门无上限」不同；建议注明这是被证伪的旧形态 | — |
| N-OPS-223 | TTL/重试/池 | `spike/sp02/src/main/resources/application.yml:29-35` | `run-ttl-hours: 6` / `max-rounds: 5` / `suspend-pool-size: 16` | 6 h / 5 轮 / 16 线程 | 挂起态 TTL / 续跑轮次上限 / 承载池 | 配置 | 部分（注释：DC-12 有界池；5 轮与 16 无论证） | 5 轮是「续跑显式循环」的安全阀；建议注明超限行为（快速失败） | — |
| N-OPS-224 | 超时/池 | `spike/sp02/src/main/java/com/example/spike/config/ExecutorConfig.java:22` | `new ThreadPoolExecutor(0, suspendPoolSize, 60L, SECONDS, SynchronousQueue, AbortPolicy)` | 60 s keep-alive / 队列 0 | 挂起池：核心 0、最大 16、无队列、饱和即拒 | 硬编码 | 部分（DC-12 口径「平台线程 + 有界池，超限快速失败」；60s 无依据） | 与产品 suspend.pool-size 20 不同；建议注明试验值 | — |
| N-OPS-225 | 超时 | `spike/sp01ab/scripts/sp01ab-verify.mjs:165` `.mjs:182` `.mjs:186` `.mjs:217` `.mjs:227` `.mjs:231` `.mjs:254` `.mjs:264` `.mjs:268` `.mjs:293` `.mjs:298` | `45000` / `30000` / `90000` / `(timeout+40)*1000` | 30–90 s | 等前端工具请求 / 决策 / done 帧的事件窗 | 硬编码 | 裸数字 | 11 处事件窗；建议按「AI 轮次预算」派生 | — |
| N-OPS-226 | 轮询/超时 | `spike/sp02/scripts/sp02-experiment.mjs:82` `.mjs:177` `.mjs:89` `.mjs:184` | `waitUp 90000` / `waitForEvent 120000` / `sleep 400` / `sleep 250` | 90 s / 120 s / 0.4 s / 0.25 s | 等实例起 / 等事件 / 轮询节拍 | 硬编码 | 裸数字 | 试验脚本，可保留；建议失败时打印最后事件 | — |
| N-OPS-227 | 超时 | `spike/sp01c/scripts/spike-verify.mjs:68` `.mjs:162` | `timeout: 15000` / `sleep(8000)` | 15 s / 8 s | Redis CLI 子进程超时 / 短 TTL 到期等待 | 硬编码 | 裸数字 | 8 s 是「短 TTL 实验」的等待；建议与覆盖后的 TTL 关联 | — |
| N-OPS-228 | 等待 | `spike/sp01d/scripts/fault-proxy.mjs:30` `.mjs:35` `.mjs:76` | `--port 18304` / `stallMs: 8000` / `closeAllConnections` 延迟 250 ms | 18304 / 8 s / 250 ms | 故障代理端口、默认静默、断连延迟 | 配置（CLI）+ 硬编码 | 裸数字 | 250ms 断连延迟影响「截断」证据形态；建议注明 | — |
| N-OPS-229 | 等待 | `spike/sp01d/scripts/spike-verify.mjs:155` `.mjs:158` `.mjs:161` `.mjs:165` `.mjs:181` `.mjs:184` `.mjs:197` `.mjs:200` `.mjs:251` `.mjs:255` `.mjs:270` | `stallMs 180000/9000/12000/500`、`clientAbortMs 15000/30000/40000/60000` | 0.5–180 s | 故障注入静默值与客户端主动断开时刻 | 硬编码 | 部分（注释：180000 = 两分钟内不回任何字节） | 13 组手工时刻；试验脚本可接受，建议表格式集中 | — |
| N-OPS-230 | 超时 | `spike/sp01d/scripts/run-all.sh:6-7` | `timeout 150` + `sleep 1` | 150 s / 1 s | 单实验外壳超时与串行间隔 | 硬编码 | 裸数字 | 150 s 与单实验 90s 事件窗相关；建议注释 | — |
| N-OPS-231 | 等待 | `spike/sp01ab/src/main/java/com/example/spike/web/SpikeController.java:220` | `Thread.sleep(500)` | 500 ms | 控制器内固定等待（模拟处理） | 硬编码 | 裸数字 | 试验代码；建议用参数而非固定睡 | — |
| N-OPS-232 | 等待 | `spike/sp01d/src/main/java/com/example/spike/tools/SpikeTools.java:81` `.java:91` | `Thread.sleep(200)` / `Thread.sleep(seconds * 1000L)` | 200 ms / N s | 工具执行的可取消/不可取消对照 | 硬编码 | 部分（注释：cooperative=false 时一次性 sleep 不响应取消） | 对照设计清晰；建议把 seconds 来源写进注释 | — |

### 8.1 补录（复核追加，编号接续 232）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-233 | 阈值 | `scripts/ci/stub-upstream.mjs:1462` `.mjs:1471` | `fields.length < 1 或 > 20` / `label.length > 100` | 1–20 个字段 / 100 字符 | 桩侧模板校验的行内字面量（字段数、label 长度） | 硬编码 | 部分（脚本头 :107-108 自认「仍是行内字面量，未纳入逐值对账集合」） | 与 050-054 的「副本清单」不在一处，易漏对账；建议并入同一事实源对账集合 | S-20 |

---

## 9. 统计

- **ID 行数：274 条**（N-OPS-001 … N-OPS-274；无重号、无缺号，逐行 10 列完整；233 与 234-274 为两轮复核补录，分别见 §8.1、§11）。
- **独立项数：257 条**（扣除完全重复 17 行：部署 profile 9 行 + 前端 env 1 行 + 双实例 7 行，含 255/256 与 138/140 的同行同值重复；详见文首口径与各行「非独立项」标注）。下文的来源/依据/分类统计**均按 ID 行计数**（含重复行），不按独立项重算。
- **来源**：配置类 **48** 条（17.5%），硬编码类 **226** 条（82.5%）。→ 与本范围的性质一致：脚本/CI 的绝大多数数字改不了，只能改脚本。
- **依据**：`有` **52** 条（19.0%）、`部分` **113** 条（41.2%）、`裸数字` **109** 条（39.8%）。→ 裸数字集中在固定 sleep、等待窗、归档保留与探活超时；§11 补录后裸数字占比上升，因为「用例级覆写」几乎全是手写窗口。
- **分类分布**（按主类归并）：超时 70、阈值 57、等待 26、轮询 24、缓冲 19、工作流预算 17、端口 16、重试 15、行数/样例 14、并发/池 11、TTL 3、定时 1、其他 1。
- **§11 补录 41 条**（234-274）：桩路由 1 条、探针用例级 20 条、双实例 harness 13 条、spike Java 7 条。
- **带旧表 ID 的行：79 条**（按 ID 行计；同一个 S-xx 可能对应多行，属「旧表按行合并」的拆分）。

### 9.1 旧表 S-01…S-33 复核结论（按现态）

| 旧 ID | 新行 | 复核结论 |
| --- | --- | --- |
| S-01 | 001-008、024、025、030、033、038-042 | 值有增删（新增 changes/redis-guard/summary；e2e-real 拆 60/45；cluster3 45；redis5 25；nightly 30/25/25/60/5）；仍全部裸数字 |
| S-02 | 009、026、043 | 由 1 处变 3 个 workflow 共 4 个 service；参数未变 |
| S-03 | 019、046 | 90s 未变；nightly 复制一套 |
| S-04 | 021、046 | 60s 未变；nightly 复制一套 |
| S-05 | 014、020、046、177 | curl -m 5/3 未变；新增 cluster3 的 `-m 2` |
| S-06 | 013、046 | `sleep 2` 未变并复制到 nightly |
| S-07 | 010-012、049、098、131、132 | 端口集合扩大（新增 231xx 段、93xx CDP、8082） |
| S-08 | 015、016 | 10 / 150 未变 |
| S-09 | 023、028、031、034、044、045 | 7 天与 14 天两套并存（旧表只记 7 天） |
| S-10 | 101 | 120s 派生式（旧表行尾注记已含），现公式仍未读 yml |
| S-11 | 104 | 60s ×5（旧表 3 处），未变 |
| S-12 | 102、103 | 400/500ms 未变 |
| S-13 | 099、100、106 | **180s → 360s（total-budget 300 + 60）**，改为派生式 |
| S-14 | 115、116 | **3500/3000/3000 → 5000（5 处）+ 3000（2 处）**；文档称 3000 不达标，但 2 处仍用 3000（冲突未收口） |
| S-15 | 107 | maxSuspend 6 未变（2 处） |
| S-16 | 112、113、114、154、160 | `Sleep 2s` 由 13 处降为 12 处；但新增大量 200–800ms 轮询与 CDP 固定等待，总量未降 |
| S-17 | 130 | 纪律保留（断言 >0，不写死 120） |
| S-18 | 022、125、186 | 定义数 ≥15 由 1 处变 3 处 |
| S-19 | 050-054 | 5 项上限未变，新增 `MAX_LINKAGE_DEPTH=2`（055） |
| S-20 | 056、055、233 | KEY_PATTERN `{0,39}` 未变；fields `1..20`（`stub-upstream.mjs:1462`）与 `label ≤100`（`:1471`）**仍在**，属旧表已登记的「行内字面量、未纳入逐值对账」观察项，本册补录为 233 |
| S-21 | 058、059 | 24 字符切片、160/300 截断未变 |
| S-22 | 074 | usage 5 组造数未变，新增 `big` 的 note |
| S-23 | 073 | maxCallsPerTurn 6 未变 |
| S-24 | 087 | check-docs 128MiB 未变 |
| S-25 | 088、089 | `{1,60}` / `{1,80}` 未变 |
| S-26 | 090 | 5 / 24 未变（仍有完整论证） |
| S-27 | 192 | 密钥长度阈值扩到 20 组，仍无逐条来源 |
| S-28 | 193、194、195 | 4000 跳过 + `{1,80}` 未变；新增 PATH `{1,60}` |
| S-29 | 197 | max_size_kb 2048 未变 |
| S-30 | 151、153 | **AWAIT 240000 → FALLBACK 330s（派生）+ 尾段 150s**；语义已改为事件驱动 |
| S-31 | 149、150 | 120 改为 `SMOKE_FRAME_TIMEOUT_SECONDS`（可覆写）；12×500ms 未变 |
| S-32 | 154 | 8 处 sleeps 未变 |
| S-33 | 148 | CDP 9333 与 1400×1800 未变 |

**旧表没有、本册新增的面**：`changed-files.mjs` / `select-tests.mjs` / `gate.mjs`（选择性测试全套阈值）、`check-d6-sync.mjs`、`forbid-redis-cluster-unsafe.mjs`、i18n 基线、`cluster-3node` 与 `redis-5-smoke` 两个 workflow、`cluster3-run.sh` / `cluster3-probe.mjs`、三份部署 profile、`spike/**` 全部 yml 与 Java 常量。

**旧表有、现态已消失的面**：`FileKeyMigrationRunner` / `FileKeyMigrationLock` / `RedisFileKeyMigrationLock` 及测试（git status 显示删除），脚本/CI/spike 全文 0 引用 ⇒ 迁移锁等待、迁移批量、迁移重试一类数字**不再存在**，本册不设行、也不从旧表照抄。

---

## 10. 重点待裁项（按影响排序，均只提不改）

1. **N-OPS-116 与 N-OPS-115 自相矛盾**：同文件注释明确「3000ms 只兜得住 1 个心跳周期、会假绿」，但 `verify-e2e.ps1:3410` 与 `:3680` 仍用 3000ms 做「无新帧」观察。这是唯一「文档判据与实现直接冲突」的时序项。
2. **N-OPS-061 桩缺省 `stall-frames=50000` < 产品 `inter-event-timeout=90s`**：若有用例依赖缺省值验「帧间静默看门狗」，看门狗根本不会响（用例必须显式传 `inter-event+5s`，S-04 确实如此）。缺省值与产品阈值的不等式应写清，否则下一个用例作者会踩。
3. **N-OPS-104 `Wait-Until … 60` ×5**：作业创建是毫秒级，60s 上限意味着失败时要白等一分钟；同类问题见 N-OPS-105（20s/10s 两个「随手取」）。
4. **固定 sleep 总量未降**：N-OPS-112（12×2s）、N-OPS-114（22 处 0.2–1s）、N-OPS-160（23 处 0.1–1.5s）——旧表 H-06 点名的脆弱面在 CDP 冒烟里反而扩大了。
5. **保留期两套并存**：N-OPS-023/028/045（7 天）vs N-OPS-031/034/044（14 天）。失败证据与审计报告用 7 天，与「证据链可追溯」目标冲突。
6. **同一阈值在三/四个 workflow 各写一份**：N-OPS-009/026/043（redis 健康 5/3/12）、N-OPS-013/014/019/021 与 046（sleep/curl/90s/60s）。改一处必忘其余。
7. **N-OPS-208 PG `timeout-per-shutdown-phase: 20s` < SSE 单流 300s**：优雅停机可能直接截断在途长流，与 202/209 的 drain-window 5s 组合后的真实行为需实测。
8. **N-OPS-180 vs N-OPS-178**：探针 job-fail 缺省 90s，workflow 传 130s；**N-OPS-181 vs N-OPS-136**：接管窗 120s vs 90s。同类语义三套值。
9. **N-OPS-099 / N-OPS-101 仍是手抄产品键**：`TurnBudgetSeconds=300`、`(30*3)+10+20` 的 30/3/10/20 都写死在脚本里，而同文件已有 `Get-AiResilienceSeconds` 读 yml 的能力——同一脚本内两种纪律。
10. **N-OPS-150 取消收敛 6s**：对「取消后收敛」偏紧，且与 330s 的轮次上限相差 55 倍；失败时无法区分「未收敛」与「太早判」。
11. **N-OPS-027 真模型采样 10 次无判据**：缺「10 次全过才绿」还是「允许 1 次 flake」的规则，采样数就没有意义。
12. **N-OPS-037 确定性棘轮 40**：已有「只许下调」纪律，但缺「每夜降多少 / 谁负责」的收口机制。
13. **探针用例级窗口高度分散（§11.2 实测）**：18 个用例中共 **59 处**覆写（不含 helper 缺省与自检），同一语义出现 30/60/90/120 s 四档（238/239/241/246/251），单用例最多 13 个不同窗口（248）。建议裁一次「四档常量表」，否则调一个产品键要改十来处。
14. **N-OPS-234 桩无逐路由调用上限**：25 条路由只有全局 `maxCallsPerTurn=6`。若将来某条路由需要独立限额（如慢工具路由），当前结构没有承载位；这不是缺陷，但应作为已知边界登记，避免误以为「逐路由已限」。

---

## 11. 复核补全（主控 2026-10-31 要求，编号接续 233）

> 本节是对 §12.3「可能遗漏」中**可静态确认**的四类面的补齐：① 桩路由逐路由上限；② 探针全部用例级时间窗；③ 双实例 harness 全部等待点；④ spike Java 其余时间/资源数字。补录后这些面**不再是「可能遗漏」**，§12.3 已相应改写。一次性造数仍按既定口径排除；「DOM 固定 sleep 是否改条件等待」只给静态建议。

### 11.1 ① `stub-routes.json` 逐路由 `maxCalls`（机械扫描结论）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-234 | 阈值 | `scripts/ci/stub-routes.json:35`（`routes` 数组全文，25 条） | `routes[*].maxCalls` / `routes[*].maxCallsPerTurn`（**均不存在**） | **0 条**（25 条路由中 0 条带逐路由次数上限；全文件仅 1 处 `maxCallsPerTurn`，即全局 6，已登记为 073） | 逐路由工具调用次数上限的机械扫描结果 | 配置（JSON） | 有（机械扫描：JSON 解析 25 条 `routes`，键集合仅 `id/note/match/tool/arguments`；全文大小写不敏感检索 `maxcalls` 命中 1 次） | 逐路由上限**不存在**，收敛只靠全局 `maxCallsPerTurn=6`；若将来引入逐路由上限，须先定义「逐路由 vs 全局」的优先级与计数口径，否则两闸会互相遮蔽 | — |

### 11.2 ② `cluster3-probe.mjs` 用例级时间窗（按用例逐条）

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-235 | 超时 | `scripts/ci/cluster3-probe.mjs:2848-2901` | `caseColdStart3` 无覆写 | **0 处**（全部走 helper 缺省） | 三实例冷启动（共享 H2-TCP/Redis/文件） | 硬编码（无） | 有（机械扫描：函数体内 0 处 timeout 覆写） | 唯一「不覆写」的用例；建议注明它是缺省值的基线用例，缺省改动会先在这里显形 | — |
| N-OPS-236 | 超时 | `scripts/ci/cluster3-probe.mjs:2916` | `timeoutMs: 10000` | 10 s | 无粘滞用例：30 请求经代理的健康/路由取证单请求上限 | 硬编码 | 裸数字 | 10 s 是普通 HTTP；建议与缺省 getJson 15s 统一 | — |
| N-OPS-237 | 超时 | `scripts/ci/cluster3-probe.mjs:2949` `.mjs:3010` | `timeoutMs: 10000` / `waitTerminal(120000)` | 10 s / 120 s | 同一 keep-alive TCP 跨 A/B/C：首请求上限 + 终态等待 | 硬编码 | 裸数字 | 120 s 与本用例的「三实例接力」轮次相关，建议注明轮数×单轮预算 | — |
| N-OPS-238 | 超时 | `scripts/ci/cluster3-probe.mjs:3047` `.mjs:3049` `.mjs:3052` | `fullReplayToTerminal 60000` / `reattachOn 60000` / `waitTerminal 60000` | 60 s ×3 | 任意实例 reattach（B 全量 / C 差量） | 硬编码 | 裸数字 | 三处同值可抽一个用例常量；60 s 与 90 s 缺省不一致，建议说明为何收紧 | — |
| N-OPS-239 | 超时 | `scripts/ci/cluster3-probe.mjs:3095` `.mjs:3097` `.mjs:3099` `.mjs:3101` `.mjs:3103` `.mjs:3113` | `30000`×2 / `120000`×3 / `90000` | 30 s / 120 s / 90 s | 跨实例 seq：实时流两帧 + 重挂终态 + 全量回放 | 硬编码 | 裸数字 | 同一用例三档值；建议写成「首帧 30s、终态 120s、回放 90s」的命名常量 | — |
| N-OPS-240 | 超时 | `scripts/ci/cluster3-probe.mjs:3154` `.mjs:3170` `.mjs:3255` | `reattachOn 30000`×2 / `fullReplayToTerminal 90000` | 30 s / 90 s | 挂起在 A、B/C 决策前重挂、A 全量回放 | 硬编码 | 裸数字 | 30 s 是「重挂立刻收流」的判据窗；建议与 park settle 20s 的关系注明 | — |
| N-OPS-241 | 超时 | `scripts/ci/cluster3-probe.mjs:3327` `.mjs:3359` | `fullReplayToTerminal 120000`×2 | 120 s ×2 | 跨实例幂等：两个 run 的全量回放 | 硬编码 | 裸数字 | 同用例两个 run 各 120s，最坏 240s；建议串行/并行语义写明 | — |
| N-OPS-242 | 超时 | `scripts/ci/cluster3-probe.mjs:3464` `.mjs:3466` `.mjs:3474` | `30000`×2 / `60000` | 30 s / 60 s | 会话销毁：在途慢流两帧 + 跨实例 DELETE | 硬编码 | 裸数字 | DELETE 60 s 远大于单请求；建议与「在途 deferred 重试」窗对齐 | — |
| N-OPS-243 | 超时 | `scripts/ci/cluster3-probe.mjs:3708` | `timeoutMs: 30000` | 30 s | 配置热更新：A 写入后等 B/C reload 的单请求 | 硬编码 | 裸数字 | 30 s 是 reload 周期余量；建议与产品 reload 周期关联 | — |
| N-OPS-244 | 超时 | `scripts/ci/cluster3-probe.mjs:3719` `.mjs:3733` `.mjs:3781` `.mjs:3795` | `timeoutMs: 30000`×4 | 30 s ×4 | 共享文件：A 上传、B/C/代理下载字节一致 | 硬编码 | 裸数字 | 同一用例 4 处复制；建议抽用例常量 | — |
| N-OPS-245 | 超时 | `scripts/ci/cluster3-probe.mjs:3928` `.mjs:3995` | `fullReplayToTerminal 90000` / `postJson scan 60000` | 90 s / 60 s | 挂起态早杀 A：C 全量回放 + startup-resume/scan | 硬编码 | 裸数字 | scan 60 s 与僵尸扫描 5×5s 的窗不同源；建议统一到「接管窗」 | — |
| N-OPS-246 | 超时 | `scripts/ci/cluster3-probe.mjs:4018` `.mjs:4020` `.mjs:4057` | `30000`×2 / `90000` | 30 s / 90 s | 流式中杀 A：两帧 + 跨实例补帧到终态 | 硬编码 | 裸数字 | 90 s 等于缺省 waitTerminal；建议直接省略以用缺省 | — |
| N-OPS-247 | 超时 | `scripts/ci/cluster3-probe.mjs:4157` `.mjs:4206` | `waitJobStatus 120000` / `httpRequest 10000` | 120 s / 10 s | 作业中杀 A：幸存实例看门狗收敛 + 代理健康 | 硬编码 | 部分（120 s 与 job-fail 判死窗相关） | 120 s > job-fail 90s，能覆盖判死；建议注释该不等式 | — |
| N-OPS-248 | 超时 | `scripts/ci/cluster3-probe.mjs:4237` `.mjs:4249` `.mjs:4262` `.mjs:4283` `.mjs:4285` `.mjs:4288` `.mjs:4303` `.mjs:4321` `.mjs:4326` `.mjs:4338` `.mjs:4350` `.mjs:4400` `.mjs:4445` | `25000`×4 / `15000` / `30000`×2 / `3000` / `1000` / `2000` / `90000` / `35000` / `8000` | 1–90 s（13 处） | readiness 摘除：stats UP/DOWN、draining 期探活、接管终态、恢复纳入 | 硬编码 | 部分（1000/2000ms 是摘流窗口内的刻意紧探） | 单用例 13 个不同窗口是全文件最密处；建议把「drain 窗内探活」与「接管终态」分两组命名常量 | — |
| N-OPS-249 | 超时 | `scripts/ci/cluster3-probe.mjs:4473` `.mjs:4480` `.mjs:4492` `.mjs:4502` `.mjs:4508` `.mjs:4515` `.mjs:4551` `.mjs:4819` `.mjs:4931` `.mjs:5019` | `15000`×6 / `60000` / `30000`×3 | 15 s / 60 s / 30 s | Redis 故障恢复：停 Redis 期间 health/ready/definitions 探活、恢复后重挂与两格回放 | 硬编码 | 部分（15 s 是故障期探活上限） | 15 s×6 与「2s 命令超时」相差 7.5 倍；建议探活上限取「超时×重试次数+余量」 | — |
| N-OPS-250 | 超时 | `scripts/ci/cluster3-probe.mjs:5221` | `timeoutMs: 15000` | 15 s | L-150 三格：取消态读取/拒绝回执的单请求 | 硬编码 | 裸数字 | 单处；建议与 249 的 15s 抽常量 | — |
| N-OPS-251 | 超时 | `scripts/ci/cluster3-probe.mjs:5475` | `fullReplayToTerminal 90000` | 90 s | 双身份（确认门）：A 身份在 C 的全量回放 | 硬编码 | 裸数字 | 同 246 的 90s；建议统一 | — |
| N-OPS-252 | 超时 | `scripts/ci/cluster3-probe.mjs:5586` `.mjs:5624` | `reattachOn 30000` / `fullReplayToTerminal 90000` | 30 s / 90 s | 双身份（前端通道）：决策前重挂 + 全量回放 | 硬编码 | 裸数字 | 与 240/251 同构；建议抽「重挂 30s / 回放 90s」两常量 | — |
| N-OPS-253 | 超时 | `scripts/ci/cluster3-probe.mjs:3022` `.mjs:4197` `.mjs:4685` `.mjs:4713` `.mjs:4738` `.mjs:4778` | `historyStable 15000` / `waitAllStatsUp 25000` / `awaitCancelUnknownAll 15000` / `awaitCancelReadableAll 30000` / `waitPendingStatus 20000` / `resumeRefusal 20000` | 15/25/15/30/20/20 s | §5 的 N-OPS-189 未覆盖的 6 个 helper 缺省（189 只列了 12 个） | 硬编码 | 裸数字 | 与 189 合并为一处清单，避免「helper 缺省」散落两行 | — |
| N-OPS-254 | 超时 | `scripts/ci/cluster3-probe.mjs:5811` `.mjs:5814` `.mjs:5817` `.mjs:5992` `.mjs:5993` `.mjs:6038` `.mjs:6602` `.mjs:6603` `.mjs:6609` `.mjs:6610` `.mjs:6614` `.mjs:6618` | preflight `5000`×3 / 自检 `10000`+`5000` / keep-alive `5000` / park `10000`+`5000` / 空回放 `10000`+`5000` / 自检负控 `300` | 0.3–10 s（12 处） | 探针 preflight（桩/代理/stats 可达）与 `--self-check` 本地服务器的窗口 | 硬编码 | 裸数字 | 自检是本地起服，5–10 s 富余；建议降到 1–2 s 缩短自检时长，并注明 300ms 负控是「必须超时」 | — |

### 11.3 ③ `b23l1-dual-instance.ps1` 全部等待点与用例语义

> 已登记且本表不再设行：`:188` 300ms 与 `:206` 1000ms（preflight 端口/Redis 探测，见 N-OPS-139）、`:408` 120s（`Read-Sse` 缺省，见 137）、`:835` 30s（`-WakeReadyTimeoutSec`，见 135）、`:962` 120s（`Read-RunFullToTerminal` 缺省，见 143）。
> **重叠如实标注**：下表 255/256/260/261/264/265/266 与既有行**完全重复**（255→138、256→140 为同文件同行同值；260/261→142、264/265→146（+147）、266→147+142）；258/262/263 为**部分重叠**（常量已登记、新增调用点）；255/256 同时承载 134/135 的等待函数，但数值已由 138/140 登记。各行已在「审视建议」列写明与既有 ID 的关系，不再笼统声称「不重复」。

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-255 | 轮询 | `scripts/b23l1-dual-instance.ps1:401` | `Start-Sleep -Milliseconds 800` | 800 ms/次 | `Wait-Health` 节拍：所有用例（含 `-NoStart`）的 A/B 就绪等待 | 硬编码 | 裸数字 | 【**与 N-OPS-138 完全重复、非独立项**：同文件同一行 `:401`、同值 800ms 已在该行登记；本行仅补用例语义，不另计】120s/0.8s=150 次；建议 1–2 s 并复用 verify-e2e 的 1s 口径（与 N-OPS-134 的关系：134 记 120s 上限参数，本行只是其等待函数的轮询节拍） | — |
| N-OPS-256 | 轮询 | `scripts/b23l1-dual-instance.ps1:500` | `Start-Sleep -Milliseconds 100` | 100 ms/次 | `Wait-WakeReady`：`suspend-reattach-wake` 等后台 SSE 长连接真正建立 | 硬编码 | 部分（注释：连接真建立了才去批准确认） | 【**与 N-OPS-140 完全重复、非独立项**：同文件同一行 `:500`、同值 100ms 已在该行登记；本行仅补用例语义，不另计】100ms×30s=300 次探测；建议保留（该处精度要求高）（与 N-OPS-135 的关系：135 记 30s 上限） | — |
| N-OPS-257 | 等待 | `scripts/b23l1-dual-instance.ps1:552` | `Start-Sleep -Milliseconds $waitMs` | 计算值（`fireAtMs - now`） | `Start-PostJob` 的「同刻齐发」：`suspend-decision`（并发双决策）、`resource-lock` 共享轮、`lease-scripts` | 硬编码（派生） | 有（函数语义：按给定 fireAt 对齐毫秒级并发） | 派生式等待，属正确做法；建议注明「对齐精度受调度抖动影响」 | — |
| N-OPS-258 | 重试 | `scripts/b23l1-dual-instance.ps1:626` `.ps1:1086` `.ps1:1157` | `Start-Sleep -Milliseconds 300` + `$timeoutSec = 10`（调用处传 10） | 300 ms/次 / 10 s 上限 | `Wait-RedisKeyGone`：`resource-lock`（预置锁）与共享库轮次的锁键消失 | 硬编码 | 裸数字 | 10 s 远大于单键 DEL；建议降到 2–3 s 并注明「锁键只由本脚本删除」。（**与 N-OPS-145 部分重叠**：300ms/10s 常量已在 145 的 `:622-626` 登记；本行新增 `:1086`/`:1157` 两个调用点，属调用面补充而非独立数值） | — |
| N-OPS-259 | 等待 | `scripts/b23l1-dual-instance.ps1:790` `.ps1:797` | `.Wait(1000)`×2 | 1000 ms | `suspend-reattach-wake`：哨兵文件出现 + 端口可连的双重探测 | 硬编码 | 裸数字 | 与 256 的 100ms 轮询配合；建议合并为单次条件探测 | — |
| N-OPS-260 | 超时 | `scripts/b23l1-dual-instance.ps1:842` | `Wait-Job -Timeout 90` | 90 s | `suspend-reattach-wake`：收尾后台 job（超时即 `Stop-Job`） | 硬编码 | 裸数字 | 【**与 N-OPS-142 完全重复、非独立项**：`:842` 的 Wait-Job 90s 已在该行登记；本行仅补用例语义，不另计】与 `KillTakeoverTimeoutSec=90` 同值但语义不同；建议区分命名 | — |
| N-OPS-261 | 超时 | `scripts/b23l1-dual-instance.ps1:918` | `Wait-Job -Timeout 60` | 60 s | `suspend-decision`（并发双决策）：两路 confirm job 收尾 | 硬编码 | 裸数字 | 【**与 N-OPS-142 完全重复、非独立项**：`:918` 的 Wait-Job 60s 已登记；本行仅补用例语义】60 s 对两路 HTTP 偏宽；建议与 257 的齐发窗对齐 | — |
| N-OPS-262 | 轮询/超时 | `scripts/b23l1-dual-instance.ps1:970` `.ps1:982` `.ps1:1141` `.ps1:1142` | `Start-Sleep -Milliseconds 500` + `timeoutSec 120`×4 调用点 | 500 ms / 120 s | `Read-RunFullToTerminal`：批准后全量重放到终态（`resource-lock`、共享库轮次） | 硬编码 | 裸数字 | 500ms 节拍 + 120s 上限 = 240 次重挂；建议改为 lastSeq 续收而非全量重放。（**与 N-OPS-143 部分重叠**：`:962` 的 120s 缺省已在 143；本行新增 `:970` 的 500ms 节拍与 `:982`/`:1141`/`:1142` 显式实参） | — |
| N-OPS-263 | 重试 | `scripts/b23l1-dual-instance.ps1:1013` `.ps1:1129` `.ps1:1167` | `$maxRounds = 3` / `Wait-Job 60` / 调用处 `3` | 3 轮 / 60 s | `resource-lock` 共享库变体：3 轮竞态，每轮两路 confirm job | 硬编码 | 裸数字 | 3 轮是「撞出竞态」的经验值；建议注释单轮失败是否中止。（**与 N-OPS-142 部分重叠**：`:1129` 的 Wait-Job 60s 已在 142；本行新增 `:1013` 的 3 轮上限与 `:1167` 调用点） | — |
| N-OPS-264 | 等待 | `scripts/b23l1-dual-instance.ps1:1432` `.ps1:1435` | `Start-Sleep -Seconds 12` + `PTTL < 22000` | 12 s / 22 s | `lease-scripts`：等一个续租周期后验 PTTL 回到 ~28s | 硬编码 | 部分（注释：每 10s 续租一次） | 【**与 N-OPS-146 完全重复、非独立项**：`:1432` 的 12s 与 `:1435` 的 22s 均已在该行登记；本行仅补用例语义】22 s 应为 `leaseTtl - 2s`；建议写成派生式（产品 lease.ttl 12s） | — |
| N-OPS-265 | 等待 | `scripts/b23l1-dual-instance.ps1:1439` `.ps1:1455` | `Start-Sleep -Seconds 7` + `AddSeconds(KillTakeoverTimeoutSec)` | 7 s / 90 s 上限 | `lease-scripts`：避开 20s 那次续租后杀 A，再等租约过期 | 硬编码 | 部分（注释：最后一次续租 ~10s、租约 ~40s 过期） | 【**与 N-OPS-146、N-OPS-147 完全重复、非独立项**：`:1439` 的 7s 见 146，`:1455` 的 90s deadline 见 147；本行仅补用例语义】7 s 与 12 s 的组合依赖续租节奏；建议注明「若产品续租周期改动必同步」 | — |
| N-OPS-266 | 轮询/重试 | `scripts/b23l1-dual-instance.ps1:1459` `.ps1:1463` | `Start-Sleep -Seconds 1` + `Wait-Job -Timeout 60` | 1 s / 60 s | `lease-scripts`：租约过期后两路并发 resume，等「恰一个 202 + 一个 409」 | 硬编码 | 部分（注释：预算 KillTakeoverTimeoutSec，可能延迟重试先接管） | 【**与 N-OPS-147、N-OPS-142 完全重复、非独立项**：`:1459` 的 1s 见 147，`:1463` 的 Wait-Job 60s 见 142；本行仅补用例语义】1 s 节拍下最多 90 轮、每轮两个 job；建议改为事件读 `runs/current` | — |
| N-OPS-267 | 超时 | `scripts/b23l1-dual-instance.ps1:1306` | `($KillTakeoverTimeoutSec * 1000)` | 90 s | `instance-kill`：B 侧等被接管 run 的终帧（`LEASE_HELD_ELSEWHERE` 时靠延迟重试） | 硬编码（派生） | 部分（注释：延迟重试在租约过期后接管） | 派生正确；建议注释 90s ≥ 租约 12s + retry-delay 17s + 余量 | — |

### 11.4 ④ `spike/**` Java 内部其余时间/资源数字

| ID | 分类 | 文件:行 | 常量/表达式 | 现值+单位 | 作用 | 来源 | 依据 | 审视建议（不修改） | 旧ID |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-OPS-268 | 并发/池 | `spike/sp01ab/src/main/java/com/example/spike/config/SpikeConfig.java:85` `.java:87` | `setCorePoolSize(4)` / `setQueueCapacity(32)` | 4 线程 / 32 队列 | sp01ab 工具调用执行池（与 214 的 maxPool 8 成组） | 硬编码 | 裸数字 | 4/8/32 是一组；建议在决策卡登记「队列饱和后拒绝策略」 | — |
| N-OPS-269 | 并发/池 | `spike/sp01d/src/main/java/com/example/spike/config/SpikeConfig.java:55` | `setQueueCapacity(64)` | 64 | sp01d 执行池队列（core=max=threads=8，见 220） | 硬编码 | 裸数字 | 64 与 sp01ab 的 32 不一致且无说明；建议注明各自试验的并发假设 | — |
| N-OPS-270 | 重试 | `spike/sp01d/src/main/java/com/example/spike/core/FrontendToolLoop.java:42` `.java:197` | `MAX_ROUNDS = 4` | 4 轮 | 前端工具循环的最大轮次（超限即终止） | 硬编码 | 部分（`if (r.round > MAX_ROUNDS)` 有明确语义） | 4 轮与产品 max-tool-steps 25 差异大；建议注明是「前端工具专项」上限 | — |
| N-OPS-271 | 重试 | `spike/sp01d/src/main/java/com/example/spike/core/ResilientChatService.java:216` `.java:272` | `.retry(1)` ×2 | 1 次重试 | REF / REFMODEL 参照分支的硬编码重试（复刻参照实现） | 硬编码 | 有（注释：复刻参照实现顺序；GUARDED 分支才用 maxAttempts=2） | 参照分支与受控分支两套重试并存是刻意对照；建议在两处注明「不可统一」 | — |
| N-OPS-272 | 行数/样例 | `spike/sp01d/src/main/java/com/example/spike/web/DevController.java:186` | `frames.size() >= 30` | 30 层栈帧 | `/api/dev/threads` 取证时每线程保留的栈帧上限 | 硬编码 | 裸数字 | 30 只影响诊断输出；建议注释「仅取证，不影响判定」 | — |
| N-OPS-273 | 缓冲 | `spike/sp02/src/main/java/com/example/spike/core/ToolCallExecutor.java:313` | `text.length() > 800 ? substring(0,800)` | 800 字符 | 工具结果回填前的截断（sp02 侧，与 214 的 sp01ab 同值） | 硬编码 | 裸数字 | 三处 800（sp01ab/sp02）各自复制；建议抽公共常量或注明「实验对照必须同值」 | — |
| N-OPS-274 | 等待 | `spike/sp02/src/main/java/com/example/spike/core/ToolCallExecutor.java:218` | `Thread.sleep(delay)`（`delay = properties.execDelayMsAfterApprove`） | 0 ms 缺省（`SP02_EXEC_DELAY_MS` 可覆写） | 「已放行未执行」故障注入窗（approve 后、执行前） | 配置（属性） | 有（注释：复现「已放行未执行」时进程死亡；`exec_pre_delay` 先落事件再睡） | 缺省 0 = 关闭注入，属安全缺省；建议注释「注入时必须 >0 且小于客户端超时」 | — |

---

## 12. 方法与覆盖面（终版）

### 12.1 扫描方式

- 全量枚举范围内文件（`find`）：`scripts/**` 29 个、`.github/workflows/**` 5 个、`spike/**` 源码与脚本（排除 `target/` 产物）、`backend/config/application.yml.example`、`backend/src/main/resources/application-{cluster,h2tcp,postgresql}.yml`、`frontend/.env.example`。
- 逐文件按模式检索后**人工读上下文**：`timeout|Timeout|sleep|Sleep|wait|Wait|retry|Retry|interval|period|budget|max|min|limit|cap|size|count|port|Port|buffer|maxBuffer|retention|concurrency|pool|seq |while |for (` 以及数值字面量与 `{n,m}` 量词。
- 大文件（`verify-e2e.ps1` 6663 行、`cluster3-probe.mjs` 7641 行、`stub-upstream.mjs` 2216 行）采用「常量区 + 关键函数 + 全部 sleep/等待点」定位法，不逐行通读全篇。
- **§11 补录的机械扫描**：① 对 `stub-routes.json` 做 JSON 解析 + 键集合枚举 + 全文 `maxcalls` 大小写不敏感检索；② 按 `async function caseX` 切分 `cluster3-probe.mjs` 的函数体，逐区间提取 `timeoutMs:`/`waitTerminal(`/`waitClosed(`/`fullReplay*(..., N)`/`waitStatsStatus(..., N)`/`waitJobStatus(..., N)` 的数值实参，并剔除函数签名里的缺省值；③ 对 `b23l1-dual-instance.ps1` 提取全部 `Start-Sleep`、`.Wait(`、`Wait-Job`、派生 deadline；④ 对 `spike/**/src` 提取 `Thread.sleep`、`Duration.of*`、线程池参数、截断长度、循环上限。
- 交叉核对：`scripts/README.md`、workflow 注释、旧 NUMSPEC 总账 S 段；同名常量在脚本/JSON/yml 之间做同值比对（如 `MAX_*` 与 `stub-routes.json` 的 fault 缺省）。

### 12.2 明确排除面

- **依赖/工具版本**：`node-version: 22`、`java-version: 21`、`redis:7.2.16`、`haproxy:2.9-alpine`、`actions/*@v5`、Playwright/SpreadJS 版本。属依赖版本，非运行数字规格。
- **HTTP 状态码**（200/201/202/400/409/429/503）与错误码字符串。
- **测试数据断言与一次性造数**：种子行数（CURRENCY=5、SYS_PARAM=40 等）、用例条数文案（35/166/91/34/4）、P0P1P2 分布、xlsx 魔数/最小字节（1000/500）、`verify-e2e.ps1` 用例内临时造数。
- **算法下标/协议常量**：CRC16 的 8 位循环与 `0x1021`、`0x3FFF`、Redis 16384 槽、`Long.MAX_VALUE`、`2^53`、sha 40/8（仅 199 行因「探测窗」同列一并登记）。
- **spike 的键形/位宽**：`RunStore` 的 runId 后缀 6 位、`Sp02Controller` 的 8 位 UUID 片段、Redis 键前缀拼接 —— 属标识符形态而非时间/资源数字，明确排除（时间/资源数字见 268-274）。
- **纯业务 fixture**：`STAMP`、提示词文本、`sheet-big` 之外的造数行。
- **主 `application.yml` 与后端/前端源码常量**：属其它分册；本册只登记其**副本/引用**（cluster/h2tcp/postgresql profile、脚本读取点）。
- **敏感文件**：`.env.local`、任何 `.env*`、密钥（未读取；`.env.example` 中的空值为占位）。
- **仓库外产物**：`spike/*/target/**`（编译产物与复制的 application.yml）、`backend/target/**`。

### 12.3 可能遗漏（如实登记，四项已闭合）

**已闭合、不再列为遗漏**：
- ① 桩逐路由 `maxCalls` → **N-OPS-234**（机械扫描：25 条路由 0 条带逐路由上限）。
- ② 探针用例级时间窗 → **N-OPS-235…252**（18 个用例 59 处覆写，含「零覆写」的 cold-start），helper 余项 **253**，自检/preflight **254**。
- ③ 双实例 harness 全部等待点 → **N-OPS-255…267**（含各等待点所属用例语义）。**重叠已修正**：255/256 与 138/140 同文件同行同值、260/261/264/265/266 分别见 142/146/147，共 **7 行完全重复**（行内均标「非独立项」）；258/262/263 为部分重叠（常量已登记、仅补调用点）；`188/206/408/835/962` 属函数缺省/探针，已在 135/137/139/143 登记，本表不设行。
- ④ spike Java 其余时间/资源数字 → **N-OPS-268…274**（线程池 4/8/32/64、MAX_ROUNDS 4、retry(1)、30 栈帧、800 截断、exec-delay sleep）；键形/位宽按 12.2 排除。

**仍属未覆盖/需实跑的面**：
1. **`verify-e2e.ps1` 用例内一次性造数**（消息长度、临时 `size=500` 之外的取值）只登记控制验证逻辑的部分；其余按 12.2 口径排除，非漏登记。
2. **CDP 固定 sleep 的可替换性**：`chat-panel-smoke.mjs` 23 处与 `m2t2a-smoke.mjs` 8 处已全列（160/154），但「能否改为条件等待」只给静态建议，需运行观察 DOM/store 稳定信号后才能定案。
3. **窗口「值是否足够」**：探针与双实例的全部窗口已列，静态只能确认「覆盖与来源」；是否足够（尤其 248 的 13 个窗、249 的故障期 15s×6）需实跑或压测取证。
4. **部署面缺口**：全仓无 Dockerfile/Compose/nginx，故「容器资源限制、副本数、探针 periodSeconds」一类数字**没有对象可审**；若后续引入，需新增一节。
5. **旧 NUMSPEC 的 B/F/V 段**（后端/前端/环境）不在本册；若本册的「脚本读取产品键」与其它分册的现值不一致，应在汇总时对账（重点：total-budget 300s、hitl 120s、heartbeat 2s、memory 40、lease 12s）。
6. **spike 的跨进程时序**（fault-proxy 与应用的相对时序）只登记了注入值，未推导端到端最坏时长；试验工程按「证据可复现」而非「时序预算」管理。
