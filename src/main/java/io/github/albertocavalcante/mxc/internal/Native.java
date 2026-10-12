package io.github.albertocavalcante.mxc.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Panama downcalls to {@code mxc_ffi}. Hand-maintained against {@code
 * microsoft/mxc/src/ffi/mxc_ffi/src/*.rs}; that C ABI is explicitly unstable, so re-diff on every
 * MXC upgrade. Not public API.
 *
 * <p>Library resolution order: system property {@code mxc.ffi.path} (file or directory), env {@code
 * MXC_FFI_DIR}, jar resource {@code /META-INF/native/<os>-<arch>/<lib>}, then the platform loader
 * search path.
 */
public final class Native {
    private Native() {}

    // MxcErrorDetail { char *message, *operation, *native_code, *remediation }
    public static final StructLayout ERROR =
            MemoryLayout.structLayout(
                    ADDRESS.withName("message"), ADDRESS.withName("operation"),
                    ADDRESS.withName("native_code"), ADDRESS.withName("remediation"));
    // MxcRunResult
    public static final StructLayout RUN_RESULT =
            MemoryLayout.structLayout(
                    JAVA_INT.withName("status"),
                    JAVA_INT.withName("exit_code"),
                    JAVA_INT.withName("timed_out"),
                    MemoryLayout.paddingLayout(4),
                    ADDRESS.withName("stdout"),
                    ADDRESS.withName("stderr"),
                    ERROR.withName("error"),
                    ADDRESS.withName("metadata"),
                    ADDRESS.withName("warnings"));
    // MxcStateAwareResult
    public static final StructLayout STATE_RESULT =
            MemoryLayout.structLayout(
                    JAVA_INT.withName("status"), MemoryLayout.paddingLayout(4),
                    ADDRESS.withName("response"), ERROR.withName("error"));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = load();

    private static String libFile() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "mxc_ffi.dll";
        if (os.contains("mac")) return "libmxc_ffi.dylib";
        return "libmxc_ffi.so";
    }

    private static String platformDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String a = (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "x64";
        return (os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux") + "-" + a;
    }

    /** True for null, blank, or an unresolved Maven placeholder such as {@code ${env.X}}. */
    private static boolean unset(String v) {
        return v == null || v.isBlank() || v.contains("${");
    }

    private static SymbolLookup load() {
        String file = libFile();
        Arena global = Arena.global();
        String prop = System.getProperty("mxc.ffi.path");
        if (unset(prop)) prop = System.getenv("MXC_FFI_DIR");
        if (!unset(prop)) {
            Path p = Path.of(prop);
            if (Files.isDirectory(p)) p = p.resolve(file);
            if (!Files.isRegularFile(p)) {
                throw new UnsatisfiedLinkError(
                        "mxc_ffi not found at "
                                + p
                                + " (from -Dmxc.ffi.path / MXC_FFI_DIR). Build it from"
                                + " microsoft/mxc with `cargo build --release -p mxc_ffi`.");
            }
            return SymbolLookup.libraryLookup(p, global);
        }
        String res = "/META-INF/native/" + platformDir() + "/" + file;
        try (InputStream in = Native.class.getResourceAsStream(res)) {
            if (in != null) {
                Path tmp = Files.createTempDirectory("mxc-ffi").resolve(file);
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                tmp.getParent().toFile().deleteOnExit();
                return SymbolLookup.libraryLookup(tmp, global);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to extract bundled mxc_ffi", e);
        }
        try {
            return SymbolLookup.libraryLookup(file, global);
        } catch (IllegalArgumentException e) {
            throw new UnsatisfiedLinkError(
                    "mxc_ffi ("
                            + file
                            + ") not found. Set MXC_FFI_DIR or -Dmxc.ffi.path to the directory"
                            + " holding it, bundle it as META-INF/native/"
                            + platformDir()
                            + "/"
                            + file
                            + ", or put it on the loader path. Build it from microsoft/mxc with"
                            + " `cargo build --release -p mxc_ffi`.");
        }
    }

    private static MethodHandle fn(String name, FunctionDescriptor fd) {
        MemorySegment sym =
                LOOKUP.find(name)
                        .orElseThrow(
                                () ->
                                        new UnsatisfiedLinkError(
                                                "mxc_ffi symbol not found: " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    private static FunctionDescriptor of(MemoryLayout ret, MemoryLayout... args) {
        return FunctionDescriptor.of(ret, args);
    }

    private static FunctionDescriptor ofVoid(MemoryLayout... args) {
        return FunctionDescriptor.ofVoid(args);
    }

    public static final MethodHandle VERSION = fn("mxc_version", of(ADDRESS));
    public static final MethodHandle AVAILABLE_BACKENDS =
            fn("mxc_available_backends_json", of(ADDRESS));
    public static final MethodHandle PLATFORM_SUPPORT =
            fn("mxc_platform_support_json", of(ADDRESS));
    public static final MethodHandle STRING_FREE = fn("mxc_string_free", ofVoid(ADDRESS));

    public static final MethodHandle RUN_JSON =
            fn("mxc_run_json", of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    public static final MethodHandle RUN_RESULT_FREE = fn("mxc_run_result_free", ofVoid(ADDRESS));
    public static final MethodHandle ERROR_DETAIL_FREE =
            fn("mxc_error_detail_free", ofVoid(ADDRESS));

    public static final MethodHandle SPAWN_JSON =
            fn("mxc_spawn_json", of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle SPAWN_PTY_JSON =
            fn(
                    "mxc_spawn_pty_json",
                    of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_SHORT, JAVA_SHORT, ADDRESS, ADDRESS));
    public static final MethodHandle PTY_RESIZE =
            fn("mxc_sandbox_pty_resize", of(JAVA_INT, ADDRESS, JAVA_SHORT, JAVA_SHORT));

    public static final MethodHandle TAKE_STDIN =
            fn("mxc_sandbox_take_stdin", of(ADDRESS, ADDRESS));
    public static final MethodHandle TAKE_STDOUT =
            fn("mxc_sandbox_take_stdout", of(ADDRESS, ADDRESS));
    public static final MethodHandle TAKE_STDERR =
            fn("mxc_sandbox_take_stderr", of(ADDRESS, ADDRESS));
    public static final MethodHandle STDOUT_CLOSER =
            fn("mxc_sandbox_stdout_closer", of(ADDRESS, ADDRESS));
    public static final MethodHandle STDERR_CLOSER =
            fn("mxc_sandbox_stderr_closer", of(ADDRESS, ADDRESS));
    public static final MethodHandle STREAM_READ =
            fn("mxc_stream_read", of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    public static final MethodHandle STREAM_WRITE =
            fn("mxc_stream_write", of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    public static final MethodHandle STREAM_FLUSH = fn("mxc_stream_flush", of(JAVA_INT, ADDRESS));
    public static final MethodHandle CLOSER_CLOSE =
            fn("mxc_stream_closer_close", of(JAVA_INT, ADDRESS));
    public static final MethodHandle READ_STREAM_FREE = fn("mxc_read_stream_free", ofVoid(ADDRESS));
    public static final MethodHandle WRITE_STREAM_FREE =
            fn("mxc_write_stream_free", ofVoid(ADDRESS));
    public static final MethodHandle CLOSER_FREE = fn("mxc_stream_closer_free", ofVoid(ADDRESS));

    public static final MethodHandle SANDBOX_ID = fn("mxc_sandbox_id", of(JAVA_INT, ADDRESS));
    public static final MethodHandle SANDBOX_WARNINGS =
            fn("mxc_sandbox_warnings_json", of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle SANDBOX_METADATA =
            fn("mxc_sandbox_output_metadata_json", of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle TRY_WAIT =
            fn("mxc_sandbox_try_wait", of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle KILL = fn("mxc_sandbox_kill", of(JAVA_INT, ADDRESS));
    public static final MethodHandle SANDBOX_FREE = fn("mxc_sandbox_free", ofVoid(ADDRESS));

    public static final MethodHandle STATE_AWARE_JSON =
            fn("mxc_run_state_aware_json", of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    public static final MethodHandle STATE_AWARE_RESULT_FREE =
            fn("mxc_state_aware_result_free", ofVoid(ADDRESS));
    public static final MethodHandle EXEC_STATE_AWARE =
            fn("mxc_exec_state_aware_json", of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle RUN_STATE_AWARE_EXEC =
            fn("mxc_run_state_aware_exec_json", of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    public static final MethodHandle TELEMETRY_CONSENT =
            fn("mxc_telemetry_get_consent", of(JAVA_INT, ADDRESS));
    public static final MethodHandle TELEMETRY_CONSENT_STATUS =
            fn("mxc_telemetry_get_consent_status", of(JAVA_INT, ADDRESS));
    public static final MethodHandle TELEMETRY_POLICY =
            fn("mxc_telemetry_get_policy", of(JAVA_INT, ADDRESS));
    public static final MethodHandle TELEMETRY_WITHDRAW =
            fn("mxc_telemetry_withdraw_consent", of(JAVA_INT, ADDRESS));

    // ---- helpers -----------------------------------------------------------

    /** Invoke a handle, converting checked Throwables into unchecked ones. */
    public static Object call(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static int callInt(MethodHandle h, Object... args) {
        return (Integer) call(h, args);
    }

    public static MemorySegment callPtr(MethodHandle h, Object... args) {
        return (MemorySegment) call(h, args);
    }

    public static boolean isNull(MemorySegment s) {
        return s == null || s.equals(MemorySegment.NULL) || s.address() == 0;
    }

    /**
     * Copy a NUL-terminated C string out of native memory; null for a null pointer. The mxc_ffi
     * run-result API already decoded the output lossily (invalid UTF-8 and NUL bytes become
     * U+FFFD), so one-shot capture is not binary-safe; the streaming API carries raw bytes.
     */
    public static byte[] cBytes(MemorySegment p) {
        if (isNull(p)) return null;
        MemorySegment all = p.reinterpret(Long.MAX_VALUE);
        long n = 0;
        while (all.get(ValueLayout.JAVA_BYTE, n) != 0) n++;
        return all.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
    }

    public static String cString(MemorySegment p) {
        byte[] b = cBytes(p);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Free a library-owned heap string and return its contents. */
    public static String takeString(MemorySegment p) {
        if (isNull(p)) return null;
        String s = cString(p);
        call(STRING_FREE, p);
        return s;
    }

    public static MemorySegment ptrAt(MemorySegment struct, long offset) {
        return struct.get(ADDRESS, offset);
    }
}
