#!/usr/bin/env python3
"""
Merge the newest Tailored BFT and Indy RESULTS.md files into one comparison table.

    python3 compare.py                         # newest run of each, from ./results
    python3 compare.py TAILORED.md INDY.md     # explicit files

Refuses to present a comparison silently when the two runs used different run counts, and
flags a Tailored result produced by the in-process simulator.
"""
import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent / "results"

ROW = re.compile(
    r"^\|\s*(?P<name>(?:Tailored BFT VDR|Hyperledger Indy) -- (?P<mix>[\w-]+))\s*\|"
    r"\s*(?P<tput>[\d.]+)\s*±\s*(?P<ci>[\d.]+)\s*\|"
    r"\s*(?P<p50>\d+)\s*/\s*(?P<p95>\d+)\s*/\s*(?P<p99>\d+)")
BURST = re.compile(r"(\d+) revocations drained in (-?\d+) ms \((\d+) revokes/s\), burst p99 (\d+)")
RUNS = re.compile(r"(\d+) runs per configuration")


def newest(*subs):
    """Newest RESULTS.md across the given result folders, searched in order of preference.

    Tailored results land in results/tailored-bft when the run used BFT-SMaRt and in
    results/tailored when it used the simulator. The real cluster wins whenever it has produced
    anything at all, so a stale simulator run cannot quietly become the reported column.
    """
    for sub in subs:
        d = BASE / sub
        runs = sorted(p for p in d.iterdir() if (p / "RESULTS.md").exists()) if d.exists() else []
        if runs:
            return runs[-1] / "RESULTS.md"
    sys.exit("no RESULTS.md under " + " or ".join(str(BASE / s) for s in subs))


def parse(path):
    text = Path(path).read_text(encoding="utf-8")
    rows = {}
    for line in text.splitlines():
        m = ROW.match(line)
        if m:
            rows[m["mix"]] = {k: float(m[k]) for k in ("tput", "ci", "p50", "p95", "p99")}
    b = BURST.search(text)
    burst = {"drain_ms": float(b[2]), "rate": float(b[3]), "p99": float(b[4])} if b else None
    r = RUNS.search(text)
    return {"rows": rows, "burst": burst, "runs": int(r[1]) if r else None,
            "simulated": "not measurements of a BFT system" in text, "path": str(path)}


def ms(us):
    return f"{us / 1000:,.1f}"


def ratio(a, b):
    return f"{a / b:.3g}x" if b else "—"


def main():
    if len(sys.argv) == 3:
        t_path, i_path = sys.argv[1], sys.argv[2]
    else:
        t_path, i_path = newest("tailored-bft", "tailored"), newest("indy")
    t, i = parse(t_path), parse(i_path)

    print(f"Tailored: {t['path']}")
    print(f"Indy:     {i['path']}\n")
    if t["runs"] != i["runs"]:
        print(f"WARNING: run counts differ (Tailored {t['runs']}, Indy {i['runs']}); "
              "rerun both with the same -Dbench.* settings before comparing.\n")
    if t["simulated"]:
        print("WARNING: the Tailored result comes from the in-process simulator with hardcoded "
              "latency constants. This table checks the pipeline; it is NOT a paper result.\n")

    print("| Mix | Metric | Tailored BFT | Indy | Tailored / Indy |")
    print("|---|---|---|---|---|")
    for mix in ("read-heavy", "bursty-revoke"):
        tr, ir = t["rows"].get(mix), i["rows"].get(mix)
        if not tr or not ir:
            print(f"| {mix} | (missing in {'Tailored' if not tr else 'Indy'}) | | | |")
            continue
        print(f"| {mix} | throughput (ops/s) | {tr['tput']:,.0f} ± {tr['ci']:,.0f} "
              f"| {ir['tput']:,.0f} ± {ir['ci']:,.0f} | {ratio(tr['tput'], ir['tput'])} |")
        for p in ("p50", "p95", "p99"):
            print(f"| {mix} | {p} latency (ms) | {ms(tr[p])} | {ms(ir[p])} | {ratio(tr[p], ir[p])} |")

    tb, ib = t["burst"], i["burst"]
    if tb and ib:
        print(f"| burst | 800-revoke drain (ms) | {tb['drain_ms']:,.0f} | {ib['drain_ms']:,.0f} "
              f"| {ratio(tb['drain_ms'], ib['drain_ms'])} |")
        print(f"| burst | burst p99 (ms) | {ms(tb['p99'])} | {ms(ib['p99'])} "
              f"| {ratio(tb['p99'], ib['p99'])} |")

    print("\nThroughput is the sustained operating point each system reached under the same p99 "
          "target, not a fixed offered load. Ratios < 1x for latency favour Tailored.")


if __name__ == "__main__":
    main()
