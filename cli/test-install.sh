#!/usr/bin/env bash
# Exercises the installer against synthetic releases without network or profile writes.
set -eu

installer=$(cd "$(dirname "$0")" && pwd)/install.sh
test_root=$(mktemp -d "${TMPDIR:-/tmp}/linkml-install-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/release"

case "$(uname -s)" in
  Linux) platform=linux ;;
  Darwin) platform=macos ;;
  *) echo "Unsupported test platform" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) platform="$platform-x86_64" ;;
  aarch64 | arm64) platform="$platform-arm64" ;;
  *) echo "Unsupported test architecture" >&2; exit 1 ;;
esac

for name in linkml-scala ohi; do
  printf '#!/bin/sh\nprintf "fixture %s\\n"\n' "$name" | gzip > "$test_root/release/$name-$platform.gz"
done
(
  cd "$test_root/release"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum ./*.gz
  else
    shasum -a 256 ./*.gz
  fi
) | sed 's| \./| |' > "$test_root/release/SHA256SUMS"

# Only the release URLs below are served. Every other request fails locally.
cat > "$test_root/bin/curl" <<'SH'
#!/bin/sh
output=
url=
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) output=$2; shift 2 ;;
    -w) shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done
printf '%s\n' "$url" >> "$TEST_REQUESTS"
case "$url" in
  https://github.com/NeverBlink-OSS/linkml-scala/releases/latest)
    printf 'https://github.com/NeverBlink-OSS/linkml-scala/releases/tag/v0.0.1'
    ;;
  https://github.com/NeverBlink-OSS/linkml-scala/releases/download/v0.0.1/*)
    file=${url##*/}
    [ -f "$TEST_RELEASE/$file" ] || exit 22
    cp "$TEST_RELEASE/$file" "$output"
    ;;
  *) echo "Unexpected fixture URL: $url" >&2; exit 22 ;;
esac
SH
cat > "$test_root/bin/gh" <<'SH'
#!/bin/sh
echo 'no attestations found' >&2
exit 1
SH
chmod +x "$test_root/bin/curl" "$test_root/bin/gh"

new_case() {
  case_name=$1
  case_dir="$test_root/$case_name"
  mkdir -p "$case_dir"
  cp -R "$test_root/release" "$case_dir/release"
  : > "$case_dir/requests"
}

fail() {
  echo "FAIL: $case_name: $*" >&2
  cat "$case_dir/output" >&2
  exit 1
}

run_case() {
  expected_status=$1
  shift
  actual_status=0
  env PATH="$test_root/bin:/usr/bin:/bin:/usr/sbin:/sbin" INSTALLER="$installer" \
    TEST_RELEASE="$case_dir/release" TEST_REQUESTS="$case_dir/requests" \
    LINKML_SCALA_TOOL= LINKML_SCALA_VERSION= \
    LINKML_SCALA_INSTALL_DIR="$case_dir/install" LINKML_SCALA_MODIFY_PROFILE=0 \
    LINKML_SCALA_REQUIRE_CHECKSUM=1 LINKML_SCALA_REQUIRE_ATTESTATION=0 \
    "$@" > "$case_dir/output" 2>&1 || actual_status=$?
  [ "$actual_status" -eq "$expected_status" ] ||
    fail "expected status $expected_status, got $actual_status"
}

assert_installed() {
  name=$1
  [ "$("$case_dir/install/$name")" = "fixture $name" ] || fail "payload did not run"
  [ "$(ls "$case_dir/install")" = "$name" ] || fail "unexpected installed file"
}

assert_refused() {
  grep -Fq "$1" "$case_dir/output" || fail "missing error: $1"
  [ ! -e "$case_dir/install" ] || fail "failed install wrote its destination"
}

passed() {
  echo "PASS: $case_name"
}

new_case default_executed
run_case 0 bash "$installer"
assert_installed linkml-scala
grep -Fq 'Checksum verified against SHA256SUMS.' "$case_dir/output" || fail "checksum not verified"
passed

for shell in bash zsh; do
  if ! command -v "$shell" >/dev/null 2>&1; then
    echo "SKIP: ohi_sourced_$shell ($shell unavailable)"
    continue
  fi
  new_case "ohi_sourced_$shell"
  run_case 0 "$(command -v "$shell")" -c 'set -- linkml-scala ignored; LINKML_SCALA_TOOL=ohi . "$INSTALLER"; command -v ohi'
  assert_installed ohi
  grep -Fxq "$case_dir/install/ohi" "$case_dir/output" || fail "source did not update PATH"
  passed
done

new_case invalid_selector_survives_source
run_case 1 bash -c 'LINKML_SCALA_TOOL=ohi-cli . "$INSTALLER"; rc=$?; echo caller-alive; exit "$rc"'
assert_refused "LINKML_SCALA_TOOL must be linkml-scala or ohi, not 'ohi-cli'."
grep -Fxq caller-alive "$case_dir/output" || fail "source terminated caller"
[ ! -s "$case_dir/requests" ] || fail "invalid selector requested a release"
passed

new_case checksum_missing_selected_asset
grep -v 'ohi-' "$case_dir/release/SHA256SUMS" > "$case_dir/release/remaining"
mv "$case_dir/release/remaining" "$case_dir/release/SHA256SUMS"
run_case 1 env LINKML_SCALA_TOOL=ohi bash "$installer"
assert_refused "does not list ohi-$platform.gz"
passed

new_case checksum_mismatch
printf '#!/bin/sh\nexit 0\n' | gzip > "$case_dir/release/ohi-$platform.gz"
run_case 1 env LINKML_SCALA_TOOL=ohi bash "$installer"
assert_refused 'checksum mismatch'
passed
