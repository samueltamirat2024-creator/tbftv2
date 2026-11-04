package vdr.bench;

/**
 * Latency histogram at 1 microsecond precision, log-linear buckets.
 *
 * Two rules from plan section 12.1 are enforced by this class rather than by convention:
 *
 *  1. Percentiles are read from a MERGED histogram across all runs of a configuration,
 *     never averaged from per-run percentiles -- hence {@link #merge}.
 *  2. Nothing is discarded. DepSpace dropped the 5% highest-variance values; that practice
 *     is indefensible for a p99 claim, so there is deliberately no trimming method here.
 *
 * A stand-in for HdrHistogram, which cannot be fetched in this build environment.
 */
public final class Histogram {

    private static final int SUB_BUCKET_BITS = 7;              // 128 linear slots per octave
    private static final int SUB_BUCKETS = 1 << SUB_BUCKET_BITS;
    private static final int OCTAVES = 40;

    private final long[] counts = new long[OCTAVES * SUB_BUCKETS];
    private long total = 0;
    private long max = 0;
    private long sum = 0;

    /**
     * Samples whose latency came out negative, i.e. the generator issued the operation before its
     * scheduled arrival. Clamping these to zero deflates p50/p95 silently, so they are counted and
     * surfaced rather than absorbed. A run reporting a non-zero value here is not usable.
     */
    private long earlyIssued = 0;

    public long earlyIssued() { return earlyIssued; }

    public void record(long micros) {
        if (micros < 0) { earlyIssued++; micros = 0; }
        counts[index(micros)]++;
        total++;
        sum += micros;
        if (micros > max) max = micros;
    }

    public void merge(Histogram other) {
        this.earlyIssued += other.earlyIssued;
        for (int i = 0; i < counts.length; i++) counts[i] += other.counts[i];
        total += other.total;
        sum += other.sum;
        max = Math.max(max, other.max);
    }

    public long count() { return total; }

    public double mean() { return total == 0 ? 0 : (double) sum / total; }

    public long max() { return max; }

    public double percentile(double p) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(p / 100.0 * total);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= target) return value(i);
        }
        return max;
    }

    /** Non-empty buckets as [lower edge us, count]; the form deploy/vps/stats.py merges and reads. */
    void appendJson(StringBuilder out) {
        out.append("{\"n\":").append(total).append(",\"sum\":").append(sum)
           .append(",\"max\":").append(max).append(",\"early\":").append(earlyIssued)
           .append(",\"buckets\":[");
        boolean first = true;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            if (!first) out.append(',');
            first = false;
            out.append('[').append(value(i)).append(',').append(counts[i]).append(']');
        }
        out.append("]}");
    }

    private static int index(long v) {
        if (v < SUB_BUCKETS) return (int) v;
        int msb = 63 - Long.numberOfLeadingZeros(v);
        int octave = msb - SUB_BUCKET_BITS + 1;              // >= 1
        if (octave >= OCTAVES) octave = OCTAVES - 1;
        int sub = (int) ((v >>> octave) & (SUB_BUCKETS - 1));
        return Math.min(octave * SUB_BUCKETS + sub, OCTAVES * SUB_BUCKETS - 1);
    }

    private static long value(int index) {
        int octave = index / SUB_BUCKETS;
        int sub = index % SUB_BUCKETS;
        return octave == 0 ? sub : ((long) sub) << octave;
    }

    /** Two-sided 95% Student's t quantiles for df = 1..30; beyond that the normal value is close. */
    private static final double[] T95 = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    /** 95% confidence interval half-width for a set of per-run throughput values. */
    public static double ci95(double[] samples) {
        if (samples.length < 2) return 0;
        double mean = 0;
        for (double s : samples) mean += s;
        mean /= samples.length;
        double var = 0;
        for (double s : samples) var += (s - mean) * (s - mean);
        var /= (samples.length - 1);
        double t = samples.length - 2 < T95.length ? T95[samples.length - 2] : 1.96;
        return t * Math.sqrt(var / samples.length);
    }

    public static double mean(double[] samples) {
        double m = 0;
        for (double s : samples) m += s;
        return samples.length == 0 ? 0 : m / samples.length;
    }
}
