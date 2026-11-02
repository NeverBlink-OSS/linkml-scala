# ohi CLI

<!-- Last updated: 2026-11-03. -->

ohi lets command-line users find ontology publications, download files, track the publications a project depends on, and run LinkML engine commands.

## Install

In Bash or Zsh, on Linux, macOS or Windows - Git Bash, MSYS2, Cygwin, WSL:

```sh
. <(curl -sSfL https://ohi.dev/install.sh)
ohi version
```

In PowerShell:

```powershell
irm https://ohi.dev/install.ps1 | iex
ohi version
```

The installer downloads the latest GitHub release, verifies its checksum and installs `ohi` in
`~/.local/bin`. Source it again to update. Set `LINKML_SCALA_VERSION` to a release tag to select a
version, or `LINKML_SCALA_INSTALL_DIR` to choose the directory. It adds that directory to the current
shell's `PATH` and asks before changing your shell profile. Set `LINKML_SCALA_REQUIRE_CHECKSUM=1` to
refuse releases that publish no checksum manifest, which is every release up to and including v0.12.0.
Both scripts take the same variables and install to the same place; the PowerShell one updates the
session's `PATH` and asks before changing your PowerShell profile. Read them before running them:
[install.sh](../install.sh), [install.ps1](../install.ps1).

Native releases support Linux and macOS 14 or later on x86_64 and arm64, plus Windows on x86_64.
Download other release assets from [GitHub Releases](https://github.com/NeverBlink-OSS/ohi/releases).
The `ohi.jar` asset runs with Java 17 or later using `java -jar ohi.jar`.
ohi and LinkML use the same release version.
See [download verification](https://linkml.ohi.dev/docs/verifying-downloads/) for checksums and build provenance.

```sh
ohi search provenance
ohi inspect w3c/prov-o@2013 --json
ohi get https://api.ohi.dev/files/packages/w3c/prov-o/2013/prov-o.ttl --to ./ontologies
```

Search uses the ohi.dev catalog. These commands require no ohi project and leave project records
unchanged. The download example saves `ontologies/w3c/prov-o/2013/prov-o.owl.ttl`.

Pass a search hit's `base_url` to `inspect`. Select an entry from `artifacts`, check its `format`, then pass that entry's `url` to `get`.

Inspect or download from ohi.dev using the namespace and name shown by `search`:

```sh
ohi inspect namespace/name@version
ohi get namespace/name
ohi get namespace/name@version --format owl-turtle --to ./ontologies
```

Omit `@version` for the latest published release. Without `--format`, `get` and `add`
use `default_format` from `ohi.yaml`, or `linkml-yaml` without a project.
If the publication lacks the preferred format, they fall back to `linkml-yaml`.
Use `--format` to require a specific format without changing the project default.

## Catalog commands

`search` returns one page. Use `--page` to request another page and `--per-page` to set its size.
A cutoff warning means results or counts may be incomplete. Run `ohi search --help` for filters,
sorting and facets.

Search uses color in a terminal. Use `--color=never` to disable it.

To select another catalog, supply both `--search-url` and `--query-by`. Omitting both uses ohi.dev.
Supplying only one fails.

`inspect` accepts an ontology reference or an exact publication URL. It reports publication metadata,
all artifacts and dependency publication URLs, without requiring a format.

`get` uses `download_dir` from `ohi.yaml` in the current directory, or `.ohi/` there without a
declaration. It does not search parent directories. Use `--to` for a one-off destination, with
relative paths resolved from the current directory. A file is saved under
`<root>/<namespace>/<name>/<version>/<name>.<format-suffix>`, such as
`.ohi/w3c/prov-o/2013/prov-o.owl.ttl`. Different versions and formats have separate paths.

Hub file URLs are checked against their publication metadata. All downloads verify SHA-256
checksums and reuse matching regular files. Different contents cause an error without overwrite.
Success output reports downloaded and verified counts. JSON includes saved absolute paths.
If a failure leaves an incomplete file, the error identifies it for cleanup.

Use `--json` for scripts. Successful results go to stdout with exit status 0. Failures go to stderr
with exit status 1. The result fields are
defined in [SearchResult](../../model/ohi/search.yaml), [Publication](../../model/ohi/package.yaml)
and [DeliveryResult](../../model/ohi/cli/cli-delivery.yaml).

Use `--timeout-ms` to override the default 30000 ms timeout. Download dependency files individually
with `get <file-url>` or [with sync](#synchronize-downloads).

## Project commands

A project is a directory containing [`ohi.yaml`](../../model/ohi/cli/declaration.yaml), which you edit to list ontology references, versions and formats. ohi writes [`ohi.lock.json`](../../model/ohi/lock.yaml), which lists the selected files and their dependency files.

```sh
ohi init ./project --to ontologies
ohi add namespace/name --format owl-turtle --project ./project
ohi add https://api.ohi.dev/files/packages/w3c/prov-o/2013/ --artifact https://api.ohi.dev/files/packages/w3c/prov-o/2013/prov-o.ttl --project ./project
ohi sync --project ./project
```

| Command | Effect |
| --- | --- |
| `init [directory] [--default_format <format>] [--to <directory>]` | Creates the directory and `ohi.yaml` when needed. New declarations default to `linkml-yaml`, `.ohi` and an empty dependency list. Preserves an existing valid declaration. |
| `add <namespace/name[@version]> [--format <format>]` | Records the complete reference and selected format, rebuilds `ohi.lock.json` and installs all locked files. |
| `add <base-url> --artifact <url>` | Adds the reference and format if the catalog resolves them to that exact URL pair, then installs all locked files. |
| `sync [--init]` | Installs locked files, updating the lock when dependencies change. `--init` creates a missing declaration from a shared lock. |
| `lock` | Rebuilds `ohi.lock.json` from `ohi.yaml` without downloading. |

`add` accepts the same ontology references and formats as `get`. Use `--artifact` only with a
publication URL, and omit `--format` for that form. Use standalone `get <file-url>` for files
that cannot be selected through the catalog. Saved references include the version, so `lock`
does not select a newer release.

When `init`, `add` or `sync --init` creates `ohi.yaml`, it reports the project directory, the editable
file and the [project guide](https://ohi.dev/docs/guides/projects/).

`add`, `sync` and `lock` take `--project`, defaulting to the current directory. `add` and `sync` use
the selected project's `download_dir` and have no `--to` option. `init --to ontologies` stores a
project-relative setting, so the example above installs into `project/ontologies/`.
Neither `init` nor `lock` creates the download directory.

To change a dependency, edit `ohi.yaml` and run `sync`:

```yaml
format_version: 2
default_format: linkml-yaml
download_dir: ontologies
dependencies:
  - ontology: w3c/prov-o@2013
    format: owl-turtle
```

Edit `default_format` to change the project's preference for future `get` and `add` commands.
Existing dependency formats remain unchanged. Removing a dependency does not delete its downloaded file.
Adding a new dependency rewrites `ohi.yaml` and drops its comments and formatting.

All four top-level declaration fields are required. `download_dir` must be a nonempty relative path
strictly beneath the project, separate from its records. Editing it changes future downloads without
moving or deleting existing files. Repeating an addition preserves declaration bytes and refreshes
the lock. `add` and `sync` verify matching files and download missing ones. Use `lock` for a refresh
without installation. None of these commands upgrades pinned versions.

For each selection, the lock also lists one artifact from each dependency publication in the same
format. Lock construction fails when a dependency has no artifact or several artifacts in that
format. `add` and `sync` install the complete lock, leaving obsolete or unrelated local files in place.

Keep both project records in Git and user-authored ontology files outside the download directory.
A nonempty installation creates `.gitignore` in the configured directory containing `*` and a newline
if absent. Existing ignore contents are preserved. This policy also applies to `get` without `--to`.
Explicit `get --to` destinations receive no ignore edits. Edit the directory's ignore policy to
version downloads. Existing tracked files remain tracked.

`--json` prints a [ProjectResult](../../model/ohi/cli/project-result.yaml) on stdout and uses
[ProjectError](../../model/ohi/cli/project-error.yaml) on stderr. Both report completed files.
When a command fails after writing some files, follow its recovery command. Completed records
and downloads remain in place.

## Synchronize downloads

After cloning a project or receiving `ohi.lock.json`, run:

```sh
ohi sync
```

Sync downloads missing files and verifies existing files against the lock's checksums. It rebuilds
the lock only when missing or when the dependencies in `ohi.yaml` differ. A reused lock needs no
catalog or publication-metadata requests. Changing project defaults or dependency order keeps it unchanged.

With only a lock, downloads go into `.ohi/`. To also create an editable `ohi.yaml`, run
`ohi sync --init`. The new declaration retains direct selections, versions and formats, with
`default_format: linkml-yaml` and `download_dir: .ohi`. Existing YAML is preserved. If neither
record exists, start with `ohi init` or `ohi add`.

Downloads preserve the published bytes and embedded imports. A LinkML schema at the project root
can import `.ohi/w3c/dcat/3/dcat.linkml` when that file is installed under the default root. Use the
configured directory and a path relative to the importing schema. Ohi does not rewrite imports or
generate import maps, so installation alone does not guarantee offline loading.

Rebuild older project locks with `ohi lock --project <directory>` first.

## LinkML commands

```sh
ohi linkml validate model.yaml
ohi linkml to json-schema model.yaml --to model.schema.json
```

Replace `model.yaml` with your LinkML schema. Successful validation reports `Schema is valid.` and exits 0. Validation exits 1 for errors or fatal issues, and for warnings when `--strict` is used. To validate application data, use a separate validator with the generated JSON Schema or SHACL definitions.

The generation example writes JSON Schema and replaces an existing destination file.

`ohi linkml --help` lists every supported command. Use a complete command's `--help`, such as `ohi linkml to scala --help`, to see its options. Generation requires a target, such as `scala` or `json-schema`.

Run `ohi version` to check the installed version.

## Local builds

Build and run a JVM assembly or the native `ohi` executable from the repository root. Running the assembly requires Java 17 or later.

```sh
./mill cli.ohi.jvm.assembly
java -jar out/cli/ohi/jvm/assembly.dest/out.jar --help
./mill cli.ohi.jvm.nativeImage
out/cli/ohi/jvm/nativeImage.dest/ohi --help
```

Native builds require the host's C/C++ compiler. To run directly from source, use
`./mill cli.ohi.jvm.run <command>`.
