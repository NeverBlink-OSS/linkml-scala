// Type-level smoke test for the generated index.d.ts, type-checked by
// verify-package.mjs. Not shipped in the package.

import { LinkML, type SchemaView } from "@neverblink/linkml";

const schema = "id: https://example.org/s\nname: s";
const importMap: Record<string, string> = {};

const view: SchemaView = LinkML.loadFromString(schema, importMap);
const viewFromPath: SchemaView = LinkML.loadFromPath("model.yaml", { "model.yaml": schema });
void viewFromPath;

const jsonSchema: string = LinkML.jsonSchema(view);
const jsonSchemaFull: string = LinkML.jsonSchema(view, true, "Person");
const shacl: string = LinkML.shacl(view);
const shaclFull: string = LinkML.shacl(view, false, true);
const rdfs: string = LinkML.rdfs(view);
const rdfsFull: string = LinkML.rdfs(view, false);
const linkml: string = LinkML.linkml(view);
const linkmlFull: string = LinkML.linkml(view, "skip", true, "Person", "json");
const scala: Record<string, string> = LinkML.scala(view, "com.example");
const tableSchema: string = LinkML.tableSchema(view);
const tableSchemaRoot: string = LinkML.tableSchema(view, "Person");
const lint: string = LinkML.lint(view);
const lintFull: string = LinkML.lint(view, 10, true);

void [
  jsonSchema,
  jsonSchemaFull,
  shacl,
  shaclFull,
  rdfs,
  rdfsFull,
  linkml,
  linkmlFull,
  scala,
  tableSchema,
  tableSchemaRoot,
  lint,
  lintFull,
];
