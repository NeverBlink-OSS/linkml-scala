const INPUT_STORAGE_KEY = 'linkml-ui-input';

let LinkML;
import('../out/generator/js/fullLinkJS.dest/main.js').then((m) => {
  LinkML = m.LinkML;
  scheduleGenerate();
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

// ── Target definitions ────────────────────────────────────────────────────

const TARGETS = [
  {
    id: 'jsonSchema',
    label: 'JSON Schema',
    fn: 'jsonSchema',
    options: [
      { key: 'open', type: 'checkbox', label: 'Open', title: 'Allow additionalProperties' },
      { key: 'treeRootOverride', type: 'text', label: 'Tree root', placeholder: 'Class name (optional)' },
    ],
    call: (schema, im, o) => LinkML.jsonSchema(schema, im, !!o.open, blankToUndef(o.treeRootOverride)),
  },
  {
    id: 'shacl',
    label: 'SHACL',
    fn: 'shacl',
    options: [
      { key: 'open', type: 'checkbox', label: 'Open', title: 'sh:closed false' },
      { key: 'onlyClassesFromRootSchema', type: 'checkbox', label: 'Root schema only' },
    ],
    call: (schema, im, o) => LinkML.shacl(schema, im, !!o.open, !!o.onlyClassesFromRootSchema),
  },
  {
    id: 'rdfs',
    label: 'RDFS',
    fn: 'rdfs',
    options: [
      { key: 'onlyClassesFromRootSchema', type: 'checkbox', label: 'Root schema only' },
    ],
    call: (schema, im, o) => LinkML.rdfs(schema, im, !!o.onlyClassesFromRootSchema),
  },
  {
    id: 'tableSchema',
    label: 'Table Schema',
    fn: 'tableSchema',
    options: [
      { key: 'treeRoot', type: 'text', label: 'Tree root', placeholder: 'Class name (optional)' },
    ],
    call: (schema, im, o) => LinkML.tableSchema(schema, im, blankToUndef(o.treeRoot)),
  },
  {
    id: 'scala',
    label: 'Scala code',
    fn: 'scala',
    options: [
      { key: 'package', type: 'text', label: 'Package', default: 'eu.neverblink.linkml.metamodel' },
    ],
    call: (schema, im, o) => LinkML.scala(schema, im, o.package || 'eu.neverblink.linkml.metamodel'),
  },
  {
    id: 'linkml',
    label: 'Derived LinkML',
    fn: 'linkml',
    options: [
      { key: 'pruningMode', type: 'select', label: 'Pruning', choices: ['treeRoot', 'schema', 'skip'], default: 'treeRoot' },
      { key: 'skipDerivation', type: 'checkbox', label: 'Skip derivation' },
      { key: 'treeRoot', type: 'text', label: 'Tree root', placeholder: 'Class name (optional)' },
      { key: 'outFormat', type: 'select', label: 'Format', choices: ['yaml', 'json'], default: 'yaml' },
    ],
    call: (schema, im, o) =>
      LinkML.linkml(schema, im, o.pruningMode || 'treeRoot', !!o.skipDerivation, blankToUndef(o.treeRoot), o.outFormat || 'yaml'),
  },
  {
    id: 'lint',
    label: 'Lint',
    fn: 'lint',
    options: [
      { key: 'maxProblems', type: 'number', label: 'Max problems', default: 5 },
      { key: 'verbose', type: 'checkbox', label: 'Verbose' },
    ],
    call: (schema, im, o) => LinkML.lint(schema, im, Number(o.maxProblems) || 5, !!o.verbose),
  },
];

function blankToUndef(v) {
  const t = (v || '').trim();
  return t === '' ? undefined : t;
}

// ── Simple YAML highlighting (regex-based, not a real parser) ──────────────

const SCALAR_TOKEN_RE =
  /(#.*)$|("(?:[^"\\]|\\.)*")|('(?:[^'\\]|\\.)*')|(&[\w-]+|\*[\w-]+)|(!!?[\w./-]+)|\b(true|false|null|True|False|Null|TRUE|FALSE|NULL|~)\b|\b(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b/g;

const LEADING_DASHES_RE = /^(\s*)((?:-\s+)*)/;
const KEY_RE = /^([^\s:#][^:#]*?)(:)(\s|$)/;

function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function tokenizeScalar(text) {
  let out = '';
  let last = 0;
  SCALAR_TOKEN_RE.lastIndex = 0;
  let m;
  while ((m = SCALAR_TOKEN_RE.exec(text))) {
    if (m.index > last) out += escapeHtml(text.slice(last, m.index));
    const cls = m[1] ? 'tok-comment' : m[2] || m[3] ? 'tok-string' : m[4] || m[5] ? 'tok-tag' : m[6] ? 'tok-const' : 'tok-number';
    out += `<span class="${cls}">${escapeHtml(m[0])}</span>`;
    last = SCALAR_TOKEN_RE.lastIndex;
  }
  out += escapeHtml(text.slice(last));
  return out;
}

function highlightLine(line) {
  let out = '';
  let i = 0;

  const lead = LEADING_DASHES_RE.exec(line);
  out += escapeHtml(lead[1]);
  if (lead[2]) out += `<span class="tok-punct">${escapeHtml(lead[2])}</span>`;
  i = lead[0].length;

  const rest = line.slice(i);
  const key = KEY_RE.exec(rest);
  if (key) {
    out += `<span class="tok-key">${escapeHtml(key[1])}</span>${escapeHtml(key[2])}`;
    out += tokenizeScalar(rest.slice(key[0].length - key[3].length));
  } else {
    out += tokenizeScalar(rest);
  }

  return out;
}

function highlightYaml(text) {
  // Each line gets its own block with an explicit height, rather than one
  // <pre> text flow with embedded newlines. A <textarea> and a plain
  // multi-line <pre> can render the *same* declared line-height a pixel or
  // two apart per line (a real, measured browser quirk in the line-box
  // metrics of the two element types) — that's invisible on one line but
  // drifts the two layers apart over a whole document. Giving every line an
  // explicit CSS height sidesteps that: the browser can no longer compute it
  // from font metrics, so it can no longer disagree between the two layers.
  return text
    .split('\n')
    .map((line) => `<div class="hl-line">${highlightLine(line)}</div>`)
    .join('');
}

// ── State ──────────────────────────────────────────────────────────────────

let activeTargetId = TARGETS[0].id;
const optionValues = Object.fromEntries(
  TARGETS.map((t) => [t.id, Object.fromEntries(t.options.map((o) => [o.key, o.default ?? (o.type === 'checkbox' ? false : '')]))]),
);
let scalaFiles = null;
let activeScalaFile = null;
let generateTimer = null;

// ── DOM refs ───────────────────────────────────────────────────────────────

const $input = document.getElementById('input');
const $editorPre = document.getElementById('inputHighlight');
const $inputHighlight = $editorPre.querySelector('code');
const $output = document.getElementById('output');
const $fileTabs = document.getElementById('fileTabs');
const $targetTabs = document.getElementById('targetTabs');
const $optionsRow = document.getElementById('optionsRow');
const $generateBtn = document.getElementById('generateBtn');
const $statusPill = document.getElementById('statusPill');
const $autoGenerate = document.getElementById('autoGenerate');
const $copyOutput = document.getElementById('copyOutput');
const $loadExample = document.getElementById('loadExample');
const $clearInput = document.getElementById('clearInput');
const $themeToggle = document.getElementById('themeToggle');
const $themeIconMoon = document.getElementById('themeIconMoon');
const $themeIconSun = document.getElementById('themeIconSun');

// ── Rendering: target tabs ─────────────────────────────────────────────────

function renderTargetTabs() {
  $targetTabs.innerHTML = '';
  for (const t of TARGETS) {
    const btn = document.createElement('button');
    btn.className = 'tab-btn' + (t.id === activeTargetId ? ' tab-btn--active' : '');
    btn.textContent = t.label;
    btn.setAttribute('role', 'tab');
    btn.setAttribute('aria-selected', String(t.id === activeTargetId));
    btn.addEventListener('click', () => {
      activeTargetId = t.id;
      renderTargetTabs();
      renderOptions();
      scheduleGenerate(0);
    });
    $targetTabs.appendChild(btn);
  }
}

// ── Rendering: options for the active target ────────────────────────────────

function renderOptions() {
  const target = TARGETS.find((t) => t.id === activeTargetId);
  const values = optionValues[target.id];
  $optionsRow.innerHTML = '';

  for (const opt of target.options) {
    const field = document.createElement('div');
    field.className = 'opt-field';

    if (opt.type === 'checkbox') {
      const id = `opt-${target.id}-${opt.key}`;
      field.innerHTML = `<input type="checkbox" id="${id}"><label for="${id}">${opt.label}</label>`;
      const input = field.querySelector('input');
      input.checked = !!values[opt.key];
      if (opt.title) field.title = opt.title;
      input.addEventListener('change', () => {
        values[opt.key] = input.checked;
        scheduleGenerate();
      });
    } else if (opt.type === 'select') {
      const id = `opt-${target.id}-${opt.key}`;
      const select = document.createElement('select');
      select.id = id;
      for (const choice of opt.choices) {
        const o = document.createElement('option');
        o.value = choice;
        o.textContent = choice;
        select.appendChild(o);
      }
      select.value = values[opt.key];
      const label = document.createElement('label');
      label.htmlFor = id;
      label.textContent = opt.label;
      field.append(label, select);
      select.addEventListener('change', () => {
        values[opt.key] = select.value;
        scheduleGenerate();
      });
    } else {
      const id = `opt-${target.id}-${opt.key}`;
      const input = document.createElement('input');
      input.type = opt.type;
      input.id = id;
      input.value = values[opt.key];
      if (opt.placeholder) input.placeholder = opt.placeholder;
      const label = document.createElement('label');
      label.htmlFor = id;
      label.textContent = opt.label;
      field.append(label, input);
      input.addEventListener('input', () => {
        values[opt.key] = input.value;
        scheduleGenerate();
      });
    }

    $optionsRow.appendChild(field);
  }
}

// ── Output rendering ─────────────────────────────────────────────────────

function showOutputText(text) {
  scalaFiles = null;
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = '';
  $output.textContent = text;
  $output.classList.remove('code-output--error');
}

function showOutputError(text) {
  scalaFiles = null;
  $fileTabs.hidden = true;
  $fileTabs.innerHTML = '';
  $output.textContent = text;
  $output.classList.add('code-output--error');
}

function showScalaFiles(dict) {
  scalaFiles = dict;
  const names = Object.keys(dict);
  if (!names.includes(activeScalaFile)) activeScalaFile = names[0];

  $fileTabs.hidden = names.length <= 1;
  $fileTabs.innerHTML = '';
  for (const name of names) {
    const btn = document.createElement('button');
    btn.className = 'file-tab' + (name === activeScalaFile ? ' file-tab--active' : '');
    btn.textContent = name;
    btn.addEventListener('click', () => {
      activeScalaFile = name;
      showScalaFiles(dict);
    });
    $fileTabs.appendChild(btn);
  }

  $output.classList.remove('code-output--error');
  $output.textContent = dict[activeScalaFile] ?? '';
}

function renderHighlight() {
  $inputHighlight.innerHTML = highlightYaml($input.value);
  // Auto-grow the (invisible) textarea to its full content height so it never
  // scrolls internally — the .editor wrapper is the single scroll owner, which
  // keeps it and the <pre> beneath it perfectly aligned at every line.
  $input.style.height = 'auto';
  $input.style.height = `${$input.scrollHeight}px`;
}

function setStatus(ok, text) {
  $statusPill.hidden = false;
  $statusPill.textContent = text;
  $statusPill.classList.toggle('status-pill--error', !ok);
}

// ── Generation ─────────────────────────────────────────────────────────────

function scheduleGenerate(delay = 400) {
  if (!$autoGenerate.checked && delay > 0) return;
  clearTimeout(generateTimer);
  generateTimer = setTimeout(runGenerate, delay);
}

function runGenerate() {
  if (!LinkML) return;
  const target = TARGETS.find((t) => t.id === activeTargetId);
  const schema = $input.value;

  if (!schema.trim()) {
    $statusPill.hidden = true;
    showOutputText('');
    $output.innerHTML = '<span class="code-output-empty">Output will appear here.</span>';
    return;
  }

  const start = performance.now();
  try {
    const result = target.call(schema, {}, optionValues[target.id]);
    const elapsed = Math.round(performance.now() - start);
    if (target.id === 'scala') {
      showScalaFiles(result);
    } else {
      showOutputText(result || 'Schema is clean');
    }
    setStatus(true, `${elapsed}ms`);
  } catch (e) {
    showOutputError(e && e.toString ? e.toString() : String(e));
    setStatus(false, 'error');
  }
}

$generateBtn.addEventListener('click', () => scheduleGenerate(0));
$input.addEventListener('input', () => {
  localStorage.setItem(INPUT_STORAGE_KEY, $input.value);
  renderHighlight();
  scheduleGenerate();
});

// ── Toolbar actions ────────────────────────────────────────────────────────

$loadExample.addEventListener('click', () => {
  $input.value = EXAMPLE_SCHEMA;
  localStorage.setItem(INPUT_STORAGE_KEY, $input.value);
  renderHighlight();
  scheduleGenerate(0);
});

$clearInput.addEventListener('click', () => {
  $input.value = '';
  localStorage.removeItem(INPUT_STORAGE_KEY);
  renderHighlight();
  $input.focus();
  scheduleGenerate(0);
});

$copyOutput.addEventListener('click', async () => {
  const text = $output.textContent;
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    const original = $copyOutput.textContent;
    $copyOutput.textContent = 'Copied!';
    $copyOutput.classList.add('btn-secondary--done');
    setTimeout(() => {
      $copyOutput.textContent = original;
      $copyOutput.classList.remove('btn-secondary--done');
    }, 1200);
  } catch {
    /* clipboard permission denied — nothing sensible to do */
  }
});

// ── Theme ──────────────────────────────────────────────────────────────────

function applyTheme(theme) {
  const light = theme === 'light';
  if (light) document.documentElement.setAttribute('data-theme', 'light');
  else document.documentElement.removeAttribute('data-theme');
  // `.hidden` is an HTMLElement property; these icons are SVGElements where it is
  // a no-op, so toggle the attribute directly (see .ic[hidden] in styles.css).
  $themeIconMoon.toggleAttribute('hidden', light);
  $themeIconSun.toggleAttribute('hidden', !light);
}

const storedTheme = localStorage.getItem('linkml-ui-theme');
applyTheme(storedTheme === 'light' ? 'light' : 'dark');

$themeToggle.addEventListener('click', () => {
  const isLight = document.documentElement.getAttribute('data-theme') === 'light';
  const next = isLight ? 'dark' : 'light';
  applyTheme(next);
  localStorage.setItem('linkml-ui-theme', next);
});

// ── GitHub repo stats (mkdocs-material style) ────────────────────────────────

const REPO_SLUG = 'NeverBlink-OSS/linkml-scala';
const REPO_STATS_KEY = 'linkml-ui-repo-stats';

function formatCount(n) {
  if (n < 1000) return String(n);
  const k = n / 1000;
  return (k >= 10 ? Math.round(k) : Number(k.toFixed(1))) + 'k';
}

function renderRepoStats(stars, forks) {
  document.getElementById('repoStars').textContent = formatCount(stars);
  document.getElementById('repoForks').textContent = formatCount(forks);
  document.getElementById('repoStats').hidden = false;
}

(function loadRepoStats() {
  try {
    const cached = JSON.parse(localStorage.getItem(REPO_STATS_KEY) || 'null');
    if (cached) renderRepoStats(cached.stars, cached.forks);
  } catch { /* ignore malformed cache */ }

  fetch(`https://api.github.com/repos/${REPO_SLUG}`)
    .then((r) => (r.ok ? r.json() : null))
    .then((d) => {
      if (!d) return;
      renderRepoStats(d.stargazers_count, d.forks_count);
      localStorage.setItem(
        REPO_STATS_KEY,
        JSON.stringify({ stars: d.stargazers_count, forks: d.forks_count }),
      );
    })
    .catch(() => { /* offline or rate-limited — keep cached value or stay hidden */ });
})();

// ── Init ───────────────────────────────────────────────────────────────────

renderTargetTabs();
renderOptions();
const storedInput = localStorage.getItem(INPUT_STORAGE_KEY);
$input.value = storedInput !== null ? storedInput : EXAMPLE_SCHEMA;
renderHighlight();
