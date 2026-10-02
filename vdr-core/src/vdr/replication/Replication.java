package vdr.replication;

import vdr.ops.Op;
import vdr.ops.Reply;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The L1 replication seam (plan §4, BFT-SMaRt implementation plan WP1).
 *
 * <p>Everything above L1 -- the client, the gateway, the benchmark backend -- codes against this
 * interface and nothing else. Two implementations exist:
 *
 * <ul>
 *   <li>{@link SimulatedCluster}: an in-process, deterministic stand-in with two calibrated
 *       latency constants. Fast, dependency-free, and the only one the correctness gates can
 *       drive Byzantine behaviour through. Its numbers are harness validation, never results.</li>
 *   <li>{@code vdr.replication.bftsmart.BftSmartCluster}: real state-machine replication over
 *       BFT-SMaRt, in the separate {@code vdr-bftsmart} module so that vdr-core keeps building
 *       with no external dependency.</li>
 * </ul>
 *
 * <p>Selected with {@code -Dvdr.replication=simulated|bftsmart}; see {@link ReplicationFactory}.
 *
 * <p><b>The client does its own verification.</b> {@link #invokeOrdered} returns the individual
 * replies, not one "agreed" answer, because {@code VdrClient} performs the f+1 matching and the
 * Merkle proof checks itself. An implementation that collapses the replies before the client sees
 * them removes the check that makes a Byzantine replica detectable.
 */
public interface Replication extends AutoCloseable {

    /** Number of replicas, n = 3f+1. */
    int n();

    /** Tolerated faulty replicas, f = (n-1)/3. */
    int f();

    /** Total-order multicast: the reply list carries one entry per replica that answered. */
    CompletableFuture<List<Reply>> invokeOrdered(Op op);

    /** Blocking convenience wrapper. */
    default List<Reply> invokeOrderedSync(Op op) {
        try {
            return invokeOrdered(op).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Tier 0: one round trip to exactly ONE replica. Sending to more measures a different path. */
    Reply readTier0(String did, int preferredReplica);

    /** Tier 1: f+1 replicas, accepted on majority agreement. */
    List<Reply> readTier1(String did, int startReplica);

    /** Tier 1 revocation-status read: the freshness-critical path. */
    List<Reply> revocationTier1(String registryId, String handle, int startReplica);

    /** Signed-checkpoint gossip: a root is trusted once f+1 distinct replicas announce it. */
    byte[] trustedRoot();

    /** Highest epoch announced by at least f+1 replicas: the Tier-1 freshness bar. */
    long trustedEpoch();

    /**
     * True when the two synthetic latency constants are in play, so the harness can decide whether
     * RESULTS.md carries the provenance warning. Printing that warning on a real run would be as
     * misleading as omitting it on a simulated one.
     */
    boolean simulated();

    /**
     * Cuts a Merkle checkpoint on every replica, so Tier-0 reads have a root to prove against from
     * the first measured operation. Called after population, never inside a measurement window.
     */
    void checkpointEverywhere();

    /** Fails loudly if a benchmark image was built with fault injection enabled (plan §4). */
    default void assertNoByzantineInBenchmark() { }

    /** One line naming the engine and its configuration, for the results notes. */
    String describe();

    @Override void close();
}
