#!/usr/bin/env python3
"""What the disk arm may be called, and where equal-load levels come from.

    python3 test_arms.py
"""
import csv
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


table = load("make_table", "make-table.py")
levels = load("equal_levels", "equal-levels.py")
COLUMNS = ["name", "throughput", "ci95", "p50_us", "p95_us", "p99_us", "runs", "independent_runs",
           "steady_ms", "warmup_ms", "notes"]


def write_rows(path, system, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, delimiter="\t")
        w.writeheader()
        for mix, offered, tput, p99 in rows:
            w.writerow({"name": f"{system} -- {mix}", "throughput": tput, "ci95": 1, "p50_us": 1000,
                        "p95_us": 2000, "p99_us": p99, "runs": 10, "independent_runs": 1,
                        "steady_ms": 60000, "warmup_ms": 60000, "notes": f"offered {offered} ops/s"})


def build(d, verdict):
    d = Path(d)
    write_rows(d / "t.tsv", "Tailored BFT VDR", [("register", 1000, 1000, 10000)])
    write_rows(d / "disk.tsv", "Tailored BFT VDR", [("register", 500, 500, 40000)])
    write_rows(d / "indy.tsv", "Hyperledger Indy", [("register", 100, 100, 200000)])
    args = [sys.executable, str(HERE / "make-table.py"), "--tailored", str(d / "t.tsv"), "--indy", str(d / "indy.tsv"),
            "--tailored-disk", str(d / "disk.tsv")]
    if verdict is not None:
        (d / "v.txt").write_text(f"verdict: {verdict}\nmore\n")
        args += ["--durability", str(d / "v.txt")]
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


class DiskArmName(unittest.TestCase):
    def test_gate_failed(self):
        with tempfile.TemporaryDirectory() as d:
            out = build(d, "FAIL")
        self.assertIn("sync disk log, not recoverable", out)
        self.assertNotIn("durable,", out)

    def test_gate_never_ran_is_not_durable(self):
        with tempfile.TemporaryDirectory() as d:
            out = build(d, None)
        self.assertIn("durability gate not run", out)
        self.assertNotIn("durable,", out)

    def test_only_an_exact_pass_is_durable(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIn("(durable, sync disk log)", build(d, "PASS"))
        with tempfile.TemporaryDirectory() as d:
            self.assertNotIn("durable,", build(d, "PASS with 3 failures"))

    def test_cost_table_ratios_are_against_in_memory(self):
        with tempfile.TemporaryDirectory() as d:
            out = build(d, "FAIL")
        self.assertIn("## Cost of disk logging", out)
        row = next(l for l in out.splitlines() if l.startswith("| register | Tailored BFT VDR (sync disk log"))
        self.assertIn("0.5x", row)
        self.assertIn("4x", row)

    def test_no_disk_arm_no_cost_table(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            write_rows(d / "t.tsv", "Tailored BFT VDR", [("register", 1000, 1000, 10000)])
            out = subprocess.run([sys.executable, str(HERE / "make-table.py"), "--tailored", str(d / "t.tsv")],
                                 capture_output=True, text=True, check=True).stdout
        self.assertNotIn("Cost of disk logging", out)


class EqualLevels(unittest.TestCase):
    def run_tool(self, rows):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "indy.tsv"
            write_rows(path, "Hyperledger Indy", rows)
            return subprocess.run([sys.executable, str(HERE / "equal-levels.py"), str(path)],
                                  capture_output=True, text=True)

    def test_from_measured_knee(self):
        r = self.run_tool([("read-heavy", 200, 199, 1), ("bursty-revoke", 50, 49, 1),
                           ("register", 100, 99, 1), ("update", 75, 74, 1)])
        self.assertEqual(r.returncode, 0)
        self.assertIn("export EQ_READ_HEAVY=100,160", r.stdout)
        self.assertIn("export EQ_BURSTY_REVOKE=25,40", r.stdout)
        self.assertIn("export EQ_UPDATE=38,60", r.stdout)

    def test_missing_mix_refuses(self):
        r = self.run_tool([("register", 100, 99, 1)])
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("read-heavy", r.stderr)

    def test_low_knee_never_gives_zero_and_dedupes(self):
        self.assertEqual(levels.levels(1, [0.5, 0.8]), [1])


if __name__ == "__main__":
    unittest.main()
