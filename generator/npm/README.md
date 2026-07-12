# @neverblink/linkml

JavaScript / TypeScript bindings for [LinkML-Scala](https://github.com/NeverBlink-OSS/linkml-scala) —
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
default_range: string
classes:
  Person:
    attributes:
      name:
      age:
        range: integer
`;

// The second argument is an import map (filename -> YAML source) for any
// models referenced via LinkML \`imports:\`. Pass {} when there are none.
const jsonSchema = LinkML.jsonSchema(schema, {});
console.log(jsonSchema);
```

### Available functions

| Function | Returns | Notes |
| --- | --- | --- |
| `jsonSchema(schema, importMap, open?, treeRootOverride?)` | `string` | JSON Schema |
| `shacl(schema, importMap, open?, onlyClassesFromRootSchema?)` | `string` | SHACL shapes in N-Triples |
| `rdfs(schema, importMap, onlyClassesFromRootSchema?)` | `string` | RDFS in N-Triples |
| `linkml(schema, importMap, pruningMode?, skipDerivation?, treeRoot?, outFormat?)` | `string` | derived/pruned LinkML schema |
| `scala(schema, importMap, packageName)` | `Record<string, string>` | filename → generated Scala |
| `tableSchema(schema, importMap, treeRoot?)` | `string` | Frictionless Table Schema (JSON) |
| `lint(schema, importMap, maxProblems?, verbose?)` | `string` | problem summary, empty if valid |

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
