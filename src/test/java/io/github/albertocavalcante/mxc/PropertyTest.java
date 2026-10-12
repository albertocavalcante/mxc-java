package io.github.albertocavalcante.mxc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Property: nothing the typed API can emit is rejected as {@code MALFORMED_REQUEST} by MXC's strict
 * contract parser (it denies unknown fields and checks enums). The native parser is the oracle, so
 * typed-model drift shows up here.
 */
class PropertyTest {
    private static final String TMP = System.getProperty("java.io.tmpdir");

    private static <T> T pick(Random r, List<T> xs) {
        return xs.get(r.nextInt(xs.size()));
    }

    private static Boolean bool(Random r) {
        return r.nextBoolean();
    }

    static Request randomRequest(Random r) {
        Request.Workload w = Request.Workload.of("true");
        if (r.nextBoolean()) {
            w = w.withCwd(pick(r, List.of("/tmp", "/usr", TMP)));
        }
        switch (r.nextInt(3)) {
            case 1 -> w = w.withEnv(List.of());
            case 2 ->
                    w =
                            w.withEnv(List.of("A=1", "B=two words"))
                                    .withInheritDefaultEnv(r.nextBoolean());
            default -> {
                // null: backend default environment
            }
        }
        if (r.nextBoolean()) {
            w = w.withTimeout(Duration.ofMillis(1 + r.nextInt(30_000)));
        }
        Request req = Request.of("true").withProcess(w);
        // A backend section must match the selected containment (a cross-field rule MXC
        // enforces), and Seatbelt is only selectable on macOS.
        boolean mac = TestPlatform.mac();
        if (mac && r.nextBoolean()) {
            req = req.withContainment(Request.Containment.SEATBELT);
        }
        if (r.nextBoolean()) {
            req =
                    req.withFilesystem(
                            new Request.Filesystem(
                                    r.nextBoolean()
                                            ? List.of(pick(r, List.of("/usr", "/bin", "/etc")))
                                            : null,
                                    r.nextBoolean() ? List.of(TMP, "/tmp") : null,
                                    r.nextBoolean()
                                            ? List.of(
                                                    pick(
                                                            r,
                                                            List.of(
                                                                    "/tmp/mxc-denied",
                                                                    "/var/empty")))
                                            : null));
        }
        if (r.nextBoolean()) {
            Request.Egress egress = null;
            if (r.nextBoolean()) {
                List<Request.Rule> allow = null;
                if (r.nextBoolean()) {
                    Request.Peer peer =
                            r.nextBoolean()
                                    ? new Request.Peer("10.0.0.0/8", List.of("10.1.0.0/16"))
                                    : new Request.Peer("fd00::/8", List.of("fd00:1::/32"));
                    Request.Port port =
                            switch (r.nextInt(3)) {
                                case 0 -> new Request.Port(Request.Protocol.ICMP, null, null);
                                case 1 ->
                                        new Request.Port(
                                                Request.Protocol.TCP, 1 + r.nextInt(65535), null);
                                default -> new Request.Port(Request.Protocol.UDP, 1000, 2000);
                            };
                    allow = List.of(new Request.Rule(List.of(peer), List.of(port)));
                }
                egress = new Request.Egress(pick(r, List.of(Request.Action.values())), allow, null);
            }
            Request.Ingress ingress =
                    r.nextBoolean()
                            ? new Request.Ingress(
                                    pick(r, List.of(Request.Action.values())),
                                    pick(r, List.of(Request.Action.values())))
                            : null;
            req = req.withNetwork(new Request.Network(egress, ingress));
        }
        if (r.nextBoolean()) {
            req =
                    req.withUi(
                            new Request.Ui(
                                    pick(r, List.of(Request.Clipboard.values())),
                                    bool(r),
                                    bool(r)));
        }
        if (r.nextBoolean()) {
            req = req.withTelemetry(new Request.Telemetry(false));
        }
        if (mac && r.nextBoolean()) {
            req = req.withSeatbelt(new Request.Seatbelt(null, bool(r), bool(r), bool(r), null));
        }
        if (r.nextBoolean()) {
            req = req.withRuntimeConfig(new Request.RuntimeConfig(null));
        }
        return req;
    }

    @Test
    void typedRequestsAreNeverMalformed() {
        TestPlatform.needPosixSandbox();
        long seed = System.nanoTime();
        Random r = new Random(seed);
        Map<String, Integer> outcomes = new HashMap<>();
        for (int i = 0; i < 150; i++) {
            Request req = randomRequest(r);
            try {
                Mxc.run(req);
                outcomes.merge("ok", 1, Integer::sum);
            } catch (MxcException e) {
                if (e.code() == ErrorCode.MALFORMED_REQUEST) {
                    fail(
                            "typed request rejected as malformed (seed="
                                    + seed
                                    + ", i="
                                    + i
                                    + "): "
                                    + e.getMessage()
                                    + "\n"
                                    + req.toJson());
                }
                outcomes.merge(e.code().name(), 1, Integer::sum);
            }
        }
        System.out.println("PropertyTest seed=" + seed + " outcomes=" + outcomes);
        // Non-vacuous: a healthy share of the random requests must actually run.
        assertTrue(outcomes.getOrDefault("ok", 0) >= 20, "generator too hostile: " + outcomes);
        assertEquals(150, outcomes.values().stream().mapToInt(Integer::intValue).sum());
    }
}
