#!/bin/bash

# This script installs the latest cli release from the GitHub repository
# Let's start by fetching the latest release information
REPO_BASE="NeverBlink-OSS/linkml-scala"
REPO="https://api.github.com/repos/$REPO_BASE/releases/latest"
RELEASE_INFO=$(curl -fsSL "$REPO") || {
  echo "Error: Could not fetch latest release info from $REPO";
  return 1 2>/dev/null || exit 1;
}

# And let's get the tag name of the latest release
TAG_NAME=$(echo $RELEASE_INFO | grep -o '"tag_name": "[^"]*' | sed 's/"tag_name": "//')
if [ -z "$TAG_NAME" ]; then
  echo "Error: Could not fetch the latest release tag name.";
  return 1 2>/dev/null || exit 1;
fi

# Check the operating system and architecture
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)
# Map the architecture to the appropriate binary name
case $OS in
  linux) BINARY_NAME="linkml-scala-linux" ;;
  darwin) BINARY_NAME="linkml-scala-macos" ;;
  *) echo "Unsupported operating system: $OS"; exit 1 ;;
esac
# Append the architecture to the binary name but yell for cases we don't support
case $ARCH in
  x86_64) ARCH_NAME="-x86_64"
        # check that os not darwin here
        if [ "$OS" = "darwin" ]; then
          echo "Unsupported architecture: $ARCH on macOS"
          return 1 2>/dev/null || exit 1
        fi;;
  aarch64)
    ARCH_NAME="-arm64" ;;
  arm64)
      ARCH_NAME="-arm64" ;;
  *) echo "Unsupported architecture: $ARCH"
    return 1 2>/dev/null || exit 1
    ;;
esac

# Check the installation directory
INSTALL_DIR="$HOME/.local/bin"
if [ ! -d "$INSTALL_DIR" ]; then
  echo "Creating installation directory: $INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"
fi

# Download the binary
DOWNLOAD_URL="https://github.com/$REPO_BASE/releases/download/$TAG_NAME/$BINARY_NAME$ARCH_NAME.gz"
echo "Downloading $BINARY_NAME from $DOWNLOAD_URL"
curl -fL "$DOWNLOAD_URL" -o "$INSTALL_DIR/linkml-scala.gz" || {
  echo "Error: Failed to download the binary from $DOWNLOAD_URL";
  return 1 2>/dev/null || exit 1;
}
gzip -d -f "$INSTALL_DIR/linkml-scala.gz" || {
  echo "Error: Failed to decompress $INSTALL_DIR/linkml-scala.gz"
  return 1 2>/dev/null || exit 1
}
CONTENT=$(wc -c < "$INSTALL_DIR/linkml-scala" 2>/dev/null || echo 0)
if [ "${CONTENT:-0}" -lt 500 ]; then
  echo "Error: Failed to download the binary from $DOWNLOAD_URL";
  return 1 2>/dev/null || exit 1;
fi
chmod +x "$INSTALL_DIR/linkml-scala"

# Ensure that the installation directory is in the PATH
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "Adding $INSTALL_DIR to PATH"
  # Add the binary to the PATH for the current session
  export PATH="$PATH:$INSTALL_DIR"
  # Check shell config file from bash and zsh
  if expr "$SHELL" : '.*zsh' >/dev/null; then
    grep -q "PATH=.*$INSTALL_DIR" "$HOME/.zshrc" 2>/dev/null || echo "export PATH=\"\$PATH:$INSTALL_DIR\"" >> "$HOME/.zshrc"
  elif expr "$SHELL" : '.*bash' >/dev/null; then
    grep -q "PATH=.*$INSTALL_DIR" "$HOME/.bashrc" 2>/dev/null || echo "export PATH=\"\$PATH:$INSTALL_DIR\"" >> "$HOME/.bashrc"
  fi
fi

# Link the binary to the installation directory
if [ -f "$INSTALL_DIR/linkml-scala" ]; then
  echo "Installation successful! You can now use LinkML Scala CLI by running 'linkml-scala'."
else
  echo "Error: Installation failed. The binary was not found in the installation directory."
  exit 1
fi