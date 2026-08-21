#!/usr/bin/env bash
#
# Compile and run nativelib/smoke.c against an unpacked release archive, to check that the archive
# is usable from C on this platform.
#
# Kept out of the workflow files because the compiler and the way a program finds a shared library
# at run time differ per platform, and that is easier to read (and to run by hand) here.
#
# Usage: nativelib/smoke.sh <prefix>
#
#   prefix   an unpacked archive, i.e. the directory holding include/ and lib/

set -euo pipefail

if [ "$#" -ne 1 ]; then
  sed -n '2,12p' "$0" >&2
  exit 2
fi

prefix="$(cd "$1" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
echo "building in ${work}" >&2

if [ ! -d "${prefix}/include" ]; then
  echo "error: ${prefix} has no include/ directory" >&2
  exit 1
fi

# Collect candidates with nullglob rather than `ls`: `ls a.so b.dylib` exits non-zero for the
# pattern that does not match even when the other does, which under `set -e` kills the script
# before it compiles anything.
shopt -s nullglob
runtime_libs=("${prefix}"/lib/*.so "${prefix}"/lib/*.dylib "${prefix}"/bin/*.dll)
import_libs=("${prefix}"/lib/*.lib)
shopt -u nullglob

if [ ${#runtime_libs[@]} -eq 0 ]; then
  echo "error: no .so, .dylib or .dll found under ${prefix}" >&2
  exit 1
fi
echo "found $(basename "${runtime_libs[0]}")"

case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN* | Windows_NT)
  if [ ${#import_libs[@]} -eq 0 ]; then
    echo "error: no import library (.lib) under ${prefix}/lib" >&2
    exit 1
  fi
  # _CRT_SECURE_NO_WARNINGS: MSVC deprecates sscanf, which smoke.c uses to read the handle back.
  #
  # cl takes - as well as / for options, and MSYS leaves an argument starting with - alone, so
  # written this way the flags need no escaping. //flag would work too, but only for a flag with
  # no path in it: MSYS passes //I/d/a/... through as-is, and cl then ignores the whole option as
  # unknown. Hence cygpath -m for every path, which gives cl a path it understands (drive letter,
  # forward slashes, so no second round of escaping here).
  cl -nologo -D_CRT_SECURE_NO_WARNINGS "-I$(cygpath -m "${prefix}/include")" \
    "$(cygpath -m "${here}/smoke.c")" "-Fe:$(cygpath -m "${work}/smoke.exe")" \
    -link "$(cygpath -m "${import_libs[0]}")"
  # The loader finds a DLL through PATH.
  PATH="${prefix}/bin:${PATH}" "${work}/smoke.exe"
  ;;
*)
  # An rpath rather than LD_LIBRARY_PATH/DYLD_LIBRARY_PATH: it is baked into the binary, so the
  # program runs from anywhere, and it survives macOS stripping DYLD_* from protected processes.
  cc "${here}/smoke.c" -o "${work}/smoke" \
    -I"${prefix}/include" -L"${prefix}/lib" -llinkml_scala \
    -Wl,-rpath,"${prefix}/lib"
  # Deliberately no library path in the environment: if this runs, the rpath is right.
  (cd / && "${work}/smoke")
  ;;
esac
