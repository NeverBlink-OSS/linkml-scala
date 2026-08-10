# Reviewing a LinkML schema

The validator catches what is *broken*. This skill catches what is *unwise* — choices that
validate cleanly but will hurt later.

**Start by running the validator anyway.** There is no point discussing naming while the schema
has a fatal error, and its findings frame everything else:

```shell
linkml-scala validate --strict --format json schema.yaml
linkml-scala generate linkml --pruning-mode skip schema.yaml   # the effective model
```

The second command is the one that makes a real review possible: it resolves imports and
materialises inherited slots into attributes, so you review what the schema *means* rather than
what it appears to say. Inheritance and `slot_usage` routinely hide surprises. Review both.

**Rank findings by consequence, and separate the two kinds.** Things that will break consumers
of the schema come first; style comes last. Be specific — quote the element and give the fix.
Do not pad the list: five real findings beat twenty nits, and a schema with nothing wrong should
be told so.

## What to check

### 1. Unsupported features (highest priority)

Anything from [200-limitations.md](200-limitations.md) that appears in the schema is a real defect, because it
validates but does not do what the author expects:

```shell
grep -nE '^\s*(rules|any_of|none_of|all_of|exactly_one_of|reachable_from|designates_type):' schema.yaml
grep -nE '^\s*(ifabsent|equals_expression|array|minus|inherits|include):' schema.yaml
```

`rules:` and boolean expressions are the common traps. Report each with the line and what will
actually happen — usually "silently ignored by every generator except SHACL".

### 2. Identifiers and keys

- Does every class that is referenced from elsewhere have an `identifier` or `key`? Without one
  it gets inlined by value, which is usually not what was intended for an entity.
- Is each identifier's range a **scalar type**? A class or enum range is an error in
  linkml-scala.
- Is the identifier genuinely unique and stable? An identifier that can change breaks every
  reference to it.
- Conversely, does a value object (an address, a measurement) have an identifier it does not
  need? That forces reference semantics where inlining was wanted.

### 3. tree_root

Exactly one class in the root schema should have `tree_root: true`. None means a warning and
generators guessing; more than one is an error. Check the choice is the real document root, not
just the most important class — and consider whether `tree_root_as` (`list`, `compact_dict`,
`simple_dict`) matches the intended document shape. See [200-limitations.md](200-limitations.md).

### 4. Inlining

Walk every slot whose range is a class and confirm the inferred inlining is what was wanted —
this is the most common source of "the generated JSON looks wrong". The rules are inference, not
declaration, so the author may never have thought about it. Generate JSON Schema and read the
result rather than reasoning it out. Multivalued inlined slots without `inlined_as_list: true`
become dicts keyed by identifier, which surprises nearly everyone the first time.

### 5. Reuse and inheritance

- Are the same attribute name and range repeated across classes? That is a candidate for a
  top-level `slot`, or a `mixin` when a cluster of slots travels together.
- Is `is_a` used where the subclass is not genuinely a kind of the parent? Prefer `mixins` for
  cross-cutting traits and keep `is_a` for real specialisation.
- Is inheritance deeper than about three levels? It gets hard to reason about, and
  `generate linkml` output gets hard to read.
- Does `slot_usage` narrow a slot in a way that contradicts the parent — widening a range, or
  making a required slot optional? Narrowing is fine; widening is a modelling error.

### 6. External vocabulary mappings

Schemas that map onto nothing are islands. Check for `class_uri`, `slot_uri` and enum `meaning`
pointing at established vocabularies (schema.org, Dublin Core, SKOS, FOAF, or a domain
ontology). Without them, generated RDF mints private IRIs and cannot be joined to anything.
Also check `exact_mappings`/`close_mappings` where an exact match does not exist.

This is a recommendation, not a defect — but for a schema intended to be published it is the
difference between reusable and not.

### 7. Naming consistency

LinkML convention: `UpperCamelCase` classes, `snake_case` slots and attributes,
`UPPER_SNAKE_CASE` permissible values. Flag *inconsistency* within the schema more strongly than
deviation from convention — a schema that is uniformly different is liveable, a schema that is
half one and half the other is not.

Also flag names that will confuse: abbreviations without explanation, `data`/`info`/`value` as
attribute names, plural names for single-valued slots or singular for `multivalued: true`.

### 8. Documentation

- Does every class and every non-obvious attribute have a `description`?
- Does the schema itself have `title`, `description`, and a `license`?
- Are there `examples:` on anything with a non-obvious format?
- Do units-bearing quantities say so (`unit:` or an explicit description)?

Undocumented schemas are the norm and it is worth saying, but keep it proportionate — one
finding, not one per attribute.

### 9. Constraints actually expressed

A schema that only declares types is doing half the job. Look for constraints the domain clearly
has but the schema does not state: `required`, `pattern` on identifiers and codes,
`minimum_value`/`maximum_value` on quantities, enums instead of free-text status fields.

Every constraint that is real and expressible should be expressed — that is what generation is
for.

### 10. Generation sanity

Generate everything the project uses, and check the output is sane rather than merely
non-erroring:

```shell
linkml-scala generate json-schema schema.yaml  >/dev/null
linkml-scala generate shacl --format ttl schema.yaml >/dev/null
```

Then actually read one of them. A schema can generate valid-but-useless output — a JSON Schema
where everything is an optional string means the model is not pulling its weight.

## Reporting

Group by severity and lead with consequence, not with the rule:

```
## Will break consumers
1. `Measurement` has no identifier, so every reference to it is inlined by value.
   Generated JSON nests a full copy at each use site. Add `id` with `identifier: true`.
2. `rules:` on `Order` (line 88) is ignored by every linkml-scala generator. The
   constraint is not enforced anywhere. Express it with `required` + `pattern`, or
   drop it and document the gap.

## Worth fixing
3. ...

## Style
7. ...

## Good
- Consistent snake_case throughout; every class has a description.
```

Saying what is already good is not filler — it tells the author which conventions to keep.

## Reference material

[200-limitations.md](200-limitations.md) — the authority on what is actually supported; check any construct
you are unsure about before calling it fine. [900-metaslots.tsv](900-metaslots.tsv) — grep to check whether a constraint the author wants
is even expressible. [100-authoring.md](100-authoring.md) — the built-in ranges.
