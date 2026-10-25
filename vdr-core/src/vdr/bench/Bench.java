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
    /**
     * DIDs written before warm-up, which resolves and updates then cycle over. Overridable because
     * updates are serialised per DID on both systems: once the update rate x update latency exceeds
     * the pool, updates queue behind each other's locks (reported as contendedUpdates in the notes).
     */
    static final int DID_POOL = Integer.getInteger("bench.didPool", 400);

    /**
     * Sweep runs may use shorter windows than the reported runs: a sweep level only has to show
     * whether the schedule is kept, and the chosen level is then re-measured with the full
     * WARMUP_MS / STEADY_MS / RUNS (and stepped down if its merged p99 misses the target). Defaults
     * keep the plan's protocol (sweep windows = measurement windows).
     */
    static final long SWEEP_WARMUP_MS = Long.getLong("bench.sweepWarmupMs", WARMUP_MS);
    static final long SWEEP_STEADY_MS = Long.getLong("bench.sweepSteadyMs", STEADY_MS);

    /**
     * Bisection steps between the last sustained and the first saturated sweep level, so the
     * operating point lands near the knee without hand-tuning a level list per machine. 0 keeps
     * the plain level list. Each step is one sweep run.
     */
    static final int REFINE_STEPS = Integer.getInteger("bench.refine", 0);

    /**
     * Which mixes this invocation measures: read-heavy, bursty-revoke, register, update, balanced
     * (default the first two).
     */
    static final List<String> MIXES = List.of(System.getProperty("bench.mixes",
            "read-heavy,bursty-revoke").replace(" ", "").split(","));

    /**
     * Load-generator threads. "cached" (default): an unbounded pool, so the generator is truly
     * open-loop -- the old fixed pool of 8 x vCPUs capped in-flight operations at 32 on a 4 vCPU
     * box, i.e. at most 32 / latency ops/s, which throttled Indy (whose writes take seconds) far
     * below its real capacity and turned the "open loop" into a closed one. "virtual": one
     * virtual thread per operation. "fixed": the old behaviour, for comparison only.
     */
    static final String EXECUTOR = System.getProperty("bench.executor", "cached");

    /**
     * In-flight bound that ends a run as saturated. The generator is open loop with one thread per
     * in-flight operation, so past a write knee the thread count grows without limit until the JVM
     * cannot create one -- which killed a register sweep at 2000 ops/s (macOS allows 2048 threads
     * per process). The bound is rate x 2 x the p99 target (1 s of arrivals): by Little's law,
     * more operations in flight than that means mean latency is already past 1 s, so the run could
     * not have met the 500 ms p99 target, and stopping it changes no admission decision.
     *
     * <p>{@code bench.maxInFlight} caps it in absolute terms so the generator stays inside the
     * process thread limit. An ordered write on BFT-SMaRt holds two threads (the generator's, and
     * a vdr-ordered one in BftSmartCluster), so the default is 800 on macOS (2048 threads per
     * process) and 4000 elsewhere -- enough on Linux for every level measure-table.sh sweeps. Below
     * rate x the p99 target the cap no longer implies the target was missed, and the verdict says
     * NOT MEASURABLE instead: raise the cap before reporting a knee above maxInFlight / 0.5 s.
     */
    static final long MAX_IN_FLIGHT = Long.getLong("bench.maxInFlight",
            System.getProperty("os.name", "").toLowerCase().contains("mac") ? 800L : 4000L);

    static long inFlightLimit(int offeredRate) {
        return Math.min(MAX_IN_FLIGHT, Math.max(200L, (long) (offeredRate * 2 * P99_TARGET_US / 1e6)));
    }

    /** True when hitting the bound at this rate proves the p99 target was missed (Little's law). */
    static boolean inFlightLimitImpliesSaturation(int offeredRate) {
        return inFlightLimit(offeredRate) >= offeredRate * P99_TARGET_US / 1e6;
    }

    static ExecutorService newGeneratorPool() {
        return switch (EXECUTOR) {
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            case "fixed" -> Executors.newFixedThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors() * 8));
            // Platform threads by default: BFT-SMaRt's client and indy-vdr's FFM upcalls block in
            // places that would pin virtual-thread carriers.
            default -> Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bench-op");
                t.setDaemon(true);
                return t;
            });
        };
    }

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

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            // Exit explicitly: the BFT-SMaRt client's non-daemon threads would keep a failed run's
            // JVM (and its client sessions) alive.
            t.printStackTrace();
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
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

        if (Arrays.stream(Mix.values()).anyMatch(m -> !equalLoadLevels(m).isEmpty())) {
            runEqualLoad(n);
            return;
        }

        // Low levels are needed for Indy, whose writes go through a much slower consensus path.
        int[] levels = java.util.Arrays.stream(
                        System.getProperty("bench.levels", "10,20,50,100,250,500,1000,2000").split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
        System.out.printf("generator executor = %s, sweep windows = %d ms / %d ms, refine steps = %d, "
                + "mixes = %s, max in flight = %d%n", EXECUTOR, SWEEP_WARMUP_MS, SWEEP_STEADY_MS,
                REFINE_STEPS, MIXES, MAX_IN_FLIGHT);
        String label = BACKEND.displayName();
        List<Result> results = new ArrayList<>();
        for (Mix mix : Mix.values()) {
            if (!MIXES.contains(mix.label())) continue;
            // -Dbench.levels.<mix> (e.g. bench.levels.register) overrides the shared list for one
            // mix: the write-only mixes' knees sit far below the read-heavy one, so one list
            // either wastes runs on the writes or starts above their knee.
            List<Integer> own = levelsProperty("bench.levels." + mix.propertyKey());
            int[] mixLevels = own.isEmpty() ? levels : own.stream().mapToInt(Integer::intValue).toArray();
            // -Dbench.admitted.<mix>: levels a previous -Dbench.sweepOnly invocation admitted. The
            // sweep deliberately drives the system past its knee; measuring the operating point on
            // the same cluster and client afterwards measured that damage (update at 500 ops/s:
            // 0-42 ops/s after a sweep, 500 ops/s with p99 15 ms on a fresh cluster).
            // measure-table.sh therefore sweeps in one invocation and measures every level on a
            // fresh cluster / ledger in another.
            List<Integer> admitted = levelsProperty("bench.admitted." + mix.propertyKey());
            if (admitted.isEmpty()) {
                System.out.println("\n-- offered-load sweep, " + mix.label()
                        + (own.isEmpty() ? "" : " (levels " + own + ")"));
                admitted = sweepOperatingPoint(n, mix, mixLevels, 250);
                if (SWEEP_ONLY) {
                    // The line measure-table.sh reads; nothing is measured in this invocation.
                    System.out.println("ADMITTED " + mix.label() + " " + admitted.stream()
                            .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
                    continue;
                }
                System.out.printf("%ncandidate operating point: %s %d ops/s (re-measured below)%n%n",
                        mix.label(), admitted.get(admitted.size() - 1));
            } else {
                System.out.printf("%n-- %s: measuring admitted level(s) %s (swept separately)%n",
                        mix.label(), admitted);
            }
            try {
                results.add(measureWithStepDown(label + " -- " + mix.label(), n, mix, admitted));
            } catch (NoLevelMetTarget e) {
                // Exit 3 tells measure-table.sh to step down on a FRESH cluster, carrying the path.
                System.out.println("STEPDOWN_PATH " + e.compactPath);
                System.out.flush();
                System.exit(3);
            }
        }
        if (SWEEP_ONLY) {
            System.out.flush();
            System.exit(0);
        }

        System.out.println();
        printTable(results);
        writeResultsFile(results, n);
        writeRowsTsv(results, n);
        // The shared BFT-SMaRt client's netty threads are not daemons: without an explicit exit the
        // JVM outlives the run, keeps its sessions open against the replicas, and the next
        // invocation's clients collide with it.
        System.out.flush();
        System.exit(0);
    }

    /**
     * Equal-offered-load mode. Comparing each system at its OWN operating point compares latencies
     * at loads up to 40x apart; these lists fix the load instead, so both systems are measured at
     * the same offered rate. Pick levels BOTH systems sustain: a level past either system's knee
     * measures that system's queue. Either property switches the run to this mode (no sweep, no
     * step-down; every listed level is measured with the full RUNS).
     *
     *   -Dbench.equalLoad.readHeavy=50,200 -Dbench.equalLoad.burstyRevoke=25,50
     *   -Dbench.equalLoad.register=10,25,50 -Dbench.equalLoad.update=10,25,50
     */
    static List<Integer> equalLoadLevels(Mix mix) {
        return levelsProperty("bench.equalLoad." + mix.propertyKey());
    }

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
        for (Mix mix : Mix.values()) {
            for (int rate : equalLoadLevels(mix)) {
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
        writeRowsTsv(results, n);
        System.out.flush();
        System.exit(0);
    }

    /** The four VDR operations, in the order the per-operation tables list them. */
    public enum OpType {
        RESOLVE, REGISTER, UPDATE, REVOKE;

        public String label() {
            return name().toLowerCase();
        }
    }

    /**
     * Workload mixes of plan section 12.2 / Paper 2 section VIII-C, plus one single-operation mix
     * per write. The mixed workloads' compositions are unchanged from the rows already reported:
     * their write share is updates only.
     */
    public enum Mix {
        READ_HEAVY(95, 0, 4, 1, false),     // steady-state verification
        BURSTY_REVOKE(90, 0, 10, 0, true),  // incident response
        BALANCED(60, 0, 30, 10, false),     // active onboarding
        REGISTER(0, 100, 0, 0, false),      // every arrival registers a fresh DID
        UPDATE(0, 0, 100, 0, false);        // every arrival rotates a populated DID's key

        final int resolvePct, registerPct, updatePct, revokePct;
        final boolean burst;

        Mix(int r, int reg, int upd, int rev, boolean burst) {
            this.resolvePct = r; this.registerPct = reg; this.updatePct = upd; this.revokePct = rev;
            this.burst = burst;
        }

        /** Percentages over all four operations; gate B6 checks this is 100 for every mix. */
        public int totalPct() {
            return resolvePct + registerPct + updatePct + revokePct;
        }

        /** The operation the arrival with this roll (idx % 100) performs. */
        public OpType opFor(int roll) {
            if (roll < resolvePct) return OpType.RESOLVE;
            if (roll < resolvePct + registerPct) return OpType.REGISTER;
            if (roll < resolvePct + registerPct + updatePct) return OpType.UPDATE;
            return OpType.REVOKE;
        }

        /** "read-heavy", "bursty-revoke", "register", "update": the label compare.py keys rows by. */
        public String label() {
            return name().toLowerCase().replace('_', '-');
        }

        /** "readHeavy", "burstyRevoke", ...: the suffix of -Dbench.equalLoad.<key>. */
        String propertyKey() {
            String[] parts = name().toLowerCase().split("_");
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
            return sb.toString();
        }
    }

    /**
     * One operation type's share of a configuration: committed ops/s (mean over runs) and
     * percentiles from that operation's histogram merged across all runs.
     */
    public record OpStats(OpType op, double throughput, long count,
                          double p50, double p95, double p99) {}

    /** Public so the gate suite can drive {@link #chooseLevel} directly (gate B2). */
    /**
     * @param burstTxns ordered revocation operations the burst cost, mean per run (-1 if the
     *     backend cannot say)
     * @param burstTxnsConfirmed of those, how many the system acknowledged, mean per run
     * @param burstDrainCi 95% confidence half-width of the per-run drain times
     * @param burstP50Us burst p50 / p95 from the burst's merged histogram (as burstP99Us)
     */
    /**
     * @param writeTput register + update ops/s (the combined column of earlier tables)
     * @param perOp one entry per operation type that committed at least once, in OpType order
     */
    public record Result(String name, double throughput, double throughputCi,
                  double p50, double p95, double p99, String notes,
                  double resolveTput, double writeTput, double revokeTput,
                  double burstDrainMs, double burstP99Us, int burstSize,
                  double burstTxns, double burstTxnsConfirmed, List<OpStats> perOp,
                  double burstDrainCi, double burstP50Us, double burstP95Us) {

        /** Without burst transaction counts or per-operation stats (gate B2 builds rows this way). */
        public Result(String name, double throughput, double throughputCi,
                      double p50, double p95, double p99, String notes,
                      double resolveTput, double writeTput, double revokeTput,
                      double burstDrainMs, double burstP99Us, int burstSize) {
            this(name, throughput, throughputCi, p50, p95, p99, notes, resolveTput, writeTput,
                    revokeTput, burstDrainMs, burstP99Us, burstSize, -1, -1, List.of(), 0, 0, 0);
        }

        Result withNotes(String newNotes) {
            return new Result(name, throughput, throughputCi, p50, p95, p99, newNotes,
                    resolveTput, writeTput, revokeTput, burstDrainMs, burstP99Us, burstSize,
                    burstTxns, burstTxnsConfirmed, perOp, burstDrainCi, burstP50Us, burstP95Us);
        }

        /** The stats for one operation, or null if it never committed in this configuration. */
        public OpStats op(OpType t) {
            for (OpStats o : perOp) if (o.op() == t) return o;
            return null;
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
        int saturatedAt = -1;
        for (int rate : levels) {
            if (!sweepLevel(n, mix, rate, batchSize)) { saturatedAt = rate; break; }
            admitted.add(rate);
        }
        if (admitted.isEmpty()) {
            throw new IllegalStateException("no offered load was sustained; lower the sweep levels "
                    + "or give the harness more CPU -- reporting percentiles from a saturated "
                    + "open-loop run would measure the queue, not the system");
        }
        // Bisect between the last sustained and the first saturated level.
        int lo = admitted.get(admitted.size() - 1), hi = saturatedAt;
        for (int step = 0; step < REFINE_STEPS && hi > 0; step++) {
            int mid = roundLevel((lo + hi) / 2);
            if (mid <= lo || mid >= hi || hi - lo <= Math.max(5, lo / 20)) break;
            if (sweepLevel(n, mix, mid, batchSize)) { admitted.add(mid); lo = mid; } else { hi = mid; }
        }
        return admitted;
    }

    /** Rounds a refined level to 5 ops/s below 200, 25 below 2000, 50 above. */
    static int roundLevel(int v) {
        int q = v < 200 ? 5 : v < 2000 ? 25 : 50;
        return Math.max(q, (v / q) * q);
    }

    /** One sweep run at one level: true if the schedule is kept within the p99 target. */
    static boolean sweepLevel(int n, Mix mix, int rate, int batchSize) throws Exception {
        {
            RunOutcome o = singleRun(n, mix, rate, batchSize, SWEEP_WARMUP_MS, SWEEP_STEADY_MS);
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
            boolean sustained = keepsSchedule && withinSlo && !o.overloaded;
            String verdict = sustained ? "sustained"
                    : o.overloaded ? (inFlightLimitImpliesSaturation(rate)
                            ? "SATURATED (in-flight bound " + inFlightLimit(rate) + " hit)"
                            : "NOT MEASURABLE (generator cap " + inFlightLimit(rate)
                                    + " hit; raise -Dbench.maxInFlight)")
                    : (!keepsSchedule ? "SATURATED (throughput)" : "SATURATED (p99 past target)");
            System.out.printf("    sweep: offered %6d ops/s -> achieved %8.0f ops/s  p99 %8.0f us  %s%s%n",
                    rate, o.throughput, p99, verdict,
                    o.failures > 0 ? "  (" + o.failures + " failed ops)" : "");
            return sustained;
        }
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
        Result r = chooseLevel(name, admitted, rate -> {
            try {
                return measure(name, n, mix, rate);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // Levels already abandoned by earlier invocations (each on its own fresh cluster) belong in
        // the same path, so the row still shows every level that was tried.
        String prior = expandPath(PRIOR_ADMISSION_PATH);
        return prior.isEmpty() ? r : r.withNotes(r.notes().replace(
                "admission path: ", "admission path: " + prior + ", "));
    }

    /**
     * "1000:1475,500:270" -> "1000 ops/s -> p99 1475 ms, 500 ops/s -> p99 270 ms". The compact form
     * travels through JAVA_TOOL_OPTIONS, which splits on whitespace.
     */
    static String expandPath(String compact) {
        StringBuilder sb = new StringBuilder();
        for (String step : compact.split(",")) {
            if (step.isBlank()) continue;
            String[] rp = step.trim().split(":");
            if (sb.length() > 0) sb.append(", ");
            sb.append(rp[0]).append(" ops/s -> p99 ").append(rp.length > 1 ? rp[1] : "?").append(" ms");
        }
        return sb.toString();
    }

    /** Sweep only, print "ADMITTED <mix> <levels>", measure nothing (measure-table.sh phase 1). */
    static final boolean SWEEP_ONLY = Boolean.getBoolean("bench.sweepOnly");

    /** Admission path carried in from earlier invocations that stepped down. */
    static final String PRIOR_ADMISSION_PATH = System.getProperty("bench.priorAdmissionPath", "").trim();

    /** No admitted level met the p99 target; {@link #compactPath} lists what was measured. */
    public static final class NoLevelMetTarget extends IllegalStateException {
        /** "rate:p99ms,rate:p99ms", highest level first; see {@link #expandPath}. */
        public final String compactPath;

        NoLevelMetTarget(String message, String compactPath) {
            super(message);
            this.compactPath = compactPath;
        }
    }

    /**
     * The step-down decision, separated from the measuring so it can be tested directly
     * (gate B2) rather than only through a run whose timing decides whether the path is taken.
     *
     * @param measurer measures one level; called at most once per admitted level, highest first
     */
    public static Result chooseLevel(String name, List<Integer> admitted,
                                     java.util.function.IntFunction<Result> measurer) {
        StringBuilder path = new StringBuilder(), compact = new StringBuilder();
        for (int i = admitted.size() - 1; i >= 0; i--) {
            int rate = admitted.get(i);
            Result r = measurer.apply(rate);
            if (compact.length() > 0) compact.append(',');
            compact.append(rate).append(':').append(Math.round(r.p99() / 1000.0));
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
        throw new NoLevelMetTarget(name + ": no admitted level met the p99 target when "
                + "measured (" + path + "). Lower the sweep levels; reporting a level whose "
                + "measured p99 is past the target would publish an operating point that Paper 2's "
                + "own SLO rejects.", compact.toString());
    }

    static Result measure(String name, int n, Mix mix, int offeredRate) throws Exception {
        Histogram merged = new Histogram();
        double[] perRunThroughput = new double[RUNS];
        int ops = OpType.values().length;
        Histogram[] mergedOp = new Histogram[ops];
        double[][] perRunOp = new double[ops][RUNS];
        for (int t = 0; t < ops; t++) mergedOp[t] = new Histogram();
        long contended = 0;
        int overloadedRuns = 0;
        double[] perRunDrain = new double[RUNS];
        double[] perRunBurstTxns = new double[RUNS];
        double[] perRunBurstConfirmed = new double[RUNS];
        Histogram mergedBurst = new Histogram();
        int burstSize = 0;
        int batchSize = 250;
        String backendNotes = "";
        long failures = 0;

        for (int run = 0; run < RUNS; run++) {
            RunOutcome o = singleRun(n, mix, offeredRate, batchSize, WARMUP_MS, STEADY_MS);
            backendNotes = o.notes;
            failures += o.failures;
            merged.merge(o.hist);
            perRunThroughput[run] = o.throughput;
            for (int t = 0; t < ops; t++) {
                mergedOp[t].merge(o.opHist[t]);
                perRunOp[t][run] = o.opThroughput[t];
            }
            if (o.contendedUpdates > 0) contended += o.contendedUpdates;
            if (o.overloaded) overloadedRuns++;
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
        String notes = String.format("offered %d ops/s, %d runs, %d failed ops; %s",
                offeredRate, RUNS, failures, backendNotes);
        if (contended > 0) {
            // Each backend's notes() shows only its last run's count; this is the total.
            System.out.printf("    WARNING: %s: %d updates waited for an in-flight update of the "
                    + "same DID; raise -Dbench.didPool so update latency excludes that wait%n",
                    name, contended);
            notes += String.format("; WARNING %d contended updates over all runs", contended);
        }

        // An overloaded run stopped early, so its histogram holds only what completed before the
        // stop. The in-flight bound implies latency of at least 2 x the target; report that as a
        // floor so neither the step-down nor an equal-load verdict can read the row as within it.
        double p99 = merged.percentile(99);
        if (overloadedRuns > 0) {
            p99 = Math.max(p99, 2 * P99_TARGET_US);
            notes = String.format("OVERLOADED in %d of %d runs (in-flight > %d; p99 is a lower "
                    + "bound); %s", overloadedRuns, RUNS, inFlightLimit(offeredRate), notes);
        }

        List<OpStats> perOp = new ArrayList<>();
        for (OpType t : OpType.values()) {
            Histogram h = mergedOp[t.ordinal()];
            if (h.count() == 0) continue;
            double opP99 = overloadedRuns > 0 ? Math.max(h.percentile(99), 2 * P99_TARGET_US)
                    : h.percentile(99);
            perOp.add(new OpStats(t, Histogram.mean(perRunOp[t.ordinal()]), h.count(),
                    h.percentile(50), h.percentile(95), opP99));
        }
        double[] perRunWrite = new double[RUNS];
        for (int run = 0; run < RUNS; run++) {
            perRunWrite[run] = perRunOp[OpType.REGISTER.ordinal()][run]
                    + perRunOp[OpType.UPDATE.ordinal()][run];
        }

        return new Result(name,
                Histogram.mean(perRunThroughput), Histogram.ci95(perRunThroughput),
                merged.percentile(50), merged.percentile(95), p99, notes,
                Histogram.mean(perRunOp[OpType.RESOLVE.ordinal()]), Histogram.mean(perRunWrite),
                Histogram.mean(perRunOp[OpType.REVOKE.ordinal()]),
                Histogram.mean(perRunDrain), mergedBurst.percentile(99), burstSize,
                Histogram.mean(perRunBurstTxns), Histogram.mean(perRunBurstConfirmed), perOp,
                Histogram.ci95(perRunDrain), mergedBurst.percentile(50), mergedBurst.percentile(95));
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
     * @param opHist steady-mix latencies split by operation, indexed by OpType ordinal
     * @param opThroughput committed ops/s in the steady window per operation, same indexing
     * @param contendedUpdates updates that waited for the same DID's lock (-1 if unknown)
     * @param overloaded the run hit {@link #inFlightLimit} and was stopped early: saturated
     */
    public record RunOutcome(Histogram hist, double throughput, Histogram[] opHist, double[] opThroughput,
                      Histogram burst, double burstDrainMs, int burstSize,
                      long burstTxns, long burstTxnsConfirmed, String notes, long failures,
                      long contendedUpdates, boolean overloaded) {}

    /**
     * One run against whichever system {@link #BACKEND} names.
     *
     * <p>The generator below is shared by both systems (baseline plan section 8): same arrival
     * schedule, same warm-up, same merged-histogram percentiles, same burst accounting, same
     * operating-point admission. Only what happens behind the {@link Backend} calls differs, which
     * is what makes the four rows of the evaluation matrix comparable to each other.
     */
    public static RunOutcome singleRun(int n, Mix mix, int offeredRate, int batchSize,
                                long warmupMs, long steadyMs) throws Exception {
        try (Backend backend = BACKEND.create(n, batchSize)) {
            try {

            // ---- population phase (not measured) ----------------------------------
            List<String> dids = backend.populate(DID_POOL);

            ExecutorService pool = newGeneratorPool();
            Histogram hist = new Histogram();
            AtomicLong committed = new AtomicLong();
            int ops = OpType.values().length;
            Histogram[] opHist = new Histogram[ops];
            AtomicLong[] opCommitted = new AtomicLong[ops], opFailed = new AtomicLong[ops];
            for (int t = 0; t < ops; t++) {
                opHist[t] = new Histogram();
                opCommitted[t] = new AtomicLong();
                opFailed[t] = new AtomicLong();
            }
            // Burst operations are accounted separately: they are not part of the arrival
            // schedule, so counting them as committed steady-mix work inflates achieved
            // throughput above offered load and makes the admission test meaningless.
            AtomicLong burstCommitted = new AtomicLong(), burstRevokes = new AtomicLong();
            java.util.concurrent.atomic.AtomicReference<String> firstFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicLong steadyStart = new AtomicLong(Long.MAX_VALUE);
            long start = System.nanoTime();
            long warmupEndNanos = start + warmupMs * 1_000_000L;
            long endNanos = warmupEndNanos + steadyMs * 1_000_000L;

            // ---- open-loop arrival schedule: fixed inter-arrival interval, no back-pressure
            long intervalNanos = 1_000_000_000L / offeredRate;
            // Arrivals are ISSUED in small groups to keep the number of park() calls sane,
            // but every operation keeps its OWN scheduled timestamp, so queueing delay is
            // still charged against the schedule and coordinated omission is avoided.
            final int group = Math.max(1, offeredRate / 500);   // one park per ~2 ms
            long nextArrival = System.nanoTime();
            long opIndex = 0;
            final long burstAt = warmupEndNanos + (steadyMs / 2) * 1_000_000L;
            boolean burstFired = false;
            final Histogram burstHist = new Histogram();
            final AtomicLong burstLastCommitNanos = new AtomicLong(0);
            final AtomicLong burstStartNanos = new AtomicLong(0);
            long txnsAtBurst = 0, confirmedAtBurst = 0;
            // Updates go round-robin over the whole DID pool. Picking them by idx % DID_POOL (with
            // DID_POOL a multiple of 100) sent every update to the same 16 DIDs, so successive
            // rotations of one DID overlapped and conflicted on both systems.
            long writeSeq = 0;
            // Measured registers take names the backend guarantees were never written before.
            long registerSeq = 0;
            final AtomicLong inFlight = new AtomicLong();
            final long inFlightLimit = inFlightLimit(offeredRate);
            boolean overloaded = false;
            long hitBound = 0;
            boolean hitInWarmup = false;

            arrivals:
            while (System.nanoTime() < endNanos) {
                // Warm-up is discarded, so a backlog that forms while the system is cold (fresh
                // replica JVMs) and then drains must not end the run: only the thread-safety cap
                // applies before the window. A backlog that never clears still trips the
                // Little's-law bound once the window opens.
                boolean inWarmup = System.nanoTime() < warmupEndNanos;
                long bound = inWarmup ? MAX_IN_FLIGHT : inFlightLimit;
                if (inFlight.get() > bound) {
                    overloaded = true;
                    hitBound = bound;
                    hitInWarmup = inWarmup;
                    break arrivals;
                }
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

                final OpType op = mix.opFor((int) (idx % 100));
                final String did = switch (op) {
                    case REGISTER -> backend.freshDid(registerSeq++);
                    case UPDATE -> dids.get((int) (writeSeq++ % DID_POOL));
                    default -> dids.get((int) (idx % DID_POOL));
                };
                inFlight.incrementAndGet();
                pool.execute(() -> {
                    try {
                        switch (op) {
                            case RESOLVE -> backend.resolve(did, (int) idx);
                            case REGISTER -> backend.register(did, docFor((int) idx));
                            case UPDATE -> backend.update(did, 1, docFor((int) idx));
                            case REVOKE -> backend.submitRevoke(backend.registryIdFor(did),
                                    handle(idx)).join();
                        }
                        if (measured) {
                            // One timestamp for both histograms, so the per-op split is exactly
                            // a partition of the mix histogram.
                            long micros = (System.nanoTime() - scheduled) / 1000;
                            synchronized (hist) { hist.record(micros); }
                            Histogram h = opHist[op.ordinal()];
                            synchronized (h) { h.record(micros); }
                            committed.incrementAndGet();
                            opCommitted[op.ordinal()].incrementAndGet();
                        }
                    } catch (Exception e) {
                        opFailed[op.ordinal()].incrementAndGet();
                        firstFailure.compareAndSet(null, String.valueOf(e));
                        // a failed operation is not a committed operation; it is excluded from
                        // throughput and from the latency histogram, never counted as a success
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
                }
            }
            backend.flushRevocations();
            if (overloaded) {
                System.out.printf("      stopped early: %d operations in flight > %s %d at "
                        + "%d ops/s offered -- saturated%n", inFlight.get(),
                        hitInWarmup ? "warm-up thread cap" : "bound", hitBound, offeredRate);
            }
            // Let the backlog drain; never interrupt it unless it will not. The bound keeps it to
            // about one second of arrivals. Interrupting threads blocked inside the shared
            // BFT-SMaRt client (one per JVM, reused by every later run) left it wedged: every
            // later run, even at 100 ops/s, then committed nothing.
            pool.shutdown();
            if (!pool.awaitTermination(120, TimeUnit.SECONDS)) {
                // A saturated level leaves a backlog; it must not bleed into the next run.
                System.out.println("      WARNING: backlog did not drain in 120 s; interrupting it "
                        + "-- later runs in this JVM may be affected");
                pool.shutdownNow();
            }

            // The fixed arrival window. Dividing by wall time up to the last completion charges
            // the post-window drain — Indy's serial revocation chain, in-flight writes — against
            // the steady stream, and reports a system that kept the schedule as saturated.
            double windowSeconds = steadyMs / 1000.0;
            long failures = 0;
            for (AtomicLong f : opFailed) failures += f.get();
            if (failures > 0) {
                System.out.printf("      failures: resolve %d, register %d, update %d, revoke %d "
                                + "(first: %s)%n",
                        opFailed[0].get(), opFailed[1].get(), opFailed[2].get(), opFailed[3].get(),
                        firstFailure.get());
            }
            double[] opThroughput = new double[ops];
            for (int t = 0; t < ops; t++) opThroughput[t] = opCommitted[t].get() / windowSeconds;
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
            return new RunOutcome(hist, committed.get() / windowSeconds, opHist, opThroughput,
                    burstHist, drainMs, mix.burst ? BURST_SIZE : 0,
                    burstTxns, burstConfirmed, backend.notes(), failures,
                    backend.contendedUpdates(), overloaded);
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
        for (String pending : BACKEND.rowsNotMeasuredHere(MIXES)) {
            System.out.printf("%-34s | %-18s | %-22s | %s%n",
                    pending, "not measured", "not measured", "run the other backend to fill this");
        }
        System.out.println("-".repeat(94));
        for (Result r : results) {
            StringBuilder ops = new StringBuilder();
            for (OpStats o : r.perOp) {
                if (ops.length() > 0) ops.append("; ");
                ops.append(String.format("%s %.0f ops/s, p50/p95/p99 %.0f/%.0f/%.0f us",
                        o.op().label(), o.throughput(), o.p50(), o.p95(), o.p99()));
            }
            System.out.printf("  %s per-operation: %s%n", r.name,
                    ops.length() > 0 ? ops : String.format("resolve %.0f, register/update %.0f, "
                            + "revoke %.0f ops/s", r.resolveTput, r.writeTput, r.revokeTput));
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
        for (String pending : BACKEND.rowsNotMeasuredHere(MIXES)) {
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

    /**
     * Machine-readable rows for deploy/vps/make-table.py: one line per measured configuration.
     * Columns: name, throughput, ci95, p50_us, p95_us, p99_us, runs, warmup_ms, steady_ms, n,
     * burst_drain_ms, burst_p99_us, burst_size, burst_drain_ci_ms, burst_p50_us, burst_p95_us,
     * burst_txns, burst_txns_confirmed (-1 when the backend cannot count them), then &lt;op&gt;_tput / &lt;op&gt;_p50_us / &lt;op&gt;_p95_us /
     * &lt;op&gt;_p99_us for resolve, register, update, revoke (empty if the op never committed),
     * then notes, which stays last.
     */
    static void writeRowsTsv(List<Result> results, int n) throws Exception {
        StringBuilder sb = new StringBuilder(
                "name\tthroughput\tci95\tp50_us\tp95_us\tp99_us\truns\twarmup_ms\tsteady_ms\tn"
                + "\tburst_drain_ms\tburst_p99_us\tburst_size\tburst_drain_ci_ms\tburst_p50_us"
                + "\tburst_p95_us\tburst_txns\tburst_txns_confirmed");
        for (OpType t : OpType.values()) {
            String k = t.label();
            sb.append('\t').append(k).append("_tput\t").append(k).append("_p50_us\t")
              .append(k).append("_p95_us\t").append(k).append("_p99_us");
        }
        sb.append("\tnotes\n");
        for (Result r : results) {
            sb.append(String.format(java.util.Locale.ROOT,
                    "%s\t%.1f\t%.1f\t%.0f\t%.0f\t%.0f\t%d\t%d\t%d\t%d\t%.0f\t%.0f\t%d\t%.1f\t%.0f\t%.0f"
                    + "\t%.1f\t%.1f",
                    r.name, r.throughput, r.throughputCi, r.p50, r.p95, r.p99, RUNS, WARMUP_MS,
                    STEADY_MS, n, r.burstDrainMs, r.burstP99Us, r.burstSize, r.burstDrainCi,
                    r.burstP50Us, r.burstP95Us, r.burstTxns, r.burstTxnsConfirmed));
            for (OpType t : OpType.values()) {
                OpStats o = r.op(t);
                sb.append(o == null ? "\t\t\t\t" : String.format(java.util.Locale.ROOT,
                        "\t%.1f\t%.0f\t%.0f\t%.0f", o.throughput(), o.p50(), o.p95(), o.p99()));
            }
            sb.append('\t').append(r.notes.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("rows.tsv"), sb.toString());
        System.out.println("wrote rows.tsv");
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
          .append(String.format(" ± %.0f", r.burstDrainCi))
          .append(" ms (").append(String.format("%.0f", r.burstDrainMs > 0
                ? r.burstSize / (r.burstDrainMs / 1000.0) : 0))
          .append(" revokes/s), burst p99 ").append(String.format("%.0f", r.burstP99Us))
          .append(String.format(" \u00b5s (p50 %.0f \u00b5s, p95 %.0f \u00b5s)", r.burstP50Us, r.burstP95Us))
          .append(". Burst arrivals share one injection instant, so they are\n")
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
        sb.append("| Configuration | resolve | register | update | revoke |\n|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append("| ").append(r.name);
            for (OpType t : OpType.values()) {
                OpStats o = r.op(t);
                sb.append(" | ").append(o == null ? "0" : String.format("%.0f", o.throughput()));
            }
            sb.append(" |\n");
        }
        // Each operation's own merged histogram: the latency a register or update has inside a
        // mix, and (for the single-operation mixes) the same numbers as the row above.
        sb.append("\n## Per-operation latency (p50 / p95 / p99, µs, merged over all runs)\n\n");
        sb.append("| Configuration | resolve | register | update | revoke |\n|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append("| ").append(r.name);
            for (OpType t : OpType.values()) {
                OpStats o = r.op(t);
                sb.append(" | ").append(o == null ? "—" : String.format("%.0f / %.0f / %.0f (n=%d)",
                        o.p50(), o.p95(), o.p99(), o.count()));
            }
            sb.append(" |\n");
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
