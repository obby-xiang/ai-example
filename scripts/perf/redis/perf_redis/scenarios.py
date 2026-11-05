"""Scenario matrix.

A scenario is a data recipe plus the HTTP operations to measure. Every scenario
is executed unchanged against standalone and cluster Redis; only the connection
env differs.

Profiles:
  smoke  - small, fast, safe defaults (no large data, no bloat)
  full   - smoke + every axis with medium/large sizes; large entries are skipped
           unless PERF_ALLOW_LARGE_SCALE=true
  stress - full + 1,000,000 background keys (needs PERF_ALLOW_BACKGROUND_MILLION=true)
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple

from .envcfg import Config

READ_OPS = (
    "history",
    "history_before",
    "context",
    "runs_current",
    "runs_list",
    "run_detail",
    "events_probe",
    "cancel_status",
    "pending",
    "startup_resume",
)

MAX_DERIVED_ARCHIVE_TURNS = 2000


def _run(status: str, frames: int = 0, terminal: str = "none", gap: bool = False,
         pending: int = 0, gate: bool = False, lease_held: bool = False,
         pending_fields: int = 0, terminal_type: str = "done") -> Dict[str, object]:
    return {
        "status": status,
        "frames": {"count": int(frames), "terminal": terminal, "gap": bool(gap),
                   "terminalType": terminal_type},
        "pending": int(pending),
        "pending_fields": int(pending_fields),
        "gate": bool(gate),
        "lease_held": bool(lease_held),
    }


def _runs(count: int, status: str = "DONE") -> List[Dict[str, object]]:
    return [_run(status, pending=1) for _ in range(int(count))]


def _plan(session_record: bool = True, memory: int = 0, staging: int = 0,
          transcript: int = 0, runs: Optional[List[Dict[str, object]]] = None,
          archives: int = 0, ctx_turn_no: Optional[int] = None,
          attachments: int = 0, feedback: int = 0, background: Optional[str] = None,
          ops: Optional[List[str]] = None, verify_mode: str = "delete",
          gate_holder_index: Optional[int] = None, notes: str = "") -> Dict[str, object]:
    return {
        "session_record": bool(session_record),
        "memory_messages": int(memory),
        "staging_count": int(staging),
        "transcript_entries": int(transcript),
        "runs": list(runs or []),
        "archive_count": int(archives),
        "ctx_turn_no": ctx_turn_no,
        "attachments": int(attachments),
        "feedback": int(feedback),
        "background": background,
        "ops": list(ops or ["delete"]),
        "verify_mode": verify_mode,
        "gate_holder_index": gate_holder_index,
        "notes": notes,
    }


def _scenario(description: str, profiles: Tuple[str, ...], plan: Dict[str, object],
              destructive: bool, needs_http: bool = False, scale: str = "small") -> Dict[str, object]:
    return {
        "description": description,
        "profiles": list(profiles),
        "plan": plan,
        "destructive": bool(destructive),
        "needs_http": bool(needs_http),
        "scale": scale,
    }


def build_matrix(cfg: Config) -> Dict[str, Dict[str, object]]:
    s = cfg.sizes
    matrix: Dict[str, Dict[str, object]] = {}

    matrix["empty_session"] = _scenario(
        "不存在的会话：DELETE 幂等 200；无任何键可删（仍会留下 guard/pending/记忆 tombstone）",
        ("smoke",), _plan(session_record=False, notes="found=false"), True)

    matrix["minimal_session"] = _scenario(
        "最小会话：只有 ai:session 记录",
        ("smoke",), _plan(), True)

    matrix["runs_0"] = _scenario(
        "会话存在但 run 数 = 0",
        ("smoke",), _plan(ctx_turn_no=0), True)

    matrix["runs_1_terminal"] = _scenario(
        "1 个终态 run（13 键 + 快照/pending/decision/索引齐全）",
        ("smoke",), _plan(runs=_runs(1)), True)

    matrix["runs_10_terminal"] = _scenario(
        "10 个终态 run",
        ("smoke", "full"), _plan(runs=_runs(int(s["runs_med"]))), True)

    matrix["runs_50_terminal"] = _scenario(
        "50 个终态 run",
        ("full",), _plan(runs=_runs(int(s["runs_large"]))), True)

    matrix["runs_mixed"] = _scenario(
        "终态/挂起/运行中混合：活跃 run 不得删 13 键（可能 deferred）",
        ("full",), _plan(runs=[
            _run("DONE"),
            _run("CANCELLED"),
            _run("SUSPENDED", pending=1, frames=16),
            _run("RUNNING", frames=16),
        ], notes="活跃 run 的终态取决于取消收尾是否当拍完成"), True)

    matrix["runs_gate_busy"] = _scenario(
        "会话门被另一轮持有：取消收尾可能 SESSION_BUSY（结果允许 deferred 或完成，不得半删）",
        ("full",), _plan(runs=[
            _run("SUSPENDED", pending=1),
            _run("SUSPENDED", pending=1),
        ], gate_holder_index=1), True)

    matrix["runs_with_frames"] = _scenario(
        "3 个终态 run，各带 128 帧归档",
        ("full",), _plan(runs=[_run("DONE", frames=int(s["frames_small"]), terminal="tail") for _ in range(3)]), True)

    matrix["staging_small"] = _scenario(
        "记忆在飞 staging 小规模（含 final 数据键）",
        ("smoke",), _plan(memory=int(s["memory_small"]), staging=int(s["staging_small"]), runs=_runs(1)), True)

    matrix["staging_large"] = _scenario(
        "记忆 staging 大规模",
        ("full",), _plan(memory=int(s["memory_small"]), staging=int(s["staging_large"]), runs=_runs(1)), True,
        scale="large")

    matrix["archive_turnno"] = _scenario(
        "unsupported_legacy：已停止写入的旧 archive 结构，不生成、不计当前验收",
        (), _plan(runs=_runs(1), archives=int(s["archive_small"]) + 3,
                           ctx_turn_no=int(s["archive_small"]) - 1), True, scale="unsupported_legacy")

    matrix["archive_scan_only"] = _scenario(
        "unsupported_legacy：已停止写入的旧 archive 结构，不生成、不计当前验收",
        (), _plan(runs=_runs(1), archives=int(s["archive_small"])), True, scale="unsupported_legacy")

    matrix["archive_large"] = _scenario(
        "unsupported_legacy：已停止写入的旧 archive 结构，不生成、不计当前验收",
        (), _plan(runs=_runs(1), archives=int(s["archive_large"]),
                           ctx_turn_no=int(s["archive_large"]) - 1), True, scale="unsupported_legacy")

    matrix["archive_over_turnno"] = _scenario(
        "unsupported_legacy：已停止写入的旧 archive 结构，不生成、不计当前验收",
        (), _plan(runs=_runs(1), archives=int(s["archive_large"]),
                           ctx_turn_no=MAX_DERIVED_ARCHIVE_TURNS + 500), True, scale="unsupported_legacy")

    matrix["transcript_small"] = _scenario(
        "用户展示历史（transcript）小规模",
        ("full",), _plan(memory=int(s["memory_small"]), transcript=int(s["transcript_small"]), runs=_runs(1)), True)

    matrix["transcript_large"] = _scenario(
        "用户展示历史大规模",
        ("full",), _plan(memory=int(s["memory_small"]), transcript=int(s["transcript_large"]), runs=_runs(1)), True,
        scale="large")

    matrix["memory_large"] = _scenario(
        "记忆窗口大规模（chat:mem）+ transcript",
        ("full",), _plan(memory=int(s["memory_large"]), transcript=int(s["transcript_small"]), runs=_runs(1)), True,
        scale="large")

    matrix["frames_terminal_tail"] = _scenario(
        "活跃 run，终帧在 Stream 尾部（探测应命中，取消收尾 ALREADY_TERMINAL）",
        ("smoke",), _plan(runs=[_run("RUNNING", frames=int(s["frames_small"]), terminal="tail")]), True)

    matrix["frames_terminal_head"] = _scenario(
        "活跃 run，终帧在 Stream 首（小窗口内应命中）",
        ("full",), _plan(runs=[_run("RUNNING", frames=int(s["frames_small"]), terminal="head")]), True)

    matrix["frames_terminal_middle"] = _scenario(
        "活跃 run，终帧在 Stream 中部",
        ("full",), _plan(runs=[_run("RUNNING", frames=int(s["frames_small"]), terminal="middle")]), True)

    matrix["frames_terminal_missing"] = _scenario(
        "活跃 run，无终帧：取消收尾需要补写 CANCELLED/终帧",
        ("full",), _plan(runs=[_run("RUNNING", frames=int(s["frames_small"]), terminal="none")]), True)

    matrix["frames_terminal_error"] = _scenario(
        "活跃 run，error 终帧在中部（done/error 同判）",
        ("full",), _plan(runs=[_run("RUNNING", frames=int(s["frames_small"]), terminal="middle",
                                    terminal_type="error")]), True)

    matrix["frames_gap_large"] = _scenario(
        "大 Stream + seq 大跳号 + 终帧在首：源码的 6256 帧预算按 seq 差计算，预期探测漏帧",
        ("full",), _plan(runs=[_run("RUNNING", frames=int(s["frames_large"]), terminal="head", gap=True)]), True,
        scale="large")

    matrix["suspended_pending"] = _scenario(
        "挂起 run + 待决条目：活跃 run 可能 deferred；pending/guard/发现键保留",
        ("smoke",), _plan(runs=[_run("SUSPENDED", pending=2, frames=32)],
                           memory=int(s["memory_small"]), transcript=int(s["transcript_small"]),
                           ctx_turn_no=2), True)

    matrix["attachments_1"] = _scenario(
        "1 个真实上传附件（挂起表单 + 真实上传 API），验证文件/登记/索引清理",
        ("full",), _plan(runs=[_run("SUSPENDED", pending=1, frames=8, pending_fields=1)],
                          attachments=1, transcript=8), True, needs_http=True)

    matrix["attachments_3"] = _scenario(
        "3 个真实上传附件（同一表单 3 个 attachment 字段）",
        ("full",), _plan(runs=[_run("SUSPENDED", pending=1, frames=8, pending_fields=3)],
                          attachments=int(s["attachment_max"]), transcript=8), True, needs_http=True)

    matrix["feedback_rows"] = _scenario(
        "H2 ai_feedback 行（经真实 POST /api/ai/feedback），验证 DB 删除",
        ("full",), _plan(runs=_runs(1), feedback=int(s["feedback_rows"])), True, needs_http=True)

    matrix["repeat_delete"] = _scenario(
        "同一会话连续两次 DELETE（第二次应 found=false 且仍 complete=true）",
        ("smoke",), _plan(runs=_runs(2), ops=["delete", "delete"], notes="同一 copy 上串行两次"), True)

    matrix["concurrent_delete"] = _scenario(
        "同一会话并发 DELETE（并发数 = PERF_CONCURRENCY）",
        ("smoke",), _plan(runs=[_run("DONE"), _run("SUSPENDED", pending=1)], ops=["delete"],
                           notes="同一 copy 上并发；源码没有记录级单飞"), True)

    matrix["reads_suspended"] = _scenario(
        "真实 Redis 只读接口：history latest/before / context / runs/current / runs/{id} / events probe / cancel / pending",
        ("smoke",), _plan(runs=[_run("SUSPENDED", pending=2, frames=int(s["frames_small"]), terminal="none")],
                           memory=int(s["memory_small"]), transcript=int(s["transcript_small"]),
                           ctx_turn_no=2, ops=list(READ_OPS), verify_mode="reads"), False)

    matrix["health_read"] = _scenario(
        "GET /api/ai/health（无身份头）",
        ("smoke",), _plan(session_record=False, ops=["health"], verify_mode="reads"), False)

    matrix["kb_status_read"] = _scenario(
        "GET /api/ai/kb/status（KB 状态只读；KB 缓存不属会话销毁面）",
        ("smoke",), _plan(session_record=False, ops=["kb_status"], verify_mode="reads"), False)

    matrix["kb_reload"] = _scenario(
        "POST /api/ai/kb/reload（本地 Lucene 重建，不涉及模型/会话 Redis；需 PERF_ENABLE_KB_RELOAD=true）",
        ("full",), _plan(session_record=False, ops=["kb_reload"], verify_mode="reads"), False)

    matrix["bg_10k"] = _scenario(
        "背景键空间 1 万：证明两趟全库 SCAN 的固定成本",
        ("full",), _plan(runs=_runs(1), background="bg_10k"), True)

    matrix["bg_100k"] = _scenario(
        "背景键空间 10 万",
        ("full",), _plan(runs=_runs(1), background="bg_100k"), True, scale="large")

    matrix["bg_1m"] = _scenario(
        "背景键空间 100 万（显式允许才可生成/清理）",
        ("stress",), _plan(runs=_runs(1), background="bg_1m"), True, scale="million")

    return matrix


def resolve_selection(cfg: Config) -> Tuple[List[str], List[Dict[str, str]]]:
    """Return (selected scenario names, blocked entries).

    Explicit PERF_SCENARIOS entries that are blocked raise ConfigError: a user who
    names a scenario must not silently get a subset.
    """
    matrix = build_matrix(cfg)
    if cfg.scenarios:
        requested = cfg.scenarios
        unknown = [name for name in requested if name not in matrix]
        if unknown:
            raise ValueError(
                "未知场景：%s；可用：%s" % (",".join(unknown), ",".join(sorted(matrix)))
            )
    else:
        requested = [name for name, item in matrix.items() if cfg.profile in item["profiles"]]

    selected: List[str] = []
    blocked: List[Dict[str, str]] = []
    explicit = bool(cfg.scenarios)
    for name in requested:
        item = matrix[name]
        scale = item["scale"]
        reason = ""
        if scale == "unsupported_legacy":
            reason = "legacy archive 已停止写入；当前功能未上线，不做旧数据/旧协议场景"
        if scale == "large" and not cfg.allow_large_scale:
            reason = "大规模场景需显式允许：PERF_ALLOW_LARGE_SCALE=true"
        if scale == "million" and not cfg.allow_background_million:
            reason = "百万背景键需显式允许：PERF_ALLOW_BACKGROUND_MILLION=true（同时需要 PERF_ALLOW_LARGE_SCALE=true）"
        if scale == "million" and cfg.allow_background_million and not cfg.allow_large_scale:
            reason = "百万背景键同时需要 PERF_ALLOW_LARGE_SCALE=true"
        if name == "kb_reload" and not cfg.enable_kb_reload:
            reason = "KB reload 默认关闭：PERF_ENABLE_KB_RELOAD=true"
        if name.startswith("attachments") and not cfg.enable_attachments:
            reason = "附件场景已关闭：PERF_ENABLE_ATTACHMENTS=false"
        if name == "kb_status_read" and not cfg.enable_kb:
            reason = "KB 读取已关闭：PERF_ENABLE_KB=false"
        if reason:
            if explicit:
                raise ValueError("场景 %s 被阻止：%s" % (name, reason))
            blocked.append({"scenario": name, "reason": reason})
            continue
        selected.append(name)
    return selected, blocked


def plan_for(cfg: Config, name: str) -> Dict[str, object]:
    matrix = build_matrix(cfg)
    if name not in matrix:
        raise ValueError("未知场景：%s" % name)
    return matrix[name]["plan"]
