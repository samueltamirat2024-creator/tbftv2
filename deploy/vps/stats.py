#!/usr/bin/env python3
"""
Run-level statistics over the runs.jsonl files vdr.bench.Bench writes.

    python3 stats.py check-p99 REPS_DIR               # merged p99 of one level; exit 3 if past 500 ms
    python3 stats.py rows REPS_DIR --equal-load ...   # one rows.tsv row, for make-table.py
    python3 stats.py report --arm indy=DIR --arm tailored=DIR --ref indy --out STATS.md

The sample is the RUN, because runs are what get repeated: operations inside a run share a cluster,
so resampling them would treat thousands of correlated latencies as independent and report absurdly
narrow intervals. Runs are only independent if each has its own cluster and generator JVM, which is
what REPEAT_FRESH=1 in measure-table.sh provides. Runs that shared a cluster are flagged.

Intervals are 95% expanded BCa bootstrap (10,000 resamples, seeded). For a percentile, each resample
merges its runs' histograms and reads the percentile, the same rule the reported figure follows. The
expansion corrects plain BCa's undercoverage at n = 10; test_stats.py measures the coverage achieved.
Throughput also shows a t interval as a cross-check.

Comparisons use a two-sided permutation test of the difference in per-run means (exact when the
arrangements can be enumerated), with Holm correction across every test in the report. Percentile
tests compare per-run percentiles, since a merged percentile has no per-run value to permute.

Python stdlib only, as with the rest of deploy/vps.
"""
import argparse
import bisect
import csv
import itertools
import json
import math
import random
import re
import sys
from pathlib import Path
from statistics import NormalDist, mean, stdev

OPS = ["resolve", "register", "update", "revoke"]
P99_TARGET_US = 500_000
PROTOCOL_RUNS = 10
RESAMPLES = 10_000
EXACT_LIMIT = 200_000
SEED = 20261104
ND = NormalDist()
T95 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160,
       2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056,
       2.052, 2.048, 2.045, 2.042]


# ------------------------------------------------------------------ histograms
class MergedPercentile:
    """Percentiles of any weighted merge of per-run histograms, without re-merging each time.

    A resample is a list of run weights. Every run's cumulative counts are aligned on one shared
    value axis, so a percentile costs a binary search over the axis rather than a merge and a sort.
    """

    def __init__(self, hists):
        self.values = sorted({v for h in hists for v, _ in h["buckets"]})
        self.cum = []
        for h in hists:
            counts = dict((v, c) for v, c in h["buckets"])
            total, line = 0, []
            for v in self.values:
                total += counts.get(v, 0)
                line.append(total)
            self.cum.append(line)

    def percentile(self, p, weights):
        """Same rule as Histogram.percentile: first bucket whose running count reaches ceil(p% N)."""
        if not self.values:
            return 0.0
        active = [(w, line) for w, line in zip(weights, self.cum) if w]
        total = sum(w * line[-1] for w, line in active)
        if total == 0:
            return 0.0
        target = math.ceil(p / 100.0 * total)
        lo, hi = 0, len(self.values) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if sum(w * line[mid] for w, line in active) >= target:
                hi = mid
            else:
                lo = mid + 1
        return float(self.values[lo])


def run_percentile(hist, p):
    return MergedPercentile([hist]).percentile(p, [1])


# ------------------------------------------------------------------ bootstrap
def bca(stat, sizes, resamples=RESAMPLES, seed=SEED, alpha=0.05):
    """BCa interval for stat(weights_per_group), resampling within each group.

    stat takes one list of integer run weights per group. Returns (low, high), or the estimate
    twice if every resample gave the same value, for example when all runs are identical.
    """
    rng = random.Random(seed)
    full = [[1] * n for n in sizes]
    theta = stat(full)
    boots = []
    for _ in range(resamples):
        weights = []
        for n in sizes:
            w = [0] * n
            for _ in range(n):
                w[rng.randrange(n)] += 1
            weights.append(w)
        boots.append(stat(weights))
    boots.sort()
    if boots[0] == boots[-1]:
        return theta, theta

    below = bisect.bisect_left(boots, theta)
    ties = bisect.bisect_right(boots, theta) - below
    proportion = (below + ties / 2) / resamples
    proportion = min(max(proportion, 1 / (2 * resamples)), 1 - 1 / (2 * resamples))
    z0 = ND.inv_cdf(proportion)

    # Acceleration from the jackknife, group by group, so two-sample ratios are handled.
    numerator = denominator = 0.0
    for g, n in enumerate(sizes):
        if n < 2:
            continue
        leave = []
        for i in range(n):
            weights = [list(w) for w in full]
            weights[g][i] = 0
            leave.append(stat(weights))
        centre = sum(leave) / n
        diffs = [(centre - v) / n for v in leave]     # scaled so unequal groups combine correctly
        numerator += sum(d ** 3 for d in diffs)
        denominator += sum(d ** 2 for d in diffs)
    accel = numerator / (6 * denominator ** 1.5) if denominator > 0 else 0.0

    # Plain BCa covers about 90% at n = 10 for a nominal 95% (test_stats.py measures it). Hesterberg's
    # expansion widens the tail by the small-sample gap between normal and t quantiles.
    effective = min(sizes)
    if effective >= 2 and alpha == 0.05:
        t = T95[effective - 2] if effective - 2 < len(T95) else 1.96
        alpha = 2 * ND.cdf(-math.sqrt(effective / (effective - 1)) * t)

    def endpoint(q):
        z = ND.inv_cdf(q)
        adjusted = ND.cdf(z0 + (z0 + z) / (1 - accel * (z0 + z)))
        index = min(max(int(adjusted * resamples), 0), resamples - 1)
        return boots[index]

    return endpoint(alpha / 2), endpoint(1 - alpha / 2)


def t_halfwidth(values):
    n = len(values)
    if n < 2:
        return 0.0
    t = T95[n - 2] if n - 2 < len(T95) else 1.96
    return t * stdev(values) / math.sqrt(n)


# ------------------------------------------------------------------ tests
def permutation_p(a, b, limit=EXACT_LIMIT, seed=SEED):
    """Two-sided p for the difference in means. Monte Carlo above limit, with the +1 correction."""
    pooled = list(a) + list(b)
    total, na, nb = sum(pooled), len(a), len(b)
    observed = abs(sum(a) / na - sum(b) / nb)
    tolerance = 1e-9 * max(1.0, abs(total))

    def extreme(subset_sum):
        return abs(subset_sum / na - (total - subset_sum) / nb) >= observed - tolerance

    arrangements = math.comb(na + nb, na)
    if arrangements <= limit:
        hits = sum(extreme(sum(c)) for c in itertools.combinations(pooled, na))
        return hits / arrangements
    rng = random.Random(seed)
    draws = 100_000
    hits = 0
    for _ in range(draws):
        hits += extreme(sum(rng.sample(pooled, na)))
    return (hits + 1) / (draws + 1)


def holm(pvalues):
    """Holm-adjusted p-values, in the input order."""
    order = sorted(range(len(pvalues)), key=lambda i: pvalues[i])
    adjusted = [0.0] * len(pvalues)
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, min(1.0, (len(pvalues) - rank) * pvalues[i]))
        adjusted[i] = running
    return adjusted


def cliffs_delta(a, b):
    """P(a > b) - P(a < b): an effect size that does not depend on sample size."""
    greater = sum(1 for x in a for y in b if x > y)
    lesser = sum(1 for x in a for y in b if x < y)
    return (greater - lesser) / (len(a) * len(b))


# ------------------------------------------------------------------ loading
def load_runs(root):
    """Every run under root, or in root if it is one file. A file that does not parse is an error."""
    root = Path(root)
    files = [root] if root.is_file() else sorted(root.rglob("runs.jsonl"))
    runs = []
    for f in files:
        with open(f, encoding="utf-8") as lines:
            for number, line in enumerate(lines, 1):
                if not line.strip():
                    continue
                run = json.loads(line)
                run["_source"] = f"{f}:{number}"
                run["_file"] = str(f)
                runs.append(run)
    return runs


def by_level(runs):
    out = {}
    for r in runs:
        out.setdefault((r["mix"], r["rate"]), []).append(r)
    return out


def independent(runs):
    """True if no two runs came out of one invocation, i.e. one cluster and generator JVM."""
    files = [r["_file"] for r in runs]
    return len(set(files)) == len(files)


def split_valid(runs):
    return [r for r in runs if r["valid"]], [r for r in runs if not r["valid"]]


# ------------------------------------------------------------------ metrics
def merged_stat(hists, p):
    index = MergedPercentile(hists)
    return lambda weights: index.percentile(p, weights[0])


def mean_stat(values):
    return lambda weights: sum(w * v for w, v in zip(weights[0], values)) / sum(weights[0])


def summarise_percentile(runs, p, key="hist"):
    hists = [r[key] for r in runs]
    est = MergedPercentile(hists).percentile(p, [1] * len(hists))
    low, high = bca(merged_stat(hists, p), [len(hists)]) if len(hists) > 1 else (est, est)
    return est, low, high


def summarise_mean(values):
    est = mean(values)
    low, high = bca(mean_stat(values), [len(values)]) if len(values) > 1 else (est, est)
    return est, low, high


# ------------------------------------------------------------------ check-p99
def cmd_check_p99(a):
    runs = load_runs(a.reps)
    if not runs:
        print(f"no runs under {a.reps}", file=sys.stderr)
        return 2
    p99 = MergedPercentile([r["hist"] for r in runs]).percentile(99, [1] * len(runs))
    overloaded = sum(r["overloaded"] for r in runs)
    print(f"{p99 / 1000:.0f}")
    return 3 if overloaded or p99 > P99_TARGET_US else 0


# ------------------------------------------------------------------ rows
def base_columns():
    cols = ("name throughput ci95 p50_us p95_us p99_us runs warmup_ms steady_ms n burst_drain_ms "
            "burst_p99_us burst_size burst_drain_ci_ms burst_p50_us burst_p95_us burst_txns "
            "burst_txns_confirmed").split()
    for op in OPS:
        cols += [f"{op}_tput", f"{op}_p50_us", f"{op}_p95_us", f"{op}_p99_us"]
    cols += ["tier0_reads", "tier0_fallbacks", "tput_ci_lo", "tput_ci_hi", "p99_ci_lo", "p99_ci_hi",
             "drain_ci_lo", "drain_ci_hi", "independent_runs", "invalid_runs", "notes"]
    return cols


def first_notes(reps_dir):
    """Configuration notes from a repetition's rows.tsv, without its per-invocation counts."""
    for path in sorted(Path(reps_dir).rglob("rows.tsv")):
        with open(path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f, delimiter="\t"):
                notes = row["notes"]
                start = notes.find("offered ")
                notes = notes[start:] if start >= 0 else notes
                notes = re.sub(r"offered \d+ ops/s, \d+ runs, \d+ failed ops; ?", "", notes)
                notes = re.sub(r"; ?WARNING \d+ contended updates over all runs", "", notes)
                notes = re.sub(r"; ?Tier-0 fallbacks over all runs:[^;]*", "", notes)
                notes = re.sub(r"; ?INVALID \d+ of \d+ runs[^;]*", "", notes)
                return notes
    return ""


def build_row(all_runs, reps_dir, equal_load, admission_path):
    runs, invalid = split_valid(all_runs)
    if not runs:
        raise SystemExit(f"every run under {reps_dir} is invalid: "
                         + "; ".join(r["invalid_reason"] for r in invalid))
    head = runs[0]
    tputs = [r["throughput"] for r in runs]
    tput, tput_lo, tput_hi = summarise_mean(tputs)
    p99, p99_lo, p99_hi = summarise_percentile(runs, 99)
    hists = MergedPercentile([r["hist"] for r in runs])
    weights = [1] * len(runs)
    overloaded = sum(r["overloaded"] for r in runs)
    # Same floor as Bench.measure: an overloaded run stopped early and its histogram understates.
    reported_p99 = max(p99, 2 * P99_TARGET_US) if overloaded else p99
    rate = head["rate"]

    row = {c: "" for c in base_columns()}
    row.update(name=head["name"], throughput=f"{tput:.1f}", ci95=f"{t_halfwidth(tputs):.1f}",
               p50_us=f"{hists.percentile(50, weights):.0f}",
               p95_us=f"{hists.percentile(95, weights):.0f}", p99_us=f"{reported_p99:.0f}",
               runs=len(runs), warmup_ms=head["warmup_ms"], steady_ms=head["steady_ms"], n=head["n"],
               tput_ci_lo=f"{tput_lo:.1f}", tput_ci_hi=f"{tput_hi:.1f}",
               p99_ci_lo=f"{p99_lo:.0f}", p99_ci_hi=f"{p99_hi:.0f}",
               independent_runs=int(independent(runs)), invalid_runs=len(invalid))

    drains = [r["burst_drain_ms"] for r in runs]
    if head["burst_size"] > 0:
        drain, drain_lo, drain_hi = summarise_mean(drains)
        bursts = MergedPercentile([r["burst_hist"] for r in runs])
        row.update(burst_drain_ms=f"{drain:.0f}", burst_size=head["burst_size"],
                   burst_drain_ci_ms=f"{t_halfwidth(drains):.1f}",
                   burst_p50_us=f"{bursts.percentile(50, weights):.0f}",
                   burst_p95_us=f"{bursts.percentile(95, weights):.0f}",
                   burst_p99_us=f"{bursts.percentile(99, weights):.0f}",
                   drain_ci_lo=f"{drain_lo:.0f}", drain_ci_hi=f"{drain_hi:.0f}")
    else:
        row.update(burst_drain_ms="0", burst_size="0", burst_drain_ci_ms="0.0", burst_p50_us="0",
                   burst_p95_us="0", burst_p99_us="0")
    txns = [r["burst_txns"] for r in runs]
    row["burst_txns"] = f"{mean(txns):.1f}" if min(txns) >= 0 else "-1.0"
    confirmed = [r["burst_confirmed"] for r in runs]
    row["burst_txns_confirmed"] = f"{mean(confirmed):.1f}" if min(confirmed) >= 0 else "-1.0"

    for op in OPS:
        op_hists = [r["ops"][op]["hist"] for r in runs]
        if sum(h["n"] for h in op_hists) == 0:
            continue
        index = MergedPercentile(op_hists)
        floor = 2 * P99_TARGET_US if overloaded else 0
        row.update({f"{op}_tput": f"{mean(r['ops'][op]['tput'] for r in runs):.1f}",
                    f"{op}_p50_us": f"{index.percentile(50, weights):.0f}",
                    f"{op}_p95_us": f"{index.percentile(95, weights):.0f}",
                    f"{op}_p99_us": f"{max(index.percentile(99, weights), floor):.0f}"})

    reads = [r["tier0_reads"] for r in runs]
    if min(reads) < 0:
        row["tier0_reads"], row["tier0_fallbacks"] = -1, -1
    else:
        row["tier0_reads"] = sum(reads)
        row["tier0_fallbacks"] = sum(r["tier0_fallbacks"] for r in runs)

    failures = sum(r["failures"] for r in runs)
    notes = [f"offered {rate} ops/s, {len(runs)} runs, {failures} failed ops"]
    configuration = first_notes(reps_dir)
    if configuration:
        notes.append(configuration)
    contended = sum(max(r["contended"], 0) for r in runs)
    if contended:
        notes.append(f"WARNING {contended} contended updates over all runs")
    if invalid:
        notes.append(f"EXCLUDED {len(invalid)} invalid runs: "
                     + ", ".join(sorted({r['invalid_reason'] for r in invalid})))
    if admission_path:
        notes.append(f"admission path: {admission_path}")
    text = "; ".join(notes)
    if overloaded:
        text = (f"OVERLOADED in {overloaded} of {len(runs)} runs (p99 is a lower bound); " + text)
    if equal_load:
        keeps = tput >= 0.95 * rate
        verdict = ("sustained" if keeps and reported_p99 <= P99_TARGET_US
                   else "SATURATED (throughput) -- not comparable" if not keeps
                   else "SATURATED (p99 past target) -- not comparable")
        text = f"{verdict}; {text}"
    row["notes"] = text.replace("\t", " ").replace("\n", " ")
    return row


def cmd_rows(a):
    runs = load_runs(a.reps)
    if not runs:
        print(f"no runs under {a.reps}", file=sys.stderr)
        return 2
    levels = by_level(runs)
    if len(levels) != 1:
        print(f"{a.reps} holds {len(levels)} levels; rows takes one level", file=sys.stderr)
        return 2
    if len(runs) < 2:
        print(f"{a.reps} holds one run; no interval is possible", file=sys.stderr)
    row = build_row(runs, a.reps, a.equal_load, a.admission_path or "")
    columns = base_columns()
    dest = Path(a.out)
    new = not dest.is_file() or dest.stat().st_size == 0
    with open(dest, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, delimiter="\t", lineterminator="\n",
                           quoting=csv.QUOTE_NONE, quotechar=None, escapechar="\\")
        if new:
            w.writeheader()
        w.writerow(row)
    return 0


# ------------------------------------------------------------------ report
METRICS = [("p50 latency (ms)", 50), ("p95 latency (ms)", 95), ("p99 latency (ms)", 99)]


def ratio_stat(values_a, values_b):
    def stat(weights):
        wa, wb = weights
        return (sum(w * v for w, v in zip(wa, values_a)) / sum(wa)) / \
               (sum(w * v for w, v in zip(wb, values_b)) / sum(wb))
    return stat


def percentile_ratio_stat(hists_a, hists_b, p):
    ia, ib = MergedPercentile(hists_a), MergedPercentile(hists_b)

    def stat(weights):
        denominator = ib.percentile(p, weights[1])
        return ia.percentile(p, weights[0]) / denominator if denominator else float("inf")
    return stat


def compare_level(name_a, runs_a, name_b, runs_b, equal_load):
    """(row dicts) comparing arm a with reference b at one level; each row gets a raw p."""
    rows = []
    sizes = [len(runs_a), len(runs_b)]

    def add(metric, values_a, values_b, est_a, est_b, stat):
        low, high = bca(stat, sizes)
        rows.append(dict(metric=metric, a=est_a, b=est_b, ratio=est_a / est_b if est_b else float("inf"),
                         low=low, high=high, p=permutation_p(values_a, values_b),
                         delta=cliffs_delta(values_a, values_b)))

    tputs_a = [r["throughput"] for r in runs_a]
    tputs_b = [r["throughput"] for r in runs_b]
    # At equal load throughput equals the schedule on both systems unless one saturated, so a
    # test of it says nothing; it is still printed so that a saturated row cannot pass unseen.
    add("throughput (ops/s)", tputs_a, tputs_b, mean(tputs_a), mean(tputs_b), ratio_stat(tputs_a, tputs_b))

    hists_a, hists_b = [r["hist"] for r in runs_a], [r["hist"] for r in runs_b]
    for metric, p in METRICS:
        values_a = [run_percentile(h, p) / 1000 for h in hists_a]
        values_b = [run_percentile(h, p) / 1000 for h in hists_b]
        est_a = MergedPercentile(hists_a).percentile(p, [1] * len(hists_a)) / 1000
        est_b = MergedPercentile(hists_b).percentile(p, [1] * len(hists_b)) / 1000
        add(metric, values_a, values_b, est_a, est_b, percentile_ratio_stat(hists_a, hists_b, p))

    if runs_a[0]["burst_size"] > 0 and runs_b[0]["burst_size"] > 0:
        drains_a = [r["burst_drain_ms"] for r in runs_a]
        drains_b = [r["burst_drain_ms"] for r in runs_b]
        add("burst drain (ms)", drains_a, drains_b, mean(drains_a), mean(drains_b),
            ratio_stat(drains_a, drains_b))
    return rows


def cmd_report(a):
    arms = {}
    for spec in a.arm:
        name, _, path = spec.partition("=")
        arms[name] = by_level(load_runs(path))
    if a.ref not in arms:
        print(f"--ref {a.ref} is not one of the arms ({', '.join(arms)})", file=sys.stderr)
        return 2

    out = ["# Run-level statistics", ""]
    problems = []
    usable = {}
    for name, levels in arms.items():
        for key, runs in sorted(levels.items()):
            valid, invalid = split_valid(runs)
            label = f"{name} {key[0]} @ {key[1]}"
            if invalid:
                problems.append(f"{label}: {len(invalid)} invalid runs excluded "
                                f"({'; '.join(sorted({r['invalid_reason'] for r in invalid}))})")
            if any(r["overloaded"] for r in valid):
                problems.append(f"{label}: overloaded in "
                                f"{sum(r['overloaded'] for r in valid)} runs; percentiles are lower bounds")
            if not independent(valid):
                problems.append(f"{label}: runs shared a cluster and JVM, so they are not independent "
                                "(REPEAT_FRESH=1); intervals and tests are not valid")
            if len(valid) < PROTOCOL_RUNS:
                problems.append(f"{label}: {len(valid)} valid runs, below the protocol's {PROTOCOL_RUNS}")
            usable[(name, key)] = valid

    out += ["## Per level", "",
            "| Arm | Mix | Offered | Runs | Throughput (ops/s) [95% BCa] | t half-width "
            "| p50 (ms) | p95 (ms) | p99 (ms) [95% BCa] | Burst drain (ms) [95% BCa] |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for (name, key), runs in usable.items():
        if not runs:
            continue
        tputs = [r["throughput"] for r in runs]
        t, tl, th = summarise_mean(tputs)
        index = MergedPercentile([r["hist"] for r in runs])
        weights = [1] * len(runs)
        p99, l99, h99 = summarise_percentile(runs, 99)
        drain = "—"
        if runs[0]["burst_size"] > 0:
            d, dl, dh = summarise_mean([r["burst_drain_ms"] for r in runs])
            drain = f"{d:.0f} [{dl:.0f}, {dh:.0f}]"
        out.append(f"| {name} | {key[0]} | {key[1]} | {len(runs)} | {t:.1f} [{tl:.1f}, {th:.1f}] "
                   f"| ±{t_halfwidth(tputs):.1f} | {index.percentile(50, weights) / 1000:.1f} "
                   f"| {index.percentile(95, weights) / 1000:.1f} "
                   f"| {p99 / 1000:.1f} [{l99 / 1000:.1f}, {h99 / 1000:.1f}] | {drain} |")

    # Own-operating-point runs sit at different rates, so comparison keys on mix alone there.
    rows = []
    for name in arms:
        if name == a.ref:
            continue
        for (other, key), runs in usable.items():
            if other != name or not runs:
                continue
            if a.equal_load:
                ref_runs = usable.get((a.ref, key))
            else:
                candidates = [r for (o, k), r in usable.items() if o == a.ref and k[0] == key[0]]
                ref_runs = candidates[0] if len(candidates) == 1 else None
            if not ref_runs or len(runs) < 2 or len(ref_runs) < 2:
                continue
            load = f"{key[1]}" if a.equal_load else f"{key[1]} vs {ref_runs[0]['rate']}"
            for row in compare_level(name, runs, a.ref, ref_runs, a.equal_load):
                row.update(arm=name, mix=key[0], load=load)
                rows.append(row)

    if rows:
        adjusted = holm([r["p"] for r in rows])
        out += ["", f"## Each arm against {a.ref}", ""]
        if not a.equal_load:
            out += ["Each arm is at its OWN operating point, so latency ratios compare different loads.", ""]
        out += [f"Holm correction across all {len(rows)} tests below. Cliff's delta runs from -1 (every "
                "run of the arm below the reference) to +1 (every run above). With 10 runs per arm the "
                "smallest possible exact p is about 1.1e-5.", "",
                f"| Arm | Mix | Load (ops/s) | Metric | Arm | {a.ref} | Ratio [95% BCa] | Cliff's delta "
                "| p | Holm p |",
                "|---|---|---|---|---|---|---|---|---|---|"]
        for r, h in zip(rows, adjusted):
            out.append(f"| {r['arm']} | {r['mix']} | {r['load']} | {r['metric']} | {r['a']:.1f} | {r['b']:.1f} "
                       f"| {r['ratio']:.3g} [{r['low']:.3g}, {r['high']:.3g}] | {r['delta']:+.2f} "
                       f"| {r['p']:.2g} | {h:.2g} |")
    out += ["", "Measured coverage of nominal 95% intervals at n = 10 (test_stats.py, simulated data): "
            "means 93-94%; ratios of means 96%; merged p99 86-97%, lowest when runs differ most from "
            "each other. Read p99 intervals with that in mind."]
    if problems:
        out = ["# NOT REPORTABLE as it stands", ""] + [f"- {p}" for p in problems] + [""] + out
    text = "\n".join(out) + "\n"
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8")
    else:
        print(text, end="")
    return 4 if problems else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("check-p99")
    p.add_argument("reps")
    p.set_defaults(func=cmd_check_p99)
    p = sub.add_parser("rows")
    p.add_argument("reps")
    p.add_argument("--out", required=True)
    p.add_argument("--equal-load", action="store_true")
    p.add_argument("--admission-path")
    p.set_defaults(func=cmd_rows)
    p = sub.add_parser("report")
    p.add_argument("--arm", action="append", required=True, metavar="NAME=DIR")
    p.add_argument("--ref", required=True)
    p.add_argument("--equal-load", action="store_true")
    p.add_argument("--out")
    p.set_defaults(func=cmd_report)
    a = ap.parse_args()
    sys.exit(a.func(a))


if __name__ == "__main__":
    main()
