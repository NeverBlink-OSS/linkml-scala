// The catalog of everything the playground can run, shared by the UI and the worker: generators
// that turn a LinkML schema into something else, and importers that go the other way.
//
// The UI thread uses everything here *except* `call` - labels, option widgets and the output
// language. `call` runs only in the worker, which is the sole owner of the LinkML API and of the
// `SchemaView` handles it hands out (those are live Scala.js objects and cannot cross a
// postMessage boundary). Keeping both halves in one file means ids and option keys have a
// single definition that both sides are type-checked against.
import type { Format as OhiFormat } from "@neverblink/ohi";
import type { LinkMLApi, SchemaValidationReport, SchemaView } from "./linkml";
import type { OutputLang } from "./editor.js";
import { EXAMPLE_OSSIE, EXAMPLE_OWL } from "./examples.js";

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

/** The validation report types, generated from model/issue-types.yaml into linkml.d.ts. */
export type { IssueLocation, SchemaIssue, SchemaValidationReport } from "./linkml";

export type TargetResult = string | Record<string, string> | SchemaValidationReport;

/** The build info, generated from model/build-info.yaml into linkml.d.ts. */
export type { BuildInfo } from "./linkml";

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
  /** Starting point for the example in the load dialog. */
  example: string;
  /** Which files on ohi.dev this importer can read. When there is more than one, the load dialog
   * offers a choice between them, and the first is the default. */
  ohiFormats: OhiChoice[];
  /** Ids of the generators this undoes, if any. Both directions of each pair get the reverse
   * button in the output pane. Going back from the importer picks the first one, unless the
   * generator last used is also on the list. */
  reverses?: string[];
  /** How to highlight the input pane. Defaults to YAML. */
  inputLang?: OutputLang;
  call: (api: LinkMLApi, input: string, o: OptionValues) => string;
}

/** A file format on ohi.dev that an input pane can read. `label` names it on the format toggle,
 * `name` in the note on an ontology that has no such file. */
export interface OhiChoice {
  format: OhiFormat;
  label: string;
  name: string;
}

/** The generator the playground opens with. Shared links leave it out, so it must stay the same for
 * old links to keep working. */
export const DEFAULT_TARGET_ID = "jsonSchema";

/** Sorted by label. */
export const TARGETS: Target[] = [
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
    id: "linkml",
    label: "Derived LinkML",
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    options: [
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "treeRoot" },
      { key: "skipDerivation", type: "checkbox", label: "Skip derivation" },
      TREE_ROOT,
      { key: "fullUris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, v, o) =>
      api.linkml(v, String(o.pruningMode || "treeRoot"), !!o.skipDerivation, blankToUndef(o.treeRoot), String(o.outFormat || "yaml"), !!o.fullUris),
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
    id: "translation",
    label: "Key translations",
    lang: "json",
    options: [
      { key: "target", "type": "select", label: "Target name form", choices: ["base", "uri", "scala", "graphql", "frictionless", "ossie", "erdiagram", "json", "typescript", "pydantic"], default: "base" },
      {
        key: "derivedAttributes",
        type: "checkbox",
        label: "Derived attributes",
        title: "Also list inherited slots, mixin slots and slots from the class's slots list",
      },
    ],
    call: (api, v, o) => api.translation(v, String(o.target), !!o.derivedAttributes)
  },
  {
    id: "lint",
    label: "Lint",
    lang: "json",
    view: "report",
    options: [
      { key: "inferMessages", type: "checkbox", label: "Messages", default: true },
    ],
    call: (api, v, o) => api.lint(v, !!o.inferMessages),
  },
  {
    id: "owl",
    label: "OWL 2",
    lang: "turtle",
    options: [
      { key: "onlyRootSchema", type: "checkbox", label: "Root schema only" },
      { key: "metadataProfile", type: "select", label: "Descriptions", choices: ["rdfs", "linkml"], default: "rdfs" },
      { key: "permissibleValues", type: "select", label: "Permissible values", choices: ["individual", "class"], default: "individual" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (api, v, o) =>
      api.owl(
        v,
        !!o.onlyRootSchema,
        String(o.metadataProfile || "rdfs"),
        String(o.permissibleValues || "individual"),
        String(o.format || "ttl"),
      ),
  },
  {
    id: "pydantic",
    label: "Pydantic",
    lang: "python",
    options: [
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "includeNull", type: "checkbox", label: "Nulls", title: "Keep nulls when dumping" },
      { key: "open", type: "checkbox", label: "Open", title: "Allow additional properties" },
    ],
    call: (api, v, o) =>
      api.pydantic(
        v,
        String(o.pruningMode || "skip"),
        blankToUndef(o.treeRoot),
        !!o.includeNull,
        !!o.open,
      ),
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
    id: "scala",
    label: "Scala code",
    lang: "scala",
    options: [{ key: "package", type: "text", label: "Package", default: "eu.neverblink.linkml.metamodel" }],
    call: (api, v, o) => api.scala(v, String(o.package || "eu.neverblink.linkml.metamodel")),
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
    id: "typeScript",
    label: "TypeScript",
    lang: "typescript",
    options: [
      { key: "pruningMode", type: "select", label: "Pruning", choices: ["treeRoot", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "includeNull", type: "checkbox", label: "Nulls", title: "Allow null for optional slots" },
      { key: "open", type: "checkbox", label: "Open", title: "Allow additional properties" },
    ],
    call: (api, v, o) =>
      api.typeScript(
        v,
        String(o.pruningMode || "skip"),
        blankToUndef(o.treeRoot),
        !!o.includeNull,
        !!o.open,
      ),
  },
];

export const IMPORTERS: Importer[] = [
  {
    id: "ossie",
    label: "Apache Ossie",
    inputLabel: "Ossie ontology (YAML or JSON)",
    reverses: ["ossie"],
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    example: EXAMPLE_OSSIE,
    ohiFormats: [{ format: "ossie-yaml", label: "Ossie", name: "Ossie" }],
    options: [
      { key: "schemaId", type: "text", label: "Schema id", placeholder: "https://… (optional)" },
      { key: "fullUris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) =>
      api.fromOssie(input, blankToUndef(o.schemaId), String(o.outFormat || "yaml"), !!o.fullUris),
  },
  {
    id: "owl",
    label: "OWL 2 / RDFS",
    inputLabel: "OWL 2 or RDFS ontology (Turtle)",
    reverses: ["owl", "rdfs"],
    inputLang: "turtle",
    lang: (o) => (o.outFormat === "json" ? "json" : "yaml"),
    example: EXAMPLE_OWL,
    // The importer reads Turtle only, so the RDF/XML files on ohi.dev are not on offer.
    ohiFormats: [
      { format: "owl-turtle", label: "OWL", name: "OWL (Turtle)" },
      { format: "rdfs-turtle", label: "RDFS", name: "RDFS (Turtle)" },
    ],
    options: [
      {
        key: "listNotImported",
        type: "checkbox",
        label: "Not imported",
        title: "List what could not be imported, in comments at the top",
        showIf: (o) => o.outFormat !== "json",
      },
      { key: "fullUris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "outFormat", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) =>
      api.fromOwl(input, undefined, String(o.outFormat || "yaml"), !!o.listNotImported, undefined, undefined, !!o.fullUris),
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
