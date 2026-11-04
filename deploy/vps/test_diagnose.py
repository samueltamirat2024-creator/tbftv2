#!/usr/bin/env python3
"""Checks diagnose.py finds a planted cause and is not fooled by decoys:  python3 test_diagnose.py"""
import random
import unittest

import diagnose
import stats

START = 1_000_000_000


def empty_log(**events):
    log = diagnose.parse_log("")
    log.update(events)
    return log


def fake_run(p99_by_sec, warmup=10, steady=120, replicas=None, generator=None, lags=None, reads=None):
    seconds = []
    for s, v in p99_by_sec.items():
        ops = {"register": {"n": 100, "measured": 100, "failed": 0, "p50": v // 2, "p99": v, "max": v}}
        if reads:
            ops["resolve"] = {"n": 100, "measured": 100, "failed": 0, "p50": 1, "p99": reads.get(s, 1000), "max": 1}
        seconds.append({"sec": s, "epoch_ms": START + s * 1000, "in_flight": 1,
                        "schedule_lag_us": (lags or {}).get(s, 0), "dispatch_lag_us": 0, "ops": ops})
    record = {"start_epoch_ms": START, "warmup_ms": warmup * 1000, "steady_ms": steady * 1000,
              "hist": stats_hist(p99_by_sec)}
    return diagnose.Run(seconds, record, replicas or {}, generator or empty_log())


def stats_hist(values):
    counts = {}
    for v in values.values():
        counts[v] = counts.get(v, 0) + 1
    return {"buckets": sorted([v, c] for v, c in counts.items()), "n": sum(counts.values())}


def event_at(sec, duration_us=50_000):
    return (START + sec * 1000 + 100, duration_us)


def planted(seed, period=11, spike=200_000, base=10_000):
    """Latency spikes when the snapshot runs, about every `period` seconds. Decoys run independently."""
    rng = random.Random(seed)
    total = 130
    snapshot_secs = [s for s in range(rng.randrange(period), total, period)]
    p99 = {s: base + rng.randrange(1000) for s in range(total)}
    for s in snapshot_secs:
        if rng.random() < 0.9:
            p99[s] = spike
    decoy = sorted(rng.sample(range(total), len(snapshot_secs)))
    replicas = {i: empty_log(snapshot=[event_at(s) for s in snapshot_secs],
                             gc=[event_at(s) for s in decoy]) for i in range(4)}
    return fake_run(p99, replicas=replicas)


class Parsing(unittest.TestCase):
    def test_real_lines_with_docker_prefix(self):
        text = """vdr-replica-0-1  | 2026-11-04T09:46:44.1Z SNAPSHOT replica=0 epoch_ms=1793785622585 duration_us=28780 bytes=672112
vdr-replica-0-1  | 2026-11-04T09:46:44.2Z MERKLE replica=0 epoch_ms=1793785618849 duration_us=3328 seq=215
vdr-replica-0-1  | 2026-11-04T09:46:44.3Z JVM-SAMPLE label=replica-0 epoch_ms=1793785604565 compile_ms=62 gc_ms=0 heap_used=5275944
vdr-replica-0-1  | 2026-11-04T09:46:44.4Z [2026-11-04T12:46:58.551+0300][info][gc       ] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 43M->10M(514M) 39.184ms
[2026-11-04T12:46:44.390+0300][info][gc] Using Serial
[2026-11-04T12:46:44.812+0300][info][safepoint] Safepoint "GenCollectForAllocation", Time since last: 7654750 ns, Total: 2331625 ns
"""
        log = diagnose.parse_log(text)
        self.assertEqual(log["snapshot"], [(1793785622585, 28780)])
        self.assertEqual(log["snapshots"][0][2], 672112)
        self.assertEqual(log["merkle"], [(1793785618849, 3328)])
        self.assertEqual(log["samples"], [(1793785604565, 62, 0)])
        self.assertEqual(len(log["gc"]), 1)
        self.assertEqual(log["gc"][0][1], 39184)
        self.assertEqual(log["collector"], "Serial")

    def test_gc_start_is_before_its_logged_end(self):
        line = "[2026-11-04T12:46:58.551+0300][info][gc] GC(1) Pause Full (Ergonomics) 43M->10M(514M) 2000.000ms\n"
        start, duration = diagnose.parse_log(line)["gc"][0]
        self.assertEqual(duration, 2_000_000)
        self.assertEqual(start, 1793785618551 - 2000)


class Attribution(unittest.TestCase):
    def test_planted_cause_coincides_and_decoy_does_not(self):
        runs = [planted(s) for s in range(10)]
        rows = diagnose.coincidence_rows(runs)
        self.assertTrue(rows["snapshot"]["coincides"], rows["snapshot"])
        self.assertFalse(rows["replica_gc"]["coincides"], rows["replica_gc"])

    def test_an_event_in_every_second_cannot_pass(self):
        # Hit rate 100%, and worthless: this is why the chance comparison exists.
        runs = []
        for seed in range(5):
            run = planted(seed)
            for log in run.replicas.values():
                log["merkle"] = [event_at(s) for s in range(130)]
            runs.append(run)
        rows = diagnose.coincidence_rows(runs)
        self.assertEqual(rows["merkle"]["rate"], 1.0)
        self.assertFalse(rows["merkle"]["coincides"], rows["merkle"])

    def test_short_events_are_ignored(self):
        run = planted(1)
        for log in run.replicas.values():
            log["snapshot"] = [(start, 400) for start, _ in log["snapshot"]]
        self.assertEqual(run.event_seconds("snapshot"), set())

    def test_events_too_short_to_explain_an_excursion_are_ignored(self):
        p99 = {s: 100_000 for s in range(130)}       # threshold 300 ms, so the minimum event is 75 ms
        run = fake_run(p99, replicas={0: empty_log(snapshot=[event_at(50, 74_000), event_at(70, 76_000)])})
        self.assertEqual(run.event_seconds("snapshot"), {70})

    def test_leader_events_are_replica_zeros(self):
        p99 = {s: 10_000 for s in range(130)}
        run = fake_run(p99, replicas={0: empty_log(snapshot=[event_at(20)]), 1: empty_log(snapshot=[event_at(40)])})
        self.assertEqual(run.event_seconds("leader_snapshot"), {20})
        self.assertEqual(run.event_seconds("snapshot"), {20, 40})

    def test_no_excursions_never_coincides(self):
        flat = {s: 10_000 for s in range(130)}
        rows = diagnose.coincidence_rows([fake_run(flat, replicas={0: empty_log(snapshot=[event_at(50)])})])
        self.assertFalse(rows["snapshot"]["coincides"])

    def test_false_positive_rate_on_independent_events(self):
        # Spikes and events with no link: the rule should almost never call this a cause.
        false_positives = 0
        trials = 40
        for t in range(trials):
            rng = random.Random(5000 + t)
            runs = []
            for _ in range(5):
                p99 = {s: 10_000 + rng.randrange(1000) for s in range(130)}
                for s in rng.sample(range(10, 130), 12):
                    p99[s] = 200_000
                events = [event_at(s) for s in rng.sample(range(130), 40)]
                runs.append(fake_run(p99, replicas={0: empty_log(snapshot=events)}))
            r = diagnose.coincidence(runs, "snapshot", shifts=300, seed=t)
            false_positives += r["rate"] >= diagnose.HIT_RATE and r["p"] < 0.05
        self.assertLessEqual(false_positives / trials, 0.10)


class RunMetrics(unittest.TestCase):
    def test_stall_length(self):
        p99 = {s: 2_000_000 for s in range(8)}
        p99.update({s: 10_000 for s in range(8, 130)})
        run = fake_run(p99)
        self.assertEqual(run.stall_seconds(), 8)
        self.assertEqual(run.stall_peak_us(), 2_000_000)

    def test_a_lone_spike_does_not_end_or_extend_the_stall(self):
        p99 = {s: 10_000 for s in range(130)}
        p99[60] = 900_000
        self.assertEqual(fake_run(p99).stall_seconds(), 0)

    def test_slope_recovers_planted_growth_through_spikes(self):
        rng = random.Random(3)
        p99 = {s: 10_000 + s * 100 for s in range(130)}      # 6 ms per minute
        for s in rng.sample(range(10, 130), 8):
            p99[s] = 900_000
        self.assertAlmostEqual(fake_run(p99).slope_ms_per_min(), 6.0, delta=0.3)

    def test_flat_series_has_no_slope(self):
        self.assertEqual(fake_run({s: 10_000 for s in range(130)}).slope_ms_per_min(), 0)

    def test_generator_lag_is_an_event(self):
        p99 = {s: 10_000 for s in range(130)}
        run = fake_run(p99, lags={40: 900_000, 41: 10})
        self.assertEqual(run.event_seconds("generator_lag"), {40})


class Verdicts(unittest.TestCase):
    def test_needs_both_halves(self):
        self.assertEqual(diagnose.verdict(True, True, True), "CONFIRMED")
        self.assertTrue(diagnose.verdict(True, True, False).startswith("NOT CONFIRMED"))
        self.assertTrue(diagnose.verdict(False, True, True).startswith("NOT CONFIRMED"))
        self.assertEqual(diagnose.verdict(False, True, False), "NOT SUPPORTED")
        self.assertTrue(diagnose.verdict(True, False, False).startswith("UNRESOLVED"))


if __name__ == "__main__":
    unittest.main()
