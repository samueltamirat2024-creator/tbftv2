#!/usr/bin/env python3
"""
Attribute the Tailored BFT VDR's write tail and start-up stall to a cause, by the rule fixed in
advance, with output from diagnose.sh:

    python3 diagnose.py results/diagnose/<stamp>      # writes DIAGNOSIS.md and overlay.tsv there
    python3 diagnose.py --single DIR --warmup-ms N    # one run's files in one folder; no verdicts

A cause is CONFIRMED only if both hold:

  1. Coincidence. Tail excursions fall near its events more often than chance. Checkpoints and GC
     are frequent enough that any spike sits near one, so the hit rate alone proves nothing: chance
     is measured by circularly shifting each run's events, which keeps their spacing and removes any
     link to the excursions. An event type present in nearly every second can never pass, so an
     event must last at least a quarter of the excursion threshold to count, and the leader's events
     are also tested alone: its stall is the one that holds up ordering.
  2. Intervention. Changing that cause and nothing else moves the metric, over independent
     repetitions: the 95% interval on the change must exclude no change AND the permutation test must
     hold after Holm correction. The interval alone is not enough: at small n a bootstrap interval
     excluded 1 for a checkpoint setting on the simulator, which has no such checkpoints.

Anything else is reported as what it is, not as a likely cause.

An excursion is a steady-window second whose write p99 exceeds max(3 x that run's median per-second
p99, median + 5 ms). Per-second percentiles come from TimeSeries's coarse buckets, which is enough to
locate a stall in time; magnitudes in the verdicts come from the full histograms.

Limits of what this can see: one slow replica need not delay a quorum, so a replica's event can
coincide with nothing and still be real for a different mix. Stalls during warm-up reach no reported
percentile, so they are judged on the length of the stall rather than on p99.
"""
import argparse
import csv
import json
import random
import re
import sys
from datetime import datetime
from pathlib import Path
from statistics import mean, median

import stats

K = 3.0
FLOOR_US = 5_000
MIN_EVENT_US = 1_000
MIN_EVENT_SHARE = 0.25
SHIFTS = 2_000
SEED = 20261104
SETTLE_S = 5
HIT_RATE = 0.5
WRITE_OPS = ("register", "update", "revoke")

SNAPSHOT = re.compile(r"\bSNAPSHOT replica=(\d+) epoch_ms=(\d+) duration_us=(\d+) bytes=(\d+)")
MERKLE = re.compile(r"\bMERKLE replica=(\d+) epoch_ms=(\d+) duration_us=(\d+)")
SAMPLE = re.compile(r"\bJVM-SAMPLE label=(\S+) epoch_ms=(\d+) compile_ms=(\d+) gc_ms=(\d+)")
GC_PAUSE = re.compile(r"\[(\d{4}-\d\d-\d\dT[\d:.]+[+-]\d{4})\]\[\w+\s*\]\[gc\s*\] GC\(\d+\) Pause .* ([\d.]+)ms\s*$")
COLLECTOR = re.compile(r"\]\[gc\s*\] Using (\S+)")

CAUSES = {
    "snapshot": "BFT-SMaRt snapshot, any replica",
    "leader_snapshot": "BFT-SMaRt snapshot, leader",
    "merkle": "Merkle checkpoint, any replica",
    "leader_merkle": "Merkle checkpoint, leader",
    "replica_gc": "GC pause, any replica",
    "leader_gc": "GC pause, leader",
    "generator_gc": "GC pause, generator",
    "generator_lag": "generator late issuing arrivals",
}
LEADER_KINDS = {"leader_snapshot": "snapshot", "leader_merkle": "merkle", "leader_gc": "gc"}
# Which configurations test which cause: (config, reference).
INTERVENTIONS = {
    "snapshot": [("cp5k", "baseline"), ("cp20k", "baseline")],
    "leader_snapshot": [("cp5k", "baseline"), ("cp20k", "baseline")],
    "merkle": [("merkle10k", "baseline")],
    "leader_merkle": [("merkle10k", "baseline")],
    "replica_gc": [("heap-young", "baseline"), ("epsilon", "baseline")],
    "leader_gc": [("heap-young", "baseline"), ("epsilon", "baseline")],
    "generator_gc": [],
    "generator_lag": [],
}
# The stall is compared in warm-up, where the two sides can be told apart.
STALL_INTERVENTIONS = {
    "replicas cold": ("warm-replicas", "baseline"),
    "generator cold": ("warm-both", "warm-replicas"),
}


# ------------------------------------------------------------------ logs
def parse_log(text, replica=None):
    """Events in one JVM's log: {kind: [(start_ms, duration_us)]}, plus compile samples and details."""
    out = {"snapshot": [], "merkle": [], "gc": [], "samples": [], "snapshots": [], "collector": None}
    for line in text.splitlines():
        m = SNAPSHOT.search(line)
        if m:
            start, duration, size = int(m[2]), int(m[3]), int(m[4])
            out["snapshot"].append((start, duration))
            out["snapshots"].append((start, duration, size))
            continue
        m = MERKLE.search(line)
        if m:
            out["merkle"].append((int(m[2]), int(m[3])))
            continue
        m = SAMPLE.search(line)
        if m:
            out["samples"].append((int(m[2]), int(m[3]), int(m[4])))
            continue
        m = GC_PAUSE.search(line)
        if m:
            end = datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000
            duration = float(m[2]) * 1000
            out["gc"].append((int(end - duration / 1000), int(duration)))
            continue
        m = COLLECTOR.search(line)
        if m:
            out["collector"] = m[1]
    return out


# ------------------------------------------------------------------ one run
class Run:
    def __init__(self, seconds, record, replicas, generator):
        self.seconds = seconds
        self.record = record
        self.replicas = replicas      # {replica id: parse_log result}
        self.generator = generator
        self.start_ms = record["start_epoch_ms"]
        self.warmup_s = record["warmup_ms"] // 1000
        self.steady_s = record["steady_ms"] // 1000
        self.write_p99 = {}
        self.read_p99 = {}
        self.lag = {}
        for s in seconds:
            writes = [o["p99"] for k, o in s["ops"].items() if k in WRITE_OPS]
            if writes:
                self.write_p99[s["sec"]] = max(writes)
            if "resolve" in s["ops"]:
                self.read_p99[s["sec"]] = s["ops"]["resolve"]["p99"]
            self.lag[s["sec"]] = s.get("schedule_lag_us", 0)
        self.window = list(range(self.warmup_s, self.warmup_s + self.steady_s))
        values = [self.write_p99[s] for s in self.window if s in self.write_p99]
        self.median_p99 = median(values) if values else 0
        self.threshold = max(K * self.median_p99, self.median_p99 + FLOOR_US)

    def excursions(self):
        return {s for s in self.window if self.write_p99.get(s, 0) > self.threshold}

    def stall_seconds(self):
        """Seconds from the first arrival until p99 first holds below the threshold for SETTLE_S."""
        ordered = sorted(self.write_p99)
        for i, s in enumerate(ordered):
            following = ordered[i:i + SETTLE_S]
            if len(following) == SETTLE_S and all(self.write_p99[x] <= self.threshold for x in following):
                return s
        return len(ordered)

    def stall_peak_us(self):
        end = self.stall_seconds()
        values = [v for s, v in self.write_p99.items() if s < end]
        return max(values) if values else 0

    def slope_ms_per_min(self):
        """Theil-Sen slope of per-second write p99 over the steady window: robust to the spikes."""
        points = [(s, self.write_p99[s]) for s in self.window if s in self.write_p99]
        slopes = [(b[1] - a[1]) / (b[0] - a[0]) for i, a in enumerate(points) for b in points[i + 1:]]
        return median(slopes) * 60 / 1000 if slopes else 0.0

    def event_seconds(self, cause):
        """Seconds an event of this cause touches. A delayed operation completes during or after it."""
        if cause == "generator_lag":
            limit = self.threshold / 2
            return {s for s, v in self.lag.items() if v > limit}
        events = []
        if cause in ("snapshot", "merkle"):
            for log in self.replicas.values():
                events += log[cause]
        elif cause == "replica_gc":
            for log in self.replicas.values():
                events += log["gc"]
        elif cause in LEADER_KINDS:
            if 0 in self.replicas:      # view 0's leader; fault-free runs do not change view
                events += self.replicas[0][LEADER_KINDS[cause]]
        elif cause == "generator_gc":
            events += self.generator["gc"]
        out = set()
        minimum = max(MIN_EVENT_US, self.threshold * MIN_EVENT_SHARE)
        for start, duration in events:
            if duration < minimum:
                continue
            first = (start - self.start_ms) // 1000
            last = (start + duration // 1000 - self.start_ms) // 1000
            out.update(range(first, last + 1))
        return out

    def has_events(self, cause):
        if cause == "generator_lag":
            return bool(self.lag)
        if cause == "generator_gc":
            return bool(self.generator["samples"] or self.generator["gc"])
        return bool(self.replicas)


def near(excursion, events):
    return excursion in events or excursion - 1 in events


def coincidence(runs, cause, shifts=SHIFTS, seed=SEED):
    """Hits, excursions, chance expectation and shift p-value, pooled over runs."""
    rng = random.Random(seed)
    data = []
    for run in runs:
        window = run.window
        if not window:
            continue
        low, size = window[0], len(window)
        # Inside the window only: start-up events would otherwise shift in and read as chance hits.
        events = {e - low for e in run.event_seconds(cause) if low - 1 <= e < low + size}
        data.append((sorted(x - low for x in run.excursions()), events, size))
    excursions = sum(len(d[0]) for d in data)
    observed = sum(1 for ex, ev, _ in data for x in ex if near(x, ev))
    if excursions == 0:
        return dict(hits=0, excursions=0, expected=0.0, p=1.0, rate=0.0)
    at_least = 0
    total = 0
    for _ in range(shifts):
        hits = 0
        for ex, ev, size in data:
            offset = rng.randrange(size)
            shifted = {(e + offset) % size for e in ev}
            hits += sum(1 for x in ex if near(x, shifted))
        total += hits
        at_least += hits >= observed
    return dict(hits=observed, excursions=excursions, expected=total / shifts,
                p=(at_least + 1) / (shifts + 1), rate=observed / excursions)


# ------------------------------------------------------------------ loading
def load_rep(rep_dir, replica_dir):
    record = stats.load_runs(Path(rep_dir) / "runs.jsonl")[0]
    with open(Path(rep_dir) / "timeseries.jsonl", encoding="utf-8") as f:
        seconds = [json.loads(line) for line in f if line.strip()]
    replicas = {}
    for path in sorted(Path(replica_dir).glob("vdr-replica-*.log")):
        replicas[int(path.stem.rsplit("-", 1)[1])] = parse_log(path.read_text(errors="replace"))
    bench = Path(rep_dir) / "bench.log"
    generator = parse_log(bench.read_text(errors="replace")) if bench.is_file() else parse_log("")
    return Run(seconds, record, replicas, generator)


def load_config(table_dir):
    """{(mix, level): [Run]} for one configuration's table folder."""
    table = Path(table_dir)
    out = {}
    root = table / "reps" / "tailored"
    for rep in sorted(root.glob("*/*/rep-*")):
        mix, level = rep.parent.parent.name, int(rep.parent.name)
        k = rep.name.split("-")[1]
        logs = table / "replica-logs" / f"tailored-{mix}-{level}-rep{k}"
        out.setdefault((mix, level), []).append(load_rep(rep, logs))
    return out


# ------------------------------------------------------------------ comparisons
def difference_stat(a, b):
    def stat(weights):
        wa, wb = weights
        return sum(w * v for w, v in zip(wa, a)) / sum(wa) - sum(w * v for w, v in zip(wb, b)) / sum(wb)
    return stat


def compare_p99(arm, ref):
    hists_a = [r.record["hist"] for r in arm]
    hists_b = [r.record["hist"] for r in ref]
    weights_a, weights_b = [1] * len(arm), [1] * len(ref)
    a = stats.MergedPercentile(hists_a).percentile(99, weights_a)
    b = stats.MergedPercentile(hists_b).percentile(99, weights_b)
    low, high = stats.bca(stats.percentile_ratio_stat(hists_a, hists_b, 99), [len(arm), len(ref)])
    return dict(a_ms=a / 1000, b_ms=b / 1000, ratio=a / b if b else float("inf"), low=low, high=high,
                p=stats.permutation_p([stats.run_percentile(h, 99) for h in hists_a],
                                      [stats.run_percentile(h, 99) for h in hists_b]))


def compare_values(a, b):
    low, high = stats.bca(difference_stat(a, b), [len(a), len(b)])
    return dict(a=mean(a), b=mean(b), diff=mean(a) - mean(b), low=low, high=high,
                p=stats.permutation_p(a, b))


def verdict(coincides, measured, effect):
    """The rule in the module docstring, as a table."""
    if not measured:
        return "UNRESOLVED: no intervention measured" + (" (it does coincide)" if coincides else "")
    if coincides and effect:
        return "CONFIRMED"
    if coincides:
        return "NOT CONFIRMED: coincides, but changing it did not help"
    if effect:
        return "NOT CONFIRMED: changing it helped, but excursions do not coincide"
    return "NOT SUPPORTED"


# ------------------------------------------------------------------ reports
def coincidence_rows(runs):
    rows = {c: coincidence(runs, c) for c in CAUSES if any(r.has_events(c) for r in runs)}
    names = list(rows)
    for name, adjusted in zip(names, stats.holm([rows[n]["p"] for n in names])):
        rows[name]["holm"] = adjusted
        rows[name]["coincides"] = (rows[name]["excursions"] > 0 and rows[name]["rate"] >= HIT_RATE
                                   and adjusted < 0.05)
    return rows


def coincidence_table(rows):
    out = ["| Cause | Excursions near an event | Expected by chance | Holm p | Coincides? |",
           "|---|---|---|---|---|"]
    for cause, r in rows.items():
        out.append(f"| {CAUSES[cause]} | {r['hits']} of {r['excursions']} | {r['expected']:.1f} "
                   f"| {r['holm']:.3g} | {'yes' if r['coincides'] else 'no'} |")
    return out


def snapshot_growth(runs):
    """Snapshot cost against state size: the mechanism for a tail that grows with state."""
    out = []
    for run in runs:
        for log in run.replicas.values():
            points = [p for p in log["snapshots"] if p[2] > 1 << 16]
            if len(points) >= 2:
                out.append((points[0], points[-1], len(points)))
    if not out:
        return []
    return ["| Replica-run count | Snapshots per replica | First: bytes, ms | Last: bytes, ms |", "|---|---|---|---|",
            f"| {len(out)} | {mean(o[2] for o in out):.0f} "
            f"| {mean(o[0][2] for o in out) / 1e6:.1f} MB, {mean(o[0][1] for o in out) / 1000:.1f} ms "
            f"| {mean(o[1][2] for o in out) / 1e6:.1f} MB, {mean(o[1][1] for o in out) / 1000:.1f} ms |"]


def run_summary(runs):
    slopes = [r.slope_ms_per_min() for r in runs]
    stalls = [r.stall_seconds() for r in runs]
    peaks = [r.stall_peak_us() / 1000 for r in runs]
    out = [f"- Start-up stall: {mean(stalls):.1f} s on average (per run: {sorted(stalls)}), peak write "
           f"p99 {mean(peaks):.0f} ms on average."]
    if len(runs) > 1:
        low, high = stats.bca(stats.mean_stat(slopes), [len(slopes)])
        out.append(f"- Steady-window p99 slope: {mean(slopes):+.2f} ms per minute, 95% interval "
                   f"[{low:+.2f}, {high:+.2f}].")
    else:
        out.append(f"- Steady-window p99 slope: {slopes[0]:+.2f} ms per minute.")
    out.append(f"- Median per-second write p99: {mean(r.median_p99 for r in runs) / 1000:.1f} ms; excursions: "
               f"{sum(len(r.excursions()) for r in runs)} of {sum(len(r.window) for r in runs)} seconds.")
    if any(r.read_p99 for r in runs):
        # The built-in control: reads come from a volatile checkpoint, not under the store's lock.
        hit = total = 0
        for r in runs:
            for s in r.excursions():
                if s in r.read_p99:
                    total += 1
                    reads = [r.read_p99[x] for x in r.window if x in r.read_p99]
                    hit += r.read_p99[s] > K * median(reads)
        out.append(f"- Read control: reads also spiked in {hit} of {total} write-excursion seconds. A cause "
                   "that blocks only ordered execution should leave reads alone.")
    collectors = {log["collector"] for r in runs for log in r.replicas.values() if log["collector"]}
    if collectors:
        out.append(f"- Replica collector: {', '.join(sorted(collectors))}.")
    return out


def overlay(path, runs_by_label):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        causes = list(CAUSES)
        w.writerow(["label", "run", "sec", "epoch_ms", "write_p99_us", "read_p99_us", "excursion",
                    "schedule_lag_us", *causes])
        for label, runs in runs_by_label.items():
            for i, run in enumerate(runs):
                events = {c: run.event_seconds(c) for c in causes}
                excursions = run.excursions()
                for s in sorted(run.write_p99):
                    w.writerow([label, i, s, run.start_ms + s * 1000, run.write_p99.get(s, ""),
                                run.read_p99.get(s, ""), int(s in excursions), run.lag.get(s, ""),
                                *[int(s in events[c]) for c in causes]])


def report(directory):
    directory = Path(directory)
    manifest = directory / "configs.tsv"
    base = Path(__file__).resolve().parent
    configs = {}
    with open(manifest, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            table = Path(row["table_dir"])
            configs[row["config"]] = load_config(table if table.is_absolute() else base / table)
    if "baseline" not in configs:
        print("no baseline configuration in the manifest; nothing to compare against", file=sys.stderr)
        return 2

    out = []
    problems = []
    for name, levels in configs.items():
        for key, runs in levels.items():
            if name != "soak" and len(runs) < stats.PROTOCOL_RUNS:
                problems.append(f"{name} {key[0]} @ {key[1]}: {len(runs)} repetitions, below {stats.PROTOCOL_RUNS}")
    if problems:
        out += ["# NOT CONCLUSIVE: pilot data", ""] + [f"- {p}" for p in problems] + [""]
    out += ["# Write tail and start-up stall: diagnosis", ""]

    baseline = configs["baseline"]
    for key, runs in sorted(baseline.items()):
        mix, level = key
        rows = coincidence_rows(runs)
        out += [f"## {mix} @ {level} ops/s", "", "### What the baseline shows", ""]
        out += run_summary(runs) + ["", "### Do excursions coincide with events?", ""]
        out += coincidence_table(rows) + [""]
        out += snapshot_growth(runs) + [""]

        out += ["### Interventions (merged p99 against baseline)", "",
                "| Configuration | Arm p99 (ms) | Baseline p99 (ms) | Ratio [95% BCa] | p | Holm p | Helped? |",
                "|---|---|---|---|---|---|---|"]
        # One test per configuration, however many causes it bears on.
        compared = {}
        for tests in INTERVENTIONS.values():
            for config, reference in tests:
                arm, ref = configs.get(config, {}).get(key), configs.get(reference, {}).get(key)
                if config not in compared and arm and ref and len(arm) >= 2 and len(ref) >= 2:
                    compared[config] = compare_p99(arm, ref)
        names = list(compared)
        for name, adjusted in zip(names, stats.holm([compared[n]["p"] for n in names])):
            c = compared[name]
            c["helped"] = c["high"] < 1.0 and adjusted < 0.05
            out.append(f"| {name} | {c['a_ms']:.1f} | {c['b_ms']:.1f} "
                       f"| {c['ratio']:.3g} [{c['low']:.3g}, {c['high']:.3g}] | {c['p']:.2g} | {adjusted:.2g} "
                       f"| {'yes' if c['helped'] else 'no'} |")
        effects = {}
        for cause, tests in INTERVENTIONS.items():
            for config, _ in tests:
                if config in compared:
                    effects.setdefault(cause, []).append(compared[config]["helped"])
        out += ["", "### Verdicts: write tail", "", "| Cause | Verdict |", "|---|---|"]
        for cause in rows:
            out.append(f"| {CAUSES[cause]} | "
                       f"{verdict(rows[cause]['coincides'], cause in effects, any(effects.get(cause, [])))} |")
        out += ["", "### Verdicts: start-up stall", "",
                "| Cause | Comparison | Stall (s) | Reference (s) | Change [95% BCa] | p | Verdict |", "|---|---|---|---|---|---|---|"]
        for cause, (config, reference) in STALL_INTERVENTIONS.items():
            arm, ref = configs.get(config, {}).get(key), configs.get(reference, {}).get(key)
            if not arm or not ref or len(arm) < 2 or len(ref) < 2:
                out.append(f"| {cause} | {config} vs {reference} | | | | | UNRESOLVED: not measured |")
                continue
            c = compare_values([r.stall_seconds() for r in arm], [r.stall_seconds() for r in ref])
            text = "CONFIRMED" if c["high"] < 0 and c["p"] < 0.025 else "NOT SUPPORTED"   # two tests: Bonferroni
            out.append(f"| {cause} | {config} vs {reference} | {c['a']:.1f} | {c['b']:.1f} "
                       f"| {c['diff']:+.1f} [{c['low']:+.1f}, {c['high']:+.1f}] | {c['p']:.2g} | {text} |")
        out.append("")

    soak = configs.get("soak", {})
    for key, runs in sorted(soak.items()):
        out += [f"## Soak: {key[0]} @ {key[1]} ops/s, {runs[0].steady_s} s window", ""]
        out += run_summary(runs) + [""] + snapshot_growth(runs) + [""]
        out += coincidence_table(coincidence_rows(runs)) + [""]

    (directory / "DIAGNOSIS.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    overlay(directory / "overlay.tsv", {f"{m}@{l}": r for (m, l), r in baseline.items()})
    print(f"wrote {directory / 'DIAGNOSIS.md'} and overlay.tsv")
    return 4 if problems else 0


def single(directory, warmup_ms):
    run = load_rep(directory, directory)
    if warmup_ms is not None:
        assert run.warmup_s == warmup_ms // 1000
    print("\n".join(run_summary([run]) + [""] + coincidence_table(coincidence_rows([run])) + [""]
                    + snapshot_growth([run])))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("directory")
    ap.add_argument("--single", action="store_true", help="the folder holds one run's files")
    ap.add_argument("--warmup-ms", type=int)
    a = ap.parse_args()
    sys.exit(single(Path(a.directory), a.warmup_ms) if a.single else report(a.directory))


if __name__ == "__main__":
    main()
