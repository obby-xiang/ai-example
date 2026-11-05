"""Scoped cleanup: only this namespace's sessions/files, never a global flush.

Default is a dry-run. Execution needs PERF_ISOLATED_ENV=true (same gate as
generate/measure). Every Redis target is derived from manifest ids, deterministic
key formulas, or registered index members; cleanup never scans the keyspace.
"""

from __future__ import annotations

import json
import os
from typing import Dict, List, Tuple

from . import derive as D
from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps, RedisUnavailable


def _synthetic_copy(cfg: Config, scenario: str, plan: Dict[str, object], copy: Dict[str, object]) -> Dict[str, object]:
    """Fill run ids for an interrupted copy (placeholder written before generation)."""
    if copy.get("runs"):
        return copy
    runs = []
    for index, spec in enumerate(plan.get("runs") or []):
        runs.append({
            "run_id": M.run_id_for(cfg, scenario, str(copy.get("copy")), index),
            "status": (spec or {}).get("status", "DONE"),
            "keys": K.run_keys_13(M.run_id_for(cfg, scenario, str(copy.get("copy")), index),
                                  cfg.tenant_id, cfg.user_id),
        })
    filled = dict(copy)
    filled["runs"] = runs
    return filled


def _dedupe_index_members(refs: List[Dict[str, str]]) -> List[Dict[str, str]]:
    seen = set()
    out: List[Dict[str, str]] = []
    for ref in refs:
        identity = (str(ref["key"]), str(ref["member"]))
        if identity in seen:
            continue
        seen.add(identity)
        out.append(ref)
    return out


def _build_targets(cfg: Config, manifest: Dict[str, object], ops: RedisOps) -> Dict[str, object]:
    exact: List[str] = []
    due_members: List[str] = []
    index_members: List[Dict[str, str]] = []
    runs: List[str] = []
    sessions: List[str] = []
    files: List[Tuple[str, str]] = []
    feedback: List[Tuple[str, str]] = []
    background_map: Dict[str, Dict[str, object]] = {}
    skipped: List[Dict[str, str]] = []
    unknown_attachment_index: List[str] = []
    unknown_background: List[str] = []

    for scenario, entry in (manifest.get("scenarios") or {}).items():
        if not isinstance(entry, dict):
            continue
        plan = entry.get("plan") or {}
        for copy in entry.get("copies") or []:
            if not isinstance(copy, dict):
                continue
            filled = _synthetic_copy(cfg, scenario, plan, copy)
            derived = D.copy_keys(cfg, scenario, plan, filled)
            exact.extend(derived["expected_absent_on_clean"])
            exact.append(str(derived["guard"]))
            exact.append(str(derived["pending"]))
            exact.append(str(derived["lock"]))
            exact.append(str(derived["memory"]["ver"]))
            exact.append(str(derived["memory"]["gen"]))
            # Attachment registrations are also discovered from the live session index,
            # so an interrupted upload (manifest has no fileId yet) is still cleanable.
            current_session_id = str(derived["session_id"])
            session_index = str(derived["attachments"]["session_index"])
            exact.append(session_index)
            members = ops.smembers(session_index)
            if members is None:
                skipped.append({
                    "file_id": "-",
                    "reason": "附件会话索引读取失败，无法枚举登记（不猜测、不删除文件）：%s" % session_index,
                })
                unknown_attachment_index.append(session_index)
                members = []
            for file_id in members:
                registration = K.attachment_registration_key(file_id, cfg.tenant_id, cfg.user_id)
                raw = ops.get_str(registration)
                storage_key = ""
                form_run_id = ""
                form_tool_call_id = ""
                if raw:
                    try:
                        parsed = json.loads(raw)
                    except ValueError:
                        parsed = None
                    if isinstance(parsed, dict):
                        registered_session = str(parsed.get("sessionId") or "")
                        if not registered_session:
                            skipped.append({
                                "file_id": file_id,
                                "reason": "登记缺少 sessionId，无法证明归属，跳过（不删文件/登记）",
                            })
                            continue
                        if registered_session != current_session_id:
                            skipped.append({
                                "file_id": file_id,
                                "reason": "登记 sessionId=%s 与索引会话=%s 不一致，跳过（不删他会话文件）"
                                          % (registered_session, current_session_id),
                            })
                            continue
                        storage_key = str(parsed.get("storagePath") or "")
                        form_run_id = str(parsed.get("runId") or "")
                        form_tool_call_id = str(parsed.get("toolCallId") or "")
                    else:
                        skipped.append({
                            "file_id": file_id,
                            "reason": "登记 JSON 不可解析，跳过（避免删登记后留下孤儿文件）",
                        })
                        continue
                exact.append(registration)
                exact.append(K.attachment_consumed_key(file_id, cfg.tenant_id, cfg.user_id))
                if storage_key:
                    files.append((file_id, storage_key))
                if form_run_id and form_tool_call_id:
                    exact.append(K.attachment_form_index_key(form_run_id, form_tool_call_id,
                                                             cfg.tenant_id, cfg.user_id))
            due_members.append(str(derived["pending"]))
            index_members.extend(derived["run_index_refs"])
            runs.extend(list(derived["runs"].keys()))
            for run_id in derived["runs"].keys():
                exact.append(K.run_scoped(K.XTRIM_PREFIX, run_id, cfg.tenant_id, cfg.user_id))
                exact.append(K.run_scoped(K.XACK_PREFIX, run_id, cfg.tenant_id, cfg.user_id))
            sessions.append(str(derived["session_id"]))
            for item in filled.get("attachments") or []:
                if isinstance(item, dict) and item.get("storage_key"):
                    files.append((str(item.get("file_id") or ""), str(item.get("storage_key"))))
            for item in filled.get("feedback") or []:
                if isinstance(item, dict) and item.get("message_id"):
                    feedback.append((str(item.get("message_id")), str(derived["session_id"])))
        bucket = plan.get("background")
        if bucket:
            bucket_name = str(bucket)
            if bucket_name not in background_map:
                state = (manifest.get("background") or {}).get(bucket_name) \
                    if isinstance(manifest.get("background"), dict) else None
                planned = 0
                written = 0
                if isinstance(state, dict):
                    try:
                        planned = int(state.get("planned") or state.get("count") or 0)
                        written = int(state.get("written") or 0)
                    except (TypeError, ValueError):
                        planned = 0
                        written = 0
                if planned <= 0:
                    unknown_background.append(bucket_name)
                else:
                    background_map[bucket_name] = {
                        "bucket": bucket_name,
                        "planned": planned,
                        "written": written,
                    }
    background_manifest = manifest.get("background") or {}
    if isinstance(background_manifest, dict):
        for bucket_name, state in background_manifest.items():
            bucket_name = str(bucket_name)
            if bucket_name in background_map:
                continue
            planned = 0
            written = 0
            if isinstance(state, dict):
                try:
                    planned = int(state.get("planned") or state.get("count") or 0)
                    written = int(state.get("written") or 0)
                except (TypeError, ValueError):
                    planned = 0
                    written = 0
            if planned <= 0:
                if bucket_name not in unknown_background:
                    unknown_background.append(bucket_name)
            else:
                background_map[bucket_name] = {
                    "bucket": bucket_name,
                    "planned": planned,
                    "written": written,
                }
    if cfg.keep_guard:
        exact = [key for key in exact
                 if not key.startswith(K.PURGE_GUARD_PREFIX) and not key.startswith(K.PURGE_PENDING_PREFIX)]
        due_members = []
    return {
        "exact": K.dedupe(exact),
        "due_members": K.dedupe(due_members),
        "index_members": _dedupe_index_members(index_members),
        "runs": K.dedupe(runs),
        "sessions": K.dedupe(sessions),
        "files": files,
        "feedback": feedback,
        "background": list(background_map.values()),
        "skipped": skipped,
        "unknown_attachment_index": unknown_attachment_index,
        "unknown_background": unknown_background,
    }


def _file_plan(cfg: Config, files: List[Tuple[str, str]]) -> List[Dict[str, object]]:
    out: List[Dict[str, object]] = []
    root = cfg.app_file_storage_path
    for file_id, storage_key in files:
        entry: Dict[str, object] = {
            "file_id": file_id,
            "storage_key": storage_key,
            "action": "report",
            "status": "info",
            "detail": "",
        }
        if not root:
            entry["detail"] = "未配置 PERF_APP_FILE_STORAGE_PATH，无法安全定位文件"
            entry["exists"] = None
            out.append(entry)
            continue
        candidate = os.path.normpath(os.path.join(root, storage_key))
        root_norm = os.path.normpath(root)
        root_real = os.path.realpath(root_norm)
        candidate_real = os.path.realpath(candidate)
        entry["path"] = candidate_real
        entry["exists"] = os.path.exists(candidate_real)
        if candidate_real == root_real:
            entry["status"] = "fail"
            entry["detail"] = "storageKey 指向存储根目录本身，拒绝删除"
            out.append(entry)
            continue
        if not candidate_real.startswith(root_real + os.sep):
            entry["status"] = "fail"
            entry["detail"] = "storageKey 越出配置的存储根目录（含符号链接解析），拒绝删除"
            out.append(entry)
            continue
        if not entry["exists"]:
            entry["detail"] = "文件不存在（可能已被应用删除），无需删除"
            out.append(entry)
            continue
        if os.path.islink(candidate) or os.path.islink(candidate_real) or not os.path.isfile(candidate_real):
            entry["status"] = "fail"
            entry["detail"] = "目标不是根目录下的普通文件（目录/符号链接等），拒绝删除"
            out.append(entry)
            continue
        if not cfg.allow_file_delete:
            entry["detail"] = "文件存在但 PERF_ALLOW_FILE_DELETE=false（只报告不删除）"
            out.append(entry)
            continue
        entry["action"] = "delete"
        out.append(entry)
    return out


def _delete_background_exact(cfg: Config, ops: RedisOps, bucket: str, count: int,
                             rows: List[Dict[str, object]]) -> Dict[str, object]:
    """Delete 0..planned-1 by deterministic key formula, in batches.

    Never SCAN: the whole point is that a 6M-key keyspace must not be traversed
    for background data. Each batch is deleted and then checked by exact EXISTS.
    """
    result: Dict[str, object] = {
        "bucket": bucket,
        "planned": count,
        "deleted": 0,
        "checked": 0,
        "remaining": 0,
        "unknown": 0,
        "errors": [],
        "complete": False,
    }
    if count <= 0:
        result["reason"] = "manifest 未记录 planned count，无法精确删除背景键"
        return result
    for start in range(0, count, cfg.batch_size):
        end = min(count, start + cfg.batch_size)
        keys = [K.background_key(cfg.namespace, bucket, index) for index in range(start, end)]
        deleted = ops.delete_keys(keys)
        result["deleted"] = int(result["deleted"]) + int(deleted.get("deleted", 0))
        errors = list(deleted.get("errors", []))
        if errors:
            result["errors"].extend(str(item) for item in errors)
            for error in errors:
                rows.append({
                    "kind": "background",
                    "key": "%s[%d..%d]" % (bucket, start, end - 1),
                    "action": "delete",
                    "status": "fail",
                    "detail": str(error),
                })
        existence = ops.exists_many(keys)
        unknown = [key for key in keys if existence.get(key) is None]
        remaining = [key for key in keys if existence.get(key) is True]
        result["checked"] = int(result["checked"]) + len(keys)
        result["unknown"] = int(result["unknown"]) + len(unknown)
        result["remaining"] = int(result["remaining"]) + len(remaining)
        if remaining:
            rows.append({
                "kind": "background",
                "key": "%s[%d..%d]" % (bucket, start, end - 1),
                "action": "delete",
                "status": "fail",
                "detail": "批次删除后仍存在 %d 个" % len(remaining),
            })
        if unknown:
            rows.append({
                "kind": "background",
                "key": "%s[%d..%d]" % (bucket, start, end - 1),
                "action": "delete",
                "status": "unknown",
                "detail": "批次删除后存在性不可判 %d 个" % len(unknown),
            })
    result["complete"] = (
        int(result["remaining"]) == 0
        and int(result["unknown"]) == 0
        and not result["errors"]
    )
    if not result["complete"]:
        result["reason"] = "背景键仍有剩余或不可判（未伪报干净）"
    return result


def cleanup(cfg: Config, manifest: Dict[str, object], execute: bool) -> Dict[str, object]:
    started_at = M.utc_now_iso()
    rows: List[Dict[str, object]] = []
    if execute:
        cfg.require_write_safety()
    ops = RedisOps(cfg)
    api = ApiClient(cfg)
    summary: Dict[str, object] = {
        "executed": bool(execute),
        "started_at": started_at,
        "namespace": cfg.namespace,
        "keep_guard": cfg.keep_guard,
        "allow_file_delete": cfg.allow_file_delete,
    }
    try:
        ops.connect()
        if cfg.is_cluster():
            # Refuse to plan/delete against a partial topology: every primary
            # must be reachable before any cleanup decision is made.
            authoritative = ops.primaries(allow_seed_fallback=False)
            if not authoritative:
                raise RedisUnavailable("无法读取 cluster 主节点拓扑，拒绝开始清理")
            ops.primary_node_clients(strict=True, targets=authoritative)
        targets = _build_targets(cfg, manifest, ops)
        if not (manifest.get("scenarios") or manifest.get("background")):
            targets["unknown_background"].append(
                "manifest 为空：无法确认是否存在历史背景键（未做全库 SCAN，不伪报干净）"
            )
        exact = list(targets["exact"])
        background = list(targets["background"])
        files = _file_plan(cfg, list(targets["files"]))
        file_delete_result: List[Dict[str, object]] = []

        presence = ops.exists_many(exact) if exact else {}
        present_exact = [key for key in exact if presence.get(key) is True]
        unknown_exact = [key for key in exact if presence.get(key) is None]
        background_plan = [dict(item) for item in background]

        summary["plan"] = {
            "exact_keys": len(exact),
            "exact_present": len(present_exact),
            "exact_unknown": len(unknown_exact),
            "exact_sample": present_exact[:20],
            "due_members": targets["due_members"],
            "run_index_members": targets["index_members"],
            "runs": targets["runs"],
            "sessions": targets["sessions"],
            "background": background_plan,
            "files": files,
            "feedback": [
                {"message_id": message_id, "session_id": session_id}
                for message_id, session_id in targets["feedback"]
            ],
            "skipped": targets["skipped"],
            "unknown_attachment_index": targets["unknown_attachment_index"],
            "unknown_background": targets["unknown_background"],
        }

        if not execute:
            summary["complete"] = None
            summary["remaining"] = {"exact": present_exact, "exact_unknown": unknown_exact,
                                     "scanned": [], "background": background_plan}
            summary["files"] = files
            summary["feedback"] = summary["plan"]["feedback"]
            summary["finished_at"] = M.utc_now_iso()
            return summary

        # 1) stop the retry ledger first (it would otherwise re-run the purge later)
        if targets["due_members"]:
            removed = ops.zrem_members(K.PURGE_DUE_KEY, targets["due_members"])
            summary["due_removed"] = removed
        if targets["index_members"]:
            members_by_key: Dict[str, List[str]] = {}
            for ref in targets["index_members"]:
                members_by_key.setdefault(str(ref["key"]), []).append(str(ref["member"]))
            summary["index_members_removed"] = {
                key: ops.zrem_members(key, members)
                for key, members in members_by_key.items()
            }

        # 2) exact session/run keys
        result = ops.delete_keys(exact)
        summary["deleted_exact"] = result
        for error in result.get("errors", []):
            rows.append({"kind": "exact", "key": "-", "action": "delete", "status": "fail",
                         "detail": str(error)})

        # 3) background keys: deterministic manifest range, no SCAN
        background_results: List[Dict[str, object]] = []
        for item in background:
            if not isinstance(item, dict):
                continue
            bucket = str(item.get("bucket") or "")
            planned = int(item.get("planned") or 0)
            background_results.append(_delete_background_exact(cfg, ops, bucket, planned, rows))
        summary["background"] = background_results

        # 4) archive/staging/run leftovers are already in the exact manifest plan.
        summary["scans"] = []

        # 5) files (only with an explicit allow + a verified storage root)
        for entry in files:
            if entry.get("action") != "delete":
                file_delete_result.append(entry)
                continue
            path = str(entry.get("path"))
            try:
                root_real = os.path.realpath(os.path.normpath(cfg.app_file_storage_path))
                current_real = os.path.realpath(path)
                if current_real == root_real or not current_real.startswith(root_real + os.sep):
                    entry["status"] = "fail"
                    entry["detail"] = "删除前复核失败：路径不在存储根目录之下"
                    file_delete_result.append(entry)
                    continue
                path = current_real
                entry["path"] = path
                if os.path.islink(path) or (os.path.exists(path) and not os.path.isfile(path)):
                    entry["status"] = "fail"
                    entry["detail"] = "删除前复核失败：目标不是普通文件"
                    file_delete_result.append(entry)
                    continue
                if os.path.exists(path):
                    os.remove(path)
                entry["status"] = "pass"
                entry["detail"] = "已删除" if not os.path.exists(path) else "删除后仍存在"
                if os.path.exists(path):
                    entry["status"] = "fail"
            except OSError as exc:
                entry["status"] = "fail"
                entry["detail"] = "删除失败：%s" % exc
            file_delete_result.append(entry)
        summary["files"] = file_delete_result

        # 6) feedback rows through the real API (identity-scoped, rating=0)
        feedback_result: List[Dict[str, object]] = []
        for message_id, session_id in targets["feedback"]:
            result = api.feedback_cancel(message_id, session_id)
            feedback_result.append({
                "message_id": message_id,
                "session_id": session_id,
                "status": result.get("status"),
                "ok": bool(result.get("ok")),
                "error": result.get("transport_error", ""),
            })
        summary["feedback"] = feedback_result

        # 7) final residue check
        presence = ops.exists_many(exact) if exact else {}
        remaining_exact = [key for key in exact if presence.get(key) is True]
        unknown_exact = [key for key in exact if presence.get(key) is None]
        remaining_due = [member for member in targets["due_members"] if ops.zscore(K.PURGE_DUE_KEY, member) is not None]
        remaining_index = []
        unknown_index = []
        for ref in targets["index_members"]:
            try:
                if ops.client.zscore(ref["key"], ref["member"]) is not None:
                    remaining_index.append(ref)
            except Exception:
                unknown_index.append(ref)
        remaining_background = [item for item in background_results if not item.get("complete")]
        summary["remaining"] = {
            "exact": remaining_exact,
            "exact_unknown": unknown_exact,
            "due": remaining_due,
            "index_members": remaining_index,
            "index_unknown": unknown_index,
            "scanned": [],
            "background": remaining_background,
            "unknown_background": targets["unknown_background"],
        }
        feedback_failed = [item for item in feedback_result if not item.get("ok")]
        file_failed = [item for item in file_delete_result if item.get("status") == "fail"]
        file_left = [item for item in file_delete_result
                     if item.get("action") != "delete" and item.get("exists") is not False]
        summary["complete"] = (
            not remaining_exact
            and not unknown_exact
            and not remaining_due
            and not remaining_index
            and not unknown_index
            and not remaining_background
            and not targets["unknown_background"]
            and not feedback_failed
            and not file_failed
            and not file_left
            and not targets["unknown_attachment_index"]
        )
        summary["files_left"] = file_left
    finally:
        api.close()
        ops.close()
    summary["finished_at"] = M.utc_now_iso()
    summary["rows"] = rows
    return summary
