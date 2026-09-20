# @neverblink/linkml

JavaScript / TypeScript bindings for [LinkML-Scala](https://github.com/NeverBlink-OSS/linkml-scala) –
LinkML schema validation and multi-format code generation (JSON Schema, SHACL, RDFS, Scala),
compiled from Scala 3 to JavaScript via [Scala.js](https://www.scala-js.org/).

The package ships a single self-contained ES module. It has no runtime dependencies.

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
// is an import map (filename -> YAML source) for any models referenced
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

- `loadFromString(schema, importMap)` – start from the schema's YAML text. Imported models
  must be provided in the import map (filename → YAML).
- `loadFromPath(path, importMap)` – start from a path into the import map. The root schema is
  read from `importMap[path]` (paths behave like file paths, `.yaml` is appended when missing).
  Because the root is tracked from the start of import resolution, this variant is immune to
  cyclic imports that reference the root schema back.

```js no-test
// loadFromPath: the root lives in the import map under its own path.
const { view } = LinkML.loadFromPath("model.yaml", {
  "model.yaml": schema,
  "person.yaml": personSchema, // referenced via `imports: - person`
});
```

### Available functions

Load a schema into a `SchemaView` handle (see above), then pass that handle to any generator:

| Function                                                                     | Returns                  | Notes                                                                          |
|------------------------------------------------------------------------------|--------------------------|--------------------------------------------------------------------------------|
| `loadFromString(schema, importMap, inferMessages?)`                          | `LoadResult`             | parse from YAML text; `{ view?, report }`                                      |
| `loadFromPath(path, importMap, inferMessages?)`                              | `LoadResult`             | parse from a path in the import map; cycle-safe for the root                   |
| `lint(view, inferMessages?)`                                                 | `object`                 | `SchemaValidationReport` (JSON)                                                |
| `buildInfo()`                                                                | `object`                 | `BuildInfo` (JSON) – version and build metadata                                |
| `fromOssie(ontology, schemaId?, outFormat?)`                                 | `string`                 | the LinkML schema from an Apache Ossie ontology                                |

<!-- BEGIN GENERATED OPTIONS docs.npm.generators -->

| Function | Returns | Notes |
| --- | --- | --- |
| ` jsonSchema(view, open?, treeRootOverride?) ` | ` string ` | Options for generating JSON Schema. |
 ` shacl(view, open?, onlyClassesFromRootSchema?, format?) ` | ` string ` | Options for generating SHACL shapes. |
 ` rdfs(view, onlyClassesFromRootSchema?, format?) ` | ` string ` | Options for generating RDF schema. |
 ` linkml(view, pruningMode?, skipDerivation?, treeRoot?, outFormat?) ` | ` string ` | Options for materializing a derived LinkML schema. Derives classes and can prune unreachable elements. |
 ` frictionless(view, pruningMode?, treeRoot?, skipClassesWithoutIdentifier?) ` | ` Record<string, string> ` | Options for generating a Frictionless Data Package. Each selected class becomes a CSV table, described by its own Table Schema, and references between classes can become foreign keys between the tables. |
 ` graphQl(view, pruningMode?, treeRoot?) ` | ` string ` | Options for generating a GraphQL schema. Only types/interfaces/scalar/enums, queries must be provided for a specific implementation. |
 ` erDiagram(view, pruningMode?, treeRoot?, optionalMarker?) ` | ` string ` | Options for generating Mermaid entity relationship diagrams. Classes become entities, type- and enum-ranged slots become their attributes, and class-ranged slots become relationship lines. |
 ` ossie(view, pruningMode?, treeRoot?, outFormat?) ` | ` string ` | Options for generating an Apache Ossie ontology. Classes become entity types, enums and named types become value types, and slots become the relationships grouped under the concept that plays their first role. |
 ` scala(view, packageName) ` | ` Record<string, string> ` | Options for generating Scala classes. This is primarily used for the metamodel. |
 ` translation(view, target) ` | ` string ` | Options for generating JSON dictionaries that translate the LinkML name to specific frameworks. This is useful when the framework symbols are significant and must be known, like when constructing a query that is meant to be executed against a database conformant to a LinkML schema. |

See the [generator option reference](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/generator-options.md)
for defaults, CLI flags, Python keywords, and native JSON fields.

<!-- END GENERATED OPTIONS docs.npm.generators -->

Scala and Frictionless return maps from filenames to content. Frictionless includes
`datapackage.json` and `schemas/*.json`.

See [`index.d.ts`](./index.d.ts) for full type signatures.

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
