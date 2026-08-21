#!/usr/bin/env bash
#
# Set up what native-image needs to cross-link the shared library against musl.
#
# We need a musl-flavor gcc and a zlib built against musl.
#
# Usage: sudo nativelib/install-musl-toolchain.sh [zlib-version]

set -euo pipefail

zlib_version="${1:-1.3.2}"

# zlib.net only serves the current release at its root and moves everything else into /fossils/, so
# a pinned version there 404s the moment a new one lands. The GitHub release for a tag never moves.
case "$zlib_version" in
1.3.2) zlib_sha256=bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16 ;;
1.3.1) zlib_sha256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23 ;;
*)
  echo "error: no known checksum for zlib ${zlib_version}. Add one to $0." >&2
  exit 1
  ;;
esac

case "$(uname -m)" in
x86_64) arch=x86_64 ;;
aarch64 | arm64) arch=aarch64 ;;
*)
  echo "error: unsupported architecture $(uname -m)" >&2
  exit 1
  ;;
esac

prefix="${MUSL_PREFIX:-/usr/local/${arch}-linux-musl}"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq musl-tools musl-dev gcc make curl

# native-image insists on the architecture-prefixed name.
ln -sf "$(command -v musl-gcc)" "/usr/local/bin/${arch}-linux-musl-gcc"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
echo "building zlib ${zlib_version} against musl into ${prefix}" >&2
tarball="${work}/zlib.tar.gz"
curl -sSfL -o "$tarball" \
  "https://github.com/madler/zlib/releases/download/v${zlib_version}/zlib-${zlib_version}.tar.gz"
# This ends up linked into a library we publish, so check we got what we expected.
echo "${zlib_sha256}  ${tarball}" | sha256sum -c - >/dev/null
tar xzf "$tarball" -C "$work"
cd "${work}/zlib-${zlib_version}"
CC=musl-gcc ./configure --static --prefix="$prefix" >/dev/null
make -j"$(nproc)" >/dev/null
make install >/dev/null

echo "installed:" >&2
ls -l "/usr/local/bin/${arch}-linux-musl-gcc" "${prefix}/lib/libz.a" >&2
