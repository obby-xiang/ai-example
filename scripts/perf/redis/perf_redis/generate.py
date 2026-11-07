"""Data generation.

Every value written here has the shape produced by the real services:
  * ai:run:{<t>:<u>:run:<id>}        RunSnapshot JSON (camelCase, non-null fields)
  * ai:runstate:...                  status string
  * ai:pending:...                   HASH field=toolCallId value=PendingToolCall JSON
  * ai:req / ai:decided / ai:claim   HASH markers
  * ai:ledger / ai:idem              LIST / HASH (index + ~meta projection)
  * ai:seq / ai:xevents              STRING / Stream (seq + frame fields, id=<seq>-0)
  * chat:mem:*                       LIST of MessageJsonCodec JSON + :ver/:gen
  * chat:mem:staging:*               in-flight staging LIST
  * ai:tx:*                          HASH entries + :order ZSET + :meta HASH
  * ai:ctx:* + ai:ctx:archive:*      HASH + STRING archives
  * ai:session:* / current / runs / active / lock
  * ai:form:att:*                    created through the real upload endpoint only

Only PERF_NAMESPACE-derived session keys and their own run index members are touched.
"""

from __future__ import annotations

import json
import time
from typing import Dict, List, Optional, Tuple

from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps, error_detail
from .scenarios import plan_for

TS = "spikeTimestamp"


def _now_ms() -> int:
    return int(time.time() * 1000)


def _message_json(role: str, text: str) -> str:
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    return json.dumps({
        "type": role,
        "content": text,
        "metadata": {TS: now},
        "timestamp": now,
    }, ensure_ascii=False)


def _transcript_entry(session_id: str, run_id: str, index: int, now_ms: int) -> str:
    kind = "user" if index % 2 == 0 else "assistant"
    return json.dumps({
        "entryId": "e-%s-%06d" % (session_id, index),
        "kind": kind,
        "runId": run_id,
        "turnStartedAtMs": now_ms - 100000 + index,
        "atMs": now_ms - 100000 + index,
        "text": "perf transcript entry %d for %s" % (index, session_id),
    }, ensure_ascii=False)


def _frame_entries(count: int, terminal: str, gap: bool, terminal_type: str) -> List[Tuple[str, Dict[str, str]]]:
    if count <= 0:
        return []
    seqs: List[int] = []
    for index in range(count):
        if gap:
            seqs.append(1 + 100 * index)
        else:
            seqs.append(1 + index)
    terminal_index = -1
    if terminal == "head":
        terminal_index = 0
    elif terminal == "middle":
        terminal_index = count // 2
    elif terminal == "tail":
        terminal_index = count - 1
    entries: List[Tuple[str, Dict[str, str]]] = []
    for index, seq in enumerate(seqs):
        if index == terminal_index:
            if terminal_type == "error":
                frame = {"type": "error", "seq": seq, "code": "PERF_SYNTHETIC", "message": "perf synthetic error"}
            else:
                frame = {"type": "done", "seq": seq, "finishReason": "stop"}
        else:
            frame = {"type": "delta", "seq": seq, "text": "frame-%d" % seq}
        entries.append(("%d-0" % seq, {"seq": str(seq), "frame": json.dumps(frame, ensure_ascii=False)}))
    return entries


def _form_arguments(field_count: int) -> str:
    fields = []
    for index in range(1, max(1, field_count) + 1):
        fields.append({
            "key": "evidence%d" % index,
            "type": "attachment",
            "label": "Perf evidence %d" % index,
            "required": False,
        })
    return json.dumps({
        "form": {
            "title": "Perf attachment form",
            "scenario": "FILTER",
            "fields": fields,
        }
    }, ensure_ascii=False)


def _pending_entry(run_id: str, index: int, status: str, name: str, kind: str,
                   arguments: str, now_ms: int) -> Dict[str, object]:
    return {
        "toolCallId": M.tool_call_id(run_id, index),
        "name": name,
        "type": "function",
        "kind": kind,
        "arguments": arguments,
        "status": status,
        "outcome": "ok" if status == "EXECUTED" else None,
        "executed": status == "EXECUTED",
        "executedBy": "perf" if status == "EXECUTED" else None,
        "requestedAtMs": now_ms,
        "atMs": now_ms,
        "req": True,
        "reqAtMs": now_ms,
        "irreversible": False,
    }


def _snapshot(run_id: str, session_id: str, t: int, u: int, status: str,
              pendings: List[Dict[str, object]], now_ms: int) -> Dict[str, object]:
    cancelled = status == "CANCELLED"
    snapshot: Dict[str, object] = {
        "runId": run_id,
        "sessionId": session_id,
        "tenantId": t,
        "userId": u,
        "status": status,
        "round": 1,
        "attempts": 0,
        "toolSteps": 0,
        "cancelled": cancelled,
        "messageJson": [
            _message_json("USER", "perf request for " + session_id),
            _message_json("ASSISTANT", "perf answer"),
        ],
        "context": {"page": "perf", "taskId": None},
        "createdBy": "perf-tool",
        "createdAtMs": now_ms - 60000,
        "updatedAtMs": now_ms,
        "requestText": "perf request",
        "turnStartedAtMs": now_ms - 60000,
    }
    if status in ("RUNNING", "SUSPENDED"):
        snapshot["assistantContent"] = ""
        snapshot["inFlightToolCalls"] = [item for item in pendings if item.get("status") == "PENDING"]
    if status in ("DONE", "CANCELLED"):
        snapshot["finalText"] = "perf final answer"
    if status == "FAILED":
        snapshot["error"] = "perf synthetic failure"
    return snapshot


def _context_fields(turn_no: int, now_ms: int) -> Dict[str, str]:
    return {
        "used": "1200",
        "completion": "64",
        "turnNo": str(turn_no),
        "est": "false",
        "atMs": str(now_ms),
        "model": "perf-model",
        "level": "none",
        "turns": "0",
        "lost": "0",
        "circuit": "closed",
        "fails": "0",
        "systemTokens": "200",
        "toolsTokens": "300",
        "inputTokens": "1200",
        "openUntil": "0",
    }


def _write_run(ops: RedisOps, cfg: Config, run_id: str, session_id: str, spec: Dict[str, object],
               t: int, u: int, now_ms: int) -> Dict[str, object]:
    ttl = cfg.key_ttl_seconds
    status = str(spec.get("status", "DONE"))
    pending_count = int(spec.get("pending", 0))
    pending_fields = int(spec.get("pending_fields", 0))
    frames = spec.get("frames") or {}
    frame_count = int(frames.get("count", 0))
    terminal = str(frames.get("terminal", "none"))
    gap = bool(frames.get("gap", False))
    terminal_type = str(frames.get("terminalType", "done"))

    pending_status = "PENDING" if status in ("RUNNING", "SUSPENDED") else "EXECUTED"
    pendings: List[Dict[str, object]] = []
    for index in range(pending_count):
        if index == 0 and pending_fields > 0:
            name = "generative_form"
            kind = "FRONTEND"
            arguments = _form_arguments(pending_fields)
        else:
            name = "start_export"
            kind = "CONFIRM"
            arguments = "{\"taskId\":1}"
        pendings.append(_pending_entry(run_id, index, pending_status, name, kind, arguments, now_ms))

    snapshot = _snapshot(run_id, session_id, t, u, status, pendings, now_ms)
    frame_entries = _frame_entries(frame_count, terminal, gap, terminal_type)
    last_seq = frame_entries[-1][0].split("-")[0] if frame_entries else "0"
    run_keys = K.run_keys_13(run_id, t, u)
    items: List[Tuple[str, str, int]] = [(run_keys["run"], json.dumps(snapshot, ensure_ascii=False), ttl)]
    items.append((run_keys["runstate"], status, ttl))
    items.append((run_keys["seq"], last_seq, ttl))
    items.append((run_keys["fence"], "1", ttl))
    items.append((run_keys["lease_epoch"], "1", ttl))
    if bool(spec.get("lease_held")):
        items.append((run_keys["lease"], "1|perf-other-instance", ttl))
    ops.set_values(items)

    if pendings:
        mapping: Dict[str, str] = {}
        for index, pending in enumerate(pendings):
            mapping[M.tool_call_id(run_id, index)] = json.dumps(pending, ensure_ascii=False)
        ops.hset_mapping(run_keys["pending"], mapping, ttl)
        ops.hset_mapping(run_keys["req"], {key: "1" for key in mapping}, ttl)
        ops.hset_mapping(run_keys["claim"], {key: "1|perf" for key in mapping}, ttl)
        ops.hset_mapping(run_keys["decided"], {"decided-%s" % run_id: "REJECTED|perf"}, ttl)
        ledger_entry = json.dumps({
            "toolCallId": list(mapping)[0],
            "name": "start_export",
            "outcome": "ok",
            "code": None,
            "executedBy": "perf",
            "atMs": now_ms,
        }, ensure_ascii=False)
        ops.rpush_values(run_keys["ledger"], [ledger_entry], ttl)
        first_tool_call = list(mapping)[0]
        ops.hset_mapping(run_keys["idem"], {
            "%s:%s" % (run_id, first_tool_call): "0|ok||perf|%d" % now_ms,
            "~meta:0": json.dumps({"name": "start_export", "atMs": now_ms, "toolCallId": first_tool_call}),
        }, ttl)
        ops.hset_mapping(K.observed_key(run_id, t, u), {first_tool_call: "1"}, ttl)
    else:
        ops.rpush_values(run_keys["ledger"], [json.dumps({"name": "list_tasks", "outcome": "ok", "atMs": now_ms})], ttl)

    if frame_entries:
        written = ops.xadd_frames(run_keys["xevents"], frame_entries, ttl)
        if written < len(frame_entries):
            raise RuntimeError("Stream archive incomplete runId=%s written=%d/%d"
                               % (run_id, written, len(frame_entries)))

    ops.set_values([
        (K.beat_key(run_id, t, u), str(now_ms), ttl),
        (K.activity_key(run_id, t, u), str(now_ms), ttl),
    ])
    if status == "CANCELLED":
        ops.set_values([(K.cancel_key(run_id, t, u), "CANCELLED", ttl)])

    generated = {"run", "runstate", "seq", "fence", "lease_epoch", "ledger"}
    if bool(spec.get("lease_held")):
        generated.add("lease")
    if pendings:
        generated.update(("pending", "claim", "idem", "req", "decided"))
    if frame_entries:
        generated.add("xevents")

    ok, slots = K.slot_check(list(run_keys.values()))
    return {
        "run_id": run_id,
        "status": status,
        "pending": pending_count,
        "lease_held": bool(spec.get("lease_held")),
        "frames": {"count": frame_count, "terminal": terminal, "gap": gap, "terminalType": terminal_type},
        "slot_check": {"single_slot": ok, "slots": sorted(set(slots.values()))},
        "keys": run_keys,
        "generated_keys": [name for name in K.RUN_KEYS_13 if name in generated],
    }


def _upload_attachments(cfg: Config, api: ApiClient, ops: RedisOps, session_id: str, run_id: str,
                        count: int, field_count: int) -> List[Dict[str, str]]:
    uploaded: List[Dict[str, str]] = []
    for index in range(1, count + 1):
        field_key = "evidence%d" % index
        filename = "perf-%s-%d.txt" % (session_id, index)
        content = ("perf attachment %d for %s\n" % (index, session_id)).encode("utf-8")
        result = api.upload_attachment(run_id, M.tool_call_id(run_id, 0), session_id, field_key,
                                       filename, content, "text/plain")
        business = result.get("business") or {}
        file_id = business.get("fileId") if isinstance(business, dict) else None
        if not result.get("ok") or not file_id:
            uploaded.append({
                "file_id": "",
                "field_key": field_key,
                "error": "upload failed status=%s transport_error=%s" % (
                    result.get("status"), result.get("transport_error", "")),
            })
            continue
        storage_key = ""
        try:
            registration_key = K.attachment_registration_key(str(file_id), cfg.tenant_id, cfg.user_id)
            raw = ops.get_str(registration_key)
            if raw:
                parsed = json.loads(raw)
                storage_key = str(parsed.get("storagePath") or "")
        except Exception:
            storage_key = ""
        uploaded.append({
            "file_id": str(file_id),
            "field_key": field_key,
            "storage_key": storage_key,
            "size": str(len(content)),
        })
    return uploaded


def generate_copy(cfg: Config, ops: RedisOps, api: Optional[ApiClient], scenario: str,
                  plan: Dict[str, object], copy_name: str) -> Dict[str, object]:
    ttl = cfg.key_ttl_seconds
    t = cfg.tenant_id
    u = cfg.user_id
    now_ms = _now_ms()
    session_id = M.session_id_for(cfg, scenario, copy_name)
    copy: Dict[str, object] = {
        "copy": copy_name,
        "session_id": session_id,
        "generated_at": M.utc_now_iso(),
        "runs": [],
        "staging_count": int(plan.get("staging_count", 0)),
        "archive_count": int(plan.get("archive_count", 0)),
        "ctx_turn_no": plan.get("ctx_turn_no"),
        "transcript_entries": int(plan.get("transcript_entries", 0)),
        "memory_messages": int(plan.get("memory_messages", 0)),
        "attachments": [],
        "feedback": [],
        "errors": [],
    }

    if plan.get("session_record"):
        ops.set_values([(
            K.session_record_key(session_id, t, u),
            json.dumps({
                "sessionId": session_id,
                "lastActivity": M.utc_now_iso(),
                "tenantId": t,
                "userId": u,
            }, ensure_ascii=False),
            ttl,
        )])

    memory_count = int(plan.get("memory_messages", 0))
    if memory_count > 0:
        messages = [_message_json("USER" if index % 2 == 0 else "ASSISTANT",
                                  "perf memory message %d for %s" % (index, session_id))
                    for index in range(memory_count)]
        ops.rpush_values(K.memory_data_key(session_id, t, u), messages, ttl)
        ops.set_values([
            (K.memory_version_key(session_id, t, u), "1", ttl),
            (K.memory_generation_key(session_id, t, u), "0", ttl),
        ])

    staging_count = int(plan.get("staging_count", 0))
    if staging_count > 0:
        staging_tokens = [M.staging_token(index) for index in range(staging_count)]
        for index, token in enumerate(staging_tokens):
            key = K.memory_staging_key(session_id, token, t, u)
            ops.rpush_values(key, [_message_json("USER", "staging %d" % index)], ttl)
        ops.sadd_members(K.memory_staging_index_key(session_id, t, u), staging_tokens, ttl)

    transcript_count = int(plan.get("transcript_entries", 0))
    if transcript_count > 0:
        first_run = M.run_id_for(cfg, scenario, copy_name, 0)
        entry_ids = ["e-%s-%06d" % (session_id, index) for index in range(transcript_count)]
        entries = [_transcript_entry(session_id, first_run, index, now_ms) for index in range(transcript_count)]
        total_bytes = sum(len(entry.encode("utf-8")) for entry in entries)
        ops.hset_mapping(K.transcript_data_key(session_id, t, u), dict(zip(entry_ids, entries)), ttl)
        ops.zadd_mapping(K.transcript_order_key(session_id, t, u),
                         {entry_id: float(index + 1) for index, entry_id in enumerate(entry_ids)}, ttl)
        meta = {
            "bytes": str(total_bytes),
            "seq": str(transcript_count),
            "rev": str(transcript_count),
        }
        meta.update({"fence:" + entry_id: "1" for entry_id in entry_ids})
        ops.hset_mapping(K.transcript_meta_key(session_id, t, u), meta, ttl)

    run_specs = plan.get("runs") or []
    run_ids: List[str] = []
    active_run_ids: List[str] = []
    suspended_run_ids: List[str] = []
    global_recovery_members: Dict[str, float] = {}
    for index, spec in enumerate(run_specs):
        run_id = M.run_id_for(cfg, scenario, copy_name, index)
        run_ids.append(run_id)
        written = _write_run(ops, cfg, run_id, session_id, spec, t, u, now_ms)
        copy["runs"].append(written)
        status = str(spec.get("status"))
        ops.zadd_mapping(K.run_list_index_key(t, u), {run_id: float(now_ms)}, ttl)
        if status in ("RUNNING", "SUSPENDED"):
            active_run_ids.append(run_id)
            due_at = float(now_ms + 180000)
            ops.zadd_mapping(K.identity_recovery_due_key(t, u), {run_id: due_at}, ttl)
            global_recovery_members[K.run_index_member(run_id, t, u)] = due_at
        if status == "SUSPENDED":
            suspended_run_ids.append(run_id)

    if run_ids:
        ops.sadd_members(K.session_runs_key(session_id, t, u), run_ids, ttl)
        if active_run_ids:
            ops.zadd_mapping(K.session_active_key(session_id, t, u),
                             {run_id: float(now_ms) for run_id in active_run_ids}, ttl)
        if suspended_run_ids:
            ops.set_values([(K.session_current_key(session_id, t, u), suspended_run_ids[0], ttl)])
        if cfg.write_global_run_index:
            try:
                ops.zadd_mapping(K.RECOVERY_DUE_INDEX, global_recovery_members, None)
            except Exception as exc:
                copy["errors"].append("ai:recovery:due ZADD failed: %s" % error_detail(exc))
        else:
            copy["global_run_index"] = "skipped (PERF_WRITE_GLOBAL_RUN_INDEX=false)"

    gate_holder = plan.get("gate_holder_index")
    if gate_holder is not None and run_specs:
        index = int(gate_holder)
        if 0 <= index < len(run_ids):
            ops.set_values([(
                K.session_lock_key(session_id, t, u),
                "%s|perf-token" % run_ids[index],
                min(ttl, 600),
            )])

    archive_count = int(plan.get("archive_count", 0))
    ctx_turn_no = plan.get("ctx_turn_no")
    if ctx_turn_no is not None:
        turn_no = int(ctx_turn_no)
        ops.hset_mapping(K.context_key(session_id, t, u), _context_fields(turn_no, now_ms), ttl)
        ops.rpush_values(K.context_ledger_key(session_id, t, u), [
            json.dumps({"kind": "compact", "level": "L1", "atMs": now_ms, "lost": 0}),
        ], ttl)
        ops.rpush_values(K.safety_key(session_id, t, u), [
            json.dumps({"atMs": now_ms, "ruleId": "perf", "action": "observe"}),
        ], ttl)
        ops.rpush_values(K.context_events_key(session_id, t, u), [
            json.dumps({"eventId": "perf-%s" % session_id, "atMs": now_ms}),
        ], ttl)
    if archive_count > 0:
        archives: List[Tuple[str, str, int]] = []
        for turn in range(archive_count):
            key = K.context_archive_key(session_id, turn, t, u)
            archives.append((key, json.dumps({"turnNo": turn, "text": "archived original %d" % turn}), ttl))
        ops.set_values(archives)

    if plan.get("session_record") or run_specs or memory_count > 0 or transcript_count > 0 or archive_count > 0:
        ops.hset_mapping(K.ctdraft_key(session_id, t, u), {"1": "perf-task"}, ttl)
        ops.hset_mapping(K.creq_key(session_id, t, u),
                         {"perf-client-request": run_ids[0] if run_ids else "none"}, ttl)

    # HTTP-backed faces: real upload endpoint / real feedback endpoint only.
    attachments = int(plan.get("attachments", 0))
    if attachments > 0:
        if api is None:
            copy["errors"].append("attachments requested but HTTP client unavailable")
        elif not suspended_run_ids:
            copy["errors"].append("attachments requested but no SUSPENDED run with a generative_form pending")
        else:
            field_count = int((plan.get("runs") or [{}])[0].get("pending_fields", attachments))
            copy["attachments"] = _upload_attachments(cfg, api, ops, session_id, suspended_run_ids[0],
                                                      attachments, field_count)

    feedback = int(plan.get("feedback", 0))
    if feedback > 0:
        if api is None:
            copy["errors"].append("feedback requested but HTTP client unavailable")
        else:
            rows: List[Dict[str, str]] = []
            for index in range(feedback):
                message_id = M.feedback_message_id(session_id, index)
                result = api.feedback_create(message_id, session_id, 1)
                rows.append({
                    "message_id": message_id,
                    "status": str(result.get("status")),
                    "ok": str(bool(result.get("ok"))).lower(),
                })
                if not result.get("ok"):
                    copy["errors"].append("feedback create failed status=%s transport_error=%s" % (
                        result.get("status"), result.get("transport_error", "")))
            copy["feedback"] = rows

    return copy


def generate(cfg: Config, manifest: Dict[str, object], selected: List[str]) -> Dict[str, object]:
    cfg.require_write_safety()
    ops = RedisOps(cfg)
    api: Optional[ApiClient] = None
    summary: Dict[str, object] = {"scenarios": {}, "background": {}, "errors": []}
    try:
        ops.connect()
        api = ApiClient(cfg)
        # Reachability is reported, not fatal: scenarios without HTTP faces can still be generated.
        reach = api.tenants()
        http_ready = bool(reach.get("ok"))
        summary["app_reachable"] = http_ready
        if not http_ready:
            summary["app_error"] = reach.get("transport_error") or (
                "HTTP %s" % reach.get("status") if reach.get("status") is not None else "request failed")

        matrix_needed: List[str] = []
        for scenario in selected:
            plan = plan_for(cfg, scenario)
            bucket = plan.get("background")
            if bucket:
                matrix_needed.append(str(bucket))
        for bucket in sorted(set(matrix_needed)):
            if not isinstance(manifest.get("background"), dict):
                manifest["background"] = {}
            background = manifest.setdefault("background", {})
            existing = background.get(bucket)
            count = cfg.sizes_for(bucket)
            start_index = 0
            if isinstance(existing, dict):
                prev_count = int(existing.get("planned") or existing.get("count") or 0)
                prev_written = int(existing.get("written") or 0)
                if prev_count == count and prev_written > 0 and prev_written <= count:
                    last_written = K.background_key(cfg.namespace, bucket, prev_written - 1)
                    if prev_written == count:
                        probes = K.background_probe_keys(cfg.namespace, bucket, count)
                        existence = ops.exists_many(probes)
                        unknown = [key for key in probes if existence.get(key) is None]
                        missing = [key for key in probes if existence.get(key) is False]
                        if unknown:
                            raise RuntimeError("cannot confirm background key existence bucket=%s keys=%s"
                                               % (bucket, ",".join(unknown[:3])))
                        if not missing:
                            existing["planned"] = count
                            existing["count"] = count
                            background[bucket] = existing
                            M.save_manifest(cfg, manifest)
                            summary["background"][bucket] = existing
                            continue
                        # Manifest says complete but exact probes are missing: rewrite.
                        start_index = 0
                    elif ops.exists(last_written):
                        start_index = prev_written
                    else:
                        start_index = 0
                elif prev_count == count and prev_written == 0:
                    start_index = 0
            state = _generate_background(cfg, ops, bucket, count, manifest, existing, start_index)
            background[bucket] = state
            summary["background"][bucket] = state
            if not state.get("done"):
                raise RuntimeError("background key generation incomplete bucket=%s written=%s/%s"
                                   % (bucket, state.get("written"), count))
            probes = K.background_probe_keys(cfg.namespace, bucket, count)
            existence = ops.exists_many(probes)
            unknown = [key for key in probes if existence.get(key) is None]
            missing = [key for key in probes if existence.get(key) is False]
            if unknown:
                raise RuntimeError("cannot confirm background key existence bucket=%s keys=%s"
                                   % (bucket, ",".join(unknown[:3])))
            if missing:
                raise RuntimeError("background keys missing from exact samples bucket=%s keys=%s"
                                   % (bucket, ",".join(missing[:3])))

        for scenario in selected:
            plan = plan_for(cfg, scenario)
            entry = manifest.setdefault("scenarios", {}).setdefault(scenario, {
                "plan": plan,
                "copies": [],
                "status": "pending",
            })
            if entry.get("status") == "generated":
                summary["scenarios"][scenario] = {"status": "already_generated"}
                continue
            needs_http = bool(plan.get("attachments")) or bool(plan.get("feedback"))
            if needs_http and not http_ready:
                entry["status"] = "unsupported"
                entry["reason"] = "real HTTP API required for attachments/feedback, but the application is unreachable: %s" % (
                    summary.get("app_error") or "unknown")
                summary["scenarios"][scenario] = {"status": "unsupported", "reason": entry["reason"]}
                M.save_manifest(cfg, manifest)
                continue
            entry["copies"] = []
            entry["plan"] = plan
            destructive = bool(plan.get("verify_mode") == "delete")
            names = M.copy_names(cfg, destructive)
            failed = False
            for copy_name in names:
                placeholder = {
                    "copy": copy_name,
                    "session_id": M.session_id_for(cfg, scenario, copy_name),
                    "status": "in_progress",
                    "runs": [],
                }
                entry["copies"].append(placeholder)
                M.save_manifest(cfg, manifest)
                copy = generate_copy(cfg, ops, api, scenario, plan, copy_name)
                entry["copies"][-1] = copy
                if copy.get("errors"):
                    failed = True
                M.save_manifest(cfg, manifest)
            entry["status"] = "failed" if failed else "generated"
            if failed:
                entry["reason"] = "some copies failed to generate; see copies[].errors"
            summary["scenarios"][scenario] = {
                "status": entry["status"],
                "copies": len(entry["copies"]),
                "reason": entry.get("reason", ""),
            }
            M.save_manifest(cfg, manifest)
    finally:
        if api is not None:
            api.close()
        ops.close()
    return summary


def _generate_background(cfg: Config, ops: RedisOps, bucket: str, count: int,
                         manifest: Dict[str, object], existing: object,
                         start_index: int) -> Dict[str, object]:
    namespace = cfg.namespace
    state: Dict[str, object] = dict(existing) if isinstance(existing, dict) else {}
    state.update({
        "bucket": bucket,
        "planned": count,
        "count": count,
        "written": max(0, min(int(start_index), count)),
        "batch_size": cfg.batch_size,
        "pattern": K.background_pattern(namespace, bucket),
        "done": count <= 0,
        "started_at": state.get("started_at") or M.utc_now_iso(),
    })
    state.pop("finished_at", None)
    manifest.setdefault("background", {})[bucket] = state
    M.save_manifest(cfg, manifest)
    if count <= 0:
        state["finished_at"] = M.utc_now_iso()
        M.save_manifest(cfg, manifest)
        return state

    written = int(state["written"])
    for start in range(written, count, cfg.batch_size):
        end = min(count, start + cfg.batch_size)
        chunk: List[Tuple[str, str, int]] = []
        for index in range(start, end):
            key = K.background_key(namespace, bucket, index)
            chunk.append((key, "perf-bg-%s-%d" % (bucket, index), cfg.key_ttl_seconds))
        wrote = ops.set_values(chunk)
        if wrote != len(chunk):
            raise RuntimeError("background key batch write incomplete bucket=%s batch=%d..%d wrote=%d/%d"
                               % (bucket, start, end - 1, wrote, len(chunk)))
        written = end
        state["written"] = written
        state["done"] = written >= count
        state["progress_at"] = M.utc_now_iso()
        # Progress is persisted per batch so an interrupted run can resume and
        # cleanup knows the deterministic key range without a SCAN.
        M.save_manifest(cfg, manifest)
    state["written"] = written
    state["done"] = written >= count
    state["finished_at"] = M.utc_now_iso()
    M.save_manifest(cfg, manifest)
    return state
