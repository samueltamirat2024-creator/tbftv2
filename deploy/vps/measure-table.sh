#!/usr/bin/env bash
# ONE command for the comparison table, on one virtual server with 4 nodes x 1 vCPU.
#
#   ./measure-table.sh                     # both systems, all four mixes, PROFILE=standard
#   PROFILE=quick ./measure-table.sh       # pipeline check; numbers NOT reportable
#   PROFILE=full  ./measure-table.sh       # the paper's protocol: 60 s / 60 s / 10 runs (many hours)
#   SYSTEMS=tailored ./measure-table.sh    # one arm: tailored | tailored-disk | tailored-disk-nosync | indy
#   MIXES="register update" ./measure-table.sh   # only the two VDR write operations
#   EQUAL_LOAD=1 ./measure-table.sh        # every mix at the SAME fixed offered loads on both
#
# It measures these rows (MIXES selects a subset):
#
#   Tailored BFT VDR -- read-heavy            Hyperledger Indy baseline -- read-heavy
#   Tailored BFT VDR -- bursty-revoke         Hyperledger Indy baseline -- bursty-revoke
#   Tailored BFT VDR -- register              Hyperledger Indy baseline -- register
#   Tailored BFT VDR -- update                Hyperledger Indy baseline -- update
#
# register: every arrival writes a fresh DID (Tailored DepSpace cas / Indy trustee NYM).
# update:   every arrival rotates a populated DID's key (Tailored versioned update / Indy NYM
#           verkey rotation signed by the DID's own key).
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
# tailored keeps its log in memory; Indy writes its ledger to disk. tailored-disk removes that
# difference by logging with sync_log=true, and durable-gate.sh decides what it may be called.
# tailored-disk-nosync is for when indy-fsync-probe.sh shows Indy does not sync per commit.
SYSTEMS="${SYSTEMS:-tailored tailored-disk indy}"
DELAY_MS="${VDR_MAX_BATCH_DELAY_MS:-50}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="results/table/$STAMP${OUT_LABEL:+-$OUT_LABEL}"
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
# Overrides for deploy/vps/diagnose.sh, which varies one thing at a time.
RUNS="${RUNS_OVERRIDE:-$RUNS}"
STEADY="${STEADY_OVERRIDE:-$STEADY}"
# REPEAT_FRESH=1: each of the RUNS repetitions gets its own fresh cluster or wiped ledger and its own
# generator JVM, so runs are independent samples and stats.py can bootstrap over them. REPEAT_FRESH=0
# runs them all on one cluster and JVM, with each run adding 400 DIDs: faster, but those runs are
# correlated, and the tables and stats.py say so. The default for the reportable profile is 1.
REPEAT_FRESH="${REPEAT_FRESH:-$( [ "$PROFILE" = full ] && echo 1 || echo 0 )}"
# Coarse levels; the knee is then bisected REFINE times. Indy's knees on earlier hardware were
# 50-75 (bursty-revoke) and 200-250 (read-heavy) ops/s; Tailored's were in the thousands.
TAILORED_LEVELS="${TAILORED_LEVELS:-100,250,500,1000,1500,2000,3000,4000,6000,8000}"
INDY_LEVELS="${INDY_LEVELS:-10,25,50,75,100,150,200,300,400,600}"
# Write-only mixes: every arrival is an ordered write, so the knees sit far below the read-heavy
# ones on both systems.
TAILORED_WRITE_LEVELS="${TAILORED_WRITE_LEVELS:-50,100,250,500,1000,1500,2000,3000,4000}"
INDY_WRITE_LEVELS="${INDY_WRITE_LEVELS:-5,10,25,50,75,100,150,200,300}"
# DIDs the update mix rotates over. Updates are serialised per DID on both systems; if rate x
# update latency exceeds this, the harness warns (contendedUpdates) and the pool must grow.
UPDATE_DID_POOL="${UPDATE_DID_POOL:-400}"

# EQUAL_LOAD=1: no sweep; every listed level is measured on both systems with the full RUNS.
# Pick loads BOTH systems sustain: a level past Indy's knee compares Tailored with Indy's queue.
EQUAL_LOAD="${EQUAL_LOAD:-0}"
# Check these against a measured Indy table, or use EQ_FROM below: a level past Indy's knee compares
# Tailored with Indy's queue.
EQ_READ_HEAVY="${EQ_READ_HEAVY:-50,100,200}"
EQ_BURSTY_REVOKE="${EQ_BURSTY_REVOKE:-25,50}"
EQ_REGISTER="${EQ_REGISTER:-10,25,50}"
EQ_UPDATE="${EQ_UPDATE:-10,25,50}"

# mix_flags <tailored|indy> <mix>: the load flags for one mix, sweep or equal load.
mix_flags() {
  local sys="$1" mix="$2" levels pool=""
  [ "$mix" = update ] && pool=" -Dbench.didPool=$UPDATE_DID_POOL"
  if [ "$EQUAL_LOAD" = 1 ]; then
    case "$mix" in
      read-heavy)    levels="-Dbench.equalLoad.readHeavy=$EQ_READ_HEAVY" ;;
      bursty-revoke) levels="-Dbench.equalLoad.burstyRevoke=$EQ_BURSTY_REVOKE" ;;
      register)      levels="-Dbench.equalLoad.register=$EQ_REGISTER" ;;
      update)        levels="-Dbench.equalLoad.update=$EQ_UPDATE" ;;
      *) echo "unknown mix $mix" >&2; exit 2 ;;
    esac
    echo "$levels$pool"
    return
  fi
  case "$sys:$mix" in
    tailored*:register|tailored*:update) levels="$TAILORED_WRITE_LEVELS" ;;
    indy:register|indy:update)         levels="$INDY_WRITE_LEVELS" ;;
    tailored*:*)                       levels="$TAILORED_LEVELS" ;;
    indy:*)                            levels="$INDY_LEVELS" ;;
  esac
  echo "-Dbench.mixes=$mix -Dbench.levels=$levels$pool"
}

BENCH_FLAGS="-Xms1g -Xmx2g -Dbench.warmupMs=$WARM -Dbench.steadyMs=$STEADY -Dbench.runs=$RUNS \
 -Dbench.sweepWarmupMs=$SWEEP_W -Dbench.sweepSteadyMs=$SWEEP_S -Dbench.refine=$REFINE ${BENCH_EXTRA_FLAGS:-}"
# EQ_FROM=<table dir>: equal-load levels at about 50% and 80% of the Indy operating points measured there.
if [ -n "${EQ_FROM:-}" ]; then
  eval "$(python3 equal-levels.py "$EQ_FROM/indy.tsv")" || { echo "EQ_FROM: no usable levels" >&2; exit 2; }
fi
MIXES="${MIXES:-read-heavy bursty-revoke register update}"
for ARM in $SYSTEMS; do
  case "$ARM" in tailored|tailored-disk|tailored-disk-nosync|indy) ;;
    *) echo "SYSTEMS: unknown arm '$ARM'" >&2; exit 2 ;;
  esac
done
is_tailored() { [[ "$1" == tailored* ]]; }
for MIX in $MIXES; do
  case "$MIX" in read-heavy|bursty-revoke|register|update) ;;
    *) echo "MIXES: unknown mix '$MIX' (read-heavy bursty-revoke register update)" >&2; exit 2 ;;
  esac
done

COMPOSE=(docker compose --profile bftsmart --profile indy)
REPLICAS=(vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3)
NODES=(node1 node2 node3 node4)

# ------------------------------------------------------------------ preflight
[ "$REPEAT_FRESH" = 0 ] || [ "$RUNS" -ge 2 ] || { echo "REPEAT_FRESH=1 needs at least 2 runs" >&2; exit 2; }
say "== measure-table $STAMP: profile $PROFILE, systems: $SYSTEMS, mixes: $MIXES$( [ "$EQUAL_LOAD" = 1 ] && echo ', EQUAL LOAD')"
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
# Provenance: exactly which code and images produced this table.
{
  echo "git: $(git rev-parse HEAD 2>/dev/null || echo unknown)$(git diff --quiet HEAD 2>/dev/null || echo ' (uncommitted changes)')"
  "${COMPOSE[@]}" images --format json 2>/dev/null || "${COMPOSE[@]}" images
  docker image inspect --format '{{.RepoTags}} {{.Id}}' vdr-bench 2>/dev/null || true
} > "$OUT/provenance.txt"
docker run --rm vdr-bench sh -c 'ls vdr-bftsmart/lib | grep -q bcprov && echo "Ed25519: BouncyCastle" || echo "Ed25519: JDK (bcprov not fetched)"' | tee -a "$LOG"

# BFT-SMaRt replica keys (system.communication.defaultkeys=false): generated on this machine,
# never shipped in the image.
CFG="$(cd "${VDR_CONFIG_DIR:-../../config}" && pwd)"
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
# shellcheck disable=SC1091
. ./cluster-lib.sh

save_replica_logs() {    # save_replica_logs <label>
  mkdir -p "$OUT/replica-logs/$1"
  for r in "${REPLICAS[@]}"; do
    "${COMPOSE[@]}" logs --no-color --timestamps "$r" > "$OUT/replica-logs/$1/$r.log" 2>/dev/null || true
  done
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

# ------------------------------------------------------------------ one system, one mix
# FRESH STATE PER MEASURED LEVEL (FRESH_MEASURE=1, the default). The sweep deliberately drives a
# system past its knee. Measured afterwards on the same cluster and client, the operating point
# measured that damage: on a real BFT-SMaRt cluster, update at 500 ops/s fell to 0-42 ops/s after
# the sweep, and held 500 ops/s with p99 15 ms on a fresh one. So the sweep runs in its own
# invocation (-Dbench.sweepOnly), and every level that is then measured -- the candidate and each
# step-down -- gets a fresh cluster or a wiped ledger and a fresh JVM, on both systems alike.
# FRESH_MEASURE=0 restores the single-invocation protocol (sweep and measurement on one cluster).
FRESH_MEASURE="${FRESH_MEASURE:-1}"

SAMPLER_PID=""
start_sampler() {        # start_sampler <file>
  python3 sample-resources.py --out "$1" >> "$LOG" 2>&1 &
  SAMPLER_PID=$!
}
stop_sampler() {
  [ -n "$SAMPLER_PID" ] && { kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; }
  SAMPLER_PID=""
}
trap stop_sampler EXIT

mix_key() {              # mix_key <mix> -> the -Dbench.*.<key> suffix
  case "$1" in
    read-heavy) echo readHeavy ;; bursty-revoke) echo burstyRevoke ;; *) echo "$1" ;;
  esac
}
pool_flag() { [ "$1" = update ] && echo "-Dbench.didPool=$UPDATE_DID_POOL" || true; }

fresh_state() {          # fresh_state <tailored|indy>
  if is_tailored "$1"; then
    cluster_up || { say "the BFT-SMaRt cluster never started cleanly; see $LOG"; return 1; }
  else
    fresh_pool
  fi
}

teardown() {             # teardown <tailored|indy> <label>: keep the node logs, stop the nodes
  if is_tailored "$1"; then
    save_replica_logs "$1-$2"
    replicas_down
  else
    mkdir -p "$OUT/indy-node-logs/$2"
    local nd
    for nd in "${NODES[@]}"; do
      docker compose logs --no-color --tail 2000 "$nd" > "$OUT/indy-node-logs/$2/$nd.log" 2>&1 || true
    done
    docker compose stop "${NODES[@]}" >> "$LOG" 2>&1 || true
  fi
}

# bench_invoke <tailored|indy> <tag> <sweep|measure> <log file> <registries> <java flags>
# Runs vdr.bench.Bench once; returns its exit status (3 = no admitted level met the p99 target).
bench_invoke() {
  local sys="$1" tag="$2" phase="$3" logf="$4" regs="$5" flags="$6" status
  set +e
  if is_tailored "$sys"; then
    "${COMPOSE[@]}" run --rm --no-deps -T \
        -e RESULT_TAG="$tag" -e BENCH_PHASE="$phase" \
        -e JAVA_TOOL_OPTIONS="$BENCH_FLAGS -Dvdr.maxBatchDelayMs=$DELAY_MS $flags" \
        tailored-bench 2>&1 | tee -a "$LOG" "$logf" \
      | grep -E "^(    sweep|  Tailored|      failures|      stopped|    WARNING|candidate|-- |ADMITTED|SATURATED_AT|STEPDOWN|Tailored BFT VDR --|wrote)"
    status=${PIPESTATUS[0]}
  else
    say "   registry setup: $regs fresh revocation registries (one per run), then the harness"
    INDY_REGISTRY_COUNT="$regs" "${COMPOSE[@]}" run --rm --no-deps -T \
        -e RESULT_TAG="$tag" -e BENCH_PHASE="$phase" -e INDY_GENESIS_READY=1 \
        -e INDY_REGISTRY_COUNT="$regs" \
        -e JAVA_TOOL_OPTIONS="$BENCH_FLAGS -Dindy.maxBatchDelayMs=$DELAY_MS $flags" \
        indy-bench 2>&1 | tee -a "$LOG" "$logf" \
      | grep -E "^(    sweep|  Hyperledger|      failures|      stopped|    WARNING|candidate|-- |ADMITTED|SATURATED_AT|STEPDOWN|Hyperledger Indy --|wrote|passed)"
    status=${PIPESTATUS[0]}
  fi
  set -e
  return "$status"
}

collect() {              # collect <tailored|indy> <tag> <mix>: file the measured run's outputs
  local sub run_dir
  is_tailored "$1" && sub=tailored-bft || sub=indy
  run_dir="$(latest_result "$sub" "$2")" || { say "$1 $3: no result folder for tag $2"; return 1; }
  append_rows "$run_dir/rows.tsv" "$OUT/$1.tsv"
  cp "$run_dir/RESULTS.md" "$OUT/$1-$3-RESULTS.md"
  is_tailored "$1" && cp "$run_dir/environment.txt" "$OUT/$1-environment.txt"
  say "   -> $run_dir"
}

# repeat_level <tailored|indy> <mix> <level> <java flags>: RUNS independent repetitions of one level,
# filed under $OUT/reps/<system>/<mix>/<level>/rep-<k>/, one fresh state and JVM each.
repeat_level() {
  local sys="$1" mix="$2" level="$3" flags="$4" k rep tag status sub run_dir f
  is_tailored "$sys" && sub=tailored-bft || sub=indy
  for (( k=1; k<=RUNS; k++ )); do
    rep="$OUT/reps/$sys/$mix/$level/rep-$k"
    tag="$STAMP-$sys-$mix-$level-$k"
    mkdir -p "$rep"
    say "-- $mix @ $level ops/s: repetition $k of $RUNS, fresh state"
    fresh_state "$sys" || return 1
    start_sampler "$rep/resources.tsv"
    if [ -n "${PRELOAD_SEPARATE_MS:-}" ]; then
      # Warm the replicas from a JVM that then exits, so the measuring generator starts cold.
      bench_invoke "$sys" "$tag-preload" sweep "$rep/preload.log" 6 \
          "-Dbench.runs=1 -Dbench.stepdown=false -Dbench.rawOutput=false -Dbench.warmupMs=0 -Dbench.steadyMs=$PRELOAD_SEPARATE_MS $flags" \
          || { say "$sys $mix @ $level preload failed; see $rep/preload.log"; stop_sampler; return 1; }
    fi
    # The later -Dbench.runs overrides the one in BENCH_FLAGS. Registries: one run, plus slack.
    bench_invoke "$sys" "$tag" measure "$rep/bench.log" 6 \
        "-Dbench.runs=1 -Dbench.stepdown=false $flags" && status=0 || status=$?
    stop_sampler
    teardown "$sys" "$mix-$level-rep$k"
    [ "$status" -eq 0 ] || { say "$sys $mix @ $level repetition $k failed (exit $status); see $rep/bench.log"; return 1; }
    run_dir="$(latest_result "$sub" "$tag")" || { say "$sys: no result folder for tag $tag"; return 1; }
    for f in runs.jsonl timeseries.jsonl rows.tsv environment.txt; do cp "$run_dir/$f" "$rep/"; done
  done
}

measure_mix_repeated() { # measure_mix_repeated <tailored|indy> <mix>
  local sys="$1" mix="$2" tag="$STAMP-$1-$2" key level levels status logf admitted ceiling path="" p99 k levels_arr dir
  key="$(mix_key "$mix")"
  mkdir -p "$OUT/bench-logs"
  if [ "$EQUAL_LOAD" = 1 ]; then
    case "$mix" in
      read-heavy) levels="$EQ_READ_HEAVY" ;; bursty-revoke) levels="$EQ_BURSTY_REVOKE" ;;
      register) levels="$EQ_REGISTER" ;; update) levels="$EQ_UPDATE" ;;
    esac
    IFS=, read -ra levels_arr <<<"$levels"
    for level in "${levels_arr[@]}"; do
      repeat_level "$sys" "$mix" "$level" "-Dbench.equalLoad.$key=$level $(pool_flag "$mix")" || return 1
      python3 stats.py rows "$OUT/reps/$sys/$mix/$level" --equal-load --out "$OUT/$sys.tsv"
    done
    return
  fi

  say "-- $mix: sweep (fresh state)"
  fresh_state "$sys" || return 1
  logf="$OUT/bench-logs/$sys-$mix-sweep.log"
  local nlev
  if [ "$mix" = register ] || [ "$mix" = update ]; then
    nlev=$(tr ',' '\n' <<<"$INDY_WRITE_LEVELS" | grep -c .)
  else
    nlev=$(tr ',' '\n' <<<"$INDY_LEVELS" | grep -c .)
  fi
  bench_invoke "$sys" "$tag-sweep" sweep "$logf" "$(( nlev + REFINE + 5 ))" \
      "$(mix_flags "$sys" "$mix") -Dbench.sweepOnly=true" && status=0 || status=$?
  teardown "$sys" "$mix-sweep"
  [ "$status" -eq 0 ] || { say "$sys $mix sweep failed (exit $status); see $LOG"; return 1; }
  admitted="$(grep -E "^ADMITTED $mix " "$logf" | tail -1 | awk '{print $3}')"
  [ -n "$admitted" ] || { say "$sys $mix: the sweep admitted no level; see $logf"; return 1; }
  ceiling="$(grep -E "^SATURATED_AT $mix " "$logf" | tail -1 | awk '{print $3}')"
  grep -E "^    sweep:" "$logf" > "$OUT/$sys-$mix-sweep.txt" || true
  IFS=, read -ra levels_arr <<<"$admitted"
  say "   admitted: $admitted; first failing level: ${ceiling:-unknown}; $RUNS repetitions at the highest, stepping down if the merged p99 misses 500 ms"
  for (( k=${#levels_arr[@]}-1; k>=0; k-- )); do
    level="${levels_arr[$k]}"
    dir="$OUT/reps/$sys/$mix/$level"
    repeat_level "$sys" "$mix" "$level" \
        "-Dbench.mixes=$mix -Dbench.admitted.$key=$level $(pool_flag "$mix") ${ceiling:+-Dbench.sweepCeiling=$ceiling}" || return 1
    p99="$(python3 stats.py check-p99 "$dir")" && status=0 || status=$?
    path="${path:+$path, }$level ops/s -> p99 $p99 ms"
    if [ "$status" -eq 0 ]; then
      python3 stats.py rows "$dir" --admission-path "$path" --out "$OUT/$sys.tsv"
      return
    elif [ "$status" -ne 3 ]; then
      say "$sys $mix @ $level: stats.py failed (exit $status)"; return 1
    fi
    say "   $mix @ $level ops/s missed the p99 target over $RUNS repetitions ($p99 ms); stepping down"
    # Kept for the record, but out of reps/, which holds only the levels that are reported.
    mkdir -p "$OUT/reps-rejected/$sys/$mix"
    mv "$dir" "$OUT/reps-rejected/$sys/$mix/$level"
  done
  say "$sys $mix: no admitted level met the p99 target over $RUNS repetitions (path $path)"
  return 1
}

measure_mix() {          # measure_mix <tailored|indy> <mix>
  [ "$REPEAT_FRESH" = 1 ] && { measure_mix_repeated "$1" "$2"; return; }
  local sys="$1" mix="$2" tag="$STAMP-$1-$2" key status logf admitted ceiling prior="" step k level
  key="$(mix_key "$mix")"
  mkdir -p "$OUT/bench-logs"
  # Registries Indy needs per invocation: one per sweep level / measured run, plus slack.
  local nlev regs_sweep regs_measure
  if [ "$mix" = register ] || [ "$mix" = update ]; then
    nlev=$(tr ',' '\n' <<<"$INDY_WRITE_LEVELS" | grep -c .)
  else
    nlev=$(tr ',' '\n' <<<"$INDY_LEVELS" | grep -c .)
  fi
  regs_sweep="${INDY_REGISTRY_COUNT:-$(( nlev + REFINE + 5 ))}"
  regs_measure="${INDY_REGISTRY_COUNT:-$(( RUNS + 5 ))}"

  if [ "$EQUAL_LOAD" = 1 ] || [ "$FRESH_MEASURE" != 1 ]; then
    say "-- $mix: fresh state, one invocation$( [ "$EQUAL_LOAD" = 1 ] && echo ' (equal load)')"
    fresh_state "$sys" || return 1
    logf="$OUT/bench-logs/$sys-$mix.log"
    bench_invoke "$sys" "$tag" measure "$logf" "${INDY_REGISTRY_COUNT:-$(( nlev + REFINE + RUNS * 4 + 5 ))}" \
        "$(mix_flags "$sys" "$mix")" && status=0 || status=$?
    teardown "$sys" "$mix"
    [ "$status" -eq 0 ] || { say "$sys $mix failed (exit $status); see $LOG"; return 1; }
    collect "$sys" "$tag" "$mix"
    return
  fi

  say "-- $mix: sweep (fresh state)"
  fresh_state "$sys" || return 1
  logf="$OUT/bench-logs/$sys-$mix-sweep.log"
  bench_invoke "$sys" "$tag-sweep" sweep "$logf" "$regs_sweep" \
      "$(mix_flags "$sys" "$mix") -Dbench.sweepOnly=true" && status=0 || status=$?
  teardown "$sys" "$mix-sweep"
  [ "$status" -eq 0 ] || { say "$sys $mix sweep failed (exit $status); see $LOG"; return 1; }
  admitted="$(grep -E "^ADMITTED $mix " "$logf" | tail -1 | awk '{print $3}')"
  [ -n "$admitted" ] || { say "$sys $mix: the sweep admitted no level; see $logf"; return 1; }
  # The lowest level the sweep failed ("none" if every listed level passed: a lower bound). It goes
  # into the measured row's notes, so TABLE.md can bracket the knee without the sweep log.
  ceiling="$(grep -E "^SATURATED_AT $mix " "$logf" | tail -1 | awk '{print $3}')"
  grep -E "^    sweep:" "$logf" > "$OUT/$sys-$mix-sweep.txt" || true
  local levels_arr
  IFS=, read -ra levels_arr <<<"$admitted"
  say "   admitted: $admitted; first failing level: ${ceiling:-unknown}; measuring the highest on fresh state, stepping down if its merged p99 misses 500 ms"

  for (( k=${#levels_arr[@]}-1; k>=0; k-- )); do
    level="${levels_arr[$k]}"
    say "-- $mix @ $level ops/s: fresh state, $RUNS runs"
    fresh_state "$sys" || return 1
    logf="$OUT/bench-logs/$sys-$mix-$level.log"
    bench_invoke "$sys" "$tag" measure "$logf" "$regs_measure" \
        "-Dbench.mixes=$mix -Dbench.admitted.$key=$level $(pool_flag "$mix") ${ceiling:+-Dbench.sweepCeiling=$ceiling} ${prior:+-Dbench.priorAdmissionPath=$prior}" \
        && status=0 || status=$?
    teardown "$sys" "$mix-$level"
    if [ "$status" -eq 0 ]; then
      collect "$sys" "$tag" "$mix"
      return
    elif [ "$status" -eq 3 ]; then
      step="$(grep -E '^STEPDOWN_PATH ' "$logf" | tail -1 | awk '{print $2}')"
      prior="${prior:+$prior,}${step:-$level:?}"
      say "   $mix @ $level ops/s missed the p99 target when measured ($step); stepping down"
    else
      say "$sys $mix @ $level failed (exit $status); see $logf"; return 1
    fi
  done
  say "$sys $mix: no admitted level met the p99 target when measured (path $prior)"
  return 1
}

# ------------------------------------------------------------------ Tailored BFT VDR
# use_arm <arm>: replicas and generator read this arm's config. A disk arm's name in the table comes
# from durable-gate.sh on this machine, so the gate runs first (SKIP_DURABILITY_GATE=1 to reuse a
# verdict, which must then be copied to $OUT/durability-<arm>.txt by hand).
use_arm() {
  case "$1" in
    tailored) export VDR_CONFIG_DIR="$CFG" ;;
    *)
      VDR_CONFIG_DIR="$(ARM_CONFIG_BASE="$CFG" ./arm-config.sh "$1")" || return 1
      export VDR_CONFIG_DIR
      if [ "$1" = tailored-disk ] && [ -z "${SKIP_DURABILITY_GATE:-}" ]; then
        say "== durability gate for $1 (kill -9 of all replicas)"
        ARM_CONFIG_BASE="$CFG" ARM="$1" ./durable-gate.sh "$OUT/durability-$1.txt" && say "   PASS" \
          || say "   FAIL: this arm is reported as disk logging, not recoverable"
      fi
      ;;
  esac
}

for ARM in $SYSTEMS; do
  is_tailored "$ARM" || continue
  say ""
  say "== $ARM: 4 BFT-SMaRt replicas x 1 vCPU (cores ${REPLICA0_CPUSET},${REPLICA1_CPUSET},${REPLICA2_CPUSET},${REPLICA3_CPUSET})"
  "${COMPOSE[@]}" stop "${NODES[@]}" webserver >/dev/null 2>&1 || true
  use_arm "$ARM" || { say "could not set up arm $ARM"; exit 1; }
  for MIX in $MIXES; do
    measure_mix "$ARM" "$MIX" || exit 1
  done
done

# ------------------------------------------------------------------ Hyperledger Indy
if [[ " $SYSTEMS " == *" indy "* ]]; then
  say ""
  say "== Hyperledger Indy baseline: 4 validators x 1 vCPU (cores ${NODE1_CPUSET},${NODE2_CPUSET},${NODE3_CPUSET},${NODE4_CPUSET})"
  for MIX in $MIXES; do
    measure_mix indy "$MIX" || exit 1
  done
fi

# ------------------------------------------------------------------ the table
python3 make-table.py --profile "$PROFILE" --cpus "$TOTAL_CPUS" \
    $( [ "$EQUAL_LOAD" = 1 ] && echo --equal-load ) \
    $( [ "$PROFILE" = full ] && [ -z "${ALLOW_UNREPORTABLE:-}" ] || echo --allow-unreportable ) \
    --tailored "$OUT/tailored.tsv" --indy "$OUT/indy.tsv" \
    --tailored-disk "$OUT/tailored-disk.tsv" --durability "$OUT/durability-tailored-disk.txt" \
    --tailored-disk-nosync "$OUT/tailored-disk-nosync.tsv" > "$OUT/TABLE.md"
if [ "$REPEAT_FRESH" = 1 ]; then
  ARMS=()
  for sys in $SYSTEMS; do ARMS+=(--arm "$sys=$OUT/reps/$sys"); done
  # Exit 4 means the report opens with why it is not reportable: wanted, not a failure here.
  python3 stats.py report "${ARMS[@]}" --ref "${SYSTEMS##* }" $( [ "$EQUAL_LOAD" = 1 ] && echo --equal-load ) \
      --out "$OUT/STATS.md" || [ $? -eq 4 ]
  say "== statistics: $OUT/STATS.md"
fi
say ""
cat "$OUT/TABLE.md" | tee -a "$LOG"
say ""
say "== done: $OUT/TABLE.md"
