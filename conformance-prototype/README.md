# Conformance runner (Python prototype)

A Python port of `generator/test/src-jvm/eu/neverblink/linkml/generator/conformance/ConformanceRunner.scala`.

It loads `generator/test/resources/conformance/manifest.yaml` into the classes in
`generated.py` (generated from `model/conformance-ontology.yaml` with `gen-python`),
runs each entry's action against that entry's schema, and checks the output
against the entry's assertion.

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

## How it maps to the Scala runner

| Scala | Python |
| --- | --- |
| `manifestCodec.decode(parseYaml(...))` | `yaml_loader.load(path, generated.Manifest)` |
| `LinkMlGenerator` with `OutputFormat.json` | `LinkmlGenerator(format="json", materialize_attributes=True)` |
| `JsonSchemaGenerator` | `linkml.generators.jsonschemagen.JsonSchemaGenerator` |
| `sv.lint()` | `linkml.linter.linter.Linter().lint(...)` |
| networknt `SchemaRegistry` | `jsonschema` |

`entries` is inlined and keyed by `name`, so it loads as a dict of name -> `Test`,
and each `Test` carries its own `schema`. The `type` slot is a type designator, so
`linkml_runtime`'s loader picks the right `Action`/`Assertion` subclass by itself —
no manual dispatch on load.

`materialize_attributes=True` folds induced slots into each class's `attributes`,
which is what the `classes/<C>/attributes/<slot>/rank` assertions read.

JSON Schema validation uses the `jsonschema` package (Draft 2020-12 by default,
or whatever the generated schema's `$schema` declares).

## Coverage

Actions: `LoadAction`, `DeriveAction`, `LintAction`, `JsonSchemaGenerate`.
Assertions: `LoadsAssertion`, `StringAssertion`, `JsonPathAssertion`, `JsonSchemaAccepts`, `JsonSchemaRejects`.

## Known difference from the Scala runner

Scala passes 5/5; Python passes 4/5. The one failure is **Slot to slot rank
inheritance**: `rank` is not inherited through a slot's `is_a` parent in Python
linkml, so `second_slot` comes out with no `rank` at all where Scala derives 2.

```
raw second_base_slot rank: 2
raw second_slot rank:      None
induced second_slot rank:  None   (slot_ancestors: second_slot, second_base_slot)
```

`rank` is not marked `inherited: true` in the metamodel, so `class_induced_slots`
never propagates it. `class2class` (which uses `slot_usage`) works fine in both.
This is a genuine implementation difference, not a bug in the runner.
