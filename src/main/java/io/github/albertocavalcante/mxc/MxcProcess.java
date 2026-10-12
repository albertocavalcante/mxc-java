package io.github.albertocavalcante.mxc;

import io.github.albertocavalcante.mxc.internal.Native;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A live sandboxed process. All native calls on the process handle are serialized internally.
 * stdout, stderr and stdin may each be used from a different thread; a single stream must be used
 * by one thread at a time.
 */
public final class MxcProcess implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();
    private MemorySegment handle;
    private boolean closed;
    private Result exited;

    private final StdinStream stdin;
    private final OutStream stdout;
    private final OutStream stderr;

    MxcProcess(MemorySegment handle) {
        this.handle = handle;
        MemorySegment in;
        MemorySegment out;
        MemorySegment err;
        MemorySegment outCloser;
        MemorySegment errCloser;
        try {
            in = Native.callPtr(Native.TAKE_STDIN, handle);
            out = Native.callPtr(Native.TAKE_STDOUT, handle);
            err = Native.callPtr(Native.TAKE_STDERR, handle);
            outCloser =
                    Native.isNull(out)
                            ? MemorySegment.NULL
                            : Native.callPtr(Native.STDOUT_CLOSER, handle);
            errCloser =
                    Native.isNull(err)
                            ? MemorySegment.NULL
                            : Native.callPtr(Native.STDERR_CLOSER, handle);
        } catch (RuntimeException e) {
            // The caller never sees this object, so it cannot free the handle.
            try {
                Native.call(Native.SANDBOX_FREE, handle);
            } catch (RuntimeException ignored) {
            }
            throw e;
        }
        this.stdin = Native.isNull(in) ? null : new StdinStream(in);
        this.stdout = Native.isNull(out) ? null : new OutStream(out, outCloser);
        this.stderr = Native.isNull(err) ? null : new OutStream(err, errCloser);
    }

    /** Child stdin; closing it delivers EOF. Null if not piped. */
    public OutputStream stdin() {
        return stdin;
    }

    /** Child stdout (merged terminal output for a PTY). Null if not piped. */
    public InputStream stdout() {
        return stdout;
    }

    /** Child stderr. Null if not piped (always null for a PTY). */
    public InputStream stderr() {
        return stderr;
    }

    /** The native process id, or 0 once the process has been closed. */
    public long pid() {
        lock.lock();
        try {
            return closed ? 0 : Integer.toUnsignedLong(Native.callInt(Native.SANDBOX_ID, handle));
        } finally {
            lock.unlock();
        }
    }

    /** Security warnings raised at launch (for example a relaxed policy); empty if none. */
    public List<String> warnings() {
        lock.lock();
        try {
            if (closed) {
                return List.of();
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment o = a.allocate(ValueLayout.ADDRESS);
                Native.callInt(Native.SANDBOX_WARNINGS, handle, o);
                String s = Native.takeString(o.get(ValueLayout.ADDRESS, 0));
                return s == null ? List.of() : Mxc.stringList(s);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Resize a PTY process. */
    public void resize(int rows, int cols) {
        lock.lock();
        try {
            ensureOpen();
            int st = Native.callInt(Native.PTY_RESIZE, handle, (short) rows, (short) cols);
            if (st != 0) {
                throw new MxcException(st, "pty resize failed");
            }
        } finally {
            lock.unlock();
        }
    }

    /** Kill the process tree. */
    public void kill() {
        lock.lock();
        try {
            ensureOpen();
            int st = Native.callInt(Native.KILL, handle);
            if (st != 0) {
                throw new MxcException(st, "kill failed");
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("mxc process is closed");
        }
    }

    /** Poll once. Returns the exit status if the process has exited, else null. */
    public Result poll() {
        lock.lock();
        try {
            if (exited != null) {
                return exited;
            }
            ensureOpen();
            try (Arena a = Arena.ofConfined()) {
                MemorySegment e = a.allocate(ValueLayout.JAVA_INT);
                MemorySegment r = a.allocate(ValueLayout.JAVA_INT);
                MemorySegment t = a.allocate(ValueLayout.JAVA_INT);
                int st = Native.callInt(Native.TRY_WAIT, handle, e, r, t);
                if (st != 0) {
                    throw new MxcException(st, "wait failed");
                }
                if (r.get(ValueLayout.JAVA_INT, 0) != 0) {
                    return null;
                }
                exited =
                        new Result(
                                e.get(ValueLayout.JAVA_INT, 0),
                                t.get(ValueLayout.JAVA_INT, 0) != 0,
                                new byte[0],
                                new byte[0],
                                List.of(),
                                null);
                return exited;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Block until the process exits. Per the native concurrency contract this polls and kills from
     * the calling thread; if the thread is interrupted the process is killed and {@link
     * InterruptedException} is thrown. Stdout/stderr are not captured; see {@link #output()}.
     */
    public Result waitFor() throws InterruptedException {
        long delayMs = 1;
        while (true) {
            Result r = poll();
            if (r != null) {
                return r;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                try {
                    kill();
                } catch (RuntimeException ignored) {
                }
                for (int i = 0; i < 500 && poll() == null; i++) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        /* keep reaping */
                    }
                }
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (delayMs < 25) {
                delayMs *= 2;
            }
        }
    }

    /** Drain stdout/stderr concurrently, wait for exit, and return everything. */
    public Result output() throws InterruptedException {
        // Nothing is supplied on stdin here; close it so children such as `cat` see EOF.
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
        }
        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        // Platform threads: the drains block inside native downcalls, which would pin virtual
        // threads.
        Thread t1 = Thread.ofPlatform().daemon().start(() -> drain(stdout, so));
        Thread t2 = Thread.ofPlatform().daemon().start(() -> drain(stderr, se));
        Result r;
        try {
            r = waitFor();
        } catch (InterruptedException ie) {
            closeStreams();
            throw ie;
        }
        // The child has exited, but a surviving grandchild may keep the pipes open: give the
        // drains a short grace, then close the streams (off-thread; close may block behind a
        // read) and return what was captured. ByteArrayOutputStream is synchronized.
        long deadline = System.nanoTime() + DRAIN_GRACE_NANOS;
        t1.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
        t2.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
        if (t1.isAlive() || t2.isAlive()) {
            Thread.ofPlatform().daemon().start(this::closeStreams);
        }
        return new Result(
                r.exitCode(),
                r.timedOut(),
                so.toByteArray(),
                se.toByteArray(),
                warnings(),
                metadata());
    }

    private static final long DRAIN_GRACE_NANOS = 500_000_000L;

    private static void drain(InputStream in, ByteArrayOutputStream sink) {
        if (in == null) {
            return;
        }
        try {
            in.transferTo(sink);
        } catch (IOException ignored) {
            /* closed or EOF */
        }
    }

    private String metadata() {
        lock.lock();
        try {
            if (closed) {
                return null;
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment o = a.allocate(ValueLayout.ADDRESS);
                Native.callInt(Native.SANDBOX_METADATA, handle, o);
                return Native.takeString(o.get(ValueLayout.ADDRESS, 0));
            }
        } finally {
            lock.unlock();
        }
    }

    private void closeStreams() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
        }
        if (stdout != null) {
            stdout.close();
        }
        if (stderr != null) {
            stderr.close();
        }
    }

    /** Release the process; kills the tree if still running. Idempotent. */
    @Override
    public void close() {
        // Kill first: this unblocks in-flight stream reads/writes so closeStreams() cannot hang
        // behind them (notably when no native stdout/stderr closer exists).
        lock.lock();
        try {
            if (!closed) {
                try {
                    Native.callInt(Native.KILL, handle);
                } catch (RuntimeException ignored) {
                }
            }
        } finally {
            lock.unlock();
        }
        closeStreams();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Native.call(Native.SANDBOX_FREE, handle);
            handle = MemorySegment.NULL;
        } finally {
            lock.unlock();
        }
    }

    // ---- streams -----------------------------------------------------------

    private static final class OutStream extends InputStream {
        private final ReentrantLock rl = new ReentrantLock(); // held across each native read
        private MemorySegment stream;
        private MemorySegment closer;
        private boolean closeStarted;

        OutStream(MemorySegment stream, MemorySegment closer) {
            this.stream = stream;
            this.closer = closer;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            rl.lock();
            try {
                if (Native.isNull(stream)) {
                    return -1;
                }
                int cap = Math.min(len, 64 * 1024);
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment buf = a.allocate(cap);
                    MemorySegment n = a.allocate(ValueLayout.JAVA_LONG);
                    int st = Native.callInt(Native.STREAM_READ, stream, buf, (long) cap, n);
                    if (st != 0) {
                        throw new IOException(new MxcException(st, "stream read failed"));
                    }
                    long got = n.get(ValueLayout.JAVA_LONG, 0);
                    if (got == 0) {
                        return -1;
                    }
                    MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, b, off, (int) got);
                    return (int) got;
                }
            } finally {
                rl.unlock();
            }
        }

        /** Unblocks an in-flight read (without killing the child), then frees. */
        @Override
        public void close() {
            synchronized (this) {
                if (closeStarted) {
                    return;
                }
                closeStarted = true;
            }
            if (!Native.isNull(closer)) {
                Native.call(Native.CLOSER_CLOSE, closer); // not guarded by rl
            }
            rl.lock();
            try {
                if (!Native.isNull(stream)) {
                    Native.call(Native.READ_STREAM_FREE, stream);
                    stream = MemorySegment.NULL;
                }
                if (!Native.isNull(closer)) {
                    Native.call(Native.CLOSER_FREE, closer);
                    closer = MemorySegment.NULL;
                }
            } finally {
                rl.unlock();
            }
        }
    }

    private static final class StdinStream extends OutputStream {
        private final ReentrantLock wl = new ReentrantLock();
        private MemorySegment stream;

        StdinStream(MemorySegment stream) {
            this.stream = stream;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            wl.lock();
            try {
                if (Native.isNull(stream)) {
                    throw new IOException("stdin closed");
                }
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment buf = a.allocate(Math.max(len, 1));
                    MemorySegment n = a.allocate(ValueLayout.JAVA_LONG);
                    MemorySegment.copy(b, off, buf, ValueLayout.JAVA_BYTE, 0, len);
                    int done = 0;
                    while (done < len) {
                        int st =
                                Native.callInt(
                                        Native.STREAM_WRITE,
                                        stream,
                                        buf.asSlice(done),
                                        (long) (len - done),
                                        n);
                        if (st != 0) {
                            throw new IOException(new MxcException(st, "stream write failed"));
                        }
                        long w = n.get(ValueLayout.JAVA_LONG, 0);
                        if (w == 0) {
                            throw new IOException("short write");
                        }
                        done += (int) w;
                    }
                }
            } finally {
                wl.unlock();
            }
        }

        @Override
        public void flush() throws IOException {
            wl.lock();
            try {
                if (Native.isNull(stream)) {
                    return;
                }
                int st = Native.callInt(Native.STREAM_FLUSH, stream);
                if (st != 0) {
                    throw new IOException(new MxcException(st, "stream flush failed"));
                }
            } finally {
                wl.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            wl.lock();
            try {
                if (Native.isNull(stream)) {
                    return;
                }
                Native.call(Native.WRITE_STREAM_FREE, stream);
                stream = MemorySegment.NULL;
            } finally {
                wl.unlock();
            }
        }
    }
}
