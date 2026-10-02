#!/usr/bin/env bash
# Build and probe the Hyperledger Indy baseline backend.
#
# java.lang.foreign is a preview API in Java 21, so this module needs --enable-preview.
# vdr-core stays preview-free; only this module carries the flag. On Java 22+ the API is
# final and the flag can be dropped with no source change.
set -euo pipefail
cd "$(dirname "$0")/.."

CORE=build/classes
OUT=build/indy
mkdir -p "$OUT"

[ -d "$CORE" ] || ./build.sh >/dev/null
javac --release 21 --enable-preview -nowarn -cp "$CORE" -d "$OUT" \
  $(find indy-baseline/src -name '*.java') 2>&1 | grep -v 'preview' || true

LIB="${2:-${INDY_VDR_LIB:-}}"

case "${1:-}" in
  probe)
    if [ -z "$LIB" ]; then
      cat >&2 <<'MSG'
usage: ./indy-baseline/build.sh probe /path/to/libindy_vdr.so

The library ships inside the indy-vdr Python wheel; extract it with:
  pip download indy-vdr --no-deps -d /tmp/ivdr
  unzip -o /tmp/ivdr/indy_vdr-*.whl -d /tmp/ivdr/x
  ls /tmp/ivdr/x/indy_vdr/libindy_vdr.so
MSG
      exit 2
    fi
    exec java --enable-preview --enable-native-access=ALL-UNNAMED \
      -cp "$CORE:$OUT" vdr.baseline.indy.IndyProbe "$LIB"
    ;;
  bench)
    # The two Indy rows, produced by the SAME generator that produces the Tailored BFT rows.
    : "${INDY_VDR_LIB:?set INDY_VDR_LIB to libindy_vdr.dylib (macOS) or .so (Linux)}"
    : "${INDY_GENESIS:?set INDY_GENESIS to the pool transactions genesis file}"
    : "${INDY_REVOC_REG_DEF_ID:?set INDY_REVOC_REG_DEF_ID (printed by pool/setup_registry.py)}"
    exec java --enable-preview --enable-native-access=ALL-UNNAMED \
      -cp "$CORE:$OUT" \
      -Dvdr.backend=indy \
      -Dindy.lib="$INDY_VDR_LIB" \
      -Dindy.genesis="$INDY_GENESIS" \
      -Dindy.did="${INDY_SUBMITTER_DID:-V4SGRU86Z58d6TV7PBUe6f}" \
      -Dindy.revocRegDefId="$INDY_REVOC_REG_DEF_ID" \
      ${INDY_ENTRIES_PER_TXN:+-Dindy.entriesPerTxn=$INDY_ENTRIES_PER_TXN} \
      vdr.bench.Bench "${2:-4}"
    ;;
  "")
    echo "compiled to $OUT"
    echo "  ./indy-baseline/build.sh probe <libindy_vdr>   verify the binding (no pool)"
    echo "  ./indy-baseline/build.sh bench [n]             measure the two Indy rows (needs a pool)"
    ;;
  *)
    echo "usage: ./indy-baseline/build.sh [probe <libindy_vdr>|bench [n]]" >&2
    exit 2
    ;;
esac
