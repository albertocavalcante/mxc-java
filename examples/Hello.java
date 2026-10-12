import io.github.albertocavalcante.mxc.Mxc;
import io.github.albertocavalcante.mxc.Request;
import io.github.albertocavalcante.mxc.Result;
import java.time.Duration;

/**
 * Run with: just example
 * (java --enable-native-access=ALL-UNNAMED -cp target/classes:... examples/Hello.java)
 */
public class Hello {
    public static void main(String[] args) {
        Result r =
                Mxc.run(
                        Request.of("echo hello from the sandbox")
                                .withProcess(
                                        Request.Workload.of("echo hello from the sandbox")
                                                .withTimeout(Duration.ofSeconds(10)))
                                .withFilesystem(
                                        Request.Filesystem.readwrite(
                                                System.getProperty("java.io.tmpdir")))
                                .withNetwork(Request.Network.denyAll()));
        System.out.printf("exit=%d stdout=%s%n", r.exitCode(), r.stdoutString().trim());
    }
}
