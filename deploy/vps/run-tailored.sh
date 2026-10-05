#!/usr/bin/env bash
# Tailored BFT VDR runner (the tailored-bench service in deploy/vps/docker-compose.yml).
#
# Measurement only: environment capture, then vdr.bench.Bench against the four BFT-SMaRt replica
# containers. No build (the image compiled everything), no simulator gates, no simulated rows.
# Bench settings arrive in JAVA_TOOL_OPTIONS from measure-table.sh. Outputs land in /results.
set -euo pipefail
cd /work

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="/results/$STAMP"
mkdir -p "$OUT"
CONFIG="${VDR_BFTSMART_CONFIG:-/work/config}"

{
  echo "# Environment (Tailored BFT VDR, BFT-SMaRt)"
  echo "captured_utc: $STAMP"
  echo "uname: $(uname -a)"
  echo "runner vCPUs (container view): $(nproc)"
  echo "cpu model: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
  echo "memory: $(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1024 )) MB"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-}"
  echo "jars (sha256):"
  (cd vdr-bftsmart/lib && sha256sum ./*.jar) | sed 's/^/  /'
  for f in hosts.config system.config; do
    echo "--- $f ---"
    grep -v '^\s*#' "$CONFIG/$f" | grep -v '^\s*$' | sed 's/^/  /'
  done
} > "$OUT/environment.txt"

VDR_SKIP_BUILD=1 VDR_BFTSMART_CONFIG="$CONFIG" ./vdr-bftsmart/build.sh bench 4 2>&1 \
  | grep -v '^-- ' | tee "$OUT/bench.log"
cp RESULTS.md "$OUT/RESULTS.md"
cp rows.tsv "$OUT/rows.tsv"
[ -n "${RESULT_TAG:-}" ] && echo "$STAMP" > "/results/latest-$RESULT_TAG"
echo "done. outputs in deploy/vps/results/tailored-bft/$STAMP"
