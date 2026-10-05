package vdr.merkle;

import vdr.crypto.Crypto;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Merkle checkpoint tree (plan section 5).
 *
 * Leaves are supplied as an ORDERED map keyed by a canonical leaf key, so the root is
 * a deterministic function of the replica-identical projection of state. Nothing that
 * differs between honest replicas (PVSS shares, decrypted payloads) may be passed in
 * here; see VdrStore.stateRoot().
 *
 * Domain separation: leaves are hashed with a 0x00 prefix and internal nodes with 0x01,
 * so a proof for a leaf cannot be replayed as a proof for an internal node.
 */
public final class Merkle {

    private static final byte[] LEAF_PREFIX = {0x00};
    private static final byte[] NODE_PREFIX = {0x01};

    private final List<String> keys = new ArrayList<>();
    private final List<byte[]> leaves = new ArrayList<>();
    private final List<List<byte[]>> levels = new ArrayList<>();
    private final byte[] root;

    public Merkle(SortedMap<String, byte[]> orderedLeaves) {
        this(orderedLeaves, false);
    }

    /**
     * @param prehashed true when the map's values are already leaf hashes ({@link #leafHash}),
     *     so a tree rebuilt from cached leaves does not hash every leaf again
     */
    private Merkle(SortedMap<String, byte[]> orderedLeaves, boolean prehashed) {
        for (Map.Entry<String, byte[]> e : orderedLeaves.entrySet()) {
            keys.add(e.getKey());
            leaves.add(prehashed ? e.getValue() : leafHash(e.getKey(), e.getValue()));
        }
        if (leaves.isEmpty()) {
            root = Crypto.sha256("EMPTY_VDR_STATE");
            return;
        }
        List<byte[]> level = new ArrayList<>(leaves);
        levels.add(level);
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                byte[] l = level.get(i);
                byte[] r = (i + 1 < level.size()) ? level.get(i + 1) : level.get(i); // duplicate last
                next.add(Crypto.sha256(NODE_PREFIX, l, r));
            }
            levels.add(next);
            level = next;
        }
        root = level.get(0);
    }

    /** A tree over leaves whose hashes were computed with {@link #leafHash} beforehand. */
    public static Merkle ofLeafHashes(SortedMap<String, byte[]> orderedLeafHashes) {
        return new Merkle(orderedLeafHashes, true);
    }

    /** The domain-separated leaf hash: H(0x00, key, leafValue). */
    public static byte[] leafHash(String key, byte[] leafValue) {
        return Crypto.sha256(LEAF_PREFIX, key.getBytes(StandardCharsets.UTF_8), leafValue);
    }

    /** The domain-separated internal-node hash: H(0x01, left, right). */
    public static byte[] nodeHash(byte[] left, byte[] right) {
        return Crypto.sha256(NODE_PREFIX, left, right);
    }

    /** True if {@code key} is a leaf of this tree. */
    public boolean contains(String key) {
        return Collections.binarySearch(keys, key) >= 0;
    }

    /** Root without a defensive copy, for callers that never mutate it. */
    byte[] rootUnsafe() {
        return root;
    }

    public byte[] root() {
        return root.clone();
    }

    public String rootHex() {
        return Crypto.hex(root);
    }

    public int size() {
        return leaves.size();
    }

    /** @return inclusion proof for {@code key}, or null if the key is absent. */
    public Proof prove(String key) {
        int idx = Collections.binarySearch(keys, key);
        if (idx < 0) return null;
        List<byte[]> siblings = new ArrayList<>();
        List<Boolean> rightSide = new ArrayList<>();
        int i = idx;
        for (int lvl = 0; lvl < levels.size() - 1; lvl++) {
            List<byte[]> level = levels.get(lvl);
            int sib = (i % 2 == 0) ? Math.min(i + 1, level.size() - 1) : i - 1;
            siblings.add(level.get(sib));
            rightSide.add(i % 2 == 0); // sibling sits to the right of us
            i /= 2;
        }
        return new Proof(key, siblings, rightSide);
    }

    /**
     * Client-side verification. The client never holds replica state: it recomputes the
     * leaf from the value it was served and walks the proof up to the root it trusts.
     */
    public static boolean verify(byte[] expectedRoot, String key, byte[] leafValue, Proof proof) {
        if (proof == null || expectedRoot == null) return false;
        if (!proof.key().equals(key)) return false;
        byte[] h = Crypto.sha256(LEAF_PREFIX, key.getBytes(StandardCharsets.UTF_8), leafValue);
        for (int i = 0; i < proof.siblings().size(); i++) {
            byte[] sib = proof.siblings().get(i);
            h = proof.siblingOnRight().get(i) ? Crypto.sha256(NODE_PREFIX, h, sib)
                                              : Crypto.sha256(NODE_PREFIX, sib, h);
        }
        return Arrays.equals(h, expectedRoot);
    }

    /** Proof of inclusion of one leaf under a checkpoint root. */
    public record Proof(String key, List<byte[]> siblings, List<Boolean> siblingOnRight) {}
}
