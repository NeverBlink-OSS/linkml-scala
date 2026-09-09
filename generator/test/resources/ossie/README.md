# Apache Ossie ontology schema

`ontology.json` is the JSON Schema for Apache Ossie ontology definitions, taken verbatim from [apache/ossie](https://github.com/apache/ossie) (`ontology/ontology.json`, spec version `0.2.0.dev0`).

Distributed under the Apache License 2.0, see `LICENSE`.

To update, replace both files and refresh the commit reference above:

```bash
base=https://raw.githubusercontent.com/apache/ossie/main
curl -fL $base/ontology/ontology.json -o generator/test/resources/ossie/ontology.json
curl -fL $base/core-spec/ossie-schema.json -o generator/test/resources/ossie/ossie-schema.json
```
