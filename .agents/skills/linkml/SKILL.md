---
name: linkml
description: Author, validate, review and release LinkML schemas using the ohi CLI (`ohi linkml`). Auto-load for any task involving a LinkML schema - writing or editing classes, slots, attributes, enums, ranges, identifiers, inheritance, inlining, imports or prefixes; debugging a schema that fails to validate; generating JSON Schema, SHACL, RDFS, OWL, a Frictionless data package or Scala; importing an OWL ontology, RDFS vocabulary or Apache Ossie ontology into LinkML; converting SHACL shapes, JSON Schema, XSD or sample data into LinkML; validating instance data against a schema; or setting up GitHub Actions for schema validation and releases.
license: Apache-2.0
---

# LinkML schemas with ohi

[LinkML](https://linkml.io) is a YAML data-modelling language: write the model once, generate
JSON Schema, SHACL, RDFS, Frictionless data packages and code from it. This skill drives
[linkml-scala](https://github.com/NeverBlink-OSS/linkml-scala).

## Rules

* ALWAYS validate after editing a schema. The validator takes milliseconds and its messages are
  precise — never reason about whether a schema is correct when you can run it and know.
* ALWAYS check [200-limitations.md](200-limitations.md) before using a LinkML feature you have
  not already used with linkml-scala. A significant set of the language is unimplemented and
  **fails silently** — `rules:`, boolean expressions, arrays, dynamic enums, type designators.
  This is the single most common way to produce a schema that looks right and does nothing.
* NEVER claim a generator or importer exists without checking.
* NEVER write a schema by hand from an OWL ontology, an RDFS vocabulary or an Apache Ossie
  ontology. Run the importer (`ohi linkml from owl` / `from ossie`), then review and tweak.
* When unsure what a construct *means*, generate from it and read the output. `to linkml`
  and `to json-schema` are cheaper and more reliable than reasoning.
* NEVER install anything without asking first, and never pipe a remote script into a shell.

## Toolchain

```shell
ohi version     # 0.17.1 or newer is required
```

If the CLI is missing or too old, offer the
options in [990-install.md](990-install.md). If the user cannot install it, the
[playground](https://linkml.ohi.dev/playground) runs the same engine in a browser.

## The loop

```shell
ohi linkml validate --format json schema.yaml   # machine-readable, act on it precisely
# fix, repeat until clean, then:
ohi linkml validate --strict schema.yaml        # warnings too
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
ohi linkml to json-schema --to out/schema.json schema.yaml
```

Generators: `json-schema`, `shacl`, `rdfs`, `owl`, `frictionless`, `scala`, `linkml`, `graphql`,
`typescript`, `pydantic`, `er-diagram`, `docs`, `translation`, `ossie`. Omit `--to` for stdout; `--format ttl` gives prefixed Turtle from the RDF ones
instead of N-Triples; `--open` on `json-schema`/`shacl` allows undeclared properties. `scala` and
`frictionless` write several files, so point their `--to` at a directory. `docs` writes Markdown
documentation: a page per class, slot, enum and type when `--to` is a directory, or one document
when it is a `.md` file or stdout. For flags run
`ohi linkml to <generator> --help` — do not guess.

`to er-diagram` draws the model as a
[Mermaid ER diagram](https://mermaid.js.org/syntax/entityRelationshipDiagram.html) — classes become
entities, type- and enum-ranged slots their attributes, and class-ranged slots the lines between
them. Reach for it to *show* someone a schema, and to eyeball whether the inlining came out the way
you meant: a solid line is an inlined child, a dashed one a reference.

Optional attributes get a trailing `?`, which **Mermaid only understands from 11.16 (June 2026)** —
and an older renderer rejects the whole diagram, not just the marker. If the diagram is headed for
somewhere that pins its own Mermaid (GitHub, GitLab, Notion, mkdocs), pass
`--optional-marker=false`.

**`to linkml` is the debugging tool.** It resolves imports, materialises inherited slots
into attributes and prunes unused elements, showing what the schema *actually* says:

```shell
ohi linkml to linkml schema.yaml
```

Reach for it whenever inheritance, `slot_usage` or imports make the effective model unclear — and
it is the best artifact to hand downstream consumers, who then need no import resolution.

## Importing

The reverse of `to`: read a document in another format and write a LinkML schema.

```shell
ohi linkml from owl --list-not-imported --to schema.yaml ontology.ttl
```

Importers: `owl`, `ossie`.

`from owl` reads OWL ontologies and plain RDFS vocabularies (DC terms, Schema.org and the like) in
Turtle or N-Triples. `from ossie` reads an [Apache Ossie](https://github.com/apache/ossie)
ontology. Both keep the source IRIs, and the result usually needs only small manual fixes. How to
import, check and finish the result is in [300-bootstrap.md](300-bootstrap.md).

## Topics

Read these when the task calls for them; do not load them up front.

### Authoring

* [Authoring reference](100-authoring.md) — `slot_usage`, inheritance and mixins, constraints,
  enums, identifiers and `unique_keys`, imports, prefixes and URI mappings, built-in ranges,
  documentation metadata, upstream doc links.
* [Limitations and divergent semantics](200-limitations.md) — unsupported features, the inlining
  decision table, `tree_root_as`, where behaviour differs from linkml-python. **Check before
  using anything unfamiliar.**
* [Inherited slots and `slot_usage`](210-slot-usage-inheritance.md) — why a `slot_usage` can make a
  slot required or multivalued, but not optional or single-valued, and how `any_of`, `all_of`,
  `exactly_one_of` and `none_of` from parent classes, mixins and the slot definition are combined.
* [Import maps](220-import-maps.md) — how to load an import from another place without editing
  the schema, with a JSON file of import to location, and how imports, including CURIEs, are
  matched.
* [URIs and CURIEs](230-curies.md) — how CURIEs are expanded when a schema is loaded and URIs
  compacted when it is written, and which prefixes are used.
* [Language strings in generators](240-langstring.md) — how each generator writes a `langstring`
  value, a plain string or a mapping from language tag to text.

### Workflows

* [Bootstrapping from existing artifacts](300-bootstrap.md) — importing an OWL ontology, RDFS
  vocabulary or Ossie ontology, converting SHACL shapes, JSON Schema, XSD, or sample
  JSON/YAML/CSV data into LinkML, and proving the result round-trips.
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
