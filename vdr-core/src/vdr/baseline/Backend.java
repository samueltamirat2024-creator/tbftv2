package vdr.baseline;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The seam that lets ONE load generator drive both systems (baseline plan §8).
 *
 * <p>The alternative — our harness for the Tailored BFT VDR and an Indy-native tool such as
 * {@code perf_processes.py} for the baseline — would mean two definitions of throughput, two
 * arrival models and two treatments of coordinated omission. The rows would then not be
 * comparable, and nothing downstream could repair that. So the generator, the warm-up rule, the
 * merged-histogram percentiles, the burst accounting and the operating-point admission rule are
 * shared, and only what happens behind these five methods differs.
 *
 * <p>Implementations must honour the contract in {@code bench/METRICS.md}:
 *
 * <ul>
 *   <li>{@link #register} and {@link #resolve} return only when the client has a reply it can act
 *       on — for the Tailored BFT VDR that is the f+1-th matching reply, for Indy an accepted
 *       response whose state proof has been verified. Read-path proof verification is inside the
 *       measured latency on both.
 *   <li>{@link #submitRevoke} is non-blocking. A thread per in-flight revocation would measure
 *       generator starvation rather than the system under test.
 *   <li>Population happens before warm-up and is never measured.
 * </ul>
 */
public interface Backend extends AutoCloseable {

    /** Appears in the evaluation matrix row label. */
    String name();

    /** Notes appended to this backend's row (configuration that affects the number). */
    String notes();

    /**
     * Population phase, before warm-up: create the DID pool the mixes read and update.
     *
     * @return the DIDs created, in order
     */
    List<String> populate(int count) throws Exception;

    /** Ordered write. Returns when committed by the definition above. */
    void register(String did, byte[] document) throws Exception;

    /** Ordered write: key rotation, the realistic SSI update. */
    void update(String did, long expectedVersion, byte[] document) throws Exception;

    /**
     * Tier-0 read: one round trip, proof verified client-side, verification cost included.
     *
     * @param replicaHint which replica/node to prefer, so read load spreads the same way on both
     */
    void resolve(String did, int replicaHint) throws Exception;

    /** The revocation registry a DID's credentials belong to. */
    String registryIdFor(String did);

    /**
     * Non-blocking revocation submission. Batching is the backend's business: the Tailored BFT
     * gateway aggregates into ordered batches, Indy accumulates indices into one REVOC_REG_ENTRY.
     * Both knobs are swept, and the swept optimum is reported per baseline plan §5 — comparing a
     * batched system against an unbatched one would manufacture a speed-up.
     */
    CompletableFuture<Void> submitRevoke(String registryId, String credentialHandle);

    /** Flush any pending revocation batch. Called at the end of a run, never mid-window. */
    void flushRevocations();

    @Override
    void close() throws Exception;
}
