package io.github.albertocavalcante.mxc;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A completed run.
 *
 * @param exitCode workload exit code; -1 when {@code timedOut}
 * @param timedOut the request's timeout expired and the workload was killed
 * @param warnings security warnings raised during the run (e.g. relaxed policy)
 * @param metadataJson backend-specific output metadata JSON, or null
 */
public record Result(
        int exitCode,
        boolean timedOut,
        byte[] stdout,
        byte[] stderr,
        List<String> warnings,
        String metadataJson) {
    public String stdoutString() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrString() {
        return new String(stderr, StandardCharsets.UTF_8);
    }
}
