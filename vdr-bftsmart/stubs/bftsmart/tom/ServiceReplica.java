package bftsmart.tom;

import bftsmart.tom.server.Executable;
import bftsmart.tom.server.Recoverable;
import bftsmart.tom.server.Replier;
import bftsmart.tom.server.RequestVerifier;
import bftsmart.tom.util.KeyLoader;
import java.security.Provider;

/**
 * Stub of bftsmart.tom.ServiceReplica (BFT-SMaRt 1.2). Type-checking only. The constructors are
 * exactly 1.2's: a signature that exists only here compiles offline and fails against the jar.
 */
public class ServiceReplica {
    public ServiceReplica(int id, Executable executor, Recoverable recoverer) { }
    public ServiceReplica(int id, Executable executor, Recoverable recoverer, RequestVerifier verifier) { }
    public ServiceReplica(int id, Executable executor, Recoverable recoverer, RequestVerifier verifier,
                          Replier replier) { }
    public ServiceReplica(int id, Executable executor, Recoverable recoverer, RequestVerifier verifier,
                          Replier replier, KeyLoader loader, Provider provider) { }
    public ServiceReplica(int id, String configHome, Executable executor, Recoverable recoverer,
                          RequestVerifier verifier, Replier replier, KeyLoader loader) { }
    public void kill() { }
}
