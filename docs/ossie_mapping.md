# LinkML <-> Apache Ossie ontology mapping

How the `ossie` generator and importer map between LinkML and [Apache Ossie](https://github.com/apache/ossie) ontologies (`ontology/ontology.json`, version `0.2.0.dev0`).

## Document

| Ossie field         | From LinkML                                    |
|---------------------|------------------------------------------------|
| `version`           | Hardcoded `0.2.0.dev0`                         |
| `name`              | `name`                                         |
| `description`       | `description`                                  |
| `ontology`          | One component per class, enum, and named type. |
| `ai_context`        | `extensions.ai_context`                        |
| `requires`          | **Not emitted.**                               |
| `ontology_mappings` | **Not emitted.**                               |

## Concept (`OntologyComponent`)

| Ossie field     | From LinkML                                                                                                                                          |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `concept`       | Element name in PascalCase.                                                                                                                          |
| `type`          | `EntityType` for classes, `ValueType` for enums and types.                                                                                           |
| `description`   | Element `description`, in `--metadata-language`.                                                                                                     |
| `extends`       | Classes: `is_a` + `mixins`. Enums and types: the built-in value type for their base.                                                                 |
| `identify_by`   | `identifier`, `key`, or `unique_keys`                                                                                                                |
| `requires`      | Required slots, as `Concept.relationship`. Enums: `Concept IN ('a', 'b')`. Named types also get their `minimum_value` / `maximum_value` / `pattern`. |
| `relationships` | The class' derived slots, minus the ones stated identically in a supertype.                                                                          |
| `derived_by`    | **Not emitted.**                                                                                                                                     |

Abstract classes and mixins become ordinary concepts – Ossie has no abstract elements.

A subtype only declares what it does not inherit unchanged. If it narrows a slot (a tighter range or `required` where the supertype had it optional), the relationship is declared again on the subtype.

## Relationship

One per derived slot of the class. Identified as `Concept.name`, so names only need to be unique within their concept.

| Ossie field    | From LinkML                                                                        |
|----------------|------------------------------------------------------------------------------------|
| `name`         | Slot `alias`, else slot name in snake_case.                                        |
| `verbalizes`   | `{Concept} <title or space-case name> {Range}`                                     |
| `description`  | Slot `description`, in `--metadata-language`.                                      |
| `roles`        | Always exactly one, played by the slot's range concept.                            |
| `multiplicity` | Single-valued identifier/key slot → `OneToOne`. Other single-valued → `ManyToOne`. |
| `requires`     | Slot `minimum_value` / `maximum_value` / `pattern`, as expressions over the role.  |
| `derived_by`   | **Not emitted.**                                                                   |

### Roles

A role's `name` is only emitted when the player would otherwise be ambiguous – i.e. a slot whose
range is its own class. `Person.parent_of` becomes `roles: [{concept: Person, name: parent_of}]` and verbalizes as `{Person} parent of {Person:parent_of}`, per the spec's disambiguation rule.

## Ranges

Primitive LinkML types map straight onto Ossie's built-in concepts:

| LinkML                                           | Ossie      |
|--------------------------------------------------|------------|
| `string`, `uri`, `uriorcurie`, `curie`, `ncname` | `String`   |
| `integer`                                        | `Integer`  |
| `float`, `double`                                | `Float`    |
| `decimal`                                        | `Decimal`  |
| `boolean`                                        | `Boolean`  |
| `date`                                           | `Date`     |
| `datetime`                                       | `DateTime` |
| `time`                                           | `String`   |
| localized text                                   | `String`   |
| `linkml:Any`, unknown base                       | `Any`      |

A non-primitive LinkML type becomes its own `ValueType` concept extending a built-in.

An **enum** becomes a `ValueType` extending `String`, with its permissible values as a `requires` expression. Enum inheritance and dynamic enums are not mapped.

## Not mapped from LinkML

Ossie does not support arbitrary extensions, so the following LinkML features are not mapped:

- URIs and CURIEs of any kind (`class_uri`, `slot_uri`, `prefixes`)
- `deprecated`, `annotations`, `extensions`, `subsets`, `see_also` and other metadata
- `unit`, `default` / `ifabsent`, `recommended`
- Cardinality counts (`minimum_cardinality`, `maximum_cardinality`)
- `rules`, `any_of` / `all_of` / `none_of` / `exactly_one_of`
- Type designators, arrays
