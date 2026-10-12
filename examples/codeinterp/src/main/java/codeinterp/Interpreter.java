package codeinterp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How to run one language inside the sandbox.
 *
 * @param file name of the file written into the work directory
 * @param command absolute interpreter path
 * @param args arguments between the interpreter and the file (with a trailing space, or empty)
 * @param readOnly directories the interpreter needs to read
 */
record Interpreter(String file, String command, String args, List<String> readOnly) {

    /** The interpreters this host can offer: sh always, python and node when installed. */
    static Map<String, Interpreter> discover() {
        Map<String, Interpreter> found = new java.util.LinkedHashMap<>();
        found.put("sh", new Interpreter("main.sh", "/bin/sh", "", List.of()));
        // -I: Python isolated mode, ignoring environment variables and user site.
        addIfInstalled(found, "python", "python3", "main.py", "-I ");
        addIfInstalled(found, "node", "node", "main.js", "");
        return found;
    }

    private static void addIfInstalled(
            Map<String, Interpreter> into, String lang, String bin, String file, String args) {
        String path = System.getenv("PATH");
        if (path == null) {
            return;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, bin);
            if (Files.isExecutable(candidate)) {
                try {
                    Path real = candidate.toRealPath();
                    into.put(lang, new Interpreter(file, real.toString(), args, readRoots(real)));
                } catch (IOException ignored) {
                    // unresolvable: simply not offered
                }
                return;
            }
        }
    }

    /**
     * Read-only roots an interpreter needs. A Homebrew binary links libraries all over its prefix,
     * so that prefix is granted read-only; nothing under it is writable or secret.
     */
    static List<String> readRoots(Path bin) {
        List<String> roots = new ArrayList<>();
        roots.add(bin.getParent().toString());
        if (bin.getParent().getParent() != null) {
            roots.add(bin.getParent().getParent().toString());
        }
        for (String prefix : List.of("/opt/homebrew", "/usr/local")) {
            if (bin.startsWith(prefix + "/")) {
                roots.add(prefix);
            }
        }
        return roots;
    }
}
