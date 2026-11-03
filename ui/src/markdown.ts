// Turns Markdown into HTML for the playground's page view. Bundled into dist/markdown by build.mjs
// and imported by app.ts on first use only, like Mermaid.
import DOMPurify from "dompurify";
import { Marked } from "marked";

// GitHub-flavoured Markdown, so tables and fenced code read as they do on GitHub.
const marked = new Marked({ gfm: true });

// Every link that doesn't go to a section of the page opens in a new tab, so the playground keeps
// the schema being edited. This includes relative links in descriptions, such as `spec.md`, which
// would otherwise replace the playground. Links to sections start with `#`, and app.ts scrolls to
// them.
DOMPurify.addHook("afterSanitizeAttributes", (node) => {
  const href = node.tagName === "A" ? node.getAttribute("href") : null;
  if (href !== null && !href.startsWith("#")) {
    node.setAttribute("target", "_blank");
    node.setAttribute("rel", "noopener");
  }
});

/** The page for `markdown`, with anything that could run code removed.
 *
 * Schema descriptions are copied into the documentation as they are, and Markdown allows raw HTML.
 * A shared playground link can carry any schema, so a description could hold a `<script>` or an
 * `onerror` attribute. DOMPurify keeps only plain markup, as GitHub does.
 *
 * DOMPurify returns the nodes it has checked, rather than turning them back into HTML, which the
 * page would then have to parse a second time.
 *
 * The content of each first-column cell, which is usually an element name, is put in a
 * `<span class="md-name">` with `<wbr>` between its words (see `.md-name` in styles.css). */
export function renderMarkdown(markdown: string): DocumentFragment {
  const html = marked.parse(markdown, { async: false });
  const page = DOMPurify.sanitize(html, { RETURN_DOM_FRAGMENT: true });
  for (const cell of page.querySelectorAll("td:first-child")) {
    addWordBreaks(cell);
    const name = document.createElement("span");
    name.className = "md-name";
    name.append(...cell.childNodes);
    cell.append(name);
  }
  return page;
}

// Where a name can go onto the next line: before a capital letter that follows a small letter or
// a digit, as in `RegisteredShare`, and after `_`, as in `share_class`.
const WORD_START = /(?<=[a-z0-9])(?=[A-Z])|(?<=_)(?=.)/;

/** Adds a `<wbr>` between the words of each name in `cell`, where the browser can start a new line. */
function addWordBreaks(cell: Element): void {
  const texts: Text[] = [];
  const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) texts.push(node as Text);
  for (const text of texts) {
    const words = text.data.split(WORD_START);
    if (words.length < 2) continue;
    const parts: Node[] = [];
    for (const word of words) {
      if (parts.length > 0) parts.push(document.createElement("wbr"));
      parts.push(document.createTextNode(word));
    }
    text.replaceWith(...parts);
  }
}
