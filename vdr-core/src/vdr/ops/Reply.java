package vdr.ops;

import vdr.crypto.Crypto;
import vdr.merkle.Merkle;

/**
 * A replica's reply. The client accepts a write on f+1 matching replies from distinct
 * replicas (DepSpace replication contract), and a Tier-0 read on a single reply whose
 * inclusion proof verifies against a trusted checkpoint root.
 *
 * {@link #digest()} is what "matching" means: two replies match iff their digests are
 * equal. It deliberately EXCLUDES per-replica data so that honest replicas always agree.
 */
public final class Reply {

    public final int replicaId;
    public final boolean ok;
    public final String errorCode;      // policy rejection code, e.g. "P1_DID_EXISTS"
    public final byte[] value;          // resolved document bytes, or null
    public final long version;
    public final long epoch;            // revocation epoch at the serving checkpoint
    public final long seq;              // consensus sequence number applied
    public final byte[] checkpointRoot;
    public final Merkle.Proof proof;
    public final String leafKey;
    public final boolean revoked;       // revocation-status answer

    public Reply(int replicaId, boolean ok, String errorCode, byte[] value, long version, long epoch,
                 long seq, byte[] checkpointRoot, Merkle.Proof proof, String leafKey, boolean revoked) {
        this.replicaId = replicaId;
        this.ok = ok;
        this.errorCode = errorCode;
        this.value = value;
        this.version = version;
        this.epoch = epoch;
        this.seq = seq;
        this.checkpointRoot = checkpointRoot;
        this.proof = proof;
        this.leafKey = leafKey;
        this.revoked = revoked;
    }

    public static Reply ok(int id, long seq) {
        return new Reply(id, true, null, null, 0, 0, seq, null, null, null, false);
    }

    public static Reply denied(int id, String code, long seq) {
        return new Reply(id, false, code, null, 0, 0, seq, null, null, null, false);
    }

    /**
     * Digest used for f+1 matching. Includes the outcome, the value and the state root,
     * excludes the replica id and the proof path (which is a function of the root anyway).
     */
    public String digest() {
        return Crypto.hex(Crypto.sha256(
                new byte[]{(byte) (ok ? 1 : 0), (byte) (revoked ? 1 : 0)},
                errorCode == null ? Crypto.EMPTY : errorCode.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                value == null ? Crypto.EMPTY : value,
                Crypto.longToBytes(version),
                Crypto.longToBytes(epoch),
                checkpointRoot == null ? Crypto.EMPTY : checkpointRoot));
    }

    @Override
    public String toString() {
        return "Reply{r" + replicaId + (ok ? " ok" : " denied:" + errorCode) + " v" + version + " e" + epoch + "}";
    }
}
