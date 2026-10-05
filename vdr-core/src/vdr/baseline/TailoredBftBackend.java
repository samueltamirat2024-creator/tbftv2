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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * Per-backend DID namespace. Every backend (one per sweep level and per run) generates a fresh
     * issuer key, so it must also write fresh DIDs: against long-lived BFT-SMaRt replicas the old
     * code re-registered the same names, every register after the first run was DENIED
     * (P1_DID_EXISTS) and every update and revoke signed with the new key was DENIED
     * (P2/P4_UNAUTHORISED_KEY) -- and all of them were counted as committed writes. A fresh
     * namespace also gives every run a fresh revocation registry, as Indy's backend does.
     */
    private static final java.util.concurrent.atomic.AtomicLong RUN_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private final String namespace = Long.toString(System.currentTimeMillis(), 36) + "-"
            + RUN_SEQ.incrementAndGet();

    /** Current version of each populated DID (P3 needs it: updates are optimistic-concurrency). */
    private final ConcurrentHashMap<String, long[]> versions = new ConcurrentHashMap<>();
    /** One in-flight update per DID: a controller does not race its own key rotation. */
    private final ConcurrentHashMap<String, ReentrantLock> didLocks = new ConcurrentHashMap<>();

    /** Distinct client ids across concurrently live backends: BFT-SMaRt keys its session by id. */
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_IDS =
            new java.util.concurrent.atomic.AtomicInteger(Integer.getInteger("vdr.clientIdBase",
                    // Distinct per JVM by default. BFT-SMaRt replicas keep per-client-id session and
                    // sequence state; a new process that reuses the previous process's ids against
                    // the same long-lived replicas starts its sequence numbers at 0 again, and its
                    // requests go unanswered. A clock-derived base keeps successive invocations apart.
                    10_000 + (int) ((System.currentTimeMillis() / 1000L) % 1_000_000L) * 40));

    /**
     * Each backend opens a POOL of BFT-SMaRt proxies with consecutive ids (base, base+1, ...,
     * base+poolSize-1). Advancing the base by 1 per backend made consecutive backends -- one per
     * sweep level and per run -- reuse all but one of the previous backend's ids against the same
     * long-lived replicas, which still hold per-client session and sequence state for them. The
     * stride keeps every backend's id range disjoint from every earlier one in this JVM.
     */
    private static final int CLIENT_ID_STRIDE =
            Math.max(1000, Integer.getInteger("vdr.bftsmart.proxies", 32) + 1);

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
                + "maxBatchDelay=%d ms, %s, client Ed25519 %s, Tier-0 fallbacks %d",
                cluster.n(), cluster.f(), batchSize, maxBatchDelayMillis, cluster.describe(),
                vdr.crypto.Crypto.ed25519Provider(), issuer.tier0Fallbacks.sum());
    }

    @Override public List<String> populate(int count) {
        List<String> dids = new ArrayList<>(count);
        List<CompletableFuture<List<vdr.ops.Reply>>> population = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String did = "did:vdr:bench-" + namespace + "-" + i;
            dids.add(did);
            population.add(cluster.invokeOrdered(issuer.signFor(
                    Op.register("bench-issuer", did, document(i), issuer.publicKey(), i))));
        }
        for (int i = 0; i < count; i++) {
            List<vdr.ops.Reply> replies = population.get(i).join();
            if (!committedOk(replies)) {
                throw new IllegalStateException("population register of " + dids.get(i)
                        + " was not committed by f+1 replicas: " + summarise(replies));
            }
            versions.put(dids.get(i), new long[] {1L});
        }
        // Checkpoint before warm-up so Tier-0 reads have a root to prove against from the first
        // measured operation. Indy's equivalent is a fully caught-up pool (baseline plan §9).
        cluster.checkpointEverywhere();
        return dids;
    }

    @Override public void register(String did, byte[] document) {
        VdrClient.Outcome o = issuer.register(did, document);
        if (!o.ok()) {
            throw new IllegalStateException("register denied: " + o.errorCode());
        }
        versions.put(did, new long[] {o.version()});
    }

    /**
     * Key-rotation update against the DID's CURRENT version. The harness passes a nominal
     * expectedVersion; P3 (optimistic concurrency) would deny every update after a DID's first,
     * and a denial must never be counted as a committed write. The backend therefore tracks the
     * version it last committed and serialises updates per DID. A denial throws, so the harness
     * books it as a failure rather than throughput.
     */
    @Override public void update(String did, long expectedVersion, byte[] document) {
        ReentrantLock lock = didLocks.computeIfAbsent(did, k -> new ReentrantLock());
        lock.lock();
        try {
            long[] v = versions.computeIfAbsent(did, k -> new long[] {expectedVersion});
            VdrClient.Outcome o = issuer.update(did, v[0], document, issuer.publicKey());
            if (!o.ok()) {
                throw new IllegalStateException("update denied: " + o.errorCode());
            }
            v[0] = o.version();
        } finally {
            lock.unlock();
        }
    }

    @Override public void resolve(String did, int replicaHint) {
        // A read of nothing is not a resolve (Indy's backend applies the same rule to GET_NYM
        // "data":null). resolveTier0 returns null on a denied/unknown read.
        if (issuer.resolveTier0(did, replicaHint) == null) {
            throw new IllegalStateException("Tier-0 resolve returned no document for " + did);
        }
    }

    @Override public String registryIdFor(String did) {
        return Records.registryIdFor(did);
    }

    @Override public CompletableFuture<Void> submitRevoke(String registryId, String handle) {
        // Committed means f+1 matching OK replies, the same rule as every other write. The old
        // adapter completed on any reply list, so a batch every replica DENIED counted as revoked.
        return gateway.submitRevoke("bench-issuer", registryId, handle, null, issuer::signFor)
                .thenApply(replies -> {
                    if (!committedOk(replies)) {
                        throw new IllegalStateException("revocation batch not committed: "
                                + summarise(replies));
                    }
                    return null;
                });
    }

    /** f+1 replies from distinct replicas that agree on the digest AND say ok. */
    private boolean committedOk(List<vdr.ops.Reply> replies) {
        if (replies == null) return false;
        java.util.Map<String, java.util.Set<Integer>> byDigest = new java.util.HashMap<>();
        for (vdr.ops.Reply r : replies) {
            if (r == null || !r.ok) continue;
            java.util.Set<Integer> s = byDigest.computeIfAbsent(r.digest(), k -> new java.util.HashSet<>());
            s.add(r.replicaId);
            if (s.size() >= cluster.f() + 1) return true;
        }
        return false;
    }

    private static String summarise(List<vdr.ops.Reply> replies) {
        if (replies == null || replies.isEmpty()) return "no replies";
        StringBuilder sb = new StringBuilder();
        for (vdr.ops.Reply r : replies) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.replicaId).append(':').append(r.ok ? "ok" : r.errorCode);
        }
        return sb.toString();
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
