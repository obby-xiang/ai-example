"""doctor (read-only) and preflight (deep, includes a scoped write probe)."""

from __future__ import annotations

import os
import platform
import sys
from pathlib import Path
from typing import Dict, List, Optional

from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config, compare_git
from .redisops import RedisOps


def _add(checks: List[Dict[str, object]], name: str, status: str, detail: str) -> None:
    checks.append({"check": name, "status": status, "detail": detail})


def _counts(checks: List[Dict[str, object]]) -> Dict[str, int]:
    counts = {"pass": 0, "fail": 0, "unknown": 0, "info": 0}
    for item in checks:
        status = str(item.get("status"))
        counts[status] = counts.get(status, 0) + 1
    return counts


def _repo_root() -> str:
    return str(Path(__file__).resolve().parents[4])


def _identity_exists(tenants: Dict[str, object], tenant_id: int, user_id: int) -> bool:
    business = tenants.get("business") or {}
    if not isinstance(business, dict):
        return False
    for tenant in business.get("tenants") or []:
        if not isinstance(tenant, dict) or tenant.get("tenantId") != tenant_id:
            continue
        return user_id in [int(item) for item in tenant.get("users") or []]
    return False


def _redis_checks(cfg: Config, ops: RedisOps, checks: List[Dict[str, object]], write_probe: bool) -> None:
    ok, ms, error = ops.ping()
    _add(checks, "redis_ping", "pass" if ok else "fail",
         "%.1fms" % ms if ok else error)

    info = ops._info_from_any_node()
    version = str(info.get("redis_version", ""))
    server_mode = str(info.get("redis_mode", ""))
    _add(checks, "redis_version", "pass" if version else "fail", "redis_version=%s" % version)
    if version:
        parts = version.split(".")
        major = int(parts[0]) if parts and parts[0].isdigit() else 0
        _add(checks, "redis_stream_support", "pass" if major >= 5 else "fail",
             "Streams/XADD 需要 Redis >= 5（当前 %s）" % version)
    if server_mode:
        expected = cfg.mode
        _add(checks, "redis_mode_matches_env", "pass" if server_mode == expected else "fail",
             "INFO redis_mode=%s，REDIS_MODE=%s" % (server_mode, expected))
    else:
        _add(checks, "redis_mode_matches_env", "unknown", "INFO 未返回 redis_mode")

    keyspace = ops.keyspace()
    _add(checks, "redis_keyspace", "pass" if isinstance(keyspace, dict) else "unknown", str(keyspace))

    if cfg.is_cluster():
        info_cluster = ops.cluster_info()
        state = info_cluster.get("cluster_state")
        assigned = info_cluster.get("cluster_slots_assigned")
        primaries = ops.primaries()
        _add(checks, "cluster_state", "pass" if state == "ok" else "fail", "cluster_state=%s" % state)
        try:
            assigned_int = int(assigned)
        except (TypeError, ValueError):
            assigned_int = -1
        _add(checks, "cluster_slots", "pass" if assigned_int == 16384 else "unknown",
             "cluster_slots_assigned=%s" % assigned)
        _add(checks, "cluster_primaries", "pass" if primaries else "fail",
             "primaries=%s" % ",".join("%s:%d" % (h, p) for h, p in primaries))
        try:
            authoritative = ops.primaries(allow_seed_fallback=False)
            if not authoritative:
                raise RuntimeError("无法从 CLUSTER NODES 解析 primary（拒绝退回种子节点）")
            clients = ops.primary_node_clients(strict=True, targets=authoritative)
            if len(clients) != len(authoritative):
                raise RuntimeError("primary 连接不完整 %d/%d" % (len(clients), len(authoritative)))
            _add(checks, "cluster_primary_connectivity", "pass",
                 "每个 primary 均可连接：%s"
                 % ",".join("%s:%d" % (h, p) for h, p in authoritative))
        except Exception as exc:
            _add(checks, "cluster_primary_connectivity", "fail", str(exc))
        sample_session = "perf-doctor-%s" % cfg.namespace
        sample_run = sample_session + "-r0"
        thirteen = list(K.run_keys_13(sample_run, cfg.tenant_id, cfg.user_id).values())
        same_slot, slots = K.slot_check(thirteen)
        _add(checks, "cluster_13_keys_single_slot", "pass" if same_slot else "fail",
             "slots=%s" % sorted(set(slots.values())))
    else:
        _add(checks, "standalone_db", "pass" if cfg.db == 0 or cfg.confirm_non_default_db else "fail",
             "REDIS_DB=%d" % cfg.db)

    pattern = K.background_prefix(cfg.namespace) + "*"
    scan_failed = False
    try:
        sample = ops.scan_sample(pattern, 1)
    except Exception as exc:
        _add(checks, "namespace_scan", "unknown", "SCAN 失败：%s" % exc)
        scan_failed = True
        sample = []
    if scan_failed:
        _add(checks, "namespace_clean", "unknown", "无法确认命名空间是否已有残留")
    elif sample:
        _add(checks, "namespace_clean", "info",
             "命名空间下已有背景键（上次中断残留？）：%s；先执行 cleanup" % sample[0])
    else:
        _add(checks, "namespace_clean", "pass", "命名空间下没有背景键")

    if write_probe:
        probe_key = "perf:doctor:%s:probe" % cfg.namespace
        probe_ok, probe_error = ops.write_probe(probe_key, 60)
        _add(checks, "redis_write_probe", "pass" if probe_ok else "fail",
             "SET/GET/DEL %s" % (probe_key if probe_ok else probe_error))


def _app_checks(cfg: Config, api: ApiClient, checks: List[Dict[str, object]]) -> None:
    tenants = api.tenants()
    if not tenants.get("ok"):
        _add(checks, "app_reachable", "fail",
             "GET /api/ai/tenants status=%s error=%s" % (tenants.get("status"), tenants.get("transport_error")))
        return
    _add(checks, "app_reachable", "pass", "GET /api/ai/tenants 200")
    if _identity_exists(tenants, cfg.tenant_id, cfg.user_id):
        _add(checks, "app_identity_registered", "pass",
             "tenant=%d user=%d 在 /api/ai/tenants 目录内" % (cfg.tenant_id, cfg.user_id))
    else:
        _add(checks, "app_identity_registered", "fail",
             "tenant=%d user=%d 不在 /api/ai/tenants 目录内（HTTP 身份边界会 400）"
             % (cfg.tenant_id, cfg.user_id))

    health = api.health()
    if health.get("status") in (200, 503):
        business = health.get("business") or {}
        _add(checks, "app_health", "pass",
             "status=%s available=%s code=%s（无模型属正常，本工具不驱动模型）"
             % (health.get("status"), business.get("available"), business.get("code")))
    else:
        _add(checks, "app_health", "fail",
             "status=%s error=%s" % (health.get("status"), health.get("transport_error")))

    probe_session = "perf-doctor-%s" % cfg.namespace
    current = api.runs_current(probe_session)
    if current.get("status") == 200:
        _add(checks, "app_identity_headers", "pass", "带身份头 GET /api/ai/runs/current 200")
    else:
        _add(checks, "app_identity_headers", "fail",
             "带身份头 GET /api/ai/runs/current status=%s error=%s"
             % (current.get("status"), current.get("transport_error")))


def _workspace_checks(cfg: Config, checks: List[Dict[str, object]], write_probe: bool) -> None:
    paths = M.workspace(cfg)
    _add(checks, "output_dir", "pass", str(paths["root"]))
    if write_probe:
        probe = paths["root"] / ".write-probe"
        try:
            probe.write_text("ok", encoding="utf-8")
            probe.unlink()
            _add(checks, "output_dir_writable", "pass", "写入/删除探测文件成功")
        except OSError as exc:
            _add(checks, "output_dir_writable", "fail", str(exc))
    try:
        import httpx
        import redis
        _add(checks, "dependencies", "pass",
             "redis-py=%s httpx=%s" % (getattr(redis, "__version__", "?"),
                                        getattr(httpx, "__version__", "?")))
    except Exception as exc:
        _add(checks, "dependencies", "fail", str(exc))
    _add(checks, "python", "pass", "%s (%s)" % (sys.version.split()[0], platform.platform()))
    if cfg.app_file_storage_path:
        root = Path(cfg.app_file_storage_path)
        _add(checks, "file_storage_root", "pass" if root.exists() else "info",
             "%s exists=%s" % (root, root.exists()))
    if cfg.allow_file_delete and not cfg.app_file_storage_path:
        _add(checks, "file_delete_safety", "fail",
             "PERF_ALLOW_FILE_DELETE=true 但没有 PERF_APP_FILE_STORAGE_PATH，无法安全删除文件")


def _safety_checks(cfg: Config, checks: List[Dict[str, object]]) -> None:
    _add(checks, "isolated_env", "pass" if cfg.isolated_env else "fail",
         "PERF_ISOLATED_ENV=%s（generate/measure/cleanup --execute 必须为 true）" % cfg.isolated_env)
    remote = cfg.remote_targets()
    _add(checks, "remote_confirmation",
         "pass" if not remote or cfg.confirm_remote else "fail",
         "remote=%s confirm=%s" % (",".join(remote) or "none", cfg.confirm_remote))
    _add(checks, "db_confirmation",
         "pass" if cfg.is_cluster() or cfg.db == 0 or cfg.confirm_non_default_db else "fail",
         "db=%d confirm=%s" % (cfg.db, cfg.confirm_non_default_db))
    _add(checks, "large_scale", "pass" if cfg.allow_large_scale else "info",
         "PERF_ALLOW_LARGE_SCALE=%s（大规模场景需显式开启）" % cfg.allow_large_scale)
    _add(checks, "background_million", "pass" if cfg.allow_background_million else "info",
         "PERF_ALLOW_BACKGROUND_MILLION=%s（100 万背景键需显式开启）" % cfg.allow_background_million)


def doctor(cfg: Config) -> Dict[str, object]:
    checks: List[Dict[str, object]] = []
    started = M.utc_now_iso()
    _add(checks, "config_env", "fail" if cfg.errors else "pass",
         "; ".join(cfg.errors) if cfg.errors else "环境变量校验通过")
    M.resolve_namespace(cfg)
    _workspace_checks(cfg, checks, write_probe=False)
    _safety_checks(cfg, checks)
    if not cfg.errors:
        ops = RedisOps(cfg)
        try:
            ops.connect()
            _redis_checks(cfg, ops, checks, write_probe=False)
        except Exception as exc:
            _add(checks, "redis_connect", "fail", str(exc))
        finally:
            ops.close()
        api = None
        try:
            api = ApiClient(cfg)
            _app_checks(cfg, api, checks)
        except Exception as exc:
            _add(checks, "app_checks", "fail", str(exc))
        finally:
            if api is not None:
                api.close()
    counts = _counts(checks)
    return {
        "kind": "doctor",
        "started_at": started,
        "finished_at": M.utc_now_iso(),
        "namespace": cfg.namespace,
        "counts": counts,
        "checks": checks,
        "git": compare_git(_repo_root()),
        "config": cfg.describe_for_log(),
    }


def preflight(cfg: Config) -> Dict[str, object]:
    if cfg.errors:
        return {
            "kind": "preflight",
            "started_at": M.utc_now_iso(),
            "finished_at": M.utc_now_iso(),
            "counts": {"pass": 0, "fail": len(cfg.errors), "unknown": 0, "info": 0},
            "checks": [{"check": "config_env", "status": "fail", "detail": item} for item in cfg.errors],
        }
    cfg.require_write_safety()
    checks: List[Dict[str, object]] = []
    started = M.utc_now_iso()
    M.resolve_namespace(cfg)
    _workspace_checks(cfg, checks, write_probe=True)
    _safety_checks(cfg, checks)
    ops = RedisOps(cfg)
    try:
        ops.connect()
        _redis_checks(cfg, ops, checks, write_probe=True)
    except Exception as exc:
        _add(checks, "redis_connect", "fail", str(exc))
    finally:
        ops.close()
    api = None
    try:
        api = ApiClient(cfg)
        _app_checks(cfg, api, checks)
    except Exception as exc:
        _add(checks, "app_checks", "fail", str(exc))
    finally:
        if api is not None:
            api.close()
    paths = M.workspace(cfg)
    try:
        existing = M.load_manifest(cfg)
    except Exception as exc:
        _add(checks, "manifest", "fail", "读取 manifest 失败：%s" % exc)
        existing = None
    if existing:
        _add(checks, "manifest", "info",
             "已存在 manifest：%s；generate 会跳过已生成场景，重复测量请换 PERF_NAMESPACE/PERF_OUTPUT_DIR"
             % paths["manifest"])
    else:
        _add(checks, "manifest", "pass", "没有旧 manifest（全新运行）")
    counts = _counts(checks)
    return {
        "kind": "preflight",
        "started_at": started,
        "finished_at": M.utc_now_iso(),
        "namespace": cfg.namespace,
        "counts": counts,
        "checks": checks,
        "git": compare_git(_repo_root()),
        "config": cfg.describe_for_log(),
    }
