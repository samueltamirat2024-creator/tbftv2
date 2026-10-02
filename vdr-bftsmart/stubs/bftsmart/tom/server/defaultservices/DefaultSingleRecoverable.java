package bftsmart.tom.server.defaultservices;

import bftsmart.tom.MessageContext;
import bftsmart.tom.server.Executable;
import bftsmart.tom.server.Recoverable;

/**
 * Stub of bftsmart.tom.server.defaultservices.DefaultSingleRecoverable (BFT-SMaRt 1.2).
 * Type-checking only.
 */
public abstract class DefaultSingleRecoverable implements Executable, Recoverable {
    public abstract byte[] appExecuteOrdered(byte[] command, MessageContext msgCtx);
    public abstract byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx);
    public abstract byte[] getSnapshot();
    public abstract void installSnapshot(byte[] state);
}
