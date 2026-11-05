# Fault injection. Sourced by run-faults.sh, which defines COMPOSE and say().
#
# Network faults run from a throwaway container in the target's network namespace, with NET_ADMIN.
# The rules outlive that container and die with the target's namespace, so the images under test are
# never changed and both systems get the same tooling. Every fault is checked after injecting and
# after healing; one that did not take effect makes the run invalid, not a pass.

SUBNET=172.28.0.0/24

targets_for() {            # targets_for <system>: the four services, leader lookup depends on these
  case "$1" in
    tailored*) echo vdr-replica-0 vdr-replica-1 vdr-replica-2 vdr-replica-3 ;;
    indy)      echo node1 node2 node3 node4 ;;
  esac
}

ip_of() {
  case "$1" in
    vdr-replica-0) echo 172.28.0.31 ;; vdr-replica-1) echo 172.28.0.32 ;;
    vdr-replica-2) echo 172.28.0.33 ;; vdr-replica-3) echo 172.28.0.34 ;;
    node1) echo 172.28.0.11 ;; node2) echo 172.28.0.12 ;;
    node3) echo 172.28.0.13 ;; node4) echo 172.28.0.14 ;;
  esac
}

# Current leader, read from the system at injection time. A wrong guess would turn "crash the leader"
# into "crash a follower", so an unknown leader stops the run instead of defaulting.
leader_of() {
  local system="$1" regency=0 r
  case "$system" in
    tailored*)
      # BFT-SMaRt's next leader is regency mod n, and each view change logs the regency it moves to.
      for r in 0 1 2 3; do
        local seen
        seen="$("${COMPOSE[@]}" logs --no-color "vdr-replica-$r" 2>/dev/null \
                | sed -nE 's/.*next regency: ([0-9]+).*/\1/p' | sort -n | tail -1)"
        [ -n "$seen" ] && [ "$seen" -gt "$regency" ] && regency="$seen"
      done
      echo "vdr-replica-$((regency % 4))"
      ;;
    indy)
      # validator-info writes each node's view of the primary. Node names are Node1..Node4.
      "${COMPOSE[@]}" exec -T node1 sh -c 'cat $(find /home/indy -name "*_info.json" | head -1)' 2>/dev/null \
        | python3 -c '
import json, re, sys
info = json.load(sys.stdin)["Node_info"]
primary = str(info["Replicas_status"]["Node1:0"]["Primary"])
m = re.fullmatch(r"Node([1-4]):0", primary)
sys.exit(1) if not m else print("node" + m.group(1))'
      ;;
  esac
}

follower_of() {            # any service that is not the leader
  local system="$1" leader="$2" t
  for t in $(targets_for "$system"); do
    [ "$t" != "$leader" ] && { echo "$t"; return; }
  done
}

in_namespace() {           # in_namespace <service> <shell command>
  local cid
  cid="$("${COMPOSE[@]}" ps -q "$1")"
  [ -n "$cid" ] || { say "    no running container for $1"; return 1; }
  docker run --rm --network "container:$cid" --cap-add NET_ADMIN vdr-nemesis sh -c "$2"
}

event() {                  # event <inject|heal|...> [detail]
  python3 - "$NEMESIS_LOG" "$1" "${SCENARIO:-}" "${SYSTEM:-}" "${2:-}" <<'PY'
import json, sys, time
path, event, scenario, system, detail = sys.argv[1:6]
with open(path, "a", encoding="utf-8") as f:
    f.write(json.dumps({"event": event, "ts_us": int(time.time() * 1e6), "scenario": scenario,
                        "system": system, "detail": detail}) + "\n")
PY
}

NET_TOUCHED=()

iptables_block() {         # iptables_block <service> <peer spec>...  drop both ways
  local svc="$1"; shift
  local cmd="" peer
  for peer in "$@"; do
    cmd="$cmd iptables -I INPUT -s $peer -j DROP && iptables -I OUTPUT -d $peer -j DROP && "
  done
  in_namespace "$svc" "$cmd true" && NET_TOUCHED+=("$svc")
}

iptables_out_only() {      # iptables_out_only <service> <peer>: hears everything, says nothing to it
  in_namespace "$1" "iptables -I OUTPUT -d $2 -j DROP" && NET_TOUCHED+=("$1")
}

netem() {                  # netem <service> <netem args>
  in_namespace "$1" "tc qdisc add dev eth0 root netem $2" && NET_TOUCHED+=("$1")
}

crash() {                  # crash <service>...: SIGKILL, and confirm no restart policy brought it back
  "${COMPOSE[@]}" kill -s SIGKILL "$@" >> "$LOG" 2>&1 || return 1
  sleep 2
  local running
  running="$("${COMPOSE[@]}" ps -q --status running "$@" 2>/dev/null)"
  [ -z "$running" ] || { say "    still running after SIGKILL: $@"; return 1; }
  CRASHED=("$@")
}

CRASHED=()

# The rules or qdisc in a namespace, so the log proves what was in force.
net_state() {
  in_namespace "$1" 'iptables -S | grep -c DROP; tc qdisc show dev eth0 | grep -c netem' 2>/dev/null | tr '\n' ' '
}

inject() {                 # inject <system> <scenario>
  local system="$1" scenario="$2" leader follower t all
  NET_TOUCHED=(); CRASHED=()
  all=($(targets_for "$system"))
  leader="$(leader_of "$system")" || leader=""
  [ -n "$leader" ] || { say "    could not determine the leader of $system"; return 1; }
  follower="$(follower_of "$system" "$leader")"
  event inject "leader=$leader follower=$follower"
  case "$scenario" in
    crash-follower) crash "$follower" ;;
    crash-leader)   crash "$leader" ;;
    crash-all)      crash "${all[@]}" ;;
    isolate-leader) iptables_block "$leader" "$SUBNET" ;;
    partition-2-2)
      iptables_block "${all[0]}" "$(ip_of "${all[2]}")" "$(ip_of "${all[3]}")" \
        && iptables_block "${all[1]}" "$(ip_of "${all[2]}")" "$(ip_of "${all[3]}")" \
        && iptables_block "${all[2]}" "$(ip_of "${all[0]}")" "$(ip_of "${all[1]}")" \
        && iptables_block "${all[3]}" "$(ip_of "${all[0]}")" "$(ip_of "${all[1]}")"
      ;;
    delay-one)      netem "$leader" "delay ${DELAY_MS:-200}ms ${JITTER_MS:-50}ms" ;;
    delay-all)      for t in "${all[@]}"; do netem "$t" "delay ${DELAY_MS:-200}ms ${JITTER_MS:-50}ms" || return 1; done ;;
    loss-5pct)      for t in "${all[@]}"; do netem "$t" "loss 5%" || return 1; done ;;
    mute-node)      iptables_out_only "$follower" "$SUBNET" ;;
    selective-drop) iptables_out_only "$leader" "$(ip_of "$follower")" ;;
    client-kill)    return 0 ;;     # run-faults.sh owns the client
    byz-*)          return 0 ;;     # the replica has been Byzantine since it started
    *) say "    unknown scenario $scenario"; return 1 ;;
  esac || return 1
  for t in "${NET_TOUCHED[@]:-}"; do
    [ -n "$t" ] || continue
    local state
    state="$(net_state "$t")"
    event net-state "$t after inject: drop-rules/netem = $state"
    [ "$state" != "0 0 " ] || { say "    the fault on $t did not take effect"; return 1; }
  done
}

heal() {                   # heal <system> <scenario>
  local t state status=0
  for t in "${NET_TOUCHED[@]:-}"; do
    [ -n "$t" ] || continue
    in_namespace "$t" 'iptables -F; tc qdisc del dev eth0 root 2>/dev/null; true' || status=1
    state="$(net_state "$t")"
    event net-state "$t after heal: drop-rules/netem = $state"
    [ "$state" = "0 0 " ] || { say "    $t still has fault rules after healing: $state"; status=1; }
  done
  if [ "${#CRASHED[@]}" -gt 0 ]; then
    "${COMPOSE[@]}" start "${CRASHED[@]}" >> "$LOG" 2>&1 || status=1
  fi
  event heal
  return $status
}
