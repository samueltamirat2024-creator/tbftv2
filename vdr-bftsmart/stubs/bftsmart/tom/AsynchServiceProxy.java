package bftsmart.tom;

import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.core.messages.TOMMessageType;

/** Stub of bftsmart.tom.AsynchServiceProxy (BFT-SMaRt 1.2). Type-checking only. */
public class AsynchServiceProxy {
    public AsynchServiceProxy(int processId) { }
    public AsynchServiceProxy(int processId, String configHome) { }

    public int invokeAsynchRequest(byte[] request, ReplyListener listener, TOMMessageType type) {
        throw new UnsupportedOperationException("stub");
    }

    public int invokeAsynchRequest(byte[] request, int[] targets, ReplyListener listener,
                                   TOMMessageType type) {
        throw new UnsupportedOperationException("stub");
    }

    public void cleanAsynchRequest(int operationId) { throw new UnsupportedOperationException("stub"); }

    public void close() { throw new UnsupportedOperationException("stub"); }
}
