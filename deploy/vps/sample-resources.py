#!/usr/bin/env python3
"""
Per-container CPU, memory and disk I/O once a second, for lining up against a run's time series.

    python3 sample-resources.py --out resources.tsv     # until killed; measure-table.sh manages it

Reads cgroup v2 files directly: `docker stats` takes about two seconds per pass, and on a small
server its own CPU use would land in the figures being sampled. With no cgroup v2 this writes one
line saying so and exits, rather than leaving an empty file that reads as "nothing happened".
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

HEADER = "epoch_ms\tcontainer\tcpu_usec\tthrottled_usec\tmemory_bytes\tread_bytes\twrite_bytes\n"


def containers():
    out = subprocess.run(["docker", "ps", "--no-trunc", "--format", "{{.ID}} {{.Names}}"],
                         capture_output=True, text=True, check=False).stdout
    return dict(line.split(maxsplit=1) for line in out.splitlines() if " " in line)


def cgroup_dir(root, container_id):
    """The container's cgroup directory: systemd and cgroupfs drivers name it differently."""
    for candidate in (root / "system.slice" / f"docker-{container_id}.scope", root / "docker" / container_id):
        if candidate.is_dir():
            return candidate
    return None


def read_keyed(path):
    try:
        return {k: int(v) for k, v in (line.split() for line in path.read_text().splitlines())}
    except (OSError, ValueError):
        return {}


def read_int(path):
    try:
        return int(path.read_text().strip())
    except (OSError, ValueError):
        return 0


def read_io(path):
    """Totals across devices from cgroup v2 io.stat."""
    read = write = 0
    try:
        for line in path.read_text().splitlines():
            for field in line.split()[1:]:
                key, _, value = field.partition("=")
                if key == "rbytes":
                    read += int(value)
                elif key == "wbytes":
                    write += int(value)
    except (OSError, ValueError):
        pass
    return read, write


def sample(directory):
    cpu = read_keyed(directory / "cpu.stat")
    read, write = read_io(directory / "io.stat")
    return (cpu.get("usage_usec", 0), cpu.get("throttled_usec", 0),
            read_int(directory / "memory.current"), read, write)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--cgroup-root", default="/sys/fs/cgroup")
    ap.add_argument("--passes", type=int, default=0, help="stop after this many passes (tests)")
    a = ap.parse_args()
    root = Path(a.cgroup_root)
    out = Path(a.out)
    if not (root / "cgroup.controllers").is_file():
        out.write_text(f"# NOT SAMPLED: no cgroup v2 at {root}\n")
        print(f"sample-resources: no cgroup v2 at {root}; not sampling", file=sys.stderr)
        return
    known = {}
    next_refresh = 0.0
    done = 0
    with open(out, "w", buffering=1, encoding="utf-8") as f:
        f.write(HEADER)
        next_tick = time.monotonic()
        while True:
            # Containers come and go during a run (restart trials, recreated clusters).
            if time.monotonic() >= next_refresh:
                known = containers()
                next_refresh = time.monotonic() + 5.0
            now = int(time.time() * 1000)
            for container_id, name in known.items():
                directory = cgroup_dir(root, container_id)
                if directory:
                    fields = "\t".join(str(v) for v in sample(directory))
                    f.write(f"{now}\t{name}\t{fields}\n")
            done += 1
            if a.passes and done >= a.passes:
                return
            next_tick += a.interval
            time.sleep(max(0.0, next_tick - time.monotonic()))


if __name__ == "__main__":
    main()
