package vdr.bench;

import vdr.baseline.Backend;
import vdr.baseline.BackendFactory;
import vdr.crypto.Crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Evaluation harness (plan section 12): emits exactly the required table, with the two
 * Tailored-BFT rows measured.
 *
 * BINDING DEFINITIONS, fixed before any number is collected (plan section 12.1):
 *
 *  Throughput  committed operations per second, counted AT THE CLIENT on receipt of the
 *              f+1-th matching reply. A write is committed when the client can act on it,
 *              not when a replica logs it. Steady-state window only. Reported separately
 *              for resolve, register/update and revoke, plus the mix aggregate.
 *
 *  Latency     end-to-end, client submission to acceptance of the reply, INCLUDING
 *              client-side proof verification. Recorded at 1 us precision; p50/p95/p99
 *              read from the merged histogram across all runs, never averaged from
 *              per-run percentiles.
 *
 *  Open loop   the generator has a fixed arrival schedule and never waits for a reply
 *              before issuing the next request. Closed-loop generators hide queueing delay
 *              and would flatter our tail latency. Non-negotiable for p99 credibility.
 *
 *  Warm-up     the first WARMUP_MS of every run is discarded. DepSpace found JIT
 *              compilation cuts cryptographic processing delays by roughly 10x, so an
 *              un-warmed JVM measures a different system. The baseline gets identical
 *              warm-up treatment.
 *
 *  Outliers    nothing is discarded.
 */
public final class Bench {

    // Defaults are the plan's values (section 12.1 / 12.3). Override for quick debug runs with
    // -Dbench.runs / -Dbench.warmupMs / -Dbench.steadyMs; both systems must use the same values,
    // or the four rows stop being comparable.
    static final int RUNS = Integer.getInteger("bench.runs", 10);
    static final long WARMUP_MS = Long.getLong("bench.warmupMs", 60_000L);
    static final long STEADY_MS = Long.getLong("bench.steadyMs", 60_000L);
    static final int DID_POOL = 400;

    /**
     * Paper 2 section III states the VDR's performance target as sub-500 ms p99 read latency.
     * The sweep uses it as the admission criterion for an operating point, so a level whose
     * percentiles describe a growing queue is never reported as the system's result.
     */
    static final double P99_TARGET_US =
            Double.parseDouble(System.getProperty("bench.p99TargetUs", "500000"));

    /** Size of the finite revoke burst injected at t = T by the bursty-revoke mix. */
    static final int BURST_SIZE = 800;

    /**
     * Which system this run measures. Selected with -Dvdr.backend=... so that both systems are
     * driven by this one generator (baseline plan section 8):
     *
     * <pre>
     *   ./build.sh bench                          # tailored (default)
     *   ./indy-baseline/build.sh bench            # indy, via the same Bench class
     * </pre>
     */
    static final BackendFactory BACKEND = BackendFactory.fromSystemProperties();

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        System.out.println(BACKEND.displayName() + " -- evaluation harness");
        System.out.printf("n = %d (f = %d), runs = %d, warm-up = %d ms, steady state = %d ms%n",
                n, (n - 1) / 3, RUNS, WARMUP_MS, STEADY_MS);
        System.out.println("open-loop generator; no outliers discarded; percentiles from merged histogram");
        System.out.printf("p99 target = %.0f ms; an operating point is re-measured and stepped down "
                + "until its MERGED p99 meets it%n", P99_TARGET_US / 1000);
        if (P99_TARGET_US != 500_000) {
            System.out.printf("WARNING: -Dbench.p99TargetUs overrides Paper 2 section III's 500 ms "
                    + "target with %.0f ms. Valid for testing the harness, never for a reported run.%n",
                    P99_TARGET_US / 1000);
        }
        // vCPU budget: every replica should own at least one vCPU. availableProcessors() honours
        // the container's CPU limit (docker --cpus / cpuset), so this reports what the run really got.
        int vcpus = Runtime.getRuntime().availableProcessors();
        System.out.printf("vCPUs available = %d, replicas = %d, vCPU per replica = %.2f, replica threads = %s%n",
                vcpus, n, (double) vcpus / n, System.getProperty("vdr.replicaThreads", "true"));
        if (vcpus < n && "tailored".equals(System.getProperty("vdr.backend", "tailored"))) {
            System.out.printf("WARNING: %d vCPUs for %d replicas -- replicas share cores; "
                    + "give the runner at least %d vCPUs (deploy/vps/cpu-plan.sh)%n", vcpus, n, n);
        }
        System.out.println("=".repeat(94));

        if (!EQUAL_LOAD_READ_HEAVY.isEmpty() || !EQUAL_LOAD_BURSTY.isEmpty()) {
            runEqualLoad(n);
            return;
        }

        // Low levels are needed for Indy, whose writes go through a much slower consensus path.
        int[] levels = java.util.Arrays.stream(
                        System.getProperty("bench.levels", "10,20,50,100,250,500,1000,2000").split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
        System.out.println("\n-- offered-load sweep, read-heavy");
        List<Integer> rhLevels = sweepOperatingPoint(n, Mix.READ_HEAVY, levels, 250);
        System.out.println("-- offered-load sweep, bursty-revoke");
        List<Integer> brLevels = sweepOperatingPoint(n, Mix.BURSTY_REVOKE, levels, 250);
        System.out.printf("%ncandidate operating points: read-heavy %d ops/s, bursty-revoke %d ops/s"
                        + " (each re-measured below)%n%n",
                rhLevels.get(rhLevels.size() - 1), brLevels.get(brLevels.size() - 1));

        String label = BACKEND.displayName();
        Result readHeavy = measureWithStepDown(label + " -- read-heavy", n, Mix.READ_HEAVY, rhLevels);
        Result bursty = measureWithStepDown(label + " -- bursty-revoke", n, Mix.BURSTY_REVOKE, brLevels);

        System.out.println();
        printTable(List.of(readHeavy, bursty));
        writeResultsFile(List.of(readHeavy, bursty), n);
    }

    /**
     * Equal-offered-load mode. Comparing each system at its OWN operating point compares latencies
     * at loads up to 40x apart; these lists fix the load instead, so both systems are measured at
     * the same offered rate. Pick levels BOTH systems sustain: a level past either system's knee
     * measures that system's queue. Either property switches the run to this mode (no sweep, no
     * step-down; every listed level is measured with the full RUNS).
     *
     *   -Dbench.equalLoad.readHeavy=50,200 -Dbench.equalLoad.burstyRevoke=25,50
     */
    static final List<Integer> EQUAL_LOAD_READ_HEAVY = levelsProperty("bench.equalLoad.readHeavy");
    static final List<Integer> EQUAL_LOAD_BURSTY = levelsProperty("bench.equalLoad.burstyRevoke");

    static List<Integer> levelsProperty(String key) {
        List<Integer> out = new ArrayList<>();
        for (String s : System.getProperty(key, "").split(",")) {
            if (!s.isBlank()) out.add(Integer.parseInt(s.trim()));
        }
        return out;
    }

    static void runEqualLoad(int n) throws Exception {
        String label = BACKEND.displayName();
        List<Result> results = new ArrayList<>();
        for (Mix mix : new Mix[] {Mix.READ_HEAVY, Mix.BURSTY_REVOKE}) {
            List<Integer> levels = mix == Mix.READ_HEAVY ? EQUAL_LOAD_READ_HEAVY : EQUAL_LOAD_BURSTY;
            for (int rate : levels) {
                System.out.printf("%n-- equal offered load: %s at %d ops/s%n", mix.label(), rate);
                Result r = measure(label + " -- " + mix.label() + " @ " + rate + " ops/s", n, mix, rate);
                // Not a reason to stop: the other system's row at this level is still wanted. But
                // the row must say so, because its percentiles then describe a queue.
                boolean keepsSchedule = r.throughput() >= 0.95 * rate;
                boolean withinSlo = r.p99() <= P99_TARGET_US;
                String verdict = keepsSchedule && withinSlo ? "sustained"
                        : !keepsSchedule ? "SATURATED (throughput) -- not comparable"
                        : "SATURATED (p99 past target) -- not comparable";
                results.add(r.withNotes(verdict + "; " + r.notes()));
            }
        }
        System.out.println();
        printTable(results);
        writeEqualLoadFile(results, n);
    }

    /** Workload mixes of plan section 12.2 / Paper 2 section VIII-C. */
    enum Mix {
        READ_HEAVY(95, 4, 1, false),        // steady-state verification
        BURSTY_REVOKE(90, 10, 0, true),     // incident response
        BALANCED(60, 30, 10, false);        // active onboarding

        final int resolvePct, writePct, revokePct;
        final boolean burst;

        Mix(int r, int w, int rev, boolean burst) {
            this.resolvePct = r; this.writePct = w; this.revokePct = rev; this.burst = burst;
        }

        /** "read-heavy", "bursty-revoke": the label compare.py keys rows by. */
        String label() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    /** Public so the gate suite can drive {@link #chooseLevel} directly (gate B2). */
    /**
     * @param burstTxns ordered revocation operations the burst cost, mean per run (-1 if the
     *     backend cannot say)
     * @param burstTxnsConfirmed of those, how many the system acknowledged, mean per run
     */
    public record Result(String name, double throughput, double throughputCi,
                  double p50, double p95, double p99, String notes,
                  double resolveTput, double writeTput, double revokeTput,
                  double burstDrainMs, double burstP99Us, int burstSize,
                  double burstTxns, double burstTxnsConfirmed) {

        /** Without burst transaction counts (gate B2 builds rows this way). */
        public Result(String name, double throughput, double throughputCi,
                      double p50, double p95, double p99, String notes,
                      double resolveTput, double writeTput, double revokeTput,
                      double burstDrainMs, double burstP99Us, int burstSize) {
            this(name, throughput, throughputCi, p50, p95, p99, notes, resolveTput, writeTput,
                    revokeTput, burstDrainMs, burstP99Us, burstSize, -1, -1);
        }

        Result withNotes(String newNotes) {
            return new Result(name, throughput, throughputCi, p50, p95, p99, newNotes,
                    resolveTput, writeTput, revokeTput, burstDrainMs, burstP99Us, burstSize,
                    burstTxns, burstTxnsConfirmed);
        }
    }

    // ------------------------------------------------------------------ driver

    /**
     * Offered-load sweep (plan section 12.2: each mix at multiple load levels).
     * Returns EVERY level the system sustained, lowest first, because the sweep admits a level
     * from a single run and that is not enough to report it -- see {@link #measureWithStepDown}.
     */
    static List<Integer> sweepOperatingPoint(int n, Mix mix, int[] levels, int batchSize)
            throws Exception {
        List<Integer> admitted = new ArrayList<>();
        for (int rate : levels) {
            RunOutcome o = singleRun(n, mix, rate, batchSize);
            double p99 = o.hist.percentile(99);
            // Two admission criteria, both required.
            //  (1) the arrival schedule is kept: achieved within 5% of offered;
            //  (2) the run is inside the p99 target Paper 2 section III sets for the VDR.
            // Criterion (2) is not redundant: an open-loop generator can keep up on count while
            // a backlog builds, and the percentiles then describe the queue rather than the
            // system. A revoke burst also injects operations beyond the arrival schedule, so
            // achieved can exceed offered while latency is already far past anything usable.
            boolean keepsSchedule = o.throughput >= 0.95 * rate;
            boolean withinSlo = p99 <= P99_TARGET_US;
            boolean sustained = keepsSchedule && withinSlo;
            String verdict = sustained ? "sustained"
                    : (!keepsSchedule ? "SATURATED (throughput)" : "SATURATED (p99 past target)");
            System.out.printf("    sweep: offered %6d ops/s -> achieved %8.0f ops/s  p99 %8.0f us  %s%n",
                    rate, o.throughput, p99, verdict);
            if (sustained) admitted.add(rate); else break;
        }
        if (admitted.isEmpty()) {
            throw new IllegalStateException("no offered load was sustained; lower the sweep levels "
                    + "or give the harness more CPU -- reporting percentiles from a saturated "
                    + "open-loop run would measure the queue, not the system");
        }
        return admitted;
    }

    /**
     * Measures the highest admitted level whose MERGED p99 meets the target, stepping down when it
     * does not (remediation plan section 3).
     *
     * <p>The sweep admits a level from one run. Near the knee one run is not representative:
     * levels admitted at 350-490 us-scale p99 have merged to 795, 811 and 1,098 ms over three
     * runs. Reporting such a level publishes an operating point that Paper 2's own 500 ms target
     * rejects, so the reported point is always re-measured, and the path taken is recorded in the
     * notes so a reviewer can see it was.
     */
    static Result measureWithStepDown(String name, int n, Mix mix, List<Integer> admitted)
            throws Exception {
        return chooseLevel(name, admitted, rate -> {
            try {
                return measure(name, n, mix, rate);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * The step-down decision, separated from the measuring so it can be tested directly
     * (gate B2) rather than only through a run whose timing decides whether the path is taken.
     *
     * @param measurer measures one level; called at most once per admitted level, highest first
     */
    public static Result chooseLevel(String name, List<Integer> admitted,
                                     java.util.function.IntFunction<Result> measurer) {
        StringBuilder path = new StringBuilder();
        for (int i = admitted.size() - 1; i >= 0; i--) {
            int rate = admitted.get(i);
            Result r = measurer.apply(rate);
            if (path.length() > 0) path.append(", ");
            path.append(rate).append(" ops/s -> p99 ")
                .append(Math.round(r.p99() / 1000.0)).append(" ms");
            if (r.p99() <= P99_TARGET_US) {
                if (i < admitted.size() - 1) {
                    System.out.printf("    %s: stepped down to %d ops/s (%s)%n", name, rate, path);
                }
                return r.withNotes(r.notes() + "; admission path: " + path);
            }
            System.out.printf("    %s: merged p99 %.0f ms exceeds the %.0f ms target at %d ops/s; "
                    + "stepping down%n", name, r.p99() / 1000.0, P99_TARGET_US / 1000.0, rate);
        }
        throw new IllegalStateException(name + ": no admitted level met the p99 target when "
                + "measured (" + path + "). Lower the sweep levels; reporting a level whose "
                + "measured p99 is past the target would publish an operating point that Paper 2's "
                + "own SLO rejects.");
    }

    static Result measure(String name, int n, Mix mix, int offeredRate) throws Exception {
        Histogram merged = new Histogram();
        double[] perRunThroughput = new double[RUNS];
        double[] perRunResolve = new double[RUNS];
        double[] perRunWrite = new double[RUNS];
        double[] perRunRevoke = new double[RUNS];
        double[] perRunDrain = new double[RUNS];
        double[] perRunBurstTxns = new double[RUNS];
        double[] perRunBurstConfirmed = new double[RUNS];
        Histogram mergedBurst = new Histogram();
        int burstSize = 0;
        int batchSize = 250;
        String backendNotes = "";

        for (int run = 0; run < RUNS; run++) {
            RunOutcome o = singleRun(n, mix, offeredRate, batchSize);
            backendNotes = o.notes;
            merged.merge(o.hist);
            perRunThroughput[run] = o.throughput;
            perRunResolve[run] = o.resolveThroughput;
            perRunWrite[run] = o.writeThroughput;
            perRunRevoke[run] = o.revokeThroughput;
            perRunDrain[run] = o.burstDrainMs;
            perRunBurstTxns[run] = o.burstTxns;
            perRunBurstConfirmed[run] = o.burstTxnsConfirmed;
            mergedBurst.merge(o.burst);
            burstSize = o.burstSize;
            System.out.printf("  %-34s run %2d/%d: %8.0f ops/s  p99 %7.0f us%n",
                    name, run + 1, RUNS, o.throughput, o.hist.percentile(99));
        }

        if (merged.earlyIssued() > 0) {
            throw new IllegalStateException(String.format(
                    "%d samples were issued before their scheduled arrival; their latency was "
                    + "negative and would have been clamped to zero, deflating p50/p95. "
                    + "This run is not usable -- fix the arrival loop before reporting.",
                    merged.earlyIssued()));
        }

        // The backend describes its own configuration. A hardcoded string here put "confidentiality
        // off, fast path Tier 0" on Hyperledger Indy rows, which have neither.
        String notes = backendNotes;

        return new Result(name,
                Histogram.mean(perRunThroughput), Histogram.ci95(perRunThroughput),
                merged.percentile(50), merged.percentile(95), merged.percentile(99), notes,
                Histogram.mean(perRunResolve), Histogram.mean(perRunWrite), Histogram.mean(perRunRevoke),
                Histogram.mean(perRunDrain), mergedBurst.percentile(99), burstSize,
                Histogram.mean(perRunBurstTxns), Histogram.mean(perRunBurstConfirmed));
    }

    /**
     * @param hist steady-mix latencies only. Burst arrivals are kept out: they share a single
     *     arrival instant, so merging them would make every percentile describe the burst
     *     backlog instead of the system serving the steady stream.
     * @param burst latencies of the finite revoke burst, measured from the instant of injection
     * @param burstDrainMs wall time from injection to the last burst revocation committing
     * @param burstSize number of revocations in the burst
     * @param burstTxns ordered revocation operations submitted from the burst's injection to the
     *     end of the run; the bursty-revoke mix has no steady-state revokes, so all are the burst's
     * @param burstTxnsConfirmed of those, how many the system acknowledged
     */
    record RunOutcome(Histogram hist, double throughput, double resolveThroughput,
                      double writeThroughput, double revokeThroughput,
                      Histogram burst, double burstDrainMs, int burstSize,
                      long burstTxns, long burstTxnsConfirmed, String notes) {}

    /**
     * One run against whichever system {@link #BACKEND} names.
     *
     * <p>The generator below is shared by both systems (baseline plan section 8): same arrival
     * schedule, same warm-up, same merged-histogram percentiles, same burst accounting, same
     * operating-point admission. Only what happens behind the {@link Backend} calls differs, which
     * is what makes the four rows of the evaluation matrix comparable to each other.
     */
    static RunOutcome singleRun(int n, Mix mix, int offeredRate, int batchSize) throws Exception {
        try (Backend backend = BACKEND.create(n, batchSize)) {
            try {

            // ---- population phase (not measured) ----------------------------------
            List<String> dids = backend.populate(DID_POOL);

            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors() * 8));
            Histogram hist = new Histogram();
            AtomicLong committed = new AtomicLong();
            AtomicLong resolves = new AtomicLong(), writes = new AtomicLong(), revokes = new AtomicLong();
            // Burst operations are accounted separately: they are not part of the arrival
            // schedule, so counting them as committed steady-mix work inflates achieved
            // throughput above offered load and makes the admission test meaningless.
            AtomicLong burstCommitted = new AtomicLong(), burstRevokes = new AtomicLong();
            AtomicLong failedResolve = new AtomicLong(), failedWrite = new AtomicLong(),
                    failedRevoke = new AtomicLong();
            java.util.concurrent.atomic.AtomicReference<String> firstFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicLong steadyStart = new AtomicLong(Long.MAX_VALUE);
            long start = System.nanoTime();
            long warmupEndNanos = start + WARMUP_MS * 1_000_000L;
            long endNanos = warmupEndNanos + STEADY_MS * 1_000_000L;

            // ---- open-loop arrival schedule: fixed inter-arrival interval, no back-pressure
            long intervalNanos = 1_000_000_000L / offeredRate;
            // Arrivals are ISSUED in small groups to keep the number of park() calls sane,
            // but every operation keeps its OWN scheduled timestamp, so queueing delay is
            // still charged against the schedule and coordinated omission is avoided.
            final int group = Math.max(1, offeredRate / 500);   // one park per ~2 ms
            long nextArrival = System.nanoTime();
            long opIndex = 0;
            final long burstAt = warmupEndNanos + (STEADY_MS / 2) * 1_000_000L;
            boolean burstFired = false;
            final Histogram burstHist = new Histogram();
            final AtomicLong burstLastCommitNanos = new AtomicLong(0);
            final AtomicLong burstStartNanos = new AtomicLong(0);
            long txnsAtBurst = 0, confirmedAtBurst = 0;

            while (System.nanoTime() < endNanos) {
                long now = System.nanoTime();
                if (now < nextArrival) {
                    LockSupport.parkNanos(nextArrival - now);
                    continue;
                }
                for (int g = 0; g < group; g++) {
                // Issue only arrivals that are already DUE. Issuing ahead of schedule would give
                // an operation a scheduled timestamp in the future, its measured latency would be
                // negative, and the histogram would clamp it to zero -- silently deflating p50 and
                // p95 by the fraction of the group that ran early.
                if (nextArrival > now) break;
                final long scheduled = nextArrival;     // coordinated-omission-free timestamp
                nextArrival += intervalNanos;
                final long idx = opIndex++;
                final boolean measured = scheduled >= warmupEndNanos;
                if (measured) steadyStart.compareAndSet(Long.MAX_VALUE, scheduled);

                if (mix.burst && !burstFired && scheduled >= burstAt) {
                    burstFired = true;
                    txnsAtBurst = backend.revocationTransactionsSubmitted();
                    confirmedAtBurst = backend.revocationTransactionsConfirmed();
                    burstStartNanos.set(System.nanoTime());
                    fireRevocationBurst(backend, dids, burstHist, burstCommitted, burstRevokes,
                            BURST_SIZE, scheduled, burstLastCommitNanos);
                    continue;
                }

                int roll = (int) (idx % 100);
                pool.execute(() -> {
                    try {
                        if (roll < mix.resolvePct) {
                            String did = dids.get((int) (idx % DID_POOL));
                            backend.resolve(did, (int) idx);
                            if (measured) { record(hist, scheduled); committed.incrementAndGet(); resolves.incrementAndGet(); }
                        } else if (roll < mix.resolvePct + mix.writePct) {
                            String did = dids.get((int) (idx % DID_POOL));
                            backend.update(did, 1, docFor((int) idx));
                            if (measured) { record(hist, scheduled); committed.incrementAndGet(); writes.incrementAndGet(); }
                        } else {
                            String did = dids.get((int) (idx % DID_POOL));
                            backend.submitRevoke(backend.registryIdFor(did), handle(idx)).join();
                            if (measured) { record(hist, scheduled); committed.incrementAndGet(); revokes.incrementAndGet(); }
                        }
                    } catch (Exception e) {
                        if (roll < mix.resolvePct) failedResolve.incrementAndGet();
                        else if (roll < mix.resolvePct + mix.writePct) failedWrite.incrementAndGet();
                        else failedRevoke.incrementAndGet();
                        firstFailure.compareAndSet(null, String.valueOf(e));
                        // a failed operation is not a committed operation; it is excluded from
                        // throughput and from the latency histogram, never counted as a success
                    }
                });
                }
            }
            backend.flushRevocations();
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);

            // The fixed arrival window. Dividing by wall time up to the last completion charges
            // the post-window drain — Indy's serial revocation chain, in-flight writes — against
            // the steady stream, and reports a system that kept the schedule as saturated.
            double windowSeconds = STEADY_MS / 1000.0;
            long failures = failedResolve.get() + failedWrite.get() + failedRevoke.get();
            if (failures > 0) {
                System.out.printf("      failures: resolve %d, write %d, revoke %d (first: %s)%n",
                        failedResolve.get(), failedWrite.get(), failedRevoke.get(), firstFailure.get());
            }
            double drainMs = (burstStartNanos.get() == 0) ? 0
                    : (burstLastCommitNanos.get() - burstStartNanos.get()) / 1e6;
            long burstTxns = -1, burstConfirmed = -1;
            if (burstFired && backend.revocationTransactionsSubmitted() >= 0) {
                burstTxns = backend.revocationTransactionsSubmitted() - txnsAtBurst;
                burstConfirmed = backend.revocationTransactionsConfirmed() - confirmedAtBurst;
                System.out.printf("      burst: %d revocations -> %d ordered revocation "
                        + "transaction(s) submitted, %d confirmed%n",
                        BURST_SIZE, burstTxns, burstConfirmed);
            }
            return new RunOutcome(hist, committed.get() / windowSeconds,
                    resolves.get() / windowSeconds, writes.get() / windowSeconds,
                    revokes.get() / windowSeconds,
                    burstHist, drainMs, mix.burst ? BURST_SIZE : 0,
                    burstTxns, burstConfirmed, backend.notes());
            } finally {
                // backend closed by try-with-resources
            }
        }
    }

    /** Bursty-revoke mix: a finite revoke burst injected at t = T (plan section 12.2). */
    /**
     * Bursty-revoke mix: a finite revoke burst injected at t = T (plan section 12.2).
     *
     * <p>The burst is submitted straight to the gateway, which aggregates it into ordered batches
     * (plan section 3.4). It deliberately does NOT spawn a task per revocation: a blocking join per
     * item would occupy every thread in the shared pool, and the steady stream's latency would then
     * measure thread starvation in the load generator rather than the system's behaviour under an
     * incident-response burst. Per-item completion is timed with a callback instead.
     */
    static void fireRevocationBurst(Backend backend,
                                    List<String> dids, Histogram burstHist, AtomicLong committed,
                                    AtomicLong revokes, int burstSize, long scheduled,
                                    AtomicLong lastCommitNanos) {
        String registryId = backend.registryIdFor(dids.get(0));
        for (int i = 0; i < burstSize; i++) {
            backend.submitRevoke(registryId, handle(i))
                    .whenComplete((ok, err) -> {
                        if (err != null) {
                            return;
                        }
                        // Every burst arrival shares the injection instant: that is the
                        // incident-response model, and it is why these latencies are reported as
                        // burst drain rather than folded into the steady-mix percentiles.
                        record(burstHist, scheduled);
                        lastCommitNanos.accumulateAndGet(System.nanoTime(), Math::max);
                        committed.incrementAndGet();
                        revokes.incrementAndGet();
                    });
        }
        backend.flushRevocations();
    }

    static void record(Histogram hist, long scheduledNanos) {
        long micros = (System.nanoTime() - scheduledNanos) / 1000;
        synchronized (hist) { hist.record(micros); }
    }

    static byte[] docFor(int i) {
        return ("{\"@context\":\"https://www.w3.org/ns/did/v1\",\"id\":\"did:vdr:bench" + i
                + "\",\"verificationMethod\":[{\"type\":\"Ed25519VerificationKey2020\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String handle(long i) {
        return Crypto.hex(Crypto.sha256("bench-handle-" + i));
    }

    // ------------------------------------------------------------------ output

    static void printTable(List<Result> results) {
        System.out.println("=".repeat(94));
        System.out.println("EVALUATION MATRIX (plan section 12)");
        System.out.println("-".repeat(94));
        System.out.printf("%-34s | %-18s | %-22s | %s%n",
                "Configuration", "Throughput (ops/s)", "p50 / p95 / p99 (us)", "Notes");
        System.out.println("-".repeat(94));
        for (Result r : results) {
            System.out.printf("%-34s | %8.0f +/- %-6.0f | %6.0f / %6.0f / %-6.0f | %s%n",
                    r.name, r.throughput, r.throughputCi, r.p50, r.p95, r.p99, r.notes);
        }
        for (String pending : BACKEND.rowsNotMeasuredHere()) {
            System.out.printf("%-34s | %-18s | %-22s | %s%n",
                    pending, "not measured", "not measured", "run the other backend to fill this");
        }
        System.out.println("-".repeat(94));
        for (Result r : results) {
            System.out.printf("  %s per-operation: resolve %.0f, register/update %.0f, revoke %.0f ops/s%n",
                    r.name, r.resolveTput, r.writeTput, r.revokeTput);
            if (r.burstSize > 0) {
                // The burst metric the plan actually asks for (section 3.4): a burst's throughput is
                // bounded by batch size, not by consensus round count. Reported separately from the
                // steady-mix percentiles above.
                System.out.printf("  %s burst: %d revocations drained in %.0f ms (%.0f revokes/s), "
                                + "burst p99 %.0f us%n",
                        r.name, r.burstSize, r.burstDrainMs,
                        r.burstDrainMs > 0 ? r.burstSize / (r.burstDrainMs / 1000.0) : 0,
                        r.burstP99Us);
                if (r.burstTxns >= 0) {
                    System.out.printf("  %s burst cost: %.1f ordered revocation transactions per run "
                            + "(%.1f confirmed)%n", r.name, r.burstTxns, r.burstTxnsConfirmed);
                }
            }
        }
    }

    static void writeResultsFile(List<Result> results, int n) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Evaluation matrix -- harness output\n\n");
        sb.append("Generated by `vdr.bench.Bench`. n = ").append(n)
          .append(", f = ").append((n - 1) / 3).append(", ").append(RUNS).append(" runs per configuration.\n")
          .append("Warm-up ").append(WARMUP_MS).append(" ms, steady window ").append(STEADY_MS)
          .append(" ms. Operating points are re-measured and stepped down until the merged p99 is\n")
          .append("within ").append(Math.round(P99_TARGET_US / 1000)).append(" ms; the path taken is in each row's notes.\n\n");
        appendProvenance(sb);
        sb.append("| Configuration | Throughput (ops/s) | p50 / p95 / p99 latency | Notes |\n");
        sb.append("|---|---|---|---|\n");
        for (Result r : results) {
            appendRow(sb, r);
        }
        for (String pending : BACKEND.rowsNotMeasuredHere()) {
            sb.append("| ").append(pending)
              .append(" | — | — | run the other backend to fill this |\n");
        }
        sb.append("| Public-chain reference (ION/regtest) | — | — | M8: not yet implemented |\n\n");
        for (Result r : results) {
            appendBurst(sb, r);
        }
        appendPerOperation(sb, results);
        java.nio.file.Files.writeString(java.nio.file.Path.of("RESULTS.md"), sb.toString());
        System.out.println("\nwrote RESULTS.md");
    }

    /**
     * Equal-offered-load results. The first heading is the marker compare.py uses to tell this file
     * from an operating-point RESULTS.md, so the two never stand in for each other.
     */
    static void writeEqualLoadFile(List<Result> results, int n) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Equal offered load -- harness output\n\n");
        sb.append("Generated by `vdr.bench.Bench` with -Dbench.equalLoad.*. n = ").append(n)
          .append(", f = ").append((n - 1) / 3).append(", ").append(RUNS).append(" runs per configuration.\n")
          .append("Warm-up ").append(WARMUP_MS).append(" ms, steady window ").append(STEADY_MS)
          .append(" ms. Every level is measured at the offered rate given, with no sweep and no\n")
          .append("step-down; a row whose p99 is past ").append(Math.round(P99_TARGET_US / 1000))
          .append(" ms or that missed the schedule is marked SATURATED.\n\n");
        appendProvenance(sb);
        sb.append("| Configuration | Throughput (ops/s) | p50 / p95 / p99 latency | Notes |\n");
        sb.append("|---|---|---|---|\n");
        for (Result r : results) {
            appendRow(sb, r);
        }
        sb.append('\n');
        for (Result r : results) {
            appendBurst(sb, r);
        }
        appendPerOperation(sb, results);
        java.nio.file.Files.writeString(java.nio.file.Path.of("RESULTS.md"), sb.toString());
        System.out.println("\nwrote RESULTS.md (equal offered load)");
    }

    static void appendRow(StringBuilder sb, Result r) {
        sb.append(String.format("| %s | %.0f ± %.0f | %.0f / %.0f / %.0f µs | %s |%n",
                r.name, r.throughput, r.throughputCi, r.p50, r.p95, r.p99, r.notes));
    }

    static void appendBurst(StringBuilder sb, Result r) {
        if (r.burstSize <= 0) return;
        // Equal-load files hold several bursts, so each names its row for compare.py.
        sb.append("\n**Revoke burst")
          .append(r.name.contains(" @ ") ? " (" + r.name + ")" : "").append(".** ").append(r.burstSize)
          .append(" revocations drained in ").append(String.format("%.0f", r.burstDrainMs))
          .append(" ms (").append(String.format("%.0f", r.burstDrainMs > 0
                ? r.burstSize / (r.burstDrainMs / 1000.0) : 0))
          .append(" revokes/s), burst p99 ").append(String.format("%.0f", r.burstP99Us))
          .append(" \u00b5s. Burst arrivals share one injection instant, so they are\n")
          .append("reported here rather than folded into the steady-mix percentiles above.\n");
        if (r.burstTxns >= 0) {
            // The drain is a per-BATCH cost: both systems aggregate revocations before ordering
            // them. Saying how many transactions the burst became keeps it from being read as a
            // per-revocation consensus cost.
            sb.append(String.format("Burst cost: %.1f ordered revocation transactions per run "
                    + "(%.1f confirmed).%n", r.burstTxns, r.burstTxnsConfirmed));
        }
    }

    static void appendPerOperation(StringBuilder sb, List<Result> results) {
        sb.append("## Per-operation throughput\n\n");
        sb.append("| Configuration | resolve | register/update | revoke |\n|---|---|---|---|\n");
        for (Result r : results) {
            sb.append(String.format("| %s | %.0f | %.0f | %.0f |%n",
                    r.name, r.resolveTput, r.writeTput, r.revokeTput));
        }
    }

    /**
     * The provenance warning belongs only to runs served by the simulated ordering layer.
     * Printing it on an Indy run -- measured against a live four-validator pool -- would be
     * false, and a reviewer who spots one false disclaimer discounts the rest.
     */
    static void appendProvenance(StringBuilder sb) {
        if (BACKEND.usesSimulatedReplication()) {
            sb.append("> **Provenance warning. These are not measurements of a BFT system.**\n")
              .append("> They were produced against the in-process simulated total-order layer\n")
              .append("> (`vdr.replication.SimulatedCluster`), not against BFT-SMaRt on a real cluster.\n")
              .append(">\n")
              .append("> The latency figures are dominated by two **hardcoded constants** in\n")
              .append("> `SimulatedCluster.standard()` -- 3000 us charged per consensus instance and 300 us\n")
              .append("> for a single-replica round trip -- chosen to match DepSpace's 2008 Emulab anchor\n")
              .append("> points. Those constants are inputs to this run, not results of it. A p50 derived\n")
              .append("> from them tells you what the harness was told to assume, not what a cluster does.\n")
              .append(">\n")
              .append("> What these rows DO establish: the arrival schedule, warm-up, merged-histogram\n")
              .append("> percentiles, per-operation split, burst accounting and operating-point admission\n")
              .append("> all work end to end, and the batching and fast-path mechanisms move the numbers in\n")
              .append("> the direction the design predicts. That is milestone M0's question.\n")
              .append(">\n")
              .append("> They are NOT the numbers that belong in Paper 2, Table III. Fill that table from M9\n")
              .append("> runs against the real engine, with both synthetic constants removed.\n")
              .append("> Run with -Dvdr.replication=bftsmart once the cluster is up.\n\n");
        }
    }
}
