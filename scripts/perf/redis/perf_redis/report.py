"""JSON + CSV writers (UTF-8, no machine-specific paths)."""

from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Dict, List, Optional

from . import manifest as M
from .envcfg import Config


def _write_csv(path: Path, fieldnames: List[str], rows: List[Dict[str, object]]) -> None:
    M.ensure_dir(path.parent)
    handle, temp_name = None, str(path) + ".tmp"
    try:
        with open(temp_name, "w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fieldnames, extrasaction="ignore")
            writer.writeheader()
            for row in rows:
                writer.writerow({key: _cell(row.get(key)) for key in fieldnames})
        import os

        os.replace(temp_name, str(path))
    finally:
        import os

        if os.path.exists(temp_name):
            try:
                os.remove(temp_name)
            except OSError:
                pass


def _cell(value: object) -> object:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return value


def request_rows(result: Dict[str, object]) -> List[Dict[str, object]]:
    rows: List[Dict[str, object]] = []
    for row in result.get("rows", []):
        business = row.get("business") or {}
        if not isinstance(business, dict):
            business = {}
        rows.append({
            "scenario": row.get("scenario"),
            "copy": row.get("copy"),
            "round": row.get("round"),
            "warmup": row.get("warmup"),
            "operation": row.get("operation"),
            "status": row.get("status"),
            "ok": row.get("ok"),
            "accepted": row.get("accepted"),
            "failure_kind": row.get("failure_kind"),
            "budget_error": row.get("budget_error"),
            "duration_ms": row.get("duration_ms"),
            "complete": business.get("complete"),
            "found": business.get("found"),
            "aborted": business.get("aborted"),
            "phase": business.get("phase"),
            "deferred_count": len(business.get("deferred") or []),
            "error_count": len(business.get("errors") or []),
            "warning_count": len(business.get("warnings") or []),
            "transport_error": row.get("transport_error"),
            "server_timing": row.get("server_timing"),
        })
    return rows


def summary_rows(result: Dict[str, object]) -> List[Dict[str, object]]:
    rows: List[Dict[str, object]] = []
    for item in result.get("stats", []):
        statuses = item.get("http_status") or {}
        rows.append({
            "scenario": item.get("scenario"),
            "operation": item.get("operation"),
            "count": item.get("count"),
            "mean_ms": item.get("mean"),
            "p50_ms": item.get("p50"),
            "p95_ms": item.get("p95"),
            "p99_ms": item.get("p99"),
            "min_ms": item.get("min"),
            "max_ms": item.get("max"),
            "http_status": json.dumps(statuses, ensure_ascii=False),
            "status_200": statuses.get("200", 0),
            "status_503": statuses.get("503", 0),
        })
    return rows


def verify_rows(result: Dict[str, object]) -> List[Dict[str, object]]:
    return list(result.get("checks", []))


def cleanup_rows(result: Dict[str, object]) -> List[Dict[str, object]]:
    rows: List[Dict[str, object]] = []
    plan = result.get("plan") or {}
    rows.append({
        "kind": "plan",
        "key": "-",
        "action": "report",
        "status": "info",
        "detail": "exact=%s present=%s backgrounds=%s"
                  % (plan.get("exact_keys"), plan.get("exact_present"), len(plan.get("background") or [])),
    })
    for item in result.get("rows", []) or []:
        rows.append({
            "kind": item.get("kind"),
            "key": item.get("key"),
            "action": item.get("action"),
            "status": item.get("status"),
            "detail": item.get("detail"),
        })
    for item in result.get("background", []) or []:
        rows.append({
            "kind": "background",
            "key": item.get("bucket"),
            "action": "delete_exact",
            "status": "pass" if item.get("complete") else "fail",
            "detail": "planned=%s deleted=%s checked=%s remaining=%s unknown=%s"
                      % (item.get("planned"), item.get("deleted"), item.get("checked"),
                         item.get("remaining"), item.get("unknown")),
        })
    for item in result.get("files", []) or []:
        rows.append({
            "kind": "file",
            "key": item.get("storage_key"),
            "action": item.get("action"),
            "status": item.get("status"),
            "detail": item.get("detail"),
        })
    for item in result.get("feedback", []) or []:
        if "ok" in item:
            status = "pass" if item.get("ok") else "fail"
            detail = "http=%s %s" % (item.get("status"), item.get("error") or "")
        else:
            status = "info"
            detail = "dry-run: only feedback rows pending cancellation are listed"
        rows.append({
            "kind": "feedback",
            "key": item.get("message_id"),
            "action": "cancel",
            "status": status,
            "detail": detail,
        })
    for item in (result.get("plan") or {}).get("skipped", []) or []:
        rows.append({
            "kind": "skipped",
            "key": item.get("file_id"),
            "action": "skip",
            "status": "info",
            "detail": item.get("reason"),
        })
    if result.get("executed"):
        for item in result.get("remaining", {}).get("exact", []) or []:
            rows.append({
                "kind": "remaining_exact",
                "key": item,
                "action": "report",
                "status": "fail",
                "detail": "key still exists after cleanup",
            })
    return rows


def write_cleanup_only(workspace: Dict[str, Path], cleanup_result: Dict[str, object]) -> None:
    M.atomic_write_json(workspace["cleanup_json"], cleanup_result)
    _write_csv(
        workspace["cleanup_csv"],
        ["kind", "key", "action", "status", "detail"],
        cleanup_rows(cleanup_result),
    )
    existing = M.read_json(workspace["results"])
    if isinstance(existing, dict):
        existing["cleanup"] = cleanup_result
        existing["cleanup_complete"] = cleanup_result.get("complete")
        acceptance = existing.get("acceptance") if isinstance(existing.get("acceptance"), dict) else {}
        acceptance["cleanup_complete"] = cleanup_result.get("complete")
        existing["acceptance"] = acceptance
        M.atomic_write_json(workspace["results"], existing)


def write_all(cfg: Config, workspace: Dict[str, Path], measure_result: Optional[Dict[str, object]],
              verify_result: Optional[Dict[str, object]], cleanup_result: Optional[Dict[str, object]],
              git_info: Dict[str, object], extra: Optional[Dict[str, object]] = None,
              verify_phase: str = "precleanup") -> Dict[str, object]:
    post_cleanup = verify_phase == "postcleanup"
    existing = M.read_json(workspace["results"]) if post_cleanup else None
    if not isinstance(existing, dict):
        existing = {}
    combined: Dict[str, object] = {
        "tool": "ai-redis-perf",
        "version": "1.0.0",
        "generated_at": M.utc_now_iso(),
        "environment": {
            "config": cfg.describe_for_log(),
            "git": git_info,
            "namespace": cfg.namespace,
            "redis_mode": cfg.mode,
            "identity": {"tenantId": cfg.tenant_id, "userId": cfg.user_id},
            "output_dir": str(Path(cfg.output_dir)),
        },
    }
    if measure_result is not None:
        combined["measure"] = measure_result
        combined["stats"] = measure_result.get("stats", [])
        combined["scenarios"] = measure_result.get("scenarios", [])
        combined["requests"] = request_rows(measure_result)
        _write_csv(
            workspace["requests_csv"],
            ["scenario", "copy", "round", "warmup", "operation", "status", "ok", "accepted",
             "failure_kind", "budget_error", "duration_ms",
             "complete", "found", "aborted", "phase", "deferred_count", "error_count",
             "warning_count", "transport_error", "server_timing"],
            combined["requests"],
        )
        _write_csv(
            workspace["summary_csv"],
            ["scenario", "operation", "count", "mean_ms", "p50_ms", "p95_ms", "p99_ms",
             "min_ms", "max_ms", "http_status", "status_200", "status_503"],
            summary_rows(measure_result),
        )
    pre_counts: Dict[str, object] = {}
    post_counts: Dict[str, object] = {}
    if verify_result is not None:
        counts = verify_result.get("counts", {})
        if post_cleanup:
            post_counts = counts
            combined["post_cleanup_verify"] = verify_result
            combined["post_cleanup_verify_counts"] = counts
            _write_csv(
                workspace["verify_postcleanup_csv"],
                ["scenario", "copy", "check", "status", "detail"],
                verify_rows(verify_result),
            )
            M.atomic_write_json(workspace["verify_postcleanup_json"], verify_result)
            previous_pre = existing.get("pre_cleanup_verify")
            if not isinstance(previous_pre, dict):
                candidate = existing.get("verify")
                previous_pre = candidate if isinstance(candidate, dict) and candidate.get("phase") != "post_cleanup" else None
            if previous_pre is not None:
                pre_counts = existing.get("pre_cleanup_verify_counts") or previous_pre.get("counts", {})
                combined["pre_cleanup_verify"] = previous_pre
                combined["pre_cleanup_verify_counts"] = pre_counts
                combined["verify"] = previous_pre
                combined["verify_counts"] = pre_counts
            else:
                combined["verify"] = verify_result
                combined["verify_counts"] = counts
        else:
            pre_counts = counts
            combined["pre_cleanup_verify"] = verify_result
            combined["pre_cleanup_verify_counts"] = counts
            combined["verify"] = verify_result
            combined["verify_counts"] = counts
            _write_csv(
                workspace["verify_csv"],
                ["scenario", "copy", "check", "status", "detail"],
                verify_rows(verify_result),
            )
            M.atomic_write_json(workspace["verify_json"], verify_result)
            _write_csv(
                workspace["verify_precleanup_csv"],
                ["scenario", "copy", "check", "status", "detail"],
                verify_rows(verify_result),
            )
            M.atomic_write_json(workspace["verify_precleanup_json"], verify_result)
    if cleanup_result is not None:
        combined["cleanup"] = cleanup_result
        combined["cleanup_complete"] = cleanup_result.get("complete")
        M.atomic_write_json(workspace["cleanup_json"], cleanup_result)
        _write_csv(
            workspace["cleanup_csv"],
            ["kind", "key", "action", "status", "detail"],
            cleanup_rows(cleanup_result),
        )
    if extra:
        combined["extra"] = extra
    combined["acceptance"] = {
        "note": "响应提前返回 != 最终清理完成；以 verify + cleanup 的实测状态为准",
        "verify_counts": pre_counts or combined.get("verify_counts", {}),
        "pre_cleanup_verify_counts": pre_counts,
        "post_cleanup_verify_counts": post_counts,
        "cleanup_complete": (cleanup_result or {}).get("complete"),
        "failures": {
            "transport": (measure_result or {}).get("transport_failures"),
            "http": (measure_result or {}).get("http_failures"),
            "allowed_http": (measure_result or {}).get("allowed_http_responses"),
            "total": (measure_result or {}).get("failed_requests"),
        },
    }
    M.atomic_write_json(workspace["results"], combined)
    return combined
