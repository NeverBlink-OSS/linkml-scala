---
title: Find and inspect ontologies
description: Search ohi.dev, check a publication's files and download the format you need.
---

<!-- Last updated: 2026-11-03. -->

Use `ohi search` to find an ontology, then `ohi inspect` to see its files and dependencies.

## Search by name or topic

```sh
ohi search dcat
```

This finds DCAT, an ontology for data catalogs. Each result shows its name, version,
available formats and publication URL. Put multiword queries in quotes, such as `"data catalog"`.

To browse a particular namespace, use a filter:

```sh
ohi search '*' --filter-by 'namespace:=w3c'
```

For more results, request another page. This browses the second page of the catalog, with five
results per page:

```sh
ohi search '*' --per-page 5 --page 2
```

## Inspect a publication

Use the ontology name and version from a search result:

```sh
ohi inspect w3c/dcat@3
```

Omit `@3` to inspect the most recently published release. You can also pass the publication URL
shown by `search`.

Under **Files**, you'll find each artifact's filename, format and download URL. For example,
`dcat3.owl.ttl` contains OWL in Turtle (`owl-turtle`). Under **Dependencies**, you'll see
Dublin Core Terms, SKOS and PROV-O.

## Download a file

Use the ontology name and version from the result, with your chosen format:

```sh
ohi get w3c/dcat@3 --format owl-turtle
```

Without a project in the current directory, this saves `.ohi/w3c/dcat/3/dcat.owl.ttl`.
If `ohi.yaml` is present there, `get` uses its `download_dir`. Add `--to ./ontologies` to save
`ontologies/w3c/dcat/3/dcat.owl.ttl` instead. Omit `@3` to select the most recently published release.

To record this choice in a project and download its dependencies, follow
[Manage ontology projects](/docs/guides/projects/).
