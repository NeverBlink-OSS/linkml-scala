---
title: Manage ontology projects
description: Record your ontology choices and download the same files and dependencies again.
---

Use `ohi add` to keep track of the ontologies your project uses. Here we'll add
[DCAT 3](https://ohi.dev/w3c/dcat@3), which describes data catalogs, and download its dependencies.

## Create a project

```sh
ohi init city-catalog
cd city-catalog
```

This creates `ohi.yaml`, where your ontology choices are saved. For an existing project, run
`ohi init` inside its directory instead.

## Add an ontology

```sh
ohi add w3c/dcat@3 --format owl-turtle --to ontologies
```

You'll get `ontologies/dcat3.owl.ttl`. Your selection is saved in `ohi.yaml`, and ohi creates
`ohi.lock.json` with the exact files and checksums for DCAT and its dependencies, Dublin Core Terms,
SKOS and PROV-O.

Omit `@3` to choose the most recently published release on ohi.dev. The saved selection still points
to that exact publication, so later commands won't silently switch versions.

## Download the dependencies

```sh
ohi get --lock ohi.lock.json --to ontologies
```

This downloads `dublin_core_terms.owl.ttl`, `skos.owl.ttl` and `prov-o.ttl`, and reuses the
`dcat3.owl.ttl` you already have. ohi checks all four files against the checksums in the lock.

Keep `ohi.yaml` and `ohi.lock.json` in version control. After cloning the project on another machine,
run the same command to retrieve the same files.

## Change your selections

To add another ontology, run `ohi add` again with its name and format.

To remove or replace a selection, edit `ohi.yaml`. Each entry pairs a publication's `base_url`
with the selected file's `url`:

```yaml
format_version: 1
selections:
  - base_url: https://api.ohi.dev/files/packages/w3c/dcat/3/
    url: https://api.ohi.dev/files/packages/w3c/dcat/3/dcat3.owl.ttl
```

Remove the entry, or replace both URLs with ones from
[`ohi inspect`](/docs/guides/search/#inspect-a-publication). If you remove the last entry, use
`selections: []`. Then rebuild the lock:

```sh
ohi lock
```

This updates the lock without downloading files. When changing versions, download into a fresh
directory to keep the old files:

```sh
ohi get --lock ohi.lock.json --to ontologies-updated
```

Removing a selection leaves its previously downloaded files in place.
