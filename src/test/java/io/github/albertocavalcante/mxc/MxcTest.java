package io.github.albertocavalcante.mxc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class MxcTest {
    static boolean sandbox;

    @BeforeAll
    static void probe() {
        sandbox = Mxc.platformSupport().isSupported();
    }

    static void need() {
        Assumptions.assumeTrue(sandbox, "no supported sandbox backend");
    }

    static Request sh(String cmd) {
        return Request.of(cmd)
                .withProcess(Request.Workload.of(cmd).withTimeout(Duration.ofSeconds(20)))
                .withFilesystem(Request.Filesystem.readwrite(System.getProperty("java.io.tmpdir")));
    }

    @Test
    void requestJson() throws Exception {
        Request r =
                new Request(
                        Request.Containment.SEATBELT,
                        Request.Workload.of("true")
                                .withEnv(List.of())
                                .withTimeout(Duration.ofMillis(1500)),
                        null,
                        Request.Network.denyAll(),
                        new Request.Ui(null, false, null),
                        null,
                        null,
                        null,
                        Map.of("lifecycle", Map.of("destroyOnExit", true)));
        JsonNode n = new ObjectMapper().readTree(r.toJson());
        assertEquals("1.0.0", n.get("version").asText());
        assertEquals("seatbelt", n.get("containment").asText());
        assertTrue(
                n.get("process").get("env").isArray() && n.get("process").get("env").isEmpty(),
                "explicit empty env preserved");
        assertEquals(1500, n.get("process").get("timeout").asInt());
        assertFalse(n.get("ui").get("disable").asBoolean(), "explicit false preserved");
        assertEquals("deny", n.get("network").get("egress").get("default").asText());
        assertTrue(n.has("lifecycle"), "extra merged");
        assertFalse(
                new ObjectMapper().readTree(Request.of("x").toJson()).get("process").has("env"),
                "null env omitted");
    }

    @Test
    void nativeAndDiscovery() {
        assertFalse(Mxc.nativeVersion().isEmpty());
        assertNotNull(Mxc.platformSupport());
        assertTrue(Mxc.availableBackends().isArray());
    }

    @Test
    void runCapture() {
        need();
        Result r = Mxc.run(sh("echo out; echo err 1>&2; exit 3"));
        assertEquals(3, r.exitCode());
        assertFalse(r.timedOut());
        assertEquals("out\n", r.stdoutString());
        assertEquals("err\n", r.stderrString());
    }

    @Test
    void enforcesFilesystemPolicy() throws Exception {
        need();
        Path target = Path.of(System.getProperty("user.home"), "mxc_java_should_fail");
        try {
            Result r = Mxc.run(sh("touch " + target + " 2>/dev/null; echo rc=$?"));
            assertNotEquals("rc=0", r.stdoutString().trim());
            assertFalse(Files.exists(target), "file created outside sandbox policy");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    @Timeout(30)
    void runTimeout() {
        need();
        Request req =
                sh("sleep 30")
                        .withProcess(
                                Request.Workload.of("sleep 30")
                                        .withTimeout(Duration.ofMillis(500)));
        long t0 = System.nanoTime();
        Result r = Mxc.run(req);
        assertTrue(r.timedOut());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 10);
    }

    @Test
    void errorsAreMapped() {
        MxcException e =
                assertThrows(
                        MxcException.class,
                        () ->
                                Mxc.runJson(
                                        "{\"version\":\"9.9.9\",\"process\":{\"commandLine\":\"x\"}}",
                                        Mxc.Options.DEFAULT));
        assertEquals(ErrorCode.MALFORMED_REQUEST, e.code());
        MxcException e2 =
                assertThrows(
                        MxcException.class,
                        () -> Mxc.spawnJson("{\"version\":\"9.9.9\"}", Mxc.Options.DEFAULT));
        assertEquals(ErrorCode.MALFORMED_REQUEST, e2.code());
    }

    @Test
    @Timeout(30)
    void streamingStdin() throws Exception {
        need();
        try (MxcProcess p = Mxc.spawn(sh("cat"))) {
            p.stdin().write("hello\nworld\n".getBytes(StandardCharsets.UTF_8));
            p.stdin().flush();
            BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.stdout(), StandardCharsets.UTF_8));
            assertEquals("hello", br.readLine());
            assertEquals("world", br.readLine());
            p.stdin().close();
            Result r = p.waitFor();
            assertEquals(0, r.exitCode());
        }
    }

    @Test
    @Timeout(30)
    void outputDrainsConcurrently() throws Exception {
        need();
        try (MxcProcess p =
                Mxc.spawn(sh("yes out | head -c 300000; yes err | head -c 300000 1>&2"))) {
            Result r = p.output();
            assertEquals(300000, r.stdout().length);
            assertEquals(300000, r.stderr().length);
        }
    }

    @Test
    @Timeout(30)
    void interruptKills() throws Exception {
        need();
        Thread[] t = new Thread[1];
        Throwable[] err = new Throwable[1];
        long t0 = System.nanoTime();
        t[0] =
                new Thread(
                        () -> {
                            try {
                                Mxc.runInterruptibly(sh("sleep 30"), Mxc.Options.DEFAULT);
                            } catch (Throwable e) {
                                err[0] = e;
                            }
                        });
        t[0].start();
        Thread.sleep(400);
        t[0].interrupt();
        t[0].join(10_000);
        assertFalse(t[0].isAlive());
        assertInstanceOf(InterruptedException.class, err[0]);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 10);
    }

    @Test
    @Timeout(30)
    void closeUnblocksRead() throws Exception {
        need();
        MxcProcess p = Mxc.spawn(sh("sleep 30"));
        Thread reader =
                new Thread(
                        () -> {
                            try {
                                p.stdout().transferTo(java.io.OutputStream.nullOutputStream());
                            } catch (Exception ignored) {
                            }
                        });
        reader.start();
        Thread.sleep(200);
        p.close();
        reader.join(10_000);
        assertFalse(reader.isAlive(), "reader still blocked after close");
    }

    @Test
    @Timeout(30)
    void pty() throws Exception {
        need();
        MxcProcess p;
        try {
            p =
                    Mxc.spawnPty(
                            sh("[ -t 0 ] && [ -t 1 ] && echo istty; stty size; read x; stty size"),
                            Mxc.Options.DEFAULT.withPtySize(30, 100));
        } catch (MxcException e) {
            Assumptions.abort("pty unsupported: " + e.getMessage());
            return;
        }
        try (p) {
            BufferedReader br =
                    new BufferedReader(new InputStreamReader(p.stdout(), StandardCharsets.UTF_8));
            assertEquals("istty", br.readLine().trim());
            assertEquals("30 100", br.readLine().trim());
            p.resize(40, 120);
            p.stdin().write("\n".getBytes());
            p.stdin().flush();
            String rest = br.lines().reduce("", (a, b) -> a + b);
            assertTrue(rest.contains("40 120"), "resize not observed: " + rest);
        }
    }

    @Test
    void lifecycleUnavailableOffWindows() {
        MxcException e =
                assertThrows(
                        MxcException.class,
                        () -> Mxc.provision(Map.of("containment", "wslc"), Mxc.Options.DEFAULT));
        assertNotNull(e.code());
        assertThrows(
                MxcException.class,
                () -> Mxc.start("wslc:0123456789abcdef0123456789abcdef", Mxc.Options.DEFAULT));
    }
}
