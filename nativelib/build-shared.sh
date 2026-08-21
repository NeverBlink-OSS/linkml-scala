#!/usr/bin/env bash
#
# Build the shared library for both Linux libc flavours we publish wheels for.
#
#   glibc  Runs native-image inside a manylinux2014 container. There is no native-image flag for
#          "target an older glibc", so linking against an old one is the only way to lower the
#          minimum version of libc.
#
#   musl   Runs native-image on this machine, cross-linking against musl. No container: musl is a
#          compilation target here, not a build environment. Needs the toolchain set up by
#          install-musl-toolchain.sh.
#
# Usage: nativelib/build-shared.sh <libc> <graalvm-home> <out-dir> <classpath-file> [options...]
#
#   libc            glibc or musl
#   graalvm-home    the GraalVM providing native-image
#   out-dir         where to write liblinkml_scala.* and the generated C headers
#   classpath-file  a file holding the image classpath, ':'-separated
#   options...      the rest is passed to native-image as-is

set -euo pipefail

if [ "$#" -lt 4 ]; then
  sed -n '2,29p' "$0" >&2
  exit 2
fi

libc="$1"
graalvm_home="$2"
out_dir="$3"
classpath_file="$4"
shift 4

case "$libc" in
glibc | musl) ;;
*)
  echo "error: unknown libc '$libc', expected glibc or musl" >&2
  exit 2
  ;;
esac

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="$(mkdir -p "$out_dir" && cd "$out_dir" && pwd)"
classpath="$(cat "$classpath_file")"

case "$(uname -m)" in
x86_64) arch=x86_64 ;;
aarch64 | arm64) arch=aarch64 ;;
*)
  echo "error: unsupported architecture $(uname -m)" >&2
  exit 1
  ;;
esac

case "$libc" in
glibc)
  image="quay.io/pypa/manylinux2014_${arch}"

  # Mount stuff for the build: GraalVM, the repo (read-write, because of out/)
  # and any classpath entry from elsewhere (coursier cache).
  mounts=(-v "${repo_root}:${repo_root}" -v "${graalvm_home}:${graalvm_home}:ro")
  while IFS= read -r entry; do
    case "$entry" in
    "${repo_root}"/* | "") continue ;;
    esac
    mounts+=(-v "${entry}:${entry}:ro")
  done <<<"$(printf '%s' "$classpath" | tr ':' '\n' | sort -u)"

  echo "building against glibc in ${image}" >&2
  exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    "${mounts[@]}" \
    -w "$repo_root" \
    "$image" \
    "${graalvm_home}/bin/native-image" --shared "$@" \
    "-H:Path=${out_dir}" -H:Name=liblinkml_scala -cp "$classpath"
  ;;

musl)
  # GraalVM ships the static JDK libraries a musl link needs for x64 only (lib/static/linux-amd64/
  # musl), and native-image looks for a compiler called x86_64-linux-musl-gcc whatever it runs on.
  if [ "$arch" != "x86_64" ]; then
    echo "error: GraalVM cannot build against musl on ${arch}, only on x86_64" >&2
    exit 1
  fi

  compiler="${arch}-linux-musl-gcc"
  if ! command -v "$compiler" >/dev/null; then
    echo "error: $compiler is not on PATH. Run nativelib/install-musl-toolchain.sh first." >&2
    exit 1
  fi

  prefix="${MUSL_PREFIX:-/usr/local/${arch}-linux-musl}"
  if [ ! -f "${prefix}/lib/libz.a" ]; then
    echo "error: no musl zlib at ${prefix}/lib/libz.a." \
      "Run nativelib/install-musl-toolchain.sh first." >&2
    exit 1
  fi
  # -L on the linker command rather than LIBRARY_PATH in the environment: musl-gcc honours
  # LIBRARY_PATH, but native-image builds a clean environment for the compiler it spawns, so an
  # exported one never arrives and the link fails with "cannot find -lz".
  echo "building against musl with ${compiler}" >&2
  exec "${graalvm_home}/bin/native-image" --shared --libc=musl "$@" \
    "-H:NativeLinkerOption=-L${prefix}/lib" \
    "-H:Path=${out_dir}" -H:Name=liblinkml_scala -cp "$classpath"
  ;;
esac
