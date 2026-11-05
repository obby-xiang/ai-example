# Redis 生产类直驱压测（P0–P2）

本入口直接实例化并调用生产类/生产 runner，不使用 `redis-protocol` 的候选 Lua 或状态机。Java 代码位于：

```text
backend/src/test/java/com/example/configmgr/perf/production/
```

复用现有 `HarnessConfig / RedisHarness / Manifest / HarnessMetrics / OutputWriter`，输出同名的 `manifest.json`、`results.json`、`protocols.json`、`verify.json`、`cleanup.json`。只连接显式隔离的 Redis；不启动 HTTP 服务、不执行项目测试、不驱动模型。DB 候选、事务、附件和文件依赖使用 Mockito 或场景独立目录隔离，Redis 命令、Lua、runner 和业务断言均由真实生产实现执行。

**当前只完成代码集成与 `test-compile`。真实 standalone/Cluster 结果必须由下述隔离设备执行产生，不能用静态编译代替实测 PASS。**

## 场景清单（43）

### P0：删除主事故面与核心写协议（6）

- `startup_runner_active_scan_1m`
- `run_hitl_decision_claim_complete`
- `memory_concurrent_cas_and_staging`
- `kb_generation_prune_recovery`
- `purge_retry_due_full_lifecycle`
- `lease_fenced_takeover_cluster`

### P1：高放大接口、跨实例一致性与资源治理（29）

- `purge_retry_failure_and_fairness`
- `purge_active_cancel_deferred`
- `startup_resume_candidate_storm`
- `startup_dependency_failure_matrix`
- `job_heartbeat_ticker_hot`
- `job_cancel_version_and_db_fallback`
- `job_watchdog_singleflight`
- `task_delete_slow_redis_lock_hold`
- `task_event_pubsub_fanout`
- `task_event_slow_emitter_saturation`
- `frame_append_hot_large`
- `frame_replay_gap_and_trim`
- `wake_coalesce_and_reconnect`
- `transcript_large_pagination`
- `context_compaction_commit_hot`
- `ledger_idem_large_projection`
- `attachment_full_lifecycle`
- `attachment_failure_rollback`
- `session_registration_and_gate_token`
- `run_snapshot_hot_save`
- `run_recovery_index_large`
- `client_request_sweep_large`
- `memory_purge_generation`
- `purge_memory_writeback_crash`
- `purge_cluster_slot_and_error_cleanup`
- `resource_lock_two_phase_release`
- `scan_lease_long_and_stalled`
- `kb_refresh_large_corpus`
- `redis_availability_down_recovery`

### P2：固定配置、健康、缓存与降级（8）

- `task_event_redis_down_lazy_start`
- `cancel_and_observation_lifecycle`
- `memory_find_ids_scan`
- `kb_cache_outage_fallback`
- `ai_model_catalog_cache`
- `create_task_draft_merge_large`
- `startup_topology_contract`
- `health_degradation_observability`

不注册 P3，不重复场景 ID。standalone 与 Cluster 使用完全相同的生产类和业务断言；每场景有独立 namespace/copy，背景键、共享 HASH field、run/purge 索引 member 只处理本工具登记项。

## 证据与清理

- `results.json`：完整输入、manifest 属性、逐阶段耗时、真实 Lettuce 命令数、失败命令数、SCAN/HSCAN 页数、请求字节、协议断言、覆盖状态及清理结果。
- `verify.json`：生产断言与 retained 控制样本。
- `cleanup.json`：自有键、共享 HASH 自有 field、背景键、run/purge 索引 member 和外部控制样本。
- KB 共享 manifest 仅按本场景发布值条件恢复；generation 仅在当前值仍为本场景最新值时条件恢复/删除，不覆盖其他场景新代次；lease 仅按 token 条件删除。
- 清理不使用 FLUSH、KEYS 或全键 SCAN。单调 generation 不回退到覆盖其他场景的新值，外部 member/新值不回删。
- 所选场景缺 dataset 时 cleanup 返回 `unknown/not generated` 且 `complete=false`。

## 覆盖与故障

默认 `PERF_FAULT_INJECTION=true`、`PERF_FAULT_OPERATIONS=all`。完整 P0–P2 覆盖要求全部 43 场景及以下故障操作启用：

```text
rpush,sadd,rename,cas,staging_discard,hdel,eval,hgetall,set,get,
index_eval,registration_delete,tombstone_set,del,srem,lpush,hget,
ping,subscribe,zrem,AI_FRAME_APPEND,scan,MEMORY_CLEAR,zrangebyscore,pexpire
```

场景子集、关闭故障或故障子集均为显式缩减实验：`coverageStatus=PARTIAL`，最终 verify/exit 为非 PASS，并记录 `coverageReason`。`single_master_targeted` 目前明确属于 `coverageLimit`，不是 COMPLETE 的必需 PASS 项：现有故障代理不能按 slot/master 定向，standalone 也没有 master-slot 语义，不能用普通一次 PING 冒充单 master 故障。场景级 `coverageLimit` 会写入 manifest，并在 generate/measure/verify/all 分阶段命令间保留；只有无限制、43 场景且全部必需 fault 启用时才为 COMPLETE。

`task_event_slow_emitter_saturation` 的前端 `getJob → onSnapshot` 快照轮询补偿需要真实浏览器验证，Java 场景只记录 `snapshotPollingCoverage=PARTIAL`、`coverageLimit=EXTERNAL_BROWSER_REQUIRED`，不生成该路径的 PASS；后续由 DeepSeek 浏览器复核。

## 配置

模板见 `.env.example`。安全默认值不变：`PERF_ISOLATED_ENV=false`、全局写门禁关闭、非默认 DB/远程需显式确认、cleanup 默认 dry-run。`PERF_NAMESPACE` 必须显式指定。

规模与专项输入：

- `PERF_STARTUP_BACKGROUND_KEYS`：默认 smoke=2000、full=100000、stress=1000000；超过 10000 需 `PERF_ALLOW_LARGE_SCALE=true`，超过 100000 还需 `PERF_ALLOW_BACKGROUND_MILLION=true`。
- `PERF_STARTUP_EXTERNAL_ACTIVE_KEY` / `PERF_STARTUP_EXTERNAL_REMOTE_KEY` / `PERF_STARTUP_EXTERNAL_INVALID_KEY`：仅登记已存在的外部控制键，不创建、不删除。
- `PERF_MEMORY_MESSAGE_BYTES` 默认 16384；`PERF_MEMORY_CONCURRENT_RUNS` 默认 4；`PERF_LEASE_CONCURRENT_OWNERS` 默认 8（2..64）。
- `PERF_KB_CORPUS_DOCS` 默认 130（65..256，小样本，不生成一万文档）；`PERF_KB_CORPUS_BODY_BYTES` 默认 256（64..2048）。
- `PERF_TASK_EVENT_SATURATION_FRAMES` 默认 32（8..256）。
- `PERF_TASK_REDIS_DELAY_MS` 默认 80（1..2000），用于任务删除边界延迟/失败与恢复验证。

故障注入发生在真实 Redis 命令/生产 Lua 调用边界，不复制或替换脚本。manifest 在任何场景写操作前先保存占位，失败时保存同一对象的部分键面；后台 ticker 在 `finally` 停止，cleanup 不因场景失败跳过。

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
export PERF_SCENARIOS=                    # 空 = 全部 43 场景

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

百万启动背景（专用设备、单场景，结果仍为 PARTIAL）：

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

Windows CMD 使用相同环境变量并执行 `run.cmd`。只有全部 43 场景和全部必需故障点都执行，`coverageStatus=COMPLETE` 才允许完整 P0–P2 PASS；未实测前不得宣称性能或覆盖达标。
