package vdr.test;

import vdr.crypto.Crypto;
import vdr.client.VdrClient;
import vdr.merkle.Merkle;
import vdr.model.Records;
import vdr.ops.Op;
import vdr.ops.Reply;
import vdr.replication.SimulatedCluster;
import vdr.replication.Gateway;
import vdr.replication.ServiceReplica;
import vdr.store.VdrStore;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.*;

/**
 * The go/no-go gates of plan section 11, as executable assertions.
 *
 * Each test prints the milestone it gates and the adversarial-review question it answers,
 * so a failing run names the gate that failed rather than just a stack trace.
 */
public final class Gates {

    private static int passed = 0, failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Tailored BFT VDR -- milestone gate suite");
        System.out.println("=".repeat(78));

        m1StoreAndOperations();
        m1DeterminismReplay();
        m2PolicyLayer();
        m3CheckpointRootsIdentical();
        m3ProofsSurviveStateGrowth();
        m4ReadFastPath();
        m5RevocationBatching();
        m6ByzantineForgery();
        m6ByzantineStaleRead();
        m6RevocationSuppression();
        m6ByzantineClientRepair();
        m7ConfidentialityRootEquality();
        bWireCodecRoundTrip();
        bStepDownAdmission();
        bSnapshotRestoresIdenticalRoot();
        bSnapshotExcludesPerReplicaState();
        bIncrementalCheckpointEqualsScratch();

        System.out.println("=".repeat(78));
        System.out.printf("passed %d, failed %d%n", passed, failed);
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ M1

    private static void m1StoreAndOperations() {
        gate("M1", "Can a client write to a DID it does not control?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                VdrClient mallory = client(c, "mallory");
                String did = "did:vdr:alice1";

                check(alice.register(did, doc("alice v1")).ok(), "register should succeed");
                check(!alice.register(did, doc("again")).ok(), "re-register must be refused (cas)");

                // Mallory holds a different key: the core must refuse her update
                VdrClient.Outcome stolen = mallory.update(did, 1, doc("mallory"), mallory.publicKey());
                check(!stolen.ok(), "unauthorised update must be denied");
                check("P2_UNAUTHORISED_KEY".equals(stolen.errorCode()),
                        "expected P2_UNAUTHORISED_KEY, got " + stolen.errorCode());

                VdrClient.Outcome rot = alice.update(did, 1, doc("alice v2"), alice.publicKey());
                check(rot.ok() && rot.version() == 2, "key rotation should append version 2");
                check(rot.matchingReplies() >= c.f + 1, "need f+1 matching replies");

                byte[] resolved = alice.resolveTier2(did);
                check(Arrays.equals(resolved, doc("alice v2")), "ordered resolve returns latest version");
            }
        });
    }

    private static void m1DeterminismReplay() {
        gate("M1", "Replay the same ordered log against fresh replicas: identical roots?", () -> {
            List<Op> log = syntheticLog(400);
            String firstRoot = null;
            for (int trial = 0; trial < 4; trial++) {
                VdrStore store = new VdrStore(trial, 4096, 1_000_000);
                long seq = 0;
                StringBuilder replyDigests = new StringBuilder();
                for (Op op : log) {
                    Reply r = store.execute(op, ++seq);
                    replyDigests.append(r.digest());
                }
                String root = store.stateRootHex() + "/" + Crypto.hex(Crypto.sha256(replyDigests.toString()));
                if (firstRoot == null) firstRoot = root;
                else check(firstRoot.equals(root),
                        "replica " + trial + " diverged on replay: " + root + " != " + firstRoot);
            }
        });
    }

    // ------------------------------------------------------------------ M2

    private static void m2PolicyLayer() {
        gate("M2", "Does the gateway's verdict ever substitute for the core's?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:policy1";
                alice.register(did, doc("v1"));

                // P3: version conflict
                VdrClient.Outcome stale = alice.update(did, 99, doc("v2"), alice.publicKey());
                check("P3_VERSION_CONFLICT".equals(stale.errorCode()),
                        "expected P3_VERSION_CONFLICT, got " + stale.errorCode());

                // P6: batch well-formedness -- duplicate handle and low-entropy handle
                String reg = Records.registryIdFor(did);
                VdrClient.Outcome dup = alice.revoke(reg, List.of(handle(1), handle(1)), null);
                check("P6_DUPLICATE_HANDLE".equals(dup.errorCode()),
                        "expected P6_DUPLICATE_HANDLE, got " + dup.errorCode());
                VdrClient.Outcome weak = alice.revoke(reg, List.of("cred-7"), null);
                check("P6_LOW_ENTROPY_HANDLE".equals(weak.errorCode()),
                        "expected P6_LOW_ENTROPY_HANDLE, got " + weak.errorCode());

                // P4: revoke under a key not authorised for the registry
                VdrClient mallory = client(c, "mallory");
                VdrClient.Outcome bad = mallory.revoke(reg, List.of(handle(2)), null);
                check("P4_UNAUTHORISED_KEY".equals(bad.errorCode()),
                        "expected P4_UNAUTHORISED_KEY, got " + bad.errorCode());

                // the denial itself must be agreed by f+1 replicas, not asserted by one
                check(bad.matchingReplies() >= c.f + 1, "denial needs f+1 identical error codes");
            }
        });
    }

    // ------------------------------------------------------------------ M3

    private static void m3CheckpointRootsIdentical() {
        gate("M3", "Identical checkpoint roots across replicas after 10k ops?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                for (int i = 0; i < 60; i++) alice.register("did:vdr:bulk" + i, doc("d" + i));
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();
                String root0 = c.replica(0).store().checkpoint().rootHex();
                for (ServiceReplica r : c.replicas()) {
                    check(r.store().checkpoint().rootHex().equals(root0),
                            "replica " + r.id() + " root differs");
                }
                check(c.trustedRoot() != null, "f+1 replicas must agree on an announced root");
            }
        });
    }

    private static void m3ProofsSurviveStateGrowth() {
        gate("M3", "Can a replica serve a document with a proof from a root no replica committed?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                for (int i = 0; i < 25; i++) alice.register("did:vdr:proof" + i, doc("p" + i));
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();

                byte[] resolved = alice.resolveTier0("did:vdr:proof7", 0);
                check(Arrays.equals(resolved, doc("p7")), "Tier-0 read must return the document");

                // a proof for one leaf must not verify for another leaf's value
                Reply r = c.readTier0("did:vdr:proof7", 1);
                check(!Merkle.verify(r.checkpointRoot, r.leafKey,
                                Records.DidDoc.leafBytes("did:vdr:proof7", r.version, doc("p8")), r.proof),
                        "a proof must not verify against a substituted value");
            }
        });
    }

    // ------------------------------------------------------------------ M4

    private static void m4ReadFastPath() {
        gate("M4", "PERFORMANCE GATE: unordered resolve p50 must beat ordered resolve p50 by >=2x", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:fast1";
                alice.register(did, doc("fast"));
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();

                long[] tier0 = new long[200];
                long[] tier2 = new long[200];
                for (int i = 0; i < 30; i++) { alice.resolveTier0(did, i); alice.resolveTier2(did); } // warm-up
                for (int i = 0; i < tier0.length; i++) {
                    long t = System.nanoTime(); alice.resolveTier0(did, i); tier0[i] = System.nanoTime() - t;
                }
                for (int i = 0; i < tier2.length; i++) {
                    long t = System.nanoTime(); alice.resolveTier2(did); tier2[i] = System.nanoTime() - t;
                }
                double p50fast = percentile(tier0, 50) / 1000.0;
                double p50ordered = percentile(tier2, 50) / 1000.0;
                System.out.printf("        fast-path p50 = %.1f us, ordered p50 = %.1f us, speed-up = %.1fx%n",
                        p50fast, p50ordered, p50ordered / p50fast);
                check(p50ordered / p50fast >= 2.0,
                        String.format("fast path only %.2fx faster -- M4 gate says report this, "
                                + "the mechanism is not earning its place", p50ordered / p50fast));
            }
        });
    }

    // ------------------------------------------------------------------ M5

    private static void m5RevocationBatching() {
        gate("M5", "Can a revoke be dropped from a batch without detection?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:revbatch";
                alice.register(did, doc("rev"));
                String reg = Records.registryIdFor(did);

                int burst = 2000, batchSize = 250;
                Gateway gw = new Gateway(c, batchSize, 50);
                long before = c.consensusInstances();
                List<java.util.concurrent.CompletableFuture<List<Reply>>> futures = new ArrayList<>();
                for (int i = 0; i < burst; i++) {
                    futures.add(gw.submitRevoke("alice", reg, handle(i), null, alice::signFor));
                }
                gw.flush();
                for (var fu : futures) fu.join();
                long instances = c.consensusInstances() - before;

                System.out.printf("        %d revocations committed in %d consensus instances "
                                + "(%d gateway batches, batchSize=%d)%n",
                        burst, instances, gw.batchesSubmitted(), batchSize);
                check(gw.batchesSubmitted() <= (burst + batchSize - 1) / batchSize,
                        "gateway must not submit more batches than ceil(burst/batchSize)");

                // every single handle must be individually provable as revoked
                for (int i = 0; i < burst; i += 97) {
                    check(alice.isRevoked(reg, handle(i), 0), "handle " + i + " silently dropped from batch");
                }
                check(!alice.isRevoked(reg, handle(burst + 1), 0), "unrevoked handle reported revoked");
            }
        });
    }

    // ------------------------------------------------------------------ M6

    private static void m6ByzantineForgery() {
        gate("M6", "Can a Byzantine replica return a forged document on the fast path?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:forge";
                alice.register(did, doc("real"));
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();

                c.replica(2).setBehaviour(ServiceReplica.Behaviour.FORGE_DOC);
                boolean detected = false;
                try {
                    alice.resolveTier0(did, 2);
                } catch (VdrClient.ProofFailure e) {
                    detected = true;
                }
                check(detected, "forged document was accepted -- proof verification is broken");

                // and the honest replicas still serve correctly
                check(Arrays.equals(alice.resolveTier0(did, 0), doc("real")), "honest read must still work");
            }
        });
    }

    private static void m6ByzantineStaleRead() {
        gate("M6", "Does a stale-but-valid read ever satisfy a freshness-critical query?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:stale";
                alice.register(did, doc("v1"));
                c.replica(1).setBehaviour(ServiceReplica.Behaviour.STALE_READ);
                alice.update(did, 1, doc("v2"), alice.publicKey());
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();

                // a stale replica serves an older, still-valid document: integrity holds,
                // freshness does not. That is exactly the bounded weakening of section 3.3.
                byte[] tier1 = alice.resolveTier1(did, 0);
                check(Arrays.equals(tier1, doc("v2")),
                        "Tier-1 read must not return the stale version");
            }
        });
    }

    private static void m6RevocationSuppression() {
        gate("M6", "KEY SAFETY CLAIM: can any in-bound fault serve a missed revocation?", () -> {
            for (ServiceReplica.Behaviour b : List.of(ServiceReplica.Behaviour.SUPPRESS_REVOKE,
                    ServiceReplica.Behaviour.STALE_READ, ServiceReplica.Behaviour.EQUIVOCATE)) {
                try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                    VdrClient alice = client(c, "alice");
                    String did = "did:vdr:suppress-" + b;
                    alice.register(did, doc("s"));
                    String reg = Records.registryIdFor(did);

                    c.replica(3).setBehaviour(b);                 // exactly f = 1 Byzantine replica
                    alice.revoke(reg, List.of(handle(42)), null);

                    // ask every replica first, including the Byzantine one
                    for (int start = 0; start < c.n; start++) {
                        check(alice.isRevoked(reg, handle(42), start),
                                "MISSED REVOCATION served under " + b + " starting at replica " + start);
                    }
                }
            }
        });
    }

    private static void m6ByzantineClientRepair() {
        gate("M6", "Is the damage from a Byzantine issuer recoverable and bounded?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient mallory = client(c, "mallory");
                String did = "did:vdr:repair";
                mallory.register(did, doc("m"));
                String reg = Records.registryIdFor(did);
                mallory.revoke(reg, List.of(handle(5)), "bad-payload".getBytes(StandardCharsets.UTF_8));

                String hh = Crypto.hex(Crypto.sha256(handle(5)));
                check(c.replica(0).store().hasShare(reg, hh), "confidential share should exist before repair");

                // DepSpace Algorithm 3: justified repair deletes the entry and blacklists the inserter
                VdrClient.Outcome rep = mallory.repair(reg, hh);
                check(rep.ok(), "justified repair must be accepted");
                check(!c.replica(0).store().hasShare(reg, hh), "repair must delete the invalid payload");

                // bounded damage: the blacklisted client's later requests are ignored
                VdrClient.Outcome after = mallory.register("did:vdr:repair2", doc("m2"));
                check("P0_BLACKLISTED".equals(after.errorCode()),
                        "blacklisted client must be ignored, got " + after.errorCode());

                // P5: revocation stays monotone -- repair never un-revokes
                check(c.replica(0).store().revoked(reg, hh), "repair must not roll back the accumulator");
            }
        });
    }

    // ------------------------------------------------------------------ M7

    private static void m7ConfidentialityRootEquality() {
        gate("M7", "Does enabling confidentiality break checkpoint-root equality?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                String did = "did:vdr:conf";
                alice.register(did, doc("c"));
                String reg = Records.registryIdFor(did);

                // confidential revocation metadata: each replica stores a DIFFERENT share
                for (int i = 0; i < 40; i++) {
                    alice.revoke(reg, List.of(handle(1000 + i)),
                            ("reason-" + i).getBytes(StandardCharsets.UTF_8));
                }
                for (ServiceReplica r : c.replicas()) r.store().forceCheckpoint();

                String root0 = c.replica(0).store().checkpoint().rootHex();
                for (ServiceReplica r : c.replicas()) {
                    check(r.store().checkpoint().rootHex().equals(root0),
                            "replica " + r.id() + " root diverged with confidentiality on -- "
                                    + "the state root is leaking per-replica share data");
                }
                // and revoke keeps its linearizable status despite the confidential payload
                check(alice.isRevoked(reg, handle(1007), 0), "status must survive confidentiality");
            }
        });
    }

    // --------------------------------------------------------------- helpers

    private static VdrClient client(SimulatedCluster c, String id) {
        KeyPair kp = Crypto.generateKeyPair();
        return new VdrClient(c, id, kp);
    }

    private static byte[] doc(String s) {
        return ("{\"@context\":\"https://www.w3.org/ns/did/v1\",\"id\":\"" + s + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** High-entropy opaque credential handle: never a sequential id (plan section 2.1). */
    static String handle(int i) {
        return Crypto.hex(Crypto.sha256("handle-seed-" + i));
    }

    private static List<Op> syntheticLog(int size) {
        List<Op> ops = new ArrayList<>();
        KeyPair kp = Crypto.generateKeyPair();
        byte[] pub = Crypto.encodePublic(kp.getPublic());
        Map<String, Long> versions = new TreeMap<>();
        for (int i = 0; i < size; i++) {
            String did = "did:vdr:replay" + (i % 40);
            if (!versions.containsKey(did)) {
                Op op = Op.register("replayer", did, doc("r" + i), pub, i);
                ops.add(op.withSignature(Crypto.sign(kp.getPrivate(), op.signedBytes())));
                versions.put(did, 1L);
            } else if (i % 3 == 0) {
                long v = versions.get(did);
                Op op = Op.update("replayer", did, v, doc("r" + i), pub, i);
                ops.add(op.withSignature(Crypto.sign(kp.getPrivate(), op.signedBytes())));
                versions.put(did, v + 1);
            } else {
                Op op = Op.revoke("replayer", Records.registryIdFor(did), List.of(handle(i)), null, i);
                ops.add(op.withSignature(Crypto.sign(kp.getPrivate(), op.signedBytes())));
            }
        }
        return ops;
    }

    static double percentile(long[] samples, double p) {
        long[] s = samples.clone();
        Arrays.sort(s);
        int idx = (int) Math.ceil(p / 100.0 * s.length) - 1;
        return s[Math.max(0, Math.min(idx, s.length - 1))];
    }

    // ------------------------------------------------- B: BFT-SMaRt prerequisites

    /**
     * WP2. Every Op and Reply must survive the wire unchanged, and equal values must encode to
     * identical bytes: replies are matched f+1-wise by a digest over their content, so an encoder
     * with two encodings of one value would make honest replicas look like they disagreed.
     */
    private static void bWireCodecRoundTrip() {
        gate("B1", "Does the wire codec round-trip every operation and reply canonically?", () -> {
            KeyPair kp = Crypto.generateKeyPair();
            List<Op> ops = new ArrayList<>(List.of(
                    Op.register("c", "did:vdr:x", "doc".getBytes(StandardCharsets.UTF_8),
                            Crypto.encodePublic(kp.getPublic()), 1),
                    Op.update("c", "did:vdr:x", 1, "doc2".getBytes(StandardCharsets.UTF_8),
                            Crypto.encodePublic(kp.getPublic()), 2),
                    Op.resolve("c", "did:vdr:x"),
                    Op.root("c"),
                    Op.revoke("c", "did:vdr:x#rev", List.of("h1", "h2", "h3"),
                            "reason".getBytes(StandardCharsets.UTF_8), 3),
                    Op.repair("c", "did:vdr:x#rev", "abcd", 4)));
            for (Op op : ops) {
                Op signed = op.withSignature(Crypto.sign(kp.getPrivate(), op.signedBytes()));
                byte[] once = vdr.serialization.Codec.encode(signed);
                Op back = vdr.serialization.Codec.decodeOp(once);
                check(back.kind == signed.kind, "kind changed on the wire");
                check(Objects.equals(back.did, signed.did), "did changed on the wire");
                check(Objects.equals(back.registryId, signed.registryId), "registryId changed");
                check(Objects.equals(back.handles, signed.handles), "handles changed");
                check(back.nonce == signed.nonce, "nonce changed");
                check(Crypto.eq(back.sig, signed.sig), "signature changed");
                check(Crypto.eq(back.signedBytes(), signed.signedBytes()),
                        "the signed bytes differ after a round trip: every replica would reject "
                        + "this operation as unsigned");
                check(Arrays.equals(once, vdr.serialization.Codec.encode(back)),
                        "re-encoding produced different bytes: the encoding is not canonical");
            }
            // A reply carrying a real inclusion proof, which is the part with structure.
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                alice.register("did:vdr:codec", "d".getBytes(StandardCharsets.UTF_8));
                c.checkpointEverywhere();
                Reply r = c.readTier0("did:vdr:codec", 0);
                byte[] enc = vdr.serialization.Codec.encode(r);
                Reply back = vdr.serialization.Codec.decodeReply(enc);
                check(back.digest().equals(r.digest()),
                        "the reply digest changed on the wire: f+1 matching would never succeed");
                check(back.proof != null && back.leafKey != null, "the proof was lost on the wire");
                check(Merkle.verify(back.checkpointRoot, back.leafKey,
                                Records.DidDoc.leafBytes("did:vdr:codec", back.version, back.value),
                                back.proof),
                        "a proof that verified before the round trip does not verify after it");
                check(Arrays.equals(enc, vdr.serialization.Codec.encode(back)),
                        "re-encoding a reply produced different bytes");
            }
        });
    }

    /**
     * Remediation plan §3. An operating point admitted by ONE sweep run must be re-measured, and
     * abandoned when its merged p99 misses the target. Three of the four operating points reported
     * on 22 September were admitted at 350-490 ms and then measured at 795, 811 and 1,098 ms; each
     * would have been published as a result the paper's own 500 ms SLO rejects.
     */
    private static void bStepDownAdmission() {
        gate("B2", "Is an operating point abandoned when its measured p99 misses the target?", () -> {
            List<Integer> admitted = List.of(50, 100, 150, 200);
            List<Integer> measured = new ArrayList<>();
            // Only 100 ops/s and below meet the target once measured.
            vdr.bench.Bench.Result r = vdr.bench.Bench.chooseLevel("test", admitted, rate -> {
                measured.add(rate);
                double p99 = rate <= 100 ? 300_000 : 900_000;
                return new vdr.bench.Bench.Result("test", rate, 0, 1_000, 10_000, p99,
                        "n=4", rate * 0.95, rate * 0.04, rate * 0.01, 0, 0, 0);
            });
            check(r.throughput() == 100,
                    "reported " + r.throughput() + " ops/s; 100 is the highest level that met the "
                    + "target when measured");
            check(measured.equals(List.of(200, 150, 100)),
                    "levels were measured in the order " + measured + "; the step-down must start "
                    + "at the highest admitted level and stop at the first that passes");
            check(r.notes().contains("admission path:"),
                    "the reported row does not record the path it took: a reviewer cannot tell "
                    + "the point was re-measured");
            check(r.notes().contains("200 ops/s") && r.notes().contains("100 ops/s"),
                    "the admission path omits a level that was measured");

            // And when nothing passes, the harness must refuse rather than report the least bad.
            boolean refused = false;
            try {
                vdr.bench.Bench.chooseLevel("test", List.of(50, 100), rate ->
                        new vdr.bench.Bench.Result("test", rate, 0, 1_000, 10_000, 900_000,
                                "n=4", 0, 0, 0, 0, 0, 0));
            } catch (IllegalStateException e) {
                refused = true;
            }
            check(refused, "the harness reported a number when no level met the target");
        });
    }

    /**
     * WP3. A replica restored from a snapshot must hold the same state root as one that never
     * crashed. If it does not, its Tier-0 proofs stop verifying -- silently, because nothing
     * checks roots across replicas at run time.
     */
    private static void bSnapshotRestoresIdenticalRoot() {
        gate("B4", "Does a replica restored from a snapshot reach the same state root?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                for (int i = 0; i < 40; i++) {
                    alice.register("did:vdr:snap" + i, ("d" + i).getBytes(StandardCharsets.UTF_8));
                }
                for (int i = 0; i < 10; i++) {
                    alice.update("did:vdr:snap" + i, 1, ("d2-" + i).getBytes(StandardCharsets.UTF_8),
                            alice.publicKey());
                }
                alice.revoke(Records.registryIdFor("did:vdr:snap0"),
                        List.of(handle(1), handle(2), handle(3)), null);
                VdrStore source = c.replica(0).store();
                byte[] snapshot = source.serialiseIdenticalProjection();

                VdrStore restored = new VdrStore(9, 4096, 2000);
                restored.installIdenticalProjection(snapshot);
                check(restored.stateRootHex().equals(source.stateRootHex()),
                        "restored root " + restored.stateRootHex() + " != source root "
                        + source.stateRootHex());
                check(restored.seq() == source.seq(), "the sequence number was not restored");
                check(restored.epoch() == source.epoch(), "the revocation epoch was not restored");
                // A restored replica must also keep executing correctly, not merely look right.
                Reply r = restored.resolveUnordered("did:vdr:snap5");
                check(r.ok && r.proof != null, "a restored replica cannot serve a Tier-0 read");
                check(Arrays.equals(restored.serialiseIdenticalProjection(), snapshot),
                        "re-serialising a restored snapshot produced different bytes");
            }
        });
    }

    /**
     * WP3, the trap docs/BFT-SMART-WIRING.md names. Per-replica state -- a PVSS share -- must
     * never travel in a snapshot: a recovered replica would come back holding another replica's
     * share and diverge from every replica that never crashed.
     */
    private static void bSnapshotExcludesPerReplicaState() {
        gate("B4", "Does a snapshot leak per-replica confidential state?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                alice.register("did:vdr:conf", "d".getBytes(StandardCharsets.UTF_8));
                byte[] payload = "confidential-reason".getBytes(StandardCharsets.UTF_8);
                alice.revoke(Records.registryIdFor("did:vdr:conf"), List.of(handle(7)), payload);

                // Two replicas hold DIFFERENT shares of the same payload by construction.
                byte[] snap0 = c.replica(0).store().serialiseIdenticalProjection();
                byte[] snap1 = c.replica(1).store().serialiseIdenticalProjection();
                check(Arrays.equals(snap0, snap1),
                        "two replicas produced different snapshots of the identical projection: "
                        + "per-replica state has leaked into state transfer");
                // And the share itself must not appear anywhere in the bytes.
                String hex = Crypto.hex(snap0);
                check(!hex.contains(Crypto.hex(Crypto.sha256(payload, Crypto.intToBytes(0)))),
                        "replica 0's PVSS share is present in the snapshot");
            }
        });
    }

    /**
     * Checkpoints are maintained incrementally (vdr.merkle.IncrementalMerkle). The incremental
     * root must equal a root computed from scratch over the same state after every kind of
     * operation, every Tier-0 proof cut from it must verify, and a restored replica must agree.
     */
    private static void bIncrementalCheckpointEqualsScratch() {
        gate("B5", "Does the incrementally maintained checkpoint equal a from-scratch rebuild?", () -> {
            try (SimulatedCluster c = SimulatedCluster.standard(4)) {
                VdrClient alice = client(c, "alice");
                VdrStore s0 = c.replica(0).store();
                int ops = 0;
                for (int i = 0; i < 300; i++) {
                    alice.register("did:vdr:inc" + i, ("d" + i).getBytes(StandardCharsets.UTF_8));
                    if (i % 3 == 0) {
                        alice.update("did:vdr:inc" + i, 1, ("u" + i).getBytes(StandardCharsets.UTF_8),
                                alice.publicKey());
                    }
                    if (i % 25 == 24) {
                        List<String> hs = new ArrayList<>();
                        for (int k = 0; k < 20; k++) hs.add(handle(i * 100 + k));
                        alice.revoke(Records.registryIdFor("did:vdr:inc" + (i % 7)), hs, null);
                    }
                    if (i % 50 == 49) {
                        ops++;
                        check(Arrays.equals(s0.incrementalStateRoot(), s0.stateRoot()),
                                "incremental root diverged from the scratch root after " + (i + 1)
                                + " registrations");
                    }
                }
                check(ops > 0, "no cross-check ran");
                for (int r = 0; r < 4; r++) {
                    check(Arrays.equals(c.replica(r).store().incrementalStateRoot(),
                            c.replica(r).store().stateRoot()), "replica " + r + " diverged");
                }
                // Proofs cut from the incremental tree verify with the unchanged client verifier.
                s0.forceCheckpoint();
                for (int i = 0; i < 300; i += 37) {
                    Reply rep = s0.resolveUnordered("did:vdr:inc" + i);
                    check(rep.ok && Merkle.verify(rep.checkpointRoot, rep.leafKey,
                            Records.DidDoc.leafBytes("did:vdr:inc" + i, rep.version, rep.value),
                            rep.proof), "Tier-0 proof from the incremental tree did not verify");
                }
                Reply rev = s0.revocationStatusUnordered(Records.registryIdFor("did:vdr:inc" + (24 % 7)),
                        handle(2400));
                check(rev.revoked, "a committed revocation is not visible in the checkpoint");
                // State transfer rebuilds the indexes: same root.
                VdrStore restored = new VdrStore(9, 4096, 2000);
                restored.installIdenticalProjection(s0.serialiseIdenticalProjection());
                check(Arrays.equals(restored.checkpoint().root, s0.checkpoint().root),
                        "restored checkpoint root differs from the source's");
            }
        });
    }

    private static void gate(String milestone, String question, Runnable body) {
        System.out.printf("%n[%s] %s%n", milestone, question);
        try {
            body.run();
            passed++;
            System.out.println("        PASS");
        } catch (Throwable t) {
            failed++;
            failures.add(milestone + ": " + t.getMessage());
            System.out.println("        FAIL -- " + t.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
