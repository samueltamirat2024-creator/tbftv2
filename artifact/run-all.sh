#!/usr/bin/env bash
#
# Tailored BFT VDR — artifact reproduction.
#
# One command runs everything an evaluator needs: environment capture, build, the
# milestone gates, and the evaluation harness. Every output lands in a single
# timestamped directory so a run can be archived or diffed against ours.
#
#   ./artifact/run-all.sh            # n = 4 (f = 1), the default configuration
#   ./artifact/run-all.sh 7          # n = 7 (f = 2)
#   ./artifact/run-all.sh 4 quick    # gates only; skips the ~4 minute benchmark
#
# Requires Java 21+. Nothing else: no Maven, no network, no container runtime.

set -euo pipefail

N="${1:-4}"
MODE="${2:-full}"

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTDIR="$REPO_ROOT/artifact/results/$STAMP-n$N"
mkdir -p "$OUTDIR"

echo "Tailored BFT VDR — artifact run"
# Which L1 engine this run measures. VDR_REPLICATION=bftsmart points the harness at the real
# cluster; anything else (the default) uses the in-process simulator, whose numbers are harness
# validation only. The choice is printed, recorded in environment.txt, and drives whether
# RESULTS.md carries the provenance warning.
REPLICATION="${VDR_REPLICATION:-simulated}"
BFTSMART_CONFIG="${VDR_BFTSMART_CONFIG:-config}"
JAVA_FLAGS="-Dvdr.replication=$REPLICATION -Dvdr.bftsmart.config=$BFTSMART_CONFIG"
if [ "$REPLICATION" = "bftsmart" ]; then
  ./vdr-bftsmart/build.sh >/dev/null
  BENCH_CP="build/classes:build/bftsmart:$(find vdr-bftsmart/lib -name '*.jar' 2>/dev/null | tr '\n' ':')"
else
  BENCH_CP="build/classes"
fi

echo "  n            = $N"
echo "  mode         = $MODE"
echo "  replication  = $REPLICATION"
echo "  output       = artifact/results/$STAMP-n$N"
echo

# ---------------------------------------------------------------- 0. environment

# Captured first and unconditionally. A result without the machine it came from is
# not reproducible, and plan §12.1 fixes the metric definitions but not the hardware.
{
  echo "# Environment"
  echo
  echo "captured_utc: $STAMP"
  echo "uname: $(uname -a)"
  echo
  echo "## Java"
  java -version 2>&1 | sed 's/^/  /'
  echo
  echo "## Replication layer"
  echo "  engine: $REPLICATION"
  if [ "$REPLICATION" = "bftsmart" ]; then
    echo "  bft-smart jars (sha256):"
    (cd vdr-bftsmart/lib 2>/dev/null && { sha256sum ./*.jar 2>/dev/null || shasum -a 256 ./*.jar 2>/dev/null; }) | sed 's/^/    /' \
      || echo "    (none found)"
    # Replica logging changes throughput: with no logback config, BFT-SMaRt logs at DEBUG on the
    # ordering path. Recorded so runs before and after config/logback.xml are never compared.
    echo "  logging: $(if [ -f "$BFTSMART_CONFIG/logback.xml" ]; then
      echo "$BFTSMART_CONFIG/logback.xml, bftsmart level $(sed -nE 's/.*<logger name="bftsmart" level="([A-Z]+)".*/\1/p' "$BFTSMART_CONFIG/logback.xml")"
    else
      echo "NO logback.xml -- logback default, DEBUG on every request"
    fi)"
    for f in hosts.config system.config; do
      if [ -f "$BFTSMART_CONFIG/$f" ]; then
        echo
        echo "  --- $f ---"
        grep -v '^\s*#' "$BFTSMART_CONFIG/$f" | grep -v '^\s*$' | sed 's/^/    /'
      fi
    done
  else
    echo "  NOTE: the simulated ordering layer charges two calibrated constants"
    echo "        (3000 us per consensus instance, 300 us per replica round trip)."
    echo "        Numbers from this run are harness validation, not results."
  fi
  echo
  echo "## CPU / memory"
  if [ "$(uname -s)" = "Darwin" ]; then
    echo "  model:   $(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
    echo "  cores:   $(sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
    echo "  memory:  $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 )) MB"
  else
    echo "  model:   $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ //' || echo unknown)"
    echo "  cores:   $(nproc 2>/dev/null || echo unknown)"
    echo "  memory:  $(( $(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo 0) / 1024 )) MB"
  fi
  echo
  echo "## Source revision"
  if git -C "$REPO_ROOT" rev-parse --short HEAD >/dev/null 2>&1; then
    echo "  git:     $(git -C "$REPO_ROOT" rev-parse HEAD)"
    echo "  dirty:   $(git -C "$REPO_ROOT" status --porcelain | wc -l | tr -d ' ') modified files"
  else
    echo "  git:     not a repository (distributed as an archive)"
  fi
} > "$OUTDIR/environment.txt"

echo "[1/3] environment captured"

# ---------------------------------------------------------------------- 1. build

if ! ./build.sh > "$OUTDIR/build.log" 2>&1; then
  echo "BUILD FAILED — see $OUTDIR/build.log" >&2
  tail -20 "$OUTDIR/build.log" >&2
  exit 1
fi
echo "[2/3] build OK"

# ---------------------------------------------------------------------- 2. gates

# The milestone go/no-go gates of plan §11, as executable assertions. These are the
# functional claims: they must pass on any machine, and they are what an evaluator
# checks first.
#
# vdr.test.Gates builds a SimulatedCluster in every gate and never touches BFT-SMaRt, whatever
# -Dvdr.replication says. On a real-engine run it is therefore filed as gates-simulator.log,
# without the engine flags, so it cannot pass for a real-engine result. The real-engine gates
# (M3, M4, M6) need container control this runner does not have: vdr-bftsmart/real-gates.sh.
if [ "$REPLICATION" = "bftsmart" ]; then
  GATES_LOG="gates-simulator.log"
  GATES_FLAGS=""
else
  GATES_LOG="gates.log"
  GATES_FLAGS="$JAVA_FLAGS"
fi
set +e
java -cp "$BENCH_CP" $GATES_FLAGS vdr.test.Gates > "$OUTDIR/$GATES_LOG" 2>&1
GATES_STATUS=$?
set -e

GATES_LINE="$(grep -E '^passed [0-9]+' "$OUTDIR/$GATES_LOG" || echo 'no summary line')"
if [ "$REPLICATION" = "bftsmart" ]; then
  echo "[3/3] gates (SIMULATOR only, not this engine): $GATES_LINE"
  echo "      real-engine M3/M4/M6: run ./vdr-bftsmart/real-gates.sh on the host"
else
  echo "[3/3] gates: $GATES_LINE"
fi

if [ "$GATES_STATUS" -ne 0 ]; then
  echo
  echo "GATES FAILED. This is a real result, not a setup problem — see $OUTDIR/$GATES_LOG" >&2
  echo "Report it with environment.txt attached." >&2
  exit "$GATES_STATUS"
fi

# ------------------------------------------------------------------ 3. benchmark

if [ "$MODE" = "quick" ]; then
  echo
  echo "quick mode: benchmark skipped"
else
  echo
  echo "running the evaluation harness (~4 minutes; 10 runs per configuration)"
  java -cp "$BENCH_CP" $JAVA_FLAGS vdr.bench.Bench "$N" 2>&1 | tee "$OUTDIR/bench.log"
  [ -f RESULTS.md ] && cp RESULTS.md "$OUTDIR/RESULTS.md"
fi

# --------------------------------------------------------------------- 4. summary

cat > "$OUTDIR/README.md" <<EOF
# Artifact run $STAMP (n = $N)

| File | Contents |
|---|---|
| environment.txt | machine, JVM and source revision this run came from |
| build.log | compiler output |
| $GATES_LOG | milestone go/no-go gates on the SIMULATOR ($GATES_LINE)$( [ "$REPLICATION" = "bftsmart" ] && echo "; not evidence about BFT-SMaRt — real-engine gates come from vdr-bftsmart/real-gates.sh" ) |
| bench.log | full harness output including the offered-load sweep |
| RESULTS.md | the evaluation matrix, with its provenance warning |

Read \`artifact/CLAIMS.md\` for which of the paper's claims each of these supports,
and — more to the point — which of them this artifact cannot support on a single
machine.
EOF

echo
echo "done. outputs in artifact/results/$STAMP-n$N"
