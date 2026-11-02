# CLI behavior and recovery

## Search results

Search defaults to ohi.dev. To use another catalog, supply both its search endpoint and indexed fields. Supplying only one is an error. Each search returns one page in backend order. Request further pages explicitly. `search_cutoff: true` means hits or the total may be incomplete. An empty successful page differs from a failed request. `ohi search` does not rerank results or traverse pages automatically.

## Publication metadata

`inspect` accepts a publication URL or an ohi.dev reference, `namespace/name[@version]`. Without `@version`, it selects the most recently published release. For URL input, check that the returned `base_url` equals it before choosing an artifact. Retrieve an advertised artifact to read its definitions. Check the artifact's format, origin, roots and reuse terms before selecting its URL. A format combines language and syntax, such as `owl-turtle`.

Retrieval supplies a prepared published file. To convert local edits, use supported local engine tools. A conversion can omit constraints that its target format cannot express.

## Retrieve a selected file

`get` and `add` resolve `namespace/name[@version]` through ohi.dev with an optional `--format`.
Use exact namespace, name and version values from search.
Omitting the version selects the release most recently
published to the Hub. Supported formats are
`owl-turtle`, `owl-rdfxml`, `rdfs-turtle`, `linkml-yaml` and `ossie-yaml`.

Without `--format`, ohi reads `default_format` from `ohi.yaml`, or uses `linkml-yaml` without a declaration.
If the publication lacks the preferred format, it falls back to `linkml-yaml`.
An explicit `--format` requires that format without changing the project default.

Exact URL forms are `get <Hub-file-url>` and `add <base-url> --artifact <file-url>`, without
`--format`. URL-based `add` requires the catalog to resolve the publication's complete reference
and file format to the supplied exact URL pair. Use standalone `get` for unindexed publications or
files that cannot be selected uniquely by reference and format.

A Hub file URL is checked against its owning publication's metadata. The CLI verifies the advertised
SHA-256 before saving. Matching existing regular files are verified and reused without downloading.
Different contents or non-regular targets cause an error without overwrite.

Files use `<root>/<namespace>/<name>/<version>/<name>.<format-suffix>`, such as
`.ohi/w3c/dcat/3/dcat.linkml.yaml`. Use the saved absolute `path` in JSON. See
[downloaded files](https://ohi.dev/docs/reference/project-files/#downloaded-files) for naming and Git-ignore behavior.

All `get` forms use `download_dir` in cwd's `ohi.yaml`, or cwd's `.ohi` without a declaration.
`--to` overrides that root, resolving relative paths from cwd. `get` reads the declaration only
when it needs the default format or directory. Supplying both `--format` and `--to` skips it.
A malformed required declaration or failed inspection produces an error. `get` never searches
parent directories or changes project records.

## Synchronize downloads

`ohi sync [--project <directory>]` installs all files from `ohi.lock.json`, using its exact URLs and
SHA-256 checksums. It updates the lock only when missing or when the declared dependencies differ.
A matching lock needs no catalog or publication-metadata requests. Existing files are verified and
reused, and completed downloads remain after a failure.

With only a lock, sync uses `.ohi/` and creates no declaration. Add `--init` to create `ohi.yaml`
from the lock's direct selections, retaining their versions and formats. New settings are
`default_format: linkml-yaml` and `download_dir: .ohi`. Existing YAML is preserved. If neither record
exists, use `ohi init` or `ohi add`. Downloads require lock version 2.

## Dependencies

Publication `dependencies` contain publication URLs in `base_url`. The list is complete and flat. Inspect those publications, select advertised artifacts and download required files explicitly with `get`, or [sync the complete lock](#synchronize-downloads) when one exists. ohi does not derive download URLs from namespace prefixes or configure an application's loader.

## Project records

`add`, `sync` and `lock` use `--project`, defaulting to cwd, without searching parent directories.
Relative project paths start at cwd. `add` and `sync` install all locked files into that project's
`download_dir` and have no `--to` option.

`init [directory] [--default_format <format>] [--to <directory>]` sets the format and project-relative
download directory for a new declaration. Defaults are `linkml-yaml` and `.ohi`. It preserves an
existing valid declaration and creates no lock or download directory. `add` requires an existing
project directory, validates its records and creates a missing declaration with those defaults.

Edit [`ohi.yaml`](https://ohi.dev/docs/reference/project-files/#ohiyaml) to change defaults,
the project-relative download directory or dependencies. It requires `format_version: 2`,
`default_format`, `download_dir` and `dependencies` containing `ontology`/`format` pairs.
Use `dependencies: []` for an empty project.

Preserve unrelated dependencies. Appending through `add` rewrites YAML comments and formatting.
A repeated addition preserves declaration bytes, rebuilds the lock and installs its files.

After editing dependencies, run `sync` to update the lock and install its files. Use `lock` for
an explicit refresh without downloading. Both preserve existing YAML and pinned versions.
Generate `ohi.lock.json` through the CLI rather than editing it by hand.

When building a lock, ohi resolves declared references using recorded formats without fallback,
then inspects publications and their complete dependency lists. Each dependency
must advertise exactly one artifact matching its direct artifact's format. Missing or ambiguous
matches fail before record writes. An empty declaration produces an empty lock and no downloads.
A lock alone confirms neither downloads nor compatibility between releases.

Keep project records in version control and authored files outside the download directory.
See the [project guide](https://ohi.dev/docs/guides/projects/) for directory changes and LinkML imports.

## Failures and recovery

Catalog and project operations write successful results to stdout and failures to stderr. Network requests do not follow redirects or retry automatically. Preserve HTTP and metadata errors rather than treating them as empty search results. For a redirect, obtain the final resource URL.

Project JSON results and errors report `declaration_created`, `declaration_changed`, `lock_changed`
and completed outputs in `files`. Writes complete in declaration, lock, download order. Completed
earlier writes remain after a later failure. Follow the reported recovery command:

| Failure stage | Recovery after correcting the cause |
| --- | --- |
| Before any record write | Rerun the requested operation |
| Project-record or ignore-file write | Rerun the original command with its original project and selection arguments |
| Project download | Use the reported `ohi sync --project <directory>` command |
| Standalone `get` | Rerun the original command |

For a refused existing target, choose another destination or preserve the file elsewhere before retrying. If cleanup failed, handle the specific incomplete path reported by the error. Do not infer that a failed download rolled back project records.
