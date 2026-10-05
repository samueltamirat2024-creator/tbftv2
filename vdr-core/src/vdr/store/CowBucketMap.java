package vdr.store;

import vdr.merkle.IncrementalMerkle;

import java.util.BitSet;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A map whose snapshots cost O(entries changed since the last snapshot), not O(size).
 *
 * <p>Checkpoints used to deep-copy the whole did-to-latest-document map on every checkpoint. This
 * keeps the live map in {@link IncrementalMerkle#BUCKETS} buckets and copies only the buckets
 * written since the previous snapshot; untouched buckets are shared between snapshots, which are
 * immutable and safe to read concurrently with execution.
 */
final class CowBucketMap<V> {

    @SuppressWarnings("unchecked")
    private final TreeMap<String, V>[] live = new TreeMap[IncrementalMerkle.BUCKETS];
    private final BitSet dirty = new BitSet(IncrementalMerkle.BUCKETS);
    private Snapshot<V> last;

    CowBucketMap() {
        for (int i = 0; i < live.length; i++) live[i] = new TreeMap<>();
        dirty.set(0, live.length);
    }

    void put(String key, V value) {
        int b = IncrementalMerkle.bucketOf(key);
        live[b].put(key, value);
        dirty.set(b);
    }

    void clear() {
        for (TreeMap<String, V> m : live) m.clear();
        dirty.set(0, live.length);
        last = null;
    }

    Snapshot<V> snapshot() {
        if (last != null && dirty.isEmpty()) return last;
        @SuppressWarnings("unchecked")
        Map<String, V>[] buckets = last == null ? new Map[live.length] : last.buckets.clone();
        for (int b = dirty.nextSetBit(0); b >= 0; b = dirty.nextSetBit(b + 1)) {
            buckets[b] = live[b].isEmpty() ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new TreeMap<>(live[b]));
        }
        dirty.clear();
        last = new Snapshot<>(buckets);
        return last;
    }

    /** Immutable point-in-time view. */
    static final class Snapshot<V> {
        private final Map<String, V>[] buckets;

        private Snapshot(Map<String, V>[] buckets) {
            this.buckets = buckets;
        }

        V get(String key) {
            return buckets[IncrementalMerkle.bucketOf(key)].get(key);
        }

        int size() {
            int n = 0;
            for (Map<String, V> m : buckets) n += m.size();
            return n;
        }
    }
}
