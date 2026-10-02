# Tailored BFT VDR — reference implementation

Implementation of the plan *A Secure and Resilient Verifiable Data Registry on a Permissioned
Blockchain with Tailored Byzantine Fault-Tolerant Consensus* (Paper 2 §VII–§VIII), using the
DepSpace algorithms (EuroSys'08) for the storage, access-control, policy and confidentiality
layers.

Java 21. `vdr-core` has no external dependencies; the BFT-SMaRt replication layer lives in a
separate module (`vdr-bftsmart`) and is loaded reflectively, so the core builds and the gates run
with no jar present.

```bash
./build.sh          # compile vdr-core
./build.sh gates    # go/no-go gates (16)
./build.sh bench    # evaluation harness; writes RESULTS.md
./build.sh bench 7  # n = 7 (f = 2)

./vdr-bftsmart/build.sh          # compile the BFT-SMaRt layer
./vdr-bftsmart/build.sh replica 0  # one replica process (ids 0..3)
./vdr-bftsmart/build.sh bench 4    # the harness against the real cluster
```

Which replication layer a run used is a run property, not a habit of remembering flags:
`-Dvdr.replication=simulated|bftsmart`, printed at startup, recorded in `environment.txt`, and
carried into `RESULTS.md` — with a provenance warning attached automatically when it was the
simulator.

Step-by-step guide for both systems (Tailored BFT VDR and the Indy VDR baseline):
[`docs/BUILD-AND-RUN.md`](docs/BUILD-AND-RUN.md).

## What is implemented

| Plan section | Module | State |
|---|---|---|
| §2.2 fingerprint, protection type vectors | `vdr.model.Fingerprint`, `Protection` | done |
| §2.1 record families, §2.3 ordered indices | `vdr.model.Records`, `vdr.store.VdrStore` | done |
| §3.1 `register` via DepSpace `cas` | `VdrStore.doRegister` | done |
| §3.2 `update`, append-only, per-DID monotonicity | `VdrStore.doUpdate` | done |
| §3.3 `resolve` Tier 0 / 1 / 2 + Merkle proofs | `vdr.client.VdrClient` | done |
| §3.4 `revoke` batching + accumulator deltas | `vdr.replication.Gateway` | done |
| §3.5 repair + blacklist (DepSpace Alg. 3) | `VdrStore.doRepair` | done |
| §5 Merkle checkpoints over the identical projection | `vdr.merkle.Merkle`, `VdrStore.buildCheckpoint` | done |
| §6 access control + policies P1–P6 | `vdr.accesscontrol`, `vdr.policy.Policies` | done |
| §7 determinism rules + replay test | `VdrStore`, `vdr.test.Gates` | done |
| §12 evaluation harness, metric definitions | `vdr.bench.Bench`, `bench/METRICS.md` | done |
| §13 fault injection (Byzantine replica, issuer, gateway) | `vdr.replication.SimulatedCluster` | partial |
| §4 BFT-SMaRt consensus core | `vdr-bftsmart/`, `vdr.replication.Replication` | done, **needs the jar** |
| §8 PVSS confidentiality layer | share plumbing only | **stubbed** |
| §9 Go gateway, §10 Docker/Helm | `deploy/` | skeleton |

## The one thing to read before trusting a number

There are two replication layers and they are not interchangeable for reporting purposes.

`vdr.replication.bftsmart.BftSmartCluster` is a real BFT-SMaRt 1.2 cluster: four replica processes,
network, leader, view change, state transfer. `Op`s cross the wire through a canonical codec, reads
are served unordered from committed checkpoints, and the client still does its own f+1 matching and
Merkle verification. This is the path Paper 2's Table III is filled from. It needs
`library-1.2.jar` in `vdr-bftsmart/lib/` — see `docs/BFT-SMART-WIRING.md` — and without it
`vdr-bftsmart/build.sh` type-checks against API stubs and then **refuses to run anything**.

`vdr.replication.SimulatedCluster` is a **deterministic in-JVM total-order multicast**, not a
Byzantine consensus protocol. It delivers every operation to all n = 3f+1 replicas in the same order and
returns a reply only on f+1 matching responses — DepSpace's replication contract — which is enough
to build and test the store, policy, checkpoint and determinism work above it. It has no view
change, no leader, no network, and no adversarial scheduling.

Worse, for reporting purposes: its latency is dominated by two **hardcoded constants** in
`SimulatedCluster.standard()` — 3000 µs per consensus instance and 300 µs for a single-replica round
trip, chosen to match DepSpace's 2008 Emulab anchor points. Those constants are inputs to a run, not
results of it. Any p50 read off a simulated `RESULTS.md` is largely restating what the simulator was
told to assume. `SimulatedCluster.describe()` names both constants and `Bench` copies that into the
results notes, so a simulated row cannot be mistaken for a measured one after the fact.

What the rows *do* establish is that the measurement pipeline is sound — arrival schedule,
warm-up, merged-histogram percentiles, per-operation split, burst accounting, operating-point
admission — and that batching and the fast path move the numbers in the direction the design
predicts. That is M0's "can we measure anything at all, honestly?" and nothing more. Paper 2's
Table III is filled from M9, against BFT-SMaRt, with both constants out of the picture.

Maven Central was unreachable in the environment this was built in, so the jar could not be fetched
here; the module is type-checked against `vdr-bftsmart/stubs/` and the build script blocks any run
from a stub build rather than letting a stubbed cluster produce numbers.

## Layout

```
vdr-core/src/vdr/
  model/        fingerprint, protection type vectors, record families
  ops/          operation and reply types
  store/        versioned append-only log, ordered indices, accumulators, checkpoints
  merkle/       Merkle tree, inclusion proofs
  policy/       PEATS policy chain P1–P6 (compiled Java, not Groovy — plan §6)
  accesscontrol/ DepSpace credentials model
  crypto/       Ed25519, SHA-256, AES-GCM helpers
  replication/  Replication (the seam), SimulatedCluster, ServiceReplica, Gateway
  serialization/ canonical wire codec for Op / Reply / Merkle proofs
  client/       resolver/issuer SDK, three read tiers, proof verification
  bench/        open-loop load generator, histogram, evaluation matrix
  test/         go/no-go gates
vdr-bftsmart/      the real engine: VdrReplica (server), BftSmartCluster (client), API stubs
config/            hosts.config and system.config, archived with every run
bench/METRICS.md   frozen operational metric definitions (plan §12.1)
docs/              BFT-SMaRt wiring, deviations from the plan
deploy/            Dockerfile, Compose stack, Helm skeleton, and the measurement protocol runner
```

## Running the measurement protocol

`deploy/vps/run-protocol.sh` is the only entry point that gives both systems the same treatment —
60 s warm-up, 60 s steady window, 10 runs, matched batching delay, fine sweep levels near each knee,
and a pool reset so every Indy run starts on a fresh revocation registry:

```bash
cd deploy/vps
./run-protocol.sh debug indy     # short windows, to check the wiring; not a result
./run-protocol.sh indy           # Hyperledger Indy baseline
./run-protocol.sh tailored-bft   # Tailored BFT VDR on BFT-SMaRt
python3 compare.py               # merge the two into one table
```

## Indy baseline

`indy-baseline/` holds the comparison baseline (milestone M8). It binds libindy_vdr through Java 21
FFM so that one load generator drives both systems — `vdr.baseline.Backend` is the seam, with
`TailoredBftBackend` and `IndyBackend` behind it. The binding is verified against the real library
by an offline probe; the pool itself is a skeleton. See `indy-baseline/README.md`.

## Artifact evaluation

`artifact/` holds the reproduction package: `ARTIFACT.md` (appendix and badge scope),
`CLAIMS.md` (which claim each experiment supports, and which five it cannot), `ZENODO.md`
(DOI archiving checklist), and `run-all.sh`, which captures the environment, builds, runs the
gates and the harness into one timestamped directory:

```bash
./artifact/run-all.sh            # everything, ~5 minutes
./artifact/run-all.sh 4 quick    # gates only, ~30 seconds
```

## Gates

`./build.sh gates` runs the adversarial-review questions from the plan's milestone table as
executable assertions — including M4's performance gate (unordered resolve p50 must beat ordered
resolve p50 by ≥2×) and M6's key safety claim (no in-bound fault scenario serves a missed
revocation on the read path). They also cover the wire codec, the step-down admission rule, and the
two snapshot obligations that state transfer depends on.

They run against the simulated engine only (every gate builds a `SimulatedCluster`), which has no
view change, no leader and no adversarial scheduling. M3, M4 and M6 are re-asked of four real
BFT-SMaRt replica containers by `./vdr-bftsmart/real-gates.sh`, with a real leader stop, restart and
state transfer, and Byzantine replicas. Network-level adversarial scheduling is not yet covered.
