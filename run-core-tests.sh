#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/.build"
rm -rf "$OUT"
mkdir -p "$OUT"
mapfile -t SOURCES < <(find "$ROOT/core/src/main/kotlin" "$ROOT/core/src/test/kotlin" -name '*.kt' | sort)
kotlinc "${SOURCES[@]}" -include-runtime -d "$OUT/core-tests.jar"
java -jar "$OUT/core-tests.jar"
