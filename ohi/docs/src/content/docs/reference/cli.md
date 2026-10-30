---
title: CLI commands
description: Arguments, options and defaults for the ohi CLI.
---

Run `ohi --help` for the command overview, or `ohi <command> --help` for a command's options.

| Command | Purpose |
| --- | --- |
| [`search`](#search) | Find ontologies by name or topic. |
| [`inspect`](#inspect) | Show a publication's files and dependencies. |
| [`get`](#get) | Download a file or all files in a lock. |
| [`init`](#init) | Create `ohi.yaml`. |
| [`add`](#add) | Download a file and record it in a project. |
| [`lock`](#lock) | Rebuild `ohi.lock.json` from `ohi.yaml`. |
| [`linkml`](#linkml) | Work with local schemas. |
| [`version`](#version) | Print the installed version. |

## Shared options

| Option | Available on | Effect |
| --- | --- | --- |
| `-h`, `--help` | All commands | Show help. |
| `--json` | `search`, `inspect`, `get`, `init`, `add`, `lock` | Write JSON output. |
| `--timeout-ms <number>` | `search`, `inspect`, `get` | Positive request timeout in milliseconds. Default `30000`. |

## Ontology references and formats

`get` and `add` accept `namespace/name[@version]` from ohi.dev, such as
`w3c/prov-o@2013`. Without `@version`, they select the most recently published release.

Without `--format`, ohi prefers the format shared by all selections in `ohi.yaml`, falling back
to `linkml-yaml`. `get` reads `ohi.yaml` in the current directory. `add` uses its `--project` directory.

Use `--format` to require one of these values:

| Value | Language | Syntax |
| --- | --- | --- |
| `owl-turtle` | OWL | Turtle |
| `owl-rdfxml` | OWL | RDF/XML |
| `rdfs-turtle` | RDFS | Turtle |
| `linkml-yaml` | LinkML | YAML |
| `ossie-yaml` | Apache Ossie | YAML |

The selected publication must contain exactly one file in that format. If it has several,
choose a file by URL. ohi does not fall back to an older publication.

## search

`ohi search <query> [options]`

Returns one page of matches from ohi.dev. Quote multiword queries. Use `'*'` to browse.

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
| `--search-url <url>` | Complete search endpoint for another catalog. Requires `--query-by`. |
| `--query-by <fields>` | Comma-separated search fields for that catalog. Requires `--search-url`. |
| `--color <auto\|never>` | Color terminal output. Default `auto`. |

Use `--page 2` with the same query and options to request the next page. A cutoff warning means
the results or counts may be incomplete.

## inspect

`ohi inspect <release-url> [options]`

Shows a publication's metadata, files, formats and dependency URLs. Use the URL shown by `search`.

```sh
ohi inspect https://api.ohi.dev/files/packages/w3c/prov-o/2013/
```

Inspection does not save ontology files. See [Shared options](#shared-options) for JSON output
and the request timeout.

## get

```text
ohi get <namespace/name[@version]> [--format <format>] [options]
ohi get <file-url> [options]
ohi get --lock <file> [options]
```

Downloads one file, or every file recorded in a lock, including dependencies.

```sh
ohi get w3c/prov-o@2013 --format owl-turtle --to ./ontologies
```

| Option | Effect |
| --- | --- |
| `--format <format>` | Require a format for an ontology reference. Omit with a file URL or `--lock`. |
| `--to <directory>` | Download directory. Default is the current directory. Created if needed. |
| `--lock <file>` | Download from a version-2 lock. Use without a reference or file URL. |

Files keep the filename from their download URL. Existing files are never overwritten.
With `--lock`, ohi checks SHA-256 checksums and reuses existing regular files that match.
A failed download stops the command and leaves completed files in place.

See [Find and inspect ontologies](/docs/guides/search/) for a search-to-download example.

## init

`ohi init [directory] [--json]`

Creates `ohi.yaml` with an empty selection list. The directory defaults to the current directory.
An explicit directory is created with its parents when needed. An existing valid `ohi.yaml`
is kept unchanged.

```sh
ohi init my-project
```

## add

```text
ohi add <namespace/name[@version]> [--format <format>] [options]
ohi add <release-url> --artifact <file-url> [options]
```

Records a selection in `ohi.yaml`, rebuilds `ohi.lock.json` and downloads the selected file.
The lock includes dependencies, but `add` downloads only the selected file.

```sh
ohi add w3c/prov-o@2013 --format owl-turtle --to ./ontologies
```

| Option | Effect |
| --- | --- |
| `--format <format>` | Require a format for an ontology reference. Omit with a release URL. |
| `--artifact <file-url>` | Required with a release URL. Select a file listed by `inspect`. |
| `--project <directory>` | Existing project directory. Default is the current directory. |
| `--to <directory>` | Download directory. Default is the current directory. Created if needed. |

`add` creates `ohi.yaml` if needed. Existing downloaded files are never overwritten.
`--project` and `--to` are independent. Relative paths are resolved from the directory where
you run the command.

To download the dependencies too, use `ohi get --lock ohi.lock.json --to ./ontologies`.
See [Manage ontology projects](/docs/guides/projects/) for the complete workflow.

## lock

`ohi lock [--project <directory>] [--json]`

Rebuilds `ohi.lock.json` from `ohi.yaml` without downloading files. It keeps the exact
publications selected in `ohi.yaml`, including when newer releases are available.

```sh
ohi lock --project ./my-project
```

`--project` defaults to the current directory. See [Project files](/docs/reference/project-files/)
for the file formats and how dependency files are selected.

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
