# What changed: remediation phases A–D

The four fixes from the remediation plan, as implemented in this tree. Each one exists because a
number in the previous evaluation was measuring something other than what it claimed to.

---

## Phase A — a fresh revocation registry for every Indy run

**The defect.** All runs shared one revocation registry. A registry accumulates state and its
accumulator chain lengthens with every entry, so repeated runs of the *same* configuration drifted
upward: burst drain 1256 → 1628 → 2831 ms across three runs. That is registry growth, not the
baseline's behaviour, and it flattered the Tailored side by however much the last Indy run had grown.

**The fix.**

| File | Change |
|---|---|
| `indy-baseline/pool/setup_registry.py` | `--count N` / `--out PATH`. One schema and credential definition, then N independent `REVOC_REG_DEF` + initial entry pairs under tags `bench-000…`; writes `registries.env` and resets `registry.counter` to 0 |
| `indy-baseline/src/vdr/baseline/indy/IndyBackend.java` | `nextRegistry()` claims the next unused registry from that list under a lock; the constructor reads the registry's current state and **prints how many indices were already revoked at open**, with a warning when it is not 0; `close()` prints the delta; `notes()` records `fresh registry per run (N revoked at open)` |
| `deploy/vps/reset-pool.sh` (new) | `down -v`, delete `registry.env` / `registries.env` / `registry.counter`, `up -d`, wait for genesis, mint a fresh batch |
| `deploy/vps/run-indy.sh` | passes `--count $INDY_REGISTRY_COUNT` (default 40) and archives `registries.env` with the run |

**How you know it is working.** Every run prints `registry bench-0NN opened with 0 revoked index(es)`.
A non-zero count is the defect returning, and the run is not comparable.

---

## Phase B — step down whenever the merged p99 misses the target

**The defect.** The sweep picked the highest level that sustained ≥95% of the offered rate, and the
p99 target was then checked, reported and *ignored*. Operating points were being reported at a p99
well past 500 ms — which is the one number the paper's admission rule is stated in terms of.

**The fix, in `vdr-core/src/vdr/bench/Bench.java`.**

- `P99_TARGET_US` is a named constant (`-Dbench.p99TargetUs`, default 500 000) and printing a
  **WARNING** is unconditional when it is overridden, so a relaxed target cannot be quiet.
- `sweepOperatingPoint` returns *every* sustained level, not just the highest.
- `chooseLevel(name, admitted, measurer)` walks those levels downward and takes the first whose
  merged p99 meets the target. If none does, it **throws rather than reporting a number**.
- The path taken is recorded in the results notes: `admission path: 200 ops/s -> p99 900 ms,
  150 ops/s -> p99 900 ms, 100 ops/s -> p99 300 ms`. A step-down that is not visible in the output is
  a step-down nobody can check.
- `RunOutcome.notes` now comes from `backend.notes()`, replacing a hardcoded string that said
  "confidentiality off, fast path Tier 0" whatever the backend had actually been configured to do.
- The simulator provenance warning is printed only when `BACKEND.usesSimulatedReplication()`, so it
  stops appearing on real runs and starts being meaningful.

Gate **B2** drives `chooseLevel` with a fake measurer where only levels ≤ 100 meet the target, and
asserts the reported level is 100, the measurement order was `[200, 150, 100]`, the notes contain the
path, and that the harness throws when no level passes.

---

## Phase C — the same protocol for both systems

**The defect.** The two systems had been run with different windows, different run counts and, at one
point, different batching delays. Any of the three manufactures a difference.

**The fix.** `deploy/vps/run-protocol.sh` is the single entry point:

```bash
./run-protocol.sh debug indy      # 5 s / 15 s / 3 runs, to check wiring — not a result
./run-protocol.sh indy            # 60 s warm-up, 60 s window, 10 runs
./run-protocol.sh tailored-bft    # the same, on the real cluster
```

It enforces the same warm-up, window and run count on both sides; uses the same
`BATCH_DELAY_MS` for both; sweeps fine levels near each knee (`10,20,50,75,100,125,150,175,200,250`
for Indy, `250…4000` for Tailored) so a step-down lands one level below the knee rather than five;
calls `reset-pool.sh` before a reportable Indy run; and refuses to start outside tmux without
confirmation, because a dropped SSH connection has killed a multi-hour run before.

`deploy/vps/compare.py` now prefers `results/tailored-bft/` over `results/tailored/`, so a stale
simulator run cannot silently win the merge.

---

## Phase D — BFT-SMaRt instead of the simulated replication layer

**The defect.** The Tailored column's latency was, in large part, two constants in the simulator:
3000 µs charged per consensus instance and 300 µs per single-replica round trip. Those are inputs.

**The fix.** A seam, two implementations, and a new module.

| File | Role |
|---|---|
| `vdr-core/src/vdr/replication/Replication.java` (new) | the seam: `invokeOrdered`, `readTier0`, `readTier1`, `revocationTier1`, `trustedRoot`, `checkpointEverywhere`, `simulated()`, `describe()` |
| `vdr-core/src/vdr/replication/SimulatedCluster.java` | the old `Cluster`, renamed and made an implementation; `describe()` names both constants |
| `vdr-core/src/vdr/replication/ReplicationFactory.java` (new) | `-Dvdr.replication=simulated\|bftsmart`; loads the BFT-SMaRt class **reflectively**, so `vdr-core` keeps building with no jar |
| `vdr-core/src/vdr/serialization/Codec.java` (new) | canonical wire codec for `Op` / `Reply` / `Merkle.Proof`, explicit null markers — not Java serialisation (DepSpace §8) |
| `vdr-core/src/vdr/ops/Op.java` | new read kinds `ROOT`, `REVSTATUS`, and `CHECKPOINT`; `isWrite()` excludes the reads |
| `vdr-core/src/vdr/store/VdrStore.java` | `rootReply()`, `serialiseIdenticalProjection()`, `installIdenticalProjection()` for state transfer |
| `vdr-bftsmart/src/…/VdrReplica.java` (new) | server side, `DefaultSingleRecoverable`; ordered execution clocked by `MessageContext.getConsensusId()`; unordered path answers RESOLVE/ROOT/REVSTATUS and returns `UNORDERED_WRITE_REFUSED` for anything else |
| `vdr-bftsmart/src/…/BftSmartCluster.java` (new) | client side; `AsynchServiceProxy` pool, per-reply collector, explicit read targets (1 for Tier 0, f+1 for Tier 1), background root refresher |
| `config/hosts.config`, `config/system.config` (new) | n = 4 with commented 7/10 rows; archived into `environment.txt` on every run |
| `vdr-bftsmart/stubs/**`, `build.sh`, `pom.xml` (new) | offline type-checking, and a build that **refuses to run** when it used the stubs |

Design decisions worth restating, because each removes a way of accidentally reporting something
untrue:

- **`AsynchServiceProxy`, not `ServiceProxy`.** The client keeps its own f+1 matching and Merkle
  verification. That check is what makes a Byzantine replica detectable; collapsing the replies before
  the client sees them would delete it.
- **Tier 0 goes to exactly one replica.** Reads use an explicit target list. Broadcasting a Tier-0
  read to all n would measure a fan-out the design does not claim and make the M4 gate meaningless.
- **Roots are gossiped, not polled per read**, so Tier-0 verification does not cost a round trip. An
  aged root costs a Tier-1 fallback, never a wrong answer.
- **A stub build cannot produce a number.** No jar → compile against stubs → `build.sh` exits 3 on
  `replica`, `bench` and `gates`.
- **Provenance travels with the run.** `simulated()` reaches `RESULTS.md` through
  `BackendFactory.usesSimulatedReplication()`; `describe()` lands in the notes column;
  `artifact/run-all.sh` prints the engine and copies both config files into `environment.txt`.

`defaultkeys = true` in `config/system.config` is the one thing left to change before a reported run:
Paper 2 §III claims mutually authenticated channels, so the final evaluation generates per-replica
keys and sets it to `false`. The file says so in a comment at the point of the setting.

---

## New gates

`./build.sh gates` → **16 passed**. Four are new:

| Gate | Question |
|---|---|
| B1 | Does the wire codec round-trip every operation and reply canonically? |
| B2 | Is an operating point abandoned when its measured p99 misses the target? |
| B4 | Does a replica restored from a snapshot reach the same state root? |
| B4 | Does a snapshot leak per-replica confidential state? |

The last two matter because a snapshot that carries a PVSS share or a decrypted payload makes a
recovered replica diverge, and every Tier-0 inclusion proof then stops verifying *silently*.

---

## What is still outstanding

1. **Get `library-1.2.jar` into `vdr-bftsmart/lib/`.** Maven Central is not reachable from the build
   sandbox, so Phase D is type-checked but has not been run against a live cluster here. Until it is,
   the Tailored column is still simulator constants.
2. **Set `defaultkeys = false`** and generate per-replica keys.
3. **Re-run the gates against the real engine** (`./vdr-bftsmart/build.sh gates`). M3, M4 and M6 do
   not test the design until the ordering layer is real — the simulator has no view change, no leader
   and no adversarial scheduling.
4. **Freeze the image digests** before the measurement runs, not after, or the baseline cannot be
   shown to have been fixed rather than tuned.
5. **Run the protocol** for both systems and merge with `compare.py`.
