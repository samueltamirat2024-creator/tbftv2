#!/usr/bin/env bash
# M3, M4 and M6 against four real BFT-SMaRt replica containers.
#
#   ./vdr-bftsmart/real-gates.sh            rebuild the image, run every scenario
#   SKIP_BUILD=1 ./vdr-bftsmart/real-gates.sh
#   SKIP_M4=1 ./vdr-bftsmart/real-gates.sh   M3 and M6 only (M4 is blocked by the client stall)
#   RESTART_TRIALS=15 SKIP_BUILD=1 ./vdr-bftsmart/real-gates.sh   only the M3 stop/restart, 15 times
#
# vdr.test.Gates answers these on SimulatedCluster, which has no leader, no view change, no state
# transfer and no network. Here the faults are real: the view-0 leader's container is stopped
# mid-run, restarted with empty memory, and replicas are started in a Byzantine mode.
#
# Each scenario gets a freshly created cluster (in-memory state, log_to_disk=false) and ends with
# its replica logs saved. Only the vdr-replica-* services are created or removed: the Indy pool in
# the same compose project is never touched. Output: build/real-gates/<UTC stamp>/.
#
# Not covered: adversarial network scheduling (delay/reorder injection). Ordering here is whatever
# the Docker network does.
set -uo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f deploy/vps/docker-compose.yml -f deploy/vps/docker-compose.gates.yml
         --profile bftsmart)
REPLICAS=(vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3)
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="build/real-gates/$STAMP"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
FAILED=0
CLIENT_BASE=1001

say() { echo "$*" | tee -a "$SUMMARY"; }

# ------------------------------------------------------------------ preflight
./vdr-bftsmart/fetch-jars.sh >/dev/null || { echo "BFT-SMaRt jars missing" >&2; exit 1; }
if grep -Eq '^system\.communication\.defaultkeys *= *false' config/system.config; then
  for i in 0 1 2 3; do
    [ -f "config/keys/privatekey$i" ] && [ -f "config/keys/publickey$i" ] \
      || { echo "defaultkeys=false but config/keys/*key$i is missing" >&2; exit 1; }
  done
fi
docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

{
  echo "# Real-engine gates, $STAMP"
  echo "host: $(uname -a)"
  echo "docker: $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
  echo "jars:"; (cd vdr-bftsmart/lib && shasum -a 256 ./*.jar) | sed 's/^/  /'
  echo "system.config:"; grep -v '^\s*#' config/system.config | grep -v '^\s*$' | sed 's/^/  /'
  echo
} > "$OUT/environment.txt"
cp "$OUT/environment.txt" "$SUMMARY"

if [ -z "${SKIP_BUILD:-}" ]; then
  echo "building the vdr-bench image (replicas compile from the image's copy of the tree)..."
  "${COMPOSE[@]}" build vdr-replica-0 > "$OUT/image-build.log" 2>&1 \
    || { echo "image build failed; see $OUT/image-build.log" >&2; exit 1; }
fi

# ------------------------------------------------------------------ cluster control
cluster_down() {
  "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1
}

save_logs() {
  local dir="$OUT/$1"; mkdir -p "$dir"
  for r in "${REPLICAS[@]}"; do "${COMPOSE[@]}" logs --no-color "$r" > "$dir/$r.log" 2>&1; done
}

# Captured first, then searched: `logs | grep -q` exits on the first match, `logs` then dies of
# SIGPIPE, and under pipefail a match reads as a miss.
replica_log_has() {
  local text
  text="$("${COMPOSE[@]}" logs --no-color "vdr-replica-$1" 2>/dev/null)"
  grep -qF "$2" <<<"$text"
}

# Every replica must have finished the signed Diffie-Hellman exchange with each of the other three.
# BFT-SMaRt 1.2 has a startup race where one side reads an empty signature, the other side thinks
# the channel is fine, and the failed side never retries: that replica then rejects all of the
# peer's messages ("Secret key expected") and silently sits out, using up the f = 1 budget before
# any gate injects a fault. Seen in 2 of the first 5 runs. Such a cluster is never gated.
handshakes_complete() {
  local i j text missing=""
  for i in 0 1 2 3; do
    text="$("${COMPOSE[@]}" logs --no-color "vdr-replica-$i" 2>/dev/null)"
    for j in 0 1 2 3; do
      [ "$i" = "$j" ] && continue
      grep -qF "Diffie-Hellman complete with $j" <<<"$text" || missing="$missing $i->$j"
    done
  done
  [ -z "$missing" ] && return 0
  HANDSHAKE_MISSING="$missing"
  return 1
}

# cluster_up <byzantine replica index or -> <behaviour>: retries a cluster whose startup handshake
# failed, and records each retry in the summary (it is a finding about the engine, not noise).
cluster_up() {
  local attempt
  for attempt in 1 2 3; do
    cluster_start "$@" || return 1
    local deadline=$((SECONDS + 30))
    until handshakes_complete; do
      [ $SECONDS -gt $deadline ] && break
      sleep 2
    done
    handshakes_complete && return 0
    say "  startup handshake failed (attempt $attempt): no authenticated channel for$HANDSHAKE_MISSING"
    save_logs "handshake-failure-$attempt-$(date -u +%H%M%S)"
    HANDSHAKE_RETRIES=$((HANDSHAKE_RETRIES + 1))
  done
  say "  giving up: the cluster never started with all channels authenticated"
  return 1
}
HANDSHAKE_RETRIES=0

cluster_start() {
  local byz="$1" behaviour="${2:-}"
  cluster_down
  for i in 0 1 2 3; do
    if [ "$i" = "$byz" ]; then export "VDR_BYZANTINE_$i=$behaviour"; else export "VDR_BYZANTINE_$i="; fi
  done
  "${COMPOSE[@]}" up -d --force-recreate "${REPLICAS[@]}" >/dev/null 2>&1 \
    || { say "  cluster failed to start"; return 1; }
  local deadline=$((SECONDS + 240))
  for i in 0 1 2 3; do
    until replica_log_has "$i" "VDR replica $i ready"; do
      [ $SECONDS -gt $deadline ] && { say "  replica $i never became ready"; return 1; }
      sleep 2
    done
  done
  if [ "$byz" != "-" ] && ! replica_log_has "$byz" "BYZANTINE MODE: $behaviour"; then
    say "  replica $byz did not report BYZANTINE MODE: $behaviour; a pass would be vacuous"
    return 1
  fi
}

# phase <scenario> <RealGates phase> [arg]
phase() {
  local scenario="$1"; shift
  local log="$OUT/$scenario/$1.log"; mkdir -p "$OUT/$scenario"
  "${COMPOSE[@]}" run --rm -T \
      -e VDR_CLIENT_ID_BASE="$CLIENT_BASE" -e VDR_GATES_BYZANTINE="${GATES_BYZ:-}" \
      gates-runner ./vdr-bftsmart/build.sh realgates "$@" > "$log" 2>&1
  local status=$?
  CLIENT_BASE=$((CLIENT_BASE + 1000))       # never reuse a client id against a live cluster
  # BFT-SMaRt's own client lines ("-- Connecting to replica ...") stay in the phase log only.
  sed -n '/^\[/,/^phase /p' "$log" | grep -v '^-- ' | tee -a "$SUMMARY"
  if [ $status -ne 0 ]; then
    FAILED=$((FAILED + 1))
    grep -q '^phase ' "$log" || say "  phase $1 exited $status before reporting; see $log"
  fi
  return $status
}

trap 'cluster_down' EXIT

# ------------------------------------------------------------------ restart trials
# RESTART_TRIALS=<k>: only the M3 stop/restart scenario, k times on fresh clusters, to measure how
# often a restarted replica fails to rejoin. One row per trial in restart-trials.csv. The outcome
# is classified from replica 0's log AFTER its restart:
#   recovered         m3-verify passed (all four roots equal)
#   stuck-leaderchange  no state installed, and replica 0 kept re-sending a STOP for a regency the
#                     others had already installed (BFT-SMaRt 1.2 recovery livelock)
#   handshake         replica 0 did not finish the key exchange with every peer after restart
#   other             failed some other way; see that trial's logs
if [ -n "${RESTART_TRIALS:-}" ]; then
  CSV="$OUT/restart-trials.csv"
  echo "trial,outcome,startup_handshake_retries,snapshot_installs,stop_retransmissions,dh_peers_after_restart,reconverge_rounds,first_write_leader_down_ms" > "$CSV"
  for t in $(seq 1 "$RESTART_TRIALS"); do
    s="trial-$t"
    say ""
    say "== $s of $RESTART_TRIALS"
    before=$HANDSHAKE_RETRIES
    if ! { cluster_up - && phase "$s" ready; }; then
      echo "$t,setup-failed,$((HANDSHAKE_RETRIES - before)),,,,," >> "$CSV"
      save_logs "$s"; continue
    fi
    phase "$s" m3-load "${M3_OPS:-10000}"
    "${COMPOSE[@]}" stop vdr-replica-0 >/dev/null 2>&1
    phase "$s" m3-leaderdown "${M3_DOWN_OPS:-1200}"
    "${COMPOSE[@]}" start vdr-replica-0 >/dev/null 2>&1
    phase "$s" m3-verify; verified=$?
    save_logs "$s"

    log0="$OUT/$s/vdr-replica-0.log"
    start_line=$(grep -n "VDR replica 0 starting" "$log0" | tail -1 | cut -d: -f1)
    after="$(tail -n +"${start_line:-1}" "$log0")"
    installs=$(grep -c "installing state-transfer snapshot" <<<"$after")
    stops=$(grep -c "Re-transmitting STOP" <<<"$after")
    peers=$(grep -oE "Diffie-Hellman complete with [0-9]+" <<<"$after" | awk '{print $NF}' | sort -u | tr '\n' ' ' | sed 's/ $//')
    rounds=$(sed -nE 's/.*after ([0-9]+) rounds of traffic.*/\1/p' "$OUT/$s/m3-verify.log")
    firstms=$(sed -nE 's/.*first write with leader down: ([0-9]+) ms.*/\1/p' "$OUT/$s/m3-leaderdown.log")
    if [ "$verified" -eq 0 ]; then outcome=recovered
    elif [ "$peers" != "1 2 3" ]; then outcome=handshake
    elif [ "$installs" -eq 0 ] && [ "$stops" -gt 0 ]; then outcome=stuck-leaderchange
    else outcome=other; fi
    echo "$t,$outcome,$((HANDSHAKE_RETRIES - before)),$installs,$stops,$peers,$rounds,$firstms" >> "$CSV"
    say "  trial $t: $outcome (snapshot installs $installs, STOP retransmissions $stops)"
  done
  say ""
  say "restart trials: $(tail -n +2 "$CSV" | cut -d, -f2 | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
  say "startup handshake retries: $HANDSHAKE_RETRIES   (table: $CSV)"
  exit 0
fi

# ------------------------------------------------------------------ scenarios
say "== honest cluster: ${SKIP_M4:+(M4 skipped) }M4, then M3 (leader stop -> view change -> restart -> state transfer)"
if cluster_up - && phase honest ready; then
  if [ -n "${SKIP_M4:-}" ]; then say "  (M4 skipped: SKIP_M4 set)"; else phase honest m4; fi
  phase honest m3-load "${M3_OPS:-10000}"
  "${COMPOSE[@]}" stop vdr-replica-0 >/dev/null 2>&1
  say "  (stopped vdr-replica-0, the view-0 leader)"
  phase honest m3-leaderdown "${M3_DOWN_OPS:-1200}"
  "${COMPOSE[@]}" start vdr-replica-0 >/dev/null 2>&1
  say "  (restarted vdr-replica-0 as a new JVM with an empty store)"
  phase honest m3-verify
  save_logs honest
  say "  evidence: regency lines in replicas 1-3: $(cat "$OUT"/honest/vdr-replica-[123].log | grep -ci 'regency')"
  say "  evidence: replica 0 snapshot installs: $(grep -c 'installing state-transfer snapshot' "$OUT/honest/vdr-replica-0.log")"
else
  FAILED=$((FAILED + 1)); save_logs honest
fi

m6() {  # m6 <scenario> <byz index> <behaviour> <phase> [arg]
  local scenario="$1" byz="$2" behaviour="$3"; shift 3
  say ""
  say "== $scenario: replica $byz runs $behaviour"
  if cluster_up "$byz" "$behaviour" && phase "$scenario" ready; then
    GATES_BYZ="$behaviour" phase "$scenario" "$@"
  else
    FAILED=$((FAILED + 1))
  fi
  save_logs "$scenario"
}

m6 m6-forge            2 FORGE_DOC       m6-forge
m6 m6-stale            1 STALE_READ      m6-stale
m6 m6-suppress-revoke  3 SUPPRESS_REVOKE m6-suppress "${M6_ROUNDS:-25}"
m6 m6-suppress-stale   3 STALE_READ      m6-suppress "${M6_ROUNDS:-25}"
m6 m6-suppress-equiv   3 EQUIVOCATE      m6-suppress "${M6_ROUNDS:-25}"

say ""
say "startup handshake retries: $HANDSHAKE_RETRIES"
say "failed phases: $FAILED   (full logs: $OUT)"
[ "$FAILED" -eq 0 ]
