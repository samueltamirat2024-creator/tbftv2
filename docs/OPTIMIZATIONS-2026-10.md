# Optimisation and measurement fixes, October 2026

What changed before the four-row table (`deploy/vps/measure-table.sh`) was run on the 4 × 1 vCPU
server, and why. Grouped by effect on the numbers: first the bugs that made earlier rows
**invalid**, then the changes that make the system **faster**, then the runner.

Sandbox figures below come from a 2 vCPU development container running all four replicas *and*
the load generator. They show direction and relative size only; they are not results.

## A. Measurement-validity fixes (earlier Tailored rows over-counted)

| # | Problem | Effect on earlier numbers | Fix |
|---|---|---|---|
| A1 | Every backend (one per sweep level and per run) generated a **new issuer key** but registered the **same DID names**. Against long-lived BFT-SMaRt replicas, every run after the first had its registers denied (`P1_DID_EXISTS`) and every update and revocation denied (`P2/P4_UNAUTHORISED_KEY`). | Denied writes were **counted as committed**: the write and revoke columns measured cheap rejections, and no revocation ever cut a checkpoint. | Fresh DID namespace per backend (`TailoredBftBackend.namespace`); this also gives each run a fresh revocation registry, as Indy's backend already had. |
| A2 | The harness always sent `update(did, expectedVersion = 1)`. P3 (optimistic concurrency) denies every update after a DID's first. | Same as A1, even in the first run. | The backend tracks each DID's committed version and serialises updates per DID. |
| A3 | `update`, `register` and revocation results were never checked. | A denial counted as throughput. | A write counts only with f+1 matching **ok** replies; otherwise it is a failed op, excluded from throughput and latency and reported in the row's notes. |
| A4 | A Tier-0 read that returned "unknown DID" returned `null`, and the harness counted it. Right after population up to 2f replicas may not yet have executed the registers. | Empty reads counted as resolves. Also a **safety bug**: one replica could make a DID disappear for a client. | `VdrClient.resolveTier0` falls back to Tier 1 (f+1) on a single-replica denial; the backend counts a `null` resolve as a failure. Indy already did the same for `"data":null`. |
| A5 | Updates were picked as `idx % 400` with idx ≡ 95..98 (mod 100): every update hit the same 16 DIDs. | Successive rotations of one DID overlapped and conflicted (both systems). | Updates go round-robin over all 400 DIDs. Both backends also serialise rotations per DID. |
| A6 | Load-generator pool was fixed at 8 × vCPUs threads (32 on 4 vCPU). | In-flight operations capped at 32, i.e. at most 32 / latency ops/s — for Indy, whose writes take seconds, a closed loop far below its capacity. | Unbounded cached pool (`-Dbench.executor`), so the generator is really open-loop. |
| A7 | Ordered BFT-SMaRt calls ran on `ForkJoinPool.commonPool()` (vCPUs − 1 threads). | Ordered throughput capped at ~3 / consensus latency regardless of the cluster. | Dedicated cached pool; the limit is now the proxy pool, as designed. |
| A8 | The client's trusted checkpoint root was refreshed once a second, while every committed revocation batch cuts a new checkpoint (V6) — several per second under the read-heavy mix. | Most Tier-0 reads carried an "untrusted" root and fell back to Tier 1/Tier 2. Sandbox read-heavy p50 at 1000 ops/s: **10.9 ms**. | On-demand root refresh (still f+1 agreement, shared by concurrent readers) on a dedicated proxy that load cannot starve, background refresh every 250 ms. Same run: p50 **0.6 ms**. |
| A9 | Two Bench JVMs reused the same BFT-SMaRt client ids, and Bench never exited (non-daemon netty threads), so a finished run kept its sessions open against the replicas. | The next invocation's requests went unanswered ("no replies"). | Client-id base derived from the clock per JVM; Bench calls `System.exit`. |

## B. Performance

| # | Change | Where | Effect (sandbox) |
|---|---|---|---|
| B1 | **Incremental Merkle checkpoints.** The checkpoint tree used to be rebuilt from every leaf of the whole registry on every checkpoint, and a checkpoint is cut per revocation batch. Now leaves are bucketed by SHA-256(key) into 4096 sorted sub-trees under a fixed-depth top tree; a checkpoint re-hashes only the buckets touched since the previous one. | `vdr.merkle.IncrementalMerkle`, `vdr.store.CowBucketMap`, `VdrStore` | One 50-handle revocation batch incl. checkpoint: 20 k leaves 106 ms → 5 ms; 100 k leaves 560 ms → 16 ms (what remains is Ed25519, B3). Cost no longer grows with everything ever written. |
| B2 | Checkpoints are immutable and published through a `volatile` field, so unordered reads no longer take the store's lock. | `VdrStore` | Reads are not blocked behind a checkpoint build. |
| B3 | **Fast Ed25519** (BouncyCastle) when its jar is present, bound reflectively (vdr-core still has no compile-time dependency), with a load-time self-test against the JDK. The JDK's EdDSA costs ~1 ms per sign/verify; every ordered write is verified on every replica on BFT-SMaRt's single delivery thread, which also serves the reads. Indy uses libsodium. The Indy client now signs through the same function, so both generators pay the same signing cost. | `vdr.crypto.Crypto.FastEd25519`, `Dockerfile.bench` | ~20× cheaper signatures (libsodium-class). Every row records which implementation was used. |
| B4 | Per-thread `MessageDigest` / `Signature`, cached decoded public keys, shared `HexFormat`. | `Crypto` | Removes a provider lookup and an X.509 decode from every hash and verify. |
| B5 | Replicas no longer recompile at container start; heap pre-sized (`REPLICA_JAVA_OPTS`). | `vdr-bftsmart/build.sh`, compose | Faster, uniform startup on 1 vCPU. |

### What did not change

- The protocol: total order, f+1 matching, Tier 0/1/2 rules, policies P0–P6, V6 checkpoint at
  every revocation epoch, BFT-SMaRt and its `system.config`, the Indy pool and `indy_config.py`.
- Hash functions and domain separation (0x00 leaf / 0x01 node), the proof format and
  `Merkle.verify`: an incremental proof is the in-bucket path followed by the bucket's path in the
  top tree, so the client verifier is untouched. The root *value* differs from the old single-tree
  root, so the state-transfer snapshot format number was bumped (1 → 2): mixed builds fail loudly.
- Gate B5 (new) checks after every kind of operation that the incremental root equals a root
  rebuilt from scratch, that proofs verify, and that state transfer reproduces the root.
  `./build.sh gates`: 17 passed, 0 failed.

## C. Runner

- `deploy/vps/measure-table.sh`: one command, 4 nodes × 1 vCPU pinned to cores 0–3, one system at
  a time, fresh cluster/ledger per system, only the four rows. See `deploy/vps/MEASURE-TABLE.md`.
- `Bench`: `-Dbench.mixes`, separate sweep windows (`bench.sweepWarmupMs/SteadyMs`), knee
  bisection (`bench.refine`), failures in the row notes, machine-readable `rows.tsv`.
- `deploy/vps/make-table.py`: merges the two systems' `rows.tsv` into `TABLE.md`.

## Known limits

- **Read amplification (unchanged):** BFT-SMaRt 1.2's client stalls if an unordered request is
  sent to fewer than a reply quorum, so every Tier-0 read is sent to all four replicas and only the
  target's reply is accepted. Replicas therefore execute each read 4×; this understates Tailored
  read throughput.
- On a 4 vCPU server the load generator shares the nodes' cores (both systems alike).
- The BouncyCastle path was exercised here only through a test double with the same API (the
  sandbox could not reach Maven Central); on the server the load-time self-test decides, and falls
  back to the JDK if anything disagrees.
