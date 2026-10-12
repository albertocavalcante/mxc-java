#!/usr/bin/env bash
# Fails if Native.java binds a C function the native library does not export.
# MXC's C ABI is unstable, so run this whenever MXC_REF changes.
set -euo pipefail

dir="${1:?usage: abi-check.sh <dir containing libmxc_ffi>}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$here/src/main/java/io/github/albertocavalcante/mxc/internal/Native.java"

lib=""
for c in libmxc_ffi.dylib libmxc_ffi.so mxc_ffi.dll; do
  [ -f "$dir/$c" ] && lib="$dir/$c" && break
done
[ -n "$lib" ] || { echo "abi-check: no libmxc_ffi in $dir" >&2; exit 1; }

exported="$(nm -g "$lib" 2>/dev/null | awk '{print $NF}' | sed 's/^_//' | grep '^mxc_' | sort -u)"
declared="$(grep -oE 'fn\("mxc_[a-z0-9_]+"' "$src" | sed 's/fn("//; s/"//' | sort -u)"

missing="$(comm -23 <(echo "$declared") <(echo "$exported"))"
if [ -n "$missing" ]; then
  echo "abi-check: bound but not exported by $lib:" >&2
  echo "$missing" >&2
  exit 1
fi
echo "abi-check: ok ($(echo "$declared" | wc -l | tr -d ' ') symbols present)"
