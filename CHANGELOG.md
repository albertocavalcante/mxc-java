# Changelog

## Versioning policy

`mxc-java` follows **0.x = unstable**: breaking changes may ship in any minor
release until 1.0, after which it follows semver. Each release names the MXC
commit it was tested against.

## Unreleased

Initial implementation, tested against MXC `c6f301d` (see `MXC_REF`) on JDK 25:

- Typed `1.0.0` request model, `Mxc.run`, `runJson`, `runAsync`, `spawn`,
  `spawnPty`.
- Live processes with `InputStream`/`OutputStream` stdio, `waitFor`, `output`,
  `kill`, `resize`, and interrupt-driven cancellation.
- Persistent-container lifecycle, discovery, telemetry consent, `MxcException`.

Behavior worth knowing (found while hardening before the first tag):

- `MxcProcess.close()` kills the tree first, so it cannot hang behind a blocked
  stream read or write. `output()` closes stdin and bounds the drain when a
  grandchild keeps the pipes open.
- `runJson`/`spawnJson` reject a NUL byte instead of letting the C boundary
  silently truncate the request.
- Telemetry: `telemetryConsent()` and `telemetryPolicy()` return `ConsentState`
  and `PolicyState`; they are bare strings natively, not JSON.
- `Workload.withTimeout` rejects values above the schema maximum; an unresolved
  `${env.MXC_FFI_DIR}` no longer leaks into the library path.
- Captured output from `run` is decoded lossily by the native layer (invalid
  UTF-8 and NUL become U+FFFD); use `spawn` for raw bytes.
