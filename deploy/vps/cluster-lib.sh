# Starting and stopping the systems under test. Sourced by faults/run-faults.sh, which defines COMPOSE, REPLICAS, NODES, LOG and say().

replica_log() { "${COMPOSE[@]}" logs --no-color "$1" 2>/dev/null; }

handshakes_complete() {
  local i j text
  for i in 0 1 2 3; do
    text="$(replica_log "vdr-replica-$i")"
    grep -qF "VDR replica $i ready" <<<"$text" || return 1
    for j in 0 1 2 3; do
      [ "$i" = "$j" ] && continue
      grep -qF "Diffie-Hellman complete with $j" <<<"$text" || return 1
    done
  done
}

replicas_down() {
  "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1 || true
}

# Fresh cluster (empty state). BFT-SMaRt 1.2 sometimes fails one replica's startup key exchange and
# that replica silently sits out (vdr-bftsmart/real-gates.sh); never run against that.
cluster_up() {
  local attempt deadline
  for attempt in 1 2 3; do
    replicas_down
    "${COMPOSE[@]}" up -d --force-recreate "${REPLICAS[@]}" >> "$LOG" 2>&1
    deadline=$((SECONDS + 180))
    until handshakes_complete; do
      [ $SECONDS -gt $deadline ] && break
      sleep 3
    done
    if handshakes_complete; then
      { replica_log vdr-replica-0 | grep "starting:" | head -1 | tee -a "$LOG"; } || true
      return 0
    fi
    say "  replica startup handshake incomplete (attempt $attempt); recreating the cluster"
  done
  return 1
}

# fresh_pool: wipe the ledger, start the four validators, fetch the genesis file, stop the ledger
# browser.
fresh_pool() {
  "${COMPOSE[@]}" rm -sf "${REPLICAS[@]}" >/dev/null 2>&1 || true
  docker compose down -v >> "$LOG" 2>&1 || true
  # Containers write these as root; delete them from a container so ownership never blocks it.
  docker run --rm -v "$PWD/results/indy":/results vdr-bench \
      rm -f /results/registry.env /results/registries.env /results/registry.counter \
            /results/pool_transactions_genesis >> "$LOG" 2>&1
  docker compose up -d "${NODES[@]}" webserver >> "$LOG" 2>&1
  echo -n "   waiting for the pool " | tee -a "$LOG"
  local i
  for i in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:${WEB_SERVER_HOST_PORT:-9000}/genesis" \
         -o results/indy/pool_transactions_genesis 2>/dev/null \
       && [ -s results/indy/pool_transactions_genesis ]; then
      say " ready after $((i * 10)) s"; break
    fi
    echo -n "."; sleep 10
    [ "$i" -eq 120 ] && { say " pool not ready after 20 min; docker compose logs webserver"; return 1; }
  done
  # The ledger browser is only needed for the genesis file. Left running it keeps reading the
  # ledger and competes with the validators for CPU during the measurement.
  docker compose stop webserver >> "$LOG" 2>&1
}
