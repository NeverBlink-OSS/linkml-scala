---
title: Find and inspect ontologies
description: Search the Hub, check a publication's files and download the format you need.
---

Use `ohi search` to find an ontology, then `ohi inspect` to see its files and dependencies.

## Search by name or topic

```sh
ohi search energy
```

This finds SAREF4ENER, an ontology for energy devices. Each result shows its name, version,
available formats and publication URL. Put multiword queries in quotes, such as `"smart appliances"`.

To browse a particular namespace, use a filter:

```sh
ohi search '*' --filter-by 'namespace:=saref'
```

For more results, request another page. This browses the second page of the catalog, with five
results per page:

```sh
ohi search '*' --per-page 5 --page 2
```

## Inspect a publication

Copy the URL from a search result and pass it to `inspect`:

```sh
ohi inspect https://api.ohi.dev/files/packages/saref/saref4ener/v2.1.1/
```

Under **Files**, you'll find each artifact's filename, format and download URL. For example,
`saref4ener.ttl` contains OWL in Turtle (`owl-turtle`). Under **Dependencies**, you'll see the
SAREF Core publication it uses.

## Download a file

Use the ontology name and version from the result, with your chosen format:

```sh
ohi get saref/saref4ener@v2.1.1 --format owl-turtle
```

This saves `saref4ener.ttl` in your current directory. Add `--to ./ontologies` to save it elsewhere.
Omit `@v2.1.1` to select the most recently published release.

To record this choice in a project and download its dependencies, follow
[Manage project dependencies](/docs/guides/dependencies/).
