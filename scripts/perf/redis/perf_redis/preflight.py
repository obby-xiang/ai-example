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
from .redisops import RedisOps, RedisUnavailable, error_detail


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


def _namespace_probe_keys(cfg: Config, manifest: Dict[str, object]) -> List[str]:
    """Exact keys only: background first/middle/last + a few session/run keys.

    Never SCAN: on a multi-million-key Cluster a no-match SCAN would traverse
    every master and block doctor/preflight.
    """
    keys: List[str] = []
    background = manifest.get("background") or {}
    if not isinstance(background, dict):
        background = {}
    for bucket, state in background.items():
        if not isinstance(state, dict):
            continue
        try:
            count = int(state.get("planned") or state.get("count") or 0)
        except (TypeError, ValueError):
            count = 0
        if count <= 0:
            continue
        indexes = sorted({0, count // 2, count - 1})
        for index in indexes:
            keys.append(K.background_key(cfg.namespace, str(bucket), index))

    scenarios = manifest.get("scenarios") or {}
    if not isinstance(scenarios, dict):
        scenarios = {}
    for scenario, entry in list(scenarios.items())[:3]:
        if not isinstance(entry, dict):
            continue
        plan = entry.get("plan") or {}
        for copy in (entry.get("copies") or [])[:2]:
            if not isinstance(copy, dict):
                continue
            session_id = str(copy.get("session_id") or "")
            if not session_id:
                continue
            if plan.get("session_record"):
                keys.append(K.session_record_key(session_id, cfg.tenant_id, cfg.user_id))
            runs = copy.get("runs") or []
            if runs and isinstance(runs[0], dict):
                run_id = str(runs[0].get("run_id") or "")
                if run_id:
                    keys.append(K.run_key(run_id, cfg.tenant_id, cfg.user_id))
    return K.dedupe(keys)


def _namespace_state_checks(cfg: Config, ops: RedisOps, checks: List[Dict[str, object]]) -> None:
    try:
        manifest = M.load_manifest(cfg)
    except Exception as exc:
        _add(checks, "namespace_manifest", "unknown", "failed to read the local manifest: %s" % error_detail(exc))
        manifest = None
    if manifest is None:
        _add(checks, "namespace_manifest", "info", "no local manifest")
        _add(checks, "namespace_state", "info",
             "cannot prove the namespace is empty; no full-database SCAN was performed (large keyspaces scan every primary)")
        return
    _add(checks, "namespace_manifest", "pass", "local manifest loaded (namespace=%s)" % manifest.get("namespace"))
    probes = _namespace_probe_keys(cfg, manifest)
    if not probes:
        _add(checks, "namespace_state", "info",
             "manifest has no exact keys to probe; cannot prove the namespace is empty (no SCAN performed)")
        return
    existence = ops.exists_many(probes)
    present = [key for key in probes if existence.get(key) is True]
    unknown = [key for key in probes if existence.get(key) is None]
    if unknown:
        _add(checks, "namespace_state", "unknown",
             "exact probe reads failed; residual keys cannot be determined: %s" % ",".join(unknown[:5]))
    elif present:
        _add(checks, "namespace_state", "info",
             "exact sampling found %d residual keys (first/middle/last/session samples): %s; run cleanup first"
             % (len(present), ",".join(present[:5])))
    else:
        _add(checks, "namespace_state", "info",
             "none of the %d exact first/middle/last/session keys exist; this does not prove the whole keyspace is empty (no SCAN performed)"
             % len(probes))


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
             "Streams/XADD require Redis >= 5 (current %s)" % version)
    if server_mode:
        expected = cfg.mode
        _add(checks, "redis_mode_matches_env", "pass" if server_mode == expected else "fail",
             "INFO redis_mode=%s, REDIS_MODE=%s" % (server_mode, expected))
    else:
        _add(checks, "redis_mode_matches_env", "unknown", "INFO did not return redis_mode")

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
                raise RedisUnavailable("cannot parse primaries from CLUSTER NODES; seed fallback refused")
            clients = ops.primary_node_clients(strict=True, targets=authoritative)
            if len(clients) != len(authoritative):
                raise RedisUnavailable("incomplete primary connectivity %d/%d" % (len(clients), len(authoritative)))
            _add(checks, "cluster_primary_connectivity", "pass",
                 "every primary is reachable: %s"
                 % ",".join("%s:%d" % (h, p) for h, p in authoritative))
        except Exception as exc:
            _add(checks, "cluster_primary_connectivity", "fail", error_detail(exc))
        sample_session = "perf-doctor-%s" % cfg.namespace
        sample_run = sample_session + "-r0"
        thirteen = list(K.run_keys_13(sample_run, cfg.tenant_id, cfg.user_id).values())
        same_slot, slots = K.slot_check(thirteen)
        _add(checks, "cluster_13_keys_single_slot", "pass" if same_slot else "fail",
             "slots=%s" % sorted(set(slots.values())))
    else:
        _add(checks, "standalone_db", "pass" if cfg.db == 0 or cfg.confirm_non_default_db else "fail",
             "REDIS_DB=%d" % cfg.db)

    _namespace_state_checks(cfg, ops, checks)

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
             "tenant=%d user=%d is present in the /api/ai/tenants directory" % (cfg.tenant_id, cfg.user_id))
    else:
        _add(checks, "app_identity_registered", "fail",
             "tenant=%d user=%d is absent from the /api/ai/tenants directory (the HTTP identity boundary returns 400)"
             % (cfg.tenant_id, cfg.user_id))

    health = api.health()
    if health.get("status") in (200, 503):
        business = health.get("business") or {}
        _add(checks, "app_health", "pass",
             "status=%s available=%s code=%s (model absence is normal; this tool does not invoke the model)"
             % (health.get("status"), business.get("available"), business.get("code")))
    else:
        _add(checks, "app_health", "fail",
             "status=%s error=%s" % (health.get("status"), health.get("transport_error")))

    probe_session = "perf-doctor-%s" % cfg.namespace
    current = api.runs_current(probe_session)
    if current.get("status") == 200:
        _add(checks, "app_identity_headers", "pass", "GET /api/ai/runs/current with identity headers returned 200")
    else:
        _add(checks, "app_identity_headers", "fail",
             "GET /api/ai/runs/current with identity headers returned status=%s error=%s"
             % (current.get("status"), current.get("transport_error")))


def _workspace_checks(cfg: Config, checks: List[Dict[str, object]], write_probe: bool) -> None:
    paths = M.workspace(cfg)
    _add(checks, "output_dir", "pass", str(paths["root"]))
    if write_probe:
        probe = paths["root"] / ".write-probe"
        try:
            probe.write_text("ok", encoding="utf-8")
            probe.unlink()
            _add(checks, "output_dir_writable", "pass", "probe file written and deleted successfully")
        except OSError as exc:
            _add(checks, "output_dir_writable", "fail", error_detail(exc))
    try:
        import httpx
        import redis
        _add(checks, "dependencies", "pass",
             "redis-py=%s httpx=%s" % (getattr(redis, "__version__", "?"),
                                        getattr(httpx, "__version__", "?")))
    except Exception as exc:
        _add(checks, "dependencies", "fail", error_detail(exc))
    _add(checks, "python", "pass", "%s (%s)" % (sys.version.split()[0], platform.platform()))
    if cfg.app_file_storage_path:
        root = Path(cfg.app_file_storage_path)
        _add(checks, "file_storage_root", "pass" if root.exists() else "info",
             "%s exists=%s" % (root, root.exists()))
    if cfg.allow_file_delete and not cfg.app_file_storage_path:
        _add(checks, "file_delete_safety", "fail",
             "PERF_ALLOW_FILE_DELETE=true without PERF_APP_FILE_STORAGE_PATH; files cannot be deleted safely")


def _safety_checks(cfg: Config, checks: List[Dict[str, object]]) -> None:
    _add(checks, "isolated_env", "pass" if cfg.isolated_env else "fail",
         "PERF_ISOLATED_ENV=%s (must be true for generate/measure/cleanup --execute)" % cfg.isolated_env)
    remote = cfg.remote_targets()
    _add(checks, "remote_confirmation",
         "pass" if not remote or cfg.confirm_remote else "fail",
         "remote=%s confirm=%s" % (",".join(remote) or "none", cfg.confirm_remote))
    _add(checks, "db_confirmation",
         "pass" if cfg.is_cluster() or cfg.db == 0 or cfg.confirm_non_default_db else "fail",
         "db=%d confirm=%s" % (cfg.db, cfg.confirm_non_default_db))
    _add(checks, "large_scale", "pass" if cfg.allow_large_scale else "info",
         "PERF_ALLOW_LARGE_SCALE=%s (large scenarios require explicit opt-in)" % cfg.allow_large_scale)
    _add(checks, "background_million", "pass" if cfg.allow_background_million else "info",
         "PERF_ALLOW_BACKGROUND_MILLION=%s (one million background keys require explicit opt-in)" % cfg.allow_background_million)


def doctor(cfg: Config) -> Dict[str, object]:
    checks: List[Dict[str, object]] = []
    started = M.utc_now_iso()
    _add(checks, "config_env", "fail" if cfg.errors else "pass",
         "; ".join(cfg.errors) if cfg.errors else "environment variable validation passed")
    M.resolve_namespace(cfg)
    _workspace_checks(cfg, checks, write_probe=False)
    _safety_checks(cfg, checks)
    if not cfg.errors:
        ops = RedisOps(cfg)
        try:
            ops.connect()
            _redis_checks(cfg, ops, checks, write_probe=False)
        except Exception as exc:
            _add(checks, "redis_connect", "fail", error_detail(exc))
        finally:
            ops.close()
        api = None
        try:
            api = ApiClient(cfg)
            _app_checks(cfg, api, checks)
        except Exception as exc:
            _add(checks, "app_checks", "fail", error_detail(exc))
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
        _add(checks, "redis_connect", "fail", error_detail(exc))
    finally:
        ops.close()
    api = None
    try:
        api = ApiClient(cfg)
        _app_checks(cfg, api, checks)
    except Exception as exc:
        _add(checks, "app_checks", "fail", error_detail(exc))
    finally:
        if api is not None:
            api.close()
    paths = M.workspace(cfg)
    try:
        existing = M.load_manifest(cfg)
    except Exception as exc:
        _add(checks, "manifest", "fail", "failed to read manifest: %s" % error_detail(exc))
        existing = None
    if existing:
        _add(checks, "manifest", "info",
             "existing manifest: %s; generate skips generated scenarios, use a new PERF_NAMESPACE/PERF_OUTPUT_DIR for repeated measurement"
             % paths["manifest"])
    else:
        _add(checks, "manifest", "pass", "no previous manifest (fresh run)")
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
