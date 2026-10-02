package vdr.replication.bftsmart;

import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.replication.Replication;
import vdr.serialization.Codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client side of the real replication layer (BFT-SMaRt implementation plan WP4).
 *
 * <p><b>Why AsynchServiceProxy and not ServiceProxy.</b> {@code ServiceProxy.invokeOrdered} hides
 * the individual replies and hands back one "agreed" answer. {@code VdrClient} does its own f+1
 * matching and its own Merkle proof verification, and that is the check which makes a Byzantine
 * replica detectable at all. Collapsing the replies before the client sees them would remove it,
 * so the asynchronous proxy is used and every reply is kept.
 *
 * <p><b>Tier 0 really is one replica.</b> Reads are sent to an explicit target list: one replica
 * for Tier 0, f+1 for Tier 1. Sending a Tier-0 read to all n would measure a fan-out the design
 * does not claim and would make the M4 gate meaningless.
 *
 * <p><b>Roots are gossiped, not polled per read.</b> {@code VdrClient} consults the trusted root on
 * every Tier-0 verification. Against the simulator that reads memory; over a network it would be a
 * round trip per read. A background refresher keeps the f+1-agreed root current instead, which is
 * the signed-checkpoint gossip of plan §3.3 — clients are not asked to run consensus.
 */
public final class BftSmartCluster implements Replication {

    private final int n;
    private final int f;
    private final long timeoutMs = Long.getLong("vdr.bftsmart.timeoutMs", 30_000L);
    private final long rootRefreshMs = Long.getLong("vdr.bftsmart.rootRefreshMs", 1_000L);

    /** Proxies are not thread-safe, so the open-loop generator borrows one per in-flight call. */
    private final BlockingQueue<AsynchServiceProxy> proxies;
    private final List<AsynchServiceProxy> allProxies = new ArrayList<>();
    private final String configDir = System.getProperty("vdr.bftsmart.config", "config");

    private volatile byte[] cachedRoot;
    private volatile long cachedEpoch;
    private final Thread rootRefresher;
    private volatile boolean running = true;
    private final AtomicLong nonces = new AtomicLong(1);

    /**
     * @param n replicas, n = 3f+1
     * @param clientIdBase first BFT-SMaRt client id; each proxy takes the next one. Client ids must
     *     be unique across every live client process, or replies interleave between sessions.
     */
    public BftSmartCluster(int n, int clientIdBase) {
        this.n = n;
        this.f = (n - 1) / 3;
        int poolSize = Integer.getInteger("vdr.bftsmart.proxies", 16);
        this.proxies = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            AsynchServiceProxy p = new AsynchServiceProxy(clientIdBase + i, configDir);
            allProxies.add(p);
            proxies.add(p);
        }
        refreshRoot();
        this.rootRefresher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(rootRefreshMs);
                    refreshRoot();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // A refresh failure must not kill the client: the cached root simply ages,
                    // and an aged root costs a Tier-1 fallback rather than a wrong answer.
                    System.err.println("checkpoint-root refresh failed: " + e);
                }
            }
        }, "vdr-root-refresher");
        this.rootRefresher.setDaemon(true);
        this.rootRefresher.start();
    }

    @Override public int n() { return n; }

    @Override public int f() { return f; }

    @Override public boolean simulated() { return false; }

    @Override public String describe() {
        return String.format("BFT-SMaRt (n=%d, f=%d, config=%s, %d proxies, root refresh %d ms)",
                n, f, configDir, allProxies.size(), rootRefreshMs);
    }

    // ------------------------------------------------------------- ordered path

    @Override
    public CompletableFuture<List<Reply>> invokeOrdered(Op op) {
        return CompletableFuture.supplyAsync(() ->
                invoke(Codec.encode(op), allTargets(), TOMMessageType.ORDERED_REQUEST, f + 1));
    }

    // ----------------------------------------------------------- unordered path

    @Override
    public Reply readTier0(String did, int preferredReplica) {
        int[] target = {Math.floorMod(preferredReplica, n)};
        List<Reply> replies = invoke(Codec.encode(Op.resolve("tier0", did)), target,
                TOMMessageType.UNORDERED_REQUEST, 1);
        if (replies.isEmpty()) {
            throw new IllegalStateException("no Tier-0 reply from replica " + target[0]
                    + " within " + timeoutMs + " ms");
        }
        return replies.get(0);
    }

    @Override
    public List<Reply> readTier1(String did, int startReplica) {
        return invoke(Codec.encode(Op.resolve("tier1", did)), targets(startReplica, f + 1),
                TOMMessageType.UNORDERED_REQUEST, f + 1);
    }

    @Override
    public List<Reply> revocationTier1(String registryId, String handle, int startReplica) {
        return invoke(Codec.encode(Op.revocationStatus("rev1", registryId, handle)),
                targets(startReplica, f + 1), TOMMessageType.UNORDERED_REQUEST, f + 1);
    }

    @Override public byte[] trustedRoot() {
        byte[] r = cachedRoot;
        return r == null ? null : r.clone();
    }

    @Override public long trustedEpoch() {
        return cachedEpoch;
    }

    /**
     * Asks every replica for its current checkpoint root and keeps the one f+1 of them agree on.
     * A root backed by f+1 replicas is backed by at least one honest replica, which is what makes
     * it safe to verify Tier-0 proofs against.
     */
    private void refreshRoot() {
        List<Reply> replies = invoke(Codec.encode(Op.root("root")), allTargets(),
                TOMMessageType.UNORDERED_REQUEST, f + 1);
        Map<String, Integer> tally = new TreeMap<>();
        Map<String, byte[]> byHex = new TreeMap<>();
        long[] epochs = new long[replies.size()];
        int i = 0;
        for (Reply r : replies) {
            if (r.checkpointRoot != null) {
                String hex = vdr.crypto.Crypto.hex(r.checkpointRoot);
                tally.merge(hex, 1, Integer::sum);
                byHex.put(hex, r.checkpointRoot);
            }
            epochs[i++] = r.epoch;
        }
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() >= f + 1) {
                cachedRoot = byHex.get(e.getKey());
                break;
            }
        }
        if (replies.size() >= f + 1) {
            java.util.Arrays.sort(epochs, 0, replies.size());
            // the (f+1)-th largest is backed by at least f+1 replicas, hence by an honest one
            cachedEpoch = epochs[replies.size() - (f + 1)];
        }
    }

    /**
     * One replica's own checkpoint root, unagreed. For the real-engine gates only: a client acts
     * on f+1-agreed roots, but root equality across ALL replicas is the property M3 checks.
     *
     * @return the reply, or null if the replica did not answer within {@code waitMs}
     */
    public Reply rootOf(int replica, long waitMs) {
        List<Reply> replies = invoke(Codec.encode(Op.root("root")), new int[] {replica},
                TOMMessageType.UNORDERED_REQUEST, 1, waitMs);
        return replies.isEmpty() ? null : replies.get(0);
    }

    @Override
    public void checkpointEverywhere() {
        // Ordered, so every replica cuts at the same sequence number and the roots stay identical.
        invokeOrderedSync(Op.checkpoint("bench", nonces.getAndIncrement()));
        refreshRoot();
    }

    // ------------------------------------------------------------------ plumbing

    private int[] allTargets() {
        int[] t = new int[n];
        for (int i = 0; i < n; i++) t[i] = i;
        return t;
    }

    private int[] targets(int start, int count) {
        int[] t = new int[count];
        for (int i = 0; i < count; i++) t[i] = Math.floorMod(start + i, n);
        return t;
    }

    /**
     * Sends one request and collects the individual replies.
     *
     * <p>Returns as soon as {@code needed} replies agree by digest, which is the point at which
     * the client can act; otherwise it waits for every target, then for the timeout. Whatever has
     * arrived is returned either way, and {@code VdrClient} decides whether that is a quorum — the
     * decision belongs there, not here.
     */
    private List<Reply> invoke(byte[] request, int[] targets, TOMMessageType type, int needed) {
        return invoke(request, targets, type, needed, timeoutMs);
    }

    private List<Reply> invoke(byte[] request, int[] targets, TOMMessageType type, int needed,
                               long timeoutMs) {
        AsynchServiceProxy proxy;
        try {
            proxy = proxies.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a BFT-SMaRt proxy", e);
        }
        if (proxy == null) {
            throw new IllegalStateException("no BFT-SMaRt proxy free within " + timeoutMs
                    + " ms; raise -Dvdr.bftsmart.proxies above " + allProxies.size());
        }
        Collector collector = new Collector(targets.length, needed);
        int operationId = -1;
        try {
            operationId = proxy.invokeAsynchRequest(request, targets, collector, type);
            collector.await(timeoutMs);
            return collector.replies();
        } finally {
            if (operationId >= 0) {
                proxy.cleanAsynchRequest(operationId);
            }
            proxies.add(proxy);
        }
    }

    /** Gathers one reply per replica and releases the caller once enough of them agree. */
    private static final class Collector implements ReplyListener {

        private final int expected;
        private final int needed;
        private final CountDownLatch done = new CountDownLatch(1);
        private final Map<Integer, Reply> bySender = new TreeMap<>();
        private final Map<String, Integer> byDigest = new TreeMap<>();

        Collector(int expected, int needed) {
            this.expected = expected;
            this.needed = needed;
        }

        @Override public void reset() {
            synchronized (this) {
                bySender.clear();
                byDigest.clear();
            }
        }

        @Override public void replyReceived(RequestContext context, TOMMessage reply) {
            byte[] content = reply.getContent();
            if (content == null || content.length == 0) {
                return;
            }
            Reply decoded;
            try {
                decoded = Codec.decodeReply(content);
            } catch (RuntimeException e) {
                // A reply this client cannot parse is a reply it cannot count. Dropping it is the
                // same outcome as a silent replica, which the quorum rule already tolerates.
                return;
            }
            boolean enough;
            synchronized (this) {
                if (bySender.putIfAbsent(reply.getSender(), decoded) != null) {
                    return;                                   // one vote per replica
                }
                int agreeing = byDigest.merge(decoded.digest(), 1, Integer::sum);
                enough = agreeing >= needed || bySender.size() >= expected;
            }
            if (enough) {
                done.countDown();
            }
        }

        void await(long millis) {
            try {
                done.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized List<Reply> replies() {
            return new ArrayList<>(bySender.values());
        }
    }

    @Override
    public void close() {
        running = false;
        rootRefresher.interrupt();
        for (AsynchServiceProxy p : allProxies) {
            try {
                p.close();
            } catch (RuntimeException e) {
                System.err.println("closing a BFT-SMaRt proxy failed: " + e);
            }
        }
    }
}
