#!/usr/bin/env bash
#
# Regenerate the linkml-scala-only documentation GIFs from the .tape files in
# this directory. Reproducible: builds the native CLI, exposes it as a
# `linkml-scala` launcher on PATH, then renders each tape with VHS.
#
# The native-vs-Python comparison (generate-race.tape) needs Python + tmux and
# is handled separately by generate-comparison.sh, so it is skipped here.
#
# Requirements:
#   - vhs   (go install github.com/charmbracelet/vhs@latest)
#   - ttyd  (https://github.com/tsl0922/ttyd, or `snap install ttyd`)
#   - ffmpeg
#   - a JDK and ./mill (bundled); the native image build pulls GraalVM
#
# Usage: docs/screenshots/generate.sh

set -euo pipefail

# Repo root (this script lives in docs/screenshots/).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

for tool in vhs ttyd ffmpeg; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Error: '$tool' is not on PATH. See the header of this script for install hints." >&2
    exit 1
  fi
done

echo "==> Building the native image (GraalVM, ~30s)..."
./mill cli.jvm.nativeImage >/dev/null
NATIVE="$ROOT/out/cli/jvm/nativeImage.dest/linkml-scala"
[ -x "$NATIVE" ] || { echo "Error: native image not found at $NATIVE" >&2; exit 1; }

# Expose the native binary under the name the tapes type.
BIN_DIR="$(mktemp -d)"
trap 'rm -rf "$BIN_DIR"' EXIT
ln -sf "$NATIVE" "$BIN_DIR/linkml-scala"
export PATH="$BIN_DIR:$PATH"

mkdir -p docs/img
for tape in docs/screenshots/*.tape; do
  # The comparison race tape has its own generator (needs Python + tmux).
  [ "$(basename "$tape")" = "generate-race.tape" ] && continue
  echo "==> Rendering $tape"
  vhs "$tape"
done

echo "==> Done. Output written to docs/img/."
