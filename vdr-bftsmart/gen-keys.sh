#!/usr/bin/env bash
# Generate per-replica RSA key pairs in config/keys/ for system.communication.defaultkeys=false.
#
#   ./vdr-bftsmart/gen-keys.sh              ids 0 .. system.servers.num-1, keeps existing pairs
#   ./vdr-bftsmart/gen-keys.sh 0 1 2 3 4    these ids
#   FORCE=1 ./vdr-bftsmart/gen-keys.sh      regenerate even where a pair exists
#
# Run it on the machine that hosts the replicas. Private keys are kept out of git (.gitignore)
# and out of every Docker image (.dockerignore) by design; replicas read them from the mounted
# config directory. A multi-host deployment copies publickey<i> for every replica to every host,
# and privatekey<i> to replica i's host only.
set -euo pipefail
cd "$(dirname "$0")/.."

./vdr-bftsmart/fetch-jars.sh >/dev/null
CP="$(find vdr-bftsmart/lib -name '*.jar' | tr '\n' ':')"
mkdir -p config/keys

if [ $# -gt 0 ]; then
  IDS=("$@")
else
  N="$(sed -nE 's/^system\.servers\.num *= *([0-9]+).*/\1/p' config/system.config)"
  IDS=($(seq 0 $((N - 1))))
fi

for id in "${IDS[@]}"; do
  if [ -z "${FORCE:-}" ] && [ -f "config/keys/privatekey$id" ] && [ -f "config/keys/publickey$id" ]; then
    echo "replica $id: key pair exists, kept"
    continue
  fi
  # BFT-SMaRt 1.2's own generator, 2048-bit, writing config/keys/{public,private}key<id>.
  java -cp "$CP" bftsmart.tom.util.RSAKeyPairGenerator "$id" 2048
  chmod 600 "config/keys/privatekey$id"
  echo "replica $id: generated"
done
