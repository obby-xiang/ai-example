"""Derive every key a generated copy owns.

Verify and cleanup both use this module, so "what should be gone" and "what may
be deleted" never drift apart.
"""

from __future__ import annotations

from typing import Dict, List

from . import keys as K
from . import manifest as M
from .envcfg import Config


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
        runs[run_id] = {
            "keys13": dict(keys13),
            "anchors": K.run_anchor_keys(run_id, t, u),
            "member": K.run_index_member(run_id, t, u),
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
            K.transcript_bytes_key(sid, t, u),
            K.transcript_ready_key(sid, t, u),
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
        "run_index_members": [item["member"] for item in runs.values()],
        "guard": K.purge_guard_key(sid, t, u),
        "pending": K.purge_pending_key(sid, t, u),
        "lock": K.session_lock_key(sid, t, u),
        "session_id": sid,
    }
