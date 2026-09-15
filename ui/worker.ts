// Conversion worker. Owns the LinkML API, the parsed `SchemaView` and all generator and importer
// calls.
import {
  importerById,
  targetById,
  type BuildInfo,
  type OptionValues,
  type TargetResult,
  type ValidationReport,
} from "./targets.js";
import type { LinkMLApi, LoadResult } from "./linkml";

const LINKML_BUNDLE_URL = "./linkml.js";

// The Scala.js bundle expects a Node-ish `process` global.
(globalThis as { process?: unknown }).process ??= { cwd: () => "/" };

// Started at module load so the multi-MB bundle is already parsing while the page renders.
// Requests that arrive before it resolves simply await it.
const apiPromise: Promise<LinkMLApi> = import(LINKML_BUNDLE_URL).then((m: { LinkML: LinkMLApi }) => m.LinkML);

/** `fromLinkml` runs a generator over the schema in `input`; `toLinkml` runs an importer over the
 * source document in `input`. */
export type Direction = "fromLinkml" | "toLinkml";

export interface ConvertRequest {
  id: number;
  direction: Direction;
  /** The LinkML schema, or the document to import. */
  input: string;
  /** Target id for `fromLinkml`, importer id for `toLinkml`. The two sets overlap. */
  stepId: string;
  options: OptionValues;
}

/** How the UI should render `result`: as editor text, as the Scala file tabs, as the report view, or
 * as a Mermaid diagram. */
export type ResultKind = "text" | "files" | "report" | "diagram";

export type ConvertResponse =
  | {
      id: number;
      ok: true;
      /** Both echoed back so the UI renders with what was asked for, not what is selected now. */
      direction: Direction;
      stepId: string;
      kind: ResultKind;
      result: TargetResult;
      /** Time spent parsing the schema: null when the cached parse was reused, and for an importer,
       * which has no schema to parse. */
      loadMs: number | null;
      genMs: number;
      /** Set when the schema failed to load at all, so the report is a failure rather than a result. */
      fatal?: true;
    }
  | { id: number; ok: false; error: string };

/** Sent once, unprompted, when the bundle finishes loading. Every other message answers a request,
 * so the UI tells the two apart by looking for `build`. */
export interface BuildMessage {
  build: BuildInfo;
}

export type WorkerMessage = ConvertResponse | BuildMessage;

// The UI tsconfig ships the DOM lib rather than webworker, and pulling lib.webworker in here would
// collide with it. Only these two globals are used, so declare them exactly - which also types
// both ends of the protocol instead of leaving them as `any`.
declare const self: {
  onmessage: ((e: MessageEvent<ConvertRequest>) => void) | null;
  postMessage: (message: WorkerMessage) => void;
};

// Announced rather than asked for: there is nothing to ask about, and the page wants the version
// whether or not anyone has generated anything yet. Already plain data - `buildInfo` hands back a
// parsed JSON object - so unlike a report it needs no normalizing before it crosses the boundary.
apiPromise
  .then((api) => self.postMessage({ build: api.buildInfo() as BuildInfo }))
  .catch(() => {
    // A bundle that failed to load reports itself through the first generation request.
  });

// Parse the schema once and reuse it across target/option changes; only re-parse when the input
// text actually changes.
let cachedSchema: { text: string; loaded: LoadResult } | null = null;

self.onmessage = async (e: MessageEvent<ConvertRequest>) => {
  const { id, direction, input: schema, stepId, options } = e.data;
  try {
    const api = await apiPromise;

    if (direction === "toLinkml") {
      const importer = importerById(stepId);
      if (!importer) throw new Error(`Unknown importer: ${stepId}`);
      const t0 = performance.now();
      const result = importer.call(api, schema, options);
      const genMs = Math.round(performance.now() - t0);
      reply({ id, ok: true, direction, stepId, kind: "text", result, loadMs: null, genMs });
      return;
    }

    let loadMs: number | null = null;
    if (!cachedSchema || cachedSchema.text !== schema) {
      // Drop the old parse before starting a new one - these are large, and holding both at once
      // is what pushes a big schema over the heap limit.
      cachedSchema = null;
      const t0 = performance.now();
      // The empty object is the import map (filename -> YAML). The UI has no extra imports.
      const loaded = api.loadFromString(schema, {});
      loadMs = Math.round(performance.now() - t0);
      cachedSchema = { text: schema, loaded };
    }

    const { view, report } = cachedSchema.loaded;

    // Fatal problems mean there is no view to generate from, so every target shows the report.
    if (!view) {
      reply({ id, ok: true, direction, stepId, kind: "report", result: plain(report), loadMs, genMs: 0, fatal: true });
      return;
    }

    const target = targetById(stepId);
    if (!target) throw new Error(`Unknown target: ${stepId}`);

    const t1 = performance.now();
    const result = target.call(api, view, options);
    const genMs = Math.round(performance.now() - t1);

    const kind: ResultKind = target.view === "report"
      ? "report"
      : target.view === "diagram"
      ? "diagram"
      : typeof result === "object"
      ? "files"
      : "text";
    reply({ id, ok: true, direction, stepId, kind, result: kind === "report" ? plain(result) : result, loadMs, genMs });
  } catch (err) {
    reply({ id, ok: false, error: err instanceof Error ? err.toString() : String(err) });
  }
};

function reply(msg: ConvertResponse): void {
  self.postMessage(msg);
}

/** Reduce a Scala.js-produced report to plain data.
 *
 * Reports are the only structured (non-string) values that cross the boundary, and structured
 * clone throws on anything the bundle might hang off them. They are small, so normalizing is
 * cheap insurance against a DataCloneError taking down the whole response.
 */
function plain(report: unknown): ValidationReport {
  return JSON.parse(JSON.stringify(report ?? {})) as ValidationReport;
}
