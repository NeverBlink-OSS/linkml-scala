#!/usr/bin/env bash
#
# Package a native-image shared library build into the layout C, C++ and Rust consumers expect:
#
#   liblinkml-scala-<version>-<platform>/
#     include/*.h          the headers native-image generated
#     lib/liblinkml_scala.*  (bin/ for the Windows DLL, as is conventional there)
#     linkml-scala.pc      pkg-config, so `pkg-config --cflags --libs linkml-scala` works
#     LICENSE
#     README.md
#
# That is what `make install` and `cmake --install` produce, so -I<prefix>/include
# -L<prefix>/lib -llinkml_scala just work.
#
# Usage: nativelib/package.sh <version> <platform> <build-dir> <out-dir>
#
#   version     e.g. 0.13.2 (no leading v)
#   platform    e.g. linux-x86_64, windows-x86_64
#   build-dir   out/nativelib/sharedLibrary.dest, or a downloaded CI artifact
#   out-dir     where to write the archive
#
# Writes <out-dir>/linkml-scala-lib-<platform>.tar.gz, or .zip for Windows platforms.

set -euo pipefail

if [ "$#" -ne 4 ]; then
  sed -n '2,26p' "$0" >&2
  exit 2
fi

version="$1"
platform="$2"
build_dir="$3"
out_dir="$4"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
prefix_name="liblinkml-scala-${version}-${platform}"
staging="$(mktemp -d)"
prefix="${staging}/${prefix_name}"
trap 'rm -rf "$staging"' EXIT

# Windows keeps DLLs in bin/ and the import library in lib/; everywhere else the shared object
# goes in lib/.
case "$platform" in
windows-*) runtime_dir="bin" ;;
*) runtime_dir="lib" ;;
esac

mkdir -p "${prefix}/include" "${prefix}/lib" "${prefix}/${runtime_dir}"

# Sort whatever native-image produced by extension rather than by name: the exact set differs per
# platform (Windows adds an import library, and we would rather not hardcode its name).
found_library=""
for file in "${build_dir}"/*; do
  [ -f "$file" ] || continue
  case "$file" in
  *.h) cp "$file" "${prefix}/include/" ;;
  *.so | *.dylib | *.dll)
    cp "$file" "${prefix}/${runtime_dir}/"
    found_library="$file"
    ;;
  *.lib | *.a) cp "$file" "${prefix}/lib/" ;;
  *) echo "note: ignoring unexpected build output $(basename "$file")" >&2 ;;
  esac
done

if [ -z "$found_library" ]; then
  echo "error: no shared library (.so/.dylib/.dll) found in ${build_dir}" >&2
  exit 1
fi

# A shared library is not executed directly, but 755 is what every distro ships and what
# `cmake --install` sets.
chmod 755 "${prefix}/${runtime_dir}"/*

# Drop the empty one of lib/ and bin/ on Windows-only builds.
rmdir "${prefix}/lib" 2>/dev/null || true
rmdir "${prefix}/${runtime_dir}" 2>/dev/null || true

cp "${repo_root}/LICENSE" "${prefix}/LICENSE"

# ${pcfiledir} keeps this relocatable, so it works wherever the archive is unpacked.
cat >"${prefix}/linkml-scala.pc" <<EOF
prefix=\${pcfiledir}
includedir=\${prefix}/include
libdir=\${prefix}/lib

Name: linkml-scala
Description: LinkML schema validation and multi-format code generation
URL: https://github.com/NeverBlink-OSS/linkml-scala
Version: ${version}
Cflags: -I\${includedir}
Libs: -L\${libdir} -llinkml_scala
EOF

cat >"${prefix}/README.md" <<EOF
# LinkML-Scala shared library ${version} (${platform})

LinkML schema validation and code generation as a native shared library, with a C ABI. Experimental.

The library exports two functions, plus the GraalVM isolate lifecycle functions:

    char* linkml_call(graal_isolatethread_t*, char*);   // JSON request in, JSON response out
    void  linkml_free(graal_isolatethread_t*, char*);   // release a response

Create an isolate with \`graal_create_isolate\`, attach any further threads with
\`graal_attach_thread\`, then send JSON requests. The request is \`const\`; the response is not,
because you own it and must release it with \`linkml_free\`.

The protocol, the full API and a worked example in Python are documented at:

  https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/python_bindings.md

Licensed under the Apache License 2.0. See LICENSE.
EOF

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"

case "$platform" in
windows-*)
  archive="${out_dir}/linkml-scala-lib-${platform}.zip"
  rm -f "$archive"
  (cd "$staging" && zip -q -r -9 "$archive" "$prefix_name")
  ;;
*)
  archive="${out_dir}/linkml-scala-lib-${platform}.tar.gz"
  rm -f "$archive"
  tar -czf "$archive" -C "$staging" "$prefix_name"
  ;;
esac

echo "$archive"
