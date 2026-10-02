package vdr.policy;

import vdr.model.Records;
import vdr.ops.Op;

import java.util.Optional;

/**
 * Policy enforcement layer (L3), PEATS-style.
 *
 * DepSpace's policy layer decides on three inputs: invoker identity, operation and
 * arguments, and the records currently in the space. We adopt that mechanism and
 * REJECT the scripting: DepSpace compiles Groovy policies at space-creation time and
 * has to sandbox the class loader against things like System.exit(0). A VDR's policy
 * set is small, stable and security-critical, so policies here are fixed at build time
 * as compiled Java. This is a deliberate divergence, recorded in plan section 6.
 *
 * Every implementation MUST be deterministic and side-effect-free: it runs on every
 * correct replica and must return the same verdict on each (plan section 7).
 */
public interface VdrPolicy {

    /** @return empty if the operation is permitted, otherwise a stable error code. */
    Optional<String> deny(Op op, View view);

    /** Read-only projection of committed state that policies may consult. */
    interface View {
        Optional<Records.DidHead> head(String did);
        Optional<Records.DidDoc> version(String did, long version);
        boolean revoked(String registryId, String handleHash);
        boolean blacklisted(String clientId);
        int maxBatchSize();
    }
}
