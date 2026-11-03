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
