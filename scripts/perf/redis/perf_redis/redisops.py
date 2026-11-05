"""Thin Redis access layer (redis-py only).

Safety rules enforced here:
  * SCAN only (never KEYS);
  * never FLUSHDB / FLUSHALL / DEBUG / CONFIG;
  * cluster scans run per primary node because a single-node SCAN silently misses
    keys that live on other masters;
  * multi-key commands are never issued on cluster (single-key commands are
    routed by redis-py);
  * owned-key writes require a TTL; shared global index writes never expire the key.
"""

from __future__ import annotations

import time
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from .envcfg import Config
from . import keys as K


class RedisUnavailable(Exception):
    pass


class ScanBudgetExceeded(Exception):
    """Bounded SCAN hit its per-node page cap or monotonic deadline."""

    pass


class ScanStats:
    def __init__(self) -> None:
        self.keys = 0
        self.pages = 0
        self.per_node: Dict[str, int] = {}
        self.per_node_pages: Dict[str, int] = {}

    def as_dict(self) -> Dict[str, object]:
        return {
            "keys": self.keys,
            "pages": self.pages,
            "per_node": dict(self.per_node),
            "per_node_pages": dict(self.per_node_pages),
        }


class RedisOps:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.client = None
        self._node_clients: Dict[str, object] = {}
        self.redis_version = ""
        self.redis_mode = ""
        self.last_error = ""

    # ------------------------------------------------------------------ connect
    def _common_kwargs(self) -> Dict[str, object]:
        kwargs: Dict[str, object] = {
            "socket_timeout": self.cfg.socket_timeout,
            "socket_connect_timeout": self.cfg.connect_timeout,
            "decode_responses": True,
        }
        if self.cfg.username:
            kwargs["username"] = self.cfg.username
        if self.cfg.password:
            kwargs["password"] = self.cfg.password
        if self.cfg.tls:
            kwargs["ssl"] = True
            if self.cfg.tls_insecure:
                kwargs["ssl_cert_reqs"] = "none"
        return kwargs

    def connect(self) -> None:
        import redis

        try:
            if self.cfg.is_cluster():
                from redis.cluster import ClusterNode, RedisCluster

                startup = [ClusterNode(host, port) for host, port in self.cfg.cluster_nodes]
                self.client = RedisCluster(startup_nodes=startup, **self._common_kwargs())
            else:
                kwargs = self._common_kwargs()
                kwargs["host"] = self.cfg.host
                kwargs["port"] = self.cfg.port
                kwargs["db"] = self.cfg.db
                self.client = redis.Redis(**kwargs)
            self.client.ping()
        except Exception as exc:  # noqa: BLE001 - surfaced with context
            self.last_error = str(exc)
            raise RedisUnavailable("Redis 连接失败：%s" % exc)
        self.read_server_facts()

    def close(self) -> None:
        for node_client in list(self._node_clients.values()):
            try:
                node_client.close()
            except Exception:
                pass
        self._node_clients.clear()
        if self.client is not None:
            try:
                self.client.close()
            except Exception:
                pass

    # ------------------------------------------------------------------- facts
    def read_server_facts(self) -> None:
        info = self._info_from_any_node()
        self.redis_version = str(info.get("redis_version", ""))
        self.redis_mode = str(info.get("redis_mode", ""))

    def _info_from_any_node(self) -> Dict[str, object]:
        candidates: List[object] = []
        if self.cfg.is_cluster():
            candidates.extend(self.primary_node_clients())
        if self.client is not None:
            candidates.append(self.client)
        for candidate in candidates:
            try:
                info = candidate.info("server")
                if isinstance(info, dict):
                    return info
            except Exception:
                continue
        return {}

    def ping(self) -> Tuple[bool, float, str]:
        start = time.perf_counter()
        try:
            self.client.ping()
            return True, (time.perf_counter() - start) * 1000.0, ""
        except Exception as exc:
            return False, (time.perf_counter() - start) * 1000.0, str(exc)

    def keyspace(self) -> Dict[str, object]:
        try:
            return self.client.info("keyspace")
        except Exception:
            return {}

    def cluster_info(self) -> Dict[str, object]:
        if not self.cfg.is_cluster():
            return {}
        try:
            info = self.client.cluster_info()
            if isinstance(info, dict):
                return info
        except Exception:
            pass
        try:
            info = self.client.execute_command("CLUSTER", "INFO")
            if isinstance(info, dict):
                return info
        except Exception as exc:
            return {"error": str(exc)}
        return {}

    def primaries(self, allow_seed_fallback: bool = True) -> List[Tuple[str, int]]:
        """Primary (host, port) list from CLUSTER NODES (read-only).

        ``allow_seed_fallback=False`` is used by the SCAN path: scanning only the
        configured seeds can silently miss keys owned by other masters, so the
        caller must fail instead of treating seeds as a complete topology.
        """
        if not self.cfg.is_cluster():
            return [(self.cfg.host, self.cfg.port)]
        nodes: List[Tuple[str, int]] = []
        raw: object = {}
        try:
            raw = self.client.cluster_nodes()
        except Exception:
            raw = {}
        if isinstance(raw, dict):
            for attrs in raw.values():
                if not isinstance(attrs, dict):
                    continue
                flags = str(attrs.get("flags", ""))
                if "master" not in flags.split(","):
                    continue
                host = attrs.get("host")
                port = attrs.get("port")
                if not host or host in ("", "?"):
                    continue
                try:
                    port_int = int(port)
                except (TypeError, ValueError):
                    continue
                item = (str(host), port_int)
                if item not in nodes:
                    nodes.append(item)
        if not nodes:
            # Very old redis-py: parse the raw CLUSTER NODES text.
            try:
                text = self.client.execute_command("CLUSTER", "NODES")
            except Exception:
                text = ""
            if isinstance(text, str):
                for line in text.splitlines():
                    parts = line.split()
                    if len(parts) < 3:
                        continue
                    flags = parts[2].split(",")
                    if "master" not in flags:
                        continue
                    address = parts[1]
                    if ":" not in address:
                        continue
                    host, port_text = address.rsplit(":", 1)
                    host = host.split("@")[0]
                    try:
                        port_int = int(port_text.split("@")[0])
                    except ValueError:
                        continue
                    if host and host != "?":
                        item = (host, port_int)
                        if item not in nodes:
                            nodes.append(item)
        if not nodes and allow_seed_fallback:
            nodes = list(self.cfg.cluster_nodes)
        return nodes

    def primary_node_clients(self, strict: bool = False,
                             targets: Optional[List[Tuple[str, int]]] = None) -> List[object]:
        """Open (and keep) one standalone client per primary.

        ``strict=True`` is used by SCAN: every primary must have a usable
        connection, otherwise a partial scan could be mistaken for a complete
        one. The error names the failing host:port.
        """
        import redis

        nodes = list(targets) if targets is not None else self.primaries()
        clients: List[object] = []
        for host, port in nodes:
            cache_key = "%s:%d" % (host, port)
            node_client = self._node_clients.get(cache_key)
            if node_client is None:
                kwargs = self._common_kwargs()
                kwargs["host"] = host
                kwargs["port"] = port
                kwargs["db"] = 0
                try:
                    node_client = redis.Redis(**kwargs)
                    node_client.ping()
                except Exception as exc:
                    if strict:
                        raise RedisUnavailable(
                            "cluster primary 连接失败 %s:%d：%s" % (host, port, exc)
                        )
                    continue
                self._node_clients[cache_key] = node_client
            if strict:
                try:
                    node_client.ping()
                except Exception as exc:
                    raise RedisUnavailable(
                        "cluster primary 连接失败 %s:%d：%s" % (host, port, exc)
                    )
            clients.append(node_client)
        if strict and len(clients) != len(nodes):
            raise RedisUnavailable(
                "cluster primary 连接不完整：可用 %d/%d" % (len(clients), len(nodes))
            )
        return clients

    # ------------------------------------------------------------------- reads
    def exists(self, key: str) -> bool:
        try:
            return bool(self.client.exists(key))
        except Exception:
            return False

    def get_str(self, key: str) -> Optional[str]:
        try:
            return self.client.get(key)
        except Exception:
            return None

    def ttl(self, key: str) -> int:
        try:
            return int(self.client.ttl(key))
        except Exception:
            return -3

    def key_type(self, key: str) -> str:
        try:
            return str(self.client.type(key))
        except Exception:
            return "unknown"

    def llen(self, key: str) -> int:
        try:
            return int(self.client.llen(key))
        except Exception:
            return -1

    def hgetall(self, key: str) -> Dict[str, str]:
        try:
            raw = self.client.hgetall(key)
        except Exception:
            return {}
        if not isinstance(raw, dict):
            return {}
        return {str(k): str(v) for k, v in raw.items()}

    def zscore(self, key: str, member: str) -> Optional[float]:
        try:
            value = self.client.zscore(key, member)
            return None if value is None else float(value)
        except Exception:
            return None

    def smembers(self, key: str) -> Optional[List[str]]:
        try:
            raw = self.client.smembers(key)
        except Exception:
            return None
        return sorted(str(item) for item in raw) if raw else []

    # ---------------------------------------------------------------- pipelines
    def _chunks(self, values: Sequence[object]) -> List[Sequence[object]]:
        size = max(1, self.cfg.batch_size)
        return [values[index:index + size] for index in range(0, len(values), size)]

    def _pipeline(self):
        return self.client.pipeline(transaction=False)

    def _scalar_many(self, keys: Sequence[str], method: str) -> Dict[str, object]:
        result: Dict[str, object] = {}
        for chunk in self._chunks(list(keys)):
            try:
                pipe = self._pipeline()
                for key in chunk:
                    getattr(pipe, method)(key)
                values = pipe.execute()
                for key, value in zip(chunk, values):
                    result[str(key)] = value
            except Exception:
                for key in chunk:
                    try:
                        result[str(key)] = getattr(self.client, method)(key)
                    except Exception:
                        result[str(key)] = None
        return result

    def exists_many(self, keys: Sequence[str]) -> Dict[str, object]:
        raw = self._scalar_many(keys, "exists")
        return {key: (None if value is None else bool(value)) for key, value in raw.items()}

    def ttl_many(self, keys: Sequence[str]) -> Dict[str, int]:
        raw = self._scalar_many(keys, "ttl")
        out: Dict[str, int] = {}
        for key, value in raw.items():
            try:
                out[key] = int(value)
            except (TypeError, ValueError):
                out[key] = -3
        return out

    def type_many(self, keys: Sequence[str]) -> Dict[str, str]:
        raw = self._scalar_many(keys, "type")
        return {key: str(value) if value is not None else "unknown" for key, value in raw.items()}

    # ------------------------------------------------------------------- scans
    def iter_scan(self, match: str, stats: Optional[ScanStats] = None,
                  max_pages_per_node: Optional[int] = None,
                  deadline: Optional[float] = None) -> Iterable[str]:
        """Yield keys matching ``match`` using SCAN only (per primary on cluster).

        ``max_pages_per_node``/``deadline`` are hard bounds checked inside the
        cursor loop, including no-match scans that never yield a key.
        Raises ScanBudgetExceeded when either bound is hit.
        """
        if not self.cfg.is_cluster():
            try:
                for key in self._scan_one(self.client, match, "standalone", stats,
                                          max_pages_per_node, deadline):
                    yield key
            except ScanBudgetExceeded:
                raise
            except Exception as exc:
                raise RedisUnavailable("SCAN 失败（standalone）：%s" % exc)
            return
        primaries = self.primaries(allow_seed_fallback=False)
        if not primaries:
            raise RedisUnavailable("SCAN 失败：无法读取 cluster 主节点拓扑（拒绝用种子节点冒充完整扫描）")
        clients = self.primary_node_clients(strict=True, targets=primaries)
        if len(clients) != len(primaries):
            raise RedisUnavailable(
                "SCAN 失败：cluster 主节点连接不完整 %d/%d" % (len(clients), len(primaries))
            )
        for (host, port), node_client in zip(primaries, clients):
            label = "%s:%d" % (host, port)
            try:
                for key in self._scan_one(node_client, match, label, stats,
                                          max_pages_per_node, deadline):
                    yield key
            except ScanBudgetExceeded:
                raise
            except Exception as exc:
                raise RedisUnavailable(
                    "SCAN 失败：cluster primary %s:%d 中断（扫描结果不完整）：%s"
                    % (host, port, exc)
                )

    def _scan_one(self, client, match: str, label: str, stats: Optional[ScanStats],
                  max_pages_per_node: Optional[int] = None,
                  deadline: Optional[float] = None) -> Iterable[str]:
        cursor = 0
        pages = 0
        while True:
            if deadline is not None and time.monotonic() >= deadline:
                raise ScanBudgetExceeded("达到单调时间预算，停止扫描（%s）" % label)
            cursor, batch = client.scan(cursor=cursor, match=match, count=self.cfg.scan_count)
            pages += 1
            if max_pages_per_node is not None and pages > max_pages_per_node:
                raise ScanBudgetExceeded(
                    "达到每节点页数预算 %d，停止扫描（%s）" % (max_pages_per_node, label)
                )
            if stats is not None:
                stats.pages += 1
                stats.per_node_pages[label] = stats.per_node_pages.get(label, 0) + 1
            for key in batch:
                text = str(key)
                if stats is not None:
                    stats.keys += 1
                    stats.per_node[label] = stats.per_node.get(label, 0) + 1
                yield text
            if cursor == 0:
                break

    # ------------------------------------------------------------------ writes
    def set_values(self, items: Sequence[Tuple[str, str, int]]) -> int:
        written = 0
        for chunk in self._chunks(list(items)):
            pipe = self._pipeline()
            for key, value, ttl in chunk:
                pipe.set(key, value, px=max(1, int(ttl)) * 1000)
            results = pipe.execute()
            written += len([item for item in results if item])
        return written

    def rpush_values(self, key: str, values: Sequence[str], ttl: int) -> int:
        total = 0
        for chunk in self._chunks(list(values)):
            pipe = self._pipeline()
            pipe.rpush(key, *chunk)
            pipe.execute()
            total += len(chunk)
        try:
            self.client.expire(key, max(1, int(ttl)))
        except Exception:
            pass
        return total

    def hset_mapping(self, key: str, mapping: Dict[str, str], ttl: int) -> None:
        self.client.hset(key, mapping=mapping)
        self.client.expire(key, max(1, int(ttl)))

    def sadd_members(self, key: str, members: Sequence[str], ttl: int) -> None:
        if not members:
            return
        self.client.sadd(key, *members)
        self.client.expire(key, max(1, int(ttl)))

    def srem_members(self, key: str, members: Sequence[str]) -> int:
        if not members:
            return 0
        try:
            return int(self.client.srem(key, *members))
        except Exception:
            return 0

    def zadd_mapping(self, key: str, mapping: Dict[str, float], ttl: Optional[int]) -> None:
        if not mapping:
            return
        self.client.zadd(key, mapping)
        if ttl is not None:
            self.client.expire(key, max(1, int(ttl)))

    def zrem_members(self, key: str, members: Sequence[str]) -> int:
        if not members:
            return 0
        try:
            return int(self.client.zrem(key, *members))
        except Exception:
            return 0

    def xadd_frames(self, key: str, entries: Sequence[Tuple[str, Dict[str, str]]], ttl: int) -> int:
        written = 0
        for chunk in self._chunks(list(entries)):
            pipe = self._pipeline()
            for entry_id, fields in chunk:
                pipe.xadd(key, fields, id=entry_id)
            try:
                pipe.execute()
                written += len(chunk)
            except Exception:
                # Isolate one bad entry; ignore an id that the failed pipeline may
                # already have written (XADD then reports "equal or smaller ID").
                for entry_id, fields in chunk:
                    try:
                        self.client.xadd(key, fields, id=entry_id)
                        written += 1
                    except Exception:
                        continue
        try:
            self.client.expire(key, max(1, int(ttl)))
        except Exception:
            pass
        return written

    def xlen(self, key: str) -> int:
        try:
            return int(self.client.xlen(key))
        except Exception:
            return -1

    def xlast_id(self, key: str) -> str:
        try:
            items = self.client.xrevrange(key, count=1)
        except Exception:
            return ""
        if not items:
            return ""
        return str(items[0][0])

    # ----------------------------------------------------------------- deletes
    def delete_keys(self, keys: Sequence[str]) -> Dict[str, object]:
        unique = K.dedupe([key for key in keys if key])
        deleted = 0
        errors: List[str] = []
        for chunk in self._chunks(unique):
            if self.cfg.is_cluster():
                pipe = self._pipeline()
                for key in chunk:
                    pipe.delete(key)
                try:
                    results = pipe.execute()
                    for value in results:
                        if value:
                            deleted += 1
                except Exception as exc:
                    errors.append("cluster delete batch: %s" % exc)
                    for key in chunk:
                        try:
                            deleted += int(bool(self.client.delete(key)))
                        except Exception as single_exc:
                            errors.append("%s: %s" % (key, single_exc))
            else:
                try:
                    deleted += int(self.client.unlink(*chunk))
                except Exception:
                    try:
                        deleted += int(self.client.delete(*chunk))
                    except Exception as exc:
                        errors.append("standalone delete batch: %s" % exc)
        return {"requested": len(unique), "deleted": deleted, "errors": errors}

    # ------------------------------------------------------------------ probes
    def write_probe(self, key: str, ttl_seconds: int = 60) -> Tuple[bool, str]:
        try:
            self.client.set(key, "probe", px=max(1, ttl_seconds) * 1000)
            value = self.client.get(key)
            self.client.delete(key)
            if value != "probe":
                return False, "写入后读回不一致"
            return True, ""
        except Exception as exc:
            return False, str(exc)

    def role_summary(self) -> Dict[str, object]:
        if not self.cfg.is_cluster():
            return {"mode": "standalone"}
        info = self.cluster_info()
        return {
            "mode": "cluster",
            "cluster_state": info.get("cluster_state"),
            "cluster_slots_assigned": info.get("cluster_slots_assigned"),
            "cluster_known_nodes": info.get("cluster_known_nodes"),
            "primaries": ["%s:%d" % (host, port) for host, port in self.primaries()],
        }
