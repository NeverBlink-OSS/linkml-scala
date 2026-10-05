// Type-level smoke test for the generated index.d.ts, type-checked by
// verify-package.mjs. Not shipped in the package.

import {
  LinkML,
  type LoadResult,
  type SchemaIssue,
  type SchemaValidationReport,
  type SchemaView,
} from "@neverblink/linkml";

const schema = "id: https://example.org/s\nname: s";
const importMap: Record<string, string> = {};

// Build metadata needs no schema.
const build: any = LinkML.buildInfo();
const buildVersion: string = build.linkml_scala_version;

// Loading always yields a report; `view` is absent when the schema has fatal problems.
const loaded: LoadResult = LinkML.loadFromString(schema, importMap);
const loadReport: SchemaValidationReport = loaded.report;
if (!loaded.view) throw new Error("schema did not load");
const view: SchemaView = loaded.view;

const loadedFromPath: LoadResult = LinkML.loadFromPath("model.yaml", { "model.yaml": schema });
const loadedNoMessages: LoadResult = LinkML.loadFromString(schema, importMap, false);
void loadedFromPath;
void loadedNoMessages;

const jsonSchema: string = LinkML.jsonSchema(view);
const jsonSchemaFull: string = LinkML.jsonSchema(view, true, "Person");
const shacl: string = LinkML.shacl(view);
const shaclFull: string = LinkML.shacl(view, false, true, "ttl");
const rdfs: string = LinkML.rdfs(view);
const rdfsFull: string = LinkML.rdfs(view, false, "ttl");
const linkml: string = LinkML.linkml(view);
const linkmlFull: string = LinkML.linkml(view, "skip", true, "Person", "json");
const scala: Record<string, string> = LinkML.scala(view, "com.example");
const frictionless: Record<string, string> = LinkML.frictionless(view);
const frictionlessRoot: Record<string, string> = LinkML.frictionless(view, "treeRoot", "Person", true);
const erDiagram: string = LinkML.erDiagram(view);
const erDiagramFull: string = LinkML.erDiagram(view, "skip", "Person", false);
const ossie: string = LinkML.ossie(view);
const ossieFull: string = LinkML.ossie(view, "treeRoot", "Person", "json");
const lint: SchemaValidationReport = LinkML.lint(view);
const lintIssues: SchemaIssue[] = lint.issues;
const lintNoMessages: SchemaValidationReport = LinkML.lint(view, false);
// `issue_type` tells the kinds of issue apart, so checking it narrows the issue.
for (const issue of lintIssues) {
  if (issue.issue_type === "UnknownReference") {
    const reference: string = issue.reference_value;
    void reference;
  }
  // @ts-expect-error only an UnknownReference has a reference_value
  void issue.reference_value;
}

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
  ossie,
  ossieFull,
  lint,
  lintIssues,
  lintNoMessages,
];
