package vdr.client;

import vdr.crypto.Crypto;
import vdr.merkle.Merkle;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.replication.Replication;

import java.security.KeyPair;
import java.util.*;

/**
 * Client-side stack (plan section 1): the proxy/SDK that talks to the gateway, verifies
 * proofs itself, and never trusts a single replica for anything it cannot check.
 *
 * Write acceptance follows DepSpace's replication contract: the client waits for f+1
 * replies with the same response from different replicas. Because the store is
 * deterministic and every replica applies the same order, at least n-f >= 2f+1 correct
 * replicas produce that reply.
 */
public final class VdrClient {

    public static final class ProofFailure extends RuntimeException {
        public ProofFailure(String m) { super(m); }
    }

    private final Replication cluster;
    private final String clientId;
    private final KeyPair keys;
    private long nonce = 0;

    /**
     * f+1-agreed checkpoint roots, most recent last. A Tier-0 reply is accepted only if its root is
     * one of these, so a replica can serve at most ROOT_WINDOW checkpoints' worth of staleness.
     * State this bound in the paper (§IX-C): staleness <= ROOT_WINDOW x checkpoint interval.
     */
    private static final int ROOT_WINDOW = Integer.getInteger("vdr.rootWindow", 2);
    private final ArrayDeque<byte[]> recentRoots = new ArrayDeque<>();
    /** Tier-0 reads that fell back because the replica's root was not (yet) agreed. Report it. */
    public final java.util.concurrent.atomic.LongAdder tier0Fallbacks =
            new java.util.concurrent.atomic.LongAdder();

    public VdrClient(Replication cluster, String clientId, KeyPair keys) {
        this.cluster = cluster;
        this.clientId = clientId;
        this.keys = keys;
    }

    public String clientId() { return clientId; }
    public byte[] publicKey() { return Crypto.encodePublic(keys.getPublic()); }

    private Op sign(Op op) {
        return op.withSignature(Crypto.sign(keys.getPrivate(), op.signedBytes()));
    }

    private synchronized long nextNonce() { return ++nonce; }

    /** Exposed so the gateway can have the client sign an aggregated revoke batch. */
    public Op signFor(Op op) { return sign(op); }

    // --------------------------------------------------------------- writes

    public Outcome register(String did, byte[] doc) {
        Op op = sign(Op.register(clientId, did, doc, publicKey(), nextNonce()));
        return accept(cluster.invokeOrderedSync(op));
    }

    public Outcome update(String did, long expectedVersion, byte[] doc, byte[] newControllerKey) {
        Op op = sign(Op.update(clientId, did, expectedVersion, doc, newControllerKey, nextNonce()));
        return accept(cluster.invokeOrderedSync(op));
    }

    public Outcome revoke(String registryId, List<String> handles, byte[] reasonPayload) {
        Op op = sign(Op.revoke(clientId, registryId, handles, reasonPayload, nextNonce()));
        return accept(cluster.invokeOrderedSync(op));
    }

    public Outcome repair(String registryId, String handleHash) {
        Op op = sign(Op.repair(clientId, registryId, handleHash, nextNonce()));
        return accept(cluster.invokeOrderedSync(op));
    }

    /**
     * f+1 matching replies from distinct replicas. A rejection is accepted the same way:
     * DepSpace requires f+1 copies of the same error code before a client believes a denial.
     */
    private Outcome accept(List<Reply> replies) {
        Map<String, List<Reply>> byDigest = new TreeMap<>();
        for (Reply r : replies) {
            byDigest.computeIfAbsent(r.digest(), k -> new ArrayList<>()).add(r);
        }
        for (List<Reply> group : byDigest.values()) {
            Set<Integer> distinct = new TreeSet<>();
            for (Reply r : group) distinct.add(r.replicaId);
            if (distinct.size() >= cluster.f() + 1) {
                Reply rep = group.get(0);
                return new Outcome(rep.ok, rep.errorCode, rep.version, rep.epoch, distinct.size());
            }
        }
        return new Outcome(false, "NO_QUORUM", 0, 0, 0);
    }

    // ---------------------------------------------------------------- reads

    /**
     * Tier 0 (default, one round trip): read from the nearest replica and verify the
     * inclusion proof against a root the client already trusts. A Byzantine replica
     * cannot forge a document -- the proof fails. Its only power is to serve a
     * stale-but-valid document from an older checkpoint (plan section 3.3).
     */
    public byte[] resolveTier0(String did, int nearestReplica) {
        Reply r = cluster.readTier0(did, nearestReplica);
        if (!r.ok) return null;
        if (!isTrustedRoot(r.checkpointRoot)) {
            // An honest replica slightly ahead of (or too far behind) the f+1-agreed root is not a
            // forger. Same rule as Tier 1: a stale or early replica costs a fallback, never an answer.
            tier0Fallbacks.increment();
            return resolveTier1(did, nearestReplica);
        }
        if (!verifiesAgainstRoot(r, r.checkpointRoot)) {
            // Trusted root, bad proof: this IS a forgery.
            throw new ProofFailure("Tier-0 inclusion proof failed for " + did
                    + " from replica " + r.replicaId);
        }
        return r.value;
    }

    /** Tier 1 (freshness-critical): f+1 replicas, majority agreement, epoch >= current. */
    public byte[] resolveTier1(String did, int startReplica) {
        List<Reply> replies = cluster.readTier1(did, startReplica);
        long bar = cluster.trustedEpoch();
        Map<String, List<Reply>> byDigest = new TreeMap<>();
        for (Reply r : replies) {
            if (!r.ok || r.epoch < bar) continue;              // reject stale-but-valid
            if (!verifiesAgainstRoot(r, r.checkpointRoot)) continue;
            byDigest.computeIfAbsent(r.digest(), k -> new ArrayList<>()).add(r);
        }
        for (List<Reply> g : byDigest.values()) {
            if (g.size() >= cluster.f() + 1) return g.get(0).value;   // unanimous among f+1
        }
        // DepSpace section 4.6: if the unordered responses are not all equal, the read-only
        // optimisation does not apply and the normal (ordered) protocol must be executed.
        // A stale replica therefore costs a fallback, never a stale answer.
        return resolveTier2(did);
    }

    /** Tier 2: ordered read through consensus, DepSpace's fallback path. */
    public byte[] resolveTier2(String did) {
        List<Reply> replies = cluster.invokeOrderedSync(Op.resolve(clientId, did));
        Map<String, List<Reply>> byDigest = new TreeMap<>();
        for (Reply r : replies) byDigest.computeIfAbsent(r.digest(), k -> new ArrayList<>()).add(r);
        for (List<Reply> g : byDigest.values()) {
            if (g.size() >= cluster.f() + 1) return g.get(0).ok ? g.get(0).value : null;
        }
        return null;
    }

    /**
     * Revocation status on the Tier-1 freshness path. This is the single safety claim of
     * plan section 13: no in-bound fault scenario may cause a missed revocation to be
     * served here. A suppressing or stale replica is filtered out by the epoch bar, and a
     * "not revoked" answer is only believed if it is backed by an f+1-agreed current epoch.
     */
    public boolean isRevoked(String registryId, String handle, int startReplica) {
        List<Reply> replies = cluster.revocationTier1(registryId, handle, startReplica);
        long bar = cluster.trustedEpoch();
        int revokedVotes = 0, freshVotes = 0;
        for (Reply r : replies) {
            if (!r.ok) continue;
            if (r.revoked) {
                // a positive revocation answer carries an inclusion proof for the REVENTRY
                if (verifiesAgainstRoot(r, r.checkpointRoot)) revokedVotes++;
            } else if (r.epoch >= bar) {
                freshVotes++;
            }
        }
        if (revokedVotes > 0) return true;     // fail-closed: one proved revocation is enough
        return freshVotes == 0;                // no fresh "not revoked" evidence -> treat as revoked
    }

    /** Is this root one of the last ROOT_WINDOW roots agreed by f+1 replicas? */
    private synchronized boolean isTrustedRoot(byte[] root) {
        if (root == null) return false;
        byte[] current = cluster.trustedRoot();
        if (current != null && (recentRoots.isEmpty() || !Arrays.equals(recentRoots.peekLast(), current))) {
            recentRoots.addLast(current);
            while (recentRoots.size() > ROOT_WINDOW) recentRoots.removeFirst();
        }
        for (byte[] t : recentRoots) {
            if (Arrays.equals(t, root)) return true;
        }
        return false;
    }

    private boolean verifiesAgainstRoot(Reply r, byte[] root) {
        if (root == null || r.proof == null || r.leafKey == null) return false;
        byte[] leaf = leafBytesFor(r);
        return Merkle.verify(root, r.leafKey, leaf, r.proof);
    }

    /**
     * The client recomputes the leaf from the value it was served. This is what makes a
     * forged document detectable with no replica cooperation at all.
     */
    private byte[] leafBytesFor(Reply r) {
        if (r.leafKey.startsWith("DIDDOC|")) {
            String[] parts = r.leafKey.split("\\|");
            return vdr.model.Records.DidDoc.leafBytes(parts[1], r.version, r.value);
        }
        if (r.leafKey.startsWith("REVENTRY|")) {
            String[] parts = r.leafKey.split("\\|");
            return Crypto.sha256(parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Crypto.unhex(parts[2]));
        }
        return null;  // REVACC leaves are not client-verifiable without the accumulator value
    }

    /** Result of an accepted (or denied) write. */
    public record Outcome(boolean ok, String errorCode, long version, long epoch, int matchingReplies) {}
}
