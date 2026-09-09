# Apache Ossie ontology schema

`ontology.json` is the JSON Schema for Apache Ossie ontology definitions, taken verbatim from
[apache/ossie](https://github.com/apache/ossie) (`ontology/ontology.json`, spec version
`0.2.0.dev0`), at commit
[`50457d3`](https://github.com/apache/ossie/commit/50457d308f2e48c4e7b0294502d0f40db11f52c0).

Distributed under the Apache License 2.0, see `LICENSE`.

To update, replace both files and refresh the commit reference above:

```bash
base=https://raw.githubusercontent.com/apache/ossie/main
curl -fL $base/ontology/ontology.json -o generator/test/resources/ossie/ontology.json
curl -fL $base/core-spec/ossie-schema.json -o generator/test/resources/ossie/ossie-schema.json
```
