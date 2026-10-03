package vdr.baseline;

import vdr.client.VdrClient;
import vdr.crypto.Crypto;
import vdr.model.Records;
import vdr.ops.Op;
import vdr.replication.Gateway;
import vdr.replication.Replication;
import vdr.replication.ReplicationFactory;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The Tailored BFT VDR behind the common {@link Backend} seam.
 *
 * <p>This is a thin adapter over what the harness already did directly, so the two Tailored-BFT
 * rows are produced by exactly the same generator that produces the two Indy rows.
 */
public final class TailoredBftBackend implements Backend {

    private final Replication cluster;
    private final VdrClient issuer;
    private final Gateway gateway;
    private final int batchSize;
    private final long maxBatchDelayMillis;

    /** Distinct client ids across concurrently live backends: BFT-SMaRt keys its session by id. */
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_IDS =
            new java.util.concurrent.atomic.AtomicInteger(
                    Integer.getInteger("vdr.clientIdBase", 1001));

    /**
     * Each backend opens a POOL of BFT-SMaRt proxies with consecutive ids (base, base+1, ...,
     * base+poolSize-1). Advancing the base by 1 per backend made consecutive backends -- one per
     * sweep level and per run -- reuse all but one of the previous backend's ids against the same
     * long-lived replicas, which still hold per-client session and sequence state for them. The
     * stride keeps every backend's id range disjoint from every earlier one in this JVM.
     */
    private static final int CLIENT_ID_STRIDE =
            Math.max(1000, Integer.getInteger("vdr.bftsmart.proxies", 16) + 1);

    /**
     * ONE BFT-SMaRt client (one pool of proxies) per JVM, shared by every backend this harness
     * creates -- one per sweep level and per run.
     *
     * <p>Opening a fresh proxy pool per run was measured to break every run after the first: thread
     * dumps of the second run (2026-10-03) showed all 64 proxies waiting the full 30 s invoke timeout
     * for replies to ordered requests while the leader replica sat idle, and every load-generator
     * thread blocked the same way on Tier-0 reads. The first pool in a JVM was always served; later
     * pools against the same long-lived replicas were not. The real-engine gates never hit this
     * because they use one client per JVM. Sharing the pool removes per-run session churn from the
     * measurement entirely; connection setup was never part of what a run measures.
     *
     * <p>The simulator keeps a fresh in-process cluster per backend, as before: it has no sessions.
     */
    private static Replication sharedReal;

    private static synchronized Replication sharedRealCluster(int n) {
        if (sharedReal == null) {
            sharedReal = ReplicationFactory.create(n, CLIENT_IDS.getAndAdd(CLIENT_ID_STRIDE));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { sharedReal.close(); } catch (RuntimeException ignored) { }
            }, "bftsmart-client-close"));
        } else if (sharedReal.n() != n) {
            throw new IllegalStateException("shared BFT-SMaRt client is for n=" + sharedReal.n()
                    + ", requested n=" + n);
        }
        return sharedReal;
    }

    public TailoredBftBackend(int n, int batchSize, long maxBatchDelayMillis) {
        // -Dvdr.replication picks the engine: the in-process simulator, or BFT-SMaRt on a real
        // cluster. Nothing else in this class changes between them (plan §4).
        this.cluster = ReplicationFactory.isSimulated()
                ? ReplicationFactory.create(n, CLIENT_IDS.getAndAdd(CLIENT_ID_STRIDE))
                : sharedRealCluster(n);
        // Fault injection never ships in a benchmark image (plan §4).
        this.cluster.assertNoByzantineInBenchmark();
        KeyPair kp = Crypto.generateKeyPair();
        this.issuer = new VdrClient(cluster, "bench-issuer", kp);
        this.gateway = new Gateway(cluster, batchSize, maxBatchDelayMillis);
        this.batchSize = batchSize;
        this.maxBatchDelayMillis = maxBatchDelayMillis;
    }

    @Override public String name() {
        return "Tailored BFT VDR";
    }

    @Override public String notes() {
        return String.format("n=%d (f=%d), confidentiality off, fast path Tier 0, batchSize=%d, "
                + "maxBatchDelay=%d ms, %s",
                cluster.n(), cluster.f(), batchSize, maxBatchDelayMillis, cluster.describe());
    }

    @Override public List<String> populate(int count) {
        List<String> dids = new ArrayList<>(count);
        List<CompletableFuture<?>> population = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String did = "did:vdr:bench" + i;
            dids.add(did);
            population.add(cluster.invokeOrdered(issuer.signFor(
                    Op.register("bench-issuer", did, document(i), issuer.publicKey(), i))));
        }
        for (CompletableFuture<?> f : population) {
            f.join();
        }
        // Checkpoint before warm-up so Tier-0 reads have a root to prove against from the first
        // measured operation. Indy's equivalent is a fully caught-up pool (baseline plan §9).
        cluster.checkpointEverywhere();
        return dids;
    }

    @Override public void register(String did, byte[] document) {
        issuer.register(did, document);
    }

    @Override public void update(String did, long expectedVersion, byte[] document) {
        issuer.update(did, expectedVersion, document, issuer.publicKey());
    }

    @Override public void resolve(String did, int replicaHint) {
        issuer.resolveTier0(did, replicaHint);
    }

    @Override public String registryIdFor(String did) {
        return Records.registryIdFor(did);
    }

    @Override public CompletableFuture<Void> submitRevoke(String registryId, String handle) {
        return gateway.submitRevoke("bench-issuer", registryId, handle, null, issuer::signFor)
                .thenApply(r -> null);
    }

    @Override public void flushRevocations() {
        gateway.flush();
    }

    @Override public long revocationTransactionsSubmitted() {
        return gateway.batchesSubmitted();
    }

    @Override public long revocationTransactionsConfirmed() {
        return gateway.batchesCommitted();
    }

    @Override public void close() {
        gateway.close();
        if (cluster != sharedReal) {
            cluster.close();          // simulator: per-backend; the shared real client lives for the JVM
        }
    }

    public Replication cluster() {
        return cluster;
    }

    private static byte[] document(int i) {
        return ("{\"@context\":\"https://www.w3.org/ns/did/v1\",\"id\":\"did:vdr:bench" + i
                + "\",\"verificationMethod\":[{\"type\":\"Ed25519VerificationKey2020\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
