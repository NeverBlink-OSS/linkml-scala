// The catalog of everything the playground can run, shared by the UI and the worker: generators
// that turn a LinkML schema into something else, and importers that go the other way.
//
// The UI thread uses everything here *except* `call` - labels, option widgets and the output
// language. `call` runs only in the worker, which is the sole owner of the LinkML API and of the
// `SchemaView` handles it hands out (those are live Scala.js objects and cannot cross a
// postMessage boundary). Keeping both halves in one file means ids and option keys have a
// single definition that both sides are type-checked against.
import type { LinkMLApi, SchemaView } from "./linkml";
import type { OutputLang } from "./editor.js";
import { EXAMPLE_OSSIE } from "./examples.js";

export interface Option {
  key: string;
  type: "checkbox" | "text" | "number" | "select";
  label: string;
  title?: string;
  placeholder?: string;
  choices?: string[];
  default?: string | number | boolean;
  /** Shown only when this holds for the step's current option values. Absent means always shown. */
  showIf?: (o: OptionValues) => boolean;
}

export type OptionValues = Record<string, string | number | boolean>;

/** The tree root override, for the generators that only reach it through the pruning mode.
 *
 * Those pass it to `PruningMode(mode, treeRoot)`, which keeps it in tree-root mode and drops it in
 * every other one - so outside that mode the field does nothing and is not offered. JSON Schema is
 * the exception: it has no pruning mode and applies its override unconditionally, so it declares a
 * field of its own.
 */
const TREE_ROOT: Option = {
  key: "treeRoot",
  type: "text",
  label: "Tree root",
  placeholder: "Class name (optional)",
  showIf: (o) => o.pruningMode === "treeRoot",
};

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

/** What a [[Target]] and an [[Importer]] have in common: a tab, a set of option widgets and an
 * output language. The UI works in terms of this wherever the direction does not matter. */
export interface Step {
  id: string;
  label: string;
  options: Option[];
  lang: OutputLang | ((o: OptionValues) => OutputLang);
}

/** A generator: LinkML in, something else out. */
export interface Target extends Step {
  /** How to display what `call` returns. Defaults to text, or the file tabs for a `Record`.
   * `diagram` renders the result with Mermaid, behind a Diagram/Code tab pair. */
  view?: "report" | "diagram";
  /** Runs in the worker only. `api` is injected rather than imported so this module stays free of
   * the multi-MB Scala.js bundle, which the UI thread never loads. */
  call: (api: LinkMLApi, view: SchemaView, o: OptionValues) => TargetResult;
}

/** An importer: some other format in, LinkML out.
 *
 * Unlike a generator it never sees a `SchemaView` - there is no LinkML schema yet - so it is handed
 * the input document as text.
 */
export interface Importer extends Step {
  /** What the input pane calls the document it wants. */
  inputLabel: string;
  /** Starting point for the Load example button. */
  example: string;
  /** Id of the generator this undoes, when there is one. Both directions of that pair get the
   * reverse button in the output pane. */
  reverses?: string;
  call: (api: LinkMLApi, input: string, o: OptionValues) => string;
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
    id: "ossie",
    label: "Apache Ossie",
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    options: [
      // Defaults to `skip` for the same reason as Frictionless: a root schema may only import its
      // classes, and an ontology with no concepts at all is not valid Ossie.
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, v, o) =>
        api.ossie(v, String(o.pruningMode || "skip"), blankToUndef(o.treeRoot), String(o.outFormat || "yaml")),
  },
  {
    id: "frictionless",
    label: "Frictionless",
    lang: "json",
    options: [
      // Defaults to `skip`: narrower modes can leave the package empty, because a root schema may
      // only import its classes rather than define any.
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "skip" },
      TREE_ROOT,
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
      TREE_ROOT,
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
      TREE_ROOT,
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
      TREE_ROOT,
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, v, o) =>
      api.linkml(v, String(o.pruningMode || "treeRoot"), !!o.skipDerivation, blankToUndef(o.treeRoot), String(o.outFormat || "yaml")),
  },
  {
    id: "translation",
    label: "Key translations",
    lang: "json",
    options: [
      { key: "target", "type": "select", label: "Target name form", choices: ["base", "uri", "scala", "graphql", "frictionless", "ossie"], default: "base" },
    ],
    call: (api, v, o) => api.translation(v, String(o.target))
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

export const IMPORTERS: Importer[] = [
  {
    id: "ossie",
    label: "Apache Ossie",
    inputLabel: "Ossie ontology (YAML or JSON)",
    reverses: "ossie",
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    example: EXAMPLE_OSSIE,
    options: [
      { key: "schemaId", type: "text", label: "Schema id", placeholder: "https://… (optional)" },
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) =>
      api.fromOssie(input, blankToUndef(o.schemaId), String(o.outFormat || "yaml")),
  },
];

export function targetById(id: string): Target | undefined {
  return TARGETS.find((t) => t.id === id);
}

export function importerById(id: string): Importer | undefined {
  return IMPORTERS.find((i) => i.id === id);
}

function blankToUndef(v: string | number | boolean | undefined): string | undefined {
  const t = String(v ?? "").trim();
  return t === "" ? undefined : t;
}
