package codeinterp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.albertocavalcante.mxc.Mxc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A code-interpreter service: runs untrusted snippets (for example LLM-generated code) in an MXC
 * sandbox.
 *
 * <pre>
 * POST /v1/run         {"language":"python","code":"print(1+1)"}  -> JSON result
 * POST /v1/run/stream  same request                               -> Server-Sent Events
 * GET  /healthz                                                   -> sandbox support
 * </pre>
 *
 * <p>Run it as an unprivileged user, ideally in a container or VM, because MXC itself does not
 * limit memory or process count. Bind loopback unless you add authentication.
 */
public final class Server {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Server() {}

    /** Starts the service on {@code address}; port 0 picks a free port. */
    static HttpServer start(Config cfg, InetSocketAddress address) throws IOException {
        HttpServer http = HttpServer.create(address, 0);
        http.setExecutor(Executors.newCachedThreadPool());
        http.createContext("/healthz", ex -> guard(ex, "GET", () -> health(cfg, ex)));
        http.createContext("/v1/run/stream", ex -> guard(ex, "POST", () -> stream(cfg, ex)));
        http.createContext("/v1/run", ex -> guard(ex, "POST", () -> run(cfg, ex)));
        http.start();
        return http;
    }

    /** Entry point: {@code --addr host:port --work dir --deny a,b}. */
    public static void main(String[] args) throws IOException {
        String addr = "127.0.0.1:8080";
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "codeinterp");
        String deny = System.getProperty("user.home");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--addr" -> addr = args[i + 1];
                case "--work" -> work = Path.of(args[i + 1]);
                case "--deny" -> deny = args[i + 1];
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        String[] hostPort = addr.split(":");
        Config cfg = new Config(work, List.of(deny.split(",")));
        HttpServer http =
                start(cfg, new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])));
        System.out.printf(
                "codeinterp listening on http://%s (mxc native %s)%n", addr, Mxc.nativeVersion());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> http.stop(2)));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void guard(HttpExchange ex, String method, Action action) throws IOException {
        try {
            if (!ex.getRequestMethod().equals(method)) {
                send(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            action.run();
        } catch (Runner.InvalidRequest e) {
            send(ex, 400, Map.of("error", "invalid request: " + e.getMessage()));
        } catch (Runner.Busy e) {
            ex.getResponseHeaders().set("Retry-After", "1");
            send(ex, 429, Map.of("error", e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            send(ex, 400, Map.of("error", e.getOriginalMessage()));
        } catch (Exception e) {
            System.err.println("run failed:");
            e.printStackTrace();
            send(ex, 500, Map.of("error", "sandbox failure"));
        } finally {
            ex.close();
        }
    }

    private static void health(Config cfg, HttpExchange ex) throws IOException {
        Mxc.PlatformSupport ps = Mxc.platformSupport();
        if (!ps.isSupported()) {
            send(ex, 503, Map.of("ok", false, "support", ps));
            return;
        }
        send(
                ex,
                200,
                Map.of(
                        "ok",
                        true,
                        "backends",
                        ps.availableMethods(),
                        "languages",
                        List.copyOf(cfg.interpreters.keySet())));
    }

    private static Runner.RunRequest decode(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes((1 << 20) + 1);
        if (body.length > (1 << 20)) {
            throw new IOException("request body too large");
        }
        return JSON.readValue(body, Runner.RunRequest.class);
    }

    private static void run(Config cfg, HttpExchange ex) throws Exception {
        Runner.RunRequest rq = decode(ex);
        send(ex, 200, Runner.execute(cfg, rq, null));
    }

    private static void stream(Config cfg, HttpExchange ex) throws Exception {
        Runner.RunRequest rq = decode(ex);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        Runner.RunResult res;
        try {
            res = Runner.execute(cfg, rq, (name, data) -> event(out, name, Map.of("data", data)));
        } catch (Runner.InvalidRequest | Runner.Busy e) {
            event(out, "error", Map.of("error", e.getMessage()));
            return;
        }
        Runner.RunResult tail =
                new Runner.RunResult(
                        res.exitCode(), "", "", res.timedOut(), res.truncated(), res.durationMs());
        event(out, "result", tail);
    }

    /** Writes one SSE event; returns false once the client has gone away. */
    private static synchronized boolean event(OutputStream out, String name, Object payload) {
        try {
            out.write(
                    ("event: " + name + "\ndata: " + JSON.writeValueAsString(payload) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
