---
title: Project files
description: The fields and behavior of ohi.yaml and ohi.lock.json.
---

An ohi project uses two files. Keep both in version control.

| File | Contents | Updated by |
| --- | --- | --- |
| `ohi.yaml` | The publications and files you selected. | You, `ohi init` and `ohi add`. |
| `ohi.lock.json` | Exact files and checksums, including dependencies. | `ohi add` and `ohi lock`. |

For a walkthrough, see [Manage ontology projects](/docs/guides/dependencies/).

## ohi.yaml

This example selects PROV-O as OWL in Turtle:

```yaml
format_version: 1
selections:
  - base_url: https://api.ohi.dev/files/packages/w3c/prov-o/2013/
    url: https://api.ohi.dev/files/packages/w3c/prov-o/2013/prov-o.ttl
```

| Field | Meaning |
| --- | --- |
| `format_version` | Declaration format version. Must be `1`. |
| `selections` | List of directly selected files. Use `[]` for an empty project. |
| `selections[].base_url` | Exact publication URL shown by `search`. |
| `selections[].url` | Exact URL of a file listed by `inspect` for that publication. |

All fields are required. Copy URLs exactly, including trailing slashes. Each pair of URLs
can appear only once in the selection list.

Edit this file to remove or replace a selection, then run `ohi lock`. Removing a selection
does not delete downloaded files. Adding a selection with `ohi add` rewrites this file,
so custom comments and formatting are not preserved.

## ohi.lock.json

The lock records the files needed to reproduce your selections. For the PROV-O selection above,
which has no dependencies:

```json
{
  "format_version": 2,
  "selections": [
    {
      "base_url": "https://api.ohi.dev/files/packages/w3c/prov-o/2013/",
      "url": "https://api.ohi.dev/files/packages/w3c/prov-o/2013/prov-o.ttl"
    }
  ],
  "files": [
    {
      "base_url": "https://api.ohi.dev/files/packages/w3c/prov-o/2013/",
      "url": "https://api.ohi.dev/files/packages/w3c/prov-o/2013/prov-o.ttl",
      "format": "owl-turtle",
      "sha256": "7d203989f67b38bca572253942acc5a1bf24ce3ccfece16f072dcb4be2b79a96"
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `format_version` | Current CLI lock format. Must be `2` for downloads. |
| `selections` | Direct selections from `ohi.yaml`, with the same URL fields. |
| `files` | Selected files and their dependency files. |
| `files[].base_url` | Publication that contains the file. |
| `files[].url` | Exact download URL. |
| `files[].format` | [File format](/docs/reference/cli/#ontology-references-and-formats), such as `owl-turtle`. |
| `files[].sha256` | SHA-256 checksum used to verify the file. |

All fields shown are required in a version-2 lock. Every selection has a matching entry in
`files`. Download directories are not stored in either project file.

Update the lock with `ohi add` or `ohi lock`. To replace an older version-1 project lock,
run `ohi lock` in its project directory.

## Dependency files

The lock includes each selected file and one file in the same format from each required
dependency publication. For example, selecting DCAT 3 in `owl-turtle` also records
the Turtle files for Dublin Core Terms, SKOS and PROV-O in the lock.

Each dependency must provide exactly one file in that format. If a dependency has none or more
than one, lock creation fails. Rebuilding the lock keeps the exact publications in `ohi.yaml`.

Download the recorded files with:

```sh
ohi get --lock ohi.lock.json --to ./ontologies
```

ohi verifies each file against its recorded checksum and reuses files that already match.
