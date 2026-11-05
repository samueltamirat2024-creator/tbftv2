#!/usr/bin/env bash
# Writes one arm's BFT-SMaRt config directory and prints its path. Same keys and hosts as config/;
# only the logging lines differ, so an arm cannot drift from the in-memory one in anything else.
#
#   ./arm-config.sh tailored-disk          log_to_disk, write-through log and checkpoints
#   ./arm-config.sh tailored-disk-nosync   log_to_disk, left to the OS page cache
#
# DiskStateLog opens the log with mode "rwd" when sync_log=true, so each batch's write reaches the
# device before it returns. It writes under /work/files, the replica volumes in docker-compose.yml.
set -euo pipefail
cd "$(dirname "$0")"
ARM="${1:?usage: arm-config.sh tailored-disk|tailored-disk-nosync}"
case "$ARM" in
  tailored-disk)        SYNC=true ;;
  tailored-disk-nosync) SYNC=false ;;
  *) echo "arm-config.sh: unknown arm $ARM" >&2; exit 2 ;;
esac
SRC="$(cd "${ARM_CONFIG_BASE:-../../config}" && pwd)"
DEST="$PWD/.arm-config/$ARM"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -R "$SRC" "$DEST"
rm -f "$DEST/currentView"
override() {
  grep -v "^system\.totalordermulticast\.$1 *=" "$DEST/system.config" > "$DEST/system.config.new"
  echo "system.totalordermulticast.$1 = $2" >> "$DEST/system.config.new"
  mv "$DEST/system.config.new" "$DEST/system.config"
}
override log_to_disk true
override sync_log "$SYNC"
override checkpoint_to_disk true
override sync_ckp "$SYNC"
echo "$DEST"
