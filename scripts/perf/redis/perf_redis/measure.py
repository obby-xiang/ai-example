"""Measurement: warmup, repeated rounds, optional concurrency.

Each destructive scenario has one copy per warmup/round, generated up front, so a
DELETE round never measures a half-empty session. Read scenarios reuse one copy.
"""

from __future__ import annotations

import concurrent.futures
import time
from typing import Dict, List, Optional, Tuple

from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps
from .stats import summarize

READ_OPERATIONS = {
    "history",
    "context",
    "runs_current",
    "runs_list",
    "run_detail",
    "events_probe",
    "cancel_status",
    "pending",
    "startup_resume",
    "health",
    "kb_status",
    "kb_reload",
}


def _op_label(operation: str, index: int) -> str:
    if operation == "delete":
        return "delete" if index == 0 else "delete_again"
    return operation


def _pick_run(copy: Dict[str, object]) -> Optional[Dict[str, object]]:
    runs = copy.get("runs") or []
    for run in runs:
        if isinstance(run, dict) and run.get("status") == "SUSPENDED":
            return run
    if runs and isinstance(runs[0], dict):
        return runs[0]
    return None


def _call(cfg: Config, api: ApiClient, copy: Dict[str, object], operation: str, label: str) -> Dict[str, object]:
    session_id = str(copy.get("session_id"))
    if operation == "delete":
        return api.delete_session(session_id, operation=label)
    if operation == "history":
        return api.history(session_id)
    if operation == "context":
        return api.context(session_id)
    if operation == "runs_current":
        return api.runs_current(session_id)
    if operation == "runs_list":
        return api.runs_list()
    if operation == "startup_resume":
        return api.startup_resume()
    if operation == "health":
        return api.health()
    if operation == "kb_status":
        return api.kb_status()
    if operation == "kb_reload":
        return api.kb_reload()
    run = _pick_run(copy)
    if run is None:
        return {
            "operation": label,
            "method": "GET",
            "path": "",
            "status": None,
            "ok": False,
            "duration_ms": 0.0,
            "transport_error": "copy 没有 run（缺少 runId）",
            "server_timing": "",
            "business": {},
            "body_keys": [],
            "text_prefix": "",
        }
    run_id = str(run.get("run_id"))
    if operation == "run_detail":
        return api.run_detail(run_id)
    if operation == "events_probe":
        return api.events_probe(run_id)
    if operation == "cancel_status":
        return api.cancel_status(run_id)
    if operation == "pending":
        return api.pending(run_id)
    raise ValueError("未知操作：%s" % operation)


def _failure_kind(label: str, status: object, transport_error: str) -> str:
    """Classify a measured request.

    health 503 is an allowed result (the endpoint reports AI unavailability with
    the same body). Any other non-2xx is an HTTP failure; a missing status or a
    transport_error is a transport failure.
    """
    if transport_error or status is None:
        return "transport"
    try:
        code = int(status)
    except (TypeError, ValueError):
        return "http"
    if 200 <= code < 300:
        return ""
    if label == "health" and code == 503:
        return "allowed_http"
    return "http"


def _row(scenario: str, copy_name: str, round_no: int, warmup: bool, label: str,
         result: Dict[str, object]) -> Dict[str, object]:
    status = result.get("status")
    transport_error = str(result.get("transport_error") or "")
    kind = _failure_kind(label, status, transport_error)
    return {
        "scenario": scenario,
        "copy": copy_name,
        "round": round_no,
        "warmup": warmup,
        "operation": label,
        "status": status,
        "ok": bool(result.get("ok")),
        "accepted": kind in ("", "allowed_http"),
        "failure_kind": kind,
        "duration_ms": float(result.get("duration_ms") or 0.0),
        "transport_error": transport_error,
        "server_timing": result.get("server_timing", ""),
        "business": result.get("business", {}),
        "body_keys": result.get("body_keys", []),
        "text_prefix": result.get("text_prefix", ""),
    }


def _execute_concurrent(cfg: Config, api: ApiClient, copy: Dict[str, object], operation: str,
                        label: str, count: int) -> List[Dict[str, object]]:
    workers = max(1, int(count))
    results: List[Dict[str, object]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(_call, cfg, api, copy, operation, label) for _ in range(workers)]
        for future in futures:
            try:
                results.append(future.result())
            except Exception as exc:
                results.append({
                    "operation": label,
                    "status": None,
                    "ok": False,
                    "duration_ms": 0.0,
                    "transport_error": str(exc),
                    "business": {},
                    "body_keys": [],
                    "text_prefix": "",
                })
    return results


def measure(cfg: Config, manifest: Dict[str, object], selected: List[str]) -> Dict[str, object]:
    cfg.require_write_safety()
    started_at = M.utc_now_iso()
    rows: List[Dict[str, object]] = []
    scenario_rows: List[Dict[str, object]] = []
    api = ApiClient(cfg)
    redis_ops = RedisOps(cfg)
    environment: Dict[str, object] = {}
    try:
        try:
            redis_ops.connect()
            environment["redis"] = {
                "mode": cfg.mode,
                "server_mode": redis_ops.redis_mode,
                "version": redis_ops.redis_version,
                "keyspace": redis_ops.keyspace(),
                "topology": redis_ops.role_summary(),
            }
        except Exception as exc:
            environment["redis"] = {"error": str(exc)}

        health = api.health()
        environment["app"] = {
            "base_url": cfg.app_base_url,
            "health_status": health.get("status"),
            "health_business": health.get("business"),
            "reachable": health.get("status") is not None,
        }

        for scenario in selected:
            entry = (manifest.get("scenarios") or {}).get(scenario)
            if not isinstance(entry, dict) or entry.get("status") != "generated":
                scenario_rows.append({
                    "scenario": scenario,
                    "status": "skipped",
                    "reason": (entry or {}).get("reason", "未生成/不支持"),
                })
                continue
            plan = entry.get("plan") or {}
            copies = {str(copy.get("copy")): copy for copy in entry.get("copies", []) if isinstance(copy, dict)}
            operations = list(plan.get("ops") or ["delete"])
            destructive = plan.get("verify_mode") == "delete"
            scenario_result: Dict[str, object] = {
                "scenario": scenario,
                "destructive": destructive,
                "operations": operations,
                "copies": sorted(copies),
                "status": "measured",
                "errors": [],
            }
            if destructive and cfg.warmup > 0:
                warm_copy = copies.get("warmup")
                if warm_copy is None:
                    scenario_result["errors"].append("缺少 warmup copy")
                else:
                    for index, operation in enumerate(operations):
                        label = _op_label(operation, index)
                        if operation == "delete" and index == 0 and (cfg.concurrency > 1 or scenario == "concurrent_delete"):
                            count = max(2, cfg.concurrency) if scenario == "concurrent_delete" else cfg.concurrency
                            results = _execute_concurrent(cfg, api, warm_copy, operation, label, count)
                            for result in results:
                                rows.append(_row(scenario, "warmup", 0, True, label, result))
                        else:
                            result = _call(cfg, api, warm_copy, operation, label)
                            rows.append(_row(scenario, "warmup", 0, True, label, result))
            if not destructive and cfg.warmup > 0:
                warm_copy = copies.get("r1")
                if warm_copy is not None:
                    for index, operation in enumerate(operations):
                        label = _op_label(operation, index)
                        result = _call(cfg, api, warm_copy, operation, label)
                        rows.append(_row(scenario, "r1", 0, True, label, result))

            for round_no in range(1, cfg.repeat + 1):
                copy_name = "r%d" % round_no
                copy = copies.get(copy_name)
                if copy is None:
                    scenario_result["errors"].append("缺少测量 copy %s" % copy_name)
                    continue
                if copy.get("errors"):
                    scenario_result["errors"].append("%s 生成错误：%s" % (copy_name, copy.get("errors")))
                for index, operation in enumerate(operations):
                    label = _op_label(operation, index)
                    if operation == "delete" and index == 0 and (cfg.concurrency > 1 or scenario == "concurrent_delete"):
                        count = max(2, cfg.concurrency) if scenario == "concurrent_delete" else cfg.concurrency
                        results = _execute_concurrent(cfg, api, copy, operation, label, count)
                        for result in results:
                            rows.append(_row(scenario, copy_name, round_no, False, label, result))
                    else:
                        result = _call(cfg, api, copy, operation, label)
                        rows.append(_row(scenario, copy_name, round_no, False, label, result))
            scenario_rows.append(scenario_result)
    finally:
        api.close()
        redis_ops.close()

    measured = [row for row in rows if not row["warmup"]]
    stats: List[Dict[str, object]] = []
    labels: List[Tuple[str, str]] = []
    for row in measured:
        key = (str(row["scenario"]), str(row["operation"]))
        if key not in labels:
            labels.append(key)
    for scenario, operation in labels:
        values = [float(row["duration_ms"]) for row in measured
                  if row["scenario"] == scenario and row["operation"] == operation]
        statuses: Dict[str, int] = {}
        for row in measured:
            if row["scenario"] == scenario and row["operation"] == operation:
                key = str(row["status"])
                statuses[key] = statuses.get(key, 0) + 1
        item = {"scenario": scenario, "operation": operation}
        item.update(summarize(values))
        item["http_status"] = statuses
        stats.append(item)

    transport_failures = len([row for row in measured if row.get("failure_kind") == "transport"])
    http_failures = len([row for row in measured if row.get("failure_kind") == "http"])
    allowed_http_responses = len([row for row in measured if row.get("failure_kind") == "allowed_http"])
    failed_requests = transport_failures + http_failures
    return {
        "started_at": started_at,
        "finished_at": M.utc_now_iso(),
        "namespace": cfg.namespace,
        "environment": environment,
        "scenarios": scenario_rows,
        "rows": rows,
        "stats": stats,
        "transport_failures": transport_failures,
        "http_failures": http_failures,
        "allowed_http_responses": allowed_http_responses,
        "failed_requests": failed_requests,
        "measured_requests": len(measured),
    }
