# Bootstrapping a LinkML schema from existing artifacts

Translating an existing model into LinkML. The output must validate cleanly and round-trip back
to something equivalent to the input — this skill is only done when both are demonstrated.

**Use an importer when there is one:** `linkml-scala from owl` for OWL ontologies and RDFS
vocabularies, `linkml-scala from ossie` for Apache Ossie ontologies. They handle a wide range of
real ontologies, and a hand translation will not do better, so run the importer and then make the
small manual fixes below. If `linkml-scala --help` has no `from` commands, offer an upgrade
([990-install.md](990-install.md)). SHACL, JSON Schema, XSD and sample data have no importer, so
translate those by hand.

**Be honest about fidelity.** This is a translation between formalisms with genuinely different
expressive power, not a mechanical transform. Some constructs have no LinkML equivalent, and
some have no equivalent *in linkml-scala* even though LinkML defines them. Always report what
you dropped. A schema that silently loses constraints is worse than one that admits the gap.

## Process

1. **Read the source and inventory it** — count entities up front: classes, properties,
   datatypes, enumerations, constraints. This is your checklist and how you later prove
   coverage.
2. **Import, or decide the shape and write.** With an importer, run it (see below). Otherwise
   decide before writing YAML which source entity becomes a class, which becomes an enum, what
   the identifiers are, and which single class is the `tree_root` — then write the schema,
   preserving source URIs via `class_uri`, `slot_uri` and `meaning` so the result maps back onto
   the original vocabulary.
3. **Validate** — `linkml-scala validate --strict --format json schema.yaml` — and iterate until
   clean.
4. **Finish** — for an imported schema, work through the fixes listed for its importer below.
5. **Round-trip** — generate the source formalism back out and diff it against the input, where
   a generator exists for it (`owl`, `rdfs`, `ossie`, `shacl`, `json-schema`, `frictionless`).
6. **Report** the inventory versus what you produced, and everything dropped and why.

Never skip 4–6. An unvalidated bootstrap is a draft, and saying so is part of the job.

## Preserve the source vocabulary

The importers do this for you. When translating by hand, it is what makes the result useful rather than a lookalike. Keep the original IRIs:

```yaml
prefixes:
  ex: https://example.org/
  foaf: http://xmlns.com/foaf/0.1/
default_prefix: ex

classes:
  Person:
    class_uri: foaf:Person          # keep the original class IRI
    attributes:
      name:
        slot_uri: foaf:name         # keep the original property IRI
        range: string

enums:
  Status:
    permissible_values:
      ACTIVE:
        meaning: ex:ActiveStatus    # keep the original term IRI
```

Without these, generated RDF invents `https://example.org/Person` and no longer matches the data
you were modelling.

## Importing OWL, RDFS and Ossie

```shell
linkml-scala from owl --list-not-imported --to schema.yaml ontology.ttl
linkml-scala from ossie --schema-id https://example.org/my-schema --to schema.yaml ontology.json
```

`from owl` reads Turtle or N-Triples only. `--list-not-imported` prints what was dropped, which
goes in your report. For the other flags, including the `--config` that controls naming and maps
`owl:imports` to existing LinkML schemas, run `--help`. The mappings are in
[docs/owl.md](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/owl.md) and
[docs/ossie_mapping.md](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/ossie_mapping.md).

Usual fixes afterwards — ask the user rather than guess:

* Pick a `tree_root`.
* Narrow `range: Any` (OWL properties with no range or a mixed one).
* Make slots single-valued where the data has one value. OWL/RDFS import everything that is not
  functional as multivalued.
* Add an `identifier` slot if the data needs one. RDF uses the node IRI, so none is imported.
* Rename what reads badly, and set a real `--schema-id` for Ossie (the default is a placeholder).

Round-trip with `generate owl` or `generate ossie`.

## Translating by hand

### SHACL shapes

The closest fit, since SHACL is also closed-world and shape-centric. `sh:NodeShape` → class,
`sh:property` → attribute, `sh:path` → `slot_uri`, `sh:datatype`/`sh:class` → `range`,
`sh:minCount ≥ 1` → `required: true`, `sh:maxCount > 1` (or absent) → `multivalued: true`,
`sh:pattern` → `pattern`, `sh:in` → an enum, `sh:minInclusive`/`sh:maxInclusive` →
`minimum_value`/`maximum_value`.

`sh:or`/`sh:not`/`sh:xone` map to `any_of`/`none_of`/`exactly_one_of`, which **linkml-scala only
partially supports, in SHACL output only**. Flag these rather than emitting them silently.
Also unsupported: `sh:sparql` constraints, and `sh:node` indirection beyond a plain range.

### JSON Schema

`type: object` → class, `properties` → attributes, `required` → `required: true`,
`type: array` → `multivalued: true`, `enum` → an enum, `$ref` → a class range, `format: date` →
`range: date`, `pattern` → `pattern`. The root schema becomes the `tree_root` class.

`additionalProperties: false` is the LinkML default in linkml-scala; if the source allows extras
there is no faithful equivalent, so note it (`--open` at generation time is the closest thing).
`oneOf`/`anyOf`/`allOf` hit the same boolean-expression limitation as SHACL. `if`/`then`,
`patternProperties`, `propertyNames` and tuple-form `items` have no LinkML equivalent.

### XSD

`xs:complexType` → class, `xs:element`/`xs:attribute` → attributes, `xs:simpleType` with
`xs:enumeration` → enum, `xs:extension` → `is_a`, `minOccurs`/`maxOccurs` →
`required`/`multivalued`, `xs:restriction` facets → `pattern`, `minimum_value`,
`maximum_value`. `xs:sequence` ordering, `xs:choice`, mixed content and substitution groups do
not survive; say so.

### Sample data (JSON, YAML, CSV)

Inference from examples, so state your confidence and get it confirmed.

- Union every record, don't just read the first — optional fields only reveal themselves across
  a sample.
- A field absent from any record is `required: true`; anything else is optional.
- Narrow types conservatively: all-integer → `integer`, ISO-8601-looking → `date`/`datetime`,
  `true`/`false` → `boolean`, otherwise `string`. One stray value collapses a column to
  `string` — prefer that over a wrong narrow type.
- A small, closed, repeated value set suggests an enum. **Ask** before committing, since new
  data may add values and an enum then rejects it.
- Nested objects → an inlined class. Arrays of objects → `multivalued: true` plus
  `inlined_as_list: true`.
- A plausible unique key suggests `identifier: true`. Verify uniqueness across the whole sample
  before asserting it, and prefer no identifier over a wrong one — it changes inlining
  behaviour everywhere.
- For CSV, `linkml-scala generate frictionless` round-trips back to Frictionless as a data
  package, so you can check your work.

## Round-tripping

The importers' round-trips are above. For hand translations:

```shell
# SHACL in, SHACL out
linkml-scala generate shacl --format ttl --to check.ttl schema.yaml

# JSON Schema in, JSON Schema out
linkml-scala generate json-schema --to check.json schema.yaml

# Sample data in: generate JSON Schema and check the samples still validate
linkml-scala generate json-schema --to check.json schema.yaml
# then validate the originals against check.json (see 500-validate-data.md)
```

Diffs are expected — LinkML adds structure and normalises names. What matters is that no
*constraint* was lost and no *entity* went missing. For sample data the bar is concrete and
absolute: **every input record must validate against the generated JSON Schema.** If one does
not, the schema is wrong, not the data.

## Naming

LinkML convention is `UpperCamelCase` classes, `snake_case` slots, `UPPER_SNAKE_CASE`
permissible values. Rename to fit and let `class_uri`/`slot_uri`/`meaning` carry the original
identifiers. Keep source names discoverable in `aliases:` when the rename is not obvious.

## Report template

```
Source: <file> (<format>)
Inventory:  N classes, M properties, K enums, J constraints
Produced:   N classes, M attributes, K enums
Method:     linkml-scala from owl --list-not-imported (or: by hand)
Dropped:
  - property chain hasParent o hasParent → hasGrandparent — LinkML cannot express it
  - sh:or on Baz.qux — linkml-scala supports any_of in SHACL output only
Fixed after import:
  - Animal as tree_root; has_parent range Any → Animal
Inferred (needs confirmation):
  - Status modelled as an enum from 4 observed values
  - Person.id as identifier — unique across all 1,203 sample records
Verified: validate --strict clean; 1,203/1,203 sample records validate
```

## Reference material

[200-limitations.md](200-limitations.md) — check before emitting anything unusual; it is what decides whether
a construct is genuinely supported. [910-examples.md](910-examples.md) — known-good schemas indexed by
feature; find one using the construct you need and copy its shape. [900-metaslots.tsv](900-metaslots.tsv) —
grep for whether a slot exists at all. [100-authoring.md](100-authoring.md) — the built-in ranges.

Once the schema exists, [100-authoring.md](100-authoring.md) covers editing it,
[400-review.md](400-review.md) a modelling-quality pass, and
[500-validate-data.md](500-validate-data.md) checking data against it.
