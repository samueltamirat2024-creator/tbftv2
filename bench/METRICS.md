# METRICS.md — operational definitions, frozen before collection

Plan §12.1 and §16 step 2: these definitions are fixed *before* any number is collected, and
require co-author sign-off. Changing one after measurement has begun invalidates every run
collected under the old definition.

Implemented in `vdr.bench.Bench`; the constants named below are the ones in that file.

## Throughput

Committed operations per second, counted **at the client** on receipt of the f+1-th matching
reply. A write is committed when the client can act on it, not when a replica logs it.

- Steady-state window only. Warm-up and ramp-down are excluded.
- Reported separately for `resolve`, `register`, `update` and `revoke`, plus the mix aggregate.
- A failed operation is not a committed operation: it is excluded from throughput and from the
  latency histogram, and never counted as a success.

## Latency

End-to-end: client request submission to acceptance of the reply, **including client-side proof
verification**. The timestamp is the *scheduled* arrival instant, not the instant a worker thread
picked the request up — that is what makes the measurement coordinated-omission-free.

- Recorded at 1 µs precision.
- p50/p95/p99 are read from the histogram **merged across all runs** of a configuration. They are
  never averaged from per-run percentiles.

## Coordinated omission

The load generator is **open-loop** with a fixed arrival schedule and never waits for a reply
before issuing the next request. Closed-loop generators hide queueing delay and would flatter the
tail. Non-negotiable for p99 credibility (plan §12.1, risk register §15).

Three consequences the harness enforces:

1. **Operating-point admission.** A load level is reported only if it both keeps the arrival
   schedule (achieved ≥ 95% of offered) and stays inside the p99 target Paper 2 §III sets for the
   VDR (`P99_TARGET_US`, 500 ms). The throughput test alone is not sufficient: an open-loop
   generator can keep up on count while a backlog builds, and the percentiles then describe the
   queue rather than the system. If no level qualifies the harness raises rather than reporting a
   saturated run.
2. **In-flight bound.** The generator runs one thread per in-flight operation. If more than
   rate × 2 × `P99_TARGET_US` operations (1 s worth of arrivals) are in flight at once, the
   run stops early and is marked saturated. This bound applies from the start of the steady
   window. During warm-up, which is discarded, only the absolute thread cap below applies, so a
   backlog that builds while the system is cold and then drains does not end the run. By Little's law, mean latency is already past 1 s at
   that point, so the level could not have met the 500 ms p99 target. Stopping it changes no
   admission decision. It only keeps a write-only mix past its knee from exhausting the JVM's
   threads. A reported row that was stopped this way says `OVERLOADED`, and its p99 is
   reported as a lower bound of 2 × the target. The early stop never interrupts the operations
   still in flight; they drain first, so the shared BFT-SMaRt client is never left in a broken
   state. `-Dbench.maxInFlight` caps the bound so the generator stays inside the process thread
   limit. An ordered write holds two threads, so the default is 800 on macOS (2048 threads per
   process) and 4000 elsewhere. Above `maxInFlight` / 0.5 s of offered load, the cap no
   longer implies a missed target, and a level stopped there is reported as NOT MEASURABLE, not
   saturated. Raise the cap before reporting a knee above that load.
3. **No generator-induced blocking.** The revoke burst is submitted to the gateway without a
   thread per revocation. A blocking join per item would occupy the generator's pool and the
   steady stream's latency would measure thread starvation in the harness.

## Warm-up

Every run discards the first `WARMUP_MS`. DepSpace found JIT compilation cuts cryptographic
processing delays by roughly a factor of ten, so an un-warmed JVM measures a different system.
Both our replicas and the Indy baseline get identical warm-up treatment.

## Outliers

Nothing is discarded. DepSpace dropped the 5% highest-variance values; that practice is
indefensible for a p99 claim. State this difference explicitly if a reviewer compares.

## Revoke burst

The bursty-revoke mix injects a finite burst of `BURST_SIZE` revocations at t = T. All burst
arrivals share one injection instant — that *is* the incident-response model — so they are
reported separately from the steady-mix percentiles:

- **burst drain time**: injection to last burst revocation committing;
- **burst throughput**: revocations per second across the drain;
- **burst p50 / p95 / p99**: from the burst's own histogram merged over all runs;
- **drain-time 95% CI**: half-width over the per-run drain times;
- **burst cost**: ordered revocation transactions submitted from injection to the end of the run
  (and how many were confirmed), i.e. how many consensus instances the burst became.

`make-table.py` reports these as a separate analysis section of `TABLE.md` ("Revocation burst"),
Tailored next to Indy.

Folding them into the mix histogram would make every percentile in the row describe the burst
backlog rather than the system serving the steady stream. The plan's metric for the burst is
throughput bounded by batch size rather than consensus round count (§3.4), which is what the drain
numbers measure.

## Mixed-workload compositions

- **read-heavy**: 95% resolve, 4% update, 1% revoke (steady-state revocations, batched by the
  gateway like any other revoke).
- **bursty-revoke**: 90% resolve, 10% update, no steady revokes, plus the revoke burst above.

Neither mixed workload registers. Gate B7 pins the read-heavy composition.

## Tier-0 fallback rate

A Tier-0 read goes to one replica and is verified against an f+1-agreed checkpoint root. It
**falls back** to Tier 1 (f+1 replicas), and from there possibly to Tier 2 (ordered), when the
replica denies the read or its root is not (yet) agreed after one on-demand refresh. A fallback
costs latency, never a wrong answer.

- **N**: Tier-0 reads attempted from the first measured arrival to the end of the drain,
  **summed over all runs** of the reported level. Warm-up reads still in flight at the boundary
  can land in the window, at most one inter-arrival interval's worth.
- **F**: of those, the reads that fell back, over the same window.
- Reported as F of N (F / N %) in the row notes, as the `tier0_reads` / `tier0_fallbacks`
  columns of `rows.tsv` (-1 for Indy, which has no Tier-0 path), and in the "Tier-0 fallbacks"
  table of `TABLE.md`.
- The backend's own "Tier-0 fallbacks n (last run, incl. warm-up)" in the notes is a
  per-backend diagnostic. **Do not report it.** `make-table.py` falls back to it only for
  `rows.tsv` files written before the columns existed, and labels it as such.

## Knee bracket

The sweep records the lowest level it failed (`SATURATED_AT`). The measured row's notes carry it
as `sweep ceiling: first failing level C ops/s (<reason>)`, with one of these reasons:
`throughput`, `p99` or `inflight`.

`TABLE.md` reports the operating point R as `knee in (R, C]`. C is the lower of the sweep ceiling
and any higher admitted level that missed the p99 target when measured (the step-down path).

An operating point is a **lower bound** only in two cases:
- the sweep passed every listed level (`sweep ceiling: none up to M ops/s`), or
- the failing level was stopped by the generator's own in-flight cap (`generator`), not by the
  system.

## Register and Update mixes

Added after the read-heavy and bursty-revoke definitions were frozen. These are new definitions;
no existing one changes. They need the same sign-off as the rest of this file before any
register/update number is collected.

- **`register`**: every arrival registers a DID that has never been written (`Backend.freshDid`).
  A register is committed at the f+1-th matching OK reply (Tailored) or an accepted NYM reply
  (Indy). A DENIED register (for example a name collision) is a failure, not a commit.
- **`update`**: every arrival is a key-rotation update of a DID from the pre-populated pool,
  taken round-robin. It is committed by the same rule as a register. Updates of one DID are
  serialised on both systems. Any update that waited for another update of the same DID is
  counted (`contendedUpdates`), and a reported row must show 0.
- **Throughput** for these rows is that operation's committed ops/s in the steady window.
  **Latency** is that operation's merged histogram, which for a single-operation mix is the
  row's histogram.
- **Admission** is the same as for every other row: the schedule is kept (≥ 95% of offered) and
  the merged p99 is ≤ `P99_TARGET_US` (500 ms).
- **Equal load**: `-Dbench.equalLoad.register` / `-Dbench.equalLoad.update` measure both systems
  at the same offered rates, with no sweep.
- **Per-operation latency inside mixed workloads**: every committed operation is recorded in
  the mix histogram *and* in its own operation's histogram, with one timestamp, so the
  per-operation histograms partition the mix histogram exactly (gate B6). Per-operation
  percentiles are read from per-operation histograms merged across runs, never averaged.

## Statistical protocol

`RUNS` ≥ 10 per configuration (plan §12.3). Throughput reported as mean ± 95% CI across runs.
Significance testing of H1's claims against both baselines is done at M9, not here.

## Sign-off

| Role | Name | Date |
|---|---|---|
| Author | Samuel Tamirat | |
| Co-author | Sileshi Demissie | |
| Co-author | Miguel Correia | |
