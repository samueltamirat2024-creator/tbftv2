#!/usr/bin/env python3
"""
Checks stats.py against answers known in advance. Run it before trusting an interval:

    python3 test_stats.py            # about two minutes; set STATS_QUICK=1 for fewer trials
"""
import json
import math
import os
import random
import tempfile
import unittest
from pathlib import Path
from statistics import mean

import stats

QUICK = os.environ.get("STATS_QUICK") == "1"


def hist(samples):
    counts = {}
    for s in samples:
        counts[s] = counts.get(s, 0) + 1
    return {"n": len(samples), "sum": sum(samples), "max": max(samples or [0]), "early": 0,
            "buckets": sorted([v, c] for v, c in counts.items())}


class Percentiles(unittest.TestCase):
    def test_matches_the_java_rule(self):
        # Histogram.percentile: the first value whose running count reaches ceil(p% of N).
        h = hist(list(range(1, 101)))
        index = stats.MergedPercentile([h])
        self.assertEqual(index.percentile(50, [1]), 50)
        self.assertEqual(index.percentile(99, [1]), 99)
        self.assertEqual(index.percentile(100, [1]), 100)

    def test_merging_is_not_averaging(self):
        fast, slow = hist([10] * 99), hist([1000] * 1)
        self.assertEqual(stats.MergedPercentile([fast, slow]).percentile(99, [1, 1]), 10)
        self.assertEqual(stats.MergedPercentile([fast, slow]).percentile(100, [1, 1]), 1000)

    def test_weights_match_a_real_merge(self):
        a, b = list(range(1, 50)), list(range(30, 200))
        direct = stats.MergedPercentile([hist(a + a + a + b)]).percentile(95, [1])
        self.assertEqual(stats.MergedPercentile([hist(a), hist(b)]).percentile(95, [3, 1]), direct)

    def test_weight_zero_drops_a_run(self):
        index = stats.MergedPercentile([hist([5]), hist([900])])
        self.assertEqual(index.percentile(100, [1, 0]), 5)


class Bootstrap(unittest.TestCase):
    def coverage(self, draw, truth, trials, n=10):
        hits = 0
        for t in range(trials):
            rng = random.Random(1000 + t)
            values = [draw(rng) for _ in range(n)]
            low, high = stats.bca(stats.mean_stat(values), [n], resamples=1000, seed=t)
            hits += low <= truth <= high
        return hits / trials

    def test_coverage_normal(self):
        got = self.coverage(lambda r: r.gauss(100, 10), 100, 200 if QUICK else 1000)
        # Plain BCa measured 0.904 here; the expansion measures 0.939 over 1500 trials.
        self.assertGreater(got, 0.90 if QUICK else 0.92)
        self.assertLessEqual(got, 0.98)

    def test_coverage_skewed(self):
        got = self.coverage(lambda r: r.lognormvariate(0, 0.5), math.exp(0.125), 200 if QUICK else 1000)
        # Plain BCa measured 0.878; the expansion measures 0.931.
        self.assertGreater(got, 0.88 if QUICK else 0.91)

    def test_coverage_merged_p99_is_what_the_report_claims(self):
        # Between-run spread is what limits this, so it is tested with a spread real clusters show.
        # Measured over 300 trials: 0.900 at sd 0.15, 0.857 at sd 0.3. STATS.md quotes these.
        def one_run(r, sd, ops):
            level = math.exp(r.gauss(0, sd))
            return [int(level * r.lognormvariate(math.log(5000), 0.6)) >> 5 << 5 for _ in range(ops)]
        r = random.Random(99)
        big = sorted(v for _ in range(1000) for v in one_run(r, 0.15, 300))
        truth = big[math.ceil(0.99 * len(big)) - 1]
        trials = 60 if QUICK else 200
        hits = 0
        for t in range(trials):
            r = random.Random(10000 + t)
            hists = [hist(one_run(r, 0.15, 2000)) for _ in range(10)]
            low, high = stats.bca(stats.merged_stat(hists, 99), [10], resamples=400, seed=t)
            hits += low <= truth <= high
        self.assertGreater(hits / trials, 0.78 if QUICK else 0.84)

    def test_very_small_samples_do_not_crash(self):
        # A pilot with two repetitions reaches this after its measurements have finished.
        for values in ([1.0, 2.0], [1.0, 1.0, 9.0], [5.0, 900.0]):
            low, high = stats.bca(stats.mean_stat(values), [len(values)], resamples=500)
            self.assertLessEqual(low, high)
        low, high = stats.bca(stats.ratio_stat([1.0, 2.0], [3.0, 4.0]), [2, 2], resamples=500)
        self.assertLessEqual(low, high)

    def test_identical_runs_give_a_point(self):
        self.assertEqual(stats.bca(stats.mean_stat([5.0] * 10), [10], resamples=200), (5.0, 5.0))

    def test_interval_contains_estimate_for_skew(self):
        values = [1, 1, 1, 1, 1, 1, 1, 1, 1, 50.0]
        low, high = stats.bca(stats.mean_stat(values), [10], resamples=2000)
        self.assertLess(low, mean(values))
        self.assertGreater(high, mean(values))

    def test_ratio_interval_two_groups(self):
        rng = random.Random(7)
        a = [rng.gauss(200, 5) for _ in range(10)]
        b = [rng.gauss(100, 5) for _ in range(10)]
        low, high = stats.bca(stats.ratio_stat(a, b), [10, 10], resamples=2000)
        self.assertLess(low, 2.0)
        self.assertGreater(high, 2.0)

    def test_t_halfwidth_at_ten_runs(self):
        values = [1.0, 2.0] * 5
        self.assertAlmostEqual(stats.t_halfwidth(values), 2.262 * 0.5270 / math.sqrt(10), places=3)


class Tests(unittest.TestCase):
    def test_complete_separation_is_the_exact_minimum(self):
        p = stats.permutation_p(list(range(10)), list(range(100, 110)))
        self.assertAlmostEqual(p, 2 / math.comb(20, 10))

    def test_identical_groups_give_one(self):
        self.assertEqual(stats.permutation_p([3.0] * 5, [3.0] * 5), 1.0)

    def test_small_exact_case_by_hand(self):
        # {1,2} vs {3,4}: of 6 arrangements, only the two complete separations are as extreme.
        self.assertAlmostEqual(stats.permutation_p([1, 2], [3, 4]), 2 / 6)

    def test_uniform_under_the_null(self):
        trials = 100 if QUICK else 400
        rejected = 0
        below_half = 0
        for t in range(trials):
            rng = random.Random(t)
            p = stats.permutation_p([rng.gauss(0, 1) for _ in range(6)], [rng.gauss(0, 1) for _ in range(6)])
            rejected += p <= 0.05
            below_half += p <= 0.5
        self.assertLess(rejected / trials, 0.09)
        self.assertGreater(below_half / trials, 0.40)
        self.assertLess(below_half / trials, 0.60)

    def test_detects_a_real_difference(self):
        rng = random.Random(1)
        p = stats.permutation_p([rng.gauss(0, 1) for _ in range(10)], [rng.gauss(3, 1) for _ in range(10)])
        self.assertLess(p, 0.01)

    def test_monte_carlo_fallback_agrees(self):
        a, b = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0], [2.5, 3.5, 4.5, 5.5, 6.5, 7.5]
        self.assertAlmostEqual(stats.permutation_p(a, b), stats.permutation_p(a, b, limit=0), delta=0.02)

    def test_holm_textbook(self):
        got = stats.holm([0.01, 0.04, 0.03])
        self.assertAlmostEqual(got[0], 0.03)
        self.assertAlmostEqual(got[1], 0.06)
        self.assertAlmostEqual(got[2], 0.06)

    def test_cliffs_delta(self):
        self.assertEqual(stats.cliffs_delta([2, 3], [0, 1]), 1)
        self.assertEqual(stats.cliffs_delta([0, 1], [2, 3]), -1)
        self.assertEqual(stats.cliffs_delta([1, 2], [1, 2]), 0)


def run(mix="update", rate=50, run_index=0, tput=50.0, samples=None, **extra):
    samples = samples or [1000, 2000, 3000]
    h = hist(samples)
    empty = hist([])
    record = {"name": f"X -- {mix} @ {rate} ops/s", "mix": mix, "rate": rate, "run": run_index, "n": 4,
              "start_epoch_ms": 0, "warmup_ms": 1000, "steady_ms": 2000, "throughput": tput, "failures": 0,
              "overloaded": False, "valid": True, "invalid_reason": "", "contended": 0, "tier0_reads": -1,
              "tier0_fallbacks": -1, "burst_size": 0, "burst_drain_ms": 0.0, "burst_failed": 0,
              "burst_txns": -1, "burst_confirmed": -1, "hist": h, "burst_hist": empty,
              "ops": {op: {"tput": tput if op == "update" else 0.0, "hist": h if op == "update" else empty}
                      for op in stats.OPS}}
    record.update(extra)
    return record


class Commands(unittest.TestCase):
    def write_reps(self, root, runs):
        for i, r in enumerate(runs):
            d = Path(root) / f"rep-{i}"
            d.mkdir(parents=True)
            (d / "runs.jsonl").write_text(json.dumps(r) + "\n")

    def test_independence_needs_one_run_per_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_reps(tmp, [run(), run()])
            self.assertTrue(stats.independent(stats.load_runs(tmp)))
            shared = Path(tmp) / "shared.jsonl"
            shared.write_text(json.dumps(run()) + "\n" + json.dumps(run()) + "\n")
            self.assertFalse(stats.independent(stats.load_runs(shared)))

    def test_row_has_every_column_and_the_merged_percentile(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_reps(tmp, [run(samples=[1000] * 99 + [9000]) for _ in range(3)])
            row = stats.build_row(stats.load_runs(tmp), tmp, True, "")
            self.assertEqual(set(row), set(stats.base_columns()))
            self.assertEqual(row["p99_us"], "1000")
            self.assertEqual(row["runs"], 3)
            self.assertEqual(row["independent_runs"], 1)
            self.assertTrue(row["notes"].startswith("sustained; offered 50 ops/s, 3 runs"))

    def test_invalid_runs_are_excluded_and_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_reps(tmp, [run(), run(), run(valid=False, invalid_reason="3 of 800 failed")])
            row = stats.build_row(stats.load_runs(tmp), tmp, False, "")
            self.assertEqual(row["runs"], 2)
            self.assertEqual(row["invalid_runs"], 1)
            self.assertIn("3 of 800 failed", row["notes"])

    def test_overload_floors_the_reported_p99(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_reps(tmp, [run(overloaded=True), run()])
            row = stats.build_row(stats.load_runs(tmp), tmp, True, "")
            self.assertEqual(row["p99_us"], str(2 * stats.P99_TARGET_US))
            self.assertIn("OVERLOADED in 1 of 2", row["notes"])
            self.assertIn("SATURATED", row["notes"])


if __name__ == "__main__":
    unittest.main()
