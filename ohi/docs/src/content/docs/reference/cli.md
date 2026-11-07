---
title: CLI commands
description: Arguments, options and defaults for the ohi CLI.
---

Run `ohi --help` for the command overview, or `ohi <command> --help` for a command's options.

| Command | Purpose |
| --- | --- |
| [`search`](#search) | Find ontologies by name or topic. |
| [`inspect`](#inspect) | Show a release's files and dependencies. |
| [`get`](#get) | Download one ontology file. |
| [`init`](#init) | Create `ohi.yaml`. |
| [`add`](#add) | Add an ontology to a project and download its dependencies. |
| [`sync`](#sync) | Download and verify project ontologies. |
| [`lock`](#lock) | Rebuild `ohi.lock.json` from `ohi.yaml`. |
| [`linkml`](#linkml) | Work with local schemas. |
| [`version`](#version) | Print the installed version. |

## Shared options

| Option | Available on | Effect |
| --- | --- | --- |
| `-h`, `--help` | All commands | Show help. |
| `--json` | `search`, `inspect`, `get`, `init`, `add`, `sync`, `lock` | Write JSON output. |
| `--timeout-ms <number>` | `search`, `inspect`, `get`, `sync` | Positive request timeout in milliseconds. Default `30000`. |

## Ontology references and formats

`inspect`, `get` and `add` accept `namespace/name[@version]` from ohi.dev, such as
`w3c/prov-o@2013`. Without `@version`, they select the latest version.

`get` and `add` use `default_format` from `ohi.yaml`, or `linkml-yaml` if there is no project.
If the preferred format is unavailable, they try `linkml-yaml`. Use `--format` to require a
specific format:

| Value | Language | Syntax | Filename suffix |
| --- | --- | --- | --- |
| `owl-turtle` | OWL | Turtle | `.owl.ttl` |
| `owl-rdfxml` | OWL | RDF/XML | `.owl` |
| `rdfs-turtle` | RDFS | Turtle | `.rdfs.ttl` |
| `linkml-yaml` | LinkML | YAML | `.linkml.yaml` |
| `ossie-yaml` | Apache Ossie | YAML | `.ossie.yaml` |

The release must provide exactly one file in the requested format. ohi does not search older
releases when a format is unavailable.

## search

`ohi search <query> [options]`

Returns one page of matches from ohi.dev, one per ontology at its latest version. Quote multiword
queries. Use `'*'` to browse.

```sh
ohi search 'energy' --filter-by 'namespace:=saref' --per-page 5
```

| Option | Effect |
| --- | --- |
| `--page <number>` | Page to return, starting at `1`. Default `1`. |
| `--per-page <number>` | Matches per page, from `1` to `250`. Default `10`. |
| `--filter-by <expression>` | Filter matches, for example `namespace:=saref`. |
| `--sort-by <expression>` | Order matches, for example `published_at:desc`. |
| `--facet-by <fields>` | Count values for comma-separated fields, such as `namespace`. |
| `--max-facet-values <number>` | Maximum values per facet, at least `1`. Omit to use the search service's default. |
| `--all-releases` | Search every release instead of each ontology's latest version. |
| `--search-url <url>` | Complete search endpoint for another catalog, ending in `/search/collections/packages/documents/search`. Requires `--query-by`. |
| `--query-by <fields>` | Comma-separated search fields for that catalog. Requires `--search-url`. |
| `--color <auto\|never>` | Color terminal output. Default `auto`. |

Use `--page 2` with the same query and options to request the next page. A cutoff warning means
the results or counts may be incomplete.

## inspect

```text
ohi inspect <namespace/name[@version]> [options]
ohi inspect <release-url> [options]
```

Shows a release's metadata, all files, formats and dependency URLs. For a reference, terminal
output also lists the ontology's versions, latest first.

```sh
ohi inspect w3c/prov-o@2013
ohi inspect https://api.ohi.dev/files/packages/w3c/prov-o/2013/
```

## get

```text
ohi get <namespace/name[@version]> [--format <format>] [options]
ohi get <file-url> [options]
```

Downloads one ontology file from a reference or Hub file URL.

```sh
ohi get w3c/prov-o@2013 --format owl-turtle
```

| Option | Effect |
| --- | --- |
| `--format <format>` | Require a format for an ontology reference. Omit with a file URL. |
| `--to <directory>` | Choose a download folder for this command. Relative paths start at the current directory. |

Downloads go into `download_dir` from the current directory's `ohi.yaml`, or `.ohi/` if there
is no project. With the default folder, this example saves `.ohi/w3c/prov-o/2013/prov-o.owl.ttl`.
See [Downloaded files](/docs/reference/project-files/#downloaded-files) for naming and Git settings.

Files are verified using SHA-256 checksums. Matching local files are reused. If a local file has
changed, ohi stops and reports its path. Move or delete that file, then retry.

## init

`ohi init [directory] [--default_format <format>] [--to <directory>] [--json]`

Creates [`ohi.yaml`](/docs/reference/project-files/#ohiyaml) with an empty dependency list.
The project directory defaults to the current directory.

```sh
ohi init my-project --to ontologies
```

| Option | Effect |
| --- | --- |
| `--default_format <format>` | Set the preferred format. Default `linkml-yaml`. |
| `--to <directory>` | Set the download subfolder. Default `.ohi`. |

The example sets the download folder to `my-project/ontologies/`. You can edit both settings
later in `ohi.yaml`.

## add

```text
ohi add <namespace/name[@version]> [--format <format>] [options]
ohi add <release-url> --artifact <file-url> [options]
```

Adds the ontology's version and format to `ohi.yaml`, updates `ohi.lock.json`, and downloads
the project's ontologies and dependencies.

```sh
ohi add w3c/prov-o@2013 --format owl-turtle
```

| Option | Effect |
| --- | --- |
| `--format <format>` | Require a format for an ontology reference. Omit with a release URL. |
| `--artifact <file-url>` | Required with a release URL. Select a file listed by `inspect`. |
| `--project <directory>` | Existing project directory. Default is the current directory. |

Downloads use the project's `download_dir`. If needed, `add` creates `ohi.yaml` with
`default_format: linkml-yaml` and `download_dir: .ohi`.

For the URL form, use the release and file URLs shown by `ohi inspect <reference>`.

See [Manage ontology projects](/docs/guides/projects/) for the complete workflow.

## sync

`ohi sync [--project <directory>] [--init] [--locked | --frozen] [options]`

Downloads missing files and verifies existing files using `ohi.lock.json`. If you changed the
dependencies in `ohi.yaml`, or there is no lock yet, sync updates the lock before downloading.
If a local file has changed, move or delete the reported file, then rerun `ohi sync`.

```sh
ohi sync --project ./my-project
```

| Option | Effect |
| --- | --- |
| `--project <directory>` | Directory containing `ohi.yaml`, `ohi.lock.json`, or both. Default is the current directory. |
| `--init` | Create `ohi.yaml` from a shared lock if it is missing. |
| `--locked` | Fail instead of updating `ohi.lock.json` when it is missing or doesn't match `ohi.yaml`. |
| `--frozen` | Install `ohi.lock.json` as it is, without checking it against `ohi.yaml`. Fails if the lock is missing. |
| `--color <auto\|never>` | Color terminal output. Default `auto`. |

Downloads use `download_dir` from `ohi.yaml`. With only a lockfile, they go into `.ohi/`.
A new `ohi.yaml` keeps the selected versions and formats, with `default_format: linkml-yaml`
and `download_dir: .ohi`. If neither project file exists, start with `ohi init` or `ohi add`.
Use `--locked` in CI to make sure the committed lock matches `ohi.yaml`.

## lock

`ohi lock [--project <directory>] [--check] [--json]`

Rebuilds `ohi.lock.json` using the versions and formats in `ohi.yaml`, without downloading files.

```sh
ohi lock --project ./my-project
```

| Option                  | Effect                                                                                                                                      |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `--project <directory>` | Directory containing `ohi.yaml`. Default is the current directory.                                                                          |
| `--check`               | Only check that `ohi.lock.json` matches the dependencies in `ohi.yaml`. Fails if it doesn't or if it is missing. Does not change any files. |

## linkml

Runs LinkML schema validation, conversion and generation commands. Use the complete command's
help for its arguments and options:

```sh
ohi linkml --help
ohi linkml validate --help
ohi linkml to json-schema --help
```

## version

```sh
ohi version
```

Prints the installed ohi and component versions. `--format <terminal|json>` selects the output
format. Default is `terminal`.
