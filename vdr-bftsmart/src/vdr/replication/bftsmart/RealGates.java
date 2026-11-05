package vdr.replication.bftsmart;

import vdr.client.VdrClient;
import vdr.crypto.Crypto;
import vdr.model.Records;
import vdr.ops.Op;
import vdr.ops.Reply;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M3, M4 and M6 against the real BFT-SMaRt cluster.
 *
 * <p>{@code vdr.test.Gates} runs every gate on {@code SimulatedCluster}, which has no leader, no
 * view change, no state transfer and no network. These are the gates whose claims depend on
 * exactly those things, re-asked of four real replica processes. Faults are real: a stopped
 * container, a restarted JVM with empty memory, a replica started in a Byzantine mode.
 *
 * <p>Each invocation is ONE phase in a fresh JVM, driven by {@code vdr-bftsmart/real-gates.sh},
 * which owns the containers (stop the leader, restart it, choose the Byzantine replica). A phase
 * never kills anything itself. Every phase needs its own {@code -Dvdr.clientIdBase}: BFT-SMaRt
 * keys client sessions by id, and a new JVM reusing an id restarts that session's sequence
 * numbers, which the replicas then discard as duplicates.
 *
 * <pre>
 *   ready                         every replica answers
 *   m3-load [ops]                 roots identical on all 4 after [ops] ordered writes
 *   m3-leaderdown [ops]           replica 0 (the view-0 leader) is DOWN: writes must still commit
 *   m3-verify                     replica 0 is back with empty memory: all 4 roots must reconverge
 *   m4                            unordered vs ordered resolve p50
 *   m6-forge                      replica 2 runs FORGE_DOC
 *   m6-stale                      replica 1 runs STALE_READ
 *   m6-suppress [rounds]          replica 3 runs the behaviour real-gates.sh started it with
 * </pre>
 */
public final class RealGates {

    private static final int N = 4;
    private static final long READY_MS = Long.getLong("vdr.gates.readyMs", 120_000L);
    private static final long CONVERGE_MS = Long.getLong("vdr.gates.convergeMs", 180_000L);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        if (args.length < 1) usage();
        String phase = args[0];
        int arg = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        int base = Integer.getInteger("vdr.clientIdBase", 1001);

        switch (phase) {
            case "ready" -> ready(base);
            case "m3-load" -> m3Load(base, arg > 0 ? arg : 10_000);
            case "m3-leaderdown" -> m3LeaderDown(base, arg > 0 ? arg : 1_200);
            case "m3-verify" -> m3Verify(base);
            case "m4" -> m4(base);
            case "m6-forge" -> m6Forge(base);
            case "m6-stale" -> m6Stale(base);
            case "m6-suppress" -> m6Suppress(base, arg > 0 ? arg : 25);
            default -> usage();
        }
        System.out.printf("phase %s: passed %d, failed %d%n", phase, passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    // --------------------------------------------------------------------- ready

    private static void ready(int base) {
        // Answering a ROOT query is not participating: a replica that lost its startup key
        // exchange with the leader still answers unordered reads, from a store stuck at seq 0.
        // So an ordered checkpoint must land on every replica, at the same seq and root.
        gate("--", "Do all " + N + " replicas take part in ordering?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                byte[] before = c.rootOf(0, 5_000).checkpointRoot;
                int[] w = load(c, "ready", 1, 1);
                check(w[0] == 1, "a single ordered write did not commit");
                c.checkpointEverywhere();
                Map<Integer, Reply> roots = converge(c, all(), 30_000, null);
                assertAllEqual(roots);
                check(!Arrays.equals(roots.get(0).checkpointRoot, before),
                        "roots never moved off genesis: the write and checkpoint did not execute");
            }
        });
    }

    // ------------------------------------------------------------------------ M3

    private static void m3Load(int base, int ops) {
        gate("M3", "Identical checkpoint roots on all 4 real replicas after " + ops + " ordered ops?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                int[] outcome = load(c, "load", ops, 16);
                System.out.printf("        %d ops: %d committed ok, %d denied by f+1, %d without quorum%n",
                        ops, outcome[0], outcome[1], outcome[2]);
                check(outcome[2] == 0, outcome[2] + " ops never reached f+1 matching replies");
                check(outcome[0] > ops / 2, "most of the load was denied; the roots would compare "
                        + "near-empty state");
                c.checkpointEverywhere();
                Map<Integer, Reply> roots = converge(c, all(), CONVERGE_MS, null);
                assertAllEqual(roots);
                check(Arrays.equals(c.trustedRoot(), roots.get(0).checkpointRoot),
                        "the f+1-agreed root a client trusts is not the root every replica holds");
            }
        });
    }

    /**
     * The script has stopped replica 0, which leads view 0. Writes can only commit once the others
     * time out on it and install a new leader, so the first write's latency shows the change.
     * {@code ops} is sent one at a time, so it is also the number of consensus instances: above
     * system.totalordermulticast.checkpoint_period (1000), replica 0 cannot catch up from the log
     * alone and m3-verify exercises getSnapshot/installSnapshot.
     */
    private static void m3LeaderDown(int base, int ops) {
        gate("M3", "With the view-0 leader down, do writes commit and do the 3 live roots agree?", () -> {
            try (BftSmartCluster c = open(base, live(1, 2, 3))) {
                long t0 = System.nanoTime();
                int[] first = load(c, "down-first", 1, 1);
                double firstMs = (System.nanoTime() - t0) / 1e6;
                System.out.printf("        first write with leader down: %.0f ms (request timeout "
                        + "is 2000 ms; a new leader must be installed before it can commit)%n", firstMs);
                check(first[0] == 1, "first write after the leader stopped did not commit");

                long t1 = System.nanoTime();
                int[] outcome = load(c, "down", ops, 1);
                System.out.printf("        %d sequential ops under the new leader in %.1f s: "
                                + "%d ok, %d denied, %d without quorum%n", ops,
                        (System.nanoTime() - t1) / 1e9, outcome[0], outcome[1], outcome[2]);
                check(outcome[2] == 0, outcome[2] + " ops never reached f+1 matching replies "
                        + "after the leader change");

                c.checkpointEverywhere();
                Map<Integer, Reply> roots = converge(c, live(1, 2, 3), CONVERGE_MS, null);
                assertAllEqual(roots);
            }
        });
    }

    /**
     * Replica 0 is back as a new JVM with an empty store. Its root can only match the others'
     * through BFT-SMaRt state transfer (snapshot + log). Writes keep flowing while waiting, since
     * a recovering replica only notices it is behind when consensus traffic reaches it.
     */
    private static void m3Verify(int base) {
        gate("M3", "After replica 0 restarts empty, does state transfer give it the same root?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                int[] round = {0};
                Map<Integer, Reply> roots = converge(c, all(), CONVERGE_MS, () -> {
                    load(c, "verify-" + round[0]++, 20, 1);
                    c.checkpointEverywhere();
                });
                assertAllEqual(roots);
                System.out.printf("        replica 0 reconverged at checkpoint seq %d after %d "
                        + "rounds of traffic%n", roots.get(0).seq, round[0]);
            }
        });
    }

    // ------------------------------------------------------------------------ M4

    private static void m4(int base) {
        gate("M4", "PERFORMANCE GATE (real engine): unordered resolve p50 >= 2x faster than ordered?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:fast-real";
                check(alice.register(did, doc("fast")).ok(), "register failed");
                c.checkpointEverywhere();

                long[] tier0 = new long[200];
                long[] tier2 = new long[200];
                for (int i = 0; i < 30; i++) { alice.resolveTier0(did, i); alice.resolveTier2(did); }
                long fallbacksBefore = alice.tier0Fallbacks.sum();
                for (int i = 0; i < tier0.length; i++) {
                    long t = System.nanoTime();
                    byte[] got = alice.resolveTier0(did, i);
                    tier0[i] = System.nanoTime() - t;
                    check(Arrays.equals(got, doc("fast")), "Tier-0 returned the wrong document");
                }
                for (int i = 0; i < tier2.length; i++) {
                    long t = System.nanoTime();
                    byte[] got = alice.resolveTier2(did);
                    tier2[i] = System.nanoTime() - t;
                    check(Arrays.equals(got, doc("fast")), "Tier-2 returned the wrong document");
                }
                long fallbacks = alice.tier0Fallbacks.sum() - fallbacksBefore;
                double p50fast = percentile(tier0, 50) / 1000.0;
                double p50ordered = percentile(tier2, 50) / 1000.0;
                System.out.printf("        fast-path p50 = %.1f us (p99 %.1f), ordered p50 = %.1f us "
                                + "(p99 %.1f), speed-up = %.1fx, Tier-0 fallbacks = %d%n",
                        p50fast, percentile(tier0, 99) / 1000.0, p50ordered,
                        percentile(tier2, 99) / 1000.0, p50ordered / p50fast, fallbacks);
                if (p50fast > 900_000) {
                    System.out.println("        note: ~1 s per read is BFT-SMaRt's client, not the read: "
                            + "send() calls waitForChannels(replyQuorum) before every request, so a "
                            + "send to fewer targets leaves futures that never complete and the next "
                            + "send on that proxy waits its 1000 ms timeout (1.2, 2.0 and master alike)");
                }
                // A fallback turns a Tier-0 sample into a Tier-1 one: the fast-path number would
                // then not measure the fast path.
                check(fallbacks == 0, fallbacks + " Tier-0 reads fell back to Tier 1; the fast-path "
                        + "sample is contaminated");
                check(p50ordered / p50fast >= 2.0, String.format("fast path only %.2fx faster -- M4 "
                        + "gate says report this, the mechanism is not earning its place",
                        p50ordered / p50fast));
            }
        });
    }

    // ------------------------------------------------------------------------ M6

    private static void m6Forge(int base) {
        gate("M6", "Real engine, replica 2 FORGE_DOC: can it pass a forged document on Tier 0?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:forge-real";
                check(alice.register(did, doc("real")).ok(), "register failed");
                c.checkpointEverywhere();

                // checkpointEverywhere() refreshes the root as soon as f+1 replicas agree, which
                // can be f+1 that have not executed the CHECKPOINT yet. A Tier-0 reply against a
                // root the client does not trust yet falls back to Tier 1 -- rejected, but not by
                // the proof check under test. So wait until the client trusts the root all four
                // replicas hold; the simulator never has this window.
                Map<Integer, Reply> roots = converge(c, all(), CONVERGE_MS, null);
                assertAllEqual(roots);
                byte[] current = roots.get(0).checkpointRoot;
                long deadline = System.currentTimeMillis() + 15_000;
                while (!Arrays.equals(c.trustedRoot(), current) && System.currentTimeMillis() < deadline) {
                    sleep(200);
                }
                check(Arrays.equals(c.trustedRoot(), current),
                        "client never came to trust the root all replicas hold");

                long fallbacksBefore = alice.tier0Fallbacks.sum();
                boolean detected = false;
                byte[] served = null;
                try {
                    served = alice.resolveTier0(did, 2);
                } catch (VdrClient.ProofFailure e) {
                    detected = true;
                }
                long fallbacks = alice.tier0Fallbacks.sum() - fallbacksBefore;
                // Safety first: whatever path ran, the forged bytes must never come back.
                check(served == null || Arrays.equals(served, doc("real")),
                        "FORGED DOCUMENT RETURNED to the caller");
                check(detected, fallbacks > 0
                        ? "read fell back to Tier 1 (root not trusted), so the proof check was not exercised"
                        : "forged document was accepted -- proof verification is broken");
                check(Arrays.equals(alice.resolveTier0(did, 0), doc("real")), "honest read must still work");
            }
        });
    }

    private static void m6Stale(int base) {
        gate("M6", "Real engine, replica 1 STALE_READ: can a Tier-1 read return the old version?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:stale-real";
                check(alice.register(did, doc("v1")).ok(), "register failed");
                check(alice.update(did, 1, doc("v2"), alice.publicKey()).ok(), "update failed");
                c.checkpointEverywhere();
                // Every start, so the stale replica is first in the f+1 set at least once.
                for (int start = 0; start < N; start++) {
                    check(Arrays.equals(alice.resolveTier1(did, start), doc("v2")),
                            "Tier-1 read returned a stale version starting at replica " + start);
                }
            }
        });
    }

    /**
     * The key safety claim. Unlike the simulator, the client's freshness bar here comes from a
     * background root refresh, and a replica in the f+1 read set may not have executed the revoke
     * yet. So the check is repeated {@code rounds} times, each read issued immediately after the
     * revoke commits, from every start replica: a timing window shows up as a miss.
     */
    private static void m6Suppress(int base, int rounds) {
        String behaviour = System.getProperty("vdr.gates.byzantine", "?");
        gate("M6", "KEY SAFETY CLAIM (real engine, replica 3 " + behaviour + "): can a missed "
                + "revocation be served?", () -> {
            try (BftSmartCluster c = open(base, all())) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:suppress-real";
                check(alice.register(did, doc("s")).ok(), "register failed");
                String reg = Records.registryIdFor(did);

                List<String> misses = new ArrayList<>();
                for (int k = 0; k < rounds; k++) {
                    VdrClient.Outcome o = alice.revoke(reg, List.of(handle(1000 + k)), null);
                    check(o.ok(), "revoke " + k + " was not committed: " + o.errorCode());
                    for (int start = 0; start < N; start++) {
                        if (!alice.isRevoked(reg, handle(1000 + k), start)) {
                            misses.add("round " + k + " start " + start);
                        }
                    }
                }
                System.out.printf("        %d revocations x %d start replicas, each read "
                        + "immediately after commit: %d missed%n", rounds, N, misses.size());

                // Evidence the fault was live, not just configured.
                c.checkpointEverywhere();
                Reply r0 = c.rootOf(0, 5_000);
                Reply r3 = c.rootOf(3, 5_000);
                if (r0 != null && r3 != null) {
                    System.out.printf("        replica 3 root %s replica 0's%n",
                            Arrays.equals(r0.checkpointRoot, r3.checkpointRoot) ? "EQUALS" : "differs from");
                }
                check(misses.isEmpty(), "MISSED REVOCATION served: " + misses);
            }
        });
    }

    // ------------------------------------------------------------------- helpers

    /**
     * A client whose proxies reach every replica in {@code live}. BFT-SMaRt 1.2 proxies connect at
     * construction, so a client opened while a replica was still starting may never reach it;
     * such a client is closed and a new one opened on fresh client ids.
     */
    private static BftSmartCluster open(int base, int[] live) {
        long deadline = System.currentTimeMillis() + READY_MS;
        int attempt = 0;
        while (true) {
            BftSmartCluster c = new BftSmartCluster(N, base + 50 * attempt++);
            List<Integer> silent = new ArrayList<>();
            for (int i : live) {
                if (rootOrNull(c, i, 3_000) == null) silent.add(i);
            }
            if (silent.isEmpty()) return c;
            c.close();
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("replicas " + silent + " never answered within "
                        + READY_MS + " ms");
            }
            sleep(2_000);
        }
    }

    /**
     * Polls until every replica in {@code ids} reports the same checkpoint seq, running
     * {@code traffic} between polls if given. Returns the last replies either way.
     */
    private static Map<Integer, Reply> converge(BftSmartCluster c, int[] ids, long timeoutMs,
                                                Runnable traffic) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<Integer, Reply> last = new TreeMap<>();
        while (true) {
            last.clear();
            for (int i : ids) last.put(i, rootOrNull(c, i, 5_000));
            if (last.values().stream().allMatch(r -> r != null && r.seq == last.get(ids[0]).seq
                    && Arrays.equals(r.checkpointRoot, last.get(ids[0]).checkpointRoot))) {
                return new TreeMap<>(last);
            }
            if (System.currentTimeMillis() > deadline) return new TreeMap<>(last);
            if (traffic != null) traffic.run(); else sleep(500);
        }
    }

    /**
     * BFT-SMaRt 1.2 throws "Server not connected" for a one-target send whose channel is down
     * (a replica still restarting) instead of letting the request time out; here that is simply
     * "no answer yet".
     */
    private static Reply rootOrNull(BftSmartCluster c, int replica, long waitMs) {
        try {
            return c.rootOf(replica, waitMs);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void assertAllEqual(Map<Integer, Reply> roots) {
        StringBuilder table = new StringBuilder();
        Reply ref = null;
        boolean equal = true;
        for (Map.Entry<Integer, Reply> e : roots.entrySet()) {
            Reply r = e.getValue();
            table.append(String.format("%n          replica %d: %s", e.getKey(), r == null
                    ? "NO ANSWER" : "seq " + r.seq + " root " + shortHex(r.checkpointRoot)));
            if (r == null) { equal = false; continue; }
            if (ref == null) ref = r;
            else if (r.seq != ref.seq || !Arrays.equals(r.checkpointRoot, ref.checkpointRoot)) equal = false;
        }
        System.out.print("        roots:" + table + "\n");
        check(equal, "checkpoint roots differ across replicas");
    }

    /**
     * {@code count} signed writes in windows of {@code window} concurrent requests. A DID's ops
     * are 40 apart, so with window <= 40 they never race each other and are not denied for an
     * out-of-order version. Returns {ok, denied by f+1, no f+1 quorum}.
     */
    private static int[] load(BftSmartCluster c, String tag, int count, int window) {
        KeyPair kp = LOAD_KEYS;
        byte[] pub = Crypto.encodePublic(kp.getPublic());
        String clientId = "loader";
        int[] outcome = new int[3];
        List<CompletableFuture<List<Reply>>> inFlight = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String did = "did:vdr:" + tag + "-" + (i % 40);
            long nonce = NONCES.incrementAndGet();
            Op op;
            if (i < 40) {
                op = Op.register(clientId, did, doc(tag + i), pub, nonce);
            } else if (i % 3 == 0) {
                op = Op.update(clientId, did, VERSIONS.merge(did, 1L, Long::sum), doc(tag + i), pub, nonce);
            } else {
                op = Op.revoke(clientId, Records.registryIdFor(did),
                        List.of(handle(tag.hashCode() * 31 + i)), null, nonce);
            }
            inFlight.add(c.invokeOrdered(op.withSignature(Crypto.sign(kp.getPrivate(), op.signedBytes()))));
            if (inFlight.size() == window || i == count - 1) {
                for (CompletableFuture<List<Reply>> f : inFlight) tally(f, c.f(), outcome);
                inFlight.clear();
            }
        }
        return outcome;
    }

    private static final KeyPair LOAD_KEYS = Crypto.generateKeyPair();
    private static final java.util.concurrent.atomic.AtomicLong NONCES = new java.util.concurrent.atomic.AtomicLong();
    /** Expected version per DID: 1 after register, +1 per update. */
    private static final Map<String, Long> VERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void tally(CompletableFuture<List<Reply>> f, int fault, int[] outcome) {
        List<Reply> replies;
        try {
            replies = f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            outcome[2]++;
            return;
        }
        Map<String, List<Reply>> byDigest = new TreeMap<>();
        for (Reply r : replies) byDigest.computeIfAbsent(r.digest(), k -> new ArrayList<>()).add(r);
        for (List<Reply> g : byDigest.values()) {
            if (g.size() >= fault + 1) {
                outcome[g.get(0).ok ? 0 : 1]++;
                return;
            }
        }
        outcome[2]++;
    }

    private static VdrClient client(BftSmartCluster c, String id) {
        return new VdrClient(c, id, Crypto.generateKeyPair());
    }

    private static byte[] doc(String s) {
        return ("{\"@context\":\"https://www.w3.org/ns/did/v1\",\"id\":\"" + s + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Same derivation as vdr.test.Gates.handle: high-entropy, never sequential. */
    private static String handle(int i) {
        return Crypto.hex(Crypto.sha256("handle-seed-" + i));
    }

    private static int[] all() { return new int[] {0, 1, 2, 3}; }

    private static int[] live(int... ids) { return ids; }

    private static String shortHex(byte[] b) {
        return b == null ? "null" : Crypto.hex(b).substring(0, 16) + "…";
    }

    private static double percentile(long[] samples, double p) {
        long[] s = samples.clone();
        Arrays.sort(s);
        int idx = (int) Math.ceil(p / 100.0 * s.length) - 1;
        return s[Math.max(0, Math.min(idx, s.length - 1))];
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void gate(String milestone, String question, Runnable body) {
        System.out.printf("%n[%s] %s%n", milestone, question);
        try {
            body.run();
            passed++;
            System.out.println("        PASS");
        } catch (Throwable t) {
            failed++;
            System.out.println("        FAIL -- " + t.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void usage() {
        System.err.println("usage: RealGates ready|m3-load [ops]|m3-leaderdown [ops]|m3-verify|m4|"
                + "m6-forge|m6-stale|m6-suppress [rounds]");
        System.err.println("  run through vdr-bftsmart/real-gates.sh, which owns the containers");
        System.exit(2);
    }
}
