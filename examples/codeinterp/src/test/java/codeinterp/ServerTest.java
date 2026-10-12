package codeinterp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.github.albertocavalcante.mxc.Mxc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the real service against the real sandbox with hostile snippets. Mirrors the Go example's
 * test suite. Skips where no sandbox backend is available.
 */
@Timeout(120)
class ServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Config cfg;
    private HttpServer http;
    private String base;
    private Path secretDir;
    private String secretToken;

    record Reply(int status, JsonNode body) {
        String stdout() {
            return body.path("stdout").asText("");
        }

        String stderr() {
            return body.path("stderr").asText("");
        }

        int exitCode() {
            return body.path("exit_code").asInt();
        }

        boolean timedOut() {
            return body.path("timed_out").asBoolean();
        }

        boolean truncated() {
            return body.path("truncated").asBoolean();
        }
    }

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
                "POSIX shell workloads");
        Assumptions.assumeTrue(Mxc.platformSupport().isSupported(), "no sandbox backend");
        secretDir = Files.createTempDirectory("secrets-");
        secretToken = "TOKEN-" + new Random().nextLong();
        Files.writeString(secretDir.resolve("id_rsa"), secretToken);
        cfg = new Config(Files.createTempDirectory("work-"), List.of(secretDir.toString()));
        cfg.cpuSeconds = 1;
        http = Server.start(cfg, new InetSocketAddress("127.0.0.1", 0));
        base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private void needLang(String lang) {
        Assumptions.assumeTrue(cfg.interpreters.containsKey(lang), lang + " not installed");
    }

    private Reply run(String lang, String code) throws Exception {
        return run(lang, code, null, null);
    }

    private Reply run(String lang, String code, Integer timeoutMs, String stdin) throws Exception {
        var node = JSON.createObjectNode().put("language", lang).put("code", code);
        if (timeoutMs != null) {
            node.put("timeout_ms", timeoutMs);
        }
        if (stdin != null) {
            node.put("stdin", stdin);
        }
        return post("/v1/run", JSON.writeValueAsString(node));
    }

    private Reply post(String path, String body) throws Exception {
        HttpResponse<String> r =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), JSON.readTree(r.body()));
    }

    private static boolean procRunning(String marker) throws Exception {
        return new ProcessBuilder("pgrep", "-f", marker).start().waitFor() == 0;
    }

    private static boolean waitGone(String marker, Duration within) throws Exception {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (!procRunning(marker)) {
                return true;
            }
            Thread.sleep(100);
        }
        return !procRunning(marker);
    }

    private static String uniqueSleep() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "sleep 4%d.%d".formatted(100 + r.nextInt(800), r.nextInt(1_000_000));
    }

    private static void killAll(String marker) throws Exception {
        new ProcessBuilder("pkill", "-f", marker).start().waitFor();
    }

    // ---- normal programs ---------------------------------------------------------------

    @Test
    void pythonComputesAndReadsStdin() throws Exception {
        needLang("python");
        Reply r =
                run(
                        "python",
                        "import sys\nprint(sum(range(101)), sys.stdin.read().strip().upper())",
                        null,
                        "hi");
        assertEquals(200, r.status());
        assertEquals(0, r.exitCode());
        assertEquals("5050 HI\n", r.stdout());
    }

    @Test
    void workDirectoryIsWritableAndPrivate() throws Exception {
        needLang("python");
        Reply r =
                run(
                        "python",
                        "open('data.txt','w').write('persisted')\nprint(open('data.txt').read())");
        assertEquals("persisted\n", r.stdout());
    }

    @Test
    void exitCodeAndStderrPropagate() throws Exception {
        Reply r = run("sh", "echo oops >&2; exit 7");
        assertEquals(7, r.exitCode());
        assertEquals("oops\n", r.stderr());
    }

    @Test
    void nodeWorks() throws Exception {
        needLang("node");
        Reply r = run("node", "console.log(JSON.stringify([1,2,3].map(x => x * 2)))");
        assertEquals(0, r.exitCode());
        assertEquals("[2,4,6]", r.stdout().strip());
    }

    @Test
    void validation() throws Exception {
        assertEquals(400, run("cobol", "x").status());
        assertEquals(400, run("sh", "").status());
        assertEquals(400, run("sh", "true", 10 * 60 * 1000, null).status());
        assertEquals(
                400,
                post("/v1/run", "{\"language\":\"sh\",\"code\":\"true\",\"sandbox\":\"off\"}")
                        .status());
    }

    // ---- hostile snippets --------------------------------------------------------------

    @Test
    void cannotReadADeniedSecret() throws Exception {
        needLang("python");
        Reply r = run("python", "print(open('%s').read())".formatted(secretDir.resolve("id_rsa")));
        assertFalse(
                (r.stdout() + r.stderr()).contains(secretToken), "secret reachable: " + r.body());
        assertTrue(r.exitCode() != 0);
    }

    @Test
    void cannotEscapeThroughASymlink() throws Exception {
        needLang("python");
        Reply r =
                run(
                        "python",
                        "import os\nos.symlink('%s', 'link')\nprint(open('link/id_rsa').read())"
                                .formatted(secretDir));
        assertFalse((r.stdout() + r.stderr()).contains(secretToken), "symlink escape: " + r.body());
    }

    @Test
    void cannotWriteOutsideTheWorkDirectory() throws Exception {
        needLang("python");
        Path target = Files.createTempDirectory("outside-").resolve("pwned.txt");
        run("python", "open('%s','w').write('x')".formatted(target));
        assertFalse(Files.exists(target), "wrote outside the sandbox");
    }

    /** Accepts connections on all interfaces and counts them. */
    private static final class Listener implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger hits = new AtomicInteger();

        Listener() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
            Thread.ofPlatform()
                    .daemon()
                    .start(
                            () -> {
                                try {
                                    while (true) {
                                        socket.accept().close();
                                        hits.incrementAndGet();
                                    }
                                } catch (IOException ignored) {
                                    // listener closed
                                }
                            });
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String lanAddress() throws Exception {
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address
                        && !a.isLoopbackAddress()
                        && !a.isLinkLocalAddress()) {
                    return a.getHostAddress();
                }
            }
        }
        return null;
    }

    @Test
    void cannotReachTheNetwork() throws Exception {
        needLang("python");
        // Loopback is covered by the separate host-loopback rule; the egress rule is proven
        // by the external-host test below.
        String lan = lanAddress();
        for (String host : lan == null ? List.of("127.0.0.1") : List.of("127.0.0.1", lan)) {
            try (Listener l = new Listener()) {
                Reply r =
                        run(
                                "python",
                                "import socket\nsocket.create_connection(('%s',%d),2)\nprint('CONNECTED')"
                                        .formatted(host, l.socket.getLocalPort()));
                Thread.sleep(200);
                assertEquals(0, l.hits.get(), host + ": network egress possible: " + r.body());
                assertFalse(r.stdout().contains("CONNECTED"), host + ": " + r.body());
            }
        }
    }

    @Test
    void cannotReachAnExternalHost() throws Exception {
        needLang("python");
        // Meaningful only if this machine can reach the target unsandboxed.
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("1.1.1.1", 443), 3000);
        } catch (IOException e) {
            Assumptions.abort("1.1.1.1:443 unreachable from the test host: " + e.getMessage());
        }
        Reply r =
                run(
                        "python",
                        "import socket\n"
                                + "socket.create_connection(('1.1.1.1',443),3)\n"
                                + "print('CONNECTED')");
        assertFalse(r.stdout().contains("CONNECTED"), "sandbox reached the internet: " + r.body());
    }

    @Test
    void doesNotInheritTheServiceEnvironment() throws Exception {
        needLang("python");
        // The service runs in this JVM; PATH/HOME are present in its environment, and the
        // sandbox must see only the explicit minimal one.
        Reply r = run("python", "import os\nprint(os.environ)");
        assertFalse(r.stdout().contains(System.getProperty("user.name") + "/"), r.stdout());
        assertFalse(r.stdout().contains("JAVA_HOME"), r.stdout());
        assertFalse(r.stdout().contains("MXC_FFI_DIR"), r.stdout());
    }

    @Test
    void infiniteLoopIsStoppedByTheTimeout() throws Exception {
        needLang("python");
        long t0 = System.nanoTime();
        Reply r = run("python", "while True: pass", 700, null);
        assertTrue(r.timedOut(), r.body().toString());
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 8);
    }

    @Test
    void outputFloodIsCappedAndTheProducerKilled() throws Exception {
        needLang("python");
        cfg.cpuSeconds = 120; // only the output cap, not ulimit, may stop it
        long t0 = System.nanoTime();
        Reply r = run("python", "while True: print('x' * 4096)");
        assertTrue(r.truncated());
        assertTrue(r.stdout().length() <= cfg.outputLimit);
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 8);
    }

    @Test
    void descendantsDoNotSurviveATimeout() throws Exception {
        String m = uniqueSleep();
        try {
            Reply r =
                    run(
                            "sh",
                            "for i in 1 2 3 4 5 6 7 8; do %s & done; wait".formatted(m),
                            700,
                            null);
            assertTrue(r.timedOut(), r.body().toString());
            assertTrue(waitGone(m, Duration.ofSeconds(5)), "descendants survived: " + m);
        } finally {
            killAll(m);
        }
    }

    @Test
    void aBackgroundChildCannotHoldTheResponseHostage() throws Exception {
        String m = uniqueSleep();
        try {
            long t0 = System.nanoTime();
            Reply r = run("sh", m + " & echo parent-done");
            assertEquals("parent-done\n", r.stdout());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 8, "response delayed");
            assertTrue(waitGone(m, Duration.ofSeconds(5)), "orphaned child survived: " + m);
        } finally {
            killAll(m);
        }
    }

    @Test
    void cpuBurnIsLimitedByUlimit() throws Exception {
        needLang("python");
        long t0 = System.nanoTime();
        Reply r = run("python", "while True: pass", 20_000, null);
        long secs = Duration.ofNanos(System.nanoTime() - t0).toSeconds();
        assertFalse(r.timedOut() || secs > 10, "CPU limit not enforced after " + secs + "s");
    }

    @Test
    void workDirectoriesAreCleanedUp() throws Exception {
        needLang("python");
        run("python", "open('junk','w').write('x'*1000)");
        try (var entries = Files.list(cfg.workRoot)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    // ---- service behavior --------------------------------------------------------------

    @Test
    void backpressureReturns429WhileTheOnlySlotIsBusy() throws Exception {
        cfg.slots = new java.util.concurrent.Semaphore(1);
        Thread slow =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        run("sh", "sleep 1.5");
                                    } catch (Exception e) {
                                        fail(e);
                                    }
                                });
        Thread.sleep(400);
        assertEquals(429, run("sh", "true").status());
        slow.join();
        assertEquals(200, run("sh", "true").status());
    }

    @Test
    void streamsOutputAsServerSentEvents() throws Exception {
        HttpResponse<String> r =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create(base + "/v1/run/stream"))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"language\":\"sh\",\"code\":\"echo one; sleep"
                                                        + " 0.3; echo two\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        String body = r.body();
        for (String want :
                List.of(
                        "event: stdout",
                        "\"data\":\"one\\n\"",
                        "\"data\":\"two\\n\"",
                        "event: result",
                        "\"exit_code\":0")) {
            assertTrue(body.contains(want), "missing " + want + " in:\n" + body);
        }
    }
}
