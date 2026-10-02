#!/usr/bin/env bash
# Fetch BFT-SMaRt 1.2 and its runtime dependencies into vdr-bftsmart/lib/.
#
# 1.2 is not on Maven Central, and JitPack's POM for it declares no dependencies, so Maven cannot
# resolve it. The files come from the upstream v1.2 git tag instead: bin/BFT-SMaRt.jar (saved as
# library-1.2.jar) and the jars the tag ships in lib/. Each is checked against a pinned SHA-256:
# an unpinned consensus library makes two runs incomparable.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib

# sha256sum on Linux and in the runner image; shasum on macOS.
sha256_check() {
  if command -v sha256sum >/dev/null; then sha256sum -c --quiet -; else shasum -a 256 -c --quiet -; fi
}

TAG=https://raw.githubusercontent.com/bft-smart/library/v1.2

# local name | path in the tag | sha256
while read -r name src sum; do
  if [ ! -f "lib/$name" ]; then
    curl -sfL -o "lib/$name.part" "$TAG/$src"
    mv "lib/$name.part" "lib/$name"
  fi
  echo "$sum  lib/$name" | sha256_check || { echo "checksum mismatch: lib/$name" >&2; exit 1; }
done <<'JARS'
library-1.2.jar            bin/BFT-SMaRt.jar                8379e9fc245faec33df885c72f8d9b47d4330a61ad3e46384c39f3d088eaf866
commons-codec-1.11.jar     lib/commons-codec-1.11.jar       e599d5318e97aa48f42136a2927e6dfa4e8881dff0e6c8e3109ddbbff51d7b7d
core-0.1.4.jar             lib/core-0.1.4.jar               ba5baef7ee73fc978fc818ff4f61e1c7f2d5dbbf631f7baa7ad1e47fb9a3728c
logback-classic-1.2.3.jar  lib/logback-classic-1.2.3.jar    747258da0cdc4bd6943b9997bab95425f464143182ed4cda2f64b0789da10103
logback-core-1.2.3.jar     lib/logback-core-1.2.3.jar       91409b6338f5342e64b82c8c3bb902849a42f9ca7d61ce58b9e2dceff7e1ed53
netty-all-4.1.30.Final.jar lib/netty-all-4.1.30.Final.jar   af76cf48e0cc481b78b0ae3e2fb03ba7abf5f1f211b27ed930122b536ce8957c
slf4j-api-1.7.25.jar       lib/slf4j-api-1.7.25.jar         89a3fb0d09e65450e2107dd50697a1bb1d2e59dcb9dbca35a8e6cc76c327f861
JARS

echo "BFT-SMaRt 1.2 jars verified in vdr-bftsmart/lib/"
