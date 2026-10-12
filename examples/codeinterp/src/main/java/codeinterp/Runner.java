package codeinterp;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.albertocavalcante.mxc.Mxc;
import io.github.albertocavalcante.mxc.MxcProcess;
import io.github.albertocavalcante.mxc.Request;
import io.github.albertocavalcante.mxc.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/** Runs one untrusted snippet inside an MXC sandbox and collects the outcome. */
final class Runner {
    private Runner() {}

    /** API input. */
    record RunRequest(
            String language,
            String code,
            String stdin,
            @JsonProperty("timeout_ms") Integer timeoutMs) {}

    /** API output. */
    record RunResult(
            @JsonProperty("exit_code") int exitCode,
            String stdout,
            String stderr,
            @JsonProperty("timed_out") boolean timedOut,
            boolean truncated,
            @JsonProperty("duration_ms") long durationMs) {}

    /** Receives output as it arrives; return false to stop the run (for example, client gone). */
    interface Chunks {
        boolean accept(String stream, String data);
    }

    /** The request is malformed or exceeds a limit (HTTP 400). */
    static final class InvalidRequest extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidRequest(String message) {
            super(message);
        }
    }

    /** All sandbox slots are in use (HTTP 429). */
    static final class Busy extends Exception {
        private static final long serialVersionUID = 1L;

        Busy() {
            super("all sandbox slots are busy");
        }
    }

    /** Accumulates at most a limit of bytes and remembers if it dropped any. */
    private static final class Capped {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile boolean truncated;

        /** Returns how many bytes of the chunk were kept. */
        synchronized int add(byte[] data, int len, int limit) {
            int room = limit - buf.size();
            if (room <= 0) {
                truncated = true;
                return 0;
            }
            int keep = Math.min(room, len);
            if (keep < len) {
                truncated = true;
            }
            buf.write(data, 0, keep);
            return keep;
        }

        synchronized String text() {
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    static RunResult execute(Config cfg, RunRequest rq, Chunks sink)
            throws InvalidRequest, Busy, IOException, InterruptedException {
        Interpreter interp = rq.language() == null ? null : cfg.interpreters.get(rq.language());
        if (interp == null) {
            throw new InvalidRequest("unsupported language \"" + rq.language() + "\"");
        }
        if (rq.code() == null
                || rq.code().isEmpty()
                || rq.code().getBytes(StandardCharsets.UTF_8).length > cfg.maxCodeBytes) {
            throw new InvalidRequest("code must be 1.." + cfg.maxCodeBytes + " bytes");
        }
        Duration timeout =
                rq.timeoutMs() == null || rq.timeoutMs() <= 0
                        ? Duration.ofSeconds(10)
                        : Duration.ofMillis(rq.timeoutMs());
        if (timeout.compareTo(cfg.maxTimeout) > 0) {
            throw new InvalidRequest("timeout_ms exceeds " + cfg.maxTimeout.toMillis());
        }

        Semaphore slots = cfg.slots;
        if (!slots.tryAcquire()) {
            throw new Busy();
        }
        Path work = null;
        try {
            // A fresh, private work directory per request: the only writable path.
            work = Files.createTempDirectory(cfg.workRoot, "run-");
            Files.writeString(work.resolve(interp.file()), rq.code());
            return runIn(cfg, interp, rq, work, timeout, sink);
        } finally {
            slots.release();
            if (work != null) {
                deleteTree(work);
            }
        }
    }

    private static RunResult runIn(
            Config cfg, Interpreter interp, RunRequest rq, Path work, Duration timeout, Chunks sink)
            throws IOException, InterruptedException {
        // MXC enforces filesystem and network policy and a wall-clock timeout. It does not cap
        // CPU, memory, file size or open files, so the service adds what it can with ulimit.
        // One ulimit per option: POSIX shells such as dash accept only one, and a combined
        // call would fail silently and leave the process unlimited.
        String cmd =
                "ulimit -t %d 2>/dev/null; ulimit -f %d 2>/dev/null; ulimit -n %d 2>/dev/null; exec %s %s%s"
                        .formatted(
                                cfg.cpuSeconds,
                                cfg.fileSizeKb * 2,
                                cfg.maxOpenFiles,
                                interp.command(),
                                interp.args(),
                                interp.file());

        Request.Workload workload =
                Request.Workload.of(cmd)
                        .withCwd(work.toString())
                        // An explicit environment: nothing from the service's own environment
                        // (tokens, credentials) is inherited.
                        .withEnv(
                                List.of(
                                        "PATH=/usr/bin:/bin",
                                        "HOME=" + work,
                                        "TMPDIR=" + work,
                                        "LANG=C.UTF-8"))
                        .withTimeout(timeout);
        Request req =
                Request.of(cmd)
                        .withProcess(workload)
                        .withFilesystem(
                                new Request.Filesystem(
                                        interp.readOnly().isEmpty() ? null : interp.readOnly(),
                                        List.of(work.toString()),
                                        cfg.denyPaths.isEmpty() ? null : cfg.denyPaths))
                        .withNetwork(Request.Network.denyAll());

        long start = System.nanoTime();
        Capped out = new Capped();
        Capped err = new Capped();
        MxcProcess p = Mxc.spawn(req);
        try {
            // Hard deadline beyond MXC's own timeout, in case the native wait never returns.
            Thread watchdog =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try {
                                            Thread.sleep(timeout.plusSeconds(5));
                                            p.kill();
                                        } catch (InterruptedException | RuntimeException ignored) {
                                            // finished in time, or already closed
                                        }
                                    });
            Thread stdinWriter =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try (var in = p.stdin()) {
                                            if (rq.stdin() != null) {
                                                in.write(
                                                        rq.stdin()
                                                                .getBytes(StandardCharsets.UTF_8));
                                            }
                                        } catch (IOException ignored) {
                                            // the process exited or was killed
                                        }
                                    });
            Thread outPump = pump("stdout", p.stdout(), out, cfg.outputLimit, sink, p);
            Thread errPump = pump("stderr", p.stderr(), err, cfg.outputLimit, sink, p);

            Result res;
            try {
                res = p.waitFor();
            } finally {
                watchdog.interrupt();
            }
            // The child has exited, but a surviving descendant could hold the pipes open;
            // close() kills the tree and unblocks the readers.
            p.close();
            outPump.join();
            errPump.join();
            stdinWriter.join();
            return new RunResult(
                    res.exitCode(),
                    out.text(),
                    err.text(),
                    res.timedOut(),
                    out.truncated || err.truncated,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } finally {
            p.close(); // idempotent; also covers an exception before the explicit close
        }
    }

    private static Thread pump(
            String stream, InputStream in, Capped dst, int limit, Chunks sink, MxcProcess p) {
        return Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            byte[] buf = new byte[8192];
                            try {
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    int kept = dst.add(buf, n, limit);
                                    boolean goOn = true;
                                    if (kept > 0 && sink != null) {
                                        goOn =
                                                sink.accept(
                                                        stream,
                                                        new String(
                                                                buf,
                                                                0,
                                                                kept,
                                                                StandardCharsets.UTF_8));
                                    }
                                    if (dst.truncated || !goOn) {
                                        // A flood (or a gone client) ends the run: stop the
                                        // producer.
                                        p.kill();
                                    }
                                }
                            } catch (IOException | RuntimeException ignored) {
                                // stream closed because the process ended
                            }
                        });
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
