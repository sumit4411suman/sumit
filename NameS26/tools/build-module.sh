#!/usr/bin/env bash
# Packages ../magisk-module into a deterministic ZIP (same inputs -> same SHA-256).
# Usage: tools/build-module.sh [output.zip]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/dist/NameS26-magisk-module.zip}"
SRC="$ROOT/magisk-module"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# Fixed timestamps + sorted entries make the archive reproducible.
export TZ=UTC
find "$SRC" -type f -exec touch -t 202601010000.00 {} +
( cd "$SRC" && find . -type f | sed 's|^\./||' | LC_ALL=C sort | zip -X -q -D "$OUT" -@ )
echo "module zip: $OUT"
sha256sum "$OUT"
