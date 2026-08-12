// Generation worker. Owns the LinkML API, the parsed `SchemaView` and all generator calls.
import { targetById, type OptionValues, type TargetResult, type ValidationReport } from "./targets.js";
import type { LinkMLApi, LoadResult } from "./linkml";

const LINKML_BUNDLE_URL = "./linkml.js";

// The Scala.js bundle expects a Node-ish `process` global.
(globalThis as { process?: unknown }).process ??= { cwd: () => "/" };

// Started at module load so the multi-MB bundle is already parsing while the page renders.
// Requests that arrive before it resolves simply await it.
const apiPromise: Promise<LinkMLApi> = import(LINKML_BUNDLE_URL).then((m: { LinkML: LinkMLApi }) => m.LinkML);

export interface GenerateRequest {
  id: number;
  schema: string;
  targetId: string;
  options: OptionValues;
}

/** How the UI should render `result`: as editor text, as the Scala file tabs, as the report view, or
 * as a Mermaid diagram. */
export type ResultKind = "text" | "files" | "report" | "diagram";

export type GenerateResponse =
  | {
      id: number;
      ok: true;
      /** Echoed back so the UI renders with the target that was asked for, not the current one. */
      targetId: string;
      kind: ResultKind;
      result: TargetResult;
      /** Time spent parsing, or null when the cached parse was reused. */
      loadMs: number | null;
      genMs: number;
      /** Set when the schema failed to load at all, so the report is a failure rather than a result. */
      fatal?: true;
    }
  | { id: number; ok: false; error: string };

// The UI tsconfig ships the DOM lib rather than webworker, and pulling lib.webworker in here would
// collide with it. Only these two globals are used, so declare them exactly - which also types
// both ends of the protocol instead of leaving them as `any`.
declare const self: {
  onmessage: ((e: MessageEvent<GenerateRequest>) => void) | null;
  postMessage: (message: GenerateResponse) => void;
};

// Parse the schema once and reuse it across target/option changes; only re-parse when the input
// text actually changes.
let cachedSchema: { text: string; loaded: LoadResult } | null = null;

self.onmessage = async (e: MessageEvent<GenerateRequest>) => {
  const { id, schema, targetId, options } = e.data;
  try {
    const api = await apiPromise;

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
      reply({ id, ok: true, targetId, kind: "report", result: plain(report), loadMs, genMs: 0, fatal: true });
      return;
    }

    const target = targetById(targetId);
    if (!target) throw new Error(`Unknown target: ${targetId}`);

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
    reply({ id, ok: true, targetId, kind, result: kind === "report" ? plain(result) : result, loadMs, genMs });
  } catch (err) {
    reply({ id, ok: false, error: err instanceof Error ? err.toString() : String(err) });
  }
};

function reply(msg: GenerateResponse): void {
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
