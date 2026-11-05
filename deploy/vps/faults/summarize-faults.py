#!/usr/bin/env python3
"""
One table per system from a fault campaign, using the same t intervals as stats.py.

    python3 summarize-faults.py results/faults/<stamp> [--out SUMMARY.md]
    python3 summarize-faults.py --restart-trials build/real-gates/<stamp>/restart-trials.csv

A run with invalid.txt never counts as evidence: its fault did not take effect, or the run did not
finish. Every count of "no violation" says how many valid runs it rests on.
"""
import argparse
import csv
import importlib.util
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

_spec = importlib.util.spec_from_file_location("stats", Path(__file__).resolve().parent.parent / "stats.py")
stats = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(stats)


def wilson(successes, n, z=1.959964):
    """95% Wilson interval for a proportion: unlike the normal one, it is sensible at 0 of 10."""
    if n == 0:
        return None
    p = successes / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, centre - half), min(1.0, centre + half)


def mean_ci(values):
    values = [v for v in values if v is not None]
    if not values:
        return "—"
    if len(values) == 1:
        return f"{values[0]:.2f} (one run)"
    return f"{statistics.fmean(values):.2f} ± {stats.t_halfwidth(values):.2f}"


def pct_interval(k, n):
    w = wilson(k, n)
    return "—" if w is None else f"{k}/{n} [{100 * w[0]:.0f}%, {100 * w[1]:.0f}%]"


def load_campaign(root):
    runs = defaultdict(lambda: defaultdict(list))
    for run in sorted(Path(root).glob("*/*/rep-*")):
        system, scenario = run.parent.parent.name, run.parent.name
        check = run / "check.json"
        result = json.loads(check.read_text(encoding="utf-8")) if check.is_file() else None
        invalid = (run / "invalid.txt").read_text(encoding="utf-8").strip() \
            if (run / "invalid.txt").is_file() else None
        runs[system][scenario].append({"dir": run.name, "check": result, "invalid": invalid})
    return runs


def recovery_cell(valid):
    never = sum(1 for r in valid if "recovery_s" in r and r["recovery_s"] is None)
    measured = [r["recovery_s"] for r in valid if r.get("recovery_s") is not None]
    text = mean_ci(measured)
    return f"{text}; never in {never}" if never else text


def p99_cell(valid, phase):
    return mean_ci([r.get("phase_write_p99_ms", {}).get(phase) for r in valid])


def summary_lines(runs):
    lines = ["# Fault campaign\n",
             "Each run: healthy, fault, healed, on fresh state. Only a run whose fault was confirmed in "
             "force and whose client finished its final checks counts as evidence. Intervals are 95%: "
             "Wilson for proportions, t for means.\n"]
    for system, scenarios in sorted(runs.items()):
        lines.append(f"## {system}\n")
        lines.append("| Scenario | Valid / all | No verdict | Runs with a violation | Violation kinds | "
                     "Longest write gap (s) | Recovery after heal (s) | Write p99 fault (ms) | "
                     "Write p99 healed (ms) |")
        lines.append("|---|---|---|---|---|---|---|---|---|")
        for scenario, reps in sorted(scenarios.items()):
            checked = [r["check"] for r in reps if r["check"] and not r["invalid"]]
            no_verdict = [c for c in checked if c["unverifiable"] and not c["violations"]]
            valid = [c for c in checked if c not in no_verdict]
            bad = [c for c in valid if c["violations"]]
            kinds = Counter(v["kind"] for c in bad for v in c["violations"])
            lines.append(
                f"| {scenario} | {len(valid)} / {len(reps)} | {len(no_verdict)} | "
                f"{pct_interval(len(bad), len(valid))} | "
                f"{', '.join(f'{k} ({n})' for k, n in kinds.most_common()) or '—'} | "
                f"{mean_ci([c.get('longest_write_gap_s') for c in checked])} | "
                f"{recovery_cell(checked)} | {p99_cell(checked, 'fault')} | {p99_cell(checked, 'healed')} |")
        lines.append("")
        lines.append(f"### Read levels met on {system}\n")
        lines.append("| Scenario | Level | Reads | DIDs linearizable / not / unknown | Runs with a stale read | "
                     "Max staleness (ms) | Monotonic violations |")
        lines.append("|---|---|---|---|---|---|---|")
        for scenario, reps in sorted(scenarios.items()):
            checked = [r["check"] for r in reps if r["check"] and not r["invalid"]]
            levels = sorted({l for c in checked for l in c["levels"]})
            for level in levels:
                s = [c["levels"][level] for c in checked if level in c["levels"]]
                lines.append(
                    f"| {scenario} | {level} | {sum(x['reads'] for x in s)} | "
                    f"{sum(x['dids_linearizable'] for x in s)} / {sum(x['dids_not_linearizable'] for x in s)} / "
                    f"{sum(x['dids_unknown'] for x in s)} | "
                    f"{sum(1 for x in s if x['stale_reads'])} of {len(s)} | "
                    f"{max(x['max_staleness_ms'] for x in s):.0f} | {sum(x['monotonic_violations'] for x in s)} |")
        lines.append("")
    return lines


def restart_lines(path):
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    outcomes = Counter(r["outcome"] for r in rows)
    usable = [r for r in rows if r["outcome"] != "setup-failed"]
    recovered = outcomes.get("recovered", 0)
    lines = ["# Restart trials\n", f"{len(rows)} trials, {len(usable)} usable.\n",
             f"Recovered: {pct_interval(recovered, len(usable))} (95% Wilson interval).\n",
             "| Outcome | Trials |", "|---|---|"]
    lines += [f"| {k} | {n} |" for k, n in outcomes.most_common()]
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?")
    ap.add_argument("--out")
    ap.add_argument("--restart-trials")
    a = ap.parse_args()
    if a.restart_trials:
        lines = restart_lines(a.restart_trials)
    elif a.root:
        lines = summary_lines(load_campaign(a.root))
    else:
        ap.error("give a campaign folder or --restart-trials")
    text = "\n".join(lines) + "\n"
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
