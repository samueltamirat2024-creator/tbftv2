package vdr.replication;

import vdr.crypto.Crypto;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.store.VdrStore;

/**
 * One VDR replica: the L5 operation executor sitting on top of the store.
 *
 * The Byzantine behaviours below are the fault-injection adapter of plan section 13.
 * They are compiled into the test artefact and MUST NOT be enabled in a benchmark or
 * production image -- Cluster.assertNoByzantineInBenchmark() enforces that.
 */
public final class ServiceReplica {

    /** In-bound Byzantine behaviours: at most f replicas may exhibit these. */
    public enum Behaviour {
        HONEST,
        EQUIVOCATE,        // return a reply that disagrees with the honest majority
        SUPPRESS_REVOKE,   // silently drop revocations (the key safety claim under test)
        STALE_READ,        // serve a stale-but-valid document from an older checkpoint
        FORGE_DOC          // serve an altered document with a genuine-looking proof
    }

    private final VdrStore store;
    private volatile Behaviour behaviour = Behaviour.HONEST;
    private VdrStore.Checkpoint previousCheckpoint;

    public ServiceReplica(int replicaId, int maxBatchSize, long checkpointInterval) {
        this.store = new VdrStore(replicaId, maxBatchSize, checkpointInterval);
        this.previousCheckpoint = store.checkpoint();
    }

    public int id() { return store.replicaId(); }
    public VdrStore store() { return store; }
    public Behaviour behaviour() { return behaviour; }
    public void setBehaviour(Behaviour b) { this.behaviour = b; }

    // ------------------------------------------------------------ ordered path

    public Reply executeOrdered(Op op, long seq) {
        if (behaviour == Behaviour.SUPPRESS_REVOKE && op.kind == Op.Kind.REVOKE) {
            // pretend to commit, apply nothing: this is the suppression attack of
            // Paper 2 section IX-B, and the Tier-1 read path must defeat it
            return Reply.ok(id(), seq);
        }
        Reply r = store.execute(op, seq);
        if (behaviour == Behaviour.EQUIVOCATE) {
            return new Reply(id(), !r.ok, "EQUIVOCATION", r.value, r.version + 7, r.epoch,
                    seq, r.checkpointRoot, r.proof, r.leafKey, !r.revoked);
        }
        return r;
    }

    // ---------------------------------------------------------- read fast path

    public Reply resolve(String did) {
        VdrStore.Checkpoint current = store.checkpoint();
        switch (behaviour) {
            case STALE_READ -> {
                Reply stale = store.resolveFrom(previousCheckpoint, did);
                previousCheckpoint = current;
                return stale;
            }
            case FORGE_DOC -> {
                Reply honest = store.resolveFrom(current, did);
                if (!honest.ok) return honest;
                byte[] forged = Crypto.sha256(honest.value, Crypto.sha256("FORGED"));
                // proof and root are genuine; the VALUE is not, so client-side verification fails
                return new Reply(id(), true, null, forged, honest.version, honest.epoch,
                        honest.seq, honest.checkpointRoot, honest.proof, honest.leafKey, false);
            }
            default -> {
                previousCheckpoint = current;
                return store.resolveFrom(current, did);
            }
        }
    }

    public Reply revocationStatus(String registryId, String handle) {
        VdrStore.Checkpoint current = store.checkpoint();
        if (behaviour == Behaviour.STALE_READ) {
            Reply stale = store.revocationStatusFrom(previousCheckpoint, registryId, handle);
            previousCheckpoint = current;
            return stale;
        }
        previousCheckpoint = current;
        return store.revocationStatusFrom(current, registryId, handle);
    }

    /** Signed checkpoint announcement (plan section 3.3, client-side root trust). */
    public byte[] announcedRoot() {
        return store.checkpoint().root;
    }

    public long announcedEpoch() {
        return store.checkpoint().epoch;
    }
}
