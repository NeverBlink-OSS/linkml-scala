# @neverblink/linkml

JavaScript / TypeScript bindings for [ohi LinkML](https://github.com/NeverBlink-OSS/ohi) –
LinkML schema validation and multi-format code generation (JSON Schema, SHACL, RDFS, Scala).

## Installation

```shell
npm install @neverblink/linkml
```

## Usage

```js
import { LinkML } from "@neverblink/linkml";

const schema = `
id: https://example.org/my-schema
name: my-schema
prefixes:
  linkml: https://w3id.org/linkml/
imports:
  - linkml:types
default_range: string
classes:
  Person:
    tree_root: true
    attributes:
      name:
      age:
        range: integer
`;

// Parse the schema once into a reusable handle. The second argument
// maps file names to YAML source for any models referenced
// via LinkML \`imports:\`. Pass {} when there are none.
const { view, report } = LinkML.loadFromString(schema, {});
if (!view) throw new Error(JSON.stringify(report, null, 2)); // fatal problems: nothing to generate from

// Then run any generator on the loaded schema.
const jsonSchema = view.jsonSchema();
console.log(jsonSchema);

const shacl = view.shacl({ format: "nt" });
console.log(shacl);
```

### Loading

There are two ways to load a schema into a `SchemaView` handle:

- `loadFromString(schema, imports)` – start from the schema's YAML text. Imported models
  must be provided in `imports` (filename → YAML).
- `loadFromPath(path, imports)` – start from a path into `imports`, which also holds the root
  schema itself. The root schema is read from `imports[path]`. Because the root is tracked from the start of import resolution,
  this variant is immune to cyclic imports that reference the root schema back.

Keys of `imports` are import paths as seen from the root schema. The `.yaml` and `.yml` extensions
are optional and interchangeable, and each imported schema is loaded only once, even when it is
reached through different relative paths.

Both take an optional fourth argument, `importMap`, which maps an import to the key of `imports`
to load it from. Use it when a schema imports another one by URI or CURIE. Keys are full URIs or
paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it. To use
a JSON file, pass `JSON.parse` of its text. See
[Import maps](https://linkml.ohi.dev/docs/import-maps/).

```js no-test
// loadFromPath: the root lives in `imports` under its own path.
const { view } = LinkML.loadFromPath("model.yaml", {
  "model.yaml": schema,
  "person.yaml": personSchema, // referenced via `imports: - person`
});

// importMap: `units:core` (or `https://example.org/units/core`) is read from "deps/units.yaml".
const { view: withUnits } = LinkML.loadFromPath(
  "model.yaml",
  { "model.yaml": schema, "deps/units.yaml": unitsSchema },
  true,
  { "https://example.org/units/core": "deps/units.yaml" },
);
```

### Available functions

Load a schema into a `SchemaView` (see above), then call any generator on it. Every generator
takes an optional options object, such as `{ pruning_mode: "tree_root", format: "json" }`. The
generators, their options and the defaults are the functions of
[`model/converter`](https://github.com/NeverBlink-OSS/ohi/tree/main/model/converter). Their types, such as `LinkMlGeneratorOptions`, are in `index.d.ts`.

| Method of `SchemaView` | Returns | Notes |
|------------------------|---------|-------|
| `jsonSchema(options?)` | `string` | JSON Schema |
| `shacl(options?)` | `string` | SHACL shapes, `ttl` (default) or `nt` |
| `rdfs(options?)` | `string` | RDFS, `ttl` (default) or `nt` |
| `owl(options?)` | `string` | OWL 2 ontology, `ttl` (default) or `nt` |
| `linkml(options?)` | `string` | derived/pruned LinkML schema |
| `scala(options?)` | `Files` | filename → generated Scala |
| `frictionless(options?)` | `Files` | filename → Frictionless data package (`datapackage.json` and `schemas/*.json`) |
| `graphql(options?)` | `string` | GraphQL |
| `typescript(options?)` | `string` | TypeScript types for the JSON data (no runtime code) |
| `pydantic(options?)` | `string` | Python classes (pydantic) that load and dump the JSON data |
| `java(options?)` | `Files` | filename → Java classes (Jackson) that load and dump the JSON data |
| `erDiagram(options?)` | `string` | Mermaid entity relationship diagram |
| `docs(options?)` | `Files` | filename → Markdown documentation (`index.md` and a page per element) |
| `ossie(options?)` | `string` | Apache Ossie ontology, `yaml` (default) or `json` |
| `translation(options?)` | `string` | Translation dictionary for generator outputs |
| `stats(options?)` | `SchemaStats` | counts of the elements, their documentation, mappings, languages and imports |

The functions of `LinkML`:

| Function                                                                     | Returns                  | Notes                                                                          |
|------------------------------------------------------------------------------|--------------------------|--------------------------------------------------------------------------------|
| `loadFromString(schema, imports, inferMessages?, importMap?)`                | `LoadResult`             | parse from YAML text; `{ view?, report }`                                      |
| `loadFromPath(path, imports, inferMessages?, importMap?)`                    | `LoadResult`             | parse from a path in `imports`; cycle-safe for the root                        |
| `lint(view, inferMessages?)`                                                 | `SchemaValidationReport` | validation report, typed by issue kind (see below)                             |
| `buildInfo()`                                                                | `object`                 | `BuildInfo` (JSON) – version and build metadata                                |
| `fromOssie(document, options?)`                                              | `ImportResult`           | the LinkML schema from an Apache Ossie ontology                                |
| `fromOwl(document, options?)`                                                | `ImportResult`           | the LinkML schema from an OWL ontology in Turtle or N-Triples (see the OWL guide) |

The importers, `fromOssie` and `fromOwl`, take the document to import, then an options object like
the generators, such as `{ format: "json" }`. Their options are in `model/converter` too. Both
return `{ schema, warnings }`: the schema, and what could not be imported.

See [`index.d.ts`](./index.d.ts) for full type signatures. The validation report that `lint` and
`loadFromString`/`loadFromPath` return is fully typed: each issue's `issue_type` names its kind
(e.g. `"UnknownReference"`), and checking it narrows the issue to that kind's fields. For how to use the generated TypeScript,
see [the TypeScript guide](https://linkml.ohi.dev/docs/generators/typescript/), for `pydantic`
[the Pydantic guide](https://linkml.ohi.dev/docs/generators/pydantic/), and for `java`
[the Java guide](https://linkml.ohi.dev/docs/generators/java/). For `owl` and `fromOwl`, see
[the OWL generator](https://linkml.ohi.dev/docs/generators/owl/) and [the OWL importer](https://linkml.ohi.dev/docs/importers/owl/).

### Errors

A generator or importer that can't convert a schema or ontology, for a reason you can fix, throws
an `Error` with an `issue` property. It is a `SchemaConversionError` issue, in the same shape as the
issues of a validation report, and its `message` says what is wrong. `linkml`, `ossie`, `owl`,
`fromOssie` and `fromOwl` can throw it. Any other error is a wrong argument, such as an unknown
format or metadata profile, or a bug.

```js
import { LinkML } from "@neverblink/linkml";

try {
  LinkML.fromOssie("- not\n- an ontology\n");
  throw new Error("fromOssie should have failed");
} catch (e) {
  if (e.issue?.issue_type !== "SchemaConversionError") throw e; // a wrong argument or a bug
  console.log(e.issue.message); // Not a readable Ossie ontology: ...
}
```

## Browser use

The module works in Node.js as-is. In the browser it references `process.cwd`
once, so provide a minimal shim before importing:

```html
<script>
  var process = { cwd: () => "/" };
</script>
```

## License

Apache-2.0 © [NeverBlink](https://neverblink.eu)
