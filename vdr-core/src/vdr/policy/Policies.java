package vdr.policy;

import vdr.crypto.Crypto;
import vdr.model.Records;
import vdr.ops.Op;

import java.util.*;

/**
 * The policy set of plan section 6, table P1-P6. Evaluated in order on EVERY correct
 * replica, not only at the gateway (Paper 2 section IX-A). Because replica states are
 * equivalent, the layer returns the same verdict everywhere.
 */
public final class Policies {

    private Policies() {}

    public static List<VdrPolicy> standard() {
        return List.of(new P0Blacklist(), new P1SingleHead(), new P2UpdateAuthorised(),
                new P3MonotonicVersions(), new P4RevokeAuthorised(), new P5MonotoneRevocation(),
                new P6BatchWellFormed());
    }

    /** Evaluate the chain; first denial wins, so error codes are deterministic. */
    public static Optional<String> evaluate(List<VdrPolicy> chain, Op op, VdrPolicy.View view) {
        for (VdrPolicy p : chain) {
            Optional<String> d = p.deny(op, view);
            if (d.isPresent()) return d;
        }
        return Optional.empty();
    }

    /** DepSpace Algorithm 3 outcome: a blacklisted client's later requests are ignored. */
    static final class P0Blacklist implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            return v.blacklisted(op.clientId) ? Optional.of("P0_BLACKLISTED") : Optional.empty();
        }
    }

    /** P1: at most one DIDHEAD per did; no re-registration after deactivation. */
    static final class P1SingleHead implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.REGISTER) return Optional.empty();
            Optional<Records.DidHead> h = v.head(op.did);
            if (h.isPresent()) {
                return Optional.of(h.get().deactivated() ? "P1_DID_DEACTIVATED" : "P1_DID_EXISTS");
            }
            if (op.did == null || !op.did.startsWith("did:")) return Optional.of("P1_MALFORMED_DID");
            if (!Crypto.verify(op.controllerPubKey, op.signedBytes(), op.sig)) {
                return Optional.of("P1_BAD_SIGNATURE");
            }
            return Optional.empty();
        }
    }

    /**
     * P2: update accepted only if signed under a key authorised in version latestVersion.
     * This is capability-based, not identity-based -- the right model for SSI, where
     * controllers rotate keys (plan section 6).
     */
    static final class P2UpdateAuthorised implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.UPDATE) return Optional.empty();
            Optional<Records.DidHead> h = v.head(op.did);
            if (h.isEmpty()) return Optional.of("P2_UNKNOWN_DID");
            if (h.get().deactivated()) return Optional.of("P2_DID_DEACTIVATED");
            Optional<Records.DidDoc> cur = v.version(op.did, h.get().latestVersion());
            if (cur.isEmpty()) return Optional.of("P2_MISSING_VERSION");
            if (!Crypto.verify(cur.get().controllerPubKey(), op.signedBytes(), op.sig)) {
                return Optional.of("P2_UNAUTHORISED_KEY");
            }
            return Optional.empty();
        }
    }

    /** P3: versions strictly monotonic, never deleted. Enforces optimistic concurrency. */
    static final class P3MonotonicVersions implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.UPDATE) return Optional.empty();
            Optional<Records.DidHead> h = v.head(op.did);
            if (h.isEmpty()) return Optional.of("P3_UNKNOWN_DID");
            if (op.expectedVersion != h.get().latestVersion()) return Optional.of("P3_VERSION_CONFLICT");
            return Optional.empty();
        }
    }

    /** P4: revoke accepted only under a key authorised for the target registry. */
    static final class P4RevokeAuthorised implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.REVOKE) return Optional.empty();
            String did = Records.didOfRegistry(op.registryId);
            Optional<Records.DidHead> h = v.head(did);
            if (h.isEmpty()) return Optional.of("P4_UNKNOWN_REGISTRY");
            Optional<Records.DidDoc> cur = v.version(did, h.get().latestVersion());
            if (cur.isEmpty()) return Optional.of("P4_MISSING_VERSION");
            if (!Crypto.verify(cur.get().controllerPubKey(), op.signedBytes(), op.sig)) {
                return Optional.of("P4_UNAUTHORISED_KEY");
            }
            return Optional.empty();
        }
    }

    /**
     * P5: a revocation, once committed, is never removed -- accumulator deltas are monotone.
     * Repair (DepSpace Algorithm 3) may delete a malformed REVENTRY payload, but never
     * un-revokes: the handle hash stays in the accumulator.
     */
    static final class P5MonotoneRevocation implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.REPAIR) return Optional.empty();
            // repair is permitted only for an entry that exists
            if (!v.revoked(op.registryId, op.handles.get(0))) return Optional.of("P5_NO_SUCH_ENTRY");
            return Optional.empty();
        }
    }

    /** P6: batch well-formedness -- no duplicate handles, batch size within the configured max. */
    static final class P6BatchWellFormed implements VdrPolicy {
        public Optional<String> deny(Op op, View v) {
            if (op.kind != Op.Kind.REVOKE) return Optional.empty();
            if (op.handles == null || op.handles.isEmpty()) return Optional.of("P6_EMPTY_BATCH");
            if (op.handles.size() > v.maxBatchSize()) return Optional.of("P6_BATCH_TOO_LARGE");
            Set<String> seen = new HashSet<>();
            for (String h : op.handles) {
                if (h == null || h.length() < 32) return Optional.of("P6_LOW_ENTROPY_HANDLE");
                if (!seen.add(h)) return Optional.of("P6_DUPLICATE_HANDLE");
            }
            return Optional.empty();
        }
    }
}
