// The catalog of everything the playground can run, shared by the UI and the worker: generators
// that turn a LinkML schema into something else, and importers that go the other way.
//
// The UI thread uses everything here *except* `call` - labels, option widgets and the output
// language. `call` runs only in the worker, which is the sole owner of the LinkML API and of the
// `SchemaView` handles it hands out (those are live Scala.js objects and cannot cross a
// postMessage boundary). Keeping both halves in one file means ids and option keys have a
// single definition that both sides are type-checked against.
import type { Format as OhiFormat } from "@neverblink/ohi";
import type { LinkMLApi, SchemaStats, SchemaValidationReport, SchemaView } from "./linkml";
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
 * Outside the `tree_root` mode the field does nothing, so it is not offered. JSON Schema is the
 * exception: it has no pruning mode and applies its override unconditionally, so it declares a
 * field of its own.
 */
const TREE_ROOT: Option = {
  key: "tree_root",
  type: "text",
  label: "Tree root",
  placeholder: "Class name (optional)",
  showIf: (o) => o.pruning_mode === "tree_root",
};

/** The validation report types, generated from model/issue-types.yaml into linkml.d.ts. */
export type { IssueLocation, SchemaIssue, SchemaValidationReport } from "./linkml";

/** The schema statistics, generated from model/schema-stats.yaml into linkml.d.ts. */
export type { SchemaStats } from "./linkml";

export type TargetResult = string | Record<string, string> | SchemaValidationReport | SchemaStats;

/** The build info, generated from model/build-info.yaml into linkml.d.ts. */
export type { BuildInfo } from "./linkml";

/** What a [[Target]] and an [[Importer]] have in common: a tab, a set of option widgets and an
 * output language. The UI works in terms of this wherever the direction does not matter. */
export interface Step {
  id: string;
  label: string;
  docs: string;
  options: Option[];
  lang: OutputLang | ((o: OptionValues) => OutputLang);
}

/** A generator: LinkML in, something else out. */
export interface Target extends Step {
  /** How to display what `call` returns. Defaults to text, or the file tabs for a `Record`.
   * `diagram` renders the result with Mermaid, behind a Diagram/Code tab pair. `page` renders it
   * as Markdown, behind a Page/Code tab pair. `stats` renders the statistics as tables. */
  view?: "report" | "diagram" | "page" | "stats";
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

/** The generator the playground opens with. */
export const DEFAULT_TARGET_ID = "docs";

/** Sorted by label. */
export const TARGETS: Target[] = [
  {
    id: "ossie",
    label: "Apache Ossie",
    docs: "generators/ossie/",
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    options: [
      // Defaults to `skip` for the same reason as Frictionless: a root schema may only import its
      // classes, and an ontology with no concepts at all is not valid Ossie.
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (_, v, o) => v.ossie(converterOptions(o)),
  },
  {
    id: "linkml",
    label: "Derived LinkML",
    docs: "generators/linkml/",
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    options: [
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "tree_root" },
      { key: "skip_class_derivation", type: "checkbox", label: "Skip derivation" },
      TREE_ROOT,
      { key: "full_uris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (_, v, o) => v.linkml(converterOptions(o)),
  },
  {
    id: "docs",
    label: "Documentation",
    docs: "generators/docs/",
    lang: "text",
    view: "page",
    options: [
      // Defaults to `schema`, like the ER diagram: documentation is for the whole model.
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "schema" },
      TREE_ROOT,
      { key: "metadata_language", type: "text", label: "Language", placeholder: "en", title: "Language of titles and descriptions given in more than one language" },
    ],
    // Always one document: the page view links its sections with anchors, and a file tab per class
    // would be too many for a real schema.
    call: (_, v, o) =>
      v.docs({ ...converterOptions(o), single_file: true })["docs.md"] ?? "",
  },
  {
    id: "erDiagram",
    label: "ER diagram",
    docs: "generators/er-diagram/",
    lang: "text",
    view: "diagram",
    options: [
      // Defaults to `schema` rather than `tree_root`: a diagram is for looking at the whole model,
      // and pruning to the tree root hides every class it cannot reach.
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "schema" },
      TREE_ROOT,
      {
        key: "optional_marker",
        type: "checkbox",
        label: "Optional ?",
        title: "Mark optional attributes with a trailing '?' (needs Mermaid 11.16+)",
        default: true,
      },
    ],
    call: (_, v, o) => v.erDiagram(converterOptions(o)),
  },
  {
    id: "frictionless",
    label: "Frictionless",
    docs: "generators/frictionless/",
    lang: "json",
    options: [
      // Defaults to `skip`: narrower modes can leave the package empty, because a root schema may
      // only import its classes rather than define any.
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      {
        key: "skip_classes_without_identifier",
        type: "checkbox",
        label: "Identified only",
        title: "Skip classes that have no identifier slot",
      },
    ],
    call: (_, v, o) => v.frictionless(converterOptions(o)),
  },
  {
    id: "graphql",
    label: "GraphQL",
    docs: "generators/graphql/",
    lang: "graphql",
    options: [
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "tree_root" },
      TREE_ROOT,
    ],
    call: (_, v, o) => v.graphql(converterOptions(o)),
  },
  {
    id: "jsonSchema",
    label: "JSON Schema",
    docs: "generators/json-schema/",
    lang: "json",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "Allow additionalProperties" },
      { key: "tree_root", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
    ],
    call: (_, v, o) => v.jsonSchema(converterOptions(o)),
  },
  {
    id: "translation",
    label: "Key translations",
    docs: "generators/translation/",
    lang: "json",
    options: [
      { key: "target", "type": "select", label: "Target name form", choices: ["base", "uri", "scala", "graphql", "frictionless", "ossie", "erdiagram", "json", "typescript", "pydantic", "docs"], default: "base" },
      {
        key: "derived_attributes",
        type: "checkbox",
        label: "Derived attributes",
        title: "Also list inherited slots, mixin slots and slots from the class's slots list",
      },
    ],
    call: (_, v, o) => v.translation(converterOptions(o)),
  },
  {
    id: "lint",
    label: "Lint",
    docs: "validation/",
    lang: "json",
    view: "report",
    options: [
      { key: "inferMessages", type: "checkbox", label: "Messages", default: true },
    ],
    call: (api, v, o) => api.lint(v, !!o.inferMessages),
  },
  {
    id: "stats",
    label: "Statistics",
    docs: "generators/stats/",
    lang: "json",
    view: "stats",
    options: [
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "schema" },
      TREE_ROOT,
    ],
    call: (_, v, o) => v.stats(converterOptions(o)),
  },
  {
    id: "owl",
    label: "OWL 2",
    docs: "generators/owl/",
    lang: "turtle",
    options: [
      { key: "only_root_schema", type: "checkbox", label: "Root schema only" },
      { key: "metadata_profile", type: "select", label: "Descriptions", choices: ["rdfs", "linkml"], default: "rdfs" },
      { key: "permissible_values", type: "select", label: "Permissible values", choices: ["individual", "class"], default: "individual" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (_, v, o) => v.owl(converterOptions(o)),
  },
  {
    id: "pydantic",
    label: "Pydantic",
    docs: "generators/pydantic/",
    lang: "python",
    options: [
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "include_null", type: "checkbox", label: "Nulls", title: "Keep nulls when dumping" },
      { key: "open", type: "checkbox", label: "Open", title: "Allow additional properties" },
    ],
    call: (_, v, o) => v.pydantic(converterOptions(o)),
  },
  {
    id: "rdfs",
    label: "RDFS",
    docs: "generators/rdfs/",
    lang: "turtle",
    options: [
      { key: "only_classes_from_root_schema", type: "checkbox", label: "Root schema only" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (_, v, o) => v.rdfs(converterOptions(o)),
  },
  {
    id: "scala",
    label: "Scala code",
    docs: "generators/scala/",
    lang: "scala",
    options: [
      { key: "package", type: "text", label: "Package", default: "eu.neverblink.linkml.metamodel" },
      {
        key: "error_handling",
        type: "select",
        label: "Function errors",
        title: "How the functions of the schema report their errors",
        choices: ["annotation", "either"],
        default: "annotation",
      },
    ],
    call: (_, v, o) => v.scala(converterOptions(o)),
  },
  {
    id: "shacl",
    label: "SHACL",
    docs: "generators/shacl/",
    // N-Triples is a subset of Turtle, so one mode highlights both formats.
    lang: "turtle",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "sh:closed false" },
      { key: "only_classes_from_root_schema", type: "checkbox", label: "Root schema only" },
      { key: "format", type: "select", label: "Format", choices: ["ttl", "nt"], default: "ttl" },
    ],
    call: (_, v, o) => v.shacl(converterOptions(o)),
  },
  {
    id: "typescript",
    label: "TypeScript",
    docs: "generators/typescript/",
    lang: "typescript",
    options: [
      { key: "pruning_mode", type: "select", label: "Pruning", choices: ["tree_root", "schema", "skip"], default: "skip" },
      TREE_ROOT,
      { key: "include_null", type: "checkbox", label: "Nulls", title: "Allow null for optional slots" },
      { key: "open", type: "checkbox", label: "Open", title: "Allow additional properties" },
      {
        key: "optional_defaults",
        type: "checkbox",
        label: "Optional defaults",
        title: "Let slots with an ifabsent be left out",
      },
      {
        key: "error_handling",
        type: "select",
        label: "Function errors",
        title: "How the functions of the schema report their errors",
        choices: ["exception", "result"],
        default: "exception",
      },
    ],
    call: (_, v, o) => v.typescript(converterOptions(o)),
  },
];

export const IMPORTERS: Importer[] = [
  {
    id: "ossie",
    label: "Apache Ossie",
    docs: "importers/ossie/",
    inputLabel: "Ossie ontology (YAML or JSON)",
    reverses: ["ossie"],
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    example: EXAMPLE_OSSIE,
    ohiFormats: [{ format: "ossie-yaml", label: "Ossie", name: "Ossie" }],
    options: [
      { key: "schema_id", type: "text", label: "Schema id", placeholder: "https://… (optional)" },
      { key: "full_uris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) => api.fromOssie(input, converterOptions(o)).schema,
  },
  {
    id: "owl",
    label: "OWL 2 / RDFS",
    docs: "importers/owl/",
    inputLabel: "OWL 2 or RDFS ontology (Turtle)",
    reverses: ["owl", "rdfs"],
    inputLang: "turtle",
    lang: (o) => (o.format === "json" ? "json" : "yaml"),
    example: EXAMPLE_OWL,
    // The importer reads Turtle only, so the RDF/XML files on ohi.dev are not on offer.
    ohiFormats: [
      { format: "owl-turtle", label: "OWL", name: "OWL (Turtle)" },
      { format: "rdfs-turtle", label: "RDFS", name: "RDFS (Turtle)" },
    ],
    options: [
      {
        key: "list_not_imported",
        type: "checkbox",
        label: "Not imported",
        title: "List what could not be imported, in comments at the top",
        showIf: (o) => o.format !== "json",
      },
      { key: "full_uris", type: "checkbox", label: "Full URIs", title: "Write URIs in full, instead of as CURIEs" },
      { key: "format", type: "select", label: "Format", choices: ["yaml", "json"], default: "yaml" },
    ],
    call: (api, input, o) => {
      // Not an option of the importer, which always returns the warnings.
      const { list_not_imported, ...options } = o;
      const { schema, warnings } = api.fromOwl(input, converterOptions(options));
      if (!list_not_imported || o.format === "json") return schema;
      return warnings.map((w) => `# Not imported: ${w}\n`).join("") + schema;
    },
  },
];

export function targetById(id: string): Target | undefined {
  return TARGETS.find((t) => t.id === id);
}

export function importerById(id: string): Importer | undefined {
  return IMPORTERS.find((i) => i.id === id);
}

/** The option values of a generator or importer as its options object, the keys being the names
 * in model/converter. Blank text fields are left out, so they take their defaults. */
function converterOptions<T>(o: OptionValues): Partial<T> {
  return Object.fromEntries(
    Object.entries(o).filter(([, v]) => !(typeof v === "string" && v.trim() === "")),
  ) as Partial<T>;
}
