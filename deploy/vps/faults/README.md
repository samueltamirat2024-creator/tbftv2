# Fault campaign

Jepsen-style in method (history, nemesis, independent checker), written for this repository. It does not use
the Jepsen framework.

```
./faults/run-faults.sh                                                  # about 24 h
REPS=1 SYSTEMS=tailored SCENARIOS="crash-leader" ./faults/run-faults.sh  # pilot, about 7 min
python3 faults/test_check_history.py                                    # checker tests, no cluster needed
```

Output goes to `results/faults/<stamp>/`: `SUMMARY.md`, plus `<system>/<scenario>/rep-<k>/` holding
`history*.jsonl`, `nemesis.jsonl`, `check.json` and node logs. Run this on the frozen commit, and never at the
same time as a measurement.

## One run

1. Fresh cluster or wiped ledger.
2. `vdr.bench.History` starts: 4 writers at 5 ops/s each, 2 readers per read level at 10 ops/s each, and one
   revoker at 1 op/s, over 16 hot DIDs. Each read path is tested separately.
3. 60 s healthy, 60 s fault, 120 s healed.
4. Final checks: every DID at the strongest read, every revocation, then state digests from each replica.
5. `check-history.py` gives the verdict for the run.

## Scenarios

| Scenario | Fault | Tailored | Indy |
|---|---|---|---|
| `crash-follower`, `crash-leader` | SIGKILL, restart at heal | yes | yes |
| `crash-all` | SIGKILL all four, restart at heal | disk arm only: in memory loses everything by design | yes |
| `isolate-leader` | leader cut off from the whole subnet, both ways | yes | yes |
| `partition-2-2` | no quorum on either side; clients reach everyone | yes | yes |
| `delay-one`, `delay-all` | 200 ms ± 50 ms netem, on the leader or on all | yes | yes |
| `loss-5pct` | 5% packet loss on all | yes | yes |
| `mute-node` | follower hears everything and sends nothing | yes | yes |
| `selective-drop` | leader's messages to one follower are dropped | yes | yes |
| `client-kill` | SIGKILL the client; a new one reads the old client's DIDs | yes | yes |
| `byz-equivocate`, `byz-suppress-revoke`, `byz-stale-read`, `byz-forge-doc` | `VDR_BYZANTINE` on replica 1 | yes | no |

Each run confirms its fault: leader looked up at injection, crashed services confirmed down, and rule and qdisc
counts logged after injecting and after healing. A run where this fails is recorded in `invalid.txt` and never
counts as evidence.

## Reading the result

`check-history.py` separates **safety violations** from **what each read level met**:

- Violations on any read path: lost acknowledged write, a read of a value never written, a refused write visible,
  a revocation forgotten after its acknowledgement, replicas that do not converge. Also non-linearizable histories
  on `tier2`, the ordered path.
- Measured, not judged: linearizability, monotonic reads per reader, and staleness, for `tier0`, `tier1` and Indy's
  `nym`. Tier 0 and Tier 1 answer from the latest Merkle checkpoint, and Indy reads one node, so stale reads may be
  legitimate. A local run saw reads several seconds stale on tiers 0 and 1. The write-up must state the level each
  path meets, not assume it. In particular, check any claim that Tier 1 is "freshness-critical" for DID documents
  against these numbers.
- A run with no acknowledged writes, no final reads, or no digests gives **no verdict**, not a pass.

## Not covered (say so in the write-up)

- **Clock skew.** It needs time faking inside each image.
- **Byzantine faults on Indy.** They would need patching Indy, which would test the patch.
- **Byzantine message-level attacks on Tailored.** The behaviours act at the service layer. BFT-SMaRt's consensus
  messages are not tampered with; `mute-node` and `selective-drop` are the closest network-level approximations.
- **Writer-against-writer races on one DID.** Both backends serialise updates per DID in the client.
- **Mid-burst client kill.** `client-kill` happens during the steady workload, not during the 800-revocation burst.
- **Revocation per handle on Indy.** `IndyBackend` does not map a handle to its index, so Indy is checked by the
  registry's revoked count (no fewer than acknowledged) rather than handle by handle.
- **Whether findings hold at n > 4.** Only n = 4 is run.

## Not yet run

A one-repetition laptop run against real replica JVMs through a `docker` shim, with shortened phases, covered
`crash-leader`, `client-kill`, `byz-stale-read`, `byz-forge-doc` and the `tailored-disk` `crash-all`. These are pipeline
checks, not results. The shim records firewall rules without dropping a packet, so `partition-2-2` ran with no real
partition; the checker now refuses that run.

**Never run yet:** the real Docker paths, every `tc` and `iptables` rule, all Indy paths including the leader lookup
and `validator-info` digests. Run a one-repetition pilot of each system before a campaign, and check that
`partition-2-2` gets a verdict, because that is the only proof so far that the rules stop traffic. Two assumptions
the pilot must confirm for Indy: `*_info.json` under `/home/indy`, with `Node_info.Replicas_status` and
`Committed_state_root_hashes`. If they are wrong, `crash-leader` stops the run as invalid, and convergence is
reported as "not checked". Neither can produce a false pass.

## Restart trials

```
RESTART_TRIALS=30 SKIP_BUILD=1 ../../vdr-bftsmart/real-gates.sh
python3 faults/summarize-faults.py --restart-trials ../../build/real-gates/<stamp>/restart-trials.csv
```

Earlier trials on arm64 recovered 14 of 15, with one stuck in a BFT-SMaRt leader-change livelock. Those predate
commit 51f87a1. Report the VPS rate with its Wilson interval.
