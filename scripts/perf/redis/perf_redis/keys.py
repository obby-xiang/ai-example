"""Key builders mirroring the backend's production key layout.

All builders were derived from the real Java sources:
  ai/tenant/TenantKeyScope, ai/tenant/RunKeyScope,
  ai/run/RunStore, ai/run/RedisFrameStore,
  ai/memory/RedisChatMemoryRepository, ai/memory/SessionKeyCodec,
  ai/history/ConversationTranscriptStore,
  ai/session/{AiSessionPurgeService, SessionPurgeGuard, SessionPurgePendingStore, SessionGate},
  ai/form/RedisFormAttachmentStore, ai/run/{CreateTaskDraftStore, ClientRequestRegistry}.

Do not change a key string here without re-reading those files.
"""

from __future__ import annotations

from typing import Dict, Iterable, List, Optional, Tuple

RUN_PREFIX = "ai:run:"
RECOVERY_DUE_INDEX = "ai:recovery:due"
RECOVERY_DUE_IDENTITY_PREFIX = "ai:recovery:due:"
RUN_LIST_INDEX_PREFIX = "ai:runs:"
RUN_STATE_PREFIX = "ai:runstate:"
PENDING_PREFIX = "ai:pending:"
CLAIM_PREFIX = "ai:claim:"
LEDGER_PREFIX = "ai:ledger:"
IDEM_PREFIX = "ai:idem:"
SEQ_PREFIX = "ai:seq:"
XEVENTS_PREFIX = "ai:xevents:"
XACK_PREFIX = "ai:xack:"
XTRIM_PREFIX = "ai:xtrim:"
LEASE_RUN_PREFIX = "ai:lease:run:"
LEASE_EPOCH_PREFIX = "ai:lease:epoch:"
FENCE_RUN_PREFIX = "ai:fence:run:"
REQ_PREFIX = "ai:req:"
DECIDED_PREFIX = "ai:decided:"
BEAT_PREFIX = "ai:beat:"
ACTIVITY_PREFIX = "ai:activity:"
CANCEL_PREFIX = "ai:cancel:"
OBSERVED_PREFIX = "ai:observed:"

SESSION_PREFIX = "ai:session:"
SESSION_LOCK_PREFIX = "ai:session:lock:"
SESSION_CURRENT_PREFIX = "ai:session:current:"
SESSION_RUNS_PREFIX = "ai:session:runs:"
SESSION_ACTIVE_PREFIX = "ai:session:active:"

CONTEXT_PREFIX = "ai:ctx:"
CONTEXT_LEDGER_PREFIX = "ai:ctx:ledger:"
CONTEXT_ARCHIVE_PREFIX = "ai:ctx:archive:"
CONTEXT_EVENTS_PREFIX = "ai:ctx:events:"
SAFETY_PREFIX = "ai:safety:"

CTDRAFT_PREFIX = "ai:ctdraft:"
CREQ_PREFIX = "ai:creq:"

MEMORY_PREFIX = "chat:mem:"
MEMORY_VERSION_SUFFIX = ":ver"
MEMORY_GENERATION_SUFFIX = ":gen"
MEMORY_STAGING_PREFIX = "chat:mem:staging:"

TRANSCRIPT_PREFIX = "ai:tx:"
TRANSCRIPT_ORDER_SUFFIX = ":order"
TRANSCRIPT_META_SUFFIX = ":meta"
TRANSCRIPT_TRUNCATED_SUFFIX = ":truncated"

ATTACHMENT_PREFIX = "ai:form:att:"
ATTACHMENT_USED_PREFIX = "ai:form:att:used:"
ATTACHMENT_SESSION_INDEX_PREFIX = "ai:form:att:sess:"
ATTACHMENT_FORM_INDEX_PREFIX = "ai:form:att:form:"

PURGE_GUARD_PREFIX = "ai:purge:guard:"
PURGE_PENDING_PREFIX = "ai:purge:pending:"
PURGE_DUE_KEY = "ai:purge:retry:due"

RUN_KEYS_13 = (
    "run",
    "runstate",
    "pending",
    "claim",
    "ledger",
    "idem",
    "seq",
    "xevents",
    "lease",
    "fence",
    "req",
    "decided",
    "lease_epoch",
)

RUN_KEY_PREFIX_BY_NAME = {
    "run": RUN_PREFIX,
    "runstate": RUN_STATE_PREFIX,
    "pending": PENDING_PREFIX,
    "claim": CLAIM_PREFIX,
    "ledger": LEDGER_PREFIX,
    "idem": IDEM_PREFIX,
    "seq": SEQ_PREFIX,
    "xevents": XEVENTS_PREFIX,
    "lease": LEASE_RUN_PREFIX,
    "fence": FENCE_RUN_PREFIX,
    "req": REQ_PREFIX,
    "decided": DECIDED_PREFIX,
    "lease_epoch": LEASE_EPOCH_PREFIX,
}


def segment(tenant_id: int, user_id: int) -> str:
    return "%d:%d:" % (int(tenant_id), int(user_id))


def scoped(prefix: str, suffix: str, tenant_id: int, user_id: int) -> str:
    return prefix + segment(tenant_id, user_id) + suffix


def escaped_session_id(session_id: str) -> str:
    return session_id.replace("%", "%25").replace("{", "%7B").replace("}", "%7D")


def session_hash_tag(session_id: str) -> str:
    return "{" + escaped_session_id(session_id) + "}"


def run_tag(run_id: str, tenant_id: int, user_id: int) -> str:
    return "{" + segment(tenant_id, user_id) + "run:" + run_id + "}"


def run_scoped(prefix: str, run_id: str, tenant_id: int, user_id: int) -> str:
    return prefix + run_tag(run_id, tenant_id, user_id)


def run_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(RUN_PREFIX, run_id, t, u)


def run_state_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(RUN_STATE_PREFIX, run_id, t, u)


def pending_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(PENDING_PREFIX, run_id, t, u)


def claim_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(CLAIM_PREFIX, run_id, t, u)


def ledger_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(LEDGER_PREFIX, run_id, t, u)


def idem_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(IDEM_PREFIX, run_id, t, u)


def seq_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(SEQ_PREFIX, run_id, t, u)


def xevents_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(XEVENTS_PREFIX, run_id, t, u)


def lease_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(LEASE_RUN_PREFIX, run_id, t, u)


def fence_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(FENCE_RUN_PREFIX, run_id, t, u)


def req_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(REQ_PREFIX, run_id, t, u)


def decided_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(DECIDED_PREFIX, run_id, t, u)


def lease_epoch_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(LEASE_EPOCH_PREFIX, run_id, t, u)


def beat_key(run_id: str, t: int, u: int) -> str:
    return scoped(BEAT_PREFIX, run_id, t, u)


def activity_key(run_id: str, t: int, u: int) -> str:
    return scoped(ACTIVITY_PREFIX, run_id, t, u)


def cancel_key(run_id: str, t: int, u: int) -> str:
    return run_scoped(CANCEL_PREFIX, run_id, t, u)


def observed_key(run_id: str, t: int, u: int) -> str:
    return scoped(OBSERVED_PREFIX, run_id, t, u)


def run_keys_13(run_id: str, t: int, u: int) -> Dict[str, str]:
    build = {
        "run": run_key,
        "runstate": run_state_key,
        "pending": pending_key,
        "claim": claim_key,
        "ledger": ledger_key,
        "idem": idem_key,
        "seq": seq_key,
        "xevents": xevents_key,
        "lease": lease_key,
        "fence": fence_key,
        "req": req_key,
        "decided": decided_key,
        "lease_epoch": lease_epoch_key,
    }
    return {name: build[name](run_id, t, u) for name in RUN_KEYS_13}


def run_anchor_keys(run_id: str, t: int, u: int) -> Dict[str, str]:
    return {
        "beat": beat_key(run_id, t, u),
        "activity": activity_key(run_id, t, u),
        "cancel": cancel_key(run_id, t, u),
        "observed": observed_key(run_id, t, u),
    }


def run_index_member(run_id: str, t: int, u: int) -> str:
    return "%d/%d/%s" % (int(t), int(u), run_id)


def identity_recovery_due_key(t: int, u: int) -> str:
    return RECOVERY_DUE_IDENTITY_PREFIX + "%d:%d" % (int(t), int(u))


def run_list_index_key(t: int, u: int) -> str:
    return RUN_LIST_INDEX_PREFIX + "%d:%d" % (int(t), int(u))


def run_index_refs(run_id: str, t: int, u: int) -> List[Dict[str, str]]:
    return [
        {"key": RECOVERY_DUE_INDEX, "member": run_index_member(run_id, t, u)},
        {"key": identity_recovery_due_key(t, u), "member": run_id},
        {"key": run_list_index_key(t, u), "member": run_id},
    ]


def session_record_key(session_id: str, t: int, u: int) -> str:
    return scoped(SESSION_PREFIX, session_id, t, u)


def session_lock_key(session_id: str, t: int, u: int) -> str:
    return scoped(SESSION_LOCK_PREFIX, session_id, t, u)


def session_current_key(session_id: str, t: int, u: int) -> str:
    return scoped(SESSION_CURRENT_PREFIX, session_id, t, u)


def session_runs_key(session_id: str, t: int, u: int) -> str:
    return scoped(SESSION_RUNS_PREFIX, session_id, t, u)


def session_active_key(session_id: str, t: int, u: int) -> str:
    return scoped(SESSION_ACTIVE_PREFIX, session_id, t, u)


def ctdraft_key(session_id: str, t: int, u: int) -> str:
    return CTDRAFT_PREFIX + "{" + segment(t, u) + "session:" + escaped_session_id(session_id) + "}"


def creq_key(session_id: str, t: int, u: int) -> str:
    return scoped(CREQ_PREFIX, session_id, t, u)


def context_key(session_id: str, t: int, u: int) -> str:
    return scoped(CONTEXT_PREFIX, session_id, t, u)


def context_ledger_key(session_id: str, t: int, u: int) -> str:
    return scoped(CONTEXT_LEDGER_PREFIX, session_id, t, u)


def context_archive_prefix(session_id: str, t: int, u: int) -> str:
    return scoped(CONTEXT_ARCHIVE_PREFIX, session_id, t, u)


def context_archive_key(session_id: str, turn_no: int, t: int, u: int) -> str:
    return context_archive_prefix(session_id, t, u) + ":" + str(int(turn_no))


def context_events_key(session_id: str, t: int, u: int) -> str:
    return scoped(CONTEXT_EVENTS_PREFIX, session_id, t, u)


def safety_key(session_id: str, t: int, u: int) -> str:
    return scoped(SAFETY_PREFIX, session_id, t, u)


def memory_data_key(session_id: str, t: int, u: int) -> str:
    return MEMORY_PREFIX + segment(t, u) + session_hash_tag(session_id)


def memory_version_key(session_id: str, t: int, u: int) -> str:
    return memory_data_key(session_id, t, u) + MEMORY_VERSION_SUFFIX


def memory_generation_key(session_id: str, t: int, u: int) -> str:
    return memory_data_key(session_id, t, u) + MEMORY_GENERATION_SUFFIX


def memory_staging_prefix(session_id: str, t: int, u: int) -> str:
    return MEMORY_STAGING_PREFIX + segment(t, u) + session_hash_tag(session_id) + ":"


def memory_staging_index_key(session_id: str, t: int, u: int) -> str:
    return MEMORY_STAGING_PREFIX + "idx:" + segment(t, u) + session_hash_tag(session_id)


def memory_staging_key(session_id: str, token: str, t: int, u: int) -> str:
    return memory_staging_prefix(session_id, t, u) + token


def transcript_data_key(session_id: str, t: int, u: int) -> str:
    return TRANSCRIPT_PREFIX + segment(t, u) + session_hash_tag(session_id)


def transcript_order_key(session_id: str, t: int, u: int) -> str:
    return transcript_data_key(session_id, t, u) + TRANSCRIPT_ORDER_SUFFIX


def transcript_meta_key(session_id: str, t: int, u: int) -> str:
    return transcript_data_key(session_id, t, u) + TRANSCRIPT_META_SUFFIX


def transcript_truncated_key(session_id: str, t: int, u: int) -> str:
    return transcript_data_key(session_id, t, u) + TRANSCRIPT_TRUNCATED_SUFFIX


def attachment_registration_key(file_id: str, t: int, u: int) -> str:
    return ATTACHMENT_PREFIX + segment(t, u) + file_id


def attachment_consumed_key(file_id: str, t: int, u: int) -> str:
    return ATTACHMENT_USED_PREFIX + segment(t, u) + file_id


def attachment_session_index_key(session_id: str, t: int, u: int) -> str:
    return ATTACHMENT_SESSION_INDEX_PREFIX + segment(t, u) + session_id


def attachment_form_index_key(run_id: str, tool_call_id: str, t: int, u: int) -> str:
    return ATTACHMENT_FORM_INDEX_PREFIX + segment(t, u) + run_id + ":" + tool_call_id


def purge_guard_key(session_id: str, t: int, u: int) -> str:
    return scoped(PURGE_GUARD_PREFIX, session_id, t, u)


def purge_pending_key(session_id: str, t: int, u: int) -> str:
    return scoped(PURGE_PENDING_PREFIX, session_id, t, u)


def escape_glob(value: str) -> str:
    out: List[str] = []
    for ch in value:
        if ch in ("*", "?", "[", "]", "\\"):
            out.append("\\")
        out.append(ch)
    return "".join(out)


def archive_scan_pattern(session_id: str, t: int, u: int) -> str:
    return escape_glob(context_archive_prefix(session_id, t, u)) + ":*"


def staging_scan_pattern(session_id: str, t: int, u: int) -> str:
    return escape_glob(memory_staging_prefix(session_id, t, u)) + "*"


# ---------------------------------------------------------------------------
# Cluster slot helpers (CRC16/XMODEM, same algorithm as redis-cli CLUSTER KEYSLOT)
# ---------------------------------------------------------------------------

def _build_crc16_table() -> List[int]:
    table: List[int] = []
    for index in range(256):
        crc = index << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
        table.append(crc)
    return table


_CRC16_TABLE = _build_crc16_table()


def crc16(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFF) ^ _CRC16_TABLE[((crc >> 8) ^ byte) & 0xFF]
    return crc


def hash_tag_content(key: str) -> str:
    start = key.find("{")
    if start < 0:
        return key
    end = key.find("}", start + 1)
    if end <= start + 1:
        return key
    return key[start + 1:end]


def slot_of(key: str) -> int:
    return crc16(hash_tag_content(key).encode("utf-8")) % 16384


def slot_check(keys: Iterable[str]) -> Tuple[bool, Dict[str, int]]:
    slots: Dict[str, int] = {}
    for key in keys:
        slots[key] = slot_of(key)
    return (len(set(slots.values())) <= 1), slots


def dedupe(values: Iterable[str]) -> List[str]:
    seen = set()
    out: List[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        out.append(value)
    return out


def background_key(namespace: str, bucket: str, index: int) -> str:
    return "perf:bg:%s:%s:%d" % (namespace, bucket, int(index))


def background_pattern(namespace: str, bucket: str) -> str:
    return "perf:bg:%s:%s:*" % (namespace, bucket)


def background_prefix(namespace: str) -> str:
    return "perf:bg:%s:" % namespace


def background_probe_indexes(count: int) -> List[int]:
    if count <= 0:
        return []
    indexes = {0, count // 2, count - 1}
    if count > 2:
        indexes.add(1)
    return sorted(index for index in indexes if 0 <= index < count)


def background_probe_keys(namespace: str, bucket: str, count: int) -> List[str]:
    return [background_key(namespace, bucket, index) for index in background_probe_indexes(count)]


def xack_pattern(run_id: str, t: int, u: int) -> str:
    return "ai:xack:*:" + run_tag(run_id, t, u)


def run_tag_scan_pattern(run_id: str, t: int, u: int) -> str:
    return "ai:*" + escape_glob(run_tag(run_id, t, u)) + "*"


def session_prefixes_for_cleanup(session_id: str, t: int, u: int) -> List[str]:
    escaped = escape_glob(session_hash_tag(session_id))
    return [
        "chat:mem:" + segment(t, u) + escaped + "*",
        "ai:tx:" + segment(t, u) + escaped + "*",
    ]


def is_pure_digits(value: str) -> bool:
    if not value:
        return False
    for ch in value:
        if ch < "0" or ch > "9":
            return False
    return True


def archive_key_for_suffix(key: str, session_id: str, t: int, u: int) -> bool:
    prefix = context_archive_prefix(session_id, t, u) + ":"
    if not key.startswith(prefix):
        return False
    return is_pure_digits(key[len(prefix):])


def staging_key_for_prefix(key: str, session_id: str, t: int, u: int) -> bool:
    return key.startswith(memory_staging_prefix(session_id, t, u))
