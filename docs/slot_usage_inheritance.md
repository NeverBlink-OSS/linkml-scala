# Inherited slots and `slot_usage`

A class can get the metaslots of a slot from several places: its own `slot_usage`, the `slot_usage` of its parent classes and mixins, and the slot definition.
This page shows how LinkML-Scala combines three kinds of them, where the LinkML specification, LinkML-Python 1.12.1 and LinkML-Scala do not all agree:

- [Boolean metaslots](#boolean-metaslots), like `required` and `multivalued`, which are `true` or `false`.
  LinkML-Scala follows the specification here, and LinkML-Python works differently.
- [Boolean expressions](#boolean-expressions): `any_of`, `all_of`, `exactly_one_of` and `none_of`, which are lists of conditions.
  LinkML-Scala works differently from both the specification and LinkML-Python here.
- [Value bounds](#value-bounds): `minimum_value` and `maximum_value`.
  LinkML-Scala follows the specification here, and also uses it for types and unions. LinkML-Python follows it only for `slot_usage`.

## Boolean metaslots

The LinkML specification combines boolean metaslots, like `required` and `multivalued`, with OR when it derives a slot.
So a `slot_usage` can make a slot required or multivalued, but setting either to `false` has no effect when the slot or a parent class already sets it to `true`.
LinkML-Scala does the same. LinkML-Python 1.12.1 uses the value from the closest `slot_usage` instead.

```yaml
classes:
  Base:
    slots:
      - name
      - tags
  Child:
    is_a: Base
    slot_usage:
      name:
        required: false
      tags:
        multivalued: false
slots:
  name:
    required: true
  tags:
    multivalued: true
```

| Slot of `Child` | LinkML-Python 1.12.1 | LinkML-Scala |
|-----------------|----------------------|--------------|
| `name` | Optional | Required |
| `tags` | Single-valued | Multivalued |

So `{}` is a valid `Child` for `linkml-validate`, but not for the JSON Schema, SHACL, Pydantic or TypeScript that LinkML-Scala generates.
To make a slot optional or single-valued in some classes, leave it optional or single-valued in the slot definition, and turn it on in the classes that need it.

## Boolean expressions

LinkML-Scala combines the `any_of`, `all_of`, `exactly_one_of` and `none_of` lists of a slot so that a value of the class is also a valid value of each of its parent classes and mixins (Liskov substitution).

| Expression       | LinkML specification | LinkML-Python 1.12.1              | LinkML-Scala                                                               |
|------------------|----------------------|-----------------------------------|----------------------------------------------------------------------------|
| `all_of`         | Joins the lists      | Keeps only the most specific list | Joins the lists                                                            |
| `none_of`        | Joins the lists      | Keeps only the most specific list | Joins the lists                                                            |
| `any_of`         | Joins the lists      | Keeps only the most specific list | Keeps the most specific list, and requires the other lists too (see below) |
| `exactly_one_of` | Joins the lists      | Keeps only the most specific list | Keeps the most specific list, and requires the other lists too (see below) |

Joining the lists is correct for `all_of` and `none_of`: each member is one more check.
For `any_of` and `exactly_one_of`, neither joining nor keeping one list is correct:

- Joining `any_of` lists allows more values. A subclass can't narrow down the ranges.
- Keeping only the most specific `any_of` list allows a subclass to add ranges that a parent class doesn't allow.
- An `exactly_one_of` with fewer members can allow more values: a value that matches two inherited members is not valid for the parent class, but it is valid for a list with only one of these members.

So LinkML-Scala keeps the most specific list, and adds each other list to `all_of`, as a member with this list as its `any_of` or `exactly_one_of`.
An `any_of` list is not added if it has all the members of the most specific list, because then it allows all the values that the most specific list allows.

```yaml
classes:
  Base:
    slots:
      - value
  Child:
    is_a: Base
    slot_usage:
      value:
        any_of:
          - range: integer
          - range: boolean

slots:
  value:
    any_of:
      - range: integer
      - range: string
```

| Value  | LinkML specification (`any_of` of `integer`, `string`, `boolean`) | LinkML-Python 1.12.1 (`any_of` of `integer`, `boolean`) | LinkML-Scala (both `any_of` lists) |
|--------|-------------------------------------------------------------------|---------------------------------------------------------|------------------------------------|
| 42     | Valid                                                             | Valid                                                   | Valid                              |
| "abc"  | Valid                                                             | Invalid                                                 | Invalid                            |
| true   | Valid                                                             | Valid, but not valid for `Base`                         | Invalid                            |

For `Child.value`, LinkML-Scala uses this derived slot:

```yaml
any_of:
  - range: integer
  - range: boolean
all_of:
  - any_of:
      - range: integer
      - range: string
```

The SHACL generator emits all of it.
The OWL generator gives each class its own `any_of` ranges; OWL subclassing requires the ranges of the parent classes too.
The JSON Schema and Pydantic generators do not support boolean expressions yet.

## Value bounds

A bound can narrow the values that a slot allows, but not widen them.
So when a slot gets `minimum_value` or `maximum_value` from more than one place, all of them hold: the largest minimum and the smallest maximum.
The LinkML specification combines slots this way: `max(v1, v2)` for `minimum_value` and `min(v1, v2)` for `maximum_value`.
LinkML-Scala uses the same rule for each place a bound can come from:

- the `slot_usage` of the class, its parent classes and its mixins;
- the slot definition and its parent slots;
- the range type and its `typeof` parents;
- each `union_of` type on the way to a member, and the member type.

```yaml
types:
  Small:
    typeof: integer
    maximum_value: 10
  Bigger:
    typeof: Small
    maximum_value: 100
classes:
  Base:
    slots:
      - count
    slot_usage:
      count:
        maximum_value: 10
  Child:
    is_a: Base
    slot_usage:
      count:
        maximum_value: 100
    attributes:
      size:
        range: Bigger
      amount:
        range: Small
        maximum_value: 100
slots:
  count:
    range: integer
```

| Slot of `Child` | LinkML-Python 1.12.1 | LinkML-Scala |
|-----------------|----------------------|--------------|
| `count`         | at most 10           | at most 10   |
| `size`          | at most 100          | at most 10   |
| `amount`        | at most 100          | at most 10   |

LinkML-Python takes the nearest bound for a type, a slot over its range type and a parent slot.
So 50 is a valid `size` or `amount` for it, though it isn't a valid `Small`.

Only numbers can be compared.
For other bounds, such as dates, LinkML-Scala takes the nearest one.
