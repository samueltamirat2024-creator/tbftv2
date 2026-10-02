#!/usr/bin/env bash
# vCPU plan for the single-VPS stack: every node gets at least one whole vCPU.
#
#   per-node vCPUs = max(1, floor(total vCPUs / 4))
#
#   4 vCPU server  -> node1..node4 get 1 vCPU each (cores 0,1,2,3)
#   8 vCPU server  -> node1..node4 get 2 vCPU each (cores 0-1, 2-3, 4-5, 6-7)
#
# Each node is capped (docker --cpus) AND pinned to its own cores (--cpuset-cpus), so one
# busy node cannot steal time from another. The Tailored BFT runner gets the same per-replica
# budget: TAILORED_N x per-node vCPUs, one execution thread per replica (vdr.replicaThreads).
#
# The four BFT-SMaRt replicas (vdr-replica-0..3) get exactly what the four Indy nodes get:
# replica i is capped at the per-node budget and pinned to node i+1's cores. The tailored-bft
# runner, like indy-bench, is pinned to the leftover cores. Run one system at a time.
#
# Writes deploy/vps/.env, which docker compose reads automatically. Re-run it after resizing
# the server or changing TAILORED_N. Overrides:
#   NODE_CPUS=2 ./cpu-plan.sh          force the per-node budget
#   TAILORED_N=7 ./cpu-plan.sh         size the Tailored runner for n = 7
#   TOTAL_CPUS=8 ./cpu-plan.sh         plan for a different machine (dry planning)
set -euo pipefail
cd "$(dirname "$0")"

NODES=4                                   # the Indy pool is fixed at four nodes
TOTAL="${TOTAL_CPUS:-$(nproc)}"
TAILORED_N="${TAILORED_N:-4}"
PER_NODE="${NODE_CPUS:-$(( TOTAL / NODES ))}"
[ "$PER_NODE" -ge 1 ] || PER_NODE=1

range() {                                 # range <first> <count> -> "a" or "a-b"
  if [ "$2" -le 1 ]; then echo "$1"; else echo "$1-$(( $1 + $2 - 1 ))"; fi
}

NEEDED=$(( NODES * PER_NODE ))
declare -a SETS
if [ "$NEEDED" -le "$TOTAL" ]; then
  for i in $(seq 0 $(( NODES - 1 ))); do SETS[$i]="$(range $(( i * PER_NODE )) "$PER_NODE")"; done
  POOL_OK=yes
else
  # Fewer vCPUs than nodes: pinning cannot give each node its own core. Wrap around and say so.
  PER_NODE=1
  for i in $(seq 0 $(( NODES - 1 ))); do SETS[$i]="$(( i % TOTAL ))"; done
  POOL_OK=no
fi

# Web server and runners: the cores left over after the nodes; all cores if none are left.
LEFT=$(( TOTAL - NODES * PER_NODE ))
if [ "$POOL_OK" = yes ] && [ "$LEFT" -ge 1 ]; then
  AUX_SET="$(range $(( NODES * PER_NODE )) "$LEFT")"
  AUX_COUNT="$LEFT"
else
  AUX_SET="$(range 0 "$TOTAL")"
  AUX_COUNT="$TOTAL"
fi

# Tailored runner: one per-node budget per replica, capped at the machine. It runs its n
# replicas in one JVM, so it is not pinned to a node's cores -- run it with the Indy pool
# stopped (docker compose stop) when you want the two systems on identical hardware.
TAILORED_CPUS=$(( TAILORED_N * PER_NODE ))
[ "$TAILORED_CPUS" -le "$TOTAL" ] || TAILORED_CPUS="$TOTAL"
# The Indy runner is only the load generator; give it the same budget so both runners match,
# limited to the cores it is pinned to.
BENCH_CPUS="$TAILORED_CPUS"
[ "$BENCH_CPUS" -le "$AUX_COUNT" ] || BENCH_CPUS="$AUX_COUNT"

# Keep any user settings already in .env; replace only the lines this script owns.
touch .env
grep -vE '^(NODE_CPUS|NODE[1-4]_CPUSET|REPLICA_CPUS|REPLICA[0-3]_CPUSET|AUX_CPUSET|TAILORED_CPUS|BENCH_CPUS|TAILORED_N)=|^# cpu-plan' .env > .env.tmp || true
{
  cat .env.tmp
  echo "# cpu-plan.sh: $TOTAL vCPUs, $NODES nodes x $PER_NODE vCPU"
  echo "NODE_CPUS=$PER_NODE"
  for i in $(seq 1 $NODES); do echo "NODE${i}_CPUSET=${SETS[$(( i - 1 ))]}"; done
  # BFT-SMaRt replica i on node i+1's cores, with the same cap.
  echo "REPLICA_CPUS=$PER_NODE"
  for i in $(seq 0 $(( NODES - 1 ))); do echo "REPLICA${i}_CPUSET=${SETS[$i]}"; done
  echo "AUX_CPUSET=$AUX_SET"
  echo "TAILORED_N=$TAILORED_N"
  echo "TAILORED_CPUS=$TAILORED_CPUS"
  echo "BENCH_CPUS=$BENCH_CPUS"
} > .env
rm -f .env.tmp

echo "vCPU plan ($TOTAL vCPUs on this server)"
for i in $(seq 1 $NODES); do
  printf '  node%d      %s vCPU  cores %s\n' "$i" "$PER_NODE" "${SETS[$(( i - 1 ))]}"
done
for i in $(seq 0 $(( NODES - 1 ))); do
  printf '  replica-%d  %s vCPU  cores %s (same as node%d)\n' "$i" "$PER_NODE" "${SETS[$i]}" $(( i + 1 ))
done
printf '  webserver  shared     cores %s\n' "$AUX_SET"
printf '  tailored   %s vCPU  (n = %s replicas, %s vCPU each)\n' "$TAILORED_CPUS" "$TAILORED_N" \
  "$(awk -v a="$TAILORED_CPUS" -v b="$TAILORED_N" 'BEGIN { printf "%.2g", a / b }')"
printf '  indy-bench %s vCPU  cores %s\n' "$BENCH_CPUS" "$AUX_SET"
printf '  tailored-bft runner %s vCPU  cores %s (same as indy-bench)\n' "$BENCH_CPUS" "$AUX_SET"
if [ "$POOL_OK" = no ]; then
  echo "WARNING: $TOTAL vCPUs cannot give 4 nodes one vCPU each; nodes share cores. Use a 4+ vCPU server." >&2
fi
if [ $(( TAILORED_N * PER_NODE )) -gt "$TOTAL" ]; then
  echo "WARNING: n = $TAILORED_N needs $(( TAILORED_N * PER_NODE )) vCPUs for the Tailored runner; capped at $TOTAL." >&2
fi
echo "wrote deploy/vps/.env"
