// Type-level smoke test for the generated index.d.ts, type-checked by
// verify-package.mjs. Not shipped in the package.

import { LinkML } from "@neverblink/linkml";

const schema = "id: https://example.org/s\nname: s";
const importMap: Record<string, string> = {};

const jsonSchema: string = LinkML.jsonSchema(schema, importMap);
const jsonSchemaFull: string = LinkML.jsonSchema(schema, importMap, true, "Person");
const shacl: string = LinkML.shacl(schema, importMap);
const shaclFull: string = LinkML.shacl(schema, importMap, false, true);
const rdfs: string = LinkML.rdfs(schema, importMap, false);
const linkml: string = LinkML.linkml(schema, importMap);
const linkmlFull: string = LinkML.linkml(schema, importMap, "skip", true, "Person", "json");
const scala: Record<string, string> = LinkML.scala(schema, importMap, "com.example");
const tableSchema: string = LinkML.tableSchema(schema, importMap);
const tableSchemaRoot: string = LinkML.tableSchema(schema, importMap, "Person");
const lint: string = LinkML.lint(schema, importMap);
const lintFull: string = LinkML.lint(schema, importMap, 10, true);

void [
  jsonSchema,
  jsonSchemaFull,
  shacl,
  shaclFull,
  rdfs,
  linkml,
  linkmlFull,
  scala,
  tableSchema,
  tableSchemaRoot,
  lint,
  lintFull,
];
