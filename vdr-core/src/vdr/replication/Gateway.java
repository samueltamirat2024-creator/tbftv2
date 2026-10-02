package vdr.replication;

import vdr.crypto.Crypto;
import vdr.ops.Op;
import vdr.ops.Reply;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The stateless gateway of Paper 2 section V-E, plan section 9.
 *
 * Responsibilities implemented here:
 *   - signature verification at entry (V2), re-checked in the core because the gateway
 *     is untrusted -- a Byzantine gateway cannot forge an accepted write
 *   - two-path routing: unordered for resolve, ordered for writes
 *   - revocation batch aggregation (V1): pending revokes are flushed when batchSize is
 *     reached OR maxBatchDelay elapses, and submitted as ONE ordered operation
 *
 * It holds no authoritative state, so any instance can serve any request and instances
 * scale horizontally to absorb read fan-out. In the reference deployment this is a Go
 * binary; the logic is mirrored here so the benchmark exercises the same batching path.
 */
public final class Gateway implements AutoCloseable {

    private final Replication cluster;
    private final int batchSize;
    private final long maxBatchDelayMillis;

    private final List<PendingRevoke> pending = new ArrayList<>();
    private long lastFlushMillis = System.currentTimeMillis();
    private long batchesSubmitted = 0;
    private final java.util.concurrent.atomic.AtomicLong batchesCommitted =
            new java.util.concurrent.atomic.AtomicLong();
    private final Thread flusher;
    private volatile boolean running = true;

    public Gateway(Replication cluster, int batchSize, long maxBatchDelayMillis) {
        this.cluster = cluster;
        this.batchSize = batchSize;
        this.maxBatchDelayMillis = maxBatchDelayMillis;
        // maxBatchDelay must be enforced by the clock, not by the arrival of the next
        // request: otherwise a burst whose submitters all block waiting for their own
        // batch can never reach the flush threshold.
        this.flusher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(Math.max(1, maxBatchDelayMillis));
                    flush();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "gateway-batch-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    @Override public void close() {
        running = false;
        flusher.interrupt();
    }

    public synchronized long batchesSubmitted() { return batchesSubmitted; }
    /** Batches whose ordered operation completed without error. */
    public long batchesCommitted() { return batchesCommitted.get(); }
    public int batchSize() { return batchSize; }

    /** Entry-point verification. Rejecting here saves a consensus instance; it never grants one. */
    public boolean verifyAtEntry(Op op, byte[] claimedKey) {
        return Crypto.verify(claimedKey, op.signedBytes(), op.sig);
    }

    public CompletableFuture<List<Reply>> submitWrite(Op op) {
        return cluster.invokeOrdered(op);
    }

    /**
     * V1: accumulate revoke requests, flush on size or delay. Amortising the fixed
     * per-instance consensus cost across many revocations is what makes a burst bounded
     * by batch size rather than by consensus round count.
     */
    public synchronized CompletableFuture<List<Reply>> submitRevoke(
            String clientId, String registryId, String handle, byte[] reasonPayload,
            java.util.function.Function<Op, Op> signer) {

        CompletableFuture<List<Reply>> future = new CompletableFuture<>();
        pending.add(new PendingRevoke(clientId, registryId, handle, reasonPayload, future, signer));
        if (pending.size() >= batchSize
                || System.currentTimeMillis() - lastFlushMillis >= maxBatchDelayMillis) {
            flush();
        }
        return future;
    }

    public synchronized void flush() {
        if (pending.isEmpty()) return;
        // one batch per (client, registry): all handles in a batch share one signature
        String clientId = pending.get(0).clientId;
        String registryId = pending.get(0).registryId;
        byte[] payload = pending.get(0).reasonPayload;
        List<String> handles = new ArrayList<>(pending.size());
        for (PendingRevoke p : pending) handles.add(p.handle);

        Op op = pending.get(0).signer.apply(
                Op.revoke(clientId, registryId, handles, payload, System.nanoTime()));
        List<PendingRevoke> batch = new ArrayList<>(pending);
        pending.clear();
        lastFlushMillis = System.currentTimeMillis();
        batchesSubmitted++;

        cluster.invokeOrdered(op).whenComplete((replies, err) -> {
            if (err == null) batchesCommitted.incrementAndGet();
            for (PendingRevoke p : batch) {
                if (err != null) p.future.completeExceptionally(err);
                else p.future.complete(replies);
            }
        });
    }

    private record PendingRevoke(String clientId, String registryId, String handle,
                                 byte[] reasonPayload, CompletableFuture<List<Reply>> future,
                                 java.util.function.Function<Op, Op> signer) {}
}
