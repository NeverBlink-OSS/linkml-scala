---
title: Manage ontology projects
description: Record your ontology choices and download the same files and dependencies again.
---

<!-- Last updated: 2026-11-03. -->

Use `ohi add` to keep track of the ontologies your project uses. Here we'll add
[DCAT 3](https://ohi.dev/o/w3c/dcat@3), which describes data catalogs, and download its dependencies.

## Create a project

```sh
ohi init city-catalog --default_format owl-turtle
cd city-catalog
```

This creates a `city-catalog` folder with your project settings in `ohi.yaml`.
The `--default_format owl-turtle` option makes OWL in Turtle your preferred format.
Leave out that option to use LinkML YAML instead.

## Add an ontology

```sh
ohi add w3c/dcat@3
```

You'll find DCAT at `.ohi/w3c/dcat/3/dcat.owl.ttl`. Its dependencies, Dublin Core Terms,
SKOS and PROV-O, are downloaded into their own folders under `.ohi/`.
Ohi records your choice in `ohi.yaml` and the exact files and checksums in `ohi.lock.json`.

Omit `@3` to choose the most recently published release on ohi.dev. Ohi saves the resolved version
in `ohi.yaml` so you can keep using it until you decide to change it.

Here, `add` uses the format we chose during initialization. You can request another format with
`--format`, such as `--format linkml-yaml`. When you leave out `--format`, ohi tries the project
default first, then LinkML YAML if that default isn't available.

Run `ohi add` again to add more ontologies. Each time, ohi checks existing downloads against the
Hub's SHA-256 checksums. It reuses files that match and downloads any missing project files.
If a local file has been edited or damaged, ohi stops and shows its path. Move that file elsewhere
if you want to keep it, or delete it, then rerun the command to download a fresh copy.

## Restore a project on another machine

Keep `ohi.yaml` and `ohi.lock.json` in version control. After cloning the project on another
machine, run this from the folder containing those files:

```sh
ohi sync
```

Ohi downloads the files recorded in `ohi.lock.json` into the project's download folder, `.ohi/`
by default. Files already present are checked against the saved checksums and reused.

If someone shares only `ohi.lock.json`, the same command downloads its files into `.ohi/`.
To also create an editable `ohi.yaml` from that lock, run:

```sh
ohi sync --init
```

Ohi creates `.ohi/.gitignore` to keep downloads out of Git. Edit that file if you want to commit
the downloads too. Keep schemas you write yourself outside `.ohi/`.

## Change an ontology's format

To switch DCAT from Turtle to LinkML YAML, first check which formats DCAT 3 provides:

```sh
ohi inspect w3c/dcat@3
```

Under **Files**, look for the **Format** entries. The output includes `linkml-yaml`, so you can
use that value in your project. Open `ohi.yaml` and change DCAT's `format` from `owl-turtle`
to `linkml-yaml`:

```yaml
format_version: 2
default_format: owl-turtle
download_dir: .ohi
dependencies:
  - ontology: w3c/dcat@3
    format: linkml-yaml
```

The `format` under DCAT applies to that dependency. `default_format` remains the preference for
future `add` and `get` commands. You can edit it too if you want future downloads to prefer LinkML.

Apply your change:

```sh
ohi sync
```

This updates `ohi.lock.json` and downloads DCAT and its dependencies in LinkML YAML. The earlier
Turtle files stay in place.

To change versions, follow the same steps with the release you want. For example, run
`ohi inspect w3c/dcat@2`, check its formats, then replace `ontology: w3c/dcat@3` with
`ontology: w3c/dcat@2` in `ohi.yaml` and run `ohi sync`. Each version has its own download folder.

## Choose another download directory

You can choose a custom download folder when you create a project:

```sh
ohi init another-catalog --to ontologies
```

This saves `download_dir: ontologies` in `ohi.yaml`, so downloads go into
`another-catalog/ontologies/`.

## Import a downloaded LinkML schema

After downloading the LinkML format, you can import it from a schema next to `ohi.yaml`.
With the default `.ohi/` download folder, use:

```yaml
imports:
  - .ohi/w3c/dcat/3/dcat.linkml
```

If you chose `ontologies/` as your download folder, use `ontologies/w3c/dcat/3/dcat.linkml` instead.
Import paths start from the schema containing them. A schema in `models/` would therefore use
`../.ohi/w3c/dcat/3/dcat.linkml` for the default download folder.

A downloaded schema may import dependencies using other paths or URLs. If ohi LinkML cannot
find one, use an [import map](https://linkml.ohi.dev/docs/import-maps/) to point that import to
the downloaded file.
