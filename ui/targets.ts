// The generator catalog, shared by the UI and the worker.
//
// The UI thread uses everything here *except* `call` - labels, option widgets and the output
// language. `call` runs only in the worker, which is the sole owner of the LinkML API and of the
// `SchemaView` handles it hands out (those are live Scala.js objects and cannot cross a
// postMessage boundary). Keeping both halves in one file means target ids and option keys have a
// single definition that both sides are type-checked against.
import type { LinkMLApi, SchemaView } from "./linkml";
import type { OutputLang } from "./editor.js";

export interface Option {
  key: string;
  type: "checkbox" | "text" | "number" | "select";
  label: string;
  title?: string;
  placeholder?: string;
  choices?: string[];
  default?: string | number | boolean;
}

export type OptionValues = Record<string, string | number | boolean>;

/** Shape of the `SchemaValidationReport` that `LinkML.lint` returns.
 *
 * Hand-written because the API is untyped for now - see the TODO on `LinkMlJsApi.lint`. Everything
 * is optional: the serializer omits slots that are empty or equal to their default.
 */
export interface CodeRegion {
  start_line?: number;
  start_column?: number;
}
export interface IssueLocation {
  schema_id?: string;
  json_pointer?: string;
  code_region?: CodeRegion;
}
export interface ReportIssue {
  severity?: string;
  message?: string;
  details?: string;
  location?: IssueLocation;
}
export interface ValidationReport {
  validation_run_id?: string;
  issues?: ReportIssue[];
}

export type TargetResult = string | Record<string, string> | ValidationReport;

export interface Target {
  id: string;
  label: string;
  options: Option[];
  lang: OutputLang | ((o: OptionValues) => OutputLang);
  /** How to display what `call` returns. Defaults to text, or the file tabs for a `Record`. */
  view?: "report";
  /** Runs in the worker only. `api` is injected rather than imported so this module stays free of
   * the multi-MB Scala.js bundle, which the UI thread never loads. */
  call: (api: LinkMLApi, view: SchemaView, o: OptionValues) => TargetResult;
}

export const TARGETS: Target[] = [
  {
    id: "jsonSchema",
    label: "JSON Schema",
    lang: "json",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "Allow additionalProperties" },
      { key: "treeRootOverride", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
    ],
    call: (api, v, o) => api.jsonSchema(v, !!o.open, blankToUndef(o.treeRootOverride)),
  },
  {
    id: "shacl",
    label: "SHACL",
    lang: "turtle",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "sh:closed false" },
      { key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" },
    ],
    call: (api, v, o) => api.shacl(v, !!o.open, !!o.onlyClassesFromRootSchema),
  },
  {
    id: "rdfs",
    label: "RDFS",
    lang: "turtle",
    options: [{ key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" }],
    call: (api, v, o) => api.rdfs(v, !!o.onlyClassesFromRootSchema),
  },
  {
    id: "tableSchema",
    label: "Table Schema",
    lang: "json",
    options: [{ key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" }],
    call: (api, v, o) => api.tableSchema(v, blankToUndef(o.treeRoot)),
  },
  {
    id: "graphQl",
    label: "GraphQL",
    lang: "graphql",
    options: [
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "treeRoot" },
      { key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
    ],
    call: (api, v, o) => api.graphQl(v, String(o.pruningMode || "treeRoot"), blankToUndef(o.treeRoot)),
  },
  {
    id: "scala",
    label: "Scala code",
    lang: "scala",
    options: [{ key: "package", type: "text", label: "Package", default: "eu.neverblink.linkml.metamodel" }],
    call: (api, v, o) => api.scala(v, String(o.package || "eu.neverblink.linkml.metamodel")),
  },
  {
    id: "linkml",
    label: "Derived LinkML",
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    options: [
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "treeRoot" },
      { key: "skipDerivation", type: "checkbox", label: "Skip derivation" },
      { key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, v, o) =>
      api.linkml(v, String(o.pruningMode || "treeRoot"), !!o.skipDerivation, blankToUndef(o.treeRoot), String(o.outFormat || "yaml")),
  },
  {
    id: "lint",
    label: "Lint",
    lang: "json",
    view: "report",
    options: [
      { key: "inferMessages", type: "checkbox", label: "Messages", default: true },
    ],
    call: (api, v, o) => api.lint(v, !!o.inferMessages) as ValidationReport,
  },
];

export function targetById(id: string): Target | undefined {
  return TARGETS.find((t) => t.id === id);
}

function blankToUndef(v: string | number | boolean | undefined): string | undefined {
  const t = String(v ?? "").trim();
  return t === "" ? undefined : t;
}
