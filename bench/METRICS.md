# METRICS.md — operational definitions, frozen before collection

Plan §12.1 and §16 step 2: these definitions are fixed *before* any number is collected, and
require co-author sign-off. Changing one after measurement has begun invalidates every run
collected under the old definition.

Implemented in `vdr.bench.Bench`; the constants named below are the ones in that file.

## Throughput

Committed operations per second, counted **at the client** on receipt of the f+1-th matching
reply. A write is committed when the client can act on it, not when a replica logs it.

- Steady-state window only. Warm-up and ramp-down are excluded.
- Reported separately for `resolve`, `register`/`update` and `revoke`, plus the mix aggregate.
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

Two consequences the harness enforces:

1. **Operating-point admission.** A load level is reported only if it both keeps the arrival
   schedule (achieved ≥ 95% of offered) and stays inside the p99 target Paper 2 §III sets for the
   VDR (`P99_TARGET_US`, 500 ms). The throughput test alone is not sufficient: an open-loop
   generator can keep up on count while a backlog builds, and the percentiles then describe the
   queue rather than the system. If no level qualifies the harness raises rather than reporting a
   saturated run.
2. **No generator-induced blocking.** The revoke burst is submitted to the gateway without a
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
- **burst p99**: from the burst's own merged histogram.

Folding them into the mix histogram would make every percentile in the row describe the burst
backlog rather than the system serving the steady stream. The plan's metric for the burst is
throughput bounded by batch size rather than consensus round count (§3.4), which is what the drain
numbers measure.

## Statistical protocol

`RUNS` ≥ 10 per configuration (plan §12.3). Throughput reported as mean ± 95% CI across runs.
Significance testing of H1's claims against both baselines is done at M9, not here.

## Sign-off

| Role | Name | Date |
|---|---|---|
| Author | Samuel Tamirat | |
| Co-author | Sileshi Demissie | |
| Co-author | Miguel Correia | |
