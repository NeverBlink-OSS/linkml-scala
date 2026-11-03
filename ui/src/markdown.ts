// Turns the Markdown of the Docs target into HTML for the playground's page view. It runs in the
// generation worker (see worker.ts), so the page only has to show the HTML.
import { Marked, Renderer, type Tokens } from "marked";

// GitHub-flavoured Markdown, so tables and fenced code read as they do on GitHub.
const marked = new Marked({
  gfm: true,
  renderer: {
    link(token) {
      const html = Renderer.prototype.link.call(this, token);
      // Links to sections start with `#`, and app.ts scrolls to them. Every other link opens in a
      // new tab, so the playground keeps the schema being edited. This includes relative links in
      // descriptions, such as `spec.md`, which would otherwise replace the playground.
      return token.href.startsWith("#") ? html : html.replace("<a ", '<a target="_blank" rel="noopener" ');
    },
    // The same HTML as marked's own table, except for the first cell of each row of the body.
    table(token) {
      let header = "";
      for (const cell of token.header) header += this.tablecell(cell);
      let body = "";
      for (const row of token.rows) {
        let cells = "";
        for (const cell of row) cells += cells === "" ? nameCell(this, cell) : this.tablecell(cell);
        body += this.tablerow({ text: cells });
      }
      if (body !== "") body = `<tbody>${body}</tbody>`;
      return `<table>\n<thead>\n${this.tablerow({ text: header })}</thead>\n${body}</table>\n`;
    },
  },
});

/** The HTML for `markdown`, which the Docs generator writes.
 *
 * The HTML can be shown as it is: the Docs generator writes the Markdown from the schema so that it
 * cannot add tags or links that run code (see Markdown.scala), and marked escapes the rest.
 *
 * The content of the first cell of each table row, which is usually an element name, is put in a
 * `<span class="md-name">`, with `<wbr>` between its words (see `.md-name` in styles.css). */
export function renderMarkdown(markdown: string): string {
  return marked.parse(markdown, { async: false });
}

/** The HTML for `cell`, the first cell of a table row. */
function nameCell(renderer: Renderer, cell: Tokens.TableCell): string {
  const name = addWordBreaks(renderer.parser.parseInline(cell.tokens));
  const td = cell.align ? `<td align="${cell.align}">` : "<td>";
  return `${td}<span class="md-name">${name}</span></td>\n`;
}

// Where a name can go onto the next line: before a capital letter that follows a small letter or
// a digit, as in `RegisteredShare`, and after `_`, as in `share_class`.
const WORD_START = /(?<=[a-z0-9])(?=[A-Z])|(?<=_)(?=.)/g;

// A tag or a character reference, such as `&amp;`, which must stay as it is.
const MARKUP = /(<[^>]*>|&[#0-9A-Za-z]+;)/;

/** `html` with a `<wbr>` between the words of its text, where the browser can start a new line. */
export function addWordBreaks(html: string): string {
  const parts = html.split(MARKUP);
  // `split` puts the text at the even indexes, and the markup between them at the odd ones.
  for (let i = 0; i < parts.length; i += 2) parts[i] = parts[i]!.replace(WORD_START, "<wbr>");
  return parts.join("");
}

/** The documentation as sections, for the page view to update only the sections that changed.
 *
 * In one document, the Docs generator starts the page of each element with a line `<a id="…">`, its
 * anchor. Markdown from the schema cannot start such a line, as the Docs generator writes a
 * zero-width space after its `<` (see Markdown.scala). So the document splits into the index, before
 * the first anchor, and one section for each element, keyed by its anchor. Each section starts after
 * a blank line, and the Docs generator closes any code block that it opens, so each section is
 * Markdown of its own. */
export interface PagePatch {
  /** The key of each section, in order. The index has the key "". */
  keys: string[];
  /** The revision of each section, in the order of `keys`. */
  revs: number[];
  /** The HTML of each section whose revision is newer than the one that the page asked with. */
  html: Map<string, string>;
  /** The number of lines of Markdown of each section in `html`, for the height that the page view
   * gives it until it is laid out. */
  lines: Map<string, number>;
  /** The newest revision of any section, which the page asks with once it shows this patch. */
  rev: number;
}

/** The sections of the last document, which `update` compares the next one with. */
export class PageSections {
  private sections = new Map<string, Section>();
  private rev = 0;

  /** The patch from the sections that the page shows, up to revision `known`, to the sections of
   * `markdown`. A section gets a new revision when its Markdown changes, and only then is it turned
   * into HTML. `known` is 0 for a page that shows nothing. */
  update(markdown: string, known: number): PagePatch {
    const next = new Map<string, Section>();
    const keys: string[] = [];
    const revs: number[] = [];
    const html = new Map<string, string>();
    const lines = new Map<string, number>();
    let start = 0;
    while (start < markdown.length || keys.length === 0) {
      const anchor = markdown.indexOf('\n<a id="', start);
      const end = anchor < 0 ? markdown.length : anchor + 1;
      const text = markdown.slice(start, end);
      let key = text.startsWith('<a id="') ? text.slice(7, text.indexOf('"', 7)) : "";
      // Anchors are unique, but a key is kept unique even if they were not.
      if (next.has(key)) key = `${key}\u0000${keys.length}`;
      let section = this.sections.get(key);
      if (section?.markdown !== text) {
        section = { markdown: text, html: renderMarkdown(text), lines: countLines(text), rev: ++this.rev };
      }
      next.set(key, section);
      keys.push(key);
      revs.push(section.rev);
      if (section.rev > known) {
        html.set(key, section.html);
        lines.set(key, section.lines);
      }
      start = end;
    }
    this.sections = next;
    return { keys, revs, html, lines, rev: this.rev };
  }
}

/** A section of the last document, with its HTML and the revision it got when it last changed. */
interface Section {
  markdown: string;
  html: string;
  lines: number;
  rev: number;
}

/** The number of lines of `text`. */
function countLines(text: string): number {
  let lines = 1;
  for (let i = text.indexOf("\n"); i >= 0; i = text.indexOf("\n", i + 1)) lines++;
  return lines;
}
