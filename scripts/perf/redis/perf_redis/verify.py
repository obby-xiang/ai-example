"""Post-conditions derived from the real delete semantics.

Hard rules (from AiSessionPurgeService / RunStore / SessionPurgeGuard):
  * complete=true  => every owned session key is gone; guard/pending/ver/gen are
    intentional tombstones and must NOT be reported as residue;
  * complete=false => a deferred/active run keeps every run key generation
    actually wrote (pending/claim/idem/req/decided exist only for runs with
    pending entries; lease only when lease_held); pending+guard and the
    discovery keys stay so a retry can enumerate them;
  * aborted=true   => nothing in Redis was deleted;
  * xack (120s) / xtrim / session lock are deliberate leftovers, never failures.

Unreadable state is reported as `unknown`, never as pass.
"""

from __future__ import annotations

import json
import time
from typing import Dict, List, Optional

from . import derive as D
from . import keys as K
from . import manifest as M
from .api import ApiClient
from .envcfg import Config
from .redisops import RedisOps


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
               "manifest 未记录 bucket %s 的 planned/count，无法精确抽查" % bucket)
        return
    if count <= 0:
        _check(checks, scenario, copy_name, "background_preserved", "info",
               "bucket %s planned=0，无背景键可抽查" % bucket)
        return
    probes = K.background_probe_keys(cfg.namespace, str(bucket), count)
    existence = ops.exists_many(probes)
    unknown = [key for key in probes if existence.get(key) is None]
    missing = [key for key in probes if existence.get(key) is False]
    if missing:
        _check(checks, scenario, copy_name, "background_preserved", "fail",
               "背景键精确抽样缺失 bucket=%s：%s" % (bucket, _sample(missing)))
    elif unknown:
        _check(checks, scenario, copy_name, "background_preserved", "unknown",
               "背景键精确抽样读取失败 bucket=%s：%s" % (bucket, _sample(unknown)))
    else:
        _check(checks, scenario, copy_name, "background_preserved", "pass",
               "背景键首/中/尾抽样仍存在 bucket=%s（%d 个样本，非全键空间证明）"
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
                   "未记录 storageKey（登记读取失败或上传失败），文件存在性不可判")
            continue
        if not root:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "unknown",
                   "未配置 PERF_APP_FILE_STORAGE_PATH，无法核对文件 key=%s" % storage_key)
            continue
        candidate = os.path.normpath(os.path.join(root, storage_key))
        root_norm = os.path.normpath(root)
        if not candidate.startswith(root_norm + os.sep) and candidate != root_norm:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "fail",
                   "storageKey 越出配置的存储根目录：%s" % storage_key)
            continue
        if os.path.exists(candidate):
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "fail",
                   "文件仍存在：%s" % candidate)
        else:
            _check(checks, scenario, copy_name, "attachment_file_%s" % file_id, "pass",
                   "文件已删除：%s" % storage_key)


def _check_feedback(api: ApiClient, checks: List[Dict[str, object]], scenario: str, copy_name: str,
                    copy: Dict[str, object]) -> None:
    rows = [item for item in copy.get("feedback") or [] if isinstance(item, dict)]
    if not rows:
        return
    result = api.feedback_list()
    if not result.get("ok"):
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "unknown",
               "读取 /api/ai/feedback 失败，无法确认 DB 行：status=%s" % result.get("status"))
        return
    business = result.get("business") or {}
    message_ids = [str(item) for item in (business.get("messageIds") or [])]
    expected = [str(item.get("message_id")) for item in rows]
    remaining = [item for item in expected if item in message_ids]
    if remaining:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "fail",
               "反馈行仍可见（最多 100 条列表内）：%s" % _sample(remaining))
    elif len(message_ids) >= 100:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "info",
               "反馈列表达到 100 条上限，无法证明本会话行不存在；请按 messageId 手工复核")
    else:
        _check(checks, scenario, copy_name, "feedback_rows_deleted", "pass",
               "本会话反馈行未出现在 /api/ai/feedback（列表上限 100，已按 messageId 核对）")


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
               "13 键不在同一 slot（Cluster 多键 DEL 会失去原子性）：%s" % slot_check.get("slots"))


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
               "complete=true 但仍有残留 %d 个：%s" % (len(residue), _sample(residue)))
    elif unknown:
        _check(checks, scenario, copy_name, "owned_keys_absent", "unknown",
               "无法确认 %d 个键是否存在（Redis 读失败）：%s" % (len(unknown), _sample(unknown)))
    else:
        _check(checks, scenario, copy_name, "owned_keys_absent", "pass",
               "全部 %d 个本会话键已不存在" % len(expected))

    for run_id in keys["runs"]:
        info = (merged.get("runs") or {}).get(run_id)
        if not isinstance(info, dict):
            _check(checks, scenario, copy_name, "run_%s_reported" % run_id, "unknown",
                   "响应未列出该 runId（可能被归属复核跳过）")
            continue
        if info.get("deferred"):
            _check(checks, scenario, copy_name, "run_%s_not_deferred" % run_id, "fail",
                   "complete=true 但 run 被标记 deferred")
        else:
            _check(checks, scenario, copy_name, "run_%s_deleted" % run_id, "pass",
                   "响应标记 deleted=%s" % info.get("deleted"))

    guard = str(keys["guard"])
    pending = str(keys["pending"])
    guard_ttl = ops.ttl(guard)
    if guard_ttl > 0:
        _check(checks, scenario, copy_name, "guard_retained", "pass",
               "guard 为有意保留（TTL=%ss）" % guard_ttl)
    elif guard_ttl == -2:
        _check(checks, scenario, copy_name, "guard_retained", "fail",
               "complete=true 但 guard 不存在（源码应保留）")
    else:
        _check(checks, scenario, copy_name, "guard_retained", "unknown",
               "guard TTL 不可读（ttl=%s）" % guard_ttl)

    pending_ttl = ops.ttl(pending)
    if pending_ttl == -2:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "info",
               "pending 已被 60s 记忆复核移除（或尚未排程前结束）")
    elif pending_ttl == -3:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "unknown",
               "pending TTL 不可读")
    elif pending_ttl != -1:
        _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
               "pending 存在但 TTL 不是 -1（源码为无 TTL 台账）：ttl=%s" % pending_ttl)
    else:
        raw = ops.get_str(pending) or ""
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = None
        if not isinstance(parsed, dict):
            _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                   "pending JSON 不可解析，无法确认 verifyAtMs")
        elif parsed.get("verifyAtMs") is None:
            _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                   "complete=true 但 pending 没有 verifyAtMs（应为 60s 记忆复核台账）")
        else:
            due = ops.zscore(K.PURGE_DUE_KEY, pending)
            if due is None:
                _check(checks, scenario, copy_name, "pending_verify_ledger", "fail",
                       "pending 存在但不在 ai:purge:retry:due（复核不会被执行）")
            else:
                _check(checks, scenario, copy_name, "pending_verify_ledger", "pass",
                       "pending.verifyAtMs=%s 且已排入 due" % parsed.get("verifyAtMs"))

    memory = keys["memory"]
    ttl_ver = ops.ttl(str(memory["ver"]))
    ttl_gen = ops.ttl(str(memory["gen"]))
    if ttl_ver > 0 and ttl_gen > 0:
        _check(checks, scenario, copy_name, "memory_tombstones", "pass",
               "gen/ver 为有意 tombstone（TTL=%s/%s）" % (ttl_gen, ttl_ver))
    elif ttl_ver == -2 or ttl_gen == -2:
        _check(checks, scenario, copy_name, "memory_tombstones", "fail",
               "gen/ver 缺失（gen=%s ver=%s；源码 MEMORY_CLEAR 应创建并设 TTL）" % (ttl_gen, ttl_ver))
    elif ttl_ver < 0 or ttl_gen < 0:
        _check(checks, scenario, copy_name, "memory_tombstones", "unknown",
               "gen/ver TTL 不可读（gen=%s ver=%s）" % (ttl_gen, ttl_ver))
    else:
        _check(checks, scenario, copy_name, "memory_tombstones", "fail",
               "gen/ver 无 TTL（gen=%s ver=%s）" % (ttl_gen, ttl_ver))

    for run_id in list(keys["runs"])[:3]:
        if keys["runs"][run_id].get("status") not in ("RUNNING", "SUSPENDED"):
            continue
        # xack keys carry a random token, so enumerating them would require a
        # keyspace SCAN. They are an intentional 120s TTL tombstone; do not scan.
        _check(checks, scenario, copy_name, "xack_ttl_%s" % run_id, "info",
               "xack 为 120s 有意保留；为避免无匹配全库 SCAN 不枚举随机 token，按 TTL 自清")
        trim_key = K.run_scoped(K.XTRIM_PREFIX, run_id, cfg.tenant_id, cfg.user_id)
        if ops.exists(trim_key):
            ttl = ops.ttl(trim_key)
            _check(checks, scenario, copy_name, "xtrim_retained_%s" % run_id,
                   "pass" if ttl > 0 else "info",
                   "xtrim 为有意保留（不在 13 键内，TTL=%s）" % ttl)
    if ops.exists(str(keys["lock"])):
        ttl = ops.ttl(str(keys["lock"]))
        _check(checks, scenario, copy_name, "session_lock_retained", "info",
               "会话锁由 TTL 收敛（源码有意不删）：TTL=%s" % ttl)

    members = keys["run_index_members"]
    still = []
    for member in members:
        try:
            if ops.client.sismember(K.RUN_INDEX, member):
                still.append(member)
        except Exception:
            still.append(member)
    if still:
        _check(checks, scenario, copy_name, "run_index_members_removed", "fail",
               "ai:runs 全局索引成员仍在：%s" % _sample(still))
    else:
        _check(checks, scenario, copy_name, "run_index_members_removed", "pass",
               "ai:runs 成员不存在（写入开关=%s；SREM 生效或未写入）" % cfg.write_global_run_index)

    business = merged.get("business") or {}
    memory_business = business.get("memory") if isinstance(business, dict) else None
    if isinstance(memory_business, dict) and memory_business.get("existed") is True:
        if memory_business.get("deleted") is True:
            _check(checks, scenario, copy_name, "response_memory_deleted", "pass", "memory.deleted=true")
        else:
            _check(checks, scenario, copy_name, "response_memory_deleted", "fail",
                   "complete=true 但 memory.existed=true/memory.deleted=%s" % memory_business.get("deleted"))
    transcript_business = business.get("transcript") if isinstance(business, dict) else None
    if isinstance(transcript_business, dict) and transcript_business.get("existed") is True:
        if transcript_business.get("deleted") is True:
            _check(checks, scenario, copy_name, "response_transcript_deleted", "pass",
                   "transcript.deleted=true")
        else:
            _check(checks, scenario, copy_name, "response_transcript_deleted", "fail",
                   "complete=true 但 transcript.existed=true/deleted=%s"
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
                   "attachments %s/%s 登记已删" % (deleted_regs, registered))

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
           "measure 预算耗尽（status=%s）%s；该场景不能作为性能通过依据"
           % (status, ("：" + str(detail)) if detail else ""))
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
                   "deferred run 不在生成清单中")
            continue
        generated = info.get("generated_keys") or {}
        unresolved = [str(name) for name in (info.get("generated_unknown") or [])]
        if not generated and not unresolved:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "unknown",
                   "manifest 未记录该 run 实际生成的键，无法判定保留是否完整")
            continue
        expected = list(generated.values())
        existence = ops.exists_many(expected) if expected else {}
        missing = [key for key in expected if existence.get(key) is False]
        unreadable = [key for key in expected if existence.get(key) is None]
        if missing:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "fail",
                   "deferred run 的已生成键被删了 %d/%d 个（源码不得删活跃 run）：%s"
                   % (len(missing), len(expected), _sample(missing)))
        elif unreadable or unresolved:
            parts = []
            if unreadable:
                parts.append("已生成键读取失败 %d 个：%s" % (len(unreadable), _sample(unreadable)))
            if unresolved:
                parts.append("旧 manifest 未记录 %s，是否生成不可判" % _sample(unresolved))
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "unknown",
                   "已生成键 %d 个中已确认 %d 个仍存在；%s"
                   % (len(expected), len(expected) - len(missing) - len(unreadable), "；".join(parts)))
        else:
            _check(checks, scenario, copy_name, "deferred_run_%s_intact" % run_id, "pass",
                   "deferred run 的 %d 个已生成键全部保留（按 manifest 清单，非 13 键全量）"
                   % len(expected))


def _check_retained(cfg: Config, ops: RedisOps, api: ApiClient, checks: List[Dict[str, object]], scenario: str,
                    copy_name: str, plan: Dict[str, object], copy: Dict[str, object],
                    merged: Dict[str, object], background_counts: Dict[str, int]) -> None:
    keys = D.copy_keys(cfg, scenario, plan, copy)
    deferred = [str(item) for item in merged.get("deferred") or []]
    errors = [str(item) for item in merged.get("errors") or []]
    if not deferred and not errors:
        _check(checks, scenario, copy_name, "incomplete_has_reason", "fail",
               "complete=false 但既无 deferred 也无 errors（响应语义不一致）")
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
                   "响应 deleted=true 但仍有键：%s" % _sample(residue))
        elif unknown:
            _check(checks, scenario, copy_name, "deleted_run_%s_absent" % run_id, "unknown",
                   "删除结果不可判：%s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "deleted_run_%s_absent" % run_id, "pass",
                   "已删 run 的 13 键不存在")

    pending_ttl = ops.ttl(str(keys["pending"]))
    if pending_ttl == -1:
        _check(checks, scenario, copy_name, "pending_retained", "pass", "durable pending 保留（无 TTL）")
    elif pending_ttl == -3:
        _check(checks, scenario, copy_name, "pending_retained", "unknown", "pending TTL 不可读")
    else:
        _check(checks, scenario, copy_name, "pending_retained", "fail",
               "失败/延后时 pending 必须保留，ttl=%s" % pending_ttl)
    guard_ttl = ops.ttl(str(keys["guard"]))
    if guard_ttl > 0:
        _check(checks, scenario, copy_name, "guard_retained", "pass", "guard 保留 TTL=%ss" % guard_ttl)
    elif guard_ttl == -3:
        _check(checks, scenario, copy_name, "guard_retained", "unknown", "guard TTL 不可读")
    else:
        _check(checks, scenario, copy_name, "guard_retained", "fail",
               "失败/延后时 guard 应保留，ttl=%s" % guard_ttl)

    attachment_keys = list(keys["attachments"]["registrations"])
    if attachment_keys:
        existence = ops.exists_many(attachment_keys)
        alive = [key for key in attachment_keys if existence.get(key) is True]
        unknown = [key for key in attachment_keys if existence.get(key) is None]
        if alive:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "fail",
                   "即使 run deferred，附件登记也应在本轮删除；仍有：%s" % _sample(alive))
        elif unknown:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "unknown",
                   "附件登记存在性不可判：%s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "attachments_deleted_on_defer", "pass",
                   "附件登记已删除（与 run 是否 deferred 无关）")
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
               "发现键至少一个保留（重试仍可枚举）")
    elif unknown:
        _check(checks, scenario, copy_name, "discovery_kept", "unknown",
               "发现键存在性不可判：%s" % _sample(unknown))
    elif deferred:
        _check(checks, scenario, copy_name, "discovery_kept", "fail",
               "仍有 deferred run 但 current/runs/active 全部不存在：重试会丢失发现面")
    else:
        _check(checks, scenario, copy_name, "discovery_kept", "info",
               "无 deferred run；发现键可能因成员删空而自动消失（非删除动作）")
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
               "aborted=true 但数据缺失 %d 个：%s" % (len(missing), _sample(missing)))
    elif unknown:
        _check(checks, scenario, copy_name, "aborted_nothing_deleted", "unknown",
               "aborted 后数据存在性不可判：%s" % _sample(unknown))
    else:
        _check(checks, scenario, copy_name, "aborted_nothing_deleted", "pass",
               "枚举/守卫/DB 中止发生在删除之前，数据保持完整")
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
                   "预算耗尽，请求未完成：%s" % row.get("transport_error"))
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
                   "只读接口不应删除数据，但缺失：%s" % _sample(missing))
        elif unknown:
            _check(checks, scenario, copy_name, "read_data_intact", "unknown",
                   "只读后数据存在性不可判：%s" % _sample(unknown))
        else:
            _check(checks, scenario, copy_name, "read_data_intact", "pass",
                   "只读接口后数据仍完整")

    for row in rows:
        business = row.get("business") or {}
        if not isinstance(business, dict):
            continue
        operation = str(row.get("operation"))
        if operation == "runs_current" and str(copy.get("copy")) == copy_name:
            has_suspended = any(item.get("status") == "SUSPENDED" for item in (copy.get("runs") or []))
            if has_suspended and business.get("found") is not True:
                _check(checks, scenario, copy_name, "runs_current_found", "fail",
                       "存在 SUSPENDED run 但 /runs/current found=%s" % business.get("found"))
            elif has_suspended:
                _check(checks, scenario, copy_name, "runs_current_found", "pass", "found=true")
        if operation == "run_detail" and business.get("exists") is not True:
            _check(checks, scenario, copy_name, "run_detail_exists", "fail",
                   "GET /runs/{id} exists=%s" % business.get("exists"))
        if operation == "history" and keys["transcript"]:
            total = business.get("total")
            if total is None or int(total) < 1:
                _check(checks, scenario, copy_name, "history_non_empty", "fail", "history total=%s" % total)
            else:
                _check(checks, scenario, copy_name, "history_non_empty", "pass", "history total=%s" % total)


def verify(cfg: Config, manifest: Dict[str, object], results: Dict[str, object],
           selected: List[str]) -> Dict[str, object]:
    started_at = M.utc_now_iso()
    if cfg.verify_settle_seconds > 0:
        time.sleep(cfg.verify_settle_seconds)
    checks: List[Dict[str, object]] = []
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
                    raise RuntimeError("无法读取 cluster 主节点拓扑（拒绝用部分节点继续 verify）")
                ops.primary_node_clients(strict=True, targets=authoritative)
        except Exception as exc:
            return {
                "started_at": started_at,
                "finished_at": M.utc_now_iso(),
                "checks": [{
                    "scenario": "*", "copy": "*", "check": "redis_connectivity",
                    "status": "unknown", "detail": "无法保证所有目标节点可用，拒绝继续 verify：%s" % exc,
                }],
                "counts": {"pass": 0, "fail": 0, "unknown": 1, "info": 0, "skip": 0},
                "error": str(exc),
            }
        measure_scenarios: Dict[str, Dict[str, object]] = {}
        for item in results.get("scenarios", []) or []:
            if isinstance(item, dict):
                measure_scenarios[str(item.get("scenario"))] = item

        for scenario in selected:
            entry = (manifest.get("scenarios") or {}).get(scenario)
            if not isinstance(entry, dict) or entry.get("status") != "generated":
                _check(checks, scenario, "-", "scenario_generated", "skip",
                       (entry or {}).get("reason", "未生成"))
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
                continue
            by_copy = _delete_rows(results, scenario)
            for copy_name in sorted(copies):
                copy = copies[copy_name]
                if copy_name == "warmup":
                    continue
                rows = by_copy.get(copy_name) or []
                if not rows:
                    _check(checks, scenario, copy_name, "delete_measured", "skip", "没有测量行")
                    continue
                merged = _merge(rows)
                _check_slots(cfg, checks, scenario, copy_name, copy)
                statuses = merged["statuses"]
                if merged["transport_errors"]:
                    _check(checks, scenario, copy_name, "delete_http", "unknown",
                           "传输失败：%s" % _sample(merged["transport_errors"], 3))
                    continue
                non_200 = [status for status in statuses if status != 200]
                if non_200:
                    _check(checks, scenario, copy_name, "delete_http",
                           "unknown" if all(item == 503 for item in non_200) else "fail",
                           "非 200 状态：%s" % non_200)
                    if all(item == 503 for item in non_200):
                        continue
                else:
                    _check(checks, scenario, copy_name, "delete_http", "pass", "全部 DELETE 200")
                if merged["aborted"]:
                    _check_aborted(cfg, ops, checks, scenario, copy_name, plan, copy, merged, background_counts)
                elif merged["complete_any"]:
                    _check_clean(cfg, ops, api, checks, scenario, copy_name, plan, copy, merged, background_counts)
                else:
                    _check_retained(cfg, ops, api, checks, scenario, copy_name, plan, copy, merged, background_counts)

                if scenario == "repeat_delete":
                    found_values = []
                    for row in rows:
                        business = row.get("business") or {}
                        if isinstance(business, dict) and "found" in business:
                            found_values.append(business.get("found"))
                    if len(found_values) >= 2:
                        if found_values[-1] is False:
                            _check(checks, scenario, copy_name, "second_delete_idempotent", "pass",
                                   "第二次 DELETE found=false")
                        else:
                            _check(checks, scenario, copy_name, "second_delete_idempotent", "fail",
                                   "第二次 DELETE found=%s（首次完成后应为 false）" % found_values[-1])
                if scenario == "concurrent_delete":
                    if not merged["complete_any"]:
                        _check(checks, scenario, copy_name, "concurrent_single_success", "fail",
                               "并发 DELETE 没有任何一次 complete=true")
                    else:
                        _check(checks, scenario, copy_name, "concurrent_single_success", "pass",
                               "并发 DELETE 至少一次 complete=true")
    finally:
        api.close()
        ops.close()

    counts = {"pass": 0, "fail": 0, "unknown": 0, "info": 0, "skip": 0}
    for item in checks:
        status = str(item.get("status"))
        counts[status] = counts.get(status, 0) + 1
    return {
        "started_at": started_at,
        "finished_at": M.utc_now_iso(),
        "checks": checks,
        "counts": counts,
    }
