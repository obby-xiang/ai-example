"""Measurement budgets: per-scenario and total limits must bound requests.

A single DELETE must not keep the configured 300s HTTP timeout after the remaining
budget is smaller; exhausted scenarios keep their partial rows and later
scenarios are marked skipped instead of being reported as measured.
"""

import unittest
from unittest import mock

from perf_redis import measure as MM
from perf_redis.envcfg import Config


class Clock:
    def __init__(self, start=1000.0):
        self.value = float(start)

    def __call__(self):
        return self.value

    def advance(self, seconds):
        self.value += float(seconds)


class FakeRedis:
    def __init__(self, cfg):
        self.redis_mode = "standalone"
        self.redis_version = "7.0.0"

    def connect(self):
        pass

    def keyspace(self):
        return {}

    def role_summary(self):
        return {}

    def close(self):
        pass


class FakeApi:
    def __init__(self, clock):
        self.clock = clock
        self.timeouts = []

    def health(self, timeout=None):
        return {
            "operation": "health", "status": 200, "ok": True, "duration_ms": 0.0,
            "transport_error": "", "business": {}, "body_keys": [], "text_prefix": "",
        }

    def delete_session(self, session_id, operation="delete", timeout=None):
        self.timeouts.append(timeout)
        self.clock.advance(timeout if timeout is not None else 0.0)
        return {
            "operation": operation,
            "method": "DELETE",
            "path": "/api/ai/session/" + session_id,
            "status": None,
            "ok": False,
            "duration_ms": 0.0,
            "transport_error": "timed out",
            "timed_out": True,
            "request_timeout_s": timeout,
            "server_timing": "",
            "business": {},
            "body_keys": [],
            "text_prefix": "",
        }

    def close(self):
        pass


def _entry():
    return {
        "status": "generated",
        "plan": {"ops": ["delete"], "verify_mode": "delete"},
        "copies": [
            {"copy": "r1", "session_id": "s-r1"},
            {"copy": "r2", "session_id": "s-r2"},
        ],
    }


def _config():
    return Config(
        tenant_id=1,
        user_id=101,
        namespace="unittest",
        isolated_env=True,
        repeat=2,
        warmup=0,
        concurrency=1,
        http_timeout=300.0,
        measure_scenario_timeout=2.0,
        measure_total_timeout=2.5,
    )


class BudgetTest(unittest.TestCase):
    def test_scenario_limit_caps_remaining(self):
        clock = Clock()
        budget = MM._Budget(total_seconds=100, scenario_seconds=10, clock=clock)
        budget.start_scenario()
        self.assertAlmostEqual(budget.remaining(), 10.0)
        clock.advance(4)
        self.assertAlmostEqual(budget.remaining(), 6.0)
        self.assertEqual(budget.exhausted_kind(), "")
        clock.advance(6)
        self.assertTrue(budget.expired())
        self.assertEqual(budget.exhausted_kind(), "scenario")

    def test_total_limit_caps_scenario_limit(self):
        clock = Clock()
        budget = MM._Budget(total_seconds=5, scenario_seconds=10, clock=clock)
        budget.start_scenario()
        self.assertAlmostEqual(budget.remaining(), 5.0)
        clock.advance(5)
        self.assertEqual(budget.exhausted_kind(), "total")

    def test_zero_disables_the_limit(self):
        budget = MM._Budget(total_seconds=0, scenario_seconds=0, clock=Clock())
        budget.start_scenario()
        self.assertIsNone(budget.remaining())
        self.assertFalse(budget.expired())


class MeasureBudgetTest(unittest.TestCase):
    def test_exhaustion_keeps_partial_results_and_skips_later_scenarios(self):
        clock = Clock()
        api = FakeApi(clock)
        manifest = {"scenarios": {"s1": _entry(), "s2": _entry(), "s3": _entry()}}
        with mock.patch.object(MM, "_monotonic", clock), \
                mock.patch.object(MM, "ApiClient", lambda cfg: api), \
                mock.patch.object(MM, "RedisOps", FakeRedis):
            result = MM.measure(_config(), manifest, ["s1", "s2", "s3"])

        self.assertEqual(len(api.timeouts), 2)
        self.assertAlmostEqual(api.timeouts[0], 2.0)
        self.assertAlmostEqual(api.timeouts[1], 0.5)
        self.assertEqual(len(result["rows"]), 2)
        self.assertTrue(all(row["accepted"] is False for row in result["rows"]))
        self.assertTrue(all(row["failure_kind"] == "transport" for row in result["rows"]))
        self.assertEqual(result["rows"][0]["budget_error"], "scenario")
        self.assertEqual(result["rows"][1]["budget_error"], "total")
        self.assertEqual(result["transport_failures"], 2)
        self.assertEqual(result["failed_requests"], 2)
        self.assertEqual(result["measured_requests"], 2)

        scenarios = {item["scenario"]: item for item in result["scenarios"]}
        self.assertEqual(scenarios["s1"]["status"], "budget_exhausted")
        self.assertEqual(len(scenarios["s1"]["skipped"]), 1)
        self.assertEqual(scenarios["s2"]["status"], "budget_exhausted")
        self.assertEqual(len(scenarios["s2"]["skipped"]), 1)
        self.assertEqual(scenarios["s3"]["status"], "skipped")
        self.assertEqual(scenarios["s3"]["reason"], "budget_exhausted")
        self.assertEqual(result["budget_exhausted_scenarios"], 2)
        self.assertEqual(result["skipped_scenarios"], 1)
        self.assertTrue(result["budget"]["exhausted"])
        self.assertTrue(result["budget"]["total_exhausted"])
        self.assertAlmostEqual(result["budget"]["elapsed_seconds"], 2.5)


if __name__ == "__main__":
    unittest.main()
