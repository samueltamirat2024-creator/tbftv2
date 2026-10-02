#!/usr/bin/env bash
# Build and run the Tailored BFT VDR prototype. Java 21, no external dependencies.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/classes
mkdir -p "$OUT"
find vdr-core/src -name '*.java' > build/sources.txt
javac -d "$OUT" @build/sources.txt

case "${1:-}" in
  gates) java -cp "$OUT" vdr.test.Gates ;;
  bench) java -cp "$OUT" vdr.bench.Bench "${2:-4}" ;;
  "")    echo "compiled to $OUT — try: ./build.sh gates | ./build.sh bench [n]" ;;
  *)     echo "usage: ./build.sh [gates|bench [n]]" >&2; exit 2 ;;
esac
