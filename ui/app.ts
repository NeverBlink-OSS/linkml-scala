import type { EditorView } from "@codemirror/view";
import { createInput, createOutput, setDoc, setOutput, type OutputLang } from "./editor.js";
// LinkML API types generated from the Scala facade (`./mill uiTypes` → linkml.d.ts).
// Type-checking the UI against these catches drift when LinkMlJsApi.scala changes.
import type { LinkMLApi } from "./linkml";

type ImportMap = Record<string, string>;

const INPUT_STORAGE_KEY = "linkml-ui-input";
const LINKML_BUNDLE_URL = "/out/generator/js/fullLinkJS.dest/main.js";

// The Scala.js bundle expects a Node-ish `process` global.
(globalThis as { process?: unknown }).process ??= { cwd: () => "/" };

let LinkML: LinkMLApi | undefined;
import(LINKML_BUNDLE_URL).then((m: { LinkML: LinkMLApi }) => {
  LinkML = m.LinkML;
  scheduleGenerate(0);
});

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

// ── Target definitions ──────────────────────────────────────────────────────

interface Option {
  key: string;
  type: "checkbox" | "text" | "number" | "select";
  label: string;
  title?: string;
  placeholder?: string;
  choices?: string[];
  default?: string | number | boolean;
}

interface Target {
  id: string;
  label: string;
  options: Option[];
  lang: OutputLang | ((o: OptionValues) => OutputLang);
  call: (schema: string, im: ImportMap, o: OptionValues) => string | Record<string, string>;
}

type OptionValues = Record<string, string | number | boolean>;

const api = (): LinkMLApi => LinkML!;

const TARGETS: Target[] = [
  {
    id: "jsonSchema",
    label: "JSON Schema",
    lang: "json",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "Allow additionalProperties" },
      { key: "treeRootOverride", type: "text", label: "Tree root", placeholder: "Class name (optional)" },
    ],
    call: (s, im, o) => api().jsonSchema(s, im, !!o.open, blankToUndef(o.treeRootOverride)),
  },
  {
    id: "shacl",
    label: "SHACL",
    lang: "turtle",
    options: [
      { key: "open", type: "checkbox", label: "Open", title: "sh:closed false" },
      { key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" },
    ],
    call: (s, im, o) => api().shacl(s, im, !!o.open, !!o.onlyClassesFromRootSchema),
  },
  {
    id: "rdfs",
    label: "RDFS",
    lang: "turtle",
    options: [{ key: "onlyClassesFromRootSchema", type: "checkbox", label: "Root schema only" }],
    call: (s, im, o) => api().rdfs(s, im, !!o.onlyClassesFromRootSchema),
  },
  {
    id: "tableSchema",
    label: "Table Schema",
    lang: "json",
    options: [{ key: "treeRoot", type: "text", label: "Tree root", placeholder: "Class name (optional)" }],
    call: (s, im, o) => api().tableSchema(s, im, blankToUndef(o.treeRoot)),
  },
  {
    id: "scala",
    label: "Scala code",
    lang: "scala",
    options: [{ key: "package", type: "text", label: "Package", default: "eu.neverblink.linkml.metamodel" }],
    call: (s, im, o) => api().scala(s, im, String(o.package || "eu.neverblink.linkml.metamodel")),
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
    call: (s, im, o) =>
      api().linkml(s, im, String(o.pruningMode || "treeRoot"), !!o.skipDerivation, blankToUndef(o.treeRoot), String(o.outFormat || "yaml")),
  },
  {
    id: "lint",
    label: "Lint",
    lang: "text",
    options: [
      { key: "maxProblems", type: "number", label: "Max problems", default: 5 },
      { key: "verbose", type: "checkbox", label: "Verbose" },
    ],
    call: (s, im, o) => api().lint(s, im, Number(o.maxProblems) || 5, !!o.verbose),
  },
];

function blankToUndef(v: string | number | boolean | undefined): string | undefined {
  const t = String(v ?? "").trim();
  return t === "" ? undefined : t;
}

// ── State ─────────────────────────────────────────────────────────────────

let activeTargetId = TARGETS[0]!.id;
const optionValues: Record<string, OptionValues> = Object.fromEntries(
  TARGETS.map((t) => [t.id, Object.fromEntries(t.options.map((o) => [o.key, o.default ?? (o.type === "checkbox" ? false : "")]))]),
);
let scalaFiles: Record<string, string> | null = null;
let activeScalaFile: string | null = null;
let generateTimer: ReturnType<typeof setTimeout> | undefined;

// ── DOM refs ────────────────────────────────────────────────────────────────

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const $fileTabs = $("fileTabs");
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
  scalaFiles = null;
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.remove("cm-output--error");
  setOutput(outputView, text, lang);
}

function showOutputError(text: string): void {
  scalaFiles = null;
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = "";
  outputView.dom.classList.add("cm-output--error");
  setOutput(outputView, text, "text");
}

function showScalaFiles(dict: Record<string, string>): void {
  scalaFiles = dict;
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

function setStatus(ok: boolean, text: string): void {
  $statusPill.hidden = false;
  $statusPill.textContent = text;
  $statusPill.classList.toggle("status-pill--error", !ok);
}

// ── Generation ───────────────────────────────────────────────────────────

function scheduleGenerate(delay = 400): void {
  if (!$autoGenerate.checked && delay > 0) return;
  clearTimeout(generateTimer);
  generateTimer = setTimeout(runGenerate, delay);
}

function runGenerate(): void {
  if (!LinkML) return;
  const target = activeTarget();
  const schema = inputView.state.doc.toString();

  if (!schema.trim()) {
    $statusPill.hidden = true;
    showOutputText("", "text");
    return;
  }

  const start = performance.now();
  try {
    const result = target.call(schema, {}, optionValues[target.id]!);
    const elapsed = Math.round(performance.now() - start);
    if (typeof result === "object") {
      showScalaFiles(result);
    } else {
      showOutputText(result || "Schema is clean", targetLang(target));
    }
    setStatus(true, `${elapsed}ms`);
  } catch (e) {
    showOutputError(e instanceof Error ? e.toString() : String(e));
    setStatus(false, "error");
  }
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
  const text = outputView.state.doc.toString();
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
    /* clipboard permission denied — nothing sensible to do */
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
      /* offline or rate-limited — keep cached value or stay hidden */
    });
})();

// ── Init ─────────────────────────────────────────────────────────────────

renderTargetTabs();
renderOptions();
