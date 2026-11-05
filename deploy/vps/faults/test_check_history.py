#!/usr/bin/env python3
"""A checker that never fails proves nothing: planted violations must be caught, clean runs must pass.

    python3 test_check_history.py
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("check_history", Path(__file__).with_name("check-history.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class Run:
    """Builds a history file. Times are in milliseconds here and microseconds in the file."""

    def __init__(self, levels=("tier2",)):
        self.lines = [{"t": "start", "backend": "test", "levels": list(levels), "ts": 0,
                       "write_period_ms": 200, "writers": 4, "read_period_ms": 100, "readers_per_level": 2}]
        self.next_id = 1
        self.digests = {"replica-0": "a", "replica-1": "a"}

    def init(self, did, value):
        self.lines.append({"t": "init", "did": did, "value": value, "ts": 0})
        return self

    def op(self, f, key, start, end, value, outcome="ok", p="p0", level=""):
        i = self.next_id
        self.next_id += 1
        is_read = f in ("read", "revstatus")
        invoke_value = value if not is_read else None
        if f == "revstatus":
            invoke_value, value = value
        self.lines.append({"t": "invoke", "id": i, "p": p, "f": f, "key": key, "value": invoke_value,
                           "level": level, "ts": start * 1000})
        if outcome is not None:
            self.lines.append({"t": outcome, "id": i, "value": value, "err": "", "ts": end * 1000})
        return self

    def write(self, did, start, end, value, outcome="ok"):
        return self.op("write", did, start, end, value, outcome, p="w")

    def read(self, did, start, end, value, level="tier2", p="r", outcome="ok"):
        return self.op("read", did, start, end, value, outcome, p=p, level=level)

    def final(self, did, at, value, level="tier2"):
        return self.op("read", did, at, at + 1, value, p="final", level=level)

    def check(self, nemesis=None):
        lines = self.lines + [{"t": "digests", "digests": self.digests, "ts": 10 ** 9},
                              {"t": "end", "ts": 10 ** 9}]
        with tempfile.TemporaryDirectory() as d:
            Path(d, "history.jsonl").write_text("\n".join(json.dumps(l) for l in lines) + "\n")
            if nemesis:
                Path(d, "nemesis.jsonl").write_text("\n".join(json.dumps(e) for e in nemesis) + "\n")
            return checker.check(d)


def kinds(result):
    return {v["kind"] for v in result["violations"]}


class Clean(unittest.TestCase):
    def test_sequential(self):
        r = (Run().init("d", "v0").read("d", 1, 2, "v0").write("d", 3, 4, "v1").read("d", 5, 6, "v1")
             .final("d", 100, "v1").check())
        self.assertEqual(r["violations"], [])
        self.assertEqual(r["unverifiable"], [])
        self.assertEqual(r["levels"]["tier2"]["dids_linearizable"], 1)
        self.assertEqual(r["convergence"], "converged")

    def test_read_overlapping_a_write_may_return_either(self):
        for seen in ("v0", "v1"):
            r = (Run().init("d", "v0").write("d", 10, 20, "v1").read("d", 12, 18, seen)
                 .final("d", 100, "v1").check())
            self.assertEqual(r["violations"], [], seen)

    def test_unknown_write_may_apply_later_or_never(self):
        for seen in ("v0", "v1"):
            r = (Run().init("d", "v0").write("d", 10, 20, "v1", outcome="info").read("d", 500, 501, seen)
                 .final("d", 600, seen).check())
            self.assertEqual(r["violations"], [], seen)

    def test_killed_client_write_with_no_completion_is_unknown(self):
        r = (Run().init("d", "v0").write("d", 10, 20, "v1", outcome=None).write("d", 30, 40, "v2", outcome=None)
             .final("d", 100, "v1").check())
        self.assertNotIn("lost-write", kinds(r))
        self.assertEqual(r["writes_unknown"], 2)


class Planted(unittest.TestCase):
    def test_lost_write(self):
        r = Run().init("d", "v0").write("d", 1, 2, "v1").write("d", 3, 4, "v2").final("d", 100, "v1").check()
        self.assertIn("lost-write", kinds(r))

    def test_phantom(self):
        r = Run().init("d", "v0").read("d", 1, 2, "nobody-wrote-this").final("d", 100, "v0").check()
        self.assertIn("phantom-read", kinds(r))

    def test_refused_write_visible(self):
        r = (Run().init("d", "v0").write("d", 1, 2, "v1", outcome="fail").read("d", 3, 4, "v1")
             .final("d", 100, "v0").check())
        self.assertIn("failed-visible", kinds(r))

    def test_stale_read_on_a_strong_level(self):
        r = (Run().init("d", "v0").write("d", 1, 2, "v1").read("d", 5, 6, "v0")
             .final("d", 100, "v1").check())
        self.assertIn("linearizability", kinds(r))
        self.assertEqual(r["levels"]["tier2"]["stale_reads"], 1)

    def test_read_of_the_future(self):
        r = (Run().init("d", "v0").read("d", 1, 2, "v1").write("d", 5, 6, "v1")
             .final("d", 100, "v1").check())
        self.assertIn("linearizability", kinds(r))

    def test_two_readers_seeing_new_then_old(self):
        r = (Run().init("d", "v0").write("d", 1, 100, "v1").read("d", 10, 11, "v1", p="a")
             .read("d", 20, 21, "v0", p="b").final("d", 200, "v1").check())
        self.assertIn("linearizability", kinds(r))

    def test_revocation_forgotten(self):
        r = (Run().init("d", "v0").write("d", 1, 2, "v1").final("d", 100, "v1")
             .op("revoke", "reg", 3, 4, "h1", p="rev")
             .op("revstatus", "reg", 5, 6, ("h1", "false"), p="rev").check())
        self.assertIn("revocation", kinds(r))

    def test_revocation_read_before_the_ack_is_allowed(self):
        r = (Run().init("d", "v0").write("d", 1, 2, "v1").final("d", 100, "v1")
             .op("revoke", "reg", 3, 40, "h1", p="rev")
             .op("revstatus", "reg", 5, 6, ("h1", "false"), p="rev").check())
        self.assertNotIn("revocation", kinds(r))

    def test_divergence(self):
        run = Run().init("d", "v0").write("d", 1, 2, "v1").final("d", 100, "v1")
        run.digests = {"replica-0": "a", "replica-1": "b"}
        self.assertIn("no-convergence", kinds(run.check()))

    def test_silent_replica_is_not_convergence(self):
        run = Run().init("d", "v0").write("d", 1, 2, "v1").final("d", 100, "v1")
        run.digests = {"replica-0": "no-answer", "replica-1": "no-answer"}
        self.assertIn("no-convergence", kinds(run.check()))


class Levels(unittest.TestCase):
    def test_stale_tier0_is_measured_not_a_violation(self):
        r = (Run(levels=("tier0", "tier2")).init("d", "v0").write("d", 1, 2, "v1")
             .read("d", 500, 501, "v0", level="tier0").final("d", 1000, "v1").check())
        self.assertEqual(kinds(r), set())
        t0 = r["levels"]["tier0"]
        self.assertEqual(t0["dids_not_linearizable"], 1)
        self.assertEqual(t0["stale_reads"], 1)
        self.assertAlmostEqual(t0["max_staleness_ms"], 498.0)

    def test_monotonic_is_per_reader(self):
        run = (Run(levels=("tier1",)).init("d", "v0").write("d", 1, 2, "v1").write("d", 3, 4, "v2"))
        run.read("d", 10, 11, "v2", level="tier1", p="a").read("d", 20, 21, "v1", level="tier1", p="a")
        run.read("d", 30, 31, "v0", level="tier1", p="b")
        r = run.final("d", 100, "v2", level="tier1").check()
        self.assertEqual(r["levels"]["tier1"]["monotonic_violations"], 1)


class CannotConclude(unittest.TestCase):
    def test_no_acknowledged_write(self):
        r = Run().init("d", "v0").final("d", 100, "v0").check()
        self.assertTrue(any("no write was acknowledged" in u for u in r["unverifiable"]))

    def test_client_never_finished(self):
        run = Run().init("d", "v0").write("d", 1, 2, "v1")
        with tempfile.TemporaryDirectory() as d:
            Path(d, "history.jsonl").write_text("\n".join(json.dumps(l) for l in run.lines) + "\n")
            r = checker.check(d)
        self.assertTrue(any("never reached" in u for u in r["unverifiable"]))
        self.assertTrue(any("convergence" in u for u in r["unverifiable"]))

    def test_final_read_that_never_answered(self):
        run = Run().init("d", "v0").write("d", 1, 2, "v1")
        run.op("read", "d", 100, 101, None, outcome="info", p="final", level="tier2")
        self.assertTrue(any("final read" in u for u in run.check()["unverifiable"]))

    def test_search_budget_is_unknown_not_a_pass(self):
        ops = [{"id": i, "f": "write", "value": f"w{i}", "start": 1, "end": checker.INFINITY,
                "outcome": "info"} for i in range(14)]
        ops.append({"id": 99, "f": "read", "value": "nothing", "start": 2, "end": 3, "outcome": "ok"})
        self.assertEqual(checker.linearizable("v0", ops, budget=50), (None, None))


class Availability(unittest.TestCase):
    def nemesis(self):
        return [{"event": "inject", "ts_us": 60_000_000, "scenario": "x", "system": "tailored"},
                {"event": "heal", "ts_us": 120_000_000}]

    def run_with_gap(self, resume_ms):
        run = Run().init("d", "v0")
        t = 0
        while t < 240_000:
            if not 60_000 <= t < resume_ms:
                run.write("d", t, t + 1, f"v{t}")
            t += 50
        return run.final("d", 241_000, f"v{t - 50}").check(self.nemesis())

    def test_gap_and_recovery(self):
        r = self.run_with_gap(130_000)
        self.assertAlmostEqual(r["longest_write_gap_s"], 70, delta=0.2)
        self.assertAlmostEqual(r["recovery_s"], 10, delta=1.1)
        self.assertEqual(set(r["phase_write_p99_ms"]), {"healthy", "fault", "healed"})

    def test_never_recovered_is_none_not_zero(self):
        r = self.run_with_gap(240_000)
        self.assertIsNone(r["recovery_s"])

    def partition(self, resume_ms):
        run = Run().init("d", "v0")
        t = 0
        while t < 240_000:
            if not 60_000 <= t < resume_ms:
                run.write("d", t, t + 1, f"v{t}")
            t += 50
        return run.final("d", 241_000, f"v{t - 50}").check(
            [{"event": "inject", "ts_us": 60_000_000, "scenario": "partition-2-2", "system": "tailored"},
             {"event": "net-state", "ts_us": 60_000_001, "scenario": "partition-2-2"},
             {"event": "heal", "ts_us": 120_000_000, "scenario": "partition-2-2"}])

    def test_partition_that_stopped_writes_counts(self):
        self.assertEqual(self.partition(120_000)["unverifiable"], [])

    def test_partition_that_writes_ignored_gives_no_verdict(self):
        r = self.partition(60_000)
        self.assertEqual(r["violations"], [])
        self.assertTrue(any("did not take effect" in u for u in r["unverifiable"]))


if __name__ == "__main__":
    unittest.main()
