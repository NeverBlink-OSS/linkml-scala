# LinkML authoring reference

Depth on constructs the skill body only mentions. Everything here is supported by linkml-scala
unless explicitly noted. Check `200-limitations.md` before using anything not covered here.

Examples are fragments; wrap them in the minimal schema from the skill body. All of them are
validated under `--strict` in CI.

## Slots versus attributes

```yaml
# Inline on one class. Use this by default.
classes:
  Person:
    attributes:
      name:
        range: string

# Reusable across classes. Worth it once genuinely shared.
slots:
  name:
    range: string
    required: true
classes:
  Person:
    slots: [name]
  Organisation:
    slots: [name]
```

A shared slot can be adjusted per class with `slot_usage`, which narrows it for that class only:

```yaml
classes:
  Organisation:
    slots: [name]
    slot_usage:
      name:
        description: Registered legal name.
        pattern: "^[A-Z].*"
```

`slot_usage` may only **narrow**. Widening a range or making a required slot optional is a
modelling error, and `InvalidSlotUsage` is reported if you name a slot the class does not
actually have. To see the result of all this resolution, run
`linkml-scala generate linkml`.

## Inheritance

```yaml
classes:
  Agent:
    abstract: true
    attributes:
      id:
        identifier: true

  HasAddress:
    mixin: true
    attributes:
      address:
        range: string

  Person:
    is_a: Agent          # exactly one parent
    mixins:              # any number of mixins
      - HasAddress
    attributes:
      name:
```

- `is_a` is single inheritance and means "is a kind of". One only.
- `mixins` carry cross-cutting sets of slots. Use these when a source model has multiple
  inheritance.
- `abstract: true` cannot be instantiated; `mixin: true` marks a class as intended for mixing in.
- Prefer shallow hierarchies — past about three levels the materialised model is hard to follow.

Enum inheritance is **not supported** — no `inherits`, `include`, `minus` or `reachable_from`.

## Ranges and constraints

```yaml
attributes:
  age:
    range: integer
    minimum_value: 0
    maximum_value: 150
  email:
    pattern: "^[^@]+@[^@]+$"
  code:
    range: string
    required: true
  tags:
    multivalued: true
  note:
    recommended: true      # not required, but flagged if missing
```

Available: `required`, `recommended`, `multivalued`, `pattern`, `minimum_value`,
`maximum_value`, `equals_string`, `equals_number`, `unit`. 

### Built-in ranges

Omitting `range` gives the schema's `default_range`, itself defaulting to `string`. Enum and
class names are valid ranges too; `linkml:Any` accepts anything (see below).

Everyday scalars: `string`, `integer`, `boolean`, `float`, `double`, `decimal`.

Dates and times: `date`, `datetime`, `time`, `date_or_datetime`.

Identifiers: `uri`, `uriorcurie`, `curie`, `ncname` (the prefix part of a CURIE),
`objectidentifier` and `nodeidentifier` (an IRI, CURIE or blank node naming a node in a graph).

Sized numerics: `int8`…`int64`, `uint8`…`uint64`, `float16`/`float32`/`float64`,
`signedinteger`, `unsignedinteger`, `any_number`.

Validated string formats: `jsonpath`, `jsonpointer`, `sparqlpath`.

`structured_pattern` composes a regex from `settings:`, and is expanded at derivation time:

```yaml
settings:
  id_prefix: "EX"
classes:
  Thing:
    attributes:
      id:
        identifier: true
        structured_pattern:
          syntax: "^{id_prefix}:[0-9]{4}$"
          interpolated: true
```

**Not supported**: `any_of`, `none_of`, `all_of`, `exactly_one_of` (SHACL output only), `rules:`,
and arrays. If a constraint needs one of these, express it another way or document the gap.

## Enums

```yaml
enums:
  Status:
    description: Lifecycle state.
    permissible_values:
      ACTIVE:
        description: In active use.
        meaning: ex:Active        # maps onto an external vocabulary
      RETIRED:
        description: No longer used.
```

Every permissible value gets an IRI whether or not you give it a `meaning`, so enums always
survive a round trip through RDF. Give a `meaning` when an external term exists — that is what
makes the enum interoperable.

Use `UPPER_SNAKE_CASE` for value names. An enum name is a valid slot `range`.

## Keys and identifiers

```yaml
classes:
  Person:
    attributes:
      id:
        identifier: true    # globally unique across the document
  LineItem:
    attributes:
      sku:
        key: true           # unique within the containing collection
```

- At most one `identifier` **or** `key` per class; more is an error.
- The range must be a scalar type. A class or enum range is an error in linkml-scala.
- The presence of one changes inlining everywhere the class is used, so this is not a
  cosmetic choice.

`unique_keys` declares additional uniqueness over slot combinations. Note that
`unique_key_slots` resolves against **top-level `slots:` only** — naming an inline attribute
there is a fatal `Unknown reference` in linkml-scala, so a class using `unique_keys` has to
declare those slots at the top level:

```yaml
slots:
  room:
    range: string
  day:
    range: date
classes:
  Booking:
    slots: [room, day]
    unique_keys:
      room_day:
        unique_key_slots: [room, day]
```

## Imports and multi-file schemas

```yaml
prefixes:
  ex: https://example.org/
  linkml: https://w3id.org/linkml/
default_prefix: ex
imports:
  - linkml:types        # the built-in types; nearly always needed
  - ./common            # a sibling file, common.yaml
```

Relative imports resolve against the importing file, without the `.yaml` extension. `linkml:` and
other prefixed imports resolve via the prefix map.

All references are checked **eagerly** in linkml-scala: an unresolvable name is fatal and the
schema will not load at all, so imports must be right before anything else works.

Flatten a multi-file schema into one self-contained file with:

```shell
linkml-scala generate linkml --to derived.yaml schema.yaml
```

That is the artifact to hand to consumers who should not have to resolve your imports.

## URIs, prefixes and mappings

```yaml
prefixes:
  ex: https://example.org/
  schema: http://schema.org/
default_prefix: ex

classes:
  Person:
    class_uri: schema:Person
    attributes:
      name:
        slot_uri: schema:name
    exact_mappings:
      - foaf:Person
    close_mappings:
      - dcterms:Agent
```

- `default_prefix` is used to mint IRIs for anything without an explicit URI. If omitted, the
  schema `id` is used, so URIs are always constructible.
- Every prefix you reference must be declared, or you get `UndefinedPrefix`. These are
  pre-declared: `linkml`, `rdf`, `rdfs`, `xsd`, `skos`, `dcterms`, `OIO`, `owl`, `pav`.
- `class_uri`/`slot_uri` pin the identity of an element. Use them whenever the concept already
  exists in a published vocabulary — without them, generated RDF cannot be joined to anything.
- `exact_mappings`, `close_mappings`, `related_mappings`, `narrow_mappings`, `broad_mappings`
  record looser relationships and do not change generated output.

## The document root

```yaml
classes:
  Container:
    tree_root: true
    attributes:
      persons:
        range: Person
        multivalued: true
        inlined_as_list: true
```

One class in the root schema should be `tree_root`. `tree_root_as` (a linkml-scala extension)
controls the root's shape — `plain`, `optional`, `list`, `compact_dict`, `simple_dict`:

```yaml
classes:
  Person:
    tree_root: true
    extensions:
      tree_root_as: list      # the document is a JSON array of Person
```

At generation time `--tree-root-override` picks a different root class, and
`--tree-root-inline-type-override` changes the shape without editing the schema. Details and the
full inlining decision table are in `200-limitations.md`.

## Documentation and metadata

`title`, `description`, `comments`, `notes`, `examples`, `see_also`, `aliases`, `deprecated`,
`status`, `rank`, `in_subset` and `keywords` are available on most elements. `rank` controls
ordering in generated output; `examples` takes a list of `{value, description}` pairs.

## Anything-goes ranges

There is no `Any` type. Declare a class whose `class_uri` is `linkml:Any`:

```yaml
classes:
  Any:
    class_uri: linkml:Any
  Record:
    attributes:
      payload:
        range: Any
```

Remember that omitting `range` gives you `string` (via `default_range`), not "anything".

## Checking whether a construct exists

`900-metaslots.tsv` (see the skill body for the queries) lists what the *metamodel* defines, which
is not what linkml-scala implements. Cross-check anything unusual against
[200-limitations.md](200-limitations.md), and when in doubt write the smallest schema using it and
run the validator.

## Upstream documentation

Only worth fetching when nothing here answers the question.

> Use the `/latest/` URLs. The `w3id.org/linkml/...` redirects for HTML pages drop the version
> segment and 404. `w3id.org/linkml/meta.yaml` does resolve.

| Topic | URL |
|---|---|
| Authoring guides | <https://linkml.io/linkml/schemas/> |
| Modelling FAQ — ~28 "X or Y?" decisions, the densest source | <https://linkml.io/linkml/faq/modeling.html> |
| Specification — schemas datamodel | <https://linkml.io/linkml-model/latest/docs/specification/03schemas/> |
| Specification — derived schemas, inheritance | <https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/> |
| Per-element metamodel pages | `https://linkml.io/linkml-model/latest/docs/<ElementName>/` |
| Machine-readable metamodel | <https://w3id.org/linkml/meta.yaml> |

Where linkml.io and linkml-scala disagree, [200-limitations.md](200-limitations.md) wins — it
describes the implementation you are running.
