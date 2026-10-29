# Installing ohi

**Ask the user before installing anything.** Never pipe a remote script straight into a shell.
Prefer a version pinned inside the project over a global install, so the whole team and CI agree
on one version.

`ohi` is the CLI. It carries the LinkML engine, which it exposes under `ohi linkml`.

Requirement: **0.17.1 or newer**.

```shell
ohi version     # already installed?
```

## Option 1 — mise, pinned in the project (best default)

Works on Linux, macOS and Windows, records the version in a file you commit, and needs no
`sudo`. Requires [mise](https://mise.jdx.dev/getting-started.html).

```shell
mise use --pin 'ubi:NeverBlink-OSS/linkml-scala[matching=ohi,exe=ohi]'
```

The release carries several binaries, so `matching` picks the `ohi` asset and `exe` names the
installed command. This installs the newest release and writes the exact version it resolved
into `mise.toml`. Everyone who runs `mise install` in that repo then gets byte-identical tooling.

## Option 2 — the install script

For a user-level install on Linux, macOS or WSL. Download and read it first:

```shell
curl -fsSLO https://ohi.dev/install.sh
bash install.sh
```

It installs into `~/.local/bin`, verifies the download against the release's `SHA256SUMS`, checks
build provenance when `gh` is available, and **asks** before touching a shell profile. Useful
variables:

| Variable | Effect |
|---|---|
| `LINKML_SCALA_VERSION` | Install a specific tag instead of the latest |
| `LINKML_SCALA_INSTALL_DIR` | Install somewhere other than `~/.local/bin` |
| `LINKML_SCALA_REQUIRE_CHECKSUM=1` | Refuse a release that publishes no checksums |
| `LINKML_SCALA_MODIFY_PROFILE=1` | Pre-answer the PATH prompt (needed when unattended) |

Sourcing it instead (`. <(curl -sSfL https://ohi.dev/install.sh)`) also puts the directory on the
current shell's PATH, at the cost of not being able to read the script first.

## Option 3 — manual download, verified

Assets are at
<https://github.com/NeverBlink-OSS/linkml-scala/releases/latest>: `ohi-linux-x86_64`,
`-linux-arm64`, `-macos-arm64`, `-macos-x86_64`, `-windows-x86_64.exe`, each also gzipped, plus
`ohi.jar`.

```shell
gh attestation verify ohi-linux-x86_64 --repo NeverBlink-OSS/linkml-scala
chmod +x ohi-linux-x86_64
```

Full verification procedure, including checksums and the Maven and npm channels:
[Verifying downloads](https://linkml.ohi.dev/docs/verifying-downloads/).

## Option 4 — no install at all

- **[Web playground](https://linkml.ohi.dev/playground)** — same engine, in the browser.
  Good for a one-off question or when the user cannot install software.
- **npm** — `npm install @neverblink/linkml` gives the same generators as a zero-dependency ES
  module. Published with SLSA provenance, verifiable via `npm audit signatures`. It is a
  *library*, not a CLI, so it needs a few lines of JavaScript to drive.
- **JVM** — `ohi.jar` runs anywhere with Java 17+:
  `java -jar ohi.jar linkml validate schema.yaml`. The LinkML library is also on Maven Central
  under `eu.neverblink.linkml`.

## In CI

Don't install the binary. Use the action, which bundles the engine as pure Node:

```yaml
- uses: NeverBlink-OSS/linkml-scala-action@v0.14.0
  with:
    files: "schemas/**/*.yaml"
```

See [600-ci.md](600-ci.md).
