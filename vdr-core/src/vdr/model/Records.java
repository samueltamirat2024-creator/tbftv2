package vdr.model;

import vdr.crypto.Crypto;

import java.util.List;

/**
 * The four record families of plan section 2.1, with their protection type vectors.
 *
 *  DID head        <DIDHEAD, did, latestVersion, stateRootEpoch>              <PU,PU,PU,PU>
 *  DID doc version <DIDDOC, did, version, docBytes, keySetHash, prevHash>     <PU,PU,PU,PU,PU,PU>
 *  Revocation acc. <REVACC, registryId, epoch, accumulator, deltaDigest>      <PU,PU,PU,PU,PU>
 *  Revocation entry<REVENTRY, registryId, credentialHandle, reasonMetadata>   <PU,PU,CO,PR>
 *
 * DID documents are public by construction: they carry the keys verifiers must fetch,
 * so making them PU keeps resolve cheap AND keeps replica state identical (plan section 7).
 */
public final class Records {

    private Records() {}

    public static final List<Protection> V_DIDHEAD  = List.of(Protection.PU, Protection.PU, Protection.PU, Protection.PU);
    public static final List<Protection> V_DIDDOC   = List.of(Protection.PU, Protection.PU, Protection.PU, Protection.PU, Protection.PU, Protection.PU);
    public static final List<Protection> V_REVACC   = List.of(Protection.PU, Protection.PU, Protection.PU, Protection.PU, Protection.PU);
    public static final List<Protection> V_REVENTRY = List.of(Protection.PU, Protection.PU, Protection.CO, Protection.PR);

    /** Head of a DID: pointer to the latest version. */
    public record DidHead(String did, long latestVersion, long stateRootEpoch, boolean deactivated) {
        public List<String> tupleFields() {
            return java.util.Arrays.asList("DIDHEAD", did, Long.toString(latestVersion), Long.toString(stateRootEpoch));
        }
    }

    /** One immutable version of a DID document. Never deleted (P3, auditability). */
    public record DidDoc(String did, long version, byte[] docBytes, byte[] controllerPubKey,
                         String keySetHash, String prevHash) {

        public static String hashKeySet(byte[] pubKey) {
            return Crypto.hex(Crypto.sha256(pubKey));
        }

        public List<String> tupleFields() {
            return java.util.Arrays.asList("DIDDOC", did, Long.toString(version),
                    Crypto.hex(Crypto.sha256(docBytes)), keySetHash, prevHash);
        }

        /**
         * Leaf content for the Merkle state root: PUBLIC PROJECTION ONLY.
         *
         * Deliberately limited to (did, version, H(docBytes)) so that a client holding
         * only the served document can rebuild the leaf and verify the inclusion proof
         * without any replica cooperation. The controller keys are inside docBytes, so
         * their integrity follows from H(docBytes); keySetHash and prevHash stay in the
         * log for auditability but are not needed on the read path.
         */
        public byte[] leafBytes() {
            return leafBytes(did, version, docBytes);
        }

        public static byte[] leafBytes(String did, long version, byte[] docBytes) {
            return Crypto.sha256(
                    "DIDDOC".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    did.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Crypto.longToBytes(version),
                    Crypto.sha256(docBytes));
        }
    }

    /**
     * Template for resolve: <DIDDOC, did, *, *, *, *>.
     * The wildcards are the DepSpace template mechanism, not a formatting artefact.
     */
    public static List<String> resolveTemplate(String did) {
        return java.util.Arrays.asList("DIDDOC", did, null, null, null, null);
    }

    /** Template for a revocation-status check: <REVENTRY, registryId, H(handle), PR>. */
    public static List<String> revocationTemplate(String registryId, String credentialHandle) {
        return java.util.Arrays.asList("REVENTRY", registryId, credentialHandle, null);
    }

    /** registryId is derived from the DID, so revoke authorisation reduces to DID key control. */
    public static String registryIdFor(String did) {
        return did + "#rev";
    }

    public static String didOfRegistry(String registryId) {
        int i = registryId.indexOf("#rev");
        return i < 0 ? registryId : registryId.substring(0, i);
    }
}
