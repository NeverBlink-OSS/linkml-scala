// Copies the guides in ../docs into src/content/docs as Starlight pages. docs/ remains the source of truth,
// and they keep working on GitHub. This is run on every build and on every change to docs/ while
// `astro dev` is serving a website (see astro.config.mjs).
//
// For each docs/*.md file this:
// - changes the name to kebab case (import_maps.md -> /docs/import-maps/),
// - README.md is renamed to index,
// - changes `# Title` into the `title` frontmatter (Starlight prints it itself),
// - sets `editUrl` to the file in docs/, not the copy,
// - rewrites GitHub alerts (`> [!TIP]`) into Starlight asides,
// - rewrites links to other guides to their page URLs, and links that leave docs/ to GitHub.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteDir = path.dirname(fileURLToPath(import.meta.url));
const repoDir = path.resolve(websiteDir, '..');
export const docsDir = path.join(repoDir, 'docs');
const contentDir = path.join(websiteDir, 'src', 'content', 'docs');
const publicDir = path.join(websiteDir, 'public');

const base = '/docs/';
const repoUrl = 'https://github.com/NeverBlink-OSS/linkml-scala';
// Only these folders in docs/ are copied to the site. The rest (examples, screenshot scripts)
// are linked on GitHub.
const assetDirs = ['img'];

/** The page slug for a Markdown file name in docs/, e.g. `import-maps` for `import_maps.md`. */
export function slugFor(fileName) {
  if (fileName === 'README.md') return 'index';
  return fileName.replace(/\.md$/, '').replaceAll('_', '-').toLowerCase();
}

/** The slugs of all pages made from docs/. */
export function docSlugs() {
  return markdownFiles().map(slugFor);
}

function markdownFiles() {
  return fs
    .readdirSync(docsDir, { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith('.md'))
    .map((entry) => entry.name)
    .sort();
}

/** Writes the pages and assets, leaving files that did not change alone. */
export function syncDocs() {
  const wanted = new Map();

  for (const fileName of markdownFiles()) {
    const source = fs.readFileSync(path.join(docsDir, fileName), 'utf8');
    wanted.set(`${slugFor(fileName)}.md`, toPage(fileName, source));
  }
  for (const dir of assetDirs) {
    for (const file of listFiles(path.join(docsDir, dir))) {
      wanted.set(path.join(dir, file), fs.readFileSync(path.join(docsDir, dir, file)));
    }
  }

  fs.mkdirSync(contentDir, { recursive: true });
  for (const file of listFiles(contentDir)) {
    if (!wanted.has(file)) fs.rmSync(path.join(contentDir, file));
  }
  for (const [file, content] of wanted) writeIfChanged(path.join(contentDir, file), content);

  writeIfChanged(
    path.join(publicDir, 'favicon.svg'),
    fs.readFileSync(path.join(repoDir, 'ui', 'favicon.svg')),
  );
  // The playground's fonts, so the docs look the same (see src/styles/theme.css)
  const fontsDir = path.join(repoDir, 'ui', 'fonts');
  for (const file of listFiles(fontsDir)) {
    writeIfChanged(path.join(publicDir, 'fonts', file), fs.readFileSync(path.join(fontsDir, file)));
  }
}

function toPage(fileName, source) {
  const lines = source.split('\n');
  const titleIndex = lines.findIndex((line) => line.startsWith('# '));
  if (titleIndex === -1 || lines.slice(0, titleIndex).some((line) => line.trim() !== '')) {
    throw new Error(`docs/${fileName} must start with a "# Title" line`);
  }
  // Starlight titles are plain text, so `code` in a heading would show its backticks
  const title = lines[titleIndex].slice(2).replaceAll('`', '').trim();

  let body = lines.slice(titleIndex + 1);
  while (body.length > 0 && body[0].trim() === '') body = body.slice(1);

  const frontmatter = [
    '---',
    `title: ${JSON.stringify(title)}`,
    `editUrl: ${JSON.stringify(`${repoUrl}/edit/main/docs/${fileName}`)}`,
    '---',
    '',
  ];
  return frontmatter.concat(rewriteLinks(fileName, convertAlerts(body))).join('\n');
}

/** Calls `transform` on each line outside fenced code blocks, and returns its lines. */
function outsideCode(lines, transform) {
  let fence = null;
  return lines.flatMap((line, i) => {
    const fenceMatch = line.match(/^\s*(`{3,}|~{3,})/);
    if (fenceMatch) {
      if (fence === null) fence = fenceMatch[1];
      else if (fenceMatch[1].startsWith(fence)) fence = null;
      return [line];
    }
    return fence === null ? transform(line, i) : [line];
  });
}

// GitHub alerts, `> [!TIP]` and so on, and the Starlight asides they become
const asides = { NOTE: 'note', TIP: 'tip', IMPORTANT: 'note', WARNING: 'caution', CAUTION: 'danger' };

/** Turns GitHub alerts into Starlight asides (`:::tip ... :::`). */
function convertAlerts(lines) {
  let inAlert = false;
  return outsideCode(lines, (line, i) => {
    const alert = line.match(/^> \[!(\w+)\]\s*$/);
    if (alert && asides[alert[1]]) {
      inAlert = true;
      return [`:::${asides[alert[1]]}`].concat(closeAlert(lines, i));
    }
    if (inAlert) {
      const end = closeAlert(lines, i);
      if (end.length > 0) inAlert = false;
      return [line.replace(/^> ?/, '')].concat(end);
    }
    return [line];
  });
}

/** `:::` if the alert ends after line `i`, so the aside is closed there. */
function closeAlert(lines, i) {
  return lines[i + 1]?.startsWith('>') ? [] : [':::'];
}

/** Rewrites the targets of inline links and images, skipping code blocks and code spans. */
function rewriteLinks(fileName, lines) {
  return outsideCode(lines, (line) => [
    // Odd parts are code spans
    line
      .split(/(`+[^`]*`+)/)
      .map((part, i) =>
        i % 2 === 1
          ? part
          : part.replace(/\]\(([^)\s]+)\)/g, (_, target) => `](${rewriteTarget(fileName, target)})`),
      )
      .join(''),
  ]);
}

function rewriteTarget(fileName, target) {
  // Absolute URLs (https:, mailto:), absolute paths and anchors on the same page
  if (/^[a-z][a-z0-9+.-]*:/i.test(target) || target.startsWith('/') || target.startsWith('#')) {
    return target;
  }

  const [targetPath, hash] = target.split(/(?=#)/);
  const resolved = path.resolve(docsDir, decodeURIComponent(targetPath));
  if (!fs.existsSync(resolved)) {
    throw new Error(`docs/${fileName} links to ${target}, which does not exist`);
  }

  const relative = path.relative(docsDir, resolved);
  const insideDocs = !relative.startsWith('..') && !path.isAbsolute(relative);
  if (insideDocs && !relative.includes(path.sep) && relative.endsWith('.md')) {
    const slug = slugFor(relative);
    return (slug === 'index' ? base : `${base}${slug}/`) + (hash ?? '');
  }
  if (insideDocs && assetDirs.includes(relative.split(path.sep)[0])) return target;

  const repoPath = path.relative(repoDir, resolved).split(path.sep).join('/');
  const kind = fs.statSync(resolved).isDirectory() ? 'tree' : 'blob';
  return `${repoUrl}/${kind}/main/${repoPath}${hash ?? ''}`;
}

/** All files under a directory, as paths relative to it. */
function listFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(dir, path.join(entry.parentPath, entry.name)));
}

function writeIfChanged(file, content) {
  const bytes = Buffer.from(content);
  if (fs.existsSync(file) && fs.readFileSync(file).equals(bytes)) return;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, bytes);
}
