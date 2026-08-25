# Validating instance data against a LinkML schema

**`linkml-scala validate` does not do this.** It lints the *schema*. Do not run it on a data file
and report the result — it will either fail confusingly or appear to pass while checking nothing.

The route is two steps: generate a validation artifact from the schema, then run a validator for
that format. This is exactly how linkml-scala tests itself, using
[networknt/json-schema-validator](https://github.com/networknt/json-schema-validator) for JSON
Schema and RDF4J's SHACL engine for RDF.

Pick by data format:

| Data | Generate | Validate with |
|---|---|---|
| JSON, YAML | `json-schema` | `check-jsonschema`, `ajv`, or Python `jsonschema` |
| RDF (Turtle, N-Triples, JSON-LD) | `shacl` | `pyshacl` or Apache Jena `shacl` |
| CSV, TSV | `table-schema` | `frictionless validate` |

## Step 1 — always lint the schema first

A schema with fatal issues cannot generate anything, and a schema with warnings often generates
something subtly wrong. Get it clean before blaming the data:

```shell
linkml-scala validate --format json schema.yaml
```

## Step 2 — generate

```shell
linkml-scala generate json-schema  --to build/schema.json  schema.yaml
linkml-scala generate shacl --format ttl --to build/shapes.ttl  schema.yaml
linkml-scala generate table-schema --to build/table.json  schema.yaml
```

Two flags change the outcome materially:

- **`--open`** (json-schema, shacl) allows properties the schema does not declare. Without it,
  output is **closed** — an undeclared field is a violation. If the user expects extra fields to
  be tolerated, they need `--open`; if they expect typos to be caught, they must not use it.
- **`--tree-root-override`** (json-schema) / **`--tree-root`** (table-schema) validate against a
  class other than the schema's `tree_root`. This is how you check a fragment rather than a whole
  document.

Whether the root is a single object, a list or a dict comes from the root class's `tree_root_as`
extension — so a JSON array of records needs `extensions: {tree_root_as: list}`, or validation of
a perfectly good file fails on the root type. You can override it per generation without touching
the schema:

```shell
linkml-scala generate json-schema --tree-root-inline-type-override list \
  --to build/schema.json schema.yaml
```

Accepts `plain`, `optional`, `list`, `compact_dict`, `simple_dict`; the default is `plain`. Use
the override when validating one file shape, and change the schema when the array *is* the
document. See [200-limitations.md](200-limitations.md).

## Step 3 — validate

### JSON and YAML

```shell
# check-jsonschema (pipx install check-jsonschema) - reads YAML too
check-jsonschema --schemafile build/schema.json data/*.json

# ajv (npm i -g ajv-cli ajv-formats)
ajv validate -s build/schema.json -d "data/*.json" --spec=draft2020 -c ajv-formats

# Python, no install beyond the library
python3 -c '
import json, sys, jsonschema
schema = json.load(open("build/schema.json"))
for path in sys.argv[1:]:
    v = jsonschema.Draft202012Validator(schema)
    errs = sorted(v.iter_errors(json.load(open(path))), key=lambda e: list(e.absolute_path))
    print(f"{path}: {len(errs)} error(s)" if errs else f"{path}: OK")
    for e in errs:
        print("   ", "/".join(map(str, e.absolute_path)) or "<root>", "-", e.message)
' data/*.json
```

`ajv` needs `ajv-formats` for `date`, `date-time` and `uri`; without it those constraints are
ignored and the data appears to pass.

### RDF

```shell
# pyshacl (pipx install pyshacl)
pyshacl -s build/shapes.ttl -f human data/graph.ttl

# Apache Jena
shacl validate --shapes build/shapes.ttl --data data/graph.ttl
```

Both exit non-zero on violations. `pyshacl -f human` is the readable format; use `-f json` to
process the report.

### CSV

```shell
frictionless validate --schema build/table.json data/records.csv
```

## Ask before installing

All of these are third-party tools. Say which you propose and why, and let the user choose —
several may already be available. Check first:

```shell
command -v check-jsonschema ajv pyshacl shacl frictionless
python3 -c "import jsonschema" 2>/dev/null && echo "python jsonschema available"
```

Prefer whatever the project already depends on. If nothing is installed and the user does not
want new tools, the Python `jsonschema` snippet above has the smallest footprint, and
`@neverblink/linkml` plus any JS validator works in a Node project without new binaries.

## Interpreting failures

When data fails, decide which side is wrong before proposing a fix — and say which you concluded:

- **Closed-world surprises.** Errors like `additionalProperties` on a field that obviously
  belongs mean the schema is missing an attribute, not that the data is bad.
- **Root shape mismatch.** "expected object, got array" is almost always a missing
  `tree_root_as: list`, not malformed data.
- **Inlining mismatch.** A nested object where a string identifier was expected (or vice versa)
  means the schema's inlining inference does not match reality. Generate JSON Schema and read
  what it expects.
- **Type narrowing.** `"42"` failing an `integer` range is a real data problem if the source is
  JSON, but expected if it came from CSV, where everything is a string. `table-schema` is the
  right artifact for CSV, not `json-schema`.
- **Missing formats.** A `date` that passes when it should not usually means the validator lacks
  format support rather than that the data is correct.

Report a summary, not a wall of output: how many records checked, how many failed, and the
distinct failure kinds with one example each. Then say whether the schema or the data needs the
fix.

## Worked examples to check against

The linkml-scala test suite ships `valid/` and `invalid/` instance data next to most example
schemas — genuinely useful for confirming your pipeline works before trusting it on real data.
A schema plus a file that *should* fail is the best way to prove the validator is actually
running:

<https://github.com/NeverBlink-OSS/linkml-scala/tree/main/tests/resources/models>

## In CI

Generation belongs in CI via the action; the validation step is then whatever tool you chose:

```yaml
- uses: NeverBlink-OSS/linkml-scala-action@v0.14.0
  with:
    command: generate
    generator: json-schema
    files: "schemas/**/*.yaml"
    output: build
- run: pipx run check-jsonschema --schemafile build/schema.json data/*.json
```

See [600-ci.md](600-ci.md). `linkml-scala <command> --help` has the full generator flags;
[200-limitations.md](200-limitations.md) covers `tree_root_as` and inlining semantics.
