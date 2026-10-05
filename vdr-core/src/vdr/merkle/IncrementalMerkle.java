package vdr.merkle;

import vdr.crypto.Crypto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Merkle checkpoint tree that is updated in O(changed leaves), not rebuilt in O(state).
 *
 * <p><b>Why.</b> {@code VdrStore.buildCheckpoint()} used to re-hash every leaf of the whole
 * registry and rebuild the tree on every checkpoint -- and a checkpoint is cut on every committed
 * revocation batch (V6) as well as every {@code checkpointInterval} operations. On a 1 vCPU
 * replica that made the ordering thread's cost grow linearly with everything ever written: a few
 * tens of thousands of leaves already cost tens of milliseconds per revocation batch, so the
 * system slowed down as a run went on, and every run was slower than the one before.
 *
 * <p><b>Shape.</b> Leaves are assigned to one of {@link #BUCKETS} buckets by the first bits of
 * SHA-256(key) -- a deterministic function of the key, identical on every replica, and not
 * steerable by an issuer choosing DIDs. Each bucket is an ordinary sorted {@link Merkle} tree; the
 * bucket roots are the leaves of a complete binary tree of depth {@link #BUCKET_BITS}. A checkpoint
 * rebuilds only the buckets touched since the previous one, from cached leaf hashes, and
 * recomputes only their paths to the root. Untouched buckets are shared between snapshots.
 *
 * <p><b>What does not change.</b> The leaf and node hash functions and their domain separation
 * (0x00 / 0x01), the proof format (sibling list + side flags) and {@link Merkle#verify} are exactly
 * as before: a proof is the in-bucket path followed by the bucket's path in the top tree, so the
 * client-side verification code is untouched. The root is still a deterministic function of the
 * replica-identical projection only (plan section 5). Its numeric value differs from the old
 * single-tree root, which is why the snapshot format number was bumped.
 */
public final class IncrementalMerkle {

    public static final int BUCKET_BITS = 12;
    public static final int BUCKETS = 1 << BUCKET_BITS;

    /** Every empty bucket is the same immutable tree; built once, shared by every store. */
    private static final Merkle EMPTY_BUCKET = Merkle.ofLeafHashes(new TreeMap<>());

    private static Merkle bucketTree(TreeMap<String, byte[]> leaves) {
        return leaves.isEmpty() ? EMPTY_BUCKET : Merkle.ofLeafHashes(leaves);
    }

    @SuppressWarnings("unchecked")
    private final TreeMap<String, byte[]>[] leafHashes = new TreeMap[BUCKETS];
    private final BitSet dirty = new BitSet(BUCKETS);
    private Snapshot last;

    public IncrementalMerkle() {
        for (int i = 0; i < BUCKETS; i++) leafHashes[i] = new TreeMap<>();
        dirty.set(0, BUCKETS);
    }

    /** Hash of an all-empty subtree at each top-tree level (level 0 = an empty bucket's root). */
    private static final byte[][] EMPTY_LEVELS = new byte[BUCKET_BITS + 1][];
    static {
        EMPTY_LEVELS[0] = EMPTY_BUCKET.rootUnsafe();
        for (int l = 1; l <= BUCKET_BITS; l++) {
            EMPTY_LEVELS[l] = Merkle.nodeHash(EMPTY_LEVELS[l - 1], EMPTY_LEVELS[l - 1]);
        }
    }

    /** Bucket of a leaf key: the top BUCKET_BITS of SHA-256(key). */
    public static int bucketOf(String key) {
        byte[] h = Crypto.sha256(key.getBytes(StandardCharsets.UTF_8));
        int v = ((h[0] & 0xff) << 8) | (h[1] & 0xff);
        return v >>> (16 - BUCKET_BITS);
    }

    /** Inserts or replaces a leaf. {@code leafValue} is the leaf content, as for {@link Merkle}. */
    public void put(String key, byte[] leafValue) {
        int b = bucketOf(key);
        leafHashes[b].put(key, Merkle.leafHash(key, leafValue));
        dirty.set(b);
    }

    /** Removes every leaf (state transfer installs a whole new state). */
    public void clear() {
        for (TreeMap<String, byte[]> m : leafHashes) m.clear();
        dirty.set(0, BUCKETS);
        last = null;
    }

    public int size() {
        int n = 0;
        for (TreeMap<String, byte[]> m : leafHashes) n += m.size();
        return n;
    }

    /** Immutable view of the tree as it is now; only buckets changed since the last one are rebuilt. */
    public Snapshot snapshot() {
        Merkle[] buckets;
        byte[][][] top;
        if (last == null) {
            buckets = new Merkle[BUCKETS];
            top = new byte[BUCKET_BITS + 1][][];
            for (int l = 0; l <= BUCKET_BITS; l++) top[l] = new byte[BUCKETS >>> l][];
            for (int b = 0; b < BUCKETS; b++) {
                buckets[b] = bucketTree(leafHashes[b]);
                top[0][b] = buckets[b].rootUnsafe();
            }
            for (int l = 1; l <= BUCKET_BITS; l++) {
                byte[] emptyHere = EMPTY_LEVELS[l];
                for (int i = 0; i < top[l].length; i++) {
                    byte[] a = top[l - 1][2 * i], c = top[l - 1][2 * i + 1];
                    // Both children empty subtrees: the parent is the precomputed empty hash.
                    top[l][i] = (a == EMPTY_LEVELS[l - 1] && c == EMPTY_LEVELS[l - 1])
                            ? emptyHere : Merkle.nodeHash(a, c);
                }
            }
        } else {
            if (dirty.isEmpty()) return last;
            buckets = last.buckets.clone();
            top = new byte[BUCKET_BITS + 1][][];
            for (int l = 0; l <= BUCKET_BITS; l++) top[l] = last.top[l].clone();
            for (int b = dirty.nextSetBit(0); b >= 0; b = dirty.nextSetBit(b + 1)) {
                buckets[b] = bucketTree(leafHashes[b]);
                top[0][b] = buckets[b].rootUnsafe();
            }
            // Recompute each level once over the parents of dirty nodes.
            BitSet level = (BitSet) dirty.clone();
            for (int l = 1; l <= BUCKET_BITS; l++) {
                BitSet parents = new BitSet(top[l].length);
                for (int i = level.nextSetBit(0); i >= 0; i = level.nextSetBit(i + 1)) parents.set(i >>> 1);
                for (int i = parents.nextSetBit(0); i >= 0; i = parents.nextSetBit(i + 1)) {
                    top[l][i] = Merkle.nodeHash(top[l - 1][2 * i], top[l - 1][2 * i + 1]);
                }
                level = parents;
            }
        }
        dirty.clear();
        last = new Snapshot(buckets, top);
        return last;
    }

    /** Builds a snapshot from scratch over the given leaves; used to cross-check the incremental one. */
    public static Snapshot fromScratch(SortedMap<String, byte[]> leaves) {
        IncrementalMerkle m = new IncrementalMerkle();
        for (Map.Entry<String, byte[]> e : leaves.entrySet()) m.put(e.getKey(), e.getValue());
        return m.snapshot();
    }

    /** An immutable checkpoint tree. Safe to read from any thread. */
    public static final class Snapshot {
        private final Merkle[] buckets;
        private final byte[][][] top;

        private Snapshot(Merkle[] buckets, byte[][][] top) {
            this.buckets = buckets;
            this.top = top;
        }

        public byte[] root() {
            return top[BUCKET_BITS][0].clone();
        }

        public boolean contains(String key) {
            return buckets[bucketOf(key)].contains(key);
        }

        public int size() {
            int n = 0;
            for (Merkle m : buckets) n += m.size();
            return n;
        }

        /** In-bucket path, then the bucket's path to the root. Verifies with {@link Merkle#verify}. */
        public Merkle.Proof prove(String key) {
            int b = bucketOf(key);
            Merkle.Proof inner = buckets[b].prove(key);
            if (inner == null) return null;
            List<byte[]> siblings = new ArrayList<>(inner.siblings().size() + BUCKET_BITS);
            List<Boolean> right = new ArrayList<>(inner.siblings().size() + BUCKET_BITS);
            siblings.addAll(inner.siblings());
            right.addAll(inner.siblingOnRight());
            int i = b;
            for (int l = 0; l < BUCKET_BITS; l++) {
                boolean even = (i & 1) == 0;
                siblings.add(top[l][even ? i + 1 : i - 1]);
                right.add(even);
                i >>>= 1;
            }
            return new Merkle.Proof(key, siblings, right);
        }
    }
}
