#!/usr/bin/env bash
# Build and run the BFT-SMaRt replication layer (BFT-SMaRt implementation plan WP1-WP8).
#
#   ./vdr-bftsmart/build.sh                    compile
#   ./vdr-bftsmart/build.sh replica <id> [cfg] run one replica process
#   ./vdr-bftsmart/build.sh bench [n]          run the harness against the real cluster
#   ./vdr-bftsmart/build.sh realgates <phase>  one real-engine gate phase (vdr-bftsmart/real-gates.sh
#                                              runs them all and owns the containers)
#
# There is no `gates` target: vdr.test.Gates builds SimulatedCluster in every gate and never
# touches BFT-SMaRt, whatever -Dvdr.replication says. Simulator gates: ./build.sh gates.
#
# THE JAR. BFT-SMaRt is not vendored. Put library-1.2.jar (and its dependencies) in
# vdr-bftsmart/lib/, or set BFTSMART_JARS to a classpath fragment. Without it this script
# compiles against stubs/ so the module can still be type-checked offline, and then REFUSES to
# run anything: a stub build proves the code is structurally sound and nothing more.
set -euo pipefail
cd "$(dirname "$0")/.."

CORE=build/classes
OUT=build/bftsmart
LIB=vdr-bftsmart/lib
CONFIG="${VDR_BFTSMART_CONFIG:-config}"

mkdir -p "$OUT" "$LIB"
[ -d "$CORE/vdr" ] || ./build.sh >/dev/null

# ---------------------------------------------------------------- classpath
if [ -n "${BFTSMART_JARS:-}" ]; then
  JARS="$BFTSMART_JARS"
elif compgen -G "$LIB/*.jar" >/dev/null; then
  JARS="$(find "$LIB" -name '*.jar' | tr '\n' ':')"
else
  JARS=""
fi

if [ -n "$JARS" ]; then
  echo "building against the real BFT-SMaRt jars"
  STUBBED=0
  CP="$CORE:$JARS"
else
  cat <<'MSG'
NOTE: no BFT-SMaRt jar found in vdr-bftsmart/lib/.
      Compiling against vdr-bftsmart/stubs/ so the module can be type-checked offline.
      This build CANNOT run: fetch the pinned jars with

        ./vdr-bftsmart/fetch-jars.sh

      or drop library-1.2.jar and its dependencies into vdr-bftsmart/lib/.
MSG
  STUBBED=1
  mkdir -p build/bftsmart-stubs
  javac -nowarn -d build/bftsmart-stubs $(find vdr-bftsmart/stubs -name '*.java')
  CP="$CORE:build/bftsmart-stubs"
fi

# VDR_SKIP_BUILD=1 (set by the compose replicas): the image already compiled the module; four
# 1 vCPU replicas recompiling it at the same instant only delays startup.
if [ "${VDR_SKIP_BUILD:-0}" = "1" ] && [ "$STUBBED" = "0" ] && [ -d "$OUT/vdr/replication/bftsmart" ]; then
  :
else
  javac -nowarn -cp "$CP" -d "$OUT" $(find vdr-bftsmart/src -name '*.java')
  echo "compiled to $OUT"
fi

refuse_if_stubbed() {
  if [ "$STUBBED" = "1" ]; then
    echo "refusing to run: this build used the API stubs, which throw on every call." >&2
    echo "Put the BFT-SMaRt jars in $LIB and rebuild." >&2
    exit 3
  fi
}

case "${1:-}" in
  replica)
    refuse_if_stubbed
    [ -n "${2:-}" ] || { echo "usage: $0 replica <id> [configDir]" >&2; exit 2; }
    # shellcheck disable=SC2086
    exec java ${VDR_REPLICA_JAVA_OPTS:-} -cp "$CORE:$OUT:$JARS" \
      -Dlogback.configurationFile="$(cd "$CONFIG" && pwd)/logback.xml" \
      vdr.replication.bftsmart.VdrReplica "$2" "${3:-$CONFIG}"
    ;;
  bench)
    refuse_if_stubbed
    # shellcheck disable=SC2086
    exec java ${VDR_BENCH_JAVA_OPTS:-} -cp "$CORE:$OUT:$JARS" \
      -Dlogback.configurationFile="$(cd "$CONFIG" && pwd)/logback.xml" \
      -Dvdr.replication=bftsmart \
      -Dvdr.bftsmart.config="$CONFIG" \
      vdr.bench.Bench "${2:-4}"
    ;;
  realgates)
    refuse_if_stubbed
    shift
    exec java -cp "$CORE:$OUT:$JARS" -Dlogback.configurationFile="$(cd "$CONFIG" && pwd)/logback.xml" \
      -Dvdr.bftsmart.config="$CONFIG" \
      -Dvdr.bftsmart.proxies="${VDR_GATES_PROXIES:-8}" \
      -Dvdr.clientIdBase="${VDR_CLIENT_ID_BASE:-1001}" \
      -Dvdr.gates.byzantine="${VDR_GATES_BYZANTINE:-}" \
      vdr.replication.bftsmart.RealGates "$@"
    ;;
  gates)
    cat >&2 <<'MSG'
refusing: vdr.test.Gates runs every gate on SimulatedCluster and never contacts BFT-SMaRt, so a
pass here would be a simulator pass labelled as a real-engine one.
  real engine (M3, M4, M6):  ./vdr-bftsmart/real-gates.sh
  simulator (all 16):        ./build.sh gates
MSG
    exit 2
    ;;
  "")
    echo
    echo "  $0 replica <id> [cfg]     start replica <id>"
    echo "  $0 bench [n]              run the harness against the cluster"
    echo "  $0 realgates <phase>      one real-engine gate phase (use real-gates.sh)"
    ;;
  *)
    echo "usage: $0 [replica <id> [cfg]|bench [n]|realgates <phase>]" >&2
    exit 2
    ;;
esac
