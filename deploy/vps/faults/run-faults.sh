#!/usr/bin/env bash
# Jepsen-style fault campaign on both systems. Output: deploy/vps/results/faults/<UTC stamp>/, with
# SUMMARY.md and one folder per run holding history*.jsonl, nemesis.jsonl, check.json and node logs.
#
#   ./faults/run-faults.sh                                everything below, 10 repetitions each
#   REPS=1 SCENARIOS="crash-leader" SYSTEMS=tailored ./faults/run-faults.sh     a pilot
#   HEALTHY_S=60 FAULT_S=60 HEALED_S=120                  the phases of one run (these are defaults)
#
# Scenarios per system. Crash-all needs state on disk, so the in-memory arm skips it. Byzantine
# behaviours exist only in this code base, so they are Tailored only (disclosed; patching Indy would
# test the patch). See faults/README.md for what is not covered.
#
# Runs one at a time and must never overlap a measurement: they share the machine.
set -uo pipefail
cd "$(dirname "$0")/.."

SYSTEMS="${SYSTEMS:-tailored indy}"
NETWORK_SCENARIOS="crash-follower crash-leader isolate-leader partition-2-2 delay-one delay-all loss-5pct mute-node selective-drop client-kill"
BYZANTINE_SCENARIOS="byz-equivocate byz-suppress-revoke byz-stale-read byz-forge-doc"
REPS="${REPS:-10}"
HEALTHY_S="${HEALTHY_S:-60}"; FAULT_S="${FAULT_S:-60}"; HEALED_S="${HEALED_S:-120}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${FAULTS_OUT:-results/faults/$STAMP}"
mkdir -p "$OUT" results/faults-work
LOG="$OUT/campaign.log"
say() { echo "$*" | tee -a "$LOG"; }

scenarios_for() {
  [ -n "${SCENARIOS:-}" ] && { echo "$SCENARIOS"; return; }
  case "$1" in
    tailored)      echo "$NETWORK_SCENARIOS $BYZANTINE_SCENARIOS" ;;
    tailored-disk) echo "crash-all" ;;
    indy)          echo "$NETWORK_SCENARIOS crash-all" ;;
  esac
}

REPLICAS=(vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3)
NODES=(node1 node2 node3 node4)
BASE_COMPOSE=(docker compose --profile bftsmart --profile indy)
GATES_COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.gates.yml --profile bftsmart --profile indy)
COMPOSE=("${BASE_COMPOSE[@]}")
ALLOW_BYZANTINE=1
# shellcheck disable=SC1091
. ./cluster-lib.sh
# shellcheck disable=SC1091
. ./faults/nemesis.sh

docker build -q -t vdr-nemesis -f faults/Dockerfile.nemesis faults >> "$LOG" 2>&1 \
  || { say "could not build the nemesis image"; exit 1; }
{
  echo "git: $(git rev-parse HEAD 2>/dev/null || echo unknown)$(git diff --quiet HEAD 2>/dev/null || echo ' (uncommitted changes)')"
  echo "phases: healthy $HEALTHY_S s, fault $FAULT_S s, healed $HEALED_S s; repetitions $REPS"
  echo "host: $(uname -a)"
  docker image inspect --format '{{.RepoTags}} {{.Id}}' vdr-bench vdr-nemesis 2>/dev/null
} > "$OUT/provenance.txt"

byzantine_behaviour() {    # byz-stale-read -> STALE_READ
  echo "${1#byz-}" | tr 'a-z-' 'A-Z_'
}

set_cluster_mode() {       # set_cluster_mode <system> <scenario>
  local i
  COMPOSE=("${BASE_COMPOSE[@]}")
  for i in 0 1 2 3; do unset "VDR_BYZANTINE_$i"; done
  unset VDR_CONFIG_DIR
  case "$2" in
    byz-*)
      COMPOSE=("${GATES_COMPOSE[@]}")
      export VDR_BYZANTINE_1="$(byzantine_behaviour "$2")"
      ;;
  esac
  [ "$1" = tailored-disk ] && { VDR_CONFIG_DIR="$(./arm-config.sh tailored-disk)"; export VDR_CONFIG_DIR; }
  return 0
}

client_service() {         # client_service <system>
  case "$1" in tailored*) echo tailored-bench ;; indy) echo indy-bench ;; esac
}

# start_client <run dir> <client file> <duration s> [resume file]
start_client() {
  local run="$1" file="$2" duration="$3" resume="${4:-}" service flags name
  service="$(client_service "$SYSTEM")"
  name="fault-client-$$-$file"
  flags="-Xms512m -Xmx1g -Dvdr.replication=bftsmart -Dhistory.durationMs=$((duration * 1000)) -Dhistory.opTimeoutMs=5000 -Dvdr.bftsmart.timeoutMs=5000"
  flags="$flags -Dhistory.out=/results/faults-work/$file.jsonl -Dvdr.maxBatchDelayMs=50 -Dindy.maxBatchDelayMs=50"
  [ -n "$resume" ] && flags="$flags -Dhistory.resumeFrom=/results/faults-work/$resume.jsonl"
  "${COMPOSE[@]}" run --rm --no-deps -T --name "$name" \
      -e RESULT_TAG= -e BENCH_PHASE=history -e VDR_BENCH_MAIN=vdr.bench.History \
      -e INDY_GENESIS_READY=1 -e INDY_REGISTRY_COUNT=4 \
      -e JAVA_TOOL_OPTIONS="$flags" "$service" > "$run/$file.log" 2>&1 &
  CLIENT_PID=$!
  CLIENT_NAME="$name"
}

wait_client_ready() {      # wait_client_ready <log file> <timeout s>
  local deadline=$((SECONDS + $2))
  until grep -q "^HISTORY ready" "$1" 2>/dev/null; do
    kill -0 "$CLIENT_PID" 2>/dev/null || return 1
    [ $SECONDS -gt $deadline ] && return 1
    sleep 1
  done
}

# The client bounds its own final checks; this catches whatever still hangs, so one stuck run cannot
# hold up an unattended campaign. Evidence written so far is kept and checked as usual.
wait_client() {            # wait_client <run dir> <seconds of work the client has left>
  local deadline=$((SECONDS + $2 + ${CLIENT_GRACE_S:-900}))
  while kill -0 "$CLIENT_PID" 2>/dev/null; do
    if [ $SECONDS -gt $deadline ]; then
      say "   the client overran; stopping it"
      echo "client overran" >> "$1/invalid.txt"
      docker kill "$CLIENT_NAME" >> "$LOG" 2>&1 || kill "$CLIENT_PID" 2>/dev/null
      break
    fi
    sleep 2
  done
  wait "$CLIENT_PID" 2>/dev/null
}

results_dir() {
  case "$SYSTEM" in tailored*) echo results/tailored-bft ;; indy) echo results/indy ;; esac
}

save_node_logs() {         # save_node_logs <run dir>
  local t
  for t in $(targets_for "$SYSTEM"); do
    "${COMPOSE[@]}" logs --no-color --timestamps "$t" > "$1/$t.log" 2>/dev/null || true
  done
}

# Indy's client cannot ask one node for its digest, so read each node's own validator-info. Failing to
# read them leaves no file, and the checker then reports convergence as not checked, not as a pass.
indy_digests() {           # indy_digests <run dir>
  local n text
  for n in 1 2 3 4; do
    "${COMPOSE[@]}" exec -T "node$n" sh -c 'cat $(find /home/indy -name "*_info.json" | head -1)' > "$1/node$n-info.json" 2>/dev/null || true
    [ -s "$1/node$n-info.json" ] || rm -f "$1/node$n-info.json"
  done
  python3 - "$1" <<'PY'
import glob, json, os, sys
run = sys.argv[1]
out = {}
for path in sorted(glob.glob(os.path.join(run, "node*-info.json"))):
    try:
        info = json.load(open(path))["Node_info"]
        out[os.path.basename(path)[:5]] = json.dumps(info["Committed_state_root_hashes"], sort_keys=True)
    except Exception as e:
        print(f"could not read {path}: {e}", file=sys.stderr)
if len(out) == 4:
    json.dump(out, open(os.path.join(run, "node-digests.json"), "w"))
PY
}

one_run() {                # one_run <system> <scenario> <repetition>
  SYSTEM="$1"; SCENARIO="$2"; local rep="$3" run status=0 work file
  run="$OUT/$SYSTEM/$SCENARIO/rep-$rep"
  mkdir -p "$run"
  NEMESIS_LOG="$run/nemesis.jsonl"
  say "-- $SYSTEM / $SCENARIO / repetition $rep of $REPS"
  set_cluster_mode "$SYSTEM" "$SCENARIO"
  work="$(results_dir)/faults-work"
  docker run --rm -v "$PWD/$(results_dir)":/results vdr-bench sh -c 'rm -rf /results/faults-work; mkdir -p /results/faults-work; chmod 777 /results/faults-work' >> "$LOG" 2>&1

  case "$SYSTEM" in
    indy) fresh_pool ;;
    *)    cluster_up ;;
  esac || { say "   could not start $SYSTEM"; echo "start failed" > "$run/invalid.txt"; return 0; }

  start_client "$run" history "$((HEALTHY_S + FAULT_S + HEALED_S))"
  if ! wait_client_ready "$run/history.log" 600; then
    say "   the client never became ready; see $run/history.log"
    echo "client never ready" > "$run/invalid.txt"
    kill "$CLIENT_PID" 2>/dev/null; wait "$CLIENT_PID" 2>/dev/null
    save_node_logs "$run"; return 0
  fi

  sleep "$HEALTHY_S"
  inject "$SYSTEM" "$SCENARIO" || { status=1; echo "fault did not take effect" > "$run/invalid.txt"; }

  if [ "$SCENARIO" = client-kill ]; then
    docker kill "$CLIENT_NAME" >> "$LOG" 2>&1 || { status=1; echo "client kill failed" > "$run/invalid.txt"; }
    wait "$CLIENT_PID" 2>/dev/null
    sleep "$FAULT_S"
    heal "$SYSTEM" "$SCENARIO" || status=1
    start_client "$run" history-2 "$HEALED_S" history
    wait_client "$run" "$HEALED_S"
  else
    sleep "$FAULT_S"
    heal "$SYSTEM" "$SCENARIO" || { status=1; echo "healing failed" >> "$run/invalid.txt"; }
    wait_client "$run" "$HEALED_S"
  fi

  cp "$work"/history*.jsonl "$run/" 2>/dev/null
  [ "$SYSTEM" = indy ] && indy_digests "$run"
  save_node_logs "$run"
  python3 faults/check-history.py "$run" > "$run/check.txt" 2>&1
  local verdict=$?
  case "$verdict" in
    0) say "   no safety violation; $(sed -n '$p' "$run/check.txt")" ;;
    1) say "   SAFETY VIOLATION; see $run/check.txt" ;;
    2) say "   no verdict possible; see $run/check.txt" ;;
    *) say "   checker error $verdict; see $run/check.txt"; echo "checker error" >> "$run/invalid.txt" ;;
  esac
  return 0
}

trap 'replicas_down; docker compose stop "${NODES[@]}" >/dev/null 2>&1 || true' EXIT
for system in $SYSTEMS; do
  case "$system" in tailored|tailored-disk|indy) ;;
    *) say "SYSTEMS: unknown system $system"; exit 2 ;; esac
  for scenario in $(scenarios_for "$system"); do
    for rep in $(seq 1 "$REPS"); do
      one_run "$system" "$scenario" "$rep"
      case "$system" in indy) docker compose stop "${NODES[@]}" >> "$LOG" 2>&1 ;; *) COMPOSE=("${BASE_COMPOSE[@]}"); replicas_down ;; esac
    done
  done
done
python3 faults/summarize-faults.py "$OUT" --out "$OUT/SUMMARY.md"
say "== done: $OUT/SUMMARY.md"
