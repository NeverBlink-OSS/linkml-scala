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

cat > "$temporary/README.md" <<'MARKDOWN'
# Ohi SDK

The Ohi SDK lets Python and TypeScript applications search publications, inspect metadata, download selected files and save supplied file selections.

Install on Python 3.10 or later:

```sh
python -m pip install ohi-sdk
```

Install for Node 22 or later, or a browser application:

```sh
npm install @neverblink/ohi
```

See the [SDK guide](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/README.md) for usage, browser requirements and API behavior.
MARKDOWN

case "$kind" in
  python)
    cp -R ohi/python "$temporary/package"
    cp LICENSE "$temporary/package/LICENSE"
    cp "$temporary/README.md" "$temporary/package/README.md"
    python - "$temporary/package/pyproject.toml" "$version" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
text = text.replace("[project]\n", '[project]\nreadme = "README.md"\n', 1)
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
    cp ohi/typescript/package.json "$temporary/package/"
    cp -R ohi/typescript/dist "$temporary/package/dist"
    cp LICENSE "$temporary/package/LICENSE"
    cp "$temporary/README.md" "$temporary/package/README.md"
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
