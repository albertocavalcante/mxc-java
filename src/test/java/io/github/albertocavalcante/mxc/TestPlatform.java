package io.github.albertocavalcante.mxc;

import org.junit.jupiter.api.Assumptions;

import java.util.Locale;

/** Platform gates shared by the tests. */
final class TestPlatform {
    private TestPlatform() {}

    static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean mac() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Skips unless a sandbox backend is available and commands are POSIX shell. The shell-based
     * tests cannot run on Windows (cmd.exe), where the job verifies library load, discovery and
     * telemetry instead.
     */
    static void needPosixSandbox() {
        Assumptions.assumeFalse(windows(), "tests use POSIX shell commands");
        Assumptions.assumeTrue(Mxc.platformSupport().isSupported(), "no sandbox backend");
    }
}
