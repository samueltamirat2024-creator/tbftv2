#!/usr/bin/env python3
"""
Equal-load levels from a measured Indy table, so no row compares Tailored against Indy's queue.

    eval "$(python3 equal-levels.py results/table/<stamp>/indy.tsv)"     # sets EQ_* for measure-table.sh

Each mix gets about 50% and 80% of the load Indy sustained at its operating point. Fails if a mix is
missing: guessing a level for it would defeat the point.
"""
import argparse
import csv
import re
import sys

MIXES = {"read-heavy": "EQ_READ_HEAVY", "bursty-revoke": "EQ_BURSTY_REVOKE",
         "register": "EQ_REGISTER", "update": "EQ_UPDATE"}


def levels(sustained, fractions):
    """Distinct whole-number loads, lowest first. A level of 0 is not a load."""
    return sorted({max(1, round(sustained * f)) for f in fractions})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("indy_tsv")
    ap.add_argument("--fractions", default="0.5,0.8")
    a = ap.parse_args()
    fractions = [float(x) for x in a.fractions.split(",")]
    found = {}
    with open(a.indy_tsv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            mix = row["name"].rsplit("-- ", 1)[-1].partition(" @ ")[0].strip()
            m = re.search(r"offered (\d+) ops/s", row["notes"])
            if mix in MIXES and m:
                found[mix] = int(m.group(1))
    missing = [m for m in MIXES if m not in found]
    if missing:
        sys.exit(f"{a.indy_tsv} has no operating point for: {', '.join(missing)}")
    for mix, var in MIXES.items():
        print(f"export {var}={','.join(str(l) for l in levels(found[mix], fractions))}")


if __name__ == "__main__":
    main()
