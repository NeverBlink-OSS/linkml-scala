# Implementation differences

## Feature comparison with LinkML-Python

The following features are not yet supported or are partially supported in LinkML-Scala:

- Arrays
- Boolean expressions (`any_of`, `none_of`); initial support in SHACL
- Partial support for default values (`ifabsent`)
  - Only enum and boolean defaults are supported, only in the Scala generator
- Partial support for computed values (e.g., `equals_expression`)
  - Only string interpolation is supported, only in the Scala generator
- Partial support for type designators (`designates_type`)
  - Supported in the Scala, TypeScript, Pydantic and JSON Schema generators and in YAML/JSON serialization. Not yet in SHACL.
- Enum inheritance, dynamic enums (`include`, `minus`, `reachable_from`)
- Rules (`rules`)

These features are supported in LinkML-Scala but not in LinkML-Python (or are supported in LinkML-Python to a lesser degree):

- Support for [extra data (`extra_slots`)](#extra-data)
  - A `range_expression` allows any extra data, which is not checked against it (see below)
  - Extra data is only validated, not kept: decoding drops it, so it is lost on a round trip (see below)
- Support for `union_of`
  - JSON Schema, Scala, TypeScript, and Pydantic generators support type unions.
  - Some constraints on unioned types may be lost, depending on the expressiveness of the target language.
- Support for [language strings](#language-strings)

## Inherited type constraints

LinkML-Scala emits JSON Schema patterns and numeric bounds inherited through `typeof`.
Python LinkML 1.11.1 omits these in our comparison tests, so Scala's schema rejects some data Python's accepts.

## Imports

Imports are easier to write in LinkML-Scala: file extensions are optional and interchangeable, and each imported schema is loaded only once.

| Case | Python LinkML 1.11.1 | LinkML-Scala |
|------|----------------------|--------------|
| `imports: [core]` with only `core.yml` on disk | Fails | Reads `core.yml` |
| `imports: [core.yaml]` or `[core.yml]` | Fails: reads `core.yaml.yaml` or `core.yml.yaml` | Works |
| One file imported as `dir/a`, `dir/./a`, `dir//a` or `dir/x/../a` | Loaded once per spelling | Loaded once |
| One file reached through a symbolic or hard link | Loaded twice | Loaded once (JVM and Native) |
| An import back to the root schema | Loads the root again, unless its `name` matches the file name | Loaded once, when the root is loaded from a path |

## Eager validation of references

All LinkML references (like `slot_name` in `slots: [ slot_name ]`) are eagerly checked when creating the SchemaView.
SchemaView is not able to proceed with derivation if this requirement is not satisfied.

For the same reason, loops are rejected when creating the SchemaView: a class or slot that inherits from itself through `is_a` or `mixins`, a type that is its own `typeof` ancestor, and a type that is a member of its own `union_of` (directly, through nested unions, or through the `typeof` parent of a member).

## Emitted prefixes

Metamodel `emit_prefixes` is honored, meaning the following prefixes are defined automatically in all schemas:

- `linkml`: https://w3id.org/linkml/
- `rdf`: http://www.w3.org/1999/02/22-rdf-syntax-ns#
- `rdfs`: http://www.w3.org/2000/01/rdf-schema#
- `xsd`: http://www.w3.org/2001/XMLSchema#
- `skos`: http://www.w3.org/2004/02/skos/core#
- `dcterms`: http://purl.org/dc/terms/
- `OIO`: http://www.geneontology.org/formats/oboInOwl#
- `owl`: http://www.w3.org/2002/07/owl#
- `pav`: http://purl.org/pav/

## Default `default_range`

If a `default_range` is not provided, then it will be filled with `string` (as per [the specification](https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#rule-populate-schema-metadata))
This is always honored in LinkML-Scala.
To make generators to emit an "accept anything" schema, set the range to a class with uri `linkml:Any`, as described [here](https://linkml.io/linkml/schemas/advanced.html#linkml-any-type).

## Default `default_prefix`

If a `default_prefix` is not provided, then the schema's `id` will be used instead.
This ensures that all schema Elements always can construct a valid URI, even if it is synthetic.

## Always meaningful enums

Enum `permissible_values` always have a meaning, even if `meaning` is not explicitly defined.
Thanks to this, enum values can always be represented as IRIs in RDF, and LinkML-Scala does not allow string-based enums.

## Additional identifier constraints

The identifier slot for classes must have a scalar `type` range.
It is an error to have an `enum` or `class` identifier.

## Extra data

As in the [metamodel](https://w3id.org/linkml/extra_slots), instances of a class may only have data for its slots, unless the class allows more with `extra_slots`:

```yaml
classes:
  Person:
    extra_slots:
      allowed: true
    attributes:
      name: {}
```

LinkML-Scala honors `extra_slots` when decoding with generated Scala classes, as well as in the JSON Schema (`additionalProperties`), SHACL (`sh:closed`), TypeScript (`[key: string]: unknown`) and Pydantic (`extra="allow"`) generators.
Python LinkML 1.11.1 does not read `extra_slots`, and closes all classes in its JSON Schema.

- `allowed: true` allows any extra data.
- `allowed: false`, or no `extra_slots`, forbids it. An explicit `allowed: false` wins over a `range_expression`.
- A `range_expression` alone allows extra data matching it. LinkML-Scala allows any extra data then, without checking it against the expression.
- `extra_slots` is not inherited: a subclass of a class allowing extra data forbids it, unless its own `extra_slots` allows it too.

The `--open` option of the JSON Schema, SHACL, TypeScript and Pydantic generators allows extra data in all classes.

In LinkML-Scala, `extra_slots` only controls validation. When decoding, allowed extra data is accepted and then discarded: generated Scala classes have a fixed set of fields, so there is nowhere to store it, and encoding the object again will not write it back. Python LinkML can attach unknown attributes to an object at runtime, which Scala classes cannot do.


LinkML-Scala provides a `tree_root_as` extension for classes, which allows specifying how the `tree_root` class will be laid out.
For example, this allows specifying that the root of a JSON document should be a JSON array with instances of this class:

```yaml
SomeClass:
  tree_root: true
  extensions:
    tree_root_as: list
  attributes:
    id:
      range: string
      identifier: true
    value:
      range: integer
      required: true
```

JSON Schema generated from this LinkML schema accepts: 

```json
[
  { "id": "1", "value": 1 }, 
  { "id": "2", "value": 2 }, 
  { "id": "3", "value": 3 }
]
```

But rejects:

```json
{ "id": "1", "value": 1 }
```

`tree_root_as` accepts the following options:

* `plain`: object instance, 
* `optional`: object instance or null, 
* `list`: array of object instances, may be empty, 
* `compact_dict`: dict object mapping the key field to the identifier-optional object instances
* `simple_dict`: dict object mapping the key field to the value field of the object instances


## Language strings

LinkML-Scala uses a [fork of the metamodel](https://github.com/linkml/linkml-model/compare/main...NeverBlink-OSS:linkml-model:main) that adds support for language strings in LinkML schemas.

The `title` and `description` of schema elements can be a plain string or a mapping from language tag to text:

```yaml
classes:
  Book:
    title:
      en: Book
      pl: Książka
      de: Buch
    description:
      en: It's a book, obviously.

  Person:
    title: Person
    description: You can also still use the plain string syntax!
```

The RDFS and SHACL generators emit plain strings as `xsd:string` literals and language mappings as `rdf:langString` literals:

```turtle
library:Book a rdfs:Class ;
  rdfs:label "Book"@en , "Książka"@pl , "Buch"@de ;
  rdfs:comment "It's a book, obviously."@en .
  
library:Person a rdfs:Class ;
  rdfs:label "Person" ;
  rdfs:comment "You can also still use the plain string syntax!" .
```

Schemas can also use language strings in their own data using the `langstring` type imported from `linkml:types`.
The value is a plain string or a non-empty mapping from language tag to text.

## Inline type semantics

Different forms:
- `Plain` - In JSON the inlined class is always present. In RDF there MUST always be exactly one property representing this slot.
- `Optional` - in JSON the inlined class may be present, may be omitted or may be null. In RDF there MUST be at most 1 property. 
- `List` - In JSON this is an array, which may be an empty array, may be omitted or may be null. No assumed constraints in RDF.
- `Dict(Form)` - In JSON this is a `form` (`SimpleDict` or `CompactDict`) object, which may be omitted, may be an empty object or may be null. No assumed constraints in RDF.

A slot can explicitly inline (`inlined: true`) a class, or implicitly inline a class, if the class does not have an identifier.
LinkML-Scala uses the following logic when a slot `s` is inlining a class `c`:

- If `s` is not multivalued:
  - If `s` is required, then the inline type is `Plain`,
  - If `s` is not required, then the inline type is `Optional`.
- If `s` is multivalued:
  - If `s` has `inlined_as_list`, then the inline type is `List`,
  - Else if `c` does not have an `identifier` or `key` slot, then the inline type is `List`,
  - Else if `c` has 2 slots in total or 2 required slots, then the inline type is `SimpleDict`,
  - Otherwise, the inline type is `CompactDict`.

## Null semantics

We're working on strict null semantics for LinkML instances - formalizing the usages of null, compatible with the expected metamodel structure.

(Not yet fully implemented)

CompactDict / SimpleDict:

- `{ key: null }` (canonical) = `{ key: {} }`
  - Means `Map("key" -> RangeClass("key"))`
- `{ key: [] }` not allowed
- `<omitted>` (canonical) = `{}` = `null`
  - Means empty collection `Map()`

List:

- `[ {} ]`
  - Means a single instance with all default values `Seq(RangeClass())`
- `[ null ]` not allowed
- `{}` not allowed
- `<omitted>` (canonical) = `null` = `[]`
    - Means empty collection

Optional:

- `{}`
  - Means slot is defined, with all default values `Some(RangeClass())`
- `<omitted>` (canonical) = `null`
  - Means `None`
- `[]` not allowed
