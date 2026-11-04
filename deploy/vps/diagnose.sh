#!/usr/bin/env bash
# Isolate the cause of the Tailored BFT VDR's write tail growth and start-up stall.
#
#   DIAG_RATE_REGISTER=500 DIAG_RATE_UPDATE=500 ./diagnose.sh     # every configuration below
#   DIAG_CONFIGS="baseline cp5k" ... ./diagnose.sh                # some of them
#   DIAG_PROFILE=quick DIAG_RUNS=2 ... ./diagnose.sh              # pilot; never for reporting
#   EPSILON_HEAP=6g ... ./diagnose.sh                             # also run the no-GC control
#   python3 diagnose.py results/diagnose/<stamp>                  # then the verdicts
#
# Choose rates each mix sustains, near the top of its operating range: past the knee, every
# configuration measures the same queue. Each configuration is a full measure-table.sh run at equal
# load on the same rates, with independent repetitions, and differs from baseline in one thing:
#
#   baseline      as measured, with timing on
#   cp5k, cp20k   BFT-SMaRt checkpoint_period 5000, 20000 (baseline 1000): snapshot cost
#   merkle10k     Merkle checkpoint every 10000 ops (baseline 2000)
#   heap-young    -Xms1g -Xmx1g -Xmn512m (baseline -Xms512m -Xmx1g): GC frequency and promotion
#   epsilon       no collector at all, EPSILON_HEAP of heap: run only if EPSILON_HEAP is set, since
#                 the JVM dies when it runs out. Needs roughly that much RAM per replica.
#   warm-replicas a throwaway load from another JVM first: replicas warm, generator cold
#   warm-both     the throwaway load from the measuring JVM: replicas and generator warm
#   soak          register for 30 minutes, 3 repetitions: does the tail grow with state?
#
# Timing and GC logging stay on in baseline too, so their small cost is common to every arm. Reported
# tables come from runs without them.
set -euo pipefail
cd "$(dirname "$0")"

: "${DIAG_RATE_REGISTER:?set DIAG_RATE_REGISTER: a rate the register mix sustains (ops/s)}"
: "${DIAG_RATE_UPDATE:?set DIAG_RATE_UPDATE: a rate the update mix sustains (ops/s)}"
if [ -f .env ] && grep -q '^REPLICA_JAVA_OPTS=' .env; then
  echo "REPLICA_JAVA_OPTS is set in .env and would override these configurations; remove it first" >&2
  exit 2
fi

DIAG_PROFILE="${DIAG_PROFILE:-full}"
DEFAULT_CONFIGS="baseline cp5k cp20k merkle10k heap-young warm-replicas warm-both soak"
[ -n "${EPSILON_HEAP:-}" ] && DEFAULT_CONFIGS="$DEFAULT_CONFIGS epsilon"
DIAG_CONFIGS="${DIAG_CONFIGS:-$DEFAULT_CONFIGS}"
PRELOAD_MS="${DIAG_PRELOAD_MS:-60000}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="results/diagnose/$STAMP"
mkdir -p "$DIR"
BASE_CONFIG="$(cd ../../config && pwd)"
LOGGING="-Xlog:gc,safepoint:stdout:time,level,tags -Dvdr.timing=true"
HEAP="-Xms512m -Xmx1g"

# The manifest diagnose.py reads, written as each configuration finishes.
printf 'config\ttable_dir\tregister_rate\tupdate_rate\n' > "$DIR/configs.tsv"

for config in $DIAG_CONFIGS; do
  overlay="$PWD/$DIR/$config/config"
  mkdir -p "$(dirname "$overlay")"
  cp -R "$BASE_CONFIG" "$overlay"
  replica_heap="$HEAP"; replica_extra=""; bench_extra=""; preload=""; runs="${DIAG_RUNS:-}"; steady=""; mixes="register update"
  case "$config" in
    baseline) ;;
    cp5k|cp20k)
      period=5000; [ "$config" = cp20k ] && period=20000
      sed -i.bak -E "s/^(system\.totalordermulticast\.checkpoint_period) *=.*/\1 = $period/" "$overlay/system.config"
      rm -f "$overlay/system.config.bak"
      grep -q "checkpoint_period = $period" "$overlay/system.config" || { echo "$config: overlay edit failed" >&2; exit 1; }
      ;;
    merkle10k)     replica_extra="-Dvdr.checkpointInterval=10000" ;;
    heap-young)    replica_heap="-Xms1g -Xmx1g -Xmn512m" ;;
    epsilon)
      : "${EPSILON_HEAP:?epsilon needs EPSILON_HEAP}"
      replica_heap="-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms$EPSILON_HEAP -Xmx$EPSILON_HEAP"
      ;;
    warm-replicas) preload="$PRELOAD_MS" ;;
    warm-both)     bench_extra="-Dbench.preloadMs=$PRELOAD_MS" ;;
    soak)          mixes="register"; runs="${DIAG_RUNS:-3}"; steady="${DIAG_SOAK_MS:-1800000}" ;;
    *) echo "unknown configuration '$config'" >&2; exit 2 ;;
  esac

  label="diag-$STAMP-$config"
  echo "== $config"
  VDR_CONFIG_DIR="$overlay" \
  REPLICA_JAVA_OPTS="$replica_heap $replica_extra $LOGGING" \
  BENCH_EXTRA_FLAGS="$bench_extra -Xlog:gc,safepoint:stdout:time,level,tags -Dvdr.timing=true" \
  PRELOAD_SEPARATE_MS="$preload" RUNS_OVERRIDE="$runs" STEADY_OVERRIDE="$steady" OUT_LABEL="$label" \
  SYSTEMS=tailored MIXES="$mixes" EQUAL_LOAD=1 REPEAT_FRESH=1 PROFILE="$DIAG_PROFILE" \
  EQ_REGISTER="$DIAG_RATE_REGISTER" EQ_UPDATE="$DIAG_RATE_UPDATE" \
    ./measure-table.sh
  printf '%s\t%s\t%s\t%s\n' "$config" "$(ls -d results/table/*-"$label" | tail -1)" \
    "$DIAG_RATE_REGISTER" "$DIAG_RATE_UPDATE" >> "$DIR/configs.tsv"
done
echo "== done: python3 diagnose.py $DIR"
