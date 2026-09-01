# Conformance runner (Python prototype)

A Python port of `generator/test/src-jvm/eu/neverblink/linkml/generator/conformance/ConformanceRunner.scala`.

It loads `generator/test/resources/conformance/manifest.yaml` into the classes in
`generated.py` (generated from `model/conformance-ontology.yaml` with `gen-python`),
runs each entry's action, and checks the output against the entry's assertion.

## Files

- `generated.py` — the LinkML-generated classes for the conformance ontology.
- `runner.py` — the runner itself, plus a small CLI.
- `test_conformance.py` — pytest wrapper, one test per manifest entry.

## Running

```bash
.venv/bin/python conformance-prototype/runner.py     # plain report, non-zero exit on failure
.venv/bin/python -m pytest conformance-prototype     # one pytest case per entry
```

The resources path is hard-coded at the top of `runner.py`, as it is in the Scala version.

## What's covered

Actions: `LoadAction`, `DeriveAction`, `LintAction`, `JsonSchemaGenerate`.
Assertions: `LoadsAssertion`, `StringAssertion`, `JsonPathAssertion`, `JsonSchemaAccepts`, `JsonSchemaRejects`.

The `type` slot in the ontology is a type designator, so `linkml_runtime`'s loader
picks the right `Action`/`Assertion` subclass by itself — no manual dispatch on load.

JSON Schema validation uses the `jsonschema` package (Draft 2020-12 by default,
or whatever the generated schema's `$schema` declares), standing in for
networknt's `SchemaRegistry` on the Scala side.

## Known gap

`DeriveAction` maps to linkml's `YAMLGenerator`. It currently fails on
`conformance/basic/model.yaml` with `Unknown CURIE prefix: linkml`, because that
schema imports `linkml:types` without declaring the `linkml` prefix.
`JsonSchemaGenerator` tolerates this, `YAMLGenerator` does not. Adding

```yaml
prefixes:
  linkml: https://w3id.org/linkml/
```

to the test schema fixes it, but that file is shared with the Scala suite, so it
was left alone. No manifest entry uses `DeriveAction` today.
