"""Derive every key a generated copy owns.

Verify and cleanup both use this module, so "what should be gone" and "what may
be deleted" never drift apart.
"""

from __future__ import annotations

from typing import Dict, List

from . import keys as K
from . import manifest as M
from .envcfg import Config

ALWAYS_GENERATED_RUN_KEYS = ("run", "runstate", "seq", "fence", "lease_epoch", "ledger")
PENDING_GENERATED_RUN_KEYS = ("pending", "claim", "idem", "req", "decided")


def _int_or_none(value: object):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def run_generated_key_names(run: Dict[str, object]) -> Dict[str, object]:
    """Key names a run copy actually wrote, plus names that cannot be decided.

    New manifests list them in ``generated_keys``. Old manifests are derived
    from the recorded shape (pending/frames/lease_held); a field that was never
    recorded stays in ``unknown`` instead of being assumed absent or present.
    """
    raw = run.get("generated_keys")
    if isinstance(raw, list):
        wanted = {str(name) for name in raw}
        return {
            "names": [name for name in K.RUN_KEYS_13 if name in wanted],
            "unknown": [],
            "source": "manifest",
        }

    names = set(ALWAYS_GENERATED_RUN_KEYS)
    unknown: List[str] = []
    frames = run.get("frames")
    frame_count = _int_or_none(frames.get("count")) if isinstance(frames, dict) else None
    if frame_count is None:
        unknown.append("xevents")
    elif frame_count > 0:
        names.add("xevents")

    pending = _int_or_none(run.get("pending"))
    if pending is None:
        unknown.extend(PENDING_GENERATED_RUN_KEYS)
    elif pending > 0:
        names.update(PENDING_GENERATED_RUN_KEYS)

    lease_held = run.get("lease_held")
    if isinstance(lease_held, bool):
        if lease_held:
            names.add("lease")
    else:
        unknown.append("lease")

    return {
        "names": [name for name in K.RUN_KEYS_13 if name in names],
        "unknown": unknown,
        "source": "derived",
    }


def copy_keys(cfg: Config, scenario: str, plan: Dict[str, object], copy: Dict[str, object]) -> Dict[str, object]:
    t = cfg.tenant_id
    u = cfg.user_id
    sid = str(copy.get("session_id"))
    session_record = K.session_record_key(sid, t, u) if plan.get("session_record") else ""

    runs: Dict[str, Dict[str, object]] = {}
    for run in copy.get("runs") or []:
        if not isinstance(run, dict):
            continue
        run_id = str(run.get("run_id"))
        keys13 = run.get("keys") if isinstance(run.get("keys"), dict) else K.run_keys_13(run_id, t, u)
        generated = run_generated_key_names(run)
        generated_keys: Dict[str, str] = {}
        generated_unknown = list(generated["unknown"])
        for name in generated["names"]:
            if name in keys13:
                generated_keys[name] = str(keys13[name])
            else:
                generated_unknown.append(name)
        runs[run_id] = {
            "keys13": dict(keys13),
            "generated_keys": generated_keys,
            "generated_unknown": generated_unknown,
            "generated_source": generated["source"],
            "anchors": K.run_anchor_keys(run_id, t, u),
            "index_refs": K.run_index_refs(run_id, t, u),
            "status": str(run.get("status") or ""),
        }

    context_keys: List[str] = []
    archive_keys: List[str] = []
    if plan.get("ctx_turn_no") is not None or int(plan.get("archive_count", 0)) > 0:
        context_keys = [
            K.context_key(sid, t, u),
            K.context_ledger_key(sid, t, u),
            K.safety_key(sid, t, u),
            K.context_events_key(sid, t, u),
        ]
    archive_count = int(plan.get("archive_count", 0))
    for turn in range(archive_count):
        archive_keys.append(K.context_archive_key(sid, turn, t, u))

    transcript_keys: List[str] = []
    if int(plan.get("transcript_entries", 0)) > 0:
        transcript_keys = [
            K.transcript_data_key(sid, t, u),
            K.transcript_order_key(sid, t, u),
            K.transcript_meta_key(sid, t, u),
            K.transcript_truncated_key(sid, t, u),
        ]

    memory: Dict[str, object] = {
        "data": "",
        "ver": K.memory_version_key(sid, t, u),
        "gen": K.memory_generation_key(sid, t, u),
        "staging": [],
    }
    if int(plan.get("memory_messages", 0)) > 0:
        memory["data"] = K.memory_data_key(sid, t, u)
    staging: List[str] = []
    for index in range(int(plan.get("staging_count", 0))):
        staging.append(K.memory_staging_key(sid, M.staging_token(index), t, u))
    memory["staging"] = staging

    misc_keys = [
        K.ctdraft_key(sid, t, u),
        K.creq_key(sid, t, u),
        K.session_current_key(sid, t, u),
        K.session_runs_key(sid, t, u),
        K.session_active_key(sid, t, u),
    ]

    attachments: Dict[str, object] = {
        "registrations": [],
        "consumed": [],
        "session_index": K.attachment_session_index_key(sid, t, u),
        "form_indexes": [],
        "files": [],
    }
    registrations: List[str] = []
    consumed: List[str] = []
    form_indexes: List[str] = []
    files: List[str] = []
    first_run_id = ""
    for run in copy.get("runs") or []:
        if isinstance(run, dict):
            first_run_id = str(run.get("run_id"))
            break
    for item in copy.get("attachments") or []:
        if not isinstance(item, dict):
            continue
        file_id = str(item.get("file_id") or "")
        if not file_id:
            continue
        registrations.append(K.attachment_registration_key(file_id, t, u))
        consumed.append(K.attachment_consumed_key(file_id, t, u))
        storage_key = str(item.get("storage_key") or "")
        if storage_key:
            files.append(storage_key)
    if registrations and first_run_id:
        form_indexes.append(K.attachment_form_index_key(first_run_id, M.tool_call_id(first_run_id, 0), t, u))
    attachments["registrations"] = registrations
    attachments["consumed"] = consumed
    attachments["form_indexes"] = form_indexes
    attachments["files"] = files

    expected_absent: List[str] = []
    if session_record:
        expected_absent.append(session_record)
    for run_id, item in runs.items():
        expected_absent.extend(item["keys13"].values())
        expected_absent.extend(item["anchors"].values())
    expected_absent.extend(context_keys)
    expected_absent.extend(archive_keys)
    expected_absent.extend(transcript_keys)
    if memory["data"]:
        expected_absent.append(str(memory["data"]))
    expected_absent.extend(staging)
    if int(plan.get("staging_count", 0)) > 0:
        expected_absent.append(K.memory_staging_index_key(sid, t, u))
    expected_absent.extend(misc_keys)
    expected_absent.extend(registrations)
    expected_absent.extend(consumed)
    if registrations:
        expected_absent.append(str(attachments["session_index"]))
        expected_absent.extend(form_indexes)

    return {
        "session_record": session_record,
        "runs": runs,
        "context": context_keys,
        "archives": archive_keys,
        "transcript": transcript_keys,
        "memory": memory,
        "misc": misc_keys,
        "attachments": attachments,
        "expected_absent_on_clean": K.dedupe(expected_absent),
        "run_index_refs": [ref for item in runs.values() for ref in item["index_refs"]],
        "guard": K.purge_guard_key(sid, t, u),
        "pending": K.purge_pending_key(sid, t, u),
        "lock": K.session_lock_key(sid, t, u),
        "session_id": sid,
    }
