package bftsmart.communication.client;

import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;

/** Stub of bftsmart.communication.client.ReplyListener (BFT-SMaRt 1.2). Type-checking only. */
public interface ReplyListener {
    void reset();
    void replyReceived(RequestContext context, TOMMessage reply);
}
