"""Latency statistics (mean / P50 / P95 / P99 / min / max)."""

from __future__ import annotations

import math
from typing import Dict, Iterable, List


def percentile(sorted_values: List[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    if p <= 0:
        return sorted_values[0]
    if p >= 100:
        return sorted_values[-1]
    rank = int(math.ceil((p / 100.0) * len(sorted_values))) - 1
    if rank < 0:
        rank = 0
    if rank >= len(sorted_values):
        rank = len(sorted_values) - 1
    return sorted_values[rank]


def summarize(values: Iterable[float]) -> Dict[str, float]:
    clean = sorted(float(value) for value in values)
    if not clean:
        return {
            "count": 0,
            "mean": 0.0,
            "p50": 0.0,
            "p95": 0.0,
            "p99": 0.0,
            "min": 0.0,
            "max": 0.0,
        }
    total = sum(clean)
    return {
        "count": len(clean),
        "mean": round(total / len(clean), 3),
        "p50": round(percentile(clean, 50), 3),
        "p95": round(percentile(clean, 95), 3),
        "p99": round(percentile(clean, 99), 3),
        "min": round(clean[0], 3),
        "max": round(clean[-1], 3),
    }


def group_summaries(rows: List[Dict[str, object]], group_keys: List[str],
                    value_key: str = "duration_ms") -> List[Dict[str, object]]:
    buckets: Dict[str, List[float]] = {}
    meta: Dict[str, Dict[str, object]] = {}
    for row in rows:
        label = " | ".join(str(row.get(key, "")) for key in group_keys)
        buckets.setdefault(label, []).append(float(row.get(value_key, 0.0)))
        if label not in meta:
            meta[label] = {key: row.get(key, "") for key in group_keys}
    out: List[Dict[str, object]] = []
    for label in sorted(buckets):
        item = dict(meta[label])
        item.update(summarize(buckets[label]))
        out.append(item)
    return out
