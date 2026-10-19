# Boolean metaslots in `slot_usage`

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
