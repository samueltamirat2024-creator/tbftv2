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
 * <p><b>Tier 0 really is one replica -- in what is counted.</b> A Tier-0 read is answered by one
 * replica and a Tier-1 read by f+1, and only those replicas' replies are accepted (the Collector's
 * allowed set). On the wire, however, every unordered request is SENT to all n replicas. BFT-SMaRt
 * 1.2's client calls waitForChannels(replyQuorum) before each send, so a send to fewer than a
 * quorum leaves channel operations pending and the next send on that proxy stalls for its 1000 ms
 * timeout (1.2, 2.0 and master alike). That stall capped the whole client at about one request per
 * proxy per second. Sending to all n avoids it without changing what the client trusts: replies
 * from replicas outside the allowed set are dropped unread, so latency is still the intended
 * replica's own reply time. The cost is conservative and must be reported: replicas execute each
 * unordered read n times rather than once (Tier 0) or f+1 times (Tier 1), which overstates
 * replica-side read load and so understates Tailored-BFT read throughput.
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
    private final long rootRefreshMs = Long.getLong("vdr.bftsmart.rootRefreshMs", 250L);
    /**
     * Bound on one root refresh. A refresh asks all n replicas and returns at f+1 agreement or when
     * all n answered; with a replica down and replicas on different checkpoints it would otherwise
     * wait the full request timeout (30 s) inside a read.
     */
    private final long rootWaitMs = Long.getLong("vdr.bftsmart.rootWaitMs", 500L);
    private final Object refreshLock = new Object();
    private CompletableFuture<Void> refreshInFlight;

    /** Proxies are not thread-safe, so the open-loop generator borrows one per in-flight call. */
    private final BlockingQueue<AsynchServiceProxy> proxies;
    private final List<AsynchServiceProxy> allProxies = new ArrayList<>();
    private final AsynchServiceProxy rootProxy;
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
        int poolSize = Integer.getInteger("vdr.bftsmart.proxies", 32);
        this.proxies = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            AsynchServiceProxy p = new AsynchServiceProxy(clientIdBase + i, configDir);
            allProxies.add(p);
            proxies.add(p);
        }
        // Root gossip gets its own session. Sharing the pool let a saturated read path starve
        // the refreshes, so the client's trusted root aged and every Tier-0 read fell back to
        // Tier 1 / Tier 2 -- turning overload into a collapse. Only one refresh is ever in flight
        // (refreshLock), so one proxy is enough.
        this.rootProxy = new AsynchServiceProxy(clientIdBase + poolSize, configDir);
        allProxies.add(rootProxy);
        refreshRoot(timeoutMs);
        this.rootRefresher = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(rootRefreshMs);
                    refreshTrustedRootNow();
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
        return String.format("BFT-SMaRt (n=%d, f=%d, config=%s, %d proxies + 1 root-gossip proxy, "
                + "root refresh %d ms + on demand)", n, f, configDir, allProxies.size() - 1, rootRefreshMs);
    }

    // ------------------------------------------------------------- ordered path

    /**
     * Ordered calls wait for consensus, so each one blocks a thread for a full round. The old code
     * ran them on {@code ForkJoinPool.commonPool()}, whose parallelism is (vCPUs - 1): 3 threads on
     * a 4 vCPU runner, 1 on a 2 vCPU one. Every ordered write, every revocation batch and all 400
     * population registers queued behind those few threads, which capped write throughput at
     * ~3 / consensus latency independently of the cluster. A dedicated cached pool removes the
     * cap; the real limit is now the proxy pool (-Dvdr.bftsmart.proxies), as intended. Platform
     * threads, not virtual ones: BFT-SMaRt's client blocks inside synchronized sections, which
     * would pin virtual-thread carriers.
     */
    private static final java.util.concurrent.ExecutorService ORDERED_CALLS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "vdr-ordered");
                t.setDaemon(true);
                return t;
            });

    @Override
    public CompletableFuture<List<Reply>> invokeOrdered(Op op) {
        byte[] request = Codec.encode(op);       // encode on the caller: Op is not shared state
        return CompletableFuture.supplyAsync(() ->
                invoke(request, allTargets(), TOMMessageType.ORDERED_REQUEST, f + 1), ORDERED_CALLS);
    }

    // ----------------------------------------------------------- unordered path

    @Override
    public Reply readTier0(String did, int preferredReplica) {
        int[] target = {Math.floorMod(preferredReplica, n)};
        List<Reply> replies = invoke(Codec.encode(Op.resolve("tier0", did)), allTargets(), target,
                TOMMessageType.UNORDERED_REQUEST, 1, timeoutMs);
        if (replies.isEmpty()) {
            throw new IllegalStateException("no Tier-0 reply from replica " + target[0]
                    + " within " + timeoutMs + " ms");
        }
        return replies.get(0);
    }

    @Override
    public List<Reply> readTier1(String did, int startReplica) {
        return invoke(Codec.encode(Op.resolve("tier1", did)), allTargets(),
                targets(startReplica, f + 1), TOMMessageType.UNORDERED_REQUEST, f + 1, timeoutMs);
    }

    @Override
    public List<Reply> revocationTier1(String registryId, String handle, int startReplica) {
        return invoke(Codec.encode(Op.revocationStatus("rev1", registryId, handle)), allTargets(),
                targets(startReplica, f + 1), TOMMessageType.UNORDERED_REQUEST, f + 1, timeoutMs);
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
    @Override
    public void refreshTrustedRootNow() {
        CompletableFuture<Void> mine = null, theirs;
        synchronized (refreshLock) {
            if (refreshInFlight == null) {
                refreshInFlight = mine = new CompletableFuture<>();
            }
            theirs = refreshInFlight;
        }
        if (mine == null) {
            try {
                theirs.get(rootWaitMs * 2, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // a slow refresh costs this read a Tier-1 fallback, never a wrong answer
            }
            return;
        }
        try {
            refreshRoot(rootWaitMs);
        } catch (RuntimeException e) {
            System.err.println("checkpoint-root refresh failed: " + e);
        } finally {
            synchronized (refreshLock) {
                refreshInFlight = null;
            }
            mine.complete(null);
        }
    }

    private void refreshRoot(long waitMs) {
        int[] all = allTargets();
        List<Reply> replies;
        synchronized (rootProxy) {
            replies = invokeOn(rootProxy, Codec.encode(Op.root("root")), all, all,
                    TOMMessageType.UNORDERED_REQUEST, f + 1, waitMs);
        }
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
        List<Reply> replies = invoke(Codec.encode(Op.root("root")), allTargets(), new int[] {replica},
                TOMMessageType.UNORDERED_REQUEST, 1, waitMs);
        return replies.isEmpty() ? null : replies.get(0);
    }

    @Override
    public void checkpointEverywhere() {
        // Ordered, so every replica cuts at the same sequence number and the roots stay identical.
        invokeOrderedSync(Op.checkpoint("bench", nonces.getAndIncrement()));
        refreshRoot(timeoutMs);
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
        return invoke(request, targets, targets, type, needed, timeoutMs);
    }

    /**
     * @param sendTo replicas the request goes to on the wire
     * @param accept replicas whose replies are counted; any other reply is dropped unread. See the
     *     class doc for why unordered reads send to all n but accept only 1 or f+1.
     */
    private List<Reply> invoke(byte[] request, int[] sendTo, int[] accept, TOMMessageType type,
                               int needed, long timeoutMs) {
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
        try {
            return invokeOn(proxy, request, sendTo, accept, type, needed, timeoutMs);
        } finally {
            proxies.add(proxy);
        }
    }

    /** One request on a proxy the caller already owns exclusively. */
    private List<Reply> invokeOn(AsynchServiceProxy proxy, byte[] request, int[] sendTo, int[] accept,
                                 TOMMessageType type, int needed, long timeoutMs) {
        Collector collector = new Collector(accept, needed);
        int operationId = -1;
        try {
            operationId = proxy.invokeAsynchRequest(request, sendTo, collector, type);
            collector.await(timeoutMs);
            return collector.replies();
        } finally {
            if (operationId >= 0) {
                proxy.cleanAsynchRequest(operationId);
            }
        }
    }

    /** Gathers one reply per replica and releases the caller once enough of them agree. */
    private static final class Collector implements ReplyListener {

        private final int expected;
        private final int needed;
        private final java.util.Set<Integer> allowed = new java.util.HashSet<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final Map<Integer, Reply> bySender = new TreeMap<>();
        private final Map<String, Integer> byDigest = new TreeMap<>();

        Collector(int[] accept, int needed) {
            for (int a : accept) allowed.add(a);
            this.expected = allowed.size();
            this.needed = needed;
        }

        @Override public void reset() {
            synchronized (this) {
                bySender.clear();
                byDigest.clear();
            }
        }

        @Override public void replyReceived(RequestContext context, TOMMessage reply) {
            if (!allowed.contains(reply.getSender())) {
                return;                                       // sent to, but not asked: not counted
            }
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
