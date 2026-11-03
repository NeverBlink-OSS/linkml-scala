// Turns the Markdown of the Docs target into HTML for the playground's page view. It runs in the
// generation worker (see worker.ts), so the page only has to check and show the HTML.
import { Marked, type Renderer, type Tokens } from "marked";

// GitHub-flavoured Markdown, so tables and fenced code read as they do on GitHub.
const marked = new Marked({
  gfm: true,
  renderer: {
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

/** The HTML for `markdown`.
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
