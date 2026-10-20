#!/usr/bin/env bash
# Run from the repository root. Build and exercise both native CLI executables.
set -euo pipefail

mill=./mill
suffix=
platform="${RUNNER_OS:-$(uname -s)}"
if [ "$platform" = Windows ]; then
  mill=./mill.bat
  suffix=.exe
fi

"$mill" --no-server cli.jvm.nativeImage
"$mill" --no-server cli.ohi.jvm.nativeImage
linkml="./out/cli/jvm/nativeImage.dest/linkml-scala$suffix"
ohi="./out/cli/ohi/jvm/nativeImage.dest/ohi$suffix"

output=$("$linkml" validate tests/resources/models/basic/model.yaml)
echo "$output"
grep -Fq 'Schema is valid.' <<< "$output"
python cli/check-ohi.py "$ohi"

if [ "$platform" = macOS ] || [ "$platform" = Darwin ]; then
  for binary in "$linkml" "$ohi"; do
    target=$(otool -l "$binary" | grep -A4 LC_BUILD_VERSION)
    echo "$target"
    grep -q 'minos 14.0' <<< "$target"
  done
fi
