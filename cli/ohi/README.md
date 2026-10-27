# Ohi CLI

Ohi lets command-line users find ontology publications, download files, track the publications a project depends on, and run LinkML engine commands.

## Install

On Linux or macOS, run this in Bash or Zsh:

```sh
LINKML_SCALA_TOOL=ohi LINKML_SCALA_REQUIRE_CHECKSUM=1 . <(curl -sSfL https://raw.githubusercontent.com/NeverBlink-OSS/linkml-scala/main/cli/install.sh)
ohi version
```

The installer downloads the latest GitHub release, verifies its checksum and installs `ohi` in
`~/.local/bin`. Source it again to update. Set `LINKML_SCALA_VERSION` to a release tag to select a
version, or `LINKML_SCALA_INSTALL_DIR` to choose the directory. It adds that directory to the current
shell's `PATH` and asks before changing your shell profile.

Native releases support Linux and macOS 14 or later on x86_64 and arm64, plus Windows on x86_64.
Download other release assets from [GitHub Releases](https://github.com/NeverBlink-OSS/linkml-scala/releases).
On Windows, download `ohi-windows-x86_64.exe` and `SHA256SUMS` from the same release. Compare
`(Get-FileHash .\ohi-windows-x86_64.exe -Algorithm SHA256).Hash` in PowerShell with that asset's
entry in `SHA256SUMS`, then rename it to `ohi.exe` and place it on your `PATH`.
The `ohi.jar` asset runs with Java 17 or later using `java -jar ohi.jar`.
Ohi and LinkML use the same release version.
See [download verification](../../docs/verifying_downloads.md) for checksums and build provenance.

```sh
ohi search provenance
ohi inspect https://hub.example/publications/prov-o-2013 --json
ohi get https://hub.example/files/prov-o.ttl --to ./ontologies
```

Search uses the ohi.dev catalog. The inspection and file addresses above are illustrative.
These commands require no Ohi project and do not read or write `ohi.yaml` or a lockfile.

Pass a search hit's `base_url` to `inspect`. Select an entry from `artifacts`, check its `format`, then pass that entry's `url` to `get`.

## Catalog commands

`search` returns one page. Use `--page` to request another page and `--per-page` to set its size.
A cutoff warning means results or counts may be incomplete. Run `ohi search --help` for filters,
sorting and facets.

Search uses color in a terminal. Use `--color=never` to disable it.

To select another catalog, supply both `--search-url` and `--query-by`. Omitting both uses ohi.dev.
Supplying only one fails.

`inspect` retrieves the exact publication URL supplied. It reports publication metadata, artifacts and dependency publication URLs.

`get` saves the file under its URL filename in the `--to` directory, creating the directory if
needed. Existing files are not overwritten. Move the existing file or choose another directory
before retrying. The result reports the saved path. If a failure leaves an incomplete file,
the error tells you which file to remove.

Use `--json` for scripts. Successful results go to stdout with exit status 0. Failures go to stderr
with exit status 1. Argument errors can remain text even with `--json`. The result fields are
defined in [SearchResult](../../ohi/model/schema/search.yaml), [Publication](../../ohi/model/schema/package.yaml)
and [DeliveryResult](schema/cli-delivery.yaml).

Use `--timeout-ms` to override the default 30000 ms timeout. Download dependency files separately
with `get`.

## Project commands

A project is a directory with two files. You edit [`ohi.yaml`](schema/declaration.yaml), which lists the publications the project depends on and, for each, the artifact it uses. Ohi writes [`ohi.lock.json`](../../ohi/model/schema/lock.yaml), which lists the files those selections resolve to, including dependencies.

```sh
ohi init ./project
ohi add https://hub.example/publications/prov-o-2013 --artifact https://hub.example/files/prov-o.ttl --to ./ontologies --project ./project
ohi lock --project ./project
```

| Command | Effect |
| --- | --- |
| `init [directory]` | Creates the directory if needed and an `ohi.yaml` with no selections. Keeps an existing `ohi.yaml`. |
| `add <base-url> --artifact <url> --to <directory>` | Adds the publication and artifact to `ohi.yaml`, rebuilds `ohi.lock.json` and downloads the artifact into `--to`. |
| `lock` | Rebuilds `ohi.lock.json` from `ohi.yaml`. Downloads nothing. |

`add` and `lock` take `--project`, which defaults to the current directory.

To reorder, replace or remove selections, edit `ohi.yaml` and run `lock`:

```yaml
format_version: 1
selections:
  - base_url: https://hub.example/publications/prov-o-2013
    url: https://hub.example/files/prov-o.ttl
```

Removing a selection does not delete its downloaded file. Adding a new selection rewrites `ohi.yaml` and drops its comments and formatting.

For each selection, the lock also lists one artifact from each dependency of the selected publication, in the format of the selected artifact. `add` and `lock` fail when a dependency has no artifact or several artifacts in that format. Ohi does not download dependency artifacts.

`--json` prints a [ProjectResult](schema/project-result.yaml) on stdout or a [ProjectError](schema/project-error.yaml) on stderr. When a command fails after writing some files, its error message lists the commands that finish the work.

## LinkML commands

```sh
ohi linkml validate model.yaml
ohi linkml generate json-schema model.yaml --to model.schema.json
```

Replace `model.yaml` with your LinkML schema. Successful validation reports `Schema is valid.` and exits 0. Validation exits 1 for errors or fatal issues, and for warnings when `--strict` is used. To validate application data, use a separate validator with the generated JSON Schema or SHACL definitions.

The generation example writes JSON Schema and replaces an existing destination file.

`ohi linkml --help` lists every supported command. Use a complete command's `--help`, such as `ohi linkml generate scala --help`, to see its options. Generation requires a target, such as `scala` or `json-schema`.

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
