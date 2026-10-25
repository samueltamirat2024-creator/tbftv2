#!/usr/bin/env python3
"""
Build the comparison tables from the rows.tsv files vdr.bench.Bench writes.

    python3 make-table.py --tailored T.tsv --indy I.tsv [--profile standard] [--cpus 4]
    python3 make-table.py ... --equal-load      # rows are "<mix> @ <rate> ops/s"

Four tables:
  1. every configuration (system x mix), as before;
  2. Register and Update, Tailored next to Indy, with ratios;
  3. per-operation latency of the writes inside the mixed workloads;
  4. the revocation burst (800 revocations at one instant inside bursty-revoke), Tailored next
     to Indy, with ratios.

Missing inputs leave their rows as "not measured", so a one-system run still prints the tables.
Latency is reported in milliseconds; the harness records microseconds.
"""
import argparse
import csv
import re
from pathlib import Path

SYSTEMS = [("Tailored BFT VDR", "tailored"), ("Hyperledger Indy baseline", "indy")]
MIXES = ["read-heavy", "bursty-revoke", "register", "update"]
MIXED = ["read-heavy", "bursty-revoke"]
WRITES = ["register", "update"]


def load(path):
    """{(mix, rate or None): row}; rate is set only for equal-load rows."""
    if not path or not Path(path).is_file():
        return {}
    out = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            tail = row["name"].rsplit("-- ", 1)[-1]
            mix, _, rate = tail.partition(" @ ")
            rate = int(rate.split()[0]) if rate else None
            out[(mix.strip(), rate)] = row
    return out


def ms(us):
    v = float(us) / 1000.0
    return f"{v:.1f}" if v < 100 else f"{v:.0f}"


def num(row, col):
    """A float from an optional column (older rows.tsv files lack the per-op columns)."""
    v = (row or {}).get(col) or ""
    return float(v) if v.strip() else None


def burst_size(row):
    """Revocations in the burst; rows.tsv files before the burst_size column used 800."""
    n = num(row, "burst_size")
    return int(n) if n else 800


def ratio(a, b):
    return f"{a / b:.3g}x" if a is not None and b else "—"


def short_notes(row, mix):
    notes = row["notes"]
    parts = []
    if notes.startswith("SATURATED"):
        parts.append(notes.split(";", 1)[0])
    m = re.search(r"offered (\d+) ops/s", notes)
    if m:
        equal = notes.startswith(("sustained", "SATURATED"))   # equal-load rows carry a verdict
        parts.append(f"{m.group(1)} ops/s offered" if equal
                     else f"operating point {m.group(1)} ops/s offered")
    parts.append(f"{row['runs']} runs x {int(row['steady_ms']) // 1000} s window, "
                 f"{int(row['warmup_ms']) // 1000} s warm-up")
    m = re.search(r"(\d+) failed ops", notes)
    if m and m.group(1) != "0":
        parts.append(f"{m.group(1)} failed ops")
    m = re.search(r"WARNING (\d+) contended updates", notes)
    if m:
        parts.append(f"{m.group(1)} contended updates (raise UPDATE_DID_POOL)")
    if mix == "bursty-revoke" and float(row.get("burst_drain_ms", 0) or 0) > 0:
        parts.append(f"{burst_size(row)}-revocation burst drained in {float(row['burst_drain_ms']):.0f} ms "
                     f"(burst p99 {ms(row['burst_p99_us'])} ms)")
    m = re.search(r"Ed25519 ([^,;]+)", notes)
    if m:
        parts.append(f"client Ed25519: {m.group(1).strip()}")
    m = re.search(r"stepped down|admission path: ([^;|]+)", notes)
    if m and m.group(0).startswith("admission path") and "," in m.group(1):
        parts.append("stepped down: " + m.group(1).strip())
    return "; ".join(parts)


def keys_for(data, mix, equal):
    """The (mix, rate) keys to print for one mix: every measured rate, or the one operating point."""
    if not equal:
        return [(mix, None)]
    rates = sorted({r for d in data.values() for (m, r) in d if m == mix and r is not None})
    return [(mix, r) for r in rates]


def label_of(mix, rate):
    return mix if rate is None else f"{mix} @ {rate} ops/s"


def main_table(data, mixes, equal):
    print("| Configuration | Throughput (ops/s) | p50 / p95 / p99 latency | Notes |")
    print("|---|---|---|---|")
    for system, key in SYSTEMS:
        for mix in mixes:
            for k in keys_for(data, mix, equal) or [(mix, None)]:
                row = data[key].get(k)
                label = f"{system} — {label_of(*k)}"
                if not row:
                    print(f"| {label} | — | — | not measured in this run |")
                    continue
                tput = f"{float(row['throughput']):.0f} ± {float(row['ci95']):.0f}"
                lat = f"{ms(row['p50_us'])} / {ms(row['p95_us'])} / {ms(row['p99_us'])} ms"
                print(f"| {label} | {tput} | {lat} | {short_notes(row, mix)} |")


def writes_table(data, mixes, equal):
    """Register and Update side by side. In a write-only mix the row IS that operation."""
    wanted = [m for m in WRITES if m in mixes]
    if not wanted:
        return
    print("\n## Register and Update — Tailored BFT VDR vs Hyperledger Indy\n")
    if equal:
        print("Both systems offered the same load in each row.\n")
    else:
        print("Each system at its own operating point (highest load with merged p99 ≤ 500 ms), so "
              "latencies are at DIFFERENT loads; run EQUAL_LOAD=1 for a same-load comparison.\n")
    print("| Operation | Metric | Tailored BFT VDR | Hyperledger Indy | Tailored / Indy |")
    print("|---|---|---|---|---|")
    for mix in wanted:
        for k in keys_for(data, mix, equal) or [(mix, None)]:
            t, i = data["tailored"].get(k), data["indy"].get(k)
            label = label_of(*k)
            if not t or not i:
                print(f"| {label} | (not measured on {'Tailored' if not t else 'Indy'}) | | | |")
                continue
            tt, it = float(t["throughput"]), float(i["throughput"])
            print(f"| {label} | throughput (ops/s) | {tt:.0f} ± {float(t['ci95']):.0f} "
                  f"| {it:.0f} ± {float(i['ci95']):.0f} | {ratio(tt, it)} |")
            for p in ("p50", "p95", "p99"):
                a, b = float(t[f"{p}_us"]), float(i[f"{p}_us"])
                print(f"| {label} | {p} latency (ms) | {ms(a)} | {ms(b)} | {ratio(a, b)} |")
    print("\nRatios > 1x for throughput and < 1x for latency favour the Tailored BFT VDR.")


def mixed_write_latency(data, mixes, equal):
    """Each write's own merged histogram inside the mixed workloads."""
    wanted = [m for m in MIXED if m in mixes]
    rows = []
    for mix in wanted:
        for k in keys_for(data, mix, equal) or [(mix, None)]:
            for op in WRITES:
                cells = []
                for _, key in SYSTEMS:
                    row = data[key].get(k)
                    p = [num(row, f"{op}_p{q}_us") for q in (50, 95, 99)]
                    tput = num(row, f"{op}_tput")
                    cells.append("—" if None in p else
                                 f"{tput:.0f} ops/s; {ms(p[0])} / {ms(p[1])} / {ms(p[2])} ms")
                if any(c != "—" for c in cells):
                    rows.append(f"| {label_of(*k)} | {op} | {cells[0]} | {cells[1]} |")
    if not rows:
        return
    print("\n## Writes inside the mixed workloads (per-operation p50 / p95 / p99)\n")
    print("| Mix | Operation | Tailored BFT VDR | Hyperledger Indy |")
    print("|---|---|---|---|")
    print("\n".join(rows))
    print("\nThe mixed workloads' write share is updates only (read-heavy 4%, bursty-revoke 10%), "
          "so register appears only in its own mix.")


def burst_table(data, mixes, equal):
    """The revocation burst as an experiment of its own: drain, burst latency and batching cost."""
    if "bursty-revoke" not in mixes:
        return
    keys = keys_for(data, "bursty-revoke", equal) or [("bursty-revoke", None)]
    rows = []

    def add(label, metric, t, i, fmt, with_ratio=True):
        cell = lambda v: "—" if v is None else fmt(v)
        rows.append(f"| {label} | {metric} | {cell(t)} | {cell(i)} | "
                    f"{ratio(t, i) if with_ratio else '—'} |")

    for k in keys:
        t, i = data["tailored"].get(k), data["indy"].get(k)
        fired = [r for r in (t, i) if r and (num(r, "burst_drain_ms") or 0) > 0]
        if not fired:
            continue
        label = label_of(*k)
        b = lambda r, c: num(r, c) if r and (num(r, "burst_drain_ms") or 0) > 0 else None

        def offered(r):
            m = re.search(r"offered (\d+) ops/s", (r or {}).get("notes", ""))
            return float(m.group(1)) if m else None

        def drain_cell(r):
            d, ci = b(r, "burst_drain_ms"), b(r, "burst_drain_ci_ms")
            return None if d is None else (d, ci)

        td, idr = drain_cell(t), drain_cell(i)
        rows.append(f"| {label} | burst size (revocations) | "
                    f"{burst_size(t) if td else '—'} | {burst_size(i) if idr else '—'} | — |")
        add(label, "steady offered load around the burst (ops/s)", offered(t), offered(i),
            lambda v: f"{v:.0f}", False)
        fmt_drain = lambda c: "—" if c is None else (
            f"{c[0]:.0f} ± {c[1]:.0f}" if c[1] is not None else f"{c[0]:.0f}")
        rows.append(f"| {label} | drain time, injection to last commit (ms) | {fmt_drain(td)} | "
                    f"{fmt_drain(idr)} | {ratio(td and td[0], idr and idr[0])} |")
        rate = lambda r, c: burst_size(r) / (c[0] / 1000.0) if c else None
        add(label, "drain throughput (revocations/s)", rate(t, td), rate(i, idr),
            lambda v: f"{v:.0f}")
        for p in ("p50", "p95", "p99"):
            add(label, f"burst {p} latency (ms)", b(t, f"burst_{p}_us"), b(i, f"burst_{p}_us"), ms)

        def txns(r):
            v = b(r, "burst_txns")
            return v if v is not None and v >= 0 else None
        tt, it = txns(t), txns(i)
        conf = lambda r, v: "—" if v is None else f"{v:.1f} ({b(r, 'burst_txns_confirmed'):.1f})"
        rows.append(f"| {label} | ordered revocation transactions (confirmed) | {conf(t, tt)} | "
                    f"{conf(i, it)} | {ratio(tt, it)} |")
        add(label, "revocations per ordered transaction",
            burst_size(t) / tt if tt else None, burst_size(i) / it if it else None,
            lambda v: f"{v:.0f}")
        add(label, "steady-stream p99 in the same runs (ms)",
            num(t, "p99_us"), num(i, "p99_us"), ms)
    if not rows:
        return
    print("\n## Revocation burst — 800 revocations injected at one instant\n")
    print("Inside every bursty-revoke run, halfway through the steady window, the generator submits "
          "the whole burst at a single instant on top of the steady stream (incident response: one "
          "issuer revoking many credentials at once). Burst latency is measured from that instant, "
          "so it includes queueing behind the rest of the burst; it is kept out of the steady-mix "
          "percentiles above. Drain time is the mean over runs ± its 95% confidence half-width; "
          "burst percentiles come from the burst's histogram merged over all runs. Both systems "
          "aggregate revocations before ordering them (same batching delay), so the transaction "
          "rows show what the burst cost in consensus instances.\n")
    if not equal:
        print("Each system carries the burst on top of its OWN steady operating point (row 2), so "
              "the steady background load differs; run EQUAL_LOAD=1 for the same background load.\n")
    print("| Load | Metric | Tailored BFT VDR | Hyperledger Indy | Tailored / Indy |")
    print("|---|---|---|---|---|")
    print("\n".join(rows))
    print("\nRatios < 1x for time, latency and transaction count, and > 1x for revocations/s and "
          "revocations per transaction, favour the Tailored BFT VDR. A dash means not measured, or "
          "(transactions) that the backend cannot count them.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tailored")
    ap.add_argument("--indy")
    ap.add_argument("--profile", default="")
    ap.add_argument("--cpus", default="")
    ap.add_argument("--equal-load", action="store_true")
    a = ap.parse_args()
    data = {"tailored": load(a.tailored), "indy": load(a.indy)}
    measured = {m for d in data.values() for (m, _) in d}
    # The original four rows always show, so the table keeps its shape; register/update rows
    # only when some system measured them.
    mixes = [m for m in MIXES if m in MIXED or m in measured]

    print("# Tailored BFT VDR vs Hyperledger Indy -- measured\n")
    if a.equal_load:
        print(f"4 nodes x 1 vCPU each (capped and pinned), server vCPUs: {a.cpus or '?'}, "
              f"protocol profile: {a.profile or '?'}. Open-loop generator at the SAME fixed offered "
              "load on both systems; a row marked SATURATED kept neither the schedule nor the "
              "500 ms p99 and measures a queue. Latency percentiles from the merged histogram of "
              "all runs at that load.\n")
    else:
        print(f"4 nodes x 1 vCPU each (capped and pinned), server vCPUs: {a.cpus or '?'}, "
              f"protocol profile: {a.profile or '?'}. Open-loop generator; throughput = committed "
              "ops/s in the steady window at the highest offered load whose merged p99 stays "
              "within 500 ms; latency percentiles from the merged histogram of all runs at that "
              "load.\n")
    main_table(data, mixes, a.equal_load)
    writes_table(data, mixes, a.equal_load)
    mixed_write_latency(data, mixes, a.equal_load)
    burst_table(data, mixes, a.equal_load)
    if a.profile == "quick":
        print("\n> PROFILE=quick: short windows and 3 runs. Use these to check the pipeline, not as "
              "results. Re-run with PROFILE=standard or full.")
    print("\nFull per-row notes (batching, client configuration, admission path, per-operation "
          "tables) are in the tailored-*-RESULTS.md and indy-*-RESULTS.md files next to this one.")


if __name__ == "__main__":
    main()
