#!/usr/bin/env python3
"""
Check one fault run's history from vdr.bench.History against its nemesis log.

    python3 check-history.py RUN_DIR [--out RUN_DIR/check.json]

RUN_DIR holds history*.jsonl (one per client process) and optionally nemesis.jsonl. Exit 0 means no
safety violation, 1 means at least one, and 2 means the run cannot support a verdict (no history, no
writes acknowledged, or the client never reached the final checks).

SAFETY (a violation on any read path):
  lost-write       the final read of a DID is older than its last acknowledged write
  phantom-read     a read returned a value never written to that DID
  failed-visible   a read returned the value of a write the system definitely refused
  revocation       a handle reads as not revoked after its revocation was acknowledged
  revoked-count    the registry reports fewer revoked entries than were acknowledged
  no-convergence   replicas report different state digests after healing, or did not answer
  linearizability  on a read level declared strong (STRONG_LEVELS), reads and writes on one DID have
                   no legal total order that respects real time

WHAT EACH READ LEVEL MEETS (reported, not a violation):
  linearizable     per-DID Wing-Gong search, reads at this level plus all writes
  monotonic        per reader process, per DID, reads never go back to an older write
  staleness_ms     how long after a newer write was acknowledged a read still returned older data

A Tier-0 read answers from the last Merkle checkpoint, and Indy reads one node, so either may be
stale by design. check-history.py reports what they do and leaves the claim to the write-up.

Linearizability is local, so checking each DID alone is sound. Writes to one DID are serial in the
client, so their order is their invoke order, and `info` writes may or may not have taken effect. The
search has a budget: running out reports "unknown", never a pass.
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

STRONG_LEVELS = {"tier2"}       # ordered reads: the one path that claims linearizability
SEARCH_BUDGET = 2_000_000
INFINITY = float("inf")


def load_history(run_dir):
    """Every client process's records, in file order. Clocks are comparable (see History.java)."""
    records = []
    for path in sorted(Path(run_dir).glob("history*.jsonl")):
        with open(path, encoding="utf-8") as f:
            for number, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    # A killed client can leave half a line, but only as its last.
                    if f.readline():
                        raise ValueError(f"{path}:{number}: corrupt line before the end of the file")
                    continue
                record["_file"] = path.name
                records.append(record)
    return records


def load_nemesis(run_dir):
    path = Path(run_dir) / "nemesis.jsonl"
    if not path.is_file():
        return []
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def build_ops(records):
    """Pairs invokes with completions. An invoke with no completion is an `info` that never ended."""
    ops, open_ops = [], {}
    for r in records:
        key = (r["_file"], r.get("id"))
        if r["t"] == "invoke":
            op = {"id": r["id"], "file": r["_file"], "p": r["p"], "f": r["f"], "key": r["key"],
                  "value": r["value"], "level": r["level"], "start": r["ts"], "end": INFINITY,
                  "outcome": "info", "final": r["p"] == "final"}
            open_ops[key] = op
            ops.append(op)
        elif r["t"] in ("ok", "fail", "info"):
            op = open_ops.pop(key)
            op["outcome"] = r["t"]
            op["end"] = r["ts"] if r["t"] != "info" else INFINITY
            op["completed_at"] = r["ts"]
            if op["f"] != "write":          # a write's value was fixed when it was invoked
                op["value"] = r["value"]
    return ops


# ------------------------------------------------------------------ linearizability

def linearizable(initial, ops, budget=SEARCH_BUDGET):
    """Wing-Gong search with memoisation on (linearized set, register value).

    Returns (True, None), (False, furthest op id) or (None, None) when the budget runs out. `ok`
    operations must all be placed. `info` writes may be placed anywhere after they began, or not at
    all. Failed and `info` reads say nothing and are not passed in.
    """
    ops = sorted(ops, key=lambda o: o["start"])
    must = [i for i, o in enumerate(ops) if o["outcome"] == "ok"]
    must_mask = sum(1 << i for i in must)
    seen = set()
    stack = [(0, initial)]
    furthest, furthest_count = None, -1
    visited = 0
    while stack:
        done, state = stack.pop()
        if done & must_mask == must_mask:
            return True, None
        if (done, state) in seen:
            continue
        seen.add((done, state))
        visited += 1
        if visited > budget:
            return None, None
        remaining = [i for i in range(len(ops)) if not done >> i & 1]
        horizon = min((ops[i]["end"] for i in remaining if ops[i]["outcome"] == "ok"), default=INFINITY)
        count = bin(done & must_mask).count("1")
        if count > furthest_count:
            furthest_count = count
            pending = [i for i in remaining if ops[i]["outcome"] == "ok"]
            furthest = ops[min(pending, key=lambda i: ops[i]["end"])]["id"] if pending else None
        for i in remaining:
            op = ops[i]
            if op["start"] > horizon:
                break               # sorted by start: nothing later can precede the first to end
            if op["f"] == "write":
                stack.append((done | 1 << i, op["value"]))
            elif op["value"] == state:
                stack.append((done | 1 << i, state))
    return False, furthest


# ------------------------------------------------------------------ the checks

def check_did(did, initial, ops, levels, out):
    writes = [o for o in ops if o["f"] == "write" and o["outcome"] != "fail"]
    writes.sort(key=lambda o: o["start"])
    order = {initial: 0}
    for i, w in enumerate(writes, 1):
        order[w["value"]] = i
    refused = {o["value"] for o in ops if o["f"] == "write" and o["outcome"] == "fail"}
    acked = [i for i, w in enumerate(writes, 1) if w["outcome"] == "ok"]
    last_acked = acked[-1] if acked else 0
    reads = [o for o in ops if o["f"] == "read" and o["outcome"] == "ok"]

    for r in reads:
        if r["value"] in refused:
            out["violations"].append({"kind": "failed-visible", "did": did, "op": r["id"],
                                      "level": r["level"], "value": r["value"]})
        elif r["value"] not in order:
            out["violations"].append({"kind": "phantom-read", "did": did, "op": r["id"],
                                      "level": r["level"], "value": r["value"]})

    finals = [r for r in reads if r["final"]]
    for r in finals:
        if order.get(r["value"], -1) < last_acked:
            out["violations"].append({"kind": "lost-write", "did": did, "op": r["id"],
                                      "acked_write": last_acked, "read_write": order.get(r["value"])})
    if any(o["final"] and o["f"] == "read" and o["outcome"] != "ok" for o in ops):
        out["final_reads_failed"] += 1

    for level in levels:
        at_level = [r for r in reads if r["level"] == level and r["value"] in order]
        stats = out["levels"][level]
        stats["reads"] += len(at_level)

        last_seen = {}
        for r in sorted(at_level, key=lambda o: o["start"]):
            index = order[r["value"]]
            if index < last_seen.get(r["p"], 0):
                stats["monotonic_violations"] += 1
            last_seen[r["p"]] = max(index, last_seen.get(r["p"], 0))

        for r in at_level:
            newer = [w for i, w in enumerate(writes, 1)
                     if i > order[r["value"]] and w["end"] < r["start"]]
            if newer:
                stats["stale_reads"] += 1
                stats["max_staleness_ms"] = max(stats["max_staleness_ms"],
                                                (r["start"] - min(w["end"] for w in newer)) / 1000.0)

        history = writes + at_level
        verdict, witness = linearizable(initial, history)
        if verdict is True:
            stats["dids_linearizable"] += 1
        elif verdict is None:
            stats["dids_unknown"] += 1
        else:
            stats["dids_not_linearizable"] += 1
            if stats["first_witness"] is None:
                stats["first_witness"] = {"did": did, "op": witness}
            if level in STRONG_LEVELS:
                out["violations"].append({"kind": "linearizability", "did": did, "level": level,
                                          "op": witness})


def check_revocations(ops, out):
    acked = {}
    for o in ops:
        if o["f"] == "revoke" and o["outcome"] == "ok":
            acked[o["value"]] = o["end"]
    out["revocations_acked"] = len(acked)
    for o in ops:
        if o["f"] == "revstatus" and o["outcome"] == "ok" and o["value"] == "false" \
                and o["key"] is not None:
            handle = o.get("handle")
            if handle in acked and o["start"] > acked[handle]:
                out["violations"].append({"kind": "revocation", "registry": o["key"],
                                          "handle": handle, "op": o["id"]})


def phases(events, start, end):
    """(name, from, to) from the nemesis log. Times are microseconds."""
    inject = next((e["ts_us"] for e in events if e["event"] == "inject"), None)
    heal = next((e["ts_us"] for e in events if e["event"] == "heal"), None)
    if inject is None:
        return [("whole run", start, end)]
    if heal is None:
        return [("healthy", start, inject), ("fault", inject, end)]
    return [("healthy", start, inject), ("fault", inject, heal), ("healed", heal, end)]


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(p / 100.0 * len(s) + 0.999999) - 1))]


def availability(ops, events, meta, out):
    """Longest gap in acknowledged writes, recovery after healing, and per-phase p99."""
    writes = [o for o in ops if o["f"] == "write" and not o["final"]]
    if not writes:
        return
    start, end = min(o["start"] for o in writes), max(o["start"] for o in writes)
    acks = sorted(o["end"] for o in writes if o["outcome"] == "ok")
    edges = [start] + acks + [end]
    out["longest_write_gap_s"] = round(max(b - a for a, b in zip(edges, edges[1:])) / 1e6, 3)

    out["phase_write_p99_ms"] = {}
    out["phase_read_p99_ms"] = {}
    reads = [o for o in ops if o["f"] == "read" and not o["final"] and o["outcome"] == "ok"]
    for name, a, b in phases(events, start, end):
        w = [(o["end"] - o["start"]) / 1000 for o in writes if o["outcome"] == "ok" and a <= o["start"] < b]
        r = [(o["end"] - o["start"]) / 1000 for o in reads if a <= o["start"] < b]
        p_w, p_r = percentile(w, 99), percentile(r, 99)
        out["phase_write_p99_ms"][name] = round(p_w, 3) if p_w is not None else None
        out["phase_read_p99_ms"][name] = round(p_r, 3) if p_r is not None else None

    heal = next((e["ts_us"] for e in events if e["event"] == "heal"), None)
    period = meta.get("write_period_ms"), meta.get("writers")
    if heal is None or None in period:
        return
    offered_per_s = period[1] * 1000.0 / period[0]
    # A window starting at t counts as recovered when 10 s from t holds 95% of the offered load.
    window = 10_000_000
    needed = 0.95 * offered_per_s * window / 1e6
    t = heal
    while t + window <= end:
        if sum(1 for a in acks if t <= a < t + window) >= needed:
            out["recovery_s"] = round((t - heal) / 1e6, 3)
            return
        t += 1_000_000
    out["recovery_s"] = None        # never held 95% before the run ended


def fault_had_effect(events, out):
    """A 2/2 split leaves no quorum at n = 4, so writes must stop. If they did not, the rules were
    present but the partition was not, and the run says nothing about how the system handles one."""
    times = {e["event"]: e["ts_us"] for e in events if e["event"] in ("inject", "heal")}
    gap = out.get("longest_write_gap_s")
    if out["scenario"] != "partition-2-2" or len(times) < 2 or gap is None:
        return
    fault_s = (times["heal"] - times["inject"]) / 1e6
    if gap < fault_s / 2:
        out["unverifiable"].append(f"writes kept flowing through a {fault_s:.0f} s partition "
                                   f"(longest gap {gap} s), so it did not take effect")


def check(run_dir):
    records = load_history(run_dir)
    events = load_nemesis(run_dir)
    out = {"run": str(run_dir), "violations": [], "levels": {}, "final_reads_failed": 0,
           "unverifiable": [], "scenario": next((e.get("scenario") for e in events), None),
           "system": next((e.get("system") for e in events), None)}
    starts = [r for r in records if r["t"] == "start"]
    if not starts:
        out["unverifiable"].append("no history records")
        return out
    meta = starts[-1]
    levels = meta["levels"]
    out["levels"] = {l: {"reads": 0, "stale_reads": 0, "max_staleness_ms": 0.0,
                         "monotonic_violations": 0, "dids_linearizable": 0,
                         "dids_not_linearizable": 0, "dids_unknown": 0, "first_witness": None}
                     for l in levels}

    initial = {r["did"]: r["value"] for r in records if r["t"] == "init"}
    ops = build_ops(records)
    # revstatus carries its handle in the invoke's value, which the completion overwrites.
    handles = {(r["_file"], r["id"]): r["value"] for r in records
               if r["t"] == "invoke" and r["f"] == "revstatus"}
    for o in ops:
        if o["f"] == "revstatus":
            o["handle"] = handles[(o["file"], o["id"])]

    by_did = defaultdict(list)
    for o in ops:
        if o["f"] in ("read", "write"):
            by_did[o["key"]].append(o)
    for did, did_ops in by_did.items():
        if did not in initial:
            out["unverifiable"].append(f"{did} has no initial value")
            continue
        check_did(did, initial[did], did_ops, levels, out)
    check_revocations(ops, out)

    out["writes_acked"] = sum(1 for o in ops if o["f"] == "write" and o["outcome"] == "ok")
    out["writes_refused"] = sum(1 for o in ops if o["f"] == "write" and o["outcome"] == "fail")
    out["writes_unknown"] = sum(1 for o in ops if o["f"] == "write" and o["outcome"] == "info")
    if out["writes_acked"] == 0:
        out["unverifiable"].append("no write was acknowledged, so nothing can have been lost")
    if out["final_reads_failed"]:
        out["unverifiable"].append(f"{out['final_reads_failed']} DIDs never answered the final read")

    ended = [r for r in records if r["t"] == "end"]
    if not ended:
        out["unverifiable"].append("the last client never reached the end of its final checks")

    counts = [r for r in records if r["t"] == "revoked_count"]
    if counts and counts[-1]["count"] >= 0:
        acked = out["revocations_acked"]
        if counts[-1]["count"] < acked:
            out["violations"].append({"kind": "revoked-count", "reported": counts[-1]["count"],
                                      "acked": acked})

    digests = [r for r in records if r["t"] == "digests"]
    node_digests = Path(run_dir) / "node-digests.json"
    reported = digests[-1]["digests"] if digests else {}
    if not reported and node_digests.is_file():
        reported = json.loads(node_digests.read_text(encoding="utf-8"))
    if not reported:
        out["convergence"] = "not checked"
        out["unverifiable"].append("no state digests: replica convergence was not checked")
    elif len(set(reported.values())) == 1 and "no-answer" not in reported.values():
        out["convergence"] = "converged"
    else:
        out["convergence"] = "diverged or silent"
        out["violations"].append({"kind": "no-convergence", "digests": reported})

    availability(ops, events, meta, out)
    fault_had_effect(events, out)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out")
    a = ap.parse_args()
    result = check(a.run)
    text = json.dumps(result, indent=1, sort_keys=True)
    Path(a.out or Path(a.run) / "check.json").write_text(text + "\n", encoding="utf-8")
    for level, s in result["levels"].items():
        print(f"{level}: {s['reads']} reads, linearizable DIDs {s['dids_linearizable']}, "
              f"not {s['dids_not_linearizable']}, unknown {s['dids_unknown']}; stale {s['stale_reads']} "
              f"(max {s['max_staleness_ms']:.0f} ms); monotonic violations {s['monotonic_violations']}")
    print(f"writes: {result.get('writes_acked', 0)} acked, {result.get('writes_refused', 0)} refused, "
          f"{result.get('writes_unknown', 0)} unknown; convergence: {result.get('convergence')}; "
          f"longest write gap {result.get('longest_write_gap_s')} s; recovery {result.get('recovery_s')} s")
    for v in result["violations"][:20]:
        print("VIOLATION", json.dumps(v))
    for u in result["unverifiable"]:
        print("UNVERIFIABLE", u)
    if result["violations"]:
        sys.exit(1)
    if result["unverifiable"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
