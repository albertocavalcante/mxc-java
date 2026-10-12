package io.github.albertocavalcante.mxc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;

/** A forgotten close() must not leave the sandboxed process running forever. */
class CleanerTest {
    // Random at runtime so no harness command line (shell, IDE) can contain it.
    private static final String MARKER =
            "sleep " + java.util.concurrent.ThreadLocalRandom.current().nextInt(900_000, 999_999);

    private static boolean markerRunning() throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("pgrep", "-f", MARKER).redirectErrorStream(true).start();
        String out = new String(ps.getInputStream().readAllBytes());
        ps.waitFor();
        return !out.isBlank();
    }

    private static void leak() {
        MxcProcess p =
                Mxc.spawn(
                        Request.of(MARKER)
                                .withProcess(
                                        Request.Workload.of(MARKER)
                                                .withTimeout(Duration.ofMinutes(2))));
        // Intentionally not closed; p becomes unreachable on return.
        p.pid();
    }

    @Test
    @Timeout(60)
    void unreachableProcessIsKilledByCleaner() throws Exception {
        TestPlatform.needPosixSandbox();
        leak();
        Thread.sleep(300);
        assertTrue(markerRunning(), "test setup: the sandboxed sleep should be running");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (markerRunning() && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(200);
        }
        assertFalse(markerRunning(), "Cleaner did not kill the leaked sandboxed process");
    }
}
