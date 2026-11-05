#!/usr/bin/env bash
# What does an Indy validator sync per write? Counts sync syscalls on one validator during a register
# load, so the Tailored arm can be compared with what Indy really does rather than an assumption.
#
#   ./indy-fsync-probe.sh [seconds] [node service]     # with the pool up and a register load running
#
# Prints calls per second. Divide by the committed writes per second and by the batch rate to place it:
# about 1 sync per batch or more means Indy's strict arm is tailored-disk. Far fewer means Indy relies
# on the page cache, and tailored-disk-nosync is the matching comparison.
set -euo pipefail
cd "$(dirname "$0")"
SECONDS_TO_TRACE="${1:-30}"
NODE="${2:-node2}"
CID="$(docker compose ps -q "$NODE")"
[ -n "$CID" ] || { echo "$NODE is not running" >&2; exit 1; }
docker run --rm -i --pid="container:$CID" --cap-add SYS_PTRACE debian:bookworm-slim sh -c "
  apt-get update -qq >/dev/null && apt-get install -y -qq strace >/dev/null
  timeout -s INT $SECONDS_TO_TRACE strace -f -c -e trace=fsync,fdatasync,sync_file_range,msync,sync -p 1 2>&1 || true
"
