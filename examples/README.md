# Examples

| Example | What it shows |
|---|---|
| [`Hello.java`](Hello.java) | The smallest possible `Mxc.run`. Run with `just example`. |
| [`codeinterp`](codeinterp) | A code-interpreter HTTP service for untrusted snippets (LLM output, plugins), with a hostile test suite. A separate Maven module on the JDK's built-in HTTP server: no web framework. |

```sh
just native && export MXC_FFI_DIR="$PWD/.mxc/src/target/release"
just serve                                   # builds the library + example, listens on 127.0.0.1:8080
curl -s localhost:8080/v1/run -d '{"language":"python","code":"print(sum(range(10)))"}'
curl -sN localhost:8080/v1/run/stream -d '{"language":"sh","code":"echo one; sleep 1; echo two"}'
```

(`just serve` runs `codeinterp.Server`, which takes `--addr host:port`, `--work dir`
and `--deny a,b`; run your own JVM with `--enable-native-access=ALL-UNNAMED`.)

## `codeinterp`: what the policy does

Per request, the service writes the code into a fresh private directory and runs
it with:

- **Filesystem**: the work directory is the only writable path; operator-chosen
  secret paths (default: your home directory) are denied; the interpreter's own
  directories are readable.
- **Network**: all egress, ingress and host-loopback denied.
- **Environment**: an explicit minimal one, so the service's own credentials are
  never inherited.
- **Limits**: wall-clock timeout (MXC), plus CPU seconds, file size and open
  files via `ulimit`, an output cap per stream that **kills** a flooding
  producer, a concurrency cap (`429` when busy), and a request body cap.
- **Cleanup**: the work directory is removed, and the whole process tree is
  killed on timeout, when a streaming client disconnects, and when a background
  child would otherwise hold the response open.

`mvn test` in the module (or `just examples`) drives the service with hostile
snippets and asserts the attacks fail: reading a canary secret, escaping through
a symlink, writing outside the work directory, connecting to a local listener and
to the internet, leaking the service environment, spinning forever, flooding
output, leaving background processes behind, and holding the response open from a
child. Each rule was mutation-checked: weakening it makes the matching test fail.
The Go binding has the same example and suite.

### Lessons from building it

- MXC enforces filesystem and network policy and a wall-clock timeout. It does
  **not** cap memory, CPU or process count: a 2,000-process fork storm succeeds.
  Add `ulimit`s, and for real resource isolation run the service in a container
  or VM. Never expose it beyond loopback without authentication.
- Issue one `ulimit` per option. `dash` (`/bin/sh` on Debian and Ubuntu) accepts
  only one per call, and a combined call fails silently and leaves the process
  unlimited. Linux CI caught this.
- Homebrew interpreters link libraries across their whole prefix, so the policy
  grants that prefix read-only.
- Local addresses are governed by the host-loopback rule, so proving that egress
  is blocked needs an external destination (the test checks the test host can
  reach it unsandboxed first, so it cannot pass vacuously).
- The JDK's `HttpServer` cannot tell a handler that the client disconnected, so
  only the streaming endpoint stops a run when the client goes away. Use a
  framework that exposes disconnects if you need it for the plain endpoint.
