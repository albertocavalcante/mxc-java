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
