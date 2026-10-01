# TypeScript

The TypeScript generator turns a LinkML schema into TypeScript types for your data. The types
describe exactly the JSON that the generated JSON Schema describes, so they work with
`JSON.parse` and `JSON.stringify`. The output is a single `.ts` file:

- It has **no runtime dependencies**, no imports and no functions. It's only types.
- It uses only syntax that TypeScript can erase (no `enum`, no `namespace`). So it also works
  with Node's built-in type stripping and with `erasableSyntaxOnly`.

## Generating

From the command line:

```shell
linkml-scala generate typescript --to model.ts schema.yaml
```

Options:

- `--pruning-mode <treeRoot|schema|skip>`: which classes and enums to generate (see
  [Pruning](#pruning)). Default: `skip`, i.e. all of them.
- `--tree-root <Class>`: with `--pruning-mode treeRoot`, use this class as the tree root.
- `--include-null`: allow `null` for optional slots.
- `--open`: allow extra properties on every object.

## Loading and dumping data

Parse the JSON and tell TypeScript what it is:

```ts
import type { Zoo, Keeper } from "./model.ts";

const zoo = JSON.parse(text) as Zoo;          // load
const text2 = JSON.stringify(zoo, null, 2);   // dump
```

The cast is not checked at runtime. If the JSON doesn't fit the schema, you find out only when
your code reads a field that isn't what the type says. If the data comes from somewhere you
don't trust, validate it first with the JSON Schema from `generate json-schema`.

## Example

This schema:

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

generates:

```ts
export type Animal =
  | Lion
  | Parrot;

/** A person who looks after animals. */
export interface Keeper {
  id: string;
  name?: string;
  shift?: Shift;
  since?: string;
}

export interface Lion {
  /** Reference to Keeper */
  keeper?: string;
  kind: "Lion";
  mane_length?: number;
  name: string;
}

export interface Parrot {
  /** Reference to Keeper */
  keeper?: string;
  kind: "Parrot";
  name: string;
  words?: string[];
}

export interface Zoo {
  animals?: Animal[];
  keepers?: Record<string, KeyOptional<Keeper, "id">>;
  name: string;
}

export type Shift =
  | "day"
  | "night";

/** The object `T`, with the key slot `K` optional, as used in dict values. */
export type KeyOptional<T, K extends keyof T> = T extends unknown
  ? Omit<T, K> & Partial<Pick<T, K>> extends infer O ? { [P in keyof O]: O[P] } : never
  : never;
```

## How the schema maps to TypeScript

### Classes

Every class becomes an `interface`. The interface lists **all** of the class's slots, including
the ones it inherits through `is_a` and mixins. There's no `extends`. TypeScript compares
objects by their shape, so a `Lion` still fits wherever its parent type is expected.

Property names are the JSON keys. They are the same as in the JSON Schema: the slot's `alias`
if it has one, otherwise its name in `snake_case`. A key that isn't a valid identifier is
quoted, e.g. `"odd-name"?: string`.

### Slot types

| LinkML range                                                   | TypeScript          |
|----------------------------------------------------------------|---------------------|
| `string`, `date`, `datetime`, `time`, `uri`, `curie`, `uriorcurie`, `ncname` and types based on them | `string` |
| `integer`, `float`, `double`, `decimal`                        | `number`            |
| `boolean`                                                      | `boolean`           |
| `linkml:Any`                                                   | `unknown`           |
| an enum                                                        | the enum's type     |
| a class, not inlined                                           | the type of the class's identifier, with a "Reference to X" comment |
| a class, inlined                                               | the class's type    |
| a class, inlined as a list                                     | `X[]`               |
| a class, inlined as a dict (compact form)                      | `Record<string, KeyOptional<X, "id">>` |
| a class, inlined as a dict (simple form)                       | `Record<string, V \| KeyOptional<X, "id">>`, where `V` is the type of the value slot |

A multivalued slot of any other range becomes an array, e.g. `string[]`.

In the dict forms the key of each entry is the object's identifier, so the identifier is
optional inside the object. `KeyOptional<X, "id">` is `X` with `id` made optional. It is only
added to the file when it's used.

With `--include-null`, optional properties also accept `null` (`name?: string | null`), the same
as the JSON Schema option.

### Enums

An enum becomes a union of its values: `export type Shift = "day" | "night";`. An enum without
permissible values (e.g. a dynamic enum) becomes `string`.

### Names

Classes and enums get `PascalCase` names. A name that starts with a digit or is reserved in
TypeScript gets a `_` prefix (e.g. a class `Record` becomes `_Record`).
