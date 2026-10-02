package vdr.baseline.indy;

import vdr.baseline.Backend;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hyperledger Indy behind the common {@link Backend} seam (baseline plan §3, §8).
 *
 * <p>Operation mapping, fixed by baseline plan §3 so that one workload operation costs one
 * comparable unit of work on both systems:
 *
 * <table>
 *   <tr><td>register</td><td>NYM (create), written by the trustee/endorser</td></tr>
 *   <tr><td>resolve</td><td>GET_NYM — single node, state proof verified client-side</td></tr>
 *   <tr><td>update</td><td>NYM (verkey rotation), signed by the DID's own current key</td></tr>
 *   <tr><td>revoke</td><td>REVOC_REG_ENTRY carrying an accumulator value and a revoked-index delta</td></tr>
 * </table>
 *
 * <p>SCHEMA, CRED_DEF, REVOC_REG_DEF and the initial REVOC_REG_ENTRY are setup, not workload:
 * pool/setup_registry.py writes them before the measurement window.
 *
 * <p><b>2026-09-20 fixes (first contact with a live pool).</b>
 * <ul>
 *   <li>DIDs and verkeys are real Indy identities: Ed25519 key pair from a seed, DID =
 *       base58(pub[0..16]), verkey = base58(pub). The old generator produced random base58-looking
 *       strings that did not decode to 16/32 bytes.</li>
 *   <li>The submitter key is derived from INDY_TRUSTEE_SEED and checked against indy.did. It was
 *       a random key, so every write would have failed signature verification.</li>
 *   <li>update is signed by the DID's current key: Indy only lets the owner rotate a verkey.</li>
 *   <li>Requests are never freed after pool_submit_request: the library takes ownership.</li>
 *   <li>Revocation entries form one serial chain starting at INDY_REVOC_ACCUM, and the
 *       accumulator advances only on success. Concurrent entries could reach the ledger out of
 *       order and break the prevAccum chain for every later entry. This serialisation is a real
 *       property of Indy's per-registry accumulator and should be stated in the paper.</li>
 *   <li>A GET_NYM reply with "data":null is a failure, not a fast read.</li>
 *   <li>Population is idempotent within a process and salted per process, because the pool's
 *       ledger persists across container runs.</li>
 * </ul>
 */
public final class IndyBackend implements Backend {

    private static final String DEFAULT_TRUSTEE_SEED = "000000000000000000000000Trustee1";
    /** Maximum NYM writes in flight during population (setup, not measured). */
    private static final int POPULATE_WINDOW = 100;
    private static final char[] BASE58 =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final java.util.regex.Pattern ACCUM =
            java.util.regex.Pattern.compile("\"accum\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern REVOKED =
            java.util.regex.Pattern.compile("\"revoked\"\\s*:\\s*\\[([^\\]]*)\\]");

    /**
     * FRESH REGISTRY PER RUN (remediation plan §2). A new backend is built for every sweep level
     * and every measurement run, and each takes the next unused revocation registry from the list
     * pool/setup_registry.py --count wrote. Without this the registry's revoked set grows across
     * runs and later runs are slower for reasons that have nothing to do with the system: burst
     * drain rose 1,256 → 1,628 → 2,831 ms over three successive runs on one pool.
     */
    private static final java.nio.file.Path REGISTRY_LIST = java.nio.file.Path.of(
            firstNonEmpty(System.getProperty("indy.registriesFile"),
                    System.getenv("INDY_REGISTRIES_FILE"), "/results/registries.env"));
    private static final java.nio.file.Path REGISTRY_COUNTER = java.nio.file.Path.of(
            firstNonEmpty(System.getProperty("indy.registryCounter"),
                    System.getenv("INDY_REGISTRY_COUNTER"), "/results/registry.counter"));

    /** Revocation indices accumulated into one REVOC_REG_ENTRY. Swept; see baseline plan §5. */
    private final int entriesPerTransaction;

    private final IndyVdr vdr;
    private final long pool;
    private final String submitterDid;
    private final PrivateKey submitterKey;
    private final String revocRegDefId;
    private final int nodeCount;
    /** indy-vdr client configuration applied before pool_create; reported in notes(). */
    private final String poolConfig;
    /** Revoked-set size when this backend opened; compared at close to show ledger growth. */
    private final long revokedAtOpen;

    /** Per-process salt: the ledger outlives the process, so DIDs must not collide across runs. */
    private final String salt = Long.toHexString(System.currentTimeMillis());

    /** Bench-facing DID -> current Indy identity (current key changes on every update). */
    private final ConcurrentHashMap<String, Identity> identities = new ConcurrentHashMap<>();
    /** DIDs already written by populate in this process, in order. */
    private final List<String> population = new ArrayList<>();
    private final AtomicLong keyRotations = new AtomicLong(1);

    private final List<String> pendingRevocations = new ArrayList<>();
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();
    private final AtomicLong revocationIndex = new AtomicLong(1);
    private final AtomicLong entrySeq = new AtomicLong(1);
    /** The ledger's current accumulator for the registry; only the revoke chain touches it. */
    private volatile String previousAccumulator;
    /** Tail of the serial REVOC_REG_ENTRY chain. Never completes exceptionally. */
    private CompletableFuture<Void> revokeChain = CompletableFuture.completedFuture(null);
    /** Chain steps run here, not on indy-vdr's callback threads. */
    private final ExecutorService revokeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "indy-revoke-chain");
        t.setDaemon(true);
        return t;
    });

    /**
     * Time-based flush, the same rule as the Tailored BFT gateway (plan §3.4 V1): a batch is
     * submitted at entriesPerTransaction OR after maxBatchDelayMs, whichever comes first. Without
     * it, sparse revocations never reach the ledger inside a measurement window. Set
     * -Dindy.maxBatchDelayMs to the Tailored gateway's maxBatchDelay for a like-for-like run.
     */
    private final long maxBatchDelayMs = Long.getLong("indy.maxBatchDelayMs", 50L);
    private final ScheduledExecutorService flushTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "indy-revoke-flush");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingFlush;

    /**
     * @param libraryPath path to libindy_vdr
     * @param genesisPath pool transactions genesis file for the frozen pool
     * @param submitterDid an ENDORSER-or-better DID authorised to write NYMs; must be the DID of
     *     INDY_TRUSTEE_SEED (or -Dindy.seed)
     * @param revocRegDefId revocation registry definition written during setup
     * @param entriesPerTransaction revoked indices per REVOC_REG_ENTRY — the batching knob that
     *     must be swept against the Tailored BFT gateway's batchSize
     */
    public IndyBackend(String libraryPath, String genesisPath, String submitterDid,
                       String revocRegDefId, int entriesPerTransaction, int nodeCount)
            throws Exception {
        String seed = firstNonEmpty(System.getProperty("indy.seed"),
                System.getenv("INDY_TRUSTEE_SEED"), DEFAULT_TRUSTEE_SEED);
        byte[] seedBytes = seed.getBytes(StandardCharsets.US_ASCII);
        if (seedBytes.length != 32) {
            throw new IllegalArgumentException("trustee seed must be exactly 32 characters");
        }
        Identity trustee = Identity.fromSeed(null, seedBytes);
        if (!trustee.did.equals(submitterDid)) {
            throw new IllegalStateException("trustee seed derives DID " + trustee.did
                    + " but indy.did is " + submitterDid
                    + "; set INDY_SUBMITTER_DID and INDY_TRUSTEE_SEED consistently");
        }

        // Fallback only. The ledger is asked for the real value once the pool is open: a new
        // backend is built for every sweep level and every run, so an environment value captured
        // by an earlier setup does not track what the registry has since accepted.
        String accumFallback = firstNonEmpty(System.getProperty("indy.revocAccum"),
                System.getenv("INDY_REVOC_ACCUM"), null);

        this.submitterDid = submitterDid;
        this.submitterKey = trustee.privateKey;
        this.revocRegDefId = nextRegistry(revocRegDefId);
        this.entriesPerTransaction = entriesPerTransaction;
        this.nodeCount = nodeCount;

        this.vdr = new IndyVdr(libraryPath);
        // Client connection tuning (baseline plan §9). indy-vdr's defaults (5 requests per
        // connection, 5 s idle) open ~1000 ZeroMQ sockets at 250 ops/s and hit ZeroMQ's
        // 1023-socket cap ("Too many open files"). These are CLIENT settings, not ledger ones;
        // record them in the published baseline configuration.
        int connRequestLimit = Integer.getInteger("indy.connRequestLimit", 100);
        long connActiveTimeout = Long.getLong("indy.connActiveTimeout", 5L);
        Integer requestReadNodes = Integer.getInteger("indy.requestReadNodes");
        StringBuilder cfg = new StringBuilder("{\"conn_request_limit\":").append(connRequestLimit)
                .append(",\"conn_active_timeout\":").append(connActiveTimeout);
        if (requestReadNodes != null) {
            cfg.append(",\"request_read_nodes\":").append(requestReadNodes);
        }
        this.poolConfig = cfg.append('}').toString();
        this.vdr.setConfig(poolConfig);
        this.vdr.setProtocolVersion(2);
        String params = "{\"transactions_path\":\"" + genesisPath.replace("\\", "\\\\") + "\"}";
        this.pool = vdr.poolCreate(params);
        // Refresh before warm-up, never inside a measurement window: a pool still discovering its
        // validators is not the steady state we are claiming to measure (baseline plan §9).
        vdr.poolRefresh(pool).join();

        RegistryState state = null;
        try {
            state = fetchRegistryState();
        } catch (RuntimeException e) {
            System.err.println("could not read the registry's state from the ledger: " + e);
        }
        this.previousAccumulator = (state != null && state.accum != null) ? state.accum : accumFallback;
        this.revokedAtOpen = state != null ? state.revokedCount() : -1;
        if (state != null) {
            // The registry outlives the process: indices already revoked cannot be revoked again.
            revocationIndex.set(state.maxRevokedIndex + 1);
        }
        if (this.previousAccumulator == null) {
            throw new IllegalStateException("no accumulator for " + this.revocRegDefId + ": the "
                    + "ledger returned none and INDY_REVOC_ACCUM is unset. Run "
                    + "pool/setup_registry.py, which writes the registry and its initial "
                    + "REVOC_REG_ENTRY.");
        }
        // Ledger-growth evidence (remediation plan §2 A4). A run that starts on a registry whose
        // revoked set is not empty is not comparable with one that starts clean.
        System.out.printf("      registry %s opened with %d revoked index(es)%n",
                shortId(this.revocRegDefId), Math.max(revokedAtOpen, 0));
        if (revokedAtOpen > 0) {
            System.out.println("      WARNING: this registry has been used before. For reportable "
                    + "runs give every run a fresh registry (setup_registry.py --count N).");
        }
    }

    /**
     * Takes the next unused registry from the list setup wrote, or falls back to the single id
     * passed in when no list exists. The counter file is the only shared state, so sweep levels
     * and runs in one process, and successive container runs, never reuse a registry.
     */
    private static synchronized String nextRegistry(String fallback) {
        try {
            if (!java.nio.file.Files.isReadable(REGISTRY_LIST)) {
                return fallback;
            }
            List<String> ids = new ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(REGISTRY_LIST)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');              // tolerate KEY='value' as well as a bare id
                if (eq >= 0) {
                    s = s.substring(eq + 1).trim();
                }
                if (s.length() > 1 && (s.charAt(0) == '\'' || s.charAt(0) == '"')) {
                    s = s.substring(1, s.length() - 1);
                }
                if (!s.isEmpty()) {
                    ids.add(s);
                }
            }
            if (ids.isEmpty()) {
                return fallback;
            }
            long k = 0;
            if (java.nio.file.Files.isReadable(REGISTRY_COUNTER)) {
                String v = java.nio.file.Files.readString(REGISTRY_COUNTER).trim();
                if (!v.isEmpty()) {
                    k = Long.parseLong(v);
                }
            }
            if (k >= ids.size()) {
                throw new IllegalStateException("the registry pool is exhausted: " + ids.size()
                        + " registries in " + REGISTRY_LIST + ", this is request " + (k + 1)
                        + ". Reuse would make later runs slower than earlier ones. Run "
                        + "deploy/vps/reset-pool.sh with a larger --count.");
            }
            java.nio.file.Files.writeString(REGISTRY_COUNTER, Long.toString(k + 1));
            return ids.get((int) k);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not take the next revocation registry", e);
        }
    }

    /** Last 24 characters of a registry id: enough to tell two apart in a log line. */
    private static String shortId(String id) {
        return id.length() <= 24 ? id : "…" + id.substring(id.length() - 24);
    }

    /** The registry's current accumulator and highest revoked index, as the ledger holds them. */
    private RegistryState fetchRegistryState() {
        long toTs = System.currentTimeMillis() / 1000L + 300; // a little ahead of the ledger clock
        long req = vdr.buildGetRevocRegDeltaRequest(submitterDid, revocRegDefId, -1L, toTs);
        String reply = vdr.poolSubmitRequest(pool, req).join();
        if (reply == null) {
            return null;
        }
        String flat = reply.replace("\\\"", "\"");
        // The delta's accum_to carries the current value; with from = -1 there is no accum_from.
        java.util.regex.Matcher m = ACCUM.matcher(flat);
        String accum = null;
        while (m.find()) {
            accum = m.group(1);
        }
        long maxRevoked = 0;
        long revokedCount = 0;
        java.util.regex.Matcher r = REVOKED.matcher(flat);
        while (r.find()) {
            for (String part : r.group(1).split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    try {
                        maxRevoked = Math.max(maxRevoked, Long.parseLong(t));
                        revokedCount++;
                    } catch (NumberFormatException ignored) {
                        // not an index list
                    }
                }
            }
        }
        return new RegistryState(accum, maxRevoked, revokedCount);
    }

    /** What the ledger says about the registry right now. */
    private record RegistryState(String accum, long maxRevokedIndex, long revokedCount) { }

    @Override public String name() {
        return "Hyperledger Indy baseline";
    }

    @Override public String notes() {
        return String.format("n=%d, %d revoked indices per "
                + "REVOC_REG_ENTRY or %d ms (swept optimum), serial accumulator chain, "
                + "fresh registry per run (%d revoked at open), client config %s, indy-vdr %s",
                nodeCount, entriesPerTransaction, maxBatchDelayMs, Math.max(revokedAtOpen, 0),
                poolConfig, vdr.version());
    }

    /**
     * Writes NYMs for the population before warm-up. Idempotent within a process: DIDs already
     * written are reused rather than rewritten, because a second NYM from the trustee for a DID
     * whose key has since been rotated is an unauthorised edit.
     */
    @Override public synchronized List<String> populate(int count) throws Exception {
        List<CompletableFuture<String>> window = new ArrayList<>(POPULATE_WINDOW);
        while (population.size() < count) {
            Identity id = Identity.derive(salt + ":pop:" + population.size());
            window.add(submitNym(submitterDid, submitterKey, id.did, id.verkey, null));
            identities.put(id.did, id);
            population.add(id.did);
            if (window.size() >= POPULATE_WINDOW) {
                joinAll(window);
            }
        }
        joinAll(window);
        return new ArrayList<>(population.subList(0, count));
    }

    @Override public void register(String did, byte[] document) throws Exception {
        // A committed write is one the client has an accepted reply for, matching the f+1-matching
        // definition used for the Tailored BFT VDR (bench/METRICS.md).
        Identity id = Identity.derive(salt + ":reg:" + did);
        submitNym(submitterDid, submitterKey, id.did, id.verkey, null).join();
        identities.put(did, id);
        if (!did.equals(id.did)) {
            identities.put(id.did, id);
        }
    }

    @Override public void update(String did, long expectedVersion, byte[] document)
            throws Exception {
        // Key rotation: the realistic SSI update, and what the Tailored BFT update does.
        // Only the owner may rotate a verkey, so the request is signed by the DID's current key.
        Identity current = identities.get(did);
        if (current == null) {
            throw new IllegalStateException("update of a DID this backend never wrote: " + did);
        }
        Identity next = Identity.fromSeed(current.did,
                sha256(salt + ":rot:" + current.did + ":" + keyRotations.getAndIncrement()));
        submitNym(current.did, current.privateKey, current.did, next.verkey, null).join();
        identities.put(did, next);
        if (!did.equals(current.did)) {
            identities.put(current.did, next);
        }
    }

    @Override public void resolve(String did, int replicaHint) throws Exception {
        Identity id = identities.get(did);
        String indyDid = id != null ? id.did : did;
        // indy-vdr sends a read to a single node and verifies the returned state proof against
        // the pool's BLS multi-signature. That verification cost stays inside the measured
        // latency, exactly as client-side Merkle proof verification does on our system
        // (baseline plan §4). Disabling it would measure a weaker system.
        long req = vdr.buildGetNymRequest(submitterDid, indyDid);
        // No freeRequest after submit: pool_submit_request takes ownership of the handle.
        String reply = vdr.poolSubmitRequest(pool, req).join();
        if (reply == null || reply.isEmpty()) {
            throw new IllegalStateException("empty GET_NYM reply for " + indyDid);
        }
        if (reply.contains("\"data\":null")) {
            throw new IllegalStateException("GET_NYM found no NYM for " + indyDid
                    + " — a read of nothing must not count as a resolve");
        }
    }

    @Override public String registryIdFor(String did) {
        return revocRegDefId;
    }

    /**
     * Accumulates revoked indices and submits one REVOC_REG_ENTRY per batch — Indy's native
     * batching, and the direct analogue of the Tailored BFT gateway's aggregation. Non-blocking:
     * no thread per in-flight revocation.
     */
    @Override public synchronized CompletableFuture<Void> submitRevoke(String registryId,
                                                                      String credentialHandle) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        pendingRevocations.add(credentialHandle);
        pendingFutures.add(result);
        if (pendingRevocations.size() >= entriesPerTransaction) {
            flushRevocations();
        } else if (pendingFlush == null) {
            pendingFlush = flushTimer.schedule(this::flushRevocations, maxBatchDelayMs,
                    TimeUnit.MILLISECONDS);
        }
        return result;
    }

    /**
     * Appends one entry to the serial revocation chain. Each entry's prevAccum is the ledger's
     * current accumulator, so entries for one registry cannot be in flight concurrently.
     */
    @Override public synchronized void flushRevocations() {
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
        if (pendingRevocations.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> batch = new ArrayList<>(pendingFutures);
        int size = pendingRevocations.size();
        pendingRevocations.clear();
        pendingFutures.clear();

        revokeChain = revokeChain.thenComposeAsync(ignored -> submitEntry(size, batch),
                revokeExecutor);
    }

    /** One chain step. The returned future never completes exceptionally. */
    private CompletableFuture<Void> submitEntry(int size, List<CompletableFuture<Void>> batch) {
        // A real deployment computes the accumulator with anoncreds-rs. The value is opaque to the
        // ledger, so a placeholder is adequate for load generation — but it means this backend does
        // NOT measure accumulator computation cost. Record that in the parity audit (baseline §7).
        String prev = previousAccumulator;
        if (prev == null) {
            // A previous entry failed, so the local state is untrustworthy: ask the ledger.
            RegistryState state;
            try {
                state = fetchRegistryState();
            } catch (RuntimeException e) {
                batch.forEach(f -> f.completeExceptionally(e));
                return CompletableFuture.completedFuture(null);
            }
            if (state == null || state.accum() == null) {
                IllegalStateException e = new IllegalStateException(
                        "no accumulator on the ledger for " + revocRegDefId);
                batch.forEach(f -> f.completeExceptionally(e));
                return CompletableFuture.completedFuture(null);
            }
            prev = state.accum();
            long next = state.maxRevokedIndex() + 1;
            revocationIndex.updateAndGet(v -> Math.max(v, next));
        }
        // Indices are taken here, not at flush time, so a re-read of the ledger moves the next
        // entry past anything already revoked. The ledger rejects a repeated index.
        List<Long> indices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indices.add(revocationIndex.getAndIncrement());
        }
        // Must differ from prev: the ledger rejects an entry that revokes indices without moving
        // the accumulator. A per-entry counter alone repeats across processes on the same registry.
        String accum = "1 " + Long.toHexString(System.nanoTime())
                + " 1 " + Long.toHexString(entrySeq.getAndIncrement());
        String entry = "{\"ver\":\"1.0\",\"value\":{\"prevAccum\":\"" + prev
                + "\",\"accum\":\"" + accum + "\",\"issued\":[],\"revoked\":" + indices + "}}";

        long req;
        try {
            req = vdr.buildRevocRegEntryRequest(submitterDid, revocRegDefId, "CL_ACCUM", entry);
        } catch (RuntimeException e) {
            batch.forEach(f -> f.completeExceptionally(e));
            return CompletableFuture.completedFuture(null);
        }
        try {
            sign(req, submitterKey);
        } catch (RuntimeException e) {
            vdr.freeRequest(req); // built but never submitted: still ours to free
            batch.forEach(f -> f.completeExceptionally(e));
            return CompletableFuture.completedFuture(null);
        }
        return vdr.poolSubmitRequest(pool, req).handle((reply, err) -> {
            if (err != null) {
                // The local value may no longer match the ledger; the next entry re-reads it.
                previousAccumulator = null;
                batch.forEach(f -> f.completeExceptionally(err));
            } else {
                previousAccumulator = accum;
                batch.forEach(f -> f.complete(null));
            }
            return (Void) null;
        });
    }

    @Override public void close() {
        CompletableFuture<Void> tail;
        synchronized (this) {
            flushRevocations();
            tail = revokeChain;
        }
        try {
            tail.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("revocation chain did not drain before close: " + e);
        }
        flushTimer.shutdownNow();
        revokeExecutor.shutdown();
        // Ledger-growth evidence (remediation plan §2 A4), read before the pool closes.
        try {
            RegistryState end = fetchRegistryState();
            if (end != null && revokedAtOpen >= 0) {
                System.out.printf("      registry %s closed with %d revoked index(es) (+%d this "
                        + "run)%n", shortId(revocRegDefId), end.revokedCount(),
                        end.revokedCount() - revokedAtOpen);
            }
        } catch (RuntimeException e) {
            System.err.println("could not read the registry's closing state: " + e);
        }
        try {
            vdr.poolClose(pool);
        } finally {
            vdr.close();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Builds, signs and submits a NYM. The library owns the request once submitted. */
    private CompletableFuture<String> submitNym(String submitter, PrivateKey key, String dest,
                                                String verkey, String role) {
        long req = vdr.buildNymRequest(submitter, dest, verkey, null, role, null, -1);
        try {
            sign(req, key);
        } catch (RuntimeException e) {
            vdr.freeRequest(req);
            throw e;
        }
        return vdr.poolSubmitRequest(pool, req);
    }

    /** Indy signs the canonical signature input, not the raw request JSON. */
    private void sign(long requestHandle, PrivateKey key) {
        try {
            byte[] input = vdr.signatureInput(requestHandle);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(input);
            vdr.setSignature(requestHandle, s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    private static void joinAll(List<CompletableFuture<String>> futures) {
        for (CompletableFuture<String> f : futures) {
            f.join();
        }
        futures.clear();
    }

    private static String firstNonEmpty(String a, String b, String fallback) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        if (b != null && !b.isEmpty()) {
            return b;
        }
        return fallback;
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String base58(byte[] in) {
        int zeros = 0;
        while (zeros < in.length && in[zeros] == 0) {
            zeros++;
        }
        BigInteger n = new BigInteger(1, in);
        BigInteger base = BigInteger.valueOf(58);
        StringBuilder sb = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(base);
            sb.append(BASE58[qr[1].intValue()]);
            n = qr[0];
        }
        for (int i = 0; i < zeros; i++) {
            sb.append('1');
        }
        return sb.reverse().toString();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** An Indy identity: DID, full verkey, and the Ed25519 private key behind it. */
    private static final class Identity {
        final String did;
        final String verkey;
        final PrivateKey privateKey;

        private Identity(String did, String verkey, PrivateKey privateKey) {
            this.did = did;
            this.verkey = verkey;
            this.privateKey = privateKey;
        }

        /** Fresh identity from a label: its DID is derived from its own key, as Indy does. */
        static Identity derive(String label) {
            return fromSeed(null, sha256(label));
        }

        /**
         * Ed25519 key pair from a 32-byte seed (Indy's convention: the seed IS the private key).
         * If fixedDid is null the DID is base58(pub[0..16]); otherwise the key is a rotation for
         * an existing DID, whose identifier does not change.
         */
        static Identity fromSeed(String fixedDid, byte[] seed) {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
                g.initialize(NamedParameterSpec.ED25519, new FixedSeedRandom(seed));
                KeyPair kp = g.generateKeyPair();
                byte[] spki = kp.getPublic().getEncoded();
                byte[] pub = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
                String did = fixedDid != null ? fixedDid : base58(Arrays.copyOf(pub, 16));
                return new Identity(did, base58(pub), kp.getPrivate());
            } catch (Exception e) {
                throw new IllegalStateException("Ed25519 key derivation failed", e);
            }
        }
    }

    /** Feeds a fixed seed to the JDK's Ed25519 generator, which draws the private key from it. */
    private static final class FixedSeedRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final byte[] seed;

        FixedSeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override public void nextBytes(byte[] bytes) {
            if (bytes.length > seed.length) {
                throw new IllegalStateException("seed too short for requested key material");
            }
            System.arraycopy(seed, 0, bytes, 0, bytes.length);
        }
    }
}
