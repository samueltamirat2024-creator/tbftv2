package vdr.replication;

import vdr.ops.Op;
import vdr.ops.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * The n = 3f+1 replica group and its ordering layer.
 *
 * WHAT THIS IS: a deterministic, in-process stand-in for BFT-SMaRt's total order
 * multicast. It reproduces the two properties the layers above depend on --
 *   (i)  every correct replica receives the same operations in the same order, and
 *   (ii) a consensus instance orders a BATCH of operations, not a single one
 *        (DepSpace batch agreement, plan section 4)
 * -- and charges a configurable per-instance cost so the read fast path and the
 * revoke-batching mechanism can be measured against each other.
 *
 * WHAT THIS IS NOT: a Byzantine consensus protocol. It has no view change, no
 * reconfiguration, and no message-level fault model. Replacing it with the real engine
 * is a matter of implementing {@link Replication} -- see vdr-bftsmart/, which holds the
 * BFT-SMaRt adapter and builds separately because it needs the bft-smart jar.
 *
 * Absolute latency and throughput numbers produced on top of this class are HARNESS
 * VALIDATION ONLY. The evaluation matrix rows of plan section 12 are filled from runs
 * against the real engine on the real cluster.
 */
public final class SimulatedCluster implements Replication {

    public final int n;
    public final int f;
    private final List<ServiceReplica> replicas = new ArrayList<>();
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final Thread orderingThread;
    private volatile boolean running = true;

    /**
     * One execution thread per replica, so each of the n replicas runs on its own vCPU
     * (the same "one node, one vCPU" shape the Indy pool gets in deploy/vps). Every replica
     * still executes the ordered stream strictly in sequence on its own single thread, so the
     * determinism argument of plan section 7 is unchanged. -Dvdr.replicaThreads=false restores
     * the original serial loop (all replicas executed on the ordering thread).
     */
    private static final boolean REPLICA_THREADS =
            Boolean.parseBoolean(System.getProperty("vdr.replicaThreads", "true"));
    private final ExecutorService[] replicaExecutors;

    private final long consensusInstanceNanos;
    private final long networkRttNanos;
    private final int maxInstanceBatch;
    private final long maxBatchDelayNanos;

    private long seq = 0;
    private long consensusInstances = 0;

    public SimulatedCluster(int n, int maxBatchSize, long checkpointInterval,
                   long consensusInstanceMicros, long networkRttMicros,
                   int maxInstanceBatch, long maxBatchDelayMicros) {
        if ((n - 1) % 3 != 0) throw new IllegalArgumentException("n must be 3f+1, got " + n);
        this.n = n;
        this.f = (n - 1) / 3;
        this.consensusInstanceNanos = consensusInstanceMicros * 1_000L;
        this.networkRttNanos = networkRttMicros * 1_000L;
        this.maxInstanceBatch = maxInstanceBatch;
        this.maxBatchDelayNanos = maxBatchDelayMicros * 1_000L;
        for (int i = 0; i < n; i++) replicas.add(new ServiceReplica(i, maxBatchSize, checkpointInterval));
        if (REPLICA_THREADS) {
            replicaExecutors = new ExecutorService[n];
            for (int i = 0; i < n; i++) {
                final int id = i;
                replicaExecutors[i] = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "replica-" + id);
                    t.setDaemon(true);
                    return t;
                });
            }
        } else {
            replicaExecutors = null;
        }
        this.orderingThread = new Thread(this::orderingLoop, "total-order-multicast");
        this.orderingThread.setDaemon(true);
        this.orderingThread.start();
    }

    public static SimulatedCluster standard(int n) {
        // DepSpace anchor points (Emulab, 2008): ordered operations ~3.5 ms dominated by
        // total order multicast; read-only optimised operations under 2 ms. We charge
        // 3000 us per consensus instance and 300 us for a single-replica round trip.
        return new SimulatedCluster(n, 4096, 2000, 3000, 300, 64, 1000);
    }

    @Override public int n() { return n; }
    @Override public int f() { return f; }

    public List<ServiceReplica> replicas() { return replicas; }
    public ServiceReplica replica(int i) { return replicas.get(i); }
    public long consensusInstances() { return consensusInstances; }

    /** Fails loudly if a benchmark image was built with fault injection enabled (plan section 4). */
    @Override public void assertNoByzantineInBenchmark() {
        for (ServiceReplica r : replicas) {
            if (r.behaviour() != ServiceReplica.Behaviour.HONEST) {
                throw new IllegalStateException("replica " + r.id() + " is Byzantine; "
                        + "the fault-injection adapter must never ship in a benchmark image");
            }
        }
    }

    // ------------------------------------------------------------- ordered path

    @Override public CompletableFuture<List<Reply>> invokeOrdered(Op op) {
        Pending p = new Pending(op, new CompletableFuture<>());
        queue.add(p);
        return p.future;
    }

    /** Blocking convenience wrapper used by tests. */
    @Override public List<Reply> invokeOrderedSync(Op op) {
        try {
            return invokeOrdered(op).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void orderingLoop() {
        List<Pending> batch = new ArrayList<>(maxInstanceBatch);
        while (running) {
            try {
                Pending first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                long deadline = System.nanoTime() + maxBatchDelayNanos;
                while (batch.size() < maxInstanceBatch) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    Pending next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    batch.add(next);
                }
                // one consensus instance orders the whole batch (DepSpace batch agreement)
                park(consensusInstanceNanos);
                consensusInstances++;
                if (replicaExecutors != null) {
                    executeInParallel(batch);
                } else {
                    for (Pending p : batch) {
                        seq++;
                        List<Reply> replies = new ArrayList<>(n);
                        for (ServiceReplica r : replicas) {
                            replies.add(r.executeOrdered(p.op, seq));
                        }
                        p.future.complete(replies);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                for (Pending p : batch) p.future.completeExceptionally(t);
            }
        }
    }

    /**
     * Hands the decided batch to every replica at once. Each replica runs the whole batch
     * in order on its own thread; the replies are collected per operation in replica-id
     * order, exactly as the serial loop produced them.
     */
    private void executeInParallel(List<Pending> batch) throws InterruptedException {
        final int size = batch.size();
        final long firstSeq = seq + 1;
        seq += size;
        final Reply[][] out = new Reply[size][n];
        List<Future<?>> done = new ArrayList<>(n);
        for (int r = 0; r < n; r++) {
            final int ri = r;
            final ServiceReplica replica = replicas.get(r);
            done.add(replicaExecutors[r].submit(() -> {
                for (int j = 0; j < size; j++) {
                    out[j][ri] = replica.executeOrdered(batch.get(j).op, firstSeq + j);
                }
            }));
        }
        Throwable failure = null;
        for (Future<?> fut : done) {
            try {
                fut.get();
            } catch (ExecutionException e) {
                if (failure == null) failure = e.getCause();
            }
        }
        if (failure != null) {
            for (Pending p : batch) p.future.completeExceptionally(failure);
            return;
        }
        for (int j = 0; j < size; j++) {
            batch.get(j).future.complete(java.util.Arrays.asList(out[j]));
        }
    }

    // ---------------------------------------------------------- unordered path

    /** Tier 0: one round trip to the nearest replica. */
    @Override public Reply readTier0(String did, int preferredReplica) {
        park(networkRttNanos);
        return replicas.get(preferredReplica % n).resolve(did);
    }

    /** Tier 1: f+1 replicas, accepted on majority agreement. */
    @Override public List<Reply> readTier1(String did, int startReplica) {
        park(networkRttNanos);
        List<Reply> out = new ArrayList<>(f + 1);
        for (int i = 0; i <= f; i++) out.add(replicas.get((startReplica + i) % n).resolve(did));
        return out;
    }

    /** Tier 1 revocation-status read: the freshness-critical path. */
    @Override public List<Reply> revocationTier1(String registryId, String handle, int startReplica) {
        park(networkRttNanos);
        List<Reply> out = new ArrayList<>(f + 1);
        for (int i = 0; i <= f; i++) {
            out.add(replicas.get((startReplica + i) % n).revocationStatus(registryId, handle));
        }
        return out;
    }

    /** Signed-checkpoint gossip: a client trusts a root seen from f+1 distinct replicas. */
    @Override public byte[] trustedRoot() {
        java.util.TreeMap<String, Integer> tally = new java.util.TreeMap<>();
        java.util.TreeMap<String, byte[]> byHex = new java.util.TreeMap<>();
        for (ServiceReplica r : replicas) {
            byte[] root = r.announcedRoot();
            String hex = vdr.crypto.Crypto.hex(root);
            tally.merge(hex, 1, Integer::sum);
            byHex.put(hex, root);
        }
        for (var e : tally.entrySet()) {
            if (e.getValue() >= f + 1) return byHex.get(e.getKey());
        }
        return null;
    }

    /** Highest epoch announced by at least f+1 replicas: the Tier-1 freshness bar. */
    @Override public long trustedEpoch() {
        long[] epochs = new long[n];
        for (int i = 0; i < n; i++) epochs[i] = replicas.get(i).announcedEpoch();
        java.util.Arrays.sort(epochs);
        // the (f+1)-th largest is backed by at least f+1 replicas, hence by an honest one
        return epochs[n - (f + 1)];
    }

    /**
     * Simulated delay. parkNanos overshoots below a millisecond on Linux, so the absolute
     * values here run above the configured cost; the overshoot applies equally to the
     * ordered and unordered paths, so the RATIO the M4 gate tests remains meaningful.
     */
    private static void park(long nanos) {
        if (nanos <= 0) return;
        long deadline = System.nanoTime() + nanos;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            // No spin-waiting: the reference harness may run on a single core, where
            // busy-waiting threads starve the very system under measurement.
            LockSupport.parkNanos(remaining);
        }
    }

    @Override public boolean simulated() { return true; }

    @Override public void checkpointEverywhere() {
        for (ServiceReplica r : replicas) r.store().forceCheckpoint();
    }

    @Override public String describe() {
        return String.format("SIMULATED ordering (n=%d, f=%d, %d us/consensus instance, %d us RTT)",
                n, f, consensusInstanceNanos / 1000, networkRttNanos / 1000);
    }

    @Override public void close() {
        running = false;
        orderingThread.interrupt();
        if (replicaExecutors != null) {
            for (ExecutorService e : replicaExecutors) e.shutdownNow();
        }
    }

    private record Pending(Op op, CompletableFuture<List<Reply>> future) {}
}
