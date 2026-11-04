package vdr.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Raw per-run output, written as each run ends so a crash keeps the runs before it.
 *
 * <p>{@code runs.jsonl} holds what rows.tsv merges away: each run's own histograms, which is what
 * lets deploy/vps/stats.py put a confidence interval on a percentile. {@code timeseries.jsonl} holds
 * that run's seconds ({@link TimeSeries}). Both are replaced when a JVM first writes them, as
 * rows.tsv is. Set -Dbench.rawOutput=false to write neither.
 */
final class RunLog {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("bench.rawOutput", "true"));
    private static final Path RUNS = Path.of("runs.jsonl");
    private static final Path SERIES = Path.of("timeseries.jsonl");
    private static boolean started = false;

    private RunLog() {}

    static synchronized void append(String name, Bench.Mix mix, int rate, int n, int run,
                                    long warmupMs, long steadyMs, Bench.RunOutcome o) throws IOException {
        if (!ENABLED) return;
        if (!started) {
            Files.deleteIfExists(RUNS);
            Files.deleteIfExists(SERIES);
            started = true;
        }
        String id = String.format(Locale.ROOT, "\"name\":\"%s\",\"mix\":\"%s\",\"rate\":%d,\"run\":%d",
                escape(name), mix.label(), rate, run);

        StringBuilder r = new StringBuilder("{").append(id);
        r.append(String.format(Locale.ROOT,
                ",\"n\":%d,\"start_epoch_ms\":%d,\"warmup_ms\":%d,\"steady_ms\":%d,\"throughput\":%.3f"
                + ",\"failures\":%d,\"overloaded\":%b,\"valid\":%b,\"invalid_reason\":\"%s\",\"contended\":%d"
                + ",\"tier0_reads\":%d,\"tier0_fallbacks\":%d,\"burst_size\":%d,\"burst_drain_ms\":%.3f"
                + ",\"burst_failed\":%d,\"burst_txns\":%d,\"burst_confirmed\":%d",
                n, o.series().startEpochMs(), warmupMs, steadyMs, o.throughput(),
                o.failures(), o.overloaded(), o.invalidReason().isEmpty(), escape(o.invalidReason()),
                o.contendedUpdates(), o.tier0Reads(), o.tier0Fallbacks(), o.burstSize(),
                o.burstDrainMs(), o.burstFailed(), o.burstTxns(), o.burstTxnsConfirmed()));
        r.append(",\"hist\":");
        o.hist().appendJson(r);
        r.append(",\"burst_hist\":");
        o.burst().appendJson(r);
        r.append(",\"ops\":{");
        Bench.OpType[] types = Bench.OpType.values();
        for (int t = 0; t < types.length; t++) {
            if (t > 0) r.append(',');
            r.append('"').append(types[t].label()).append("\":{")
             .append(String.format(Locale.ROOT, "\"tput\":%.3f,\"hist\":", o.opThroughput()[t]));
            o.opHist()[t].appendJson(r);
            r.append('}');
        }
        r.append("}}\n");
        Files.writeString(RUNS, r, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        StringBuilder s = new StringBuilder();
        o.series().appendJson(s, id);
        Files.writeString(SERIES, s, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ').replace('\t', ' ');
    }
}
