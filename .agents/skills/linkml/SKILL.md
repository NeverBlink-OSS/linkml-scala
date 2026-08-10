---
name: linkml
description: Author, validate, review and release LinkML schemas using the linkml-scala CLI. Auto-load for any task involving a LinkML schema - writing or editing classes, slots, attributes, enums, ranges, identifiers, inheritance, inlining, imports or prefixes; debugging a schema that fails to validate; generating JSON Schema, SHACL, RDFS, Frictionless Table Schema or Scala; converting an RDFS/OWL ontology, SHACL shapes, JSON Schema, XSD or sample data into LinkML; validating instance data against a schema; or setting up GitHub Actions for schema validation and releases.
license: Apache-2.0
---

# LinkML schemas with linkml-scala

[LinkML](https://linkml.io) is a YAML data-modelling language: write the model once, generate
JSON Schema, SHACL, RDFS, table schemas and code from it. This skill drives
[linkml-scala](https://github.com/NeverBlink-OSS/linkml-scala).

## Rules

* ALWAYS validate after editing a schema. The validator takes milliseconds and its messages are
  precise — never reason about whether a schema is correct when you can run it and know.
* ALWAYS check [200-limitations.md](200-limitations.md) before using a LinkML feature you have
  not already used with linkml-scala. A significant set of the language is unimplemented and
  **fails silently** — `rules:`, boolean expressions, arrays, dynamic enums, type designators.
  This is the single most common way to produce a schema that looks right and does nothing.
* NEVER claim a generator exists without checking.
* When unsure what a construct *means*, generate from it and read the output. `generate linkml`
  and `generate json-schema` are cheaper and more reliable than reasoning.
* NEVER install anything without asking first, and never pipe a remote script into a shell.

## Toolchain

```shell
linkml-scala version     # 0.12.0 or newer is required
```

`validate --format json` does not exist before 0.12.0. If the CLI is missing or too old, offer the
options in [990-install.md](990-install.md). If the user cannot install it, the
[playground](https://linkml.neverblink.eu/playground) runs the same engine in a browser.

## The loop

```shell
linkml-scala validate --format json schema.yaml   # machine-readable, act on it precisely
# fix, repeat until clean, then:
linkml-scala validate --strict schema.yaml        # warnings too
```

`--format json` emits one
[`SchemaValidationReport`](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/model/validation-report.yaml)
per input file. Each issue names the offending element and its JSON path, so act on the message
rather than guessing. **FATAL** means the schema will not load and every generator refuses;
**ERROR** fails the command; **WARNING** fails only under `--strict` but usually still wants fixing.

`validate` takes several files at once, and `lint` is an alias.

**It checks the schema, not your data.** There is no command to validate instance data — see
[500-validate-data.md](500-validate-data.md).

## A minimal schema

```yaml
id: https://example.org/my-schema
name: my-schema
description: What this models.

prefixes:
  ex: https://example.org/
  linkml: https://w3id.org/linkml/
default_prefix: ex

imports:
  - linkml:types        # nearly always needed: string, integer, date, ...

classes:
  Person:
    tree_root: true     # the root of a generated document
    attributes:
      id:
        identifier: true
        range: string
      name:
        required: true
      age:
        range: integer
      email:
        multivalued: true
      status:
        range: PersonStatus

enums:
  PersonStatus:
    permissible_values:
      ACTIVE:
      RETIRED:
```

Four things that trip people up:

* **`attributes` vs `slots`.** `attributes` are inline on one class — the default choice.
  Top-level `slots:` are reusable, referenced by name from a class's `slots:` list. Do not reach
  for them until something is genuinely shared.
* **`default_range` is always `string`** if omitted — a slot with no `range` is a string, not
  "anything". For anything-goes, use a class whose `class_uri` is `linkml:Any`.
* **`identifier: true`** needs a scalar range (not a class, not an enum), at most one per class,
  and it silently changes inlining everywhere the class is used.
* **`tree_root`** marks the document root. Exactly one class in the root schema should have it.

Whether a nested class is embedded or referenced by identifier is **inferred**, not declared, and
it changes the shape of every generated artifact. The decision table is in
[200-limitations.md](200-limitations.md); when in doubt generate JSON Schema and look.

## Generating

```shell
linkml-scala generate json-schema --to out/schema.json schema.yaml
```

Generators: `json-schema`, `shacl`, `rdfs`, `table-schema`, `scala`, `linkml`. Omit `--to` for
stdout; `--format ttl` gives prefixed Turtle from the RDF ones instead of N-Triples; `--open` on
`json-schema`/`shacl` allows undeclared properties. For flags run
`linkml-scala generate <generator> --help` — do not guess.

**`generate linkml` is the debugging tool.** It resolves imports, materialises inherited slots
into attributes and prunes unused elements, showing what the schema *actually* says:

```shell
linkml-scala generate linkml schema.yaml
```

Reach for it whenever inheritance, `slot_usage` or imports make the effective model unclear — and
it is the best artifact to hand downstream consumers, who then need no import resolution.

## Topics

Read these when the task calls for them; do not load them up front.

### Authoring

* [Authoring reference](100-authoring.md) — `slot_usage`, inheritance and mixins, constraints,
  enums, identifiers and `unique_keys`, imports, prefixes and URI mappings, built-in ranges,
  documentation metadata, upstream doc links.
* [Limitations and divergent semantics](200-limitations.md) — unsupported features, the inlining
  decision table, `tree_root_as`, where behaviour differs from linkml-python. **Check before
  using anything unfamiliar.**

### Workflows

* [Bootstrapping from existing artifacts](300-bootstrap.md) — converting an RDFS/OWL ontology,
  SHACL shapes, JSON Schema, XSD, or sample JSON/YAML/CSV data into LinkML, and proving the result
  round-trips.
* [Reviewing a schema](400-review.md) — modelling-quality checklist beyond the linter: identifier
  and `tree_root` choices, inlining, reuse, external vocabulary mappings, naming, documentation.
* [Validating instance data](500-validate-data.md) — generate JSON Schema or SHACL, then run
  `check-jsonschema`, `ajv`, `pyshacl` or `frictionless`. Includes how to read the failures.
* [CI and releases](600-ci.md) — GitHub Actions for validation with inline PR annotations,
  artifact generation, and tag-triggered releases. Templates in `assets/`.

### Lookup tables

* [900-metaslots.tsv](900-metaslots.tsv) — every metamodel slot you may write, over 200 of them.
  **Query it, do not read it:**
  ```shell
  grep -P '^inlined\t' 900-metaslots.tsv
  awk -F'\t' '$5 ~ /min|basic/ {print $1}' 900-metaslots.tsv   # the everyday vocabulary
  ```
* [910-examples.md](910-examples.md) — dozens of known-good schemas indexed by feature, each
  verified against every generator on every commit. When unsure how to write something, read a
  working one.
* [990-install.md](990-install.md) — install options, in order of preference.
