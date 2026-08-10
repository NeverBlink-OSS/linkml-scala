#!/usr/bin/env bash
#
# Installs the linkml-scala CLI from GitHub Releases.
#
# Source it, so that the install directory lands on the PATH of the current shell:
#
#   . <(curl -sSfL https://raw.githubusercontent.com/NeverBlink-OSS/linkml-scala/main/cli/install.sh)
#
# Or download it, read it, and run it - PATH changes then only apply to new shells:
#
#   curl -fsSLO https://raw.githubusercontent.com/NeverBlink-OSS/linkml-scala/main/cli/install.sh
#   bash install.sh
#
# Environment variables:
#   LINKML_SCALA_VERSION           Release tag to install, e.g. v0.12.0 (default: latest).
#   LINKML_SCALA_INSTALL_DIR       Where to put the binary (default: $HOME/.local/bin).
#   LINKML_SCALA_REQUIRE_CHECKSUM  Set to 1 to abort if the release publishes no SHA256SUMS.
#   LINKML_SCALA_REQUIRE_ATTESTATION  Set to 1 to abort unless the build provenance verifies.
#                                  Needs the GitHub CLI, recent enough to read the current
#                                  Sigstore trust root.
#   LINKML_SCALA_MODIFY_PROFILE    Preset the "add to PATH?" answer (1/yes to accept,
#                                  anything else to decline). Skips the prompt.
#
# The script never calls `exit`, so it is also safe to `source`.
#
# If the install directory is not on your PATH, the script asks before adding it to your
# shell profile. It never edits anything without an explicit yes, and never prompts when
# there is no terminal to prompt on (CI, `curl | bash`) - there it just prints the line.

_linkml_scala_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1"
  else
    shasum -a 256 "$1"
  fi
}

_linkml_scala_install() {
  local repo_base="NeverBlink-OSS/linkml-scala"
  local install_dir="${LINKML_SCALA_INSTALL_DIR:-$HOME/.local/bin}"
  local tag="${LINKML_SCALA_VERSION:-}"
  local tool os arch arch_suffix binary_name asset release_url tmp_dir
  local latest_url expected actual attestation profile path_line answer

  for tool in curl gzip mktemp; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "Error: '$tool' is required but was not found." >&2
      return 1
    fi
  done

  # macOS ships shasum rather than GNU coreutils' sha256sum.
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    echo "Error: neither 'sha256sum' nor 'shasum' is available, so the download" >&2
    echo "cannot be verified. Refusing to install." >&2
    return 1
  fi

  # Resolve the release tag unless one was pinned. This follows the redirect that
  # /releases/latest issues, rather than asking api.github.com, which rate-limits
  # unauthenticated callers to 60 requests per hour per IP.
  if [ -z "$tag" ]; then
    latest_url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
      "https://github.com/$repo_base/releases/latest") || {
      echo "Error: could not reach GitHub to determine the latest release." >&2
      echo "Set LINKML_SCALA_VERSION to install a specific release instead." >&2
      return 1
    }
    case "$latest_url" in
      */releases/tag/?*) tag="${latest_url##*/tag/}" ;;
      *)
        echo "Error: could not determine the latest release tag of $repo_base." >&2
        echo "Set LINKML_SCALA_VERSION to install a specific release instead." >&2
        return 1
        ;;
    esac
  fi

  os=$(uname -s | tr '[:upper:]' '[:lower:]')
  arch=$(uname -m)

  case "$os" in
    linux) binary_name="linkml-scala-linux" ;;
    darwin) binary_name="linkml-scala-macos" ;;
    *)
      echo "Error: unsupported operating system '$os'." >&2
      echo "On Windows, install with mise instead:  mise use 'ubi:$repo_base'" >&2
      return 1
      ;;
  esac

  case "$arch" in
    x86_64 | amd64) arch_suffix="-x86_64" ;;
    aarch64 | arm64) arch_suffix="-arm64" ;;
    *)
      echo "Error: unsupported architecture '$arch'." >&2
      return 1
      ;;
  esac

  asset="$binary_name$arch_suffix"
  release_url="https://github.com/$repo_base/releases/download/$tag"

  tmp_dir=$(mktemp -d) || {
    echo "Error: could not create a temporary directory." >&2
    return 1
  }

  echo "Installing linkml-scala $tag ($asset)"

  if ! curl -fL --progress-bar "$release_url/$asset.gz" -o "$tmp_dir/$asset.gz"; then
    echo "Error: failed to download $release_url/$asset.gz" >&2
    rm -rf "$tmp_dir"
    return 1
  fi

  # Verify the download against the release's checksum manifest. A 404 here is an
  # expected, handled case for older releases, so curl stays quiet about it (no -S).
  if curl -fsL "$release_url/SHA256SUMS" -o "$tmp_dir/SHA256SUMS"; then
    expected=$(awk -v want="$asset.gz" \
      '{ name = $2; sub(/^\*/, "", name); if (name == want) { print $1; exit } }' \
      "$tmp_dir/SHA256SUMS")
    if [ -z "$expected" ]; then
      echo "Error: SHA256SUMS for $tag does not list $asset.gz." >&2
      rm -rf "$tmp_dir"
      return 1
    fi
    actual=$(_linkml_scala_sha256 "$tmp_dir/$asset.gz" | cut -d ' ' -f 1)
    if [ "$expected" != "$actual" ]; then
      echo "Error: checksum mismatch for $asset.gz - refusing to install." >&2
      echo "  expected: $expected" >&2
      echo "  actual:   $actual" >&2
      rm -rf "$tmp_dir"
      return 1
    fi
    echo "Checksum verified against SHA256SUMS."
  else
    # Releases up to and including v0.12.0 predate the SHA256SUMS asset.
    # TODO: make this a hard failure once the oldest supported release publishes one.
    if [ "${LINKML_SCALA_REQUIRE_CHECKSUM:-}" = "1" ]; then
      echo "Error: release $tag publishes no SHA256SUMS and" >&2
      echo "LINKML_SCALA_REQUIRE_CHECKSUM=1 was set. Refusing to install." >&2
      rm -rf "$tmp_dir"
      return 1
    fi
    echo "Warning: release $tag publishes no SHA256SUMS, so the download could not be" >&2
    echo "verified. Install a newer release to get a verifiable download." >&2
  fi

  # Best effort: Sigstore-backed proof that these bytes came out of the release workflow.
  # gh exits 1 for every problem alike - a missing attestation, a stale gh that cannot
  # parse the Sigstore trust root, no auth, a genuine mismatch - so branch on what it
  # actually said. Reporting all of those as "no provenance" hides the ones that matter.
  if command -v gh >/dev/null 2>&1; then
    if attestation=$(gh attestation verify "$tmp_dir/$asset.gz" --repo "$repo_base" 2>&1); then
      echo "Build provenance verified with gh attestation."
    else
      case "$attestation" in
        *"HTTP 404"* | *"no attestations found"*)
          # Releases up to and including v0.12.0 predate the attestation.
          echo "Note: release $tag publishes no build provenance (older releases have none)." >&2
          ;;
        *)
          echo "Warning: the build provenance of $tag could not be checked. gh reported:" >&2
          printf '%s\n' "$attestation" | sed '/^[[:space:]]*$/d; s/^/  /' >&2
          echo "This is most often an out-of-date gh that cannot read the current Sigstore" >&2
          echo "trust root; upgrading gh usually fixes it. The checksum above still matched," >&2
          echo "but to check the provenance itself, re-run:" >&2
          echo "  gh attestation verify <file> --repo $repo_base" >&2
          ;;
      esac
      if [ "${LINKML_SCALA_REQUIRE_ATTESTATION:-}" = "1" ]; then
        echo "Error: LINKML_SCALA_REQUIRE_ATTESTATION=1 was set, so an unverified build" >&2
        echo "provenance is fatal. Refusing to install." >&2
        rm -rf "$tmp_dir"
        return 1
      fi
    fi
  elif [ "${LINKML_SCALA_REQUIRE_ATTESTATION:-}" = "1" ]; then
    echo "Error: LINKML_SCALA_REQUIRE_ATTESTATION=1 was set but 'gh' was not found, so the" >&2
    echo "build provenance cannot be checked. Refusing to install." >&2
    rm -rf "$tmp_dir"
    return 1
  fi

  if ! gzip -d -f "$tmp_dir/$asset.gz"; then
    echo "Error: failed to decompress $asset.gz" >&2
    rm -rf "$tmp_dir"
    return 1
  fi

  if ! mkdir -p "$install_dir"; then
    echo "Error: could not create the install directory $install_dir." >&2
    rm -rf "$tmp_dir"
    return 1
  fi

  chmod +x "$tmp_dir/$asset"
  if ! mv -f "$tmp_dir/$asset" "$install_dir/linkml-scala"; then
    echo "Error: could not install into $install_dir." >&2
    rm -rf "$tmp_dir"
    return 1
  fi
  rm -rf "$tmp_dir"

  echo "Installed linkml-scala $tag to $install_dir/linkml-scala"

  case ":$PATH:" in
    *":$install_dir:"*)
      echo "Run 'linkml-scala --help' to get started."
      return 0
      ;;
  esac

  # Usable straight away if this script was sourced; a no-op when it was executed.
  export PATH="$PATH:$install_dir"

  case "${SHELL:-}" in
    *zsh) profile="$HOME/.zshrc" ;;
    *bash) profile="$HOME/.bashrc" ;;
    *) profile="" ;; # Unknown shell - we would only guess wrong.
  esac
  path_line="export PATH=\"\$PATH:$install_dir\""
  answer=""

  # Only ever edit a profile with permission. Honour a preset answer for unattended
  # installs, and never prompt when there is no terminal to prompt on - piping this
  # script into a shell, or running it in CI, must not block.
  if [ -n "$profile" ] && [ -n "${LINKML_SCALA_MODIFY_PROFILE:-}" ]; then
    answer="$LINKML_SCALA_MODIFY_PROFILE"
  elif [ -n "$profile" ] && [ -t 0 ]; then
    printf '\n%s is not on your PATH.\nAdd it to %s? [y/N] ' "$install_dir" "$profile"
    read -r answer
  fi

  case "$answer" in
    1 | y | Y | yes | YES)
      if [ -f "$profile" ] && grep -qF "$install_dir" "$profile"; then
        echo "$profile already refers to $install_dir - leaving it unchanged."
      elif printf '\n# Added by the linkml-scala installer\n%s\n' "$path_line" >>"$profile"; then
        echo "Added $install_dir to your PATH in $profile."
        echo "Open a new shell, or run 'source $profile', to pick it up."
      else
        echo "Error: could not write to $profile. Add this line yourself:" >&2
        echo "  $path_line" >&2
        return 1
      fi
      ;;
    *)
      echo
      echo "Leaving your shell profile alone. To put $install_dir on your PATH"
      echo "permanently, add this line to it:"
      echo
      echo "  $path_line"
      echo
      echo "Or call the binary by its full path:"
      echo "  $install_dir/linkml-scala --help"
      ;;
  esac
}

_linkml_scala_install "$@"
