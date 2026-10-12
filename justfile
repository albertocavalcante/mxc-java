# mxc-java development commands (needs JDK 22+ on PATH / JAVA_HOME).
#
# Native library: mxc_ffi is built from a pinned microsoft/mxc checkout (MXC_REF)
# into .mxc/. Override MXC_FFI_DIR to use an existing build instead.

mxc_ref := `cat MXC_REF`
mxc_dir := justfile_directory() / ".mxc"

export MXC_FFI_DIR := env("MXC_FFI_DIR", mxc_dir / "src/target/release")

default: test

# Build libmxc_ffi from the pinned upstream commit.
native:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ ! -d "{{ mxc_dir }}/.git" ]; then
        git clone --filter=blob:none https://github.com/microsoft/mxc "{{ mxc_dir }}"
    fi
    git -C "{{ mxc_dir }}" fetch --quiet origin "{{ mxc_ref }}" || git -C "{{ mxc_dir }}" fetch --quiet origin
    git -C "{{ mxc_dir }}" checkout --quiet "{{ mxc_ref }}"
    cargo build --release -p mxc_ffi --manifest-path "{{ mxc_dir }}/src/Cargo.toml"

build:
    ./mvnw -B -q -DskipTests compile

test:
    ./mvnw -B test

# Full verification: compile, test, package.
verify:
    ./mvnw -B verify

fmt:
    ./scripts/gjf.sh apply

fmt-check:
    ./scripts/gjf.sh check

# Build and test the example modules (installs the library into ~/.m2 first).
examples:
    ./mvnw -B -q install -DskipTests -Dcheckstyle.skip
    ./mvnw -B -f examples/codeinterp/pom.xml verify

# Run the codeinterp example service (Ctrl-C to stop).
serve addr="127.0.0.1:8080":
    #!/usr/bin/env bash
    set -euo pipefail
    ./mvnw -B -q install -DskipTests -Dcheckstyle.skip
    ./mvnw -B -q -f examples/codeinterp/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    exec java --enable-native-access=ALL-UNNAMED -cp "examples/codeinterp/target/classes:$(cat examples/codeinterp/target/cp.txt)" codeinterp.Server --addr {{ addr }}

# Checkstyle: star/unused imports, braces, naming, public-API Javadoc.
lint:
    ./mvnw -B -q checkstyle:check@checkstyle

# Javadoc must build without errors (doclint) since Central requires it.
javadoc:
    ./mvnw -B -q -Prelease -DskipTests -Dgpg.skip=true javadoc:javadoc

# Verify every C function this binding binds exists in the built library.
abi-check:
    ./scripts/abi-check.sh "$MXC_FFI_DIR"

ci: fmt-check verify javadoc abi-check examples

# Point git at the repo's hooks (lefthook).
hooks:
    lefthook install

example:
    #!/usr/bin/env bash
    set -euo pipefail
    ./mvnw -B -q -DskipTests dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    java --enable-native-access=ALL-UNNAMED -Dmxc.ffi.path="$MXC_FFI_DIR" \
        -cp "target/classes:$(cat target/cp.txt)" examples/Hello.java
