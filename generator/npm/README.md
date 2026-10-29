# @neverblink/linkml

JavaScript / TypeScript bindings for [LinkML-Scala](https://github.com/NeverBlink-OSS/linkml-scala) –
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

// Then run any generator against the loaded schema.
const jsonSchema = LinkML.jsonSchema(view);
console.log(jsonSchema);

const shacl = LinkML.shacl(view);
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

Load a schema into a `SchemaView` handle (see above), then pass that handle to any generator:

| Function                                                                     | Returns                  | Notes                                                                          |
|------------------------------------------------------------------------------|--------------------------|--------------------------------------------------------------------------------|
| `loadFromString(schema, imports, inferMessages?, importMap?)`                | `LoadResult`             | parse from YAML text; `{ view?, report }`                                      |
| `loadFromPath(path, imports, inferMessages?, importMap?)`                    | `LoadResult`             | parse from a path in `imports`; cycle-safe for the root                        |
| `jsonSchema(view, open?, treeRootOverride?)`                                 | `string`                 | JSON Schema                                                                    |
| `shacl(view, open?, onlyClassesFromRootSchema?, format?)`                    | `string`                 | SHACL shapes, `ttl` (default) or `nt`                                          |
| `rdfs(view, onlyClassesFromRootSchema?, format?)`                            | `string`                 | RDFS, `ttl` (default) or `nt`                                                  |
| `owl(view, onlyRootSchema?, metadataProfile?, permissibleValues?, format?)`  | `string`                 | OWL 2 ontology, `ttl` (default) or `nt`                                        |
| `linkml(view, pruningMode?, skipDerivation?, treeRoot?, outFormat?, fullUris?)` | `string`                 | derived/pruned LinkML schema                                                   |
| `scala(view, packageName)`                                                   | `Record<string, string>` | filename → generated Scala                                                     |
| `frictionless(view, pruningMode?, treeRoot?, skipClassesWithoutIdentifier?)` | `Record<string, string>` | filename → Frictionless data package (`datapackage.json` and `schemas/*.json`) |
| `graphQl(view, pruningMode?, treeRoot?)`                                     | `string`                 | GraphQL                                                                        |
| `typeScript(view, pruningMode?, treeRoot?, includeNull?, open?)`             | `string`                 | TypeScript types for the JSON data (no runtime code)                           |
| `pydantic(view, pruningMode?, treeRoot?, includeNull?, open?)`               | `string`                 | Python classes (pydantic) that load and dump the JSON data                     |
| `erDiagram(view, pruningMode?, treeRoot?, optionalMarker?)`                  | `string`                 | Mermaid entity relationship diagram                                            |
| `docs(view, pruningMode?, treeRoot?, singleFile?, metadataLanguage?)`        | `Record<string, string>` | filename → Markdown documentation (`index.md` and a page per element)          |
| `ossie(view, pruningMode?, treeRoot?, outFormat?)`                           | `string`                 | Apache Ossie ontology, `yaml` (default) or `json`                              |
| `translation(view, target)`                                                  | `string`                 | Translation dictionary for generator outputs                                   |
| `lint(view, inferMessages?)`                                                 | `SchemaValidationReport` | validation report, typed by issue kind (see below)                             |
| `buildInfo()`                                                                | `object`                 | `BuildInfo` (JSON) – version and build metadata                                |
| `fromOssie(ontology, schemaId?, outFormat?, fullUris?)`                      | `string`                 | the LinkML schema from an Apache Ossie ontology                                |
| `fromOwl(ontology, config?, outFormat?, listNotImported?, imports?, inputFormat?, fullUris?)` | `string`         | the LinkML schema from an OWL ontology in Turtle or N-Triples (see the OWL guide) |

See [`index.d.ts`](./index.d.ts) for full type signatures. The validation report that `lint` and
`loadFromString`/`loadFromPath` return is fully typed: each issue's `issue_type` names its kind
(e.g. `"UnknownReference"`), and checking it narrows the issue to that kind's fields. For how to use the generated TypeScript,
see [the TypeScript guide](https://linkml.ohi.dev/docs/typescript/), and for `pydantic`
[the Pydantic guide](https://linkml.ohi.dev/docs/pydantic/). For `owl` and `fromOwl`, see
[the OWL guide](https://linkml.ohi.dev/docs/owl/).

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
