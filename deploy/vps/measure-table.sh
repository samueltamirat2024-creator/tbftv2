#!/usr/bin/env bash
# ONE command for the four-row comparison table, on one virtual server with 4 nodes x 1 vCPU.
#
#   ./measure-table.sh                     # both systems, PROFILE=standard (~2 h on 4 vCPU)
#   PROFILE=quick ./measure-table.sh       # pipeline check, ~40 min; numbers NOT reportable
#   PROFILE=full  ./measure-table.sh       # the paper's protocol: 60 s / 60 s / 10 runs (many hours)
#   SYSTEMS=tailored ./measure-table.sh    # only one system (tailored | indy)
#
# It measures exactly these rows and nothing else:
#
#   Tailored BFT VDR -- read-heavy            Hyperledger Indy baseline -- read-heavy
#   Tailored BFT VDR -- bursty-revoke         Hyperledger Indy baseline -- bursty-revoke
#
# and writes results/table/<UTC stamp>/TABLE.md (plus each system's full harness output).
#
# WHAT IS HELD EQUAL
#   - Nodes: four Indy validators, or four BFT-SMaRt replicas, each capped at 1 vCPU (docker
#     --cpus=1) and pinned to its own core (--cpuset-cpus 0, 1, 2, 3). Never both systems at once.
#   - Load generator: the same vdr.bench.Bench, same open-loop schedule, same windows, same runs,
#     same p99 <= 500 ms admission rule, same 50 ms revocation batching delay, same CPU budget.
#   - State: a freshly started cluster / freshly wiped ledger for EACH system and EACH mix; within
#     a mix every run writes fresh DIDs and takes a fresh revocation registry. (BFT-SMaRt's state
#     snapshot, cut every checkpoint_period instances, costs O(state); a mix measured on a cluster
#     that already carries the other mix's history would pay for that history.)
#
# THE ONE THING A 4 vCPU SERVER CANNOT HOLD APART
#   With 4 nodes on 4 vCPUs there is no spare core for the load generator, so it shares cores 0-3
#   with the nodes (on both systems alike). On a server with more vCPUs this script still gives
#   each node exactly 1 vCPU and the generator gets the remaining cores to itself -- better if you
#   can: e.g. an 8 vCPU server -> nodes on 0-3, generator on 4-7.
set -euo pipefail
cd "$(dirname "$0")"

PROFILE="${PROFILE:-standard}"
SYSTEMS="${SYSTEMS:-tailored indy}"
DELAY_MS="${VDR_MAX_BATCH_DELAY_MS:-50}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="results/table/$STAMP"
mkdir -p "$OUT" results/tailored-bft results/indy
LOG="$OUT/measure.log"
say() { echo "$*" | tee -a "$LOG"; }

# ------------------------------------------------------------------ protocol profiles
#   sweep  : short runs that only locate the knee (schedule kept AND p99 <= 500 ms)
#   measure: the reported runs at the chosen level, re-measured and stepped down if needed
case "$PROFILE" in
  quick)    SWEEP_W=5000;  SWEEP_S=15000; WARM=10000; STEADY=30000; RUNS=3;  REFINE=2 ;;
  standard) SWEEP_W=15000; SWEEP_S=30000; WARM=60000; STEADY=60000; RUNS=5;  REFINE=3 ;;
  full)     SWEEP_W=60000; SWEEP_S=60000; WARM=60000; STEADY=60000; RUNS=10; REFINE=3 ;;
  *) echo "PROFILE must be quick, standard or full" >&2; exit 2 ;;
esac
# Coarse levels; the knee is then bisected REFINE times. Indy's knees on earlier hardware were
# 50-75 (bursty-revoke) and 200-250 (read-heavy) ops/s; Tailored's were in the thousands.
TAILORED_LEVELS="${TAILORED_LEVELS:-100,250,500,1000,1500,2000,3000,4000,6000,8000}"
INDY_LEVELS="${INDY_LEVELS:-10,25,50,75,100,150,200,300,400,600}"

BENCH_FLAGS="-Xms1g -Xmx2g -Dbench.warmupMs=$WARM -Dbench.steadyMs=$STEADY -Dbench.runs=$RUNS \
 -Dbench.sweepWarmupMs=$SWEEP_W -Dbench.sweepSteadyMs=$SWEEP_S -Dbench.refine=$REFINE"
MIXES="${MIXES:-read-heavy bursty-revoke}"

COMPOSE=(docker compose --profile bftsmart --profile indy)
REPLICAS=(vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3)
NODES=(node1 node2 node3 node4)

# ------------------------------------------------------------------ preflight
say "== measure-table $STAMP: profile $PROFILE, systems: $SYSTEMS"
[ "$(uname -m)" = "x86_64" ] || say "WARNING: $(uname -m) host; the Indy (von-network) images are amd64 only"
docker compose version >/dev/null 2>&1 || { echo "docker compose is required" >&2; exit 1; }
TOTAL_CPUS="$(nproc)"
[ "$TOTAL_CPUS" -ge 4 ] || { echo "need at least 4 vCPUs (have $TOTAL_CPUS)" >&2; exit 1; }
if [ -z "${TMUX:-}${STY:-}" ] && [ -t 0 ] && [ "${NO_TMUX_CHECK:-0}" != "1" ]; then
  echo "NOTE: not inside tmux/screen; a dropped SSH session kills a run that takes hours."
  echo "      tmux new -s bench   (then re-run)    or    NO_TMUX_CHECK=1 $0"
  read -r -p "continue anyway? [y/N] " reply
  [ "$reply" = "y" ] || exit 1
fi

# Exactly 1 vCPU per node, pinned to cores 0..3, whatever the server size.
NODE_CPUS=1 TAILORED_N=4 ./cpu-plan.sh | tee -a "$LOG"
# shellcheck disable=SC1091
set -a; . ./.env; set +a
if [ "$TOTAL_CPUS" -gt 4 ]; then
  say "load generator: cores $AUX_CPUSET (dedicated), $BENCH_CPUS vCPU"
else
  say "load generator: shares cores 0-3 with the nodes ($BENCH_CPUS vCPU cap) -- same on both systems"
fi

say "== building images (first time: several minutes)"
"${COMPOSE[@]}" build >> "$LOG" 2>&1 || { echo "image build failed; see $LOG" >&2; exit 1; }
docker run --rm vdr-bench sh -c 'ls vdr-bftsmart/lib | grep -q bcprov && echo "Ed25519: BouncyCastle" || echo "Ed25519: JDK (bcprov not fetched)"' | tee -a "$LOG"

# BFT-SMaRt replica keys (system.communication.defaultkeys=false): generated on this machine,
# never shipped in the image.
CFG="$(cd ../../config && pwd)"
if grep -Eq '^system\.communication\.defaultkeys *= *false' "$CFG/system.config"; then
  for i in 0 1 2 3; do
    if [ ! -f "$CFG/keys/privatekey$i" ] || [ ! -f "$CFG/keys/publickey$i" ]; then
      say "generating BFT-SMaRt replica keys in config/keys/"
      docker run --rm -v "$CFG":/work/config vdr-bench ./vdr-bftsmart/gen-keys.sh >> "$LOG" 2>&1
      break
    fi
  done
fi

# ------------------------------------------------------------------ helpers
replica_log() { "${COMPOSE[@]}" logs --no-color "$1" 2>/dev/null; }

handshakes_complete() {
  local i j text
  for i in 0 1 2 3; do
    text="$(replica_log "vdr-replica-$i")"
    grep -qF "VDR replica $i ready" <<<"$text" || return 1
    for j in 0 1 2 3; do
      [ "$i" = "$j" ] && continue
      grep -qF "Diffie-Hellman complete with $j" <<<"$text" || return 1
    done
  done
}

# Fresh cluster (empty in-memory state). BFT-SMaRt 1.2 sometimes fails one replica's startup key
# exchange and that replica silently sits out (vdr-bftsmart/real-gates.sh); never measure that.
cluster_up() {
  local attempt deadline
  for attempt in 1 2 3; do
    "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1 || true
    "${COMPOSE[@]}" up -d --force-recreate "${REPLICAS[@]}" >> "$LOG" 2>&1
    deadline=$((SECONDS + 180))
    until handshakes_complete; do
      [ $SECONDS -gt $deadline ] && break
      sleep 3
    done
    if handshakes_complete; then
      { replica_log vdr-replica-0 | grep "starting:" | head -1 | tee -a "$LOG"; } || true
      return 0
    fi
    say "  replica startup handshake incomplete (attempt $attempt); recreating the cluster"
  done
  return 1
}

save_replica_logs() {    # save_replica_logs <label>
  mkdir -p "$OUT/replica-logs/$1"
  for r in "${REPLICAS[@]}"; do replica_log "$r" > "$OUT/replica-logs/$1/$r.log"; done
}

latest_result() {        # latest_result <results subdir> <tag> -> path of that run's folder
  local tagfile="results/$1/latest-$2"
  [ -f "$tagfile" ] || return 1
  echo "results/$1/$(cat "$tagfile")"
}

# Appends a run's rows.tsv (minus the header after the first) to a per-system file.
append_rows() {          # append_rows <rows.tsv> <dest>
  if [ -s "$2" ]; then tail -n +2 "$1" >> "$2"; else cp "$1" "$2"; fi
}

# ------------------------------------------------------------------ Tailored BFT VDR
if [[ " $SYSTEMS " == *" tailored "* ]]; then
  say ""
  say "== Tailored BFT VDR: 4 BFT-SMaRt replicas x 1 vCPU (cores ${REPLICA0_CPUSET},${REPLICA1_CPUSET},${REPLICA2_CPUSET},${REPLICA3_CPUSET})"
  "${COMPOSE[@]}" stop "${NODES[@]}" webserver >/dev/null 2>&1 || true
  for MIX in $MIXES; do
    say "-- $MIX: fresh cluster"
    cluster_up || { say "the BFT-SMaRt cluster never started cleanly; see $LOG"; exit 1; }
    TAG="$STAMP-tailored-$MIX"
    set +e
    "${COMPOSE[@]}" run --rm --no-deps -T \
        -e RESULT_TAG="$TAG" \
        -e JAVA_TOOL_OPTIONS="$BENCH_FLAGS -Dbench.mixes=$MIX -Dvdr.maxBatchDelayMs=$DELAY_MS -Dbench.levels=$TAILORED_LEVELS" \
        tailored-bench 2>&1 | tee -a "$LOG" | grep -E "^(    sweep|  Tailored|      failures|candidate|Tailored BFT VDR --|wrote)"
    STATUS=${PIPESTATUS[0]}
    set -e
    save_replica_logs "tailored-$MIX"
    "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1 || true
    if [ "$STATUS" -ne 0 ] || ! RUN_DIR="$(latest_result tailored-bft "$TAG")"; then
      say "Tailored BFT VDR $MIX failed (exit $STATUS); see $LOG"; exit 1
    fi
    append_rows "$RUN_DIR/rows.tsv" "$OUT/tailored.tsv"
    cp "$RUN_DIR/RESULTS.md" "$OUT/tailored-$MIX-RESULTS.md"
    cp "$RUN_DIR/environment.txt" "$OUT/tailored-environment.txt"
    say "   -> $RUN_DIR"
  done
fi

# ------------------------------------------------------------------ Hyperledger Indy
# fresh_pool <registry count>: wipe the ledger, start the four validators, fetch the genesis file,
# stop the ledger browser.
fresh_pool() {
  "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1 || true
  docker compose down -v >> "$LOG" 2>&1 || true
  # Containers write these as root; delete them from a container so ownership never blocks it.
  docker run --rm -v "$PWD/results/indy":/results vdr-bench \
      rm -f /results/registry.env /results/registries.env /results/registry.counter \
            /results/pool_transactions_genesis >> "$LOG" 2>&1
  docker compose up -d "${NODES[@]}" webserver >> "$LOG" 2>&1
  echo -n "   waiting for the pool " | tee -a "$LOG"
  local i
  for i in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:${WEB_SERVER_HOST_PORT:-9000}/genesis" \
         -o results/indy/pool_transactions_genesis 2>/dev/null \
       && [ -s results/indy/pool_transactions_genesis ]; then
      say " ready after $((i * 10)) s"; break
    fi
    echo -n "."; sleep 10
    [ "$i" -eq 120 ] && { say " pool not ready after 20 min; docker compose logs webserver"; return 1; }
  done
  # The ledger browser is only needed for the genesis file. Left running it keeps reading the
  # ledger and competes with the validators for CPU during the measurement.
  docker compose stop webserver >> "$LOG" 2>&1
}

if [[ " $SYSTEMS " == *" indy "* ]]; then
  say ""
  say "== Hyperledger Indy baseline: 4 validators x 1 vCPU (cores ${NODE1_CPUSET},${NODE2_CPUSET},${NODE3_CPUSET},${NODE4_CPUSET})"
  # Registries per mix: one per sweep level and per measured run, with room for step-downs.
  NLEV=$(( $(tr ',' '\n' <<<"$INDY_LEVELS" | grep -c .) + REFINE ))
  REG_COUNT="${INDY_REGISTRY_COUNT:-$(( NLEV + RUNS * 4 + 5 ))}"
  for MIX in $MIXES; do
    say "-- $MIX: wiping the ledger, fresh pool"
    fresh_pool || exit 1
    say "   registry setup: $REG_COUNT fresh revocation registries (one per run), then the harness"
    TAG="$STAMP-indy-$MIX"
    set +e
    INDY_REGISTRY_COUNT="$REG_COUNT" "${COMPOSE[@]}" run --rm --no-deps -T \
        -e RESULT_TAG="$TAG" -e INDY_GENESIS_READY=1 -e INDY_REGISTRY_COUNT="$REG_COUNT" \
        -e JAVA_TOOL_OPTIONS="$BENCH_FLAGS -Dbench.mixes=$MIX -Dindy.maxBatchDelayMs=$DELAY_MS -Dbench.levels=$INDY_LEVELS" \
        indy-bench 2>&1 | tee -a "$LOG" | grep -E "^(    sweep|  Hyperledger|      failures|candidate|Hyperledger Indy --|wrote|passed)"
    STATUS=${PIPESTATUS[0]}
    set -e
    mkdir -p "$OUT/indy-node-logs/$MIX"
    for nd in "${NODES[@]}"; do
      docker compose logs --no-color --tail 2000 "$nd" > "$OUT/indy-node-logs/$MIX/$nd.log" 2>&1 || true
    done
    docker compose stop "${NODES[@]}" >> "$LOG" 2>&1 || true
    if [ "$STATUS" -ne 0 ] || ! RUN_DIR="$(latest_result indy "$TAG")"; then
      say "Indy $MIX failed (exit $STATUS); see $LOG"; exit 1
    fi
    append_rows "$RUN_DIR/rows.tsv" "$OUT/indy.tsv"
    cp "$RUN_DIR/RESULTS.md" "$OUT/indy-$MIX-RESULTS.md"
    say "   -> $RUN_DIR"
  done
fi

# ------------------------------------------------------------------ the table
python3 make-table.py --profile "$PROFILE" --cpus "$TOTAL_CPUS" \
    --tailored "$OUT/tailored.tsv" --indy "$OUT/indy.tsv" > "$OUT/TABLE.md"
say ""
cat "$OUT/TABLE.md" | tee -a "$LOG"
say ""
say "== done: $OUT/TABLE.md"
