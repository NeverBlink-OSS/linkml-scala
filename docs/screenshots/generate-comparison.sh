#!/usr/bin/env bash
#
# Regenerate the native-vs-Python `generate shacl` race GIF (docs/img/generate-race.gif).
#
# Builds the GraalVM native image, sets up a Python 3.14 `linkml` venv, puts both
# `linkml-scala` (native) and `linkml` (python) on PATH, then renders the tmux
# race tape with VHS.
#
# Requirements: vhs, ttyd, ffmpeg, tmux, uv, a JDK + ./mill (bundled), Python 3.14.
#
# Usage: docs/screenshots/generate-comparison.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

for tool in vhs ttyd ffmpeg tmux uv; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Error: '$tool' is not on PATH. See the header of this script for requirements." >&2
    exit 1
  fi
done

echo "==> Building the native image (GraalVM, ~30s)..."
./mill cli.jvm.nativeImage >/dev/null
NATIVE="$ROOT/out/cli/jvm/nativeImage.dest/linkml-scala"
[ -x "$NATIVE" ] || { echo "Error: native image not found at $NATIVE" >&2; exit 1; }

echo "==> Ensuring a Python 3.14 linkml venv..."
VENV="${XDG_CACHE_HOME:-$HOME/.cache}/linkml-scala-docs/venv-linkml"
if [ ! -x "$VENV/bin/linkml" ]; then
  uv venv --python 3.14 "$VENV"
  uv pip install --python "$VENV/bin/python" linkml
fi

# Expose both CLIs under the names the tape types.
BIN_DIR="$(mktemp -d)"
trap 'rm -rf "$BIN_DIR"; tmux kill-server 2>/dev/null || true' EXIT
ln -sf "$NATIVE" "$BIN_DIR/linkml-scala"
ln -sf "$VENV/bin/linkml" "$BIN_DIR/linkml"
export PATH="$BIN_DIR:$PATH"
export LC_ALL=C

# Prime both CLIs once so the recorded run is warm and stable (the first Python
# invocation compiles bytecode and is markedly slower, which would overrun the tape).
echo "==> Warming up..."
( cd docs/examples \
    && linkml-scala generate shacl core.yaml >/dev/null 2>&1 \
    && linkml generate shacl core.yaml >/dev/null 2>&1 ) || true

mkdir -p docs/img
tmux kill-server 2>/dev/null || true
echo "==> Rendering the race GIF..."
vhs docs/screenshots/generate-race.tape

echo "==> Done: docs/img/generate-race.gif"
