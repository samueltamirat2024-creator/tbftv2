package vdr.ops;

import vdr.crypto.Crypto;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The minimum VDR operation set of Paper 2, Table I: register / resolve / update / revoke,
 * plus DepSpace's repair (Algorithm 3).
 *
 * Every write carries a client signature that is verified at the gateway AND re-verified
 * on every replica -- the gateway is untrusted (Paper 2 section V-E).
 */
public final class Op {

    public enum Kind {
        REGISTER,   // DepSpace cas: at-most-once per DID
        UPDATE,     // ordered write with per-DID monotonicity
        RESOLVE,    // read-only fast path (never ordered unless Tier 2)
        REVOKE,     // ordered batch, accumulator delta
        REPAIR,     // DepSpace Algorithm 3, scoped to REVENTRY
        ROOT,       // unordered: the replica's current checkpoint root and epoch
        REVSTATUS,  // unordered: is this credential handle revoked? (the Tier-1 safety path)
        CHECKPOINT  // ordered: cut a Merkle checkpoint, so every replica cuts at the same seq
    }

    public final Kind kind;
    public final String clientId;
    public final String did;
    public final long expectedVersion;      // UPDATE precondition
    public final byte[] docBytes;           // REGISTER / UPDATE
    public final byte[] controllerPubKey;   // key set asserted by this document version
    public final byte[] sig;
    public final String registryId;         // REVOKE / REPAIR
    public final List<String> handles;      // REVOKE: high-entropy opaque credential handles
    public final byte[] reasonPayload;      // REVOKE: optional confidential metadata (PR field)
    public final long nonce;                // client-supplied; no randomness in execution (plan section 7)

    private Op(Kind kind, String clientId, String did, long expectedVersion, byte[] docBytes,
               byte[] controllerPubKey, byte[] sig, String registryId, List<String> handles,
               byte[] reasonPayload, long nonce) {
        this.kind = kind;
        this.clientId = clientId;
        this.did = did;
        this.expectedVersion = expectedVersion;
        this.docBytes = docBytes;
        this.controllerPubKey = controllerPubKey;
        this.sig = sig;
        this.registryId = registryId;
        this.handles = handles;
        this.reasonPayload = reasonPayload;
        this.nonce = nonce;
    }

    /** Canonical bytes a client signs. Deterministic and independent of field order in memory. */
    public byte[] signedBytes() {
        return Crypto.sha256(
                kind.name().getBytes(StandardCharsets.UTF_8),
                s(did),
                Crypto.longToBytes(expectedVersion),
                docBytes == null ? Crypto.EMPTY : docBytes,
                controllerPubKey == null ? Crypto.EMPTY : controllerPubKey,
                s(registryId),
                handles == null ? Crypto.EMPTY : String.join(",", handles).getBytes(StandardCharsets.UTF_8),
                Crypto.longToBytes(nonce));
    }

    private static byte[] s(String v) {
        return v == null ? Crypto.EMPTY : v.getBytes(StandardCharsets.UTF_8);
    }

    public Op withSignature(byte[] signature) {
        return new Op(kind, clientId, did, expectedVersion, docBytes, controllerPubKey, signature,
                registryId, handles, reasonPayload, nonce);
    }

    /** RESOLVE, ROOT and REVSTATUS are reads: never ordered unless Tier 2, never mutating. */
    public boolean isWrite() {
        return kind != Kind.RESOLVE && kind != Kind.ROOT && kind != Kind.REVSTATUS;
    }

    public static Op register(String clientId, String did, byte[] docBytes, byte[] pubKey, long nonce) {
        return new Op(Kind.REGISTER, clientId, did, 0, docBytes, pubKey, null, null, null, null, nonce);
    }

    public static Op update(String clientId, String did, long expectedVersion, byte[] docBytes,
                            byte[] newPubKey, long nonce) {
        return new Op(Kind.UPDATE, clientId, did, expectedVersion, docBytes, newPubKey, null, null, null, null, nonce);
    }

    public static Op resolve(String clientId, String did) {
        return new Op(Kind.RESOLVE, clientId, did, 0, null, null, null, null, null, null, 0);
    }

    /**
     * Checkpoint-root query (plan §3.3, client-side root trust). The simulator reads roots
     * straight off its in-process replicas; over a real network the client has to ask, and it
     * trusts a root only once f+1 replicas return the same one.
     */
    /**
     * Revocation-status check on the Tier-1 freshness path (plan §3.3). Read-only, so it is served
     * unordered from the latest committed checkpoint and carries an inclusion proof when the
     * answer is "revoked".
     */
    public static Op revocationStatus(String clientId, String registryId, String handle) {
        return new Op(Kind.REVSTATUS, clientId, null, 0, null, null, null, registryId,
                List.of(handle), null, 0);
    }

    public static Op root(String clientId) {
        return new Op(Kind.ROOT, clientId, null, 0, null, null, null, null, null, null, 0);
    }

    /**
     * Forces a Merkle checkpoint. Ordered, so every replica cuts at the same sequence number and
     * the roots stay identical -- an out-of-band "checkpoint now" call to each replica would cut
     * at whatever each had executed, and Tier-0 proofs would verify against roots no quorum held.
     * Used after population, never inside a measurement window.
     */
    public static Op checkpoint(String clientId, long nonce) {
        return new Op(Kind.CHECKPOINT, clientId, null, 0, null, null, null, null, null, null, nonce);
    }

    /**
     * Rebuilds an Op from the wire. FOR {@code vdr.serialization.Codec} ONLY -- every other caller
     * uses a named factory above, so that an operation cannot be assembled in a shape no factory
     * can produce.
     */
    public static Op restore(Kind kind, String clientId, String did, long expectedVersion,
                             byte[] docBytes, byte[] controllerPubKey, byte[] sig, String registryId,
                             List<String> handles, byte[] reasonPayload, long nonce) {
        return new Op(kind, clientId, did, expectedVersion, docBytes, controllerPubKey, sig,
                registryId, handles, reasonPayload, nonce);
    }

    public static Op revoke(String clientId, String registryId, List<String> handles,
                            byte[] reasonPayload, long nonce) {
        return new Op(Kind.REVOKE, clientId, null, 0, null, null, null, registryId, handles, reasonPayload, nonce);
    }

    public static Op repair(String clientId, String registryId, String handleHash, long nonce) {
        return new Op(Kind.REPAIR, clientId, null, 0, null, null, null, registryId, List.of(handleHash), null, nonce);
    }

    @Override
    public String toString() {
        return kind + "(" + (did != null ? did : registryId) + ")";
    }
}
