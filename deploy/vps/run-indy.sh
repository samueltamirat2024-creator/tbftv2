#!/usr/bin/env bash
# Indy baseline runner (the indy-bench service in deploy/vps/docker-compose.yml).
#
# 1. waits for the pool, 2. probes the libindy_vdr binding, 3. writes the revocation registry
# once per ledger, 4. runs vdr.bench.Bench with -Dvdr.backend=indy. Outputs land in /results.
set -euo pipefail
cd /work

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="/results/$STAMP"
mkdir -p "$OUT"

# ---------------------------------------------------------------- 1. pool readiness
# The web server answers /genesis with 503 until it can reach the pool.
echo "waiting for the pool at $GENESIS_URL"
GENESIS=/results/pool_transactions_genesis
for _ in $(seq 1 60); do
  if curl -fsS "$GENESIS_URL" -o "$GENESIS.tmp" 2>/dev/null; then
    mv "$GENESIS.tmp" "$GENESIS"
    break
  fi
  sleep 5
done
[ -s "$GENESIS" ] || { echo "pool not ready after 5 minutes; check: docker compose logs" >&2; exit 1; }
cp "$GENESIS" "$OUT/"
export INDY_GENESIS="$GENESIS"

# ---------------------------------------------------------------- 2. binding probe
INDY_VDR_LIB="$(python3 -c 'import indy_vdr, os; print(os.path.join(os.path.dirname(indy_vdr.__file__), "libindy_vdr.so"))')"
export INDY_VDR_LIB
./indy-baseline/build.sh probe "$INDY_VDR_LIB" | tee "$OUT/probe.log"

# ---------------------------------------------------------------- 3. setup, once per ledger
# Setup transactions are not workload. registry.env is reused on later runs; delete it
# whenever the ledger volumes are wiped (deploy/vps/reset-pool.sh does both).
#
# INDY_REGISTRY_COUNT registries are created in one pass. IndyBackend takes the next unused one
# for every sweep level and every measurement run, so no run inherits another run's revoked set
# (remediation plan §2). Without this the revoked set grows across runs and later runs are slower
# for reasons that have nothing to do with the system.
REGISTRY_ENV=/results/registry.env
REGISTRY_COUNT="${INDY_REGISTRY_COUNT:-40}"
if [ ! -s "$REGISTRY_ENV" ]; then
  python3 indy-baseline/pool/setup_registry.py \
      --genesis "$INDY_GENESIS" --seed "$INDY_TRUSTEE_SEED" \
      --count "$REGISTRY_COUNT" --out /results/registries.env | tee "$OUT/setup.log"
  grep '^export ' "$OUT/setup.log" > "$REGISTRY_ENV"
fi
# shellcheck disable=SC1090
source "$REGISTRY_ENV"
cp "$REGISTRY_ENV" "$OUT/"
[ -s /results/registries.env ] && cp /results/registries.env "$OUT/"

# ---------------------------------------------------------------- 4. harness
# The pool has four nodes, so n is fixed at 4.
[ -n "${INDY_ENTRIES_PER_TXN:-}" ] || unset INDY_ENTRIES_PER_TXN
./indy-baseline/build.sh bench 4 2>&1 | tee "$OUT/bench.log"
cp RESULTS.md "$OUT/RESULTS.md"

echo "done. outputs in deploy/vps/results/indy/$STAMP"
