package codeinterp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The service's policy. Everything here is operator-controlled; nothing is taken from a request
 * except the language, code, stdin and timeout.
 */
final class Config {
    final Path workRoot;
    final List<String> denyPaths;
    final Map<String, Interpreter> interpreters = Interpreter.discover();
    final Duration maxTimeout = Duration.ofSeconds(30);
    final int maxCodeBytes = 256 * 1024;
    final int outputLimit = 64 * 1024;
    final int fileSizeKb = 10 * 1024;
    final int maxOpenFiles = 128;
    volatile int cpuSeconds = 20;
    volatile Semaphore slots = new Semaphore(4);

    Config(Path workRoot, List<String> denyPaths) throws IOException {
        this.workRoot = Files.createDirectories(workRoot);
        this.denyPaths = List.copyOf(denyPaths);
    }
}
