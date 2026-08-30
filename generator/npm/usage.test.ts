// Type-level smoke test for the generated index.d.ts, type-checked by
// verify-package.mjs. Not shipped in the package.

import { LinkML, type LoadResult, type SchemaView } from "@neverblink/linkml";

const schema = "id: https://example.org/s\nname: s";
const importMap: Record<string, string> = {};

// Build metadata needs no schema.
const build: any = LinkML.buildInfo();
const buildVersion: string = build.linkml_scala_version;

// Loading always yields a report; `view` is absent when the schema has fatal problems.
const loaded: LoadResult = LinkML.loadFromString(schema, importMap);
const loadReport: unknown = loaded.report;
if (!loaded.view) throw new Error("schema did not load");
const view: SchemaView = loaded.view;

const loadedFromPath: LoadResult = LinkML.loadFromPath("model.yaml", { "model.yaml": schema });
const loadedNoMessages: LoadResult = LinkML.loadFromString(schema, importMap, false);
void loadedFromPath;
void loadedNoMessages;

const jsonSchema: string = LinkML.jsonSchema(view);
const jsonSchemaFull: string = LinkML.jsonSchema(view, true, "Person");
const shacl: string = LinkML.shacl(view);
const shaclFull: string = LinkML.shacl(view, false, true);
const rdfs: string = LinkML.rdfs(view);
const rdfsFull: string = LinkML.rdfs(view, false);
const linkml: string = LinkML.linkml(view);
const linkmlFull: string = LinkML.linkml(view, "skip", true, "Person", "json");
const scala: Record<string, string> = LinkML.scala(view, "com.example");
const frictionless: Record<string, string> = LinkML.frictionless(view);
const frictionlessRoot: Record<string, string> = LinkML.frictionless(view, "treeRoot", "Person", true);
const erDiagram: string = LinkML.erDiagram(view);
const erDiagramFull: string = LinkML.erDiagram(view, "skip", "Person", false);
const lint: any = LinkML.lint(view);
const lintIssues: unknown[] = lint.issues;
const lintNoMessages: any = LinkML.lint(view, false);

void [
  build,
  buildVersion,
  loadReport,
  jsonSchema,
  jsonSchemaFull,
  shacl,
  shaclFull,
  rdfs,
  rdfsFull,
  linkml,
  linkmlFull,
  scala,
  frictionless,
  frictionlessRoot,
  erDiagram,
  erDiagramFull,
  lint,
  lintIssues,
  lintNoMessages,
];
