#!/usr/bin/env bash
# Phase C / Phase E: the full measurement protocol for one system (remediation plan §4, §6).
#
#   ./run-protocol.sh indy          Hyperledger Indy baseline, 60 s / 60 s / 10 runs
#   ./run-protocol.sh tailored      Tailored BFT VDR on the SIMULATED layer (not reportable)
#   ./run-protocol.sh tailored-bft  Tailored BFT VDR on BFT-SMaRt
#   ./run-protocol.sh debug indy    the same path with short windows, for checking wiring
#   ./run-protocol.sh equal-load indy           both mixes at FIXED offered loads (no sweep), so
#   ./run-protocol.sh equal-load tailored-bft   latency is compared at the same load on both
#
# WHAT THIS ENFORCES
#   - both systems get the same warm-up, steady window and run count (the defaults in Bench)
#   - Indy gets a fresh revocation registry per run, from a pool reset beforehand
#   - the sweep has fine levels near each knee, so a step-down lands close rather than far below
#   - the run happens inside tmux, because a dropped SSH connection has killed runs before
#
# WHAT IT DOES NOT DO
#   Freeze the image digests. Do that first (milestone M8) or the baseline cannot be shown to have
#   been frozen rather than tuned afterwards:  docker compose images --quiet | xargs docker inspect
set -euo pipefail
cd "$(dirname "$0")"

DEBUG=0
if [ "${1:-}" = "debug" ]; then
  DEBUG=1
  shift
fi
EQUAL=0
if [ "${1:-}" = "equal-load" ]; then
  EQUAL=1
  shift
fi
SYSTEM="${1:-}"

if [ "$DEBUG" = "1" ]; then
  # Short windows exercise every path in minutes. They are NOT a result: with a 15 s window the
  # tail near the knee is unstable and confidence intervals are degenerate.
  SETTINGS="-Dbench.warmupMs=5000 -Dbench.steadyMs=15000 -Dbench.runs=3"
  LABEL="DEBUG (short windows; not reportable)"
else
  # Bench defaults are already the plan's 60 s / 60 s / 10 runs; passing nothing keeps them.
  SETTINGS=""
  LABEL="full protocol (60 s warm-up, 60 s window, 10 runs)"
fi

# Fine levels near each knee. A step-down measures a whole configuration again, so landing one
# step below the knee costs ~40 minutes; landing five steps below costs a defensible result.
# Indy's knees were between 50 and 75 (bursty-revoke) and 200 and 250 (read-heavy); Tailored's
# was between 2000 and 3000. The extra levels there put the reported ratio within ~10%.
INDY_LEVELS="10,20,50,60,65,70,75,100,125,150,175,200,225,250"
TAILORED_LEVELS="250,500,750,1000,1250,1500,1750,2000,2250,2500,2750,3000,4000"

# The batching delay must match on both sides. Different delays manufacture a speed-up.
BATCH_DELAY_MS="${VDR_MAX_BATCH_DELAY_MS:-50}"

# Equal-offered-load levels. Only loads BOTH systems sustain: Indy saturates bursty-revoke past
# ~50 ops/s, so 200 ops/s there would compare Tailored with Indy's queue. Override per mix.
EQ_READ_HEAVY="${EQ_READ_HEAVY:-50,200}"
EQ_BURSTY="${EQ_BURSTY:-25,50}"

# Indy takes one fresh revocation registry per sweep level and per measured run. Worst case for
# the full protocol: 14 levels x 2 mixes + 10 runs x 2 mixes + two step-downs per mix (40) = 88.
# Equal load: 4 levels x 10 runs = 40. Too few and the run dies mid-way on an exhausted pool.
if [ "$EQUAL" = "1" ]; then
  INDY_REGISTRIES="${INDY_REGISTRY_COUNT:-50}"
  LEVEL_FLAGS="-Dbench.equalLoad.readHeavy=$EQ_READ_HEAVY -Dbench.equalLoad.burstyRevoke=$EQ_BURSTY"
  LABEL="$LABEL, equal offered load (read-heavy $EQ_READ_HEAVY, bursty-revoke $EQ_BURSTY ops/s)"
else
  INDY_REGISTRIES="${INDY_REGISTRY_COUNT:-100}"
  LEVEL_FLAGS=""
fi

if [ -z "${TMUX:-}" ]; then
  echo "NOTE: not inside tmux. A full run takes hours and a dropped connection kills it."
  echo "      Start one with:  tmux new -s bench"
  echo
  read -r -p "continue anyway? [y/N] " reply
  [ "$reply" = "y" ] || exit 1
fi

echo "=== $SYSTEM: $LABEL ==="

case "$SYSTEM" in
  indy)
    # A fresh registry per run comes from the pool this reset creates (remediation plan §2).
    if [ "$DEBUG" = "0" ]; then
      echo "resetting the pool so every run starts on a clean registry"
      ./reset-pool.sh "$INDY_REGISTRIES"
    fi
    docker compose run --rm -e JAVA_TOOL_OPTIONS="$SETTINGS $LEVEL_FLAGS \
        -Dindy.maxBatchDelayMs=$BATCH_DELAY_MS \
        -Dbench.levels=$INDY_LEVELS" indy-bench
    ;;
  tailored)
    echo "WARNING: the simulated ordering layer is in use. These numbers validate the pipeline;"
    echo "         they are not results. Use 'tailored-bft' once the cluster is up."
    docker compose run --rm -e JAVA_TOOL_OPTIONS="$SETTINGS $LEVEL_FLAGS \
        -Dvdr.maxBatchDelayMs=$BATCH_DELAY_MS \
        -Dbench.levels=$TAILORED_LEVELS" tailored
    ;;
  tailored-bft)
    # Per-replica keys are generated on this machine and never shipped (git, image). Without them
    # a replica dies at startup on a missing key file, and the run measures the survivors.
    if grep -Eq '^system\.communication\.defaultkeys *= *false' ../../config/system.config; then
      N="$(sed -nE 's/^system\.servers\.num *= *([0-9]+).*/\1/p' ../../config/system.config)"
      for i in $(seq 0 $((N - 1))); do
        if [ ! -f "../../config/keys/privatekey$i" ] || [ ! -f "../../config/keys/publickey$i" ]; then
          echo "config/keys/ has no key pair for replica $i (defaultkeys=false)." >&2
          echo "Generate them on this machine first:  ../../vdr-bftsmart/gen-keys.sh" >&2
          exit 1
        fi
      done
    else
      echo "WARNING: defaultkeys=true -- every replica shares BFT-SMaRt's public demo key, so the"
      echo "         replica channels are not authenticated. Not reportable for Paper 2 §III."
    fi
    echo "bringing up the four BFT-SMaRt replicas"
    docker compose --profile bftsmart up -d vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3
    sleep 20                      # replicas need to find each other before the first request
    docker compose --profile bftsmart run --rm \
        -e JAVA_TOOL_OPTIONS="$SETTINGS $LEVEL_FLAGS -Dvdr.maxBatchDelayMs=$BATCH_DELAY_MS \
            -Dbench.levels=$TAILORED_LEVELS" \
        tailored-bft
    ;;
  *)
    echo "usage: $0 [debug] [equal-load] {indy|tailored|tailored-bft}" >&2
    exit 2
    ;;
esac

echo
echo "=== done. Merge the two systems with:  python3 compare.py      (operating points)"
echo "                                          python3 compare.py --equal-load ==="
