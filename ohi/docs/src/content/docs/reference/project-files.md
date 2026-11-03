---
title: Project files
description: The fields and behavior of ohi.yaml and ohi.lock.json.
---

Keep both project files in version control:

| File | Contents |
| --- | --- |
| `ohi.yaml` | Your ontologies, their versions and formats, and project settings. |
| `ohi.lock.json` | Download URLs and checksums for those ontologies and their dependencies. |

For a walkthrough, see [Manage ontology projects](/docs/guides/projects/).

## ohi.yaml

This project uses PROV-O in Turtle format:

```yaml
format_version: 1
default_format: linkml-yaml
download_dir: .ohi
dependencies:
  - ontology: w3c/prov-o@2013
    format: owl-turtle
```

| Field | Meaning |
| --- | --- |
| `format_version` | Project file version. Must be `1`. |
| `default_format` | Preferred format for `get` and `add` when you omit `--format`. |
| `download_dir` | Subfolder of the project directory where ontologies are downloaded. |
| `dependencies` | Ontologies added to the project. Use `[]` for an empty project. |
| `dependencies[].ontology` | Complete `namespace/name@version` reference. |
| `dependencies[].format` | The dependency's [file format](/docs/reference/cli/#ontology-references-and-formats). |

All fields are required. You can include several versions or formats of an ontology, but each
reference and format pair must be unique.

After editing a dependency, run `ohi sync` to update the lock and downloads. Changing
`default_format` affects future `get` and `add` commands, leaving the listed formats unchanged.

Adding a new entry with `ohi add` removes custom comments and resets formatting.

## ohi.lock.json

`ohi add` updates the lock automatically. `ohi sync` uses it to restore downloads and updates it
when the dependencies in `ohi.yaml` change.

| Field | Meaning |
| --- | --- |
| `format_version` | Lock format. Must be `2`. |
| `selections` | Release and file URLs for the ontologies you added directly. |
| `files` | All files to download, including dependencies. |

Each file entry records its release URL (`base_url`), download URL (`url`), `format` and
SHA-256 checksum (`sha256`).

Dependencies use the same format as the ontology you selected. For example, DCAT 3 in Turtle
also requires Turtle files for Dublin Core Terms, SKOS and PROV-O.

Use [`ohi sync`](/docs/reference/cli/#sync) after cloning a project or receiving a shared lockfile.

## Downloaded files

Downloads are grouped by namespace, ontology and version. For example, DCAT 3 in two formats:

```text
.ohi/w3c/dcat/3/dcat.linkml.yaml
.ohi/w3c/dcat/3/dcat.owl.ttl
```

The [format table](/docs/reference/cli/#ontology-references-and-formats) lists filename suffixes.

ohi creates a `.gitignore` inside the download folder to keep downloads out of Git, unless that
file already exists. Edit it if you want to commit the downloads too. Keep files you write
yourself outside this folder. `get --to` leaves ignore files unchanged.
