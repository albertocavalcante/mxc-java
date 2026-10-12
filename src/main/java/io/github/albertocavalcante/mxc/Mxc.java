package io.github.albertocavalcante.mxc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.albertocavalcante.mxc.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Entry points for MXC (Microsoft eXecution Container): policy-driven sandboxes for untrusted code.
 * Wraps the native {@code mxc_ffi} library via {@code java.lang.foreign} (JDK 22+; run with {@code
 * --enable-native-access=ALL-UNNAMED}).
 */
public final class Mxc {
    private Mxc() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** Options for launch calls. */
    public record Options(boolean experimental, short rows, short cols) {
        public static final Options DEFAULT = new Options(false, (short) 24, (short) 80);

        public Options withExperimental(boolean v) {
            return new Options(v, rows, cols);
        }

        public Options withPtySize(int rows, int cols) {
            return new Options(experimental, (short) rows, (short) cols);
        }
    }

    // ---- discovery ---------------------------------------------------------

    /** Version of the loaded native library. */
    public static String nativeVersion() {
        return Native.cString(Native.callPtr(Native.VERSION));
    }

    /** Whether the host can launch sandboxes, and which backends. */
    public record PlatformSupport(
            boolean isSupported, String reason, List<String> availableMethods) {}

    public static PlatformSupport platformSupport() {
        JsonNode n = parse(Native.takeString(Native.callPtr(Native.PLATFORM_SUPPORT)));
        return new PlatformSupport(
                n.path("isSupported").asBoolean(),
                n.path("reason").isNull() ? null : n.path("reason").asText(null),
                JSON.convertValue(
                        n.path("availableMethods"), new TypeReference<List<String>>() {}));
    }

    /** Raw JSON array of host-available backends (advisory; shape grows over time). */
    public static JsonNode availableBackends() {
        return parse(Native.takeString(Native.callPtr(Native.AVAILABLE_BACKENDS)));
    }

    // ---- run to completion -------------------------------------------------

    public static Result run(Request request) {
        return runJson(request.toJson(), Options.DEFAULT);
    }

    public static Result run(Request request, Options options) {
        return runJson(request.toJson(), options);
    }

    /**
     * Run a caller-supplied exact-version MXC request JSON (any registered version) to completion.
     * A non-zero workload exit code is not an exception.
     */
    public static Result runJson(String requestJson, Options options) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(Native.RUN_RESULT);
            Native.callInt(
                    Native.RUN_JSON,
                    a.allocateFrom(requestJson),
                    options.experimental() ? 1 : 0,
                    out);
            return consumeRunResult(out);
        }
    }

    /** Like {@link #run(Request)} but killable: interrupting the thread kills the workload. */
    public static Result runInterruptibly(Request request, Options options)
            throws InterruptedException {
        try (MxcProcess p = spawn(request, options)) {
            return p.output();
        }
    }

    /** Run on a virtual thread. */
    public static CompletableFuture<Result> runAsync(Request request) {
        return CompletableFuture.supplyAsync(() -> run(request), r -> Thread.ofVirtual().start(r));
    }

    static Result consumeRunResult(MemorySegment out) {
        try {
            int status =
                    out.get(
                            ValueLayout.JAVA_INT,
                            Native.RUN_RESULT.byteOffset(PathElement.groupElement("status")));
            if (status != 0)
                throw errorFrom(
                        status,
                        out,
                        Native.RUN_RESULT.byteOffset(PathElement.groupElement("error")));
            int exit =
                    out.get(
                            ValueLayout.JAVA_INT,
                            Native.RUN_RESULT.byteOffset(PathElement.groupElement("exit_code")));
            boolean timedOut =
                    out.get(
                                    ValueLayout.JAVA_INT,
                                    Native.RUN_RESULT.byteOffset(
                                            PathElement.groupElement("timed_out")))
                            != 0;
            byte[] so =
                    Native.cBytes(
                            Native.ptrAt(
                                    out,
                                    Native.RUN_RESULT.byteOffset(
                                            PathElement.groupElement("stdout"))));
            byte[] se =
                    Native.cBytes(
                            Native.ptrAt(
                                    out,
                                    Native.RUN_RESULT.byteOffset(
                                            PathElement.groupElement("stderr"))));
            String meta =
                    Native.cString(
                            Native.ptrAt(
                                    out,
                                    Native.RUN_RESULT.byteOffset(
                                            PathElement.groupElement("metadata"))));
            String warn =
                    Native.cString(
                            Native.ptrAt(
                                    out,
                                    Native.RUN_RESULT.byteOffset(
                                            PathElement.groupElement("warnings"))));
            return new Result(
                    exit,
                    timedOut,
                    so == null ? new byte[0] : so,
                    se == null ? new byte[0] : se,
                    warn == null ? List.of() : stringList(warn),
                    meta);
        } finally {
            Native.call(Native.RUN_RESULT_FREE, out);
        }
    }

    // ---- live processes ----------------------------------------------------

    public static MxcProcess spawn(Request request) {
        return spawn(request, Options.DEFAULT);
    }

    public static MxcProcess spawn(Request request, Options options) {
        return spawnJson(request.toJson(), options);
    }

    public static MxcProcess spawnJson(String requestJson, Options options) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment handle = a.allocate(ValueLayout.ADDRESS);
            MemorySegment err = a.allocate(Native.ERROR);
            int st =
                    Native.callInt(
                            Native.SPAWN_JSON,
                            a.allocateFrom(requestJson),
                            options.experimental() ? 1 : 0,
                            handle,
                            err);
            return finishSpawn(st, handle, err);
        }
    }

    /** Spawn attached to an MXC-owned pseudo-terminal; stdout is the merged terminal output. */
    public static MxcProcess spawnPty(Request request, Options options) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment handle = a.allocate(ValueLayout.ADDRESS);
            MemorySegment err = a.allocate(Native.ERROR);
            int st =
                    Native.callInt(
                            Native.SPAWN_PTY_JSON,
                            a.allocateFrom(request.toJson()),
                            options.experimental() ? 1 : 0,
                            options.rows(),
                            options.cols(),
                            handle,
                            err);
            return finishSpawn(st, handle, err);
        }
    }

    static MxcProcess finishSpawn(int status, MemorySegment handleOut, MemorySegment err) {
        try {
            MemorySegment h = handleOut.get(ValueLayout.ADDRESS, 0);
            if (status != 0 || Native.isNull(h)) throw errorFrom(status, err, 0);
            return new MxcProcess(h);
        } finally {
            Native.call(Native.ERROR_DETAIL_FREE, err);
        }
    }

    // ---- errors / json helpers --------------------------------------------

    /** Build an exception from an MxcErrorDetail at {@code base + offset} of {@code seg}. */
    static MxcException errorFrom(int status, MemorySegment seg, long offset) {
        String msg = Native.cString(Native.ptrAt(seg, offset));
        String op = Native.cString(Native.ptrAt(seg, offset + 8));
        String nc = Native.cString(Native.ptrAt(seg, offset + 16));
        String rem = Native.cString(Native.ptrAt(seg, offset + 24));
        return new MxcException(status, msg == null ? "unknown error" : msg, op, nc, rem);
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid JSON from mxc_ffi: " + json, e);
        }
    }

    static List<String> stringList(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- persistent-container lifecycle (state-aware) ----------------------

    /**
     * Provision a persistent container (WSLC / IsolationSession, Windows hosts); returns its
     * sandbox id. On other hosts this throws {@link MxcException}.
     *
     * @param body fields of the provision request besides version/phase, e.g. {@code
     *     Map.of("containment","wslc")}
     */
    public static String provision(Map<String, Object> body, Options options) {
        JsonNode resp = lifecycle("provision", null, body, false, options);
        String id = resp.path("result").path("sandboxId").asText(null);
        if (id == null)
            throw new MxcException(
                    ErrorCode.BACKEND_ERROR.status(),
                    "provision response carried no sandboxId: " + resp);
        return id;
    }

    public static void start(String sandboxId, Options options) {
        lifecycle("start", sandboxId, null, false, options);
    }

    public static void stop(String sandboxId, Options options) {
        lifecycle("stop", sandboxId, null, false, options);
    }

    public static void deprovision(String sandboxId, Options options) {
        lifecycle("deprovision", sandboxId, null, false, options);
    }

    /** Native dry run of a phase; returns the response envelope. */
    public static JsonNode validate(
            String phase, String sandboxId, Map<String, Object> body, Options options) {
        return lifecycle(phase, sandboxId, body, true, options);
    }

    private static JsonNode lifecycle(
            String phase, String sandboxId, Map<String, Object> body, boolean dryRun, Options o) {
        ObjectNode env = JSON.createObjectNode();
        env.put("version", Request.SCHEMA_VERSION);
        env.put("phase", phase);
        if (sandboxId != null) env.put("sandboxId", sandboxId);
        if (body != null) env.setAll((ObjectNode) JSON.valueToTree(body));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(Native.STATE_RESULT);
            int st =
                    Native.callInt(
                            Native.STATE_AWARE_JSON,
                            a.allocateFrom(env.toString()),
                            dryRun ? 1 : 0,
                            o.experimental() ? 1 : 0,
                            out);
            try {
                if (st != 0)
                    throw errorFrom(
                            st,
                            out,
                            Native.STATE_RESULT.byteOffset(PathElement.groupElement("error")));
                String resp =
                        Native.cString(
                                Native.ptrAt(
                                        out,
                                        Native.STATE_RESULT.byteOffset(
                                                PathElement.groupElement("response"))));
                return parse(resp);
            } finally {
                Native.call(Native.STATE_AWARE_RESULT_FREE, out);
            }
        }
    }

    /** Run a process in an existing container to completion. */
    public static Result exec(String sandboxId, Request.Workload process, Options options) {
        ObjectNode env = JSON.createObjectNode();
        env.put("version", Request.SCHEMA_VERSION);
        env.put("phase", "exec");
        env.put("sandboxId", sandboxId);
        env.set("process", JSON.valueToTree(process));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(Native.RUN_RESULT);
            Native.callInt(
                    Native.RUN_STATE_AWARE_EXEC,
                    a.allocateFrom(env.toString()),
                    options.experimental() ? 1 : 0,
                    out);
            return consumeRunResult(out);
        }
    }

    /** Run a process in an existing container with live piped stdio. */
    public static MxcProcess spawnExec(
            String sandboxId, Request.Workload process, Options options) {
        ObjectNode env = JSON.createObjectNode();
        env.put("version", Request.SCHEMA_VERSION);
        env.put("phase", "exec");
        env.put("sandboxId", sandboxId);
        env.set("process", JSON.valueToTree(process));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment handle = a.allocate(ValueLayout.ADDRESS);
            MemorySegment err = a.allocate(Native.ERROR);
            int st =
                    Native.callInt(
                            Native.EXEC_STATE_AWARE,
                            a.allocateFrom(env.toString()),
                            options.experimental() ? 1 : 0,
                            handle,
                            err);
            return finishSpawn(st, handle, err);
        }
    }

    // ---- telemetry consent --------------------------------------------------

    public static JsonNode telemetryConsent() {
        return telemetry(Native.TELEMETRY_CONSENT);
    }

    public static JsonNode telemetryConsentStatus() {
        return telemetry(Native.TELEMETRY_CONSENT_STATUS);
    }

    public static JsonNode telemetryPolicy() {
        return telemetry(Native.TELEMETRY_POLICY);
    }

    public static JsonNode telemetryWithdrawConsent() {
        return telemetry(Native.TELEMETRY_WITHDRAW);
    }

    private static JsonNode telemetry(java.lang.invoke.MethodHandle h) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(ValueLayout.ADDRESS);
            int st = Native.callInt(h, out);
            String s = Native.takeString(out.get(ValueLayout.ADDRESS, 0));
            if (st != 0) throw new MxcException(st, "telemetry operation failed");
            return parse(s);
        }
    }

    /** Convenience: timeout helper for requests. */
    public static Request.Workload withTimeout(String cmd, Duration d) {
        return Request.Workload.of(cmd).withTimeout(d);
    }
}
