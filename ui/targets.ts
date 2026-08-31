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

/** Shape of the `BuildInfo` that `LinkML.buildInfo` returns, following model/build-info.yaml.
 *
 * Hand-written for the same reason as `ValidationReport`, and optional throughout for the same
 * reason too. Slots the JavaScript build never fills - `abi_version` - are left out entirely
 * rather than typed as always-absent.
 */
export interface BuildInfo {
  linkml_scala_version?: string;
  metamodel_version?: string;
  scala_version?: string;
  scala_js_version?: string;
  platform?: string;
  runtime?: string;
}

export interface Target {
  id: string;
  label: string;
  options: Option[];
  lang: OutputLang | ((o: OptionValues) => OutputLang);
  /** How to display what `call` returns. Defaults to text, or the file tabs for a `Record`.
   * `diagram` renders the result with Mermaid, behind a Diagram/Code tab pair. */
  view?: "report" | "diagram";
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
    // N-Triples is a subset of Turtle, so one mode highlights both formats.
    lang: "turtle",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "sh:closed false" },
      { key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (api, v, o) => api.shacl(v, !!o.open, !!o.onlyClassesFromRootSchema, String(o.format || "ttl")),
  },
  {
    id: "rdfs",
    label: "RDFS",
    lang: "turtle",
    options: [
      { key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (api, v, o) => api.rdfs(v, !!o.onlyClassesFromRootSchema, String(o.format || "ttl")),
  },
  {
    id: "frictionless",
    label: "Frictionless",
    lang: "json",
    options: [
      // Defaults to `skip`: narrower modes can leave the package empty, because a root schema may
      // only import its classes rather than define any.
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "skip" },
      { key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
      {
        key: "skipClassesWithoutIdentifier",
        type: "checkbox",
        label: "Identified only",
        title: "Skip classes that have no identifier slot",
      },
    ],
    call: (api, v, o) =>
      api.frictionless(v, String(o.pruningMode), blankToUndef(o.treeRoot), !!o.skipClassesWithoutIdentifier),
  },
  {
    id: "erDiagram",
    label: "ER diagram",
    lang: "text",
    view: "diagram",
    options: [
      // Defaults to `schema` rather than `treeRoot`: a diagram is for looking at the whole model,
      // and pruning to the tree root hides every class it cannot reach.
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "schema" },
      { key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
      {
        key: "optionalMarker",
        type: "checkbox",
        label: "Optional ?",
        title: "Mark optional attributes with a trailing '?' (needs Mermaid 11.16+)",
        default: true,
      },
    ],
    call: (api, v, o) =>
        api.erDiagram(v, String(o.pruningMode || "schema"), blankToUndef(o.treeRoot), !!o.optionalMarker),
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
