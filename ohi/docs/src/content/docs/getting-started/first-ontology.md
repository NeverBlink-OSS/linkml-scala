---
title: Download your first ontology
description: Find SAREF4ENER and download its OWL file in Turtle syntax.
---

We'll download SAREF4ENER, an ontology for energy devices. You'll need
[ohi installed](/docs/getting-started/install/). Downloading by name requires the next ohi release,
after v0.17.2.

## Find the ontology

```sh
ohi search saref4ener
```

Look for `saref/saref4ener` at version `v2.1.1`. The result lists its available formats.

## Download it

```sh
ohi get saref/saref4ener@v2.1.1 --format owl-turtle --to ontologies
```

This saves `saref4ener.ttl` in an `ontologies` folder, creating the folder if needed.
`owl-turtle` selects OWL in Turtle syntax. Open the file in a text editor to see its definitions.

SAREF4ENER also uses definitions from SAREF Core. To download both and record your choices,
continue with [Manage project dependencies](/docs/guides/dependencies/).
