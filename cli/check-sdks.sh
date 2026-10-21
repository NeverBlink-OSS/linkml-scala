#!/usr/bin/env bash
# Run from the repository root with Python, Node and Chrome available.
set -euo pipefail

temporary=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ohi-sdk.XXXXXX")
trap 'rm -rf "$temporary"' EXIT
publication="$PWD/ohi/examples/sensors-package.json"

python -m venv "$temporary/build"
"$temporary/build/bin/python" -m pip install --quiet build twine
PATH="$temporary/build/bin:$PATH" bash cli/package-ohi.sh python "$temporary/distributions"
"$temporary/build/bin/python" -m twine check "$temporary"/distributions/*

python -m venv "$temporary/consumer"
"$temporary/consumer/bin/python" -m pip install --quiet "$temporary"/distributions/*.whl
"$temporary/consumer/bin/python" -m unittest discover -s ohi/python/tests

npm --prefix ohi/typescript ci
npm --prefix ohi/typescript test
npm --prefix ohi/typescript run test:browser
bash cli/package-ohi.sh npm "$temporary"
mkdir "$temporary/node-consumer"
(
  cd "$temporary/node-consumer"
  npm init --yes >/dev/null
  npm install "$temporary"/neverblink-ohi-*.tgz
  node --input-type=module - "$publication" <<'JS'
    import assert from "node:assert/strict";
    import { readFile, writeFile } from "node:fs/promises";
    import { decodePublication } from "@neverblink/ohi";
    import { readLock } from "@neverblink/ohi/node";
    const publication = decodePublication(await readFile(process.argv[2], "utf8"));
    assert.equal(publication.namespace, "example");
    assert.equal(publication.name, "sensors");
    assert.equal(publication.version, "1.0.0");
    const lock = '{"format_version":1,"selections":[],"files":[]}';
    const expected = { format_version: 1, selections: [], files: [] };
    await writeFile("ohi.lock.json", lock);
    assert.deepEqual(await readLock("ohi.lock.json"), expected);
JS
)
