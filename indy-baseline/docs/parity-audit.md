# Parity audit (milestone B4)

Baseline plan §7. These asymmetries exist. The paper discloses them; it does not quietly benefit
from them. Complete this before B7.

## 1. Storage — the most serious one

Indy persists every transaction to disk. Our prototype holds state in memory. This is a large,
systematic advantage to us on write latency.

- [ ] **Option 1 (preferred):** enable BFT-SMaRt durability in the Tailored BFT VDR and report both
      systems with disk in the loop.
- [ ] **Option 2:** report *both* configurations of our system (in-memory and durable) and compare
      Indy only against the durable row.

Reporting only an in-memory row against a disk-backed Indy is the most serious methodological flaw
available to this study. DepSpace could state that neither it nor its comparison system touched
disk; we cannot, so the difference must be explicit.

**Chosen:** ______

## 2. Byzantine fault injection — asymmetric by construction

We built a fault-injection adapter into our own consensus core. We are not forking indy-plenum, so
the baseline receives network-level and client-level faults only.

- [ ] The paper states plainly that replica-level Byzantine injection was applied to our system and
      **not** to Indy.
- [ ] No comparative claim is made about Byzantine behaviour — only about crash, partition and
      client faults, which both systems receive identically.

## 3. Accumulator cost

Indy's revocation accumulator is cryptographic (CL); ours is a monotone hash chain with no compact
non-membership proof. Indy is ahead on this mechanism.

- [ ] Stated in the paper rather than left for a reviewer to notice.
- [ ] Note that the load driver uses a placeholder accumulator value, so neither run charges the
      issuer-side accumulator computation. If that cost matters to a claim, measure it separately
      with anoncreds-rs.

## 4. Tails files

AnonCreds revocation requires tails file generation and distribution; ours does not. Generation
happens during population, never inside a measurement window.

- [ ] Discussed in the paper as a real property of the AnonCreds model. Benefiting from its absence
      in the numbers while omitting it from the discussion would be selective.

## 5. Access control semantics

Indy's write authorisation is role-based (TRUSTEE / STEWARD / ENDORSER, configurable via
AUTH_RULE); ours is capability-based against the controller key set in the current document.

- [ ] Indy's auth rules configured as permissively as is realistic for a consortium deployment, so
      the baseline is not paying for a check the workload does not need.
- [ ] Difference stated.

## 6. Transaction Author Agreement

- [ ] TAA either enabled on both systems' equivalent path or disabled on Indy. Not left on by
      accident and reported as a design result.

## 7. Client runtime

Our driver is JVM; indy-vdr is Rust behind an FFM binding. Warm-up exists for different reasons —
JIT on ours, connection pool and catch-up on Indy — but the *treatment* is identical: same
discarded prefix, same absence of outlier discarding, same merged-histogram percentiles.

- [ ] Confirmed identical in `bench/METRICS.md` terms.

## 8. Register and Update as measured operations

The `register` and `update` mixes (`vdr.bench.Bench.Mix.REGISTER` / `UPDATE`) put every arrival on
one write path, so how far the two writes actually match decides what those rows mean.

| | Tailored BFT VDR | Hyperledger Indy |
|---|---|---|
| register | DepSpace `cas` of a new DID record, signed by the bench issuer, committed at f+1 matching replies | NYM create, signed by the trustee (ENDORSER-or-better submitter), committed on an accepted pool reply |
| update | append-only versioned update against the DID's current version (P3 optimistic concurrency), signed by the controller key | NYM verkey rotation, signed by the DID's *current* key; the next update is signed by the new one |
| payload | the full DID document (~150 bytes) is stored and covered by the checkpoint root | NYM carries the DID and verkey only; the bench document bytes are not written |
| write batching | gateway / BFT-SMaRt batch, `maxBatchDelay` = 50 ms | `Max3PCBatchSize` = 100, `Max3PCBatchWait` = 0.05 s |
| per-DID serialisation | one in-flight update per DID | one in-flight update per DID |
| fresh names | `freshDid` uses the backend's per-run namespace | `freshDid` uses the backend's per-process salt |

- [ ] Stated in the paper: the Tailored update stores a whole document version, while the Indy
      update rotates one key. Indy writes fewer bytes per update, and that favours the baseline.
- [ ] Stated: Indy signs every register with one trustee and Tailored with one issuer. Neither
      side spreads registers over several submitters.
- [ ] `contendedUpdates 0` in every reported update row on both systems, so update latency
      excludes waits on the generator's own per-DID locks (raise `UPDATE_DID_POOL` if not).
- [ ] The same 500 ms p99 admission target as the read rows. It is a read SLO (Paper 2 §III),
      applied unchanged so that every row follows one rule.
