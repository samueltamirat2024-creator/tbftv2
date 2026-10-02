#!/usr/bin/env python3
"""
Merge the newest Tailored BFT and Indy RESULTS.md files into one comparison table.

    python3 compare.py                         # operating points, newest run of each
    python3 compare.py --equal-load            # latency at the same offered load on both
    python3 compare.py TAILORED.md INDY.md     # explicit files (either kind)
    python3 compare.py --allow-unreportable    # print anyway, marked NOT REPORTABLE

Refuses to print a comparison that cannot be reported: a Tailored run on the in-process
simulator, run counts that differ or are below the protocol's 10, a Tailored cluster with shared
demo keys, batching delays that differ between the systems, or (equal load) levels that differ or
a row that saturated. --allow-unreportable prints the table for pipeline checks, with every
problem listed above it.
"""
import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent / "results"
PROTOCOL_RUNS = 10
EQUAL_MARKER = "# Equal offered load"

COLS = (r"\s*(?P<tput>[\d.]+)\s*±\s*(?P<ci>[\d.]+)\s*\|"
        r"\s*(?P<p50>\d+)\s*/\s*(?P<p95>\d+)\s*/\s*(?P<p99>\d+)[^|]*\|(?P<notes>[^|]*)\|")
ROW = re.compile(r"^\|\s*(?:Tailored BFT VDR|Hyperledger Indy) -- (?P<mix>[\w-]+)\s*\|" + COLS)
EQ_ROW = re.compile(r"^\|\s*(?:Tailored BFT VDR|Hyperledger Indy) -- (?P<mix>[\w-]+) "
                    r"@ (?P<rate>\d+) ops/s\s*\|" + COLS)
BURST = re.compile(r"(?:\(\D*-- bursty-revoke @ (?P<rate>\d+) ops/s\)\.\*\* )?"
                   r"(?P<size>\d+) revocations drained in (?P<drain>-?\d+) ms \((?P<rps>\d+) "
                   r"revokes/s\), burst p99 (?P<p99>\d+)")
BURST_COST = re.compile(r"Burst cost: ([\d.-]+) ordered revocation transactions per run "
                        r"\(([\d.-]+) confirmed\)")
RUNS = re.compile(r"(\d+) runs per configuration")
DELAY = re.compile(r"maxBatchDelay=(\d+) ms|or (\d+) ms \(swept optimum\)")


def is_equal(path):
    return Path(path).read_text(encoding="utf-8").startswith(EQUAL_MARKER)


def newest(equal, *subs):
    """Newest RESULTS.md of the requested kind across the given folders, in order of preference.

    Tailored results land in results/tailored-bft when the run used BFT-SMaRt and in
    results/tailored when it used the simulator. The real cluster wins whenever it has produced
    anything at all, so a stale simulator run cannot quietly become the reported column. Equal-load
    and operating-point files share the folders; the marker keeps one from standing in for the other.
    """
    for sub in subs:
        d = BASE / sub
        runs = sorted(p for p in d.iterdir()
                      if (p / "RESULTS.md").exists() and is_equal(p / "RESULTS.md") == equal) \
            if d.exists() else []
        if runs:
            return runs[-1] / "RESULTS.md"
    kind = "equal-load " if equal else ""
    sys.exit(f"no {kind}RESULTS.md under " + " or ".join(str(BASE / s) for s in subs))


def parse(path):
    text = Path(path).read_text(encoding="utf-8")
    equal = text.startswith(EQUAL_MARKER)
    rows = {}
    for line in text.splitlines():
        m = (EQ_ROW if equal else ROW).match(line)
        if m:
            key = (m["mix"], int(m["rate"])) if equal else m["mix"]
            rows[key] = {k: float(m[k]) for k in ("tput", "ci", "p50", "p95", "p99")}
            rows[key]["notes"] = m["notes"].strip()

    # Burst paragraphs, each followed by its optional cost line. Keyed by rate in equal-load files.
    bursts = {}
    for b in BURST.finditer(text):
        cost = BURST_COST.search(text, b.end(), b.end() + 400)
        bursts[int(b["rate"]) if b["rate"] else None] = {
            "drain_ms": float(b["drain"]), "rate": float(b["rps"]), "p99": float(b["p99"]),
            "txns": float(cost[1]) if cost else None,
            "confirmed": float(cost[2]) if cost else None}

    delays = {int(a or b) for a, b in DELAY.findall(text)}
    r = RUNS.search(text)
    env = Path(path).with_name("environment.txt")
    env_text = env.read_text(encoding="utf-8") if env.exists() else None
    return {"rows": rows, "bursts": bursts, "runs": int(r[1]) if r else None,
            "delays": delays, "env": env_text, "equal": equal,
            "simulated": "not measurements of a BFT system" in text, "path": str(path)}


def problems(t, i):
    """Every reason this pair of files cannot be reported. Empty means reportable."""
    out = []
    if t["simulated"]:
        out.append("the Tailored result comes from the in-process simulator with hardcoded "
                   "latency constants")
    if t["runs"] != i["runs"]:
        out.append(f"run counts differ (Tailored {t['runs']}, Indy {i['runs']}); rerun both with "
                   "the same -Dbench.* settings")
    for name, r in (("Tailored", t), ("Indy", i)):
        if r["runs"] is not None and r["runs"] < PROTOCOL_RUNS:
            out.append(f"{name} used {r['runs']} runs per configuration; the protocol is "
                       f"{PROTOCOL_RUNS} (a debug run?)")
    if not t["simulated"]:
        if t["env"] is None:
            out.append("no environment.txt beside the Tailored RESULTS.md: the run's keys, "
                       "engine and machine cannot be checked")
        elif not re.search(r"system\.communication\.defaultkeys\s*=\s*false", t["env"]):
            out.append("the Tailored cluster did not run with defaultkeys=false: replicas shared "
                       "BFT-SMaRt's public demo key")
    if len(t["delays"]) != 1 or len(i["delays"]) != 1 or t["delays"] != i["delays"]:
        out.append(f"batching delays differ or are not recorded (Tailored "
                   f"{sorted(t['delays']) or 'unknown'} ms, Indy {sorted(i['delays']) or 'unknown'} ms)")
    if t["equal"]:
        if set(t["rows"]) != set(i["rows"]):
            out.append(f"equal-load levels differ (Tailored {sorted(t['rows'])}, "
                       f"Indy {sorted(i['rows'])})")
        for name, r in (("Tailored", t), ("Indy", i)):
            for (mix, rate), row in sorted(r["rows"].items()):
                if row["notes"].startswith("SATURATED"):
                    out.append(f"{name} saturated at {mix} {rate} ops/s; that row measures a queue")
    return out


def ms(us):
    return f"{us / 1000:,.1f}"


def ratio(a, b):
    return f"{a / b:.3g}x" if b else "—"


def burst_rows(label, tb, ib):
    if not (tb and ib):
        return
    print(f"| {label} | 800-revoke drain (ms) | {tb['drain_ms']:,.0f} | {ib['drain_ms']:,.0f} "
          f"| {ratio(tb['drain_ms'], ib['drain_ms'])} |")
    print(f"| {label} | burst p99 (ms) | {ms(tb['p99'])} | {ms(ib['p99'])} "
          f"| {ratio(tb['p99'], ib['p99'])} |")
    if tb["txns"] is not None and ib["txns"] is not None:
        print(f"| {label} | ordered revocation txns per burst (confirmed) "
              f"| {tb['txns']:.1f} ({tb['confirmed']:.1f}) | {ib['txns']:.1f} ({ib['confirmed']:.1f}) "
              f"| — |")


def operating_points(t, i):
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
    burst_rows("burst", t["bursts"].get(None), i["bursts"].get(None))
    print("\nThroughput is the sustained operating point each system reached under the same p99 "
          "target, not a fixed offered load, so latencies here are at DIFFERENT loads; see "
          "--equal-load. Ratios < 1x for latency favour Tailored. The burst drain is a per-batch "
          "cost; the txn row says how many ordered operations it took.")


def equal_load(t, i):
    print("| Mix @ offered load | Metric | Tailored BFT | Indy | Tailored / Indy |")
    print("|---|---|---|---|---|")
    for key in sorted(set(t["rows"]) | set(i["rows"]), key=lambda k: (k[0] != "read-heavy", k[1])):
        mix, rate = key
        label = f"{mix} @ {rate}"
        tr, ir = t["rows"].get(key), i["rows"].get(key)
        if not tr or not ir:
            print(f"| {label} | (missing in {'Tailored' if not tr else 'Indy'}) | | | |")
            continue
        print(f"| {label} | achieved (ops/s) | {tr['tput']:,.0f} ± {tr['ci']:,.0f} "
              f"| {ir['tput']:,.0f} ± {ir['ci']:,.0f} | {ratio(tr['tput'], ir['tput'])} |")
        for p in ("p50", "p95", "p99"):
            print(f"| {label} | {p} latency (ms) | {ms(tr[p])} | {ms(ir[p])} | {ratio(tr[p], ir[p])} |")
        if mix == "bursty-revoke":
            burst_rows(f"{label} burst", t["bursts"].get(rate), i["bursts"].get(rate))
    print("\nBoth systems were offered the same load in every row. Ratios < 1x for latency "
          "favour Tailored.")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    unknown = flags - {"--equal-load", "--allow-unreportable"}
    if unknown or len(args) not in (0, 2):
        sys.exit(__doc__)
    if args:
        t_path, i_path = args
    else:
        equal = "--equal-load" in flags
        t_path, i_path = newest(equal, "tailored-bft", "tailored"), newest(equal, "indy")
    t, i = parse(t_path), parse(i_path)
    if t["equal"] != i["equal"]:
        sys.exit("one file is an equal-load run and the other an operating-point run; "
                 "compare like with like")

    print(f"Tailored: {t['path']}")
    print(f"Indy:     {i['path']}\n")
    found = problems(t, i)
    if found:
        print("NOT REPORTABLE:")
        for p in found:
            print(f"  - {p}")
        print()
        if "--allow-unreportable" not in flags:
            sys.exit("refusing to print the table. Fix the runs, or pass --allow-unreportable "
                     "for a pipeline check.")

    (equal_load if t["equal"] else operating_points)(t, i)


if __name__ == "__main__":
    main()
