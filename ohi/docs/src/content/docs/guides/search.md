---
title: Find and inspect ontologies
description: Search ohi.dev, check a publication's files and download the format you need.
---

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

Copy the URL from a search result and pass it to `inspect`:

```sh
ohi inspect https://api.ohi.dev/files/packages/w3c/dcat/3/
```

Under **Files**, you'll find each artifact's filename, format and download URL. For example,
`dcat3.owl.ttl` contains OWL in Turtle (`owl-turtle`). Under **Dependencies**, you'll see
Dublin Core Terms, SKOS and PROV-O.

## Download a file

Use the ontology name and version from the result, with your chosen format:

```sh
ohi get w3c/dcat@3 --format owl-turtle
```

This saves `dcat3.owl.ttl` in your current directory. Add `--to ./ontologies` to save it elsewhere.
Omit `@3` to select the most recently published release.

To record this choice in a project and download its dependencies, follow
[Manage ontology projects](/docs/guides/projects/).
