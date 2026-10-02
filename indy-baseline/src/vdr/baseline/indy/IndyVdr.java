package vdr.baseline.indy;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Binding to libindy_vdr, the Hyperledger Indy ledger client (baseline plan §2).
 *
 * <p>indy-vdr is used rather than indy-sdk, which is deprecated: benchmarking a deprecated client
 * is a reviewer objection we do not need to invite.
 *
 * <p>Why a direct FFM binding rather than driving Indy from Python: baseline plan §8 requires ONE
 * load generator across both systems. Two generators mean two definitions of throughput, two
 * arrival models and two treatments of coordinated omission, at which point the rows are not
 * comparable and the study collapses. The generator stays in Java; only the backend changes.
 *
 * <p><b>Java version.</b> {@code java.lang.foreign} is a preview API in Java 21, so this module
 * compiles and runs with {@code --enable-preview}. On Java 22+ the API is final and the flag can be
 * dropped with no source change. This is why the Indy backend is a separate compilation unit from
 * vdr-core, which stays preview-free.
 *
 * <p>Threading: the library is thread-safe; async results arrive on library-owned threads and are
 * delivered through {@link CompletableFuture}. The callback stubs and their arena live for the
 * process lifetime because the library may invoke them at any time before pool close.
 *
 * <p><b>2026-09-20 fixes.</b>
 * <ul>
 *   <li>pool_refresh's callback is {@code (cb_id, err)} with NO response pointer. Sharing the
 *       three-argument submit stub made Java read a garbage register as a char* and SIGSEGV in
 *       getByte. Refresh now has its own two-argument stub.</li>
 *   <li>GET_NYM timestamp is -1 (unset), matching the official Python binding. 0 risks a
 *       state-at-epoch-0 read that returns no data and makes resolve look artificially fast.</li>
 *   <li>Strings returned through out-parameters are released with indy_vdr_string_free.
 *       Callback response strings are library-owned and are NOT freed.</li>
 *   <li>Exceptions inside upcalls are caught; an escaping exception would abort the JVM.</li>
 * </ul>
 */
public final class IndyVdr implements AutoCloseable {

    private static final Linker LINKER = Linker.nativeLinker();

    /** FfiByteBuffer: { int64 len; uint8* value } — passed BY VALUE. */
    private static final StructLayout BYTE_BUFFER = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("len"),
            ValueLayout.ADDRESS.withName("value"));

    private final Arena arena = Arena.ofShared();
    private final SymbolLookup lib;

    private final MethodHandle version;
    private final MethodHandle getCurrentError;
    private final MethodHandle stringFree;
    private final MethodHandle setConfig;
    private final MethodHandle setProtocolVersion;
    private final MethodHandle buildNymRequest;
    private final MethodHandle buildGetNymRequest;
    private final MethodHandle buildAttribRequest;
    private final MethodHandle buildGetAttribRequest;
    private final MethodHandle buildRevocRegEntryRequest;
    private final MethodHandle buildGetRevocRegDeltaRequest;
    private final MethodHandle requestGetBody;
    private final MethodHandle requestGetSignatureInput;
    private final MethodHandle requestSetSignature;
    private final MethodHandle requestFree;
    private final MethodHandle poolCreate;
    private final MethodHandle poolSubmitRequest;
    private final MethodHandle poolRefresh;
    private final MethodHandle poolClose;

    /** callback(int64 cb_id, int64 err, const char* response) — pool_submit_request. */
    private final MemorySegment responseCallbackStub;
    /** callback(int64 cb_id, int64 err) — pool_refresh (no response argument). */
    private final MemorySegment statusCallbackStub;

    /** cb_id -> pending future. */
    private final ConcurrentHashMap<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicLong callbackIds = new AtomicLong(1);

    public IndyVdr(String libraryPath) {
        this.lib = SymbolLookup.libraryLookup(libraryPath, arena);

        // Every entry point returns int64: 0 on success, error code otherwise.
        this.version = downcall("indy_vdr_version", FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.getCurrentError = downcallErr("indy_vdr_get_current_error", ValueLayout.ADDRESS);
        this.stringFree = downcall("indy_vdr_string_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.setConfig = downcallErr("indy_vdr_set_config", ValueLayout.ADDRESS);
        this.setProtocolVersion = downcallErr("indy_vdr_set_protocol_version", ValueLayout.JAVA_LONG);
        this.buildNymRequest = downcallErr("indy_vdr_build_nym_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        this.buildGetNymRequest = downcallErr("indy_vdr_build_get_nym_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS);
        this.buildAttribRequest = downcallErr("indy_vdr_build_attrib_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.buildGetAttribRequest = downcallErr("indy_vdr_build_get_attrib_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.buildRevocRegEntryRequest = downcallErr("indy_vdr_build_revoc_reg_entry_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS);
        this.buildGetRevocRegDeltaRequest = downcallErr("indy_vdr_build_get_revoc_reg_delta_request",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        this.requestGetBody = downcallErr("indy_vdr_request_get_body",
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        this.requestGetSignatureInput = downcallErr("indy_vdr_request_get_signature_input",
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        this.requestSetSignature = downcallErr("indy_vdr_request_set_signature",
                ValueLayout.JAVA_LONG, BYTE_BUFFER);
        this.requestFree = downcallErr("indy_vdr_request_free", ValueLayout.JAVA_LONG);
        this.poolCreate = downcallErr("indy_vdr_pool_create",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        this.poolSubmitRequest = downcallErr("indy_vdr_pool_submit_request",
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG);
        this.poolRefresh = downcallErr("indy_vdr_pool_refresh",
                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        this.poolClose = downcallErr("indy_vdr_pool_close", ValueLayout.JAVA_LONG);

        this.responseCallbackStub = makeResponseCallbackStub();
        this.statusCallbackStub = makeStatusCallbackStub();
    }

    // ------------------------------------------------------------------ plumbing

    private MethodHandle downcall(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(
                lib.find(name).orElseThrow(() -> new IllegalStateException("missing symbol: " + name)),
                fd);
    }

    /** Entry points returning an int64 error code. */
    private MethodHandle downcallErr(String name, MemoryLayout... args) {
        return downcall(name, FunctionDescriptor.of(ValueLayout.JAVA_LONG, args));
    }

    /** void callback(int64 cb_id, int64 err, const char* response) */
    private MemorySegment makeResponseCallbackStub() {
        try {
            MethodHandle target = MethodHandles.lookup().bind(this, "onResponseCallback",
                    MethodType.methodType(void.class, long.class, long.class, MemorySegment.class));
            return LINKER.upcallStub(target,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS),
                    arena);
        } catch (Exception e) {
            throw new IllegalStateException("could not create response upcall stub", e);
        }
    }

    /** void callback(int64 cb_id, int64 err) */
    private MemorySegment makeStatusCallbackStub() {
        try {
            MethodHandle target = MethodHandles.lookup().bind(this, "onStatusCallback",
                    MethodType.methodType(void.class, long.class, long.class));
            return LINKER.upcallStub(target,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                    arena);
        } catch (Exception e) {
            throw new IllegalStateException("could not create status upcall stub", e);
        }
    }

    /**
     * Invoked from native code. The response pointer is valid only for the duration of this call
     * and is owned by the library: copy it now, never free it.
     */
    @SuppressWarnings("unused")
    private void onResponseCallback(long cbId, long err, MemorySegment response) {
        CompletableFuture<String> fut = pending.remove(cbId);
        if (fut == null) {
            return; // already completed or timed out
        }
        try {
            if (err != 0) {
                fut.completeExceptionally(new IndyVdrException(err, currentError()));
            } else {
                fut.complete(readString(response));
            }
        } catch (Throwable t) {
            // An exception escaping an upcall aborts the JVM.
            fut.completeExceptionally(t);
        }
    }

    /** Invoked from native code for callbacks that carry no response (pool_refresh). */
    @SuppressWarnings("unused")
    private void onStatusCallback(long cbId, long err) {
        CompletableFuture<String> fut = pending.remove(cbId);
        if (fut == null) {
            return;
        }
        try {
            if (err != 0) {
                fut.completeExceptionally(new IndyVdrException(err, currentError()));
            } else {
                fut.complete(null);
            }
        } catch (Throwable t) {
            fut.completeExceptionally(t);
        }
    }

    private static String readString(MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) {
            return null;
        }
        return p.reinterpret(Long.MAX_VALUE).getUtf8String(0);
    }

    /** Copy a library-allocated string, then release it with indy_vdr_string_free. */
    private String takeString(MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) {
            return null;
        }
        String s = readString(p);
        try {
            stringFree.invokeExact(p);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return s;
    }

    private void check(long code, String what) {
        if (code != 0) {
            throw new IndyVdrException(code, what + ": " + currentError());
        }
    }

    /** The library's last error, as JSON. Read immediately after a failure. */
    public String currentError() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.ADDRESS);
            long rc = (long) getCurrentError.invokeExact(out);
            if (rc != 0) {
                return "(no error detail)";
            }
            String s = takeString(out.get(ValueLayout.ADDRESS, 0));
            return s == null ? "(no error detail)" : s;
        } catch (Throwable t) {
            return "(error detail unavailable: " + t + ")";
        }
    }

    private MemorySegment str(SegmentAllocator a, String s) {
        return s == null ? MemorySegment.NULL : a.allocateUtf8String(s);
    }

    // -------------------------------------------------------------------- library

    public String version() {
        try {
            return readString((MemorySegment) version.invokeExact());
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Global client configuration (indy-vdr PoolConfig), e.g.
     * {"conn_request_limit":100,"conn_active_timeout":5}. Must be called before pool_create.
     * Fields left out keep the library defaults.
     */
    public void setConfig(String configJson) {
        try (Arena a = Arena.ofConfined()) {
            check((long) setConfig.invokeExact(str(a, configJson)), "set_config");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Indy protocol version; 2 for any currently supported pool. */
    public void setProtocolVersion(long v) {
        try {
            check((long) setProtocolVersion.invokeExact(v), "set_protocol_version");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ------------------------------------------------------------------- requests

    /**
     * register / update — NYM. Under did:indy the NYM is the DID record (baseline plan §3).
     *
     * @param version self-certification method; -1 leaves it unset
     */
    public long buildNymRequest(String submitterDid, String dest, String verkey, String alias,
                                String role, String diddocContent, int version) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildNymRequest.invokeExact(
                    str(a, submitterDid), str(a, dest), str(a, verkey), str(a, alias),
                    str(a, role), str(a, diddocContent), version, out), "build_nym_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * resolve — GET_NYM. Read path; the reply carries a state proof (baseline plan §4).
     * seq_no and timestamp are both -1 (unset): read the current state, as the Python binding does.
     */
    public long buildGetNymRequest(String submitterDid, String dest) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildGetNymRequest.invokeExact(
                    str(a, submitterDid), str(a, dest), -1, -1L, out), "build_get_nym_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long buildAttribRequest(String submitterDid, String targetDid, String hash, String raw,
                                   String enc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildAttribRequest.invokeExact(
                    str(a, submitterDid), str(a, targetDid), str(a, hash), str(a, raw),
                    str(a, enc), out), "build_attrib_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long buildGetAttribRequest(String submitterDid, String targetDid, String raw, String hash,
                                      String enc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildGetAttribRequest.invokeExact(
                    str(a, submitterDid), str(a, targetDid), str(a, raw), str(a, hash),
                    str(a, enc), out), "build_get_attrib_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * revoke — REVOC_REG_ENTRY. One entry carries an accumulator value plus a delta of revoked
     * indices, so a single transaction can revoke many credentials. This is Indy's native batching
     * and the direct analogue of the Tailored BFT gateway's batchSize; baseline plan §5 requires it
     * to be swept the same way, because fixing it at one revocation per transaction while our
     * gateway batches 250 would manufacture a speed-up.
     */
    public long buildRevocRegEntryRequest(String submitterDid, String revocRegDefId,
                                          String revocRegDefType, String entryJson) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildRevocRegEntryRequest.invokeExact(
                    str(a, submitterDid), str(a, revocRegDefId), str(a, revocRegDefType),
                    str(a, entryJson), out), "build_revoc_reg_entry_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * GET_REVOC_REG_DELTA. The ledger is the only authority on a registry's current accumulator:
     * the next REVOC_REG_ENTRY's prevAccum must equal it exactly.
     *
     * @param fromTs -1 for "from the beginning"; toTs a unix timestamp at or after now
     */
    public long buildGetRevocRegDeltaRequest(String submitterDid, String revocRegDefId,
                                             long fromTs, long toTs) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) buildGetRevocRegDeltaRequest.invokeExact(
                    str(a, submitterDid), str(a, revocRegDefId), fromTs, toTs, out),
                    "build_get_revoc_reg_delta_request");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public String requestBody(long requestHandle) {
        return stringOut(requestGetBody, requestHandle, "request_get_body");
    }

    /** The canonical bytes to sign. Indy's signature is over this, not over the raw JSON. */
    public byte[] signatureInput(long requestHandle) {
        String s = stringOut(requestGetSignatureInput, requestHandle, "request_get_signature_input");
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    public void setSignature(long requestHandle, byte[] signature) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment bytes = a.allocateArray(ValueLayout.JAVA_BYTE, signature.length);
            MemorySegment.copy(signature, 0, bytes, ValueLayout.JAVA_BYTE, 0, signature.length);
            MemorySegment buf = a.allocate(BYTE_BUFFER);
            buf.set(ValueLayout.JAVA_LONG, 0, signature.length);
            buf.set(ValueLayout.ADDRESS, 8, bytes);
            check((long) requestSetSignature.invokeExact(requestHandle, buf),
                    "request_set_signature");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void freeRequest(long requestHandle) {
        try {
            long rc = (long) requestFree.invokeExact(requestHandle);
            if (rc != 0) {
                // Freeing is best-effort; a failure here must not mask the caller's own outcome.
                System.err.println("indy_vdr_request_free failed: " + currentError());
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Out-parameter strings are library-allocated: copy, then free. */
    private String stringOut(MethodHandle mh, long handle, String what) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.ADDRESS);
            check((long) mh.invokeExact(handle, out), what);
            return takeString(out.get(ValueLayout.ADDRESS, 0));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ----------------------------------------------------------------------- pool

    /** @param paramsJson e.g. {"transactions_path":"/path/to/pool_transactions_genesis"} */
    public long poolCreate(String paramsJson) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.JAVA_LONG);
            check((long) poolCreate.invokeExact(str(a, paramsJson), out), "pool_create");
            return out.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Submits a prepared request. Non-blocking: the library completes the future on its own thread.
     *
     * <p>This is why the backend never needs a thread per in-flight request, which
     * {@code bench/METRICS.md} requires — a blocking join per operation would measure generator
     * thread starvation rather than the ledger.
     */
    public CompletableFuture<String> poolSubmitRequest(long poolHandle, long requestHandle) {
        long cbId = callbackIds.getAndIncrement();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.put(cbId, fut);
        try {
            long rc = (long) poolSubmitRequest.invokeExact(poolHandle, requestHandle,
                    responseCallbackStub, cbId);
            if (rc != 0) {
                pending.remove(cbId);
                fut.completeExceptionally(new IndyVdrException(rc, "pool_submit_request: " + currentError()));
            }
        } catch (Throwable t) {
            pending.remove(cbId);
            fut.completeExceptionally(t);
        }
        return fut;
    }

    /**
     * Refresh the pool's view of its validators. Run before warm-up, never inside a window.
     * The native callback carries no response, so the future completes with null.
     */
    public CompletableFuture<String> poolRefresh(long poolHandle) {
        long cbId = callbackIds.getAndIncrement();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.put(cbId, fut);
        try {
            long rc = (long) poolRefresh.invokeExact(poolHandle, statusCallbackStub, cbId);
            if (rc != 0) {
                pending.remove(cbId);
                fut.completeExceptionally(new IndyVdrException(rc, "pool_refresh: " + currentError()));
            }
        } catch (Throwable t) {
            pending.remove(cbId);
            fut.completeExceptionally(t);
        }
        return fut;
    }

    public void poolClose(long poolHandle) {
        try {
            check((long) poolClose.invokeExact(poolHandle), "pool_close");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void close() {
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("library closed")));
        pending.clear();
        arena.close();
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        return new IllegalStateException(t);
    }

    /** Carries the library's error code and its JSON detail. */
    public static final class IndyVdrException extends RuntimeException {
        public final long code;

        public IndyVdrException(long code, String message) {
            super("indy-vdr error " + code + ": " + message);
            this.code = code;
        }
    }
}
