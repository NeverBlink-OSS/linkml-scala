import { createInput, createOutput, setDoc, setInputLang, setOutput, type OutputLang } from "./editor.js";
import { EXAMPLE_SCHEMA } from "./examples.js";
import {
  IMPORTERS,
  TARGETS,
  DEFAULT_TARGET_ID,
  importerById,
  targetById,
  type BuildInfo,
  type Importer,
  type IssueLocation,
  type OhiChoice,
  type Option,
  type OptionValues,
  type SchemaIssue,
  type Step,
  type Target,
  type SchemaValidationReport,
} from "./targets.js";
import type { ConvertRequest, ConvertResponse, Direction, WorkerMessage } from "./worker.js";
import type { CatalogClient, Format as OhiFormat, SearchHit } from "@neverblink/ohi";

// The LinkML schema lives here; each importer's own input document gets this plus a suffix.
const INPUT_STORAGE_KEY = "linkml-ui-input";
// Query parameter holding the link the schema was loaded from, so the address bar is a share link.
const URL_PARAM = "url";
// Takes its place for a file from ohi.dev: `namespace/name@version`, which reads better than the
// file's own address. `format` follows only when it is not the input pane's default one.
const OHI_PARAM = "ohi";
const FORMAT_PARAM = "format";
// Next to either, the importer whose input pane the link belongs to. Absent means the LinkML pane.
const INPUT_PARAM = "input";
// And the generator whose output the link shows. Absent in the other direction, where `input`
// names the importer instead.
const TARGET_PARAM = "target";
const LINK_PARAMS = [URL_PARAM, OHI_PARAM, FORMAT_PARAM, INPUT_PARAM, TARGET_PARAM];
// Resolved against dist/app.js at runtime, so it lands on the sibling dist/worker.js. Built from a
// variable rather than a literal to keep esbuild from trying to resolve it at bundle time.
const WORKER_URL = "./worker.js";
// Mermaid, bundled into dist/mermaid by build.mjs. Imported on first use only: it and its chunks
// are ~210 KB gzipped, which nobody who never opens the ER diagram tab should pay for.
const MERMAID_URL = "./mermaid/mermaid.js";
// The ohi.dev SDK, bundled into dist/ohi.js by build.mjs. Imported the first time the load dialog
// opens, for the same reason as Mermaid.
const OHI_URL = "./ohi.js";
// The Markdown renderer for the documentation page view, bundled into dist/markdown by build.mjs and
// imported on first use only, like Mermaid.
const MARKDOWN_URL = "./markdown/markdown.js";

// ── State ─────────────────────────────────────────────────────────────────

/** Which way the conversion runs: `fromLinkml` picks a generator, `toLinkml` an importer. */
let direction: Direction = "fromLinkml";
let activeTargetId = DEFAULT_TARGET_ID;
let activeImporterId = IMPORTERS[0]!.id;

const DIRECTIONS = ["fromLinkml", "toLinkml"] as const;

function steps(dir: Direction): Step[] {
  return dir === "fromLinkml" ? TARGETS : IMPORTERS;
}

/** Generators and importers each have their own ids and the two sets overlap - "ossie" is both - so
 * anything stored per step is keyed by the direction as well. */
function stepKey(dir: Direction, id: string): string {
  return `${dir}:${id}`;
}

function activeStep(): Step {
  return direction === "fromLinkml"
    ? TARGETS.find((t) => t.id === activeTargetId)!
    : IMPORTERS.find((i) => i.id === activeImporterId)!;
}

function activeKey(): string {
  return stepKey(direction, activeStep().id);
}

function stepById(dir: Direction, id: string): Step | undefined {
  return dir === "fromLinkml" ? targetById(id) : importerById(id);
}

function optionDefault(opt: Option): string | number | boolean {
  return opt.default ?? (opt.type === "checkbox" ? false : "");
}

const optionValues: Record<string, OptionValues> = Object.fromEntries(
  DIRECTIONS.flatMap((dir) =>
    steps(dir).map((s) => [
      stepKey(dir, s.id),
      Object.fromEntries(s.options.map((o) => [o.key, optionDefault(o)])),
    ]),
  ),
);
// Which file tab is open, per target.
const activeFile: Record<string, string | null> = {};
// The validation report is rendered as DOM rather than into the output editor, so keep a
// plain-text rendition of it around for the Copy button. Non-null exactly while the report
// is the visible view.
let reportText: string | null = null;
// Which half of the diagram target is showing. Sticky across regenerations, so typing in the
// schema does not throw you back to the other tab.
let diagramTab: "diagram" | "code" = "diagram";
// The Mermaid source currently on screen, kept so the tab buttons and the theme toggle can
// re-render without waiting for another generation. Non-null exactly while the diagram view is up.
let diagramSource: string | null = null;
// Which half of the documentation target is showing, sticky like `diagramTab`.
let pageTab: "page" | "code" = "page";
// The Markdown currently on screen. Non-null exactly while the page view is up.
let pageSource: string | null = null;
let generateTimer: ReturnType<typeof setTimeout> | undefined;
let diagramTimer: ReturnType<typeof setTimeout> | undefined;
let busyTimer: ReturnType<typeof setTimeout> | undefined;
// Every request is numbered and only the newest one is rendered. Generation is async now, so a
// burst of tab clicks would otherwise let a slow earlier result land on top of a fast later one.
let requestId = 0;
let pendingId: number | null = null;

// ── Input documents ─────────────────────────────────────────────────────────

/** One document per input format: the LinkML schema every generator reads, plus one of its own per
 * importer. */
interface InputDoc {
  text: string;
  /** The link it was loaded from, and exactly what that link served, while it still holds it. The
   * change listener compares the two to tell "this is what the link gives you" apart from "the user
   * has edited it since". */
  link: string | null;
  remote: string | null;
  /** Set alongside `link` when that link is a file on ohi.dev, which the address bar then names
   * by package instead. */
  ohi: OhiRef | null;
}

/** A file on ohi.dev, by package rather than by address. */
interface OhiRef {
  /** `namespace/name`. */
  pkg: string;
  version: string;
  format: OhiFormat;
}

const LINKML_INPUT = "linkml";

function inputKey(): string {
  return direction === "fromLinkml" ? LINKML_INPUT : stepKey(direction, activeImporterId);
}

function storageKey(key: string): string {
  return key === LINKML_INPUT ? INPUT_STORAGE_KEY : `${INPUT_STORAGE_KEY}:${key}`;
}

/** What the load dialog's example puts in the pane, and what a first-time visitor starts with. */
function exampleInput(): string {
  return direction === "fromLinkml" ? EXAMPLE_SCHEMA : (activeStep() as Importer).example;
}

const inputDocs: Record<string, InputDoc> = {};

function inputDoc(): InputDoc {
  const key = inputKey();
  return (inputDocs[key] ??= {
    text: localStorage.getItem(storageKey(key)) ?? exampleInput(),
    link: null,
    remote: null,
    ohi: null,
  });
}

/** Which document the editor is showing, so that switching target within one direction does not
 * needlessly replace it. */
let shownInput = "";

function swapInput(): void {
  const key = inputKey();
  if (key === shownInput) return;
  shownInput = key;
  setDoc(inputView, inputDoc().text);
  syncLinkParams();
}

// ── DOM refs ────────────────────────────────────────────────────────────────

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const $fileTabs = $("fileTabs");
const $outputPanel = $("outputPanel");
const $reportView = $("reportView");
const $diagramView = $("diagramView");
const $pageView = $("pageView");
const $outputEditorHost = $("outputEditor");
const $targetCombo = $("targetCombo");
const $targetButton = $<HTMLButtonElement>("targetButton");
const $targetLabel = $("targetLabel");
const $targetPopup = $("targetPopup");
const $targetSearch = $<HTMLInputElement>("targetSearch");
const $targetList = $("targetList");
const $dirToggle = $("dirToggle");
const $inputSub = $("inputSub");
const $outputSub = $("outputSub");
const $optionsRow = $("optionsRow");
const $generateBtn = $<HTMLButtonElement>("generateBtn");
const $statusPill = $("statusPill");
const $autoGenerate = $<HTMLInputElement>("autoGenerate");
const $copyOutput = $<HTMLButtonElement>("copyOutput");
const $reverseOutput = $<HTMLButtonElement>("reverseOutput");
const $loadFrom = $<HTMLButtonElement>("loadFrom");
const $clearInput = $<HTMLButtonElement>("clearInput");
const $themeToggle = $<HTMLButtonElement>("themeToggle");
const $themeIconMoon = $("themeIconMoon");
const $themeIconSun = $("themeIconSun");

// ── Editors ───────────────────────────────────────────────────────────────

const inputView = createInput($("inputEditor"), inputDoc().text, (value) => {
  const doc = inputDoc();
  doc.text = value;
  localStorage.setItem(storageKey(inputKey()), value);
  // Once the text on screen differs from what the link serves, a shared address would hand the
  // other person something other than what you are looking at, so drop the link.
  if (value !== doc.remote) {
    doc.link = null;
    doc.ohi = null;
    clearLinkParams();
  }
  scheduleGenerate();
});
const outputView = createOutput($("outputEditor"));

function stepLang(step: Step, dir: Direction = direction): OutputLang {
  return typeof step.lang === "function" ? step.lang(optionValues[stepKey(dir, step.id)]!) : step.lang;
}

// ── Direction and labels ────────────────────────────────────────────────────

/** Everything that follows from the direction and the step selected within it. */
function renderStep(): void {
  setInputLang(inputView, direction === "toLinkml" ? ((activeStep() as Importer).inputLang ?? "yaml") : "yaml");
  renderCombo();
  renderOptions();
  renderLabels();
  updateReverseButton();
}

/** Take a new direction or step, including the input document that goes with it. */
function selectStep(): void {
  renderStep();
  swapInput();
  // The address bar names the step as well, and `swapInput` does nothing when the document stays.
  syncLinkParams();
  scheduleGenerate(0);
}

function pickStep(step: Step): void {
  if (direction === "fromLinkml") activeTargetId = step.id;
  else activeImporterId = step.id;
  closeCombo(true);
  selectStep();
}

function renderLabels(): void {
  const importer = direction === "toLinkml" ? (activeStep() as Importer) : null;
  $inputSub.textContent = importer ? importer.inputLabel : "LinkML YAML";
  $outputSub.textContent = importer ? "LinkML" : "";
  // Phrased without an article, so it reads right whatever an importer calls its input format.
  $("urlLabel").textContent = `${importer ? importer.inputLabel : "LinkML schema (YAML)"} to load`;
  // Every importer reads some kind of ontology, while the LinkML side reads a schema.
  const noun = importer ? "ontology" : "schema";
  $loadFrom.textContent = `Load ${noun}…`;
  $("loadTitle").textContent = `Load ${noun}`;
  $("exampleHint").textContent =
    `A small ${noun} for a library, with books, people and loans.`;
  for (const btn of $dirToggle.querySelectorAll<HTMLButtonElement>(".dir-btn")) {
    const on = btn.dataset.dir === direction;
    btn.classList.toggle("dir-btn--active", on);
    btn.setAttribute("aria-pressed", String(on));
  }
}

$dirToggle.addEventListener("click", (e) => {
  const btn = (e.target as HTMLElement).closest<HTMLButtonElement>(".dir-btn");
  if (!btn || btn.dataset.dir === direction) return;
  direction = btn.dataset.dir as Direction;
  selectStep();
});

// ── Target combobox ─────────────────────────────────────────────────────────

/** What the user has typed to narrow the list. Null means they have not typed anything since the
 * list opened, so every option is on offer. */
let comboQuery: string | null = null;
/** The options currently listed, in the order they are shown, with the element for each. */
let comboItems: { step: Step; el: HTMLElement }[] = [];
/** Index into [[comboItems]] of the option the arrow keys are on. */
let comboActive = 0;

function comboOpen(): boolean {
  return !$targetPopup.hidden;
}

/** Show the label of whatever is selected. Called on every render, so a change of direction or a
 * reverse lands on the button too. */
function renderCombo(): void {
  $targetLabel.textContent = activeStep().label;
  if (comboOpen()) renderComboList();
}

function openCombo(): void {
  $targetPopup.hidden = false;
  $targetButton.setAttribute("aria-expanded", "true");
  comboQuery = null;
  $targetSearch.value = "";
  renderComboList();
  $targetSearch.focus();
}

/** `returnFocus` puts focus back on the button, for when the list closes while the search box has
 * focus - otherwise focus would fall back to the page and keyboard users would lose their place. */
function closeCombo(returnFocus = false): void {
  if (!comboOpen()) return;
  $targetPopup.hidden = true;
  $targetButton.setAttribute("aria-expanded", "false");
  $targetSearch.removeAttribute("aria-activedescendant");
  comboQuery = null;
  comboItems = [];
  if (returnFocus) $targetButton.focus();
}

function renderComboList(): void {
  const query = (comboQuery ?? "").trim().toLowerCase();
  const matches = query ? steps(direction).filter((s) => s.label.toLowerCase().includes(query)) : steps(direction);
  const selectedId = activeStep().id;

  $targetList.innerHTML = "";
  comboItems = matches.map((step) => {
    const el = document.createElement("li");
    el.id = `target-opt-${step.id}`;
    el.className = "combo-option" + (step.id === selectedId ? " combo-option--selected" : "");
    el.setAttribute("role", "option");
    el.setAttribute("aria-selected", String(step.id === selectedId));
    el.textContent = step.label;
    // mousedown, not click, with the default stopped: otherwise the search box loses focus first
    // and the list closes out from under it.
    el.addEventListener("mousedown", (e) => {
      e.preventDefault();
      pickStep(step);
    });
    el.addEventListener("mousemove", () => setComboActive(comboItems.findIndex((it) => it.el === el)));
    $targetList.appendChild(el);
    return { step, el };
  });

  if (!comboItems.length) {
    const empty = document.createElement("li");
    empty.className = "combo-empty";
    empty.textContent = "No match";
    $targetList.appendChild(empty);
    $targetSearch.removeAttribute("aria-activedescendant");
    return;
  }
  // Start on what is selected when the list opens unfiltered, and on the first hit once filtering.
  const start = comboQuery === null ? comboItems.findIndex((it) => it.step.id === selectedId) : 0;
  setComboActive(Math.max(0, start));
}

/** Move the keyboard highlight, wrapping at both ends. `index` may be -1 or the length itself: one
 * step off either end wraps round to the other. */
function setComboActive(index: number): void {
  if (!comboItems.length) return;
  comboActive = (index + comboItems.length) % comboItems.length;
  comboItems.forEach((it, i) => it.el.classList.toggle("combo-option--active", i === comboActive));
  const current = comboItems[comboActive]!;
  $targetSearch.setAttribute("aria-activedescendant", current.el.id);
  current.el.scrollIntoView({ block: "nearest" });
}

// The default is stopped so a click on the button while the list is open does not pull focus out
// of the search box, which would close the list before the click could. Enter and Space on the
// button still fire the click.
$targetButton.addEventListener("mousedown", (e) => e.preventDefault());
$targetButton.addEventListener("click", () => {
  if (comboOpen()) closeCombo(true);
  else openCombo();
});
$targetButton.addEventListener("keydown", (e) => {
  if ((e.key === "ArrowDown" || e.key === "ArrowUp") && !comboOpen()) {
    e.preventDefault();
    openCombo();
  }
});

// Close once focus goes anywhere outside the button and the list.
$targetCombo.addEventListener("focusout", (e) => {
  if (!$targetCombo.contains(e.relatedTarget as Node | null)) closeCombo();
});

$targetSearch.addEventListener("input", () => {
  comboQuery = $targetSearch.value;
  renderComboList();
});

$targetSearch.addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    setComboActive(comboActive + (e.key === "ArrowDown" ? 1 : -1));
  } else if (e.key === "Enter") {
    e.preventDefault();
    const pick = comboItems[comboActive];
    if (pick) pickStep(pick.step);
  } else if (e.key === "Escape") {
    // Kept off the page: Escape would otherwise reach an enclosing dialog or reset the field.
    e.preventDefault();
    e.stopPropagation();
    closeCombo(true);
  }
});

// ── Options ─────────────────────────────────────────────────────────────────

/** The widgets on screen, so [[updateOptionVisibility]] can reach them. */
let optionFields: { opt: Option; field: HTMLElement }[] = [];

/** Some options only mean anything alongside another - the tree root is dead weight unless the
 * pruning mode is tree-root. Hidden rather than rebuilt, so showing or hiding one never takes
 * focus away from the widget that is being used. */
function updateOptionVisibility(): void {
  const values = optionValues[activeKey()]!;
  for (const { opt, field } of optionFields) {
    field.hidden = opt.showIf !== undefined && !opt.showIf(values);
  }
}

function renderOptions(): void {
  const step = activeStep();
  const values = optionValues[activeKey()]!;
  $optionsRow.innerHTML = "";
  optionFields = [];

  for (const opt of step.options) {
    const field = document.createElement("div");
    field.className = "opt-field";
    const id = `opt-${activeKey()}-${opt.key}`;

    if (opt.type === "checkbox") {
      field.innerHTML = `<input type="checkbox" id="${id}"><label for="${id}">${opt.label}</label>`;
      const input = field.querySelector("input")!;
      input.checked = !!values[opt.key];
      if (opt.title) field.title = opt.title;
      input.addEventListener("change", () => {
        values[opt.key] = input.checked;
        updateOptionVisibility();
        scheduleGenerate();
      });
    } else if (opt.type === "select") {
      const select = document.createElement("select");
      select.id = id;
      for (const choice of opt.choices ?? []) {
        const o = document.createElement("option");
        o.value = choice;
        o.textContent = choice;
        select.appendChild(o);
      }
      select.value = String(values[opt.key]);
      const label = document.createElement("label");
      label.htmlFor = id;
      label.textContent = opt.label;
      field.append(label, select);
      select.addEventListener("change", () => {
        values[opt.key] = select.value;
        updateOptionVisibility();
        scheduleGenerate();
      });
    } else {
      const input = document.createElement("input");
      input.type = opt.type;
      input.id = id;
      input.value = String(values[opt.key]);
      if (opt.placeholder) input.placeholder = opt.placeholder;
      const label = document.createElement("label");
      label.htmlFor = id;
      label.textContent = opt.label;
      field.append(label, input);
      input.addEventListener("input", () => {
        values[opt.key] = input.value;
        updateOptionVisibility();
        scheduleGenerate();
      });
    }

    $optionsRow.appendChild(field);
    optionFields.push({ opt, field });
  }

  updateOptionVisibility();
}

// ── Output rendering ─────────────────────────────────────────────────────

function showOutputText(text: string, lang: OutputLang): void {
  hideReport();
  hideDiagram();
  hidePage();
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.remove("cm-output--error");
  setOutput(outputView, text, lang);
}

function showOutputError(text: string): void {
  hideReport();
  hideDiagram();
  hidePage();
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.add("cm-output--error");
  setOutput(outputView, text, "text");
}

// ── Diagram view ──────────────────────────────────────────────────────────

/** Mermaid's public surface, as much of it as this file uses. */
interface MermaidApi {
  initialize(config: Record<string, unknown>): void;
  parse(text: string, options?: { suppressErrors?: boolean }): Promise<unknown>;
  render(id: string, text: string): Promise<{ svg: string }>;
}

let mermaidPromise: Promise<MermaidApi> | null = null;

function loadMermaid(): Promise<MermaidApi> {
  mermaidPromise ??= import(MERMAID_URL).then((m: { default: MermaidApi }) => m.default);
  return mermaidPromise;
}

/** Only the newest render is allowed to reach the DOM. Mermaid's layout is synchronous once it
 * starts, so this cannot cancel work - it just stops a slow earlier diagram landing on a newer one. */
let diagramToken = 0;

/** True once a render's result is no longer wanted: a newer one started, or the diagram stopped
 * being the visible view (another target, or the Code tab) while this one was still laying out. */
function stale(token: number): boolean {
  return token !== diagramToken || $diagramView.hidden;
}

/** Rough size of a generated diagram, read off the text rather than parsed.
 *
 * Entity blocks and relationship lines both sit at one indent level; attribute rows are deeper. */
function diagramSize(text: string): { entities: number; relationships: number } {
  let entities = 0;
  let relationships = 0;
  for (const line of text.split("\n")) {
    if (!line.startsWith("  ") || line.startsWith("    ")) continue;
    const trimmed = line.trim();
    if (!trimmed || trimmed === "}" || trimmed.startsWith("%%") || trimmed === "erDiagram") continue;
    if (trimmed.includes(" : ")) relationships++;
    else entities++;
  }
  return { entities, relationships };
}

/** Above this, drawing takes long enough to be worth asking first. */
const DIAGRAM_SOFT_LIMIT = { entities: 40, relationships: 120 };
/** Above this it is not offered at all. */
const DIAGRAM_HARD_LIMIT = { entities: 150, relationships: 500 };

function over(size: { entities: number; relationships: number }, limit: typeof DIAGRAM_SOFT_LIMIT): boolean {
  return size.entities > limit.entities || size.relationships > limit.relationships;
}

function hideDiagram(): void {
  clearTimeout(diagramTimer);
  $diagramView.hidden = true;
  $diagramView.innerHTML = "";
  $diagramView.classList.remove("diagram--interactive", "diagram--panning");
  diagramSource = null;
  $outputEditorHost.hidden = false;
}

// ── Diagram pan & zoom ────────────────────────────────────────────────────

/** The diagram's viewport: a scale plus a translation, applied to a wrapper around the SVG.
 *
 * `adjusted` records whether the user has moved it themselves. Until they do, every re-render
 * re-fits; afterwards their framing is kept, so editing the schema with auto-generate on does not
 * yank the view back on every keystroke. */
const diagramView = { scale: 1, x: 0, y: 0, adjusted: false };

const DIAGRAM_MIN_SCALE = 0.05;
const DIAGRAM_MAX_SCALE = 8;
/** Floor for *fitting* only - zooming out by hand still goes to [[DIAGRAM_MIN_SCALE]].
 *
 * A long chain of classes lays out tall and narrow (50 classes come out ~11 000 px high), and
 * fitting that into the panel would land around 0.05, where the diagram is a few pixels wide and the
 * labels are invisible. Past this point it is more use to open at a readable size, anchored at the
 * top left, and let the reader pan. */
const DIAGRAM_FIT_FLOOR = 0.4;

function diagramCanvas(): HTMLElement | null {
  return $diagramView.querySelector<HTMLElement>(".diagram-canvas");
}

function applyDiagramView(): void {
  const canvas = diagramCanvas();
  if (!canvas) return;
  const { x, y, scale } = diagramView;
  canvas.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
}

/** Pin the SVG to the pixel size of its own viewBox.
 *
 * Mermaid emits `width="100%"` plus a `max-width` style, which is right for a page that lets it
 * shrink to fit. Here the wrapper's transform does the scaling, so the SVG needs a fixed natural
 * size for the fit maths - and for a percentage width to mean anything at all inside a wrapper that
 * has no width of its own. */
function sizeDiagramToViewBox(): void {
  const svg = diagramCanvas()?.querySelector("svg");
  const box = svg?.getAttribute("viewBox")?.split(/[\s,]+/).map(Number);
  if (!svg || !box || box.length !== 4 || !box[2] || !box[3]) return;
  svg.setAttribute("width", String(box[2]));
  svg.setAttribute("height", String(box[3]));
  svg.style.maxWidth = "none";
}

/** Scale the diagram to fit the panel and centre it. Never enlarges past 1:1, and never shrinks past
 * [[DIAGRAM_FIT_FLOOR]] - beyond that it anchors at the top left instead, to be panned. */
function fitDiagram(): void {
  const canvas = diagramCanvas();
  const svg = canvas?.querySelector("svg");
  if (!canvas || !svg) return;
  const width = Number(svg.getAttribute("width")) || svg.getBoundingClientRect().width;
  const height = Number(svg.getAttribute("height")) || svg.getBoundingClientRect().height;
  if (!width || !height) return;
  const pad = 24;
  const scale = Math.max(
    DIAGRAM_FIT_FLOOR,
    Math.min(1, ($diagramView.clientWidth - pad) / width, ($diagramView.clientHeight - pad) / height),
  );
  diagramView.scale = scale;
  // `max(0, ...)` centres what fits and pins the rest to the top left, ready to be panned.
  diagramView.x = Math.max(0, ($diagramView.clientWidth - width * scale) / 2);
  diagramView.y = Math.max(0, ($diagramView.clientHeight - height * scale) / 2);
  diagramView.adjusted = false;
  applyDiagramView();
}

/** Zoom about a point in the viewport, so whatever sits under the cursor stays under it. */
function zoomDiagramAt(clientX: number, clientY: number, factor: number): void {
  const rect = $diagramView.getBoundingClientRect();
  const px = clientX - rect.left;
  const py = clientY - rect.top;
  const next = Math.min(DIAGRAM_MAX_SCALE, Math.max(DIAGRAM_MIN_SCALE, diagramView.scale * factor));
  if (next === diagramView.scale) return;
  diagramView.x = px - ((px - diagramView.x) / diagramView.scale) * next;
  diagramView.y = py - ((py - diagramView.y) / diagramView.scale) * next;
  diagramView.scale = next;
  diagramView.adjusted = true;
  applyDiagramView();
}

$diagramView.addEventListener(
  "wheel",
  (e: WheelEvent) => {
    if (!$diagramView.classList.contains("diagram--interactive")) return;
    e.preventDefault();
    // Trackpad pinches arrive as ctrl+wheel with much smaller deltas than a mouse notch.
    const intensity = e.ctrlKey ? 0.01 : 0.0015;
    zoomDiagramAt(e.clientX, e.clientY, Math.exp(-e.deltaY * intensity));
  },
  // Not passive: zooming has to stop the panel from scrolling.
  { passive: false },
);

$diagramView.addEventListener("pointerdown", (e: PointerEvent) => {
  if (!$diagramView.classList.contains("diagram--interactive") || e.button !== 0) return;
  const startX = e.clientX - diagramView.x;
  const startY = e.clientY - diagramView.y;
  $diagramView.setPointerCapture(e.pointerId);
  $diagramView.classList.add("diagram--panning");

  const move = (ev: PointerEvent) => {
    diagramView.x = ev.clientX - startX;
    diagramView.y = ev.clientY - startY;
    diagramView.adjusted = true;
    applyDiagramView();
  };
  const up = () => {
    $diagramView.classList.remove("diagram--panning");
    $diagramView.removeEventListener("pointermove", move);
    $diagramView.removeEventListener("pointerup", up);
    $diagramView.removeEventListener("pointercancel", up);
  };
  $diagramView.addEventListener("pointermove", move);
  $diagramView.addEventListener("pointerup", up);
  $diagramView.addEventListener("pointercancel", up);
});

$diagramView.addEventListener("dblclick", () => {
  if ($diagramView.classList.contains("diagram--interactive")) fitDiagram();
});

/** Render the Mermaid source, behind a Diagram/Code tab pair sharing the file tab bar. */
function showDiagram(text: string): void {
  hideReport();
  hidePage();
  outputView.dom.classList.remove("cm-output--error");
  diagramSource = text;
  // Keep the editor's document in sync even while it is hidden, so switching to Code is instant and
  // the Copy button always hands back the Mermaid source.
  setOutput(outputView, text, "text");

  $fileTabs.hidden = false;
  $fileTabs.innerHTML = "";
  for (const [tab, label] of [["diagram", "Diagram"], ["code", "Code"]] as const) {
    const btn = document.createElement("button");
    btn.className = "file-tab" + (tab === diagramTab ? " file-tab--active" : "");
    btn.textContent = label;
    btn.addEventListener("click", () => {
      if (diagramTab === tab) return;
      diagramTab = tab;
      if (diagramSource !== null) showDiagram(diagramSource);
    });
    $fileTabs.appendChild(btn);
  }

  if (diagramTab === "code") {
    clearTimeout(diagramTimer);
    $diagramView.hidden = true;
    $outputEditorHost.hidden = false;
    return;
  }

  $outputEditorHost.hidden = true;
  $diagramView.hidden = false;
  scheduleDiagramRender(text);
}

/** Debounced so that typing in the schema does not queue up one blocking layout per keystroke. The
 * generation itself is already debounced; this covers the render on top of it. */
function scheduleDiagramRender(text: string, delay = 250): void {
  clearTimeout(diagramTimer);
  const size = diagramSize(text);
  if (over(size, DIAGRAM_SOFT_LIMIT)) {
    showDiagramGate(text, size);
    return;
  }
  diagramTimer = setTimeout(() => void renderDiagram(text), delay);
}

/** Offer (or decline) to draw a diagram that is large enough to lock the page up. */
function showDiagramGate(text: string, size: { entities: number; relationships: number }): void {
  const counts = `${size.entities} entities and ${size.relationships} relationships`;
  const notice = document.createElement("div");
  notice.className = "diagram-notice";

  if (over(size, DIAGRAM_HARD_LIMIT)) {
    notice.innerHTML =
      `<strong>Too large to draw</strong>This diagram has ${counts}. Mermaid lays that out on the ` +
      "page's own thread and would freeze the tab for minutes. Narrow it down with the Pruning " +
      "and Tree root options above, or read it on the Code tab.";
  } else {
    notice.innerHTML =
      `<strong>Large diagram</strong>${counts}. Drawing this may block the page for a few seconds.`;
    const btn = document.createElement("button");
    btn.className = "btn btn-secondary btn-sm";
    btn.textContent = "Render anyway";
    btn.addEventListener("click", () => void renderDiagram(text));
    notice.appendChild(btn);
  }

  showDiagramNotice(notice);
}

/** Put a notice where the diagram would go, and take the pan/zoom behaviour back off. */
function showDiagramNotice(notice: HTMLElement): void {
  $diagramView.classList.remove("diagram--interactive", "diagram--panning");
  $diagramView.removeAttribute("title");
  $diagramView.innerHTML = "";
  $diagramView.appendChild(notice);
}

/** A notice with [[title]] and the error [[message]], for a diagram or a page that failed. */
function errorNotice(title: string, message: string): HTMLElement {
  const notice = document.createElement("div");
  notice.className = "diagram-notice diagram-notice--error";
  const heading = document.createElement("strong");
  heading.textContent = title;
  const detail = document.createElement("code");
  detail.textContent = message;
  notice.append(heading, detail);
  return notice;
}

function showDiagramError(message: string): void {
  showDiagramNotice(errorNotice("Could not draw this diagram", message));
}

async function renderDiagram(text: string): Promise<void> {
  const token = ++diagramToken;
  try {
    const mermaid = await loadMermaid();
    if (token !== diagramToken) return;
    mermaid.initialize({
      startOnLoad: false,
      // Diagram text is derived from the user's own schema, but it still ends up as markup.
      securityLevel: "strict",
      theme: document.documentElement.getAttribute("data-theme") === "light" ? "default" : "dark",
      // Both default caps (50 000 characters, 500 edges) are below what a real-world schema
      // produces. The gate above is what actually keeps the page responsive, so lift these and let
      // it decide.
      maxTextSize: 5_000_000,
      maxEdges: 10_000,
    });
    // Parse first: `render` answers a size or syntax problem with a picture of an error message
    // rather than by failing, which would otherwise reach the page as a successful render.
    await mermaid.parse(text, { suppressErrors: false });
    const { svg } = await mermaid.render(`mermaid-${token}`, text);
    if (stale(token)) return;
    // The SVG goes inside a wrapper that pan/zoom transforms, rather than being transformed itself:
    // Mermaid owns the element's own width/height/style attributes.
    $diagramView.innerHTML = `<div class="diagram-canvas"></div>`;
    diagramCanvas()!.innerHTML = svg;
    sizeDiagramToViewBox();
    $diagramView.classList.add("diagram--interactive");
    $diagramView.title = "Scroll to zoom, drag to pan, double-click to fit";
    // Keep the framing the user chose; re-fit only while they have not touched it.
    if (diagramView.adjusted) applyDiagramView();
    else fitDiagram();
  } catch (err) {
    if (stale(token)) return;
    showDiagramError(err instanceof Error ? err.message : String(err));
  }
}

// ── Documentation page view ─────────────────────────────────────────────────

/** The module built from markdown.ts. */
interface MarkdownApi {
  renderMarkdown(markdown: string): string;
}

let markdownPromise: Promise<MarkdownApi> | null = null;

function loadMarkdown(): Promise<MarkdownApi> {
  markdownPromise ??= import(MARKDOWN_URL) as Promise<MarkdownApi>;
  return markdownPromise;
}

/** Only the newest page render may reach the DOM, as for diagrams. */
let pageToken = 0;

function hidePage(): void {
  $pageView.hidden = true;
  $pageView.innerHTML = "";
  pageSource = null;
  $outputEditorHost.hidden = false;
}

/** Show the Markdown as a page, behind a Page/Code tab pair sharing the file tab bar. */
function showPage(text: string): void {
  hideReport();
  hideDiagram();
  outputView.dom.classList.remove("cm-output--error");
  // Keep the editor in sync while it is hidden, so Code is instant and Copy gives the Markdown.
  setOutput(outputView, text, "text");

  $fileTabs.hidden = false;
  $fileTabs.innerHTML = "";
  for (const [tab, label] of [["page", "Page"], ["code", "Code"]] as const) {
    const btn = document.createElement("button");
    btn.className = "file-tab" + (tab === pageTab ? " file-tab--active" : "");
    btn.textContent = label;
    btn.addEventListener("click", () => {
      if (pageTab === tab) return;
      pageTab = tab;
      if (pageSource !== null) showPage(pageSource);
    });
    $fileTabs.appendChild(btn);
  }

  // A new schema starts at the top. The same schema regenerated, as while typing, keeps its place.
  const sameDocument = pageSource !== null && !$pageView.hidden;
  pageSource = text;

  if (pageTab === "code") {
    $pageView.hidden = true;
    $outputEditorHost.hidden = false;
    return;
  }

  $outputEditorHost.hidden = true;
  $pageView.hidden = false;
  void renderPage(text, sameDocument ? $pageView.scrollTop : 0);
}

async function renderPage(text: string, scrollTop: number): Promise<void> {
  const token = ++pageToken;
  try {
    const { renderMarkdown } = await loadMarkdown();
    if (token !== pageToken || $pageView.hidden) return;
    // Links that don't go to a section of the page already open in a new tab (see markdown.ts).
    $pageView.innerHTML = renderMarkdown(text);
    $pageView.scrollTop = scrollTop;
  } catch (err) {
    if (token !== pageToken || $pageView.hidden) return;
    $pageView.replaceChildren(
      errorNotice("Could not show this page", err instanceof Error ? err.message : String(err)),
    );
  }
}

// Links between sections are anchors. Scroll the panel to them, rather than letting the browser
// change the playground's own address.
$pageView.addEventListener("click", (e) => {
  const link = (e.target as Element).closest("a");
  const href = link?.getAttribute("href");
  if (!href?.startsWith("#")) return;
  e.preventDefault();
  const id = decodeURIComponent(href.slice(1));
  const target = id ? $pageView.querySelector(`[id="${CSS.escape(id)}"]`) : null;
  if (target) target.scrollIntoView({ block: "start" });
  else $pageView.scrollTop = 0;
});

// ── Validation report ───────────────────────────────────────────────────────

/** Most severe first, so the report reads top-down in order of urgency. */
const SEVERITY_ORDER = ["FATAL", "ERROR", "WARNING"];

function severityRank(issue: SchemaIssue): number {
  const i = SEVERITY_ORDER.indexOf(String(issue.severity ?? "").toUpperCase());
  return i === -1 ? SEVERITY_ORDER.length : i;
}

/** e.g. "1 error, 2 warnings" - counts per severity, most severe first, only non-zero. */
function severitySummary(issues: SchemaIssue[]): string {
  const parts: string[] = [];
  for (const sev of SEVERITY_ORDER) {
    const n = issues.filter((i) => String(i.severity ?? "").toUpperCase() === sev).length;
    if (n === 0) continue;
    const noun = sev === "FATAL" ? "fatal error" : sev.toLowerCase();
    parts.push(`${n} ${noun}${n === 1 ? "" : "s"}`);
  }
  const unknown = issues.length - parts.reduce((a, p) => a + Number(p.split(" ")[0]), 0);
  if (unknown > 0) parts.push(`${unknown} other`);
  return parts.join(", ");
}

/** Where an issue was found, as a single compact line. */
function locationLabel(location?: IssueLocation): string {
  if (!location) return "";
  const bits: string[] = [];
  if (location.json_pointer) bits.push(location.json_pointer);
  const region = location.code_region;
  if (region?.start_line) {
    bits.push(`line ${region.start_line}${region.start_column ? `:${region.start_column}` : ""}`);
  }
  if (!bits.length && location.schema_id) bits.push(location.schema_id);
  return bits.join(" · ");
}

function hideReport(): void {
  $reportView.hidden = true;
  $reportView.innerHTML = "";
  reportText = null;
  $outputEditorHost.hidden = false;
}

function showReport(report: SchemaValidationReport): void {
  hideDiagram();
  hidePage();
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.remove("cm-output--error");
  $outputEditorHost.hidden = true;
  $reportView.hidden = false;
  $reportView.innerHTML = "";

  const issues = [...(report.issues ?? [])].sort((a, b) => severityRank(a) - severityRank(b));

  const summary = document.createElement("div");
  summary.className = "report-summary" +
    (issues.length === 0 ? " report-summary--ok" : ` report-summary--${severityClass(issues[0]!)}`);
  summary.textContent = issues.length === 0
    ? "Schema is valid."
    : severitySummary(issues);
  $reportView.appendChild(summary);

  const textLines: string[] = [summary.textContent];

  for (const issue of issues) {
    const item = document.createElement("article");
    item.className = `report-item report-item--${severityClass(issue)}`;

    const head = document.createElement("div");
    head.className = "report-item-head";

    const badge = document.createElement("span");
    badge.className = "report-badge";
    badge.textContent = String(issue.severity ?? "ISSUE").toUpperCase();
    head.appendChild(badge);

    const message = document.createElement("span");
    message.className = "report-message";
    // With messages turned off there is nothing human-readable, so fall back to the raw slots.
    const messageText = issue.message ?? rawSummary(issue);
    // Issues with a `code_region` quote the offending source line with a `^` under the column after
    // the first line, so that part goes into a monospaced block that keeps its whitespace.
    const newline = issue.location?.code_region ? messageText.indexOf("\n") : -1;
    message.textContent = newline < 0 ? messageText : messageText.slice(0, newline);
    head.appendChild(message);
    item.appendChild(head);
    if (newline >= 0) {
      const snippet = document.createElement("pre");
      snippet.className = "report-snippet";
      snippet.textContent = messageText.slice(newline + 1);
      item.appendChild(snippet);
    }

    const issueLines = [`${badge.textContent} ${messageText}`];

    const where = locationLabel(issue.location);
    if (where) {
      const loc = document.createElement("div");
      loc.className = "report-location";
      loc.textContent = where;
      item.appendChild(loc);
      issueLines.push(`  at ${where}`);
    }

    if (issue.details && !restatesMessage(issue.details, issue.message)) {
      const details = document.createElement("p");
      details.className = "report-details";
      details.textContent = issue.details;
      item.appendChild(details);
      issueLines.push(`  ${issue.details}`);
    }

    $reportView.appendChild(item);
    textLines.push(issueLines.join("\n"));
  }

  reportText = textLines.join("\n\n");
}

function severityClass(issue: SchemaIssue): string {
  const sev = String(issue.severity ?? "").toUpperCase();
  return SEVERITY_ORDER.includes(sev) ? sev.toLowerCase() : "other";
}

/** Whether `details` says nothing beyond `message`: either the same text, or the same text after a
 * lead-in that differs only by naming the source (e.g. "Cannot parse schema 'x.yaml': ..." against
 * "Cannot parse schema: ...").
 */
function restatesMessage(details: string, message: string | undefined): boolean {
  if (message === undefined) return false;
  if (details === message) return true;
  const d = details.indexOf(": ");
  const m = message.indexOf(": ");
  return d >= 0 && m >= 0 && details.slice(d + 2) === message.slice(m + 2);
}

/** Describe an issue that carries no `message`, from whatever structured slots it does have.
 *
 * Multivalued slots arrive as arrays, so join them rather than dropping them. Nested objects (only
 * `location`, which is shown separately) are skipped.
 */
function rawSummary(issue: SchemaIssue): string {
  const slots = Object.entries(issue)
    .filter(([k]) => k !== "severity" && k !== "location")
    .flatMap(([k, v]) => {
      if (Array.isArray(v)) return [`${k}: ${v.map(String).join(", ")}`];
      if (v !== null && typeof v === "object") return [];
      return [`${k}: ${String(v)}`];
    });
  return slots.length ? slots.join(", ") : "(no message)";
}

function showFiles(target: Target, dict: Record<string, string>): void {
  hideReport();
  hideDiagram();
  hidePage();
  const names = Object.keys(dict);
  let active = activeFile[target.id] ?? null;
  if (!active || !names.includes(active)) active = names[0] ?? null;
  activeFile[target.id] = active;

  $fileTabs.hidden = names.length <= 1;
  $fileTabs.innerHTML = "";
  for (const name of names) {
    const btn = document.createElement("button");
    btn.className = "file-tab" + (name === active ? " file-tab--active" : "");
    btn.textContent = name;
    btn.addEventListener("click", () => {
      activeFile[target.id] = name;
      showFiles(target, dict);
    });
    $fileTabs.appendChild(btn);
  }

  outputView.dom.classList.remove("cm-output--error");
  // Only a generator produces files, so this is always the generator direction.
  setOutput(outputView, (active && dict[active]) || "", stepLang(target, "fromLinkml"));
}

function setStatus(ok: boolean, text: string, title = ""): void {
  $statusPill.hidden = false;
  $statusPill.textContent = text;
  $statusPill.title = title;
  $statusPill.classList.remove("status-pill--busy");
  $statusPill.classList.toggle("status-pill--error", !ok);
}

/** Show that work is in flight, but only once it has been running long enough to notice.
 *
 * The example schema generates in a few milliseconds; flashing a busy state at it would be a
 * worse artefact than showing nothing. The previous result stays on screen throughout - it is
 * dimmed, never cleared, so switching tabs on a big schema doesn't blank the panel. */
const BUSY_DELAY_MS = 150;

function setBusy(busy: boolean): void {
  clearTimeout(busyTimer);
  if (!busy) {
    $outputPanel.classList.remove("panel--busy");
    return;
  }
  busyTimer = setTimeout(() => {
    $outputPanel.classList.add("panel--busy");
    $statusPill.hidden = false;
    $statusPill.textContent = "working…";
    $statusPill.title = "";
    $statusPill.classList.remove("status-pill--error");
    $statusPill.classList.add("status-pill--busy");
  }, BUSY_DELAY_MS);
}

// ── Generation ───────────────────────────────────────────────────────────

// Parsing and generation run in a worker, so a multi-second SHACL doesn't
// freeze the page. The worker owns the LinkML bundle and the parsed SchemaView. This thread only
// sends the input text plus the chosen target and renders whatever comes back.
let worker: Worker | null = null;

function getWorker(): Worker {
  if (worker) return worker;
  const w = new Worker(new URL(WORKER_URL, import.meta.url), { type: "module" });
  w.onmessage = (e: MessageEvent<WorkerMessage>) => {
    // Everything except the one-off build announcement is an answer to a request.
    if ("build" in e.data) showBuildInfo(e.data.build);
    else onResult(e.data);
  };
  w.onerror = (e) => {
    e.preventDefault();
    // A worker that died (out of memory on a huge schema, say) never answers again, so drop it.
    // The next request builds a fresh one, which also means a fresh parse.
    w.terminate();
    if (worker === w) worker = null;
    pendingId = null;
    setBusy(false);
    showOutputError(`Generation worker failed: ${e.message || "unknown error"}`);
    setStatus(false, "error");
  };
  worker = w;
  return w;
}

function scheduleGenerate(delay = 400): void {
  if (!$autoGenerate.checked && delay > 0) return;
  clearTimeout(generateTimer);
  generateTimer = setTimeout(runGenerate, delay);
}

function runGenerate(): void {
  const input = inputView.state.doc.toString();

  if (!input.trim()) {
    pendingId = null;
    setBusy(false);
    $statusPill.hidden = true;
    setReversibleOutput(null);
    showOutputText("", "text");
    return;
  }

  const step = activeStep();
  const id = ++requestId;
  pendingId = id;
  setBusy(true);
  const request: ConvertRequest = { id, direction, input, stepId: step.id, options: optionValues[activeKey()]! };
  getWorker().postMessage(request);
}

function onResult(res: ConvertResponse): void {
  // Superseded by a newer request - its answer is the one that should land.
  if (res.id !== pendingId) return;
  pendingId = null;
  setBusy(false);

  if (!res.ok) {
    setReversibleOutput(null);
    showOutputError(res.error);
    setStatus(false, "error");
    return;
  }

  // Rendering is the one part still on this thread, so it counts towards what the user waited for.
  const start = performance.now();
  const step = stepById(res.direction, res.stepId) ?? activeStep();
  if (res.kind === "report") {
    showReport(res.result as SchemaValidationReport);
  } else if (res.kind === "files") {
    showFiles(step as Target, res.result as Record<string, string>);
  } else if (res.kind === "diagram") {
    showDiagram(res.result as string);
  } else if (res.kind === "page") {
    showPage(res.result as string);
  } else {
    showOutputText((res.result as string) || "Schema is clean", stepLang(step, res.direction));
  }
  const displayMs = Math.round(performance.now() - start);

  // Only a plain document can be handed back to the other direction - file tabs, reports and
  // diagrams are not one.
  setReversibleOutput(res.kind === "text" ? (res.result as string) || null : null);

  // The pill reports the LinkML work itself: the parse when one happened, plus generation.
  setStatus(!res.fatal, `${(res.loadMs ?? 0) + res.genMs}ms`, breakdown(res, displayMs));
}

/** Where the time went, for the pill's tooltip. Spells out when a parse was reused, which is what
 * makes the headline number jump between a cold load and a tab switch. */
function breakdown(res: Extract<ConvertResponse, { ok: true }>, displayMs: number): string {
  const parts: string[] = [];
  if (res.direction === "toLinkml") {
    // An importer parses its own input inside the call, so there is no separate load step.
    parts.push(`convert ${res.genMs}ms`);
  } else {
    parts.push(res.loadMs === null ? "parse cached" : `parse ${res.loadMs}ms`);
    if (!res.fatal) parts.push(`generate ${res.genMs}ms`);
  }
  parts.push(`display ${displayMs}ms`);
  return parts.join(" · ");
}

// ── Reverse ──────────────────────────────────────────────────────────────

/** The step that would undo what is on screen: for a generator, the importer that reads its output
 * back; for an importer, the generator it undoes. */
function reverseStep(): Step | undefined {
  if (direction === "fromLinkml") return IMPORTERS.find((i) => i.reverses?.includes(activeTargetId));
  const reverses = (activeStep() as Importer).reverses ?? [];
  return targetById(reverses.includes(activeTargetId) ? activeTargetId : (reverses[0] ?? ""));
}

/** The output as it would be handed over, while there is one to hand over. */
let reversibleOutput: string | null = null;

function setReversibleOutput(text: string | null): void {
  reversibleOutput = text;
  updateReverseButton();
}

function updateReverseButton(): void {
  const step = reverseStep();
  $reverseOutput.hidden = step === undefined || reversibleOutput === null;
  if ($reverseOutput.hidden) return;
  const label = `Convert back to ${direction === "toLinkml" ? step!.label : "LinkML"}`;
  $reverseOutput.title = label;
  $reverseOutput.setAttribute("aria-label", label);
}

$reverseOutput.addEventListener("click", () => {
  const step = reverseStep();
  const text = reversibleOutput;
  if (!step || text === null) return;

  direction = direction === "fromLinkml" ? "toLinkml" : "fromLinkml";
  if (direction === "toLinkml") activeImporterId = step.id;
  else activeTargetId = step.id;

  // The one place the two sides meet: what was generated becomes the other side's input, replacing
  // whatever was sitting there.
  const doc = inputDoc();
  doc.text = text;
  doc.link = null;
  doc.remote = null;
  doc.ohi = null;
  selectStep();
});

$generateBtn.addEventListener("click", () => scheduleGenerate(0));

// ── Toolbar actions ──────────────────────────────────────────────────────

// Lets the change listener do the storing: it already writes to the key the active document
// belongs to. So does loading an example, further down.
$clearInput.addEventListener("click", () => {
  setDoc(inputView, "");
  localStorage.removeItem(storageKey(inputKey()));
  inputView.focus();
  scheduleGenerate(0);
});

/** Swap a text-only button's label for a moment, to confirm something happened. Ignored while a
 * previous flash is still up, so a double click cannot leave "Copied!" as the permanent label. */
function flashDone(btn: HTMLButtonElement, label: string): void {
  if (btn.classList.contains("btn--done")) return;
  const original = btn.textContent;
  btn.textContent = label;
  btn.classList.add("btn--done");
  setTimeout(() => {
    btn.textContent = original;
    btn.classList.remove("btn--done");
  }, 1200);
}

/** Close a dialog on its header X, on any extra close button named here, and on a backdrop click -
 * which lands on the dialog element itself, since its content fills it. */
function wireDialog(dialog: HTMLDialogElement, ...closeSelectors: string[]): void {
  for (const selector of [".modal-close", ...closeSelectors]) {
    dialog.querySelector<HTMLElement>(selector)!.addEventListener("click", () => dialog.close());
  }
  dialog.addEventListener("click", (e) => {
    if (e.target === dialog) dialog.close();
  });
}

$copyOutput.addEventListener("click", async () => {
  // While the report is shown the output editor is hidden and still holds the previously
  // generated target's text, so copying it would hand back content from another tab.
  const text = reportText ?? outputView.state.doc.toString();
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    flashDone($copyOutput, "Copied!");
  } catch {
    /* clipboard permission denied – nothing sensible to do */
  }
});

// ── Load dialog ───────────────────────────────────────────────────────────

const $loadDialog = $<HTMLDialogElement>("loadDialog");
const $urlForm = $<HTMLFormElement>("urlForm");
const $urlInput = $<HTMLInputElement>("urlInput");
const $urlError = $("urlError");
const $urlSubmit = $<HTMLButtonElement>("urlSubmit");

/** The `?url=` the page was opened with. Once a document is loaded, its own `link` is what counts -
 * the address bar just follows whichever document is on screen. */
function incomingLink(): string | null {
  return new URL(location.href).searchParams.get(URL_PARAM);
}

/** The `?ohi=` the page was opened with, as given, and the format next to it. Both are checked
 * once the input pane is known. */
function incomingOhi(): { value: string; format: string | null } | null {
  const params = new URL(location.href).searchParams;
  const value = params.get(OHI_PARAM);
  return value === null ? null : { value, format: params.get(FORMAT_PARAM) };
}

/** The importer named next to the link the page was opened with, if this build has it. */
function incomingImporter(): Importer | undefined {
  const id = new URL(location.href).searchParams.get(INPUT_PARAM);
  return id === null ? undefined : importerById(id);
}

/** The generator named next to it, if this build has it. */
function incomingTarget(): Target | undefined {
  const id = new URL(location.href).searchParams.get(TARGET_PARAM);
  return id === null ? undefined : targetById(id);
}

/** The query parameters that point at what the document on screen was loaded from. Without the
 * pane it belongs to, a reload would load an Ossie or OWL file into the LinkML pane. */
function linkParams(doc: InputDoc): [string, string][] {
  const params: [string, string][] = [];
  if (doc.ohi) {
    params.push([OHI_PARAM, `${doc.ohi.pkg}@${doc.ohi.version}`]);
    if (doc.ohi.format !== ohiChoices()[0]!.format) params.push([FORMAT_PARAM, doc.ohi.format]);
  } else {
    params.push([URL_PARAM, doc.link!]);
  }
  if (direction === "toLinkml") params.push([INPUT_PARAM, activeImporterId]);
  else params.push([TARGET_PARAM, activeTargetId]);
  return params;
}

/** Point the address bar at whichever document is on screen, and at the step showing it. */
function syncLinkParams(): void {
  const doc = inputDoc();
  if (doc.link) setLinkParams(doc);
  else clearLinkParams();
}

function setLinkParams(doc: InputDoc): void {
  const here = new URL(location.href);
  for (const p of LINK_PARAMS) here.searchParams.delete(p);
  for (const [k, v] of linkParams(doc)) here.searchParams.set(k, v);
  history.replaceState(null, "", readable(here));
}

/** `searchParams` escapes `/`, `:` and `@`, though a query may hold them as they are. Left
 * escaped, `?ohi=w3c/prov-o@2013` would read `?ohi=w3c%2Fprov-o%402013`. */
function readable(u: URL): string {
  return u.href.replace(/%2F/gi, "/").replace(/%3A/gi, ":").replace(/%40/g, "@");
}

function clearLinkParams(): void {
  const here = new URL(location.href);
  if (!LINK_PARAMS.some((p) => here.searchParams.has(p))) return;
  for (const p of LINK_PARAMS) here.searchParams.delete(p);
  history.replaceState(null, "", here);
}

/** Turns a link to a file's web page into a link to the file itself. Copying what is in the
 * address bar is the obvious thing to do, and on GitHub or GitLab that address serves HTML. */
function rawFileUrl(u: URL): URL {
  const parts = u.pathname.split("/").filter(Boolean);
  if (u.hostname === "github.com" && parts[2] === "blob" && parts.length > 3) {
    // /owner/repo/blob/ref/path -> raw.githubusercontent.com/owner/repo/ref/path
    return new URL(`https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/${parts.slice(3).join("/")}`);
  }
  // Matched on the path rather than the host, so that self-hosted GitLab instances work too.
  if (u.pathname.includes("/-/blob/")) {
    const raw = new URL(u.href);
    raw.pathname = u.pathname.replace("/-/blob/", "/-/raw/");
    raw.search = "";
    return raw;
  }
  return u;
}

type LoadResult = { ok: true } | { ok: false; error: string };
/** What a download returns when something newer got there first: nothing landed, and there is
 * nothing to report either. */
const SUPERSEDED = { ok: false, error: null } as const;
type DownloadResult = LoadResult | typeof SUPERSEDED;

/** Counts downloads, so that one finishing late can tell a newer one has started. */
let downloads = 0;

/** Note what a download is about to replace, before it starts. The editor stays usable while it
 * runs, so by the time it lands the user may have started another, switched to another pane or
 * typed something - and any of those beats a file they may have forgotten they asked for. */
function startDownload(): () => boolean {
  const id = ++downloads;
  const key = inputKey();
  const text = inputDoc().text;
  return () => id === downloads && inputKey() === key && inputDoc().text === text;
}

/** Downloads a schema and puts it in the input pane. The link, as pasted, goes into the address
 * bar rather than the schema itself: it stays short enough to paste anywhere.
 *
 * `current` is for callers with lookups of their own to do first, which count as part of the
 * download. */
async function loadFromUrl(
  link: string,
  ohi: OhiRef | null = null,
  current: () => boolean = startDownload(),
): Promise<DownloadResult> {
  let target: URL;
  try {
    target = rawFileUrl(new URL(link));
  } catch {
    return { ok: false, error: "That is not a URL. It has to start with https://" };
  }
  if (target.protocol !== "https:" && target.protocol !== "http:") {
    return { ok: false, error: "Only http and https links can be loaded." };
  }
  // The deployed playground is https, and a browser refuses to fetch http from an https page
  // (mixed content). That refusal looks exactly like a CORS block, so name the real reason.
  if (target.protocol === "http:" && location.protocol === "https:") {
    return { ok: false, error: "This page is served over HTTPS, so the browser will not load an http:// link. Try https:// instead." };
  }

  let text: string;
  try {
    const res = await fetch(target.href);
    if (!res.ok) return { ok: false, error: `The server answered ${res.status} ${res.statusText}.` };
    text = await res.text();
  } catch {
    // A cross-origin block and being offline both surface as the same detail-free TypeError.
    return {
      ok: false,
      error: "Could not fetch that link. The server may not allow being read from other sites (CORS), or you may be offline.",
    };
  }
  if (/^\s*<(!doctype|html)/i.test(text)) {
    return { ok: false, error: "That link returned a web page, not a schema. Link straight to the raw file." };
  }
  if (!current()) return SUPERSEDED;

  // Set before the change lands, so the listener sees an unedited document either way.
  const doc = inputDoc();
  doc.remote = text;
  doc.link = link;
  doc.ohi = ohi;
  setDoc(inputView, text);
  setLinkParams(doc);
  scheduleGenerate(0);
  return { ok: true };
}

function showUrlError(message: string): void {
  $urlError.textContent = message;
  $urlError.hidden = false;
}

/** Load a link, and on failure hand it back in the dialog to be fixed. Nothing else on screen
 * would explain why the editor stayed empty. */
async function loadOrPrompt(link: string): Promise<void> {
  const res = await loadFromUrl(link);
  if (res.ok || res.error === null) return;
  openLoadDialog();
  $urlInput.value = link;
  showUrlError(res.error);
  $urlInput.focus();
  scheduleGenerate(0);
}

function openLoadDialog(): void {
  $urlInput.value = inputDoc().link ?? "";
  $urlError.hidden = true;
  $ohiError.hidden = true;
  $loadDialog.showModal();
  openOhi();
}

$loadFrom.addEventListener("click", openLoadDialog);

$("exampleSubmit").addEventListener("click", () => {
  setDoc(inputView, exampleInput());
  scheduleGenerate(0);
  $loadDialog.close();
});

$urlForm.addEventListener("submit", async (e) => {
  // Handled here rather than by the browser: the dialog stays open when the fetch fails.
  e.preventDefault();
  const link = $urlInput.value.trim();
  if (!link) return;
  $urlSubmit.disabled = true;
  $urlSubmit.textContent = "Loading…";
  const res = await loadFromUrl(link);
  $urlSubmit.disabled = false;
  $urlSubmit.textContent = "Load";
  if (res.ok) $loadDialog.close();
  else if (res.error !== null) showUrlError(res.error);
});

wireDialog($loadDialog);

// ── ohi.dev search ────────────────────────────────────────────────────────

const $ohiSearch = $<HTMLInputElement>("ohiSearch");
const $ohiFormats = $("ohiFormats");
const $ohiStatus = $("ohiStatus");
const $ohiResults = $("ohiResults");
const $ohiError = $("ohiError");

/** How many hits one search shows. There is no paging: past this, a narrower query is the answer. */
const OHI_PAGE = 25;
const LINKML_OHI: OhiChoice[] = [{ format: "linkml-yaml", label: "LinkML", name: "LinkML" }];

let ohiClientPromise: Promise<CatalogClient> | null = null;

function ohiClient(): Promise<CatalogClient> {
  ohiClientPromise ??= import(OHI_URL).then(
    (m: typeof import("@neverblink/ohi")) => new m.CatalogClient(),
    (e: unknown) => {
      // Let the next search try the download again, rather than fail for good.
      ohiClientPromise = null;
      throw e;
    },
  );
  return ohiClientPromise;
}

/** The format picked on the toggle, per input document. Absent means the first one. */
const ohiPicked: Record<string, OhiFormat> = {};

function ohiChoices(): OhiChoice[] {
  return direction === "fromLinkml" ? LINKML_OHI : (activeStep() as Importer).ohiFormats;
}

function ohiChoice(): OhiChoice {
  const choices = ohiChoices();
  return choices.find((c) => c.format === ohiPicked[inputKey()]) ?? choices[0]!;
}

/** The last search that came back. A hit lists every format it has, so switching format only
 * redraws these instead of searching again. */
let ohiResult: { query: string; hits: SearchHit[]; total: number } | null = null;
let ohiSearchId = 0;
let ohiTimer: ReturnType<typeof setTimeout> | undefined;
/** Set while a picked ontology downloads, so a second click cannot race it into the editor. */
let ohiLoading = false;

function openOhi(): void {
  renderOhiFormats();
  // The first time round it lists what is there before anything is typed.
  if (ohiResult) renderOhiResults();
  else void searchOhi();
  $ohiSearch.focus();
}

function renderOhiFormats(): void {
  const choices = ohiChoices();
  const picked = ohiChoice();
  $ohiFormats.hidden = choices.length < 2;
  $ohiFormats.replaceChildren(
    ...choices.map((c) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "dir-btn";
      btn.textContent = c.label;
      btn.dataset.format = c.format;
      btn.classList.toggle("dir-btn--active", c === picked);
      btn.setAttribute("aria-pressed", String(c === picked));
      return btn;
    }),
  );
}

$ohiFormats.addEventListener("click", (e) => {
  const btn = (e.target as HTMLElement).closest<HTMLButtonElement>(".dir-btn");
  if (!btn) return;
  ohiPicked[inputKey()] = btn.dataset.format as OhiFormat;
  renderOhiFormats();
  renderOhiResults();
});

$ohiSearch.addEventListener("input", () => {
  clearTimeout(ohiTimer);
  ohiTimer = setTimeout(() => void searchOhi(), 250);
});

$ohiSearch.addEventListener("keydown", (e) => {
  if (e.key !== "Enter") return;
  e.preventDefault();
  clearTimeout(ohiTimer);
  void searchOhi();
});

async function searchOhi(): Promise<void> {
  const query = $ohiSearch.value.trim();
  const id = ++ohiSearchId;
  $ohiStatus.textContent = "Searching…";
  $ohiError.hidden = true;
  try {
    const client = await ohiClient();
    // `*` lists everything, which is what an empty box should show.
    const page = await client.search({ q: query || "*", per_page: OHI_PAGE });
    if (id !== ohiSearchId) return;
    ohiResult = { query, hits: page.hits, total: page.total };
    renderOhiResults();
  } catch (e) {
    if (id !== ohiSearchId) return;
    ohiResult = null;
    $ohiResults.replaceChildren();
    $ohiStatus.textContent = "";
    showOhiError(`Could not search ohi.dev. ${ohiErrorText(e)}`);
  }
}

function ohiErrorText(e: unknown): string {
  // The SDK reports a blocked or failed transfer as CONNECTION_ERROR or TIMEOUT, and anything
  // else (a script that would not download, say) is just as likely to mean being offline.
  const code = (e as { error?: { code?: string } }).error?.code;
  if (code === "HTTP_ERROR" || code === "INVALID_RESPONSE") return (e as Error).message;
  return "You may be offline.";
}

function showOhiError(message: string): void {
  $ohiError.textContent = message;
  $ohiError.hidden = false;
}

function renderOhiResults(): void {
  if (!ohiResult) return;
  const { query, hits, total } = ohiResult;
  const what = query ? (total === 1 ? "match" : "matches") : total === 1 ? "ontology" : "ontologies";
  $ohiStatus.textContent =
    total === 0 ? `Nothing on ohi.dev matches “${query}”.`
    : hits.length < total ? `Showing ${hits.length} of ${total} ${what}.${query ? "" : " Type to search."}`
    : `${total} ${what}.`;
  const choice = ohiChoice();
  $ohiResults.replaceChildren(...hits.map((hit) => ohiHitItem(hit, choice)));
}

const EXTERNAL_LINK_ICON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" ' +
  'stroke-linejoin="round" aria-hidden="true"><path d="M15 3h6v6"/><path d="M10 14 21 3"/>' +
  '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>';

function ohiHitItem(hit: SearchHit, choice: OhiChoice): HTMLLIElement {
  const has = hit.formats.includes(choice.format);
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "ohi-hit";
  const line = (cls: string, text: string): void => {
    const el = document.createElement("span");
    el.className = cls;
    el.textContent = text;
    btn.append(el);
  };
  line("ohi-hit-title", hit.title);
  line("ohi-hit-meta", `${hit.namespace}/${hit.name} · ${hit.version}`);
  if (hit.description) line("ohi-hit-desc", hit.description);
  if (has) {
    btn.addEventListener("click", () => void pickOhi(hit, choice, btn));
  } else {
    // aria-disabled rather than disabled: the button stays focusable, so the note saying why it
    // is greyed out can still be reached and read out.
    btn.setAttribute("aria-disabled", "true");
    btn.classList.add("ohi-hit--missing");
    line("ohi-hit-note", `No ${choice.name} file on ohi.dev.`);
  }
  // Beside the button rather than in it: a link inside a button is not valid HTML.
  const view = document.createElement("a");
  view.className = "ohi-view icon-btn";
  view.href = `https://ohi.dev/${encodeURIComponent(hit.namespace)}/${encodeURIComponent(hit.name)}`;
  view.target = "_blank";
  view.rel = "noopener";
  view.title = "View on ohi.dev";
  view.setAttribute("aria-label", `View ${hit.title} on ohi.dev (opens in a new tab)`);
  view.innerHTML = EXTERNAL_LINK_ICON;
  const li = document.createElement("li");
  li.className = "ohi-item";
  li.append(btn, view);
  return li;
}

/** Download one format of an ohi.dev publication into the input pane. */
async function loadOhiFile(
  baseUrl: string,
  ref: OhiRef,
  title: string,
  current: () => boolean = startDownload(),
): Promise<DownloadResult> {
  try {
    const publication = await (await ohiClient()).inspect(baseUrl);
    const files = publication.artifacts.filter((a) => a.format === ref.format);
    // Prefer the file as its publisher wrote it over one ohi.dev generated from it.
    const file = files.find((a) => a.origin === "UPSTREAM") ?? files[0];
    const name = ohiChoices().find((c) => c.format === ref.format)?.name ?? ref.format;
    if (!file) return { ok: false, error: `${title} has no ${name} file on ohi.dev.` };
    return await loadFromUrl(file.url, ref, current);
  } catch (e) {
    return { ok: false, error: `Could not load ${title}. ${ohiErrorText(e)}` };
  }
}

/** Load a package named in an incoming `?ohi=` link. The catalog is asked for its publication
 * rather than its address being made up from the name, which ohi.dev does not promise to keep.
 *
 * The version is required. Without one, "the latest" would have to mean the one ohi.dev
 * published last, which is not always the newest release - older ones can be indexed later. */
async function loadOhiPackage(value: string, format: OhiFormat): Promise<DownloadResult> {
  const current = startDownload();
  // Backticks are left out of the names because they would end the value in the filter below.
  const m = value.match(/^([^/@`\s]+)\/([^/@`\s]+)@([^`\s]+)$/);
  if (!m) {
    return { ok: false, error: `“${value}” does not name an ohi.dev package. It should look like w3c/prov-o@2013.` };
  }
  const [, namespace = "", name = "", version = ""] = m;
  const label = `${namespace}/${name}@${version}`;
  let hit: SearchHit | undefined;
  try {
    const page = await (await ohiClient()).search({
      q: "*",
      filter_by: `namespace:=\`${namespace}\` && name:=\`${name}\` && version:=\`${version}\``,
      per_page: 1,
    });
    hit = page.hits[0];
  } catch (e) {
    return { ok: false, error: `Could not look up ${label} on ohi.dev. ${ohiErrorText(e)}` };
  }
  if (!hit) return { ok: false, error: `There is no ${label} on ohi.dev.` };
  return loadOhiFile(hit.base_url, { pkg: `${namespace}/${name}`, version, format }, hit.title, current);
}

/** Like [[loadOrPrompt]], for a package named in the address. */
async function loadOhiOrPrompt(value: string, format: OhiFormat): Promise<void> {
  const res = await loadOhiPackage(value, format);
  if (res.ok || res.error === null) return;
  openLoadDialog();
  showOhiError(res.error);
  scheduleGenerate(0);
}

/** Download the picked ontology into the input pane. It goes through [[loadFromUrl]] like a pasted
 * link, so the address bar and Share point at the file on ohi.dev afterwards. */
async function pickOhi(hit: SearchHit, choice: OhiChoice, btn: HTMLButtonElement): Promise<void> {
  if (ohiLoading) return;
  ohiLoading = true;
  $ohiResults.classList.add("ohi-results--busy");
  btn.classList.add("ohi-hit--loading");
  $ohiError.hidden = true;
  $ohiStatus.textContent = `Loading ${hit.title}…`;
  try {
    const ref = { pkg: `${hit.namespace}/${hit.name}`, version: hit.version, format: choice.format };
    const res = await loadOhiFile(hit.base_url, ref, hit.title);
    if (res.ok) $loadDialog.close();
    else if (res.error !== null) showOhiError(res.error);
  } finally {
    ohiLoading = false;
    $ohiResults.classList.remove("ohi-results--busy");
    btn.classList.remove("ohi-hit--loading");
    renderOhiResults();
  }
}

// ── Share link ─────────────────────────────────────────────────────────────

// The playground's state, deflated and base64url-encoded, behind this in the fragment. A fragment
// rather than a query parameter: the browser never sends it, so the schema still never leaves the
// device, and it is not subject to the URL length a server or CDN is willing to accept.
const SHARE_PREFIX = "s=";
// Bump only when making BREAKING changes
const SHARE_VERSION = 1;
/** Past this a link still works in a browser, but chat clients and mail wrap or truncate it. */
const SHARE_LONG = 8_000;
/** Past this it approaches what Safari accepts at all (~80 000) and is unusable most other places. */
const SHARE_HUGE = 32_000;
/** Cap on what a link is allowed to decompress to. Nothing anyone would open in a playground comes
 * near it, and without one a hand-made fragment could inflate a thousandfold and wedge the tab. */
const SHARE_MAX_CHARS = 2_000_000;
/** Marks the one failure [[unpack]] raises deliberately, to tell it from a corrupt payload. */
const TOO_LARGE = "share-payload-too-large";
/** Baseline since May 2023, so this is about old browsers rather than exotic ones. */
const CAN_SHARE = typeof CompressionStream !== "undefined" && typeof DecompressionStream !== "undefined";

/** What a share link carries. Short keys mostly for legibility in a hand-decoded payload - deflate
 * makes their length nearly free. */
interface SharedState {
  v: number;
  /** The input text, when the editor holds something no link would serve. */
  s?: string;
  /** The link it came from instead, when the editor still holds exactly what that link serves. */
  u?: string;
  /** Active generator id. Present exactly when the link was made in the "from LinkML" direction. */
  t?: string;
  /** Active importer id. Present exactly when the link was made in the "to LinkML" direction. */
  i?: string;
  /** Only the options that differ from their default. */
  o?: OptionValues;
}

// deflate-raw rather than gzip: the same compression without the header and checksum. It runs at
// about 3.5-4x on schema-sized YAML, so a 100 KB schema comes out as a ~28 000 character link.
async function pack(text: string): Promise<string> {
  const stream = new Blob([new TextEncoder().encode(text)])
    .stream()
    .pipeThrough(new CompressionStream("deflate-raw"));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  // A byte at a time rather than `String.fromCharCode(...bytes)`, which blows the argument limit
  // somewhere around a 100 KB schema.
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function unpack(encoded: string): Promise<string> {
  // `atob` is specified as forgiving-base64, so the stripped padding does not need putting back.
  const binary = atob(encoded.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  // Read it chunk by chunk rather than with `Response.text()`, so [[SHARE_MAX_CHARS]] can stop an
  // oversized payload part way instead of after the whole thing is already in memory.
  const reader = (stream as ReadableStream<Uint8Array>).getReader();
  const decoder = new TextDecoder();
  let text = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    text += decoder.decode(value, { stream: true });
    if (text.length > SHARE_MAX_CHARS) {
      await reader.cancel();
      throw new Error(TOO_LARGE);
    }
  }
  return text + decoder.decode();
}

function changedOptions(): OptionValues | undefined {
  const values = optionValues[activeKey()]!;
  const changed = Object.fromEntries(
    activeStep().options.filter((o) => values[o.key] !== optionDefault(o)).map((o) => [o.key, values[o.key]!]),
  );
  return Object.keys(changed).length ? changed : undefined;
}

/** Filter options that arrived in a link down to ones this build can honour: an unknown key, or a
 * select value that is not on offer, would leave the widget showing nothing while still being what
 * the generator is handed. */
function knownOptions(step: Step, shared: OptionValues | undefined): OptionValues {
  const kept: OptionValues = {};
  for (const opt of step.options) {
    const value = shared?.[opt.key];
    if (value === undefined) continue;
    if (opt.type === "select" && !opt.choices?.includes(String(value))) continue;
    kept[opt.key] = value;
  }
  return kept;
}

function currentState(): SharedState {
  const step = activeStep();
  const text = inputView.state.doc.toString();
  const doc = inputDoc();
  const state: SharedState = { v: SHARE_VERSION };
  // A link is far shorter than the schema, and stays current if the file behind it changes - but
  // only while the editor still holds exactly what it serves.
  if (doc.link !== null && text === doc.remote) state.u = doc.link;
  else state.s = text;
  if (direction === "toLinkml") state.i = step.id;
  else state.t = step.id;
  const options = changedOptions();
  if (options) state.o = options;
  return state;
}

/** A share link, plus whether it is the readable `?url=` kind - which the hint text has to match. */
async function shareUrl(): Promise<{ href: string; plain: boolean }> {
  const state = currentState();
  const here = new URL(location.href);
  here.search = "";
  here.hash = "";
  // With nothing but a link and a step to pass on, the query parameters say the same thing in about
  // the same space and a recipient can see where it points, so there is no reason to bury it in an
  // opaque fragment.
  if (state.u !== undefined && state.o === undefined) {
    for (const [k, v] of linkParams(inputDoc())) here.searchParams.set(k, v);
    return { href: readable(here), plain: true };
  }
  here.hash = SHARE_PREFIX + (await pack(JSON.stringify(state)));
  return { href: here.href, plain: false };
}

function shareFragment(): string | null {
  const hash = location.hash.slice(1);
  return hash.startsWith(SHARE_PREFIX) ? hash.slice(SHARE_PREFIX.length) : null;
}

/** Put the state a share link carries on screen. */
async function applySharedState(encoded: string): Promise<LoadResult> {
  if (!CAN_SHARE) {
    return { ok: false, error: "This browser cannot read share links: it has no Compression Streams support." };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(await unpack(encoded));
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error && err.message === TOO_LARGE
        ? "This share link unpacks to far more text than the playground will open."
        : "This share link is damaged. It may have been cut short on the way here.",
    };
  }
  // Anything at all can come out of a fragment, including `null`, which would throw on the version
  // check rather than be reported.
  if (typeof parsed !== "object" || parsed === null || (parsed as SharedState).v !== SHARE_VERSION) {
    return { ok: false, error: "This link was made by a different version of the playground and cannot be read." };
  }
  const state = parsed as SharedState;

  // An id this build does not know (a link from a newer playground) leaves the default generator
  // selected, and its options with it - they would not mean anything here either.
  const importer = state.i === undefined ? undefined : importerById(state.i);
  if (importer) {
    direction = "toLinkml";
    activeImporterId = importer.id;
  } else {
    direction = "fromLinkml";
    activeTargetId = targetById(state.t ?? "")?.id ?? DEFAULT_TARGET_ID;
  }
  Object.assign(optionValues[activeKey()]!, knownOptions(activeStep(), state.o));
  renderStep();

  // The direction decides which document the link's text belongs to, so it is set first and the
  // editor only pointed at that document once the answer is known.
  shownInput = inputKey();
  if (state.u !== undefined) {
    void loadOrPrompt(state.u);
  } else {
    const doc = inputDoc();
    doc.link = null;
    doc.remote = null;
    doc.ohi = null;
    setDoc(inputView, state.s ?? "");
    scheduleGenerate(0);
  }
  return { ok: true };
}

const $share = $<HTMLButtonElement>("share");
const $shareDialog = $<HTMLDialogElement>("shareDialog");
const $shareTitle = $("shareTitle");
const $shareBody = $("shareBody");
const $shareLink = $<HTMLInputElement>("shareLink");
const $shareSize = $("shareSize");
const $shareHint = $("shareHint");
const $shareError = $("shareError");
const $shareCopy = $<HTMLButtonElement>("shareCopy");

// Nothing here works without Compression Streams, so do not offer it. The read path still explains
// itself, because a link can arrive from someone whose browser could make one.
$share.hidden = !CAN_SHARE;

/** The dialog doubles as the place a broken incoming link is reported, where there is no link to
 * offer and nothing to copy - so everything but the error line comes off, heading included. */
function showShareBody(visible: boolean): void {
  $shareBody.hidden = !visible;
  $shareCopy.hidden = !visible;
  $shareTitle.textContent = visible ? "Share this playground" : "This share link did not work";
}

function showShareError(message: string): void {
  $shareError.textContent = message;
  $shareError.hidden = false;
}

function showShareSize(length: number): void {
  const count = `${length.toLocaleString("en-US")} characters`;
  $shareSize.classList.toggle("share-size--warn", length > SHARE_LONG);
  if (length <= SHARE_LONG) {
    $shareSize.textContent = `${count}.`;
  } else if (length <= SHARE_HUGE) {
    $shareSize.textContent = `${count} – long. Some chat apps and mail clients cut links this long.`;
  } else {
    $shareSize.textContent =
      `${count} – very long. Most places will cut a link this size. Consider putting the schema ` +
      "online, loading it from that URL and sharing again.";
  }
}

$share.addEventListener("click", async () => {
  $shareError.hidden = true;
  showShareBody(true);
  $shareLink.value = "";
  $shareSize.textContent = "";
  $shareSize.classList.remove("share-size--warn");
  $shareDialog.showModal();
  try {
    const { href, plain } = await shareUrl();
    $shareLink.value = href;
    showShareSize(href.length);
    $shareHint.textContent = "Your data is not sent to the server nor stored anywhere.";
    $shareLink.select();
  } catch {
    showShareError("Could not build a share link for this schema.");
  }
});

$shareCopy.addEventListener("click", async () => {
  if (!$shareLink.value) return;
  try {
    await navigator.clipboard.writeText($shareLink.value);
    flashDone($shareCopy, "Copied!");
  } catch {
    // Clipboard permission denied - the link is already selected, so leave it to be copied by hand.
    $shareLink.select();
  }
});

wireDialog($shareDialog, ".share-done");

/** Apply an incoming share link, and on failure say so in the dialog it came from. */
async function applyShareOrPrompt(encoded: string): Promise<void> {
  const res = await applySharedState(encoded);
  if (res.ok) return;
  // Nothing else on screen would explain why the link did not bring anything with it.
  showShareError(res.error);
  showShareBody(false);
  $shareDialog.showModal();
  scheduleGenerate(0);
}

// ── Theme ──────────────────────────────────────────────────────────────────

function applyTheme(theme: string): void {
  const light = theme === "light";
  if (light) document.documentElement.setAttribute("data-theme", "light");
  else document.documentElement.removeAttribute("data-theme");
  // `.hidden` is an HTMLElement property; these icons are SVGElements where it is
  // a no-op, so toggle the attribute directly (see .ic[hidden] in styles.css).
  $themeIconMoon.toggleAttribute("hidden", light);
  $themeIconSun.toggleAttribute("hidden", !light);
}

applyTheme(localStorage.getItem("linkml-ui-theme") === "light" ? "light" : "dark");

$themeToggle.addEventListener("click", () => {
  const next = document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light";
  applyTheme(next);
  localStorage.setItem("linkml-ui-theme", next);
  // A rendered diagram bakes in the theme's colours, so it has to be drawn again to follow along.
  if (diagramSource !== null && diagramTab === "diagram" && !$diagramView.hidden) {
    scheduleDiagramRender(diagramSource, 0);
  }
});

// ── Build info ───────────────────────────────────────────────────────────

const $footerVersion = $<HTMLButtonElement>("footerVersion");

/** Fill in the version rows once the worker reports what it loaded.
 *
 * Every slot is treated as optional: this mirrors an API that hands back plain JSON, and the
 * serializer leaves out slots that are empty. A missing one shows the same dash it started with.
 */
function showBuildInfo(info: BuildInfo): void {
  const set = (id: string, value: string | undefined) => {
    if (value) $(id).textContent = value;
  };
  set("biVersion", info.linkml_scala_version);
  set("biMetamodel", info.metamodel_version);
  set("biScala", info.scala_version);
  set("biScalaJs", info.scala_js_version);
  set("biRuntime", info.runtime);

  if (info.linkml_scala_version) {
    $footerVersion.textContent = `v${info.linkml_scala_version}`;
    $footerVersion.hidden = false;
  }
}

// ── Help / FAQ dialog ────────────────────────────────────────────────────

const $faqDialog = $<HTMLDialogElement>("faqDialog");
const openFaq = () => $faqDialog.showModal();
$<HTMLButtonElement>("helpToggle").addEventListener("click", openFaq);
$<HTMLButtonElement>("aboutLink").addEventListener("click", openFaq);
// The version in the footer is the short form; the dialog has the rest, so send people there.
$footerVersion.addEventListener("click", () => {
  openFaq();
  $("buildSection").scrollIntoView({ block: "nearest" });
});
wireDialog($faqDialog);

// ── GitHub repo stats (mkdocs-material style) ────────────────────────────────

const REPO_SLUG = "NeverBlink-OSS/linkml-scala";
const REPO_STATS_KEY = "linkml-ui-repo-stats";

function formatCount(n: number): string {
  if (n < 1000) return String(n);
  const k = n / 1000;
  return (k >= 10 ? Math.round(k) : Number(k.toFixed(1))) + "k";
}

function renderRepoStats(stars: number, forks: number): void {
  $("repoStars").textContent = formatCount(stars);
  $("repoForks").textContent = formatCount(forks);
  $("repoStats").hidden = false;
}

(function loadRepoStats(): void {
  try {
    const cached = JSON.parse(localStorage.getItem(REPO_STATS_KEY) || "null");
    if (cached) renderRepoStats(cached.stars, cached.forks);
  } catch {
    /* ignore malformed cache */
  }
  fetch(`https://api.github.com/repos/${REPO_SLUG}`)
    .then((r) => (r.ok ? r.json() : null))
    .then((d: { stargazers_count: number; forks_count: number } | null) => {
      if (!d) return;
      renderRepoStats(d.stargazers_count, d.forks_count);
      localStorage.setItem(REPO_STATS_KEY, JSON.stringify({ stars: d.stargazers_count, forks: d.forks_count }));
    })
    .catch(() => {
      /* offline or rate-limited – keep cached value or stay hidden */
    });
})();

// ── Init ─────────────────────────────────────────────────────────────────

// A link may name the generator it was made from; an importer's own link is read further down,
// together with the input pane it belongs to.
const initialTarget = incomingTarget();
if (initialTarget) activeTargetId = initialTarget.id;

// The editor was built from this document, so record it rather than swapping it back in.
shownInput = inputKey();
renderStep();

const initialShare = shareFragment();
const initialLink = incomingLink();
const initialOhi = incomingOhi();
if (initialShare !== null) {
  // Read once, then dropped from the address bar: it keeps the URL legible, and stops an
  // edit-then-reload resurrecting the state the link came with. The schema itself survives a
  // reload either way, through localStorage.
  const clean = new URL(location.href);
  clean.hash = "";
  history.replaceState(null, "", clean);
  getWorker();
  void applyShareOrPrompt(initialShare);
} else if (initialLink === null && initialOhi === null) {
  // Starts the worker, which begins loading the LinkML bundle immediately. The request waits on
  // that load inside the worker, so the page is interactive while the multi-MB bundle parses.
  scheduleGenerate(0);
} else {
  // Same warm-up, minus the request: the download generates once it lands, so asking for a
  // generation here as well would parse the previous session's schema for nothing.
  getWorker();
  // The link may belong to an importer's pane, which has to be on screen before it loads.
  const importer = incomingImporter();
  if (importer) {
    direction = "toLinkml";
    activeImporterId = importer.id;
    renderStep();
    swapInput();
  }
  if (initialOhi) {
    // A format this pane cannot read falls back to the one it reads by default.
    const choice = ohiChoices().find((c) => c.format === initialOhi.format) ?? ohiChoices()[0]!;
    ohiPicked[inputKey()] = choice.format;
    void loadOhiOrPrompt(initialOhi.value, choice.format);
  } else {
    void loadOrPrompt(initialLink!);
  }
}
