---
title: Download your first ontology
description: Find FOAF and download its OWL file in Turtle syntax.
---

We'll download [FOAF](https://ohi.dev/o/foaf/foaf@2014-01-14), an ontology for people and their
relationships. You'll need [ohi installed](/docs/getting-started/install/).

## Find the ontology

```sh
ohi search foaf
```

Look for `foaf/foaf` at version `2014-01-14`. The result lists its available formats.

## Download it

```sh
ohi get foaf/foaf@2014-01-14 --format owl-turtle
```

Without a project in the current directory, this saves `.ohi/foaf/foaf/2014-01-14/foaf.owl.ttl`.
If `ohi.yaml` is present there, it uses that project's `download_dir` instead of `.ohi`.
`owl-turtle` selects OWL in Turtle syntax. Open the file in a text editor to see its definitions.
Use `--to ontologies` for a separate destination, such as `ontologies/foaf/foaf/2014-01-14/foaf.owl.ttl`.

To record ontology choices in a project and download their dependencies, continue with
[Manage ontology projects](/docs/guides/projects/).
