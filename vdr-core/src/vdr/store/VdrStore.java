package vdr.store;

import vdr.crypto.Crypto;
import vdr.merkle.Merkle;
import vdr.model.Fingerprint;
import vdr.model.Protection;
import vdr.model.Records;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.policy.Policies;
import vdr.policy.VdrPolicy;
import vdr.serialization.Codec;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Local VDR registry held by one replica: a versioned, append-only log of DID-document
 * operations plus revocation accumulators, with Merkle checkpoints over the
 * replica-identical projection of state.
 *
 * DETERMINISM RULES (plan section 7), enforced here by construction:
 *   - no wall-clock reads: time is the consensus sequence number and the revocation epoch
 *   - every structure that can affect a reply or a state root is ordered (TreeMap/TreeSet)
 *   - no randomness: nonces are client-supplied and ordered
 *   - no floating point in state
 *   - tie-breaking is explicit: records are ordered by (did, version) ascending
 */
public final class VdrStore implements VdrPolicy.View {

    private final int replicaId;
    private final List<VdrPolicy> policyChain = Policies.standard();
    private final int maxBatchSize;
    private final long checkpointInterval;

    // ---- committed state (replica-identical) ------------------------------------
    private final TreeMap<String, Records.DidHead> heads = new TreeMap<>();
    /**
     * Primary index (plan section 2.3): fingerprint key -> did, giving O(log n) cas and
     * resolve. DepSpace keeps a flat bag of tuples; at VDR benchmark scale (10^5-10^6 DIDs)
     * a linear template scan makes resolve O(n) and renders the read fast path meaningless.
     */
    private final TreeMap<String, String> headIndex = new TreeMap<>();
    private final TreeMap<String, TreeMap<Long, Records.DidDoc>> versions = new TreeMap<>();
    private final TreeMap<String, RevRegistry> registries = new TreeMap<>();
    private final TreeSet<String> blacklist = new TreeSet<>();
    private final TreeMap<String, String> revEntryInserter = new TreeMap<>(); // leafKey -> clientId

    // ---- per-replica state (NEVER in the state root; see plan section 5) ---------
    private final TreeMap<String, byte[]> confidentialShares = new TreeMap<>();

    private long seq = 0;
    private long revocationEpoch = 0;        // monotone; never recomputed by scanning registries
    private long opsSinceCheckpoint = 0;
    private Checkpoint checkpoint;

    public VdrStore(int replicaId, int maxBatchSize, long checkpointInterval) {
        this.replicaId = replicaId;
        this.maxBatchSize = maxBatchSize;
        this.checkpointInterval = checkpointInterval;
        this.checkpoint = buildCheckpoint();
    }

    public int replicaId() { return replicaId; }
    public long seq() { return seq; }
    public Checkpoint checkpoint() { return checkpoint; }
    public long epoch() { return currentEpoch(); }

    // ================================================================= execution

    /**
     * Deterministic state transition. Called on every replica with the same operation in
     * the same total order, so at least n-f >= 2f+1 correct replicas produce the same reply.
     */
    public synchronized Reply execute(Op op, long sequenceNumber) {
        this.seq = sequenceNumber;

        // L3: policy enforcement runs on EVERY replica, not only at the gateway
        Optional<String> denial = Policies.evaluate(policyChain, op, this);
        if (denial.isPresent()) {
            return Reply.denied(replicaId, denial.get(), seq);
        }

        Reply r = switch (op.kind) {
            case REGISTER -> doRegister(op);
            case UPDATE   -> doUpdate(op);
            case REVOKE   -> doRevoke(op);
            case REPAIR   -> doRepair(op);
            case RESOLVE  -> orderedResolve(op);   // Tier 2 fallback path
            case ROOT     -> rootReply();          // read-only; never mutates
            case REVSTATUS -> revocationStatusUnordered(op.registryId, op.handles.get(0));
            case CHECKPOINT -> { forceCheckpoint(); yield rootReply(); }
        };

        opsSinceCheckpoint++;
        if (opsSinceCheckpoint >= checkpointInterval) {
            forceCheckpoint();
        }
        return r;
    }

    /**
     * DepSpace cas(t-bar, t): insert iff no record matches the template, executed
     * indivisibly. A tuple space supporting cas can solve consensus, which is exactly
     * the at-most-once primitive register needs -- so we use it rather than inventing
     * a uniqueness check (plan section 3.1).
     */
    private Reply doRegister(Op op) {
        String indexKey = headFingerprintKey(op.did);
        if (headIndex.containsKey(indexKey)) {
            return Reply.denied(replicaId, "CAS_FALSE_DID_EXISTS", seq);
        }
        Records.DidDoc doc = new Records.DidDoc(op.did, 1, op.docBytes, op.controllerPubKey,
                Records.DidDoc.hashKeySet(op.controllerPubKey), "GENESIS");
        versions.computeIfAbsent(op.did, k -> new TreeMap<>()).put(1L, doc);
        heads.put(op.did, new Records.DidHead(op.did, 1, currentEpoch(), false));
        headIndex.put(indexKey, op.did);
        return new Reply(replicaId, true, null, null, 1, currentEpoch(), seq, null, null, null, false);
    }

    /**
     * The content-addressable index key for a DIDHEAD, computed through the DepSpace
     * fingerprint function so that indexing and template matching cannot drift apart.
     */
    private static String headFingerprintKey(String did) {
        return Fingerprint.key(Fingerprint.of(
                Arrays.asList("DIDHEAD", did, null, null), Records.V_DIDHEAD));
    }

    /** Template-match fallback, retained for templates the index cannot answer. */
    boolean matchesExistingHead(List<String> templateFp) {
        for (Records.DidHead h : heads.values()) {
            if (Fingerprint.matches(Fingerprint.of(h.tupleFields(), Records.V_DIDHEAD), templateFp)) return true;
        }
        return false;
    }

    /**
     * Pure append plus a DIDHEAD pointer swap, atomically inside one ordered operation.
     * DepSpace has to remove-update-reinsert because a tuple space cannot update a tuple;
     * our append-only versioned log does not inherit that workaround (plan section 3.2).
     */
    private Reply doUpdate(Op op) {
        TreeMap<Long, Records.DidDoc> vs = versions.get(op.did);
        Records.DidHead head = heads.get(op.did);
        long newVersion = head.latestVersion() + 1;
        Records.DidDoc prev = vs.get(head.latestVersion());
        Records.DidDoc doc = new Records.DidDoc(op.did, newVersion, op.docBytes, op.controllerPubKey,
                Records.DidDoc.hashKeySet(op.controllerPubKey),
                Crypto.hex(Crypto.sha256(prev.docBytes())));
        vs.put(newVersion, doc);
        heads.put(op.did, new Records.DidHead(op.did, newVersion, currentEpoch(), false));
        return new Reply(replicaId, true, null, null, newVersion, currentEpoch(), seq, null, null, null, false);
    }

    /**
     * Revocation batch (plan section 3.4). The whole batch is ONE ordered operation, so
     * burst throughput is bounded by batch size, not by consensus round count. Every
     * signature is re-verified here because the gateway is untrusted; status is an
     * accumulator delta, not one record per credential.
     */
    private Reply doRevoke(Op op) {
        String registryId = op.registryId;
        RevRegistry reg = registries.computeIfAbsent(registryId, RevRegistry::new);

        // deterministic application order within the batch: sorted by H(handle)
        TreeSet<String> handleHashes = new TreeSet<>();
        for (String h : op.handles) {
            handleHashes.add(Crypto.hex(Crypto.sha256(h)));
        }
        for (String hh : handleHashes) {
            if (reg.handleHashes.add(hh)) {
                reg.accumulator = Crypto.hex(Crypto.sha256(
                        Crypto.unhex(reg.accumulator), Crypto.unhex(hh)));
                String leafKey = revEntryLeafKey(registryId, hh);
                revEntryInserter.put(leafKey, op.clientId);
                if (op.reasonPayload != null) {
                    // Confidentiality layer (L2), optional: the PR field is stored as a
                    // per-replica share. DIFFERENT ON EVERY REPLICA BY DESIGN, and therefore
                    // excluded from the state root (plan section 5).
                    confidentialShares.put(leafKey, Crypto.sha256(op.reasonPayload,
                            Crypto.intToBytes(replicaId)));
                }
            }
        }
        reg.deltaDigest = Crypto.hex(Crypto.sha256(String.join(",", handleHashes)));
        reg.epoch++;                    // revocation epoch advances on every committed batch
        revocationEpoch++;
        forceCheckpoint();              // V6: checkpoint at the revocation-epoch boundary
        return new Reply(replicaId, true, null, null, 0, reg.epoch, seq, checkpoint.root, null, null, true);
    }

    /**
     * DepSpace Algorithm 3, retargeted to REVENTRY (plan section 3.5): delete the
     * malformed entry payload and blacklist the inserting client, so the damage a
     * Byzantine issuer can do is recoverable and bounded. The accumulator is NOT rolled
     * back -- P5 keeps revocation monotone.
     */
    private Reply doRepair(Op op) {
        String leafKey = revEntryLeafKey(op.registryId, op.handles.get(0));
        confidentialShares.remove(leafKey);
        String inserter = revEntryInserter.get(leafKey);
        if (inserter != null) blacklist.add(inserter);
        forceCheckpoint();
        return new Reply(replicaId, true, null, null, 0, currentEpoch(), seq, checkpoint.root, null, null, true);
    }

    private Reply orderedResolve(Op op) {
        Optional<Records.DidDoc> d = latest(op.did);
        if (d.isEmpty()) return Reply.denied(replicaId, "RESOLVE_UNKNOWN_DID", seq);
        return new Reply(replicaId, true, null, d.get().docBytes(), d.get().version(),
                currentEpoch(), seq, null, null, null, false);
    }

    /**
     * The replica's current checkpoint root and epoch, as a reply. This is the signed-checkpoint
     * announcement of plan §3.3 in request form: over a real network a client cannot read a
     * replica's memory, so it asks, and believes a root only when f+1 replicas return the same one.
     */
    public synchronized Reply rootReply() {
        return new Reply(replicaId, true, null, checkpoint.root, 0, checkpoint.epoch,
                checkpoint.seq, checkpoint.root, null, null, false);
    }

    // ============================================================== read fast path

    /**
     * Tier 0/1 read (plan section 3.3): served WITHOUT total order multicast, from the
     * most recent checkpoint, carrying an inclusion proof against the checkpoint root.
     *
     * A Byzantine replica cannot forge a document -- the proof fails verification. Its only
     * power is to serve a stale-but-valid document from an older checkpoint, bounded by the
     * checkpoint interval. Tier 1 is the escape hatch for revocation freshness.
     */
    public synchronized Reply resolveUnordered(String did) {
        return resolveFrom(checkpoint, did);
    }

    /** Same read, served from an explicitly chosen checkpoint (used to model a stale replica). */
    public synchronized Reply resolveFrom(Checkpoint cp, String did) {
        Records.DidDoc doc = cp.latestByDid.get(did);
        if (doc == null) return Reply.denied(replicaId, "RESOLVE_UNKNOWN_DID", seq);
        String leafKey = didDocLeafKey(did, doc.version());
        Merkle.Proof proof = cp.tree.prove(leafKey);
        return new Reply(replicaId, true, null, doc.docBytes(), doc.version(), cp.epoch,
                cp.seq, cp.root, proof, leafKey, false);
    }

    /**
     * Revocation-status check, the security-critical read. Matches the DepSpace template
     * <REVENTRY, registryId, H(credentialHandle), PR> -- an equality match on a COMPARABLE
     * field, so the replica set never learns the handle in plaintext.
     */
    public synchronized Reply revocationStatusUnordered(String registryId, String credentialHandle) {
        return revocationStatusFrom(checkpoint, registryId, credentialHandle);
    }

    public synchronized Reply revocationStatusFrom(Checkpoint cp, String registryId, String credentialHandle) {
        String hh = Crypto.hex(Crypto.sha256(credentialHandle));
        List<String> template = Records.revocationTemplate(registryId, hh);
        List<String> templateFp = Fingerprint.of(template, Records.V_REVENTRY);
        // The comparable field is already the hash, so the template fingerprint carries H(H(handle));
        // matching is performed against the checkpoint's entry set via the same transform.
        boolean revoked = cp.revokedHandleHashes.contains(registryId + "|" + hh);
        String leafKey = revoked ? revEntryLeafKey(registryId, hh) : revAccLeafKey(registryId);
        Merkle.Proof proof = cp.tree.prove(leafKey);
        return new Reply(replicaId, true, null,
                Fingerprint.key(templateFp).getBytes(StandardCharsets.UTF_8),
                0, cp.epoch, cp.seq, cp.root, proof, leafKey, revoked);
    }

    // ================================================================ checkpoints

    public synchronized void forceCheckpoint() {
        opsSinceCheckpoint = 0;
        checkpoint = buildCheckpoint();
    }

    /**
     * THE RULE OF PLAN SECTION 5: the Merkle state root is computed exclusively over the
     * common, replica-identical projection of state -- the fingerprint, the public fields,
     * the public proof data and the accumulator values. Never over shares, never over
     * decrypted payloads. Violating this makes honest replicas diverge the moment the
     * confidentiality layer is switched on, silently invalidating every read proof.
     */
    private Checkpoint buildCheckpoint() {
        TreeMap<String, byte[]> leaves = new TreeMap<>();
        TreeMap<String, Records.DidDoc> latestByDid = new TreeMap<>();
        TreeSet<String> revoked = new TreeSet<>();

        for (Map.Entry<String, TreeMap<Long, Records.DidDoc>> e : versions.entrySet()) {
            for (Records.DidDoc d : e.getValue().values()) {
                leaves.put(didDocLeafKey(d.did(), d.version()), d.leafBytes());   // public projection
            }
            Records.DidHead h = heads.get(e.getKey());
            if (h != null) latestByDid.put(e.getKey(), e.getValue().get(h.latestVersion()));
        }
        for (RevRegistry reg : registries.values()) {
            leaves.put(revAccLeafKey(reg.registryId), Crypto.sha256(
                    reg.registryId.getBytes(StandardCharsets.UTF_8),
                    Crypto.longToBytes(reg.epoch),
                    Crypto.unhex(reg.accumulator)));
            for (String hh : reg.handleHashes) {
                // the COMPARABLE hash only -- never the payload, which is a per-replica share
                leaves.put(revEntryLeafKey(reg.registryId, hh),
                        Crypto.sha256(reg.registryId.getBytes(StandardCharsets.UTF_8), Crypto.unhex(hh)));
                revoked.add(reg.registryId + "|" + hh);
            }
        }
        Merkle tree = new Merkle(leaves);
        return new Checkpoint(tree, tree.root(), currentEpoch(), seq, latestByDid, revoked);
    }

    public synchronized byte[] stateRoot() {
        return buildCheckpoint().root;
    }

    public synchronized String stateRootHex() {
        return Crypto.hex(stateRoot());
    }

    // =========================================================== state transfer

    /**
     * Serialises the REPLICA-IDENTICAL projection of state, and only it (plan §5, BFT-SMaRt
     * implementation plan WP3). This is what {@code getSnapshot()} hands a recovering replica.
     *
     * <p>The exclusion is load-bearing, not tidiness. {@code confidentialShares} holds one PVSS
     * share per replica: every replica's is different by design. If a share reached the snapshot,
     * a recovered replica would come back holding another replica's share, its checkpoint root
     * would diverge from the replicas that never crashed, and every Tier-0 inclusion proof served
     * from it would silently stop verifying. Nothing detects that at run time except the
     * root-equality gate, which is why that gate must run against a cluster that has actually
     * performed a state transfer.
     *
     * <p>What goes in is exactly what {@link #buildCheckpoint()} walks, plus the bookkeeping a
     * replica needs to keep executing: the version log, the heads and their index, the
     * registries, the blacklist and inserter map, the sequence number and the revocation epoch.
     */
    public synchronized byte[] serialiseIdenticalProjection() {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 16);
             java.io.DataOutputStream out = new java.io.DataOutputStream(bos)) {
            out.writeInt(SNAPSHOT_FORMAT);
            out.writeLong(seq);
            out.writeLong(revocationEpoch);
            out.writeLong(opsSinceCheckpoint);

            out.writeInt(heads.size());
            for (Records.DidHead h : heads.values()) {          // TreeMap: ordered by construction
                Codec.writeString(out, h.did());
                out.writeLong(h.latestVersion());
                out.writeLong(h.stateRootEpoch());
                out.writeBoolean(h.deactivated());
            }

            out.writeInt(versions.size());
            for (Map.Entry<String, TreeMap<Long, Records.DidDoc>> e : versions.entrySet()) {
                Codec.writeString(out, e.getKey());
                out.writeInt(e.getValue().size());
                for (Records.DidDoc d : e.getValue().values()) {
                    out.writeLong(d.version());
                    Codec.writeBytes(out, d.docBytes());
                    Codec.writeBytes(out, d.controllerPubKey());
                    Codec.writeString(out, d.keySetHash());
                    Codec.writeString(out, d.prevHash());
                }
            }

            out.writeInt(registries.size());
            for (RevRegistry r : registries.values()) {
                Codec.writeString(out, r.registryId);
                Codec.writeString(out, r.accumulator);
                Codec.writeString(out, r.deltaDigest);
                out.writeLong(r.epoch);
                out.writeInt(r.handleHashes.size());
                for (String hh : r.handleHashes) {              // TreeSet: ordered
                    Codec.writeString(out, hh);
                }
            }

            out.writeInt(blacklist.size());
            for (String c : blacklist) {
                Codec.writeString(out, c);
            }

            out.writeInt(revEntryInserter.size());
            for (Map.Entry<String, String> e : revEntryInserter.entrySet()) {
                Codec.writeString(out, e.getKey());
                Codec.writeString(out, e.getValue());
            }
            out.flush();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not serialise the state snapshot", e);
        }
    }

    /** Restores a snapshot written by {@link #serialiseIdenticalProjection()}. */
    public synchronized void installIdenticalProjection(byte[] state) {
        if (state == null || state.length == 0) {
            return;                                   // nothing to install: a fresh cluster
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(state))) {
            int format = in.readInt();
            if (format != SNAPSHOT_FORMAT) {
                throw new IllegalStateException("snapshot format " + format + ", expected "
                        + SNAPSHOT_FORMAT + "; replicas are running different builds, which would "
                        + "diverge silently");
            }
            heads.clear();
            headIndex.clear();
            versions.clear();
            registries.clear();
            blacklist.clear();
            revEntryInserter.clear();
            // Per-replica state is NOT in the snapshot and must not survive it: a share belonging
            // to the pre-crash incarnation is meaningless to the restored one.
            confidentialShares.clear();

            this.seq = in.readLong();
            this.revocationEpoch = in.readLong();
            this.opsSinceCheckpoint = in.readLong();

            int headCount = in.readInt();
            for (int i = 0; i < headCount; i++) {
                String did = Codec.readString(in);
                long latest = in.readLong();
                long epoch = in.readLong();
                boolean deactivated = in.readBoolean();
                heads.put(did, new Records.DidHead(did, latest, epoch, deactivated));
                headIndex.put(headFingerprintKey(did), did);
            }

            int didCount = in.readInt();
            for (int i = 0; i < didCount; i++) {
                String did = Codec.readString(in);
                int versionCount = in.readInt();
                TreeMap<Long, Records.DidDoc> vs = new TreeMap<>();
                for (int j = 0; j < versionCount; j++) {
                    long version = in.readLong();
                    byte[] doc = Codec.readBytes(in);
                    byte[] key = Codec.readBytes(in);
                    String keySetHash = Codec.readString(in);
                    String prevHash = Codec.readString(in);
                    vs.put(version, new Records.DidDoc(did, version, doc, key, keySetHash, prevHash));
                }
                versions.put(did, vs);
            }

            int registryCount = in.readInt();
            for (int i = 0; i < registryCount; i++) {
                String registryId = Codec.readString(in);
                RevRegistry r = new RevRegistry(registryId);
                r.accumulator = Codec.readString(in);
                r.deltaDigest = Codec.readString(in);
                r.epoch = in.readLong();
                int handleCount = in.readInt();
                for (int j = 0; j < handleCount; j++) {
                    r.handleHashes.add(Codec.readString(in));
                }
                registries.put(registryId, r);
            }

            int blacklistCount = in.readInt();
            for (int i = 0; i < blacklistCount; i++) {
                blacklist.add(Codec.readString(in));
            }

            int inserterCount = in.readInt();
            for (int i = 0; i < inserterCount; i++) {
                String leafKey = Codec.readString(in);
                revEntryInserter.put(leafKey, Codec.readString(in));
            }

            // Rebuild the checkpoint from the restored state. A recovered replica that kept its
            // old checkpoint would serve Tier-0 reads proved against a root nobody committed.
            this.checkpoint = buildCheckpoint();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not install the state snapshot", e);
        }
    }

    /** Bumped whenever the snapshot layout changes, so mismatched builds fail loudly. */
    private static final int SNAPSHOT_FORMAT = 1;

    private long currentEpoch() {
        return revocationEpoch;
    }

    static String didDocLeafKey(String did, long version) {
        return "DIDDOC|" + did + "|" + String.format("%012d", version);
    }

    static String revAccLeafKey(String registryId) {
        return "REVACC|" + registryId;
    }

    static String revEntryLeafKey(String registryId, String handleHash) {
        return "REVENTRY|" + registryId + "|" + handleHash;
    }

    // ============================================================ VdrPolicy.View

    @Override public synchronized Optional<Records.DidHead> head(String did) {
        return Optional.ofNullable(heads.get(did));
    }

    @Override public synchronized Optional<Records.DidDoc> version(String did, long version) {
        TreeMap<Long, Records.DidDoc> vs = versions.get(did);
        return vs == null ? Optional.empty() : Optional.ofNullable(vs.get(version));
    }

    @Override public synchronized boolean revoked(String registryId, String handleHash) {
        RevRegistry r = registries.get(registryId);
        return r != null && r.handleHashes.contains(handleHash);
    }

    @Override public synchronized boolean blacklisted(String clientId) {
        return clientId != null && blacklist.contains(clientId);
    }

    @Override public int maxBatchSize() { return maxBatchSize; }

    public synchronized Optional<Records.DidDoc> latest(String did) {
        Records.DidHead h = heads.get(did);
        if (h == null) return Optional.empty();
        return Optional.ofNullable(versions.get(did).get(h.latestVersion()));
    }

    public synchronized int didCount() { return heads.size(); }

    public synchronized boolean hasShare(String registryId, String handleHash) {
        return confidentialShares.containsKey(revEntryLeafKey(registryId, handleHash));
    }

    /** Per-registry revocation accumulator (plan section 2.3). */
    static final class RevRegistry {
        final String registryId;
        final TreeSet<String> handleHashes = new TreeSet<>();
        String accumulator = Crypto.hex(Crypto.sha256("REVACC_GENESIS"));
        String deltaDigest = "";
        long epoch = 0;

        RevRegistry(String registryId) { this.registryId = registryId; }
    }

    /** An immutable checkpoint: what Tier-0/1 reads are served from and proved against. */
    public static final class Checkpoint {
        public final Merkle tree;
        public final byte[] root;
        public final long epoch;
        public final long seq;
        public final TreeMap<String, Records.DidDoc> latestByDid;
        public final TreeSet<String> revokedHandleHashes;

        Checkpoint(Merkle tree, byte[] root, long epoch, long seq,
                   TreeMap<String, Records.DidDoc> latestByDid, TreeSet<String> revokedHandleHashes) {
            this.tree = tree;
            this.root = root;
            this.epoch = epoch;
            this.seq = seq;
            this.latestByDid = latestByDid;
            this.revokedHandleHashes = revokedHandleHashes;
        }

        public String rootHex() { return Crypto.hex(root); }
    }
}
