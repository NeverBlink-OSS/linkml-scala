---
title: Download your first ontology
description: Find FOAF and download its OWL file in Turtle syntax.
---

We'll download [FOAF](https://ohi.dev/foaf/foaf@2014-01-14), an ontology for people and their
relationships. You'll need [ohi installed](/docs/getting-started/install/).

## Find the ontology

```sh
ohi search foaf
```

Look for `foaf/foaf` at version `2014-01-14`. The result lists its available formats.

## Download it

```sh
ohi get foaf/foaf@2014-01-14 --format owl-turtle --to ontologies
```

This saves `20140114.owl.ttl` in an `ontologies` folder, creating the folder if needed.
`owl-turtle` selects OWL in Turtle syntax. Open the file in a text editor to see its definitions.

To record ontology choices in a project and download their dependencies, continue with
[Manage ontology projects](/docs/guides/projects/).
