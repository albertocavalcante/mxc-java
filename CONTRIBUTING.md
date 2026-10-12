# Contributing

## Setup

```sh
brew install just lefthook      # or your package manager
just native                     # build libmxc_ffi from the pinned MXC commit (needs Rust)
just hooks                      # install git hooks
just ci                         # everything CI runs (needs JDK 22+)
```

## Commits

[Conventional Commits](https://www.conventionalcommits.org/):
`type(scope)!: subject`, where type is one of `build chore ci docs feat fix perf
refactor revert style test`, subject at most 72 characters. The `commit-msg`
hook rejects anything else; CI checks every commit in a PR.

## Upgrading MXC

1. Update `MXC_REF` to the new commit and `just native`.
2. Diff MXC's `src/ffi/mxc_ffi/src/*.rs` against `internal/Native.java`
   (struct layouts and signatures); update the bindings.
3. `just abi-check` and `just ci`.
4. Record the bump in `CHANGELOG.md`.

## Style

google-java-format (AOSP) via `just fmt`; the build compiles with
`-Xlint:all -Werror`. Keep Panama code confined to `internal/Native.java`; the
public API must never expose a `MemorySegment`.

## Releasing

`./mvnw -Prelease deploy` builds signed sources and javadoc jars and publishes
through the Central Portal (`central-publishing-maven-plugin`). It needs a GPG
key and a `central` server entry with portal credentials in `~/.m2/settings.xml`.
Set `autoPublish` to true in the pom only once a release has been checked by hand.
