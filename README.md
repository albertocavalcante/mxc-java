# mxc-java

[![ci](https://github.com/albertocavalcante/mxc-java/actions/workflows/ci.yml/badge.svg)](https://github.com/albertocavalcante/mxc-java/actions/workflows/ci.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An **unofficial** JVM binding for [MXC](https://github.com/microsoft/mxc)
(Microsoft eXecution Container), a policy-driven sandbox for running untrusted
code: model output, plugins, tools. You describe the workload and a policy
(filesystem, network, UI); MXC picks the OS-native backend (Seatbelt on macOS,
Bubblewrap on Linux, ProcessContainer on Windows, and others) and enforces it.

This library is not affiliated with or endorsed by Microsoft. It calls MXC's
native `mxc_ffi` C library through Panama (`java.lang.foreign`), the same layer
Microsoft's .NET and Node SDKs use. There is no JNI and no C glue, and it works
from Kotlin and Scala too.

```java
Result r = Mxc.run(
    Request.of("echo hello")
        .withProcess(Request.Workload.of("echo hello").withTimeout(Duration.ofSeconds(10)))
        .withFilesystem(Request.Filesystem.readwrite(System.getProperty("java.io.tmpdir")))
        .withNetwork(Request.Network.denyAll()));

System.out.println(r.exitCode() + " " + r.stdoutString());
```

## Features

- Typed `1.0.0` request model (immutable records with `with*` copies) that
  serializes to MXC's exact request JSON; `Request.withExtra`, `runJson` and
  `spawnJson` cover backend-specific sections and any registered raw version.
- Captured runs and **live processes** exposing `InputStream` / `OutputStream`.
- **PTY** processes with resize.
- Interrupt-aware: interrupting a waiting thread kills the sandboxed process
  tree. `runAsync` runs on a virtual thread.
- Persistent-container lifecycle (`provision`, `start`, `exec`, `spawnExec`,
  `stop`, `deprovision`, `validate`; WSLC and IsolationSession on Windows hosts).
- Backend discovery, telemetry consent (including the `requestConsent` prompt
  flow with a throw-safe presenter), and structured `MxcException` with an
  `ErrorCode`, failing operation, native status and remediation.
- A forgotten `close()` is not fatal: a `Cleaner` kills and reclaims the process.

## Requirements

- **JDK 22 or newer** (`java.lang.foreign` is final from 22). Run with
  `--enable-native-access=ALL-UNNAMED` to silence the restricted-method warning.
- The native library `libmxc_ffi`, which is **not bundled** (see below).

## Install

Not yet published to Maven Central. Until then, build and install locally:

```sh
./mvnw install -DskipTests
```

```xml
<dependency>
  <groupId>io.github.albertocavalcante</groupId>
  <artifactId>mxc-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Native library

Build `libmxc_ffi` from the MXC commit this library is tested against
(`MXC_REF`):

```sh
just native      # clones microsoft/mxc into .mxc/ and builds it with cargo
```

The library is located in this order: `-Dmxc.ffi.path` (file or directory),
`$MXC_FFI_DIR`, a jar resource at `META-INF/native/<os>-<arch>/`, then the
platform loader path. Failure to find it raises an `UnsatisfiedLinkError` that
says where it looked.

## Usage

### Stream a process

```java
try (MxcProcess p = Mxc.spawn(Request.of("cat"))) {
    p.stdin().write("ping\n".getBytes(StandardCharsets.UTF_8));
    p.stdin().close();                       // EOF
    String out = new String(p.stdout().readAllBytes(), StandardCharsets.UTF_8);
    Result r = p.waitFor();                  // exit status; kills on interrupt
}
```

`MxcProcess.output()` drains stdout and stderr concurrently and returns
everything, which avoids pipe deadlocks.

### Timeouts and cancellation

`Workload.withTimeout` is enforced by MXC itself and reported as
`Result.timedOut()`. For caller-driven cancellation use `Mxc.runInterruptibly`
or `MxcProcess.waitFor`: interrupting the thread kills the workload and rethrows
`InterruptedException`.

### Environment semantics

`Workload.env == null` selects the backend's default environment; an empty list
sends an explicitly empty environment.

## Behavior notes

- `run` output is decoded lossily by the native layer (invalid UTF-8 and NUL
  become U+FFFD). Use `spawn` and read the streams for raw bytes.
- `Result.exitCode()` is `-1` when the workload did not exit normally (timeout or
  killed by a signal).
- Closing a PTY process's stdin sends the terminal EOF character, which the
  terminal echoes as `^D`; `output()` closes stdin first, so PTY output from it
  may begin with it.
- Cross-field rules (an ICMP rule has no port, an `except` CIDR lies inside its
  `cidr`, ...) are enforced by MXC natively and surface as a `MALFORMED_REQUEST`
  or `POLICY_VALIDATION` exception rather than being duplicated here.

## Platform support

| Platform | Backend | Status |
|---|---|---|
| macOS (arm64) | Seatbelt | Tested on JDK 25: run, filesystem enforcement, timeout, streaming, interrupt, PTY |
| Linux | Bubblewrap, LXC | Untested; builds in CI on a best-effort basis |
| Windows | ProcessContainer, WSLC, ... | Untested (lifecycle code is written but unverified) |

Use `Mxc.platformSupport()` and `Mxc.availableBackends()` to check at runtime.

## Verification

Beyond unit tests:

- **Formal model.** The close protocol (handle lock, stream locks held across
  blocking I/O, `kill`/`wait`, `Cleaner`) is modeled in TLA+ and exhaustively
  checked by TLC in CI; see
  [mxc-go/formal](https://github.com/albertocavalcante/mxc-go/tree/main/formal).
  `MxcProcess` follows the same design (kill first, then streams, then the
  handle), and `CleanerTest` exercises the safety net against the real library.
- **Property test** (`PropertyTest`): random requests built through the typed API
  are never rejected by MXC's strict contract parser as malformed.
- **ABI check**: every C function bound in `Native.java` must be exported by the
  pinned native library.

## Stability

MXC's C ABI is explicitly **unstable** ("regenerate bindings when this surface
changes"). This library pins a tested MXC commit in `MXC_REF` and verifies that
every function it binds is exported by the built library (`just abi-check`, run
in CI). On upgrades, re-diff `internal/Native.java` against MXC's
`src/ffi/mxc_ffi/src/*.rs`. This library is v0.x: the API may change before 1.0.

## Development

```sh
just native && just ci     # fmt-check, verify (compile -Xlint -Werror, tests, Checkstyle), javadoc, ABI check
just hooks                 # install lefthook git hooks
just fmt                   # google-java-format (AOSP), checksum-pinned
just lint                  # Checkstyle: no star imports, braces required, public API documented
```

Commits must follow [Conventional Commits](https://www.conventionalcommits.org/)
with a subject of at most 72 characters (enforced by the `commit-msg` hook and
in CI). See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. MXC itself is MIT-licensed by Microsoft Corporation and is not included
here.
