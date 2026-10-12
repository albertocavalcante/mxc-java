package io.github.albertocavalcante.mxc;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A create-and-run container request in the exact MXC {@code 1.0.0} contract.
 *
 * <p>Null fields are omitted. A non-null empty {@code env} list is sent as an explicit empty
 * environment; null selects the backend default environment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Request(
        Containment containment,
        Workload process,
        Filesystem filesystem,
        Network network,
        Ui ui,
        Telemetry telemetry,
        Seatbelt seatbelt,
        RuntimeConfig runtimeConfig,
        @JsonIgnore Map<String, Object> extra) {

    public static final String SCHEMA_VERSION = "1.0.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Request running {@code commandLine} under the platform default containment. */
    public static Request of(String commandLine) {
        return new Request(
                null, Workload.of(commandLine), null, null, null, null, null, null, null);
    }

    public Request withContainment(Containment c) {
        return new Request(
                c, process, filesystem, network, ui, telemetry, seatbelt, runtimeConfig, extra);
    }

    public Request withProcess(Workload p) {
        return new Request(
                containment, p, filesystem, network, ui, telemetry, seatbelt, runtimeConfig, extra);
    }

    public Request withFilesystem(Filesystem f) {
        return new Request(
                containment, process, f, network, ui, telemetry, seatbelt, runtimeConfig, extra);
    }

    public Request withNetwork(Network n) {
        return new Request(
                containment, process, filesystem, n, ui, telemetry, seatbelt, runtimeConfig, extra);
    }

    /** Returns a copy with the given UI policy. */
    public Request withUi(Ui u) {
        return new Request(
                containment,
                process,
                filesystem,
                network,
                u,
                telemetry,
                seatbelt,
                runtimeConfig,
                extra);
    }

    public Request withTelemetry(Telemetry t) {
        return new Request(
                containment, process, filesystem, network, ui, t, seatbelt, runtimeConfig, extra);
    }

    public Request withSeatbelt(Seatbelt s) {
        return new Request(
                containment, process, filesystem, network, ui, telemetry, s, runtimeConfig, extra);
    }

    public Request withRuntimeConfig(RuntimeConfig r) {
        return new Request(
                containment, process, filesystem, network, ui, telemetry, seatbelt, r, extra);
    }

    /**
     * Extra top-level sections (e.g. {@code processContainer}, {@code lxc}) merged into the
     * document.
     */
    public Request withExtra(Map<String, Object> e) {
        return new Request(
                containment,
                process,
                filesystem,
                network,
                ui,
                telemetry,
                seatbelt,
                runtimeConfig,
                e);
    }

    @JsonAnyGetter
    Map<String, Object> anyExtra() {
        return extra;
    }

    /** Exact MXC request JSON, including {@code "version"}. */
    public String toJson() {
        ObjectNode body = MAPPER.valueToTree(this);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("version", SCHEMA_VERSION);
        out.setAll(body);
        return out.toString();
    }

    /** Containment backend selection; leave null for the platform default. */
    public enum Containment {
        PROCESS("process"),
        PROCESS_CONTAINER("processcontainer"),
        LXC("lxc"),
        BUBBLEWRAP("bubblewrap"),
        SEATBELT("seatbelt"),
        ISOLATION_SESSION("isolation_session"),
        WSLC("wslc");
        private final String wire;

        Containment(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Allow or deny decision for network rules. */
    public enum Action {
        ALLOW("allow"),
        DENY("deny");
        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Transport protocol of a port rule. */
    public enum Protocol {
        TCP("tcp"),
        UDP("udp"),
        ICMP("icmp"),
        ANY("any");
        private final String wire;

        Protocol(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Clipboard access level. */
    public enum Clipboard {
        NONE("none"),
        READ("read"),
        WRITE("write"),
        ALL("all");
        private final String wire;

        Clipboard(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Largest {@code timeout} (milliseconds) the request schema accepts. */
    public static final long MAX_TIMEOUT_MILLIS = 4294967295L;

    /** The process to execute. {@code timeout} is milliseconds on the wire. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Workload(
            String commandLine,
            String cwd,
            List<String> env,
            Boolean inheritDefaultEnv,
            Long timeout) {
        public static Workload of(String commandLine) {
            return new Workload(commandLine, null, null, null, null);
        }

        public Workload withCwd(String v) {
            return new Workload(commandLine, v, env, inheritDefaultEnv, timeout);
        }

        public Workload withEnv(List<String> v) {
            return new Workload(commandLine, cwd, v, inheritDefaultEnv, timeout);
        }

        public Workload withInheritDefaultEnv(boolean v) {
            return new Workload(commandLine, cwd, env, v, timeout);
        }

        /** Sets the timeout (millisecond resolution, at most {@link #MAX_TIMEOUT_MILLIS}). */
        public Workload withTimeout(Duration d) {
            if (d.compareTo(Duration.ofMillis(MAX_TIMEOUT_MILLIS)) > 0) {
                throw new IllegalArgumentException(
                        "timeout "
                                + d
                                + " exceeds the schema maximum of "
                                + MAX_TIMEOUT_MILLIS
                                + " ms");
            }
            long ms = d.toMillis();
            return new Workload(
                    commandLine, cwd, env, inheritDefaultEnv, d.isZero() ? null : Math.max(1, ms));
        }
    }

    /** Filesystem policy: read-only, read-write and denied paths. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filesystem(
            List<String> readonlyPaths, List<String> readwritePaths, List<String> deniedPaths) {
        public static Filesystem readwrite(String... paths) {
            return new Filesystem(null, List.of(paths), null);
        }

        public static Filesystem readonly(String... paths) {
            return new Filesystem(List.of(paths), null, null);
        }
    }

    /** Directional network policy; omitted directions default to deny. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Network(Egress egress, Ingress ingress) {
        public static Network denyAll() {
            return new Network(
                    new Egress(Action.DENY, null, null), new Ingress(Action.DENY, Action.DENY));
        }
    }

    /** Outbound network rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Egress(
            @JsonProperty("default") Action defaultAction, List<Rule> allow, List<Rule> deny) {}

    /** Inbound and host-loopback network rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ingress(@JsonProperty("default") Action defaultAction, Action hostLoopback) {}

    /** A network rule: optional destination CIDRs and ports (omission matches all). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(List<Peer> to, List<Port> ports) {}

    /** A destination CIDR with optional excluded CIDRs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Peer(String cidr, List<String> except) {}

    /** A destination protocol and port or port range. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Port(Protocol protocol, Integer port, Integer endPort) {}

    /** Cross-platform user-interface policy. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ui(Clipboard clipboard, Boolean disable, Boolean injection) {}

    /** Opts one run into diagnostic telemetry (still subject to user consent). */
    public record Telemetry(boolean enabled) {}

    /** macOS Seatbelt backend settings. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Seatbelt(
            List<String> extraMachLookups,
            Boolean guiAccess,
            Boolean keychainAccess,
            Boolean nestedPty,
            String profileOverride) {}

    /** Runtime values such as a cooperative network proxy URL. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuntimeConfig(String networkProxy) {}
}
