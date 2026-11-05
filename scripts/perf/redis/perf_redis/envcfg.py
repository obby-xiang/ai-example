"""Environment-only configuration for the Redis performance verification tool.

Every value is read from the process environment. No secret and no machine
specific path is hard-coded. Validation collects all problems so that doctor can
print one complete list instead of failing on the first key.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import socket
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

NAMESPACE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{1,40}$")
PROFILES = ("smoke", "full", "stress")
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "0.0.0.0"}

TRUE_VALUES = {"1", "true", "yes", "y", "on"}
FALSE_VALUES = {"0", "false", "no", "n", "off", ""}


class ConfigError(Exception):
    def __init__(self, errors: List[str]):
        self.errors = list(errors)
        super().__init__("; ".join(self.errors))


def _get(env: Dict[str, str], name: str, default: str = "") -> str:
    value = env.get(name)
    if value is None:
        return default
    return value.strip()


def _int(env: Dict[str, str], name: str, default: int, errors: List[str], minimum: int = 0) -> int:
    raw = _get(env, name, str(default))
    try:
        value = int(raw)
    except (TypeError, ValueError):
        errors.append("%s 必须是整数（当前=%r）" % (name, raw))
        return default
    if value < minimum:
        errors.append("%s 必须 >= %d（当前=%d）" % (name, minimum, value))
        return default
    return value


def _float(env: Dict[str, str], name: str, default: float, errors: List[str], minimum: float = 0.0) -> float:
    raw = _get(env, name, str(default))
    try:
        value = float(raw)
    except (TypeError, ValueError):
        errors.append("%s 必须是数字（当前=%r）" % (name, raw))
        return default
    if value < minimum:
        errors.append("%s 必须 >= %s（当前=%s）" % (name, minimum, value))
        return default
    return value


def _bool(env: Dict[str, str], name: str, default: bool, errors: List[str]) -> bool:
    raw = _get(env, name, "")
    if raw == "":
        return default
    lowered = raw.lower()
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    errors.append("%s 必须是 true/false（当前=%r）" % (name, raw))
    return default


def _csv(env: Dict[str, str], name: str, default: str = "") -> List[str]:
    raw = _get(env, name, default)
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def parse_cluster_nodes(raw: str, errors: List[str]) -> List[Tuple[str, int]]:
    nodes: List[Tuple[str, int]] = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        if ":" not in item:
            errors.append("REDIS_CLUSTER_NODES 项缺少端口：%r（应为 host:port）" % item)
            continue
        host, port_text = item.rsplit(":", 1)
        try:
            port = int(port_text)
        except ValueError:
            errors.append("REDIS_CLUSTER_NODES 端口非法：%r" % item)
            continue
        if not host or port <= 0 or port > 65535:
            errors.append("REDIS_CLUSTER_NODES 项非法：%r" % item)
            continue
        if host.startswith("[") and host.endswith("]"):
            host = host[1:-1]
        nodes.append((host, port))
    if not nodes:
        errors.append("cluster 模式必须设置 REDIS_CLUSTER_NODES=host1:port1,host2:port2,...")
    return nodes


@dataclass
class Config:
    mode: str = "standalone"
    host: str = "127.0.0.1"
    port: int = 6379
    db: int = 0
    cluster_nodes: List[Tuple[str, int]] = field(default_factory=list)
    username: str = ""
    password: str = ""
    tls: bool = False
    tls_insecure: bool = False
    socket_timeout: float = 5.0
    connect_timeout: float = 5.0

    app_base_url: str = "http://127.0.0.1:8081"
    tenant_id: int = 1
    user_id: int = 101
    tenant_header: str = "X-Tenant-Id"
    user_header: str = "X-User-Id"
    http_timeout: float = 30.0
    http_verify_tls: bool = True
    user_agent: str = "ai-redis-perf/1.0"
    measure_scenario_timeout: float = 60.0
    measure_total_timeout: float = 300.0

    namespace: str = ""
    profile: str = "smoke"
    scenarios: List[str] = field(default_factory=list)
    repeat: int = 2
    warmup: int = 1
    concurrency: int = 1

    output_dir: str = "./perf-redis-out"
    key_ttl_seconds: int = 43200
    batch_size: int = 500
    scan_count: int = 1000
    verify_settle_seconds: int = 0
    keep_guard: bool = False

    enable_attachments: bool = True
    enable_kb: bool = True
    enable_kb_reload: bool = False
    allow_file_delete: bool = False
    app_file_storage_path: str = ""
    write_global_run_index: bool = False

    allow_large_scale: bool = False
    allow_background_million: bool = False
    confirm_remote: bool = False
    confirm_non_default_db: bool = False
    isolated_env: bool = False

    sizes: Dict[str, int] = field(default_factory=dict)
    errors: List[str] = field(default_factory=list)

    # ------------------------------------------------------------------
    def is_cluster(self) -> bool:
        return self.mode == "cluster"

    def remote_targets(self) -> List[str]:
        if self.mode == "cluster":
            hosts = [host for host, _ in self.cluster_nodes]
        else:
            hosts = [self.host]
        return [host for host in hosts if host not in LOOPBACK_HOSTS]

    def assert_valid(self) -> None:
        if self.errors:
            raise ConfigError(self.errors)

    def require_write_safety(self) -> None:
        errors: List[str] = []
        if not self.isolated_env:
            errors.append(
                "拒绝写入：必须先确认这是隔离环境（PERF_ISOLATED_ENV=true）。"
                "本工具只应在专用 Redis 与专用应用实例上运行。"
            )
        if self.remote_targets() and not self.confirm_remote:
            errors.append(
                "目标是远程 Redis（%s），需显式确认 PERF_CONFIRM_REMOTE=true"
                % ",".join(self.remote_targets())
            )
        if self.mode == "standalone" and self.db != 0 and not self.confirm_non_default_db:
            errors.append(
                "standalone Redis 使用非 0 库（db=%d），需显式确认 PERF_CONFIRM_NON_DEFAULT_DB=true" % self.db
            )
        if errors:
            raise ConfigError(errors)

    def sizes_for(self, key: str) -> int:
        return int(self.sizes.get(key, 0))

    def sanitized(self) -> Dict[str, object]:
        data: Dict[str, object] = {
            "redis": {
                "mode": self.mode,
                "host": self.host,
                "port": self.port,
                "db": self.db,
                "cluster_nodes": ["%s:%d" % (h, p) for h, p in self.cluster_nodes],
                "tls": self.tls,
                "tls_insecure": self.tls_insecure,
                "username_present": bool(self.username),
                "password_present": bool(self.password),
                "socket_timeout": self.socket_timeout,
                "connect_timeout": self.connect_timeout,
            },
            "app": {
                "base_url": self.app_base_url,
                "identity": {"tenantId": self.tenant_id, "userId": self.user_id},
                "headers": [self.tenant_header, self.user_header],
                "http_timeout": self.http_timeout,
                "http_verify_tls": self.http_verify_tls,
                "measure_scenario_timeout": self.measure_scenario_timeout,
                "measure_total_timeout": self.measure_total_timeout,
            },
            "perf": {
                "namespace": self.namespace,
                "profile": self.profile,
                "scenarios": self.scenarios,
                "repeat": self.repeat,
                "warmup": self.warmup,
                "concurrency": self.concurrency,
                "key_ttl_seconds": self.key_ttl_seconds,
                "batch_size": self.batch_size,
                "scan_count": self.scan_count,
                "verify_settle_seconds": self.verify_settle_seconds,
                "keep_guard": self.keep_guard,
                "enable_attachments": self.enable_attachments,
                "enable_kb": self.enable_kb,
                "enable_kb_reload": self.enable_kb_reload,
                "allow_file_delete": self.allow_file_delete,
                "file_storage_path_configured": bool(self.app_file_storage_path),
                "write_global_run_index": self.write_global_run_index,
                "allow_large_scale": self.allow_large_scale,
                "allow_background_million": self.allow_background_million,
                "sizes": dict(self.sizes),
            },
        }
        return data

    def digest(self) -> str:
        raw = json.dumps(self.sanitized(), sort_keys=True, ensure_ascii=False).encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    def describe_for_log(self) -> Dict[str, object]:
        data = self.sanitized()
        data["config_digest"] = self.digest()
        data["python"] = sys.version.split()[0]
        data["platform"] = platform.platform()
        data["hostname"] = socket.gethostname()
        return data


def load(env: Optional[Dict[str, str]] = None) -> Config:
    source = dict(os.environ) if env is None else dict(env)
    errors: List[str] = []
    cfg = Config()

    cfg.mode = _get(source, "REDIS_MODE", _get(source, "PERF_REDIS_MODE", "standalone")).lower()
    if cfg.mode not in ("standalone", "cluster"):
        errors.append("REDIS_MODE 只能是 standalone 或 cluster（当前=%r）" % cfg.mode)

    cfg.host = _get(source, "REDIS_HOST", "127.0.0.1")
    cfg.port = _int(source, "REDIS_PORT", 6379, errors, 1)
    if cfg.port > 65535:
        errors.append("REDIS_PORT 必须 <= 65535")
    cfg.db = _int(source, "REDIS_DB", 0, errors, 0)
    if cfg.mode == "cluster":
        cfg.cluster_nodes = parse_cluster_nodes(_get(source, "REDIS_CLUSTER_NODES", ""), errors)
    else:
        cfg.cluster_nodes = []
    if cfg.mode == "cluster" and cfg.db != 0:
        errors.append("Redis Cluster 只支持 db=0（当前 REDIS_DB=%d）" % cfg.db)

    cfg.username = _get(source, "REDIS_USERNAME", "")
    cfg.password = _get(source, "REDIS_PASSWORD", "")
    cfg.tls = _bool(source, "REDIS_TLS", False, errors)
    cfg.tls_insecure = _bool(source, "REDIS_TLS_INSECURE", False, errors)
    if cfg.tls_insecure and not cfg.tls:
        errors.append("REDIS_TLS_INSECURE=true 需要同时设置 REDIS_TLS=true")
    cfg.socket_timeout = _float(source, "REDIS_SOCKET_TIMEOUT_SECONDS", 5.0, errors, 0.1)
    cfg.connect_timeout = _float(source, "REDIS_CONNECT_TIMEOUT_SECONDS", 5.0, errors, 0.1)

    base_url = _get(source, "APP_BASE_URL", "http://127.0.0.1:8081").rstrip("/")
    if not (base_url.startswith("http://") or base_url.startswith("https://")):
        errors.append("APP_BASE_URL 必须以 http:// 或 https:// 开头（当前=%r）" % base_url)
    cfg.app_base_url = base_url
    cfg.tenant_id = _int(source, "APP_TENANT_ID", 1, errors, 1)
    cfg.user_id = _int(source, "APP_USER_ID", 101, errors, 1)
    cfg.tenant_header = _get(source, "APP_TENANT_HEADER", "X-Tenant-Id")
    cfg.user_header = _get(source, "APP_USER_HEADER", "X-User-Id")
    cfg.http_timeout = _float(source, "APP_HTTP_TIMEOUT_SECONDS", 30.0, errors, 1.0)
    cfg.http_verify_tls = _bool(source, "APP_HTTP_VERIFY_TLS", True, errors)
    cfg.user_agent = _get(source, "PERF_USER_AGENT", "ai-redis-perf/1.0")
    cfg.measure_scenario_timeout = _float(
        source, "PERF_MEASURE_SCENARIO_TIMEOUT_SECONDS", 60.0, errors, 0.0)
    cfg.measure_total_timeout = _float(
        source, "PERF_MEASURE_TOTAL_TIMEOUT_SECONDS", 300.0, errors, 0.0)

    cfg.namespace = _get(source, "PERF_NAMESPACE", "")
    if cfg.namespace and not NAMESPACE_PATTERN.match(cfg.namespace):
        errors.append(
            "PERF_NAMESPACE 只允许字母/数字/_.-，长度 2..41（当前=%r）；它会被拼进会话号与清理扫描"
            % cfg.namespace
        )
    cfg.profile = _get(source, "PERF_PROFILE", "smoke").lower()
    if cfg.profile not in PROFILES:
        errors.append("PERF_PROFILE 只能是 %s（当前=%r）" % ("/".join(PROFILES), cfg.profile))
    cfg.scenarios = _csv(source, "PERF_SCENARIOS", "")
    cfg.repeat = _int(source, "PERF_REPEAT", 2, errors, 1)
    cfg.warmup = _int(source, "PERF_WARMUP", 1, errors, 0)
    cfg.concurrency = _int(source, "PERF_CONCURRENCY", 1, errors, 1)
    if cfg.concurrency > 64:
        errors.append("PERF_CONCURRENCY 上限 64（当前=%d）" % cfg.concurrency)

    cfg.output_dir = _get(source, "PERF_OUTPUT_DIR", "./perf-redis-out") or "./perf-redis-out"
    cfg.key_ttl_seconds = _int(source, "PERF_KEY_TTL_SECONDS", 43200, errors, 60)
    cfg.batch_size = _int(source, "PERF_BATCH_SIZE", 500, errors, 1)
    if cfg.batch_size > 10000:
        errors.append("PERF_BATCH_SIZE 上限 10000")
    cfg.scan_count = _int(source, "PERF_SCAN_COUNT", 1000, errors, 10)
    if cfg.scan_count > 100000:
        errors.append("PERF_SCAN_COUNT 上限 100000")
    cfg.verify_settle_seconds = _int(source, "PERF_VERIFY_SETTLE_SECONDS", 0, errors, 0)
    cfg.keep_guard = _bool(source, "PERF_KEEP_GUARD", False, errors)

    cfg.enable_attachments = _bool(source, "PERF_ENABLE_ATTACHMENTS", True, errors)
    cfg.enable_kb = _bool(source, "PERF_ENABLE_KB", True, errors)
    cfg.enable_kb_reload = _bool(source, "PERF_ENABLE_KB_RELOAD", False, errors)
    cfg.allow_file_delete = _bool(source, "PERF_ALLOW_FILE_DELETE", False, errors)
    cfg.app_file_storage_path = _get(source, "PERF_APP_FILE_STORAGE_PATH", "")
    if cfg.app_file_storage_path and not os.path.isabs(cfg.app_file_storage_path):
        errors.append("PERF_APP_FILE_STORAGE_PATH 必须是绝对路径（用于文件残留核对/安全删除）")
    cfg.write_global_run_index = _bool(source, "PERF_WRITE_GLOBAL_RUN_INDEX", False, errors)

    cfg.allow_large_scale = _bool(source, "PERF_ALLOW_LARGE_SCALE", False, errors)
    cfg.allow_background_million = _bool(source, "PERF_ALLOW_BACKGROUND_MILLION", False, errors)
    cfg.confirm_remote = _bool(source, "PERF_CONFIRM_REMOTE", False, errors)
    cfg.confirm_non_default_db = _bool(source, "PERF_CONFIRM_NON_DEFAULT_DB", False, errors)
    cfg.isolated_env = _bool(source, "PERF_ISOLATED_ENV", False, errors)

    cfg.sizes = {
        "bg_10k": _int(source, "PERF_BG_KEYS_10K", 10000, errors, 1),
        "bg_100k": _int(source, "PERF_BG_KEYS_100K", 100000, errors, 1),
        "bg_1m": _int(source, "PERF_BG_KEYS_1M", 1000000, errors, 1),
        "runs_small": _int(source, "PERF_RUNS_SMALL", 1, errors, 0),
        "runs_med": _int(source, "PERF_RUNS_MED", 10, errors, 0),
        "runs_large": _int(source, "PERF_RUNS_LARGE", 50, errors, 0),
        "staging_small": _int(source, "PERF_STAGING_SMALL", 5, errors, 0),
        "staging_large": _int(source, "PERF_STAGING_LARGE", 500, errors, 0),
        "archive_small": _int(source, "PERF_ARCHIVE_SMALL", 10, errors, 0),
        "archive_large": _int(source, "PERF_ARCHIVE_LARGE", 2000, errors, 0),
        "frames_small": _int(source, "PERF_FRAMES_SMALL", 128, errors, 0),
        "frames_large": _int(source, "PERF_FRAMES_LARGE", 9000, errors, 0),
        "transcript_small": _int(source, "PERF_TRANSCRIPT_SMALL", 100, errors, 0),
        "transcript_large": _int(source, "PERF_TRANSCRIPT_LARGE", 5000, errors, 0),
        "memory_small": _int(source, "PERF_MEMORY_SMALL", 24, errors, 0),
        "memory_large": _int(source, "PERF_MEMORY_LARGE", 400, errors, 0),
        "attachment_max": _int(source, "PERF_ATTACHMENT_MAX", 3, errors, 0),
        "feedback_rows": _int(source, "PERF_FEEDBACK_ROWS", 2, errors, 0),
    }
    for name, value in cfg.sizes.items():
        if name.startswith("bg_") and value > 5000000:
            errors.append("%s 上限 5,000,000（当前=%d）" % (name, value))

    cfg.errors = errors
    return cfg


def compare_git(repo_root: str) -> Dict[str, object]:
    """Read-only git summary. Never runs a write command."""
    import subprocess

    result: Dict[str, object] = {"available": False}
    try:
        commit = subprocess.run(
            ["git", "-C", repo_root, "rev-parse", "HEAD"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
        )
        if commit.returncode != 0:
            return result
        branch = subprocess.run(
            ["git", "-C", repo_root, "rev-parse", "--abbrev-ref", "HEAD"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
        )
        dirty = subprocess.run(
            ["git", "-C", repo_root, "status", "--porcelain"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20,
        )
        result["available"] = True
        result["commit"] = commit.stdout.decode("utf-8", "replace").strip()
        result["branch"] = branch.stdout.decode("utf-8", "replace").strip()
        status_text = dirty.stdout.decode("utf-8", "replace")
        result["dirty"] = bool(status_text.strip())
        result["dirty_paths"] = len([line for line in status_text.splitlines() if line.strip()])
    except (OSError, subprocess.SubprocessError):
        return result
    return result
