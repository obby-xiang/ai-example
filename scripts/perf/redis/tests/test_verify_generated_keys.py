"""Deferred-run key checks must follow the manifest's generated-key list.

fames_terminal_* runs are generated with pending=0, so only the 7 always-written
keys exist. Requiring all 13 keys produced false FAILs; ignoring missing keys
would hide real deletions.
"""

import unittest

from perf_redis import derive as D
from perf_redis import keys as K
from perf_redis import verify as V
from perf_redis.envcfg import Config


def _run_entry(**overrides):
    entry = {
        "run_id": "rid",
        "status": "RUNNING",
        "pending": 0,
        "frames": {"count": 128, "terminal": "tail", "gap": False, "terminalType": "done"},
        "keys": K.run_keys_13("rid", 1, 101),
    }
    entry.update(overrides)
    return entry


class FakeOps:
    def __init__(self, present):
        self.present = set(present)
        self.queried = []

    def exists_many(self, keys):
        keys = list(keys)
        self.queried = keys
        return {key: key in self.present for key in keys}


def _copy_keys(entry):
    cfg = Config()
    cfg.tenant_id = 1
    cfg.user_id = 101
    copy = {"session_id": "p-ns-frames_terminal_tail-r1", "runs": [entry]}
    return D.copy_keys(cfg, "frames_terminal_tail", {"session_record": True}, copy)


class RunGeneratedKeyNamesTest(unittest.TestCase):
    def test_manifest_generated_keys_are_authoritative(self):
        got = D.run_generated_key_names(
            _run_entry(generated_keys=["run", "runstate", "seq", "xevents"])
        )
        self.assertEqual(set(got["names"]), {"run", "runstate", "seq", "xevents"})
        self.assertEqual(got["unknown"], [])
        self.assertEqual(got["source"], "manifest")

    def test_old_manifest_derives_written_keys_and_marks_lease_unknown(self):
        got = D.run_generated_key_names(_run_entry())
        self.assertIn("xevents", got["names"])
        self.assertIn("ledger", got["names"])
        for name in ("pending", "claim", "idem", "lease", "req", "decided"):
            self.assertNotIn(name, got["names"])
        self.assertEqual(got["unknown"], ["lease"])
        self.assertEqual(got["source"], "derived")

    def test_old_manifest_with_pending_derives_pending_family(self):
        got = D.run_generated_key_names(
            _run_entry(pending=2, frames={"count": 0}, lease_held=False)
        )
        for name in ("pending", "claim", "idem", "req", "decided", "ledger"):
            self.assertIn(name, got["names"])
        self.assertNotIn("xevents", got["names"])
        self.assertEqual(got["unknown"], [])


class DeferredRunCheckTest(unittest.TestCase):
    def test_only_generated_keys_are_required(self):
        entry = _run_entry(generated_keys=[
            "run", "runstate", "lease_epoch", "ledger", "seq", "fence", "xevents",
        ])
        keys = _copy_keys(entry)
        expected = list(keys["runs"]["rid"]["generated_keys"].values())
        self.assertEqual(len(expected), 7)
        ops = FakeOps(expected)
        checks = []
        V._check_deferred_runs(ops, checks, "frames_terminal_tail", "r1", keys, ["rid"])
        self.assertEqual(checks[-1]["status"], "pass", checks[-1])
        self.assertEqual(ops.queried, expected)

    def test_real_deletion_of_a_generated_key_still_fails(self):
        entry = _run_entry(generated_keys=[
            "run", "runstate", "lease_epoch", "ledger", "seq", "fence", "xevents",
        ])
        keys = _copy_keys(entry)
        expected = list(keys["runs"]["rid"]["generated_keys"].values())
        ops = FakeOps(expected[1:])
        checks = []
        V._check_deferred_runs(ops, checks, "frames_terminal_tail", "r1", keys, ["rid"])
        self.assertEqual(checks[-1]["status"], "fail", checks[-1])
        self.assertIn(expected[0], checks[-1]["detail"])

    def test_old_manifest_lease_is_unknown_not_pass(self):
        keys = _copy_keys(_run_entry())
        expected = list(keys["runs"]["rid"]["generated_keys"].values())
        self.assertEqual(len(expected), 7)
        ops = FakeOps(expected)
        checks = []
        V._check_deferred_runs(ops, checks, "frames_terminal_tail", "r1", keys, ["rid"])
        self.assertEqual(checks[-1]["status"], "unknown", checks[-1])
        self.assertIn("lease", checks[-1]["detail"])


class BudgetMeasureCheckTest(unittest.TestCase):
    def test_budget_exhausted_scenario_is_unknown(self):
        checks = []
        skipped = V._check_measure_budget(checks, "s1", {"status": "budget_exhausted"})
        self.assertFalse(skipped)
        self.assertEqual(checks[-1]["status"], "unknown")
        self.assertEqual(checks[-1]["check"], "measure_budget")

    def test_never_measured_scenario_tells_callers_to_skip(self):
        checks = []
        skipped = V._check_measure_budget(checks, "s2", {"status": "skipped"})
        self.assertTrue(skipped)
        self.assertEqual(checks[-1]["status"], "unknown")

    def test_measured_scenario_has_no_budget_check(self):
        checks = []
        skipped = V._check_measure_budget(checks, "s3", {"status": "measured"})
        self.assertFalse(skipped)
        self.assertEqual(checks, [])


if __name__ == "__main__":
    unittest.main()
