# Artifact Appendix

**Paper.** A Secure and Resilient Verifiable Data Registry on a Permissioned Blockchain with
Tailored Byzantine Fault-Tolerant Consensus.

## Abstract

The artifact is a self-contained Java 21 implementation of the VDR described in the paper: the
DepSpace-derived storage, access-control and policy layers, the read-only fast path with
checkpoint-anchored Merkle proofs, revocation batching over monotone accumulators, and the
evaluation harness that produces the paper's metrics table. It builds and runs from source with no
network access, no build tool and no container runtime — `./artifact/run-all.sh` does everything.

It supports the paper's *functional and safety* claims in full, on one machine, in about five
minutes. It does **not** support the paper's *performance* claims: the consensus core is a
simulator, for the reason given under Limitations below. An evaluator should read that section
before running anything, so the badge scope is clear from the outset rather than discovered from a
provenance warning halfway down a results file.

## Artifact check-list

- **Program:** Tailored BFT VDR prototype, ~2,600 lines of Java.
- **Compilation:** `javac`, Java 21 or newer. No Maven, no dependency resolution, no network.
- **Run-time environment:** any OS with a JDK 21+. Tested on Linux x86-64 and macOS arm64.
- **Hardware:** any machine with 2+ cores. No special hardware.
- **Metrics:** throughput (ops/s) and p50/p95/p99 latency, defined operationally in `bench/METRICS.md`.
- **Output:** milestone gate results (pass/fail) and the evaluation matrix.
- **Experiments:** one script; no manual steps.
- **Disk:** under 5 MB.
- **Time to prepare:** under a minute (install a JDK).
- **Time to run:** ~30 seconds for the gates, ~4 minutes for the full harness.
- **Publicly available:** yes, archived with a DOI — see `artifact/ZENODO.md`.
- **Badges applied for:** Artifacts Available, Artifacts Evaluated — Functional. **Not** Results
  Reproduced; see Limitations.

## Description

### How to access

Unpack the archive, or clone the repository at the tagged revision recorded in `artifact/ZENODO.md`.

### Hardware dependencies

None. The artifact runs on a laptop. Absolute throughput scales with core count, which is why the
harness reports the machine it ran on (`environment.txt`) alongside every result.

### Software dependencies

A JDK, version 21 or newer. That is the complete list. The implementation deliberately has no
third-party dependencies, so an evaluator never has to resolve an artifact-breaking version
conflict years after publication.

macOS: `brew install --cask temurin@21`. Debian/Ubuntu: `apt install openjdk-21-jdk`.

## Installation

```bash
unzip tailored-bft-vdr.zip
cd tailored-bft-vdr
./build.sh
```

## Experiment workflow

```bash
./artifact/run-all.sh            # everything, n = 4
./artifact/run-all.sh 4 quick    # gates only, ~30 seconds
./artifact/run-all.sh 7          # n = 7 (f = 2)
```

Each invocation writes a timestamped directory under `artifact/results/` containing the captured
environment, build log, gate results, harness output and the evaluation matrix.

## Evaluation and expected results

**The gates.** `run-all.sh` prints `passed 12, failed 0`. These are the milestone go/no-go
questions of the plan's §11 written as executable assertions — including whether a client can write
to a DID it does not control, whether replicas agree on checkpoint roots after 10k operations,
whether a Byzantine replica can serve a forged document on the fast path, and the key safety claim
that no in-bound fault scenario causes a missed revocation to be served on the read path. A failure
here is a real finding about the design, not a setup problem; report it with `environment.txt`.

**The matrix.** The harness fills both Tailored-BFT rows of the paper's metrics table with
throughput, p50/p95/p99 and the per-operation split, plus revoke-burst drain statistics reported
separately. Our reference run is included under `artifact/results/`, with the machine it came from
in its `environment.txt` — compare against that rather than against a figure quoted in prose, since
absolute throughput tracks core count and JVM version.

**What matching numbers would and would not show.** Reproducing our throughput figures shows the
harness behaves the same on your machine as on ours. It does not corroborate the paper's
performance claims, because neither run measures a Byzantine consensus protocol.

## Limitations

**The consensus core is a simulator unless the run says otherwise.** There are two replication
layers behind `vdr.replication.Replication`, selected with `-Dvdr.replication`, and every run
records which one it used in `environment.txt` and in the `RESULTS.md` notes.
`vdr.replication.bftsmart.BftSmartCluster` is a real BFT-SMaRt 1.2 cluster and needs
`library-1.2.jar` in `vdr-bftsmart/lib/`; the jar could not be fetched in this build environment, so
the runs archived here used the simulator and say so.

`vdr.replication.SimulatedCluster` is a deterministic in-JVM total-order multicast: every replica receives the same operations in the same order and a reply is
returned on f+1 matching responses, which is DepSpace's replication contract and is sufficient to
build and test everything above L1. It has no view change, no leader election, no network and no
adversarial scheduling.

**The simulated latency figures are dominated by two constants.** `SimulatedCluster.standard()`
charges 3000 µs per
consensus instance and 300 µs per single-replica round trip, chosen to match DepSpace's 2008 Emulab
anchor points. They are inputs to a run, not results of it: a p50 read off `RESULTS.md` largely
restates what the simulator was told to assume. They exist so that the *relative* behaviour of the
fast path and the batching mechanism is not absurd. They are named in `SimulatedCluster.describe()`,
which the harness copies into the results notes, so a simulated row stays identifiable after the
fact; they do not exist on the BFT-SMaRt path at all.

**Consequence for badges.** This artifact can be checked for availability and for functionality.
It cannot support Results Reproduced for the performance table, and we are not claiming it. The
paper's Table III is produced at milestone M9 against BFT-SMaRt on a real cluster;
`docs/BFT-SMART-WIRING.md` documents how to run that path and lists the gates that must be re-run
against it, because none of them tests the design until the ordering layer is real.

**Also incomplete:** the PVSS confidentiality layer is share plumbing plus the state-root exclusion
rule, not secret sharing; the revocation accumulator is a monotone hash chain with no compact
non-membership proof; nothing is serialised, so the custom wire codecs the design calls for are
untested. `docs/DEVIATIONS.md` is the full list, with what each one means for the paper.

## Notes for the evaluator

`bench/METRICS.md` fixes the metric definitions before collection, as the methodology requires. Two
of them are load-bearing and worth checking against the code: the generator is open-loop with a
fixed arrival schedule and timestamps from scheduled arrival rather than dispatch, and no outliers
are discarded. The offered-load sweep refuses to report a level whose p99 exceeds the paper's
500 ms target even when throughput keeps up, so the harness will abort rather than hand you
percentiles that describe a queue.
