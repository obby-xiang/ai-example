> 来源=<仓外证据>/cluster-config-resume-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# cluster-config-resume 修复报告（run 40182796512）

- 基线：`<主仓>` @ `34fd8f4`（未改动，只读参照）
- 施工副本：`<验证副本>/_verify-20261025-cluster-config-resume`（隔离，提交 `6a038d3`）
- 补丁（对 `34fd8f4`，`git apply` 兼容）：`<仓外证据>/cluster-config-resume-34fd8f4.patch`
  - 16 文件 / +1013 −87；已在 `34fd8f4` 的干净克隆上 `git apply --check` + 实 apply + 提交后跑自证与聚焦测试（见 §5）
- 证据依据：`<仓外证据>/cluster3-34fd8f4-analysis.md`（唯一失败 `suspend-cross-instance`，15 PASS / 1 FAIL）

---

## 1 结论

| 根因 | 处置 | 结果 |
|---|---|---|
| **A1**（主因）种子写路径不发布配置版本 ⇒ 其它实例快照永久为空 ⇒ 跨实例续跑误判 `PROVIDER_GONE` | 种子事务内 bump `ai_setting['config.version']`，提交后 `INCR ai:cfg:ver` + reload + evict；写路径与种子共用同一实现 | 双向收敛（DB 版本通道 + Redis 信号通道）均有 RED→GREEN 用例 |
| **B1**（暴露面）硬否定只回 409，快照停在 SUSPENDED、无终帧、无驱动方 ⇒ 客户端 120s 无帧超时 | 在 `ResumeService` 的硬否定处收敛：`FAILED` + `PROVIDER_GONE` 前缀 + `error` 终帧（新码 `AI_PROVIDER_GONE`）+ 收流；只读/不可确认**不**收敛 | 直接决策与重挂自愈两条入口分别有 RED→GREEN 用例 |
| **B2**（防御纵深）按陈旧快照判 `PROVIDER_GONE` | 首次解析为空时强制 `reload()` 一次再判（仅此时多一次 DB 读） | 陈旧缓存正例 + 真删除反例各有用例 |
| **T1** 冷启动断言被 env 合成行掩盖 | 每实例必须至少一条**非 synthetic 库行**、默认 `providerId` 落在其中、三实例 id 一致（有界等待收敛） | 自证 1 正例 + 5 反例；干净树上 `run.sh --self-check` 53/53、`probe --self-check` 28/28 |

范围守约：**未**触碰只读审计结论，**未**触碰 T-13（另一个 agent 的主 CI 失败）；未改探针超时、未重排用例、未改拓扑、未引入新依赖。

---

## 2 改动清单（逐文件 + 为什么）

### 生产代码 + 文案/镜像（8 文件，含 1 个新增）

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java` | **新增**。配置版本发布的唯一实现：`bumpInTransaction()`（在调用方事务内读-改-写 `config.version`，含"为什么不是原子 UPDATE"的原注释迁移）、`publishAfterCommit()`（`INCR ai:cfg:ver` → `AiConfigService#reload` → `AiProviderRegistry#evictAll`，三者失败都不回滚且 Redis 只 WARN）。与写路径原语义逐字一致，只是从 `AiConfigWriter` 内部提出来共用。 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java` | 私有 `bumpVersion()` / `afterCommit()` 改为委托 `AiConfigVersionPublisher`（删掉 60 行重复实现与两个随之无用的字段），**构造器签名不变**。语义零变化（`AiConfigWriterTest` 27 例全绿）。 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java` | `writeSeed` 返回 `boolean`（真的写了数据行才为 true）：provider+model 写完之后**在同一事务内** `versions.bumpInTransaction()`；事务提交后 `versions.publishAfterCommit()`。no-op（表非空 / seed-force 撞到用户改过的行）不 bump、不发布；并发冲突（唯一约束）整事务回滚 ⇒ 版本只由胜方推进一次。类注补"写种子 = 写数据行 + 推进版本"一节（含本轮事故的因果）。 |
| `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java` | ① `resolveForResume(...)`：身份解析为空时强制 `reload()` 一次再判（B2），重载异常按"仍为空"处理并 WARN；② `providerGone(...)` 末尾调用 `convergeProviderGone(...)`（B1）：`FAILED` + `error="PROVIDER_GONE：…"` + 清 in-flight → `store.save` → `out.error(AI_PROVIDER_GONE, …)` → `registry.close(runId)`；**写权限只读/不可确认时整段跳过**（不写终态、不出业务帧、不收流）；已是终态则幂等跳过；③ 新增 `PROVIDER_GONE_MESSAGE` / `_EN` 常量与 `providerGoneMessage()`（en 走 `messages_en`，不回落到内部信息）；④ 409 body 的 `message` 改用常量（一处文案两处出口）。 |
| `backend/src/main/java/com/example/configmgr/common/ErrorCodes.java` | 新增 `AI_PROVIDER_GONE`（机器可读终态码；与 `AI_RESUME_FAILED` 的分工写在注释里：后者是"续跑执行失败"，前者是"确定不可用，终态"）。 |
| `backend/src/main/resources/messages_zh.properties` / `messages_en.properties` | 各 +1 行 `error.AI_PROVIDER_GONE`（与 409 同一句中文；en 为白名单短句）。`ErrorCodesContractTest` 的三方对账（后端码/前端镜像/双 locale）自动覆盖。 |
| `frontend/src/types/api.ts` | `ErrorCode` 镜像 +1 项（后端新增码必须同步，否则 `ErrorCodesContractTest#frontendMirrorMatchesBackendCodes` 红）。仅类型常量，无行为改动。 |

### 探针（1 文件，T1）

| 文件 | 改动 |
|---|---|
| `scripts/ci/cluster3-probe.mjs` | 新增 `seededProviderIdentity(body)`（按 `synthetic` 认库行，不用"id 键是否存在"）、`assertColdProviderIdentity(snapshots)`（每实例 ≥1 条非合成行 + 默认 `providerId` 落在其中 + 三实例 id/providerId 逐字一致）、`waitColdSeededProvider(30s)`（有界等待；成功落证据 `cold-provider-identity.json`，超时**先落** `cold-provider-identity-last.json` 再抛，报文带最后观测）；`verifyColdState()` 在三条 `/health` 断言之后、`waitSeeded` 之前调用它；自证新增 `selfTestColdProviderIdentity`（1 正例 + 5 反例：仅合成行 / providerId 为空 / 三实例 id 不一致 / providerId 不在库行 / providers 缺失）。**未改**任何既有断言、超时与用例顺序。 |

### 测试（7 文件，含 3 个新增）

| 文件 | 用例 |
|---|---|
| `AiConfigSeederConvergenceTest`（新） | ① `seedPublishesTheConfigVersionSoAnotherInstanceConvergesToTheSeededProvider`：三个**独立** `AiConfigService` 快照（种子方 / 只比 DB 版本 / 比 `ai:cfg:ver`）共享一份库，两个读者**先**加载空快照（= 实测 B/C 形态），断言种子后 DB 版本 1→2、`ai:cfg:ver` 由"不存在"→"1"、两个读者都 `resolveChatTarget(seededId,"deepseek-flash")` 非空、诊断视图出现 `synthetic=false` 的库行；② `noOpSeedDoesNotChurnTheVersion`（版本与 INCR 次数都不动）；③ `seedForceRewritesAndBumpsTheVersionOnce`（重灌 ⇒ 版本 +1、无重复行）。 |
| `AiConfigSeederTest`（Spring 真实装配） | 启动后 `config.version` 必须为 2（迁移常量 1 + 种子 +1）；并发双启动**恰好** +1（输方整事务回滚，既不跳两格也不一格没动）。其余 4 例零改动。 |
| `ResumeProviderGoneTerminalTest`（新） | ① `confirmedProviderGoneLandsAsFailedTerminalStateWithAMachineReadableFrame`（FAILED + `PROVIDER_GONE` 前缀 + 归档末帧 `type=error,code=AI_PROVIDER_GONE` + 不驱动）；② `convergenceIsSkippedWhenTheLeaseAuthorityIsUnreadable`（`UNAVAILABLE` ⇒ 仍 SUSPENDED、归档 0 帧）；③ `alreadyTerminalRunIsNotRewrittenOnASecondAttempt`（幂等）。用真实 `RunRegistry` + `InMemoryRunStore` 事件日志，断言的是**对外的终帧**而非"方法被调用过"。 |
| `ResumeProviderGoneDispatchTest`（新，`ai/gate`） | 真实 `ResumeService` 挂在真实 `ConfirmGate` 上（同步派发）：① `reattachSelfHealConvergesToAFailedTerminalFrame`（`triggerResumeForParked`，= `GET /events/{runId}` 的自愈对账）；② `directConfirmConvergesToAFailedTerminalFrame`（`submitDecision`，= `POST /api/confirm` 的派发）。两条入口都断言 FAILED + 终帧。 |
| `AiResumeExactProviderBindingTest` | +`staleSnapshotDoesNotYieldProviderGoneWhenTheRowIsStillInTheDatabase`（先加载空快照、另实例随后写库、版本未动 ⇒ 必须 ACCEPTED）；+`genuinelyDeletedProviderStillYieldsProviderGoneAfterTheForcedReload`（库里确实没有 ⇒ 仍 GONE 且不驱动）。 |
| `ResumeProviderGoneTest` | `providerSwitchOfTheSameModelYieldsProviderGone`：`resolveChatTarget` 断言改为 `atLeastOnce()` 并新增 `verify(configService).reload()`（B2 的契约变了：判死前必刷新），**未**放宽"不按模型名去别家借"的那条 `never()`。 |
| `AiConfigWriterTest` | `seeder(...)` 辅助补第 6 个构造参数（新 publisher），断言零改动。 |

---

## 3 RED → GREEN 记录（每条生产改动都有先红的证据）

命令统一为（IDEA 自带 Maven；**无 `clean`**、`-o` 离线、`-Dspring.data.redis.port=1`、不启 8081/5202、不连真实拓扑）：

```
MVN="<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
cd backend && "$MVN" -B -o test -Dspring.data.redis.port=1 -Dtest=<类名>
```

| # | 改动 | RED（修复前实测） | GREEN（修复后实测） |
|---|---|---|---|
| A1 | 种子发布版本 | `-Dtest=AiConfigSeederConvergenceTest` → **Tests run: 3, Failures: 2**：`seedPublishes…` 在「种子写与 config.version +1 必须同一事务」处 `expected: 2`（实得 1，`ai:cfg:ver` 未发布、两个读者 `resolveChatTarget` 为空）；`seedForceRewritesAndBumpsTheVersionOnce` 同样 `expected: 2` | 3/3 通过；连带 `AiConfigSeederTest` 6/6（含真实启动版本=2、并发恰好 +1）、`AiConfigWriterTest` 27/27 |
| B2 | 硬否定前强制刷新 | `-Dtest=AiResumeExactProviderBindingTest` → **Tests run: 6, Failures: 1**：`staleSnapshotDoesNotYieldProviderGoneWhenTheRowIsStillInTheDatabase` 在「库里有这一行、只是快照旧 ⇒ …不得误判 PROVIDER_GONE」处 `expected: ACCEPTED`（实得 GONE） | 6/6 通过（含真删除仍 GONE 的反例） |
| B1 | 硬否定收敛终态 | `-Dtest=ResumeProviderGoneTerminalTest` → **Tests run: 3, Failures: 1**：`[确定不可用必须落 FAILED，不能停在 SUSPENDED 等超时] expected: "FAILED"`；同一改动在 `-Dtest=ResumeProviderGoneDispatchTest` → **Tests run: 2, Failures: 2**（`directConfirm…` 与 `reattachSelfHeal…` 都停在 SUPENDED，确认两条入口原本都不收敛） | 3/3 与 2/2 通过 |
| T1 | 冷启动种子身份断言 | 自证反例即 RED 等价物：`selfTestColdProviderIdentity` 的「只有 env 合成行必须红」等 5 条反例在旧断言下会通过 → 新断言把它们判红；真实三实例的 RED **不可在本机复跑**（禁止本机真实拓扑），其现场证据是 run 40182796512 的 `cases/config-hot-reload/config-reload.json`：`healthBefore.b/c.providers[0].synthetic=true` | `node scripts/ci/cluster3-probe.mjs --self-check` → **PASS 28 / FAIL 0**（基线 27，+1 新项）；干净树上 `bash scripts/ci/cluster3-run.sh --self-check` → **PASS 53 / FAIL 0**（基线 53，聚合项内部 +1 子项，总数不变） |

> 说明：B1 的 RED 是在实现完成之后用"临时注释掉 `convergeProviderGone(...)` 一行后重编"复跑的（源码只差这一行），随后恢复并复跑 GREEN。A1/B2 的 RED 是真正的前置红（先写用例、后写实现）。T1 的红证是"断言升级 + 反例自证"，真实集群红证只有历史证据（本机禁止跑真实三实例）。

---

## 4 回归面（聚焦，未跑全量 `mvn test`）

| 批次 | 选择 | 结果 |
|---|---|---|
| 1 | `Resume*Test,MultiCardParkResumeTest,StartupResumeRunner*Test` | **70 tests / 0 F / 0 E** |
| 2 | `ConfirmGate*Test,HumanWaitParkTest,ParkedFrontendExpiryTest` | **73 / 0 / 0** |
| 3 | `AiControllerEvents*Test,AiControllerConfirmStateUnavailableTest,AiControllerRunsCurrentTest` | **19 / 0 / 0** |
| 4 | `AiConfig*Test,AiResume*Test,ExactProviderBindingTest,RunParamsFreezeTest,ErrorCodes*Test,MessageSource*Test,ProviderRegistry*Test,AiAvailability*Test` | **148 / 0 / 0** |
| 5 | `*ConformanceTest,FrameContractFalsificationTest,FrameTypeSetFrozenTest,AiChatTargetResolutionTest,AiDefaultTargetBindingTest,AiProperties*Test,AiProviderRegistry*Test,AiParkConfigTest` | **173 / 0 / 0** |
| 合计 | | **483 tests / 0 failures / 0 errors / BUILD SUCCESS** |

其它门禁（在隔离副本上）：

- `node scripts/ci/check-docs.mjs` → **6/6 断言绿**；
- `frontend` `npm run typecheck` → **0 错误**；`npx vitest run tests/i18n-script-copy.spec.ts` → **14/14**（新增码只加常量，前端行为不变）；
- `node scripts/ci/cluster3-probe.mjs --self-check` → **28/28**；`bash scripts/ci/cluster3-run.sh --self-check` → **53/53**（干净树，见 §5）。

---

## 5 补丁可应用性验证（干净 `34fd8f4`）

```
git clone --shared --no-checkout <main> <验证副本>/_applycheck-34fd8f4
cd <验证副本>/_applycheck-34fd8f4 && git config core.autocrlf false && git checkout 34fd8f4
git apply --check <仓外证据>/cluster-config-resume-34fd8f4.patch   # OK（16 文件 / +1013 −87）
git apply <patch> && git add -A && git commit -F <本批 message>            # 干净树
bash scripts/ci/cluster3-run.sh --self-check    # PASS 53 / FAIL 0  SELF-CHECK PASS
node scripts/ci/cluster3-probe.mjs --self-check # PASS 28 / FAIL 0  SELF-CHECK PASS
mvn -B -o test -Dspring.data.redis.port=1 -Dtest='AiConfigSeederConvergenceTest,ResumeProviderGoneTerminalTest,
  ResumeProviderGoneDispatchTest,AiConfigSeederTest,AiResumeExactProviderBindingTest,ResumeProviderGoneTest,AiConfigWriterTest'
# 全量重编 304 main + 426 test 源文件；Tests run: 51, Failures: 0 → BUILD SUCCESS
```

> 注（如实记录）：在**施工副本**里直接跑 `cluster3-run.sh --self-check` 会有一条 FAIL —— `tree_clean` 这个批内卫生守卫要求"工作树只含本批文件"。副本带本批未提交改动 + 主仓原有的未跟踪运行产物（`_b3e-*.log`/`data/`/`test-results/`），所以它红是**守卫按设计工作**；在干净检出 + 本补丁的树上实测 53/53。另：本机 `core.autocrlf=true` 会让 checkout 出来的 `cluster3-run.sh` 变成 CRLF 并触发 `file_hygiene` 的 CRLF 项（与本补丁无关）；`_applycheck` 里用 `git config core.autocrlf false` 后再 checkout 即 0 FAIL（补丁里所有文件索引侧均为 `i/lf`，Linux CI 不受影响）。

---

## 6 未做 / 剩余风险（如实）

1. **真实三实例拓扑未在本机复跑**（明确禁止）。因此 A1/T1 的最终确认依赖下一次 `cluster-3node` 运行：预期 `suspend-cross-instance` 转绿，且第 1 例即刻校验出"三实例同一非合成 provider 行"。
2. **双终帧的理论边角**：若 provider 被真删 **且** 某个 park 首轮驱动线程在收敛之后才被唤醒，它走 `ResilientChatService` 的 `ProviderGoneException` 分支会再发一帧 `AI_RESUME_FAILED`（与既有 `markDriveFailure` 同源，本批未改帧去重）。客户端在第一帧终态即收敛，不影响本轮的 120s 缺陷，登记为后续可收紧点。
3. **B2 的一次额外 DB 读**只在"第一次解析为空"时发生；库抖动导致 `reload()` 抛错时按"仍为空"判定（并 WARN），行为与修复前一致。
4. **Redis 抖动下 A1 的收敛时延**依赖既有 DB 版本比对兜底（`INCR` 失败只 WARN）—— 机制未变，但本轮未构造该故障做实测（与分析的"未证明部分 §8-3"一致）。
5. **文档未改**：`docs/任务看板.md` 等批次台账类文档不在本次指令范围（只交付补丁 + 本报告）；若合流流程要求回填台账，需另行补档。
6. 只读审计结论与 **T-13（主 CI 失败，另一 agent 负责）** 未触碰、未复查。

---

## 7 一句话

`AiConfigSeeder` 现在与人工写路径共用同一个版本发布实现（数据行 + `config.version` 同事务，提交后 `INCR ai:cfg:ver` + reload + evict），跨实例快照必然收敛（A1）；续跑硬否定不再是"只回 409 的半死轮"，而是 `FAILED` + `AI_PROVIDER_GONE` 终帧 + 收流，直接决策与重挂自愈两条入口都有实测收敛用例（B1）；判死前先强制刷新一次配置，缓存陈旧不再被当成端点消失（B2）；冷启动断言升级为"非合成库行 + 三实例一致"，env 兜底不再能掩盖这一类缺陷（T1）。
