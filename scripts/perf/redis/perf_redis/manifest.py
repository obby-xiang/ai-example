"""Workspace, manifest and atomic JSON/CSV output.

The manifest is the tool's only durable description of generated data. It stores
recipes and ids, never every key, so a 1,000,000-key background pool does not
produce a giant file. Key strings are always re-derived from ids + profiles;
each run also records the names (not the strings) of the keys it actually wrote.
"""

from __future__ import annotations

import json
import os
import secrets
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

from .envcfg import Config, NAMESPACE_PATTERN


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")


def ensure_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def atomic_write_text(path: Path, text: str) -> None:
    ensure_dir(path.parent)
    handle, temp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="") as stream:
            stream.write(text)
        os.replace(temp_name, str(path))
    finally:
        if os.path.exists(temp_name):
            try:
                os.remove(temp_name)
            except OSError:
                pass


def atomic_write_json(path: Path, data: object) -> None:
    atomic_write_text(path, json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False) + "\n")


def read_json(path: Path) -> Optional[object]:
    if not path.exists():
        return None
    with open(path, "r", encoding="utf-8") as stream:
        return json.load(stream)


def workspace(cfg: Config) -> Dict[str, Path]:
    root = Path(cfg.output_dir).expanduser()
    ensure_dir(root)
    return {
        "root": root,
        "manifest": root / "manifest.json",
        "results": root / "results.json",
        "requests_csv": root / "requests.csv",
        "summary_csv": root / "summary.csv",
        "verify_csv": root / "verify.csv",
        "verify_json": root / "verify.json",
        "verify_precleanup_csv": root / "verify-precleanup.csv",
        "verify_precleanup_json": root / "verify-precleanup.json",
        "verify_postcleanup_csv": root / "verify-postcleanup.csv",
        "verify_postcleanup_json": root / "verify-postcleanup.json",
        "cleanup_json": root / "cleanup.json",
        "cleanup_csv": root / "cleanup.csv",
        "doctor": root / "doctor.json",
        "preflight": root / "preflight.json",
        "namespace": root / ".namespace",
    }


def resolve_namespace(cfg: Config) -> str:
    if cfg.namespace:
        return cfg.namespace
    paths = workspace(cfg)
    state = paths["namespace"]
    if state.exists():
        value = state.read_text(encoding="utf-8").strip()
        if NAMESPACE_PATTERN.match(value):
            cfg.namespace = value
            return value
    value = "perf%s%s" % (utc_stamp(), secrets.token_hex(2))
    atomic_write_text(state, value + "\n")
    cfg.namespace = value
    return value


def session_id_for(cfg: Config, scenario: str, copy_name: str) -> str:
    return "p-%s-%s-%s" % (cfg.namespace, scenario, copy_name)


def run_id_for(cfg: Config, scenario: str, copy_name: str, index: int) -> str:
    return "%s-r%d" % (session_id_for(cfg, scenario, copy_name), index)


def tool_call_id(run_id: str, index: int) -> str:
    return "%s-tc%d" % (run_id, index)


def feedback_message_id(session_id: str, index: int) -> str:
    return "perf-msg-%s-%d" % (session_id, index)


def staging_token(index: int) -> str:
    return "perfstage-%05d" % index


def new_manifest(cfg: Config) -> Dict[str, object]:
    return {
        "tool": "ai-redis-perf",
        "version": 1,
        "created_at": utc_now_iso(),
        "namespace": cfg.namespace,
        "redis_mode": cfg.mode,
        "identity": {"tenantId": cfg.tenant_id, "userId": cfg.user_id},
        "profile": cfg.profile,
        "sizes": dict(cfg.sizes),
        "config_digest": cfg.digest(),
        "background": {},
        "scenarios": {},
    }


def load_manifest(cfg: Config) -> Optional[Dict[str, object]]:
    data = read_json(workspace(cfg)["manifest"])
    if data is None:
        return None
    if not isinstance(data, dict):
        raise ValueError("manifest.json must contain an object")
    if data.get("namespace") != cfg.namespace:
        raise ValueError(
            "manifest namespace mismatch: file=%r current=%r (change PERF_NAMESPACE or PERF_OUTPUT_DIR)"
            % (data.get("namespace"), cfg.namespace)
        )
    return data


def save_manifest(cfg: Config, manifest: Dict[str, object]) -> None:
    manifest["updated_at"] = utc_now_iso()
    atomic_write_json(workspace(cfg)["manifest"], manifest)


def copy_names(cfg: Config, destructive: bool) -> List[str]:
    if not destructive:
        return ["r1"]
    names = ["warmup"]
    for index in range(1, cfg.repeat + 1):
        names.append("r%d" % index)
    return names


def measured_copy_names(cfg: Config) -> List[str]:
    return ["r%d" % index for index in range(1, cfg.repeat + 1)]
