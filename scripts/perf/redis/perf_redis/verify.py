"""Post-conditions derived from the real delete semantics.

Hard rules (from AiSessionPurgeService / RunStore / SessionPurgeGuard):
  * complete=true  => every owned session key is gone; guard/pending/ver/gen are
    intentional tombstones and must NOT be reported as residue;
  * complete=false => a deferred/active run keeps every run key generation
    actually wrote (pending/claim/idem/req/decided exist only for runs with
    pending entries; lease only when lease_held); pending+guard and the
    discovery keys stay so a retry can enumerate them;
  * aborted=true in pending/enumerate/guard/database => nothing in Redis was deleted
    (only when no round completed a deletion); later delete-phase aborts use retained checks;
    an earlier aborted round followed by complete is verified as a successful deletion;
  * xack (120s) / xtrim / session lock are deliberate leftovers, never failures.
  * complete responses are followed by a bounded memory-verification wait; timeout/unreadable
    is reported as unknown rather than mistaking in-flight writes for deletion failure.

Unreadable state is reported as `unknown`, never as pass.
"""

from __future__ import annotations

import json
import time
from typing import Dict, List, Optional, Tuple

from . import derive as D
from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps, RedisUnavailable, error_detail


def _check(checks: List[Dict[str, object]], scenario: str, copy: str, name: str,
           status: str, detail: str) -> None:
    checks.append({
        "scenario": scenario,
        "copy": copy,
        "check": name,
        "status": status,
        "detail": detail,
    })


def _sample(items: List[str], limit: int = 8) -> str:
    if len(items) <= limit:
        return ",".join(items)
    return "%s,...(+%d)" % (",".join(items[:limit]), len(items) - limit)


MEMORY_VERIFY_TIMEOUT_SECONDS = 65
MEMORY_VERIFY_POLL_SECONDS = 0.25


def _wait_for_memory_verify(ops: RedisOps, pending_key: str) -> str:
    deadline = time.monotonic() + MEMORY_VERIFY_TIMEOUT_SECONDS
    while True:
        try:
            raw = ops.get_str_strict(pending_key)
        except Exception:
            return "unknown"
        if not raw:
            return "ready"
        try:
            pending = json.loads(raw)
        except Exception:
            return "unreadable"
        if not isinstance(pending, dict) or pending.get("verifyAtMs") is None:
            return "unreadable"
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return "timeout"
        time.sleep(min(MEMORY_VERIFY_POLL_SECONDS, remaining))


def _delete_rows(results: Dict[str, object], scenario: str) -> Dict[str, List[Dict[str, object]]]:
    by_copy: Dict[str, List[Dict[str, object]]] = {}
    for row in results.get("rows", []):
        if row.get("warmup"):
            continue
        if row.get("scenario") != scenario:
            continue
        if not str(row.get("operation", "")).startswith("delete"):
            continue
        by_copy.setdefault(str(row.get("copy")), []).append(row)
    for rows in by_copy.values():
        rows.sort(key=lambda item: (int(item.get("round", 0)), str(item.get("operation"))))
    return by_copy


def _merge(rows: List[Dict[str, object]]) -> Dict[str, object]:
    statuses: List[object] = [row.get("status") for row in rows]
    transport = [str(row.get("transport_error") or "") for row in rows if row.get("transport_error")]
    complete_values: List[bool] = []
    aborted_values: List[bool] = []
    deferred: List[str] = []
    runs: Dict[str, Dict[str, object]] = {}
    errors: List[str] = []
    warnings: List[str] = []
    complete_business: Optional[Dict[str, object]] = None
    last_business: Optional[Dict[str, object]] = None
    for row in rows:
        business = row.get("business") or {}
        if not isinstance(business, dict):
            continue
        last_business = business
        if business.get("complete") is True:
            complete_values.append(True)
            complete_business = business
        elif business.get("complete") is False:
            complete_values.append(False)
        if business.get("aborted") is True:
            aborted_values.append(True)
        for run_id in business.get("deferred") or []:
            if run_id and run_id not in deferred:
                deferred.append(str(run_id))
        for run in business.get("runs") or []:
            if not isinstance(run, dict):
                continue
            run_id = str(run.get("runId"))
            merged = runs.setdefault(run_id, {"deleted": False, "deferred": False})
            if run.get("deleted") is True:
                merged["deleted"] = True
            if run.get("deferred") is True:
                merged["deferred"] = True
            merged["status"] = run.get("status")
        for item in business.get("errors") or []:
            errors.append(str(item))
        for item in business.get("warnings") or []:
            warnings.append(str(item))
    return {
        "statuses": statuses,
        "transport_errors": transport,
        "complete_any": any(complete_values),
        "complete_all": bool(complete_values) and all(complete_values),
        "complete_last": complete_values[-1] if complete_values else None,
        "aborted": any(aborted_values),
        "deferred": deferred,
        "runs": runs,
        "errors": errors,
        "warnings": warnings,
        "business": complete_business or last_business or {},
    }


def _check_background(cfg: Config, ops: RedisOps, checks: List[Dict[str, object]], scenario: str,
                      copy_name: str, plan: Dict[str, object],
                      background_counts: Dict[str, int]) -> None:
    bucket = plan.get("background")
    if not bucket:
        return
    count = background_counts.get(str(bucket))
    if count is None:
        _check(checks, scenario, copy_name, "background_preserved", "unknown",
               "manifest has no planned/count for bucket %s; exact sampling is impossible" % bucket)
        return
    if count <= 0:
        _check(checks, scenario, copy_name, "background_preserved", "info",
               "bucket %s planned=0; no background keys to sample" % bucket)
        return
    probes = K.background_probe_keys(cfg.namespace, str(bucket), count)
    existence = ops.exists_many(probes)
    unknown = [key for key in probes if existence.get(key) is None]
    missing = [key for key in probes if existence.get(key) is False]
    if missing:
        _check(checks, scenario, copy_name, "background_preserved", "fail",
               "background keys missing from exact samples bucket=%s: %s" % (bucket, _sample(missing)))
    elif unknown:
        _check(checks, scenario, copy_name, "background_preserved", "unknown",
               "failed to read exact background samples bucket=%s: %s" % (bucket, _sample(unknown)))
    else:
        _check(checks, scenario, copy_name, "background_preserved", "pass",
               "first/middle/last background samples still exist bucket=%s (%d samples; not proof for the whole keyspace)"
               % (bucket, len(probes)))


def _check_files(cfg: Config, checks: List[Dict[str, object]], scenario: str, copy_name: str,
                 copy: Dict[str, object]) -> None:
    import os

    root = cfg.app_file_storage_path
    for item in copy.get("attachments") or []:
        if not isinstance(item, dict):
            continue
        storage_key = str(item.get("storage_key") or "")
        file_id = str(item.get("file_id") or "")
        if not storage_key:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "unknown",
                   "storageKey was not recorded (registration read or upload failed); file existence is unknown")
            continue
        if not root:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "unknown",
                   "PERF_APP_FILE_STORAGE_PATH is not configured; cannot check file key=%s" % storage_key)
            continue
        candidate = os.path.normpath(os.path.join(root, storage_key))
        root_norm = os.path.normpath(root)
        if not candidate.startswith(root_norm + os.sep) and candidate != root_norm:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "fail",
                   "storageKey escapes the configured storage root: %s" % storage_key)
            continue
        if os.path.exists(candidate):
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "fail",
                   "file still exists: %s" % candidate)
        else:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "pass",
                   "file deleted: %s" % storage_key)


def _check_feedback(api: ApiClient, checks: List[Dict[str, object]], scenario: str, copy_name: str,
                    copy: Dict[str, object]) -> None:
    rows = [item for item in copy.get("feedback") or [] if isinstance(item, dict)]
    if not rows:
        return
    result = api.feedback_list()
    if not result.get("ok"):
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "unknown",
               "failed to read /api/ai/feedback; DB rows cannot be confirmed: status=%s" % result.get("status"))
        return
    business = result.get("business") or {}
    message_ids = [str(item) for item in (business.get("messageIds") or [])]
    expected = [str(item.get("message_id")) for item in rows]
    remaining = [item for item in expected if item in message_ids]
    if remaining:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "fail",
               "feedback rows are still visible (within the list cap of 100): %s" % _sample(remaining))
    elif len(message_ids) >= 100:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "info",
               "feedback list reached the 100-row cap; absence for this session is unproven, check messageId manually")
    else:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "pass",
               "this session's feedback rows are absent from /api/ai/feedback (list cap 100; checked by messageId)")


def _check_slots(cfg: Config, checks: List[Dict[str, object]], scenario: str, copy_name: str,
                 copy: Dict[str, object]) -> None:
    if not cfg.is_cluster():
        return
    for run in copy.get("runs") or []:
        if not isinstance(run, dict):
            continue
        slot_check = run.get("slot_check") or {}
        if slot_check.get("single_slot") is True:
            continue
        _check(checks, scenario, copy_name, "run_%s_single_slot" % run.get("run_id"), "fail",
               "the 13 keys are not in one slot (Cluster multi-key DEL loses atomicity): %s" % slot_check.get("slots"))


def _check_bounded_existing(ops: RedisOps, checks: List[Dict[str, object]], scenario: str,
                            copy_name: str, name: str, keys: List[str], maximum: int = 86400) -> None:
    unique = list(dict.fromkeys(str(key) for key in keys if key))
    if not unique:
        return
    ttls = ops.ttl_many(unique)
    existing = [key for key, ttl in ttls.items() if ttl != -2]
    invalid = ["%s=%s" % (key, ttls.get(key)) for key in existing
               if not 0 < ttls.get(key, -3) <= maximum]
    unknown = [key for key in existing if ttls.get(key) == -3]
    if unknown:
        _check(checks, scenario, copy_name, name, "unknown",
               "TTL reads failed: %s" % _sample(unknown))
    elif invalid:
        _check(checks, scenario, copy_name, name, "fail",
               "existing keys require TTL in (0,%d]: %s" % (maximum, _sample(invalid)))
    elif existing:
        _check(checks, scenario, copy_name, name, "pass",
               "%d existing keys have TTL in (0,%d]" % (len(existing), maximum))


def _check_clean(cfg: Config, ops: RedisOps, api: ApiClient, checks: List[Dict[str, object]],
                 scenario: str, copy_name: str, plan: Dict[str, object], copy: Dict[str, object],
                 merged: Dict[str, object], background_counts: Dict[str, int]) -> None:
    keys = D.copy_keys(cfg, scenario, plan, copy)
    expected = list(keys["expected_absent_on_clean"])
    existence = ops.exists_many(expected)
    residue = [key for key in expected if existence.get(key) is True]
    unknown = [key for key in expected if existence.get(key) is None]
    if residue:
        _check(checks, scenario, copy_name, "owned_keys_absent", "fail",
               "complete=true but %d residual keys remain: %s" % (len(residue), _sample(residue)))
    elif unknown:
        _check(checks, scenario, copy_name, "owned_keys_absent", "unknown",
               "existence of %d keys cannot be confirmed (Redis reads failed): %s" % (len(unknown), _sample(unknown)))
    else:
        _check(checks, scenario, copy_name, "owned_keys_absent", "pass",
               "all %d session keys are absent" % len(expected))

    for run_id in keys["runs"]:
        info = (merged.get("runs") or {}).get(run_id)
        if not isinstance(info, dict):
            _check(checks, scenario, copy_name, "run_%s_reported" % run_id, "unknown",
                   "response did not list this runId (it may have been skipped by the ownership check)")
            continue
        if info.get("deferred"):
            _check(checks, scenario, copy_name, "run_%s_not_deferred" % run_id, "fail",
                   "complete=true but the run is marked deferred")
        else:
            _check(checks, scenario, copy_name, "run_%s_deleted" % run_id, "pass",
                   "response reports deleted=%s" % info.get("deleted"))

    guard = str(keys["guard"])
    pending = str(keys["pending"])
    guard_ttl = ops.ttl(guard)
    if 0 < guard_ttl <= 86400:
        _check(checks, scenario, copy_name, "guard_retained", "pass",
               "guard is intentionally retained with bounded session TTL=%ss" % guard_ttl)
    elif guard_ttl == -2:
        _check(checks, scenario, copy_name, "guard_retained", "fail",
               "complete=true but guard is absent (the source retains it)")
    elif guard_ttl == -3:
        _check(checks, scenario, copy_name, "guard_retained", "unknown",
               "guard TTL is unreadable (ttl=%s)" % guard_ttl)
    else:
        _check(checks, scenario, copy_name, "guard_retained", "fail",
               "guard must have a positive TTL no greater than 24h (ttl=%s)" % guard_ttl)

    pending_ttl = ops.ttl(pending)
    if pending_ttl == -2:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "info",
               "pending was removed by the 60s memory review (or ended before scheduling)")
    elif pending_ttl == -3:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "unknown",
               "pending TTL is unreadable")
    elif not 0 < pending_ttl <= 86400:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
               "pending must have a positive TTL no greater than 24h: ttl=%s" % pending_ttl)
    else:
        raw = ops.get_str(pending) or ""
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = None
        if not isinstance(parsed, dict):
            _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                   "pending JSON is invalid; verifyAtMs cannot be confirmed")
        elif parsed.get("verifyAtMs") is None:
            _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                   "complete=true but pending has no verifyAtMs (expected a 60s memory-review ledger)")
        else:
            try:
                due = ops.zscore_strict(K.PURGE_DUE_KEY, pending)
            except Exception:
                _check(checks, scenario, copy_name, "pending_verify_ledger", "unknown",
                       "failed to read the purge retry due member")
            else:
                due_ttl = ops.ttl(K.PURGE_DUE_KEY)
                if due is None:
                    _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                           "pending exists but is absent from ai:purge:retry:due (the review will not run)")
                elif due_ttl == -3:
                    _check(checks, scenario, copy_name, "pending_verify_ledger", "unknown",
                           "purge retry due TTL is unreadable")
                elif not 0 < due_ttl <= 86400:
                    _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                           "purge retry due index must have a positive TTL no greater than 24h: ttl=%s" % due_ttl)
                else:
                    _check(checks, scenario, copy_name, "pending_verify_ledger", "pass",
                           "pending TTL=%ss verifyAtMs=%s; due TTL=%ss and member is scheduled"
                           % (pending_ttl, parsed.get("verifyAtMs"), due_ttl))

    memory = keys["memory"]
    ttl_ver = ops.ttl(str(memory["ver"]))
    ttl_gen = ops.ttl(str(memory["gen"]))
    if 0 < ttl_ver <= 86400 and 0 < ttl_gen <= 86400:
        _check(checks, scenario, copy_name, "memory_tombstones", "pass",
               "gen/ver are intentional bounded tombstones (TTL=%s/%s)" % (ttl_gen, ttl_ver))
    elif ttl_ver == -2 or ttl_gen == -2:
        _check(checks, scenario, copy_name, "memory_tombstones", "fail",
               "gen/ver missing (gen=%s ver=%s; MEMORY_CLEAR should create them with TTLs)" % (ttl_gen, ttl_ver))
    elif ttl_ver == -3 or ttl_gen == -3:
        _check(checks, scenario, copy_name, "memory_tombstones", "unknown",
               "gen/ver TTLs are unreadable (gen=%s ver=%s)" % (ttl_gen, ttl_ver))
    else:
        _check(checks, scenario, copy_name, "memory_tombstones", "fail",
               "gen/ver require TTLs in (0,86400] (gen=%s ver=%s)" % (ttl_gen, ttl_ver))

    for run_id in list(keys["runs"])[:3]:
        if keys["runs"][run_id].get("status") not in ("RUNNING", "SUSPENDED"):
            continue
        # xack keys carry a random token, so enumerating them would require a
        # keyspace SCAN. They are an intentional 120s TTL tombstone; do not scan.
        _check(checks, scenario, copy_name, "xack_ttl_%s" % run_id, "info",
               "xack is intentionally retained for 120s; random tokens are not enumerated to avoid an unmatched full-database SCAN and expire by TTL")
        trim_key = K.run_scoped(K.XTRIM_PREFIX, run_id, cfg.tenant_id, cfg.user_id)
        if ops.exists(trim_key):
            _check_bounded_existing(ops, checks, scenario, copy_name, "xtrim_retained_%s" % run_id, [trim_key])
    if ops.exists(str(keys["lock"])):
        _check_bounded_existing(ops, checks, scenario, copy_name, "session_lock_retained", [str(keys["lock"])], maximum=600)

    refs = keys["run_index_refs"]
    _check_bounded_existing(ops, checks, scenario, copy_name, "shared_index_ttls",
                            [str(ref["key"]) for ref in refs] + [K.PURGE_DUE_KEY])
    still = []
    unknown_index = []
    for ref in refs:
        label = "%s member=%s" % (ref["key"], ref["member"])
        try:
            if ops.client.zscore(ref["key"], ref["member"]) is not None:
                still.append(label)
        except Exception:
            unknown_index.append(label)
    if still:
        _check(checks, scenario, copy_name, "run_index_members_removed", "fail",
               "resume/list ZSET members remain: %s" % _sample(still))
    if unknown_index:
        _check(checks, scenario, copy_name, "run_index_members_unknown", "unknown",
               "failed to read resume/list ZSETs; member cleanup cannot be confirmed: %s" % _sample(unknown_index))
    if not still and not unknown_index:
        _check(checks, scenario, copy_name, "run_index_members_removed", "pass",
               "this run's members are absent from global/identity resume and identity-list ZSETs (global write enabled=%s)"
               % cfg.write_global_run_index)

    business = merged.get("business") or {}
    memory_business = business.get("memory") if isinstance(business, dict) else None
    if isinstance(memory_business, dict) and memory_business.get("existed") is True:
        if memory_business.get("deleted") is True:
            _check(checks, scenario, copy_name, "response_memory_deleted", "pass", "memory.deleted=true")
        else:
            _check(checks, scenario, copy_name, "response_memory_deleted", "fail",
                   "complete=true but memory.existed=true/memory.deleted=%s" % memory_business.get("deleted"))
    transcript_business = business.get("transcript") if isinstance(business, dict) else None
    if isinstance(transcript_business, dict) and transcript_business.get("existed") is True:
        if transcript_business.get("deleted") is True:
            _check(checks, scenario, copy_name, "response_transcript_deleted", "pass",
                   "transcript.deleted=true")
        else:
            _check(checks, scenario, copy_name, "response_transcript_deleted", "fail",
                   "complete=true but transcript.existed=true/deleted=%s"
                   % transcript_business.get("deleted"))
    attachment_business = business.get("attachments") if isinstance(business, dict) else None
    if isinstance(attachment_business, dict):
        registered = int(attachment_business.get("registered") or 0)
        deleted_regs = int(attachment_business.get("registrationsDeleted") or 0)
        failed_ids = attachment_business.get("failedFileIds") or []
        if registered > 0 and (deleted_regs != registered or failed_ids):
            _check(checks, scenario, copy_name, "response_attachments", "fail",
                   "attachments registered=%s deletedRegs=%s failed=%s"
                   % (registered, deleted_regs, _sample([str(item) for item in failed_ids])))
        elif registered > 0:
            _check(checks, scenario, copy_name, "response_attachments", "pass",
                   "attachment registrations deleted %s/%s" % (deleted_regs, registered))

    _check_files(cfg, checks, scenario, copy_name, copy)
    _check_feedback(api, checks, scenario, copy_name, copy)
    _check_background(cfg, ops, checks, scenario, copy_name, plan, background_counts)


def _check_measure_budget(checks: List[Dict[str, object]], scenario: str,
                          scenario_measure: Dict[str, object]) -> bool:
    """Record that a budget-exhausted scenario is not a performance pass.

    Returns True when the scenario was never measured at all (caller can skip
    its postcondition checks); a partially measured scenario still gets checked.
    """
    status = str((scenario_measure or {}).get("status") or "")
    if status not in ("skipped", "budget_exhausted"):
        return False
    detail = (scenario_measure or {}).get("detail") or (scenario_measure or {}).get("reason") or ""
    _check(checks, scenario, "-", "measure_budget", "unknown",
           "measure budget exhausted (status=%s)%s; this scenario cannot support a performance pass"
           % (status, (": " + str(detail)) if detail else ""))
    return status == "skipped"


def _check_deferred_runs(ops: RedisOps, checks: List[Dict[str, object]], scenario: str,
                         copy_name: str, keys: Dict[str, object],
                         deferred: List[str]) -> None:
    """A deferred run keeps exactly the keys generation actually wrote.

    Checking all 13 keys invents pending/claim/req/idem/lease for runs generated
    with pending=0. Checking only what the manifest says keeps real deletions
    visible; unknown generation facts are reported as unknown, never pass.
    """
    for run_id in deferred:
        info = keys["runs"].get(run_id)
        if not info:
            _check(checks, scenario, copy_name, "deferred_run_%s" % run_id, "unknown",
                   "deferred run is absent from the generation manifest")
            continue
        generated = info.get("generated_keys") or {}
        unresolved = [str(name) for name in (info.get("generated_unknown") or [])]
        if not generated and not unresolved:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "unknown",
                   "manifest does not record the keys actually generated for this run; retention cannot be verified")
            continue
        expected = list(generated.values())
        existence = ops.exists_many(expected) if expected else {}
        missing = [key for key in expected if existence.get(key) is False]
        unreadable = [key for key in expected if existence.get(key) is None]
        if missing:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "fail",
                   "%d/%d generated keys for the deferred run were deleted (active runs must not be deleted): %s"
                   % (len(missing), len(expected), _sample(missing)))
        elif unreadable or unresolved:
            parts = []
            if unreadable:
                parts.append("failed to read %d generated keys: %s" % (len(unreadable), _sample(unreadable)))
            if unresolved:
                parts.append("old manifest does not record %s; generation is unknown" % _sample(unresolved))
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "unknown",
                   "%d of %d generated keys are confirmed to remain; %s"
                   % (len(expected) - len(missing) - len(unreadable), len(expected), "; ".join(parts)))
        else:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "pass",
                   "all %d generated keys for the deferred run remain (manifest list, not all 13 keys)"
                   % len(expected))


def _all_generated_runs_absent(ops: RedisOps, keys: Dict[str, object]) -> bool:
    expected: List[str] = []
    for info in keys["runs"].values():
        expected.extend((info.get("generated_keys") or {}).values())
    if not expected:
        return False
    existence = ops.exists_many(expected)
    return all(existence.get(key) is False for key in expected)


def _check_retained(cfg: Config, ops: RedisOps, api: ApiClient, checks: List[Dict[str, object]], scenario: str,
                    copy_name: str, plan: Dict[str, object], copy: Dict[str, object],
                    merged: Dict[str, object], background_counts: Dict[str, int]) -> None:
    keys = D.copy_keys(cfg, scenario, plan, copy)
    deferred = [str(item) for item in merged.get("deferred") or []]
    errors = [str(item) for item in merged.get("errors") or []]
    if not deferred and not errors:
        _check(checks, scenario, copy_name, "incomplete_has_reason", "fail",
               "complete=false but neither deferred nor errors is present (inconsistent response semantics)")
    else:
        _check(checks, scenario, copy_name, "incomplete_has_reason", "pass",
               "deferred=%d errors=%d" % (len(deferred), len(errors)))

    _check_deferred_runs(ops, checks, scenario, copy_name, keys, deferred)

    for run_id, info in (merged.get("runs") or {}).items():
        if not isinstance(info, dict) or not info.get("deleted"):
            continue
        keys13 = keys["runs"].get(str(run_id), {}).get("keys13") if keys["runs"].get(str(run_id)) else None
        if not keys13:
            continue
        existence = ops.exists_many(list(keys13.values()))
        residue = [key for key in keys13 if existence.get(key) is True]
        unknown = [key for key in keys13 if existence.get(key) is None]
        if residue:
            _check(checks, scenario, copy_name, "deleted_run_%s_absent" % run_id, "fail",
                   "response deleted=true but keys remain: %s" % _sample(residue))
        elif unknown:
            _check(checks, scenario, copy_name, "deleted_run_%s_absent" % run_id, "unknown",
                   "deletion result is unknown: %s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "deleted_run_%s_absent" % run_id, "pass",
                   "the deleted run's 13 keys are absent")

    pending_ttl = ops.ttl(str(keys["pending"]))
    if pending_ttl == -3:
        _check(checks, scenario, copy_name, "pending_retained", "unknown", "pending TTL is unreadable")
    elif pending_ttl == -2 and _all_generated_runs_absent(ops, keys):
        _check(checks, scenario, copy_name, "pending_retained", "info",
               "pending was removed after the subsequent memory review (all generated run keys are absent)")
    elif not 0 < pending_ttl <= 86400:
        _check(checks, scenario, copy_name, "pending_retained", "fail",
               "pending must be retained on failure/defer with a positive TTL no greater than 24h, ttl=%s" % pending_ttl)
    else:
        try:
            due = ops.zscore_strict(K.PURGE_DUE_KEY, str(keys["pending"]))
        except Exception:
            _check(checks, scenario, copy_name, "pending_retained", "unknown",
                   "failed to read the purge retry due member")
        else:
            due_ttl = ops.ttl(K.PURGE_DUE_KEY)
            if due is None:
                _check(checks, scenario, copy_name, "pending_retained", "fail",
                       "pending is retained but its due member is absent")
            elif due_ttl == -3:
                _check(checks, scenario, copy_name, "pending_retained", "unknown",
                       "purge retry due TTL is unreadable")
            elif not 0 < due_ttl <= 86400:
                _check(checks, scenario, copy_name, "pending_retained", "fail",
                       "pending is retained but its due TTL is invalid: due_ttl=%s" % due_ttl)
            else:
                _check(checks, scenario, copy_name, "pending_retained", "pass",
                       "durable pending retained with TTL=%ss; due TTL=%ss" % (pending_ttl, due_ttl))
    guard_ttl = ops.ttl(str(keys["guard"]))
    if 0 < guard_ttl <= 86400:
        _check(checks, scenario, copy_name, "guard_retained", "pass", "guard retained with bounded TTL=%ss" % guard_ttl)
    elif guard_ttl == -3:
        _check(checks, scenario, copy_name, "guard_retained", "unknown", "guard TTL is unreadable")
    else:
        _check(checks, scenario, copy_name, "guard_retained", "fail",
               "guard should be retained on failure/defer with TTL in (0,86400], ttl=%s" % guard_ttl)

    retained_keys = [str(key) for info in keys["runs"].values() for key in (info.get("generated_keys") or {}).values()]
    _check_bounded_existing(ops, checks, scenario, copy_name, "deferred_data_ttls", retained_keys)
    lease_keys = [str(info["generated_keys"]["lease"]) for info in keys["runs"].values()
                  if "lease" in (info.get("generated_keys") or {})]
    _check_bounded_existing(ops, checks, scenario, copy_name, "deferred_short_lease_ttls", lease_keys, maximum=30)
    _check_bounded_existing(ops, checks, scenario, copy_name, "shared_index_ttls",
                            [str(ref["key"]) for ref in keys["run_index_refs"]] + [K.PURGE_DUE_KEY])

    attachment_keys = list(keys["attachments"]["registrations"])
    if attachment_keys:
        existence = ops.exists_many(attachment_keys)
        alive = [key for key in attachment_keys if existence.get(key) is True]
        unknown = [key for key in attachment_keys if existence.get(key) is None]
        if alive:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "fail",
                   "attachment registrations should be deleted in this round even for a deferred run; remaining: %s" % _sample(alive))
        elif unknown:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "unknown",
                   "attachment registration existence is unknown: %s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "pass",
                   "attachment registrations deleted regardless of run deferral")
        _check_files(cfg, checks, scenario, copy_name, copy)
    _check_feedback(api, checks, scenario, copy_name, copy)

    discovery = [K.session_current_key(keys["session_id"], cfg.tenant_id, cfg.user_id),
                 K.session_runs_key(keys["session_id"], cfg.tenant_id, cfg.user_id),
                 K.session_active_key(keys["session_id"], cfg.tenant_id, cfg.user_id)]
    existence = ops.exists_many(discovery)
    present = [key for key in discovery if existence.get(key) is True]
    unknown = [key for key in discovery if existence.get(key) is None]
    if present:
        _check(checks, scenario, copy_name, "discovery_kept", "pass",
               "at least one discovery key remains (retry can still enumerate it)")
    elif unknown:
        _check(checks, scenario, copy_name, "discovery_kept", "unknown",
               "discovery key existence is unknown: %s" % _sample(unknown))
    elif deferred:
        _check(checks, scenario, copy_name, "discovery_kept", "fail",
               "deferred runs remain but current/runs/active are all absent; retry loses discovery")
    else:
        _check(checks, scenario, copy_name, "discovery_kept", "info",
               "no deferred runs; discovery keys may disappear automatically when empty (not a deletion action)")
    _check_background(cfg, ops, checks, scenario, copy_name, plan, background_counts)


def _check_aborted(cfg: Config, ops: RedisOps, checks: List[Dict[str, object]], scenario: str,
                   copy_name: str, plan: Dict[str, object], copy: Dict[str, object],
                   merged: Dict[str, object], background_counts: Dict[str, int]) -> None:
    keys = D.copy_keys(cfg, scenario, plan, copy)
    must_exist: List[str] = []
    if keys["session_record"]:
        must_exist.append(str(keys["session_record"]))
    for _run_id, info in keys["runs"].items():
        must_exist.append(str(info["keys13"]["run"]))
        must_exist.append(str(info["keys13"]["ledger"]))
    if keys["memory"].get("data"):
        must_exist.append(str(keys["memory"]["data"]))
    if keys["transcript"]:
        must_exist.append(str(keys["transcript"][0]))
    must_exist.extend(str(key) for key in keys["archives"][:3])
    existence = ops.exists_many(must_exist)
    missing = [key for key in must_exist if existence.get(key) is False]
    unknown = [key for key in must_exist if existence.get(key) is None]
    if missing:
        _check(checks, scenario, copy_name, "aborted_nothing_deleted", "fail",
               "aborted=true but %d data keys are missing: %s" % (len(missing), _sample(missing)))
    elif unknown:
        _check(checks, scenario, copy_name, "aborted_nothing_deleted", "unknown",
               "data existence is unknown after abort: %s" % _sample(unknown))
    else:
        _check(checks, scenario, copy_name, "aborted_nothing_deleted", "pass",
               "enumeration/guard/DB abort occurred before deletion; data remains intact")
    _check_background(cfg, ops, checks, scenario, copy_name, plan, background_counts)


def _check_reads(cfg: Config, ops: RedisOps, checks: List[Dict[str, object]], scenario: str,
                 results: Dict[str, object], plan: Dict[str, object], copy_name: str,
                 copy: Dict[str, object]) -> None:
    rows = [row for row in results.get("rows", [])
            if row.get("scenario") == scenario and row.get("copy") == copy_name and not row.get("warmup")]
    for row in rows:
        operation = str(row.get("operation"))
        status = row.get("status")
        if row.get("budget_error"):
            _check(checks, scenario, copy_name, "read_%s_http" % operation, "unknown",
                   "budget exhausted; request incomplete: %s" % row.get("transport_error"))
            continue
        if operation == "health":
            ok = status in (200, 503)
        else:
            try:
                code = int(status) if status is not None else None
            except (TypeError, ValueError):
                code = None
            ok = code is not None and 200 <= code < 300
        if ok:
            _check(checks, scenario, copy_name, "read_%s_http" % operation, "pass",
                   "status=%s duration=%.1fms" % (status, float(row.get("duration_ms") or 0.0)))
        else:
            _check(checks, scenario, copy_name, "read_%s_http" % operation, "fail",
                   "status=%s error=%s" % (status, row.get("transport_error")))

    keys = D.copy_keys(cfg, scenario, plan, copy)
    must_exist: List[str] = []
    for _run_id, info in keys["runs"].items():
        must_exist.append(str(info["keys13"]["run"]))
    if keys["memory"].get("data"):
        must_exist.append(str(keys["memory"]["data"]))
    if keys["transcript"]:
        must_exist.append(str(keys["transcript"][0]))
    if must_exist:
        existence = ops.exists_many(must_exist)
        missing = [key for key in must_exist if existence.get(key) is False]
        unknown = [key for key in must_exist if existence.get(key) is None]
        if missing:
            _check(checks, scenario, copy_name, "read_data_intact", "fail",
                   "read-only endpoints must not delete data, but keys are missing: %s" % _sample(missing))
        elif unknown:
            _check(checks, scenario, copy_name, "read_data_intact", "unknown",
                   "data existence is unknown after read-only calls: %s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "read_data_intact", "pass",
                   "data remains intact after read-only calls")

    activity = D.activity_keys(cfg, scenario, plan, copy)
    _check_bounded_existing(ops, checks, scenario, copy_name, "read_data_ttls", activity["renewable"])
    _check_bounded_existing(ops, checks, scenario, copy_name, "read_short_lease_ttls", activity["leases"], maximum=30)

    history_business = {
        str(row.get("operation")): (row.get("business") or {})
        for row in rows
        if str(row.get("operation")) in ("history", "history_before")
    }
    for row in rows:
        business = row.get("business") or {}
        if not isinstance(business, dict):
            continue
        operation = str(row.get("operation"))
        if operation == "runs_current" and str(copy.get("copy")) == copy_name:
            has_suspended = any(item.get("status") == "SUSPENDED" for item in (copy.get("runs") or []))
            if has_suspended and business.get("found") is not True:
                _check(checks, scenario, copy_name, "runs_current_found", "fail",
                       "a SUSPENDED run exists but /runs/current found=%s" % business.get("found"))
            elif has_suspended:
                _check(checks, scenario, copy_name, "runs_current_found", "pass", "found=true")
        if operation == "run_detail" and business.get("exists") is not True:
            _check(checks, scenario, copy_name, "run_detail_exists", "fail",
                   "GET /runs/{id} exists=%s" % business.get("exists"))
        if operation in ("history", "history_before"):
            continue

    if keys["transcript"] and "history_before" in history_business:
        entry_count = int(copy.get("transcript_entries", 0))
        session_id = str(copy.get("session_id"))
        expected_pages = {
            "history": list(range(max(0, entry_count - 20), entry_count)),
            "history_before": list(range(max(0, entry_count - 40), max(0, entry_count - 20))),
        }
        expected_cursors = {
            "history": entry_count - 19 if entry_count > 20 else None,
            "history_before": entry_count - 39 if entry_count > 40 else None,
        }
        actual_indexes = {}
        for operation, expected in expected_pages.items():
            business = history_business.get(operation) or {}
            expected_ids = ["e-%s-%06d" % (session_id, index) for index in expected]
            entry_ids = [str(item) for item in (business.get("entryIds") or [])]
            entry_indexes = [int(item) for item in (business.get("entryIndexes") or [])]
            actual_indexes[operation] = entry_indexes if entry_indexes else entry_ids
            ids_match = entry_ids == expected_ids if entry_ids else entry_indexes == expected
            expected_cursor = expected_cursors[operation]
            actual_cursor_raw = business.get("nextCursor")
            actual_cursor = None if actual_cursor_raw in (None, "") else str(actual_cursor_raw)
            cursor_match = actual_cursor == (None if expected_cursor is None else str(expected_cursor))
            has_more_match = business.get("hasMore") is (expected_cursor is not None)
            page_match = (
                business.get("total") == entry_count
                and business.get("count") == len(expected)
                and ids_match
                and cursor_match
                and has_more_match
            )
            _check(checks, scenario, copy_name, "%s_entry_range_order_cursor" % operation,
                   "pass" if page_match else "fail",
                   "expectedIds=%s actualIds=%s expectedIndexes=%s actualIndexes=%s expectedCursor=%s actualCursor=%s hasMore=%s"
                   % (expected_ids, entry_ids, expected, entry_indexes, expected_cursor, actual_cursor,
                      business.get("hasMore")))
        latest = actual_indexes.get("history") or []
        before = actual_indexes.get("history_before") or []
        nonoverlap = not set(latest).intersection(before)
        _check(checks, scenario, copy_name, "history_pages_nonoverlap", "pass" if nonoverlap else "fail",
               "latest=%s before=%s" % (latest, before))


def _activity_pair(evidence: List[Dict[str, object]], operation: str,
                   copy_name: str) -> Tuple[Optional[Dict[str, object]], Optional[Dict[str, object]]]:
    rows = [item for item in evidence
            if item.get("operation") == operation and item.get("copy") == copy_name and not item.get("warmup")]
    before = next((item for item in rows if item.get("phase") == "before"), None)
    after = next((item for item in rows if item.get("phase") == "after"), None)
    return before, after


def _observed_seconds(before: Dict[str, object], after: Dict[str, object]) -> float:
    before_ms = before.get("observed_at_ms")
    after_ms = after.get("observed_at_ms")
    if not isinstance(before_ms, (int, float)) or not isinstance(after_ms, (int, float)):
        return 0.0
    return max(0.0, (float(after_ms) - float(before_ms)) / 1000.0)


def _check_activity_renewal(checks: List[Dict[str, object]], scenario: str, copy_name: str,
                            name: str, before: Optional[Dict[str, object]],
                            after: Optional[Dict[str, object]], increase: bool = True) -> None:
    if before is None or after is None:
        _check(checks, scenario, copy_name, name, "unknown", "missing before/after Redis TTL evidence for this copy")
        return
    setup = before.get("setup") or {}
    setup_errors = setup.get("errors") or []
    if setup_errors:
        _check(checks, scenario, copy_name, name, "unknown",
               "fixture TTL setup failed: %s" % _sample([str(item) for item in setup_errors]))
        return
    before_ttl = before.get("ttl") or {}
    after_ttl = after.get("ttl") or {}
    elapsed = _observed_seconds(before, after)
    expected = [key for key, value in before_ttl.items() if isinstance(value, int) and value > 0]
    unreadable = [key for key in set(before_ttl) | set(after_ttl)
                  if before_ttl.get(key) == -3 or after_ttl.get(key) == -3]
    invalid = []
    for key in expected:
        old = before_ttl.get(key)
        new = after_ttl.get(key)
        if not isinstance(new, int) or not 0 < new <= 86400:
            invalid.append("%s(%s->%s)" % (key, old, new))
            continue
        if increase and (new <= old or new + elapsed < 86398):
            invalid.append("%s(%s->%s; elapsed=%.3fs; 24h target not proven)" % (key, old, new, elapsed))
        if not increase and (new > old or old - new > elapsed + 2):
            invalid.append("%s(%s->%s; elapsed=%.3fs; unexpected TTL change)" % (key, old, new, elapsed))
    if not expected:
        _check(checks, scenario, copy_name, name, "fail", "no existing Redis key was available for TTL comparison")
    elif unreadable or invalid:
        _check(checks, scenario, copy_name, name, "fail",
               "TTL renewal mismatch expected=%d unreadable=%s invalid=%s"
               % (len(expected), _sample(unreadable), _sample(invalid)))
    else:
        _check(checks, scenario, copy_name, name, "pass",
               "%d existing keys have bounded TTLs; direction=%s; observed elapsed=%.3fs"
               % (len(expected), "increased-to-24h" if increase else "not-increased", elapsed))


def _check_activity_data(checks: List[Dict[str, object]], scenario: str, copy_name: str, name: str,
                         before: Optional[Dict[str, object]], after: Optional[Dict[str, object]],
                         require_present: bool = True) -> None:
    if before is None or after is None:
        _check(checks, scenario, copy_name, name, "unknown", "missing dataKey TTL evidence")
        return
    key = str(after.get("data_key") or before.get("data_key") or "")
    old = (before.get("ttl") or {}).get(key)
    new = (after.get("ttl") or {}).get(key)
    valid = new == -2 if not require_present else (
        isinstance(old, int) and old > 0 and isinstance(new, int) and old < new <= 86400)
    _check(checks, scenario, copy_name, name, "pass" if valid else "fail",
           "dataKey=%s TTL %s->%s; required=%s" % (key, old, new, "present-and-renewed" if require_present else "missing"))


def _check_activity(checks: List[Dict[str, object]], scenario: str, copy_name: str,
                    scenario_measure: Dict[str, object]) -> None:
    evidence = [item for item in (scenario_measure.get("activity_evidence") or [])
                if isinstance(item, dict) and item.get("copy") == copy_name and not item.get("warmup")]
    if not evidence:
        _check(checks, scenario, copy_name, "activity_evidence", "unknown",
               "no real Redis TTL snapshots recorded for this copy")
        return
    if scenario == "session_activity":
        first_before, first_after = _activity_pair(evidence, "history", copy_name)
        _check_activity_renewal(checks, scenario, copy_name, "history_all_keys_24h", first_before, first_after)
        _check_activity_data(checks, scenario, copy_name, "history_data_key_required", first_before, first_after)
        second_before, second_after = _activity_pair(evidence, "history_secondary", copy_name)
        _check_activity_renewal(checks, scenario, copy_name, "cross_instance_60s_throttle",
                                second_before, second_after, increase=False)
        throttle_before = (second_before or {}).get("throttle_ttl")
        throttle_after = (second_after or {}).get("throttle_ttl")
        throttle_elapsed = _observed_seconds(second_before, second_after) if second_before and second_after else 0.0
        throttle_valid = (
            isinstance(throttle_before, int) and isinstance(throttle_after, int)
            and 0 < throttle_after <= throttle_before <= 60 and throttle_after + throttle_elapsed >= 59
        )
        _check(checks, scenario, copy_name, "cross_instance_throttle_key", "pass" if throttle_valid else "fail",
               "secondary target=%s throttle TTL %s->%s; observed elapsed=%.3fs"
               % ((second_after or {}).get("target"), throttle_before, throttle_after, throttle_elapsed))
        window_before, window_after = _activity_pair(evidence, "history_after_window", copy_name)
        _check_activity_renewal(checks, scenario, copy_name, "history_after_window_24h", window_before, window_after)
        window_throttle = (window_after or {}).get("throttle_ttl")
        window_elapsed = _observed_seconds(window_before, window_after) if window_before and window_after else 0.0
        window_valid = bool((window_before or {}).get("setup", {}).get("window_reset")) and isinstance(
            window_throttle, int) and 0 < window_throttle <= 60 and window_throttle + window_elapsed >= 59
        _check(checks, scenario, copy_name, "activity_window_reacquired", "pass" if window_valid else "fail",
               "owned throttle key was expired for the fixture; post-GET throttle TTL=%s elapsed=%.3fs"
               % (window_throttle, window_elapsed))
        startup_before, startup_after = _activity_pair(evidence, "startup_resume", copy_name)
        _check_activity_renewal(checks, scenario, copy_name, "background_read_does_not_renew_session",
                                startup_before, startup_after, increase=False)
        startup_throttle = (startup_after or {}).get("throttle_ttl")
        _check(checks, scenario, copy_name, "background_read_no_activity_window",
               "pass" if startup_throttle == -2 and bool((startup_before or {}).get("setup", {}).get("window_reset")) else "fail",
               "startup_resume fixture cleared the owned window; post-read throttle TTL=%s" % startup_throttle)
        final = evidence[-1]
        background = final.get("background_ttl") or {}
        bad_background = ["%s=%s" % (key, ttl) for key, ttl in background.items() if not isinstance(ttl, int) or not 0 < ttl <= 3600]
        _check(checks, scenario, copy_name, "background_not_renewed", "pass" if background and not bad_background else "fail",
               "background keys retain the fixture TTL rather than 24h: %s" % (bad_background or background))
        leases = (first_after or {}).get("lease_ttl") or {}
        old_leases = (first_before or {}).get("lease_ttl") or {}
        bad_leases = [key for key, ttl in leases.items() if not isinstance(ttl, int) or not 0 < ttl <= 30 or ttl > old_leases.get(key, -1)]
        _check(checks, scenario, copy_name, "short_lease_not_renewed", "pass" if leases and not bad_leases else "fail",
               "run lease stays at its own short TTL: %s" % leases)
        sentinel = (final.get("sentinel") or {})
        sentinel_ok = sentinel.get("key") and sentinel.get("member") and sentinel.get("score") is not None
        _check(checks, scenario, copy_name, "shared_index_other_member_preserved", "pass" if sentinel_ok else "fail",
               "sentinel ZSET member=%s score=%s" % (sentinel.get("member"), sentinel.get("score")))
        return
    if scenario == "session_activity_guard":
        before, after = _activity_pair(evidence, "history", copy_name)
        _check_activity_renewal(checks, scenario, copy_name, "purge_guard_skips_all_renewal", before, after, increase=False)
        guard_ttl = (after or {}).get("guard_ttl")
        throttle_ttl = (after or {}).get("throttle_ttl")
        _check(checks, scenario, copy_name, "purge_guard_no_activity_window",
               "pass" if isinstance(guard_ttl, int) and 0 < guard_ttl <= 300 and throttle_ttl == -2 else "fail",
               "guard TTL=%s throttle TTL=%s" % (guard_ttl, throttle_ttl))
        return
    if scenario in ("session_activity_run_cap", "session_activity_enumeration_cap"):
        first_before, first_after = _activity_pair(evidence, "history", copy_name)
        retry_before, retry_after = _activity_pair(evidence, "history_cap_retry", copy_name)
        _check_activity_data(checks, scenario, copy_name, "cap_first_attempt_data_key", first_before, first_after)
        _check_activity_data(checks, scenario, copy_name, "cap_retry_not_throttled", retry_before, retry_after)
        cap_ok = (first_after or {}).get("throttle_ttl") == -2 and (retry_after or {}).get("throttle_ttl") == -2 \
            and not (retry_before or {}).get("setup", {}).get("window_reset")
        boundary = "129-run" if scenario == "session_activity_run_cap" else "1025-member"
        _check(checks, scenario, copy_name, "cap_failure_releases_window", "pass" if cap_ok else "fail",
               "%s hard-cap attempts release the Redis window; retry did not delete it in the fixture" % boundary)
        return
    if scenario == "session_activity_data_key":
        first_before, first_after = _activity_pair(evidence, "history_data_missing", copy_name)
        retry_before, retry_after = _activity_pair(evidence, "history_data_restored", copy_name)
        _check_activity_data(checks, scenario, copy_name, "missing_data_key_detected", first_before, first_after, require_present=False)
        _check_activity_data(checks, scenario, copy_name, "restored_data_key_renewed", retry_before, retry_after)
        failure_released = (first_after or {}).get("throttle_ttl") == -2
        retry_throttle = (retry_after or {}).get("throttle_ttl")
        success_acquired = isinstance(retry_throttle, int) and 0 < retry_throttle <= 60
        release_ok = failure_released and success_acquired and not (retry_before or {}).get("setup", {}).get("window_reset")
        _check(checks, scenario, copy_name, "data_key_failure_releases_window", "pass" if release_ok else "fail",
               "missing dataKey leaves throttle absent; successful restored-dataKey GET acquires a fresh 60s window (TTL=%s) without fixture deletion"
               % retry_throttle)


def _reconcile_delete(api: ApiClient, checks: List[Dict[str, object]], scenario: str,
                      copy_name: str, copy: Dict[str, object]) -> Tuple[str, Dict[str, object], Dict[str, object]]:
    result = api.delete_session(str(copy.get("session_id")), operation="delete_verify_reconcile")
    business = result.get("business") if isinstance(result.get("business"), dict) else {}
    complete = business.get("complete")
    merged = _merge([result])
    evidence = {
        "scenario": scenario,
        "copy": copy_name,
        "status": result.get("status"),
        "transport_error": result.get("transport_error", ""),
        "complete": complete,
    }
    if result.get("transport_error"):
        _check(checks, scenario, copy_name, "delete_reconcile", "unknown",
               "reconcile DELETE transport failed: %s" % result.get("transport_error"))
        return "unknown", merged, evidence
    status = result.get("status")
    if status != 200:
        outcome = "unknown" if status in (None, 503) else "fail"
        _check(checks, scenario, copy_name, "delete_reconcile", outcome,
               "reconcile DELETE returned HTTP %s" % status)
        return outcome, merged, evidence
    if complete is True:
        _check(checks, scenario, copy_name, "delete_reconcile", "pass",
               "reconcile DELETE complete=true; current response and Redis state use the same purge generation")
        return "complete", merged, evidence
    if complete is False:
        _check(checks, scenario, copy_name, "delete_reconcile", "info",
               "reconcile DELETE complete=false; retained-state assertions are required")
        return "incomplete", merged, evidence
    _check(checks, scenario, copy_name, "delete_reconcile", "unknown",
           "reconcile DELETE response has no readable boolean complete flag")
    return "unknown", merged, evidence


def verify(cfg: Config, manifest: Dict[str, object], results: Dict[str, object],
           selected: List[str]) -> Dict[str, object]:
    started_at = M.utc_now_iso()
    if cfg.verify_settle_seconds > 0:
        time.sleep(cfg.verify_settle_seconds)
    checks: List[Dict[str, object]] = []
    reconciles: List[Dict[str, object]] = []
    background_counts: Dict[str, int] = {}
    background_manifest = manifest.get("background") or {}
    if not isinstance(background_manifest, dict):
        background_manifest = {}
    for bucket, state in background_manifest.items():
        if not isinstance(state, dict):
            continue
        try:
            background_counts[str(bucket)] = int(state.get("planned") or state.get("count") or 0)
        except (TypeError, ValueError):
            background_counts[str(bucket)] = 0
    ops = RedisOps(cfg)
    api = ApiClient(cfg)
    try:
        try:
            ops.connect()
            if cfg.is_cluster():
                authoritative = ops.primaries(allow_seed_fallback=False)
                if not authoritative:
                    raise RedisUnavailable("cluster primary topology unavailable; verify with partial nodes refused")
                ops.primary_node_clients(strict=True, targets=authoritative)
        except Exception as exc:
            return {
                "phase": "pre_cleanup",
                "started_at": started_at,
                "finished_at": M.utc_now_iso(),
                "checks": [{
                    "scenario": "*", "copy": "*", "check": "redis_connectivity",
                    "status": "unknown", "detail": "not all target nodes are available; verify refused: %s" % error_detail(exc),
                }],
                "counts": {"pass": 0, "fail": 0, "unknown": 1, "info": 0, "skip": 0},
                "error": error_detail(exc),
                "reconciles": reconciles,
            }
        measure_scenarios: Dict[str, Dict[str, object]] = {}
        for item in results.get("scenarios", []) or []:
            if isinstance(item, dict):
                measure_scenarios[str(item.get("scenario"))] = item

        for scenario in selected:
            entry = (manifest.get("scenarios") or {}).get(scenario)
            if not isinstance(entry, dict) or entry.get("status") != "generated":
                _check(checks, scenario, "-", "scenario_generated", "skip",
                       (entry or {}).get("reason", "not generated"))
                continue
            if _check_measure_budget(checks, scenario, measure_scenarios.get(scenario) or {}):
                continue
            plan = entry.get("plan") or {}
            mode = plan.get("verify_mode")
            copies = {str(copy.get("copy")): copy for copy in entry.get("copies", []) if isinstance(copy, dict)}
            if mode == "reads":
                for copy_name in sorted(copies):
                    _check_slots(cfg, checks, scenario, copy_name, copies[copy_name])
                    _check_reads(cfg, ops, checks, scenario, results, plan, copy_name, copies[copy_name])
                    if scenario.startswith("session_activity"):
                        _check_activity(checks, scenario, copy_name, measure_scenarios.get(scenario) or {})
                continue
            by_copy = _delete_rows(results, scenario)
            for copy_name in sorted(copies):
                copy = copies[copy_name]
                if copy_name == "warmup":
                    continue
                rows = by_copy.get(copy_name) or []
                if not rows:
                    _check(checks, scenario, copy_name, "delete_measured", "skip", "no measurement rows")
                    continue
                merged = _merge(rows)
                _check_slots(cfg, checks, scenario, copy_name, copy)
                statuses = merged["statuses"]
                if merged["transport_errors"]:
                    _check(checks, scenario, copy_name, "delete_http", "unknown",
                           "transport failures: %s" % _sample(merged["transport_errors"], 3))
                    continue
                non_200 = [status for status in statuses if status != 200]
                if non_200:
                    _check(checks, scenario, copy_name, "delete_http",
                           "unknown" if all(item == 503 for item in non_200) else "fail",
                           "non-200 statuses: %s" % non_200)
                    if all(item == 503 for item in non_200):
                        continue
                else:
                    _check(checks, scenario, copy_name, "delete_http", "pass", "all DELETE requests returned 200")
                successful_delete = any(isinstance(row.get("business"), dict)
                                        and row["business"].get("complete") is True for row in rows)
                early_abort = str((merged.get("business") or {}).get("phase") or "") in {
                    "pending", "enumerate", "guard", "database"
                }
                if merged["aborted"] and early_abort and not successful_delete:
                    _check_aborted(cfg, ops, checks, scenario, copy_name, plan, copy, merged, background_counts)
                elif successful_delete:
                    verify_status = _wait_for_memory_verify(ops, str(D.copy_keys(cfg, scenario, plan, copy)["pending"]))
                    if verify_status != "ready":
                        _check(checks, scenario, copy_name, "memory_verify_wait", "unknown",
                               "memory review incomplete (%s); an in-flight write-back is not misclassified as a deletion failure" % verify_status)
                    else:
                        _check_clean(cfg, ops, api, checks, scenario, copy_name, plan, copy, merged,
                                     background_counts)
                else:
                    outcome, reconcile_merged, evidence = _reconcile_delete(api, checks, scenario, copy_name, copy)
                    reconciles.append(evidence)
                    if outcome == "complete":
                        verify_status = _wait_for_memory_verify(
                            ops, str(D.copy_keys(cfg, scenario, plan, copy)["pending"]))
                        if verify_status != "ready":
                            _check(checks, scenario, copy_name, "memory_verify_wait", "unknown",
                                   "memory review incomplete (%s); an in-flight write-back is not misclassified as a deletion failure" % verify_status)
                        else:
                            _check_clean(cfg, ops, api, checks, scenario, copy_name, plan, copy,
                                         reconcile_merged, background_counts)
                    elif outcome == "incomplete":
                        _check_retained(cfg, ops, api, checks, scenario, copy_name, plan, copy,
                                        reconcile_merged, background_counts)

                if scenario == "repeat_delete":
                    found_values = []
                    for row in rows:
                        business = row.get("business") or {}
                        if isinstance(business, dict) and "found" in business:
                            found_values.append(business.get("found"))
                    if len(found_values) >= 2:
                        if found_values[-1] is False:
                            _check(checks, scenario, copy_name, "second_delete_idempotent", "pass",
                                   "second DELETE found=false")
                        else:
                            _check(checks, scenario, copy_name, "second_delete_idempotent", "fail",
                                   "second DELETE found=%s (expected false after the first completion)" % found_values[-1])
                if scenario == "concurrent_delete":
                    if not merged["complete_any"]:
                        _check(checks, scenario, copy_name, "concurrent_single_success", "fail",
                               "no concurrent DELETE returned complete=true")
                    else:
                        _check(checks, scenario, copy_name, "concurrent_single_success", "pass",
                               "at least one concurrent DELETE returned complete=true")
    finally:
        api.close()
        ops.close()

    counts = {"pass": 0, "fail": 0, "unknown": 0, "info": 0, "skip": 0}
    for item in checks:
        status = str(item.get("status"))
        counts[status] = counts.get(status, 0) + 1
    return {
        "phase": "pre_cleanup",
        "started_at": started_at,
        "finished_at": M.utc_now_iso(),
        "checks": checks,
        "counts": counts,
        "reconciles": reconciles,
    }


def verify_cleanup(cleanup: Dict[str, object]) -> Dict[str, object]:
    """Verify an executed cleanup without reusing pre-cleanup retained assertions."""
    started_at = M.utc_now_iso()
    checks: List[Dict[str, object]] = []
    complete = cleanup.get("complete") is True
    remaining = cleanup.get("remaining") or {}
    detail = "cleanup.complete=true; guard/pending/tombstone/background keys deleted according to the cleanup contract" if complete else \
        "cleanup incomplete, remaining=%s; acceptance uses actual cleanup residues and budget results" % remaining
    _check(checks, "cleanup", "-", "cleanup_complete", "pass" if complete else "fail", detail)
    counts = {"pass": 0, "fail": 0, "unknown": 0, "info": 0, "skip": 0}
    for item in checks:
        counts[str(item.get("status"))] = counts.get(str(item.get("status")), 0) + 1
    return {
        "phase": "post_cleanup",
        "started_at": started_at,
        "finished_at": M.utc_now_iso(),
        "checks": checks,
        "counts": counts,
    }
