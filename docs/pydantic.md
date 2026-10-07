# Pydantic

The Pydantic generator turns a LinkML schema into Python classes for your data, based on
[pydantic](https://docs.pydantic.dev/) v2. The classes load and dump exactly the JSON that is described by the
generated JSON Schema, and they check the schema while loading. The output is a single `.py` file:

- It needs Python 3.10 or later and pydantic 2.11 or later. Apart from pydantic, it imports only
  the standard library.
- Loading checks the data the way the JSON Schema does: required slots, types, enums, patterns,
  minimum and maximum values, and cardinality. Unknown keys are rejected.
- Dumping gives back the JSON you loaded, with the exceptions listed under
  [Limitations](#limitations).

## Generating

From the command line:

```shell
linkml-scala generate pydantic --to model.py schema.yaml
```

Options:

- `--pruning-mode <treeRoot|schema|skip>`: which classes and enums to generate. Default: `skip`,
  i.e. all of them. The parents of the included classes are always generated too.
- `--tree-root <Class>`: with `--pruning-mode treeRoot`, use this class as the tree root.
- `--include-null`: keep the `null`s that were loaded or set when dumping, which this option of
  the JSON Schema generator allows.
- `--open`: accept and keep extra properties on every object.

## Loading and dumping data

```python
from model import Zoo

zoo = Zoo.model_validate_json(text)   # load, raises pydantic.ValidationError if invalid
text2 = zoo.model_dump_json()         # dump
```

`model_dump_json()` skips the fields without a value, so you don't need to set `exclude_none=True`. You can also build objects in Python code and dump them:

```python
from datetime import date

from model import Keeper, Lion

keeper = Keeper(id="k1", name="Ann", since=date(2020, 1, 2))
lion = Lion(name="Leo")   # kind="Lion" is filled in automatically
```

If the tree root isn't a single object (`tree_root_as` is `list`, `optional`, or a dict), or if it's a class with a type designator, the file also has a document class for the whole JSON document, named after the tree root: `ZooDocument.model_validate_json(text)`. Its value is in `.root`.

## Example

This schema (the same one as in the [TypeScript guide](typescript.md#example)):

```yaml
classes:
  Zoo:
    tree_root: true
    attributes:
      name:
        required: true
      animals:
        range: Animal
        multivalued: true
        inlined_as_list: true
      keepers:
        range: Keeper
        multivalued: true
        inlined: true
  Animal:
    abstract: true
    attributes:
      kind:
        designates_type: true
        required: true
      name:
        required: true
      keeper:
        range: Keeper
  Lion:
    is_a: Animal
    attributes:
      mane_length:
        range: float
  Parrot:
    is_a: Animal
    attributes:
      words:
        multivalued: true
  Keeper:
    description: A person who looks after animals.
    attributes:
      id:
        identifier: true
      name:
      since:
        range: date
      shift:
        range: Shift
enums:
  Shift:
    permissible_values:
      day:
      night:
```

generates the following, after the imports and a few helpers that every file starts with:

```python
class Shift(str, Enum):
    day = "day"
    night = "night"


class Animal(LinkMLModel):
    keeper: str | None = None
    """Reference to Keeper"""
    kind: str
    name: str


class Keeper(LinkMLModel):
    """A person who looks after animals."""

    id: str
    name: str | None = None
    shift: Shift | None = None
    since: JsonDate | None = None


class Lion(Animal):
    keeper: str | None = None
    """Reference to Keeper"""
    kind: Literal["Lion"] = "Lion"
    mane_length: StrictFloat | None = None
    name: str


class Parrot(Animal):
    keeper: str | None = None
    """Reference to Keeper"""
    kind: Literal["Parrot"] = "Parrot"
    name: str
    words: list[str] | None = None


class Zoo(LinkMLModel):
    animals: list[AnyAnimal] | None = None
    keepers: Annotated[dict[str, Keeper], _with_keys("id"), _without_keys("id")] | None = None
    name: str


AnyAnimal = Annotated[
    Union[Lion, Parrot],
    Field(discriminator="kind"),
]
"""Any subclass of Animal, told apart by `kind`."""
```

Loading `{"name": "Z", "animals": [{"kind": "Lion", "name": "Leo"}]}` gives a `Zoo` whose first animal is a `Lion`.

## How the schema maps to Python

### Classes

Every class becomes a class. It inherits from its `is_a` parent and its mixins, in that order.
A class without parents inherits from `LinkMLModel`, the base class at the top of the file.

### Names

The JSON keys are the same as in the JSON Schema: the slot's `alias` if it has one, otherwise
the slot's name. A field uses the JSON key as its Python name if it can. Otherwise:

- characters that aren't allowed become `_`: `my-slot` becomes `my_slot`
- leading underscores are removed, since pydantic treats such names as private: `_id` becomes `id`
- a leading digit gets `field_` in front: `1st` becomes `field_1st`
- a Python keyword, a `BaseModel` attribute (such as `json` or `model_config`), or a name the
  file uses for a type (such as `date` or `str`) gets `_` at the end: `class` becomes `class_`

Each field keeps its JSON key as its pydantic `alias`, and dumping to JSON always use this key. Loading accepts either: `{"my-slot": ...}` and `{"my_slot": ...}` both load.

### Slot types

| LinkML range                                               | Python                         |
|------------------------------------------------------------|--------------------------------|
| `string`, `uri`, `curie`, `uriorcurie`, `ncname` and types based on them | `str`            |
| `integer`                                                  | `StrictInt`                    |
| `float`, `double`                                          | `StrictFloat`                  |
| `decimal`                                                  | `JsonDecimal`: a `Decimal`, read from and written as a JSON number |
| `boolean`                                                  | `StrictBool`                   |
| `date`, `datetime`, `time`                                 | `JsonDate`, `JsonDateTime`, `JsonTime`: `date`, `datetime` and `time`, read from ISO 8601 text only |
| `linkml:Any`                                               | `Any`                          |
| an enum                                                    | the enum's class               |
| a class, not inlined                                       | the type of the class's identifier, with a "Reference to X" docstring |
| a class, inlined                                           | the class                      |
| a class, inlined as a list                                 | `list[X]`                      |
| a class, inlined as a dict                                 | `dict[str, X]` (see below)     |

A multivalued slot of any other range becomes a `list`. An optional slot is `T | None` and
defaults to `None`.

### Null and missing values

Loading accepts `null` for an optional slot, the same as the key not being present entirely. Dumps by default skip every field without a value, so `{"name": null}` is saved as `{}`. With the `--include-null` option, dumps keep the `null`s that were loaded or set, and skip only the fields that were never set.

### Extra properties

With the `--open` option, objects accept keys that the schema doesn't have, and keep them: they are dumped again as they were.

## Limitations

- These are not supported, the same as in the JSON Schema generator: boolean expressions
  (`any_of`, `exactly_one_of`, `all_of`, `none_of`), `equals_string`, `equals_number`,
  `structured_pattern`, `rules`, `unique_keys`, `union_of`, arrays, `ifabsent` and
  `equals_expression`.
- Dumping returns the same JSON, except that:
  - dates and times can be written differently: `2020-01-01T00:00:00.000+00:00` is saved as
    `2020-01-01T00:00:00Z`
  - a simple dict entry written as an object is returned in the short form, if it can
  - `5` in a float slot is saved as `5.0`
  - a `null` dict entry is saved as `{}`
- Pydantic reads JSON numbers through `float`, so a decimal with more than about 16 digits loses
  the rest.
