#!/usr/bin/env python3
"""
Build the four-row comparison table from the rows.tsv files vdr.bench.Bench writes.

    python3 make-table.py --tailored T.tsv --indy I.tsv [--profile standard] [--cpus 4]

Missing inputs leave their rows as "not measured", so a one-system run still prints the table.
Latency is reported in milliseconds; the harness records microseconds.
"""
import argparse
import csv
import re
from pathlib import Path

ROWS = [
    ("Tailored BFT VDR", "read-heavy", "tailored"),
    ("Tailored BFT VDR", "bursty-revoke", "tailored"),
    ("Hyperledger Indy baseline", "read-heavy", "indy"),
    ("Hyperledger Indy baseline", "bursty-revoke", "indy"),
]


def load(path):
    if not path or not Path(path).is_file():
        return {}
    out = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            mix = row["name"].rsplit("-- ", 1)[-1].split(" @ ")[0].strip()
            out[mix] = row
    return out


def ms(us):
    v = float(us) / 1000.0
    return f"{v:.1f}" if v < 100 else f"{v:.0f}"


def short_notes(row, mix):
    notes = row["notes"]
    parts = []
    m = re.search(r"offered (\d+) ops/s", notes)
    if m:
        parts.append(f"operating point {m.group(1)} ops/s offered")
    parts.append(f"{row['runs']} runs x {int(row['steady_ms']) // 1000} s window, "
                 f"{int(row['warmup_ms']) // 1000} s warm-up")
    m = re.search(r"(\d+) failed ops", notes)
    if m and m.group(1) != "0":
        parts.append(f"{m.group(1)} failed ops")
    if mix == "bursty-revoke" and float(row.get("burst_drain_ms", 0) or 0) > 0:
        parts.append(f"800-revocation burst drained in {float(row['burst_drain_ms']):.0f} ms "
                     f"(burst p99 {ms(row['burst_p99_us'])} ms)")
    m = re.search(r"Ed25519 ([^,;]+)", notes)
    if m:
        parts.append(f"client Ed25519: {m.group(1).strip()}")
    m = re.search(r"stepped down|admission path: ([^;|]+)", notes)
    if m and m.group(0).startswith("admission path") and "," in m.group(1):
        parts.append("stepped down: " + m.group(1).strip())
    return "; ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tailored")
    ap.add_argument("--indy")
    ap.add_argument("--profile", default="")
    ap.add_argument("--cpus", default="")
    a = ap.parse_args()
    data = {"tailored": load(a.tailored), "indy": load(a.indy)}

    print("# Tailored BFT VDR vs Hyperledger Indy -- measured\n")
    print(f"4 nodes x 1 vCPU each (capped and pinned), server vCPUs: {a.cpus or '?'}, "
          f"protocol profile: {a.profile or '?'}. Open-loop generator; throughput = committed "
          "ops/s in the steady window at the highest offered load whose merged p99 stays within "
          "500 ms; latency percentiles from the merged histogram of all runs at that load.\n")
    print("| Configuration | Throughput (ops/s) | p50 / p95 / p99 latency | Notes |")
    print("|---|---|---|---|")
    for system, mix, key in ROWS:
        row = data[key].get(mix)
        label = f"{system} — {mix}"
        if not row:
            print(f"| {label} | — | — | not measured in this run |")
            continue
        tput = f"{float(row['throughput']):.0f} ± {float(row['ci95']):.0f}"
        lat = f"{ms(row['p50_us'])} / {ms(row['p95_us'])} / {ms(row['p99_us'])} ms"
        print(f"| {label} | {tput} | {lat} | {short_notes(row, mix)} |")
    if a.profile == "quick":
        print("\n> PROFILE=quick: short windows and 3 runs. Use these to check the pipeline, not as "
              "results. Re-run with PROFILE=standard or full.")
    print("\nFull per-row notes (batching, client configuration, admission path) are in "
          "tailored-RESULTS.md and indy-RESULTS.md next to this file.")


if __name__ == "__main__":
    main()
