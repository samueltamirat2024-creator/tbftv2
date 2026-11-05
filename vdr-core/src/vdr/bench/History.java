package vdr.bench;

import vdr.baseline.Backend;
import vdr.baseline.BackendFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operation history for the fault campaign (deploy/vps/faults/), checked by check-history.py.
 *
 * <p>This is not a load test, so nothing here is comparable with {@link Bench}. Workers tick at a low
 * fixed rate over a small set of DIDs, so reads really do overlap writes to the same DID. Every
 * operation is written as an invoke line before it starts and a completion line after it ends, with
 * one of three outcomes:
 *
 * <ul>
 *   <li>{@code ok}: the system acknowledged it.
 *   <li>{@code fail}: the system definitely refused it, so it had no effect.
 *   <li>{@code info}: the result is unknown (a timeout or lost connection), so it may or may not take
 *       effect later. An invoke with no completion, because the client was killed, means the same.
 * </ul>
 *
 * <p><b>Writers.</b> Both backends serialise updates per DID and need the current version or key, so
 * concurrency here is between readers and a writer, not between writers. After an {@code info} write
 * the client no longer knows the DID's current version, so it stops writing that DID and takes the
 * next one from a reserve. Reads and the final check still cover it.
 *
 * <p><b>Clock.</b> Times are microseconds on one wall clock taken at start and advanced by the
 * monotonic clock, so two client processes on one host and the nemesis events can be lined up.
 */
public final class History {

    private static final long DURATION_MS = Long.getLong("history.durationMs", 240_000L);
    private static final long OP_TIMEOUT_MS = Long.getLong("history.opTimeoutMs", 5_000L);
    private static final long QUIESCE_MS = Long.getLong("history.quiesceMs", 10_000L);
    private static final long FINAL_TIMEOUT_MS = Long.getLong("history.finalTimeoutMs", 120_000L);
    private static final int DIDS = Integer.getInteger("history.dids", 200);
    private static final int HOT_DIDS = Integer.getInteger("history.hotDids", 16);
    private static final int WRITERS = Integer.getInteger("history.writers", 4);
    private static final int READERS = Integer.getInteger("history.readersPerLevel", 2);
    private static final long WRITE_PERIOD_MS = Long.getLong("history.writePeriodMs", 200L);
    private static final long READ_PERIOD_MS = Long.getLong("history.readPeriodMs", 100L);
    private static final long REVOKE_PERIOD_MS = Long.getLong("history.revokePeriodMs", 1_000L);
    private static final String OUT = System.getProperty("history.out", "history.jsonl");
    /** A killed client's history: its DIDs are read, and checked at the end, but not written. */
    private static final String RESUME_FROM = System.getProperty("history.resumeFrom", "");

    private final Backend backend;
    private final BufferedWriter out;
    private final AtomicLong ids = new AtomicLong();
    private final long startEpochUs = System.currentTimeMillis() * 1000;
    private final long startNanos = System.nanoTime();
    private final ExecutorService calls = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "history-call");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** DIDs readers pick from. Writers take one out of writable while they use it. */
    private final List<String> hot = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> writable = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> reserve = new ConcurrentLinkedQueue<>();
    private final Set<String> everyDid = ConcurrentHashMap.newKeySet();
    private final List<String[]> revocations = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong revokeOk = new AtomicLong();

    private History(Backend backend, BufferedWriter out) {
        this.backend = backend;
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        Backend backend = BackendFactory.fromSystemProperties().create(n, 100);
        int status;
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(OUT), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            status = new History(backend, out).run();
        }
        // The BFT-SMaRt client's threads are not daemons.
        System.exit(status);
    }

    private int run() throws Exception {
        line(String.format("{\"t\":\"start\",\"backend\":\"%s\",\"levels\":%s,\"ts\":%d,"
                        + "\"write_period_ms\":%d,\"writers\":%d,\"read_period_ms\":%d,\"readers_per_level\":%d}",
                esc(backend.name()), jsonList(backend.readLevels()), now(), WRITE_PERIOD_MS, WRITERS,
                READ_PERIOD_MS, READERS));

        List<String> dids = backend.populate(DIDS);
        String strongest = backend.readLevels().get(backend.readLevels().size() - 1);
        for (String did : dids) {
            line(String.format("{\"t\":\"init\",\"did\":\"%s\",\"value\":%s,\"ts\":%d}", esc(did),
                    jsonString(retryRead(did, strongest)), now()));
        }
        everyDid.addAll(dids);
        for (int i = 0; i < dids.size(); i++) {
            if (i < HOT_DIDS) {
                hot.add(dids.get(i));
                writable.add(dids.get(i));
            } else {
                reserve.add(dids.get(i));
            }
        }
        List<String> resumed = resumedDids();
        everyDid.addAll(resumed);
        hot.addAll(resumed);
        System.out.printf("HISTORY ready: %d DIDs, %d resumed, levels %s, %d ms%n", dids.size(),
                resumed.size(), backend.readLevels(), DURATION_MS);

        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < WRITERS; w++) workers.add(worker("write-" + w, WRITE_PERIOD_MS, this::writeOnce));
        for (String level : backend.readLevels()) {
            for (int r = 0; r < READERS; r++) {
                String process = "read-" + level + "-" + r;
                Random random = new Random(process.hashCode());
                workers.add(worker(process, READ_PERIOD_MS, p -> readOnce(p, level, random)));
            }
        }
        workers.add(worker("revoke", REVOKE_PERIOD_MS, this::revokeOnce));
        workers.forEach(Thread::start);
        Thread.sleep(DURATION_MS);
        running.set(false);
        for (Thread t : workers) t.join(OP_TIMEOUT_MS + 2_000);

        Thread.sleep(QUIESCE_MS);
        finalChecks(strongest);
        return 0;
    }

    // ------------------------------------------------------------------ workers

    private interface Tick {
        void run(String process) throws Exception;
    }

    /** Ticks missed while an operation hangs are lost, and count against goodput in the checker. */
    private Thread worker(String process, long periodMs, Tick tick) {
        Thread t = new Thread(() -> {
            long next = System.nanoTime();
            while (running.get()) {
                try {
                    tick.run(process);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    System.err.println(process + ": " + e);
                }
                next += TimeUnit.MILLISECONDS.toNanos(periodMs);
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    try { TimeUnit.NANOSECONDS.sleep(sleep); } catch (InterruptedException e) { return; }
                } else {
                    next = System.nanoTime();
                }
            }
        }, process);
        t.setDaemon(true);
        return t;
    }

    private void writeOnce(String process) throws InterruptedException {
        String did = writable.poll();
        if (did == null) return;
        long id = ids.incrementAndGet();
        byte[] document = String.format("{\"id\":\"%s\",\"op\":%d,\"started\":%d}", did, id, startEpochUs)
                .getBytes(StandardCharsets.UTF_8);
        String value = backend.writeValue(did, document);
        invoke(id, process, "write", did, value, "");
        Outcome o = call(() -> {
            backend.update(did, 1, document);
            return value;
        }, true);
        complete(id, o, value);
        if (o.kind.equals("info")) {
            String next = reserve.poll();
            if (next != null) {
                synchronized (hot) { hot.add(next); }
                writable.add(next);
            }
        } else {
            writable.add(did);
        }
    }

    private void readOnce(String process, String level, Random random) throws InterruptedException {
        String did;
        synchronized (hot) { did = hot.get(random.nextInt(hot.size())); }
        long id = ids.incrementAndGet();
        invoke(id, process, "read", did, null, level);
        Outcome o = call(() -> backend.observe(did, (int) id, level), false);
        complete(id, o, o.value);
    }

    private void revokeOnce(String process) throws InterruptedException {
        String did;
        synchronized (hot) { did = hot.get(0); }
        String registry = backend.registryIdFor(did);
        long id = ids.incrementAndGet();
        String handle = vdr.crypto.Crypto.hex(vdr.crypto.Crypto.sha256(
                ("history-" + startEpochUs + "-" + id).getBytes(StandardCharsets.UTF_8)));
        revocations.add(new String[] {registry, handle});
        invoke(id, process, "revoke", registry, handle, "");
        Outcome o = call(() -> {
            backend.submitRevoke(registry, handle).get(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return handle;
        }, true);
        if (o.kind.equals("ok")) revokeOk.incrementAndGet();
        complete(id, o, handle);
        if (o.kind.equals("ok") && backend.observeRevoked(registry, handle, 0) != null) {
            long status = ids.incrementAndGet();
            invoke(status, process, "revstatus", registry, handle, "");
            Outcome s = call(() -> String.valueOf(backend.observeRevoked(registry, handle, (int) status)), false);
            complete(status, s, s.value);
        }
    }

    // ------------------------------------------------------------------ the end of the run

    /** After healing: every DID at the strongest read, then state digests and the revoked count. */
    private void finalChecks(String level) throws Exception {
        // One clock for the whole pass, renewed by each answer. A cluster that never recovers would
        // otherwise cost the full timeout per DID: hours, on a campaign that runs unattended.
        Outcome gaveUp = new Outcome("info", null, "no answer within the final-check timeout");
        long answerBy = System.currentTimeMillis() + FINAL_TIMEOUT_MS;
        for (String did : everyDid) {
            long id = ids.incrementAndGet();
            invoke(id, "final", "read", did, null, level);
            Outcome o = gaveUp;
            while (System.currentTimeMillis() < answerBy) {
                o = call(() -> backend.observe(did, (int) id, level), false);
                if (o.kind.equals("ok")) {
                    answerBy = System.currentTimeMillis() + FINAL_TIMEOUT_MS;
                    break;
                }
                Thread.sleep(1_000);
            }
            complete(id, o, o.value);
        }
        for (String[] r : revocations) {
            long id = ids.incrementAndGet();
            invoke(id, "final", "revstatus", r[0], r[1], "");
            Outcome o = System.currentTimeMillis() < answerBy
                    ? call(() -> String.valueOf(backend.observeRevoked(r[0], r[1], (int) id)), false) : gaveUp;
            if (!"null".equals(o.value)) complete(id, o, o.value);
        }
        Outcome count = call(() -> String.valueOf(backend.observeRevokedCount()), false);
        line(String.format("{\"t\":\"revoked_count\",\"count\":%s,\"acked\":%d,\"ts\":%d}",
                count.kind.equals("ok") ? count.value : "-1", revokeOk.get(), now()));

        // Replicas may need traffic to notice they are behind, so ask until they agree or time runs out.
        long deadline = System.currentTimeMillis() + FINAL_TIMEOUT_MS;
        Map<String, String> digests;
        do {
            digests = digestsOrEmpty();
            if (digests.isEmpty() || digests.values().stream().distinct().count() == 1) break;
            Thread.sleep(2_000);
        } while (System.currentTimeMillis() < deadline);
        StringBuilder sb = new StringBuilder();
        digests.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ",")
                .append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"'));
        line(String.format("{\"t\":\"digests\",\"digests\":{%s},\"ts\":%d}", sb, now()));
        line(String.format("{\"t\":\"end\",\"ts\":%d}", now()));
    }

    private Map<String, String> digestsOrEmpty() throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> digests =
                new java.util.concurrent.atomic.AtomicReference<>();
        Outcome o = call(() -> {
            digests.set(backend.stateDigests());
            return "ok";
        }, false, 60_000);
        return o.kind.equals("ok") ? digests.get() : Map.of("client", "digest-request-failed: " + o.error);
    }

    private String retryRead(String did, String level) throws Exception {
        long deadline = System.currentTimeMillis() + FINAL_TIMEOUT_MS;
        while (true) {
            try {
                String value = backend.observe(did, 0, level);
                if (value != null) return value;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() > deadline) throw e;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("no initial value for " + did);
            }
            Thread.sleep(500);
        }
    }

    private static List<String> resumedDids() throws IOException {
        if (RESUME_FROM.isBlank()) return List.of();
        java.util.regex.Pattern init = java.util.regex.Pattern.compile("\"t\":\"init\",\"did\":\"([^\"]+)\"");
        List<String> dids = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(RESUME_FROM))) {
            java.util.regex.Matcher m = init.matcher(line);
            if (m.find()) dids.add(m.group(1));
        }
        return dids;
    }

    // ------------------------------------------------------------------ calls and outcomes

    private record Outcome(String kind, String value, String error) { }

    private interface Call {
        String run() throws Exception;
    }

    private Outcome call(Call body, boolean isWrite) throws InterruptedException {
        return call(body, isWrite, OP_TIMEOUT_MS);
    }

    /** The call keeps running on its own thread after a timeout: it may yet take effect. */
    private Outcome call(Call body, boolean isWrite, long timeoutMs) throws InterruptedException {
        Future<String> future = calls.submit(body::run);
        try {
            return new Outcome("ok", future.get(timeoutMs, TimeUnit.MILLISECONDS), "");
        } catch (TimeoutException e) {
            return new Outcome("info", null, "timeout");
        } catch (ExecutionException e) {
            String message = String.valueOf(e.getCause());
            return new Outcome(isWrite && definitelyRefused(message) ? "fail" : "info", null, message);
        }
    }

    /**
     * Only an explicit refusal counts as "had no effect". Anything else stays unknown, which is the
     * conservative side: it widens what the checker allows, so it cannot create a false violation.
     */
    private static boolean definitelyRefused(String message) {
        if (message.contains("NO_QUORUM")) return false;
        return message.contains(" denied: ") || message.contains("\"op\":\"REJECT\"")
                || message.contains("\"op\":\"REQNACK\"");
    }

    private long now() {
        return startEpochUs + (System.nanoTime() - startNanos) / 1000;
    }

    private void invoke(long id, String process, String f, String key, String value, String level) {
        line(String.format("{\"t\":\"invoke\",\"id\":%d,\"p\":\"%s\",\"f\":\"%s\",\"key\":\"%s\","
                        + "\"value\":%s,\"level\":\"%s\",\"ts\":%d}",
                id, esc(process), f, esc(key), jsonString(value), level, now()));
    }

    private void complete(long id, Outcome o, String value) {
        line(String.format("{\"t\":\"%s\",\"id\":%d,\"value\":%s,\"err\":\"%s\",\"ts\":%d}",
                o.kind, id, jsonString(value), esc(o.error), now()));
    }

    /** Flushed per line: a killed client must leave its invokes behind. */
    private synchronized void line(String json) {
        try {
            out.write(json);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String jsonString(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    private static String jsonList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (String v : values) sb.append(sb.length() == 1 ? "" : ",").append(jsonString(v));
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
