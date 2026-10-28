# CLI behavior and recovery

## Search results

Search defaults to ohi.dev. To use another catalog, supply both its search endpoint and indexed fields. Supplying only one is an error. Each search returns one page in backend order. Request further pages explicitly. `search_cutoff: true` means hits or the total may be incomplete. An empty successful page differs from a failed request. ohi does not rerank results or traverse pages automatically.

## Publication metadata

Inspection returns publication metadata. Check that its `base_url` equals the exact requested publication URL before choosing an artifact. Retrieve an advertised artifact to read its definitions. Check the artifact's format, origin, roots and reuse terms before selecting its URL. A format combines language and syntax, such as `owl-turtle`.

Retrieval supplies a prepared published file. To convert local edits, use supported local engine tools. A conversion can omit constraints that its target format cannot express.

## Retrieve a selected file

ohi derives the filename from the artifact URL and returns the saved absolute `path`. Existing targets, including symlinks, are refused. Use separate destination directories for files sharing a basename.

Compare the saved byte length and SHA-256 with the inspected artifact's `size_bytes` and `sha256` before loading it. The CLI does not perform this comparison. On mismatch, preserve the expected and observed values and investigate before using the file. A direct file URL can be retrieved without metadata, but its advertised integrity and dependency requirements remain unchecked.

## Dependencies

Publication `dependencies` contain publication URLs in `base_url`. The list is complete and flat. Inspect those publications, select advertised artifacts and download required files explicitly with `get`. ohi does not derive download URLs from namespace prefixes or configure an application's loader.

## Project records

`--project` defaults to the invocation directory. ohi does not search parent directories. Relative paths, including `--to`, resolve against the invocation directory even when another project directory is selected.

`init` leaves an existing valid declaration unchanged and creates no lock. `add` requires an existing project directory and can create a missing declaration, so initialization is optional for that directory.

The editable `ohi.yaml` requires `format_version: 1` and a `selections` list of exact `base_url` and `url` pairs. Use `selections: []` for an empty project. Preserve unrelated selections. Appending through `add` rewrites YAML comments and formatting. A successful repeat of an existing selection preserves its position and declaration bytes, rebuilds the lock and downloads the file. An existing download target causes failure before record writes.

To replace, reorder or remove selections, edit the declaration and run `lock`. It preserves declaration bytes and downloads nothing. Removing a selection does not delete files. Generate `ohi.lock.json` through the CLI rather than editing it by hand.

Both `add` and `lock` inspect selected publications and their complete dependency lists. Each dependency must advertise exactly one artifact matching its direct artifact's format. Missing or ambiguous matches fail before record writes. `add` downloads only the requested direct artifact. Neither command downloads dependencies. A lock records file choices without confirming downloads or compatibility between releases.

## Failures and recovery

Catalog and project operations write successful results to stdout and failures to stderr. Network requests do not follow redirects or retry automatically. Preserve HTTP and metadata errors rather than treating them as empty search results. For a redirect, obtain the final resource URL.

Project results and errors report `declaration_changed` and `lock_changed`. Writes complete in declaration, lock, download order. Completed earlier writes remain after a later failure. Follow the error's recovery instructions:

| Failure stage | Recovery after correcting the cause |
| --- | --- |
| Before any record write | Rerun the requested operation |
| Lock replacement | Run `lock`, then use `get` if the original request included a download |
| Download after records were written | Use `get` for the requested file |

For an existing target, choose another destination or preserve the file elsewhere before retrying. If cleanup failed, handle the specific incomplete path reported by the error. Do not infer that a failed download rolled back project records.
