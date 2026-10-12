#!/usr/bin/env bash
# Runs a checksum-pinned google-java-format (AOSP style) over src/.
# usage: gjf.sh check|apply
set -euo pipefail

VERSION=1.37.0
SHA256=834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$here/.tools/google-java-format-$VERSION-all-deps.jar"

if [ ! -f "$jar" ]; then
  mkdir -p "$here/.tools"
  curl -fsSL -o "$jar.tmp" \
    "https://repo1.maven.org/maven2/com/google/googlejavaformat/google-java-format/$VERSION/google-java-format-$VERSION-all-deps.jar"
  echo "$SHA256  $jar.tmp" | shasum -a 256 -c - >/dev/null
  mv "$jar.tmp" "$jar"
fi

files=()
while IFS= read -r f; do files+=("$f"); done < <(find "$here/src" "$here"/examples/*/src -name '*.java' | sort)

case "${1:?usage: gjf.sh check|apply}" in
  apply) java -jar "$jar" --aosp --replace "${files[@]}" ;;
  check)
    bad="$(java -jar "$jar" --aosp --dry-run "${files[@]}")"
    if [ -n "$bad" ]; then
      echo "not formatted (run 'just fmt'):" >&2
      echo "$bad" >&2
      exit 1
    fi
    ;;
  *) echo "usage: gjf.sh check|apply" >&2; exit 2 ;;
esac
