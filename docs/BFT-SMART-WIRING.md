# The real consensus core (plan §4) — Phase D

The simulator is no longer the only replication layer. Everything above L1 codes against
`vdr.replication.Replication`, and there are two implementations:

| Implementation | Module | What it is |
|---|---|---|
| `vdr.replication.SimulatedCluster` | `vdr-core` | deterministic in-JVM total-order multicast; **latency comes from two hardcoded constants** |
| `vdr.replication.bftsmart.BftSmartCluster` | `vdr-bftsmart` | real BFT-SMaRt 1.2 cluster over the network |

Selection is one system property, read by `vdr.replication.ReplicationFactory`:

```bash
-Dvdr.replication=simulated     # default
-Dvdr.replication=bftsmart      # loads vdr.replication.bftsmart.BftSmartCluster reflectively
```

`vdr-core` does not depend on `vdr-bftsmart` or on BFT-SMaRt: the factory loads the class by name,
so the core still compiles and the gates still run with no jar present. Whether the numbers in a run
came from a simulator is not a matter of remembering which flag was passed —
`Replication.simulated()` is surfaced through `BackendFactory.usesSimulatedReplication()`, and
`Bench` prints the provenance warning into `RESULTS.md` only when it is true.

## 1. The interface

```java
public interface Replication extends AutoCloseable {
    int n();
    int f();
    CompletableFuture<List<Reply>> invokeOrdered(Op op);   // ordered path, one consensus instance
    Reply readTier0(String did, int preferredReplica);     // ONE replica, unordered
    List<Reply> readTier1(String did, int startReplica);   // f+1 replicas, unordered
    List<Reply> revocationTier1(String registryId, String handle, int startReplica);
    byte[] trustedRoot();                                  // f+1-agreed checkpoint root
    long trustedEpoch();
    boolean simulated();
    void checkpointEverywhere();
    String describe();                                     // recorded in RESULTS.md notes
    void close();
}
```

The client keeps its own f+1 matching and its own Merkle verification. That is deliberate: it is the
check that makes a Byzantine replica detectable at all, so the replication layer hands up the
individual replies and never an "agreed" answer.

## 2. Layout

```
vdr-bftsmart/
  src/vdr/replication/bftsmart/
    VdrReplica.java        # server side: DefaultSingleRecoverable, one process per replica
    BftSmartCluster.java   # client side: AsynchServiceProxy pool + reply collector
  stubs/bftsmart/**        # BFT-SMaRt API surface, type-check only, NEVER on the runtime classpath
  lib/                     # put library-1.2.jar and its dependencies here
  fetch-jars.sh            # fetches the pinned 1.2 jars into lib/
  pom.xml                  # dependency coordinates, for the record (does not resolve)
  build.sh                 # compile / replica / bench / gates
config/
  hosts.config             # n = 4, with commented rows for 7 and 10
  system.config            # the settings plan §4 fixes; archived with every run
```

## 3. Getting the jar

BFT-SMaRt is not vendored, and 1.2 is not on Maven Central (JitPack has the jar but its POM
declares no dependencies), so Maven cannot fetch it. Either:

```bash
./vdr-bftsmart/fetch-jars.sh
```

which takes `bin/BFT-SMaRt.jar` and the bundled `lib/` jars from the upstream v1.2 tag and checks
each against a pinned SHA-256,

or drop `library-1.2.jar` and its dependencies into `vdr-bftsmart/lib/`, or point
`BFTSMART_JARS` at a classpath fragment.

**Without a jar the build falls back to `stubs/` and then refuses to run anything.** The stubs throw
on every call; they exist so the module can be type-checked offline, and `build.sh` will not start a
replica, a bench or a gate run from a stub build. A stub build proves the code is structurally sound
and nothing more.

## 4. Running

```bash
./vdr-bftsmart/build.sh                      # compile
./vdr-bftsmart/build.sh replica 0            # in four terminals: ids 0,1,2,3
./vdr-bftsmart/build.sh bench 4              # harness against the real cluster
./vdr-bftsmart/real-gates.sh                 # M3/M4/M6 against real replica containers
```

`real-gates.sh` owns the containers: it stops the view-0 leader mid-run, restarts it empty so it
must state-transfer, and starts replicas in Byzantine modes (`deploy/vps/docker-compose.gates.yml`,
`VDR_BYZANTINE`). `vdr.test.Gates` cannot do this: every gate builds a `SimulatedCluster`, so it
never touches BFT-SMaRt whatever `-Dvdr.replication` says.

Under Docker Compose the replicas are a profile, so they are not started by the simulated runs:

```bash
cd deploy/vps
./run-protocol.sh tailored-bft               # brings up vdr-replica-0..3, then the harness
```

`config/` is mounted read-only into every replica and into the runner, and `artifact/run-all.sh`
copies `hosts.config` and `system.config` verbatim into `environment.txt`. A consensus configuration
that was not recorded makes two runs incomparable.

## 5. Configuration the plan fixes (§4)

| Key | Value | Why |
|---|---|---|
| `system.servers.num` | 4 / 7 / 10 | f = 1 / 2 / 3; Paper 2's scalability sweep |
| `system.servers.f` | 1 / 2 / 3 | n = 3f+1 |
| `system.totalordermulticast.maxbatchsize` | swept (400 default) | DepSpace batch agreement; what bounds a revocation burst |
| `system.totalordermulticast.state_transfer` | `true` | needed for the snapshot obligations in §7 |
| `system.totalordermulticast.checkpoint_period` | sweep {1k, 5k, 20k} | BFT-SMaRt's own log checkpoint — **not** the Merkle checkpoint interval (`-Dvdr.checkpointInterval`) |
| `system.communication.useSenderThread` | `true` | keeps signature verification off the ordering thread |
| `system.communication.defaultkeys` | **`false` before any reported run** | Paper 2 §III claims mutually authenticated channels; the shipped file says `true` for bring-up and says so in a comment |

Do not modify BFT-SMaRt's ordering. The only permitted core touch-point is the Byzantine
fault-injection adapter (plan §13), which is never enabled in a benchmark image —
`Replication.assertNoByzantineInBenchmark()` enforces that at run time on both implementations.

## 6. Client-side choices worth knowing about

**`AsynchServiceProxy`, not `ServiceProxy`.** `ServiceProxy.invokeOrdered` collapses the replies into
one answer before the client sees them, which would remove the f+1 matching and the proof check.
`BftSmartCluster` keeps every reply, returns as soon as `needed` of them agree by digest, and lets
`VdrClient` decide whether that is a quorum.

**Tier 0 really is one replica.** Reads go to an explicit target list: one replica for Tier 0, f+1
for Tier 1. Sending a Tier-0 read to all n would measure a fan-out the design does not claim and
would make the M4 gate meaningless.

**Roots are gossiped, not polled per read.** `VdrClient` consults the trusted root on every Tier-0
verification. Against the simulator that reads memory; over a network it would be a round trip per
read. A background refresher keeps the f+1-agreed root current instead (`-Dvdr.bftsmart.rootRefreshMs`,
default 1000), which is the signed-checkpoint gossip of plan §3.3 — clients are not asked to run
consensus. The client verifies against a window of the last W=2 agreed roots and falls back to
Tier 1 on a miss, so an aged root costs a fallback rather than a wrong answer.

**One proxy per in-flight call.** BFT-SMaRt proxies are not thread-safe and the generator is
open-loop, so `BftSmartCluster` borrows from a pool (`-Dvdr.bftsmart.proxies`, default 16). Client ids
must be unique across every live client process — `-Dvdr.clientIdBase`, default 1001 — or replies
interleave between sessions.

## 7. The three things that were easy to get wrong

**Snapshots carry the identical projection, and only it.** `getSnapshot()` / `installSnapshot()`
delegate to `VdrStore.serialiseIdenticalProjection()` / `installIdenticalProjection()`, which
serialise exactly what `buildCheckpoint()` walks. If a PVSS share or a decrypted payload reached a
snapshot, a recovered replica's root would diverge from the replicas that never crashed and every
Tier-0 proof would silently stop verifying. Two gates hold this down: *does a replica restored from a
snapshot reach the same state root*, and *does a snapshot leak per-replica confidential state*. Run
them against a cluster that has actually performed a state transfer, not just a fresh one.

**Time is `MessageContext.getConsensusId()`, never the wall clock.** `VdrReplica.appExecuteOrdered`
takes the sequence number from the message context, so every replica agrees on it by construction. A
`System.currentTimeMillis()` anywhere in the execution path makes replicas diverge on the first
operation whose behaviour depends on it — for this system, a revocation-epoch boundary (plan §7).

**`appExecuteUnordered` does not mutate.** BFT-SMaRt does not order it, so a mutation there would
apply on whichever replicas happened to serve the read. The method answers RESOLVE, ROOT and
REVSTATUS and returns `UNORDERED_WRITE_REFUSED` for everything else, rather than quietly promoting a
write to the ordered path.

## 8. Serialisation

`vdr.serialization.Codec` is a canonical `DataOutputStream` codec for `Op`, `Reply` and
`Merkle.Proof`, with explicit ABSENT/PRESENT markers for nullable fields — not Java serialisation,
which is what DepSpace §8 says not to ship (custom codecs cut a STORE message from 2313 to 1300
bytes, mostly because `BigInteger` fragments a fixed-width number across many fields). Gate B1
round-trips every operation and reply through it, because a codec asymmetry between the client and
the replica presents as a quorum failure and is miserable to diagnose at benchmark time.

## 9. What to re-run, and what a passing gate means

Every gate passes against the simulator, which has no view change, no leader and no adversarial
scheduling. None of them means what it says until it passes against the real engine:

- **M3** root equality, including across a forced view change and after a state transfer.
- **M4** the performance gate — unordered resolve p50 must beat ordered resolve p50 by ≥2×. This is
  the plan's honest kill-switch: against BFT-SMaRt the ordered path costs a real consensus round, so
  this is the first time the gate tests the design rather than a constant.
- **M6** the key safety claim, with the Byzantine adapter driving equivocation, conflicting proposals
  and revocation suppression.

The two synthetic constants (3000 µs per consensus instance, 300 µs round trip) live only in
`SimulatedCluster` and are named in its `describe()` output, which `Bench` copies into the notes
column. They exist so the simulator's *relative* behaviour is not absurd; nothing that reports a
number may come from that path, and `RESULTS.md` carries the warning automatically when it does.
