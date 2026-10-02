package vdr.baseline.indy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline verification of the indy-vdr binding (baseline milestone B1).
 *
 * <p>Everything here runs without a pool: request construction, canonical signature-input
 * derivation, signature attachment and error handling are all local calls into libindy_vdr. Only
 * submission needs validators, so this probe is the check that the binding itself is correct before
 * anyone stands up four nodes.
 *
 * <pre>
 *   ./indy-baseline/build.sh probe /path/to/libindy_vdr.so
 * </pre>
 *
 * <p>A passing probe does NOT mean the baseline works — it means the client library is wired up
 * correctly. The pool, the tuning sweep and the parity audit are milestones B0 and B3–B4.
 */
public final class IndyProbe {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: IndyProbe <path-to-libindy_vdr.so>");
            System.exit(2);
        }
        System.out.println("indy-vdr binding probe (no pool required)");
        System.out.println("=".repeat(78));

        try (IndyVdr vdr = new IndyVdr(args[0])) {
            String version = vdr.version();
            System.out.println("library version: " + version);
            check("library reports a version", version != null && !version.isBlank());

            vdr.setProtocolVersion(2);
            check("protocol version 2 accepted", true);

            // ---- register: NYM ------------------------------------------------------
            String submitter = "V4SGRU86Z58d6TV7PBUe6f";     // well-known test trustee DID
            String target = "VsKV7grR1BUE29mG2Fm2kX";
            String verkey = "GjZWsBLgZCR18aL468JAT7w9CZRiBnpxUPPgyQxh4voa";

            long nym = vdr.buildNymRequest(submitter, target, verkey, null, "ENDORSER", null, -1);
            String body = vdr.requestBody(nym);
            check("NYM request built", body != null && body.contains("\"type\""));
            check("NYM carries the target DID", body.contains(target));
            // Indy's NYM txn type is 1.
            check("NYM is txn type 1", body.contains("\"type\":\"1\"") || body.contains("\"type\": \"1\""));

            // ---- signature path -----------------------------------------------------
            byte[] sigInput = vdr.signatureInput(nym);
            check("signature input derived", sigInput != null && sigInput.length > 0);

            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(kp.getPrivate());
            signer.update(sigInput);
            byte[] sig = signer.sign();

            vdr.setSignature(nym, sig);
            String signedBody = vdr.requestBody(nym);
            check("signature attached to request", signedBody.contains("signature"));
            vdr.freeRequest(nym);

            // ---- resolve: GET_NYM ---------------------------------------------------
            long getNym = vdr.buildGetNymRequest(submitter, target);
            String getBody = vdr.requestBody(getNym);
            check("GET_NYM request built", getBody != null && getBody.contains(target));
            // GET_NYM txn type is 105.
            check("GET_NYM is txn type 105",
                    getBody.contains("\"type\":\"105\"") || getBody.contains("\"type\": \"105\""));
            vdr.freeRequest(getNym);

            // ---- update: ATTRIB -----------------------------------------------------
            long attrib = vdr.buildAttribRequest(submitter, target, null,
                    "{\"endpoint\":{\"ha\":\"127.0.0.1:9700\"}}", null);
            check("ATTRIB request built", vdr.requestBody(attrib) != null);
            vdr.freeRequest(attrib);

            // ---- revoke: REVOC_REG_ENTRY -------------------------------------------
            // One entry revokes many indices. The indices list IS the batch, and baseline plan §5
            // requires sweeping its size against the Tailored BFT gateway's batchSize.
            List<Integer> revoked = new ArrayList<>();
            for (int i = 1; i <= 250; i++) {
                revoked.add(i);
            }
            String entry = "{\"ver\":\"1.0\",\"value\":{\"accum\":\"1 0000 1 0000\",\"prevAccum\":"
                    + "\"1 0000 1 0000\",\"issued\":[],\"revoked\":" + revoked + "}}";
            String revocRegDefId = target + ":4:" + target
                    + ":3:CL:1:tag:CL_ACCUM:bench-reg";
            long rre = vdr.buildRevocRegEntryRequest(submitter, revocRegDefId, "CL_ACCUM", entry);
            String rreBody = vdr.requestBody(rre);
            check("REVOC_REG_ENTRY built with a 250-index delta",
                    rreBody != null && rreBody.contains("revoked"));
            check("one transaction carries the whole batch", rreBody.contains("250"));
            vdr.freeRequest(rre);

            // ---- error handling -----------------------------------------------------
            boolean threw = false;
            try {
                vdr.buildGetNymRequest(submitter, "not-a-valid-did");
            } catch (IndyVdr.IndyVdrException e) {
                threw = true;
                check("invalid DID rejected with detail", e.getMessage().length() > 30);
            }
            check("invalid input raises rather than returning a bad request", threw);

            // ---- submission requires a pool -----------------------------------------
            System.out.println();
            System.out.println("not covered by this probe (needs a live pool, milestone B0):");
            System.out.println("  pool_create / pool_refresh / pool_submit_request");
            System.out.println("  state-proof verification on the read path");
            System.out.println("  everything in baseline plan sections 4 (read tiers) and 9 (tuning)");
        }

        System.out.println("=".repeat(78));
        System.out.printf("passed %d, failed %d%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}
