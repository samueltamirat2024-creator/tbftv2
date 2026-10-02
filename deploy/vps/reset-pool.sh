#!/usr/bin/env bash
# Reset the Indy pool and create a fresh set of revocation registries.
#
#   ./reset-pool.sh [COUNT]        # default 40
#
# Run this BEFORE each measured configuration, never between the runs of one: the runs of a
# configuration share a populated DID set, and each takes its own registry from the list this
# script writes (remediation plan §2).
#
# WHY. The revocation registry's revoked set and the ledger itself grow across runs. Burst drain
# rose 1,256 -> 1,628 -> 2,831 ms over three successive runs against one pool, and read-heavy p99
# at low load rose from about 205 ms to about 300 ms. Runs on a growing ledger are not comparable
# with each other.
#
# DESTRUCTIVE: `docker compose down -v` deletes the ledger volumes permanently. Results under
# ./results are untouched.
set -euo pipefail
cd "$(dirname "$0")"

COUNT="${1:-40}"
SEED="${INDY_TRUSTEE_SEED:-000000000000000000000000Trustee1}"

echo "== resetting the Indy pool (ledger volumes will be deleted) =="
docker compose down -v

rm -f results/indy/registry.env \
      results/indy/registries.env \
      results/indy/registry.counter
mkdir -p results/indy

echo "== starting the pool =="
docker compose up -d

echo -n "== waiting for the genesis file "
for i in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:${WEB_SERVER_HOST_PORT:-9000}/genesis" -o results/indy/pool_transactions_genesis 2>/dev/null; then
    echo " ready after ${i}0s =="
    break
  fi
  echo -n "."
  sleep 10
  if [ "$i" -eq 120 ]; then
    echo
    echo "the pool did not become ready in 20 minutes; check: docker compose logs webserver" >&2
    exit 1
  fi
done

echo "== creating $COUNT revocation registries =="
docker compose run --rm --entrypoint /opt/venv/bin/python indy-bench \
  /work/indy-baseline/pool/setup_registry.py \
  --genesis /results/pool_transactions_genesis \
  --seed "$SEED" \
  --count "$COUNT" \
  --out /results/registries.env \
  | tee results/indy/setup.log

echo
echo "Pool reset. $COUNT registries are listed in results/indy/registries.env and the counter is"
echo "at 0. Every backend now takes the next unused registry, so each run starts clean."
