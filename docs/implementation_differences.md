# Implementation differences

## Eager validation of references

All LinkML references (like `slot_name` in `slots: [ slot_name ]`) are eagerly checked when creating the SchemaView.
SchemaView is not able to proceed with derivation if this requirement is not satisfied.

## Always meaningful enums

Enum permissible values in LinkML-Scala always have a meaning.
If `meaning` is not explicitly defined, then a synthetic (`default_prefix + text`) meaning will be assigned.

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


## Tree root extension

LinkML-Scala provides a `tree_root_as` extension for classes, which allows specifying how the `tree_root` class will be laid out.
For example, this allows specifying that the root of a JSON document should be a JSON array with instances of this class. 

## Additional identifier constraints

LinkML-Scala additionally requires the identifier slot for classes to always have a scalar `type` range.
