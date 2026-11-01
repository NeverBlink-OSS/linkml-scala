// Type-level smoke test for the generated index.d.ts, type-checked by
// verify-package.mjs. Not shipped in the package.

import {
  LinkML,
  type LoadResult,
  type ImportResult,
  type SchemaConversionError,
  type SchemaIssue,
  type SchemaStats,
  type SchemaValidationReport,
  type SchemaView,
} from "@neverblink/linkml";

const schema = "id: https://example.org/s\nname: s";
const imports: Record<string, string> = {};

// Build metadata needs no schema.
const build: any = LinkML.buildInfo();
const buildVersion: string = build.linkml_scala_version;

// Loading always yields a report; `view` is absent when the schema has fatal problems.
const loaded: LoadResult = LinkML.loadFromString(schema, imports);
const loadReport: SchemaValidationReport = loaded.report;
if (!loaded.view) throw new Error("schema did not load");
const view: SchemaView = loaded.view;

const loadedFromPath: LoadResult = LinkML.loadFromPath("model.yaml", { "model.yaml": schema });
const loadedNoMessages: LoadResult = LinkML.loadFromString(schema, imports, false);
// `importMap` maps an import to the key of the imports to load it from.
const loadedWithImportMap: LoadResult = LinkML.loadFromPath(
  "model.yaml",
  { "model.yaml": schema, "deps/units.yaml": schema },
  true,
  { "https://example.org/units/": "deps/units/" },
);
const loadedFromStringWithImportMap: LoadResult = LinkML.loadFromString(schema, imports, true, {
  "https://example.org/units/core": "deps/units.yaml",
});
void loadedFromPath;
void loadedNoMessages;
void loadedWithImportMap;
void loadedFromStringWithImportMap;

const jsonSchema: string = view.jsonSchema();
const jsonSchemaFull: string = view.jsonSchema({ open: true, tree_root: "Person" });
const shacl: string = view.shacl();
const shaclFull: string = view.shacl({ only_classes_from_root_schema: true, format: "ttl" });
const rdfs: string = view.rdfs();
const rdfsFull: string = view.rdfs({ format: "ttl" });
const linkml: string = view.linkml();
const linkmlFull: string = view.linkml({ pruning_mode: "skip", skip_class_derivation: true, format: "json" });
const scala: Record<string, string> = view.scala({ package: "com.example" });
const frictionless: Record<string, string> = view.frictionless();
const frictionlessRoot: Record<string, string> = view.frictionless({
  pruning_mode: "tree_root",
  tree_root: "Person",
  skip_classes_without_identifier: true,
});
const erDiagram: string = view.erDiagram();
const erDiagramFull: string = view.erDiagram({ pruning_mode: "skip", optional_marker: false });
const stats: SchemaStats = view.stats();
const statsTreeRoot: SchemaStats = view.stats({ pruning_mode: "tree_root", tree_root: "Person" });
const classCount: number = stats.elements.classes.count;
const ossie: string = view.ossie();
const ossieFull: string = view.ossie({ pruning_mode: "tree_root", tree_root: "Person", format: "json" });
// @ts-expect-error the pruning modes are the names in the options schema
view.stats({ pruning_mode: "treeRoot" });
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

// An importer takes the document, then its options, and returns the schema with its warnings.
const owlImport: ImportResult = LinkML.fromOwl("", {
  format: "json",
  config: { naming: { slots: "keep" } },
  schemas: { "other.yaml": "id: https://example.org/o\nname: o" },
});
const owlWarnings: string[] = owlImport.warnings;
const ossieSchema: string = LinkML.fromOssie("{}", { schema_id: "https://example.org/x" }).schema;
// @ts-expect-error the options follow model/converter, in snake case
LinkML.fromOssie("{}", { schemaId: "https://example.org/x" });

// A problem with the input is thrown as an Error with a `SchemaConversionError` issue.
try {
  LinkML.fromOssie("- not\n- an ontology\n");
} catch (e) {
  const issue: SchemaConversionError | undefined = (e as { issue?: SchemaConversionError }).issue;
  const reason: string | undefined = issue?.reason;
  void reason;
}

void [
  owlImport,
  owlWarnings,
  ossieSchema,
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
  stats,
  statsTreeRoot,
  classCount,
  ossie,
  ossieFull,
  lint,
  lintIssues,
  lintNoMessages,
];
