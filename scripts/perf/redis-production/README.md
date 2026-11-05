# Redis 生产类直驱压测（P0）

本入口直接实例化并调用生产类/生产 runner，不使用 `redis-protocol` 的候选 Lua 或状态机。Java 代码位于：

```text
backend/src/test/java/com/example/configmgr/perf/production/
```

复用现有 `HarnessConfig / RedisHarness / Manifest / HarnessMetrics / OutputWriter`，输出同名的 `manifest.json`、`results.json`、`protocols.json`、`verify.json`、`cleanup.json`。只连接显式隔离的 Redis；不启动 HTTP 服务、不执行项目测试、不驱动模型。DB 候选、事务、附件和文件依赖使用 Mockito 隔离，Redis 命令、Lua、runner 和业务断言均由真实生产实现执行。

## P0 场景

| 场景 | 生产入口与主要证据 |
| --- | --- |
| `startup_runner_active_scan_1m` | `beginRunner/beat/tickOnce` 证明主机索引写入、PTTL 续期和批量两阶段续期；依次运行 `RunnerActiveKeyCleanupRunner`、`StaleJobRecoveryRunner`；本机/他机/存活/非法 PID、不同 generation、SCAN=0；自建 active/beat 键精确 DEL、自建索引 field 精确 HDEL，外部控制样本单独保留 |
| `run_hitl_decision_claim_complete` | 真实 `RunStore` 并发批准/认领、READ 取消整笔不写、非 READ 真实结果、取消完成竞态、六键同槽及旧 epoch 拒绝 |
| `memory_concurrent_cas_and_staging` | 真实 `RedisChatMemoryRepository` 多 run 大消息 CAS；staging/final/index 同槽；RPUSH/SADD/RENAME/CAS/DEL 故障后的旧历史与精确清理 |
| `kb_generation_prune_recovery` | 真实 `KbCache/RedisKbCacheStore` 代次、下线、并发刷新、DEL/HDEL 中断；旧实例不得删新正文，残留下轮收敛；DOC_IDS 只清本场景 field，manifest 由单键 Lua 原子比对发布值并恢复/删除，generation/lock 按归属条件清理 |
| `purge_retry_due_full_lifecycle` | 真实 pending store、purge service 与 retry runner：deferred→due→retry→verify→complete，owner/epoch、旧执行体、guard/pending/due 证据；先证明 memory 写入再 arm guard，最终 data/staging 均清净 |
| `lease_fenced_takeover_cluster` | 真实 `LeaseService` 并发 acquire、计数器/fence 缺失和过期、旧 owner 续租释放、created=false 释放权、epoch 单调与同槽 |
| `purge_active_cancel_deferred` | 真实活跃 run 延期，终态后 retry 续清，不得半删 |
| `purge_retry_failure_and_fairness` | 缺身份记录与后续合法候选同场；合法候选必须继续推进，不能被前一条阻塞 |

standalone 与 Cluster 使用完全相同的场景和断言。每个场景有独立 namespace/copy；run 恢复/列表索引与 purge due 只摘本工具 member。清理不使用 FLUSH、KEYS 或全键 SCAN。

## 配置

模板见 `.env.example`。安全默认值不变：`PERF_ISOLATED_ENV=false`、全局写门禁关闭、非默认 DB/远程需显式确认、cleanup 默认 dry-run。`PERF_NAMESPACE` 必须显式指定。

规模：

- `PERF_STARTUP_BACKGROUND_KEYS`：默认 smoke=2000、full=100000、stress=1000000。
- 超过 10000 需 `PERF_ALLOW_LARGE_SCALE=true`；超过 100000 还需 `PERF_ALLOW_BACKGROUND_MILLION=true`。
- `PERF_MEMORY_MESSAGE_BYTES` 默认 16384；`PERF_MEMORY_CONCURRENT_RUNS` 默认 4；`PERF_LEASE_CONCURRENT_OWNERS` 默认 8（2..64）。
- `PERF_STARTUP_EXTERNAL_ACTIVE_KEY` / `PERF_STARTUP_EXTERNAL_REMOTE_KEY` / `PERF_STARTUP_EXTERNAL_INVALID_KEY` 仅用于登记已存在的外部控制键；本场景不创建、不删除这些键。活/他机/非法自建夹具只在场景期间保留，最终全部清理。

故障注入：

- 默认 `PERF_FAULT_INJECTION=true`、`PERF_FAULT_OPERATIONS=all`，执行全部必需故障点。
- 可选 `rpush,sadd,rename,cas,staging_discard,hdel` 子集；关闭或选择子集时，manifest 的 `faultsSkipped` 明确列出未执行项，不生成这些故障点的通过断言。
- 注入发生在真实 Redis 命令/生产 Lua 调用边界，不复制或替换脚本。
- 只有全部 8 个场景和全部必需故障点都执行，`coverageStatus=COMPLETE` 才允许完整 P0 PASS。场景子集、关闭故障或故障子集均为显式缩减实验：`coverageStatus=PARTIAL`，最终 verify/exit 为非 PASS，并记录 `coverageReason`。

证据：`results.json` 包含完整输入、manifest 属性、逐阶段耗时、真实 Lettuce 命令数、失败命令数、SCAN/HSCAN 页数、协议断言、覆盖状态及清理结果；`verify.json` 检查生产断言与外部控制样本；`cleanup.json` 检查自有键、共享 HASH 自有 field、背景键、run/purge 索引 member 和外部控制样本。manifest 在任何场景写操作前先保存占位，失败时保存同一对象的部分键面；Startup ticker 在 `finally` 停止，cleanup 不因场景失败跳过。KB 外部 member/新值不回删，单调 generation 不回退；所选场景缺 dataset 时 cleanup 返回 `unknown/not generated` 且 `complete=false`。

## Cluster 设备执行命令

```bash
cd <repo>/scripts/perf/redis-production
export MVN=/path/to/apache-maven/bin/mvn
export PERF_MVN_MODE=offline             # 新设备缺依赖时改 online
export REDIS_MODE=cluster
export REDIS_CLUSTER_NODES=10.0.0.11:6379,10.0.0.12:6379,10.0.0.13:6379
export REDIS_DB=0
export PERF_ISOLATED_ENV=true
export PERF_CONFIRM_REMOTE=true
export PERF_NAMESPACE=prod-cl-smoke-001
export PERF_OUTPUT_DIR=./out-cl-smoke-001
export PERF_PROFILE=smoke
export PERF_SCENARIOS=startup_runner_active_scan_1m,run_hitl_decision_claim_complete,memory_concurrent_cas_and_staging,kb_generation_prune_recovery,purge_retry_due_full_lifecycle,purge_active_cancel_deferred,purge_retry_failure_and_fairness,lease_fenced_takeover_cluster

./run.sh doctor
./run.sh all --execute

# 也可分阶段复核同一证据链：
./run.sh generate --execute
./run.sh measure --execute
./run.sh verify
./run.sh cleanup                      # dry-run，只列计划
./run.sh cleanup --execute
./run.sh verify
```

百万启动背景（专用设备）：

```bash
export PERF_NAMESPACE=prod-cl-startup-1m-001
export PERF_OUTPUT_DIR=./out-cl-startup-1m-001
export PERF_PROFILE=stress
export PERF_STARTUP_BACKGROUND_KEYS=1000000
export PERF_ALLOW_LARGE_SCALE=true
export PERF_ALLOW_BACKGROUND_MILLION=true
export PERF_SCENARIOS=startup_runner_active_scan_1m
./run.sh all --execute
```

该单场景命令用于专项性能取样，`coverageStatus=PARTIAL`，不会作为完整 P0 PASS；完整验收须使用上面的全场景、全故障配置。

Windows CMD 使用相同环境变量并执行 `run.cmd`。

## 后续 P1/P2

- P1：作业 ticker/取消版本/看门狗、任务与 AI pub/sub、frame 热写与重放、run recovery 大候选、session gate、KB 大语料、memory purge 代次、context/ledger、purge 更多故障公平性及 Cluster 部分失败。
- P2：KB/模型缓存降级、memory SPI 扫描、启动拓扑、健康降级、create_task 草案与诊断面。
- 当前只完成代码与静态编译；真实 standalone/Cluster 结果必须由上述隔离设备执行产生，不能用静态编译代替实测 PASS。
