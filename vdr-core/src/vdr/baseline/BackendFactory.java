package vdr.baseline;

import java.util.List;

/**
 * Chooses which system a benchmark run measures (baseline plan §8).
 *
 * <p>The Indy backend is loaded reflectively on purpose. It lives in a separate compilation unit
 * because {@code java.lang.foreign} is a preview API in Java 21; vdr-core must stay preview-free so
 * the Tailored BFT VDR builds and runs with no flags. Reflection is the seam that lets one
 * generator class drive both without dragging a preview dependency into the core.
 */
public interface BackendFactory {

    Backend create(int n, int batchSize) throws Exception;

    /** Row label for this system in the evaluation matrix. */
    String displayName();

    /** Rows this run cannot fill, printed as "not measured" so the matrix shape stays visible. */
    List<String> rowsNotMeasuredHere();

    /**
     * True when this run's numbers come from the simulated ordering layer, so RESULTS.md carries
     * the provenance warning. An Indy run is measured against a live pool and must NOT carry it:
     * a reviewer who finds one false disclaimer discounts the rest of the document.
     */
    default boolean usesSimulatedReplication() {
        return false;
    }

    /**
     * Reads {@code -Dvdr.backend}. Defaults to the Tailored BFT VDR.
     *
     * <p>Indy additionally needs:
     * <ul>
     *   <li>{@code -Dindy.lib} — path to libindy_vdr (.so on Linux, .dylib on macOS)
     *   <li>{@code -Dindy.genesis} — pool transactions genesis file
     *   <li>{@code -Dindy.did} — an ENDORSER-or-better submitter DID
     *   <li>{@code -Dindy.revocRegDefId} — revocation registry written during setup
     *   <li>{@code -Dindy.entriesPerTxn} — revoked indices per REVOC_REG_ENTRY (swept; §5)
     * </ul>
     */
    static BackendFactory fromSystemProperties() {
        String which = System.getProperty("vdr.backend", "tailored").toLowerCase();
        return switch (which) {
            case "tailored", "bft" -> tailored();
            case "indy" -> indy();
            default -> throw new IllegalArgumentException(
                    "unknown -Dvdr.backend=" + which + " (expected 'tailored' or 'indy')");
        };
    }

    static BackendFactory tailored() {
        return new BackendFactory() {
            @Override public Backend create(int n, int batchSize) {
                return new TailoredBftBackend(n, batchSize, 20);
            }

            @Override public String displayName() {
                return "Tailored BFT VDR";
            }

            @Override public List<String> rowsNotMeasuredHere() {
                return List.of("Hyperledger Indy -- read-heavy",
                        "Hyperledger Indy -- bursty-revoke");
            }

            @Override public boolean usesSimulatedReplication() {
                return vdr.replication.ReplicationFactory.isSimulated();
            }
        };
    }

    static BackendFactory indy() {
        return new BackendFactory() {
            @Override public Backend create(int n, int batchSize) throws Exception {
                String lib = required("indy.lib");
                String genesis = required("indy.genesis");
                String did = System.getProperty("indy.did", "V4SGRU86Z58d6TV7PBUe6f");
                String revocRegDefId = required("indy.revocRegDefId");
                // Indy's batching knob. Defaults to the Tailored gateway's batchSize so the two
                // systems batch identically unless deliberately swept apart — comparing a batched
                // system against an unbatched one manufactures a speed-up (baseline plan §5).
                int entriesPerTxn = Integer.getInteger("indy.entriesPerTxn", batchSize);

                Class<?> cls = Class.forName("vdr.baseline.indy.IndyBackend");
                return (Backend) cls.getConstructor(String.class, String.class, String.class,
                        String.class, int.class, int.class)
                        .newInstance(lib, genesis, did, revocRegDefId, entriesPerTxn, n);
            }

            @Override public String displayName() {
                return "Hyperledger Indy";
            }

            @Override public List<String> rowsNotMeasuredHere() {
                return List.of("Tailored BFT VDR -- read-heavy",
                        "Tailored BFT VDR -- bursty-revoke");
            }
        };
    }

    private static String required(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "-D" + key + " is required for the Indy backend. See indy-baseline/README.md");
        }
        return v;
    }
}
