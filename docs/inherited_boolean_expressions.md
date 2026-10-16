# Inherited boolean expressions

A class can get a boolean slot (`any_of`, `all_of`, `exactly_one_of`, `none_of`) of a slot from several places: its own `slot_usage`, the `slot_usage` of its parent classes and mixins, and the slot definition.
LinkML-Scala combines them so that a value of the class is also a valid value of each of its parent classes and mixins (Liskov substitution).

| Boolean slot     | LinkML specification | LinkML-Python 1.12.1              | LinkML-Scala                                                               |
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
