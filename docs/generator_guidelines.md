# Generator guidelines

Choose top level elements you want your target to represent: `classes`, `enums`, `types` and `slots`.
Formats must at least support classes - but they may choose to stop at the `tree_root` class.

If you're working with derived models, use the AttributeView classes.
They contain utilities for working with the ranges of slots,
resolving references ahead of time, bundling the relevant ElementViews.

## Schema inline / reference

Prefer using in-artifact references instead of inlining large definitions.
This is especially important for `classes` and `enums`, whose definitions can be very large.

For example, JSON Schema uses `$defs` and `$ref` to handle inline classes and enums.
Remember: `inlined: true` refers to the data instance, not the schema!

Note: types and slots 

## Generate vs Serialize

If possible, prefer using an intermediate models to separate serialization from construction.
The `generate` method of the generator should output instances of this intermediate model.
The `serialize` method of the generator should output serialized instances, in some format. 

## Overrides

Generators should provide options to override certain behaviors and parameters, most notably:

- `tree_root` class selection
- `tree_root_as` mode selection 
- Schema pruning, generation scope
