import { createInput, createOutput, setDoc, setOutput, type OutputLang } from "./editor.js";
import {
  TARGETS,
  targetById,
  type IssueLocation,
  type OptionValues,
  type ReportIssue,
  type Target,
  type ValidationReport,
} from "./targets.js";
import type { GenerateRequest, GenerateResponse } from "./worker.js";

const INPUT_STORAGE_KEY = "linkml-ui-input";
// Resolved against dist/app.js at runtime, so it lands on the sibling dist/worker.js. Built from a
// variable rather than a literal to keep esbuild from trying to resolve it at bundle time.
const WORKER_URL = "./worker.js";

const EXAMPLE_SCHEMA = `id: https://example.org/library
name: library
description: A tiny example schema, showing classes, slots, enums and a tree root.
prefixes:
  linkml: https://w3id.org/linkml/
default_range: string
imports:
  - linkml:types

enums:
  LoanStatus:
    permissible_values:
      AVAILABLE:
      ON_LOAN:
      LOST:

classes:
  Book:
    description: A book that can be borrowed from the library.
    attributes:
      title:
        required: true
      isbn:
        description: International Standard Book Number
      published_year:
        range: integer
      status:
        range: LoanStatus
      author:
        range: Person
        inlined: true

  Person:
    description: An author or a library member.
    attributes:
      name:
        required: true
      email:

  Library:
    tree_root: true
    attributes:
      name:
        required: true
      books:
        range: Book
        multivalued: true
        inlined_as_list: true
`;

// ── State ─────────────────────────────────────────────────────────────────

let activeTargetId = TARGETS[0]!.id;
const optionValues: Record<string, OptionValues> = Object.fromEntries(
  TARGETS.map((t) => [t.id, Object.fromEntries(t.options.map((o) => [o.key, o.default ?? (o.type === "checkbox" ? false : "")]))]),
);
let activeScalaFile: string | null = null;
// The validation report is rendered as DOM rather than into the output editor, so keep a
// plain-text rendition of it around for the Copy button. Non-null exactly while the report
// is the visible view.
let reportText: string | null = null;
let generateTimer: ReturnType<typeof setTimeout> | undefined;
let busyTimer: ReturnType<typeof setTimeout> | undefined;
// Every request is numbered and only the newest one is rendered. Generation is async now, so a
// burst of tab clicks would otherwise let a slow earlier result land on top of a fast later one.
let requestId = 0;
let pendingId: number | null = null;

// ── DOM refs ────────────────────────────────────────────────────────────────

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const $fileTabs = $("fileTabs");
const $outputPanel = $("outputPanel");
const $reportView = $("reportView");
const $outputEditorHost = $("outputEditor");
const $targetTabs = $("targetTabs");
const $optionsRow = $("optionsRow");
const $generateBtn = $<HTMLButtonElement>("generateBtn");
const $statusPill = $("statusPill");
const $autoGenerate = $<HTMLInputElement>("autoGenerate");
const $copyOutput = $<HTMLButtonElement>("copyOutput");
const $loadExample = $<HTMLButtonElement>("loadExample");
const $clearInput = $<HTMLButtonElement>("clearInput");
const $themeToggle = $<HTMLButtonElement>("themeToggle");
const $themeIconMoon = $("themeIconMoon");
const $themeIconSun = $("themeIconSun");

// ── Editors ───────────────────────────────────────────────────────────────

const storedInput = localStorage.getItem(INPUT_STORAGE_KEY);
const inputView = createInput($("inputEditor"), storedInput ?? EXAMPLE_SCHEMA, (value) => {
  localStorage.setItem(INPUT_STORAGE_KEY, value);
  scheduleGenerate();
});
const outputView = createOutput($("outputEditor"));

function activeTarget(): Target {
  return TARGETS.find((t) => t.id === activeTargetId)!;
}

function targetLang(t: Target): OutputLang {
  return typeof t.lang === "function" ? t.lang(optionValues[t.id]!) : t.lang;
}

// ── Target tabs ─────────────────────────────────────────────────────────────

function renderTargetTabs(): void {
  $targetTabs.innerHTML = "";
  for (const t of TARGETS) {
    const btn = document.createElement("button");
    btn.className = "tab-btn" + (t.id === activeTargetId ? " tab-btn--active" : "");
    btn.textContent = t.label;
    btn.setAttribute("role", "tab");
    btn.setAttribute("aria-selected", String(t.id === activeTargetId));
    btn.addEventListener("click", () => {
      activeTargetId = t.id;
      renderTargetTabs();
      renderOptions();
      scheduleGenerate(0);
    });
    $targetTabs.appendChild(btn);
  }
}

// ── Options ─────────────────────────────────────────────────────────────────

function renderOptions(): void {
  const target = activeTarget();
  const values = optionValues[target.id]!;
  $optionsRow.innerHTML = "";

  for (const opt of target.options) {
    const field = document.createElement("div");
    field.className = "opt-field";
    const id = `opt-${target.id}-${opt.key}`;

    if (opt.type === "checkbox") {
      field.innerHTML = `<input type="checkbox" id="${id}"><label for="${id}">${opt.label}</label>`;
      const input = field.querySelector("input")!;
      input.checked = !!values[opt.key];
      if (opt.title) field.title = opt.title;
      input.addEventListener("change", () => {
        values[opt.key] = input.checked;
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
        scheduleGenerate();
      });
    }

    $optionsRow.appendChild(field);
  }
}

// ── Output rendering ─────────────────────────────────────────────────────

function showOutputText(text: string, lang: OutputLang): void {
  hideReport();
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.remove("cm-output--error");
  setOutput(outputView, text, lang);
}

function showOutputError(text: string): void {
  hideReport();
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.add("cm-output--error");
  setOutput(outputView, text, "text");
}

// ── Validation report ───────────────────────────────────────────────────────

/** Most severe first, so the report reads top-down in order of urgency. */
const SEVERITY_ORDER = ["FATAL", "ERROR", "WARNING"];

function severityRank(issue: ReportIssue): number {
  const i = SEVERITY_ORDER.indexOf(String(issue.severity ?? "").toUpperCase());
  return i === -1 ? SEVERITY_ORDER.length : i;
}

/** e.g. "1 error, 2 warnings" - counts per severity, most severe first, only non-zero. */
function severitySummary(issues: ReportIssue[]): string {
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

function showReport(report: ValidationReport): void {
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
    message.textContent = issue.message ?? rawSummary(issue);
    head.appendChild(message);
    item.appendChild(head);

    const issueLines = [`${badge.textContent} ${message.textContent}`];

    const where = locationLabel(issue.location);
    if (where) {
      const loc = document.createElement("div");
      loc.className = "report-location";
      loc.textContent = where;
      item.appendChild(loc);
      issueLines.push(`  at ${where}`);
    }

    if (issue.details && issue.details !== issue.message) {
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

function severityClass(issue: ReportIssue): string {
  const sev = String(issue.severity ?? "").toUpperCase();
  return SEVERITY_ORDER.includes(sev) ? sev.toLowerCase() : "other";
}

/** Describe an issue that carries no `message`, from whatever structured slots it does have.
 *
 * Multivalued slots arrive as arrays, so join them rather than dropping them. Nested objects (only
 * `location`, which is shown separately) are skipped.
 */
function rawSummary(issue: ReportIssue): string {
  const slots = Object.entries(issue)
    .filter(([k]) => k !== "severity" && k !== "location")
    .flatMap(([k, v]) => {
      if (Array.isArray(v)) return [`${k}: ${v.map(String).join(", ")}`];
      if (v !== null && typeof v === "object") return [];
      return [`${k}: ${String(v)}`];
    });
  return slots.length ? slots.join(", ") : "(no message)";
}

function showScalaFiles(dict: Record<string, string>): void {
  hideReport();
  const names = Object.keys(dict);
  if (!activeScalaFile || !names.includes(activeScalaFile)) activeScalaFile = names[0] ?? null;

  $fileTabs.hidden = names.length <= 1;
  $fileTabs.innerHTML = "";
  for (const name of names) {
    const btn = document.createElement("button");
    btn.className = "file-tab" + (name === activeScalaFile ? " file-tab--active" : "");
    btn.textContent = name;
    btn.addEventListener("click", () => {
      activeScalaFile = name;
      showScalaFiles(dict);
    });
    $fileTabs.appendChild(btn);
  }

  outputView.dom.classList.remove("cm-output--error");
  setOutput(outputView, (activeScalaFile && dict[activeScalaFile]) || "", "scala");
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
  w.onmessage = (e: MessageEvent<GenerateResponse>) => onResult(e.data);
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
  const schema = inputView.state.doc.toString();

  if (!schema.trim()) {
    pendingId = null;
    setBusy(false);
    $statusPill.hidden = true;
    showOutputText("", "text");
    return;
  }

  const target = activeTarget();
  const id = ++requestId;
  pendingId = id;
  setBusy(true);
  const request: GenerateRequest = { id, schema, targetId: target.id, options: optionValues[target.id]! };
  getWorker().postMessage(request);
}

function onResult(res: GenerateResponse): void {
  // Superseded by a newer request - its answer is the one that should land.
  if (res.id !== pendingId) return;
  pendingId = null;
  setBusy(false);

  if (!res.ok) {
    showOutputError(res.error);
    setStatus(false, "error");
    return;
  }

  // Rendering is the one part still on this thread, so it counts towards what the user waited for.
  const start = performance.now();
  if (res.kind === "report") {
    showReport(res.result as ValidationReport);
  } else if (res.kind === "files") {
    showScalaFiles(res.result as Record<string, string>);
  } else {
    const target = targetById(res.targetId) ?? activeTarget();
    showOutputText((res.result as string) || "Schema is clean", targetLang(target));
  }
  const displayMs = Math.round(performance.now() - start);

  // The pill reports the LinkML work itself: the parse when one happened, plus generation.
  setStatus(!res.fatal, `${(res.loadMs ?? 0) + res.genMs}ms`, breakdown(res, displayMs));
}

/** Where the time went, for the pill's tooltip. Spells out when a parse was reused, which is what
 * makes the headline number jump between a cold load and a tab switch. */
function breakdown(res: Extract<GenerateResponse, { ok: true }>, displayMs: number): string {
  const parts = [res.loadMs === null ? "parse cached" : `parse ${res.loadMs}ms`];
  if (!res.fatal) parts.push(`generate ${res.genMs}ms`);
  parts.push(`display ${displayMs}ms`);
  return parts.join(" · ");
}

$generateBtn.addEventListener("click", () => scheduleGenerate(0));

// ── Toolbar actions ──────────────────────────────────────────────────────

$loadExample.addEventListener("click", () => {
  setDoc(inputView, EXAMPLE_SCHEMA);
  localStorage.setItem(INPUT_STORAGE_KEY, EXAMPLE_SCHEMA);
  scheduleGenerate(0);
});

$clearInput.addEventListener("click", () => {
  setDoc(inputView, "");
  localStorage.removeItem(INPUT_STORAGE_KEY);
  inputView.focus();
  scheduleGenerate(0);
});

$copyOutput.addEventListener("click", async () => {
  // While the report is shown the output editor is hidden and still holds the previously
  // generated target's text, so copying it would hand back content from another tab.
  const text = reportText ?? outputView.state.doc.toString();
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    const original = $copyOutput.textContent;
    $copyOutput.textContent = "Copied!";
    $copyOutput.classList.add("btn-secondary--done");
    setTimeout(() => {
      $copyOutput.textContent = original;
      $copyOutput.classList.remove("btn-secondary--done");
    }, 1200);
  } catch {
    /* clipboard permission denied – nothing sensible to do */
  }
});

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
});

// ── Help / FAQ dialog ────────────────────────────────────────────────────

const $faqDialog = $<HTMLDialogElement>("faqDialog");
const openFaq = () => $faqDialog.showModal();
$<HTMLButtonElement>("helpToggle").addEventListener("click", openFaq);
$<HTMLButtonElement>("aboutLink").addEventListener("click", openFaq);
$faqDialog.querySelector<HTMLButtonElement>(".faq-close")!.addEventListener("click", () => $faqDialog.close());
// Click on the backdrop (the dialog element itself, since content fills it) closes it.
$faqDialog.addEventListener("click", (e) => {
  if (e.target === $faqDialog) $faqDialog.close();
});

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

renderTargetTabs();
renderOptions();
// Starts the worker, which begins loading the LinkML bundle immediately. The request waits on
// that load inside the worker, so the page is interactive while the multi-MB bundle parses.
scheduleGenerate(0);
