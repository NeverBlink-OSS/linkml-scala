# Bootstrapping a LinkML schema from existing artifacts

Translating an existing model into LinkML. The output must validate cleanly and round-trip back
to something equivalent to the input — this skill is only done when both are demonstrated.

**Be honest about fidelity.** This is a translation between formalisms with genuinely different
expressive power, not a mechanical transform. Some constructs have no LinkML equivalent, and
some have no equivalent *in linkml-scala* even though LinkML defines them. Always report what
you dropped. A schema that silently loses constraints is worse than one that admits the gap.

## Process

1. **Read the source and inventory it** — count entities up front: classes, properties,
   datatypes, enumerations, constraints. This is your checklist and how you later prove
   coverage.
2. **Decide the shape** before writing YAML: which source entity becomes a class, which becomes
   an enum, what the identifiers are, and which single class is the `tree_root`.
3. **Write the schema**, preserving source URIs via `class_uri`, `slot_uri` and `meaning` so the
   result maps back onto the original vocabulary.
4. **Validate** — `linkml-scala validate --strict --format json schema.yaml` — and iterate until
   clean.
5. **Round-trip** — generate the source formalism back out and diff it against the input, where
   a generator exists for it (`rdfs`, `shacl`, `json-schema`, `table-schema`).
6. **Report** the inventory versus what you produced, and everything dropped and why.

Never skip 4–6. An unvalidated bootstrap is a draft, and saying so is part of the job.

## Preserve the source vocabulary

This is what makes the result useful rather than a lookalike. Keep the original IRIs:

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

## By source format

### RDFS / OWL ontologies

`rdfs:Class`/`owl:Class` → class. `rdf:Property`/`owl:DatatypeProperty` → attribute with an XSD
range. `owl:ObjectProperty` → attribute whose range is another class. `rdfs:subClassOf` →
`is_a` for single inheritance; use `mixins` when the source has multiple parents, since LinkML
allows only one `is_a`.

Ontologies are usually **open-world and property-centric**, while LinkML is class-centric: a
`domain`-less property applies to everything, whereas a LinkML attribute belongs to a class.
Decide per property which class owns it, and say so in your report.

Expect to drop: `owl:Restriction` cardinality axioms (partially expressible via `required`
and `multivalued`), `owl:equivalentClass`, `owl:disjointWith`, property characteristics
(transitive, symmetric, inverse), and anything relying on OWL inference.

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
- For CSV, `linkml-scala generate table-schema` round-trips back to Frictionless, so you can
  check your work.

## Round-tripping

```shell
# SHACL in, SHACL out
linkml-scala generate shacl --format ttl --to check.ttl schema.yaml

# JSON Schema in, JSON Schema out
linkml-scala generate json-schema --to check.json schema.yaml

# Sample data in: generate JSON Schema and check the samples still validate
linkml-scala generate json-schema --to check.json schema.yaml
# then validate the originals against check.json (see the linkml-validate-data skill)
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
Dropped:
  - owl:disjointWith on Foo/Bar — no LinkML equivalent
  - sh:or on Baz.qux — linkml-scala supports any_of in SHACL output only
Inferred (needs confirmation):
  - Status modelled as an enum from 4 observed values
  - Person.id as identifier — unique across all 1,203 sample records
Verified: validate --strict clean; 1,203/1,203 sample records validate
```

## Reference material

[200-limitations.md](200-limitations.md) — check before emitting anything unusual; it is what decides whether
a construct is genuinely supported. [910-examples.md](910-examples.md) — 49 known-good schemas indexed by
feature; find one using the construct you need and copy its shape. [900-metaslots.tsv](900-metaslots.tsv) —
grep for whether a slot exists at all. [100-authoring.md](100-authoring.md) — the built-in ranges.

Once the schema exists, hand off to [100-authoring.md](100-authoring.md) for editing, `linkml-review` for a
modelling-quality pass, and `linkml-validate-data` to check data against it.
