# LinkML <-> Apache Ossie ontology mapping

How the `ossie` generator and importer map between LinkML and [Apache Ossie](https://github.com/apache/ossie) ontologies (`ontology/ontology.json`, version `0.2.0.dev0`).

## Document

| From LinkML                                    | Ossie field         | To LinkML     |
|------------------------------------------------|---------------------|---------------|
| Hardcoded `0.2.0.dev0`                         | `version`           | **Not read.** |
| `name`                                         | `name`              | Inverse.      |
| `description`                                  | `description`       | Inverse.      |
| One component per class, enum, and named type. | `ontology`          | Inverse.      |
| `extensions.ai_context`                        | `ai_context`        | Inverse.      |
| **Not emitted.**                               | `requires`          | **Not read.** |
| **Not emitted.**                               | `ontology_mappings` | **Not read.** |

By default, the importer invents an identifier for the LinkML schema as `https://example.org/` + `name` in snake_case, unless the caller supplies it.

## Concept (`OntologyComponent`)

| From LinkML                                                                          | Ossie field     | To LinkML                                                                                                         |
|--------------------------------------------------------------------------------------|-----------------|-------------------------------------------------------------------------------------------------------------------|
| Element name in PascalCase.                                                          | `concept`       | Inverse. Original spelling kept in `alias`.                                                                       |
| `EntityType` for classes, `ValueType` for enums and types.                           | `type`          | Inverse.                                                                                                          |
| Element `description`, in `--metadata-language`.                                     | `description`   | Inverse.                                                                                                          |
| Classes: `is_a` + `mixins`. Enums and types: the built-in value type for their base. | `extends`       | Last entry is `is_a`, the rest are `mixins`. For a value type it is `typeof`, or `string` if the base is unknown. |
| `identifier`, `key`, or `unique_keys`                                                | `identify_by`   | One entry, `OneToOne`, or scalar range → `identifier`. Otherwise `unique_keys`.                                   |
| Required slots: `Concept.relationship`. Enums and named types: see Expressions.      | `requires`      | Inverse. Any other restriction is ignored.                                                                        |
| The class' derived slots, minus the ones stated identically in a supertype.          | `relationships` | One attribute each.                                                                                               |
| **Not emitted.**                                                                     | `derived_by`    | **Not read.**                                                                                                     |

Abstract classes and mixins become ordinary concepts – Ossie has no abstract elements.

A subtype only declares what it does not inherit unchanged. If it narrows a slot (a tighter range or `required` where the supertype had it optional), the relationship is declared again on the subtype.

## Relationship

One per derived slot of the class. Identified as `Concept.name`, so names only need to be unique within their concept.

| From LinkML                                                                       | Ossie field            | To LinkML                                                                                            |
|-----------------------------------------------------------------------------------|------------------------|------------------------------------------------------------------------------------------------------|
| Slot `alias`, else slot name in snake_case.                                       | `name`                 | Slot name in snake_case, original spelling in `alias`.                                               |
| `{Concept} <title or space-case name> {Range}`                                    | `verbalizes`           | The phrase becomes the slot `title`, unless it is just the space-cased name. Only the first is read. |
| Slot `description`, in `--metadata-language`.                                     | `description`          | Inverse.                                                                                             |
| Always exactly one, played by the slot's range concept.                           | `roles`                | The first role's concept is the range. Any others are dropped.                                       |
| Single-valued identifier/key slot → `OneToOne`. Others → `ManyToOne`.             | `multiplicity`         | Absent → `multivalued: true`.                                                                        |
| Slot `minimum_value` / `maximum_value` / `pattern`, as expressions over the role. | `requires`             | Inverse.                                                                                             |
| **Not emitted.**                                                                  | `derived_by`           | **Not read.**                                                                                        |
| `rank` of the slot                                                                | *(relationship order)* | Inverse.                                                                                             |

### Roles

A role's `name` is only emitted when the player would otherwise be ambiguous – i.e. a slot whose
range is its own class. `Person.parent_of` becomes `roles: [{concept: Person, name: parent_of}]` and verbalizes as `{Person} parent of {Person:parent_of}`, per the spec's disambiguation rule.

## Ranges

Primitive LinkML types map straight onto Ossie's built-in concepts:

| From LinkML                                      | Ossie      | To LinkML                            |
|--------------------------------------------------|------------|--------------------------------------|
| `string`, `uri`, `uriorcurie`, `curie`, `ncname` | `String`   | `string`                             |
| `integer`                                        | `Integer`  | `integer`                            |
| `float`, `double`                                | `Float`    | `float`                              |
| `decimal`                                        | `Decimal`  | `decimal`                            |
| `boolean`                                        | `Boolean`  | `boolean`                            |
| `date`                                           | `Date`     | `date`                               |
| `datetime`                                       | `DateTime` | `datetime`                           |
| `time`                                           | `String`   | `string`                             |
| localized text                                   | `String`   | `string`                             |
| `linkml:Any`, unknown base                       | `Any`      | A class with `class_uri: linkml:Any` |

Several LinkML types share one Ossie concept, so the last column is not the inverse of the first: a `uri` comes back as a `string`, and a `double` as a `float`.

A non-primitive LinkML type becomes its own `ValueType` concept extending a built-in.

An **enum** becomes a `ValueType` extending `String`, with its permissible values as a `requires` expression. Enum inheritance and dynamic enums are not mapped.

## Expressions

| From LinkML                      | Ossie                   | To LinkML |
|----------------------------------|-------------------------|-----------|
| `required: true`                 | `Concept.relationship`  | Inverse.  |
| `permissible_values`             | `Ref IN ('a', 'b')`     | Inverse.  |
| `minimum_value`, `maximum_value` | `Ref >= v`, `Ref <= v`  | Inverse.  |
| `pattern`                        | `REGEXP_LIKE(Ref, 'p')` | Inverse.  |

## Not mapped from LinkML

Ossie does not support arbitrary extensions, so the following LinkML features are not mapped:

- URIs and CURIEs of any kind (`class_uri`, `slot_uri`, `prefixes`)
- `deprecated`, `annotations`, `extensions`, `subsets`, `see_also` and other metadata
- `unit`, `default` / `ifabsent`, `recommended`
- Cardinality counts (`minimum_cardinality`, `maximum_cardinality`)
- `rules`, `any_of` / `all_of` / `none_of` / `exactly_one_of`
- Type designators, arrays
