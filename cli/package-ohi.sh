#!/usr/bin/env bash
# Run from the repository root. Stage SDK metadata and license without changing the checkout.
# Arguments are python|npm, output directory and an optional release version.
set -euo pipefail

kind=$1
mkdir -p "$2"
destination=$(cd "$2" && pwd)
version=${3:-}
temporary=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ohi-package.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

case "$kind" in
  python)
    cp -R ohi/python "$temporary/package"
    cp LICENSE "$temporary/package/LICENSE"
    python - "$temporary/package/pyproject.toml" "$version" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
if sys.argv[2]:
    old = 'version = "0.0.0"'
    if text.count(old) != 1:
        raise SystemExit("Expected one development version in pyproject.toml.")
    text = text.replace(old, f"version = {json.dumps(sys.argv[2])}", 1)
path.write_text(text)
PY
    python -m build --outdir "$destination" "$temporary/package"
    ;;
  npm)
    mkdir "$temporary/package"
    cp ohi/typescript/package.json ohi/typescript/README.md "$temporary/package/"
    cp -R ohi/typescript/dist "$temporary/package/dist"
    cp LICENSE "$temporary/package/LICENSE"
    (
      cd "$temporary/package"
      if [ -n "$version" ]; then
        npm version "$version" --no-git-tag-version --ignore-scripts
      fi
      npm pack --pack-destination "$destination"
    )
    ;;
  *)
    echo "Expected package kind python or npm, received '$kind'." >&2
    exit 1
    ;;
esac
