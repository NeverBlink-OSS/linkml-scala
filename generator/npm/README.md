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
const view = LinkML.loadFromString(schema, {});

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
const view = LinkML.loadFromPath("model.yaml", {
  "model.yaml": schema,
  "person.yaml": personSchema, // referenced via `imports: - person`
});
```

### Available functions

Load a schema into a `SchemaView` handle (see above), then pass that handle to any generator:

| Function | Returns | Notes |
| --- | --- | --- |
| `loadFromString(schema, importMap)` | `SchemaView` | parse from YAML text; reuse the handle |
| `loadFromPath(path, importMap)` | `SchemaView` | parse from a path in the import map; cycle-safe for the root |
| `jsonSchema(view, open?, treeRootOverride?)` | `string` | JSON Schema |
| `shacl(view, open?, onlyClassesFromRootSchema?)` | `string` | SHACL shapes in N-Triples |
| `rdfs(view, onlyClassesFromRootSchema?)` | `string` | RDFS in N-Triples |
| `linkml(view, pruningMode?, skipDerivation?, treeRoot?, outFormat?)` | `string` | derived/pruned LinkML schema |
| `scala(view, packageName)` | `Record<string, string>` | filename → generated Scala |
| `tableSchema(view, treeRoot?)` | `string` | Frictionless Table Schema (JSON) |
| `lint(view, maxProblems?, verbose?)` | `string` | problem summary, empty if valid |

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
