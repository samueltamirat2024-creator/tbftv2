package vdr.replication;

/**
 * Chooses the L1 engine: {@code -Dvdr.replication=simulated|bftsmart} (BFT-SMaRt implementation
 * plan WP1). Default is {@code simulated} until the BFT-SMaRt gates of WP8 pass.
 *
 * <p>The BFT-SMaRt adapter lives in the separate {@code vdr-bftsmart} module and is loaded
 * reflectively, exactly as the Indy backend is: vdr-core must keep compiling and running with no
 * external dependency, so a hard reference to a class that needs the bft-smart jar cannot appear
 * here. If the module was not built, the error says so rather than failing with a bare
 * NoClassDefFoundError.
 */
public final class ReplicationFactory {

    public static final String BFTSMART_CLASS = "vdr.replication.bftsmart.BftSmartCluster";

    private ReplicationFactory() {}

    /** The engine this JVM is configured to use, without creating one. */
    public static String selected() {
        return System.getProperty("vdr.replication", "simulated").toLowerCase();
    }

    public static boolean isSimulated() {
        return !"bftsmart".equals(selected());
    }

    /**
     * @param n replicas, n = 3f+1
     * @param clientId distinct per client process AND per concurrent proxy: BFT-SMaRt keys its
     *     session by client id, so two clients sharing one id interleave their replies
     */
    public static Replication create(int n, int clientId) {
        String which = selected();
        return switch (which) {
            case "simulated", "sim" -> SimulatedCluster.standard(n);
            case "bftsmart", "bft-smart" -> createBftSmart(n, clientId);
            default -> throw new IllegalArgumentException(
                    "unknown -Dvdr.replication=" + which + " (expected 'simulated' or 'bftsmart')");
        };
    }

    private static Replication createBftSmart(int n, int clientId) {
        try {
            Class<?> cls = Class.forName(BFTSMART_CLASS);
            return (Replication) cls.getConstructor(int.class, int.class).newInstance(n, clientId);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "-Dvdr.replication=bftsmart needs the vdr-bftsmart module and the bft-smart "
                    + "jar on the classpath. Build it with ./vdr-bftsmart/build.sh and run through "
                    + "that script, which puts both on the classpath.", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("could not start the BFT-SMaRt client: " + cause, cause);
        }
    }
}
