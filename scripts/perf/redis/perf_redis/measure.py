"""Measurement: warmup, repeated rounds, optional concurrency.

Each destructive scenario has one copy per warmup/round, generated up front, so a
DELETE round never measures a half-empty session. Read scenarios reuse one copy.

Every scenario and the whole measure run have a monotonic budget. Each request
timeout is capped by the remaining budget, so one hung DELETE cannot keep the
30s HTTP ceiling while the budget is nearly gone. Exhaustion keeps completed
rows/stats and marks the scenario budget_exhausted; later scenarios are skipped.
"""

from __future__ import annotations

import concurrent.futures
import time
from typing import Callable, Dict, List, Optional, Tuple

from . import derive as D
from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps, error_detail
from .stats import summarize


def _monotonic() -> float:
    return time.monotonic()


class _Budget:
    """Monotonic measurement budget. A value of 0 disables that limit."""

    def __init__(self, total_seconds: float, scenario_seconds: float,
                 clock: Optional[Callable[[], float]] = None) -> None:
        self.total_seconds = float(total_seconds)
        self.scenario_seconds = float(scenario_seconds)
        self._clock = clock or _monotonic
        self._started = self._clock()
        self.total_deadline: Optional[float] = (
            self._started + self.total_seconds if self.total_seconds > 0 else None)
        self.scenario_deadline: Optional[float] = None

    def start_scenario(self) -> None:
        now = self._clock()
        deadline = now + self.scenario_seconds if self.scenario_seconds > 0 else None
        if self.total_deadline is not None:
            deadline = self.total_deadline if deadline is None else min(deadline, self.total_deadline)
        self.scenario_deadline = deadline

    def remaining(self) -> Optional[float]:
        deadlines = [d for d in (self.total_deadline, self.scenario_deadline) if d is not None]
        if not deadlines:
            return None
        return max(0.0, min(deadlines) - self._clock())

    def exhausted_kind(self) -> str:
        now = self._clock()
        if self.total_deadline is not None and now >= self.total_deadline:
            return "total"
        if self.scenario_deadline is not None and now >= self.scenario_deadline:
            return "scenario"
        return ""

    def expired(self) -> bool:
        remaining = self.remaining()
        return remaining is not None and remaining <= 0.0

    def total_exhausted(self) -> bool:
        return self.total_deadline is not None and self._clock() >= self.total_deadline

    def elapsed(self) -> float:
        return max(0.0, self._clock() - self._started)


def _request_timeout(cfg: Config, budget: _Budget) -> Optional[float]:
    remaining = budget.remaining()
    if remaining is None:
        return None
    if remaining <= 0.0:
        return 0.0
    return min(float(remaining), cfg.http_timeout)


def _health_timeout(cfg: Config, budget: _Budget) -> Optional[float]:
    timeout = _request_timeout(cfg, budget)
    if timeout is not None and timeout <= 0.0:
        return 0.001
    return timeout


def _mark_budget_timeout(cfg: Config, budget: _Budget, timeout: Optional[float],
                         result: Dict[str, object]) -> Dict[str, object]:
    if result.get("timed_out") and timeout is not None and timeout < cfg.http_timeout:
        kind = budget.exhausted_kind() or "budget"
        result["budget_error"] = kind
        result["transport_error"] = "budget_exhausted(%s): %s" % (
            kind, result.get("transport_error") or "request timed out")
    return result


def _skipped_entry(scenario: str, copy_name: str, round_no: int, warmup: bool,
                   label: str, budget: _Budget, detail: str = "") -> Dict[str, object]:
    return {
        "scenario": scenario,
        "copy": copy_name,
        "round": round_no,
        "warmup": warmup,
        "operation": label,
        "reason": "budget_exhausted",
        "budget_kind": budget.exhausted_kind(),
        "detail": detail or "budget exhausted; request not sent",
    }

READ_OPERATIONS = {
    "history",
    "history_before",
    "history_secondary",
    "history_after_window",
    "history_cap_retry",
    "history_data_missing",
    "history_data_restored",
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


def _call(cfg: Config, api: ApiClient, copy: Dict[str, object], operation: str, label: str,
          timeout: Optional[float] = None) -> Dict[str, object]:
    session_id = str(copy.get("session_id"))
    if operation == "delete":
        return api.delete_session(session_id, operation=label, timeout=timeout)
    if operation in {
        "history", "history_secondary", "history_after_window", "history_cap_retry",
        "history_data_missing", "history_data_restored",
    }:
        return api.history(session_id, timeout=timeout, operation=operation)
    if operation == "history_before":
        transcript_entries = int(copy.get("transcript_entries", 0))
        before = max(1, transcript_entries - 20 + 1)
        return api.history(session_id, before=before, timeout=timeout, operation=operation)
    if operation == "context":
        return api.context(session_id, timeout=timeout)
    if operation == "runs_current":
        return api.runs_current(session_id, timeout=timeout)
    if operation == "runs_list":
        return api.runs_list(timeout=timeout)
    if operation == "startup_resume":
        return api.startup_resume(timeout=timeout)
    if operation == "health":
        return api.health(timeout=timeout)
    if operation == "kb_status":
        return api.kb_status(timeout=timeout)
    if operation == "kb_reload":
        return api.kb_reload(timeout=timeout)
    run = _pick_run(copy)
    if run is None:
        return {
            "operation": label,
            "method": "GET",
            "path": "",
            "status": None,
            "ok": False,
            "duration_ms": 0.0,
            "transport_error": "copy has no run (missing runId)",
            "timed_out": False,
            "request_timeout_s": timeout,
            "server_timing": "",
            "business": {},
            "body_keys": [],
            "text_prefix": "",
        }
    run_id = str(run.get("run_id"))
    if operation == "run_detail":
        return api.run_detail(run_id, timeout=timeout)
    if operation == "events_probe":
        return api.events_probe(run_id, timeout=timeout)
    if operation == "cancel_status":
        return api.cancel_status(run_id, timeout=timeout)
    if operation == "pending":
        return api.pending(run_id, timeout=timeout)
    raise ValueError("unknown operation: %s" % operation)


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
        "budget_error": str(result.get("budget_error") or ""),
        "timed_out": bool(result.get("timed_out")),
        "request_timeout_s": result.get("request_timeout_s"),
        "duration_ms": float(result.get("duration_ms") or 0.0),
        "transport_error": transport_error,
        "server_timing": result.get("server_timing", ""),
        "business": result.get("business", {}),
        "body_keys": result.get("body_keys", []),
        "text_prefix": result.get("text_prefix", ""),
    }


def _execute_concurrent(cfg: Config, api: ApiClient, budget: _Budget, copy: Dict[str, object],
                        operation: str, label: str, count: int) -> List[Dict[str, object]]:
    workers = max(1, int(count))

    def call_one() -> Optional[Dict[str, object]]:
        timeout = _request_timeout(cfg, budget)
        if timeout is not None and timeout <= 0.0:
            return None
        return _mark_budget_timeout(cfg, budget, timeout,
                                    _call(cfg, api, copy, operation, label, timeout=timeout))

    results: List[Dict[str, object]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(call_one) for _ in range(workers)]
        for future in futures:
            try:
                result = future.result()
            except Exception as exc:
                result = {
                    "operation": label,
                    "status": None,
                    "ok": False,
                    "duration_ms": 0.0,
                    "transport_error": error_detail(exc),
                    "business": {},
                    "body_keys": [],
                    "text_prefix": "",
                }
            if result is not None:
                results.append(result)
    return results


ACTIVITY_SCENARIOS = {
    "session_activity", "session_activity_guard", "session_activity_run_cap",
    "session_activity_enumeration_cap", "session_activity_data_key",
}
ACTIVITY_FIXTURE_TTL_SECONDS = 3600


def _activity_background_keys(cfg: Config, plan: Dict[str, object]) -> List[str]:
    bucket = str(plan.get("background") or "")
    if not bucket:
        return []
    return [K.background_key(cfg.namespace, bucket, index) for index in range(cfg.sizes_for(bucket))]


def _activity_ttl_keys(scenario: str, keys: Dict[str, object]) -> List[str]:
    if scenario in ("session_activity_run_cap", "session_activity_enumeration_cap"):
        return [str(keys["data_key"])] if keys["data_key"] else []
    return list(keys["renewable"])


def _activity_snapshot(cfg: Config, ops: RedisOps, scenario: str, plan: Dict[str, object],
                       copy: Dict[str, object], operation: str, phase: str, warmup: bool,
                       setup: Optional[Dict[str, object]] = None) -> Dict[str, object]:
    keys = D.activity_keys(cfg, scenario, plan, copy)
    background = _activity_background_keys(cfg, plan)
    sentinel = keys["sentinel"]
    sentinel_evidence: Dict[str, object] = dict(sentinel) if isinstance(sentinel, dict) else {}
    if sentinel_evidence.get("key") and sentinel_evidence.get("member"):
        sentinel_evidence["score"] = ops.zscore(str(sentinel_evidence["key"]), str(sentinel_evidence["member"]))
    return {
        "copy": str(copy.get("copy") or ""),
        "operation": operation,
        "phase": phase,
        "warmup": bool(warmup),
        "observed_at_ms": int(time.time() * 1000),
        "target": "secondary" if operation == "history_secondary" else "primary",
        "ttl": ops.ttl_many(_activity_ttl_keys(scenario, keys)),
        "lease_ttl": ops.ttl_many(keys["leases"]),
        "throttle_ttl": ops.ttl(keys["throttle"]),
        "guard_ttl": ops.ttl(keys["guard"]),
        "lock_ttl": ops.ttl(keys["lock"]),
        "background_ttl": ops.ttl_many(background),
        "data_key": keys["data_key"],
        "sentinel": sentinel_evidence,
        "setup": dict(setup or {}),
    }


def _activity_prepare(cfg: Config, ops: RedisOps, scenario: str, plan: Dict[str, object],
                      copy: Dict[str, object], operation: str, warmup: bool,
                      scenario_result: Dict[str, object]) -> Optional[Dict[str, object]]:
    if scenario not in ACTIVITY_SCENARIOS:
        return None
    keys = D.activity_keys(cfg, scenario, plan, copy)
    setup: Dict[str, object] = {"window_reset": False, "errors": []}
    shorten = operation in {
        "history", "history_after_window", "history_cap_retry",
        "history_data_missing", "history_data_restored", "startup_resume",
    }
    if shorten:
        expiry = ops.expire_many(_activity_ttl_keys(scenario, keys), ACTIVITY_FIXTURE_TTL_SECONDS)
        setup["fixture_ttl_seconds"] = ACTIVITY_FIXTURE_TTL_SECONDS
        setup["shortened_keys"] = len([value for value in expiry.values() if value == 1])
        setup["errors"] = [key for key, value in expiry.items() if value == -3]
        background = _activity_background_keys(cfg, plan)
        background_expiry = ops.expire_many(background, ACTIVITY_FIXTURE_TTL_SECONDS)
        setup["background_missing"] = [key for key, value in background_expiry.items() if value == 0]
        setup["errors"].extend(key for key, value in background_expiry.items() if value == -3)
    if operation in ("history_after_window", "startup_resume") or (scenario == "session_activity" and operation == "history"):
        deleted = ops.delete_keys([keys["throttle"]])
        setup["window_reset"] = True
        setup["window_delete_errors"] = deleted.get("errors") or []
    if operation == "history_data_missing":
        backup = ops.hgetall(keys["data_key"])
        scenario_result["activity_data_backup"] = backup
        setup["data_backup_entries"] = len(backup)
        if not backup:
            setup["errors"].append("transcript dataKey backup was empty or unreadable")
        deleted = ops.delete_keys([keys["data_key"]])
        setup["data_key_deleted"] = not (deleted.get("errors") or [])
        setup["errors"].extend(str(item) for item in (deleted.get("errors") or []))
    if operation == "history_data_restored":
        backup = scenario_result.get("activity_data_backup") or {}
        setup["data_restore_entries"] = len(backup)
        if backup:
            ops.hset_mapping(keys["data_key"], backup, ACTIVITY_FIXTURE_TTL_SECONDS)
        else:
            setup["errors"].append("no transcript dataKey backup was available for restoration")
    return _activity_snapshot(cfg, ops, scenario, plan, copy, operation, "before", warmup, setup)


def measure(cfg: Config, manifest: Dict[str, object], selected: List[str]) -> Dict[str, object]:
    cfg.require_write_safety()
    started_at = M.utc_now_iso()
    budget = _Budget(cfg.measure_total_timeout, cfg.measure_scenario_timeout)
    rows: List[Dict[str, object]] = []
    scenario_rows: List[Dict[str, object]] = []
    api = ApiClient(cfg)
    secondary_api = ApiClient(cfg, cfg.secondary_app_base_url) if cfg.secondary_app_base_url else None
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
            environment["redis"] = {"error": error_detail(exc)}

        health = api.health(timeout=_health_timeout(cfg, budget))
        environment["app"] = {
            "base_url": cfg.app_base_url,
            "health_status": health.get("status"),
            "health_business": health.get("business"),
            "reachable": health.get("status") is not None,
        }

        def run_operations(scenario: str, copy_name: str, round_no: int, warmup: bool,
                           copy: Dict[str, object], operations: List[str],
                           scenario_result: Dict[str, object]) -> None:
            plan = (manifest.get("scenarios") or {}).get(scenario, {}).get("plan") or {}
            for index, operation in enumerate(operations):
                label = _op_label(operation, index)
                timeout = _request_timeout(cfg, budget)
                if timeout is not None and timeout <= 0.0:
                    scenario_result["skipped"].append(
                        _skipped_entry(scenario, copy_name, round_no, warmup, label, budget))
                    continue
                before = _activity_prepare(cfg, redis_ops, scenario, plan, copy, operation, warmup, scenario_result)
                if before is not None:
                    scenario_result["activity_evidence"].append(before)
                request_api = api
                if operation == "history_secondary":
                    if secondary_api is None:
                        raise RuntimeError("history_secondary requires APP_SECONDARY_BASE_URL")
                    request_api = secondary_api
                concurrent_scenario = (operation == "delete" and index == 0
                                       and (cfg.concurrency > 1 or scenario == "concurrent_delete"))
                if concurrent_scenario:
                    count = max(2, cfg.concurrency) if scenario == "concurrent_delete" else cfg.concurrency
                    results = _execute_concurrent(cfg, request_api, budget, copy, operation, label, count)
                    for result in results:
                        rows.append(_row(scenario, copy_name, round_no, warmup, label, result))
                        if result.get("budget_error"):
                            scenario_result["budget_errors"].append({
                                "copy": copy_name, "round": round_no, "operation": label,
                                "budget_kind": result.get("budget_error"),
                            })
                    if len(results) < count:
                        scenario_result["skipped"].append(_skipped_entry(
                            scenario, copy_name, round_no, warmup, label, budget,
                            "budget allowed only %d/%d concurrent requests to execute" % (len(results), count)))
                else:
                    result = _call(cfg, request_api, copy, operation, label, timeout=timeout)
                    result = _mark_budget_timeout(cfg, budget, timeout, result)
                    rows.append(_row(scenario, copy_name, round_no, warmup, label, result))
                    if result.get("budget_error"):
                        scenario_result["budget_errors"].append({
                            "copy": copy_name, "round": round_no, "operation": label,
                            "budget_kind": result.get("budget_error"),
                        })
                if scenario in ACTIVITY_SCENARIOS:
                    scenario_result["activity_evidence"].append(_activity_snapshot(
                        cfg, redis_ops, scenario, plan, copy, operation, "after", warmup))

        for scenario in selected:
            entry = (manifest.get("scenarios") or {}).get(scenario)
            if not isinstance(entry, dict) or entry.get("status") != "generated":
                scenario_rows.append({
                    "scenario": scenario,
                    "status": "skipped",
                    "reason": (entry or {}).get("reason", "not generated or unsupported"),
                })
                continue
            if budget.total_exhausted():
                scenario_rows.append({
                    "scenario": scenario,
                    "status": "skipped",
                    "reason": "budget_exhausted",
                    "budget_kind": "total",
                    "detail": "total measure deadline of %ss exhausted; scenario not started"
                              % cfg.measure_total_timeout,
                })
                continue
            budget.start_scenario()
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
                "skipped": [],
                "budget_errors": [],
                "activity_evidence": [],
                "executed_requests": 0,
            }
            first_row = len(rows)
            if destructive and cfg.warmup > 0:
                warm_copy = copies.get("warmup")
                if warm_copy is None:
                    scenario_result["errors"].append("missing warmup copy")
                else:
                    run_operations(scenario, "warmup", 0, True, warm_copy, operations, scenario_result)
            if not destructive and cfg.warmup > 0:
                warm_copy = copies.get("r1")
                if warm_copy is not None:
                    run_operations(scenario, "r1", 0, True, warm_copy, operations, scenario_result)

            for round_no in range(1, cfg.repeat + 1):
                copy_name = "r%d" % round_no
                copy = copies.get(copy_name)
                if copy is None:
                    scenario_result["errors"].append("missing measurement copy %s" % copy_name)
                    continue
                if copy.get("errors"):
                    scenario_result["errors"].append("%s generation errors: %s" % (copy_name, copy.get("errors")))
                run_operations(scenario, copy_name, round_no, False, copy, operations, scenario_result)

            scenario_result["executed_requests"] = len(rows) - first_row
            if scenario_result["skipped"] or scenario_result["budget_errors"]:
                scenario_result["status"] = "budget_exhausted"
                scenario_result["reason"] = (
                    "measure budget exhausted (PERF_MEASURE_SCENARIO_TIMEOUT_SECONDS="
                    "%ss / PERF_MEASURE_TOTAL_TIMEOUT_SECONDS=%ss); completed results were retained"
                    % (cfg.measure_scenario_timeout, cfg.measure_total_timeout))
                scenario_result["partial"] = scenario_result["executed_requests"] > 0
            scenario_rows.append(scenario_result)
    finally:
        api.close()
        if secondary_api is not None:
            secondary_api.close()
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

    total_exhausted = budget.total_exhausted()
    exhausted_names = [str(item.get("scenario")) for item in scenario_rows
                       if item.get("status") == "budget_exhausted"]
    skipped_names = [str(item.get("scenario")) for item in scenario_rows
                     if item.get("status") == "skipped" and item.get("reason") == "budget_exhausted"]
    budget_info = {
        "total_seconds": cfg.measure_total_timeout,
        "scenario_seconds": cfg.measure_scenario_timeout,
        "elapsed_seconds": round(budget.elapsed(), 3),
        "total_exhausted": total_exhausted,
        "exhausted": bool(exhausted_names or skipped_names),
        "exhausted_kind": "total" if total_exhausted else ("scenario" if exhausted_names else ""),
        "budget_exhausted_scenarios": exhausted_names,
        "skipped_scenarios": skipped_names,
    }
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
        "budget": budget_info,
        "budget_exhausted_scenarios": len(exhausted_names),
        "skipped_scenarios": len(skipped_names),
    }
