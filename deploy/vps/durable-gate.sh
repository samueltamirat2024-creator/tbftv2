#!/usr/bin/env bash
# May the disk-log arm be called durable? Kills all four replicas with SIGKILL and checks that every
# write a client was told is committed is still there after a restart.
#
#   ./durable-gate.sh [verdict file]     # exit 0 PASS, 1 FAIL
#   GATE_TRIALS=5 ARM=tailored-disk ./durable-gate.sh
#
# Each trial runs three kills on one set of disks:
#   quiet       after a load has finished, so the log holds a checkpoint and a tail
#   mid-load    during a load, so replicas stop a few instances apart
#   repeat      again straight after recovering, with nothing written in between
#
# On BFT-SMaRt 1.2 this is expected to FAIL, for reasons in docs/DURABILITY.md. The gate runs anyway
# so the verdict comes from this machine and this image, not from an earlier conclusion.
set -uo pipefail
cd "$(dirname "$0")"

ARM="${ARM:-tailored-disk}"
TRIALS="${GATE_TRIALS:-5}"
LOAD_OPS="${GATE_LOAD_OPS:-2500}"
MIDLOAD_S="${GATE_MIDLOAD_S:-30}"
RESTART_S="${GATE_RESTART_S:-20}"
VERDICT="${1:-results/durable-gate-$ARM.txt}"
mkdir -p "$(dirname "$VERDICT")" results/gate-work
LOG="${VERDICT%.txt}.log"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.gates.yml --profile bftsmart)
REPLICAS=(vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3)
NODES=(node1 node2 node3 node4)
# shellcheck disable=SC1091
. ./cluster-lib.sh
trap replicas_down EXIT

VDR_CONFIG_DIR="$(./arm-config.sh "$ARM")" || exit 1
export VDR_CONFIG_DIR
BASE=$((20000 + RANDOM % 10000))

phase() {   # phase <log> <RealGates phase> [arg]
  local logf="$1"; shift
  "${COMPOSE[@]}" run --rm --no-deps -T -e VDR_SKIP_BUILD=1 -e VDR_CLIENT_ID_BASE="$BASE" \
      -e VDR_GATES_ACK_FILE=/gate/acks.txt gates-runner ./vdr-bftsmart/build.sh realgates "$@" \
      > "$logf" 2>&1
  local status=$?
  BASE=$((BASE + 1000))
  grep -v '^-- ' "$logf" | sed -n '/^\[/,/^phase /p' | sed 's/^/    /' | tee -a "$LOG"
  return $status
}

kill_all() {
  "${COMPOSE[@]}" kill -s SIGKILL "${REPLICAS[@]}" >> "$LOG" 2>&1
  local running
  running="$("${COMPOSE[@]}" ps -q --status running "${REPLICAS[@]}" 2>/dev/null)"
  [ -z "$running" ] || { say "    replicas still running after SIGKILL: $running"; return 1; }
}

restart_all() {
  "${COMPOSE[@]}" start "${REPLICAS[@]}" >> "$LOG" 2>&1
  sleep "$RESTART_S"
}

# verify <label>: RESULTS gets one line per check, so the verdict file says where it broke.
verify() {
  local label="$1"
  if phase "$DIR/verify-$label.log" durable-verify; then
    echo "$trial,$label,pass" >> "$RESULTS"
  else
    echo "$trial,$label,fail" >> "$RESULTS"
    say "  trial $trial: $label FAILED"
    return 1
  fi
}

RESULTS="${VERDICT%.txt}.csv"
echo "trial,check,outcome" > "$RESULTS"
FAILED=0
for trial in $(seq 1 "$TRIALS"); do
  DIR="${VERDICT%.txt}-logs/trial-$trial"; mkdir -p "$DIR"
  say "== trial $trial of $TRIALS"
  docker run --rm -v "$PWD/results/gate-work":/gate vdr-bench rm -f /gate/acks.txt >> "$LOG" 2>&1
  cluster_up || { say "  cluster did not start"; echo "$trial,start,fail" >> "$RESULTS"; FAILED=$((FAILED + 1)); continue; }

  ok=1
  phase "$DIR/load.log" durable-load "$LOAD_OPS" || { ok=0; echo "$trial,load,fail" >> "$RESULTS"; }
  if [ "$ok" = 1 ]; then
    kill_all && restart_all && verify quiet || ok=0
  fi
  if [ "$ok" = 1 ]; then
    phase "$DIR/midload.log" durable-load 1000000 & load_pid=$!
    sleep "$MIDLOAD_S"
    kill_all || ok=0
    wait "$load_pid" || true     # its phase ends on the first write that gets no answer
    restart_all
    verify mid-load || ok=0
  fi
  if [ "$ok" = 1 ]; then
    kill_all && restart_all && verify repeat || ok=0
  fi
  [ "$ok" = 1 ] || FAILED=$((FAILED + 1))
  for r in "${REPLICAS[@]}"; do "${COMPOSE[@]}" logs --no-color --timestamps "$r" > "$DIR/$r.log" 2>/dev/null; done
done

{
  if [ "$FAILED" -eq 0 ]; then
    echo "verdict: PASS"
    echo "$TRIALS trials: every acknowledged write and the state root survived SIGKILL of all replicas."
  else
    echo "verdict: FAIL"
    echo "$FAILED of $TRIALS trials lost acknowledged writes or did not recover. Label this arm"
    echo "'disk logging, not recoverable' and do not call it durable."
  fi
  echo "arm: $ARM"
  echo "checks:"
  sed 's/^/  /' "$RESULTS"
} > "$VERDICT"
say "$(head -1 "$VERDICT")"
[ "$FAILED" -eq 0 ]
